package com.shusheng.cobblemarket.client

import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.currency.ClaimedItem
import com.shusheng.cobblemarket.platform.registerHudRender
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import org.lwjgl.opengl.GL11

/**
 * **定期领取店的一键领取：物品飞入物品栏**（2026-10-23 用户定，接在 [PointsMarketScreen] 的领取按钮上）。
 *
 * 口径（用户拍板）：
 * - **只做物品**，精灵领取不做动画
 * - 每个物品格从**它在商店格子里的位置**出发，飞到**屏幕底部中央**（原版快捷栏中心）并一路缩小到消失
 * - 多格依次错开 40ms（按格子顺序），同一格的多件只飞**一个图标 + ×N 角标**
 *
 * 与 [ItemCelebrationAnimation] 的区别（有意不合并）：那条是「买到 / 拍到」的庆祝，
 * 终点是**屏幕中心**、要停留与弹跳/淡出；这条是「收进背包」，终点在**屏幕底部**、
 * 全程缩小、不停留。曲线与生命周期都不同，硬凑只会让两边都难改。
 *
 * 起点机制照抄庆祝动画（[PointsMarketScreen] 在点「领取」那一刻登记，服务端回执到达时消费）：
 * 界面在关闭前 / 翻页前把坐标抓下来，因为包回来时那些格子可能已经不在了。
 * ⚠ 界面坐标是**虚拟**坐标（GUI 尺寸档位），本动画画在缩放矩阵之外 ⇒ 登记时换算成屏幕坐标。
 */
object ItemClaimFlyAnimation {

    /** 单件飞行的时长（用户选的档：450ms · 缓入 · 缩到消失） */
    private const val FLY_MS = 450f

    /** 相邻两格的起飞间隔：少量错开，不糊成一团 */
    private const val STAGGER_MS = 40L

    /**
     * 终点：原版快捷栏的中心距屏幕底边的距离。
     * 快捷栏贴图高 22、顶边在 `scaledHeight - 22` ⇒ 中心 = `scaledHeight - 11`。
     */
    private const val HOTBAR_CENTER_FROM_BOTTOM = 11f

    /**
     * 登记的起点存活时长。超时视为失效：领取请求可能被节流丢弃 / 服务端最终没发回执，
     * 起点留着的话，下一次领取会从上次那些早已变化的格子位置起飞。
     */
    private const val PENDING_TTL_MS = 3_000L

    /**
     * 数量角标相对图标的字号倍数（图标缩放 1 倍时角标文字约 0.55 倍大小）。
     * 与庆祝动画的「固定 2 倍」不同 —— 那条图标不缩小，而这条一路缩小到 0，
     * 角标必须绑在图标上一起缩，否则图标没了角标还悬着。
     */
    private const val BADGE_REL_SCALE = 0.55f

    /** 角标缩到这个程度就不再画（再小就是个糊点） */
    private const val BADGE_MIN_SCALE = 0.35f

    /** 起点：图标的**中心**屏幕坐标 + 图标相对 16×16 的缩放（与渲染方式同口径） */
    data class Origin(val x: Float, val y: Float, val scale: Float) {
        companion object {
            /** 从一个物品图标矩形换算（[x] / [y] 是图标左上角，[size] 是它在界面里的像素边长） */
            fun ofIcon(x: Int, y: Int, size: Int) = Origin(
                x = x + size / 2f,
                y = y + size / 2f,
                scale = size / 16f
            )
        }
    }

    /** 界面在点击领取那一刻登记的**一格物品**：图标用的物品栈 + 起点 */
    data class Icon(val stack: ItemStack, val origin: Origin)

    private class Flying(val stack: ItemStack, val origin: Origin, val startAt: Long)

    /** 正在飞的（可能还没到起飞时刻 —— [Flying.startAt] 在错开时是未来时间） */
    private val active = mutableListOf<Flying>()

    /** 界面登记的起点（按 entryId 对回服务端的交付明细） */
    private var pendingShopId: String? = null
    private var pendingAt = 0L
    private var pendingIcons: Map<String, Icon> = emptyMap()

    /**
     * 登记起点（由 [PointsMarketScreen] 在点「领取」的**同一刻**调用）。
     *
     * ⚠ 这里就把坐标换算成**屏幕坐标**：起点取自界面（虚拟坐标），而本动画画在缩放矩阵之外
     * （终点按 `window.scaledWidth` 算），两者不在同一坐标系 —— 不换算的话界面被放大时
     * 起点会偏到屏幕左上角外（同 [ItemCelebrationAnimation.setPendingOrigin]，2026-10-04）。
     */
    fun setPending(shopId: String, icons: Map<String, Icon>) {
        val screen = MinecraftClient.getInstance().currentScreen
        val factor = if (screen == null) 1f else GuiScale.factorFor(screen)
        pendingIcons = if (factor == 1f) icons else icons.mapValues { (_, icon) ->
            val o = icon.origin
            Icon(icon.stack, Origin(o.x * factor, o.y * factor, o.scale * factor))
        }
        pendingShopId = shopId
        pendingAt = System.currentTimeMillis()
    }

    /**
     * 服务端回执到达：只给**真的进了背包**且**有可见起点**的格子排动画。
     *
     * ⚠ 过期的起点一律不播（宁可不动，也别从早已不在的位置起飞）；店 id 对不上也不播
     * （玩家可能已经换到另一家店）。
     */
    fun onClaimed(shopId: String, claimed: List<ClaimedItem>) {
        val icons = pendingIcons
        val sameShop = pendingShopId == shopId
        val fresh = System.currentTimeMillis() - pendingAt <= PENDING_TTL_MS
        pendingIcons = emptyMap()
        pendingShopId = null
        if (!sameShop || !fresh) return

        val now = System.currentTimeMillis()
        var seq = 0
        for (item in claimed) {
            if (item.count <= 0) continue
            val icon = icons[item.entryId] ?: continue
            // 复制一份再改数量：登记时那份与格子图标缓存是同一个栈，改了会把商店里的数量一起改掉
            val stack = icon.stack.copy()
            stack.count = item.count
            active += Flying(stack, icon.origin, now + seq * STAGGER_MS)
            seq++
        }
    }

    fun register() {
        // 无界面时走 HUD 阶段；有界面时交给 ScreenMixin（renderWithTooltip 之后）。
        // 两条路径互斥 —— 同帧画两次会让同一件物品叠着画两遍（边缘发亮）。
        registerHudRender { context, delta ->
            if (MinecraftClient.getInstance()?.currentScreen == null) {
                renderOverlay(context, delta)
            }
        }
    }

    /**
     * 唯一渲染入口（HUD 阶段 / ScreenMixin 均走这里）。
     *
     * 与庆祝动画同源，必须做两件事才能置顶：
     *  1. DrawContext 是延迟批处理的 —— 先手动 `draw()` 落屏，动画才排在界面内容之后；
     *  2. GUI 元素已把 z 写进深度缓冲，会把后画的内容整片剔除 —— 清掉深度缓冲。
     */
    fun renderOverlay(context: DrawContext, delta: Float) {
        val now = System.currentTimeMillis()
        active.removeAll { now - it.startAt >= FLY_MS.toLong() }
        if (active.isEmpty()) return

        context.draw()
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
        active.forEach { draw(context, it, now) }
        // 动画自身也走 immediate buffer，立刻提交，别留到帧末尾跟别人混在一起
        context.draw()
    }

    private fun draw(context: DrawContext, f: Flying, now: Long) {
        val elapsed = now - f.startAt
        if (elapsed < 0) return   // 错开时还没轮到它起飞

        val t = (elapsed / FLY_MS).coerceIn(0f, 1f)
        // 位置用「缓入」（t²）：先慢后快，像被吸进物品栏
        val k = t * t
        val client = MinecraftClient.getInstance()
        val targetX = client.window.scaledWidth / 2f
        val targetY = client.window.scaledHeight - HOTBAR_CENTER_FROM_BOTTOM
        val x = f.origin.x + (targetX - f.origin.x) * k
        val y = f.origin.y + (targetY - f.origin.y) * k
        // 缩放按**线性**一路缩到 0（用户选的「一路缩小、到终点自然消失」，不需要淡出）
        val s = f.origin.scale * (1f - t)
        if (s <= 0.01f) return

        val matrices = context.matrices

        // 物品图标基准 16×16：先平移到「图标左上角该在的位置」再整体缩放，
        // 等价于围绕图标中心（x / y）缩放
        matrices.push()
        matrices.translate((x - 8f * s).toDouble(), (y - 8f * s).toDouble(), 0.0)
        matrices.scale(s, s, 1f)
        drawItemWithBar(context, f.stack, 0, 0)
        matrices.pop()

        // 数量角标（>1 才画）：贴在图标右下角，**跟着图标一起缩**
        if (f.stack.count > 1) {
            val badgeScale = s * BADGE_REL_SCALE
            if (badgeScale >= BADGE_MIN_SCALE) {
                val text = "×${f.stack.count}"
                val textRenderer = client.textRenderer
                val w = textRenderer.getWidth(text).toFloat()
                matrices.push()
                matrices.translate(x.toDouble(), y.toDouble(), 0.0)
                matrices.scale(badgeScale, badgeScale, 1f)
                // 除以 badgeScale 是因为后面还会被放大 badgeScale 倍，这样角标右下角正好贴住图标右下角
                matrices.translate(
                    (8f * s / badgeScale - w).toDouble(),
                    (8f * s / badgeScale - 8f).toDouble(),
                    0.0
                )
                context.drawTextWithShadow(textRenderer, Text.literal(text), 0, 0, 0xFFFFFFFF.toInt())
                matrices.pop()
            }
        }
    }
}
