package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.network.ShopCardDiscountPayload

/**
 * **自己在当前商店的卡片购买折扣**（2026-10-28，S2C [ShopCardDiscountPayload]）。
 *
 * 打开商城时问服务端一次（[com.shusheng.cobblemarket.network.RequestShopCardDiscountPayload]），
 * 购买确认窗的「价格优惠」提示行读它。
 *
 * ⚠ 折扣按玩家各算各的（持卡不同、每家店可单独覆盖）⇒ **不能搭广播的商城快照下发**，
 *   所以自成一个往返，缓存也只跟一家店（换店 / 重开界面时重新请求）。
 */
object ShopCardDiscountCache {

    private var shopId: String = ""
    private var rate: Double = 0.0
    private var card: String = ""

    fun accept(payload: ShopCardDiscountPayload) {
        shopId = payload.shopId
        rate = payload.rate
        card = payload.card
    }

    fun clear() {
        shopId = ""
        rate = 0.0
        card = ""
    }

    /** 某家店的生效折扣率（0 = 没折扣 / 不是这家店 / 还没收到） */
    fun rateOf(shop: String): Double = if (shop == shopId) rate else 0.0

    /** 打折的卡种（`purple` / `black`）；空串 = 没折扣 */
    fun cardOf(shop: String): String = if (shop == shopId) card else ""
}
