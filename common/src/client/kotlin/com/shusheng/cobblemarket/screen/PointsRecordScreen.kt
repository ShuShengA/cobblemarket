package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.PointsRecordCache
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.network.PointRecordInfo
import com.shusheng.cobblemarket.network.RequestPointRecordsPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * **我的兑换记录**（玩家侧）：我用兑换码兑过什么、到账多少。
 *
 * 入口在 RP 商城界面的「兑换记录」按钮（购买 RP 左侧）。
 * 布局照同背景的列表界面（交易历史 / 兑换码管理那套）：全高 `market_panel` 面板 + 296 宽的行 +
 * 顶部操作行下方分割线 + 底部区间指示。
 *
 * 数据源：服务端 [com.shusheng.cobblemarket.currency.PointRecordState]（本地码与外部签名码**两条路都记**），
 * 走 `RequestPointRecordsPayload` 只取自己的那几条。
 */
class PointsRecordScreen : Screen(Text.translatable("cobblemarket.points.record.title")) {

    private val panelWidth = 296
    private val rowHeight = 22
    private val listStartY = 68
    private val PANEL_BOTTOM_INSET = 32
    private val CONTENT_BOTTOM_INSET = 48

    private fun leftX() = width / 2 - panelWidth / 2
    private fun maxVisible() = maxOf(1, (height - CONTENT_BOTTOM_INSET - listStartY) / rowHeight)

    private var backButton: NineSliceButton? = null

    /**
     * 记录列表直接读缓存（**最新在前**，服务端已排好序）。
     *
     * ⚠ 不再存一份界面字段：包到的时候界面可能还没建 / 已经重建过（返回再进、resize），
     * 缓存是唯一来源，界面只是画它。
     */
    private val entries: List<PointRecordInfo> get() = PointsRecordCache.entries

    private var scrollOffset = 0
    private var hoveredRow = -1

    override fun init() {
        super.init()
        backButton = NineSliceButton(
            leftX() + panelWidth - 50, 13, 50, 16, Text.literal(""),
            { client?.setScreen(PointsMarketScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)
        requestRecords()
        clampScroll()
    }

    // ── 请求（排队补发：服务端节流窗口内直接丢包，不回错也不回包）──

    /** 请求记录。窗口没到就排队，等 [tick] 补发 —— 连点/重建合并成最后一次 */
    private fun requestRecords() {
        val now = System.currentTimeMillis()
        if (now - PointsRecordCache.lastRequestAt >= LIST_REQUEST_INTERVAL_MS) {
            PointsRecordCache.lastRequestAt = now
            PointsRecordCache.pending = false
            sendToServer(RequestPointRecordsPayload())
        } else {
            PointsRecordCache.pending = true
        }
    }

    override fun tick() {
        super.tick()
        if (PointsRecordCache.pending &&
            System.currentTimeMillis() - PointsRecordCache.lastRequestAt >= LIST_REQUEST_INTERVAL_MS
        ) {
            requestRecords()
        }
    }

    /** 数据到了（由 S2C 分发调用）。界面只依赖缓存，这里只需要把滚动位置拉回合法区间 */
    fun onRecords() = clampScroll()

    private fun clampScroll() {
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, entries.size - maxVisible()))
    }

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int, sliceH: Int = 16) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    /** 右侧滚动条（照同族界面，与六个配置界面同款）：**行数超出一屏才画**，否则玩家不知道下面还有东西 */
    private fun renderScrollbar(context: DrawContext) {
        val maxScroll = maxOf(0, entries.size - maxVisible())
        if (maxScroll <= 0) return
        val x = leftX() + panelWidth - 5
        val top = listStartY
        val trackH = height - CONTENT_BOTTOM_INSET - listStartY
        val thumbH = maxOf(16, trackH * trackH / (trackH + maxScroll * rowHeight))
        val thumbY = top + (trackH - thumbH) * scrollOffset / maxScroll
        context.fill(x, top, x + 2, top + trackH, 0x40FFFFFF)
        context.fill(x, thumbY, x + 2, thumbY + thumbH, 0xC0FFFFFF.toInt())
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 面板 320 宽（drawPanelSlice 内部按 640×0.5 画），行 296 宽（两侧各留 12px 边框）——
        // 口径与兑换码管理 / 充值订单一致，别写成 leftX()（那样面板会左移 10px、行顶在边框上）
        val panelLeft = width / 2 - 160
        val panelTop = 2
        val panelBottom = height - PANEL_BOTTOM_INSET
        val sliceH = 16
        drawPanelSlice(context, PANEL_TOP, panelLeft, panelTop)
        var y = panelTop + sliceH
        while (y < panelBottom - sliceH) {
            drawPanelSlice(context, PANEL_MID, panelLeft, y, minOf(sliceH, panelBottom - sliceH - y))
            y += sliceH
        }
        drawPanelSlice(context, PANEL_BOT, panelLeft, panelBottom - sliceH)

        // 标题（金色，与同背景的列表界面一致），画在**背景层**：画在 render 里会盖在控件之上
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.record.title").formatted(Formatting.GOLD),
            width / 2, 20, 0xFFFFFF
        )

        // 行背景（悬停行换一张）——行左缘必须是 leftX()（= 296 宽居中），与下面画文字同一口径
        entries.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, _ ->
            drawNineSlice(
                context, ROW_BACKGROUND_TEXTURE, leftX(), listStartY + i * rowHeight, panelWidth, rowHeight,
                if (i == hoveredRow) 1 else 0, ROW_BACKGROUND_TEX_H
            )
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val cx = width / 2

        drawPanelDivider(context, leftX() + panelWidth / 2, DIVIDER_Y)

        if (entries.isEmpty()) {
            // 还没拉到过就显示「加载中」—— 直接说「还没有记录」是错的（第一次进来必然如此）
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(
                    if (PointsRecordCache.loaded) "cobblemarket.points.record.empty"
                    else "cobblemarket.points.record.loading"
                ).formatted(Formatting.GRAY),
                cx, listStartY + 24, 0xAAAAAA
            )
        } else {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            entries.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, e ->
                val y = listStartY + i * rowHeight + 7
                // 时间：`Instant` 按**本地时区**转（存档里存的是 epoch 毫秒，跨时区看的是自己的钟点）
                context.drawTextWithShadow(
                    textRenderer,
                    Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault()).format(fmt),
                    leftX() + COL_TIME, y, 0xAAAAAA
                )
                // 面额：**恒为入账**，所以带个「+」；⚠ 用 Text 追加拼，别转成 String 再拼（图标是字形，一转就成方块）
                context.drawTextWithShadow(
                    textRenderer,
                    Text.literal("+").append(CurrencyService.amountText(e.amount, MarketCurrency.POINTS)),
                    leftX() + COL_AMOUNT, y, 0xFFAA00
                )
                // 到账后余额：这一列是「当时余额」，不是本次变动 —— 悬停里有带标签的说明
                context.drawTextWithShadow(
                    textRenderer,
                    CurrencyService.amountText(e.balanceAfter, MarketCurrency.POINTS),
                    leftX() + COL_BALANCE, y, 0xFFFFFF
                )
            }
            if (entries.size > maxVisible()) {
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible(), entries.size)} / ${entries.size}",
                    cx, height - 49, 0x888888
                )
            }
            // 悬停：把三列分别是什么讲清楚（行内没有列标题的位置）
            entries.getOrNull(scrollOffset + hoveredRow)?.let { e ->
                val full = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                context.drawTooltip(
                    textRenderer,
                    listOf(
                        Text.translatable(
                            "cobblemarket.points.record.tip_time",
                            Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault()).format(full)
                        ),
                        Text.translatable(
                            "cobblemarket.points.record.tip_amount",
                            CurrencyService.amountText(e.amount, MarketCurrency.POINTS)
                        ),
                        Text.translatable(
                            "cobblemarket.points.record.tip_balance",
                            CurrencyService.amountText(e.balanceAfter, MarketCurrency.POINTS)
                        )
                    ),
                    mouseX, mouseY
                )
            }
        }
        renderScrollbar(context)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val max = maxOf(0, entries.size - maxVisible())
        if (max <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        val target = (scrollOffset - verticalAmount.toInt()).coerceIn(0, max)
        if (target == scrollOffset) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        scrollOffset = target
        return true
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        super.mouseMoved(mouseX, mouseY)
        val rel = ((mouseY - listStartY) / rowHeight).toInt()
        hoveredRow = if (mouseY >= listStartY && rel in 0 until minOf(maxVisible(), entries.size - scrollOffset)) {
            rel
        } else {
            -1
        }
    }

    override fun shouldPause() = false

    private companion object {
        /** 列表请求节流：服务端 `READ_INTERVAL_MS`(500ms) + 100ms 余量（同宽时网络抖动会让请求落回服务端窗口里） */
        const val LIST_REQUEST_INTERVAL_MS = 600L

        /** 分割线 y：与同族列表界面同一式子（首行 68 − 6px 间隙） */
        const val DIVIDER_Y = 62

        // 列的固定偏移（相对 leftX）：固定列才与整体观感一致，别用前一列的文字宽度推
        const val COL_TIME = 8
        const val COL_AMOUNT = 118
        const val COL_BALANCE = 206

        val PANEL_TOP = Identifier.of("cobblemarket", "textures/gui/market_panel_top.png")
        val PANEL_MID = Identifier.of("cobblemarket", "textures/gui/market_panel_middle.png")
        val PANEL_BOT = Identifier.of("cobblemarket", "textures/gui/market_panel_bottom.png")
    }
}
