package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.TooltipPlacement
import com.shusheng.cobblemarket.client.playFailSound

import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.SellItemPayload
import com.shusheng.cobblemarket.platform.sendToServer
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
import com.shusheng.cobblemarket.client.drawItemWithBar

class ItemSellScreen : Screen(Text.translatable("cobblemarket.item.sell_title")) {

    private val panelWidth = 296
    private val rowHeight = 24

    // ── 行按钮几何（**建按钮与悬停窗避让共用一处定义**，别各写一份）──
    private val rowBtnInset = 52
    private val rowBtnW = 46
    private val rowBtnH = 16
    private val rowBtnDy = 4

    /** 第 [row] 行的出售按钮矩形 —— 与 `rebuildSellButtons` 同源 */
    private fun sellButtonRect(row: Int) = TooltipPlacement.Rect(
        width / 2 - panelWidth / 2 + panelWidth - rowBtnInset,
        getListStartY() + row * rowHeight + rowBtnDy, rowBtnW, rowBtnH
    )

    // name：显示名在背包扫描时构建一次，render 每帧取 stack.name 是分配热点
    private data class SellItem(val stack: ItemStack, val count: Int, val name: String)

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
            Text.literal(""),
            { client?.setScreen(ItemMarketScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
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
                    list.add(SellItem(stack.copy(), stack.count, stack.name.string))
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
                leftX + panelWidth - rowBtnInset, y + rowBtnDy, rowBtnW, rowBtnH,
                Text.translatable("cobblemarket.sell.sell"),
                { openConfirmDialog(item) }
            )
            // 弹窗打开期间重建（如滚动）的行按钮同样隐藏，防止文字透过遮罩
            if (selectedItem != null) btn.visible = false
            sellButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    private fun openConfirmDialog(item: SellItem) {
        selectedItem = item
        // widget 文字画在遮罩（addDrawable）之上，active=false 只变灰仍会透过——必须 visible=false
        sellButtons.forEach { it.visible = false }
        backButton?.visible = false
        // 清掉可能残留的聚焦（旧输入框对象），否则 E 键保护会误判为输入中
        setFocused(null)
        val centerX = width / 2
        val dialogY = height / 2 - 85

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderDialogBackground(context)
            }
        })

        countField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 72, 160, 16, Text.literal(""))
        countField?.setPlaceholder(Text.translatable("cobblemarket.item.sell_count"))
        countField?.setTextPredicate { it.length <= 4 && it.all { c -> c.isDigit() } }
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
        val dialogW = minOf(300, width - 40).coerceAtLeast(200)
        val dialogH = 170
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.sell_confirm_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        drawItemWithBar(context, entry.stack, centerX - 8, dialogY + 26)
        context.drawCenteredTextWithShadow(textRenderer,
            entry.name, centerX, dialogY + 46, 0xFFFFFF)

        context.drawTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.sell_count").string + com.shusheng.cobblemarket.util.TextUtil.parenOpen() + Text.translatable("cobblemarket.item.sell_max").string + " ${entry.count}" + com.shusheng.cobblemarket.util.TextUtil.parenClose(),
            centerX - 80, dialogY + 62, 0xAAAAAA)
        context.drawTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.sell_price").string,
            centerX - 80, dialogY + 92, 0xAAAAAA)
    }

    private fun closeConfirmDialog() {
        selectedItem = null
        countField = null
        priceField = null
        // 聚焦指向已废弃的输入框对象会让 E 键保护（focused is TextFieldWidget）误判，关闭弹窗必须清除
        setFocused(null)
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
        val count = countField?.text?.toIntOrNull() ?: run {
            playFailSound()
            return
        }
        val price = priceField?.text?.toIntOrNull() ?: run {
            playFailSound()
            return
        }
        if (count <= 0 || count > entry.count || price <= 0) {
            playFailSound()
            return
        }
        val registry = client?.world?.registryManager ?: return
        val itemId = Registries.ITEM.getId(entry.stack.item).toString()
        val itemNbt = entry.stack.encode(registry, NbtCompound()) as? NbtCompound ?: NbtCompound()
        sendToServer(SellItemPayload(itemId, itemNbt, count, price))
        closeConfirmDialog()
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        MarketPanelBackground.draw(context, width, height)

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
        drawPanelDivider(context, leftX + panelWidth / 2, dividerY)

        if (items.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.item.sell_no_item").formatted(Formatting.GRAY),
                centerX, getListStartY() + 40, 0xFFFFFF)
        }

        val startY = getListStartY()
        items.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, item ->
            val y = startY + i * rowHeight
            drawItemWithBar(context, item.stack, leftX + 2, y + 2)
            // 行名照物品栏悬浮第一行按稀有度着色（组件明细看悬停词条，行内不重复显示）
            val nameText = com.shusheng.cobblemarket.util.TextUtil.rarityColoredName(item.stack)
            context.drawTextWithShadow(textRenderer,
                com.shusheng.cobblemarket.util.TextUtil.truncateText(nameText, 200 - 24 - 6),
                leftX + 24, y + 6, 0xFFFFFF)
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

    // tooltip 内容缓存：悬停同一物品时每帧双背包扫描 + getTooltip 是悬停掉帧主因。
    // 内容要反映实时耐久/计时，悬停目标变化时立即重建；同一目标悬停期间 250ms 节流重建
    private var tooltipCacheKey: SellItem? = null
    private var tooltipCacheLines: List<Text> = emptyList()
    private var tooltipCacheMaxWidth = 0
    private var lastTooltipBuild = 0L
    /** Shift 展开的高级词条缓存（按住 Shift 才按需构建，同 250ms 节流重建） */
    private var tooltipAdvancedLines: List<Text>? = null
    private var tooltipAdvancedType: net.minecraft.item.tooltip.TooltipType? = null
    private var tooltipAdvancedMaxWidth = 0
    private var lastAdvancedBuild = 0L

    private fun renderItemTooltip(context: DrawContext, entry: SellItem, mouseX: Int, mouseY: Int) {
        val now = System.currentTimeMillis()
        if (tooltipCacheKey !== entry || now - lastTooltipBuild >= 250) {
            tooltipCacheKey = entry
            lastTooltipBuild = now
            // 悬停时实时取对应栈：优先屏幕容器槽位（服务端同步数据，计时准确，与背包界面一致）；
            // 回退本地背包（客户端 tick 本地计时有漂移），再回退快照
            val liveStack = client?.player?.playerScreenHandler?.slots
                ?.firstOrNull { it.hasStack() && com.shusheng.cobblemarket.network.itemsEqualForTrading(it.stack, entry.stack) }
                ?.stack
                ?: client?.player?.inventory?.main?.firstOrNull {
                    com.shusheng.cobblemarket.network.itemsEqualForTrading(it, entry.stack)
                }
                ?: entry.stack
            // 走统一入口（别直连 getTooltip）：受损物品才会多一行「耐久：X / Y」
            tooltipCacheLines = com.shusheng.cobblemarket.client.ItemComponentsDisplay
                .itemTooltip(liveStack, client?.player, TooltipType.BASIC)
            var maxWidth = 0
            tooltipCacheLines.forEach { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it)) }
            tooltipCacheMaxWidth = maxWidth
            tooltipAdvancedLines = null
        }
        // 按住 Shift 展开完整词条 / Ctrl（F3+H 开启）展开调试信息（照原版背包悬停；250ms 节流重建同 BASIC）
        var lines = tooltipCacheLines
        var maxWidth = tooltipCacheMaxWidth
        if (com.shusheng.cobblemarket.client.ItemComponentsDisplay.hoverExpanded()) {
            val type = com.shusheng.cobblemarket.client.ItemComponentsDisplay.tooltipTypeForHover()
            if (tooltipAdvancedLines == null || tooltipAdvancedType != type || now - lastAdvancedBuild >= 250) {
                lastAdvancedBuild = now
                val liveStack = client?.player?.playerScreenHandler?.slots
                    ?.firstOrNull { it.hasStack() && com.shusheng.cobblemarket.network.itemsEqualForTrading(it.stack, entry.stack) }
                    ?.stack
                    ?: client?.player?.inventory?.main?.firstOrNull {
                        com.shusheng.cobblemarket.network.itemsEqualForTrading(it, entry.stack)
                    }
                    ?: entry.stack
                tooltipAdvancedLines = com.shusheng.cobblemarket.client.ItemComponentsDisplay.itemTooltip(liveStack, client?.player, type)
                tooltipAdvancedType = type
                var amw = 0
                tooltipAdvancedLines!!.forEach { amw = maxOf(amw, textRenderer.getWidth(it)) }
                tooltipAdvancedMaxWidth = amw
            }
            tooltipAdvancedLines?.let { lines = it; maxWidth = tooltipAdvancedMaxWidth }
        } else {
            tooltipAdvancedType = null
        }

        val padding = 4
        val tooltipHeight = lines.size * 10 + padding
        // 悬停窗**不许把本行的出售按钮盖掉一半以上**：矩形按行号算几何（与 rebuildSellButtons 同源）
        val (tx, ty) = TooltipPlacement.place(
            mouseX = mouseX, mouseY = mouseY,
            textW = maxWidth, textH = tooltipHeight, pad = padding,
            screenW = width, screenH = height, avoid = sellButtonRect(hoveredRow)
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
