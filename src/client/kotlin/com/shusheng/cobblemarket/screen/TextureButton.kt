package com.shusheng.cobblemarket.screen

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.sound.SoundManager
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Identifier

class TextureButton(
    x: Int, y: Int, width: Int, height: Int,
    message: Text,
    onPress: PressAction,
    private val iconLeft: Identifier? = null,
    private val iconRight: Identifier? = null
) : ButtonWidget(x, y, width, height, message, onPress, ButtonWidget.DEFAULT_NARRATION_SUPPLIER) {

    private val texture = Identifier.of("cobblemarket", "textures/gui/button.png")
    private val textureWidth = 320
    private val textureHeight = 96
    private val stateHeight = 48

    override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val hovered = isHovered
        val vOffset = if (hovered) stateHeight else 0

        // Texture is 2x resolution; scale down to the button's logical size
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(width / 320f, height / 48f, 1f)
        context.drawTexture(texture, 0, 0, 0f, vOffset.toFloat(), 320, stateHeight, textureWidth, textureHeight)
        context.matrices.pop()

        // Draw optional side icons (24x24 texture scaled down to 12x12 logical)
        val iconSize = 12
        val iconY = y + (height - iconSize) / 2
        if (iconLeft != null) {
            drawIcon(context, iconLeft, x + 5, iconY)
        }
        if (iconRight != null) {
            drawIcon(context, iconRight, x + width - 5 - iconSize, iconY)
        }

        // Draw centered text（有图标时在图标右侧的剩余空间内居中，长文案不压图标）
        val font = MinecraftClient.getInstance().textRenderer
        val iconSpace = if (iconLeft != null) 5 + 12 + 4 else 0
        val textX = x + iconSpace + (width - iconSpace - font.getWidth(message)) / 2
        val textY = y + (height - 8) / 2
        context.drawTextWithShadow(font, message, textX, textY, 0xFFFFFF)
    }

    private fun drawIcon(context: DrawContext, icon: Identifier, x: Int, y: Int) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f, 1f)
        context.drawTexture(icon, 0, 0, 0f, 0f, 24, 24, 24, 24)
        context.matrices.pop()
    }

    override fun playDownSound(soundManager: SoundManager) {
        soundManager.play(PositionedSoundInstance.master(
            SoundEvent.of(Identifier.of("cobblemarket", "button_click")),
            1.0f
        ))
    }
}
