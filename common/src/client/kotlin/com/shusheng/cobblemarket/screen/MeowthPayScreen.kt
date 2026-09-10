package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.client.requestCreditInfo
import com.shusheng.cobblemarket.network.CreditInfoPayload
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.Formatting
import kotlin.math.roundToLong

/**
 * 喵喵支付（消费贷，批次 4）：独立弹窗式界面照 LoanScreen 模板。
 * 商品名+金额 → 三档方案按钮（默认第一档）→ 动态估算行（每期约还/共约还）→ 确认支付 → 黄金模板确认弹窗。
 * 确认后回调 onConfirm(planIndex)（精灵购买发 BuyFromMarketPayload、物品购买发 BuyItemPayload）。
 */
class MeowthPayScreen(
    private val itemDesc: Text,
    private val amount: Long,
    private val onConfirm: (Int) -> Unit,
    private val onBack: () -> Unit
) : Screen(Text.translatable("cobblemarket.meowth_pay.title")) {

    private val dialogW = 280
    private val dialogH = 170

    private var plans = listOf<Pair<Int, Double>>()
    private var selectedPlan = 0
    private var infoLoaded = false

    private val planButtons = mutableListOf<NineSliceButton>()
    private var backButton: NineSliceButton? = null
    private var payButton: NineSliceButton? = null

    // ── 确认弹窗（黄金模板照 LoanScreen.openConfirmDialog） ──
    private var confirmOpen = false
    private var confirmConfirmButton: NineSliceButton? = null
    private var confirmCancelButton: NineSliceButton? = null

    override fun init() {
        super.init()
        val dialogX = width / 2 - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        // 返回按钮：右上角内移 8px（照 LoanScreen）
        backButton = NineSliceButton(
            dialogX + dialogW - 58, dialogY + 8, 50, 16,
            Text.literal(""),
            { client?.setScreen(null); onBack() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        rebuildPlanButtons(dialogX, dialogY)

        // 确认支付（点开黄金模板确认弹窗）
        payButton = NineSliceButton(
            dialogX + 90, dialogY + 114, 100, 16,
            Text.translatable("cobblemarket.meowth_pay.confirm_btn"),
            { openConfirmDialog() }
        )
        addDrawableChild(payButton)

        if (!infoLoaded) {
            requestCreditInfo()
            infoLoaded = true
        }
    }

    private fun rebuildPlanButtons(dialogX: Int, dialogY: Int) {
        planButtons.forEach(::remove)
        planButtons.clear()
        val btnW = 84
        plans.forEachIndexed { i, (periods, fee) ->
            val btn = NineSliceButton(
                dialogX + 10 + i * (btnW + 4), dialogY + 72, btnW, 16,
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

    /** 方案按钮文案：「▶3期·0.5%」（照 LoanScreen） */
    private fun planLabel(periods: Int, feeRate: Double, selected: Boolean): String {
        val base = Text.translatable("cobblemarket.loan.plan_btn", periods, planPercentText(feeRate)).string
        return if (selected) "▶$base" else base
    }

    private fun planPercentText(feeRate: Double): String {
        val tenths = (feeRate * 1000).roundToLong()
        return if (tenths % 10 == 0L) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"
    }

    /** 估算利息（照 LoanScreen 确认弹窗公式）：等额本金，各期剩余之和 = P×(n+1)/2 */
    private fun estInterest(periods: Int, feeRate: Double): Long =
        Math.round(amount * feeRate * (periods + 1) / 2.0)

    /** 分期方案快照（打开时拉取） */
    fun onCreditInfo(payload: CreditInfoPayload) {
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

    // ── 确认弹窗（黄金模板照 LoanScreen.openConfirmDialog） ──

    private fun openConfirmDialog() {
        if (plans.getOrNull(selectedPlan) == null) {
            playFailSound()
            return
        }
        confirmOpen = true
        // 隐藏下层控件（弹窗打开期间不可交互；closeConfirmDialog 的 init 重建会恢复）
        planButtons.forEach { it.visible = false }
        backButton?.visible = false
        payButton?.visible = false

        val centerX = width / 2
        val dialogY = height / 2 - 90

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染，照搬 LoanScreen）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderConfirmBackground(context)
            }
        })

        confirmConfirmButton = NineSliceButton(
            centerX - 50, dialogY + 144, 56, 20,
            Text.translatable("cobblemarket.loan.confirm_btn"),
            { confirmPay() }
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

    private fun confirmPay() {
        val plan = plans.getOrNull(selectedPlan) ?: return
        onConfirm(selectedPlan)
    }

    private fun renderConfirmBackground(context: DrawContext) {
        val (periods, feeRate) = plans.getOrNull(selectedPlan) ?: return
        val centerX = width / 2
        val dW = 280
        val dH = 180
        val dialogX = centerX - dW / 2
        val dialogY = height / 2 - dH / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dW, dH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.meowth_pay.confirm_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF
        )

        val lineX = dialogX + 12
        var ly = dialogY + 42
        // 商品名按可用宽度截断（长名不溢出弹窗）
        val itemLine = Text.translatable("cobblemarket.meowth_pay.confirm_item").append(" ").append(itemDesc)
        context.drawTextWithShadow(
            textRenderer,
            com.shusheng.cobblemarket.util.TextUtil.truncateString(itemLine.string, dW - 24),
            lineX, ly, 0xFFFFFF
        )
        ly += 18
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.meowth_pay.confirm_amount", formatPriceLong(amount), inlineCurrencyUnit()),
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
                formatPriceLong(amount / periods + estInterest(periods, feeRate) / periods),
                inlineCurrencyUnit()
            ),
            lineX, ly, 0xFFFFFF
        )
        ly += 18
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable(
                "cobblemarket.loan.confirm_total",
                formatPriceLong(amount + estInterest(periods, feeRate)),
                inlineCurrencyUnit()
            ),
            lineX, ly, 0x55FFFF
        )
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val confirm = confirmOpen
        super.resize(client, width, height)
        if (confirm) {
            confirmOpen = false
            openConfirmDialog()
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
            Text.translatable("cobblemarket.meowth_pay.title").formatted(Formatting.GOLD),
            width / 2, dialogY + 14, 0xFFFFFF
        )
        // 商品行：名称 + 金额（价格+货币名照全模组规矩用蓝色；长名截断）
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable(
                "cobblemarket.meowth_pay.item_line",
                com.shusheng.cobblemarket.util.TextUtil.truncateString(itemDesc.string, 120),
                formatPriceLong(amount),
                inlineCurrencyUnit()
            ),
            dialogX + 12, dialogY + 40, 0xFFFFFF
        )
        // 动态估算行：随选中方案刷新（玩家能看到每期/总计该还多少）
        val (periods, feeRate) = plans.getOrNull(selectedPlan) ?: (0 to 0.0)
        if (periods > 0) {
            context.drawTextWithShadow(
                textRenderer,
                Text.translatable(
                    "cobblemarket.meowth_pay.each_est",
                    formatPriceLong(amount / periods + estInterest(periods, feeRate) / periods),
                    formatPriceLong(amount + estInterest(periods, feeRate)),
                    inlineCurrencyUnit()
                ),
                dialogX + 12, dialogY + 96, 0x888888
            )
        }
    }

    override fun shouldPause() = false
}
