package com.shusheng.cobblemarket.client

import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.network.CelebrationSource
import com.shusheng.cobblemarket.platform.registerHudRender
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.MathHelper
import org.lwjgl.opengl.GL11
import kotlin.math.PI

/**
 * 获得物品的庆祝动画（买到 / 拍到 / 求购单接受交付时由服务端下发触发，渲染在所有界面之上）。
 *
 * 两种入场方式与精灵**同名**（全项目统一叫法），但**曲线不同**（用户 2026-09-15 拍板）：
 * - **图标起飞**：飞到屏幕中心 → 停留 → 淡出。**不弹跳**：物品图标在弹窗里多落在左侧，
 *   飞行基本是水平向右的，到中心再转成垂直弹跳会是个 90° 的急转弯，很别扭。
 *   比精灵那条只短在**飞行段**（起点在屏幕内，不用从屏幕上方外侧飞过来），停留时间一样长。
 * - **顶部空降**：屏幕上方落下 → 弹跳 3 次 → 淡出。这条**保留弹跳**：它本来就是垂直下落的，
 *   方向天然一致；而物品是张 2D 死图，落到中心干停着太呆，得靠弹跳给动感。
 *
 * 与精灵动画在实现上不合并（[PokemonCelebrationAnimation]）：精灵画 3D 模型（要管 FloatingState、
 * 姿势、战吼），物品画 GUI 图标栈（矩阵变换与淡出手法都不同），硬凑只会让两边都难改。
 *
 * 触发口径与总开关都在服务端（[com.shusheng.cobblemarket.network.CelebrationNetwork]），
 * 客户端只负责按收到的包排队播放。
 */
object ItemCelebrationAnimation {

    // ── 图标起飞的时间线：飞行 → 停留 → 淡出 ──
    // 「停留」与精灵那条同长（1600）：能上拍卖场的物品多是稀罕物，多看一会儿无妨；
    // 短的是**飞行段** —— 起点就在屏幕内（弹窗里的物品图标），不用像精灵从屏幕上方外侧那样飞那么久。
    private const val FLY_MS = 700f
    private const val HOLD_MS = 1_600f
    private const val FLY_FADE_MS = 400f
    private const val FLY_DURATION_MS = 2_700L   // = FLY + HOLD + FLY_FADE

    // ── 顶部空降的时间线：下落 → 弹跳 3 次 → 淡出（沿用改造前的参数：24% / 64% / 12% × 2800）──
    private const val DROP_MS = 672f
    private const val BOUNCE_MS = 1_792f
    private const val DROP_FADE_MS = 336f
    private const val DROP_DURATION_MS = 2_800L  // = DROP + BOUNCE + DROP_FADE

    /** 图标放大倍数：GUI 物品图标基准 16px，放大 8 倍约 128px（与精灵动画约 200px 同一量级） */
    private const val ICON_SCALE = 8f
    /** 数量角标字号倍数 */
    private const val BADGE_SCALE = 2f

    /** **顶部空降**的起点：屏幕上方外侧的纵向偏移（相对屏幕中心） */
    private const val SCRATCH_FROM_Y = 220f
    /** 顶部空降的起点缩放（相对终点）。照精灵那套，**不能是 0**（第一帧会整个看不见） */
    private const val SCRATCH_FROM_SCALE = 0.2f

    /** 弹跳高度（3 次递减），沿用改造前的数值 */
    private val BOUNCE_HEIGHTS = floatArrayOf(60f, 40f, 22f)

    /**
     * 登记的起点存活时长。超时视为失效：服务端可能最终没发庆祝包（个人开关关、请求失败），
     * 起点留着的话，下一次别的入口触发会从上次那个早已消失的图标位置起飞（理由同精灵那边）。
     */
    private const val PENDING_ORIGIN_TTL_MS = 3_000L

    /**
     * **图标起飞**的起点：界面里那个物品图标的屏幕位置与缩放。
     *
     * [scale] 照抄本动画的渲染方式 —— `matrices.scale(s, s)` 作用在 16×16 的图标上，
     * 所以「和弹窗里画得一样大」= `size / 16f`，而终点是 [ICON_SCALE]。
     */
    data class Origin(val x: Float, val y: Float, val scale: Float) {
        companion object {
            /** 从一个物品图标矩形换算（[x] / [y] 是图标左上角的屏幕坐标，[size] 是它在界面里的像素边长） */
            fun ofIcon(x: Int, y: Int, size: Int) = Origin(
                x = x + size / 2f,
                y = y + size / 2f,
                scale = size / 16f
            )
        }
    }

    private data class Pending(val itemNbt: NbtCompound, val count: Int, val origin: Origin?)

    /** 待播队列：一次到手多种物品时依次播完，不互相覆盖 */
    private val queue = ArrayDeque<Pending>()

    private var stack: ItemStack? = null
    /** 当前这件物品的飞行起点；null = 顶部空降 */
    private var origin: Origin? = null
    /** 本件的总时长 —— 两条入场不同，[startNext] 里按起点选定 */
    private var currentDurationMs = DROP_DURATION_MS
    private var startTime = 0L
    private var active = false

    /** 本件的登场音是否已响。与精灵那边同理：**必须在第一帧渲染之后**发声，别在构造时发 */
    private var soundPlayed = false

    /** 界面在关闭前登记的起飞起点（见 [Origin]）。用后即清、且带过期 —— 理由见 [PENDING_ORIGIN_TTL_MS] */
    private var pendingOrigin: Origin? = null
    private var pendingOriginAt = 0L

    /** 界面在关闭前登记起飞起点 —— 弹窗一关那个图标就不在了，坐标必须提前抓下来 */
    fun setPendingOrigin(origin: Origin?) {
        pendingOrigin = origin
        pendingOriginAt = System.currentTimeMillis()
    }

    /** 取走登记的起点并清空；超过 [PENDING_ORIGIN_TTL_MS] 视为失效（返回 null → 走顶部空降） */
    private fun takePendingOrigin(): Origin? {
        val taken = pendingOrigin ?: return null
        pendingOrigin = null
        return if (System.currentTimeMillis() - pendingOriginAt <= PENDING_ORIGIN_TTL_MS) taken else null
    }

    /** 入队一件；空闲则立即开播，正在播则排队等前面播完 */
    fun trigger(itemNbt: NbtCompound, count: Int, source: String) {
        // 个人开关按来源分流，走的是**物品那一组**（设置弹窗「物品」标题下），与精灵侧各自独立。
        // ⚠ 主市场与 **RP 商城**同组（都是"在市场上直接买"，2026-09-25 拍板）。
        // 未知来源归到低频那组，宁可多播也不要静默吞掉。
        val allowed = if (source == CelebrationSource.MARKET.name || source == CelebrationSource.STORE.name)
            ClientConfig.celebrationItemOnMarketBuy
        else
            ClientConfig.celebrationItemOnAuctionAndOrder
        if (!allowed) return
        // 起点在入队时取走：三个入口都是「关弹窗前登记」，没有拍卖精灵那种「包到达时现场取」的需求
        queue.addLast(Pending(itemNbt, count, takePendingOrigin()))
        if (!active) startNext()
    }

    /** 取队首开播；重建失败的（物品已删 / ID 无效等）跳过换下一件，队列空则回到空闲 */
    private fun startNext() {
        // 物品栈要靠注册表重建，没有世界就没有注册表——直接收摊（正常流程下服务端不会在此时发包）
        val registryManager = MinecraftClient.getInstance().world?.registryManager ?: run { reset(); return }
        while (true) {
            val next = queue.removeFirstOrNull() ?: run { reset(); return }
            val built = try {
                ItemStack.fromNbtOrEmpty(registryManager, next.itemNbt)
            } catch (_: Exception) {
                continue
            }
            if (built.isEmpty) continue
            // 挂单 NBT 里存的是上架总量，到手数量以包里单独传的为准
            built.count = next.count.coerceAtLeast(1)
            stack = built
            origin = next.origin
            currentDurationMs = if (next.origin != null) FLY_DURATION_MS else DROP_DURATION_MS
            soundPlayed = false
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
     * 光把绘制调用挪到最后并不能让动画置顶，必须处理两件事（与精灵动画同源）：
     *  1. DrawContext 是延迟批处理的，界面内容只是攒在 buffer 里——先手动 draw() 落屏，动画才排在它们之后；
     *  2. GUI 元素已把 z 写进深度缓冲，会把后画的内容整片剔除——清掉深度缓冲即可。
     *
     * [delta] 只是沿用外部调用签名（ScreenMixin / HUD 两处），本动画的时间轴一律按真实时间推进，
     * 不读它 —— 画的是 2D 图标、不碰 `FloatingState`，因此也没有精灵那边「时间轴跟帧率跑」的毛病。
     */
    fun renderOverlay(context: DrawContext, delta: Float) {
        if (!active) return
        if (System.currentTimeMillis() - startTime >= currentDurationMs) {
            startNext()          // 播下一件；队列空了 active 转 false
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
        stack = null
        origin = null
    }

    private fun draw(context: DrawContext) {
        val current = stack ?: run { startNext(); return }
        val elapsedMs = (System.currentTimeMillis() - startTime).toFloat()
        val client = MinecraftClient.getInstance()
        val scaledW = client.window.scaledWidth
        val scaledH = client.window.scaledHeight

        fun smooth(k: Float): Float = k * k * (3f - 2f * k)

        // 落点：屏幕正中心（物品图标以中心定位，不像精灵那样要减去模型半高）
        val homeX = scaledW / 2f
        val homeY = scaledH / 2f
        val homeScale = ICON_SCALE

        val start = origin
        val posX: Float
        val posY: Float
        val renderScale: Float
        val alpha: Float
        if (start != null) {
            // ── 图标起飞：飞到中心 → 停留 → 淡出 ──
            // 两端（图标态、庆祝态）各自都是已验证的渲染方式，中间插值即可，起飞与落点都不会跳
            if (elapsedMs < FLY_MS) {
                val k = smooth(elapsedMs / FLY_MS)
                posX = start.x + (homeX - start.x) * k
                posY = start.y + (homeY - start.y) * k
                renderScale = start.scale + (homeScale - start.scale) * k
                alpha = 1f
            } else if (elapsedMs < FLY_MS + HOLD_MS) {
                posX = homeX
                posY = homeY
                renderScale = homeScale
                alpha = 1f
            } else {
                posX = homeX
                posY = homeY
                renderScale = homeScale
                alpha = 1f - (elapsedMs - FLY_MS - HOLD_MS) / FLY_FADE_MS
            }
        } else {
            // ── 顶部空降：下落 → 弹跳 3 次 → 淡出 ──
            val fromY = homeY - SCRATCH_FROM_Y
            if (elapsedMs < DROP_MS) {
                // 下落：从屏幕上方外侧落到中心（缓出），缩放同步放大到最大
                val k = smooth(elapsedMs / DROP_MS)
                posX = homeX
                posY = fromY + (homeY - fromY) * k
                renderScale = homeScale * SCRATCH_FROM_SCALE + (homeScale - homeScale * SCRATCH_FROM_SCALE) * k
                alpha = 1f
            } else if (elapsedMs < DROP_MS + BOUNCE_MS) {
                // 弹跳：3 次，高度递减（60→40→22），抛物线（sin 曲线），保持最大形态
                val bt = (elapsedMs - DROP_MS) / BOUNCE_MS
                val bouncePhase = bt * 3f
                val hopIndex = minOf(bouncePhase.toInt(), 2)
                val h = BOUNCE_HEIGHTS[hopIndex]
                posX = homeX
                posY = homeY - h * MathHelper.sin((bouncePhase % 1f) * PI.toFloat())
                renderScale = homeScale
                alpha = 1f
            } else {
                // 淡出：第三跳完整落回后静止淡出
                posX = homeX
                posY = homeY
                renderScale = homeScale
                alpha = 1f - (elapsedMs - DROP_MS - BOUNCE_MS) / DROP_FADE_MS
            }
        }

        // 登场音：物品用拾取声（精灵那边是战吼）。只响一次，且在第一帧之后
        if (!soundPlayed) {
            soundPlayed = true
            client.soundManager.play(PositionedSoundInstance.master(SoundEvents.ENTITY_ITEM_PICKUP, 1f))
        }

        val s = renderScale
        val textRenderer = client.textRenderer
        val matrices = context.matrices

        // 物品图标基准 16×16：先平移到「图标左上角该在的位置」再整体缩放，
        // 等价于围绕图标中心（posX / posY）缩放
        matrices.push()
        matrices.translate((posX - 8f * s).toDouble(), (posY - 8f * s).toDouble(), 0.0)
        matrices.scale(s, s, 1f)
        // 淡出走 shader 颜色调制器（GUI 物品渲染走实体渲染层，读它）。
        // ⚠ 若实测物品不跟着变淡（某些版本里 ItemRenderer 会重置 shader 颜色），
        //   改用「上浮 + 缩小」代替淡出，别在这里死磕。
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha)
        drawItemWithBar(context, current, 0, 0)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        matrices.pop()

        // 数量角标（>1 才画）：贴在图标右下角。字号固定 2x 不随图标缩放——按图标大小缩放会小到看不清
        if (current.count > 1) {
            val text = current.count.toString()
            val w = textRenderer.getWidth(text).toFloat()
            val a = (alpha * 255f).toInt().coerceIn(0, 255)
            matrices.push()
            matrices.translate(posX.toDouble(), posY.toDouble(), 0.0)
            matrices.scale(BADGE_SCALE, BADGE_SCALE, 1f)
            // 除以 BADGE_SCALE 是因为后面还会被放大 BADGE_SCALE 倍，这样角标右下角正好贴住图标右下角
            matrices.translate(
                (8f * s / BADGE_SCALE - w).toDouble(),
                (8f * s / BADGE_SCALE - 8f).toDouble(),
                0.0
            )
            context.drawTextWithShadow(textRenderer, text, 0, 0, (a shl 24) or 0xFFFFFF)
            matrices.pop()
        }
    }
}
