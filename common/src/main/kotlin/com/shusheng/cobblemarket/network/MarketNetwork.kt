package com.shusheng.cobblemarket.network

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.shusheng.cobblemarket.config.CurrencyHandler
import com.shusheng.cobblemarket.util.RequestThrottle
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.market.ItemListing
import com.shusheng.cobblemarket.market.ItemMarketState
import com.shusheng.cobblemarket.market.ListingStatus
import com.shusheng.cobblemarket.market.MarketListing
import com.shusheng.cobblemarket.market.MarketState
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer

import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID

// ── Data transfer objects ──

data class ListingEntry(
    val id: UUID,
    val sellerUuid: UUID,
    val species: String,
    val speciesId: String,
    val level: Int,
    val shiny: Boolean,
    val price: Int,
    val sellerName: String,
    val primaryType: String,
    val secondaryType: String,
    val ivsHp: Int, val ivsAtk: Int, val ivsDef: Int,
    val ivsSpAtk: Int, val ivsSpDef: Int, val ivsSpd: Int,
    // 极限特训值（hyper training，-1 = 未特训）：显示「真实值（特训值）」用
    val htHp: Int, val htAtk: Int, val htDef: Int,
    val htSpAtk: Int, val htSpDef: Int, val htSpd: Int,
    val nature: String,
    val natureBase: String,    // 原生性格（与 nature 不同 = 用过薄荷）
    val ability: String,
    val gender: String,
    val ball: String,
    val ballItem: String,
    val heldItemId: String,
    val currencyName: String,
    val aspects: List<String> // 精灵形态（性别/地区等），客户端渲染 3D 图标用
) {
    fun write(buf: PacketByteBuf) {
        buf.writeUuid(id)
        buf.writeUuid(sellerUuid)
        buf.writeString(species)
        buf.writeString(speciesId)
        buf.writeInt(level)
        buf.writeBoolean(shiny)
        buf.writeInt(price)
        buf.writeString(sellerName)
        buf.writeString(primaryType)
        buf.writeString(secondaryType)
        buf.writeInt(ivsHp); buf.writeInt(ivsAtk); buf.writeInt(ivsDef)
        buf.writeInt(ivsSpAtk); buf.writeInt(ivsSpDef); buf.writeInt(ivsSpd)
        buf.writeInt(htHp); buf.writeInt(htAtk); buf.writeInt(htDef)
        buf.writeInt(htSpAtk); buf.writeInt(htSpDef); buf.writeInt(htSpd)
        buf.writeString(nature)
        buf.writeString(natureBase)
        buf.writeString(ability)
        buf.writeString(gender)
        buf.writeString(ball)
        buf.writeString(ballItem)
        buf.writeString(heldItemId)
        buf.writeString(currencyName)
        buf.writeVarInt(aspects.size); aspects.forEach { buf.writeString(it) }
    }

    companion object {
        fun read(buf: PacketByteBuf): ListingEntry = ListingEntry(
            id = buf.readUuid(),
            sellerUuid = buf.readUuid(),
            species = buf.readString(),
            speciesId = buf.readString(),
            level = buf.readInt(),
            shiny = buf.readBoolean(),
            price = buf.readInt(),
            sellerName = buf.readString(),
            primaryType = buf.readString(),
            secondaryType = buf.readString(),
            ivsHp = buf.readInt(), ivsAtk = buf.readInt(), ivsDef = buf.readInt(),
            ivsSpAtk = buf.readInt(), ivsSpDef = buf.readInt(), ivsSpd = buf.readInt(),
            htHp = buf.readInt(), htAtk = buf.readInt(), htDef = buf.readInt(),
            htSpAtk = buf.readInt(), htSpDef = buf.readInt(), htSpd = buf.readInt(),
            nature = buf.readString(),
            natureBase = buf.readString(),
            ability = buf.readString(),
            gender = buf.readString(),
            ball = buf.readString(),
            ballItem = buf.readString(),
            heldItemId = buf.readString(),
            currencyName = buf.readString(),
            aspects = (0 until buf.readVarInt()).map { buf.readString() }
        )
    }
}

// ── S2C: 市场总开关状态（登录补发 + /market on|off 切换时全员广播） ──

data class MarketStatePayload(val enabled: Boolean) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<MarketStatePayload>(CobbleMarket.id("market_state"))
        val CODEC: PacketCodec<PacketByteBuf, MarketStatePayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.enabled) },
            { b -> MarketStatePayload(b.readBoolean()) }
        )
    }
}

// ── C2S: OP 在入口界面切换市场总开关 ──

data class SetMarketEnabledPayload(val enabled: Boolean) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<SetMarketEnabledPayload>(CobbleMarket.id("set_market_enabled"))
        val CODEC: PacketCodec<PacketByteBuf, SetMarketEnabledPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.enabled) },
            { b -> SetMarketEnabledPayload(b.readBoolean()) }
        )
    }
}

/** 市场总开关切换（命令与入口按钮共用）：落盘 + 全员广播 */
fun toggleMarketEnabled(server: net.minecraft.server.MinecraftServer, enabled: Boolean) {
    com.shusheng.cobblemarket.config.CobbleMarketConfig.setMarketEnabled(enabled)
    server.playerManager.playerList.forEach { sendToPlayer(it, MarketStatePayload(enabled)) }
}

/**
 * 市场总开关拦截：marketEnabled=false 时拒绝一切交易写操作并提示。
 * 只拦交易，不拦取回资产（待领取/余额领取等入口不调用本函数，与封禁语义一致）。
 */
fun marketBlocked(player: net.minecraft.server.network.ServerPlayerEntity): Boolean {
    if (com.shusheng.cobblemarket.config.CobbleMarketConfig.marketEnabled) return false
    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.market.closed")))
    return true
}

// ── C2S: Request market data ──

data class RequestMarketPayload(
    val speciesFilter: String,
    val shinyOnly: Boolean,
    val minLevel: Int,
    val maxLevel: Int,
    val sortMode: String,
    val page: Int,
    val genderFilter: String,
    val typeFilter: String,
    // 特性/性格筛选（翻译 key；空串 = 不限）
    val abilityFilter: String,
    val natureFilter: String,
    val ivExactHp: Int,
    val ivExactAtk: Int,
    val ivExactDef: Int,
    val ivExactSpAtk: Int,
    val ivExactSpDef: Int,
    val ivExactSpd: Int,
    // IV 比较方式：0 = 等于（默认），1 = 大于等于，2 = 小于等于
    val ivOpHp: Int,
    val ivOpAtk: Int,
    val ivOpDef: Int,
    val ivOpSpAtk: Int,
    val ivOpSpDef: Int,
    val ivOpSpd: Int,
    val pageSize: Int,
    val mineOnly: Boolean,
    // 特训筛选三态：0 = 不限，1 = 仅含训练，2 = 仅不含训练
    val htFilter: Int
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<RequestMarketPayload>(CobbleMarket.id("request_market"))
        val CODEC: PacketCodec<PacketByteBuf, RequestMarketPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.speciesFilter)
                b.writeBoolean(p.shinyOnly)
                b.writeInt(p.minLevel)
                b.writeInt(p.maxLevel)
                b.writeString(p.sortMode)
                b.writeInt(p.page)
                b.writeString(p.genderFilter)
                b.writeString(p.typeFilter)
                b.writeString(p.abilityFilter)
                b.writeString(p.natureFilter)
                b.writeInt(p.ivExactHp)
                b.writeInt(p.ivExactAtk)
                b.writeInt(p.ivExactDef)
                b.writeInt(p.ivExactSpAtk)
                b.writeInt(p.ivExactSpDef)
                b.writeInt(p.ivExactSpd)
                b.writeInt(p.ivOpHp)
                b.writeInt(p.ivOpAtk)
                b.writeInt(p.ivOpDef)
                b.writeInt(p.ivOpSpAtk)
                b.writeInt(p.ivOpSpDef)
                b.writeInt(p.ivOpSpd)
                b.writeInt(p.pageSize)
                b.writeBoolean(p.mineOnly)
                b.writeInt(p.htFilter)
            },
            // 读端用具名参数，读写字段顺序必须一致
            { b ->
                RequestMarketPayload(
                    speciesFilter = b.readString(),
                    shinyOnly = b.readBoolean(),
                    minLevel = b.readInt(),
                    maxLevel = b.readInt(),
                    sortMode = b.readString(),
                    page = b.readInt(),
                    genderFilter = b.readString(),
                    typeFilter = b.readString(),
                    abilityFilter = b.readString(),
                    natureFilter = b.readString(),
                    ivExactHp = b.readInt(),
                    ivExactAtk = b.readInt(),
                    ivExactDef = b.readInt(),
                    ivExactSpAtk = b.readInt(),
                    ivExactSpDef = b.readInt(),
                    ivExactSpd = b.readInt(),
                    ivOpHp = b.readInt(),
                    ivOpAtk = b.readInt(),
                    ivOpDef = b.readInt(),
                    ivOpSpAtk = b.readInt(),
                    ivOpSpDef = b.readInt(),
                    ivOpSpd = b.readInt(),
                    pageSize = b.readInt(),
                    mineOnly = b.readBoolean(),
                    htFilter = b.readInt()
                )
            }
        )
    }
}

// ── C2S: Admin request all Pokemon ──

data class AdminRequestPokemonPayload(
    val speciesFilter: String,
    val sellerFilter: String,
    val shinyOnly: Boolean,
    val sortMode: String,
    val page: Int,
    val ivExactHp: Int,
    val ivExactAtk: Int,
    val ivExactDef: Int,
    val ivExactSpAtk: Int,
    val ivExactSpDef: Int,
    val ivExactSpd: Int,
    // IV 比较方式：0 = 等于（默认），1 = 大于等于，2 = 小于等于
    val ivOpHp: Int,
    val ivOpAtk: Int,
    val ivOpDef: Int,
    val ivOpSpAtk: Int,
    val ivOpSpDef: Int,
    val ivOpSpd: Int,
    val pageSize: Int,
    val mineOnly: Boolean,
    // 特训筛选三态：0 = 不限，1 = 仅含训练，2 = 仅不含训练
    val htFilter: Int
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<AdminRequestPokemonPayload>(CobbleMarket.id("admin_request_pokemon"))
        val CODEC: PacketCodec<PacketByteBuf, AdminRequestPokemonPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.speciesFilter)
                b.writeString(p.sellerFilter)
                b.writeBoolean(p.shinyOnly)
                b.writeString(p.sortMode)
                b.writeInt(p.page)
                b.writeInt(p.ivExactHp)
                b.writeInt(p.ivExactAtk)
                b.writeInt(p.ivExactDef)
                b.writeInt(p.ivExactSpAtk)
                b.writeInt(p.ivExactSpDef)
                b.writeInt(p.ivExactSpd)
                b.writeInt(p.ivOpHp)
                b.writeInt(p.ivOpAtk)
                b.writeInt(p.ivOpDef)
                b.writeInt(p.ivOpSpAtk)
                b.writeInt(p.ivOpSpDef)
                b.writeInt(p.ivOpSpd)
                b.writeInt(p.pageSize)
                b.writeBoolean(p.mineOnly)
                b.writeInt(p.htFilter)
            },
            // 读端用具名参数，读写字段顺序必须一致
            { b ->
                AdminRequestPokemonPayload(
                    speciesFilter = b.readString(),
                    sellerFilter = b.readString(),
                    shinyOnly = b.readBoolean(),
                    sortMode = b.readString(),
                    page = b.readInt(),
                    ivExactHp = b.readInt(),
                    ivExactAtk = b.readInt(),
                    ivExactDef = b.readInt(),
                    ivExactSpAtk = b.readInt(),
                    ivExactSpDef = b.readInt(),
                    ivExactSpd = b.readInt(),
                    ivOpHp = b.readInt(),
                    ivOpAtk = b.readInt(),
                    ivOpDef = b.readInt(),
                    ivOpSpAtk = b.readInt(),
                    ivOpSpDef = b.readInt(),
                    ivOpSpd = b.readInt(),
                    pageSize = b.readInt(),
                    mineOnly = b.readBoolean(),
                    htFilter = b.readInt()
                )
            }
        )
    }
}

// ── C2S: Admin cancel Pokemon ──

data class AdminCancelPokemonPayload(val listingId: UUID) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<AdminCancelPokemonPayload>(CobbleMarket.id("admin_cancel_pokemon"))
        val CODEC: PacketCodec<PacketByteBuf, AdminCancelPokemonPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.listingId) },
            { b -> AdminCancelPokemonPayload(b.readUuid()) }
        )
    }
}

// ── S2C: Market data response ──

data class MarketDataPayload(
    val entries: List<ListingEntry>,
    val totalPages: Int,
    val currentPage: Int,
    val pendingBalance: Long,
    /** 待领取精灵数量（>0 时客户端收款按钮旁红点提示；管理端响应恒 0） */
    val pendingReturns: Int
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<MarketDataPayload>(CobbleMarket.id("market_data"))
        val CODEC: PacketCodec<PacketByteBuf, MarketDataPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.entries.size)
                p.entries.forEach { it.write(b) }
                b.writeInt(p.totalPages)
                b.writeInt(p.currentPage)
                b.writeLong(p.pendingBalance)
                b.writeVarInt(p.pendingReturns)
            },
            { b ->
                val size = b.readVarInt()
                val entries = (0 until size).map { ListingEntry.read(b) }
                MarketDataPayload(
                    entries = entries,
                    totalPages = b.readInt(),
                    currentPage = b.readInt(),
                    pendingBalance = b.readLong(),
                    pendingReturns = b.readVarInt()
                )
            }
        )
    }
}

// ── C2S: Buy listing ──

data class BuyFromMarketPayload(val listingId: UUID) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<BuyFromMarketPayload>(CobbleMarket.id("buy_from_market"))
        val CODEC: PacketCodec<PacketByteBuf, BuyFromMarketPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.listingId) },
            { b -> BuyFromMarketPayload(b.readUuid()) }
        )
    }
}

// ── C2S: Cancel listing ──

data class CancelFromMarketPayload(val listingId: UUID) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<CancelFromMarketPayload>(CobbleMarket.id("cancel_from_market"))
        val CODEC: PacketCodec<PacketByteBuf, CancelFromMarketPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.listingId) },
            { b -> CancelFromMarketPayload(b.readUuid()) }
        )
    }
}

// ── S2C: Action result ──

data class MarketResultPayload(
    val success: Boolean,
    val message: Text
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<MarketResultPayload>(CobbleMarket.id("market_result"))
        val CODEC: PacketCodec<PacketByteBuf, MarketResultPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.success); net.minecraft.text.TextCodecs.PACKET_CODEC.encode(b, p.message) },
            { b -> MarketResultPayload(b.readBoolean(), net.minecraft.text.TextCodecs.PACKET_CODEC.decode(b)) }
        )
    }
}

// ── S2C: Open market screen ──

data class OpenMarketPayload(val dummy: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<OpenMarketPayload>(CobbleMarket.id("open_market"))
        val CODEC: PacketCodec<PacketByteBuf, OpenMarketPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); OpenMarketPayload(0) }
        )
    }
}

// ── Pokemon preview for sell selection ──

data class PokemonPreview(
    val uuid: UUID,
    val species: String,
    val speciesId: String,
    val speciesName: String,
    val level: Int,
    val shiny: Boolean,
    val gender: String,
    val nature: String,        // 生效性格（薄荷后为薄荷性格）
    val natureBase: String,    // 原生性格（遗传用；与 nature 不同 = 用过薄荷）
    val ability: String,
    val ivsHp: Int, val ivsAtk: Int, val ivsDef: Int, val ivsSpAtk: Int, val ivsSpDef: Int, val ivsSpd: Int,
    // 极限特训值（hyper training，-1 = 未特训）：显示「真实值（特训值）」用
    val htHp: Int, val htAtk: Int, val htDef: Int, val htSpAtk: Int, val htSpDef: Int, val htSpd: Int,
    val ball: String,
    val primaryType: String,
    val secondaryType: String,
    val source: String, // "party" or "pc"
    val slot: Int,
    val heldItemId: String,
    val aspects: List<String> // 精灵形态（shiny/性别/地区形态等），客户端渲染 3D 图标用
) {
    fun write(buf: PacketByteBuf) {
        buf.writeUuid(uuid); buf.writeString(species); buf.writeString(speciesId); buf.writeString(speciesName)
        buf.writeInt(level); buf.writeBoolean(shiny); buf.writeString(gender)
        buf.writeString(nature); buf.writeString(natureBase); buf.writeString(ability)
        buf.writeInt(ivsHp); buf.writeInt(ivsAtk); buf.writeInt(ivsDef)
        buf.writeInt(ivsSpAtk); buf.writeInt(ivsSpDef); buf.writeInt(ivsSpd)
        buf.writeInt(htHp); buf.writeInt(htAtk); buf.writeInt(htDef)
        buf.writeInt(htSpAtk); buf.writeInt(htSpDef); buf.writeInt(htSpd)
        buf.writeString(ball); buf.writeString(primaryType); buf.writeString(secondaryType)
        buf.writeString(source); buf.writeInt(slot); buf.writeString(heldItemId)
        buf.writeVarInt(aspects.size); aspects.forEach { buf.writeString(it) }
    }

    companion object {
        fun read(buf: PacketByteBuf) = PokemonPreview(
            buf.readUuid(), buf.readString(), buf.readString(), buf.readString(),
            buf.readInt(), buf.readBoolean(), buf.readString(),
            buf.readString(), buf.readString(), buf.readString(),
            buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
            buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
            buf.readString(), buf.readString(), buf.readString(),
            buf.readString(), buf.readInt(), buf.readString(),
            (0 until buf.readVarInt()).map { buf.readString() }
        )
    }
}

// ── C2S: Request my Pokémon list ──

class RequestMyPokemonPayload(val page: Int, val requestId: Int) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestMyPokemonPayload>(CobbleMarket.id("request_my_pokemon"))
        val CODEC: PacketCodec<PacketByteBuf, RequestMyPokemonPayload> = PacketCodec.of(
            { p, b -> b.writeInt(p.page); b.writeInt(p.requestId) },
            { b -> RequestMyPokemonPayload(b.readInt(), b.readInt()) }
        )
    }
}

// ── S2C: My Pokémon list response ──

data class MyPokemonListPayload(
    val pokemon: List<PokemonPreview>,
    val page: Int,
    val requestId: Int,
    val hasMore: Boolean
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<MyPokemonListPayload>(CobbleMarket.id("my_pokemon_list"))
        val CODEC: PacketCodec<PacketByteBuf, MyPokemonListPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.page); b.writeInt(p.requestId); b.writeBoolean(p.hasMore)
                b.writeVarInt(p.pokemon.size); p.pokemon.forEach { it.write(b) }
            },
            { b ->
                val page = b.readInt()
                val requestId = b.readInt()
                val hasMore = b.readBoolean()
                MyPokemonListPayload(
                    (0 until b.readVarInt()).map { PokemonPreview.read(b) },
                    page, requestId, hasMore
                )
            }
        )
    }
}

// ── C2S: Collect pending balance ──

class CollectBalancePayload : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<CollectBalancePayload>(CobbleMarket.id("collect_balance"))
        val CODEC: PacketCodec<PacketByteBuf, CollectBalancePayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); CollectBalancePayload() }
        )
    }
}

// ── History ──

data class HistoryEntry(
    val type: String,
    val category: String,
    val species: String,
    val price: Int,
    val buyerName: String,
    val sellerName: String,
    val timestamp: Long
) {
    fun write(buf: PacketByteBuf) {
        buf.writeString(type); buf.writeString(category); buf.writeString(species); buf.writeInt(price); buf.writeString(
            buyerName
        ); buf.writeString(sellerName); buf.writeLong(timestamp)
    }

    companion object {
        fun read(buf: PacketByteBuf) = HistoryEntry(
            buf.readString(),
            buf.readString(),
            buf.readString(),
            buf.readInt(),
            buf.readString(),
            buf.readString(),
            buf.readLong()
        )
    }
}

data class RequestHistoryPayload(val all: Boolean) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestHistoryPayload>(CobbleMarket.id("request_history"))
        val CODEC: PacketCodec<PacketByteBuf, RequestHistoryPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.all) },
            { b -> RequestHistoryPayload(b.readBoolean()) }
        )
    }
}

data class HistoryDataPayload(val entries: List<HistoryEntry>) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<HistoryDataPayload>(CobbleMarket.id("history_data"))
        val CODEC: PacketCodec<PacketByteBuf, HistoryDataPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> HistoryDataPayload((0 until b.readVarInt()).map { HistoryEntry.read(b) }) }
        )
    }
}

// ── C2S: Sell from storage ──

data class SellFromStoragePayload(val pokemonUuid: UUID, val price: Int) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<SellFromStoragePayload>(CobbleMarket.id("sell_from_storage"))
        val CODEC: PacketCodec<PacketByteBuf, SellFromStoragePayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.pokemonUuid); b.writeInt(p.price) },
            { b -> SellFromStoragePayload(b.readUuid(), b.readInt()) }
        )
    }
}

// ── C2S: Sell item ──

data class SellItemPayload(val itemId: String, val itemNbt: NbtCompound, val count: Int, val price: Int) :
    CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<SellItemPayload>(CobbleMarket.id("sell_item"))
        val CODEC: PacketCodec<PacketByteBuf, SellItemPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.itemId); PacketCodecs.NBT_COMPOUND.encode(
                b,
                p.itemNbt
            ); b.writeInt(p.count); b.writeInt(p.price)
            },
            { b -> SellItemPayload(b.readString(), PacketCodecs.NBT_COMPOUND.decode(b), b.readInt(), b.readInt()) }
        )
    }
}

// ── C2S: Request item market ──

data class RequestItemMarketPayload(
    val sortMode: String,
    val page: Int,
    val mineOnly: Boolean,
    val pageSize: Int,
    val query: String,
    val itemIds: List<String>
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestItemMarketPayload>(CobbleMarket.id("request_item_market"))
        val CODEC: PacketCodec<PacketByteBuf, RequestItemMarketPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.sortMode); b.writeInt(p.page); b.writeBoolean(p.mineOnly); b.writeInt(p.pageSize); b.writeString(p.query)
                b.writeVarInt(p.itemIds.size); p.itemIds.forEach { b.writeString(it) }
            },
            { b -> RequestItemMarketPayload(b.readString(), b.readInt(), b.readBoolean(), b.readInt(), b.readString(), (0 until b.readVarInt()).map { b.readString() }) }
        )
    }
}

// ── S2C: Item market data ──

data class ItemMarketDataPayload(
    val entries: List<ItemEntry>,
    val totalPages: Int,
    val currentPage: Int,
    val pendingBalance: Long,
    /** 待领取物品数量（>0 时客户端收款按钮旁红点提示；管理端响应恒 0） */
    val pendingReturns: Int
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<ItemMarketDataPayload>(CobbleMarket.id("item_market_data"))
        val CODEC: PacketCodec<PacketByteBuf, ItemMarketDataPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.entries.size)
                p.entries.forEach { it.write(b) }
                b.writeInt(p.totalPages)
                b.writeInt(p.currentPage)
                b.writeLong(p.pendingBalance)
                b.writeVarInt(p.pendingReturns)
            },
            { b ->
                val size = b.readVarInt()
                val entries = (0 until size).map { ItemEntry.read(b) }
                ItemMarketDataPayload(
                    entries = entries,
                    totalPages = b.readInt(),
                    currentPage = b.readInt(),
                    pendingBalance = b.readLong(),
                    pendingReturns = b.readVarInt()
                )
            }
        )
    }
}

// ── C2S: Admin request all items ──

data class AdminRequestItemPayload(
    val sellerFilter: String,
    val itemFilter: String,
    val itemIds: List<String>,
    val sortMode: String,
    val page: Int,
    val pageSize: Int,
    val mineOnly: Boolean
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<AdminRequestItemPayload>(CobbleMarket.id("admin_request_item"))
        val CODEC: PacketCodec<PacketByteBuf, AdminRequestItemPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.sellerFilter); b.writeString(p.itemFilter)
                b.writeVarInt(p.itemIds.size); p.itemIds.forEach { b.writeString(it) }
                b.writeString(p.sortMode); b.writeInt(p.page); b.writeInt(p.pageSize); b.writeBoolean(
                p.mineOnly
            )
            },
            { b -> AdminRequestItemPayload(b.readString(), b.readString(), (0 until b.readVarInt()).map { b.readString() }, b.readString(), b.readInt(), b.readInt(), b.readBoolean()) }
        )
    }
}

// ── C2S: Admin cancel item ──

data class AdminCancelItemPayload(val listingId: UUID) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<AdminCancelItemPayload>(CobbleMarket.id("admin_cancel_item"))
        val CODEC: PacketCodec<PacketByteBuf, AdminCancelItemPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.listingId) },
            { b -> AdminCancelItemPayload(b.readUuid()) }
        )
    }
}

// ── C2S: Buy item ──

data class BuyItemPayload(val listingId: UUID, val count: Int) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<BuyItemPayload>(CobbleMarket.id("buy_item"))
        val CODEC: PacketCodec<PacketByteBuf, BuyItemPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.listingId); b.writeInt(p.count) },
            { b -> BuyItemPayload(b.readUuid(), b.readInt()) }
        )
    }
}

// ── C2S: Cancel item ──

data class CancelItemPayload(val listingId: UUID) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<CancelItemPayload>(CobbleMarket.id("cancel_item"))
        val CODEC: PacketCodec<PacketByteBuf, CancelItemPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.listingId) },
            { b -> CancelItemPayload(b.readUuid()) }
        )
    }
}

// ── C2S: Request pokemon returns ──

data class RequestPokemonReturnPayload(val page: Int, val pageSize: Int) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestPokemonReturnPayload>(CobbleMarket.id("request_pokemon_return"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPokemonReturnPayload> = PacketCodec.of(
            { p, b -> b.writeInt(p.page); b.writeInt(p.pageSize) },
            { b -> RequestPokemonReturnPayload(b.readInt(), b.readInt()) }
        )
    }
}

// ── S2C: Pokemon return data ──

data class PokemonReturnDataPayload(
    val pokemon: List<PokemonPreview>,
    val totalPages: Int,
    val currentPage: Int
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PokemonReturnDataPayload>(CobbleMarket.id("pokemon_return_data"))
        val CODEC: PacketCodec<PacketByteBuf, PokemonReturnDataPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.pokemon.size); p.pokemon.forEach { it.write(b) }
                b.writeInt(p.totalPages); b.writeInt(p.currentPage)
            },
            { b ->
                val pokemon = (0 until b.readVarInt()).map { PokemonPreview.read(b) }
                PokemonReturnDataPayload(pokemon, b.readInt(), b.readInt())
            }
        )
    }
}

// ── C2S: Claim pokemon returns ──

class ClaimPokemonReturnPayload : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<ClaimPokemonReturnPayload>(CobbleMarket.id("claim_pokemon_return"))
        val CODEC: PacketCodec<PacketByteBuf, ClaimPokemonReturnPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); ClaimPokemonReturnPayload() }
        )
    }
}

// ── C2S: Request item returns ──

data class RequestItemReturnPayload(val page: Int, val pageSize: Int) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestItemReturnPayload>(CobbleMarket.id("request_item_return"))
        val CODEC: PacketCodec<PacketByteBuf, RequestItemReturnPayload> = PacketCodec.of(
            { p, b -> b.writeInt(p.page); b.writeInt(p.pageSize) },
            { b -> RequestItemReturnPayload(b.readInt(), b.readInt()) }
        )
    }
}

// ── S2C: Item return data ──

data class ItemReturnDataPayload(
    val items: List<ItemEntry>,
    val totalPages: Int,
    val currentPage: Int
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<ItemReturnDataPayload>(CobbleMarket.id("item_return_data"))
        val CODEC: PacketCodec<PacketByteBuf, ItemReturnDataPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.items.size); p.items.forEach { it.write(b) }
                b.writeInt(p.totalPages); b.writeInt(p.currentPage)
            },
            { b ->
                val items = (0 until b.readVarInt()).map { ItemEntry.read(b) }
                ItemReturnDataPayload(items, b.readInt(), b.readInt())
            }
        )
    }
}

// ── C2S: Claim item returns ──

data class ClaimItemReturnPayload(
    /** 玩家个人设置：装不下的部分掉在地上（默认关=留在待领取） */
    val dropOverflow: Boolean
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<ClaimItemReturnPayload>(CobbleMarket.id("claim_item_return"))
        val CODEC: PacketCodec<PacketByteBuf, ClaimItemReturnPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.dropOverflow) },
            { b -> ClaimItemReturnPayload(dropOverflow = b.readBoolean()) }
        )
    }
}

// ── Registration ──

private fun parseSortMode(name: String): com.shusheng.cobblemarket.market.SortMode =
    com.shusheng.cobblemarket.market.SortMode.entries.firstOrNull { it.name == name }
        ?: com.shusheng.cobblemarket.market.SortMode.PRICE_ASC

// 退还物品到背包：insertStack 会扣减传入栈的 count（放入部分），
// 未完全放入的剩余部分掉落到地面（拾取延迟 0），确保玩家不损失物品
fun giveBackItem(stack: ItemStack, player: ServerPlayerEntity) {
    if (stack.isEmpty) return
    player.inventory.insertStack(stack)
    if (!stack.isEmpty) {
        val entity = player.dropItem(stack, false)
        entity?.setPickupDelay(0)
    }
    player.inventory.markDirty()
}

/** 是否为蛋类物品（Cobbreeding 蛋：namespace 为 cobbreeding、path 以 pokemon_egg 结尾，含通用蛋与全部属性蛋；不依赖其类名） */
fun isEggItem(itemId: String): Boolean {
    val id = net.minecraft.util.Identifier.tryParse(itemId) ?: return false
    return id.namespace == "cobbreeding" && id.path.endsWith("pokemon_egg")
}

/** 客户端语言物种名 → 资源路径名（照 MarketScreen 原私有实现；服务端只存英文资源名，中文搜索词必须在客户端转 id） */
fun localizeSpeciesQuery(raw: String): String {
    val q = raw.trim()
    if (q.isEmpty() || q.all { it.code < 128 }) return q
    com.cobblemon.mod.common.api.pokemon.PokemonSpecies.implemented
        .firstOrNull { it.translatedName.string.contains(q) }
        ?.let { return it.resourceIdentifier.path }
    return q
}

/** 客户端语言物品名搜索 → 匹配的物品 id 集合（服务端语言与客户端不同时靠 id 传递过滤；含 id 路径匹配，英文查询同样覆盖） */
fun resolveItemIdsByQuery(query: String): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    net.minecraft.registry.Registries.ITEM.forEach { item ->
        val id = net.minecraft.registry.Registries.ITEM.getId(item)
        if (id.path.contains(q, ignoreCase = true) || item.name.string.contains(q, ignoreCase = true)) {
            result.add(id.toString())
            if (result.size >= 200) return result
        }
    }
    return result
}

/**
 * 交易匹配：物品相同且组件一致；动态组件（cobbreeding 蛋的孵化计时 timer/second 持续变化、
 * version 组件在服务端重建时会被 verifyComponentsAfterLoad 迁移改写）导致完整比较永远失败——
 * 剔除动态组件后再比较一次。
 * 宽松分支仅对蛋类物品生效：非蛋物品一律严格比较（避免未来其它物品的 :timer 等组件承载区分性数据时被误判相同）。
 * 蛋的安全边界：只忽略 :timer / :second / :version 结尾的组件，蛋数据（egg_info）等其余组件仍严格比较，不会混淆不同物品。
 */
fun itemsEqualForTrading(a: ItemStack, b: ItemStack): Boolean {
    if (a.isEmpty || b.isEmpty) return false
    if (!a.isOf(b.item)) return false
    if (ItemStack.areItemsAndComponentsEqual(a, b)) return true
    if (!isEggItem(Registries.ITEM.getId(a.item).toString())) return false
    return componentEntriesIgnoringDynamic(a) == componentEntriesIgnoringDynamic(b)
}

private fun componentEntriesIgnoringDynamic(stack: ItemStack): Set<Pair<String, Any?>> {
    val out = mutableSetOf<Pair<String, Any?>>()
    stack.components.stream().forEach { entry ->
        val id = Registries.DATA_COMPONENT_TYPE.getId(entry.type()).toString()
        if (!id.endsWith(":timer") && !id.endsWith(":second") && !id.endsWith(":version")) {
            out.add(id to entry.value())
        }
    }
    return out
}

// 预检：insertStack（PlayerInventory）只往 main 放（getEmptySlot 只遍历 main），
// 预检同样只数 main，与实际插入行为一致，不会误拒合法交易
internal fun canFitInInventory(player: ServerPlayerEntity, stack: ItemStack): Boolean {
    var remaining = stack.count
    val main = player.inventory.main
    for (i in 0 until main.size) {
        val slot = main[i]
        if (!slot.isEmpty && itemsEqualForTrading(slot, stack)) {
            remaining -= (slot.maxCount - slot.count)
            if (remaining <= 0) return true
        }
    }
    for (i in 0 until main.size) {
        if (main[i].isEmpty) {
            remaining -= stack.maxCount
            if (remaining <= 0) return true
        }
    }
    return false
}

object MarketNetwork {

    fun register() {
        registerS2CType(MarketStatePayload.ID, MarketStatePayload.CODEC)
        registerS2CType(MyPokemonListPayload.ID, MyPokemonListPayload.CODEC)
        registerS2CType(HistoryDataPayload.ID, HistoryDataPayload.CODEC)
        registerS2CType(OpenMarketPayload.ID, OpenMarketPayload.CODEC)
        registerS2CType(MarketDataPayload.ID, MarketDataPayload.CODEC)
        registerS2CType(ItemMarketDataPayload.ID, ItemMarketDataPayload.CODEC)
        registerS2CType(PokemonReturnDataPayload.ID, PokemonReturnDataPayload.CODEC)
        registerS2CType(ItemReturnDataPayload.ID, ItemReturnDataPayload.CODEC)
        registerS2CType(MarketResultPayload.ID, MarketResultPayload.CODEC)

        registerC2S(RequestMarketPayload.ID, RequestMarketPayload.CODEC) { payload, player ->
            // 250ms：与客户端搜索防抖（250ms）配合——防抖保证正常操作下两次请求间隔
            // 至少 250ms，最终态请求不会被节流误丢；仍拦得住恶意高频全量搜索
            if (!RequestThrottle.allow(player.uuid, "request_market", 250L)) return@registerC2S
            val server = player.server
            server.execute {
                val state = MarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())

                val sortMode = parseSortMode(payload.sortMode)
                val results = state.search(
                    species = payload.speciesFilter.ifBlank { null },
                    shiny = if (payload.shinyOnly) true else null,
                    minLevel = if (payload.minLevel > 0) payload.minLevel else null,
                    maxLevel = if (payload.maxLevel >= 0) payload.maxLevel else null,
                    sortBy = sortMode,
                    gender = payload.genderFilter.ifBlank { null },
                    typeFilter = payload.typeFilter.ifBlank { null },
                    ability = payload.abilityFilter.ifBlank { null },
                    nature = payload.natureFilter.ifBlank { null },
                    ivExact = buildMap {
                        if (payload.ivExactHp >= 0) put("ivsHp", payload.ivExactHp)
                        if (payload.ivExactAtk >= 0) put("ivsAtk", payload.ivExactAtk)
                        if (payload.ivExactDef >= 0) put("ivsDef", payload.ivExactDef)
                        if (payload.ivExactSpAtk >= 0) put("ivsSpAtk", payload.ivExactSpAtk)
                        if (payload.ivExactSpDef >= 0) put("ivsSpDef", payload.ivExactSpDef)
                        if (payload.ivExactSpd >= 0) put("ivsSpd", payload.ivExactSpd)
                    },
                    ivOps = buildMap {
                        if (payload.ivExactHp >= 0) put("ivsHp", payload.ivOpHp.coerceIn(0, 2))
                        if (payload.ivExactAtk >= 0) put("ivsAtk", payload.ivOpAtk.coerceIn(0, 2))
                        if (payload.ivExactDef >= 0) put("ivsDef", payload.ivOpDef.coerceIn(0, 2))
                        if (payload.ivExactSpAtk >= 0) put("ivsSpAtk", payload.ivOpSpAtk.coerceIn(0, 2))
                        if (payload.ivExactSpDef >= 0) put("ivsSpDef", payload.ivOpSpDef.coerceIn(0, 2))
                        if (payload.ivExactSpd >= 0) put("ivsSpd", payload.ivOpSpd.coerceIn(0, 2))
                    },
                    sellerUuid = if (payload.mineOnly) player.uuid else null,
                    htFilter = payload.htFilter
                )

                val pageSize = payload.pageSize.coerceIn(1, 30)
                val totalPages = ((results.size - 1) / pageSize) + 1
                val clampedPage = payload.page.coerceIn(1, maxOf(1, totalPages))

                val pageEntries = if (results.isEmpty()) emptyList() else {
                    val start = (clampedPage - 1) * pageSize
                    results.drop(start).take(pageSize).map { listing ->
                        val detail = listing.extraData
                        ListingEntry(
                            id = listing.id,
                            sellerUuid = listing.sellerUuid,
                            species = listing.species,
                            speciesId = detail["speciesId"] ?: listing.species.lowercase().replace(" ", "_"),
                            level = listing.level,
                            shiny = listing.shiny,
                            price = listing.price,
                            sellerName = listing.sellerName,
                            primaryType = detail["primaryType"] ?: "normal",
                            secondaryType = detail["secondaryType"] ?: "",
                            ivsHp = detail["ivsHp"]?.toIntOrNull() ?: 0,
                            ivsAtk = detail["ivsAtk"]?.toIntOrNull() ?: 0,
                            ivsDef = detail["ivsDef"]?.toIntOrNull() ?: 0,
                            ivsSpAtk = detail["ivsSpAtk"]?.toIntOrNull() ?: 0,
                            ivsSpDef = detail["ivsSpDef"]?.toIntOrNull() ?: 0,
                            ivsSpd = detail["ivsSpd"]?.toIntOrNull() ?: 0,
                            htHp = detail["htHp"]?.toIntOrNull() ?: -1,
                            htAtk = detail["htAtk"]?.toIntOrNull() ?: -1,
                            htDef = detail["htDef"]?.toIntOrNull() ?: -1,
                            htSpAtk = detail["htSpAtk"]?.toIntOrNull() ?: -1,
                            htSpDef = detail["htSpDef"]?.toIntOrNull() ?: -1,
                            htSpd = detail["htSpd"]?.toIntOrNull() ?: -1,
                            nature = detail["nature"] ?: "?",
                            natureBase = detail["natureBase"] ?: detail["nature"] ?: "?",
                            ability = detail["ability"] ?: "?",
                            gender = detail["gender"] ?: "?",
                            ball = detail["ball"] ?: "?",
                            ballItem = detail["ballItem"] ?: "cobblemon:poke_ball",
                            heldItemId = detail["heldItemId"] ?: "",
                            currencyName = com.shusheng.cobblemarket.config.CurrencyHandler.getCurrencyId(),
                            aspects = parseAspects(detail)
                        )
                    }
                }

                sendToPlayer(
                    player,
                    MarketDataPayload(
                        pageEntries,
                        maxOf(1, totalPages),
                        clampedPage,
                        state.getPendingBalance(player.uuid),
                        state.getPendingReturns(player.uuid).size
                    )
                )
            }
        }

        registerC2S(BuyFromMarketPayload.ID, BuyFromMarketPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "buy_pokemon", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val banCheckTime = System.currentTimeMillis()
                val banInfo = BanState.get(server).getBanInfo(player.uuid, banCheckTime)
                if (marketBlocked(player)) return@execute
                if (banInfo != null) {
                    // 保留 Text 对象而非 .string：翻译在客户端语言下渲染（服务端语言 ≠ 客户端语言）
                    val timeDesc: Text = if (banInfo.isPermanent)
                        Text.translatable("cobblemarket.ban.permanent")
                    else
                        Text.translatable(
                            "cobblemarket.ban.remaining",
                            BanState.formatRemaining(banInfo.expiresAt!! - banCheckTime)
                        )
                    val banMsg = if (banInfo.reason.isNotBlank())
                        Text.translatable("cobblemarket.ban.banned_msg_time_reason", timeDesc, banInfo.reason)
                    else
                        Text.translatable("cobblemarket.ban.banned_msg_time", timeDesc)
                    sendToPlayer(player, MarketResultPayload(false, banMsg))
                    return@execute
                }
                val state = MarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())
                val listing = state.getListing(payload.listingId)

                if (listing == null || !listing.isActive()) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                if (listing.sellerUuid == player.uuid) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.cannot_buy_own"))
                    )
                    return@execute
                }
                // 先校验挂单数据可加载，再扣款，避免解析失败吞掉买家的钱
                val registryLookup = player.serverWorld.registryManager
                val pokemon = try {
                    com.cobblemon.mod.common.pokemon.Pokemon().loadFromNBT(registryLookup, listing.pokemonNbt)
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.warn("Failed to load pokemon NBT for listing {}: {}", listing.id, e.message)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }

                // 黑名单检查：拦截上架后被加入黑名单的存量挂单（治理即时生效）
                if (com.shusheng.cobblemarket.market.PokemonBlacklistState.get(server)
                        .isBlacklisted(pokemon)
                ) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.blocked"))
                    )
                    return@execute
                }

                if (!removeCurrency(player, listing.price)) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(
                            false,
                            Text.translatable(
                                "cobblemarket.network.need_diamonds",
                                com.shusheng.cobblemarket.config.CurrencyHandler.goldAmount(listing.price),
                                com.shusheng.cobblemarket.config.CurrencyHandler.goldCurrencyText()
                            )
                        )
                    )
                    return@execute
                }

                val party = Cobblemon.storage.getParty(player)
                val added = try {
                    party.add(pokemon)
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.warn("Failed to add pokemon to party for buyer {}: {}", player.uuid, e.message)
                    false
                }
                if (!added) {
                    val refunded = giveCurrency(player, listing.price)
                    if (refunded < listing.price.toLong()) {
                        // 退款未全部到账（背包满）：差额转入待领余额兜底，避免买家钱被吞
                        state.addPendingBalance(player.uuid, listing.price.toLong() - refunded)
                        CobbleMarket.LOGGER.error(
                            "Partial refund for buyer {} on listing {}; {} moved to pending balance",
                            player.uuid, listing.id, listing.price.toLong() - refunded
                        )
                    }
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.storage_full"))
                    )
                    return@execute
                }

                state.addPendingBalance(listing.sellerUuid, listing.price.toLong())
                listing.status = ListingStatus.SOLD
                state.markModified()
                com.shusheng.cobblemarket.event.MarketEvents.PURCHASE.trigger(
                    com.shusheng.cobblemarket.event.PurchaseEvent(
                        player.uuid,
                        player.name.string,
                        listing.sellerUuid,
                        listing,
                        listing.price
                    )
                )

                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(
                        true,
                        Text.translatable(
                            "cobblemarket.network.bought",
                            listing.speciesText(),
                            com.shusheng.cobblemarket.config.CurrencyHandler.goldAmount(listing.price),
                            com.shusheng.cobblemarket.config.CurrencyHandler.goldCurrencyText()
                        )
                    )
                )

                // 买家庆祝动画：精灵已进队伍，所有权转移完成
                CelebrationNetwork.sendFromEntry(player, listing.species, listing.shiny, listing.extraData, CelebrationSource.MARKET)

                // 卖家离线则入队补发
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                    server, listing.sellerUuid,
                    Text.translatable("cobblemarket.network.sold", listing.speciesText())
                )

                // 精灵已进买家队伍，挂单生命周期终结，立即删除避免存档膨胀
                state.removeListing(listing.id)
            }
        }

        registerC2S(CancelFromMarketPayload.ID, CancelFromMarketPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "cancel_pokemon", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 封禁只禁止交易；取消挂单是取回自己的资产，允许
                val state = MarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())
                val listing = state.getListing(payload.listingId)

                if (listing == null || !listing.isActive()) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                if (listing.sellerUuid != player.uuid) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.cmd.not_your_listing"))
                    )
                    return@execute
                }

                val pokemon = try {
                    com.cobblemon.mod.common.pokemon.Pokemon()
                        .loadFromNBT(player.serverWorld.registryManager, listing.pokemonNbt)
                } catch (e: Exception) {
                    // 挂单数据损坏时保留 ACTIVE 状态，等待管理员处理，避免精灵凭空消失
                    CobbleMarket.LOGGER.warn("Failed to load pokemon NBT for listing {}: {}", listing.id, e.message)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                val party = Cobblemon.storage.getParty(player)
                if (!party.add(pokemon)) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.storage_full"))
                    )
                    return@execute
                }

                listing.status = ListingStatus.CANCELLED
                state.markModified()
                com.shusheng.cobblemarket.event.MarketEvents.CANCEL.trigger(
                    com.shusheng.cobblemarket.event.CancelEvent(
                        player.uuid,
                        listing
                    )
                )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(true, Text.translatable("cobblemarket.cmd.cancelled", listing.speciesText()))
                )

                // 精灵已回卖家队伍，挂单生命周期终结，立即删除避免存档膨胀
                state.removeListing(listing.id)
            }
        }

        registerC2S(AdminRequestPokemonPayload.ID, AdminRequestPokemonPayload.CODEC) { payload, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                val state = MarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())

                val sortMode = parseSortMode(payload.sortMode)
                val results = state.search(
                    species = payload.speciesFilter.ifBlank { null },
                    shiny = if (payload.shinyOnly) true else null,
                    sortBy = sortMode,
                    ivExact = buildMap {
                        if (payload.ivExactHp >= 0) put("ivsHp", payload.ivExactHp)
                        if (payload.ivExactAtk >= 0) put("ivsAtk", payload.ivExactAtk)
                        if (payload.ivExactDef >= 0) put("ivsDef", payload.ivExactDef)
                        if (payload.ivExactSpAtk >= 0) put("ivsSpAtk", payload.ivExactSpAtk)
                        if (payload.ivExactSpDef >= 0) put("ivsSpDef", payload.ivExactSpDef)
                        if (payload.ivExactSpd >= 0) put("ivsSpd", payload.ivExactSpd)
                    },
                    ivOps = buildMap {
                        if (payload.ivExactHp >= 0) put("ivsHp", payload.ivOpHp.coerceIn(0, 2))
                        if (payload.ivExactAtk >= 0) put("ivsAtk", payload.ivOpAtk.coerceIn(0, 2))
                        if (payload.ivExactDef >= 0) put("ivsDef", payload.ivOpDef.coerceIn(0, 2))
                        if (payload.ivExactSpAtk >= 0) put("ivsSpAtk", payload.ivOpSpAtk.coerceIn(0, 2))
                        if (payload.ivExactSpDef >= 0) put("ivsSpDef", payload.ivOpSpDef.coerceIn(0, 2))
                        if (payload.ivExactSpd >= 0) put("ivsSpd", payload.ivOpSpd.coerceIn(0, 2))
                    },
                    sellerUuid = if (payload.mineOnly) player.uuid else null,
                    sellerName = payload.sellerFilter.ifBlank { null },
                    htFilter = payload.htFilter
                )

                val pageSize = payload.pageSize.coerceIn(1, 30)
                val totalPages = ((results.size - 1) / pageSize) + 1
                val clampedPage = payload.page.coerceIn(1, maxOf(1, totalPages))

                val pageEntries = if (results.isEmpty()) emptyList() else {
                    val start = (clampedPage - 1) * pageSize
                    results.drop(start).take(pageSize).map { listing ->
                        val detail = listing.extraData
                        ListingEntry(
                            id = listing.id,
                            sellerUuid = listing.sellerUuid,
                            species = listing.species,
                            speciesId = detail["speciesId"] ?: listing.species.lowercase().replace(" ", "_"),
                            level = listing.level,
                            shiny = listing.shiny,
                            price = listing.price,
                            sellerName = listing.sellerName,
                            primaryType = detail["primaryType"] ?: "normal",
                            secondaryType = detail["secondaryType"] ?: "",
                            ivsHp = detail["ivsHp"]?.toIntOrNull() ?: 0,
                            ivsAtk = detail["ivsAtk"]?.toIntOrNull() ?: 0,
                            ivsDef = detail["ivsDef"]?.toIntOrNull() ?: 0,
                            ivsSpAtk = detail["ivsSpAtk"]?.toIntOrNull() ?: 0,
                            ivsSpDef = detail["ivsSpDef"]?.toIntOrNull() ?: 0,
                            ivsSpd = detail["ivsSpd"]?.toIntOrNull() ?: 0,
                            htHp = detail["htHp"]?.toIntOrNull() ?: -1,
                            htAtk = detail["htAtk"]?.toIntOrNull() ?: -1,
                            htDef = detail["htDef"]?.toIntOrNull() ?: -1,
                            htSpAtk = detail["htSpAtk"]?.toIntOrNull() ?: -1,
                            htSpDef = detail["htSpDef"]?.toIntOrNull() ?: -1,
                            htSpd = detail["htSpd"]?.toIntOrNull() ?: -1,
                            nature = detail["nature"] ?: "?",
                            natureBase = detail["natureBase"] ?: detail["nature"] ?: "?",
                            ability = detail["ability"] ?: "?",
                            gender = detail["gender"] ?: "?",
                            ball = detail["ball"] ?: "?",
                            ballItem = detail["ballItem"] ?: "cobblemon:poke_ball",
                            heldItemId = detail["heldItemId"] ?: "",
                            currencyName = com.shusheng.cobblemarket.config.CurrencyHandler.getCurrencyId(),
                            aspects = parseAspects(detail)
                        )
                    }
                }

                sendToPlayer(player, MarketDataPayload(pageEntries, maxOf(1, totalPages), clampedPage, 0L, 0))
            }
        }

        registerC2S(AdminCancelPokemonPayload.ID, AdminCancelPokemonPayload.CODEC) { payload, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                val state = MarketState.get(server)
                val listing = state.getListing(payload.listingId)
                if (listing == null || !listing.isActive()) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                listing.status = ListingStatus.CANCELLED
                state.addPendingReturn(listing.sellerUuid, listing)
                state.markModified()
                com.shusheng.cobblemarket.event.MarketEvents.CANCEL.trigger(
                    com.shusheng.cobblemarket.event.CancelEvent(listing.sellerUuid, listing)
                )
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                    server, listing.sellerUuid,
                    Text.translatable("cobblemarket.cmd.cancelled", listing.speciesText())
                )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(
                        true,
                        Text.translatable("cobblemarket.op.cancelled", listing.sellerName, listing.speciesText())
                    )
                )
            }
        }

        registerC2S(AdminRequestItemPayload.ID, AdminRequestItemPayload.CODEC) { payload, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                val state = ItemMarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())

                val sortMode = parseSortMode(payload.sortMode)
                val results = state.search(
                    sortBy = sortMode,
                    sellerUuid = if (payload.mineOnly) player.uuid else null,
                    sellerName = payload.sellerFilter.ifBlank { null }
                ).let { list ->
                    val query = payload.itemFilter.trim()
                    if (query.isEmpty()) list
                    else list.filter { it.itemId in payload.itemIds }
                }

                // 上限 84（12 行）：网格页容量随窗口，上限低于容量会导致末行空槽+多余分页；
                // 84 是服务器压力折中：恶意高频请求（250ms 节流下每秒 4 次）的带宽攻击面减半，正常窗口（≤12 行）无感知
                // 物品 itemNbt 通常几百字节（蛋较大，最坏全蛋页约 1MB，可接受）
                val pageSize = payload.pageSize.coerceIn(1, 84)
                val totalPages = ((results.size - 1) / pageSize) + 1
                val clampedPage = payload.page.coerceIn(1, maxOf(1, totalPages))

                val pageEntries = if (results.isEmpty()) emptyList() else {
                    val start = (clampedPage - 1) * pageSize
                    results.drop(start).take(pageSize).map { listing ->
                        ItemEntry(
                            id = listing.id,
                            sellerUuid = listing.sellerUuid,
                            sellerName = listing.sellerName,
                            itemId = listing.itemId,
                            itemNbt = listing.itemNbt,
                            count = listing.count,
                            price = listing.price,
                            currencyName = CurrencyHandler.getCurrencyId()
                        )
                    }
                }

                sendToPlayer(
                    player,
                    ItemMarketDataPayload(pageEntries, maxOf(1, totalPages), clampedPage, 0L, 0)
                )
            }
        }

        registerC2S(AdminCancelItemPayload.ID, AdminCancelItemPayload.CODEC) { payload, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                val state = ItemMarketState.get(server)
                val listing = state.getListing(payload.listingId)
                if (listing == null || !listing.isActive()) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                listing.status = ListingStatus.CANCELLED
                state.addPendingReturn(listing.sellerUuid, listing)
                state.markModified()
                com.shusheng.cobblemarket.event.TransactionHistory.get(server).addRecord(
                    com.shusheng.cobblemarket.event.TransactionRecord(
                        timestamp = System.currentTimeMillis(),
                        type = com.shusheng.cobblemarket.event.TransactionType.CANCEL,
                        category = com.shusheng.cobblemarket.event.TransactionCategory.ITEM,
                        sellerUuid = listing.sellerUuid,
                        sellerName = listing.sellerName,
                        buyerUuid = null,
                        buyerName = "",
                        species = listing.itemId,
                        price = listing.price,
                        fee = 0,
                        detail = com.shusheng.cobblemarket.util.RecordDetail.item(listing.itemNbt, listing.count)
                    )
                )
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                    server, listing.sellerUuid,
                    Text.translatable("cobblemarket.item.cancelled")
                )
                // 物品名传翻译 Text（客户端按玩家语言渲染），与精灵下架的 speciesText() 一致，不能传裸 itemId
                val itemName = Identifier.tryParse(listing.itemId)
                    ?.let { Registries.ITEM.get(it).name }
                    ?: Text.literal(listing.itemId)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(
                        true,
                        Text.translatable("cobblemarket.op.cancelled", listing.sellerName, itemName)
                    )
                )
            }
        }

        registerC2S(RequestMyPokemonPayload.ID, RequestMyPokemonPayload.CODEC) { payload, player ->
            // page 超出合理范围直接丢弃，防 page * pageSize 溢出
            if (payload.page !in 0..10000) return@registerC2S
            // page 0 是开关界面的入口，保留节流防刷；分页请求由响应驱动（拿到响应才会发下一个），
            // 天然有节奏且只读无副作用，不节流——本地服务器往返 <1ms，500ms 冷却会把整条链全部拒掉。
            if (payload.page == 0 && !RequestThrottle.allow(player.uuid, "request_my_pokemon", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                val pageSize = 100
                val previews = mutableListOf<PokemonPreview>()
                if (payload.page == 0) {
                    // 队伍精灵固定显示在列表顶部，只随第一页发送
                    val party = Cobblemon.storage.getParty(player)
                    for (i in 0..5) {
                        try {
                            val p = party.get(PartyPosition(i)) ?: continue
                            previews.add(toPreview(p, "party", i))
                        } catch (_: Exception) {
                        }
                    }
                }
                var hasMore = false
                try {
                    val pc = Cobblemon.storage.getPC(player)
                    // PC 按全局偏移分页切片：坏精灵计入偏移但不占名额，保证翻页切片不漂移
                    var idx = 0
                    var sent = 0
                    val skip = payload.page * pageSize
                    val iter = pc.iterator()
                    while (iter.hasNext()) {
                        val p = iter.next()
                        if (idx++ < skip) continue
                        if (sent >= pageSize) { hasMore = true; break }
                        try {
                            previews.add(toPreview(p, "pc", idx - 1))
                            sent++
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
                sendToPlayer(player, MyPokemonListPayload(previews, payload.page, payload.requestId, hasMore))
            }
        }

        registerC2S(SellFromStoragePayload.ID, SellFromStoragePayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "sell_from_storage", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val banCheckTime = System.currentTimeMillis()
                val banInfo = BanState.get(server).getBanInfo(player.uuid, banCheckTime)
                if (marketBlocked(player)) return@execute
                if (banInfo != null) {
                    // 保留 Text 对象而非 .string：翻译在客户端语言下渲染（服务端语言 ≠ 客户端语言）
                    val timeDesc: Text = if (banInfo.isPermanent)
                        Text.translatable("cobblemarket.ban.permanent")
                    else
                        Text.translatable(
                            "cobblemarket.ban.remaining",
                            BanState.formatRemaining(banInfo.expiresAt!! - banCheckTime)
                        )
                    val banMsg = if (banInfo.reason.isNotBlank())
                        Text.translatable("cobblemarket.ban.banned_msg_time_reason", timeDesc, banInfo.reason)
                    else
                        Text.translatable("cobblemarket.ban.banned_msg_time", timeDesc)
                    sendToPlayer(player, MarketResultPayload(false, banMsg))
                    return@execute
                }
                val party = Cobblemon.storage.getParty(player)
                val pc = Cobblemon.storage.getPC(player)
                val pokemonUuid = payload.pokemonUuid

                // Find in party first
                var pokemon = party.find { it.uuid == pokemonUuid }
                val fromParty = pokemon != null
                if (pokemon == null) {
                    pokemon = pc.find { it.uuid == pokemonUuid }
                }
                if (pokemon == null) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                if (payload.price <= 0) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_price"))
                    )
                    return@execute
                }
                // 队伍至少要留一只精灵
                if (fromParty && party.occupied() <= 1) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.party_last"))
                    )
                    return@execute
                }

                // 黑名单检查（物种 + IV + 形态：["*"]=全形态，[]=默认形态，非空=子集匹配）
                if (com.shusheng.cobblemarket.market.PokemonBlacklistState.get(server)
                        .isBlacklisted(pokemon)
                ) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.blocked"))
                    )
                    return@execute
                }

                // 携带物黑名单检查：拉黑的物品不允许随精灵上架（防绕过物品黑名单，与蛋交易联动同语义）
                val heldItem = pokemon.heldItem()
                val heldItemId = if (heldItem.isEmpty) null
                    else net.minecraft.registry.Registries.ITEM.getId(heldItem.item).toString()
                if (heldItemId != null && com.shusheng.cobblemarket.market.ItemBlacklistState.get(server).contains(heldItemId)) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.held_item_blocked"))
                    )
                    return@execute
                }
                // 价格限制检查：精灵规则（物种可空=全部精灵、V 档可空=不限、形态照黑名单语义）+ 携带物规则合并
                val pokemonBounds = com.shusheng.cobblemarket.market.PokemonPriceLimitState.get(server)
                    .getPriceBounds(pokemon)
                val itemBounds = heldItemId?.let {
                    com.shusheng.cobblemarket.market.ItemPriceLimitState.get(server).getPriceBounds(it)
                }
                val priceBounds = com.shusheng.cobblemarket.market.mergePriceBounds(pokemonBounds, itemBounds)
                if (priceBounds != null) {
                    // 携带物参与限价时用带说明的提示，玩家才知道总价里包含了携带物部分
                    if (priceBounds.min != null && payload.price < priceBounds.min) {
                        val key = if (heldItemId != null) "cobblemarket.price_limit.held_below_min" else "cobblemarket.price_limit.below_min"
                        sendToPlayer(
                            player,
                            MarketResultPayload(false, Text.translatable(key, priceBounds.min))
                        )
                        return@execute
                    }
                    if (priceBounds.max != null && payload.price > priceBounds.max) {
                        val key = if (heldItemId != null) "cobblemarket.price_limit.held_above_max" else "cobblemarket.price_limit.above_max"
                        sendToPlayer(
                            player,
                            MarketResultPayload(false, Text.translatable(key, priceBounds.max))
                        )
                        return@execute
                    }
                }

                // 上架数量上限检查
                val maxPokemonListings = com.shusheng.cobblemarket.config.CobbleMarketConfig.maxPokemonListingsPerPlayer
                if (maxPokemonListings > 0 && MarketState.get(server)
                        .countActiveBySeller(player.uuid) >= maxPokemonListings
                ) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(
                            false,
                            Text.translatable("cobblemarket.cmd.max_listings", maxPokemonListings)
                        )
                    )
                    return@execute
                }

                // 先完成所有可能失败的操作（序列化 + 数据构建），失败时零副作用
                val heldItemStack = pokemon.heldItem()
                val world = player.serverWorld
                val nbt = try {
                    pokemon.saveToNBT(world.registryManager, NbtCompound())
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to serialize pokemon {} for listing by {}: {}", pokemonUuid, player.uuid, e.message)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed"))
                    )
                    return@execute
                }
                val now = System.currentTimeMillis()
                val extra = try {
                    buildListingExtra(pokemon, heldItemStack)
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to build listing data for pokemon {} by {}: {}", pokemonUuid, player.uuid, e.message)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed"))
                    )
                    return@execute
                }
                val listing = MarketListing(
                    id = UUID.randomUUID(),
                    sellerUuid = player.uuid,
                    sellerName = player.name.string,
                    pokemonNbt = nbt,
                    species = pokemon.species.name,
                    level = pokemon.level,
                    shiny = pokemon.shiny,
                    price = payload.price,
                    createdAt = now,
                    expiresAt = now + com.shusheng.cobblemarket.config.CobbleMarketConfig.listingDurationDays * 24L * 60 * 60 * 1000,
                    status = ListingStatus.ACTIVE,
                    extraData = extra
                )

                // 副作用阶段：扣手续费 → 移除精灵 → 挂单入库
                // Listing fee check
                val feePercent = com.shusheng.cobblemarket.config.CobbleMarketConfig.pokemonListingFeePercent
                // toLong 先提升：Int×Int 在价格×费率超过 21.5 亿时环绕溢出（fee 可算成负/0，逃税或凭空生钱）
                val fee = if (feePercent > 0) Math.ceil(payload.price.toLong() * feePercent / 100.0).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
                if (fee > 0 && !CurrencyHandler.remove(player, fee)) {
                    sendToPlayer(
                        player, MarketResultPayload(
                            false,
                            Text.translatable("cobblemarket.cmd.need_fee", CurrencyHandler.goldAmount(fee), CurrencyHandler.goldCurrencyText())
                        )
                    )
                    return@execute
                }

                // 移除精灵
                val removed = try {
                    if (fromParty) party.remove(pokemon) else pc.remove(pokemon)
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to remove pokemon {} from storage for listing by {}: {}", pokemonUuid, player.uuid, e.message)
                    false
                }
                if (!removed) {
                    // 精灵未实际移除：退还手续费并中止，避免同一精灵同时存在于存储和挂单
                    if (fee > 0) {
                        val refunded = giveCurrency(player, fee)
                        if (refunded < fee.toLong()) {
                            MarketState.get(server).addPendingBalance(player.uuid, fee.toLong() - refunded)
                            CobbleMarket.LOGGER.error(
                                "Partial fee refund for seller {} after failed pokemon removal; {} moved to pending balance",
                                player.uuid, fee.toLong() - refunded
                            )
                        }
                    }
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed"))
                    )
                    return@execute
                }

                val state = MarketState.get(server)
                state.addListing(listing)
                com.shusheng.cobblemarket.event.MarketEvents.ADD.trigger(
                    com.shusheng.cobblemarket.event.AddEvent(
                        listing,
                        fee
                    )
                )

                val listedMsg: Text = if (fee > 0)
                    Text.translatable(
                        "cobblemarket.cmd.listed_fee",
                        pokemon.species.translatedName,
                        pokemon.level,
                        CurrencyHandler.goldAmount(payload.price),
                        CurrencyHandler.goldCurrencyText(),
                        CurrencyHandler.goldAmount(fee),
                        CurrencyHandler.goldCurrencyText()
                    )
                else
                    Text.translatable(
                        "cobblemarket.cmd.listed",
                        pokemon.species.translatedName,
                        pokemon.level,
                        CurrencyHandler.goldAmount(payload.price),
                        CurrencyHandler.goldCurrencyText()
                    )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, listedMsg))
            }
        }

        registerC2S(SellItemPayload.ID, SellItemPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "sell_item", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val banCheckTime = System.currentTimeMillis()
                val banInfo = BanState.get(server).getBanInfo(player.uuid, banCheckTime)
                if (marketBlocked(player)) return@execute
                if (banInfo != null) {
                    // 保留 Text 对象而非 .string：翻译在客户端语言下渲染（服务端语言 ≠ 客户端语言）
                    val timeDesc: Text = if (banInfo.isPermanent)
                        Text.translatable("cobblemarket.ban.permanent")
                    else
                        Text.translatable(
                            "cobblemarket.ban.remaining",
                            BanState.formatRemaining(banInfo.expiresAt!! - banCheckTime)
                        )
                    val banMsg = if (banInfo.reason.isNotBlank())
                        Text.translatable("cobblemarket.ban.banned_msg_time_reason", timeDesc, banInfo.reason)
                    else
                        Text.translatable("cobblemarket.ban.banned_msg_time", timeDesc)
                    sendToPlayer(player, MarketResultPayload(false, banMsg))
                    return@execute
                }
                if (payload.count <= 0 || payload.price <= 0) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }

                // 重建目标物品，以服务端重建的物品为准（不信任客户端 itemId/itemNbt 的一致性）
                val diagId = net.minecraft.util.Identifier.tryParse(payload.itemId)
                val diagItem = diagId?.let { Registries.ITEM.getOrEmpty(it).orElse(null) }
                val targetStack = ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, payload.itemNbt)
                if (targetStack.isEmpty) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
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

                // 物品黑名单检查（以权威 itemId 为准）
                if (com.shusheng.cobblemarket.market.ItemBlacklistState.get(server).contains(authoritativeItemId)) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.item_blocked"))
                    )
                    return@execute
                }

                // 价格限制检查
                val itemPriceBounds = com.shusheng.cobblemarket.market.ItemPriceLimitState.get(server)
                    .getPriceBounds(authoritativeItemId)
                if (itemPriceBounds != null) {
                    if (itemPriceBounds.min != null && payload.price < itemPriceBounds.min) {
                        sendToPlayer(
                            player,
                            MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.below_min", itemPriceBounds.min))
                        )
                        return@execute
                    }
                    if (itemPriceBounds.max != null && payload.price > itemPriceBounds.max) {
                        sendToPlayer(
                            player,
                            MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.above_max", itemPriceBounds.max))
                        )
                        return@execute
                    }
                }

                // 容器内容校验：黑名单/限价/蛋开关对容器内物品同样生效（防塞箱绕过）
                val containerReject = com.shusheng.cobblemarket.market.ContainerTradeCheck.check(targetStack, server)
                if (containerReject != null) {
                    sendToPlayer(player, MarketResultPayload(false, containerReject))
                    return@execute
                }

                val main = player.inventory.main
                var available = 0
                for (i in 0 until main.size) {
                    val stack = main[i]
                    if (itemsEqualForTrading(stack, targetStack)) available += stack.count
                }
                if (available < payload.count) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }

                // 上架数量上限检查
                val maxItemListings = com.shusheng.cobblemarket.config.CobbleMarketConfig.maxItemListingsPerPlayer
                if (maxItemListings > 0 && ItemMarketState.get(server)
                        .countActiveBySeller(player.uuid) >= maxItemListings
                ) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.cmd.max_listings", maxItemListings))
                    )
                    return@execute
                }

                // 预编码：先验证真实栈可序列化，再开始扣物品（避免扣到一半失败导致物品+手续费双失）
                val listingNbt = try {
                    main.firstOrNull { itemsEqualForTrading(it, targetStack) }
                        ?.encode(player.serverWorld.registryManager) as? NbtCompound
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to encode item stack for listing by {}: {}", player.uuid, e.message)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed"))
                    )
                    return@execute
                }
                if (listingNbt == null) {
                    // available 检查已保证存在匹配栈，这里只做兜底
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }

                // 副作用阶段：先扣物品、再扣手续费。
                // 顺序不能反过来：货币物品 == 上架物品时（默认钻石货币 + 上架钻石），
                // 先扣手续费会从 main 的同一栈扣掉一部分，导致物品扣除不足额；
                // 若不校验剩余量，挂单仍按 count 全额入库 = 凭空虚增货物（经济漏洞）。
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
                    // 防御性校验：available 检查已保证足额，正常不可达；异常时退还已扣部分，绝不虚增挂单
                    giveBackItem(targetStack.copyWithCount(payload.count - remaining), player)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed"))
                    )
                    return@execute
                }
                player.inventory.markDirty()

                // 手续费（基于总价）；失败时退还已扣物品并中止
                val feePercent = com.shusheng.cobblemarket.config.CobbleMarketConfig.itemListingFeePercent
                val totalPrice = payload.price.toLong() * payload.count
                val fee = if (feePercent > 0) Math.ceil(totalPrice * feePercent / 100.0).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
                if (fee > 0 && !CurrencyHandler.remove(player, fee)) {
                    giveBackItem(targetStack.copyWithCount(payload.count), player)
                    sendToPlayer(
                        player, MarketResultPayload(
                            false,
                            Text.translatable("cobblemarket.cmd.need_fee", CurrencyHandler.goldAmount(fee), CurrencyHandler.goldCurrencyText())
                        )
                    )
                    return@execute
                }

                // 手动同步背包，确保客户端先收到背包更新、再收到上架结果
                player.currentScreenHandler.sendContentUpdates()

                val now = System.currentTimeMillis()
                val listing = ItemListing(
                    id = UUID.randomUUID(),
                    sellerUuid = player.uuid,
                    sellerName = player.name.string,
                    itemId = authoritativeItemId,
                    itemNbt = listingNbt,
                    count = payload.count,
                    price = payload.price,
                    createdAt = now,
                    expiresAt = now + com.shusheng.cobblemarket.config.CobbleMarketConfig.listingDurationDays * 24L * 60 * 60 * 1000,
                    status = ListingStatus.ACTIVE
                )

                val state = ItemMarketState.get(server)
                state.addListing(listing)

                com.shusheng.cobblemarket.event.TransactionHistory.get(server).addRecord(
                    com.shusheng.cobblemarket.event.TransactionRecord(
                        timestamp = System.currentTimeMillis(),
                        type = com.shusheng.cobblemarket.event.TransactionType.ADD,
                        category = com.shusheng.cobblemarket.event.TransactionCategory.ITEM,
                        sellerUuid = player.uuid,
                        sellerName = player.name.string,
                        buyerUuid = null,
                        buyerName = "",
                        species = payload.itemId,
                        price = payload.price,
                        fee = fee,
                        detail = com.shusheng.cobblemarket.util.RecordDetail.item(listing.itemNbt, listing.count)
                    )
                )

                val listedMsg: Text = if (fee > 0)
                    Text.translatable(
                        "cobblemarket.item.listed_fee",
                        payload.count,
                        targetStack.item.name,
                        CurrencyHandler.goldAmount(payload.price),
                        CurrencyHandler.goldCurrencyText(),
                        CurrencyHandler.goldAmount(fee),
                        CurrencyHandler.goldCurrencyText()
                    )
                else
                    Text.translatable(
                        "cobblemarket.item.listed",
                        payload.count,
                        targetStack.item.name,
                        CurrencyHandler.goldAmount(payload.price),
                        CurrencyHandler.goldCurrencyText()
                    )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, listedMsg))
            }
        }

        registerC2S(RequestItemMarketPayload.ID, RequestItemMarketPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_item_market", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = ItemMarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())

                val sortMode = parseSortMode(payload.sortMode)
                val results = state.search(
                    sortBy = sortMode,
                    sellerUuid = if (payload.mineOnly) player.uuid else null
                ).let { list ->
                    val query = payload.query.trim()
                    if (query.isEmpty()) list
                    else list.filter { it.itemId in payload.itemIds }
                }

                // 上限 84（12 行）：网格页容量随窗口，上限低于容量会导致末行空槽+多余分页；
                // 84 是服务器压力折中：恶意高频请求（250ms 节流下每秒 4 次）的带宽攻击面减半，正常窗口（≤12 行）无感知
                // 物品 itemNbt 通常几百字节（蛋较大，最坏全蛋页约 1MB，可接受）
                val pageSize = payload.pageSize.coerceIn(1, 84)
                val totalPages = ((results.size - 1) / pageSize) + 1
                val clampedPage = payload.page.coerceIn(1, maxOf(1, totalPages))

                val pageEntries = if (results.isEmpty()) emptyList() else {
                    val start = (clampedPage - 1) * pageSize
                    results.drop(start).take(pageSize).map { listing ->
                        ItemEntry(
                            id = listing.id,
                            sellerUuid = listing.sellerUuid,
                            sellerName = listing.sellerName,
                            itemId = listing.itemId,
                            itemNbt = listing.itemNbt,
                            count = listing.count,
                            price = listing.price,
                            currencyName = CurrencyHandler.getCurrencyId()
                        )
                    }
                }

                sendToPlayer(
                    player,
                    ItemMarketDataPayload(
                        pageEntries,
                        maxOf(1, totalPages),
                        clampedPage,
                        MarketState.get(server).getPendingBalance(player.uuid),
                        ItemMarketState.get(server).getPendingReturns(player.uuid).size
                    )
                )
            }
        }

        registerC2S(BuyItemPayload.ID, BuyItemPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "buy_item", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val banCheckTime = System.currentTimeMillis()
                val banInfo = BanState.get(server).getBanInfo(player.uuid, banCheckTime)
                if (marketBlocked(player)) return@execute
                if (banInfo != null) {
                    // 保留 Text 对象而非 .string：翻译在客户端语言下渲染（服务端语言 ≠ 客户端语言）
                    val timeDesc: Text = if (banInfo.isPermanent)
                        Text.translatable("cobblemarket.ban.permanent")
                    else
                        Text.translatable(
                            "cobblemarket.ban.remaining",
                            BanState.formatRemaining(banInfo.expiresAt!! - banCheckTime)
                        )
                    val banMsg = if (banInfo.reason.isNotBlank())
                        Text.translatable("cobblemarket.ban.banned_msg_time_reason", timeDesc, banInfo.reason)
                    else
                        Text.translatable("cobblemarket.ban.banned_msg_time", timeDesc)
                    sendToPlayer(player, MarketResultPayload(false, banMsg))
                    return@execute
                }
                val state = ItemMarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())
                val listing = state.getListing(payload.listingId)
                if (listing == null || !listing.isActive()) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                if (listing.sellerUuid == player.uuid) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.cannot_buy_own"))
                    )
                    return@execute
                }
                // 黑名单检查：拦截上架后被加入黑名单的存量挂单（治理即时生效）
                if (com.shusheng.cobblemarket.market.ItemBlacklistState.get(server).contains(listing.itemId)) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.item_blocked"))
                    )
                    return@execute
                }
                // 蛋交易开关：关闭后拦截存量蛋挂单的购买（与黑名单一致，治理即时生效）
                if (isEggItem(listing.itemId) && !com.shusheng.cobblemarket.config.CobbleMarketConfig.eggTradingEnabled) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.egg_trading_disabled"))
                    )
                    return@execute
                }
                val count = payload.count
                if (count <= 0) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                // 库存不足（可能被其他玩家抢先购买）：提示剩余数量，引导重新输入
                if (count > listing.count) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.insufficient_stock", listing.count))
                    )
                    return@execute
                }
                val stack = ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, listing.itemNbt)
                if (stack.isEmpty) {
                    // 挂单物品数据已失效（如 mod 被移除）：拒绝交易，避免买家买空气
                    CobbleMarket.LOGGER.warn("Item listing {} has invalid item data; rejecting purchase", listing.id)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.item_invalid"))
                    )
                    return@execute
                }
                stack.count = count

                if (!canFitInInventory(player, stack)) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.inventory_full"))
                    )
                    return@execute
                }

                val totalPriceLong = listing.price.toLong() * count
                if (totalPriceLong <= 0 || totalPriceLong > Int.MAX_VALUE) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_price"))
                    )
                    return@execute
                }
                val totalPrice = totalPriceLong.toInt()
                if (!CurrencyHandler.remove(player, totalPrice)) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(
                            false,
                            Text.translatable(
                                "cobblemarket.network.need_diamonds",
                                CurrencyHandler.goldAmount(totalPrice),
                                CurrencyHandler.goldCurrencyText()
                            )
                        )
                    )
                    return@execute
                }

                player.inventory.insertStack(stack)
                if (!stack.isEmpty) {
                    // 未完全放入（insertStack 返回 true 也可能只是部分插入）：回滚已放入部分，避免复制
                    val inserted = count - stack.count
                    if (inserted > 0) {
                        var toRemove = inserted
                        // 只遍历 main：insertStack 只往 main 放，回滚范围与插入范围严格一致，
                        // 避免误扣玩家 armor/offhand 里本来就有的相同物品
                        for (i in 0 until player.inventory.main.size) {
                            val slot = player.inventory.main[i]
                            if (itemsEqualForTrading(slot, stack)) {
                                val r = minOf(toRemove, slot.count)
                                slot.decrement(r)
                                toRemove -= r
                                if (toRemove <= 0) break
                            }
                        }
                        player.inventory.markDirty()
                    }
                    val refunded = CurrencyHandler.give(player, totalPrice.toLong())
                    if (refunded < totalPrice.toLong()) {
                        // 退款未全部到账（背包满）：差额转入待领余额兜底，避免买家钱被吞
                        MarketState.get(server).addPendingBalance(player.uuid, totalPrice.toLong() - refunded)
                        CobbleMarket.LOGGER.error(
                            "Partial refund for buyer {} on item listing {}; {} moved to pending balance",
                            player.uuid, listing.id, totalPrice.toLong() - refunded
                        )
                    }
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.inventory_full"))
                    )
                    return@execute
                }

                MarketState.get(server).addPendingBalance(listing.sellerUuid, totalPrice.toLong())
                listing.count -= count
                if (listing.count <= 0) {
                    listing.status = ListingStatus.SOLD
                    // 库存清空且货物已全部交付买家，挂单生命周期终结，立即删除避免存档膨胀
                    state.removeListing(listing.id)
                } else {
                    state.markModified()
                }

                com.shusheng.cobblemarket.event.TransactionHistory.get(server).addRecord(
                    com.shusheng.cobblemarket.event.TransactionRecord(
                        timestamp = System.currentTimeMillis(),
                        type = com.shusheng.cobblemarket.event.TransactionType.PURCHASE,
                        category = com.shusheng.cobblemarket.event.TransactionCategory.ITEM,
                        sellerUuid = listing.sellerUuid,
                        sellerName = listing.sellerName,
                        buyerUuid = player.uuid,
                        buyerName = player.name.string,
                        species = listing.itemId,
                        price = totalPrice,
                        fee = 0,
                        detail = com.shusheng.cobblemarket.util.RecordDetail.item(listing.itemNbt, listing.count)
                    )
                )

                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(
                        true,
                        Text.translatable(
                            "cobblemarket.item.bought",
                            count,
                            // 物品名传翻译 Text（客户端按玩家语言渲染），不能传裸 itemId
                            Identifier.tryParse(listing.itemId)
                                ?.let { Registries.ITEM.get(it).name }
                                ?: Text.literal(listing.itemId),
                            CurrencyHandler.goldAmount(totalPrice),
                            CurrencyHandler.goldCurrencyText()
                        )
                    )
                )

                val soldItemName = Identifier.tryParse(listing.itemId)?.let { Registries.ITEM.get(it).name }
                    ?: Text.literal(listing.itemId)
                // 卖家离线则入队补发
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                    server, listing.sellerUuid,
                    Text.translatable("cobblemarket.network.sold", soldItemName)
                )
            }
        }

        registerC2S(CancelItemPayload.ID, CancelItemPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "cancel_item", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 封禁只禁止交易；取消挂单是取回自己的资产，允许
                val state = ItemMarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())
                val listing = state.getListing(payload.listingId)
                if (listing == null || !listing.isActive()) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                if (listing.sellerUuid != player.uuid) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.cmd.not_your_listing"))
                    )
                    return@execute
                }

                val stack = ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, listing.itemNbt)
                if (stack.isEmpty) {
                    // 挂单物品数据已失效（如 mod 被移除）：保留挂单状态，等待管理员处理
                    CobbleMarket.LOGGER.warn("Item listing {} has invalid item data; keeping it active", listing.id)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.item_invalid"))
                    )
                    return@execute
                }
                stack.count = listing.count
                if (!canFitInInventory(player, stack)) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.inventory_full"))
                    )
                    return@execute
                }
                player.inventory.insertStack(stack)
                if (!stack.isEmpty) {
                    // 未完全放入（insertStack 返回 true 也可能只是部分插入）：回滚已放入部分，挂单保持 ACTIVE，避免物品凭空消失
                    val inserted = listing.count - stack.count
                    if (inserted > 0) {
                        var toRemove = inserted
                        // 只遍历 main：insertStack 只往 main 放，回滚范围与插入范围严格一致，
                        // 避免误扣玩家 armor/offhand 里本来就有的相同物品
                        for (i in 0 until player.inventory.main.size) {
                            val slot = player.inventory.main[i]
                            if (itemsEqualForTrading(slot, stack)) {
                                val r = minOf(toRemove, slot.count)
                                slot.decrement(r)
                                toRemove -= r
                                if (toRemove <= 0) break
                            }
                        }
                        player.inventory.markDirty()
                    }
                    CobbleMarket.LOGGER.warn(
                        "Failed to return item to seller {} for listing {}; keeping it active",
                        player.uuid, listing.id
                    )
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.inventory_full"))
                    )
                    return@execute
                }
                listing.status = ListingStatus.CANCELLED
                state.markModified()

                com.shusheng.cobblemarket.event.TransactionHistory.get(server).addRecord(
                    com.shusheng.cobblemarket.event.TransactionRecord(
                        timestamp = System.currentTimeMillis(),
                        type = com.shusheng.cobblemarket.event.TransactionType.CANCEL,
                        category = com.shusheng.cobblemarket.event.TransactionCategory.ITEM,
                        sellerUuid = player.uuid,
                        sellerName = listing.sellerName,
                        buyerUuid = null,
                        buyerName = "",
                        species = listing.itemId,
                        price = listing.price,
                        fee = 0,
                        detail = com.shusheng.cobblemarket.util.RecordDetail.item(listing.itemNbt, listing.count)
                    )
                )

                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(true, Text.translatable("cobblemarket.item.cancelled"))
                )

                // 物品已回卖家背包，挂单生命周期终结，立即删除避免存档膨胀
                state.removeListing(listing.id)
            }
        }

        registerC2S(CollectBalancePayload.ID, CollectBalancePayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "collect_balance", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = MarketState.get(server)
                // 先发钱、按实际发放量清账，发不完的留在账本，避免余额蒸发
                val amount = state.getPendingBalance(player.uuid)
                if (amount <= 0) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.cmd.no_earnings"))
                    )
                    return@execute
                }
                val given = CurrencyHandler.give(player, amount)
                if (given <= 0) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.collect_failed"))
                    )
                    return@execute
                }
                // 只清掉已实际发放的部分，差额留在账本（单方法内完成，无中间态）
                state.claimPendingBalance(player.uuid, given)
                val msg = if (given < amount)
                    Text.translatable("cobblemarket.cmd.collected_partial", CurrencyHandler.goldAmount(given), CurrencyHandler.goldCurrencyText())
                else
                    Text.translatable("cobblemarket.cmd.collected", CurrencyHandler.goldAmount(amount), CurrencyHandler.goldCurrencyText())
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, msg))
            }
        }

        // 入口界面的市场总开关按钮（OP）：写配置 + 全员广播，与命令同逻辑
        registerC2S(SetMarketEnabledPayload.ID, SetMarketEnabledPayload.CODEC) { payload, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute { toggleMarketEnabled(server, payload.enabled) }
        }

        registerC2S(RequestHistoryPayload.ID, RequestHistoryPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_history", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val history = com.shusheng.cobblemarket.event.TransactionHistory.get(server)
                val isAdmin = player.hasPermissionLevel(2)
                val records =
                    if (payload.all && isAdmin) history.getRecords() else history.getRecordsByPlayer(player.uuid)
                // 界面内最多展示 500 条（滚动浏览），完整记录见 config/cobblemarket/history/ CSV 日志
                val entries = records.take(500).map { r ->
                    val t =
                        if (r.type == com.shusheng.cobblemarket.event.TransactionType.PURCHASE && r.buyerUuid == player.uuid) "BUY" else r.type.name
                    HistoryEntry(t, r.category.name, r.species, r.price, r.buyerName, r.sellerName, r.timestamp)
                }
                sendToPlayer(player, HistoryDataPayload(entries))
            }
        }

        registerC2S(RequestPokemonReturnPayload.ID, RequestPokemonReturnPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_pokemon_return", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                AuctionNetwork.settleAndBroadcast(server) // 打开返还界面即触发到期拍卖结算
                val state = MarketState.get(server)
                val returns = state.getPendingReturns(player.uuid)
                // 协议分页：只打包当前页，避免退回列表过大时打出超大包
                val pageSize = payload.pageSize.coerceIn(1, 30)
                val totalPages = maxOf(1, ((returns.size - 1) / pageSize) + 1)
                val clampedPage = payload.page.coerceIn(1, totalPages)
                val pageItems = if (returns.isEmpty()) emptyList() else {
                    returns.drop((clampedPage - 1) * pageSize).take(pageSize)
                }
                val previews = pageItems.mapIndexedNotNull { i, listing ->
                    try {
                        val pokemon = com.cobblemon.mod.common.pokemon.Pokemon()
                            .loadFromNBT(player.serverWorld.registryManager, listing.pokemonNbt)
                        toPreview(pokemon, "return", i)
                    } catch (e: Exception) {
                        // 单条损坏不影响其他退回的预览
                        CobbleMarket.LOGGER.warn("Failed to build return preview for listing {}: {}", listing.id, e.message)
                        null
                    }
                }
                sendToPlayer(player, PokemonReturnDataPayload(previews, totalPages, clampedPage))
            }
        }

        registerC2S(ClaimPokemonReturnPayload.ID, ClaimPokemonReturnPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "claim_pokemon_return", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = MarketState.get(server)
                val returned = state.claimReturns(player)
                val remaining = state.getPendingReturns(player.uuid).size
                val msg = if (remaining > 0)
                    Text.translatable("cobblemarket.return.claimed", returned, remaining)
                else
                    Text.translatable("cobblemarket.return.claimed_all", returned)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, msg))
            }
        }

        registerC2S(RequestItemReturnPayload.ID, RequestItemReturnPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_item_return", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                AuctionNetwork.settleAndBroadcast(server) // 打开返还界面即触发到期拍卖结算
                val state = ItemMarketState.get(server)
                val returns = state.getPendingReturns(player.uuid)
                // 协议分页：只打包当前页，避免退回列表过大时打出超大包
                val pageSize = payload.pageSize.coerceIn(1, 42)
                val totalPages = maxOf(1, ((returns.size - 1) / pageSize) + 1)
                val clampedPage = payload.page.coerceIn(1, totalPages)
                val pageItems = if (returns.isEmpty()) emptyList() else {
                    returns.drop((clampedPage - 1) * pageSize).take(pageSize)
                }
                val entries = pageItems.map { listing ->
                    ItemEntry(
                        id = listing.id,
                        sellerUuid = listing.sellerUuid,
                        sellerName = listing.sellerName,
                        itemId = listing.itemId,
                        itemNbt = listing.itemNbt,
                        count = listing.count,
                        price = listing.price,
                        currencyName = CurrencyHandler.getCurrencyId()
                    )
                }
                sendToPlayer(player, ItemReturnDataPayload(entries, totalPages, clampedPage))
            }
        }

        registerC2S(ClaimItemReturnPayload.ID, ClaimItemReturnPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "claim_item_return", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = ItemMarketState.get(server)
                val returned = state.claimReturns(player, payload.dropOverflow)
                val remaining = state.getPendingReturns(player.uuid).size
                val msg = if (remaining > 0)
                    Text.translatable("cobblemarket.return.item_claimed", returned, remaining)
                else
                    Text.translatable("cobblemarket.return.item_claimed_all", returned)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, msg))
            }
        }
    }

    /** 构建挂单展示数据；调用方负责 try-catch（上架路径要求零副作用后才扣费/移除）。 */
    // 旧挂单（aspects 功能上线前上架的）没有 aspects 数据，按性别兜底：
    // 性别差异物种的形态 aspect 恰好名为 male/female
    private fun parseAspects(detail: Map<String, String>): List<String> =
        detail["aspects"]?.takeIf { it.isNotBlank() }?.split(",")
            ?: when (detail["gender"]) {
                "MALE" -> listOf("male")
                "FEMALE" -> listOf("female")
                else -> emptyList()
            }

    private fun buildListingExtra(
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
            // 精灵形态（性别/地区等），客户端渲染 3D 图标用；逗号分隔，aspect 名不含逗号
            "aspects" to pokemon.aspects.joinToString(",")
        )
        pokemon.secondaryType?.let { extra["secondaryType"] = "cobblemon.type.${it.name.lowercase()}" }
        return extra
    }

    private fun toPreview(pokemon: com.cobblemon.mod.common.pokemon.Pokemon, source: String, slot: Int) =
        PokemonPreview(
            uuid = pokemon.uuid,
            // 只发翻译 key，客户端本地翻译——服务端无玩家语言上下文，translatedName 永远是默认语言。
            // 必须走 SpeciesText.translationKey（直接取 Cobblemon 生成的翻译 key）：
            // 手拼 key 容易踩 species.name（显示名，如 "Indeedee" 大写）与资源路径的差异，key 拼错
            // 客户端只能显示 key 原文，且中文搜索跟着失效。
            species = com.shusheng.cobblemarket.util.SpeciesText.translationKey(pokemon.species),
            speciesId = pokemon.species.resourceIdentifier.toString(),
            speciesName = pokemon.species.name,
            level = pokemon.level,
            shiny = pokemon.shiny,
            gender = pokemon.gender.name,
            nature = "cobblemon.nature.${pokemon.effectiveNature.name.path}",
            natureBase = "cobblemon.nature.${pokemon.nature.name.path}",
            ability = "cobblemon.ability.${pokemon.ability.name}",
            ivsHp = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.HP] ?: 0,
            ivsAtk = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK] ?: 0,
            ivsDef = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE] ?: 0,
            ivsSpAtk = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK] ?: 0,
            ivsSpDef = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE] ?: 0,
            ivsSpd = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED] ?: 0,
            htHp = pokemon.ivs.hyperTrainedIVs[com.cobblemon.mod.common.api.pokemon.stats.Stats.HP] ?: -1,
            htAtk = pokemon.ivs.hyperTrainedIVs[com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK] ?: -1,
            htDef = pokemon.ivs.hyperTrainedIVs[com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE] ?: -1,
            htSpAtk = pokemon.ivs.hyperTrainedIVs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK] ?: -1,
            htSpDef = pokemon.ivs.hyperTrainedIVs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE] ?: -1,
            htSpd = pokemon.ivs.hyperTrainedIVs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED] ?: -1,
            ball = "item.cobblemon.${pokemon.caughtBall.name.path}",
            primaryType = "cobblemon.type.${pokemon.primaryType.name.lowercase()}",
            secondaryType = pokemon.secondaryType?.let { "cobblemon.type.${it.name.lowercase()}" } ?: "",
            source = source,
            slot = slot,
            heldItemId = if (pokemon.heldItem().isEmpty) "" else Registries.ITEM.getId(pokemon.heldItem().item).toString(),
            aspects = pokemon.aspects.toList()
        )

    fun openScreen(player: ServerPlayerEntity) {
        sendToPlayer(player, OpenMarketPayload(0))
    }

    private fun removeCurrency(player: ServerPlayerEntity, amount: Int) =
        com.shusheng.cobblemarket.config.CurrencyHandler.remove(player, amount)

    private fun giveCurrency(player: ServerPlayerEntity, amount: Int): Long =
        com.shusheng.cobblemarket.config.CurrencyHandler.give(player, amount.toLong())
}
