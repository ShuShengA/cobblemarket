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
    val reason: String
) {
    val isPermanent: Boolean get() = expiresAt == null
}

class BanState private constructor() : PersistentState() {

    private val bans = mutableMapOf<UUID, BanInfo>()

    fun ban(playerUuid: UUID, playerName: String, bannedBy: String, expiresAt: Long?, reason: String) {
        // 先删后插：重复封禁（改时长/理由）时 put 覆盖不移动位置，强制移到末尾保证最新在前
        bans.remove(playerUuid)
        bans[playerUuid] = BanInfo(playerUuid, playerName, bannedBy, System.currentTimeMillis(), expiresAt, reason)
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
                                reason = c.getString("reason")
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

        fun formatRemaining(ms: Long): String {
            val totalMinutes = ms / 60000
            val days = totalMinutes / (24 * 60)
            val hours = (totalMinutes % (24 * 60)) / 60
            val minutes = totalMinutes % 60
            return when {
                days > 0 -> "${days}d${hours}h"
                hours > 0 -> "${hours}h${minutes}m"
                else -> "${minutes}m"
            }
        }
    }
}
