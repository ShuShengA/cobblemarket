package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.peek.PeekService
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import com.shusheng.cobblemarket.util.RequestThrottle
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID

/**
 * 玩家总览里点「监视」时发出的请求（C2S，2026-11-09）。
 *
 * 只带目标 UUID：权限、在线、存活、是否自己全部由服务端重新判定（按钮显隐不是鉴权）。
 * 成功/失败都走聊天反馈。
 */
data class StartPeekPayload(val targetUuid: UUID) : CustomPayload {

    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<StartPeekPayload>(CobbleMarket.id("start_peek"))

        val CODEC: PacketCodec<PacketByteBuf, StartPeekPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.targetUuid) },
            { b -> StartPeekPayload(b.readUuid()) }
        )
    }
}

/**
 * **监视会话状态**（S2C，2026-11-10）：true = 正在监视，false = 已结束。
 *
 * 客户端据此显示「按 X 退出监视」的 HUD 提示并启用退出键。
 *
 * ⚠ **服务端是唯一真相**，客户端不本地伪造：开始成功发 true；所有结束路径
 * （手动 / 目标离线 / 目标死亡 / 撤权 / 离开维度 / 重进后的崩溃恢复）都在
 * `PeekService.finishCleanup` 真正删掉状态后发 false。
 * ⚠ 还原失败、状态被保留时**不发 false** —— HUD 继续提示，玩家可重试。
 */
data class PeekActivePayload(val active: Boolean) : CustomPayload {

    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PeekActivePayload>(CobbleMarket.id("peek_active"))

        val CODEC: PacketCodec<PacketByteBuf, PeekActivePayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.active) },
            { b -> PeekActivePayload(b.readBoolean()) }
        )
    }
}

/**
 * **快捷退出键的停止请求**（C2S，2026-11-10）。
 *
 * 效果与 `/market peek stop` 完全一致（都走 `PeekService.stop`）；只能停**发包者自己**
 * 的会话 —— 权限 / 是否在监视由服务端重新判定，客户端的 HUD 提示不是鉴权。
 */
data class StopPeekPayload(val dummy: Int = 0) : CustomPayload {

    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<StopPeekPayload>(CobbleMarket.id("stop_peek"))

        val CODEC: PacketCodec<PacketByteBuf, StopPeekPayload> = PacketCodec.of(
            { _, _ -> },
            { _ -> StopPeekPayload() }
        )
    }
}

/** 在线玩家名单的一行：监视目标（uuid + 名字） */
data class PeekTargetEntry(val uuid: UUID, val name: String)

/**
 * **请求可监视的在线玩家名单**（C2S，2026-11-10）。
 *
 * 快速展示界面右侧面板打开时发一次，打开期间每 2 秒刷新（有人上下线）；
 * 服务端按 `overview` 节点判权后回 [PeekTargetsPayload]。
 */
data class RequestPeekTargetsPayload(val dummy: Int = 0) : CustomPayload {

    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestPeekTargetsPayload>(CobbleMarket.id("request_peek_targets"))

        val CODEC: PacketCodec<PacketByteBuf, RequestPeekTargetsPayload> = PacketCodec.of(
            { _, _ -> },
            { _ -> RequestPeekTargetsPayload() }
        )
    }
}

/**
 * **可监视的在线玩家名单**（S2C，2026-11-10）：除请求者自己以外的在线玩家，按名字排序。
 *
 * 只发给持 `overview` 节点的玩家（服主恒真、**不认 OP**）。客户端只拿它画快速展示右侧面板；
 * 真正的鉴权仍在 [PeekService.start]（点击时再判一次权限 / 在线 / 同维度）。
 */
data class PeekTargetsPayload(val entries: List<PeekTargetEntry>) : CustomPayload {

    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PeekTargetsPayload>(CobbleMarket.id("peek_targets"))

        val CODEC: PacketCodec<PacketByteBuf, PeekTargetsPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.entries.size)
                p.entries.forEach { e ->
                    b.writeUuid(e.uuid)
                    b.writeString(e.name)
                }
            },
            { b ->
                val n = b.readVarInt()
                // 读端一律具名传参：顺序写错会静默串位（编译不报）
                PeekTargetsPayload(List(n) { PeekTargetEntry(uuid = b.readUuid(), name = b.readString()) })
            }
        )
    }
}

object PeekNetwork {

    fun register() {
        // ⚠⚠ S2C 两步注册之一：服务端发送侧的类型注册。漏了**客户端启动即崩**
        //   （Fabric；NeoForge 的 playToClient 自带类型注册 ⇒ 不显形，别拿 NeoForge 跑通当没问题）
        registerS2CType(PeekActivePayload.ID, PeekActivePayload.CODEC)
        registerS2CType(PeekTargetsPayload.ID, PeekTargetsPayload.CODEC)

        // ⚠ C2S 必须写在**本函数体内**，并由 CobbleMarket.register 调用 ——
        //   插到别的函数里会编译通过但永不注册，第一次发包直接断连
        registerC2S(StartPeekPayload.ID, StartPeekPayload.CODEC) { payload, player ->
            // 防恶意客户端高频发包：每次 start 可能触发一次玩家数据落盘（写操作级冷却）
            if (!RequestThrottle.allowNotify(player, "peek", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            // 判据在 PeekService.start 里（共用 overview 节点、不认 OP）
            PeekService.start(player.server, player, payload.targetUuid)
        }
        registerC2S(StopPeekPayload.ID, StopPeekPayload.CODEC) { _, player ->
            // 连点退出键（或 HUD 消失前的重复按键）**静默丢弃**：第一次点击已经会走 stop 的完整反馈，
            // 再弹「操作太快」只是噪音。用独立 key，避免和 start 的冷却互相影响。
            if (!RequestThrottle.allow(player.uuid, "peek_stop", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            PeekService.stop(player.server, player)
        }
        registerC2S(RequestPeekTargetsPayload.ID, RequestPeekTargetsPayload.CODEC) { _, player ->
            // 读限流：面板 2 秒刷新一次，正常不会撞窗口；高频发包直接丢弃
            if (!RequestThrottle.allow(player.uuid, "peek_targets", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerC2S
            }
            // 与监视同一判据：共用 overview 节点（服主恒真、**不认 OP**）。
            // 面板显隐只是入口，这里才是真正拦人的地方
            if (!MarketPermissions.has(player, MarketPermissions.OVERVIEW)) return@registerC2S
            val entries = player.server.playerManager.playerList
                .asSequence()
                // 旁观者（含正在监视别人的 staff）不能当监视目标：跟着一个旁观者飞没有意义
                .filter { it.uuid != player.uuid && !it.isSpectator }
                .sortedBy { it.gameProfile.name.lowercase() }
                .map { PeekTargetEntry(uuid = it.uuid, name = it.gameProfile.name) }
                .toList()
            sendToPlayer(player, PeekTargetsPayload(entries))
        }
    }

    /** 把「是否正在监视」推给监视者本人（服务端状态变化时调用；调用方保证玩家在线） */
    fun sendActive(player: ServerPlayerEntity, active: Boolean) {
        sendToPlayer(player, PeekActivePayload(active))
    }
}
