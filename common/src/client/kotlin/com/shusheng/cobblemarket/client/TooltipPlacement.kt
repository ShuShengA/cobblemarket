package com.shusheng.cobblemarket.client

/**
 * 悬停窗位置计算 —— 挑一个不会挡住鼠标的角落。
 *
 * ⚠ **鼠标落在本行按钮上时整个不画**（[place] 返回 null）：玩家那时正准备点击、不需要详情。
 * 这一条同时让「避让行按钮」这件事**根本不必存在** —— 悬停窗就算盖住了按钮，
 * 玩家把鼠标移过去它也会立刻消失，所以不可能出现「想点却被挡」。
 *
 * 历史（别走回头路）：2026-09-20 曾用「按顺序挑第一个没盖住按钮的候选」来避让，但候选的 y
 * 跟着鼠标走 —— 同一行内鼠标上下移动十几像素就会让判定翻转、换到另一个方向，
 * 于是从上往下扫列表时悬停窗一左一右地闪（2026-09-21 用户报）。
 * 改成「鼠标在按钮上就不画」后避让整套删掉，抖动也随之消失。
 *
 * 候选顺序固定为 **右上 → 右下 → 左上 → 左下**，取第一个**完整放得下屏幕**的；
 * 四个都放不下（悬停窗比屏幕还大）才退回各自钳制后的版本，保证不会整块跑到屏幕外。
 */
/**
 * 悬停窗**行数钳制** —— 内容比屏幕高时只画放得下的那些行，末行换成「…（还有 N 行）」。
 *
 * ⚠ **各界面原先各写一份** `if (tyAbove <= 0) minOf(mouseY + 12, height - tooltipHeight) else tyAbove`，
 *   而行数一多 `height - tooltipHeight` 就是**负数** ⇒ 悬停窗**顶部被切到屏幕外**（且总高超过屏幕时
 *   放上放下都放不下）—— 2026-09-26 用户报的隐患。`[TooltipPlacement.place]` 那一族虽会把**位置**
 *   钳进屏幕、不会跑到屏幕外，但内容超高时同样显示不全，所以两边都要接这个。
 *   （典型触发：按住 Shift 展开完整词条，或 Ctrl 展开调试信息；几十行的模组物品一条就中招。）
 *
 * @param rows 原始行（类型由调用方定：`Text` / `Pair<Text, Int>` / …）
 * @param screenH 屏幕逻辑高（各界面传 `height`）
 * @param rowH 行高，默认 10（与各处一致）
 * @param overflow 末行构造——给「还有几行」，由调用方按自己的行类型造（这样不用为类型写泛型约束）
 */
fun <T> clampTooltipRows(rows: List<T>, screenH: Int, rowH: Int = 10, overflow: (Int) -> T): List<T> {
    // 悬停窗总高 = 行数 × 行高 + 上下边框（各处 pad = 4，合计 8）；再留 8px，避免贴死屏幕上下缘
    val maxRows = ((screenH - 16) / rowH).coerceAtLeast(1)
    if (rows.size <= maxRows) return rows
    // 末行让给提示本身，所以实际显示 maxRows - 1 行
    val kept = maxRows - 1
    return rows.take(kept) + overflow(rows.size - kept)
}

object TooltipPlacement {

    /** 光标与悬停窗之间的间隔 */
    const val GAP = 12

    /** 光标上方时额外多让开一点（沿用既有手感：`mouseY - h - 4`） */
    private const val ABOVE_EXTRA = 4

    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {

        /** 点是否落在矩形内（左闭右开，与 MC 的命中判定口径一致） */
        fun contains(mx: Int, my: Int): Boolean =
            mx >= x && mx < x + w && my >= y && my < y + h

    }

    /** 钳制进屏幕（悬停窗比屏幕还大时贴左上角，与既有行为一致） */
    private fun Rect.clampedInto(screenW: Int, screenH: Int): Rect = Rect(
        x.coerceIn(0, maxOf(0, screenW - w)),
        y.coerceIn(0, maxOf(0, screenH - h)),
        w, h
    )

    /**
     * 不加钳制就完全放得下吗 —— 见 [place] 里「先挑放得下的」那段。
     * ⚠ 不能省掉这一步：直接钳制会把「光标上方放不下」悄悄变成「贴屏幕顶」，
     * 于是右上候选永远被判为可用、悬停窗**钉死在顶部不跟鼠标**（2026-09-20 用户实测：求购单的
     * 悬停窗最高十几行，最明显；矮的界面看不出来）。
     */
    private fun Rect.fitsOnScreen(screenW: Int, screenH: Int): Boolean =
        x >= 0 && y >= 0 && x + w <= screenW && y + h <= screenH

    /** 悬停窗**文字区**左上角（背景 = 文字区四周各留 pad） */
    data class Pos(val x: Int, val y: Int)

    /**
     * @param mouseX/mouseY 光标位置
     * @param textW/textH 悬停窗文字区尺寸（背景比它四周各多 [pad]）
     * @param avoid **本行按钮矩形**；鼠标落在里面就返回 null（不画）；传 null = 该行没有按钮、
     *        是「点击整行互动」的那种（求购单的部分 tab 等），悬停窗照常显示
     * @return 文字区左上角；**null = 这一帧不该画**（鼠标正停在行按钮上）
     */
    fun place(
        mouseX: Int, mouseY: Int,
        textW: Int, textH: Int, pad: Int,
        screenW: Int, screenH: Int,
        avoid: Rect? = null
    ): Pos? {
        // 鼠标在本行按钮上：不画（玩家准备点了；也正因此不需要再避让按钮）
        if (avoid != null && avoid.contains(mouseX, mouseY)) return null

        val w = textW + 2 * pad
        val h = textH + pad
        // 背景矩形（文字区四周各留 pad）：右上/右下贴光标右侧，左上/左下贴光标左侧 ——
        // 右侧起点的 x 与原来的 `minOf(mouseX + 12, ...)` 完全一致，不改变既有手感
        val bgXRight = mouseX + GAP - pad
        val bgXLeft = mouseX - GAP - pad - textW
        val bgYAbove = mouseY - ABOVE_EXTRA - pad - textH
        val bgYBelow = mouseY + GAP - pad
        val raw = listOf(
            Rect(bgXRight, bgYAbove, w, h), // 右上
            Rect(bgXRight, bgYBelow, w, h), // 右下
            Rect(bgXLeft, bgYAbove, w, h),  // 左上
            Rect(bgXLeft, bgYBelow, w, h)   // 左下
        )
        // ⚠ **先挑「不用钳制就放得下」的**：否则「上方放不下」会被钳成「贴屏幕顶」，
        // 悬停窗就钉在顶部不跟鼠标了（原行为是「上方放不下 → 翻到光标下方」）。
        val candidates = raw.filter { it.fitsOnScreen(screenW, screenH) }
            .ifEmpty { raw.map { it.clampedInto(screenW, screenH) } }
        val bg = candidates.first()

        // 背景左上角 →（加回 pad）文字区左上角
        return Pos(bg.x + pad, bg.y + pad)
    }
}
