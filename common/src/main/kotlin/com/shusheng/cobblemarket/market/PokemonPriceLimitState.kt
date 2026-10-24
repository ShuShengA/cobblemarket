package com.shusheng.cobblemarket.market

import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.IVs
import com.cobblemon.mod.common.pokemon.Pokemon
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.currency.MarketCurrency
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtString
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState

// 价格上下限区间：null = 该侧不限制
data class PriceBounds(val min: Int?, val max: Int?) {

    /** 空区间（下限 > 上限）：多条同档限价规则交集为空，该物品被交叉锁死、任何价格都过不了校验 */
    val isEmptyRange: Boolean get() = min != null && max != null && min!! > max!!
}

// 精灵与携带物价格限制合并：下限两侧相加（不限侧按 0 参与）；
// 上限仅当两侧都设限时相加（一侧不限则总上限不限——精灵部分可以定价无限高）。
// 合并后无实际约束（下限 0 且无上限）返回 null。
fun mergePriceBounds(a: PriceBounds?, b: PriceBounds?): PriceBounds? {
    if (a == null) return b
    if (b == null) return a
    // Long 相加 + 封顶：两侧 Int 相加可能溢出回绕成负，导致 price < min / price > max
    // 永不触发、限价静默失效（界面输入单侧限 9 位触发不了，防的是手改配置的极端值）
    val min = ((a.min ?: 0).toLong() + (b.min ?: 0)).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val max = if (a.max != null && b.max != null) {
        (a.max.toLong() + b.max).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else null
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
    // 特训限定（读官方 Pokemon 的 hyperTrainedIVs 字段；设置特训的内容由附属模组提供）：
    //   0 = 不限（旧数据读入时缺省为此值，保持旧的不分特训语义）
    //   1 = 仅特训（精灵至少有一项特训值）
    //   2 = 不含特训（精灵无任何特训值）
    val htFilter: Int = HT_ANY,
    // 变体限定（species feature：花纹 / 饰品 / 帽子…）—— 与黑名单**同一套语义**：
    //   []          = 不限（变体这一维**不参与判定**；顶部「全部」开关落的就是"形态 `*` + 这个空表"）
    //   [x, y, ...] = 命中所勾的任一个
    // ⚠ 这里**没有**"全部变体"哨兵（同黑名单，2026-09-28 废止，见 [LEGACY_ALL_VARIANTS]）
    // ⚠ 追加末尾 + 默认值：旧档读不到自动落空表 = 不限，不损坏存档
    val variantAspects: List<String> = emptyList(),
    /**
     * 这条规则管哪种币种（2026-10-24）：主货币 / RP。**不同币种是各自独立的规则**
     * （同一只精灵可以同时有主货币上下限和 RP 上下限）—— 因此币种也进 key。
     * ⚠ 末尾追加 + 默认主货币：旧档读不到自动落主货币，不损坏存档。
     */
    val currency: MarketCurrency = MarketCurrency.PRIMARY
) {
    companion object {
        const val ALL_FORMS = "*" // 与 PokemonBlacklistEntry.ALL_FORMS 同值同语义
        /** 形态**不限**（形态不参与判定，只按变体限价）—— 与黑名单同值同语义 */
        const val ANY_FORMS = "**"
        /**
         * ⚠ **曾经的「全部变体」哨兵**——**已废止**，只在读老数据时用来把它剔掉（见 [readNbt] 那段）。
         * 理由同 `PokemonBlacklistEntry.LEGACY_ALL_VARIANTS`：它是"要带任意变体才命中"，会漏掉普通个体。
         * 变体这一维的「不限」= **空表**。
         */
        const val LEGACY_ALL_VARIANTS = "*"
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
        // ⚠ **形态与变体是两个独立维度、都要满足** —— 别提前 return，那会把另一半吃掉
        //   （黑名单那边原来就是这么写的，2026-09-27 改掉）
        val formsMatch = when {
            ANY_FORMS in aspects -> true
            ALL_FORMS in aspects -> true
            // 默认形态：精灵不能携带该物种任何 form aspect（shiny 等非 form aspect 不影响）
            aspects.isEmpty() -> formAspectUnion.none { it in targetAspects }
            // 形态子集匹配：条目限定的 aspect 必须全部出现在精灵上
            else -> targetAspects.containsAll(aspects)
        }
        if (!formsMatch) return false
        // ⚠ **空表 = 不限**（变体这一维不参与判定，恒过）——「全部」开关落的就是空表；
        //   别写成变体 `*`：那是"要带任意变体才命中"，会把普通个体漏掉（见 [LEGACY_ALL_VARIANTS]）
        if (variantAspects.isEmpty()) return true
        return variantAspects.any { it in targetAspects }
    }
}

class PokemonPriceLimitState private constructor() : PersistentState() {

    // ⚠ 变体也进 key：它和形态一样是**判据的一部分**，不进的话"同一物种同一形态、只有变体不同"
    //   的两条规则会互相覆盖
    private data class EntryKey(
        val speciesId: String, val vCount: Int, val shinyFilter: Int,
        val aspects: List<String>, val htFilter: Int, val variantAspects: List<String>,
        val currency: MarketCurrency
    )

    private val entries = mutableMapOf<EntryKey, PokemonPriceLimitEntry>()

    /**
     * 条目的 key（= 编辑 / 去重的身份）——**所有构造 key 的地方都走这里**。
     *
     * ⚠ **形态与变体列表要先排序再进 key**：`List` 相等是**顺序敏感**的，而变体是**多选**，
     *   顺序就是玩家的**点选顺序** ⇒ 同一组合、点的顺序不同会被当成两条不同的规则，
     *   "重复添加 = 覆盖"随之失效（列表里留下两条看着一样的）。
     * ⚠ 读档处也要走这个（否则排序过的 key 和存档里原序拼的 key 对不上，同一条规则会存成两份）。
     */
    private fun keyOf(
        speciesId: String, vCount: Int, shinyFilter: Int, aspects: List<String>,
        htFilter: Int, variantAspects: List<String>, currency: MarketCurrency
    ): EntryKey =
        EntryKey(speciesId, vCount, shinyFilter, aspects.sorted(), htFilter, variantAspects.sorted(), currency)

    private fun keyOf(entry: PokemonPriceLimitEntry): EntryKey =
        keyOf(entry.speciesId, entry.vCount, entry.shinyFilter, entry.aspects, entry.htFilter, entry.variantAspects, entry.currency)

    fun add(entry: PokemonPriceLimitEntry) {
        // 先删后插：同物品价格限制一致的语义——已存在 key 的 put 不移动位置，重复添加会留在旧位置
        val key = keyOf(entry)
        entries.remove(key)
        entries[key] = entry
        markDirty()
    }

    fun remove(
        speciesId: String, vCount: Int, shinyFilter: Int, aspects: List<String>,
        htFilter: Int, variantAspects: List<String>, currency: MarketCurrency
    ): Boolean {
        val removed = entries.remove(
            keyOf(speciesId, vCount, shinyFilter, aspects, htFilter, variantAspects, currency)
        ) != null
        if (removed) markDirty()
        return removed
    }

    fun getAll(): List<PokemonPriceLimitEntry> = entries.values.toList()

    /**
     * 命中结果：合并区间 + **决定下限 / 上限的那两条规则**。
     *
     * 后两项是给"把理由带回给玩家"用的（见 [com.shusheng.cobblemarket.market.RuleReasonText]）：
     * 区间是**多条规则取最严交集**算出来的，玩家只看到「最高价 ₽5000」根本不知道是哪条规则卡的 ——
     * 尤其是按**形态 / 变体**限的规则（为别的变体设的上限，凭什么套到我这只）。
     *
     * ⚠ 两项都是**并列取第一条**（`maxByOrNull` / `minByOrNull` 返回第一个极值元素）：两条规则
     *   卡同一个数时，理由显示哪条都足以说明问题。
     * ⚠ 携带物那侧的限价规则**不在**这两项里（它没有形态 / 变体维度）；总价含携带物时提示自会另说。
     */
    data class PriceLimitMatch(
        val bounds: PriceBounds?,
        /** 决定下限的那条（下限取最大）；没有下限约束时为 null */
        val minEntry: PokemonPriceLimitEntry?,
        /** 决定上限的那条（上限取最小）；没有上限约束时为 null */
        val maxEntry: PokemonPriceLimitEntry?
    )

    fun check(pokemon: Pokemon, currency: MarketCurrency): PriceLimitMatch {
        val species = pokemon.species
        val speciesId = species.resourceIdentifier.toString()
        // 该物种所有已声明 form aspect 的并集（standard form + forms），用于"默认形态"判定（照黑名单）
        val formAspectUnion = buildSet {
            addAll(species.standardForm.aspects)
            species.forms.forEach { addAll(it.aspects) }
        }
        // 特训判定：六项特训值（hyper trained，值域 0~31）任一存在即"有特训"
        val targetHasHt = pokemon.ivs.hyperTrainedIVs.values.any { it >= 0 }
        // 只取**挂单币种**的规则：主货币与 RP 是两套量纲，混用只会误伤（2026-10-24）
        val matched = entries.values.filter {
            it.currency == currency && it.matches(
                speciesId, vCountOf(pokemon), pokemon.shiny, pokemon.aspects, formAspectUnion, targetHasHt
            )
        }
        if (matched.isEmpty()) return PriceLimitMatch(null, null, null)
        val withMin = matched.filter { it.minPrice != null }
        val withMax = matched.filter { it.maxPrice != null }
        return PriceLimitMatch(
            bounds = PriceBounds(
                min = matched.mapNotNull { it.minPrice }.maxOrNull(),
                max = matched.mapNotNull { it.maxPrice }.minOrNull()
            ),
            // 并列取第一条：两个 maxBy/minBy 都是"第一个极值元素"的语义
            minEntry = withMin.maxByOrNull { it.minPrice ?: Int.MIN_VALUE },
            maxEntry = withMax.minByOrNull { it.maxPrice ?: Int.MAX_VALUE }
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
            // 变体同理：**总是写**（空表也写）；新键 ⇒ 旧档读不到自动落空表 = 不限变体，无需迁移分支
            val variantList = NbtList()
            e.variantAspects.forEach { variantList.add(NbtString.of(it)) }
            c.put("variantAspects", variantList)
            c.putInt("htFilter", e.htFilter)
            c.putBoolean("hasMin", e.minPrice != null)
            e.minPrice?.let { c.putInt("minPrice", it) }
            c.putBoolean("hasMax", e.maxPrice != null)
            e.maxPrice?.let { c.putInt("maxPrice", it) }
            // 币种（末尾追加）：旧档读不到 → fromStored 落主货币
            c.putString("currency", e.currency.name)
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
                                htFilter = if (c.contains("htFilter")) c.getInt("htFilter") else PokemonPriceLimitEntry.HT_ANY,
                                // 旧格式无 variantAspects 键 → 空表 = **不限变体**（正是旧条目的语义：
                                // 那时候压根没有变体这个维度）⇒ 不需要迁移分支
                                // ⚠ 顺手**剔掉废止的哨兵**（同黑名单）：它在界面上既看不见也删不掉，
                                //   留着会把规则偷偷变成"要带变体才命中"
                                variantAspects = if (c.contains("variantAspects"))
                                    c.getList("variantAspects", NbtList.STRING_TYPE.toInt())
                                        .map { it.asString() }
                                        .filter { it != PokemonPriceLimitEntry.LEGACY_ALL_VARIANTS }
                                else emptyList(),
                                // 币种：旧档无此键 → 主货币（fromStored 容错，不抛异常）
                                currency = MarketCurrency.fromStored(
                                    if (c.contains("currency")) c.getString("currency") else null
                                )
                            )
                            entries[keyOf(entry)] = entry
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
