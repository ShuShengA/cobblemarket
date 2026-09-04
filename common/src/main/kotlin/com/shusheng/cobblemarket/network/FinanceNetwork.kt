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
import java.util.UUID

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

// ── S2C：信用额度信息（可用额度/当前欠款/逾期禁借/坏账禁借） ──

data class CreditInfoPayload(
    val limit: Long,
    val debt: Long,
    val hasOverdue: Boolean,
    /** 有坏账（已注销）贷款：永久禁借，界面提示与逾期区分 */
    val hasBadDebt: Boolean,
    /** 分期方案文本（"3:0.005,6:0.008,12:0.012"），客户端借款界面渲染方案按钮用 */
    val plans: String,
    /** 金融总开关（入口喵喵银行按钮置灰依据） */
    val financeEnabled: Boolean,
    /** 消费贷（喵喵支付）开关 = 总开关 && consumerLoanEnabled（购买弹窗按钮显示依据） */
    val consumerLoanEnabled: Boolean,
    /** 喵喵紫卡持有者（补发按钮显示依据） */
    val hasPurpleCard: Boolean
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CreditInfoPayload>(CobbleMarket.id("credit_info"))
        val CODEC: PacketCodec<PacketByteBuf, CreditInfoPayload> = PacketCodec.of(
            { p, b ->
                b.writeLong(p.limit); b.writeLong(p.debt); b.writeBoolean(p.hasOverdue); b.writeBoolean(p.hasBadDebt)
                b.writeString(p.plans); b.writeBoolean(p.financeEnabled); b.writeBoolean(p.consumerLoanEnabled)
                b.writeBoolean(p.hasPurpleCard)
            },
            { b -> CreditInfoPayload(b.readLong(), b.readLong(), b.readBoolean(), b.readBoolean(), b.readString(), b.readBoolean(), b.readBoolean(), b.readBoolean()) }
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

// ── C2S：请求还款柜台列表（未结清贷款明细） ──

class RequestRepayListPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestRepayListPayload>(CobbleMarket.id("request_repay_list"))
        val CODEC: PacketCodec<PacketByteBuf, RequestRepayListPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestRepayListPayload() }
        )
    }
}

// ── S2C：还款柜台列表（金额由服务端实算，客户端只展示） ──

data class RepayEntry(
    val id: Long,
    val periodsTotal: Int,
    val periodsPaid: Int,
    /** 剩余本金（CLOSED 恒 0；列表只含未结清） */
    val remaining: Long,
    /** 本期应还本金（最后一期兜底整除尾差） */
    val periodPrincipal: Long,
    /** 本期利息（实算到服务端返回时刻） */
    val periodInterest: Long,
    /** 结清总额（剩余本金 + 利息，提前结清实扣基准） */
    val settleTotal: Long,
    val status: String,
    /** 已欠期数（到期期数 − 已还期数，OVERDUE 弹窗提示用） */
    val dueCount: Int
) {
    fun write(buf: PacketByteBuf) {
        buf.writeLong(id); buf.writeInt(periodsTotal); buf.writeInt(periodsPaid); buf.writeLong(remaining)
        buf.writeLong(periodPrincipal); buf.writeLong(periodInterest); buf.writeLong(settleTotal)
        buf.writeString(status); buf.writeInt(dueCount)
    }

    companion object {
        fun read(buf: PacketByteBuf) = RepayEntry(
            buf.readLong(), buf.readInt(), buf.readInt(), buf.readLong(),
            buf.readLong(), buf.readLong(), buf.readLong(),
            buf.readString(), buf.readInt()
        )
    }
}

data class RepayListDataPayload(val entries: List<RepayEntry>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RepayListDataPayload>(CobbleMarket.id("repay_list_data"))
        val CODEC: PacketCodec<PacketByteBuf, RepayListDataPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> RepayListDataPayload((0 until b.readVarInt()).map { RepayEntry.read(b) }) }
        )
    }
}

// ── C2S：还款执行（settle=false 还一期 / true 提前结清；金额服务端实算，客户端不传） ──

data class RequestRepayPayload(val loanId: Long, val settle: Boolean) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestRepayPayload>(CobbleMarket.id("request_repay"))
        val CODEC: PacketCodec<PacketByteBuf, RequestRepayPayload> = PacketCodec.of(
            { p, b -> b.writeLong(p.loanId); b.writeBoolean(p.settle) },
            { b -> RequestRepayPayload(b.readLong(), b.readBoolean()) }
        )
    }
}

// ── C2S：请求金融统计（全服累计成交额所有玩家可见；准备金池/坏账总额仅 OP） ──

class RequestFinanceStatsPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestFinanceStatsPayload>(CobbleMarket.id("request_finance_stats"))
        val CODEC: PacketCodec<PacketByteBuf, RequestFinanceStatsPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestFinanceStatsPayload() }
        )
    }
}

// ── S2C：金融统计（非 OP 时 reservePool/badDebtTotal 为 -1 表示无权限） ──

data class FinanceStatsPayload(
    val totalVolume: Long,
    val reservePool: Long,
    val badDebtTotal: Long
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<FinanceStatsPayload>(CobbleMarket.id("finance_stats"))
        val CODEC: PacketCodec<PacketByteBuf, FinanceStatsPayload> = PacketCodec.of(
            { p, b -> b.writeLong(p.totalVolume); b.writeLong(p.reservePool); b.writeLong(p.badDebtTotal) },
            { b -> FinanceStatsPayload(b.readLong(), b.readLong(), b.readLong()) }
        )
    }
}

// ── C2S：活期存款（批次 7.5；总开关关时拒绝存款，取款永远开放——钱是玩家的不能卡） ──

data class RequestDepositPayload(val amount: Long) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestDepositPayload>(CobbleMarket.id("request_deposit"))
        val CODEC: PacketCodec<PacketByteBuf, RequestDepositPayload> = PacketCodec.of(
            { p, b -> b.writeLong(p.amount) },
            { b -> RequestDepositPayload(b.readLong()) }
        )
    }
}

// ── C2S：取款（金额 ≤ 存款余额；取款不受总开关限制） ──

data class RequestWithdrawPayload(val amount: Long) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestWithdrawPayload>(CobbleMarket.id("request_withdraw"))
        val CODEC: PacketCodec<PacketByteBuf, RequestWithdrawPayload> = PacketCodec.of(
            { p, b -> b.writeLong(p.amount) },
            { b -> RequestWithdrawPayload(b.readLong()) }
        )
    }
}

// ── C2S：请求存款信息（余额/累计利息/日利率） ──

class RequestDepositInfoPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestDepositInfoPayload>(CobbleMarket.id("request_deposit_info"))
        val CODEC: PacketCodec<PacketByteBuf, RequestDepositInfoPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestDepositInfoPayload() }
        )
    }
}

// ── S2C：存款信息 ──

data class DepositInfoPayload(
    val balance: Long,
    val interest: Long,
    val rate: Double
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<DepositInfoPayload>(CobbleMarket.id("deposit_info"))
        val CODEC: PacketCodec<PacketByteBuf, DepositInfoPayload> = PacketCodec.of(
            { p, b -> b.writeLong(p.balance); b.writeLong(p.interest); b.writeDouble(p.rate) },
            { b -> DepositInfoPayload(b.readLong(), b.readLong(), b.readDouble()) }
        )
    }
}

// ── C2S：请求申请紫卡条件快照（打开申请界面时拉取） ──

class RequestPurpleCardApplyInfoPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestPurpleCardApplyInfoPayload>(CobbleMarket.id("request_purple_card_apply_info"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPurpleCardApplyInfoPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestPurpleCardApplyInfoPayload() }
        )
    }
}

// ── C2S：申请紫卡（服务端复核资格 + 扣申请费 + 发卡） ──

class RequestPurpleCardApplyPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestPurpleCardApplyPayload>(CobbleMarket.id("request_purple_card_apply"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPurpleCardApplyPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestPurpleCardApplyPayload() }
        )
    }
}

// ── S2C：申请条件快照（6 项条件 + 费用 + 资格 + 开关状态） ──

data class ApplyConditionEntry(
    val requirement: Long,
    val current: Long,
    val satisfied: Boolean
) {
    fun write(buf: PacketByteBuf) {
        buf.writeLong(requirement); buf.writeLong(current); buf.writeBoolean(satisfied)
    }

    companion object {
        fun read(buf: PacketByteBuf) = ApplyConditionEntry(buf.readLong(), buf.readLong(), buf.readBoolean())
    }
}

data class PurpleCardApplyInfoPayload(
    /** 6 项：资产/消费金额/额度/存款余额/图鉴数/无逾期（末项 requirement 0/1、current 0/1） */
    val conditions: List<ApplyConditionEntry>,
    val fee: Long,
    val eligible: Boolean,
    val selfApplyEnabled: Boolean,
    /** 已是持有者：界面按钮变「补发紫卡」 */
    val isHolder: Boolean
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<PurpleCardApplyInfoPayload>(CobbleMarket.id("purple_card_apply_info"))
        val CODEC: PacketCodec<PacketByteBuf, PurpleCardApplyInfoPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.conditions.size)
                p.conditions.forEach { it.write(b) }
                b.writeLong(p.fee)
                b.writeBoolean(p.eligible)
                b.writeBoolean(p.selfApplyEnabled)
                b.writeBoolean(p.isHolder)
            },
            { b ->
                PurpleCardApplyInfoPayload(
                    (0 until b.readVarInt()).map { ApplyConditionEntry.read(b) },
                    b.readLong(),
                    b.readBoolean(),
                    b.readBoolean(),
                    b.readBoolean()
                )
            }
        )
    }
}

// ── C2S：补发喵喵紫卡凭证（持有者丢弃后从喵喵银行重新领取） ──

class RequestPurpleCardRedoPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestPurpleCardRedoPayload>(CobbleMarket.id("request_purple_card_redo"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPurpleCardRedoPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestPurpleCardRedoPayload() }
        )
    }
}

// ── C2S：撤销玩家全部坏账（仅 OP；批次 7 服主干预双入口之一） ──

data class RequestRevokeBadDebtPayload(val playerUuid: UUID) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestRevokeBadDebtPayload>(CobbleMarket.id("request_revoke_bad_debt"))
        val CODEC: PacketCodec<PacketByteBuf, RequestRevokeBadDebtPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.playerUuid) },
            { b -> RequestRevokeBadDebtPayload(b.readUuid()) }
        )
    }
}

// ── S2C：借款历史数据 ──

data class LoanHistoryEntry(
    val id: Long,
    /** 借款人 UUID（OP 全服流水撤销坏账按钮用） */
    val playerUuid: UUID,
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
    val remaining: Long,
    /** 借款人最近 IP（仅 OP 全服流水下发，小号排查用；普通玩家视图为空串） */
    val ip: String
) {
    fun write(buf: PacketByteBuf) {
        buf.writeLong(id); buf.writeUuid(playerUuid); buf.writeLong(timestamp); buf.writeString(playerName); buf.writeLong(principal)
        buf.writeInt(periodsTotal); buf.writeInt(periodsPaid); buf.writeDouble(feeRate)
        buf.writeString(source); buf.writeString(status); buf.writeLong(remaining); buf.writeString(ip)
    }

    companion object {
        fun read(buf: PacketByteBuf) = LoanHistoryEntry(
            buf.readLong(),
            buf.readUuid(),
            buf.readLong(),
            buf.readString(),
            buf.readLong(),
            buf.readInt(),
            buf.readInt(),
            buf.readDouble(),
            buf.readString(),
            buf.readString(),
            buf.readLong(),
            buf.readString()
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
        registerS2CType(RepayListDataPayload.ID, RepayListDataPayload.CODEC)
        registerS2CType(FinanceStatsPayload.ID, FinanceStatsPayload.CODEC)
        registerS2CType(DepositInfoPayload.ID, DepositInfoPayload.CODEC)
        registerS2CType(PurpleCardApplyInfoPayload.ID, PurpleCardApplyInfoPayload.CODEC)

        // ── 应急贷款借款 ──
        registerC2S(RequestLoanPayload.ID, RequestLoanPayload.CODEC) { payload, player ->
            // 与写操作一致的节流：防高频轰炸（照其他写请求的 REPEAT_WRITE 间隔）
            if (!RequestThrottle.allow(player.uuid, "request_loan", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                val now = System.currentTimeMillis()
                // 文案区分：总开关关 = 喵喵银行没开门；现金贷关 = 喵喵的帮助暂未开放
                if (!CobbleMarketConfig.financeEnabled) {
                    player.sendMessage(Text.translatable("cobblemarket.loan.finance_disabled").formatted(Formatting.RED), false)
                    return@execute
                }
                if (!CobbleMarketConfig.cashLoanEnabled) {
                    player.sendMessage(Text.translatable("cobblemarket.loan.cash_loan_disabled").formatted(Formatting.RED), false)
                    return@execute
                }
                // 逾期/坏账禁止新增借贷（ACTIVE 不拦：允许多笔并存）；文案区分：逾期=暂时，坏账=永久
                val mine = state.getLoansByPlayer(player.uuid)
                if (mine.any { it.status == LoanStatus.BAD_DEBT }) {
                    player.sendMessage(Text.translatable("cobblemarket.loan.bad_debt_blocked").formatted(Formatting.RED), false)
                    return@execute
                }
                if (mine.any { it.status == LoanStatus.OVERDUE }) {
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

        // ── 申请紫卡条件快照 ──
        registerC2S(RequestPurpleCardApplyInfoPayload.ID, RequestPurpleCardApplyInfoPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "request_purple_card_apply_info", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute { sendPurpleCardApplyInfo(player) }
        }

        // ── 申请紫卡（复核资格 + 扣费 + 发卡） ──
        registerC2S(RequestPurpleCardApplyPayload.ID, RequestPurpleCardApplyPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "request_purple_card_apply", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                val now = System.currentTimeMillis()
                if (!CobbleMarketConfig.purpleCardSelfApply) {
                    player.sendMessage(Text.translatable("cobblemarket.card.apply_closed").formatted(Formatting.RED), false)
                    return@execute
                }
                val cash = CurrencyHandler.getBalance(player).toLong()
                val dex = com.shusheng.cobblemarket.finance.FinanceService.getCaughtSpeciesCount(server, player.uuid)
                if (!state.isPurpleCardEligible(player.uuid, cash, dex, now)) {
                    player.sendMessage(Text.translatable("cobblemarket.card.apply_not_eligible").formatted(Formatting.RED), false)
                    return@execute
                }
                val max = CobbleMarketConfig.purpleCardCount
                if (max > 0 && state.getPurpleCardHolderCount() >= max) {
                    player.sendMessage(Text.translatable("cobblemarket.card.cap_reached", max).formatted(Formatting.RED), false)
                    return@execute
                }
                val fee = CobbleMarketConfig.purpleCardApplyFee
                if (fee > 0) {
                    if (!com.shusheng.cobblemarket.finance.FinanceService.removeInChunks(player, fee)) {
                        player.sendMessage(Text.translatable("cobblemarket.card.apply_fee_missing", fee).formatted(Formatting.RED), false)
                        return@execute
                    }
                    state.depositReserve(fee)
                }
                state.addPurpleCardHolder(player.uuid)
                val item = net.minecraft.registry.Registries.ITEM.get(CobbleMarket.id("meowth_purple_card"))
                val added = player.inventory.insertStack(net.minecraft.item.ItemStack(item))
                if (!added) player.dropItem(net.minecraft.item.ItemStack(item), false)
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                player.sendMessage(Text.translatable("cobblemarket.card.apply_success").formatted(Formatting.GREEN), false)
                sendPurpleCardApplyInfo(player)
                sendCreditInfo(player, state, now)
            }
        }

        // ── 补发喵喵紫卡凭证（持有者从喵喵银行重新领取） ──
        registerC2S(RequestPurpleCardRedoPayload.ID, RequestPurpleCardRedoPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "request_purple_card_redo", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                if (!state.isPurpleCardHolder(player.uuid)) {
                    player.sendMessage(Text.translatable("cobblemarket.card.not_holder_self").formatted(Formatting.RED), false)
                    return@execute
                }
                val item = net.minecraft.registry.Registries.ITEM.get(CobbleMarket.id("meowth_purple_card"))
                val added = player.inventory.insertStack(net.minecraft.item.ItemStack(item))
                if (!added) player.dropItem(net.minecraft.item.ItemStack(item), false)
                player.sendMessage(Text.translatable("cobblemarket.card.redo_success").formatted(Formatting.GREEN), false)
            }
        }

        // ── 活期存款（总开关只拦存款，取款永远开放——钱是玩家的不能卡） ──
        registerC2S(RequestDepositPayload.ID, RequestDepositPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_deposit", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                val now = System.currentTimeMillis()
                if (!CobbleMarketConfig.financeEnabled) {
                    player.sendMessage(Text.translatable("cobblemarket.loan.finance_disabled").formatted(Formatting.RED), false)
                    return@execute
                }
                val amount = payload.amount
                if (amount <= 0 || amount > Int.MAX_VALUE) {
                    player.sendMessage(Text.translatable("cobblemarket.loan.invalid_amount").formatted(Formatting.RED), false)
                    return@execute
                }
                // 分批扣款（防 Int 溢出）+ 入池 + 记账（利息先结算入本金）
                if (!com.shusheng.cobblemarket.finance.FinanceService.removeInChunks(player, amount)) {
                    player.sendMessage(
                        Text.translatable(
                            "cobblemarket.network.need_diamonds",
                            com.shusheng.cobblemarket.config.CurrencyHandler.goldAmount(amount),
                            com.shusheng.cobblemarket.config.CurrencyHandler.goldCurrencyText()
                        ).formatted(Formatting.RED),
                        false
                    )
                    return@execute
                }
                state.depositReserve(amount)
                val newBalance = state.depositMoney(player.uuid, amount, now)
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                player.sendMessage(
                    Text.translatable(
                        "cobblemarket.deposit.success",
                        com.shusheng.cobblemarket.config.CurrencyHandler.goldAmount(amount),
                        com.shusheng.cobblemarket.config.CurrencyHandler.goldCurrencyText()
                    ).formatted(Formatting.GREEN),
                    false
                )
                sendDepositInfo(player, state, now)
            }
        }

        registerC2S(RequestWithdrawPayload.ID, RequestWithdrawPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_withdraw", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                val now = System.currentTimeMillis()
                val amount = payload.amount
                if (amount <= 0) {
                    player.sendMessage(Text.translatable("cobblemarket.loan.invalid_amount").formatted(Formatting.RED), false)
                    return@execute
                }
                val balance = state.getDepositBalance(player.uuid, now)
                if (balance <= 0) {
                    player.sendMessage(Text.translatable("cobblemarket.deposit.empty").formatted(Formatting.RED), false)
                    return@execute
                }
                // 结算取出：利息入账后取出；池出账 + 发放（背包满挂待领取兜底）
                val (principalPart, interestPart) = state.withdrawMoney(player.uuid, amount, now)
                if (principalPart + interestPart <= 0) {
                    player.sendMessage(Text.translatable("cobblemarket.deposit.empty").formatted(Formatting.RED), false)
                    return@execute
                }
                val take = principalPart + interestPart
                state.withdrawReserve(take)
                val given = com.shusheng.cobblemarket.config.CurrencyHandler.give(player, take)
                if (given < take) {
                    MarketState.get(server).addPendingBalance(player.uuid, take - given)
                }
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                player.sendMessage(
                    Text.translatable(
                        "cobblemarket.deposit.withdrawn",
                        com.shusheng.cobblemarket.config.CurrencyHandler.goldAmount(take),
                        com.shusheng.cobblemarket.config.CurrencyHandler.goldCurrencyText(),
                        com.shusheng.cobblemarket.config.CurrencyHandler.goldAmount(interestPart)
                    ).formatted(Formatting.GREEN),
                    false
                )
                sendDepositInfo(player, state, now)
            }
        }

        registerC2S(RequestDepositInfoPayload.ID, RequestDepositInfoPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "request_deposit_info", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute { sendDepositInfo(player, FinanceState.get(server), System.currentTimeMillis()) }
        }

        // ── 金融统计（全服累计成交额所有人可见；准备金池/坏账总额仅 OP） ──
        registerC2S(RequestFinanceStatsPayload.ID, RequestFinanceStatsPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "request_finance_stats", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                val isOp = player.hasPermissionLevel(2)
                sendToPlayer(
                    player,
                    FinanceStatsPayload(
                        totalVolume = state.getTotalTradingVolume(),
                        reservePool = if (isOp) state.getReservePool() else -1,
                        badDebtTotal = if (isOp) state.getBadDebtTotal() else -1
                    )
                )
            }
        }

        // ── 撤销玩家全部坏账（仅 OP；与 /market loan clear 命令双入口） ──
        registerC2S(RequestRevokeBadDebtPayload.ID, RequestRevokeBadDebtPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_revoke_bad_debt", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                if (!player.hasPermissionLevel(2)) return@execute
                val state = FinanceState.get(server)
                val removed = state.clearBadDebts(payload.playerUuid)
                if (removed.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    removed.forEach { record ->
                        CreditFileLogger.logLoan(
                            record, com.shusheng.cobblemarket.finance.LoanLogType.REVOKED,
                            detail = "服主撤销坏账",
                            detailEn = "Bad debt revoked by admin",
                            timestamp = now
                        )
                    }
                    // 撤销后重新评估冻结（坏账冻结随记录消失解除；仍逾期 ≥14 天的贷款保持冻结）
                    com.shusheng.cobblemarket.finance.FinanceService.syncFreeze(server, payload.playerUuid)
                    com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                    player.sendMessage(
                        Text.translatable("cobblemarket.repay.revoke_success", removed.first().playerName, removed.size)
                            .formatted(Formatting.GREEN),
                        false
                    )
                    // 回发全服流水刷新（撤销按钮所在界面）
                    sendToPlayer(
                        player,
                        LoanHistoryDataPayload(buildLoanHistoryEntries(state, state.getAllLoans(), all = true))
                    )
                } else {
                    player.sendMessage(
                        Text.translatable("cobblemarket.repay.revoke_none").formatted(Formatting.RED),
                        false
                    )
                }
            }
        }

        // ── 还款柜台列表 ──
        registerC2S(RequestRepayListPayload.ID, RequestRepayListPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "request_repay_list", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute { com.shusheng.cobblemarket.finance.FinanceService.sendRepayList(player) }
        }

        // ── 还款执行（还一期 / 提前结清；金额服务端实算） ──
        registerC2S(RequestRepayPayload.ID, RequestRepayPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_repay", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute { com.shusheng.cobblemarket.finance.FinanceService.executeRepay(player, payload.loanId, payload.settle) }
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
                val entries = buildLoanHistoryEntries(state, loans, all = payload.all && player.hasPermissionLevel(2))
                sendToPlayer(player, LoanHistoryDataPayload(entries))
                sendToPlayer(player, LoanHistoryDataPayload(entries))
            }
        }
    }

    /** 申请紫卡条件快照（打开界面/申请后回发）：6 项条件 + 费用 + 资格 + 开关 */
    private fun sendPurpleCardApplyInfo(player: ServerPlayerEntity) {
        val server = player.server
        val state = FinanceState.get(server)
        val now = System.currentTimeMillis()
        val cash = CurrencyHandler.getBalance(player).toLong()
        val volume = state.getTotalCountedVolumeOf(player.uuid)
        val deposit = state.getDepositBalance(player.uuid, now)
        val dex = com.shusheng.cobblemarket.finance.FinanceService.getCaughtSpeciesCount(server, player.uuid)
        val debt = state.getLoansByPlayer(player.uuid)
            .filter { it.status != LoanStatus.CLOSED && it.status != LoanStatus.BAD_DEBT }
            .sumOf { it.remainingPrincipal.toLong() }
        val creditBase = state.creditLimitFor(player.uuid, now) + debt
        val hasBadRecord = state.getLoansByPlayer(player.uuid)
            .any { it.status == LoanStatus.OVERDUE || it.status == LoanStatus.BAD_DEBT }
        val conditions = listOf(
            ApplyConditionEntry(CobbleMarketConfig.purpleCardApplyAsset, cash, cash >= CobbleMarketConfig.purpleCardApplyAsset),
            ApplyConditionEntry(CobbleMarketConfig.purpleCardApplyVolume, volume, volume >= CobbleMarketConfig.purpleCardApplyVolume),
            ApplyConditionEntry(CobbleMarketConfig.purpleCardApplyCredit, creditBase, creditBase >= CobbleMarketConfig.purpleCardApplyCredit),
            ApplyConditionEntry(CobbleMarketConfig.purpleCardApplyDeposit, deposit, deposit >= CobbleMarketConfig.purpleCardApplyDeposit),
            ApplyConditionEntry(CobbleMarketConfig.purpleCardApplyDex, dex.toLong(), dex.toLong() >= CobbleMarketConfig.purpleCardApplyDex),
            ApplyConditionEntry(
                if (CobbleMarketConfig.purpleCardApplyNoOverdue) 1L else 0L,
                if (hasBadRecord) 0L else 1L,
                !CobbleMarketConfig.purpleCardApplyNoOverdue || !hasBadRecord
            ),
        )
        sendToPlayer(
            player,
            PurpleCardApplyInfoPayload(
                conditions = conditions,
                fee = CobbleMarketConfig.purpleCardApplyFee,
                eligible = state.isPurpleCardEligible(player.uuid, cash, dex, now),
                selfApplyEnabled = CobbleMarketConfig.purpleCardSelfApply,
                isHolder = state.isPurpleCardHolder(player.uuid)
            )
        )
    }

    /** 存款信息回发（存取成功后刷新界面） */
    private fun sendDepositInfo(player: ServerPlayerEntity, state: FinanceState, now: Long) {
        val interest = state.depositInterest(player.uuid, now)
        sendToPlayer(
            player,
            DepositInfoPayload(
                balance = state.getDepositBalance(player.uuid, now),
                interest = interest,
                rate = CobbleMarketConfig.dailyDepositRate
            )
        )
    }

    /** 借款历史条目构造（借款历史请求与撤销坏账后回发共用）；all=true 时带最近 IP（小号排查） */
    private fun buildLoanHistoryEntries(
        state: FinanceState,
        loans: List<com.shusheng.cobblemarket.finance.LoanRecord>,
        all: Boolean
    ): List<LoanHistoryEntry> =
        loans.sortedByDescending { it.id }.map { r ->
            LoanHistoryEntry(
                id = r.id,
                playerUuid = r.playerUuid,
                timestamp = r.createdAt,
                playerName = r.playerName,
                principal = r.principal,
                periodsTotal = r.periodsTotal,
                periodsPaid = r.periodsPaid,
                feeRate = r.dailyRate * 7.0,
                source = r.source.name,
                status = r.status.name,
                remaining = r.remainingPrincipal,
                ip = if (all)
                    state.getIpEntries(r.playerUuid, System.currentTimeMillis()).maxByOrNull { it.at }?.ip ?: ""
                else ""
            )
        }

    /** 额度信息快照：可用额度 + 欠款 + 逾期/坏账禁借标记（借款成功/还款后也用它回发刷新） */
    fun sendCreditInfo(player: ServerPlayerEntity, state: FinanceState, now: Long) {
        val mine = state.getLoansByPlayer(player.uuid)
        val debt = mine.filter { it.status != LoanStatus.CLOSED && it.status != LoanStatus.BAD_DEBT }
            .sumOf { it.remainingPrincipal.toLong() }
        sendToPlayer(
            player,
            CreditInfoPayload(
                limit = state.creditLimitFor(player.uuid, now),
                debt = debt,
                hasOverdue = mine.any { it.status == LoanStatus.OVERDUE },
                hasBadDebt = mine.any { it.status == LoanStatus.BAD_DEBT },
                plans = CobbleMarketConfig.loanPlansText(),
                financeEnabled = CobbleMarketConfig.financeEnabled,
                consumerLoanEnabled = CobbleMarketConfig.financeEnabled && CobbleMarketConfig.consumerLoanEnabled,
                hasPurpleCard = state.isPurpleCardHolder(player.uuid)
            )
        )
    }
}
