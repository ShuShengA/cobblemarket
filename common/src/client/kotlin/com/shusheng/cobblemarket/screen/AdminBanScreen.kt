package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.TooltipPlacement
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.network.AdminBanPayload
import com.shusheng.cobblemarket.network.AdminUnbanPayload
import com.shusheng.cobblemarket.network.BanChangesPayload
import com.shusheng.cobblemarket.network.BanEntry
import com.shusheng.cobblemarket.network.BanListDataPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PlayerNameSuggestionsPayload
import com.shusheng.cobblemarket.network.RequestBanChangesPayload
import com.shusheng.cobblemarket.network.RequestBanListPayload
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

class AdminBanScreen : Screen(Text.translatable("cobblemarket.ban.title")) {

    private val panelWidth = 300
    private val rowHeight = 22
    // 面板自上而下：标题(20) → info 图标(18) → 页签行(44，高 14) → 封禁表单(62，高 16) → 分割线(82) → 列表(86)
    // ⚠ 版式整段照 PermissionScreen（它同样是「标题 20 / info 18 / 页签 44 / 第二行 62 / 列表 86」）
    private val tabRowY = 44
    private val formRowY = 62
    // 页签 0 有表单行（62~78），列表接在它下面；页签 1 没有这一行，整段高度收掉
    // （不收的话表单留下的空位会把变更记录顶下去，看着空一截 —— 照 PermissionScreen 那两个常量）
    private val banListStartY = 86
    private val changeListStartY = 66

    /** 当前页签的列表起始 y（分割线画在它上方 4px）：render / 行按钮 / 悬停判定都走这里 */
    private fun listStartY() = if (currentTab == 0) banListStartY else changeListStartY

    // ── 行按钮几何（**建按钮与悬停窗避让共用一处定义**，别各写一份）──
    private val rowBtnInset = 60
    private val rowBtnW = 56
    private val rowBtnH = 16
    private val rowBtnDy = 3

    /** 第 [row] 行的解封按钮矩形 —— 与 `rebuildUnbanButtons` 同源（行按钮只属于页签 0，故用 [banListStartY]） */
    private fun unbanButtonRect(row: Int) = TooltipPlacement.Rect(
        width / 2 - panelWidth / 2 + panelWidth - rowBtnInset,
        banListStartY + row * rowHeight + rowBtnDy, rowBtnW, rowBtnH
    )

    private var nameField: TextFieldWidget? = null
    private var durationField: TextFieldWidget? = null
    private var reasonField: TextFieldWidget? = null
    private var banButton: NineSliceButton? = null
    private var backButton: NineSliceButton? = null
    private var infoButton: NineSliceButton? = null
    /** 「同时禁止借款」开关（确认弹窗里那枚）：标签即状态名，选中态走 `selectedVisual` 第三段纹理 */
    private var blockLoanButton: NineSliceButton? = null
    private var pendingBanName = ""
    private var pendingBanDuration = ""
    private var pendingBlockLoan = false
    private var bans = listOf<BanEntry>()
    private var scrollOffset = 0
    private var hoveredRow = -1

    // ── 页签（照 PermissionScreen：按钮重建 + 选中态走 selectedVisual，不在文字前加 ●）──
    private var currentTab = 0                      // 0 = 封禁列表，1 = 变更记录
    private var bansTabButton: NineSliceButton? = null
    private var changesTabButton: NineSliceButton? = null

    // ── 变更记录（页签 1）──
    /**
     * 全量记录（服务端读**整个**账本、不分页不设窗口：封禁不多、文件永久保留）。
     * ⚠ 行文本与悬停内容**在数据到达时算一次**，render 每帧只读（照 `PermissionScreen.AuditLine`）——
     *   时间格式化 + 词条拼接每帧现算，在全量列表上就是可见的掉帧源。
     */
    private data class ChangeLine(
        val text: Text,
        /** 悬停窗内容：**逐段带色、与行内一一对应**（灰时间 / 金执行者→目标 / 红封禁·绿解封 / 时长 / 理由） */
        val tooltip: List<Text>
    )
    private var changeLines = listOf<ChangeLine>()
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

    private fun maxVisible() = maxOf(0, (height - listStartY() - 16) / rowHeight)

    private companion object {
        const val MAX_SUGGESTION_ROWS = 8
        /**
         * 封禁确认弹窗高度 —— **控件定位与背景绘制共用这一个数**。
         * 原先两边各写一遍（`height/2 - 75` 与 `dialogH = 150`），加一行控件漏改一处就会控件与背景错位。
         * 版式：标题 14 / 玩家名 40 / 执行人员 58 / 理由输入框 74(h16) /
         *       借款开关 100(h20) / 按钮 134(h20) / 距底边框 22
         */
        const val DIALOG_H = 176

        /** 变更记录**行内**的时间：只到分钟就够（当天看的是"什么时候"的大致位置） */
        private val changeTimeFormat = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")

        /** 悬停窗里的时间：给**完整日期 + 秒** —— 账本本来就是按秒记的，查账时要能对得上 */
        private val changeTooltipTimeFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        val backBtn = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(AdminScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        backButton = backBtn
        addDrawableChild(backBtn)

        // info 图标（左上角）：封禁拦什么 / 不拦什么 —— 与交易历史 / 借贷账本 / 权限管理同一套做法
        // ⚠ x 取「中线 -148」（不是本界面自己的 leftX = 中线 -150）：那三个界面 panelWidth=296、
        //   左缘正好是 148，本界面虽然 panelWidth=300，但输入框列也在 leftX+2 = 同一列
        val infoBtn = NineSliceButton(
            width / 2 - 148, 18, 16, 16,
            Text.literal(""),
            { },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/info.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        )
        infoBtn.setTooltip(
            net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.ban.info_tip"))
        )
        infoButton = infoBtn
        addDrawableChild(infoBtn)

        // 页签行（两个 80 宽、间隔 4、整体居中 —— 与 PermissionScreen 同坐标：centerX-82 / centerX+2）
        val bansTab = NineSliceButton(width / 2 - 82, tabRowY, 80, 14, Text.literal(""), { switchTab(0) })
        val changesTab = NineSliceButton(width / 2 + 2, tabRowY, 80, 14, Text.literal(""), { switchTab(1) })
        bansTabButton = bansTab
        changesTabButton = changesTab
        addDrawableChild(bansTab)
        addDrawableChild(changesTab)
        updateTabButtons()

        val savedName = nameField?.text ?: ""
        nameField = TextFieldWidget(textRenderer, leftX + 2, formRowY, 120, 16, Text.literal(""))
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

        durationField = TextFieldWidget(textRenderer, leftX + 126, formRowY, 90, 16, Text.literal(""))
        durationField?.setPlaceholder(Text.translatable("cobblemarket.ban.duration"))
        addSelectableChild(durationField)
        addDrawableChild(durationField)

        val banBtn = NineSliceButton(
            leftX + 220, formRowY, 76, 16,
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

    /**
     * 弹窗打开时藏掉主界面**所有可点控件**。
     * ⚠⭐ **新增任何按钮/输入框都要加进这里** —— 遮罩只挡视线、**挡不住点击**（用户实测报过：
     *   封禁确认弹窗开着还能点到底下的页签）。所以这份名单必须收口成一处，别散在各自的开弹窗代码里。
     * （关闭走 `closeBanConfirmDialog` → `clearChildren()` + `init()`，控件是重建的，不用手动恢复）
     */
    private fun hideMainControls() {
        nameField?.visible = false
        durationField?.visible = false
        banButton?.visible = false
        backButton?.visible = false
        infoButton?.visible = false
        bansTabButton?.visible = false
        changesTabButton?.visible = false
        unbanButtons.forEach { it.visible = false }
    }

    private fun openBanConfirmDialog(name: String, duration: String) {
        pendingBanName = name
        pendingBanDuration = duration
        pendingBlockLoan = false   // 每次开弹窗都从「不选」起（= 老行为），不继承上一次
        hideMainControls()
        // 收起可能展开的联想列表（否则联想按钮残留在遮罩下可点）
        suggestionsOpen = false
        suggestDirty = false
        lastRequestPrefix = null
        rebuildSuggestionList()
        val centerX = width / 2
        val dialogY = height / 2 - DIALOG_H / 2

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderBanDialogBackground(context)
            }
        })

        reasonField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 74, 160, 16, Text.literal(""))
        reasonField?.setPlaceholder(Text.translatable("cobblemarket.ban.reason"))
        reasonField?.setTextPredicate { it.length <= 100 }
        addDrawableChild(reasonField)

        // 「同时禁止借款」开关：**按钮形态**（标签就是状态名，选中态走第三段纹理），
        // 两态说明按约定写在悬停提示里（「选中：… / 不选：…」），不是打勾的说法
        val loanBtn = NineSliceButton(
            centerX - 85, dialogY + 100, 170, 20,
            Text.translatable("cobblemarket.ban.block_loan"),
            { toggleBlockLoan() },
            tooltip = Text.translatable("cobblemarket.ban.block_loan_tip")
        )
        loanBtn.selectedVisual = pendingBlockLoan
        blockLoanButton = loanBtn
        addDrawableChild(loanBtn)

        addDrawableChild(NineSliceButton(
            centerX - 85, dialogY + 134, 80, 20,
            Text.translatable("cobblemarket.ban.confirm"),
            { confirmBan() }
        ))
        addDrawableChild(NineSliceButton(
            centerX + 5, dialogY + 134, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeBanConfirmDialog() }
        ))
    }

    private fun renderBanDialogBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogW = minOf(300, width - 40).coerceAtLeast(200)
        val dialogH = DIALOG_H
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

    /** 翻「同时禁止借款」：只改状态与选中态，不重建按钮 */
    private fun toggleBlockLoan() {
        pendingBlockLoan = !pendingBlockLoan
        blockLoanButton?.selectedVisual = pendingBlockLoan
    }

    private fun confirmBan() {
        val reason = reasonField?.text?.trim() ?: ""
        sendToServer(AdminBanPayload(pendingBanName, pendingBanDuration, reason, pendingBlockLoan))
        closeBanConfirmDialog()
        nameField?.text = ""
        durationField?.text = ""
    }

    private fun closeBanConfirmDialog() {
        pendingBanName = ""
        pendingBanDuration = ""
        pendingBlockLoan = false
        reasonField = null
        blockLoanButton = null
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

    fun onBanChanges(payload: BanChangesPayload) {
        changeLines = payload.rows.map { buildChangeLine(it) }
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, changeLines.size - maxVisible()))
    }

    /** 一条变更记录 → 行内文本 + 悬停窗内容（**建行时算一次**；两处的分段与配色一一对应） */
    private fun buildChangeLine(row: BanChangesPayload.Row): ChangeLine {
        val isBan = row.actionKey == BanState.ACTION_BAN
        fun at(format: java.time.format.DateTimeFormatter) = format.format(
            java.time.Instant.ofEpochMilli(row.timestamp).atZone(java.time.ZoneId.systemDefault())
        )
        val whoRow = Text.literal("${BanState.reasonText(row.operator).string} → ${row.targetName}")
            .formatted(Formatting.GOLD)
        val actionRow = BanState.reasonText(row.actionKey)
            .formatted(if (isBan) Formatting.RED else Formatting.GREEN)
        // 时长：永久封禁、以及拿不到原时长的解封行都是 null ⇒ 显示「永久」
        val durationRow = row.durationMs?.let { BanState.remainingText(it) }
            ?: Text.translatable("cobblemarket.ban.permanent")
        val originalRow = row.originalBanner.takeIf { it.isNotBlank() }?.let {
            Text.translatable("cobblemarket.ban.changes_original_banner", BanState.reasonText(it))
        }

        // 行内：时间 · 执行者→目标 · 动作 · 时长 [· 原封禁者] [· 理由] —— 超宽会被截断，完整内容看悬停
        // ⚠ 全部**平级**挂在空父节点下：带色的段自己带色，` · ` 这些陪衬文字不设色（吃行色 0xFFFFFF）
        val text = Text.literal("")
            .append(Text.literal(at(changeTimeFormat)).formatted(Formatting.GRAY)).append("  ·  ")
            .append(whoRow).append("  ·  ")
            .append(actionRow).append("  ·  ")
            .append(durationRow)
        if (!isBan) originalRow?.let { text.append("  ·  ").append(it) }
        if (row.reason.isNotBlank()) text.append("  ·  ").append(BanState.reasonText(row.reason))

        // 悬停窗：同样的分段各自成行（时间给到秒），颜色与行内一一对应
        val tooltip = buildList {
            add(
                Text.literal("").append(Text.literal(at(changeTooltipTimeFormat)).formatted(Formatting.GRAY))
                    .append("   ").append(whoRow)
            )
            add(Text.literal("").append(actionRow).append("  ·  ").append(durationRow))
            originalRow?.let { add(it) }
            if (row.reason.isNotBlank()) {
                add(Text.literal("").append(Text.translatable("cobblemarket.ban.reason_label")).append(BanState.reasonText(row.reason)))
            }
        }
        return ChangeLine(text, tooltip)
    }

    private fun updateTabButtons() {
        // 选中的页签靠**按钮自己的选中态**表达（背景第三段），不在文字前加 `● `
        bansTabButton?.setMessage(Text.translatable("cobblemarket.ban.banlist_title"))
        bansTabButton?.selectedVisual = currentTab == 0
        changesTabButton?.setMessage(Text.translatable("cobblemarket.ban.tab_changes"))
        changesTabButton?.selectedVisual = currentTab == 1
    }

    private fun switchTab(tab: Int) {
        if (currentTab == tab) return
        currentTab = tab
        scrollOffset = 0
        hoveredRow = -1
        updateTabButtons()
        // 表单行（玩家名 / 时长 / 封禁按钮）只属于页签 0；联想列表同样只属于它
        val bansTab = tab == 0
        nameField?.visible = bansTab
        durationField?.visible = bansTab
        banButton?.visible = bansTab
        suggestionsOpen = false
        suggestDirty = false
        lastRequestPrefix = null
        rebuildSuggestionList()
        // ⚠⭐ **每次切到变更记录都要重拉**（照 `PermissionScreen.requestCurrentTabData`）：
        //   这一页是历史账本，刚封禁 / 刚解封（本界面或命令操作的）、别的管理员加的、
        //   金融自动冻结/解冻追加的，都可能在你停留期间出现。写成"只拉第一次"就会一直显示
        //   进界面那一刻的旧数据，**不退重进就看不到新的**（2026-10-04 用户实测报的）
        if (!bansTab) sendToServer(RequestBanChangesPayload())
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
            val y = banListStartY + i * rowHeight
            val btn = NineSliceButton(
                leftX + panelWidth - rowBtnInset, y + rowBtnDy, rowBtnW, rowBtnH,
                Text.translatable("cobblemarket.ban.unban"),
                { doUnban(entry) }
            )
            // 弹窗打开时 / 切到变更记录页签时都要藏起来（那一页没有可解封的行）
            btn.visible = pendingBanName.isEmpty() && currentTab == 0
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

        // 防抖触发联想请求（输入框只属于页签 0）
        if (currentTab == 0) checkSuggestDebounce()

        // 联想列表打开时跳过分隔线与列表（画在 children 之后，会刺穿联想按钮）
        if (suggestionsOpen) return

        drawPanelDivider(context, leftX + panelWidth / 2, listStartY() - 4)

        if (currentTab == 1) {
            renderChangeList(context, centerX, leftX)
            // 行悬停窗：行内会被截断，悬停给完整内容 —— 与封禁行同一套（变更记录没有行按钮，不用避让）
            if (hoveredRow >= 0) {
                val idx = scrollOffset + hoveredRow
                if (idx in changeLines.indices) {
                    renderTextTooltip(context, changeLines[idx].tooltip, mouseX, mouseY)
                }
            }
            return
        }

        if (bans.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.ban.banlist_empty").formatted(Formatting.GRAY),
                centerX, listStartY() + 30, 0xFFFFFF)
            return
        }

        bans.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, entry ->
            val y = listStartY() + i * rowHeight
            // 永久封禁按客户端语言渲染"永久"（服务端只发空串）
            val durationText = if (entry.expiresAt == null)
                Text.translatable("cobblemarket.ban.permanent")
            else
                // 服务端发的是**剩余毫秒**（不是格式化好的串），这里按词条渲染 ⇒ 跟客户端语言走
                com.shusheng.cobblemarket.market.BanState.remainingText(entry.remainingMs)
            // 行文字画到「解封按钮左侧留 4px 空隙」为止：按钮起于 panelWidth-60、宽 56，
            // 文字起于 +4 → 可用 = panelWidth - 60 - 4 - 4。金融封禁的原因词条很长，不截会压到按钮上
            context.drawTextWithShadow(textRenderer,
                TextUtil.truncateText(
                    Text.literal("${entry.playerName}  ·  ").append(com.shusheng.cobblemarket.market.BanState.reasonText(entry.bannedBy)).append("  ·  ").append(durationText),
                    panelWidth - 68
                ),
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
                // ⚠ 起点**必须跟着表单行走**（原来写死 62、是按表单在 44 那版算的；表单挪到 62 之后
                //   联想按钮就压在输入框上了）—— 用 formRowY 推，别再写字面量
                leftX + 2, formRowY + 18 + i * 14, 118, 14,
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

    /**
     * 页签 1「变更记录」的列表（照着权限管理那边的行样式）：
     * `时间(灰) · 执行者→目标(金) · 封禁(红)/解封(绿) · 时长 · 理由`，解封那行还带「原封禁者」。
     *
     * ⚠ 行文本里的 `执行者`/`动作`/`理由` 可能存的是 `$词条键`（系统来源）—— 一律过
     *   `BanState.reasonText` 解析：**按客户端语言**渲染，服务端不拼死任何一种语言。
     */
    private fun renderChangeList(context: DrawContext, centerX: Int, leftX: Int) {
        if (changeLines.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.ban.changes_empty").formatted(Formatting.GRAY),
                centerX, listStartY() + 30, 0xFFFFFF)
            return
        }

        changeLines.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, line ->
            val y = listStartY() + i * rowHeight
            context.drawTextWithShadow(textRenderer,
                TextUtil.truncateText(line.text, panelWidth - 8), leftX + 4, y + 5, 0xFFFFFF)
        }

        if (changeLines.size > maxVisible()) {
            context.drawCenteredTextWithShadow(textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible(), changeLines.size)} / ${changeLines.size}",
                width / 2, height - 49, 0x888888)
        }
    }

    private fun renderBanTooltip(context: DrawContext, entry: BanEntry, mouseX: Int, mouseY: Int) {
        val lines: List<Text> = buildList {
            add(Text.literal("${Text.translatable("cobblemarket.ban.target").string} ${entry.playerName}"))
            add(Text.literal("${Text.translatable("cobblemarket.ban.by").string} ${com.shusheng.cobblemarket.market.BanState.reasonText(entry.bannedBy).string}"))
            add(Text.literal("${Text.translatable("cobblemarket.ban.reason_label").string} ${com.shusheng.cobblemarket.market.BanState.reasonText(entry.reason).string.ifBlank { "-" }}"))
            // 勾了「同时禁止借款」才多这一行：这开关设完在别处看不见，悬停窗是唯一能回看的地方
            if (entry.blockLoan) add(Text.translatable("cobblemarket.ban.block_loan"))
        }

        // 悬停窗**不许把本行的解封按钮盖掉一半以上**：矩形按行号算几何（与 rebuildUnbanButtons 同源）
        renderTextTooltip(context, lines, mouseX, mouseY, avoid = unbanButtonRect(hoveredRow))
    }

    /**
     * 悬停窗的通用绘制（封禁行 / 变更记录行共用）。
     * @param lines **逐段带色**的完整内容 —— 行内会被截断，悬停这里给全，且颜色与行内一一对应
     * @param avoid 本行的按钮矩形：鼠标落在按钮上就不画（玩家准备点了）
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
        MarketPanelBackground.draw(context, width, height)

        val leftX = width / 2 - panelWidth / 2
        val visibleRows = maxVisible()
        val startY = listStartY()
        val listAreaBottom = startY + visibleRows * rowHeight
        val rowCount = if (currentTab == 0) bans.size else changeLines.size

        hoveredRow = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY in startY..listAreaBottom) {
            val row = (mouseY - startY) / rowHeight
            val shownCount = minOf(visibleRows, rowCount - scrollOffset)
            if (row in 0 until shownCount) hoveredRow = row
        }

        // 行底板两个页签都画（变更记录只是不给悬停窗，行本身照样要有斑马/悬停反馈）
        val drawn = minOf(visibleRows, maxOf(0, rowCount - scrollOffset))
        for (di in 0 until drawn) {
            val rowY = startY + di * rowHeight
            val rowState = if (di == hoveredRow) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, rowState, ROW_BACKGROUND_TEX_H)
        }
    }

    override fun shouldPause() = false
}
