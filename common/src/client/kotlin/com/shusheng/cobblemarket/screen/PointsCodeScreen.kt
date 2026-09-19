package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.PointCodes
import com.shusheng.cobblemarket.network.CreatePointCodesPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PointCodeInfo
import com.shusheng.cobblemarket.network.PointCodesCreatedPayload
import com.shusheng.cobblemarket.network.PlayerNameSuggestionsPayload
import com.shusheng.cobblemarket.network.PointCodesPayload
import com.shusheng.cobblemarket.network.RequestPlayerNameSuggestionsPayload
import com.shusheng.cobblemarket.network.PurgePointCodesPayload
import com.shusheng.cobblemarket.network.RequestPointCodesPayload
import com.shusheng.cobblemarket.network.RevokePointCodePayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * **兑换码管理**（管理员）：生成 / 查看 / 作废游戏内本地兑换码。
 *
 * 布局照 `AdminPointOrdersScreen`（同类 RP 管理端列表）：全高 `market_panel` 面板、
 * 顶部操作行下方一条 **300px 公共分割线**（[drawPanelDivider]）、分割线内滚动列表、
 * 底部「当前区间 / 总数」滚动指示。
 *
 * ⚠ **分层（本项目反复踩过）**：
 * - 面板与**行背景**画在 [renderBackground]（控件之下）→ 弹窗遮罩压得住
 * - 行**文字**画在 [render]（控件之上）；但**弹窗打开时跳过**，否则会浮在弹窗面上
 * - **标题也画在 [renderBackground]** —— 一开始画在 render 里，弹窗一开就压在弹窗上（用户实测）
 * - 弹窗面板画在 [renderBackground] 末尾（下层之后）、弹窗文字画在 [render] 末尾
 *
 * 三个设计点（用户 2026-09-19 拍板）：列表只显示**码前缀**（码 = 钱，整屏明文截图等于送出去一批）、
 * 每行**复制按钮**才取完整码；作废走二次确认；生成 ≤10 张回显、>10 张写文件。
 */
class PointsCodeScreen : Screen(Text.translatable("cobblemarket.points.code.title")) {

    // ── 布局（与 AdminPointOrdersScreen / 交易历史同一套口径）──
    // 296：**以黑名单界面为统一基准**（同背景的列表界面一律 296，行两侧各留 12px 给 320 的面板边框）
    private val panelWidth = 296
    private val rowHeight = 22
    private val listStartY = 68
    private val PANEL_BOTTOM_INSET = 32
    private val CONTENT_BOTTOM_INSET = 48
    private val GAP = 4
    /** 分割线半长：`market_panel` 那套贴图的界面一律 300px（±150） */
    private val DIVIDER_HALF = 150

    private fun leftX() = width / 2 - panelWidth / 2
    private fun maxVisible() = maxOf(1, (height - CONTENT_BOTTOM_INSET - listStartY) / rowHeight)

    // ── 列表状态 ──
    private var entries = listOf<PointCodeInfo>()
    private var scrollOffset = 0
    private var hoveredRow = -1
    private val rowButtons = mutableListOf<NineSliceButton>()
    private var backButton: NineSliceButton? = null
    private var createButton: NineSliceButton? = null

    // ── 生成弹窗 ──
    private val createW = 268

    /**
     * 生成弹窗高度：**按内容走**。只有五个输入框 + 两个按钮时紧凑（190），
     * 生成出结果后才撑到 300 给结果区腾地方 —— 写死 300 的话初始状态底下空一大截（用户实测）。
     */
    /**
     * 弹窗高度**固定**：一次给够（五个输入框 + 候选六行 + 结果区都放得下）。
     *
     * ⚠ 别改成"随内容伸缩"：那会「一输入玩家名，弹窗突然长高、把下层分割线露出来」——
     * 用户连提两次，他要的就是**开出来就高一点、之后纹丝不动**。
     */
    private fun createH() = 300
    private fun createX() = width / 2 - createW / 2
    private fun createY() = height / 2 - createH() / 2
    private var createOpen = false
    private var amountField: TextFieldWidget? = null
    private var countField: TextFieldWidget? = null
    private var usesField: TextFieldWidget? = null
    private var daysField: TextFieldWidget? = null
    private var boundField: TextFieldWidget? = null
    private var createConfirmButton: NineSliceButton? = null
    private var createCancelButton: NineSliceButton? = null
    // ── 绑定玩家名联想状态 ──
    private var suggestions = listOf<String>()
    private var suggestionsOpen = false
    private var suggestDirty = false
    private var lastSuggestEdit = 0L
    private var lastRequestPrefix = ""
    private var suppressSuggest = false
    private val suggestionButtons = mutableListOf<NineSliceButton>()
    private val MAX_SUGGESTION_ROWS = 6

    /** 上次生成的结果（≤10 张列全部码；>10 张只留文件路径） */
    private var createdCodes = listOf<String>()
    private var createdFilePath: String? = null
    private var createdSummary: String? = null

    // ── 作废确认弹窗 ──
    private var revokeTarget: PointCodeInfo? = null
    private var revokeConfirmButton: NineSliceButton? = null
    private var revokeCancelButton: NineSliceButton? = null

    // ── 清理已失效确认弹窗 ──
    private var purgeButton: NineSliceButton? = null
    private var purgeOpen = false
    /** 打开清理弹窗时算出的失效张数（客户端按已到手的列表算，服务端仍按自己的状态删） */
    private var purgeCount = 0
    private var purgeConfirmButton: NineSliceButton? = null
    private var purgeCancelButton: NineSliceButton? = null

    /** 操作结果提示（3 秒） */
    private var resultMsg: String? = null
    private var resultUntil = 0L

    private fun anyDialogOpen() = createOpen || revokeTarget != null || purgeOpen

    /**
     * 弹窗打开时隐藏主界面控件。
     *
     * ⚠ **新增常驻控件必须加进这里**（返回 / 生成 / 清理 / 行按钮）——
     * 之前每个 `openXxxDialog` 各写一份名单，加「清理失效」时只补了自己那份，
     * 另外两处漏掉 → 弹窗盖着、按钮还高亮能点（用户实测）。本项目已踩过三次，一律走这个函数。
     * 恢复不用管：关闭弹窗走 `clearChildren() + init()` 全量重建。
     */
    private fun hideMainControls() {
        backButton?.visible = false
        createButton?.visible = false
        purgeButton?.visible = false
        rowButtons.forEach { it.visible = false }
    }

    /** 客户端判定「已失效」：用完或已过期（与服务端 `PointCode.isUsable` 同一口径） */
    private fun isDead(e: PointCodeInfo, now: Long) =
        e.remainingUses <= 0 || (e.expiresAt in 1 until now)

    override fun init() {
        super.init()
        val leftX = leftX()

        // info 图标（**左上角**，位置与图标照 HistoryScreen 的同一个）：引导服主了解
        // 「用自己的网站 / 发卡网自动签发兑换码」（外部签名码协议）。
        // ⚠ **悬停文案待补**（用户 2026-09-19 拍板：按钮先放上，等官网有对应内容、
        //   即 1.3.0 发布前再把文案写进去 —— 现在指向官网会是死链）。素材与要说的要点
        //   见 memory 的 project-code-screen-tip-button。
        val infoButton = NineSliceButton(
            leftX, 18, 16, 16, Text.literal(""), { },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/info.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        )
        // TODO(发布 1.3.0 前)：infoButton.setTooltip(...) 写清外部签名码怎么用 + 官网链接
        addDrawableChild(infoButton)

        backButton = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16, Text.literal(""),
            { client?.setScreen(PointsConfigScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        // 顶部操作行（`y = TOP_ROW_Y`，与各管理端界面同一水平线）——⚠ 返回按钮独占 `y=13` 那一行，
        // 操作按钮必须落在**它下面这一行**，否则分割线上方会凭空空出一截（照 AdminPointOrdersScreen）
        // ⚠ 这两个按钮的**右缘与返回按钮竖着对齐**（= 面板右缘 `leftX + panelWidth`）——
        //   之前各留了 4px 间隙，看着就是没对齐（用户 2026-09-19 指出）。返回按钮宽 50、右缘贴齐，
        //   这两个宽 60 也一律右缘贴齐，往左依次排开
        createButton = NineSliceButton(
            leftX + panelWidth - 60, TOP_ROW_Y, 60, 16,
            Text.translatable("cobblemarket.points.code.create_button"),
            { openCreateDialog() }
        )
        addDrawableChild(createButton)

        // 清理已失效（用完 / 过期的一键删掉）—— 服务器开久了这类码攒得最快
        purgeButton = NineSliceButton(
            leftX + panelWidth - 60 - GAP - 60, TOP_ROW_Y, 60, 16,
            Text.translatable("cobblemarket.points.code.purge_button"),
            { openPurgeDialog() }
        )
        addDrawableChild(purgeButton)

        sendToServer(RequestPointCodesPayload())
        rebuildRowButtons()
    }

    fun onPointCodes(payload: PointCodesPayload) {
        entries = payload.entries
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, entries.size - maxVisible()))
        rebuildRowButtons()
    }

    fun onPointCodesCreated(payload: PointCodesCreatedPayload) {
        createdCodes = payload.codes
        createdFilePath = payload.filePath.ifEmpty { null }
        createdSummary = payload.message.string
        if (payload.success) sendToServer(RequestPointCodesPayload())
        if (createOpen) rebuildCreateDialogWidgets()
    }

    fun onMarketResult(payload: MarketResultPayload) {
        resultMsg = payload.message.string
        resultUntil = System.currentTimeMillis() + 3000
        if (payload.success) {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.GREEN), false)
            if (revokeTarget != null) closeRevokeDialog()
            if (purgeOpen) closePurgeDialog()
            sendToServer(RequestPointCodesPayload())
        } else {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.RED), false)
            playFailSound()
        }
    }

    // ── 行按钮 ──

    private fun rebuildRowButtons() {
        rowButtons.forEach { remove(it) }
        rowButtons.clear()
        // 弹窗打开时行按钮保持隐藏（否则会浮在遮罩之上刺穿）
        if (anyDialogOpen()) return
        val visible = entries.drop(scrollOffset).take(maxVisible())
        visible.forEachIndexed { i, entry ->
            val y = listStartY + i * rowHeight
            val btnY = y + (rowHeight - 14) / 2
            // 复制：把**完整码**放进剪贴板（行内只显示前缀）
            val copyBtn = NineSliceButton(
                leftX() + panelWidth - 8 - 72, btnY, 34, 14,
                Text.translatable("cobblemarket.points.code.copy"),
                {
                    client?.keyboard?.clipboard = PointCodes.format(entry.code)
                    resultMsg = Text.translatable("cobblemarket.points.code.copied").string
                    resultUntil = System.currentTimeMillis() + 3000
                }
            )
            rowButtons.add(copyBtn)
            addDrawableChild(copyBtn)
            // 作废 / 删除：**所有行都给**。已用完 / 已过期的码虽然已经不能用，
            // 但 `revoke` 底层就是从码表里移除 —— 不放按钮的话它们会永久占着列表（越积越长）。
            // 文案按状态区分：还能用的叫「作废」（是让它失效），已失效的叫「删除」（纯粹清掉）
            val usable = entry.remainingUses > 0 &&
                (entry.expiresAt == 0L || entry.expiresAt > System.currentTimeMillis())
            val revokeBtn = NineSliceButton(
                leftX() + panelWidth - 8 - 34, btnY, 34, 14,
                Text.translatable(
                    if (usable) "cobblemarket.points.code.revoke" else "cobblemarket.points.code.delete"
                ),
                { openRevokeDialog(entry) }
            )
            rowButtons.add(revokeBtn)
            addDrawableChild(revokeBtn)
        }
    }

    // ── 生成弹窗 ──

    private fun openCreateDialog() {
        createOpen = true
        createdCodes = listOf()
        createdFilePath = null
        createdSummary = null
        hideMainControls()
        rebuildCreateDialogWidgets()
    }

    private fun closeCreateDialog() {
        createOpen = false
        suggestions = listOf()
        suggestionsOpen = false
        suggestDirty = false
        suggestionButtons.clear()
        createdCodes = listOf()
        createdFilePath = null
        createdSummary = null
        amountField = null; countField = null; usesField = null
        daysField = null; boundField = null
        createConfirmButton = null; createCancelButton = null
        clearChildren()
        init()
    }

    private fun rebuildCreateDialogWidgets() {
        amountField?.let { remove(it) }; countField?.let { remove(it) }
        usesField?.let { remove(it) }; daysField?.let { remove(it) }
        boundField?.let { remove(it) }
        createConfirmButton?.let { remove(it) }; createCancelButton?.let { remove(it) }

        val cx = createX() + createW / 2
        val fieldX = cx - 48
        var y = createY() + 40

        fun addField(key: String, maxLen: Int, digits: Boolean): TextFieldWidget {
            val f = TextFieldWidget(textRenderer, fieldX, y, 96, 16, Text.literal(""))
            f.setPlaceholder(Text.translatable(key).formatted(Formatting.GRAY))
            f.setTextPredicate { s -> s.length <= maxLen && (!digits || s.all { it.isDigit() }) }
            addDrawableChild(f)
            y += 24
            return f
        }
        amountField = addField("cobblemarket.points.code.ph_amount", 12, true)
        countField = addField("cobblemarket.points.code.ph_count", 3, true)
        usesField = addField("cobblemarket.points.code.ph_uses", 3, true)
        daysField = addField("cobblemarket.points.code.ph_days", 5, true)
        boundField = addField("cobblemarket.points.code.ph_bound", 16, false)
        // 玩家名联想：停手 250ms 后向服务端要候选（照 AdminBanScreen 那套）
        boundField?.setChangedListener {
            // 点选候选填进来时跳过（否则列表收起后又被这次 changed 重新展开）
            if (suppressSuggest) {
                suppressSuggest = false
                return@setChangedListener
            }
            suggestDirty = true
            lastSuggestEdit = System.currentTimeMillis()
        }

        createConfirmButton = NineSliceButton(
            cx - 75, createY() + createH() - 28, 70, 18,
            Text.translatable("cobblemarket.points.code.generate"),
            { confirmCreate() }
        )
        addDrawableChild(createConfirmButton)
        createCancelButton = NineSliceButton(
            cx + 5, createY() + createH() - 28, 70, 18,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeCreateDialog() }
        )
        addDrawableChild(createCancelButton)
    }

    private fun confirmCreate() {
        val amount = amountField?.text?.toLongOrNull() ?: 0L
        val count = countField?.text?.toIntOrNull() ?: 0
        val uses = usesField?.text?.toIntOrNull() ?: 0
        val days = daysField?.text?.toIntOrNull() ?: 0
        if (amount <= 0 || count <= 0 || uses <= 0) {
            resultMsg = Text.translatable("cobblemarket.points.invalid_amount").string
            resultUntil = System.currentTimeMillis() + 3000
            playFailSound()
            return
        }
        sendToServer(
            CreatePointCodesPayload(
                amount = amount, count = count, uses = uses, days = days,
                boundName = boundField?.text?.trim().orEmpty()
            )
        )
    }

    // ── 作废确认弹窗 ──

    private fun openRevokeDialog(entry: PointCodeInfo) {
        revokeTarget = entry
        hideMainControls()
        val cx = width / 2
        val stillUsable = entry.remainingUses > 0 &&
            (entry.expiresAt == 0L || entry.expiresAt > System.currentTimeMillis())
        revokeConfirmButton = NineSliceButton(
            cx - 75, height / 2 + 20, 70, 18,
            Text.translatable(
                if (stillUsable) "cobblemarket.points.code.revoke_confirm"
                else "cobblemarket.points.code.delete_confirm"
            ),
            { revokeTarget?.let { sendToServer(RevokePointCodePayload(it.code)) } }
        )
        addDrawableChild(revokeConfirmButton)
        revokeCancelButton = NineSliceButton(
            cx + 5, height / 2 + 20, 70, 18,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeRevokeDialog() }
        )
        addDrawableChild(revokeCancelButton)
    }

    private fun closeRevokeDialog() {
        revokeTarget = null
        revokeConfirmButton = null
        revokeCancelButton = null
        clearChildren()
        init()
    }

    // ── 绑定玩家名联想（照 AdminBanScreen：防抖 + 陈旧响应过滤 + 候选按钮列表；含离线玩家）──

    fun onNameSuggestions(payload: PlayerNameSuggestionsPayload) {
        if (!createOpen) return
        // 陈旧响应：输入已变化（含点选候选填入的名字）→ 忽略，免得列表收起后又被展开
        if ((boundField?.text?.trim() ?: "") != lastRequestPrefix) return
        suggestions = payload.names
        suggestionsOpen = suggestions.isNotEmpty() && !(boundField?.text?.isBlank() ?: true)
        rebuildSuggestionList()
        // 候选占位让弹窗变高 → **只重摆位置**，别重建控件（重建会清空已填内容、丢焦点）
        layoutCreateDialog()
    }

    /** 防抖（render 每帧检查）：停手 250ms 再发，避免被服务端 500ms 节流静默丢弃 */
    private fun checkSuggestDebounce() {
        if (!suggestDirty) return
        if (System.currentTimeMillis() - lastSuggestEdit < 250) return
        suggestDirty = false
        lastRequestPrefix = boundField?.text?.trim().orEmpty()
        sendToServer(RequestPlayerNameSuggestionsPayload(lastRequestPrefix))
    }

    private fun rebuildSuggestionList() {
        suggestionButtons.forEach { remove(it) }
        suggestionButtons.clear()
        if (!suggestionsOpen) return
        val f = boundField ?: return
        suggestions.take(MAX_SUGGESTION_ROWS).forEachIndexed { i, name ->
            val btn = NineSliceButton(
                f.x, f.y + 18 + i * 14, f.width, 14, Text.literal(name),
                {
                    suppressSuggest = true
                    boundField?.text = name
                    suggestionsOpen = false
                    rebuildSuggestionList()
                    layoutCreateDialog()
                }
            )
            suggestionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // ── 清理已失效 ──

    /**
     * 按当前弹窗高度**重新摆放**已有控件（改 x/y）。
     *
     * ⚠ **绝不能在这里重建控件**（`rebuildCreateDialogWidgets`）：那会删掉旧输入框再建新的，
     * 玩家已经填的内容和焦点会一起没 —— 表现为「点一下输入框，别的框全清空、自己也输不进去」
     * （2026-09-19 用户实测）。弹窗高度变化只影响位置，改坐标就够了。
     */
    private fun layoutCreateDialog() {
        val cx = createX() + createW / 2
        val fieldX = cx - 48
        var y = createY() + 40
        listOf(amountField, countField, usesField, daysField, boundField).forEach { f ->
            f?.let { it.x = fieldX; it.y = y }
            y += 24
        }
        val btnY = createY() + createH() - 28
        createConfirmButton?.let { it.x = cx - 75; it.y = btnY }
        createCancelButton?.let { it.x = cx + 5; it.y = btnY }
        laySuggestionList()
    }

    /** 候选按钮位置跟着绑定输入框走（输入框刚重摆过，这里必须再算一次） */
    private fun laySuggestionList() {
        val f = boundField ?: return
        suggestionButtons.forEachIndexed { i, btn ->
            btn.x = f.x
            btn.y = f.y + 18 + i * 14
        }
    }

    private fun openPurgeDialog() {
        val now = System.currentTimeMillis()
        purgeCount = entries.count { isDead(it, now) }
        if (purgeCount == 0) {
            resultMsg = Text.translatable("cobblemarket.points.code.purge_none").string
            resultUntil = System.currentTimeMillis() + 3000
            return
        }
        purgeOpen = true
        hideMainControls()
        val cx = width / 2
        purgeConfirmButton = NineSliceButton(
            cx - 75, height / 2 + 20, 70, 18,
            Text.translatable("cobblemarket.points.code.purge_confirm"),
            { sendToServer(PurgePointCodesPayload()) }
        )
        addDrawableChild(purgeConfirmButton)
        purgeCancelButton = NineSliceButton(
            cx + 5, height / 2 + 20, 70, 18,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closePurgeDialog() }
        )
        addDrawableChild(purgeCancelButton)
    }

    private fun closePurgeDialog() {
        purgeOpen = false
        purgeCount = 0
        purgeConfirmButton = null
        purgeCancelButton = null
        clearChildren()
        init()
    }

    // ── 渲染（背景层）──

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int, sliceH: Int = 16) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // ⚠ 面板是 **320 宽**（`drawPanelSlice` 内部按 640×0.5 画），而行是 300 宽 —— 两侧各留 10px
        //   给贴图边框。把 panelLeft 写成 `leftX()`（= 行左缘）会让面板整体左移 10px、行顶在边框上，
        //   看着就是「行跑出背景、右边空一截」（2026-09-19 用户实测）。
        //   口径与 AdminPointOrdersScreen 的 `width / 2 - 160` 一致。
        val panelLeft = width / 2 - 160
        val panelTop = 2
        val panelBottom = height - PANEL_BOTTOM_INSET
        val sliceH = 16
        drawPanelSlice(context, PANEL_TOP, panelLeft, panelTop)
        var y = panelTop + sliceH
        while (y < panelBottom - sliceH) {
            drawPanelSlice(context, PANEL_MID, panelLeft, y, minOf(sliceH, panelBottom - sliceH - y))
            y += sliceH
        }
        drawPanelSlice(context, PANEL_BOT, panelLeft, panelBottom - sliceH)

        // 标题画在**这一层**（控件之下，放 render 里会压在弹窗上）；弹窗打开时**干脆不画** ——
        // 只靠遮罩压暗的话它还是看得见，玩家会以为弹窗标题和它串在一起（用户实测）
        if (!anyDialogOpen()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.code.title").formatted(Formatting.GOLD),
                width / 2, 20, 0xFFFFFF
            )
        }

        // 行背景：弹窗打开时**照画**（靠遮罩压暗），而不是不画。
        // ⚠ 行用 **`leftX()`**（300 宽居中），**不是 `panelLeft`**（那是 320 宽的面板左缘）——
        //   误用会让整行左移 10px：行跑出背景左缘、而按 `leftX()` 定位的行按钮超到行右端外面。
        //   行按钮（rebuildRowButtons）与这里必须同用 `leftX()`，两边口径一致才对得上。
        val visible = maxVisible()
        entries.drop(scrollOffset).take(visible).forEachIndexed { i, _ ->
            val y = listStartY + i * rowHeight
            drawNineSlice(
                context, ROW_BACKGROUND_TEXTURE, leftX(), y, panelWidth, rowHeight,
                if (i == hoveredRow) 1 else 0, ROW_BACKGROUND_TEX_H
            )
        }

        // 弹窗面板：必须在**下层之后**（这样下层是被压暗、而不是消失）
        if (anyDialogOpen()) {
            drawScreenDimMask(context, width, height)
            drawNineSlice(
                context, DIALOG_BACKGROUND_TEXTURE,
                createX(), createY(), createW, createH(), 0, DIALOG_BACKGROUND_TEX_H
            )
        }
    }

    // ── 渲染（前景层）──

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        checkSuggestDebounce()
        super.render(context, mouseX, mouseY, delta)
        val cx = width / 2

        // 弹窗打开时**不画列表文字、也不画分割线** —— 它们画在 render 里（控件与遮罩**之上**），
        // 照画就会浮在弹窗面上（用户实测：「下层分割线透过来了」）。标题已在背景层、被遮罩压暗
        if (anyDialogOpen()) {
            renderDialogForeground(context)
            return
        }

        // 分割线（公共 300px 灰线）：位置照**有顶部操作行的**同背景界面 ——
        // `topRowY + 操作行高 16 + GAP + 2`（充值订单就是这个式子）。⚠ 别用封禁的 `listStartY - 4`：
        // 那个界面没有操作行，式子不通用。首行 68 − 分割线 62 = 6px，与订单界面一致。
        drawPanelDivider(context, leftX() + panelWidth / 2, TOP_ROW_Y + 16 + GAP + 2)

        if (entries.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.code.empty").formatted(Formatting.GRAY),
                cx, listStartY + 24, 0xAAAAAA
            )
        } else {
            val now = System.currentTimeMillis()
            // **一行一条**（行高 22 是模组列表的惯例，塞两行会粘连且与列标题对不上）。
            // 次要信息（绑定 / 有效期 / 创建者）走悬停面板，见下面的 tooltip。
            entries.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, e ->
                val y = listStartY + i * rowHeight + 7
                // 码只显示前缀（完整码走旁边的复制按钮，防截图外泄）
                context.drawTextWithShadow(
                    textRenderer, PointCodes.format(e.code).take(9) + "…", leftX() + COL_CODE, y, 0xFFDD99
                )
                context.drawTextWithShadow(
                    textRenderer, "×${e.remainingUses}/${e.totalUses}", leftX() + COL_USES, y, 0xFFFFFF
                )
                context.drawTextWithShadow(
                    textRenderer,
                    CurrencyService.amountText(e.amount, MarketCurrency.POINTS),
                    leftX() + COL_AMOUNT, y, 0xFFAA00
                )
                val (statusKey, statusColor) = when {
                    e.remainingUses <= 0 -> "cobblemarket.points.code.status_used" to 0x888888
                    e.expiresAt in 1 until now -> "cobblemarket.points.code.status_expired" to 0x888888
                    else -> "cobblemarket.points.code.status_usable" to 0x55FF55
                }
                context.drawTextWithShadow(
                    textRenderer, Text.translatable(statusKey), leftX() + COL_STATUS, y, statusColor
                )
            }
            if (entries.size > maxVisible()) {
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible(), entries.size)} / ${entries.size}",
                    cx, height - 49, 0x888888
                )
            }
            // 悬停：绑定 / 有效期 / 创建者（⚠ **不放完整码** —— 悬停截图同样会泄露，码走复制按钮）
            entries.getOrNull(scrollOffset + hoveredRow)?.let { e ->
                val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                val lines = mutableListOf<Text>()
                lines.add(
                    if (e.boundName.isEmpty()) Text.translatable("cobblemarket.points.code.tip_unbound")
                    else Text.translatable("cobblemarket.points.code.tip_bound", e.boundName)
                )
                lines.add(
                    if (e.expiresAt == 0L) Text.translatable("cobblemarket.points.code.tip_never")
                    else Text.translatable(
                        "cobblemarket.points.code.tip_expires",
                        Instant.ofEpochMilli(e.expiresAt).atZone(ZoneId.systemDefault()).format(fmt)
                    )
                )
                lines.add(Text.translatable("cobblemarket.points.code.tip_created_by", e.createdBy))
                context.drawTooltip(textRenderer, lines, mouseX, mouseY)
            }
        }

        resultMsg?.let { msg ->
            if (System.currentTimeMillis() < resultUntil) {
                context.drawCenteredTextWithShadow(textRenderer, msg, cx, height - 62, 0x55FF55)
            }
        }
    }

    /** 弹窗的文字层（画在控件与遮罩之上）：生成表单的标签与结果、作废确认的正文 */
    private fun renderDialogForeground(context: DrawContext) {
        val cx = width / 2
        if (purgeOpen) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.code.purge_title").formatted(Formatting.GOLD),
                cx, createY() + 20, 0xFFFFFF
            )
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.code.purge_body", purgeCount),
                cx, createY() + 40, 0xFFFFFF
            )
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.code.revoke_warn").formatted(Formatting.RED),
                cx, createY() + 54, 0xFFFFFF
            )
            return
        }
        val entry = revokeTarget
        if (entry != null) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.code.revoke_title").formatted(Formatting.GOLD),
                cx, createY() + 20, 0xFFFFFF
            )
            val stillUsable = entry.remainingUses > 0 &&
                (entry.expiresAt == 0L || entry.expiresAt > System.currentTimeMillis())
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(
                    if (stillUsable) "cobblemarket.points.code.revoke_body"
                    else "cobblemarket.points.code.delete_body",
                    PointCodes.format(entry.code).take(9) + "…"
                ),
                cx, createY() + 40, 0xFFFFFF
            )
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.code.revoke_warn").formatted(Formatting.RED),
                cx, createY() + 54, 0xFFFFFF
            )
            return
        }
        if (!createOpen) return
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.code.create_title").formatted(Formatting.GOLD),
            cx, createY() + 16, 0xFFFFFF
        )
        // 标签：右对齐到输入框左侧 4px
        val labelX = createX() + createW / 2 - 48 - 4
        val labels = listOf(
            "cobblemarket.points.code.label_amount", "cobblemarket.points.code.label_count",
            "cobblemarket.points.code.label_uses", "cobblemarket.points.code.label_days",
            "cobblemarket.points.code.label_bound"
        )
        labels.forEachIndexed { i, key ->
            val label = Text.translatable(key).string
            context.drawTextWithShadow(
                textRenderer, label,
                labelX - textRenderer.getWidth(label), createY() + 44 + i * 24, 0xFFFFFF
            )
        }
        var y = createY() + 40 + 5 * 24
        createdSummary?.let { summary ->
            context.drawCenteredTextWithShadow(textRenderer, summary, cx, y, 0x55FF55)
            y += 13
            if (createdCodes.isEmpty() && createdFilePath != null) {
                context.drawCenteredTextWithShadow(textRenderer, createdFilePath!!, cx, y, 0xFFDD99)
            } else {
                val shown = createdCodes.take(MAX_INLINE_ROWS)
                shown.forEach { code ->
                    context.drawCenteredTextWithShadow(textRenderer, PointCodes.format(code), cx, y, 0xFFDD99)
                    y += 11
                }
                if (createdCodes.size > shown.size) {
                    context.drawCenteredTextWithShadow(
                        textRenderer,
                        Text.translatable("cobblemarket.points.code.more", createdCodes.size - shown.size),
                        cx, y, 0x888888
                    )
                }
            }
        }
    }

    // ── 交互 ──

    private fun isInputFocused(): Boolean =
        focused == amountField || focused == countField || focused == usesField ||
            focused == daysField || focused == boundField

    private fun isMouseOverAnyInput(mx: Double, my: Double): Boolean =
        listOfNotNull(amountField, countField, usesField, daysField, boundField)
            .any { mx >= it.x && mx < it.x + it.width && my >= it.y && my < it.y + it.height }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val wasInInput = isInputFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) focused = null
        return result
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (anyDialogOpen()) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        val max = maxOf(0, entries.size - maxVisible())
        if (max <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        val target = (scrollOffset - verticalAmount.toInt()).coerceIn(0, max)
        if (target == scrollOffset) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        scrollOffset = target
        rebuildRowButtons()
        return true
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        super.mouseMoved(mouseX, mouseY)
        hoveredRow = if (anyDialogOpen()) -1 else {
            val rel = ((mouseY - listStartY) / rowHeight).toInt()
            if (mouseY >= listStartY && rel in 0 until minOf(maxVisible(), entries.size - scrollOffset)) rel else -1
        }
    }

    override fun shouldPause() = false

    private companion object {
        /** 生成结果里最多列几行码（再多就超弹窗高度；>10 张本来就走文件） */
        const val MAX_INLINE_ROWS = 8

        /** 顶部操作行的 y（与各管理端界面同一水平线；返回按钮独占它上面那一行） */
        const val TOP_ROW_Y = 40

        // 列的固定偏移（相对 leftX）：固定列才与整体观感一致，别用前一列的文字宽度推。
        // 右端 220 起是按钮区（复制 34 + 间隙 + 作废 34 + 边距），状态列不能压过去
        const val COL_CODE = 6
        const val COL_USES = 66
        const val COL_AMOUNT = 110
        const val COL_STATUS = 172

        val PANEL_TOP = Identifier.of("cobblemarket", "textures/gui/market_panel_top.png")
        val PANEL_MID = Identifier.of("cobblemarket", "textures/gui/market_panel_middle.png")
        val PANEL_BOT = Identifier.of("cobblemarket", "textures/gui/market_panel_bottom.png")
    }
}
