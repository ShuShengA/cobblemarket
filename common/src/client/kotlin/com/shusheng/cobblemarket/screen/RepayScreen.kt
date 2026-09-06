package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.network.RepayEntry
import com.shusheng.cobblemarket.network.RepayListDataPayload
import com.shusheng.cobblemarket.network.RequestRepayListPayload
import com.shusheng.cobblemarket.network.RequestRepayPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/**
 * 还款柜台：未结清贷款列表（面板三件套照 LoanHistoryScreen，行按钮照 BuyOrderScreen）。
 * 行显示 贷款号/期数/剩余本金/状态 + 「还款」按钮 → 确认弹窗（黄金模板）：
 * 本期应还与结清总额由服务端实算随列表下发，弹窗只展示，执行时服务端重算实扣。
 */
class RepayScreen : Screen(Text.translatable("cobblemarket.repay.title")) {

    private val panelWidth = 296
    private val rowHeight = 24

    private var entries = listOf<RepayEntry>()
    private var loaded = false
    private var scrollOffset = 0

    private var backButton: NineSliceButton? = null
    private val rowButtons = mutableListOf<NineSliceButton>()

    // ── 还款确认弹窗（黄金模板照 AdminAuctionScreen.openCancelDialog） ──
    private var dialogEntry: RepayEntry? = null
    private var dialogPeriodButton: NineSliceButton? = null
    private var dialogSettleButton: NineSliceButton? = null
    private var dialogCancelButton: NineSliceButton? = null

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        backButton = NineSliceButton(
            leftX + panelWidth - 50, 18, 50, 16,
            Text.literal(""),
            { client?.setScreen(MeowthBankScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        rebuildRowButtons()

        if (!loaded) {
            sendToServer(RequestRepayListPayload())
            loaded = true
        }
    }

    fun onRepayListData(payload: RepayListDataPayload) {
        entries = payload.entries
        rebuildRowButtons()
    }

    private fun rebuildRowButtons() {
        rowButtons.forEach(::remove)
        rowButtons.clear()
        // 弹窗打开时行按钮保持隐藏（还款成功后服务端回发列表触发的重建不得覆盖弹窗）
        if (dialogEntry != null) return
        val leftX = width / 2 - panelWidth / 2
        val startY = getListStartY()
        val maxVisible = getMaxVisibleRows()
        entries.drop(scrollOffset).take(maxVisible).forEachIndexed { i, _ ->
            val y = startY + i * rowHeight
            val btn = NineSliceButton(
                leftX + panelWidth - 54, y + 4, 44, 16,
                Text.translatable("cobblemarket.repay.row_button"),
                { openRepayDialog(entries[scrollOffset + i]) }
            )
            rowButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    private fun getListStartY() = 48

    private fun getMaxVisibleRows() = maxOf(3, (height - 48 - getListStartY()) / rowHeight)

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

        // 行背景（照 BuyOrderScreen hover 惯例）
        val leftX = width / 2 - panelWidth / 2
        val startY = getListStartY()
        val maxVisible = getMaxVisibleRows()
        repeat(minOf(maxVisible, maxOf(0, entries.size - scrollOffset))) { i ->
            val rowY = startY + i * rowHeight
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, 0, ROW_BACKGROUND_TEX_H)
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        if (dialogEntry != null) {
            return
        }

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.repay.title").formatted(Formatting.GOLD),
            width / 2, 14, 0xFFFFFF
        )

        val startY = getListStartY()
        val panelHalf = panelWidth / 2
        val leftX = width / 2 - panelHalf
        // 按钮行与第一条记录之间的分割线（照 LoanHistoryScreen）
        context.fill(leftX, 41, leftX + panelWidth, 42, 0xFF555555.toInt())
        val maxVisible = getMaxVisibleRows()

        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, entries.size - maxVisible))

        if (entries.isEmpty() && loaded) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.repay.empty").formatted(Formatting.GRAY),
                width / 2, startY + 20, 0xFFFFFF
            )
        }

        entries.drop(scrollOffset).take(maxVisible).forEachIndexed { i, e ->
            val y = startY + i * rowHeight
            var x = leftX + 5
            context.drawTextWithShadow(textRenderer, "#${e.id}", x, y + 8, 0xFFFFFF)
            x += textRenderer.getWidth("#${e.id}") + 4
            val periods = Text.translatable("cobblemarket.loan_history.periods", e.periodsPaid, e.periodsTotal)
            context.drawTextWithShadow(textRenderer, periods, x, y + 8, 0x888888)
            x += textRenderer.getWidth(periods) + 6
            val statusText = Text.translatable("cobblemarket.loan_history.status.${e.status}").string
            context.drawTextWithShadow(textRenderer, statusText, x, y + 8, statusColor(e.status))
            x += textRenderer.getWidth(statusText) + 6
            val remainingText = Text.translatable(
                "cobblemarket.repay.remaining",
                formatPriceLong(e.remaining),
                inlineCurrencyUnit()
            ).string
            // 剩余本金右对齐到行按钮左侧（照列表界面惯例）；空间不足退回 x 截断绘制
            val btnLeftX = leftX + panelWidth - 54
            val remX = btnLeftX - 5 - textRenderer.getWidth(remainingText)
            val drawX = if (remX > x) remX else x
            context.drawTextWithShadow(
                textRenderer,
                TextUtil.truncateString(remainingText, btnLeftX - 5 - drawX),
                drawX, y + 8, 0x55FFFF
            )
        }

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
        else -> 0xFFFFFF
    }

    // ── 还款确认弹窗 ──

    private fun openRepayDialog(entry: RepayEntry) {
        dialogEntry = entry
        // 隐藏下层控件（弹窗打开期间不可交互；closeRepayDialog 的 init 重建会恢复）
        backButton?.visible = false
        rowButtons.forEach { it.visible = false }

        val centerX = width / 2
        val dialogY = height / 2 - 80

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染，照搬取消弹窗）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderDialogBackground(context)
            }
        })

        dialogPeriodButton = NineSliceButton(
            centerX - 88, dialogY + 122, 56, 20,
            Text.translatable("cobblemarket.repay.pay_period"),
            { sendRepay(false) }
        )
        addDrawableChild(dialogPeriodButton)
        dialogSettleButton = NineSliceButton(
            centerX - 28, dialogY + 122, 56, 20,
            Text.translatable("cobblemarket.repay.settle"),
            { sendRepay(true) }
        )
        addDrawableChild(dialogSettleButton)
        dialogCancelButton = NineSliceButton(
            centerX + 32, dialogY + 122, 56, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeRepayDialog() }
        )
        addDrawableChild(dialogCancelButton)
    }

    private fun closeRepayDialog() {
        dialogEntry = null
        dialogPeriodButton = null
        dialogSettleButton = null
        dialogCancelButton = null
        clearChildren()
        init()
    }

    private fun sendRepay(settle: Boolean) {
        val entry = dialogEntry ?: return
        sendToServer(RequestRepayPayload(entry.id, settle))
        closeRepayDialog()
    }

    private fun renderDialogBackground(context: DrawContext) {
        val entry = dialogEntry ?: return
        val centerX = width / 2
        val dW = 280
        val dH = 160
        val dialogX = centerX - dW / 2
        val dialogY = height / 2 - dH / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dW, dH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.repay.dialog_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF
        )

        val lineX = dialogX + 12
        var ly = dialogY + 40
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable(
                "cobblemarket.repay.dialog_loan_line",
                entry.id,
                entry.periodsPaid,
                entry.periodsTotal
            ),
            lineX, ly, 0x888888
        )
        ly += 18
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable(
                "cobblemarket.repay.dialog_period_due",
                formatPriceLong(entry.periodPrincipal + entry.periodInterest),
                inlineCurrencyUnit()
            ),
            lineX, ly, 0xFFFFFF
        )
        ly += 18
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable(
                "cobblemarket.repay.dialog_settle_total",
                formatPriceLong(entry.settleTotal),
                inlineCurrencyUnit()
            ),
            lineX, ly, 0x55FFFF
        )
        ly += 18
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable(
                "cobblemarket.repay.dialog_split",
                formatPriceLong(entry.periodPrincipal),
                formatPriceLong(entry.periodInterest)
            ),
            lineX, ly, 0x888888
        )
        if (entry.dueCount > 0) {
            ly += 18
            context.drawTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.repay.dialog_due_count", entry.dueCount).formatted(Formatting.RED),
                lineX, ly, 0xFFFFFF
            )
        }
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val dialog = dialogEntry
        super.resize(client, width, height)
        if (dialog != null) {
            dialogEntry = null
            openRepayDialog(dialog)
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val maxVisible = getMaxVisibleRows()
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, entries.size - maxVisible))
        rebuildRowButtons()
        return true
    }

    override fun shouldPause() = false
}
