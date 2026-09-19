package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.network.PointRecordInfo

/**
 * 「我的兑换记录」的客户端缓存（[com.shusheng.cobblemarket.network.PointRecordsPayload] 的落地处）。
 *
 * ⚠ 必须缓存：包在**请求之后**才回来，界面开出来头几帧是空的 ——
 * 不落地就表现为「每次进来都先闪一下『还没有兑换记录』」，返回再进来又闪一次
 * （界面每次都是新实例）。缓存住 = 先显示上次的、新数据到了再刷新。
 *
 * ⚠ **请求节流的时间戳也放这里，不能放界面字段**：界面每次重建（resize / 返回再进）都是新实例，
 * 节流状态跟着实例走会漏判 —— 两次请求挤进服务端 500ms 窗口内，后一次被静默丢弃
 * （见 memory 的 reference-list-request-throttle）。
 */
object PointsRecordCache {

    /** 记录（**最新在前**，服务端已排好序） */
    var entries: List<PointRecordInfo> = emptyList()

    /** 收到过至少一次快照（区分「还没拉到」与「拉到了但没有」） */
    var loaded: Boolean = false

    /** 上次发请求的时刻（客户端侧节流窗口的一侧；另一侧是 [pending]） */
    var lastRequestAt: Long = 0L

    /** 有请求被窗口挡住、排队等 tick 补发 */
    var pending: Boolean = false
}
