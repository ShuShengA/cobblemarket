package com.shusheng.cobblemarket.compat

import com.nbp.cobblemon_smartphone.api.SmartphoneAction
import com.shusheng.cobblemarket.client.CobbleMarketClient
import com.shusheng.cobblemarket.screen.MarketEntryScreen
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import net.minecraft.util.Identifier

object OpenMarketAction : SmartphoneAction {
    override val id = "cobblemarket:market"
    override val texture = Identifier.of("cobblemarket", "textures/gui/buttons/market.png")
    override val hoverTexture = Identifier.of("cobblemarket", "textures/gui/buttons/market_hover.png")
    override val displayName: Text get() = Text.translatable("cobblemarket.entry.pokemon")

    override fun onClick() {
        CobbleMarketClient.playEntrySound()
        MinecraftClient.getInstance().setScreen(MarketEntryScreen())
    }
}
