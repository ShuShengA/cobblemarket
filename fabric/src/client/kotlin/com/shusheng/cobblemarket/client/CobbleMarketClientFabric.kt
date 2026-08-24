package com.shusheng.cobblemarket.client

import net.fabricmc.api.ClientModInitializer

/**
 * fabric 平台客户端入口：fabric.mod.json 的 client entrypoint。
 * 初始化逻辑全在 common 的 CobbleMarketClient.init()，双平台共用。
 */
class CobbleMarketClientFabric : ClientModInitializer {
    override fun onInitializeClient() {
        CobbleMarketClient.init()
    }
}
