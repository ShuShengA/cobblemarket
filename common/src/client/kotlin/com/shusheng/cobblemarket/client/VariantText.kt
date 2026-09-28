package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.market.PokemonBlacklistEntry
import com.shusheng.cobblemarket.util.FeatureCandidates
import com.shusheng.cobblemarket.util.SpeciesText
import net.minecraft.text.Text

/**
 * **变体值的显示**（黑名单 / 价格限制两个界面共用）：行内那串 + 悬停窗那几行。
 *
 * 为什么要有这个共用件：这两处的 `variantTextOf` / `variantLinesOf` 原本是**逐字拷贝**
 * （连注释都一样），改一处漏一处。而变体值有三条容易踩的规则，必须只写一遍：
 *
 * 1. ⚠ **悬停窗绝不横着排**：一个维度勾了好几个值时，`值/值/值…` 那一行会横跨整个屏幕
 *    （2026-09-28 用户两次报过）⇒ **每个值单独一行**，同一维度多值时维度名逐行重复。
 *    ⚠ RP 商城那枚按钮能用 `/` 拼，是因为它**组内单选**；黑名单 / 价格限制是**多选**，照抄必横出去。
 * 2. ⚠ **悬停窗每个维度一行**（照 RP 商城：`%s：%s`，没限制的维度写「不限」）——
 *    「光一个『不限』不知道是什么不限」这个问题在变体这边也就不存在了。
 * 3. ⚠ **候选表没到要退化**：维度名只能从候选表查（[PointMarketCache.featureAspects]），
 *    没拉过就没有 ⇒ 退回只显示值，**绝不能让整段消失**（2026-09-28 用户报过）。
 */
object VariantText {

    /** 「不限」：该维度这条规则没限制 */
    private fun anyLabel(): String = Text.translatable("cobblemarket.blacklist.variant_any").string

    /**
     * **行内**那串值（外面还套 `variant_label` 的「变体：%s」）。
     *
     * @return 空表 → null（= 不限，整段不显示）；否则 `值/值`
     */
    fun rowValues(variantAspects: List<String>): String? =
        variantAspects.takeIf { it.isNotEmpty() }
            ?.joinToString("/") { SpeciesText.featureValueLabel(it) }

    /**
     * **悬停窗**的行。
     *
     * - 有候选表 → **该物种每个维度一行**「维度名：值 ／ 不限」
     * - 表没到 → 退回**逐值**（没有维度名，但总比整段不见强）
     *
     * @param groups 该行**自己那个物种**的候选（调用方按 `entry.speciesId` 查，别用对话框里那只）
     */
    fun tooltipLines(variantAspects: List<String>, groups: List<FeatureCandidates>): List<String> {
        if (variantAspects.isEmpty()) return emptyList()
        if (groups.isEmpty()) return variantAspects.map { SpeciesText.featureValueLabel(it) }
        // ⚠⚠ **每个值单独一行**：悬停窗**绝不许横着排** —— 一个维度勾了好几个（皮卡丘的饰品能勾 6 个、
        //   晃晃斑 5 处斑点）时，`值/值/值…` 那一行会横跨整个屏幕（2026-09-28 用户两次报过）。
        //   ⇒ 同一维度有多个值时**维度名逐行重复**，换的是可读性与不溢出（多出几行可接受）。
        //   ⚠ RP 商城那枚按钮的悬停窗能用 `/` 拼，是因为它**组内单选**（一个维度只有一个值）——
        //     黑名单 / 价格限制是**多选**，照抄它就会横出去。
        val lines = mutableListOf<String>()
        val shown = mutableSetOf<String>()
        groups.forEach { g ->
            val picked = g.aspects.filter { it in variantAspects }
            if (picked.isEmpty()) {
                lines += line(g.key, anyLabel())
            } else {
                picked.forEach { a ->
                    lines += line(g.key, SpeciesText.featureValueLabel(a))
                    shown += a
                }
            }
        }
        // ⚠ **候选表里查不到的值也要列**：行内（rowValues）是照列不误的，悬停窗漏掉就两边不一致
        //   （正常配不出来 —— 列表只列候选；但"换物种时表还没到就提交"会留下这种值）
        variantAspects.filter { it !in shown }.forEach { lines += SpeciesText.featureValueLabel(it) }
        return lines
    }

    /** 悬停窗的一行：`维度名：值` */
    private fun line(groupKey: String, value: String): String =
        Text.translatable("cobblemarket.rule.feature_line", SpeciesText.featureGroupLabel(groupKey), value).string
}
