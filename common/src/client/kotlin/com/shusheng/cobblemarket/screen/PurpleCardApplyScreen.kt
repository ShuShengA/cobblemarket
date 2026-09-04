package com.shusheng.cobblemarket.screen

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * 申请喵喵紫卡弹窗（批次 7.5 骨架）：内容后期补充。
 * 照 LoanScreen 弹窗式：遮罩 + 居中弹窗 + 标题 + 返回按钮。
 */
class PurpleCardApplyScreen : Screen(Text.translatable("cobblemarket.card.apply_title")) {

    private val dialogW = 280
    private val dialogH = 170

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
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val dialogX = width / 2 - dialogW / 2
        val dialogY = height / 2 - dialogH / 2
        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val dialogY = height / 2 - dialogH / 2
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.card.apply_title").formatted(Formatting.GOLD),
            width / 2, dialogY + 14, 0xFFFFFF
        )
    }

    override fun shouldPause() = false
}
