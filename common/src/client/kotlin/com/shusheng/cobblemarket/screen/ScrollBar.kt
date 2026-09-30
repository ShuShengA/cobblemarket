package com.shusheng.cobblemarket.screen

import net.minecraft.client.gui.DrawContext
import kotlin.math.roundToInt

/**
 * **可拖动的滚动条** —— 画轨道 / 画滑块 / 命中判定 / 拖动换算，**一处实现**，各界面别重写。
 *
 * ## 为什么要有它（2026-09-30 用户报「所有界面的滚动条都点不动」）
 *
 * 项目里 16 个界面的滚动条原先**只是画出来的**（`context.fill` 两根竖条），鼠标按上去毫无反应 ——
 * 只能靠滚轮。抽成这一份之后，各界面只要接三个点：
 *
 * 1. 渲染时：[draw]（顺便把它画出来的几何记下来，拖动换算要用）
 * 2. 按下时：[mouseClicked]（返回新的 scroll；null = 没点在条上，调用方继续走自己的逻辑）
 * 3. 拖动/松开：[mouseDragged]（返回新的 scroll）/ [mouseReleased]
 *
 * ## 交互口径
 *
 * - **拖滑块**：按住滑块上下拖；**拖出轨道也照样跟手**（拖动期间不判命中，只看鼠标 y）
 * - **点轨道空白**：**直接跳过去**（滑块中心落到点的位置），并顺势进入拖动 ——
 *   长列表（比如放开池子后的 900 条技能）里这比拖滑块快得多，也是浏览器那套通用习惯
 * - **点条以外**：一律不响应，交点原样返回 null
 *
 * ⚠ **命中区比视觉宽**（滑块才 3px，1:1 根本抓不住）：左右各放宽 [HIT_SLACK] 像素。
 *   ⇒ 调用方**要让列表行优先判定**，否则压在列表右缘那一两像素会被这里截胡
 *   （见 `PointsMarketScreen.handleEditDialogClick` 里的顺序）。
 *
 * ⚠ **单例，同一帧只支持一个滚动条**：拖动换算要用"上一次画出来的几何"
 *   （渲染时记录 —— 项目的既有惯例，见 `PointsMarketScreen` 里买下动画起点的注释），
 *   所以那份几何是**这个实例的状态**。
 *
 * ⚠⚠ **一帧画两条滚动条的界面必须各持一个实例**（`MeowthBankScreen` 的紫卡 / 黑卡两个名单就是）——
 *   共用一个的话，后画的那条会覆盖几何、前一条拖不动（2026-09-30 铺开时实测发现）。
 *   其余界面直接用 [ScrollBar.shared] 那个共享实例。
 */
class ScrollBar {

    companion object {
        /**
         * **默认实例** —— 绝大多数界面一帧只画一条滚动条，直接用这个（用法与原来的 object 一样：
         * `ScrollBar.shared.draw(...)`）。
         * ⚠ 一帧画**两条**的界面**别用它**，各 new 一个（理由见类 KDoc）。
         */
        val shared = ScrollBar()

        /** 滑块最小长度：条目再多也留这么长，否则细得抓不住 */
        private const val MIN_THUMB_H = 6

        /** 轨道 / 滑块的宽度（沿用原来那套观感：1px 轨道、3px 滑块，滑块居中于轨道） */
        private const val TRACK_W = 1
        private const val THUMB_W = 3

        /** 命中区比视觉左右各放宽多少像素（滑块太细，1:1 抓不住） */
        private const val HIT_SLACK = 3

        /** 轨道色（半透明白）与滑块色（实心白）—— 与原实现一致，各界面滚动条长一个样 */
        private const val TRACK_COLOR = 0x40FFFFFF
        private val THUMB_COLOR = 0xFFFFFFFF.toInt()
    }

    // ── 上一次 [draw] 记下的几何（拖动换算全靠它）──
    private var trackX = 0
    private var trackTop = 0
    private var trackH = 0
    private var thumbY = 0
    private var thumbH = 0
    private var totalRows = 0
    private var visibleRows = 0

    // ── 拖动状态 ──
    /** 是否正在拖（true 时 [mouseDragged] 才换算） */
    private var dragging = false

    /** 抓取点落在滑块内的偏移（0 = 抓的是滑块顶边）——拖起来才不会"跳一下" */
    private var grabOffset = 0

    /**
     * 画滚动条并**记下几何**（[mouseClicked] / [mouseDragged] 要用）。
     *
     * [x] = **轨道左缘**的 x；[top] / [trackH] = 轨道上下端；[total] = 条目总数、
     * [rows] = 一屏放得下几行、[scroll] = 当前偏移（**单位都是"行"**，与调用方的 scroll 变量同一口径）。
     *
     * 返回 false = **没画**（条目装得下，不需要滚动条）—— 调用方不用管返回值，它只是个提示。
     */
    fun draw(
        context: DrawContext, x: Int, top: Int, trackH: Int,
        total: Int, rows: Int, scroll: Int
    ): Boolean {
        if (total <= rows || trackH <= 0) return false
        trackX = x
        trackTop = top
        this.trackH = trackH
        totalRows = total
        visibleRows = rows
        thumbH = (trackH * rows / total).coerceAtLeast(MIN_THUMB_H)
        val maxScroll = (total - rows).coerceAtLeast(1)
        thumbY = top + (trackH - thumbH) * scroll.coerceIn(0, maxScroll) / maxScroll
        // 轨道（1px 竖线）
        context.fill(x, top, x + TRACK_W, top + trackH, TRACK_COLOR)
        // 滑块（3px，**居中于轨道**：左缘 = x-1，与原来的观感一致）
        val thumbLeft = x - (THUMB_W - TRACK_W) / 2
        context.fill(thumbLeft, thumbY, thumbLeft + THUMB_W, thumbY + thumbH, THUMB_COLOR)
        return true
    }

    /**
     * 按下。返回**新的 scroll**；**null = 没点在滚动条上**，调用方按自己的逻辑继续（点行、失焦…）。
     *
     * ⚠ 调用方必须**先判列表行、再判这里**：命中区左右各放宽了 [HIT_SLACK]，压在列表右缘那
     *   一两像素会被这里截胡（行优先才符合直觉）。
     */
    fun mouseClicked(mouseX: Double, mouseY: Double, scroll: Int): Int? {
        if (totalRows <= visibleRows || trackH <= 0) return null
        val thumbLeft = trackX - (THUMB_W - TRACK_W) / 2
        if (mouseX < thumbLeft - HIT_SLACK || mouseX >= thumbLeft + THUMB_W + HIT_SLACK) return null
        if (mouseY < trackTop || mouseY >= trackTop + trackH) return null
        val y = mouseY.toInt()
        grabOffset = if (y in thumbY until thumbY + thumbH) {
            y - thumbY                     // 抓在滑块上：按原样拎起来，不跳
        } else {
            thumbH / 2                     // 点轨道空白：**直接跳过去**（滑块中心对准点击处），顺势进入拖动
        }
        dragging = true
        return scrollFor(y - grabOffset, scroll)
    }

    /** 拖动中。返回新的 scroll；**null = 当前没在拖**，调用方走自己的逻辑 */
    fun mouseDragged(mouseY: Double, scroll: Int): Int? {
        if (!dragging) return null
        return scrollFor(mouseY.toInt() - grabOffset, scroll)
    }

    /**
     * 松开。返回**这次松开是不是结束了一次拖动** ——
     * true 表示调用方该吃掉这次事件（别让它在别处又触发一次点击）。
     */
    fun mouseReleased(): Boolean {
        val was = dragging
        dragging = false
        return was
    }

    /**
     * 放弃拖动状态（滚动条所属的列表被关掉时调）。
     *
     * ⚠ 不调的话会留一个"半拖"状态：下次 [draw] 换了内容，而 `dragging` 还是 true ⇒
     *   鼠标没按住也会跟着走。列表关闭时顺手清掉最省心。
     */
    fun cancel() {
        dragging = false
    }

    /**
     * 把「滑块顶边想落在哪」换算成 scroll（行）。
     *
     * [fallback] = 几何还没建立（没画过）时的退路 —— 直接原样返回，不动 scroll。
     */
    private fun scrollFor(thumbTop: Int, fallback: Int): Int {
        val maxScroll = (totalRows - visibleRows).coerceAtLeast(0)
        if (maxScroll <= 0) return fallback
        val travel = (trackH - thumbH).coerceAtLeast(1)
        val clamped = thumbTop.coerceIn(trackTop, trackTop + trackH - thumbH)
        return ((clamped - trackTop).toFloat() * maxScroll / travel).roundToInt().coerceIn(0, maxScroll)
    }
}
