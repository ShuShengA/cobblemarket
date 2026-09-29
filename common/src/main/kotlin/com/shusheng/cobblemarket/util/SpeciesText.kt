package com.shusheng.cobblemarket.util

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.util.substitute
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * **一个 feature 维度**的候选（RP 商城编辑窗那枚「变体」按钮的列表按它分节）。
 *
 * @param key 注册名（下划线形式，如 `snake_pattern`）—— 客户端拿它当**互斥判据**：
 *   同一 `key` 下的选项互相顶替，不同 `key` 之间可以共存
 * @param default 数据 json 里的 `default`。**这一项决定「不指定」的名字**：
 *   `random` ⇒ 不指定就是随机掷一个（阿柏怪的花纹、晃晃斑的斑纹都是）；落在 [aspects] 里 ⇒
 *   不指定等于固定选它；其它（`none` 之类）⇒ 不指定就是不设置该 feature。
 *   ⚠ 空串 = 数据没写 default，按"其它"处理
 * @param aspects 该维度的全部候选 aspect 串（已滤掉 gmax、已去重）
 */
data class FeatureCandidates(
    val key: String,
    val default: String,
    val aspects: List<String>
)

object SpeciesText {
    fun translationKey(species: Species): String {
        val content = species.translatedName.content
        if (content is net.minecraft.text.TranslatableTextContent) {
            return content.key
        }
        return "${species.resourceIdentifier.namespace}.species.${species.showdownId()}.name"
    }

    fun translated(species: Species): Text = Text.translatable(translationKey(species))

    /**
     * 物种 **ID**（`cobblemon:pikachu`）→ 翻译 key（`cobblemon.species.pikachu.name`）。
     *
     * 交易账本**全项目统一存 key**（市场 / 拍卖 / 求购都是 `extraData["speciesKey"]`，
     * 由本类的 [translationKey] 快照而来），而**商城的配置里存的是物种 ID** ⇒
     * 商城写交易账本前必须转一道。不转会同时坏两件事（2026-09-28 用户实测报的）：
     * ① 界面拿 ID 当 key 翻译不出来，直接显示成 `cobblemon:pikachu`；
     * ② CSV 回读会把 ID 补成 `cobblemon.species.cobblemon:pikachu.name`，与内存里那条对不上
     *   ⇒ 去重键失效，**一条交易显示成两条**。
     */
    fun translationKeyOf(speciesId: String): String {
        val species = Identifier.tryParse(speciesId)?.let { PokemonSpecies.getByIdentifier(it) }
        if (species != null) return translationKey(species)
        // 解析不到（该物种已被移除 / 产出它的附属模组没装）：按 Cobblemon 的命名规则拼一个，
        // 至少比裸 ID 强 —— 界面会显示这条 key 本身，与「翻译缺失」的既有表现一致
        val namespace = speciesId.substringBefore(':', "cobblemon")
        return "$namespace.species.${speciesId.substringAfterLast(':')}.name"
    }

    /**
     * aspect 的显示名：走本模组翻译表（`cobblemarket.aspect.<名>`），未收录的显示原文。
     *
     * ⚠ 只用于**显示**；aspect 本身的匹配与存储一律用原始名（翻译表增删不影响已有数据）。
     * 黑名单的形态按钮与商城窗口的形态选项共用这一份（各写一遍必然漂移）。
     */
    fun aspectLabel(aspect: String): String = aspectText(aspect).string

    /**
     * [aspectLabel] 的 **Text 版** —— 给**服务端拼提示**用（见 [com.shusheng.cobblemarket.market.RuleReasonText]）。
     *
     * ⚠ **服务端拼提示一律用这个，不许用 [aspectLabel]**：`.string` 解析出来的是**服务端语言**
     *   （专用服务器多半是英文），发到中文客户端就成英文了。返回词条本身，客户端按自己的语言渲染。
     * ⚠ 判"词条在不在"只能按**当前语言**判（MC 没有跨语言查表的 API）：模组自己的
     *   `cobblemarket.aspect.*` 中英两份齐平，可靠；Cobblemon 那边的形态词条同理（它也是各语言都发）。
     */
    fun aspectText(aspect: String): Text {
        val key = "cobblemarket.aspect.$aspect"
        return if (Text.translatable(key).string == key) Text.literal(aspect) else Text.translatable(key)
    }

    /** 超极巨化形态的 aspect 名 —— Cobblemon 数据里 gmax 形态的 aspects 就是 `["gmax"]` */
    const val GMAX_ASPECT = "gmax"

    /**
     * **极巨化形态的全部 aspect**（超极巨化 + 无极汰那的 `eternamax`）。
     *
     * 来源：`species_features/dynamax_form.json` 的 `choices` —— 就是 `["gmax", "eternamax"]`。
     * ⚠ 客户端拿不到那份候选本身（choice 型不同步，见 [allFeatureAspects]），只能按值列在这儿；
     *   Cobblemon 将来加了第三种极巨化形态，这里要跟着补。
     *
     * ⚠ **只按 `gmax` 一个值滤是不够的**：无极汰那的 form 写的是 `eternamax`，会漏过去 ——
     *   而它标着 `restricted`、玩家根本拿不到，让人在**黑名单 / 价格限制**里禁它毫无意义
     *   （2026-09-27 用户指出；同一个坑在「变体」那边也踩过一次，那次漏的是候选、这次漏的是 forms）。
     */
    private val DYNAMAX_ASPECTS = setOf(GMAX_ASPECT, "eternamax")

    /**
     * **「正常路径下需要满足某个前置条件」的形态 aspect** —— RP 商城上架精灵时给服主**警告**用。
     *
     * 判据（2026-09-30 用户拍板）：**这个形态正常变身需要道具 / 招式 / 等级 / 特性之类的前置条件**，
     * 而商城造出来的精灵**必然不满足** ⇒ 从诞生就是**非法**精灵：
     * - 形态 ↔ 道具的不变量被打破（Mega 精灵却没带 Mega 石）
     * - ⚠ **省掉一个携带物位** ⇒ 能另带一件道具 ⇒ **影响对战平衡**
     * - `Species.getForm` **不看 `battleOnly`** ⇒ 种族值 / 属性 / 特性 / 可学技能**全部常驻**走那一套
     * - 附属模组认为"它已经是 Mega"是**事实正确**的 ⇒ **无法解除**
     *
     * ⚠⚠ **必须硬编码**：Cobblemon 的 `FormData` 根本没读 species json 的 `battleOnly`
     *   （`PokemonSpecies.kt` 里只有一个 `// ToDo battleOnly`），`labels` 虽被读入但**不同步给客户端**
     *   ⇒ 客户端**没有任何权威判据可用**，只能按 aspect 值列在这儿。见 [selectableForms] 的同类说明。
     *
     * 来源：扫 `Cobblemon-fabric-1.8.0+1.21.1.jar` 里 `data/cobblemon/species` 下各世代的 json（1025 个物种）
     * ⇒ `battleOnly: true` 共 **107 个 form / 94 物种**；减去**已滤掉**的 `gmax` 34 + `eternamax` 1
     * ⇒ 本表 **24 个 aspect 值覆盖 72 个 form**。
     *
     * ⚠ **Cobblemon 升级时要复核本表** —— 官方新增 Mega / 战斗形态时这里**不会自动跟上**。
     *
     * ⚠ 下面这 3 个例外要知道：
     * - **烈空坐的 Mega 靠「画龙点睛」不靠道具**（用户 2026-09-30 指出）—— 判据因此从"需要道具"
     *   放宽成"需要**前置条件**"，它用的仍是 `mega`
     * - `xerneas` 的 `active-mode` ⚠ **待核**：哲尔尼亚斯平时就是 Active Mode（Neutral 是伪装态），
     *   它被标 `battleOnly: true` **可能是数据标注问题**，别误伤
     * - `castform` / `cherrim` / `aegislash` / `xerneas` / `cramorant` / `eiscue` / `morpeko` / `terapagos`
     *   这 **8 只的 forms 里全是 battleOnly**（没有普通形态），形态按钮点开就全是它们
     */
    val GATED_FORM_ASPECTS: Set<String> = setOf(
        // Mega 系（需 Mega 石；⚠ 烈空坐例外，见上）
        "mega", "mega_x", "mega_y",
        // 传说专属（需宝珠 / 腐朽的剑与盾 / 与日月神合体）
        "primal", "crowned", "ultra-fusion",
        // 自动触发型（等级 / 换人下场 / 特性 / 天气 / 受击…）
        "sunny-form", "rainy-form", "snowy-form", "sunshine-form",
        "zen-mode", "blade-forme", "ash", "active-mode", "complete-percent",
        "busted-form", "school-form", "gulping-form", "gorging-form",
        "noice_face", "hangry-mode", "hero-form", "terastal-form", "stellar-form",
    )

    /** 这个形态是不是**需要前置条件**的那种（任一 aspect 命中 [GATED_FORM_ASPECTS]）—— 商城上架时警告用 */
    fun isGatedForm(form: FormData): Boolean = form.aspects.any { it in GATED_FORM_ASPECTS }

    /**
     * 一堆 aspect 里**有没有**需要前置条件的 —— 判"当前草稿选中的形态危不危险"用。
     *
     * ⚠ 传的是 `spec.aspects` 这类**混放**列表（形态 + 变体 + 闪光），这里只按值判，
     *   不会误伤变体 —— 因为变体的值（花纹 / 帽子那些）不在 [GATED_FORM_ASPECTS] 里。
     */
    fun hasGatedAspect(aspects: Collection<String>): Boolean = aspects.any { it in GATED_FORM_ASPECTS }

    /**
     * 该物种**可选的形态**（给各界面的形态按钮做候选）：**滤掉超极巨化形态**。
     *
     * 为什么滤（2026-09-20 用户拍板）：
     * - `gmax` 形态在 Cobblemon 数据里标着 `battleOnly`（**只在战斗中套用**）。超极巨化本该由精灵的
     *   `gmaxFactor` 表达 —— RP 商城编辑窗那枚「超极巨化」按钮走的就是它
     * - 让人在形态里选它，会做出一只**常驻**超极巨化外观、战斗里反而**不能**超极巨化的精灵（正好拧了）
     * - 玩家正常拿不到这种精灵 ⇒ 黑名单 / 价格限制里列它没有意义
     *
     * ⚠ 判据只能用 aspects 里的 `gmax`：Cobblemon 的 `FormData` **没读** species json 的
     *   `battleOnly` 字段（`PokemonSpecies.kt` 里只有一个 `// ToDo battleOnly`），那个标记读不到。
     * ⚠ **只给界面候选用**：服务端那几处 `formAspectUnion`（默认形态判定）仍要走**全量** forms ——
     *   那是匹配逻辑不是候选表，滤了会动到判定语义。
     */
    fun selectableForms(species: Species?): List<FormData> =
        species?.forms.orEmpty().filter { form -> form.aspects.none { it in DYNAMAX_ASPECTS } }

    /**
     * **形态按钮的候选**（RP 商城编辑窗用）= 该物种各个 form 的 aspects，返回 `aspect 串 → 显示名`。
     *
     * ⚠ **形态按钮只管 forms**（2026-09-27 用户拍板）：species feature（花纹 / 帽子 / 颜色…）
     *   **不进这里**——它们是另一套控件的事。把 feature 混进来会同时坏两件事：
     *   ① 极巨化之类的**战斗形态**被带回来（用户实测截图抓到 `eternamax`）；
     *   ② `mega` 这种"全物种共用候选里只对该物种无意义"的死选项也混进来。
     *   ⇒ feature 候选走**独立的按钮**，两者互不影响。
     *
     * ⚠ 显示名走本模组的形态词条（`cobblemarket.aspect.<名>`），未收录回落原文。
     * ⚠ **按 aspect 串去重**：同一个差异可能两种写法都有。
     */
    fun selectableFormAspects(species: Species?): List<Pair<String, String>> {
        if (species == null) return emptyList()
        val out = mutableListOf<Pair<String, String>>()   // aspect 串 → 显示名
        val seen = mutableSetOf<String>()
        selectableForms(species).forEach { form ->
            val aspects = form.aspects
            if (aspects.isEmpty()) return@forEach
            val value = aspects.joinToString(",")
            if (seen.add(value)) {
                out += value to aspects.joinToString("/") { aspectLabel(it) }
            }
        }
        return out
    }

    /**
     * **服务端**：全部物种的 choice 型 feature 候选（`物种资源 id → aspect 串列表`），
     * 供 [selectableFormAspects] 的 `featureAspects` 参数使用（见那里的长注释）。
     *
     * ⚠⚠ **只能在服务端调**：客户端 `resourceFeatures` 是空表（choice 型不同步），
     *   在客户端调必然得到空表 —— 那不是 bug，别去"修"。
     *
     * ⚠ **这里要走 `getResourceFeature()`（只查 json 那份），不能用 `getFeaturesFor()`**：
     *   后者内部走 `getFeature()`，而那个是 **code 优先**（`getCodeFeature(name) ?: getResourceFeature(name)`）。
     *   Cobblemon 给 `snake_pattern` 这类**同时注册了代码版**（`IntSpeciesFeatureProvider`，数值型、
     *   没有候选值）⇒ 拿到手根本没法枚举（2026-09-27 实测：arbok → `IntSpeciesFeatureProvider`、out=0）。
     *   真正带 22 个花纹选项的是 **json 那份**（`species_features/` 里那些 json 的 `"type": "choice"`）。
     *   ⚠ 写这个路径时**别带通配符**：斜杠紧跟星号在 KDoc 里会开启**嵌套块注释**（Kotlin 支持嵌套），
     *   把后面的注释结束符全吞掉，报的是「Unclosed comment」而看不出真正原因（2026-09-27 踩过）。
     *
     * ⚠ 候选**已滤掉 `gmax`**（与 [selectableForms] 同一口径）：`dynamax_form` 这个 feature 的候选
     *   正好就是 `gmax` / `eternamax`，超极巨化该由精灵的 `gmaxFactor` 表达 —— 理由见 [selectableForms]。
     */
    fun allFeatureAspects(): Map<String, List<FeatureCandidates>> {
        val out = LinkedHashMap<String, List<FeatureCandidates>>()
        com.cobblemon.mod.common.api.pokemon.PokemonSpecies.implemented.forEach { species ->
            val groups = featureGroupsFor(species)
            if (groups.isNotEmpty()) out[species.resourceIdentifier.toString()] = groups
        }
        return out
    }

    /**
     * **整类不列候选的 feature**（按注册键，下划线形式）—— 2026-09-27 用户实测后拍板。
     *
     * - `mega_evolution`：Mega 形态**已经由 forms 提供**（妙蛙花 / 耿鬼 / 胡地的 forms 里就是
     *   `mega`，喷火龙 / 超梦是 `mega_x` / `mega_y`）。而这个 feature 的候选是**全 mega 物种共用**
     *   的三个值 —— 列出来的话，喷火龙的列表里会多一个 `mega`（**该物种没有对应外观的死选项**，
     *   选了毫无变化），用户实测当场问"这个 mega 又代表什么"
     * - `dynamax_form`：极巨化/超极巨化形态，由编辑窗那枚**「超极巨化」按钮**（`gmaxFactor`）表达 ——
     *   与 [selectableForms] 滤掉 `gmax` 形态是**同一条规定**。它的候选是 `gmax` + `eternamax`，
     *   只按 aspect 值滤 `gmax` 会漏掉 `eternamax`（2026-09-27 用户截图实测抓到，等于把
     *   当初"极巨化不进形态列表"的规定推翻了一半）⇒ **只能整类跳过，不能按值滤**
     * - `region_bias`：**地区形态的 aspect 就是它** —— 卡拉卡拉 / 皮卡丘那些的形态 json 里写的就是
     *   `"aspects": ["region-bias-alola"]`（labels 标着 `alolan_form`）⇒ 形态按钮里的「阿罗拉」和
     *   变体里的「阿罗拉趋异」**是同一个东西**，两边都能选、配哪边都一样（用户 2026-09-27 发现）。
     *   地区形态归**形态按钮**管，变体这边整类跳过
     */
    private val EXCLUDED_FEATURES = setOf("mega_evolution", "dynamax_form", "region_bias")

    /**
     * 单个物种的 feature 候选，**按 feature 分好组**（[allFeatureAspects] 的逐条实现）。
     *
     * ⚠ 分组是必须的：客户端要"点 mega_y 只换掉 mega_x、不碰花纹"就必须知道**哪些 aspect
     *   属于同一个维度** —— 扁平列表做不到这件事。同时把数据里的 `default` 一并带回去，
     *   界面才能把「不指定」那一项写对名字（`random` → 「随机」）。见 [FeatureCandidates]。
     */
    /**
     * 该物种身上**能枚举候选的** feature provider（choice / weighted_choice 两类）。
     *
     * ⚠ **两处共用**（列候选、把服主选的值落成 feature）—— 别各写一份查找逻辑，
     *   那种"过滤条件改一处漏一处"的坑本项目栽过太多次。
     */
    private fun choiceProvidersFor(
        species: Species
    ): List<com.cobblemon.mod.common.api.pokemon.feature.AbstractChoiceSpeciesFeatureProvider<*>> {
        val names = species.features +
            com.cobblemon.mod.common.api.pokemon.feature.SpeciesFeatureAssignments.getFeatures(species)
        return names.distinct()
            // ⚠ **按注册键整类跳过**（见 [EXCLUDED_FEATURES]）—— 注意物种 json 里可能写成连字符形式
            .filter { it.replace('-', '_') !in EXCLUDED_FEATURES }
            .mapNotNull { raw ->
                // ⚠⚠ **两种写法都要试**：`arbok.json` 里写的是 `"features": ["snake-pattern"]`（**连字符**），
                //   而 `species_features/snake_pattern.json` 注册的键是 `"keys": ["snake_pattern"]`（**下划线**）——
                //   拿原样去查必然落空。两个都试一遍，谁有算谁
                //   （转换只是兜底，不能反过来漏掉本来就叫连字符的）。
                com.cobblemon.mod.common.api.pokemon.feature.SpeciesFeatures.getResourceFeature(raw)
                    ?: com.cobblemon.mod.common.api.pokemon.feature.SpeciesFeatures
                        .getResourceFeature(raw.replace('-', '_'))
            }
            .filterIsInstance<com.cobblemon.mod.common.api.pokemon.feature.AbstractChoiceSpeciesFeatureProvider<*>>()
    }

    /**
     * **把服主选定的变体落成 `pokemon.features` 的值**（服务端造精灵时调）。
     *
     * ⚠⚠ **为什么不能只塞 `forcedAspects`**：aspect 是**从 feature 派生**出来的
     *   （`AbstractChoiceSpeciesFeatureProvider.provide(pokemon)`：feature 值 → aspect），而设 species 时
     *   Cobblemon 已经给每个 feature 掷过默认值了 —— `Pokemon.species` 的 setter 会跑
     *   `getFeaturesFor(species).mapNotNull { it.invoke(this) }`，`default = random` 的维度**当场随机**。
     *   只强制 aspect 的话 feature 那边仍留着随机值 ⇒ `updateAspects()` 得到的集合里
     *   **随机值与服主选的值同时挂着**、互相打架，服主选的未必生效。⇒ 必须改 feature 本身。
     *
     * @param selected 规格里的 aspects（服主在「变体」里选的；**形态的 aspect 混在里面不会命中
     *   任何 provider 的候选**，自然被忽略）
     * @return 落成的 feature 个数（只为排障看日志）
     */
    fun applyFeatureAspects(pokemon: Pokemon, species: Species, selected: Collection<String>): Int {
        if (selected.isEmpty()) return 0
        var applied = 0
        choiceProvidersFor(species).forEach { provider ->
            val choices = when (val c = provider.choices) {
                is List<*> -> c.filterIsInstance<String>()
                is Map<*, *> -> c.keys.filterIsInstance<String>()
                else -> emptyList()
            }
            // 反推：哪个候选值套进 `aspectFormat` 之后，正好是服主选的那个 aspect
            val hit = choices.firstOrNull { choice ->
                provider.aspectFormat.substitute("choice", choice) in selected
            } ?: return@forEach
            val name = provider.keys.firstOrNull() ?: return@forEach
            // 同 `SpeciesFeatureUpdatePacket.set` 的写法：先摘同名的、再加新的
            pokemon.features.removeIf { it.name == name }
            pokemon.features.add(
                com.cobblemon.mod.common.api.pokemon.feature.StringSpeciesFeature(name, hit)
            )
            applied++
        }
        return applied
    }

    /**
     * **变体候选值的显示名**：优先用 Cobblemon 官方的形态词条
     * （`cobblemon.ui.pokedex.info.form.<aspect>` —— 图鉴里显示这个形态叫什么就用它），
     * 没有的把 aspect 末段当物品再查一次（`cosmetic_item-rage_candy_bar` → 愤怒馒头），
     * 都查不到才回落 aspect 原文。
     *
     * ⚠ 官方那套**不全**（实测 795 条里不含花纹 `snake-pattern-*`、饰品 `cosmetic-item-*`、
     *   `mega_x` 这些）⇒ 只能"有就用、没有往下落"。
     * ⚠ 好处不止好看：**语言自动跟着走**，而且 Cobblemon 将来补了词条我们自动受益。
     * ⚠ 物品要试**两个命名空间**：Cobblemon 自己的（`cobblemon:rage_candy_bar`）和
     *   原版的（`minecraft:leather_helmet`、`compass`），只查前者会让后者落空。
     *
     * ⚠ 放在这里（而不是界面里）是因为 **RP 商城和黑名单都要用** —— 各写一份必然漂移。
     */
    /**
     * **变体维度的名字**（分节标题）= feature 注册名的词条（`cobblemarket.feature.<键>`）。
     *
     * ⚠ 未收录就**回落显示键本身**（`snake_pattern`）—— 模组 / 数据包自己加的 feature 我们
     *   不可能预先翻译，显示原文总比空白强。
     * ⚠ 放在这里是因为 **RP 商城 / 黑名单 / 价格限制三处都要用**。
     */
    fun featureGroupLabel(key: String): String {
        val k = "cobblemarket.feature.$key"
        val t = Text.translatable(k).string
        return if (t == k) key else t
    }

    fun featureValueLabel(aspect: String): String = featureValueText(aspect).string

    /**
     * [featureValueLabel] 的 **Text 版** —— 给**服务端拼提示**用（同 [aspectText] 的理由：值必须以
     * 词条下发，客户端按自己的语言渲染）。
     *
     * 三层回落与 [featureValueLabel] 完全一致，**只是每一层都保持是 Text**：
     * ① 官方图鉴形态词条 → ② 末段当物品查（**取 `defaultStack.name` 这个 Text，不取 `.string`**，
     * 物品名本身也是词条，中文客户端照样显示中文）→ ③ aspect 原文（Literal，各语言都一样）。
     */
    fun featureValueText(aspect: String): Text {
        val k = "cobblemon.ui.pokedex.info.form.$aspect"
        if (Text.translatable(k).string != k) return Text.translatable(k)
        val itemName = runCatching {
            val short = aspect.substringAfterLast('-')
            listOf("cobblemon", "minecraft").firstNotNullOfOrNull { ns ->
                Identifier.tryParse("$ns:$short")
                    ?.let { net.minecraft.registry.Registries.ITEM.getOrEmpty(it).orElse(null) }
                    ?.defaultStack?.name
                    ?.takeIf { it.string.isNotEmpty() }
            }
        }.getOrNull()
        return itemName ?: Text.literal(aspect)
    }

    private fun featureGroupsFor(species: Species): List<FeatureCandidates> {
        return choiceProvidersFor(species)
            .mapNotNull { provider ->
                // ⚠ 两种 choice provider 的 `getAllAspects()` 返回类型不同：普通 choice 是 `List<String>`、
                //   **weighted_choice 是 `Map<String, Int>`**（权重表）—— 只接 List 会把后者整类漏掉
                val aspects = when (val all = provider.getAllAspects()) {
                    is Map<*, *> -> all.keys.filterIsInstance<String>()
                    is List<*> -> all.filterIsInstance<String>()
                    else -> emptyList()
                }.filter { it.isNotEmpty() && it !in DYNAMAX_ASPECTS }.distinct()
                // 整组都被滤空（例如只剩 gmax）就不列这个维度 —— 空标题没有意义
                if (aspects.isEmpty()) return@mapNotNull null
                FeatureCandidates(
                    // 分组键取注册名（下划线形式）：界面拿它当标题、客户端拿它判断"哪些项互斥"
                    key = provider.keys.firstOrNull().orEmpty(),
                    // ⚠ `default` 可能是 null（数据没写）⇒ 统一成空串，客户端按"其它"处理（显示「默认」）
                    default = provider.default.orEmpty(),
                    aspects = aspects
                )
            }
            // 同一物种可能两种写法都命中同一个 provider（连字符/下划线），按分组键去重
            .distinctBy { it.key }
    }

    // 客户端显示名：数据包物种未配语言文件时翻译返回 key 原文，fallback 到物种显示名（如 "Foo"）
    fun displayName(species: Species): String {
        val key = translationKey(species)
        val t = Text.translatable(key).string
        return if (t == key) species.name else t
    }

    /**
     * **规则对话框的候选解析**：把玩家输入的物种名/id 解析成候选列表（可能多个）。
     *
     * 分层匹配、**精确优先** —— 照物品侧 `ItemCandidateResolver` 那套已经验证过的做法：
     * ① 资源 id / 英文名精确 → ② 显示名精确 → ③ 显示名包含，保序去重。
     *
     * ⚠ 界面**不要**再自己写 `firstOrNull { == 输入 || contains 输入 }` 取第一个：中文名互为
     * 子串的情形很多（「鬼斯」是「鬼斯通」的前缀），那样会把玩家输入的名字**静默换成别的物种**
     * —— 黑名单 / 价格限制 / 求购单都是在配规则，配错物种玩家极难察觉。
     * 多候选时由界面交给玩家挑（箭头切换 / 列表点选），本函数只回答「有哪些」。
     */
    fun candidatesByNameOrId(input: String): List<Species> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.contains(":")) {
            val byId = Identifier.tryParse(trimmed)
                ?.let { com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getByIdentifier(it) }
            return if (byId != null) listOf(byId) else emptyList()
        }
        val lower = trimmed.lowercase().replace(" ", "_")
        val all: List<Species> = com.cobblemon.mod.common.api.pokemon.PokemonSpecies.implemented
        // ①/② **精确命中就到此为止**：输入「鬼斯」/`mew` 就是那一个，不该再把「鬼斯通」/`Mewtwo`
        //   一起列成候选 —— 玩家打的完整名字本身就是"确定"，多列一个反而要他再点一次箭头
        all.firstOrNull { s -> s.showdownId() == lower || s.name == lower }?.let { return listOf(it) }
        all.firstOrNull { s -> s.translatedName.string == trimmed }?.let { return listOf(it) }
        // ③ 没精确命中（输入的是片段：`char` / 「鬼」）→ 列出全部模糊候选，交给玩家用箭头挑。
        //    ⚠ 必须 ignoreCase：英文物种名首字母大写，小写输入 `char` 用区分大小写的 contains
        //    一条都匹配不到（`"Charmander".contains("char")` 是 false）
        val out = LinkedHashMap<String, Species>()
        all.filter { s -> s.translatedName.string.contains(trimmed, ignoreCase = true) }
            .forEach { out.putIfAbsent(it.resourceIdentifier.toString(), it) }
        return out.values.toList()
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
        // ⚠ 服务端兜底路径：客户端解析失败时才会走到这儿（正常情况传的是 id，上面就返回了）。
        //   **精确优先**：先找显示名完全等于输入的，再退到包含 —— 别让「鬼斯」被「鬼斯通」截胡。
        //   模糊那层要 ignoreCase（英文首字母大写，小写输入匹配不到）
        all.firstOrNull { s -> s.translatedName.string == trimmed }
            ?.let { return it.resourceIdentifier.toString() }
        all.firstOrNull { s ->
            s.translatedName.string.contains(trimmed, ignoreCase = true)
        }?.let { return it.resourceIdentifier.toString() }
        return null
    }
}
