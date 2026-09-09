package com.shusheng.cobblemarket.network

import com.cobblemon.mod.common.Cobblemon
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.config.CurrencyHandler
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.event.TransactionCategory
import com.shusheng.cobblemarket.event.TransactionHistory
import com.shusheng.cobblemarket.event.TransactionRecord
import com.shusheng.cobblemarket.event.TransactionType
import com.shusheng.cobblemarket.market.BuyOrder
import com.shusheng.cobblemarket.market.BuyOrderState
import com.shusheng.cobblemarket.market.BuyOrderStatus
import com.shusheng.cobblemarket.market.BuyOrderType
import com.shusheng.cobblemarket.market.PendingDelivery
import com.shusheng.cobblemarket.market.ItemBlacklistState
import com.shusheng.cobblemarket.market.ItemListing
import com.shusheng.cobblemarket.market.ItemMarketState
import com.shusheng.cobblemarket.market.ListingStatus
import com.shusheng.cobblemarket.market.MarketListing
import com.shusheng.cobblemarket.market.MarketState
import com.shusheng.cobblemarket.market.PokemonBlacklistEntry
import com.shusheng.cobblemarket.market.PokemonBlacklistState
import com.shusheng.cobblemarket.util.RequestThrottle
import com.shusheng.cobblemarket.platform.onServerTickEnd
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer

import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.text.ClickEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID

// ── DTO：精简求购单条目（客户端列表用） ──

/** 待确认交付的 DTO（买家确认弹窗展示用；null = 无待确认） */
data class BuyOrderPendingEntry(
    val id: UUID,
    val sellerName: String,
    val price: Int,
    val speciesKey: String?,   // 精灵 = 翻译 key（客户端本地翻译）；物品 = null
    val level: Int,
    val shiny: Boolean,
    val extraData: Map<String, String>, // 精灵展示字段（IV/球种等）
    val count: Int,            // 物品件数（精灵 = 1）
    /** 物品交付的组件 NBT（附魔/名称等验收核对用；精灵交付为 null） */
    val itemNbt: NbtCompound?
) {
    fun write(buf: PacketByteBuf) {
        buf.writeUuid(id)
        buf.writeString(sellerName)
        buf.writeInt(price)
        buf.writeBoolean(speciesKey != null)
        speciesKey?.let { buf.writeString(it) }
        buf.writeInt(level)
        buf.writeBoolean(shiny)
        buf.writeVarInt(extraData.size)
        extraData.forEach { (k, v) -> buf.writeString(k); buf.writeString(v) }
        buf.writeInt(count)
        buf.writeBoolean(itemNbt != null)
        itemNbt?.let { PacketCodecs.NBT_COMPOUND.encode(buf, it) }
    }

    companion object {
        fun read(buf: PacketByteBuf) = BuyOrderPendingEntry(
            id = buf.readUuid(),
            sellerName = buf.readString(),
            price = buf.readInt(),
            speciesKey = if (buf.readBoolean()) buf.readString() else null,
            level = buf.readInt(),
            shiny = buf.readBoolean(),
            extraData = (0 until buf.readVarInt()).associate { buf.readString() to buf.readString() },
            count = buf.readInt(),
            itemNbt = if (buf.readBoolean()) PacketCodecs.NBT_COMPOUND.decode(buf) else null
        )
    }
}

data class BuyOrderEntry(
    val id: UUID,
    val type: String,          // "POKEMON" / "ITEM"
    val buyerUuid: UUID,
    val buyerName: String,
    val speciesId: String?,    // 精灵 = 物种 ID；null = 任意精灵（客户端交付预核对用）
    val speciesKey: String?,   // 精灵 = 翻译 key（客户端本地翻译）；null = 任意精灵；物品 = null
    val shinyFilter: Int,
    val htFilter: Int,
    val ivHp: Int, val ivAtk: Int, val ivDef: Int,
    val ivSpAtk: Int, val ivSpDef: Int, val ivSpd: Int,
    val aspects: List<String>,
    val abilityKey: String?,
    val natureKey: String?,
    val itemId: String,        // 物品单的物品 ID（精灵单为空串）
    val itemComponentsSpec: net.minecraft.nbt.NbtCompound?, // 物品单的组件要求（null = 所有变体）
    val totalCount: Int,
    val remainingCount: Int,
    val minPrice: Int,
    val maxPrice: Int,
    val note: String,          // 买家备注（额外需求，可为空）
    val createdAt: Long,
    val expiresAt: Long,
    val pending: BuyOrderPendingEntry? // 待确认交付（锁定策略下最多 1 条）
) {
    fun write(buf: PacketByteBuf) {
        buf.writeUuid(id)
        buf.writeString(type)
        buf.writeUuid(buyerUuid)
        buf.writeString(buyerName)
        buf.writeBoolean(speciesId != null)
        speciesId?.let { buf.writeString(it) }
        buf.writeBoolean(speciesKey != null)
        speciesKey?.let { buf.writeString(it) }
        buf.writeInt(shinyFilter)
        buf.writeInt(htFilter)
        buf.writeInt(ivHp); buf.writeInt(ivAtk); buf.writeInt(ivDef)
        buf.writeInt(ivSpAtk); buf.writeInt(ivSpDef); buf.writeInt(ivSpd)
        buf.writeVarInt(aspects.size)
        aspects.forEach { buf.writeString(it) }
        buf.writeBoolean(abilityKey != null)
        abilityKey?.let { buf.writeString(it) }
        buf.writeBoolean(natureKey != null)
        natureKey?.let { buf.writeString(it) }
        buf.writeString(itemId)
        buf.writeNbt(itemComponentsSpec)
        buf.writeInt(totalCount)
        buf.writeInt(remainingCount)
        buf.writeInt(minPrice)
        buf.writeInt(maxPrice)
        buf.writeString(note)
        buf.writeLong(createdAt)
        buf.writeLong(expiresAt)
        buf.writeBoolean(pending != null)
        pending?.let { it.write(buf) }
    }

    companion object {
        fun read(buf: PacketByteBuf) = BuyOrderEntry(
            id = buf.readUuid(),
            type = buf.readString(),
            buyerUuid = buf.readUuid(),
            buyerName = buf.readString(),
            speciesId = if (buf.readBoolean()) buf.readString() else null,
            speciesKey = if (buf.readBoolean()) buf.readString() else null,
            shinyFilter = buf.readInt(),
            htFilter = buf.readInt(),
            ivHp = buf.readInt(), ivAtk = buf.readInt(), ivDef = buf.readInt(),
            ivSpAtk = buf.readInt(), ivSpDef = buf.readInt(), ivSpd = buf.readInt(),
            aspects = (0 until buf.readVarInt()).map { buf.readString() },
            abilityKey = if (buf.readBoolean()) buf.readString() else null,
            natureKey = if (buf.readBoolean()) buf.readString() else null,
            itemId = buf.readString(),
            itemComponentsSpec = buf.readNbt(),
            totalCount = buf.readInt(),
            remainingCount = buf.readInt(),
            minPrice = buf.readInt(),
            maxPrice = buf.readInt(),
            note = buf.readString(),
            createdAt = buf.readLong(),
            expiresAt = buf.readLong(),
            pending = if (buf.readBoolean()) BuyOrderPendingEntry.read(buf) else null
        )
    }
}

fun buyOrderToEntry(o: BuyOrder): BuyOrderEntry = BuyOrderEntry(
    id = o.id,
    type = o.type.name,
    buyerUuid = o.buyerUuid,
    buyerName = o.buyerName,
    speciesId = o.speciesId,
    speciesKey = o.speciesKey,
    shinyFilter = o.shinyFilter,
    htFilter = o.htFilter,
    ivHp = o.ivHp, ivAtk = o.ivAtk, ivDef = o.ivDef,
    ivSpAtk = o.ivSpAtk, ivSpDef = o.ivSpDef, ivSpd = o.ivSpd,
    aspects = o.aspects,
    abilityKey = o.abilityKey,
    natureKey = o.natureKey,
    itemId = o.itemId,
    itemComponentsSpec = o.itemComponentsSpec,
    totalCount = o.totalCount,
    remainingCount = o.remainingCount,
    minPrice = o.minPrice,
    maxPrice = o.maxPrice,
    note = o.note,
    createdAt = o.createdAt,
    expiresAt = o.expiresAt,
    pending = o.pendingDeliveries.firstOrNull()?.let { p ->
        BuyOrderPendingEntry(
            id = p.id,
            sellerName = p.sellerName,
            price = p.price,
            speciesKey = p.extraData["speciesKey"],
            level = p.level,
            shiny = p.shiny,
            extraData = p.extraData,
            count = p.count,
            itemNbt = p.itemNbt
        )
    }
)

// ── C2S：请求求购单列表 ──

class RequestBuyOrderListPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestBuyOrderListPayload>(CobbleMarket.id("request_buy_order_list"))
        val CODEC: PacketCodec<PacketByteBuf, RequestBuyOrderListPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestBuyOrderListPayload() }
        )
    }
}

// ── S2C：求购单列表数据 ──

data class BuyOrderListDataPayload(val entries: List<BuyOrderEntry>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<BuyOrderListDataPayload>(CobbleMarket.id("buy_order_list_data"))
        val CODEC: PacketCodec<PacketByteBuf, BuyOrderListDataPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> BuyOrderListDataPayload((0 until b.readVarInt()).map { BuyOrderEntry.read(b) }) }
        )
    }
}

// ── S2C：求购单增量事件（NEW/UPDATED/CLOSED；CLOSED 也带条目供按 id 移除行） ──

data class BuyOrderEventPayload(val event: String, val entry: BuyOrderEntry) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<BuyOrderEventPayload>(CobbleMarket.id("buy_order_event"))
        val CODEC: PacketCodec<PacketByteBuf, BuyOrderEventPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.event); p.entry.write(b) },
            { b -> BuyOrderEventPayload(b.readString(), BuyOrderEntry.read(b)) }
        )
    }
}

// ── C2S：创建精灵求购单 ──

data class CreatePokemonBuyOrderPayload(
    val speciesId: String,     // 空串 = 任意精灵
    val shinyFilter: Int,      // -1 不限 / 0 仅非闪 / 1 仅闪
    val htFilter: Int,         // 0 不限 / 1 仅特训 / 2 不含特训
    val ivHp: Int, val ivAtk: Int, val ivDef: Int,   // -1 = 不限
    val ivSpAtk: Int, val ivSpDef: Int, val ivSpd: Int,
    val aspects: List<String>, // ["*"] = 不限形态
    val abilityKey: String,    // 空串 = 不限
    val natureKey: String,     // 空串 = 不限
    val minPrice: Int,
    val maxPrice: Int,
    val note: String           // 买家备注（可为空）
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CreatePokemonBuyOrderPayload>(CobbleMarket.id("create_pokemon_buy_order"))
        val CODEC: PacketCodec<PacketByteBuf, CreatePokemonBuyOrderPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.speciesId); b.writeInt(p.shinyFilter); b.writeInt(p.htFilter)
                b.writeInt(p.ivHp); b.writeInt(p.ivAtk); b.writeInt(p.ivDef)
                b.writeInt(p.ivSpAtk); b.writeInt(p.ivSpDef); b.writeInt(p.ivSpd)
                b.writeVarInt(p.aspects.size); p.aspects.forEach { b.writeString(it) }
                b.writeString(p.abilityKey); b.writeString(p.natureKey)
                b.writeInt(p.minPrice); b.writeInt(p.maxPrice)
                b.writeString(p.note)
            },
            { b ->
                CreatePokemonBuyOrderPayload(
                    b.readString(), b.readInt(), b.readInt(),
                    b.readInt(), b.readInt(), b.readInt(),
                    b.readInt(), b.readInt(), b.readInt(),
                    (0 until b.readVarInt()).map { b.readString() },
                    b.readString(), b.readString(),
                    b.readInt(), b.readInt(), b.readString()
                )
            }
        )
    }
}

// ── C2S：创建物品求购单 ──

data class CreateItemBuyOrderPayload(
    val itemId: String,
    val totalCount: Int,
    val minPrice: Int,
    val maxPrice: Int,
    val note: String,          // 买家备注（可为空）
    val useHeldItem: Boolean,  // true = 用手持物品（含组件快照）作为求购要求
    // 搜索路径选中的具体变体组件快照（如「招式学习器 · 打鼾」）；手持路径为 null（服务端读主手）
    val componentsSpec: net.minecraft.nbt.NbtCompound? = null,
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CreateItemBuyOrderPayload>(CobbleMarket.id("create_item_buy_order"))
        val CODEC: PacketCodec<PacketByteBuf, CreateItemBuyOrderPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.itemId); b.writeInt(p.totalCount); b.writeInt(p.minPrice); b.writeInt(p.maxPrice)
                b.writeString(p.note)
                b.writeBoolean(p.useHeldItem)
                b.writeNbt(p.componentsSpec)
            },
            { b -> CreateItemBuyOrderPayload(
                itemId = b.readString(),
                totalCount = b.readInt(),
                minPrice = b.readInt(),
                maxPrice = b.readInt(),
                note = b.readString(),
                useHeldItem = b.readBoolean(),
                componentsSpec = b.readNbt(),
            ) }
        )
    }
}

// ── C2S：买家关闭求购单（退剩余冻结金） ──

data class CancelBuyOrderPayload(val orderId: UUID) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CancelBuyOrderPayload>(CobbleMarket.id("cancel_buy_order"))
        val CODEC: PacketCodec<PacketByteBuf, CancelBuyOrderPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.orderId) },
            { b -> CancelBuyOrderPayload(b.readUuid()) }
        )
    }
}

// ── C2S：OP 强制下架求购单（复用 closeByBuyer：冻结金退买家、待确认交付退卖家） ──

data class ForceCancelBuyOrderPayload(val orderId: UUID) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<ForceCancelBuyOrderPayload>(CobbleMarket.id("force_cancel_buy_order"))
        val CODEC: PacketCodec<PacketByteBuf, ForceCancelBuyOrderPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.orderId) },
            { b -> ForceCancelBuyOrderPayload(b.readUuid()) }
        )
    }
}

// ── C2S：卖家交付精灵（1 只即凑满，订单自动关闭） ──

data class DeliverPokemonBuyOrderPayload(
    val orderId: UUID,
    val pokemonUuid: UUID,
    val price: Int
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<DeliverPokemonBuyOrderPayload>(CobbleMarket.id("deliver_pokemon_buy_order"))
        val CODEC: PacketCodec<PacketByteBuf, DeliverPokemonBuyOrderPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.orderId); b.writeUuid(p.pokemonUuid); b.writeInt(p.price) },
            { b -> DeliverPokemonBuyOrderPayload(b.readUuid(), b.readUuid(), b.readInt()) }
        )
    }
}

// ── C2S：卖家交付物品（按件成交，多个卖家可部分交付） ──

data class DeliverItemBuyOrderPayload(
    val orderId: UUID,
    val count: Int,
    val price: Int,
    /** 所选形态的序列化（客户端背包示例栈）：服务端重建为权威参考栈，不信任客户端一致性 */
    val variantNbt: NbtCompound,
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<DeliverItemBuyOrderPayload>(CobbleMarket.id("deliver_item_buy_order"))
        val CODEC: PacketCodec<PacketByteBuf, DeliverItemBuyOrderPayload> = PacketCodec.of(
            { p, b ->
                b.writeUuid(p.orderId); b.writeInt(p.count); b.writeInt(p.price)
                PacketCodecs.NBT_COMPOUND.encode(b, p.variantNbt)
            },
            { b -> DeliverItemBuyOrderPayload(b.readUuid(), b.readInt(), b.readInt(), PacketCodecs.NBT_COMPOUND.decode(b)) }
        )
    }
}

// ── C2S：买家接受待确认交付（结算：扣冻结金实际价+退差价、卖家收钱-费、货进买家待领取） ──

data class AcceptPendingDeliverPayload(
    val orderId: UUID,
    val deliveryId: UUID
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AcceptPendingDeliverPayload>(CobbleMarket.id("accept_pending_deliver"))
        val CODEC: PacketCodec<PacketByteBuf, AcceptPendingDeliverPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.orderId); b.writeUuid(p.deliveryId) },
            { b -> AcceptPendingDeliverPayload(b.readUuid(), b.readUuid()) }
        )
    }
}

// ── C2S：买家拒绝待确认交付（货退回卖家待领取，订单保持 OPEN；reason = 给卖家的留言，可为空） ──

data class RejectPendingDeliverPayload(
    val orderId: UUID,
    val deliveryId: UUID,
    val reason: String
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RejectPendingDeliverPayload>(CobbleMarket.id("reject_pending_deliver"))
        val CODEC: PacketCodec<PacketByteBuf, RejectPendingDeliverPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.orderId); b.writeUuid(p.deliveryId); b.writeString(p.reason) },
            { b -> RejectPendingDeliverPayload(b.readUuid(), b.readUuid(), b.readString()) }
        )
    }
}

object BuyOrderNetwork {

    /** 备注最大长度（字符，服务端钳制；客户端输入框同值限制） */
    const val NOTE_MAX_LENGTH = 100

    fun register() {
        registerS2CType(BuyOrderListDataPayload.ID, BuyOrderListDataPayload.CODEC)
        registerS2CType(BuyOrderEventPayload.ID, BuyOrderEventPayload.CODEC)

        // 到期自动关闭：每秒轮询（照拍卖结算定时器），退款+广播不依赖玩家打开界面
        var tick = 0
        onServerTickEnd { server ->
            tick++
            if (tick < 20) return@onServerTickEnd
            tick = 0
            settleExpiredAndBroadcast(server)
        }

        registerC2S(RequestBuyOrderListPayload.ID, RequestBuyOrderListPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "buy_order_list", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                settleExpiredAndBroadcast(server)
                sendToPlayer(
                    player,
                    BuyOrderListDataPayload(BuyOrderState.get(server).getOpenOrders().map { buyOrderToEntry(it) })
                )
            }
        }

        registerC2S(CreatePokemonBuyOrderPayload.ID, CreatePokemonBuyOrderPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "create_pokemon_buy_order", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                if (!checkPrices(player, payload.minPrice, payload.maxPrice)) return@execute
                if (!checkOrderLimit(server, player)) return@execute
                // 形态列表上限：恶意客户端可发海量 aspects 随订单持久化 + 全服广播（正常客户端一个物种形态最多几十个）
                if (payload.aspects.size > PokemonBlacklistEntry.MAX_ASPECTS) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                // 物种解析：空 = 任意精灵；解析失败明确反馈
                val speciesId = payload.speciesId.trim().ifEmpty { null }
                var speciesKey: String? = null
                if (speciesId != null) {
                    val resolved = com.shusheng.cobblemarket.util.SpeciesText.resolveByNameOrId(speciesId)
                    if (resolved == null) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.species_not_found")))
                        return@execute
                    }
                    speciesKey = resolvedSpeciesKey(resolved) ?: return@execute
                }
                // 冻结金 = maxPrice × 1（精灵固定 1 只），扣款失败即余额不足
                if (!CurrencyHandler.remove(player, payload.maxPrice)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.not_enough")))
                    return@execute
                }
                val now = System.currentTimeMillis()
                val order = BuyOrder(
                    id = UUID.randomUUID(),
                    buyerUuid = player.uuid,
                    buyerName = player.name.string,
                    type = BuyOrderType.POKEMON,
                    speciesId = speciesId,
                    speciesKey = speciesKey,
                    shinyFilter = payload.shinyFilter.coerceIn(PokemonBlacklistEntry.SHINY_ANY, PokemonBlacklistEntry.SHINY_YES),
                    htFilter = payload.htFilter.coerceIn(PokemonBlacklistEntry.HT_ANY, PokemonBlacklistEntry.HT_NONE),
                    ivHp = payload.ivHp.coerceIn(-1, 31),
                    ivAtk = payload.ivAtk.coerceIn(-1, 31),
                    ivDef = payload.ivDef.coerceIn(-1, 31),
                    ivSpAtk = payload.ivSpAtk.coerceIn(-1, 31),
                    ivSpDef = payload.ivSpDef.coerceIn(-1, 31),
                    ivSpd = payload.ivSpd.coerceIn(-1, 31),
                    // 归一化：含 "*" 即不限形态，其余 aspect 原样存
                    aspects = if (PokemonBlacklistEntry.ALL_FORMS in payload.aspects) listOf(PokemonBlacklistEntry.ALL_FORMS) else payload.aspects,
                    abilityKey = payload.abilityKey.trim().ifEmpty { null },
                    natureKey = payload.natureKey.trim().ifEmpty { null },
                    itemId = "",
                    totalCount = 1,
                    remainingCount = 1,
                    minPrice = payload.minPrice,
                    maxPrice = payload.maxPrice,
                    // 备注长度服务端钳制（防刷屏/超长 NBT）
                    note = payload.note.take(NOTE_MAX_LENGTH),
                    createdAt = now,
                    expiresAt = now + CobbleMarketConfig.buyOrderExpiryDays * 24L * 60 * 60 * 1000,
                    status = BuyOrderStatus.OPEN,
                    returnedAt = null
                )
                BuyOrderState.get(server).addOrder(order)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.created")))
                broadcastEvent(server, "NEW", buyOrderToEntry(order))
                recordOrder(server, order, TransactionType.ORDER)
            }
        }

        registerC2S(CreateItemBuyOrderPayload.ID, CreateItemBuyOrderPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "create_item_buy_order", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                if (!checkPrices(player, payload.minPrice, payload.maxPrice)) return@execute
                if (!checkOrderLimit(server, player)) return@execute
                // 手持物品模式：用手持物品（含组件快照）作为求购要求；否则用输入框的 itemId
                val heldStack = if (payload.useHeldItem) player.mainHandStack.copy() else net.minecraft.item.ItemStack.EMPTY
                val itemId = if (payload.useHeldItem) {
                    if (heldStack.isEmpty) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.held_item_empty")))
                        return@execute
                    }
                    Registries.ITEM.getId(heldStack.item).toString()
                } else payload.itemId.trim()
                val itemIdentifier = net.minecraft.util.Identifier.tryParse(itemId)
                if (itemIdentifier == null || !Registries.ITEM.getOrEmpty(itemIdentifier).isPresent) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.item_not_found")))
                    return@execute
                }
                // 组件快照：手持路径读主手提取；搜索路径用客户端传的快照重建后重新提取白名单组件（不盲信）
                val itemComponentsSpec = if (payload.useHeldItem) {
                    com.shusheng.cobblemarket.market.ItemRuleComponents.extractSpec(heldStack, player.serverWorld.registryManager)
                } else com.shusheng.cobblemarket.market.ItemRuleComponents.sanitizeSpec(
                    itemIdentifier.toString(), payload.componentsSpec, player.serverWorld.registryManager
                )
                val totalCount = payload.totalCount.coerceAtLeast(1)
                // 冻结金上限钳制：CurrencyHandler.remove 为 Int 扣款，超限拒绝（防溢出吞钱）
                val frozen = payload.maxPrice.toLong() * totalCount
                if (frozen > Int.MAX_VALUE) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.too_large")))
                    return@execute
                }
                if (!CurrencyHandler.remove(player, frozen.toInt())) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.not_enough")))
                    return@execute
                }
                val now = System.currentTimeMillis()
                val order = BuyOrder(
                    id = UUID.randomUUID(),
                    buyerUuid = player.uuid,
                    buyerName = player.name.string,
                    type = BuyOrderType.ITEM,
                    speciesId = null,
                    speciesKey = null,
                    shinyFilter = PokemonBlacklistEntry.SHINY_ANY,
                    htFilter = PokemonBlacklistEntry.HT_ANY,
                    ivHp = -1, ivAtk = -1, ivDef = -1,
                    ivSpAtk = -1, ivSpDef = -1, ivSpd = -1,
                    aspects = emptyList(),
                    abilityKey = null,
                    natureKey = null,
                    itemId = itemIdentifier.toString(),
                    itemComponentsSpec = itemComponentsSpec,
                    totalCount = totalCount,
                    remainingCount = totalCount,
                    minPrice = payload.minPrice,
                    maxPrice = payload.maxPrice,
                    note = payload.note.take(NOTE_MAX_LENGTH),
                    createdAt = now,
                    expiresAt = now + CobbleMarketConfig.buyOrderExpiryDays * 24L * 60 * 60 * 1000,
                    status = BuyOrderStatus.OPEN,
                    returnedAt = null
                )
                BuyOrderState.get(server).addOrder(order)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.created")))
                broadcastEvent(server, "NEW", buyOrderToEntry(order))
                recordOrder(server, order, TransactionType.ORDER)
            }
        }

        registerC2S(CancelBuyOrderPayload.ID, CancelBuyOrderPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "cancel_buy_order", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 封禁只拦交易；关闭求购单是取回自己的冻结金，允许（照取消挂单语义）
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                if (order.buyerUuid != player.uuid) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.cmd.not_your_listing")))
                    return@execute
                }
                val refund = order.frozenRemaining()
                if (!state.closeByBuyer(server, order)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                sendToPlayer(
                    player,
                    MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.closed", CurrencyHandler.goldAmount(refund), CurrencyHandler.goldCurrencyText()))
                )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                broadcastEvent(server, "CLOSED", buyOrderToEntry(order))
                recordOrder(server, order, TransactionType.CANCEL, "user")
            }
        }

        registerC2S(ForceCancelBuyOrderPayload.ID, ForceCancelBuyOrderPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "force_cancel_buy_order", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                // 下架前记下待确认交付的卖家（closeByBuyer 会退回货物并清空 pending）
                val pendingSellers = order.pendingDeliveries.map { it.sellerUuid }.toSet()
                if (!state.closeByBuyer(server, order)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                // 通知买家（冻结金已退还）与待确认交付的卖家（货物已退回）；离线则入队补发
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                    server, order.buyerUuid,
                    Text.translatable("cobblemarket.buy_order.force_cancelled_buyer").formatted(Formatting.RED)
                )
                pendingSellers.forEach { seller ->
                    com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                        server, seller,
                        Text.translatable("cobblemarket.buy_order.force_cancelled_seller").formatted(Formatting.RED)
                    )
                }
                sendToPlayer(
                    player,
                    MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.force_cancelled_msg", order.buyerName, order.requirementText()))
                )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                broadcastEvent(server, "CLOSED", buyOrderToEntry(order))
                recordOrder(server, order, TransactionType.CANCEL, "admin")
            }
        }

        registerC2S(DeliverPokemonBuyOrderPayload.ID, DeliverPokemonBuyOrderPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "deliver_pokemon_buy_order", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.type != BuyOrderType.POKEMON) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                // 不能交付自己的单：自买自卖只会白扣中介费
                if (order.buyerUuid == player.uuid) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.cannot_deliver_own")))
                    return@execute
                }
                // 已有待确认交付时锁定新交付（买家确认/拒绝后才解锁）
                if (order.pendingDeliveries.isNotEmpty()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.pending_locked")))
                    return@execute
                }
                if (payload.price < order.minPrice || payload.price > order.maxPrice) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_out_of_range")))
                    return@execute
                }

                val party = Cobblemon.storage.getParty(player)
                val pc = Cobblemon.storage.getPC(player)
                var pokemon = party.find { it.uuid == payload.pokemonUuid }
                val fromParty = pokemon != null
                if (pokemon == null) pokemon = pc.find { it.uuid == payload.pokemonUuid }
                if (pokemon == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                // 对战中不可交付求购单（整个队伍）：战斗系统动态读队伍，抽走任何精灵都可能造成战斗内模型消失或变相复制
                if (com.shusheng.cobblemarket.market.BattleGuard.isPlayerInBattle(player.uuid)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.in_battle")))
                    return@execute
                }
                if (fromParty && party.occupied() <= 1) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.party_last")))
                    return@execute
                }
                if (!order.matchesPokemon(pokemon)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.not_matching")))
                    return@execute
                }
                // 治理校验（照拍卖上架路径）：黑名单 + 携带物黑名单 + 价格限制
                // （价格限制不含特性/性格维度，实际交付精灵按 形态/V数/闪光/特训 匹配规则取 bounds）
                if (PokemonBlacklistState.get(server).isBlacklisted(pokemon)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.blocked")))
                    return@execute
                }
                val heldItem = pokemon.heldItem()
                val heldItemId = if (heldItem.isEmpty) null
                    else Registries.ITEM.getId(heldItem.item).toString()
                if (heldItemId != null && ItemBlacklistState.get(server)
                        .matches(heldItem, player.serverWorld.registryManager)
                ) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.held_item_blocked")))
                    return@execute
                }
                val pokemonBounds = com.shusheng.cobblemarket.market.PokemonPriceLimitState.get(server)
                    .getPriceBounds(pokemon)
                val itemBounds = if (heldItemId != null)
                    com.shusheng.cobblemarket.market.ItemPriceLimitState.get(server)
                        .getPriceBounds(heldItem, player.serverWorld.registryManager)
                else null
                val bounds = com.shusheng.cobblemarket.market.mergePriceBounds(pokemonBounds, itemBounds)
                if (bounds != null) {
                    // 空区间 = 多条同档限价规则交叉锁死，任何价格都过不了校验：明确告知而不是轮流报上下限
                    if (bounds.isEmptyRange) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.conflict")))
                        return@execute
                    }
                    if (bounds.min != null && payload.price < bounds.min) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_limit_below", CurrencyHandler.formatAmount(bounds.min))))
                        return@execute
                    }
                    if (bounds.max != null && payload.price > bounds.max) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_limit_above", CurrencyHandler.formatAmount(bounds.max))))
                        return@execute
                    }
                }

                // 先完成所有可能失败的操作（序列化 + 数据构建）
                val nbt = try {
                    pokemon.saveToNBT(player.serverWorld.registryManager, NbtCompound())
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to serialize pokemon {} for buy order {} delivery by {}: {}", payload.pokemonUuid, order.id, player.uuid, e.message)
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                val now = System.currentTimeMillis()

                // 副作用阶段：移除精灵 → 构建待确认交付挂入订单（货悬空，买家确认时结算）
                val removed = try {
                    if (fromParty) party.remove(pokemon) else pc.remove(pokemon)
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to remove pokemon {} for buy order {} delivery by {}: {}", payload.pokemonUuid, order.id, player.uuid, e.message)
                    false
                }
                if (!removed) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                order.pendingDeliveries.add(
                    PendingDelivery(
                        id = UUID.randomUUID(),
                        sellerUuid = player.uuid,
                        sellerName = player.name.string,
                        price = payload.price,
                        pokemonNbt = nbt,
                        species = pokemon.species.name,
                        level = pokemon.level,
                        shiny = pokemon.shiny,
                        extraData = buildDeliveryExtra(pokemon, heldItem),
                        itemNbt = null,
                        count = 1,
                        deliveredAt = now
                    )
                )
                state.markModified()
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.delivered_pending")))
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(server, order.buyerUuid,
                    pendingReviewNotice(order))
                broadcastEvent(server, "UPDATED", buyOrderToEntry(order))
            }
        }

        registerC2S(DeliverItemBuyOrderPayload.ID, DeliverItemBuyOrderPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "deliver_item_buy_order", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.type != BuyOrderType.ITEM) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.buyerUuid == player.uuid) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.cannot_deliver_own")))
                    return@execute
                }
                // 已有待确认交付时锁定新交付（买家确认/拒绝后才解锁）
                if (order.pendingDeliveries.isNotEmpty()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.pending_locked")))
                    return@execute
                }
                if (payload.count <= 0 || payload.count > order.remainingCount) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.wrong_count")))
                    return@execute
                }
                if (payload.price < order.minPrice || payload.price > order.maxPrice) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_out_of_range")))
                    return@execute
                }
                // 治理即时生效：蛋交易开关关闭/物品黑名单/价格限制拦截存量订单的交付
                if (isEggItem(order.itemId) && !CobbleMarketConfig.eggTradingEnabled) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.egg_trading_disabled")))
                    return@execute
                }
                // 参考栈 = 客户端所选形态（经 ItemVariantSelectScreen 选择后随包发送的序列化），
                // 服务端重建为权威栈（不信任客户端一致性）。求购单只指定 itemId，但同一次交付
                // 必须组件一致——否则「32 锋利V + 32 保护I」会被按参考栈标准化成 64 个锋利V，
                // 凭空放大价值（拍卖/上架路径同样按组件匹配）。
                val main = player.inventory.main
                val referenceStack = try {
                    ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, payload.variantNbt)
                        .takeIf { !it.isEmpty && Registries.ITEM.getId(it.item).toString() == order.itemId }
                } catch (e: Exception) {
                    null
                }
                if (referenceStack == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }

                // 求购单组件要求校验：买家指定组件快照（如「锋利V」）时，交付物品组件须包含之
                if (!com.shusheng.cobblemarket.market.ItemRuleComponents.matches(
                        referenceStack, order.itemId, order.itemComponentsSpec, player.serverWorld.registryManager
                    )) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.item_variant_not_matching")))
                    return@execute
                }
                // 治理即时生效：物品黑名单/价格限制拦截存量订单的交付（组件粒度）
                if (ItemBlacklistState.get(server).matches(referenceStack, player.serverWorld.registryManager)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.item_blocked")))
                    return@execute
                }
                // 物品价格限制是单价语义：交付单价直接对照 bounds
                val itemBounds = com.shusheng.cobblemarket.market.ItemPriceLimitState.get(server)
                    .getPriceBounds(referenceStack, player.serverWorld.registryManager)
                if (itemBounds != null) {
                    // 空区间 = 多条同档限价规则交叉锁死，任何价格都过不了校验：明确告知而不是轮流报上下限
                    if (itemBounds.isEmptyRange) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.conflict")))
                        return@execute
                    }
                    if (itemBounds.min != null && payload.price < itemBounds.min) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_limit_below", CurrencyHandler.formatAmount(itemBounds.min))))
                        return@execute
                    }
                    if (itemBounds.max != null && payload.price > itemBounds.max) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_limit_above", CurrencyHandler.formatAmount(itemBounds.max))))
                        return@execute
                    }
                }

                // 容器内容校验：交付的容器物品内不得含黑名单/限价物品/未开开关的蛋（防塞箱绕过）
                val containerReject = com.shusheng.cobblemarket.market.ContainerTradeCheck.check(referenceStack, server)
                if (containerReject != null) {
                    sendToPlayer(player, MarketResultPayload(false, containerReject))
                    return@execute
                }
                // 背包足额：只统计与参考栈组件一致的部分
                var available = 0
                for (i in 0 until main.size) {
                    val stack = main[i]
                    if (itemsEqualForTrading(stack, referenceStack)) available += stack.count
                }
                if (available < payload.count) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.item_variant_not_enough")))
                    return@execute
                }
                // 物品参考栈 NBT（待领取重建用，带组件物品保真）
                val listingNbt = try {
                    referenceStack.encode(player.serverWorld.registryManager) as? NbtCompound
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to encode item stack for buy order {} delivery by {}: {}", order.id, player.uuid, e.message)
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                if (listingNbt == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }

                // 副作用阶段：扣物品（只扣与参考栈组件一致的，照物品上架路径）
                var remaining = payload.count
                for (i in 0 until main.size) {
                    val stack = main[i]
                    if (itemsEqualForTrading(stack, referenceStack)) {
                        val r = minOf(remaining, stack.count)
                        stack.decrement(r)
                        remaining -= r
                        if (remaining <= 0) break
                    }
                }
                if (remaining > 0) {
                    // 防御性校验：available 预检已保证足额，正常不可达；异常时退还已扣部分
                    giveBackItem(ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, listingNbt).copyWithCount(payload.count - remaining), player)
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                player.inventory.markDirty()
                player.currentScreenHandler.sendContentUpdates()
                val now = System.currentTimeMillis()

                // 构建待确认交付挂入订单（货悬空，买家确认时结算）
                order.pendingDeliveries.add(
                    PendingDelivery(
                        id = UUID.randomUUID(),
                        sellerUuid = player.uuid,
                        sellerName = player.name.string,
                        price = payload.price,
                        pokemonNbt = null,
                        species = "",
                        level = 0,
                        shiny = false,
                        extraData = emptyMap(),
                        itemNbt = listingNbt,
                        count = payload.count,
                        deliveredAt = now
                    )
                )
                state.markModified()
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.delivered_items_pending", payload.count))
                )
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(server, order.buyerUuid,
                    pendingReviewNotice(order))
                broadcastEvent(server, "UPDATED", buyOrderToEntry(order))
            }
        }

        registerC2S(AcceptPendingDeliverPayload.ID, AcceptPendingDeliverPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "accept_pending_deliver", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 接受 = 交易行为，封禁拦截
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.buyerUuid != player.uuid) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.cmd.not_your_listing")))
                    return@execute
                }
                val pending = order.pendingDeliveries.firstOrNull { it.id == payload.deliveryId }
                if (pending == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                val now = System.currentTimeMillis()
                // 货进买家待领取（失败保持 pending 原状，买家可重试）
                val enqueued = state.returnPending(server, order, pending, order.buyerUuid, order.buyerName, ListingStatus.SOLD)
                if (!enqueued) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                // 资金结算：买家冻结金按实际价扣（差价退待领），卖家收实际价-中介费
                val unitDiff = (order.maxPrice - pending.price).toLong()
                val gross = pending.price.toLong() * pending.count
                val fee = deliveryFee(server, order.buyerUuid, gross, now)
                MarketState.get(server).addPendingBalance(order.buyerUuid, unitDiff * pending.count)
                MarketState.get(server).addPendingBalance(pending.sellerUuid, gross - fee)
                // 金融系统成交挂钩子：求购交付成交计入买家（求购发起方）交易额
                com.shusheng.cobblemarket.finance.FinanceState.get(server).recordTrade(
                    server, order.buyerUuid, pending.sellerUuid, gross, now
                )
                order.pendingDeliveries.remove(pending)
                order.remainingCount -= pending.count
                val category = if (order.type == BuyOrderType.POKEMON)
                    com.shusheng.cobblemarket.event.TransactionCategory.POKEMON
                else
                    com.shusheng.cobblemarket.event.TransactionCategory.ITEM
                if (order.remainingCount <= 0) {
                    order.status = BuyOrderStatus.CLOSED
                    order.returnedAt = now
                    state.removeOrder(order.id)
                }
                state.markModified()
                try {
                    com.shusheng.cobblemarket.event.TransactionHistory.get(server).addRecord(
                        com.shusheng.cobblemarket.event.TransactionRecord(
                            timestamp = now,
                            type = com.shusheng.cobblemarket.event.TransactionType.PURCHASE,
                            category = category,
                            sellerUuid = pending.sellerUuid,
                            sellerName = pending.sellerName,
                            buyerUuid = order.buyerUuid,
                            buyerName = order.buyerName,
                            species = if (order.type == BuyOrderType.POKEMON)
                                (pending.extraData["speciesKey"] ?: pending.species)
                            else order.itemId,
                            price = gross.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            fee = fee.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            detail = if (order.type == BuyOrderType.POKEMON)
                                com.shusheng.cobblemarket.util.RecordDetail.pokemon(pending.extraData, pending.level, pending.shiny)
                            else
                                com.shusheng.cobblemarket.util.RecordDetail.item(pending.itemNbt, pending.count)
                        )
                    )
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.warn("Failed to record buy order accepted delivery {}: {}", order.id, e.message)
                }
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.accepted")))
                // 买家庆祝动画：接受交付即所有权转移（货进买家待领取，精灵单固定 1 只）
                if (order.type == BuyOrderType.POKEMON) {
                    CelebrationNetwork.sendFromEntry(player, pending.species, pending.shiny, pending.extraData, CelebrationSource.BUY_ORDER)
                }
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(server, pending.sellerUuid,
                    // 到账金额 + 手续费一并提示：买家付的是 gross，卖家到手 gross-fee，不说清会产生疑问
                    Text.translatable("cobblemarket.buy_order.accepted_seller", order.requirementText(), CurrencyHandler.goldAmount(gross - fee), CurrencyHandler.goldCurrencyText(), CurrencyHandler.goldAmount(fee), CurrencyHandler.goldCurrencyText()).formatted(Formatting.GREEN))
                broadcastEvent(server, if (order.isOpen()) "UPDATED" else "CLOSED", buyOrderToEntry(order))
            }
        }

        registerC2S(RejectPendingDeliverPayload.ID, RejectPendingDeliverPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "reject_pending_deliver", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 拒绝 = 货退回卖家，不涉及买家资产变动，封禁不拦（照取消挂单语义）
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.buyerUuid != player.uuid) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.cmd.not_your_listing")))
                    return@execute
                }
                val pending = order.pendingDeliveries.firstOrNull { it.id == payload.deliveryId }
                if (pending == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                // 货退回卖家待领取（失败保持 pending 原状，买家可重试）
                val enqueued = state.returnPending(server, order, pending, pending.sellerUuid, pending.sellerName, ListingStatus.CANCELLED)
                if (!enqueued) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                order.pendingDeliveries.remove(pending)
                state.markModified()
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.rejected")))
                // 卖家反馈：被退回 + 买家留言（有留言时附上；卖家离线则上线补发）
                val reason = payload.reason.take(100)
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(server, pending.sellerUuid,
                    if (reason.isNotBlank())
                        Text.translatable("cobblemarket.buy_order.rejected_seller_reason",
                            order.buyerName, order.requirementText(), reason).formatted(Formatting.YELLOW)
                    else
                        Text.translatable("cobblemarket.buy_order.rejected_seller",
                            order.buyerName, order.requirementText()).formatted(Formatting.YELLOW)
                )
                broadcastEvent(server, "UPDATED", buyOrderToEntry(order))
            }
        }
    }

    // ── 共享辅助 ──

    /** 结算到期求购单并广播 CLOSED 事件（数据请求入口与定时器共用，实现惰性+周期双结算） */
    fun settleExpiredAndBroadcast(server: MinecraftServer) {
        val closed = BuyOrderState.get(server).settleExpiredOrders(server, System.currentTimeMillis())
        if (closed.isNotEmpty()) {
            // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
            com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
        }
        closed.forEach { order ->
            broadcastEvent(server, "CLOSED", buyOrderToEntry(order))
            com.shusheng.cobblemarket.market.OfflineMessageState.notify(server, order.buyerUuid,
                Text.translatable("cobblemarket.buy_order.expired", order.requirementText()).formatted(Formatting.YELLOW))
            recordOrder(server, order, TransactionType.CANCEL, "expired")
        }
    }

    private fun broadcastEvent(server: MinecraftServer, event: String, entry: BuyOrderEntry) {
        server.playerManager.playerList.forEach { p ->
            sendToPlayer(p, BuyOrderEventPayload(event, entry))
        }
    }

    /** 中介费：成交总额 × 费率，向上取整（照拍卖成交费算法；买家逾期 ≥7 天翻倍） */
    private fun deliveryFee(server: MinecraftServer, payerUuid: java.util.UUID, gross: Long, now: Long): Long {
        val feePercent = CobbleMarketConfig.buyOrderFeePercent
        val base = if (feePercent > 0)
            Math.ceil(gross * feePercent / 100.0).toLong().coerceAtMost(Int.MAX_VALUE.toLong())
        else 0L
        return com.shusheng.cobblemarket.finance.FinanceService.applyHolderDiscount(
            com.shusheng.cobblemarket.finance.FinanceState.get(server),
            payerUuid,
            com.shusheng.cobblemarket.finance.FinanceService.applyFeeMultiplier(
                com.shusheng.cobblemarket.finance.FinanceState.get(server), payerUuid, now, base
            )
        )
    }

    private fun checkPrices(player: net.minecraft.server.network.ServerPlayerEntity, minPrice: Int, maxPrice: Int): Boolean {
        if (minPrice >= 1 && maxPrice >= minPrice) return true
        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_price")))
        return false
    }

    /**
     * 求购单数量上限（精灵与物品合计，照拍卖 maxAuctionsPerPlayer）。
     * 求购单列表是全量下发给所有客户端的，没有上限时单个玩家就能把全服的列表撑大。
     */
    private fun checkOrderLimit(server: MinecraftServer, player: net.minecraft.server.network.ServerPlayerEntity): Boolean {
        val max = CobbleMarketConfig.maxBuyOrdersPerPlayer
        if (max > 0 && BuyOrderState.get(server).countOpenByBuyer(player.uuid) >= max) {
            sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.max_active", max)))
            return false
        }
        return true
    }

    private fun banBlocked(server: MinecraftServer, player: net.minecraft.server.network.ServerPlayerEntity): Boolean {
        val banInfo = BanState.get(server).getBanInfo(player.uuid, System.currentTimeMillis())
        if (banInfo == null) return false
        // 保留 Text 对象而非 .string：翻译在客户端语言下渲染
        val timeDesc: Text = if (banInfo.isPermanent)
            Text.translatable("cobblemarket.ban.permanent")
        else
            Text.translatable("cobblemarket.ban.remaining", BanState.formatRemaining(banInfo.expiresAt!! - System.currentTimeMillis()))
        val banMsg = if (banInfo.reason.isNotBlank())
            Text.translatable("cobblemarket.ban.banned_msg_time_reason", timeDesc, com.shusheng.cobblemarket.market.BanState.reasonText(banInfo.reason))
        else
            Text.translatable("cobblemarket.ban.banned_msg_time", timeDesc)
        sendToPlayer(player, MarketResultPayload(false, banMsg))
        return true
    }

    private fun resolvedSpeciesKey(resolvedSpeciesId: String): String? {
        val species = com.cobblemon.mod.common.api.pokemon.PokemonSpecies.implemented
            .firstOrNull { it.resourceIdentifier.toString() == resolvedSpeciesId }
        if (species == null) {
            CobbleMarket.LOGGER.warn("Resolved species {} not found in registry", resolvedSpeciesId)
            return null
        }
        return com.shusheng.cobblemarket.util.SpeciesText.translationKey(species)
    }

    /** 交付精灵的待领取展示字段（照拍卖/上架的 extraData 字段集，保证待领取列表渲染一致） */
    private fun buildDeliveryExtra(
        pokemon: com.cobblemon.mod.common.pokemon.Pokemon,
        heldItemStack: ItemStack
    ): Map<String, String> {
        val htIvs = pokemon.ivs.hyperTrainedIVs // 极限特训值表（-1 = 未特训）
        val extra = mutableMapOf(
            "speciesId" to pokemon.species.resourceIdentifier.toString(),
            "speciesName" to pokemon.species.translatedName.string,
            "speciesKey" to com.shusheng.cobblemarket.util.SpeciesText.translationKey(pokemon.species),
            "primaryType" to "cobblemon.type.${pokemon.primaryType.name.lowercase()}",
            "ivsHp" to pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.HP].toString(),
            "ivsAtk" to pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK].toString(),
            "ivsDef" to pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE].toString(),
            "ivsSpAtk" to pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK].toString(),
            "ivsSpDef" to pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE].toString(),
            "ivsSpd" to pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED].toString(),
            "htHp" to (htIvs[com.cobblemon.mod.common.api.pokemon.stats.Stats.HP] ?: -1).toString(),
            "htAtk" to (htIvs[com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK] ?: -1).toString(),
            "htDef" to (htIvs[com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE] ?: -1).toString(),
            "htSpAtk" to (htIvs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK] ?: -1).toString(),
            "htSpDef" to (htIvs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE] ?: -1).toString(),
            "htSpd" to (htIvs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED] ?: -1).toString(),
            "nature" to "cobblemon.nature.${pokemon.effectiveNature.name.path}",
            // 原生性格（薄荷不改）：与 nature 不同 = 用过薄荷，客户端斜体显示
            "natureBase" to "cobblemon.nature.${pokemon.nature.name.path}",
            "ability" to "cobblemon.ability.${pokemon.ability.name}",
            "gender" to pokemon.gender.name,
            "ball" to "item.${pokemon.caughtBall.name.namespace}.${pokemon.caughtBall.name.path}",
            "ballItem" to pokemon.caughtBall.name.toString(),
            "heldItemId" to (if (heldItemStack.isEmpty) "" else Registries.ITEM.getId(heldItemStack.item).toString()),
            "aspects" to pokemon.aspects.joinToString(","),
            "marks" to pokemon.marks.map { it.texture.toString() }.joinToString(",")
        )
        pokemon.secondaryType?.let { extra["secondaryType"] = "cobblemon.type.${it.name.lowercase()}" }
        return extra
    }

    /** 背包返还兜底（照上架路径的退还逻辑） */
    private fun giveBackItem(stack: ItemStack, player: net.minecraft.server.network.ServerPlayerEntity) {
        if (stack.isEmpty) return
        player.inventory.insertStack(stack)
        if (!stack.isEmpty) {
            // 背包满兜底：直接丢到玩家脚下，绝不吞玩家物品
            val dropped = player.dropItem(stack, true)
            if (dropped != null) dropped.setToDefaultPickupDelay()
        }
        player.inventory.markDirty()
    }

    /** CSV 账本记录：发布=求购（ORDER，发起者占卖家列）、关闭/过期/强制下架=下架（reason 区分）；交付成交已有 PURCHASE 记录 */
    private fun recordOrder(server: MinecraftServer, order: BuyOrder, type: TransactionType, reason: String? = null) {
        val category = if (order.type == BuyOrderType.POKEMON) TransactionCategory.POKEMON else TransactionCategory.ITEM
        TransactionHistory.get(server).addRecord(TransactionRecord(
            timestamp = System.currentTimeMillis(),
            type = type,
            category = category,
            // 求购单无货物：发起者（买家）占卖家列，对账按名字筛选
            sellerUuid = order.buyerUuid,
            sellerName = order.buyerName,
            buyerUuid = null,
            buyerName = "",
            species = if (order.type == BuyOrderType.POKEMON) (order.speciesKey ?: "any") else order.itemId,
            price = order.maxPrice,
            fee = 0,
            detail = reason ?: ""
        ))
    }

    /** 求购单待确认通知（绿，聊天颜色模板）+ 金色下划线 [查看待交付] 按钮：
     *  点击走客户端指令 /cobblemarket review <orderId>，打开界面直达确认交付弹窗（看货后再决定接受/拒绝） */
    private fun pendingReviewNotice(order: com.shusheng.cobblemarket.market.BuyOrder): Text {
        val button = Text.translatable("cobblemarket.chat.review_button").styled {
            it.withColor(Formatting.GOLD)
                .withUnderline(true)
                .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cobblemarket review ${order.id}"))
        }
        return Text.translatable("cobblemarket.buy_order.pending_buyer", order.requirementText()).formatted(Formatting.GREEN)
            .append(Text.literal(" ")).append(button)
    }
}
