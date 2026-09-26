package com.shusheng.cobblemarket.util

import com.shusheng.cobblemarket.network.sendSound
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 读请求冷却：Fabric 自定义网络包没有频率限制，恶意客户端可高频发包
 * 让主线程反复执行全量搜索。在包接收处（进入 server.execute 之前）按
 * "玩家 + 请求类型"做最小间隔限制，超限直接丢弃。玩家断线时清理条目。
 */
object RequestThrottle {
    const val READ_INTERVAL_MS = 500L

    /** 写操作冷却：购买/汇总领取涉及资产变动，正常操作频率低，与读操作同级 */
    const val WRITE_INTERVAL_MS = 500L

    /** 连续写操作冷却：取消/领取可批量连点，250ms 对人类连点无感，仍能挡住高频发包 */
    const val REPEAT_WRITE_INTERVAL_MS = 250L

    private val lastRequest = ConcurrentHashMap<String, Long>()

    fun allow(playerUuid: UUID, key: String, minIntervalMs: Long): Boolean {
        // 用 nanoTime（JVM 单调时钟）测冷却间隔：系统时钟被回拨时 wall-clock 的
        // 差值长期为负，会导致所有请求被误拒。
        val now = System.nanoTime()
        val minIntervalNanos = minIntervalMs * 1_000_000L
        val mapKey = "$playerUuid:$key"
        // CAS 循环而非 compute+返回值比较：
        // compute 的 remapping function 在竞争时会重试、且"保留旧值"与"写入 now"无法
        // 通过返回值区分（同毫秒并发时 last == now），会导致冷却判定被绕过。
        while (true) {
            val last = lastRequest[mapKey]
            if (last != null && now - last < minIntervalNanos) return false
            if (last == null) {
                if (lastRequest.putIfAbsent(mapKey, now) == null) return true
                // 竞争失败：其他线程刚写入，重试读取
            } else {
                if (lastRequest.replace(mapKey, last, now)) return true
                // 竞争失败：值已被改，重试读取
            }
        }
    }

    /**
     * 节流判定，**命中时给玩家一条提示 + 失败音**。
     *
     * ⚠ **静默丢弃会让玩家以为界面坏了**：2026-09-26 用户报「点购买后界面关了、没有任何反应」，
     *   查下来其中一条路径就是撞了这个冷却 —— 包被丢掉、玩家端**一点反馈都没有**。
     *   ⇒ **玩家主动点击触发的操作（写操作）一律走这个**；只读的列表请求仍用 [allow]
     *   （那边客户端有补发机制，被拒是常态，提示反而成了噪音）。
     *
     * 提示走**动作栏**（`overlay = true`）不刷聊天栏 —— 手快连点时不该糊满一屏。
     */
    fun allowNotify(player: ServerPlayerEntity, key: String, minIntervalMs: Long): Boolean {
        if (allow(player.uuid, key, minIntervalMs)) return true
        player.sendMessage(Text.translatable("cobblemarket.gui.too_fast").formatted(Formatting.RED), true)
        sendSound(player, "result_fail")
        return false
    }

    fun onDisconnect(playerUuid: UUID) {
        val prefix = "$playerUuid:"
        lastRequest.keys.removeIf { it.startsWith(prefix) }
    }
}
