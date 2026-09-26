package com.shusheng.cobblemarket.util

import net.minecraft.text.Text
import net.minecraft.text.TextColor
import net.minecraft.util.Formatting

/**
 * 属性类型 → 聊天文本颜色（与客户端 typeColor 映射一致，服务端聊天消息用——如拍卖播报的物种名属性色）。
 * 属性色是界面语言无关的视觉惯例，服务端构造 Text 时直接用 ARGB。
 */
object TypeTextColors {
    fun color(typeKey: String): TextColor = TextColor.fromRgb(
        when (typeKey.substringAfterLast(".").lowercase()) {
            "normal" -> 0xAAAA99; "fire" -> 0xFF4422; "water" -> 0x3399FF
            "electric" -> 0xFFCC33; "grass" -> 0x77CC55; "ice" -> 0x66CCFF
            "fighting" -> 0xBB5544; "poison" -> 0xAA5599; "ground" -> 0xDDBB55
            "flying" -> 0x8899FF; "psychic" -> 0xFF5599; "bug" -> 0xAABB22
            "rock" -> 0xBBAA66; "ghost" -> 0x6666BB; "dragon" -> 0x7766EE
            "dark" -> 0x775544; "steel" -> 0xAAAABB; "fairy" -> 0xFFAAFF
            else -> 0xFFFFFF
        }
    )

    /**
     * **属性行**的完整组装 —— `属性：主 [+ 副] [（太晶：x）]`，界面与服务端聊天**共用这一份**。
     *
     * ⚠ 别各拼各的：这一行在**七处**各有一份拷贝（拍卖 / 管理端拍卖的悬停窗与弹窗、求购单悬停窗、
     *   服务端的拍卖播报与求购交付悬停），太晶那段稍不留神就漏一处。2026-09-26 收口到这里。
     *
     * @param teraType 太晶属性翻译 key；**空串 = 没有**（整段不画）——
     *   官方 `Pokemon.teraType` 默认就是主属性，服务端只在 **≠ 主属性** 时才下发，
     *   所以「没装太晶附属模组」的玩家看到的东西与以前完全一样
     */
    fun typeLineWithTera(primaryType: String, secondaryType: String, teraType: String): Text {
        val line = Text.literal(
            net.minecraft.text.Text.translatable("cobblemarket.gui.tooltip_type").string
        ).append(typeLineOf(primaryType, secondaryType))
        if (teraType.isNotEmpty()) {
            line.append(
                net.minecraft.text.Text.translatable(
                    "cobblemarket.gui.tooltip_tera",
                    net.minecraft.text.Text.translatable(teraType).styled { it.withColor(color(teraType)) }
                )
            )
        }
        return line
    }

    /**
     * 主 + 副属性的分段着色（「主 + 副」，各段自己的色）。
     * ⚠ 三段**平级**挂在空节点下 —— 带色的段不能当父节点（MC 会把父节点样式传给每段 append）。
     */
    private fun typeLineOf(primaryType: String, secondaryType: String): Text {
        val line = Text.literal("")
        line.append(Text.translatable(primaryType).styled { it.withColor(color(primaryType)) })
        if (secondaryType.isNotEmpty()) {
            line.append(Text.literal(" + "))
            line.append(Text.translatable(secondaryType).styled { it.withColor(color(secondaryType)) })
        }
        return line
    }

    /**
     * 物种资源 id → 属性翻译 key（`cobblemon.type.xxx`）；[primary] = false 时取副属性。
     * 解析不出 / 该物种没有对应属性时返回**空串**（调用方按"没有这条"处理）。
     *
     * ⚠ 这段解析此前只存在于 `BuyOrderNetwork`（文件级 private），于是 `BuyOrderState.requirementText()`
     *   想给精灵名上色时**拿不到主属性** —— 它的**物品分支有**稀有度色、**精灵分支却一直是白字**
     *   （同一个函数两条分支口径不一致），5 条聊天通知跟着一起白。2026-09-26 提到这里共用。
     */
    fun typeKeyOf(speciesId: String?, primary: Boolean): String {
        val id = speciesId?.let { net.minecraft.util.Identifier.tryParse(it) } ?: return ""
        val sp = com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getByIdentifier(id) ?: return ""
        val t = if (primary) sp.primaryType else (sp.secondaryType ?: return "")
        return "cobblemon.type.${t.name.lowercase()}"
    }

    /**
     * 精灵名的聊天展示文本：名字本体带主属性色，闪光再加前置金★。
     *
     * 服务端凡是「聊天消息里出现精灵名」都走这里（拍卖上架/成交播报、售出通知、分享……），
     * 免得各写一份、有的上色有的漏（2026-09-19 统一）。
     *
     * @param speciesText 名字本体（翻译 Text，客户端按玩家语言渲染）
     * @param primaryTypeKey 主属性 key；缺失/非法时 [color] 自己会落白色
     */
    fun speciesName(speciesText: Text, primaryTypeKey: String?, shiny: Boolean): Text =
        withShinyStar(
            Text.literal("").append(speciesText)
                .styled { it.withColor(color(primaryTypeKey ?: "cobblemon.type.normal")) },
            shiny
        )

    /**
     * 闪光精灵名加前置金★（非闪光原样返回）。
     *
     * 名字本体**已经带色**时用这个 —— 别再走 [speciesName] 重复上色：挂单/拍卖的
     * `speciesText()` 现在自己就带属性色，播报与通知拿到的都是带色名字。
     */
    fun withShinyStar(nameText: Text, shiny: Boolean): Text =
        if (shiny) Text.literal("").append(Text.literal("★ ").formatted(Formatting.GOLD)).append(nameText)
        else nameText
}
