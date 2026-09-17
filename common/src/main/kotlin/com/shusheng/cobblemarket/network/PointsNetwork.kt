package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.PointRedeemService
import com.shusheng.cobblemarket.currency.RedeemOutcome
import com.shusheng.cobblemarket.platform.onPlayerJoin
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.text.Text

/**
 * 兑换码输入上限：外部码 32 位按 4 位分组后是 39 字符（32 + 7 个连字符），这里留一倍余量。
 * **超长直接在协议层拒绝**（读串越界会抛异常）——不给伪造客户端塞超长串做无谓解析的机会。
 */
private const val MAX_CODE_LENGTH = 64

/**
 * 点券总开关（S2C）：登录时下发一次，客户端据此决定「点券市场」入口按钮是否置灰。
 * ⚠ 与市场总开关（[MarketStatePayload]）的区别：那个是**紧急停市**，按钮仍有用（看列表/取回资产）；
 * 点券总开关管的是「这个服不用点券」—— 界面进不去也没损失，所以按钮照喵喵银行的做法**置灰**。
 */
data class PointsStatusPayload(val enabled: Boolean) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PointsStatusPayload>(CobbleMarket.id("points_status"))
        val CODEC: PacketCodec<PacketByteBuf, PointsStatusPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.enabled) },
            { b -> PointsStatusPayload(b.readBoolean()) }
        )
    }
}

/** 玩家在点券市场界面里兑换（命令 `/market points redeem` 是同一个内核的另一入口） */
data class RedeemPointCodePayload(val code: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RedeemPointCodePayload>(CobbleMarket.id("redeem_point_code"))
        val CODEC: PacketCodec<PacketByteBuf, RedeemPointCodePayload> = PacketCodec.of(
            { p, b -> b.writeString(p.code, MAX_CODE_LENGTH) },
            { b -> RedeemPointCodePayload(b.readString(MAX_CODE_LENGTH)) }
        )
    }
}

/**
 * **点券网络层**（GUI 侧）。
 *
 * 与命令侧的关系：两边**共用同一批内核**（兑换走 [PointRedeemService]，充值订单走
 * `PointShopService`），网络层只负责「收包 → 主线程 → 回包」这一层壳，
 * 绝不各自实现一套发放逻辑（方案风险清单第 12 条）。
 */
object PointsNetwork {

    fun register() {
        registerC2S(RedeemPointCodePayload.ID, RedeemPointCodePayload.CODEC) { payload, player ->
            val server = player.server
            // 整段在主线程：「先扣次数再加余额」不可被打断（见 PointRedeemService）
            server.execute {
                // ⚠ 这里**不查 pointsEnabled**：总开关管的是「玩家之间用点券交易」，
                // 而兑换是发放路径 —— 命令侧 `/market points redeem` 同样不查，两边保持一致
                when (val outcome = PointRedeemService.redeem(server, player, payload.code)) {
                    is RedeemOutcome.Success -> sendToPlayer(
                        player,
                        MarketResultPayload(
                            true,
                            Text.translatable(
                                "cobblemarket.points.redeem_ok",
                                CurrencyService.amountText(outcome.amount, MarketCurrency.POINTS),
                                CurrencyService.amountText(outcome.balanceAfter, MarketCurrency.POINTS)
                            )
                        )
                    )

                    RedeemOutcome.Locked -> sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.points.redeem_locked"))
                    )

                    RedeemOutcome.Invalid -> sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.points.redeem_invalid"))
                    )

                    // 节流：静默丢弃（与命令侧一致，不值得回一条消息）
                    RedeemOutcome.Throttled -> {}
                }
            }
        }

        registerS2CType(PointsStatusPayload.ID, PointsStatusPayload.CODEC)
        // 登录补发总开关：客户端据此把入口按钮置灰（照市场总开关的补发时机）
        onPlayerJoin { player ->
            sendToPlayer(player, PointsStatusPayload(CobbleMarketConfig.pointsEnabled))
        }
    }
}
