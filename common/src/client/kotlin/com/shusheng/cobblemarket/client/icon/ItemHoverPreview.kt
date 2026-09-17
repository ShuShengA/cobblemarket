package com.shusheng.cobblemarket.client.icon

import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.client.ClientConfig
import com.shusheng.cobblemarket.client.drawItemWithBar
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ItemStack
import org.lwjgl.opengl.GL11

/**
 * 悬停格的**物品**放大预览（屏幕左侧空白区）。
 *
 * 与精灵版 [PokemonHoverPreview] 并列而非合并：精灵画 3D 模型（`drawProfilePokemon`）、
 * 物品画 GUI 图标栈（`DrawContext.drawItem`），两条渲染路径没有共用部分。
 * 位置口径与精灵版完全一致：x 取「面板左缘与屏幕左缘的中点」，y 取屏幕中线。
 *
 * 物品没有骨架动画，所以这里是**纯静态**的放大图标（数量角标跟着一起画）。
 */
object ItemHoverPreview {

    /** 放大倍数：GUI 物品图标基准 16px，8 倍约 128px（视觉重量与精灵版约 200px 高的模型相称） */
    private const val PREVIEW_SCALE = 8f

    /**
     * 画在**面板之下**：调用点必须位于界面 `render()` 中 `super.render()` 之前
     * ——面板在 `renderBackground` 里画，会自然盖住溢出到面板上的部分。
     *
     * @param stack 从挂单 NBT 重建的物品栈（**只用于取物品外观**）
     * @param panelLeft 面板左边缘 X（列表区左边），据此在左侧空白区取中
     * @param screenHeight 屏幕逻辑高度，用于纵向居中
     * @param centerX **非空 = 居中模式**（聊天播报悬浮预览用）：直接按它当屏幕上的目标中心，
     *                并**跳过开关判断** —— 聊天那一路不归「物品预览图」开关管（拍板：无条件生效）。
     *                null 走列表那套（面板左侧空白区取中）
     */
    fun render(
        context: DrawContext,
        stack: ItemStack,
        panelLeft: Int,
        screenHeight: Int,
        centerX: Float? = null
    ) {
        // 物品侧有自己独立的开关（「物品预览图」），与精灵侧互不影响；居中模式不受开关约束
        if (centerX == null && !ClientConfig.hoverPreviewItem) return

        // flush + 清深度（照精灵版）：不先提交攒批并清掉深度，大图会被先画的 GUI 元素整片剔除
        context.draw()
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)

        val s = PREVIEW_SCALE
        // 基准同样是左侧空白区的中点；物品图标自身居中，不需要精灵那边"模型偏右"的左移补偿
        val targetX = centerX ?: panelLeft / 2f
        val centerY = screenHeight / 2f
        val matrices = context.matrices

        matrices.push()
        matrices.translate((targetX - 8f * s).toDouble(), (centerY - 8f * s).toDouble(), 0.0)
        matrices.scale(s, s, 1f)
        drawItemWithBar(context, stack, 0, 0)
        matrices.pop()

        // 大图走 immediate buffer，立刻提交，别留到帧末尾跟别人混在一起
        context.draw()
    }
}
