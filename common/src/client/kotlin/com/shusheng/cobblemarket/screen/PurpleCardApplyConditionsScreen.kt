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
 * 自行申请紫卡条件配置界面（批次 7.5）：资产/消费金额/额度三项门槛（0 = 不要求）。
 * 照 PurpleCardConfigScreen 模式；保存时条件字段提交编辑值、其余回填快照。
 */
class PurpleCardApplyConditionsScreen : Screen(Text.translatable("cobblemarket.op.card_conditions")) {

    private val dialogW = 260
    private val rowHeight = 24

    private data class NumDef(val labelKey: String, val isInt: Boolean)

    private val numDefs = listOf(
        NumDef("cobblemarket.op.scfg_apply_asset", true) to "applyAsset",
        NumDef("cobblemarket.op.scfg_apply_volume", true) to "applyVolume",
        NumDef("cobblemarket.op.scfg_apply_credit", true) to "applyCredit",
    )

    private val numFields = mutableMapOf<String, TextFieldWidget>()
    private val resetButtons = mutableMapOf<String, NineSliceButton>()
    private var saveButton: NineSliceButton? = null
    private var cancelButton: NineSliceButton? = null
    private var scrollOffset = 0
    private var savedToastUntil = 0L
    private val totalRows = numDefs.size

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
            val field = TextFieldWidget(textRenderer, dialogX + dialogW - 10 - 20 - 2 - 54, startY, 54, 16, Text.literal(""))
            field.setTextPredicate { text -> if (def.isInt) text.all { it.isDigit() } else text.all { it.isDigit() || it == '.' } }
            field.setMaxLength(10)
            // 口径解释（服主向）
            when (key) {
                "applyAsset" -> field.setTooltip(
                    net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.op.scfg_apply_asset_tip"))
                )
                "applyVolume" -> field.setTooltip(
                    net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.op.scfg_apply_volume_tip"))
                )
                "applyCredit" -> field.setTooltip(
                    net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.op.scfg_apply_credit_tip"))
                )
            }
            numFields[key] = field
            addSelectableChild(field)
            addDrawableChild(field)
            val resetBtn = NineSliceButton(
                dialogX + dialogW - 10 - 20, startY, 20, 16,
                Text.literal("↺"),
                {
                    numFields[key]?.text = snapshotText(key, null)
                    save()
                }
            )
            resetButtons[key] = resetBtn
            addDrawableChild(resetBtn)
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
            { client?.setScreen(PurpleCardConfigScreen()) }
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
        "applyAsset" -> (payload?.purpleCardApplyAsset ?: 0L).toDouble()
        "applyVolume" -> (payload?.purpleCardApplyVolume ?: 0L).toDouble()
        "applyCredit" -> (payload?.purpleCardApplyCredit ?: 0L).toDouble()
        else -> 0.0
    }

    fun refreshFrom(payload: ServerConfigDataPayload) {
        numDefs.forEach { (_, key) ->
            val field = numFields[key] ?: return@forEach
            if (focused !== field) {
                field.text = snapshotText(key, payload)
            }
        }
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
            purpleCardCount = p?.purpleCardCount ?: 20L,
            purpleCardCreditLimit = p?.purpleCardCreditLimit ?: 1_000_000L,
            purpleCardSelfApply = p?.purpleCardSelfApply ?: false,
            purpleCardApplyAsset = longOr("applyAsset", p?.purpleCardApplyAsset ?: 0L),
            purpleCardApplyVolume = longOr("applyVolume", p?.purpleCardApplyVolume ?: 0L),
            purpleCardApplyCredit = longOr("applyCredit", p?.purpleCardApplyCredit ?: 0L),
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
            Text.translatable("cobblemarket.op.card_conditions").formatted(Formatting.GOLD, Formatting.BOLD),
            centerX, dialogY() + 10, 0xFFFFFF
        )
        val startY = listStartY()
        numDefs.forEachIndexed { i, (def, _) ->
            val rowY = startY + i * rowHeight
            context.fill(dialogX + 6, rowY, dialogX + dialogW - 6, rowY + 1, 0xFF555555.toInt())
            context.drawTextWithShadow(
                textRenderer,
                Text.translatable(def.labelKey),
                dialogX + 10, rowY + 7, 0xFFFFFF
            )
        }
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
