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

    /** 白名单组件 ID：贸易相关；忽略 repair_cost/max_stack_size 等噪音组件
     * （服主 give 的物品磨损为 0，若存进去会变成「要求磨损=0」的怪语义）。
     *
     * ⚠ **`damage` 不在这个白名单里** —— 它是**按调用方语义**决定要不要的：
     *   规则条目（黑名单 / 价格限制 / 求购单）不要它（正是上面那条"磨损=0 怪语义"）；
     *   而**卖实物**的调用方（RP 商城的商品）**必须要**，见 [extractSpec] 的 `includeDamage`。
     *
     * ⚠ 2026-09-26 之前商城也走默认的"不要"，于是**上架物品的耐久根本没存**：一把快坏的镐子
     *   卖出去，买家拿到的是**满耐久**（等于凭空修复，玩家能刷）；表面症状则是"物品图标的
     *   耐久条永远不显示"（用户实测报上来的）。 */
    private val WHITELIST_IDS: Set<String> by lazy {
        setOf(
            Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.STORED_ENCHANTMENTS).toString(),
            Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.ENCHANTMENTS).toString(),
            Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.POTION_CONTENTS).toString(),
            Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.CUSTOM_NAME).toString(),
            Registries.DATA_COMPONENT_TYPE.getId(CobblemonItemComponents.TM_MOVE).toString(),
        )
    }

    /** `minecraft:damage` 的组件 id —— 只给 [extractSpec] 的 `includeDamage` 用（见 [WHITELIST_IDS] 的注释） */
    private val DAMAGE_ID: String by lazy {
        Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.DAMAGE).toString()
    }

    private val ENCHANTMENT_IDS: Set<String> by lazy {
        setOf(
            Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.STORED_ENCHANTMENTS).toString(),
            Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.ENCHANTMENTS).toString(),
        )
    }

    /**
     * 从手持物品提取白名单组件快照；无白名单组件返回 null（= 所有变体条目）。
     *
     * @param includeDamage **卖实物**的调用方传 `true`（RP 商城的商品物品）—— 让磨损跟着商品走，
     *   否则快坏的装备卖出去会变成满耐久（凭空修复）。规则条目保持默认 `false`，
     *   理由见 [WHITELIST_IDS] 的注释。
     */
    fun extractSpec(
        stack: ItemStack,
        registryLookup: RegistryWrapper.WrapperLookup,
        includeDamage: Boolean = false
    ): NbtCompound? {
        if (stack.isEmpty) return null
        val components = (stack.encode(registryLookup) as? NbtCompound)?.getCompound("components")
            ?: return null
        val out = NbtCompound()
        WHITELIST_IDS.forEach { key ->
            if (components.contains(key)) out.put(key, components.get(key)!!)
        }
        // 磨损按调用方语义决定收不收（见 includeDamage）。满耐久没有 damage 组件，`contains` 天然跳过
        if (includeDamage && components.contains(DAMAGE_ID)) out.put(DAMAGE_ID, components.get(DAMAGE_ID)!!)
        return out.takeIf { !it.isEmpty }
    }

    /** 服务端侧：从「itemId + 客户端传来的组件快照」重建物品并重新提取白名单组件。
     * 用于搜索路径添加变体条目（客户端构造快照，服务端不盲信——重建失败或非白名单组件一律丢弃）；失败返回 null */
    fun sanitizeSpec(
        itemId: String,
        raw: NbtCompound?,
        registryLookup: RegistryWrapper.WrapperLookup,
        includeDamage: Boolean = false
    ): NbtCompound? {
        if (raw == null || raw.isEmpty) return null
        val stack = rebuild(itemId, raw, registryLookup) ?: return null
        // ⚠ `includeDamage` 必须**透传**：服务端这道重建会把白名单外的组件丢掉，
        //   不透传的话客户端传上来的磨损还是会被吃掉（那边改了这边不改 = 白改）
        return extractSpec(stack, registryLookup, includeDamage)
    }

    // ── 「完整组件」口径（**卖实物**用，RP 商城的物品商品）────────────────────────────
    //
    // ⚠⚠ 与上面那套**白名单**口径是**两套语义、有意分开的**（2026-10-02 用户拍板：
    //   「小手按钮应该要做到完完整整的复制……不然物品商城的功能就算不完整」）：
    //   - **白名单**（[extractSpec]/[sanitizeSpec]）= **规则条目**（黑名单 / 价格限制 / 求购单）：
    //     存的是"匹配条件"，白名单外的组件会变成怪规则（磨损 ⇒ "要求物品全新"、插件等级 ⇒
    //     "必须带某个等级"），那些是噪音，**是设计不是缺陷**
    //   - **完整**（[extractFullSpec]/[sanitizeFullSpec]）= **卖实物**（RP 商城的物品商品）：
    //     买家要拿到**一模一样**的东西，「插件 / 升级数据」这类模组自定义组件必须跟着走
    //   ⇒ **别把两边"统一"掉**，那是把其中一个语义改坏。

    /**
     * **完整提取**：物品栈的**全部**组件，原样返回（无任何组件时返回 null）。
     *
     * ⚠ 只给**卖实物**的调用方用（RP 商城的物品商品），**别拿去替换** [extractSpec] —— 见上面那条。
     */
    fun extractFullSpec(stack: ItemStack, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound? {
        if (stack.isEmpty) return null
        val components = (stack.encode(registryLookup) as? NbtCompound)?.getCompound("components")
            ?: return null
        return components.takeIf { !it.isEmpty }
    }

    /**
     * 服务端侧：完整版的 [sanitizeSpec] —— **重建校验照样做**（畸形 NBT 重建不出来就直接拒，
     * 不盲信客户端），只是**不再按白名单筛**：游戏认得的组件全部保留。
     *
     * 重建走 [rebuild]，与 [sanitizeSpec] 同一段逻辑（不各写一份）。
     */
    fun sanitizeFullSpec(
        itemId: String,
        raw: NbtCompound?,
        registryLookup: RegistryWrapper.WrapperLookup
    ): NbtCompound? {
        if (raw == null || raw.isEmpty) return null
        val stack = rebuild(itemId, raw, registryLookup) ?: return null
        return extractFullSpec(stack, registryLookup)
    }

    /** 「itemId + 组件快照」→ ItemStack；重建失败（畸形 NBT / 物品 id 不存在）返回 null，绝不抛 */
    private fun rebuild(itemId: String, components: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): ItemStack? =
        try {
            ItemStack.fromNbtOrEmpty(registryLookup, NbtCompound().apply {
                putString("id", itemId)
                putInt("count", 1)
                put("components", components)
            })
        } catch (_: Throwable) {
            null
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
