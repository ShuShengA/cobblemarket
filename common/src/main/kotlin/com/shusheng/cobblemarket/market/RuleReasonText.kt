package com.shusheng.cobblemarket.market

import com.shusheng.cobblemarket.util.SpeciesText
import net.minecraft.text.MutableText
import net.minecraft.text.Text

/**
 * **「这条规则为什么拦我」** —— 把命中的规则条件拼成一段说明，附在拦截提示后面。
 *
 * 起因（用户 2026-09-28）：黑名单加了「变体」维度之后，只封某个变体的规则也提示
 * 「该精灵已被黑名单禁止交易」，玩家会以为**整只精灵**都不能卖 ⇒ 必须把命中规则的条件
 * 说出来，例如 `该精灵已被黑名单禁止交易（形态：不限 · 变体：愤怒馒头）`。
 *
 * **列哪些维度**（用户 2026-09-28 拍板）：
 * - **形态与变体成对**：这两维里只要有一维真的限定了范围，**两维都列**（连同另一维的「不限」）——
 *   「形态：不限」这句话本身就是在告诉玩家"不是形态拦你、是变体"
 * - **其余维度各自独立**：闪光 / 特训 / IV / V 档，命中（不是「不限」）才追加
 * - **什么条件都没有**（整只精灵全封）：退回不带括号的原句
 *
 * ⚠ **一律拼 [Text]（词条），不许用 `.string` 取名字**：这几处拦截判定都在**服务端**，而
 *   `.string` 解析出来的是**服务端语言**（专用服务器多半是英文）—— 发到中文客户端就成英文了。
 *   拼词条下发，客户端按自己的语言渲染（[MarketResultPayload] 走 `TextCodecs.PACKET_CODEC`，
 *   嵌套词条与参数都完好）。取值入口见 [SpeciesText.aspectText] / [SpeciesText.featureValueText]。
 * ⚠ 这里只管**文字**，不改判定：命中与否仍由两个 State 的 `matches` 说了算。
 */
object RuleReasonText {

    /** 维度之间的分隔（中英通用，不进语言文件） */
    private const val SEP = " · "

    /** 同一个维度里多个值的连接（与列表行的写法一致，见 BlacklistScreen 的形态 / 变体行） */
    private const val VALUE_SEP = "/"

    /** 黑名单拦截提示：命中规则无条件可列时，就是不带括号的原句 */
    fun blacklistBlocked(entry: PokemonBlacklistEntry): Text =
        withReason(Text.translatable("cobblemarket.blacklist.blocked"), blacklistReason(entry))

    /**
     * 价格限制拦截提示：把**决定这一侧（下限 / 上限）**的那条规则附到已有提示后面。
     *
     * ⚠ [base] 由各调用点传进来 —— 市场 / 拍卖 / 求购单三处的原句各不相同（金额单位、
     *   是否含携带物），这里只负责补理由。
     */
    fun priceLimit(base: MutableText, entry: PokemonPriceLimitEntry?): MutableText =
        withReason(base, entry?.let { priceLimitReason(it) })

    /**
     * 把理由附在已有提示后面；理由为空（规则没限定任何条件）时**原样返回**。
     *
     * ⚠ 父节点用 `Text.literal("")` 而不是把 [base] 当父：带色的段落当父会让 [reason]
     *   跟着继承颜色（客户端那边给整句套的红字仍会传到每个子段，这里**不设色**即可）。
     */
    fun withReason(base: MutableText, reason: Text?): MutableText =
        if (reason == null) base else Text.literal("").append(base).append(reason)

    /** 黑名单命中条目的理由（整只精灵全封 → null） */
    fun blacklistReason(entry: PokemonBlacklistEntry): Text? =
        reason(entry.aspects, entry.variantAspects, entry.shinyFilter, entry.htFilter, ivParts(entry))

    /** 价格限制命中条目的理由（没限定任何条件 → null） */
    fun priceLimitReason(entry: PokemonPriceLimitEntry): Text? =
        reason(entry.aspects, entry.variantAspects, entry.shinyFilter, entry.htFilter, vCountParts(entry))

    /**
     * 共用的拼装：形态 / 变体成对 + 闪光 / 特训 + [extra]（黑名单的 IV、价格限制的 V 档）。
     *
     * ⚠ 两侧的哨兵常量**同值同义**（`PokemonPriceLimitEntry` 的字段注释里写着"与黑名单同语义"），
     *   这里统一按 [PokemonBlacklistEntry] 那份取，避免同一个数字两处判。
     */
    private fun reason(
        formAspects: List<String>,
        variantAspects: List<String>,
        shinyFilter: Int,
        htFilter: Int,
        extra: List<Text>
    ): Text? {
        val parts = mutableListOf<Text>()
        // 形态与变体**成对**出现：只要有一维真的缩了范围，两维都列（含另一维的「不限」）
        if (narrowsForms(formAspects) || variantAspects.isNotEmpty()) {
            parts += formPart(formAspects)
            parts += variantPart(variantAspects)
        }
        if (shinyFilter != PokemonBlacklistEntry.SHINY_ANY) parts += shinyPart(shinyFilter)
        if (htFilter != PokemonBlacklistEntry.HT_ANY) parts += htPart(htFilter)
        parts += extra
        if (parts.isEmpty()) return null
        return Text.translatable("cobblemarket.rule.reason", join(parts, SEP))
    }

    /**
     * 形态这一维**是否真的缩小了范围** ⇒ 决定要不要把「形态 + 变体」这对列出来。
     *
     * ⚠ **空表也算缩小**（它是「默认形态」，只封默认形态那一档）：别用 `aspects.isNotEmpty()`
     *   当下界 —— 那样「默认形态」的规则会变成一句理由都没有的「禁止交易」，玩家更懵。
     *   「全部」（`*`）与「不限」（`**`）才是真的没缩范围，两者都排除。
     */
    private fun narrowsForms(aspects: List<String>): Boolean =
        PokemonBlacklistEntry.ALL_FORMS !in aspects && PokemonBlacklistEntry.ANY_FORMS !in aspects

    /** `形态：<值>`（「：」在词条里，中英各写各的） */
    private fun formPart(aspects: List<String>): Text =
        Text.translatable("cobblemarket.blacklist.form").append(
            when {
                PokemonBlacklistEntry.ANY_FORMS in aspects ->
                    Text.translatable("cobblemarket.blacklist.form_any")
                PokemonBlacklistEntry.ALL_FORMS in aspects ->
                    Text.translatable("cobblemarket.blacklist.form_all")
                aspects.isEmpty() -> Text.translatable("cobblemarket.blacklist.form_default")
                else -> join(aspects.map { SpeciesText.aspectText(it) }, VALUE_SEP)
            }
        )

    /**
     * `变体：<值>`；「不限」用**整句词条**（`variant` 本身就是「变体：不限」），
     * 其余走 `variant_label` 的 `%s`。
     */
    private fun variantPart(aspects: List<String>): Text = when {
        aspects.isEmpty() -> Text.translatable("cobblemarket.blacklist.variant")
        else -> Text.translatable(
            "cobblemarket.blacklist.variant_label",
            join(aspects.map { SpeciesText.featureValueText(it) }, VALUE_SEP)
        )
    }

    /**
     * `闪光：<值>` —— 「闪光」那档用 `rule.shiny_only`（「仅闪光」）而不是通用的
     * `gui.shiny_yes`（「闪光」）：后者拼出来是「闪光：闪光」，等于没说。
     */
    private fun shinyPart(filter: Int): Text =
        Text.translatable("cobblemarket.blacklist.shiny").append(
            if (filter == PokemonBlacklistEntry.SHINY_YES) Text.translatable("cobblemarket.rule.shiny_only")
            else Text.translatable("cobblemarket.gui.shiny_no")
        )

    /**
     * 特训：`仅特训` / `仅原生` —— 与**规则界面**同一对词条（`rule.*`），玩家在提示里看到的
     * 说法和服主配规则时看到的一致。⚠ `仅特训` 那一档规则界面**不给选**（只有两档），
     * 但数据格式支持，留着以防手改配置。
     */
    private fun htPart(filter: Int): Text =
        if (filter == PokemonBlacklistEntry.HT_ONLY) Text.translatable("cobblemarket.gui.filter_ht_on")
        else Text.translatable("cobblemarket.rule.ht_none")

    /** 黑名单专有：被限定的 IV 项（`HP=31`）。特训值不参与 —— 判定用的是特训后的有效值 */
    private fun ivParts(entry: PokemonBlacklistEntry): List<Text> {
        val out = mutableListOf<Text>()
        fun add(value: Int, statKey: String) {
            // 负值 = 该属性不限（与 entry.matches 的判据一致）
            if (value >= 0) out += Text.translatable("cobblemarket.rule.iv", Text.translatable(statKey), value)
        }
        add(entry.ivHp, "cobblemon.stat.hp.name")
        add(entry.ivAtk, "cobblemon.stat.attack.name")
        add(entry.ivDef, "cobblemon.stat.defence.name")
        add(entry.ivSpAtk, "cobblemon.stat.special_attack.name")
        add(entry.ivSpDef, "cobblemon.stat.special_defence.name")
        add(entry.ivSpd, "cobblemon.stat.speed.name")
        return out
    }

    /** 价格限制专有：V 档（`V 数：3V`，与价格限制界面的写法一致） */
    private fun vCountParts(entry: PokemonPriceLimitEntry): List<Text> =
        if (entry.vCount >= 0)
            listOf(Text.translatable("cobblemarket.price_limit.v_label").append("${entry.vCount}V"))
        else emptyList()

    /** 逐段拼接（父节点不设色，各段自己的样式原样保留） */
    private fun join(parts: List<Text>, sep: String): Text =
        Text.literal("").apply {
            parts.forEachIndexed { i, t ->
                if (i > 0) append(sep)
                append(t)
            }
        }
}
