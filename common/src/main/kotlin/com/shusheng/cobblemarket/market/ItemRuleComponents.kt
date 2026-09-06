package com.shusheng.cobblemarket.market

import com.cobblemon.mod.common.CobblemonItemComponents
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtHelper
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryWrapper

/**
 * 物品规则条目（黑名单/价格限制/求购单）的组件粒度支持。
 *
 * 条目组件快照（componentsSpec）：手持物品添加时从物品提取的白名单组件 NBT；
 * null/空 = 旧式「所有变体」条目（纯 itemId），无快照自然向后兼容。
 *
 * 匹配语义 = 包含匹配（条目组件 ⊆ 物品组件，2026-09-06 拍板）：
 * - 组件 key 级：条目里每个组件，物品必须有
 * - 附魔类组件（stored_enchantments/enchantments）：条目每个 (附魔,等级) 物品须有同附魔
 *   且等级 ≥ 条目等级——「锋利V」条目命中「锋利V+耐久III」，防加垃圾附魔绕过
 * - 其余组件：NBT 结构相等（potion_contents 暂不做效果子集，罕见场景可纯 id 条目覆盖）
 */
object ItemRuleComponents {

    /** 白名单组件 ID：贸易相关；忽略 damage/repair_cost/max_stack_size 等噪音组件
     * （服主 give 的物品磨损为 0，若存进去会变成「要求磨损=0」的怪语义） */
    private val WHITELIST_IDS: Set<String> by lazy {
        setOf(
            Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.STORED_ENCHANTMENTS).toString(),
            Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.ENCHANTMENTS).toString(),
            Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.POTION_CONTENTS).toString(),
            Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.CUSTOM_NAME).toString(),
            Registries.DATA_COMPONENT_TYPE.getId(CobblemonItemComponents.TM_MOVE).toString(),
        )
    }

    private val ENCHANTMENT_IDS: Set<String> by lazy {
        setOf(
            Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.STORED_ENCHANTMENTS).toString(),
            Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.ENCHANTMENTS).toString(),
        )
    }

    /** 从手持物品提取白名单组件快照；无白名单组件返回 null（= 所有变体条目） */
    fun extractSpec(stack: ItemStack, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound? {
        if (stack.isEmpty) return null
        val components = (stack.encode(registryLookup) as? NbtCompound)?.getCompound("components")
            ?: return null
        val out = NbtCompound()
        WHITELIST_IDS.forEach { key ->
            if (components.contains(key)) out.put(key, components.get(key)!!)
        }
        return out.takeIf { !it.isEmpty }
    }

    /** 条目组件要求的「具体程度」：附魔按个数计（锋利V+抢夺III=2 比 锋利V=1 更具体），其它组件各计 1；无组件=0。
     * 限价多条目命中的「最具体优先」用（2026-09-06 拍板：避免「锋利V [10,10]」压死「锋利V+抢夺III [20,30]」产生空区间） */
    fun specSize(spec: NbtCompound?): Int {
        if (spec == null || spec.isEmpty) return 0
        var size = 0
        spec.keys.forEach { key ->
            if (key in ENCHANTMENT_IDS) size += spec.getCompound(key)?.getCompound("levels")?.size ?: 0
            else size += 1
        }
        return size
    }

    /** 条目快照相等（重复添加按键覆盖用；NbtHelper 结构比较避开 toString 的 key 顺序问题） */
    fun specsEqual(a: NbtCompound?, b: NbtCompound?): Boolean =
        when {
            a == null || a.isEmpty -> b == null || b.isEmpty
            b == null || b.isEmpty -> false
            else -> NbtHelper.matches(a, b, false)
        }

    /** 包含匹配：物品组件 ⊇ 条目组件。spec null/空 = 只按 itemId 匹配（所有变体） */
    fun matches(stack: ItemStack, itemId: String, spec: NbtCompound?, registryLookup: RegistryWrapper.WrapperLookup): Boolean {
        if (stack.isEmpty) return false
        if (Registries.ITEM.getId(stack.item).toString() != itemId) return false
        if (spec == null || spec.isEmpty) return true
        val components = (stack.encode(registryLookup) as? NbtCompound)?.getCompound("components")
            ?: return false
        spec.keys.forEach { key ->
            if (!components.contains(key)) return false
            val specValue = spec.get(key)!!
            val itemValue = components.get(key)!!
            if (key in ENCHANTMENT_IDS) {
                if (!enchantsContained(specValue, itemValue)) return false
            } else if (!NbtHelper.matches(specValue, itemValue, false)) {
                return false
            }
        }
        return true
    }

    /** 附魔包含：条目每个附魔物品须有且等级 ≥。解析失败（未知 NBT 格式）退化精确结构比较。 */
    private fun enchantsContained(specValue: NbtElement, itemValue: NbtElement): Boolean {
        val specLevels = enchantLevels(specValue) ?: return NbtHelper.matches(specValue, itemValue, false)
        val itemLevels = enchantLevels(itemValue) ?: return false
        return specLevels.all { (ench, lvl) -> (itemLevels[ench] ?: 0) >= lvl }
    }

    /** 附魔组件 NBT → (附魔 id, 等级)。支持 1.21.1 的 {levels: {...}} 与 1.21.2+ 的 {enchantments: [...]}。 */
    private fun enchantLevels(nbt: NbtElement): Map<String, Int>? {
        if (nbt !is NbtCompound) return null
        if (nbt.contains("levels")) {
            val levels = nbt.getCompound("levels")
            return levels.keys.associateWith { levels.getInt(it) }
        }
        if (nbt.contains("enchantments")) {
            val list = nbt.getList("enchantments", NbtElement.COMPOUND_TYPE.toInt())
            val out = mutableMapOf<String, Int>()
            for (i in 0 until list.size) {
                val e = list.getCompound(i)
                out[e.getString("id")] = e.getInt("lvl")
            }
            return out
        }
        return null
    }
}
