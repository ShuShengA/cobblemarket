package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.market.OfflineMessageState
import com.shusheng.cobblemarket.network.sendSound
import com.shusheng.cobblemarket.util.PersistHelper
import net.minecraft.server.MinecraftServer
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID

/** 充值订单的状态流转失败原因（调用方翻成玩家语言的提示） */
enum class PointShopError {
    /** 游戏内充值未开启（总开关或充值开关关着） */
    DISABLED,
    /** 档位不存在（服主改过配置 / 客户端拿了旧档位列表） */
    NO_TIER,
    /** 已有未完结订单（一人同时只允许一笔） */
    ALREADY_ACTIVE,
    /** 未完结订单太多（见 [PointShopService.MAX_ACTIVE_ORDERS]） */
    TOO_MANY_ACTIVE,
    NOT_FOUND,
    /** 不是自己的订单 */
    NOT_OWNER,
    /** 当前状态不允许该操作（含**重复确认**——幂等闸门就靠它） */
    BAD_STATUS,
    /** 待支付已超时太久 */
    EXPIRED,
    /** 订单号/尾数连续重抽超限（活跃订单占满 99 个尾数，实际不可能） */
    GENERATE_FAILED
}

sealed class PointShopResult {
    data class Success(val order: PointOrder) : PointShopResult()
    data class Failure(val error: PointShopError) : PointShopResult()
}

/**
 * **游戏内充值的状态机与发放内核**（方案「RP 充值」）。
 *
 * 铁律（涉及真钱，逐条照做）：
 * - **模组不看钱、不碰钱、不做支付通道**：收款码是服主的，资金进服主自己的账户
 * - **模组无法判断支付是否成功，也不假装能判断**：玩家点「我已支付」只是**声明不是证据**
 * - **失败方向必须是对的**：未确认一律不发放 —— 宁可漏发（服主看列表补），不能滥发
 *
 * ⚠ 命令与界面**共用本类**，不许各自实现一套发放逻辑（方案风险清单第 12 条：
 * 发放路径分叉会立刻长出两套幂等与审计）。
 */
object PointShopService {

    /**
     * 待支付超时后的**宽限期**：倒计时归零只是「本该付完了」，不代表玩家没付 ——
     * 他可能已经扫了码、只是回来点得慢。宽限期内仍接受「我已支付」（转入待确认，由服主核对），
     * 超出则作废。**作废是安全的失败方向**（漏发可补），误判成「已付款」才危险。
     */
    private const val PAYMENT_GRACE_MS = 30 * 60 * 1000L

    /** 已完结订单保留期：服主要拿它跟收款账单逐笔对账，不能一完结就删 */
    private const val KEEP_FINISHED_DAYS = 90L

    /** 扫描节流：超时作废与清理都不急，60 秒一次足够 */
    private const val SCAN_INTERVAL_MS = 60_000L

    /**
     * 同时允许几笔未完结订单。
     * 取 3：够玩家"下了 5 元单又改主意想充 10 元"（2026-09-18 用户指出一笔太死），
     * 又不至于让一个人把 99 个**金额尾数**占满（尾数是服主对账的键）。
     */
    private const val MAX_ACTIVE_ORDERS = 3

    private var lastScanAt = 0L

    /** 单机切换存档时复位扫描节流（静态状态不跨世界，见 PersistHelper.reset） */
    fun reset() {
        lastScanAt = 0L
    }

    // ── 玩家侧 ──

    /**
     * 创建订单（一人同时只允许一笔未完结订单）。
     *
     * 限制的原因有二：金额尾数只有 99 个（活跃订单不重复，是服主核对账单的键），
     * 以及防「一个人连点下单把尾数占满」。
     */
    fun createOrder(
        server: MinecraftServer,
        playerUuid: UUID,
        playerName: String,
        tierIndex: Int
    ): PointShopResult {
        if (!CobbleMarketConfig.pointsEnabled || !CobbleMarketConfig.pointsShopEnabled) {
            return PointShopResult.Failure(PointShopError.DISABLED)
        }
        val tier = CobbleMarketConfig.pointsShopTiers.getOrNull(tierIndex)
            ?: return PointShopResult.Failure(PointShopError.NO_TIER)
        val state = PointOrderState.get(server)
        val now = System.currentTimeMillis()
        // 顺手清掉自己超时已久的旧单，免得它把玩家挡在「已有未完结订单」外面
        state.voidExpired(now, PAYMENT_GRACE_MS)
        if (state.activeOrdersOf(playerUuid).size >= MAX_ACTIVE_ORDERS) {
            return PointShopResult.Failure(PointShopError.TOO_MANY_ACTIVE)
        }
        val order = state.create(
            playerUuid = playerUuid,
            playerName = playerName,
            tierName = tier.name,
            baseCents = tier.priceCents,
            points = tier.points,
            timeoutMinutes = CobbleMarketConfig.pointsShopOrderTimeoutMinutes,
            // 该档配了专用码就用它，否则回退到全局那张（两者都为空 = 没码可展示）
            qrFile = tier.qr.ifBlank { CobbleMarketConfig.pointsShopPayQrImage },
            // 专用码是"带金额"的固定码 ⇒ 金额写死，**不能加尾数**；走全局码才加尾数供服主对账
            tailEnabled = tier.qr.isBlank(),
            now = now
        ) ?: return PointShopResult.Failure(PointShopError.GENERATE_FAILED)
        PointOrderLogger.log(PointOrderLogType.CREATE, order, playerName, "档位 ${tier.name}")
        PersistHelper.requestSave(server)
        return PointShopResult.Success(order)
    }

    /**
     * 玩家声明「我已支付」：待支付 → 待确认。**只是登记声明，一分钱都不发** ——
     * 发不发由服主对着收款账单说了算。
     */
    fun markPaid(server: MinecraftServer, playerUuid: UUID, orderNo: String): PointShopResult {
        val state = PointOrderState.get(server)
        val order = state.find(orderNo) ?: return PointShopResult.Failure(PointShopError.NOT_FOUND)
        if (order.playerUuid != playerUuid) return PointShopResult.Failure(PointShopError.NOT_OWNER)
        val now = System.currentTimeMillis()
        // 同 PointOrderState.voidExpired：用减法避免 expireAt 为 Long.MAX_VALUE 时加法溢出
        if (order.status == PointOrderStatus.PENDING_PAYMENT && now - PAYMENT_GRACE_MS > order.expireAt) {
            state.markVoid(order, PointOrderVoidReason.TIMEOUT, null, now)
            PointOrderLogger.log(PointOrderLogType.VOID_TIMEOUT, order, "服务器（超时）")
            PersistHelper.requestSave(server)
            return PointShopResult.Failure(PointShopError.EXPIRED)
        }
        if (order.status != PointOrderStatus.PENDING_PAYMENT) {
            return PointShopResult.Failure(PointShopError.BAD_STATUS)
        }
        state.markPaid(order, now)
        PointOrderLogger.log(PointOrderLogType.PAID, order, order.playerName)
        PersistHelper.requestSave(server)
        notifyOperatorsNewOrder(server, order)
        return PointShopResult.Success(order)
    }

    /**
     * 提醒**在线 OP**：有人提交了待确认的充值订单。
     *
     * 这是整条充值链路上**唯一必须人肉处理**的环节 —— 个人收款码没有支付回调，模组也判断不了
     * 玩家到底付没付，所以玩家点完「我已支付」就只能等。不主动提醒的话，服主想不起来去翻列表，
     * 玩家就一直干等（2026-09-18 用户实测：OP 侧毫无提示）。
     *
     * ⚠ 与「到账通知」同一个原则：**涉及钱的事必须让该知道的人知道**。管理面板那个红点是
     * 「进来才看得到」，聊天栏这条是「不用进来也能看到」，两条都要有。
     */
    private fun notifyOperatorsNewOrder(server: MinecraftServer, order: PointOrder) {
        val msg = Text.translatable(
            "cobblemarket.points.shop.new_order",
            order.playerName,
            order.tierName,
            // ⚠ 带服主自定义的货币单位（与客户端弹窗/列表里的写法一致）——
            //   光给个数字，服主对着账单还得自己猜这是元还是美元
            moneyLabel(order.payCents),
            PointOrderNumbers.format(order.orderNo)
        ).formatted(Formatting.GOLD)
        server.playerManager.playerList
            .filter { it.hasPermissionLevel(2) }
            .forEach { it.sendMessage(msg, false) }
    }

    /** 玩家取消自己的订单（未完结的都能取消；取消不发放、不影响已发的） */
    fun cancelOrder(server: MinecraftServer, playerUuid: UUID, orderNo: String): PointShopResult {
        val state = PointOrderState.get(server)
        val order = state.find(orderNo) ?: return PointShopResult.Failure(PointShopError.NOT_FOUND)
        if (order.playerUuid != playerUuid) return PointShopResult.Failure(PointShopError.NOT_OWNER)
        if (!order.isActive()) return PointShopResult.Failure(PointShopError.BAD_STATUS)
        state.markVoid(order, PointOrderVoidReason.PLAYER_CANCEL, null, System.currentTimeMillis())
        PointOrderLogger.log(PointOrderLogType.VOID_PLAYER, order, order.playerName)
        PersistHelper.requestSave(server)
        return PointShopResult.Success(order)
    }

    // ── 服主侧 ──

    /**
     * **确认发放**（服主核对收款账单后点）。
     *
     * ⚠ 幂等：只有「待确认」能发放 —— 重复点、界面重发、崩溃后重新打开列表再点，
     * 都不会第二次加余额。
     *
     * ⚠ 顺序铁律（同兑换码的「先扣次数再加余额」）：**先把订单标成已发放（并发起落盘），
     * 再加余额**。极端崩溃窗口下最坏是「状态没落盘、钱也没加」——服主重新确认即可；
     * 反过来先加余额再标状态的话，崩溃后订单仍是待确认，服主再点一次就是**凭空多给一份**。
     */
    fun confirm(server: MinecraftServer, operatorName: String, orderNo: String): PointShopResult {
        val state = PointOrderState.get(server)
        val order = state.find(orderNo) ?: return PointShopResult.Failure(PointShopError.NOT_FOUND)
        if (order.status != PointOrderStatus.PENDING_CONFIRM) {
            return PointShopResult.Failure(PointShopError.BAD_STATUS)
        }
        val now = System.currentTimeMillis()
        state.markGranted(order, operatorName, now)
        PointOrderLogger.log(PointOrderLogType.GRANT, order, operatorName)
        PersistHelper.requestSave(server)
        val after = PointsState.get(server).addBalance(order.playerUuid, order.points)
        PointsAuditLogger.log(
            PointsLogType.SHOP_CONFIRM, order.playerUuid, order.playerName, order.points, after, operatorName,
            "充值发放：订单 ${order.orderNo}（档位 ${order.tierName}，实付 ${formatCents(order.payCents)}）",
            "Top-up: order ${order.orderNo} (tier ${order.tierName}, paid ${formatCents(order.payCents)})"
        )
        // 到账音效：**只给在线玩家发** —— 离线没法播音（消息已经入队，上线还能看到）。
        // 直接复用市场的「钱到账」音：语义一模一样（钱进来了），也省得再引入一个音效文件
        server.playerManager.getPlayer(order.playerUuid)?.let { sendSound(it, "points_in") }
        // 到账通知：在线直接发，离线入队（玩家付了钱，必须让他知道到账了）
        OfflineMessageState.notify(
            server, order.playerUuid,
            Text.translatable(
                "cobblemarket.points.shop.granted",
                CurrencyService.amountText(order.points, MarketCurrency.POINTS),
                PointOrderNumbers.format(order.orderNo)
            ).formatted(Formatting.GREEN)
        )
        return PointShopResult.Success(order)
    }

    /**
     * 服主作废订单（核对账单后没这笔钱、玩家重复提交、误操作等）。
     * 已发放的订单**不能**走这里（那要用 `/market points take` 单独回收并留审计）。
     */
    fun reject(server: MinecraftServer, operatorName: String, orderNo: String): PointShopResult {
        val state = PointOrderState.get(server)
        val order = state.find(orderNo) ?: return PointShopResult.Failure(PointShopError.NOT_FOUND)
        if (!order.isActive()) return PointShopResult.Failure(PointShopError.BAD_STATUS)
        state.markVoid(order, PointOrderVoidReason.OPERATOR, operatorName, System.currentTimeMillis())
        PointOrderLogger.log(PointOrderLogType.VOID_OPERATOR, order, operatorName)
        PersistHelper.requestSave(server)
        OfflineMessageState.notify(
            server, order.playerUuid,
            Text.translatable("cobblemarket.points.shop.rejected", PointOrderNumbers.format(order.orderNo))
                .formatted(Formatting.RED)
        )
        return PointShopResult.Success(order)
    }

    /** 手动清理已完结订单（保留期是 [KEEP_FINISHED_DAYS] 天，正常由 tick 自动做） */
    fun purgeFinished(server: MinecraftServer): Int {
        val removed = PointOrderState.get(server).purgeFinished(System.currentTimeMillis(), KEEP_FINISHED_DAYS)
        if (removed > 0) PersistHelper.requestSave(server)
        return removed
    }

    /**
     * 定时维护（挂在服务端 tick 上，内部 60 秒节流）：
     * 超时未支付且过了宽限期的订单作废 + 已完结订单到期清理。
     */
    fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        if (now - lastScanAt < SCAN_INTERVAL_MS) return
        lastScanAt = now
        server.execute {
            val state = PointOrderState.get(server)
            val voided = state.voidExpired(now, PAYMENT_GRACE_MS)
            val purged = state.purgeFinished(now, KEEP_FINISHED_DAYS)
            if (voided > 0 || purged > 0) {
                // 表结构真变了才请求落盘：无脑调用会让 saveAll 空跑，mtime 校验误报「保存失败」（见 PersistHelper）
                PersistHelper.requestSave(server)
                CobbleMarket.LOGGER.info(
                    "Point orders: {} expired voided, {} finished purged", voided, purged
                )
            }
        }
    }

    /**
     * 分 → 展示串（`537` → `5.37`）。
     * ⚠ **不写死货币名**：档位金额是服主自己货币的展示值（元/美元/欧元都行），模组不参与结算。
     */
    fun formatCents(cents: Long): String = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

    /**
     * 金额 + 服主自定义的**货币单位**（`pointsShop.currencyLabel`，留空就只给数字）。
     * 玩家侧界面（弹窗、订单列表）早就是这么写的，服务端发出去的消息也得跟上 ——
     * 不然服主在聊天栏看到「实付 5.37」，还得自己回想这是哪国货币。
     */
    fun moneyLabel(cents: Long): String {
        val label = CobbleMarketConfig.pointsShopCurrencyLabel
        return if (label.isEmpty()) formatCents(cents) else "${formatCents(cents)} $label"
    }
}
