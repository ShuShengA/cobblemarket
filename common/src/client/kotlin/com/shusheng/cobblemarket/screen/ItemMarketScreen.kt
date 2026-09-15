package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.client.requestCreditInfo

import com.shusheng.cobblemarket.network.BuyItemPayload
import com.shusheng.cobblemarket.network.CancelItemPayload
import com.shusheng.cobblemarket.network.CreditInfoPayload
import com.shusheng.cobblemarket.network.CollectBalancePayload
import com.shusheng.cobblemarket.network.RequestBalancePayload
import com.shusheng.cobblemarket.network.ItemEntry
import com.shusheng.cobblemarket.network.ItemMarketDataPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.RequestItemMarketPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.util.UUID
import com.shusheng.cobblemarket.client.drawItemWithBar

class ItemMarketScreen : Screen(Text.translatable("cobblemarket.item.title")) {

    private val panelWidth = 296
    private val slotSize = 36
    private val gap = 4

    private var entries = listOf<ItemEntry>()
    private var currentPage = 1
    private var totalPages = 1
    // 搜索防抖（照精灵市场）：250ms 静默后统一发请求，避免连续输入被服务端节流丢包
    private var searchDirty = false
    private var lastSearchEdit = 0L
    private var pendingBalance = 0L
    /** 待领取物品数量：>0 时待领取按钮右上角画红点（随市场数据响应刷新） */
    private var pendingReturns = 0

    private var searchField: TextFieldWidget? = null
    private var sortMode = "NEWEST"
    private var showMineOnly = false
    private var collectButton: NineSliceButton? = null
    private var returnsButton: NineSliceButton? = null
    private var backButton: NineSliceButton? = null
    private var sellAddButton: NineSliceButton? = null
    private var sortButton: NineSliceButton? = null
    private var mineButton: NineSliceButton? = null
    private var prevButton: NineSliceButton? = null
    private var nextButton: NineSliceButton? = null

    private var hoveredSlot = -1
    // 物品栈解析缓存：itemNbt → ItemStack 反序列化只在收到市场数据时做一次，
    // render 每帧直接取用（此前每帧解析 56 个 NBT 树是物品市场掉帧主因）
    private val entryStacks = mutableMapOf<UUID, ItemStack>()
    // tooltip 文本行缓存：内容只取决于条目，悬停同一格时每帧重建文本行是悬停掉帧主因
    private var tooltipCacheKey: UUID? = null
    private var tooltipCacheLines: List<Pair<Text, Int>> = emptyList()
    /** Shift 展开词条缓存（按住 Shift 才按需构建，防每帧 getTooltip ADVANCED 解析 NBT 掉帧） */
    private var tooltipAdvancedLines: List<Pair<Text, Int>>? = null
    private var tooltipAdvancedType: TooltipType? = null

    private var selectedEntry: ItemEntry? = null
    private var buyCountField: TextFieldWidget? = null
    // 购买数量校验失败提示（按钮上方红字，2 秒后消失；重新输入时清除）
    private var buyErrorText: net.minecraft.text.Text? = null
    private var buyErrorUntil = 0L
    private var cancelEntry: ItemEntry? = null

    // ── 喵喵支付（消费贷，批次 4）：购买弹窗内入口按钮，点击进独立 MeowthPayScreen ──
    private var buyPayButton: NineSliceButton? = null
    /** 消费贷开关：初始读全局缓存（入口界面已拉取）避免首次打开弹窗高度闪烁；关时弹窗 170 无喵喵支付按钮 */
    private var payAvailable = com.shusheng.cobblemarket.client.FinanceCache.consumerLoanEnabled

    private fun columns() = (panelWidth + gap) / (slotSize + gap)
    private fun getGridStartY() = 68
    // 底部预留 72px（照精灵市场：按钮 20 + 底部分割线 4 + 面板底边框 16 + 间距），
    // 保证窗口高度为任意值时翻页按钮都不会遮住面板底部边框
    private fun rows() = maxOf(0, (height - getGridStartY() - 72) / (slotSize + gap))

    private fun sortDisplay(): String = when (sortMode) {
        "PRICE_ASC" -> "cobblemarket.sort.price_asc"
        "PRICE_DESC" -> "cobblemarket.sort.price_desc"
        "NEWEST" -> "cobblemarket.sort.newest"
        else -> "cobblemarket.sort.price_asc"
    }

    override fun init() {
        super.init()
        sendToServer(RequestBalancePayload())
        val leftX = width / 2 - panelWidth / 2

        // 收款 / 返回
        collectButton = NineSliceButton(
            leftX, 13, 50, 16,
            Text.translatable("cobblemarket.gui.collect"), { collectBalance() }
        )
        addDrawableChild(collectButton)

        // Expired returns button
        returnsButton = NineSliceButton(
            leftX + 52, 13, 50, 16,
            Text.translatable("cobblemarket.gui.returns"), { client?.setScreen(ItemReturnScreen()) }
        )
        addDrawableChild(returnsButton)
        backButton = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        // 搜索框
        val savedSearch = searchField?.text ?: ""
        searchField = TextFieldWidget(textRenderer, leftX + 2, 44, 132, 16, Text.translatable("cobblemarket.item.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.item.search").formatted(Formatting.GRAY))
        // 服务端搜索：文字变化只标记 dirty，tick 防抖后回到第一页重新拉取（与精灵市场一致）
        searchField?.setChangedListener { _ ->
            searchDirty = true
            lastSearchEdit = System.currentTimeMillis()
        }
        addSelectableChild(searchField)
        addDrawableChild(searchField)
        searchField?.text = savedSearch

        // 上架按钮（choose 图标）
        sellAddButton = NineSliceButton(
            leftX + 136, 44, 18, 16,
            Text.literal(""), { openItemSellScreen() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/choose.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        )
        addDrawableChild(sellAddButton)

        // 排序按钮
        sortButton = NineSliceButton(
            leftX + panelWidth - 88, 44, 86, 16,
            Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay())),
            { cycleSort() }
        )
        addDrawableChild(sortButton)

        // 我的（personal/personal_click 双图标：关 = 全部，开 = 仅我的；悬停词条随状态）
        mineButton = NineSliceButton(
            leftX + 156, 44, 50, 16,
            Text.literal(""),
            { toggleMineOnly() },
            iconLeft = Identifier.of("cobblemarket",
                if (showMineOnly) "textures/gui/personal_click.png" else "textures/gui/personal.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine")
        )
        addDrawableChild(mineButton)

        // 分页（照精灵市场：底部分割线在网格最后一行下方 4px 对称，按钮在其与背景底边之间居中偏上 5px）
        val gridBottom = getGridStartY() + rows() * (slotSize + gap)
        val btnY = (gridBottom + 5 + (height - 32)) / 2 - 10 - 5
        prevButton = NineSliceButton(
            leftX, btnY, 80, 20,
            Text.literal(""),
            { prevPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/previous.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.prev")
        )
        addDrawableChild(prevButton)
        nextButton = NineSliceButton(
            leftX + panelWidth - 80, btnY, 80, 20,
            Text.literal(""),
            { nextPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/next.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.next")
        )
        addDrawableChild(nextButton)
        updatePageButtons()

        requestFilterRefresh()
    }

    private fun openItemSellScreen() {
        client?.setScreen(ItemSellScreen())
    }

    private fun refreshData(resetPending: Boolean = true) {
        lastListRequestAt = System.currentTimeMillis()
        if (resetPending) pendingPage = 0 // 非翻页路径（搜索/排序/购买后刷新）取消未发出的翻页目标
        val query = searchField?.text?.trim() ?: ""
        sendToServer(
            RequestItemMarketPayload(
                sortMode, currentPage, showMineOnly, columns() * rows(),
                query,
                com.shusheng.cobblemarket.client.ItemSearchIndex.itemIdsMatchingStrict(query),
                com.shusheng.cobblemarket.client.ItemSearchIndex.tmMovesMatching(query).toList(),
                com.shusheng.cobblemarket.client.ItemSearchIndex.enchantsMatching(query).toList()
            )
        )
    }

    // 防抖计时器（照精灵市场）：250ms 静默后统一发请求
    override fun tick() {
        // 兜底：翻页请求 1s 未收到响应（如服务端节流静默丢弃）强制复位 inFlight，防止按钮永久锁灰
        if (pageRequestInFlight && System.currentTimeMillis() - lastListRequestAt > 1000) {
            pageRequestInFlight = false
        }
        // 目标页码补发：窗口允许且无在途时发出请求（连点合并到最终目标页；
        // 目标保留到响应确认，请求被丢弃时兜底复位后自动重试）
        if (!pageRequestInFlight && pendingPage != 0 && System.currentTimeMillis() - lastListRequestAt >= PAGE_CLICK_INTERVAL_MS) {
            currentPage = pendingPage.coerceIn(1, maxOf(1, totalPages))
            pageRequestInFlight = true
            updatePageButtons()
            refreshData(resetPending = false)
        }
        // 筛选变更补发：与翻页共用 inFlight/窗口
        if (!pageRequestInFlight && pendingFilterRefresh && System.currentTimeMillis() - lastListRequestAt >= PAGE_CLICK_INTERVAL_MS) {
            pendingFilterRefresh = false
            pageRequestInFlight = true
            refreshData(resetPending = false)
        }
        if (searchDirty && System.currentTimeMillis() - lastSearchEdit >= 250) {
            searchDirty = false
            currentPage = 1
            requestFilterRefresh()
        }
    }

    private fun collectBalance() {
        sendToServer(CollectBalancePayload())
    }

    fun onItemMarketData(payload: ItemMarketDataPayload) {
        entries = payload.entries
        totalPages = payload.totalPages
        when {
            pendingPage == 0 -> currentPage = payload.currentPage
            // 目标页已到达（或被服务端 clamp 出界）：确认，清目标
            payload.currentPage == pendingPage || pendingPage > payload.totalPages -> {
                pendingPage = 0
                currentPage = payload.currentPage
            }
            // 目标未达（罕见）：保留目标由 tick 继续重试，页码维持乐观值
            else -> {}
        }
        pendingBalance = payload.pendingBalance
        pendingReturns = payload.pendingReturns
        pageRequestInFlight = false
        updatePageButtons()
        rebuildEntryStacks()
    }

    /** 一次性解析本页全部物品栈（render 每帧只读缓存，见 entryStacks 注释） */
    private fun rebuildEntryStacks() {
        entryStacks.clear()
        val registry = client?.world?.registryManager ?: return
        entries.forEach { entry ->
            entryStacks[entry.id] = ItemStack.fromNbtOrEmpty(registry, entry.itemNbt)
        }
    }


    private fun cycleSort() {
        sortMode = when (sortMode) {
            "PRICE_ASC" -> "PRICE_DESC"
            "PRICE_DESC" -> "NEWEST"
            else -> "PRICE_ASC"
        }
        sortButton?.message = Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay()))
        currentPage = 1
        requestFilterRefresh()
    }

    private fun toggleMineOnly() {
        showMineOnly = !showMineOnly
        mineButton?.iconLeft = Identifier.of("cobblemarket",
            if (showMineOnly) "textures/gui/personal_click.png" else "textures/gui/personal.png")
        mineButton?.setTooltip(Tooltip.of(Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine")))
        currentPage = 1
        requestFilterRefresh()
    }

    // 翻页请求在途标志：响应到达前不重复发请求
    private var pageRequestInFlight = false

    // 最近一次列表请求发送时间：补发节奏对齐服务端节流窗口（request_item_market 500ms）
    // 并留 100ms 余量——窗口同宽时网络抖动会让请求恰好落入服务端窗口被静默丢弃
    private var lastListRequestAt = 0L

    // 待翻页目标：点击立即更新页码并把目标页记到这里，tick 在窗口允许时补发请求。
    // 连点合并到最终目标页（中间页不发）；补发后保留目标，响应确认到达才清——
    // 请求若被静默丢弃，1s 兜底复位后 tick 自动重试
    private var pendingPage = 0

    // 筛选变更待发标志：筛选按钮连点/搜索防抖后若仍在服务端节流窗口内，记标志由 tick 补发
    private var pendingFilterRefresh = false

    /** 筛选/搜索等「当前筛选态」请求统一入口：窗口允许立即发，否则排队 tick 补发 */
    private fun requestFilterRefresh() {
        if (!pageRequestInFlight && System.currentTimeMillis() - lastListRequestAt >= PAGE_CLICK_INTERVAL_MS) {
            refreshData()
        } else {
            pendingFilterRefresh = true
        }
    }

    private fun updatePageButtons() {
        prevButton?.active = currentPage > 1
        nextButton?.active = currentPage < totalPages
    }

    private fun prevPage() {
        if (currentPage <= 1) return
        currentPage--
        pendingPage = currentPage
        updatePageButtons()
    }

    private fun nextPage() {
        if (currentPage >= totalPages) return
        currentPage++
        pendingPage = currentPage
        updatePageButtons()
    }

    private companion object {
        /** 补发请求最小间隔：服务端 request_item_market 节流 500ms + 100ms 余量，防网络抖动边界丢弃 */
        const val PAGE_CLICK_INTERVAL_MS = 600L
        /** 收款按钮右上角金币角标（48×48 源图按 1/6 缩到 8×8） */
        val COIN_ICON = Identifier.of("cobblemarket", "textures/gui/coin_icon.png")
        /** 待领取按钮右上角红点（48×48 源图按 1/6 缩到 8×8） */
        val RED_DOT = Identifier.of("cobblemarket", "textures/gui/red_dot.png")
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

        if (selectedEntry != null || cancelEntry != null) {
            return
        }

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        // 收款按钮右上角金币角标（8×8，仅 2px 露在角外，红点通知样式）：有待收款时显示
        // （弹窗打开时此段之前已 return，角标随下层一起隐藏）
        if (pendingBalance > 0) {
            context.matrices.push()
            context.matrices.translate((leftX + 50 - 6).toDouble(), (13 - 2).toDouble(), 0.0)
            context.matrices.scale(1f / 6f, 1f / 6f, 1f)
            context.drawTexture(COIN_ICON, 0, 0, 0f, 0f, 48, 48, 48, 48)
            context.matrices.pop()
        }
        // 待领取按钮右上角红点（与收款角标同位置样式）：有待领取物品时显示
        if (pendingReturns > 0) {
            context.matrices.push()
            context.matrices.translate((leftX + 102 - 6).toDouble(), (13 - 2).toDouble(), 0.0)
            context.matrices.scale(1f / 6f, 1f / 6f, 1f)
            context.drawTexture(RED_DOT, 0, 0, 0f, 0f, 48, 48, 48, 48)
            context.matrices.pop()
        }

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.title").formatted(Formatting.GOLD),
            centerX, 14, 0xFFFFFF)

        // 余额 + 待收款：文字标签默认色，金额蓝/绿（2026-08-24 拍板）；页码右对齐，长数字互不干扰
        val balText: Text? = com.shusheng.cobblemarket.client.BalanceCache.balance.takeIf { it.isNotEmpty() }?.let {
            Text.translatable("cobblemarket.gui.balance",
                Text.literal(it + " " + com.shusheng.cobblemarket.client.inlineCurrencyUnit()).formatted(Formatting.GOLD))
        }
        val balW = balText?.let { textRenderer.getWidth(it) + 4 } ?: 0
        if (balText != null) context.drawTextWithShadow(textRenderer, balText, leftX, 31, 0xFFFFFF)
        context.drawTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.gui.pending_balance",
                Text.literal(com.shusheng.cobblemarket.client.formatBalanceLong(pendingBalance) + " " + com.shusheng.cobblemarket.client.inlineCurrencyUnit()).formatted(Formatting.GREEN)),
            leftX + balW, 31, 0xFFFFFF)

        val pageText = Text.translatable("cobblemarket.gui.page", currentPage, totalPages).formatted(Formatting.GRAY)
        context.drawTextWithShadow(textRenderer,
            pageText,
            leftX + panelWidth - 4 - textRenderer.getWidth(pageText), 32, 0xFFFFFF)

        if (entries.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.item.empty").formatted(Formatting.GRAY),
                centerX, getGridStartY() + 40, 0xFFFFFF)
        }

        val dividerY = getGridStartY() - 4
        context.fill(leftX, dividerY, leftX + panelWidth, dividerY + 1, 0xFF555555.toInt())
        // 底部分割线：最多显示行数的网格最后一行下方 4px（与顶部对称），分页按钮在其与背景底边之间居中
        val gridBottom = getGridStartY() + rows() * (slotSize + gap)
        context.fill(leftX, gridBottom + 4, leftX + panelWidth, gridBottom + 5, 0xFF555555.toInt())

        val cols = columns()
        val rows = rows()
        val displayEntries = entries
        val gridOffsetX = (panelWidth - (cols * slotSize + (cols - 1) * gap)) / 2

        hoveredSlot = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY >= getGridStartY()) {
            val col = (mouseX - leftX - gridOffsetX) / (slotSize + gap)
            val row = (mouseY - getGridStartY()) / (slotSize + gap)
            val idx = row * cols + col
            if (col in 0 until cols && row in 0 until rows && idx in displayEntries.indices) hoveredSlot = idx
        }

        displayEntries.take(cols * rows).forEachIndexed { index, entry ->
            val col = index % cols
            val row = index / cols
            val x = leftX + gridOffsetX + col * (slotSize + gap)
            val y = getGridStartY() + row * (slotSize + gap)

            val rowState = if (index == hoveredSlot) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, x, y, slotSize, slotSize, rowState, ROW_BACKGROUND_TEX_H)

            // 弹窗打开时本函数已在开头 return，此处无需图标条件（不会在弹窗下渲染）
            entryStacks[entry.id]?.let { stack ->
                drawItemWithBar(context, stack, x + (slotSize - 16) / 2, y + (slotSize - 16) / 2)
            }

            val countText = "×${entry.count}"
            context.drawText(textRenderer, countText, x + slotSize - 2 - textRenderer.getWidth(countText), y + 2, 0xFFFFFF, false)

            val priceText = "${com.shusheng.cobblemarket.client.formatPriceShort(entry.price)} ${com.shusheng.cobblemarket.client.inlineCurrencyUnit()}"
            context.drawText(textRenderer, priceText, x + 3, y + slotSize - 10, 0xFFAA00, false)
        }

        if (hoveredSlot in displayEntries.indices) {
            renderItemTooltip(context, displayEntries[hoveredSlot], mouseX, mouseY)
        }
    }

    private fun renderItemTooltip(context: DrawContext, entry: ItemEntry, mouseX: Int, mouseY: Int) {
        // 文本行缓存：悬停同一格时内容不变，只在悬停目标变化时重建（Shift/Ctrl 切换不影响缓存键）
        if (tooltipCacheKey != entry.id) {
            tooltipCacheKey = entry.id
            tooltipCacheLines = buildTooltipLines(entry, TooltipType.BASIC)
            tooltipAdvancedLines = null
            tooltipAdvancedType = null
        }
        // 按住 Shift 展开完整词条 / Ctrl（F3+H 开启）展开调试信息（照原版背包悬停；类型变化重建缓存）
        val advanced = if (com.shusheng.cobblemarket.client.ItemComponentsDisplay.hoverExpanded()) {
            val type = com.shusheng.cobblemarket.client.ItemComponentsDisplay.tooltipTypeForHover()
            if (tooltipAdvancedType != type) {
                tooltipAdvancedType = type
                tooltipAdvancedLines = buildTooltipLines(entry, type)
            }
            tooltipAdvancedLines
        } else {
            tooltipAdvancedType = null
            null
        }
        val lines = advanced ?: tooltipCacheLines

        var maxWidth = 0
        lines.forEach { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it.first)) }

        val padding = 4
        val tx = minOf(mouseX + 12, width - maxWidth - 12)
        val tooltipHeight = lines.size * 10 + padding
        val tyAbove = mouseY - tooltipHeight - 4
        val ty = if (tyAbove <= 0) minOf(mouseY + 12, height - tooltipHeight) else tyAbove

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, lines.size * 10 + 2 * padding, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, (line, color) ->
            context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
        }
        context.matrices.pop()
    }

    private fun isInputFieldFocused() = focused?.let { f -> f === searchField || f === buyCountField } ?: false

    /** 悬停词条构建（BASIC 常驻 / ADVANCED 按 Shift 构建）：物品词条 + 卖家 + 价格 + 数量 */
    private fun buildTooltipLines(entry: ItemEntry, type: TooltipType): List<Pair<Text, Int>> {
        val stack = entryStacks[entry.id]
        val built = mutableListOf<Pair<Text, Int>>()
        if (stack != null) {
            built.addAll(com.shusheng.cobblemarket.client.ItemComponentsDisplay.itemTooltip(stack, client?.player, type).map { it to 0xFFFFFF })
        } else {
            built.add(Text.literal(entry.itemId) to 0xFFFFFF)
        }
        built.add(Text.translatable("cobblemarket.gui.tooltip_seller").append(entry.sellerName) to 0xFFFFFF)
        // 价格行：标签默认色，金额段蓝色（2026-08-24 拍板）
        built.add(Text.translatable("cobblemarket.item.tooltip_price").append(
            Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ${com.shusheng.cobblemarket.client.displayCurrency(entry.currencyName)}").formatted(Formatting.GOLD)
        ) to 0xFFFFFF)
        built.add(Text.literal("×${entry.count}") to 0xFFFFFF)
        return built
    }

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true ||
        buyCountField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (cancelEntry != null) {
            handleCancelDialogClick(mouseX.toInt(), mouseY.toInt())
            return true
        }
        if (selectedEntry != null) {
            val wasInInput = isInputFieldFocused()
            val result = super.mouseClicked(mouseX, mouseY, button)
            if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
                focused = null
            }
            return true
        }
        val displayEntries = entries
        if (hoveredSlot in displayEntries.indices) {
            val entry = displayEntries[hoveredSlot]
            val playerUuid = client?.player?.uuid
            if (entry.sellerUuid == playerUuid) {
                openCancelDialog(entry)
            } else {
                openBuyDialog(entry)
            }
            return true
        }
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        return result
    }

    /** 弹窗打开时隐藏主界面全部控件：widget 文字画在遮罩（addDrawable）之上，visible=false 才能防穿透与误点 */
    private fun setMainControlsVisible(visible: Boolean) {
        collectButton?.visible = visible
        returnsButton?.visible = visible
        backButton?.visible = visible
        searchField?.visible = visible
        sellAddButton?.visible = visible
        sortButton?.visible = visible
        mineButton?.visible = visible
        prevButton?.visible = visible
        nextButton?.visible = visible
    }

    private fun openBuyDialog(entry: ItemEntry) {
        selectedEntry = entry
        setMainControlsVisible(false)
        val centerX = width / 2
        // 弹窗高度随消费贷开关收缩（210/170），dialogY 与 renderBuyDialogBackground 同公式
        val dialogY = height / 2 - buyDialogH() / 2

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderBuyDialogBackground(context)
            }
        })

        buyCountField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 72, 100, 16, Text.literal(""))
        buyCountField?.setPlaceholder(Text.translatable("cobblemarket.item.buy_count"))
        buyCountField?.setTextPredicate { it.length <= 4 && it.all { c -> c.isDigit() } }
        // 重新输入时清除校验失败提示
        buyCountField?.setChangedListener { buyErrorText = null }
        addDrawableChild(buyCountField)

        // 按钮行 y 随弹窗高度收缩：210 高 → y+140；170 高 → y+128
        val btnRowY = dialogY + (if (payAvailable) 140 else 128)
        addDrawableChild(NineSliceButton(
            centerX - 85, btnRowY, 80, 20,
            Text.translatable("cobblemarket.gui.buy"),
            { confirmBuy() }
        ))
        addDrawableChild(NineSliceButton(
            centerX + 5, btnRowY, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeBuyDialog() }
        ))

        // 喵喵支付入口（点击进独立 MeowthPayScreen 选方案）；消费贷关时不显示
        if (payAvailable) {
            buyPayButton = NineSliceButton(
                centerX - 85, dialogY + 164, 80, 20,
                Text.translatable("cobblemarket.buy_confirm.meowth_pay"),
                { openMeowthPay() }
            )
            addDrawableChild(buyPayButton)
        }

        // 拉消费贷开关（喵喵支付按钮显示与弹窗高度依据）
        requestCreditInfo()
    }

    /** 购买弹窗高度：消费贷开 210（含喵喵支付行）/ 关 170（原尺寸） */
    private fun buyDialogH(): Int = if (payAvailable) 210 else 170

    /** 消费贷开关快照（打开购买弹窗时拉取）：状态变化时重建弹窗控件（含输入恢复）+ 回写缓存 */
    fun onCreditInfo(payload: CreditInfoPayload) {
        val available = payload.consumerLoanEnabled
        com.shusheng.cobblemarket.client.FinanceCache.consumerLoanEnabled = available
        if (available == payAvailable) return
        payAvailable = available
        val entry = selectedEntry ?: return
        val countText = buyCountField?.text ?: ""
        closeBuyDialog()
        openBuyDialog(entry)
        buyCountField?.text = countText
    }

    private fun closeBuyDialog() {
        selectedEntry = null
        buyCountField = null
        buyPayButton = null
        clearChildren()
        init()
    }

    /** 打开喵喵支付独立界面（数量校验同现金购买；确认回调发喵喵支付物品购买请求） */
    private fun openMeowthPay() {
        val entry = selectedEntry ?: return
        val count = buyCountField?.text?.toIntOrNull() ?: run {
            playFailSound()
            return
        }
        if (count <= 0 || count > entry.count) {
            buyErrorText = Text.translatable("cobblemarket.item.buy_too_many").formatted(Formatting.RED)
            buyErrorUntil = System.currentTimeMillis() + 2000
            playFailSound()
            return
        }
        val itemName = Identifier.tryParse(entry.itemId)?.let { Registries.ITEM.get(it).name }
            ?: Text.literal(entry.itemId)
        client?.setScreen(MeowthPayScreen(
            itemDesc = Text.literal("$count×").append(itemName),
            amount = entry.price.toLong() * count,
            onConfirm = { plan ->
                sendToServer(BuyItemPayload(entry.id, count, plan))
                client?.setScreen(ItemMarketScreen())
            },
            onBack = { client?.setScreen(ItemMarketScreen()) }
        ))
    }

    private fun openCancelDialog(entry: ItemEntry) {
        cancelEntry = entry
        setMainControlsVisible(false)
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderCancelDialogBackground(context, mouseX, mouseY)
            }
        })
    }

    private fun closeCancelDialog() {
        cancelEntry = null
        clearChildren()
        init()
    }

    private fun handleCancelDialogClick(mx: Int, my: Int) {
        val centerX = width / 2
        val dialogH = 170
        val dialogY = height / 2 - dialogH / 2
        val btnW = 80
        val btnH = 20
        val btnY = dialogY + 128
        val confirmX = centerX - 85
        val cancelX = centerX + 5
        if (mx in confirmX..(confirmX + btnW) && my in btnY..(btnY + btnH)) {
            playClickSound()
            confirmCancel()
        } else if (mx in cancelX..(cancelX + btnW) && my in btnY..(btnY + btnH)) {
            playClickSound()
            closeCancelDialog()
        }
    }

    private fun playClickSound() {
        client?.soundManager?.play(PositionedSoundInstance.master(
            SoundEvent.of(Identifier.of("cobblemarket", "button_click")),
            1.0f
        ))
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val cancel = cancelEntry
        val buy = selectedEntry
        val buyCount = buyCountField?.text ?: ""
        super.resize(client, width, height)
        if (cancel != null) {
            cancelEntry = null
            openCancelDialog(cancel)
        } else if (buy != null) {
            selectedEntry = null
            openBuyDialog(buy)
            buyCountField?.text = buyCount
        }
    }

    private fun confirmCancel() {
        val entry = cancelEntry ?: return
        sendToServer(CancelItemPayload(entry.id))
        closeCancelDialog()
    }

    private fun renderCancelDialogBackground(context: DrawContext, mouseX: Int, mouseY: Int) {
        val entry = cancelEntry ?: return
        val centerX = width / 2
        val dialogW = 220
        val dialogH = 170
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 100.0)
        drawScreenDimMask(context, width, height)
        context.matrices.pop()

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 200.0)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.cancel_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        val registry = client?.world?.registryManager
        if (registry != null) {
            val stack = ItemStack.fromNbtOrEmpty(registry, entry.itemNbt)
            drawItemWithBar(context, stack, centerX - 8, dialogY + 26)
            // 物品名照物品栏悬浮第一行按稀有度着色
            context.drawCenteredTextWithShadow(textRenderer,
                com.shusheng.cobblemarket.util.TextUtil.rarityColoredName(stack), centerX, dialogY + 46, 0xFFFFFF)
        }

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.cancel_hint", entry.count).formatted(Formatting.GRAY),
            centerX, dialogY + 66, 0xFFFFFF)

        val btnW = 80
        val btnH = 20
        val btnY = dialogY + 128
        val confirmX = centerX - 85
        val cancelX = centerX + 5
        val confirmHover = mouseX in confirmX..(confirmX + btnW) && mouseY in btnY..(btnY + btnH)
        val cancelHover = mouseX in cancelX..(cancelX + btnW) && mouseY in btnY..(btnY + btnH)
        drawNineSlice(context, BUTTON_TEXTURE, confirmX, btnY, btnW, btnH, if (confirmHover) 1 else 0, BUTTON_TEX_H)
        drawNineSlice(context, BUTTON_TEXTURE, cancelX, btnY, btnW, btnH, if (cancelHover) 1 else 0, BUTTON_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("cobblemarket.item.cancel_confirm"), confirmX + btnW / 2, btnY + (btnH - 8) / 2, 0xFFFFFF)
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("cobblemarket.buy_confirm.cancel"), cancelX + btnW / 2, btnY + (btnH - 8) / 2, 0xFFFFFF)
        context.matrices.pop()
    }

    private fun confirmBuy() {
        val entry = selectedEntry ?: return
        val count = buyCountField?.text?.toIntOrNull() ?: run {
            playFailSound()
            return
        }
        if (count <= 0 || count > entry.count) {
            // 无效数量：红字提示 2 秒 + fail 音效（原先静默返回，玩家无感知）
            buyErrorText = Text.translatable("cobblemarket.item.buy_too_many").formatted(Formatting.RED)
            buyErrorUntil = System.currentTimeMillis() + 2000
            MinecraftClient.getInstance().soundManager.play(
                PositionedSoundInstance.master(
                    SoundEvent.of(Identifier.of("cobblemarket", "fail")),
                    1.0f
                )
            )
            return
        }
        sendToServer(BuyItemPayload(entry.id, count, -1))
        closeBuyDialog()
    }

    /** 总价 Long 计算：单价 9 位 × 数量 4 位可超 Int 上限，Int 相乘会溢出显示负数 */
    private fun buyTotal(): Long {
        val entry = selectedEntry ?: return 0
        val count = buyCountField?.text?.toIntOrNull() ?: 0
        return entry.price.toLong() * count
    }

    private fun renderBuyDialogBackground(context: DrawContext) {
        val entry = selectedEntry ?: return
        val centerX = width / 2
        val dialogW = 220
        // 弹窗高度随消费贷开关收缩：210（含喵喵支付行）/ 170（原尺寸）
        val dialogH = buyDialogH()
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.buy_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        // 购买数量校验失败提示（按钮上方红字，2 秒后消失；y 随弹窗高度收缩）
        if (buyErrorText != null && System.currentTimeMillis() < buyErrorUntil) {
            context.drawCenteredTextWithShadow(textRenderer, buyErrorText, centerX, dialogY + (if (payAvailable) 128 else 108), 0xFFFFFF)
        }

        val registry = client?.world?.registryManager
        if (registry != null) {
            val stack = ItemStack.fromNbtOrEmpty(registry, entry.itemNbt)
            drawItemWithBar(context, stack, centerX - 8, dialogY + 26)
            // 物品名照物品栏悬浮第一行按稀有度着色
            context.drawCenteredTextWithShadow(textRenderer,
                com.shusheng.cobblemarket.util.TextUtil.rarityColoredName(stack), centerX, dialogY + 46, 0xFFFFFF)
        }

        context.drawTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.buy_count").string + "（" + Text.translatable("cobblemarket.item.sell_max").string + " ${entry.count}）",
            centerX - 80, dialogY + 62, 0xAAAAAA)
        context.drawTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.buy_total").append(": ").append(
                Text.literal(com.shusheng.cobblemarket.client.formatPriceLong(buyTotal()) + " " + com.shusheng.cobblemarket.client.displayCurrency(entry.currencyName)).formatted(Formatting.GOLD)),
            centerX - 80, dialogY + 92, 0xFFFFFF)
    }

    fun onMarketResult(payload: MarketResultPayload) {
        if (payload.success) {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.GREEN), false)
            requestFilterRefresh()
        } else {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.RED), false)
        }
    }

    override fun shouldPause() = false
}
