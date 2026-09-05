package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.DepositInfoPayload
import com.shusheng.cobblemarket.network.RequestDepositInfoPayload
import com.shusheng.cobblemarket.network.RequestDepositPayload
import com.shusheng.cobblemarket.network.RequestWithdrawPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * 存款/取款（活期，批次 7.5）：弹窗式照 LoanScreen。
 * 余额/累计利息/日利率三行 + 金额输入框 + 存款/取款按钮；存取成功后服务端回发 DepositInfoPayload 刷新。
 */
class DepositScreen : Screen(Text.translatable("cobblemarket.deposit.title")) {

    private val dialogW = 280
    private val dialogH = 170

    private var balance = 0L
    private var interest = 0L
    private var rate = 0.0
    private var infoLoaded = false

    private var amountField: TextFieldWidget? = null
    /** 输入内容暂存：init 重建（resize）后恢复 */
    private var pendingAmountText = ""

    override fun init() {
        super.init()
        val dialogX = width / 2 - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        // 返回按钮：右上角内移 8px（照 LoanScreen）
        addDrawableChild(NineSliceButton(
            dialogX + dialogW - 58, dialogY + 8, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(MeowthBankScreen()) }
        ))

        // 金额输入框（只数字；重建后恢复输入）
        amountField = TextFieldWidget(textRenderer, dialogX + 130, dialogY + 88, 130, 16, Text.literal(""))
        amountField?.setMaxLength(10)
        amountField?.setTextPredicate { it.all(Char::isDigit) }
        amountField?.setChangedListener { pendingAmountText = it }
        amountField?.text = pendingAmountText
        addSelectableChild(amountField)
        addDrawableChild(amountField)

        // 存款 / 取款按钮（并排 4px 间隙）
        addDrawableChild(NineSliceButton(
            dialogX + 20, dialogY + 124, 110, 16,
            Text.translatable("cobblemarket.deposit.do_deposit"),
            { sendDeposit() }
        ))
        addDrawableChild(NineSliceButton(
            dialogX + 134, dialogY + 124, 126, 16,
            Text.translatable("cobblemarket.deposit.do_withdraw"),
            { sendWithdraw() }
        ))

        if (!infoLoaded) {
            sendToServer(RequestDepositInfoPayload())
            infoLoaded = true
        }
    }

    private fun sendDeposit() {
        val amount = amountField?.text?.toLongOrNull() ?: 0L
        if (amount <= 0) {
            playFailSound()
            return
        }
        sendToServer(RequestDepositPayload(amount))
        amountField?.text = ""
        pendingAmountText = ""
    }

    private fun sendWithdraw() {
        val amount = amountField?.text?.toLongOrNull() ?: 0L
        // 金额非法 / 超过存款余额（快照实算含未结算利息）：本地拦截 + fail 音效，服务端仍会复核
        if (amount <= 0 || amount > balance) {
            playFailSound()
            return
        }
        sendToServer(RequestWithdrawPayload(amount))
        amountField?.text = ""
        pendingAmountText = ""
    }

    /** 存款信息快照（进入时拉取/存取成功后回发刷新） */
    fun onDepositInfo(payload: DepositInfoPayload) {
        balance = payload.balance
        interest = payload.interest
        rate = payload.rate
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val dialogX = width / 2 - dialogW / 2
        val dialogY = height / 2 - dialogH / 2
        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val dialogX = width / 2 - dialogW / 2
        val dialogY = height / 2 - dialogH / 2
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.deposit.title").formatted(Formatting.GOLD),
            width / 2, dialogY + 14, 0xFFFFFF
        )
        // 余额 / 累计利息（价格+货币名蓝色）
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.deposit.balance_line", formatPriceLong(balance), inlineCurrencyUnit()),
            dialogX + 12, dialogY + 40, 0x55FFFF
        )
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.deposit.interest_line", formatPriceLong(interest), inlineCurrencyUnit()),
            dialogX + 12, dialogY + 58, 0x55FFFF
        )
        // 日利率（0.0001 → 0.01%）
        val ratePercent = String.format("%.2f%%", rate * 100)
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.deposit.rate_line", ratePercent),
            dialogX + 12, dialogY + 76, 0x888888
        )
        // 金额输入标签
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.deposit.amount_label"),
            dialogX + 12, dialogY + 92, 0xFFFFFF
        )
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 点击空白处结束输入状态（照 LoanScreen）
        val wasInInput = focused is TextFieldWidget
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && amountField?.isMouseOver(mouseX, mouseY) != true) {
            focused = null
        }
        return result
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        super.resize(client, width, height)
    }

    override fun shouldPause() = false
}
