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
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
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
    val nature: String,
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
        buf.writeString(nature)
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
            nature = buf.readString(),
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

// ── 蛋交易开关 ──

class RequestEggTradingPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestEggTradingPayload>(CobbleMarket.id("request_egg_trading"))
        val CODEC: PacketCodec<PacketByteBuf, RequestEggTradingPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestEggTradingPayload() }
        )
    }
}

data class SetEggTradingPayload(val enabled: Boolean) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<SetEggTradingPayload>(CobbleMarket.id("set_egg_trading"))
        val CODEC: PacketCodec<PacketByteBuf, SetEggTradingPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.enabled) },
            { b -> SetEggTradingPayload(b.readBoolean()) }
        )
    }
}

data class EggTradingStatePayload(val enabled: Boolean) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<EggTradingStatePayload>(CobbleMarket.id("egg_trading_state"))
        val CODEC: PacketCodec<PacketByteBuf, EggTradingStatePayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.enabled) },
            { b -> EggTradingStatePayload(b.readBoolean()) }
        )
    }
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
    val minIvsHp: Int,
    val minIvsAtk: Int,
    val minIvsDef: Int,
    val minIvsSpAtk: Int,
    val minIvsSpDef: Int,
    val minIvsSpd: Int,
    val pageSize: Int,
    val mineOnly: Boolean
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<RequestMarketPayload>(CobbleMarket.id("request_market"))
        val CODEC: PacketCodec<PacketByteBuf, RequestMarketPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.speciesFilter); b.writeBoolean(p.shinyOnly); b.writeInt(p.minLevel); b.writeInt(p.maxLevel); b.writeString(
                p.sortMode
            ); b.writeInt(p.page); b.writeString(p.genderFilter); b.writeString(p.typeFilter); b.writeInt(p.minIvsHp); b.writeInt(
                p.minIvsAtk
            ); b.writeInt(p.minIvsDef); b.writeInt(p.minIvsSpAtk); b.writeInt(p.minIvsSpDef); b.writeInt(p.minIvsSpd); b.writeInt(
                p.pageSize
            ); b.writeBoolean(p.mineOnly)
            },
            { b ->
                RequestMarketPayload(
                    b.readString(),
                    b.readBoolean(),
                    b.readInt(),
                    b.readInt(),
                    b.readString(),
                    b.readInt(),
                    b.readString(),
                    b.readString(),
                    b.readInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readBoolean()
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
    val minIvsHp: Int,
    val minIvsAtk: Int,
    val minIvsDef: Int,
    val minIvsSpAtk: Int,
    val minIvsSpDef: Int,
    val minIvsSpd: Int,
    val pageSize: Int,
    val mineOnly: Boolean
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<AdminRequestPokemonPayload>(CobbleMarket.id("admin_request_pokemon"))
        val CODEC: PacketCodec<PacketByteBuf, AdminRequestPokemonPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.speciesFilter); b.writeString(p.sellerFilter); b.writeBoolean(p.shinyOnly); b.writeString(
                p.sortMode
            ); b.writeInt(p.page); b.writeInt(p.minIvsHp); b.writeInt(p.minIvsAtk); b.writeInt(p.minIvsDef); b.writeInt(
                p.minIvsSpAtk
            ); b.writeInt(p.minIvsSpDef); b.writeInt(p.minIvsSpd); b.writeInt(p.pageSize); b.writeBoolean(p.mineOnly)
            },
            { b ->
                AdminRequestPokemonPayload(
                    b.readString(),
                    b.readString(),
                    b.readBoolean(),
                    b.readString(),
                    b.readInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readBoolean()
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
    val pendingBalance: Long
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
            },
            { b ->
                val size = b.readVarInt()
                val entries = (0 until size).map { ListingEntry.read(b) }
                MarketDataPayload(entries, b.readInt(), b.readInt(), b.readLong())
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
    val nature: String,
    val ability: String,
    val ivsHp: Int, val ivsAtk: Int, val ivsDef: Int, val ivsSpAtk: Int, val ivsSpDef: Int, val ivsSpd: Int,
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
        buf.writeString(nature); buf.writeString(ability)
        buf.writeInt(ivsHp); buf.writeInt(ivsAtk); buf.writeInt(ivsDef)
        buf.writeInt(ivsSpAtk); buf.writeInt(ivsSpDef); buf.writeInt(ivsSpd)
        buf.writeString(ball); buf.writeString(primaryType); buf.writeString(secondaryType)
        buf.writeString(source); buf.writeInt(slot); buf.writeString(heldItemId)
        buf.writeVarInt(aspects.size); aspects.forEach { buf.writeString(it) }
    }

    companion object {
        fun read(buf: PacketByteBuf) = PokemonPreview(
            buf.readUuid(), buf.readString(), buf.readString(), buf.readString(),
            buf.readInt(), buf.readBoolean(), buf.readString(),
            buf.readString(), buf.readString(),
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
    val pageSize: Int
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestItemMarketPayload>(CobbleMarket.id("request_item_market"))
        val CODEC: PacketCodec<PacketByteBuf, RequestItemMarketPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.sortMode); b.writeInt(p.page); b.writeBoolean(p.mineOnly); b.writeInt(p.pageSize) },
            { b -> RequestItemMarketPayload(b.readString(), b.readInt(), b.readBoolean(), b.readInt()) }
        )
    }
}

// ── S2C: Item market data ──

data class ItemMarketDataPayload(
    val entries: List<ItemEntry>,
    val totalPages: Int,
    val currentPage: Int,
    val pendingBalance: Long
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
            },
            { b ->
                val size = b.readVarInt()
                val entries = (0 until size).map { ItemEntry.read(b) }
                ItemMarketDataPayload(entries, b.readInt(), b.readInt(), b.readLong())
            }
        )
    }
}

// ── C2S: Admin request all items ──

data class AdminRequestItemPayload(
    val sellerFilter: String,
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
                b.writeString(p.sellerFilter); b.writeString(p.sortMode); b.writeInt(p.page); b.writeInt(p.pageSize); b.writeBoolean(
                p.mineOnly
            )
            },
            { b -> AdminRequestItemPayload(b.readString(), b.readString(), b.readInt(), b.readInt(), b.readBoolean()) }
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

class ClaimItemReturnPayload : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<ClaimItemReturnPayload>(CobbleMarket.id("claim_item_return"))
        val CODEC: PacketCodec<PacketByteBuf, ClaimItemReturnPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); ClaimItemReturnPayload() }
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

/** 是否为蛋类物品（Cobbreeding 蛋的注册 ID 以 pokemon_egg 结尾，含通用蛋与全部属性蛋；不依赖其类名） */
fun isEggItem(itemId: String): Boolean = itemId.substringAfter(':').endsWith("pokemon_egg")

/**
 * 交易匹配：物品相同且组件一致；动态组件（cobbreeding 蛋的孵化计时 timer/second 持续变化、
 * version 组件在服务端重建时会被 verifyComponentsAfterLoad 迁移改写）导致完整比较永远失败——
 * 剔除动态组件后再比较一次。
 * 安全边界：只忽略 :timer / :second / :version 结尾的组件，蛋数据（egg_info）等其余组件仍严格比较，不会混淆不同物品。
 */
fun itemsEqualForTrading(a: ItemStack, b: ItemStack): Boolean {
    if (a.isEmpty || b.isEmpty) return false
    if (!a.isOf(b.item)) return false
    if (ItemStack.areItemsAndComponentsEqual(a, b)) return true
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
        PayloadTypeRegistry.playC2S().register(RequestMarketPayload.ID, RequestMarketPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(BuyFromMarketPayload.ID, BuyFromMarketPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CancelFromMarketPayload.ID, CancelFromMarketPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(RequestMyPokemonPayload.ID, RequestMyPokemonPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SellFromStoragePayload.ID, SellFromStoragePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SellItemPayload.ID, SellItemPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(RequestItemMarketPayload.ID, RequestItemMarketPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(BuyItemPayload.ID, BuyItemPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CancelItemPayload.ID, CancelItemPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(RequestPokemonReturnPayload.ID, RequestPokemonReturnPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(ClaimPokemonReturnPayload.ID, ClaimPokemonReturnPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(RequestItemReturnPayload.ID, RequestItemReturnPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(ClaimItemReturnPayload.ID, ClaimItemReturnPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CollectBalancePayload.ID, CollectBalancePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(RequestHistoryPayload.ID, RequestHistoryPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(AdminRequestPokemonPayload.ID, AdminRequestPokemonPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(AdminCancelPokemonPayload.ID, AdminCancelPokemonPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(AdminRequestItemPayload.ID, AdminRequestItemPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(AdminCancelItemPayload.ID, AdminCancelItemPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(RequestEggTradingPayload.ID, RequestEggTradingPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SetEggTradingPayload.ID, SetEggTradingPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(EggTradingStatePayload.ID, EggTradingStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(MyPokemonListPayload.ID, MyPokemonListPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(HistoryDataPayload.ID, HistoryDataPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(OpenMarketPayload.ID, OpenMarketPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(MarketDataPayload.ID, MarketDataPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(ItemMarketDataPayload.ID, ItemMarketDataPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(PokemonReturnDataPayload.ID, PokemonReturnDataPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(ItemReturnDataPayload.ID, ItemReturnDataPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(MarketResultPayload.ID, MarketResultPayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(RequestMarketPayload.ID) { payload, context ->
            val player = context.player()
            // 250ms：与客户端搜索防抖（250ms）配合——防抖保证正常操作下两次请求间隔
            // 至少 250ms，最终态请求不会被节流误丢；仍拦得住恶意高频全量搜索
            if (!RequestThrottle.allow(player.uuid, "request_market", 250L)) return@registerGlobalReceiver
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
                    minIvs = buildMap {
                        if (payload.minIvsHp >= 0) put("ivsHp", payload.minIvsHp)
                        if (payload.minIvsAtk >= 0) put("ivsAtk", payload.minIvsAtk)
                        if (payload.minIvsDef >= 0) put("ivsDef", payload.minIvsDef)
                        if (payload.minIvsSpAtk >= 0) put("ivsSpAtk", payload.minIvsSpAtk)
                        if (payload.minIvsSpDef >= 0) put("ivsSpDef", payload.minIvsSpDef)
                        if (payload.minIvsSpd >= 0) put("ivsSpd", payload.minIvsSpd)
                    },
                    sellerUuid = if (payload.mineOnly) player.uuid else null
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
                            nature = detail["nature"] ?: "?",
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

                ServerPlayNetworking.send(
                    player,
                    MarketDataPayload(
                        pageEntries,
                        maxOf(1, totalPages),
                        clampedPage,
                        state.getPendingBalance(player.uuid)
                    )
                )
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(BuyFromMarketPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "buy_pokemon", RequestThrottle.WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val banCheckTime = System.currentTimeMillis()
                val banInfo = BanState.get(server).getBanInfo(player.uuid, banCheckTime)
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
                    ServerPlayNetworking.send(player, MarketResultPayload(false, banMsg))
                    return@execute
                }
                val state = MarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())
                val listing = state.getListing(payload.listingId)

                if (listing == null || !listing.isActive()) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                if (listing.sellerUuid == player.uuid) {
                    ServerPlayNetworking.send(
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
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }

                // 黑名单检查：拦截上架后被加入黑名单的存量挂单（治理即时生效）
                if (com.shusheng.cobblemarket.market.PokemonBlacklistState.get(server)
                        .isBlacklisted(pokemon)
                ) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.blocked"))
                    )
                    return@execute
                }

                if (!removeCurrency(player, listing.price)) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(
                            false,
                            Text.translatable(
                                "cobblemarket.network.need_diamonds",
                                listing.price,
                                com.shusheng.cobblemarket.config.CurrencyHandler.currencyText()
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
                    ServerPlayNetworking.send(
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

                ServerPlayNetworking.send(
                    player,
                    MarketResultPayload(
                        true,
                        Text.translatable(
                            "cobblemarket.network.bought",
                            listing.speciesText(),
                            listing.price,
                            com.shusheng.cobblemarket.config.CurrencyHandler.currencyText()
                        )
                    )
                )

                val seller = server.playerManager.getPlayer(listing.sellerUuid)
                seller?.sendMessage(Text.translatable("cobblemarket.network.sold", listing.speciesText()), false)

                // 精灵已进买家队伍，挂单生命周期终结，立即删除避免存档膨胀
                state.removeListing(listing.id)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CancelFromMarketPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "cancel_pokemon", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                // 封禁只禁止交易；取消挂单是取回自己的资产，允许
                val state = MarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())
                val listing = state.getListing(payload.listingId)

                if (listing == null || !listing.isActive()) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                if (listing.sellerUuid != player.uuid) {
                    ServerPlayNetworking.send(
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
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                val party = Cobblemon.storage.getParty(player)
                if (!party.add(pokemon)) {
                    ServerPlayNetworking.send(
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
                ServerPlayNetworking.send(
                    player,
                    MarketResultPayload(true, Text.translatable("cobblemarket.cmd.cancelled", listing.speciesText()))
                )

                // 精灵已回卖家队伍，挂单生命周期终结，立即删除避免存档膨胀
                state.removeListing(listing.id)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(AdminRequestPokemonPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val state = MarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())

                val sortMode = parseSortMode(payload.sortMode)
                val results = state.search(
                    species = payload.speciesFilter.ifBlank { null },
                    shiny = if (payload.shinyOnly) true else null,
                    sortBy = sortMode,
                    minIvs = buildMap {
                        if (payload.minIvsHp >= 0) put("ivsHp", payload.minIvsHp)
                        if (payload.minIvsAtk >= 0) put("ivsAtk", payload.minIvsAtk)
                        if (payload.minIvsDef >= 0) put("ivsDef", payload.minIvsDef)
                        if (payload.minIvsSpAtk >= 0) put("ivsSpAtk", payload.minIvsSpAtk)
                        if (payload.minIvsSpDef >= 0) put("ivsSpDef", payload.minIvsSpDef)
                        if (payload.minIvsSpd >= 0) put("ivsSpd", payload.minIvsSpd)
                    },
                    sellerUuid = if (payload.mineOnly) player.uuid else null,
                    sellerName = payload.sellerFilter.ifBlank { null }
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
                            nature = detail["nature"] ?: "?",
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

                ServerPlayNetworking.send(player, MarketDataPayload(pageEntries, maxOf(1, totalPages), clampedPage, 0L))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(AdminCancelPokemonPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val state = MarketState.get(server)
                val listing = state.getListing(payload.listingId)
                if (listing == null || !listing.isActive()) {
                    ServerPlayNetworking.send(
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
                server.playerManager.getPlayer(listing.sellerUuid)?.sendMessage(
                    Text.translatable("cobblemarket.cmd.cancelled", listing.speciesText()), false
                )
                ServerPlayNetworking.send(
                    player,
                    MarketResultPayload(
                        true,
                        Text.translatable("cobblemarket.op.cancelled", listing.sellerName, listing.speciesText())
                    )
                )
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(AdminRequestItemPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val state = ItemMarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())

                val sortMode = parseSortMode(payload.sortMode)
                val results = state.search(
                    sortBy = sortMode,
                    sellerUuid = if (payload.mineOnly) player.uuid else null,
                    sellerName = payload.sellerFilter.ifBlank { null }
                )

                // 上限与精灵市场一致（30）：条目含完整 itemNbt，200 条大 NBT 会打出数百 MB 的包
                val pageSize = payload.pageSize.coerceIn(1, 30)
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

                ServerPlayNetworking.send(
                    player,
                    ItemMarketDataPayload(pageEntries, maxOf(1, totalPages), clampedPage, 0L)
                )
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(AdminCancelItemPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val state = ItemMarketState.get(server)
                val listing = state.getListing(payload.listingId)
                if (listing == null || !listing.isActive()) {
                    ServerPlayNetworking.send(
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
                        fee = 0
                    )
                )
                server.playerManager.getPlayer(listing.sellerUuid)?.sendMessage(
                    Text.translatable("cobblemarket.item.cancelled"), false
                )
                ServerPlayNetworking.send(
                    player,
                    MarketResultPayload(
                        true,
                        Text.translatable("cobblemarket.op.cancelled", listing.sellerName, listing.itemId)
                    )
                )
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(RequestMyPokemonPayload.ID) { payload, context ->
            val player = context.player()
            // page 超出合理范围直接丢弃，防 page * pageSize 溢出
            if (payload.page !in 0..10000) return@registerGlobalReceiver
            // page 0 是开关界面的入口，保留节流防刷；分页请求由响应驱动（拿到响应才会发下一个），
            // 天然有节奏且只读无副作用，不节流——本地服务器往返 <1ms，500ms 冷却会把整条链全部拒掉。
            if (payload.page == 0 && !RequestThrottle.allow(player.uuid, "request_my_pokemon", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerGlobalReceiver
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
                ServerPlayNetworking.send(player, MyPokemonListPayload(previews, payload.page, payload.requestId, hasMore))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(SellFromStoragePayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "sell_from_storage", RequestThrottle.WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val banCheckTime = System.currentTimeMillis()
                val banInfo = BanState.get(server).getBanInfo(player.uuid, banCheckTime)
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
                    ServerPlayNetworking.send(player, MarketResultPayload(false, banMsg))
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
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                if (payload.price <= 0) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_price"))
                    )
                    return@execute
                }
                // 队伍至少要留一只精灵
                if (fromParty && party.occupied() <= 1) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.party_last"))
                    )
                    return@execute
                }

                // 黑名单检查（物种 + IV + 形态：["*"]=全形态，[]=默认形态，非空=子集匹配）
                if (com.shusheng.cobblemarket.market.PokemonBlacklistState.get(server)
                        .isBlacklisted(pokemon)
                ) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.blocked"))
                    )
                    return@execute
                }

                // 价格限制检查：所有匹配规则（物种可空=全部精灵、V 档可空=不限）的最严交集
                val priceBounds = com.shusheng.cobblemarket.market.PokemonPriceLimitState.get(server)
                    .getPriceBounds(
                        pokemon.species.resourceIdentifier.toString(),
                        com.shusheng.cobblemarket.market.PokemonPriceLimitState.vCountOf(pokemon.ivs),
                        pokemon.shiny
                    )
                if (priceBounds != null) {
                    if (priceBounds.min != null && payload.price < priceBounds.min) {
                        ServerPlayNetworking.send(
                            player,
                            MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.below_min", priceBounds.min))
                        )
                        return@execute
                    }
                    if (priceBounds.max != null && payload.price > priceBounds.max) {
                        ServerPlayNetworking.send(
                            player,
                            MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.above_max", priceBounds.max))
                        )
                        return@execute
                    }
                }

                // 上架数量上限检查
                val maxPokemonListings = com.shusheng.cobblemarket.config.CobbleMarketConfig.maxPokemonListingsPerPlayer
                if (maxPokemonListings > 0 && MarketState.get(server)
                        .countActiveBySeller(player.uuid) >= maxPokemonListings
                ) {
                    ServerPlayNetworking.send(
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
                    ServerPlayNetworking.send(
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
                    ServerPlayNetworking.send(
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
                val fee = if (feePercent > 0) Math.ceil(payload.price * feePercent / 100.0).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
                if (fee > 0 && !CurrencyHandler.remove(player, fee)) {
                    ServerPlayNetworking.send(
                        player, MarketResultPayload(
                            false,
                            Text.translatable("cobblemarket.cmd.need_fee", fee, CurrencyHandler.currencyText())
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
                    ServerPlayNetworking.send(
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
                        payload.price,
                        CurrencyHandler.currencyText(),
                        fee,
                        CurrencyHandler.currencyText()
                    )
                else
                    Text.translatable(
                        "cobblemarket.cmd.listed",
                        pokemon.species.translatedName,
                        pokemon.level,
                        payload.price,
                        CurrencyHandler.currencyText()
                    )
                ServerPlayNetworking.send(player, MarketResultPayload(true, listedMsg))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(SellItemPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "sell_item", RequestThrottle.WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val banCheckTime = System.currentTimeMillis()
                val banInfo = BanState.get(server).getBanInfo(player.uuid, banCheckTime)
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
                    ServerPlayNetworking.send(player, MarketResultPayload(false, banMsg))
                    return@execute
                }
                if (payload.count <= 0 || payload.price <= 0) {
                    ServerPlayNetworking.send(
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
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                val authoritativeItemId = Registries.ITEM.getId(targetStack.item).toString()

                // 蛋交易开关（默认关闭）：蛋走物品链路不经过精灵黑名单，需服主显式开启
                if (isEggItem(authoritativeItemId) && !com.shusheng.cobblemarket.config.CobbleMarketConfig.eggTradingEnabled) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.egg_trading_disabled"))
                    )
                    return@execute
                }

                // 物品黑名单检查（以权威 itemId 为准）
                if (com.shusheng.cobblemarket.market.ItemBlacklistState.get(server).contains(authoritativeItemId)) {
                    ServerPlayNetworking.send(
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
                        ServerPlayNetworking.send(
                            player,
                            MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.below_min", itemPriceBounds.min))
                        )
                        return@execute
                    }
                    if (itemPriceBounds.max != null && payload.price > itemPriceBounds.max) {
                        ServerPlayNetworking.send(
                            player,
                            MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.above_max", itemPriceBounds.max))
                        )
                        return@execute
                    }
                }

                val main = player.inventory.main
                var available = 0
                for (i in 0 until main.size) {
                    val stack = main[i]
                    if (itemsEqualForTrading(stack, targetStack)) available += stack.count
                }
                if (available < payload.count) {
                    ServerPlayNetworking.send(
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
                    ServerPlayNetworking.send(
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
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed"))
                    )
                    return@execute
                }
                if (listingNbt == null) {
                    // available 检查已保证存在匹配栈，这里只做兜底
                    ServerPlayNetworking.send(
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
                    ServerPlayNetworking.send(
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
                    ServerPlayNetworking.send(
                        player, MarketResultPayload(
                            false,
                            Text.translatable("cobblemarket.cmd.need_fee", fee, CurrencyHandler.currencyText())
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
                        fee = fee
                    )
                )

                val listedMsg: Text = if (fee > 0)
                    Text.translatable(
                        "cobblemarket.item.listed_fee",
                        payload.count,
                        targetStack.item.name,
                        payload.price,
                        CurrencyHandler.currencyText(),
                        fee,
                        CurrencyHandler.currencyText()
                    )
                else
                    Text.translatable(
                        "cobblemarket.item.listed",
                        payload.count,
                        targetStack.item.name,
                        payload.price,
                        CurrencyHandler.currencyText()
                    )
                ServerPlayNetworking.send(player, MarketResultPayload(true, listedMsg))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(RequestItemMarketPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "request_item_market", RequestThrottle.READ_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val state = ItemMarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())

                val sortMode = parseSortMode(payload.sortMode)
                val results = state.search(
                    sortBy = sortMode,
                    sellerUuid = if (payload.mineOnly) player.uuid else null
                )

                // 上限与精灵市场一致（30）：条目含完整 itemNbt，200 条大 NBT 会打出数百 MB 的包
                val pageSize = payload.pageSize.coerceIn(1, 30)
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

                ServerPlayNetworking.send(
                    player,
                    ItemMarketDataPayload(
                        pageEntries,
                        maxOf(1, totalPages),
                        clampedPage,
                        MarketState.get(server).getPendingBalance(player.uuid)
                    )
                )
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(BuyItemPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "buy_item", RequestThrottle.WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val banCheckTime = System.currentTimeMillis()
                val banInfo = BanState.get(server).getBanInfo(player.uuid, banCheckTime)
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
                    ServerPlayNetworking.send(player, MarketResultPayload(false, banMsg))
                    return@execute
                }
                val state = ItemMarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())
                val listing = state.getListing(payload.listingId)
                if (listing == null || !listing.isActive()) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                if (listing.sellerUuid == player.uuid) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.cannot_buy_own"))
                    )
                    return@execute
                }
                // 黑名单检查：拦截上架后被加入黑名单的存量挂单（治理即时生效）
                if (com.shusheng.cobblemarket.market.ItemBlacklistState.get(server).contains(listing.itemId)) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.item_blocked"))
                    )
                    return@execute
                }
                // 蛋交易开关：关闭后拦截存量蛋挂单的购买（与黑名单一致，治理即时生效）
                if (isEggItem(listing.itemId) && !com.shusheng.cobblemarket.config.CobbleMarketConfig.eggTradingEnabled) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.egg_trading_disabled"))
                    )
                    return@execute
                }
                val count = payload.count
                if (count <= 0) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                // 库存不足（可能被其他玩家抢先购买）：提示剩余数量，引导重新输入
                if (count > listing.count) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.insufficient_stock", listing.count))
                    )
                    return@execute
                }
                val stack = ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, listing.itemNbt)
                if (stack.isEmpty) {
                    // 挂单物品数据已失效（如 mod 被移除）：拒绝交易，避免买家买空气
                    CobbleMarket.LOGGER.warn("Item listing {} has invalid item data; rejecting purchase", listing.id)
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.item_invalid"))
                    )
                    return@execute
                }
                stack.count = count

                if (!canFitInInventory(player, stack)) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.inventory_full"))
                    )
                    return@execute
                }

                val totalPriceLong = listing.price.toLong() * count
                if (totalPriceLong <= 0 || totalPriceLong > Int.MAX_VALUE) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_price"))
                    )
                    return@execute
                }
                val totalPrice = totalPriceLong.toInt()
                if (!CurrencyHandler.remove(player, totalPrice)) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(
                            false,
                            Text.translatable(
                                "cobblemarket.network.need_diamonds",
                                totalPrice,
                                CurrencyHandler.currencyText()
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
                    ServerPlayNetworking.send(
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
                        fee = 0
                    )
                )

                ServerPlayNetworking.send(
                    player,
                    MarketResultPayload(
                        true,
                        Text.translatable(
                            "cobblemarket.item.bought",
                            count,
                            listing.itemId,
                            totalPrice,
                            CurrencyHandler.currencyText()
                        )
                    )
                )

                val seller = server.playerManager.getPlayer(listing.sellerUuid)
                val soldItemName = Identifier.tryParse(listing.itemId)?.let { Registries.ITEM.get(it).name }
                    ?: Text.literal(listing.itemId)
                seller?.sendMessage(Text.translatable("cobblemarket.network.sold", soldItemName), false)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CancelItemPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "cancel_item", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                // 封禁只禁止交易；取消挂单是取回自己的资产，允许
                val state = ItemMarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())
                val listing = state.getListing(payload.listingId)
                if (listing == null || !listing.isActive()) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                if (listing.sellerUuid != player.uuid) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.cmd.not_your_listing"))
                    )
                    return@execute
                }

                val stack = ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, listing.itemNbt)
                if (stack.isEmpty) {
                    // 挂单物品数据已失效（如 mod 被移除）：保留挂单状态，等待管理员处理
                    CobbleMarket.LOGGER.warn("Item listing {} has invalid item data; keeping it active", listing.id)
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.item_invalid"))
                    )
                    return@execute
                }
                stack.count = listing.count
                if (!canFitInInventory(player, stack)) {
                    ServerPlayNetworking.send(
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
                    ServerPlayNetworking.send(
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
                        fee = 0
                    )
                )

                ServerPlayNetworking.send(
                    player,
                    MarketResultPayload(true, Text.translatable("cobblemarket.item.cancelled"))
                )

                // 物品已回卖家背包，挂单生命周期终结，立即删除避免存档膨胀
                state.removeListing(listing.id)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CollectBalancePayload.ID) { _, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "collect_balance", RequestThrottle.WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val state = MarketState.get(server)
                // 先发钱、按实际发放量清账，发不完的留在账本，避免余额蒸发
                val amount = state.getPendingBalance(player.uuid)
                if (amount <= 0) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.cmd.no_earnings"))
                    )
                    return@execute
                }
                val given = CurrencyHandler.give(player, amount)
                if (given <= 0) {
                    ServerPlayNetworking.send(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.collect_failed"))
                    )
                    return@execute
                }
                // 只清掉已实际发放的部分，差额留在账本（单方法内完成，无中间态）
                state.claimPendingBalance(player.uuid, given)
                val msg = if (given < amount)
                    Text.translatable("cobblemarket.cmd.collected_partial", given, CurrencyHandler.currencyText())
                else
                    Text.translatable("cobblemarket.cmd.collected", amount, CurrencyHandler.currencyText())
                ServerPlayNetworking.send(player, MarketResultPayload(true, msg))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(RequestEggTradingPayload.ID) { _, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            ServerPlayNetworking.send(player, EggTradingStatePayload(com.shusheng.cobblemarket.config.CobbleMarketConfig.eggTradingEnabled))
        }

        ServerPlayNetworking.registerGlobalReceiver(SetEggTradingPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            com.shusheng.cobblemarket.config.CobbleMarketConfig.setEggTradingEnabled(payload.enabled)
            ServerPlayNetworking.send(player, EggTradingStatePayload(payload.enabled))
        }

        ServerPlayNetworking.registerGlobalReceiver(RequestHistoryPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "request_history", RequestThrottle.READ_INTERVAL_MS)) return@registerGlobalReceiver
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
                ServerPlayNetworking.send(player, HistoryDataPayload(entries))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(RequestPokemonReturnPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "request_pokemon_return", RequestThrottle.READ_INTERVAL_MS)) return@registerGlobalReceiver
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
                ServerPlayNetworking.send(player, PokemonReturnDataPayload(previews, totalPages, clampedPage))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(ClaimPokemonReturnPayload.ID) { _, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "claim_pokemon_return", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val state = MarketState.get(server)
                val returned = state.claimReturns(player)
                val remaining = state.getPendingReturns(player.uuid).size
                val msg = if (remaining > 0)
                    Text.translatable("cobblemarket.return.claimed", returned, remaining)
                else
                    Text.translatable("cobblemarket.return.claimed_all", returned)
                ServerPlayNetworking.send(player, MarketResultPayload(true, msg))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(RequestItemReturnPayload.ID) { payload, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "request_item_return", RequestThrottle.READ_INTERVAL_MS)) return@registerGlobalReceiver
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
                ServerPlayNetworking.send(player, ItemReturnDataPayload(entries, totalPages, clampedPage))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(ClaimItemReturnPayload.ID) { _, context ->
            val player = context.player()
            if (!RequestThrottle.allow(player.uuid, "claim_item_return", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val state = ItemMarketState.get(server)
                val returned = state.claimReturns(player)
                val remaining = state.getPendingReturns(player.uuid).size
                val msg = if (remaining > 0)
                    Text.translatable("cobblemarket.return.item_claimed", returned, remaining)
                else
                    Text.translatable("cobblemarket.return.item_claimed_all", returned)
                ServerPlayNetworking.send(player, MarketResultPayload(true, msg))
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
            ability = "cobblemon.ability.${pokemon.ability.name}",
            ivsHp = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.HP] ?: 0,
            ivsAtk = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK] ?: 0,
            ivsDef = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE] ?: 0,
            ivsSpAtk = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK] ?: 0,
            ivsSpDef = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE] ?: 0,
            ivsSpd = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED] ?: 0,
            ball = "item.cobblemon.${pokemon.caughtBall.name.path}",
            primaryType = "cobblemon.type.${pokemon.primaryType.name.lowercase()}",
            secondaryType = pokemon.secondaryType?.let { "cobblemon.type.${it.name.lowercase()}" } ?: "",
            source = source,
            slot = slot,
            heldItemId = if (pokemon.heldItem().isEmpty) "" else Registries.ITEM.getId(pokemon.heldItem().item).toString(),
            aspects = pokemon.aspects.toList()
        )

    fun openScreen(player: ServerPlayerEntity) {
        ServerPlayNetworking.send(player, OpenMarketPayload(0))
    }

    private fun removeCurrency(player: ServerPlayerEntity, amount: Int) =
        com.shusheng.cobblemarket.config.CurrencyHandler.remove(player, amount)

    private fun giveCurrency(player: ServerPlayerEntity, amount: Int): Long =
        com.shusheng.cobblemarket.config.CurrencyHandler.give(player, amount.toLong())
}
