package com.shusheng.cobblemarket.finance

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

/** 贷款来源：柜台（现金贷/借呗）、购精灵、购物品、拍卖出价（后三者为消费贷/喵喵支付） */
enum class LoanSource { COUNTER, POKEMON_BUY, ITEM_BUY, AUCTION_BID }

/** 贷款状态机：ACTIVE 正常 → OVERDUE 逾期 → BAD_DEBT 坏账；CLOSED 已结清 */
enum class LoanStatus { ACTIVE, OVERDUE, BAD_DEBT, CLOSED }

/** 单笔贷款（现金贷/消费贷统一模型：等额本金 + 按天利息实算） */
data class LoanRecord(
    val id: Long,
    val playerUuid: UUID,
    val playerName: String,
    /** 本金总额（金额一律 Long，与市场挂单/冻结金一致） */
    val principal: Long,
    /** 期数（每期 7 天） */
    val periodsTotal: Int,
    /** 已还期数 */
    val periodsPaid: Int,
    /** 上次还款时刻：利息实算基准 */
    val lastRepayAt: Long,
    /** 日利率快照（创建时从配置取，改配置不影响存量贷款） */
    val dailyRate: Double,
    val source: LoanSource,
    val status: LoanStatus
) {
    /** 剩余本金（提前结清也用它；CLOSED 后恒 0，防报表/界面显示残留尾差） */
    val remainingPrincipal: Long get() =
        if (status == LoanStatus.CLOSED) 0
        else principal - periodPrincipal * periodsPaid

    /** 基准每期本金（Long 整除向下取整） */
    val periodPrincipal: Long get() = principal / periodsTotal

    /** 下期应还本金：最后一期兜底整除尾差（1000 分 3 期 → 333/333/334），玩家本金足额实还、池子不亏 */
    val nextPeriodPrincipal: Long get() =
        if (periodsPaid >= periodsTotal - 1) principal - periodPrincipal * (periodsTotal - 1)
        else periodPrincipal

    /** 距上次还款的利息（实算）：剩余本金 × 日利率 × 天数，四舍五入 */
    fun interestSince(now: Long): Long {
        if (status == LoanStatus.CLOSED) return 0
        val days = ((now - lastRepayAt).coerceAtLeast(0)).toDouble() / DAY_MS
        return Math.round(remainingPrincipal * dailyRate * days)
    }

    companion object {
        const val DAY_MS = 24.0 * 60 * 60 * 1000
    }
}

/** 防刷 IP 记录（30 天窗口，批次 2 额度计算启用） */
data class IpEntry(val ip: String, val at: Long)

/**
 * 金融系统持久化状态（world/data/cobblemarket_finance.dat，PersistHelper/StateBackup 自动兜底）：
 * 准备金池是所有借贷资金的唯一进出口（可为负 = 服主负债）；贷款表保存每笔借贷的状态机；
 * 全服累计成交额仅入口展示用；IP 记录供额度防刷。
 */
class FinanceState private constructor() : PersistentState() {

    private var reservePool: Long = 0L
    private val loans = mutableMapOf<Long, LoanRecord>()
    private var nextLoanId: Long = 1L
    private var totalTradingVolume: Long = 0L
    private val ipHistory = mutableMapOf<UUID, MutableList<IpEntry>>()

    // ── 准备金池 ──

    fun getReservePool(): Long = reservePool

    /** 准备金入账（还款本金/利息 100% 进池） */
    fun depositReserve(amount: Long) {
        if (amount <= 0) return
        reservePool += amount
        markDirty()
    }

    /** 准备金出账（借款/信用支付垫付从池子出）；允许为负 = 服主负债（OP 面板红色告警） */
    fun withdrawReserve(amount: Long): Boolean {
        if (amount <= 0) return false
        reservePool -= amount
        markDirty()
        return true
    }

    // ── 贷款状态机 ──

    fun getLoan(id: Long): LoanRecord? = loans[id]

    fun getLoansByPlayer(playerUuid: UUID): List<LoanRecord> =
        loans.values.filter { it.playerUuid == playerUuid }

    fun getAllLoans(): List<LoanRecord> = loans.values.toList()

    /** 创建贷款：分配自增 ID，状态 ACTIVE，初始 lastRepayAt = 当前时刻 */
    fun createLoan(
        playerUuid: UUID,
        playerName: String,
        principal: Long,
        periodsTotal: Int,
        dailyRate: Double,
        source: LoanSource,
        now: Long
    ): LoanRecord {
        val record = LoanRecord(
            id = nextLoanId++,
            playerUuid = playerUuid,
            playerName = playerName,
            principal = principal,
            periodsTotal = periodsTotal,
            periodsPaid = 0,
            lastRepayAt = now,
            dailyRate = dailyRate,
            source = source,
            status = LoanStatus.ACTIVE
        )
        loans[record.id] = record
        markDirty()
        return record
    }

    /** 还款一期：期数 +1，刷新计息基准；满期自动转 CLOSED */
    fun recordRepayment(id: Long, now: Long): Boolean {
        val record = loans[id] ?: return false
        if (record.status == LoanStatus.CLOSED || record.status == LoanStatus.BAD_DEBT) return false
        val paid = record.periodsPaid + 1
        val status = if (paid >= record.periodsTotal) LoanStatus.CLOSED else record.status
        loans[id] = record.copy(periodsPaid = paid, lastRepayAt = now, status = status)
        markDirty()
        return true
    }

    /** 逾期标记（到期划扣不足时进入） */
    fun markOverdue(id: Long): Boolean {
        val record = loans[id] ?: return false
        if (record.status != LoanStatus.ACTIVE) return false
        loans[id] = record.copy(status = LoanStatus.OVERDUE)
        markDirty()
        return true
    }

    /** 坏账（逾期 30 天冲销后标记；只标记不改池，冲销决策在调用方） */
    fun markBadDebt(id: Long): Boolean {
        val record = loans[id] ?: return false
        if (record.status == LoanStatus.CLOSED) return false
        loans[id] = record.copy(status = LoanStatus.BAD_DEBT)
        markDirty()
        return true
    }

    // ── 全服累计成交额（仅入口界面展示，不参与任何金融计算） ──

    fun getTotalTradingVolume(): Long = totalTradingVolume

    fun addTradingVolume(amount: Long) {
        if (amount <= 0) return
        totalTradingVolume += amount
        markDirty()
    }

    // ── IP 记录（防刷，30 天窗口） ──

    fun recordIp(playerUuid: UUID, ip: String, now: Long) {
        val list = ipHistory.getOrPut(playerUuid) { mutableListOf() }
        list.removeAll { now - it.at > IP_WINDOW_MS }
        list.add(IpEntry(ip, now))
        markDirty()
    }

    fun getIpEntries(playerUuid: UUID, now: Long): List<IpEntry> {
        val list = ipHistory[playerUuid] ?: return emptyList()
        return list.filter { now - it.at <= IP_WINDOW_MS }
    }

    // ── NBT ──

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        nbt.putLong("reservePool", reservePool)
        nbt.putLong("nextLoanId", nextLoanId)
        nbt.putLong("totalTradingVolume", totalTradingVolume)
        val loanList = NbtList()
        loans.values.forEach { record ->
            val c = NbtCompound()
            c.putLong("id", record.id)
            c.putUuid("uuid", record.playerUuid)
            c.putString("name", record.playerName)
            c.putLong("principal", record.principal)
            c.putInt("periodsTotal", record.periodsTotal)
            c.putInt("periodsPaid", record.periodsPaid)
            c.putLong("lastRepayAt", record.lastRepayAt)
            c.putDouble("dailyRate", record.dailyRate)
            c.putString("source", record.source.name)
            c.putString("status", record.status.name)
            loanList.add(c)
        }
        nbt.put("loans", loanList)
        val ipList = NbtList()
        ipHistory.forEach { (uuid, entries) ->
            val c = NbtCompound()
            c.putUuid("uuid", uuid)
            val sub = NbtList()
            entries.forEach { e ->
                val ec = NbtCompound()
                ec.putString("ip", e.ip)
                ec.putLong("at", e.at)
                sub.add(ec)
            }
            c.put("entries", sub)
            ipList.add(c)
        }
        nbt.put("ipHistory", ipList)
        return nbt
    }

    companion object {
        private const val IP_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

        private val TYPE = PersistentState.Type(
            { FinanceState() },
            { nbt, _ ->
                FinanceState().apply {
                    reservePool = nbt.getLong("reservePool")
                    nextLoanId = nbt.getLong("nextLoanId").coerceAtLeast(1)
                    totalTradingVolume = nbt.getLong("totalTradingVolume")
                    nbt.getList("loans", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            val record = LoanRecord(
                                id = c.getLong("id"),
                                playerUuid = c.getUuid("uuid"),
                                playerName = c.getString("name"),
                                principal = c.getLong("principal"),
                                periodsTotal = c.getInt("periodsTotal"),
                                periodsPaid = c.getInt("periodsPaid"),
                                lastRepayAt = c.getLong("lastRepayAt"),
                                dailyRate = c.getDouble("dailyRate"),
                                source = try { LoanSource.valueOf(c.getString("source")) } catch (_: Exception) { LoanSource.COUNTER },
                                status = try { LoanStatus.valueOf(c.getString("status")) } catch (_: Exception) { LoanStatus.ACTIVE }
                            )
                            loans[record.id] = record
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted finance loan entry: {}", e.message)
                        }
                    }
                    nbt.getList("ipHistory", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            val list = mutableListOf<IpEntry>()
                            c.getList("entries", NbtList.COMPOUND_TYPE.toInt()).forEach { sub ->
                                val ec = sub as NbtCompound
                                list.add(IpEntry(ec.getString("ip"), ec.getLong("at")))
                            }
                            ipHistory[c.getUuid("uuid")] = list
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted finance ip entry: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): FinanceState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_finance")
    }
}
