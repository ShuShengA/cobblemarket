package com.shusheng.cobblemarket.network

import com.cobblemon.mod.common.Cobblemon
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.config.CurrencyHandler
import com.shusheng.cobblemarket.market.BanState
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
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
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
    val count: Int             // 物品件数（精灵 = 1）
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
            count = buf.readInt()
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
            count = p.count
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
    val note: String           // 买家备注（可为空）
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CreateItemBuyOrderPayload>(CobbleMarket.id("create_item_buy_order"))
        val CODEC: PacketCodec<PacketByteBuf, CreateItemBuyOrderPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.itemId); b.writeInt(p.totalCount); b.writeInt(p.minPrice); b.writeInt(p.maxPrice)
                b.writeString(p.note)
            },
            { b -> CreateItemBuyOrderPayload(b.readString(), b.readInt(), b.readInt(), b.readInt(), b.readString()) }
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
    val price: Int
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<DeliverItemBuyOrderPayload>(CobbleMarket.id("deliver_item_buy_order"))
        val CODEC: PacketCodec<PacketByteBuf, DeliverItemBuyOrderPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.orderId); b.writeInt(p.count); b.writeInt(p.price) },
            { b -> DeliverItemBuyOrderPayload(b.readUuid(), b.readInt(), b.readInt()) }
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
        PayloadTypeRegistry.playC2S().register(RequestBuyOrderListPayload.ID, RequestBuyOrderListPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CreatePokemonBuyOrderPayload.ID, CreatePokemonBuyOrderPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CreateItemBuyOrderPayload.ID, CreateItemBuyOrderPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CancelBuyOrderPayload.ID, CancelBuyOrderPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(DeliverPokemonBuyOrderPayload.ID, DeliverPokemonBuyOrderPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(DeliverItemBuyOrderPayload.ID, DeliverItemBuyOrderPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(AcceptPendingDeliverPayload.ID, AcceptPendingDeliverPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(RejectPendingDeliverPayload.ID, RejectPendingDeliverPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BuyOrderListDataPayload.ID, BuyOrderListDataPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BuyOrderEventPayload.ID, BuyOrderEventPayload.CODEC)

        // 到期自动关闭：每秒轮询（照拍卖结算定时器），退款+广播不依赖玩家打开界面
        var tick = 0
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register { server ->
            tick++
            if (tick < 20) return@register
            tick = 0
            settleExpiredAndBroadcast(server)
        }

        ServerPlayNetworking.registerGlobalReceiver(RequestBuyOrderListPayload.ID) { _, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "buy_order_list", RequestThrottle.READ_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                settleExpiredAndBroadcast(server)
                ServerPlayNetworking.send(
                    player,
                    BuyOrderListDataPayload(BuyOrderState.get(server).getOpenOrders().map { buyOrderToEntry(it) })
                )
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CreatePokemonBuyOrderPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "create_pokemon_buy_order", RequestThrottle.WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                if (!checkPrices(player, payload.minPrice, payload.maxPrice)) return@execute
                if (!checkOrderLimit(server, player)) return@execute
                // 物种解析：空 = 任意精灵；解析失败明确反馈
                val speciesId = payload.speciesId.trim().ifEmpty { null }
                var speciesKey: String? = null
                if (speciesId != null) {
                    val resolved = com.shusheng.cobblemarket.util.SpeciesText.resolveByNameOrId(speciesId)
                    if (resolved == null) {
                        ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.species_not_found")))
                        return@execute
                    }
                    speciesKey = resolvedSpeciesKey(resolved) ?: return@execute
                }
                // 冻结金 = maxPrice × 1（精灵固定 1 只），扣款失败即余额不足
                if (!CurrencyHandler.remove(player, payload.maxPrice)) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.not_enough")))
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
                ServerPlayNetworking.send(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.created")))
                broadcastEvent(server, "NEW", buyOrderToEntry(order))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CreateItemBuyOrderPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "create_item_buy_order", RequestThrottle.WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                if (!checkPrices(player, payload.minPrice, payload.maxPrice)) return@execute
                if (!checkOrderLimit(server, player)) return@execute
                val itemId = payload.itemId.trim()
                val itemIdentifier = net.minecraft.util.Identifier.tryParse(itemId)
                if (itemIdentifier == null || !Registries.ITEM.getOrEmpty(itemIdentifier).isPresent) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.item_not_found")))
                    return@execute
                }
                val totalCount = payload.totalCount.coerceAtLeast(1)
                // 冻结金上限钳制：CurrencyHandler.remove 为 Int 扣款，超限拒绝（防溢出吞钱）
                val frozen = payload.maxPrice.toLong() * totalCount
                if (frozen > Int.MAX_VALUE) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.too_large")))
                    return@execute
                }
                if (!CurrencyHandler.remove(player, frozen.toInt())) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.not_enough")))
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
                ServerPlayNetworking.send(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.created")))
                broadcastEvent(server, "NEW", buyOrderToEntry(order))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CancelBuyOrderPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "cancel_buy_order", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                // 封禁只拦交易；关闭求购单是取回自己的冻结金，允许（照取消挂单语义）
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                if (order.buyerUuid != player.uuid) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.cmd.not_your_listing")))
                    return@execute
                }
                val refund = order.frozenRemaining()
                if (!state.closeByBuyer(server, order)) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                ServerPlayNetworking.send(
                    player,
                    MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.closed", refund, CurrencyHandler.currencyText()))
                )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                broadcastEvent(server, "CLOSED", buyOrderToEntry(order))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(DeliverPokemonBuyOrderPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "deliver_pokemon_buy_order", RequestThrottle.WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.type != BuyOrderType.POKEMON) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                // 不能交付自己的单：自买自卖只会白扣中介费
                if (order.buyerUuid == player.uuid) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.cannot_deliver_own")))
                    return@execute
                }
                // 已有待确认交付时锁定新交付（买家确认/拒绝后才解锁）
                if (order.pendingDeliveries.isNotEmpty()) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.pending_locked")))
                    return@execute
                }
                if (payload.price < order.minPrice || payload.price > order.maxPrice) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_out_of_range")))
                    return@execute
                }

                val party = Cobblemon.storage.getParty(player)
                val pc = Cobblemon.storage.getPC(player)
                var pokemon = party.find { it.uuid == payload.pokemonUuid }
                val fromParty = pokemon != null
                if (pokemon == null) pokemon = pc.find { it.uuid == payload.pokemonUuid }
                if (pokemon == null) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                if (fromParty && party.occupied() <= 1) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.party_last")))
                    return@execute
                }
                if (!order.matchesPokemon(pokemon)) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.not_matching")))
                    return@execute
                }
                // 治理校验（照拍卖上架路径）：黑名单 + 携带物黑名单 + 价格限制
                // （价格限制不含特性/性格维度，实际交付精灵按 形态/V数/闪光/特训 匹配规则取 bounds）
                if (PokemonBlacklistState.get(server).isBlacklisted(pokemon)) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.blocked")))
                    return@execute
                }
                val heldItem = pokemon.heldItem()
                val heldItemId = if (heldItem.isEmpty) null
                    else Registries.ITEM.getId(heldItem.item).toString()
                if (heldItemId != null && ItemBlacklistState.get(server).contains(heldItemId)) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.held_item_blocked")))
                    return@execute
                }
                val pokemonBounds = com.shusheng.cobblemarket.market.PokemonPriceLimitState.get(server)
                    .getPriceBounds(pokemon)
                val itemBounds = heldItemId?.let {
                    com.shusheng.cobblemarket.market.ItemPriceLimitState.get(server).getPriceBounds(it)
                }
                val bounds = com.shusheng.cobblemarket.market.mergePriceBounds(pokemonBounds, itemBounds)
                if (bounds != null) {
                    if (bounds.min != null && payload.price < bounds.min) {
                        ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_limit_below", bounds.min)))
                        return@execute
                    }
                    if (bounds.max != null && payload.price > bounds.max) {
                        ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_limit_above", bounds.max)))
                        return@execute
                    }
                }

                // 先完成所有可能失败的操作（序列化 + 数据构建）
                val nbt = try {
                    pokemon.saveToNBT(player.serverWorld.registryManager, NbtCompound())
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to serialize pokemon {} for buy order {} delivery by {}: {}", payload.pokemonUuid, order.id, player.uuid, e.message)
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
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
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
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
                ServerPlayNetworking.send(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.delivered_pending")))
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(server, order.buyerUuid,
                    Text.translatable("cobblemarket.buy_order.pending_buyer", order.requirementText()).formatted(Formatting.GREEN))
                broadcastEvent(server, "UPDATED", buyOrderToEntry(order))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(DeliverItemBuyOrderPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "deliver_item_buy_order", RequestThrottle.WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.type != BuyOrderType.ITEM) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.buyerUuid == player.uuid) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.cannot_deliver_own")))
                    return@execute
                }
                // 已有待确认交付时锁定新交付（买家确认/拒绝后才解锁）
                if (order.pendingDeliveries.isNotEmpty()) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.pending_locked")))
                    return@execute
                }
                if (payload.count <= 0 || payload.count > order.remainingCount) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.wrong_count")))
                    return@execute
                }
                if (payload.price < order.minPrice || payload.price > order.maxPrice) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_out_of_range")))
                    return@execute
                }
                // 治理即时生效：蛋交易开关关闭/物品黑名单/价格限制拦截存量订单的交付
                if (isEggItem(order.itemId) && !CobbleMarketConfig.eggTradingEnabled) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.egg_trading_disabled")))
                    return@execute
                }
                if (ItemBlacklistState.get(server).contains(order.itemId)) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.item_blocked")))
                    return@execute
                }
                // 物品价格限制是单价语义：交付单价直接对照 bounds
                val itemBounds = com.shusheng.cobblemarket.market.ItemPriceLimitState.get(server)
                    .getPriceBounds(order.itemId)
                if (itemBounds != null) {
                    if (itemBounds.min != null && payload.price < itemBounds.min) {
                        ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_limit_below", itemBounds.min)))
                        return@execute
                    }
                    if (itemBounds.max != null && payload.price > itemBounds.max) {
                        ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_limit_above", itemBounds.max)))
                        return@execute
                    }
                }

                // 参考栈：背包中第一个 itemId 匹配的栈，决定本次交付的具体形态（附魔/名称等组件）。
                // 求购单只指定 itemId，但同一次交付必须组件一致——否则「32 锋利V + 32 保护I」会被
                // 按参考栈标准化成 64 个锋利V，凭空放大价值（拍卖/上架路径同样按组件匹配）。
                // 必须 .copy()：下面扣物品会 decrement 背包里的栈，参考栈若是引用会被扣空，
                // 之后 itemsEqualForTrading 的 b.isEmpty 判定会让剩余栈全部匹配失败。
                val main = player.inventory.main
                val referenceStack = main.firstOrNull {
                    !it.isEmpty && Registries.ITEM.getId(it.item).toString() == order.itemId
                }?.copy()
                if (referenceStack == null) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                // 背包足额：只统计与参考栈组件一致的部分
                var available = 0
                for (i in 0 until main.size) {
                    val stack = main[i]
                    if (itemsEqualForTrading(stack, referenceStack)) available += stack.count
                }
                if (available < payload.count) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.item_variant_not_enough")))
                    return@execute
                }
                // 物品参考栈 NBT（待领取重建用，带组件物品保真）
                val listingNbt = try {
                    referenceStack.encode(player.serverWorld.registryManager) as? NbtCompound
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to encode item stack for buy order {} delivery by {}: {}", order.id, player.uuid, e.message)
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                if (listingNbt == null) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
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
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
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
                ServerPlayNetworking.send(
                    player,
                    MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.delivered_items_pending", payload.count))
                )
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(server, order.buyerUuid,
                    Text.translatable("cobblemarket.buy_order.pending_buyer", order.requirementText()).formatted(Formatting.GREEN))
                broadcastEvent(server, "UPDATED", buyOrderToEntry(order))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(AcceptPendingDeliverPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "accept_pending_deliver", RequestThrottle.WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                // 接受 = 交易行为，封禁拦截
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.buyerUuid != player.uuid) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.cmd.not_your_listing")))
                    return@execute
                }
                val pending = order.pendingDeliveries.firstOrNull { it.id == payload.deliveryId }
                if (pending == null) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                val now = System.currentTimeMillis()
                // 货进买家待领取（失败保持 pending 原状，买家可重试）
                val enqueued = state.returnPending(server, order, pending, order.buyerUuid, order.buyerName, ListingStatus.SOLD)
                if (!enqueued) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                // 资金结算：买家冻结金按实际价扣（差价退待领），卖家收实际价-中介费
                val unitDiff = (order.maxPrice - pending.price).toLong()
                val gross = pending.price.toLong() * pending.count
                val fee = deliveryFee(gross)
                MarketState.get(server).addPendingBalance(order.buyerUuid, unitDiff * pending.count)
                MarketState.get(server).addPendingBalance(pending.sellerUuid, gross - fee)
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
                ServerPlayNetworking.send(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.accepted")))
                // 买家庆祝动画：接受交付即所有权转移（货进买家待领取，精灵单固定 1 只）
                if (order.type == BuyOrderType.POKEMON) {
                    CelebrationNetwork.sendFromEntry(player, pending.species, pending.shiny, pending.extraData, CelebrationSource.BUY_ORDER)
                }
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(server, pending.sellerUuid,
                    Text.translatable("cobblemarket.buy_order.accepted_seller", order.requirementText()).formatted(Formatting.GREEN))
                broadcastEvent(server, if (order.isOpen()) "UPDATED" else "CLOSED", buyOrderToEntry(order))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(RejectPendingDeliverPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "reject_pending_deliver", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                // 拒绝 = 货退回卖家，不涉及买家资产变动，封禁不拦（照取消挂单语义）
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.buyerUuid != player.uuid) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.cmd.not_your_listing")))
                    return@execute
                }
                val pending = order.pendingDeliveries.firstOrNull { it.id == payload.deliveryId }
                if (pending == null) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                // 货退回卖家待领取（失败保持 pending 原状，买家可重试）
                val enqueued = state.returnPending(server, order, pending, pending.sellerUuid, pending.sellerName, ListingStatus.CANCELLED)
                if (!enqueued) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                order.pendingDeliveries.remove(pending)
                state.markModified()
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                ServerPlayNetworking.send(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.rejected")))
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
        }
    }

    private fun broadcastEvent(server: MinecraftServer, event: String, entry: BuyOrderEntry) {
        server.playerManager.playerList.forEach { p ->
            ServerPlayNetworking.send(p, BuyOrderEventPayload(event, entry))
        }
    }

    /** 中介费：成交总额 × 费率，向上取整（照拍卖成交费算法） */
    private fun deliveryFee(gross: Long): Long {
        val feePercent = CobbleMarketConfig.buyOrderFeePercent
        return if (feePercent > 0)
            Math.ceil(gross * feePercent / 100.0).toLong().coerceAtMost(Int.MAX_VALUE.toLong())
        else 0L
    }

    private fun checkPrices(player: net.minecraft.server.network.ServerPlayerEntity, minPrice: Int, maxPrice: Int): Boolean {
        if (minPrice >= 1 && maxPrice >= minPrice) return true
        ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_price")))
        return false
    }

    /**
     * 求购单数量上限（精灵与物品合计，照拍卖 maxAuctionsPerPlayer）。
     * 求购单列表是全量下发给所有客户端的，没有上限时单个玩家就能把全服的列表撑大。
     */
    private fun checkOrderLimit(server: MinecraftServer, player: net.minecraft.server.network.ServerPlayerEntity): Boolean {
        val max = CobbleMarketConfig.maxBuyOrdersPerPlayer
        if (max > 0 && BuyOrderState.get(server).countOpenByBuyer(player.uuid) >= max) {
            ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.max_active", max)))
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
            Text.translatable("cobblemarket.ban.banned_msg_time_reason", timeDesc, banInfo.reason)
        else
            Text.translatable("cobblemarket.ban.banned_msg_time", timeDesc)
        ServerPlayNetworking.send(player, MarketResultPayload(false, banMsg))
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
            "ball" to "item.cobblemon.${pokemon.caughtBall.name.path}",
            "ballItem" to "cobblemon:${pokemon.caughtBall.name.path}",
            "heldItemId" to (if (heldItemStack.isEmpty) "" else Registries.ITEM.getId(heldItemStack.item).toString()),
            "aspects" to pokemon.aspects.joinToString(",")
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
}
