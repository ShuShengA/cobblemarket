package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.network.PurpleCardApplyInfoPayload
import com.shusheng.cobblemarket.network.RequestPurpleCardApplyInfoPayload
import com.shusheng.cobblemarket.network.RequestPurpleCardApplyPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/**
 * 申请喵喵紫卡（批次 7.5）：上方紫卡大图 + 逐条申请条件（门槛/玩家当前值/✓✗，照交付精灵条件样式）。
 * 打开时拉取条件快照；全部满足且开关开启时可点「申请」（服务端复核扣费发卡）。
 */
class PurpleCardApplyScreen : Screen(Text.translatable("cobblemarket.card.apply_title")) {

    private val dialogW = 300
    private val dialogH = 320

    private val conditionKeys = listOf(
        "cobblemarket.op.scfg_apply_asset",
        "cobblemarket.op.scfg_apply_volume",
        "cobblemarket.op.scfg_apply_credit",
        "cobblemarket.op.scfg_apply_deposit",
        "cobblemarket.op.scfg_apply_dex",
        "cobblemarket.op.scfg_apply_no_overdue",
    )

    private var info: PurpleCardApplyInfoPayload? = null
    private var applyButton: NineSliceButton? = null

    override fun init() {
        super.init()
        val dialogX = width / 2 - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        // 返回按钮：右上角内移 8px
        addDrawableChild(NineSliceButton(
            dialogX + dialogW - 58, dialogY + 8, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(MeowthBankScreen()) }
        ))

        // 申请按钮（底部居中；资格/开关不符时置灰，文案区分）
        applyButton = NineSliceButton(
            width / 2 - 50, dialogY + dialogH - 44, 100, 20,
            Text.translatable("cobblemarket.card.apply_btn"),
            { sendToServer(RequestPurpleCardApplyPayload()) }
        )
        applyButton?.active = false
        addDrawableChild(applyButton)

        sendToServer(RequestPurpleCardApplyInfoPayload())
    }

    fun onApplyInfo(payload: PurpleCardApplyInfoPayload) {
        info = payload
        applyButton?.active = payload.eligible && payload.selfApplyEnabled
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
            Text.translatable("cobblemarket.card.apply_title").formatted(Formatting.GOLD, Formatting.BOLD),
            centerX, dialogY + 14, 0xFFFFFF
        )

        // 紫卡大图（56px 居中）
        val cardItem = net.minecraft.registry.Registries.ITEM.get(
            Identifier.of("cobblemarket", "meowth_purple_card")
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
            var y = dialogY + 96
            conditionKeys.forEachIndexed { i, key ->
                val entry = payload.conditions.getOrNull(i) ?: return@forEachIndexed
                val label = Text.translatable(key).string
                val isBool = i == 5 // 无逾期记录项
                val valueText = if (isBool) {
                    if (entry.current > 0) Text.translatable("cobblemarket.card.apply_no_record").string
                    else Text.translatable("cobblemarket.card.apply_has_record").string
                } else {
                    "${formatPriceLong(entry.current)}/${formatPriceLong(entry.requirement)}"
                }
                val mark = if (entry.satisfied)
                    Text.translatable("cobblemarket.buy_order.match_ok_full").string to 0x55FF55
                else
                    Text.translatable("cobblemarket.buy_order.match_no_full").string to 0xFF6666
                context.drawTextWithShadow(textRenderer, label, dialogX + 14, y, 0xFFFFFF)
                context.drawTextWithShadow(textRenderer, valueText, dialogX + 110, y, 0x55FFFF)
                context.drawTextWithShadow(textRenderer, mark.first, dialogX + 252, y, mark.second)
                y += 15
            }
            // 申请费用行
            val feeLine = Text.translatable(
                "cobblemarket.card.apply_fee_line",
                formatPriceLong(payload.fee), inlineCurrencyUnit()
            )
            context.drawTextWithShadow(textRenderer, feeLine, dialogX + 14, y + 2, 0x888888)
        }
    }

    override fun shouldPause() = false
}
