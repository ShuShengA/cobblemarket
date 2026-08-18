package com.shusheng.cobblemarket.market

import com.cobblemon.mod.common.Cobblemon
import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.world.PersistentState
import java.util.UUID


class MarketState private constructor() : PersistentState() {

    private val listings = mutableMapOf<UUID, MarketListing>()
    private val pendingBalances = mutableMapOf<UUID, Long>()
    private val pendingReturns = mutableMapOf<UUID, MutableList<MarketListing>>()

    fun addListing(listing: MarketListing) {
        listings[listing.id] = listing
        markDirty()
    }

    fun removeListing(id: UUID): MarketListing? {
        val removed = listings.remove(id)
        if (removed != null) markDirty()
        return removed
    }

    fun getListing(id: UUID): MarketListing? = listings[id]
    fun getAllListings(): List<MarketListing> = listings.values.toList()
    fun getActiveListings(): List<MarketListing> = listings.values.filter { it.isActive() }

    fun getListingsBySeller(sellerUuid: UUID): List<MarketListing> =
        listings.values.filter { it.sellerUuid == sellerUuid }

    fun search(
        species: String? = null,
        shiny: Boolean? = null,
        minLevel: Int? = null,
        maxLevel: Int? = null,
        minPrice: Int? = null,
        maxPrice: Int? = null,
        sortBy: SortMode = SortMode.PRICE_ASC,
        gender: String? = null,
        typeFilter: String? = null,
        minIvs: Map<String, Int> = emptyMap(),
        sellerUuid: UUID? = null,
        sellerName: String? = null
    ): List<MarketListing> {
        var results = getActiveListings()
        species?.let { s -> results = results.filter {
            it.species.contains(s, ignoreCase = true) || (it.extraData["speciesName"]?.contains(s, ignoreCase = true) == true)
        } }
        shiny?.let { s -> results = results.filter { it.shiny == s } }
        minLevel?.let { l -> results = results.filter { it.level >= l } }
        maxLevel?.let { l -> results = results.filter { it.level <= l } }
        minPrice?.let { p -> results = results.filter { it.price >= p } }
        maxPrice?.let { p -> results = results.filter { it.price <= p } }
        gender?.let { g -> results = results.filter { it.extraData["gender"]?.equals(g, ignoreCase = true) == true } }
        typeFilter?.let { t -> results = results.filter {
            (it.extraData["primaryType"]?.contains(t, ignoreCase = true) == true) ||
            (it.extraData["secondaryType"]?.contains(t, ignoreCase = true) == true)
        } }
        minIvs.forEach { (statName, value) ->
            results = results.filter { (it.extraData[statName]?.toIntOrNull() ?: 0) == value }
        }
        sellerUuid?.let { u -> results = results.filter { it.sellerUuid == u } }
        sellerName?.let { s -> results = results.filter { it.sellerName.contains(s, ignoreCase = true) } }
        return when (sortBy) {
            SortMode.PRICE_ASC -> results.sortedBy { it.price }
            SortMode.PRICE_DESC -> results.sortedByDescending { it.price }
            SortMode.LEVEL_ASC -> results.sortedBy { it.level }
            SortMode.LEVEL_DESC -> results.sortedByDescending { it.level }
            SortMode.NEWEST -> results.sortedByDescending { it.createdAt }
            SortMode.OLDEST -> results.sortedBy { it.createdAt }
        }
    }

    fun countActiveBySeller(sellerUuid: UUID): Int =
        listings.values.count { it.sellerUuid == sellerUuid && it.isActive() }

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

    fun getPendingReturns(playerUuid: UUID): List<MarketListing> =
        pendingReturns[playerUuid] ?: emptyList()

    fun addPendingReturn(playerUuid: UUID, listing: MarketListing) {
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
                        category = "POKEMON",
                        playerUuid = uuid,
                        listingId = listing.id,
                        detail = listing.species,
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
                "Cleaned up {} unclaimed pokemon returns after {} days retention",
                removedCount, retentionDays
            )
        }
    }

    fun claimReturns(player: ServerPlayerEntity): Int {
        val playerReturns = pendingReturns[player.uuid] ?: return 0
        val remaining = mutableListOf<MarketListing>()
        var returned = 0
        playerReturns.forEach { listing ->
            val success = try {
                returnPokemon(player, listing)
            } catch (e: Exception) {
                // 单条 NBT 损坏等异常不阻塞其他退款
                CobbleMarket.LOGGER.warn("Failed to return pokemon listing {} to {}: {}", listing.id, player.uuid, e.message)
                false
            }
            if (success) {
                returned++
                // 精灵已归还玩家，挂单生命周期终结，立即删除避免存档膨胀
                listings.remove(listing.id)
                try {
                    com.shusheng.cobblemarket.event.MarketEvents.RETURN.trigger(com.shusheng.cobblemarket.event.ReturnEvent(player.uuid, listing))
                } catch (e: Exception) {
                    // 事件订阅者异常只影响日志，精灵已归还，绝不重新入队（否则会重复发放）
                    CobbleMarket.LOGGER.warn("Return event handler failed for listing {}: {}", listing.id, e.message)
                }
            } else {
                remaining.add(listing)
            }
        }
        if (remaining.isEmpty()) {
            pendingReturns.remove(player.uuid)
        } else {
            pendingReturns[player.uuid] = remaining
        }
        if (returned > 0) markDirty()
        return returned
    }

    private fun returnPokemon(player: ServerPlayerEntity, listing: MarketListing): Boolean {
        val pokemon = com.cobblemon.mod.common.pokemon.Pokemon()
            .loadFromNBT(player.serverWorld.registryManager, listing.pokemonNbt)
        // Cobblemon 的 Party.add 在队伍满时自动转入 PC（溢出兜底），无需额外处理
        return Cobblemon.storage.getParty(player).add(pokemon)
    }

    fun markModified() = markDirty()

    fun addPendingBalance(playerUuid: UUID, amount: Long) {
        pendingBalances[playerUuid] = (pendingBalances[playerUuid] ?: 0L) + amount
        markDirty()
    }

    fun getPendingBalance(playerUuid: UUID): Long = pendingBalances[playerUuid] ?: 0L

    /** 领取至多 [upTo] 的余额（差额留在账本），返回实际领取量；单次调用内完成，避免调用方分两步补差。 */
    fun claimPendingBalance(playerUuid: UUID, upTo: Long): Long {
        val amount = pendingBalances[playerUuid] ?: return 0L
        val taken = minOf(amount, upTo)
        if (taken >= amount) {
            pendingBalances.remove(playerUuid)
        } else {
            pendingBalances[playerUuid] = amount - taken
        }
        if (taken > 0L) markDirty()
        return taken
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        listings.values.forEach { listing -> list.add(listing.toNbt()) }
        nbt.put("listings", list)

        val balances = NbtCompound()
        pendingBalances.forEach { (uuid, amount) -> balances.putLong(uuid.toString(), amount) }
        nbt.put("pendingBalances", balances)

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
            { MarketState() },
            { nbt, _ ->
                MarketState().apply {
                    nbt.getList("listings", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val listing = MarketListing.fromNbt(element as NbtCompound)
                            listings[listing.id] = listing
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted market listing: {}", e.message)
                        }
                    }
                    val balances = nbt.getCompound("pendingBalances")
                    balances.keys.forEach { key ->
                        try {
                            pendingBalances[UUID.fromString(key)] = balances.getLong(key)
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted pending balance for '{}': {}", key, e.message)
                        }
                    }
                    val returns = nbt.getCompound("pendingReturns")
                    returns.keys.forEach { key ->
                        try {
                            val rlist = returns.getList(key, NbtList.COMPOUND_TYPE.toInt())
                            val mlist = mutableListOf<MarketListing>()
                            rlist.forEach { element ->
                                try {
                                    mlist.add(MarketListing.fromNbt(element as NbtCompound))
                                } catch (e: Exception) {
                                    CobbleMarket.LOGGER.warn("Skipping corrupted returned listing for '{}': {}", key, e.message)
                                }
                            }
                            pendingReturns[UUID.fromString(key)] = mlist
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted pending return entry '{}': {}", key, e.message)
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
                        CobbleMarket.LOGGER.info("Cleaned up {} orphan pokemon listings from old save data", orphans.size)
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): MarketState {
            return server.overworld.persistentStateManager.getOrCreate(TYPE, CobbleMarket.MOD_ID)
        }
    }
}
