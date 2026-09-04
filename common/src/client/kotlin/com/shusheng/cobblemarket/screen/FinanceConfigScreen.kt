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
    // +1 = 喵喵紫卡配置入口行（左标签 + 右「配置」按钮）
    private val totalRows = numDefs.size + toggleDefs.size + 1
    private var cardOpenButton: NineSliceButton? = null

    private fun dialogH() = minOf(height - 8, 30 + totalRows * rowHeight + 34)
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
                    numFields[key]?.text = when (key) {
                        "loanPlans" -> ServerConfigScreen.latest?.loanPlans ?: ""
                        else -> snapshotText(key, null)
                    }
                    save()
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
        rebuildPositions()
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
        "cardCount" -> (payload?.purpleCardCount ?: 20L).toDouble()
        "cardLimit" -> (payload?.purpleCardCreditLimit ?: 1_000_000L).toDouble()
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
        val on = when (key) {
            "financeEnabled" -> p?.financeEnabled ?: false
            "cashLoan" -> p?.cashLoanEnabled ?: true
            "consumerLoan" -> p?.consumerLoanEnabled ?: true
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
    }

    private fun save() {
        val p = ServerConfigScreen.latest
        fun intOr(key: String, fallback: Int): Int = numFields[key]?.text?.toIntOrNull() ?: fallback
        fun doubleOr(key: String, fallback: Double): Double = numFields[key]?.text?.toDoubleOrNull() ?: fallback
        fun longOr(key: String, fallback: Long): Long = numFields[key]?.text?.toLongOrNull() ?: fallback
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
        fun drawNumRow(def: NumDef) {
            if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
                val rowY = startY + (row - scrollOffset) * rowHeight
                drawRowLine(rowY)
                context.drawTextWithShadow(
                    textRenderer,
                    Text.translatable(def.labelKey),
                    dialogX + 10, rowY + 7, 0xFFFFFF
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
        numDefs.forEach { (def, _) -> drawNumRow(def) }
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
        scrollOffset = (scrollOffset - verticalAmount.toInt())
            .coerceIn(0, maxOf(0, totalRows - getMaxVisibleRows()))
        rebuildPositions()
        return true
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        super.resize(client, width, height)
        rebuildPositions()
    }

    override fun shouldPause() = false
}
