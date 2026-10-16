package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.IdCardBackgroundTexture
import com.shusheng.cobblemarket.client.IdCardDataCache
import com.shusheng.cobblemarket.client.OverviewLine
import com.shusheng.cobblemarket.client.amountUnitWidth
import com.shusheng.cobblemarket.client.drawAmountWithUnit
import com.shusheng.cobblemarket.client.currencyUnitWidth
import com.shusheng.cobblemarket.client.drawCurrencyUnit
import com.shusheng.cobblemarket.client.overviewLines
import com.shusheng.cobblemarket.util.TextUtil
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.ingame.InventoryScreen
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.opengl.GL11

/**
 * **市场 ID 卡** —— 玩家看自己在这套系统里的全部状态（人人可用，自己看自己，不走权限节点）。
 *
 * 版式（比例全部从内置背景图量出来，见方案第十二节）：
 * - **卡片本体 = 图的下方「宽 × 9/16」那块**，图按卡片宽等比缩放、**底边与左右对齐卡片**，
 *   顶部多出来的部分（服主放的吉祥物 / 标题）**照画不裁**、自然露在卡片上方
 * - 卡片内部：竖分割线在 **36.6%** 处 ⇒ 左 **37%** 是玩家模型区、右 **63%** 是信息区
 * - ⭐ 模型画在模型区的**偏上**（内框顶 → 75.1% 那块），底部那一条让给品牌字
 *
 * ⚠ 与全服总览的分工：总览是"服主看所有人"（要 `overview` 节点），本界面是"自己看自己"；
 *   **内容与总览的悬停窗逐项一致** —— 靠共用 `overviewLines` / 同一个 `buildRows` 保证。
 */
class IdCardScreen : Screen(Text.translatable("cobblemarket.idcard.title")) {

    private companion object {
        /**
         * 卡片本体逻辑尺寸（16:9）。
         * ⚠ **440 是屏幕高度允许的上限** —— 再宽卡片高就到 270，加上顶部装饰会超出一屏
         *   （1080p 默认档位的逻辑屏高只有 360）。
         * ⚠ 加宽会让**背景图里的一切等比放大**（标题 / 装饰精灵 / 内框 / 分割线），
         *   形状不变（等比缩放，不是拉伸）—— 2026-10-16 用户拍板走这条，
         *   为的是给英文标签和更长的金额留出宽度。
         */
        const val CARD_W = 440

        /** 440 × 9/16 = 247.5 —— **必须与图的 9/16 严格一致**，否则图里的边框会与卡片本体错位 */
        const val CARD_H = 247.5f

        /** 内置背景图（服主没配 / 图还没到货时用它）—— 尺寸写死在内置图上，与配置图的动态尺寸区分 */
        val BUILTIN_TEX = Identifier.of("cobblemarket", "textures/gui/id_card_background.png")
        const val BUILTIN_W = 1280
        const val BUILTIN_H = 940

        // ── 卡片内部比例（从内置图量出来的，见 docs/player_overview_plan.md 第十二节）──
        const val INNER_LEFT = 0.051f
        const val INNER_RIGHT = 0.952f
        const val INNER_TOP = 0.139f
        const val INNER_BOTTOM = 0.864f
        /** 竖分割线（模型区 / 信息区的分界） */
        const val SPLIT = 0.366f
        /** 品牌字顶部 —— 模型只画到这条线以上（「模型偏上」的量化落点） */
        const val TITLE_TOP = 0.751f

        /**
         * 模型占可用区高度的比例。
         * ⚠ `drawEntity` 的缩放是**按区域高度算的** —— 区域取满会让模型比原先大一圈
         *   （2026-10-16 用户实测问「为什么变大了」），所以位置用满、大小单独收一点。
         */
        const val MODEL_SCALE = 0.85f
    }

    /** 金额行的命中框：界面显示的是**缩写**（`9.8k`），鼠标停上来时用完整千分位展全 */
    private class AmountHit(val x: Int, val y: Int, val w: Int, val full: String)
    private val amountHits = mutableListOf<AmountHit>()

    override fun init() {
        super.init()
        // 返回按钮 —— **卡片内容区右下角**（2026-10-16 用户实测反馈：原先放屏幕右上角、离得太远）
        val geo = geometry()
        addDrawableChild(NineSliceButton(
            // ⚠ 右缘要**离内框右边那条线 6px** —— 贴到 innerRight 会正好压在白线上（2026-10-16 用户实测）
            (geo.innerRight - 50 - 6).toInt(), (geo.innerBottom - 16 - 2).toInt(), 50, 16,
            Text.literal(""),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        ))

        // 每次打开都重新拉：1) 自己的数据（顺便刷新）2) 背景图（服主换图后无需重连）
        IdCardDataCache.request()
        IdCardBackgroundTexture.requestRefresh()
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        drawCardBackground(context)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        val row = IdCardDataCache.row ?: run {
            context.drawCenteredTextWithShadow(
                textRenderer, Text.translatable("cobblemarket.overview.loading"),
                width / 2, height / 2, 0xFFFFFF
            )
            return
        }

        val geo = geometry()
        // ⚠ 顺序要紧：**先画 2D 文字、最后画 3D 模型**。反过来的话，模型之后绘制的 GUI
        //   会把深度写进缓冲，把模型切掉一块（2026-10-16 用户实测报「模型顶部被切割」）
        drawInfoColumns(context, row, geo)
        drawPlayerModel(context, geo, mouseX, mouseY)

        // 金额是**缩写**显示（`9.8k`）⇒ 停在那一行上时展全完整千分位
        // （照入口界面「全服累计成交额」的做法：缩写 + 悬停出完整值）
        amountHits.firstOrNull { mouseX in it.x until (it.x + it.w) && mouseY in it.y until (it.y + 10) }
            ?.let { renderAmountTooltip(context, mouseX, mouseY, it.full) }
    }

    /** 完整金额的小面板（照 `MarketEntryScreen.renderAmountTooltip`：鼠标右上、贴屏边兜底） */
    private fun renderAmountTooltip(context: DrawContext, mx: Int, my: Int, amount: String) {
        val pad = 4
        val w = amountUnitWidth(textRenderer, amount) + 2 * pad
        val h = 14
        val tx = minOf(mx + 12, width - w - 4)
        val ty = maxOf(my - h - 4, 0)
        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx, ty, w, h, 1, ROW_BACKGROUND_TEX_H)
        drawAmountWithUnit(context, textRenderer, amount, tx + pad, ty + 3, 0xFFD700.toInt())
        context.matrices.pop()
    }

    // ── 背景：整张图按卡片宽等比缩放、底边对齐卡片底边（顶部溢出照画）──

    private fun drawCardBackground(context: DrawContext) {
        val entry = IdCardBackgroundTexture.get()
        val tex = entry?.id ?: BUILTIN_TEX
        val texW = entry?.width ?: BUILTIN_W
        val texH = entry?.height ?: BUILTIN_H

        val geo = geometry()
        val scale = CARD_W.toFloat() / texW

        context.matrices.push()
        // ⚠ drawTexture 的 width/height 是**采样区**（不是显示尺寸）⇒ 缩放只能靠 matrices.scale
        context.matrices.translate(geo.textureLeft.toDouble(), geo.textureTop.toDouble(), 0.0)
        context.matrices.scale(scale, scale, 1f)
        context.drawTexture(tex, 0, 0, 0f, 0f, texW, texH, texW, texH)
        context.matrices.pop()
    }

    /** 卡片几何：图按卡片宽缩放后的整体位置 + 卡片内框与分割线 */
    private class Geometry(
        val cardLeft: Float, val cardTop: Float, val cardBottom: Float,
        val textureLeft: Float, val textureTop: Float,
        val innerLeft: Float, val innerRight: Float, val innerTop: Float, val innerBottom: Float,
        val split: Float, val titleTop: Float,
    )

    private fun geometry(): Geometry {
        val entry = IdCardBackgroundTexture.get()
        val texW = entry?.width ?: BUILTIN_W
        val texH = entry?.height ?: BUILTIN_H
        val scale = CARD_W.toFloat() / texW
        val drawH = texH * scale

        // ⭐ **卡片本体（内容框）居中**，不是整张图居中 ——
        //   图底边与卡片底边对齐，顶部装饰往上溢出 ⇒ **整张图是偏上的**
        //   （2026-10-16 用户实测指出：我原先让整张图居中，卡片就整个沉到屏幕下半部了）
        val cardTop = (height - CARD_H) / 2f
        val cardBottom = cardTop + CARD_H
        val textureTop = cardBottom - drawH
        val cardLeft = (width - CARD_W) / 2f

        return Geometry(
            cardLeft = cardLeft, cardTop = cardTop, cardBottom = cardBottom,
            textureLeft = cardLeft, textureTop = textureTop,
            innerLeft = cardLeft + CARD_W * INNER_LEFT,
            innerRight = cardLeft + CARD_W * INNER_RIGHT,
            innerTop = cardTop + CARD_H * INNER_TOP,
            innerBottom = cardTop + CARD_H * INNER_BOTTOM,
            split = cardLeft + CARD_W * SPLIT,
            titleTop = cardTop + CARD_H * TITLE_TOP,
        )
    }

    // ── 左侧：玩家自己的 3D 模型（偏上，底部让给品牌字）──

    private fun drawPlayerModel(context: DrawContext, geo: Geometry, mouseX: Int, mouseY: Int) {
        val player = MinecraftClient.getInstance().player ?: return

        // ⚠ 画 3D 前必须 **先 flush 已排队的 GUI、再清深度缓冲** —— 否则模型会被残留的深度信息
        //   切掉一块（精灵图标为此时栽过好几轮，见 memory project_pokemon_icon_depth）
        context.draw()
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)

        // ⚠ 位置是 2026-10-16 用户**两轮**实测定下来的：区域取满「内框顶 → 品牌字顶」整块，
        //   既不留余量也不下移 —— 第一次留 85% 余量显得偏上，第二次整体下移 18px 又压住了品牌字
        val x1 = geo.innerLeft.toInt()
        val y1 = geo.innerTop.toInt()
        val x2 = geo.split.toInt()
        val y2 = geo.titleTop.toInt()
        val modelBottom = y2

        // ⚠ 1.21.1 里这个方法叫 `drawEntity`（旧名 renderEntityInInventoryFollowsMouse 已不存在，
        //   老代码/教程里还能见到）—— 反编译 MC 的映射 jar 确认过签名
        InventoryScreen.drawEntity(
            context, x1, y1, x2, modelBottom,
            ((modelBottom - y1) * MODEL_SCALE / 2f).toInt(),   // size（缩放）—— 别用满区域高度
            0f,                               // yOffset
            mouseX.toFloat(), mouseY.toFloat(),
            player
        )

        // 画完把模型批次提交、**再清一次深度** —— 别把 3D 的深度留给后续绘制
        // （下一帧的卡片背景 / 文字都会受它影响，表现就是"莫名其妙被切一块"）
        context.draw()
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
    }

    // ── 右侧：两列信息（与总览悬停窗同一份文本，只是排成两列）──

    private fun drawInfoColumns(context: DrawContext, row: com.shusheng.cobblemarket.overview.PlayerOverviewRow, geo: Geometry) {
        val lines = overviewLines(row)
        amountHits.clear()
        // ⚠ 按**分组块**分列，一块不拆开 —— 2026-10-16 用户实测指出：原先按"前半 / 后半"硬切，
        //   会出现「钱」这个标题落在左列末尾、它的内容（主货币余额）却跑到右列开头的割裂
        val columns = splitByGroups(lines)

        // ⚠ 2026-10-16 用户实测：文字要**下移并右移**（原先顶着内框上沿、紧贴分割线）
        val startX = geo.split + 10f
        val startY = geo.innerTop + 18f
        val colGap = 8f
        val colW = (geo.innerRight - geo.split - colGap) / 2f
        val lineH = 10

        columns.forEachIndexed { ci, col ->
            val x = startX + ci * (colW + colGap)
            col.forEachIndexed { li, line ->
                val y = startY + li * lineH
                // ⚠ 截断到列宽，别让长文案压到内框右边那条线。
                //   用 `truncateStyled`：它会**保住词条参数里那段的颜色**，而 `truncateText`
                //   只留顶层样式，会把金额的颜色抹掉。
                //   ⚠ 还得**扣掉货币单位的宽度** —— 单位是截断之后另外补画的，
                //     不扣的话文字截到线内、单位照样顶出去（2026-10-16 用户实测报的就是这个）
                val unitW = if (line.money) currencyUnitWidth(textRenderer) else 0
                val text = TextUtil.truncateStyled(line.text, (colW.toInt() - unitW).coerceAtLeast(0))
                val drawn = context.drawTextWithShadow(textRenderer, text, x.toInt(), y.toInt(), 0xFFFFFF)
                // 金额行：数字的颜色已由词条参数自带（金 = 能花的钱 / 蓝 = 额度、待还），这里只补行尾的单位
                if (line.money) {
                    drawCurrencyUnit(context, textRenderer, drawn, y.toInt(), line.color)
                    // 记下命中框 —— 界面上是缩写，鼠标停上来要能展全完整千分位
                    line.fullAmount?.let {
                        val endX = drawn + currencyUnitWidth(textRenderer)
                        amountHits += AmountHit(x.toInt(), y.toInt(), endX - x.toInt(), it)
                    }
                }
            }
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

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean = false

    override fun shouldPause() = false
}
