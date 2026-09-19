package com.shusheng.cobblemarket.client

import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier

/**
 * 金额与它后面那个单位之间的间隔宽度 —— 就是**一个空格字符**的宽度。
 *
 * 主行的「1000 ₽」中间那个缝其实就是空格；图标当单位的地方（RP）也得按同一个宽度留，
 * 所以**别写死 2 或 4 像素**：字体一换，写死的间距就和主行对不上了。
 */
fun unitGap(textRenderer: TextRenderer): Int = textRenderer.getWidth(" ")

/**
 * 货币图标渲染的**唯一入口** —— 界面上所有「钱」的图标都从这儿取，别在各自界面里再写一遍字面量。
 *
 * 为什么收口：行内符号图这一张 `points_symbol.png` 同时被四处用（余额 HUD 的单位、市场入口按钮图标、
 * 管理端充值订单角标、聊天字形的源图），各写各的 Identifier 迟早写歪一个 —— 尤其
 * **服主要用资源包换图**（见方案「RP 图的自定义」），路径必须全项目一致。
 *
 * 两张图的分工（方案「图标渲染」，2026-09-17 拍板是**两张不同的图**）：
 * - [POINTS_SYMBOL_TEXTURE] 行内货币符号，源图 **48×48** 方图；各处按需缩到 8×8 ~ 18×18。
 *   **它同时是聊天里那个字形的源图** —— `assets/cobblemarket/font/points.json` 的 bitmap 指向它，
 *   所以服主换这一张，聊天 / HUD / 列表行的图标全都跟着变。
 * - [POINTS_ICON_TEXTURE] 充值界面那张大的，源图 **256×160**（1.6:1，像点卡），显示 128×80。
 */
val POINTS_SYMBOL_TEXTURE: Identifier = Identifier.of("cobblemarket", "textures/gui/points_symbol.png")

/** 充值界面图标（档位行 / 订单弹窗）。行内符号用的是上面那张，别混 */
val POINTS_ICON_TEXTURE: Identifier = Identifier.of("cobblemarket", "textures/gui/points_icon.png")

/** [POINTS_SYMBOL_TEXTURE] 的源图边长（正方形）。缩放比 = 目标尺寸 / 它 */
const val POINTS_SYMBOL_SRC = 48

/**
 * 行内货币单位的显示边长（**8×8，与 8px 的文字行高齐平**）。
 *
 * 聊天里那个字形也是这个尺寸（`font/points.json` 的 `height: 8`）—— 两处必须一致，
 * 否则同一句话在聊天里和 HUD 上的图标大小对不上。
 */
const val POINTS_SYMBOL_INLINE_SIZE = 8

/**
 * 画行内货币符号（余额 HUD 的 RP 行、列表行、悬停窗都走它）。
 *
 * ⚠ **缩放必须走 matrices**：`drawTexture` 的 width/height 是**从源图采样的区域尺寸**，
 * 不是目标显示尺寸 —— 直接喂目标尺寸（比如 8）只会画出源图左上角那一小块。
 * 源图恒取整张 [POINTS_SYMBOL_SRC]，靠 matrices 缩到 [size]。
 */
fun drawPointsSymbol(context: DrawContext, x: Int, y: Int, size: Int) {
    val scale = size / POINTS_SYMBOL_SRC.toFloat()
    context.matrices.push()
    context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
    context.matrices.scale(scale, scale, 1f)
    context.drawTexture(
        POINTS_SYMBOL_TEXTURE, 0, 0, 0f, 0f,
        POINTS_SYMBOL_SRC, POINTS_SYMBOL_SRC, POINTS_SYMBOL_SRC, POINTS_SYMBOL_SRC
    )
    context.matrices.pop()
}
