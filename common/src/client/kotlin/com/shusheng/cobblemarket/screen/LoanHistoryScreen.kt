package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.network.LoanHistoryDataPayload
import com.shusheng.cobblemarket.network.LoanHistoryEntry
import com.shusheng.cobblemarket.network.RequestLoanHistoryPayload
import com.shusheng.cobblemarket.network.RequestRevokeBadDebtPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 借款历史（showAll=false，我的借贷流水）/ 全部借款历史（showAll=true，仅 OP 可达，全服借贷流水审计）。
 * 照搬 HistoryScreen：面板三件套 + 单行文本流 + 滚动 + 空态。
 * 批次 7：OP 全服流水行显示最近 IP（小号排查）；BAD_DEBT 行带「撤销」按钮（服主干预，与 /market loan clear 双入口）。
 */
class LoanHistoryScreen(private val showAll: Boolean = false) :
    Screen(Text.translatable(if (showAll) "cobblemarket.loan_history.all_title" else "cobblemarket.loan_history.title")) {

    private val panelWidth = 296
    private var entries = listOf<LoanHistoryEntry>()
    private var loaded = false
    private var scrollOffset = 0
    private val rowButtons = mutableListOf<NineSliceButton>()

    // 全部借款历史（OP）的「全部/坏账」tab（照 BuyOrderScreen tab 模式）
    private var showBadDebtOnly = false
    private val tabButtons = mutableListOf<NineSliceButton>()

    /** 显示列表：坏账 tab 只筛 BAD_DEBT（tab 仅 OP 全服流水可用） */
    private fun displayEntries(): List<LoanHistoryEntry> =
        if (showBadDebtOnly) entries.filter { it.status == "BAD_DEBT" } else entries

    // ── 撤销坏账二次确认弹窗（照蛋交易确认弹窗模板：5 秒冷静期 + 红白双色说明） ──
    private var revokeEntry: LoanHistoryEntry? = null
    private var revokeOpenedAt = 0L
    private var revokeConfirmButton: NineSliceButton? = null
    private var revokeCancelButton: NineSliceButton? = null

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        // 返回按钮（右上，照 HistoryScreen）
        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 18, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(MeowthBankScreen()) }
        ))

        rebuildTabButtons()
        rebuildRowButtons()

        if (!loaded) {
            sendToServer(RequestLoanHistoryPayload(showAll))
            loaded = true
        }
    }

    /** 全部/坏账 tab（仅 OP 全服流水）：分割线上方左侧并排，间距 4px；选中态黑圈在前（照拍卖场 tab，● 前缀走 NineSliceButton 黑点渲染） */
    private fun rebuildTabButtons() {
        tabButtons.forEach(::remove)
        tabButtons.clear()
        if (!showAll || client?.player?.hasPermissionLevel(2) != true) return
        val leftX = width / 2 - panelWidth / 2
        val tabY = 24
        val btn1 = NineSliceButton(
            leftX + 5, tabY, 50, 14,
            Text.literal((if (!showBadDebtOnly) "● " else "") + Text.translatable("cobblemarket.loan_history.tab_all").string),
            { switchTab(false) }
        )
        val btn2 = NineSliceButton(
            leftX + 59, tabY, 50, 14,
            Text.literal((if (showBadDebtOnly) "● " else "") + Text.translatable("cobblemarket.loan_history.tab_bad_debt").string),
            { switchTab(true) }
        )
        tabButtons.add(btn1)
        tabButtons.add(btn2)
        addDrawableChild(btn1)
        addDrawableChild(btn2)
    }

    private fun switchTab(badDebtOnly: Boolean) {
        if (showBadDebtOnly == badDebtOnly) return
        showBadDebtOnly = badDebtOnly
        scrollOffset = 0
        rebuildTabButtons()
        rebuildRowButtons()
    }

    fun onLoanHistoryData(payload: LoanHistoryDataPayload) {
        entries = payload.entries
        rebuildRowButtons()
    }

    /** 撤销按钮行（照 RepayScreen 模式）：仅 OP 全服流水的 BAD_DEBT 行显示；确认弹窗打开时隐藏 */
    private fun rebuildRowButtons() {
        rowButtons.forEach(::remove)
        rowButtons.clear()
        if (revokeEntry != null) return
        if (!showAll || client?.player?.hasPermissionLevel(2) != true) return
        val leftX = width / 2 - panelWidth / 2
        val rowHeight = 24
        val startY = 48
        val maxVisible = maxOf(3, (height - 48 - startY) / rowHeight)
        displayEntries().drop(scrollOffset).take(maxVisible).forEachIndexed { i, e ->
            if (e.status != "BAD_DEBT") return@forEachIndexed
            val y = startY + i * rowHeight
            val btn = NineSliceButton(
                leftX + panelWidth - 48, y + 4, 44, 16,
                Text.translatable("cobblemarket.loan_history.revoke"),
                { openRevokeDialog(e) }
            )
            rowButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // ── 撤销坏账二次确认弹窗（照蛋交易确认弹窗模板：5 秒冷静期 + 红白双色说明原因后果） ──

    private fun openRevokeDialog(entry: LoanHistoryEntry) {
        revokeEntry = entry
        revokeOpenedAt = System.currentTimeMillis()
        // 隐藏下层控件（弹窗打开期间不可交互；closeRevokeDialog 的 init 重建会恢复）
        rowButtons.forEach { it.visible = false }

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染，照蛋交易弹窗）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderRevokeDialog(context)
            }
        })

        val centerX = width / 2
        val dialogY = height / 2 - 85
        revokeConfirmButton = NineSliceButton(
            centerX - 85, dialogY + 124, 80, 20,
            Text.translatable("cobblemarket.loan_history.revoke_yes_countdown", 5),
            { confirmRevoke() }
        )
        addDrawableChild(revokeConfirmButton)
        revokeCancelButton = NineSliceButton(
            centerX + 5, dialogY + 124, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeRevokeDialog() }
        )
        addDrawableChild(revokeCancelButton)
    }

    private fun closeRevokeDialog() {
        revokeEntry = null
        revokeConfirmButton = null
        revokeCancelButton = null
        clearChildren()
        init()
    }

    private fun confirmRevoke() {
        val entry = revokeEntry ?: return
        sendToServer(RequestRevokeBadDebtPayload(entry.playerUuid))
        closeRevokeDialog()
    }

    /** 冷静期：5 秒内确认按钮禁用并显示倒计时 */
    private fun updateRevokeButtons() {
        val cooldownLeft = 5 - (System.currentTimeMillis() - revokeOpenedAt) / 1000
        val canConfirm = cooldownLeft <= 0
        revokeConfirmButton?.active = canConfirm
        revokeConfirmButton?.message = if (canConfirm)
            Text.translatable("cobblemarket.loan_history.revoke_yes")
        else
            Text.translatable("cobblemarket.loan_history.revoke_yes_countdown", cooldownLeft)
    }

    private fun renderRevokeDialog(context: DrawContext) {
        val entry = revokeEntry ?: return
        val centerX = width / 2
        val dialogW = 280
        val dialogH = 160
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan_history.revoke_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF
        )

        // 逐行渲染：红=警告、白=普通说明（原因+后果）
        val lines = listOf(
            "cobblemarket.loan_history.revoke_l1_warn" to 0xFF5555,
            "cobblemarket.loan_history.revoke_l2_text" to 0xFFFFFF,
            "cobblemarket.loan_history.revoke_l3_text" to 0xFFFFFF,
            "cobblemarket.loan_history.revoke_l4_warn" to 0xFF5555
        )
        var ty = dialogY + 36
        lines.forEach { (key, color) ->
            val text = Text.translatable(key, entry.playerName).string
            if (text.isEmpty()) return@forEach
            context.drawTextWithShadow(textRenderer, text, dialogX + 12, ty, color)
            ty += 10
        }
    }

    override fun tick() {
        if (revokeEntry != null) {
            updateRevokeButtons()
        }
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

        // 行背景（照 RepayScreen 行按钮列表惯例）
        val leftX = width / 2 - panelWidth / 2
        val startY = 48
        val rowHeight = 24
        val maxVisible = maxOf(3, (height - 48 - startY) / rowHeight)
        repeat(minOf(maxVisible, maxOf(0, displayEntries().size - scrollOffset))) { i ->
            val rowY = startY + i * rowHeight
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, 0, ROW_BACKGROUND_TEX_H)
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        // 撤销确认弹窗打开：下层行文字不渲染（黄金模板）
        if (revokeEntry != null) {
            return
        }

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable(if (showAll) "cobblemarket.loan_history.all_title" else "cobblemarket.loan_history.title")
                .formatted(Formatting.GOLD),
            width / 2, 14, 0xFFFFFF
        )

        val startY = 48
        val rowHeight = 24
        val dateFormat = SimpleDateFormat("MM-dd HH:mm")
        val panelHalf = panelWidth / 2
        // 按钮行与第一条记录之间的分割线（按钮底 34、列表顶 48 → 线在正中 41）
        context.fill(width / 2 - panelHalf, 41, width / 2 + panelHalf, 42, 0xFF555555.toInt())
        val maxVisible = maxOf(3, (height - 48 - startY) / rowHeight)

        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, displayEntries().size - maxVisible))

        if (displayEntries().isEmpty() && loaded) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.loan_history.empty").formatted(Formatting.GRAY),
                width / 2, startY + 20, 0xFFFFFF
            )
        }

        val visibleEntries = displayEntries().drop(scrollOffset).take(maxVisible)
        visibleEntries.forEachIndexed { i, e ->
            val y = startY + i * rowHeight
            val time = dateFormat.format(Date(e.timestamp))
            val statusText = Text.translatable("cobblemarket.loan_history.status.${e.status}").string

            val leftX = width / 2 - panelHalf
            // 撤销按钮占最右 48px（仅 OP 全服流水的坏账行）
            val hasRevoke = showAll && e.status == "BAD_DEBT" && client?.player?.hasPermissionLevel(2) == true
            val rightReserved = if (hasRevoke) 48 else 0
            var x = leftX + 5
            context.drawTextWithShadow(textRenderer, time, x, y + 8, 0x888888)
            x += textRenderer.getWidth(time) + 6
            val statusLabel = "[$statusText]"
            context.drawTextWithShadow(textRenderer, statusLabel, x, y + 8, statusColor(e.status))
            x += textRenderer.getWidth(statusLabel) + 6
            val owner = if (showAll) "${e.playerName} " else ""
            val source = Text.translatable("cobblemarket.loan_history.source.${e.source}").string
            val periodsText = Text.translatable("cobblemarket.loan_history.periods", e.periodsPaid, e.periodsTotal).string
            val ipPart = if (showAll && e.ip.isNotEmpty()) " IP:${e.ip}" else ""
            val middle = "$owner${formatPriceLong(e.principal)}·$periodsText($source)$ipPart"
            // 剩余本金右对齐行尾（照列表界面惯例），中间部分按实际可用像素截断（truncateString 单位为像素）
            val pricePart = "| ${formatPriceLong(e.remaining)} ${inlineCurrencyUnit()}"
            val priceX = leftX + panelWidth - 5 - rightReserved - textRenderer.getWidth(pricePart)
            val middleMax = priceX - 6 - x
            context.drawTextWithShadow(textRenderer, TextUtil.truncateString(middle, middleMax), x, y + 8, 0xFFFFFF)
            context.drawTextWithShadow(textRenderer, pricePart, priceX, y + 8, 0x55FFFF)
        }

        // Scroll indicator
        if (displayEntries().size > maxVisible) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible, displayEntries().size)} / ${displayEntries().size}",
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
        val rowHeight = 24
        val maxVisible = maxOf(3, (height - 48 - 48) / rowHeight)
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, displayEntries().size - maxVisible))
        rebuildRowButtons()
        return true
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val revoke = revokeEntry
        super.resize(client, width, height)
        if (revoke != null) {
            revokeEntry = null
            openRevokeDialog(revoke)
        }
    }

    override fun shouldPause() = false
}
