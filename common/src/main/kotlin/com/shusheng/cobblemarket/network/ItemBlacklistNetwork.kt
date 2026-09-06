package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.market.ItemBlacklistEntry
import com.shusheng.cobblemarket.market.ItemBlacklistState
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.registry.Registries

// ── DTO 序列化 ──

fun ItemBlacklistEntry.write(buf: PacketByteBuf) {
    buf.writeString(itemId)
    buf.writeNbt(componentsSpec)
}

fun readItemBlacklistEntry(buf: PacketByteBuf) = ItemBlacklistEntry(
    itemId = buf.readString(),
    componentsSpec = buf.readNbt()
)

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

// ── C2S: 批量删除物品黑名单（按条目键精确解封当前搜索匹配的条目；所见即所得——搜索过滤到变体级后不再按 itemId 连坐） ──

data class RemoveItemsBlacklistPayload(val entries: List<ItemBlacklistEntry>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RemoveItemsBlacklistPayload>(CobbleMarket.id("remove_items_blacklist"))
        val CODEC: PacketCodec<PacketByteBuf, RemoveItemsBlacklistPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> RemoveItemsBlacklistPayload((0 until b.readVarInt()).map { readItemBlacklistEntry(b) }) }
        )
    }
}

// ── C2S: 添加手持物品黑名单（服务端读主手物品提取组件快照，不信任客户端传输） ──

class AddHeldItemBlacklistPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AddHeldItemBlacklistPayload>(CobbleMarket.id("add_held_item_blacklist"))
        val CODEC: PacketCodec<PacketByteBuf, AddHeldItemBlacklistPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); AddHeldItemBlacklistPayload() }
        )
    }
}

// ── C2S: 删除物品黑名单（按键 itemId + 组件快照） ──

data class RemoveItemBlacklistPayload(
    val itemId: String,
    val componentsSpec: net.minecraft.nbt.NbtCompound?
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RemoveItemBlacklistPayload>(CobbleMarket.id("remove_item_blacklist"))
        val CODEC: PacketCodec<PacketByteBuf, RemoveItemBlacklistPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.itemId); b.writeNbt(p.componentsSpec) },
            { b -> RemoveItemBlacklistPayload(b.readString(), b.readNbt()) }
        )
    }
}

// ── S2C: 物品黑名单列表 ──

data class ItemBlacklistDataPayload(val entries: List<ItemBlacklistEntry>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<ItemBlacklistDataPayload>(CobbleMarket.id("item_blacklist_data"))
        val CODEC: PacketCodec<PacketByteBuf, ItemBlacklistDataPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> ItemBlacklistDataPayload((0 until b.readVarInt()).map { readItemBlacklistEntry(b) }) }
        )
    }
}

object ItemBlacklistNetwork {

    fun register() {
        registerS2CType(ItemBlacklistDataPayload.ID, ItemBlacklistDataPayload.CODEC)

        registerC2S(RequestItemBlacklistPayload.ID, RequestItemBlacklistPayload.CODEC) { _, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                val entries = ItemBlacklistState.get(server).getAll()
                sendToPlayer(player, ItemBlacklistDataPayload(entries.reversed()))
            }
        }

        registerC2S(AddItemBlacklistPayload.ID, AddItemBlacklistPayload.CODEC) { payload, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
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
                ItemBlacklistState.get(server).add(itemId, null)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                val entries = ItemBlacklistState.get(server).getAll()
                sendToPlayer(player, ItemBlacklistDataPayload(entries.reversed()))
            }
        }

        registerC2S(AddHeldItemBlacklistPayload.ID, AddHeldItemBlacklistPayload.CODEC) { _, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                val heldStack = player.mainHandStack.copy()
                if (heldStack.isEmpty) {
                    player.sendMessage(
                        net.minecraft.text.Text.translatable("cobblemarket.blacklist.held_item_empty")
                            .formatted(net.minecraft.util.Formatting.RED), false)
                    return@execute
                }
                ItemBlacklistState.get(server).add(
                    Registries.ITEM.getId(heldStack.item).toString(),
                    com.shusheng.cobblemarket.market.ItemRuleComponents.extractSpec(heldStack, player.serverWorld.registryManager)
                )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                val entries = ItemBlacklistState.get(server).getAll()
                sendToPlayer(player, ItemBlacklistDataPayload(entries.reversed()))
            }
        }

        registerC2S(AddItemsBlacklistPayload.ID, AddItemsBlacklistPayload.CODEC) { payload, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                val state = ItemBlacklistState.get(server)
                var added = 0
                payload.itemIds.forEach { id ->
                    if (net.minecraft.util.Identifier.tryParse(id) != null) {
                        state.add(id, null)
                        added++
                    }
                }
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, ItemBlacklistDataPayload(state.getAll().reversed()))
                player.sendMessage(
                    net.minecraft.text.Text.translatable("cobblemarket.blacklist.added_all", added)
                        .formatted(net.minecraft.util.Formatting.GREEN), false)
            }
        }

        registerC2S(RemoveItemBlacklistPayload.ID, RemoveItemBlacklistPayload.CODEC) { payload, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                ItemBlacklistState.get(server).remove(payload.itemId, payload.componentsSpec)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                val entries = ItemBlacklistState.get(server).getAll()
                sendToPlayer(player, ItemBlacklistDataPayload(entries.reversed()))
            }
        }

        registerC2S(RemoveItemsBlacklistPayload.ID, RemoveItemsBlacklistPayload.CODEC) { payload, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                val state = ItemBlacklistState.get(server)
                // 所见即所得：按条目键精确删除搜索过滤出的条目（搜索到变体级时只删显示的，不连坐同 itemId 的其它条目）
                payload.entries.forEach { state.remove(it.itemId, it.componentsSpec) }
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, ItemBlacklistDataPayload(state.getAll().reversed()))
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
