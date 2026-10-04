package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.TooltipPlacement
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.network.AdminLoanBlockPayload
import com.shusheng.cobblemarket.network.AdminLoanUnblockPayload
import com.shusheng.cobblemarket.network.CardHolderEntry
import com.shusheng.cobblemarket.network.CardHolderListPayload
import com.shusheng.cobblemarket.network.LoanBlockInfo
import com.shusheng.cobblemarket.network.LoanBlockListPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PlayerNameSuggestionsPayload
import com.shusheng.cobblemarket.network.RequestCardHolderListPayload
import com.shusheng.cobblemarket.network.RequestLoanBlockListPayload
import com.shusheng.cobblemarket.network.RequestCardRevokePayload
import com.shusheng.cobblemarket.network.RequestPlayerNameSuggestionsPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/**
 * 金融管理（FINANCE 节点；喵喵银行界面的入口，2026-10-05 建）。
 *
 * 与 [AdminBanScreen]（封禁管理）**结构对称**：一个管交易封禁、一个管金融 —— 版式照它抄
 * （标题 20 / info 18 / 页签行 44 / 表单行 / 分割线 / 列表），本屏唯一的新东西是**页签会有多个**。
 *
 * ### 页签
 *
 * | 页签 | 内容 | 需要表单行 |
 * |---|---|---|
 * | 禁止借款 | 名单 + 搜索框/时长/按钮（照封禁管理那套） | ✓ |
 * | 卡片管理 | 现有 `CardManageScreen` 的列表搬进来（持有者 + 收回） | ✗ |
 *
 * ⚠⭐ 版式数字**一律由 [tabRows] / [formRowY] 推导**，别在别处写字面量 ——
 *   页签从 1 行涨到 2 行时，下面所有 y 都要跟着走（本项目「挪版式忘改下游」栽过 6 次）。
 * ⚠ 控件可见性**收口在 [hideMainControls]**：遮罩只挡视线、挡不住点击（封禁界面实测报过）。
 */
class AdminFinanceScreen : Screen(Text.translatable("cobblemarket.finance.title")) {

    // 296：与黑名单 / 封禁管理等**同背景的列表界面**统一基准
    private val panelWidth = 296
    /** 列表行高：记录/列表类界面一律 24（项目约定） */
    private val rowHeight = 24
    private val tabRowY = 44

    /**
     * 页签行数：≤3 枚排一行，4~5 枚排两行（用户 2026-10-05 定：宁可让出列表一行，
     * 也不把页签标签压成 `No-Loan` 这种缩写）。
     */
    private val tabRows = if (TABS.size <= 3) 1 else 2

    /** 表单行 y（第一行正文）；⚠ 由页签行数推导，别写死 */
    private val formRowY = tabRowY + 14 + 4 + (tabRows - 1) * 18

    /** 列表起始 y：需要表单行的页签接在表单下面，不需要的直接贴页签行 */
    private fun listStartY() = formRowY + if (currentTab == 0) 24 else 4

    private var currentTab = 0
    private val tabButtons = mutableListOf<NineSliceButton>()

    // ── 页签 0：禁止借款 ──
    private var nameField: TextFieldWidget? = null
    private var durationField: TextFieldWidget? = null
    private var blockButton: NineSliceButton? = null
    private var entries = listOf<LoanBlockInfo>()
    private var hoveredRow = -1
    private var scrollOffset = 0
    private val rowButtons = mutableListOf<NineSliceButton>()

    // ── 页签 1：卡片管理 ──
    private var cardEntries = listOf<CardHolderEntry>()
    private val cardButtons = mutableListOf<Pair<NineSliceButton, String>>()

    private var backButton: NineSliceButton? = null
    private var infoButton: NineSliceButton? = null

    // ── 禁借确认弹窗（照封禁确认弹窗：先确认再发） ──
    private var pendingName = ""
    private var pendingDuration = ""
    private var reasonField: TextFieldWidget? = null
    private var confirmButtons = mutableListOf<NineSliceButton>()

    // 玩家名联想（覆盖存档所有登录过的玩家，含离线）：输入防抖后请求服务端，展开列表点选
    private var suggestions = listOf<String>()
    private var suggestionsOpen = false
    private var suggestDirty = false
    private var lastSuggestEdit = 0L
    private var suggestScroll = 0
    private var suppressSuggest = false
    private var lastRequestPrefix: String? = null
    private val suggestionButtons = mutableListOf<NineSliceButton>()

    private companion object {
        const val MAX_SUGGESTION_ROWS = 8

        /** 当前实现的页签（**按实现进度往里加**；加一枚就自动重排、不用手改坐标） */
        val TABS = listOf("cobblemarket.finance.tab_loan_block", "cobblemarket.finance.tab_cards")

        /** 禁借确认弹窗高度 —— 控件定位与背景绘制**共用这一个数**（照封禁那枚 `DIALOG_H` 的教训） */
        const val DIALOG_H = 150
    }

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        val backBtn = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(MeowthBankScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        backButton = backBtn
        addDrawableChild(backBtn)

        // info 图标（左上角）：这个界面管什么、不影响什么 —— 与封禁管理 / 交易历史同一套做法
        val infoBtn = NineSliceButton(
            width / 2 - 148, 18, 16, 16,
            Text.literal(""),
            { },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/info.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        )
        infoBtn.setTooltip(
            net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.finance.info_tip"))
        )
        infoButton = infoBtn
        addDrawableChild(infoBtn)

        rebuildTabs()

        val savedName = nameField?.text ?: ""
        nameField = TextFieldWidget(textRenderer, leftX + 2, formRowY, 120, 16, Text.literal(""))
        nameField?.setPlaceholder(Text.translatable("cobblemarket.finance.player_name"))
        nameField?.setChangedListener {
            if (suppressSuggest) {
                suppressSuggest = false
                return@setChangedListener
            }
            suggestDirty = true
            lastSuggestEdit = System.currentTimeMillis()
        }
        addSelectableChild(nameField)
        addDrawableChild(nameField)
        nameField?.text = savedName

        durationField = TextFieldWidget(textRenderer, leftX + 126, formRowY, 90, 16, Text.literal(""))
        durationField?.setPlaceholder(Text.translatable("cobblemarket.ban.duration"))
        addSelectableChild(durationField)
        addDrawableChild(durationField)

        val blockBtn = NineSliceButton(
            leftX + 220, formRowY, 76, 16,
            Text.translatable("cobblemarket.finance.block"),
            { openConfirmDialog() }
        )
        blockButton = blockBtn
        addDrawableChild(blockBtn)

        applyTabVisibility()
        scrollOffset = 0
        requestCurrentTabData()
    }

    /** 拉当前页签的数据（**每次切页签都重拉** —— 别人刚改过的状态要能看见，照 `AdminBanScreen`） */
    private fun requestCurrentTabData() {
        if (currentTab == 0) sendToServer(RequestLoanBlockListPayload())
        else sendToServer(RequestCardHolderListPayload())
    }

    // ── 页签几何（按 TABS 数量自动排版，加页签不用手改坐标） ──

    /**
     * 建页签按钮。
     * ⚠ 位置与文案**一处算**：x 由本函数按「当前几枚」居中推出 —— 页签从 2 枚长到 5 枚
     *   只多一枚按钮，不需要重算别人的坐标（这正是「挪版式」最容易漏下游的地方）。
     */
    private fun rebuildTabs() {
        tabButtons.forEach { remove(it) }
        tabButtons.clear()
        val perRow = 3
        val btnW = 80
        val gap = 4
        TABS.forEachIndexed { i, key ->
            val row = i / perRow
            val col = i % perRow
            // 本行实际有几枚（最后一行可能不满）：居中的分母要按**本行**算，不然最后一行会偏左
            val inRow = minOf(perRow, TABS.size - row * perRow)
            val rowW = inRow * btnW + (inRow - 1) * gap
            val x = width / 2 - rowW / 2 + col * (btnW + gap)
            val y = tabRowY + row * 18
            val btn = NineSliceButton(x, y, btnW, 14, Text.translatable(key), { switchTab(i) })
            tabButtons.add(btn)
            addDrawableChild(btn)
        }
        updateTabButtons()
    }

    private fun updateTabButtons() {
        // 选中的页签靠**按钮自己的选中态**表达（背景第三段），不在文字前加 `● `
        tabButtons.forEachIndexed { i, btn -> btn.selectedVisual = i == currentTab }
    }

    private fun switchTab(tab: Int) {
        if (currentTab == tab) return
        currentTab = tab
        scrollOffset = 0
        hoveredRow = -1
        updateTabButtons()
        // 联想列表只属于「禁止借款」页签；切页签时先收掉（否则联想按钮会残留在新页签上可点）
        suggestionsOpen = false
        suggestDirty = false
        lastRequestPrefix = null
        rebuildSuggestionList()
        applyTabVisibility()
        rebuildRowButtons()
        requestCurrentTabData()
    }

    /** 表单行（玩家名 / 时长 / 禁借按钮）只属于页签 0 */
    private fun applyTabVisibility() {
        val isBlockTab = currentTab == 0
        nameField?.visible = isBlockTab
        durationField?.visible = isBlockTab
        blockButton?.visible = isBlockTab
    }

    // ── 数据到达 ──

    fun onLoanBlocks(payload: LoanBlockListPayload) {
        entries = payload.entries
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, entries.size - maxVisible()))
        rebuildRowButtons()
    }

    fun onCardHolders(payload: CardHolderListPayload) {
        cardEntries = payload.entries
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, cardEntries.size - maxVisible()))
        rebuildRowButtons()
    }

    fun onResult(payload: MarketResultPayload) {
        client?.player?.sendMessage(
            payload.message.copy().formatted(if (payload.success) Formatting.GREEN else Formatting.RED), false
        )
        requestCurrentTabData()
    }

    private fun maxVisible() = maxOf(0, (height - 48 - listStartY()) / rowHeight)

    private fun rowCount() = if (currentTab == 0) entries.size else cardEntries.size

    // ── 行按钮 ──

    private fun rebuildRowButtons() {
        rowButtons.forEach { remove(it) }
        rowButtons.clear()
        cardButtons.forEach { (btn, _) -> remove(btn) }
        cardButtons.clear()
        if (suggestionsOpen) return
        val leftX = width / 2 - panelWidth / 2
        val startY = listStartY()
        if (currentTab == 0) {
            // ⚠ 捕获该行的 entry（别存下标回头查：滚动 / 数据刷新后下标会错位）
            entries.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, e ->
                val btn = NineSliceButton(
                    leftX + panelWidth - 60, startY + i * rowHeight + 3, 56, 16,
                    Text.translatable("cobblemarket.finance.unblock"),
                    { sendToServer(AdminLoanUnblockPayload(e.playerUuid)) }
                )
                btn.visible = pendingName.isEmpty()
                rowButtons.add(btn)
                addDrawableChild(btn)
            }
            return
        }
        // 页签 1：卡片管理 —— 卡组（图标 + 按钮）右对齐行尾，双持时紫组在黑组左侧
        // （算式照 `CardManageScreen`：按钮永远在行末尾，图标在按钮左）
        val rightEdge = leftX + panelWidth - 8
        val btnW = 44
        val iconW = 16
        val gap = 4
        cardEntries.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, e ->
            val y = startY + i * rowHeight + 4
            var cursor = rightEdge
            if (e.holdsBlack) {
                val btn = NineSliceButton(
                    cursor - btnW, y, btnW, 16,
                    Text.translatable("cobblemarket.card.manage_revoke"),
                    { sendToServer(RequestCardRevokePayload(e.uuid, "black")) }
                )
                cardButtons.add(btn to "black")
                addDrawableChild(btn)
                cursor -= btnW + gap + iconW + gap + gap
            }
            if (e.holdsPurple) {
                val btn = NineSliceButton(
                    cursor - btnW, y, btnW, 16,
                    Text.translatable("cobblemarket.card.manage_revoke"),
                    { sendToServer(RequestCardRevokePayload(e.uuid, "purple")) }
                )
                cardButtons.add(btn to "purple")
                addDrawableChild(btn)
            }
        }
    }

    /**
     * 弹窗打开时藏掉主界面**所有可点控件**。
     * ⚠⭐ **新增任何按钮/输入框都要加进这里** —— 遮罩只挡视线、**挡不住点击**
     *   （封禁界面实测报过：弹窗开着还能点到底下的页签）。这份名单必须收口成一处。
     * （关闭走 `closeConfirmDialog` → `clearChildren()` + `init()`，控件是重建的，不用手动恢复）
     */
    private fun hideMainControls() {
        nameField?.visible = false
        durationField?.visible = false
        blockButton?.visible = false
        backButton?.visible = false
        infoButton?.visible = false
        tabButtons.forEach { it.visible = false }
        rowButtons.forEach { it.visible = false }
        cardButtons.forEach { (b, _) -> b.visible = false }
    }

    // ── 禁借确认弹窗 ──

    private fun openConfirmDialog() {
        val name = nameField?.text?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            playFailSound()
            return
        }
        pendingName = name
        pendingDuration = durationField?.text?.trim() ?: ""
        hideMainControls()
        suggestionsOpen = false
        suggestDirty = false
        lastRequestPrefix = null
        rebuildSuggestionList()
        val centerX = width / 2
        val dialogY = height / 2 - DIALOG_H / 2

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderDialogBackground(context)
            }
        })

        reasonField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 74, 160, 16, Text.literal(""))
        reasonField?.setPlaceholder(Text.translatable("cobblemarket.ban.reason"))
        reasonField?.setTextPredicate { it.length <= 100 }
        addDrawableChild(reasonField)

        confirmButtons.forEach { remove(it) }
        confirmButtons.clear()
        val confirmBtn = NineSliceButton(
            centerX - 85, dialogY + 118, 80, 20,
            Text.translatable("cobblemarket.ban.confirm"),
            { confirmBlock() }
        )
        val cancelBtn = NineSliceButton(
            centerX + 5, dialogY + 118, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeConfirmDialog() }
        )
        confirmButtons.add(confirmBtn)
        confirmButtons.add(cancelBtn)
        addDrawableChild(confirmBtn)
        addDrawableChild(cancelBtn)
    }

    private fun renderDialogBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogW = minOf(300, width - 40).coerceAtLeast(200)
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - DIALOG_H / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, DIALOG_H, 0, DIALOG_BACKGROUND_TEX_H)

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.finance.confirm_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.ban.target").string + pendingName,
            dialogX + 12, dialogY + 40, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.ban.by").string + (client?.player?.name?.string ?: ""),
            dialogX + 12, dialogY + 58, 0xFFFFFF
        )
        // 时长：空串 = 永久（与输入框同一口径）—— 确认界面就该把正要确认的东西全列出来
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.ban.duration_label").string +
                pendingDuration.ifBlank { Text.translatable("cobblemarket.ban.permanent").string },
            dialogX + 12, dialogY + 22, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.ban.reason_label").string,
            dialogX + 12, dialogY + 92, 0xFFFFFF
        )
    }

    private fun confirmBlock() {
        val reason = reasonField?.text?.trim() ?: ""
        sendToServer(AdminLoanBlockPayload(pendingName, pendingDuration, reason))
        closeConfirmDialog()
        nameField?.text = ""
        durationField?.text = ""
    }

    private fun closeConfirmDialog() {
        pendingName = ""
        pendingDuration = ""
        reasonField = null
        confirmButtons.forEach { remove(it) }
        confirmButtons.clear()
        clearChildren()
        init()
    }

    // ── 联想（照 AdminBanScreen，一字不改地照搬） ──

    fun onNameSuggestions(payload: PlayerNameSuggestionsPayload) {
        if (pendingName.isNotEmpty()) return
        if ((nameField?.text?.trim() ?: "") != lastRequestPrefix) return
        suggestions = payload.names
        suggestionsOpen = suggestions.isNotEmpty() && !(nameField?.text?.isBlank() ?: true)
        suggestScroll = 0
        rebuildSuggestionList()
    }

    private fun checkSuggestDebounce() {
        if (!suggestDirty) return
        if (System.currentTimeMillis() - lastSuggestEdit < 250) return
        suggestDirty = false
        lastRequestPrefix = nameField?.text?.trim().orEmpty()
        sendToServer(RequestPlayerNameSuggestionsPayload(lastRequestPrefix ?: ""))
    }

    private fun rebuildSuggestionList() {
        if (!suggestionsOpen) {
            suggestionButtons.forEach { it.visible = false }
            rebuildRowButtons()
            return
        }
        suggestionButtons.forEach { remove(it) }
        suggestionButtons.clear()
        val leftX = width / 2 - panelWidth / 2
        suggestions.drop(suggestScroll).take(MAX_SUGGESTION_ROWS).forEachIndexed { i, name ->
            val btn = NineSliceButton(
                // ⚠ 起点**跟着表单行走**（写死数字会在页签换行时压到输入框上 —— 封禁界面栽过）
                leftX + 2, formRowY + 18 + i * 14, 118, 14,
                Text.literal(name),
                {
                    suppressSuggest = true
                    nameField?.text = name
                    suggestDirty = false
                    lastRequestPrefix = null
                    suggestionsOpen = false
                    rebuildSuggestionList()
                }
            )
            suggestionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        MarketPanelBackground.draw(context, width, height)

        val leftX = width / 2 - panelWidth / 2
        val visibleRows = maxVisible()
        val startY = listStartY()
        val listAreaBottom = startY + visibleRows * rowHeight
        val rows = rowCount()

        hoveredRow = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY in startY..listAreaBottom) {
            val row = (mouseY - startY) / rowHeight
            val shownCount = minOf(visibleRows, rows - scrollOffset)
            if (row in 0 until shownCount) hoveredRow = row
        }

        val drawn = minOf(visibleRows, maxOf(0, rows - scrollOffset))
        for (di in 0 until drawn) {
            val rowY = startY + di * rowHeight
            val rowState = if (di == hoveredRow) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, rowState, ROW_BACKGROUND_TEX_H)
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        if (pendingName.isNotEmpty()) return

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.finance.title").formatted(Formatting.GOLD),
            centerX, 20, 0xFFFFFF
        )

        if (currentTab == 0) checkSuggestDebounce()
        if (suggestionsOpen) return

        drawPanelDivider(context, leftX + panelWidth / 2, listStartY() - 4)

        if (currentTab == 0) renderBlockList(context, centerX, leftX, mouseX, mouseY)
        else renderCardList(context, centerX, leftX, mouseX, mouseY)
    }

    private fun renderBlockList(context: DrawContext, centerX: Int, leftX: Int, mouseX: Int, mouseY: Int) {
        if (entries.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.finance.block_empty").formatted(Formatting.GRAY),
                centerX, listStartY() + 30, 0xFFFFFF
            )
            return
        }
        entries.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, e ->
            val y = listStartY() + i * rowHeight
            val durationText = if (e.expiresAt == null) Text.translatable("cobblemarket.ban.permanent")
            else BanState.remainingText(e.remainingMs)
            // 行文字画到「解除按钮左侧留 4px」为止：按钮起于 panelWidth-60、文字起于 +4
            context.drawTextWithShadow(
                textRenderer,
                // ⚠ 必须 truncateStyled：truncateText 会把分段颜色压平成一段（封禁界面实测报过）
                TextUtil.truncateStyled(
                    Text.literal("${e.playerName}  ·  ").append(BanState.reasonText(e.blockedBy))
                        .append("  ·  ").append(durationText),
                    panelWidth - 68
                ),
                leftX + 4, y + 7, 0xFFFFFF
            )
        }
        if (entries.size > maxVisible()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible(), entries.size)} / ${entries.size}",
                centerX, height - 49, 0x888888
            )
        }
        if (hoveredRow >= 0) {
            val idx = scrollOffset + hoveredRow
            if (idx in entries.indices) renderBlockTooltip(context, entries[idx], mouseX, mouseY)
        }
    }

    private fun renderCardList(context: DrawContext, centerX: Int, leftX: Int, mouseX: Int, mouseY: Int) {
        if (cardEntries.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.card.manage_empty").formatted(Formatting.GRAY),
                centerX, listStartY() + 30, 0xFFFFFF
            )
            return
        }
        val purpleItem = net.minecraft.registry.Registries.ITEM.get(Identifier.of("cobblemarket", "meowth_purple_card"))
        val blackItem = net.minecraft.registry.Registries.ITEM.get(Identifier.of("cobblemarket", "meowth_black_card"))
        val air = net.minecraft.registry.Registries.ITEM.get(Identifier.of("minecraft", "air"))
        val visible = cardEntries.drop(scrollOffset).take(maxVisible())
        visible.forEachIndexed { i, e ->
            val y = listStartY() + i * rowHeight
            // 名字截断到卡组左侧（双持时紫图标最靠左）—— 完整名字看悬停窗
            context.drawTextWithShadow(
                textRenderer,
                Text.literal(truncateName(e.name, 140)),
                leftX + 8, y + 7, 0xFFFFFF
            )
            if (e.holdsPurple && purpleItem != air) {
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = net.minecraft.item.ItemStack(purpleItem),
                    x = (leftX + iconXOf(e, "purple")).toDouble(), y = (y + 4).toDouble(), scale = 1.0,
                    matrixStack = context.matrices
                )
            }
            if (e.holdsBlack && blackItem != air) {
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = net.minecraft.item.ItemStack(blackItem),
                    x = (leftX + iconXOf(e, "black")).toDouble(), y = (y + 4).toDouble(), scale = 1.0,
                    matrixStack = context.matrices
                )
            }
        }
        if (cardEntries.size > maxVisible()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible(), cardEntries.size)} / ${cardEntries.size}",
                centerX, height - 49, 0x888888
            )
        }
        if (hoveredRow >= 0) {
            val idx = scrollOffset + hoveredRow
            if (idx in cardEntries.indices) renderCardTooltip(context, cardEntries[idx], mouseX, mouseY)
        }
    }

    /** 卡组（图标 + 按钮）右对齐行尾的图标偏移 —— 算式与 `CardManageScreen` / [rebuildRowButtons] 同源 */
    private fun iconXOf(e: CardHolderEntry, kind: String): Int {
        val rightEdge = panelWidth - 8
        val btnW = 44
        val iconW = 16
        val gap = 4
        return when (kind) {
            "black" -> rightEdge - btnW - gap - iconW
            "purple" -> if (e.holdsBlack) rightEdge - btnW - gap - iconW - gap - gap - btnW - gap - iconW
            else rightEdge - btnW - gap - iconW
            else -> 0
        }
    }

    private fun truncateName(name: String, maxWidth: Int): String {
        if (textRenderer.getWidth(name) <= maxWidth) return name
        var cut = name.length
        while (cut > 1 && textRenderer.getWidth(name.substring(0, cut) + "…") > maxWidth) cut--
        return name.substring(0, cut) + "…"
    }

    /**
     * 禁借行的悬停窗 —— **行的完整版**：行内会被截断（玩家名一长两头都看不到），
     * 这里把行内每一段都给全（照封禁行那五行的口径）。
     */
    private fun renderBlockTooltip(context: DrawContext, e: LoanBlockInfo, mouseX: Int, mouseY: Int) {
        val lines: List<Text> = buildList {
            add(Text.literal("${Text.translatable("cobblemarket.ban.target").string} ${e.playerName}"))
            add(Text.literal("${Text.translatable("cobblemarket.ban.by").string} ${BanState.reasonText(e.blockedBy).string}"))
            add(
                Text.translatable(
                    "cobblemarket.ban.remaining",
                    if (e.expiresAt == null) Text.translatable("cobblemarket.ban.permanent")
                    else BanState.remainingText(e.remainingMs)
                )
            )
            add(Text.literal("${Text.translatable("cobblemarket.ban.reason_label").string} ${BanState.reasonText(e.reason).string.ifBlank { "-" }}"))
        }
        // 悬停窗不许盖掉本行的「解除」按钮
        renderTextTooltip(context, lines, mouseX, mouseY, avoid = rowButtonRect(hoveredRow))
    }

    private fun renderCardTooltip(context: DrawContext, e: CardHolderEntry, mouseX: Int, mouseY: Int) {
        val lines: List<Text> = buildList {
            add(Text.literal("${Text.translatable("cobblemarket.ban.target").string} ${e.name}"))
            if (e.holdsPurple) add(Text.translatable("cobblemarket.card.purple_name"))
            if (e.holdsBlack) add(Text.translatable("cobblemarket.card.black_name"))
        }
        renderTextTooltip(context, lines, mouseX, mouseY, avoid = rowButtonRect(hoveredRow))
    }

    private fun rowButtonRect(row: Int) = TooltipPlacement.Rect(
        width / 2 - panelWidth / 2 + panelWidth - 60, listStartY() + row * rowHeight + 3, 60, 16
    )

    /**
     * 悬停窗的通用绘制（禁借行 / 卡片行共用）。
     * ⚠ 与 `AdminBanScreen.renderTextTooltip` 是**同一份实现的两次拷贝** —— 等第三个界面要用时
     *   抽成共用件（本次不动那个刚实测过的屏，避免搬代码搬出新问题）。
     */
    private fun renderTextTooltip(
        context: DrawContext, lines: List<Text>, mouseX: Int, mouseY: Int,
        avoid: TooltipPlacement.Rect? = null
    ) {
        var maxWidth = 0
        lines.forEach { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it)) }

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
            context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, 0xFFFFFF)
        }
        context.matrices.pop()
    }

    // ── 交互 ──

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (suggestionsOpen) {
            if (suggestions.size > MAX_SUGGESTION_ROWS) {
                suggestScroll = (suggestScroll - verticalAmount.toInt()).coerceIn(0, suggestions.size - MAX_SUGGESTION_ROWS)
                rebuildSuggestionList()
            }
            return true
        }
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, rowCount() - maxVisible()))
        rebuildRowButtons()
        return true
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val name = pendingName
        val duration = pendingDuration
        val reason = reasonField?.text ?: ""
        super.resize(client, width, height)
        if (name.isNotEmpty()) {
            pendingName = ""
            pendingDuration = ""
            openConfirmDialogFor(name, duration)
            reasonField?.text = reason
        }
    }

    /** resize 后重开弹窗：名字不能再从输入框取（它已被隐藏 / 清空），故单独入口 */
    private fun openConfirmDialogFor(name: String, duration: String) {
        nameField?.text = name
        durationField?.text = duration
        openConfirmDialog()
    }

    private fun isInputFieldFocused() = focused?.let { f -> f === nameField || f === durationField } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        nameField?.isMouseOver(mouseX, mouseY) == true ||
        durationField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        if (suggestionsOpen && !isMouseOverSuggestionArea(mouseX, mouseY)) {
            suggestionsOpen = false
            suggestDirty = false
            lastRequestPrefix = null
            rebuildSuggestionList()
        }
        return result
    }

    private fun isMouseOverSuggestionArea(mouseX: Double, mouseY: Double): Boolean {
        if (nameField?.isMouseOver(mouseX, mouseY) == true) return true
        return suggestionButtons.any { it.visible && it.isMouseOver(mouseX, mouseY) }
    }

    override fun shouldPause() = false
}
