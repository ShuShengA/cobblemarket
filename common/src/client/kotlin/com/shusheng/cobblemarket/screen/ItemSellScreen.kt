package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.CURRENCY_BUTTON_ICON_SIZE
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_INLINE_SIZE
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_SRC
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_TEXTURE
import com.shusheng.cobblemarket.client.PointsStateCache
import com.shusheng.cobblemarket.client.clampTooltipRows
import com.shusheng.cobblemarket.client.TooltipPlacement
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.MarketCurrency

import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.SellItemPayload
import com.shusheng.cobblemarket.network.ShareNetwork
import com.shusheng.cobblemarket.network.SharePayload
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

/**
 * 选择要上架的物品界面；分享模式（shareMode）复用本界面：把价格弹窗换成「直接分享」，
 * 行按钮改文案后按一下即把该物品发到全服聊天。
 */
class ItemSellScreen(private val shareMode: Boolean = false) : Screen(Text.translatable(
    if (shareMode) "cobblemarket.share.title_item" else "cobblemarket.item.sell_title"
)) {

    private val panelWidth = 296
    private val rowHeight = 24

    /** 是否展示模式：结果音效分发要据此换成专属音（见 CobbleMarketClient 的 MarketResultPayload 处理） */
    fun isShareMode(): Boolean = shareMode

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
    // slot：首次出现的背包主栏槽位（同类物品跨多个栈时数量合并显示，分享按这个槽位取实物——
    //       服务端只认槽位号、自己读背包，客户端递上来的内容一律不信）
    private data class SellItem(val stack: ItemStack, val count: Int, val name: String, val slot: Int)

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
            // 分享模式是从入口界面进来的，返回也回入口（上架模式照旧回物品市场）。
            // ⚠ 必须 `skipDropAnim`：否则返回入口会**重播一次掉落入场动画** ——
            //   那段动画是「从游戏外部打开市场」才该播的（2026-09-28 用户报）
            { client?.setScreen(if (shareMode) MarketEntryScreen(skipDropAnim = true) else ItemMarketScreen()) },
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
                    list.add(SellItem(stack.copy(), stack.count, stack.name.string, i))
                }
            }
        }
        items = list
    }

    private fun rebuildSellButtons() {
        sellButtons.forEach { remove(it) }
        sellButtons.clear()
        // ⚠ 分享模式**不建行内按钮**（2026-09-28 用户定：取消展示按钮，改成点行直接展示）。
        //   这些是"每行一个"的动态控件 ⇒ 这里直接不建（不是设 visible）；
        //   反正 `sellButtons` 只服务于"打开/关闭上架确认弹窗"的隐藏与恢复，分享模式用不到。
        if (shareMode) return
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

        // 价格框 160 → 120：右侧腾出位置给货币切换按钮（最多 9 位数字，用不到 160 那么宽）
        priceField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 102, 120, 16, Text.literal(""))
        priceField?.setPlaceholder(Text.translatable("cobblemarket.item.sell_price"))
        priceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addDrawableChild(priceField)

        // 货币切换按钮（价格框右侧）。⚠ 服务端没开 RP 总开关、或没开「玩家间 RP 交易」时
        // **整个按钮不创建** —— 价格区干干净净，比留个点了报错的灰按钮清楚。
        // 图标走 points_symbol，缩到 8×8 —— 与余额 HUD 那个单位图标同图同尺寸。
        // ⚠ 这里**不再重置币种**：初始值来自 `MarketStateCache.marketCurrency`（2026-09-28）——
        //   在 RP 页点「上架」进来就该是 RP；原先这一行会把刚读到的共享值清回主货币（用户实测报的）
        // ⚠ 只有 RP **总开关**关着时不建；「玩家间 RP 交易」没开照样显示、只是置灰点不动
        // ⚠ **总开关关着时也建**（只是置灰）：按钮的位置是排好的，凭空消失会空出一块。
        //   （用户 2026-09-24 定：创建类界面的货币按钮统一「建 + 置灰」）
        currencyButton = NineSliceButton(
            centerX + 44, dialogY + 102, 20, 16, Text.literal(""), { onCurrencyClick() },
            iconTexW = POINTS_SYMBOL_SRC, iconTexH = POINTS_SYMBOL_SRC,
            iconScale = CURRENCY_BUTTON_ICON_SIZE / POINTS_SYMBOL_SRC.toFloat()
        ).also {
            // 两种"不能用"都置灰：RP 总开关关 / 玩家间 RP 交易没开
            it.dimmed = !PointsStateCache.enabled || !PointsStateCache.marketEnabled
            updateCurrencyButton(it)
            addDrawableChild(it)
        }

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
        // ⚠ 提交前**再查一次实时缓存**：服主可能在弹窗开着的时候把「玩家间 RP 交易」关掉了，
        //   那这一次必须拦下并切回主货币（服务端也会拒，这里是为了给个明确提示、而不是静默失败）
        if (currency == MarketCurrency.POINTS && !PointsStateCache.marketEnabled) {
            setCurrency(MarketCurrency.PRIMARY)
            client?.player?.sendMessage(
                // ⚠ 提醒类消息**用红色**，和普通反馈区分开（图标自带白色，不受影响）
                Text.translatable("cobblemarket.network.points_market_disabled", CurrencyService.pointsIcon())
                    .formatted(Formatting.RED),
                false
            )
            playFailSound()
            return
        }
        sendToServer(SellItemPayload(itemId, itemNbt, count, price, currency.name))
        closeConfirmDialog()
    }

    /** 这次上架用哪个币种定价（默认主货币） */
    private var currency = com.shusheng.cobblemarket.client.MarketStateCache.marketCurrency

    /** 货币切换按钮（服务端没开 RP 相关开关时为 null = 压根没建） */
    private var currencyButton: NineSliceButton? = null

    /** 点货币按钮：「玩家间 RP 交易」没开就只给反馈、不切（置灰态按钮同样走这里） */
    private fun onCurrencyClick() {
        // ⚠ 总开关关着时按钮只是置灰、仍可点：先给「未开启 RP」的提示（两个词条对应两种"不能用"，别混用）
        if (!PointsStateCache.enabled) {
            playFailSound()
            client?.player?.sendMessage(
                Text.translatable("cobblemarket.network.points_disabled", CurrencyService.pointsIcon())
                    .formatted(Formatting.RED),
                false
            )
            return
        }
        if (!PointsStateCache.marketEnabled) {
            playFailSound()
            client?.player?.sendMessage(
                // ⚠ 提醒类消息**用红色**，和普通反馈区分开（图标自带白色，不受影响）
                Text.translatable("cobblemarket.network.points_market_disabled", CurrencyService.pointsIcon())
                    .formatted(Formatting.RED),
                false
            )
            return
        }
        toggleCurrency()
    }

    /**
     * 设置当前币种 —— **唯一入口**：改字段、刷按钮外观、写共享缓存，三件事一起做。
     *
     * ⚠⚠ **别直接写 `currency = ...`**：那样容易只改字段、忘了刷外观 ——
     *   于是玩家**看到的**是主货币、**提交的**却是 RP（或反过来）。
     *   ⚠ RP 的来路由服主决定（**可能是真钱充值来的**，也可能只是任务 / 活动奖励），
     *   一旦不一致，轻则玩家卖亏、重则直接是经济损失（用户 2026-09-28 拍板必须堵死）。
     *   自查：全类 `grep "currency = "` 应**只剩本函数里那一处**。
     */
    private fun setCurrency(value: MarketCurrency) {
        currency = value
        currencyButton?.let { updateCurrencyButton(it) }
        com.shusheng.cobblemarket.client.MarketStateCache.marketCurrency = value
    }

    /** 切主货币 ↔ RP */
    private fun toggleCurrency() {
        setCurrency(if (currency == MarketCurrency.POINTS) MarketCurrency.PRIMARY else MarketCurrency.POINTS)
    }

    /** 刷新货币按钮外观：主货币显示 `₽` 文字，RP 显示 8×8 图标（与余额 HUD 的单位同一张图） */
    private fun updateCurrencyButton(btn: NineSliceButton) {
        val points = currency == MarketCurrency.POINTS
        btn.setMessage(Text.literal(if (points) "" else "₽"))
        btn.iconLeft = if (points) POINTS_SYMBOL_TEXTURE else null
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

        // ⚠ 标题有**两处**：构造里的 `Screen.title` 与这里（**真正画给玩家看的是这一处**）——
        //   加分叉时两处都要改，只改构造那处玩家看不到任何变化（2026-09-28 用户报的就是这个）
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable(if (shareMode) "cobblemarket.share.title_item" else "cobblemarket.item.sell_title")
                .formatted(Formatting.GOLD),
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
            // ⚠ 图标要**垂直居中于行**：原来写死 +2，行高 24 下上留 2、下留 6，看着就是"偏上"
            //   （`drawItem` 的 y 是图标**左上角**，不是底部 —— 2026-09-20 用户实测指出）
            drawItemWithBar(context, item.stack, leftX + 2, y + (rowHeight - 16) / 2)
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

        // 行数按屏幕高钳制（Shift / Ctrl 展开可能几十行）—— 见 [clampTooltipRows]
        lines = clampTooltipRows(lines, height) { n -> Text.translatable("cobblemarket.gui.tooltip_more", n) }
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
        // 分享模式：**点谁展示谁**（这一模式的列表里没有行内按钮，点行就是唯一操作）。
        // ⚠ 放在 super 之后：让输入框 / 弹窗先有机会消费这次点击，别抢它们的。
        // ⚠ 行号口径与 `renderBackground` 里算 hoveredRow 的那段**逐字一致** ——
        //   两处口径不一样就会出现「点到的行和看到高亮的行不是同一行」。
        if (shareMode && !result && selectedItem == null) {
            val leftX = width / 2 - panelWidth / 2
            val startY = getListStartY()
            if (mouseX >= leftX && mouseX <= leftX + panelWidth && mouseY >= startY) {
                val row = ((mouseY - startY) / rowHeight).toInt()
                if (row < maxVisible() && (scrollOffset + row) < items.size) {
                    // 展示成功由服务端回包触发关闭界面（见 onMarketResult），这里不用自己收尾
                    shareItem(items[scrollOffset + row])
                    return true
                }
            }
        }
        return result
    }

    /** 分享模式：把该物品的槽位号交给服务端（服务端按槽位读自己的背包，归属天然成立） */
    private fun shareItem(item: SellItem) {
        sendToServer(SharePayload(ShareNetwork.KIND_ITEM, item.slot.toString()))
    }

    fun onMarketResult(payload: MarketResultPayload) {
        if (payload.success) {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.GREEN), false)
            // 分享成功即完成：**关掉界面回游戏**（分享的落点是聊天，玩家该直接看到聊天里的那条播报）
            if (shareMode) {
                client?.setScreen(null)
                return
            }
            loadInventory()
            rebuildSellButtons()
        } else {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.RED), false)
        }
    }

    override fun shouldPause() = false
}
