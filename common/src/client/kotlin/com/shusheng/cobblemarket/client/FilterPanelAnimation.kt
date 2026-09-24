package com.shusheng.cobblemarket.client

import net.minecraft.client.gui.DrawContext

/**
 * 右侧筛选面板的开关动画：**展开时从「筛选」按钮的位置放大平移到目标位置，收起时反向缩回**。
 *
 * 手法照 [EnterAnimation] / [CloseAnimation]（全局时间戳 + smoothstep 缓动 + 矩阵 + shader 淡入），
 * 但那两个是靠 `ScreenMixin` 在 `renderWithTooltip` 的 HEAD/TAIL 包矩阵、作用于**整个界面**的；
 * 筛选面板只占界面一角，塞不进那套 ⇒ 这里单独做一个，由各界面的 `renderBackground` 手动配对调用。
 *
 * ⚠ **只作用于面板背景**（`MarketPanelBackground.draw`，自绘）。面板里的按钮 / 输入框是 MC 控件，
 *   由 `super.render()` 统一绘制、**塞不进矩阵、无法跟着缩放** ⇒ 动画进行中把它们隐藏、播完再显示
 *   （见各界面 `updateControlVisibility` / `applyFilterVisibility` 里的 `isActive()` 判断）。
 *   最终观感：面板从筛选按钮那里「长」出来，内容随后点亮；收起反过来。
 */
class FilterPanelAnimation {

    companion object {
        const val DURATION_MS = 200L
        /** 起始缩放：面板从多小长出来（0.2 ≈ 一个小小的方块从按钮那里冒出来） */
        private const val START_SCALE = 0.2f
    }

    private var startAt = 0L

    /** 本次是展开还是收起（决定插值方向） */
    private var expanding = false

    /** 启动动画。`expand` = true 展开、false 收起 */
    fun start(expand: Boolean) {
        expanding = expand
        startAt = System.currentTimeMillis()
    }

    /** 动画进行中（进度未到 1）。界面据此决定「面板内控件是否先藏起来」 */
    fun isActive(): Boolean = startAt != 0L

    /** 0..1 缓动进度（smoothstep：先快后慢，照市场进出场动画） */
    private fun progress(): Float {
        val raw = ((System.currentTimeMillis() - startAt).toFloat() / DURATION_MS).coerceIn(0f, 1f)
        return raw * raw * (3f - 2f * raw)
    }

    /**
     * 压入面板的变换矩阵并设置淡入。**必须与 [restore] 配对**（仅当本函数返回 true 时才需要 restore）。
     *
     * 变换 = 「平移到现在的位置」→「按当前比例缩放」→「把原点移回面板中心」：
     * 这样面板照原坐标绘制即可，看到的却是从 [btnX],[btnY]（筛选按钮中心）一路长到 [panelCx],[panelCy]。
     *
     * @return true = 这一帧面板**要画**（矩阵已压入，画完记得调 [restore]）；
     *         false = 没有动画在跑、**或刚好播完这一帧** —— 后者是关键：收起动画的最后一帧必须跳过绘制，
     *         否则会以"全尺寸、无变换"闪一下空面板（用户 2026-09-24 实测报的）。见 MarketScreen 调用处
     */
    fun applyTransform(
        context: DrawContext,
        btnX: Float, btnY: Float,
        panelCx: Float, panelCy: Float
    ): Boolean {
        if (!isActive()) return false
        val p = progress()
        if (p >= 1f) {
            startAt = 0L
            return false
        }
        // t = 面板"长大"的程度：展开时 p 从 0→1，收起时反过来（1→0）
        val t = if (expanding) p else 1f - p
        val cx = btnX + (panelCx - btnX) * t
        val cy = btnY + (panelCy - btnY) * t
        val s = START_SCALE + (1f - START_SCALE) * t
        context.matrices.push()
        context.matrices.translate(cx.toDouble(), cy.toDouble(), 0.0)
        context.matrices.scale(s, s, 1f)
        context.matrices.translate(-panelCx.toDouble(), -panelCy.toDouble(), 0.0)
        // 淡入：比缩放更早到 1（1.4 倍进度），免得出现"轮廓清楚但内容全无"的空面板。
        // 收起时 t 递减 ⇒ alpha 同步淡出
        val alpha = (t * 1.4f).coerceIn(0f, 1f)
        context.setShaderColor(alpha, alpha, alpha, alpha)
        return true
    }

    /** 恢复矩阵与颜色（与 [applyTransform] 配对） */
    fun restore(context: DrawContext) {
        context.setShaderColor(1f, 1f, 1f, 1f)
        context.matrices.pop()
    }
}
