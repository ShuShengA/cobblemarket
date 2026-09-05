package com.shusheng.cobblemarket.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.command.argument.EntityArgumentType
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.finance.CreditFileLogger
import com.shusheng.cobblemarket.finance.FinanceService
import com.shusheng.cobblemarket.finance.FinanceState
import com.shusheng.cobblemarket.finance.LoanLogType
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.network.MarketNetwork
import com.shusheng.cobblemarket.network.MarketStatePayload
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
                        .requires { it.hasPermissionLevel(2) }
                        .then(CommandManager.argument("player", StringArgumentType.word())
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
                        .requires { it.hasPermissionLevel(2) }
                        .then(CommandManager.argument("player", StringArgumentType.word())
                            .executes(::unbanPlayer)
                        )
                    )
                    .then(CommandManager.literal("banlist")
                        .requires { it.hasPermissionLevel(2) }
                        .executes(::banList)
                    )
                    .then(CommandManager.literal("on")
                        .requires { it.hasPermissionLevel(2) }
                        .executes(::marketOn)
                    )
                    .then(CommandManager.literal("off")
                        .requires { it.hasPermissionLevel(2) }
                        .executes(::marketOff)
                    )
                    .then(CommandManager.literal("reload")
                        .requires { it.hasPermissionLevel(2) }
                        .executes(::reloadConfig)
                    )
                    .then(CommandManager.literal("loan")
                        .requires { it.hasPermissionLevel(2) }
                        .then(CommandManager.literal("clear")
                            .then(CommandManager.argument("player", StringArgumentType.word())
                                .executes(::loanClear)
                            )
                        )
                    )
                    .then(CommandManager.literal("card")
                        .requires { it.hasPermissionLevel(2) }
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
            )
        }

        // 登录补发市场开关状态：客户端据此把入口按钮置灰（开关是全局的，登录时必须同步一次）
        onPlayerJoin { player ->
            sendToPlayer(player, MarketStatePayload(CobbleMarketConfig.marketEnabled))
        }
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
            state.addBlackCardHolder(player.uuid)
            // 黑卡是紫卡的升级替代：获得黑卡自动移除紫卡资格，并主动清除背包紫卡（自检有 OP 豁免）
            state.removePurpleCardHolder(player.uuid)
            com.shusheng.cobblemarket.finance.FinanceService.clearPurpleCardItems(player)
        } else {
            state.addPurpleCardHolder(player.uuid)
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
        val removed = if (black) state.removeBlackCardHolder(player.uuid) else state.removePurpleCardHolder(player.uuid)
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
        val removed = if (black) state.removeBlackCardHolder(uuid) else state.removePurpleCardHolder(uuid)
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

        if (BanState.get(server).unban(target.first) != null) {
            source.sendFeedback({ Text.translatable("cobblemarket.ban.unbanned", target.second).formatted(Formatting.GREEN) }, false)
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
                Text.literal(BanState.formatRemaining(info.expiresAt!! - System.currentTimeMillis()))
            source.sendFeedback({
                Text.translatable("cobblemarket.ban.banlist_entry", info.playerName, info.bannedBy, duration)
            }, false)
        }
        return Command.SINGLE_SUCCESS
    }

}
