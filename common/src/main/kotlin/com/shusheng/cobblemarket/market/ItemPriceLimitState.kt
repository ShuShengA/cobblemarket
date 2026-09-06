package com.shusheng.cobblemarket.market

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState

/**
 * 物品价格规则：(itemId + 组件快照) 唯一，重复添加覆盖（upsert = 编辑语义）；
 * 同 itemId 不同组件快照 = 独立条目并存（2026-09-06 组件粒度拍板）。
 * componentsSpec null/空 = 所有变体（旧数据零迁移兼容）。
 * minPrice/maxPrice null = 该侧不限制。
 */
data class ItemPriceLimitEntry(
    val itemId: String,
    val componentsSpec: NbtCompound?,
    val minPrice: Int?,
    val maxPrice: Int?
)

/**
 * 多条物品限价规则同时命中时合并 = 取交集最严：下限取最大、上限取最小。
 * ⚠ 与 [mergePriceBounds]（精灵+携带物两部分价格相加）语义不同，勿混用。
 */
fun mergeRuleBounds(hit: List<ItemPriceLimitEntry>): PriceBounds? {
    var min: Int? = null
    var max: Int? = null
    hit.forEach { e ->
        if (e.minPrice != null) min = maxOf(min ?: e.minPrice, e.minPrice)
        if (e.maxPrice != null) max = minOf(max ?: e.maxPrice, e.maxPrice)
    }
    return if (min == null && max == null) null else PriceBounds(min, max)
}

class ItemPriceLimitState private constructor() : PersistentState() {

    /** 最新添加在前（列表显示顺序），持久化按此顺序写 */
    private val entries = mutableListOf<ItemPriceLimitEntry>()

    fun add(entry: ItemPriceLimitEntry) {
        // 按键覆盖：同 itemId + 同组件快照 = 更新价格并移到最前；不同组件快照 = 独立条目
        entries.removeAll { it.itemId == entry.itemId && ItemRuleComponents.specsEqual(it.componentsSpec, entry.componentsSpec) }
        entries.add(0, entry)
        markDirty()
    }

    fun remove(itemId: String, componentsSpec: NbtCompound?): Boolean {
        val removed = entries.removeAll { it.itemId == itemId && ItemRuleComponents.specsEqual(it.componentsSpec, componentsSpec) }
        if (removed) markDirty()
        return removed
    }

    fun getAll(): List<ItemPriceLimitEntry> = entries.toList()

    /** 完整物品匹配：命中条目按「最具体优先」（组件要求最多的条目生效，2026-09-06 拍板）——
     * 锋利V+抢夺III 走自己的条目而不是被「锋利V」条目的上限压死；同具体程度多条仍取交集最严 */
    fun getPriceBounds(stack: ItemStack, registryLookup: RegistryWrapper.WrapperLookup): PriceBounds? {
        val hit = entries.filter { ItemRuleComponents.matches(stack, it.itemId, it.componentsSpec, registryLookup) }
        if (hit.isEmpty()) return null
        val maxSpec = hit.maxOf { ItemRuleComponents.specSize(it.componentsSpec) }
        return mergeRuleBounds(hit.filter { ItemRuleComponents.specSize(it.componentsSpec) == maxSpec })
    }

    /** 只有 id 的场景（老格式容器内容）：组件无法验证，只按无组件条目取 bounds */
    fun getPriceBoundsByIdOnly(itemId: String): PriceBounds? {
        val hit = entries.filter { it.itemId == itemId && (it.componentsSpec == null || it.componentsSpec.isEmpty) }
        return mergeRuleBounds(hit)
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        entries.forEach { e ->
            val c = NbtCompound()
            c.putString("itemId", e.itemId)
            e.componentsSpec?.takeIf { !it.isEmpty }?.let { c.put("components", it) }
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
                            val spec = if (c.contains("components")) c.getCompound("components").takeIf { !it.isEmpty } else null
                            val entry = ItemPriceLimitEntry(
                                itemId = c.getString("itemId"),
                                componentsSpec = spec,
                                minPrice = if (c.getBoolean("hasMin")) c.getInt("minPrice") else null,
                                maxPrice = if (c.getBoolean("hasMax")) c.getInt("maxPrice") else null
                            )
                            entries.add(entry)
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
