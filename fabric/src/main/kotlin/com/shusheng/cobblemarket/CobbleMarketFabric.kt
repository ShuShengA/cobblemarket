package com.shusheng.cobblemarket

import net.fabricmc.api.ModInitializer

/**
 * fabric 平台入口：fabric.mod.json 的 main entrypoint。
 * 初始化逻辑全在 common 的 CobbleMarket.init()，双平台共用。
 */
class CobbleMarketFabric : ModInitializer {
    override fun onInitialize() {
        CobbleMarket.init()
    }
}
