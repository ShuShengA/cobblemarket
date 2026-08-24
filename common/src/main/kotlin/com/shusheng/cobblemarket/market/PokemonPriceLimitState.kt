package com.shusheng.cobblemarket.market

import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.IVs
import com.cobblemon.mod.common.pokemon.Pokemon
import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtString
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState

// 价格上下限区间：null = 该侧不限制
data class PriceBounds(val min: Int?, val max: Int?)

// 精灵与携带物价格限制合并：下限两侧相加（不限侧按 0 参与）；
// 上限仅当两侧都设限时相加（一侧不限则总上限不限——精灵部分可以定价无限高）。
// 合并后无实际约束（下限 0 且无上限）返回 null。
fun mergePriceBounds(a: PriceBounds?, b: PriceBounds?): PriceBounds? {
    if (a == null) return b
    if (b == null) return a
    val min = (a.min ?: 0) + (b.min ?: 0)
    val max = if (a.max != null && b.max != null) a.max + b.max else null
    return if (min == 0 && max == null) null else PriceBounds(min, max)
}

/**
 * 精灵价格规则：speciesId 空串 = 全部精灵；vCount -1 = 不限 V 数，0~6 = 恰好 N 个 31；
 * shinyFilter -1 = 不限闪光，0 = 仅非闪光，1 = 仅闪光；aspects 形态限定（与黑名单同语义，见字段注释）；
 * htFilter 特训限定：0 = 不限（旧数据缺省），1 = 仅特训，2 = 不含特训。
 * (speciesId, vCount, shinyFilter, aspects, htFilter) 组合唯一，重复添加覆盖（upsert = 编辑语义）。
 */
data class PokemonPriceLimitEntry(
    val speciesId: String,
    val vCount: Int,
    val shinyFilter: Int,
    val minPrice: Int?,
    val maxPrice: Int?,
    // 形态限定（与黑名单同语义）：
    //   ["*"]       = 全部形态（旧数据读入时缺省为此值，保持旧的不分形态语义）
    //   []          = 默认形态（精灵不含该物种任何已声明的 form aspect）
    //   [x, y, ...] = 仅匹配包含所有这些 aspect 的精灵
    val aspects: List<String> = listOf(ALL_FORMS),
    // 特训限定（配合 Cobblemon Utility+ 等模组的 hyper training）：
    //   0 = 不限（旧数据读入时缺省为此值，保持旧的不分特训语义）
    //   1 = 仅特训（精灵至少有一项特训值）
    //   2 = 不含特训（精灵无任何特训值）
    val htFilter: Int = HT_ANY
) {
    companion object {
        const val ALL_FORMS = "*" // 与 PokemonBlacklistEntry.ALL_FORMS 同值同语义
        const val SHINY_ANY = -1
        const val SHINY_NO = 0
        const val SHINY_YES = 1
        const val HT_ANY = 0
        const val HT_ONLY = 1
        const val HT_NONE = 2
    }

    fun matches(targetSpeciesId: String, targetVCount: Int, targetShiny: Boolean, targetAspects: Set<String>, formAspectUnion: Set<String>, targetHasHt: Boolean): Boolean {
        if (speciesId.isNotEmpty() && speciesId != targetSpeciesId) return false
        if (vCount >= 0 && vCount != targetVCount) return false
        if (shinyFilter != SHINY_ANY && targetShiny != (shinyFilter == SHINY_YES)) return false
        if (htFilter != HT_ANY && targetHasHt != (htFilter == HT_ONLY)) return false
        if (ALL_FORMS in aspects) return true
        if (aspects.isEmpty()) {
            // 默认形态：精灵不能携带该物种任何 form aspect（shiny 等非 form aspect 不影响）
            return formAspectUnion.none { it in targetAspects }
        }
        // 形态子集匹配：条目限定的 aspect 必须全部出现在精灵上
        return targetAspects.containsAll(aspects)
    }
}

class PokemonPriceLimitState private constructor() : PersistentState() {

    private data class EntryKey(val speciesId: String, val vCount: Int, val shinyFilter: Int, val aspects: List<String>, val htFilter: Int)

    private val entries = mutableMapOf<EntryKey, PokemonPriceLimitEntry>()

    fun add(entry: PokemonPriceLimitEntry) {
        // 先删后插：同物品价格限制一致的语义——已存在 key 的 put 不移动位置，重复添加会留在旧位置
        val key = EntryKey(entry.speciesId, entry.vCount, entry.shinyFilter, entry.aspects, entry.htFilter)
        entries.remove(key)
        entries[key] = entry
        markDirty()
    }

    fun remove(speciesId: String, vCount: Int, shinyFilter: Int, aspects: List<String>, htFilter: Int): Boolean {
        val removed = entries.remove(EntryKey(speciesId, vCount, shinyFilter, aspects, htFilter)) != null
        if (removed) markDirty()
        return removed
    }

    fun getAll(): List<PokemonPriceLimitEntry> = entries.values.toList()

    /** 该精灵所有匹配规则的最严交集：min 取最大、max 取最小；无匹配规则返回 null（不限制）。 */
    fun getPriceBounds(pokemon: Pokemon): PriceBounds? {
        val species = pokemon.species
        val speciesId = species.resourceIdentifier.toString()
        // 该物种所有已声明 form aspect 的并集（standard form + forms），用于"默认形态"判定（照黑名单）
        val formAspectUnion = buildSet {
            addAll(species.standardForm.aspects)
            species.forms.forEach { addAll(it.aspects) }
        }
        // 特训判定：六项特训值（hyper trained，值域 0~31）任一存在即"有特训"
        val targetHasHt = pokemon.ivs.hyperTrainedIVs.values.any { it >= 0 }
        val matched = entries.values.filter {
            it.matches(speciesId, vCountOf(pokemon), pokemon.shiny, pokemon.aspects, formAspectUnion, targetHasHt)
        }
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
            // 总是写入 aspects（空列表也写），读取端用 contains 区分旧格式
            val aspectList = NbtList()
            e.aspects.forEach { aspectList.add(NbtString.of(it)) }
            c.put("aspects", aspectList)
            c.putInt("htFilter", e.htFilter)
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

        /** IV 中有效值恰好等于 31 的项数（0~6）：特训项用特训值，未特训用真实值（特训 6V 也算 6V） */
        fun vCountOf(pokemon: Pokemon): Int {
            val htIvs = pokemon.ivs.hyperTrainedIVs
            return listOf(
                Stats.HP, Stats.ATTACK, Stats.DEFENCE,
                Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED
            ).count { (htIvs[it] ?: pokemon.ivs[it]) == 31 }
        }

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
                                maxPrice = if (c.getBoolean("hasMax")) c.getInt("maxPrice") else null,
                                // 旧格式无 aspects 字段 → ["*"] = 全形态，保持旧的不分形态语义；
                                // 新格式显式空列表 = 默认形态
                                aspects = if (c.contains("aspects"))
                                    c.getList("aspects", NbtList.STRING_TYPE.toInt()).map { it.asString() }
                                else
                                    listOf(PokemonPriceLimitEntry.ALL_FORMS),
                                // 旧格式无 htFilter 字段 → 不限特训，保持旧的不分特训语义
                                htFilter = if (c.contains("htFilter")) c.getInt("htFilter") else PokemonPriceLimitEntry.HT_ANY
                            )
                            entries[EntryKey(entry.speciesId, entry.vCount, entry.shinyFilter, entry.aspects, entry.htFilter)] = entry
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
