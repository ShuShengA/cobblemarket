package com.shusheng.cobblemarket.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.network.CelebrationSource
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ItemStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import org.joml.Quaternionf
import org.lwjgl.opengl.GL11
import kotlin.math.PI

/**
 * 获得精灵的庆祝动画（买到 / 拍到 / 求购单接受交付时由服务端下发触发，渲染在所有界面之上）：
 * 弹跳球效果——从屏幕上方落到中心（下落中放大到最大），
 * 然后以最大形态反复弹跳 3 次（高度递减），结尾淡出（约 2.5 秒，最大约 200px）。
 *
 * 触发口径与总开关都在服务端（[com.shusheng.cobblemarket.network.CelebrationNetwork]），
 * 客户端只负责按收到的包排队播放。
 */
object PokemonCelebrationAnimation {

    private const val DURATION_MS = 2500L

    private data class Pending(val speciesId: String, val aspects: List<String>, val shiny: Boolean)

    /** 待播队列：同一批获得多只（一次结算赢下几个拍卖）时依次播完，不互相覆盖 */
    private val queue = ArrayDeque<Pending>()

    private var renderable: RenderablePokemon? = null
    private var state: FloatingState? = null
    private var startTime = 0L
    private var active = false

    /** 入队一只；空闲则立即开播，正在播则排队等前面播完 */
    fun trigger(speciesId: String, aspects: List<String>, shiny: Boolean, source: String) {
        // 个人开关按来源分流（服务端总开关是另一层：那层关了压根不发包）。
        // 未知来源（版本不匹配等）归到低频那组，宁可多播也不要静默吞掉。
        val allowed = if (source == CelebrationSource.MARKET.name)
            ClientConfig.celebrationOnMarketBuy
        else
            ClientConfig.celebrationOnAuctionAndOrder
        if (!allowed) return
        queue.addLast(Pending(speciesId, aspects, shiny))
        if (!active) startNext()
    }

    /** 取队首开播；构造失败的（物种 ID 无效等）直接跳过换下一只，队列空则回到空闲 */
    private fun startNext() {
        while (true) {
            val next = queue.removeFirstOrNull() ?: run { reset(); return }
            val id = Identifier.tryParse(next.speciesId) ?: continue
            val species = PokemonSpecies.getByIdentifier(id) ?: continue
            val renderAspects = next.aspects.toMutableSet()
            if (next.shiny) renderAspects.add("shiny")
            renderable = try {
                RenderablePokemon(species, renderAspects, ItemStack.EMPTY)
            } catch (_: Exception) {
                continue
            }
            state = FloatingState()
            startTime = System.currentTimeMillis()
            active = true
            return
        }
    }

    fun register() {
        // 无界面时走 HUD 阶段；有界面时交给 ScreenMixin（renderWithTooltip 之后）。
        // 两条路径互斥——同帧画两次会让淡出阶段的 alpha 叠加，尾巴偏亮且消失突兀。
        HudRenderCallback.EVENT.register { context, _ ->
            if (MinecraftClient.getInstance()?.currentScreen == null) {
                renderOverlay(context)
            }
        }
    }

    /**
     * 唯一渲染入口（HUD 阶段 / ScreenMixin 均走这里）。
     *
     * 光把绘制调用挪到最后并不能让动画置顶，必须处理两件事：
     *  1. DrawContext 是延迟批处理的，Screen 画的文字/物品图标只是攒在 immediate buffer 里，
     *     要等帧末尾 drawContext.draw() 才提交，且提交顺序按 RenderLayer 分组、与调用顺序无关。
     *     所以先手动 draw() 把它们全部落屏，动画才排在它们之后。
     *  2. GUI 元素已经把 z 写进深度缓冲，会把后画的 3D 模型整片剔除（表现为背景板盖住动画）。
     *     清掉深度缓冲即可，模型自身的前后遮挡不受影响（那是清空之后才写入的）。
     */
    @JvmStatic
    fun renderOverlay(context: DrawContext) {
        if (!active) return
        if (System.currentTimeMillis() - startTime >= DURATION_MS) {
            startNext()          // 播下一只；队列空了 active 转 false
            if (!active) return
        }
        context.draw()
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
        draw(context)
        // 动画自身也走 immediate buffer，立刻提交，别留到帧末尾跟别人混在一起
        context.draw()
    }

    private fun reset() {
        active = false
        renderable = null
        state = null
    }

    private fun draw(context: DrawContext) {
        val rp = renderable ?: run { startNext(); return }
        val st = state ?: run { startNext(); return }
        val elapsed = System.currentTimeMillis() - startTime
        val client = MinecraftClient.getInstance()
        val scaledW = client.window.scaledWidth
        val scaledH = client.window.scaledHeight

        // ── 弹跳球效果：从屏幕上方落到中心（下落中放大到最大），
        //    然后以最大形态反复弹跳几次（高度递减），结尾淡出 ──
        val t = elapsed.toFloat() / DURATION_MS
        fun smooth(k: Float): Float = k * k * (3f - 2f * k)

        val dropEnd = 0.27f // 前 27% 时间：下落+放大
        val offsetY: Float
        val scaleFactor: Float
        val alpha: Float
        if (t < dropEnd) {
            // 下落：从中心上方 220px 落到中心（缓出），缩放 0→1
            val k = smooth(t / dropEnd)
            offsetY = -220f + 220f * k
            scaleFactor = k
            alpha = 1f
        } else {
            // 弹跳：3 次，高度递减（60→40→22），抛物线（sin 曲线）
            val bt = (t - dropEnd) / (1f - dropEnd)
            val bouncePhase = bt * 3f
            val hopIndex = minOf(bouncePhase.toInt(), 2)
            val heights = floatArrayOf(60f, 40f, 22f)
            val h = heights[hopIndex]
            offsetY = -h * MathHelper.sin((bouncePhase % 1f) * PI.toFloat())
            scaleFactor = 1f
            // 结尾 15% 时间淡出
            alpha = if (bt > 0.85f) 1f - (bt - 0.85f) / 0.15f else 1f
        }

        val matrices = context.matrices
        matrices.push()
        // 模型从原点向下延伸（头顶在原点）：落点上移半高（100px），让视觉中心对齐屏幕中心
        matrices.translate(scaledW / 2.0, scaledH / 2.0 - 100.0 + offsetY.toDouble(), 0.0)
        try {
            drawProfilePokemon(
                renderablePokemon = rp,
                matrixStack = matrices,
                rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                state = st,
                partialTicks = 0f,
                // 最大约 200px；scale 参数围绕模型中心缩放，位置不会偏移
                scale = 90f * scaleFactor,
                r = alpha,
                g = alpha,
                b = alpha,
                a = alpha
            )
        } catch (_: Exception) {
        }
        matrices.pop()
    }
}
