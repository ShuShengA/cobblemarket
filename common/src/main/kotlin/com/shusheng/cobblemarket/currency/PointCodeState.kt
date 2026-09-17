package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

/** 一次兑换记录（谁、什么时候兑的）—— 服主查「这张码被谁用了」靠它 */
data class PointRedemption(val playerUuid: UUID, val playerName: String, val at: Long)

/**
 * 一张**本地兑换码**（`/market points code create` 生成的那类）。
 *
 * 与「外部签名码」的区别（见 `docs/points_plan.md` 的两条路径对照）：
 * - **本地码存在本表里**：自带次数、绑定玩家、有效期语义，可作废、可列出
 * - **外部码不进表**：只记已用的 serial 防重放（`usedSerials`），自带面额与有效期
 */
data class PointCode(
    val code: String,
    val amount: Long,
    val totalUses: Int,
    var remainingUses: Int,
    val expiresAt: Long?,
    val createdAt: Long,
    val createdBy: String,
    val boundPlayer: UUID?,
    val boundPlayerName: String?,
    val redemptions: MutableList<PointRedemption>
) {
    fun isExpired(now: Long): Boolean = expiresAt != null && now > expiresAt

    fun isExhausted(): Boolean = remainingUses <= 0

    /** 还能不能兑（次数未尽 + 没过期） */
    fun isUsable(now: Long): Boolean = !isExhausted() && !isExpired(now)
}

/**
 * **兑换码账本**（`cobblemarket_point_codes.dat`）：本地码表 + 外部签名码的已用 serial 集合。
 *
 * ⚠ 兑换流程的铁律（方案「兑换码专项」）：**先扣次数、再加余额**，两件事在同一个
 * `server.execute{}` 单线程内完成 —— 崩溃窗口最坏是玩家少一次机会，**绝不会凭空多拿**。
 */
class PointCodeState private constructor() : PersistentState() {

    /** 归一化码 → 码记录 */
    private val codes = mutableMapOf<String, PointCode>()

    /**
     * 已用过的外部签名码 serial（归一化 16 进制）。本地码不走这里。
     *
     * ⚠ **永久码（expiresDays=0）的 serial 必须永久保留**，否则可被重放；带有效期的码其 serial
     * 在过期后保留 30 天再清理（防"刚过期就重放"）—— 清理时机由 [purgeExpired] 带过。
     */
    private val usedSerials = mutableSetOf<String>()

    fun find(code: String): PointCode? = codes[code]

    fun all(): Collection<PointCode> = codes.values

    fun size(): Int = codes.size

    /**
     * 生成一张本地码。碰撞（极低概率）则重抽，**带上限防死循环**（表被填满时宁可失败也不卡服）。
     * 返回 null = 连续碰撞超限。
     */
    fun create(
        amount: Long,
        uses: Int,
        expiresAt: Long?,
        createdBy: String,
        boundPlayer: UUID?,
        boundPlayerName: String?,
        now: Long
    ): PointCode? {
        repeat(MAX_GENERATE_ATTEMPTS) {
            val code = PointCodes.generate()
            if (code !in codes) {
                val entry = PointCode(
                    code = code,
                    amount = amount,
                    totalUses = uses,
                    remainingUses = uses,
                    expiresAt = expiresAt,
                    createdAt = now,
                    createdBy = createdBy,
                    boundPlayer = boundPlayer,
                    boundPlayerName = boundPlayerName,
                    redemptions = mutableListOf()
                )
                codes[code] = entry
                markDirty()
                return entry
            }
        }
        CobbleMarket.LOGGER.warn("Point code generation collided {} times; giving up", MAX_GENERATE_ATTEMPTS)
        return null
    }

    /** 作废（删除）一张码；返回是否真的删掉了 */
    fun revoke(code: String): Boolean {
        if (codes.remove(code) == null) return false
        markDirty()
        return true
    }

    /** 记一次兑换（次数 -1 + 追加记录）。**调用方负责在扣次数后才加余额** */
    fun recordRedemption(entry: PointCode, playerUuid: UUID, playerName: String, now: Long) {
        entry.remainingUses -= 1
        entry.redemptions.add(PointRedemption(playerUuid, playerName, now))
        markDirty()
    }

    /**
     * 清理已用尽 / 已过期的码（防存档膨胀）。返回清掉的条数。
     * `keepRedemptionsDays = 0` 表示不保留（默认清）—— 审计另有 CSV 账本，表里不必留。
     */
    fun purgeExpired(now: Long, keepRedemptionsDays: Long = 0): Int {
        val cutoff = now - keepRedemptionsDays * DAY_MS
        val toRemove = codes.values.filter { entry ->
            (!entry.isUsable(now)) && (keepRedemptionsDays <= 0 || entry.createdAt < cutoff)
        }
        toRemove.forEach { codes.remove(it.code) }
        if (toRemove.isNotEmpty()) markDirty()
        return toRemove.size
    }

    // ── 外部签名码的防重放 ──

    fun isSerialUsed(serial: String): Boolean = serial in usedSerials

    /** 记下已用的 serial；返回 false = 这个 serial 已经用过（调用方必须拒绝兑换） */
    fun markSerialUsed(serial: String): Boolean {
        if (!usedSerials.add(serial)) return false
        markDirty()
        return true
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        codes.values.forEach { entry ->
            val c = NbtCompound()
            c.putString("code", entry.code)
            c.putLong("amount", entry.amount)
            c.putInt("totalUses", entry.totalUses)
            c.putInt("remainingUses", entry.remainingUses)
            entry.expiresAt?.let { c.putLong("expiresAt", it) }
            c.putLong("createdAt", entry.createdAt)
            c.putString("createdBy", entry.createdBy)
            entry.boundPlayer?.let { c.putUuid("boundPlayer", it) }
            entry.boundPlayerName?.let { c.putString("boundPlayerName", it) }
            val redemptions = NbtList()
            entry.redemptions.forEach { r ->
                val rc = NbtCompound()
                rc.putUuid("uuid", r.playerUuid)
                rc.putString("name", r.playerName)
                rc.putLong("at", r.at)
                redemptions.add(rc)
            }
            c.put("redemptions", redemptions)
            list.add(c)
        }
        nbt.put("codes", list)
        val serials = NbtList()
        usedSerials.forEach { s ->
            val sc = NbtCompound()
            sc.putString("serial", s)
            serials.add(sc)
        }
        nbt.put("usedSerials", serials)
        return nbt
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MAX_GENERATE_ATTEMPTS = 64

        private val TYPE = PersistentState.Type(
            { PointCodeState() },
            { nbt, _ ->
                PointCodeState().apply {
                    // 逐条容错：单条损坏只跳过它 + warn，绝不整体抛异常（见 CLAUDE.md 的存档铁律）
                    nbt.getList("codes", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            val code = c.getString("code")
                            if (code.isBlank()) return@forEach
                            val redemptions = mutableListOf<PointRedemption>()
                            c.getList("redemptions", NbtList.COMPOUND_TYPE.toInt()).forEach { re ->
                                try {
                                    val rc = re as NbtCompound
                                    redemptions.add(
                                        PointRedemption(rc.getUuid("uuid"), rc.getString("name"), rc.getLong("at"))
                                    )
                                } catch (e: Exception) {
                                    CobbleMarket.LOGGER.warn("Skipping corrupted point redemption: {}", e.message)
                                }
                            }
                            codes[code] = PointCode(
                                code = code,
                                amount = c.getLong("amount"),
                                totalUses = c.getInt("totalUses"),
                                remainingUses = c.getInt("remainingUses"),
                                expiresAt = if (c.contains("expiresAt")) c.getLong("expiresAt") else null,
                                createdAt = c.getLong("createdAt"),
                                createdBy = c.getString("createdBy"),
                                boundPlayer = if (c.contains("boundPlayer")) c.getUuid("boundPlayer") else null,
                                boundPlayerName = if (c.contains("boundPlayerName")) c.getString("boundPlayerName") else null,
                                redemptions = redemptions
                            )
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted point code entry: {}", e.message)
                        }
                    }
                    nbt.getList("usedSerials", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val serial = (element as NbtCompound).getString("serial")
                            if (serial.isNotBlank()) usedSerials.add(serial)
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted used serial: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): PointCodeState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_point_codes")
    }
}
