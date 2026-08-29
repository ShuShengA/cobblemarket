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

    fun item(itemNbt: NbtCompound?, count: Int?): String {
        val parts = mutableListOf<String>()
        if (count != null) parts.add("count=$count")
        if (itemNbt != null && !itemNbt.isEmpty) parts.add("nbt=${itemNbt.toString()}")
        return parts.joinToString("|")
    }

    private fun String.stripPrefix(): String =
        removePrefix("cobblemon.nature.").removePrefix("cobblemon.ability.")
}
