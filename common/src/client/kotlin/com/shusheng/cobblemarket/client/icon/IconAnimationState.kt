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

    /**
     * 悬停预览大图专用的一份，与行内图标分开。
     *
     * **必须分开**：同一个 `PosableState` 一帧内会被行内图标和大图各渲染一次，
     * 若共用一份 yaw/pitch，两次 `rotLerp` 会各自朝自己的目标角度拉（两者屏幕位置不同，目标角度天然不同），
     * 结果两边都在抖。
     */
    private val previewStates = WeakHashMap<PosableState, IconAnimationState>()

    fun of(state: PosableState, preview: Boolean = false): IconAnimationState =
        (if (preview) previewStates else states).getOrPut(state) { IconAnimationState() }

    // ── 鼠标静止判定（全局共用：同一时刻整屏图标看到的是同一个鼠标位置）──

    private var lastMouseX = Int.MIN_VALUE
    private var lastMouseY = Int.MIN_VALUE
    private var lastMouseMoveAt = System.currentTimeMillis()

    /**
     * 鼠标已静止的毫秒数；若本帧鼠标移动过则刷新计时并返回 0。
     *
     * 用于「鼠标长时间不动 → 精灵的头转回正前方」，照 Cobblemon 详情页的行为
     * （`ModelWidget` 里 `MAX_SECONDS_LOOKING = 6` 秒）。差别：它判的是「鼠标**离开模型**」，
     * 而图标是无条件跟随、没有悬停这个前提，所以这里改判「鼠标**静止**」。
     */
    fun mouseIdleMillis(mouseX: Int, mouseY: Int): Long {
        val now = System.currentTimeMillis()
        if (mouseX != lastMouseX || mouseY != lastMouseY) {
            lastMouseX = mouseX
            lastMouseY = mouseY
            lastMouseMoveAt = now
            return 0L
        }
        return now - lastMouseMoveAt
    }
}
