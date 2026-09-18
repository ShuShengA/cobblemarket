package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.GuiColors
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.currency.PointOrderNumbers
import com.shusheng.cobblemarket.currency.PointOrderStatus
import com.shusheng.cobblemarket.currency.PointShopService
import com.shusheng.cobblemarket.network.ConfirmPointOrderPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PointOrderInfo
import com.shusheng.cobblemarket.network.PointOrdersPayload
import com.shusheng.cobblemarket.network.RejectPointOrderPayload
import com.shusheng.cobblemarket.network.RequestPointOrdersPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/**
 * **充值订单**（管理端，仅 OP）：待确认列表 → 核对收款账单 → 确认发放 / 作废。
 *
 * 这是方案「RP 充值」里那个**唯一的人肉环节** —— 个人收款码没有支付回调，模组也**无法判断
 * 玩家到底付没付**（「我已支付」只是声明）。所以这里的每一行都要服主对着账单核一遍，
 * 列表把**两条匹配键**（实付金额含唯一尾数 / 订单号）都摆出来：
 * - 国内个人码：金额尾数一眼对上，不依赖玩家备注
 * - PayPal / Stripe 等有手续费的渠道：尾数会失效，得靠订单号（玩家报的也是它）
 *
 * ⚠ **发放走服务端 `PointShopService.confirm`**（与命令同一内核，幂等在那儿）——
 * 界面只管收集点击、弹窗确认、刷新列表，绝不自己判断能不能发。
 *
 * ⚠ 待确认订单**不超时**（玩家已付款，自动作废等于吞钱），所以列表和确认弹窗都要显示
 * **下单时间**（`MM-dd HH:mm`）—— 服主得知道「这是哪天的单、压了多久」，两个 tab 用同一种格式、
 * 且时间列左右对齐（2026-09-18 用户要求与「我的订单」保持一致）。
 */
class AdminPointOrdersScreen : Screen(Text.translatable("cobblemarket.points.admin.title")) {

    /**
     * 列表行宽。**面板本身是 320**（`drawPanelSlice` 里贴图 640 宽 × scale(0.5)，宽度钉死，
     * 改不了），行比面板窄一点、居中放在框内 —— 与交易历史 / 黑名单同一套。
     */
    private val panelWidth = 300
    private val rowHeight = 22

    /** 列表首行的 y：分割线下方留一点空隙 */
    private val listStartY = 68

    /** 相邻控件之间的最小空隙（按钮间距规则） */
    private val GAP = 4

    /** 列表分割线半长：`market_panel` 那套贴图的界面**一律 300px**（±150） */
    private val DIVIDER_HALF = 150

    // 列相对 leftX 的偏移。⚠ 320 的面板放不下「玩家名/档位/金额/订单号/状态/等待/两个按钮」，
    // 砍掉档位列（弹窗里会显示）并把两个操作收成一个「处理」按钮，才塞得下
    private val colName = 6
    private val colMoney = 64
    private val colOrder = 110
    private val colStatus = 214
    /** 下单时间（`MM-dd HH:mm`）—— 两个 tab 都要，与「我的订单」同一种格式 */
    private val colTime = 154
    private val colHandle = 254
    // 已完结没有行按钮，右边空出来的地方给「发了多少 RP」
    // 已完结：玩家名 / 金额 / 订单号 / **下单时间** / 结果（发放的写 +N RP、作废的写状态 —— 并成一列）
    private val colFinishedTime = 154
    private val colFinishedResult = 226

    /** true = 看已完结（已发放/已作废），false = 看待确认 */
    private var showFinished = false

    private var searchField: TextFieldWidget? = null
    private var orders = listOf<PointOrderInfo>()
    private var scrollOffset = 0
    private var hoveredRow = -1

    private val rowButtons = mutableListOf<NineSliceButton>()
    private var backButton: NineSliceButton? = null
    private var pendingTabButton: NineSliceButton? = null
    private var finishedTabButton: NineSliceButton? = null

    /** 正在弹窗确认的那一笔（null = 没开弹窗）；[confirmingGrant] 区分确认发放 / 作废 */
    private var confirmingOrder: PointOrderInfo? = null
    private var confirmingGrant = false
    private val dialogButtons = mutableListOf<NineSliceButton>()

    /** 列表请求已发出、还没回来（防连点重复请求） */
    private var loading = false

    private fun leftX() = width / 2 - panelWidth / 2

    private fun maxVisible() = maxOf(1, (height - listStartY - 8) / rowHeight)

    override fun init() {
        super.init()
        val leftX = leftX()

        backButton = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16, Text.literal(""),
            { client?.setScreen(AdminScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        // 顶部一行：两个 tab + 订单号搜索框 + 搜索按钮，**从左往右排、间距 4、正好铺满面板宽**
        // （⚠ 之前每个元素各自拍坐标，改面板宽度时没同步改，输入框直接穿到按钮上还出了面板）
        val tabW = 60
        pendingTabButton = NineSliceButton(leftX, topRowY, tabW, 16, Text.literal(""), { switchTab(false) })
        finishedTabButton =
            NineSliceButton(leftX + tabW + GAP, topRowY, tabW, 16, Text.literal(""), { switchTab(true) })
        addDrawableChild(pendingTabButton)
        addDrawableChild(finishedTabButton)
        updateTabButtons()

        val searchX = leftX + (tabW + GAP) * 2
        val searchFieldW = panelWidth - (tabW + GAP) * 2
        // 搜索框：**输入即搜**（250ms 防抖），与物品管理 / 精灵管理 / 黑名单一致 ——
        // 那几个界面都没有搜索按钮，这里也不该有（2026-09-18 用户指出）
        val saved = searchField?.text ?: ""
        searchField = TextFieldWidget(textRenderer, searchX, topRowY, searchFieldW, 16, Text.literal(""))
        searchField?.setMaxLength(48)
        searchField?.setPlaceholder(Text.translatable("cobblemarket.points.admin.search_hint"))
        searchField?.setChangedListener {
            pendingQuery = it
            searchDirty = true
            lastSearchEdit = System.currentTimeMillis()
        }
        addSelectableChild(searchField)
        addDrawableChild(searchField)
        searchField?.text = saved

        requestList()
    }

    private var pendingQuery = ""

    /** 搜索防抖（照物品/精灵管理：250ms 静默后统一发请求，连续输入只发最终态） */
    private var searchDirty = false
    private var lastSearchEdit = 0L

    /** 顶部操作行的 y（与各管理端界面同一水平线） */
    private val topRowY = 40

    /** 顶部行下方那条分割线：与列表首行的间距 */
    private val dividerY = topRowY + 16 + GAP + 2

    private fun switchTab(finished: Boolean) {
        if (showFinished == finished) return
        showFinished = finished
        scrollOffset = 0
        updateTabButtons()
        requestList()
    }

    private fun updateTabButtons() {
        fun label(key: String, selected: Boolean) = Text.literal(
            Text.translatable(key).string
        ).let { if (selected) com.shusheng.cobblemarket.util.TextUtil.selectedText(it.string) else it }
        pendingTabButton?.setMessage(label("cobblemarket.points.admin.tab_pending", !showFinished))
        finishedTabButton?.setMessage(label("cobblemarket.points.admin.tab_finished", showFinished))
    }

    private fun requestList() {
        loading = true
        sendToServer(RequestPointOrdersPayload(showFinished, searchField?.text?.trim().orEmpty()))
    }

    /** 列表到了：换数据 + 重建行按钮 + 把滚动位置夹回合法范围 */
    fun onOrders(payload: PointOrdersPayload) {
        // 下拉来的可能是另一个 tab 的（服主连点两个 tab）—— 丢掉过期的
        if (payload.includeFinished != showFinished) return
        orders = payload.orders
        loading = false
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, orders.size - maxVisible()))
        rebuildRowButtons()
    }

    /** 确认 / 作废的结果：聊天栏说一声（详细原因在 message 里），然后刷新列表 */
    fun onResult(payload: MarketResultPayload) {
        client?.player?.sendMessage(
            payload.message.copy().formatted(if (payload.success) Formatting.GREEN else Formatting.RED), false
        )
        if (payload.success) requestList()
    }

    // ── 确认弹窗 ──

    private fun openDialog(order: PointOrderInfo) {
        confirmingOrder = order
        rebuildDialogButtons()
    }

    private fun closeDialog() {
        confirmingOrder = null
        rebuildDialogButtons()
        rebuildRowButtons()
    }

    /**
     * 弹窗里**发放 / 作废并排 + 取消**（三个按钮）。
     *
     * 列表里每行只放得下一个「处理」按钮（面板是 320 宽的死数，塞不下两个操作按钮），
     * 两个动作因此都收进弹窗 —— 反正方案要求发放前必须弹窗确认，顺手把作废也放这儿。
     */
    private fun rebuildDialogButtons() {
        dialogButtons.forEach { remove(it) }
        dialogButtons.clear()
        val open = confirmingOrder != null
        // ⚠ 下层控件全部藏掉 —— **不是「不画」而是 visible = false**：
        //   控件由 super.render() 绘制，遮罩挡不住它们的点击，只有 visible=false 才真的不可互动
        listOfNotNull(searchField, pendingTabButton, finishedTabButton)
            .forEach { it.visible = !open }
        if (!open) return
        rowButtons.forEach { it.visible = false }
        val dx = width / 2 - 160
        val dy = height / 2 - 60
        dialogButtons += addDrawableChild(
            NineSliceButton(
                dx + 16, dy + 92, 90, 20,
                Text.translatable("cobblemarket.points.admin.do_grant"), { doConfirm(grant = true) }
            )
        )
        dialogButtons += addDrawableChild(
            NineSliceButton(
                dx + 115, dy + 92, 90, 20,
                Text.translatable("cobblemarket.points.admin.do_void"), { doConfirm(grant = false) }
            )
        )
        dialogButtons += addDrawableChild(
            NineSliceButton(
                dx + 214, dy + 92, 90, 20,
                Text.translatable("cobblemarket.buy_confirm.cancel"), { closeDialog() }
            )
        )
    }

    private fun doConfirm(grant: Boolean) {
        val order = confirmingOrder ?: return
        if (grant) sendToServer(ConfirmPointOrderPayload(order.orderNo))
        else sendToServer(RejectPointOrderPayload(order.orderNo))
        closeDialog()
    }

    // ── 行按钮 ──

    private fun rebuildRowButtons() {
        rowButtons.forEach { remove(it) }
        rowButtons.clear()
        if (confirmingOrder != null) return
        val visible = maxVisible()
        val btnX = leftX() + colHandle
        orders.forEachIndexed { index, order ->
            val rel = index - scrollOffset
            if (rel !in 0 until visible) return@forEachIndexed
            val y = listStartY + rel * rowHeight + 3
            // 已完结的不给按钮：发放幂等、作废过的也没意义，能点的只有待确认
            if (statusOf(order) != PointOrderStatus.PENDING_CONFIRM) return@forEachIndexed
            rowButtons += addDrawableChild(
                NineSliceButton(
                    btnX, y, 46, 16,
                    Text.translatable("cobblemarket.points.admin.handle"), { openDialog(order) }
                )
            )
        }
    }

    private fun isInputFieldFocused() = focused != null && focused === searchField

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true

    /**
     * **点击空白失焦**（含输入框的界面必做）：不做的话点空白退不出输入态，
     * **E 键会被输入框吃掉、界面关不掉**。
     * 先记「点之前焦点在不在输入框」再交给 super —— 让点击先落到控件上（点输入框内部不能误清）。
     */
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) focused = null
        return result
    }

    override fun mouseScrolled(
        mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double
    ): Boolean {
        if (confirmingOrder != null) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        val max = maxOf(0, orders.size - maxVisible())
        if (max <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        val target = (scrollOffset - verticalAmount.toInt()).coerceIn(0, max)
        if (target == scrollOffset) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        scrollOffset = target
        rebuildRowButtons()
        return true
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        super.mouseMoved(mouseX, mouseY)
        hoveredRow = -1
        if (mouseX < leftX() || mouseX > leftX() + panelWidth) return
        val rel = ((mouseY - listStartY) / rowHeight).toInt()
        if (mouseY >= listStartY && rel in 0 until minOf(maxVisible(), orders.size - scrollOffset)) hoveredRow = rel
    }

    // ── 渲染 ──

    /** 下单时间文本（`MM-dd HH:mm`，与交易历史 / 我的订单同一格式） */
    private fun orderTimeText(createdAt: Long): String =
        if (createdAt <= 0L) "" else java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(createdAt))

    private fun statusOf(order: PointOrderInfo): PointOrderStatus? =
        runCatching { PointOrderStatus.valueOf(order.status) }.getOrNull()

    private fun texOf(order: PointOrderInfo): String {
        val label = com.shusheng.cobblemarket.client.PointsShopCache.currencyLabel
        val cents = PointShopService.formatCents(order.payCents)
        return if (label.isEmpty()) cents else "$cents $label"
    }


    /** 面板三段贴图（640×32 缩 0.5 ⇒ 固定 320 宽），与交易历史 / 黑名单同一套画法 */
    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int, sliceH: Int = 16) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // ⚠ 下层**照画**（面板 + 行 + 文字），弹窗时靠遮罩把它压暗 ——
        //   而不是「弹窗时不画」。不画的话背景会整个消失，只剩控件浮在那儿（2026-09-18 用户实测）。
        //   这是「确认弹窗黄金模板」的画法（AdminAuctionScreen.openCancelDialog 那套）。
        val panelLeft = width / 2 - 160
        val panelTop = 2
        val panelBottom = height - 32
        val sliceH = 16
        drawPanelSlice(context, PANEL_TOP, panelLeft, panelTop)
        var y = panelTop + sliceH
        while (y < panelBottom - sliceH) {
            drawPanelSlice(context, PANEL_MID, panelLeft, y, minOf(sliceH, panelBottom - sliceH - y))
            y += sliceH
        }
        drawPanelSlice(context, PANEL_BOT, panelLeft, panelBottom - sliceH)

        // ⚠ **列表行画在这里**（renderBackground）而不是 render 里：控件（含每行的「处理」按钮）
        //   由 super.render() 绘制，行背景若画在 render 里就会把它们**整个盖住**，
        //   只剩按钮上的文字透出来、看着像一行纯文字（2026-09-18 用户实测）。
        //   AdminAuctionScreen 也是这么摆的。
        drawRows(context)

        // 弹窗：遮罩（把上面画好的下层压暗）+ 弹窗面板。顺序必须在**下层之后** ——
        // 这也是「下层背景还在、只是变暗」的关键
        confirmingOrder?.let {
            drawScreenDimMask(context, width, height)
            drawNineSlice(
                context, DIALOG_BACKGROUND_TEXTURE,
                width / 2 - 160, height / 2 - 60, 320, 124, 0, DIALOG_BACKGROUND_TEX_H
            )
        }
    }

    /** 画可见的订单行（半透明行底 + 悬停高亮） */
    private fun drawRows(context: DrawContext) {
        val leftX = leftX()
        val visible = maxVisible()
        orders.forEachIndexed { index, _ ->
            val rel = index - scrollOffset
            if (rel !in 0 until visible) return@forEachIndexed
            val y = listStartY + rel * rowHeight
            drawNineSlice(
                context, ROW_BACKGROUND_TEXTURE, leftX, y, panelWidth, rowHeight,
                if (rel == hoveredRow) 1 else 0, ROW_BACKGROUND_TEX_H
            )
        }
    }

    private companion object {
        val PANEL_TOP = Identifier.of("cobblemarket", "textures/gui/market_panel_top.png")
        val PANEL_MID = Identifier.of("cobblemarket", "textures/gui/market_panel_middle.png")
        val PANEL_BOT = Identifier.of("cobblemarket", "textures/gui/market_panel_bottom.png")
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val leftX = leftX()
        val centerX = width / 2

        confirmingOrder?.let { order ->
            renderDialog(context, order)
            return
        }

        // 搜索防抖：停手 250ms 后统一发一次请求（边打边发会被服务端节流丢掉中间态）
        if (searchDirty && System.currentTimeMillis() - lastSearchEdit >= 250) {
            searchDirty = false
            requestList()
        }

        context.drawCenteredTextWithShadow(
            textRenderer, Text.translatable("cobblemarket.points.admin.title").formatted(Formatting.GOLD),
            centerX, 16, 0xFFFFFF
        )

        // 顶部操作行下方那条分割线：走**公共的** [drawPanelDivider]（灰色 0xFF555555、300px 居中）——
        // 交易历史 / 黑名单 / 物品管理这些列表界面用的都是它。
        // ⚠ 别自己 fill：入口界面/管理面板底部那条是**红色**的 GuiColors.DIVIDER，两回事
        drawPanelDivider(context, leftX + panelWidth / 2, dividerY)

        // 列表行**只画文字**，行背景在 renderBackground（见那里的注释：放这儿会盖住按钮）
        val visible = maxVisible()
        orders.forEachIndexed { index, order ->
            val rel = index - scrollOffset
            if (rel !in 0 until visible) return@forEachIndexed
            val y = listStartY + rel * rowHeight
            val ty = y + 7
            // 前三列两个 tab 共用（换 tab 时不会跳）
            context.drawTextWithShadow(textRenderer, Text.literal(order.playerName), leftX + colName, ty, 0xFFFFFF)
            // 金额与订单号是**核对账单的两条键**，用金色标出来
            context.drawTextWithShadow(
                textRenderer, Text.literal(texOf(order)).formatted(Formatting.GOLD), leftX + colMoney, ty, 0xFFFFFF
            )
            context.drawTextWithShadow(
                textRenderer, Text.literal(PointOrderNumbers.format(order.orderNo)).formatted(Formatting.GOLD),
                leftX + colOrder, ty, 0xFFFFFF
            )
            val statusKey = when (statusOf(order)) {
                PointOrderStatus.PENDING_CONFIRM -> "cobblemarket.points.admin.st_waiting"
                PointOrderStatus.GRANTED -> "cobblemarket.points.admin.st_granted"
                PointOrderStatus.VOID -> "cobblemarket.points.admin.st_void"
                else -> "cobblemarket.points.admin.st_unpaid"
            }
            // ⚠ 后两列**按 tab 分叉**：
            //   待确认要的是「等了多久」（服主据此判断这单压了多长时间）；
            //   已完结要的是「发了多少 RP」—— 已完结订单的等待时长只会随时间一直涨（那是
            //   从提交到现在），越看越糊涂，对账要看的是这笔最终发出去了多少。
            if (showFinished) {
                val granted = statusOf(order) == PointOrderStatus.GRANTED
                // 下单时间（`MM-dd HH:mm`）—— 处理完之后只剩相对时间就看不出「这是哪天充的」了
                context.drawTextWithShadow(
                    textRenderer, Text.literal(orderTimeText(order.createdAt)),
                    leftX + colFinishedTime, ty, 0xAAAAAA
                )
                // 结果并成一列：发放的写 +N RP（绿），作废的写状态（灰）—— 一眼分得开
                context.drawTextWithShadow(
                    textRenderer,
                    Text.literal(
                        if (granted) "+${order.points} RP"
                        else Text.translatable(statusKey).string
                    ),
                    leftX + colFinishedResult, ty, if (granted) 0x55FF55 else 0xAAAAAA
                )
            } else {
                context.drawTextWithShadow(
                    textRenderer, Text.translatable(statusKey), leftX + colStatus, ty,
                    if (statusOf(order) == PointOrderStatus.PENDING_CONFIRM) 0xFFAA00 else 0xAAAAAA
                )
                context.drawTextWithShadow(
                    textRenderer, Text.literal(orderTimeText(order.createdAt)), leftX + colTime, ty, 0xAAAAAA
                )
            }
        }

        if (orders.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(
                    if (loading) "cobblemarket.points.admin.loading" else "cobblemarket.points.admin.empty"
                ),
                centerX, listStartY + 20, 0xAAAAAA
            )
        }
    }

    private fun renderDialog(context: DrawContext, order: PointOrderInfo) {
        val dx = width / 2 - 160
        val dy = height / 2 - 60
        val centerX = width / 2
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.admin.confirm_title").formatted(Formatting.GOLD),
            centerX, dy + 12, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer, Text.translatable("cobblemarket.points.admin.d_player", order.playerName),
            dx + 16, dy + 38, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer, Text.translatable("cobblemarket.points.admin.d_tier", order.tierName),
            dx + 16, dy + 52, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.admin.d_order", PointOrderNumbers.format(order.orderNo)),
            dx + 16, dy + 66, 0xFFFFFF
        )
        // 下单时间：列表到这条弹窗之间隔了好几列，处理前得能在这儿确认「这是哪天的单」
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.admin.d_time", orderTimeText(order.createdAt)),
            dx + 16, dy + 80, 0xFFFFFF
        )
        // 发放的是 RP 数、作废的是这笔钱，两个动作都在这个弹窗里 —— 把两件事都写清楚，别让服主点错
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable(
                "cobblemarket.points.admin.d_grant", texOf(order), order.points.toString()
            ).formatted(Formatting.GOLD),
            dx + 16, dy + 94, 0xFFFFFF
        )
    }
}
