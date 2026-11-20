package com.shusheng.cobblemarket.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * **照片相框**（2026-11-20）：聊天悬停预览与拍照确认界面共用同一份几何与画法。
 *
 * `photo_frame.png` 是 640×360 的整体图，中间 68,68 起是 504×225 的**透明内框**：
 * 照片先画进内框（按内框比例缩放铺满），再把整张相框压上去 —— 边框不透明、内框透出照片。
 *
 * ⚠ 几何**只在这里写一份**：聊天预览（`ChatObjectPreview`）与确认界面（`PhotoConfirmScreen`）
 *   两处都调 [draw] / [heightForWidth]，改相框尺寸只改这里。
 */
object PhotoFrame {

    /** 相框图（用户 2026-11-20 提供） */
    val TEXTURE = Identifier.of("cobblemarket", "textures/gui/background/photo_frame.png")

    const val FRAME_W = 640
    const val FRAME_H = 360

    /**
     * 内框（照片区）在相框源图里的位置与尺寸 —— 实测自透明像素区域。
     * ⚠ 2026-11-20 用户把边框收窄了一版：68,68 / 504×225 → 24,24 / 592×312（比例 1.897:1）。
     *   换图后必须重新量一下这四个数（否则照片会歪 / 被边框盖住）。
     */
    const val INNER_X = 24
    const val INNER_Y = 24
    const val INNER_W = 592
    const val INNER_H = 312

    /** 内框宽高比（照片按它裁剪） */
    val INNER_ASPECT: Double = INNER_W.toDouble() / INNER_H

    /** 给定显示宽度时的相框显示高度 */
    fun heightForWidth(width: Float): Float = width * FRAME_H / FRAME_W

    /**
     * 画相框 + 照片。[left] / [top] 是**相框整图**左上角，[width] 是相框显示宽度。
     * [photoTexture] 为空时只画空相框（调用方自己补「照片已失效」之类的提示）。
     */
    fun draw(
        context: DrawContext,
        left: Float,
        top: Float,
        width: Float,
        photoTexture: Identifier?,
        photoTexW: Int,
        photoTexH: Int,
    ) {
        val scale = width / FRAME_W

        if (photoTexture != null && photoTexW > 0 && photoTexH > 0) {
            val innerW = INNER_W * scale
            val innerH = INNER_H * scale
            // ⚠ drawTexture 的 w/h 是采样区域 ⇒ 用 matrices 缩放到内框尺寸
            context.matrices.push()
            context.matrices.translate(
                (left + INNER_X * scale).toDouble(),
                (top + INNER_Y * scale).toDouble(),
                0.0
            )
            context.matrices.scale(innerW / photoTexW, innerH / photoTexH, 1f)
            context.drawTexture(
                photoTexture, 0, 0, 0f, 0f,
                photoTexW, photoTexH, photoTexW, photoTexH
            )
            context.matrices.pop()
        }

        // 相框整张画在照片之上（内框是透明像素 ⇒ 露出照片）
        context.matrices.push()
        context.matrices.translate(left.toDouble(), top.toDouble(), 0.0)
        context.matrices.scale(scale, scale, 1f)
        context.drawTexture(
            TEXTURE, 0, 0, 0f, 0f,
            FRAME_W, FRAME_H, FRAME_W, FRAME_H
        )
        context.matrices.pop()
    }

    /**
     * 在相框照片区域的**右下角**叠加拍摄位置（X / Y / Z 三个坐标，2026-11-20 用户要求）。
     * **不加底色**（用户 2026-11-20 要求去掉黑底）：直接白字 + 阴影，靠阴影在明暗画面上保持可读。
     * 确认界面预览与聊天悬停共用这一处。
     */
    fun drawLocation(
        context: DrawContext,
        left: Float,
        top: Float,
        width: Float,
        x: Int,
        y: Int,
        z: Int,
    ) {
        val scale = width / FRAME_W
        val innerRight = left + (INNER_X + INNER_W) * scale
        val innerBottom = top + (INNER_Y + INNER_H) * scale
        val font = MinecraftClient.getInstance().textRenderer
        val text = Text.literal("X: $x  Y: $y  Z: $z")
        val textX = (innerRight - LOCATION_MARGIN - font.getWidth(text)).toInt()
        val textY = (innerBottom - LOCATION_MARGIN - font.fontHeight).toInt()
        context.drawTextWithShadow(font, text, textX, textY, 0xFFFFFF)
    }

    /** 坐标框距照片区域边缘的距离 */
    private const val LOCATION_MARGIN = 6
}
