package com.shusheng.cobblemarket.screen

import net.minecraft.text.Text

/** 服务器配置 → **拍卖场配置**（2026-10-25）：第一行开关（默认开），其余为拍卖参数 */
class AuctionConfigScreen : MarketConfigScreenBase(
    Text.translatable("cobblemarket.op.auction_config")
) {
    protected override val toggleDefs = listOf(
        "cobblemarket.op.scfg_auction_enabled" to "auctionEnabled",
        "cobblemarket.op.scfg_auction_pokemon_enabled" to "auctionPokemonEnabled",
        "cobblemarket.op.scfg_auction_item_enabled" to "auctionItemEnabled",
    )

    /**
     * 联动（2026-10-25 用户定）：总开关关闭 ⇒ 精灵 / 物品子开关都关闭；
     * 任一子开关打开 ⇒ 总开关自动打开。总开关打开时两个子开关都关是允许的（此时拍卖场没有可用内容）。
     */
    protected override fun onToggleChanged(key: String, next: Boolean) {
        when (key) {
            "auctionEnabled" -> if (!next) {
                localToggles["auctionPokemonEnabled"] = false
                localToggles["auctionItemEnabled"] = false
            }
            "auctionPokemonEnabled", "auctionItemEnabled" -> if (next) {
                localToggles["auctionEnabled"] = true
            }
        }
    }
    protected override val numDefs = listOf(
        NumDef("cobblemarket.op.scfg_auction_fee", false) to "auctionFee",
        NumDef("cobblemarket.op.scfg_auction_min_bid", true) to "auctionMinBid",
        NumDef("cobblemarket.op.scfg_anti_snipe", true) to "antiSnipe",
        NumDef("cobblemarket.op.scfg_max_auctions", true) to "maxAuctions",
        NumDef("cobblemarket.op.scfg_auction_durations", false) to "auctionDurations",
    )
}
