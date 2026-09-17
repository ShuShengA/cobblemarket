package com.shusheng.cobblemarket.screen

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.tooltip.Tooltip
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
    // 动画图标帧列表（每 100ms 切一帧，帧率同小卡比兽/皮卡丘入口图标）；非空时优先于 iconLeft
    private val iconFrames: List<Identifier>? = null,
    // 图标贴图实际尺寸与缩放（6×8 性别图标用 iconTexW=6/iconTexH=8/iconScale=1.5）
    private val iconTexW: Int = 24,
    private val iconTexH: Int = 24,
    private val iconScale: Float = 0.5f,
    // 可选悬停词条（图标化按钮构造即挂上，替代文字说明）
    tooltip: Text? = null,
    // 可选背景纹理（默认按钮九宫格；入口右下角小按钮用 row_background）
    private val texture: Identifier = TEXTURE,
    private val texH: Int = TEX_H
) : ButtonWidget(x, y, width, height, message, onPress, ButtonWidget.DEFAULT_NARRATION_SUPPLIER) {

    init {
        if (tooltip != null) setTooltip(Tooltip.of(tooltip))
    }

    /**
     * **选中态**（纹理第三段；只有两段的纹理会退回悬停态）。
     *
     * 注意它表示的是「持续保持」的状态，**不是鼠标按下去那一下的反馈** ——
     * 勾选的权限节点、手持物品 / 手持添加模式、筛选面板展开期间都用它。
     * 与悬停同时成立时选中优先（见 [renderWidget] 的 state 判断）。
     */
    var selectedVisual: Boolean = false

    /**
     * 置灰视觉（不影响 active 与点击）：渲染尾部盖半透明黑遮罩，照精灵图标压暗惯例。
     * 用于「功能未开放但点击要有提示」的按钮——active=false 会吞掉 onPress，无法做点击提示。
     */
    var dimmed: Boolean = false

    override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val maxState = texH / NINE_SLICE_STATE_H - 1
        val state = when {
            selectedVisual -> 2.coerceAtMost(maxState)
            isHovered -> 1
            else -> 0
        }
        drawNineSlice(context, texture, x, y, width, height, state, texH)

        // 可选图标（可双图标并排，整体居中）：动画帧按时间切换，没有帧才用静态 iconLeft
        val leftIcon = iconFrames?.let { frames ->
            frames[((System.currentTimeMillis() / 100) % frames.size).toInt()]
        } ?: iconLeft
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
                // 禁用态图标与文字同样置灰（文字 0xA0A0A0 = 160/255 ≈ 0.627）
                if (!active) RenderSystem.setShaderColor(0.627f, 0.627f, 0.627f, 1f)
                context.drawTexture(id, 0, 0, 0f, 0f, iconTexW, iconTexH, iconTexW, iconTexH)
                if (!active) RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
                context.matrices.pop()
            }
            drawIcon(leftIcon, startX)
            iconLeft2?.let { drawIcon(it, startX + iconW + 1) }
        }

        val font = MinecraftClient.getInstance().textRenderer
        val color = if (active) textColor else 0xA0A0A0
        val iconW = (iconTexW * iconScale).toInt()
        val totalIconW = if (leftIcon != null) iconW * (if (iconLeft2 != null) 2 else 1) + (if (iconLeft2 != null) 1 else 0) else 0
        val iconSpace = if (leftIcon != null) 5 + totalIconW + 4 else 0
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
        // 贴图总高 = 120（三段：普通 / 悬停 / 选中）——**必须与贴图实际高度一致**：
        // drawNineSlice 拿它做纵向坐标归一化（vOffset / texH），对不上会把按钮切成"上下各半"
        private const val TEX_H = 120
    }
}
