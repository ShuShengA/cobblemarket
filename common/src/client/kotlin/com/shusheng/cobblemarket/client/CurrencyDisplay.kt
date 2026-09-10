package com.shusheng.cobblemarket.client

import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * 货币显示名：payload 的 currencyName 字段，物品模式下发的是物品 ID，
 * 客户端按玩家自己的语言渲染物品名；虚拟货币下发翻译 key
 * （Cobblemon Economy POKE=PokeDollars，Cobblemon Economy PCO=PokeCoins，CobbleDollars=CobbleDollars），客户端翻译后显示。
 * 服务端语言恒为 en_us 且受资源环境影响，货币名必须在客户端渲染。
 */
fun displayCurrency(raw: String): String {
    // 虚拟货币（Cobblemon Economy POKE/PCO、CobbleDollars、Impactor）统一显示 ₽（2026-08-27 用户拍板，不显示货币名）
    if (raw == com.shusheng.cobblemarket.config.CurrencyHandler.POKEDOLLARS_KEY ||
        raw == com.shusheng.cobblemarket.config.CurrencyHandler.POKECOINS_KEY ||
        raw == com.shusheng.cobblemarket.config.CurrencyHandler.COBBLEDOLLARS_KEY ||
        raw == com.shusheng.cobblemarket.config.CurrencyHandler.IMPACTOR_KEY
    ) {
        return "₽"
    }
    val id = Identifier.tryParse(raw) ?: return raw
    val item = Registries.ITEM.get(id)
    val air = Registries.ITEM.get(Identifier.of("minecraft", "air"))
    if (item == air) return raw
    val name = item.name.string
    return if (name == item.translationKey) id.path else name
}

/** 行内/弹窗金额单位：所有货币模式统一 ₽（2026-08-27 用户拍板：PCO 不再特殊显示 PCo）。 */
fun inlineCurrencyUnit(): String = "₽"

/** 悬停窗金额单位：物品货币显示物品名，虚拟货币 ₽（2026-08-27 拍板：悬停显物品名、行内显 ₽）。
 *  余额包未到达时 currencyName 为空串，此处兜底 ₽ —— 直接 displayCurrency("") 会返回空串（tryParse 得 null） */
fun activeCurrencyUnit(): String {
    val raw = BalanceCache.currencyName
    return if (raw.isEmpty()) "₽" else displayCurrency(raw)
}
