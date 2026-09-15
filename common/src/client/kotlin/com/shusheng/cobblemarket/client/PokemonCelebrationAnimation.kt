package com.shusheng.cobblemarket.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.client.icon.IconAnimationResolver
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.shusheng.cobblemarket.network.CelebrationSource
import com.shusheng.cobblemarket.platform.registerHudRender
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ItemStack
import net.minecraft.util.Identifier
import org.lwjgl.opengl.GL11

/**
 * 获得精灵的庆祝动画（买到 / 拍到 / 求购单接受交付时由服务端下发触发，渲染在所有界面之上）：
 * 弹跳球效果——从屏幕上方落到中心（下落中放大到最大），
 * 然后以最大形态反复弹跳 3 次（高度递减），第三跳完整落回后静止淡出（约 2.8 秒，最大约 200px）。
 *
 * 触发口径与总开关都在服务端（[com.shusheng.cobblemarket.network.CelebrationNetwork]），
 * 客户端只负责按收到的包排队播放。
 */
object PokemonCelebrationAnimation {

    private const val FLY_MS = 700L        // 飞行段：起点 → 屏幕中心（位置与尺寸同时缓出）
    private const val HOLD_MS = 700L       // 停留段：停在中心淡出
    private const val DURATION_MS = FLY_MS + HOLD_MS

    /** 起点缩放：起点若是界面上的小图标（弹窗 30px / 行内 20px），起飞时按这个小尺寸起步再放大。
     *  与最大形态（scale 90 ≈ 视觉 200px 高）的换算：30/200 ≈ 0.15 → 90×0.15 ≈ 13.5，取 14f。 */
    private const val START_SCALE = 14f

    private data class Pending(
        val speciesId: String,
        val aspects: List<String>,
        val shiny: Boolean,
        val heldItemNbt: String,
        /** 起点屏幕坐标（界面上的精灵图标位置）；null = 无来源，走兜底起点 */
        val startX: Int? = null,
        val startY: Int? = null
    )

    /** 待播队列：同一批获得多只（一次结算赢下几个拍卖）时依次播完，不互相覆盖 */
    private val queue = ArrayDeque<Pending>()

    private var renderable: RenderablePokemon? = null
    private var state: FloatingState? = null
    private var startTime = 0L
    private var active = false
    /** 当前正在播的这只需从哪儿起飞（取自它自己那条 Pending，不能存全局——队列连播会串位） */
    private var curStartX: Int? = null
    private var curStartY: Int? = null

    /** 本只的战斗吼叫是否已触发。**必须在第一帧渲染之后**触发：`addFirstAnimation` 依赖 `currentModel`，
     *  而它是在 `applyAnimations` 里才被赋值的，刚 new 出来的 state 还没有模型。 */
    private var cryTriggered = false

    /** 入队一只；空闲则立即开播，正在播则排队等前面播完 */
    /** 点击购买时登记的起点。购买弹窗点下之后界面**立刻关闭**，等收包时界面上早已查不到
     *  那个图标了 —— 所以要在点击那一刻先把坐标记下来。只对下一条 trigger 生效
     *  （一次购买只对应一只精灵），用完即清。 */
    private var pendingStartX: Int? = null
    private var pendingStartY: Int? = null

    fun setPendingStart(x: Int, y: Int) {
        pendingStartX = x
        pendingStartY = y
    }

    /**
     * @param startX/startY 起飞的屏幕坐标（界面上的精灵图标位置）。传了就从那儿飞到屏幕中心；
     *   不传（拿不到图标坐标：界面已关、翻页了、卖家没开着界面等）走兜底起点。
     */
    fun trigger(
        speciesId: String, aspects: List<String>, shiny: Boolean, source: String,
        heldItemNbt: String = "", startX: Int? = null, startY: Int? = null
    ) {
        // 个人开关按来源分流（服务端总开关是另一层：那层关了压根不发包）。
        // 未知来源（版本不匹配等）归到低频那组，宁可多播也不要静默吞掉。
        val allowed = if (source == CelebrationSource.MARKET.name)
            ClientConfig.celebrationOnMarketBuy
        else
            ClientConfig.celebrationOnAuctionAndOrder
        if (!allowed) return
        // 起点优先级：**点击时登记的（购买弹窗）> 调用方传的（界面上的行内图标）> 兜底**。
        // ⚠ 顺序不能反：购买后界面会切回市场，而列表里往往就有刚买的那只（同物种），
        //   行内图标查得到 —— 若让它优先，就会从列表行图标起飞，而不是用户要的「从购买弹窗飞出」。
        queue.addLast(Pending(speciesId, aspects, shiny, heldItemNbt, pendingStartX ?: startX, pendingStartY ?: startY))
        pendingStartX = null
        pendingStartY = null
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
                // 携带物一并喂给模型：Cobblemon 会把它渲染在手上/嘴上，不传则动画里的精灵是空手的
                RenderablePokemon(species, renderAspects, heldIconStack("", next.heldItemNbt) ?: ItemStack.EMPTY)
            } catch (_: Exception) {
                continue
            }
            state = FloatingState()
            cryTriggered = false
            curStartX = next.startX
            curStartY = next.startY
            startTime = System.currentTimeMillis()
            active = true
            return
        }
    }

    fun register() {
        // 无界面时走 HUD 阶段；有界面时交给 ScreenMixin（renderWithTooltip 之后）。
        // 两条路径互斥——同帧画两次会让淡出阶段的 alpha 叠加，尾巴偏亮且消失突兀。
        registerHudRender { context, delta ->
            if (MinecraftClient.getInstance()?.currentScreen == null) {
                renderOverlay(context, delta)
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
    fun renderOverlay(context: DrawContext, delta: Float) {
        if (!active) return
        if (System.currentTimeMillis() - startTime >= DURATION_MS) {
            startNext()          // 播下一只；队列空了 active 转 false
            if (!active) return
        }
        context.draw()
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
        draw(context, delta)
        // 动画自身也走 immediate buffer，立刻提交，别留到帧末尾跟别人混在一起
        context.draw()
    }

    private fun reset() {
        active = false
        renderable = null
        state = null
    }

    private fun draw(context: DrawContext, delta: Float) {
        val rp = renderable ?: run { startNext(); return }
        val st = state ?: run { startNext(); return }
        val elapsed = System.currentTimeMillis() - startTime
        val client = MinecraftClient.getInstance()
        val scaledW = client.window.scaledWidth
        val scaledH = client.window.scaledHeight

        // ── 起飞 + 停留：从界面上的精灵图标位置飞到屏幕中心（位置与尺寸同时缓出），
        //    在中心停留片刻后淡出。
        //    起点拿不到时（界面已关 / 翻页了 / 卖家没开着界面）退回兜底起点：
        //    屏幕中心上方 320px —— 视觉上仍像原来的「从屏幕上方落下」，不会凭空出现在怪位置。
        fun smooth(k: Float): Float = k * k * (3f - 2f * k)

        val startX = (curStartX ?: (scaledW / 2)).toFloat()
        val startY = (curStartY ?: (scaledH / 2 - 320)).toFloat()
        // 终点不是裸的屏幕中心：模型从原点向下延伸（头顶在原点），要再上移半高 100px，
        // 精灵的「视觉中心」才落在屏幕正中心（原设计，勿改成 scaledH / 2f）
        val endY = scaledH / 2f - 100f

        val cx: Float
        val cy: Float
        val curScale: Float
        val alpha: Float
        if (elapsed < FLY_MS) {
            val k = smooth(elapsed.toFloat() / FLY_MS)
            cx = startX + (scaledW / 2f - startX) * k
            cy = startY + (endY - startY) * k
            curScale = START_SCALE + (90f - START_SCALE) * k
            alpha = 1f
        } else {
            cx = scaledW / 2f
            cy = endY
            curScale = 90f
            alpha = 1f - (elapsed - FLY_MS).toFloat() / HOLD_MS
        }

        // 与图标共用同一套解析器：白天待机 + 头跟随鼠标、晚上睡觉。
        // 恒传 animate = true —— 庆祝动画不受「精灵图标动画」开关控制，只由它自己的两个庆祝开关管；
        // preview 传 false —— 它不是悬停预览，不播走路。
        val rotation = PokemonIconRenderer.iconRotation()
        val anim = IconAnimationResolver.resolve(
            renderable = rp,
            state = st,
            rotation = rotation,
            centerX = scaledW / 2f,
            centerY = cy,
            mouseX = IconRegistry.mouseX,
            mouseY = IconRegistry.mouseY,
            animate = true,
            preview = false
        )

        val matrices = context.matrices
        matrices.push()
        matrices.translate(cx.toDouble(), cy.toDouble(), 0.0)
        try {
            drawProfilePokemon(
                renderablePokemon = rp,
                matrixStack = matrices,
                rotation = rotation,
                poseType = anim.poseType,
                state = st,
                // 传 delta 让精灵播放 idle 待机动画（呼吸浮动，与队伍界面同款）
                partialTicks = delta,
                // 最大约 200px；scale 参数围绕模型中心缩放，位置不会偏移
                scale = curScale,
                headYaw = anim.headYaw,
                headPitch = anim.headPitch,
                r = alpha,
                g = alpha,
                b = alpha,
                a = alpha
            )
            // 携带物：与 drawProfilePokemon 成对；外面套 alpha 让淡出阶段跟着一起变淡
            RenderSystem.setShaderColor(1f, 1f, 1f, alpha)
            PokemonIconRenderer.drawHeldItem(context, rp.heldItem, st, matrices)
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
            // 登场吼一声。声音不用另写代码：FloatingState 没有 entity，Cobblemon 的
            // BedrockSoundKeyframe 会走 SimpleSoundInstance.forUI(...) 自动播出来。
            // **睡觉的精灵不叫**（与点击图标同一判据）；标志位无论叫没叫都置位，
            // 否则睡着时不触发、醒来后会补叫一声。
            if (!cryTriggered) {
                cryTriggered = true
                if (!IconAnimationResolver.isSleeping(rp)) {
                    st.addFirstAnimation(setOf("battle_cry", "cry"))
                }
            }
        } catch (_: Exception) {
        }
        matrices.pop()
    }
}
