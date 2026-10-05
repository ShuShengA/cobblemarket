package com.shusheng.cobblemarket.command

import com.cobblemon.mod.common.Cobblemon
import com.shusheng.cobblemarket.util.PokemonTags
import com.shusheng.cobblemarket.permission.PermissionFileLogger
import com.shusheng.cobblemarket.permission.PermissionNetwork
import com.shusheng.cobblemarket.permission.PermissionState
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.command.argument.EntityArgumentType
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.PointCodeState
import com.shusheng.cobblemarket.currency.PointCodes
import com.shusheng.cobblemarket.currency.PointOrderNumbers
import com.shusheng.cobblemarket.currency.PointOrderState
import com.shusheng.cobblemarket.currency.PointOrderStatus
import com.shusheng.cobblemarket.currency.PointRedeemService
import com.shusheng.cobblemarket.currency.PointShopError
import com.shusheng.cobblemarket.currency.PointShopResult
import com.shusheng.cobblemarket.currency.PointShopService
import com.shusheng.cobblemarket.currency.pointShopErrorText
import com.shusheng.cobblemarket.currency.PointsAuditLogger
import com.shusheng.cobblemarket.currency.PointsLogType
import com.shusheng.cobblemarket.currency.PointsState
import com.shusheng.cobblemarket.currency.RedeemOutcome
import com.shusheng.cobblemarket.finance.CreditFileLogger
import com.shusheng.cobblemarket.finance.FinanceService
import com.shusheng.cobblemarket.finance.FinanceState
import com.shusheng.cobblemarket.finance.LoanLogType
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.network.MarketNetwork
import com.shusheng.cobblemarket.network.MarketStatePayload
import com.shusheng.cobblemarket.network.sendSound
import com.shusheng.cobblemarket.platform.onPlayerJoin
import com.shusheng.cobblemarket.platform.registerCommands
import com.shusheng.cobblemarket.platform.sendToPlayer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.Formatting

object MarketCommands {

    fun register() {
        registerCommands { dispatcher, _, _ ->
            dispatcher.register(
                CommandManager.literal("market")
                    .then(CommandManager.literal("gui")
                        .executes(::openGui)
                    )
                    .then(CommandManager.literal("ban")
                        .requires { MarketPermissions.has(it, MarketPermissions.BAN) }
                        .then(CommandManager.argument("player", StringArgumentType.word())
                            .suggests(::suggestOnlinePlayerNames)
                            .executes(::banPlayer)
                            .then(CommandManager.argument("duration", StringArgumentType.word())
                                .executes(::banPlayer)
                                .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                                    .executes(::banPlayer)
                                )
                            )
                        )
                    )
                    .then(CommandManager.literal("unban")
                        .requires { MarketPermissions.has(it, MarketPermissions.BAN) }
                        .then(CommandManager.argument("player", StringArgumentType.word())
                            .suggests(::suggestOnlinePlayerNames)
                            .executes(::unbanPlayer)
                        )
                    )
                    .then(CommandManager.literal("banlist")
                        .requires { MarketPermissions.has(it, MarketPermissions.BAN) }
                        .executes(::banList)
                    )
                    // ⚠ 2026-09-24 起 on/off 判 MARKET_SWITCH（与面板里那颗按钮同一节点）：
                    //   下架挂单与紧急停市是两码事，不该绑在同一个节点上
                    .then(CommandManager.literal("on")
                        .requires { MarketPermissions.has(it, MarketPermissions.MARKET_SWITCH) }
                        .executes(::marketOn)
                    )
                    .then(CommandManager.literal("off")
                        .requires { MarketPermissions.has(it, MarketPermissions.MARKET_SWITCH) }
                        .executes(::marketOff)
                    )
                    .then(CommandManager.literal("reload")
                        // ⚠ **保持 OP 可用**（2026-09-23 服主制改制时有意保留）：它只是只读重载、
                        // 不改任何东西，而服主手编完 config/cobblemarket.json 得靠它生效 ——
                        // 收紧成服主专属等于把「手编配置」这条自救路径堵死
                        .requires { it.hasPermissionLevel(2) }
                        .executes(::reloadConfig)
                    )
                    .then(CommandManager.literal("loan")
                        // 撤销坏账只限服主（干预手段，不随管理节点授予）
                        .requires { MarketPermissions.isServerOwner(it) || it.hasPermissionLevel(2) }
                        .then(CommandManager.literal("clear")
                            .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests(::suggestOnlinePlayerNames)
                                .executes(::loanClear)
                            )
                        )
                    )
                    .then(CommandManager.literal("tag")
                        // ⚠ **只服主**（用户 2026-09-30 定）：它能改**别人队伍里**的精灵，影响面大。
                        //   命令树的 `requires` 带 OP 只为让游戏内 OP 敲得进来看到红字，
                        //   **真正的鉴权是 [removePokemonTag] 首行的 `denyNonOwner`**
                        .requires { MarketPermissions.isServerOwner(it) || it.hasPermissionLevel(2) }
                        .then(CommandManager.literal("remove")
                            // ⚠ 只有 remove、**没有 add**（用户 2026-09-30 定）：打标的入口已经够多
                            //   （商城编辑窗 / 数据包 / `/pokeedit`），而"给任意精灵打标"那条路早前否过；
                            //   remove 补的是**唯一**缺口 —— 标记打错之后**没有任何别的办法撤销**。
                            // ⚠ 玩家名用 EntityArgumentType.player()：它**自带在线玩家补全**（别自己写 suggests）
                            .then(CommandManager.argument("player", EntityArgumentType.player())
                                .then(CommandManager.argument("slot", IntegerArgumentType.integer(1, PARTY_SIZE))
                                    // ⚠ **标签参数必填**（用户 2026-10-01 定）：现在只有一个可撤标签，
                                    //   看着多余，但命令**还没发布过** ⇒ 现在加是零成本；等发布后再加
                                    //   就是破坏性变更（老用法失效）。补全里去掉记不住名字的问题。
                                    .then(CommandManager.argument("tag", StringArgumentType.word())
                                        // 只补全**可撤销的**短名（`shop` 有意不在其中 —— 来源标记不可撤）
                                        .suggests { _, builder ->
                                            PokemonTags.REMOVABLE_TAGS.keys.forEach { builder.suggest(it) }
                                            builder.buildFuture()
                                        }
                                        .executes(::removePokemonTag)
                                    )
                                )
                            )
                        )
                    )
                    .then(CommandManager.literal("card")
                        .requires { MarketPermissions.has(it, MarketPermissions.FINANCE) }
                        .then(CommandManager.literal("give")
                            .then(CommandManager.argument("player", EntityArgumentType.player())
                                .then(CommandManager.argument("kind", StringArgumentType.word())
                                    .suggests(::suggestCardKinds)
                                    .executes(::cardGive)
                                )
                                // 缺省卡种 = purple（旧习惯兼容）
                                .executes(::cardGive)
                            )
                        )
                        .then(CommandManager.literal("revoke")
                            .then(CommandManager.argument("player", EntityArgumentType.player())
                                .then(CommandManager.argument("kind", StringArgumentType.word())
                                    .suggests(::suggestCardKinds)
                                    .executes(::cardRevoke)
                                )
                                .executes(::cardRevoke)
                            )
                            // 离线玩家走名字分支（照封禁管理：在线 playerManager → 离线 userCache）
                            .then(CommandManager.argument("name", StringArgumentType.word())
                                .suggests(::suggestOnlinePlayerNames)
                                .then(CommandManager.argument("kind", StringArgumentType.word())
                                    .suggests(::suggestCardKinds)
                                    .executes(::cardRevokeByName)
                                )
                                .executes(::cardRevokeByName)
                            )
                        )
                        .then(CommandManager.literal("list")
                            .then(CommandManager.argument("kind", StringArgumentType.word())
                                .suggests(::suggestCardKinds)
                                .executes(::cardList)
                            )
                            .executes(::cardList)
                        )
                    )
                    .then(CommandManager.literal("perm")
                        // 权限授予是**服主专属**：不下放给任何管理节点 ——
                        // 否则持有任一节点的人能给自己加满全部节点（提权）。
                        // ⚠ requires 里带上 OP 是为了让游戏内 OP 敲得进来、看到「仅服主可执行」的红字提示；
                        //   真正的鉴权在每个执行函数首行（denyNonOwner），别把这行当成放行
                        .requires { MarketPermissions.isServerOwner(it) || it.hasPermissionLevel(2) }
                        .then(CommandManager.literal("grant")
                            .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests(::suggestOnlinePlayerNames)
                                .then(CommandManager.argument("node", StringArgumentType.word())
                                    .suggests(::suggestPermNodes)
                                    .executes(::permGrant)
                                )
                            )
                        )
                        .then(CommandManager.literal("revoke")
                            .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests(::suggestOnlinePlayerNames)
                                .then(CommandManager.argument("node", StringArgumentType.word())
                                    .suggests(::suggestPermNodes)
                                    .executes(::permRevoke)
                                )
                            )
                        )
                        .then(CommandManager.literal("list")
                            .executes { ctx -> permList(ctx, null) }
                            .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests(::suggestOnlinePlayerNames)
                                .executes { ctx -> permList(ctx, StringArgumentType.getString(ctx, "player")) }
                            )
                        )
                    )
                    .then(CommandManager.literal("points")
                        // 发放/回收 = **印钞权**：**仅服主**（无凭证的即时直发/回收，属服主干预手段，
                        // 同「撤销坏账」），不下放给任何管理节点。
                        // ⚠ 2026-09-25 从 OP 收紧为服主：原先任何 OP 都能凭空造钱 —— 违反「OP = 普通玩家」
                        //   （`/op` 可被传递，认 OP 等于给发钞权开后门）
                        // ⚠ requires 里带 OP 只为让游戏内 OP 敲得进来、看到「仅服主可执行」的红字，
                        //   **真正的鉴权在 pointsAdjust 首行（denyNonOwner）**
                        // ⚠ FTB 任务奖励走的就是这两条命令（见 pointsAdjust 的 `@s` 说明）：
                        //   以**控制台身份**执行照常可用（控制台 = 服主）；以命令方块身份会被拒
                        .then(CommandManager.literal("give")
                            .requires { MarketPermissions.isServerOwner(it) || it.hasPermissionLevel(2) }
                            // ⚠ `@s`（= 执行者自己）必须是**命令树里的字面量分支**：它是给 FTB
                            //   任务奖励用的（`/market points give @s N`，玩家做完任务自动到账）。
                            //   塞进 `StringArgumentType.word()` 当"合法值"是不行的 —— 客户端按参数类型
                            //   解析，`@s` 会被标红、服务端报「参数后应有空格分隔，但发现了紧邻的数据」，
                            //   **整条命令根本走不到我们的代码**（2026-09-28 用户实测；原先只在服务端
                            //   手工认 `@s` 确实没用，解析这关就过不去）。字面量按字符精确匹配，两端都无歧义。
                            .then(CommandManager.literal("@s")
                                .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                                    .executes { ctx -> pointsAdjustSelf(ctx, give = true) }
                                )
                            )
                            .then(CommandManager.argument("name", StringArgumentType.word())
                                .suggests(::suggestOnlinePlayerNames)
                                .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                                    .executes { ctx -> pointsAdjust(ctx, give = true) }
                                )
                            )
                        )
                        .then(CommandManager.literal("take")
                            .requires { MarketPermissions.isServerOwner(it) || it.hasPermissionLevel(2) }
                            // `@s` 字面量分支：理由同 `give`（两条对称）
                            .then(CommandManager.literal("@s")
                                .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                                    .executes { ctx -> pointsAdjustSelf(ctx, give = false) }
                                )
                            )
                            .then(CommandManager.argument("name", StringArgumentType.word())
                                .suggests(::suggestOnlinePlayerNames)
                                .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                                    .executes { ctx -> pointsAdjust(ctx, give = false) }
                                )
                            )
                        )
                        .then(CommandManager.literal("redeem")
                            // 兑换：**所有玩家可用、无权限节点**（码本身就是凭证）。
                            // ⚠ 玩家侧以后优先走 GUI（命令输入会进聊天记录与服务器日志，而码 = 钱），
                            // 命令这条路留着是为了服务器后台/客服代兑
                            .then(CommandManager.argument("code", StringArgumentType.word())
                                .executes(::pointsRedeem)
                            )
                        )
                        .then(CommandManager.literal("code")
                            // 生成/作废兑换码 = 印钞，判 **points.code 节点**（2026-09-25 从 OP 改判）——
                            // 与 give/take 不同：兑换码是**有凭证**的发放（码本身就是凭据、可审计、可限定
                            // 次数与有效期），适合下放给负责发码的客服；give/take 才是无凭证直发
                            .requires { MarketPermissions.has(it, MarketPermissions.POINTS_CODE) }
                            .then(CommandManager.literal("create")
                                .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                                    .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 100))
                                        .then(CommandManager.argument("uses", IntegerArgumentType.integer(1))
                                            .then(CommandManager.argument("days", IntegerArgumentType.integer(0))
                                                // days = 0 表示不过期（⚠ 不过期的码不能靠 serial 清理，见方案）
                                                .executes { ctx -> pointsCodeCreate(ctx, null) }
                                                .then(CommandManager.argument("bound", StringArgumentType.word())
                                                    .suggests(::suggestOnlinePlayerNames)
                                                    .executes { ctx -> pointsCodeCreate(ctx, StringArgumentType.getString(ctx, "bound")) }
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                            .then(CommandManager.literal("list")
                                .executes(::pointsCodeList)
                            )
                            .then(CommandManager.literal("revoke")
                                .then(CommandManager.argument("code", StringArgumentType.word())
                                    .executes(::pointsCodeRevoke)
                                )
                            )
                            .then(CommandManager.literal("purge")
                                .executes(::pointsCodePurge)
                            )
                        )
                        .then(CommandManager.literal("check")
                            // 查自己：**人人可用**（只读自己的数据，没有理由要权限）
                            .executes { ctx -> pointsCheck(ctx, null) }
                            // 查别人：points.check 节点（可下放给客服做核对）；控制台执行时自带此权限
                            .then(CommandManager.argument("name", StringArgumentType.word())
                                .requires { MarketPermissions.has(it, MarketPermissions.POINTS_CHECK) }
                                .suggests(::suggestOnlinePlayerNames)
                                .executes { ctx -> pointsCheck(ctx, StringArgumentType.getString(ctx, "name")) }
                            )
                        )
                        .then(CommandManager.literal("shop")
                            // 下单/我已支付/取消：玩家可用（正式路径走RP 商城界面，命令留给后台与客服代操作）
                            .then(CommandManager.literal("create")
                                .then(CommandManager.argument("tier", IntegerArgumentType.integer(1))
                                    .executes(::shopCreate)
                                )
                            )
                            .then(CommandManager.literal("paid")
                                .then(CommandManager.argument("order", StringArgumentType.word())
                                    .executes(::shopMarkPaid)
                                )
                            )
                            .then(CommandManager.literal("cancel")
                                .then(CommandManager.argument("order", StringArgumentType.word())
                                    .executes(::shopCancel)
                                )
                            )
                            // ⚠ 下面四个 = 充值订单的**管理侧**（核对收款、发放、作废、清理），
                            //   判 **points.order 节点**（2026-09-25 从「仅服主」改判为可授予，用户拍板）。
                            //   ⚠ **确认 = 加余额**（发钞权）：授予前先确认对方真的**看得到收款账单**，
                            //   否则「确认到账」这个动作本身不成立（docs/permission_nodes_plan.md ⑩）。
                            //   ⚠ 界面的充值订单管理端判**同一个节点**，两处必须一起改。
                            //   `has()` 不认 OP 且**控制台自带此权限**，所以不需要再套 denyNonOwner
                            .then(CommandManager.literal("list")
                                .requires { MarketPermissions.has(it, MarketPermissions.POINTS_ORDER) }
                                .executes(::shopPendingList)
                            )
                            .then(CommandManager.literal("confirm")
                                .requires { MarketPermissions.has(it, MarketPermissions.POINTS_ORDER) }
                                .then(CommandManager.argument("order", StringArgumentType.word())
                                    .executes(::shopConfirm)
                                )
                            )
                            .then(CommandManager.literal("reject")
                                .requires { MarketPermissions.has(it, MarketPermissions.POINTS_ORDER) }
                                .then(CommandManager.argument("order", StringArgumentType.word())
                                    .executes(::shopReject)
                                )
                            )
                            .then(CommandManager.literal("purge")
                                .requires { MarketPermissions.has(it, MarketPermissions.POINTS_ORDER) }
                                .executes(::shopPurge)
                            )
                        )
                    )
                    .then(CommandManager.literal("owner")
                        // ⚠⭐ **add / remove 已移除**（2026-10-01 定）：改名单位置**只剩配置文件**一个 ——
                        //   能在服务端"执行一条命令"的东西太多（工具模组 / 自动化 / 别的模组顺手调 API），
                        //   它们都不该能提权；配置文件则是**文件系统**这道真边界。
                        //   改完配置 `/market reload` 即生效（它会重读名单并给所有在线玩家重推权限快照）。
                        // ⚠ 只留 list（只读名单 + UUID → 玩家名解析）：服主排障用。
                        // ⚠ 判**服主**、不判 OP —— 项目铁律：模组内 OP 一律按普通玩家对待
                        //   （控制台天然满足 isServerOwner）。
                        .requires { MarketPermissions.isServerOwner(it) }
                        .then(CommandManager.literal("list")
                            .executes(::ownerList)
                        )
                    )
            )
        }

        // 登录补发市场开关状态：客户端据此把入口按钮置灰（开关是全局的，登录时必须同步一次）
        onPlayerJoin { player ->
            sendToPlayer(player, MarketStatePayload(CobbleMarketConfig.marketEnabled))
            // 待领取概况：客户端首次打开市场入口界面时据此播「离线收益」提示音
            com.shusheng.cobblemarket.network.sendPendingReturns(player, player.server)
        }
    }

    /**
     * 兑换RP（命令入口）。
     *
     * 逻辑全在 [PointRedeemService]（**GUI 走同一个内核，不另写一份** —— 方案风险清单第 12 条）；
     * 这里只负责把结果翻成命令反馈。
     */
    private fun pointsRedeem(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = source.player
        if (player == null) {
            source.sendError(Text.translatable("cobblemarket.points.players_only"))
            return 0
        }
        val raw = StringArgumentType.getString(context, "code")
        source.server.execute {
            when (val outcome = PointRedeemService.redeem(source.server, player, raw)) {
                is RedeemOutcome.Success -> source.sendFeedback({
                    Text.translatable(
                        "cobblemarket.points.redeem_ok",
                        CurrencyService.amountText(outcome.amount, MarketCurrency.POINTS),
                        CurrencyService.amountText(outcome.balanceAfter, MarketCurrency.POINTS)
                    ).formatted(Formatting.GREEN)
                }, false)
                RedeemOutcome.Locked -> source.sendError(Text.translatable("cobblemarket.points.redeem_locked"))
                RedeemOutcome.Invalid -> source.sendError(Text.translatable("cobblemarket.points.redeem_invalid"))
                // 节流：静默丢弃（防脚本连点，不值得回一条消息）
                RedeemOutcome.Throttled -> {}
            }
        }
        return 1
    }

    /** 生成本地兑换码（仅 OP）。`days = 0` 表示不过期；`bound` 非空时只有该玩家能兑 */
    private fun pointsCodeCreate(context: CommandContext<ServerCommandSource>, boundName: String?): Int {
        val source = context.source
        val server = source.server
        val amount = IntegerArgumentType.getInteger(context, "amount").toLong()
        val count = IntegerArgumentType.getInteger(context, "count")
        val uses = IntegerArgumentType.getInteger(context, "uses")
        val days = IntegerArgumentType.getInteger(context, "days")
        if (amount <= 0 || count <= 0 || uses <= 0 || days < 0) {
            source.sendError(Text.translatable("cobblemarket.points.invalid_amount"))
            return 0
        }
        val now = System.currentTimeMillis()
        val expiresAt = if (days <= 0) null else now + days * 24L * 60 * 60 * 1000
        var boundUuid: java.util.UUID? = null
        var boundNameResolved: String? = null
        if (boundName != null) {
            val resolved = BanState.resolvePlayer(server, boundName)
            if (resolved == null) {
                source.sendError(Text.translatable("cobblemarket.ban.player_not_found", boundName))
                return 0
            }
            boundUuid = resolved.first
            boundNameResolved = resolved.second
        }
        val state = PointCodeState.get(server)
        val created = mutableListOf<String>()
        repeat(count) {
            val entry = state.create(amount, uses, expiresAt, source.name, boundUuid, boundNameResolved, now)
            if (entry != null) created.add(entry.code)
        }
        if (created.isEmpty()) {
            source.sendError(Text.translatable("cobblemarket.points.code_create_failed"))
            return 0
        }
        // 生成码本身不动任何人余额（余额列写 0）：单记 CODE_CREATE 类型，不污染「发放」口径
        created.forEach { code ->
            PointsAuditLogger.log(
                PointsLogType.CODE_CREATE, boundUuid ?: java.util.UUID(0, 0), boundNameResolved ?: "-",
                amount * uses, 0, source.name,
                "生成兑换码 ${PointCodes.format(code)}（面额 $amount × $uses 次，过期 ${if (expiresAt == null) "永不" else "见配置"}）",
                detailEn = "Generated code ${PointCodes.format(code)} (value $amount x $uses uses, expires ${if (expiresAt == null) "never" else "per config"})"
            )
        }
        // ≤10 张直接回显；更多只报数量（导出文件的能力在 B7 的 GUI/命令里补）
        if (created.size <= 10) {
            created.forEach { code ->
                source.sendFeedback({ Text.literal(PointCodes.format(code)) }, false)
            }
        }
        source.sendFeedback({
            Text.translatable("cobblemarket.points.code_created", created.size).formatted(Formatting.GREEN)
        }, true)
        return 1
    }

    /** 列出未用尽的兑换码（仅 OP）；码本身脱敏展示，服主可另在生成时记录 */
    private fun pointsCodeList(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val now = System.currentTimeMillis()
        val list = PointCodeState.get(source.server).all().filter { it.isUsable(now) }
        if (list.isEmpty()) {
            source.sendFeedback({ Text.translatable("cobblemarket.points.code_list_empty") }, false)
            return 1
        }
        source.sendFeedback({
            Text.translatable("cobblemarket.points.code_list_header", list.size)
        }, false)
        list.take(20).forEach { entry ->
            source.sendFeedback({
                Text.literal(
                    "${PointCodes.format(entry.code)}  ${entry.amount}  " +
                        "${entry.remainingUses}/${entry.totalUses}" +
                        (entry.boundPlayerName?.let { "  → $it" } ?: "")
                )
            }, false)
        }
        return 1
    }

    /** 作废一张兑换码（仅 OP） */
    private fun pointsCodeRevoke(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val raw = StringArgumentType.getString(context, "code")
        val code = PointCodes.normalize(raw)
        if (code == null || !PointCodeState.get(source.server).revoke(code)) {
            source.sendError(Text.translatable("cobblemarket.points.code_not_found"))
            return 0
        }
        PointsAuditLogger.log(
            PointsLogType.CODE_REVOKE, java.util.UUID(0, 0), "-", 0, 0, source.name,
            "作废兑换码 ${PointCodes.format(code)}",
            detailEn = "Revoked code ${PointCodes.format(code)}"
        )
        source.sendFeedback({ Text.translatable("cobblemarket.points.code_revoked").formatted(Formatting.GREEN) }, true)
        return 1
    }

    /** 清理已用尽 / 已过期的码（仅 OP） */
    private fun pointsCodePurge(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val removed = PointCodeState.get(source.server).purgeExpired(System.currentTimeMillis())
        source.sendFeedback({ Text.translatable("cobblemarket.points.code_purged", removed).formatted(Formatting.GREEN) }, true)
        return 1
    }

    // ── RP：命令发放 / 回收 / 查询 ──

    // ── `/market points give|take` 的两条分支（真正的实现在 [pointsApply]）──
    //
    // ⚠ **仅服主**：无凭证的即时直发 / 回收，属服主干预手段，同「撤销坏账」。
    //   命令树的 `requires` 带 OP 只为让游戏内 OP 敲得进来看到红字，**真鉴权是各分支首行的 `denyNonOwner`**
    //   ⇒ OP 也发不了（只有服主与控制台可以）。
    // ⚠ 金额下界由命令参数 `integer(1)` 把关（**负数会让"扣款"变成"发钱"**），
    //   [pointsApply] 里再兜一层，防以后改参数类型时漏掉。
    // ⚠ 每一次变动都写审计 CSV（RP 的来源由服主决定，可能是真钱买的，必须可对账）。
    // ⚠ **FTB 任务奖励**走 `@s` 那条分支。⚠ **别写成"FTB 以控制台身份执行"** —— FTB 不是控制台
    //   （用户 2026-09-28 实测：等级设到 4 也执行不了「仅限控制台」的命令），它是「玩家身份 + N 级权限」
    //   ⇒ 执行者是**完成任务的普通玩家**（权限等级由那条奖励命令自己配）。
    //   ⚠ 开关 `rpAsQuestCurrency` 打开时放行（见下方 [denyPointsGrant]）；判据要 `hasPermissionLevel(2)`
    //     ⇒ **FTB 那边运行等级得设 2 及以上**（0/1 会被拒）；⚠ 代价是**真 OP 手敲也能发**。

    /** `/market points give|take <玩家名> <数量>` —— 按玩家名（可离线：走 [BanState.resolvePlayer]） */
    private fun pointsAdjust(context: CommandContext<ServerCommandSource>, give: Boolean): Int {
        if (denyPointsGrant(context)) return 0
        val name = StringArgumentType.getString(context, "name")
        val resolved = BanState.resolvePlayer(context.source.server, name)
        if (resolved == null) {
            context.source.sendError(Text.translatable("cobblemarket.ban.player_not_found", name))
            return 0
        }
        return pointsApply(context, give, resolved.first, resolved.second)
    }

    /**
     * `/market points give|take @s <数量>` —— 目标 = **执行者自己**。
     *
     * ⚠ **FTB 任务奖励走的就是这条**（`/market points give @s N`：玩家做完任务自动到账，
     * 不需要市场管理员 / 服主手动发）。FTB 的命令奖励在 1.20+ 只有"以玩家身份 + N 级权限"
     * 一种形态（旧的"以控制台执行"选项已被移除 —— 用户 2026-09-28 实测：等级设到 4 也执行不了
     * 「仅限控制台」的命令），所以 `@s` 恰好指"完成这个任务的那位玩家"。
     */
    private fun pointsAdjustSelf(context: CommandContext<ServerCommandSource>, give: Boolean): Int {
        if (denyPointsGrant(context)) return 0
        val player = context.source.player
        if (player == null) {
            context.source.sendError(Text.translatable("cobblemarket.points.players_only"))
            return 0
        }
        return pointsApply(context, give, player.uuid, player.name.string)
    }

    /** 发放 / 回收的公共实现（两条分支共用；**鉴权由调用方先做**） */
    private fun pointsApply(
        context: CommandContext<ServerCommandSource>,
        give: Boolean,
        uuid: java.util.UUID,
        playerName: String
    ): Int {
        val source = context.source
        val server = source.server
        val amount = IntegerArgumentType.getInteger(context, "amount").toLong()
        if (amount <= 0) {
            source.sendError(Text.translatable("cobblemarket.points.invalid_amount"))
            return 0
        }
        val state = PointsState.get(server)
        // 操作者写命令执行者名字（控制台执行也留痕，能查出是谁发的）
        val operator = source.name
        if (give) {
            val after = state.addBalance(uuid, amount)
            PointsAuditLogger.log(PointsLogType.GIVE, uuid, playerName, amount, after, operator, "命令发放", detailEn = "Granted via command")
            // 到账反馈给**玩家本人**（不是执行者）：命令发放与 FTB 任务奖励都走这条路，
            // 只回执给 OP 的话玩家那边毫无动静 —— 钱进来了得让他知道。
            // ⚠ 离线玩家不发（没法播音），余额本身已经加上了
            server.playerManager.getPlayer(uuid)?.let { target ->
                sendSound(target, "points_in")
                // 玩家自己给自己发时不重复刷屏（下面的 sendFeedback 已经回执了）
                if (target.uuid != source.player?.uuid) {
                    target.sendMessage(
                        Text.translatable(
                            "cobblemarket.points.received",
                            CurrencyService.amountText(amount, MarketCurrency.POINTS),
                            CurrencyService.amountText(after, MarketCurrency.POINTS)
                        ).formatted(Formatting.GREEN),
                        false
                    )
                }
            }
            source.sendFeedback({
                Text.translatable(
                    "cobblemarket.points.give_ok",
                    CurrencyService.amountText(amount, MarketCurrency.POINTS),
                    playerName,
                    CurrencyService.amountText(after, MarketCurrency.POINTS)
                ).formatted(Formatting.GREEN)
            }, true)
            return 1
        }
        if (!state.removeBalance(uuid, amount)) {
            source.sendError(
                Text.translatable(
                    "cobblemarket.points.not_enough", playerName,
                    CurrencyService.amountText(state.getBalance(uuid), MarketCurrency.POINTS)
                )
            )
            return 0
        }
        val after = state.getBalance(uuid)
        PointsAuditLogger.log(PointsLogType.TAKE, uuid, playerName, amount, after, operator, "命令回收", detailEn = "Taken via command")
        source.sendFeedback({
            Text.translatable(
                "cobblemarket.points.take_ok",
                playerName,
                CurrencyService.amountText(amount, MarketCurrency.POINTS),
                CurrencyService.amountText(after, MarketCurrency.POINTS)
            ).formatted(Formatting.GREEN)
        }, true)
        return 1
    }

    /** 查询RP 余额；[name] 为 null 时查执行者自己（控制台没有"自己"，提示必须给名字） */
    private fun pointsCheck(context: CommandContext<ServerCommandSource>, name: String?): Int {
        val source = context.source
        val server = source.server
        if (name == null) {
            val self = source.player
            if (self == null) {
                source.sendError(Text.translatable("cobblemarket.points.name_required"))
                return 0
            }
            val balance = PointsState.get(server).getBalance(self.uuid)
            source.sendFeedback({
                Text.translatable(
                    "cobblemarket.points.check_self",
                    CurrencyService.amountText(balance, MarketCurrency.POINTS)
                )
            }, false)
            return 1
        }
        val resolved = BanState.resolvePlayer(server, name)
        if (resolved == null) {
            source.sendError(Text.translatable("cobblemarket.ban.player_not_found", name))
            return 0
        }
        val (uuid, playerName) = resolved
        val balance = PointsState.get(server).getBalance(uuid)
        source.sendFeedback({
            Text.translatable(
                "cobblemarket.points.check_other", playerName,
                CurrencyService.amountText(balance, MarketCurrency.POINTS)
            )
        }, false)
        return 1
    }

    // ── RP：游戏内充值订单（正式路径走RP 商城界面，命令用于后台与客服代操作） ──

    /** 创建充值订单。档位序号对玩家从 1 开始数（配置数组从 0 开始） */
    private fun shopCreate(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = source.player
        if (player == null) {
            source.sendError(Text.translatable("cobblemarket.points.players_only"))
            return 0
        }
        val tierIndex = IntegerArgumentType.getInteger(context, "tier") - 1
        return when (val result = PointShopService.createOrder(source.server, player.uuid, player.name.string, tierIndex)) {
            is PointShopResult.Success -> {
                val order = result.order
                source.sendFeedback({
                    Text.translatable(
                        "cobblemarket.points.shop.created",
                        PointOrderNumbers.format(order.orderNo),
                        PointShopService.formatCents(order.payCents),
                        CurrencyService.amountText(order.points, MarketCurrency.POINTS)
                    ).formatted(Formatting.GREEN)
                }, false)
                1
            }
            is PointShopResult.Failure -> {
                source.sendError(pointShopErrorText(result.error))
                0
            }
        }
    }

    /** 玩家声明「我已支付」（**只是声明，不是证据** —— 发不发由服主对着账单说了算） */
    private fun shopMarkPaid(context: CommandContext<ServerCommandSource>): Int =
        shopPlayerAction(context) { server, player, orderNo ->
            PointShopService.markPaid(server, player.uuid, orderNo)
        }

    /** 玩家取消自己的待支付订单 */
    private fun shopCancel(context: CommandContext<ServerCommandSource>): Int =
        shopPlayerAction(context) { server, player, orderNo ->
            PointShopService.cancelOrder(server, player.uuid, orderNo)
        }

    /** 玩家侧订单操作的公共外壳：取玩家 → 归一化订单号 → 调服务 → 回显 */
    private fun shopPlayerAction(
        context: CommandContext<ServerCommandSource>,
        action: (net.minecraft.server.MinecraftServer, net.minecraft.server.network.ServerPlayerEntity, String) -> PointShopResult
    ): Int {
        val source = context.source
        val player = source.player
        if (player == null) {
            source.sendError(Text.translatable("cobblemarket.points.players_only"))
            return 0
        }
        val orderNo = PointOrderNumbers.normalize(StringArgumentType.getString(context, "order"))
        if (orderNo == null) {
            source.sendError(Text.translatable("cobblemarket.points.shop.not_found"))
            return 0
        }
        return when (val result = action(source.server, player, orderNo)) {
            is PointShopResult.Success -> {
                val messageKey = if (result.order.status == PointOrderStatus.PENDING_CONFIRM) {
                    "cobblemarket.points.shop.paid_ok"
                } else {
                    "cobblemarket.points.shop.cancelled"
                }
                source.sendFeedback({
                    Text.translatable(messageKey, PointOrderNumbers.format(result.order.orderNo)).formatted(Formatting.GREEN)
                }, false)
                1
            }
            is PointShopResult.Failure -> {
                source.sendError(pointShopErrorText(result.error))
                0
            }
        }
    }

    /** 待确认订单列表（判 `points.order`）：玩家名 / 实付金额 / 订单号 / 已等待时长，照着收款账单逐笔核 */
    private fun shopPendingList(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val list = PointOrderState.get(source.server).pendingConfirm()
        if (list.isEmpty()) {
            source.sendFeedback({ Text.translatable("cobblemarket.points.shop.list_empty") }, false)
            return 1
        }
        source.sendFeedback({ Text.translatable("cobblemarket.points.shop.list_header", list.size) }, false)
        val now = System.currentTimeMillis()
        list.take(20).forEach { order ->
            val waitedMinutes = ((now - (order.paidAt ?: order.createdAt)) / 60_000L).coerceAtLeast(0L)
            source.sendFeedback({
                Text.literal(
                    "${PointOrderNumbers.format(order.orderNo)}  ${order.playerName}  " +
                        "${PointShopService.formatCents(order.payCents)}  ${order.points}  ${waitedMinutes}m"
                )
            }, false)
        }
        return 1
    }

    /** 确认发放（判 `points.order`，幂等：重复确认不会二次发放） */
    private fun shopConfirm(context: CommandContext<ServerCommandSource>): Int =
        shopOperatorAction(context) { server, operatorUuid, operator, orderNo ->
            PointShopService.confirm(server, operatorUuid, operator, orderNo)
        }

    /** 作废订单（判 `points.order`）：核对账单后没这笔钱、重复提交、误操作等 */
    private fun shopReject(context: CommandContext<ServerCommandSource>): Int =
        shopOperatorAction(context) { server, _, operator, orderNo ->
            PointShopService.reject(server, operator, orderNo)
        }

    /** 订单操作的公共外壳（鉴权在命令树的 `requires` 里，两个入口都走它） */
    private fun shopOperatorAction(
        context: CommandContext<ServerCommandSource>,
        action: (net.minecraft.server.MinecraftServer, java.util.UUID?, String, String) -> PointShopResult
    ): Int {
        val source = context.source
        val orderNo = PointOrderNumbers.normalize(StringArgumentType.getString(context, "order"))
        if (orderNo == null) {
            source.sendError(Text.translatable("cobblemarket.points.shop.not_found"))
            return 0
        }
        // ⚠ 传执行者 uuid（控制台/命令方块为 null）—— `confirm` 靠它拦「确认自己的订单」
        return when (val result = action(source.server, source.player?.uuid, source.name, orderNo)) {
            is PointShopResult.Success -> {
                val granted = result.order.status == PointOrderStatus.GRANTED
                source.sendFeedback({
                    Text.translatable(
                        if (granted) "cobblemarket.points.shop.confirmed" else "cobblemarket.points.shop.rejected_ok",
                        PointOrderNumbers.format(result.order.orderNo),
                        result.order.playerName
                    ).formatted(Formatting.GREEN)
                }, true)
                1
            }
            is PointShopResult.Failure -> {
                source.sendError(pointShopErrorText(result.error))
                0
            }
        }
    }

    /** 清理已完结订单（判 `points.order`；保留期内留作对账，正常由服务端定时任务自动做） */
    private fun shopPurge(context: CommandContext<ServerCommandSource>): Int {
        val removed = PointShopService.purgeFinished(context.source.server)
        context.source.sendFeedback({ Text.translatable("cobblemarket.points.shop.purged", removed).formatted(Formatting.GREEN) }, true)
        return 1
    }

    private fun marketOn(context: CommandContext<ServerCommandSource>): Int = setMarketEnabled(context, true)

    /** 卡种联想：purple / black */
    private fun suggestCardKinds(
        context: CommandContext<ServerCommandSource>,
        builder: com.mojang.brigadier.suggestion.SuggestionsBuilder
    ): java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> =
        builder.suggest("purple").suggest("black").buildFuture()

    /** 在线玩家名联想（revoke 名字分支；离线名由服主手输全名） */
    private fun suggestOnlinePlayerNames(
        context: CommandContext<ServerCommandSource>,
        builder: com.mojang.brigadier.suggestion.SuggestionsBuilder
    ): java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
        context.source.server.playerManager.playerList.forEach { builder.suggest(it.name.string) }
        return builder.buildFuture()
    }

    /** 读取卡种参数（缺省/非法值一律 purple；"black" 才是黑卡） */
    private fun readCardKind(context: CommandContext<ServerCommandSource>): String =
        try {
            context.getArgument("kind", String::class.java).lowercase()
        } catch (_: Exception) {
            "purple"
        }

    private fun cardNameOf(kind: String): Text = Text.translatable(
        if (kind == "black") "cobblemarket.card.black_name" else "cobblemarket.card.purple_name"
    )

    /** /market card give <玩家|选择器> [purple|black]：发喵喵紫卡/黑卡（支持 @p/@s 选择器与玩家名联想；限额检查） */
    private fun cardGive(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val server = source.server
        val player = try {
            EntityArgumentType.getPlayer(context, "player")
        } catch (_: Exception) {
            source.sendError(Text.translatable("cobblemarket.ban.player_not_found", "?"))
            return 0
        }
        val kind = readCardKind(context)
        val black = kind == "black"
        val cardName = cardNameOf(kind)
        val state = com.shusheng.cobblemarket.finance.FinanceState.get(server)
        val isHolder = if (black) state.isBlackCardHolder(player.uuid) else state.isPurpleCardHolder(player.uuid)
        if (isHolder) {
            source.sendError(Text.translatable("cobblemarket.card.already_holder", player.name.string, cardName))
            return 0
        }
        // 升级替代互斥：给黑卡持有者发紫卡视为降级，拒绝并提示先收回黑卡
        if (!black && state.isBlackCardHolder(player.uuid)) {
            source.sendError(Text.translatable("cobblemarket.card.black_holder_block_purple", player.name.string))
            return 0
        }
        val max = if (black) com.shusheng.cobblemarket.config.CobbleMarketConfig.blackCardCount
        else com.shusheng.cobblemarket.config.CobbleMarketConfig.purpleCardCount
        val count = if (black) state.getBlackCardHolderCount() else state.getPurpleCardHolderCount()
        if (max > 0 && count >= max) {
            source.sendError(Text.translatable("cobblemarket.card.cap_reached", max))
            return 0
        }
        val item = net.minecraft.registry.Registries.ITEM.get(
            com.shusheng.cobblemarket.CobbleMarket.id(if (black) "meowth_black_card" else "meowth_purple_card")
        )
        // 背包满则拒绝（卡落地会被扫描清除，等同没发）
        if (player.inventory.getEmptySlot() == -1) {
            source.sendError(Text.translatable("cobblemarket.card.inventory_full"))
            return 0
        }
        if (black) {
            state.addBlackCardHolder(player.uuid, source.name, player.name.string)
            // 黑卡是紫卡的升级替代：获得黑卡自动移除紫卡资格，并主动清除背包紫卡（自检有 OP 豁免）
            state.removePurpleCardHolder(player.uuid, source.name, player.name.string)
            com.shusheng.cobblemarket.finance.FinanceService.clearPurpleCardItems(player)
        } else {
            state.addPurpleCardHolder(player.uuid, source.name, player.name.string)
        }
        com.shusheng.cobblemarket.network.FinanceNetwork.broadcastCardHolderBoard(server)
        player.inventory.insertStack(net.minecraft.item.ItemStack(item))
        com.shusheng.cobblemarket.network.CelebrationNetwork.sendCard(player, if (black) "black" else "purple")
        com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
        source.sendFeedback(
            { Text.translatable("cobblemarket.card.given", player.name.string, cardName).formatted(Formatting.GREEN) },
            false
        )
        return 1
    }

    /** /market card revoke <玩家|选择器> [purple|black]：收回紫卡/黑卡（状态删除即额度作废；物品凭证无需回收） */
    private fun cardRevoke(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val server = source.server
        val player = try {
            EntityArgumentType.getPlayer(context, "player")
        } catch (_: Exception) {
            source.sendError(Text.translatable("cobblemarket.ban.player_not_found", "?"))
            return 0
        }
        val kind = readCardKind(context)
        val black = kind == "black"
        val cardName = cardNameOf(kind)
        val state = com.shusheng.cobblemarket.finance.FinanceState.get(server)
        val removed = if (black) state.removeBlackCardHolder(player.uuid, source.name, player.name.string)
        else state.removePurpleCardHolder(player.uuid, source.name, player.name.string)
        if (!removed) {
            source.sendError(Text.translatable("cobblemarket.card.not_holder", player.name.string, cardName))
            return 0
        }
        com.shusheng.cobblemarket.network.FinanceNetwork.broadcastCardHolderBoard(server)
        com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
        source.sendFeedback(
            { Text.translatable("cobblemarket.card.revoked", player.name.string, cardName).formatted(Formatting.GREEN) },
            false
        )
        return 1
    }

    /** /market card revoke <名字> [purple|black]：离线也能收回（照封禁管理：在线 playerManager → 离线 userCache） */
    private fun cardRevokeByName(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val server = source.server
        val name = StringArgumentType.getString(context, "name")
        val kind = readCardKind(context)
        val black = kind == "black"
        val cardName = cardNameOf(kind)
        val state = com.shusheng.cobblemarket.finance.FinanceState.get(server)
        val holders = if (black) state.getAllBlackCardHolders() else state.getAllPurpleCardHolders()
        // 解析顺序：在线 → userCache 名字查找 → 持有者集合内按「list 同款名字来源」反查兜底
        // （findByName 偶有查不到但 getByUuid 能查到的缓存不一致，list 能显示的名字 revoke 必须能收）
        fun displayNameOf(uuid: java.util.UUID): String? =
            server.playerManager.getPlayer(uuid)?.name?.string
                ?: server.userCache?.getByUuid(uuid)?.orElse(null)?.name
        val uuid = server.playerManager.getPlayer(name)?.uuid
            ?: server.userCache?.findByName(name)?.orElse(null)?.id
            ?: holders.firstOrNull { u -> displayNameOf(u)?.equals(name, ignoreCase = true) == true }
        if (uuid == null) {
            source.sendError(Text.translatable("cobblemarket.ban.player_not_found", name))
            return 0
        }
        val targetName = displayNameOf(uuid) ?: name
        val removed = if (black) state.removeBlackCardHolder(uuid, source.name, targetName)
        else state.removePurpleCardHolder(uuid, source.name, targetName)
        if (!removed) {
            source.sendError(Text.translatable("cobblemarket.card.not_holder", name, cardName))
            return 0
        }
        com.shusheng.cobblemarket.network.FinanceNetwork.broadcastCardHolderBoard(server)
        com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
        source.sendFeedback(
            { Text.translatable("cobblemarket.card.revoked", name, cardName).formatted(Formatting.GREEN) },
            false
        )
        return 1
    }

    /** /market card list [purple|black]：列出对应卡种全部持有者（缺省 purple） */
    // ── /market perm：管理权限节点的授予 / 收回 / 查询（服主专属，见 docs/permission_nodes_plan.md） ──

    /** 节点名补全：给出**去前缀的短名**（命令里短名与全名都收） */
    private fun suggestPermNodes(
        context: CommandContext<ServerCommandSource>,
        builder: com.mojang.brigadier.suggestion.SuggestionsBuilder
    ): java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
        MarketPermissions.ALL.forEach { builder.suggest(it.removePrefix(MarketPermissions.PREFIX)) }
        return builder.buildFuture()
    }

    /** 节点名容错：短名（blacklist）与全名（cobblemarket.admin.blacklist）都收；非法名返回 null */
    private fun resolveNode(raw: String): String? {
        val full = if (raw.startsWith(MarketPermissions.PREFIX)) raw else MarketPermissions.PREFIX + raw
        return full.takeIf { it in MarketPermissions.ALL }
    }

    private fun permGrant(context: CommandContext<ServerCommandSource>): Int {
        if (denyNonOwner(context)) return 0
        val source = context.source
        val node = resolveNode(StringArgumentType.getString(context, "node"))
        if (node == null) {
            source.sendError(Text.translatable("cobblemarket.perm.unknown_node"))
            return 0
        }
        val name = StringArgumentType.getString(context, "player")
        val target = BanState.resolvePlayer(source.server, name)
        if (target == null) {
            source.sendError(Text.translatable("cobblemarket.ban.player_not_found", name))
            return 0
        }
        // 节点名走与界面 / 聊天通知同一套词条：中文环境显示「黑名单」而不是 blacklist。
        // ⚠ 统一传货币图标：含 %s 的词条（如「%s商城」）用它，不含的多余参数会被忽略
        val nodeText = Text.translatable(
            "cobblemarket.perm.node." + node.removePrefix(MarketPermissions.PREFIX),
            CurrencyService.pointsIcon()
        )
        if (!PermissionState.get(source.server).grant(target.first, target.second, node, source.name)) {
            source.sendError(Text.translatable("cobblemarket.perm.already_holding", target.second, nodeText))
            return 0
        }
        // 剩余节点 = 授予后的完整集合；写失败要提醒（别静默丢审计）
        if (!PermissionFileLogger.log(
                source.name, target.second, target.first, setOf(node), emptySet(),
                PermissionState.get(source.server).getNodes(target.first)
            )
        ) {
            // 权限已经授予/收回了，只是审计没记上：用黄色反馈而不是 sendError（红字像"操作失败"）
            source.sendFeedback(
                { Text.translatable("cobblemarket.perm.audit_write_failed").formatted(Formatting.YELLOW) }, false
            )
        }
        // 在线即时生效：推新快照（离线不用管，下次登录补发）
        PermissionNetwork.refresh(source.server, target.first)
        // 权限变更音：给被授予的玩家本人（在线才发；服主给自己加也会响）
        source.server.playerManager.getPlayer(target.first)?.let {
            com.shusheng.cobblemarket.network.sendSound(it, "permission_change")
        }
        PermissionNetwork.notifyChanged(source.server, target.first, setOf(node), emptySet())
        source.sendFeedback({ Text.translatable("cobblemarket.perm.granted", target.second, nodeText).formatted(Formatting.GREEN) }, false)
        return 1
    }

    private fun permRevoke(context: CommandContext<ServerCommandSource>): Int {
        if (denyNonOwner(context)) return 0
        val source = context.source
        val node = resolveNode(StringArgumentType.getString(context, "node"))
        if (node == null) {
            source.sendError(Text.translatable("cobblemarket.perm.unknown_node"))
            return 0
        }
        val name = StringArgumentType.getString(context, "player")
        val target = BanState.resolvePlayer(source.server, name)
        if (target == null) {
            source.sendError(Text.translatable("cobblemarket.ban.player_not_found", name))
            return 0
        }
        // 同上：节点名用本地化词条
        val nodeText = Text.translatable("cobblemarket.perm.node." + node.removePrefix(MarketPermissions.PREFIX))
        if (!PermissionState.get(source.server).revoke(target.first, node)) {
            source.sendError(Text.translatable("cobblemarket.perm.not_holding", target.second, nodeText))
            return 0
        }
        if (!PermissionFileLogger.log(
                source.name, target.second, target.first, emptySet(), setOf(node),
                PermissionState.get(source.server).getNodes(target.first)
            )
        ) {
            // 权限已经授予/收回了，只是审计没记上：用黄色反馈而不是 sendError（红字像"操作失败"）
            source.sendFeedback(
                { Text.translatable("cobblemarket.perm.audit_write_failed").formatted(Formatting.YELLOW) }, false
            )
        }
        PermissionNetwork.refresh(source.server, target.first)
        // 权限变更音：同上（被收回时也响）
        source.server.playerManager.getPlayer(target.first)?.let {
            com.shusheng.cobblemarket.network.sendSound(it, "permission_change")
        }
        PermissionNetwork.notifyChanged(source.server, target.first, emptySet(), setOf(node))
        source.sendFeedback({ Text.translatable("cobblemarket.perm.revoked", target.second, nodeText).formatted(Formatting.GREEN) }, false)
        return 1
    }

    /** 无参 = 列全部授予记录；带玩家名 = 列该玩家的节点 */
    private fun permList(context: CommandContext<ServerCommandSource>, nameArg: String?): Int {
        if (denyNonOwner(context)) return 0
        val source = context.source
        val state = PermissionState.get(source.server)
        if (nameArg == null) {
            val all = state.getAll()
            if (all.isEmpty()) {
                source.sendFeedback({ Text.translatable("cobblemarket.perm.list_empty").formatted(Formatting.GRAY) }, false)
                return 1
            }
            source.sendFeedback({ Text.translatable("cobblemarket.perm.list_title", all.size).formatted(Formatting.GOLD) }, false)
            all.forEach { grant -> source.sendFeedback({ permLine(grant.playerName, grant.nodes) }, false) }
            return all.size
        }
        val target = BanState.resolvePlayer(source.server, nameArg)
        if (target == null) {
            source.sendError(Text.translatable("cobblemarket.ban.player_not_found", nameArg))
            return 0
        }
        val nodes = state.getNodes(target.first)
        if (nodes.isEmpty()) {
            source.sendFeedback({ Text.translatable("cobblemarket.perm.list_empty_player", target.second).formatted(Formatting.GRAY) }, false)
            return 1
        }
        source.sendFeedback({ permLine(target.second, nodes) }, false)
        return nodes.size
    }

    // ── /market tag remove：撤掉精灵身上的某个标签（只服主） ──

    /**
     * 队伍槽位数（1~6）—— `slot` 参数的上界。
     *
     * ⚠ 命令**够不到 PC**（用户 2026-09-30 认可这个范围）：PC 里是哪只服主本来就看不到、也指不出来
     *  ⇒ 让玩家把有问题的换到队伍里更划算。
     */
    private const val PARTY_SIZE = 6

    /**
     * `/market tag remove <玩家> <槽位> <标签>` —— 撤掉**别人队伍里**某只精灵身上的某个标签。
     *
     * ⚠ **标签参数必填**（用户 2026-10-01 定）：目前只有一个可撤标签、看着多余，
     *   但命令**还没发布过** ⇒ 现在加是零成本；等发布后再加就成了破坏性变更（老用法失效）。
     *   补全只列 [PokemonTags.REMOVABLE_TAGS] 的短名，记不住名字的问题由 Tab 解决。
     *
     * ⭐ **这是唯一的撤销途径**（用户 2026-09-30 定）。标记一旦打上就洗不掉：
     * - `/pokeedit` 撤不了（`=no` 一律不认，那是**有意**的 —— 能被洗掉就等于禁赛失效）
     * - 商城编辑窗那颗开关只管"**以后新造的**"
     * ⇒ 标错了（手滑勾上、卖出去才发现）本来**只能看着**，这条命令补的就是这个洞。
     *
     * ⚠ **只服主**：`denyNonOwner` 首行拦下非服主 —— 它改的是**别人的**精灵。
     * ⚠ 标签只认 [PokemonTags.REMOVABLE_TAGS] 里的（目前 = `illegal`）—— **`shop` 有意不可撤**：
     *   那是"从哪来"的来源标记、只用于追溯，撤掉没有意义（见 `PokemonTags` 的 KDoc）。
     * ⚠ 改完**必须 `onChange()`** —— 那是"这只精灵变了、该存盘"的通知（官方改任何字段都走它，
     *   见 `Pokemon.kt` 里那一串 `onChange(XxxUpdatePacket(...))`）。官方**没有**针对
     *   `customProperties` 的更新包 ⇒ 这里**不发包给客户端**：我们那几处标记显示
     *  （商城格子 / 购买窗）读的都是**服务端下发的商品规格**，跟玩家客户端那份精灵数据无关。
     */
    private fun removePokemonTag(context: CommandContext<ServerCommandSource>): Int {
        if (denyNonOwner(context)) return 0
        // 标签先查表：**不在可撤表里的一律拒绝**（含 `shop` —— 它是来源标记、有意不可撤）。
        // ⚠ 小写化后再查 ⇒ 手打 `Illegal` 也认（补全给的是小写，正常不会打错）
        val rawTag = StringArgumentType.getString(context, "tag")
        val tagName = rawTag.lowercase()
        val tagKey = PokemonTags.REMOVABLE_TAGS[tagName]
        if (tagKey == null) {
            context.source.sendError(
                Text.translatable(
                    "cobblemarket.tag.not_removable",
                    rawTag, PokemonTags.REMOVABLE_TAGS.keys.joinToString(", ")
                )
            )
            return 0
        }
        val target = EntityArgumentType.getPlayer(context, "player")
        val slot = IntegerArgumentType.getInteger(context, "slot")
        // ⚠ 按**槽位下标**取 —— `party[i]`，与 Cobblemon 自己的移动 / 交换处理**同一个取法**
        //  （`MovePartyPokemonHandler` / `SwapPartyPokemonHandler` 都是 `party[position]`）。
        //  别用 `elementAtOrNull`：那是按 Iterable 顺序取的，语义未必等于槽位。
        //  空槽位 / 越界它自己就返回 null，`runCatching` 只是再兜一层（玩家可能刚把那只换走）
        val party = Cobblemon.storage.getParty(target)
        // ⚠ 必须走**方法调用** `get(slot)` —— `PartyStore.get(Int)` **没标 `operator`**，
        //   写成 `party[i]` 编译不过（同类坑见 memory「Cobblemon API 访问坑」）。
        //   参数本身就是槽位下标（0 基），命令给的是 1~6 ⇒ 减一
        val pokemon = runCatching { party.get(slot - 1) }.getOrNull()
        if (pokemon == null) {
            context.source.sendError(Text.translatable("cobblemarket.tag.empty_slot", slot))
            return 0
        }
        if (!PokemonTags.has(pokemon, tagKey)) {
            context.source.sendError(
                Text.translatable(
                    "cobblemarket.tag.not_marked",
                    pokemon.species.translatedName, tagDisplay(tagName)
                )
            )
            return 0
        }
        PokemonTags.remove(pokemon, tagKey)
        pokemon.onChange()
        // ⚠ 成功提示走**绿色**（用户 2026-09-30 指出：聊天框里的成功反馈不能用白字 ——
        //   与本项目其余"成功"反馈同一口径，如 RP 商城上架后的 `points.store.saved`）
        context.source.sendFeedback({
            Text.translatable(
                "cobblemarket.tag.removed",
                target.name, slot, pokemon.species.translatedName, tagDisplay(tagName)
            ).formatted(Formatting.GREEN)
        }, true)
        return 1
    }

    /** 标签的**显示名**（词条）：中文「非法」/ 英文 "Illegal" —— ⚠ 别硬编码，见 [PokemonTags.REMOVABLE_TAGS] */
    private fun tagDisplay(tagName: String): Text = Text.translatable("cobblemarket.tag.name.$tagName")

    // ── /market owner：服主名单（只读的名单查询；改名单位置只剩配置文件，见上方 owner 命令树注释） ──

    /**
     * 服主专属命令的统一守卫：非服主（含游戏内 OP、RCON、命令方块）给红字提示并中止。
     * 用法：`if (denyNonOwner(context)) return 0`。
     * ⚠ requires 里放 OP 进来只为给这句提示，**真正的鉴权在这里**
     */
    private fun denyNonOwner(context: CommandContext<ServerCommandSource>): Boolean {
        if (MarketPermissions.isServerOwner(context.source)) return false
        context.source.sendError(Text.translatable("cobblemarket.perm.owner_only"))
        return true
    }

    /**
     * `/market points give|take` 的守卫：**服主 / 控制台**，或「开关打开 + **玩家身份的** OP（等级 ≥ 2）」。
     *
     * ⚠⚠ **不能复用 [denyNonOwner]** —— 那个是 `perm grant`（权限授予）/ `loan clear`（撤销坏账）
     *   等命令**共用**的；在它里面放行 OP，等于给"给自己加权限"开后门。这里单独一份，只管 RP 的收发。
     *
     * 开关的用途见 [CobbleMarketConfig.rpAsQuestCurrency]：任务模组（FTB 等）要把 RP 当奖励发给玩家，
     * 而它们**以完成任务的普通玩家身份**执行命令 ⇒ 不开这个开关就永远被拒。
     *
     * ⚠ 判据里的 `entity is PlayerEntity` 是**有意的**：命令方块是"非玩家实体"⇒ 天然被排除，
     *   不用再写一套"来源类型"的判断（开关打开也不给命令方块开口子）。
     */
    private fun denyPointsGrant(context: CommandContext<ServerCommandSource>): Boolean {
        val source = context.source
        if (MarketPermissions.isServerOwner(source)) return false
        if (CobbleMarketConfig.rpAsQuestCurrency &&
            source.entity is net.minecraft.entity.player.PlayerEntity &&
            source.hasPermissionLevel(2)
        ) return false
        // 开关关着时给一句**可操作**的提示（服主最常问"FTB 怎么发不了"）。
        // ⚠ 货币单位走 `pointsIcon()`（游戏内文案不出现「RP」字样）；`perm.owner_only` 里没有 `%s`，
        //   多传的参数会被忽略 ⇒ 不必按 key 分支
        source.sendError(Text.translatable(
            if (CobbleMarketConfig.rpAsQuestCurrency) "cobblemarket.perm.owner_only"
            else "cobblemarket.points.grant_owner_only",
            CurrencyService.pointsIcon()
        ))
        return true
    }

    /** `/market owner list`：列出服主名单（名字查不到时只显示 UUID） */
    private fun ownerList(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val server = source.server
        val owners = CobbleMarketConfig.serverOwners
        if (owners.isEmpty()) {
            source.sendFeedback(
                { Text.translatable("cobblemarket.owner.list_empty").formatted(Formatting.YELLOW) },
                false
            )
            return 1
        }
        source.sendFeedback(
            { Text.translatable("cobblemarket.owner.list_header", owners.size).formatted(Formatting.GOLD) },
            false
        )
        owners.forEach { uuid ->
            val name = server.playerManager.getPlayer(uuid)?.name?.string
                ?: server.userCache?.getByUuid(uuid)?.orElse(null)?.name
            source.sendFeedback(
                {
                    if (name != null) Text.literal(" - $name").append(Text.literal(" ($uuid)").formatted(Formatting.GRAY))
                    else Text.literal(" - $uuid")
                },
                false
            )
        }
        return owners.size
    }

    /** 一名玩家的节点行：名字金色 + 本地化节点名白色，按 ALL 顺序稳定排列（不受授予先后影响） */
    private fun permLine(playerName: String, nodes: Set<String>): Text {
        val body = Text.literal("")
        MarketPermissions.ALL.filter { it in nodes }.forEachIndexed { i, node ->
            if (i > 0) body.append(Text.literal(", "))
            body.append(Text.translatable("cobblemarket.perm.node." + node.removePrefix(MarketPermissions.PREFIX)))
        }
        return Text.literal("$playerName: ").formatted(Formatting.GOLD)
            .append(body.formatted(Formatting.WHITE))
    }

    private fun cardList(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val server = source.server
        val kind = readCardKind(context)
        val black = kind == "black"
        val cardName = cardNameOf(kind)
        val state = com.shusheng.cobblemarket.finance.FinanceState.get(server)
        val holders = if (black) state.getAllBlackCardHolders() else state.getAllPurpleCardHolders()
        val max = if (black) com.shusheng.cobblemarket.config.CobbleMarketConfig.blackCardCount
        else com.shusheng.cobblemarket.config.CobbleMarketConfig.purpleCardCount
        // 按加入时间正序（先申请在前，排行感；同刻按名字）
        val sortedHolders = holders.sortedWith(
            compareBy(
                { if (black) state.getBlackCardAddedAt(it) else state.getPurpleCardAddedAt(it) },
                { server.playerManager.getPlayer(it)?.name?.string
                    ?: server.userCache?.getByUuid(it)?.orElse(null)?.name ?: it.toString() }
            )
        )
        source.sendFeedback(
            {
                Text.translatable("cobblemarket.card.list_header", cardName, holders.size, max).formatted(Formatting.GOLD)
            },
            false
        )
        sortedHolders.forEach { uuid ->
            val name = server.playerManager.getPlayer(uuid)?.name?.string
                ?: server.userCache?.getByUuid(uuid)?.orElse(null)?.name ?: uuid.toString()
            source.sendFeedback({ Text.literal(" - $name") }, false)
        }
        return 1
    }

    /** /market loan clear <玩家>：撤销该玩家全部坏账（批次 7 服主干预，与全服流水界面撤销按钮双入口） */
    private fun loanClear(context: CommandContext<ServerCommandSource>): Int {
        if (denyNonOwner(context)) return 0
        val source = context.source
        val server = source.server
        val name = StringArgumentType.getString(context, "player")
        val target = BanState.resolvePlayer(server, name)
        if (target == null) {
            source.sendError(Text.translatable("cobblemarket.ban.player_not_found", name))
            return 0
        }
        val state = FinanceState.get(server)
        val removed = state.clearBadDebts(target.first)
        if (removed.isNotEmpty()) {
            val now = System.currentTimeMillis()
            removed.forEach { record ->
                CreditFileLogger.logLoan(
                    record, LoanLogType.REVOKED,
                    detail = "服主撤销坏账",
                    detailEn = "Bad debt revoked by admin",
                    timestamp = now
                )
            }
            // 撤销后重新评估冻结（坏账冻结随记录消失解除；仍逾期 ≥14 天的贷款保持冻结）
            FinanceService.syncFreeze(server, target.first)
            com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
            source.sendFeedback(
                { Text.translatable("cobblemarket.repay.revoke_success", target.second, removed.size).formatted(Formatting.GREEN) },
                false
            )
        } else {
            source.sendError(Text.translatable("cobblemarket.repay.revoke_none"))
        }
        return 1
    }

    /** 热重载配置：货币除外（运行时切换账本错乱），货币有变更时附提示 */
    private fun reloadConfig(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val server = source.server
        val oldEnabled = CobbleMarketConfig.marketEnabled
        val currencyChanged = CobbleMarketConfig.reload()
        // 市场总开关随重载变化时即时广播（与 /market on/off 同一机制）
        if (CobbleMarketConfig.marketEnabled != oldEnabled) {
            com.shusheng.cobblemarket.network.toggleMarketEnabled(server, CobbleMarketConfig.marketEnabled)
        }
        // ⚠⚠ **服主名单是配置项，reload 后必须重推权限快照**（2026-09-23 用户实测踩到）——
        //   只 reload 的话：**服务端**立刻按新名单判定，但**客户端**的 ClientPermissionCache
        //   还拿着登录时那份旧的（不是服主、节点为空）⇒ 界面入口照旧点不动，
        //   玩家会以为"改了配置没生效"，只能**退出存档重进**才有反应。
        //   ⚠ 单机 / 局域网正是靠这条路径把自己加进名单的（没有控制台），踩中的人不会少。
        //   改配置可能影响任何人（名单/节点都可能变），所以**给全部在线玩家重推**。
        server.playerManager.playerList.forEach { PermissionNetwork.refresh(server, it.uuid) }
        source.sendFeedback({
            Text.translatable("cobblemarket.market.cmd_reload").formatted(Formatting.GREEN)
                .append(
                    if (currencyChanged)
                        Text.translatable("cobblemarket.market.cmd_reload_currency").formatted(Formatting.GOLD)
                    else Text.literal("")
                )
        }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun marketOff(context: CommandContext<ServerCommandSource>): Int = setMarketEnabled(context, false)

    private fun setMarketEnabled(context: CommandContext<ServerCommandSource>, enabled: Boolean): Int {
        val source = context.source
        val server = source.server
        // 落盘 + 全员广播（与入口界面按钮共用同一函数）
        com.shusheng.cobblemarket.network.toggleMarketEnabled(server, enabled)
        source.sendFeedback(
            { Text.translatable(if (enabled) "cobblemarket.market.cmd_on" else "cobblemarket.market.cmd_off").formatted(Formatting.GREEN) },
            true
        )
        return Command.SINGLE_SUCCESS
    }

    private fun openGui(context: CommandContext<ServerCommandSource>): Int {
        val player = context.source.playerOrThrow
        MarketNetwork.openScreen(player)
        return 1
    }

    private fun banPlayer(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val server = source.server
        val name = StringArgumentType.getString(context, "player")
        val durationStr = try { StringArgumentType.getString(context, "duration") } catch (_: Exception) { null }
        val reason = try { StringArgumentType.getString(context, "reason") } catch (_: Exception) { "" }

        val target = BanState.resolvePlayer(server, name)
        if (target == null) {
            source.sendError(Text.translatable("cobblemarket.ban.player_not_found", name))
            return 0
        }

        val expiresAt = if (durationStr.isNullOrBlank()) {
            null
        } else {
            val ms = BanState.parseDurationMs(durationStr)
            if (ms == null) {
                source.sendError(Text.translatable("cobblemarket.ban.invalid_duration"))
                return 0
            }
            System.currentTimeMillis() + ms
        }

        BanState.get(server).ban(target.first, target.second, source.name, expiresAt, reason)

        if (expiresAt == null) {
            source.sendFeedback({ Text.translatable("cobblemarket.ban.banned", target.second).formatted(Formatting.GREEN) }, false)
        } else {
            source.sendFeedback({ Text.translatable("cobblemarket.ban.banned_until", target.second, durationStr).formatted(Formatting.GREEN) }, false)
        }

        // 若目标在线，即时通知
        server.playerManager.getPlayer(target.first)?.sendMessage(
            if (reason.isNotBlank())
                Text.translatable("cobblemarket.ban.banned_msg_reason", com.shusheng.cobblemarket.market.BanState.reasonText(reason)).formatted(Formatting.RED)
            else
                Text.translatable("cobblemarket.ban.banned_msg").formatted(Formatting.RED),
            false
        )
        return Command.SINGLE_SUCCESS
    }

    private fun unbanPlayer(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val server = source.server
        val name = StringArgumentType.getString(context, "player")

        val target = BanState.resolvePlayer(server, name)
        if (target == null) {
            source.sendError(Text.translatable("cobblemarket.ban.player_not_found", name))
            return 0
        }

        if (BanState.get(server).unban(target.first, source.name) != null) {
            source.sendFeedback({ Text.translatable("cobblemarket.ban.unbanned", target.second).formatted(Formatting.GREEN) }, false)
            // 坏账玩家解封只放行市场交易：借款/喵喵支付另有独立拦截，撤销坏账才是彻底恢复
            if (FinanceService.hasBadDebt(server, target.first)) {
                source.sendFeedback({ Text.translatable("cobblemarket.ban.unbanned_bad_debt").formatted(Formatting.YELLOW) }, false)
            }
        } else {
            source.sendError(Text.translatable("cobblemarket.ban.not_banned", target.second))
            return 0
        }
        return Command.SINGLE_SUCCESS
    }

    private fun banList(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val server = source.server
        val bans = BanState.get(server).getAllBans(System.currentTimeMillis())

        if (bans.isEmpty()) {
            source.sendFeedback({ Text.translatable("cobblemarket.ban.banlist_empty") }, false)
            return Command.SINGLE_SUCCESS
        }

        source.sendFeedback({ Text.translatable("cobblemarket.ban.banlist_title").formatted(Formatting.GOLD) }, false)
        bans.forEach { info ->
            // 保留 Text 对象：嵌套翻译在客户端语言下渲染
            val duration: Text = if (info.isPermanent)
                Text.translatable("cobblemarket.ban.permanent")
            else
                BanState.remainingText(info.expiresAt!! - System.currentTimeMillis())
            source.sendFeedback({
                Text.translatable("cobblemarket.ban.banlist_entry", info.playerName, info.bannedBy, duration)
            }, false)
        }
        return Command.SINGLE_SUCCESS
    }

}
