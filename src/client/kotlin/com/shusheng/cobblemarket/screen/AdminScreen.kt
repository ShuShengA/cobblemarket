package com.shusheng.cobblemarket.screen

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

class AdminScreen : Screen(Text.translatable("cobblemarket.op.title")) {

    private var btnStartY = 0
    private var totalH = 0

    override fun init() {
        super.init()
        val centerX = width / 2
        val btnW = 87
        val btnH = 20
        val gap = 6
        val colGap = 2
        val totalW = btnW * 2 + colGap
        val leftX = centerX - totalW / 2
        val rightX = leftX + btnW + colGap
        val rowCount = 4
        totalH = btnH * rowCount + gap * (rowCount - 1)
        val startY = height / 2 - totalH / 2
        btnStartY = startY

        // 行 1
        addDrawableChild(TextureButton(
            leftX, startY, btnW, btnH,
            Text.translatable("cobblemarket.op.pokemon"),
            { client?.setScreen(AdminPokemonScreen()) }
        ))
        addDrawableChild(TextureButton(
            rightX, startY, btnW, btnH,
            Text.translatable("cobblemarket.op.item"),
            { client?.setScreen(AdminItemScreen()) }
        ))
        // 行 2
        addDrawableChild(TextureButton(
            leftX, startY + btnH + gap, btnW, btnH,
            Text.translatable("cobblemarket.ban.title"),
            { client?.setScreen(AdminBanScreen()) }
        ))
        addDrawableChild(TextureButton(
            rightX, startY + btnH + gap, btnW, btnH,
            Text.translatable("cobblemarket.entry.all_history"),
            { client?.setScreen(HistoryScreen(true)) }
        ))
        // 行 3
        addDrawableChild(TextureButton(
            leftX, startY + (btnH + gap) * 2, btnW, btnH,
            Text.translatable("cobblemarket.op.blacklist_pokemon"),
            { client?.setScreen(PokemonBlacklistScreen()) }
        ))
        addDrawableChild(TextureButton(
            rightX, startY + (btnH + gap) * 2, btnW, btnH,
            Text.translatable("cobblemarket.op.blacklist_item"),
            { client?.setScreen(ItemBlacklistScreen()) }
        ))
        // 行 4
        addDrawableChild(TextureButton(
            leftX, startY + (btnH + gap) * 3, btnW, btnH,
            Text.translatable("cobblemarket.op.price_limit"),
            { client?.setScreen(PriceLimitScreen()) }
        ))
        addDrawableChild(TextureButton(
            rightX, startY + (btnH + gap) * 3, btnW, btnH,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(MarketEntryScreen()) }
        ))
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val bg = Identifier.of("cobblemarket", "textures/gui/market_entry_background.png")
        val bgTop = btnStartY - 25
        context.drawTexture(bg, width / 2 - 96, bgTop, 0f, 0f, 192, 160, 192, 160)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.op.title").formatted(Formatting.GOLD),
            width / 2, btnStartY - 14, 0xFFFFFF
        )
    }

    override fun shouldPause() = false
}
