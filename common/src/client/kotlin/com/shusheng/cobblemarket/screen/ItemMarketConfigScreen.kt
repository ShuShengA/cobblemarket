package com.shusheng.cobblemarket.screen

import net.minecraft.text.Text

/**
 * 服务器配置 → **物品市场配置**（2026-10-25）。
 *
 * 第一行是本功能的开关（默认开）；挂单有效期与待领取保留天数与精灵市场共用，
 * 两边显示的是同一个值（保存后服务端回发快照即同步）。
 */
class ItemMarketConfigScreen : MarketConfigScreenBase(
    Text.translatable("cobblemarket.op.item_market_config")
) {
    protected override val toggleDefs = listOf(
        "cobblemarket.op.scfg_item_market_enabled" to "itemMarketEnabled",
    )
    protected override val numDefs = listOf(
        NumDef("cobblemarket.op.scfg_item_fee", false) to "itemFee",
        NumDef("cobblemarket.op.scfg_max_items", true) to "maxItemListings",
        NumDef("cobblemarket.op.scfg_listing_days", true) to "listingDays",
        NumDef("cobblemarket.op.scfg_pending_days", true) to "pendingDays",
    )
}
