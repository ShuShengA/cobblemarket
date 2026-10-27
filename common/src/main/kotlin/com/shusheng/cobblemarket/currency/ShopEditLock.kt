package com.shusheng.cobblemarket.currency

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 商店「正在被编辑」的临时锁（**不持久化**）。
 *
 * 服主 / 持 `points.shop` 的人打开编辑窗后：
 *  · 其他玩家在**这家店**的购买窗里，购买按钮置灰、点击只响失败音（客户端）；
 *  · [PointMarketService.buy] 直接拒绝（服务端兜底，防伪造包）；
 *  · 全部编辑者关窗 / 断线后立即解除。
 *
 * 按**商店**记（不是按格子）——「编辑期间整家店暂停购买」（用户 2026-10-26 定）。
 *
 * ⚠ **只在内存里**：服务器重启即清空（编辑窗随连接一起没了，没有需要持久化的状态）。
 * ⚠ **超时兜底**：客户端异常（没发「结束编辑」也没断开）时 15 分钟后自动失效；
 *   正常关窗 / 断线走 [end] / [endAll]，不会等到超时。编辑窗开着时客户端每 30 秒发一次
 *   `editing=true` 当心跳（[begin] 同时刷新时间）。
 * ⚠ 断线回调在**网络线程**，其余调用都在主线程 ⇒ 外层与内层都用 [ConcurrentHashMap]。
 */
object ShopEditLock {
    private const val TIMEOUT_MS = 15 * 60 * 1000L

    /** 店 id → (编辑者 uuid → 最后一次心跳时刻) */
    private val editors = ConcurrentHashMap<String, ConcurrentHashMap<UUID, Long>>()

    /** 开始编辑（也可当心跳）。返回 true = 这家店从「可购买」变成「编辑中」（调用方据此广播）。 */
    fun begin(shopId: String, player: UUID): Boolean {
        prune()
        val map = editors.computeIfAbsent(shopId) { ConcurrentHashMap() }
        val wasLocked = map.isNotEmpty()
        map[player] = System.currentTimeMillis()
        return !wasLocked
    }

    /** 结束编辑。返回 true = 最后一个编辑者离开、这家店恢复可购买（调用方据此广播）。 */
    fun end(shopId: String, player: UUID): Boolean {
        val map = editors[shopId] ?: return false
        map.remove(player)
        if (map.isNotEmpty()) return false
        editors.remove(shopId, map)
        return true
    }

    /** 玩家断线：清掉他在所有店的编辑状态；返回因此解除的店 id（调用方给这些店广播解锁）。 */
    fun endAll(player: UUID): Set<String> {
        val released = mutableSetOf<String>()
        editors.forEach { (shopId, map) ->
            if (map.remove(player) != null && map.isEmpty() && editors.remove(shopId, map)) {
                released += shopId
            }
        }
        return released
    }

    /** 这家店现在是否「编辑中」；顺手清掉超时的编辑者。 */
    fun isLocked(shopId: String): Boolean {
        prune()
        return editors[shopId]?.isNotEmpty() == true
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - TIMEOUT_MS
        editors.forEach { (shopId, map) ->
            map.entries.removeIf { it.value < cutoff }
            if (map.isEmpty()) editors.remove(shopId, map)
        }
    }
}
