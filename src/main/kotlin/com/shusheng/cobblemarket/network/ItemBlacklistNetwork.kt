package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.market.ItemBlacklistState
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.registry.Registries

// ── C2S: 请求物品黑名单 ──

class RequestItemBlacklistPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestItemBlacklistPayload>(CobbleMarket.id("request_item_blacklist"))
        val CODEC: PacketCodec<PacketByteBuf, RequestItemBlacklistPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestItemBlacklistPayload() }
        )
    }
}

// ── C2S: 添加物品黑名单 ──

data class AddItemBlacklistPayload(val itemName: String) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AddItemBlacklistPayload>(CobbleMarket.id("add_item_blacklist"))
        val CODEC: PacketCodec<PacketByteBuf, AddItemBlacklistPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.itemName) },
            { b -> AddItemBlacklistPayload(b.readString()) }
        )
    }
}

// ── C2S: 批量添加物品黑名单（完整物品 ID 列表，如蛋的全部属性变体） ──

data class AddItemsBlacklistPayload(val itemIds: List<String>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AddItemsBlacklistPayload>(CobbleMarket.id("add_items_blacklist"))
        val CODEC: PacketCodec<PacketByteBuf, AddItemsBlacklistPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.itemIds.size); p.itemIds.forEach { b.writeString(it) } },
            { b -> AddItemsBlacklistPayload((0 until b.readVarInt()).map { b.readString() }) }
        )
    }
}

// ── C2S: 批量删除物品黑名单（解封当前搜索匹配的条目） ──

data class RemoveItemsBlacklistPayload(val itemIds: List<String>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RemoveItemsBlacklistPayload>(CobbleMarket.id("remove_items_blacklist"))
        val CODEC: PacketCodec<PacketByteBuf, RemoveItemsBlacklistPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.itemIds.size); p.itemIds.forEach { b.writeString(it) } },
            { b -> RemoveItemsBlacklistPayload((0 until b.readVarInt()).map { b.readString() }) }
        )
    }
}

// ── C2S: 删除物品黑名单 ──

data class RemoveItemBlacklistPayload(val itemId: String) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RemoveItemBlacklistPayload>(CobbleMarket.id("remove_item_blacklist"))
        val CODEC: PacketCodec<PacketByteBuf, RemoveItemBlacklistPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.itemId) },
            { b -> RemoveItemBlacklistPayload(b.readString()) }
        )
    }
}

// ── S2C: 物品黑名单列表 ──

data class ItemBlacklistDataPayload(val entries: List<String>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<ItemBlacklistDataPayload>(CobbleMarket.id("item_blacklist_data"))
        val CODEC: PacketCodec<PacketByteBuf, ItemBlacklistDataPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { b.writeString(it) } },
            { b -> ItemBlacklistDataPayload((0 until b.readVarInt()).map { b.readString() }) }
        )
    }
}

object ItemBlacklistNetwork {

    fun register() {
        PayloadTypeRegistry.playC2S().register(RequestItemBlacklistPayload.ID, RequestItemBlacklistPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(AddItemBlacklistPayload.ID, AddItemBlacklistPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(AddItemsBlacklistPayload.ID, AddItemsBlacklistPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(RemoveItemBlacklistPayload.ID, RemoveItemBlacklistPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(RemoveItemsBlacklistPayload.ID, RemoveItemsBlacklistPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(ItemBlacklistDataPayload.ID, ItemBlacklistDataPayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(RequestItemBlacklistPayload.ID) { _, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val entries = ItemBlacklistState.get(server).getAll()
                ServerPlayNetworking.send(player, ItemBlacklistDataPayload(entries))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(AddItemBlacklistPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val itemId = resolveItemId(payload.itemName)
                if (itemId == null) {
                    // 解析失败明确反馈，不再静默丢弃
                    player.sendMessage(
                        net.minecraft.text.Text.translatable("cobblemarket.blacklist.item_not_found")
                            .formatted(net.minecraft.util.Formatting.RED), false)
                    return@execute
                }
                ItemBlacklistState.get(server).add(itemId)
                val entries = ItemBlacklistState.get(server).getAll()
                ServerPlayNetworking.send(player, ItemBlacklistDataPayload(entries))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(AddItemsBlacklistPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val state = ItemBlacklistState.get(server)
                var added = 0
                payload.itemIds.forEach { id ->
                    if (net.minecraft.util.Identifier.tryParse(id) != null) {
                        state.add(id)
                        added++
                    }
                }
                ServerPlayNetworking.send(player, ItemBlacklistDataPayload(state.getAll()))
                player.sendMessage(
                    net.minecraft.text.Text.translatable("cobblemarket.blacklist.added_all", added)
                        .formatted(net.minecraft.util.Formatting.GREEN), false)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(RemoveItemBlacklistPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                ItemBlacklistState.get(server).remove(payload.itemId)
                val entries = ItemBlacklistState.get(server).getAll()
                ServerPlayNetworking.send(player, ItemBlacklistDataPayload(entries))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(RemoveItemsBlacklistPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val state = ItemBlacklistState.get(server)
                payload.itemIds.forEach { state.remove(it) }
                ServerPlayNetworking.send(player, ItemBlacklistDataPayload(state.getAll()))
            }
        }
    }

    private fun resolveItemId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains(":")) return trimmed
        val lower = trimmed.lowercase().replace(" ", "_")
        Registries.ITEM.forEach { item ->
            val id = Registries.ITEM.getId(item)
            if (id.path == lower) return id.toString()
        }
        // 翻译名精确匹配优先于模糊匹配：注册表里方块先于物品，
        // 若混在一起 contains 匹配，搜"Diamond"会先命中"Diamond Ore"
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
