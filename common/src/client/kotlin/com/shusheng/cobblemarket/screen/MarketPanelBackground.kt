package com.shusheng.cobblemarket.screen

import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier

/**
 * 市场面板的统一背景（顶部 / 中段 / 底段三张拼接）。
 *
 * 面板几何全部算在这里，界面里不要再写死 —— 改一处全局生效。
 *
 * 顶部那张带一个「身份位」：红条正中 32x32 的方块，放该界面的标识图标。
 * 图标走 topIcon 参数传进来，约定用各界面本来就有的 48x48 按钮图标（如 auction_gavel_left.png），
 * 屏幕上显示 16x16 —— **不为顶部单独存一份图标贴图**，否则每加一个界面都要重画一张 640x32 的顶图。
 * 不传 topIcon 就是纯三张拼接（大多数界面）。
 */
object MarketPanelBackground {
    private val TOP = Identifier.of("cobblemarket", "textures/gui/market_panel_top.png")
    private val MIDDLE = Identifier.of("cobblemarket", "textures/gui/market_panel_middle.png")
    private val BOTTOM = Identifier.of("cobblemarket", "textures/gui/market_panel_bottom.png")

    /**
     * 面板宽度。贴图 640 宽按 panelWidth/640 缩放，默认 320 宽、左缘 width/2 - 160。
     * ⚠ 别写成 leftX()（那是行宽 300 的左缘），会整体错位，见 docs/gui_pitfalls.md。
     */
    const val WIDTH = 320

    /** 面板左缘（多数界面画列表/按钮要用）。传 [panelWidth] 时必须与 [draw] 一致 */
    fun left(screenWidth: Int, panelWidth: Int = WIDTH) = screenWidth / 2 - panelWidth / 2

    /**
     * 右侧**筛选面板**的宽度（2026-09-23 起）：主背景 320 的一半。
     * 为什么是 160：筛选按钮上最长的是英文特性名（`Neutralizing Gas` = 96px），面板内缩后
     * 可用 152px ⇒ 放得下、不用截断。总宽 320 + 4 + 160 = 484，1080p@缩放3（逻辑 640）没问题。
     */
    const val FILTER_PANEL_WIDTH = 160

    /** 主面板与筛选面板之间的间隙 */
    const val FILTER_PANEL_GAP = 4

    /**
     * 筛选面板左缘 = **主面板右缘 + 间隙**。
     *
     * ⚠ **主面板保持居中不动**（仍然走 [left]，即 `screenWidth/2 - 160`），筛选面板占用它右侧那块
     *   本来就空着的区域 —— 这样主面板里所有控件的坐标一个都不用动。
     *   （2026-09-23 曾先做成"两个面板整体居中"、主面板左移 82px，用户问「为什么把主面板左移了」
     *    后改回：他习惯的界面基准不该被我们擅自挪。）
     */
    fun filterLeft(screenWidth: Int) = left(screenWidth, WIDTH) + WIDTH + FILTER_PANEL_GAP

    /**
     * 筛选面板的**实际宽度**：默认 [FILTER_PANEL_WIDTH]，屏不够宽时自动收窄，保证不戳出屏幕右缘。
     * ⚠ 1080p + 缩放 3（逻辑屏 640）算出来是 **156** —— 这是本布局最紧的一档（主面板右缘 480，
     *   右边只剩 160，再留 4px 间隙就超了）；比这更窄的屏该考虑别的排法，别再往下压按钮宽度。
     */
    fun filterWidth(screenWidth: Int) = minOf(FILTER_PANEL_WIDTH, screenWidth - filterLeft(screenWidth))

    private const val PANEL_TOP = 2
    private const val BOTTOM_MARGIN = 32
    private const val SLICE_H = 16

    /** 顶部身份位显示尺寸（贴图上是 32x32，这里固定 16 保证像素锐利） */
    private const val ICON_DISPLAY = 16
    /** 图标源图边长。各界面按钮图标统一 48x48，换成别的尺寸要同步改这里 */
    private const val ICON_TEX = 48

    /**
     * @param topIcon 顶部身份位图标（48x48 源图），null = 不画
     * @param panelWidth 面板宽度，默认 320；求购单界面是 296
     * @param leftOverride 面板左缘覆盖值（默认按屏宽居中）。**并排两个面板时筛选用它指定位置** ——
     *   主面板 + 右侧筛选面板是「整体居中」（总宽 484 = 320 + 4 + 160），单独居中会把筛选面板画到屏幕中央
     */
    fun draw(
        context: DrawContext,
        screenWidth: Int,
        screenHeight: Int,
        topIcon: Identifier? = null,
        panelWidth: Int = WIDTH,
        leftOverride: Int? = null,
        /** 面板高度覆盖值（默认铺到屏幕底边往上 [BOTTOM_MARGIN]）——**由内容定高的面板**用它，
         *  如右侧筛选面板：内容到 Spd 输入框为止，不必和主面板一样高（2026-09-23 用户要求） */
        heightOverride: Int? = null,
    ) {
        val panelLeft = leftOverride ?: left(screenWidth, panelWidth)
        val panelBottom = heightOverride?.let { PANEL_TOP + it } ?: (screenHeight - BOTTOM_MARGIN)

        drawSlice(context, TOP, panelLeft, PANEL_TOP, panelWidth)
        var y = PANEL_TOP + SLICE_H
        while (y < panelBottom - SLICE_H) {
            drawSlice(context, MIDDLE, panelLeft, y, panelWidth, minOf(SLICE_H, panelBottom - SLICE_H - y))
            y += SLICE_H
        }
        drawSlice(context, BOTTOM, panelLeft, panelBottom - SLICE_H, panelWidth)

        if (topIcon != null) drawTopIcon(context, topIcon, panelLeft + panelWidth / 2)
    }

    private fun drawSlice(
        context: DrawContext,
        texture: Identifier,
        x: Int,
        y: Int,
        panelWidth: Int,
        sliceH: Int = SLICE_H,
    ) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(panelWidth / 640f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    /** [panelCenterX] = 面板左缘 + 面板宽/2 —— 身份位水平居中于面板 */
    private fun drawTopIcon(context: DrawContext, texture: Identifier, panelCenterX: Int) {
        val scale = ICON_DISPLAY.toFloat() / ICON_TEX
        context.matrices.push()
        context.matrices.translate((panelCenterX - ICON_DISPLAY / 2).toDouble(), PANEL_TOP.toDouble(), 0.0)
        context.matrices.scale(scale, scale, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, ICON_TEX, ICON_TEX, ICON_TEX, ICON_TEX)
        context.matrices.pop()
    }
}
