package com.shusheng.cobblemarket.overview

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

/**
 * 全服玩家总览的**离线快照**（每人一份：主货币余额 + 图鉴两个数）。
 *
 * **为什么需要它**（2026-10-15 定，追源码验证过）：
 * - **主货币余额**（`CurrencyHandler.getBalance`）只收**在线玩家对象** ——
 *   三种后端（CE / CobbleDollars / 物品本位）里只有 CE 能按 UUID 查，其余两种离线根本读不到
 * - **图鉴数**虽然能离线读（走磁盘上的玩家数据 json），但每查一次 = 读一次磁盘 + 解析整份 json，
 *   一次算全服会把主线程卡住
 *
 * 而**玩家一下线，他的主货币余额在这个模组里就再也不会动了**（能动钱的两个接口都收在线玩家对象、
 * 定时划扣也只扫在线的人）⇒ 下线那一刻记的数，就是他离线期间的真实值。
 *
 * **三条规则**（见方案第四节）：
 * 1. 正常下线 → 记一次（`onPlayerDisconnect`）
 * 2. 每 5 分钟 → 给**在线的**刷一遍（纯兜服务端崩溃 / 被强杀 —— 那种情况没有下线事件）
 * 3. 打开界面时：**在线的实时查** / **离线的读这里**
 *
 * ⚠ **独立的新存档文件**（`cobblemarket_overview`）：旧档没有它 = 空快照，不涉及任何迁移
 * （老存档升级后第一次打开总览，离线玩家那列是「—」，等大家上过线 / 下过线才慢慢填满）。
 */
class PlayerSnapshotState private constructor() : PersistentState() {

    /** 一名玩家的一次记录 */
    data class Snapshot(
        /** 主货币余额（记的是当时 `CurrencyHandler.getBalance` 的值） */
        val balance: Long,
        val dexSeen: Int,
        val dexCaught: Int,
        /** 记录时刻（毫秒时间戳）—— 界面据此给离线玩家标「离线时记录」 */
        val recordedAt: Long,
    )

    private val snapshots = mutableMapOf<UUID, Snapshot>()

    /** 取某人的快照；null = 从没记过（新玩家 / 老存档还没填） */
    fun snapshotOf(uuid: UUID): Snapshot? = snapshots[uuid]

    /** 快照里有记录的全部 uuid（总览列表的 uuid 来源之一） */
    fun allPlayers(): Set<UUID> = snapshots.keys

    /**
     * 记一笔。
     *
     * ⚠ **内容没变就不写**（比较时**排除时间戳**）：否则等于每 5 分钟把存档整个标脏一次、
     * 白增写盘 —— 同 `PointsRankingExport` 的"只写变化"。
     */
    fun record(uuid: UUID, balance: Long, dexSeen: Int, dexCaught: Int, now: Long) {
        val old = snapshots[uuid]
        if (old != null && old.balance == balance && old.dexSeen == dexSeen && old.dexCaught == dexCaught) return
        snapshots[uuid] = Snapshot(balance, dexSeen, dexCaught, now)
        markDirty()
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val map = NbtCompound()
        snapshots.forEach { (uuid, s) ->
            val entry = NbtCompound()
            entry.putLong("balance", s.balance)
            entry.putInt("dexSeen", s.dexSeen)
            entry.putInt("dexCaught", s.dexCaught)
            entry.putLong("recordedAt", s.recordedAt)
            map.put(uuid.toString(), entry)
        }
        nbt.put("snapshots", map)
        return nbt
    }

    companion object {
        /** 存档文件 key（新开一个，与其它 State 互不影响） */
        private const val KEY = "cobblemarket_overview"

        private val TYPE = PersistentState.Type(
            { PlayerSnapshotState() },
            { nbt, _ ->
                PlayerSnapshotState().apply {
                    val map = nbt.getCompound("snapshots")
                    map.keys.forEach { key ->
                        // 容错：单条坏只跳过该条并记日志，绝不让整份快照读取失败（铁律）
                        try {
                            val entry = map.getCompound(key)
                            snapshots[UUID.fromString(key)] = Snapshot(
                                balance = entry.getLong("balance"),
                                dexSeen = entry.getInt("dexSeen"),
                                dexCaught = entry.getInt("dexCaught"),
                                recordedAt = entry.getLong("recordedAt"),
                            )
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted overview snapshot for '{}': {}", key, e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): PlayerSnapshotState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, KEY)
    }
}
