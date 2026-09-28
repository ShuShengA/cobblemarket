package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.currency.MarketCurrency

/**
 * 市场总开关的客户端状态：MarketStatePayload 到达时更新（登录补发 + /market on|off 广播）。
 * 入口界面据此把交易入口置灰并提示。
 */
object MarketStateCache {
    var enabled: Boolean = true

    /** 待领取总数（精灵 + 物品；登录时由服务端推一次）——只在界面上显示用 */
    var pendingReturns: Int = 0

    /** 「离线收益」提示音的**待播时刻**（0 = 无）：登录推送发现有待办时设为 now + 2 秒，
     *  等世界加载完再响（免得和加载画面挤在一起）；tick 里到点播一次并清零。
     *  ⚠ 时机是**上线时**，不是打开市场时——开了市场界面玩家自己就能看到收款/待领取按钮和红点。 */
    var offlineRewardAt: Long = 0L

    /**
     * 玩家侧市场系界面**共用**的「当前在看哪个币种」（2026-09-28 用户报的两个方向）。
     *
     * 这些界面之间来回跳时，币种要跟着走：
     * - 在 **RP 页**点「上架」⇒ 上架界面**默认就是 RP**（不用玩家再手动切一次）
     * - 在上架界面选了 RP 并上架 ⇒ 返回市场**落在 RP 页**（马上看得到刚挂上去的那件）
     *
     * ⚠ **为什么非要存在外面**：这些界面之间都是 `setScreen(XxxScreen())` **新建实例**，
     *   而币种是各自的实例字段 ⇒ 不在这儿存一份，跳一次就归零。原先那个币种只发给了服务端
     *  （payload 里），客户端侧没有任何回传 —— 这就是「上架完回到普通货币页」的根。
     * ⚠ **只管玩家侧**：管理端那几个界面（`AdminPokemonScreen` / `AdminItemScreen` /
     *   `AdminAuctionScreen`）各有自己的 `currency`，不读也不写这里 —— 管理端之间互不影响，
     *   也不该被玩家侧的选择带着走。
     */
    var marketCurrency: MarketCurrency = MarketCurrency.PRIMARY
}
