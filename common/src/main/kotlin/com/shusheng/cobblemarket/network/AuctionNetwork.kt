package com.shusheng.cobblemarket.network

import com.cobblemon.mod.common.Cobblemon
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.config.CurrencyHandler
import com.shusheng.cobblemarket.event.TransactionCategory
import com.shusheng.cobblemarket.event.TransactionHistory
import com.shusheng.cobblemarket.event.TransactionRecord
import com.shusheng.cobblemarket.event.TransactionType
import com.shusheng.cobblemarket.market.AuctionBid
import com.shusheng.cobblemarket.market.AuctionListing
import com.shusheng.cobblemarket.market.AuctionState
import com.shusheng.cobblemarket.market.AuctionType
import com.shusheng.cobblemarket.market.MarketState
import com.shusheng.cobblemarket.platform.onServerTickEnd
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.util.RequestThrottle
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
import net.minecraft.text.HoverEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.util.UUID
import com.shusheng.cobblemarket.util.RecordDetail
import com.shusheng.cobblemarket.util.TypeTextColors

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
    val endsAt: Long,
    /** 创建时间：客户端「最新在上」排序用 */
    val createdAt: Long,
    /** 物品拍卖的组件 NBT（附魔/名称等词条显示用；精灵拍卖为 null）。通常几百字节，可接受 */
    val itemNbt: NbtCompound?
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
        buf.writeLong(createdAt)
        buf.writeBoolean(itemNbt != null)
        itemNbt?.let { PacketCodecs.NBT_COMPOUND.encode(buf, it) }
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
            endsAt = buf.readLong(),
            createdAt = buf.readLong(),
            itemNbt = if (buf.readBoolean()) PacketCodecs.NBT_COMPOUND.decode(buf) else null
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
    endsAt = a.endsAt,
    createdAt = a.createdAt,
    itemNbt = if (a.type == AuctionType.ITEM) a.itemNbt else null
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

// ── C2S：请求拍卖时长档位（进入上架界面时请求，客户端按钮按真实配置显示） ──

class RequestAuctionDurationsPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestAuctionDurationsPayload>(CobbleMarket.id("request_auction_durations"))
        val CODEC: PacketCodec<PacketByteBuf, RequestAuctionDurationsPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestAuctionDurationsPayload() }
        )
    }
}

// ── S2C：拍卖时长档位列表（分钟制，与配置文件一致） ──

data class AuctionDurationsPayload(val durations: List<Int>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AuctionDurationsPayload>(CobbleMarket.id("auction_durations"))
        val CODEC: PacketCodec<PacketByteBuf, AuctionDurationsPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.durations.size); p.durations.forEach { b.writeInt(it) } },
            { b -> AuctionDurationsPayload((0 until b.readVarInt()).map { b.readInt() }) }
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
        registerS2CType(AuctionListDataPayload.ID, AuctionListDataPayload.CODEC)
        registerS2CType(AuctionEventPayload.ID, AuctionEventPayload.CODEC)
        registerS2CType(AuctionSettleSoundPayload.ID, AuctionSettleSoundPayload.CODEC)
        registerS2CType(AuctionWarnSoundPayload.ID, AuctionWarnSoundPayload.CODEC)
        registerS2CType(AuctionDurationsPayload.ID, AuctionDurationsPayload.CODEC)

        registerC2S(RequestAuctionDurationsPayload.ID, RequestAuctionDurationsPayload.CODEC) { _, player ->
            // 只读数据，无权限要求：上架界面按钮显示用
            sendToPlayer(player, AuctionDurationsPayload(CobbleMarketConfig.auctionDurationOptions))
        }

        // 结束倒计时警告：每秒轮询活跃拍卖，向卖家/出价参与者定向发送渐强警告声
        onServerTickEnd { server ->
            warnTick++
            if (warnTick < 20) return@onServerTickEnd
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
                            sendToPlayer(p, AuctionWarnSoundPayload(auction.id, knock))
                        }
                    }
                }
            }
            // 到期结算走同一定时器：拍卖到期后 1 秒内自动结算并通知（落槌/铃声/聊天消息），
            // 不依赖玩家打开拍卖场或出价触发；请求路径里的 settleAndBroadcast 保留作为即时结算
            settleAndBroadcast(server)
        }

        registerC2S(RequestAuctionListPayload.ID, RequestAuctionListPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "auction_list", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                settleAndBroadcast(server)
                sendToPlayer(player, auctionListPayload(server))
            }
        }

        registerC2S(CreatePokemonAuctionPayload.ID, CreatePokemonAuctionPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "create_pokemon_auction", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                if (!checkStartingPrice(player, payload.startingPrice)) return@execute
                val durationMs = resolveDurationMs(player, payload.durationIndex) ?: return@execute

                val party = Cobblemon.storage.getParty(player)
                val pc = Cobblemon.storage.getPC(player)
                var pokemon = party.find { it.uuid == payload.pokemonUuid }
                val fromParty = pokemon != null
                if (pokemon == null) pokemon = pc.find { it.uuid == payload.pokemonUuid }
                if (pokemon == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                // 对战中不可拍卖（整个队伍）：战斗系统动态读队伍，抽走任何精灵都可能造成战斗内模型消失或变相复制
                if (com.shusheng.cobblemarket.market.BattleGuard.isPlayerInBattle(player.uuid)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.in_battle")))
                    return@execute
                }
                // 队伍至少要留一只精灵
                if (fromParty && party.occupied() <= 1) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.party_last")))
                    return@execute
                }
                // 黑名单检查（照搬上架路径）
                if (com.shusheng.cobblemarket.market.PokemonBlacklistState.get(server).isBlacklisted(pokemon)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.blocked")))
                    return@execute
                }
                // 携带物黑名单检查：拉黑的物品不允许随精灵上架（防绕过物品黑名单，与蛋交易联动同语义）
                val heldItem = pokemon.heldItem()
                val heldItemId = if (heldItem.isEmpty) null
                    else net.minecraft.registry.Registries.ITEM.getId(heldItem.item).toString()
                if (heldItemId != null && com.shusheng.cobblemarket.market.ItemBlacklistState.get(server)
                        .matches(heldItem, player.serverWorld.registryManager)
                ) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.held_item_blocked")))
                    return@execute
                }
                // 价格限制：起拍价视为上架价格校验（形态照黑名单语义）+ 携带物规则合并
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
                    // 携带物参与限价时用带说明的提示，玩家才知道总价里包含了携带物部分
                    if (bounds.min != null && payload.startingPrice < bounds.min) {
                        val key = if (heldItemId != null) "cobblemarket.auction.price_limit.held_below_min" else "cobblemarket.auction.price_limit.below_min"
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable(key, bounds.min)))
                        return@execute
                    }
                    if (bounds.max != null && payload.startingPrice > bounds.max) {
                        val key = if (heldItemId != null) "cobblemarket.auction.price_limit.held_above_max" else "cobblemarket.auction.price_limit.above_max"
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable(key, bounds.max)))
                        return@execute
                    }
                }
                // 拍卖数量上限
                val maxAuctions = CobbleMarketConfig.maxAuctionsPerPlayer
                if (maxAuctions > 0 && AuctionState.get(server).countActiveBySeller(player.uuid) >= maxAuctions) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.max_active", maxAuctions)))
                    return@execute
                }

                // 先完成所有可能失败的操作（序列化 + 数据构建）
                val heldItemStack = pokemon.heldItem()
                val nbt = try {
                    pokemon.saveToNBT(player.serverWorld.registryManager, NbtCompound())
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to serialize pokemon {} for auction by {}: {}", payload.pokemonUuid, player.uuid, e.message)
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
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
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                AuctionState.get(server).addAuction(auction)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.auction.created")))
                broadcastEvent(server, "NEW", auctionToEntry(auction))
                announceAuction(server, auction)
                recordAuction(server, auction, TransactionType.ADD)
            }
        }

        registerC2S(CreateItemAuctionPayload.ID, CreateItemAuctionPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "create_item_auction", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                if (!checkStartingPrice(player, payload.startingPrice)) return@execute
                val durationMs = resolveDurationMs(player, payload.durationIndex) ?: return@execute
                if (payload.count <= 0) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }

                // 重建目标物品，以服务端重建的物品为准（不信任客户端 itemId/itemNbt 的一致性）
                val targetStack = ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, payload.itemNbt)
                if (targetStack.isEmpty) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                val authoritativeItemId = Registries.ITEM.getId(targetStack.item).toString()
                // 蛋交易开关（默认关闭）：蛋走物品链路不经过精灵黑名单，需服主显式开启
                if (isEggItem(authoritativeItemId) && !com.shusheng.cobblemarket.config.CobbleMarketConfig.eggTradingEnabled) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.egg_trading_disabled"))
                    )
                    return@execute
                }
                if (com.shusheng.cobblemarket.market.ItemBlacklistState.get(server)
                        .matches(targetStack, player.serverWorld.registryManager)
                ) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.item_blocked")))
                    return@execute
                }
                val itemBounds = com.shusheng.cobblemarket.market.ItemPriceLimitState.get(server)
                    .getPriceBounds(targetStack, player.serverWorld.registryManager)
                if (itemBounds != null) {
                    // 空区间 = 多条同档限价规则交叉锁死，任何价格都过不了校验：明确告知而不是轮流报上下限
                    if (itemBounds.isEmptyRange) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.conflict")))
                        return@execute
                    }
                    // 价格限制是单价语义：拍卖起拍价为整组总价，下限/上限按数量换算
                    val minTotal = itemBounds.min?.toLong()?.times(payload.count)
                    val maxTotal = itemBounds.max?.toLong()?.times(payload.count)
                    if (minTotal != null && payload.startingPrice.toLong() < minTotal) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.price_limit.below_min", fmtLimit(minTotal))))
                        return@execute
                    }
                    if (maxTotal != null && payload.startingPrice.toLong() > maxTotal) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.price_limit.above_max", fmtLimit(maxTotal))))
                        return@execute
                    }
                }

                // 容器内容校验：黑名单/限价/蛋开关对容器内物品同样生效（防塞箱绕过）
                val containerReject = com.shusheng.cobblemarket.market.ContainerTradeCheck.check(targetStack, server)
                if (containerReject != null) {
                    sendToPlayer(player, MarketResultPayload(false, containerReject))
                    return@execute
                }

                val maxAuctions = CobbleMarketConfig.maxAuctionsPerPlayer
                if (maxAuctions > 0 && AuctionState.get(server).countActiveBySeller(player.uuid) >= maxAuctions) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.max_active", maxAuctions)))
                    return@execute
                }

                val main = player.inventory.main
                var available = 0
                for (i in 0 until main.size) {
                    val stack = main[i]
                    if (itemsEqualForTrading(stack, targetStack)) available += stack.count
                }
                if (available < payload.count) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                val listingNbt = try {
                    main.firstOrNull { itemsEqualForTrading(it, targetStack) }
                        ?.encode(player.serverWorld.registryManager) as? NbtCompound
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to encode item stack for auction by {}: {}", player.uuid, e.message)
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                if (listingNbt == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }

                // 副作用阶段：扣物品（照搬上架路径）
                var remaining = payload.count
                for (i in 0 until main.size) {
                    val stack = main[i]
                    if (itemsEqualForTrading(stack, targetStack)) {
                        val r = minOf(remaining, stack.count)
                        stack.decrement(r)
                        remaining -= r
                        if (remaining <= 0) break
                    }
                }
                if (remaining > 0) {
                    // 防御性校验：available 预检已保证足额，正常不可达；异常时退还已扣部分，绝不吞玩家物品
                    giveBackItem(targetStack.copyWithCount(payload.count - remaining), player)
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
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
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.auction.created")))
                broadcastEvent(server, "NEW", auctionToEntry(auction))
                announceAuction(server, auction)
                recordAuction(server, auction, TransactionType.ADD)
            }
        }

        registerC2S(PlaceBidPayload.ID, PlaceBidPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "auction_bid", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                settleAndBroadcast(server)
                val auction = AuctionState.get(server).getAuction(payload.auctionId)
                if (auction == null || !auction.isActive()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.ended")))
                    return@execute
                }
                // 到期即拒绝：不依赖 15 秒结算节流，防止过期出价被反狙击复活拍卖
                if (auction.endsAt <= System.currentTimeMillis()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.ended")))
                    return@execute
                }
                if (auction.sellerUuid == player.uuid) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.cannot_bid_own")))
                    return@execute
                }
                // 物品拍卖治理即时生效：蛋交易开关关闭/物品黑名单拦截存量拍卖的出价（卖家可自行下架取回）
                if (auction.type == AuctionType.ITEM) {
                    if (isEggItem(auction.species) && !CobbleMarketConfig.eggTradingEnabled) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.egg_trading_disabled")))
                        return@execute
                    }
                    val auctionStack = auction.itemNbt?.let {
                        ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, it)
                    } ?: ItemStack.EMPTY
                    if (com.shusheng.cobblemarket.market.ItemBlacklistState.get(server)
                            .matches(auctionStack, player.serverWorld.registryManager)
                    ) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.item_blocked")))
                        return@execute
                    }
                }
                // 精灵拍卖治理即时生效：存量精灵拍卖加入黑名单后拦截出价
                if (auction.type == AuctionType.POKEMON) {
                    val pokemon = try {
                        com.cobblemon.mod.common.pokemon.Pokemon()
                            .loadFromNBT(player.serverWorld.registryManager, auction.pokemonNbt ?: throw IllegalStateException("missing pokemonNbt"))
                    } catch (e: Exception) {
                        CobbleMarket.LOGGER.warn("Failed to load pokemon NBT for auction {}: {}", auction.id, e.message)
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.ended")))
                        return@execute
                    }
                    if (com.shusheng.cobblemarket.market.PokemonBlacklistState.get(server).isBlacklisted(pokemon)) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.blocked")))
                        return@execute
                    }
                }
                if (payload.amount < auction.startingPrice || payload.amount <= auction.currentPrice) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.bid_too_low")))
                    return@execute
                }
                // 加价幅度（首笔出价只需 ≥ 起拍价）
                if (auction.currentPrice > 0 && payload.amount - auction.currentPrice < auction.minIncrement) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.bid_increment", auction.minIncrement)))
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
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.not_enough")))
                    return@execute
                }
                if (prevBidder != null && auction.currentPrice > 0 && !selfRebid) {
                    MarketState.get(server).addPendingBalance(prevBidder, auction.currentPrice.toLong())
                    // 出价者离线则入队补发
                    com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                        server, prevBidder,
                        Text.translatable("cobblemarket.auction.outbid", payload.amount, auction.speciesText())
                            .formatted(Formatting.YELLOW)
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
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.auction.bid_placed")))
                broadcastEvent(server, "BID", auctionToEntry(auction))
            }
        }

        registerC2S(ForceCancelAuctionPayload.ID, ForceCancelAuctionPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "force_cancel_auction", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                val state = AuctionState.get(server)
                val auction = state.getAuction(payload.auctionId)
                if (auction == null || !auction.isActive()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.ended")))
                    return@execute
                }
                val bidder = auction.currentBidderUuid
                val bidAmount = auction.currentPrice
                val ok = state.forceCancel(server, auction)
                if (!ok) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                // 通知卖家与出价者（离线则入队补发），广播 SETTLED 让全服列表同步移除
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                    server, auction.sellerUuid,
                    Text.translatable("cobblemarket.auction.force_cancelled_seller", auction.speciesText())
                        .formatted(Formatting.RED)
                )
                if (bidder != null && bidAmount > 0) {
                    com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                        server, bidder,
                        Text.translatable("cobblemarket.auction.force_cancelled_bidder", auction.speciesText())
                            .formatted(Formatting.RED)
                    )
                }
                // 强制下架用 CANCELLED 事件：客户端立即移除，不播 1.5 秒落槌动画（管理操作要干脆）
                broadcastEvent(server, "CANCELLED", auctionToEntry(auction))
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.auction.force_cancelled")))
                recordAuction(server, auction, TransactionType.CANCEL, "admin")
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
            Text.translatable("cobblemarket.ban.banned_msg_time_reason", timeDesc, com.shusheng.cobblemarket.market.BanState.reasonText(banInfo.reason))
        else
            Text.translatable("cobblemarket.ban.banned_msg_time", timeDesc)
        sendToPlayer(player, MarketResultPayload(false, banMsg))
        return true
    }

    private fun checkStartingPrice(player: net.minecraft.server.network.ServerPlayerEntity, startingPrice: Int): Boolean {
        if (startingPrice > 0) return true
        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_price")))
        return false
    }

    private fun resolveDurationMs(player: net.minecraft.server.network.ServerPlayerEntity, durationIndex: Int): Long? {
        val options = CobbleMarketConfig.auctionDurationOptions
        if (options.isEmpty()) {
            sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.auction.no_duration")))
            return null
        }
        val minutes = options.getOrElse(durationIndex.coerceIn(0, options.size - 1)) { options.first() }
        return minutes * 60_000L
    }

    /** 结算到期拍卖并广播 SETTLED 事件（各数据请求入口调用，实现惰性结算） */
    fun settleAndBroadcast(server: MinecraftServer) {
        val settled = AuctionState.get(server).settleExpiredAuctions(server, System.currentTimeMillis())
        if (settled.isNotEmpty()) {
            // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
            com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
        }
        settled.forEach { auction ->
            broadcastEvent(server, "SETTLED", auctionToEntry(auction))
            // 成交落槌：定向发给卖家/赢家/所有出价参与者（在线的），无关玩家不打扰
            val related = mutableSetOf(auction.sellerUuid)
            auction.currentBidderUuid?.let { related.add(it) }
            auction.bids.forEach { related.add(it.bidderUuid) }
            related.forEach { uuid ->
                server.playerManager.getPlayer(uuid)?.let { p ->
                    sendToPlayer(p, AuctionSettleSoundPayload(auction.id))
                }
            }
            // 聊天通知：卖家（成交/流拍）+ 赢家（离线则入队补发）
            if (auction.status == com.shusheng.cobblemarket.market.AuctionStatus.SOLD) {
                // 手续费同结算公式重算（含逾期翻倍）：卖家到手 = 成交价 - 手续费，消息里一并说明避免疑问
                val feePercent = com.shusheng.cobblemarket.config.CobbleMarketConfig.auctionFeePercent
                val baseFee = if (feePercent > 0)
                    // toLong 先提升：与结算同公式，防 Int×Int 环绕溢出（消息与扣费保持一致）
                    Math.ceil(auction.currentPrice.toLong() * feePercent / 100.0).toLong().coerceAtMost(Int.MAX_VALUE.toLong())
                else 0L
                val fee = com.shusheng.cobblemarket.finance.FinanceService.applyHolderDiscount(
                    com.shusheng.cobblemarket.finance.FinanceState.get(server),
                    auction.sellerUuid,
                    com.shusheng.cobblemarket.finance.FinanceService.applyFeeMultiplier(
                        com.shusheng.cobblemarket.finance.FinanceState.get(server), auction.sellerUuid, System.currentTimeMillis(), baseFee
                    )
                ).toInt()
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                    server, auction.sellerUuid,
                    // 聊天消息货币名走 CurrencyHandler.currencyText()（translatable 随模式翻译）
                    Text.translatable(
                        "cobblemarket.auction.settled_seller",
                        auction.speciesText(),
                        CurrencyHandler.goldAmount(fmtLimit(auction.currentPrice.toLong())),
                        CurrencyHandler.goldCurrencyText(),
                        CurrencyHandler.goldAmount(fmtLimit((auction.currentPrice - fee).toLong())),
                        CurrencyHandler.goldAmount(fmtLimit(fee.toLong()))
                    ).formatted(Formatting.GOLD)
                )
                auction.currentBidderUuid?.let { winnerUuid ->
                    com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                        server, winnerUuid,
                        Text.translatable("cobblemarket.auction.settled_winner", auction.speciesText())
                            .formatted(Formatting.GOLD)
                    )
                    // 赢家庆祝动画（在线者）：与落槌铃声同批发送，天然同步
                    if (auction.type == com.shusheng.cobblemarket.market.AuctionType.POKEMON) {
                        CelebrationNetwork.sendFromEntry(server, winnerUuid, auction.species, auction.shiny, auction.extraData, CelebrationSource.AUCTION)
                    }
                }
                // 成交全服播报（与创建播报闭环：上架有人喊、成交有人喊；流拍/强制下架不播，2026-08-30 拍板）
                // 成交 CSV 记录在 AuctionState.settleExpiredAuctions 内已有，这里不重复记
                server.playerManager.playerList.forEach { it.sendMessage(settlementAnnouncement(auction)) }
            } else {
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                    server, auction.sellerUuid,
                    Text.translatable("cobblemarket.auction.settled_unsold", auction.speciesText())
                        .formatted(Formatting.YELLOW)
                )
                recordAuction(server, auction, TransactionType.CANCEL, "unsold")
            }
        }
    }

    private fun broadcastEvent(server: MinecraftServer, event: String, entry: AuctionEntry?) {
        server.playerManager.playerList.forEach { p ->
            sendToPlayer(p, AuctionEventPayload(event, entry))
        }
    }

    private fun auctionListPayload(server: MinecraftServer): AuctionListDataPayload =
        AuctionListDataPayload(AuctionState.get(server).getActiveAuctions().map { auctionToEntry(it) })

    // 照搬 MarketNetwork.buildListingExtra（保持展示字段一致）
    private fun buildAuctionExtra(
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
            "evsHp" to pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.HP].toString(),
            "evsAtk" to pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK].toString(),
            "evsDef" to pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE].toString(),
            "evsSpAtk" to pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK].toString(),
            "evsSpDef" to pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE].toString(),
            "evsSpd" to pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED].toString(),
            "friendship" to pokemon.friendship.toString(),
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

    // ── 拍卖创建全服聊天播报 ──

    /** 全服播报新拍品：拍品名带 hover 详情 + 点击直达出价弹窗（客户端指令 /cobblemarket auction <id>）。
     *  名字下划线 + 金色 [点击出价] 按钮提示可点（玩家不会想到裸名字能点击）。 */
    private fun announceAuction(server: MinecraftServer, auction: AuctionListing) {
        // 拍品名：精灵 = 翻译名 + 属性色 + 下划线（闪光前缀金★）；物品 = 物品翻译名 + 下划线
        // styled/append 只在 MutableText 上，speciesText 返回 Text，先包一层 literal 再上样式
        val baseName: Text = if (auction.type == AuctionType.POKEMON) {
            Text.literal("").append(auction.speciesText()).styled {
                it.withColor(TypeTextColors.color(auction.extraData["primaryType"] ?: "cobblemon.type.normal"))
                    .withUnderline(true)
            }
        } else {
            Text.literal("").append(auction.speciesText()).styled { it.withUnderline(true) }
        }
        val nameText = if (auction.type == AuctionType.POKEMON && auction.shiny) {
            Text.literal("").append(Text.literal("★ ").formatted(Formatting.GOLD)).append(baseName)
        } else Text.literal("").append(baseName)
        val bidLabel = Text.translatable("cobblemarket.chat.bid_button").formatted(Formatting.GOLD, Formatting.UNDERLINE)
        val clickableName = Text.literal("").append(nameText).append(Text.literal(" ")).append(bidLabel)
            .styled {
                it.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cobblemarket auction ${auction.id}"))
                    .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, buildAnnouncementHover(auction)))
            }
        // 金额与货币名恒金（聊天颜色模板）；主体蓝（2026-08-30 拍板），名字属性色/按钮/金额子样式优先于父级蓝
        val priceText = Text.literal("")
            .append(CurrencyHandler.goldAmount(auction.startingPrice))
            .append(CurrencyHandler.goldCurrencyText())
        val msg = Text.literal("[拍卖] ").formatted(Formatting.GOLD)
            .append(Text.translatable("cobblemarket.chat.auction_announce", Text.literal(auction.sellerName), clickableName, priceText).formatted(Formatting.BLUE))
        server.playerManager.playerList.forEach { it.sendMessage(msg) }
    }

    /** 物品附魔词条行（提取逻辑共享 RecordDetail.enchantmentLevels）；
     *  NBT 键是 ID 形式（minecraft:fortune），翻译 key 需转点号（enchantment.minecraft.fortune） */
    private fun enchantmentLines(auction: AuctionListing): List<Text> =
        com.shusheng.cobblemarket.util.RecordDetail.enchantmentLevels(auction.itemNbt).map { (key, level) ->
            Text.translatable("enchantment.${key.replace(':', '.')}")
                .append(Text.literal(" ${romanNumeral(level)}"))
                .formatted(Formatting.AQUA)
        }

    /** 附魔等级罗马数字（原版惯例；超 10 直接阿拉伯数字） */
    private fun romanNumeral(v: Int): String = when (v) {
        1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; 5 -> "V"
        6 -> "VI"; 7 -> "VII"; 8 -> "VIII"; 9 -> "IX"; 10 -> "X"
        else -> v.toString()
    }

    /** CSV 账本记录：创建=上架、成交=卖出（赢家为买家）、流拍/强制下架=下架（reason 区分）；与市场账本同库 */
    private fun recordAuction(server: MinecraftServer, auction: AuctionListing, type: TransactionType, reason: String? = null) {
        val category = if (auction.type == AuctionType.POKEMON) TransactionCategory.POKEMON else TransactionCategory.ITEM
        val detailBase = if (auction.type == AuctionType.POKEMON)
            RecordDetail.pokemon(auction.extraData, auction.level, auction.shiny)
        else
            RecordDetail.item(auction.itemNbt, auction.count)
        // 账本 fee 与实际扣费一致（含逾期翻倍，付款方=卖家）
        val fee = if (type == TransactionType.PURCHASE && CobbleMarketConfig.auctionFeePercent > 0)
            com.shusheng.cobblemarket.finance.FinanceService.applyHolderDiscount(
                com.shusheng.cobblemarket.finance.FinanceState.get(server),
                auction.sellerUuid,
                com.shusheng.cobblemarket.finance.FinanceService.applyFeeMultiplier(
                    com.shusheng.cobblemarket.finance.FinanceState.get(server), auction.sellerUuid,
                    System.currentTimeMillis(),
                    Math.ceil(auction.currentPrice.toLong() * CobbleMarketConfig.auctionFeePercent / 100.0).toLong()
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                )
            ).toInt()
        else 0
        TransactionHistory.get(server).addRecord(TransactionRecord(
            timestamp = System.currentTimeMillis(),
            type = type,
            category = category,
            sellerUuid = auction.sellerUuid,
            sellerName = auction.sellerName,
            buyerUuid = if (type == TransactionType.PURCHASE) auction.currentBidderUuid else null,
            buyerName = if (type == TransactionType.PURCHASE) auction.currentBidderName else "",
            species = if (auction.type == AuctionType.POKEMON) (auction.extraData["speciesKey"] ?: auction.species) else auction.species,
            price = if (type == TransactionType.PURCHASE) auction.currentPrice else auction.startingPrice,
            fee = fee,
            detail = if (reason != null) "$detailBase|reason=$reason" else detailBase
        ))
    }

    /** 成交全服播报：金 [拍卖] 标签 + 蓝主体 + 属性色名字（闪光带金★）+ 金金额——金币符号恒金铁律；
     *  名字不带点击（拍品已结束），纯展示 */
    private fun settlementAnnouncement(auction: AuctionListing): Text {
        val nameText = if (auction.type == AuctionType.POKEMON) {
            val colored = Text.literal("").append(auction.speciesText()).styled {
                it.withColor(TypeTextColors.color(auction.extraData["primaryType"] ?: "cobblemon.type.normal"))
            }
            if (auction.shiny) Text.literal("").append(Text.literal("★ ").formatted(Formatting.GOLD)).append(colored)
            else colored
        } else {
            Text.literal("").append(auction.speciesText())
        }
        val priceText = Text.literal("")
            .append(CurrencyHandler.goldAmount(auction.currentPrice))
            .append(CurrencyHandler.goldCurrencyText())
        return Text.literal("[拍卖] ").formatted(Formatting.GOLD)
            .append(Text.translatable("cobblemarket.chat.auction_sold_announce", Text.literal(auction.currentBidderName), priceText, nameText).formatted(Formatting.BLUE))
    }

    /** 悬浮详情（多行彩色文字，发出时为快照）：结构仿出价弹窗左列——名字行/属性/性格/特性/携带物/IV+EV 竖排六行，
     *  尾部拍卖信息（起拍价/最低加价/结束时间）；物品版 = 数量 + 拍卖信息 */
    private fun buildAnnouncementHover(auction: AuctionListing): Text {
        val lines = mutableListOf<Text>()
        if (auction.type == AuctionType.POKEMON) {
            val extra = auction.extraData
            // 名字行：Lv + 性别（名字本身在消息正文，这里补数值）
            val lvLine = Text.translatable("cobblemarket.gui.lv").append(Text.literal("${auction.level}"))
                .append(Text.literal(" "))
                .append(
                    when (extra["gender"]) {
                        "MALE" -> Text.translatable("cobblemarket.aspect.male")
                        "FEMALE" -> Text.translatable("cobblemarket.aspect.female")
                        else -> Text.literal("")
                    }
                )
            lines.add(lvLine)
            // 属性行（主 + 副）：属性名按属性色着色，与出价弹窗一致
            val primaryType = extra["primaryType"] ?: ""
            val secondaryType = extra["secondaryType"] ?: ""
            lines.add(
                Text.translatable("cobblemarket.gui.tooltip_type").append(
                    if (primaryType.isNotEmpty())
                        Text.translatable(primaryType).styled { it.withColor(TypeTextColors.color(primaryType)) }
                    else Text.literal("-")
                ).append(
                    if (secondaryType.isNotEmpty())
                        Text.literal(" + ").append(
                            Text.translatable(secondaryType).styled { it.withColor(TypeTextColors.color(secondaryType)) })
                    else Text.literal("")
                )
            )
            // 性格（薄荷生效值） / 特性 / 球种
            extra["nature"]?.takeIf { it.isNotEmpty() }?.let { lines.add(Text.translatable("cobblemarket.gui.tooltip_nature").append(Text.translatable(it))) }
            extra["ability"]?.takeIf { it.isNotEmpty() }?.let { lines.add(Text.translatable("cobblemarket.gui.tooltip_ability").append(Text.translatable(it))) }
            extra["ball"]?.takeIf { it.isNotEmpty() }?.let { lines.add(Text.translatable("cobblemarket.gui.tooltip_ball").append(Text.translatable(it))) }
            // 携带物（有则一行）
            val heldItemId = extra["heldItemId"].orEmpty()
            val heldKey = heldItemId.takeIf { it.isNotEmpty() }?.let { Identifier.tryParse(it) }?.let {
                if (it == Identifier.of("minecraft", "air")) null else Registries.ITEM.get(it).translationKey
            }
            heldKey?.let { lines.add(Text.translatable("cobblemarket.gui.tooltip_held").append(Text.translatable(it))) }
            // IV 段：标签 + 六行竖排（仿出价弹窗左列：stat:IV  EV:N，行色按属性色系，EV 红；聊天 16 色近似弹窗 ARGB）
            lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs"))
            val hp = Text.translatable("cobblemon.stat.hp.name")
            val atk = Text.translatable("cobblemon.stat.attack.name")
            val def = Text.translatable("cobblemon.stat.defence.name")
            val spa = Text.translatable("cobblemon.stat.special_attack.name")
            val spd = Text.translatable("cobblemon.stat.special_defence.name")
            val spe = Text.translatable("cobblemon.stat.speed.name")
            fun ivLine(stat: Text, ivKey: String, evKey: String, color: Formatting): Text =
                Text.literal("  ").append(stat)
                    .append(Text.literal(":${extra[ivKey]?.toIntOrNull() ?: 0}"))
                    .formatted(color) // 整段「stat:IV」同色（与弹窗行色一致，文字+数字都变色）
                    .append(Text.literal("  EV:${extra[evKey]?.toIntOrNull() ?: 0}").formatted(Formatting.RED))
            lines.add(ivLine(hp, "ivsHp", "evsHp", Formatting.GREEN))
            lines.add(ivLine(atk, "ivsAtk", "evsAtk", Formatting.RED))
            lines.add(ivLine(def, "ivsDef", "evsDef", Formatting.GOLD))
            lines.add(ivLine(spa, "ivsSpAtk", "evsSpAtk", Formatting.BLUE))
            lines.add(ivLine(spd, "ivsSpDef", "evsSpDef", Formatting.GREEN))
            lines.add(ivLine(spe, "ivsSpd", "evsSpd", Formatting.LIGHT_PURPLE))
            // 亲密度（与出价弹窗一致：粉色，六项个体值下方）
            lines.add(
                Text.translatable("cobblemarket.gui.friendship", extra["friendship"]?.toIntOrNull() ?: 0)
                    .styled { it.withColor(0xFF99CC) }
            )
        } else {
            lines.add(Text.translatable("cobblemarket.auction.count").append(Text.literal("${auction.count}")))
            // 附魔词条行（原版 tooltip 同款：附魔名 + 罗马等级，AQUA 色）
            lines.addAll(enchantmentLines(auction))
        }
        lines.add(
            Text.translatable("cobblemarket.auction.starting_price")
                .append(Text.literal("").append(CurrencyHandler.goldAmount(auction.startingPrice)).append(CurrencyHandler.goldCurrencyText()))
        )
        lines.add(Text.translatable("cobblemarket.auction.min_increment").append(Text.literal("${auction.minIncrement}")))
        val minutesLeft = ((auction.endsAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)
        lines.add(Text.translatable("cobblemarket.auction.ends").append(Text.translatable("cobblemarket.chat.ends_minutes", minutesLeft)))
        var hover: net.minecraft.text.MutableText = Text.literal("")
        lines.forEachIndexed { i, line ->
            hover = hover.append(line)
            if (i < lines.size - 1) hover = hover.append(Text.literal("\n"))
        }
        return hover
    }
}
