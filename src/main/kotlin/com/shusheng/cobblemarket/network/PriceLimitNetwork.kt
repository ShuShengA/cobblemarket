package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.market.ItemPriceLimitEntry
import com.shusheng.cobblemarket.market.ItemPriceLimitState
import com.shusheng.cobblemarket.market.PokemonPriceLimitEntry
import com.shusheng.cobblemarket.market.PokemonPriceLimitState
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Formatting

// ── DTO 序列化 ──

fun PokemonPriceLimitEntry.write(buf: PacketByteBuf) {
    buf.writeString(speciesId)
    buf.writeInt(vCount)
    buf.writeInt(shinyFilter)
    buf.writeVarInt(aspects.size); aspects.forEach { buf.writeString(it) }
    buf.writeBoolean(minPrice != null); minPrice?.let { buf.writeInt(it) }
    buf.writeBoolean(maxPrice != null); maxPrice?.let { buf.writeInt(it) }
    buf.writeInt(htFilter)
}

fun readPokemonPriceLimitEntry(buf: PacketByteBuf) = PokemonPriceLimitEntry(
    speciesId = buf.readString(),
    vCount = buf.readInt(),
    shinyFilter = buf.readInt(),
    aspects = (0 until buf.readVarInt()).map { buf.readString() },
    minPrice = if (buf.readBoolean()) buf.readInt() else null,
    maxPrice = if (buf.readBoolean()) buf.readInt() else null,
    htFilter = buf.readInt()
)

fun ItemPriceLimitEntry.write(buf: PacketByteBuf) {
    buf.writeString(itemId)
    buf.writeBoolean(minPrice != null); minPrice?.let { buf.writeInt(it) }
    buf.writeBoolean(maxPrice != null); maxPrice?.let { buf.writeInt(it) }
}

fun readItemPriceLimitEntry(buf: PacketByteBuf) = ItemPriceLimitEntry(
    itemId = buf.readString(),
    minPrice = if (buf.readBoolean()) buf.readInt() else null,
    maxPrice = if (buf.readBoolean()) buf.readInt() else null
)

// ── C2S: 请求精灵价格限制 ──

class RequestPokemonPriceLimitPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestPokemonPriceLimitPayload>(CobbleMarket.id("request_pokemon_price_limit"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPokemonPriceLimitPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestPokemonPriceLimitPayload() }
        )
    }
}

// ── C2S: 添加精灵价格限制（speciesId 空 = 全部精灵；同组合重复添加 = 覆盖） ──

data class AddPokemonPriceLimitPayload(
    val speciesId: String,
    val vCount: Int,
    val shinyFilter: Int,
    val minPrice: Int?,
    val maxPrice: Int?,
    val aspects: List<String>,
    // 编辑语义：非空 = 替换原条目（先删旧再插新），null = 新增
    val original: PokemonPriceLimitEntry?,
    // 特训限定：0 = 不限，1 = 仅特训，2 = 不含特训
    val htFilter: Int
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AddPokemonPriceLimitPayload>(CobbleMarket.id("add_pokemon_price_limit"))
        val CODEC: PacketCodec<PacketByteBuf, AddPokemonPriceLimitPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.speciesId)
                b.writeInt(p.vCount)
                b.writeInt(p.shinyFilter)
                b.writeVarInt(p.aspects.size); p.aspects.forEach { b.writeString(it) }
                b.writeBoolean(p.minPrice != null); p.minPrice?.let { b.writeInt(it) }
                b.writeBoolean(p.maxPrice != null); p.maxPrice?.let { b.writeInt(it) }
                b.writeBoolean(p.original != null); p.original?.write(b)
                b.writeInt(p.htFilter)
            },
            { b ->
                AddPokemonPriceLimitPayload(
                    speciesId = b.readString(),
                    vCount = b.readInt(),
                    shinyFilter = b.readInt(),
                    aspects = (0 until b.readVarInt()).map { b.readString() },
                    minPrice = if (b.readBoolean()) b.readInt() else null,
                    maxPrice = if (b.readBoolean()) b.readInt() else null,
                    original = if (b.readBoolean()) readPokemonPriceLimitEntry(b) else null,
                    htFilter = b.readInt()
                )
            }
        )
    }
}

// ── C2S: 删除精灵价格限制 ──

data class RemovePokemonPriceLimitPayload(val speciesId: String, val vCount: Int, val shinyFilter: Int, val aspects: List<String>, val htFilter: Int) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RemovePokemonPriceLimitPayload>(CobbleMarket.id("remove_pokemon_price_limit"))
        val CODEC: PacketCodec<PacketByteBuf, RemovePokemonPriceLimitPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.speciesId); b.writeInt(p.vCount); b.writeInt(p.shinyFilter)
                b.writeVarInt(p.aspects.size); p.aspects.forEach { b.writeString(it) }
                b.writeInt(p.htFilter)
            },
            { b ->
                RemovePokemonPriceLimitPayload(
                    b.readString(), b.readInt(), b.readInt(),
                    (0 until b.readVarInt()).map { b.readString() },
                    b.readInt()
                )
            }
        )
    }
}

// ── S2C: 精灵价格限制列表 ──

data class PokemonPriceLimitDataPayload(val entries: List<PokemonPriceLimitEntry>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<PokemonPriceLimitDataPayload>(CobbleMarket.id("pokemon_price_limit_data"))
        val CODEC: PacketCodec<PacketByteBuf, PokemonPriceLimitDataPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> PokemonPriceLimitDataPayload((0 until b.readVarInt()).map { readPokemonPriceLimitEntry(b) }) }
        )
    }
}

// ── C2S: 请求物品价格限制 ──

class RequestItemPriceLimitPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestItemPriceLimitPayload>(CobbleMarket.id("request_item_price_limit"))
        val CODEC: PacketCodec<PacketByteBuf, RequestItemPriceLimitPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestItemPriceLimitPayload() }
        )
    }
}

// ── C2S: 添加物品价格限制（同物品重复添加 = 覆盖） ──

data class AddItemPriceLimitPayload(
    val itemName: String,
    val minPrice: Int?,
    val maxPrice: Int?,
    // 编辑语义：非空 = 替换原条目（先删旧再插新），null = 新增
    val originalItemId: String?
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AddItemPriceLimitPayload>(CobbleMarket.id("add_item_price_limit"))
        val CODEC: PacketCodec<PacketByteBuf, AddItemPriceLimitPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.itemName)
                b.writeBoolean(p.minPrice != null); p.minPrice?.let { b.writeInt(it) }
                b.writeBoolean(p.maxPrice != null); p.maxPrice?.let { b.writeInt(it) }
                b.writeBoolean(p.originalItemId != null); p.originalItemId?.let { b.writeString(it) }
            },
            { b ->
                AddItemPriceLimitPayload(
                    itemName = b.readString(),
                    minPrice = if (b.readBoolean()) b.readInt() else null,
                    maxPrice = if (b.readBoolean()) b.readInt() else null,
                    originalItemId = if (b.readBoolean()) b.readString() else null
                )
            }
        )
    }
}

// ── C2S: 删除物品价格限制 ──

data class RemoveItemPriceLimitPayload(val itemId: String) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RemoveItemPriceLimitPayload>(CobbleMarket.id("remove_item_price_limit"))
        val CODEC: PacketCodec<PacketByteBuf, RemoveItemPriceLimitPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.itemId) },
            { b -> RemoveItemPriceLimitPayload(b.readString()) }
        )
    }
}

// ── S2C: 物品价格限制列表 ──

data class ItemPriceLimitDataPayload(val entries: List<ItemPriceLimitEntry>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<ItemPriceLimitDataPayload>(CobbleMarket.id("item_price_limit_data"))
        val CODEC: PacketCodec<PacketByteBuf, ItemPriceLimitDataPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> ItemPriceLimitDataPayload((0 until b.readVarInt()).map { readItemPriceLimitEntry(b) }) }
        )
    }
}

object PriceLimitNetwork {

    fun register() {
        PayloadTypeRegistry.playC2S().register(RequestPokemonPriceLimitPayload.ID, RequestPokemonPriceLimitPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(AddPokemonPriceLimitPayload.ID, AddPokemonPriceLimitPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(RemovePokemonPriceLimitPayload.ID, RemovePokemonPriceLimitPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(PokemonPriceLimitDataPayload.ID, PokemonPriceLimitDataPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(RequestItemPriceLimitPayload.ID, RequestItemPriceLimitPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(AddItemPriceLimitPayload.ID, AddItemPriceLimitPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(RemoveItemPriceLimitPayload.ID, RemoveItemPriceLimitPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(ItemPriceLimitDataPayload.ID, ItemPriceLimitDataPayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(RequestPokemonPriceLimitPayload.ID) { _, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val entries = PokemonPriceLimitState.get(server).getAll()
                ServerPlayNetworking.send(player, PokemonPriceLimitDataPayload(entries))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(AddPokemonPriceLimitPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val vCount = payload.vCount.coerceIn(-1, PokemonPriceLimitState.V_COUNT_RANGE.last)
                val shinyFilter = payload.shinyFilter.coerceIn(
                    PokemonPriceLimitEntry.SHINY_ANY, PokemonPriceLimitEntry.SHINY_YES)
                // 价格合法性：单侧可空，填写时必须为正；min > max 的规则没有任何建设性用途，直接拒绝
                val minPrice = payload.minPrice
                val maxPrice = payload.maxPrice
                if ((minPrice != null && minPrice <= 0) || (maxPrice != null && maxPrice <= 0)) {
                    player.sendMessage(
                        Text.translatable("cobblemarket.price_limit.invalid_price").formatted(Formatting.RED), false)
                    return@execute
                }
                if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
                    player.sendMessage(
                        Text.translatable("cobblemarket.price_limit.invalid_range").formatted(Formatting.RED), false)
                    return@execute
                }
                // 物种留空 = 全部精灵；非空时解析为权威 ID
                val speciesId = if (payload.speciesId.isBlank()) {
                    ""
                } else {
                    com.shusheng.cobblemarket.util.SpeciesText.resolveByNameOrId(payload.speciesId)
                        ?: run {
                            player.sendMessage(
                                Text.translatable("cobblemarket.blacklist.not_found").formatted(Formatting.RED), false)
                            return@execute
                        }
                }
                val state = PokemonPriceLimitState.get(server)
                // 编辑语义：替换原条目（改了形态/物种/V 数/闪光/特训等 key 字段时，旧条目不再残留）
                payload.original?.let { state.remove(it.speciesId, it.vCount, it.shinyFilter, it.aspects, it.htFilter) }
                state.add(
                    PokemonPriceLimitEntry(
                        speciesId, vCount, shinyFilter, minPrice, maxPrice, payload.aspects,
                        payload.htFilter.coerceIn(PokemonPriceLimitEntry.HT_ANY, PokemonPriceLimitEntry.HT_NONE)
                    )
                )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                val entries = PokemonPriceLimitState.get(server).getAll()
                ServerPlayNetworking.send(player, PokemonPriceLimitDataPayload(entries))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(RemovePokemonPriceLimitPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                PokemonPriceLimitState.get(server).remove(payload.speciesId, payload.vCount, payload.shinyFilter, payload.aspects, payload.htFilter)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                val entries = PokemonPriceLimitState.get(server).getAll()
                ServerPlayNetworking.send(player, PokemonPriceLimitDataPayload(entries))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(RequestItemPriceLimitPayload.ID) { _, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val entries = ItemPriceLimitState.get(server).getAll()
                ServerPlayNetworking.send(player, ItemPriceLimitDataPayload(entries))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(AddItemPriceLimitPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val minPrice = payload.minPrice
                val maxPrice = payload.maxPrice
                if ((minPrice != null && minPrice <= 0) || (maxPrice != null && maxPrice <= 0)) {
                    player.sendMessage(
                        Text.translatable("cobblemarket.price_limit.invalid_price").formatted(Formatting.RED), false)
                    return@execute
                }
                if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
                    player.sendMessage(
                        Text.translatable("cobblemarket.price_limit.invalid_range").formatted(Formatting.RED), false)
                    return@execute
                }
                val itemId = resolveItemId(payload.itemName)
                if (itemId == null) {
                    player.sendMessage(
                        Text.translatable("cobblemarket.blacklist.item_not_found").formatted(Formatting.RED), false)
                    return@execute
                }
                val state = ItemPriceLimitState.get(server)
                // 编辑语义：替换原条目（改选了物品时，旧条目不再残留）
                payload.originalItemId?.let { state.remove(it) }
                state.add(ItemPriceLimitEntry(itemId, minPrice, maxPrice))
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                val entries = ItemPriceLimitState.get(server).getAll()
                ServerPlayNetworking.send(player, ItemPriceLimitDataPayload(entries))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(RemoveItemPriceLimitPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                ItemPriceLimitState.get(server).remove(payload.itemId)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                val entries = ItemPriceLimitState.get(server).getAll()
                ServerPlayNetworking.send(player, ItemPriceLimitDataPayload(entries))
            }
        }
    }

    // 与物品黑名单一致的解析语义：ID 路径精确 > 翻译名精确 > 翻译名包含
    private fun resolveItemId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains(":")) return trimmed
        val lower = trimmed.lowercase().replace(" ", "_")
        Registries.ITEM.forEach { item ->
            val id = Registries.ITEM.getId(item)
            if (id.path == lower) return id.toString()
        }
        Registries.ITEM.forEach { item ->
            val id = Registries.ITEM.getId(item)
            if (item.name.string == trimmed) return id.toString()
        }
        Registries.ITEM.forEach { item ->
            val id = Registries.ITEM.getId(item)
            val name = item.name.string
            if (name.contains(trimmed)) return id.toString()
        }
        return null
    }
}
