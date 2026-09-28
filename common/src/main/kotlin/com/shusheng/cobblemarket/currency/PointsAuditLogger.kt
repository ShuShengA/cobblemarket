package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.platform.configDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import net.minecraft.server.MinecraftServer

/**
 * RP 变动类型（审计账本的类型列）。
 * GIVE/TAKE = 命令发放与回收；REDEEM = 兑换码；MARKET_* = **玩家间交易**的消费与收入；
 * STORE_BUY = **RP 商城购买**（RP 直接销毁，没有对应的收入方）；SHOP_CONFIRM = 充值订单确认发放。
 */
enum class PointsLogType { GIVE, TAKE, REDEEM, MARKET_SPEND, MARKET_INCOME, STORE_BUY, SHOP_CONFIRM, CODE_CREATE, CODE_REVOKE }

/**
 * **RP 审计账本**（`config/cobblemarket/points/`）。
 *
 * ⚠ RP 的来源由服主决定（可能是玩家花真钱买的、也可能是当奖励发的），两种都**必须可对账** ——
 * 发放 / 回收 / 兑换 / 市场消费与收入全量落 CSV，服主能拿它跟自己的收款记录逐笔核对。
 *
 * 照 [com.shusheng.cobblemarket.finance.CreditFileLogger] 的模式：中英双份 + README + CSV 注入防护
 * + 按天分文件 + 旧头保护；同步追加写盘（不受服务器崩溃 / 杀进程影响，只写不改）。
 */
object PointsAuditLogger {

    private val DANGEROUS_PREFIXES = setOf('=', '+', '-', '@')

    // DateTimeFormatter 线程安全（SimpleDateFormat 不是），系统时区与其余账本一致
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private var currentDate = ""
    private var currentZh: File? = null
    private var currentEn: File? = null

    /**
     * 记一笔RP 变动。
     *
     * @param amount 本次变动量（**恒为正数**，方向由 [type] 表达）
     * @param balanceAfter 变动后的余额 —— 对账时可直接核出「是不是每一分都对得上」
     * @param operatorName 操作者：玩家自己操作写玩家名，服主/系统写对应名字（如「服务器」）
     */
    fun log(
        type: PointsLogType,
        targetUuid: UUID,
        targetName: String,
        amount: Long,
        balanceAfter: Long,
        operatorName: String,
        detail: String,
        detailEn: String = detail,
        timestamp: Long = System.currentTimeMillis()
    ) {
        try {
            refreshFiles(timestamp)
            val zhFile = currentZh ?: return
            val enFile = currentEn ?: return
            Files.writeString(
                zhFile.toPath(),
                buildLine(type, targetUuid, targetName, amount, balanceAfter, operatorName, detail, timestamp, true) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
            Files.writeString(
                enFile.toPath(),
                buildLine(type, targetUuid, targetName, amount, balanceAfter, operatorName, detailEn, timestamp, false) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
        } catch (e: Exception) {
            // 审计写不进去绝不能影响交易本身（钱已经动了，这里只记日志）
            CobbleMarket.LOGGER.warn("Failed to write points audit log: {}", e.message)
        }
    }

    /**
     * 玩家间 RP 交易的**成对**落账：买家一笔「市场消费」、卖家一笔「市场收入」。
     *
     * 四条业务线共用（精灵市场 / 物品市场 / 拍卖成交 / 求购交付），一律在**钱动完之后**调 ——
     * 本函数只读余额、不再动钱，所以 `balanceAfter` 是落账那一刻的真实余额。
     *
     * ⚠ 两侧金额**可以不等**：差额是销毁的手续费（拍卖 / 求购有，两个市场没有）。这不是错账，
     * 但调用方**必须把手续费写进 [detail]**，否则服主从账本上看不出这笔钱销毁了多少。
     * ⚠ 拍卖与求购的钱在下单 / 出价时就冻结了，成交这一刻买家余额并不变 —— 这两行的
     * `balanceAfter` 是**落账时刻**的余额，不是扣款时刻的（方案「按交易事件落账」）。
     */
    fun logMarketTrade(
        server: MinecraftServer,
        buyerUuid: UUID,
        buyerName: String,
        sellerUuid: UUID,
        sellerName: String,
        buyerPaid: Long,
        sellerGot: Long,
        detail: String,
        detailEn: String
    ) {
        val points = PointsState.get(server)
        log(
            PointsLogType.MARKET_SPEND, buyerUuid, buyerName, buyerPaid,
            points.getBalance(buyerUuid), sellerName, detail, detailEn
        )
        log(
            PointsLogType.MARKET_INCOME, sellerUuid, sellerName, sellerGot,
            points.getBalance(sellerUuid), buyerName, detail, detailEn
        )
    }

    private fun refreshFiles(timestamp: Long) {
        val date = dateFormat.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
        if (date == currentDate) return
        currentDate = date
        currentZh = resolveFile(date, "zh_cn", "时间,类型,玩家,玩家UUID,变动量,变动后余额,操作者,详情")
        currentEn = resolveFile(date, "en_us", "Time,Type,Player,Player UUID,Amount,Balance After,Operator,Details")
    }

    private fun resolveFile(date: String, lang: String, header: String): File {
        val dir = configDir().resolve("cobblemarket/points").toFile()
        dir.mkdirs()
        ensureReadme(dir)
        val file = File(dir, "points_audit_${date}_${lang}.csv")
        if (file.exists()) {
            val firstLine = file.useLines { it.firstOrNull() } ?: ""
            if (firstLine.isNotEmpty() && firstLine.trim() != header.trim()) {
                val legacy = File(dir, "points_audit_${date}_${lang}_legacy_${System.currentTimeMillis()}.csv")
                file.renameTo(legacy)
                CobbleMarket.LOGGER.info("CobbleMarket points file {} has old header; preserved as {}", file.name, legacy.name)
            }
        }
        if (!file.exists()) file.writeText(header + "\n")
        return file
    }

    /** points 目录说明（只写一次，不覆盖服主修改） */
    private fun ensureReadme(dir: File) {
        val zh = File(dir, "README_zh_cn.txt")
        if (!zh.exists()) {
            try {
                zh.writeText(
                    """
                    CobbleMarket RP 审计账本目录说明
                    ==================================

                    本目录下的 CSV 是RP 审计账本（每一次RP 变动同步追加写盘，不受崩溃/杀进程影响，只写不改）。

                    列：时间, 类型, 玩家, 玩家UUID, 变动量, 变动后余额, 操作者, 详情
                      类型：发放（服主命令给的）、回收（服主命令收回的）、兑换（兑换码）、
                            市场消费（买精灵/物品/拍卖/求购花掉的）、市场收入（卖出赚到的）、
                            充值发放（游戏内充值订单经服主确认后发放的）

                    ⚠ RP 的获取途径由服主决定（当奖励发、当充值货币卖，或两者并用），本账本是**对账依据**：
                    若你用充值发放 RP，应定期拿它和自己的收款记录核对，确认每一笔发放都有对应的收款。

                    变动量恒为正数，「进/出」方向看类型列；「变动后余额」可直接逐行核对。

                    ⚠ 拍卖与求购的钱在下单 / 出价时就已扣走，成交那一刻买家余额并不变化 ——
                    这两类交易的「市场消费」行按**成交**落账，其「变动后余额」是落账时刻的余额，
                    不是当初扣款时刻的。流拍 / 被超价的资金退回不产生账目行。
                    """.trimIndent()
                )
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Failed to write points readme: {}", e.message)
            }
        }
        val en = File(dir, "README_en_us.txt")
        if (!en.exists()) {
            try {
                en.writeText(
                    """
                    CobbleMarket RP Audit Ledger Directory
                    ===========================================

                    The CSVs here are the RP audit ledger (every RP change is appended synchronously
                    to disk, unaffected by server crashes or forced kills; append-only).

                    Columns: Time, Type, Player, Player UUID, Amount, Balance After, Operator, Details
                      Types: Give (by admin command), Take (reclaimed by admin command), Redeem (code),
                             Market spend (bought a Pokémon/item/auction/buy order), Market income (sold),
                             Shop grant (in-game purchase order confirmed by the owner)

                    ⚠ Where RP comes from is up to you (handed out as a reward, sold as a top-up, or both).
                    This ledger is the reconciliation source of truth: if you sell RP, check it against your
                    own payment records regularly to make sure every grant corresponds to a payment you received.

                    Amount is always positive; the direction is in the type column. "Balance After" lets
                    you verify row by row.

                    Note: auction and buy-order funds are taken when a bid / order is placed, so the
                    buyer's balance does not change at settlement time. Those "Market spend" rows are
                    written at settlement, and "Balance After" is the balance at that moment rather than
                    at the original deduction. Outbid refunds and expired orders leave no rows.
                    """.trimIndent()
                )
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Failed to write points readme: {}", e.message)
            }
        }
    }

    private fun buildLine(
        type: PointsLogType,
        targetUuid: UUID,
        targetName: String,
        amount: Long,
        balanceAfter: Long,
        operatorName: String,
        detail: String,
        timestamp: Long,
        zh: Boolean
    ): String {
        val time = timeFormat.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
        val typeName = when (type) {
            PointsLogType.GIVE -> if (zh) "发放" else "Give"
            PointsLogType.TAKE -> if (zh) "回收" else "Take"
            PointsLogType.REDEEM -> if (zh) "兑换" else "Redeem"
            PointsLogType.MARKET_SPEND -> if (zh) "市场消费" else "Market spend"
            PointsLogType.MARKET_INCOME -> if (zh) "市场收入" else "Market income"
            PointsLogType.STORE_BUY -> if (zh) "商城消费" else "Store purchase"
            PointsLogType.SHOP_CONFIRM -> if (zh) "充值发放" else "Shop grant"
            // 生成/作废兑换码本身**不改变任何余额**（余额列写 0），单独类型免得污染"发放"口径
            PointsLogType.CODE_CREATE -> if (zh) "生成兑换码" else "Code created"
            PointsLogType.CODE_REVOKE -> if (zh) "作废兑换码" else "Code revoked"
        }
        // 玩家 UUID 供程序按 UUID 对账（改名玩家也查得到）；人读账本看玩家名
        return "$time,$typeName,${csvEscape(targetName)},$targetUuid,$amount,$balanceAfter," +
            "${csvEscape(operatorName)},${csvEscape(detail)}"
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
