package com.shusheng.cobblemarket.market

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState

class ItemBlacklistState private constructor() : PersistentState() {

    private val blacklist = mutableSetOf<String>()

    fun add(itemId: String) {
        // 先删后插：LinkedHashSet 对已存在元素的 add 不移动位置，重复添加会留在旧位置（列表显示"没加到第一行"）
        blacklist.remove(itemId)
        blacklist.add(itemId)
        markDirty()
    }

    fun remove(itemId: String): Boolean {
        val removed = blacklist.remove(itemId)
        if (removed) markDirty()
        return removed
    }

    fun contains(itemId: String): Boolean = blacklist.contains(itemId)

    fun getAll(): List<String> = blacklist.toList()

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        blacklist.forEach { itemId ->
            val c = NbtCompound()
            c.putString("itemId", itemId)
            list.add(c)
        }
        nbt.put("blacklist", list)
        return nbt
    }

    companion object {
        private val TYPE = PersistentState.Type(
            { ItemBlacklistState() },
            { nbt, _ ->
                ItemBlacklistState().apply {
                    nbt.getList("blacklist", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            blacklist.add(c.getString("itemId"))
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted item blacklist entry: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): ItemBlacklistState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_item_blacklist")
    }
}
