package com.shusheng.cobblemarket.permission

import net.minecraft.text.Text
import net.minecraft.util.Formatting
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.market.OfflineMessageState
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.sendSound
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.platform.onPlayerJoin
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import com.shusheng.cobblemarket.util.RequestThrottle
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID

/**
 * 「添加一个还没有权限的玩家」时客户端填的占位 UUID —— 服务端见到它就改用 `playerName` 解析
 * （在线玩家 → userCache）。省掉一趟「先解析 UUID 再保存」的往返。
 */
val EMPTY_UUID: UUID = UUID(0, 0)

/** C2S：客户端主动拉一次权限快照（界面打开时；登录推送丢包时兜底） */
class RequestPermissionSnapshotPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestPermissionSnapshotPayload>(CobbleMarket.id("request_permission_snapshot"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPermissionSnapshotPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestPermissionSnapshotPayload() }
        )
    }
}

/** S2C：我的权限节点快照（登录时 + 授予/收回时推送） */
data class PermissionSnapshotPayload(
    val nodes: List<String>,
    /** 是否真 OP —— **必须由服务端明说**：客户端曾用「节点数量 == 总数」反推，
     *  结果被授予全部节点的普通玩家被误判成 OP（能用只认 OP 的权限管理入口）。 */
    val op: Boolean
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<PermissionSnapshotPayload>(CobbleMarket.id("permission_snapshot"))
        val CODEC: PacketCodec<PacketByteBuf, PermissionSnapshotPayload> = PacketCodec.of(
            { p, b ->
                b.writeBoolean(p.op)
                b.writeInt(p.nodes.size)
                p.nodes.forEach { b.writeString(it) }
            },
            { b ->
                val op = b.readBoolean()
                val size = b.readInt()
                PermissionSnapshotPayload(List(size) { b.readString() }, op)
            }
        )
    }
}

// ── 权限管理界面（仅 OP）的协议 ──

/** C2S：请求权限变更审计（界面「变更记录」页签） */
class RequestPermissionAuditPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestPermissionAuditPayload>(CobbleMarket.id("request_permission_audit"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPermissionAuditPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestPermissionAuditPayload() }
        )
    }
}

/** S2C：权限变更审计记录（新的在前；节点为短名字符串） */
data class PermissionAuditPayload(val rows: List<Row>) : CustomPayload {

    data class Row(
        val timestamp: Long,
        val operator: String,
        val targetName: String,
        val added: String,
        val removed: String,
        /** 变更后该玩家剩下的全部节点（旧账本记录为空串） */
        val remaining: String
    )

    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<PermissionAuditPayload>(CobbleMarket.id("permission_audit"))
        val CODEC: PacketCodec<PacketByteBuf, PermissionAuditPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.rows.size)
                p.rows.forEach { r ->
                    b.writeLong(r.timestamp)
                    b.writeString(r.operator)
                    b.writeString(r.targetName)
                    b.writeString(r.added)
                    b.writeString(r.removed)
                    b.writeString(r.remaining)
                }
            },
            { b ->
                val n = b.readInt()
                // 读端一律具名传参：字段顺序写错会静默串位（见 feedback_payload_codec_order）
                PermissionAuditPayload(List(n) {
                    Row(
                        timestamp = b.readLong(),
                        operator = b.readString(),
                        targetName = b.readString(),
                        added = b.readString(),
                        removed = b.readString(),
                        remaining = b.readString()
                    )
                })
            }
        )
    }
}

/** C2S：请求权限列表（界面打开时） */
class RequestPermissionListPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestPermissionListPayload>(CobbleMarket.id("request_permission_list"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPermissionListPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestPermissionListPayload() }
        )
    }
}

/** C2S：保存某玩家的**完整**节点集合（弹窗多选后一次发全量，服务端算差集） */
data class SavePlayerPermissionsPayload(
    val playerUuid: UUID,
    val playerName: String,
    val nodes: List<String>
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<SavePlayerPermissionsPayload>(CobbleMarket.id("save_player_permissions"))
        val CODEC: PacketCodec<PacketByteBuf, SavePlayerPermissionsPayload> = PacketCodec.of(
            { p, b ->
                b.writeUuid(p.playerUuid)
                b.writeString(p.playerName)
                b.writeInt(p.nodes.size)
                p.nodes.forEach { b.writeString(it) }
            },
            { b ->
                val uuid = b.readUuid()
                val name = b.readString()
                val size = b.readInt()
                SavePlayerPermissionsPayload(uuid, name, List(size) { b.readString() })
            }
        )
    }
}

/** C2S：移除某玩家全部权限（列表行「删除」） */
data class RemovePlayerPermissionsPayload(val playerUuid: UUID) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RemovePlayerPermissionsPayload>(CobbleMarket.id("remove_player_permissions"))
        val CODEC: PacketCodec<PacketByteBuf, RemovePlayerPermissionsPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.playerUuid) },
            { b -> RemovePlayerPermissionsPayload(b.readUuid()) }
        )
    }
}

/** S2C：权限列表数据（界面列表用） */
data class PermissionListDataPayload(val entries: List<Entry>) : CustomPayload {

    data class Entry(val playerUuid: UUID, val playerName: String, val nodes: List<String>)

    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<PermissionListDataPayload>(CobbleMarket.id("permission_list_data"))
        val CODEC: PacketCodec<PacketByteBuf, PermissionListDataPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.entries.size)
                p.entries.forEach { e ->
                    b.writeUuid(e.playerUuid)
                    b.writeString(e.playerName)
                    b.writeInt(e.nodes.size)
                    e.nodes.forEach { b.writeString(it) }
                }
            },
            { b ->
                val count = b.readInt()
                PermissionListDataPayload(List(count) {
                    val uuid = b.readUuid()
                    val name = b.readString()
                    val size = b.readInt()
                    Entry(uuid, name, List(size) { b.readString() })
                })
            }
        )
    }
}

/**
 * 权限快照下发（docs/permission_nodes_plan.md S1）。
 * 客户端只读服务端算好的节点列表，**不自己拼集合**（省事 + 防伪造）。
 */
object PermissionNetwork {

    fun register() {
        registerS2CType(PermissionSnapshotPayload.ID, PermissionSnapshotPayload.CODEC)

        registerC2S(RequestPermissionSnapshotPayload.ID, RequestPermissionSnapshotPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "request_permission_snapshot", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            sendToPlayer(player, snapshotOf(player))
        }

        // 登录补发：客户端据此决定入口与管理面板各按钮的可用状态
        onPlayerJoin { player ->
            sendToPlayer(player, snapshotOf(player))
        }

        registerS2CType(PermissionListDataPayload.ID, PermissionListDataPayload.CODEC)

        registerS2CType(PermissionAuditPayload.ID, PermissionAuditPayload.CODEC)
        registerC2S(RequestPermissionAuditPayload.ID, RequestPermissionAuditPayload.CODEC) { _, player ->
            if (!MarketPermissions.isOp(player)) return@registerC2S
            val rows = PermissionFileLogger.readRecent().map {
                PermissionAuditPayload.Row(
                    timestamp = it.timestamp,
                    operator = it.operator,
                    targetName = it.targetName,
                    added = it.added,
                    removed = it.removed,
                    remaining = it.remaining
                )
            }
            sendToPlayer(player, PermissionAuditPayload(rows))
        }

        // ── 权限管理界面：**仅 OP**（权限授予是服主专属，不下放给任何管理节点，否则能自我提权）──

        registerC2S(RequestPermissionListPayload.ID, RequestPermissionListPayload.CODEC) { _, player ->
            if (!MarketPermissions.isOp(player)) return@registerC2S
            sendToPlayer(player, listDataOf(player.server))
        }

        registerC2S(SavePlayerPermissionsPayload.ID, SavePlayerPermissionsPayload.CODEC) { payload, player ->
            if (!MarketPermissions.isOp(player)) return@registerC2S
            if (!RequestThrottle.allow(player.uuid, "save_player_permissions", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // UUID 全 0 = 界面「添加」一个还没有任何权限的玩家：按名字解析（在线玩家 → userCache）
                val targetUuid: UUID
                val targetName: String
                if (payload.playerUuid == EMPTY_UUID) {
                    val resolved = BanState.resolvePlayer(server, payload.playerName)
                    if (resolved == null) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.ban.player_not_found", payload.playerName)))
                        return@execute
                    }
                    targetUuid = resolved.first
                    targetName = resolved.second
                } else {
                    targetUuid = payload.playerUuid
                    targetName = payload.playerName
                }
                // 只收合法节点：防客户端伪造未知节点写进表里
                val nodes = payload.nodes.filter { it in MarketPermissions.ALL }.toSet()
                val (added, removed) = PermissionState.get(server).setNodes(targetUuid, targetName, nodes, player.name.string)
                // 剩余节点 = 保存后的完整集合。写失败（账本被 Excel/WPS 独占等）要告诉 OP，别静默丢审计
                if (!PermissionFileLogger.log(player.name.string, targetName, targetUuid, added, removed, nodes)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.perm.audit_write_failed")))
                }
                // 权限变更音：给被改动的玩家本人（在线才发；管理员给自己加权限时也会响，符合直觉）
                if (added.isNotEmpty() || removed.isNotEmpty()) {
                    server.playerManager.getPlayer(targetUuid)?.let { sendSound(it, "permission_change") }
                    notifyChanged(server, targetUuid, added, removed)
                }
                refresh(server, targetUuid)              // 目标在线则即时刷新其按钮状态
                sendToPlayer(player, listDataOf(server)) // 回刷新后的列表
            }
        }

        registerC2S(RemovePlayerPermissionsPayload.ID, RemovePlayerPermissionsPayload.CODEC) { payload, player ->
            if (!MarketPermissions.isOp(player)) return@registerC2S
            if (!RequestThrottle.allow(player.uuid, "remove_player_permissions", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 界面「删除」= 收回该玩家全部节点，同样记账（removed 为其原本的全部节点）
                PermissionState.get(server).removePlayer(payload.playerUuid)?.let {
                    // 全删了 → 剩余为空；写失败同样提示 OP
                    if (!PermissionFileLogger.log(player.name.string, it.playerName, it.playerUuid, emptySet(), it.nodes, emptySet())) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.perm.audit_write_failed")))
                    }
                    // 界面「删除」= 收回全部权限，同样响一声 + 通知本人
                    server.playerManager.getPlayer(it.playerUuid)?.let { p -> sendSound(p, "permission_change") }
                    notifyChanged(server, it.playerUuid, emptySet(), it.nodes)
                }
                refresh(server, payload.playerUuid)
                sendToPlayer(player, listDataOf(server))
            }
        }
    }

    /** 界面列表数据（节点按 ALL 顺序稳定排列，不受授予先后影响） */
    private fun listDataOf(server: MinecraftServer): PermissionListDataPayload =
        PermissionListDataPayload(
            PermissionState.get(server).getAll().map { grant ->
                PermissionListDataPayload.Entry(
                    grant.playerUuid,
                    grant.playerName,
                    MarketPermissions.ALL.filter { it in grant.nodes }
                )
            }
        )

    /** 该玩家的快照：OP 直接给全部节点（客户端据此点亮所有管理按钮），否则只给授予表里命中的。
     *  `op` 字段单独携带 —— 被授予全部节点的普通玩家节点数也是 8，客户端不能靠数量反推。 */
    fun snapshotOf(player: ServerPlayerEntity): PermissionSnapshotPayload {
        val op = MarketPermissions.isOp(player)
        return PermissionSnapshotPayload(
            if (op) MarketPermissions.ALL
            else MarketPermissions.ALL.filter { it in PermissionState.get(player.server).getNodes(player.uuid) },
            op
        )
    }

    /** 授予/收回后即时刷新在线目标（离线不用推：下次登录会补发） */
    fun refresh(server: MinecraftServer, playerUuid: UUID) {
        val online = server.playerManager.getPlayer(playerUuid) ?: return
        sendToPlayer(online, snapshotOf(online))
    }

    /**
     * 权限变更通知：告诉**被改动的玩家本人**他被加了/减了什么（在线直发聊天框、离线入队补发）。
     *
     * 之前改权限是"静默生效"——被授予/收回的人除了一行 CSV 审计之外什么都不知道，
     * 只能自己发现某个管理入口突然能点/不能点了（2026-09-16 用户反馈）。
     * 音效由各调用点另外发（只在线时响），这里只管文字。
     */
    fun notifyChanged(server: MinecraftServer, playerUuid: UUID, added: Set<String>, removed: Set<String>) {
        if (added.isEmpty() && removed.isEmpty()) return
        val msg = Text.literal("")
        if (added.isNotEmpty()) {
            msg.append(
                Text.translatable("cobblemarket.perm.notify_granted", nodesText(added)).formatted(Formatting.GREEN)
            )
        }
        if (removed.isNotEmpty()) {
            if (added.isNotEmpty()) msg.append(Text.literal("\n"))
            msg.append(
                Text.translatable("cobblemarket.perm.notify_revoked", nodesText(removed)).formatted(Formatting.YELLOW)
            )
        }
        // 追加「变更后的完整节点列表」：玩家一眼看到自己现在手里有什么，不用再跑一趟界面
        val current = PermissionState.get(server).getNodes(playerUuid)
        msg.append(Text.literal("\n"))
        msg.append(
            if (current.isEmpty()) Text.translatable("cobblemarket.perm.notify_none").formatted(Formatting.GRAY)
            else Text.translatable("cobblemarket.perm.notify_current", nodesText(current)).formatted(Formatting.GRAY)
        )
        OfflineMessageState.notify(server, playerUuid, msg)
    }

    /**
     * 节点名的**可翻译文本**：与权限界面按钮/列表用同一套词条（`cobblemarket.perm.node.*`），
     * 中文环境显示「黑名单」而不是 `blacklist`。
     *
     * ⚠ 必须拼 **Text** 而不是字符串：离线补发的消息会序列化成 JSON 存着，
     * 等玩家上线时**在客户端**按他自己的语言渲染（拼成字符串就锁死发送方的语言了）。
     */
    private fun nodesText(nodes: Set<String>): Text {
        val t = Text.literal("")
        // 按 MarketPermissions.ALL 的顺序输出：与界面/命令的展示顺序一致，且顺序稳定（Set 本身无序）
        MarketPermissions.ALL.filter { it in nodes }.forEachIndexed { i, node ->
            if (i > 0) t.append(Text.literal(", "))
            t.append(Text.translatable("cobblemarket.perm.node." + node.removePrefix(MarketPermissions.PREFIX)))
        }
        return t
    }
}
