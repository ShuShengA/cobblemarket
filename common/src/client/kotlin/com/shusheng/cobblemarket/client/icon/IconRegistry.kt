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

    /** 本帧全部图标，按登记顺序（即渲染顺序）。 */
    val all: List<Entry> get() = entries
}
