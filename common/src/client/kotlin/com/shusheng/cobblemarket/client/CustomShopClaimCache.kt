package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.network.CustomShopClaimStatusPayload

/**
 * **定期领取店的玩家周期状态缓存**（S2C 先落地，界面在不在都不丢）。
 *
 * 服务端在「打开领取店」与「每次领取后」下发；界面每帧读它来画「可领 N」与刷新倒计时。
 * ⚠ 按店 id 存：玩家可能先后看两家领取店，旧包不能串到新店上。
 */
object CustomShopClaimCache {

    private var shopId: String = ""
    private var nextResetAt: Long = 0L
    private var remaining: Map<String, Int> = emptyMap()

    fun accept(payload: CustomShopClaimStatusPayload) {
        shopId = payload.shopId
        nextResetAt = payload.nextResetAt
        remaining = payload.remaining
    }

    /** 清掉旧店的状态（换店 / 重新打开时用） */
    fun clear() {
        shopId = ""
        nextResetAt = 0L
        remaining = emptyMap()
    }

    /**
     * 这家店某一格还能领几个；没有状态（未收到 / 非领取店）返回 -1 = 未知。
     *
     * ⚠ 状态里查不到这个 entryId 时**按满额 [quota] 算**，不是 0：
     *   状态是打开商店那一刻拍的，之后服主新加的格子不在里面 —— 按 0 显示会变成
     *   「刚加的商品可领 0」（2026-10-23 用户报）。
     */
    fun remainingOf(shop: String, entryId: String, quota: Int): Int =
        if (shop == shopId) remaining[entryId] ?: quota else -1

    fun nextResetAtOf(shop: String): Long = if (shop == shopId) nextResetAt else 0L
}
