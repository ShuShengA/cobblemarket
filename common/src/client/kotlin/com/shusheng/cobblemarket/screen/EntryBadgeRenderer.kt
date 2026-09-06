package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.network.ListingEntry
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

// 市场条目的完整信息行渲染（与市场列表悬停 tooltip 结构一致），居中排版：
// 名字★Lv / 类型 / 性格+特性 / 携带物(图标) / IV 六项 / 卖家 / 价格
// 购买/下架确认弹窗、购买确认页、管理员下架弹窗共用。
object EntryBadgeRenderer {

    /** 属性色表（2026-08-24 颜色模板；全模组精灵名/属性名显示统一用，见 [[pokemon-display-colors]]） */
    fun typeColor(typeKey: String): Int = when (typeKey.substringAfterLast(".").lowercase()) {
        "normal" -> 0xAAAA99; "fire" -> 0xFF4422; "water" -> 0x3399FF
        "electric" -> 0xFFCC33; "grass" -> 0x77CC55; "ice" -> 0x66CCFF
        "fighting" -> 0xBB5544; "poison" -> 0xAA5599; "ground" -> 0xDDBB55
        "flying" -> 0x8899FF; "psychic" -> 0xFF5599; "bug" -> 0xAABB22
        "rock" -> 0xBBAA66; "ghost" -> 0x6666BB; "dragon" -> 0x7766EE
        "dark" -> 0x775544; "steel" -> 0xAAAABB; "fairy" -> 0xFFAAFF
        else -> 0xFFFFFF
    }

    /** 类型行属性名分段着色：主属性名主色、「+ 副属性名」副色（标签文字由调用方拼接，默认白） */
    fun typeLine(primaryType: String, secondaryType: String): Text {
        val primary = Text.translatable(primaryType)
            .setStyle(net.minecraft.text.Style.EMPTY.withColor(typeColor(primaryType)))
        return if (secondaryType.isNotEmpty()) {
            primary.append(Text.literal(" + ")).append(
                Text.translatable(secondaryType)
                    .setStyle(net.minecraft.text.Style.EMPTY.withColor(typeColor(secondaryType)))
            )
        } else primary
    }

    /** 名称 + 金色闪光星标（★），非闪光不追加；Text 内嵌样式在绘制时按段渲染 */
    fun nameWithShinyStar(name: String, shiny: Boolean): Text =
        if (shiny) Text.literal(name).append(Text.literal(" ★").formatted(Formatting.GOLD))
        else Text.literal(name)

    /**
     * 性格显示文本（照 Cobblemon 薄荷约定）：
     * 未用薄荷 = 生效性格正常显示；用过薄荷 = 斜体原生性格（括号生效性格+薄荷后缀），如 *胆小*（大胆薄荷）。
     * baseKey/effKey 为性格翻译 key；baseKey 为空或与 effKey 相同视为未用薄荷。
     */
    fun natureText(baseKey: String, effKey: String): Text {
        if (baseKey.isEmpty() || baseKey == effKey) return Text.translatable(effKey)
        return Text.literal("")
            .append(Text.translatable(baseKey).formatted(Formatting.ITALIC))
            .append(Text.literal("（"))
            .append(Text.translatable(effKey))
            .append(Text.translatable("cobblemarket.gui.nature_mint"))
            .append(Text.literal("）"))
    }

    /**
     * 左对齐名字行：名字文本 + 右侧公母图标（♂蓝/♀红，与市场行内同款）。
     * 所有精灵详情面板的第一行统一走此函数（居中场景先算总宽再调本函数）。
     */
    fun drawNameLineLeft(context: DrawContext, name: Text, gender: String, x: Int, y: Int, color: Int = 0xFFFFFF) {
        val font = MinecraftClient.getInstance().textRenderer
        context.drawTextWithShadow(font, name, x, y, color)
        if (gender != "MALE" && gender != "FEMALE") return
        val gi = if (gender == "MALE")
            Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
        else
            Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
        com.cobblemon.mod.common.api.gui.blitk(
            matrixStack = context.matrices, texture = gi,
            x = x + font.getWidth(name) + 2, y = y, width = 6, height = 8
        )
    }

    /** 居中名字行：名字 + 公母图标整体居中（无性别信息时与普通居中文本一致） */
    fun drawNameLine(context: DrawContext, name: Text, gender: String, centerX: Int, y: Int, color: Int = 0xFFFFFF) {
        val font = MinecraftClient.getInstance().textRenderer
        if (gender != "MALE" && gender != "FEMALE") {
            context.drawCenteredTextWithShadow(font, name, centerX, y, color)
            return
        }
        val totalW = font.getWidth(name) + 14
        drawNameLineLeft(context, name, gender, centerX - totalW / 2, y, color)
    }

    // 居中绘制信息行，返回下一行的 y
    fun drawInfoLines(context: DrawContext, entry: ListingEntry, displayName: Text, centerX: Int, startY: Int): Int {
        val font = MinecraftClient.getInstance().textRenderer
        val hp = Text.translatable("cobblemon.stat.hp.name").string
        val atk = Text.translatable("cobblemon.stat.attack.name").string
        val def = Text.translatable("cobblemon.stat.defence.name").string
        val spa = Text.translatable("cobblemon.stat.special_attack.name").string
        val spd = Text.translatable("cobblemon.stat.special_defence.name").string
        val spe = Text.translatable("cobblemon.stat.speed.name").string

        val hasHeldItem = entry.heldItemId.isNotEmpty() &&
            Identifier.tryParse(entry.heldItemId)
                ?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true

        // 行列表：null = 分割线（1px 灰线）；证章行 = 图标行（marksLine 索引处画图标）
        val lines = mutableListOf<Pair<Text?, Int>>()
        // 名字行 = 主属性色；Lv 段由渲染行统一着色
        lines.add(displayName.copy().append(Text.literal("  ${Text.translatable("cobblemarket.gui.lv").string}${entry.level}")) to typeColor(entry.primaryType))
        // 类型行：标签默认白 + 属性名分段着色（主属性主色/副属性副色）
        lines.add(
            Text.literal(Text.translatable("cobblemarket.gui.tooltip_type").string)
                .append(typeLine(entry.primaryType, entry.secondaryType)) to 0xFFFFFF
        )
        lines.add(
            Text.literal(Text.translatable("cobblemarket.gui.tooltip_nature").string)
                .append(natureText(entry.natureBase, entry.nature))
                .append(Text.literal("  ${Text.translatable("cobblemarket.gui.tooltip_ability").string}"))
                .append(Text.translatable(entry.ability)) to 0xFFFFFF
        )
        if (entry.ball.isNotEmpty()) {
            lines.add(Text.literal("${Text.translatable("cobblemarket.gui.tooltip_ball").string}${Text.translatable(entry.ball).string}") to 0xFFFFFF)
        }
        var heldItemLine = -1
        if (hasHeldItem) {
            heldItemLine = lines.size
            lines.add(Text.translatable("cobblemarket.gui.tooltip_held") to 0xFFFFFF)
        }
        lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs") to 0xFFFFFF)
        lines.add(Text.literal("  $hp:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsHp, entry.htHp)}").append(Text.literal("  EV:${entry.evsHp}").formatted(Formatting.RED)) to 0x66FF66)
        lines.add(Text.literal("  $atk:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsAtk, entry.htAtk)}").append(Text.literal("  EV:${entry.evsAtk}").formatted(Formatting.RED)) to 0xFF6666)
        lines.add(Text.literal("  $def:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsDef, entry.htDef)}").append(Text.literal("  EV:${entry.evsDef}").formatted(Formatting.RED)) to 0xFFCC66)
        lines.add(Text.literal("  $spa:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpAtk, entry.htSpAtk)}").append(Text.literal("  EV:${entry.evsSpAtk}").formatted(Formatting.RED)) to 0x6699FF)
        lines.add(Text.literal("  $spd:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpDef, entry.htSpDef)}").append(Text.literal("  EV:${entry.evsSpDef}").formatted(Formatting.RED)) to 0x66FF99)
        lines.add(Text.literal("  $spe:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpd, entry.htSpd)}").append(Text.literal("  EV:${entry.evsSpd}").formatted(Formatting.RED)) to 0xFF99FF)
        lines.add(Text.translatable("cobblemarket.gui.friendship", entry.friendship) to 0xFF99CC)
        // 证章区块（证章不影响能力，纯外观展示）：亲密度下方两条分割线夹证章图标（每行 6 个，照 Cobblemon 摘要界面）
        // 服务端直接传纹理路径（不依赖客户端 Marks 注册表解析）
        var marksLine = -1
        if (entry.marks.isNotEmpty()) {
            lines.add(null to 0)
            marksLine = lines.size
            lines.add(null to 0)
            lines.add(null to 0)
        }
        lines.add(
            Text.translatable("cobblemarket.gui.tooltip_seller").append(" ").append(Text.literal(entry.sellerName)) to 0xFFFFFF
        )
        lines.add(
            Text.translatable("cobblemarket.gui.tooltip_price").append(" ").append(
                Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ${com.shusheng.cobblemarket.client.displayCurrency(entry.currencyName)}").formatted(Formatting.GOLD)) to 0xFFFFFF
        )

        var y = startY
        var i = 0
        while (i < lines.size) {
            val (line, color) = lines[i]
            when {
                line == null && i == marksLine -> {
                    // 证章图标行：第一行最多 6 个居中；超过 6 个第二行居中显示「+N」（不再画图标防挤占面板）
                    val textures = entry.marks.mapNotNull { Identifier.tryParse(it) }
                    val rowW = minOf(6, textures.size) * 12 - 4
                    textures.take(6).forEachIndexed { idx, texture ->
                        com.cobblemon.mod.common.api.gui.blitk(
                            matrixStack = context.matrices, texture = texture,
                            x = centerX - rowW / 2 + idx * 12, y = y, width = 8, height = 8
                        )
                    }
                    if (textures.size > 6) {
                        context.drawCenteredTextWithShadow(font, "+${textures.size - 6}", centerX, y + 12, 0xAAAAAA)
                        y += 24
                    } else {
                        y += 12
                    }
                }
                line == null -> {
                    // 分割线：1px 灰线，宽度只包住证章图标行（首行证章数 × 12 - 4）
                    val rowW = minOf(6, entry.marks.size) * 12 - 4
                    context.fill(centerX - rowW / 2, y + 4, centerX + rowW / 2, y + 5, 0xFF555555.toInt())
                    y += 10
                }
                i == heldItemLine -> {
                    // 携带物行：文字 + 物品图标整体居中
                    val textW = font.getWidth(line)
                    val x = centerX - (textW + 14) / 2
                    context.drawTextWithShadow(font, line, x, y, color)
                    Identifier.tryParse(entry.heldItemId)?.let { heldId ->
                        com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                            itemStack = ItemStack(Registries.ITEM.get(heldId)),
                            x = (x + textW + 2).toDouble(), y = y.toDouble(), scale = 0.6, matrixStack = context.matrices
                        )
                    }
                    y += 10
                }
                i == 0 -> {
                    // 第一行（名字★Lv）走公共名字行函数（带公母图标）
                    drawNameLine(context, line, entry.gender, centerX, y, color)
                    y += 10
                }
                else -> {
                    context.drawCenteredTextWithShadow(font, line, centerX, y, color)
                    y += 10
                }
            }
            i++
        }
        return y
    }
}
