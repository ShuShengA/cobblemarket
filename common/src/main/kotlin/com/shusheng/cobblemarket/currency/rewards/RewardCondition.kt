package com.shusheng.cobblemarket.currency.rewards

import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species

/**
 * **RP 奖励规则的条件**（数据包 `data/` 下 `cobblemarket/points_rewards/` 里一条规则的判定部分）。
 *
 * ⚠⭐ **词汇照抄黑名单 / 价格限制，但类型独立** —— 三者的语义不同（黑名单"命中即拦"、
 *   限价"命中即限价"、奖励"命中即给钱"），塞进同一个类会让三边字段互相污染。
 *   **共享的是词汇与判定口径，不是类型**（与 `PokemonBlacklistEntry` / `PokemonPriceLimitEntry`
 *   刻意分家）。
 *
 * **所有条件之间是"且"**；同一维度内是"任一"还是"全部"见每个字段的 KDoc
 * （口径逐条与黑名单对齐，别自己发明第二套语义）。
 *
 * ⚠ **全空 = 兜底规则**：不带任何条件 ⇒ 任何精灵都命中（服主用来保证"新装的魔改包也至少有产出"）。
 */
data class RewardCondition(
    /** 物种 id（`命名空间:路径`，如 `cobblemon:mewtwo`）；命中列表里**任意一个**即算 */
    val species: List<String> = emptyList(),
    /** 物种 id 的**命名空间通配**（如 `shadowspecies`）⇒ 该包注册的全部物种都命中 */
    val namespace: String? = null,
    /**
     * **形态**：精灵的 aspects 必须**全部包含**这里列出的（与黑名单同一口径）。
     * 空表 = 形态这一维不参与判定。
     */
    val aspects: List<String> = emptyList(),
    /**
     * **变体**（species feature：花纹 / 帽子 / 颜色…）：命中**任一**即算（与黑名单同一口径）。
     *
     * ⚠ 与 [aspects] 是**两个独立维度、都要满足** —— 别把它们合并成一个集合再判。
     * ⚠ 空表 = 这一维不参与判定。
     */
    val variantAspects: List<String> = emptyList(),
    /**
     * **物种标签**（`species.labels`，数据包里 `"labels": ["legendary"]` 那种）；命中**任一**即算。
     *
     * 这是黑名单 / 限价**没有的**维度，却是"兼容魔改包"的关键：`legendary` / `mythical` / `paradox`
     * 这类分类本体与附属模组都写在标签里 ⇒ 服主不必逐个物种列名字。
     */
    val labels: List<String> = emptyList(),
    /** 闪光：`true` / `false` / `null`（不限），三态与黑名单的 `shinyFilter` 同义 */
    val shiny: Boolean? = null,
    /** 头目（Alpha）：三态，同上 */
    val alpha: Boolean? = null,
    /**
     * 特训（Hyper Training）：`true` = 仅特训过的、`false` = 仅没特训的、`null` = 不限。
     * 与黑名单的 `htFilter`（0 不限 / 1 仅特训 / 2 不含特训）同义，只是写成三态更好读。
     */
    val ht: Boolean? = null,
    /** 六项 IV 里 **31 的个数下限**（0~6；特训项按特训值算，口径同限价的 `vCount`） */
    val minPerfectIvs: Int = 0,
    /** 等级区间 `[min, max]`，两端都可省（null = 不限）；只写一个数 = 只有下限 */
    val levelMin: Int? = null,
    val levelMax: Int? = null,

    // ── 排除条件（`excludeXxx`）──────────────────────────────
    //
    // ⚠⭐ **为什么需要它们**（2026-10-13 用户提出的场景）：
    //   魔改包的变体常常**继承原种的标签** —— 腐化烈空坐同时带 `legendary` 与 `corrupted`，
    //   于是"传说给 2000"那条会**先命中**，服主写的"腐化给 3000"永远走不到（而日志里
    //   只是"那条没被轮到"，看不出原因）。
    //   ⇒ 光靠"把更具体的规则写在前面"能解决，但**很脆**（顺序一调反就悄悄错）。
    //   有了排除字段就能把它**写死**：`{ labels: ["legendary"], excludeLabels: ["corrupted"] }`
    //   = "传说、但**不是**腐化的"。
    //
    // ⚠ 五个字段与上面五个正向字段一一对应、语义也对称（列表同样是"命中任一即排除"）。

    /** 这些物种**一律不命中** */
    val excludeSpecies: List<String> = emptyList(),
    /** 这些命名空间（整个模组包）**一律不命中** */
    val excludeNamespace: List<String> = emptyList(),
    /** 带这些标签的**一律不命中** */
    val excludeLabels: List<String> = emptyList(),
    /** 带这些形态的**一律不命中** */
    val excludeAspects: List<String> = emptyList(),
    /** 带这些变体的**一律不命中** */
    val excludeVariantAspects: List<String> = emptyList()
) {

    /** 不带任何正向条件 ⇒ **兜底规则**（只写排除条件的也算，那正是"除了这几只以外都给"） */
    val isFallback: Boolean
        get() = species.isEmpty() && namespace == null && aspects.isEmpty() && variantAspects.isEmpty() &&
            labels.isEmpty() && shiny == null && alpha == null && ht == null &&
            minPerfectIvs <= 0 && levelMin == null && levelMax == null

    /**
     * 这只精灵满足全部条件吗（**排除条件优先判**：命中任一排除项就直接 false）。
     *
     * ⚠ [formAspectUnion] 由调用方算好传进来（该物种所有已声明 form aspect 的并集）——
     *   一次事件里可能要对同一物种判多条规则，每次重算一遍是白烧 CPU
     *   （奖励是**捕捉时**跑的，刷怪塔面前每毫秒都算数）。
     */
    fun matches(pokemon: Pokemon, formAspectUnion: Set<String>): Boolean {
        val speciesId = pokemon.species.resourceIdentifier.toString()

        // ── 排除条件：命中任一就整个不成立（**必须在正向判定之前**，语义才对） ──
        if (excludeSpecies.isNotEmpty() && speciesId in excludeSpecies) return false
        if (excludeNamespace.isNotEmpty() && speciesId.substringBefore(':') in excludeNamespace) return false
        if (excludeLabels.isNotEmpty() && excludeLabels.any { it in pokemon.species.labels }) return false
        if (excludeAspects.isNotEmpty() && excludeAspects.any { it in pokemon.aspects }) return false
        if (excludeVariantAspects.isNotEmpty() && excludeVariantAspects.any { it in pokemon.aspects }) return false

        if (species.isNotEmpty() && speciesId !in species) return false
        if (namespace != null && !namespaceMatches(speciesId, namespace)) return false

        // ── 形态：必须**全部**包含 ──
        if (aspects.isNotEmpty() && !pokemon.aspects.containsAll(aspects)) return false
        // ── 变体：命中**任一** ──
        if (variantAspects.isNotEmpty() && variantAspects.none { it in pokemon.aspects }) return false
        // ── 标签：命中**任一** ──
        if (labels.isNotEmpty() && labels.none { it in pokemon.species.labels }) return false

        if (shiny != null && pokemon.shiny != shiny) return false
        if (alpha != null && pokemon.isAlpha != alpha) return false

        if (ht != null) {
            // 特训判定口径同黑名单：六项特训值（0~31）任一存在即"有特训"
            val hasHt = pokemon.ivs.hyperTrainedIVs.values.any { it >= 0 }
            if (hasHt != ht) return false
        }

        if (minPerfectIvs > 0 && perfectIvCount(pokemon) < minPerfectIvs) return false

        if (levelMin != null && pokemon.level < levelMin) return false
        if (levelMax != null && pokemon.level > levelMax) return false

        return true
    }

    /**
     * 物种 id 的命名空间是否是 [namespace]。
     *
     * ⚠ 判据是**冒号前那一段**（`shadowspecies:pikachu` 的命名空间是 `shadowspecies`），
     *   不是"整个 id 以它开头" —— 否则 `shadowspecies` 会把 `shadowspecies2:pikachu` 也吃进来。
     * ⚠ 没写命名空间的物种 id（理论上不该有）按 `minecraft` 处理（与 `Identifier` 同口径）。
     */
    private fun namespaceMatches(speciesId: String, namespace: String): Boolean =
        speciesId.substringBefore(':') == namespace

    /**
     * **条件指纹** —— 用来区分"这是不是同一条规则"（去重与递减的进度按它分开记，见
     * `PointsState` 里的奖励进度）。
     *
     * 刻意**不含金额 / 递减档位表**：那两个改了不该重置玩家的进度（改价钱不等于换了一批目标人群）；
     * 只含事件（由 `RewardRule.fingerprint` 拼进来）与下面这些判定字段 ——
     * **改了条件才算换了一条规则**。
     *
     * ⚠ 每一段都写成 `<名字>=<长度>:<值>;`：普通分隔符会被值本身撞上 ——
     *   `["ab", "c"]` 与 `["a", "bc"]` 用逗号拼是同一个串，玩家的进度就会莫名其妙串到一起。
     *   带长度前缀就**不可能歧义**，也不用挑"值里不可能出现的字符"。
     */
    fun fingerprint(): String = buildString {
        appendPart("species", species.joinToString(","))
        appendPart("namespace", namespace ?: "")
        appendPart("aspects", aspects.joinToString(","))
        appendPart("variantAspects", variantAspects.joinToString(","))
        appendPart("labels", labels.joinToString(","))
        appendPart("shiny", shiny?.toString() ?: "")
        appendPart("alpha", alpha?.toString() ?: "")
        appendPart("ht", ht?.toString() ?: "")
        appendPart("minPerfectIvs", minPerfectIvs.toString())
        appendPart("level", "${levelMin ?: ""}..${levelMax ?: ""}")
        // ⚠ 排除条件**也要进指纹**：两条规则只有排除项不同（"传说" vs "传说但排除腐化"）
        //   是**两条不同的规则**，各该有自己的首抓/递减进度
        appendPart("excludeSpecies", excludeSpecies.joinToString(","))
        appendPart("excludeNamespace", excludeNamespace.joinToString(","))
        appendPart("excludeLabels", excludeLabels.joinToString(","))
        appendPart("excludeAspects", excludeAspects.joinToString(","))
        appendPart("excludeVariantAspects", excludeVariantAspects.joinToString(","))
    }

    /** 往指纹里追加一段 `<名字>=<长度>:<值>;`（长度前缀消歧，见 [fingerprint]） */
    private fun StringBuilder.appendPart(name: String, value: String) {
        append(name).append('=').append(value.length).append(':').append(value).append(';')
    }

    companion object {

        /** 该物种所有已声明 form aspect 的并集（"默认形态"判定与变体判定都用得着） */
        fun formAspectUnion(species: Species): Set<String> = buildSet {
            addAll(species.standardForm.aspects)
            species.forms.forEach { addAll(it.aspects) }
        }

        /** 六项 IV 里 31 的个数（**特训项按特训值算** —— 与限价的 `vCount` 同一口径） */
        fun perfectIvCount(pokemon: Pokemon): Int {
            val htIvs = pokemon.ivs.hyperTrainedIVs
            return IV_STATS.count { (htIvs[it] ?: pokemon.ivs[it]) == 31 }
        }

        private val IV_STATS = listOf(
            Stats.HP, Stats.ATTACK, Stats.DEFENCE,
            Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED
        )
    }
}
