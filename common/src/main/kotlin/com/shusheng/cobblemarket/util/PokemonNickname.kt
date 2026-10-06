package com.shusheng.cobblemarket.util

import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.DataKeys
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.nbt.NbtCompound
import net.minecraft.text.TextCodecs

/**
 * 精灵**昵称**（玩家给精灵起的名字，`Pokemon.nickname`）。
 *
 * ⚠ 与 [PokemonOT] / [PokemonSize] 同一套路：**轻量只解字段、不构造 Pokemon** ——
 *   挂单 / 拍卖 / 求购的 DTO 是**逐行**组装的（一页几十行），重建整只精灵代价太大。
 *
 * 存档里的存法（**照 Cobblemon 自己的读法**抄，见其 `PokemonProperties`）：
 * NBT 键 [DataKeys.POKEMON_NICKNAME]，值是**一个 JSON 字符串**（`Text` 的 JSON 序列化形式）——
 * 不是组件结构本身，所以要先 `JsonParser` 再进 `TextCodecs.CODEC`
 * （`Text.Serialization.fromJson` 在 1.21.1 是包私有，项目里统一走公开的 `TextCodecs`）。
 *
 * ⚠⭐ **§ 的过滤统一收在这里**：Cobblemon 改名只校验长度（服务端 12 字符）
 *   **不拦 §**，我们商城侧（`PointMarketService`）对昵称也没有任何校验 ⇒ 不过滤的话
 *   那串会被 MC 当**格式码**解析（原版命名牌就这效果），把界面上那一行的配色打断
 *   （照 OT 的「禁 §」口径）。要改取向只改这一个函数。
 *
 * 读取容错：没有 / 空白 / 解析失败一律返回 **null** ⇒ 界面按「**那一行整行不画**」处理。
 */
object PokemonNickname {

    /** 从**存档精灵 NBT**取昵称（挂单 / 拍卖 / 求购那条线拿到的都是 NBT） */
    fun fromNbt(nbt: NbtCompound?): String? {
        val n = nbt ?: return null
        return runCatching {
            val json = n.getString(DataKeys.POKEMON_NICKNAME)
            if (json.isEmpty()) return null
            TextCodecs.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString(json))
                .result().orElse(null)?.first?.string
        }.getOrNull().cleaned()
    }

    /** 从**活精灵**取昵称（上架、商城生成、待领取、分享这类手上有 `Pokemon` 的场景） */
    fun from(pokemon: Pokemon): String? =
        runCatching { pokemon.nickname?.string }.getOrNull().cleaned()

    /** 过滤 § 格式码 + 去首尾空白；空串 = 没有昵称 */
    private fun String?.cleaned(): String? =
        this?.replace("§", "")?.trim()?.takeIf { it.isNotEmpty() }
}
