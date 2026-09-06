package com.shusheng.cobblemarket.client

import net.minecraft.client.MinecraftClient
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.registry.Registries

/**
 * 物品搜索索引（照原版创造模式 SearchManager 思路，懒构建）：
 * - 物品维度：每个物品默认栈的「itemId + 物品名 + tooltip 文本」纳入匹配——
 *   附魔名/描述等原版创造模式能搜的内容这里同样能搜（并集扩展，原 itemId/名称匹配保留）
 * - TM 招式维度：遍历 Cobblemon Moves 注册表，给每个招式构建带组件的 TM 栈，
 *   招式名/描述等 tooltip 内容一并索引——Cobblemon 以后加招式自动沿用，零改动
 * - 失效重建：语言切换（tooltip 文本随语言）或招式数变化（数据同步/资源重载）
 */
object ItemSearchIndex {

    private const val TM_ITEM_ID = "cobblemon:technical_machine"
    private const val ENCHANTED_BOOK_ID = "minecraft:enchanted_book"

    private var cacheLang: String? = null
    private var cacheMoveCount = -1
    private var itemTexts: Map<String, String> = emptyMap()
    private var moveTexts: Map<String, String> = emptyMap()
    private var enchantTexts: Map<String, String> = emptyMap()

    private fun ensureBuilt() {
        val lang = MinecraftClient.getInstance().options.language
        val moveCount = try {
            com.cobblemon.mod.common.api.moves.Moves.count()
        } catch (_: Throwable) { -1 }
        if (cacheLang == lang && cacheMoveCount == moveCount) return
        cacheLang = lang
        cacheMoveCount = moveCount

        val itemMap = mutableMapOf<String, String>()
        val moveMap = mutableMapOf<String, String>()
        val enchantMap = mutableMapOf<String, String>()
        val player = MinecraftClient.getInstance().player
        val context = Item.TooltipContext.DEFAULT
        // 创造模式 tooltip（照原版搜索索引：显示全部信息、无 Shift 提示行，避免搜「shift」误命中）
        val tooltipType = TooltipType.BASIC.withCreative()

        // 物品：默认栈的 ID + 名称 + tooltip 文本
        Registries.ITEM.forEach { item ->
            val id = Registries.ITEM.getId(item).toString()
            val stack = ItemStack(item)
            val sb = StringBuilder()
            sb.append(id).append(' ')
            sb.append(stack.name.string).append(' ')
            stack.getTooltip(context, player, tooltipType).forEach { sb.append(it.string).append(' ') }
            itemMap[id] = sb.toString().lowercase()
        }

        // TM 招式枚举：带招式组件的栈，招式 ID/显示名/描述等一并索引（Cobblemon 未实现创造栏变体枚举，这里补齐）
        try {
            com.cobblemon.mod.common.api.moves.Moves.all().forEach { move ->
                val stack = com.cobblemon.mod.common.item.components.TMMoveComponent.createStack(move)
                val sb = StringBuilder()
                sb.append(move.name).append(' ')
                sb.append(move.displayName.string).append(' ')
                stack.getTooltip(context, player, tooltipType).forEach { sb.append(it.string).append(' ') }
                moveMap[move.name] = sb.toString().lowercase()
            }
        } catch (_: Throwable) {
            // Cobblemon 数据未就绪（登录同步前）时跳过招式维度，下次查询重建
        }

        // 附魔书枚举：全部附魔的满级变体栈（照原版创造栏 ItemGroups 的附魔书枚举——搜「锋利」出锋利附魔书）
        MinecraftClient.getInstance().world?.registryManager?.let { rm ->
            val enchRegistry = rm.get(net.minecraft.registry.RegistryKeys.ENCHANTMENT)
            enchRegistry.streamEntries().forEach { entry ->
                val enchId = entry.registryKey().value.toString()
                val ench = entry.value()
                val stack = net.minecraft.item.EnchantedBookItem.forEnchantment(
                    net.minecraft.enchantment.EnchantmentLevelEntry(entry, ench.maxLevel)
                )
                val sb = StringBuilder()
                sb.append(enchId).append(' ')
                stack.getTooltip(context, player, tooltipType).forEach { sb.append(it.string).append(' ') }
                enchantMap[enchId] = sb.toString().lowercase()
            }
        }

        itemTexts = itemMap
        moveTexts = moveMap
        enchantTexts = enchantMap
    }

    /** 查询命中的 itemId 列表（含 TM 招式命中时的 technical_machine、附魔命中时的 enchanted_book 条目，供 ID 粒度场景） */
    fun itemIdsMatching(query: String): List<String> = itemIdsMatchingInternal(query, true)

    /** 严格版：不含 TM/附魔的粗粒度附加项（服务端精确过滤场景用——TM/附魔书条目必须走组件比对） */
    fun itemIdsMatchingStrict(query: String): List<String> = itemIdsMatchingInternal(query, false)

    private fun itemIdsMatchingInternal(query: String, includeVariants: Boolean): List<String> {
        ensureBuilt()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val ids = mutableListOf<String>()
        itemTexts.forEach { (id, text) -> if (text.contains(q)) ids.add(id) }
        if (includeVariants) {
            if (moveTexts.values.any { it.contains(q) } && TM_ITEM_ID in itemTexts) ids.add(TM_ITEM_ID)
            if (enchantTexts.values.any { it.contains(q) } && ENCHANTED_BOOK_ID in itemTexts) ids.add(ENCHANTED_BOOK_ID)
        }
        return ids.distinct()
    }

    /** 查询命中的招式 ID 集合（服务端按条目 NBT 组件精确过滤 TM 用） */
    fun tmMovesMatching(query: String): Set<String> {
        ensureBuilt()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptySet()
        return moveTexts.filterValues { it.contains(q) }.keys
    }

    /** 查询命中的附魔 ID 集合（服务端按条目 NBT 组件精确过滤附魔书用） */
    fun enchantsMatching(query: String): Set<String> {
        ensureBuilt()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptySet()
        return enchantTexts.filterValues { it.contains(q) }.keys
    }

    /** 查询是否命中任意招式（本地过滤的粗粒度判断用） */
    fun hasTmMoveMatch(query: String): Boolean {
        ensureBuilt()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        return moveTexts.values.any { it.contains(q) }
    }

    /** 条目级匹配（本地过滤，条目带 NBT 时精确）：itemId 文本命中 || TM 条目且 NBT 招式命中 || 附魔书条目且 NBT 附魔命中；空查询恒真 */
    fun entryMatches(itemId: String, itemNbt: net.minecraft.nbt.NbtCompound?, query: String): Boolean {
        ensureBuilt()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        if (itemTexts[itemId]?.contains(q) == true) return true
        val rm = MinecraftClient.getInstance().world?.registryManager ?: return false
        if (itemId == TM_ITEM_ID) {
            val moves = moveTexts.filterValues { it.contains(q) }.keys
            if (moves.isEmpty()) return false
            val move = com.shusheng.cobblemarket.network.tmMoveOfItemNbt(itemNbt, rm) ?: return false
            return move in moves
        }
        if (itemId == ENCHANTED_BOOK_ID) {
            // 索引文本命中 + 条目附魔显示名命中（Direct/Reference entry 都兼容——显示名不依赖注册表 key）
            if (enchantTexts.values.none { it.contains(q) }) return false
            return enchantsOfItemNbt(itemNbt, rm).any { it.contains(q) }
        }
        return false
    }

    /** 条目级匹配（本地过滤，无 NBT 场景粗粒度）：itemId 文本命中 || TM 条目且任意招式命中 || 附魔书条目且任意附魔命中；空查询恒真 */
    fun idMatches(itemId: String, query: String): Boolean {
        ensureBuilt()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        if (itemTexts[itemId]?.contains(q) == true) return true
        if (itemId == TM_ITEM_ID && moveTexts.values.any { it.contains(q) }) return true
        return itemId == ENCHANTED_BOOK_ID && enchantTexts.values.any { it.contains(q) }
    }

    /** 规则条目匹配（黑名单/限价/求购单列表搜索）：带组件快照时构造物品 NBT 走 [entryMatches] 精确；
     * 无组件条目仅物品文本命中（搜「锋利」只出锋利V 条目，不把「所有附魔书」条目带出来）。空查询恒真 */
    fun ruleEntryMatches(itemId: String, componentsSpec: net.minecraft.nbt.NbtCompound?, query: String): Boolean {
        val nbt = if (componentsSpec != null && !componentsSpec.isEmpty) net.minecraft.nbt.NbtCompound().apply {
            putString("id", itemId)
            putInt("count", 1)
            put("components", componentsSpec)
        } else null
        return entryMatches(itemId, nbt, query)
    }

    /** 条目 NBT 的附魔显示名文本列表（非附魔书或解析失败返回空；精确过滤用——显示名不依赖注册表 key，Direct entry 兼容） */
    private fun enchantsOfItemNbt(itemNbt: net.minecraft.nbt.NbtCompound?, registryLookup: net.minecraft.registry.RegistryWrapper.WrapperLookup): List<String> {
        if (itemNbt == null) return emptyList()
        return try {
            val stack = ItemStack.fromNbtOrEmpty(registryLookup, itemNbt)
            if (stack.isEmpty) emptyList()
            else {
                // 附魔书存 stored_enchantments 组件（普通物品才是 enchantments）——读 stored，回退普通
                val comp = stack.get(net.minecraft.component.DataComponentTypes.STORED_ENCHANTMENTS)
                    ?: stack.enchantments
                comp.enchantments.map { it.value().description.string.lowercase() }
            }
        } catch (_: Throwable) { emptyList() }
    }
}
