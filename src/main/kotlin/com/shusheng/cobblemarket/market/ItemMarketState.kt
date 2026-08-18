package com.shusheng.cobblemarket.market

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.world.PersistentState
import java.util.UUID


class ItemMarketState private constructor() : PersistentState() {

    private val listings = mutableMapOf<UUID, ItemListing>()
    private val pendingReturns = mutableMapOf<UUID, MutableList<ItemListing>>()

    fun addListing(listing: ItemListing) {
        listings[listing.id] = listing
        markDirty()
    }

    fun removeListing(id: UUID): ItemListing? {
        val removed = listings.remove(id)
        if (removed != null) markDirty()
        return removed
    }

    fun getListing(id: UUID): ItemListing? = listings[id]
    fun getAllListings(): List<ItemListing> = listings.values.toList()
    fun getActiveListings(): List<ItemListing> = listings.values.filter { it.isActive() }

    fun getListingsBySeller(sellerUuid: UUID): List<ItemListing> =
        listings.values.filter { it.sellerUuid == sellerUuid }

    fun countActiveBySeller(sellerUuid: UUID): Int =
        listings.values.count { it.sellerUuid == sellerUuid && it.isActive() }

    fun search(sortBy: SortMode = SortMode.NEWEST, sellerUuid: UUID? = null, sellerName: String? = null): List<ItemListing> {
        var results = getActiveListings()
        sellerUuid?.let { u -> results = results.filter { it.sellerUuid == u } }
        sellerName?.let { s -> results = results.filter { it.sellerName.contains(s, ignoreCase = true) } }
        return when (sortBy) {
            SortMode.PRICE_ASC -> results.sortedBy { it.price }
            SortMode.PRICE_DESC -> results.sortedByDescending { it.price }
            SortMode.LEVEL_ASC -> results.sortedBy { it.count }
            SortMode.LEVEL_DESC -> results.sortedByDescending { it.count }
            SortMode.NEWEST -> results.sortedByDescending { it.createdAt }
            SortMode.OLDEST -> results.sortedBy { it.createdAt }
        }
    }

    fun expireOldListings(currentTime: Long) {
        // 无节流：过期挂单应立即下架（15 秒节流窗口内过期挂单仍可被购买，语义不一致；
        // 全表 filter 极轻，请求触发频率也不高）
        val expired = listings.values.filter { it.isActive() && it.expiresAt <= currentTime }
        expired.forEach { listing ->
            listing.status = ListingStatus.EXPIRED
            listing.returnedAt = currentTime
            pendingReturns.getOrPut(listing.sellerUuid) { mutableListOf() }.add(listing)
        }
        cleanupOldReturns(currentTime)
        if (expired.isNotEmpty()) markDirty()
    }

    fun getPendingReturns(playerUuid: UUID): List<ItemListing> =
        pendingReturns[playerUuid] ?: emptyList()

    fun addPendingReturn(playerUuid: UUID, listing: ItemListing) {
        listing.returnedAt = System.currentTimeMillis()
        pendingReturns.getOrPut(playerUuid) { mutableListOf() }.add(listing)
        markDirty()
    }

    /** 按配置清理超期未领取的退回（配置 0 = 永不清理） */
    private fun cleanupOldReturns(currentTime: Long) {
        val retentionDays = com.shusheng.cobblemarket.config.CobbleMarketConfig.pendingReturnRetentionDays
        if (retentionDays <= 0) return
        val retentionMs = retentionDays * 24L * 60 * 60 * 1000
        var removedCount = 0
        pendingReturns.entries.toList().forEach { (uuid, returns) ->
            val kept = returns.filter { listing ->
                // 旧存档条目没有 returnedAt：用过期时间近似进入时间（误差 ≤ 节流窗口）
                val enteredAt = listing.returnedAt ?: listing.expiresAt
                if (currentTime - enteredAt > retentionMs) {
                    listings.remove(listing.id)
                    removedCount++
                    com.shusheng.cobblemarket.util.CleanupLogger.log(
                        category = "ITEM",
                        playerUuid = uuid,
                        listingId = listing.id,
                        detail = listing.itemId,
                        price = listing.price,
                        retainedDays = (currentTime - enteredAt) / 86_400_000L
                    )
                    false
                } else true
            }
            if (kept.size != returns.size) {
                if (kept.isEmpty()) pendingReturns.remove(uuid) else pendingReturns[uuid] = kept.toMutableList()
            }
        }
        if (removedCount > 0) {
            markDirty()
            CobbleMarket.LOGGER.warn(
                "Cleaned up {} unclaimed item returns after {} days retention",
                removedCount, retentionDays
            )
        }
    }

    fun claimReturns(player: ServerPlayerEntity): Int {
        val playerReturns = pendingReturns[player.uuid] ?: return 0
        val remaining = mutableListOf<ItemListing>()
        var returned = 0
        var changed = false
        playerReturns.forEach { listing ->
            var stack: ItemStack? = null
            try {
                val rebuilt = ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, listing.itemNbt)
                if (rebuilt.isEmpty) {
                    // 物品数据已失效（如 mod 被移除）：保留记录并告警，避免退回被吞掉
                    CobbleMarket.LOGGER.warn(
                        "Item return listing {} for player {} has invalid item data; keeping it in pending returns",
                        listing.id, player.uuid
                    )
                    remaining.add(listing)
                    return@forEach
                }
                rebuilt.count = listing.count
                stack = rebuilt
                // 能放几个放几个，只放 main（不碰副手/盔甲槽），放不下的剩余数量留下次领取
                insertIntoMain(player, rebuilt)
                if (rebuilt.isEmpty) {
                    returned++
                    changed = true
                    // 物品已全部归还，挂单生命周期终结，立即删除避免存档膨胀
                    listings.remove(listing.id)
                    try {
                        com.shusheng.cobblemarket.event.TransactionHistory.get(player.server).addRecord(
                            com.shusheng.cobblemarket.event.TransactionRecord(
                                timestamp = System.currentTimeMillis(),
                                type = com.shusheng.cobblemarket.event.TransactionType.RETURN,
                                category = com.shusheng.cobblemarket.event.TransactionCategory.ITEM,
                                sellerUuid = player.uuid,
                                sellerName = player.name.string,
                                buyerUuid = null,
                                buyerName = "",
                                species = listing.itemId,
                                price = listing.price,
                                fee = 0
                            )
                        )
                    } catch (e: Exception) {
                        // 记录失败只影响历史，不影响已归还的物品
                        CobbleMarket.LOGGER.warn("Failed to record item return for listing {}: {}", listing.id, e.message)
                    }
                } else {
                    // 部分领取：保留剩余数量，待下次领取，避免丢失。
                    listing.count = rebuilt.count
                    remaining.add(listing)
                    changed = true // count 已变化必须落盘，否则重启后恢复旧数量导致复制
                }
            } catch (e: Exception) {
                // 单条失败不阻塞其他退回；若已部分发放则按剩余量记账，避免复制
                CobbleMarket.LOGGER.warn("Failed to return item listing {}: {}", listing.id, e.message)
                val partial = stack
                if (partial != null && partial.isEmpty) {
                    // 物品已全部入背包、仅记账异常：按已领取处理
                    returned++
                    changed = true
                } else {
                    if (partial != null && partial.count != listing.count) {
                        listing.count = partial.count
                        changed = true
                    }
                    remaining.add(listing)
                }
            }
        }
        if (remaining.isEmpty()) {
            pendingReturns.remove(player.uuid)
        } else {
            pendingReturns[player.uuid] = remaining
        }
        if (changed) markDirty()
        return returned
    }

    /**
     * 只往 main（36 格）插入：先填已有同类堆叠，再填空槽。
     * 不碰副手/盔甲槽；返回后 stack.count 为未放入的剩余量。
     */
    private fun insertIntoMain(player: ServerPlayerEntity, stack: ItemStack) {
        val inv = player.inventory
        val main = inv.main
        for (i in 0 until main.size) {
            if (stack.isEmpty) return
            val slot = main[i]
            if (!slot.isEmpty && ItemStack.areItemsAndComponentsEqual(slot, stack)) {
                val space = slot.maxCount - slot.count
                if (space > 0) {
                    val r = minOf(space, stack.count)
                    inv.setStack(i, slot.copyWithCount(slot.count + r))
                    stack.decrement(r)
                }
            }
        }
        for (i in 0 until main.size) {
            if (stack.isEmpty) return
            if (main[i].isEmpty) {
                val r = minOf(stack.maxCount, stack.count)
                inv.setStack(i, stack.copyWithCount(r))
                stack.decrement(r)
            }
        }
    }

    fun markModified() = markDirty()

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        listings.values.forEach { listing -> list.add(listing.toNbt()) }
        nbt.put("listings", list)

        val returns = NbtCompound()
        pendingReturns.forEach { (uuid, returned) ->
            val rlist = NbtList()
            returned.forEach { rlist.add(it.toNbt()) }
            returns.put(uuid.toString(), rlist)
        }
        nbt.put("pendingReturns", returns)
        return nbt
    }

    companion object {
        private val TYPE = PersistentState.Type(
            { ItemMarketState() },
            { nbt, _ ->
                ItemMarketState().apply {
                    nbt.getList("listings", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val listing = ItemListing.fromNbt(element as NbtCompound)
                            listings[listing.id] = listing
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted item listing: {}", e.message)
                        }
                    }
                    val returns = nbt.getCompound("pendingReturns")
                    returns.keys.forEach { key ->
                        try {
                            val rlist = returns.getList(key, NbtList.COMPOUND_TYPE.toInt())
                            val mlist = mutableListOf<ItemListing>()
                            rlist.forEach { element ->
                                try {
                                    mlist.add(ItemListing.fromNbt(element as NbtCompound))
                                } catch (e: Exception) {
                                    CobbleMarket.LOGGER.warn("Skipping corrupted returned item for '{}': {}", key, e.message)
                                }
                            }
                            pendingReturns[UUID.fromString(key)] = mlist
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted item return entry '{}': {}", key, e.message)
                        }
                    }
                    // 迁移清理：终态且未被任何待领取列表引用的挂单是历史遗留孤儿数据，
                    // 继续保留只会让存档无限膨胀，读取时直接丢弃
                    val referenced = pendingReturns.values.flatten().map { it.id }.toSet()
                    val orphans = listings.keys.filter { id ->
                        val l = listings[id]!!
                        !l.isActive() && id !in referenced
                    }
                    orphans.forEach { listings.remove(it) }
                    if (orphans.isNotEmpty()) {
                        CobbleMarket.LOGGER.info("Cleaned up {} orphan item listings from old save data", orphans.size)
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): ItemMarketState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_items")
    }
}
