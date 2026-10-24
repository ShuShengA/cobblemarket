package com.shusheng.cobblemarket.currency

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.entity.Entity

/**
 * **绑定 NPC 的模型显示信息**（2026-10-23）。
 *
 * 管理界面的 3D 头像按「实体类型 id」建假实体，但宝可梦的实体类型对所有物种都是
 * [POKEMON_TYPE] —— 光有类型既画不出正确的模型，也显示不出名字（全叫「宝可梦」、还各自随机）。
 * 所以绑定 / 列附近实体时额外记下**物种 id** 与 **aspects**（闪光 / 地区形态）。
 *
 * ⚠ 只在宝可梦实体上取得到值；其它实体一律空串（客户端走原路径）。
 */
object NpcDisplayMeta {

    /** Cobblemon 的宝可梦实体类型 id（所有物种共用） */
    const val POKEMON_TYPE = "cobblemon:pokemon"

    /** aspects 的编码分隔符 —— aspect 名字里不会有空格 */
    private const val ASPECT_SEPARATOR = " "

    /** 物种 id（如 `cobblemon:pikachu`）；不是宝可梦 / 拿不到时空串 */
    fun speciesOf(entity: Entity?): String =
        (entity as? PokemonEntity)?.pokemon?.species?.resourceIdentifier?.toString().orEmpty()

    /** 形态 aspects（闪光、地区形态…），排序后空格分隔；不是宝可梦 / 没有 aspects 时空串 */
    fun aspectsOf(entity: Entity?): String =
        (entity as? PokemonEntity)?.pokemon?.aspects.orEmpty()
            .filter { it.isNotBlank() }
            .sorted()
            .joinToString(ASPECT_SEPARATOR)

    /** 把 [aspectsOf] 的编码还原成集合（空串 → 空集） */
    fun aspectsFrom(raw: String): Set<String> =
        raw.split(ASPECT_SEPARATOR).filter { it.isNotBlank() }.toSet()
}
