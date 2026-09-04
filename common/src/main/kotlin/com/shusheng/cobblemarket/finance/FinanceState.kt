package com.shusheng.cobblemarket.finance

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.platform.getPlayerIp
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
    /** 借款时刻（历史界面显示用；lastRepayAt 会被每次还款刷新，不能复用） */
    val createdAt: Long,
    /** 上次还款时刻：利息实算基准 */
    val lastRepayAt: Long,
    /** 日利率快照（创建时从配置取，改配置不影响存量贷款） */
    val dailyRate: Double,
    val source: LoanSource,
    val status: LoanStatus,
    /** 已发「到期前 1 天提醒」的期号（每期提醒一次，防扫描重复刷屏；批次 7） */
    val dueRemindedPeriods: Int = 0
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

    /**
     * 到期期数（固定基准：借后第 7/14/21 天…，与还款解耦，防滚动基准「还一期欠二期却算追平」的欠期蒸发）。
     * 利息基准仍是 lastRepayAt（每次还款刷新），两职责分离。
     */
    fun duePeriodsAt(now: Long): Int =
        minOf(periodsTotal, (((now - createdAt).coerceAtLeast(0)) / DAY_MS_LONG).toInt())

    /** 逾期天数：距最早欠期（第 periodsPaid+1 期）到期日已过的整天数；未欠期返回 0（批次 6 制裁档位用） */
    fun overdueDaysAt(now: Long): Long {
        if (duePeriodsAt(now) <= periodsPaid) return 0
        val firstDueAt = createdAt + (periodsPaid + 1) * DAY_MS_LONG
        return ((now - firstDueAt).coerceAtLeast(0)) / DAY_MS_LONG
    }

    companion object {
        const val DAY_MS = 24.0 * 60 * 60 * 1000
        const val DAY_MS_LONG = 24L * 60 * 60 * 1000
    }
}

/** 防刷 IP 记录（30 天窗口） */
data class IpEntry(val ip: String, val at: Long)

/** 活期存款账户（本金 + 结算基准；利息按天实算，查看/取款时结算入本金并刷新基准） */
data class DepositAccount(
    val principal: Long,
    val lastSettleAt: Long
)

/**
 * 成交记录（额度公式数据源，买入方视角）：countedAmount 为防刷三层在成交时快照判定后的实际计入额
 * （贷款/交易对/IP 状态随时间变，事后回算会失真；0 = 全额不计）。
 */
data class TradeRecord(
    /** 原始成交价（全服累计成交额用） */
    val amount: Long,
    val at: Long,
    val counterpartyUuid: UUID,
    /** 计入交易额的金额（防刷三层后；同 IP 降权为 amount×0.9） */
    val countedAmount: Long
)

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
    private val tradeRecords = mutableMapOf<UUID, MutableList<TradeRecord>>()
    /** 每玩家历史累计计入额（窗口清理后仍保留，供额度公式历史项） */
    private val totalCountedVolume = mutableMapOf<UUID, Long>()
    /** 活期存款：本金（利息不预存，查看/取款时实算结算入账）+ 上次结算时刻（存取刷新） */
    private val deposits = mutableMapOf<UUID, DepositAccount>()
    /** 喵喵紫卡持有者（额度凭证绑状态不绑物品；上限由配置 purpleCardCount 控制） */
    private val purpleCardHolders = mutableSetOf<UUID>()

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
            createdAt = now,
            lastRepayAt = now,
            dailyRate = dailyRate,
            source = source,
            status = LoanStatus.ACTIVE
        )
        loans[record.id] = record
        markDirty()
        return record
    }

    /** 还款一期：期数 +1，刷新计息基准；满期转 CLOSED，追平欠期回 ACTIVE（解除逾期），仍欠期保持 OVERDUE */
    fun recordRepayment(id: Long, now: Long): Boolean {
        val record = loans[id] ?: return false
        if (record.status == LoanStatus.CLOSED || record.status == LoanStatus.BAD_DEBT) return false
        val paid = record.periodsPaid + 1
        val status = when {
            paid >= record.periodsTotal -> LoanStatus.CLOSED
            paid >= record.duePeriodsAt(now) -> LoanStatus.ACTIVE
            else -> LoanStatus.OVERDUE
        }
        loans[id] = record.copy(periodsPaid = paid, lastRepayAt = now, status = status)
        markDirty()
        return true
    }

    /** 提前结清：剩余期数一次付清，直接 CLOSED（本金=剩余本金，利息=调用方实算后扣收） */
    fun settleLoan(id: Long, now: Long): Boolean {
        val record = loans[id] ?: return false
        if (record.status == LoanStatus.CLOSED || record.status == LoanStatus.BAD_DEBT) return false
        loans[id] = record.copy(periodsPaid = record.periodsTotal, lastRepayAt = now, status = LoanStatus.CLOSED)
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

    /** 标记第 n 期已发「到期前 1 天提醒」（每期一次，防扫描重复；批次 7） */
    fun markDueReminded(id: Long, period: Int): Boolean {
        val record = loans[id] ?: return false
        if (record.dueRemindedPeriods >= period) return false
        loans[id] = record.copy(dueRemindedPeriods = period)
        markDirty()
        return true
    }

    /**
     * 撤销玩家全部坏账（服主干预，批次 7）：删除 BAD_DEBT 贷款记录（审计 CSV 已有 BAD_DEBT 事件留痕），
     * 返回撤销的贷款列表（调用方逐笔写撤销审计）。删除后借款校验恢复放行；冻结由调用方 syncFreeze 重新评估。
     */
    fun clearBadDebts(playerUuid: UUID): List<LoanRecord> {
        val removed = loans.values.filter { it.playerUuid == playerUuid && it.status == LoanStatus.BAD_DEBT }.toList()
        removed.forEach { loans.remove(it.id) }
        if (removed.isNotEmpty()) markDirty()
        return removed
    }

    /** 坏账总额（未收回本金合计；OP 面板展示，批次 7） */
    fun getBadDebtTotal(): Long =
        loans.values.filter { it.status == LoanStatus.BAD_DEBT }.sumOf { it.remainingPrincipal.toLong() }

    /**
     * 清理已结清贷款（批次 7）：CLOSED 且结清（lastRepayAt）超过保留期的记录从状态删除，
     * 防存档无限膨胀（审计 CSV 是长期账本，历史照常可查）；逾期/坏账永久保留（制裁/禁借依据）。
     * 返回清理笔数。
     */
    fun purgeClosedLoans(now: Long, retainDays: Long): Int {
        val expired = loans.values.filter {
            it.status == LoanStatus.CLOSED && now - it.lastRepayAt > retainDays * LoanRecord.DAY_MS_LONG
        }
        expired.forEach { loans.remove(it.id) }
        if (expired.isNotEmpty()) markDirty()
        return expired.size
    }

    // ── 喵喵紫卡（额度凭证：持有者额度 = 配置固定值 − 欠款；物品只是凭证，状态才是额度依据） ──

    fun isPurpleCardHolder(uuid: UUID): Boolean = uuid in purpleCardHolders

    fun addPurpleCardHolder(uuid: UUID): Boolean {
        if (!purpleCardHolders.add(uuid)) return false
        markDirty()
        return true
    }

    fun removePurpleCardHolder(uuid: UUID): Boolean {
        if (!purpleCardHolders.remove(uuid)) return false
        markDirty()
        return true
    }

    fun getPurpleCardHolderCount(): Int = purpleCardHolders.size

    fun getAllPurpleCardHolders(): Set<UUID> = purpleCardHolders.toSet()

    /**
     * 自行申请紫卡资格校验（三项条件全部满足；0 = 不要求）：
     * 资产 = 当前现金余额（调用方传）、消费 = 历史买入成交额累计、额度 = 信用基础（无欠款公式值）。
     */
    fun isPurpleCardEligible(playerUuid: UUID, cashBalance: Long, dexCount: Int, now: Long): Boolean {
        if (purpleCardHolders.contains(playerUuid)) return false
        if (CobbleMarketConfig.purpleCardApplyAsset > 0 && cashBalance < CobbleMarketConfig.purpleCardApplyAsset) return false
        if (CobbleMarketConfig.purpleCardApplyVolume > 0 &&
            (totalCountedVolume[playerUuid] ?: 0L) < CobbleMarketConfig.purpleCardApplyVolume
        ) return false
        if (CobbleMarketConfig.purpleCardApplyCredit > 0) {
            val debt = loans.values
                .filter { it.playerUuid == playerUuid && it.status != LoanStatus.CLOSED && it.status != LoanStatus.BAD_DEBT }
                .sumOf { it.remainingPrincipal.toLong() }
            val creditBase = creditLimitFor(playerUuid, now) + debt
            if (creditBase < CobbleMarketConfig.purpleCardApplyCredit) return false
        }
        if (CobbleMarketConfig.purpleCardApplyDeposit > 0 &&
            getDepositBalance(playerUuid, now) < CobbleMarketConfig.purpleCardApplyDeposit
        ) return false
        if (CobbleMarketConfig.purpleCardApplyNoOverdue && loans.values.any {
                it.playerUuid == playerUuid && (it.status == LoanStatus.OVERDUE || it.status == LoanStatus.BAD_DEBT)
            }
        ) return false
        if (CobbleMarketConfig.purpleCardApplyDex > 0 && dexCount < CobbleMarketConfig.purpleCardApplyDex) return false
        return true
    }

    // ── 活期存款（批次 7.5：存钱进池吃利息，取款池出；利息从池出，池负照发=服主兜底） ──

    /** 距上次结算的利息（实算）：本金 × 日息 × 整天数；调用方结算后 resetSettle 刷新基准 */
    fun depositInterest(uuid: UUID, now: Long): Long {
        val account = deposits[uuid] ?: return 0
        val days = ((now - account.lastSettleAt).coerceAtLeast(0)) / LoanRecord.DAY_MS_LONG
        return Math.round(account.principal.toDouble() * CobbleMarketConfig.dailyDepositRate * days.toDouble())
    }

    /** 存款余额（本金 + 未结算利息；界面展示用，不改状态） */
    fun getDepositBalance(uuid: UUID, now: Long): Long =
        deposits[uuid]?.let { it.principal + depositInterest(uuid, now) } ?: 0L

    /** 存款：本金 +N 并先结算既有利息入本金；刷新结算基准 */
    fun depositMoney(uuid: UUID, amount: Long, now: Long): Long {
        val settled = deposits[uuid]?.principal ?: 0L
        val interest = depositInterest(uuid, now)
        deposits[uuid] = DepositAccount(settled + interest + amount, now)
        markDirty()
        return deposits[uuid]!!.principal
    }

    /** 取款结算：利息先入账，取出 take（≤ 总额）；返回 (本金部分, 利息部分)；剩余记为新本金并刷新基准 */
    fun withdrawMoney(uuid: UUID, amount: Long, now: Long): Pair<Long, Long> {
        val account = deposits[uuid] ?: return 0L to 0L
        val interest = depositInterest(uuid, now)
        val total = account.principal + interest
        val take = amount.coerceAtMost(total)
        val interestPart = minOf(take, interest)
        val principalPart = take - interestPart
        val remaining = total - take
        if (remaining > 0) {
            deposits[uuid] = DepositAccount(remaining, now)
        } else {
            deposits.remove(uuid)
        }
        markDirty()
        return principalPart to interestPart
    }

    /** 全服存款总额（OP 面板展示用） */
    fun getTotalDeposits(): Long = deposits.values.sumOf { it.principal }

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

    // ── 成交记录 + 额度计算 ──

    /**
     * 记录一笔成交（买入方视角，额度只算买入方——借贷对应「花钱的能力」）。
     * 防刷三层在成交时快照判定：
     * 1. 借贷来源（loanFunded，喵喵支付垫付）或买家当时有未结清贷款 → 全额不计（防借→买→额度涨→再借循环）
     * 2. 30 天内同一买卖对已达 MAX_SAME_PAIR_TRADES 笔 → 该笔不计
     * 3. 买卖双方同 IP（买家非 OP）→ 计入额 ×0.9
     * 全服累计成交额不受防刷影响（仅入口展示用）。顺带记录双方 IP（在线才记）。
     */
    fun recordTrade(
        server: MinecraftServer,
        buyerUuid: UUID,
        sellerUuid: UUID,
        amount: Long,
        now: Long,
        loanFunded: Boolean = false
    ) {
        if (amount <= 0) return
        addTradingVolume(amount)
        val buyer = server.playerManager.getPlayer(buyerUuid)
        val seller = server.playerManager.getPlayer(sellerUuid)
        if (buyer != null) {
            getPlayerIp(buyer)?.let { recordIp(buyerUuid, it, now) }
        }
        if (seller != null) {
            getPlayerIp(seller)?.let { recordIp(sellerUuid, it, now) }
        }
        val list = tradeRecords.getOrPut(buyerUuid) { mutableListOf() }
        // 交易对检测窗口/笔数可配（服主调防刷强度；与额度公式的「近30天交易额」窗口无关）
        val pairWindowMs = CobbleMarketConfig.tradePairWindowDays * LoanRecord.DAY_MS_LONG
        list.removeAll { now - it.at > pairWindowMs }
        val pairCount = list.count { it.counterpartyUuid == sellerUuid }
        val hasOpenLoan = loans.values.any { it.playerUuid == buyerUuid && it.status != LoanStatus.CLOSED }
        val buyerOp = buyer?.hasPermissionLevel(2) == true
        val buyerIp = latestIpOf(buyerUuid, now)
        val sameIp = !buyerOp && buyerIp != null && buyerIp == latestIpOf(sellerUuid, now)
        val counted = when {
            loanFunded || hasOpenLoan -> 0L
            pairCount >= CobbleMarketConfig.tradePairMaxTrades -> 0L
            sameIp -> amount * 9 / 10
            else -> amount
        }
        list.add(TradeRecord(amount, now, sellerUuid, counted))
        totalCountedVolume[buyerUuid] = (totalCountedVolume[buyerUuid] ?: 0L) + counted
        markDirty()
    }

    /** 30 天内最近一次 IP（无记录返回 null） */
    private fun latestIpOf(uuid: UUID, now: Long): String? =
        getIpEntries(uuid, now).maxByOrNull { it.at }?.ip

    /**
     * 同 IP 未结清欠款总和（防同 IP 多小号分散借款转给主账号）：
     * 30 天窗口内记录过该 IP 的所有玩家，其未结清贷款（CLOSED 除外）剩余本金之和。
     * 遍历 ipHistory 反查（借款为低频操作且有节流，性能无压力）。
     */
    fun debtByIp(ip: String, now: Long): Long {
        val debtors = ipHistory.entries
            .filter { (_, entries) -> entries.any { it.ip == ip && now - it.at <= IP_WINDOW_MS } }
            .map { it.key }
            .toSet()
        return loans.values
            .filter { it.playerUuid in debtors && it.status != LoanStatus.CLOSED && it.status != LoanStatus.BAD_DEBT }
            .sumOf { it.remainingPrincipal.toLong() }
    }

    /**
     * 可用额度（信用卡模型，2026-09-02 拍板）：
     * 信用基础 = max(近30天计入额×recent30Weight + 历史累计计入额×historyWeight, min)；
     * 可用额度 = max(0, 信用基础 − 未还欠款全额)，钳 max。
     * 欠款 = 未结清贷款（CLOSED/BAD_DEBT 除外）剩余本金之和——现金贷与消费贷（喵喵支付）共享同一额度池，
     * 借多少扣多少、还清即恢复；交易额加权自动适配服务器通胀水平（欠款系数 debtWeight 已废弃）。
     */
    fun creditLimitFor(playerUuid: UUID, now: Long): Long {
        // 近30天计入额：冷却期内（成交后 N 小时内）不计入——防「现刷现借」组团套现跑路（冷却时长服主可配，0 = 不冷却）
        val cooldownMs = CobbleMarketConfig.creditLimitCooldownHours * 60L * 60 * 1000
        val recent = tradeRecords[playerUuid]
            ?.filter { now - it.at > cooldownMs && now - it.at <= TRADE_WINDOW_MS }
            ?.sumOf { it.countedAmount } ?: 0L
        // history 同步冷却：扣除冷却期内计入的成交额（否则对刷者经 history×0.1 冷却期内即可借，冷却形同虚设）
        val cooldownPending = tradeRecords[playerUuid]
            ?.filter { now - it.at <= cooldownMs }
            ?.sumOf { it.countedAmount } ?: 0L
        val history = ((totalCountedVolume[playerUuid] ?: 0L) - cooldownPending).coerceAtLeast(0L)
        val debt = loans.values
            .filter { it.playerUuid == playerUuid && it.status != LoanStatus.CLOSED && it.status != LoanStatus.BAD_DEBT }
            .sumOf { it.remainingPrincipal.toLong() }
        // 喵喵紫卡持有者：额度 = 配置固定值 − 欠款（不受公式/上下限钳制）
        if (isPurpleCardHolder(playerUuid)) {
            return (CobbleMarketConfig.purpleCardCreditLimit - debt).coerceAtLeast(0L)
        }
        // Long×Double → Double 实算后四舍五入（金额一律 Long 的钳制前形态）
        val base = Math.round(
            recent * CobbleMarketConfig.creditLimitRecent30Weight +
                history * CobbleMarketConfig.creditLimitHistoryWeight
        ).coerceAtLeast(CobbleMarketConfig.creditLimitMin)
        return (base - debt).coerceAtLeast(0L).coerceAtMost(CobbleMarketConfig.creditLimitMax)
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
            c.putLong("createdAt", record.createdAt)
            c.putLong("lastRepayAt", record.lastRepayAt)
            c.putDouble("dailyRate", record.dailyRate)
            c.putString("source", record.source.name)
            c.putString("status", record.status.name)
            c.putInt("dueReminded", record.dueRemindedPeriods)
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
        val tradeList = NbtList()
        tradeRecords.forEach { (uuid, entries) ->
            val c = NbtCompound()
            c.putUuid("uuid", uuid)
            val sub = NbtList()
            entries.forEach { e ->
                val ec = NbtCompound()
                ec.putLong("amount", e.amount)
                ec.putLong("at", e.at)
                ec.putUuid("counterparty", e.counterpartyUuid)
                ec.putLong("counted", e.countedAmount)
                sub.add(ec)
            }
            c.put("entries", sub)
            tradeList.add(c)
        }
        nbt.put("tradeRecords", tradeList)
        val volumeList = NbtList()
        totalCountedVolume.forEach { (uuid, v) ->
            val c = NbtCompound()
            c.putUuid("uuid", uuid)
            c.putLong("volume", v)
            volumeList.add(c)
        }
        nbt.put("totalCountedVolume", volumeList)
        val depositList = NbtList()
        deposits.forEach { (uuid, account) ->
            val c = NbtCompound()
            c.putUuid("uuid", uuid)
            c.putLong("principal", account.principal)
            c.putLong("lastSettleAt", account.lastSettleAt)
            depositList.add(c)
        }
        nbt.put("deposits", depositList)
        val cardList = NbtList()
        purpleCardHolders.forEach { uuid ->
            val c = NbtCompound()
            c.putUuid("uuid", uuid)
            cardList.add(c)
        }
        nbt.put("purpleCards", cardList)
        return nbt
    }

    companion object {
        private const val IP_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        /** 成交额窗口（额度公式近30天项） */
        private const val TRADE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

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
                                createdAt = c.getLong("createdAt"),
                                lastRepayAt = c.getLong("lastRepayAt"),
                                dailyRate = c.getDouble("dailyRate"),
                                source = try { LoanSource.valueOf(c.getString("source")) } catch (_: Exception) { LoanSource.COUNTER },
                                status = try { LoanStatus.valueOf(c.getString("status")) } catch (_: Exception) { LoanStatus.ACTIVE },
                                dueRemindedPeriods = c.getInt("dueReminded")
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
                    nbt.getList("tradeRecords", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            val list = mutableListOf<TradeRecord>()
                            c.getList("entries", NbtList.COMPOUND_TYPE.toInt()).forEach { sub ->
                                val ec = sub as NbtCompound
                                list.add(
                                    TradeRecord(
                                        amount = ec.getLong("amount"),
                                        at = ec.getLong("at"),
                                        counterpartyUuid = ec.getUuid("counterparty"),
                                        countedAmount = ec.getLong("counted")
                                    )
                                )
                            }
                            tradeRecords[c.getUuid("uuid")] = list
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted finance trade entry: {}", e.message)
                        }
                    }
                    nbt.getList("totalCountedVolume", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            totalCountedVolume[c.getUuid("uuid")] = c.getLong("volume")
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted finance volume entry: {}", e.message)
                        }
                    }
                    nbt.getList("deposits", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            deposits[c.getUuid("uuid")] = DepositAccount(c.getLong("principal"), c.getLong("lastSettleAt"))
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted finance deposit entry: {}", e.message)
                        }
                    }
                    nbt.getList("purpleCards", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            purpleCardHolders.add((element as NbtCompound).getUuid("uuid"))
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted finance purple card entry: {}", e.message)
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
