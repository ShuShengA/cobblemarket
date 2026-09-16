package com.shusheng.cobblemarket.client.icon

import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import kotlin.random.Random

/**
 * 点击精灵图标时播放叫声（普通 `cry` 与战吼 `battle_cry` **随机**二选一），各个界面共用。
 *
 * **动作与声音都不用分别写代码**：
 * - 动画：**随机**在普通叫与战吼之间二选一。**战吼**取主动画版（独占，否则会和走路动画
 *   混合成四不像，见 [IconAnimationResolver.primaryAnimationName]）；**普通叫保持叠加** ——
 *   官方就把它注册成叠加，它只提供嘴/头部的关键帧、身体靠姿势动画撑着，独占会让模型错位。
 *   114 个物种有战吼，将来 Cobblemon 补上更多时这边不用改代码就自动生效
 * - 声音：`FloatingState` 没有 entity，Cobblemon 的 `BedrockSoundKeyframe` 会走
 *   `SimpleSoundInstance.forUI(...)` 自动播出来
 *
 * **命中检测**用 S0 建的 [IconRegistry]（每帧登记所有图标矩形，滚动、弹窗遮挡自动正确）。
 */
object IconCryClick {

    /** 连点节流：Cobblemon 自己没做，满页乱点会叠音。 */
    private const val THROTTLE_MS = 200L

    /**
     * **弹窗自动叫的延迟**（毫秒）：弹窗刚弹出时玩家还在"看这是啥"，叫早了根本反应不过来
     * 声音是从哪来的；等弹窗稳定显示后再叫，因果才对得上（2026-09-16 用户反馈调整）。
     *
     * 用法：打开弹窗时记 `xxxCryAt = System.currentTimeMillis() + AUTO_CRY_DELAY_MS`，
     * 渲染处比对 `now >= xxxCryAt` 再 [play] —— 延迟与"模型已就绪"两个条件都满足才叫。
     */
    const val AUTO_CRY_DELAY_MS = 300L

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
        // 普通叫与战吼**随机**二选一（用户拍板：每次点击都吼太单调）。
        // ⚠ 只有**战吼**走主动画版（独占）—— 它是"发力"动作，叠加会和走路/待机动画混合成四不像。
        //   普通叫**必须保持叠加**：官方就是这么注册的（火焰鸡 poser 顶层 animations 里
        //   `"cry": "q.bedrock_stateful(...)"`，而招式类是 `q.bedrock_primary(...)`），
        //   它只提供嘴/头部的关键帧，身体姿态靠姿势动画撑着；强行独占会让其余骨骼掉回
        //   rest pose —— 整个模型错位（2026-09-15 实测踩到并回退）。
        val candidates = mutableListOf<String>()
        if (Random.nextBoolean()) {
            IconAnimationResolver.primaryAnimationName(renderable, "battle_cry")?.let(candidates::add)
        }
        candidates.add("cry")
        state.addFirstAnimation(candidates.toSet())
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
