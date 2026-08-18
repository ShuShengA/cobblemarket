package com.shusheng.cobblemarket.network

import com.cobblemon.mod.common.Cobblemon
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.config.CurrencyHandler
import com.shusheng.cobblemarket.market.AuctionBid
import com.shusheng.cobblemarket.market.AuctionListing
import com.shusheng.cobblemarket.market.AuctionState
import com.shusheng.cobblemarket.market.AuctionType
import com.shusheng.cobblemarket.market.MarketState
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.util.RequestThrottle
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID

// ── DTO：精简拍卖条目（不含 NBT/出价明细，客户端列表用） ──

data class AuctionEntry(
    val id: UUID,
    val type: String,          // "POKEMON" / "ITEM"
    val sellerUuid: UUID,
    val sellerName: String,
    val species: String,       // 精灵 = 翻译 key（客户端本地翻译）；物品 = itemId
    val level: Int,
    val shiny: Boolean,
    val count: Int,
    val extraData: Map<String, String>,
    val startingPrice: Int,
    val minIncrement: Int,
    val currentPrice: Int,     // 0 = 无人出价
    val currentBidderUuid: UUID?,
    val currentBidderName: String,
    val bidCount: Int,
    val endsAt: Long
) {
    fun write(buf: PacketByteBuf) {
        buf.writeUuid(id)
        buf.writeString(type)
        buf.writeUuid(sellerUuid)
        buf.writeString(sellerName)
        buf.writeString(species)
        buf.writeInt(level)
        buf.writeBoolean(shiny)
        buf.writeInt(count)
        buf.writeVarInt(extraData.size)
        extraData.forEach { (k, v) -> buf.writeString(k); buf.writeString(v) }
        buf.writeInt(startingPrice)
        buf.writeInt(minIncrement)
        buf.writeInt(currentPrice)
        buf.writeBoolean(currentBidderUuid != null)
        currentBidderUuid?.let { buf.writeUuid(it) }
        buf.writeString(currentBidderName)
        buf.writeInt(bidCount)
        buf.writeLong(endsAt)
    }

    companion object {
        fun read(buf: PacketByteBuf) = AuctionEntry(
            id = buf.readUuid(),
            type = buf.readString(),
            sellerUuid = buf.readUuid(),
            sellerName = buf.readString(),
            species = buf.readString(),
            level = buf.readInt(),
            shiny = buf.readBoolean(),
            count = buf.readInt(),
            extraData = (0 until buf.readVarInt()).associate { buf.readString() to buf.readString() },
            startingPrice = buf.readInt(),
            minIncrement = buf.readInt(),
            currentPrice = buf.readInt(),
            currentBidderUuid = if (buf.readBoolean()) buf.readUuid() else null,
            currentBidderName = buf.readString(),
            bidCount = buf.readInt(),
            endsAt = buf.readLong()
        )
    }
}

fun auctionToEntry(a: AuctionListing): AuctionEntry = AuctionEntry(
    id = a.id,
    type = a.type.name,
    sellerUuid = a.sellerUuid,
    sellerName = a.sellerName,
    // 精灵发翻译 key（服务端无玩家语言上下文），物品发 itemId
    species = if (a.type == AuctionType.POKEMON) (a.extraData["speciesKey"] ?: a.species) else a.species,
    level = a.level,
    shiny = a.shiny,
    count = a.count,
    extraData = a.extraData,
    startingPrice = a.startingPrice,
    minIncrement = a.minIncrement,
    currentPrice = a.currentPrice,
    currentBidderUuid = a.currentBidderUuid,
    currentBidderName = a.currentBidderName,
    bidCount = a.bids.size,
    endsAt = a.endsAt
)

// ── C2S：OP 强制下架拍卖 ──

data class ForceCancelAuctionPayload(val auctionId: UUID) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<ForceCancelAuctionPayload>(CobbleMarket.id("force_cancel_auction"))
        val CODEC: PacketCodec<PacketByteBuf, ForceCancelAuctionPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.auctionId) },
            { b -> ForceCancelAuctionPayload(b.readUuid()) }
        )
    }
}

// ── S2C：结束倒计时警告声（定向发给卖家/出价参与者；auctionId 用于界面锤子图标联动，knock = 1/2/3 渐强） ──

data class AuctionWarnSoundPayload(val auctionId: UUID, val knock: Int) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AuctionWarnSoundPayload>(CobbleMarket.id("auction_warn_sound"))
        val CODEC: PacketCodec<PacketByteBuf, AuctionWarnSoundPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.auctionId); b.writeInt(p.knock) },
            { b -> AuctionWarnSoundPayload(b.readUuid(), b.readInt()) }
        )
    }
}

// ── S2C：成交落槌音效（定向发给卖家/赢家/出价参与者；auctionId 用于界面落槌动画联动） ──

data class AuctionSettleSoundPayload(val auctionId: UUID) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AuctionSettleSoundPayload>(CobbleMarket.id("auction_settle_sound"))
        val CODEC: PacketCodec<PacketByteBuf, AuctionSettleSoundPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.auctionId) },
            { b -> AuctionSettleSoundPayload(b.readUuid()) }
        )
    }
}

// ── C2S：请求拍卖列表 ──

class RequestAuctionListPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestAuctionListPayload>(CobbleMarket.id("request_auction_list"))
        val CODEC: PacketCodec<PacketByteBuf, RequestAuctionListPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestAuctionListPayload() }
        )
    }
}

// ── C2S：上架精灵拍卖 ──

data class CreatePokemonAuctionPayload(
    val pokemonUuid: UUID,
    val startingPrice: Int,
    val minIncrement: Int,     // <= 0 = 用服务器默认
    val durationIndex: Int
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CreatePokemonAuctionPayload>(CobbleMarket.id("create_pokemon_auction"))
        val CODEC: PacketCodec<PacketByteBuf, CreatePokemonAuctionPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.pokemonUuid); b.writeInt(p.startingPrice); b.writeInt(p.minIncrement); b.writeInt(p.durationIndex) },
            { b -> CreatePokemonAuctionPayload(b.readUuid(), b.readInt(), b.readInt(), b.readInt()) }
        )
    }
}

// ── C2S：上架物品拍卖 ──

data class CreateItemAuctionPayload(
    val itemId: String,
    val itemNbt: NbtCompound,
    val count: Int,
    val startingPrice: Int,
    val minIncrement: Int,
    val durationIndex: Int
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CreateItemAuctionPayload>(CobbleMarket.id("create_item_auction"))
        val CODEC: PacketCodec<PacketByteBuf, CreateItemAuctionPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.itemId)
                PacketCodecs.NBT_COMPOUND.encode(b, p.itemNbt)
                b.writeInt(p.count)
                b.writeInt(p.startingPrice)
                b.writeInt(p.minIncrement)
                b.writeInt(p.durationIndex)
            },
            { b ->
                CreateItemAuctionPayload(
                    b.readString(), PacketCodecs.NBT_COMPOUND.decode(b),
                    b.readInt(), b.readInt(), b.readInt(), b.readInt()
                )
            }
        )
    }
}

// ── C2S：出价 ──

data class PlaceBidPayload(val auctionId: UUID, val amount: Int) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<PlaceBidPayload>(CobbleMarket.id("auction_place_bid"))
        val CODEC: PacketCodec<PacketByteBuf, PlaceBidPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.auctionId); b.writeInt(p.amount) },
            { b -> PlaceBidPayload(b.readUuid(), b.readInt()) }
        )
    }
}

// ── S2C：拍卖列表（全量 ACTIVE） ──

data class AuctionListDataPayload(val entries: List<AuctionEntry>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AuctionListDataPayload>(CobbleMarket.id("auction_list_data"))
        val CODEC: PacketCodec<PacketByteBuf, AuctionListDataPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> AuctionListDataPayload((0 until b.readVarInt()).map { AuctionEntry.read(b) }) }
        )
    }
}

// ── S2C：增量事件（NEW = 新上架 / BID = 出价或反狙击延长 / SETTLED = 结算移除） ──

data class AuctionEventPayload(val event: String, val entry: AuctionEntry?) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AuctionEventPayload>(CobbleMarket.id("auction_event"))
        val CODEC: PacketCodec<PacketByteBuf, AuctionEventPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.event)
                b.writeBoolean(p.entry != null)
                p.entry?.write(b)
            },
            { b -> AuctionEventPayload(b.readString(), if (b.readBoolean()) AuctionEntry.read(b) else null) }
        )
    }
}

object AuctionNetwork {

    // 警告轮询计数（每秒执行一次）
    private var warnTick = 0

    fun register() {
        PayloadTypeRegistry.playC2S().register(RequestAuctionListPayload.ID, RequestAuctionListPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CreatePokemonAuctionPayload.ID, CreatePokemonAuctionPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CreateItemAuctionPayload.ID, CreateItemAuctionPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(PlaceBidPayload.ID, PlaceBidPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(ForceCancelAuctionPayload.ID, ForceCancelAuctionPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(AuctionListDataPayload.ID, AuctionListDataPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(AuctionEventPayload.ID, AuctionEventPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(AuctionSettleSoundPayload.ID, AuctionSettleSoundPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(AuctionWarnSoundPayload.ID, AuctionWarnSoundPayload.CODEC)

        // 结束倒计时警告：每秒轮询活跃拍卖，向卖家/出价参与者定向发送渐强警告声
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register { server ->
            warnTick++
            if (warnTick < 20) return@register
            warnTick = 0
            val now = System.currentTimeMillis()
            val state = AuctionState.get(server)
            state.getActiveAuctions().forEach { auction ->
                val knock = state.pollWarnKnock(auction.id, auction.endsAt, now)
                if (knock > 0) {
                    val related = mutableSetOf(auction.sellerUuid)
                    auction.bids.forEach { related.add(it.bidderUuid) }
                    related.forEach { uuid ->
                        server.playerManager.getPlayer(uuid)?.let { p ->
                            ServerPlayNetworking.send(p, AuctionWarnSoundPayload(auction.id, knock))
                        }
                    }
                }
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(RequestAuctionListPayload.ID) { _, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "auction_list", RequestThrottle.READ_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                settleAndBroadcast(server)
                ServerPlayNetworking.send(player, auctionListPayload(server))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CreatePokemonAuctionPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "create_pokemon_auction", RequestThrottle.WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (!checkStartingPrice(player, payload.startingPrice)) return@execute
                val durationMs = resolveDurationMs(player, payload.durationIndex) ?: return@execute

                val party = Cobblemon.storage.getParty(player)
                val pc = Cobblemon.storage.getPC(player)
                var pokemon = party.find { it.uuid == payload.pokemonUuid }
                val fromParty = pokemon != null
                if (pokemon == null) pokemon = pc.find { it.uuid == payload.pokemonUuid }
                if (pokemon == null) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                // 队伍至少要留一只精灵
                if (fromParty && party.occupied() <= 1) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.party_last")))
                    return@execute
                }
                // 黑名单检查（照搬上架路径）
                if (com.shusheng.cobblemarket.market.PokemonBlacklistState.get(server).isBlacklisted(pokemon)) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.blocked")))
                    return@execute
                }
                // 价格限制：起拍价视为上架价格校验
                val speciesId = pokemon.species.resourceIdentifier.toString()
                val vCount = com.shusheng.cobblemarket.market.PokemonPriceLimitState.vCountOf(pokemon.ivs)
                val bounds = com.shusheng.cobblemarket.market.PokemonPriceLimitState.get(server)
                    .getPriceBounds(speciesId, vCount, pokemon.shiny)
                if (bounds != null) {
                    if (bounds.min != null && payload.startingPrice < bounds.min) {
                        ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.price_limit.below_min", bounds.min)))
                        return@execute
                    }
                    if (bounds.max != null && payload.startingPrice > bounds.max) {
                        ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.price_limit.above_max", bounds.max)))
                        return@execute
                    }
                }
                // 拍卖数量上限
                val maxAuctions = CobbleMarketConfig.maxAuctionsPerPlayer
                if (maxAuctions > 0 && AuctionState.get(server).countActiveBySeller(player.uuid) >= maxAuctions) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.max_active", maxAuctions)))
                    return@execute
                }

                // 先完成所有可能失败的操作（序列化 + 数据构建）
                val heldItemStack = pokemon.heldItem()
                val nbt = try {
                    pokemon.saveToNBT(player.serverWorld.registryManager, NbtCompound())
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to serialize pokemon {} for auction by {}: {}", payload.pokemonUuid, player.uuid, e.message)
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                val extra = buildAuctionExtra(pokemon, heldItemStack)
                val now = System.currentTimeMillis()
                val auction = AuctionListing(
                    id = UUID.randomUUID(),
                    type = AuctionType.POKEMON,
                    sellerUuid = player.uuid,
                    sellerName = player.name.string,
                    pokemonNbt = nbt,
                    species = pokemon.species.name,
                    level = pokemon.level,
                    shiny = pokemon.shiny,
                    extraData = extra,
                    itemNbt = null,
                    count = 0,
                    startingPrice = payload.startingPrice,
                    minIncrement = if (payload.minIncrement > 0) payload.minIncrement else CobbleMarketConfig.auctionMinBidIncrement,
                    currentPrice = 0,
                    currentBidderUuid = null,
                    currentBidderName = "",
                    bids = mutableListOf(),
                    createdAt = now,
                    endsAt = now + durationMs,
                    status = com.shusheng.cobblemarket.market.AuctionStatus.ACTIVE,
                    returnedAt = null
                )

                // 副作用阶段：移除精灵 → 入库
                val removed = try {
                    if (fromParty) party.remove(pokemon) else pc.remove(pokemon)
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to remove pokemon {} for auction by {}: {}", payload.pokemonUuid, player.uuid, e.message)
                    false
                }
                if (!removed) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                AuctionState.get(server).addAuction(auction)
                ServerPlayNetworking.send(player, MarketResultPayload(true, Text.translatable("cobblemarket.auction.created")))
                broadcastEvent(server, "NEW", auctionToEntry(auction))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CreateItemAuctionPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "create_item_auction", RequestThrottle.WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (!checkStartingPrice(player, payload.startingPrice)) return@execute
                val durationMs = resolveDurationMs(player, payload.durationIndex) ?: return@execute
                if (payload.count <= 0) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }

                // 重建目标物品，以服务端重建的物品为准（不信任客户端 itemId/itemNbt 的一致性）
                val targetStack = ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, payload.itemNbt)
                if (targetStack.isEmpty) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                val authoritativeItemId = Registries.ITEM.getId(targetStack.item).toString()
                if (com.shusheng.cobblemarket.market.ItemBlacklistState.get(server).contains(authoritativeItemId)) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.item_blocked")))
                    return@execute
                }
                val itemBounds = com.shusheng.cobblemarket.market.ItemPriceLimitState.get(server)
                    .getPriceBounds(authoritativeItemId)
                if (itemBounds != null) {
                    // 价格限制是单价语义：拍卖起拍价为整组总价，下限/上限按数量换算
                    val minTotal = itemBounds.min?.toLong()?.times(payload.count)
                    val maxTotal = itemBounds.max?.toLong()?.times(payload.count)
                    if (minTotal != null && payload.startingPrice.toLong() < minTotal) {
                        ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.price_limit.below_min", fmtLimit(minTotal))))
                        return@execute
                    }
                    if (maxTotal != null && payload.startingPrice.toLong() > maxTotal) {
                        ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.price_limit.above_max", fmtLimit(maxTotal))))
                        return@execute
                    }
                }
                val maxAuctions = CobbleMarketConfig.maxAuctionsPerPlayer
                if (maxAuctions > 0 && AuctionState.get(server).countActiveBySeller(player.uuid) >= maxAuctions) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.max_active", maxAuctions)))
                    return@execute
                }

                val main = player.inventory.main
                var available = 0
                for (i in 0 until main.size) {
                    val stack = main[i]
                    if (ItemStack.areItemsAndComponentsEqual(stack, targetStack)) available += stack.count
                }
                if (available < payload.count) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                val listingNbt = try {
                    main.firstOrNull { ItemStack.areItemsAndComponentsEqual(it, targetStack) }
                        ?.encode(player.serverWorld.registryManager) as? NbtCompound
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to encode item stack for auction by {}: {}", player.uuid, e.message)
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                if (listingNbt == null) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }

                // 副作用阶段：扣物品（照搬上架路径）
                var remaining = payload.count
                for (i in 0 until main.size) {
                    val stack = main[i]
                    if (ItemStack.areItemsAndComponentsEqual(stack, targetStack)) {
                        val r = minOf(remaining, stack.count)
                        stack.decrement(r)
                        remaining -= r
                        if (remaining <= 0) break
                    }
                }
                if (remaining > 0) {
                    // 防御性校验：available 预检已保证足额，正常不可达；异常时退还已扣部分，绝不吞玩家物品
                    giveBackItem(targetStack.copyWithCount(payload.count - remaining), player)
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                // 手动同步背包，确保客户端先收到背包更新、再收到上架结果（与上架路径一致）
                player.inventory.markDirty()
                player.currentScreenHandler.sendContentUpdates()
                val now = System.currentTimeMillis()
                val auction = AuctionListing(
                    id = UUID.randomUUID(),
                    type = AuctionType.ITEM,
                    sellerUuid = player.uuid,
                    sellerName = player.name.string,
                    pokemonNbt = null,
                    species = authoritativeItemId,
                    level = 0,
                    shiny = false,
                    extraData = emptyMap(),
                    itemNbt = listingNbt,
                    count = payload.count,
                    startingPrice = payload.startingPrice,
                    minIncrement = if (payload.minIncrement > 0) payload.minIncrement else CobbleMarketConfig.auctionMinBidIncrement,
                    currentPrice = 0,
                    currentBidderUuid = null,
                    currentBidderName = "",
                    bids = mutableListOf(),
                    createdAt = now,
                    endsAt = now + durationMs,
                    status = com.shusheng.cobblemarket.market.AuctionStatus.ACTIVE,
                    returnedAt = null
                )
                AuctionState.get(server).addAuction(auction)
                ServerPlayNetworking.send(player, MarketResultPayload(true, Text.translatable("cobblemarket.auction.created")))
                broadcastEvent(server, "NEW", auctionToEntry(auction))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(PlaceBidPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "auction_bid", RequestThrottle.WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                settleAndBroadcast(server)
                val auction = AuctionState.get(server).getAuction(payload.auctionId)
                if (auction == null || !auction.isActive()) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.ended")))
                    return@execute
                }
                // 到期即拒绝：不依赖 15 秒结算节流，防止过期出价被反狙击复活拍卖
                if (auction.endsAt <= System.currentTimeMillis()) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.ended")))
                    return@execute
                }
                if (auction.sellerUuid == player.uuid) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.cannot_bid_own")))
                    return@execute
                }
                if (payload.amount < auction.startingPrice || payload.amount <= auction.currentPrice) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.bid_too_low")))
                    return@execute
                }
                // 加价幅度（首笔出价只需 ≥ 起拍价）
                if (auction.currentPrice > 0 && payload.amount - auction.currentPrice < auction.minIncrement) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.bid_increment", auction.minIncrement)))
                    return@execute
                }
                // 扣款与退款：
                // - 自己连续加价：只净扣差价（旧款即自己当前持有的出价，无需进出待领余额）
                // - 他人出价：全额扣款，前出价者退款进待领余额并通知
                //   （outbid 通知的"当前价"用新出价金额，currentPrice 在下方才更新）
                val prevBidder = auction.currentBidderUuid
                val selfRebid = prevBidder == player.uuid && auction.currentPrice > 0
                val deductAmount = if (selfRebid) payload.amount - auction.currentPrice else payload.amount
                if (!CurrencyHandler.remove(player, deductAmount)) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.not_enough")))
                    return@execute
                }
                if (prevBidder != null && auction.currentPrice > 0 && !selfRebid) {
                    MarketState.get(server).addPendingBalance(prevBidder, auction.currentPrice.toLong())
                    server.playerManager.getPlayer(prevBidder)?.sendMessage(
                        Text.translatable("cobblemarket.auction.outbid", payload.amount, auction.speciesText())
                            .formatted(Formatting.YELLOW), false
                    )
                }
                val now = System.currentTimeMillis()
                // 反狙击：结束前窗口内的出价延长结束时间
                val antiSnipeSeconds = CobbleMarketConfig.auctionAntiSnipeSeconds
                if (antiSnipeSeconds > 0 && auction.endsAt - now < antiSnipeSeconds * 1000L) {
                    auction.endsAt = now + antiSnipeSeconds * 1000L
                }
                auction.bids.add(AuctionBid(player.uuid, player.name.string, payload.amount, now))
                auction.currentPrice = payload.amount
                auction.currentBidderUuid = player.uuid
                auction.currentBidderName = player.name.string
                AuctionState.get(server).markModified()
                ServerPlayNetworking.send(player, MarketResultPayload(true, Text.translatable("cobblemarket.auction.bid_placed")))
                broadcastEvent(server, "BID", auctionToEntry(auction))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(ForceCancelAuctionPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "force_cancel_auction", RequestThrottle.WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val state = AuctionState.get(server)
                val auction = state.getAuction(payload.auctionId)
                if (auction == null || !auction.isActive()) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.ended")))
                    return@execute
                }
                val bidder = auction.currentBidderUuid
                val bidAmount = auction.currentPrice
                val ok = state.forceCancel(server, auction)
                if (!ok) {
                    ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                // 通知卖家与出价者（在线者），广播 SETTLED 让全服列表同步移除
                server.playerManager.getPlayer(auction.sellerUuid)?.sendMessage(
                    Text.translatable("cobblemarket.auction.force_cancelled_seller", auction.speciesText())
                        .formatted(Formatting.RED), false
                )
                if (bidder != null && bidAmount > 0) {
                    server.playerManager.getPlayer(bidder)?.sendMessage(
                        Text.translatable("cobblemarket.auction.force_cancelled_bidder", auction.speciesText())
                            .formatted(Formatting.RED), false
                    )
                }
                broadcastEvent(server, "SETTLED", auctionToEntry(auction))
                ServerPlayNetworking.send(player, MarketResultPayload(true, Text.translatable("cobblemarket.auction.force_cancelled")))
            }
        }
    }

    // ── 共享校验/广播 ──

    /** 千分位格式化限制金额（Long，min×count 可能超 Int），用于价格限制提示 */
    private fun fmtLimit(v: Long): String =
        v.toString().reversed().chunked(3).joinToString(",").reversed()


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

    private fun checkStartingPrice(player: net.minecraft.server.network.ServerPlayerEntity, startingPrice: Int): Boolean {
        if (startingPrice > 0) return true
        ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_price")))
        return false
    }

    private fun resolveDurationMs(player: net.minecraft.server.network.ServerPlayerEntity, durationIndex: Int): Long? {
        val options = CobbleMarketConfig.auctionDurationOptions
        if (options.isEmpty()) {
            ServerPlayNetworking.send(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.no_duration")))
            return null
        }
        val minutes = options.getOrElse(durationIndex.coerceIn(0, options.size - 1)) { options.first() }
        return minutes * 60_000L
    }

    /** 结算到期拍卖并广播 SETTLED 事件（各数据请求入口调用，实现惰性结算） */
    fun settleAndBroadcast(server: MinecraftServer) {
        val settled = AuctionState.get(server).settleExpiredAuctions(server, System.currentTimeMillis())
        settled.forEach { auction ->
            broadcastEvent(server, "SETTLED", auctionToEntry(auction))
            // 成交落槌：定向发给卖家/赢家/所有出价参与者（在线的），无关玩家不打扰
            val related = mutableSetOf(auction.sellerUuid)
            auction.currentBidderUuid?.let { related.add(it) }
            auction.bids.forEach { related.add(it.bidderUuid) }
            related.forEach { uuid ->
                server.playerManager.getPlayer(uuid)?.let { p ->
                    ServerPlayNetworking.send(p, AuctionSettleSoundPayload(auction.id))
                }
            }
            // 聊天通知：卖家（成交/流拍）+ 赢家
            val sellerPlayer = server.playerManager.getPlayer(auction.sellerUuid)
            if (auction.status == com.shusheng.cobblemarket.market.AuctionStatus.SOLD) {
                sellerPlayer?.sendMessage(
                    Text.translatable("cobblemarket.auction.settled_seller", auction.speciesText(), fmtLimit(auction.currentPrice.toLong()) + " ◆")
                        .formatted(Formatting.GOLD), false
                )
                auction.currentBidderUuid?.let { winnerUuid ->
                    server.playerManager.getPlayer(winnerUuid)?.sendMessage(
                        Text.translatable("cobblemarket.auction.settled_winner", auction.speciesText())
                            .formatted(Formatting.GREEN), false
                    )
                }
            } else {
                sellerPlayer?.sendMessage(
                    Text.translatable("cobblemarket.auction.settled_unsold", auction.speciesText())
                        .formatted(Formatting.YELLOW), false
                )
            }
        }
    }

    private fun broadcastEvent(server: MinecraftServer, event: String, entry: AuctionEntry?) {
        server.playerManager.playerList.forEach { p ->
            ServerPlayNetworking.send(p, AuctionEventPayload(event, entry))
        }
    }

    private fun auctionListPayload(server: MinecraftServer): AuctionListDataPayload =
        AuctionListDataPayload(AuctionState.get(server).getActiveAuctions().map { auctionToEntry(it) })

    // 照搬 MarketNetwork.buildListingExtra（保持展示字段一致）
    private fun buildAuctionExtra(
        pokemon: com.cobblemon.mod.common.pokemon.Pokemon,
        heldItemStack: ItemStack
    ): Map<String, String> {
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
            "nature" to "cobblemon.nature.${pokemon.effectiveNature.name.path}",
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
}
