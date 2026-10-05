package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.util.RequestThrottle
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 兑换结果（调用方各自翻成玩家语言的提示） */
sealed class RedeemOutcome {
    data class Success(val amount: Long, val balanceAfter: Long) : RedeemOutcome()

    /** 无效 / 已用尽 / 已过期 / 不属于他 / 重放 —— **一律同一个结果**：不给爆破者任何反馈 */
    object Invalid : RedeemOutcome()

    /** 失败次数过多，暂时锁定 */
    object Locked : RedeemOutcome()

    /** 请求过快（每次兑换的节流），静默丢弃 */
    object Throttled : RedeemOutcome()
}

/**
 * **兑换内核**：命令与界面**共用这一份**。
 *
 * 方案风险清单第 12 条：发放路径一分叉就会立刻长出两套幂等与审计 ——
 * 所以 GUI 不另写一遍逻辑，只是换个入口调这里。
 *
 * ⚠ 调用方必须保证在**服务端主线程**内调用（命令里包 `server.execute{}`，网络包 handler 同样）：
 * 「先扣次数再加余额」这两步不可被其它线程插进来。
 */
object PointRedeemService {

    /** 每次兑换的节流：防脚本连点（与防爆破是两回事，那个针对猜码） */
    private const val THROTTLE_MS = 3_000L

    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_FAILURES = 8
    private const val LOCK_MS = 10 * 60 * 1000L

    /** 玩家 → 最近失败时间戳（只留窗口内的）。内存态：防的是短时间暴力枚举，重启清空可接受 */
    private val failures = ConcurrentHashMap<UUID, MutableList<Long>>()

    /** 玩家 → 锁定到期时刻 */
    private val lockedUntil = ConcurrentHashMap<UUID, Long>()

    /** 玩家是否正被锁定；是则返回解锁时刻（调用方据此提示「稍后再试」） */
    fun lockedUntil(playerUuid: UUID, now: Long): Long? {
        val until = lockedUntil[playerUuid] ?: return null
        if (now < until) return until
        lockedUntil.remove(playerUuid)
        return null
    }

    /**
     * 兑换。
     *
     * ⚠ 顺序铁律（方案「兑换码专项」）：**先扣次数 → 再加余额**，两件事在同一次调用内完成 ——
     * 崩溃窗口最坏是玩家少一次机会，**绝不会凭空多拿**。
     */
    fun redeem(server: MinecraftServer, player: ServerPlayerEntity, rawCode: String): RedeemOutcome {
        val now = System.currentTimeMillis()
        if (lockedUntil(player.uuid, now) != null) return RedeemOutcome.Locked
        // ⚠ 走 allowNotify：两处调用方拿到 Throttled 都是 `-> {}`（静默），
        //    不在这里出声的话，兑换被冷却挡掉时玩家什么都看不到（与 [[RequestThrottle.allowNotify]] 同因）
        if (!RequestThrottle.allowNotify(player, "points_redeem", THROTTLE_MS)) return RedeemOutcome.Throttled

        val state = PointCodeState.get(server)

        // ① 先试外部签名码（服主在网站/发卡网卖的那类；secret 留空时它直接返回 null）
        val external = ExternalPointCode.verify(
            rawCode,
            CobbleMarketConfig.pointsExternalSecret,
            now,
            CobbleMarketConfig.pointsExternalMaxDays
        )
        if (external != null) {
            // 防重放：一个 serial 只能兑一次。**先记 serial 再加余额**（崩溃窗口最坏少一次机会）
            if (!state.markSerialUsed(external.serial)) return invalid(player.uuid, now)
            val after = PointsState.get(server).addBalance(player.uuid, external.amount)
            PointsAuditLogger.log(
                PointsLogType.REDEEM, player.uuid, player.name.string, external.amount, after,
                player.name.string, "外部码 serial=${external.serial}",
                detailEn = "External code serial=${external.serial}"
            )
            remember(server, player, external.amount, after, now)
            failures.remove(player.uuid)
            return RedeemOutcome.Success(external.amount, after)
        }

        // ② 再查本地码表
        val code = PointCodes.normalize(rawCode)
        val entry = code?.let { state.find(it) }
        if (entry == null || entry.isExhausted() || entry.isExpired(now) ||
            (entry.boundPlayer != null && entry.boundPlayer != player.uuid)
        ) {
            return invalid(player.uuid, now)
        }
        // 先扣次数，再加余额（顺序不能反：反了崩溃窗口会变成凭空多拿）
        state.recordRedemption(entry, player.uuid, player.name.string, now)
        val after = PointsState.get(server).addBalance(player.uuid, entry.amount)
        PointsAuditLogger.log(
            PointsLogType.REDEEM, player.uuid, player.name.string, entry.amount, after,
            player.name.string, "兑换码 ${PointCodes.format(entry.code)}",
            detailEn = "Code ${PointCodes.format(entry.code)}"
        )
        remember(server, player, entry.amount, after, now)
        failures.remove(player.uuid)
        return RedeemOutcome.Success(entry.amount, after)
    }

    /**
     * 记一条**玩家维度**的兑换记录（「我的兑换记录」界面的数据源）。
     *
     * ⚠ 两条成功分支都要调（本地码 / 外部码）—— 外部码不进码表，漏了它就是「玩家在网站买的码
     * 兑完查不到记录」。
     * ⚠ 必须在「扣次数 → 加余额」**之后**：这条只是显示用的副本，写不进去最坏少显示一条，
     * 绝不能让它的异常冒出去（钱已经动完了，抛异常等于玩家看到失败、其实已经到账）。
     */
    private fun remember(
        server: MinecraftServer,
        player: ServerPlayerEntity,
        amount: Long,
        balanceAfter: Long,
        now: Long
    ) {
        try {
            PointRecordState.get(server).append(player.uuid, PointRecord(amount, balanceAfter, now))
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to append point record: {}", e.message)
        }
    }

    private fun invalid(playerUuid: UUID, now: Long): RedeemOutcome {
        recordFailure(playerUuid, now)
        return RedeemOutcome.Invalid
    }

    /** 记一次兑换失败；窗口内失败达上限就把玩家锁一段时间 */
    private fun recordFailure(playerUuid: UUID, now: Long) {
        val list = failures.computeIfAbsent(playerUuid) { mutableListOf() }
        synchronized(list) {
            list.removeIf { now - it > WINDOW_MS }
            list.add(now)
            if (list.size >= MAX_FAILURES) {
                lockedUntil[playerUuid] = now + LOCK_MS
                list.clear()
            }
        }
    }
}
