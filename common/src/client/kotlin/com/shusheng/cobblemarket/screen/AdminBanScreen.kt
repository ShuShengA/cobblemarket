package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.AdminBanPayload
import com.shusheng.cobblemarket.network.AdminUnbanPayload
import com.shusheng.cobblemarket.network.BanEntry
import com.shusheng.cobblemarket.network.BanListDataPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PlayerNameSuggestionsPayload
import com.shusheng.cobblemarket.network.RequestBanListPayload
import com.shusheng.cobblemarket.network.RequestPlayerNameSuggestionsPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

class AdminBanScreen : Screen(Text.translatable("cobblemarket.ban.title")) {

    private val panelWidth = 300
    private val rowHeight = 22
    private val listStartY = 68

    private var nameField: TextFieldWidget? = null
    private var durationField: TextFieldWidget? = null
    private var reasonField: TextFieldWidget? = null
    private var banButton: NineSliceButton? = null
    private var backButton: NineSliceButton? = null
    private var pendingBanName = ""
    private var pendingBanDuration = ""
    private var bans = listOf<BanEntry>()
    private var scrollOffset = 0
    private var hoveredRow = -1
    private val unbanButtons = mutableListOf<NineSliceButton>()

    // 玩家名联想（覆盖存档所有登录过的玩家，含离线）：输入防抖后请求服务端，展开列表点选
    private var suggestions = listOf<String>()
    private var suggestionsOpen = false
    private var suggestDirty = false
    private var lastSuggestEdit = 0L
    private var suggestRequestId = 0
    private var suggestScroll = 0
    // 点选联想填入文本时抑制 changedListener 的防抖（否则列表收起后又重新展开）
    private var suppressSuggest = false
    // 发送请求时的输入快照：响应到达时输入不一致则忽略；点选时置 null 使所有在途响应失效
    private var lastRequestPrefix: String? = null
    private val suggestionButtons = mutableListOf<NineSliceButton>()

    private fun maxVisible() = maxOf(0, (height - listStartY - 16) / rowHeight)

    private companion object {
        const val MAX_SUGGESTION_ROWS = 8
    }

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        val backBtn = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(AdminScreen()) }
        )
        backButton = backBtn
        addDrawableChild(backBtn)

        val savedName = nameField?.text ?: ""
        nameField = TextFieldWidget(textRenderer, leftX + 2, 44, 120, 16, Text.literal(""))
        nameField?.setPlaceholder(Text.translatable("cobblemarket.ban.player_name"))
        // 防抖 250ms 后请求联想（服务端节流 500ms，连续输入只发最终态）
        nameField?.setChangedListener {
            // 点选联想填入文本时跳过防抖（否则列表收起后又会被响应重新展开）
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

        durationField = TextFieldWidget(textRenderer, leftX + 126, 44, 90, 16, Text.literal(""))
        durationField?.setPlaceholder(Text.translatable("cobblemarket.ban.duration"))
        addSelectableChild(durationField)
        addDrawableChild(durationField)

        val banBtn = NineSliceButton(
            leftX + 220, 44, 76, 16,
            Text.translatable("cobblemarket.ban.ban"),
            { doBan() }
        )
        banButton = banBtn
        addDrawableChild(banBtn)

        scrollOffset = 0
        sendToServer(RequestBanListPayload())
    }

    private fun doBan() {
        val name = nameField?.text?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            playFailSound()
            return
        }
        val duration = durationField?.text?.trim() ?: ""
        openBanConfirmDialog(name, duration)
    }

    private fun openBanConfirmDialog(name: String, duration: String) {
        pendingBanName = name
        pendingBanDuration = duration
        nameField?.visible = false
        durationField?.visible = false
        banButton?.visible = false
        backButton?.visible = false
        unbanButtons.forEach { it.visible = false }
        // 收起可能展开的联想列表（否则联想按钮残留在遮罩下可点）
        suggestionsOpen = false
        suggestDirty = false
        lastRequestPrefix = null
        rebuildSuggestionList()
        val centerX = width / 2
        val dialogY = height / 2 - 75

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderBanDialogBackground(context)
            }
        })

        reasonField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 74, 160, 16, Text.literal(""))
        reasonField?.setPlaceholder(Text.translatable("cobblemarket.ban.reason"))
        reasonField?.setTextPredicate { it.length <= 100 }
        addDrawableChild(reasonField)

        addDrawableChild(NineSliceButton(
            centerX - 85, dialogY + 108, 80, 20,
            Text.translatable("cobblemarket.ban.confirm"),
            { confirmBan() }
        ))
        addDrawableChild(NineSliceButton(
            centerX + 5, dialogY + 108, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeBanConfirmDialog() }
        ))
    }

    private fun renderBanDialogBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogW = 240
        val dialogH = 150
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.ban.confirm_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        context.drawTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.ban.target").string + pendingBanName,
            dialogX + 12, dialogY + 40, 0xFFFFFF)

        context.drawTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.ban.by").string + (client?.player?.name?.string ?: ""),
            dialogX + 12, dialogY + 58, 0xFFFFFF)
    }

    private fun confirmBan() {
        val reason = reasonField?.text?.trim() ?: ""
        sendToServer(AdminBanPayload(pendingBanName, pendingBanDuration, reason))
        closeBanConfirmDialog()
        nameField?.text = ""
        durationField?.text = ""
    }

    private fun closeBanConfirmDialog() {
        pendingBanName = ""
        pendingBanDuration = ""
        reasonField = null
        clearChildren()
        init()
    }

    private fun doUnban(entry: BanEntry) {
        sendToServer(AdminUnbanPayload(entry.playerUuid))
    }

    fun onBanList(payload: BanListDataPayload) {
        bans = payload.entries
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, bans.size - maxVisible()))
        rebuildUnbanButtons()
    }

    fun onResult(payload: MarketResultPayload) {
        client?.player?.sendMessage(payload.message, false)
        sendToServer(RequestBanListPayload())
    }

    private fun rebuildUnbanButtons() {
        unbanButtons.forEach { remove(it) }
        unbanButtons.clear()
        // 联想列表展开时行按钮保持隐藏：数据刷新触发的重建会把新按钮追加到 children 末尾，
        // 浮在联想按钮之上拦截点击（联想收起时 rebuildSuggestionList 会重建恢复）
        if (suggestionsOpen) return
        val leftX = width / 2 - panelWidth / 2
        bans.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, entry ->
            val y = listStartY + i * rowHeight
            val btn = NineSliceButton(
                leftX + panelWidth - 60, y + 3, 56, 16,
                Text.translatable("cobblemarket.ban.unban"),
                { doUnban(entry) }
            )
            btn.visible = pendingBanName.isEmpty()
            unbanButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        if (pendingBanName.isNotEmpty()) {
            return
        }

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.ban.title").formatted(Formatting.GOLD),
            centerX, 20, 0xFFFFFF)

        // 防抖触发联想请求
        checkSuggestDebounce()

        // 联想列表打开时跳过分隔线与封禁列表（画在 children 之后，会刺穿联想按钮）
        if (suggestionsOpen) return

        context.fill(leftX, listStartY - 4, leftX + panelWidth, listStartY - 3, 0xFF555555.toInt())

        if (bans.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.ban.banlist_empty").formatted(Formatting.GRAY),
                centerX, listStartY + 30, 0xFFFFFF)
            return
        }

        bans.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, entry ->
            val y = listStartY + i * rowHeight
            // 永久封禁按客户端语言渲染"永久"（服务端只发空串）
            val durationText = if (entry.expiresAt == null)
                Text.translatable("cobblemarket.ban.permanent")
            else
                Text.literal(entry.durationDisplay)
            context.drawTextWithShadow(textRenderer,
                Text.literal("${entry.playerName}  ·  ${entry.bannedBy}  ·  ").append(durationText),
                leftX + 4, y + 5, 0xFFFFFF)
        }

        if (bans.size > maxVisible()) {
            context.drawCenteredTextWithShadow(textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible(), bans.size)} / ${bans.size}",
                width / 2, height - 49, 0x888888)
        }

        if (hoveredRow >= 0) {
            val actualIdx = scrollOffset + hoveredRow
            if (actualIdx in bans.indices) {
                renderBanTooltip(context, bans[actualIdx], mouseX, mouseY)
            }
        }
    }

    // ── 玩家名联想（服务端返回存档所有登录过的玩家名，含离线） ──

    fun onNameSuggestions(payload: PlayerNameSuggestionsPayload) {
        // 封禁确认弹窗打开时忽略（输入框已隐藏）
        if (pendingBanName.isNotEmpty()) return
        // 陈旧响应：输入已变化（含点选联想填入的名字）→ 忽略，防止列表收起后又被重新展开
        if ((nameField?.text?.trim() ?: "") != lastRequestPrefix) return
        suggestions = payload.names
        // 有候选且输入非空 → 展开；否则收起
        suggestionsOpen = suggestions.isNotEmpty() && !(nameField?.text?.isBlank() ?: true)
        suggestScroll = 0
        rebuildSuggestionList()
    }

    /** 防抖触发联想请求（render 每帧检查，停止输入 250ms 后发，避免被服务端 500ms 节流丢弃） */
    private fun checkSuggestDebounce() {
        if (!suggestDirty) return
        if (System.currentTimeMillis() - lastSuggestEdit < 250) return
        suggestDirty = false
        suggestRequestId++
        lastRequestPrefix = nameField?.text?.trim().orEmpty()
        sendToServer(RequestPlayerNameSuggestionsPayload(lastRequestPrefix ?: ""))
    }

    private fun rebuildSuggestionList() {
        // 收起路径（含点选回调）只切 visible，不在 mouseClicked 栈内 remove 子元素（该操作不可靠）；
        // remove+重建只发生在展开路径（onNameSuggestions 数据到达，该路径一直工作正常）
        if (!suggestionsOpen) {
            suggestionButtons.forEach { it.visible = false }
            // 收起时重建行按钮（展开期间 rebuildUnbanButtons 被拦截，行按钮已移除）
            rebuildUnbanButtons()
            return
        }
        // 展开路径：清理旧按钮后重建。
        // 联想列表在左侧（x 2~120），时长框/封禁按钮/行按钮在右侧且更高，不重叠——不隐藏它们
        suggestionButtons.forEach { remove(it) }
        suggestionButtons.clear()
        val leftX = width / 2 - panelWidth / 2
        suggestions.drop(suggestScroll).take(MAX_SUGGESTION_ROWS).forEachIndexed { i, name ->
            val btn = NineSliceButton(
                leftX + 2, 62 + i * 14, 118, 14,
                Text.literal(name),
                {
                    suppressSuggest = true
                    nameField?.text = name
                    // setText 会触发多次 changed 事件（suppress 只吞第一次，后续会置 dirty）——
                    // 必须在 setText 之后清一次，否则 250ms 后防抖发请求、快照等于完整名被放行重新展开
                    suggestDirty = false
                    lastRequestPrefix = null // 使所有在途响应失效
                    suggestionsOpen = false
                    rebuildSuggestionList()
                }
            )
            suggestionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    private fun renderBanTooltip(context: DrawContext, entry: BanEntry, mouseX: Int, mouseY: Int) {
        val lines = listOf(
            "${Text.translatable("cobblemarket.ban.target").string} ${entry.playerName}",
            "${Text.translatable("cobblemarket.ban.by").string} ${entry.bannedBy}",
            "${Text.translatable("cobblemarket.ban.reason_label").string} ${entry.reason.ifBlank { "-" }}"
        )

        var maxWidth = 0
        lines.forEach { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it)) }

        val padding = 4
        val tx = minOf(mouseX + 12, width - maxWidth - 12)
        val tooltipHeight = lines.size * 10 + padding
        val tyAbove = mouseY - tooltipHeight - 4
        val ty = if (tyAbove <= 0) minOf(mouseY + 12, height - tooltipHeight) else tyAbove

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, lines.size * 10 + 2 * padding, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, line ->
            context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, 0xFFFFFF)
        }
        context.matrices.pop()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        // 联想列表滚动
        if (suggestionsOpen) {
            if (suggestions.size > MAX_SUGGESTION_ROWS) {
                suggestScroll = (suggestScroll - verticalAmount.toInt()).coerceIn(0, suggestions.size - MAX_SUGGESTION_ROWS)
                rebuildSuggestionList()
            }
            return true
        }
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, bans.size - maxVisible()))
        rebuildUnbanButtons()
        return true
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val name = pendingBanName
        val duration = pendingBanDuration
        val reason = reasonField?.text ?: ""
        super.resize(client, width, height)
        if (name.isNotEmpty()) {
            pendingBanName = ""
            pendingBanDuration = ""
            openBanConfirmDialog(name, duration)
            reasonField?.text = reason
        }
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
        // 点击空白处收起联想列表（点联想按钮时回调已收起，此判断不重复执行；
        // 收起时同步清除防抖与快照，防在途响应重新展开）
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

        val leftX = width / 2 - panelWidth / 2
        val visibleRows = maxVisible()
        val listAreaBottom = listStartY + visibleRows * rowHeight

        hoveredRow = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY in listStartY..listAreaBottom) {
            val row = (mouseY - listStartY) / rowHeight
            val shownCount = minOf(visibleRows, bans.size - scrollOffset)
            if (row in 0 until shownCount) hoveredRow = row
        }

        bans.drop(scrollOffset).take(visibleRows).forEachIndexed { di, _ ->
            val rowY = listStartY + di * rowHeight
            val rowState = if (di == hoveredRow) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, rowState, ROW_BACKGROUND_TEX_H)
        }
    }

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int, sliceH: Int = 16) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    override fun shouldPause() = false
}
