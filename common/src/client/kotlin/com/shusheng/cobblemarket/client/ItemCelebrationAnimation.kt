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
 * 获得物品的庆祝动画（买到 / 拍到 / 求购单接受交付时由服务端下发触发，渲染在所有界面之上）：
 * 与精灵庆祝动画同一套时间轴——物品图标从屏幕上方落到中心（下落中放大到最大），
 * 然后以最大形态弹跳 3 次（高度递减），第三跳完整落回后静止淡出（约 2.8 秒）。
 *
 * 为什么不跟 [PokemonCelebrationAnimation] 合并成一套：精灵画的是 3D 模型（drawProfilePokemon），
 * 物品画的是 GUI 图标栈（DrawContext.drawItem），矩阵变换、生命周期与淡出手法都不一样，
 * 硬凑在一起只会让两边都难改。真正共用的只有「时间轴」这一组参数——照抄比抽象省事。
 *
 * 触发口径与总开关都在服务端（[com.shusheng.cobblemarket.network.CelebrationNetwork]），
 * 客户端只负责按收到的包排队播放。
 */
object ItemCelebrationAnimation {

    private const val DURATION_MS = 2800L
    /** 图标放大倍数：GUI 物品图标基准 16px，放大 8 倍约 128px（与精灵动画约 200px 同一量级） */
    private const val ICON_SCALE = 8f
    /** 数量角标字号倍数 */
    private const val BADGE_SCALE = 2f

    private data class Pending(val itemNbt: NbtCompound, val count: Int)

    /** 待播队列：一次到手多种物品时依次播完，不互相覆盖 */
    private val queue = ArrayDeque<Pending>()

    private var stack: ItemStack? = null
    private var startTime = 0L
    private var active = false

    /** 本件的登场音是否已响。与精灵那边同理：**必须在第一帧渲染之后**发声，别在构造时发 */
    private var soundPlayed = false

    /** 入队一件；空闲则立即开播，正在播则排队等前面播完 */
    fun trigger(itemNbt: NbtCompound, count: Int, source: String) {
        // 个人开关按来源分流，走的是**物品那一组**（设置弹窗「物品」标题下），与精灵侧各自独立。
        // 未知来源归到低频那组，宁可多播也不要静默吞掉。
        val allowed = if (source == CelebrationSource.MARKET.name)
            ClientConfig.celebrationItemOnMarketBuy
        else
            ClientConfig.celebrationItemOnAuctionAndOrder
        if (!allowed) return
        queue.addLast(Pending(itemNbt, count))
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
     */
    fun renderOverlay(context: DrawContext, delta: Float) {
        if (!active) return
        if (System.currentTimeMillis() - startTime >= DURATION_MS) {
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
    }

    private fun draw(context: DrawContext) {
        val current = stack ?: run { startNext(); return }
        val elapsed = System.currentTimeMillis() - startTime
        val client = MinecraftClient.getInstance()
        val scaledW = client.window.scaledWidth
        val scaledH = client.window.scaledHeight

        // ── 弹跳球效果：时间轴与精灵动画逐参数一致（见 PokemonCelebrationAnimation.draw） ──
        val t = elapsed.toFloat() / DURATION_MS
        fun smooth(k: Float): Float = k * k * (3f - 2f * k)

        val dropEnd = 0.24f // 前 24% 时间：下落 + 放大
        val bounceEnd = 0.88f // 弹跳段结束，之后是独立淡出段
        val offsetY: Float
        val scaleFactor: Float
        val alpha: Float
        if (t < dropEnd) {
            // 下落：从中心上方 220px 落到中心（缓出），缩放 0→1
            val k = smooth(t / dropEnd)
            offsetY = -220f + 220f * k
            scaleFactor = k
            alpha = 1f
        } else if (t < bounceEnd) {
            // 弹跳：3 次，高度递减（60→40→22），抛物线（sin 曲线）
            val bt = (t - dropEnd) / (bounceEnd - dropEnd)
            val bouncePhase = bt * 3f
            val hopIndex = minOf(bouncePhase.toInt(), 2)
            val heights = floatArrayOf(60f, 40f, 22f)
            val h = heights[hopIndex]
            offsetY = -h * MathHelper.sin((bouncePhase % 1f) * PI.toFloat())
            scaleFactor = 1f
            alpha = 1f
        } else {
            // 淡出：第三跳完整落回后静止淡出
            offsetY = 0f
            scaleFactor = 1f
            alpha = 1f - (t - bounceEnd) / (1f - bounceEnd)
        }

        // 登场音：物品用拾取声（精灵那边是战吼）。只响一次，且在第一帧之后
        if (!soundPlayed) {
            soundPlayed = true
            client.soundManager.play(PositionedSoundInstance.master(SoundEvents.ENTITY_ITEM_PICKUP, 1f))
        }

        val s = ICON_SCALE * scaleFactor
        val centerX = scaledW / 2f
        val centerY = scaledH / 2f + offsetY
        val textRenderer = client.textRenderer
        val matrices = context.matrices

        // 物品图标基准 16×16：先平移到「图标左上角该在的位置」再整体缩放，
        // 等价于围绕图标中心（屏幕中心 + offsetY）缩放
        matrices.push()
        matrices.translate((centerX - 8f * s).toDouble(), (centerY - 8f * s).toDouble(), 0.0)
        matrices.scale(s, s, 1f)
        // 淡出走 shader 颜色调制器（GUI 物品渲染走实体渲染层，读它）。
        // ⚠ 若实测物品不跟着变淡（某些版本里 ItemRenderer 会重置 shader 颜色），
        //   改用「上浮 + 缩小」代替淡出，别在这里死磕。
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha)
        context.drawItem(current, 0, 0)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        matrices.pop()

        // 数量角标（>1 才画）：贴在图标右下角。字号固定 2x 不随图标缩放——按图标大小缩放会小到看不清
        if (current.count > 1) {
            val text = current.count.toString()
            val w = textRenderer.getWidth(text).toFloat()
            val a = (alpha * 255f).toInt().coerceIn(0, 255)
            matrices.push()
            matrices.translate(centerX.toDouble(), centerY.toDouble(), 0.0)
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
