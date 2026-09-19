package com.shusheng.cobblemarket.util

import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.text.Text
import net.minecraft.util.Identifier

object SpeciesText {
    fun translationKey(species: Species): String {
        val content = species.translatedName.content
        if (content is net.minecraft.text.TranslatableTextContent) {
            return content.key
        }
        return "${species.resourceIdentifier.namespace}.species.${species.showdownId()}.name"
    }

    fun translated(species: Species): Text = Text.translatable(translationKey(species))

    // 客户端显示名：数据包物种未配语言文件时翻译返回 key 原文，fallback 到物种显示名（如 "Foo"）
    fun displayName(species: Species): String {
        val key = translationKey(species)
        val t = Text.translatable(key).string
        return if (t == key) species.name else t
    }

    /**
     * **规则对话框的候选解析**：把玩家输入的物种名/id 解析成候选列表（可能多个）。
     *
     * 分层匹配、**精确优先** —— 照物品侧 `ItemCandidateResolver` 那套已经验证过的做法：
     * ① 资源 id / 英文名精确 → ② 显示名精确 → ③ 显示名包含，保序去重。
     *
     * ⚠ 界面**不要**再自己写 `firstOrNull { == 输入 || contains 输入 }` 取第一个：中文名互为
     * 子串的情形很多（「鬼斯」是「鬼斯通」的前缀），那样会把玩家输入的名字**静默换成别的物种**
     * —— 黑名单 / 价格限制 / 求购单都是在配规则，配错物种玩家极难察觉。
     * 多候选时由界面交给玩家挑（箭头切换 / 列表点选），本函数只回答「有哪些」。
     */
    fun candidatesByNameOrId(input: String): List<Species> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.contains(":")) {
            val byId = Identifier.tryParse(trimmed)
                ?.let { com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getByIdentifier(it) }
            return if (byId != null) listOf(byId) else emptyList()
        }
        val lower = trimmed.lowercase().replace(" ", "_")
        val all: List<Species> = com.cobblemon.mod.common.api.pokemon.PokemonSpecies.implemented
        val out = LinkedHashMap<String, Species>()
        fun add(s: Species) { out.putIfAbsent(s.resourceIdentifier.toString(), s) }
        all.firstOrNull { s -> s.showdownId() == lower || s.name == lower }?.let { add(it) }
        all.filter { s -> s.translatedName.string == trimmed }.forEach { add(it) }
        all.filter { s -> s.translatedName.string.contains(trimmed) }.forEach { add(it) }
        return out.values.toList()
    }

    fun resolveByNameOrId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains(":")) return trimmed
        val lower = trimmed.lowercase().replace(" ", "_")
        val all: List<Species> = com.cobblemon.mod.common.api.pokemon.PokemonSpecies.implemented
        all.firstOrNull { s ->
            s.showdownId() == lower || s.name == lower
        }?.let { return it.resourceIdentifier.toString() }
        // ⚠ 服务端兜底路径：客户端解析失败时才会走到这儿（正常情况传的是 id，上面就返回了）。
        //   **精确优先**：先找显示名完全等于输入的，再退到包含 —— 别让「鬼斯」被「鬼斯通」截胡
        all.firstOrNull { s -> s.translatedName.string == trimmed }
            ?.let { return it.resourceIdentifier.toString() }
        all.firstOrNull { s ->
            s.translatedName.string.contains(trimmed)
        }?.let { return it.resourceIdentifier.toString() }
        return null
    }
}
