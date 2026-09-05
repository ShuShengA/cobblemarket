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
 * 自行申请黑卡条件配置界面（照紫卡条件配置界面）：六项门槛 + 申请费（0 = 不要求）。
 * 「持有紫卡」为硬条件不可配置，不在此界面。保存时条件字段提交编辑值、其余回填快照。
 */
class BlackCardApplyConditionsScreen : Screen(Text.translatable("cobblemarket.op.black_card_conditions")) {

    private val dialogW = 260
    private val rowHeight = 24

    private data class NumDef(val labelKey: String, val isInt: Boolean)

    private val numDefs = listOf(
        NumDef("cobblemarket.op.scfg_apply_asset", true) to "applyAsset",
        NumDef("cobblemarket.op.scfg_apply_volume", true) to "applyVolume",
        NumDef("cobblemarket.op.scfg_apply_credit", true) to "applyCredit",
        NumDef("cobblemarket.op.scfg_apply_deposit", true) to "applyDeposit",
        NumDef("cobblemarket.op.scfg_apply_dex", true) to "applyDex",
        NumDef("cobblemarket.op.scfg_apply_fee", true) to "applyFee",
    )

    private val toggleDefs = listOf(
        "cobblemarket.op.scfg_apply_no_overdue" to "applyNoOverdue",
    )

    private val numFields = mutableMapOf<String, TextFieldWidget>()
    private val resetButtons = mutableMapOf<String, NineSliceButton>()
    private val toggleButtons = mutableMapOf<String, NineSliceButton>()
    private val localToggles = mutableMapOf<String, Boolean>()
    private var saveButton: NineSliceButton? = null
    private var cancelButton: NineSliceButton? = null
    private var scrollOffset = 0
    private var savedToastUntil = 0L
    private val totalRows = numDefs.size + toggleDefs.size

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
            // 口径解释（服主向）
            val tipKey = when (key) {
                "applyAsset" -> "cobblemarket.op.scfg_apply_asset_tip"
                "applyVolume" -> "cobblemarket.op.scfg_apply_volume_tip"
                "applyCredit" -> "cobblemarket.op.scfg_apply_credit_tip"
                "applyDeposit" -> "cobblemarket.op.scfg_apply_deposit_tip"
                "applyDex" -> "cobblemarket.op.scfg_apply_dex_tip"
                "applyFee" -> "cobblemarket.op.scfg_apply_fee_tip"
                else -> null
            }
            if (tipKey != null) {
                field.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable(tipKey)))
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
            { client?.setScreen(BlackCardConfigScreen()) }
        )
        addDrawableChild(cancelButton)
        rebuildPositions()
        sendToServer(RequestServerConfigPayload())
    }

    private fun snapshotText(key: String, payload: ServerConfigDataPayload?): String {
        val v = numValue(key, payload)
        return if (v == v.toLong().toDouble()) v.toLong().toString()
        else java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
    }

    private fun numValue(key: String, payload: ServerConfigDataPayload?): Double = when (key) {
        "applyAsset" -> (payload?.blackCardApplyAsset ?: 0L).toDouble()
        "applyVolume" -> (payload?.blackCardApplyVolume ?: 0L).toDouble()
        "applyCredit" -> (payload?.blackCardApplyCredit ?: 0L).toDouble()
        "applyDeposit" -> (payload?.blackCardApplyDeposit ?: 0L).toDouble()
        "applyDex" -> (payload?.blackCardApplyDex ?: 0L).toDouble()
        "applyFee" -> (payload?.blackCardApplyFee ?: 0L).toDouble()
        else -> 0.0
    }

    private fun toggleIcon(key: String): net.minecraft.util.Identifier? = toggleIconFor(key, null)

    private fun toggleIconFor(key: String, p: ServerConfigDataPayload?): net.minecraft.util.Identifier? {
        // 优先级：回发快照值 > 本地未保存的编辑值 > 旧快照（照 ServerConfigScreen）
        val on = p?.let { snapshot ->
            when (key) {
                "applyNoOverdue" -> snapshot.blackCardApplyNoOverdue
                else -> false
            }
        } ?: localToggles[key] ?: when (key) {
            "applyNoOverdue" -> ServerConfigScreen.latest?.blackCardApplyNoOverdue ?: false
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
            "applyNoOverdue" -> localToggles["applyNoOverdue"] ?: (p?.blackCardApplyNoOverdue ?: false)
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
    }

    private fun save() {
        val p = ServerConfigScreen.latest
        fun longOr(key: String, fallback: Long): Long = numFields[key]?.text?.toLongOrNull() ?: fallback
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
            // 紫卡字段全部回填快照（本界面不编辑）
            purpleCardCount = p?.purpleCardCount ?: 20L,
            purpleCardCreditLimit = p?.purpleCardCreditLimit ?: 1_000_000L,
            purpleCardSelfApply = p?.purpleCardSelfApply ?: false,
            purpleCardApplyAsset = p?.purpleCardApplyAsset ?: 0L,
            purpleCardApplyVolume = p?.purpleCardApplyVolume ?: 0L,
            purpleCardApplyCredit = p?.purpleCardApplyCredit ?: 0L,
            purpleCardApplyDeposit = p?.purpleCardApplyDeposit ?: 0L,
            purpleCardApplyNoOverdue = p?.purpleCardApplyNoOverdue ?: false,
            purpleCardApplyDex = p?.purpleCardApplyDex ?: 0L,
            purpleCardApplyFee = p?.purpleCardApplyFee ?: 0L,
            purpleCardRedoFee = p?.purpleCardRedoFee ?: 0L,
            purpleCardFeeDiscount = p?.purpleCardFeeDiscount ?: 0.0,
            // 黑卡字段：条件提交编辑值，其余回填快照
            blackCardCount = p?.blackCardCount ?: 5L,
            blackCardCreditLimit = p?.blackCardCreditLimit ?: 5_000_000L,
            blackCardSelfApply = p?.blackCardSelfApply ?: false,
            blackCardApplyAsset = longOr("applyAsset", p?.blackCardApplyAsset ?: 0L),
            blackCardApplyVolume = longOr("applyVolume", p?.blackCardApplyVolume ?: 0L),
            blackCardApplyCredit = longOr("applyCredit", p?.blackCardApplyCredit ?: 0L),
            blackCardApplyDeposit = longOr("applyDeposit", p?.blackCardApplyDeposit ?: 0L),
            blackCardApplyNoOverdue = localToggles["applyNoOverdue"] ?: (p?.blackCardApplyNoOverdue ?: false),
            blackCardApplyDex = longOr("applyDex", p?.blackCardApplyDex ?: 0L),
            blackCardApplyFee = longOr("applyFee", p?.blackCardApplyFee ?: 0L),
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
            val visible = row in scrollOffset until scrollOffset + getMaxVisibleRows()
            field.x = dialogX + dialogW - 10 - 20 - 2 - 54
            field.y = y + 4
            field.visible = visible
            resetButtons[key]?.x = dialogX + dialogW - 10 - 20
            resetButtons[key]?.y = y + 4
            resetButtons[key]?.visible = visible
            row++
        }
        toggleDefs.forEach { (_, key) ->
            val y = startY + (row - scrollOffset) * rowHeight
            val btn = toggleButtons[key] ?: return@forEach
            btn.x = dialogX + dialogW - 10 - 22
            btn.y = y + 1
            btn.visible = row in scrollOffset until scrollOffset + getMaxVisibleRows()
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
        val centerX = width / 2
        val dialogX = width / 2 - dialogW / 2
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.op.black_card_conditions").formatted(Formatting.GOLD, Formatting.BOLD),
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
