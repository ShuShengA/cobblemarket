package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.network.LoanHistoryDataPayload
import com.shusheng.cobblemarket.network.LoanHistoryEntry
import com.shusheng.cobblemarket.network.RequestLoanHistoryPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 借款历史（showAll=false，我的借贷流水）/ 全部借款历史（showAll=true，仅 OP 可达，全服借贷流水审计）。
 * 照搬 HistoryScreen：面板三件套 + 单行文本流 + 滚动 + 空态。
 */
class LoanHistoryScreen(private val showAll: Boolean = false) :
    Screen(Text.translatable(if (showAll) "cobblemarket.loan_history.all_title" else "cobblemarket.loan_history.title")) {

    private val panelWidth = 296
    private var entries = listOf<LoanHistoryEntry>()
    private var loaded = false
    private var scrollOffset = 0

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        // 返回按钮（右上，照 HistoryScreen）
        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 18, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(MeowthBankScreen()) }
        ))

        if (!loaded) {
            sendToServer(RequestLoanHistoryPayload(showAll))
            loaded = true
        }
    }

    fun onLoanHistoryData(payload: LoanHistoryDataPayload) {
        entries = payload.entries
    }

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int, sliceH: Int = 16) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val panelLeft = width / 2 - 160
        val panelTop = 2
        val panelBottom = height - 32
        val sliceH = 16

        val top = Identifier.of("cobblemarket", "textures/gui/market_panel_top.png")
        val mid = Identifier.of("cobblemarket", "textures/gui/market_panel_middle.png")
        val bot = Identifier.of("cobblemarket", "textures/gui/market_panel_bottom.png")

        drawPanelSlice(context, top, panelLeft, panelTop)
        var y = panelTop + sliceH
        while (y < panelBottom - sliceH) {
            drawPanelSlice(context, mid, panelLeft, y, minOf(sliceH, panelBottom - sliceH - y))
            y += sliceH
        }
        drawPanelSlice(context, bot, panelLeft, panelBottom - sliceH)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable(if (showAll) "cobblemarket.loan_history.all_title" else "cobblemarket.loan_history.title")
                .formatted(Formatting.GOLD),
            width / 2, 14, 0xFFFFFF
        )

        val startY = 48
        val rowHeight = 18
        val dateFormat = SimpleDateFormat("MM-dd HH:mm")
        val panelHalf = panelWidth / 2
        // 按钮行与第一条记录之间的分割线（按钮底 34、列表顶 48 → 线在正中 41）
        context.fill(width / 2 - panelHalf, 41, width / 2 + panelHalf, 42, 0xFF555555.toInt())
        val maxVisible = maxOf(3, (height - 48 - startY) / rowHeight)

        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, entries.size - maxVisible))

        if (entries.isEmpty() && loaded) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.loan_history.empty").formatted(Formatting.GRAY),
                width / 2, startY + 20, 0xFFFFFF
            )
        }

        val visibleEntries = entries.drop(scrollOffset).take(maxVisible)
        visibleEntries.forEachIndexed { i, e ->
            val y = startY + i * rowHeight
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, width / 2 - panelHalf, y, panelWidth, rowHeight, 0, ROW_BACKGROUND_TEX_H)
            val time = dateFormat.format(Date(e.timestamp))
            val statusText = Text.translatable("cobblemarket.loan_history.status.${e.status}").string

            val leftX = width / 2 - panelHalf
            var x = leftX + 5
            context.drawTextWithShadow(textRenderer, time, x, y + 4, 0x888888)
            x += textRenderer.getWidth(time) + 6
            val statusLabel = "[$statusText]"
            context.drawTextWithShadow(textRenderer, statusLabel, x, y + 4, statusColor(e.status))
            x += textRenderer.getWidth(statusLabel) + 6
            val owner = if (showAll) "${e.playerName} " else ""
            val source = Text.translatable("cobblemarket.loan_history.source.${e.source}").string
            val periodsText = Text.translatable("cobblemarket.loan_history.periods", e.periodsPaid, e.periodsTotal).string
            val middle = "$owner${formatPriceLong(e.principal)}·$periodsText($source)"
            // 剩余本金右对齐行尾（照列表界面惯例），中间部分按实际可用像素截断（truncateString 单位为像素）
            val pricePart = "| ${formatPriceLong(e.remaining)} ${inlineCurrencyUnit()}"
            val priceX = leftX + panelWidth - 5 - textRenderer.getWidth(pricePart)
            val middleMax = priceX - 6 - x
            context.drawTextWithShadow(textRenderer, TextUtil.truncateString(middle, middleMax), x, y + 4, 0xFFFFFF)
            context.drawTextWithShadow(textRenderer, pricePart, priceX, y + 4, 0x55FFFF)
        }

        // Scroll indicator
        if (entries.size > maxVisible) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible, entries.size)} / ${entries.size}",
                width / 2, height - 49, 0x888888
            )
        }
    }

    private fun statusColor(status: String): Int = when (status) {
        "ACTIVE" -> 0x55FF55
        "OVERDUE" -> 0xFF5555
        "BAD_DEBT" -> 0xAA0000
        "CLOSED" -> 0xAAAAAA
        else -> 0xFFFFFF
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val rowHeight = 18
        val maxVisible = maxOf(3, (height - 48 - 48) / rowHeight)
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, entries.size - maxVisible))
        return true
    }

    override fun shouldPause() = false
}
