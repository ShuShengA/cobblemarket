package com.shusheng.cobblemarket.market

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
 * 封禁变更审计账本（`docs/ban_improvements_plan.md` 第三节）。
 *
 * 中英双份 + **按天分文件**，落在 `config/cobblemarket/bans/`（与权限账本 `permissions/`、
 * 金融账本 `credit/` 分开，服主按同一套习惯查）。写入失败**只记日志、绝不打断封禁操作本身**
 * （照 PermissionFileLogger / CreditFileLogger —— 最常见失败原因是账本被 Excel / WPS 独占）。
 *
 * ⚠ **文件永不自动删、读取也不设窗口（全读）** —— 用户 2026-10-04 定：
 * 「封禁不是频繁的操作，文件也不会很大，永久保存都没事，一个服务器能封几个人？」
 * ⇒ 想清理由服主自己删文件，代码里不做保留期、不做 `readRecent(days)` 那一套。
 *
 * ### 列：时间 / 执行者 / 目标玩家 / 目标UUID / 动作 / 时长 / 理由 / 原封禁者 / 借款 / 原时长 / 原借款
 *
 * - 「执行者」「动作」「理由」里凡是**已知短语**（系统来源），一律存 `$cobblemarket.xxx` 的
 *   **词条键形式**，由界面按**客户端语言**渲染（`BanState.reasonText` 同款）；玩家手填的理由原样存。
 *   ⇒ 账本自身**语言中立**：界面在英文客户端上不会冒出中文（本项目踩过"服务端拼死语言文本"的坑）。
 * - 「时长」存**毫秒**（= `expiresAt - bannedAt`，永久封禁留空），界面用 `BanState.remainingText` 渲染
 *   ⇒ 与聊天提示、管理端列表三处口径一致（`3d5h` 那套已在 1.2.1 本地化掉）。
 * - 「原封禁者」**只有解封那行有值**：用户要的场景是「A 封的、B 解的也要查得到」，
 *   这一列让解封那行**自成一条**，不用回头去翻当初封禁那行。
 * - 「借款」= 本次动作**之后**「同时禁止借款」的状态（`Y` / `N`）；解封行记的是**被解掉的那笔**的状态。
 * - 「原时长」「原借款」**只有「修改」那行有值** —— 用户要的「写清改前改后」：
 *   新的时长/借款状态在各自的列里，旧值在这里 ⇒ 两列一比就是「3 天 → 7 天、不选 → 选中」。
 *   ⚠ 这两列**只放数字**（毫秒 / `Y`·`N`），不含自由文本 ⇒ 读端不需要任何转义解析。
 */
object BanChangeLogger {

    /** CSV 公式注入防护：以这些字符开头的字段会被 Excel 当公式执行 */
    private val DANGEROUS_PREFIXES = setOf('=', '+', '-', '@')

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private var currentDate = ""
    private var currentZh: File? = null
    private var currentEn: File? = null

    /**
     * 记一次封禁变更（**在 `BanState.ban` / `unban` 内部调用**，见那里的说明）。
     *
     * @param operator      执行者名字，或系统来源的 `$词条键`（如金融自动冻结 = `$cobblemarket.ban.by_finance`）
     * @param actionKey     `$词条键`：封禁 / 解封
     * @param durationMs    本次封禁的有效时长（`expiresAt - bannedAt`）；永久或解封无原时长时传 null
     * @param reason        封禁理由（`$词条键` 或玩家原文）；解封时传**原封禁的理由**
     * @param originalBanner 原封禁者；**只有解封那行有值**，其余传空串
     * @param blockLoan 本次动作**之后**「同时禁止借款」的状态
     * @param beforeDurationMs / @param beforeBlockLoan **只有「修改」那行有值**（改前的值）
     * @return 是否写成功（失败已 warn，调用方**不需要**中断封禁操作）
     */
    fun log(
        operator: String,
        targetName: String,
        targetUuid: UUID,
        actionKey: String,
        durationMs: Long?,
        reason: String,
        originalBanner: String,
        blockLoan: Boolean,
        beforeDurationMs: Long? = null,
        beforeBlockLoan: Boolean? = null,
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
                esc(reason),
                esc(originalBanner),
                if (blockLoan) "Y" else "N",
                beforeDurationMs?.toString().orEmpty(),
                beforeBlockLoan?.let { if (it) "Y" else "N" }.orEmpty()
            ).joinToString(",")
            Files.writeString(zh.toPath(), row + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            Files.writeString(en.toPath(), row + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            true
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to write ban change log: {}", e.message)
            false
        }
    }

    /**
     * 读**全部**变更记录（界面「变更记录」页签用；新的在前）。
     * ⚠ 有意**不做保留期**（用户定「永久保留」）—— 只读 `_zh_cn` 一份：两份内容完全一致，
     *   差别只有表头（照 `PermissionFileLogger.readRecent` 的既有做法）。
     */
    fun readAll(): List<BanChangeRow> {
        val dir = bansDir()
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
        currentZh = resolveFile(date, "zh_cn", "时间,执行者,目标玩家,目标UUID,动作,时长,理由,原封禁者,借款,原时长,原借款")
        currentEn = resolveFile(date, "en_us", "Time,Operator,Target,Target UUID,Action,Duration,Reason,Original Banner,Block Loans,Prev Duration,Prev Block Loans")
    }

    private fun bansDir(): File = configDir().resolve("cobblemarket/bans").toFile()

    private fun resolveFile(date: String, lang: String, header: String): File {
        val dir = bansDir()
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
     * ⚠ 不能用 `split(",")`：理由 / 玩家名都可能带逗号，被 [esc] 加引号包起来之后，
     * 简单切分会把引号里的逗号也切开 ⇒ **后面所有列整体错位**
     * （PermissionFileLogger 上踩过：变更记录显示成完全不相干的另一条）。
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

    private fun parseFile(file: File): List<BanChangeRow> = try {
        file.useLines { lines ->
            lines.drop(1).mapNotNull { raw ->
                val cols = splitCsvLine(raw)
                if (cols.size < 7) return@mapNotNull null
                val ts = runCatching {
                    java.time.LocalDateTime.parse(cols[0], timeFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull() ?: return@mapNotNull null
                BanChangeRow(
                    timestamp = ts,
                    operator = cols[1].trim('"'),
                    targetName = cols[2].trim('"'),
                    targetUuid = runCatching { UUID.fromString(cols[3]) }.getOrNull() ?: UUID(0, 0),
                    actionKey = cols[4].trim('"'),
                    durationMs = cols[5].trim('"').toLongOrNull(),
                    reason = cols[6].trim('"'),
                    // 后四列都是随功能追加的：一律**容错读**，缺列/缺值按"没有"处理（照 1.2.1 的前几列）
                    originalBanner = cols.getOrNull(7)?.trim('"').orEmpty(),
                    blockLoan = cols.getOrNull(8)?.trim('"') == "Y",
                    beforeDurationMs = cols.getOrNull(9)?.trim('"')?.toLongOrNull(),
                    beforeBlockLoan = cols.getOrNull(10)?.trim('"')?.let { it == "Y" }
                )
            }.toList()
        }
    } catch (e: Exception) {
        CobbleMarket.LOGGER.warn("Failed to read ban change log: {}", e.message)
        emptyList()
    }

    private const val FILE_PREFIX = "ban_changes_"
}

/**
 * 一条封禁变更记录（界面「变更记录」页签用）。
 *
 * ⚠ [operator] / [actionKey] / [reason] / [originalBanner] 里可能存的是 `$` 开头的**词条键** ——
 * 显示前一律过一遍 `BanState.reasonText(...)`（它按 `$` 前缀判断要不要翻译）。
 */
data class BanChangeRow(
    val timestamp: Long,
    val operator: String,
    val targetName: String,
    val targetUuid: UUID,
    /** `$cobblemarket.ban.action_ban` / `..._unban` */
    val actionKey: String,
    /** 有效时长毫秒；永久封禁与解封行（拿不到原时长时）为空 */
    val durationMs: Long?,
    val reason: String,
    /** 解封行 = 当初是谁封的；其余行为空 */
    val originalBanner: String,
    /** 本次动作**之后**「同时禁止借款」的状态；解封行 = 被解掉的那笔的状态 */
    val blockLoan: Boolean,
    /** 「修改」行有值：改前的时长；其余为 null */
    val beforeDurationMs: Long?,
    /** 「修改」行有值：改前的「同时禁止借款」；其余为 null */
    val beforeBlockLoan: Boolean?
)
