package com.shusheng.cobblemarket.client

/**
 * RP 相关的客户端状态缓存（服务端 `PointsStatusPayload` 推过来）。
 *
 * [enabled] 未拉取时**默认乐观为开**（照 [MarketStateCache] 的惯例）：登录时服务端立刻补发一次，
 * 窗口极小；若默认关，刚登录那一下按钮会先灰后亮，反而闪。
 */
object PointsStateCache {

    /** RP 总开关；关闭时入口按钮置灰（点击给 fail 音 + 3 秒提示，照喵喵银行） */
    var enabled: Boolean = true
}
