package com.shusheng.cobblemarket.screen

import net.minecraft.text.Text

/** 服务器配置 → **求购单配置**（2026-10-25）：第一行开关（默认开），其余为求购参数 */
class BuyOrderConfigScreen : MarketConfigScreenBase(
    Text.translatable("cobblemarket.op.buyorder_config")
) {
    protected override val toggleDefs = listOf(
        "cobblemarket.op.scfg_buyorder_enabled" to "buyOrderEnabled",
        "cobblemarket.op.scfg_buyorder_pokemon_enabled" to "buyOrderPokemonEnabled",
        "cobblemarket.op.scfg_buyorder_item_enabled" to "buyOrderItemEnabled",
    )

    /** 联动规则同拍卖场：总关 ⇒ 子都关；子开 ⇒ 总开 */
    protected override fun onToggleChanged(key: String, next: Boolean) {
        when (key) {
            "buyOrderEnabled" -> if (!next) {
                localToggles["buyOrderPokemonEnabled"] = false
                localToggles["buyOrderItemEnabled"] = false
            }
            "buyOrderPokemonEnabled", "buyOrderItemEnabled" -> if (next) {
                localToggles["buyOrderEnabled"] = true
            }
        }
    }
    protected override val numDefs = listOf(
        NumDef("cobblemarket.op.scfg_buyorder_fee", false) to "buyOrderFee",
        NumDef("cobblemarket.op.scfg_buyorder_days", true) to "buyOrderExpiry",
        NumDef("cobblemarket.op.scfg_max_buyorders", true) to "maxBuyOrders",
    )
}
