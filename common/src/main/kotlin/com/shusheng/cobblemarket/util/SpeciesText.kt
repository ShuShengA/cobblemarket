package com.shusheng.cobblemarket.util

import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.text.Text

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

    fun resolveByNameOrId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains(":")) return trimmed
        val lower = trimmed.lowercase().replace(" ", "_")
        val all: List<Species> = com.cobblemon.mod.common.api.pokemon.PokemonSpecies.implemented
        all.firstOrNull { s ->
            s.showdownId() == lower || s.name == lower
        }?.let { return it.resourceIdentifier.toString() }
        all.firstOrNull { s ->
            s.translatedName.string == trimmed || s.translatedName.string.contains(trimmed)
        }?.let { return it.resourceIdentifier.toString() }
        return null
    }
}
