package com.shusheng.cobblemarket.market

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState

/**
 * 物品黑名单条目：itemId + 可选组件快照（null/空 = 所有变体，旧数据零迁移兼容）。
 * 组件粒度匹配语义见 [ItemRuleComponents]（包含匹配）。
 */
data class ItemBlacklistEntry(
    val itemId: String,
    val componentsSpec: NbtCompound?,
)

class ItemBlacklistState private constructor() : PersistentState() {

    /** 最新添加在前（列表显示顺序），持久化按此顺序写 */
    private val blacklist = mutableListOf<ItemBlacklistEntry>()

    /** 按键覆盖（2026-09-06 拍板）：同 itemId + 同组件快照重复添加 = 去重并移到最前；同 itemId 不同组件 = 独立条目并存 */
    fun add(itemId: String, componentsSpec: NbtCompound?) {
        blacklist.removeAll { it.itemId == itemId && ItemRuleComponents.specsEqual(it.componentsSpec, componentsSpec) }
        blacklist.add(0, ItemBlacklistEntry(itemId, componentsSpec))
        markDirty()
    }

    fun remove(itemId: String, componentsSpec: NbtCompound?): Boolean {
        val removed = blacklist.removeAll { it.itemId == itemId && ItemRuleComponents.specsEqual(it.componentsSpec, componentsSpec) }
        if (removed) markDirty()
        return removed
    }

    /** 完整物品匹配：任一命中即拦 */
    fun matches(stack: ItemStack, registryLookup: RegistryWrapper.WrapperLookup): Boolean =
        blacklist.any { ItemRuleComponents.matches(stack, it.itemId, it.componentsSpec, registryLookup) }

    /** 只有 id 的场景（老格式容器内容）：组件无法验证，只命中无组件条目（组件条目不误伤也不强拦） */
    fun matchesByIdOnly(itemId: String): Boolean =
        blacklist.any { it.itemId == itemId && (it.componentsSpec == null || it.componentsSpec.isEmpty) }

    fun getAll(): List<ItemBlacklistEntry> = blacklist.toList()

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        blacklist.forEach { entry ->
            val c = NbtCompound()
            c.putString("itemId", entry.itemId)
            entry.componentsSpec?.takeIf { !it.isEmpty }?.let { c.put("components", it) }
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
                            val spec = if (c.contains("components")) c.getCompound("components").takeIf { !it.isEmpty } else null
                            blacklist.add(ItemBlacklistEntry(c.getString("itemId"), spec))
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
