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
 * 获得精灵的庆祝动画（买到 / 拍到 / 求购单接受交付时由服务端下发触发，渲染在所有界面之上）。
 *
 * **时间线只有一条**：飞向屏幕中心 → 停留 → 淡出（最大约 200px）。
 * 两种入场方式**只差起点**，后续曲线完全相同 —— 全项目统一叫这两个名字：
 * - **图标起飞**：起点是界面里那个精灵图标（图标位置 + 按图标大小换算的起始缩放）。
 *   精灵市场购买、求购单接受交付、以及拍卖时玩家正开着出价弹窗 —— 都走这条。
 * - **顶部空降**：起点在屏幕上方外侧。拿不到界面图标时走这条（典型是拍卖异步结算、
 *   中拍那一刻玩家早把出价弹窗关了），是设计的兜底。
 *
 * 触发口径与总开关都在服务端（[com.shusheng.cobblemarket.network.CelebrationNetwork]），
 * 客户端只负责按收到的包排队播放。
 */
object PokemonCelebrationAnimation {

    // ⚠ 总时长要盖得住整条合成动画（`PoseWithBattleCry.duration` = 战吼时长 + 维持战斗姿态 3.5 秒）：
    //   只按战吼算的话，最长的喷火龙 battle_cry 是 3.0 秒（≥ 3180 才不被切尾）；
    //   但后面还接着一段战斗姿态，2026-09-26 之前 2980 的总长让姿态刚摆出来就开始淡出（用户报「太仓促」）——
    //   现在停留段 2600、总长 3980，淡出点落在动画第 3.58 秒：战吼吼得完、姿态也稳定展示一会儿。
    private const val DURATION_MS = 3980L

    /** 起点动画（从界面图标起飞）的时间轴，单位毫秒：飞行 → 停留 → 淡出（淡出 = 总时长 − 前两段 = 400ms） */
    private const val FLY_MS = 980f
    private const val HOLD_MS = 2_600f

    /** **顶部空降**的起点：屏幕上方外侧的纵向位置（视觉上「从天上落下来」） */
    private const val SCRATCH_FROM_Y = 260f

    /** 顶部空降的初始缩放（相对最终尺寸）。**不能是 0** —— 见 draw() 里 else 分支的注释 */
    private const val SCRATCH_FROM_SCALE = 0.2f

    /**
     * **图标起飞**的起点：界面里那个精灵图标的**屏幕位置与缩放**。
     *
     * 传了它 → 图标起飞；没传 → [SCRATCH_FROM_Y] 那套顶部空降。
     * 三个入口（市场购买 / 拍卖 / 求购确认交付）都已接入，但仍会有拿不到起点的时候——
     * 典型是拍卖：异步结算，中拍时玩家早把出价弹窗关了 —— 那时就走顶部空降，是设计的兜底。
     *
     * [x] / [y] 照抄 [PokemonIconRenderer.draw] 的 `translate` 值、[scale] 照抄它的等效 profile scale ——
     * 两端（图标态、庆祝态）各自都是已验证过的渲染方式，中间插值即可，起飞与落点都不会跳。
     */
    data class Origin(val x: Float, val y: Float, val scale: Float) {
        companion object {
            /** 从一个图标矩形换算。公式必须与 [PokemonIconRenderer.draw] 保持一致。 */
            fun ofIcon(x: Int, y: Int, size: Int, centerAnchorY: Boolean) = Origin(
                x = x + size / 2f,
                y = y + if (centerAnchorY) size / 2f else 1f,
                scale = size / 25f * 2.5f * PokemonIconRenderer.ICON_SCALE
            )
        }
    }

    private data class Pending(
        val speciesId: String,
        val aspects: List<String>,
        val shiny: Boolean,
        val heldItemNbt: String,
        /** 携带物在模型上是否渲染（玩家可在 Cobblemon 里设隐藏）；false = 动画里的精灵空手 */
        val heldItemVisible: Boolean,
        val origin: Origin?
    )

    /** 待播队列：同一批获得多只（一次结算赢下几个拍卖）时依次播完，不互相覆盖 */
    private val queue = ArrayDeque<Pending>()

    private var renderable: RenderablePokemon? = null
    private var state: FloatingState? = null
    /** 当前这只的飞行起点；null = 走旧的掉落弹跳 */
    private var origin: Origin? = null
    private var startTime = 0L
    private var active = false

    /** 本只的战斗吼叫是否已触发。**必须在第一帧渲染之后**触发：`addFirstAnimation` 依赖 `currentModel`，
     *  而它是在 `applyAnimations` 里才被赋值的，刚 new 出来的 state 还没有模型。 */
    private var cryTriggered = false

    /**
     * 上一帧的真实时间戳（纳秒），用来自己算帧间隔 —— 见 [draw] 里 selfDelta 的注释。
     * 0 = 这一只还没渲染过第一帧（[startNext] 里重置）。
     */
    private var lastFrameNanos = 0L

    /**
     * 单帧最多推进的等价 tick 数（1 秒 = 20 单位）。
     *
     * 卡顿或断点之后一帧可能隔很久，不封顶会一次性把整段动画跳完；2.0 相当于 10fps，够宽松又不失真。
     */
    private const val MAX_SELF_DELTA = 2f

    /**
     * 界面在关闭前登记的起飞起点（见 [Origin]）。**用后即清、且带过期**：
     * 服务端可能最终没发庆祝包（个人开关关、余额不足、请求失败），那时起点会留在这里 ——
     * 不加过期的话，下一次触发（哪怕是别的入口）会从上次那个早已消失的图标位置起飞。
     */
    private var pendingOrigin: Origin? = null
    private var pendingOriginAt = 0L
    private const val PENDING_ORIGIN_TTL_MS = 3_000L

    /** 界面在关闭前登记起飞起点 —— 弹窗一关那个图标就不在了，坐标必须提前抓下来。
     *
     *  ⚠ **这里就把坐标换算成屏幕坐标**：起点取自**界面**（购买/结算弹窗里的精灵图标，虚拟坐标），
     *  而本动画画在缩放矩阵**之外**（落点按 `window.scaledWidth` 算屏幕中心，是整个屏幕层的特效）⇒
     *  两者不在同一坐标系。不换算的话界面**被放大**时起点会偏到屏幕左上角外
     *  （用户 2026-10-04 实测：档位 5 / 游戏尺寸 3，精灵从左上角飞出来）；被缩小时则偏向右下。
     *  ⚠ 收在这一处最保险：起点可能来自任何界面，逐个改调用点必漏。 */
    fun setPendingOrigin(origin: Origin?) {
        pendingOrigin = origin?.let { o ->
            val screen = MinecraftClient.getInstance().currentScreen
            if (screen == null) o else {
                val factor = GuiScale.factorFor(screen)
                if (factor == 1f) o else Origin(o.x * factor, o.y * factor, o.scale * factor)
            }
        }
        pendingOriginAt = System.currentTimeMillis()
    }

    /** 取走登记的起点并清空；超过 [PENDING_ORIGIN_TTL_MS] 视为失效（返回 null → 走旧动画） */
    private fun takePendingOrigin(): Origin? {
        val taken = pendingOrigin ?: return null
        pendingOrigin = null
        return if (System.currentTimeMillis() - pendingOriginAt <= PENDING_ORIGIN_TTL_MS) taken else null
    }

    /** 入队一只；空闲则立即开播，正在播则排队等前面播完 */
    fun trigger(
        speciesId: String,
        aspects: List<String>,
        shiny: Boolean,
        source: String,
        heldItemNbt: String = "",
        heldItemVisible: Boolean = true,
        origin: Origin? = null
    ) {
        // 个人开关按来源分流（服务端总开关是另一层：那层关了压根不发包）。
        // ⚠ 主市场与 **RP 商城**同组（都是"在市场上直接买"，2026-09-25 拍板）。
        // 未知来源（版本不匹配等）归到低频那组，宁可多播也不要静默吞掉。
        val allowed = if (source == CelebrationSource.MARKET.name || source == CelebrationSource.STORE.name)
            ClientConfig.celebrationOnMarketBuy
        else
            ClientConfig.celebrationOnAuctionAndOrder
        if (!allowed) return
        queue.addLast(Pending(speciesId, aspects, shiny, heldItemNbt, heldItemVisible, origin ?: takePendingOrigin()))
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
                RenderablePokemon(species, renderAspects, heldIconStackForModel(next.heldItemVisible, "", next.heldItemNbt))
            } catch (_: Exception) {
                continue
            }
            state = FloatingState()
            cryTriggered = false
            lastFrameNanos = 0L
            origin = next.origin
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
        // delta 参数保留只为不改动 ScreenMixin / HUD 两处的调用签名 —— 模型动画已改为按真实帧间隔自己推进
        // （见 draw() 里 selfDelta 的注释），不再需要调用方传 tickDelta
        draw(context)
        // 动画自身也走 immediate buffer，立刻提交，别留到帧末尾跟别人混在一起
        context.draw()
    }

    private fun reset() {
        active = false
        renderable = null
        state = null
        origin = null
    }

    private fun draw(context: DrawContext) {
        val rp = renderable ?: run { startNext(); return }
        val st = state ?: run { startNext(); return }
        val elapsed = System.currentTimeMillis() - startTime
        val client = MinecraftClient.getInstance()
        val scaledW = client.window.scaledWidth
        val scaledH = client.window.scaledHeight

        // ── 模型动画（战吼 / 待机 / 走路）自己按真实时间推进，不用调用方给的 delta ──
        // 调用方给的是 tickDelta —— 一个 tick 内的插值进度（0~1），而 FloatingState.updatePartialTicks 是
        // **累加**它的，于是 animationSeconds 的推进速度 ≈ 帧率 ÷ 40：帧率越高动画越快；单人游戏开着界面时
        // tick 暂停，它又几乎不推进。实测（2026-09-15）同一只 3 秒的战吼：在界面里能撑满窗口，
        // 回到游戏世界 0.65 秒就被判「播完」清掉 —— 同一个动画两种观感。
        // 换算成等价的 partialTicks（1 秒 = 20 单位）后，速度只跟真实时间走，与帧率、与是否暂停都无关。
        val nowNanos = System.nanoTime()
        val selfDelta = if (lastFrameNanos == 0L) 0f
        else ((nowNanos - lastFrameNanos) / 1_000_000_000f * 20f).coerceAtMost(MAX_SELF_DELTA)
        lastFrameNanos = nowNanos

        // ── 时间线。**图标起飞与顶部空降共用同一套**：飞行 → 停留 → 淡出，
        //    区别只在起点 from（下方取）──
        val elapsedMs = elapsed.toFloat()
        fun smooth(k: Float): Float = k * k * (3f - 2f * k)

        // 落点：translate 的是模型原点（头顶），故上移半高 100px，视觉中心才落在屏幕中心。
        // 两端的 translate/scale 都照抄各自已验证的渲染方式，中间插值 → 起飞与落点都不跳。
        val homeX = scaledW / 2f
        val homeY = scaledH / 2f - 100f
        val homeScale = 90f

        val posX: Float
        val posY: Float
        val renderScale: Float
        val alpha: Float

        // 起点：有界面图标就用它（图标起飞），没有（典型是拍卖异步结算、中拍时玩家早关了出价弹窗）
        // 就从屏幕上方外侧飞入（顶部空降）—— 两套叫法见类注释。
        //
        // ⚠ 早先这两条路走的是**不同的时间线** —— 没起点时是独立的「下落 + 弹跳」，其中下落段的缩放
        //   从 **0** 开始。scale=0 会让 drawProfilePokemon 出问题，而「登场吼一声」就写在它之后
        //   同一个 try 里，异常被 catch 吞掉 → 表现为「吼一下立刻变回待机」（2026-09-15 实测）。
        //   现在统一成同一套时间线，只是起点不同，这条坑就自然没了。
        val from = origin ?: Origin(scaledW / 2f, -SCRATCH_FROM_Y, homeScale * SCRATCH_FROM_SCALE)
        val fadeMs = DURATION_MS.toFloat() - FLY_MS - HOLD_MS
        if (elapsedMs < FLY_MS) {
            val k = smooth(elapsedMs / FLY_MS)
            posX = from.x + (homeX - from.x) * k
            posY = from.y + (homeY - from.y) * k
            renderScale = from.scale + (homeScale - from.scale) * k
            alpha = 1f
        } else {
            posX = homeX
            posY = homeY
            renderScale = homeScale
            // 停留期间不透明，之后快速淡出
            alpha = if (elapsedMs < FLY_MS + HOLD_MS) 1f
            else 1f - (elapsedMs - FLY_MS - HOLD_MS) / fadeMs
        }

        // 与图标共用同一套解析器：白天待机 + 头跟随鼠标、晚上睡觉。
        // 恒传 animate = true —— 庆祝动画不受「精灵图标动画」开关控制，只由它自己的两个庆祝开关管；
        // preview 传 false —— 它不是悬停预览，不播走路。
        val rotation = PokemonIconRenderer.iconRotation()
        val anim = IconAnimationResolver.resolve(
            renderable = rp,
            state = st,
            rotation = rotation,
            centerX = posX,
            centerY = posY,
            mouseX = IconRegistry.mouseX,
            mouseY = IconRegistry.mouseY,
            animate = true,
            preview = false
        )

        val matrices = context.matrices
        matrices.push()
        matrices.translate(posX.toDouble(), posY.toDouble(), 0.0)
        try {
            drawProfilePokemon(
                renderablePokemon = rp,
                matrixStack = matrices,
                rotation = rotation,
                poseType = anim.poseType,
                state = st,
                // 传 selfDelta（按真实帧间隔换算）让精灵播放 idle 待机动画（呼吸浮动，与队伍界面同款）——
                // **不能传调用方给的 tickDelta**，理由见本函数开头
                partialTicks = selfDelta,
                // 最大约 200px；scale 参数围绕模型中心缩放，位置不会偏移
                scale = renderScale,
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
                    // 有战吼的物种吼战吼，其余落到 cry。
                    // ⚠ 两条都**必须叠加**、不能做成主动画（`:primary`）：那会把姿势的基础待机动画
                    //   一并 dump 掉、模型掉回 rest pose —— 暴鲤龙整个立起来、化石翼龙翅膀变直纸片
                    //   （2026-09-15 普通叫踩过，2026-09-22 战吼又踩一次，见 playBattleCry 的注释）。
                    if (!IconAnimationResolver.playBattleCry(st, rp)) {
                        st.addFirstAnimation(setOf("cry"))
                    }
                }
            }
        } catch (_: Exception) {
        }
        matrices.pop()
    }
}
