package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.network.RequestBalancePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

class MarketEntryScreen : Screen(Text.translatable("cobblemarket.entry.title")) {

    private var btnStartY = 0
    private var totalH = 0

    override fun init() {
        super.init()
        ClientPlayNetworking.send(RequestBalancePayload())
        val centerX = width / 2
        val btnW = 87
        val btnH = 24
        val gap = 8
        val colGap = 2
        val totalW = btnW * 2 + colGap
        val leftX = centerX - totalW / 2
        val rightX = leftX + btnW + colGap

        val isAdmin = client?.player?.hasPermissionLevel(2) == true
        val rowCount = if (isAdmin) 3 else 2
        totalH = btnH * rowCount + gap * (rowCount - 1)
        val startY = height / 2 - totalH / 2
        btnStartY = startY

        // 行 1
        addDrawableChild(TextureButton(
            leftX, startY, btnW, btnH,
            Text.translatable("cobblemarket.entry.pokemon"),
            { client?.setScreen(MarketScreen()) }
        ))
        addDrawableChild(TextureButton(
            rightX, startY, btnW, btnH,
            Text.translatable("cobblemarket.entry.item"),
            { openItemMarket() }
        ))
        // 行 2
        addDrawableChild(TextureButton(
            leftX, startY + btnH + gap, btnW, btnH,
            Text.translatable("cobblemarket.entry.history"),
            { client?.setScreen(HistoryScreen()) }
        ))
        addDrawableChild(TextureButton(
            rightX, startY + btnH + gap, btnW, btnH,
            Text.translatable("cobblemarket.entry.auction"),
            { client?.setScreen(AuctionScreen()) },
            Identifier.of("cobblemarket", "textures/gui/auction_gavel_left.png"),
            Identifier.of("cobblemarket", "textures/gui/auction_gavel_right.png")
        ))
        // 行 3：管理员面板居中（仅 OP）
        if (isAdmin) {
            addDrawableChild(TextureButton(
                centerX - btnW / 2, startY + (btnH + gap) * 2, btnW, btnH,
                Text.translatable("cobblemarket.entry.op"),
                { client?.setScreen(AdminScreen()) }
            ))
        }
    }

    private fun openItemMarket() {
        client?.setScreen(ItemMarketScreen())
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val bg = Identifier.of("cobblemarket", "textures/gui/market_entry_background.png")
        // Center 160-tall background on content (title + buttons)
        val contentTop = btnStartY - 14
        val contentBottom = btnStartY + totalH
        val contentHeight = contentBottom - contentTop
        val bgTop = contentTop - (160 - contentHeight) / 2
        context.drawTexture(bg, width / 2 - 96, bgTop, 0f, 0f, 192, 160, 192, 160)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.entry.title").formatted(Formatting.GOLD),
            width / 2, btnStartY - 14, 0xFFFFFF
        )
        // 余额（标题上方，来自全局缓存）
        val bal = com.shusheng.cobblemarket.client.BalanceCache.balance
        if (bal.isNotEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.gui.balance", bal).string,
                width / 2, btnStartY - 26, 0x55FFFF
            )
        }
    }

    override fun shouldPause() = false
}
