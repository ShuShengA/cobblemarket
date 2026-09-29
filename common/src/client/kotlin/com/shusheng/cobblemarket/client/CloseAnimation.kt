package com.shusheng.cobblemarket.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen

/**
 * 界面关闭动画（入口掉落动画倒放）：按 E/Esc 关闭模组界面时，当前界面整体向上缩回屏幕顶部，
 * 200ms 后真正关闭。实现：ScreenMixin 在 renderWithTooltip HEAD/TAIL 包一层矩阵变换，
 * 整个界面渲染（含弹窗/遮罩/下层内容）作为一个整体同步滑出。
 */
object CloseAnimation {
    const val DURATION_MS = 100L

    private var startAt = 0L

    /**
     * 动画播完后要切到的界面；`null` = 回游戏（绝大多数界面）。
     *
     * 供**「叠在别的界面上」的界面**用（[com.shusheng.cobblemarket.screen.GatedFormScreen] 就是）——
     * 那类界面关掉时该回它的上层，回游戏等于把上层配到一半的草稿一起丢了。
     */
    private var target: Screen? = null

    /** 启动动画；已在动画中返回 false（防重复触发）。[target] 见同名字段 */
    fun start(target: Screen? = null): Boolean {
        if (isActive()) return false
        this.target = target
        startAt = System.currentTimeMillis()
        playCloseSound()
        return true
    }

    /** 关闭界面音效（与动画解绑：动画开关关闭时关闭界面同样播） */
    fun playCloseSound() {
        val client = MinecraftClient.getInstance()
        client.soundManager.play(
            net.minecraft.client.sound.PositionedSoundInstance.master(
                net.minecraft.sound.SoundEvent.of(net.minecraft.util.Identifier.of("cobblemarket", "screen_close")),
                1.0f,
                0.5f // 音量（两参重载固定 0.25 太轻，同 playResultSound）
            )
        )
    }

    fun isActive(): Boolean = startAt != 0L

    /** 0..1 缓动进度（smooth：先快后慢，照入口动画） */
    private fun progress(): Float {
        val raw = ((System.currentTimeMillis() - startAt).toFloat() / DURATION_MS).coerceIn(0f, 1f)
        return raw * raw * (3f - 2f * raw)
    }

    /** 每帧 tick：动画播完真正关闭当前界面（切到 [target]，默认回游戏） */
    fun onTick(client: MinecraftClient) {
        if (!isActive()) return
        if (System.currentTimeMillis() - startAt >= DURATION_MS) {
            startAt = 0L
            // 先取出再清空：切界面会连带触发别的逻辑，留着重入时不会误用上一次的目标
            val to = target
            target = null
            client.setScreen(to)
        }
    }

    /** HEAD 注入：动画进行中，把整个界面渲染向上平移出屏幕 */
    fun pushTransform(context: DrawContext) {
        if (!isActive()) return
        val client = MinecraftClient.getInstance()
        val screen = client.currentScreen ?: return
        val h = screen.height.toFloat()
        context.matrices.push()
        // 围绕屏幕中心缩放 + 整体上移（照入口动画 renderDropImage 的中心缩放手法；
        // 直接 scale 会围绕屏幕左上角缩，界面向左上缩窄变形——求购单等宽面板界面尤其明显）
        val p = progress()
        val cx = screen.width / 2f
        val cy = screen.height / 2f
        context.matrices.translate(cx.toDouble(), (cy - p * h).toDouble(), 0.0)
        context.matrices.scale(1f - p, 1f - p, 1f)
        context.matrices.translate((-cx).toDouble(), (-cy).toDouble(), 0.0)
    }

    /** TAIL 注入：恢复矩阵（与 pushTransform 配对；动画状态在一帧内不变，配对安全） */
    fun popTransform(context: DrawContext) {
        if (!isActive()) return
        context.matrices.pop()
    }
}
