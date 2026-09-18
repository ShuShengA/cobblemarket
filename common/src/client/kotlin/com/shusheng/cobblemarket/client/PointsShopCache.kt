package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.network.PointOrderInfo
import com.shusheng.cobblemarket.network.ShopTierInfo

/**
 * 点券商城信息的客户端缓存（[com.shusheng.cobblemarket.network.PointsShopInfoPayload] 的落地处）。
 *
 * ⚠ 必须缓存：那份快照是**玩家进界面时请求**才下发的，而收款码弹窗要等**下单/有单**才开 ——
 * 不落地的话「包先到、弹窗后建」会拿到空数据（S2C 缓存铁律）。
 */
object PointsShopCache {

    var tiers: List<ShopTierInfo> = emptyList()

    /** 服主配了收款码（决定弹窗里画图还是只画文字） */
    var hasQr: Boolean = false

    /** 玩家**全部**未完结订单（按创建时间升序；空列表 = 没有）—— 允许多笔，上限在服务端把关 */
    var orders: List<PointOrderInfo> = emptyList()

    /** 档位金额的货币单位（空 = 不显示单位），服主可在配置界面自定义 */
    var currencyLabel: String = ""

    /** 收到过至少一次快照（区分「还没拉到」与「拉到了但没有」） */
    var loaded: Boolean = false

    fun clear() {
        tiers = emptyList()
        hasQr = false
        orders = emptyList()
        currencyLabel = ""
        loaded = false
    }
}
