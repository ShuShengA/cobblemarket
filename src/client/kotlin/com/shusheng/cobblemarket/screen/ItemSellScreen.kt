package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.SellItemPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

class ItemSellScreen : Screen(Text.translatable("cobblemarket.item.sell_title")) {

    private val panelWidth = 296
    private val rowHeight = 24

    private data class SellItem(val stack: ItemStack, val count: Int)

    private var items = listOf<SellItem>()
    private val sellButtons = mutableListOf<NineSliceButton>()
    private var scrollOffset = 0
    private var hoveredRow = -1

    private var selectedItem: SellItem? = null
    private var lastInventoryCheck = 0L
    private var countField: TextFieldWidget? = null
    private var priceField: TextFieldWidget? = null
    private var backButton: NineSliceButton? = null

    private fun getListStartY() = 48
    private fun maxVisible() = maxOf(0, (height - getListStartY() - 48) / rowHeight)

    override fun init() {
        super.init()
        loadInventory()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, items.size - maxVisible()))
        rebuildSellButtons()

        val leftX = width / 2 - panelWidth / 2
        val backBtn = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(ItemMarketScreen()) }
        )
        backButton = backBtn
        addDrawableChild(backBtn)
    }

    private fun loadInventory() {
        val player = client?.player ?: return
        val list = mutableListOf<SellItem>()
        val main = player.inventory.main
        for (i in 0 until main.size) {
            val stack = main[i]
            if (!stack.isEmpty) {
                val idx = list.indexOfFirst { ItemStack.areItemsAndComponentsEqual(it.stack, stack) }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(count = list[idx].count + stack.count)
                } else {
                    list.add(SellItem(stack.copy(), stack.count))
                }
            }
        }
        items = list
    }

    private fun rebuildSellButtons() {
        sellButtons.forEach { remove(it) }
        sellButtons.clear()
        val leftX = width / 2 - panelWidth / 2
        val startY = getListStartY()
        items.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, item ->
            val y = startY + i * rowHeight
            val btn = NineSliceButton(
                leftX + panelWidth - 52, y + 4, 46, 16,
                Text.translatable("cobblemarket.sell.sell"),
                { openConfirmDialog(item) }
            )
            sellButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    private fun openConfirmDialog(item: SellItem) {
        selectedItem = item
        sellButtons.forEach { it.active = false }
        backButton?.active = false
        val centerX = width / 2
        val dialogY = height / 2 - 85

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderDialogBackground(context)
            }
        })

        countField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 72, 160, 16, Text.literal(""))
        countField?.setPlaceholder(Text.translatable("cobblemarket.item.sell_count"))
        countField?.setTextPredicate { it.length <= 3 && it.all { c -> c.isDigit() } }
        addDrawableChild(countField)

        priceField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 102, 160, 16, Text.literal(""))
        priceField?.setPlaceholder(Text.translatable("cobblemarket.item.sell_price"))
        priceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addDrawableChild(priceField)

        addDrawableChild(NineSliceButton(
            centerX - 85, dialogY + 128, 80, 20,
            Text.translatable("cobblemarket.sell.sell"),
            { confirmSell() }
        ))
        addDrawableChild(NineSliceButton(
            centerX + 5, dialogY + 128, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeConfirmDialog() }
        ))
    }

    private fun renderDialogBackground(context: DrawContext) {
        val entry = selectedItem ?: return
        val centerX = width / 2
        val dialogW = 220
        val dialogH = 170
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        context.fill(0, 0, width, height, 0xC0000000.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.sell_confirm_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        context.drawItem(entry.stack, centerX - 8, dialogY + 26)
        context.drawCenteredTextWithShadow(textRenderer,
            entry.stack.name, centerX, dialogY + 46, 0xFFFFFF)

        context.drawTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.sell_count").string + "（" + Text.translatable("cobblemarket.item.sell_max").string + " ${entry.count}）",
            centerX - 80, dialogY + 62, 0xAAAAAA)
        context.drawTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.sell_price").string,
            centerX - 80, dialogY + 92, 0xAAAAAA)
    }

    private fun closeConfirmDialog() {
        selectedItem = null
        countField = null
        priceField = null
        clearChildren()
        init()
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val item = selectedItem
        val count = countField?.text ?: ""
        val price = priceField?.text ?: ""
        super.resize(client, width, height)
        if (item != null) {
            selectedItem = null
            openConfirmDialog(item)
            countField?.text = count
            priceField?.text = price
        }
    }

    private fun confirmSell() {
        val entry = selectedItem ?: return
        val count = countField?.text?.toIntOrNull() ?: return
        val price = priceField?.text?.toIntOrNull() ?: return
        if (count <= 0 || count > entry.count || price <= 0) return
        val registry = client?.world?.registryManager ?: return
        val itemId = Registries.ITEM.getId(entry.stack.item).toString()
        val itemNbt = entry.stack.encode(registry, NbtCompound()) as? NbtCompound ?: NbtCompound()
        ClientPlayNetworking.send(SellItemPayload(itemId, itemNbt, count, price))
        closeConfirmDialog()
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

        val leftX = width / 2 - panelWidth / 2
        val startY = getListStartY()
        hoveredRow = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY >= startY) {
            val row = (mouseY - startY) / rowHeight
            if (row < maxVisible() && (scrollOffset + row) < items.size) hoveredRow = row
        }
        items.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, _ ->
            val rowY = startY + i * rowHeight
            val rowState = if (i == hoveredRow) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, rowState, ROW_BACKGROUND_TEX_H)
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        if (selectedItem != null) {
            return
        }

        // 每秒轻校验：过滤背包里已不存在的条目（如蛋在界面打开期间孵化成精灵），并同步重建行内按钮
        val checkNow = System.currentTimeMillis()
        if (checkNow - lastInventoryCheck > 1000) {
            lastInventoryCheck = checkNow
            val main = client?.player?.inventory?.main
            if (main != null) {
                val filtered = items.filter { it ->
                    main.any { slot -> com.shusheng.cobblemarket.network.itemsEqualForTrading(slot, it.stack) }
                }
                if (filtered.size != items.size) {
                    items = filtered
                    rebuildSellButtons()
                }
            }
        }

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.sell_title").formatted(Formatting.GOLD),
            centerX, 20, 0xFFFFFF)

        val dividerY = getListStartY() - 4
        context.fill(leftX, dividerY, leftX + panelWidth, dividerY + 1, 0xFF555555.toInt())

        if (items.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.item.sell_no_item").formatted(Formatting.GRAY),
                centerX, getListStartY() + 40, 0xFFFFFF)
        }

        val startY = getListStartY()
        items.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, item ->
            val y = startY + i * rowHeight
            context.drawItem(item.stack, leftX + 2, y + 2)
            context.drawTextWithShadow(textRenderer, item.stack.name, leftX + 24, y + 6, 0xFFFFFF)
            context.drawTextWithShadow(textRenderer, "×${item.count}", leftX + 200, y + 6, 0xAAAAAA)
        }

        val actualIdx = scrollOffset + hoveredRow
        if (hoveredRow >= 0 && actualIdx in items.indices) {
            renderItemTooltip(context, items[actualIdx], mouseX, mouseY)
        }

        if (items.size > maxVisible()) {
            context.drawCenteredTextWithShadow(textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible(), items.size)} / ${items.size}",
                width / 2, height - 49, 0x888888)
        }
    }

    private fun renderItemTooltip(context: DrawContext, entry: SellItem, mouseX: Int, mouseY: Int) {
        // 悬停时实时取对应栈：优先屏幕容器槽位（服务端同步数据，计时准确，与背包界面一致）；
        // 回退本地背包（客户端 tick 本地计时有漂移），再回退快照
        val liveStack = client?.player?.playerScreenHandler?.slots
            ?.firstOrNull { it.hasStack() && com.shusheng.cobblemarket.network.itemsEqualForTrading(it.stack, entry.stack) }
            ?.stack
            ?: client?.player?.inventory?.main?.firstOrNull {
                com.shusheng.cobblemarket.network.itemsEqualForTrading(it, entry.stack)
            }
            ?: entry.stack
        val lines = liveStack.getTooltip(Item.TooltipContext.DEFAULT, client?.player, TooltipType.BASIC)
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
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, items.size - maxVisible()))
        rebuildSellButtons()
        return true
    }

    private fun isInputFieldFocused() = focused?.let { f -> f === countField || f === priceField } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        countField?.isMouseOver(mouseX, mouseY) == true ||
        priceField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        return result
    }

    fun onMarketResult(payload: MarketResultPayload) {
        if (payload.success) {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.GREEN), false)
            loadInventory()
            rebuildSellButtons()
        } else {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.RED), false)
        }
    }

    override fun shouldPause() = false
}
