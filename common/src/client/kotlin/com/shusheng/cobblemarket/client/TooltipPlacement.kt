package com.shusheng.cobblemarket.client

/**
 * 悬停窗位置计算 —— **避免盖住本行的操作按钮**（2026-09-20 用户实测反馈后新增）。
 *
 * 背景：悬停窗一直贴在光标右下 / 右上，而**自动 GUI 尺寸**下逻辑屏宽很小（1080p 只有 480、2K 更小），
 * 靠屏幕右缘一挤就被推回中间，正好压在「购买 / 出价」这类**行按钮**上 ——
 * 鼠标明明停在按钮上，却被悬停窗挡住（只能凭记忆点）。
 *
 * 规则：按「右上 → 右下 → 左上 → 左下」依次尝试，挑第一个**没有把本行按钮盖掉一半以上**的位置；
 * 四个都盖太多（极窄屏 / 悬停窗特别宽）时挑**交叠面积最小**的那个 —— 保证悬停窗不会因此不显示。
 * 所有候选都会先钳制进屏幕内（与既有行为一致）。
 *
 * ⚠ 阈值取「一半」的由来见 [Rect.coversTooMuch] 的注释：太松（只看是否完全覆盖）挡不住宽悬停窗，
 * 太严（一点不许重叠）会把悬停窗推到很别扭的位置。
 */
object TooltipPlacement {

    /** 光标与悬停窗之间的间隔 */
    const val GAP = 12

    /** 光标上方时额外多让开一点（沿用既有手感：`mouseY - h - 4`） */
    private const val ABOVE_EXTRA = 4

    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {

        /**
         * 是否**盖住 [o] 一半以上**（阈值见 [MAX_COVER_RATIO]）。
         *
         * 判据演进（2026-09-20 用户两轮反馈定稿）：
         * ① 「完全盖住才换位置」太松 —— **悬停窗很宽时**（性格/特性那行字多）会把按钮盖掉九成，
         *    按钮明明还在但基本看不见，用户实测照样算「被盖住」；
         * ② 「一点都不许重叠」太严 —— 尺寸 3 下悬停窗压住按钮一角本来就是常态，且不影响点击。
         */
        fun coversTooMuch(o: Rect): Boolean = overlapArea(o) * 2 >= o.w.toLong() * o.h

        /** 交叠面积（不相交为 0）—— 四个候选都盖太多时挑最小的那个用 */
        fun overlapArea(o: Rect): Long {
            val ow = minOf(x + w, o.x + o.w) - maxOf(x, o.x)
            val oh = minOf(y + h, o.y + o.h) - maxOf(y, o.y)
            return if (ow <= 0 || oh <= 0) 0L else ow.toLong() * oh
        }

    }

    /** 钳制进屏幕（悬停窗比屏幕还大时贴左上角，与既有行为一致） */
    private fun Rect.clampedInto(screenW: Int, screenH: Int): Rect = Rect(
        x.coerceIn(0, maxOf(0, screenW - w)),
        y.coerceIn(0, maxOf(0, screenH - h)),
        w, h
    )

    /** 悬停窗**文字区**左上角（背景 = 文字区四周各留 pad） */
    data class Pos(val x: Int, val y: Int)

    /**
     * @param mouseX/mouseY 光标位置
     * @param textW/textH 悬停窗文字区尺寸（背景比它四周各多 [pad]）
     * @param avoid 本行按钮矩形（**「完全覆盖」判定用，所以别外扩**）；**null = 不避让**
     */
    fun place(
        mouseX: Int, mouseY: Int,
        textW: Int, textH: Int, pad: Int,
        screenW: Int, screenH: Int,
        avoid: Rect? = null
    ): Pos {
        val w = textW + 2 * pad
        val h = textH + pad
        // 背景矩形（文字区四周各留 pad）：右上/右下贴光标右侧，左上/左下贴光标左侧 ——
        // 右侧起点的 x 与原来的 `minOf(mouseX + 12, ...)` 完全一致，不改变既有手感
        val bgXRight = mouseX + GAP - pad
        val bgXLeft = mouseX - GAP - pad - textW
        val bgYAbove = mouseY - ABOVE_EXTRA - pad - textH
        val bgYBelow = mouseY + GAP - pad
        val candidates = listOf(
            Rect(bgXRight, bgYAbove, w, h), // 右上
            Rect(bgXRight, bgYBelow, w, h), // 右下
            Rect(bgXLeft, bgYAbove, w, h),  // 左上
            Rect(bgXLeft, bgYBelow, w, h)   // 左下
        ).map { it.clampedInto(screenW, screenH) }

        val bg = avoid?.let { a ->
            candidates.firstOrNull { !it.coversTooMuch(a) } ?: candidates.minByOrNull { it.overlapArea(a) }!!
        } ?: candidates.first()

        // 背景左上角 →（加回 pad）文字区左上角
        return Pos(bg.x + pad, bg.y + pad)
    }
}
