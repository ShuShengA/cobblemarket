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

    /**
     * RP 余额（已千分位，**不带单位** —— HUD 那行用 points_symbol 图标当单位）。
     * **空串 = 本服没开 RP**（服务端总开关关着时就不填），HUD 那行据此整行收掉。
     */
    var pointsBalance: String = ""

    /** RP 原始数值（HUD 变动浮字的差值对比用） */
    var pointsRaw: Long = 0L

    /** **喵喵银行存款余额**（已千分位；0 时为空串）：HUD 在「主货币」与「RP」之间显示这一行 */
    var depositBalance: String = ""
    /** 存款原始数值：HUD 判「> 0 才显示」用 */
    var depositBalanceRaw: Long = 0L
}
