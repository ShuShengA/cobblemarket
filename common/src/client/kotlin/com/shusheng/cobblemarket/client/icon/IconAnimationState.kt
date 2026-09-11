package com.shusheng.cobblemarket.client.icon

import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import java.util.WeakHashMap

/**
 * 单个图标的跨帧动画状态。
 *
 * 头部跟随需要「上一帧的 yaw/pitch」来做平滑（Cobblemon 的 `calculateHeadYawAndPitch` 是纯函数，
 * 存值由调用方负责），昼夜姿态与悬停预览同样需要每个图标各记各的。
 *
 * 每个图标本来就持有一个跨帧存活的 [PosableState]（各界面 IconData 里的 FloatingState），
 * 以它为键即可做到「每个图标一份状态」，不必给十几个界面的数据类加字段。
 */
class IconAnimationState {
    /** 头部朝向的偏航角，跨帧平滑。 */
    var headYaw: Float = 0f

    /** 头部朝向的俯仰角，跨帧平滑。 */
    var headPitch: Float = 0f
}

/** [IconAnimationState] 的取用入口，按 [PosableState] 实例隔离。 */
object IconAnimationStates {
    private val states = WeakHashMap<PosableState, IconAnimationState>()

    fun of(state: PosableState): IconAnimationState = states.getOrPut(state) { IconAnimationState() }
}
