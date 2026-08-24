package com.shusheng.cobblemarket

import com.shusheng.cobblemarket.platform.neoforge.NeoForgePlatform
import com.shusheng.cobblemarket.platform.neoforge.registerClientC2S
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment

/**
 * neoforge 平台入口：@Mod 构造器即初始化时机（neoforge 无 fabric 式 entrypoint 概念）。
 * 服务端与客户端初始化逻辑全在 common 的 CobbleMarket / CobbleMarketClient，双平台共用。
 *
 * 客户端初始化放在构造器内（dist 判断）而非 FMLClientSetupEvent：
 * 桩实现需要在 mod 构造期拿到本 mod 的 event bus 注册网络/按键/HUD 监听，
 * 构造器之后 ModLoadingContext 的 activeContainer 已清空，无从获取。
 */
@Mod(CobbleMarket.MOD_ID)
class CobbleMarketNeoForge(bus: IEventBus) {
    init {
        NeoForgePlatform.modBus = bus
        CobbleMarket.init()
        if (FMLEnvironment.dist.isClient) {
            com.shusheng.cobblemarket.client.CobbleMarketClient.init()
            // neoforge 网络协商双端一致性：客户端空注册全部 C2S 类型（见该函数注释）
            registerClientC2S()
        }
    }
}
