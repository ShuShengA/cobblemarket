package com.shusheng.cobblemarket.client.icon

import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import com.cobblemon.mod.common.pokemon.RenderablePokemon

/**
 * 弹窗预览专用的姿态状态持有者：**renderable 换了就整个换新**。
 *
 * ⚠ **为什么必须换**：`PokemonIconRenderer` 会把当前模型与动画 locator 写进 state，
 *   复用同一个就会把**上一只的动作**带过来 —— 用户 2026-09-26 实测：
 *   「点开某只的预览、动画刚开播就退出、立刻点开另一只 ⇒ 新那只演的是**上一只的动作**」。
 *   ⚠ 叫声没串是因为它走 `renderable` 参数、动作走 state。
 *
 * ⚠ **判据用引用比较**：调用方传进来的 renderable 都是**字段里存着的那个**
 *   （换商品 / 换精灵时才新建），所以同一只预览期间引用不变、**不会每帧重置**
 *   （每帧重置会让动画永远播不出来）。
 *
 * 用法：把原来那句 `private val xxxState = FloatingState()` 换成这个持有者，
 * 渲染时 `state = xxxState.stateFor(rp)`（叫声那处也一样）。
 */
class PreviewStateHolder {

    private var state = FloatingState()
    private var owner: RenderablePokemon? = null

    /** 取本次预览该用的 state —— [renderable] 与上次不同就换一个全新的 */
    fun stateFor(renderable: RenderablePokemon): PosableState {
        if (owner !== renderable) {
            state = FloatingState()
            owner = renderable
        }
        return state
    }
}
