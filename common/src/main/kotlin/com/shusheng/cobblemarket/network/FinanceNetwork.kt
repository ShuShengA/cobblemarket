package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.config.CurrencyHandler
import com.shusheng.cobblemarket.finance.CreditFileLogger
import com.shusheng.cobblemarket.finance.FinanceState
import com.shusheng.cobblemarket.finance.LoanLogType
import com.shusheng.cobblemarket.finance.LoanSource
import com.shusheng.cobblemarket.finance.LoanStatus
import com.shusheng.cobblemarket.market.MarketState
import com.shusheng.cobblemarket.platform.getPlayerIp
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import com.shusheng.cobblemarket.util.PersistHelper
import com.shusheng.cobblemarket.util.RequestThrottle
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting

// ── C2S：应急贷款借款请求（金额 + 分期方案下标） ──

data class RequestLoanPayload(val amount: Long, val planIndex: Int) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestLoanPayload>(CobbleMarket.id("request_loan"))
        val CODEC: PacketCodec<PacketByteBuf, RequestLoanPayload> = PacketCodec.of(
            { p, b -> b.writeLong(p.amount); b.writeInt(p.planIndex) },
            { b -> RequestLoanPayload(b.readLong(), b.readInt()) }
        )
    }
}

// ── C2S：请求信用额度信息（喵喵银行/应急贷款界面打开时拉取） ──

class RequestCreditInfoPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestCreditInfoPayload>(CobbleMarket.id("request_credit_info"))
        val CODEC: PacketCodec<PacketByteBuf, RequestCreditInfoPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestCreditInfoPayload() }
        )
    }
}

// ── S2C：信用额度信息（可用额度/当前欠款/是否有逾期禁借） ──

data class CreditInfoPayload(
    val limit: Long,
    val debt: Long,
    val hasOverdue: Boolean,
    /** 分期方案文本（"3:0.005,6:0.008,12:0.012"），客户端借款界面渲染方案按钮用 */
    val plans: String
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CreditInfoPayload>(CobbleMarket.id("credit_info"))
        val CODEC: PacketCodec<PacketByteBuf, CreditInfoPayload> = PacketCodec.of(
            { p, b ->
                b.writeLong(p.limit); b.writeLong(p.debt); b.writeBoolean(p.hasOverdue); b.writeString(p.plans)
            },
            { b -> CreditInfoPayload(b.readLong(), b.readLong(), b.readBoolean(), b.readString()) }
        )
    }
}

// ── C2S：请求借款历史（all=true 仅 OP 有效，全服借贷流水） ──

data class RequestLoanHistoryPayload(val all: Boolean) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestLoanHistoryPayload>(CobbleMarket.id("request_loan_history"))
        val CODEC: PacketCodec<PacketByteBuf, RequestLoanHistoryPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.all) },
            { b -> RequestLoanHistoryPayload(b.readBoolean()) }
        )
    }
}

// ── S2C：借款历史数据 ──

data class LoanHistoryEntry(
    val id: Long,
    /** 借款时刻（创建时间，非还款刷新过的 lastRepayAt） */
    val timestamp: Long,
    val playerName: String,
    val principal: Long,
    val periodsTotal: Int,
    val periodsPaid: Int,
    /** 每期费率（dailyRate×7 回算展示，UI 只显示每期费率不写年化） */
    val feeRate: Double,
    val source: String,
    val status: String,
    /** 剩余本金（CLOSED 恒 0） */
    val remaining: Long
) {
    fun write(buf: PacketByteBuf) {
        buf.writeLong(id); buf.writeLong(timestamp); buf.writeString(playerName); buf.writeLong(principal)
        buf.writeInt(periodsTotal); buf.writeInt(periodsPaid); buf.writeDouble(feeRate)
        buf.writeString(source); buf.writeString(status); buf.writeLong(remaining)
    }

    companion object {
        fun read(buf: PacketByteBuf) = LoanHistoryEntry(
            buf.readLong(),
            buf.readLong(),
            buf.readString(),
            buf.readLong(),
            buf.readInt(),
            buf.readInt(),
            buf.readDouble(),
            buf.readString(),
            buf.readString(),
            buf.readLong()
        )
    }
}

data class LoanHistoryDataPayload(val entries: List<LoanHistoryEntry>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<LoanHistoryDataPayload>(CobbleMarket.id("loan_history_data"))
        val CODEC: PacketCodec<PacketByteBuf, LoanHistoryDataPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> LoanHistoryDataPayload((0 until b.readVarInt()).map { LoanHistoryEntry.read(b) }) }
        )
    }
}

object FinanceNetwork {

    fun register() {
        registerS2CType(CreditInfoPayload.ID, CreditInfoPayload.CODEC)
        registerS2CType(LoanHistoryDataPayload.ID, LoanHistoryDataPayload.CODEC)

        // ── 应急贷款借款 ──
        registerC2S(RequestLoanPayload.ID, RequestLoanPayload.CODEC) { payload, player ->
            // 与写操作一致的节流：防高频轰炸（照其他写请求的 REPEAT_WRITE 间隔）
            if (!RequestThrottle.allow(player.uuid, "request_loan", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                val now = System.currentTimeMillis()
                if (!CobbleMarketConfig.financeEnabled || !CobbleMarketConfig.cashLoanEnabled) {
                    player.sendMessage(Text.translatable("cobblemarket.loan.finance_disabled").formatted(Formatting.RED), false)
                    return@execute
                }
                // 逾期/坏账禁止新增借贷（ACTIVE 不拦：允许多笔并存）
                if (state.getLoansByPlayer(player.uuid).any { it.status == LoanStatus.OVERDUE || it.status == LoanStatus.BAD_DEBT }) {
                    player.sendMessage(Text.translatable("cobblemarket.loan.overdue_blocked").formatted(Formatting.RED), false)
                    return@execute
                }
                val plan = CobbleMarketConfig.loanPlans.getOrNull(payload.planIndex)
                if (plan == null) {
                    player.sendMessage(Text.translatable("cobblemarket.loan.invalid_plan").formatted(Formatting.RED), false)
                    return@execute
                }
                val amount = payload.amount
                // CurrencyHandler.remove 为 Int 签名（批次 5 还款划扣侧），借款金额钳制在 Int 范围内防后续截断
                if (amount <= 0 || amount > Int.MAX_VALUE) {
                    player.sendMessage(Text.translatable("cobblemarket.loan.invalid_amount").formatted(Formatting.RED), false)
                    return@execute
                }
                val limit = state.creditLimitFor(player.uuid, now)
                if (amount > limit) {
                    player.sendMessage(
                        Text.translatable(
                            "cobblemarket.loan.insufficient_limit",
                            CurrencyHandler.goldAmount(limit),
                            CurrencyHandler.goldCurrencyText()
                        ).formatted(Formatting.RED),
                        false
                    )
                    return@execute
                }
                // 同 IP 聚合欠款上限：防同 IP 多小号分散借款转给主账号（OP 豁免；0=不限制）
                val ip = getPlayerIp(player)
                if (CobbleMarketConfig.ipDebtLimit > 0 && ip != null && !player.hasPermissionLevel(2)) {
                    val ipDebt = state.debtByIp(ip, now)
                    if (ipDebt + amount > CobbleMarketConfig.ipDebtLimit) {
                        player.sendMessage(
                            Text.translatable(
                                "cobblemarket.loan.ip_limit",
                                CurrencyHandler.goldAmount(CobbleMarketConfig.ipDebtLimit),
                                CurrencyHandler.goldCurrencyText()
                            ).formatted(Formatting.RED),
                            false
                        )
                        return@execute
                    }
                }
                // 准备金出账（允许为负 = 服主负债）→ 入钱包；物品货币模式背包满 → 未发放部分挂待领取余额（钱不丢）
                state.withdrawReserve(amount)
                val given = CurrencyHandler.give(player, amount)
                if (given < amount) {
                    MarketState.get(server).addPendingBalance(player.uuid, amount - given)
                }
                val record = state.createLoan(
                    playerUuid = player.uuid,
                    playerName = player.name.string,
                    principal = amount,
                    periodsTotal = plan.periods,
                    dailyRate = plan.feeRate / 7.0,
                    source = LoanSource.COUNTER,
                    now = now
                )
                getPlayerIp(player)?.let { state.recordIp(player.uuid, it, now) }
                CreditFileLogger.logLoan(
                    record, LoanLogType.CREATED,
                    detail = "柜台借款，${plan.periods}期",
                    detailEn = "Counter loan, ${plan.periods} periods"
                )
                player.sendMessage(
                    Text.translatable(
                        "cobblemarket.loan.success",
                        CurrencyHandler.goldAmount(amount),
                        CurrencyHandler.goldCurrencyText(),
                        plan.periods
                    ).formatted(Formatting.GREEN),
                    false
                )
                // 回发额度快照：应急贷款界面即时刷新可用额度
                sendCreditInfo(player, state, now)
                // 借款后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                PersistHelper.requestSave(server)
            }
        }

        // ── 信用额度信息 ──
        registerC2S(RequestCreditInfoPayload.ID, RequestCreditInfoPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "request_credit_info", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                sendCreditInfo(player, FinanceState.get(server), System.currentTimeMillis())
            }
        }

        // ── 借款历史 ──
        registerC2S(RequestLoanHistoryPayload.ID, RequestLoanHistoryPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_loan_history", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                // all=true 仅 OP 有效（全服借贷流水审计），普通玩家回退为本人借款历史
                val loans = if (payload.all && player.hasPermissionLevel(2)) state.getAllLoans()
                else state.getLoansByPlayer(player.uuid)
                val entries = loans.sortedByDescending { it.id }.map { r ->
                    LoanHistoryEntry(
                        id = r.id,
                        timestamp = r.createdAt,
                        playerName = r.playerName,
                        principal = r.principal,
                        periodsTotal = r.periodsTotal,
                        periodsPaid = r.periodsPaid,
                        feeRate = r.dailyRate * 7.0,
                        source = r.source.name,
                        status = r.status.name,
                        remaining = r.remainingPrincipal
                    )
                }
                sendToPlayer(player, LoanHistoryDataPayload(entries))
            }
        }
    }

    /** 额度信息快照：可用额度 + 欠款 + 逾期禁借标记（借款成功后也用它回发刷新） */
    private fun sendCreditInfo(player: ServerPlayerEntity, state: FinanceState, now: Long) {
        val mine = state.getLoansByPlayer(player.uuid)
        val debt = mine.filter { it.status != LoanStatus.CLOSED }.sumOf { it.remainingPrincipal.toLong() }
        sendToPlayer(
            player,
            CreditInfoPayload(
                limit = state.creditLimitFor(player.uuid, now),
                debt = debt,
                hasOverdue = mine.any { it.status == LoanStatus.OVERDUE || it.status == LoanStatus.BAD_DEBT },
                plans = CobbleMarketConfig.loanPlansText()
            )
        )
    }
}
