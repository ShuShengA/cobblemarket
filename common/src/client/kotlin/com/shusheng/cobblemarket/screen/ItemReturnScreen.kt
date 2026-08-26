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

    private fun requestData() {
        // 页大小上限 42 并向下对齐到列数倍数，保证每页都是整行、不出现半行空格
        val cols = columns().coerceAtLeast(1)
        val clamped = minOf(pageSize(), 42)
        val size = (clamped / cols * cols).coerceAtLeast(1)
        sendToServer(RequestItemReturnPayload(currentPage, size))
    }
    private fun prevPage() {
        if (currentPage > 1) { currentPage--; requestData() }
    }
    private fun nextPage() {
        if (currentPage < totalPages) { currentPage++; requestData() }
    }

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(ItemMarketScreen()) }
        ))

        // 按钮放在底部分割线（gridBottom+4）与背景底边（height-32）之间居中（照物品市场分页按钮布局）
        val gridBottom = getGridStartY() + rows() * (slotSize + gap)
        val btnY = (gridBottom + 5 + (height - 32)) / 2 - 10 - 5
        prevButton = NineSliceButton(leftX, btnY, 80, 20, Text.translatable("cobblemarket.gui.prev"), { prevPage() })
        addDrawableChild(prevButton)
        claimButton = NineSliceButton(width / 2 - 50, btnY, 100, 20, Text.translatable("cobblemarket.return.claim"), { claimAll() })
        addDrawableChild(claimButton)
        nextButton = NineSliceButton(leftX + panelWidth - 80, btnY, 80, 20, Text.translatable("cobblemarket.gui.next"), { nextPage() })
        addDrawableChild(nextButton)

        requestData()
        loaded = true
    }

    private fun claimAll() {
        sendToServer(ClaimItemReturnPayload(ClientConfig.dropOverflowOnClaim))
    }

    fun onReturnData(payload: ItemReturnDataPayload) {
        items = payload.items
        totalPages = payload.totalPages
        currentPage = payload.currentPage
        loaded = true
        rebuildEntryCaches(payload.items)
    }

    private fun rebuildEntryCaches(newEntries: List<ItemEntry>) {
        entryStacks.clear()
        tooltipStackLines.clear()
        val registry = client?.world?.registryManager ?: return
        for (entry in newEntries) {
            val stack = ItemStack.fromNbtOrEmpty(registry, entry.itemNbt)
            entryStacks[entry.id] = stack
            tooltipStackLines[entry.id] = stack.getTooltip(Item.TooltipContext.DEFAULT, client?.player, TooltipType.BASIC)
                .map { it to 0xFFFFFF }
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
        val stackLines = tooltipStackLines[entry.id]
        if (stackLines != null) {
            lines.addAll(stackLines)
        } else {
            lines.add(Text.literal(entry.itemId) to 0xFFFFFF)
        }
        lines.add(Text.translatable("cobblemarket.gui.tooltip_seller").append(" ").append(entry.sellerName) to 0xFFFFFF)
        // 价格行：标签默认色，金额段蓝色（2026-08-24 拍板）
        lines.add(Text.translatable("cobblemarket.item.tooltip_price").append(" ").append(
            Text.literal("${entry.price} ${com.shusheng.cobblemarket.client.displayCurrency(entry.currencyName)}").formatted(Formatting.GOLD)
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
