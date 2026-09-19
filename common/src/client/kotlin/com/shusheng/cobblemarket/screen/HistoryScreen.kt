package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.network.HistoryDataPayload
import com.shusheng.cobblemarket.network.HistoryEntry
import com.shusheng.cobblemarket.network.RequestHistoryPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.platform.configDir
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Util
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import java.text.SimpleDateFormat
import java.util.Date

class HistoryScreen(private val showAll: Boolean = false) :
    Screen(Text.translatable(if (showAll) "cobblemarket.history.all_title" else "cobblemarket.history.title")) {

    private val panelWidth = 296
    private var entries = listOf<HistoryEntry>()
    private var loaded = false
    private var scrollOffset = 0

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        // info 图标（左上角，仅全部交易历史）：模组多跑在服务器上，「打开文件夹」只会打开
        // 自己客户端的目录、没有实际意义（2026-09-13 用户拍板）—— 改成纯信息展示，
        // 悬停显示 CSV 账本在本机的存放路径，不再做点击动作
        if (showAll) {
            val infoBtn = NineSliceButton(
                leftX, 18, 16, 16,
                Text.literal(""),
                { },
                iconLeft = Identifier.of("cobblemarket", "textures/gui/info.png"),
                iconTexW = 48, iconTexH = 48, iconScale = 0.25f
            )
            infoBtn.setTooltip(
                net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("cobblemarket.history.folder_path", historyFolderPath())
                )
            )
            addDrawableChild(infoBtn)
        }

        // Back button (top-right)
        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 18, 50, 16,
            Text.literal(""),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        ))

        if (!loaded) {
            sendToServer(RequestHistoryPayload(showAll))
            loaded = true
        }
    }

    /**
     * CSV 账本的存放路径（仅用于 info 悬停提示，不做打开动作）。
     * 只给「从 config 开始」的相对路径 —— 绝对路径又长又带着用户目录，服主照着 config 目录找就行。
     */
    private fun historyFolderPath(): String = "config/cobblemarket/history"

    fun onHistoryData(payload: HistoryDataPayload) {
        entries = payload.entries
    }

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int, sliceH: Int = 16) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

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

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable(if (showAll) "cobblemarket.history.all_title" else "cobblemarket.history.title").formatted(Formatting.GOLD),
            width / 2, 14, 0xFFFFFF)

        val startY = 48
        val rowHeight = 18
        val dateFormat = SimpleDateFormat("MM-dd HH:mm")
        val panelHalf = panelWidth / 2
        // 按钮行与第一条记录之间的分割线（按钮底 34、列表顶 48 → 线在正中 41）
        drawPanelDivider(context, width / 2, 41)
        // Bottom border is 16px tall; content should end before height-48
        val maxVisible = maxOf(3, (height - 48 - startY) / rowHeight)

        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, entries.size - maxVisible))

        if (entries.isEmpty() && loaded) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.history.empty").formatted(Formatting.GRAY),
                width / 2, startY + 20, 0xFFFFFF)
        }

        val visibleEntries = entries.drop(scrollOffset).take(maxVisible)
        visibleEntries.forEachIndexed { i, e ->
            val y = startY + i * rowHeight
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, width / 2 - panelHalf, y, panelWidth, rowHeight, 0, ROW_BACKGROUND_TEX_H)
            val typeText = Text.translatable("cobblemarket.history.type.${e.type}").string
            val time = dateFormat.format(Date(e.timestamp))
            val speciesText = if (e.category == "ITEM") {
                val id = Identifier.tryParse(e.species)
                if (id != null) Registries.ITEM.get(id).name.string else e.species
            } else {
                Text.translatable(e.species).string
            }
            val seller = if (e.sellerName.isNotEmpty()) e.sellerName else "?"
            val buyer = if (e.buyerName.isNotEmpty()) " → ${e.buyerName}" else ""

            var x = width / 2 - panelHalf + 5
            context.drawTextWithShadow(textRenderer, time, x, y + 4, 0x888888)
            x += textRenderer.getWidth(time) + 6
            val typeLabel = "[$typeText]"
            context.drawTextWithShadow(textRenderer, typeLabel, x, y + 4, typeColor(e.type))
            x += textRenderer.getWidth(typeLabel) + 6
            val middle = if (showAll) "$seller$buyer $speciesText" else "$speciesText"
            context.drawTextWithShadow(textRenderer, middle, x, y + 4, 0xFFFFFF)
            x += textRenderer.getWidth(middle) + 6
            // 单位按**这一笔自己的币种**来：主货币 `₽`、RP 用图标（与全项目同一个口径）
            val priceText: Text =
                if (MarketCurrency.fromStored(e.currency) == MarketCurrency.POINTS) {
                    Text.literal("| ${com.shusheng.cobblemarket.client.formatPrice(e.price)} ")
                        .append(CurrencyService.pointsIcon())
                } else {
                    Text.literal(
                        "| ${com.shusheng.cobblemarket.client.formatPrice(e.price)} " +
                            com.shusheng.cobblemarket.client.inlineCurrencyUnit()
                    )
                }
            context.drawTextWithShadow(textRenderer, priceText, x, y + 4, 0xFFAA00)
            if (!showAll && buyer.isNotEmpty()) {
                x += textRenderer.getWidth(priceText) + 6
                context.drawTextWithShadow(textRenderer, buyer.trimStart(), x, y + 4, 0xFFFFFF)
            }
        }

        // Scroll indicator
        if (entries.size > maxVisible) {
            context.drawCenteredTextWithShadow(textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible, entries.size)} / ${entries.size}",
                width / 2, height - 49, 0x888888)
        }
    }

    private fun typeColor(type: String): Int = when (type) {
        "ADD" -> 0x55FF55
        "PURCHASE" -> 0xFFAA00
        "BUY" -> 0xFFAA00
        "CANCEL" -> 0xAAAAAA
        "RETURN" -> 0x55AAFF
        else -> 0xFFFFFF
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val rowHeight = 18
        val maxVisible = maxOf(3, (height - 48 - 48) / rowHeight)
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, entries.size - maxVisible))
        return true
    }

    override fun shouldPause() = false
}
