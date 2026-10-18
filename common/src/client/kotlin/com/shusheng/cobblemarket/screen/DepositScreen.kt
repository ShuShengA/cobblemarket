package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.BalanceCache
import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.drawAmountLine
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.DepositInfoPayload
import com.shusheng.cobblemarket.network.RequestBalancePayload
import com.shusheng.cobblemarket.network.RequestDepositInfoPayload
import com.shusheng.cobblemarket.network.RequestDepositPayload
import com.shusheng.cobblemarket.network.RequestWithdrawPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.Formatting

/**
 * 存款/取款（活期，批次 7.5）：弹窗式照 LoanScreen。
 * 当前余额/存款余额/累计利息/日利率四行 + 金额输入框 + 存款/取款按钮；
 * 存取成功后服务端回发 DepositInfoPayload 刷新。
 *
 * ⚠ **当前余额（钱包）** 2026-10-18 用户要求补上：部分服主把余额 HUD 关了，
 * 光看这个界面看不出自己有多少钱可以存。数据走全局 [BalanceCache]（HUD 关了也照常缓存）。
 */
class DepositScreen : Screen(Text.translatable("cobblemarket.deposit.title")) {

    // 下限 280（= 1.1.0 的固定宽度）：金额输入框（dialogX+130，宽 130）与取款按钮（dialogX+134，宽 126）
    // 都是**绝对偏移**摆的、不随弹窗缩，右缘到 260 —— 下限 200 时窄屏下会溢出 60px
    //   （2026-09-21 用户报；同批「按屏幕钳制」改动引入）
    // ⚠ 判断某界面下限够不够，看的是**内部元素实际画到哪**，不是"1.1.0 用过多少宽"——
    //   配置类界面（紫电卡/金轮卡/服务器配置）的元素全走 dialogX+dialogW-N，自适应用不着抬下限
    private val dialogW = minOf(340, width - 40).coerceAtLeast(280)
    // 170 → 188：顶部多了一行「当前余额」，其余内容整体下移 18（底部留白维持 30）
    private val dialogH = 188

    private var balance = 0L
    private var interest = 0L
    private var rate = 0.0
    private var infoLoaded = false
    private var walletRequested = false

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
            Text.literal(""),
            { client?.setScreen(MeowthBankScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        ))

        // 金额输入框（只数字；重建后恢复输入）
        amountField = TextFieldWidget(textRenderer, dialogX + 130, dialogY + 106, 130, 16, Text.literal(""))
        amountField?.setMaxLength(10)
        amountField?.setTextPredicate { it.all(Char::isDigit) }
        amountField?.setChangedListener { pendingAmountText = it }
        amountField?.text = pendingAmountText
        addSelectableChild(amountField)
        addDrawableChild(amountField)

        // 存款 / 取款按钮（并排 4px 间隙）
        addDrawableChild(NineSliceButton(
            dialogX + 20, dialogY + 142, 110, 16,
            Text.translatable("cobblemarket.deposit.do_deposit"),
            { sendDeposit() }
        ))
        addDrawableChild(NineSliceButton(
            dialogX + 134, dialogY + 142, 126, 16,
            Text.translatable("cobblemarket.deposit.do_withdraw"),
            { sendWithdraw() }
        ))

        if (!infoLoaded) {
            sendToServer(RequestDepositInfoPayload())
            infoLoaded = true
        }
        // 钱包余额：界面缓存可能还没到（比如直接从别处进来），主动要一次；HUD 关着也照常更新缓存
        if (!walletRequested) {
            sendToServer(RequestBalancePayload())
            walletRequested = true
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
        // ⚠ 弹窗自己的货币图标不受门禁管（同 EntryBadgeRenderer.drawInfoLines 的说明）：
        //   遮罩是这个界面自己画的，不关掉的话弹窗里的单位会被自己的遮罩挡住
        com.shusheng.cobblemarket.client.suppressCurrencyIcons = false
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
    }

    /**
     * 日利率的百分比文本：**最多 6 位小数、去掉尾零**，自实现避开 Locale
     * （`String.format` 在部分语言下会输出逗号小数点）。
     *
     * ⚠ **不能固定两位**：防套利护栏会把利率钳成 `0.00047619`（= 0.047619%），
     * 两位小数会显示成 `0.05%`；而 `0.00047`（= 0.047%）也是 `0.05%` ——
     * 服主看不出自己设的值到底生效没有（2026-10-18 用户报）。
     *
     * 例：`0.00047619 → 0.047619%` / `0.00047 → 0.047%` / `0.0001 → 0.01%` /
     *     `0.0005 → 0.05%` / `0.01 → 1%` / `0 → 0%`
     */
    private fun formatDailyRate(rate: Double): String {
        // 百分比最多 6 位小数 ⇒ 先换成整数（单位为「百万分之一百分比」），全程整数运算
        val units = Math.round(rate.coerceAtLeast(0.0) * 100 * 1_000_000)
        val whole = units / 1_000_000
        val frac = (units % 1_000_000).toString().padStart(6, '0').trimEnd('0')
        return if (frac.isEmpty()) "$whole%" else "$whole.$frac%"
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
        // 余额 / 累计利息：**真金白银**（能取出来花）→ 数字与单位金色、标签白
        // 蓝色只留给服务器给的虚拟授信与债务（口径：「这个数字能不能花」）
        // ⚠ 金额与单位拆开画（物品货币时单位是物品图标）：词条的单位参数传空串占位，见 drawAmountLine
        // 当前余额（钱包）：部分服主把余额 HUD 关了，这里是玩家判断"能存多少"的唯一依据
        drawAmountLine(
            context, textRenderer,
            Text.translatable("cobblemarket.deposit.wallet_line",
                Text.literal(formatPriceLong(BalanceCache.balanceRaw)).formatted(Formatting.GOLD),
                Text.literal("")),
            dialogX + 12, dialogY + 40, 0xFFFFFF, unitColor = 0xFFAA00
        )
        drawAmountLine(
            context, textRenderer,
            Text.translatable("cobblemarket.deposit.balance_line",
                Text.literal(formatPriceLong(balance)).formatted(Formatting.GOLD),
                Text.literal("")),
            dialogX + 12, dialogY + 58, 0xFFFFFF, unitColor = 0xFFAA00
        )
        drawAmountLine(
            context, textRenderer,
            Text.translatable("cobblemarket.deposit.interest_line",
                Text.literal(formatPriceLong(interest)).formatted(Formatting.GOLD),
                Text.literal("")),
            dialogX + 12, dialogY + 76, 0xFFFFFF, unitColor = 0xFFAA00
        )
        // 日利率（自适应精度：0.00047619 → 0.047619%；不能固定两位 —— 见 formatDailyRate）
        val ratePercent = formatDailyRate(rate)
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.deposit.rate_line", ratePercent),
            dialogX + 12, dialogY + 94, 0x888888
        )
        // 金额输入标签
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.deposit.amount_label"),
            dialogX + 12, dialogY + 110, 0xFFFFFF
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
