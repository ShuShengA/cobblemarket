package com.shusheng.cobblemarket.finance

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.platform.configDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 贷款事件类型（loan_records 的类型列）；REVOKED = 服主撤销坏账 */
enum class LoanLogType { CREATED, REVOKED, CLOSED, OVERDUE, BAD_DEBT }

/** 还款方式（repayment_records 的方式列） */
enum class RepayMethod { MANUAL, AUTO, EARLY }

/**
 * 金融系统审计账本（config/cobblemarket/credit/，独立目录不混交易账本）：
 * 每笔借贷/还款事件同步追加写盘（不受崩溃/杀进程影响），只写不改。
 * 与交易账本分工：交易账本回答「货去哪了」，信用账本回答「钱怎么走的」。
 * 照 TransactionFileLogger 模式：中英双份 + README + CSV 注入防护 + 按天分文件 + 旧头保护。
 */
object CreditFileLogger {

    private val DANGEROUS_PREFIXES = setOf('=', '+', '-', '@')

    // DateTimeFormatter 线程安全（SimpleDateFormat 不是），系统时区与交易账本一致
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private var currentDate = ""
    private var currentLoanZh: File? = null
    private var currentLoanEn: File? = null
    private var currentRepayZh: File? = null
    private var currentRepayEn: File? = null

    /** 贷款事件：创建（柜台/购精灵/购物品）/ 撤销（服主撤销坏账）/ 结清 / 逾期 / 坏账；detail 双语（CSV 中英双份） */
    fun logLoan(record: LoanRecord, type: LoanLogType, detail: String, detailEn: String = detail, timestamp: Long = System.currentTimeMillis()) {
        try {
            refreshFiles(timestamp)
            val zhFile = currentLoanZh ?: return
            val enFile = currentLoanEn ?: return
            val lineZh = buildLoanLine(record, type, detail, timestamp, true)
            val lineEn = buildLoanLine(record, type, detailEn, timestamp, false)
            Files.writeString(zhFile.toPath(), lineZh + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            Files.writeString(enFile.toPath(), lineEn + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to write credit loan log: {}", e.message)
        }
    }

    /** 还款事件：主动 / 自动划扣 / 提前结清；detail 双语（CSV 中英双份，照 logLoan） */
    fun logRepayment(
        playerUuid: UUID,
        playerName: String,
        loanId: Long,
        principalPart: Long,
        interest: Long,
        method: RepayMethod,
        detail: String,
        detailEn: String = detail,
        timestamp: Long = System.currentTimeMillis()
    ) {
        try {
            refreshFiles(timestamp)
            val zhFile = currentRepayZh ?: return
            val enFile = currentRepayEn ?: return
            val lineZh = buildRepayLine(playerName, loanId, principalPart, interest, method, detail, timestamp, true)
            val lineEn = buildRepayLine(playerName, loanId, principalPart, interest, method, detailEn, timestamp, false)
            Files.writeString(zhFile.toPath(), lineZh + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            Files.writeString(enFile.toPath(), lineEn + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to write credit repayment log: {}", e.message)
        }
    }

    /** 跨天切换时重建四个文件引用（含表头写入与旧头保护） */
    private fun refreshFiles(timestamp: Long) {
        val date = dateFormat.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
        if (date == currentDate) return
        currentDate = date
        currentLoanZh = resolveFile(date, "loan_records", "zh_cn",
            "时间,类型,来源,玩家,本金,期数,日利率,状态,详情")
        currentLoanEn = resolveFile(date, "loan_records", "en_us",
            "Time,Type,Source,Player,Principal,Periods,Daily Rate,Status,Details")
        currentRepayZh = resolveFile(date, "repayment_records", "zh_cn",
            "时间,玩家,贷款ID,本金部分,利息,方式,详情")
        currentRepayEn = resolveFile(date, "repayment_records", "en_us",
            "Time,Player,Loan ID,Principal Part,Interest,Method,Details")
    }

    private fun resolveFile(date: String, name: String, lang: String, header: String): File {
        val dir = configDir().resolve("cobblemarket/credit").toFile()
        dir.mkdirs()
        ensureReadme(dir)
        val file = File(dir, "${name}_${date}_${lang}.csv")
        if (file.exists()) {
            val firstLine = file.useLines { it.firstOrNull() } ?: ""
            if (firstLine.isNotEmpty() && firstLine.trim() != header.trim()) {
                val legacy = File(dir, "${name}_${date}_${lang}_legacy_${System.currentTimeMillis()}.csv")
                file.renameTo(legacy)
                CobbleMarket.LOGGER.info("CobbleMarket credit file {} has old header; preserved as {}", file.name, legacy.name)
            }
        }
        if (!file.exists()) {
            file.writeText(header + "\n")
        }
        return file
    }

    /** credit 目录说明（只写一次，不覆盖服主修改） */
    private fun ensureReadme(dir: File) {
        val zh = File(dir, "README_zh_cn.txt")
        if (!zh.exists()) {
            try {
                zh.writeText(
                    """
                    CobbleMarket 金融系统（喵喵银行）信用账本目录说明
                    ==================================================

                    本目录下的 CSV 是信用账本（每笔借贷/还款事件同步追加写盘，不受服务器崩溃/杀进程影响，只写不改）。

                    loan_records（贷款事件）：时间, 类型, 来源, 玩家, 本金, 期数, 日利率, 状态, 详情
                      类型：创建（柜台=现金贷/借呗；购精灵/购物品=消费贷/喵喵支付）、
                            撤销（服主撤销坏账）、结清（还完全部期数）、逾期、坏账
                      状态：ACTIVE 正常 / OVERDUE 逾期 / BAD_DEBT 坏账 / CLOSED 已结清

                    repayment_records（还款事件）：时间, 玩家, 贷款ID, 本金部分, 利息, 方式, 详情
                      方式：主动 / 自动划扣 / 提前结清

                    与交易账本（config/cobblemarket/history/）分工：
                    - 交易账本回答「货去哪了」（上架/卖出/下架/退还，按三步对账补偿）
                    - 信用账本回答「钱怎么走的」（借了多少/还了多少/利息/逾期处置）
                    玩家报告借贷纠纷时：先在 loan_records 按玩家名找创建记录（记下贷款ID），
                    再在 repayment_records 按贷款ID 核对该笔的全部还款流水。
                    """.trimIndent()
                )
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Failed to write credit readme: {}", e.message)
            }
        }
        val en = File(dir, "README_en_us.txt")
        if (!en.exists()) {
            try {
                en.writeText(
                    """
                    CobbleMarket Finance System (Meowth Bank) Credit Ledger Directory
                    =========================================================================

                    The CSVs here are the credit ledger (every loan/repayment event is appended
                    synchronously to disk, unaffected by server crashes or forced kills; append-only).

                    loan_records (loan events): Time, Type, Source, Player, Principal, Periods, Daily Rate, Status, Details
                      Types: Created (Counter=cash loan/Jiebei; Pokémon purchase/Item purchase=consumer loan/Meowth Pay),
                             Revoked (bad debt revoked by admin), Closed (all periods repaid), Overdue, Bad debt
                      Status: ACTIVE / OVERDUE / BAD_DEBT / CLOSED

                    repayment_records (repayment events): Time, Player, Loan ID, Principal Part, Interest, Method, Details
                      Methods: Manual / Auto / Early payoff

                    Split of duties with the transaction ledger (config/cobblemarket/history/):
                    - Transaction ledger answers "where did the goods go" (list/sell/cancel/return with the 3-step reconciliation)
                    - Credit ledger answers "where did the money go" (borrowed/repaid/interest/overdue handling)
                    For loan disputes: find the Created record in loan_records by player name (note the loan ID),
                    then check every repayment for that loan ID in repayment_records.
                    """.trimIndent()
                )
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Failed to write credit readme: {}", e.message)
            }
        }
    }

    private fun buildLoanLine(record: LoanRecord, type: LoanLogType, detail: String, timestamp: Long, zh: Boolean): String {
        val time = timeFormat.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
        val typeName = when (type) {
            LoanLogType.CREATED -> if (zh) "创建" else "Created"
            LoanLogType.REVOKED -> if (zh) "撤销" else "Revoked"
            LoanLogType.CLOSED -> if (zh) "结清" else "Closed"
            LoanLogType.OVERDUE -> if (zh) "逾期" else "Overdue"
            LoanLogType.BAD_DEBT -> if (zh) "坏账" else "Bad debt"
        }
        val sourceName = when (record.source) {
            LoanSource.COUNTER -> if (zh) "柜台" else "Counter"
            LoanSource.POKEMON_BUY -> if (zh) "购精灵" else "Pokémon purchase"
            LoanSource.ITEM_BUY -> if (zh) "购物品" else "Item purchase"
        }
        return "$time,$typeName,$sourceName,${csvEscape(record.playerName)},${record.principal},${record.periodsTotal},${record.dailyRate},${record.status.name},${csvEscape(detail)}"
    }

    private fun buildRepayLine(
        playerName: String,
        loanId: Long,
        principalPart: Long,
        interest: Long,
        method: RepayMethod,
        detail: String,
        timestamp: Long,
        zh: Boolean
    ): String {
        val time = timeFormat.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
        val methodName = when (method) {
            RepayMethod.MANUAL -> if (zh) "主动" else "Manual"
            RepayMethod.AUTO -> if (zh) "自动划扣" else "Auto"
            RepayMethod.EARLY -> if (zh) "提前结清" else "Early payoff"
        }
        return "$time,${csvEscape(playerName)},$loanId,$principalPart,$interest,$methodName,${csvEscape(detail)}"
    }

    private fun csvEscape(s: String): String {
        // 防 CSV 公式注入：Excel/WPS 会把以 = + - @ 开头的单元格当公式执行，
        // 前缀单引号强制按文本处理（Excel 中单引号不显示）
        val guarded = if (s.isNotEmpty() && s[0] in DANGEROUS_PREFIXES) "'$s" else s
        return if (guarded.contains(",") || guarded.contains("\"") || guarded.contains("\n") || guarded.contains("\r")) {
            "\"" + guarded.replace("\"", "\"\"") + "\""
        } else guarded
    }
}
