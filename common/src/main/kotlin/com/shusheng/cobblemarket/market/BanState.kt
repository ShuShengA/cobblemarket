package com.shusheng.cobblemarket.market

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

data class BanInfo(
    val playerUuid: UUID,
    val playerName: String,
    val bannedBy: String,
    val bannedAt: Long,
    val expiresAt: Long?,  // null = 永久封禁
    val reason: String,
    /** 同时禁止借款（**只拦借款**：存取款、还款都不受影响 —— 还款必须留着，他还钱才能把逾期降下来）。
     *  末尾新字段 + 默认 false ⇒ 旧档缺这一格自动落默认，不需要迁移分支 */
    val blockLoan: Boolean = false
) {
    val isPermanent: Boolean get() = expiresAt == null
}

class BanState private constructor() : PersistentState() {

    private val bans = mutableMapOf<UUID, BanInfo>()

    fun ban(
        playerUuid: UUID,
        playerName: String,
        bannedBy: String,
        expiresAt: Long?,
        reason: String,
        blockLoan: Boolean = false
    ) {
        // 先删后插：重复封禁（改时长/理由）时 put 覆盖不移动位置，强制移到末尾保证最新在前
        bans.remove(playerUuid)
        bans[playerUuid] = BanInfo(playerUuid, playerName, bannedBy, System.currentTimeMillis(), expiresAt, reason, blockLoan)
        markDirty()
    }

    fun unban(playerUuid: UUID): BanInfo? {
        val removed = bans.remove(playerUuid)
        if (removed != null) markDirty()
        return removed
    }

    fun getBanInfo(playerUuid: UUID, currentTime: Long): BanInfo? {
        val info = bans[playerUuid] ?: return null
        val exp = info.expiresAt
        if (exp != null && exp <= currentTime) {
            bans.remove(playerUuid)
            markDirty()
            return null
        }
        return info
    }

    fun getAllBans(currentTime: Long): List<BanInfo> {
        val expired = bans.values.filter { it.expiresAt != null && it.expiresAt <= currentTime }
        expired.forEach { bans.remove(it.playerUuid) }
        if (expired.isNotEmpty()) markDirty()
        return bans.values.toList()
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        bans.values.forEach { info ->
            val c = NbtCompound()
            c.putUuid("uuid", info.playerUuid)
            c.putString("name", info.playerName)
            c.putString("bannedBy", info.bannedBy)
            c.putLong("bannedAt", info.bannedAt)
            info.expiresAt?.let { c.putLong("expiresAt", it) }
            c.putString("reason", info.reason)
            c.putBoolean("blockLoan", info.blockLoan)
            list.add(c)
        }
        nbt.put("bans", list)
        return nbt
    }

    companion object {
        private val TYPE = PersistentState.Type(
            { BanState() },
            { nbt, _ ->
                BanState().apply {
                    nbt.getList("bans", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            val expiresAt = if (c.contains("expiresAt")) c.getLong("expiresAt") else null
                            val info = BanInfo(
                                playerUuid = c.getUuid("uuid"),
                                playerName = c.getString("name"),
                                bannedBy = c.getString("bannedBy"),
                                bannedAt = c.getLong("bannedAt"),
                                expiresAt = expiresAt,
                                reason = c.getString("reason"),
                                // 缺这格 = 旧档 ⇒ getBoolean 给 false，正是要的默认值
                                blockLoan = c.getBoolean("blockLoan")
                            )
                            bans[info.playerUuid] = info
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted ban entry: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): BanState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_bans")

        /**
         * 封禁理由渲染约定：以 $ 开头的 reason 是 translatable key（系统来源，渲染时按玩家语言翻译）；
         * 玩家手动填写的理由原样显示（不翻译）。
         */
        fun reasonText(reason: String): net.minecraft.text.Text =
            if (reason.startsWith("$"))
                net.minecraft.text.Text.translatable(reason.removePrefix("$"))
            else
                net.minecraft.text.Text.literal(reason)

        fun parseDurationMs(input: String): Long? {
            val regex = Regex("(\\d+)([dhm])")
            var total = 0L
            var matched = false
            regex.findAll(input.lowercase()).forEach { m ->
                // 数字超 Long 范围：整体解析失败，宁报错不静默忽略该段
                val v = m.groupValues[1].toLongOrNull() ?: return null
                val unitMs = when (m.groupValues[2]) {
                    "d" -> 24L * 60 * 60 * 1000
                    "h" -> 60L * 60 * 1000
                    "m" -> 60L * 1000
                    else -> return null
                }
                // 乘法/累加溢出防护：Long 回绕会颠覆封禁语义，改为安全失败
                if (v > Long.MAX_VALUE / unitMs) return null
                val addMs = v * unitMs
                if (total > Long.MAX_VALUE - addMs) return null
                total += addMs
                matched = true
            }
            return if (matched && total > 0) total else null
        }

        fun resolvePlayer(server: MinecraftServer, name: String): Pair<UUID, String>? {
            server.playerManager.getPlayer(name)?.let { return it.uuid to it.name.string }
            val profile = server.userCache?.findByName(name)?.orElse(null)
            return profile?.let { it.id to it.name }
        }

        /**
         * 剩余时长（**本地化**）：返回 Text 而非字符串 —— 调用方一律把它当**嵌套参数**塞进词条
         * （如 `ban.remaining`），由客户端按自己的语言渲染。⚠ 别先 `.string` 成串：那样在服务端
         * 就定死成一种语言了（英文客户端玩家会看到中文）。
         * 三档与旧的 `3d5h` 写法一一对应，只是补上了语言与单位名。
         */
        fun remainingText(ms: Long): net.minecraft.text.Text {
            val totalMinutes = ms / 60000
            val days = totalMinutes / (24 * 60)
            val hours = (totalMinutes % (24 * 60)) / 60
            val minutes = totalMinutes % 60
            return when {
                days > 0 -> net.minecraft.text.Text.translatable("cobblemarket.ban.dur_days_hours", days, hours)
                hours > 0 -> net.minecraft.text.Text.translatable("cobblemarket.ban.dur_hours_minutes", hours, minutes)
                else -> net.minecraft.text.Text.translatable("cobblemarket.ban.dur_minutes", minutes)
            }
        }
    }
}
