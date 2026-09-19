package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

/**
 * 一条**玩家维度**的兑换记录（「我的兑换记录」界面用：时间 / 面额 / 到账后余额）。
 *
 * ⚠ 与 [PointRedemption] 不是一回事，别合并：那个挂在**码**上（服主查「这张码被谁用了」），
 * 码用完/过期会被 `purgeExpired` 清掉、**外部签名码更是压根不进码表** ——
 * 玩家想查「我兑过什么、到账没有」时，那两份数据都指望不上，所以另存一份按玩家归集的。
 */
data class PointRecord(val amount: Long, val balanceAfter: Long, val at: Long)

/**
 * **玩家兑换记录账本**（`cobblemarket_point_records.dat`）。
 *
 * 写入点在 [PointRedeemService] 兑换成功之后（本地码与外部码两条路都记）——
 * 崩在写入之前最坏只丢一条**显示用**记录，钱与次数早已落盘，不存在对不上账的情况。
 *
 * 公开给玩家的只有**他自己的**那几条（`RequestPointRecordsPayload` 按 uuid 取，不看别人的），
 * 服务端对账仍以 [PointsAuditLogger] 的 CSV 为准 —— 这里只是「玩家自己看得见」的那一份。
 */
class PointRecordState private constructor() : PersistentState() {

    /** 玩家 → 他的记录（**按时间升序**，末尾最新）。每人上限 [MAX_PER_PLAYER]，超出丢最旧的 */
    private val records = mutableMapOf<UUID, MutableList<PointRecord>>()

    /** 全部记录条数（[pruneOldest] 的水位线判据；别用 records 各表长度现算，那个是 O(玩家数)） */
    private var total = 0

    /**
     * 记一笔（兑换成功后调）。
     *
     * ⚠ 顺序：**扣次数 → 加余额 → 记这里**。本函数永远最后跑，所以它抛异常/没写进去都不会
     * 影响玩家的钱与次数（调用方另有 try/catch 兜底）。
     */
    fun append(playerUuid: UUID, record: PointRecord) {
        add(playerUuid, record)
        markDirty()
    }

    /** 真正放进表里（含两个上限的兜底）。**不打脏标志** —— 读档时也走它，那时候没什么可保存的 */
    private fun add(playerUuid: UUID, record: PointRecord) {
        val list = records.getOrPut(playerUuid) { mutableListOf() }
        list.add(record)
        total++
        // 每人上限：超出丢最旧的（界面一屏放不下更多，也给存档体积定死）
        while (list.size > MAX_PER_PLAYER) {
            list.removeAt(0)
            total--
        }
        // 全局兜底水位线（见 MAX_TOTAL 注释）
        if (total > MAX_TOTAL) pruneOldest()
    }

    /** 某个玩家的记录，**最新在前**（界面直接用，不用自己倒） */
    fun recent(playerUuid: UUID): List<PointRecord> =
        records[playerUuid]?.asReversed()?.toList() ?: emptyList()

    /**
     * 全局上限兜底：把**全局最旧**的记录成批砍到低水位线。
     *
     * 为什么要有它：每人 50 条是「单人不会撑爆」，但**玩家数 × 50** 没有上界 ——
     * 大服跑几年，光这一份 .dat 就能涨到几十 MB、每次自动保存都全量重写。
     * 触发概率极低（要攒够 [MAX_TOTAL] 条），一旦触发就一次砍掉一批，之后很久不会再触发
     * （每次调用只做一次 O(玩家数) 的取头比较，不排序）。
     */
    private fun pruneOldest() {
        val target = MAX_TOTAL - PRUNE_BATCH
        while (total > target) {
            var oldestUuid: UUID? = null
            var oldestAt = Long.MAX_VALUE
            // 每个玩家的表都是时间升序 → 表头就是他最早的一条；取全局最早的那个表头
            records.forEach { (uuid, list) ->
                val head = list.firstOrNull() ?: return@forEach
                if (head.at < oldestAt) {
                    oldestAt = head.at
                    oldestUuid = uuid
                }
            }
            val uuid = oldestUuid ?: break
            val list = records[uuid] ?: break
            list.removeAt(0)
            total--
            if (list.isEmpty()) records.remove(uuid)
        }
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        records.forEach { (uuid, entries) ->
            entries.forEach { r ->
                val c = NbtCompound()
                c.putUuid("uuid", uuid)
                c.putLong("amount", r.amount)
                c.putLong("balanceAfter", r.balanceAfter)
                c.putLong("at", r.at)
                list.add(c)
            }
        }
        nbt.put("records", list)
        return nbt
    }

    companion object {
        /** 每人保留多少条（超出丢最旧的）—— 用户 2026-09-19 拍板 */
        const val MAX_PER_PLAYER = 50

        /** 全局条数水位线（见 [pruneOldest]）与每次砍掉的批量 */
        private const val MAX_TOTAL = 20_000
        private const val PRUNE_BATCH = 2_000

        private val TYPE = PersistentState.Type(
            { PointRecordState() },
            { nbt, _ ->
                PointRecordState().apply {
                    // 逐条容错：单条损坏只跳过它 + warn，绝不整体抛异常（见 CLAUDE.md 的存档铁律）
                    nbt.getList("records", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            add(
                                c.getUuid("uuid"),
                                PointRecord(c.getLong("amount"), c.getLong("balanceAfter"), c.getLong("at"))
                            )
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted point record: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): PointRecordState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_point_records")
    }
}
