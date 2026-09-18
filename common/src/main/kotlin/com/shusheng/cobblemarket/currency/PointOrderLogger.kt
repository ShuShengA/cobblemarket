package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.platform.configDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 订单生命周期里会落日志的几件事（**钱没动也算**，所以与 [PointsLogType] 分开） */
enum class PointOrderLogType { CREATE, PAID, GRANT, VOID_TIMEOUT, VOID_PLAYER, VOID_OPERATOR }

/**
 * **充值订单流水**（`config/cobblemarket/points/points_orders_<日期>_<语言>.csv`）。
 *
 * 与 [PointsAuditLogger] 的分工：那份记的是**RP 余额变动**（钱动了才写），而本份记的是
 * **订单的生命周期**（创建 → 玩家声明已付 → 发放 / 作废），**每一笔都写**，包括：
 *
 * - 玩家下了单、付了钱、服主**一直没处理**的 —— 它在 [PointsAuditLogger] 里**根本没有痕迹**
 *   （余额没动），只能从订单账本 `.dat` 里翻，而那是二进制、服主读不了（2026-09-18 用户提出）
 * - 被超时自动作废的、玩家自己取消的 —— 同样不进余额流水，但都该有据可查
 *
 * 所以本账本是**余额流水的补充**：两份合起来，服主离开游戏也能把
 * 「谁什么时候下了单 → 后来怎么了」和「钱到底进没进」对起来。
 *
 * 写法照 [PointsAuditLogger]：中英双份、按天分文件、**只追加不改**、同步写盘、
 * CSV 注入防护、旧表头自动另存。写失败只 warn —— 记账绝不能影响充值流程本身。
 */
object PointOrderLogger {

    /** CSV 注入防护：这几个字符开头会被表格软件当成公式（照 PointsAuditLogger） */
    private val DANGEROUS_PREFIXES = setOf('=', '+', '-', '@')

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private var currentDate = ""
    private var currentZh: File? = null
    private var currentEn: File? = null

    /**
     * 记一笔订单事件。
     *
     * @param operatorName 触发者：玩家自己操作写玩家名；服主确认/作废写服主名；系统（超时）写「服务器」
     */
    fun log(
        type: PointOrderLogType,
        order: PointOrder,
        operatorName: String,
        detail: String = "",
        detailEn: String = detail,
        timestamp: Long = System.currentTimeMillis()
    ) {
        try {
            refreshFiles(timestamp)
            val zh = currentZh ?: return
            val en = currentEn ?: return
            Files.writeString(
                zh.toPath(),
                buildLine(type, order, operatorName, detail, timestamp, true) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
            Files.writeString(
                en.toPath(),
                buildLine(type, order, operatorName, detailEn, timestamp, false) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to write point order log: {}", e.message)
        }
    }

    private fun buildLine(
        type: PointOrderLogType,
        order: PointOrder,
        operatorName: String,
        detail: String,
        timestamp: Long,
        zh: Boolean
    ): String {
        val typeText = if (zh) when (type) {
            PointOrderLogType.CREATE -> "创建订单"
            PointOrderLogType.PAID -> "玩家声明已付"
            PointOrderLogType.GRANT -> "确认发放"
            PointOrderLogType.VOID_TIMEOUT -> "超时作废"
            PointOrderLogType.VOID_PLAYER -> "玩家取消"
            PointOrderLogType.VOID_OPERATOR -> "服主作废"
        } else when (type) {
            PointOrderLogType.CREATE -> "created"
            PointOrderLogType.PAID -> "claimed paid"
            PointOrderLogType.GRANT -> "granted"
            PointOrderLogType.VOID_TIMEOUT -> "voided (timeout)"
            PointOrderLogType.VOID_PLAYER -> "voided (player)"
            PointOrderLogType.VOID_OPERATOR -> "voided (operator)"
        }
        val time = timeFormat.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
        return listOf(
            time,
            typeText,
            PointOrderNumbers.format(order.orderNo),
            order.playerName,
            order.playerUuid.toString(),
            order.tierName,
            PointShopService.formatCents(order.payCents),
            order.points.toString(),
            operatorName,
            detail
        ).joinToString(",") { cell(it) }
    }

    /** 转义一格：先挡住公式前缀，再按 CSV 规则加引号 */
    private fun cell(raw: String): String {
        val safe = if (raw.isNotEmpty() && raw[0] in DANGEROUS_PREFIXES) "'$raw" else raw
        return if (safe.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${safe.replace("\"", "\"\"")}\""
        } else {
            safe
        }
    }

    private fun refreshFiles(timestamp: Long) {
        val date = dateFormat.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
        if (date == currentDate) return
        currentDate = date
        currentZh = resolveFile(date, "zh_cn", ZH_HEADER)
        currentEn = resolveFile(date, "en_us", EN_HEADER)
    }

    private fun resolveFile(date: String, lang: String, header: String): File {
        val dir = configDir().resolve("cobblemarket/points").toFile()
        dir.mkdirs()
        val file = File(dir, "points_orders_${date}_${lang}.csv")
        if (file.exists()) {
            val firstLine = file.useLines { it.firstOrNull() } ?: ""
            if (firstLine.isNotEmpty() && firstLine.trim() != header.trim()) {
                val legacy = File(dir, "points_orders_${date}_${lang}_legacy_${System.currentTimeMillis()}.csv")
                file.renameTo(legacy)
                CobbleMarket.LOGGER.info("Point order log {} has an old header; preserved as {}", file.name, legacy.name)
            }
        }
        if (!file.exists()) file.writeText(header + "\n")
        return file
    }

    private const val ZH_HEADER = "时间,事件,订单号,玩家,玩家UUID,档位,实付金额,发放点数,操作者,备注"
    private const val EN_HEADER = "Time,Event,Order,Player,Player UUID,Tier,Amount Paid,Points,Operator,Details"
}
