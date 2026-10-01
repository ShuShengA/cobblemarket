package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.currencyUnitWidth
import com.shusheng.cobblemarket.client.drawAmountLine
import com.shusheng.cobblemarket.client.drawCurrencyUnit
import com.shusheng.cobblemarket.client.formatPriceLong
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

    // 下限 280 = 三期按钮的硬需求（10 左边距 + 3×84 + 2×4 间隙 + 10 右边距）；
    // ⚠ 别再压到 200：按钮是固定宽、不随弹窗缩，弹窗一窄第三期就冲出背景（2026-09-21 用户报）
    private val dialogW = minOf(340, width - 40).coerceAtLeast(280)
    // 148 = 最下面一行（Confirm Pay 底边 122+16=138）+ 底部留白 10px（CLAUDE.md 的弹窗约定）。
    // ⚠ 原来写的 170 让底部空了 40px（2026-09-21 用户报）；高度与行位**成对**改，别只动一边
    private val dialogH = 148

    private var plans = listOf<Pair<Int, Double>>()
    private var selectedPlan = 0
    private var infoLoaded = false

    /** 可用额度（进界面时经 requestCreditInfo 拉取，响应到达即刷新；未到达前按 0 显示，与 LoanScreen 同口径） */
    private var limit = 0L

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
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        rebuildPlanButtons(dialogX, dialogY)

        // 确认支付（点开黄金模板确认弹窗）
        payButton = NineSliceButton(
            dialogX + 90, dialogY + 122, 100, 16,
            Text.translatable("cobblemarket.meowth_pay.confirm_btn"),
            { openConfirmDialog() }
        )
        addDrawableChild(payButton)

        if (!infoLoaded) {
            requestCreditInfo()
            infoLoaded = true
        }
    }

    /** 方案按钮行（照 LoanScreen）：并排间距 4px；选中档靠**按钮自己的选中态**（背景第三段）表达 */
    private fun rebuildPlanButtons(dialogX: Int, dialogY: Int) {
        planButtons.forEach(::remove)
        planButtons.clear()
        val btnW = 84
        plans.forEachIndexed { i, (periods, fee) ->
            val btn = NineSliceButton(
                dialogX + 10 + i * (btnW + 4), dialogY + 80, btnW, 16,
                Text.literal(planLabel(periods, fee)),
                {
                    selectedPlan = i
                    refreshPlanLabels()
                }
            )
            // 选中的档位靠**按钮自己的选中态**（背景第三段）表达，不再在文字前加 `▶`
            btn.selectedVisual = i == selectedPlan
            planButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    /** 只刷选中态（按钮不重建；文案与选中无关，不再 setMessage） */
    private fun refreshPlanLabels() {
        planButtons.forEachIndexed { i, btn -> btn.selectedVisual = i == selectedPlan }
    }

    /** 方案按钮文案：「3期·0.5%」（照 LoanScreen；选中态由按钮自己的 selectedVisual 表达） */
    private fun planLabel(periods: Int, feeRate: Double): String =
        Text.translatable("cobblemarket.loan.plan_btn", periods, planPercentText(feeRate)).string

    private fun planPercentText(feeRate: Double): String {
        val tenths = (feeRate * 1000).roundToLong()
        return if (tenths % 10 == 0L) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"
    }

    /** 估算利息（照 LoanScreen 确认弹窗公式）：等额本金，各期剩余之和 = P×(n+1)/2 */
    private fun estInterest(periods: Int, feeRate: Double): Long =
        Math.round(amount * feeRate * (periods + 1) / 2.0)

    /** 分期方案快照（打开时拉取） */
    fun onCreditInfo(payload: CreditInfoPayload) {
        limit = payload.limit
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
        // ⚠ 走 `truncateStyled`（保留分段样式）—— `.string` 一转，精灵的属性色 / 金★ 全被洗成白字
        //   （2026-09-26 用户报「其他地方我懒得一个一个找了」；本文件主界面那条早就用了 truncateStyled
        //   并在注释里点过这个坑，**确认弹窗这条漏改了**）
        val itemLine = Text.translatable("cobblemarket.meowth_pay.confirm_item").append(itemDesc)
        context.drawTextWithShadow(
            textRenderer,
            com.shusheng.cobblemarket.util.TextUtil.truncateStyled(itemLine, dW - 24),
            lineX, ly, 0xFFFFFF
        )
        ly += 18
        // ⚠ 金额与单位拆开画（物品货币时单位是物品图标）：词条的单位参数传空串占位，见 drawAmountLine
        drawAmountLine(
            context, textRenderer, false,
            Text.translatable("cobblemarket.meowth_pay.confirm_amount", formatPriceLong(amount), Text.literal("")),
            lineX, ly, 0xFFFFFF
        )
        ly += 18
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan.confirm_plan", periods, planPercentText(feeRate)),
            lineX, ly, 0xFFFFFF
        )
        ly += 18
        drawAmountLine(
            context, textRenderer, false,
            Text.translatable(
                "cobblemarket.loan.confirm_each",
                formatPriceLong(amount / periods + estInterest(periods, feeRate) / periods),
                Text.literal("")
            ),
            lineX, ly, 0xFFFFFF
        )
        ly += 18
        drawAmountLine(
            context, textRenderer, false,
            Text.translatable(
                "cobblemarket.loan.confirm_total",
                Text.literal(formatPriceLong(amount + estInterest(periods, feeRate))).formatted(Formatting.GOLD),
                Text.literal("")
            ),
            lineX, ly, 0xFFFFFF, unitColor = 0xFFAA00
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
        // 商品行：名称 + 金额。⚠ 两条规矩：
        // ① **金额与单位金色**（真实金额用金；蓝色只留给服务器给的虚拟额度，见下面的额度行）；
        // ② 必须走富文本参数，别用 itemDesc.string 拼 —— 那是纯文本，会把精灵名的样式整个丢掉
        //    （闪光精灵的金★就长在名字里），长名截断改用 truncateStyled（保样式）
        // ⚠ 金额与单位拆开画（物品货币时单位是物品图标）：金额那个参数只留金额 + 一个空格，见 drawAmountLine
        drawAmountLine(
            context, textRenderer, false,
            Text.translatable(
                "cobblemarket.meowth_pay.item_line",
                com.shusheng.cobblemarket.util.TextUtil.truncateStyled(itemDesc, 120),
                Text.literal("${formatPriceLong(amount)} ").formatted(Formatting.GOLD)
            ),
            dialogX + 12, dialogY + 58, 0xFFFFFF, unitColor = 0xFFAA00
        )
        // 可用额度：**居中紧贴标题**（用户 2026-09-21 拍板）—— 与标题同属「头部状态区」，
        // 商品/方案/估算才是左对齐的交易信息。蓝色同 LoanScreen 的额度行，
        // **不足本次支付金额时标红**（玩家一眼看出买不起，不用点确认才被服务端拒）。
        // ⚠ 混排行别用 drawCenteredTextWithShadow —— 它会让行首汉字比同行的数字高一点点，
        //   改成「算好 x 再左对齐画」，观感同样居中（见 docs/gui_pitfalls.md）
        // ⚠ 额度由 requestCreditInfo 异步拉取、未到达前按 0 显示（与 LoanScreen 同口径）
        val limitColor = if (limit < amount) 0xFF5555 else 0x55FFFF
        val limitLine = Text.translatable(
            "cobblemarket.meowth_pay.limit_line",
            formatPriceLong(limit.coerceAtLeast(0)),
            Text.literal("")
        )
        // 单位不在文本里了 → 居中锚点按「整行含单位」算，单位紧跟在词条文本之后
        val limitX = width / 2 - (textRenderer.getWidth(limitLine) + currencyUnitWidth(textRenderer, false)) / 2
        context.drawTextWithShadow(textRenderer, limitLine, limitX, dialogY + 36, limitColor)
        drawCurrencyUnit(context, textRenderer, false, limitX + textRenderer.getWidth(limitLine), dialogY + 36, limitColor)
        // 动态估算行：随选中方案刷新（玩家能看到每期/总计该还多少）
        val (periods, feeRate) = plans.getOrNull(selectedPlan) ?: (0 to 0.0)
        if (periods > 0) {
            drawAmountLine(
                context, textRenderer, false,
                Text.translatable(
                    "cobblemarket.meowth_pay.each_est",
                    formatPriceLong(amount / periods + estInterest(periods, feeRate) / periods),
                    formatPriceLong(amount + estInterest(periods, feeRate)),
                    Text.literal("")
                ),
                dialogX + 12, dialogY + 104, 0x888888
            )
        }
    }

    override fun shouldPause() = false
}
