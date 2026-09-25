package com.shusheng.cobblemarket.screen

import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 点券系列界面（**RP 商城 / 购买 RP**）共用的背景与几何换算 —— 两个界面共用同一张背景图
 * （方案拍板），绘制与换算写两份必然漂移。
 *
 * 背景渲染：`points_market_background.png` **等比缩放 + 居中 + 四周留 4%**。
 * ⚠ **不用九宫格**：四条边的中段都有红色装饰条，九宫格拉伸会把它们拉长变形（越宽的屏拉得越长）。
 * ⚠ **缩放必须走 matrices**：`drawTexture` 的 width/height 是「从源图采样的区域尺寸」，
 *   直接传缩放后的目标尺寸只会把原图左上角那一块抽出来放大（2026-09-18 实测踩到）。
 */
object PointsMarketBackground {

    val TEXTURE = Identifier.of("cobblemarket", "textures/gui/points_market_background.png")
    const val TEX_W = 1280
    const val TEX_H = 720

    /** 四周留白：给游戏画面留一圈（4%） */
    private const val MARGIN_RATIO = 0.04f

    /**
     * 原图边框厚度：贴边控件（返回按钮、标题）要避开它，别压到装饰条上。
     * 实测内容区包围盒：左右 59px、顶部约 85px —— 取 90 统一兜住最厚的一边。
     */
    const val TEX_BORDER = 90

    /** 背景等比缩放后的矩形（居中） */
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    fun scale(width: Int, height: Int): Float {
        val availW = width * (1f - MARGIN_RATIO * 2f)
        val availH = height * (1f - MARGIN_RATIO * 2f)
        return min(availW / TEX_W, availH / TEX_H)
    }

    fun rect(width: Int, height: Int): Rect {
        val s = scale(width, height)
        val w = (TEX_W * s).roundToInt()
        val h = (TEX_H * s).roundToInt()
        return Rect((width - w) / 2, (height - h) / 2, w, h)
    }

    /**
     * **原图坐标（1280×720 口径）→ 当前缩放下的逻辑像素**。
     * 用户给的尺寸都是照原图说的（「与顶部 28 像素」），换算只走这一个口子，
     * 免得各处各写一遍缩放、改一个位置要满文件找。
     */
    fun texPx(value: Int, width: Int, height: Int): Int = (value * scale(width, height)).roundToInt()

    /** 内容区左上角相对背景的偏移（边框减掉后的空白区起点） */
    fun borderInset(width: Int, height: Int): Int = texPx(TEX_BORDER, width, height)

    fun draw(context: DrawContext, width: Int, height: Int) {
        val bg = rect(width, height)
        val s = scale(width, height)
        context.matrices.push()
        context.matrices.translate(bg.x.toDouble(), bg.y.toDouble(), 0.0)
        context.matrices.scale(s, s, 1f)
        context.drawTexture(TEXTURE, 0, 0, 0f, 0f, TEX_W, TEX_H, TEX_W, TEX_H)
        context.matrices.pop()
    }
}
