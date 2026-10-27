package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.finance.CardNames
import com.shusheng.cobblemarket.network.RequestServerConfigPayload
import com.shusheng.cobblemarket.network.SaveServerConfigPayload
import com.shusheng.cobblemarket.network.ServerConfigDataPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/**
 * **服务器配置 → 四个市场子界面的共用骨架**（2026-10-25）。
 *
 * 照 [ServerConfigScreen] / [FinanceConfigScreen] 的行模式：
 * 打开时请求快照 → 数字输入框失焦提交、开关即时改本地状态 → 点「保存」一次性提交全部字段。
 * 保存时只提交本界面负责的字段，其余字段从 [ServerConfigScreen.latest] 回填原值
 * （见 [serverConfigPayloadBase]）——服务器配置是整包保存，不能把别处的值冲掉。
 *
 * 子类只需声明 [numDefs] / [toggleDefs]；开关**固定排在第一位**（2026-10-25 用户要求）。
 * 蛋交易这类「打开要二次确认」的开关通过 [guardedToggles] 声明（照搬原蛋交易确认窗）。
 */
abstract class MarketConfigScreenBase(private val screenTitle: Text) : Screen(screenTitle) {

    protected data class NumDef(val labelKey: String, val isInt: Boolean)

    /** 数字配置行：label 词条 → 保存 payload 的字段名（字符串 key 便于回填与快照取值） */
    protected abstract val numDefs: List<Pair<NumDef, String>>

    /** 开关行：label 词条 → 字段名；**第一项必须是本功能的总开关** */
    protected abstract val toggleDefs: List<Pair<String, String>>

    /** 需要二次确认才能打开的开关（目前只有蛋交易） */
    protected open val guardedToggles: Set<String> = emptySet()

    /**
     * 开关联动钩子：子类在本地值 [next] 已写入 [localToggles] 之后被调用，
     * 可以顺手改同组的其他开关（例如拍卖总开关关闭 ⇒ 精灵 / 物品子开关一起关）。
     * 基类随后会统一刷新**全部**开关图标。
     */
    protected open fun onToggleChanged(key: String, next: Boolean) {}

    private val dialogW: Int get() = minOf(360, (width - 40).coerceAtLeast(200))
    private val rowHeight = 24

    private val numFields = mutableMapOf<String, TextFieldWidget>()
    private val toggleButtons = mutableMapOf<String, NineSliceButton>()
    private val resetButtons = mutableMapOf<String, NineSliceButton>()
    protected val localToggles = mutableMapOf<String, Boolean>()
    private var saveButton: NineSliceButton? = null
    private var cancelButton: NineSliceButton? = null
    private var scrollOffset = 0
    private var savedToastUntil = 0L

    private val totalRows get() = numDefs.size + toggleDefs.size

    // ── 二次确认弹窗（照 ServerConfigScreen 的蛋交易模板：3 秒冷静期） ──
    private var confirmOpen = false
    private var confirmOpenedAt = 0L
    private var confirmButton: NineSliceButton? = null
    private var confirmCancelButton: NineSliceButton? = null
    private var confirmKey: String? = null
    private var savedFieldTexts: Map<String, String>? = null
    private var savedToggles: Map<String, Boolean>? = null

    private fun savePendingEdits() {
        savedFieldTexts = numFields.mapValues { it.value.text }
        savedToggles = localToggles.toMap()
    }

    private fun dialogH() = minOf(height - 8, 30 + totalRows * rowHeight + 45)
    private fun dialogY() = height / 2 - dialogH() / 2
    private fun listStartY() = dialogY() + 30
    private fun listAreaH() = dialogH() - 30 - 45
    private fun getMaxVisibleRows() = maxOf(0, listAreaH() / rowHeight)

    override fun init() {
        super.init()
        val dialogX = width / 2 - dialogW / 2
        val startY = listStartY()

        numDefs.forEach { (def, key) ->
            val isDurations = key == "auctionDurations"
            val fieldW = ConfigFieldLayout.configFieldWidth(textRenderer, dialogW, key, def.labelKey)
            val field = TextFieldWidget(
                textRenderer, ConfigFieldLayout.configFieldRight(dialogX, dialogW) - fieldW,
                startY, fieldW, 16, Text.literal("")
            )
            field.setTextPredicate { text ->
                when {
                    isDurations -> text.all { it.isDigit() || it == ',' }
                    def.isInt -> text.all { it.isDigit() }
                    else -> text.all { it.isDigit() || it == '.' }
                }
            }
            field.setMaxLength(if (isDurations) 60 else 10)
            numFields[key] = field
            addSelectableChild(field)
            addDrawableChild(field)
            val resetBtn = NineSliceButton(
                dialogX + dialogW - 10 - 20, startY, 20, 16,
                Text.literal("↺"),
                {
                    // 只填回默认值不提交：点下方「保存」统一生效
                    numFields[key]?.text = resetText(key)
                    numFields[key]?.setCursor(0, false)
                }
            )
            resetButtons[key] = resetBtn
            addDrawableChild(resetBtn)
        }

        toggleDefs.forEach { (_, key) ->
            val btn = NineSliceButton(
                dialogX + dialogW - 10 - 22, startY, 22, 22,
                Text.literal(""),
                {
                    // 乐观 UI：只切本地状态，保存时才提交；受保护开关（蛋交易）开→需确认
                    if (key in guardedToggles && !currentToggleValue(key)) {
                        openConfirmDialog(key)
                    } else {
                        val next = !currentToggleValue(key)
                        localToggles[key] = next
                        onToggleChanged(key, next)
                        // 联动可能改了同组开关 ⇒ 刷新全部图标
                        toggleDefs.forEach { (_, k) -> toggleButtons[k]?.iconLeft = toggleIconFor(k, null) }
                    }
                },
                iconLeft = toggleIcon(key),
                iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            )
            toggleButtons[key] = btn
            addDrawableChild(btn)
        }

        saveButton = NineSliceButton(
            width / 2 - 62, dialogY() + dialogH() - 30, 60, 20,
            Text.translatable("cobblemarket.op.scfg_save"),
            { save() }
        )
        addDrawableChild(saveButton)
        cancelButton = NineSliceButton(
            width / 2 + 2, dialogY() + dialogH() - 30, 60, 20,
            Text.translatable("cobblemarket.op.scfg_cancel"),
            { closeScreen() }
        )
        addDrawableChild(cancelButton)

        rebuildPositions()
        // 确认弹窗关闭 / resize 重建：恢复未提交的编辑，且不重新请求快照
        if (savedFieldTexts != null) {
            savedFieldTexts!!.forEach { (key, text) ->
                numFields[key]?.text = text
                numFields[key]?.setCursor(0, false)
            }
            savedToggles?.let { saved ->
                localToggles.clear()
                localToggles.putAll(saved)
            }
            toggleDefs.forEach { (_, key) -> toggleButtons[key]?.iconLeft = toggleIconFor(key, null) }
            savedFieldTexts = null
            savedToggles = null
        } else {
            sendToServer(RequestServerConfigPayload())
        }
    }

    private fun closeScreen() {
        // 照其他配置子界面（FinanceConfigScreen / PointsConfigScreen）：取消回服务器配置
        client?.setScreen(ServerConfigScreen())
    }

    private fun rebuildPositions() {
        val dialogX = width / 2 - dialogW / 2
        val startY = listStartY()
        var row = 0
        fun placeNumField(def: NumDef, key: String) {
            val y = startY + (row - scrollOffset) * rowHeight
            val field = numFields[key] ?: return
            val visible = !confirmOpen && row in scrollOffset until scrollOffset + getMaxVisibleRows()
            // ⚠ x 与宽度必须和 init 用同一套算法：只改一处会让右缘戳出弹窗（2026-09-22 踩过）
            field.x = ConfigFieldLayout.configFieldX(textRenderer, dialogX, dialogW, key, def.labelKey)
            field.setWidth(ConfigFieldLayout.configFieldWidth(textRenderer, dialogW, key, def.labelKey))
            field.y = y + 4
            field.visible = visible
            val resetBtn = resetButtons[key]
            resetBtn?.x = dialogX + dialogW - 10 - 20
            resetBtn?.y = y + 4
            resetBtn?.visible = visible
            row++
        }
        fun placeToggle(key: String) {
            val y = startY + (row - scrollOffset) * rowHeight
            val btn = toggleButtons[key] ?: return
            btn.x = dialogX + dialogW - 10 - 22
            btn.y = y + 1
            btn.visible = !confirmOpen && row in scrollOffset until scrollOffset + getMaxVisibleRows()
            row++
        }
        toggleDefs.forEach { (_, key) -> placeToggle(key) }
        numDefs.forEach { (def, key) -> placeNumField(def, key) }
        val pageButtonVisible = !confirmOpen
        saveButton?.visible = pageButtonVisible
        cancelButton?.visible = pageButtonVisible
    }

    // ── 快照 → 界面刷新 ──

    /** S2C 快照刷新（[ServerConfigScreen.onConfigData] 按类型转发） */
    fun refreshFrom(payload: ServerConfigDataPayload) {
        numDefs.forEach { (_, key) ->
            val field = numFields[key] ?: return@forEach
            // 聚焦中的输入框不覆盖（用户正在输入）；保存后回发的快照会刷新全部
            if (focused !== field) {
                field.text = snapshotText(key, payload)
                field.setCursor(0, false)
            }
        }
        localToggles.clear()
        toggleDefs.forEach { (_, key) -> toggleButtons[key]?.iconLeft = toggleIconFor(key, payload) }
    }

    private fun snapshotText(key: String, payload: ServerConfigDataPayload?): String {
        if (key == "auctionDurations") return payload?.auctionDurations ?: ""
        val v = numValue(key, payload)
        // 小数用 BigDecimal 明文格式化：Double.toString 对极小值输出科学计数法（1.0E-4），
        // 输入框过滤器只允许数字+小数点，E/- 会被拒导致显示空
        return if (v == v.toLong().toDouble()) v.toLong().toString()
        else java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
    }

    /** 重置按钮的默认值（与 [com.shusheng.cobblemarket.config.CobbleMarketConfig] 默认值一致） */
    private fun resetText(key: String): String = when (key) {
        "auctionDurations" -> "720,1440,2880,4320"
        "pokemonFee", "itemFee", "auctionFee", "buyOrderFee" -> "5.0"
        "maxPokemonListings", "maxItemListings" -> "0"
        "listingDays" -> "14"
        "pendingDays" -> "30"
        "auctionMinBid" -> "100"
        "antiSnipe" -> "120"
        "maxAuctions" -> "3"
        "buyOrderExpiry" -> "3"
        "maxBuyOrders" -> "5"
        else -> "0"
    }

    private fun numValue(key: String, payload: ServerConfigDataPayload?): Double = when (key) {
        "pokemonFee" -> payload?.pokemonFee ?: 5.0
        "maxPokemonListings" -> (payload?.maxPokemonListings ?: 0).toDouble()
        "listingDays" -> (payload?.listingDays ?: 14).toDouble()
        "pendingDays" -> (payload?.pendingDays ?: 30).toDouble()
        "itemFee" -> payload?.itemFee ?: 5.0
        "maxItemListings" -> (payload?.maxItemListings ?: 0).toDouble()
        "auctionFee" -> payload?.auctionFee ?: 5.0
        "auctionMinBid" -> (payload?.auctionMinBid ?: 100).toDouble()
        "antiSnipe" -> (payload?.antiSnipe ?: 120).toDouble()
        "maxAuctions" -> (payload?.maxAuctions ?: 3).toDouble()
        "buyOrderFee" -> payload?.buyOrderFee ?: 5.0
        "buyOrderExpiry" -> (payload?.buyOrderExpiry ?: 3).toDouble()
        "maxBuyOrders" -> (payload?.maxBuyOrders ?: 5).toDouble()
        "pokemonMarketEnabled", "itemMarketEnabled", "auctionEnabled", "buyOrderEnabled", "eggTrading" -> 0.0
        else -> 0.0
    }

    protected fun currentToggleValue(key: String): Boolean {
        // 本地未保存的编辑值优先（否则连续点击算出同一个结果，开关只能点一次）
        localToggles[key]?.let { return it }
        return snapshotToggle(key, ServerConfigScreen.latest)
    }

    private fun snapshotToggle(key: String, p: ServerConfigDataPayload?): Boolean = when (key) {
        "pokemonMarketEnabled" -> p?.pokemonMarketEnabled ?: true
        "itemMarketEnabled" -> p?.itemMarketEnabled ?: true
        "auctionEnabled" -> p?.auctionEnabled ?: true
        "auctionPokemonEnabled" -> p?.auctionPokemonEnabled ?: true
        "auctionItemEnabled" -> p?.auctionItemEnabled ?: true
        "buyOrderEnabled" -> p?.buyOrderEnabled ?: true
        "buyOrderPokemonEnabled" -> p?.buyOrderPokemonEnabled ?: true
        "buyOrderItemEnabled" -> p?.buyOrderItemEnabled ?: true
        "eggTrading" -> p?.eggTrading ?: false
        else -> false
    }

    private fun toggleIcon(key: String): Identifier = toggleIconFor(key, null)

    private fun toggleIconFor(key: String, payload: ServerConfigDataPayload?): Identifier {
        // 优先级：回发快照值 > 本地未保存的编辑值 > 旧快照
        val on = payload?.let { snapshotToggle(key, it) }
            ?: localToggles[key]
            ?: snapshotToggle(key, ServerConfigScreen.latest)
        return Identifier.of(
            "cobblemarket",
            if (on) "textures/gui/button/switch_icon_on.png" else "textures/gui/button/switch_icon_off.png"
        )
    }

    // ── 保存 ──

    /** 保存：本界面字段提交编辑值，其余字段回填快照（服务器整包保存，不能冲掉别处） */
    private fun save() {
        val p = ServerConfigScreen.latest
        var out = serverConfigPayloadBase(p)
        numDefs.forEach { (_, key) ->
            val text = numFields[key]?.text.orEmpty()
            out = when (key) {
                "pokemonFee" -> out.copy(pokemonFee = text.toDoubleOrNull() ?: (p?.pokemonFee ?: 5.0))
                "maxPokemonListings" -> out.copy(maxPokemonListings = text.toIntOrNull() ?: (p?.maxPokemonListings ?: 0))
                "listingDays" -> out.copy(listingDays = text.toIntOrNull() ?: (p?.listingDays ?: 14))
                "pendingDays" -> out.copy(pendingDays = text.toIntOrNull() ?: (p?.pendingDays ?: 30))
                "itemFee" -> out.copy(itemFee = text.toDoubleOrNull() ?: (p?.itemFee ?: 5.0))
                "maxItemListings" -> out.copy(maxItemListings = text.toIntOrNull() ?: (p?.maxItemListings ?: 0))
                "auctionFee" -> out.copy(auctionFee = text.toDoubleOrNull() ?: (p?.auctionFee ?: 5.0))
                "auctionMinBid" -> out.copy(auctionMinBid = text.toIntOrNull() ?: (p?.auctionMinBid ?: 100))
                "antiSnipe" -> out.copy(antiSnipe = text.toIntOrNull() ?: (p?.antiSnipe ?: 120))
                "maxAuctions" -> out.copy(maxAuctions = text.toIntOrNull() ?: (p?.maxAuctions ?: 3))
                "auctionDurations" -> out.copy(auctionDurations = text.ifBlank { p?.auctionDurations ?: "" })
                "buyOrderFee" -> out.copy(buyOrderFee = text.toDoubleOrNull() ?: (p?.buyOrderFee ?: 5.0))
                "buyOrderExpiry" -> out.copy(buyOrderExpiry = text.toIntOrNull() ?: (p?.buyOrderExpiry ?: 3))
                "maxBuyOrders" -> out.copy(maxBuyOrders = text.toIntOrNull() ?: (p?.maxBuyOrders ?: 5))
                else -> out
            }
        }
        toggleDefs.forEach { (_, key) ->
            val v = currentToggleValue(key)
            out = when (key) {
                "pokemonMarketEnabled" -> out.copy(pokemonMarketEnabled = v)
                "itemMarketEnabled" -> out.copy(itemMarketEnabled = v)
                "auctionEnabled" -> out.copy(auctionEnabled = v)
                "auctionPokemonEnabled" -> out.copy(auctionPokemonEnabled = v)
                "auctionItemEnabled" -> out.copy(auctionItemEnabled = v)
                "buyOrderEnabled" -> out.copy(buyOrderEnabled = v)
                "buyOrderPokemonEnabled" -> out.copy(buyOrderPokemonEnabled = v)
                "buyOrderItemEnabled" -> out.copy(buyOrderItemEnabled = v)
                "eggTrading" -> out.copy(eggTrading = v)
                else -> out
            }
        }
        sendToServer(out)
        savedToastUntil = System.currentTimeMillis() + 1500
    }

    // ── 二次确认弹窗（蛋交易） ──

    private fun setControlsVisible(visible: Boolean) {
        numFields.values.forEach { it.visible = visible }
        resetButtons.values.forEach { it.visible = visible }
        toggleButtons.values.forEach { it.visible = visible }
        saveButton?.visible = visible
        cancelButton?.visible = visible
    }

    private fun openConfirmDialog(key: String) {
        confirmOpen = true
        confirmKey = key
        confirmOpenedAt = System.currentTimeMillis()
        savePendingEdits()
        setControlsVisible(false)
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderConfirmBackground(context)
            }
        })
        val centerX = width / 2
        val dialogY = height / 2 - 75
        confirmButton = NineSliceButton(
            centerX - 85, dialogY + 116, 80, 20,
            Text.translatable("cobblemarket.op.egg_confirm_yes"),
            { confirmGuardedToggle() }
        )
        addDrawableChild(confirmButton)
        confirmCancelButton = NineSliceButton(
            centerX + 5, dialogY + 116, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeConfirmDialog() }
        )
        addDrawableChild(confirmCancelButton)
    }

    private fun closeConfirmDialog() {
        confirmOpen = false
        confirmKey = null
        confirmButton = null
        confirmCancelButton = null
        clearChildren()
        init()
    }

    private fun confirmGuardedToggle() {
        // 冷静期内点击：置灰按钮仍可点（dimmed 模式），播 fail 音效提示，不执行
        if (System.currentTimeMillis() - confirmOpenedAt < 3000L) {
            playFailSound()
            return
        }
        // 只切本地状态（保存时才提交）；先更新保存快照再关闭——
        // 否则 init 恢复会用弹窗打开时的旧快照把刚确认的「开」覆盖回关
        confirmKey?.let { localToggles[it] = true }
        savePendingEdits()
        closeConfirmDialog()
    }

    private fun renderConfirmBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogW = minOf(340, width - 40).coerceAtLeast(200)
        val dialogH = 150
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2
        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.op.egg_confirm_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF
        )
    }

    private fun renderConfirmText(context: DrawContext) {
        val centerX = width / 2
        val dialogX = centerX - 140
        val dialogY = height / 2 - 75
        val lines = (1..9).map { i ->
            listOf(
                "cobblemarket.op.egg_l${i}_warn" to 0xFF5555,
                "cobblemarket.op.egg_l${i}_text" to 0xFFFFFF,
            )
        }
        var ty = dialogY + 32
        lines.forEach { line ->
            val segs = line.mapNotNull { (key, color) ->
                val text = Text.translatable(key).string
                if (text.isEmpty()) null else text to color
            }
            if (segs.isEmpty()) return@forEach
            val lineWidth = segs.sumOf { textRenderer.getWidth(it.first) }
            var tx = dialogX + 20 + (240 - lineWidth) / 2
            segs.forEach { (text, color) ->
                context.drawTextWithShadow(textRenderer, text, tx, ty, color)
                tx += textRenderer.getWidth(text)
            }
            ty += 9
        }
    }

    private fun updateConfirmButtons() {
        val cooldownLeft = 3 - (System.currentTimeMillis() - confirmOpenedAt) / 1000
        val canConfirm = cooldownLeft <= 0
        confirmButton?.dimmed = !canConfirm
        confirmButton?.message = if (canConfirm)
            Text.translatable("cobblemarket.op.egg_confirm_yes")
        else
            Text.translatable("cobblemarket.op.egg_confirm_yes_countdown", cooldownLeft)
    }

    // ── 鼠标 / 滚动 ──

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val wasInInput = focused is TextFieldWidget
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !numFields.values.any { it.isMouseOver(mouseX, mouseY) }) {
            focused = null
        }
        if (!result && !confirmOpen && button == 0 && totalRows > getMaxVisibleRows()) {
            ScrollBar.shared.mouseClicked(mouseX, mouseY, scrollOffset)?.let {
                scrollOffset = it
                rebuildPositions()
                return true
            }
        }
        return result
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (!confirmOpen) {
            ScrollBar.shared.mouseDragged(mouseY, scrollOffset)?.let {
                scrollOffset = it
                rebuildPositions()
                return true
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (ScrollBar.shared.mouseReleased()) return true
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (confirmOpen) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        val maxScroll = maxOf(0, totalRows - getMaxVisibleRows())
        if (maxScroll > 0) {
            scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)
            rebuildPositions()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, totalRows - getMaxVisibleRows()))
        val wasConfirmOpen = confirmOpen
        val key = confirmKey
        savePendingEdits()
        super.resize(client, width, height)
        if (wasConfirmOpen && key != null) {
            confirmOpen = false
            openConfirmDialog(key)
        }
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        drawScreenDimMask(context, width, height)
        val dialogX = width / 2 - dialogW / 2
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY(), dialogW, dialogH(), 0, DIALOG_BACKGROUND_TEX_H)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        if (confirmOpen) {
            renderConfirmText(context)
            updateConfirmButtons()
            return
        }
        val centerX = width / 2
        val dialogX = centerX - dialogW / 2
        context.drawCenteredTextWithShadow(
            textRenderer,
            screenTitle.copy().formatted(Formatting.GOLD, Formatting.BOLD),
            centerX, dialogY() + 10, 0xFFFFFF
        )
        val startY = listStartY()
        var row = 0
        fun drawRowLine(rowY: Int) {
            context.fill(dialogX + 6, rowY, dialogX + dialogW - 6, rowY + 1, 0xFF888888.toInt())
        }
        fun drawToggleRow(labelKey: String) {
            if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
                val rowY = startY + (row - scrollOffset) * rowHeight
                drawRowLine(rowY)
                context.drawTextWithShadow(
                    textRenderer,
                    CardNames.label(labelKey),
                    dialogX + 10, rowY + 7, 0xFFFFFF
                )
            }
            row++
        }
        fun drawNumRow(def: NumDef, key: String) {
            if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
                val rowY = startY + (row - scrollOffset) * rowHeight
                drawRowLine(rowY)
                context.drawTextWithShadow(
                    textRenderer,
                    TextUtil.truncateStyled(
                        CardNames.label(def.labelKey),
                        ConfigFieldLayout.configLabelWidth(textRenderer, dialogW, key, def.labelKey)
                    ),
                    dialogX + 10, rowY + 7, 0xFFFFFF
                )
            }
            row++
        }
        toggleDefs.forEach { (labelKey, _) -> drawToggleRow(labelKey) }
        numDefs.forEach { (def, key) -> drawNumRow(def, key) }

        // 底部提示：保存成功 toast（服务端回发快照即视为成功）
        if (System.currentTimeMillis() < savedToastUntil) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.op.scfg_saved"),
                centerX, dialogY() + dialogH() - 42, 0x55FF55
            )
        }
        // 滚动条（共享实例；几何由 draw 记下供拖动换算）
        ScrollBar.shared.draw(
            context, width / 2 + dialogW / 2 - 5, listStartY(), listAreaH(),
            totalRows, getMaxVisibleRows(), scrollOffset
        )
    }

    /** 界面以任何方式离开时放弃可能还在进行中的滚动条拖动（共享单例，不清会带到下个界面） */
    override fun removed() {
        super.removed()
        ScrollBar.shared.cancel()
    }

    override fun shouldPause() = false
}

/**
 * 服务器配置保存包的**底稿**：全部字段回填当前快照（缺快照时用默认值）。
 * 各子界面用 `copy(...)` 覆盖自己负责的字段后再提交 —— 服务器是整包保存，
 * 不回填就会把别处的配置冲回默认值。
 */
fun serverConfigPayloadBase(p: ServerConfigDataPayload?): SaveServerConfigPayload =
    SaveServerConfigPayload(
        pokemonFee = p?.pokemonFee ?: 5.0,
        itemFee = p?.itemFee ?: 5.0,
        maxPokemonListings = p?.maxPokemonListings ?: 0,
        maxItemListings = p?.maxItemListings ?: 0,
        listingDays = p?.listingDays ?: 14,
        pendingDays = p?.pendingDays ?: 30,
        auctionFee = p?.auctionFee ?: 5.0,
        auctionMinBid = p?.auctionMinBid ?: 100,
        antiSnipe = p?.antiSnipe ?: 120,
        maxAuctions = p?.maxAuctions ?: 3,
        buyOrderFee = p?.buyOrderFee ?: 5.0,
        buyOrderExpiry = p?.buyOrderExpiry ?: 3,
        maxBuyOrders = p?.maxBuyOrders ?: 5,
        eggTrading = p?.eggTrading ?: false,
        auctionDurations = p?.auctionDurations ?: "",
        idCardBackground = p?.idCardBackground ?: "",
        financeEnabled = p?.financeEnabled ?: false,
        cashLoanEnabled = p?.cashLoanEnabled ?: true,
        consumerLoanEnabled = p?.consumerLoanEnabled ?: true,
        loanPlans = p?.loanPlans ?: "",
        creditRecent30 = p?.creditRecent30 ?: 0.5,
        creditHistory = p?.creditHistory ?: 0.1,
        creditDebt = p?.creditDebt ?: 0.3,
        creditCooldown = p?.creditCooldown ?: 24L,
        dailyDepositRate = p?.dailyDepositRate ?: 0.0001,
        tradePairWindowDays = p?.tradePairWindowDays ?: 30L,
        tradePairMaxTrades = p?.tradePairMaxTrades ?: 3L,
        purpleCardCount = p?.purpleCardCount ?: 20L,
        purpleCardCreditLimit = p?.purpleCardCreditLimit ?: 1_000_000L,
        purpleCardSelfApply = p?.purpleCardSelfApply ?: false,
        purpleCardApplyAsset = p?.purpleCardApplyAsset ?: 0L,
        purpleCardApplyVolume = p?.purpleCardApplyVolume ?: 0L,
        purpleCardApplyCredit = p?.purpleCardApplyCredit ?: 0L,
        purpleCardApplyDeposit = p?.purpleCardApplyDeposit ?: 0L,
        purpleCardApplyNoOverdue = p?.purpleCardApplyNoOverdue ?: false,
        purpleCardApplySeen = p?.purpleCardApplySeen ?: 0L,
        purpleCardApplyDex = p?.purpleCardApplyDex ?: 0L,
        purpleCardApplyFee = p?.purpleCardApplyFee ?: 0L,
        purpleCardRedoFee = p?.purpleCardRedoFee ?: 0L,
        purpleCardFeeDiscount = p?.purpleCardFeeDiscount ?: 0.0,
        blackCardCount = p?.blackCardCount ?: 5L,
        blackCardCreditLimit = p?.blackCardCreditLimit ?: 5_000_000L,
        blackCardSelfApply = p?.blackCardSelfApply ?: false,
        blackCardApplyAsset = p?.blackCardApplyAsset ?: 0L,
        blackCardApplyVolume = p?.blackCardApplyVolume ?: 0L,
        blackCardApplyDeposit = p?.blackCardApplyDeposit ?: 0L,
        blackCardApplyNoOverdue = p?.blackCardApplyNoOverdue ?: false,
        blackCardApplySeen = p?.blackCardApplySeen ?: 0L,
        blackCardApplyDex = p?.blackCardApplyDex ?: 0L,
        blackCardApplyFee = p?.blackCardApplyFee ?: 0L,
        blackCardRedoFee = p?.blackCardRedoFee ?: 0L,
        blackCardFeeDiscount = p?.blackCardFeeDiscount ?: 0.0,
        creditMin = p?.creditMin ?: 0L,
        creditMax = p?.creditMax ?: 100_000L,
        autoRepayMinBalance = p?.autoRepayMinBalance ?: 1_000L,
        ipDebtLimit = p?.ipDebtLimit ?: 100_000L,
        overdueFeeDouble = p?.overdueFeeDouble ?: 7,
        overdueFreeze = p?.overdueFreeze ?: 14,
        overdueBadDebt = p?.overdueBadDebt ?: 30,
        shareCooldownSeconds = p?.shareCooldownSeconds ?: 10,
        pokemonMarketEnabled = p?.pokemonMarketEnabled ?: true,
        itemMarketEnabled = p?.itemMarketEnabled ?: true,
        auctionEnabled = p?.auctionEnabled ?: true,
        buyOrderEnabled = p?.buyOrderEnabled ?: true,
            auctionPokemonEnabled = p?.auctionPokemonEnabled ?: true,
            auctionItemEnabled = p?.auctionItemEnabled ?: true,
            buyOrderPokemonEnabled = p?.buyOrderPokemonEnabled ?: true,
            buyOrderItemEnabled = p?.buyOrderItemEnabled ?: true,
    )
