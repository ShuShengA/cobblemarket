package com.shusheng.cobblemarket.command

import com.shusheng.cobblemarket.permission.PermissionFileLogger
import com.shusheng.cobblemarket.permission.PermissionNetwork
import com.shusheng.cobblemarket.permission.PermissionState
import com.shusheng.cobblemarket.permission.MarketPermissions
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
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID

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
                    .then(CommandManager.literal("on")
                        .requires { MarketPermissions.has(it, MarketPermissions.MARKET) }
                        .executes(::marketOn)
                    )
                    .then(CommandManager.literal("off")
                        .requires { MarketPermissions.has(it, MarketPermissions.MARKET) }
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
                    .then(CommandManager.literal("owner")
                        // 服主名单是整套权限的**根**：只认服务器控制台 ——
                        // 游戏内 OP 敲会看到「仅限服务器控制台执行」（requires 放它们进来只为给提示，
                        // 真正的鉴权是各执行函数首行的 isConsole 判定）；
                        // ⚠ **绝不能改成 entity == null 判控制台**：命令方块的 entity 也是 null，
                        //   那样 OP 在游戏里放个命令方块写 /market owner add 自己 就把自己加成服主了
                        .requires { MarketPermissions.isConsole(it) || it.hasPermissionLevel(2) }
                        .then(CommandManager.literal("add")
                            .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests(::suggestOnlinePlayerNames)
                                .executes(::ownerAdd)
                            )
                        )
                        .then(CommandManager.literal("remove")
                            .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests(::suggestOnlinePlayerNames)
                                .executes(::ownerRemove)
                            )
                        )
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
        // 节点名走与界面 / 聊天通知同一套词条：中文环境显示「黑名单」而不是 blacklist
        val nodeText = Text.translatable("cobblemarket.perm.node." + node.removePrefix(MarketPermissions.PREFIX))
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

    // ── /market owner：服主名单（整套权限的根，只认服务器控制台） ──

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

    /** 控制台专属命令的守卫（/market owner）：游戏内 OP 与 RCON 都拒，只有真控制台过 */
    private fun denyNonConsole(context: CommandContext<ServerCommandSource>): Boolean {
        if (MarketPermissions.isConsole(context.source)) return false
        context.source.sendError(Text.translatable("cobblemarket.owner.console_only"))
        return true
    }

    /**
     * 解析服主参数：**先按 UUID 试**（服主可直接贴 UUID —— 要加的人可能从没上线过、
     * userCache 里查不到），再按名字查（在线 → userCache）。都查不到返回 null。
     */
    private fun resolveOwnerArg(server: MinecraftServer, raw: String): Pair<UUID, String>? {
        val trimmed = raw.trim()
        val byUuid = try {
            UUID.fromString(trimmed)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (byUuid != null) {
            val name = server.playerManager.getPlayer(byUuid)?.name?.string
                ?: server.userCache?.getByUuid(byUuid)?.orElse(null)?.name
            return byUuid to (name ?: byUuid.toString())
        }
        return BanState.resolvePlayer(server, trimmed)
    }

    /** `/market owner add <玩家名|UUID>`：加入服主名单（立即生效并落盘，无需重启或 reload） */
    private fun ownerAdd(context: CommandContext<ServerCommandSource>): Int {
        if (denyNonConsole(context)) return 0
        val source = context.source
        val server = source.server
        val raw = StringArgumentType.getString(context, "player")
        val target = resolveOwnerArg(server, raw)
        if (target == null) {
            source.sendError(Text.translatable("cobblemarket.ban.player_not_found", raw))
            return 0
        }
        if (!CobbleMarketConfig.addServerOwner(target.first)) {
            source.sendError(Text.translatable("cobblemarket.owner.already", target.second))
            return 0
        }
        // 在线即时生效：重推快照，他立刻拿到服主身份与全部管理节点
        PermissionNetwork.refresh(server, target.first)
        source.sendFeedback(
            { Text.translatable("cobblemarket.owner.added", target.second, target.first.toString()).formatted(Formatting.GREEN) },
            true
        )
        return 1
    }

    /** `/market owner remove <玩家名|UUID>`：移出服主名单 */
    private fun ownerRemove(context: CommandContext<ServerCommandSource>): Int {
        if (denyNonConsole(context)) return 0
        val source = context.source
        val server = source.server
        val raw = StringArgumentType.getString(context, "player")
        val target = resolveOwnerArg(server, raw)
        if (target == null) {
            source.sendError(Text.translatable("cobblemarket.ban.player_not_found", raw))
            return 0
        }
        if (!CobbleMarketConfig.removeServerOwner(target.first)) {
            source.sendError(Text.translatable("cobblemarket.owner.not_owner", target.second))
            return 0
        }
        PermissionNetwork.refresh(server, target.first)
        source.sendFeedback(
            { Text.translatable("cobblemarket.owner.removed", target.second).formatted(Formatting.GREEN) },
            true
        )
        // 移除的是最后一名服主：现在谁都管不了市场了，必须显眼提醒（只能手编配置或从控制台加回来）
        if (CobbleMarketConfig.serverOwners.isEmpty()) {
            source.sendFeedback(
                { Text.translatable("cobblemarket.owner.last_removed_warn").formatted(Formatting.RED) },
                false
            )
        }
        return 1
    }

    /** `/market owner list`：列出服主名单（名字查不到时只显示 UUID） */
    private fun ownerList(context: CommandContext<ServerCommandSource>): Int {
        if (denyNonConsole(context)) return 0
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

        if (BanState.get(server).unban(target.first) != null) {
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
                Text.literal(BanState.formatRemaining(info.expiresAt!! - System.currentTimeMillis()))
            source.sendFeedback({
                Text.translatable("cobblemarket.ban.banlist_entry", info.playerName, info.bannedBy, duration)
            }, false)
        }
        return Command.SINGLE_SUCCESS
    }

}
