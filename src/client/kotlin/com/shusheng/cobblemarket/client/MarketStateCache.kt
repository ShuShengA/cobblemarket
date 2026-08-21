package com.shusheng.cobblemarket.client

/**
 * 市场总开关的客户端状态：MarketStatePayload 到达时更新（登录补发 + /market on|off 广播）。
 * 入口界面据此把交易入口置灰并提示。
 */
object MarketStateCache {
    var enabled: Boolean = true
}
