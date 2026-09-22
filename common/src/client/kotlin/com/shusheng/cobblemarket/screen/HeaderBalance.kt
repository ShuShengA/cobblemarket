package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.BalanceCache
import com.shusheng.cobblemarket.client.formatPriceShortLong
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * 面板**左上角的余额行**。它与标题挤在同一行（标题居中、余额贴左缘），所以余额一长就压到标题上 ——
 * 2026-09-21 用户先报「和 tab 行重合」、更早还报过压标题，故按可用宽度**三级降级**：
 *
 * 1. 完整数字（[BalanceCache.balance]，带千分位）
 * 2. 放不下 → 换成 k/M/B 缩写（[formatPriceShortLong] 读原始数值）
 * 3. 还放不下 → 截断兜底（[TextUtil.truncateStyled]，保分段样式）
 *
 * ⚠ **各界面标题长度不同、左缘内缩也不同**（求购单面板窄且边框宽），所以可用宽必须按
 * [titleWidth] 与 [x] 现算，不要在界面里写死。别退回「直接 draw 一行 Text」的写法。
 *
 * @param centerX 界面中心（标题居中处，用来反推标题左缘）
 * @param x 余额行左缘（各界面内缩不同：常例 `leftX + 4`，求购单 `leftX + PANEL_BORDER_X + 2`）
 * @param titleWidth 同行那个标题的像素宽
 * @param y 基线行号，默认 20（与标题同行）
 */
fun drawHeaderBalance(
    context: DrawContext,
    textRenderer: TextRenderer,
    centerX: Int,
    x: Int,
    titleWidth: Int,
    y: Int = 20,
) {
    val balRaw = BalanceCache.balance
    if (balRaw.isEmpty()) return

    // 标题左缘再留 6px 间隙，避免贴到字上
    val avail = centerX - titleWidth / 2 - 6 - x
    val balUnit = inlineCurrencyUnit()
    fun balLine(amount: String) = Text.translatable(
        "cobblemarket.gui.balance",
        Text.literal("$amount $balUnit").formatted(Formatting.GOLD),
    )
    val balFull = balLine(balRaw)
    val balShown = if (textRenderer.getWidth(balFull) <= avail) balFull
    else balLine(formatPriceShortLong(BalanceCache.balanceRaw))

    context.drawTextWithShadow(textRenderer, TextUtil.truncateStyled(balShown, avail), x, y, 0xFFFFFF)
}
