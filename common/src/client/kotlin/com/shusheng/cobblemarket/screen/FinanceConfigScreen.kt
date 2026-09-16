package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.network.RequestServerConfigPayload
import com.shusheng.cobblemarket.network.SaveServerConfigPayload
import com.shusheng.cobblemarket.network.ServerConfigDataPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * 喵喵银行配置界面（批次 7.5 拆分）：服务器配置界面只留入口按钮，全部金融配置移到这里。
 * 照 ServerConfigScreen 的行模式；保存时金融字段提交编辑值、其余字段回填服务端快照原值。
 */
class FinanceConfigScreen : Screen(Text.translatable("cobblemarket.op.finance_config")) {

    /** 贷款方案默认文本（与 CobbleMarketConfig.loanPlans 默认值一致，重置按钮用） */
    private val defaultLoanPlansText = "3:0.005,6:0.008,12:0.012"

    private val dialogW = 260
    private val rowHeight = 24

    private data class NumDef(val labelKey: String, val isInt: Boolean)

    private val numDefs = listOf(
        NumDef("cobblemarket.op.scfg_loan_plans", false) to "loanPlans",
        NumDef("cobblemarket.op.scfg_credit_recent30", false) to "creditRecent30",
        NumDef("cobblemarket.op.scfg_credit_history", false) to "creditHistory",
        NumDef("cobblemarket.op.scfg_credit_min", true) to "creditMin",
        NumDef("cobblemarket.op.scfg_credit_max", true) to "creditMax",
        NumDef("cobblemarket.op.scfg_credit_cooldown", true) to "creditCooldown",
        NumDef("cobblemarket.op.scfg_deposit_rate", false) to "depositRate",
        NumDef("cobblemarket.op.scfg_pair_window", true) to "pairWindow",
        NumDef("cobblemarket.op.scfg_pair_max", true) to "pairMax",
        NumDef("cobblemarket.op.scfg_ip_debt_limit", true) to "ipDebtLimit",
        NumDef("cobblemarket.op.scfg_min_balance", true) to "autoRepayMinBalance",
        NumDef("cobblemarket.op.scfg_overdue_fee_double", true) to "overdueFeeDouble",
        NumDef("cobblemarket.op.scfg_overdue_freeze", true) to "overdueFreeze",
        NumDef("cobblemarket.op.scfg_overdue_bad_debt", true) to "overdueBadDebt",
    )

    private val toggleDefs = listOf(
        "cobblemarket.op.scfg_finance_enabled" to "financeEnabled",
        "cobblemarket.op.scfg_cash_loan" to "cashLoan",
        "cobblemarket.op.scfg_consumer_loan" to "consumerLoan",
    )

    private val numFields = mutableMapOf<String, TextFieldWidget>()
    private val toggleButtons = mutableMapOf<String, NineSliceButton>()
    private val resetButtons = mutableMapOf<String, NineSliceButton>()
    private val localToggles = mutableMapOf<String, Boolean>()
    private var saveButton: NineSliceButton? = null
    private var cancelButton: NineSliceButton? = null
    private var scrollOffset = 0
    private var savedToastUntil = 0L
    /** 本次保存提交的数值字段解析值（key → Double；解析失败 = null 不参与对比；loanPlans 文本单独存） */
    private var submittedNums: Map<String, Double?> = emptyMap()
    /** 本次保存提交的贷款方案原始文本（对比服务器解析规范化后的回发文本） */
    private var submittedPlansText: String? = null
    /** 保存后被服务器调整的字段 → 黄色标记到期时间戳（8 秒行标签变色，无文案零宽度风险） */
    private val adjustedUntil = mutableMapOf<String, Long>()
    // +2 = 喵喵紫卡/黑卡配置入口行（左标签 + 右「配置」按钮）
    private val totalRows = numDefs.size + toggleDefs.size + 2
    private var cardOpenButton: NineSliceButton? = null
    private var blackCardOpenButton: NineSliceButton? = null

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
            val isPlans = key == "loanPlans"
            val field = TextFieldWidget(textRenderer, dialogX + dialogW - 10 - 20 - 2 - 54, startY, 54, 16, Text.literal(""))
            field.setTextPredicate { text ->
                when {
                    isPlans -> text.all { it.isDigit() || it == ':' || it == '.' || it == ',' }
                    def.isInt -> text.all { it.isDigit() }
                    else -> text.all { it.isDigit() || it == '.' }
                }
            }
            field.setMaxLength(if (isPlans) 60 else 10)
            when (key) {
                "pairWindow", "pairMax" -> field.setTooltip(
                    net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.op.scfg_pair_tip"))
                )
                "creditCooldown" -> field.setTooltip(
                    net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.op.scfg_cooldown_tip"))
                )
                "depositRate" -> field.setTooltip(
                    net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.op.scfg_deposit_rate_tip"))
                )
            }
            numFields[key] = field
            addSelectableChild(field)
            addDrawableChild(field)
            val resetBtn = NineSliceButton(
                dialogX + dialogW - 10 - 20, startY, 20, 16,
                Text.literal("↺"),
                {
                    // 只填回默认值不提交：多项重置（如贷款方案+存款日利率）点下方「保存」统一生效，
                    // 避免单点重置立即保存时被护栏按「新值+旧方案」的中间态钳制（免息方案会把利率钳成 0）
                    numFields[key]?.text = when (key) {
                        // 方案重置 = 恢复默认方案（快照值可能已被服主改过，重置回快照等于没变）
                        "loanPlans" -> defaultLoanPlansText
                        else -> snapshotText(key, null)
                    }
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
                    localToggles[key] = !currentToggleValue(key)
                    toggleButtons[key]?.iconLeft = toggleIconFor(key, null)
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
            { client?.setScreen(ServerConfigScreen()) }
        )
        addDrawableChild(cancelButton)
        // 喵喵紫卡配置入口行（列表末尾：标签「喵喵紫卡」+ 右侧「配置」按钮 → PurpleCardConfigScreen）
        cardOpenButton = NineSliceButton(
            dialogX + dialogW - 10 - 20 - 2 - 54, startY, 54, 16,
            Text.translatable("cobblemarket.op.finance_open"),
            { client?.setScreen(PurpleCardConfigScreen()) }
        )
        addDrawableChild(cardOpenButton)
        // 喵喵黑卡配置入口行（紫卡入口行下方 → BlackCardConfigScreen）
        blackCardOpenButton = NineSliceButton(
            dialogX + dialogW - 10 - 20 - 2 - 54, startY, 54, 16,
            Text.translatable("cobblemarket.op.finance_open"),
            { client?.setScreen(BlackCardConfigScreen()) }
        )
        addDrawableChild(blackCardOpenButton)
        rebuildPositions()
        submittedNums = emptyMap()
        submittedPlansText = null
        adjustedUntil.clear()
        sendToServer(RequestServerConfigPayload())
    }

    private fun snapshotText(key: String, payload: ServerConfigDataPayload?): String {
        val v = numValue(key, payload)
        return if (v == v.toLong().toDouble()) v.toLong().toString()
        else java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
    }

    private fun numValue(key: String, payload: ServerConfigDataPayload?): Double = when (key) {
        "loanPlans" -> 0.0 // 文本型，special-case
        "creditRecent30" -> payload?.creditRecent30 ?: 0.5
        "creditHistory" -> payload?.creditHistory ?: 0.1
        "creditMin" -> (payload?.creditMin ?: 0L).toDouble()
        "creditMax" -> (payload?.creditMax ?: 100_000L).toDouble()
        "creditCooldown" -> (payload?.creditCooldown ?: 24L).toDouble()
        "depositRate" -> payload?.dailyDepositRate ?: 0.0001
        "pairWindow" -> (payload?.tradePairWindowDays ?: 30L).toDouble()
        "pairMax" -> (payload?.tradePairMaxTrades ?: 3L).toDouble()
        "ipDebtLimit" -> (payload?.ipDebtLimit ?: 100_000L).toDouble()
        "autoRepayMinBalance" -> (payload?.autoRepayMinBalance ?: 1_000L).toDouble()
        "overdueFeeDouble" -> (payload?.overdueFeeDouble ?: 7).toDouble()
        "overdueFreeze" -> (payload?.overdueFreeze ?: 14).toDouble()
        "overdueBadDebt" -> (payload?.overdueBadDebt ?: 30).toDouble()
        else -> 0.0
    }

    private fun toggleIcon(key: String): net.minecraft.util.Identifier? =
        toggleIconFor(key, null)

    private fun toggleIconFor(key: String, p: ServerConfigDataPayload?): net.minecraft.util.Identifier? {
        // 优先级：回发快照值 > 本地未保存的编辑值 > 旧快照（照 ServerConfigScreen）
        val on = p?.let { snapshot ->
            when (key) {
                "financeEnabled" -> snapshot.financeEnabled
                "cashLoan" -> snapshot.cashLoanEnabled
                "consumerLoan" -> snapshot.consumerLoanEnabled
                else -> false
            }
        } ?: localToggles[key] ?: when (key) {
            "financeEnabled" -> ServerConfigScreen.latest?.financeEnabled ?: false
            "cashLoan" -> ServerConfigScreen.latest?.cashLoanEnabled ?: true
            "consumerLoan" -> ServerConfigScreen.latest?.consumerLoanEnabled ?: true
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
            "financeEnabled" -> localToggles["financeEnabled"] ?: (p?.financeEnabled ?: false)
            "cashLoan" -> localToggles["cashLoan"] ?: (p?.cashLoanEnabled ?: true)
            "consumerLoan" -> localToggles["consumerLoan"] ?: (p?.consumerLoanEnabled ?: true)
            else -> false
        }
    }

    /** S2C 快照刷新（CobbleMarketClient 转发） */
    fun refreshFrom(payload: ServerConfigDataPayload) {
        numDefs.forEach { (_, key) ->
            val field = numFields[key] ?: return@forEach
            if (focused !== field) {
                field.text = when (key) {
                    "loanPlans" -> payload.loanPlans
                    else -> snapshotText(key, payload)
                }
            }
        }
        toggleDefs.forEach { (_, key) ->
            toggleButtons[key]?.iconLeft = toggleIconFor(key, payload)
        }
        localToggles.clear()
        // 统一调整对比：提交值 ≠ 回发值 → 该字段行标签黄 8 秒（贷款方案/权重/额度上限/交易对/日利率等所有钳制场景统一覆盖）
        val now = System.currentTimeMillis()
        val plansSubmitted = submittedPlansText
        if (plansSubmitted != null && plansSubmitted != payload.loanPlans) {
            adjustedUntil["loanPlans"] = now + 8000
        }
        submittedNums.forEach { (key, submitted) ->
            if (submitted != null && submitted != numValue(key, payload)) {
                adjustedUntil[key] = now + 8000
            }
        }
        submittedPlansText = null
        submittedNums = emptyMap()
        // 日利率被钳制时额外掐掉绿 toast（底部黄字说明原因，优先显示）
        if (adjustedUntil.containsKey("depositRate")) {
            savedToastUntil = 0L
        }
    }

    private fun save() {
        val p = ServerConfigScreen.latest
        fun intOr(key: String, fallback: Int): Int = numFields[key]?.text?.toIntOrNull() ?: fallback
        fun doubleOr(key: String, fallback: Double): Double = numFields[key]?.text?.toDoubleOrNull() ?: fallback
        fun longOr(key: String, fallback: Long): Long = numFields[key]?.text?.toLongOrNull() ?: fallback
        // 记录全部数值字段提交解析值 + 方案原始文本（回发时逐字段对比，被服务器调整的字段行标签变黄）
        submittedNums = numDefs.map { (_, key) ->
            key to if (key == "loanPlans") null else numFields[key]?.text?.toDoubleOrNull()
        }.toMap()
        submittedPlansText = numFields["loanPlans"]?.text
        // 点保存视为读完上次的调整标记（本次提交若再被调整，回发时重新标记）
        adjustedUntil.clear()
        sendToServer(SaveServerConfigPayload(
            // 非金融字段全部回填快照原值（本界面不编辑）
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
            // 金融字段提交编辑值
            financeEnabled = localToggles["financeEnabled"] ?: (p?.financeEnabled ?: false),
            cashLoanEnabled = localToggles["cashLoan"] ?: (p?.cashLoanEnabled ?: true),
            consumerLoanEnabled = localToggles["consumerLoan"] ?: (p?.consumerLoanEnabled ?: true),
            loanPlans = numFields["loanPlans"]?.text.orEmpty().ifBlank { p?.loanPlans ?: "" },
            creditRecent30 = doubleOr("creditRecent30", p?.creditRecent30 ?: 0.5),
            creditHistory = doubleOr("creditHistory", p?.creditHistory ?: 0.1),
            creditDebt = p?.creditDebt ?: 0.3,
            creditMin = longOr("creditMin", p?.creditMin ?: 0L),
            creditMax = longOr("creditMax", p?.creditMax ?: 100_000L),
            creditCooldown = longOr("creditCooldown", p?.creditCooldown ?: 24L),
            dailyDepositRate = doubleOr("depositRate", p?.dailyDepositRate ?: 0.0001),
            tradePairWindowDays = longOr("pairWindow", p?.tradePairWindowDays ?: 30L),
            tradePairMaxTrades = longOr("pairMax", p?.tradePairMaxTrades ?: 3L),
            // 紫卡字段回填快照（已迁移到 PurpleCardConfigScreen 编辑）
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
            ipDebtLimit = longOr("ipDebtLimit", p?.ipDebtLimit ?: 100_000L),
            autoRepayMinBalance = longOr("autoRepayMinBalance", p?.autoRepayMinBalance ?: 1_000L),
            overdueFeeDouble = intOr("overdueFeeDouble", p?.overdueFeeDouble ?: 7),
            overdueFreeze = intOr("overdueFreeze", p?.overdueFreeze ?: 14),
            overdueBadDebt = intOr("overdueBadDebt", p?.overdueBadDebt ?: 30),
        ))
        savedToastUntil = System.currentTimeMillis() + 1500
    }

    private fun rebuildPositions() {
        val dialogX = width / 2 - dialogW / 2
        val startY = listStartY()
        var row = 0
        fun placeNumField(key: String) {
            val y = startY + (row - scrollOffset) * rowHeight
            val field = numFields[key] ?: return
            val visible = row in scrollOffset until scrollOffset + getMaxVisibleRows()
            field.x = dialogX + dialogW - 10 - 20 - 2 - 54
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
            btn.visible = row in scrollOffset until scrollOffset + getMaxVisibleRows()
            row++
        }
        numDefs.forEach { (_, key) -> placeNumField(key) }
        toggleDefs.forEach { (_, key) -> placeToggle(key) }
        // 喵喵紫卡配置入口行（列表末尾）
        val y = startY + (row - scrollOffset) * rowHeight
        val visible = row in scrollOffset until scrollOffset + getMaxVisibleRows()
        cardOpenButton?.x = dialogX + dialogW - 10 - 20 - 2 - 54
        cardOpenButton?.y = y + 4
        cardOpenButton?.visible = visible
        // 喵喵黑卡配置入口行（紫卡入口行下方）
        val blackY = startY + (row + 1 - scrollOffset) * rowHeight
        val blackVisible = row + 1 in scrollOffset until scrollOffset + getMaxVisibleRows()
        blackCardOpenButton?.x = dialogX + dialogW - 10 - 20 - 2 - 54
        blackCardOpenButton?.y = blackY + 4
        blackCardOpenButton?.visible = blackVisible
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val dialogX = width / 2 - dialogW / 2
        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY(), dialogW, dialogH(), 0, DIALOG_BACKGROUND_TEX_H)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val centerX = width / 2
        val dialogX = width / 2 - dialogW / 2
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.op.finance_config").formatted(Formatting.GOLD, Formatting.BOLD),
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
                    Text.translatable(def.labelKey),
                    dialogX + 10, rowY + 7, labelColor
                )
            }
            row++
        }
        fun drawToggleRow(labelKey: String) {
            if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
                val rowY = startY + (row - scrollOffset) * rowHeight
                drawRowLine(rowY)
                context.drawTextWithShadow(
                    textRenderer,
                    Text.translatable(labelKey),
                    dialogX + 10, rowY + 7, 0xFFFFFF
                )
            }
            row++
        }
        numDefs.forEach { (def, key) -> drawNumRow(def, key) }
        toggleDefs.forEach { (labelKey, _) -> drawToggleRow(labelKey) }
        // 喵喵紫卡配置入口行（列表末尾）
        if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
            val rowY = startY + (row - scrollOffset) * rowHeight
            drawRowLine(rowY)
            context.drawTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.op.card_entry"),
                dialogX + 10, rowY + 7, 0xFFFFFF
            )
        }
        row++
        // 喵喵黑卡配置入口行
        if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
            val rowY = startY + (row - scrollOffset) * rowHeight
            drawRowLine(rowY)
            context.drawTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.op.black_card_entry"),
                dialogX + 10, rowY + 7, 0xFFFFFF
            )
        }
        row++
        if (System.currentTimeMillis() < savedToastUntil) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.op.scfg_saved").formatted(Formatting.GREEN),
                centerX, dialogY() + dialogH() - 38, 0xFFFFFF
            )
        } else if (System.currentTimeMillis() < (adjustedUntil["depositRate"] ?: 0L)) {
            // 日利率钳制说明（8 秒后回落灰 hint）：服主看到「重置后保存又变 0」时知道是贷款方案护栏干的
            val clampedText = snapshotText("depositRate", ServerConfigScreen.latest)
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.op.scfg_deposit_clamped", clampedText).formatted(Formatting.YELLOW),
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
        super.resize(client, width, height)
        rebuildPositions()
    }

    override fun shouldPause() = false
}

