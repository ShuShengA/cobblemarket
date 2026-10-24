package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

/**
 * **定期领取店的每玩家进度**（2026-10-23）。
 *
 * 口径（用户 2026-10-23 拍板）：
 * - **滚动冷却**：玩家第一次领到东西时开始计时，满 [CustomShop.claimHours] 小时重置
 * - 每个玩家每格的可领数量 = 条目 `stock`（**每期每人**，不是全服库存）；领到多少记多少
 * - **不叠加**：过期未领的部分直接作废（重置时清空），不会和下一期累加
 * - 装不下的留在商店里（本期还能再领），不另开邮箱 —— 见 [PointMarketService.claim]
 *
 * 存档是**新文件**（`cobblemarket_custom_shop_claims.dat`）：旧档没有它就是空表。
 * 读取逐条容错：坏条目跳过 + warn，绝不整体抛。
 */
class CustomShopClaimState private constructor() : PersistentState() {

    /** 一个玩家在一家店的一个周期内的进度 */
    class Period(
        /** 本周期开始时间（epoch ms，第一次领到时写入）；0 = 还没有任何进度 */
        var startedAt: Long = 0L,
        /** `entryId → 已领数量` */
        internal val claimed: MutableMap<String, Int> = mutableMapOf()
    )

    /** `"shopId|uuid"` → 进度（只存有领取记录的；没进度 = 全新，可领满） */
    private val periods = mutableMapOf<String, Period>()

    /**
     * 取这个玩家在这家店的**当前周期**；已过期就原地开新周期（清空计数）。
     * ⚠ 服务器时钟**回拨**不算过期：只把周期起点顺延到「现在」、**保留**已领计数（见下方注释）。
     *
     * ⚠ 还没领过任何东西时返回的 [Period] **不会写进表里**（`startedAt = 0`）——
     *   只有真的领到东西才 [recordClaim]，避免"点一下没空间也占用了一个周期"。
     */
    fun periodFor(shopId: String, playerUuid: UUID, now: Long, periodMillis: Long): Period {
        val existing = periods[key(shopId, playerUuid)]
        if (existing == null) return Period()
        if (existing.startedAt <= 0L || now - existing.startedAt >= periodMillis) {
            // 过期：开新周期（把已领清零，未领的部分**不累积**）
            existing.startedAt = 0L
            existing.claimed.clear()
            markDirty()
        } else if (now < existing.startedAt) {
            // **服务器时钟回拨**（NTP 校正 / VM 快照回滚 / 管理员手改时间）：
            // 不能清空计数 —— 每个玩家下次打开商店都会走到这里 ⇒ 等于给**全服**白送一整期。
            // 只把周期起点顺延到「现在」，已领数量保留：最多多等一个周期，绝不会多领。
            // ⚠ 玩家客户端的时间从不参与判定，这条只有服务器自己的时钟能触发。
            existing.startedAt = now
            markDirty()
        }
        return existing
    }

    /** 剩余可领（[quota] 是条目配置的每期数量） */
    fun remaining(period: Period, entryId: String, quota: Int): Int =
        (quota - (period.claimed[entryId] ?: 0)).coerceAtLeast(0)

    /**
     * 记一次**实际交付成功**的领取。
     * [delivered] 必须 > 0 —— 没领到就不该开启周期。
     */
    fun recordClaim(
        shopId: String, playerUuid: UUID, period: Period, entryId: String, delivered: Int, now: Long
    ) {
        if (delivered <= 0) return
        val key = key(shopId, playerUuid)
        if (periods[key] !== period) periods[key] = period
        if (period.startedAt <= 0L) period.startedAt = now
        period.claimed[entryId] = (period.claimed[entryId] ?: 0) + delivered
        markDirty()
    }

    /** 距离下次刷新（epoch ms）；0 = 还没开始周期（现在就能领） */
    fun nextResetAt(shopId: String, playerUuid: UUID, periodMillis: Long): Long {
        val p = periods[key(shopId, playerUuid)] ?: return 0L
        if (p.startedAt <= 0L) return 0L
        return p.startedAt + periodMillis
    }

    private fun key(shopId: String, playerUuid: UUID): String = "$shopId|$playerUuid"

    // ── 存档 ──

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        // 一年没动过的周期直接清掉：文件不随玩家数无界增长（过期本来就等于"重新开始"）
        val cutoff = System.currentTimeMillis() - 370L * 24L * 3600L * 1000L
        periods.entries.removeAll { it.value.startedAt in 1L..cutoff }
        val list = NbtList()
        periods.forEach { (key, period) ->
            val c = NbtCompound()
            c.putString("key", key)
            c.putLong("startedAt", period.startedAt)
            val claimed = NbtList()
            period.claimed.forEach { (entryId, count) ->
                val e = NbtCompound()
                e.putString("entry", entryId)
                e.putInt("count", count)
                claimed.add(e)
            }
            c.put("claimed", claimed)
            list.add(c)
        }
        nbt.put("periods", list)
        return nbt
    }

    private fun readNbt(nbt: NbtCompound) {
        nbt.getList("periods", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
            try {
                val c = element as NbtCompound
                val rawKey = c.getString("key")
                if (rawKey.isEmpty()) return@forEach
                val period = Period(startedAt = if (c.contains("startedAt")) c.getLong("startedAt") else 0L)
                c.getList("claimed", NbtList.COMPOUND_TYPE.toInt()).forEach { ce ->
                    val e = ce as NbtCompound
                    val entry = e.getString("entry")
                    // 手改 / 损坏的存档可能写出负数：不钳制的话 remaining = quota − 负数 会**多发**
                    if (entry.isNotEmpty()) period.claimed[entry] = e.getInt("count").coerceAtLeast(0)
                }
                periods[rawKey] = period
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Skipping corrupted custom shop claim period: {}", e.message)
            }
        }
    }

    companion object {
        private val TYPE = PersistentState.Type(
            { CustomShopClaimState() },
            { nbt, _ -> CustomShopClaimState().apply { readNbt(nbt) } },
            null
        )

        fun get(server: MinecraftServer): CustomShopClaimState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_custom_shop_claims")
    }
}
