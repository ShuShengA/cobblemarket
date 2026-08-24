package com.shusheng.cobblemarket.util

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

    fun onDisconnect(playerUuid: UUID) {
        val prefix = "$playerUuid:"
        lastRequest.keys.removeIf { it.startsWith(prefix) }
    }
}
