package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.network.CustomShopAdminInfo
import com.shusheng.cobblemarket.network.CustomShopAdminListPayload
import com.shusheng.cobblemarket.network.CustomShopNearbyInfo

/**
 * **商店管理界面的客户端缓存**（`CustomShopAdminListPayload` 的落地处）。
 *
 * 与 [PointMarketCache] 同一个理由：S2C 包到的时候界面可能还没建出来（打开 / resize），
 * 不落地就白跑一趟。界面读这里；包一到就通过 `onListData` 通知重建行按钮。
 */
object CustomShopAdminCache {

    var shops: List<CustomShopAdminInfo> = emptyList()
        private set

    /** 玩家附近 16 格内的实体（绑定 / 解绑用） */
    var nearby: List<CustomShopNearbyInfo> = emptyList()
        private set

    /** 收到过至少一次列表（区分「还没拉到」与「拉到了但是空的」） */
    var loaded: Boolean = false
        private set

    fun accept(payload: CustomShopAdminListPayload) {
        shops = payload.shops
        nearby = payload.nearby
        loaded = true
    }

    fun shop(id: String): CustomShopAdminInfo? = shops.firstOrNull { it.id == id }
}
