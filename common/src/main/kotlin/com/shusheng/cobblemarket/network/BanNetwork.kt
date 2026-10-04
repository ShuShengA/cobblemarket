package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.finance.FinanceService
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID

// ── DTO ──

data class BanEntry(
    val playerUuid: UUID,
    val playerName: String,
    val bannedBy: String,
    val bannedAt: Long,
    val expiresAt: Long?,
    /** 剩余毫秒（服务端算好发过来；永久封禁发 0，客户端按 expiresAt == null 渲染"永久"） */
    val remainingMs: Long,
    val reason: String,
    /** 同时禁止借款（末尾新字段）：列表悬停窗要显示它，否则这个开关设完就再也看不见了 */
    val blockLoan: Boolean
) {
    fun write(buf: PacketByteBuf) {
        buf.writeUuid(playerUuid)
        buf.writeString(playerName)
        buf.writeString(bannedBy)
        buf.writeLong(bannedAt)
        buf.writeBoolean(expiresAt != null)
        expiresAt?.let { buf.writeLong(it) }
        buf.writeLong(remainingMs)
        buf.writeString(reason)
        buf.writeBoolean(blockLoan)
    }

    companion object {
        fun read(buf: PacketByteBuf) = BanEntry(
            playerUuid = buf.readUuid(),
            playerName = buf.readString(),
            bannedBy = buf.readString(),
            bannedAt = buf.readLong(),
            expiresAt = if (buf.readBoolean()) buf.readLong() else null,
            remainingMs = buf.readLong(),
            reason = buf.readString(),
            blockLoan = buf.readBoolean()
        )
    }
}

// ── C2S：请求玩家名联想（封禁输入框用；覆盖存档内所有登录过的玩家，含离线） ──

data class RequestPlayerNameSuggestionsPayload(val prefix: String) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestPlayerNameSuggestionsPayload>(CobbleMarket.id("request_player_name_suggestions"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPlayerNameSuggestionsPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.prefix) },
            { b -> RequestPlayerNameSuggestionsPayload(b.readString()) }
        )
    }
}

// ── S2C：玩家名联想结果 ──

data class PlayerNameSuggestionsPayload(val names: List<String>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<PlayerNameSuggestionsPayload>(CobbleMarket.id("player_name_suggestions"))
        val CODEC: PacketCodec<PacketByteBuf, PlayerNameSuggestionsPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.names.size); p.names.forEach { b.writeString(it) } },
            { b -> PlayerNameSuggestionsPayload((0 until b.readVarInt()).map { b.readString() }) }
        )
    }
}

// ── C2S: 封禁 ──

data class AdminBanPayload(
    val playerName: String,
    val duration: String,
    val reason: String,
    /** 同时禁止借款（末尾新字段，两端同版） */
    val blockLoan: Boolean
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AdminBanPayload>(CobbleMarket.id("admin_ban"))
        val CODEC: PacketCodec<PacketByteBuf, AdminBanPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.playerName); b.writeString(p.duration); b.writeString(p.reason)
                b.writeBoolean(p.blockLoan)
            },
            { b -> AdminBanPayload(b.readString(), b.readString(), b.readString(), b.readBoolean()) }
        )
    }
}

// ── C2S: 解封 ──

data class AdminUnbanPayload(val playerUuid: UUID) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AdminUnbanPayload>(CobbleMarket.id("admin_unban"))
        val CODEC: PacketCodec<PacketByteBuf, AdminUnbanPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.playerUuid) },
            { b -> AdminUnbanPayload(b.readUuid()) }
        )
    }
}

// ── C2S: 请求封禁列表 ──

class RequestBanListPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestBanListPayload>(CobbleMarket.id("request_ban_list"))
        val CODEC: PacketCodec<PacketByteBuf, RequestBanListPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestBanListPayload() }
        )
    }
}

// ── S2C: 封禁列表 ──

data class BanListDataPayload(val entries: List<BanEntry>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<BanListDataPayload>(CobbleMarket.id("ban_list_data"))
        val CODEC: PacketCodec<PacketByteBuf, BanListDataPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> BanListDataPayload((0 until b.readVarInt()).map { BanEntry.read(b) }) }
        )
    }
}

object BanNetwork {

    fun register() {
        registerS2CType(BanListDataPayload.ID, BanListDataPayload.CODEC)
        registerS2CType(PlayerNameSuggestionsPayload.ID, PlayerNameSuggestionsPayload.CODEC)

        registerC2S(RequestPlayerNameSuggestionsPayload.ID, RequestPlayerNameSuggestionsPayload.CODEC) { payload, player ->
            // 只有「封禁界面」与「权限管理界面」需要玩家名联想：前者要 BAN 节点，后者是 OP 专属界面（OP 恒真）。
            // ⚠ 别放开成 canManage —— 那会让只拿到黑名单/价格限制等节点的玩家也能拉到**全服玩家名（含离线）**，
            //   而他们的界面根本用不到这个联想（2026-09-22 审计发现）
            if (!MarketPermissions.has(player, MarketPermissions.BAN)) return@registerC2S
            val server = player.server
            server.execute {
                val names = LinkedHashSet<String>()
                // 在线玩家
                server.playerManager.playerList.forEach { names.add(it.gameProfile.name) }
                // 当前存档的玩家数据（playerdata 目录的 UUID）+ usercache（UUID→名字）：
                // usercache.json 是服务器全局的（跨存档），必须只用本存档 playerdata 里存在的 UUID，
                // 否则新开存档也能联想到其他存档的玩家
                try {
                    val nameByUuid = mutableMapOf<String, String>()
                    val cacheFile = server.runDirectory.resolve("usercache.json").toFile()
                    if (cacheFile.exists()) {
                        val arr = com.google.gson.JsonParser.parseString(cacheFile.readText()).asJsonArray
                        arr.forEach { el ->
                            val obj = el.asJsonObject
                            val uuid = obj.get("uuid")?.takeIf { !it.isJsonNull }?.asString
                            val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString
                            if (uuid != null && name != null) nameByUuid[uuid] = name
                        }
                    }
                    val playerDataDir = server.getSavePath(net.minecraft.util.WorldSavePath.PLAYERDATA).toFile()
                    playerDataDir.listFiles()?.forEach { f ->
                        val uuid = f.name.removeSuffix(".dat")
                        nameByUuid[uuid]?.let { names.add(it) }
                    }
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.warn("Failed to collect player names for suggestions: {}", e.message)
                }
                val prefix = payload.prefix.trim().lowercase()
                val matched = names
                    .filter { prefix.isEmpty() || it.lowercase().contains(prefix) }
                    .sortedBy { it.lowercase() }
                    .take(20)
                sendToPlayer(player, PlayerNameSuggestionsPayload(matched))
            }
        }

        registerC2S(AdminBanPayload.ID, AdminBanPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.BAN)) return@registerC2S
            val server = player.server
            server.execute {
                val target = BanState.resolvePlayer(server, payload.playerName)
                if (target == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.ban.player_not_found", payload.playerName)))
                    return@execute
                }

                val expiresAt = if (payload.duration.isBlank()) {
                    null
                } else {
                    val ms = BanState.parseDurationMs(payload.duration)
                    if (ms == null) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.ban.invalid_duration")))
                        return@execute
                    }
                    System.currentTimeMillis() + ms
                }

                // 封禁理由长度钳制（防超长输入撑大存档/广播；正常理由远小于上限）
                BanState.get(server).ban(target.first, target.second, player.name.string, expiresAt, payload.reason.take(100), payload.blockLoan)

                val msg = if (expiresAt == null)
                    Text.translatable("cobblemarket.ban.banned", target.second)
                else
                    Text.translatable("cobblemarket.ban.banned_until", target.second, payload.duration)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, msg))

                server.playerManager.getPlayer(target.first)?.sendMessage(
                    buildBanNotice(expiresAt, payload.reason),
                    false
                )
            }
        }

        registerC2S(AdminUnbanPayload.ID, AdminUnbanPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.BAN)) return@registerC2S
            val server = player.server
            server.execute {
                val removed = BanState.get(server).unban(payload.playerUuid)
                if (removed != null) {
                    // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                    com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                    sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.ban.unbanned", removed.playerName)))
                    // 坏账提示直发聊天栏（不再发一个 MarketResultPayload，避免重复播放成功音）
                    if (FinanceService.hasBadDebt(server, payload.playerUuid)) {
                        player.sendMessage(Text.translatable("cobblemarket.ban.unbanned_bad_debt").formatted(Formatting.YELLOW), false)
                    }
                } else {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.ban.not_banned", payload.playerUuid.toString())))
                }
            }
        }

        registerC2S(RequestBanListPayload.ID, RequestBanListPayload.CODEC) { _, player ->
            if (!MarketPermissions.has(player, MarketPermissions.BAN)) return@registerC2S
            val server = player.server
            server.execute {
                val bans = BanState.get(server).getAllBans(System.currentTimeMillis())
                val now = System.currentTimeMillis()
                val entries = bans.map { info ->
                    BanEntry(
                        playerUuid = info.playerUuid,
                        playerName = info.playerName,
                        bannedBy = info.bannedBy,
                        bannedAt = info.bannedAt,
                        expiresAt = info.expiresAt,
                        // 永久发 0（客户端按 expiresAt == null 走翻译键渲染"永久"，这个值用不到）
                        remainingMs = if (info.isPermanent) 0L else info.expiresAt!! - now,
                        reason = info.reason,
                        blockLoan = info.blockLoan
                    )
                }
                sendToPlayer(player, BanListDataPayload(entries.reversed()))
            }
        }
    }

    /** 封禁通知（红字聊天行）：拼装统一在 BanState.noticeText，这里只管着色 */
    private fun buildBanNotice(expiresAt: Long?, reason: String): MutableText =
        BanState.noticeText(expiresAt, reason, System.currentTimeMillis()).formatted(Formatting.RED)
}
