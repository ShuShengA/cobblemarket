package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.network.CardHolderEntry
import com.shusheng.cobblemarket.network.CardHolderListPayload
import com.shusheng.cobblemarket.network.RequestCardHolderListPayload
import com.shusheng.cobblemarket.network.RequestCardRevokePayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/**
 * 卡片管理（仅 OP 可达，喵喵银行入口）：紫卡 ∪ 黑卡持有者混排列表。
 * 照 LoanHistoryScreen 面板三件套 + 滚动 + 行尾按钮模式；每行按持有卡种显示图标 +「收回」按钮，
 * 直接收回（服务端复核 OP 后执行并回发新列表刷新，与 /market card revoke 同效果）。
 */
class CardManageScreen : Screen(Text.translatable("cobblemarket.card.manage_title")) {

    private val panelWidth = 296
    private var entries = listOf<CardHolderEntry>()
    private var loaded = false
    private var scrollOffset = 0
    private val rowButtons = mutableListOf<Pair<NineSliceButton, String>>()

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        // 返回按钮（右上，照 HistoryScreen）
        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 18, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(MeowthBankScreen()) }
        ))

        rebuildRowButtons()

        if (!loaded) {
            sendToServer(RequestCardHolderListPayload())
            loaded = true
        }
    }

    fun onCardHolderList(payload: CardHolderListPayload) {
        entries = payload.entries
        scrollOffset = 0
        rebuildRowButtons()
    }

    /**
     * 行尾收回按钮（紫/黑各一，仅持有对应卡种时显示；kind 绑定到按钮用于点击回收）。
     * 卡组（图标+按钮）右对齐行尾：黑组贴内容右缘，紫组在其左（双持常态），按钮永远在行末尾。
     */
    private fun rebuildRowButtons() {
        rowButtons.forEach { (btn, _) -> remove(btn) }
        rowButtons.clear()
        val leftX = width / 2 - panelWidth / 2
        val rowHeight = 24
        val startY = 48
        val maxVisible = maxOf(3, (height - 48 - startY) / rowHeight)
        val rightEdge = leftX + panelWidth - 8
        val btnW = 44
        val iconW = 16
        val gap = 4
        entries.drop(scrollOffset).take(maxVisible).forEachIndexed { i, e ->
            val y = startY + i * rowHeight
            var cursor = rightEdge
            if (e.holdsBlack) {
                val btn = NineSliceButton(
                    cursor - btnW, y + 4, btnW, 16,
                    Text.translatable("cobblemarket.card.manage_revoke"),
                    { sendToServer(RequestCardRevokePayload(e.uuid, "black")) }
                )
                rowButtons.add(btn to "black")
                addDrawableChild(btn)
                cursor -= btnW + gap + iconW + gap + gap
            }
            if (e.holdsPurple) {
                val btn = NineSliceButton(
                    cursor - btnW, y + 4, btnW, 16,
                    Text.translatable("cobblemarket.card.manage_revoke"),
                    { sendToServer(RequestCardRevokePayload(e.uuid, "purple")) }
                )
                rowButtons.add(btn to "purple")
                addDrawableChild(btn)
            }
        }
    }

    /** 卡组（图标+按钮）右对齐行尾的图标偏移（相对面板左缘，与 rebuildRowButtons 按钮位置同算式） */
    private fun iconXOf(e: CardHolderEntry, kind: String): Int {
        val rightEdge = panelWidth - 8
        val btnW = 44
        val iconW = 16
        val gap = 4
        return when (kind) {
            "black" -> rightEdge - btnW - gap - iconW
            "purple" -> if (e.holdsBlack) rightEdge - btnW - gap - iconW - gap - gap - btnW - gap - iconW
            else rightEdge - btnW - gap - iconW
            else -> 0
        }
    }

    private fun truncateName(name: String, maxWidth: Int): String {
        if (textRenderer.getWidth(name) <= maxWidth) return name
        var cut = name.length
        while (cut > 1 && textRenderer.getWidth(name.substring(0, cut) + "…") > maxWidth) cut--
        return name.substring(0, cut) + "…"
    }

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int, sliceH: Int = 16) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    /** 照借款历史界面：market_panel 三件套全屏面板 + 行背景条（非弹窗） */
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

        // 行背景（照借款历史行按钮列表惯例）
        val leftX = width / 2 - panelWidth / 2
        val startY = 48
        val rowHeight = 24
        val maxVisible = maxOf(3, (height - 48 - startY) / rowHeight)
        repeat(minOf(maxVisible, maxOf(0, entries.size - scrollOffset))) { i ->
            val rowY = startY + i * rowHeight
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, 0, ROW_BACKGROUND_TEX_H)
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val centerX = width / 2
        val leftX = width / 2 - panelWidth / 2
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.card.manage_title").formatted(Formatting.GOLD, Formatting.BOLD),
            centerX, 14, 0xFFFFFF
        )
        val rowHeight = 24
        val startY = 48
        val maxVisible = maxOf(3, (height - 48 - startY) / rowHeight)
        // 按钮行与第一条记录之间的分割线（照借款历史：按钮底 34、列表顶 48 → 线在正中 41）
        context.fill(width / 2 - panelWidth / 2, 41, width / 2 + panelWidth / 2, 42, 0xFF555555.toInt())
        val visible = entries.drop(scrollOffset).take(maxVisible)
        if (visible.isEmpty() && loaded) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.card.manage_empty").formatted(Formatting.GRAY),
                centerX, startY + 20, 0xFFFFFF
            )
            return
        }
        val purpleItem = net.minecraft.registry.Registries.ITEM.get(Identifier.of("cobblemarket", "meowth_purple_card"))
        val blackItem = net.minecraft.registry.Registries.ITEM.get(Identifier.of("cobblemarket", "meowth_black_card"))
        val air = net.minecraft.registry.Registries.ITEM.get(Identifier.of("minecraft", "air"))
        visible.forEachIndexed { i, e ->
            val y = startY + i * rowHeight
            // 名字（超宽截断；卡组右对齐行尾，最宽双持时紫图标起点 x+152，名字区留 4px 空隙截断 140）
            context.drawTextWithShadow(
                textRenderer,
                Text.literal(truncateName(e.name, 140)),
                leftX + 8, y + 7, 0xFFFFFF
            )
            if (e.holdsPurple && purpleItem != air) {
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = net.minecraft.item.ItemStack(purpleItem),
                    x = (leftX + iconXOf(e, "purple")).toDouble(), y = (y + 4).toDouble(), scale = 1.0,
                    matrixStack = context.matrices
                )
            }
            if (e.holdsBlack && blackItem != air) {
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = net.minecraft.item.ItemStack(blackItem),
                    x = (leftX + iconXOf(e, "black")).toDouble(), y = (y + 4).toDouble(), scale = 1.0,
                    matrixStack = context.matrices
                )
            }
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val startY = 48
        val rowHeight = 24
        val maxVisible = maxOf(3, (height - 48 - startY) / rowHeight)
        scrollOffset = (scrollOffset - verticalAmount.toInt())
            .coerceIn(0, maxOf(0, entries.size - maxVisible))
        rebuildRowButtons()
        return true
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        super.resize(client, width, height)
        rebuildRowButtons()
    }

    override fun shouldPause() = false
}
