package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.ClientPermissionCache
import com.shusheng.cobblemarket.client.TooltipPlacement
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.client.activeCurrencyUnit
import com.shusheng.cobblemarket.client.currencyUnitWidth
import com.shusheng.cobblemarket.client.drawCurrencyUnit
import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.playFailSound
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
    /** 鼠标当前悬停的行（-1 = 无）：行悬停态与悬停窗共用（renderBackground 里算，render 里用） */
    private var hoveredRow = -1
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
            Text.literal(""),
            { client?.setScreen(MeowthBankScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
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
        if (!showAll || !ClientPermissionCache.has(MarketPermissions.FINANCE)) return
        val leftX = width / 2 - panelWidth / 2
        val tabY = 24
        // info 图标（左上角）：借贷账本都在服务端跑，路径只能靠提示告知 —— 与交易历史界面同一套做法
        val infoBtn = NineSliceButton(
            // y=18（不用 tabY=24）：16 高的图标放 24 会顶到 y=41 的分割线上（只剩 1px），
            // 18 与交易历史界面的 info 同高，离分割线 7px
            leftX, 18, 16, 16,
            Text.literal(""),
            { },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/info.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        )
        infoBtn.setTooltip(
            net.minecraft.client.gui.tooltip.Tooltip.of(
                Text.translatable("cobblemarket.loan_history.folder_path", "config/cobblemarket/credit")
            )
        )
        tabButtons.add(infoBtn)
        addDrawableChild(infoBtn)

        // 双 tab 居中（两个等宽 + 4 间隙）：**宽度按实测最宽文案定** —— 选中态会多个「● 」前缀、
        // 英文又比中文长，写死 50 时英文选中态「● Bad Debt」会直接溢出按钮（2026-09-21 用户报，
        // 中文下看着刚好）。左右各留 8px 给九宫格边框。
        val tabW = maxOf(
            textRenderer.getWidth("● " + Text.translatable("cobblemarket.loan_history.tab_all").string),
            textRenderer.getWidth("● " + Text.translatable("cobblemarket.loan_history.tab_bad_debt").string)
        ) + 16
        val btn1 = NineSliceButton(
            width / 2 - tabW - 2, tabY, tabW, 14,
            Text.literal((if (!showBadDebtOnly) "● " else "") + Text.translatable("cobblemarket.loan_history.tab_all").string),
            { switchTab(false) }
        )
        val btn2 = NineSliceButton(
            width / 2 + 2, tabY, tabW, 14,
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
        // ⚠ 必须与行渲染里的 hasRevoke 判定**完全一致**：不一致就会出现「按钮建出来了、
        // 金额却没为它让位」的完全重叠（2026-09-12 踩过，只有有金融管理节点的普通玩家触发）
        if (!showAll || !ClientPermissionCache.isServerOwner()) return
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
        // 冷静期内点击：置灰按钮仍可点（dimmed 模式），播 fail 音效提示，不执行
        if (System.currentTimeMillis() - revokeOpenedAt < 5000L) {
            playFailSound()
            return
        }
        sendToServer(RequestRevokeBadDebtPayload(entry.playerUuid))
        closeRevokeDialog()
    }

    /** 冷静期：5 秒内确认按钮置灰（dimmed 保留可点性）并显示倒计时；点击时 confirmRevoke 播 fail 提示 */
    private fun updateRevokeButtons() {
        val cooldownLeft = 5 - (System.currentTimeMillis() - revokeOpenedAt) / 1000
        val canConfirm = cooldownLeft <= 0
        revokeConfirmButton?.dimmed = !canConfirm
        revokeConfirmButton?.message = if (canConfirm)
            Text.translatable("cobblemarket.loan_history.revoke_yes")
        else
            Text.translatable("cobblemarket.loan_history.revoke_yes_countdown", cooldownLeft)
    }

    private fun renderRevokeDialog(context: DrawContext) {
        val entry = revokeEntry ?: return
        val centerX = width / 2
        val dialogW = minOf(380, width - 40).coerceAtLeast(200)
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
        // 可用宽从布局反算（左右各留 12），别写死；玩家名是唯一变长段，
        // 先按剩余宽度截名字保住尾部后果文案，再整行截断兜底
        val avail = dialogW - 24
        var ty = dialogY + 36
        lines.forEach { (key, color) ->
            val fixed = Text.translatable(key, "").string
            val nameBudget = (avail - textRenderer.getWidth(fixed)).coerceAtLeast(0)
            val name = TextUtil.truncateString(entry.playerName, nameBudget)
            val text = TextUtil.truncateString(Text.translatable(key, name).string, avail)
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

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        MarketPanelBackground.draw(context, width, height)

        // 行背景（照 RepayScreen 行按钮列表惯例）
        val leftX = width / 2 - panelWidth / 2
        val startY = 48
        val rowHeight = 24
        val maxVisible = maxOf(3, (height - 48 - startY) / rowHeight)
        // 悬停行：鼠标落在列表区域内才算（判据同权限管理界面：先框内、再除以行高）
        val shownCount = minOf(maxVisible, maxOf(0, displayEntries().size - scrollOffset))
        hoveredRow = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY in startY until (startY + shownCount * rowHeight)) {
            val row = (mouseY - startY) / rowHeight
            if (row in 0 until shownCount) hoveredRow = row
        }
        repeat(shownCount) { i ->
            val rowY = startY + i * rowHeight
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, if (i == hoveredRow) 1 else 0, ROW_BACKGROUND_TEX_H)
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
        drawPanelDivider(context, width / 2, 41)
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
            // 撤销按钮占最右 48px（仅服主的全服流水坏账行）
            val hasRevoke = showAll && e.status == "BAD_DEBT" && ClientPermissionCache.isServerOwner()
            val rightReserved = if (hasRevoke) 48 else 0
            var x = leftX + 5
            context.drawTextWithShadow(textRenderer, time, x, y + 8, 0x888888)
            x += textRenderer.getWidth(time) + 6
            val statusLabel = "[$statusText]"
            context.drawTextWithShadow(textRenderer, statusLabel, x, y + 8, statusColor(e.status))
            x += textRenderer.getWidth(statusLabel) + 6
            val middle = middleTextOf(e)
            // 剩余本金右对齐行尾（照列表界面惯例），中间部分按实际可用像素截断（truncateString 单位为像素）
            // 「| 」分隔符在**这里**加：priceTextOf 只给纯文案，悬停窗独立成行时不要它
            val pricePart = "| " + priceTextOf(e)
            // ⚠ 货币单位不在 pricePart 里：右对齐锚点要把单位那份算进去，再补画在行尾
            //   （物品货币时单位是物品图标）
            val priceUnitW = currencyUnitWidth(textRenderer, false)
            val priceX = leftX + panelWidth - 5 - rightReserved - textRenderer.getWidth(pricePart) - priceUnitW
            val middleMax = priceX - 6 - x
            context.drawTextWithShadow(textRenderer, TextUtil.truncateString(middle, middleMax), x, y + 8, 0xFFFFFF)
            val priceDrawn = context.drawTextWithShadow(textRenderer, pricePart, priceX, y + 8, 0x55FFFF)
            drawCurrencyUnit(context, textRenderer, false, priceDrawn, y + 8, 0x55FFFF)
        }

        // 悬停行完整信息：行内中段会被截断（玩家名 / 来源 / IP 都可能看不全），这里给完整的
        if (hoveredRow in visibleEntries.indices) {
            val e = visibleEntries[hoveredRow]
            val fullTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(e.timestamp))
            val statusText = Text.translatable("cobblemarket.loan_history.status.${e.status}").string
            // 撤销按钮（仅服主的全服流水坏账行）：**鼠标落在它上面时整个不画悬停窗** ——
            // 玩家那时正要点击、不需要详情；且悬停窗就算压住按钮，鼠标移过去也会立刻消失，
            // 所以不可能出现「想点却被挡住」（与精灵市场行按钮同一套处理）。
            // ⚠ 几何必须与 rebuildRowButtons 里建按钮的算式同源，否则会出现「在按钮上却不消失」
            val revokeRect = if (showAll && ClientPermissionCache.isServerOwner() && e.status == "BAD_DEBT")
                TooltipPlacement.Rect(width / 2 - panelWidth / 2 + panelWidth - 48, startY + hoveredRow * rowHeight + 4, 44, 16)
            else null
            // 颜色与行内一一对应：状态用状态色、时间灰、主体白、剩余本金走账本蓝（0x55FFFF）
            renderTooltip(context, listOf(
                Text.literal("")
                    .append(Text.literal("[$statusText]").styled { it.withColor(statusColor(e.status)) })
                    .append(Text.literal(" $fullTime").styled { it.withColor(0x888888) }),
                Text.literal(middleTextOf(e)),
                Text.literal(priceTextOf(e)).styled { it.withColor(0x55FFFF) }
            ), mouseX, mouseY, revokeRect, unitLine = 2)   // ← 2 = 剩余本金行在 listOf 里的下标
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

    /** 行内中段文案（全部历史带玩家名与 IP）：本金 ₽·期数（来源）—— 行渲染与悬停窗共用 */
    private fun middleTextOf(e: LoanHistoryEntry): String {
        val owner = if (showAll) "${e.playerName} " else ""
        val source = Text.translatable("cobblemarket.loan_history.source.${e.source}").string
        val periodsText = Text.translatable("cobblemarket.loan_history.periods", e.periodsPaid, e.periodsTotal).string
        val ipPart = if (showAll && e.ip.isNotEmpty()) " IP:${e.ip}" else ""
        // ⚠ 单位走 activeCurrencyUnit（物品货币显示**物品名**、虚拟货币仍是 ₽）而**不是图标**：
        //   它夹在这条字符串**中间**、整串随后还会被截断，图标没有可精确补画的位置。
        //   「能补画的地方用图标、补不了的用物品名」是这次改造的分界（见 CurrencyDisplay 顶部说明）
        return "$owner${formatPriceLong(e.principal)} ${activeCurrencyUnit()}·$periodsText($source)$ipPart"
    }

    /**
     * 剩余本金文案。
     * ⚠ **不带「| 」** —— 那是**行内**的分隔符（行尾紧跟在中段后面才需要），由行渲染自己加；
     * 悬停窗里它独立成一行，带上就变成「| 8,000 ₽」这种莫名其妙的前缀（2026-09-21 用户报）。
     */
    /** ⚠ **不含货币单位**（2026-09-29）：单位由两处调用各自补画 —— 行内补在行尾、悬停窗按 `unitLine` 补。
     *  末尾那个空格留着，调用方按 `getWidth` 定位单位 */
    private fun priceTextOf(e: LoanHistoryEntry): String =
        "${formatPriceLong(e.remaining)} "

    /**
     * 悬停窗（位置走全站统一的 [TooltipPlacement]）：自动躲开屏幕边缘。
     *
     * ⚠ 行色固定白 —— 颜色一律由各行的 [Text] 自带（状态色、时间灰、账本蓝…），
     * 别在这里按行号上色（那会把自带颜色的段也染掉）。
     *
     * @param avoid 本行操作按钮的矩形；**鼠标落在里面就整个不画**（返回 null 直接退出）
     */
    private fun renderTooltip(
        context: DrawContext,
        lines: List<Text>,
        mouseX: Int,
        mouseY: Int,
        avoid: TooltipPlacement.Rect? = null,
        /** 哪一行的末尾要补货币单位（物品货币时画那件物品的图标）；-1 = 没有 */
        unitLine: Int = -1,
    ) {
        // ⚠ unitLine 那行的货币单位**画在行尾、不在文本里** ⇒ 测量要补上，否则提示框算窄
        //   ⚠ 借款历史是**银行口径**（RP 不进金融系统）⇒ isPoints 恒 false
        val unitW = if (unitLine >= 0) currencyUnitWidth(textRenderer, false) else 0
        var maxWidth = 0
        lines.forEachIndexed { i, line ->
            maxWidth = maxOf(maxWidth, textRenderer.getWidth(line) + if (i == unitLine) unitW else 0)
        }

        val padding = 4
        val tooltipHeight = lines.size * 10 + padding
        val (tx, ty) = TooltipPlacement.place(
            mouseX = mouseX, mouseY = mouseY,
            textW = maxWidth, textH = tooltipHeight, pad = padding,
            screenW = width, screenH = height, avoid = avoid
        ) ?: return

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, lines.size * 10 + 2 * padding, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, line ->
            val w = context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, 0xFFFFFF)
            // 金额行：货币单位补在行尾（物品货币时是那件物品的图标）
            if (i == unitLine) {
                drawCurrencyUnit(context, textRenderer, false, w, ty + i * 10, 0x55FFFF)
            }
        }
        context.matrices.pop()
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
        // ⚠ 第一个 48 = **列表起始 y**（与渲染处 `val startY = 48` 同源），第二个 = 底部预留。
        //    2026-09-22 排查"滚动后最后一行看不见"时确认：这里与渲染处算式**一致**（都是 height-96），
        //    所以没出问题 —— 但两处都是**写死的**，改 startY 时必须一起改。
        //    （SellSelectScreen 就是在同款坑上栽的：滚轮写死 96、渲染用 startY=100，差 4px 就少一行）
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
