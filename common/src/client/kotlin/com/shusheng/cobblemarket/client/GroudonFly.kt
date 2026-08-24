package com.shusheng.cobblemarket.client

/**
 * 固拉多飞行动画的共享状态：
 * - 帧/时长常量（两界面一致）
 * - smoothRot 平滑朝向角：跨界面共享保证精灵市场 ↔ 上架选择切换时朝向平滑延续
 *
 * 重做第三步：水平飞入（2s）→ 逆时针弧 90° 出屏（1.8s，θ 90°→0°）→
 * 屏外切线掉头朝下 → 顺时针绕半圆 180° 到屏幕右半上方（1.2s，屏外提速）→
 * 屏外快速竖直下移 0.4s → 屏幕内加速下坠（速度与原 3s k² 版屏幕内段一致）。
 * 第四步（不连接上一步）：镜像图（头朝左、上下正常）从屏幕右上角外水平飞入停在右上角（1.2s），
 * 停留 5s 悬停。第五步：水平向左飞出屏幕停住（3s，smoothstep 缓动：悬停平滑启动、出屏减速停稳）。
 * 第六步（不连接）：再次镜像恢复原图（头朝右），从左下角外水平飞入（1.2s，末速与右飞段衔接），不停留。
 * 第七步：在市场面板背景下层水平向右飞，飞出屏幕右侧（2.5s 匀速）——
 * 下层段绘制提前到 render 开头（面板/列表绘制之前），后续背景绘制自然盖住精灵。
 * 镜像用 scale x 取负（静态镜像，非旋转中翻面）；负 scale 翻转三角形朝向，
 * 行内 3D 渲染残留的背面剔除会整图不画——绘制时临时关 cull 再恢复。
 */
object GroudonFly {
    const val FRAME_MS = 50L
    const val FLY_IN_MS = 2000f
    const val ARC_MS = 1800f
    const val ARC2_MS = 1200f
    const val DROP_OUT_MS = 400f
    const val FLY_IN2_MS = 1200f
    const val HOVER_MS = 5000f
    const val FLY_LEFT_MS = 3000f
    const val FLY_RIGHT_MS = 2500f
    var smoothRot = 0f
    /** 动画计时起点：首次渲染时记录，两个界面共享（照 PokemonCelebrationAnimation 的全局状态），
     *  精灵市场 ↔ 上架选择切换时动画不重置，时间线延续 */
    var startAt = 0L
}
