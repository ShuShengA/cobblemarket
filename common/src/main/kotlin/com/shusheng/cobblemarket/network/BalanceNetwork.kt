package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CurrencyHandler
import com.shusheng.cobblemarket.util.RequestThrottle
import com.shusheng.cobblemarket.market.MarketState
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
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

data class BalanceDataPayload(
    val balance: String,
    val pendingBalance: Long,
    val currencyName: String,
    /** 原始数值（千分位格式化前的 Long，超 Long 上限钳制）：客户端 HUD 变动提示用差值对比 */
    val balanceRaw: Long
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<BalanceDataPayload>(CobbleMarket.id("balance_data"))
        val CODEC: PacketCodec<PacketByteBuf, BalanceDataPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.balance); b.writeLong(p.pendingBalance); b.writeString(p.currencyName); b.writeLong(p.balanceRaw)
            },
            { b ->
                BalanceDataPayload(
                    balance = b.readString(),
                    pendingBalance = b.readLong(),
                    currencyName = b.readString(),
                    balanceRaw = b.readLong()
                )
            }
        )
    }
}

object BalanceNetwork {

    /**
     * 余额显示格式：不足 10 亿原样千分位（99,999,999），达到 10 亿起用 B 单位（1.5B）。
     * 不用 k/M（与物品市场网格缩写不同：大额余额以 B 为单位即可）。
     * 截断到 1 位小数（照 PriceFormat.oneDecimal 语义，不用舍入）。
     */
    private fun formatBalance(bal: java.math.BigInteger): String {
        val billion = java.math.BigInteger.valueOf(1_000_000_000L)
        if (bal < billion) return bal.toString().reversed().chunked(3).joinToString(",").reversed()
        val tenths = bal.multiply(java.math.BigInteger.TEN).divide(billion).toLong()
        return "${tenths / 10}.${tenths % 10}B"
    }

    fun register() {
        registerS2CType(BalanceDataPayload.ID, BalanceDataPayload.CODEC)

        registerC2S(RequestBalancePayload.ID, RequestBalancePayload.CODEC) { _, player ->
            // 与其他请求入口一致的节流：防高频轰炸
            if (!RequestThrottle.allow(player.uuid, "request_balance", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val raw = CurrencyHandler.getBalance(player)
                val bal = formatBalance(raw)
                val pending = MarketState.get(server).getPendingBalance(player.uuid)
                sendToPlayer(
                    player,
                    BalanceDataPayload(
                        bal,
                        pending,
                        CurrencyHandler.getCurrencyId(),
                        raw.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).toLong()
                    )
                )
            }
        }
    }
}
