package com.shusheng.cobblemarket.util

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.platform.configDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 退回清理明细日志：清理是资产删除（按服主配置的保留期），记录每一条明细，
 * 写入 config/cobblemarket/history/cleaned_YYYY-MM-DD.csv，便于事后追查。
 */
object CleanupLogger {

    // DateTimeFormatter 线程安全（SimpleDateFormat 不是），系统时区与旧行为一致
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun log(
        category: String,
        playerUuid: UUID,
        listingId: UUID,
        detail: String,
        price: Int,
        retainedDays: Long
    ) {
        try {
            val now = LocalDateTime.now()
            val date = now.format(dateFormat)
            val dir = configDir().resolve("cobblemarket/history").toFile()
            dir.mkdirs()
            val file = File(dir, "cleaned_$date.csv")
            if (!file.exists()) {
                file.writeText("Time,Category,PlayerUUID,ListingID,Detail,Price,RetainedDays\n")
            }
            val safeDetail = "\"" + detail.replace("\"", "\"\"") + "\""
            val line = "${now.format(timeFormat)},$category,$playerUuid,$listingId,$safeDetail,$price,$retainedDays"
            Files.writeString(file.toPath(), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to write cleanup log: {}", e.message)
        }
    }
}
