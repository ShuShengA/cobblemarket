package com.shusheng.cobblemarket.util

import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.text.Text
import net.minecraft.util.Identifier

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
     * aspect 的显示名：走本模组翻译表（`cobblemarket.aspect.<名>`），未收录的显示原文。
     *
     * ⚠ 只用于**显示**；aspect 本身的匹配与存储一律用原始名（翻译表增删不影响已有数据）。
     * 黑名单的形态按钮与商城窗口的形态选项共用这一份（各写一遍必然漂移）。
     */
    fun aspectLabel(aspect: String): String {
        val key = "cobblemarket.aspect.$aspect"
        val t = Text.translatable(key).string
        return if (t == key) aspect else t
    }

    /** 超极巨化形态的 aspect 名 —— Cobblemon 数据里 gmax 形态的 aspects 就是 `["gmax"]` */
    const val GMAX_ASPECT = "gmax"

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
        species?.forms.orEmpty().filter { GMAX_ASPECT !in it.aspects }

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
