package com.shusheng.cobblemarket.peek

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.world.PersistentState
import java.util.UUID

/**
 * **可疑标记**（2026-11-14）：持 `overview` 权限的人给玩家打的共享标记。
 *
 * - 谁标记的、什么时候、理由（可空）都记下来，悬停窗里展示
 * - 只有 `overview` 权限的人能读/写（名单本身只发给这些人）
 * - 独立 PersistentState，重启后保留；**不自动过期**，只能手动取消
 *
 * ⚠ 新存档文件 `cobblemarket_peek_marks`：旧档没有它 = 空标记，不涉及迁移。
 */
class PeekMarkState private constructor() : PersistentState() {

    data class Mark(
        val byUuid: String,
        val byName: String,
        val at: Long,
        val reason: String?,
    )

    private val marks = mutableMapOf<UUID, Mark>()

    fun get(uuid: UUID): Mark? = marks[uuid]

    fun all(): Map<UUID, Mark> = marks

    fun set(target: UUID, by: ServerPlayerEntity, reason: String?) {
        marks[target] = Mark(
            byUuid = by.uuid.toString(),
            byName = by.gameProfile.name,
            at = System.currentTimeMillis(),
            reason = reason,
        )
        markDirty()
    }

    fun clear(target: UUID) {
        if (marks.remove(target) != null) markDirty()
    }

    /** 标记是低频操作，立刻落盘（崩溃/重启后不丢）；失败只记日志 */
    fun saveNow(server: MinecraftServer) {
        markDirty()
        try {
            server.overworld.persistentStateManager.save()
        } catch (e: Exception) {
            CobbleMarket.LOGGER.error("CobbleMarket: failed to save peek marks", e)
        }
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val map = NbtCompound()
        marks.forEach { (uuid, m) ->
            val entry = NbtCompound()
            entry.putString("byUuid", m.byUuid)
            entry.putString("byName", m.byName)
            entry.putLong("at", m.at)
            m.reason?.let { entry.putString("reason", it) }
            map.put(uuid.toString(), entry)
        }
        nbt.put("marks", map)
        return nbt
    }

    companion object {
        private const val KEY = "cobblemarket_peek_marks"

        private val TYPE = PersistentState.Type(
            { PeekMarkState() },
            { nbt, _ ->
                PeekMarkState().apply {
                    val map = nbt.getCompound("marks")
                    map.keys.forEach { key ->
                        // 容错：单条坏只跳过该条并记日志（铁律）
                        try {
                            val entry = map.getCompound(key)
                            marks[UUID.fromString(key)] = Mark(
                                byUuid = entry.getString("byUuid"),
                                byName = entry.getString("byName"),
                                at = entry.getLong("at"),
                                reason = if (entry.contains("reason")) {
                                    entry.getString("reason").takeIf { it.isNotBlank() }
                                } else {
                                    null
                                },
                            )
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn(
                                "Skipping corrupted peek mark for '{}': {}", key, e.message
                            )
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): PeekMarkState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, KEY)

        /** 理由清理：去颜色符 / 换行，trim 后最长 80 字符；空 = 不存理由 */
        fun sanitizeReason(raw: String): String? =
            raw.replace('§', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .take(80)
                .takeIf { it.isNotEmpty() }
    }
}
