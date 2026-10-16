package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.network.RequestIdCardDataPayload
import com.shusheng.cobblemarket.overview.PlayerOverviewRow
import com.shusheng.cobblemarket.platform.sendToServer

/**
 * **ID 卡（自己那张）的客户端缓存** —— 服务端只回自己那一行。
 *
 * 与 [PlayerOverviewCache] 分开存：那份是「全服总览」（要有 `overview` 节点），
 * 这份是「自己看自己」（人人都有，不走节点）。
 */
object IdCardDataCache {

    /** 自己的那一行；null = 还没请求过 / 还没回来 */
    var row: PlayerOverviewRow? = null
        private set

    /** 请求过没有 —— 用来区分「还在加载」与「真没数据」 */
    var loaded = false
        private set

    /** 打开 ID 卡界面时调一次（每次打开都重拉：这是自己看自己，顺便就刷新了） */
    fun request() {
        sendToServer(RequestIdCardDataPayload())
    }

    /** 服务端下发时调（调用方已在客户端主线程） */
    fun accept(row: PlayerOverviewRow?) {
        this.row = row
        loaded = true
    }
}
