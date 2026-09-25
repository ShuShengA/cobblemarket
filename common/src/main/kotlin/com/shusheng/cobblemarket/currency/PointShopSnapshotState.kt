package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState

/**
 * 服主的**配方快照库**（`cobblemarket_point_shop_snapshots.dat`）。
 *
 * **为什么要有它**（2026-09-25 用户定）：服主不再能"编辑已有格子"了 —— 点有内容的格子只能
 * **查看 + 下架**，改内容走「下架 → 重新添加」。没有快照的话，改个价格就要把二十来个字段
 * 重配一遍。有了它，「重新添加」变成「选快照 → 改想改的 → 添加」。
 *
 * 顺带解决**跨格复用**：同一套配置要上架好几格时，配一次就够。
 *
 * ⚠ **存在服务端**（跟着世界走）：换客户端还在，持 `points.shop` 节点的人都能用。
 * ⚠ 上限 [MAX_SNAPSHOTS]：满了拒绝新存并提示先删旧的，防存档无界膨胀。
 * ⚠ 存档结构与格子那套**共用** [writeShopEntryBody] / [readShopEntryBody]（见 PointShopState.kt），
 *   别再写第二份序列化。
 */
class PointShopSnapshotState private constructor() : PersistentState() {

    /** 一条快照：名字（**唯一**，重名即覆盖）+ 整份规格（含价格与库存） */
    data class Snapshot(val name: String, val entry: ShopEntry)

    /** 保序：按存的先后排，服主找起来有规律 */
    private val snapshots = mutableListOf<Snapshot>()

    fun all(): List<Snapshot> = snapshots

    fun find(name: String): Snapshot? = snapshots.firstOrNull { it.name == name }

    /**
     * 存一条。**同名覆盖**（服主改完再存一次，不该变成两条）。
     * 返回 false = 已经满 [MAX_SNAPSHOTS] 条、且这次又不是覆盖。
     */
    fun put(name: String, entry: ShopEntry): Boolean {
        val idx = snapshots.indexOfFirst { it.name == name }
        if (idx >= 0) {
            snapshots[idx] = Snapshot(name, entry)
            markDirty()
            return true
        }
        if (snapshots.size >= MAX_SNAPSHOTS) return false
        snapshots.add(Snapshot(name, entry))
        markDirty()
        return true
    }

    /** 删一条；返回是否真删掉了 */
    fun remove(name: String): Boolean {
        if (!snapshots.removeAll { it.name == name }) return false
        markDirty()
        return true
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        snapshots.forEach { snapshot ->
            val c = NbtCompound()
            c.putString("name", snapshot.name)
            // 规格本体走与格子存档**同一份**序列化
            writeShopEntryBody(c, snapshot.entry)
            list.add(c)
        }
        nbt.put("snapshots", list)
        return nbt
    }

    companion object {
        /** 快照数量上限（超了提示先删旧的） */
        const val MAX_SNAPSHOTS = 64

        /** 名字长度上限 —— 与协议层的 `MAX_TEXT_LENGTH` 对齐，服务端另有一道校验 */
        const val MAX_NAME_LENGTH = 32

        private val TYPE = PersistentState.Type(
            { PointShopSnapshotState() },
            { nbt, _ ->
                PointShopSnapshotState().apply {
                    // 逐条容错：单条损坏只跳过它 + warn，绝不整体抛异常（CLAUDE.md 的存档铁律）
                    nbt.getList("snapshots", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            val name = c.getString("name")
                            if (name.isBlank()) return@forEach
                            val entry = readShopEntryBody(c) ?: return@forEach
                            snapshots.add(Snapshot(name, entry))
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted shop snapshot: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): PointShopSnapshotState =
            server.overworld.persistentStateManager
                .getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_point_shop_snapshots")
    }
}
