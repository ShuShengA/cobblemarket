package com.shusheng.cobblemarket.client

import net.minecraft.registry.Registries
import net.minecraft.text.MutableText
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

/**
 * **一条挂单/订单的价格该怎么显示** —— 金额 + **这条单自己的币种**单位。
 *
 * ⚠ 凡是显示某条挂单价格的地方都走它，别各写各的 `if (isPoints)`：
 * 币种是**挂单自己的属性**（`ListingEntry.isPoints` / `ItemEntry.isPoints`），
 * 各写各的迟早漏一处，而漏的那处会把 RP 价格标成 ₽ —— 玩家照着错单位看价，属于误导。
 * 已经漏过的两个地方就是教训：购买确认弹窗（`EntryBadgeRenderer`）、待领取物品悬停窗。
 *
 * [amount] 传格式化好的金额串（行内缩写用 `formatPriceShort`、悬停窗 `formatPrice`、
 * 弹窗总额 `formatPriceLong`），这里只管拼单位。
 *
 * [inline] 区分主货币那一侧的单位写法（2026-08-27 拍板）：行内简写恒用 `₽`，
 * 悬停窗与弹窗用完整货币名（物品货币时是物品名）。RP 一侧两种都一样，走图标。
 *
 * ⚠ 返回 **MutableText 不是 Text**：`Text.formatted(...)` 只挂在 MutableText 上，
 * 调用方几乎都要 `.formatted(Formatting.GOLD)`，声明成只读的 Text 会满屏编译错误。
 */
fun amountWithUnit(amount: String, isPoints: Boolean, currencyName: String, inline: Boolean = false): MutableText =
    if (isPoints) Text.literal("$amount ").append(com.shusheng.cobblemarket.currency.CurrencyService.pointsIcon())
    else Text.literal("$amount ${if (inline) inlineCurrencyUnit() else displayCurrency(currencyName)}")
