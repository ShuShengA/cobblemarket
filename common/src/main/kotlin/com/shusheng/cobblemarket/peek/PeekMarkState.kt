package com.shusheng.cobblemarket.peek

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.world.PersistentState
import java.util.UUID

/**
 * **可疑标记**（2026-11-14，2026-11-15 改为多标记）：持 `overview` 权限的人给玩家打的共享标记。
 *
 * - **每个目标 × 每个标记者各一条**：B 可以在 A 已标记的玩家身上再打自己的标记，
 *   互不覆盖；取消时只删**自己**那条
 * - 每条记录标记者名字、时间、理由（可空），悬停窗里全部列出（按时间升序）
 * - 只有 `overview` 权限的人能读/写（名单本身只发给这些人）
 * - 独立 PersistentState，重启后保留；**不自动过期**，只能手动取消
 *
 * ⚠ 新存档文件 `cobblemarket_peek_marks`：旧档没有它 = 空标记，不涉及迁移。
 *   多标记结构随 1.3.8 首发，之前未发布过，**不需要兼容单标记旧结构**。
 */
class PeekMarkState private constructor() : PersistentState() {

    data class Mark(
        val byName: String,
        val at: Long,
        val reason: String?,
    )

    /** `目标 UUID → (标记者 UUID → Mark)` */
    private val marks = mutableMapOf<UUID, MutableMap<UUID, Mark>>()

    /** 某目标的所有标记，**按时间升序**（最早 → 最新）；没有则空列表 */
    fun marksFor(target: UUID): List<Mark> =
        marks[target]?.values?.sortedBy { it.at } ?: emptyList()

    /** 该目标是否被任何人标记 */
    fun isMarked(target: UUID): Boolean = !marks[target].isNullOrEmpty()

    fun get(target: UUID, by: UUID): Mark? = marks[target]?.get(by)

    fun set(target: UUID, by: ServerPlayerEntity, reason: String?) {
        marks.getOrPut(target) { mutableMapOf() }[by.uuid] = Mark(
            byName = by.gameProfile.name,
            at = System.currentTimeMillis(),
            reason = reason,
        )
        markDirty()
    }

    /** 取消**某人对某目标**的标记；返回是否真的删除了记录 */
    fun clear(target: UUID, by: UUID): Boolean {
        val byMap = marks[target] ?: return false
        if (byMap.remove(by) == null) return false
        if (byMap.isEmpty()) marks.remove(target)
        markDirty()
        return true
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
        marks.forEach { (target, byMap) ->
            val bys = NbtCompound()
            byMap.forEach { (by, m) ->
                val entry = NbtCompound()
                entry.putString("byName", m.byName)
                entry.putLong("at", m.at)
                m.reason?.let { entry.putString("reason", it) }
                bys.put(by.toString(), entry)
            }
            map.put(target.toString(), bys)
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
                    map.keys.forEach { targetKey ->
                        // 容错：单个目标 / 单条标记坏只跳过该条并记日志（铁律）
                        try {
                            val target = UUID.fromString(targetKey)
                            val bys = map.getCompound(targetKey)
                            // 1.3.8 开发期曾临时用过「单标记」结构（**从未发布**）：旧档直接
                            // 忽略这条，不做迁移（铁律：不写迁移分支）；否则会被当成坏条目刷 warn
                            if (bys.contains("byName")) return@forEach
                            bys.keys.forEach { byKey ->
                                try {
                                    val entry = bys.getCompound(byKey)
                                    val by = UUID.fromString(byKey)
                                    marks.getOrPut(target) { mutableMapOf() }[by] = Mark(
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
                                        "Skipping corrupted peek mark for '{}' by '{}': {}",
                                        targetKey, byKey, e.message
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn(
                                "Skipping corrupted peek marks for '{}': {}", targetKey, e.message
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
