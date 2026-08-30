package com.shusheng.cobblemarket.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

/**
 * 界面进场动画（关闭动画的反向）：聊天可点击按钮（拍品名 / 查看待交付）跳转打开界面时，
 * 界面从屏幕底部整体滑入并淡入（100ms 缓动）。实现与 CloseAnimation 同款：
 * ScreenMixin 在 renderWithTooltip HEAD/TAIL 包矩阵 + 着色器颜色，整个界面
 * （含弹窗/遮罩/下层内容）作为一个整体同步滑入。
 */
object EnterAnimation {
    const val DURATION_MS = 100L

    private var startAt = 0L

    /** 启动动画；已在动画中返回 false（防重复触发）。播打开市场同款音效 */
    fun start(): Boolean {
        if (isActive()) return false
        startAt = System.currentTimeMillis()
        MinecraftClient.getInstance().soundManager.play(
            net.minecraft.client.sound.PositionedSoundInstance.master(
                net.minecraft.sound.SoundEvent.of(net.minecraft.util.Identifier.of("cobblemarket", "open_entry")),
                1.0f
            )
        )
        return true
    }

    fun isActive(): Boolean = startAt != 0L

    /** 0..1 缓动进度（smooth：先快后慢，照入口/关闭动画） */
    private fun progress(): Float {
        val raw = ((System.currentTimeMillis() - startAt).toFloat() / DURATION_MS).coerceIn(0f, 1f)
        return raw * raw * (3f - 2f * raw)
    }

    /** HEAD 注入：动画进行中，整体下移（起始位置在屏外一个屏高）+ 整体淡入 */
    fun pushTransform(context: DrawContext) {
        if (!isActive()) return
        val client = MinecraftClient.getInstance()
        val screen = client.currentScreen ?: return
        val p = progress()
        if (p >= 1f) {
            startAt = 0L
            return
        }
        val h = screen.height.toFloat()
        context.matrices.push()
        context.matrices.translate(0.0, ((1f - p) * h).toDouble(), 0.0)
        // 整体淡入（照庆祝动画/入口淡出的 shader color 手法：作用于背景贴图与文字）
        context.setShaderColor(p, p, p, p)
    }

    /** TAIL 注入：恢复颜色与矩阵（与 pushTransform 配对；动画状态在一帧内不变，配对安全） */
    fun popTransform(context: DrawContext) {
        if (!isActive()) return
        context.setShaderColor(1f, 1f, 1f, 1f)
        context.matrices.pop()
    }
}
