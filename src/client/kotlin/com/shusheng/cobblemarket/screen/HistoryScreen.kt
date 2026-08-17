package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.network.HistoryDataPayload
import com.shusheng.cobblemarket.network.HistoryEntry
import com.shusheng.cobblemarket.network.RequestHistoryPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.loader.api.FabricLoader
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

        // Open history folder button (top-left, all-history only)
        if (showAll) {
            addDrawableChild(NineSliceButton(
                leftX, 18, 70, 16,
                Text.translatable("cobblemarket.history.open_folder"),
                { openHistoryFolder() }
            ))
        }

        // Back button (top-right)
        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 18, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(MarketEntryScreen()) }
        ))

        if (!loaded) {
            ClientPlayNetworking.send(RequestHistoryPayload(showAll))
            loaded = true
        }
    }

    private fun openHistoryFolder() {
        try {
            val dir = FabricLoader.getInstance().configDir.resolve("cobblemarket/history")
            dir.toFile().mkdirs()
            Util.getOperatingSystem().open(dir)
        } catch (_: Exception) {
        }
    }

    fun onHistoryData(payload: HistoryDataPayload) {
        entries = payload.entries
    }

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f, 1f)
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
            drawPanelSlice(context, mid, panelLeft, y)
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
            val pricePart = "| ${com.shusheng.cobblemarket.client.formatPrice(e.price)} ◆"
            context.drawTextWithShadow(textRenderer, pricePart, x, y + 4, 0x55FFFF)
            if (!showAll && buyer.isNotEmpty()) {
                x += textRenderer.getWidth(pricePart) + 6
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
        "BUY" -> 0x55FFFF
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
