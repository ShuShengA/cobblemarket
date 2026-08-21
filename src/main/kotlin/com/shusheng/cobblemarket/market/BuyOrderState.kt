package com.shusheng.cobblemarket.market

import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

enum class BuyOrderType {
    POKEMON, ITEM
}

enum class BuyOrderStatus {
    OPEN, CLOSED
}

/**
 * 待确认交付：卖家已提交、买家尚未决定（货悬空于订单内，资金未结算）。
 * 接受 → 结算+货进买家待领取；拒绝/订单过期/买家关单 → 货退回卖家待领取。
 */
data class PendingDelivery(
    val id: UUID,
    val sellerUuid: UUID,
    val sellerName: String,
    val price: Int,
    // 精灵（POKEMON）
    val pokemonNbt: NbtCompound?,
    val species: String,
    val level: Int,
    val shiny: Boolean,
    val extraData: Map<String, String>,
    // 物品（ITEM）
    val itemNbt: NbtCompound?,
    val count: Int,
    val deliveredAt: Long
) {
    fun toNbt(): NbtCompound = NbtCompound().apply {
        putUuid("id", id)
        putUuid("sellerUuid", sellerUuid)
        putString("sellerName", sellerName)
        putInt("price", price)
        pokemonNbt?.let { put("pokemon", it) }
        putString("species", species)
        putInt("level", level)
        putBoolean("shiny", shiny)
        val extra = NbtCompound()
        extraData.forEach { (k, v) -> extra.putString(k, v) }
        put("extraData", extra)
        itemNbt?.let { put("itemNbt", it) }
        putInt("count", count)
        putLong("deliveredAt", deliveredAt)
    }

    companion object {
        fun fromNbt(nbt: NbtCompound): PendingDelivery {
            val extra = nbt.getCompound("extraData")
            val extraMap = mutableMapOf<String, String>()
            extra.keys.forEach { key -> extraMap[key] = extra.getString(key) }
            return PendingDelivery(
                id = nbt.getUuid("id"),
                sellerUuid = nbt.getUuid("sellerUuid"),
                sellerName = nbt.getString("sellerName"),
                price = nbt.getInt("price"),
                pokemonNbt = if (nbt.contains("pokemon")) nbt.getCompound("pokemon") else null,
                species = nbt.getString("species"),
                level = nbt.getInt("level"),
                shiny = nbt.getBoolean("shiny"),
                extraData = extraMap,
                itemNbt = if (nbt.contains("itemNbt")) nbt.getCompound("itemNbt") else null,
                count = nbt.getInt("count"),
                deliveredAt = nbt.getLong("deliveredAt")
            )
        }
    }
}

/**
 * 求购单条目：买家发布"我要买"订单（精灵 1 只 / 物品若干件 + 单价区间），全服可见；
 * 卖家交付符合要求的货，按实际单价成交（差价退还买家），凑满件数或过期/关闭时退剩余冻结金。
 * 精灵要求字段（物种/闪光/特训/IV/形态/特性/性格）语义照黑名单条目；ITEM 类型下这些字段为默认值。
 */
data class BuyOrder(
    val id: UUID,
    val buyerUuid: UUID,
    val buyerName: String,
    val type: BuyOrderType,
    // 精灵要求（POKEMON）
    val speciesId: String?,    // null = 任意精灵
    val speciesKey: String?,   // 物种翻译 key（客户端翻译显示）；null = 任意精灵
    val shinyFilter: Int,      // 照黑名单：-1 不限 / 0 仅非闪 / 1 仅闪
    val htFilter: Int,         // 照黑名单：0 不限 / 1 仅特训 / 2 不含特训
    val ivHp: Int, val ivAtk: Int, val ivDef: Int,   // -1 = 不限；精确匹配（按有效值）
    val ivSpAtk: Int, val ivSpDef: Int, val ivSpd: Int,
    val aspects: List<String>, // 形态照黑名单语义：["*"]=不限，[]=默认形态，[x..]=子集
    val abilityKey: String?,   // 特性翻译 key，null = 不限
    val natureKey: String?,    // 性格翻译 key，null = 不限
    // 物品要求（ITEM）
    val itemId: String,
    // 数量（精灵固定 1 只；物品=件数，多个卖家可部分交付）
    val totalCount: Int,
    var remainingCount: Int,
    // 单价区间（冻结金 = maxPrice × totalCount）
    val minPrice: Int,
    val maxPrice: Int,
    // 买家备注（额外需求提醒卖家，如"最好是母的"；长度服务端钳制）
    val note: String,
    val createdAt: Long,
    val expiresAt: Long,
    var status: BuyOrderStatus,
    var returnedAt: Long?,
    // 待确认交付（货悬空于订单内；锁定策略下同时最多 1 条）
    val pendingDeliveries: MutableList<PendingDelivery> = mutableListOf()
) {
    fun isOpen(): Boolean = status == BuyOrderStatus.OPEN

    /** 当前剩余冻结金（关闭/过期时退还买家） */
    fun frozenRemaining(): Long = maxPrice.toLong() * remainingCount

    /**
     * 精灵是否满足求购要求。匹配语义与黑名单一致：
     * IV 按有效值（特训项用特训值）；形态 ["*"] 不限 / [] 仅默认形态 / 列表=子集。
     */
    fun matchesPokemon(pokemon: com.cobblemon.mod.common.pokemon.Pokemon): Boolean {
        if (speciesId != null && speciesId != pokemon.species.resourceIdentifier.toString()) return false
        if (shinyFilter != PokemonBlacklistEntry.SHINY_ANY &&
            pokemon.shiny != (shinyFilter == PokemonBlacklistEntry.SHINY_YES)) return false
        val targetHasHt = pokemon.ivs.hyperTrainedIVs.values.any { it >= 0 }
        if (htFilter != PokemonBlacklistEntry.HT_ANY && targetHasHt != (htFilter == PokemonBlacklistEntry.HT_ONLY)) return false
        val htIvs = pokemon.ivs.hyperTrainedIVs
        val ivs = pokemon.ivs
        if (ivHp >= 0 && (htIvs[Stats.HP] ?: ivs[Stats.HP]) != ivHp) return false
        if (ivAtk >= 0 && (htIvs[Stats.ATTACK] ?: ivs[Stats.ATTACK]) != ivAtk) return false
        if (ivDef >= 0 && (htIvs[Stats.DEFENCE] ?: ivs[Stats.DEFENCE]) != ivDef) return false
        if (ivSpAtk >= 0 && (htIvs[Stats.SPECIAL_ATTACK] ?: ivs[Stats.SPECIAL_ATTACK]) != ivSpAtk) return false
        if (ivSpDef >= 0 && (htIvs[Stats.SPECIAL_DEFENCE] ?: ivs[Stats.SPECIAL_DEFENCE]) != ivSpDef) return false
        if (ivSpd >= 0 && (htIvs[Stats.SPEED] ?: ivs[Stats.SPEED]) != ivSpd) return false
        if (PokemonBlacklistEntry.ALL_FORMS !in aspects) {
            val formAspectUnion = buildSet {
                addAll(pokemon.species.standardForm.aspects)
                pokemon.species.forms.forEach { addAll(it.aspects) }
            }
            if (aspects.isEmpty()) {
                if (formAspectUnion.any { it in pokemon.aspects }) return false
            } else if (!pokemon.aspects.containsAll(aspects)) {
                return false
            }
        }
        if (abilityKey != null && abilityKey != "cobblemon.ability.${pokemon.ability.name}") return false
        if (natureKey != null && natureKey != "cobblemon.nature.${pokemon.effectiveNature.name.path}") return false
        return true
    }

    /** 通知消息用的展示名：精灵 = 物种翻译 key（客户端语言渲染）或「任意精灵」key；物品 = 物品翻译 key */
    fun requirementText(): net.minecraft.text.Text =
        if (type == BuyOrderType.POKEMON) {
            net.minecraft.text.Text.translatable(speciesKey ?: "cobblemarket.buy_order.any_pokemon")
        } else {
            val id = net.minecraft.util.Identifier.tryParse(itemId)
            val item = id?.let { net.minecraft.registry.Registries.ITEM.getOrEmpty(it).orElse(null) }
            if (item != null) net.minecraft.text.Text.translatable(item.translationKey)
            else net.minecraft.text.Text.literal(itemId)
        }

    fun toNbt(): NbtCompound = NbtCompound().apply {
        putUuid("id", id)
        putUuid("buyerUuid", buyerUuid)
        putString("buyerName", buyerName)
        putString("type", this@BuyOrder.type.name)
        speciesId?.let { putString("speciesId", it) }
        speciesKey?.let { putString("speciesKey", it) }
        putInt("shinyFilter", shinyFilter)
        putInt("htFilter", htFilter)
        putInt("ivHp", ivHp)
        putInt("ivAtk", ivAtk)
        putInt("ivDef", ivDef)
        putInt("ivSpAtk", ivSpAtk)
        putInt("ivSpDef", ivSpDef)
        putInt("ivSpd", ivSpd)
        val aspectList = NbtList()
        aspects.forEach { aspectList.add(net.minecraft.nbt.NbtString.of(it)) }
        put("aspects", aspectList)
        abilityKey?.let { putString("abilityKey", it) }
        natureKey?.let { putString("natureKey", it) }
        putString("itemId", itemId)
        putInt("totalCount", totalCount)
        putInt("remainingCount", remainingCount)
        putInt("minPrice", minPrice)
        putInt("maxPrice", maxPrice)
        putString("note", note)
        putLong("createdAt", createdAt)
        putLong("expiresAt", expiresAt)
        putString("status", status.name)
        returnedAt?.let { putLong("returnedAt", it) }
        val pendingList = NbtList()
        pendingDeliveries.forEach { pendingList.add(it.toNbt()) }
        put("pendingDeliveries", pendingList)
    }

    companion object {
        fun fromNbt(nbt: NbtCompound): BuyOrder = BuyOrder(
            id = nbt.getUuid("id"),
            buyerUuid = nbt.getUuid("buyerUuid"),
            buyerName = nbt.getString("buyerName"),
            type = BuyOrderType.valueOf(nbt.getString("type")),
            speciesId = if (nbt.contains("speciesId")) nbt.getString("speciesId") else null,
            speciesKey = if (nbt.contains("speciesKey")) nbt.getString("speciesKey") else null,
            shinyFilter = nbt.getInt("shinyFilter"),
            htFilter = nbt.getInt("htFilter"),
            ivHp = nbt.getInt("ivHp"),
            ivAtk = nbt.getInt("ivAtk"),
            ivDef = nbt.getInt("ivDef"),
            ivSpAtk = nbt.getInt("ivSpAtk"),
            ivSpDef = nbt.getInt("ivSpDef"),
            ivSpd = nbt.getInt("ivSpd"),
            aspects = nbt.getList("aspects", NbtList.STRING_TYPE.toInt()).map { it.asString() },
            abilityKey = if (nbt.contains("abilityKey")) nbt.getString("abilityKey") else null,
            natureKey = if (nbt.contains("natureKey")) nbt.getString("natureKey") else null,
            itemId = nbt.getString("itemId"),
            totalCount = nbt.getInt("totalCount"),
            remainingCount = nbt.getInt("remainingCount"),
            minPrice = nbt.getInt("minPrice"),
            maxPrice = nbt.getInt("maxPrice"),
            // 旧存档无 note 字段 → 空串
            note = if (nbt.contains("note")) nbt.getString("note") else "",
            createdAt = nbt.getLong("createdAt"),
            expiresAt = nbt.getLong("expiresAt"),
            status = BuyOrderStatus.valueOf(nbt.getString("status")),
            returnedAt = if (nbt.contains("returnedAt")) nbt.getLong("returnedAt") else null,
            pendingDeliveries = nbt.getList("pendingDeliveries", NbtList.COMPOUND_TYPE.toInt())
                .map { PendingDelivery.fromNbt(it as NbtCompound) }
                .toMutableList()
        )
    }
}

class BuyOrderState private constructor() : PersistentState() {

    private val orders = mutableMapOf<UUID, BuyOrder>()

    fun addOrder(order: BuyOrder) {
        orders[order.id] = order
        markDirty()
    }

    fun getOrder(id: UUID): BuyOrder? = orders[id]

    fun getOpenOrders(): List<BuyOrder> =
        orders.values.filter { it.isOpen() }.sortedBy { it.createdAt }

    fun countOpenByBuyer(buyerUuid: UUID): Int =
        orders.values.count { it.buyerUuid == buyerUuid && it.isOpen() }

    fun markModified() = markDirty()

    /**
     * 待确认交付的货物入队：ownerUuid 接收（买家接受=SOLD；卖家退回=CANCELLED/EXPIRED）。
     * 返回是否成功入队；失败时调用方保持 pending 原状，等待下次重试。
     */
    fun returnPending(
        server: MinecraftServer,
        order: BuyOrder,
        pending: PendingDelivery,
        ownerUuid: UUID,
        ownerName: String,
        status: ListingStatus
    ): Boolean {
        return try {
            val retentionMs = CobbleMarketConfig.pendingReturnRetentionDays * 24L * 60 * 60 * 1000
            when (order.type) {
                BuyOrderType.POKEMON -> {
                    val listing = MarketListing(
                        id = pending.id,
                        sellerUuid = ownerUuid,
                        sellerName = ownerName,
                        pokemonNbt = pending.pokemonNbt ?: NbtCompound(),
                        species = pending.species,
                        level = pending.level,
                        shiny = pending.shiny,
                        price = pending.price,
                        createdAt = pending.deliveredAt,
                        expiresAt = System.currentTimeMillis() + retentionMs,
                        status = status,
                        extraData = pending.extraData
                    )
                    MarketState.get(server).addPendingReturn(ownerUuid, listing)
                }
                BuyOrderType.ITEM -> {
                    val listing = ItemListing(
                        id = pending.id,
                        sellerUuid = ownerUuid,
                        sellerName = ownerName,
                        itemId = order.itemId,
                        itemNbt = pending.itemNbt ?: NbtCompound(),
                        count = pending.count,
                        price = pending.price,
                        createdAt = pending.deliveredAt,
                        expiresAt = System.currentTimeMillis() + retentionMs,
                        status = status
                    )
                    ItemMarketState.get(server).addPendingReturn(ownerUuid, listing)
                }
            }
            true
        } catch (e: Exception) {
            // 入队失败（单条损坏不阻塞其他流程）——返回 false，调用方保持 pending 原状
            CobbleMarket.LOGGER.error("Failed to enqueue pending delivery {} return for {}: {}", pending.id, ownerUuid, e.message)
            false
        }
    }

    /**
     * 到期自动关闭：待确认交付退回卖家，剩余冻结金退买家待领余额，条目立即移除。
     * 任一 pending 退回失败则本次跳过（保持 OPEN 下次重试，绝不丢悬空货）。
     * 返回已关闭列表供调用方广播事件。
     */
    fun settleExpiredOrders(server: MinecraftServer, currentTime: Long): List<BuyOrder> {
        val due = orders.values.filter { it.isOpen() && it.expiresAt <= currentTime }
        val closed = mutableListOf<BuyOrder>()
        due.forEach { order ->
            var allReturned = true
            order.pendingDeliveries.forEach { pending ->
                if (!returnPending(server, order, pending, pending.sellerUuid, pending.sellerName, ListingStatus.EXPIRED)) {
                    allReturned = false
                }
            }
            if (!allReturned) return@forEach
            order.pendingDeliveries.clear()
            order.status = BuyOrderStatus.CLOSED
            order.returnedAt = currentTime
            MarketState.get(server).addPendingBalance(order.buyerUuid, order.frozenRemaining())
            orders.remove(order.id)
            closed.add(order)
        }
        if (closed.isNotEmpty()) markDirty()
        return closed
    }

    /** 买家主动关闭：待确认交付退回卖家，退剩余冻结金进待领余额，条目立即移除。返回是否成功。 */
    fun closeByBuyer(server: MinecraftServer, order: BuyOrder): Boolean {
        if (!order.isOpen()) return false
        var allReturned = true
        order.pendingDeliveries.forEach { pending ->
            if (!returnPending(server, order, pending, pending.sellerUuid, pending.sellerName, ListingStatus.CANCELLED)) {
                allReturned = false
            }
        }
        if (!allReturned) return false
        order.pendingDeliveries.clear()
        order.status = BuyOrderStatus.CLOSED
        order.returnedAt = System.currentTimeMillis()
        MarketState.get(server).addPendingBalance(order.buyerUuid, order.frozenRemaining())
        orders.remove(order.id)
        markDirty()
        return true
    }

    /** 凑满交付后移除条目（剩余冻结金为 0，无退款；生命周期终结立即删除避免存档膨胀） */
    fun removeOrder(id: UUID) {
        if (orders.remove(id) != null) markDirty()
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        orders.values.forEach { order -> list.add(order.toNbt()) }
        nbt.put("orders", list)
        return nbt
    }

    companion object {
        private val TYPE = PersistentState.Type(
            { BuyOrderState() },
            { nbt, _ ->
                BuyOrderState().apply {
                    nbt.getList("orders", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val order = BuyOrder.fromNbt(element as NbtCompound)
                            // 只加载 OPEN：已关闭订单的冻结金在关闭时已退还完毕
                            if (order.isOpen()) orders[order.id] = order
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted buy order: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): BuyOrderState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_buy_orders")
    }
}
