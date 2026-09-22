package com.shusheng.cobblemarket.client.icon

import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import org.lwjgl.opengl.GL11

/**
 * 悬停行的精灵放大预览（屏幕左侧空白区），播走路动画。
 *
 * 姿势由 [IconAnimationResolver] 按「本行是否预览态」决定（预览态 → WALK），实际切换由
 * `drawProfilePokemon` 内部完成。大图与行内图标**共用同一个 [PosableState]**，
 * 所以行里那个小图标会自动跟着一起走 —— 不用逐个界面传参，也不用手动切姿势/还原。
 *
 * 尚未实现：昼夜（晚上切 sleep）、选中优先于悬停、独立开关（`hoverPreview`）、其余 10 个界面。
 */
object PokemonHoverPreview {

    /** 预览缩放，与庆祝动画同款（模型约 200px 高）。 */
    private const val PREVIEW_SCALE = 90f

    /** 模型从原点向下延伸约 200px，故视觉中心在原点下方约 100px 处（算头部朝向要用它当中心点）。 */
    private const val MODEL_HALF_HEIGHT = 100f

    /**
     * 水平左移补偿（正数 = 往左）。
     *
     * 模型并不以我们传入的坐标为中心：按空白区居中摆放时，左侧仍留空隙、右侧却压住面板，
     * 说明模型整体向右延伸得比向左多。这里做一次固定左移抵消（2026-09-11 用户实测反馈）。
     */
    private const val PREVIEW_X_SHIFT = 20f

    /**
     * 画在**面板之上**（上层方案）。调用点必须位于界面 `render()` 的后段。
     *
     * @param panelLeft 面板左边缘 X（列表区左边），据此在左侧空白区取中
     * @param screenHeight 屏幕逻辑高度，用于纵向居中
     * @param centerX **非空 = 居中模式**（聊天播报悬浮预览用）：直接按它当屏幕上的目标中心，
     *                并**跳过开关判断** —— 聊天那一路不归「精灵预览图」开关管（拍板：无条件生效）。
     *                null 走列表那套（面板左侧空白区取中）。两种模式只差横向定位，其余完全一致
     */
    fun render(
        context: DrawContext,
        renderable: RenderablePokemon,
        state: PosableState,
        panelLeft: Int,
        screenHeight: Int,
        centerX: Float? = null
    ) {
        // 设置里「精灵预览图」关掉时不显示（默认开）；居中模式不受开关约束
        if (centerX == null && !com.shusheng.cobblemarket.client.ClientConfig.hoverPreview) return

        // 基准是左侧空白区的中点（面板左缘与屏幕左缘之间），再减去模型自身的偏右量。
        // 注意别加"模型半宽"之类的下限兜底：面板偏窄时那会把模型从中心一路拽到右边去。
        // 居中模式同样要扣这份偏右量：模型不是以原点对称的，直接摆到屏幕中心看起来会偏右
        val x = (centerX ?: panelLeft / 2f) - PREVIEW_X_SHIFT
        // 模型头顶在原点、向下延伸，故上移半高让视觉中心落在屏幕中线（同庆祝动画）
        val y = screenHeight / 2f - MODEL_HALF_HEIGHT

        // flush 夹心 + 清深度（照庆祝动画 / 固拉多上层）：
        // 行内 3D 图标会写深度，不先提交攒批并清深度的话，后画的模型会被它们盖住
        context.draw()
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)

        // 大图同样跟随鼠标（与行内图标行为一致，否则行内的头在转、大图不动，像两只不同的精灵）。
        // 朝向用大图自己的位置算，因此取独立的一份 yaw/pitch 状态（preview = true）。
        val rotation = PokemonIconRenderer.iconRotation()
        val anim = IconAnimationResolver.resolve(
            renderable = renderable,
            state = state,
            rotation = rotation,
            centerX = x,
            centerY = y + MODEL_HALF_HEIGHT,
            mouseX = IconRegistry.mouseX,
            mouseY = IconRegistry.mouseY,
            // 图标选「静态」时大图也静止（用户 2026-09-11 拍板）
            animate = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT,
            preview = true
        )

        val matrices = context.matrices
        matrices.push()
        try {
            matrices.translate(x.toDouble(), y.toDouble(), 0.0)
            drawProfilePokemon(
                renderablePokemon = renderable,
                matrixStack = matrices,
                rotation = rotation,
                poseType = anim.poseType,
                state = state,
                // 传 0 不推进时间：时间轴由行内图标推进，大图读同一基准 → 两者动作天然同步；
                // 且 FloatingState 是累加式的，一帧推两次动画会翻倍。
                partialTicks = 0f,
                scale = PREVIEW_SCALE,
                headYaw = anim.headYaw,
                headPitch = anim.headPitch
            )
            // 携带物：与 drawProfilePokemon 成对，否则大图里的精灵是空手的
            PokemonIconRenderer.drawHeldItem(context, renderable.heldItem, state, matrices)
        } catch (_: Exception) {
        } finally {
            matrices.pop()
        }

        // 模型走 immediate buffer，立刻提交，别留到帧末尾跟别人混在一起
        context.draw()
    }
}
