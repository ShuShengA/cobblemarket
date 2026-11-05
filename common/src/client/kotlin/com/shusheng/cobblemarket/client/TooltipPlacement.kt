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
        avoid: Rect? = null,
        /**
         * **上下翻转的判定锚点**（默认 -1 = 自动吸附到 24px 行格的中心）。
         *
         * ⚠ 2026-11-05 用户实测「第六/七行悬停窗乱跳」：原来直接用鼠标 y，
         * 鼠标在行内上下移动 10px 就能让「下方放得下」翻成「放不下」。
         * ⇒ 默认按**行格**吸附（项目列表行高 24，`mouseY / 24 * 24 + 12`），
         * 鼠标在同一行内怎么动，翻转结果都不变；列表界面可传精确行中心。
         */
        anchorY: Int = -1,
    ): Pos? {
        // 鼠标在本行按钮上：不画（玩家准备点了；也正因此不需要再避让按钮）
        if (avoid != null && avoid.contains(mouseX, mouseY)) return null

        val w = textW + 2 * pad
        val h = textH + pad
        // 背景矩形（文字区四周各留 pad）：右上/右下贴光标右侧，左上/左下贴光标左侧 ——
        // 右侧起点的 x 与原来的 `minOf(mouseX + 12, ...)` 完全一致，不改变既有手感
        val bgXRight = mouseX + GAP - pad
        val bgXLeft = mouseX - GAP - pad - textW
        // 锚点：显式传了就用它（列表界面传行中心）；否则**吸附到 24px 行格中心**
        // （项目列表行高 24）—— 避免鼠标在行内上下移动导致翻转/贴边来回跳
        val ay = if (anchorY >= 0) anchorY else (mouseY / 24) * 24 + 12
        // ⚠ **判定**用行锚点（稳定），**最终位置**用鼠标 y（连续）——
        // 2026-11-05 用户实测「换行时窗口一格格跳、不流畅」：位置直接用 anchorY 会变成 24px 阶梯
        val bgYAbove = ay - ABOVE_EXTRA - pad - textH
        val bgYBelow = ay + GAP - pad
        val raw = listOf(
            Rect(bgXRight, bgYAbove, w, h), // 右上
            Rect(bgXRight, bgYBelow, w, h), // 右下
            Rect(bgXLeft, bgYAbove, w, h),  // 左上
            Rect(bgXLeft, bgYBelow, w, h)   // 左下
        )
        // ⚠ **先挑「不用钳制就放得下」的**（用行锚点判定，稳定）：否则「上方放不下」会被钳成
        // 「贴屏幕顶」，悬停窗就钉在顶部不跟鼠标了（原行为是「上方放不下 → 翻到光标下方」）。
        val candidates = raw.filter { it.fitsOnScreen(screenW, screenH) }
        // 选中候选的**方位**（0 右上 / 1 右下 / 2 左上 / 3 左下）——
        // 两边都放不下（窗口太高）时按锚点在屏幕的上半/下半决定贴哪边：
        // 行在上半 ⇒ 窗口在**下方**（贴底）；行在下半 ⇒ 窗口在**上方**（贴顶）
        val chosenIndex = if (candidates.isNotEmpty()) {
            raw.indexOf(candidates.first())
        } else if (ay < screenH / 2) {
            1 // 右下（below）
        } else {
            0 // 右上（above）
        }
        val above = chosenIndex % 2 == 0
        val left = chosenIndex >= 2
        // **最终位置用鼠标坐标**（连续跟随，换行不会一格格跳）；再钳进屏幕防出界
        val realY = if (above) mouseY - ABOVE_EXTRA - pad - textH else mouseY + GAP - pad
        val realX = if (left) mouseX - GAP - pad - textW else mouseX + GAP - pad
        val bg = Rect(realX, realY, w, h).clampedInto(screenW, screenH)

        // 背景左上角 →（加回 pad）文字区左上角
        return Pos(bg.x + pad, bg.y + pad)
    }
}
