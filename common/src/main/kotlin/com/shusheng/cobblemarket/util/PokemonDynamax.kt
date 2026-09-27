package com.shusheng.cobblemarket.util

import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.DataKeys
import net.minecraft.nbt.NbtCompound

/**
 * 精灵的「极巨化」信息 —— 官方 `Pokemon.dmaxLevel` 与 `Pokemon.gmaxFactor` 是**两个独立字段**。
 *
 * ⚠ **两者不是二选一**（决定了显示规则）：
 * - `gmaxFactor` = 变的**是不是**超极巨（外观 + 专属 G-Max 招式），默认 false
 * - `dmaxLevel`  = 极巨化时 **HP 倍率 = 1.5 + 0.05×等级**（官方说明：超极巨走同一条公式），默认 0
 *
 * ⇒ 界面上两者可以同时出现（`超极巨化 ⬥ 极巨等级：5`），也可以只出现一个。
 *
 * ⚠ Cobblemon **没有任何道具能改这两个值**（官方全仓库 grep 为空 ⇒ 道具/途径全留给附属模组）
 * ⇒ **普通精灵两者都是默认值 ⇒ 界面上那一行整行不画**，绝大多数精灵的布局完全不受影响。
 *
 * 与 [PokemonSize] / [PokemonTera] 同一套路：市场/拍卖/求购手里只有存档 NBT，故提供 [fromNbt]
 * （**轻量只解字段、不构造 Pokemon**——列表每次请求都会走这里）；上架与预览场景有实体，用 [from]。
 */
object PokemonDynamax {

    /** 极巨化信息；缺字段一律落官方默认值（false / 0）。 */
    data class Info(val gmaxFactor: Boolean, val dmaxLevel: Int) {
        /** 有没有可显示的：两个都是默认值 ⇒ 那一行**整行不画**（最常见的情况，布局不变） */
        val hasAny: Boolean get() = gmaxFactor || dmaxLevel > 0

        companion object {
            val NONE = Info(false, 0)
        }
    }

    /** 从实体精灵取（上架、出售选择预览等场景）。 */
    fun from(pokemon: Pokemon): Info = Info(pokemon.gmaxFactor, pokemon.dmaxLevel)

    /**
     * 从存档精灵 NBT 取（挂单 / 拍卖 / 求购的 `pokemonNbt`）。
     *
     * ⚠ **缺键 = 默认值**（`contains` 判断）：这两个键在官方 codec 里是 `fieldOf`（必填），
     *   正常存档都有；但升级上来的老精灵 / 附属模组造的数据可能没有 ⇒ 落默认而不是崩。
     * NBT 为空返回 [Info.NONE]。
     */
    fun fromNbt(nbt: NbtCompound?): Info {
        val n = nbt ?: return Info.NONE
        return runCatching {
            val gmax = n.contains(DataKeys.POKEMON_GMAX_FACTOR) &&
                n.getBoolean(DataKeys.POKEMON_GMAX_FACTOR)
            val level = if (n.contains(DataKeys.POKEMON_DMAX_LEVEL)) {
                n.getInt(DataKeys.POKEMON_DMAX_LEVEL)
            } else 0
            Info(gmax, level)
        }.getOrDefault(Info.NONE)
    }
}
