package com.shusheng.cobblemarket.event

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.platform.configDir
import com.shusheng.cobblemarket.util.SpeciesText
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
                // ⚠ 表头必须与 buildCsvLine 的列一一对应（含**末尾的币种 / 主属性 / 来源 / 商店名 / 商店ID 五列**）：
                //   漏了会让表头少列、数据多列，Excel 里整行错位
                currentZhFile = resolveFile(date, "zh_cn", "时间,类型,分类,卖家,买家,卖家UUID,买家UUID,精灵/物品,价格,手续费,详情,币种,主属性,来源,商店名,商店ID")
                currentEnFile = resolveFile(date, "en_us", "Time,Type,Category,Seller,Buyer,Seller UUID,Buyer UUID,Pokemon/Item,Price,Fee,Details,Currency,Primary Type,Source,Shop Name,Shop ID")
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

                    字段：时间, 类型, 分类, 卖家, 买家, 卖家UUID, 买家UUID, 精灵/物品, 价格, 手续费, 详情, 币种, 主属性, 来源, 商店名, 商店ID

                    来源：market = 玩家间市场直购，shop = 商店（主 RP 商城或自定义 NPC 商店）。
                    **领取记录也是 shop**：类型列写「领取 / Claimed」，价格 0，不是卖出。
                    商店售出时卖家列是店名（主商城为空），上架者不会因此进个人交易历史；
                    商店名 / 商店ID 只有商店记录有值（商店ID 用于按店筛选，店改名也不影响）。
                    老账本没有这几列，按 market / 空读。

                    币种：PRIMARY = 主货币，POINTS = RP（高级货币）。价格列的数值要与币种一起看——
                    1 RP 与 1 主货币不等值，两种币的账目不能直接相加。老账本没有这一列，按主货币读。

                    主属性：精灵名在界面上的着色依据（形态感知 —— 阿罗拉六尾记的是冰系）；
                    物品记录与老账本该列为空。精灵/物品列本身：精灵存**物种 id**（带命名空间，
                    形如 `cobblemon:pikachu`；老账本里是没带命名空间的裸名）、物品存物品 id。

                    类型：上架（市场/拍卖挂单）、求购（求购单发布，发起者占卖家列）、
                          卖出（市场/拍卖成交、求购单交付成交）、下架（主动关闭/过期/流拍/管理员强制，详情列 reason 区分）、
                          退还（待领取退还）

                    详情列（"|"分隔，缺失字段整段省略）：
                    精灵：lv=等级|shiny=Y/N|ivs=六项个体值|evs=六项努力值|ht=特训项：特训值
                          |nature=生效性格|base=原生性格（用过薄荷才有）|ability=特性|gender=性别
                          |ball=球种|held=携带物id|heldNbt=携带物完整NBT（附魔/自定义名等，无组件时省略）
                          |form=形态|name=昵称|size=体型（XS/S/M/L/XL/ALPHA）
                          |gmax=Y/N（超极巨化）|dmax=极巨等级|ot=初始训练家|tera=太晶属性
                          |friend=亲密度|moves=四个技能槽（逗号分隔，空槽为空）
                          |marks=证章（逗号分隔，每行 10 个）
                    物品：count=数量|ench=附魔id:等级,逗号分隔|nbt={完整NBT文本}

                    ⚠ 证章多的时候（一只精灵最多近 200 个）是**在同一个格子里换行**的（每 10 个一行）：
                      Excel 里需要开启「自动换行」才能看全；用程序读请按标准 CSV 解析
                      （引号内的换行属于同一个格子，别按行切）。
                    ⚠ 精灵这一段现在**从精灵的完整数据读取**，所以升级前就挂上的老条目同样能补齐
                      （昵称 / 体型 / 极巨化 / 初始训练家这些原先没记的，现在也查得到）。
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

                    Columns: Time, Type, Category, Seller, Buyer, Seller UUID, Buyer UUID, Species/Item, Price, Fee, Details, Currency, Primary Type, Source, Shop Name, Shop ID

                    Currency: PRIMARY = main currency, POINTS = RP (the premium currency). Always read the Price
                    column together with it — 1 RP and 1 main currency are not of equal value, so totals across
                    the two currencies must not be added up. Older ledgers lack this column; read them as main currency.

                    Primary Type: what the UI colours a Pokemon name by — form-aware (Alolan Vulpix reads as Ice);
                    empty for items and older ledger rows. The Species/Item column holds a species id for Pokemon
                    (namespaced, e.g. `cobblemon:pikachu`; older ledgers carry the bare name with no namespace)
                    and an item id for items.

                    Source: market = player-to-player purchase, shop = a shop (the main RP Shop or a
                    custom NPC shop). **Claim records are also shop**: the Type column says "Claimed",
                    the price is 0, and it is not a sale. For shop sales the Seller column holds the shop
                    name (empty for the main RP Shop), and the person who stocked the shop does not get the
                    sale in their personal history. Shop Name / Shop ID are only set for shop records
                    (Shop ID is used for per-shop filtering and survives renames). Older ledgers lack these
                    columns; read them as market / empty.

                    Types: Listed (market/auction listing), Order (buy order placed; the buyer fills the seller column),
                           Sold (market/auction sale, buy order delivery accepted),
                           Cancelled (closed by owner/expired/unsold/admin-forced; the Details reason tells which),
                           Returned (pending claim returned)

                    Details column ("|"-separated; segments omitted when not applicable):
                    Pokémon: lv=level|shiny=Y/N|ivs=six IVs|evs=six EVs|ht=hyper-trained stats
                             |nature=effective nature|base=base nature (only when a mint was used)
                             |ability=ability|gender=gender|ball=ball|held=held item id
                             |heldNbt=full held-item NBT (enchantments / custom name; omitted when it has no components)
                             |form=form|name=nickname|size=size (XS/S/M/L/XL/ALPHA)
                             |gmax=Y/N (Gigantamax)|dmax=Dynamax Level|ot=Original Trainer|tera=Tera type
                             |friend=friendship|moves=four move slots (comma-separated, empty when unused)
                             |marks=marks (comma-separated, 10 per line)
                    Item: count=amount|ench=enchantment_id:level,comma-separated|nbt={full NBT text}

                    NOTE: with many marks (up to ~200 on one Pokemon) the marks list wraps
                    INSIDE the same cell (10 per line) — turn on "wrap text" in Excel to see it all,
                    and parse the CSV properly in scripts (a newline inside quotes still belongs to
                    the same field; never split on line breaks).
                    NOTE: the Pokemon segment is now read from the Pokemon's FULL data, so trades
                    recorded before this update are filled in as well (nickname / size / Dynamax /
                    Original Trainer, which were previously not recorded at all).
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
        // ⚠ 币种、主属性、来源、商店名、商店ID 五列都加在**末尾**：老 CSV 没有这些列，
        //   读的时候按「缺列 = 主货币 / 无色 / 市场 / 空店名 / 空ID」容错
        //   来源：market = 玩家间市场直购、shop = 商店（主 RP 商城 / 自定义 NPC 商店）——
        //   **领取记录也是 shop**（类型列是 Claimed，不是 Sold）；
        //   商店售出的卖家列是店名（主商城为空），上架者不会因此进个人交易历史（2026-10-26）
        return "$time,$type,$category,$seller,$buyer,$sellerUuid,$buyerUuid,$species," +
            "${record.price},${record.fee},${csvEscape(record.detail)},${record.currency.name}," +
            "${csvEscape(record.primaryType)},${if (record.fromShop) "shop" else "market"}," +
            "${csvEscape(record.shopName)},${csvEscape(record.shopId)}"
    }

    private fun typeName(type: TransactionType, lang: String): String = when (type) {
        TransactionType.ADD -> if (lang == "zh_cn") "上架" else "Listed"
        TransactionType.PURCHASE -> if (lang == "zh_cn") "卖出" else "Sold"
        TransactionType.CANCEL -> if (lang == "zh_cn") "下架" else "Cancelled"
        TransactionType.RETURN -> if (lang == "zh_cn") "退还" else "Returned"
        TransactionType.ORDER -> if (lang == "zh_cn") "求购" else "Order"
        TransactionType.CLAIM -> if (lang == "zh_cn") "领取" else "Claimed"
    }

    /**
     * 物种翻译 key（`cobblemon.species.pikachu.name`）→ 账本里的紧凑写法（`cobblemon:pikachu`）。
     *
     * ⚠ **命名空间必须留着**：附属模组的物种不在 `cobblemon` 命名空间下，剥掉之后回读补不回来
     *（旧写法丢掉命名空间、回读硬补 `cobblemon.` ⇒ 附属模组物种在历史界面显示成错误的 key）。
     * 不含 `.species.` 的值（物品 id、老账本的裸物种名）一律原样返回。
     */
    private fun speciesDisplay(key: String): String {
        val marker = ".species."
        val idx = key.indexOf(marker)
        if (idx < 0) return key
        val namespace = key.substring(0, idx)
        val path = key.substring(idx + marker.length).removeSuffix(".name")
        return if (namespace.isEmpty()) path else "$namespace:$path"
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

    /**
     * 把整份 CSV 文本切成**一条条记录**（每条 = 一份字段表）。
     *
     * ⚠⚠ **为什么不能按行切**：详情列里的**证章是每 10 个换一行**的（一只精灵最多近 200 个证章，
     *   见 [RecordDetail]），写入端会给整个字段加引号包裹、内嵌真实换行 ——
     *   按 `\n` 切会把**一条交易拆成好几条**，回读（个人交易历史补老记录）立刻错位。
     *   RFC 4180 里引号内的换行本来就属于同一个字段。
     */
    private fun parseCsvRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val fields = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && inQuotes && i + 1 < text.length && text[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { fields.add(cur.toString()); cur.setLength(0) }
                (c == '\n' || c == '\r') && !inQuotes -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++  // \r\n 当一次
                    fields.add(cur.toString())
                    cur.setLength(0)
                    if (fields.any { it.isNotBlank() }) records.add(fields.toList())
                    fields.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        // 收尾：文件末尾没有换行时最后一条也要收进来
        if (cur.isNotEmpty() || fields.isNotEmpty()) {
            fields.add(cur.toString())
            if (fields.any { it.isNotBlank() }) records.add(fields.toList())
        }
        return records
    }

    /** 英文 CSV 类型名 → 枚举（回读固定读 en 文件，类型名不随语言变） */
    private fun typeFromCsv(name: String): TransactionType? = when (name) {
        "Listed" -> TransactionType.ADD
        "Sold" -> TransactionType.PURCHASE
        "Cancelled" -> TransactionType.CANCEL
        "Returned" -> TransactionType.RETURN
        "Order" -> TransactionType.ORDER
        "Claimed" -> TransactionType.CLAIM
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
                // ⚠ **按记录读、不按行读** —— 详情列里证章每 10 个换一行（见 RecordDetail），
                //   引号内的换行属于同一条记录，按行切会把一条交易拆成几条
                parseCsvRecords(file.readText()).forEach { fields ->
                    if (fields.size < 7 || fields[0].startsWith("Time")) return@forEach
                    val type = typeFromCsv(fields[1]) ?: return@forEach
                    // 新格式（含 UUID 列）字段序：0时间 1类型 2分类 3卖家 4买家 5卖家UUID 6买家UUID 7物种 8价格 9手续费 10详情
                    // 旧格式：0时间 1类型 2分类 3卖家 4买家 5物种 6价格 7手续费 8详情
                    val hasUuid = fields.size >= 10
                    val seller = fields[3].removePrefix("'")
                    val buyer = fields[4].removePrefix("'")
                    val sellerUuid = if (hasUuid) runCatching { java.util.UUID.fromString(fields[5]) }.getOrNull() else null
                    val buyerUuid = if (hasUuid) fields[6].takeIf { it.isNotEmpty() }?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() } else null
                    // ⚠ CSV 里精灵存的是**紧凑物种 id**（`cobblemon:pikachu`，见 speciesDisplay），
                    //   要补回翻译 key 才能在界面上显示本地化名字（物品存的就是物品 id，原样）。
                    //   三种历史写法都要认：新格式（带命名空间）、老账本的裸名、本就是完整 key 的
                    val speciesRaw = fields[if (hasUuid) 7 else 5].removePrefix("'")
                    val species = when {
                        fields[2] == "ITEM" -> speciesRaw
                        speciesRaw.contains(".species.") -> speciesRaw
                        // 带命名空间 ⇒ 走与商城写入同一个入口（解析得到就用物种自己的 key，
                        // 解析不到就按该命名空间拼）—— 附属模组的物种靠这一步保住命名空间
                        speciesRaw.contains(':') -> SpeciesText.translationKeyOf(speciesRaw)
                        // 老账本：裸物种名，没有命名空间可依，只能按 Cobblemon 本体补
                        else -> "cobblemon.species.$speciesRaw.name"
                    }
                    val price = fields[if (hasUuid) 8 else 6].toIntOrNull() ?: return@forEach
                    // 主属性列：⚠ 列号**以本分支的表头为准**（1.3.0 多一列「币种」⇒ 主属性是第 13 列，
                    //   1.2.x 是第 12 列）。合并上浮时这里最容易出错 ——
                    //   表头 / 写入 / 回读**三处列序必须一一对应**（gui_pitfalls 记过这个坑）
                    val primaryType = if (fields.size > 12) fields[12].removePrefix("'") else ""
                    if (playerUuid != null) {
                        // UUID 匹配优先（新行）；旧行无 UUID 回退名字匹配
                        val uuidMatch = sellerUuid == playerUuid || buyerUuid == playerUuid
                        val nameMatch = playerName != null && (seller == playerName || buyer == playerName)
                        val nameFallbackAllowed = sellerUuid == null && (buyerUuid == null || buyer.isEmpty())
                        if (!uuidMatch && !(nameFallbackAllowed && nameMatch)) return@forEach
                    }
                    val timestamp = try {
                        java.time.LocalDateTime.parse(fields[0], timeFormat).atZone(zone).toInstant().toEpochMilli()
                    } catch (e: Exception) {
                        return@forEach
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
                        // 详情列读回来（**只为了里面的 rarity= 段** —— 历史界面物品名上色用，
                        // 见 RecordDetail.rarityFromDetail）。⚠ 它**不会**被发进 payload：
                        // 服务端只提取那一个短字符串，包不会因此变大。
                        // 索引：新格式（含 UUID 列）第 10 列、旧格式第 8 列 —— 老 CSV 里本就没有
                        // rarity 段 ⇒ 提取到空串 ⇒ 界面按 itemId 取色（与改动前一致）
                        detail = fields.getOrNull(if (hasUuid) 10 else 8)?.removePrefix("'").orEmpty(),
                        // 币种第 12 列、主属性第 13 列、来源第 14 列、商店名第 15 列、商店ID 第 16 列：
                        // 老 CSV 没这几列 ⇒ 落主货币 / 空串 / market（普通市场交易）/ 空店名 / 空ID
                        currency = MarketCurrency.fromStored(fields.getOrNull(11)),
                        primaryType = primaryType,
                        fromShop = fields.getOrNull(13)?.removePrefix("'") == "shop",
                        shopName = fields.getOrNull(14)?.removePrefix("'").orEmpty(),
                        shopId = fields.getOrNull(15)?.removePrefix("'").orEmpty()
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
