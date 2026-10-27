package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

/**
 * **普通购买店的每玩家滚动冷却**（2026-10-25）。
 *
 * 两层，都是「按玩家、按成功购买时刻」记最后时间：
 * - **商店冷却**：`shopId|playerUuid` —— 玩家在这家店买成任意一格后，整店对该玩家进入冷却
 * - **格子冷却**：`shopId|playerUuid|entryId` —— 买成这一格后，只有这一格对该玩家进入冷却
 *
 * 实际生效值 = `max(商店冷却, 格子冷却)`（商店冷却是保底）；两者都为 0 = 不冷却。
 * 领取店不用这份状态（它有自己的 [CustomShopClaimState] 周期）。
 *
 * ⚠ **新存档文件**（`cobblemarket_custom_shop_cooldowns.dat`）：旧档没有它就是空表，
 * 不动任何已有存档结构。读取逐条容错：坏条目跳过 + warn，绝不整体抛。
 */
class CustomShopCooldownState private constructor() : PersistentState() {

    /** `shopId|uuid` → 最后一次买成的时间（epoch ms）；0 / 缺失 = 没有记录 */
    private val shopPurchases = mutableMapOf<String, Long>()

    /** `shopId|uuid|entryId` → 最后一次买成这一格的时间（epoch ms） */
    private val slotPurchases = mutableMapOf<String, Long>()

    // ── 查询 ──

    /** 商店冷却剩余毫秒；0 = 不在冷却 */
    fun shopRemaining(shopId: String, playerUuid: UUID, cooldownMs: Long, now: Long): Long =
        remaining(shopPurchases, shopKey(shopId, playerUuid), cooldownMs, now)

    /** 格子冷却剩余毫秒；0 = 不在冷却 */
    fun slotRemaining(shopId: String, playerUuid: UUID, entryId: String, cooldownMs: Long, now: Long): Long =
        remaining(slotPurchases, slotKey(shopId, playerUuid, entryId), cooldownMs, now)

    /** 商店冷却结束时刻（epoch ms）；0 = 不在冷却（状态包用） */
    fun shopUntil(shopId: String, playerUuid: UUID, cooldownMs: Long, now: Long): Long =
        untilOf(shopRemaining(shopId, playerUuid, cooldownMs, now), now)

    /** 格子冷却结束时刻（epoch ms）；0 = 不在冷却（状态包用） */
    fun slotUntil(shopId: String, playerUuid: UUID, entryId: String, cooldownMs: Long, now: Long): Long =
        untilOf(slotRemaining(shopId, playerUuid, entryId, cooldownMs, now), now)

    private fun untilOf(remainingMs: Long, now: Long): Long = if (remainingMs > 0) now + remainingMs else 0

    private fun remaining(
        map: MutableMap<String, Long>,
        key: String,
        cooldownMs: Long,
        now: Long
    ): Long {
        if (cooldownMs <= 0) return 0
        val last = map[key] ?: return 0
        if (last <= 0L) return 0
        // 服务器时钟回拨：只把起点顺延到「现在」、按满冷却重算 —— 绝不能让玩家靠改时间来跳过冷却
        if (now < last) {
            map[key] = now
            markDirty()
            return cooldownMs
        }
        return (last + cooldownMs - now).coerceAtLeast(0)
    }

    // ── 记录 ──

    /** 记一次**成功购买**（只有成交才调用；失败 / 取消不触发） */
    fun recordShopPurchase(shopId: String, playerUuid: UUID, now: Long) {
        shopPurchases[shopKey(shopId, playerUuid)] = now
        markDirty()
    }

    /** 记一次**成功购买这一格**（只有成交才调用） */
    fun recordSlotPurchase(shopId: String, playerUuid: UUID, entryId: String, now: Long) {
        slotPurchases[slotKey(shopId, playerUuid, entryId)] = now
        markDirty()
    }

    /** 商店被删除时清掉它的全部冷却记录（否则同 id 重建的店会继承旧冷却） */
    fun clearShop(shopId: String) {
        val prefix = "$shopId|"
        val removed = shopPurchases.keys.removeAll { it.startsWith(prefix) }
        val removedSlots = slotPurchases.keys.removeAll { it.startsWith(prefix) }
        if (removed || removedSlots) markDirty()
    }

    private fun shopKey(shopId: String, playerUuid: UUID): String = "$shopId|$playerUuid"
    private fun slotKey(shopId: String, playerUuid: UUID, entryId: String): String =
        "$shopId|$playerUuid|$entryId"

    // ── 存档 ──

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        // 超过最长冷却 + 1 小时的记录永远不可能再生效，直接清掉：文件不随玩家数无界增长
        val cutoff = System.currentTimeMillis() - (MAX_COOLDOWN_HOURS * 3_600_000L + 3_600_000L)
        shopPurchases.entries.removeAll { it.value in 1L..cutoff }
        slotPurchases.entries.removeAll { it.value in 1L..cutoff }
        nbt.put("shopPurchases", writeEntries(shopPurchases))
        nbt.put("slotPurchases", writeEntries(slotPurchases))
        return nbt
    }

    private fun writeEntries(map: Map<String, Long>): NbtList {
        val list = NbtList()
        map.forEach { (key, at) ->
            val c = NbtCompound()
            c.putString("key", key)
            c.putLong("at", at)
            list.add(c)
        }
        return list
    }

    private fun readNbt(nbt: NbtCompound) {
        readEntries(nbt, "shopPurchases", shopPurchases)
        readEntries(nbt, "slotPurchases", slotPurchases)
    }

    private fun readEntries(nbt: NbtCompound, key: String, out: MutableMap<String, Long>) {
        nbt.getList(key, NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
            try {
                val c = element as NbtCompound
                val rawKey = c.getString("key")
                if (rawKey.isEmpty()) return@forEach
                // 负数 / 0 = 脏数据，跳过（按「没有记录」处理，不会误伤玩家）
                val at = if (c.contains("at")) c.getLong("at") else 0L
                if (at <= 0L) return@forEach
                out[rawKey] = at
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Skipping corrupted custom shop cooldown entry: {}", e.message)
            }
        }
    }

    companion object {
        private val TYPE = PersistentState.Type(
            { CustomShopCooldownState() },
            { nbt, _ -> CustomShopCooldownState().apply { readNbt(nbt) } },
            null
        )

        fun get(server: MinecraftServer): CustomShopCooldownState =
            server.overworld.persistentStateManager.getOrCreate(
                TYPE, "${CobbleMarket.MOD_ID}_custom_shop_cooldowns"
            )
    }
}
