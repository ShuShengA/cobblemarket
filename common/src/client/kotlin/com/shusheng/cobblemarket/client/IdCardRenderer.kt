package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.overview.PlayerOverviewRow
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext

/**
 * **ID 卡展示**（聊天悬停）用的整张卡绘制 —— 背景 + 两列信息。
 *
 * ⚠ 与 `IdCardScreen`（自己的 ID 卡界面）**共用同一套版式常量**（卡片 440×247.5、内框比例、分割线），
 *   但那边还会画 3D 玩家模型（只有「自己」才画得出来 —— 展示者的实体在本地不一定有）；
 *   这里**不画模型**，模型区留空（卡片背景本身的视觉已经足够）。
 * ⚠ 常量是两份（`IdCardScreen` 的 companion 与这里各一份）：将来调版式要**一起改**。
 */
object IdCardRenderer {

    /** 卡片本体逻辑尺寸（16:9）—— 与 `IdCardScreen` 一致 */
    const val CARD_W = 440
    const val CARD_H = 247.5f

    // 卡片内部比例（从内置图量出来的，见 docs/player_overview_plan.md 第十二节）
    private const val INNER_LEFT = 0.051f
    private const val INNER_RIGHT = 0.952f
    private const val INNER_TOP = 0.139f
    private const val SPLIT = 0.366f
    private const val TITLE_TOP = 0.751f

    /** 模型占可用区高度的比例（照 `IdCardScreen`：区域取满会让模型大一圈，大小单独收一点） */
    private const val MODEL_SCALE = 0.85f

    /** 模型整体下移的像素数（给帽子 / 高个子模型留顶部余量，照 `IdCardScreen`） */
    private const val MODEL_DROP = 10

    /**
     * 画整张卡（背景 + 两列信息）。
     *
     * @param cardLeft 卡片本体（内容框）左上角 —— 调用方负责摆位置（通常是屏幕居中）
     * @param cardTop 同上
     */
    fun draw(
        context: DrawContext,
        textRenderer: TextRenderer,
        row: PlayerOverviewRow,
        cardLeft: Float,
        cardTop: Float,
        /** 要画的玩家模型（null = 解析不到展示者的实体，模型区留空） */
        player: net.minecraft.entity.LivingEntity? = null
    ) {
        val entry = IdCardBackgroundTexture.get()
        val tex = entry?.id ?: IdCardBackgroundTexture.BUILTIN_TEX
        val texW = entry?.width ?: IdCardBackgroundTexture.BUILTIN_WIDTH
        val texH = entry?.height ?: IdCardBackgroundTexture.BUILTIN_HEIGHT
        val scale = CARD_W.toFloat() / texW
        val drawH = texH * scale
        val cardBottom = cardTop + CARD_H
        // 图底边与卡片底边对齐、顶部装饰往上溢出 ⇒ 纹理顶边在卡片底边之上
        val textureTop = cardBottom - drawH

        // 背景
        context.matrices.push()
        // ⚠ drawTexture 的 width/height 是**采样区**（不是显示尺寸）⇒ 缩放只能靠 matrices.scale
        context.matrices.translate(cardLeft.toDouble(), textureTop.toDouble(), 0.0)
        context.matrices.scale(scale, scale, 1f)
        context.drawTexture(tex, 0, 0, 0f, 0f, texW, texH, texW, texH)
        context.matrices.pop()

        // 两列信息（与 ID 卡界面同一份行列表）
        val innerRight = cardLeft + CARD_W * INNER_RIGHT
        val innerTop = cardTop + CARD_H * INNER_TOP
        val split = cardLeft + CARD_W * SPLIT
        val lines = overviewLines(row, showOnline = true)
        val columns = splitByGroups(lines)

        val startX = split + 10f
        val startY = innerTop + 18f
        val colGap = 8f
        val colW = (innerRight - split - colGap) / 2f

        columns.forEachIndexed { ci, col ->
            val x = startX + ci * (colW + colGap)
            var y = startY
            col.forEach { line ->
                val rowY = y.toInt()
                val textY = lineTextY(rowY, line)
                val iconY = lineIconY(rowY, line)
                // ⚠ 截断要**扣掉货币单位宽度**（单位是截断之后另外补画的）与行首图标宽度
                val unitW = if (line.money) currencyUnitWidth(textRenderer) else 0
                val iconW = if (line.icon != null) OVERVIEW_ICON_W else 0
                val text = TextUtil.truncateStyled(line.text, (colW.toInt() - unitW - iconW).coerceAtLeast(0))
                line.icon?.let { drawLineIcon(context, it, x.toInt(), iconY) }
                val drawn = context.drawTextWithShadow(textRenderer, text, (x + iconW).toInt(), textY, 0xFFFFFF)
                if (line.money) drawCurrencyUnit(context, textRenderer, drawn, textY, line.color)
                y += line.height
            }
        }

        // 左侧：展示者的 3D 模型（能解析到实体才画）。
        // ⚠ 模型**最后画**（先文字后模型）—— 反过来模型之后绘制的 GUI 会把深度写进缓冲、
        //   把模型切掉一块，照 `IdCardScreen.drawPlayerModel`
        if (player != null) {
            val innerLeft = cardLeft + CARD_W * INNER_LEFT
            val innerTop = cardTop + CARD_H * INNER_TOP + MODEL_DROP
            val titleTop = cardTop + CARD_H * TITLE_TOP + MODEL_DROP
            // ⚠ 画 3D 前先 flush 已排队的 GUI、再清深度缓冲
            context.draw()
            com.mojang.blaze3d.systems.RenderSystem.clear(
                org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.MinecraftClient.IS_SYSTEM_MAC
            )
            net.minecraft.client.gui.screen.ingame.InventoryScreen.drawEntity(
                context, innerLeft.toInt(), innerTop.toInt(), split.toInt(), titleTop.toInt(),
                ((titleTop - innerTop) * MODEL_SCALE / 2f).toInt(),
                0f,
                // ⚠ 后两个是**看向的目标点**（不是鼠标偏移）。`drawEntity` 以**区域中心**为基准算偏角
                //   ⇒ 指向区域中心 = 正前方平视（2026-10-17 用户两次报「看左上方」：传 (0,0) 看屏幕左上、
                //   传区域左上角看区域左上，都不对）
                (innerLeft + split) / 2f, (innerTop + titleTop) / 2f, player
            )
            // 画完把模型批次提交、再清一次深度 —— 别把 3D 的深度留给后续绘制
            context.draw()
            com.mojang.blaze3d.systems.RenderSystem.clear(
                org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.MinecraftClient.IS_SYSTEM_MAC
            )
        }
    }

    /**
     * 把行按**分组块**切成两列：一块（标题 + 它的内容）不会被拆到两列去。
     * 先按分组标题切块，再从前往后填：第一列够一半了，后面的整块给第二列。
     */
    private fun splitByGroups(lines: List<OverviewLine>): List<List<OverviewLine>> {
        val blocks = mutableListOf<MutableList<OverviewLine>>()
        lines.forEach { line ->
            when {
                // 分组标题开新的一块；首行（名字那行）也自起一块
                line.isGroup || blocks.isEmpty() -> blocks += mutableListOf(line)
                else -> blocks.last().add(line)
            }
        }
        val target = lines.size / 2
        val col1 = mutableListOf<OverviewLine>()
        val col2 = mutableListOf<OverviewLine>()
        blocks.forEach { b -> if (col1.size < target) col1.addAll(b) else col2.addAll(b) }
        return listOf(col1, col2)
    }
}
