package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.config.CurrencyHandler
import com.shusheng.cobblemarket.finance.CreditFileLogger
import com.shusheng.cobblemarket.finance.FinanceChangeLogger
import com.shusheng.cobblemarket.finance.FinanceState
import com.shusheng.cobblemarket.finance.LoanLogType
import com.shusheng.cobblemarket.finance.LoanSource
import com.shusheng.cobblemarket.finance.LoanStatus
import com.shusheng.cobblemarket.market.BanState
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
import net.minecraft.server.MinecraftServer
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

// ── C2S：请求借款历史（all=true 需金融管理节点，全服借贷流水） ──

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
    val dueCount: Int,
    /** 下期到期时刻（epoch ms）：服务端按 LoanRecord.PERIOD_MS_LONG 算，还款弹窗「下期到期还有 X 天 XX 小时」用 */
    val nextDueAt: Long
) {
    fun write(buf: PacketByteBuf) {
        buf.writeLong(id); buf.writeInt(periodsTotal); buf.writeInt(periodsPaid); buf.writeLong(remaining)
        buf.writeLong(periodPrincipal); buf.writeLong(periodInterest); buf.writeLong(settleTotal)
        buf.writeString(status); buf.writeInt(dueCount); buf.writeLong(nextDueAt)
    }

    companion object {
        fun read(buf: PacketByteBuf) = RepayEntry(
            buf.readLong(), buf.readInt(), buf.readInt(), buf.readLong(),
            buf.readLong(), buf.readLong(), buf.readLong(),
            buf.readString(), buf.readInt(), buf.readLong()
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

// ── C2S：请求金融统计（全服累计成交额所有玩家可见；准备金池/坏账总额限金融管理节点） ──

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

// ── S2C：申请条件快照（7 项条件 + 费用 + 资格 + 开关状态） ──

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
    /** 7 项：资产/消费金额/额度/存款余额/图鉴遇见数/图鉴捕捉数/无逾期（末项 requirement 0/1、current 0/1） */
    val conditions: List<ApplyConditionEntry>,
    val fee: Long,
    val eligible: Boolean,
    val selfApplyEnabled: Boolean,
    /** 已是持有者：界面按钮变「补发紫卡」 */
    val isHolder: Boolean,
    /** 补发凭证费用（持有者界面显示补发费用行） */
    val redoFee: Long,
    /** 已持有金轮卡（升级替代）：紫卡申请按钮显示「已升级」而非「条件未满足」 */
    val holdsBlackCard: Boolean,
    /** 卡的借款额度（界面权益展示） */
    val creditLimit: Long,
    /** 持有者手续费减免比例 0~1（界面权益展示；0 = 无减免） */
    val feeDiscount: Double
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
                b.writeLong(p.redoFee)
                b.writeBoolean(p.holdsBlackCard)
                b.writeLong(p.creditLimit)
                b.writeDouble(p.feeDiscount)
            },
            { b ->
                PurpleCardApplyInfoPayload(
                    conditions = (0 until b.readVarInt()).map { ApplyConditionEntry.read(b) },
                    fee = b.readLong(),
                    eligible = b.readBoolean(),
                    selfApplyEnabled = b.readBoolean(),
                    isHolder = b.readBoolean(),
                    redoFee = b.readLong(),
                    holdsBlackCard = b.readBoolean(),
                    creditLimit = b.readLong(),
                    feeDiscount = b.readDouble()
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

// ── 黑卡协议（照搬紫卡；申请硬条件=持有紫卡，条件快照多一项「持有紫卡」） ──

// ── C2S：请求申请黑卡条件快照（打开申请界面时拉取） ──

class RequestBlackCardApplyInfoPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestBlackCardApplyInfoPayload>(CobbleMarket.id("request_black_card_apply_info"))
        val CODEC: PacketCodec<PacketByteBuf, RequestBlackCardApplyInfoPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestBlackCardApplyInfoPayload() }
        )
    }
}

// ── C2S：申请黑卡（服务端复核资格 + 扣申请费 + 发卡） ──

class RequestBlackCardApplyPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestBlackCardApplyPayload>(CobbleMarket.id("request_black_card_apply"))
        val CODEC: PacketCodec<PacketByteBuf, RequestBlackCardApplyPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestBlackCardApplyPayload() }
        )
    }
}

// ── S2C：申请条件快照（8 项：持有紫卡硬条件 + 七项门槛 + 费用 + 资格 + 开关状态） ──

data class BlackCardApplyInfoPayload(
    /** 7 项：持有紫卡（硬条件）+ 资产/消费金额/额度/存款余额/图鉴数/无逾期 */
    val conditions: List<ApplyConditionEntry>,
    val fee: Long,
    val eligible: Boolean,
    val selfApplyEnabled: Boolean,
    /** 已是持有者：界面按钮变「补发黑卡」 */
    val isHolder: Boolean,
    /** 补发凭证费用（持有者界面显示补发费用行） */
    val redoFee: Long,
    /** 卡的借款额度（界面权益展示） */
    val creditLimit: Long,
    /** 持有者手续费减免比例 0~1（界面权益展示；0 = 无减免） */
    val feeDiscount: Double
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<BlackCardApplyInfoPayload>(CobbleMarket.id("black_card_apply_info"))
        val CODEC: PacketCodec<PacketByteBuf, BlackCardApplyInfoPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.conditions.size)
                p.conditions.forEach { it.write(b) }
                b.writeLong(p.fee)
                b.writeBoolean(p.eligible)
                b.writeBoolean(p.selfApplyEnabled)
                b.writeBoolean(p.isHolder)
                b.writeLong(p.redoFee)
                b.writeLong(p.creditLimit)
                b.writeDouble(p.feeDiscount)
            },
            { b ->
                BlackCardApplyInfoPayload(
                    conditions = (0 until b.readVarInt()).map { ApplyConditionEntry.read(b) },
                    fee = b.readLong(),
                    eligible = b.readBoolean(),
                    selfApplyEnabled = b.readBoolean(),
                    isHolder = b.readBoolean(),
                    redoFee = b.readLong(),
                    creditLimit = b.readLong(),
                    feeDiscount = b.readDouble()
                )
            }
        )
    }
}

// ── C2S：补发喵喵黑卡凭证（持有者丢弃后从喵喵银行重新领取） ──

class RequestBlackCardRedoPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestBlackCardRedoPayload>(CobbleMarket.id("request_black_card_redo"))
        val CODEC: PacketCodec<PacketByteBuf, RequestBlackCardRedoPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestBlackCardRedoPayload() }
        )
    }
}

// ── 卡片管理（金融管理节点）：持有者列表 + 界面内收回 ──

// ── C2S：请求紫/黑卡持有者列表（卡片管理界面打开时拉取） ──

class RequestCardHolderListPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestCardHolderListPayload>(CobbleMarket.id("request_card_holder_list"))
        val CODEC: PacketCodec<PacketByteBuf, RequestCardHolderListPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestCardHolderListPayload() }
        )
    }
}

// ── C2S：收回指定玩家的卡（kind = purple/black；服务端复核 OP 后执行并回发新列表） ──

data class RequestCardRevokePayload(val uuid: UUID, val kind: String) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestCardRevokePayload>(CobbleMarket.id("request_card_revoke"))
        val CODEC: PacketCodec<PacketByteBuf, RequestCardRevokePayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.uuid); b.writeString(p.kind) },
            { b -> RequestCardRevokePayload(b.readUuid(), b.readString()) }
        )
    }
}

// ── S2C：持有者列表（混排：紫卡 ∪ 黑卡，每行标注持有卡种） ──

data class CardHolderEntry(
    val uuid: UUID,
    val name: String,
    val holdsPurple: Boolean,
    val holdsBlack: Boolean
) {
    fun write(buf: PacketByteBuf) {
        buf.writeUuid(uuid)
        buf.writeString(name)
        buf.writeBoolean(holdsPurple)
        buf.writeBoolean(holdsBlack)
    }

    companion object {
        fun read(buf: PacketByteBuf) =
            CardHolderEntry(buf.readUuid(), buf.readString(), buf.readBoolean(), buf.readBoolean())
    }
}

data class CardHolderListPayload(val entries: List<CardHolderEntry>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CardHolderListPayload>(CobbleMarket.id("card_holder_list"))
        val CODEC: PacketCodec<PacketByteBuf, CardHolderListPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.entries.size)
                p.entries.forEach { it.write(b) }
            },
            { b -> CardHolderListPayload((0 until b.readVarInt()).map { CardHolderEntry.read(b) }) }
        )
    }
}

// ── 禁止借款名单（金融管理界面；FINANCE 节点） ──
//
// 用户 2026-10-05：禁借从「封禁弹窗里的一枚开关」搬到金融侧、成为金融管理界面自己的名单。
// ⚠ 与封禁系统**无关**（不再写 `BanState`），与「逾期/坏账」那两条判据也**并列互不替代**。

/**
 * 一条「禁止借款」（下发给客户端）。
 * ⚠ 与 `BanEntry` 同款：**发剩余毫秒**、`expiresAt == null` 表示永久 —— 由客户端按自己的语言渲染
 * （服务端不拼死任何一种语言的时间文本）。
 */
data class LoanBlockInfo(
    val playerUuid: UUID,
    val playerName: String,
    /** 执行者：管理员名，或系统来源的 `$词条键`（渲染侧过 `BanState.reasonText`） */
    val blockedBy: String,
    /** null = 永久 */
    val expiresAt: Long?,
    /** 剩余毫秒（永久时无意义，界面按 expiresAt 判） */
    val remainingMs: Long,
    val reason: String
) {
    fun write(buf: PacketByteBuf) {
        buf.writeUuid(playerUuid)
        buf.writeString(playerName)
        buf.writeString(blockedBy)
        buf.writeBoolean(expiresAt != null)
        expiresAt?.let { buf.writeLong(it) }
        buf.writeLong(remainingMs)
        buf.writeString(reason)
    }

    companion object {
        fun read(buf: PacketByteBuf) = LoanBlockInfo(
            playerUuid = buf.readUuid(),
            playerName = buf.readString(),
            blockedBy = buf.readString(),
            expiresAt = if (buf.readBoolean()) buf.readLong() else null,
            remainingMs = buf.readLong(),
            reason = buf.readString()
        )
    }
}

// ── C2S：请求禁借名单 ──

class RequestLoanBlockListPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestLoanBlockListPayload>(CobbleMarket.id("request_loan_block_list"))
        val CODEC: PacketCodec<PacketByteBuf, RequestLoanBlockListPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestLoanBlockListPayload() }
        )
    }
}

// ── S2C：禁借名单 ──

data class LoanBlockListPayload(val entries: List<LoanBlockInfo>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<LoanBlockListPayload>(CobbleMarket.id("loan_block_list"))
        val CODEC: PacketCodec<PacketByteBuf, LoanBlockListPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.entries.size)
                p.entries.forEach { it.write(b) }
            },
            { b -> LoanBlockListPayload((0 until b.readVarInt()).map { LoanBlockInfo.read(b) }) }
        )
    }
}

// ── C2S：加一笔禁借（玩家名 / 时长 / 理由 —— 照 `AdminBanPayload`） ──

data class AdminLoanBlockPayload(
    val playerName: String,
    val duration: String,
    val reason: String
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AdminLoanBlockPayload>(CobbleMarket.id("admin_loan_block"))
        val CODEC: PacketCodec<PacketByteBuf, AdminLoanBlockPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.playerName); b.writeString(p.duration); b.writeString(p.reason) },
            { b -> AdminLoanBlockPayload(b.readString(), b.readString(), b.readString()) }
        )
    }
}

// ── C2S：解除禁借（照 `AdminUnbanPayload`） ──

data class AdminLoanUnblockPayload(val playerUuid: UUID) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AdminLoanUnblockPayload>(CobbleMarket.id("admin_loan_unblock"))
        val CODEC: PacketCodec<PacketByteBuf, AdminLoanUnblockPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.playerUuid) },
            { b -> AdminLoanUnblockPayload(b.readUuid()) }
        )
    }
}

// ── 额度管理（金融管理界面；FINANCE 节点） ──
//
// 用户 2026-10-05：额度「只增不减、没人能改」⇒ 服主遇到来路不正的额度只有封禁这一把钝刀。
// 这里的「调整」= 在**公式算出的 base 之上叠一个修正量**（不是改写公式结果 —— 那会被下次重算冲掉），
// 所以存的是 `FinanceState.creditAdjust` 里的 delta，客户端看到的是**修正后的可用额度**。

/**
 * 一行额度信息（下发用）：被人工调整过额度的玩家 / 刚查出来的那个玩家。
 * ⚠ [limit] 是**服务端实算的可用额度**（`creditLimitFor`：base + 人工修正 − 欠款，钳 0..max）。
 */
data class CreditRowInfo(
    val playerUuid: UUID,
    val playerName: String,
    /** 当前可用额度（服务端实算） */
    val limit: Long,
    /** 人工修正量（正 = 加、负 = 减；0 = 没调过） */
    val adjust: Long,
    /** 持卡玩家（紫/黑）：额度 = 配置固定值，**不允许人工调整**（界面据此置灰按钮） */
    val hasCard: Boolean
) {
    fun write(buf: PacketByteBuf) {
        buf.writeUuid(playerUuid); buf.writeString(playerName)
        buf.writeLong(limit); buf.writeLong(adjust); buf.writeBoolean(hasCard)
    }

    companion object {
        fun read(buf: PacketByteBuf) = CreditRowInfo(
            playerUuid = buf.readUuid(), playerName = buf.readString(),
            limit = buf.readLong(), adjust = buf.readLong(), hasCard = buf.readBoolean()
        )
    }
}

// ── C2S：请求「被人工调整过额度」的玩家列表 ──

class RequestCreditAdjustListPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestCreditAdjustListPayload>(CobbleMarket.id("request_credit_adjust_list"))
        val CODEC: PacketCodec<PacketByteBuf, RequestCreditAdjustListPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestCreditAdjustListPayload() }
        )
    }
}

// ── S2C：额度列表 ──

data class CreditAdjustListPayload(val rows: List<CreditRowInfo>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CreditAdjustListPayload>(CobbleMarket.id("credit_adjust_list"))
        val CODEC: PacketCodec<PacketByteBuf, CreditAdjustListPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.rows.size); p.rows.forEach { it.write(b) } },
            { b -> CreditAdjustListPayload((0 until b.readVarInt()).map { CreditRowInfo.read(b) }) }
        )
    }
}

// ── C2S：查一个玩家当前的额度（搜索框点选后查，服务端回 [CreditLookupPayload]） ──

data class RequestCreditLookupPayload(val playerName: String) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestCreditLookupPayload>(CobbleMarket.id("request_credit_lookup"))
        val CODEC: PacketCodec<PacketByteBuf, RequestCreditLookupPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.playerName) },
            { b -> RequestCreditLookupPayload(b.readString()) }
        )
    }
}

// ── S2C：某个玩家的额度（弹窗里显示"当前额度"用） ──

data class CreditLookupPayload(val row: CreditRowInfo) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CreditLookupPayload>(CobbleMarket.id("credit_lookup"))
        val CODEC: PacketCodec<PacketByteBuf, CreditLookupPayload> = PacketCodec.of(
            { p, b -> p.row.write(b) },
            { b -> CreditLookupPayload(CreditRowInfo.read(b)) }
        )
    }
}

// ── C2S：把某人的额度**设成**目标值（服务端反算 delta = 目标 − 公式 base） ──

data class AdminCreditSetPayload(
    val playerName: String,
    /** 目标可用额度（**含欠款扣减后的口径**：填 0 = 清空额度） */
    val targetLimit: Long,
    val reason: String
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AdminCreditSetPayload>(CobbleMarket.id("admin_credit_set"))
        val CODEC: PacketCodec<PacketByteBuf, AdminCreditSetPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.playerName); b.writeLong(p.targetLimit); b.writeString(p.reason) },
            { b -> AdminCreditSetPayload(b.readString(), b.readLong(), b.readString()) }
        )
    }
}

// ── 金融管理变更记录（跨来源账本：卡片 / 禁借 / 额度；照 `BanChangesPayload` 那对） ──

/** C2S：请求金融管理变更记录（读的是服务端 CSV，全量、不分页） */
class RequestFinanceChangesPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestFinanceChangesPayload>(CobbleMarket.id("request_finance_changes"))
        val CODEC: PacketCodec<PacketByteBuf, RequestFinanceChangesPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestFinanceChangesPayload() }
        )
    }
}

data class FinanceChangesPayload(val rows: List<Row>) : CustomPayload {

    /**
     * 一条变更记录。⚠ 与 `FinanceChangeRow`（CSV 那侧）**有意分开**（照 `BanChangesPayload.Row`）：
     * 账本加列不该顺手改协议。
     * ⚠ [operator] / [actionKey] / [reason] 可能是 `$` 开头的词条键 —— 显示前过 `BanState.reasonText`。
     */
    data class Row(
        val timestamp: Long,
        val operator: String,
        val targetName: String,
        val actionKey: String,
        /** 禁借的时长毫秒（永久 = null）；其余动作为 null */
        val durationMs: Long?,
        /** 调整额度：改后的可用额度；其余为 null */
        val value: Long?,
        /** 调整额度：改前的可用额度；其余为 null */
        val prevValue: Long?,
        /** `purple` / `black`；非卡片动作为空串 */
        val cardKind: String,
        val reason: String
    )

    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<FinanceChangesPayload>(CobbleMarket.id("finance_changes"))
        val CODEC: PacketCodec<PacketByteBuf, FinanceChangesPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.rows.size)
                p.rows.forEach { r ->
                    b.writeLong(r.timestamp)
                    b.writeString(r.operator)
                    b.writeString(r.targetName)
                    b.writeString(r.actionKey)
                    b.writeBoolean(r.durationMs != null)
                    r.durationMs?.let { b.writeLong(it) }
                    b.writeBoolean(r.value != null)
                    r.value?.let { b.writeLong(it) }
                    b.writeBoolean(r.prevValue != null)
                    r.prevValue?.let { b.writeLong(it) }
                    b.writeString(r.cardKind)
                    b.writeString(r.reason)
                }
            },
            { b ->
                val n = b.readVarInt()
                // ⚠ 读端一律**具名传参**：顺序写错会静默串位（编译不报，那列显示会整体错位）
                FinanceChangesPayload(List(n) {
                    Row(
                        timestamp = b.readLong(),
                        operator = b.readString(),
                        targetName = b.readString(),
                        actionKey = b.readString(),
                        durationMs = if (b.readBoolean()) b.readLong() else null,
                        value = if (b.readBoolean()) b.readLong() else null,
                        prevValue = if (b.readBoolean()) b.readLong() else null,
                        cardKind = b.readString(),
                        reason = b.readString()
                    )
                })
            }
        )
    }
}

// ── 卡片持有者展示面板（喵喵银行界面两张卡下方，所有玩家可见） ──

// ── C2S：请求持有者面板数据（进入喵喵银行时拉取） ──

class RequestCardHolderBoardPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestCardHolderBoardPayload>(CobbleMarket.id("request_card_holder_board"))
        val CODEC: PacketCodec<PacketByteBuf, RequestCardHolderBoardPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestCardHolderBoardPayload() }
        )
    }
}

// ── S2C：持有者面板数据（紫/黑各一份名单 + 各自全服上限） ──

data class CardHolderBoardEntry(val uuid: UUID, val name: String) {
    fun write(buf: PacketByteBuf) {
        buf.writeUuid(uuid)
        buf.writeString(name)
    }

    companion object {
        fun read(buf: PacketByteBuf) = CardHolderBoardEntry(buf.readUuid(), buf.readString())
    }
}

data class CardHolderBoardPayload(
    val purple: List<CardHolderBoardEntry>,
    val purpleMax: Long,
    val black: List<CardHolderBoardEntry>,
    val blackMax: Long
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CardHolderBoardPayload>(CobbleMarket.id("card_holder_board"))
        val CODEC: PacketCodec<PacketByteBuf, CardHolderBoardPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.purple.size)
                p.purple.forEach { it.write(b) }
                b.writeLong(p.purpleMax)
                b.writeVarInt(p.black.size)
                p.black.forEach { it.write(b) }
                b.writeLong(p.blackMax)
            },
            { b ->
                CardHolderBoardPayload(
                    purple = (0 until b.readVarInt()).map { CardHolderBoardEntry.read(b) },
                    purpleMax = b.readLong(),
                    black = (0 until b.readVarInt()).map { CardHolderBoardEntry.read(b) },
                    blackMax = b.readLong()
                )
            }
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
    /** 借款人最近 IP（金融管理节点的全服流水下发，小号排查用；普通玩家视图为空串） */
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

    /**
     * 「卡片自助申请」这个来源的标记（`$` 前缀 = 词条键，账本按**客户端语言**渲染）。
     *
     * ⚠⭐ 玩家**自己**在界面上申请到的卡，账本的「执行者」**不能填他自己的名字** ——
     *   那样会记成「Q → Q」，读起来像"服主给 Q 发了张卡"（用户 2026-10-05 实测指出）。
     *   与封禁那边的「喵喵行长」（`$cobblemarket.ban.by_finance`）同款：**这不是任何人的操作**。
     */
    private const val CARD_SELF_APPLY = "\$cobblemarket.finance.by_self"

    fun register() {
        registerS2CType(CreditInfoPayload.ID, CreditInfoPayload.CODEC)
        registerS2CType(LoanHistoryDataPayload.ID, LoanHistoryDataPayload.CODEC)
        registerS2CType(RepayListDataPayload.ID, RepayListDataPayload.CODEC)
        registerS2CType(FinanceStatsPayload.ID, FinanceStatsPayload.CODEC)
        registerS2CType(DepositInfoPayload.ID, DepositInfoPayload.CODEC)
        registerS2CType(PurpleCardApplyInfoPayload.ID, PurpleCardApplyInfoPayload.CODEC)
        registerS2CType(BlackCardApplyInfoPayload.ID, BlackCardApplyInfoPayload.CODEC)
        registerS2CType(CardHolderListPayload.ID, CardHolderListPayload.CODEC)
        registerS2CType(LoanBlockListPayload.ID, LoanBlockListPayload.CODEC)
        registerS2CType(CreditAdjustListPayload.ID, CreditAdjustListPayload.CODEC)
        registerS2CType(CreditLookupPayload.ID, CreditLookupPayload.CODEC)
        registerS2CType(FinanceChangesPayload.ID, FinanceChangesPayload.CODEC)
        registerS2CType(CardHolderBoardPayload.ID, CardHolderBoardPayload.CODEC)

        // ── 应急贷款借款 ──
        registerC2S(RequestLoanPayload.ID, RequestLoanPayload.CODEC) { payload, player ->
            // 与写操作一致的节流：防高频轰炸（照其他写请求的 REPEAT_WRITE 间隔）
            if (!RequestThrottle.allowNotify(player, "request_loan", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 市场总开关关（紧急停市）时不允许新增借贷：借款是唯一会让资金**流出**准备金池的
                // 金融操作，停市要防的就是这个口子。存款/取款/还款/卡片申请都不拦 ——
                // 那些要么是玩家自有资金、要么是钱流进池子，不增加服主风险（2026-09-16 与用户逐项确认）
                if (marketBlocked(player)) return@execute
                // 金融管理的禁借名单闸（原封禁弹窗那枚「同时禁止借款」开关的继任者）。
                // 与上面的关市闸并列、互不替代；存取款/还款不在此列 —— 还款必须留着，
                // 他还钱才能把逾期降下来（2026-10-04 定，2026-10-05 搬到金融侧）
                if (loanBlocked(player)) return@execute
                val state = FinanceState.get(server)
                val now = System.currentTimeMillis()
                // 文案区分：总开关关 = 喵喵银行没开门；现金贷关 = 喵喵的帮助暂未开放
                if (!CobbleMarketConfig.financeEnabled) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.loan.finance_disabled").formatted(Formatting.RED)))
                    return@execute
                }
                if (!CobbleMarketConfig.cashLoanEnabled) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.loan.cash_loan_disabled").formatted(Formatting.RED)))
                    return@execute
                }
                // 逾期/坏账禁止新增借贷（ACTIVE 不拦：允许多笔并存）；文案区分：逾期=暂时，坏账=永久
                val mine = state.getLoansByPlayer(player.uuid)
                if (mine.any { it.status == LoanStatus.BAD_DEBT }) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.loan.bad_debt_blocked").formatted(Formatting.RED)))
                    return@execute
                }
                if (mine.any { it.status == LoanStatus.OVERDUE }) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.loan.overdue_blocked").formatted(Formatting.RED)))
                    return@execute
                }
                val plan = CobbleMarketConfig.loanPlans.getOrNull(payload.planIndex)
                if (plan == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.loan.invalid_plan").formatted(Formatting.RED)))
                    return@execute
                }
                val amount = payload.amount
                // CurrencyHandler.remove 为 Int 签名（批次 5 还款划扣侧），借款金额钳制在 Int 范围内防后续截断
                if (amount <= 0 || amount > Int.MAX_VALUE) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.loan.invalid_amount").formatted(Formatting.RED)))
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
                // 同 IP 聚合欠款上限：防同 IP 多小号分散借款转给主账号（服主豁免；0=不限制）；
                // 紫/黑卡持有者豁免：卡额度远高于默认 IP 上限，且小号不可能持卡（全服限量+门槛），卡本身就是强信任凭证
                val ip = getPlayerIp(player)
                if (CobbleMarketConfig.ipDebtLimit > 0 && ip != null && !MarketPermissions.isServerOwner(player)
                    && !state.isPurpleCardHolder(player.uuid) && !state.isBlackCardHolder(player.uuid)
                ) {
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
                // 钱进钱包后立即推余额（借款不走 MarketResultPayload，客户端不会主动补拉）
                BalanceNetwork.sendBalanceTo(player)
                // 到账音：金融操作此前**完全没有声音反馈**（一行余额数字悄悄变了），补上
                sendSound(player, "money_in")
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
            if (!RequestThrottle.allowNotify(player, "request_purple_card_apply", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                val now = System.currentTimeMillis()
                if (!CobbleMarketConfig.purpleCardSelfApply) {
                    player.sendMessage(Text.translatable("cobblemarket.card.apply_closed").formatted(Formatting.RED), false)
                    return@execute
                }
                val cash = CurrencyHandler.getBalance(player).toLong()
                val dexCounts = com.shusheng.cobblemarket.finance.FinanceService.getDexCounts(server, player.uuid)
                if (!state.isPurpleCardEligible(player.uuid, cash, dexCounts.seen, dexCounts.caught, now)) {
                    player.sendMessage(Text.translatable("cobblemarket.card.apply_not_eligible").formatted(Formatting.RED), false)
                    return@execute
                }
                val max = CobbleMarketConfig.purpleCardCount
                if (max > 0 && state.getPurpleCardHolderCount() >= max) {
                    player.sendMessage(Text.translatable("cobblemarket.card.cap_reached", max).formatted(Formatting.RED), false)
                    return@execute
                }
                // 背包满则拒绝：紫卡落地会被扫描清除（付费白给），扣费前预检
                if (player.inventory.getEmptySlot() == -1) {
                    player.sendMessage(Text.translatable("cobblemarket.card.inventory_full").formatted(Formatting.RED), false)
                    return@execute
                }
                val fee = CobbleMarketConfig.purpleCardApplyFee
                if (fee > 0) {
                    if (!com.shusheng.cobblemarket.finance.FinanceService.removeInChunks(player, fee)) {
                        player.sendMessage(Text.translatable("cobblemarket.card.apply_fee_missing", CurrencyHandler.formatAmount(fee)).formatted(Formatting.RED), false)
                        return@execute
                    }
                    state.depositReserve(fee)
                }
                state.addPurpleCardHolder(player.uuid, CARD_SELF_APPLY, player.name.string)
                broadcastCardHolderBoard(server)
                val item = net.minecraft.registry.Registries.ITEM.get(CobbleMarket.id("meowth_purple_card"))
                val added = player.inventory.insertStack(net.minecraft.item.ItemStack(item))
                if (!added) player.dropItem(net.minecraft.item.ItemStack(item), false)
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                player.sendMessage(Text.translatable("cobblemarket.card.apply_success").formatted(Formatting.GREEN), false)
                com.shusheng.cobblemarket.network.CelebrationNetwork.sendCard(player, "purple")
                sendPurpleCardApplyInfo(player)
                sendCreditInfo(player, state, now)
            }
        }

        // ── 补发喵喵紫卡凭证（持有者从喵喵银行重新领取；补发费进准备金池） ──
        registerC2S(RequestPurpleCardRedoPayload.ID, RequestPurpleCardRedoPayload.CODEC) { _, player ->
            if (!RequestThrottle.allowNotify(player, "request_purple_card_redo", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                if (!state.isPurpleCardHolder(player.uuid)) {
                    player.sendMessage(Text.translatable("cobblemarket.card.not_holder_self").formatted(Formatting.RED), false)
                    return@execute
                }
                // 背包满则拒绝：紫卡落地会被扫描清除（付费白给），扣费前预检
                if (player.inventory.getEmptySlot() == -1) {
                    player.sendMessage(Text.translatable("cobblemarket.card.inventory_full").formatted(Formatting.RED), false)
                    return@execute
                }
                val fee = CobbleMarketConfig.purpleCardRedoFee
                if (fee > 0) {
                    if (!com.shusheng.cobblemarket.finance.FinanceService.removeInChunks(player, fee)) {
                        player.sendMessage(Text.translatable("cobblemarket.card.redo_fee_missing", CurrencyHandler.formatAmount(fee)).formatted(Formatting.RED), false)
                        return@execute
                    }
                    state.depositReserve(fee)
                    // 只有收费时模组状态（准备金池）有变化才落盘：免费补发只发物品（玩家数据由 MC 保存），
                    // 空跑 requestSave 会让 saveAll 无脏数据跳过写盘，mtime 验证误报「保存失败」
                    com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                }
                val item = net.minecraft.registry.Registries.ITEM.get(CobbleMarket.id("meowth_purple_card"))
                val added = player.inventory.insertStack(net.minecraft.item.ItemStack(item))
                if (!added) player.dropItem(net.minecraft.item.ItemStack(item), false)
                player.sendMessage(Text.translatable("cobblemarket.card.redo_success").formatted(Formatting.GREEN), false)
                com.shusheng.cobblemarket.network.CelebrationNetwork.sendCard(player, "purple")
            }
        }

        // ── 申请黑卡条件快照 ──
        registerC2S(RequestBlackCardApplyInfoPayload.ID, RequestBlackCardApplyInfoPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "request_black_card_apply_info", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute { sendBlackCardApplyInfo(player) }
        }

        // ── 申请黑卡（复核资格 + 扣费 + 发卡；硬条件=持有紫卡在 isBlackCardEligible 内） ──
        registerC2S(RequestBlackCardApplyPayload.ID, RequestBlackCardApplyPayload.CODEC) { _, player ->
            if (!RequestThrottle.allowNotify(player, "request_black_card_apply", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                val now = System.currentTimeMillis()
                if (!CobbleMarketConfig.blackCardSelfApply) {
                    player.sendMessage(Text.translatable("cobblemarket.card.apply_closed").formatted(Formatting.RED), false)
                    return@execute
                }
                val cash = CurrencyHandler.getBalance(player).toLong()
                val dexCounts = com.shusheng.cobblemarket.finance.FinanceService.getDexCounts(server, player.uuid)
                if (!state.isBlackCardEligible(player.uuid, cash, dexCounts.seen, dexCounts.caught, now)) {
                    player.sendMessage(Text.translatable("cobblemarket.card.apply_not_eligible").formatted(Formatting.RED), false)
                    return@execute
                }
                val max = CobbleMarketConfig.blackCardCount
                if (max > 0 && state.getBlackCardHolderCount() >= max) {
                    player.sendMessage(Text.translatable("cobblemarket.card.black_cap_reached", max).formatted(Formatting.RED), false)
                    return@execute
                }
                // 背包满则拒绝：黑卡落地会被扫描清除（付费白给），扣费前预检
                if (player.inventory.getEmptySlot() == -1) {
                    player.sendMessage(Text.translatable("cobblemarket.card.inventory_full").formatted(Formatting.RED), false)
                    return@execute
                }
                val fee = CobbleMarketConfig.blackCardApplyFee
                if (fee > 0) {
                    if (!com.shusheng.cobblemarket.finance.FinanceService.removeInChunks(player, fee)) {
                        player.sendMessage(Text.translatable("cobblemarket.card.apply_fee_missing", CurrencyHandler.formatAmount(fee)).formatted(Formatting.RED), false)
                        return@execute
                    }
                    state.depositReserve(fee)
                }
                state.addBlackCardHolder(player.uuid, CARD_SELF_APPLY, player.name.string)
                // 黑卡是紫卡的升级替代：获得黑卡自动移除紫卡资格，并主动清除背包紫卡（自检有 OP 豁免）
                state.removePurpleCardHolder(player.uuid, CARD_SELF_APPLY, player.name.string)
                com.shusheng.cobblemarket.finance.FinanceService.clearPurpleCardItems(player)
                broadcastCardHolderBoard(server)
                val item = net.minecraft.registry.Registries.ITEM.get(CobbleMarket.id("meowth_black_card"))
                val added = player.inventory.insertStack(net.minecraft.item.ItemStack(item))
                if (!added) player.dropItem(net.minecraft.item.ItemStack(item), false)
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                player.sendMessage(Text.translatable("cobblemarket.card.black_apply_success").formatted(Formatting.GREEN), false)
                player.sendMessage(Text.translatable("cobblemarket.card.purple_replaced").formatted(Formatting.GRAY), false)
                com.shusheng.cobblemarket.network.CelebrationNetwork.sendCard(player, "black")
                sendBlackCardApplyInfo(player)
                sendCreditInfo(player, state, now)
            }
        }

        // ── 补发喵喵黑卡凭证（持有者从喵喵银行重新领取；补发费进准备金池） ──
        registerC2S(RequestBlackCardRedoPayload.ID, RequestBlackCardRedoPayload.CODEC) { _, player ->
            if (!RequestThrottle.allowNotify(player, "request_black_card_redo", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                if (!state.isBlackCardHolder(player.uuid)) {
                    player.sendMessage(Text.translatable("cobblemarket.card.not_holder_self_black").formatted(Formatting.RED), false)
                    return@execute
                }
                // 背包满则拒绝：黑卡落地会被扫描清除（付费白给），扣费前预检
                if (player.inventory.getEmptySlot() == -1) {
                    player.sendMessage(Text.translatable("cobblemarket.card.inventory_full").formatted(Formatting.RED), false)
                    return@execute
                }
                val fee = CobbleMarketConfig.blackCardRedoFee
                if (fee > 0) {
                    if (!com.shusheng.cobblemarket.finance.FinanceService.removeInChunks(player, fee)) {
                        player.sendMessage(Text.translatable("cobblemarket.card.redo_fee_missing", CurrencyHandler.formatAmount(fee)).formatted(Formatting.RED), false)
                        return@execute
                    }
                    state.depositReserve(fee)
                    // 只有收费时模组状态（准备金池）有变化才落盘（同紫卡：免费补发不触发空跑保存误报）
                    com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                }
                val item = net.minecraft.registry.Registries.ITEM.get(CobbleMarket.id("meowth_black_card"))
                val added = player.inventory.insertStack(net.minecraft.item.ItemStack(item))
                if (!added) player.dropItem(net.minecraft.item.ItemStack(item), false)
                player.sendMessage(Text.translatable("cobblemarket.card.black_redo_success").formatted(Formatting.GREEN), false)
                com.shusheng.cobblemarket.network.CelebrationNetwork.sendCard(player, "black")
            }
        }

        // ── 卡片管理（金融管理节点）：请求持有者列表 ──
        registerC2S(RequestCardHolderListPayload.ID, RequestCardHolderListPayload.CODEC) { _, player ->
            if (!MarketPermissions.has(player, MarketPermissions.FINANCE)) return@registerC2S
            val server = player.server
            server.execute { sendCardHolderList(player) }
        }

        // ── 持有者面板数据（所有玩家；进入喵喵银行时拉取） ──
        registerC2S(RequestCardHolderBoardPayload.ID, RequestCardHolderBoardPayload.CODEC) { _, player ->
            // 只读、但每次都会重建持卡人名单并**全体广播**，照其它只读请求补节流（防高频请求砸服务器）
            if (!RequestThrottle.allow(player.uuid, "card_holder_board", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute { sendToPlayer(player, buildCardHolderBoard(server)) }
        }

        // ── 卡片管理（金融管理节点）：界面内收回（直接执行，无二次确认；收回后回发新列表） ──
        registerC2S(RequestCardRevokePayload.ID, RequestCardRevokePayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "request_card_revoke", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            if (!MarketPermissions.has(player, MarketPermissions.FINANCE)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                val cardName = if (payload.kind == "black") {
                    Text.translatable("cobblemarket.card.black_name")
                } else {
                    Text.translatable("cobblemarket.card.purple_name")
                }
                val targetName = holderDisplayName(server, payload.uuid)
                val removed = if (payload.kind == "black") state.removeBlackCardHolder(payload.uuid, player.name.string, targetName)
                else state.removePurpleCardHolder(payload.uuid, player.name.string, targetName)
                if (removed) {
                    com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                    broadcastCardHolderBoard(server)
                }
                player.sendMessage(
                    Text.translatable(
                        if (removed) "cobblemarket.card.revoked" else "cobblemarket.card.not_holder",
                        holderDisplayName(server, payload.uuid), cardName
                    ).formatted(if (removed) Formatting.GREEN else Formatting.RED),
                    false
                )
                // 结果反馈音（收回成功 / 该玩家本就不是持有者，各一声；管理操作不回结果包）
                sendResultSound(player, removed)
                sendCardHolderList(player)
            }
        }

        // ── 金融管理：禁止借款名单（FINANCE 节点；用户 2026-10-05 从封禁弹窗搬过来） ──
        //
        // ⚠ 判据一律 `MarketPermissions.FINANCE`（**与 OP 无关**，项目铁律）；
        //   与卡片管理那两条并列 —— 金融管理界面能做的三件事（卡片 / 禁借 / 额度）同属一个节点。
        registerC2S(RequestLoanBlockListPayload.ID, RequestLoanBlockListPayload.CODEC) { _, player ->
            if (!MarketPermissions.has(player, MarketPermissions.FINANCE)) return@registerC2S
            val server = player.server
            server.execute { sendLoanBlockList(player) }
        }

        registerC2S(AdminLoanBlockPayload.ID, AdminLoanBlockPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "admin_loan_block", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            if (!MarketPermissions.has(player, MarketPermissions.FINANCE)) return@registerC2S
            val server = player.server
            server.execute {
                val target = BanState.resolvePlayer(server, payload.playerName)
                if (target == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.ban.player_not_found", payload.playerName)))
                    return@execute
                }
                // 时长解析与封禁那边**同一个函数**（空 = 永久；`1d12h` 那套输入格式两边一致）
                val ms = if (payload.duration.isBlank()) {
                    null
                } else {
                    BanState.parseDurationMs(payload.duration) ?: run {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.ban.invalid_duration")))
                        return@execute
                    }
                }
                val now = System.currentTimeMillis()
                val expiresAt = ms?.let { now + it }
                FinanceState.get(server)
                    .blockLoan(target.first, target.second, player.name.string, expiresAt, payload.reason.take(100))
                FinanceChangeLogger.log(
                    operator = player.name.string,
                    targetName = target.second,
                    targetUuid = target.first,
                    actionKey = FinanceChangeLogger.ACTION_LOAN_BLOCK,
                    durationMs = ms,
                    reason = payload.reason.take(100)
                )
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(
                        true,
                        if (expiresAt == null) Text.translatable("cobblemarket.finance.loan_blocked", target.second)
                        else Text.translatable("cobblemarket.finance.loan_blocked_until", target.second, payload.duration)
                    )
                )
                sendLoanBlockList(player)
                // 通知被禁的玩家（在线才发）。⚠ 文案与「逾期/坏账拦借」那两条刻意分开 ——
                //   那两条是贷款状态判的，这条是管理员的决定，混成一句话玩家会去问错人
                server.playerManager.getPlayer(target.first)?.sendMessage(
                    Text.translatable("cobblemarket.finance.loan_blocked_notice").formatted(Formatting.RED),
                    false
                )
            }
        }

        registerC2S(AdminLoanUnblockPayload.ID, AdminLoanUnblockPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "admin_loan_unblock", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            if (!MarketPermissions.has(player, MarketPermissions.FINANCE)) return@registerC2S
            val server = player.server
            server.execute {
                val name = holderDisplayName(server, payload.playerUuid)
                val removed = FinanceState.get(server).unblockLoan(payload.playerUuid)
                if (removed != null) {
                    // 解禁那行**自带原时长与理由**（照封禁账本：不用回头翻当初那条）
                    FinanceChangeLogger.log(
                        operator = player.name.string,
                        targetName = removed.playerName,
                        targetUuid = removed.playerUuid,
                        actionKey = FinanceChangeLogger.ACTION_LOAN_UNBLOCK,
                        durationMs = removed.expiresAt?.minus(removed.blockedAt),
                        reason = removed.reason
                    )
                    com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                }
                sendToPlayer(
                    player,
                    MarketResultPayload(
                        removed != null,
                        Text.translatable(
                            if (removed != null) "cobblemarket.finance.loan_unblocked" else "cobblemarket.finance.not_blocked",
                            name
                        )
                    )
                )
                sendResultSound(player, removed != null)
                sendLoanBlockList(player)
            }
        }

        // ── 金融管理：额度调整（FINANCE 节点） ──
        registerC2S(RequestCreditAdjustListPayload.ID, RequestCreditAdjustListPayload.CODEC) { _, player ->
            if (!MarketPermissions.has(player, MarketPermissions.FINANCE)) return@registerC2S
            val server = player.server
            server.execute { sendCreditAdjustList(player) }
        }

        registerC2S(RequestCreditLookupPayload.ID, RequestCreditLookupPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.FINANCE)) return@registerC2S
            val server = player.server
            server.execute {
                val target = BanState.resolvePlayer(server, payload.playerName)
                if (target == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.ban.player_not_found", payload.playerName)))
                    return@execute
                }
                sendToPlayer(player, CreditLookupPayload(creditRowOf(server, target.first, target.second)))
            }
        }

        registerC2S(AdminCreditSetPayload.ID, AdminCreditSetPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "admin_credit_set", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            if (!MarketPermissions.has(player, MarketPermissions.FINANCE)) return@registerC2S
            val server = player.server
            server.execute {
                val target = BanState.resolvePlayer(server, payload.playerName)
                if (target == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.ban.player_not_found", payload.playerName)))
                    return@execute
                }
                val state = FinanceState.get(server)
                val now = System.currentTimeMillis()
                // 持卡玩家的额度 = 配置固定值、不走公式 ⇒ 人工调整没有意义（界面已置灰，这里兜底拦住）
                if (state.isBlackCardHolder(target.first) || state.isPurpleCardHolder(target.first)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.finance.credit_card_fixed", target.second)))
                    return@execute
                }
                if (payload.targetLimit < 0) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.finance.credit_invalid")))
                    return@execute
                }
                val before = state.creditLimitFor(target.first, now)
                // ⚠ 反算修正量在 FinanceState 里做（与 creditLimitFor 同一个模型），这里别自己算
                state.setCreditLimitTarget(target.first, payload.targetLimit, now)
                val after = state.creditLimitFor(target.first, now)
                FinanceChangeLogger.log(
                    operator = player.name.string,
                    targetName = target.second,
                    targetUuid = target.first,
                    actionKey = FinanceChangeLogger.ACTION_CREDIT_SET,
                    // 账本记的是**可用额度**（改前 → 改后）—— 用户要的是「把谁的额度从多少改成多少」
                    value = after,
                    prevValue = before,
                    reason = payload.reason.take(100)
                )
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(
                        true,
                        // ⚠ 聊天里的金额 = `goldAmount` + `goldCurrencyText` 两个参数分开传（单位不能拼进串）
                        Text.translatable(
                            "cobblemarket.finance.credit_set", target.second,
                            CurrencyHandler.goldAmount(before), CurrencyHandler.goldCurrencyText(),
                            CurrencyHandler.goldAmount(after), CurrencyHandler.goldCurrencyText()
                        )
                    )
                )
                sendCreditAdjustList(player)
                // 通知**被调的玩家**（在线才发，照封禁 / 禁借那两套）——
                // ⚠ 没有这条的话他只会发现"我怎么借不了钱了"，然后来问服主，而理由只有账本里才有。
                // ⚠ 聊天里的金额口径 = `goldAmount` + `goldCurrencyText` **两个参数分开传**
                //   （单位不能拼进字符串：物品本位货币时它是个物品名、RP 时是字形）
                val noticeReason = payload.reason.take(100)
                val unit = CurrencyHandler.goldCurrencyText()
                server.playerManager.getPlayer(target.first)?.sendMessage(
                    (if (noticeReason.isNotBlank()) {
                        Text.translatable(
                            "cobblemarket.finance.credit_changed_reason",
                            CurrencyHandler.goldAmount(before), unit, CurrencyHandler.goldAmount(after), unit, noticeReason
                        )
                    } else {
                        Text.translatable(
                            "cobblemarket.finance.credit_changed",
                            CurrencyHandler.goldAmount(before), unit, CurrencyHandler.goldAmount(after), unit
                        )
                    }).formatted(Formatting.GOLD),
                    false
                )
            }
        }

        // ── 金融管理：变更记录（读 CSV） ──
        registerC2S(RequestFinanceChangesPayload.ID, RequestFinanceChangesPayload.CODEC) { _, player ->
            if (!MarketPermissions.has(player, MarketPermissions.FINANCE)) return@registerC2S
            // ⚠ **有意不套 `server.execute`**（照封禁账本那对）：读 CSV 是磁盘 IO，
            //   丢回主线程反而会卡 tick；fabric 的 sendToPlayer 在这里是安全的
            val rows = FinanceChangeLogger.readAll().map {
                FinanceChangesPayload.Row(
                    timestamp = it.timestamp,
                    operator = it.operator,
                    targetName = it.targetName,
                    actionKey = it.actionKey,
                    durationMs = it.durationMs,
                    value = it.value,
                    prevValue = it.prevValue,
                    cardKind = it.cardKind,
                    reason = it.reason
                )
            }
            sendToPlayer(player, FinanceChangesPayload(rows))
        }

        // ── 活期存款（总开关只拦存款，取款永远开放——钱是玩家的不能卡） ──
        registerC2S(RequestDepositPayload.ID, RequestDepositPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "request_deposit", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
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
                // 存款是「钱离开钱包」→ 用扣款音（与自动划扣、还款同一枚；用户拍板按钱的进出分音效）
                sendSound(player, "loan_deduct")
                sendDepositInfo(player, state, now)
            }
        }

        registerC2S(RequestWithdrawPayload.ID, RequestWithdrawPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "request_withdraw", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
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
                // 钱出银行进钱包后立即推余额（取款不走 MarketResultPayload，客户端不会主动补拉）
                BalanceNetwork.sendBalanceTo(player)
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
                sendSound(player, "money_in")
                sendDepositInfo(player, state, now)
            }
        }

        registerC2S(RequestDepositInfoPayload.ID, RequestDepositInfoPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "request_deposit_info", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute { sendDepositInfo(player, FinanceState.get(server), System.currentTimeMillis()) }
        }

        // ── 金融统计（全服累计成交额所有人可见；准备金池/坏账总额限金融管理节点） ──
        registerC2S(RequestFinanceStatsPayload.ID, RequestFinanceStatsPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "request_finance_stats", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                val isOp = MarketPermissions.has(player, MarketPermissions.FINANCE)
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

        // ── 撤销玩家全部坏账（仅服主；与 /market loan clear 命令双入口） ──
        registerC2S(RequestRevokeBadDebtPayload.ID, RequestRevokeBadDebtPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "request_revoke_bad_debt", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                if (!MarketPermissions.isServerOwner(player)) return@execute   // 撤销坏账只限服主：干预手段，不随管理节点授予
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
                    sendResultSound(player)   // 撤销成功反馈（管理操作不回结果包，只给音效）
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
                    sendResultSound(player, false)   // 没有可撤销的坏账：被拒也要有听觉反馈
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
            if (!RequestThrottle.allowNotify(player, "request_repay", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute { com.shusheng.cobblemarket.finance.FinanceService.executeRepay(player, payload.loanId, payload.settle) }
        }

        // ── 借款历史 ──
        registerC2S(RequestLoanHistoryPayload.ID, RequestLoanHistoryPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_loan_history", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                // all=true 需金融管理节点（全服借贷流水审计），普通玩家回退为本人借款历史
                val loans = if (payload.all && MarketPermissions.has(player, MarketPermissions.FINANCE)) state.getAllLoans()
                else state.getLoansByPlayer(player.uuid)
                val entries = buildLoanHistoryEntries(state, loans, all = payload.all && MarketPermissions.has(player, MarketPermissions.FINANCE))
                sendToPlayer(player, LoanHistoryDataPayload(entries))
            }
        }
    }

    /** 申请紫卡条件快照（打开界面/申请后回发）：7 项条件 + 费用 + 资格 + 开关 */
    /**
     * 禁止借款的闸：**金融管理名单**（用户 2026-10-05 定）。
     *
     * ⚠ 来历：原先是封禁弹窗里那枚「同时禁止借款」开关（挂在 `BanInfo.blockLoan`），
     *   现已搬成金融侧自己的名单 —— **与封禁系统无关**了（金融的事归金融侧）。
     * ⚠ 与「逾期 / 坏账」那两条拦借判据**并列、互不替代**：那两条按**贷款状态**判、在下面的借款
     *   handler 里；这条按**管理员的决定**判。三者的提示文案刻意分开，别合并。
     * ⚠ 只拦借款 —— 存款/取款/还款一律放行（还款必须留着：他还钱才能把逾期降下来）。
     */
    private fun loanBlocked(player: ServerPlayerEntity): Boolean {
        val now = System.currentTimeMillis()
        val entry = FinanceState.get(player.server).getLoanBlock(player.uuid, now) ?: return false
        // ⚠ 走结果包（不是 `player.sendMessage`）：失败音效由 `MarketResultPayload(success=false)` 在客户端
        //   触发，直接发聊天行就只有红字、没有声音（用户 2026-10-04 实测报的）
        // ⚠ 首句特意**不是**"禁止参与交易"：这条只多拦了借款，市场交易照常，
        //   说成交易被封会把人误导去问服主"我不是还能买东西吗"
        sendToPlayer(
            player,
            MarketResultPayload(
                false,
                BanState.noticeText(entry.expiresAt, entry.reason, now, "cobblemarket.finance.loan_blocked_msg")
                    .formatted(Formatting.RED)
            )
        )
        return true
    }

    private fun sendPurpleCardApplyInfo(player: ServerPlayerEntity) {
        val server = player.server
        val state = FinanceState.get(server)
        val now = System.currentTimeMillis()
        val cash = CurrencyHandler.getBalance(player).toLong()
        val volume = state.getTotalCountedVolumeOf(player.uuid)
        // 净存款（存款 − 未还欠款）：借钱充存款无法满足门槛，界面显示值即判定口径
        val deposit = state.netDepositBalance(player.uuid, now)
        val dexCounts = com.shusheng.cobblemarket.finance.FinanceService.getDexCounts(server, player.uuid)
        // 信用基础 = 无欠款公式值（勿用 creditLimitFor+debt 反推：欠款超基础时会被钳成欠款额，见 creditBaseFor 注释）
        val creditBase = state.creditBaseFor(player.uuid, now)
        val mine = state.getLoansByPlayer(player.uuid)
        val hasBadRecord = mine.any { it.status == LoanStatus.OVERDUE || it.status == LoanStatus.BAD_DEBT }
        // 坏账是硬拦截（isPurpleCardEligible 内）：该行恒显示且不满足，让被拒的玩家看得到原因
        val hasBadDebt = mine.any { it.status == LoanStatus.BAD_DEBT }
        val conditions = listOf(
            ApplyConditionEntry(CobbleMarketConfig.purpleCardApplyAsset, cash, cash >= CobbleMarketConfig.purpleCardApplyAsset),
            ApplyConditionEntry(CobbleMarketConfig.purpleCardApplyVolume, volume, volume >= CobbleMarketConfig.purpleCardApplyVolume),
            ApplyConditionEntry(CobbleMarketConfig.purpleCardApplyCredit, creditBase, creditBase >= CobbleMarketConfig.purpleCardApplyCredit),
            ApplyConditionEntry(CobbleMarketConfig.purpleCardApplyDeposit, deposit, deposit >= CobbleMarketConfig.purpleCardApplyDeposit),
            ApplyConditionEntry(CobbleMarketConfig.purpleCardApplySeen, dexCounts.seen.toLong(), dexCounts.seen.toLong() >= CobbleMarketConfig.purpleCardApplySeen),
            ApplyConditionEntry(CobbleMarketConfig.purpleCardApplyDex, dexCounts.caught.toLong(), dexCounts.caught.toLong() >= CobbleMarketConfig.purpleCardApplyDex),
            ApplyConditionEntry(
                if (CobbleMarketConfig.purpleCardApplyNoOverdue || hasBadDebt) 1L else 0L,
                if (hasBadRecord) 0L else 1L,
                !hasBadDebt && (!CobbleMarketConfig.purpleCardApplyNoOverdue || !hasBadRecord)
            ),
        )
        sendToPlayer(
            player,
            PurpleCardApplyInfoPayload(
                conditions = conditions,
                fee = CobbleMarketConfig.purpleCardApplyFee,
                eligible = state.isPurpleCardEligible(player.uuid, cash, dexCounts.seen, dexCounts.caught, now),
                selfApplyEnabled = CobbleMarketConfig.purpleCardSelfApply,
                isHolder = state.isPurpleCardHolder(player.uuid),
                redoFee = CobbleMarketConfig.purpleCardRedoFee,
                holdsBlackCard = state.isBlackCardHolder(player.uuid),
                creditLimit = CobbleMarketConfig.purpleCardCreditLimit,
                feeDiscount = CobbleMarketConfig.purpleCardFeeDiscount
            )
        )
    }

    /** 申请黑卡条件快照回发（8 项：持有紫卡硬条件 + 七项门槛，照紫卡口径） */
    private fun sendBlackCardApplyInfo(player: ServerPlayerEntity) {
        val server = player.server
        val state = FinanceState.get(server)
        val now = System.currentTimeMillis()
        val cash = CurrencyHandler.getBalance(player).toLong()
        val volume = state.getTotalCountedVolumeOf(player.uuid)
        // 净存款（存款 − 未还欠款）：借钱充存款无法满足门槛，界面显示值即判定口径
        val deposit = state.netDepositBalance(player.uuid, now)
        val dexCounts = com.shusheng.cobblemarket.finance.FinanceService.getDexCounts(server, player.uuid)
        // 信用基础 = 无欠款公式值（紫卡持有者 = 紫卡额度；勿用 creditLimitFor+debt 反推，见 creditBaseFor 注释）
        val creditBase = state.creditBaseFor(player.uuid, now)
        val mine = state.getLoansByPlayer(player.uuid)
        val hasBadRecord = mine.any { it.status == LoanStatus.OVERDUE || it.status == LoanStatus.BAD_DEBT }
        // 坏账是硬拦截（isBlackCardEligible 内）：该行恒显示且不满足，让被拒的玩家看得到原因
        val hasBadDebt = mine.any { it.status == LoanStatus.BAD_DEBT }
        val holdsPurple = state.isPurpleCardHolder(player.uuid)
        val conditions = listOf(
            // 硬条件：必须持有紫卡（requirement 1/current 0|1）
            ApplyConditionEntry(1L, if (holdsPurple) 1L else 0L, holdsPurple),
            ApplyConditionEntry(CobbleMarketConfig.blackCardApplyAsset, cash, cash >= CobbleMarketConfig.blackCardApplyAsset),
            ApplyConditionEntry(CobbleMarketConfig.blackCardApplyVolume, volume, volume >= CobbleMarketConfig.blackCardApplyVolume),
            ApplyConditionEntry(CobbleMarketConfig.blackCardApplyCredit, creditBase, creditBase >= CobbleMarketConfig.blackCardApplyCredit),
            ApplyConditionEntry(CobbleMarketConfig.blackCardApplyDeposit, deposit, deposit >= CobbleMarketConfig.blackCardApplyDeposit),
            ApplyConditionEntry(CobbleMarketConfig.blackCardApplySeen, dexCounts.seen.toLong(), dexCounts.seen.toLong() >= CobbleMarketConfig.blackCardApplySeen),
            ApplyConditionEntry(CobbleMarketConfig.blackCardApplyDex, dexCounts.caught.toLong(), dexCounts.caught.toLong() >= CobbleMarketConfig.blackCardApplyDex),
            ApplyConditionEntry(
                if (CobbleMarketConfig.blackCardApplyNoOverdue || hasBadDebt) 1L else 0L,
                if (hasBadRecord) 0L else 1L,
                !hasBadDebt && (!CobbleMarketConfig.blackCardApplyNoOverdue || !hasBadRecord)
            ),
        )
        sendToPlayer(
            player,
            BlackCardApplyInfoPayload(
                conditions = conditions,
                fee = CobbleMarketConfig.blackCardApplyFee,
                eligible = state.isBlackCardEligible(player.uuid, cash, dexCounts.seen, dexCounts.caught, now),
                selfApplyEnabled = CobbleMarketConfig.blackCardSelfApply,
                isHolder = state.isBlackCardHolder(player.uuid),
                redoFee = CobbleMarketConfig.blackCardRedoFee,
                creditLimit = CobbleMarketConfig.blackCardCreditLimit,
                feeDiscount = CobbleMarketConfig.blackCardFeeDiscount
            )
        )
    }

    /** 持有者显示名（在线 → userCache 缓存名 → UUID 串，与 /market card list 同款来源） */
    private fun holderDisplayName(server: MinecraftServer, uuid: UUID): String =
        server.playerManager.getPlayer(uuid)?.name?.string
            ?: server.userCache?.getByUuid(uuid)?.orElse(null)?.name ?: uuid.toString()

    /** 持有者面板数据快照（紫/黑各一份；按加入时间正序=先申请在前，排行感，同刻按名字） */
    fun buildCardHolderBoard(server: MinecraftServer): CardHolderBoardPayload {
        val state = FinanceState.get(server)
        fun addedAtOf(uuid: UUID): Long =
            if (state.isBlackCardHolder(uuid)) state.getBlackCardAddedAt(uuid) else state.getPurpleCardAddedAt(uuid)
        fun entries(uuids: Set<UUID>): List<CardHolderBoardEntry> =
            uuids.sortedWith(compareBy({ addedAtOf(it) }, { holderDisplayName(server, it).lowercase() }))
                .map { CardHolderBoardEntry(it, holderDisplayName(server, it)) }
        return CardHolderBoardPayload(
            purple = entries(state.getAllPurpleCardHolders()),
            purpleMax = CobbleMarketConfig.purpleCardCount,
            black = entries(state.getAllBlackCardHolders()),
            blackMax = CobbleMarketConfig.blackCardCount
        )
    }

    /** 持有者变化（发放/收回/申请成功）后向全部在线玩家广播面板快照（面板对所有玩家可见） */
    fun broadcastCardHolderBoard(server: MinecraftServer) {
        val payload = buildCardHolderBoard(server)
        server.playerManager.playerList.forEach { sendToPlayer(it, payload) }
    }

    /** 卡片管理（金融管理节点）：持有者列表回发（紫卡 ∪ 黑卡 混排，按加入时间正序=先申请在前，同刻按名字） */
    /** 一个人的额度行（列表与查询共用；limit/adjust 都是服务端实算） */
    private fun creditRowOf(server: MinecraftServer, uuid: UUID, name: String): CreditRowInfo {
        val state = FinanceState.get(server)
        val now = System.currentTimeMillis()
        return CreditRowInfo(
            playerUuid = uuid,
            playerName = name,
            limit = state.creditLimitFor(uuid, now),
            adjust = state.getCreditAdjust(uuid),
            hasCard = state.isBlackCardHolder(uuid) || state.isPurpleCardHolder(uuid)
        )
    }

    /**
     * 额度列表回发：**只列被人工调整过额度的玩家**（用户 2026-10-05 定）。
     * ⚠ 不列全服 —— 额度是算出来的，全量列表 = 一次请求里给几千人各算一遍
     *   （要读每人的交易历史/存款/图鉴），而且几千行里也找不到人。
     *   要调别人就用搜索框查（`RequestCreditLookupPayload`）。
     */
    private fun sendCreditAdjustList(player: ServerPlayerEntity) {
        val server = player.server
        sendToPlayer(
            player,
            CreditAdjustListPayload(
                FinanceState.get(server).getAdjustedPlayers().keys.map { creditRowOf(server, it, holderDisplayName(server, it)) }
            )
        )
    }

    /** 禁借名单回发（金融管理界面；新的在前，与封禁列表同口径。增删后也调它刷新） */
    private fun sendLoanBlockList(player: ServerPlayerEntity) {
        val now = System.currentTimeMillis()
        sendToPlayer(
            player,
            LoanBlockListPayload(
                FinanceState.get(player.server).getActiveLoanBlocks(now).map { e ->
                    LoanBlockInfo(
                        playerUuid = e.playerUuid,
                        playerName = e.playerName,
                        blockedBy = e.blockedBy,
                        expiresAt = e.expiresAt,
                        // 永久（expiresAt == null）时这个值没意义 —— 界面按 expiresAt 判「永久」，
                        // 与封禁列表同一口径（那边也是发剩余毫秒 + 用 null 表示永久）
                        remainingMs = (e.expiresAt ?: now) - now,
                        reason = e.reason
                    )
                }
            )
        )
    }

    private fun sendCardHolderList(player: ServerPlayerEntity) {
        val server = player.server
        val state = FinanceState.get(server)
        fun addedAtOf(uuid: UUID): Long =
            if (state.isBlackCardHolder(uuid)) state.getBlackCardAddedAt(uuid) else state.getPurpleCardAddedAt(uuid)
        val uuids = (state.getAllPurpleCardHolders() + state.getAllBlackCardHolders())
            .sortedWith(compareBy({ addedAtOf(it) }, { holderDisplayName(server, it).lowercase() }))
        sendToPlayer(
            player,
            CardHolderListPayload(
                uuids.map { uuid ->
                    CardHolderEntry(
                        uuid = uuid,
                        name = holderDisplayName(server, uuid),
                        holdsPurple = state.isPurpleCardHolder(uuid),
                        holdsBlack = state.isBlackCardHolder(uuid)
                    )
                }
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
        val hasBadDebt = mine.any { it.status == LoanStatus.BAD_DEBT }
        sendToPlayer(
            player,
            CreditInfoPayload(
                // 坏账玩家额度显示 0：坏账已从额度公式的欠款项剔除，直显公式值会「看着有额度却一分借不出来」
                // （借款/喵喵支付均被 bad_debt_blocked 前置拦截，此处只改显示口径）
                limit = if (hasBadDebt) 0L else state.creditLimitFor(player.uuid, now),
                debt = debt,
                hasOverdue = mine.any { it.status == LoanStatus.OVERDUE },
                hasBadDebt = hasBadDebt,
                plans = CobbleMarketConfig.loanPlansText(),
                financeEnabled = CobbleMarketConfig.financeEnabled,
                consumerLoanEnabled = CobbleMarketConfig.financeEnabled && CobbleMarketConfig.consumerLoanEnabled,
                hasPurpleCard = state.isPurpleCardHolder(player.uuid)
            )
        )
    }
}
