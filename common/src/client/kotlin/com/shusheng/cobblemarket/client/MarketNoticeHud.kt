package com.shusheng.cobblemarket.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.client.icon.IconAnimationResolver
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.shusheng.cobblemarket.network.MarketNoticePayload
import com.shusheng.cobblemarket.platform.registerHudRender
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ItemStack
import net.minecraft.util.Identifier
import org.lwjgl.opengl.GL11
import kotlin.math.exp
import kotlin.math.abs

/**
 * **上架通知**：别人在市场 / 拍卖场上了新东西时，屏幕顶部弹一个圆形气泡（用户 2026-10-07 定）。
 *
 * ## 显示规则（用户逐条拍板）
 *
 * - **位置**：屏幕**顶部居中**、离顶留一点空隙（像聊天气泡）
 * - **上限 3 个**：超出时**最左边那个边左移边淡出**，不是硬切
 * - **进入**：新气泡从**屏幕上方落到它自己的槽位**（第 1 个就是落在正中央）
 *   ⚠ **不是"都从中央落下再挤开"** —— 那样新老会在中间叠一下（用户自己指出来的）
 * - **重排**：数量一变就**整组重新居中** —— 来新的整体左移、走老的剩下整体右靠
 * - **到期（3 秒）与被挤走，走同一套淡出**（视觉一致）
 *
 * ## 画什么
 *
 * 底图 `notification.png`（圆形底板），**圆心里画那只精灵的 3D 图 / 那件物品的图标**；
 * 精灵一侧拿不到图就**什么都不画**（留白，与快速展示那两个大按钮同一口径 —— 用户定）。
 *
 * ## ⚠ 几个坑
 *
 * - **不能用 [PokemonIconRenderer.draw]**：它会往 `IconRegistry` 登记图标 —— 而 HUD 层不在
 *   `IconRegistry.beginFrame` 的每帧周期里，登记表会**逐帧累积**，污染其它界面的点击叫声命中。
 *   这里照 [PokemonCelebrationAnimation] 的做法**直接调 `drawProfilePokemon`**。
 * - **3D 之前必须先 flush 拼批 + 清深度缓冲**，否则模型会被先前画的 GUI 整片剔除（老坑，见 `docs/icon_animation.md` 第十节）。
 * - **时间自己推**：HUD 不归任何界面的 `delta` 管，照庆祝动画那样按真实帧间隔换算（`FloatingState` 是累加式的）。
 */
object MarketNoticeHud {

    /** 气泡直径（`notification.png` 是 256×256，缩到这么大） */
    private const val SIZE = 40

    /** `notification.png` 的**真实像素尺寸**（⚠ 换图时必须一起改，否则缩放会错） */
    private const val TEX_SIZE = 256

    /** 两个气泡之间的间距 */
    private const val GAP = 8

    /** 离屏幕顶部的空隙（2026-10-07 用户实测：6 太大 ⇒ 收到 2） */
    private const val TOP_MARGIN = 2

    /** 同时最多显示几个（超出的从最左边淡出） */
    private const val MAX_VISIBLE = 3

    /** 单个气泡停留多久（用户定：先 3 秒看效果） */
    private const val HOLD_MS = 3000L

    /** 入场时长（从槽位上方掉下来那一段） */
    private const val ENTER_MS = 220f

    /**
     * 淡出时长（被挤走 / 到期都用它）。
     *
     * ⚠ 2026-10-07 用户实测：原本与入场共用 220ms ⇒「太快，看起来就像直接消失」⇒ 单独拉长到 450。
     * 淡出同时带**左移**，时长加长后滑走也变得可见（见 `render` 里给 `targetX` 减的那段）。
     */
    private const val FADE_MS = 450f

    /** 入场起点在槽位上方多高（掉下来那段的长度） */
    private const val ENTER_FROM = 46f

    /** 位置趋近速度（每秒收敛比例，越大越"跟手"） */
    private const val FOLLOW_SPEED = 14f

    /** 单帧最多推进的等价 tick 数（照庆祝动画，防卡顿后一次性跳完） */
    private const val MAX_SELF_DELTA = 2f

    private val TEXTURE = Identifier.of("cobblemarket", "textures/gui/notification/notification.png")

    /**
     * 通知提示音的音效 id（服务端 `broadcastMarketNotice` 发、`sounds.json` 里注册）。
     *
     * 公开出来是给客户端的播放入口把关用 —— 设置里那个「通知提示音」开关**只拦这一条**，
     * 其余音效不受影响（见 `CobbleMarketClient` 里 `PlaySoundPayload` 的 handler）。
     */
    const val SOUND_ID = "market_notice"

    /** 气泡里画的东西：精灵或物品，二选一（与 `MarketNoticePayload.isPokemon` 对应） */
    private class Notice(
        val isPokemon: Boolean,
        /** 精灵：重建好的渲染对象（含形态 / 闪光 / 携带物）；物品时为 null */
        val renderable: RenderablePokemon?,
        /** 精灵自己的跨帧动画状态 */
        val state: FloatingState?,
        val stack: ItemStack?,
        val bornAt: Long
    ) {
        /** 当前渲染位置（每帧向 [targetX] / [targetY] 趋近） */
        var x: Float = Float.NaN
        var y: Float = Float.NaN
        var targetX: Float = 0f
        var targetY: Float = 0f

        /** 入场进度 0→1（0 = 还在槽位上方） */
        var entered: Boolean = false

        /** 正在淡出（到期或被挤走），淡完就移除 */
        var dyingAt: Long = 0L
    }

    /** 占着槽位的（最多 [MAX_VISIBLE] 个，下标 0 = 最左 = 最老） */
    private val live = ArrayDeque<Notice>()

    /** 正在淡出的（不占槽位，浮在列表外自己滑走） */
    private val fading = mutableListOf<Notice>()

    /** 上一帧的真实时间戳（纳秒），用来自己算帧间隔 —— 与庆祝动画同一手法 */
    private var lastFrameNanos = 0L

    /** 客户端收到通知包时调用（在客户端主线程） */
    fun push(payload: MarketNoticePayload) {
        // 开关①：关掉就什么都不显示（音效那条由 `CobbleMarketClient` 的播放入口另行把关）
        if (!ClientConfig.marketNotice) return

        val stack: ItemStack?
        val renderable: RenderablePokemon?
        if (payload.isPokemon) {
            val speciesId = Identifier.tryParse(payload.speciesId) ?: return
            val species = PokemonSpecies.getByIdentifier(speciesId) ?: return
            // 形态照各界面统一口径：挂单真实 aspects + 闪光补 "shiny"
            val aspects = payload.aspects.toMutableSet()
            if (payload.shiny && "shiny" !in aspects) aspects.add("shiny")
            val held = heldItemStackOf(payload)
            renderable = RenderablePokemon(species, aspects, if (payload.heldItemVisible) held else ItemStack.EMPTY)
            stack = null
        } else {
            val nbt = payload.itemNbt ?: return
            val registry = MinecraftClient.getInstance().world?.registryManager ?: return
            val built = ItemStack.fromNbtOrEmpty(registry, nbt)
            if (built.isEmpty) return
            built.count = payload.count.coerceAtLeast(1)
            stack = built
            renderable = null
        }

        val notice = Notice(
            isPokemon = payload.isPokemon,
            renderable = renderable,
            state = if (payload.isPokemon) FloatingState() else null,
            stack = stack,
            bornAt = System.currentTimeMillis()
        )
        live.addLast(notice)
        // 超出上限：最老的那个转去淡出（**边左移边淡出**，见 render 里的处理）
        while (live.size > MAX_VISIBLE) {
            val oldest = live.removeFirst()
            oldest.dyingAt = System.currentTimeMillis()
            // 淡出起点定为"它现在的位置"，避免它一上来就瞬移到左边
            if (!oldest.x.isNaN()) {
                oldest.targetX = oldest.x - SIZE * 0.8f
            }
            fading.add(oldest)
        }
    }

    /** 携带物：SNBT → ItemStack（与庆祝动画同款处理） */
    private fun heldItemStackOf(payload: MarketNoticePayload): ItemStack {
        if (payload.heldItemNbt.isEmpty()) return ItemStack.EMPTY
        val registry = MinecraftClient.getInstance().world?.registryManager ?: return ItemStack.EMPTY
        return runCatching {
            val nbt = net.minecraft.nbt.StringNbtReader.parse(payload.heldItemNbt)
            ItemStack.fromNbtOrEmpty(registry, nbt)
        }.getOrDefault(ItemStack.EMPTY)
    }

    /** 客户端启动时注册一次 */
    fun register() {
        registerHudRender { context, _ -> render(context) }
    }

    private fun render(context: DrawContext) {
        if (live.isEmpty() && fading.isEmpty()) return
        val now = System.currentTimeMillis()

        // 自己算帧间隔（照庆祝动画）：HUD 拿不到界面那份 tickDelta，而 FloatingState 是累加式的
        val nowNanos = System.nanoTime()
        val selfDelta = if (lastFrameNanos == 0L) 0f
        else ((nowNanos - lastFrameNanos) / 1_000_000_000f * 20f).coerceAtMost(MAX_SELF_DELTA)
        lastFrameNanos = nowNanos

        // ── ① 到期判定：活够 3 秒的转淡出 ──
        // ⚠ **到期与"被挤走"不是同一套**（2026-10-07 用户指出）：到期是**原地淡出**（不位移），
        //   只有被第 4 个挤走那个才带左滑 —— 左移是"被挤出去"的语义，只有一个气泡时左移莫名其妙。
        //   两者共用 [FADE_MS] 的时长与透明度曲线，只是位移与否不同。
        while (live.isNotEmpty() && now - live.first().bornAt >= HOLD_MS) {
            val expired = live.removeFirst()
            expired.dyingAt = now
            // 不设 targetX ⇒ 保持它在 live 里最后算出的槽位 ⇒ 原地淡出
            fading.add(expired)
        }

        // ── ② 槽位：整组水平居中（数量一变就整体重排 —— 来新的左移、走老的右靠）──
        val n = live.size
        val totalW = n * SIZE + (n - 1) * GAP
        val startX = (context.scaledWindowWidth - totalW) / 2f
        live.forEachIndexed { i, notice ->
            notice.targetX = startX + i * (SIZE + GAP)
            notice.targetY = TOP_MARGIN.toFloat()
        }
        // ⚠ 淡出中的**不参与上面这套重排**：它的 targetX / targetY 保持转出时的值 ——
        //   到期的原地不动、被挤走的继续往左滑（见上面那两处）；剩下的 live 才重新居中。

        // ── ③ 位置趋近（与帧率无关的指数收敛）──
        val dt = selfDelta / 20f
        val k = (1f - exp(-FOLLOW_SPEED * dt.toDouble())).toFloat()
        fun step(notice: Notice) {
            if (notice.x.isNaN()) { notice.x = notice.targetX; notice.y = notice.targetY }
            notice.x += (notice.targetX - notice.x) * k
            notice.y += (notice.targetY - notice.y) * k
        }
        live.forEach(::step)
        fading.forEach(::step)

        // ── ④ 绘制 ──
        (live + fading).forEach { notice ->
            val alpha = alphaOf(notice, now)
            if (alpha <= 0.01f) return@forEach
            // 入场：从槽位上方掉进来（只对还活着的生效；淡出中的不再参与）
            val enterOffset = if (!notice.entered && notice.dyingAt == 0L) {
                val t = ((now - notice.bornAt) / ENTER_MS).coerceIn(0f, 1f)
                if (t >= 1f) notice.entered = true
                -(1f - t) * (1f - t) * ENTER_FROM       // ease-out，跟快速展示同款
            } else 0f
            drawOne(context, notice, notice.x, notice.y + enterOffset, alpha, selfDelta)
        }

        // ── ⑤ 清理淡完的 ──
        fading.removeIf { now - it.dyingAt >= FADE_MS.toLong() }

        // ⚠ 全部空了要把帧基准复位，否则下次来通知时第一帧会带一个巨大的 delta
        if (live.isEmpty() && fading.isEmpty()) lastFrameNanos = 0L
    }

    /** 淡出进度 → 透明度；没在淡出的恒 1 */
    private fun alphaOf(notice: Notice, now: Long): Float {
        if (notice.dyingAt == 0L) return 1f
        val t = ((now - notice.dyingAt) / FADE_MS).coerceIn(0f, 1f)
        return 1f - t
    }

    /** 画一个气泡：底板 + 圆心里的精灵/物品 */
    private fun drawOne(context: DrawContext, notice: Notice, x: Float, y: Float, alpha: Float, selfDelta: Float) {
        val ix = x.toInt()
        val iy = y.toInt()
        val prevColor = FloatArray(4)
        RenderSystem.getShaderColor().let { prevColor[0] = it[0]; prevColor[1] = it[1]; prevColor[2] = it[2]; prevColor[3] = it[3] }
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha)
        try {
            // 底板：整张 256×256 缩到 SIZE。
            // ⚠⭐ `drawTexture` 的 `width`/`height` 是**【采样区】**（从纹理里取多大一块），不是目标尺寸 ——
            //   直接传 SIZE 只会画出左上角那一小块。要缩放必须自己上矩阵（`docs/gui_pitfalls.md` 里记过这条）。
            context.matrices.push()
            try {
                context.matrices.translate(ix.toDouble(), iy.toDouble(), 0.0)
                // ⚠ 必须 `toFloat()`：Kotlin 里 Int / Int 是整数除法，40 / 256 会直接得 0（气泡整个消失）
                val s = SIZE.toFloat() / TEX_SIZE
                context.matrices.scale(s, s, 1f)
                context.drawTexture(TEXTURE, 0, 0, 0f, 0f, TEX_SIZE, TEX_SIZE, TEX_SIZE, TEX_SIZE)
            } finally {
                context.matrices.pop()
            }

            if (notice.isPokemon) {
                val renderable = notice.renderable ?: return
                val state = notice.state ?: return
                // 圆心：往里缩一点，别压到圆环（⚠ 别缩太多 —— 底板那圈圆环本身很粗，缩过头精灵就小得看不见）
                val inset = SIZE * 0.12f
                val cx = x + SIZE / 2f
                // ⚠⭐ **锚点是模型【头顶】，不是圆心**：`drawProfilePokemon` 画的模型从原点**向下延伸**
                //   （`PokemonIconRenderer` 也是这么用的 —— 它传图标顶边 `y + 1`）。取「内容区顶边」正好
                //   等于「模型竖直居中于圆内」：内容区高 = 模型高，其中心即圆心。
                //   ⚠ 传圆心会让模型从中间往下长、下半截掉出圆外（2026-10-07 用户实测报的"偏下、超出底部"）。
                val cy = y + inset
                val rotation = PokemonIconRenderer.iconRotation()
                // ⚠⭐ **鼠标坐标必须自己算，不能用 `IconRegistry.mouseX/mouseY`**：
                //   那份是由各界面在 `render()` 开头调 `beginFrame` 刷新的，而通知气泡经常在**世界里
                //   （没有界面）**弹出 —— 那时读到的是**上一个界面的旧坐标**，精灵会盯着一个过期位置看。
                //   也不能在这里调 `beginFrame` 补救：它会**清空图标注册表**，把别人的点击命中判定搞坏。
                //   算法照 `MouseMixin` 注释里的原版口径：`mouse.x` 是物理坐标，逻辑坐标 = 物理 / 缩放系数
                val client = MinecraftClient.getInstance()
                val logicalMouseX = (client.mouse.x / client.window.scaleFactor).toInt()
                val logicalMouseY = (client.mouse.y / client.window.scaleFactor).toInt()
                val anim = IconAnimationResolver.resolve(
                    renderable = renderable,
                    state = state,
                    rotation = rotation,
                    centerX = cx,
                    centerY = cy,
                    mouseX = logicalMouseX,
                    mouseY = logicalMouseY,
                    // 静态模式下这里也不动（与其余界面同一判据）
                    animate = ClientConfig.iconAnimMode == IconAnimMode.FLOAT,
                    preview = false
                )
                val matrices = context.matrices
                val scale = (SIZE - inset * 2) / 25f * 2.5f
                matrices.push()
                try {
                    matrices.translate(cx.toDouble(), cy.toDouble(), 0.0)
                    matrices.scale(scale, scale, 1f)
                    // ⚠ 3D 之前必须 flush 拼批 + 清深度（否则会被先前画的 GUI 整片剔除）
                    context.draw()
                    RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
                    // ⚠ 走 species 重载 + 自己喂 aspects（那个重载不暴露 doQuirks，见 docs/icon_animation.md「二、6」）
                    state.currentAspects = renderable.aspects
                    drawProfilePokemon(
                        species = renderable.species.resourceIdentifier,
                        matrixStack = matrices,
                        rotation = rotation,
                        poseType = anim.poseType,
                        state = state,
                        partialTicks = if (ClientConfig.iconAnimMode == IconAnimMode.FLOAT) selfDelta else 0f,
                        // ⚠⭐ 必须是 `ICON_SCALE`（4.5）—— `PokemonIconRenderer` 也是「矩阵缩放 × 4.5」两步走，
                        //   只做矩阵那步、这里传 1f 的话，精灵会小到 1/4.5（2026-10-07 用户实测报"太小了"）
                        scale = PokemonIconRenderer.ICON_SCALE,
                        doQuirks = true,
                        headYaw = anim.headYaw,
                        headPitch = anim.headPitch,
                        r = alpha, g = alpha, b = alpha, a = alpha
                    )
                    context.draw()
                } finally {
                    matrices.pop()
                }
            } else {
                val stack = notice.stack ?: return
                // 物品：按气泡大小缩放（16px 图标 → 内容区那么宽），居中放
                val s = (SIZE * 0.56f) / 16f
                context.matrices.push()
                try {
                    context.matrices.translate(
                        (x + SIZE / 2f - 8f * s).toDouble(),
                        (y + SIZE / 2f - 8f * s).toDouble(),
                        0.0
                    )
                    context.matrices.scale(s, s, 1f)
                    drawItemWithBar(context, stack, 0, 0)
                } finally {
                    context.matrices.pop()
                }
            }
        } finally {
            RenderSystem.setShaderColor(prevColor[0], prevColor[1], prevColor[2], prevColor[3])
        }
    }
}
