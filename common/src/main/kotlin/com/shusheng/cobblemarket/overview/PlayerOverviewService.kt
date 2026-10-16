package com.shusheng.cobblemarket.overview

import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.config.CurrencyHandler
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.PointsState
import com.shusheng.cobblemarket.finance.FinanceService
import com.shusheng.cobblemarket.finance.FinanceState
import com.shusheng.cobblemarket.finance.LoanRecord
import com.shusheng.cobblemarket.finance.LoanStatus
import com.shusheng.cobblemarket.market.AuctionState
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.market.BuyOrderState
import com.shusheng.cobblemarket.market.ItemMarketState
import com.shusheng.cobblemarket.market.MarketState
import com.shusheng.cobblemarket.platform.cobecoAvailable
import com.shusheng.cobblemarket.platform.cobecoGetBalance
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID

/**
 * 全服玩家总览的**服务端逻辑**：维护离线快照（本文件上部）+ 聚合全服数据（下部）。
 *
 * 快照维护的三条规则见 [PlayerSnapshotState]；这里只负责"什么时候记、从哪儿读"。
 */
object PlayerOverviewService {

    /** 兜底刷新间隔：5 分钟（只在服务端被强杀时才用得上 —— 正常下线有下线事件） */
    private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L

    /** 上次兜底刷新的时刻；0 = 还没刷过（开服后第一次 tick 就会刷一遍） */
    private var lastRefresh = 0L

    /**
     * **正常下线 → 记一次**（`onPlayerDisconnect`）。
     *
     * ⭐ 这一刻记下的余额就是他离线期间的真实值：玩家一下线，主货币余额在这个模组里
     * 就再也不会动了（能动钱的两个接口都收在线玩家对象，定时划扣也只扫在线的人）。
     */
    fun onPlayerDisconnect(player: ServerPlayerEntity) {
        recordSnapshot(player.server, player)
    }

    /**
     * 每 5 分钟给**在线的**刷一遍（兜服务端崩溃 / 被强杀 —— 那种情况没有下线事件）。
     * 由 `onServerTickEnd` 调，内部节流；玩家一个都没有时直接返回。
     */
    fun tick(server: MinecraftServer) {
        val online = server.playerManager.playerList
        if (online.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastRefresh < REFRESH_INTERVAL_MS) return
        lastRefresh = now
        online.forEach { recordSnapshot(server, it) }
    }

    /**
     * 读一名**在线**玩家的余额与图鉴，记进快照（没变的话 [PlayerSnapshotState.record] 不写盘）。
     *
     * ⚠ 余额读失败**不抛**、记 0：货币后端抽风不该把整个 tick 打断
     * （读不出来的值和"真的是 0"分不开，但快照本就是尽力而为的兜底）。
     */
    private fun recordSnapshot(server: MinecraftServer, player: ServerPlayerEntity) {
        val balance = runCatching { CurrencyHandler.getBalance(player).toLong() }.getOrDefault(0L)
        val dex = FinanceService.getDexCounts(server, player.uuid)
        PlayerSnapshotState.get(server).record(
            player.uuid, balance, dex.seen, dex.caught, System.currentTimeMillis()
        )
    }

    // ── 聚合：一次算出全服的行（界面打开时调一次、全量下发；搜索与排序在客户端本地做）──

    /**
     * ⚠ **三处性能坑，改这里之前先看**：
     * 1. "按人计数"一律**先一次遍历建索引**，别逐人调 `countActiveBySeller` 之类（那是 O(N×M)）
     * 2. 额度走 [FinanceState.creditOverviewFor]（内部先归一次贷款表）——
     *    逐人 `creditLimitFor` 每次都全表扫描贷款表
     * 3. 余额与图鉴：**在线的实时读**（别的模组改余额几乎必然发生在玩家在线时），
     *    **离线的读快照** —— 全表里只有这两个字段需要这样特殊处理
     */
    fun buildRows(server: MinecraftServer): List<PlayerOverviewRow> {
        val now = System.currentTimeMillis()
        val market = MarketState.get(server)
        val itemMarket = ItemMarketState.get(server)
        val auctions = AuctionState.get(server)
        val buyOrders = BuyOrderState.get(server)
        val finance = FinanceState.get(server)
        val bans = BanState.get(server)
        val snapshots = PlayerSnapshotState.get(server)
        // RP 余额（1.3.x 独有）：**一次性取出 state**，别在循环里逐人 `get(server)`
        // ⚠ **不看 RP 总开关** —— 用户 2026-10-17 拍板「0 也显示」（关着就是 0，照样画这一行）
        val pointsState = PointsState.get(server)

        val pokemonListingCount = countBy(market.getActiveListings()) { it.sellerUuid }
        val itemListingCount = countBy(itemMarket.getActiveListings()) { it.sellerUuid }
        val auctionCount = countBy(auctions.getActiveAuctions()) { it.sellerUuid }
        // ⚠ 1.3.x 的求购单**按币种分开**（`getOpenOrders(currency)` 必须传币种）——
        //   总览这一行要的是「这个人一共挂了几单」，所以**两种币种都算上**
        val buyOrderCount = countBy(MarketCurrency.entries.flatMap { buyOrders.getOpenOrders(it) }) { it.buyerUuid }
        val loansByPlayer = mutableMapOf<UUID, MutableList<LoanRecord>>()
        finance.getAllLoans().forEach { loansByPlayer.getOrPut(it.playerUuid) { mutableListOf() }.add(it) }

        // uuid 并集：在线玩家 + 快照 + 各表里有记录的人（市场 / 拍卖 / 求购 / 贷款 / 持卡 / 被调额 / 被封）
        val uuids = mutableSetOf<UUID>()
        server.playerManager.playerList.forEach { uuids += it.uuid }
        uuids += snapshots.allPlayers()
        uuids += pokemonListingCount.keys
        uuids += itemListingCount.keys
        uuids += auctionCount.keys
        uuids += buyOrderCount.keys
        uuids += loansByPlayer.keys
        uuids += finance.getAllPurpleCardHolders()
        uuids += finance.getAllBlackCardHolders()
        uuids += finance.getAdjustedPlayers().keys
        bans.getAllBans(now).forEach { uuids += it.playerUuid }

        val creditByPlayer = finance.creditOverviewFor(uuids, now)

        return uuids.map { uuid ->
            val player = server.playerManager.getPlayer(uuid)
            val online = player != null
            val snapshot = snapshots.snapshotOf(uuid)

            val balance = if (player != null) {
                runCatching { CurrencyHandler.getBalance(player).toLong() }.getOrNull()
            } else snapshot?.balance
            // PCO 余额：装了 Cobblemon Economy 才有这个概念（它收 UUID ⇒ 离线也准）；
            // ⚠ 主货币本来就是 PCO 时跳过 —— 那会跟上面那行重复显示同一个数
            val pco = if (cobecoAvailable() && CobbleMarketConfig.cobecoCurrency != "PCO") {
                runCatching { cobecoGetBalance(uuid, true)?.toLong() }.getOrNull()
            } else null
            val dex = if (player != null) FinanceService.getDexCounts(server, uuid) else null
            val dexSeen = dex?.seen ?: snapshot?.dexSeen
            val dexCaught = dex?.caught ?: snapshot?.dexCaught

            val loans = loansByPlayer[uuid].orEmpty()
            val (debt, creditLimit) = creditByPlayer[uuid] ?: (0L to 0L)
            val depositInterest = finance.depositInterest(uuid, now)
            val ban = bans.getBanInfo(uuid, now)

            PlayerOverviewRow(
                uuid = uuid,
                name = resolveName(server, uuid),
                online = online,
                pokemonListings = pokemonListingCount[uuid] ?: 0,
                itemListings = itemListingCount[uuid] ?: 0,
                activeAuctions = auctionCount[uuid] ?: 0,
                openBuyOrders = buyOrderCount[uuid] ?: 0,
                pendingPokemon = market.getPendingReturns(uuid).size,
                pendingItems = itemMarket.getPendingReturns(uuid).size,
                pendingBalance = market.getPendingBalance(uuid),
                balance = balance,
                pcoBalance = pco,
                fromSnapshot = !online,
                creditLimit = creditLimit,
                debt = debt,
                debtInterest = loans.sumOf { it.interestSince(now) },
                depositPrincipal = finance.getDepositBalance(uuid, now) - depositInterest,
                depositInterest = depositInterest,
                netDeposit = finance.netDepositBalance(uuid, now),
                overdue = loans.any { it.status == LoanStatus.OVERDUE },
                badDebt = loans.any { it.status == LoanStatus.BAD_DEBT },
                purpleCard = finance.isPurpleCardHolder(uuid),
                blackCard = finance.isBlackCardHolder(uuid),
                banned = ban != null,
                banUntil = ban?.expiresAt,
                loanBlocked = finance.isLoanBlocked(uuid, now),
                totalVolume = finance.getTotalCountedVolumeOf(uuid),
                dexSeen = dexSeen,
                dexCaught = dexCaught,
                // RP 余额：**永远实时**（不像主货币要分「在线实时 / 离线读快照」）
                // —— 它存在自己的 PersistentState 里，玩家离线期间没有任何东西会动它
                pointsBalance = pointsState.getBalance(uuid),
            )
        }
    }

    /**
     * 单人行 —— **ID 卡**用（玩家看自己）。
     *
     * ⚠ 有意直接筛 [buildRows] 的结果，而不是另写一条单人查询：这样 ID 卡与总览悬停窗的内容
     *   由**同一份代码**保证一致（方案要的就是"逐项一致"），也不会两份逻辑各自漂移。
     *   代价是开一次 ID 卡会算一遍全服（几毫秒、界面打开时一次）—— 这个量级无所谓。
     */
    fun buildRow(server: MinecraftServer, uuid: UUID): PlayerOverviewRow? =
        buildRows(server).firstOrNull { it.uuid == uuid }

    /** 按 uuid 计数（各表一次遍历用，避免逐人全表扫描） */
    private fun <T> countBy(items: Collection<T>, key: (T) -> UUID): Map<UUID, Int> {
        val m = mutableMapOf<UUID, Int>()
        items.forEach { m.merge(key(it), 1, Int::plus) }
        return m
    }

    /** 名字：在线表 → usercache → uuid 兜底（照 `/market card` 那条链路的三级 fallback） */
    private fun resolveName(server: MinecraftServer, uuid: UUID): String =
        server.playerManager.getPlayer(uuid)?.name?.string
            ?: server.userCache?.getByUuid(uuid)?.orElse(null)?.name
            ?: uuid.toString()
}
