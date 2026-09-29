package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.BalanceCache
import com.shusheng.cobblemarket.client.amountWithUnit
import com.shusheng.cobblemarket.client.formatPriceShortLong
import com.shusheng.cobblemarket.client.currencyUnitWidth
import com.shusheng.cobblemarket.client.drawCurrencyUnit
import com.shusheng.cobblemarket.currency.MarketCurrency
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
 * @param currency **这一页正在看哪个币种** —— 余额跟着它走：RP 页显示 RP 余额（图标），
 *   主货币页显示 `₽`。⚠ 别写死主货币：那会让 RP 页顶着一行 `₽` 余额，看着像"我的 RP 是这么多钱"
 * @param y 基线行号，默认 20（与标题同行）
 */
fun drawHeaderBalance(
    context: DrawContext,
    textRenderer: TextRenderer,
    centerX: Int,
    x: Int,
    titleWidth: Int,
    currency: MarketCurrency,
    y: Int = 20,
) {
    val isPoints = currency == MarketCurrency.POINTS
    // 两种币的余额在**不同的缓存字段**里，别混用
    val balRaw = if (isPoints) BalanceCache.pointsBalance else BalanceCache.balance
    if (balRaw.isEmpty()) return

    // 标题左缘再留 6px 间隙，避免贴到字上
    val avail = centerX - titleWidth / 2 - 6 - x
    // ⚠ 金额与货币单位**拆开画**（2026-09-29）：物品本位货币时单位是**物品图标**，拼不进字符串；
    //   RP 单位虽是字形，也走同一条统一路径（`drawCurrencyUnit` 内部按 `isPoints` 分派）。
    //   可用宽里先把「空格 + 单位」那一份让出来，否则单位会被截断/挤出标题左缘
    val unitGapW = textRenderer.getWidth(" ")
    val unitW = unitGapW + currencyUnitWidth(textRenderer, isPoints)
    fun balLine(amount: String) = Text.translatable(
        "cobblemarket.gui.balance",
        Text.literal(amount).formatted(Formatting.GOLD),
    )
    val balFull = balLine(balRaw)
    val balShown = if (textRenderer.getWidth(balFull) + unitW <= avail) balFull
    else balLine(formatPriceShortLong(if (isPoints) BalanceCache.pointsRaw else BalanceCache.balanceRaw))

    val drawn = context.drawTextWithShadow(
        textRenderer,
        TextUtil.truncateStyled(balShown, (avail - unitW).coerceAtLeast(0)), x, y, 0xFFFFFF,
    )
    drawCurrencyUnit(context, textRenderer, isPoints, drawn + unitGapW, y, 0xFFAA00)
}
