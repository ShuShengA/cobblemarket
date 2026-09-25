package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.currency.ShopEntry
import com.shusheng.cobblemarket.network.PointMarketItemsPayload

/**
 * **RP 商城快照的客户端缓存**（[PointMarketItemsPayload] 的落地处）。
 *
 * ⚠ 必须缓存：快照是**请求才下发**的，而包到的时候界面可能还没建出来（返回再进 / resize 重建），
 * 不落地就白跑一趟（S2C 缓存铁律）。界面每帧直接读这里，包一到下一帧就画出来。
 *
 * 与 [PointsShopCache]（充值档位 / 订单）**分开两套**：那是「买 RP」那条线的数据，
 * 生命周期与界面都不同，混在一起迟早互相踩。
 */
object PointMarketCache {

    /** 当前页的格子（格子序号 → 商品）。**只含已上架的格子**，空格子不入表（数据层如此） */
    var slots: Map<Int, ShopEntry> = emptyMap()
        private set

    /** 快照对应的页号（0 起） */
    var page: Int = 0
        private set

    /** 总页数（服务端算的，至少 1） */
    var totalPages: Int = 1
        private set

    /**
     * 这份快照属于哪个 tab（[ShopEntry.KIND_POKEMON] / [ShopEntry.KIND_ITEM]）。
     *
     * ⚠ 精灵与物品**各占一套独立的格子编号**（2026-09-25 起），所以切 tab 必须**清缓存再请求** ——
     * 否则新快照到达前，界面会拿上一个 tab 的数据去画（格子序号还恰好对得上，看着像"没切过去"）。
     */
    var kind: String = ShopEntry.KIND_POKEMON
        private set

    /** 收到过至少一次快照（区分「还没拉到」与「拉到了但这一页是空的」） */
    var loaded: Boolean = false
        private set

    fun accept(payload: PointMarketItemsPayload) {
        slots = payload.slots.associate { it.index to it.entry }
        page = payload.page
        totalPages = payload.totalPages
        kind = payload.kind
        loaded = true
    }

    fun clear() {
        slots = emptyMap()
        page = 0
        totalPages = 1
        loaded = false
    }

}
