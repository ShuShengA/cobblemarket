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
    /** 已持有黑金卡（升级替代）：紫卡申请按钮显示「已升级」而非「条件未满足」 */
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

// ── 卡片管理（仅 OP）：持有者列表 + 界面内收回 ──

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
        registerS2CType(BlackCardApplyInfoPayload.ID, BlackCardApplyInfoPayload.CODEC)
        registerS2CType(CardHolderListPayload.ID, CardHolderListPayload.CODEC)
        registerS2CType(CardHolderBoardPayload.ID, CardHolderBoardPayload.CODEC)

        // ── 应急贷款借款 ──
        registerC2S(RequestLoanPayload.ID, RequestLoanPayload.CODEC) { payload, player ->
            // 与写操作一致的节流：防高频轰炸（照其他写请求的 REPEAT_WRITE 间隔）
            if (!RequestThrottle.allow(player.uuid, "request_loan", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 市场总开关关（紧急停市）时不允许新增借贷：借款是唯一会让资金**流出**准备金池的
                // 金融操作，停市要防的就是这个口子。存款/取款/还款/卡片申请都不拦 ——
                // 那些要么是玩家自有资金、要么是钱流进池子，不增加服主风险（2026-09-16 与用户逐项确认）
                if (marketBlocked(player)) return@execute
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
                // 同 IP 聚合欠款上限：防同 IP 多小号分散借款转给主账号（OP 豁免；0=不限制）；
                // 紫/黑卡持有者豁免：卡额度远高于默认 IP 上限，且小号不可能持卡（全服限量+门槛），卡本身就是强信任凭证
                val ip = getPlayerIp(player)
                if (CobbleMarketConfig.ipDebtLimit > 0 && ip != null && !player.hasPermissionLevel(2)
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
                state.addPurpleCardHolder(player.uuid)
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
            if (!RequestThrottle.allow(player.uuid, "request_purple_card_redo", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
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
            if (!RequestThrottle.allow(player.uuid, "request_black_card_apply", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
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
                state.addBlackCardHolder(player.uuid)
                // 黑卡是紫卡的升级替代：获得黑卡自动移除紫卡资格，并主动清除背包紫卡（自检有 OP 豁免）
                state.removePurpleCardHolder(player.uuid)
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
            if (!RequestThrottle.allow(player.uuid, "request_black_card_redo", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
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

        // ── 卡片管理（仅 OP）：请求持有者列表 ──
        registerC2S(RequestCardHolderListPayload.ID, RequestCardHolderListPayload.CODEC) { _, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute { sendCardHolderList(player) }
        }

        // ── 持有者面板数据（所有玩家；进入喵喵银行时拉取） ──
        registerC2S(RequestCardHolderBoardPayload.ID, RequestCardHolderBoardPayload.CODEC) { _, player ->
            val server = player.server
            server.execute { sendToPlayer(player, buildCardHolderBoard(server)) }
        }

        // ── 卡片管理（仅 OP）：界面内收回（直接执行，无二次确认；收回后回发新列表） ──
        registerC2S(RequestCardRevokePayload.ID, RequestCardRevokePayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_card_revoke", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                val state = FinanceState.get(server)
                val cardName = if (payload.kind == "black") {
                    Text.translatable("cobblemarket.card.black_name")
                } else {
                    Text.translatable("cobblemarket.card.purple_name")
                }
                val removed = if (payload.kind == "black") state.removeBlackCardHolder(payload.uuid)
                else state.removePurpleCardHolder(payload.uuid)
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
                sendCardHolderList(player)
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
                // 存款是「钱离开钱包」→ 用扣款音（与还款同一枚，按钱的进出分音效）
                sendSound(player, "loan_deduct")
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
                // 取款是「钱回到钱包」→ 到账音
                sendSound(player, "money_in")
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
            }
        }
    }

    /** 申请紫卡条件快照（打开界面/申请后回发）：7 项条件 + 费用 + 资格 + 开关 */
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

    /** 卡片管理（仅 OP）：持有者列表回发（紫卡 ∪ 黑卡 混排，按加入时间正序=先申请在前，同刻按名字） */
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
