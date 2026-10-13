package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

/**
 * **RP 余额账本**（`cobblemarket_points.dat`）。
 *
 * 与主货币的关键差别（2026-09-15 拍板，见 `docs/points_plan.md`）：
 * - RP 交易收入**直接到账**，不走主货币那套「待领余额」—— 主货币要"待领"是因为它可能是物品
 *   （背包满了发不出去、只能记账等玩家来领），RP 是模组自存的纯数字，按 UUID 入账即可、离线也能入。
 *   好处是 `MarketState` 一行都不用改，也不存在「待领余额币种搞错 = 凭空造钱」那一整类风险。
 * - RP**不可兑换、不可转让、完全不参与金融**（不存取款生息、不还贷、不买卡）。
 *
 * 文件名前缀带 `cobblemarket` ⇒ 自动进 `PersistHelper` / `StateBackup` 的备份、恢复与 mtime 校验，无需注册。
 */
class PointsState private constructor() : PersistentState() {

    /** 玩家 → 余额。**不预建条目**（没记录 = 0），避免空账号把存档撑大 */
    private val balances = mutableMapOf<UUID, Long>()

    fun getBalance(playerUuid: UUID): Long = balances[playerUuid] ?: 0L

    /**
     * 加余额，返回加完后的值。
     * 溢出钳 `Long.MAX_VALUE` 并 warn —— RP 是花真钱买的，宁可钳住也**不能回绕成负数**
     * （那等于把玩家的钱变成负债）。
     */
    fun addBalance(playerUuid: UUID, amount: Long): Long {
        if (amount <= 0) return getBalance(playerUuid)
        val current = balances[playerUuid] ?: 0L
        val updated = if (current > Long.MAX_VALUE - amount) {
            CobbleMarket.LOGGER.warn("Points balance overflow for {}: {} + {}", playerUuid, current, amount)
            Long.MAX_VALUE
        } else {
            current + amount
        }
        balances[playerUuid] = updated
        markDirty()
        return updated
    }

    /** 扣余额：**不足一律 false 且分文不动**（绝不部分扣 —— 部分扣会让"扣款成功"与账目对不上） */
    fun removeBalance(playerUuid: UUID, amount: Long): Boolean {
        if (amount <= 0) return false
        val current = balances[playerUuid] ?: 0L
        if (current < amount) return false
        balances[playerUuid] = current - amount
        markDirty()
        return true
    }

    /** 直接设定余额（管理命令用）；0 直接删条目，负数钳 0 */
    fun setBalance(playerUuid: UUID, amount: Long) {
        val value = amount.coerceAtLeast(0L)
        if (value == 0L) balances.remove(playerUuid) else balances[playerUuid] = value
        markDirty()
    }

    /** 有余额的玩家数（管理端统计用） */
    fun holderCount(): Int = balances.size

    // ── RP 奖励规则的进度（去重计数 + 每日累计） ──
    //
    // ⚠⚠ **为什么存在这里而不是另开一个 `PersistentState`**（2026-10-13 改）：
    //   奖励进度必须**跟玩家走、跨世界共享** —— 否则玩家换一个存档就能把同一只精灵的
    //   "首抓"再吃一次（对"产出货币"的系统来说那是能刷的漏洞，不是特性）。
    //   本文件本来就是"跟玩家走的 RP 数据"（余额），进度放在同一份里语义一致。
    //   ⚠ 当初另开过 `cobblemarket_points_rewards.dat`（世界存档内）⇒ 实测就撞上了跨世界重复发。
    //
    // ⚠ 结构是**嵌套的**（`玩家 → 计数键 → 次数`），所以直接存成 NBT compound，
    //   不像余额那样摊成一条条列表项。**新键、只增不改** ⇒ 旧档读不到 = 没有进度 = 都算首次。

    /** 玩家 → 计数键（物种@规则指纹）→ 已发次数 */
    private val rewardHits = mutableMapOf<UUID, MutableMap<String, Int>>()

    /** 玩家 → 事件 key → [世界日, 当日累计 RP] */
    private val rewardDaily = mutableMapOf<UUID, MutableMap<String, LongArray>>()

    /** 这条规则对这个物种已经发过几次（0 = 还没发过） */
    fun rewardHitCount(playerUuid: UUID, key: String): Int =
        rewardHits[playerUuid]?.get(key) ?: 0

    /** 记一次发放（**只有真的发出去了才调**，见 `PointsRewards.grant`） */
    fun recordRewardHit(playerUuid: UUID, key: String) {
        val byPlayer = rewardHits.getOrPut(playerUuid) { mutableMapOf() }
        byPlayer[key] = (byPlayer[key] ?: 0) + 1
        markDirty()
    }

    /** 该玩家今天（事件 [eventKey]）已经累计发了多少 RP；跨天自动归零（**按世界时间**判） */
    fun rewardDailyTotal(playerUuid: UUID, eventKey: String, worldDay: Long): Long {
        val entry = rewardDaily[playerUuid]?.get(eventKey) ?: return 0L
        return if (entry[0] == worldDay) entry[1] else 0L
    }

    /** 累加今日金额（跨天则重新开一天，旧值丢弃 —— 只需要判上限，不留历史） */
    fun addRewardDaily(playerUuid: UUID, eventKey: String, worldDay: Long, amount: Long) {
        val byPlayer = rewardDaily.getOrPut(playerUuid) { mutableMapOf() }
        val entry = byPlayer[eventKey]
        if (entry == null || entry[0] != worldDay) {
            byPlayer[eventKey] = longArrayOf(worldDay, amount)
        } else {
            entry[1] += amount
        }
        markDirty()
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        balances.forEach { (uuid, amount) ->
            val entry = NbtCompound()
            entry.putUuid("uuid", uuid)
            entry.putLong("amount", amount)
            list.add(entry)
        }
        nbt.put("balances", list)

        // RP 奖励进度（两个新键；空表不写 —— 不用的服主的存档里不会多出空 compound）
        if (rewardHits.isNotEmpty()) {
            val hitsRoot = NbtCompound()
            rewardHits.forEach { (uuid, byKey) ->
                val playerNode = NbtCompound()
                byKey.forEach { (key, count) -> playerNode.putInt(key, count) }
                hitsRoot.put(uuid.toString(), playerNode)
            }
            nbt.put("rewardHits", hitsRoot)
        }
        if (rewardDaily.isNotEmpty()) {
            val dailyRoot = NbtCompound()
            rewardDaily.forEach { (uuid, byEvent) ->
                val playerNode = NbtCompound()
                byEvent.forEach { (eventKey, pair) ->
                    val e = NbtCompound()
                    e.putLong("day", pair[0])
                    e.putLong("total", pair[1])
                    playerNode.put(eventKey, e)
                }
                dailyRoot.put(uuid.toString(), playerNode)
            }
            nbt.put("rewardDaily", dailyRoot)
        }
        return nbt
    }

    companion object {
        private val TYPE = PersistentState.Type(
            { PointsState() },
            { nbt, _ ->
                PointsState().apply {
                    // 逐条容错：单条损坏只跳过它 + warn，绝不整体抛异常（见 CLAUDE.md 的存档铁律）
                    nbt.getList("balances", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val entry = element as NbtCompound
                            val uuid = entry.getUuid("uuid")
                            val amount = entry.getLong("amount")
                            // 只收正余额：0 与负数没有意义（负数说明存档被改过，不认）
                            if (amount > 0) balances[uuid] = amount
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted points entry: {}", e.message)
                        }
                    }
                    // RP 奖励进度（新键；旧档没有 ⇒ 空表 = 都算首次，**不需要迁移分支**）
                    // ⚠ 逐玩家 try/catch：一个玩家的进度坏掉不该连累其他人（存档铁律）
                    runCatching { nbt.getCompound("rewardHits") }.getOrNull()?.let { hitsRoot ->
                        hitsRoot.keys.forEach { uuidKey ->
                            try {
                                val uuid = UUID.fromString(uuidKey)
                                val node = hitsRoot.getCompound(uuidKey)
                                val byKey = mutableMapOf<String, Int>()
                                node.keys.forEach { k -> byKey[k] = node.getInt(k) }
                                if (byKey.isNotEmpty()) rewardHits[uuid] = byKey
                            } catch (e: Exception) {
                                CobbleMarket.LOGGER.warn("Skipping corrupted reward hits for {}: {}", uuidKey, e.message)
                            }
                        }
                    }
                    runCatching { nbt.getCompound("rewardDaily") }.getOrNull()?.let { dailyRoot ->
                        dailyRoot.keys.forEach { uuidKey ->
                            try {
                                val uuid = UUID.fromString(uuidKey)
                                val node = dailyRoot.getCompound(uuidKey)
                                val byEvent = mutableMapOf<String, LongArray>()
                                node.keys.forEach { k ->
                                    val e = node.getCompound(k)
                                    byEvent[k] = longArrayOf(e.getLong("day"), e.getLong("total"))
                                }
                                if (byEvent.isNotEmpty()) rewardDaily[uuid] = byEvent
                            } catch (e: Exception) {
                                CobbleMarket.LOGGER.warn("Skipping corrupted reward daily for {}: {}", uuidKey, e.message)
                            }
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): PointsState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_points")
    }
}
