package com.shusheng.cobblemarket.currency.rewards

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.getPlayer
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.PointsAuditLogger
import com.shusheng.cobblemarket.currency.PointsLogType
import com.shusheng.cobblemarket.currency.PointsState
import com.shusheng.cobblemarket.platform.registerServerDataReload
import net.minecraft.resource.ResourceManager
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import java.util.Random
import java.util.UUID

/**
 * **RP 奖励系统** —— 服主用数据包配"做某件事就给 RP"的规则（`data/` 下
 * `points_rewards/` 里的 json —— 路径是 `data/<命名空间>/points_rewards/`）。
 *
 * ⚠⭐ **默认整块关着**（`CobbleMarketConfig.pointsRewardsEnabled`，默认 false）：装了 1.3.0
 *   什么都不动 ⇒ **不可能有任何一个 RP 因为捕捉 / 孵化 / 战斗而自动进玩家账户**。
 *   理由：**如果 RP 被当充值货币卖，自动发就等于自动发钱**（用户 2026-10-13 点名）。
 *   开关还有两道配套：开启时弹确认窗（讲清后果 + 5 秒冷静期）、配了规则但开关关着时日志给一句提示。
 *
 * ⚠ 依赖 **RP 总开关**（`pointsEnabled`）：那个没开 ⇒ 本系统**整块短路**（在 [enabled] 一处判，
 *   别在奖励侧再判一遍 —— 两处各判一次迟早分叉）。
 *
 * **事件里零读盘、零反射**（性能要求）：规则表常驻内存、命中即算；写盘只有发放时那两下
 * `RewardProgressState` 的 `markDirty()`（去重计数 + 每日累计）。
 */
object PointsRewards {

    /** 当前生效的规则表（[onReload] 整体换新，事件侧读到的要么旧要么新、不会读到半截） */
    @Volatile
    private var rules: RewardRules = RewardRules.empty()

    private val random = Random()

    /** 本轮重载里"配了规则但总开关关着"提示过没有（每次重载重置一次，别刷日志） */
    private var warnedDisabled = false

    const val RELOAD_ID: String = "${CobbleMarket.MOD_ID}:points_rewards"

    /**
     * 整块开关。**必须同时满足**：RP 总开关开着（[CobbleMarketConfig.pointsEnabled]）+
     * 奖励开关开着（[CobbleMarketConfig.pointsRewardsEnabled]）。
     */
    private fun enabled(): Boolean =
        CobbleMarketConfig.pointsEnabled && CobbleMarketConfig.pointsRewardsEnabled

    // ── 装配 ──

    /** 在 `CobbleMarket.init()` 里调一次：注册数据包重载 + 订阅五个 Cobblemon 事件 */
    fun register() {
        registerServerDataReload(net.minecraft.util.Identifier.of(RELOAD_ID)) { manager, server ->
            onReload(manager, server)
        }
        registerEvents()
    }

    /**
     * 数据包重载（世界加载 / `/reload` 各一次）：重读 json、整体换表。
     *
     * ⚠ **回调在重载线程上同步跑**（平台层保证）⇒ 这里只解析、不碰世界。
     * ⚠ `server` 是用来**读盘列文件名**的（`ResourceManager` 列不出世界数据包的目录，
     *   见 `RewardRules.load` 上方那段实测记录）。
     *
     * ⚠⚠ **有两个入口会调它，缺一不可**（2026-10-13 实测踩到）：
     * 1. **世界启动时**（`CobbleMarket.onServerStarted`）—— 进存档这一次的加载**不经过**
     *    平台层的重载监听，不在这里补一次的话，规则表一直是空的：玩家"配了规则、抓精灵却没奖励"，
     *    手动 `/reload` 一下才好（看起来像"每次进存档都要手动开一次数据包"，其实包一直是启用的）
     * 2. **`/reload` 时**（平台层的重载监听）—— 服主改完 json 不重启就生效全靠它
     *
     * ⇒ 两个入口**共用这一个函数**，别各写一份读表逻辑（两处各写迟早分叉）。
     * ⇒ 重复调用无副作用：每次都是**整体换一份新表**，`warnedDisabled` 顺带重置。
     */
    fun onReload(manager: ResourceManager, server: MinecraftServer) {
        rules = RewardRules.load(server)
        warnedDisabled = false
        // 配了规则但开关关着 ⇒ 明确说一句，免得服主配了半天以为没生效（方案 §3.1 护栏 3）
        if (rules.ordered.isNotEmpty() && !enabled()) {
            CobbleMarket.LOGGER.info(
                "RP reward rules: {} rule(s) are configured but the reward system is OFF " +
                    "(enable it in the server config when you need it)",
                rules.ordered.size
            )
        }
    }

    private fun registerEvents() {
        CobblemonEvents.POKEMON_CAPTURED.subscribe { event ->
            onPokemonEvent(RewardEvent.CAPTURE, event.player, event.pokemon)
        }
        CobblemonEvents.HATCH_EGG_POST.subscribe { event ->
            onPokemonEvent(RewardEvent.HATCH, event.player, event.pokemon)
        }
        CobblemonEvents.FOSSIL_REVIVED.subscribe { event ->
            // ⚠ 化石复活是**唯一可能没有玩家**的事件（自然生成化石 / 命令复活）——
            //   没有玩家就没有人可发，直接跳过（方案 §2.1 已定）
            val player = event.player ?: return@subscribe
            onPokemonEvent(RewardEvent.FOSSIL_REVIVED, player, event.pokemon)
        }
        CobblemonEvents.EVOLUTION_COMPLETE.subscribe { event ->
            event.pokemon.getOwnerPlayer()?.let { player ->
                // 判的是**进化后**的形态（"进化成 X 给钱"符合直觉；`cobblemon:` 之外的名字不会变）
                onPokemonEvent(RewardEvent.EVOLUTION, player, event.pokemon)
            }
        }
        CobblemonEvents.BATTLE_VICTORY.subscribe { event ->
            // ⚠ **PvP 一律不发**（2026-10-18 用户拍板）：双方都有玩家时 `isPvP = true`，
            //   两名玩家 / 开小号互打就能无限刷 RP。战斗胜利规则的本意只有**野生 / NPC**
            //   （方案 §2.1）；NPC 对战照发，玩家对战不算。
            if (event.battle.isPvP) return@subscribe
            // ⚠ **野生宝可梦被捕捉时也会走这个事件**（源码 `WinInstruction` 里 `wasCaught=true`）——
            //   不排掉的话"抓到一只"会**同时**触发 capture 与 battle_victory 两条奖励（双倍发钱）。
            //   本体自己的 `StatHandler.onWinBattle` 就是拿这个字段挡的，照抄。
            if (event.wasWildCapture) return@subscribe

            // 被击败的对手（野生 / NPC；PvP 已在上面排除）——
            // 只认真的倒下的（`health <= 0`）：NPC 整队里没上场 / 还没倒下的不算
            val defeated = event.losers
                .filter { it.type == ActorType.WILD || it.type == ActorType.NPC }
                .flatMap { actor ->
                    actor.pokemonList
                        .filter { it.health <= 0 }
                        .mapNotNull { it.originalPokemon }
                }

            event.winners.forEach { actor ->
                if (actor.type != ActorType.PLAYER) return@forEach
                // 一个 actor 可能代表多名玩家（双人合战）⇒ 逐个来，别只取第一个
                actor.getPlayerUUIDs().forEach { uuid ->
                    val player = uuid.getPlayer() ?: return@forEach
                    // 判**上过场的那几只**（`facedOpponents` 在出场时被填），不放整支队伍 ——
                    // 不然"带一队传说去抓一只绿毛虫"会按传说算钱
                    val fighters = actor.getSide().activePokemon
                        .filter { it.battlePokemon?.facedOpponents?.isNotEmpty() == true }
                        .mapNotNull { it.battlePokemon?.originalPokemon }
                    fighters.forEach { pokemon ->
                        onPokemonEvent(RewardEvent.BATTLE_VICTORY, player, pokemon, RewardTarget.SELF, event.battle)
                    }
                    // 对手目标：**每个获胜玩家各判一次**（双人合战里两人都"打赢"了同一只对手）
                    defeated.forEach { pokemon ->
                        onPokemonEvent(RewardEvent.BATTLE_VICTORY, player, pokemon, RewardTarget.OPPONENT, event.battle)
                    }
                }
            }
        }
    }

    // ── 判定 ──

    /**
     * 一次"某只精灵带来了一个事件"。
     *
     * ⚠ **每只精灵最多发一条**（第一条命中即止）—— 与"一场战斗里多只精灵各判各的"是两件事：
     *   前者防止**一条规则重复发**，后者是**每只参战精灵各自算一次**（战斗胜利那条事件的定义如此）。
     */
    private fun onPokemonEvent(
        kind: RewardEvent,
        player: ServerPlayerEntity,
        pokemon: Pokemon,
        target: RewardTarget = RewardTarget.SELF,
        battle: PokemonBattle? = null
    ) {
        if (!enabled()) return
        val table = rules
        // 只看这个事件自己的那几条（提前分好桶，见 RewardRules.byEvent）
        val candidates = table.forEvent(kind)
        if (candidates.isEmpty() || player.isRemoved) return
        val server = player.server
        val speciesId = pokemon.species.resourceIdentifier.toString()

        // 该物种的 form aspect 并集只算一次（同一只精灵要判一串规则，别每条重算）
        val formAspectUnion by lazy { RewardCondition.formAspectUnion(pokemon.species) }
        val state = RewardProgressState.get(server)
        val worldDay = worldDay(server)

        candidates.forEach { rule ->
            // 战斗胜利：只判**本条规则指定的那一侧**
            // （`target` / `battleType` 对其它事件没有意义，解析时已警告并归默认）
            if (rule.target != target) {
                debug(table, rule, "skipped (target ${rule.target.key}; evaluating ${target.key})")
                return@forEach
            }
            if (!rule.battleType.matches(battle)) {
                debug(table, rule, "skipped (battleType ${rule.battleType.key})")
                return@forEach
            }
            if (!rule.condition.matches(pokemon, formAspectUnion)) {
                debug(table, rule, "no match (condition)")
                return@forEach
            }

            // 计数与递减（`once` 与 `decay` **共用这份计数**）
            val hits = state.hitCount(player.uuid, hitKey(speciesId, rule))
            if (rule.repeat == RewardRepeat.ONCE && hits > 0) {
                debug(table, rule, "skipped (already rewarded once for $speciesId)")
                return@forEach
            }
            val amount = rule.rolledAmount(hits, random)
            if (amount == null) {
                debug(table, rule, "skipped (decay table rolled 0 for $speciesId)")
                return@forEach
            }

            // 每日上限：**撞上就整次不发**、而且**继续往下判**（不消耗计数 —— 否则玩家等于白丢一次首抓）
            val cap = rule.dailyCap
            if (cap != null) {
                val used = state.dailyTotal(player.uuid, kind.key, worldDay)
                if (used >= cap) {
                    debug(table, rule, "skipped (daily cap $cap reached for ${kind.key})")
                    return@forEach
                }
            }

            grant(server, player, pokemon, rule, amount)
            return
        }
    }

    /** 发放：落币 + 账本 + 计数 + 每日累计 + 动作栏提示 */
    private fun grant(
        server: MinecraftServer,
        player: ServerPlayerEntity,
        pokemon: Pokemon,
        rule: RewardRule,
        amount: Long
    ) {
        val speciesId = pokemon.species.resourceIdentifier.toString()
        val state = RewardProgressState.get(server)

        val actual = CurrencyService.give(player, MarketCurrency.POINTS, amount)
        if (actual <= 0L) return
        state.recordHit(player.uuid, hitKey(speciesId, rule))
        state.addDaily(player.uuid, rule.event.key, worldDay(server), actual)

        // 审计：来源写「文件名 + 第几条 + 事件 + **哪只精灵**」
        // ⚠ 物种必须带上（2026-10-13 补）：原先只写规则来源，服主对账时**看不出这笔钱是哪只精灵给的**
        //   —— 实测中我自己就撞上过（"那 15 点是哪只？"从账本里查不出来）。走**物种资源 id**，
        //   语言中立（与交易历史 CSV 的字段口径一致）。
        val source = "${rule.sourceFile} #${rule.indexInFile + 1} (${rule.event.key}) · $speciesId"
        PointsAuditLogger.log(
            PointsLogType.REWARD, player.uuid, player.name.string, actual,
            com.shusheng.cobblemarket.currency.PointsState.get(server).getBalance(player.uuid),
            "系统", "奖励规则 $source", detailEn = "Reward rule $source"
        )

        debug(rules, rule, "granted $actual for $speciesId")
        queueActionBar(player, actual)
    }

    /** 调试日志：只对该文件开了 `"debug": true` 的情况下打（默认关，别在生产里刷日志） */
    private fun debug(table: RewardRules, rule: RewardRule, message: String) {
        if (!table.debugEnabled(rule.sourceFile)) return
        CobbleMarket.LOGGER.info("[RP reward debug] {} #{} ({}) — {}", rule.sourceFile, rule.indexInFile + 1, rule.event.key, message)
    }

    // ── 动作栏提示（1 秒内的奖励合并成一条） ──

    private data class Pending(var amount: Long, val dueAt: Long)

    private val pending = mutableMapOf<UUID, Pending>()

    /**
     * **必须合并**：连抓 10 只 = 10 条提示，不合并的话捕捉刷屏会把这套功能变成噪音。
     *
     * 做法：同一玩家 1 秒内的多次奖励**累加到同一条**上，齐了再发一次 —— 发的是**合计**，
     * 玩家看到的是「+N」（N = 这 1 秒赚到的总和），不会漏也不会刷屏。
     *
     * ⚠ 走**原版动作栏**（`overlay = true`）而不是聊天栏：捕捉时聊天栏本就热闹。
     * ⚠ 也**不走自定义网络包** ⇒ 纯服务端行为、**两端不必同版**。
     */
    private fun queueActionBar(player: ServerPlayerEntity, amount: Long) {
        val now = System.currentTimeMillis()
        val entry = pending[player.uuid]
        if (entry == null) {
            pending[player.uuid] = Pending(amount, now + MERGE_WINDOW_MS)
        } else {
            // 累加但**不延长**截止时刻：截止是"这一轮的第一个奖励"定的，最多等 1 秒
            entry.amount += amount
        }
    }

    /** 服务器 tick 末（`CobbleMarket.onServerTickEnd` 里调）：到点的提示合并成一条发出去 */
    fun onServerTick(server: MinecraftServer) {
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        val ready = pending.filterValues { it.dueAt <= now }.keys.toList()
        ready.forEach { uuid ->
            val entry = pending.remove(uuid) ?: return@forEach
            val player = server.playerManager.getPlayer(uuid)
            if (player != null && entry.amount > 0L) {
                // 参数走 CurrencyService.amountText（金额金色 + 货币图标，与全项目同一口径），
                // 前缀「奖励 +」走词条 ⇒ 中英各一份，服务端不拼死语言文本
                val text = Text.translatable("cobblemarket.points.reward_gained")
                    .append(CurrencyService.amountText(entry.amount, MarketCurrency.POINTS))
                player.sendMessage(text, true)
            }
        }
    }

    /** 关服 / 换世界时清掉残留的待发提示（单机切存档同进程，静态状态不得跨世界） */
    fun reset() {
        pending.clear()
        rules = RewardRules.empty()
        warnedDisabled = false
    }

    /** 1 秒合并窗口（见 [queueActionBar]） */
    private const val MERGE_WINDOW_MS = 1000L

    /**
     * **计数键** = 物种 + 规则条件指纹。
     *
     * ⚠⭐ 两样都要（见 `RewardProgressState` 顶部 KDoc 与方案 §3.5）：
     * - **物种**：腐化皮卡丘与腐化妙蛙种子的进度各算各的（抓到新物种不该从第 5 档开始）
     * - **规则**：腐化（贵 / 稀有）与普通（可能压根不发）**互不干扰** —— 抓普通皮卡丘**不会吃掉**
     *   腐化那条的"首次"
     */
    private fun hitKey(speciesId: String, rule: RewardRule): String = "$speciesId@${rule.fingerprint()}"

    /**
     * 世界日（**按世界时间**，不是系统时间）。
     *
     * `dayTime` 在 24000 tick 处回绕、`time` 不回绕 ⇒ 用 `time / 24000`：它单调递增，正好是"第几天"。
     * ⚠ 用世界时间判跨天：服务器改系统时区 / 玩家本机时间都不该影响"每天"的边界。
     */
    private fun worldDay(server: MinecraftServer): Long = server.overworld.time / 24000L
}
