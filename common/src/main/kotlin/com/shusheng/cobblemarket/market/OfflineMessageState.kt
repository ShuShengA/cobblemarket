package com.shusheng.cobblemarket.market

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtString
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.text.Text
import net.minecraft.world.PersistentState
import java.util.UUID

/**
 * 离线通知队列：玩家不在线时通知入队（文本以 JSON 序列化，保留 translatable 结构，
 * 上线时按玩家客户端语言渲染），JOIN 时补发并清除。
 */
class OfflineMessageState private constructor() : PersistentState() {

    private val messages = mutableMapOf<UUID, MutableList<String>>()

    fun add(playerUuid: UUID, textJson: String) {
        val list = messages.getOrPut(playerUuid) { mutableListOf() }
        // 每人最多保留 10 条（超量丢最旧，防止恶意堆积膨胀存档）
        if (list.size >= MAX_PER_PLAYER) list.removeAt(0)
        list.add(textJson)
        markDirty()
    }

    /** 取出并清除某玩家的全部离线通知 */
    fun take(playerUuid: UUID): List<String> {
        val list = messages.remove(playerUuid) ?: return emptyList()
        markDirty()
        return list
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val root = NbtCompound()
        messages.forEach { (uuid, list) ->
            val msgList = NbtList()
            list.forEach { msgList.add(NbtString.of(it)) }
            root.put(uuid.toString(), msgList)
        }
        nbt.put("messages", root)
        return nbt
    }

    companion object {
        private const val MAX_PER_PLAYER = 10

        private val TYPE = PersistentState.Type(
            { OfflineMessageState() },
            { nbt, _ ->
                OfflineMessageState().apply {
                    val root = nbt.getCompound("messages")
                    root.keys.forEach { key ->
                        try {
                            val uuid = UUID.fromString(key)
                            val list = root.getList(key, NbtList.STRING_TYPE.toInt()).map { it.asString() }.toMutableList()
                            messages[uuid] = list
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted offline message entry: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): OfflineMessageState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_offline_messages")

        /** 通知玩家（全模组通用）：在线即发聊天框，离线入队（上线时补发并按客户端语言渲染） */
        fun notify(server: MinecraftServer, playerUuid: UUID, text: Text) {
            val online = server.playerManager.getPlayer(playerUuid)
            if (online != null) {
                online.sendMessage(text, false)
                return
            }
            try {
                get(server).add(playerUuid, Text.Serialization.toJsonString(text, server.registryManager))
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Failed to queue offline message for {}: {}", playerUuid, e.message)
            }
        }
    }
}
