package com.shusheng.cobblemarket.compat

import com.nbp.cobblemon_smartphone.api.SmartphoneAction
import com.shusheng.cobblemarket.client.CobbleMarketClient
import com.shusheng.cobblemarket.screen.MarketEntryScreen
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import net.minecraft.util.Identifier

object OpenMarketAction : SmartphoneAction {
    override val id = "cobblemarket:market"
    override val texture = Identifier.of("cobblemarket", "textures/gui/button/market.png")
    override val hoverTexture = Identifier.of("cobblemarket", "textures/gui/button/market_hover.png")
    // 手机界面悬停图标的 tooltip：对齐 smartphone 内置 app 的「打开 XXX」写法
    // （曾误借用入口界面「精灵市场」按钮的译名，英文显示成 Pokémon —— 而且点开的是整个入口界面，不是精灵市场）
    override val displayName: Text get() = Text.translatable("cobblemarket.smartphone.open_market")

    override fun onClick() {
        CobbleMarketClient.playEntrySound()
        MinecraftClient.getInstance().setScreen(MarketEntryScreen())
    }
}
