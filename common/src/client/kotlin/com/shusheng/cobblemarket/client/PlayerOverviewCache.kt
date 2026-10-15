package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.network.RequestPlayerOverviewPayload
import com.shusheng.cobblemarket.overview.PlayerOverviewRow
import com.shusheng.cobblemarket.platform.sendToServer

/**
 * 全服玩家总览的**客户端缓存**：服务端一次全量下发，这里存一份供界面读。
 *
 * 搜索与排序都在界面里**本地**做（切排序、打字零延迟，不发请求）——
 * 这也是"一次全量下发"的用意（200 人规模，见方案第六节）。
 */
object PlayerOverviewCache {

    /** 最近一次收到的全服行；空 = 还没请求过 / 请求还没回来 */
    var rows: List<PlayerOverviewRow> = emptyList()
        private set

    /** 打开界面时调一次：向服务端要最新的一份 */
    fun request() {
        sendToServer(RequestPlayerOverviewPayload())
    }

    /** 服务端下发时调（已在客户端主线程） */
    fun accept(rows: List<PlayerOverviewRow>) {
        this.rows = rows
    }
}
