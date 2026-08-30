package com.shusheng.cobblemarket.util

import net.minecraft.text.TextColor

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
}
