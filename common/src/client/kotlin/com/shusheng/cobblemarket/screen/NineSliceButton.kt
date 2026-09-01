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
    var textColor: Int = 0xFFFFFF,
    /** 点击音效；null = 静音（由业务逻辑按结果自行播放，如出价按钮校验失败播 fail） */
    var clickSound: Identifier? = Identifier.of("cobblemarket", "button_click"),
    // 可选图标（默认 24×24 纹理按 iconScale 缩放居中；纯图标按钮传空 message；性别按钮运行时切换用 var）
    var iconLeft: Identifier? = null,
    // 第二图标（并排绘制，间距 1px；性别按钮不限态用 ♂♀ 双图标）
    var iconLeft2: Identifier? = null,
    // 图标贴图实际尺寸与缩放（6×8 性别图标用 iconTexW=6/iconTexH=8/iconScale=1.5）
    private val iconTexW: Int = 24,
    private val iconTexH: Int = 24,
    private val iconScale: Float = 0.5f,
    // 可选背景纹理（默认按钮九宫格；入口右下角小按钮用 row_background）
    private val texture: Identifier = TEXTURE,
    private val texH: Int = TEX_H
) : ButtonWidget(x, y, width, height, message, onPress, ButtonWidget.DEFAULT_NARRATION_SUPPLIER) {

    // 开关式按钮的按下视觉：面板展开期间保持"按下态"（纹理第三段；只有两段的纹理自动退回悬停态）
    var pressedVisual: Boolean = false

    /**
     * 置灰视觉（不影响 active 与点击）：渲染尾部盖半透明黑遮罩，照精灵图标压暗惯例。
     * 用于「功能未开放但点击要有提示」的按钮——active=false 会吞掉 onPress，无法做点击提示。
     */
    var dimmed: Boolean = false

    override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val maxState = texH / NINE_SLICE_STATE_H - 1
        val state = when {
            pressedVisual -> 2.coerceAtMost(maxState)
            isHovered -> 1
            else -> 0
        }
        drawNineSlice(context, texture, x, y, width, height, state, texH)

        // 可选图标（可双图标并排，整体居中）
        val leftIcon = iconLeft
        if (leftIcon != null) {
            val iconW = (iconTexW * iconScale).toInt()
            val iconH = (iconTexH * iconScale).toInt()
            val totalW = iconW * (if (iconLeft2 != null) 2 else 1) + (if (iconLeft2 != null) 1 else 0)
            val startX = x + (width - totalW) / 2
            val startY = y + (height - iconH) / 2
            fun drawIcon(id: Identifier, ix: Int) {
                context.matrices.push()
                context.matrices.translate(ix.toDouble(), startY.toDouble(), 0.0)
                context.matrices.scale(iconScale, iconScale, 1f)
                context.drawTexture(id, 0, 0, 0f, 0f, iconTexW, iconTexH, iconTexW, iconTexH)
                context.matrices.pop()
            }
            drawIcon(leftIcon, startX)
            iconLeft2?.let { drawIcon(it, startX + iconW + 1) }
        }

        val font = MinecraftClient.getInstance().textRenderer
        val color = if (active) textColor else 0xA0A0A0
        val iconW = (iconTexW * iconScale).toInt()
        val totalIconW = if (iconLeft != null) iconW * (if (iconLeft2 != null) 2 else 1) + (if (iconLeft2 != null) 1 else 0) else 0
        val iconSpace = if (iconLeft != null) 5 + totalIconW + 4 else 0
        val textY = y + (height - 8) / 2
        val msgString = message.string
        // 选中标记黑点（TextUtil.selectedText 的 ● 前缀）无阴影绘制：带阴影看起来不是圆
        if (msgString.startsWith("● ")) {
            val dotW = font.getWidth("●")
            val labelW = font.getWidth(msgString.substring(2))
            val textX = x + iconSpace + (width - iconSpace - (dotW + 2 + labelW)) / 2
            context.drawText(font, "●", textX, textY, 0x000000, false)
            context.drawTextWithShadow(font, msgString.substring(2), textX + dotW + 2, textY, color)
        } else {
            val textX = x + iconSpace + (width - iconSpace - font.getWidth(message)) / 2
            context.drawTextWithShadow(font, message, textX, textY, color)
        }
        // 置灰遮罩（视觉置灰但保留可点击性，见 dimmed 注释）
        if (dimmed) {
            context.fill(x, y, x + width, y + height, 0x66000000)
        }
    }

    override fun playDownSound(soundManager: SoundManager) {
        clickSound?.let {
            soundManager.play(PositionedSoundInstance.master(
                SoundEvent.of(it),
                1.0f
            ))
        }
    }

    companion object {
        private val TEXTURE = Identifier.of("cobblemarket", "textures/gui/button_9slice.png")
        private const val TEX_H = 80
    }
}
