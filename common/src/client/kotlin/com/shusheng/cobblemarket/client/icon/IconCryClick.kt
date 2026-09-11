package com.shusheng.cobblemarket.client.icon

import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import com.cobblemon.mod.common.pokemon.RenderablePokemon

/**
 * 点击精灵图标时播放叫声（`battle_cry` 优先，没有则 `cry`），各个界面共用。
 *
 * **动作与声音都不用分别写代码**：
 * - 动画：`addFirstAnimation(setOf("battle_cry", "cry"))`（88 个物种有战吼，其余覆盖到 87%）
 * - 声音：`FloatingState` 没有 entity，Cobblemon 的 `BedrockSoundKeyframe` 会走
 *   `SimpleSoundInstance.forUI(...)` 自动播出来
 *
 * **命中检测**用 S0 建的 [IconRegistry]（每帧登记所有图标矩形，滚动、弹窗遮挡自动正确）。
 */
object IconCryClick {

    /** 连点节流：Cobblemon 自己没做，满页乱点会叠音。 */
    private const val THROTTLE_MS = 200L

    private var lastPlayedAt = 0L

    /**
     * 播放叫声。
     *
     * 两种情况**不响应**：
     * - 图标设为「完全静态」（静态的意思本来就是"一个不会动的图标"，与头部跟随、睡眠姿态的判据一致）
     * - **精灵正在睡觉**（睡觉的精灵当然不叫；判定与睡眠姿态共用 [IconAnimationResolver.isSleeping]）
     */
    fun play(state: PosableState, renderable: RenderablePokemon) {
        val mode = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode
        if (mode != com.shusheng.cobblemarket.client.IconAnimMode.FLOAT) return
        if (IconAnimationResolver.isSleeping(renderable)) return

        val now = System.currentTimeMillis()
        if (now - lastPlayedAt < THROTTLE_MS) return
        lastPlayedAt = now

        // 先清掉正在播的，避免连点叠加动画（节流之外的兜底）
        state.activeAnimations.clear()
        state.addFirstAnimation(setOf("battle_cry", "cry"))
    }

    /** 命中检测：返回鼠标位置下的图标（精确到图标矩形），没有则 null。 */
    fun hitAt(mouseX: Double, mouseY: Double): IconRegistry.Entry? =
        IconRegistry.entryAt(mouseX, mouseY)

    /** 点图标矩形触发（弹窗 / 预览里的图标用这个）。@return 是否命中了图标 */
    fun playIconAt(mouseX: Double, mouseY: Double): Boolean {
        val entry = hitAt(mouseX, mouseY) ?: return false
        play(entry.state, entry.renderable)
        return true
    }

    /**
     * 点**整行**触发（列表行内图标用这个）。
     *
     * 图标只有 20px、直接点图标点不中，所以把触区放宽到整行：只要鼠标 y 落在某个图标的**行范围**内
     * （行高 = 图标高 + 上下各 2px 边距）且 x 落在 [rowLeft, rowRight) 内就算命中。
     * 这样各界面不用解析自己的列表结构，只要传一下左右边界 —— 右边界按界面收窄到行尾按钮左侧。
     *
     * @return 是否命中了某个图标（调用方一般不需要管，点了就点了）
     */
    fun playRowAt(mouseX: Double, mouseY: Double, rowLeft: Int, rowRight: Int): Boolean {
        val entry = IconRegistry.entryInRow(mouseX, mouseY, rowLeft, rowRight) ?: return false
        play(entry.state, entry.renderable)
        return true
    }
}
