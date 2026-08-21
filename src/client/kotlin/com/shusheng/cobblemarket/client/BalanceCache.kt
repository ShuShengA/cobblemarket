package com.shusheng.cobblemarket.client

/**
 * 全局余额缓存：BalanceDataPayload 到达时更新（界面打开请求 + 交易操作后自动刷新）。
 * balance 由服务端做好千分位格式化，界面直接显示。
 */
object BalanceCache {
    var balance: String = ""
    var pendingBalance: Long = 0L
    /** 服务端当前货币标识（物品 ID 或 PokéDollars 翻译 key），余额包携带；弹窗/悬停货币名统一从这里取 */
    var currencyName: String = ""
}
