package com.shusheng.cobblemarket.event

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.platform.configDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TransactionFileLogger {

    private val DANGEROUS_PREFIXES = setOf('=', '+', '-', '@')

    // DateTimeFormatter 线程安全（SimpleDateFormat 不是），系统时区与旧行为一致
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private var currentDate = ""
    private var currentZhFile: File? = null
    private var currentEnFile: File? = null

    fun log(record: TransactionRecord) {
        try {
            val instant = Instant.ofEpochMilli(record.timestamp).atZone(ZoneId.systemDefault())
            val date = dateFormat.format(instant)
            if (date != currentDate) {
                currentDate = date
                currentZhFile = resolveFile(date, "zh_cn", "时间,类型,分类,卖家,买家,卖家UUID,买家UUID,精灵/物品,价格,手续费,详情")
                currentEnFile = resolveFile(date, "en_us", "Time,Type,Category,Seller,Buyer,Seller UUID,Buyer UUID,Pokemon/Item,Price,Fee,Details")
            }
            val zhFile = currentZhFile ?: return
            val enFile = currentEnFile ?: return
            Files.writeString(zhFile.toPath(), buildCsvLine(record, "zh_cn") + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            Files.writeString(enFile.toPath(), buildCsvLine(record, "en_us") + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to write transaction log: ${e.message}")
        }
    }

    private fun resolveFile(date: String, lang: String, header: String): File {
        val dir = configDir().resolve("cobblemarket/history").toFile()
        dir.mkdirs()
        ensureReadme(dir)
        val file = File(dir, "history_${date}_${lang}.csv")
        if (file.exists()) {
            // 旧版本头部缺「详情」列：直接 append 会列错位，重命名保留旧文件后按新头新建
            val firstLine = file.useLines { it.firstOrNull() } ?: ""
            if (firstLine.isNotEmpty() && firstLine.trim() != header.trim()) {
                val legacy = File(dir, "history_${date}_${lang}_legacy_${System.currentTimeMillis()}.csv")
                file.renameTo(legacy)
                CobbleMarket.LOGGER.info("CobbleMarket history file {} has old header; preserved as {}", file.name, legacy.name)
            }
        }
        if (!file.exists()) {
            file.writeText(header + "\n")
        }
        return file
    }

    /** history 目录内放置说明文件（只写一次，不覆盖服主修改），服主查账时随手可看 */
    private fun ensureReadme(dir: File) {
        val zh = File(dir, "README_zh_cn.txt")
        if (!zh.exists()) {
            try {
                zh.writeText(
                    """
                    CobbleMarket 交易历史目录说明
                    ==============================

                    本目录下的 CSV 是交易账本（每笔交易同步写盘，不受服务器崩溃/杀进程影响）。

                    字段：时间, 类型, 分类, 卖家, 买家, 精灵/物品, 价格, 手续费, 详情

                    类型：上架（市场/拍卖挂单）、求购（求购单发布，发起者占卖家列）、
                          卖出（市场/拍卖成交、求购单交付成交）、下架（主动关闭/过期/流拍/管理员强制，详情列 reason 区分）、
                          退还（待领取退还）

                    详情列（"|"分隔，缺失字段整段省略）：
                    精灵：lv=等级|shiny=Y/N|ivs=六项个体值|evs=六项努力值|ht=特训项:特训值|nature=生效性格|base=原生性格|ability=特性|gender=性别|ball=球种|held=携带物|form=形态
                    物品：count=数量|ench=附魔id:等级,逗号分隔|nbt={完整NBT文本}
                    下架类记录额外带 reason：user=买家/卖家主动、expired=过期、admin=管理员强制、unsold=拍卖流拍

                    【玩家报告"上架的精灵/物品消失了"时，按三步对账补偿】
                    1. 在当天 CSV 中按玩家名筛选，找到该物品的「上架/求购」记录（记下价格）
                    2. 确认该物品没有后续「卖出/下架/退还」记录
                    3. 确认当前市场/拍卖/求购挂单里也没有该物品

                    三条同时满足 = 数据因服务器异常关闭而丢失 → 按记录的价格补偿玩家。
                    详情列含精灵完整数值与物品 NBT/附魔，可按记录精确复刻货物。

                    更完整的存档/备份说明见模组文档 docs/save-data-locations.md
                    """.trimIndent()
                )
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Failed to write history readme: {}", e.message)
            }
        }
        val en = File(dir, "README_en_us.txt")
        if (!en.exists()) {
            try {
                en.writeText(
                    """
                    CobbleMarket Transaction History Directory
                    ==========================================

                    The CSVs in this directory are the transaction ledger (each trade is written
                    synchronously to disk, unaffected by server crashes or forced kills).

                    Columns: Time, Type, Category, Seller, Buyer, Species/Item, Price, Fee, Details

                    Types: Listed (market/auction listing), Order (buy order placed; the buyer fills the seller column),
                           Sold (market/auction sale, buy order delivery accepted),
                           Cancelled (closed by owner/expired/unsold/admin-forced; the Details reason tells which),
                           Returned (pending claim returned)

                    Details column ("|"-separated; segments omitted when not applicable):
                    Pokémon: lv=level|shiny=Y/N|ivs=six IVs|evs=six EVs|ht=hyper-trained stats|nature=effective nature|base=base nature|ability=ability|gender=gender|ball=ball|held=held item|form=form
                    Item: count=amount|ench=enchantment_id:level,comma-separated|nbt={full NBT text}
                    Cancelled records carry reason=: user=closed by owner, expired, admin=admin-forced, unsold=auction ended unsold

                    [When a player reports "my listed Pokémon/item vanished", verify in 3 steps]
                    1. Filter the day's CSV by the player's name and find the "Listed/Order" record (note the price)
                    2. Confirm there is no later "Sold / Cancelled / Returned" record for it
                    3. Confirm the item is not currently listed on the market/auction/buy orders

                    All three hold = data was lost due to an abnormal server shutdown -> compensate
                    the player at the recorded price. The Details column contains full Pokémon
                    stats and item NBT/enchantments, so the exact goods can be recreated.

                    See the mod docs docs/save-data-locations.md for full save/backup instructions
                    """.trimIndent()
                )
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Failed to write history readme: {}", e.message)
            }
        }
    }

    private fun buildCsvLine(record: TransactionRecord, lang: String): String {
        val time = timeFormat.format(Instant.ofEpochMilli(record.timestamp).atZone(ZoneId.systemDefault()))
        val type = typeName(record.type, lang)
        val category = record.category.name
        val seller = csvEscape(record.sellerName)
        val buyer = csvEscape(record.buyerName)
        val species = csvEscape(speciesDisplay(record.species))
        // 卖家/买家 UUID 供程序回读按 UUID 匹配（改名玩家也查得到）；人读账本看名字列
        val sellerUuid = record.sellerUuid.toString()
        val buyerUuid = record.buyerUuid?.toString() ?: ""
        return "$time,$type,$category,$seller,$buyer,$sellerUuid,$buyerUuid,$species,${record.price},${record.fee},${csvEscape(record.detail)}"
    }

    private fun typeName(type: TransactionType, lang: String): String = when (type) {
        TransactionType.ADD -> if (lang == "zh_cn") "上架" else "Listed"
        TransactionType.PURCHASE -> if (lang == "zh_cn") "卖出" else "Sold"
        TransactionType.CANCEL -> if (lang == "zh_cn") "下架" else "Cancelled"
        TransactionType.RETURN -> if (lang == "zh_cn") "退还" else "Returned"
        TransactionType.ORDER -> if (lang == "zh_cn") "求购" else "Order"
    }

    private fun speciesDisplay(key: String): String {
        val marker = ".species."
        val idx = key.indexOf(marker)
        return if (idx >= 0) key.substring(idx + marker.length).removeSuffix(".name") else key
    }

    private fun csvEscape(s: String): String {
        // 防 CSV 公式注入：Excel/WPS 会把以 = + - @ 开头的单元格当公式执行，
        // 前缀单引号强制按文本处理（Excel 中单引号不显示）
        val guarded = if (s.isNotEmpty() && s[0] in DANGEROUS_PREFIXES) "'$s" else s
        return if (guarded.contains(",") || guarded.contains("\"") || guarded.contains("\n") || guarded.contains("\r")) {
            "\"" + guarded.replace("\"", "\"\"") + "\""
        } else guarded
    }

    // ── 回读（个人交易历史界面）：内存上限（全服 200 条）外的历史从 CSV 账本补 ──

    /** 解析一条 CSV 行（引号状态机，兼容详情列中的逗号/引号） */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { fields.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        fields.add(cur.toString())
        return fields
    }

    /** 英文 CSV 类型名 → 枚举（回读固定读 en 文件，类型名不随语言变） */
    private fun typeFromCsv(name: String): TransactionType? = when (name) {
        "Listed" -> TransactionType.ADD
        "Sold" -> TransactionType.PURCHASE
        "Cancelled" -> TransactionType.CANCEL
        "Returned" -> TransactionType.RETURN
        "Order" -> TransactionType.ORDER
        else -> null
    }

    /** 内存/CSV 合并去重键的时间部分（秒级，与 CSV 行格式一致） */
    fun timestampKey(timestamp: Long): String =
        timeFormat.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

    /**
     * 回读最近 [days] 天（含今天）的 CSV 账本；[playerUuid]/[playerName] 同时非空时过滤该玩家
     * （新格式行按 UUID 匹配——改名玩家也查得到；旧格式行无 UUID 列回退名字匹配），
     * 都为 null 返回全部。结果按时间倒序。
     */
    fun readRecentRecords(playerUuid: java.util.UUID?, playerName: String?, days: Int): List<TransactionRecord> {
        val dir = configDir().resolve("cobblemarket/history").toFile()
        if (!dir.isDirectory) return emptyList()
        val zone = ZoneId.systemDefault()
        val out = mutableListOf<TransactionRecord>()
        for (d in 0 until days) {
            val date = dateFormat.format(java.time.LocalDate.now(zone).minusDays(d.toLong()))
            val file = File(dir, "history_${date}_en_us.csv")
            if (!file.isFile) continue
            try {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val fields = parseCsvLine(line)
                    if (fields.size < 7 || fields[0].startsWith("Time")) return@forEachLine
                    val type = typeFromCsv(fields[1]) ?: return@forEachLine
                    // 新格式（含 UUID 列）字段序：0时间 1类型 2分类 3卖家 4买家 5卖家UUID 6买家UUID 7物种 8价格 9手续费 10详情
                    // 旧格式：0时间 1类型 2分类 3卖家 4买家 5物种 6价格 7手续费 8详情
                    val hasUuid = fields.size >= 10
                    val seller = fields[3].removePrefix("'")
                    val buyer = fields[4].removePrefix("'")
                    val sellerUuid = if (hasUuid) runCatching { java.util.UUID.fromString(fields[5]) }.getOrNull() else null
                    val buyerUuid = if (hasUuid) fields[6].takeIf { it.isNotEmpty() }?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() } else null
                    val species = fields[if (hasUuid) 7 else 5].removePrefix("'")
                    val price = fields[if (hasUuid) 8 else 6].toIntOrNull() ?: return@forEachLine
                    if (playerUuid != null) {
                        // UUID 匹配优先（新行）；旧行无 UUID 回退名字匹配
                        val uuidMatch = sellerUuid == playerUuid || buyerUuid == playerUuid
                        val nameMatch = playerName != null && (seller == playerName || buyer == playerName)
                        val nameFallbackAllowed = sellerUuid == null && (buyerUuid == null || buyer.isEmpty())
                        if (!uuidMatch && !(nameFallbackAllowed && nameMatch)) return@forEachLine
                    }
                    val timestamp = try {
                        java.time.LocalDateTime.parse(fields[0], timeFormat).atZone(zone).toInstant().toEpochMilli()
                    } catch (e: Exception) {
                        return@forEachLine
                    }
                    out.add(TransactionRecord(
                        timestamp = timestamp,
                        type = type,
                        category = if (fields[2] == "ITEM") TransactionCategory.ITEM else TransactionCategory.POKEMON,
                        sellerUuid = sellerUuid ?: LEGACY_RECORD_UUID,
                        sellerName = seller,
                        buyerUuid = buyerUuid,  // 旧行 null：BUY 判定回退名字
                        buyerName = buyer,
                        species = species,
                        price = price,
                        fee = 0,
                        detail = ""
                    ))
                }
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Failed to read history CSV {}: {}", file.name, e.message)
            }
        }
        return out.sortedByDescending { it.timestamp }
    }
}

/** 旧格式 CSV 行无 UUID 时的占位（界面 BUY 判定据此回退名字匹配） */
val LEGACY_RECORD_UUID: java.util.UUID = java.util.UUID(0L, 0L)
