package com.shusheng.cobblemarket.screen

import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.client.IdCardBackgroundTexture
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
    /**
     * **物品图标**（[iconLeft] 的另一种来源）：与其给每个物品准备贴图，不如直接画物品栈 ——
     * 物品市场的「创造分类」网格那种"一格一个物品"的按钮用。
     * ⚠ 与 [iconLeft] **二选一**（两者都传时 iconStack 优先，本按钮的既有调用点一个都没传
     *   ⇒ 行为零变化）。名称与 1.3.0 上那套**同名同义**，上浮时好合。
     */
    var iconStack: net.minecraft.item.ItemStack? = null,
    /** [iconStack] 的绘制边长（默认 16 —— 1.3.0 上同名同义） */
    var iconStackSize: Int = 16,
    // 第二图标（并排绘制，间距 1px；性别按钮不限态用 ♂♀ 双图标）
    var iconLeft2: Identifier? = null,
    // 动画图标帧列表（每 100ms 切一帧，帧率同小卡比兽/皮卡丘入口图标）；非空时优先于 iconLeft
    private val iconFrames: List<Identifier>? = null,
    // 图标贴图实际尺寸与缩放（6×8 性别图标用 iconTexW=6/iconTexH=8/iconScale=1.5）
    // ⚠ 这三个是 **var**（2026-10-16 起）：ID 卡入口的图标要**跟随服主换的背景图**——
    //   那是一张运行时才知道尺寸的动态纹理，构造时定不下来，只能每帧同步。
    var iconTexW: Int = 24,
    var iconTexH: Int = 24,
    var iconScale: Float = 0.5f,
    /**
     * **图标叠层**（> 1 才生效）：把同一张图叠这么多层、每层往**右上**错开 [iconLayerStep] 像素
     * ——「一叠卡片」的观感。整组（含错开的余量）在按钮里居中 ⇒ 看着最完整的那张偏左下、
     * 最靠右上那张压在最底下。管理面板的「玩家总览」入口用它画一叠 ID 卡。
     * ⚠ 与并排的 [iconLeft2] 互斥（叠层时后者不画）。⚠ 与物品图标 [iconStack] 无关（那个是 ItemStack）。
     */
    var iconLayers: Int = 1,
    /** 叠层往右上错开的像素（仅 [iconLayers] > 1 时生效） */
    var iconLayerStep: Int = 3,
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

        // 物品图标（iconStack）：整格居中画一个物品栈，与贴图图标互斥
        // ⚠ 缩放走 matrices —— `drawItem` 恒按 16×16 画、没有尺寸参数（与 CurrencyDisplay 同款手法）
        val stackIcon = iconStack?.takeIf { !it.isEmpty }
        if (stackIcon != null) {
            val s = iconStackSize / 16f
            context.matrices.push()
            context.matrices.translate(
                (x + (width - iconStackSize) / 2).toDouble(),
                (y + (height - iconStackSize) / 2).toDouble(),
                0.0
            )
            context.matrices.scale(s, s, 1f)
            context.drawItem(stackIcon, 0, 0)
            context.matrices.pop()
        }

        // 可选图标（可双图标并排、可叠层，两种都整体居中）
        // 动画图标帧（每 100ms 切一帧）优先于静态 iconLeft；物品图标优先于两者
        val leftIcon = if (stackIcon == null) {
            iconFrames?.let { frames ->
                frames[((System.currentTimeMillis() / 100) % frames.size).toInt()]
            } ?: iconLeft
        } else null
        if (leftIcon != null) {
            val iconW = (iconTexW * iconScale).toInt()
            val iconH = (iconTexH * iconScale).toInt()
            fun drawIcon(id: Identifier, ix: Int, iy: Int) {
                context.matrices.push()
                context.matrices.translate(ix.toDouble(), iy.toDouble(), 0.0)
                context.matrices.scale(iconScale, iconScale, 1f)
                // 禁用态图标与文字同样置灰（文字 0xA0A0A0 = 160/255 ≈ 0.627）
                if (!active) RenderSystem.setShaderColor(0.627f, 0.627f, 0.627f, 1f)
                context.drawTexture(id, 0, 0, 0f, 0f, iconTexW, iconTexH, iconTexW, iconTexH)
                if (!active) RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
                context.matrices.pop()
            }
            if (iconLayers > 1) {
                // 叠层：整组（图 + 错开的余量）居中；**从最右上那张开始画**，后画的盖住先画的
                // ⇒ 下标 0 那张最后画、看着压在最上层（位置偏左下）
                val extra = (iconLayers - 1) * iconLayerStep
                val stackX = x + (width - iconW - extra) / 2
                val stackY = y + (height - iconH - extra) / 2
                for (i in iconLayers - 1 downTo 0) {
                    drawIcon(leftIcon, stackX + i * iconLayerStep, stackY + extra - i * iconLayerStep)
                }
            } else {
                val totalW = iconW * (if (iconLeft2 != null) 2 else 1) + (if (iconLeft2 != null) 1 else 0)
                val startX = x + (width - totalW) / 2
                val startY = y + (height - iconH) / 2
                drawIcon(leftIcon, startX, startY)
                iconLeft2?.let { drawIcon(it, startX + iconW + 1, startY) }
            }
        }

        val font = MinecraftClient.getInstance().textRenderer
        val color = if (active) textColor else 0xA0A0A0
        val iconW = (iconTexW * iconScale).toInt()
        val totalIconW = if (iconLeft != null) iconW * (if (iconLeft2 != null) 2 else 1) + (if (iconLeft2 != null) 1 else 0) else 0
        val iconSpace = if (iconLeft != null) 5 + totalIconW + 4 else 0
        val textY = y + (height - 8) / 2
        // ⚠ 原先这里有一段「把 `● ` 前缀无阴影绘制」的特判 —— 那是 `TextUtil.selectedText` 时代的产物。
        //   选中项改用按钮自己的选中态（背景第三段）后，那个函数已被删除，这段也一并删掉。
        val textX = x + iconSpace + (width - iconSpace - font.getWidth(message)) / 2
        context.drawTextWithShadow(font, message, textX, textY, color)
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

/**
 * 让这颗按钮的图标**跟随服主换的 ID 卡背景图**：没配 / 还没到货 = 内置那张，尺寸按 [targetWidth] 等比缩。
 * ⚠ 缩的是**宽度**（很宽的图会跟着变矮 —— 「整张直接缩」的必然结果，用户已知）。
 *
 * 幂等（只是给几个字段赋值）⇒ 放心每帧调 —— 两个入口都在自己的 `render` 开头调一次。
 * ⚠ 入口界面的 ID 卡按钮与管理面板的「玩家总览」按钮**共用这一处**：
 *   各自算一遍「当前是哪张图、缩成多大」，迟早会不同步（2026-10-17 收口）。
 */
fun NineSliceButton.syncIdCardIcon(targetWidth: Int) {
    val entry = IdCardBackgroundTexture.get()
    val w = entry?.width ?: IdCardBackgroundTexture.BUILTIN_WIDTH
    iconLeft = entry?.id ?: IdCardBackgroundTexture.BUILTIN_TEX
    iconTexW = w
    iconTexH = entry?.height ?: IdCardBackgroundTexture.BUILTIN_HEIGHT
    iconScale = targetWidth.toFloat() / w
}
