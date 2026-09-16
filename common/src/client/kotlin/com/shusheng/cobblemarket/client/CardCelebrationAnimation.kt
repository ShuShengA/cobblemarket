package com.shusheng.cobblemarket.client

import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.platform.registerHudRender
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvent
import net.minecraft.util.Identifier
import org.lwjgl.opengl.GL11

/**
 * 发卡动画（紫金卡/黑金卡首次获得或补发）：卡片从申请界面的卡片图标位置出发，
 * 放大并缓动飞到屏幕中央（像真的在发卡），停留片刻后淡出。
 *
 * 起点：申请/补发按钮点击时由申请界面调用 setPendingStart 登记图标中心坐标；
 * 服务端成功后回发 CardCelebrationPayload，trigger 取走登记的起点（无起点=命令发卡场景，从屏幕中央下方起飞）。
 * 渲染通道照 PokemonCelebrationAnimation：HUD / ScreenMixin 两路互斥，先 draw() 落屏再清深度保证置顶。
 */
object CardCelebrationAnimation {

    /** 飞行段时长**按卡种**：黑金卡比紫金卡飞得更慢、更有分量（2026-09-16 拍板，原为统一 500） */
    private const val PURPLE_FLY_MS = 700L
    private const val BLACK_FLY_MS = 1000L

    /** 停留段时长**按卡种**：黑金卡停得更久（2026-09-16 拍板，原为统一 500） */
    private const val PURPLE_HOLD_MS = 600L
    private const val BLACK_HOLD_MS = 1500L

    /** 淡出段时长**按卡种**：在停留**结束之后**才开始（不占用停留时间） */
    private const val PURPLE_FADE_MS = 600L
    private const val BLACK_FADE_MS = 700L

    /** 本次飞行的飞行段时长（按正在播的卡种取） */
    private val flyMs: Long get() = if (kind == "black") BLACK_FLY_MS else PURPLE_FLY_MS

    /** 本次的停留段时长（按正在播的卡种取） */
    private val holdMs: Long get() = if (kind == "black") BLACK_HOLD_MS else PURPLE_HOLD_MS

    /** 本次的淡出段时长（按正在播的卡种取） */
    private val fadeMs: Long get() = if (kind == "black") BLACK_FADE_MS else PURPLE_FADE_MS

    /** 本次动画总时长 = 飞行 + 停留 + 淡出 */
    private val totalMs: Long get() = flyMs + holdMs + fadeMs

    private const val START_SIZE = 56f   // 申请界面图标大小
    private const val END_SIZE = 160f    // 屏幕中央最大尺寸

    private val queue = ArrayDeque<String>()
    private var active = false
    private var startTime = 0L
    private var kind = ""
    private var pendingStartX: Int? = null
    private var pendingStartY: Int? = null

    /** 申请/补发按钮点击时登记卡片图标中心坐标（屏幕坐标；动画包到达时取走） */
    fun setPendingStart(x: Int, y: Int) {
        pendingStartX = x
        pendingStartY = y
    }

    fun trigger(cardKind: String) {
        queue.addLast(cardKind)
        if (!active) startNext()
    }

    private fun startNext() {
        val next = queue.removeFirstOrNull() ?: run { reset(); return }
        kind = next
        startTime = System.currentTimeMillis()
        active = true
        // 卡片起飞的那一刻响音效（队列里排着多张时，每张起飞各响一次）
        playGrantSound(next)
    }

    /** 发卡音效：紫金卡 / 黑金卡各一枚（2026-09-16 新增，此前只有动画没有声音）。
     *  音量走三参重载显式给 0.5——两参重载会把音量写死 0.25（MC 的 UI 按钮音档位），太轻。 */
    private fun playGrantSound(cardKind: String) {
        val soundId = if (cardKind == "black") "card_black" else "card_purple"
        MinecraftClient.getInstance().soundManager.play(
            PositionedSoundInstance.master(
                SoundEvent.of(Identifier.of("cobblemarket", soundId)),
                1.0f,
                0.5f
            )
        )
    }

    private fun reset() {
        active = false
        kind = ""
    }

    fun register() {
        // 无界面时走 HUD 阶段；有界面时交给 ScreenMixin（照精灵庆祝动画两路互斥模式）
        registerHudRender { context, _ ->
            if (MinecraftClient.getInstance()?.currentScreen == null) {
                renderOverlay(context)
            }
        }
    }

    /** 唯一渲染入口（HUD 阶段 / ScreenMixin 均走这里；置顶手法照精灵庆祝动画） */
    @JvmStatic
    fun renderOverlay(context: DrawContext) {
        if (!active) return
        if (System.currentTimeMillis() - startTime >= totalMs) {
            startNext()
            if (!active) return
        }
        context.draw()
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
        draw(context)
        context.draw()
    }

    private fun draw(context: DrawContext) {
        val client = MinecraftClient.getInstance()
        val scaledW = client.window.scaledWidth.toFloat()
        val scaledH = client.window.scaledHeight.toFloat()
        val elapsed = System.currentTimeMillis() - startTime

        val item = Registries.ITEM.get(Identifier.of("cobblemarket", if (kind == "black") "meowth_black_card" else "meowth_purple_card"))
        val air = Registries.ITEM.get(Identifier.of("minecraft", "air"))
        if (item == air) {
            startNext()
            return
        }

        // 起点优先级：申请界面还开着 → 动态读界面上图标中心（保证第一帧与图标完全重叠）；
        // 界面已关闭 → 点击时登记的坐标；都没有（命令发卡等）→ 屏幕中央下方起飞
        val liveStart = when (val s = client.currentScreen) {
            is com.shusheng.cobblemarket.screen.PurpleCardApplyScreen -> s.cardIconCenter()
            is com.shusheng.cobblemarket.screen.BlackCardApplyScreen -> s.cardIconCenter()
            else -> null
        }
        val startX = (liveStart?.first ?: pendingStartX)?.toFloat() ?: (scaledW / 2f)
        val startY = (liveStart?.second ?: pendingStartY)?.toFloat() ?: (scaledH * 0.72f)
        pendingStartX = null
        pendingStartY = null

        fun smooth(k: Float): Float = k * k * (3f - 2f * k)

        // 三段（淡出**不占用**停留时间，在停留之后另起一段）：
        //   飞行段 = 起点 → 屏幕中央（位置与尺寸都缓出）
        //   停留段 = 停在中央、全亮不动
        //   淡出段 = alpha 1 → 0
        val cx: Float
        val cy: Float
        val size: Float
        val alpha: Float
        if (elapsed < flyMs) {
            val k = smooth(elapsed.toFloat() / flyMs)
            cx = startX + (scaledW / 2f - startX) * k
            cy = startY + (scaledH / 2f - startY) * k
            size = START_SIZE + (END_SIZE - START_SIZE) * k
            alpha = 1f
        } else {
            cx = scaledW / 2f
            cy = scaledH / 2f
            size = END_SIZE
            val fadeElapsed = elapsed - flyMs - holdMs
            alpha = if (fadeElapsed <= 0L) 1f
            else (1f - fadeElapsed.toFloat() / fadeMs).coerceAtLeast(0f)
        }

        // 淡出走全局 RenderSystem.setShaderColor（context 那套只作用于 DrawContext 自身缓冲）。
        // ⚠ **不能**用 Cobblemon 的 renderScaledGuiItemIcon：它内部第一件事就是把 shader 颜色复位成全白
        //   （RenderHelper.kt:48），外面设的 alpha 会被直接覆盖 —— 表现就是「卡片不淡出、时间到直接消失」
        //   （2026-09-16 用户实测）。改用 context.drawItem + 矩阵缩放（照物品庆祝动画 ItemCelebrationAnimation）。
        context.matrices.push()
        context.matrices.translate((cx - size / 2).toDouble(), (cy - size / 2).toDouble(), 0.0)
        val iconScale = size / 16f
        context.matrices.scale(iconScale, iconScale, 1f)
        RenderSystem.setShaderColor(alpha, alpha, alpha, alpha)
        context.drawItem(ItemStack(item), 0, 0)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        context.matrices.pop()
    }
}
