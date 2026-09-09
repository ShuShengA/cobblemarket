package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.ClientConfig
import com.shusheng.cobblemarket.network.ClaimItemReturnPayload
import com.shusheng.cobblemarket.network.ItemEntry
import com.shusheng.cobblemarket.network.ItemReturnDataPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.RequestItemReturnPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.util.UUID

class ItemReturnScreen : Screen(Text.translatable("cobblemarket.return.title")) {

    private val panelWidth = 296
    private val slotSize = 36
    private val gap = 4

    private var items = listOf<ItemEntry>()
    private var loaded = false
    private var hoveredSlot = -1
    private var currentPage = 1
    private var totalPages = 1
    private var prevButton: NineSliceButton? = null
    private var nextButton: NineSliceButton? = null
    private var claimButton: NineSliceButton? = null

    // 物品 NBT 解析缓存：数据到达时一次性反序列化，避免 render 每帧 fromNbtOrEmpty 造成 GC 压力
    private val entryStacks = mutableMapOf<UUID, ItemStack>()
    private val tooltipStackLines = mutableMapOf<UUID, List<Pair<Text, Int>>>()
    /** Shift 展开的高级词条（列表加载时与 BASIC 双份构建，渲染按 Shift 状态切换） */
    // 键 (entryId, TooltipType)：Shift/Ctrl 展开类型不同分别缓存
    private val tooltipAdvancedLines = mutableMapOf<Pair<UUID, TooltipType>, List<Pair<Text, Int>>>()

    private fun columns() = (panelWidth + gap) / (slotSize + gap)
    private fun getGridStartY() = 48
    private fun rows() = maxOf(0, (height - getGridStartY() - 72) / (slotSize + gap))

    private fun pageSize() = columns() * rows()

    // 协议分页：服务端按请求的 pageSize 切片，本地只做防溢出截断
    private fun pageItems(): List<ItemEntry> = items.take(maxOf(0, pageSize()))

    /** 拍卖结算完成事件：刷新列表（货已进待取回，不用重开界面） */
    fun onAuctionSettled() {
        requestData()
    }

    private fun requestData(resetPending: Boolean = true) {
        lastListRequestAt = System.currentTimeMillis()
        if (resetPending) pendingPage = 0 // 非翻页路径（领取后刷新等）取消未发出的翻页目标
        // 页大小上限 42 并向下对齐到列数倍数，保证每页都是整行、不出现半行空格
        val cols = columns().coerceAtLeast(1)
        val clamped = minOf(pageSize(), 42)
        val size = (clamped / cols * cols).coerceAtLeast(1)
        sendToServer(RequestItemReturnPayload(currentPage, size))
    }

    // 翻页请求在途标志：响应到达前不重复发请求
    private var pageRequestInFlight = false

    // 最近一次列表请求发送时间：补发节奏与服务端节流窗口（request_item_return 500ms）对齐，
    // 窗口内不发——请求若被服务端静默丢弃则无响应，inFlight 会永久卡死按钮
    private var lastListRequestAt = 0L

    // 待翻页目标：点击立即更新页码并把目标页记到这里，tick 在窗口允许时补发请求。
    // 连点合并到最终目标页（中间页不发），点击永远有立即反馈，且请求永不撞节流窗口
    private var pendingPage = 0

    private fun updatePageButtons() {
        prevButton?.active = currentPage > 1
        nextButton?.active = currentPage < totalPages
    }

    // 兜底 + 补发：1s 未收到响应强制复位 inFlight（防锁灰）；窗口允许且无在途时补发翻页目标
    override fun tick() {
        if (pageRequestInFlight && System.currentTimeMillis() - lastListRequestAt > 1000) {
            pageRequestInFlight = false
        }
        // 目标保留到响应确认，请求被丢弃时兜底复位后自动重试
        if (!pageRequestInFlight && pendingPage != 0 && System.currentTimeMillis() - lastListRequestAt >= PAGE_CLICK_INTERVAL_MS) {
            currentPage = pendingPage.coerceIn(1, maxOf(1, totalPages))
            pageRequestInFlight = true
            updatePageButtons()
            requestData(resetPending = false)
        }
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
        /** 补发请求最小间隔：服务端 request_item_return 节流 500ms + 100ms 余量，防网络抖动边界丢弃 */
        const val PAGE_CLICK_INTERVAL_MS = 600L
    }

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(ItemMarketScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        ))

        // 按钮放在底部分割线（gridBottom+4）与背景底边（height-32）之间居中（照物品市场分页按钮布局）
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
        claimButton = NineSliceButton(width / 2 - 50, btnY, 100, 20, Text.translatable("cobblemarket.return.claim"), { claimAll() })
        addDrawableChild(claimButton)
        nextButton = NineSliceButton(
            leftX + panelWidth - 80, btnY, 80, 20,
            Text.literal(""),
            { nextPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/next.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.next")
        )
        addDrawableChild(nextButton)

        requestData()
        loaded = true
    }

    private fun claimAll() {
        sendToServer(ClaimItemReturnPayload(ClientConfig.dropOverflowOnClaim))
    }

    fun onReturnData(payload: ItemReturnDataPayload) {
        pageRequestInFlight = false
        items = payload.items
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
        updatePageButtons()
        loaded = true
        rebuildEntryCaches(payload.items)
    }

    private fun rebuildEntryCaches(newEntries: List<ItemEntry>) {
        entryStacks.clear()
        tooltipStackLines.clear()
        tooltipAdvancedLines.clear()
        val registry = client?.world?.registryManager ?: return
        for (entry in newEntries) {
            val stack = ItemStack.fromNbtOrEmpty(registry, entry.itemNbt)
            entryStacks[entry.id] = stack
            tooltipStackLines[entry.id] = stack.getTooltip(Item.TooltipContext.DEFAULT, client?.player, TooltipType.BASIC)
                .map { it to 0xFFFFFF }
            // ADVANCED 不在此构建：Fabric tooltip 信息块只在构建时 Shift 按住才生成，渲染处按需构建
        }
    }

    fun onMarketResult(payload: MarketResultPayload) {
        client?.player?.sendMessage(payload.message.copy().formatted(if (payload.success) Formatting.GREEN else Formatting.RED), false)
        currentPage = 1
        requestData()
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

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.return.title").formatted(Formatting.GOLD),
            centerX, 20, 0xFFFFFF)

        val tp = totalPages
        if (currentPage > tp) currentPage = tp
        if (currentPage < 1) currentPage = 1
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.gui.page", currentPage, tp).formatted(Formatting.GRAY),
            centerX, 32, 0xFFFFFF)

        val dividerY = 44
        context.fill(leftX, dividerY, leftX + panelWidth, dividerY + 1, 0xFF555555.toInt())
        // 底部分割线：网格最后一行下方 4px（照物品市场分页布局），按钮在其与背景底边之间居中
        val gridBottom = getGridStartY() + rows() * (slotSize + gap)
        context.fill(leftX, gridBottom + 4, leftX + panelWidth, gridBottom + 5, 0xFF555555.toInt())

        if (items.isEmpty() && loaded) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.return.empty").formatted(Formatting.GRAY),
                centerX, 100, 0xFFFFFF)
        }

        val cols = columns()
        val rows = rows()
        val gridOffsetX = (panelWidth - (cols * slotSize + (cols - 1) * gap)) / 2

        val page = pageItems()

        hoveredSlot = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY >= getGridStartY()) {
            val col = (mouseX - leftX - gridOffsetX) / (slotSize + gap)
            val row = (mouseY - getGridStartY()) / (slotSize + gap)
            val idx = row * cols + col
            if (col in 0 until cols && row in 0 until rows && idx in page.indices) hoveredSlot = idx
        }

        page.forEachIndexed { index, entry ->
            val col = index % cols
            val row = index / cols
            val x = leftX + gridOffsetX + col * (slotSize + gap)
            val y = getGridStartY() + row * (slotSize + gap)

            val rowState = if (index == hoveredSlot) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, x, y, slotSize, slotSize, rowState, ROW_BACKGROUND_TEX_H)

            entryStacks[entry.id]?.let { stack ->
                context.drawItem(stack, x + (slotSize - 16) / 2, y + (slotSize - 16) / 2)
            }

            val countText = "×${entry.count}"
            context.drawText(textRenderer, countText, x + slotSize - 2 - textRenderer.getWidth(countText), y + 2, 0xFFFFFF, false)
        }

        if (hoveredSlot in page.indices) {
            renderItemTooltip(context, page[hoveredSlot], mouseX, mouseY)
        }

        prevButton?.active = currentPage > 1
        nextButton?.active = currentPage < totalPages
        claimButton?.active = items.isNotEmpty()
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
        lines.add(Text.translatable("cobblemarket.gui.tooltip_seller").append(" ").append(entry.sellerName) to 0xFFFFFF)
        // 价格行：标签默认色，金额段蓝色（2026-08-24 拍板）
        lines.add(Text.translatable("cobblemarket.item.tooltip_price").append(" ").append(
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

    override fun shouldPause() = false
}
