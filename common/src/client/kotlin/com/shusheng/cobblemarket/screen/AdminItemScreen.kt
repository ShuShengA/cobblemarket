package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.network.AdminCancelItemPayload
import com.shusheng.cobblemarket.network.AdminRequestItemPayload
import com.shusheng.cobblemarket.network.ItemEntry
import com.shusheng.cobblemarket.network.ItemMarketDataPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
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
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.util.UUID
import com.shusheng.cobblemarket.client.drawItemWithBar

class AdminItemScreen : Screen(Text.translatable("cobblemarket.op.item")) {

    private val panelWidth = 296
    private val slotSize = 36
    private val gap = 4

    private var entries = listOf<ItemEntry>()
    private var currentPage = 1
    private var totalPages = 1
    // 搜索防抖（照精灵市场）：250ms 静默后统一发请求，避免连续输入被服务端节流丢包
    private var searchDirty = false
    private var lastSearchEdit = 0L

    private var searchField: TextFieldWidget? = null
    private var sellerField: TextFieldWidget? = null
    private var sortMode = "NEWEST"
    private var showMineOnly = false

    private var backButton: NineSliceButton? = null
    private var sortButton: NineSliceButton? = null
    private var mineButton: NineSliceButton? = null
    private var prevButton: NineSliceButton? = null
    private var nextButton: NineSliceButton? = null

    private var hoveredSlot = -1
    private var cancelEntry: ItemEntry? = null

    // 物品 NBT 解析缓存：数据到达时一次性反序列化，避免 render 每帧 fromNbtOrEmpty 造成 GC 压力
    private val entryStacks = mutableMapOf<UUID, ItemStack>()
    private val tooltipStackLines = mutableMapOf<UUID, List<Pair<Text, Int>>>()
    /** Shift 展开的高级词条（列表加载时与 BASIC 双份构建，渲染按 Shift 状态切换） */
    // 键 (entryId, TooltipType)：Shift/Ctrl 展开类型不同分别缓存
    private val tooltipAdvancedLines = mutableMapOf<Pair<UUID, TooltipType>, List<Pair<Text, Int>>>()

    private fun columns() = (panelWidth + gap) / (slotSize + gap)
    // 84→88：卖家搜索框底边(80)与顶部分割线(80)贴死，网格起始下移 4px 留出间隙
    private fun getGridStartY() = 88
    // 底部预留 72px（照精灵市场：按钮 20 + 底部分割线 4 + 面板底边框 16 + 间距），
    // 保证窗口高度为任意值时翻页按钮都不会遮住面板底部边框
    private fun rows() = maxOf(0, (height - getGridStartY() - 72) / (slotSize + gap))

    // 翻页请求在途标志：响应到达前禁用翻页按钮，防止快速连点
    private var pageRequestInFlight = false

    // 最近一次列表请求发送时间：仅用于 tick 兜底超时复位（管理端请求服务端无节流）
    private var lastListRequestAt = 0L

    private fun updatePageButtons() {
        prevButton?.active = !pageRequestInFlight && currentPage > 1
        nextButton?.active = !pageRequestInFlight && currentPage < totalPages
    }

    private fun prevPage() {
        if (pageRequestInFlight || currentPage <= 1) return
        pageRequestInFlight = true
        updatePageButtons()
        currentPage--
        refreshData()
    }

    private fun nextPage() {
        if (pageRequestInFlight || currentPage >= totalPages) return
        pageRequestInFlight = true
        updatePageButtons()
        currentPage++
        refreshData()
    }


    private fun sortDisplay(): String = when (sortMode) {
        "PRICE_ASC" -> "cobblemarket.sort.price_asc"
        "PRICE_DESC" -> "cobblemarket.sort.price_desc"
        "NEWEST" -> "cobblemarket.sort.newest"
        else -> "cobblemarket.sort.price_asc"
    }

    private fun cycleSort() {
        sortMode = when (sortMode) {
            "PRICE_ASC" -> "PRICE_DESC"
            "PRICE_DESC" -> "NEWEST"
            else -> "PRICE_ASC"
        }
        sortButton?.message = Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay()))
        currentPage = 1
        refreshData()
    }

    private fun toggleMineOnly() {
        showMineOnly = !showMineOnly
        mineButton?.iconLeft = Identifier.of("cobblemarket",
            if (showMineOnly) "textures/gui/personal_click.png" else "textures/gui/personal.png")
        mineButton?.setTooltip(Tooltip.of(Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine")))
        currentPage = 1
        refreshData()
    }

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        backButton = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(AdminScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        val savedSearch = searchField?.text ?: ""
        val savedSeller = sellerField?.text ?: ""
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

        sellerField = TextFieldWidget(textRenderer, leftX + 2, 64, panelWidth - 4, 16, Text.translatable("cobblemarket.op.seller_search"))
        sellerField?.setPlaceholder(Text.translatable("cobblemarket.op.seller_search").formatted(Formatting.GRAY))
        sellerField?.setChangedListener { _ ->
            currentPage = 1
            refreshData()
        }
        addSelectableChild(sellerField)
        addDrawableChild(sellerField)
        sellerField?.text = savedSeller

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
        updatePageButtons()
        nextButton = NineSliceButton(
            leftX + panelWidth - 80, btnY, 80, 20,
            Text.literal(""),
            { nextPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/next.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.next")
        )
        addDrawableChild(nextButton)

        refreshData()
    }

    private fun refreshData() {
        lastListRequestAt = System.currentTimeMillis()
        val query = searchField?.text?.trim() ?: ""
        sendToServer(
            AdminRequestItemPayload(
                sellerFilter = sellerField?.text?.trim() ?: "",
                itemFilter = query,
                itemIds = com.shusheng.cobblemarket.client.ItemSearchIndex.itemIdsMatchingStrict(query),
                tmMoves = com.shusheng.cobblemarket.client.ItemSearchIndex.tmMovesMatching(query).toList(),
                enchants = com.shusheng.cobblemarket.client.ItemSearchIndex.enchantsMatching(query).toList(),
                sortMode = sortMode,
                page = currentPage,
                pageSize = columns() * rows(),
                mineOnly = showMineOnly
            )
        )
    }

    // 防抖计时器（照精灵市场）：250ms 静默后统一发请求
    override fun tick() {
        // 兜底：翻页请求 1s 未收到响应强制复位 inFlight，防止按钮永久锁灰
        if (pageRequestInFlight && System.currentTimeMillis() - lastListRequestAt > 1000) {
            pageRequestInFlight = false
            updatePageButtons()
        }
        if (searchDirty && System.currentTimeMillis() - lastSearchEdit >= 250) {
            searchDirty = false
            currentPage = 1
            refreshData()
        }
    }

    fun onItemMarketData(payload: ItemMarketDataPayload) {
        pageRequestInFlight = false
        updatePageButtons()
        entries = payload.entries
        totalPages = payload.totalPages
        currentPage = payload.currentPage
        rebuildEntryCaches(payload.entries)
    }

    private fun rebuildEntryCaches(newEntries: List<ItemEntry>) {
        entryStacks.clear()
        tooltipStackLines.clear()
        tooltipAdvancedLines.clear()
        val registry = client?.world?.registryManager ?: return
        for (entry in newEntries) {
            val stack = ItemStack.fromNbtOrEmpty(registry, entry.itemNbt)
            entryStacks[entry.id] = stack
            // 走统一入口（别直连 getTooltip）：受损物品才会多一行「耐久：X / Y」
            tooltipStackLines[entry.id] = com.shusheng.cobblemarket.client.ItemComponentsDisplay
                .itemTooltip(stack, client?.player, TooltipType.BASIC)
                .map { it to 0xFFFFFF }
            // ADVANCED 不在此构建：Fabric tooltip 信息块只在构建时 Shift 按住才生成，渲染处按需构建
        }
    }

    fun onMarketResult(payload: MarketResultPayload) {
        client?.player?.sendMessage(payload.message.copy().formatted(if (payload.success) Formatting.GREEN else Formatting.RED), false)
        if (payload.success) refreshData()
    }

    private fun openCancelDialog(entry: ItemEntry) {
        cancelEntry = entry
        // 弹窗打开时隐藏主界面全部控件：widget 文字画在遮罩（addDrawable）之上，visible=false 才能防穿透
        backButton?.visible = false
        searchField?.visible = false
        sellerField?.visible = false
        sortButton?.visible = false
        mineButton?.visible = false
        prevButton?.visible = false
        nextButton?.visible = false
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
        super.resize(client, width, height)
        if (cancel != null) {
            cancelEntry = null
            openCancelDialog(cancel)
        }
    }

    private fun confirmCancel() {
        val entry = cancelEntry ?: return
        sendToServer(AdminCancelItemPayload(entry.id))
        closeCancelDialog()
    }

    private fun renderCancelDialogBackground(context: DrawContext, mouseX: Int, mouseY: Int) {
        val entry = cancelEntry ?: return
        val centerX = width / 2
        val dialogW = minOf(300, width - 40).coerceAtLeast(200)
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

        entryStacks[entry.id]?.let { stack ->
            drawItemWithBar(context, stack, centerX - 8, dialogY + 26)
            context.drawCenteredTextWithShadow(textRenderer, stack.name, centerX, dialogY + 46, 0xFFFFFF)
        }

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.gui.tooltip_seller").append(entry.sellerName),
            centerX, dialogY + 66, 0xFFFFFF)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.sell_count").append(": ").append("×${entry.count}"),
            centerX, dialogY + 80, 0xFFFFFF)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.gui.tooltip_price").append(
                Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ${com.shusheng.cobblemarket.client.displayCurrency(entry.currencyName)}").formatted(Formatting.GOLD)
            ),
            centerX, dialogY + 94, 0xFFFFFF)

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

        if (cancelEntry != null) {
            return
        }

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.op.item").formatted(Formatting.GOLD),
            centerX, 14, 0xFFFFFF)

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.gui.page", currentPage, totalPages).formatted(Formatting.GRAY),
            centerX, 32, 0xFFFFFF)

        val dividerY = getGridStartY() - 4
        context.fill(leftX, dividerY, leftX + panelWidth, dividerY + 1, 0xFF555555.toInt())
        // 底部分割线：最多显示行数的网格最后一行下方 4px（与顶部对称），分页按钮在其与背景底边之间居中
        val gridBottom = getGridStartY() + rows() * (slotSize + gap)
        context.fill(leftX, gridBottom + 4, leftX + panelWidth, gridBottom + 5, 0xFF555555.toInt())

        val cols = columns()
        val rows = rows()
        val gridOffsetX = (panelWidth - (cols * slotSize + (cols - 1) * gap)) / 2
        val displayEntries = entries

        hoveredSlot = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY >= getGridStartY()) {
            val col = (mouseX - leftX - gridOffsetX) / (slotSize + gap)
            val row = (mouseY - getGridStartY()) / (slotSize + gap)
            val idx = row * cols + col
            if (col in 0 until cols && row in 0 until rows && idx in displayEntries.indices) hoveredSlot = idx
        }

        displayEntries.forEachIndexed { index, entry ->
            val col = index % cols
            val row = index / cols
            val x = leftX + gridOffsetX + col * (slotSize + gap)
            val y = getGridStartY() + row * (slotSize + gap)

            val rowState = if (index == hoveredSlot) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, x, y, slotSize, slotSize, rowState, ROW_BACKGROUND_TEX_H)

            entryStacks[entry.id]?.let { stack ->
                drawItemWithBar(context, stack, x + (slotSize - 16) / 2, y + (slotSize - 16) / 2)
            }

            val countText = "×${entry.count}"
            context.drawText(textRenderer, countText, x + slotSize - 2 - textRenderer.getWidth(countText), y + 2, 0xFFFFFF, false)

            // 价格过长时用缩写（与物品市场界面一致），完整价格见 tooltip
            val priceText = "${com.shusheng.cobblemarket.client.formatPriceShort(entry.price)} ${com.shusheng.cobblemarket.client.inlineCurrencyUnit()}"
            context.drawText(textRenderer, priceText, x + 3, y + slotSize - 10, 0xFFAA00, false)
        }

        if (hoveredSlot in displayEntries.indices) {
            renderItemTooltip(context, displayEntries[hoveredSlot], mouseX, mouseY)
        }

        // 每帧刷新按钮状态（render 覆写为最新 currentPage/totalPages，含 inFlight 置灰，见 updatePageButtons）
        updatePageButtons()
    }

    private fun renderItemTooltip(context: DrawContext, entry: ItemEntry, mouseX: Int, mouseY: Int) {
        val lines = mutableListOf<Pair<Text, Int>>()
        // 按住 Shift 展开完整词条 / Ctrl（F3+H 开启）展开调试信息（照原版背包悬停；按键按下时才构建，Fabric 信息块才能生成）
        val stackLines = if (com.shusheng.cobblemarket.client.ItemComponentsDisplay.hoverExpanded()) {
            val type = com.shusheng.cobblemarket.client.ItemComponentsDisplay.tooltipTypeForHover()
            val key = entry.id to type
            tooltipAdvancedLines[key] ?: entryStacks[entry.id]?.let {
                com.shusheng.cobblemarket.client.ItemComponentsDisplay.itemTooltip(it, client?.player, type)
            }?.map { it to 0xFFFFFF }?.also { tooltipAdvancedLines[key] = it } ?: tooltipStackLines[entry.id]
        } else tooltipStackLines[entry.id]
        if (stackLines != null) {
            lines.addAll(stackLines)
        } else {
            lines.add(Text.literal(entry.itemId) to 0xFFFFFF)
        }
        lines.add(Text.translatable("cobblemarket.gui.tooltip_seller").append(entry.sellerName) to 0xFFFFFF)
        // 价格行：标签默认色，金额段蓝色（2026-08-24 拍板）
        lines.add(Text.translatable("cobblemarket.item.tooltip_price").append(
            Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ${com.shusheng.cobblemarket.client.displayCurrency(entry.currencyName)}").formatted(Formatting.GOLD)
        ) to 0xFFFFFF)
        lines.add(Text.literal("×${entry.count}") to 0xFFFFFF)

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

    private fun isInputFieldFocused() = focused?.let { f -> f === searchField || f === sellerField } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true ||
        sellerField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (cancelEntry != null) {
            handleCancelDialogClick(mouseX.toInt(), mouseY.toInt())
            return true
        }
        val displayEntries = entries
        if (hoveredSlot in displayEntries.indices) {
            openCancelDialog(displayEntries[hoveredSlot])
            return true
        }
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        return result
    }

    override fun shouldPause() = false
}
