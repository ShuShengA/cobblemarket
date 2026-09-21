package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.BlackCardApplyInfoPayload
import com.shusheng.cobblemarket.network.RequestBlackCardApplyInfoPayload
import com.shusheng.cobblemarket.network.RequestBlackCardApplyPayload
import com.shusheng.cobblemarket.network.RequestBlackCardRedoPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/**
 * 申请喵喵黑卡（照紫卡申请界面）：上方黑卡大图 + 逐条申请条件（8 项：持有紫卡硬条件 + 七项门槛）。
 * 打开时拉取条件快照；全部满足且开关开启时可点「申请」（服务端复核扣费发卡）。
 */
class BlackCardApplyScreen : Screen(Text.translatable("cobblemarket.card.black_apply_title")) {

    // 下限 300（= 1.1.0 的固定宽度）：标题约 210px、右列权益行从 centerX+36 起算且自身约 112px，
    // 需求明显大于 200 —— 窄屏下弹窗缩到 200 时标题和右列全挤出去
    //   （2026-09-21 用户报；同批「按屏幕钳制」改动里 LoanScreen 也有同款问题）
    private val dialogW = minOf(360, width - 40).coerceAtLeast(300)
    /** 条件比紫卡多一行「持有紫卡」硬条件：七项门槛全配时是 8 行，加高 24px 才不会压到申请按钮 */
    private val dialogH = 344

    // 与 BlackCardApplyInfoPayload.conditions 同序（服务端 FinanceNetwork.sendBlackCardApplyInfo 构造）
    private val conditionKeys = listOf(
        "cobblemarket.card.black_need_purple",
        "cobblemarket.op.scfg_apply_asset",
        "cobblemarket.op.scfg_apply_volume",
        "cobblemarket.op.scfg_apply_credit",
        "cobblemarket.op.scfg_apply_deposit",
        "cobblemarket.op.scfg_apply_seen",
        "cobblemarket.op.scfg_apply_dex",
        "cobblemarket.op.scfg_apply_no_overdue",
    )

    private var info: BlackCardApplyInfoPayload? = null
    private var applyButton: NineSliceButton? = null
    /** 已是持有者：按钮变「补发黑卡」 */
    private var isHolder = false
    /** 按钮可点状态（持有者恒可点；否则资格+开关；快照未到前 false = 置灰） */
    private var canApply = false

    override fun init() {
        super.init()
        val dialogX = width / 2 - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        // 返回按钮：右上角内移 8px
        addDrawableChild(NineSliceButton(
            dialogX + dialogW - 58, dialogY + 8, 50, 16,
            Text.literal(""),
            { client?.setScreen(MeowthBankScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        ))

        // 底部按钮（居中；持有者=补发黑卡，非持有者=申请，资格/开关不符时置灰文案区分）
        applyButton = NineSliceButton(
            width / 2 - 50, dialogY + dialogH - 44, 100, 20,
            Text.translatable("cobblemarket.card.apply_btn"),
            {
                // 置灰态（资格不符/未开放/快照未到）：点击播 fail 音效提示，不发请求（照入口喵喵银行按钮 dimmed 模式）
                if (!canApply) {
                    playFailSound()
                } else {
                    // 登记卡片图标中心为发卡动画起点（服务端成功回包后从该位置放大飞到屏幕中央）
                    com.shusheng.cobblemarket.client.CardCelebrationAnimation.setPendingStart(width / 2, dialogY + 58)
                    if (isHolder) sendToServer(RequestBlackCardRedoPayload())
                    else sendToServer(RequestBlackCardApplyPayload())
                }
            }
        )
        applyButton?.dimmed = true // 快照未到置灰，onApplyInfo 到达后按条件刷新
        addDrawableChild(applyButton)

        sendToServer(RequestBlackCardApplyInfoPayload())
    }

    fun onApplyInfo(payload: BlackCardApplyInfoPayload) {
        info = payload
        isHolder = payload.isHolder
        canApply = payload.isHolder || (payload.eligible && payload.selfApplyEnabled)
        applyButton?.dimmed = !canApply
        if (isHolder) {
            // 持有者：按钮变「补发黑卡」（凭证丢失随时补）
            applyButton?.message = Text.translatable("cobblemarket.card.black_redo_button")
            return
        }
        applyButton?.message = Text.translatable(
            when {
                !payload.selfApplyEnabled -> "cobblemarket.card.apply_closed"
                !payload.eligible -> "cobblemarket.card.apply_not_eligible"
                else -> "cobblemarket.card.apply_btn"
            }
        )
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val dialogX = width / 2 - dialogW / 2
        val dialogY = height / 2 - dialogH / 2
        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val centerX = width / 2
        val dialogX = width / 2 - dialogW / 2
        val dialogY = height / 2 - dialogH / 2
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.card.black_apply_title").formatted(Formatting.GOLD, Formatting.BOLD),
            centerX, dialogY + 14, 0xFFFFFF
        )

        // 黑卡大图（56px 居中）
        val cardItem = net.minecraft.registry.Registries.ITEM.get(
            Identifier.of("cobblemarket", "meowth_black_card")
        )
        if (cardItem != net.minecraft.registry.Registries.ITEM.get(Identifier.of("minecraft", "air"))) {
            val size = 56
            com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                itemStack = net.minecraft.item.ItemStack(cardItem),
                x = (centerX - size / 2).toDouble(),
                y = (dialogY + 30).toDouble(),
                scale = size / 16.0,
                matrixStack = context.matrices
            )
        }

        // 条件行（照交付精灵条件样式：标签 + 门槛/当前值 + ✓/✗ 绿红）
        val payload = info
        if (payload == null) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.card.apply_loading").formatted(Formatting.GRAY),
                centerX, dialogY + 110, 0xFFFFFF
            )
        } else {
            // 卡权益（图标右侧，持有者/申请者都显示）：额度（金额用全模组价格蓝 0x55FFFF，完整千分位）+ 手续费减免（0 = 无）
            val rightsX = centerX + 36
            context.drawTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.card.apply_credit_line",
                    Text.literal("${formatPriceLong(payload.creditLimit)} ${inlineCurrencyUnit()}")
                        .setStyle(net.minecraft.text.Style.EMPTY.withColor(0x55FFFF))),
                rightsX, dialogY + 42, 0xFFFFFF
            )
            val pct = payload.feeDiscount * 100
            val discountText = if (payload.feeDiscount <= 0)
                Text.translatable("cobblemarket.card.apply_no_discount")
            else Text.literal(if (pct % 1.0 == 0.0) "${pct.toInt()}%" else "$pct%")
            context.drawTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.card.apply_fee_discount", discountText),
                rightsX, dialogY + 60, 0xFFFFFF
            )
            // 持有者（补发模式）：条件与资格无关，只居中显示补发费用行；非持有者显示条件行 + 申请费用行
            val feeLine = Text.translatable(
                if (isHolder) "cobblemarket.card.redo_fee_line" else "cobblemarket.card.apply_fee_line",
                formatPriceLong(if (isHolder) payload.redoFee else payload.fee), inlineCurrencyUnit()
            )
            if (isHolder) {
                context.drawCenteredTextWithShadow(textRenderer, feeLine, centerX, dialogY + 120, 0x888888)
            } else {
                var y = dialogY + 96
                conditionKeys.forEachIndexed { i, key ->
                    val entry = payload.conditions.getOrNull(i) ?: return@forEachIndexed
                    // 按 key 判定（勿用下标：插行会错位）
                    val isNeedPurple = key == "cobblemarket.card.black_need_purple"
                    // 门槛 0/关 = 不要求，该行不显示（硬条件「持有紫卡」除外，恒显示）
                    if (!isNeedPurple && entry.requirement <= 0) return@forEachIndexed
                    val label = Text.translatable(key).string
                    val isBool = isNeedPurple || key == "cobblemarket.op.scfg_apply_no_overdue" // 持有紫卡硬条件 / 无逾期记录项
                    // 图鉴两行是物种数不是金额，不带货币单位
                    val isDexCount = key == "cobblemarket.op.scfg_apply_seen" || key == "cobblemarket.op.scfg_apply_dex"
                    val valueText = when {
                        isBool -> if (entry.current > 0) {
                            if (isNeedPurple) Text.translatable("cobblemarket.card.black_holds_purple").string
                            else Text.translatable("cobblemarket.card.apply_no_record").string
                        } else {
                            if (isNeedPurple) Text.translatable("cobblemarket.card.black_no_purple").string
                            else Text.translatable("cobblemarket.card.apply_has_record").string
                        }
                        isDexCount -> "${formatPriceLong(entry.current)}/${formatPriceLong(entry.requirement)}"
                        else -> "${formatPriceLong(entry.current)}/${formatPriceLong(entry.requirement)} ${inlineCurrencyUnit()}"
                    }
                    // 行尾短符号（✓/✗ 绿红，长文案超宽改用颜色表意；完整语义在申请按钮文案）
                    val mark = if (entry.satisfied)
                        Text.translatable("cobblemarket.buy_order.match_ok").string to 0x55FF55
                    else
                        Text.translatable("cobblemarket.buy_order.match_no").string to 0xFF6666
                    // 两行布局：标签在上、带括号的条件值在下（英文长标签不再挤压数值）
                    context.drawTextWithShadow(textRenderer, label, dialogX + 14, y, 0xFFFFFF)
                    val valueLine = Text.translatable("cobblemarket.card.apply_cond_value", valueText)
                    context.drawTextWithShadow(textRenderer, valueLine, dialogX + 14, y + 11, 0x55FFFF)
                    context.drawTextWithShadow(textRenderer, mark.first, dialogX + dialogW - 26, y + 6, mark.second)
                    y += 22
                }
                context.drawTextWithShadow(textRenderer, feeLine, dialogX + 14, y + 2, 0x888888)
            }
        }
    }

    override fun shouldPause() = false

    /** 卡片图标中心坐标（发卡动画起点动态读取用；与 render 中图标位置同公式） */
    fun cardIconCenter(): Pair<Int, Int> {
        val dialogY = height / 2 - dialogH / 2
        return Pair(width / 2, dialogY + 30 + 28)
    }
}
