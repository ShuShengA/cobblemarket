package com.shusheng.cobblemarket.screen

import net.minecraft.text.Text

/**
 * 服务器配置 → **精灵市场配置**（2026-10-25）。
 *
 * 第一行是本功能的开关（默认开）；蛋交易原本也在服务器配置主界面，一并搬到这里
 * （它只影响精灵交易，开→关走二次确认）。其余为精灵市场的费率 / 上限 / 共用天数。
 */
class PokemonMarketConfigScreen : MarketConfigScreenBase(
    Text.translatable("cobblemarket.op.pokemon_market_config")
) {
    protected override val toggleDefs = listOf(
        "cobblemarket.op.scfg_pokemon_market_enabled" to "pokemonMarketEnabled",
        "cobblemarket.op.scfg_egg_trading" to "eggTrading",
        // 悬停窗口显示能力值（2026-11-05）：默认关；只在悬停窗画六边形，购买/出价/详情弹窗不加
        "cobblemarket.op.scfg_hover_stats_chart" to "hoverStatsChart",
    )
    protected override val guardedToggles = setOf("eggTrading")
    protected override val numDefs = listOf(
        NumDef("cobblemarket.op.scfg_pokemon_fee", false) to "pokemonFee",
        NumDef("cobblemarket.op.scfg_max_pokemon", true) to "maxPokemonListings",
        NumDef("cobblemarket.op.scfg_listing_days", true) to "listingDays",
        NumDef("cobblemarket.op.scfg_pending_days", true) to "pendingDays",
    )
}
