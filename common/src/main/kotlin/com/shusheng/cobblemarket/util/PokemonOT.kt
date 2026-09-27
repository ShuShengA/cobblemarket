package com.shusheng.cobblemarket.util

import com.cobblemon.mod.common.pokemon.OriginalTrainerType
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.DataKeys
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtOps
import net.minecraft.server.MinecraftServer
import java.util.UUID

/**
 * 精灵的「初始训练家」（OT，Original Trainer）。
 *
 * 官方语义（`Pokemon.originalTrainer` 的 KDoc）：**要么是玩家 UUID、要么是假训练家的显示名、要么没有**，
 * 由 `originalTrainerType` 区分（`NONE` / `PLAYER` / `NPC`）。
 *
 * ⚠⚠ **存档 NBT 里没有缓存好的显示名** —— `PokemonOriginalTrainerName` 只出现在**网络同步**用的
 *   `ClientPokemonP3` 里，官方存档 codec（`PokemonP3`）**不写它**；而我们的挂单 / 拍卖 / 求购快照
 *   存的都是**存档 NBT** ⇒ **必须自己把 UUID 解析成名字**（不能指望 NBT 里现成有）。
 *
 * 解析口径**照抄 Cobblemon 自己**（`Pokemon.refreshOriginalTrainer` 用 `server.profileCache`）
 * 与项目现成入口（`MarketCommands` 的「在线 playerManager → 离线 userCache」）：
 * 先查在线玩家（内存查询，列表请求的绝大多数情况都命中），miss 才查 `userCache`（离线玩家）。
 *
 * ⚠ 与 [PokemonSize] / [PokemonTera] 同一套路：**轻量只解字段、不构造 Pokemon**
 *   （列表每次请求都会走这里）。任何异常 / 缺字段一律返回空串（读取容错，绝不抛）。
 */
object PokemonOT {

    /**
     * 从存档精灵 NBT 解析 OT 的**显示名**。
     *
     * 没有 OT / 解析不到一律返回**空串** —— 调用方按「那一行整行不画」处理
     * （宁可少一行，也不把一串 UUID 甩给玩家看）。
     *
     * @param server 解析玩家名用；传 null 时只有 `NPC`（假训练家）解析得出，玩家 OT 返回空串
     */
    fun fromNbt(nbt: NbtCompound?, server: MinecraftServer?): String {
        val n = nbt ?: return ""
        return runCatching {
            // 枚举按**官方 codec**解码（`StringRepresentable` ⇒ 存的是枚举名），不自己 valueOf：
            // 非法值走 codec 自己的容错，不崩
            val element = n.get(DataKeys.POKEMON_ORIGINAL_TRAINER_TYPE) ?: return ""
            val type = OriginalTrainerType.CODEC.parse(NbtOps.INSTANCE, element).result().orElse(null)
                ?: return ""
            val raw = n.getString(DataKeys.POKEMON_ORIGINAL_TRAINER)
            when (type) {
                OriginalTrainerType.PLAYER -> playerName(raw, server)
                // 假训练家：`originalTrainer` 本身就是显示名
                OriginalTrainerType.NPC -> raw
                OriginalTrainerType.NONE -> ""
            }
        }.getOrDefault("")
    }

    /** 从实体精灵取（上架、出售选择预览等场景）：官方缓存的显示名优先，缺了再自己解析。 */
    fun from(pokemon: Pokemon, server: MinecraftServer?): String = runCatching {
        when (pokemon.originalTrainerType) {
            OriginalTrainerType.PLAYER ->
                pokemon.originalTrainerName ?: playerName(pokemon.originalTrainer.orEmpty(), server)
            OriginalTrainerType.NPC ->
                pokemon.originalTrainerName ?: pokemon.originalTrainer.orEmpty()
            OriginalTrainerType.NONE -> ""
        }
    }.getOrDefault("")

    /** UUID 字符串 → 玩家名：在线（内存）→ 离线 `userCache`；查不到返回空串。 */
    private fun playerName(rawUuid: String, server: MinecraftServer?): String {
        val s = server ?: return ""
        val uuid = runCatching { UUID.fromString(rawUuid) }.getOrNull() ?: return ""
        return s.playerManager.getPlayer(uuid)?.name?.string
            ?: s.userCache?.getByUuid(uuid)?.orElse(null)?.name
            ?: ""
    }
}
