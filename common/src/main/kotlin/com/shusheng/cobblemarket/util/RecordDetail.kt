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

    fun item(itemNbt: NbtCompound?, count: Int?): String {
        val parts = mutableListOf<String>()
        if (count != null) parts.add("count=$count")
        // 附魔摘要（可读，英文 key 形式与 ivs/nature 字段风格一致）；NBT 全文仍保留（精确复刻）
        val ench = enchantmentLevels(itemNbt)
        if (ench.isNotEmpty()) parts.add("ench=" + ench.entries.joinToString(",") { "${it.key}:${it.value}" })
        if (itemNbt != null && !itemNbt.isEmpty) parts.add("nbt=${itemNbt.toString()}")
        // 稀有度（**历史界面的物品名上色用**，2026-09-26 加）：界面那边只有 itemId，
        // 拿 `defaultStack` 取不到「附魔把稀有度顶上去」那一档（下界合金剑默认 COMMON、
        // 附了魔在物品栏里是黄的 → 历史里永远显示白）。这里手上还有 nbt，就地算好带上。
        rarityOf(itemNbt, ench)?.let { parts.add("rarity=$it") }
        return parts.joinToString("|")
    }

    /**
     * 从详情列里取出 [item] 写进去的稀有度段（`RARE` 这种枚举名）；旧记录没这段 → 空串。
     * 给历史界面用 —— 它只收这一个短字符串，**不把整个详情列塞进 payload**。
     */
    fun rarityFromDetail(detail: String): String =
        detail.split("|").firstOrNull { it.startsWith("rarity=") }?.removePrefix("rarity=").orEmpty()

    /**
     * 这件物品的**稀有度枚举名**，取不到返回 null（整段省略）。
     *
     * 口径照原版 `ItemStack.getRarity()`：物品自身的稀有度，**若为 COMMON 且带附魔则升为 UNCOMMON**
     *（原版就是这么算的 —— 所以「物品栏里是黄的、历史里是白的」才会对不上）。
     *
     * ⚠ **只用静态注册表 + NBT，不需要 registries** ⇒ [item] 的 8 个调用点一行都不用改
     *（这是个有意的取舍：另开 CSV 列要动 8 个产生点 + 读写两端，收益一样）。
     */
    private fun rarityOf(itemNbt: NbtCompound?, ench: Map<String, Int>): String? {
        return try {
            val id = itemNbt?.getString("id")?.takeIf { it.isNotEmpty() } ?: return null
            val item = net.minecraft.util.Identifier.tryParse(id)
                ?.let { net.minecraft.registry.Registries.ITEM.get(it) }
                ?: return null
            if (item == net.minecraft.item.Items.AIR) return null
            // 稀有度从**默认栈**上取（`Item.rarity` 在 1.21.1 不是能直接摸的成员 —— 编译报 unresolved；
            // 与 `ItemNameText.rarityColor` 同一个取法，那处是验证过的）
            val base = item.defaultStack.rarity
            (if (base == net.minecraft.util.Rarity.COMMON && ench.isNotEmpty())
                net.minecraft.util.Rarity.UNCOMMON else base).name
        } catch (e: Exception) {
            null
        }
    }

    private fun String.stripPrefix(): String =
        removePrefix("cobblemon.nature.").removePrefix("cobblemon.ability.")
}
