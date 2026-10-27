package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.network.CustomShopCooldownStatusPayload

/**
 * **普通购买店的每玩家冷却状态缓存**（S2C 先落地，界面在不在都不丢）。
 *
 * 服务端在「打开普通自定义商店」与「每次购买成功后」下发；界面每帧读它来画倒计时、
 * 置灰购买按钮。⚠ 按店 id 存：玩家可能先后看两家店，旧包不能串到新店上。
 *
 * ⚠ 这里只存**正在生效的结束时刻**；格子自己的冷却配置值走 [ShopEntry.cooldownHours]
 *   与开店包里的整店值，不在这里重复。
 */
object CustomShopCooldownCache {

    private var shopId: String = ""
    private var shopUntil: Long = 0L
    private var slots: Map<String, Long> = emptyMap()

    fun accept(payload: CustomShopCooldownStatusPayload) {
        shopId = payload.shopId
        shopUntil = payload.shopUntil
        slots = payload.slots
    }

    /** 清掉旧店的状态（换店 / 重新打开时用） */
    fun clear() {
        shopId = ""
        shopUntil = 0L
        slots = emptyMap()
    }

    /** 整店冷却剩余毫秒；0 = 不在冷却（不是这家店 / 未收到状态也返回 0） */
    fun shopRemainingOf(shop: String, now: Long = System.currentTimeMillis()): Long =
        if (shop == shopId) (shopUntil - now).coerceAtLeast(0L) else 0L

    /** 这一格自己的独立冷却剩余毫秒；0 = 不在冷却 */
    fun slotRemainingOf(shop: String, entryId: String, now: Long = System.currentTimeMillis()): Long {
        if (shop != shopId) return 0L
        val until = slots[entryId] ?: return 0L
        return (until - now).coerceAtLeast(0L)
    }

    /** 这一格实际要等的剩余毫秒 = max(整店冷却, 本格冷却) */
    fun remainingOf(shop: String, entryId: String, now: Long = System.currentTimeMillis()): Long =
        maxOf(shopRemainingOf(shop, now), slotRemainingOf(shop, entryId, now))

    /** 这一格实际冷却的结束时刻（epoch ms）；0 = 不在冷却（界面倒计时用） */
    fun untilOf(shop: String, entryId: String): Long {
        if (shop != shopId) return 0L
        return maxOf(shopUntil, slots[entryId] ?: 0L)
    }
}
