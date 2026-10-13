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

    // ── 配方快照（服主用；只有持 `points.shop` 的人会收到）──

    /** 快照列表（服务端下发；存 / 删之后服务端会回发最新的一份） */
    var snapshots: List<com.shusheng.cobblemarket.network.ShopSnapshotInfo> = emptyList()
        private set

    /** 收到过至少一次列表（区分「还没拉到」与「拉到了但是空的」） */
    var snapshotsLoaded: Boolean = false
        private set

    fun acceptSnapshots(payload: com.shusheng.cobblemarket.network.ShopSnapshotsPayload) {
        snapshots = payload.snapshots
        snapshotsLoaded = true
    }

    // ── 形态候选表（species feature，编辑窗的「形态」列表用）──

    /**
     * `物种资源 id → aspect 串列表`（服务端下发，见 `SpeciesText.allFeatureAspects`）。
     *
     * ⚠ 空表只是**还没到**，不是错误：编辑窗照常只列 forms。同理**别期望客户端能自己算**
     * —— choice 型 provider 不同步给客户端（原委见 `SpeciesText.selectableFormAspects`）。
     */
    var featureAspects: Map<String, List<com.shusheng.cobblemarket.util.FeatureCandidates>> = emptyMap()
        private set

    /**
     * **收到过至少一次**候选表 —— 区分「还没到」与「服务端就是没有」。
     *
     * ⚠ 这个标志**必须判**（[PointsMarketScreen] 的形态合法性校验靠它）：表在飞的那几百毫秒里
     *   候选只剩 forms，feature 型（花纹 / 帽子 / mega）会被当成非法**当场清掉** ——
     *   而快照回填正是「回填 → 紧跟一条 normalize」的路子，赶上了就把服主存好的花纹吃了。
     *   宁可**晚判**（等表到了再收）也不能误清。
     */
    var featureAspectsLoaded: Boolean = false
        private set

    /**
     * 候选表的**版本号**，每收到一次自增。
     *
     * ⚠ 界面靠它判断"要不要重建列表"：候选是**异步**回来的（打开列表时才请求），
     *   而列表**一旦建好就不会自己跟着缓存变**。没有这个的话，`先开列表、包后到` 的界面
     *   会一直停在**空列表**上（2026-09-27 用户实测：同一个候选，黑名单里空的、价格限制里有内容 ——
     *   差别只是"打开时包到了没有"）。
     */
    var featureAspectsVersion: Int = 0
        private set

    fun acceptFeatureAspects(payload: com.shusheng.cobblemarket.network.SpeciesFeatureAspectsPayload) {
        featureAspects = payload.bySpecies
        featureAspectsLoaded = true
        featureAspectsVersion++
    }

    // ── 完整招式表（六桶；编辑窗的「技能」候选与筛选按钮用）──

    /**
     * `物种资源 id → 完整招式表（六桶）` —— 服务端按需下发（见 `SpeciesMovesPayload`）。
     *
     * ⚠ **按物种缓存**（不是整表）：一个会话里同一个物种只请求一次，省往返也避开服务端节流。
     * ⚠ **查不到 ≠ 出错**：客户端自己那份表（三桶）照常能用，只是少了教学 / 形态专属 / 进化招
     *   —— 界面走本地兜底、不做任何等待（见 `PointsMarketScreen.legalMoveIds`）。
     */
    var speciesMoves: Map<String, com.shusheng.cobblemarket.network.SpeciesMovesPayload> = emptyMap()
        private set

    /**
     * 收到过几份招式表（每收到一份自增）。
     *
     * ⚠ 与 [featureAspectsVersion] 同一个用途：候选是**异步**回来的，而列表**一旦建好就不会自己
     *   跟着缓存变** ⇒ 界面靠它把**正开着**的技能列表重建一次（不然"先开列表、包后到"会一直停在
     *   本地三桶那份上，教学招永远不出现）。
     */
    var speciesMovesVersion: Int = 0
        private set

    fun acceptSpeciesMoves(payload: com.shusheng.cobblemarket.network.SpeciesMovesPayload) {
        speciesMoves = speciesMoves + (payload.speciesId to payload)
        speciesMovesVersion++
    }

}
