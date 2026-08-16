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

    /** 名称 + 金色闪光星标（★），非闪光不追加；Text 内嵌样式在绘制时按段渲染 */
    fun nameWithShinyStar(name: String, shiny: Boolean): Text =
        if (shiny) Text.literal(name).append(Text.literal(" ★").formatted(Formatting.GOLD))
        else Text.literal(name)

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

        val lines = mutableListOf<Pair<Text, Int>>()
        lines.add(displayName.copy().append(Text.literal("  ${Text.translatable("cobblemarket.gui.lv").string}${entry.level}")) to 0xFFFFFF)
        lines.add(
            Text.literal("${Text.translatable("cobblemarket.gui.tooltip_type").string}${Text.translatable(entry.primaryType).string}" +
                (if (entry.secondaryType.isNotEmpty()) " + ${Text.translatable(entry.secondaryType).string}" else "")) to 0xFFFFFF
        )
        lines.add(
            Text.literal("${Text.translatable("cobblemarket.gui.tooltip_nature").string}${Text.translatable(entry.nature).string}  " +
                "${Text.translatable("cobblemarket.gui.tooltip_ability").string}${Text.translatable(entry.ability).string}") to 0xFFFFFF
        )
        var heldItemLine = -1
        if (hasHeldItem) {
            heldItemLine = lines.size
            lines.add(Text.translatable("cobblemarket.gui.tooltip_held") to 0xFFFFFF)
        }
        lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs") to 0xFFFFFF)
        lines.add(Text.literal("  $hp:${entry.ivsHp}") to 0x66FF66)
        lines.add(Text.literal("  $atk:${entry.ivsAtk}") to 0xFF6666)
        lines.add(Text.literal("  $def:${entry.ivsDef}") to 0xFFCC66)
        lines.add(Text.literal("  $spa:${entry.ivsSpAtk}") to 0x6699FF)
        lines.add(Text.literal("  $spd:${entry.ivsSpDef}") to 0x66FF99)
        lines.add(Text.literal("  $spe:${entry.ivsSpd}") to 0xFF99FF)
        lines.add(
            Text.literal("${Text.translatable("cobblemarket.gui.tooltip_seller").formatted(Formatting.GRAY).string} ${entry.sellerName}") to 0xFFFFFF
        )
        lines.add(
            Text.literal("${Text.translatable("cobblemarket.gui.tooltip_price").formatted(Formatting.GRAY).string} " +
                "${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ${entry.currencyName}") to 0xFFFFFF
        )

        var y = startY
        lines.forEachIndexed { i, (line, color) ->
            if (i == heldItemLine) {
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
            } else {
                context.drawCenteredTextWithShadow(font, line, centerX, y, color)
            }
            y += 10
        }
        return y
    }
}
