package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.CloseAnimation
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier

const val NINE_SLICE_BORDER = 6
const val NINE_SLICE_TEX_W = 40
const val NINE_SLICE_STATE_H = 40

// Minecraft 标准金色（Formatting.GOLD 色值），用于闪光按钮等特殊高亮
const val GOLD_COLOR = 0xFFAA00

val ROW_BACKGROUND_TEXTURE = Identifier.of("cobblemarket", "textures/gui/row_background.png")
const val ROW_BACKGROUND_TEX_H = 120

val DIALOG_BACKGROUND_TEXTURE = Identifier.of("cobblemarket", "textures/gui/dialog_background.png")
const val DIALOG_BACKGROUND_TEX_H = 40

val BUTTON_TEXTURE = Identifier.of("cobblemarket", "textures/gui/button_9slice.png")
/** 按钮贴图总高（三段：普通 / 悬停 / 选中）—— 手动 drawNineSlice 画按钮时要与它一致 */
const val BUTTON_TEX_H = 120

/**
 * **入口 / 菜单类按钮**的专用贴图：与 [BUTTON_TEXTURE] 同款，但**只有「默认 + 悬停」两段**
 * （比它少一段 40px 的选中态 —— 这类按钮也不需要选中态）。
 * `NineSliceButton` 按 `texH / NINE_SLICE_STATE_H - 1` 算出最大态，两段贴图会自动把选中态退回悬停态。
 *
 * 用途（2026-09-24 用户定）：市场入口的**求购单 / 喵喵银行 / 设置**，管理面板的**权限管理 / 市场开关 /
 * 服务器配置**，以及分享按钮 —— 让这类"入口型"按钮与列表行里的操作按钮在视觉上分开。
 */
val BUTTON_TEXTURE_S = Identifier.of("cobblemarket", "textures/gui/button_9slice_s.png")
/** [BUTTON_TEXTURE_S] 的总高（两段） */
const val BUTTON_TEX_H_S = 80

/** 弹窗全屏半透明遮罩：关闭动画进行中跳过绘制——按 E/Esc 后游戏画面立刻变亮，遮罩不随界面上缩 */
fun drawScreenDimMask(context: DrawContext, width: Int, height: Int) {
    if (CloseAnimation.isActive()) return
    context.fill(0, 0, width, height, 0xC0000000.toInt())
}

fun drawNineSlice(
    context: DrawContext,
    texture: Identifier,
    x: Int, y: Int, width: Int, height: Int,
    state: Int,
    texH: Int
) {
    val b = NINE_SLICE_BORDER
    val vOffset = state * NINE_SLICE_STATE_H
    val right = NINE_SLICE_TEX_W - b
    val srcEdgeW = NINE_SLICE_TEX_W - 2 * b
    val srcEdgeH = NINE_SLICE_STATE_H - 2 * b
    val edgeW = (width - 2 * b).coerceAtLeast(0)
    val edgeH = (height - 2 * b).coerceAtLeast(0)
    val matrices = context.matrices

    fun blit(dstX: Int, dstY: Int, u: Int, v: Int, sw: Int, sh: Int) {
        context.drawTexture(texture, dstX, dstY, u.toFloat(), (v + vOffset).toFloat(), sw, sh, NINE_SLICE_TEX_W, texH)
    }

    // corners
    blit(x, y, 0, 0, b, b)
    blit(x + width - b, y, right, 0, b, b)
    blit(x, y + height - b, 0, right, b, b)
    blit(x + width - b, y + height - b, right, right, b, b)

    // top edge
    matrices.push()
    matrices.translate((x + b).toDouble(), y.toDouble(), 0.0)
    matrices.scale(edgeW.toFloat() / srcEdgeW.toFloat(), 1f, 1f)
    blit(0, 0, b, 0, srcEdgeW, b)
    matrices.pop()

    // bottom edge
    matrices.push()
    matrices.translate((x + b).toDouble(), (y + height - b).toDouble(), 0.0)
    matrices.scale(edgeW.toFloat() / srcEdgeW.toFloat(), 1f, 1f)
    blit(0, 0, b, right, srcEdgeW, b)
    matrices.pop()

    // left edge
    matrices.push()
    matrices.translate(x.toDouble(), (y + b).toDouble(), 0.0)
    matrices.scale(1f, edgeH.toFloat() / srcEdgeH.toFloat(), 1f)
    blit(0, 0, 0, b, b, srcEdgeH)
    matrices.pop()

    // right edge
    matrices.push()
    matrices.translate((x + width - b).toDouble(), (y + b).toDouble(), 0.0)
    matrices.scale(1f, edgeH.toFloat() / srcEdgeH.toFloat(), 1f)
    blit(0, 0, right, b, b, srcEdgeH)
    matrices.pop()

    // center
    matrices.push()
    matrices.translate((x + b).toDouble(), (y + b).toDouble(), 0.0)
    matrices.scale(edgeW.toFloat() / srcEdgeW.toFloat(), edgeH.toFloat() / srcEdgeH.toFloat(), 1f)
    blit(0, 0, b, b, srcEdgeW, srcEdgeH)
    matrices.pop()
}
