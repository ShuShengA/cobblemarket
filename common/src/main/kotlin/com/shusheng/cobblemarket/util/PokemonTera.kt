package com.shusheng.cobblemarket.util

import com.cobblemon.mod.common.api.types.tera.TeraType
import com.cobblemon.mod.common.util.DataKeys
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtOps

/**
 * 「精灵的太晶属性」的读取入口 —— 与 [MoveText.resolve] / [PokemonSize.fromNbt] **同一套路**。
 *
 * ⚠ **为什么需要「从 NBT 现读」这条回退**：太晶显示是后加的功能，而挂单 / 拍卖 / 求购里那份
 * `extraData` 是**上架那一刻我们手写的一份快照**（`buildListingExtra`）——那时还没往里放这一项，
 * 于是升级上来的老条目全都看不到太晶。
 * **但挂单本来就存着精灵的完整 NBT**（`pokemonNbt`），太晶（官方 `Pokemon.teraType`）
 * **当时就在里面了**（用户 2026-09-26 实测确认：待领取走 NBT 重建，老挂单也能显示）。
 * ⇒ 不用让玩家把市场里的挂单挨个重新上架。
 *
 * ⚠ **只解一个字段，不构造 Pokemon**：列表每次请求都会走这里（同 [MoveText.fromNbt] 的理由）。
 * 用 Cobblemon **自己的 codec**（`TeraType.BY_IDENTIFIER_CODEC`，见 `ClientPokemonP2` 的
 * `fieldOf(DataKeys.POKEMON_TERA_TYPE)`）解码 —— 那是"官方怎么写进去的"对应的读法，
 * 比自己猜 NBT 结构可靠。
 *
 * ⚠ 任何异常 / 缺字段一律返回空串（读取容错，绝不抛）。
 */
object PokemonTera {

    /** 从精灵 NBT 读太晶，返回界面用的属性 key（`cobblemon.type.electric`）；取不到返回空串 */
    fun fromNbt(pokemonNbt: NbtCompound?): String {
        val nbt = pokemonNbt ?: return ""
        return runCatching {
            val element = nbt.get(DataKeys.POKEMON_TERA_TYPE) ?: return ""
            TeraType.BY_IDENTIFIER_CODEC.parse(NbtOps.INSTANCE, element)
                .result().orElse(null)
                ?.let { "cobblemon.type.${it.id.path}" }
                .orEmpty()
        }.getOrDefault("")
    }

    /**
     * 快照优先、缺失才读 NBT —— **列表侧统一调这个**（与 [MoveText.resolve] 同签名）。
     * [raw] = `extraData["teraType"]`（新挂单有、老挂单没有）。
     */
    fun resolve(raw: String?, pokemonNbt: NbtCompound?): String =
        if (!raw.isNullOrBlank()) raw else fromNbt(pokemonNbt)
}
