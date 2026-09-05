package com.shusheng.cobblemarket.finance

import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.config.CurrencyHandler
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.network.RepayEntry
import com.shusheng.cobblemarket.network.RepayListDataPayload
import com.shusheng.cobblemarket.platform.sendToPlayer
import com.shusheng.cobblemarket.util.PersistHelper
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.math.BigInteger
import java.util.UUID

/**
 * 金融系统还款服务（批次 5）：
 * - 自动划扣：每 60 秒扫描 + 玩家登录触发，仅对在线玩家执行（物品货币在背包、离线扣不了）；
 *   可动金额 = 余额 − autoRepayMinBalance，循环还清所有欠期；不足则不划（全额或逾期语义）并标记 OVERDUE。
 * - 主动还款/提前结清：金额服务端实算（客户端不传），getBalance 预检后分批扣款，本金+利息全额进准备金池。
 * - 逾期天数从欠期到期日起算（固定到期基准，批次 6 制裁档位用）。
 */
object FinanceService {

    /** 自动划扣扫描间隔：1200 tick（60 秒） */
    private const val AUTO_REPAY_SCAN_TICKS = 20 * 60
    /** 逾期制裁扫描：每 1440 次划扣扫描（60 秒一次）= 每天一次 */
    private const val SANCTION_SCAN_MULTIPLE = 24 * 60
    /** FINANCE 来源封禁标记（BanInfo.bannedBy；$ 前缀 = 翻译 key，封禁列表显示「喵喵行长」；解冻只解此来源） */
    private const val FINANCE_BAN_MARK = "\$cobblemarket.ban.by_finance"
    /** 旧版本存的标记值（"Finance"），解冻判断兼容存量封禁 */
    private const val LEGACY_FINANCE_BAN_MARK = "Finance"
    /** 已结清贷款保留天数（批次 7 拍板：结清 90 天后从状态清除，审计 CSV 仍可查历史） */
    private const val CLOSED_LOAN_RETAIN_DAYS = 90L

    private var tickCount = 0
    private var sanctionTickCount = 0

    fun tick(server: MinecraftServer) {
        if (++tickCount < AUTO_REPAY_SCAN_TICKS) return
        tickCount = 0
        val state = FinanceState.get(server)
        val now = System.currentTimeMillis()
        val online = server.playerManager.playerList.associateBy { it.uuid }
        // 有欠期贷款的在线玩家（按玩家去重，每个玩家一次全量处理）
        state.getAllLoans()
            .filter { it.status != LoanStatus.CLOSED && it.status != LoanStatus.BAD_DEBT }
            .filter { it.duePeriodsAt(now) > it.periodsPaid }
            .map { it.playerUuid }
            .distinct()
            .forEach { uuid -> online[uuid]?.let { processPlayerLoans(server, it, now) } }
        // 逾期制裁三档（7 天翻倍 / 14 天冻结 / 30 天坏账）：每天扫描一次
        if (++sanctionTickCount >= SANCTION_SCAN_MULTIPLE) {
            sanctionTickCount = 0
            processSanctions(server, now)
        }
    }

    /** 登录触发：覆盖「到期后玩家才上线」场景，无需等下一轮扫描 */
    fun onPlayerJoin(player: ServerPlayerEntity) {
        player.server.execute { processPlayerLoans(player.server, player, System.currentTimeMillis()) }
    }

    /**
     * 处理一个玩家的全部贷款：循环划扣欠期（每还一期重读状态，利息按还款时刻实算），
     * 余额不足（保底后）停止；划扣后仍欠期 → markOverdue + 红通知 + 审计 CSV。
     */
    fun processPlayerLoans(server: MinecraftServer, player: ServerPlayerEntity, now: Long) {
        val state = FinanceState.get(server)
        var autoPaidTotal = 0L
        var dirty = false
        state.getLoansByPlayer(player.uuid)
            .filter { it.status != LoanStatus.CLOSED && it.status != LoanStatus.BAD_DEBT }
            .forEach { loan ->
                var cur = state.getLoan(loan.id) ?: return@forEach
                var paidAny = false
                while (true) {
                    if (cur.status == LoanStatus.CLOSED || cur.status == LoanStatus.BAD_DEBT) break
                    if (cur.duePeriodsAt(now) <= cur.periodsPaid) break
                    val interest = cur.interestSince(now)
                    val due = cur.nextPeriodPrincipal + interest
                    val available = CurrencyHandler.getBalance(player)
                        .subtract(BigInteger.valueOf(CobbleMarketConfig.autoRepayMinBalance.toLong()))
                        .max(BigInteger.ZERO)
                    if (available < BigInteger.valueOf(due)) break
                    if (!removeInChunks(player, due)) break
                    state.depositReserve(due)
                    state.recordRepayment(cur.id, now)
                    autoPaidTotal += due
                    dirty = true
                    paidAny = true
                    CreditFileLogger.logRepayment(
                        player.uuid, player.name.string, cur.id,
                        principalPart = cur.nextPeriodPrincipal,
                        interest = interest,
                        method = RepayMethod.AUTO,
                        detail = "自动划扣，第 ${cur.periodsPaid + 1} 期",
                        detailEn = "Auto-deduct, period ${cur.periodsPaid + 1}"
                    )
                    cur = state.getLoan(cur.id) ?: break
                }
                // 自然还清（最后一期还完）记结清事件
                val after = state.getLoan(loan.id)
                if (paidAny && after != null && after.status == LoanStatus.CLOSED) {
                    CreditFileLogger.logLoan(
                        after, LoanLogType.CLOSED,
                        detail = "自动划扣还清",
                        detailEn = "Cleared by auto-deduct",
                        timestamp = now
                    )
                }
                // 划扣后仍欠期 → 标记逾期（markOverdue 仅 ACTIVE→OVERDUE，已逾期返回 false 不重复通知）
                if (after != null && after.status != LoanStatus.CLOSED && after.status != LoanStatus.BAD_DEBT &&
                    after.duePeriodsAt(now) > after.periodsPaid && state.markOverdue(after.id)
                ) {
                    dirty = true
                    val latest = state.getLoan(after.id) ?: return@forEach
                    CreditFileLogger.logLoan(
                        latest, LoanLogType.OVERDUE,
                        detail = "到期未还，欠 ${latest.duePeriodsAt(now) - latest.periodsPaid} 期",
                        detailEn = "Due unpaid, ${latest.duePeriodsAt(now) - latest.periodsPaid} periods in arrears",
                        timestamp = now
                    )
                    player.sendMessage(
                        Text.translatable("cobblemarket.repay.overdue_notice", latest.id).formatted(Formatting.RED),
                        false
                    )
                }
            }
        if (autoPaidTotal > 0) {
            player.sendMessage(
                Text.translatable(
                    "cobblemarket.repay.auto_paid",
                    CurrencyHandler.goldAmount(autoPaidTotal),
                    CurrencyHandler.goldCurrencyText()
                ).formatted(Formatting.GREEN),
                false
            )
        }
        // 划扣/逾期标记后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
        if (dirty) PersistHelper.requestSave(server)
        // 划扣可能追平欠期（逾期天数归零）→ 实时同步冻结状态（还钱减刑）
        syncFreeze(server, player.uuid)
    }

    // ── 喵喵支付（消费贷，批次 4）：购买时准备金垫付给卖家，买家分期还 ──

    /**
     * 喵喵支付前置校验（照现金贷借款链）：失败返回错误消息（红字 Text），成功返回 null。
     * 额度校验查完整额度公式（欠款×0.3 已含负向项）。
     */
    fun meowthCheck(player: ServerPlayerEntity, amount: Long, planIndex: Int, now: Long): Text? {
        // 文案区分：总开关关 = 喵喵银行没开门；消费贷关 = 喵喵支付暂未开放
        if (!CobbleMarketConfig.financeEnabled) {
            return Text.translatable("cobblemarket.loan.finance_disabled").formatted(Formatting.RED)
        }
        if (!CobbleMarketConfig.consumerLoanEnabled) {
            return Text.translatable("cobblemarket.loan.consumer_disabled").formatted(Formatting.RED)
        }
        val state = FinanceState.get(player.server)
        val mine = state.getLoansByPlayer(player.uuid)
        if (mine.any { it.status == LoanStatus.BAD_DEBT }) {
            return Text.translatable("cobblemarket.loan.bad_debt_blocked").formatted(Formatting.RED)
        }
        if (mine.any { it.status == LoanStatus.OVERDUE }) {
            return Text.translatable("cobblemarket.loan.overdue_blocked").formatted(Formatting.RED)
        }
        val plan = CobbleMarketConfig.loanPlans.getOrNull(planIndex)
        if (plan == null) {
            return Text.translatable("cobblemarket.loan.invalid_plan").formatted(Formatting.RED)
        }
        val limit = state.creditLimitFor(player.uuid, now)
        if (amount > limit) {
            return Text.translatable(
                "cobblemarket.loan.insufficient_limit",
                CurrencyHandler.goldAmount(limit),
                CurrencyHandler.goldCurrencyText()
            ).formatted(Formatting.RED)
        }
        val ip = com.shusheng.cobblemarket.platform.getPlayerIp(player)
        // 紫/黑卡持有者豁免：卡额度远高于默认 IP 上限，且小号不可能持卡（全服限量+门槛），卡本身就是强信任凭证
        if (CobbleMarketConfig.ipDebtLimit > 0 && ip != null && !player.hasPermissionLevel(2)
            && !state.isPurpleCardHolder(player.uuid) && !state.isBlackCardHolder(player.uuid)
        ) {
            val ipDebt = state.debtByIp(ip, now)
            if (ipDebt + amount > CobbleMarketConfig.ipDebtLimit) {
                return Text.translatable(
                    "cobblemarket.loan.ip_limit",
                    CurrencyHandler.goldAmount(CobbleMarketConfig.ipDebtLimit),
                    CurrencyHandler.goldCurrencyText()
                ).formatted(Formatting.RED)
            }
        }
        return null
    }

    /**
     * 喵喵支付记账（货物交付成功后调用）：创建消费贷 + 审计 CSV + IP 记录。
     * 垫付出账（withdrawReserve）由调用方在交付前执行，交付失败调用方 depositReserve 回池、本函数不调用。
     */
    fun meowthPaySettle(
        server: MinecraftServer,
        player: ServerPlayerEntity,
        amount: Long,
        planIndex: Int,
        source: LoanSource,
        now: Long
    ): Unit {
        val state = FinanceState.get(server)
        val plan = CobbleMarketConfig.loanPlans.getOrNull(planIndex)
        val record = state.createLoan(
            playerUuid = player.uuid,
            playerName = player.name.string,
            principal = amount,
            periodsTotal = plan?.periods ?: 1,
            dailyRate = (plan?.feeRate ?: 0.0) / 7.0,
            source = source,
            now = now
        )
        com.shusheng.cobblemarket.platform.getPlayerIp(player)?.let { state.recordIp(player.uuid, it, now) }
        val sourceZh = if (source == LoanSource.POKEMON_BUY) "购精灵" else "购物品"
        val sourceEn = if (source == LoanSource.POKEMON_BUY) "Pokemon purchase" else "Item purchase"
        CreditFileLogger.logLoan(
            record, LoanLogType.CREATED,
            detail = "喵喵支付·$sourceZh，${record.periodsTotal}期",
            detailEn = "Meowth Pay · $sourceEn, ${record.periodsTotal} periods",
            timestamp = now
        )
    }

    /**
     * 图鉴收集数（已捕捉物种数）：Cobblemon 图鉴数据，物种记录 aspects 非空 = 有捕捉记录。
     * 紫卡自行申请条件用；Cobblemon 未安装/数据异常返回 0（条件自然不满足）。
     */
    fun getCaughtSpeciesCount(server: MinecraftServer, uuid: UUID): Int {
        return try {
            val data = com.cobblemon.mod.common.Cobblemon.playerDataManager.get(
                uuid, com.cobblemon.mod.common.api.storage.player.PlayerInstancedDataStoreTypes.POKEDEX
            )
            val manager = data as? com.cobblemon.mod.common.api.pokedex.PokedexManager ?: return 0
            // SpeciesDexRecord.aspects 是 Kotlin private（getter 公开但 Kotlin 侧不可访问）——反射读；
            // aspects 非空 = 该物种有捕捉记录（申请时一次性调用，开销可忽略）
            val getAspects = com.cobblemon.mod.common.api.pokedex.SpeciesDexRecord::class.java.getMethod("getAspects")
            manager.speciesRecords.values.count { rec ->
                (getAspects.invoke(rec) as? Set<*>)?.isNotEmpty() == true
            }
        } catch (e: Throwable) {
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.warn("Failed to read cobblemon pokedex for {}: {}", uuid, e.message)
            0
        }
    }

    // ── 逾期制裁三档（批次 6：7 天手续费翻倍 / 14 天冻结 / 30 天坏账） ──

    /** 手续费乘数（百分比 100/200）：付款方有逾期 ≥7 天的未结清贷款 → 翻倍 */
    fun feeMultiplier(state: FinanceState, payerUuid: UUID, now: Long): Long {
        val overdue = state.getLoansByPlayer(payerUuid).any {
            it.status != LoanStatus.CLOSED && it.status != LoanStatus.BAD_DEBT && it.overdueDaysAt(now) >= 7
        }
        return if (overdue) 200L else 100L
    }

    /** 基础手续费 × 逾期乘数（钳 Int 上限，防 ×2 溢出） */
    fun applyFeeMultiplier(state: FinanceState, payerUuid: UUID, now: Long, baseFee: Long): Long =
        (baseFee * feeMultiplier(state, payerUuid, now) / 100).coerceAtMost(Int.MAX_VALUE.toLong())

    /** 清除玩家背包中的紫卡物品（黑卡升级替代紫卡时主动调用：背包自检有 OP 豁免，主动清兜底） */
    fun clearPurpleCardItems(player: ServerPlayerEntity) {
        player.inventory.main.forEachIndexed { i, stack ->
            if (stack.item is MeowthPurpleCardItem) {
                player.inventory.setStack(i, net.minecraft.item.ItemStack.EMPTY)
            }
        }
    }

    /** 紫卡/黑卡持有者手续费减免（比例 0~1：0.5=减半；与逾期翻倍叠加，乘序无关；黑卡覆盖紫卡） */
    fun applyHolderDiscount(state: FinanceState, payerUuid: UUID, fee: Long): Long {
        // 黑卡优先：同时持有两张时按黑卡配置（高级卡语义，即使黑卡配置更低也按黑卡）
        val blackDiscount = CobbleMarketConfig.blackCardFeeDiscount
        if (blackDiscount > 0.0 && state.isBlackCardHolder(payerUuid)) {
            return (fee * (1.0 - blackDiscount)).toLong()
        }
        val discount = CobbleMarketConfig.purpleCardFeeDiscount
        if (discount <= 0.0 || !state.isPurpleCardHolder(payerUuid)) return fee
        return (fee * (1.0 - discount)).toLong()
    }

    /**
     * 制裁扫描（每天一次）：7 天档发提醒；14 天档加 FINANCE 冻结（不覆盖已有封禁）；
     * 30 天档坏账——标记 BAD_DEBT + CSV + 玩家通知 + OP 告警，冻结保持（防借→拖→销账→再来循环，服主手动解）。
     * 准备金池不动：借款时已出账，池子净流出已体现损失（拍板）。
     */
    fun processSanctions(server: MinecraftServer, now: Long) {
        val state = FinanceState.get(server)
        // 已结清贷款清理（结清超 90 天删除，防存档无限膨胀；审计 CSV 保留历史）
        val purged = state.purgeClosedLoans(now, CLOSED_LOAN_RETAIN_DAYS)
        if (purged > 0) {
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.info("[Finance] purged {} closed loan record(s) older than {} days", purged, CLOSED_LOAN_RETAIN_DAYS)
            com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
        }
        val online = server.playerManager.playerList.associateBy { it.uuid }
        state.getAllLoans().forEach { loan ->
            if (loan.status == LoanStatus.CLOSED || loan.status == LoanStatus.BAD_DEBT) return@forEach
            // 到期前 1 天提醒（ACTIVE 且本期未提醒过）：黄色，每期一次，防重复刷屏
            if (loan.status == LoanStatus.ACTIVE && loan.periodsPaid < loan.periodsTotal &&
                loan.dueRemindedPeriods <= loan.periodsPaid
            ) {
                val nextDueAt = loan.createdAt + (loan.periodsPaid + 1) * LoanRecord.DAY_MS_LONG
                val dueIn = nextDueAt - now
                if (dueIn in 1..LoanRecord.DAY_MS_LONG &&
                    state.markDueReminded(loan.id, loan.periodsPaid + 1)
                ) {
                    online[loan.playerUuid]?.sendMessage(
                        Text.translatable(
                            "cobblemarket.repay.due_soon",
                            loan.id,
                            CurrencyHandler.goldAmount(loan.nextPeriodPrincipal),
                            CurrencyHandler.goldCurrencyText()
                        ).formatted(Formatting.YELLOW),
                        false
                    )
                }
            }
            val days = loan.overdueDaysAt(now)
            if (days < 7) return@forEach
            val player = online[loan.playerUuid]
            when {
                days >= 30 -> {
                    state.markBadDebt(loan.id)
                    val bad = state.getLoan(loan.id) ?: return@forEach
                    CreditFileLogger.logLoan(
                        bad, LoanLogType.BAD_DEBT,
                        detail = "逾期 ${days} 天注销，本金 ${bad.remainingPrincipal}",
                        detailEn = "Written off after ${days} days overdue, principal ${bad.remainingPrincipal}",
                        timestamp = now
                    )
                    player?.sendMessage(
                        Text.translatable(
                            "cobblemarket.repay.sanction_bad_debt",
                            bad.id,
                            CurrencyHandler.goldAmount(bad.remainingPrincipal),
                            CurrencyHandler.goldCurrencyText()
                        ).formatted(Formatting.RED),
                        false
                    )
                    notifyOps(
                        server,
                        Text.translatable(
                            "cobblemarket.repay.op_bad_debt",
                            bad.playerName,
                            bad.id,
                            CurrencyHandler.goldAmount(bad.remainingPrincipal),
                            CurrencyHandler.goldCurrencyText()
                        ).formatted(Formatting.RED)
                    )
                }
                days >= 14 -> {
                    ensureFrozen(server, loan.playerUuid, loan.playerName)
                    player?.sendMessage(
                        Text.translatable("cobblemarket.repay.sanction_freeze", loan.id, days).formatted(Formatting.RED),
                        false
                    )
                }
                else -> player?.sendMessage(
                    Text.translatable("cobblemarket.repay.sanction_fee", loan.id, days).formatted(Formatting.RED),
                    false
                )
            }
        }
    }

    /** 确保 FINANCE 冻结存在（已有任何封禁则不动——不覆盖 OP 手动封禁；无封禁才加） */
    private fun ensureFrozen(server: MinecraftServer, uuid: UUID, name: String) {
        val banState = BanState.get(server)
        if (banState.getBanInfo(uuid, System.currentTimeMillis()) != null) return
        // $ 前缀 = translatable key，渲染时按玩家语言翻译（见 BanState.reasonText）
        banState.ban(uuid, name, FINANCE_BAN_MARK, null, "\$cobblemarket.ban.reason_finance")
    }

    /**
     * 冻结状态同步（还款/划扣后实时调用）：
     * 坏账玩家保持冻结；仍有逾期 ≥14 天 → 确保冻结；全部降到 <14 → 只解 FINANCE 来源封禁（OP 封禁不动）。
     */
    fun syncFreeze(server: MinecraftServer, uuid: UUID) {
        val state = FinanceState.get(server)
        val now = System.currentTimeMillis()
        val loans = state.getLoansByPlayer(uuid)
        val name = loans.firstOrNull()?.playerName ?: ""
        val hasBadDebt = loans.any { it.status == LoanStatus.BAD_DEBT }
        if (hasBadDebt) {
            ensureFrozen(server, uuid, name)
            return
        }
        val maxDays = loans
            .filter { it.status != LoanStatus.CLOSED && it.status != LoanStatus.BAD_DEBT }
            .maxOfOrNull { it.overdueDaysAt(now) } ?: 0L
        val banState = BanState.get(server)
        if (maxDays >= 14) {
            ensureFrozen(server, uuid, name)
        } else {
            val info = banState.getBanInfo(uuid, now)
            if (info != null && (info.bannedBy == FINANCE_BAN_MARK || info.bannedBy == LEGACY_FINANCE_BAN_MARK)) {
                banState.unban(uuid)
                server.playerManager.getPlayer(uuid)?.sendMessage(
                    Text.translatable("cobblemarket.repay.unfrozen").formatted(Formatting.GREEN),
                    false
                )
            }
        }
    }

    /** 在线 OP 广播（坏账告警） */
    private fun notifyOps(server: MinecraftServer, text: Text) {
        server.playerManager.playerList.forEach { p ->
            if (p.hasPermissionLevel(2)) p.sendMessage(text, false)
        }
    }

    /** 主动还款（settle=false 还一期 / true 提前结清）：金额服务端实算，分批扣款，本金+利息进池 */
    fun executeRepay(player: ServerPlayerEntity, loanId: Long, settle: Boolean) {
        val server = player.server
        val state = FinanceState.get(server)
        val now = System.currentTimeMillis()
        val loan = state.getLoan(loanId)
        if (loan == null || loan.playerUuid != player.uuid) {
            player.sendMessage(Text.translatable("cobblemarket.repay.not_yours").formatted(Formatting.RED), false)
            return
        }
        if (loan.status == LoanStatus.CLOSED || loan.status == LoanStatus.BAD_DEBT) {
            player.sendMessage(Text.translatable("cobblemarket.repay.already_closed").formatted(Formatting.RED), false)
            return
        }
        val interest = loan.interestSince(now)
        val principalPart = if (settle) loan.remainingPrincipal else loan.nextPeriodPrincipal
        val total = principalPart + interest
        if (total <= 0) {
            player.sendMessage(Text.translatable("cobblemarket.repay.already_closed").formatted(Formatting.RED), false)
            return
        }
        // 预检余额（BigInteger 精确比较，防 Int 截断误判）
        val balance = CurrencyHandler.getBalance(player)
        if (balance < BigInteger.valueOf(total)) {
            val short = BigInteger.valueOf(total).subtract(balance).max(BigInteger.ZERO).toLong()
            player.sendMessage(
                Text.translatable(
                    "cobblemarket.repay.insufficient",
                    CurrencyHandler.goldAmount(short),
                    CurrencyHandler.goldCurrencyText()
                ).formatted(Formatting.RED),
                false
            )
            return
        }
        if (!removeInChunks(player, total)) {
            player.sendMessage(Text.translatable("cobblemarket.repay.insufficient_failed").formatted(Formatting.RED), false)
            return
        }
        state.depositReserve(total)
        if (settle) {
            state.settleLoan(loan.id, now)
            CreditFileLogger.logRepayment(
                player.uuid, player.name.string, loan.id,
                principalPart = principalPart,
                interest = interest,
                method = RepayMethod.EARLY,
                detail = "提前结清",
                detailEn = "Early settlement"
            )
            val closed = state.getLoan(loan.id)
            if (closed != null) {
                CreditFileLogger.logLoan(
                    closed, LoanLogType.CLOSED,
                    detail = "提前结清",
                    detailEn = "Early settlement",
                    timestamp = now
                )
            }
            player.sendMessage(
                Text.translatable(
                    "cobblemarket.repay.settle_success",
                    CurrencyHandler.goldAmount(principalPart),
                    CurrencyHandler.goldAmount(interest),
                    CurrencyHandler.goldCurrencyText()
                ).formatted(Formatting.GREEN),
                false
            )
        } else {
            state.recordRepayment(loan.id, now)
            CreditFileLogger.logRepayment(
                player.uuid, player.name.string, loan.id,
                principalPart = principalPart,
                interest = interest,
                method = RepayMethod.MANUAL,
                detail = "主动还款，第 ${loan.periodsPaid + 1} 期",
                detailEn = "Manual repayment, period ${loan.periodsPaid + 1}"
            )
            val after = state.getLoan(loan.id)
            if (after != null && after.status == LoanStatus.CLOSED) {
                CreditFileLogger.logLoan(
                    after, LoanLogType.CLOSED,
                    detail = "按期还清",
                    detailEn = "Cleared on schedule",
                    timestamp = now
                )
            }
            player.sendMessage(
                Text.translatable(
                    "cobblemarket.repay.period_success",
                    loan.periodsPaid + 1,
                    CurrencyHandler.goldAmount(principalPart),
                    CurrencyHandler.goldAmount(interest),
                    CurrencyHandler.goldCurrencyText()
                ).formatted(Formatting.GREEN),
                false
            )
        }
        // 回发刷新：还款柜台列表 + 额度快照（欠款已变）
        sendRepayList(player)
        com.shusheng.cobblemarket.network.FinanceNetwork.sendCreditInfo(player, state, now)
        PersistHelper.requestSave(server)
        // 还款可能降档（还一期减 7 天）→ 实时同步冻结状态
        syncFreeze(server, player.uuid)
    }

    /** 还款柜台列表：未结清贷款 + 实算金额（客户端只展示不计算） */
    fun sendRepayList(player: ServerPlayerEntity) {
        val state = FinanceState.get(player.server)
        val now = System.currentTimeMillis()
        val entries = state.getLoansByPlayer(player.uuid)
            .filter { it.status != LoanStatus.CLOSED && it.status != LoanStatus.BAD_DEBT }
            .sortedByDescending { it.id }
            .map { r ->
                val interest = r.interestSince(now)
                RepayEntry(
                    id = r.id,
                    periodsTotal = r.periodsTotal,
                    periodsPaid = r.periodsPaid,
                    remaining = r.remainingPrincipal,
                    periodPrincipal = r.nextPeriodPrincipal,
                    periodInterest = interest,
                    settleTotal = r.remainingPrincipal + interest,
                    status = r.status.name,
                    dueCount = (r.duePeriodsAt(now) - r.periodsPaid).coerceAtLeast(0)
                )
            }
        sendToPlayer(player, RepayListDataPayload(entries))
    }

    /** 分批扣款（CurrencyHandler.remove 为 Int 签名，总额可能超 Int）；调用前已 getBalance 预检，竞态窗口可忽略 */
    fun removeInChunks(player: ServerPlayerEntity, total: Long): Boolean {
        if (total <= 0) return true
        var remaining = total
        while (remaining > 0) {
            val chunk = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
            if (!CurrencyHandler.remove(player, chunk)) return false
            remaining -= chunk
        }
        return true
    }
}
