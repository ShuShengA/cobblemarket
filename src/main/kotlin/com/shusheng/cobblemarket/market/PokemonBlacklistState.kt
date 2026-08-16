package com.shusheng.cobblemarket.market

import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.IVs
import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

data class PokemonBlacklistEntry(
    val id: UUID,
    val speciesId: String,
    val ivHp: Int,
    val ivAtk: Int,
    val ivDef: Int,
    val ivSpAtk: Int,
    val ivSpDef: Int,
    val ivSpd: Int,
    // 形态限定：
    //   ["*"]       = 全部形态（显式全封；旧数据读入时缺省为此值，保持旧语义）
    //   []          = 默认形态（精灵不含该物种任何已声明的 form aspect）
    //   [x, y, ...] = 仅匹配包含所有这些 aspect 的精灵
    val aspects: List<String> = emptyList(),
    // 闪光限定：-1 = 不限（旧数据缺省值），0 = 仅非闪光，1 = 仅闪光
    val shinyFilter: Int = SHINY_ANY
) {
    companion object {
        const val ALL_FORMS = "*"
        const val SHINY_ANY = -1
        const val SHINY_NO = 0
        const val SHINY_YES = 1
    }

    fun matches(targetSpeciesId: String, ivs: IVs, targetAspects: Set<String>, formAspectUnion: Set<String>, targetShiny: Boolean): Boolean {
        if (speciesId != targetSpeciesId) return false
        if (shinyFilter != SHINY_ANY && targetShiny != (shinyFilter == SHINY_YES)) return false
        if (ivHp >= 0 && ivs[Stats.HP] != ivHp) return false
        if (ivAtk >= 0 && ivs[Stats.ATTACK] != ivAtk) return false
        if (ivDef >= 0 && ivs[Stats.DEFENCE] != ivDef) return false
        if (ivSpAtk >= 0 && ivs[Stats.SPECIAL_ATTACK] != ivSpAtk) return false
        if (ivSpDef >= 0 && ivs[Stats.SPECIAL_DEFENCE] != ivSpDef) return false
        if (ivSpd >= 0 && ivs[Stats.SPEED] != ivSpd) return false
        if (ALL_FORMS in aspects) return true
        if (aspects.isEmpty()) {
            // 默认形态：精灵不能携带该物种任何 form aspect（shiny 等非 form aspect 不影响）
            return formAspectUnion.none { it in targetAspects }
        }
        // 形态子集匹配：条目限定的 aspect 必须全部出现在精灵上
        return targetAspects.containsAll(aspects)
    }
}

class PokemonBlacklistState private constructor() : PersistentState() {

    private val entries = mutableMapOf<UUID, PokemonBlacklistEntry>()

    fun add(entry: PokemonBlacklistEntry) {
        entries[entry.id] = entry
        markDirty()
    }

    fun remove(id: UUID): Boolean {
        val removed = entries.remove(id) != null
        if (removed) markDirty()
        return removed
    }

    fun getAll(): List<PokemonBlacklistEntry> = entries.values.toList()

    fun isBlacklisted(pokemon: com.cobblemon.mod.common.pokemon.Pokemon): Boolean {
        val species = pokemon.species
        val speciesId = species.resourceIdentifier.toString()
        // 该物种所有已声明 form aspect 的并集（standard form + forms），用于"默认形态"判定
        val formAspectUnion = buildSet {
            addAll(species.standardForm.aspects)
            species.forms.forEach { addAll(it.aspects) }
        }
        return entries.values.any { it.matches(speciesId, pokemon.ivs, pokemon.aspects, formAspectUnion, pokemon.shiny) }
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        entries.values.forEach { e ->
            val c = NbtCompound()
            c.putUuid("id", e.id)
            c.putString("speciesId", e.speciesId)
            c.putInt("ivHp", e.ivHp)
            c.putInt("ivAtk", e.ivAtk)
            c.putInt("ivDef", e.ivDef)
            c.putInt("ivSpAtk", e.ivSpAtk)
            c.putInt("ivSpDef", e.ivSpDef)
            c.putInt("ivSpd", e.ivSpd)
            // 总是写入 aspects（空列表也写），读取端用 contains 区分旧格式
            val aspectList = NbtList()
            e.aspects.forEach { aspectList.add(net.minecraft.nbt.NbtString.of(it)) }
            c.put("aspects", aspectList)
            c.putInt("shinyFilter", e.shinyFilter)
            list.add(c)
        }
        nbt.put("entries", list)
        return nbt
    }

    companion object {
        private val TYPE = PersistentState.Type(
            { PokemonBlacklistState() },
            { nbt, _ ->
                PokemonBlacklistState().apply {
                    nbt.getList("entries", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            val entry = PokemonBlacklistEntry(
                                id = c.getUuid("id"),
                                speciesId = c.getString("speciesId"),
                                ivHp = c.getInt("ivHp"),
                                ivAtk = c.getInt("ivAtk"),
                                ivDef = c.getInt("ivDef"),
                                ivSpAtk = c.getInt("ivSpAtk"),
                                ivSpDef = c.getInt("ivSpDef"),
                                ivSpd = c.getInt("ivSpd"),
                                // 旧格式无 aspects 字段 → ["*"] = 全形态，保持旧的全封语义；
                                // 新格式显式空列表 = 默认形态
                                aspects = if (c.contains("aspects"))
                                    c.getList("aspects", NbtList.STRING_TYPE.toInt()).map { it.asString() }
                                else
                                    listOf(PokemonBlacklistEntry.ALL_FORMS),
                                // 旧格式无 shinyFilter 字段 → 不限闪光，保持旧语义
                                shinyFilter = if (c.contains("shinyFilter")) c.getInt("shinyFilter") else PokemonBlacklistEntry.SHINY_ANY
                            )
                            entries[entry.id] = entry
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted pokemon blacklist entry: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): PokemonBlacklistState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_pokemon_blacklist")
    }
}
