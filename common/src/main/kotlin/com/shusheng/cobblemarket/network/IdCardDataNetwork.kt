package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.overview.PlayerOverviewRow
import com.shusheng.cobblemarket.overview.PlayerOverviewService
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/** 请求自己的 ID 卡数据（C2S）—— 打开 ID 卡界面时发一次。 */
data class RequestIdCardDataPayload(val dummy: Int = 0) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestIdCardDataPayload>(CobbleMarket.id("request_idcard_data"))
        val CODEC: PacketCodec<PacketByteBuf, RequestIdCardDataPayload> = PacketCodec.of(
            { _, _ -> },
            { _ -> RequestIdCardDataPayload() }
        )
    }
}

/**
 * 自己的 ID 卡数据（S2C，**单行**）。
 *
 * ⚠ 与全服总览的区别：**只回请求者自己那一行**，且**不走权限节点**（自己看自己，无争议）。
 * 数据本身复用 `PlayerOverviewService.buildRow`（内部走 `buildRows`）⇒ 与总览悬停窗**逐项一致**。
 *
 * row = null 只在极端情况下出现（理论上在线玩家一定在各表的并集里）—— 界面按"加载中"处理。
 */
data class IdCardDataPayload(val row: PlayerOverviewRow?) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<IdCardDataPayload>(CobbleMarket.id("idcard_data"))
        val CODEC: PacketCodec<PacketByteBuf, IdCardDataPayload> = PacketCodec.of(
            { p, b ->
                b.writeBoolean(p.row != null)
                p.row?.let { OverviewRowCodec.write(b, it) }
            },
            { b -> IdCardDataPayload(if (b.readBoolean()) OverviewRowCodec.read(b) else null) }
        )
    }
}

/**
 * ID 卡内容数据的网络通道（背景图那一路见 [IdCardNetwork]）。
 *
 * ⚠⚠ **S2C 是两步注册，缺一不可**：这里的 `registerS2CType`（漏了客户端启动即崩）+
 * 客户端 `CobbleMarketClient` 的 `registerS2C`（漏了静默无反应）。
 * ⚠ **NeoForge 不显形** ⇒ 漏第 1 步只在 Fabric 上崩。
 */
object IdCardDataNetwork {

    fun register() {
        registerS2CType(IdCardDataPayload.ID, IdCardDataPayload.CODEC)

        // ⚠ C2S 必须写在**本函数体内** —— 插到别的函数里会编译通过但永不注册，第一次发包直接断连
        registerC2S(RequestIdCardDataPayload.ID, RequestIdCardDataPayload.CODEC) { _, player ->
            sendToPlayer(player, IdCardDataPayload(PlayerOverviewService.buildRow(player.server, player.uuid)))
        }
    }
}
