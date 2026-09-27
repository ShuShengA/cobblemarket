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
    val shinyFilter: Int = SHINY_ANY,
    // 特训限定（读官方 Pokemon 的 hyperTrainedIVs 字段；设置特训的内容由附属模组提供）：
    //   0 = 不限（旧数据缺省值），1 = 仅特训，2 = 不含特训
    val htFilter: Int = HT_ANY,
    // 变体限定（species feature：花纹 / 饰品 / 帽子…）—— 与上面的 aspects **是两个维度**，
    // 各自独立、**都要满足**才算命中：
    //   []          = 不限变体（旧数据缺省值）
    //   ["*"]       = 全部变体（精灵带**任意**变体就命中）
    //   [x, y, ...] = 命中所勾的**任一个**
    // ⚠ **追加在末尾**并给默认值：旧档反序列化时缺这个字段自动落空表 = 不限，不损坏存档
    val variantAspects: List<String> = emptyList()
) {
    companion object {
        const val ALL_FORMS = "*"

        /**
         * **形态不限** —— 形态条件**不参与判定**（精灵长什么样都行），只按变体封。
         * 给"只封变体"那一档用（形态按钮里与「全部形态 / 默认形态」并列的「不限」）。
         * ⚠ 与 [ALL_FORMS] 值不同：那个是"所有形态都封"，这个是"形态不管"。
         */
        const val ANY_FORMS = "**"

        /**
         * ⚠ **曾经的「全部变体」哨兵**——**已废止**，只在读老数据时用来把它剔掉（见 [readNbt] 那段）。
         * 别再产出它、也别再拿它判定：它的语义是"精灵**带任意变体**才命中"，而多数精灵身上
         * 一个变体都没有 ⇒ 会**漏掉普通个体**（顶部「全部」开关曾用它，于是"整只封"名不副实）。
         * 变体这一维的「不限」= **空表**（见 [matches] 里的注释）。
         */
        const val LEGACY_ALL_VARIANTS = "*"
        const val SHINY_ANY = -1
        const val SHINY_NO = 0
        const val SHINY_YES = 1
        const val HT_ANY = 0
        const val HT_ONLY = 1
        const val HT_NONE = 2
        /** 服务端 aspects 条目上限（一个物种的形态最多几十个，正常客户端远达不到）：
         *  恶意客户端可发海量 aspects 随订单/黑名单持久化并全服广播，撑爆存档与 S2C 包 */
        const val MAX_ASPECTS = 64
    }

    fun matches(targetSpeciesId: String, ivs: IVs, htIvs: Map<com.cobblemon.mod.common.api.pokemon.stats.Stat, Int>, targetAspects: Set<String>, formAspectUnion: Set<String>, targetShiny: Boolean, targetHasHt: Boolean): Boolean {
        if (speciesId != targetSpeciesId) return false
        if (shinyFilter != SHINY_ANY && targetShiny != (shinyFilter == SHINY_YES)) return false
        if (htFilter != HT_ANY && targetHasHt != (htFilter == HT_ONLY)) return false
        // IV 按有效值匹配：特训项用特训值，未特训用真实值（特训 31 与原生 31 都命中「=31」规则）
        if (ivHp >= 0 && (htIvs[Stats.HP] ?: ivs[Stats.HP]) != ivHp) return false
        if (ivAtk >= 0 && (htIvs[Stats.ATTACK] ?: ivs[Stats.ATTACK]) != ivAtk) return false
        if (ivDef >= 0 && (htIvs[Stats.DEFENCE] ?: ivs[Stats.DEFENCE]) != ivDef) return false
        if (ivSpAtk >= 0 && (htIvs[Stats.SPECIAL_ATTACK] ?: ivs[Stats.SPECIAL_ATTACK]) != ivSpAtk) return false
        if (ivSpDef >= 0 && (htIvs[Stats.SPECIAL_DEFENCE] ?: ivs[Stats.SPECIAL_DEFENCE]) != ivSpDef) return false
        if (ivSpd >= 0 && (htIvs[Stats.SPEED] ?: ivs[Stats.SPEED]) != ivSpd) return false
        // ── 形态条件 ──
        // ⚠ **不再提前 return**：形态与变体是**两个独立维度、都要满足** —— 服主可以
        //   "全部形态 + 某个具体变体"，提前返回会把变体那一半吃掉
        val formsMatch = when {
            // 「不限」：只封变体那一档用（形态不参与判定）
            ANY_FORMS in aspects -> true
            ALL_FORMS in aspects -> true
            // 默认形态：精灵不能携带该物种任何 form aspect（shiny 等非 form aspect 不影响）
            aspects.isEmpty() -> formAspectUnion.none { it in targetAspects }
            // 形态子集匹配：条目限定的 aspect 必须全部出现在精灵上
            else -> targetAspects.containsAll(aspects)
        }
        if (!formsMatch) return false
        // ── 变体条件 ──
        // ⚠ **空表 = 不限**（变体这一维不参与判定，恒过）。
        //   界面顶部那个「全部」开关（= 整只精灵一刀切）落的就是**空表**，别写成变体 `*`：
        //   `*` 曾经表示"带任意变体就命中"，而**多数精灵身上一个变体都没有** ⇒ 会把普通个体漏掉，
        //   「全部」也就名不副实了（2026-09-28 用户实测：普通皮卡丘不被「全部」规则拦截）。
        if (variantAspects.isEmpty()) return true
        // 勾了具体几个：命中**任一**即可（黑名单是"封禁"，不是"全都要"）
        return variantAspects.any { it in targetAspects }
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

    /**
     * 命中黑名单的**那一条**规则；没命中返回 null（调用点判 `!= null` 即可）。
     *
     * ⚠ 这里返回**条目本身**（而不是只给 true/false）是为了**把禁掉的理由带回给玩家**
     *   （见 [com.shusheng.cobblemarket.market.RuleReasonText]）：规则加了「变体」维度之后，
     *   只封某个变体的规则也报「该精灵已被黑名单禁止交易」，玩家会以为**整只精灵**都不能卖。
     * ⚠ 多条同时命中时取**第一条**：判定只关心"有没有命中"，理由取哪条都足以说明问题。
     */
    fun findMatch(pokemon: com.cobblemon.mod.common.pokemon.Pokemon): PokemonBlacklistEntry? {
        val species = pokemon.species
        val speciesId = species.resourceIdentifier.toString()
        // 该物种所有已声明 form aspect 的并集（standard form + forms），用于"默认形态"判定
        val formAspectUnion = buildSet {
            addAll(species.standardForm.aspects)
            species.forms.forEach { addAll(it.aspects) }
        }
        // 特训判定：六项特训值（hyper trained，值域 0~31）任一存在即"有特训"
        val targetHasHt = pokemon.ivs.hyperTrainedIVs.values.any { it >= 0 }
        return entries.values.firstOrNull {
            it.matches(
                speciesId, pokemon.ivs, pokemon.ivs.hyperTrainedIVs, pokemon.aspects,
                formAspectUnion, pokemon.shiny, targetHasHt
            )
        }
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
            // 变体同理：**总是写**（空表也写），读取端用 `contains` 区分新旧格式
            // ⚠ 新键（不在旧数据里）⇒ 旧档读不到自动落空表 = 不限变体 ✓ 不需要迁移分支
            val variantList = NbtList()
            e.variantAspects.forEach { variantList.add(net.minecraft.nbt.NbtString.of(it)) }
            c.put("variantAspects", variantList)
            c.putInt("shinyFilter", e.shinyFilter)
            c.putInt("htFilter", e.htFilter)
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
                                shinyFilter = if (c.contains("shinyFilter")) c.getInt("shinyFilter") else PokemonBlacklistEntry.SHINY_ANY,
                                // 旧格式无 htFilter 字段 → 不限特训，保持旧的不分特训语义
                                htFilter = if (c.contains("htFilter")) c.getInt("htFilter") else PokemonBlacklistEntry.HT_ANY,
                                // 旧格式无 variantAspects 键 → 空表 = **不限变体**（正是旧条目的语义：
                                // 那时候压根没有变体这个维度）⇒ 不需要迁移分支，读不到就是对的
                                // ⚠ 顺手**剔掉废止的哨兵**（[LEGACY_ALL_VARIANTS]）：1.3.0 未发布期间
                                //   顶部「全部」开关会把它写在变体表里，而它在界面上既看不见也删不掉 ——
                                //   留着它会把规则偷偷变成"要带变体才命中"。剔掉后按正常条目判（= 其余值生效）
                                variantAspects = if (c.contains("variantAspects"))
                                    c.getList("variantAspects", NbtList.STRING_TYPE.toInt())
                                        .map { it.asString() }
                                        .filter { it != PokemonBlacklistEntry.LEGACY_ALL_VARIANTS }
                                else emptyList()
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
