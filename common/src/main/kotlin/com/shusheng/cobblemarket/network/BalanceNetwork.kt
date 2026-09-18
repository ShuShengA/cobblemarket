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
import net.minecraft.server.network.ServerPlayerEntity

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
    val balanceRaw: Long,
    /**
     * RP 余额（已千分位）。**和主货币同一份快照发出去，不新开包** ——
     * 分开发会出现「主货币已刷新、RP 还是旧的」中间态（方案「余额通道」）。
     * 空串 = 本服没开 RP（HUD 那一行整行不显示）。
     */
    val pointsBalance: String = "",
    /** RP 原始数值：HUD 变动浮字用差值对比 */
    val pointsRaw: Long = 0L
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<BalanceDataPayload>(CobbleMarket.id("balance_data"))
        val CODEC: PacketCodec<PacketByteBuf, BalanceDataPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.balance); b.writeLong(p.pendingBalance); b.writeString(p.currencyName); b.writeLong(p.balanceRaw)
                b.writeString(p.pointsBalance); b.writeLong(p.pointsRaw)
            },
            { b ->
                // ⚠ 读写顺序必须一致（payload 顺序铁律）；读端具名，错位一眼可见
                BalanceDataPayload(
                    balance = b.readString(),
                    pendingBalance = b.readLong(),
                    currencyName = b.readString(),
                    balanceRaw = b.readLong(),
                    pointsBalance = b.readString(),
                    pointsRaw = b.readLong()
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
    /**
     * RP 那一行的两个字段（已千分位的余额串 + 原始值）。
     * **总开关关着就返回空串** —— 客户端据此把那行整个收掉（方案：「RP 总开关关闭时不显示这一行，
     * 不用玩家手动关」）。与主货币**同一次快照**发出去，避免「主货币刷了、RP 还是旧的」中间态。
     */
    private fun pointsHudFields(player: ServerPlayerEntity): Pair<String, Long> {
        if (!com.shusheng.cobblemarket.config.CobbleMarketConfig.pointsEnabled) return "" to 0L
        val raw = com.shusheng.cobblemarket.currency.PointsState.get(player.server).getBalance(player.uuid)
        return formatBalance(java.math.BigInteger.valueOf(raw)) to raw
    }

    private fun formatBalance(bal: java.math.BigInteger): String {
        val billion = java.math.BigInteger.valueOf(1_000_000_000L)
        if (bal < billion) return bal.toString().reversed().chunked(3).joinToString(",").reversed()
        val tenths = bal.multiply(java.math.BigInteger.TEN).divide(billion).toLong()
        return "${tenths / 10}.${tenths % 10}B"
    }

    /**
     * 把最新余额直接推给该玩家。
     *
     * 喵喵银行的钱包变动（存取款/借款/还款/卡片付费/自动划扣）不走 MarketResultPayload，
     * 客户端不会像市场交易那样主动补拉，只能等 2 秒兜底轮询——这些操作后主动推一次，
     * HUD 数字即时刷新，也省掉客户端再发一次请求的往返。
     */
    fun sendBalanceTo(player: ServerPlayerEntity) {
        val raw = CurrencyHandler.getBalance(player)
        val (pointsBal, pointsRaw) = pointsHudFields(player)
        sendToPlayer(
            player,
            BalanceDataPayload(
                balance = formatBalance(raw),
                pendingBalance = MarketState.get(player.server).getPendingBalance(player.uuid),
                currencyName = CurrencyHandler.getCurrencyId(),
                balanceRaw = raw.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).toLong(),
                pointsBalance = pointsBal,
                pointsRaw = pointsRaw
            )
        )
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
                val (pointsBal, pointsRaw) = pointsHudFields(player)
                sendToPlayer(
                    player,
                    BalanceDataPayload(
                        balance = bal,
                        pendingBalance = pending,
                        currencyName = CurrencyHandler.getCurrencyId(),
                        balanceRaw = raw.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).toLong(),
                        pointsBalance = pointsBal,
                        pointsRaw = pointsRaw
                    )
                )
            }
        }
    }
}
