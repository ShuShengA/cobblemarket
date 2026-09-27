package com.shusheng.cobblemarket.client

import com.cobblemon.mod.common.client.render.models.blockbench.repository.VaryingModelRepository
import com.cobblemon.mod.common.pokemon.Species
import com.shusheng.cobblemarket.util.FeatureCandidates
import com.shusheng.cobblemarket.util.SpeciesText

/**
 * 「变体」候选的**可用性过滤** —— RP 商城 / 黑名单 / 价格限制**三处共用**。
 *
 * ## 为什么要过滤（两道，缺一不可）
 *
 * 服务端发下来的候选是该 feature 的**全部** choices，而"能列出来"和"选了有用"是两回事：
 *
 * 1. **已在「形态」里的不列** —— Cobblemon 里 form 常常**就是** feature 的一个取值
 *    （卡拉卡拉的阿罗拉形态 aspects 就是 `region-bias-alola`）。不去重的话同一个东西在
 *    形态按钮和变体按钮各出现一次，配了一边、另一边没配，还以为是 bug。
 * 2. **按模型表过滤** —— 只有该物种**模型里真有**的 aspect 选了才看得到变化。候选里混着大量
 *    空选项（森林蜥蜴的 `cosmetic_item` 26 个里只有 `stick` 有外观）。
 *
 * ## 两个坑
 *
 * - ⚠ **模型表用 Cobblemon 解析好的** `VaryingModelRepository.variations`，**别自己扫**
 *   `bedrock/pokemon/resolvers/` —— 它扫那目录时不分命名空间，模组 / 数据包放在自己命名空间下的
 *   皮肤也在里面；自己按物种目录名匹配只会匹配到内置那批，模组的会被当成"没实现"误滤掉。
 * - ⚠ **读不到就不过滤**（返回 null 时）：模组加的物种可能压根没有 bedrock 模型，
 *   这时候过滤会把整张列表清空 —— 宁可多列几个空选项。
 */
object VariantCandidates {

    /** 模型变体缓存：物种 id → 该物种模型里真有的 aspect 集合（`null` = 没算出来，见上） */
    private val modelCache = mutableMapOf<String, Set<String>?>()

    /**
     * 该物种**可以列出来**的变体 aspect（扁平的，按传入的维度顺序）。
     *
     * @param species 当前这只精灵的物种；null ⇒ 原样返回（没物种就没法过滤）
     * @param groups 服务端下发的该物种候选（[PointMarketCache.featureAspects] 里那一条）
     */
    fun aspectList(species: Species?, groups: List<FeatureCandidates>): List<String> =
        filteredGroups(species, groups).flatMap { it.aspects }

    /**
     * 同 [aspectList]，但**保持按维度分组** —— 列表要按节排（标题 + 该节的候选）。
     * ⚠ 整节被滤空的不返回（空标题只是噪音）。
     */
    fun filteredGroups(species: Species?, groups: List<FeatureCandidates>): List<FeatureCandidates> {
        if (species == null) return groups.filter { it.aspects.isNotEmpty() }
        val formAspects = SpeciesText.selectableForms(species).flatMap { it.aspects }.toSet()
        val modeled = modelAspectsOf(species)
        return groups.mapNotNull { g ->
            val visible = g.aspects.filter { it !in formAspects && (modeled == null || it in modeled) }
            if (visible.isEmpty()) null else g.copy(aspects = visible)
        }
    }

    /**
     * 该物种**模型里真有**的 aspect 集合。
     *
     * ⚠ 返回 null = "没算出来"（读不到 / 该物种压根没有模型变体）⇒ 调用方**不能过滤**。
     * ⚠ 按需算 + 缓存：只在打开变体列表时算一次，不必扫上千个物种。
     */
    private fun modelAspectsOf(species: Species): Set<String>? {
        val id = species.resourceIdentifier
        val key = id.toString()
        if (modelCache.containsKey(key)) return modelCache[key]
        val result = runCatching {
            VaryingModelRepository.variations[id]
                ?.variations
                ?.flatMap { it.aspects }
                ?.toSet()
                // 空集当成"没算出来"：只用默认外观的物种会返回空集，那时过滤会把候选全清掉
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        modelCache[key] = result
        return result
    }
}
