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

/**
 * 金融管理变更审计账本（金融管理界面的「变更记录」页签）。
 *
 * ⚠ **跨来源**的一本账（项目里第一次这么做）：卡片发放 / 卡片收回 / 禁止借款 / 解除禁止借款 / 调整额度
 * 混在**同一本**，所以列必须**统一**、不适用的列留空 —— 照 `BanChangeLogger` 的做法
 * （那边也是"封禁/解封/修改"三态共用一组列，靠空列区分）。
 *
 * 中英双份 + **按天分文件**，落在 `config/cobblemarket/finance/`：
 * 与贷款账本 `credit/`（`CreditFileLogger`，记"钱怎么走的"）、封禁账本 `bans/`、权限账本 `permissions/` 分开。
 * 写入失败**只记日志、绝不打断操作本身**（照 PermissionFileLogger / BanChangeLogger）。
 *
 * ⚠⭐ **本文档诞生之前的卡片发放/收回没有账本**（`CreditFileLogger` 只管贷款），
 *   ⇒ **历史补不回来**，只能从本账本上线那天开始记（用户 2026-10-05 知悉）。
 *
 * ### 列：时间 / 执行者 / 目标玩家 / 目标UUID / 动作 / 时长 / 数值 / 原数值 / 卡种 / 理由
 *
 * - 「执行者」「动作」「理由」里凡是**已知短语**，一律存 `$cobblemarket.xxx` 的**词条键形式**，
 *   由界面按**客户端语言**渲染（与封禁账本同款）；玩家手填的理由原样存 ⇒ 账本语言中立。
 * - 「时长」= 禁借的有效时长（`expiresAt - blockedAt`，永久留空），界面用 `BanState.remainingText` 渲染。
 * - 「数值」/「原数值」= 调整额度前后的**可用额度**（`creditLimitFor` 的实算值，不是修正量本身）
 *   —— 用户要的是「把谁的额度从多少改成多少」，存实算值才对得上他看到的数。
 * - 「卡种」= `purple` / `black`（原样存枚举名，界面映射到现成卡名词条），只有卡片类动作有值。
 */
object FinanceChangeLogger {

    /** CSV 公式注入防护：以这些字符开头的字段会被 Excel 当公式执行 */
    private val DANGEROUS_PREFIXES = setOf('=', '+', '-', '@')

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private var currentDate = ""
    private var currentZh: File? = null
    private var currentEn: File? = null

    /** 动作词条键（**存进 CSV 的就是这个**，界面按客户端语言渲染） */
    const val ACTION_CARD_GRANT = "\$cobblemarket.finance.action.card_grant"
    const val ACTION_CARD_REVOKE = "\$cobblemarket.finance.action.card_revoke"
    const val ACTION_LOAN_BLOCK = "\$cobblemarket.finance.action.loan_block"
    const val ACTION_LOAN_UNBLOCK = "\$cobblemarket.finance.action.loan_unblock"
    const val ACTION_CREDIT_SET = "\$cobblemarket.finance.action.credit_set"

    /**
     * 记一次金融管理变更。
     *
     * @param operator  执行者名字（管理员）
     * @param actionKey [ACTION_*] 之一
     * @param durationMs 禁借时长毫秒（永久 = null）；非禁借动作传 null
     * @param value / @param prevValue 调整额度：**改后 / 改前**的可用额度；其余动作传 null
     * @param cardKind  `purple` / `black`；其余动作传空串
     * @param reason    理由（`$词条键` 或玩家原文）；不适用传空串
     * @return 是否写成功（失败已 warn，调用方**不需要**中断操作）
     */
    fun log(
        operator: String,
        targetName: String,
        targetUuid: UUID,
        actionKey: String,
        durationMs: Long? = null,
        value: Long? = null,
        prevValue: Long? = null,
        cardKind: String = "",
        reason: String = "",
        timestamp: Long = System.currentTimeMillis()
    ): Boolean {
        return try {
            refreshFiles(timestamp)
            val zh = currentZh ?: return false
            val en = currentEn ?: return false
            val time = timeFormat.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
            val row = listOf(
                time,
                esc(operator),
                esc(targetName),
                targetUuid.toString(),
                esc(actionKey),
                durationMs?.toString().orEmpty(),
                value?.toString().orEmpty(),
                prevValue?.toString().orEmpty(),
                cardKind,
                esc(reason)
            ).joinToString(",")
            Files.writeString(zh.toPath(), row + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            Files.writeString(en.toPath(), row + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            true
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to write finance change log: {}", e.message)
            false
        }
    }

    /**
     * 读**全部**变更记录（界面「变更记录」页签用；新的在前）。
     * ⚠ 与封禁账本同口径：**不做保留期**（只读 `_zh_cn` 一份，两份内容完全一致、只差表头）。
     */
    fun readAll(): List<FinanceChangeRow> {
        val dir = financeDir()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.contains("_zh_cn") }
            ?.flatMap { parseFile(it) }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    private fun esc(s: String): String {
        val safe = if (s.isNotEmpty() && s[0] in DANGEROUS_PREFIXES) "'$s" else s
        return if (safe.any { it == ',' || it == '"' || it == '\n' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun refreshFiles(timestamp: Long) {
        val date = dateFormat.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
        if (date == currentDate) return
        currentDate = date
        currentZh = resolveFile(date, "zh_cn", "时间,执行者,目标玩家,目标UUID,动作,时长,数值,原数值,卡种,理由")
        currentEn = resolveFile(date, "en_us", "Time,Operator,Target,Target UUID,Action,Duration,Value,Prev Value,Card,Reason")
    }

    private fun financeDir(): File = configDir().resolve("cobblemarket/finance").toFile()

    private fun resolveFile(date: String, lang: String, header: String): File {
        val dir = financeDir()
        dir.mkdirs()
        val file = File(dir, "${FILE_PREFIX}${date}_${lang}.csv")
        if (file.exists()) {
            val firstLine = file.useLines { it.firstOrNull() } ?: ""
            // 表头变了（版本升级改了列）就重开一个带后缀的文件，别往旧结构里硬追加
            if (firstLine.isNotEmpty() && firstLine.trim() != header.trim()) {
                return File(dir, "${FILE_PREFIX}${date}_${lang}_${System.currentTimeMillis() % 100000}.csv")
                    .also { it.writeText(header + "\n") }
            }
        } else {
            file.writeText(header + "\n")
        }
        return file
    }

    /**
     * 按 CSV 规则切一行：**引号内的逗号不算分隔符**，`""` 是转义的双引号。
     * ⚠ 不能用 `split(",")` —— 理由 / 玩家名都可能带逗号，被 [esc] 包成引号后简单切分会把
     *   引号里的逗号也切开 ⇒ **后面所有列整体错位**（PermissionFileLogger 上踩过）。
     */
    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun parseFile(file: File): List<FinanceChangeRow> = try {
        file.useLines { lines ->
            lines.drop(1).mapNotNull { raw ->
                val cols = splitCsvLine(raw)
                if (cols.size < 5) return@mapNotNull null
                val ts = runCatching {
                    java.time.LocalDateTime.parse(cols[0], timeFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull() ?: return@mapNotNull null
                FinanceChangeRow(
                    timestamp = ts,
                    operator = cols[1].trim('"'),
                    targetName = cols[2].trim('"'),
                    targetUuid = runCatching { UUID.fromString(cols[3]) }.getOrNull() ?: UUID(0, 0),
                    actionKey = cols[4].trim('"'),
                    // 后五列随动作类型不同而空着 —— 一律**容错读**，缺列/缺值按"没有"处理
                    durationMs = cols.getOrNull(5)?.trim('"')?.toLongOrNull(),
                    value = cols.getOrNull(6)?.trim('"')?.toLongOrNull(),
                    prevValue = cols.getOrNull(7)?.trim('"')?.toLongOrNull(),
                    cardKind = cols.getOrNull(8)?.trim('"').orEmpty(),
                    reason = cols.getOrNull(9)?.trim('"').orEmpty()
                )
            }.toList()
        }
    } catch (e: Exception) {
        CobbleMarket.LOGGER.warn("Failed to read finance change log: {}", e.message)
        emptyList()
    }

    private const val FILE_PREFIX = "finance_changes_"
}

/**
 * 一条金融管理变更记录（界面「变更记录」页签用）。
 *
 * ⚠ [operator] / [actionKey] / [reason] 里可能存的是 `$` 开头的**词条键** ——
 * 显示前一律过一遍 `BanState.reasonText(...)`（它按 `$` 前缀判断要不要翻译）。
 */
data class FinanceChangeRow(
    val timestamp: Long,
    val operator: String,
    val targetName: String,
    val targetUuid: UUID,
    /** [FinanceChangeLogger.ACTION_*] 之一 */
    val actionKey: String,
    /** 禁借时长毫秒；永久与其余动作为 null */
    val durationMs: Long?,
    /** 调整额度：改后的可用额度；其余为 null */
    val value: Long?,
    /** 调整额度：改前的可用额度；其余为 null */
    val prevValue: Long?,
    /** `purple` / `black`；非卡片动作为空串 */
    val cardKind: String,
    val reason: String
)
