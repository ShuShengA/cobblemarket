package com.shusheng.cobblemarket.currency.rewards

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

/**
 * **RP 奖励规则的玩家进度**（`cobblemarket_points_rewards.dat`）。
 *
 * 两样东西：
 * - [rewardHits]：`玩家 → (物种@规则指纹) → 已发次数` —— `once` / `decay` 的递减依据
 * - [rewardDaily]：`玩家 → 事件 → [世界日, 当日累计]` —— `daily_cap` 的依据
 *
 * ⚠⚠ **为什么从 `PointsState` 拆出来**（2026-10-17 用户提出）：
 * 原先两者和**余额**挤在同一个 `cobblemarket_points.dat` 里 ⇒ 服主想"重置所有人的奖励进度"
 * （让首抓重新按第一档发）就**只能删整个文件**，玩家的 RP 余额也跟着没了。
 * 拆开后：**删本文件 = 只重置进度**，余额在 `PointsState` 里分文不动；另外还提供
 * `/market points rewards reset all|<玩家>` 命令（不停服、可单人重置）。
 *
 * ⚠ **不写"读不到新键就回退旧文件"的迁移分支**（1.3.0 未发布，按项目铁律不兼容未发布存档）：
 * 一旦回退，删掉本文件后旧进度会从 `cobblemarket_points.dat` 里"复活"，恰好破坏重置语义。
 * 旧文件里的 `rewardHits` / `rewardDaily` 不再被读取，下次保存时自然消失。
 *
 * ⚠ 文件名以 `cobblemarket` 开头 ⇒ `PersistHelper` / `StateBackup` 自动接管备份、恢复与 mtime 校验。
 * ⚠ 与 `PointsState` 一样挂在 `server.overworld.persistentStateManager` 上：同一存档内各维度共享，
 *   换存档是一份新进度（余额同理，所以不存在"换存档白拿首抓"的漏洞）。
 */
class RewardProgressState private constructor() : PersistentState() {

    /** 玩家 → 计数键（物种@规则指纹）→ 已发次数 */
    private val rewardHits = mutableMapOf<UUID, MutableMap<String, Int>>()

    /** 玩家 → 事件 key → [世界日, 当日累计 RP] */
    private val rewardDaily = mutableMapOf<UUID, MutableMap<String, LongArray>>()

    /** 玩家 → 规则指纹 → [世界日, 当天已发次数]（规则的 `daily_count` 用） */
    private val rewardDailyCount = mutableMapOf<UUID, MutableMap<String, LongArray>>()

    /** 这条规则对这个物种已经发过几次（0 = 还没发过） */
    fun hitCount(playerUuid: UUID, key: String): Int =
        rewardHits[playerUuid]?.get(key) ?: 0

    /** 记一次发放（**只有真的发出去了才调**，见 `PointsRewards.grant`） */
    fun recordHit(playerUuid: UUID, key: String) {
        val byPlayer = rewardHits.getOrPut(playerUuid) { mutableMapOf() }
        byPlayer[key] = (byPlayer[key] ?: 0) + 1
        markDirty()
    }

    /** 该玩家今天（事件 [eventKey]）已经累计发了多少 RP；跨天自动归零（**按世界时间**判） */
    fun dailyTotal(playerUuid: UUID, eventKey: String, worldDay: Long): Long {
        val entry = rewardDaily[playerUuid]?.get(eventKey) ?: return 0L
        return if (entry[0] == worldDay) entry[1] else 0L
    }

    /** 累加今日金额（跨天则重新开一天，旧值丢弃 —— 只需要判上限，不留历史） */
    fun addDaily(playerUuid: UUID, eventKey: String, worldDay: Long, amount: Long) {
        val byPlayer = rewardDaily.getOrPut(playerUuid) { mutableMapOf() }
        val entry = byPlayer[eventKey]
        if (entry == null || entry[0] != worldDay) {
            byPlayer[eventKey] = longArrayOf(worldDay, amount)
        } else {
            entry[1] += amount
        }
        markDirty()
    }

    /** 这条规则今天（[ruleKey]）已经发了几次；跨天自动归零（按世界时间） */
    fun dailyCount(playerUuid: UUID, ruleKey: String, worldDay: Long): Long {
        val entry = rewardDailyCount[playerUuid]?.get(ruleKey) ?: return 0L
        return if (entry[0] == worldDay) entry[1] else 0L
    }

    /** 记一次「该规则今天又发了一次」（只在真的发出去之后调） */
    fun addDailyCount(playerUuid: UUID, ruleKey: String, worldDay: Long, amount: Long) {
        val byPlayer = rewardDailyCount.getOrPut(playerUuid) { mutableMapOf() }
        val entry = byPlayer[ruleKey]
        if (entry == null || entry[0] != worldDay) {
            byPlayer[ruleKey] = longArrayOf(worldDay, amount)
        } else {
            entry[1] += amount
        }
        markDirty()
    }

    /**
     * **清空全服所有人的奖励进度**（已发次数 + 当天每日上限累计），返回受影响人数。
     *
     * ⚠ 只动本文件里的进度，**绝不碰 `PointsState` 的余额** —— 这正是拆分的意义。
     * ⚠ 当天累计也一起清：服主重置后玩家应当立刻按第一档拿，而不是被"今天已经领满"挡住。
     */
    fun resetAll(): Int {
        // 两个 map 的玩家集合取并集：有人可能只有每日累计、没有计数（例如同一事件反复领取后
        // 规则条件被改过、计数键换了），只清 hits 会留下"今天已领满"的残留
        val affected = (rewardHits.keys + rewardDaily.keys + rewardDailyCount.keys).toSet().size
        if (affected == 0) return 0
        rewardHits.clear()
        rewardDaily.clear()
        rewardDailyCount.clear()
        markDirty()
        return affected
    }

    /** 清空**某一个人**的奖励进度，返回是否真的清掉了什么（在线 / 离线玩家都可以） */
    fun resetPlayer(playerUuid: UUID): Boolean {
        val hadHits = rewardHits.remove(playerUuid) != null
        val hadDaily = rewardDaily.remove(playerUuid) != null
        val hadCount = rewardDailyCount.remove(playerUuid) != null
        if (!hadHits && !hadDaily && !hadCount) return false
        markDirty()
        return true
    }

    /**
     * **只清某一类事件**的奖励进度（全服），返回受影响人数。
     *
     * 计数键的格式是 `<物种 id>@<事件名>|<条件指纹>`（见 `RewardRule.fingerprint`）——
     * 事件名就在前缀里；每日上限本来就按事件 key 存在 [rewardDaily] 里。
     * ⇒ 两边都能精确过滤；**其它事件（捕捉 / 战斗 / 孵化…）的进度与余额都不动**。
     */
    fun resetEventAll(eventKey: String): Int {
        val affected = mutableSetOf<UUID>()
        val hitIt = rewardHits.entries.iterator()
        while (hitIt.hasNext()) {
            val entry = hitIt.next()
            val keyIt = entry.value.entries.iterator()
            while (keyIt.hasNext()) {
                if (eventOfHitKey(keyIt.next().key) == eventKey) {
                    keyIt.remove()
                    affected += entry.key
                }
            }
            if (entry.value.isEmpty()) hitIt.remove()
        }
        val dailyIt = rewardDaily.entries.iterator()
        while (dailyIt.hasNext()) {
            val entry = dailyIt.next()
            if (entry.value.remove(eventKey) != null) affected += entry.key
            if (entry.value.isEmpty()) dailyIt.remove()
        }
        // daily_count 的 key 是规则指纹（以事件名开头），按前缀清
        val countIt = rewardDailyCount.entries.iterator()
        while (countIt.hasNext()) {
            val entry = countIt.next()
            val keyIt = entry.value.entries.iterator()
            while (keyIt.hasNext()) {
                if (eventOfFingerprint(keyIt.next().key) == eventKey) {
                    keyIt.remove()
                    affected += entry.key
                }
            }
            if (entry.value.isEmpty()) countIt.remove()
        }
        if (affected.isNotEmpty()) markDirty()
        return affected.size
    }

    /** 只清**某一个人的某一类事件**，返回是否真的清掉了什么 */
    fun resetEventPlayer(playerUuid: UUID, eventKey: String): Boolean {
        var changed = false
        rewardHits[playerUuid]?.let { byKey ->
            val keyIt = byKey.entries.iterator()
            while (keyIt.hasNext()) {
                if (eventOfHitKey(keyIt.next().key) == eventKey) {
                    keyIt.remove()
                    changed = true
                }
            }
            if (byKey.isEmpty()) rewardHits.remove(playerUuid)
        }
        rewardDaily[playerUuid]?.let { byEvent ->
            if (byEvent.remove(eventKey) != null) changed = true
            if (byEvent.isEmpty()) rewardDaily.remove(playerUuid)
        }
        rewardDailyCount[playerUuid]?.let { byKey ->
            val keyIt = byKey.entries.iterator()
            while (keyIt.hasNext()) {
                if (eventOfFingerprint(keyIt.next().key) == eventKey) {
                    keyIt.remove()
                    changed = true
                }
            }
            if (byKey.isEmpty()) rewardDailyCount.remove(playerUuid)
        }
        if (changed) markDirty()
        return changed
    }

    /** 计数键里的「事件名」——格式：`<物种 id>@<事件名>|<条件指纹>`（物种 id 不含 `@`） */
    private fun eventOfHitKey(key: String): String? {
        val at = key.indexOf('@')
        if (at < 0) return null
        val fp = key.substring(at + 1)
        val bar = fp.indexOf('|')
        return if (bar <= 0) null else fp.substring(0, bar)
    }

    /** 规则指纹里的「事件名」——格式：`<事件名>|...` */
    private fun eventOfFingerprint(fp: String): String = fp.substringBefore('|')

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        // 空表不写 —— 不用的服主的存档里不会多出空 compound
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
        if (rewardDailyCount.isNotEmpty()) {
            val countRoot = NbtCompound()
            rewardDailyCount.forEach { (uuid, byKey) ->
                val playerNode = NbtCompound()
                byKey.forEach { (ruleKey, pair) ->
                    val e = NbtCompound()
                    e.putLong("day", pair[0])
                    e.putLong("count", pair[1])
                    playerNode.put(ruleKey, e)
                }
                countRoot.put(uuid.toString(), playerNode)
            }
            nbt.put("rewardDailyCount", countRoot)
        }
        return nbt
    }

    companion object {
        private val TYPE = PersistentState.Type(
            { RewardProgressState() },
            { nbt, _ ->
                RewardProgressState().apply {
                    // 逐条容错：一个玩家的进度坏掉不该连累其他人（见 CLAUDE.md 的存档铁律）
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
                    runCatching { nbt.getCompound("rewardDailyCount") }.getOrNull()?.let { countRoot ->
                        countRoot.keys.forEach { uuidKey ->
                            try {
                                val uuid = UUID.fromString(uuidKey)
                                val node = countRoot.getCompound(uuidKey)
                                val byKey = mutableMapOf<String, LongArray>()
                                node.keys.forEach { k ->
                                    val e = node.getCompound(k)
                                    byKey[k] = longArrayOf(e.getLong("day"), e.getLong("count"))
                                }
                                if (byKey.isNotEmpty()) rewardDailyCount[uuid] = byKey
                            } catch (e: Exception) {
                                CobbleMarket.LOGGER.warn("Skipping corrupted reward daily count for {}: {}", uuidKey, e.message)
                            }
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): RewardProgressState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_points_rewards")
    }
}
