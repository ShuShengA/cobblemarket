package com.shusheng.cobblemarket.client

/**
 * 市场总开关的客户端状态：MarketStatePayload 到达时更新（登录补发 + /market on|off 广播）。
 * 入口界面据此把交易入口置灰并提示。
 */
object MarketStateCache {
    var enabled: Boolean = true

    /** 待领取总数（精灵 + 物品；登录时由服务端推一次）——只在界面上显示用 */
    var pendingReturns: Int = 0

    /** 「离线收益」提示音的**待播时刻**（0 = 无）：登录推送发现有待办时设为 now + 2 秒，
     *  等世界加载完再响（免得和加载画面挤在一起）；tick 里到点播一次并清零。
     *  ⚠ 时机是**上线时**，不是打开市场时——开了市场界面玩家自己就能看到收款/待领取按钮和红点。 */
    var offlineRewardAt: Long = 0L
}
