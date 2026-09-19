package com.shusheng.cobblemarket.screen

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * **我的兑换记录**（玩家侧）：我用兑换码兑过什么、到账多少。
 *
 * 入口在 RP 市场界面的「兑换记录」按钮（购买 RP 左侧）。
 * 布局照同背景的列表界面（交易历史 / 黑名单那套）：全高 `market_panel` 面板 + 296 宽的行 +
 * 顶部操作行下方分割线 + 底部区间指示。
 *
 * ⚠ **当前是骨架**：列表数据接服务端查询还没做（见 memory 的待办），
 * 先给出结构与空状态，避免入口点开是黑的。
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

    /** 兑换记录（时间 / 面额 / 到账后余额）——数据接入后填充 */
    private var entries = listOf<RecordEntry>()

    data class RecordEntry(val timestamp: Long, val amount: Long, val balanceAfter: Long)

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
        val panelBottom = height - PANEL_BOTTOM_INSET
        val sliceH = 16
        drawPanelSlice(context, PANEL_TOP, panelLeft, panelTop)
        var y = panelTop + sliceH
        while (y < panelBottom - sliceH) {
            drawPanelSlice(context, PANEL_MID, panelLeft, y, minOf(sliceH, panelBottom - sliceH - y))
            y += sliceH
        }
        drawPanelSlice(context, PANEL_BOT, panelLeft, panelBottom - sliceH)

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.record.title"),
            width / 2, 20, 0xFFFFFF
        )
        entries.take(maxVisible()).forEachIndexed { i, _ ->
            drawNineSlice(
                context, ROW_BACKGROUND_TEXTURE, leftX(), listStartY + i * rowHeight, panelWidth, rowHeight,
                0, ROW_BACKGROUND_TEX_H
            )
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        drawPanelDivider(context, leftX() + panelWidth / 2, 62)
        if (entries.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.record.empty"),
                width / 2, listStartY + 24, 0xAAAAAA
            )
        }
    }

    override fun shouldPause() = false

    private companion object {
        val PANEL_TOP = Identifier.of("cobblemarket", "textures/gui/market_panel_top.png")
        val PANEL_MID = Identifier.of("cobblemarket", "textures/gui/market_panel_middle.png")
        val PANEL_BOT = Identifier.of("cobblemarket", "textures/gui/market_panel_bottom.png")
    }
}
