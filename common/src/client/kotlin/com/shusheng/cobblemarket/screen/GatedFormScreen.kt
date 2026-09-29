package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.OrderedText
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * 「需要前置条件的形态」说明界面 —— RP 商城**上架精灵**的编辑窗里那枚 info 按钮点开的。
 *
 * **为什么单开一屏**：形态候选里有 72 个 `battleOnly` 形态（Mega / 原始回归 / 达摩模式…），
 * 正常路径下变身需要前置条件（道具 / 招式 / 等级），而商城是**直接造出一只该形态的精灵**
 * ⇒ 从诞生就是非法精灵（形态 ↔ 道具的不变量被打破、还白省一个携带物位）。
 * 服主不点开就不知道，所以既要有那枚按钮、也要有这一屏把后果说全。
 * 判定与清单在 [com.shusheng.cobblemarket.util.SpeciesText.GATED_FORM_ASPECTS]。
 *
 * ⚠⚠ **返回时必须回到原来那个 [PointsMarketScreen] 实例**（[backScreen]），**不是新建一个**：
 *   服主是**在编辑窗配到一半**时点进来的，新建实例会把编辑窗连同草稿一起丢掉。
 *   `PointsMarketScreen.init()` 本来就按状态重建编辑窗（resize 走的就是这条路），
 *   所以把原实例塞回去即可，什么都不用另存。
 *
 * ⚠ 记得进 `CobbleMarketClient.isMarketScreen` 白名单 —— 漏了 E 键与自定义光标都失效。
 */
class GatedFormScreen(
    /**
     * 点进来的那个商城界面；返回时原样 `setScreen` 回去。null = 没有上层（照常回游戏）。
     *
     * ⚠ 名字**故意不叫 `parent`**：原版 `Screen` 那一族里 `parent` 是个常见字段名，
     *   Kotlin 属性会生成 `getParent()`，撞上就是"accidental override"编译错误。
     */
    val backScreen: PointsMarketScreen? = null,
) : Screen(Text.translatable("cobblemarket.gated_form.title")) {

    private lateinit var geo: DialogGeometry
    private var scroll = 0

    /**
     * 断好行的正文 —— **在 [init] 里算一次就存下**。
     * 每帧现算的话，一次渲染要重跑十几遍断行（每遍几十次 `getWidth`），白烧。
     */
    private var blocks: List<Block> = emptyList()

    override fun init() {
        super.init()
        // ⚠ **先要宽度、再断行、最后才算得出高度**，所以 `of()` 调两次：
        //   宽高在 [DialogGeometry.of] 里是各算各的（宽只看 contentW、高只看 contentNeeded），
        //   两次传同一个 contentW ⇒ 宽度必然一致，第二次纯粹是为了把真实高度带进去。
        //   屏幕窄时弹窗会被钳小，断行必须用**钳后**的宽度，否则文字会顶出弹窗
        //   —— 这正是「测量与绘制同源」那条老坑。
        val dialogW = DialogGeometry.of(width, height, contentW = TEXT_W, contentNeeded = 0).w
        blocks = buildBlocks(dialogW - DialogGeometry.PAD_W)
        val g = DialogGeometry.of(width, height, contentW = TEXT_W, contentNeeded = contentHeight())
        geo = g
        scroll = 0

        addDrawableChild(
            NineSliceButton(
                g.x + g.w / 2 - BACK_W / 2, g.buttonY, BACK_W, DialogGeometry.BUTTON_H,
                Text.literal(""),
                { goBack() },
                iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
                iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
                tooltip = Text.translatable("cobblemarket.gui.back")
            )
        )
    }

    /**
     * 内容区需要的总高（**含上下内边距**）。
     *
     * ⚠⚠ **内边距必须算进来**：内容区是**按这个值裁剪**的（[DialogGeometry.beginContent] ⇒
     *   `contentTop` 起 `contentNeeded` 高），只按「行数 × 行高」算的话，最后一行会落到裁剪线
     *   底下被切掉一截。⚠ 而且**平时看不出来** —— 字高 9 < 行高 [LINE_H] 11，那 2px 差值正好
     *   把行底顶出边界，表现是**末行汉字的底部被削掉一点**（2026-09-30 用户实测报的「成」字）。
     */
    private fun contentHeight(): Int = PAD_TOP + blocks.size * LINE_H + PAD_BOTTOM

    // ── 正文 ──

    /** 正文的一行：`line = null` 表示空行（段间距）；[bullet] 的项首行画前缀、续行悬挂缩进 */
    private data class Block(val line: OrderedText?, val bullet: Boolean)

    /**
     * 把词条拼成一块块**断好行**的正文。
     *
     * ⚠ 断行一律走 [TextUtil.wrapText]（空格分词 + 中文硬断）—— 与喵喵银行的规则面板同一份实现。
     * ⚠ 列表项的可用宽要**扣掉前缀**，否则首行会顶出右边（前缀是画上去的，不占断行宽度）。
     */
    private fun buildBlocks(textW: Int): List<Block> {
        val out = mutableListOf<Block>()
        val bulletW = textRenderer.getWidth(BULLET)
        fun para(key: String, bullet: Boolean = false) {
            val room = if (bullet) textW - bulletW else textW
            TextUtil.wrapText(Text.translatable(key).string, room).forEach { out += Block(it, bullet) }
        }

        para("cobblemarket.gated_form.intro")
        out += Block(null, false)
        (1..4).forEach { para("cobblemarket.gated_form.item.$it", bullet = true) }
        out += Block(null, false)
        para("cobblemarket.gated_form.outro")
        return out
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        drawScreenDimMask(context, width, height)
        drawDialogFrame(context, geo, title, textRenderer)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val g = geo
        // 滚出内容区的行会被裁掉（几何助手管裁剪，见 DialogGeometry.beginContent）
        g.beginContent(context)
        var y = g.contentTop + PAD_TOP - scroll
        blocks.forEach { block ->
            val line = block.line
            if (line != null) {
                val textX = g.x + DialogGeometry.PAD_W / 2 + if (block.bullet) textRenderer.getWidth(BULLET) else 0
                if (block.bullet) {
                    context.drawTextWithShadow(textRenderer, BULLET, g.x + DialogGeometry.PAD_W / 2, y, 0xFFFFFF)
                }
                context.drawTextWithShadow(textRenderer, line, textX, y, 0xFFFFFF)
            }
            y += LINE_H
        }
        g.endContent(context)
    }

    override fun mouseScrolled(
        mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double
    ): Boolean {
        val max = geo.maxScroll()
        // 装得下就不吃这个事件（交给 super，免得平白吞掉）
        if (max <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        scroll = (scroll - verticalAmount.toInt() * LINE_H).coerceIn(0, max)
        return true
    }

    // ── 关闭 ──

    /** 返回**原来那个商城实例**（编辑窗与草稿都还在那份实例里，见类注释） */
    private fun goBack() {
        val back = backScreen
        if (back != null) client?.setScreen(back) else client?.setScreen(null)
    }

    /** ESC / 右上角关闭都走这里 —— 别用原版默认（那个直接回游戏，等于丢草稿） */
    override fun close() = goBack()

    override fun shouldPause() = false

    private companion object {
        /**
         * 正文的**文字区宽**（弹窗宽 = 它 + [DialogGeometry.PAD_W]）。
         *
         * 取 340：中文一行约 37 字、英文约 56 字符 —— 再宽就不好读了（弹窗上限 460 时一行能到 48 汉字）。
         * ⚠ 这只是**期望值**，窄屏下弹窗会被钳小，实际断行宽度以钳后的为准（见 [init]）。
         */
        const val TEXT_W = 340

        /** 正文距内容区顶的间距 */
        const val PAD_TOP = 4

        /**
         * 正文距内容区底的间距。
         * ⚠ 取 2 是为了**与 [PAD_TOP] 视觉对称**：末行的字底本来就比行底高 2px
         * （字 9 / 行 [LINE_H] 11），所以「4 + 行数×11 + 2」让首行字顶与末行字底各距边界 4px。
         */
        const val PAD_BOTTOM = 2

        /** 行高：文字本身 9px，留 2px 行距 */
        const val LINE_H = 11

        /** 列表项前缀（悬挂缩进按它的宽度算） */
        const val BULLET = "· "

        /** 底部「返回」按钮宽（高走 [DialogGeometry.BUTTON_H]，与几何助手的钉底口径一致） */
        const val BACK_W = 60
    }
}
