package com.shusheng.cobblemarket.client

/**
 * 全局余额缓存：BalanceDataPayload 到达时更新（界面打开请求 + 交易操作后自动刷新）。
 * balance 由服务端做好千分位格式化，界面直接显示。
 */
object BalanceCache {
    var balance: String = ""
    var pendingBalance: Long = 0L
    /** 余额原始数值（HUD 变动提示的差值对比用） */
    var balanceRaw: Long = 0L
    /** 服务端当前货币标识（物品 ID 或虚拟货币翻译 key：Cobblemon Economy POKE=PokeDollars / Cobblemon Economy PCO=PokeCoins / CobbleDollars=CobbleDollars），余额包携带；弹窗/悬停货币名统一从这里取 */
    var currencyName: String = ""
}
