package com.shusheng.cobblemarket.market

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID


enum class AuctionType {
    POKEMON, ITEM
}

enum class AuctionStatus {
    ACTIVE, SOLD, UNSOLD
}

/** 一次出价记录（完整出价史，退款后仍保留供审计/历史展示） */
data class AuctionBid(
    val bidderUuid: UUID,
    val bidderName: String,
    val amount: Int,
    val at: Long
)

/**
 * 拍卖条目：精灵/物品二选一（按 type），当前价与出价史维护在条目内。
 * 资金模型：出价即扣款（钱悬空于拍卖中），被超价即时退待领余额；结算时赢家款项转卖家（扣手续费）。
 */
data class AuctionListing(
    val id: UUID,
    val type: AuctionType,
    val sellerUuid: UUID,
    val sellerName: String,
    // 精灵字段（POKEMON）
    val pokemonNbt: NbtCompound?,
    val species: String,
    val level: Int,
    val shiny: Boolean,
    val extraData: Map<String, String> = emptyMap(),
    // 物品字段（ITEM，species 字段存 itemId）
    val itemNbt: NbtCompound?,
    val count: Int,
    val startingPrice: Int,
    val minIncrement: Int,
    // 出价时更新当前价与领先者，故为 var
    var currentPrice: Int,
    var currentBidderUuid: UUID?,
    var currentBidderName: String,
    val bids: MutableList<AuctionBid>,
    val createdAt: Long,
    // 反狙击延长会修改结束时间，故为 var
    var endsAt: Long,
    var status: AuctionStatus,
    var returnedAt: Long?
) {
    fun isActive(): Boolean = status == AuctionStatus.ACTIVE

    /** 通知消息用的展示名：精灵 = 翻译 key、物品 = 物品翻译 key（均客户端语言渲染） */
    fun speciesText(): net.minecraft.text.Text =
        if (type == AuctionType.POKEMON) {
            net.minecraft.text.Text.translatable(extraData["speciesKey"] ?: "cobblemon.species.${species.lowercase()}.name")
        } else {
            val id = net.minecraft.util.Identifier.tryParse(species)
            val item = id?.let { net.minecraft.registry.Registries.ITEM.getOrEmpty(it).orElse(null) }
            if (item != null) net.minecraft.text.Text.translatable(item.translationKey)
            else net.minecraft.text.Text.literal(species)
        }

    fun toNbt(): NbtCompound = NbtCompound().apply {
        putUuid("id", id)
        putString("type", this@AuctionListing.type.name)
        putUuid("sellerUuid", sellerUuid)
        putString("sellerName", sellerName)
        pokemonNbt?.let { put("pokemon", it) }
        putString("species", species)
        putInt("level", level)
        putBoolean("shiny", shiny)
        val extra = NbtCompound()
        extraData.forEach { (k, v) -> extra.putString(k, v) }
        put("extraData", extra)
        itemNbt?.let { put("itemNbt", it) }
        putInt("count", count)
        putInt("startingPrice", startingPrice)
        putInt("minIncrement", minIncrement)
        putInt("currentPrice", currentPrice)
        currentBidderUuid?.let { putUuid("currentBidderUuid", it) }
        putString("currentBidderName", currentBidderName)
        val bidList = NbtList()
        bids.forEach { bid ->
            val b = NbtCompound()
            b.putUuid("bidderUuid", bid.bidderUuid)
            b.putString("bidderName", bid.bidderName)
            b.putInt("amount", bid.amount)
            b.putLong("at", bid.at)
            bidList.add(b)
        }
        put("bids", bidList)
        putLong("createdAt", createdAt)
        putLong("endsAt", endsAt)
        putString("status", status.name)
        returnedAt?.let { putLong("returnedAt", it) }
    }

    companion object {
        fun fromNbt(nbt: NbtCompound): AuctionListing {
            val extra = nbt.getCompound("extraData")
            val extraMap = mutableMapOf<String, String>()
            extra.keys.forEach { key -> extraMap[key] = extra.getString(key) }
            val bids = mutableListOf<AuctionBid>()
            nbt.getList("bids", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                val b = element as NbtCompound
                bids.add(AuctionBid(
                    bidderUuid = b.getUuid("bidderUuid"),
                    bidderName = b.getString("bidderName"),
                    amount = b.getInt("amount"),
                    at = b.getLong("at")
                ))
            }
            return AuctionListing(
                id = nbt.getUuid("id"),
                type = AuctionType.valueOf(nbt.getString("type")),
                sellerUuid = nbt.getUuid("sellerUuid"),
                sellerName = nbt.getString("sellerName"),
                pokemonNbt = if (nbt.contains("pokemon")) nbt.getCompound("pokemon") else null,
                species = nbt.getString("species"),
                level = nbt.getInt("level"),
                shiny = nbt.getBoolean("shiny"),
                extraData = extraMap,
                itemNbt = if (nbt.contains("itemNbt")) nbt.getCompound("itemNbt") else null,
                count = nbt.getInt("count"),
                startingPrice = nbt.getInt("startingPrice"),
                minIncrement = nbt.getInt("minIncrement"),
                currentPrice = nbt.getInt("currentPrice"),
                currentBidderUuid = if (nbt.containsUuid("currentBidderUuid")) nbt.getUuid("currentBidderUuid") else null,
                currentBidderName = nbt.getString("currentBidderName"),
                bids = bids,
                createdAt = nbt.getLong("createdAt"),
                endsAt = nbt.getLong("endsAt"),
                status = AuctionStatus.valueOf(nbt.getString("status")),
                returnedAt = if (nbt.contains("returnedAt")) nbt.getLong("returnedAt") else null
            )
        }
    }
}

class AuctionState private constructor() : PersistentState() {

    private val auctions = mutableMapOf<UUID, AuctionListing>()

    fun addAuction(auction: AuctionListing) {
        auctions[auction.id] = auction
        markDirty()
    }

    fun getAuction(id: UUID): AuctionListing? = auctions[id]

    fun markModified() = markDirty()

    fun getActiveAuctions(): List<AuctionListing> =
        auctions.values.filter { it.isActive() }.sortedBy { it.endsAt }

    fun countActiveBySeller(sellerUuid: UUID): Int =
        auctions.values.count { it.sellerUuid == sellerUuid && it.isActive() }

    // 结算扫描节流：与挂单过期检查同款（nanoTime 单调时钟节流，判定用 wall-clock）
    // 结束警告声状态（内存即可：重启后重新警告无害）；按结束时刻区分，反狙击延长后重新敲
    private val warnEndsAt = mutableMapOf<UUID, Long>()
    private val warnKnockCount = mutableMapOf<UUID, Int>()

    /** 轮询该拍卖当前应敲的警告声次（1/2/3；0 = 无需敲）。每个结束时刻每声只返回一次。
     *  三声节奏：剩 10s / 6s / 3s（紧凑间隔，第三声与结算落槌留出 3 秒间隙） */
    fun pollWarnKnock(id: UUID, endsAt: Long, now: Long): Int {
        val remaining = endsAt - now
        val k = when {
            remaining > 10_000L -> 0
            remaining > 6_000L -> 1
            remaining > 3_000L -> 2
            else -> 3
        }
        if (k <= 0) return 0
        if (warnEndsAt[id] != endsAt) {
            warnEndsAt[id] = endsAt
            warnKnockCount[id] = 0
        }
        val cur = warnKnockCount[id] ?: 0
        if (cur >= k) return 0
        warnKnockCount[id] = k
        return k
    }

    /**
     * 结算到期拍卖，返回已结算条目列表（供调用方广播增量事件）。
     * 成交：赢家物品进其待取回（复用挂单取回链路），卖家成交价-手续费进待领余额；
     * 流拍：物品退回卖家待取回。失败出价者的钱在被超价时已即时退还。
     */
    fun settleExpiredAuctions(server: MinecraftServer, currentTime: Long): List<AuctionListing> {
        // 无节流：到期拍卖应立即结算（15 秒节流会让错开到期的拍卖挂「结算中」十几秒，
        // 落槌延迟；全表 filter 本身极轻，惰性触发频率也低，无需节流）
        val due = auctions.values.filter { it.isActive() && it.endsAt <= currentTime }
        val settled = mutableListOf<AuctionListing>()
        due.forEach { auction ->
            val winnerUuid = auction.currentBidderUuid
            val sold = winnerUuid != null && auction.currentPrice > 0
            // 先设终态再入队（挂单状态由 auction.status 决定）；入队失败恢复 ACTIVE 待下次扫描重试
            auction.status = if (sold) AuctionStatus.SOLD else AuctionStatus.UNSOLD
            val ownerUuid = if (sold) winnerUuid else auction.sellerUuid
            val ownerName = if (sold) auction.currentBidderName else auction.sellerName
            val returned = returnToOwner(server, auction, ownerUuid, ownerName)
            if (!returned) {
                // 入队失败（极罕见）：恢复 ACTIVE，下次结算扫描重试；不移除、不转资金（物品与钱都保持原状）
                auction.status = AuctionStatus.ACTIVE
                CobbleMarket.LOGGER.error("Failed to enqueue auction {} return; will retry on next settle scan", auction.id)
                return@forEach
            }
            if (sold) {
                val feePercent = com.shusheng.cobblemarket.config.CobbleMarketConfig.auctionFeePercent
                val fee = if (feePercent > 0)
                    Math.ceil(auction.currentPrice * feePercent / 100.0).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                else 0
                MarketState.get(server).addPendingBalance(auction.sellerUuid, (auction.currentPrice - fee).toLong())
                try {
                    com.shusheng.cobblemarket.event.TransactionHistory.get(server).addRecord(
                        com.shusheng.cobblemarket.event.TransactionRecord(
                            timestamp = currentTime,
                            type = com.shusheng.cobblemarket.event.TransactionType.PURCHASE,
                            category = if (auction.type == AuctionType.POKEMON)
                                com.shusheng.cobblemarket.event.TransactionCategory.POKEMON
                            else
                                com.shusheng.cobblemarket.event.TransactionCategory.ITEM,
                            sellerUuid = auction.sellerUuid,
                            sellerName = auction.sellerName,
                            buyerUuid = winnerUuid,
                            buyerName = auction.currentBidderName,
                            // 精灵用翻译 key（历史界面客户端翻译显示中文名），物品为 itemId
                            species = auction.extraData["speciesKey"] ?: auction.species,
                            price = auction.currentPrice,
                            fee = fee
                        )
                    )
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.warn("Failed to record auction sale for {}: {}", auction.id, e.message)
                }
            }
            auction.returnedAt = currentTime
            settled.add(auction)
            // 结算完成即清除：资产已转移（货进待取回/钱进待领余额），交易已入历史，
            // 拍卖记录无任何功能引用，立即移除避免存档膨胀
            auctions.remove(auction.id)
            warnEndsAt.remove(auction.id)
            warnKnockCount.remove(auction.id)
        }
        if (settled.isNotEmpty()) markDirty()
        return settled
    }

    /** OP 强制下架：强制流拍——物品退卖家待领取，当前出价者全额退款进待收款。返回是否成功。 */
    fun forceCancel(server: MinecraftServer, auction: AuctionListing): Boolean {
        if (!auction.isActive()) return false
        val bidder = auction.currentBidderUuid
        val bidAmount = auction.currentPrice
        auction.status = AuctionStatus.UNSOLD
        val returned = returnToOwner(server, auction, auction.sellerUuid, auction.sellerName)
        if (!returned) {
            auction.status = AuctionStatus.ACTIVE
            return false
        }
        if (bidder != null && bidAmount > 0) {
            MarketState.get(server).addPendingBalance(bidder, bidAmount.toLong())
        }
        auction.returnedAt = System.currentTimeMillis()
        auctions.remove(auction.id)
        warnEndsAt.remove(auction.id)
        warnKnockCount.remove(auction.id)
        markDirty()
        return true
    }

    /** 结算产物包装成挂单进接收者的待取回列表（完全复用现有取回/保留期清理链路）。返回是否成功入队。 */
    private fun returnToOwner(server: MinecraftServer, auction: AuctionListing, ownerUuid: UUID, ownerName: String): Boolean {
        return try {
            when (auction.type) {
                AuctionType.POKEMON -> {
                    val listing = MarketListing(
                        id = auction.id,
                        sellerUuid = ownerUuid,
                        sellerName = ownerName,
                        pokemonNbt = auction.pokemonNbt ?: NbtCompound(),
                        species = auction.species,
                        level = auction.level,
                        shiny = auction.shiny,
                        price = auction.currentPrice,
                        createdAt = auction.createdAt,
                        expiresAt = auction.endsAt,
                        status = if (auction.status == AuctionStatus.SOLD) ListingStatus.SOLD else ListingStatus.EXPIRED,
                        extraData = auction.extraData
                    )
                    MarketState.get(server).addPendingReturn(ownerUuid, listing)
                }
                AuctionType.ITEM -> {
                    val listing = ItemListing(
                        id = auction.id,
                        sellerUuid = ownerUuid,
                        sellerName = ownerName,
                        itemId = auction.species,
                        itemNbt = auction.itemNbt ?: NbtCompound(),
                        count = auction.count,
                        price = auction.currentPrice,
                        createdAt = auction.createdAt,
                        expiresAt = auction.endsAt,
                        status = if (auction.status == AuctionStatus.SOLD) ListingStatus.SOLD else ListingStatus.EXPIRED
                    )
                    ItemMarketState.get(server).addPendingReturn(ownerUuid, listing)
                }
            }
            true
        } catch (e: Exception) {
            // 结算产物入队失败（单条损坏不阻塞其他结算）——记录告警，条目保持终态；成交分支据此退款给买家
            CobbleMarket.LOGGER.error("Failed to enqueue auction {} return for {}: {}", auction.id, ownerUuid, e.message)
            false
        }
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        auctions.values.forEach { auction -> list.add(auction.toNbt()) }
        nbt.put("auctions", list)
        return nbt
    }

    companion object {
        private val TYPE = PersistentState.Type(
            { AuctionState() },
            { nbt, _ ->
                AuctionState().apply {
                    nbt.getList("auctions", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val auction = AuctionListing.fromNbt(element as NbtCompound)
                            // 只加载 ACTIVE：已结束拍卖的资产在结算时已转移完毕（旧存档瘦身）
                            if (auction.isActive()) auctions[auction.id] = auction
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted auction: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): AuctionState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_auctions")
    }
}
