package com.shusheng.cobblemarket.permission

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
 * 权限变更审计账本（docs/permission_nodes_plan.md）。
 *
 * 中英双份、按天分文件，落在 `config/cobblemarket/permissions/` —— 与金融账本（`credit/`）分开放，
 * 服主可用同一套习惯查。写入失败只记日志，**绝不打断权限操作本身**（照 CreditFileLogger）。
 */
object PermissionFileLogger {

    /** CSV 公式注入防护：以这些字符开头的字段会被 Excel 当公式执行 */
    private val DANGEROUS_PREFIXES = setOf('=', '+', '-', '@')

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private var currentDate = ""
    private var currentZh: File? = null
    private var currentEn: File? = null

    /**
     * 记一次权限变更。[added] / [removed] 是本次的差集 —— 两者都空则不写（无变化不刷账本）。
     * 界面「删除」也走这里（added 空、removed 为该玩家原本的全部节点）。
     */
    fun log(
        operator: String,
        targetName: String,
        targetUuid: UUID,
        added: Set<String>,
        removed: Set<String>,
        remaining: Set<String>,
        timestamp: Long = System.currentTimeMillis()
    ): Boolean {
        if (added.isEmpty() && removed.isEmpty()) return true
        try {
            refreshFiles(timestamp)
            val zh = currentZh ?: return false
            val en = currentEn ?: return false
            val time = timeFormat.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
            // 空的半边**留空字段**，别用 "-" 占位：esc() 会把 '-' 当 Excel 公式前缀转义成 "'-"，
            // 界面还得额外认这个形态（踩过：显示成「新增：'-」）
            val addText = if (added.isEmpty()) "" else shortNames(added)
            val remText = if (removed.isEmpty()) "" else shortNames(removed)
            val remaText = if (remaining.isEmpty()) "" else shortNames(remaining)
            val row = listOf(
                time, esc(operator), esc(targetName), targetUuid.toString(), esc(addText), esc(remText), esc(remaText)
            ).joinToString(",")
            Files.writeString(zh.toPath(), row + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            Files.writeString(en.toPath(), row + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            return true
        } catch (e: Exception) {
            // ⚠ 最常见的失败原因是账本被 Excel / WPS 打开着（Windows 下文件被独占），
            // 返回 false 让调用方给 OP 一句提示 —— 否则审计静默缺失，事后查无此账
            CobbleMarket.LOGGER.warn("Failed to write permission audit log: {}", e.message)
            return false
        }
    }

    /** 节点短名，按 ALL 顺序稳定排列；多节点用 `, ` 分隔（与界面、命令输出同一套写法，便于肉眼比对）。
     *  ⚠ 含逗号的字段会被 [esc] 用引号包起来，读回时 trim('"') 还原，不影响 CSV 解析。 */
    private fun shortNames(nodes: Set<String>): String =
        MarketPermissions.ALL.filter { it in nodes }.joinToString(", ") { it.removePrefix(MarketPermissions.PREFIX) }

    private fun esc(s: String): String {
        val safe = if (s.isNotEmpty() && s[0] in DANGEROUS_PREFIXES) "'$s" else s
        return if (safe.any { it == ',' || it == '"' || it == '\n' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun refreshFiles(timestamp: Long) {
        val date = dateFormat.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
        if (date == currentDate) return
        currentDate = date
        currentZh = resolveFile(date, "zh_cn", "时间,执行者,目标玩家,目标UUID,新增节点,移除节点,剩余节点")
        currentEn = resolveFile(date, "en_us", "Time,Operator,Target,Target UUID,Added,Removed,Remaining")
    }

    private fun resolveFile(date: String, lang: String, header: String): File {
        val dir = configDir().resolve("cobblemarket/permissions").toFile()
        dir.mkdirs()
        val file = File(dir, "permission_changes_${date}_${lang}.csv")
        if (file.exists()) {
            val firstLine = file.useLines { it.firstOrNull() } ?: ""
            // 表头变了（版本升级改了列）就重开一个带后缀的文件，别往旧结构里硬追加
            if (firstLine.isNotEmpty() && firstLine.trim() != header.trim()) {
                return File(dir, "permission_changes_${date}_${lang}_${System.currentTimeMillis() % 100000}.csv")
                    .also { it.writeText(header + "\n") }
            }
        } else {
            file.writeText(header + "\n")
        }
        return file
    }

    /** 读取最近 [days] 天的变更记录（界面「变更记录」页签用；新的在前） */
    fun readRecent(days: Int = 14): List<AuditRow> {
        val dir = configDir().resolve("cobblemarket/permissions").toFile()
        if (!dir.isDirectory) return emptyList()
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        return dir.listFiles { f -> f.isFile && f.name.startsWith("permission_changes_") && f.name.contains("_zh_cn") }
            ?.flatMap { file -> parseFile(file, cutoff) }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /**
     * 按 CSV 规则切一行：**引号内的逗号不算分隔符**，`""` 是转义的双引号。
     *
     * ⚠ 不能用 `split(",")`：节点串本身就含逗号（`"blacklist, market"`），这类字段会被 [esc]
     * 加引号包裹，简单切分会把引号里的逗号也切开，导致**后面所有列整体错位**
     * （2026-09-16 踩到：变更记录显示成「新增：市场管理 / 移除：封禁 / 剩余：黑名单」——
     * 其实是同一条节点串被切成了三段）。
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
                        sb.append('"')   // "" → 一个双引号
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

    private fun parseFile(file: File, cutoff: Long): List<AuditRow> = try {
        file.useLines { lines ->
            lines.drop(1).mapNotNull { raw ->
                val cols = splitCsvLine(raw)
                if (cols.size < 6) return@mapNotNull null
                val ts = runCatching {
                    java.time.LocalDateTime.parse(cols[0], timeFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull() ?: return@mapNotNull null
                if (ts < cutoff) return@mapNotNull null
                // 第 7 列（剩余节点）是后加的：旧账本没有这一列，取不到就算空，不影响其它字段
                AuditRow(
                    ts, cols[1].trim('"'), cols[2].trim('"'),
                    runCatching { UUID.fromString(cols[3]) }.getOrNull() ?: UUID(0, 0),
                    cols[4].trim('"'), cols[5].trim('"'), cols.getOrNull(6)?.trim('"').orEmpty()
                )
            }.toList()
        }
    } catch (e: Exception) {
        CobbleMarket.LOGGER.warn("Failed to read permission audit log: {}", e.message)
        emptyList()
    }
}

/** 一条权限变更记录（界面列表用；节点是短名字符串） */
data class AuditRow(
    val timestamp: Long,
    val operator: String,
    val targetName: String,
    val targetUuid: UUID,
    val added: String,
    val removed: String,
    /** 变更后该玩家剩下的全部节点（旧账本没有这一列，为空串） */
    val remaining: String
)
