package com.shusheng.cobblemarket.currency

import net.minecraft.text.Text

/**
 * 自定义商店的**共享文案入口** —— 命令侧（`/market shop ...`）与管理界面网络层共用。
 *
 * ⚠ 以前这两个映射各写在 `MarketCommands` 里；管理界面也要用同一批错误/类型文案，
 *   再复制一份必然漂移（本项目反复踩过「两处各写一遍」的坑）。
 */
fun customShopTypeText(type: CustomShopType): Text = Text.translatable(
    when (type) {
        CustomShopType.POKEMON -> "cobblemarket.shop.type.pokemon"
        CustomShopType.ITEM -> "cobblemarket.shop.type.item"
        CustomShopType.BOTH -> "cobblemarket.shop.type.both"
    }
)

/**
 * 错误码 → 玩家语言提示。
 *
 * [args] 给带占位符的词条用（NOT_FOUND 的 id、ALREADY_BOUND 的实体名 + 店名、
 * INVALID_TYPE 的原值）；无占位符的错误不传。
 */
fun customShopErrorText(error: CustomShopError, vararg args: Any): Text {
    // 带 %s 的词条如果没给参数，退回无参数版 —— 别让界面上出现裸的 %s
    if (args.isEmpty()) {
        when (error) {
            CustomShopError.NOT_FOUND -> return Text.translatable("cobblemarket.shop.err.not_found_short")
            CustomShopError.ALREADY_BOUND -> return Text.translatable("cobblemarket.shop.err.already_bound_short")
            else -> Unit
        }
    }
    val key = when (error) {
        CustomShopError.INVALID_ID -> "cobblemarket.shop.err.invalid_id"
        CustomShopError.EXISTS -> "cobblemarket.shop.err.exists"
        CustomShopError.TOO_MANY -> "cobblemarket.shop.err.too_many"
        CustomShopError.INVALID_NAME -> "cobblemarket.shop.err.invalid_name"
        CustomShopError.NOT_FOUND -> "cobblemarket.shop.err.not_found"
        CustomShopError.ALREADY_BOUND -> "cobblemarket.shop.err.already_bound"
        CustomShopError.TOO_MANY_NPCS -> "cobblemarket.shop.err.too_many_npcs"
        CustomShopError.INVALID_TYPE -> "cobblemarket.shop.err.invalid_type"
        CustomShopError.NPC_NOT_FOUND -> "cobblemarket.shop.err.npc_not_found"
        CustomShopError.NPC_TOO_FAR -> "cobblemarket.shop.err.npc_too_far"
    }
    return if (args.isEmpty()) Text.translatable(key) else Text.translatable(key, *args)
}
