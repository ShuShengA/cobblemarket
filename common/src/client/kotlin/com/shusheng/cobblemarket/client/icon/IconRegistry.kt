package com.shusheng.cobblemarket.client.icon

import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import com.cobblemon.mod.common.pokemon.RenderablePokemon

/**
 * 本帧已渲染的图标注册表。
 *
 * 各界面在自己的 `render()` 开头调用 [beginFrame] 清空，图标渲染时由 [PokemonIconRenderer] 登记。
 * 「每帧重建」让滚动、过滤、窗口缩放后的坐标自动正确，不需要任何失效逻辑。
 *
 * 弹窗打开时主列表 render 会提前 return，于是注册表里只剩弹窗自己的图标 ——
 * 这正是「弹窗打开时点下层行不响应」需要的语义（点击叫声依赖它）。
 */
object IconRegistry {

    /** 一个图标的屏幕矩形及其渲染状态。 */
    data class Entry(
        val renderable: RenderablePokemon,
        val state: PosableState,
        val x: Int,
        val y: Int,
        val size: Int
    ) {
        fun contains(mx: Double, my: Double): Boolean =
            mx >= x && mx < x + size && my >= y && my < y + size
    }

    private val entries = mutableListOf<Entry>()

    /** 本帧鼠标位置（屏幕坐标）。头部跟随与悬停预览共用这一份，不必逐处传参。 */
    var mouseX: Int = 0
        private set
    var mouseY: Int = 0
        private set

    /**
     * 「正被大图预览」的 state —— 让**行内小图自动与大图保持同一意图**（都切走路姿势）。
     *
     * ⚠ **为什么要它**：同一个 `PosableState` 若被两个调用点用**不同的 `preview`** 渲染，
     * 两者会每帧把 `currentPose` 掰向自己、**姿势过渡被反复触发**（2026-09-22 实测：
     * 日志里过渡的重复周期**正好等于过渡时长**）。`SellSelectScreen` 的选中行就是这种结构
     * —— 大图常驻，而鼠标已经不在那一行上（大图要 WALK、小图要 PROFILE）。
     * 有了它，**任何界面只要用了大图，小图就自动跟上**，不必每个界面各自写对。
     */
    private val previewingStates = mutableSetOf<PosableState>()
    private var lastPreviewingStates: Set<PosableState> = emptySet()

    /**
     * 大图渲染时登记：这个 state 正在被预览。
     *
     * ⚠ 对 [isPreviewing] **下一帧**才可见 —— 大图渲染在小图**之后**，同帧内小图读不到本帧的标记。
     * 差一帧对姿势过渡完全无感（过渡本身就是渐变的）。
     */
    fun markPreviewing(state: PosableState) {
        previewingStates += state
    }

    /** 该 state 是否正被大图预览（读的是**上一帧**的登记，见 [markPreviewing]）。 */
    fun isPreviewing(state: PosableState): Boolean = state in lastPreviewingStates

    /** 开始一帧的登记：清空上一帧的图标、轮转预览标记，并记下本帧鼠标位置。 */
    fun beginFrame(mouseX: Int, mouseY: Int) {
        entries.clear()
        lastPreviewingStates = previewingStates.toSet()
        previewingStates.clear()
        this.mouseX = mouseX
        this.mouseY = mouseY
    }

    fun register(renderable: RenderablePokemon, state: PosableState, x: Int, y: Int, size: Int) {
        entries += Entry(renderable, state, x, y, size)
    }

    /** 命中检测，后登记的优先（同一位置叠加时取上层那个）。 */
    fun entryAt(mx: Double, my: Double): Entry? = entries.lastOrNull { it.contains(mx, my) }

    /**
     * 按「行」找图标：鼠标 y 落在某个图标的**行范围**内（图标高 + 上下各 2px 边距，正好等于 24 的行高）
     * 且 x 落在 `[rowLeft, rowRight)` 内。
     *
     * 列表行内的图标只有 20px，直接点图标点不中、悬停也难瞄准，所以行内相关功能（点击叫声、
     * 悬停预览）都以**整行**为触区。这样各界面不用解析自己的列表结构，只要传一下左右边界。
     */
    fun entryInRow(mx: Double, my: Double, rowLeft: Int, rowRight: Int): Entry? {
        if (mx < rowLeft || mx >= rowRight) return null
        return entries.lastOrNull { e -> my >= e.y - 2 && my < e.y + e.size + 2 }
    }

    /** 本帧全部图标，按登记顺序（即渲染顺序）。 */
    val all: List<Entry> get() = entries
}
