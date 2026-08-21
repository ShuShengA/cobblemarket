package com.shusheng.cobblemarket.client

import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * 货币显示名：payload 的 currencyName 字段，物品模式下发的是物品 ID，
 * 客户端按玩家自己的语言渲染物品名；非 ID 值（如 "PokéDollars"）原样显示。
 * 服务端语言恒为 en_us 且受资源环境影响，货币名必须在客户端渲染。
 */
fun displayCurrency(raw: String): String {
    if (raw == com.shusheng.cobblemarket.config.CurrencyHandler.POKEDOLLARS_KEY) {
        return Text.translatable(raw).string
    }
    val id = Identifier.tryParse(raw) ?: return raw
    val item = Registries.ITEM.get(id)
    val air = Registries.ITEM.get(Identifier.of("minecraft", "air"))
    if (item == air) return raw
    val name = item.name.string
    return if (name == item.translationKey) id.path else name
}

/** 弹窗/悬停统一入口：从余额包缓存取当前货币（物品模式自动渲染成物品中文名） */
fun displayActiveCurrency(): String = displayCurrency(
    BalanceCache.currencyName.ifEmpty { com.shusheng.cobblemarket.config.CurrencyHandler.POKEDOLLARS_KEY }
)
