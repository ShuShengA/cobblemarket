package com.shusheng.cobblemarket.screen

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
const val BUTTON_TEX_H = 80

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
