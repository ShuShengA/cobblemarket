package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.client.requestCreditInfo
import com.shusheng.cobblemarket.network.CreditInfoPayload
import com.shusheng.cobblemarket.network.RequestLoanPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.Formatting
import kotlin.math.roundToLong

/**
 * 应急贷款（借呗）：弹窗式界面照拍卖出价界面模式。
 * 顶部可用额度/当前欠款 → 金额输入框 → 3 档方案按钮（默认第一档）→ 申请 → 确认弹窗（黄金模板）。
 * 打开时拉取额度信息；借款成功后服务端回发额度快照，界面即时刷新可用额度。
 */
class LoanScreen : Screen(Text.translatable("cobblemarket.loan.title")) {

    private val dialogW = minOf(340, width - 40).coerceAtLeast(200)
    private val dialogH = 200

    // 初始读全局缓存（60 秒兜底轮询写入）秒显不闪；-1 = 未拉取，响应到达后更新
    private var limit = com.shusheng.cobblemarket.client.FinanceCache.creditLimit
    private var debt = com.shusheng.cobblemarket.client.FinanceCache.creditDebt
    private var hasOverdue = false
    private var hasBadDebt = false
    private var infoLoaded = false
    private var plans = listOf<Pair<Int, Double>>()
    private var selectedPlan = 0

    private var amountField: TextFieldWidget? = null
    /** 输入内容暂存：init 重建（resize/关确认弹窗）后恢复，避免输入丢失 */
    private var pendingAmountText = ""
    private val planButtons = mutableListOf<NineSliceButton>()
    private var applyButton: NineSliceButton? = null
    private var backButton: NineSliceButton? = null

    // ── 确认弹窗（黄金模板：visible 隐藏下层 + Drawable 背景 + 按钮 + resize 重建） ──
    private var confirmOpen = false
    private var confirmAmount = 0L
    private var confirmConfirmButton: NineSliceButton? = null
    private var confirmCancelButton: NineSliceButton? = null

    override fun init() {
        super.init()
        val dialogX = width / 2 - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        // 返回按钮：右上角内移 8px 避开弹窗九宫格边框（6px 边框 + 2px 空隙）
        backButton = NineSliceButton(
            dialogX + dialogW - 58, dialogY + 8, 50, 16,
            Text.literal(""),
            { client?.setScreen(MeowthBankScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        // 金额输入框（只数字；右对齐照 ServerConfigScreen 输入框惯例）；
        // text 每次重建后从 pendingAmountText 恢复，setChangedListener 同步（防 resize/关弹窗丢输入）
        amountField = TextFieldWidget(textRenderer, dialogX + 130, dialogY + 86, 130, 16, Text.literal(""))
        amountField?.setMaxLength(10)
        amountField?.setTextPredicate { it.all(Char::isDigit) }
        amountField?.setChangedListener { pendingAmountText = it }
        amountField?.text = pendingAmountText
        addSelectableChild(amountField)
        addDrawableChild(amountField)

        rebuildPlanButtons(dialogX, dialogY)

        applyButton = NineSliceButton(
            dialogX + 90, dialogY + 130, 100, 16,
            Text.translatable("cobblemarket.loan.apply"),
            {
                val amount = amountField?.text?.toLongOrNull() ?: 0L
                // 金额非法 / 逾期 / 坏账：本地拦截 + fail 音效（逾期/坏账时界面已显示红色提示行）
                if (amount <= 0 || hasBadDebt || hasOverdue) {
                    playFailSound()
                } else {
                    openConfirmDialog(amount)
                }
            }
        )
        addDrawableChild(applyButton)

        if (!infoLoaded) {
            requestCreditInfo()
            infoLoaded = true
        }
    }

    /** 方案按钮行：并排间距 4px，选中档前缀 ▶ */
    private fun rebuildPlanButtons(dialogX: Int, dialogY: Int) {
        planButtons.forEach(::remove)
        planButtons.clear()
        val btnW = 84
        plans.forEachIndexed { i, (periods, fee) ->
            val btn = NineSliceButton(
                dialogX + 10 + i * (btnW + 4), dialogY + 110, btnW, 16,
                Text.literal(planLabel(periods, fee, i == selectedPlan)),
                {
                    selectedPlan = i
                    refreshPlanLabels()
                }
            )
            planButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    private fun refreshPlanLabels() {
        plans.forEachIndexed { i, (periods, fee) ->
            planButtons.getOrNull(i)?.setMessage(Text.literal(planLabel(periods, fee, i == selectedPlan)))
        }
    }

    /** 方案按钮文案：「▶3期·0.5%」（费率一位小数百分比，选中加 ▶） */
    private fun planLabel(periods: Int, feeRate: Double, selected: Boolean): String {
        val base = Text.translatable("cobblemarket.loan.plan_btn", periods, planPercentText(feeRate)).string
        return if (selected) "▶$base" else base
    }

    /** 费率百分比文本（0.005 → 0.5%；截断一位小数，自实现避开 Locale） */
    private fun planPercentText(feeRate: Double): String {
        val tenths = (feeRate * 1000).roundToLong()
        return if (tenths % 10 == 0L) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"
    }

    /** 额度信息快照（打开时拉取 / 借款成功后服务端回发刷新） */
    fun onCreditInfo(payload: CreditInfoPayload) {
        limit = payload.limit
        debt = payload.debt
        hasOverdue = payload.hasOverdue
        hasBadDebt = payload.hasBadDebt
        val parsed = payload.plans.split(',')
            .mapNotNull { part ->
                val seg = part.trim().split(':')
                if (seg.size != 2) return@mapNotNull null
                val p = seg[0].trim().toIntOrNull()?.coerceAtLeast(1)
                val r = seg[1].trim().toDoubleOrNull()?.coerceIn(0.0, 1.0)
                if (p != null && r != null) p to r else null
            }
        if (parsed.isNotEmpty() && parsed != plans) {
            plans = parsed
            selectedPlan = selectedPlan.coerceIn(0, plans.size - 1)
            rebuildPlanButtons(width / 2 - dialogW / 2, height / 2 - dialogH / 2)
        }
    }

    // ── 确认弹窗（黄金模板照 AdminAuctionScreen.openCancelDialog） ──

    private fun openConfirmDialog(amount: Long) {
        confirmAmount = amount
        confirmOpen = true
        // 隐藏下层控件（弹窗打开期间不可交互；closeConfirmDialog 的 init 重建会恢复）；
        // 输入框必须失焦：隐藏后仍聚焦会吃掉 E 键退出和后续键盘输入
        amountField?.setFocused(false)
        amountField?.visible = false
        planButtons.forEach { it.visible = false }
        applyButton?.visible = false
        backButton?.visible = false

        val centerX = width / 2
        val dialogY = height / 2 - 90

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染，照搬取消弹窗）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderConfirmBackground(context)
            }
        })

        confirmConfirmButton = NineSliceButton(
            centerX - 50, dialogY + 144, 56, 20,
            Text.translatable("cobblemarket.loan.confirm_btn"),
            { confirmLoan() }
        )
        addDrawableChild(confirmConfirmButton)
        confirmCancelButton = NineSliceButton(
            centerX + 14, dialogY + 144, 56, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeConfirmDialog() }
        )
        addDrawableChild(confirmCancelButton)
    }

    private fun closeConfirmDialog() {
        confirmOpen = false
        confirmConfirmButton = null
        confirmCancelButton = null
        clearChildren()
        init()
    }

    private fun confirmLoan() {
        if (plans.getOrNull(selectedPlan) == null) return
        sendToServer(RequestLoanPayload(confirmAmount, selectedPlan))
        closeConfirmDialog()
    }

    private fun renderConfirmBackground(context: DrawContext) {
        val centerX = width / 2
        val dW = 280
        val dH = 180
        val dialogX = centerX - dW / 2
        val dialogY = height / 2 - dH / 2
        val (periods, feeRate) = plans.getOrNull(selectedPlan) ?: return

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dW, dH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan.confirm_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF
        )

        // 估算利息（等额本金：剩余本金逐期递减，各期剩余之和 = P×(n+1)/2；Double 实算四舍五入）
        val estInterest = Math.round(confirmAmount * feeRate * (periods + 1) / 2.0)
        val lineX = dialogX + 12
        var ly = dialogY + 42
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan.confirm_amount", formatPriceLong(confirmAmount), inlineCurrencyUnit()),
            lineX, ly, 0xFFFFFF
        )
        ly += 18
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan.confirm_plan", periods, planPercentText(feeRate)),
            lineX, ly, 0xFFFFFF
        )
        ly += 18
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable(
                "cobblemarket.loan.confirm_each",
                formatPriceLong(confirmAmount / periods), inlineCurrencyUnit()
            ),
            lineX, ly, 0xFFFFFF
        )
        ly += 18
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable(
                "cobblemarket.loan.confirm_total",
                formatPriceLong(confirmAmount + estInterest), inlineCurrencyUnit()
            ),
            lineX, ly, 0x55FFFF
        )
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 点击空白处结束输入状态（照 AdminItemScreen 惯例：点前焦点在输入框、点击位置不在输入框 → 取消焦点）
        val wasInInput = focused is TextFieldWidget
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && amountField?.isMouseOver(mouseX, mouseY) != true) {
            focused = null
        }
        return result
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val confirm = confirmOpen
        val amount = confirmAmount
        super.resize(client, width, height)
        if (confirm) {
            confirmOpen = false
            openConfirmDialog(amount)
        }
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val dialogX = width / 2 - dialogW / 2
        val dialogY = height / 2 - dialogH / 2
        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        if (confirmOpen) {
            return
        }

        val dialogX = width / 2 - dialogW / 2
        val dialogY = height / 2 - dialogH / 2
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan.title").formatted(Formatting.GOLD),
            width / 2, dialogY + 14, 0xFFFFFF
        )
        // 可用额度 / 当前欠款（价格+货币名照全模组规矩用蓝色；缓存未拉取按 0 显示，响应到达即更新）
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan.limit_line", formatPriceLong(limit.coerceAtLeast(0)), inlineCurrencyUnit()),
            dialogX + 12, dialogY + 40, 0x55FFFF
        )
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan.debt_line", formatPriceLong(debt.coerceAtLeast(0)), inlineCurrencyUnit()),
            dialogX + 12, dialogY + 58, 0x55FFFF
        )
        if (hasBadDebt || hasOverdue) {
            context.drawTextWithShadow(
                textRenderer,
                Text.translatable(
                    if (hasBadDebt) "cobblemarket.loan.bad_debt_hint" else "cobblemarket.loan.overdue_hint"
                ).formatted(Formatting.RED),
                dialogX + 12, dialogY + 76, 0xFFFFFF
            )
        }
        // 金额输入框标签
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan.amount_label"),
            dialogX + 12, dialogY + 90, 0xFFFFFF
        )
    }

    override fun shouldPause() = false
}
