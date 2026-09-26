package com.shusheng.cobblemarket.util

import net.minecraft.nbt.NbtCompound

/**
 * CSV 交易账本「详情」列的构建：精灵完整数值 / 物品 NBT 文本化。
 * 跨语言紧凑格式（中英两份 CSV 内容一致），字段顺序：
 * 精灵：lv=50|shiny=Y|ivs=31/31/31/31/31/31|ht=HP,ATK|nature=timid|base=jolly|ability=blaze|gender=MALE|ball=cobblemon:poke_ball|held=cobblemon:leftovers|form=...
 * 物品：count=5|nbt={...}
 * 缺失/无意义字段整段省略；nature/base/ability 去掉翻译 key 前缀保留英文名。
 */
object RecordDetail {

    private val STAT_KEYS = listOf("ivsHp", "ivsAtk", "ivsDef", "ivsSpAtk", "ivsSpDef", "ivsSpd")
    private val EV_KEYS = listOf("evsHp", "evsAtk", "evsDef", "evsSpAtk", "evsSpDef", "evsSpd")
    private val HT_KEYS = listOf(
        "htHp" to "HP", "htAtk" to "ATK", "htDef" to "DEF",
        "htSpAtk" to "SPA", "htSpDef" to "SPD", "htSpd" to "SPE"
    )

    fun pokemon(extraData: Map<String, String>, level: Int, shiny: Boolean): String {
        val parts = mutableListOf<String>()
        parts.add("lv=$level")
        parts.add("shiny=" + if (shiny) "Y" else "N")
        parts.add("ivs=" + STAT_KEYS.joinToString("/") { extraData[it] ?: "?" })
        parts.add("evs=" + EV_KEYS.joinToString("/") { extraData[it] ?: "?" })
        val ht = HT_KEYS.mapNotNull { (key, abbr) ->
            (extraData[key]?.toIntOrNull() ?: -1).takeIf { it >= 0 }?.let { "$abbr:$it" }
        }
        if (ht.isNotEmpty()) parts.add("ht=" + ht.joinToString(","))
        val nature = extraData["nature"]?.stripPrefix()
        if (!nature.isNullOrEmpty()) parts.add("nature=$nature")
        val base = extraData["natureBase"]?.stripPrefix()
        if (!base.isNullOrEmpty() && base != nature) parts.add("base=$base")
        val ability = extraData["ability"]?.stripPrefix()
        if (!ability.isNullOrEmpty()) parts.add("ability=$ability")
        val gender = extraData["gender"]
        if (!gender.isNullOrEmpty()) parts.add("gender=$gender")
        val ball = extraData["ballItem"]
        if (!ball.isNullOrEmpty()) parts.add("ball=$ball")
        // 携带物（heldItemId 为空 = 无携带物，省略整段）
        val held = extraData["heldItemId"]
        if (!held.isNullOrEmpty()) parts.add("held=$held")
        val form = extraData["aspects"]
        if (!form.isNullOrEmpty()) parts.add("form=$form")
        return parts.joinToString("|")
    }

    /** 物品附魔等级表（NBT components.minecraft:enchantments.levels → id:等级；无附魔/解析失败为空表） */
    fun enchantmentLevels(itemNbt: NbtCompound?): Map<String, Int> {
        val nbt = itemNbt ?: return emptyMap()
        val components = try { nbt.getCompound("components") } catch (e: Exception) { return emptyMap() }
        if (!components.contains("minecraft:enchantments")) return emptyMap()
        val ench = try { components.getCompound("minecraft:enchantments") } catch (e: Exception) { return emptyMap() }
        if (!ench.contains("levels")) return emptyMap()
        val levels = try { ench.getCompound("levels") } catch (e: Exception) { return emptyMap() }
        return levels.keys.associateWith { levels.getInt(it) }
    }

    fun item(itemNbt: NbtCompound?, count: Int?, registries: net.minecraft.registry.DynamicRegistryManager): String {
        val parts = mutableListOf<String>()
        if (count != null) parts.add("count=$count")
        // 附魔摘要（可读，英文 key 形式与 ivs/nature 字段风格一致）；NBT 全文仍保留（精确复刻）
        val ench = enchantmentLevels(itemNbt)
        if (ench.isNotEmpty()) parts.add("ench=" + ench.entries.joinToString(",") { "${it.key}:${it.value}" })
        if (itemNbt != null && !itemNbt.isEmpty) parts.add("nbt=${itemNbt.toString()}")
        // 稀有度（**历史界面的物品名上色用**，2026-09-26 加）：界面那边只有 itemId，
        // 拿 `defaultStack` 取不到「附魔把稀有度顶上去」那一档（下界合金剑默认 COMMON、
        // 附了魔在物品栏里是黄的 → 历史里永远显示白）。这里手上还有 nbt，就地算好带上。
        rarityOf(registries, itemNbt)?.let { parts.add("rarity=$it") }
        return parts.joinToString("|")
    }

    /**
     * 从详情列里取出 [item] 写进去的稀有度段（`RARE` 这种枚举名）；旧记录没这段 → 空串。
     * 给历史界面用 —— 它只收这一个短字符串，**不把整个详情列塞进 payload**。
     */
    fun rarityFromDetail(detail: String): String =
        detail.split("|").firstOrNull { it.startsWith("rarity=") }?.removePrefix("rarity=").orEmpty()

    /**
     * 这件物品的**稀有度枚举名**（`RARE` 这种），取不到返回 null（整段省略）。
     *
     * ⚠ **重建完整栈、直接读 `stack.rarity`** —— 这与项目里那个唯一入口
     * [com.shusheng.cobblemarket.util.ItemNameText.rarityColor]（`stack.rarity.formatting.colorValue`）
     * **同一个来源**，物品市场 / 购买弹窗 / 球种行全都是这么取色的。
     *
     * ⚠⚠ **我在这里栽过一次，两次都错在同一个念头**（2026-09-26 用户实测连抓两条）：
     * 第一版为了"不动 8 个调用点"，**放着现成入口不用**、改去手推稀有度规则 ⇒ 结果照了**旧版** MC 的
     * 「COMMON + 附魔 → UNCOMMON」，把附魔的下界合金剑算成**黄**（实际是青 RARE）。
     * 用户原话：「我们模组别的地方处处都有显示物品稀有度颜色名的代码，你偏偏自己又去翻源码」。
     * **判据**：凡是「游戏里已经存在的一个值」，先 grep 项目里有没有现成入口 ——
     * **有就复用，别自己算**；实在要自己算，才去抄反编译源码。
     */
    private fun rarityOf(registries: net.minecraft.registry.DynamicRegistryManager, itemNbt: NbtCompound?): String? {
        return try {
            val stack = itemNbt?.let { net.minecraft.item.ItemStack.fromNbtOrEmpty(registries, it) }
                ?: return null
            if (stack.isEmpty) return null
            stack.rarity.name
        } catch (e: Exception) {
            null
        }
    }

    private fun String.stripPrefix(): String =
        removePrefix("cobblemon.nature.").removePrefix("cobblemon.ability.")
}
