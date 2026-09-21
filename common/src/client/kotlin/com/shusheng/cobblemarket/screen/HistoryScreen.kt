package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.network.HistoryDataPayload
import com.shusheng.cobblemarket.network.HistoryEntry
import com.shusheng.cobblemarket.network.RequestHistoryPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.platform.configDir
import com.shusheng.cobblemarket.util.TextUtil
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
    /** 鼠标当前悬停的行（-1 = 无）：行悬停态与悬停窗共用，每帧在 render 里重算 */
    private var hoveredRow = -1

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

        // 悬停行：鼠标落在列表区域内才算（判据同权限管理界面：先框内、再除以行高）
        val listLeft = width / 2 - panelHalf
        hoveredRow = -1
        if (mouseX in listLeft..(listLeft + panelWidth) &&
            mouseY in startY until (startY + visibleEntries.size * rowHeight)) {
            val row = (mouseY - startY) / rowHeight
            if (row in visibleEntries.indices) hoveredRow = row
        }

        visibleEntries.forEachIndexed { i, e ->
            val y = startY + i * rowHeight
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, listLeft, y, panelWidth, rowHeight, if (i == hoveredRow) 1 else 0, ROW_BACKGROUND_TEX_H)
            val time = dateFormat.format(Date(e.timestamp))
            val typeLabel = "[${typeTextOf(e)}]"
            val speciesText = speciesTextOf(e)
            val seller = if (e.sellerName.isNotEmpty()) e.sellerName else "?"
            val buyer = if (e.buyerName.isNotEmpty()) " → ${e.buyerName}" else ""

            val x = listLeft + 5
            context.drawTextWithShadow(textRenderer, time, x, y + 4, 0x888888)
            var cx = x + textRenderer.getWidth(time) + 6
            context.drawTextWithShadow(textRenderer, typeLabel, cx, y + 4, typeColor(e.type))
            cx += textRenderer.getWidth(typeLabel) + 6

            // 行尾（价格 / 买家名）**从右缘反推定位**（照贷款历史界面的惯例）。
            // ⚠ 别写成「中间段之后再累加」—— 那样「价格 + 买家名」合计超宽时（长名字 + 大额），
            //   尾段会被一路推出面板右缘、中间段被挤成只剩一个「…」（2026-09-21 修）
            val pricePart = "| ${com.shusheng.cobblemarket.client.formatPrice(e.price)} ${com.shusheng.cobblemarket.client.inlineCurrencyUnit()}"
            val buyerPart = if (!showAll && buyer.isNotEmpty()) buyer.trimStart() else ""
            val right = width / 2 + panelHalf - 5
            val priceX = right - textRenderer.getWidth(pricePart)
            // 买家名夹在「中间段最小宽度」与价格之间，放不下就截断（完整名字见悬停窗）——
            // 不截的话，长名字 + 大额价格会把中间段挤成空、两段文字叠在一起
            val buyerShown = if (buyerPart.isEmpty()) buyerPart else TextUtil.truncateString(
                buyerPart, (priceX - 6 - cx - MIDDLE_MIN_W - 6).coerceAtLeast(0)
            )
            val buyerX = priceX - (if (buyerShown.isNotEmpty()) textRenderer.getWidth(buyerShown) + 6 else 0)
            // 中间段按实际可用像素截断（truncateString 单位为像素）——
            // 无翻译的物品 id 会原样显示成超长 key，不截断会把整行画出面板
            val middle = TextUtil.truncateString(
                if (showAll) "$seller$buyer $speciesText" else speciesText,
                (if (buyerShown.isNotEmpty()) buyerX else priceX) - 6 - cx
            )
            context.drawTextWithShadow(textRenderer, middle, cx, y + 4, 0xFFFFFF)
            context.drawTextWithShadow(textRenderer, pricePart, priceX, y + 4, 0xFFAA00)
            if (buyerShown.isNotEmpty()) {
                context.drawTextWithShadow(textRenderer, buyerShown, buyerX, y + 4, 0xFFFFFF)
            }
        }

        // 悬停行完整信息：行内的时间/类型/名字/价格都可能被截断或压缩，这里给全的
        if (hoveredRow >= 0) {
            val e = visibleEntries[hoveredRow]
            val seller = if (e.sellerName.isNotEmpty()) e.sellerName else "?"
            val buyer = if (e.buyerName.isNotEmpty()) " → ${e.buyerName}" else ""
            val fullTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(e.timestamp))
            renderTooltip(context, buildList {
                add(speciesTextOf(e))
                add("[${typeTextOf(e)}] $fullTime")
                add("$seller$buyer")
                add("${com.shusheng.cobblemarket.client.formatPrice(e.price)} ${com.shusheng.cobblemarket.client.inlineCurrencyUnit()}")
            }, mouseX, mouseY)
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

    /** 类型标签文案（行内与悬停窗共用） */
    private fun typeTextOf(e: HistoryEntry): String =
        Text.translatable("cobblemarket.history.type.${e.type}").string

    /** 精灵 / 物品显示名（行内与悬停窗共用）：物品走注册表名，精灵走词条 */
    private fun speciesTextOf(e: HistoryEntry): String = if (e.category == "ITEM") {
        val id = Identifier.tryParse(e.species)
        if (id != null) Registries.ITEM.get(id).name.string else e.species
    } else {
        Text.translatable(e.species).string
    }

    /** 悬停窗（照权限管理界面的 renderTooltip）：首行金色、其余白色，位置自动躲开屏幕边缘 */
    private fun renderTooltip(context: DrawContext, lines: List<String>, mouseX: Int, mouseY: Int) {
        var maxWidth = 0
        lines.forEach { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it)) }

        val padding = 4
        val tx = minOf(mouseX + 12, width - maxWidth - 12).coerceAtLeast(4)
        val tooltipHeight = lines.size * 10 + padding
        val tyAbove = mouseY - tooltipHeight - 4
        val ty = if (tyAbove <= 0) minOf(mouseY + 12, height - tooltipHeight) else tyAbove

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, lines.size * 10 + 2 * padding, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, line ->
            context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, if (i == 0) GOLD_COLOR else 0xFFFFFF)
        }
        context.matrices.pop()
    }

    private companion object {
        /** 中间段至少保留的像素宽（约 4 个汉字）：不够时优先截行尾的买家名，保精灵名 */
        const val MIDDLE_MIN_W = 40
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val rowHeight = 18
        val maxVisible = maxOf(3, (height - 48 - 48) / rowHeight)
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, entries.size - maxVisible))
        return true
    }

    override fun shouldPause() = false
}
