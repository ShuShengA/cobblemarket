package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.network.BuyItemPayload
import com.shusheng.cobblemarket.network.CancelItemPayload
import com.shusheng.cobblemarket.network.CollectBalancePayload
import com.shusheng.cobblemarket.network.RequestBalancePayload
import com.shusheng.cobblemarket.network.ItemEntry
import com.shusheng.cobblemarket.network.ItemMarketDataPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.RequestItemMarketPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
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

class ItemMarketScreen : Screen(Text.translatable("cobblemarket.item.title")) {

    private val panelWidth = 296
    private val slotSize = 36
    private val gap = 4

    private var entries = listOf<ItemEntry>()
    private var currentPage = 1
    private var totalPages = 1
    private var pendingBalance = 0L

    private var searchField: TextFieldWidget? = null
    private var sortMode = "NEWEST"
    private var showMineOnly = false
    private var sortButton: NineSliceButton? = null
    private var mineButton: NineSliceButton? = null
    private var prevButton: NineSliceButton? = null
    private var nextButton: NineSliceButton? = null

    private var hoveredSlot = -1

    private var selectedEntry: ItemEntry? = null
    private var buyCountField: TextFieldWidget? = null
    private var cancelEntry: ItemEntry? = null

    private fun columns() = (panelWidth + gap) / (slotSize + gap)
    private fun getGridStartY() = 68
    // 底部预留 56px（翻页按钮 20 + 面板底边框 16 + 间距 20），
    // 保证窗口高度为任意值时翻页按钮都不会遮住面板底部边框
    private fun rows() = maxOf(0, (height - getGridStartY() - 56) / (slotSize + gap))

    private fun sortDisplay(): String = when (sortMode) {
        "PRICE_ASC" -> "cobblemarket.sort.price_asc"
        "PRICE_DESC" -> "cobblemarket.sort.price_desc"
        "NEWEST" -> "cobblemarket.sort.newest"
        else -> "cobblemarket.sort.price_asc"
    }

    override fun init() {
        super.init()
        ClientPlayNetworking.send(RequestBalancePayload())
        val leftX = width / 2 - panelWidth / 2

        // 收款 / 返回
        addDrawableChild(NineSliceButton(
            leftX, 13, 50, 16,
            Text.translatable("cobblemarket.gui.collect"), { collectBalance() }
        ))

        // Expired returns button
        addDrawableChild(NineSliceButton(
            leftX + 52, 13, 50, 16,
            Text.translatable("cobblemarket.gui.returns"), { client?.setScreen(ItemReturnScreen()) }
        ))
        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.translatable("cobblemarket.gui.back"), { client?.setScreen(MarketEntryScreen()) }
        ))

        // 搜索框
        searchField = TextFieldWidget(textRenderer, leftX + 2, 44, 132, 16, Text.translatable("cobblemarket.item.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.item.search").formatted(Formatting.GRAY))
        addSelectableChild(searchField)
        addDrawableChild(searchField)

        // 上架按钮（+）
        addDrawableChild(NineSliceButton(
            leftX + 136, 44, 18, 16,
            Text.literal("+"), { openItemSellScreen() }
        ))

        // 排序按钮
        sortButton = NineSliceButton(
            leftX + panelWidth - 88, 44, 86, 16,
            Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay())),
            { cycleSort() }
        )
        addDrawableChild(sortButton)

        // 我的
        mineButton = NineSliceButton(
            leftX + 156, 44, 50, 16,
            Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine"),
            { toggleMineOnly() }
        )
        addDrawableChild(mineButton)

        // 分页
        val gridBottom = getGridStartY() + rows() * (slotSize + gap)
        prevButton = NineSliceButton(leftX, gridBottom, 80, 20, Text.translatable("cobblemarket.gui.prev"), { prevPage() })
        addDrawableChild(prevButton)
        nextButton = NineSliceButton(leftX + panelWidth - 80, gridBottom, 80, 20, Text.translatable("cobblemarket.gui.next"), { nextPage() })
        addDrawableChild(nextButton)

        refreshData()
    }

    private fun openItemSellScreen() {
        client?.setScreen(ItemSellScreen())
    }

    private fun refreshData() {
        ClientPlayNetworking.send(RequestItemMarketPayload(sortMode, currentPage, showMineOnly, columns() * rows()))
    }

    private fun collectBalance() {
        ClientPlayNetworking.send(CollectBalancePayload())
    }

    fun onItemMarketData(payload: ItemMarketDataPayload) {
        entries = payload.entries
        totalPages = payload.totalPages
        currentPage = payload.currentPage
        pendingBalance = payload.pendingBalance
    }

    private fun filteredEntries(): List<ItemEntry> {
        val query = searchField?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return entries
        val registry = client?.world?.registryManager ?: return entries
        return entries.filter { entry ->
            val stack = ItemStack.fromNbtOrEmpty(registry, entry.itemNbt)
            stack.name.string.contains(query, ignoreCase = true)
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
        refreshData()
    }

    private fun toggleMineOnly() {
        showMineOnly = !showMineOnly
        mineButton?.message = Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine")
        currentPage = 1
        refreshData()
    }

    private fun prevPage() {
        if (currentPage > 1) {
            currentPage--
            refreshData()
        }
    }

    private fun nextPage() {
        if (currentPage < totalPages) {
            currentPage++
            refreshData()
        }
    }

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f, 1f)
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
            drawPanelSlice(context, mid, panelLeft, y)
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

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.title").formatted(Formatting.GOLD),
            centerX, 14, 0xFFFFFF)

        // 余额（货币蓝）+ 待收款（绿），按钮行下方，左对齐；页码右对齐，长数字互不干扰
        val balPrefix = com.shusheng.cobblemarket.client.BalanceCache.balance.takeIf { it.isNotEmpty() }
            ?.let { Text.translatable("cobblemarket.gui.balance", it).string + "  " } ?: ""
        context.drawTextWithShadow(textRenderer, balPrefix, leftX, 31, 0x55FFFF)
        context.drawTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.gui.pending_balance", com.shusheng.cobblemarket.client.formatPriceLong(pendingBalance) + " ◆").string,
            leftX + textRenderer.getWidth(balPrefix), 31, 0x55FF55)

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

        val cols = columns()
        val rows = rows()
        val displayEntries = filteredEntries()
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

            val registry = client?.world?.registryManager
            if (registry != null) {
                val stack = ItemStack.fromNbtOrEmpty(registry, entry.itemNbt)
                context.drawItem(stack, x + (slotSize - 16) / 2, y + (slotSize - 16) / 2)
            }

            val countText = "×${entry.count}"
            context.drawText(textRenderer, countText, x + slotSize - 2 - textRenderer.getWidth(countText), y + 2, 0xFFFFFF, false)

            val priceText = "${com.shusheng.cobblemarket.client.formatPriceShort(entry.price)} ◆"
            context.drawText(textRenderer, priceText, x + 3, y + slotSize - 10, 0x55FFFF, false)
        }

        if (hoveredSlot in displayEntries.indices) {
            renderItemTooltip(context, displayEntries[hoveredSlot], mouseX, mouseY)
        }
    }

    private fun renderItemTooltip(context: DrawContext, entry: ItemEntry, mouseX: Int, mouseY: Int) {
        val registry = client?.world?.registryManager
        val lines = mutableListOf<Text>()
        if (registry != null) {
            val stack = ItemStack.fromNbtOrEmpty(registry, entry.itemNbt)
            lines.addAll(stack.getTooltip(Item.TooltipContext.DEFAULT, client?.player, TooltipType.BASIC))
        } else {
            lines.add(Text.literal(entry.itemId))
        }
        lines.add(Text.translatable("cobblemarket.gui.tooltip_seller").formatted(Formatting.GRAY).append(" ").append(entry.sellerName))
        lines.add(Text.translatable("cobblemarket.item.tooltip_price").formatted(Formatting.GRAY).append(" ").append("${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ${com.shusheng.cobblemarket.client.displayCurrency(entry.currencyName)}"))
        lines.add(Text.literal("×${entry.count}"))

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

    private fun isInputFieldFocused() = focused?.let { f -> f === searchField || f === buyCountField } ?: false

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
        val displayEntries = filteredEntries()
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

    private fun openBuyDialog(entry: ItemEntry) {
        selectedEntry = entry
        val centerX = width / 2
        val dialogY = height / 2 - 85

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderBuyDialogBackground(context)
            }
        })

        buyCountField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 72, 100, 16, Text.literal(""))
        buyCountField?.setPlaceholder(Text.translatable("cobblemarket.item.buy_count"))
        buyCountField?.setTextPredicate { it.length <= 3 && it.all { c -> c.isDigit() } }
        addDrawableChild(buyCountField)

        addDrawableChild(NineSliceButton(
            centerX - 85, dialogY + 128, 80, 20,
            Text.translatable("cobblemarket.gui.buy"),
            { confirmBuy() }
        ))
        addDrawableChild(NineSliceButton(
            centerX + 5, dialogY + 128, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeBuyDialog() }
        ))
    }

    private fun closeBuyDialog() {
        selectedEntry = null
        buyCountField = null
        clearChildren()
        init()
    }

    private fun openCancelDialog(entry: ItemEntry) {
        cancelEntry = entry
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
        ClientPlayNetworking.send(CancelItemPayload(entry.id))
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
        context.fill(0, 0, width, height, 0xC0000000.toInt())
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
            context.drawItem(stack, centerX - 8, dialogY + 26)
            context.drawCenteredTextWithShadow(textRenderer, stack.name, centerX, dialogY + 46, 0xFFFFFF)
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
        val count = buyCountField?.text?.toIntOrNull() ?: return
        if (count <= 0 || count > entry.count) return
        ClientPlayNetworking.send(BuyItemPayload(entry.id, count))
        closeBuyDialog()
    }

    private fun buyTotal(): Int {
        val entry = selectedEntry ?: return 0
        val count = buyCountField?.text?.toIntOrNull() ?: 0
        return entry.price * count
    }

    private fun renderBuyDialogBackground(context: DrawContext) {
        val entry = selectedEntry ?: return
        val centerX = width / 2
        val dialogW = 220
        val dialogH = 170
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        context.fill(0, 0, width, height, 0xC0000000.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.buy_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        val registry = client?.world?.registryManager
        if (registry != null) {
            val stack = ItemStack.fromNbtOrEmpty(registry, entry.itemNbt)
            context.drawItem(stack, centerX - 8, dialogY + 26)
            context.drawCenteredTextWithShadow(textRenderer, stack.name, centerX, dialogY + 46, 0xFFFFFF)
        }

        context.drawTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.buy_count").string + "（" + Text.translatable("cobblemarket.item.sell_max").string + " ${entry.count}）",
            centerX - 80, dialogY + 62, 0xAAAAAA)
        context.drawTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.buy_total").string + ": " + buyTotal().toString() + " " + com.shusheng.cobblemarket.client.displayCurrency(entry.currencyName),
            centerX - 80, dialogY + 92, 0xAAAAAA)
    }

    fun onMarketResult(payload: MarketResultPayload) {
        if (payload.success) {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.GREEN), false)
            refreshData()
        } else {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.RED), false)
        }
    }

    override fun shouldPause() = false
}
