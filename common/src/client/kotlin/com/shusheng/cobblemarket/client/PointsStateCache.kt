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

    /**
     * **玩家间 RP 交易开关**（`pointsMarketEnabled`）。关闭时上架界面**不显示**货币切换按钮，
     * 玩家只能用主货币定价；服务端上架请求里还会再拦一道，这里只是不给玩家白点。
     *
     * ⚠ 默认 **false**（与 [enabled] 的乐观默认**相反**）：这个开关本来就是「保守才关」，
     * 未拉取时先按关处理 —— 宁可少显示一个按钮，也别让玩家看到按钮、点了却被服务端拒。
     */
    var marketEnabled: Boolean = false

    /**
     * **主 RP 商城开关**（`pointsMainShopEnabled`，默认关）。
     * 关闭时入口界面的 RP 商城按钮置灰（点击提示未开放）；自定义 NPC 商店不受影响。
     * 未拉取时**乐观为开**（与 [enabled] 一致），服务端登录后立刻补发纠正。
     */
    var mainShopEnabled: Boolean = true
}
