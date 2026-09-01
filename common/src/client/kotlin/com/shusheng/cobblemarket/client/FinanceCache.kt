package com.shusheng.cobblemarket.client

/**
 * 金融开关全局缓存（照 BalanceCache 模式）：
 * 入口界面拉取 CreditInfo 时写入；购买弹窗打开直接读缓存值定布局——
 * 避免「先按关渲染窄弹窗、响应到达再撑开」的闪烁（进市场必经入口界面，缓存总在购买前就绪）。
 */
object FinanceCache {
    var financeEnabled = true
    var consumerLoanEnabled = false
}
