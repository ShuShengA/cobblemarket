package com.shusheng.cobblemarket.client

import net.minecraft.item.Items
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier

/**
 * 物品搜索候选（黑名单/价格限制/求购单的添加对话框共用）：
 * componentsSpec 为 null = 无组件「全部变体」条目（整个物品）；
 * 非 null = 具体变体（如「招式学习器 · 打鼾」），快照格式与 ItemRuleComponents 白名单一致。
 */
data class ItemCandidate(
    val itemId: String,
    val componentsSpec: NbtCompound?,
    val displayName: String,
)

/**
 * 候选解析：ID 路径精确 > 翻译名精确 > 翻译名包含 > 物品自身 tooltip 文本 > 招式/附魔变体展开。
 *
 * 与旧实现的关键区别：招式/附魔命中不再追加无组件的粗粒度物品（那样添加出来是「全部变体」条目，
 * 搜「打鼾」会连坐所有招式学习器），而是展开成每个命中招式/附魔一个带组件快照的候选。
 */
object ItemCandidateResolver {

    fun resolve(input: String): List<ItemCandidate> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.contains(":")) return listOf(basic(trimmed))

        val lower = trimmed.lowercase().replace(" ", "_")
        val out = LinkedHashMap<String, ItemCandidate>()
        fun add(candidate: ItemCandidate) {
            out.putIfAbsent(candidate.itemId + '|' + (candidate.componentsSpec?.toString() ?: ""), candidate)
        }

        // cobblemon / minecraft 前缀优先（无命名空间的常用物品 ID）
        listOf("cobblemon", "minecraft").forEach { ns ->
            Identifier.tryParse("$ns:$lower")?.let { id ->
                if (Registries.ITEM.get(id) != Items.AIR) add(basic(id.toString()))
            }
        }
        Registries.ITEM.forEach { item ->
            val id = Registries.ITEM.getId(item)
            if (id.path == lower) add(basic(id.toString()))
        }
        Registries.ITEM.forEach { item ->
            val id = Registries.ITEM.getId(item)
            if (item.name.string == trimmed) add(basic(id.toString()))
        }
        Registries.ITEM.forEach { item ->
            val id = Registries.ITEM.getId(item)
            if (item.name.string.contains(trimmed)) add(basic(id.toString()))
        }
        // 物品自身 tooltip 文本命中（不含招式/附魔的粗粒度追加——那部分走变体展开）
        ItemSearchIndex.itemIdsMatchingStrict(input).forEach { add(basic(it)) }
        // 变体展开：TM 招式 / 附魔书
        ItemSearchIndex.variantCandidates(input).forEach { add(it) }
        return out.values.toList()
    }

    /** 无组件候选（显示名 = 物品名；缺翻译的物品回退资源路径，与界面原 itemDisplay 一致） */
    private fun basic(itemId: String): ItemCandidate {
        val id = Identifier.tryParse(itemId) ?: return ItemCandidate(itemId, null, itemId)
        val item = Registries.ITEM.get(id)
        if (item == Items.AIR) return ItemCandidate(itemId, null, itemId)
        return ItemCandidate(itemId, null, displayNameOf(itemId))
    }

    /** 物品显示名：缺翻译的物品（第三方模组缺 lang）回退资源路径，避免显示超长翻译 key 原文。
     * 三个界面（黑名单/价格限制/求购单）的条目行显示共用 */
    fun displayNameOf(itemId: String): String {
        val id = Identifier.tryParse(itemId) ?: return itemId
        val item = Registries.ITEM.get(id)
        if (item == Items.AIR) return itemId
        val name = item.name.string
        return if (name == item.translationKey) id.path else name
    }
}
