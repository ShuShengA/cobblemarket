package com.shusheng.cobblemarket.screen

import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * 弹窗几何 —— **宽度按内容、高度按内容**，两者都自动钳进屏幕；装不下时给出「内容区可滚动」的参数。
 *
 * ## 为什么要有它
 *
 * 此前每个弹窗各写各的 `minOf(300, width - 40)` / `val dialogH = 170`（全项目 231 处几何计算），
 * 于是同一类问题反复出现：宽度按中文观感定死、英文一长就溢出；高度写死、内容一多就顶出屏幕；
 * 底部按钮的位置和高度公式两处各写一遍、改一处忘另一处。2026-09-26 用户提出「应该整一个模板，
 * 以后直接调用」—— 这个文件就是那个模板的**几何部分**。
 *
 * ## 怎么用（新弹窗照抄）
 *
 * ```kotlin
 * // ① 量内容：宽度取「标题 / 信息行 / 按钮行 / 输入框」里最宽的，高度按行数 × 行高
 * val g = DialogGeometry.of(
 *     screenW = width, screenH = height,
 *     contentW = maxOf(标题宽, 信息行最宽, 按钮行宽),
 *     contentNeeded = 行数 * 10,
 * )
 *
 * // ② 画框（背景 + 金色居中标题）
 * drawDialogFrame(context, g, Text.translatable("xxx.title"), textRenderer)
 *
 * // ③ 画内容：超出会被自动裁掉，滚轮把 [scroll] 传进来即可
 * g.beginContent(context)
 * ...画（y 从 g.contentTop 起，记得减 scroll）...
 * g.endContent(context)
 *
 * // ④ 按钮钉在 g.buttonY（内容再长也推不走），右缘对齐 `g.x + g.w - 边距`
 * ```
 *
 * ## 已经内置的约定（别再自己写一遍）
 *
 * - 底部按钮距边框 **10px**（[BOTTOM_H] = 按钮 20 + 边距 10）
 * - 标题居中、金色、基线 `y + 14`
 * - 弹窗与屏幕边缘至少留 [SCREEN_MARGIN]
 * - 内容超高时**不截断、改为可滚动**（[needsScroll] / [maxScroll]）
 */
data class DialogGeometry(
    /** 弹窗宽 */
    val w: Int,
    /** 弹窗高 */
    val h: Int,
    /** 弹窗左上角 */
    val x: Int,
    val y: Int,
    /** 内容区顶（绝对坐标） */
    val contentTop: Int,
    /** 内容区**可视**高（弹窗不够高时小于 [contentNeeded]） */
    val contentH: Int,
    /** 内容**实际需要**的高（调用方给的） */
    val contentNeeded: Int,
    /** 底部按钮行顶（绝对坐标）—— 按钮 20 高，距弹窗底边框 10px */
    val buttonY: Int,
) {

    /** 内容装不下 ⇒ 把 [maxScroll] 交给滚轮（`scroll = (scroll - amount).coerceIn(0, maxScroll())`） */
    val needsScroll: Boolean get() = contentNeeded > contentH

    fun maxScroll(): Int = maxOf(0, contentNeeded - contentH)

    /** 内容区裁剪（**必须成对**调用 [beginContent] / [endContent]） */
    fun beginContent(context: DrawContext) =
        context.enableScissor(x, contentTop, x + w, contentTop + contentH)

    fun endContent(context: DrawContext) = context.disableScissor()

    companion object {
        /** 标题区高：标题基线在 `y + 14`，留到 26 与内容隔开 */
        const val TITLE_H = 26

        /** 底部按钮区高：按钮 20 高 + 距边框 10px（CLAUDE.md 的统一约定） */
        const val BOTTOM_H = 30

        /** 内容左右留白合计（左右各 12） */
        const val PAD_W = 24

        /** 弹窗与屏幕边缘的最小间距 */
        const val SCREEN_MARGIN = 20

        /** 按钮行高度（钉底那行的按钮高） */
        const val BUTTON_H = 20

        /**
         * @param screenW/screenH 屏幕逻辑尺寸（各界面传 `width` / `height`）
         * @param contentW 内容最宽（像素，**调用方实测**：标题、信息行、按钮行、输入框取最大）
         * @param contentNeeded 内容需要的高（像素，按行数 × 行高算）
         * @param minW/maxW 宽度下限 / 上限 —— 下限防内容太窄时弹窗看着瘪，上限防超宽屏上拉得离谱
         * @param titleH/bottomH/padW 见同名常量，一般不用改
         */
        fun of(
            screenW: Int,
            screenH: Int,
            contentW: Int,
            contentNeeded: Int,
            minW: Int = 200,
            maxW: Int = 460,
            titleH: Int = TITLE_H,
            bottomH: Int = BOTTOM_H,
            padW: Int = PAD_W,
        ): DialogGeometry {
            // 宽：内容 + 留白，卡在 [minW, maxW] 内；再保证不顶出屏幕（屏幕太窄时以下限为准，别算出负数）
            val w = (contentW + padW)
                .coerceIn(minW, maxW)
                .coerceAtMost(maxOf(minW, screenW - SCREEN_MARGIN))

            // 高：内容 + 标题 + 底部按钮区；装不下就钳到屏幕，多出来的部分靠内容区滚动
            val wanted = titleH + contentNeeded + bottomH
            val h = wanted.coerceAtMost(maxOf(titleH + bottomH, screenH - SCREEN_MARGIN))

            val x = (screenW - w) / 2
            val y = (screenH - h) / 2
            val contentTop = y + titleH
            val contentH = (h - titleH - bottomH).coerceAtLeast(0)
            // 按钮**钉底**：底边框往上 10px 处
            val buttonY = y + h - 10 - BUTTON_H

            return DialogGeometry(w, h, x, y, contentTop, contentH, contentNeeded, buttonY)
        }
    }
}

/**
 * 画弹窗**背景 + 标题**（金色居中、基线 `y + 14`）—— 与全项目现有弹窗同一套外观。
 *
 * ⚠ 遮罩（`drawScreenDimMask`）、不透明衬底、下层控件隐藏仍由调用方负责：那些跟"这个弹窗盖住什么"有关，
 * 各界面不同（详见 CLAUDE.md 的「确认弹窗黄金模板」）。
 */
fun drawDialogFrame(context: DrawContext, g: DialogGeometry, title: Text, textRenderer: TextRenderer) {
    drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, g.x, g.y, g.w, g.h, 0, DIALOG_BACKGROUND_TEX_H)
    context.drawCenteredTextWithShadow(
        textRenderer, title.copy().formatted(Formatting.GOLD), g.x + g.w / 2, g.y + 14, 0xFFFFFF
    )
}
