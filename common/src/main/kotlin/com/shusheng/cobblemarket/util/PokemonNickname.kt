package com.shusheng.cobblemarket.util

import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.DataKeys
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.nbt.NbtCompound
import net.minecraft.text.Text
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

    /**
     * **「名字（昵称）」** —— 昵称非空才补括号段，否则**原样返回 [name]**。
     *
     * ⚠ 用在**聊天播报的正文**（拍卖上架 / 成交 / 展示）：昵称直接跟在名字后面、
     *   **不带「昵称：」前缀**（用户 2026-10-06 定：`喷火龙（阿黄）` 这样读最顺）；
     *   悬停窗 / 弹窗里那一行**仍走 `EntryBadgeRenderer.nicknameLine`**（带前缀、独占一行）。
     * ⚠ 括号走**词条**（中文全角 `（%s）` / 英文半角 ` (%s)`）—— 服务端拼不了语言相关的标点
     *   （服务端语言 ≠ 客户端语言，见 CLAUDE.md）。
     * ⚠⭐ **颜色 = 白色，整段（括号 + 昵称）一起**（用户 2026-10-06 定的口径）：
     *   锚点是**悬停窗里那个「昵称值白色」** —— 「昵称是白的」在所有地方一致；
     *   名字本体保持**自己的属性色**（属性色只属于「物种名」），不能让昵称也吃它，
     *   否则悬停窗 / 弹窗那两处就得跟着改成属性色、把已定的口径推翻。
     *   ⚠ 若只给 `%s` 参数设色，**括号会留在行色**（词条那一段自己没设色 ⇒ 继承整条播报的主体蓝，
     *   2026-10-06 用户截图报「括号和昵称为什么是蓝色」）⇒ 必须设在**整段词条**上。
     * ⚠⭐ 调用方必须把返回值**放进挂了 clickEvent 的那一段里**（悬停识别靠它，见 `ChatObjectPreview`）——
     *   昵称若是裸字，鼠标从名字挪到昵称会**闪一下重出 + 再叫一声**（2026-10-06 实测踩过同款坑）。
     */
    fun withNickname(name: Text, nickname: String?): Text =
        nickname?.takeIf { it.isNotBlank() }
            ?.let {
                Text.literal("")
                    .append(name)
                    .append(
                        Text.translatable("cobblemarket.chat.nickname_paren", Text.literal(it))
                            .formatted(net.minecraft.util.Formatting.WHITE)
                    )
            }
            ?: name
}
