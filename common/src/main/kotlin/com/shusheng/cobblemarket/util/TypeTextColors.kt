package com.shusheng.cobblemarket.util

import net.minecraft.text.Text
import net.minecraft.text.TextColor
import net.minecraft.util.Formatting

/**
 * 属性类型 → 聊天文本颜色（与客户端 typeColor 映射一致，服务端聊天消息用——如拍卖播报的物种名属性色）。
 * 属性色是界面语言无关的视觉惯例，服务端构造 Text 时直接用 ARGB。
 */
object TypeTextColors {
    fun color(typeKey: String): TextColor = TextColor.fromRgb(
        when (typeKey.substringAfterLast(".").lowercase()) {
            "normal" -> 0xAAAA99; "fire" -> 0xFF4422; "water" -> 0x3399FF
            "electric" -> 0xFFCC33; "grass" -> 0x77CC55; "ice" -> 0x66CCFF
            "fighting" -> 0xBB5544; "poison" -> 0xAA5599; "ground" -> 0xDDBB55
            "flying" -> 0x8899FF; "psychic" -> 0xFF5599; "bug" -> 0xAABB22
            "rock" -> 0xBBAA66; "ghost" -> 0x6666BB; "dragon" -> 0x7766EE
            "dark" -> 0x775544; "steel" -> 0xAAAABB; "fairy" -> 0xFFAAFF
            else -> 0xFFFFFF
        }
    )

    /**
     * 精灵名的聊天展示文本：名字本体带主属性色，闪光再加前置金★。
     *
     * 服务端凡是「聊天消息里出现精灵名」都走这里（拍卖上架/成交播报、售出通知、分享……），
     * 免得各写一份、有的上色有的漏（2026-09-19 统一）。
     *
     * @param speciesText 名字本体（翻译 Text，客户端按玩家语言渲染）
     * @param primaryTypeKey 主属性 key；缺失/非法时 [color] 自己会落白色
     */
    fun speciesName(speciesText: Text, primaryTypeKey: String?, shiny: Boolean): Text =
        withShinyStar(
            Text.literal("").append(speciesText)
                .styled { it.withColor(color(primaryTypeKey ?: "cobblemon.type.normal")) },
            shiny
        )

    /**
     * 闪光精灵名加前置金★（非闪光原样返回）。
     *
     * 名字本体**已经带色**时用这个 —— 别再走 [speciesName] 重复上色：挂单/拍卖的
     * `speciesText()` 现在自己就带属性色，播报与通知拿到的都是带色名字。
     */
    fun withShinyStar(nameText: Text, shiny: Boolean): Text =
        if (shiny) Text.literal("").append(Text.literal("★ ").formatted(Formatting.GOLD)).append(nameText)
        else nameText
}
