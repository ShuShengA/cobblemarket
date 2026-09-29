package com.shusheng.cobblemarket.screen

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.sound.SoundManager
import net.minecraft.item.ItemStack
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
    /**
     * 可选**物品**图标（携带物 / 球种这种"值本身就是物品"的字段）：渲染成 16×16。
     * 与 [iconLeft] **共用同一套排版**（占左侧图标位、文字跟在右边，整组居中）——
     * 它是"图标的另一种来源"，不是替代整个按钮。运行时可直接赋值切换。
     */
    var iconStack: ItemStack? = null,
    /**
     * [iconStack] 的绘制边长（默认 16 —— 携带物 / 球种那些按钮都是这个尺寸）。
     * 货币切换按钮要它与 RP 图标（[com.shusheng.cobblemarket.client.CURRENCY_BUTTON_ICON_SIZE]）**同尺寸**、
     * 切换时不跳变，所以做成可配；⚠ 改它不影响那些没传的调用点。
     */
    var iconStackSize: Int = 16,
    /**
     * 图标排在**文字之后**（「携带物：[图标]」这种「标签 + 值」的排法）。
     * 默认 false = 图标在前 —— 项目里既有的按钮都是纯图标或图标在前，别动它们。
     */
    private val iconAfterText: Boolean = false,
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

    /**
     * 可选：把**绘制与点击**都裁到这个矩形内 `[x1, y1, x2, y2]`（滚动列表里的按钮用）。
     * `null` = 不裁（绝大多数按钮用不到）。
     *
     * ⚠ 为什么得由**控件自己**裁：滚动列表里那些半露在可视区外的按钮，是 `super.render()`
     * 统一绘制的 —— 那时候外层 `enableScissor` 早就关了，外层裁不到它们。
     * 不裁的话，滚出去的那半截会糊在列表外的分割线上（2026-09-19 用户实测）。
     */
    var clipRect: IntArray? = null

    override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // ⚠ 本方法**不许提前 return** —— 提前返回就漏掉下面的 disableScissor，
        //   会把整个界面后续的绘制全裁掉
        val clip = clipRect
        if (clip != null) context.enableScissor(clip[0], clip[1], clip[2], clip[3])
        val maxState = texH / NINE_SLICE_STATE_H - 1
        val state = when {
            selectedVisual -> 2.coerceAtMost(maxState)
            isHovered -> 1
            else -> 0
        }
        drawNineSlice(context, texture, x, y, width, height, state, texH)

        // 图标与文字是**并排**的（「携带物：[图标]」这种「标签 + 图标」，不是整格只有一个图标）：
        // **物品图标**（iconStack）与**贴图图标**（iconLeft）共用同一套排版 —— 都占左侧图标位、
        // 按钮文字跟在右边。物品图标只是"图标的另一种来源"，不是替代整个按钮。
        val stackIcon = iconStack?.takeIf { !it.isEmpty }
        val leftIcon = iconFrames?.let { frames ->
            frames[((System.currentTimeMillis() / 100) % frames.size).toInt()]
        } ?: iconLeft
        val iconW = if (stackIcon != null) iconStackSize else (iconTexW * iconScale).toInt()
        val iconH = if (stackIcon != null) iconStackSize else (iconTexH * iconScale).toInt()
        val iconCount = if (stackIcon != null) 1 else if (iconLeft2 != null) 2 else 1
        val totalIconW = if (stackIcon != null || leftIcon != null) iconW * iconCount + (iconCount - 1) else 0

        // ⚠ 图标 + 文字要**作为一整组**居中：原来图标独立居中、文字又避开图标居中，
        //   两者并存时**必然重合**（2026-09-20 用户实测「图标和『携带物：』叠在一起」）。
        //   纯图标按钮（没有文字）仍按原来的"图标居中"，既有界面不受影响。
        val font = MinecraftClient.getInstance().textRenderer
        val msgText = message.string
        val hasText = msgText.isNotEmpty()
        val grouped = (stackIcon != null || leftIcon != null) && hasText
        val textW = font.getWidth(message)
        val groupW = if (grouped) totalIconW + ICON_TEXT_GAP + textW else totalIconW
        val groupX = x + (width - groupW) / 2
        // 图标可以排在文字**之后**（「携带物：[图标]」这种标签+值的排法，见 iconAfterText）
        val iconX = if (iconAfterText && grouped) groupX + textW + ICON_TEXT_GAP else groupX

        if (stackIcon != null) {
            context.matrices.push()
            context.matrices.translate(iconX.toDouble(), (y + (height - iconH) / 2).toDouble(), 0.0)
            // ⚠ drawItem 恒按 16×16 画（没有尺寸参数）⇒ 非 16 的走 matrices 缩放
            if (iconStackSize != 16) {
                val s = iconStackSize / 16f
                context.matrices.scale(s, s, 1f)
            }
            context.drawItem(stackIcon, 0, 0)
            context.matrices.pop()
        } else if (leftIcon != null) {
            val startX = iconX
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

        val color = if (active) textColor else 0xA0A0A0
        val textY = y + (height - 8) / 2
        // 有图标时文字紧跟在图标右边（整组已居中）；没图标就整行居中，与原来一致
        val textBaseX =
            if (grouped) (if (iconAfterText) groupX else groupX + totalIconW + ICON_TEXT_GAP)
            else x + (width - textW) / 2
        // ⚠ 原先这里有一段「把 `● ` 前缀无阴影绘制」的特判 —— 那是 `TextUtil.selectedText` 时代的产物。
        //   选中项改用按钮自己的选中态（背景第三段）后，那个函数已被删除，这段也一并删掉。
        context.drawTextWithShadow(font, message, textBaseX, textY, color)
        // 置灰遮罩（视觉置灰但保留可点击性，见 dimmed 注释）
        if (dimmed) {
            context.fill(x, y, x + width, y + height, 0x66000000)
        }
        if (clip != null) context.disableScissor()
    }

    /**
     * 被裁掉的区域**不可点** —— 露在列表区外的那半截只是视觉上被切了，
     * 若还留着点击区，玩家点到看得见的部分反而有反应，更莫名其妙。
     */
    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean {
        if (!super.isMouseOver(mouseX, mouseY)) return false
        val clip = clipRect ?: return true
        return mouseX >= clip[0] && mouseX < clip[2] && mouseY >= clip[1] && mouseY < clip[3]
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

/** 按钮里「图标 + 文字」并排时的间距（两者作为一整组居中，见 renderWidget） */
private const val ICON_TEXT_GAP = 4
