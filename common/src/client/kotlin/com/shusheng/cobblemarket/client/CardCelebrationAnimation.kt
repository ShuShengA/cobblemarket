package com.shusheng.cobblemarket.client

import com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon
import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.platform.registerHudRender
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
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

    private const val FLY_MS = 500L
    private const val HOLD_MS = 500L
    private const val TOTAL_MS = FLY_MS + HOLD_MS

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
        if (System.currentTimeMillis() - startTime >= TOTAL_MS) {
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

        // 飞行段：起点 → 屏幕中央（位置与尺寸都缓出）；停留段：静止淡出
        val cx: Float
        val cy: Float
        val size: Float
        val alpha: Float
        if (elapsed < FLY_MS) {
            val k = smooth(elapsed.toFloat() / FLY_MS)
            cx = startX + (scaledW / 2f - startX) * k
            cy = startY + (scaledH / 2f - startY) * k
            size = START_SIZE + (END_SIZE - START_SIZE) * k
            alpha = 1f
        } else {
            cx = scaledW / 2f
            cy = scaledH / 2f
            size = END_SIZE
            alpha = 1f - (elapsed - FLY_MS).toFloat() / HOLD_MS
        }

        // 淡出必须用全局 RenderSystem.setShaderColor：context.setShaderColor 只作用于 DrawContext 自身缓冲，
        // 物品图标走独立渲染路径（cobblemon renderScaledGuiItemIcon）不吃那套颜色，淡出会失效
        RenderSystem.setShaderColor(alpha, alpha, alpha, alpha)
        renderScaledGuiItemIcon(
            itemStack = ItemStack(item),
            x = (cx - size / 2).toDouble(),
            y = (cy - size / 2).toDouble(),
            scale = (size / 16.0),
            matrixStack = context.matrices
        )
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    }
}
