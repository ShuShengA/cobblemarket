package com.shusheng.cobblemarket.util

import net.minecraft.text.Text

/**
 * 六项能力的短名（个体值 / 努力值各行左侧的标签）。
 *
 * **为什么不用 Cobblemon 的原文**：它的英文是「Sp. Atk」「Sp. Def」——**点后面带一个空格**，
 * 而 MC 的字体里 `.` 只占 2px、空格占 4px，跟在字母后看起来像空了一大块（2026-09-12 用户反馈）。
 * 这里把「. 」整个去掉（`SpAtk` / `SpDef`）；中文简称（特攻 / 特防）不含点，天然不受影响。
 *
 * 取值走 [Text.translatable]，不缓存：语言可在运行中切换，各界面原有的缓存策略保持不变。
 */
object StatNames {
    val HP: String get() = of("cobblemon.stat.hp.name")
    val ATK: String get() = of("cobblemon.stat.attack.name")
    val DEF: String get() = of("cobblemon.stat.defence.name")
    val SP_ATK: String get() = of("cobblemon.stat.special_attack.name")
    val SP_DEF: String get() = of("cobblemon.stat.special_defence.name")
    val SPEED: String get() = of("cobblemon.stat.speed.name")

    /** 取一个属性短名并去掉「. 」（供上面几个常量复用，服务端拼 Text 时也可直接调） */
    fun of(key: String): String = Text.translatable(key).string.replace(". ", "")
}
