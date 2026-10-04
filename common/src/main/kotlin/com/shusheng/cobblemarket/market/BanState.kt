package com.shusheng.cobblemarket.market

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

data class BanInfo(
    val playerUuid: UUID,
    val playerName: String,
    val bannedBy: String,
    val bannedAt: Long,
    val expiresAt: Long?,  // null = 永久封禁
    val reason: String,
    /** 同时禁止借款（**只拦借款**：存取款、还款都不受影响 —— 还款必须留着，他还钱才能把逾期降下来）。
     *  末尾新字段 + 默认 false ⇒ 旧档缺这一格自动落默认，不需要迁移分支 */
    val blockLoan: Boolean = false
) {
    val isPermanent: Boolean get() = expiresAt == null
}

class BanState private constructor() : PersistentState() {

    private val bans = mutableMapOf<UUID, BanInfo>()

    fun ban(
        playerUuid: UUID,
        playerName: String,
        bannedBy: String,
        expiresAt: Long?,
        reason: String,
        blockLoan: Boolean = false
    ) {
        // 先删后插：重复封禁（改时长/理由）时 put 覆盖不移动位置，强制移到末尾保证最新在前
        bans.remove(playerUuid)
        val now = System.currentTimeMillis()
        bans[playerUuid] = BanInfo(playerUuid, playerName, bannedBy, now, expiresAt, reason, blockLoan)
        markDirty()
        // ⭐ 审计账本**收口在这里** ⇒ 命令 / 管理端界面 / 金融自动冻结这些写入点全部自动覆盖，不会漏
        BanChangeLogger.log(
            operator = bannedBy, targetName = playerName, targetUuid = playerUuid,
            actionKey = ACTION_BAN, durationMs = expiresAt?.minus(now), reason = reason,
            originalBanner = "", blockLoan = blockLoan
        )
    }

    /**
     * **编辑**一笔已生效的封禁（时长 / 理由 / 同时禁止借款）。
     *
     * ⚠⭐ **保留原 `bannedAt`**（用户 2026-10-04 拍板）：新的到期时刻 = **原起点 + 新时长** ——
     *   只是去开个「同时禁止借款」开关，不该把已经过了两天的 3 天封禁续成新的 3 天。
     *   真要把人关更久，就把时长填大。
     *
     * @param expiresAt 新的到期时刻（null = 改成永久）
     * @return 改后的 [BanInfo]；该玩家当前没被封则返回 null（调用方据此回一句提示）
     */
    fun editBan(
        playerUuid: UUID,
        bannedBy: String,
        expiresAt: Long?,
        reason: String,
        blockLoan: Boolean
    ): BanInfo? {
        val old = bans[playerUuid] ?: return null
        val updated = BanInfo(playerUuid, old.playerName, bannedBy, old.bannedAt, expiresAt, reason, blockLoan)
        bans[playerUuid] = updated
        markDirty()
        BanChangeLogger.log(
            operator = bannedBy, targetName = old.playerName, targetUuid = playerUuid,
            actionKey = ACTION_EDIT, durationMs = expiresAt?.minus(old.bannedAt), reason = reason,
            originalBanner = "", blockLoan = blockLoan,
            // 「改前」两列：新值在时长/借款两列，旧值在这里 ⇒ 账本上一比就是「3 天 → 7 天、不选 → 选中」
            beforeDurationMs = old.expiresAt?.minus(old.bannedAt),
            beforeBlockLoan = old.blockLoan
        )
        return updated
    }

    /**
     * 解封。[operator] = 执行解封的人（或系统来源的 `$词条键`）。
     * ⚠ 这个参数是 1.2.1 加的：审计账本要记「谁解的」——**封禁那条里没有 B**，
     *   「A 封的、被 B 解了」这件事只有解封这一刻才记得到。
     */
    fun unban(playerUuid: UUID, operator: String): BanInfo? {
        val removed = bans.remove(playerUuid)
        if (removed != null) {
            markDirty()
            // 解封这条**自带原封禁者与原时长**，不用回头去翻当初封禁那行（用户要的正是这个场景）
            BanChangeLogger.log(
                operator = operator, targetName = removed.playerName, targetUuid = playerUuid,
                actionKey = ACTION_UNBAN, durationMs = removed.expiresAt?.minus(removed.bannedAt),
                reason = removed.reason, originalBanner = removed.bannedBy,
                blockLoan = removed.blockLoan
            )
        }
        return removed
    }

    fun getBanInfo(playerUuid: UUID, currentTime: Long): BanInfo? {
        val info = bans[playerUuid] ?: return null
        val exp = info.expiresAt
        if (exp != null && exp <= currentTime) {
            bans.remove(playerUuid)
            markDirty()
            return null
        }
        return info
    }

    fun getAllBans(currentTime: Long): List<BanInfo> {
        val expired = bans.values.filter { it.expiresAt != null && it.expiresAt <= currentTime }
        expired.forEach { bans.remove(it.playerUuid) }
        if (expired.isNotEmpty()) markDirty()
        return bans.values.toList()
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        bans.values.forEach { info ->
            val c = NbtCompound()
            c.putUuid("uuid", info.playerUuid)
            c.putString("name", info.playerName)
            c.putString("bannedBy", info.bannedBy)
            c.putLong("bannedAt", info.bannedAt)
            info.expiresAt?.let { c.putLong("expiresAt", it) }
            c.putString("reason", info.reason)
            c.putBoolean("blockLoan", info.blockLoan)
            list.add(c)
        }
        nbt.put("bans", list)
        return nbt
    }

    companion object {
        /** 审计账本「动作」列的取值（`$` 前缀 = 词条键，界面按**客户端语言**渲染，见 [reasonText]）。
         *  直接复用界面上那两枚按钮的译名（「封禁 / 解封」）—— 同一个概念只留一个词条 */
        const val ACTION_BAN = "\$cobblemarket.ban.ban"
        const val ACTION_UNBAN = "\$cobblemarket.ban.unban"
        /** 「修改」= 编辑一笔已生效的封禁（见 [editBan]）：账本要能一眼看出这是编辑、不是新封 */
        const val ACTION_EDIT = "\$cobblemarket.ban.action_edit"

        private val TYPE = PersistentState.Type(
            { BanState() },
            { nbt, _ ->
                BanState().apply {
                    nbt.getList("bans", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            val expiresAt = if (c.contains("expiresAt")) c.getLong("expiresAt") else null
                            val info = BanInfo(
                                playerUuid = c.getUuid("uuid"),
                                playerName = c.getString("name"),
                                bannedBy = c.getString("bannedBy"),
                                bannedAt = c.getLong("bannedAt"),
                                expiresAt = expiresAt,
                                reason = c.getString("reason"),
                                // 缺这格 = 旧档 ⇒ getBoolean 给 false，正是要的默认值
                                blockLoan = c.getBoolean("blockLoan")
                            )
                            bans[info.playerUuid] = info
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted ban entry: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): BanState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_bans")

        /**
         * 本会话**已经提示过封禁**的玩家（内存、**不落盘**）。
         * 登录时清掉自己的条目 ⇒ 语义正好是「每次登录后首次开市场提示一次」，**不需要退出钩子**。
         * 用并发集合：登录钩子与请求处理虽然都在主线程，但这里刻意不留线程假设。
         */
        private val sessionNotified: MutableSet<UUID> =
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<UUID, Boolean>())

        /** 玩家上线：清掉会话提示标记（下次开市场还要能收到那条提示） */
        fun clearSessionNotified(uuid: UUID) {
            sessionNotified.remove(uuid)
        }

        /**
         * 玩家**开市场**（入口界面来要权限快照那一刻）补一条封禁提示 —— **每个会话只发一次**，
         * 让被封的玩家不至于"点了才知道"。用的是与"操作被拒"完全相同的那句红字词条。
         * ⚠ 判"首次"直接用集合 add 的返回值：同一个集合同时承担去重，不用另开标志位。
         */
        fun notifyOnMarketOpen(server: net.minecraft.server.MinecraftServer, player: net.minecraft.server.network.ServerPlayerEntity) {
            if (!sessionNotified.add(player.uuid)) return
            val now = System.currentTimeMillis()
            val info = get(server).getBanInfo(player.uuid, now) ?: return
            server.execute {
                player.sendMessage(
                    noticeText(info.expiresAt, info.reason, now).formatted(net.minecraft.util.Formatting.RED), false
                )
            }
        }

        /**
         * 封禁提示（聊天行）的**统一拼装**：`<首句>。<剩余时间或永久>。理由：…`
         * —— 理由为空时自动走不带理由的变体，**不留一个空的「理由：」**。
         * @param keyBase 词条前缀，**不带** `_time` / `_time_reason` 后缀 ——
         *   市场交易 = `cobblemarket.ban.banned_msg`；仅借款 = `cobblemarket.ban.loan_blocked_msg`
         * ⚠ 全程只传 Text、**不拼串**：嵌套词条由**客户端**按自己的语言渲染（服务端语言 ≠ 客户端语言）
         */
        // ⚠ 返回 MutableText（不是 Text）：调用方要接 `.formatted(RED)`，而 formatted 只挂在 MutableText 上
        // ⚠ 收的是 expiresAt + reason 两个**原始字段**（而不是 BanInfo）：封禁刚下发、还没从 BanState
        //   读回实体的那处（BanNetwork）也得能用同一个函数，否则它只能另抄一份 —— 那正是这次要消掉的东西
        fun noticeText(
            expiresAt: Long?,
            reason: String,
            now: Long,
            keyBase: String = "cobblemarket.ban.banned_msg"
        ): net.minecraft.text.MutableText {
            val timeDesc: net.minecraft.text.Text = if (expiresAt == null)
                net.minecraft.text.Text.translatable("cobblemarket.ban.permanent")
            else
                net.minecraft.text.Text.translatable("cobblemarket.ban.remaining", remainingText(expiresAt - now))
            return if (reason.isNotBlank())
                net.minecraft.text.Text.translatable("${keyBase}_time_reason", timeDesc, reasonText(reason))
            else
                net.minecraft.text.Text.translatable("${keyBase}_time", timeDesc)
        }

        /**
         * 封禁理由渲染约定：以 $ 开头的 reason 是 translatable key（系统来源，渲染时按玩家语言翻译）；
         * 玩家手动填写的理由原样显示（不翻译）。
         */
        // ⚠ 返回 MutableText（不是 Text）：调用方常要接 `.formatted(色)`，而 formatted 只挂在 MutableText 上
        fun reasonText(reason: String): net.minecraft.text.MutableText =
            if (reason.startsWith("$"))
                net.minecraft.text.Text.translatable(reason.removePrefix("$"))
            else
                net.minecraft.text.Text.literal(reason)

        fun parseDurationMs(input: String): Long? {
            val regex = Regex("(\\d+)([dhm])")
            var total = 0L
            var matched = false
            regex.findAll(input.lowercase()).forEach { m ->
                // 数字超 Long 范围：整体解析失败，宁报错不静默忽略该段
                val v = m.groupValues[1].toLongOrNull() ?: return null
                val unitMs = when (m.groupValues[2]) {
                    "d" -> 24L * 60 * 60 * 1000
                    "h" -> 60L * 60 * 1000
                    "m" -> 60L * 1000
                    else -> return null
                }
                // 乘法/累加溢出防护：Long 回绕会颠覆封禁语义，改为安全失败
                if (v > Long.MAX_VALUE / unitMs) return null
                val addMs = v * unitMs
                if (total > Long.MAX_VALUE - addMs) return null
                total += addMs
                matched = true
            }
            return if (matched && total > 0) total else null
        }

        fun resolvePlayer(server: MinecraftServer, name: String): Pair<UUID, String>? {
            server.playerManager.getPlayer(name)?.let { return it.uuid to it.name.string }
            val profile = server.userCache?.findByName(name)?.orElse(null)
            return profile?.let { it.id to it.name }
        }

        /**
         * 剩余时长（**本地化**）：返回 Text 而非字符串 —— 调用方一律把它当**嵌套参数**塞进词条
         * （如 `ban.remaining`），由客户端按自己的语言渲染。⚠ 别先 `.string` 成串：那样在服务端
         * 就定死成一种语言了（英文客户端玩家会看到中文）。
         * 三档与旧的 `3d5h` 写法一一对应，只是补上了语言与单位名。
         */
        fun remainingText(ms: Long): net.minecraft.text.Text {
            val totalMinutes = ms / 60000
            val days = totalMinutes / (24 * 60)
            val hours = (totalMinutes % (24 * 60)) / 60
            val minutes = totalMinutes % 60
            return when {
                days > 0 -> net.minecraft.text.Text.translatable("cobblemarket.ban.dur_days_hours", days, hours)
                hours > 0 -> net.minecraft.text.Text.translatable("cobblemarket.ban.dur_hours_minutes", hours, minutes)
                else -> net.minecraft.text.Text.translatable("cobblemarket.ban.dur_minutes", minutes)
            }
        }
    }
}
