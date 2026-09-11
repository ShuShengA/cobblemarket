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

    /** 开始一帧的登记：清空上一帧的图标，并记下本帧鼠标位置。 */
    fun beginFrame(mouseX: Int, mouseY: Int) {
        entries.clear()
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

    /**
     * 鼠标是否停在"这个图标所在的行"上（只看纵向）。
     *
     * 行内图标用它判断自己要不要进预览态（跟大图一起走 walk）。**只在行内调用点用它** ——
     * 弹窗里的图标不能用，否则鼠标划过弹窗图标时它会自顾自走起来，而那时大图是不显示的。
     */
    fun isMouseInRow(y: Int, size: Int): Boolean =
        mouseY >= y - 2 && mouseY < y + size + 2

    /** 本帧全部图标，按登记顺序（即渲染顺序）。 */
    val all: List<Entry> get() = entries
}
