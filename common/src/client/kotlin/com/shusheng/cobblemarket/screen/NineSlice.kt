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
    // ⚠ **遮罩一画，下层的货币图标就要停**（2026-09-29）：
    //   货币图标是 3D 模型，**不响应这层半透明黑**（遮罩压暗文字/贴图，压不住 3D）——
    //   于是下层图标比周围压暗的文字亮一截，看着像"高亮浮在弹窗上"。
    //   遮罩在 `renderBackground` 里画、早于 `render` 的下层内容，所以在这里置位**同一帧就生效**；
    //   复位由 `ScreenMixin` 每帧 HEAD 负责（`CurrencyDisplayKt.setSuppressCurrencyIcons(false)`），
    //   界面自己不用管 —— 这样全项目「只要有弹窗遮罩」的地方都自动覆盖，不必逐个界面接。
    com.shusheng.cobblemarket.client.suppressCurrencyIcons = true
    context.fill(0, 0, width, height, 0xC0000000.toInt())
}

val POKEMON_SLOT_TEXTURE = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")

/** 槽位贴图是 **86×86** 的方图（⚠ 与文件真实尺寸对齐，别按印象写） */
const val POKEMON_SLOT_TEX_SIZE = 86

/**
 * 槽位贴图的**大分辨率版**（256×256）—— 只给 RP 商城的「添加 / 购买精灵」两个弹窗用。
 *
 * ⚠ **为什么单独一张**：那两个弹窗的预览槽比列表图标大得多（`editPreviewSize()` 量级），
 *   86×86 那张放大会糊。⚠ **别铺到全项目** —— 256 的图加载与显存都是小图的十倍上下，
 *   列表行 / 格子那些小尺寸位置用不着。
 */
val POKEMON_SLOT_BIG_TEXTURE = Identifier.of("cobblemarket", "textures/gui/pokemon_slot_big.png")

/** 大图是 256×256 */
const val POKEMON_SLOT_BIG_TEX_SIZE = 256

/**
 * 精灵图标的**槽背景**（方图缩放到 [size]，左上角对齐）。
 *
 * 列表行 / 弹窗预览 / 商城格子里的精灵图标都该垫这一张 —— 模型直接浮在格子上会显得没有底座。
 *
 * ⚠ 缩放必须走 matrices：`drawTexture` 的 width/height 是**采样区域**，直接传缩放后的尺寸
 * 只会把原图左上角那一块抽出来放大（项目里踩过的 `reference-gui-drawtexture-scale`）。
 */
fun drawPokemonSlot(context: DrawContext, x: Int, y: Int, size: Int) =
    drawSlotTexture(context, POKEMON_SLOT_TEXTURE, POKEMON_SLOT_TEX_SIZE, x, y, size)

/** [drawPokemonSlot] 的**大图版**（256×256 那张）—— 只给 RP 商城的两个精灵弹窗，见 [POKEMON_SLOT_BIG_TEXTURE] */
fun drawPokemonSlotBig(context: DrawContext, x: Int, y: Int, size: Int) =
    drawSlotTexture(context, POKEMON_SLOT_BIG_TEXTURE, POKEMON_SLOT_BIG_TEX_SIZE, x, y, size)

private fun drawSlotTexture(
    context: DrawContext, texture: Identifier, texSize: Int,
    x: Int, y: Int, size: Int,
) {
    val scale = size / texSize.toFloat()
    context.matrices.push()
    context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
    context.matrices.scale(scale, scale, 1f)
    context.drawTexture(texture, 0, 0, 0f, 0f, texSize, texSize, texSize, texSize)
    context.matrices.pop()
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
