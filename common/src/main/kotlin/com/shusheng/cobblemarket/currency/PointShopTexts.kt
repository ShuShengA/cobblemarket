package com.shusheng.cobblemarket.currency

import net.minecraft.text.Text

/**
 * 充值订单的错误码 → 玩家语言文本。
 *
 * 命令侧（`MarketCommands`）与网络层（`PointsNetwork`）**共用这一份** ——
 * 两处各写一遍必然漂移（同一个失败在命令里说 A、在界面上说 B）。
 */
fun pointShopErrorText(error: PointShopError): Text = Text.translatable(
    when (error) {
        PointShopError.DISABLED -> "cobblemarket.points.shop.disabled"
        PointShopError.NO_TIER -> "cobblemarket.points.shop.no_tier"
        PointShopError.ALREADY_ACTIVE -> "cobblemarket.points.shop.already_active"
        PointShopError.TOO_MANY_ACTIVE -> "cobblemarket.points.shop.too_many_active"
        PointShopError.NOT_FOUND -> "cobblemarket.points.shop.not_found"
        PointShopError.NOT_OWNER -> "cobblemarket.points.shop.not_owner"
        PointShopError.BAD_STATUS -> "cobblemarket.points.shop.bad_status"
        PointShopError.EXPIRED -> "cobblemarket.points.shop.expired"
        PointShopError.GENERATE_FAILED -> "cobblemarket.points.shop.generate_failed"
    }
)
