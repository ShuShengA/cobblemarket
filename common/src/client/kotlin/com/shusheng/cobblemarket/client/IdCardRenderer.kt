package com.shusheng.cobblemarket.client

import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.overview.PlayerOverviewRow
import com.shusheng.cobblemarket.screen.DIALOG_BACKGROUND_TEXTURE
import com.shusheng.cobblemarket.screen.DIALOG_BACKGROUND_TEX_H
import com.shusheng.cobblemarket.screen.drawNineSlice
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.DiffuseLighting
import net.minecraft.entity.LivingEntity
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.joml.Quaternionf

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
    private const val INNER_BOTTOM = 0.864f
    private const val SPLIT = 0.366f
    private const val TITLE_TOP = 0.751f

    /** 原版 HUD 经验条纹理（与 ID 卡界面同一套） */
    private val EXPERIENCE_BAR_BACKGROUND_TEXTURE = Identifier.of("minecraft", "hud/experience_bar_background")
    private val EXPERIENCE_BAR_PROGRESS_TEXTURE = Identifier.of("minecraft", "hud/experience_bar_progress")

    /** 模型占可用区高度的比例（照 `IdCardScreen`：区域取满会让模型大一圈，大小单独收一点） */
    private const val MODEL_SCALE = 0.85f

    /** 模型整体下移的像素数（给帽子 / 高个子模型留顶部余量，照 `IdCardScreen`） */
    private const val MODEL_DROP = 10

    /** 原地旋转一圈的时长（毫秒）—— 分享卡与 ID 卡共用（4s → 6s，2026-10-28 用户觉得偏快） */
    const val SPIN_PERIOD_MS = 6000L

    /** 当前旋转角度（弧度）：按系统时间匀速转一圈 */
    fun spinAngleRad(): Float =
        (System.currentTimeMillis() % SPIN_PERIOD_MS) / SPIN_PERIOD_MS.toFloat() * (Math.PI * 2).toFloat()

    /**
     * 画**原地匀速旋转**的玩家模型（绕 Y 轴）。
     *
     * ⚠ 不能用 `InventoryScreen.drawEntity` —— 它的朝向由「看向点」经 `atan` 算出来，
     *   只有约 ±63° 的摆头范围，转不了 360°。这里照原版 `drawEntity` 的矩阵 / 光照设置
     *   自己调 `EntityRenderDispatcher.render`，把**相机**绕 Y 旋转（视觉上 = 模型原地自转）。
     *   [entity] 的朝向在渲染前后保存 / 恢复（那是真实玩家实体，不能把 GUI 用的值留在它身上）。
     */
    fun drawSpinningEntity(
        context: DrawContext,
        x1: Int, y1: Int, x2: Int, y2: Int,
        size: Int, angleRad: Float, entity: LivingEntity
    ) {
        val client = MinecraftClient.getInstance()
        val centerX = (x1 + x2) / 2f
        val centerY = (y1 + y2) / 2f
        val oldBodyYaw = entity.bodyYaw
        val oldYaw = entity.yaw
        val oldPitch = entity.pitch
        val oldHeadYaw = entity.headYaw
        val oldPrevHeadYaw = entity.prevHeadYaw
        val oldPrevBodyYaw = entity.prevBodyYaw
        val oldPrevYaw = entity.prevYaw
        val oldPrevPitch = entity.prevPitch
        // ⚠ 旋转**靠实体 yaw**（原版 drawEntity 也是这么做的：`entity.setYaw(180 + 40*i)`），
        //   而不是 `dispatcher.setRotation` —— 后者是相机四元数，改了模型也不转。
        //   180° 是「面朝屏幕」的基准，再叠加时间角度 ⇒ 模型绕竖直轴匀速自转。
        // ⚠ **prev* 必须一起设**：渲染状态用 `lerp(tickDelta, prev, cur)` 插值，我们这里 tickDelta=0
        //   ⇒ 实际用的是 prev 值。只设 bodyYaw 不设 prevBodyYaw 会出现「头转、身体定在斜背面」
        //   （2026-10-28 用户实测）。
        val yawDeg = 180f + Math.toDegrees(angleRad.toDouble()).toFloat()
        entity.bodyYaw = yawDeg
        entity.prevBodyYaw = yawDeg
        entity.yaw = yawDeg
        entity.prevYaw = yawDeg
        entity.pitch = 0f
        entity.prevPitch = 0f
        entity.headYaw = yawDeg
        entity.prevHeadYaw = yawDeg

        val matrices = context.matrices
        matrices.push()
        matrices.translate(centerX.toDouble(), centerY.toDouble(), 50.0)
        matrices.scale(size.toFloat(), size.toFloat(), size.toFloat())
        // 实体中心偏移（照原版 `drawEntity` 的 Vector3f(0, height/2, 0)）—— 漏了它模型会往上飘
        matrices.translate(0f, entity.height / 2f, 0f)
        // 模型朝屏幕（原版 quaternionf = rotateZ(π)；pitch 固定 0，不跟鼠标）
        matrices.multiply(Quaternionf().rotateZ(Math.PI.toFloat()))

        DiffuseLighting.method_34742()
        val dispatcher = client.entityRenderDispatcher
        // 相机四元数照抄原版 `drawEntity`（`new Quaternionf().conjugate().rotateY(π)`，单位共轭 = 单位）
        dispatcher.setRotation(Quaternionf().rotateY(Math.PI.toFloat()))
        dispatcher.setRenderShadows(false)
        val vertexConsumers = client.bufferBuilders.entityVertexConsumers
        // 参数：(entity, x, y, z, yaw, tickDelta, matrices, vertexConsumers, light)
        RenderSystem.runAsFancy {
            dispatcher.render(entity, 0.0, 0.0, 0.0, 0f, 0f, matrices, vertexConsumers, 0xF000F0)
        }
        dispatcher.setRenderShadows(true)
        context.draw()
        matrices.pop()

        entity.bodyYaw = oldBodyYaw
        entity.prevBodyYaw = oldPrevBodyYaw
        entity.yaw = oldYaw
        entity.prevYaw = oldPrevYaw
        entity.pitch = oldPitch
        entity.prevPitch = oldPrevPitch
        entity.headYaw = oldHeadYaw
        entity.prevHeadYaw = oldPrevHeadYaw
    }

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
        // 不显示「在线」：这是**分享时的快照**，online 恒为 true，对方下线后仍标着在线是误导
        // （2026-10-28 用户确认；ID 卡界面同样传 false——自己看自己必然在线）
        val lines = overviewLines(row, showOnline = false)
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
                val unitW = if (line.money) currencyUnitWidth(textRenderer, line.isPoints) else 0
                val iconW = if (line.icon != null) OVERVIEW_ICON_W else 0
                val text = TextUtil.truncateStyled(line.text, (colW.toInt() - unitW - iconW).coerceAtLeast(0))
                line.icon?.let { drawLineIcon(context, it, x.toInt(), iconY) }
                val drawn = context.drawTextWithShadow(textRenderer, text, (x + iconW).toInt(), textY, 0xFFFFFF)
                if (line.money) drawCurrencyUnit(context, textRenderer, line.isPoints, drawn, textY, line.color)
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
            // 分享到聊天栏的卡：模型**始终原地匀速旋转**（2026-10-28 用户要求；
            // 之前是固定看向区域中心 = 静止正前方）
            IdCardRenderer.drawSpinningEntity(
                context, innerLeft.toInt(), innerTop.toInt(), split.toInt(), titleTop.toInt(),
                ((titleTop - innerTop) * MODEL_SCALE / 2f).toInt(),
                spinAngleRad(), player
            )
            // 画完把模型批次提交、再清一次深度 —— 别把 3D 的深度留给后续绘制
            context.draw()
            com.mojang.blaze3d.systems.RenderSystem.clear(
                org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.MinecraftClient.IS_SYSTEM_MAC
            )
        }

        // 装备栏 + 经验条（数据由分享 payload 带下来；老数据没有时只画空框 / 0 级）
        drawSharedGearAndXp(context, textRenderer, row, cardLeft, cardTop)
    }

    /**
     * 分享卡上的**装备栏 + 经验条**（与 ID 卡界面同款位置 / 尺寸）。
     *
     * 数据来自分享 payload（`row.headItem` / `experienceLevel` 等）—— 全服总览列表不填这两样，
     * 老数据默认 null / 0 ⇒ 只画空框与 0 级，不会崩。
     */
    private fun drawSharedGearAndXp(
        context: DrawContext, textRenderer: TextRenderer,
        row: PlayerOverviewRow, cardLeft: Float, cardTop: Float
    ) {
        val world = MinecraftClient.getInstance().world ?: return
        val registry = world.registryManager
        val innerLeft = cardLeft + CARD_W * INNER_LEFT
        val innerTop = cardTop + CARD_H * INNER_TOP + MODEL_DROP
        // ⚠ 经验条用**不含 MODEL_DROP** 的内框底线（与 ID 卡界面同款位置）——
        //   加了 MODEL_DROP 会低 10px（2026-10-28 用户报分享卡经验条偏下）
        val innerBottom = cardTop + CARD_H * INNER_BOTTOM
        // 装备栏：模型左侧，22×22、间距 1（与 ID 卡界面一致）
        val slotSize = 22
        val slotX = (innerLeft + 4).toInt()
        val items = listOf(row.headItem, row.chestItem, row.legsItem, row.feetItem)
        items.forEachIndexed { i, nbt ->
            val y = innerTop.toInt() + i * (slotSize + 1)
            drawNineSlice(
                context, DIALOG_BACKGROUND_TEXTURE, slotX, y, slotSize, slotSize,
                0, DIALOG_BACKGROUND_TEX_H
            )
            if (nbt != null) {
                val stack = ItemStack.fromNbtOrEmpty(registry, nbt)
                if (!stack.isEmpty) drawItemWithBar(context, stack, slotX + 3, y + 3)
            }
        }
        // 经验条：底部居中（120×4，等级在条上方 8px、绿色）
        val barW = 120
        val barH = 4
        val centerX = cardLeft + CARD_W / 2f + 1f
        val x = (centerX - barW / 2f).toInt()
        val y = (innerBottom - 12f).toInt()
        context.drawGuiTexture(EXPERIENCE_BAR_BACKGROUND_TEXTURE, x, y, barW, barH)
        val progress = (row.experienceProgress * barW).toInt().coerceIn(0, barW)
        if (progress > 0) {
            context.drawGuiTexture(EXPERIENCE_BAR_PROGRESS_TEXTURE, 182, 5, 0, 0, x, y, progress, barH)
        }
        context.drawCenteredTextWithShadow(
            textRenderer, Text.literal(row.experienceLevel.toString()),
            centerX.toInt(), y - 8, 0x80FF20
        )
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
