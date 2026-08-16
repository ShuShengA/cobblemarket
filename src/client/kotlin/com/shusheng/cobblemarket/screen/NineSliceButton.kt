package com.shusheng.cobblemarket.screen

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.sound.SoundManager
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Identifier

class NineSliceButton(
    x: Int, y: Int, width: Int, height: Int,
    message: Text,
    onPress: PressAction,
    var textColor: Int = 0xFFFFFF
) : ButtonWidget(x, y, width, height, message, onPress, ButtonWidget.DEFAULT_NARRATION_SUPPLIER) {

    override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        drawNineSlice(context, TEXTURE, x, y, width, height, if (isHovered) 1 else 0, TEX_H)

        val font = MinecraftClient.getInstance().textRenderer
        val color = if (active) textColor else 0xA0A0A0
        val textX = x + (width - font.getWidth(message)) / 2
        val textY = y + (height - 8) / 2
        context.drawTextWithShadow(font, message, textX, textY, color)
    }

    override fun playDownSound(soundManager: SoundManager) {
        soundManager.play(PositionedSoundInstance.master(
            SoundEvent.of(Identifier.of("cobblemarket", "button_click")),
            1.0f
        ))
    }

    companion object {
        private val TEXTURE = Identifier.of("cobblemarket", "textures/gui/button_9slice.png")
        private const val TEX_H = 80
    }
}
