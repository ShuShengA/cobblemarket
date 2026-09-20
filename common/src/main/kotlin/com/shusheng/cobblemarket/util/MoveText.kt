package com.shusheng.cobblemarket.util

import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.DataKeys
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.text.Text

/**
 * **技能块的共享文本层** —— 双端可用（服务端拼聊天悬停也要用它，所以**不能**放 `common/src/client`）。
 *
 * 数据形态：**4 个槽位按顺序用「,」连接、空槽是空串**，形如 `thunderbolt,quickattack,,`
 * （取自 `MoveSet.getMovesWithNulls()`，**保留槽位**；`extraData["moves"]` 与 `ListingEntry.moves` 都存这一串）。
 *
 * ⚠ 客户端渲染层 [com.shusheng.cobblemarket.screen.EntryBadgeRenderer.drawMovesBlock] 与它**同源**
 * （两行文本由本文件的 [rows] 产出），改格式两处一起改。
 */
object MoveText {

    /** Cobblemon 一只精灵固定 4 个技能槽 */
    const val SLOTS = 4

    /** 悬停窗固定两行、一行两个 */
    const val PER_ROW = 2

    /**
     * 同一行两个技能名之间的间隔（**纯文本用空格**）。
     * GUI 那边直接用 [rows] 产出的整行文本居中画，所以两边的间隔天然一致、不会各写一份。
     */
    const val ROW_SEPARATOR = "    "

    /** 技能槽名列表 → 存串（空槽 = 空串）；列表不足 4 个时后面补空 */
    fun encode(names: List<String?>): String =
        (0 until SLOTS).joinToString(",") { names.getOrNull(it).orEmpty() }

    /** 从精灵身上取存串（服务端构造快照用，三处 extraData 构造点都走它） */
    fun of(pokemon: Pokemon): String =
        encode(pokemon.moveSet.getMovesWithNulls().map { it?.template?.name })

    /**
     * 取技能串：**优先用快照，缺失时从精灵 NBT 现读**。
     *
     * 用途：**升级上来的老挂单 / 老拍卖没有 `moves` 快照**（快照是上架那一刻记的），
     * 靠这条回退也能显示技能 —— 否则服主升级后要玩家把市场里的挂单挨个重新上架。
     * 挂单本来就存着精灵完整 NBT（`pokemonNbt`），读它几乎不要钱，随挂单流转自然弃用。
     */
    fun resolve(raw: String?, pokemonNbt: NbtCompound?): String =
        if (!raw.isNullOrBlank()) raw else fromNbt(pokemonNbt)

    /**
     * 从精灵 NBT 里读技能槽 —— ⚠ **只读字符串，不构造 Pokemon**（列表每次请求都会走这里，必须便宜）。
     * NBT 里只存**已会的招**（没有空槽），所以取出来可能不足 4 个，[encode] 会自动补空。
     * 缺失 / 类型不对 / 任何异常一律返回空串（读取容错，绝不抛）。
     */
    fun fromNbt(pokemonNbt: NbtCompound?): String {
        val nbt = pokemonNbt ?: return ""
        return runCatching {
            val list = nbt.getList(DataKeys.POKEMON_MOVESET, NbtElement.COMPOUND_TYPE.toInt())
            val names = (0 until list.size).mapNotNull { i ->
                (list[i] as? NbtCompound)
                    ?.getString(DataKeys.POKEMON_MOVESET_MOVENAME)
                    ?.takeIf { it.isNotBlank() }
            }
            encode(names)
        }.getOrDefault("")
    }

    /** 存串 → 恰好 [SLOTS] 个槽的 id（空槽 = 空串）；**无数据 / 旧数据返回空列表** */
    fun slotIds(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val parts = raw.split(",")
        return (0 until SLOTS).map { parts.getOrNull(it)?.trim().orEmpty() }
    }

    /**
     * 有没有可显示的技能。
     * 没有 = 旧数据 / 未快照（Cobblemon 里不存在没技能的精灵）⇒ 悬停窗**整块不画**。
     */
    fun hasAny(raw: String?): Boolean = slotIds(raw).any { it.isNotEmpty() }

    /**
     * 槽位 id → 本地化名字，**按技能属性着色**（2026-09-20 用户建议）。
     * 颜色走双端共用的 [TypeTextColors] —— 与精灵/形式的属性色同一张表，不另抄一份。
     * 查不到就原样显示 id（白字），**绝不崩**。
     */
    fun name(id: String): Text {
        val template = Moves.getByName(id) ?: return Text.literal(id)
        return template.displayName.styled { it.withColor(TypeTextColors.color(template.elementalType.name)) }
    }

    /**
     * 两行、每行**一对槽位 id**（空槽 = 空串）—— **GUI 渲染层按两列网格排版用**：
     * 左列在左半区居中、右列在右半区居中，这样名字长短不同的两行也能对齐（2026-09-20 用户实测反馈：
     * 原先「整行左对齐 + 固定空格间隔」在四个技能、名字长度不一时排得很乱）。
     */
    fun rowsOfIds(raw: String?): List<Pair<String, String>> {
        val ids = slotIds(raw)
        return (0 until SLOTS / PER_ROW).map { row ->
            ids.getOrNull(row * PER_ROW).orEmpty() to ids.getOrNull(row * PER_ROW + 1).orEmpty()
        }
    }

    /**
     * 两行文本，每行两个技能名（空槽留白）—— **聊天悬停用**（纯文本排不了两列，只能用固定间隔近似）。
     * 一行为空时返回空 Text（不画）；只有一侧有技能时不带间隔。
     */
    fun rows(raw: String?): List<Text> {
        val ids = slotIds(raw)
        return (0 until SLOTS / PER_ROW).map { row ->
            val left = ids.getOrNull(row * PER_ROW).orEmpty()
            val right = ids.getOrNull(row * PER_ROW + 1).orEmpty()
            when {
                left.isEmpty() && right.isEmpty() -> Text.empty()
                left.isEmpty() -> name(right)
                right.isEmpty() -> name(left)
                else -> Text.empty().append(name(left))
                    .append(Text.literal(ROW_SEPARATOR))
                    .append(name(right))
            }
        }
    }
}
