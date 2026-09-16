package com.shusheng.cobblemarket.client

/**
 * 市场总开关的客户端状态：MarketStatePayload 到达时更新（登录补发 + /market on|off 广播）。
 * 入口界面据此把交易入口置灰并提示。
 */
object MarketStateCache {
    var enabled: Boolean = true

    /** 待领取总数（精灵 + 物品；登录时由服务端推一次）——入口界面据此播「离线收益」提示音。
     *  ⚠ 待领**余额**不在这里，它在 [BalanceCache.pendingBalance]，入口界面本来就会拉。 */
    var pendingReturns: Int = 0

    /** 本次上线是否已播过「离线收益」提示音（收到登录推送时重置，一次上线只提示一次） */
    var offlineRewardNotified: Boolean = false
}
