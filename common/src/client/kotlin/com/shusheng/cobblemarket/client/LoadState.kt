package com.shusheng.cobblemarket.client

/**
 * 列表「首包是否已到」标记 —— 只用来区分**「还在加载」与「真的没有」**。
 *
 * 为什么需要它：列表数据全是**请求驱动**的（打开界面 → 发请求 → 服务端回包），
 * 而空状态那行字在**首包还没回来时就已经画出来了** ⇒ 玩家看到「暂无物品 / 暂无交易历史」，
 * 以为数据丢了、去问服主。2026-10-10 物品市场先按这个办法修过
 * （[ItemMarketScreen] 的 `everLoaded`）；2026-10-22 用户拍板**收口成共用小工具**后铺到其余列表界面。
 *
 * 用法（每屏一个实例，**每打开一次界面就是新实例** ⇒ 每次都会重新显示加载中）：
 * ```
 * private val loadState = LoadState()
 *
 * fun onXxxData(payload: XxxPayload) {
 *     entries = payload.entries
 *     loadState.markLoaded()          // ← 首包到达（**空包也要标**，那才是真正的「没有」）
 * }
 *
 * // 空列表时：
 * Text.translatable(loadState.emptyKey("cobblemarket.xxx.empty"))
 * ```
 *
 * ⚠ **别在请求发出时标 loaded** —— 那等于没修；必须在**收到回包**时标。
 * ⚠ 分页 / 切 tab 的界面：切 tab 会换一份数据源，必要时每份数据源各一个实例，
 *   否则从「已加载过」的 tab 切到另一个还没回包的 tab，会立刻显示空状态。
 */
class LoadState {
    var everLoaded = false
        private set

    /** 收到首包时调用（空列表也算已加载） */
    fun markLoaded() {
        everLoaded = true
    }

    /**
     * 换了一份**要重新请求的数据源**时调用（切币种等）—— 在新数据回来前重新显示「加载中…」。
     * ⚠ 只是同一份数据本地过滤（切 tab 过滤）不要调，那没有新的等待。
     */
    fun reset() {
        everLoaded = false
    }

    /** 空列表时该显示哪个词条：首包未到 = 加载中；到了才是真正的空 */
    fun emptyKey(emptyKey: String): String =
        if (everLoaded) emptyKey else "cobblemarket.gui.loading"
}
