package com.shusheng.cobblemarket.client

import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * 货币显示名：payload 的 currencyName 字段，物品模式下发的是物品 ID，
 * 客户端按玩家自己的语言渲染物品名；虚拟货币下发翻译 key
 * （cobeco POKE=PokeDollars，cobeco PCO=PokeCoins，CobbleDollars=CobbleDollars），客户端翻译后显示。
 * 服务端语言恒为 en_us 且受资源环境影响，货币名必须在客户端渲染。
 */
fun displayCurrency(raw: String): String {
    if (raw == com.shusheng.cobblemarket.config.CurrencyHandler.POKEDOLLARS_KEY ||
        raw == com.shusheng.cobblemarket.config.CurrencyHandler.POKECOINS_KEY ||
        raw == com.shusheng.cobblemarket.config.CurrencyHandler.COBBLEDOLLARS_KEY
    ) {
        return Text.translatable(raw).string
    }
    val id = Identifier.tryParse(raw) ?: return raw
    val item = Registries.ITEM.get(id)
    val air = Registries.ITEM.get(Identifier.of("minecraft", "air"))
    if (item == air) return raw
    val name = item.name.string
    return if (name == item.translationKey) id.path else name
}

/** 行内/弹窗金额单位：PCO 模式 PCo（与 cobeco 游戏内一致），其余（PokeDollars/CobbleDollars/物品）统一 ₽。
 *  2026-08-24 起弹窗内金额行也统一用此单位（原 displayActiveCurrency 全名 PokeCoins 已弃用删除） */
fun inlineCurrencyUnit(): String = when (BalanceCache.currencyName) {
    com.shusheng.cobblemarket.config.CurrencyHandler.POKECOINS_KEY -> "PCo"
    else -> "₽"
}
