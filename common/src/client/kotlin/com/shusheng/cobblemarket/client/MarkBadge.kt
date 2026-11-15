package com.shusheng.cobblemarket.client

import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * 可疑标记数量角标（2026-11-15）：同一玩家被**两个及以上**监察者标记时，
 * 在标记图标右上角画一个数量 —— 一眼看出「不止一个人觉得他可疑」。
 *
 * - **≥2 才画**（1 条是常态，画个「1」纯属噪音）
 * - 数字右缘对齐图标右缘并向内 1px、底边贴图标上沿 —— 观感上是角标，不额外占行内空间
 * - 白色 + 阴影（压在红色图标上、落在深色面板上都读得清）
 * - 超过 99 显示「99+」（极端情况别把行撑宽）
 *
 * ⚠ 两处调用（快速功能面板行内 / 玩家总览行尾）都走这里 —— 分开写迟早不一致。
 */
fun drawMarkCountBadge(
    context: DrawContext,
    textRenderer: TextRenderer,
    count: Int,
    iconX: Int,
    iconY: Int,
    iconSize: Int,
) {
    if (count < 2) return
    val label = if (count > 99) "99+" else count.toString()
    val textW = textRenderer.getWidth(label)
    context.drawTextWithShadow(
        textRenderer, Text.literal(label),
        iconX + iconSize - textW + 1, iconY - 1, 0xFFFFFF
    )
}
