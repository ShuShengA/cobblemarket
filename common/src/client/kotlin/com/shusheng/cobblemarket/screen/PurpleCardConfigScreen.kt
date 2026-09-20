package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.RequestServerConfigPayload
import com.shusheng.cobblemarket.network.SaveServerConfigPayload
import com.shusheng.cobblemarket.network.ServerConfigDataPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.util.Formatting

/**
 * 喵喵紫卡配置界面（批次 7.5 拆分）：从喵喵银行配置界面拆出紫卡相关配置。
 * 保存时紫卡字段提交编辑值、其余字段回填服务端快照原值（照 FinanceConfigScreen 模式）。
 */
class PurpleCardConfigScreen : Screen(Text.translatable("cobblemarket.op.card_config")) {

    private val dialogW = 260
    private val rowHeight = 24

    private data class NumDef(val labelKey: String, val isInt: Boolean)

    private val numDefs = listOf(
        NumDef("cobblemarket.op.scfg_card_count", true) to "cardCount",
        NumDef("cobblemarket.op.scfg_card_limit", true) to "cardLimit",
        NumDef("cobblemarket.op.scfg_card_redo_fee", true) to "cardRedoFee",
        NumDef("cobblemarket.op.scfg_card_fee_discount", false) to "cardFeeDiscount",
    )

    private val toggleDefs = listOf(
        "cobblemarket.op.scfg_card_self_apply" to "cardSelfApply",
    )

    private val numFields = mutableMapOf<String, TextFieldWidget>()
    private val resetButtons = mutableMapOf<String, NineSliceButton>()
    private val toggleButtons = mutableMapOf<String, NineSliceButton>()
    private val localToggles = mutableMapOf<String, Boolean>()
    private var saveButton: NineSliceButton? = null
    private var cancelButton: NineSliceButton? = null
    private var scrollOffset = 0
    private var savedToastUntil = 0L
    /** 本次保存提交的数值字段解析值（key → Double；解析失败 = null 不参与对比） */
    private var submittedNums: Map<String, Double?> = emptyMap()
    /** 保存后被服务器调整的字段 → 黄色标记到期时间戳（8 秒行标签变色） */
    private val adjustedUntil = mutableMapOf<String, Long>()
    // +1 = 自行申请条件入口行（左标签 + 右「配置」按钮）
    private val totalRows = numDefs.size + toggleDefs.size + 1
    private var conditionsOpenButton: NineSliceButton? = null
    // 自行申请开关确认弹窗（照蛋交易开关黄金模板：关→开需 5 秒冷静期；防条件未配就开导致无条件申请泛滥）
    private var selfApplyConfirmOpen = false
    private var selfApplyConfirmOpenedAt = 0L
    private var selfApplyConfirmButton: NineSliceButton? = null
    private var selfApplyCancelButton: NineSliceButton? = null
    // 弹窗关闭重建恢复区（照蛋交易：重建后不请求快照，否则快照回发把本地开关状态冲回服务端旧值）
    private var savedFieldTexts: Map<String, String>? = null
    private var savedToggles: Map<String, Boolean>? = null

    private fun dialogH() = minOf(height - 8, 30 + totalRows * rowHeight + 44)
    private fun dialogY() = height / 2 - dialogH() / 2
    private fun listStartY() = dialogY() + 30
    private fun listAreaH() = dialogH() - 30 - 34
    private fun getMaxVisibleRows() = maxOf(0, listAreaH() / rowHeight)

    override fun init() {
        super.init()
        val dialogX = width / 2 - dialogW / 2
        val startY = listStartY()
        numDefs.forEach { (def, key) ->
            val field = TextFieldWidget(textRenderer, dialogX + dialogW - 10 - 20 - 2 - 54, startY, 54, 16, Text.literal(""))
            field.setTextPredicate { text -> if (def.isInt) text.all { it.isDigit() } else text.all { it.isDigit() || it == '.' } }
            field.setMaxLength(10)
            if (key == "cardFeeDiscount") {
                field.setTooltip(
                    net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.op.scfg_card_fee_discount_tip"))
                )
            }
            numFields[key] = field
            addSelectableChild(field)
            addDrawableChild(field)
            val resetBtn = NineSliceButton(
                dialogX + dialogW - 10 - 20, startY, 20, 16,
                Text.literal("↺"),
                {
                    // 只填回默认值不提交：点下方「保存」统一生效（2026-09-05 拍板，重置按钮一律不自动保存）
                    numFields[key]?.text = snapshotText(key, null)
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
                    // 关→开走确认弹窗（5 秒冷静期）；开→关直接切
                    if (key == "cardSelfApply" && !currentToggleValue(key)) {
                        openSelfApplyConfirmDialog()
                    } else {
                        localToggles[key] = !currentToggleValue(key)
                        toggleButtons[key]?.iconLeft = toggleIconFor(key, null)
                    }
                },
                iconLeft = toggleIcon(key),
                iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
                texture = ROW_BACKGROUND_TEXTURE,
                texH = ROW_BACKGROUND_TEX_H
            )
            toggleButtons[key] = btn
            addDrawableChild(btn)
        }
        saveButton = NineSliceButton(
            width / 2 - 62, dialogY() + dialogH() - 26, 60, 20,
            Text.translatable("cobblemarket.op.scfg_save"),
            { save() }
        )
        addDrawableChild(saveButton)
        cancelButton = NineSliceButton(
            width / 2 + 2, dialogY() + dialogH() - 26, 60, 20,
            Text.translatable("cobblemarket.op.scfg_cancel"),
            { client?.setScreen(FinanceConfigScreen()) }
        )
        addDrawableChild(cancelButton)
        // 自行申请条件入口行（列表末尾：标签 + 右侧「配置」按钮 → PurpleCardApplyConditionsScreen）
        conditionsOpenButton = NineSliceButton(
            dialogX + dialogW - 10 - 20 - 2 - 54, startY, 54, 16,
            Text.translatable("cobblemarket.op.finance_open"),
            { client?.setScreen(PurpleCardApplyConditionsScreen()) }
        )
        addDrawableChild(conditionsOpenButton)
        rebuildPositions()
        // 确认弹窗关闭走 clearChildren+init 重建：恢复未提交的编辑，且不重新请求快照
        // （请求会把本地编辑的开关状态冲回服务端旧值——确认后按钮图标变回关就是这个原因）
        if (savedFieldTexts != null) {
            savedFieldTexts!!.forEach { (key, text) -> numFields[key]?.text = text }
            savedToggles?.let { saved -> localToggles.clear(); localToggles.putAll(saved) }
            toggleDefs.forEach { (_, key) -> toggleButtons[key]?.iconLeft = toggleIconFor(key, null) }
            savedFieldTexts = null
            savedToggles = null
        } else {
            // 首次打开/普通重建：请求快照（服务端回发后 refreshFrom 填值）
            submittedNums = emptyMap()
            adjustedUntil.clear()
            sendToServer(RequestServerConfigPayload())
        }
    }

    private fun snapshotText(key: String, payload: ServerConfigDataPayload?): String {
        val v = numValue(key, payload)
        return if (v == v.toLong().toDouble()) v.toLong().toString()
        else java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
    }

    private fun numValue(key: String, payload: ServerConfigDataPayload?): Double = when (key) {
        "cardCount" -> (payload?.purpleCardCount ?: 20L).toDouble()
        "cardLimit" -> (payload?.purpleCardCreditLimit ?: 1_000_000L).toDouble()
        "cardRedoFee" -> (payload?.purpleCardRedoFee ?: 0L).toDouble()
        "cardFeeDiscount" -> payload?.purpleCardFeeDiscount ?: 0.0
        else -> 0.0
    }

    private fun toggleIcon(key: String): net.minecraft.util.Identifier? = toggleIconFor(key, null)

    private fun toggleIconFor(key: String, p: ServerConfigDataPayload?): net.minecraft.util.Identifier? {
        // 优先级：回发快照值 > 本地未保存的编辑值 > 旧快照（照 ServerConfigScreen）
        val on = p?.let { snapshot ->
            when (key) {
                "cardSelfApply" -> snapshot.purpleCardSelfApply
                else -> false
            }
        } ?: localToggles[key] ?: when (key) {
            "cardSelfApply" -> ServerConfigScreen.latest?.purpleCardSelfApply ?: false
            else -> false
        }
        return if (on)
            net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/switch_icon_on.png")
        else
            net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/switch_icon_off.png")
    }

    private fun currentToggleValue(key: String): Boolean {
        val p = ServerConfigScreen.latest
        return when (key) {
            "cardSelfApply" -> localToggles["cardSelfApply"] ?: (p?.purpleCardSelfApply ?: false)
            else -> false
        }
    }

    fun refreshFrom(payload: ServerConfigDataPayload) {
        numDefs.forEach { (_, key) ->
            val field = numFields[key] ?: return@forEach
            if (focused !== field) {
                field.text = snapshotText(key, payload)
            }
        }
        toggleDefs.forEach { (_, key) ->
            toggleButtons[key]?.iconLeft = toggleIconFor(key, payload)
        }
        localToggles.clear()
        // 统一调整对比：提交值 ≠ 回发值 → 该字段行标签黄 8 秒（如手续费减免超 1 钳 1）
        val now = System.currentTimeMillis()
        submittedNums.forEach { (key, submitted) ->
            if (submitted != null && submitted != numValue(key, payload)) {
                adjustedUntil[key] = now + 8000
            }
        }
        submittedNums = emptyMap()
    }

    private fun save() {
        val p = ServerConfigScreen.latest
        fun longOr(key: String, fallback: Long): Long = numFields[key]?.text?.toLongOrNull() ?: fallback
        fun doubleOr(key: String, fallback: Double): Double = numFields[key]?.text?.toDoubleOrNull() ?: fallback
        // 记录数值字段提交解析值（回发时对比，被服务器调整的字段行标签变黄）；点保存视为读完上次标记
        submittedNums = numDefs.map { (_, key) -> key to numFields[key]?.text?.toDoubleOrNull() }.toMap()
        adjustedUntil.clear()
        sendToServer(SaveServerConfigPayload(
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
            celebration = p?.celebration ?: true,
            auctionDurations = p?.auctionDurations ?: "",
            financeEnabled = p?.financeEnabled ?: false,
            cashLoanEnabled = p?.cashLoanEnabled ?: true,
            consumerLoanEnabled = p?.consumerLoanEnabled ?: true,
            loanPlans = p?.loanPlans ?: "",
            creditRecent30 = p?.creditRecent30 ?: 0.5,
            creditHistory = p?.creditHistory ?: 0.1,
            creditDebt = p?.creditDebt ?: 0.3,
            creditMin = p?.creditMin ?: 0L,
            creditMax = p?.creditMax ?: 100_000L,
            creditCooldown = p?.creditCooldown ?: 24L,
            dailyDepositRate = p?.dailyDepositRate ?: 0.0001,
            tradePairWindowDays = p?.tradePairWindowDays ?: 30L,
            tradePairMaxTrades = p?.tradePairMaxTrades ?: 3L,
            purpleCardCount = longOr("cardCount", p?.purpleCardCount ?: 20L),
            purpleCardCreditLimit = longOr("cardLimit", p?.purpleCardCreditLimit ?: 1_000_000L),
            purpleCardSelfApply = localToggles["cardSelfApply"] ?: (p?.purpleCardSelfApply ?: false),
            purpleCardApplyAsset = p?.purpleCardApplyAsset ?: 0L,
            purpleCardApplyVolume = p?.purpleCardApplyVolume ?: 0L,
            purpleCardApplyCredit = p?.purpleCardApplyCredit ?: 0L,
            purpleCardApplyDeposit = p?.purpleCardApplyDeposit ?: 0L,
            purpleCardApplyNoOverdue = p?.purpleCardApplyNoOverdue ?: false,
            purpleCardApplySeen = p?.purpleCardApplySeen ?: 0L,
            purpleCardApplyDex = p?.purpleCardApplyDex ?: 0L,
            purpleCardApplyFee = p?.purpleCardApplyFee ?: 0L,
            purpleCardRedoFee = longOr("cardRedoFee", p?.purpleCardRedoFee ?: 0L),
            purpleCardFeeDiscount = doubleOr("cardFeeDiscount", p?.purpleCardFeeDiscount ?: 0.0),
            // 黑卡字段回填快照（在 BlackCardConfigScreen 编辑）
            blackCardCount = p?.blackCardCount ?: 5L,
            blackCardCreditLimit = p?.blackCardCreditLimit ?: 5_000_000L,
            blackCardSelfApply = p?.blackCardSelfApply ?: false,
            blackCardApplyAsset = p?.blackCardApplyAsset ?: 0L,
            blackCardApplyVolume = p?.blackCardApplyVolume ?: 0L,
            blackCardApplyCredit = p?.blackCardApplyCredit ?: 0L,
            blackCardApplyDeposit = p?.blackCardApplyDeposit ?: 0L,
            blackCardApplyNoOverdue = p?.blackCardApplyNoOverdue ?: false,
            blackCardApplySeen = p?.blackCardApplySeen ?: 0L,
            blackCardApplyDex = p?.blackCardApplyDex ?: 0L,
            blackCardApplyFee = p?.blackCardApplyFee ?: 0L,
            blackCardRedoFee = p?.blackCardRedoFee ?: 0L,
            blackCardFeeDiscount = p?.blackCardFeeDiscount ?: 0.0,
            ipDebtLimit = p?.ipDebtLimit ?: 100_000L,
            autoRepayMinBalance = p?.autoRepayMinBalance ?: 1_000L,
            overdueFeeDouble = p?.overdueFeeDouble ?: 7,
            overdueFreeze = p?.overdueFreeze ?: 14,
            overdueBadDebt = p?.overdueBadDebt ?: 30,
        ))
        savedToastUntil = System.currentTimeMillis() + 1500
    }

    private fun rebuildPositions() {
        val dialogX = width / 2 - dialogW / 2
        val startY = listStartY()
        var row = 0
        numDefs.forEach { (_, key) ->
            val y = startY + (row - scrollOffset) * rowHeight
            val field = numFields[key] ?: return@forEach
            // visible 必须叠加 !selfApplyConfirmOpen：确认弹窗打开时任何重建（滚动/resize）都不能把下层控件改回可见
            val visible = !selfApplyConfirmOpen && row in scrollOffset until scrollOffset + getMaxVisibleRows()
            field.x = dialogX + dialogW - 10 - 20 - 2 - 54
            field.y = y + 4
            field.visible = visible
            resetButtons[key]?.x = dialogX + dialogW - 10 - 20
            resetButtons[key]?.y = y + 4
            resetButtons[key]?.visible = visible
            row++
        }
        // 自行申请条件入口行（开关之前：让服主先注意到条件配置）
        val y = startY + (row - scrollOffset) * rowHeight
        val visible = !selfApplyConfirmOpen && row in scrollOffset until scrollOffset + getMaxVisibleRows()
        conditionsOpenButton?.x = dialogX + dialogW - 10 - 20 - 2 - 54
        conditionsOpenButton?.y = y + 4
        conditionsOpenButton?.visible = visible
        row++
        // 「允许自行申请」开关（列表最后一项）
        toggleDefs.forEach { (_, key) ->
            val ty = startY + (row - scrollOffset) * rowHeight
            val btn = toggleButtons[key] ?: return@forEach
            btn.x = dialogX + dialogW - 10 - 22
            btn.y = ty + 1
            btn.visible = !selfApplyConfirmOpen && row in scrollOffset until scrollOffset + getMaxVisibleRows()
            row++
        }
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val dialogX = width / 2 - dialogW / 2
        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY(), dialogW, dialogH(), 0, DIALOG_BACKGROUND_TEX_H)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        // 确认弹窗打开：只画弹窗（照蛋交易开关模板），下层行文字不渲染
        if (selfApplyConfirmOpen) {
            renderSelfApplyConfirmText(context)
            updateSelfApplyConfirmButtons()
            return
        }
        val centerX = width / 2
        val dialogX = width / 2 - dialogW / 2
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.op.card_config").formatted(Formatting.GOLD, Formatting.BOLD),
            centerX, dialogY() + 10, 0xFFFFFF
        )
        val startY = listStartY()
        var row = 0
        fun drawRowLine(rowY: Int) {
            context.fill(dialogX + 6, rowY, dialogX + dialogW - 6, rowY + 1, 0xFF555555.toInt())
        }
        fun drawNumRow(def: NumDef, key: String) {
            if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
                val rowY = startY + (row - scrollOffset) * rowHeight
                drawRowLine(rowY)
                // 保存后被服务器调整的字段：标签黄色 8 秒（无文案，中英零宽度风险）
                val labelColor = if (System.currentTimeMillis() < (adjustedUntil[key] ?: 0L)) 0xFFFF55 else 0xFFFFFF
                context.drawTextWithShadow(
                    textRenderer,
                    // ⚠ 标签**必须截到输入框左缘之前**：原先直接画、没有任何宽度限制，英文长标签
                    //   会一路压到输入框上、把里面的默认值盖住（2026-09-20 用户实测截图）。
                    //   可用宽度从 dialogW 反算 —— 与输入框位置（`dialogX + dialogW - 86`）同一口径，
                    //   弹窗变宽时自动跟着走。中文本来就短，不会触发截断
                    TextUtil.truncateString(Text.translatable(def.labelKey).string, dialogW - 100),
                    dialogX + 10, rowY + 7, labelColor
                )
            }
            row++
        }
        fun drawToggleRow(labelKey: String) {
            if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
                val rowY = startY + (row - scrollOffset) * rowHeight
                drawRowLine(rowY)
                // 文案含 | 时拆两行渲染（英文长句超宽；单行 y+7，两行 y+3/y+13）
                val lines = Text.translatable(labelKey).string.split("|")
                if (lines.size > 1) {
                    lines.take(2).forEachIndexed { i, l ->
                        context.drawTextWithShadow(textRenderer, l, dialogX + 10, rowY + 3 + i * 10, 0xFFFFFF)
                    }
                } else {
                    context.drawTextWithShadow(
                        textRenderer,
                        lines[0],
                        dialogX + 10, rowY + 7, 0xFFFFFF
                    )
                }
            }
            row++
        }
        numDefs.forEach { (def, key) -> drawNumRow(def, key) }
        // 自行申请条件入口行（开关之前：让服主先注意到条件配置）
        if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
            val condRowY = startY + (row - scrollOffset) * rowHeight
            drawRowLine(condRowY)
            context.drawTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.op.card_conditions_entry"),
                dialogX + 10, condRowY + 7, 0xFFFFFF
            )
        }
        row++
        // 「允许自行申请」开关（列表最后一项）
        toggleDefs.forEach { (labelKey, _) -> drawToggleRow(labelKey) }
        if (System.currentTimeMillis() < savedToastUntil) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.op.scfg_saved").formatted(Formatting.GREEN),
                centerX, dialogY() + dialogH() - 38, 0xFFFFFF
            )
        } else {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.op.scfg_hint").formatted(Formatting.GRAY),
                centerX, dialogY() + dialogH() - 38, 0xFFFFFF
            )
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        // 确认弹窗打开时不滚动下层列表
        if (selfApplyConfirmOpen) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        scrollOffset = (scrollOffset - verticalAmount.toInt())
            .coerceIn(0, maxOf(0, totalRows - getMaxVisibleRows()))
        rebuildPositions()
        return true
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 点击空白处结束输入状态（照 LoanScreen 惯例）
        val wasInInput = focused is TextFieldWidget
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && numFields.values.none { it.isMouseOver(mouseX, mouseY) }) {
            focused = null
        }
        return result
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val wasConfirmOpen = selfApplyConfirmOpen
        // 重建前保存未提交编辑（照蛋交易模板；super.resize 可能触发控件重建）
        savePendingEdits()
        super.resize(client, width, height)
        if (wasConfirmOpen) {
            selfApplyConfirmOpen = false
            openSelfApplyConfirmDialog()
        }
    }

    override fun shouldPause() = false

    // ── 自行申请开关确认弹窗（照蛋交易开关黄金模板：5 秒冷静期 + 红白分段说明） ──

    private fun setControlsVisible(visible: Boolean) {
        numFields.values.forEach { it.visible = visible }
        resetButtons.values.forEach { it.visible = visible }
        toggleButtons.values.forEach { it.visible = visible }
        saveButton?.visible = visible
        cancelButton?.visible = visible
        conditionsOpenButton?.visible = visible
    }

    private fun openSelfApplyConfirmDialog() {
        selfApplyConfirmOpen = true
        selfApplyConfirmOpenedAt = System.currentTimeMillis()
        // 保存未提交的编辑（关闭弹窗走 clearChildren+init 重建，init 里恢复）
        savePendingEdits()
        setControlsVisible(false)

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderSelfApplyConfirmBackground(context)
            }
        })

        val centerX = width / 2
        val dialogY = height / 2 - 75
        selfApplyConfirmButton = NineSliceButton(
            centerX - 85, dialogY + 116, 80, 20,
            Text.translatable("cobblemarket.op.egg_confirm_yes"),
            { confirmSelfApply() }
        )
        addDrawableChild(selfApplyConfirmButton)
        selfApplyCancelButton = NineSliceButton(
            centerX + 5, dialogY + 116, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeSelfApplyConfirmDialog() }
        )
        addDrawableChild(selfApplyCancelButton)
    }

    private fun closeSelfApplyConfirmDialog() {
        selfApplyConfirmOpen = false
        selfApplyConfirmButton = null
        selfApplyCancelButton = null
        clearChildren()
        init()
    }

    private fun savePendingEdits() {
        savedFieldTexts = numFields.mapValues { it.value.text }
        savedToggles = localToggles.toMap()
    }

    private fun confirmSelfApply() {
        // 冷静期内点击：置灰按钮仍可点（dimmed 模式），播 fail 音效提示，不执行
        if (System.currentTimeMillis() - selfApplyConfirmOpenedAt < 5000L) {
            playFailSound()
            return
        }
        // 只切本地状态（保存时才提交）；确认后立即重存恢复区——
        // close 走 clearChildren+init 重建，init 用恢复区还原本地状态，不重存会把刚确认的「开」覆盖回关（照蛋交易模板）
        localToggles["cardSelfApply"] = true
        savePendingEdits()
        closeSelfApplyConfirmDialog()
    }

    private fun renderSelfApplyConfirmBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogW = 280
        val dialogH = 150
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.op.self_apply_confirm_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)
    }

    private fun renderSelfApplyConfirmText(context: DrawContext) {
        val centerX = width / 2
        val dialogX = centerX - 140
        val dialogY = height / 2 - 75

        // 逐行渲染：语言文件显式分行（每行红/白两个槽位），红=警告、白=普通；空行跳过（中英行数不同）
        val lines = (1..9).map { i ->
            listOf(
                "cobblemarket.op.self_apply_l${i}_warn" to 0xFF5555,
                "cobblemarket.op.self_apply_l${i}_text" to 0xFFFFFF,
            )
        }
        var ty = dialogY + 36
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
            ty += 10
        }
    }

    /** 冷静期：5 秒内确认按钮禁用并显示倒计时 */
    private fun updateSelfApplyConfirmButtons() {
        val cooldownLeft = 5 - (System.currentTimeMillis() - selfApplyConfirmOpenedAt) / 1000
        val canConfirm = cooldownLeft <= 0
        selfApplyConfirmButton?.dimmed = !canConfirm
        selfApplyConfirmButton?.message = if (canConfirm)
            Text.translatable("cobblemarket.op.egg_confirm_yes")
        else
            Text.translatable("cobblemarket.op.egg_confirm_yes_countdown", cooldownLeft)
    }
}

