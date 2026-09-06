package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.network.itemsEqualForTrading
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.util.UUID

/**
 * 物品求购交付的形态选择：背包中同一物品存在多种组件形态（如内容不同的潜影箱）时，
 * 交付前先选择要交付哪一种。选中后经 BuyOrderScreen.pendingDeliverVariant 回传，
 * 列表刷新时恢复交付弹窗并带入所选形态。
 *
 * tooltip 用 ADVANCED 手动渲染（含容器内容），玩家靠它区分箱子里装了什么。
 */
class ItemVariantSelectScreen(
    private val orderId: UUID,
    private val itemId: String,
) : Screen(Text.translatable("cobblemarket.buy_order.variant_title")) {

    private data class Variant(val sample: ItemStack, val count: Int)

    private val variants = mutableListOf<Variant>()
    private var hovered = -1
    /** 行选中态（照 SellSelectScreen 交付模式：行点击高亮选中，底部确认按钮完成选择） */
    private var selectedIndex = -1
    private var confirmButton: NineSliceButton? = null
    /** 列表滚动：形态组多时列表限高（按钮上方），滚轮翻动，行与固定按钮永不重叠 */
    private var scrollOffset = 0

    init {
        // 背包形态分组：按组件一致（itemsEqualForTrading）聚合，每组保留示例栈 + 总数量
        val player = MinecraftClient.getInstance().player
        if (player != null) {
            for (stack in player.inventory.main) {
                if (stack.isEmpty || Registries.ITEM.getId(stack.item).toString() != itemId) continue
                val idx = variants.indexOfFirst { itemsEqualForTrading(it.sample, stack) }
                if (idx >= 0) {
                    variants[idx] = Variant(variants[idx].sample, variants[idx].count + stack.count)
                } else {
                    // copy：背包栈是活引用（交付扣减会动它），示例栈必须独立
                    variants.add(Variant(stack.copy(), stack.count))
                }
            }
        }
    }

    override fun init() {
        super.init()
        // 返回按钮右上角（照物品市场 backButton：leftX + panelWidth - 50, 13）
        val lx = width / 2 - 148
        addDrawableChild(NineSliceButton(
            lx + 296 - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(BuyOrderScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        ))
        // 确认交付按钮（照 SellSelectScreen 交付模式）：选中一行后激活；
        // 按钮固定贴底部（height-60 滚动指示文字上方 4px），分割线在按钮上方——形态组少时不留大空余
        val btnY = height - 84
        confirmButton = NineSliceButton(
            width / 2 - 42, btnY, 84, 20,
            Text.translatable("cobblemarket.buy_order.variant_confirm"),
            { confirmSelection() }
        )
        confirmButton?.active = false
        addDrawableChild(confirmButton)
    }

    private fun confirmSelection() {
        if (selectedIndex !in variants.indices) return
        val v = variants[selectedIndex]
        BuyOrderScreen.pendingDeliverVariant = v.sample.copy()
        client?.setScreen(BuyOrderScreen())
    }

    /** 面板背景：照市场界面模板（top/middle/bottom 切片铺满） */
    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val panelLeft = width / 2 - 160
        val panelTop = 2
        val panelBottom = height - 32
        val sliceH = 16

        val top = Identifier.of("cobblemarket", "textures/gui/market_panel_top.png")
        val mid = Identifier.of("cobblemarket", "textures/gui/market_panel_middle.png")
        val bot = Identifier.of("cobblemarket", "textures/gui/market_panel_bottom.png")

        drawPanelSlice(context, top, panelLeft, panelTop)
        var y = panelTop + sliceH
        while (y < panelBottom - sliceH) {
            drawPanelSlice(context, mid, panelLeft, y, minOf(sliceH, panelBottom - sliceH - y))
            y += sliceH
        }
        drawPanelSlice(context, bot, panelLeft, panelBottom - sliceH)
    }

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int, sliceH: Int = 16) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val centerX = width / 2
        val lx = centerX - 148
        // 标题顶部（照物品市场标题 y=14）
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.buy_order.variant_title").formatted(Formatting.GOLD),
            centerX, 14, 0xFFFFFF
        )

        val startY = 36
        // 列表顶部分割线（照物品市场：网格上方 4px 处，颜色一致）
        context.fill(lx, startY - 2, lx + 296, startY - 1, 0xFF555555.toInt())
        // 底部分割线：固定在确认按钮上方 4px（按钮贴底 height-84），列表行多时限高滚动
        context.fill(lx, height - 89, lx + 296, height - 88, 0xFF555555.toInt())
        val rowH = 26

        // 列表区：顶部 startY 到底部确认按钮上方 4px；形态组多时滚轮翻动，行与固定按钮永不重叠
        val visible = visibleRows()
        val maxScroll = maxOf(0, variants.size - visible)
        if (scrollOffset > maxScroll) scrollOffset = maxScroll

        hovered = -1
        if (mouseX in lx..(lx + 296) && mouseY >= startY) {
            val row = (mouseY - startY) / rowH + scrollOffset
            if (row in variants.indices) hovered = row
        }

        variants.forEachIndexed { i, v ->
            if (i < scrollOffset || i >= scrollOffset + visible) return@forEachIndexed
            val y = startY + (i - scrollOffset) * rowH
            // 行态：选中 2（高亮）、悬停 1、普通 0（照 SellSelectScreen 行态）
            val rowState = when {
                i == selectedIndex -> 2
                i == hovered -> 1
                else -> 0
            }
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, lx, y, 296, rowH - 2, rowState, ROW_BACKGROUND_TEX_H)
            context.drawItem(v.sample, lx + 4, y + 4)
            // 行名照物品栏悬浮第一行按稀有度着色（组件明细看悬停词条，行内不重复显示）
            val nameText = com.shusheng.cobblemarket.util.TextUtil.rarityColoredName(v.sample)
            val countText = "×${v.count}"
            val nameMaxW = 292 - 26 - textRenderer.getWidth(countText) - 6
            context.drawTextWithShadow(textRenderer,
                com.shusheng.cobblemarket.util.TextUtil.truncateText(nameText, nameMaxW),
                lx + 26, y + 8, 0xFFFFFF)
            context.drawTextWithShadow(textRenderer, countText, lx + 292 - textRenderer.getWidth(countText), y + 8, 0xAAAAAA)
        }
        if (variants.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.buy_order.variant_empty").formatted(Formatting.GRAY),
                centerX, startY + 20, 0xFFFFFF
            )
        }
        // 滚动位置指示（照全部交易历史界面：底部居中灰色数字 x-y / N，仅需滚动时显示；
        // y=height-49 在确认按钮（底 height-60）下方、面板底边框上方，与历史界面同位置）
        if (variants.size > visible) {
            context.drawCenteredTextWithShadow(textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + visible, variants.size)} / ${variants.size}",
                width / 2, height - 49, 0x888888)
        }
        if (hovered in variants.indices) {
            renderVariantTooltip(context, variants[hovered], mouseX, mouseY)
        }
    }

    /** 可见行数：列表区底收到 height-93（固定底部分割线 height-89 上方 4px），行多时滚动 */
    private fun visibleRows(): Int {
        val listBottom = height - 93
        return maxOf(0, (listBottom - 36) / 26)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true
        val maxScroll = maxOf(0, variants.size - visibleRows())
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        if (button == 0 && hovered in variants.indices) {
            // 行点击 = 选中高亮（确认按钮激活后才回传）
            selectedIndex = hovered
            confirmButton?.active = true
            return true
        }
        return false
    }

    /** 悬停 tooltip：照原版背包悬停按键语义（Shift 完整词条 / Ctrl+F3+H 调试信息） */
    private fun renderVariantTooltip(context: DrawContext, v: Variant, mouseX: Int, mouseY: Int) {
        val lines = com.shusheng.cobblemarket.client.ItemComponentsDisplay.itemTooltip(v.sample, client?.player,
            com.shusheng.cobblemarket.client.ItemComponentsDisplay.tooltipTypeForHover())
            .map { it to 0xFFFFFF }
        var maxWidth = 0
        lines.forEach { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it.first)) }

        val padding = 4
        val tx = minOf(mouseX + 12, width - maxWidth - 12)
        val tooltipHeight = lines.size * 10 + padding
        val tyAbove = mouseY - tooltipHeight - 4
        val ty = if (tyAbove <= 0) minOf(mouseY + 12, height - tooltipHeight) else tyAbove

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, lines.size * 10 + 2 * padding, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, (line, color) ->
            context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
        }
        context.matrices.pop()
    }

    override fun shouldPause() = false
}
