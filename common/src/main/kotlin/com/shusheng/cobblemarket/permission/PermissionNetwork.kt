package com.shusheng.cobblemarket.permission

import net.minecraft.text.Text
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.network.MarketResultPayload
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
                PermissionState.get(server).setNodes(targetUuid, targetName, nodes, player.name.string)
                refresh(server, targetUuid)              // 目标在线则即时刷新其按钮状态
                sendToPlayer(player, listDataOf(server)) // 回刷新后的列表
            }
        }

        registerC2S(RemovePlayerPermissionsPayload.ID, RemovePlayerPermissionsPayload.CODEC) { payload, player ->
            if (!MarketPermissions.isOp(player)) return@registerC2S
            if (!RequestThrottle.allow(player.uuid, "remove_player_permissions", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                PermissionState.get(server).removePlayer(payload.playerUuid)
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
}
