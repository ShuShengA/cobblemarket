package com.shusheng.cobblemarket.currency.rewards

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.resource.ResourceManager
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Identifier

/**
 * **数据包事件点** —— 奖励规则可以挂在哪件事上（json 里的 `"event"` 字段）。
 *
 * ⚠ 每个枚举名 = json 里的**小写写法**；[fromJson] 认非法值返回 `null`（跳过该条、不整体抛）。
 */
enum class RewardEvent(val key: String) {
    CAPTURE("capture"),
    HATCH("hatch"),
    BATTLE_VICTORY("battle_victory"),
    FOSSIL_REVIVED("fossil_revived"),
    EVOLUTION("evolution");

    /** 账本与日志里显示的事件名（＝ json 写法，服务端日志一律英文） */
    override fun toString(): String = key

    companion object {
        fun fromJson(raw: String?): RewardEvent? =
            entries.firstOrNull { it.key.equals(raw, ignoreCase = true) }
    }
}

/**
 * **重复抓怎么算**（每条规则可选，缺省取文件默认；**文件默认 = [ONCE]**）。
 *
 * ⭐ 三档的差别（官网"重复抓怎么算"那一节的依据）：
 * - [ONCE]：这条规则**对这个物种只发第一次**（默认，防刷）
 * - [ALWAYS]：每次都发 —— 服主**显式**配置才生效（刷怪塔面前 = 无限产币，官网必须警告）
 * - [DECAY]：按 [RewardRule.decay] 档位表递减（第 n 次发第 n 档，超出沿用末档）
 *
 * ⚠ [ONCE] 与 [DECAY] **共用同一份计数键**（玩家 + 物种 + 规则条件指纹）—— 见 `PointsState` 里的奖励进度。
 */
enum class RewardRepeat(val key: String) {
    ONCE("once"),
    ALWAYS("always"),
    DECAY("decay");

    override fun toString(): String = key

    companion object {
        fun fromJson(raw: String?): RewardRepeat? =
            entries.firstOrNull { it.key.equals(raw, ignoreCase = true) }
    }
}

/**
 * **战斗胜利规则匹配谁**（只有 `battle_victory` 用）。
 *
 * - [SELF]（默认）：条件匹配**你方上过场的精灵** —— "用烈空坐赢一场 → 2000"
 * - [OPPONENT]：条件匹配**被打倒的对手** —— "击败一只烈空坐 → 2000"
 *   （NPC 的整队里只算真的倒下的；没上场 / 没倒下不算）
 *
 * ⚠ PvP 已在事件层**全局排除**；用球捕捉走 `capture`、不算战斗胜利。
 */
enum class RewardTarget(val key: String) {
    SELF("self"),
    OPPONENT("opponent"),
    /** 按**被击败的 NPC 身份**匹配（整场战斗判一次，不看对方带什么精灵） */
    NPC("npc");

    override fun toString(): String = key

    companion object {
        fun fromJson(raw: String?): RewardTarget? =
            entries.firstOrNull { it.key.equals(raw, ignoreCase = true) }
    }
}

/**
 * **战斗类型过滤**（只有 `battle_victory` 用）。
 *
 * - [ANY]（默认）：野生 + NPC（PvP 已全局排除）
 * - [WILD]：只算野生对战
 * - [NPC]：只算 NPC 对战
 */
enum class RewardBattleType(val key: String) {
    ANY("any"),
    WILD("wild"),
    NPC("npc");

    override fun toString(): String = key

    /** 这场战斗是否属于本档；非战斗事件传 null ⇒ 只有 [ANY] 通过 */
    fun matches(battle: PokemonBattle?): Boolean = when (this) {
        ANY -> true
        WILD -> battle?.isPvW == true
        NPC -> battle?.isPvN == true
    }

    companion object {
        fun fromJson(raw: String?): RewardBattleType? =
            entries.firstOrNull { it.key.equals(raw, ignoreCase = true) }
    }
}

/**
 * 一条奖励规则（解析完的形态）。
 *
 * @param amount 固定金额（每发一次给这么多）
 * @param amountMin / @param amountMax 区间金额（随机取，写入账本的是**实际值**）；
 *   [amount] 与它俩**只会有一组**非空（解析时归一）
 * @param decay 递减档位表（仅 [RewardRepeat.DECAY] 用）；⚠ 与金额**互斥**，同时写 ⇒ 跳过该条 + warn
 * @param dailyCap 这一条规则**每名玩家每天**的累计上限（RP）；null = 不限
 */
data class RewardRule(
    val event: RewardEvent,
    /** 规则来自哪个文件（账本与调试日志用；就是数据包里的相对路径） */
    val sourceFile: String,
    /** 这条规则在文件里是第几条（0 起；日志里显示时会 +1 成"第 N 条"） */
    val indexInFile: Int,
    val repeat: RewardRepeat,
    val condition: RewardCondition,
    val amount: Long?,
    val amountMin: Long?,
    val amountMax: Long?,
    val decay: List<Long>?,
    val dailyCap: Long?,
    /** 战斗胜利：条件匹配谁（默认自己参战的精灵；非 battle 事件恒为默认值） */
    val target: RewardTarget = RewardTarget.SELF,
    /** 战斗胜利：只算哪种战斗（默认野生 + NPC；非 battle 事件恒为默认值） */
    val battleType: RewardBattleType = RewardBattleType.ANY,
    /**
     * 战斗胜利：按 NPC 身份匹配（仅 [RewardTarget.NPC] 使用）。
     * 值是 Cobblemon 数据包 NPC 的**类 id**（`data/<ns>/npcs/<file>.json` 的路径，即 `/spawnnpc` 后面那段）。
     * ⚠ 命中后整场战斗**只判一次**，不看 NPC 带什么精灵、也不按其队伍只数发。
     */
    val npc: List<String> = emptyList(),
    /** 每人每天**最多发几次**（超过就不发；null = 不限）。⚠ 按「规则」计数，不是按物种 / NPC */
    val dailyCount: Int? = null
) {
    /** 是区间金额吗 */
    val isRange: Boolean get() = amountMin != null && amountMax != null

    /**
     * @param hitCount 这条规则对这个物种**已经发过几次**（0 = 还没发过）
     * @return 这次该发多少；`null` = 这次不发（递减取到 0），调用方应当**继续往下判**
     */
    fun rolledAmount(hitCount: Int, random: java.util.Random): Long? {
        decay?.let { table ->
            // ⚠ 超出档位表 = **沿用最后一档**（表尾写 1 = 降到 1 稳住、写 0 = 停发 —— 一个开关两种诉求）
            val value = table[hitCount.coerceIn(0, table.size - 1)]
            return value.takeIf { it > 0 }
        }
        amount?.let { return it }
        val lo = amountMin ?: 0L
        val hi = amountMax ?: lo
        if (hi <= lo) return lo
        return lo + (random.nextDouble() * (hi - lo + 1)).toLong().coerceIn(0L, hi - lo)
    }

    /**
     * **条件指纹**（去重 / 递减的进度按它分开记）—— 前置事件名，再拼条件。
     *
     * ⚠ 不含金额 / 档位表：**改价钱不重置进度、改条件才算换了一条规则**。
     */
    fun fingerprint(): String = buildString {
        append(event.key).append('|')
        // ⚠ 只在**非默认值**时拼进去：默认的 self / any 不改变老规则的指纹
        //   ⇒ 升级到带这两个字段的版本时，现有进度不会被无故清空
        if (target != RewardTarget.SELF) append("target=").append(target.key).append('|')
        if (battleType != RewardBattleType.ANY) append("battleType=").append(battleType.key).append('|')
        // NPC 列表排序后再拼：只换顺序不该被当成换了一条规则
        if (npc.isNotEmpty()) append("npc=").append(npc.sorted().joinToString(",")).append('|')
        append(condition.fingerprint())
    }

    /** 命中列表里显示用的短标签 */
    fun label(): String = "第 ${indexInFile + 1} 条"
}

/**
 * 一个数据包文件解析出来的规则集。
 *
 * @param file 文件在数据包里的相对路径（`<命名空间>/points_rewards/<名>.json`）
 * @param debug 该文件开了 `"debug": true` ⇒ 每次事件都打一行"命中了第几条 / 没命中"
 */
data class RewardRuleFile(
    val file: String,
    val debug: Boolean,
    val rules: List<RewardRule>
)

/**
 * **奖励规则表**：从数据包读出来的全部规则，按**文件 → 文件内顺序**排成一串（判定顺序就是它）。
 *
 * ⚠⭐ 排序口径（官网要写明、服主据此预测"哪条规则先被看"）：
 *   **先按文件所属命名空间、再按文件名做字典序**，然后依次拼接 ——
 *   [ResourceManager.getResource] 那类按 id 单查取不到多份，所以必须自己合并。
 *
 * ⚠ 本对象是**不可变的**：[load] 每次都重建一份新表整体换上去 ⇒ 重载过程中事件读到的
 *   要么是旧那份、要么是新那份，**不会读到半截**（发布式更新，不做原地清理）。
 */
class RewardRules(
    val ordered: List<RewardRule>,
    val files: List<RewardRuleFile>
) {

    /**
     * **按事件分好桶的规则**（判定顺序与 [ordered] 一致）。
     *
     * ⚠ 提前分桶是**给事件路径省时间**的：捕捉时只该看 `capture` 那几条，
     *   而刷怪塔面前每毫秒都算数 —— 每次事件都遍历整张表、逐条 `rule.event != kind` 是白烧 CPU。
     * `ordered` 保留是给汇总日志 / 调试用的（要按原始顺序看"一共几条、哪个事件几条"）。
     */
    val byEvent: Map<RewardEvent, List<RewardRule>> = ordered.groupBy { it.event }

    /** 这个事件有哪些规则要判（没有就是空表，调用方直接返回） */
    fun forEvent(event: RewardEvent): List<RewardRule> = byEvent[event].orEmpty()

    /** 这里面的规则是不是"值得打调试日志"（任一文件开了 debug） */
    fun debugEnabled(file: String): Boolean = files.any { it.file == file && it.debug }

    companion object {

        /**
         * **规则文件在数据包里的子目录名**（完整路径 = `data/<命名空间>/points_rewards/`）。
         *
         * ⚠⚠ **命名空间 = `data/` 下的一级文件夹名**（扫描时从目录名读，**不是固定值**）。
         * 之前把 `cobblemarket/points_rewards` 写成一个常量 ⇒ 路径与资源 id 两处都多套了一层
         * `cobblemarket/`，MC 静默返回空、只表现为"0 条规则"（2026-10-13）。
         * ⚠ 2026-10-18 又复现了一次：**文档**里写成 `data/<命名空间>/cobblemarket/points_rewards/`，
         * 测试包照抄 ⇒ 一条规则都没加载。文档已改成 `data/<命名空间>/points_rewards/`。
         */
        const val SUBDIR = "points_rewards"

        private const val JSON_SUFFIX = ".json"

        fun empty(): RewardRules = RewardRules(emptyList(), emptyList())

        /**
         * 读全部数据包、解析成规则表。
         *
         * ⚠ **单条容错**：坏规则只跳过它自己 + `LOGGER.warn` 写明"哪个文件、第几条、哪个字段"，
         *   **绝不整体抛**（存档铁律 §读取容错 的同一条思路 —— 一份写错的 json 不该让整台服务器没奖励）。
         * ⚠ 规则表**来自几个文件**这一条要能被官网的排错表引用 ⇒ 每次重载都打一行汇总日志。
         */
        fun load(server: MinecraftServer): RewardRules {
            val out = mutableListOf<RewardRuleFile>()

            // **发现方式**：`ResourceManager.findResources(SUBDIR)` 一次扫全 ——
            // 模组自带包 / 世界数据包（**文件夹与 zip 都算**）/ 其它模组带的包都会命中。
            //
            // ⚠ 2026-10-23 改回资源管理器（此前是读盘列 `datapacks/` 目录 —— 那条路只认文件夹，
            //   zip 数据包会被静默跳过）。用户实测：「示例包是 zip，放进去不生效」。
            //   MC 本身支持 zip 数据包，限制来自我们自己的发现方式，改这里即可。
            // ⚠ `findResources` 返回**每个 id 优先级最高的那一份**（已按数据包优先级合并）——
            //   这正是 Minecraft 的覆盖语义；要"所有同名文件"才用 `findAllResources`。
            // ⚠ 路径只有一层：`data/<命名空间>/points_rewards/<文件>.json`，`<命名空间>` 是
            //   id 的 namespace（**不是固定值**）。别再拼一次 `cobblemarket/points_rewards`
            //   —— 2026-10-13 就是路径多套一层、静默返回空表（`SUBDIR` 只保留子目录部分）。
            // ⚠ 排序口径：**先按命名空间、再按文件名**字典序 ⇒ 官网「哪条规则先被看」写的就是这条。
            val manager = server.resourceManager
            val discovered: List<Identifier> = try {
                manager.findResources(SUBDIR) { id ->
                    id.path.startsWith("$SUBDIR/") &&
                        id.path.endsWith(JSON_SUFFIX) &&
                        id.path.count { it == '/' } == 1
                }.keys.sortedWith(compareBy({ it.namespace }, { it.path }))
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("RP reward rules: failed to list datapack resources — {}", e.message)
                emptyList()
            }

            for (fileId in discovered) {
                val relative = "${fileId.namespace}:${fileId.path}"
                val resource = try {
                    // ⚠ `getAllResources` 返回**所有**数据包里的同名文件（已按优先级排好）
                    //   ⇒ 取第一份 = 优先级最高的那份，与其他数据包内容的覆盖语义一致
                    manager.getAllResources(fileId).firstOrNull()
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.warn("RP reward rules: failed to open {} — {}", relative, e.message)
                    null
                }
                if (resource == null) {
                    CobbleMarket.LOGGER.warn("RP reward rules: {} not found in any enabled pack — skipped", relative)
                    continue
                }
                try {
                    resource.reader.use { reader ->
                        val root = JsonParser.parseReader(reader)
                        if (!root.isJsonObject) {
                            CobbleMarket.LOGGER.warn("RP reward rules: {} is not a JSON object — skipped", relative)
                            return@use
                        }
                        out += parseFile(relative, root.asJsonObject)
                    }
                } catch (e: Exception) {
                    // 坏 json（语法错 / 读不出来）⇒ 只丢这一个文件，其余照常
                    CobbleMarket.LOGGER.warn("RP reward rules: failed to read {} — {}", relative, e.message)
                }
            }

            val ordered = out.flatMap { it.rules }
            logSummary(out, ordered)
            return RewardRules(ordered, out)
        }

        /** 每次 `/reload` 后的那行汇总（官网「没生效怎么查」那一节就靠它） */
        private fun logSummary(files: List<RewardRuleFile>, ordered: List<RewardRule>) {
            if (ordered.isEmpty()) {
                CobbleMarket.LOGGER.info(
                    "RP reward rules: no rule loaded ({} file(s) under data/*/{})", files.size, SUBDIR
                )
                return
            }
            val byEvent = RewardEvent.entries
                .map { "${it.key}=${ordered.count { r -> r.event == it }}" }
                .joinToString(", ")
            CobbleMarket.LOGGER.info(
                "RP reward rules: {} rule(s) loaded from {} file(s) [{}]",
                ordered.size, files.size, byEvent
            )
        }

        /** 解析一个 json 文件 → 规则集；字段错的那**条**跳过、文件其余部分照常生效 */
        private fun parseFile(file: String, obj: JsonObject): RewardRuleFile {
            val debug = obj.get("debug")?.takeIf { it.isJsonPrimitive }?.asBoolean == true

            val event = RewardEvent.fromJson(obj.get("event")?.takeIf { it.isJsonPrimitive }?.asString)
            if (event == null) {
                CobbleMarket.LOGGER.warn(
                    "RP reward rules: {} skipped — \"event\" is missing or unknown (expected one of {})",
                    file, RewardEvent.entries.joinToString(", ") { it.key }
                )
                return RewardRuleFile(file, debug, emptyList())
            }

            // 文件级默认（规则里可各自覆盖）
            val defaultRepeat = obj.get("repeat")?.takeIf { it.isJsonPrimitive }?.asString?.let { raw ->
                RewardRepeat.fromJson(raw) ?: run {
                    CobbleMarket.LOGGER.warn(
                        "RP reward rules: {} — unknown \"repeat\" value \"{}\", falling back to \"once\"",
                        file, raw
                    )
                    RewardRepeat.ONCE
                }
            } ?: RewardRepeat.ONCE
            val defaultCap = obj.get("daily_cap").let { parsePositiveLong(it, file, "daily_cap") }

            val rulesArray = obj.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
            if (rulesArray.size() == 0) {
                CobbleMarket.LOGGER.warn("RP reward rules: {} has no \"rules\" array — nothing to load", file)
                return RewardRuleFile(file, debug, emptyList())
            }

            val rules = rulesArray.mapIndexedNotNull { index, element ->
                if (!element.isJsonObject) {
                    CobbleMarket.LOGGER.warn(
                        "RP reward rules: {} rule #{} skipped — not a JSON object", file, index + 1
                    )
                    return@mapIndexedNotNull null
                }
                parseRule(file, index, event, defaultRepeat, defaultCap, element.asJsonObject)
            }
            return RewardRuleFile(file, debug, rules)
        }

        private val KNOWN_KEYS = setOf(
            "amount", "decay", "repeat", "daily_cap", "daily_count",
            // 战斗胜利专用（见 RewardTarget / RewardBattleType）
            "target", "battleType", "npc",
            "species", "namespace", "aspects", "variantAspects", "labels",
            "shiny", "alpha", "ht", "minPerfectIvs", "level",
            // 排除条件（`excludeXxx`）—— 见 RewardCondition 里那段 KDoc：
            // 用来处理"魔改变体继承了原种标签"（腐化烈空坐同时是 legendary 与 corrupted）
            "excludeSpecies", "excludeNamespace", "excludeLabels",
            "excludeAspects", "excludeVariantAspects"
        )

        private fun parseRule(
            file: String,
            index: Int,
            event: RewardEvent,
            defaultRepeat: RewardRepeat,
            defaultCap: Long?,
            obj: JsonObject
        ): RewardRule? {
            // 日志前缀：所有 warn 都带"哪个文件、第几条"，服主照着就能定位（官网排错表的依据）
            val where = "$file rule #${index + 1}"

            // ── 未知键：显式跳过并说清楚（"写错一个字母 = 静默没生效"是最难查的一类） ──
            val unknown = obj.keySet().filter { it !in KNOWN_KEYS }
            if (unknown.isNotEmpty()) {
                CobbleMarket.LOGGER.warn(
                    "RP reward rules: {} skipped — unknown key(s): {} (known keys: {})",
                    where, unknown.joinToString(", "), KNOWN_KEYS.sorted().joinToString(", ")
                )
                return null
            }

            // ── 金额 ──
            val amountField = obj.get("amount")
            val decayField = obj.get("decay")
            if (amountField != null && decayField != null) {
                CobbleMarket.LOGGER.warn(
                    "RP reward rules: {} skipped — \"amount\" and \"decay\" cannot be used together " +
                        "(decay replaces the fixed amount)", where
                )
                return null
            }

            var amount: Long? = null
            var amountMin: Long? = null
            var amountMax: Long? = null
            var decay: List<Long>? = null

            if (decayField != null) {
                decay = parseAmountList(decayField)
                if (decay == null || decay.isEmpty()) {
                    CobbleMarket.LOGGER.warn(
                        "RP reward rules: {} skipped — \"decay\" must be a non-empty list of numbers", where
                    )
                    return null
                }
            } else {
                when {
                    amountField == null -> {
                        CobbleMarket.LOGGER.warn(
                            "RP reward rules: {} skipped — missing \"amount\" (a number, or [min, max])", where
                        )
                        return null
                    }
                    amountField.isJsonArray -> {
                        val pair = parseAmountList(amountField)
                        if (pair == null || pair.size != 2) {
                            CobbleMarket.LOGGER.warn(
                                "RP reward rules: {} skipped — \"amount\" range must be [min, max] " +
                                    "(two positive numbers)", where
                            )
                            return null
                        }
                        amountMin = minOf(pair[0], pair[1])
                        amountMax = maxOf(pair[0], pair[1])
                    }
                    else -> {
                        val single = parseAmount(amountField)
                        if (single == null || single <= 0L) {
                            CobbleMarket.LOGGER.warn(
                                "RP reward rules: {} skipped — \"amount\" must be a positive number", where
                            )
                            return null
                        }
                        amount = single
                    }
                }
            }

            // ── 重复口径 ──
            val repeat = obj.get("repeat")?.takeIf { it.isJsonPrimitive }?.asString?.let { raw ->
                RewardRepeat.fromJson(raw) ?: run {
                    CobbleMarket.LOGGER.warn(
                        "RP reward rules: {} — unknown \"repeat\" value \"{}\", falling back to \"once\"", where, raw
                    )
                    RewardRepeat.ONCE
                }
            } ?: defaultRepeat

            // decay 只对 DECAY 有意义；写在别的 repeat 上多半是配置出错 ⇒ 显式说一声（不跳过，照发）
            if (decay != null && repeat != RewardRepeat.DECAY) {
                CobbleMarket.LOGGER.warn(
                    "RP reward rules: {} — \"decay\" is only used when \"repeat\" is \"decay\" " +
                        "(this rule repeats as \"{}\", so the decay table is ignored)", where, repeat.key
                )
            }

            val cap = obj.get("daily_cap")?.let { parsePositiveLong(it, file, "daily_cap") } ?: defaultCap

            // ── 每人每天最多发几次（可选；按规则计数）──
            val dailyCount = obj.get("daily_count")?.let { el ->
                val value = parsePositiveLong(el, file, "daily_count") ?: return null
                value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }

            // ── 战斗胜利专用：匹配谁 / 哪种战斗 / 哪个 NPC ──
            // ⚠ 非法值**跳过该条 + warn**（不回退默认）—— 写错一个字母若回退成 self，
            //   会变成"用你的精灵赢一场就发钱"，比不生效更糟（配置错误要显式）
            var target = RewardTarget.SELF
            var battleType = RewardBattleType.ANY
            var npc = emptyList<String>()
            if (event == RewardEvent.BATTLE_VICTORY) {
                obj.get("target")?.let { el ->
                    val raw = el.takeIf { it.isJsonPrimitive }?.asString
                    val parsed = raw?.let { RewardTarget.fromJson(it) }
                    if (parsed == null) {
                        CobbleMarket.LOGGER.warn(
                            "RP reward rules: {} skipped — \"target\" must be \"self\", \"opponent\" or \"npc\" (got: {})",
                            where, el.toString()
                        )
                        return null
                    }
                    target = parsed
                }
                obj.get("battleType")?.let { el ->
                    val raw = el.takeIf { it.isJsonPrimitive }?.asString
                    val parsed = raw?.let { RewardBattleType.fromJson(it) }
                    if (parsed == null) {
                        CobbleMarket.LOGGER.warn(
                            "RP reward rules: {} skipped — \"battleType\" must be \"any\", \"wild\" or \"npc\" (got: {})",
                            where, el.toString()
                        )
                        return null
                    }
                    battleType = parsed
                }
                if (target == RewardTarget.NPC) {
                    npc = readStringList(obj, "npc")
                    if (npc.isEmpty()) {
                        CobbleMarket.LOGGER.warn(
                            "RP reward rules: {} skipped — \"target\": \"npc\" requires a non-empty \"npc\" list " +
                                "(Cobblemon NPC class id, e.g. \"yourpack:elite_four_lorelei\")",
                            where
                        )
                        return null
                    }
                } else if (obj.has("npc")) {
                    CobbleMarket.LOGGER.warn(
                        "RP reward rules: {} skipped — \"npc\" is only used with \"target\": \"npc\"", where
                    )
                    return null
                }
            } else {
                // 写在别的 event 上没意义：明确说一声、按默认处理（不跳过规则）
                if (obj.has("target") || obj.has("battleType") || obj.has("npc")) {
                    CobbleMarket.LOGGER.warn(
                        "RP reward rules: {} — \"target\" / \"battleType\" / \"npc\" only apply to event " +
                            "\"battle_victory\"; ignored", where
                    )
                }
            }

            // ── 条件 ──
            val level = readLevel(obj, where)
            val rawCondition = RewardCondition(
                species = readStringList(obj, "species"),
                namespace = obj.get("namespace")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() },
                aspects = readStringList(obj, "aspects"),
                variantAspects = readStringList(obj, "variantAspects"),
                labels = readStringList(obj, "labels"),
                shiny = readTristate(obj, "shiny", where),
                alpha = readTristate(obj, "alpha", where),
                ht = readTristate(obj, "ht", where),
                minPerfectIvs = obj.get("minPerfectIvs")?.let { parseAmount(it) }?.toInt()?.coerceIn(0, 6) ?: 0,
                levelMin = level?.first,
                levelMax = level?.second,
                // 排除条件（语义与正向字段对称；列表同样"命中任一即排除"）
                excludeSpecies = readStringList(obj, "excludeSpecies"),
                excludeNamespace = readStringList(obj, "excludeNamespace"),
                excludeLabels = readStringList(obj, "excludeLabels"),
                excludeAspects = readStringList(obj, "excludeAspects"),
                excludeVariantAspects = readStringList(obj, "excludeVariantAspects")
            )
            // NPC 规则按身份匹配，不看对方带什么精灵 ⇒ 条件字段一律不参与判定
            val condition = if (target == RewardTarget.NPC) {
                if (rawCondition != RewardCondition()) {
                    CobbleMarket.LOGGER.warn(
                        "RP reward rules: {} — NPC rules ignore Pokémon conditions; they will not be used", where
                    )
                }
                RewardCondition()
            } else {
                rawCondition
            }

            return RewardRule(
                event = event,
                sourceFile = file,
                indexInFile = index,
                repeat = repeat,
                condition = condition,
                amount = amount,
                amountMin = amountMin,
                amountMax = amountMax,
                decay = decay,
                dailyCap = cap,
                target = target,
                battleType = battleType,
                npc = npc,
                dailyCount = dailyCount
            )
        }

        /** 非负整数金额（JSON 里可以是数字或字符串） */
        private fun parseAmount(element: JsonElement?): Long? = try {
            if (element == null || element.isJsonNull) null
            else if (element.isJsonPrimitive) element.asLong.coerceAtLeast(0L)
            else null
        } catch (e: Exception) {
            null
        }

        private fun parseAmountList(element: JsonElement): List<Long>? {
            if (!element.isJsonArray) return null
            val out = element.asJsonArray.map { parseAmount(it) ?: return null }
            return out
        }

        /** 正整数（不能是 0：`daily_cap: 0` 写成"关掉"的意思会让人以为配了没生效，显式拒绝） */
        private fun parsePositiveLong(element: JsonElement?, file: String, field: String): Long? {
            if (element == null || element.isJsonNull) return null
            val value = parseAmount(element)
            if (value == null || value <= 0L) {
                CobbleMarket.LOGGER.warn(
                    "RP reward rules: {} — \"{}\" must be a positive number, ignored", file, field
                )
                return null
            }
            return value
        }

        private fun readStringList(obj: JsonObject, field: String): List<String> {
            val element = obj.get(field) ?: return emptyList()
            if (!element.isJsonArray) return emptyList()
            return element.asJsonArray
                .mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.trim()?.takeIf { s -> s.isNotEmpty() } }
        }

        /** 三态布尔：缺省 / JSON null = 不限；非布尔值按不限处理（不崩、也不误判成 false） */
        private fun readTristate(obj: JsonObject, field: String, where: String): Boolean? {
            val element = obj.get(field) ?: return null
            if (element.isJsonNull) return null
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
                CobbleMarket.LOGGER.warn(
                    "RP reward rules: {} — \"{}\" must be true or false (or omitted); value ignored", where, field
                )
                return null
            }
            return element.asBoolean
        }

        /**
         * 等级区间 `[min, max]`。
         *
         * 三种写法都被接受：`[5, 100]`（区间）、`[5]`（只有下限）、`5`（只有下限）。
         * ⚠ 两端的语义：**null / 省略 / 负数 = 不限**（`[5, -1]` = 5 级以上）。
         */
        private fun readLevel(obj: JsonObject, where: String): Pair<Int?, Int?>? {
            val element = obj.get("level") ?: return null
            if (element.isJsonNull) return null
            if (!element.isJsonArray && !element.isJsonPrimitive) {
                CobbleMarket.LOGGER.warn("RP reward rules: {} — \"level\" must be [min, max] or a number", where)
                return null
            }
            val values = if (element.isJsonArray) element.asJsonArray.map { parseAmount(it) } else listOf(parseAmount(element))
            fun bound(raw: Long?): Int? = raw?.takeIf { it >= 0 }?.toInt()
            val min = bound(values.getOrNull(0))
            val max = bound(values.getOrNull(1))
            // 写反了就换过来（服主手写 json，[100, 5] 显然是想要 5~100 而不是"永远不命中"）
            return if (min != null && max != null && min > max) max to min else min to max
        }
    }
}
