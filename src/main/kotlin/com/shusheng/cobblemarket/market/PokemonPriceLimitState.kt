package com.shusheng.cobblemarket.market

import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.IVs
import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState

// 价格上下限区间：null = 该侧不限制
data class PriceBounds(val min: Int?, val max: Int?)

/**
 * 精灵价格规则：speciesId 空串 = 全部精灵；vCount -1 = 不限 V 数，0~6 = 恰好 N 个 31；
 * shinyFilter -1 = 不限闪光，0 = 仅非闪光，1 = 仅闪光。
 * (speciesId, vCount, shinyFilter) 组合唯一，重复添加覆盖（upsert = 编辑语义）。
 */
data class PokemonPriceLimitEntry(
    val speciesId: String,
    val vCount: Int,
    val shinyFilter: Int,
    val minPrice: Int?,
    val maxPrice: Int?
) {
    companion object {
        const val SHINY_ANY = -1
        const val SHINY_NO = 0
        const val SHINY_YES = 1
    }

    fun matches(targetSpeciesId: String, targetVCount: Int, targetShiny: Boolean): Boolean {
        if (speciesId.isNotEmpty() && speciesId != targetSpeciesId) return false
        if (vCount >= 0 && vCount != targetVCount) return false
        if (shinyFilter != SHINY_ANY && targetShiny != (shinyFilter == SHINY_YES)) return false
        return true
    }
}

class PokemonPriceLimitState private constructor() : PersistentState() {

    private val entries = mutableMapOf<Triple<String, Int, Int>, PokemonPriceLimitEntry>()

    fun add(entry: PokemonPriceLimitEntry) {
        entries[Triple(entry.speciesId, entry.vCount, entry.shinyFilter)] = entry
        markDirty()
    }

    fun remove(speciesId: String, vCount: Int, shinyFilter: Int): Boolean {
        val removed = entries.remove(Triple(speciesId, vCount, shinyFilter)) != null
        if (removed) markDirty()
        return removed
    }

    fun getAll(): List<PokemonPriceLimitEntry> = entries.values.toList()

    /** 该精灵所有匹配规则的最严交集：min 取最大、max 取最小；无匹配规则返回 null（不限制）。 */
    fun getPriceBounds(targetSpeciesId: String, targetVCount: Int, targetShiny: Boolean): PriceBounds? {
        val matched = entries.values.filter { it.matches(targetSpeciesId, targetVCount, targetShiny) }
        if (matched.isEmpty()) return null
        return PriceBounds(
            min = matched.mapNotNull { it.minPrice }.maxOrNull(),
            max = matched.mapNotNull { it.maxPrice }.minOrNull()
        )
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        entries.values.forEach { e ->
            val c = NbtCompound()
            c.putString("speciesId", e.speciesId)
            c.putInt("vCount", e.vCount)
            c.putInt("shinyFilter", e.shinyFilter)
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
        // 可配的 V 档位：0~6（恰好 N 个 31）；-1 = 不限
        val V_COUNT_RANGE = 0..6

        /** IV 中恰好等于 31 的项数（0~6） */
        fun vCountOf(ivs: IVs): Int = listOf(
            Stats.HP, Stats.ATTACK, Stats.DEFENCE,
            Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED
        ).count { ivs[it] == 31 }

        private val TYPE = PersistentState.Type(
            { PokemonPriceLimitState() },
            { nbt, _ ->
                PokemonPriceLimitState().apply {
                    nbt.getList("entries", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            val entry = PokemonPriceLimitEntry(
                                speciesId = c.getString("speciesId"),
                                vCount = c.getInt("vCount"),
                                // 旧格式无 shinyFilter 字段 → 不限闪光，保持旧语义
                                shinyFilter = if (c.contains("shinyFilter")) c.getInt("shinyFilter") else PokemonPriceLimitEntry.SHINY_ANY,
                                minPrice = if (c.getBoolean("hasMin")) c.getInt("minPrice") else null,
                                maxPrice = if (c.getBoolean("hasMax")) c.getInt("maxPrice") else null
                            )
                            entries[Triple(entry.speciesId, entry.vCount, entry.shinyFilter)] = entry
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted pokemon price limit entry: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): PokemonPriceLimitState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_pokemon_price_limit")
    }
}
