package com.shusheng.cobblemarket.market

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState

/**
 * 物品价格规则：itemId 唯一，重复添加覆盖（upsert = 编辑语义）。
 * minPrice/maxPrice null = 该侧不限制。
 */
data class ItemPriceLimitEntry(
    val itemId: String,
    val minPrice: Int?,
    val maxPrice: Int?
)

class ItemPriceLimitState private constructor() : PersistentState() {

    private val entries = mutableMapOf<String, ItemPriceLimitEntry>()

    fun add(entry: ItemPriceLimitEntry) {
        entries[entry.itemId] = entry
        markDirty()
    }

    fun remove(itemId: String): Boolean {
        val removed = entries.remove(itemId) != null
        if (removed) markDirty()
        return removed
    }

    fun getAll(): List<ItemPriceLimitEntry> = entries.values.toList()

    fun getPriceBounds(itemId: String): PriceBounds? =
        entries[itemId]?.let { PriceBounds(it.minPrice, it.maxPrice) }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        entries.values.forEach { e ->
            val c = NbtCompound()
            c.putString("itemId", e.itemId)
            c.putBoolean("hasMin", e.minPrice != null)
            e.minPrice?.let { c.putInt("minPrice", it) }
            c.putBoolean("hasMax", e.maxPrice != null)
            e.maxPrice?.let { c.putInt("maxPrice", it) }
            list.add(c)
        }
        nbt.put("entries", list)
        return nbt
    }

    companion object {
        private val TYPE = PersistentState.Type(
            { ItemPriceLimitState() },
            { nbt, _ ->
                ItemPriceLimitState().apply {
                    nbt.getList("entries", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            val entry = ItemPriceLimitEntry(
                                itemId = c.getString("itemId"),
                                minPrice = if (c.getBoolean("hasMin")) c.getInt("minPrice") else null,
                                maxPrice = if (c.getBoolean("hasMax")) c.getInt("maxPrice") else null
                            )
                            entries[entry.itemId] = entry
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted item price limit entry: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): ItemPriceLimitState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_item_price_limit")
    }
}
