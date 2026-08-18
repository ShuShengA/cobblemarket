package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CurrencyHandler
import com.shusheng.cobblemarket.util.RequestThrottle
import com.shusheng.cobblemarket.market.MarketState
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

// ── C2S：请求余额 ──

class RequestBalancePayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestBalancePayload>(CobbleMarket.id("request_balance"))
        val CODEC: PacketCodec<PacketByteBuf, RequestBalancePayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestBalancePayload() }
        )
    }
}

// ── S2C：余额数据（balance 已做千分位格式化，客户端直接显示） ──

data class BalanceDataPayload(val balance: String, val pendingBalance: Long) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<BalanceDataPayload>(CobbleMarket.id("balance_data"))
        val CODEC: PacketCodec<PacketByteBuf, BalanceDataPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.balance); b.writeLong(p.pendingBalance) },
            { b -> BalanceDataPayload(b.readString(), b.readLong()) }
        )
    }
}

object BalanceNetwork {
    fun register() {
        PayloadTypeRegistry.playC2S().register(RequestBalancePayload.ID, RequestBalancePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BalanceDataPayload.ID, BalanceDataPayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(RequestBalancePayload.ID) { _, context ->
            val player = context.player()
            // 与其他请求入口一致的节流：防高频轰炸
            if (!RequestThrottle.allow(player.uuid, "request_balance", RequestThrottle.READ_INTERVAL_MS)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val bal = CurrencyHandler.getBalance(player)
                    .toString().reversed().chunked(3).joinToString(",").reversed()
                val pending = MarketState.get(server).getPendingBalance(player.uuid)
                ServerPlayNetworking.send(player, BalanceDataPayload(bal, pending))
            }
        }
    }
}
