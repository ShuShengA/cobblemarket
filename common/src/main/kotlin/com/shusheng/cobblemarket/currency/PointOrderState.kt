package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

/**
 * 充值订单状态机（方案「RP 充值」）。
 *
 * ```
 * [待支付] --玩家点「我已支付」--> [待确认] --服主确认--> [已发放]
 *     |                              |
 *     +-- 超时/玩家取消 --> [已作废]   +-- 服主作废 --> [已作废]
 * ```
 *
 * ⚠ **状态的每一次流转都只在服务端发生**，且「我已支付」只是玩家的**声明不是证据** ——
 * 未确认一律不发放（宁可漏发，不能滥发）。
 */
enum class PointOrderStatus { PENDING_PAYMENT, PENDING_CONFIRM, GRANTED, VOID }

/** 作废原因（只作展示与排查，不参与任何判定） */
enum class PointOrderVoidReason { TIMEOUT, PLAYER_CANCEL, OPERATOR }

/**
 * 一笔充值订单。
 *
 * ⚠ 金额一律用**分**（Long）存算，绝不用 Double —— `5.00` 在浮点里是 `4.999...`，
 * 而这里的每一分钱都要和服主的收款账单逐笔对上。
 */
data class PointOrder(
    val orderNo: String,
    val playerUuid: UUID,
    val playerName: String,
    /** 下单时的档位名（快照：服主事后改档位不影响历史订单的展示） */
    val tierName: String,
    /** 档位标价（分）—— 只作展示与对账参照，实付看 [payCents] */
    val baseCents: Long,
    /** **实付金额（分）= 标价 + 唯一尾数** —— 服主在收款账单里靠它一眼对上订单 */
    val payCents: Long,
    /** 确认发放的RP 数 */
    val points: Long,
    val createdAt: Long,
    /** 待支付超时时刻；进入待确认后不再作数（玩家已付款，等服主处理，不自动作废） */
    val expireAt: Long,
    /**
     * 下单时**快照**下来的收款码文件名（空 = 这一单没有码可展示）。
     * ⚠ 存在订单上而不是每次按档位去查 —— 服主事后改档位/换码，老订单也不会指错图。
     */
    val qrFile: String = "",
    var status: PointOrderStatus,
    var paidAt: Long? = null,
    var resolvedAt: Long? = null,
    var operatorName: String? = null,
    var voidReason: PointOrderVoidReason? = null
) {
    /** 未完结（占着金额尾数）：待支付 + 待确认 */
    fun isActive(): Boolean =
        status == PointOrderStatus.PENDING_PAYMENT || status == PointOrderStatus.PENDING_CONFIRM

    /** 金额尾数（1-99 分）—— 唯一化的那个键 */
    fun tail(): Int = (payCents % 100).toInt()
}

/**
 * **充值订单账本**（`cobblemarket_point_orders.dat`）。
 *
 * 与兑换码账本的分工：兑换码是「先有码、后有主」，订单是「先有单、后有账」 ——
 * 订单记录的是**一次待处理的收款**，服主确认后才变成RP。
 *
 * 文件名前缀带 `cobblemarket` ⇒ 自动进 `PersistHelper` / `StateBackup` 的备份、恢复与 mtime 校验。
 */
class PointOrderState private constructor() : PersistentState() {

    /** 订单号 → 订单。已完结订单保留一段时间供对账，到期由 [purgeFinished] 清理 */
    private val orders = mutableMapOf<String, PointOrder>()

    fun find(orderNo: String): PointOrder? = orders[orderNo]

    fun all(): Collection<PointOrder> = orders.values

    fun size(): Int = orders.size

    /**
     * 玩家当前**全部**未完结订单（按创建时间升序）。
     * **可以有多笔**（2026-09-18 用户拍板：玩家想改充别的档位不该被一笔卡死），
     * 同时存在的上限由 [com.shusheng.cobblemarket.currency.PointShopService] 把关。
     */
    fun activeOrdersOf(playerUuid: UUID): List<PointOrder> =
        orders.values.filter { it.playerUuid == playerUuid && it.isActive() }.sortedBy { it.createdAt }

    /** 待确认订单（管理端列表用），按玩家提交时间升序 —— 先提交的先处理 */
    fun pendingConfirm(): List<PointOrder> = orders.values
        .filter { it.status == PointOrderStatus.PENDING_CONFIRM }
        .sortedBy { it.paidAt ?: it.createdAt }

    /**
     * 建单。
     *
     * **订单号与金额尾数都要唯一**：订单号查全表（保留期内不可重复，否则申诉时对不上），
     * 尾数查**活跃订单**（两个待处理的单金额一模一样，服主就无法一眼区分谁是谁了）。
     *
     * 返回 null = 连续重抽超限（99 个尾数被活跃订单占满时才会发生；一人同时只允许一单，
     * 实际要 99 个玩家同时挂着待处理订单才可能撞上）。
     */
    fun create(
        playerUuid: UUID,
        playerName: String,
        tierName: String,
        baseCents: Long,
        points: Long,
        timeoutMinutes: Long,
        qrFile: String,
        /** 是否给实付金额加**唯一尾数**。⚠ 用"带金额的收款码"的档位必须传 false ——
         *  码上的金额是写死的，加尾数玩家就付不出来了（2026-09-18 用户提出多张码时定的） */
        tailEnabled: Boolean,
        now: Long
    ): PointOrder? {
        val usedTails = orders.values.filter { it.isActive() }.mapTo(mutableSetOf()) { it.tail() }
        for (attempt in 0 until MAX_GENERATE_ATTEMPTS) {
            val orderNo = PointOrderNumbers.generate()
            if (orderNo in orders) continue
            val payCents = if (tailEnabled) baseCents + PointOrderNumbers.tailOf(orderNo) else baseCents
            if (tailEnabled) {
                // 标价本身带角分时，加上尾数可能恰好进位到整元（尾数被抹平成 00）——换个号重来
                if (payCents % 100L == 0L) continue
                if ((payCents % 100L).toInt() in usedTails) continue
            }
            val order = PointOrder(
                orderNo = orderNo,
                playerUuid = playerUuid,
                playerName = playerName,
                tierName = tierName,
                baseCents = baseCents,
                payCents = payCents,
                points = points,
                createdAt = now,
                expireAt = now + timeoutMinutes * 60_000L,
                qrFile = qrFile,
                status = PointOrderStatus.PENDING_PAYMENT
            )
            orders[orderNo] = order
            markDirty()
            return order
        }
        CobbleMarket.LOGGER.warn("Point order number collided {} times; giving up", MAX_GENERATE_ATTEMPTS)
        return null
    }

    /** 玩家声明「我已支付」：待支付 → 待确认。**只是登记声明，不发任何东西** */
    fun markPaid(order: PointOrder, now: Long) {
        order.status = PointOrderStatus.PENDING_CONFIRM
        order.paidAt = now
        markDirty()
    }

    /** 服主确认发放后的状态落定（余额由调用方在状态落定**之后**加，见 PointShopService） */
    fun markGranted(order: PointOrder, operatorName: String, now: Long) {
        order.status = PointOrderStatus.GRANTED
        order.operatorName = operatorName
        order.resolvedAt = now
        markDirty()
    }

    /** 作废（超时 / 玩家取消 / 服主拒绝） */
    fun markVoid(order: PointOrder, reason: PointOrderVoidReason, operatorName: String?, now: Long) {
        order.status = PointOrderStatus.VOID
        order.voidReason = reason
        order.operatorName = operatorName
        order.resolvedAt = now
        markDirty()
    }

    /** 作废超时未支付的订单，返回作废条数。[graceMs] = 超时后的宽限期（见 PointShopService 的说明） */
    fun voidExpired(now: Long, graceMs: Long): Int {
        // ⚠ 判断写成「now - graceMs > expireAt」而非「now > expireAt + graceMs」：
        // 后者在 expireAt 为 Long.MAX_VALUE（旧档缺该字段时的兜底值）时会溢出成负数，
        // 于是「永不过期」的订单反而被立刻作废。
        val expired = orders.values.filter {
            it.status == PointOrderStatus.PENDING_PAYMENT && now - graceMs > it.expireAt
        }
        expired.forEach { markVoid(it, PointOrderVoidReason.TIMEOUT, null, now) }
        return expired.size
    }

    /**
     * 清理已完结订单（防存档膨胀）。返回清理条数。
     * 保留期内的订单留着供服主与收款账单对账。
     */
    fun purgeFinished(now: Long, keepDays: Long): Int {
        if (keepDays <= 0) return 0
        val cutoff = now - keepDays * DAY_MS
        val toRemove = orders.values.filter { !it.isActive() && (it.resolvedAt ?: it.createdAt) < cutoff }
        toRemove.forEach { orders.remove(it.orderNo) }
        if (toRemove.isNotEmpty()) markDirty()
        return toRemove.size
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        orders.values.forEach { order ->
            val c = NbtCompound()
            c.putString("orderNo", order.orderNo)
            c.putUuid("playerUuid", order.playerUuid)
            c.putString("playerName", order.playerName)
            c.putString("tierName", order.tierName)
            c.putLong("baseCents", order.baseCents)
            c.putLong("payCents", order.payCents)
            c.putLong("points", order.points)
            c.putLong("createdAt", order.createdAt)
            c.putLong("expireAt", order.expireAt)
            if (order.qrFile.isNotEmpty()) c.putString("qrFile", order.qrFile)
            c.putString("status", order.status.name)
            order.paidAt?.let { c.putLong("paidAt", it) }
            order.resolvedAt?.let { c.putLong("resolvedAt", it) }
            order.operatorName?.let { c.putString("operatorName", it) }
            order.voidReason?.let { c.putString("voidReason", it.name) }
            list.add(c)
        }
        nbt.put("orders", list)
        return nbt
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MAX_GENERATE_ATTEMPTS = 64

        private val TYPE = PersistentState.Type(
            { PointOrderState() },
            { nbt, _ ->
                PointOrderState().apply {
                    // 逐条容错：单条损坏只跳过它 + warn，绝不整体抛异常（见 CLAUDE.md 的存档铁律）
                    nbt.getList("orders", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            val orderNo = c.getString("orderNo")
                            if (orderNo.isBlank()) return@forEach
                            // 认不出的状态直接跳过这条：宁可少一条订单，也绝不把「已发放」误读成「待支付」
                            val status = runCatching { PointOrderStatus.valueOf(c.getString("status")) }.getOrNull()
                            if (status == null) {
                                CobbleMarket.LOGGER.warn("Skipping point order {} with unknown status", orderNo)
                                return@forEach
                            }
                            val voidReason = if (c.contains("voidReason")) {
                                runCatching { PointOrderVoidReason.valueOf(c.getString("voidReason")) }.getOrNull()
                            } else null
                            orders[orderNo] = PointOrder(
                                orderNo = orderNo,
                                playerUuid = c.getUuid("playerUuid"),
                                playerName = c.getString("playerName"),
                                tierName = c.getString("tierName"),
                                baseCents = c.getLong("baseCents"),
                                payCents = c.getLong("payCents"),
                                points = c.getLong("points"),
                                createdAt = c.getLong("createdAt"),
                                expireAt = if (c.contains("expireAt")) c.getLong("expireAt") else Long.MAX_VALUE,
                                qrFile = if (c.contains("qrFile")) c.getString("qrFile") else "",
                                status = status,
                                paidAt = if (c.contains("paidAt")) c.getLong("paidAt") else null,
                                resolvedAt = if (c.contains("resolvedAt")) c.getLong("resolvedAt") else null,
                                operatorName = if (c.contains("operatorName")) c.getString("operatorName") else null,
                                voidReason = voidReason
                            )
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted point order entry: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): PointOrderState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_point_orders")
    }
}
