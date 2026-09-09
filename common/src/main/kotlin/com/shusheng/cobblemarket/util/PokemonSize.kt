package com.shusheng.cobblemarket.util

import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.PokemonSizeCategory
import com.cobblemon.mod.common.util.DataKeys
import net.minecraft.nbt.NbtCompound

/**
 * 精灵体型分类（详情界面名字右侧那个 xs/s/m/l/xl 徽章）。
 *
 * 规则与 Cobblemon 详情页一致：alpha 精灵显示 ALPHA 徽章，其余按 ScaleModifier 分档。
 * 市场/拍卖/求购的数据只有存档 NBT，故提供 [fromNbt]；上架与预览场景直接有实体，用 [from]。
 * 取值为枚举名（"XS"/"S"/"M"/"L"/"XL"/"ALPHA"），空串 = 无体型信息（客户端不显示）。
 */
object PokemonSize {

    const val ALPHA = "ALPHA"

    /** 从实体精灵取（上架、出售选择预览等场景）。 */
    fun from(pokemon: Pokemon): String =
        if (pokemon.isAlpha) ALPHA else pokemon.getSizeCategory().name

    /**
     * 从存档精灵 NBT 取（挂单/拍卖/求购的 pokemonNbt）。
     * 旧存档缺 ScaleModifier 时按 1.0 处理，与 Cobblemon 默认值一致；NBT 为空返回空串。
     */
    fun fromNbt(nbt: NbtCompound?): String {
        if (nbt == null || nbt.isEmpty) return ""
        if (nbt.getBoolean(DataKeys.POKEMON_ALPHA)) return ALPHA
        val scale = if (nbt.contains(DataKeys.POKEMON_SCALE_MODIFIER)) {
            nbt.getFloat(DataKeys.POKEMON_SCALE_MODIFIER)
        } else 1F
        return PokemonSizeCategory.fromScale(scale).name
    }
}
