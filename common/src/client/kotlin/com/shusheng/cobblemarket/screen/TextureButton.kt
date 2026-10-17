package com.shusheng.cobblemarket.screen

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.tooltip.Tooltip
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
    private val iconRight: Identifier? = null,
    // 动画图标帧列表（每 100ms 切一帧，照皮卡丘动画帧率）；非空时替代 iconLeft
    private val iconFrames: List<Identifier>? = null,
    // 图标贴图实际尺寸与显示尺寸（默认 24 贴图缩到 12；48×48 动画帧用 iconTexSize=48/iconDisplaySize=18）
    private val iconTexSize: Int = 24,
    private val iconDisplaySize: Int = 12,
    // 可选悬停词条（图标化按钮构造即挂上，替代文字说明）
    tooltip: Text? = null,
) : ButtonWidget(x, y, width, height, message, onPress, ButtonWidget.DEFAULT_NARRATION_SUPPLIER) {

    init {
        if (tooltip != null) setTooltip(Tooltip.of(tooltip))
    }

    /**
     * 置灰视觉（不影响 active 与点击）：渲染尾部盖半透明黑遮罩，与 `NineSliceButton.dimmed` 同款。
     * 用于「无权限但点击要给提示」的按钮 —— active=false 会吞掉 onPress，做不了点击提示。
     */
    var dimmed: Boolean = false

    /**
     * **只藏文字、保留底图与可点性**：用于「被浮层压住一半」的按钮 ——
     * 浮层盖不住整个按钮时，半截露在覆盖区外的文字比没有文字更难看（见 MarketEntryScreen 的展示子按钮）。
     */
    var hideText: Boolean = false

    /**
     * 被浮层压住的矩形（屏幕坐标 `[left, top, right, bottom]`，null = 没被压住）：
     * 鼠标落在其中时本按钮**既不显示悬停态、也不接管点击**，把这一块整个让给上层浮层。
     *
     * ⚠ 拦的是 [clicked] 与 [isHovered]，**不是 `isMouseOver`** —— 反编译原版 `ClickableWidget` 可见：
     * `render` 是 final，里面直接按坐标给 `hovered` 赋值；`mouseClicked` 调的也是 `clicked(...)`。
     * 这两条路径都不经过 `isMouseOver`，重写它等于没写。
     */
    var blockedArea: IntArray? = null

    /** 本次渲染时鼠标是否落在 [blockedArea] 内：悬停态与 tooltip 都要据此让路 */
    private var hoverBlocked = false

    private fun isBlocked(mouseX: Double, mouseY: Double): Boolean {
        val area = blockedArea ?: return false
        return mouseX >= area[0] && mouseX < area[2] && mouseY >= area[1] && mouseY < area[3]
    }

    override fun clicked(mouseX: Double, mouseY: Double): Boolean =
        !isBlocked(mouseX, mouseY) && super.clicked(mouseX, mouseY)

    override fun isHovered(): Boolean = !hoverBlocked && super.isHovered()

    private val texture = Identifier.of("cobblemarket", "textures/gui/button.png")
    private val textureWidth = 320
    private val textureHeight = 96
    private val stateHeight = 48

    override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 先按本帧鼠标位置刷新让路标记，再取悬停态：原版的 tooltip 渲染排在 renderWidget **之后**，
        // 所以这里更新完，同一帧里 isHovered() 对按钮自己和 tooltip 都已经是对的
        hoverBlocked = isBlocked(mouseX.toDouble(), mouseY.toDouble())
        val hovered = isHovered
        val vOffset = if (hovered) stateHeight else 0

        // Texture is 2x resolution; scale down to the button's logical size
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(width / 320f, height / 48f, 1f)
        context.drawTexture(texture, 0, 0, 0f, vOffset.toFloat(), 320, stateHeight, textureWidth, textureHeight)
        context.matrices.pop()

        // 当前左图标：动画帧（按时间切换）优先，否则静态 iconLeft
        val currentIcon = iconFrames?.let { frames ->
            frames[((System.currentTimeMillis() / 100) % frames.size).toInt()]
        } ?: iconLeft
        val iconY = y + (height - iconDisplaySize) / 2
        if (currentIcon != null) {
            // 纯图标按钮（空文字）图标水平居中；有文字时图标靠左、文字在剩余空间居中
            val iconX = if (message.string.isEmpty()) x + (width - iconDisplaySize) / 2 else x + 5
            drawIcon(context, currentIcon, iconX, iconY)
        }
        if (iconRight != null) {
            drawIcon(context, iconRight, x + width - 5 - iconDisplaySize, iconY)
        }

        // Draw centered text（有图标时在图标右侧的剩余空间内居中，长文案不压图标）
        val font = MinecraftClient.getInstance().textRenderer
        val iconSpace = if (currentIcon != null) 5 + iconDisplaySize + 4 else 0
        val textX = x + iconSpace + (width - iconSpace - font.getWidth(message)) / 2
        val textY = y + (height - 8) / 2
        if (!hideText) context.drawTextWithShadow(font, message, textX, textY, 0xFFFFFF)

        // 置灰遮罩（视觉置灰但保留可点击性，见 dimmed 注释）
        if (dimmed) {
            context.fill(x, y, x + width, y + height, 0x66000000)
        }
    }

    private fun drawIcon(context: DrawContext, icon: Identifier, x: Int, y: Int) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(iconDisplaySize / iconTexSize.toFloat(), iconDisplaySize / iconTexSize.toFloat(), 1f)
        context.drawTexture(icon, 0, 0, 0f, 0f, iconTexSize, iconTexSize, iconTexSize, iconTexSize)
        context.matrices.pop()
    }

    override fun playDownSound(soundManager: SoundManager) {
        soundManager.play(PositionedSoundInstance.master(
            SoundEvent.of(Identifier.of("cobblemarket", "button_click")),
            1.0f
        ))
    }
}
