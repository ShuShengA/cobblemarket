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

    /**
     * 全部余额的**只读副本**（[PointsRankingExport] 导出余额排行、`/market points top` 用）。
     * ⚠ 返回副本而不是 map 本身：调用方拿不到内部表、写不进来 —— 余额只能经上面那三个方法改。
     */
    fun allBalances(): Map<UUID, Long> = balances.toMap()

    // RP 奖励规则的进度（去重计数 + 每日累计）已拆到 `RewardProgressState` —— 见那个类顶部的 KDoc：
    // 与余额分开后，服主重置奖励进度不必连玩家的 RP 余额一起删。

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        balances.forEach { (uuid, amount) ->
            val entry = NbtCompound()
            entry.putUuid("uuid", uuid)
            entry.putLong("amount", amount)
            list.add(entry)
        }
        nbt.put("balances", list)
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
                }
            },
            null
        )

        fun get(server: MinecraftServer): PointsState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_points")
    }
}
