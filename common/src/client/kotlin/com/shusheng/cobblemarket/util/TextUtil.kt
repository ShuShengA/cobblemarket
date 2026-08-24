package com.shusheng.cobblemarket.util

import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

object TextUtil {
    // 截断到 maxWidth 像素：超宽时截断尾部加 "…"；未超宽原样返回。
    // 用于黑名单按钮/列表等固定宽度区域，防止无翻译物品的超长 key 原文溢出控件。
    fun truncateString(s: String, maxWidth: Int): String {
        val font = MinecraftClient.getInstance().textRenderer
        if (font.getWidth(s) <= maxWidth) return s
        var end = s.length - 1
        while (end > 0 && font.getWidth(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }

    fun truncateText(t: Text, maxWidth: Int): Text = Text.literal(truncateString(t.string, maxWidth))

    // IV 显示：极限特训过（hyper trained）时显示「真实值（特训值）」，未特训只显示真实值。
    // 用于市场/拍卖/上架等界面的个体值展示，与 Cobblemon 队伍详情格式一致。
    fun ivText(real: Int, hyperTrained: Int): String =
        if (hyperTrained >= 0) "$real（$hyperTrained）" else "$real"

    // 选中标记文本：黑色 ●（按钮底色为白色，白色 ● 对比度不足）+ 白色标签。
    // 供 tab 按钮与展开列表的选中项使用。
    // 注意：子文本会继承父节点 Style，标签必须显式白色，否则跟着 ● 一起变黑
    fun selectedText(label: String): Text =
        Text.literal("● ").setStyle(net.minecraft.text.Style.EMPTY.withColor(0x000000))
            .append(Text.literal(label).setStyle(net.minecraft.text.Style.EMPTY.withColor(0xFFFFFF)))
}
