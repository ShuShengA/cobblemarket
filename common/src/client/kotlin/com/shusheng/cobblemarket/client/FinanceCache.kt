package com.shusheng.cobblemarket.client

/**
 * 金融数据全局缓存（照 BalanceCache 模式）：
 * - 金融开关：入口界面拉取 CreditInfo 时写入；购买弹窗打开直接读缓存值定布局——
 *   避免「先按关渲染窄弹窗、响应到达再撑开」的闪烁（进市场必经入口界面，缓存总在购买前就绪）
 * - 额度/欠款/累计成交额：各界面 onCreditInfo/onFinanceStats 与 60 秒兜底轮询写入，
 *   界面打开直接读缓存秒显（-1 = 未拉取，界面按 0/不渲染处理），消除「每次切界面闪一下」的现象
 */
object FinanceCache {
    var financeEnabled = true
    var consumerLoanEnabled = false
    /** 可用额度 / 当前欠款（-1 = 未拉取） */
    var creditLimit = -1L
    var creditDebt = -1L
    /** 全服累计成交额（-1 = 未拉取） */
    var totalVolume = -1L
}
