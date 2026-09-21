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
 * 「上架」按钮的图标（`sell.png`，同样 48×48 源图）。
 * 上架按钮用它替掉原来的「上架」二字后能窄一大截，腾出的位置正好够放货币切换按钮。
 */
val SELL_ICON_TEXTURE: Identifier = Identifier.of("cobblemarket", "textures/gui/sell.png")

/** [SELL_ICON_TEXTURE] 的源图边长 */
const val SELL_ICON_SRC = 48

/**
 * 行内货币单位的显示边长（**8×8，与 8px 的文字行高齐平**）。
 *
 * 聊天里那个字形也是这个尺寸（`font/points.json` 的 `height: 8`）—— 两处必须一致，
 * 否则同一句话在聊天里和 HUD 上的图标大小对不上。
 */
const val POINTS_SYMBOL_INLINE_SIZE = 8

/**
 * **按钮**里那枚图标的显示边长。
 *
 * 比行内单位（8）大一圈：按钮有 20px 的高度撑着，图标还按 8 画的话四周空一大圈，
 * 看着就是「按钮挺大、图挺小」（2026-09-19 用户指出的）。行内单位仍走 [POINTS_SYMBOL_INLINE_SIZE]。
 */
const val CURRENCY_BUTTON_ICON_SIZE = 12

/**
 * **上架按钮**里 [SELL_ICON_TEXTURE] 的显示边长。
 *
 * ⚠ **不跟 [CURRENCY_BUTTON_ICON_SIZE] 共用** —— 两张源图的透明留白差得多：
 * [POINTS_SYMBOL_TEXTURE] 内容几乎铺满 48×48，而 `sell.png` 纵向只占 35px（上 6 下 7 全透明），
 * 同样按 12 画出来，红块只有 **12×8.75**，比旁边那枚货币符号明显小一圈（2026-09-21 用户指出）。
 *
 * 16 = 按钮高 20 减上下各 2px（同市场那批图标按钮的惯例）→ 红块 ≈**16×11.7**，
 * 与货币符号（11.5×12）视觉齐平。再大就撑出 20 高的按钮了。
 */
const val SELL_BUTTON_ICON_SIZE = 16

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
