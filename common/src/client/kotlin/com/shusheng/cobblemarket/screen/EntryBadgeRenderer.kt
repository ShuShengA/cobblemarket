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

    /**
     * 证章图标每行个数（2026-09-09 用户拍板 6 → 10）：一行 10 个 = 120px，
     * 仍窄于提示框最宽文本行（价格/性格特性行约 130~140px），不会把提示框撑宽。
     * 全部证章排布（内联渲染 + 公共函数）统一引用本常量，调整只改这里。
     */
    const val MARKS_PER_ROW = 10

    /**
     * 证章区块高度：上下分割线各 10 + 图标行（drawMarksRow 返回 12 或 24），即一行 32、两行 44，无证章为 0。
     * 各弹窗按内容算高度时引用（改证章排布只改这里，不会与渲染分叉）。
     */
    fun marksBlockHeight(marks: List<String>): Int =
        if (marks.isEmpty()) 0 else if (marks.size > MARKS_PER_ROW) 44 else 32

    /**
     * 是否显示携带物行（空 id 或解析成 air 为 false），与 drawInfoLines 内判定同源，
     * 供各弹窗按内容算高度复用，避免高度计算与实际绘制分叉。
     */
    fun hasHeldItemLine(heldItemId: String): Boolean =
        heldItemId.isNotEmpty() &&
            Identifier.tryParse(heldItemId)
                ?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true

    /** ListingEntry 版携带物行判定（取 heldItemId 字段） */
    fun hasHeldItemLine(entry: ListingEntry): Boolean = hasHeldItemLine(entry.heldItemId)

    /**
     * 体型徽章（Cobblemon 详情页同款图标）：原图 37×16，按 0.5 缩放显示成 18.5×8，与公母图标齐高。
     * 列表行/悬停窗/详情弹窗统一画图标；聊天播报悬浮窗用字母（服务端 Text，不走本文件）。
     */
    private const val SIZE_ICON_SCALE = 0.5F
    private const val SIZE_ICON_TEX_W = 37
    private const val SIZE_ICON_TEX_H = 16
    /** 徽章显示宽度（取整，居中排版用） */
    private const val SIZE_ICON_W = 19
    /** 名字后面多个徽章之间的空隙 */
    private const val BADGE_GAP = 3

    /** 体型徽章纹理表（预建，避免每帧重复构造 Identifier） */
    private val SIZE_BADGE_TEXTURES: Map<String, Identifier> =
        listOf("xs", "s", "m", "l", "xl", "alpha")
            .associateWith { Identifier.of("cobblemon", "textures/gui/summary/icon_size_$it.png") }

    /** 体型徽章纹理；size 为空或非法值返回 null */
    private fun sizeBadgeTexture(size: String): Identifier? = SIZE_BADGE_TEXTURES[size.lowercase()]

    /**
     * 画体型徽章图标（37×16 原图按 0.5 缩放），返回占用宽度（不含前导空隙）。
     * 列表行内自行累进排版时用；size 为空返回 0。
     */
    fun drawSizeBadgeIcon(context: DrawContext, size: String, x: Int, y: Int): Int {
        val tex = sizeBadgeTexture(size) ?: return 0
        com.cobblemon.mod.common.api.gui.blitk(
            matrixStack = context.matrices, texture = tex,
            x = x / SIZE_ICON_SCALE, y = y / SIZE_ICON_SCALE,
            width = SIZE_ICON_TEX_W, height = SIZE_ICON_TEX_H, scale = SIZE_ICON_SCALE
        )
        return SIZE_ICON_W
    }

    /**
     * 名字行整体占宽（文本 + 公母图标 + 体型徽章），供悬停窗面板宽度计算。
     * 图标不计入文本宽度，面板宽度只按文本算会顶出右边缘，故单独暴露。
     */
    fun nameLineWidth(name: Text, gender: String, size: String): Int =
        MinecraftClient.getInstance().textRenderer.getWidth(name) + nameTailWidth(gender, size)

    /** 名字行尾部（公母 + 体型徽章）占用宽度，供居中排版 */
    private fun nameTailWidth(gender: String, size: String): Int {
        var w = 0
        if (gender == "MALE" || gender == "FEMALE") w += 2 + 6
        if (size.isNotEmpty()) w += (if (w > 0) BADGE_GAP else 2) + SIZE_ICON_W
        return w
    }

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
     * 左对齐名字行：名字文本 + 右侧公母图标（♂蓝/♀红）+ 体型徽章图标。
     * 所有精灵详情面板/悬停窗的第一行统一走此函数（居中场景先算总宽再调本函数）。
     */
    fun drawNameLineLeft(
        context: DrawContext, name: Text, gender: String, x: Int, y: Int,
        color: Int = 0xFFFFFF, size: String = ""
    ) {
        val font = MinecraftClient.getInstance().textRenderer
        context.drawTextWithShadow(font, name, x, y, color)
        val nameW = font.getWidth(name)
        var cx = x + nameW
        if (gender == "MALE" || gender == "FEMALE") {
            val gi = if (gender == "MALE")
                Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
            else
                Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
            com.cobblemon.mod.common.api.gui.blitk(
                matrixStack = context.matrices, texture = gi,
                x = cx + 2, y = y, width = 6, height = 8
            )
            cx += 2 + 6
        }
        if (size.isNotEmpty()) {
            cx += if (cx > x + nameW) BADGE_GAP else 2
            drawSizeBadgeIcon(context, size, cx, y)
        }
    }

    /** 居中名字行：名字 + 公母图标 + 体型徽章整体居中（详情弹窗用，无附加信息时与普通居中文本一致） */
    fun drawNameLine(
        context: DrawContext, name: Text, gender: String, centerX: Int, y: Int,
        color: Int = 0xFFFFFF, size: String = ""
    ) {
        val font = MinecraftClient.getInstance().textRenderer
        val tail = nameTailWidth(gender, size)
        if (tail == 0) {
            context.drawCenteredTextWithShadow(font, name, centerX, y, color)
            return
        }
        val totalW = font.getWidth(name) + tail + 6
        drawNameLineLeft(context, name, gender, centerX - totalW / 2, y, color, size)
    }

    /**
     * 证章图标行：从 [leftX] 开始排列，每行最多 [MARKS_PER_ROW] 个 8×8 图标；超出的部分第二行居中显示灰色「+N」。
     * 服务端直接传纹理路径（不依赖客户端 Marks 注册表解析）。返回该区块占用的高度（12 或 24）。
     * 拍卖 / 管理端拍卖 / 待归还 / 管理端精灵悬停共用（照市场悬停 tooltip 的排法）。
     */
    fun drawMarksRow(context: DrawContext, marks: List<String>, leftX: Int, y: Int): Int {
        val textures = marks.mapNotNull { Identifier.tryParse(it) }
        if (textures.isEmpty()) return 0
        textures.take(MARKS_PER_ROW).forEachIndexed { idx, texture ->
            com.cobblemon.mod.common.api.gui.blitk(
                matrixStack = context.matrices, texture = texture,
                x = leftX + idx * 12, y = y, width = 8, height = 8
            )
        }
        if (textures.size > MARKS_PER_ROW) {
            context.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, "+${textures.size - MARKS_PER_ROW}", leftX + minOf(MARKS_PER_ROW, textures.size) * 6, y + 12, 0xAAAAAA)
            return 24
        }
        return 12
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

        val hasHeldItem = hasHeldItemLine(entry)

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
        // 证章区块（证章不影响能力，纯外观展示）：亲密度下方两条分割线夹证章图标（每行 MARKS_PER_ROW 个）
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
                    // 证章图标行：第一行最多 MARKS_PER_ROW 个居中；超出的部分第二行居中显示「+N」（不再画图标防挤占面板）
                    val textures = entry.marks.mapNotNull { Identifier.tryParse(it) }
                    val rowW = minOf(MARKS_PER_ROW, textures.size) * 12 - 4
                    textures.take(MARKS_PER_ROW).forEachIndexed { idx, texture ->
                        com.cobblemon.mod.common.api.gui.blitk(
                            matrixStack = context.matrices, texture = texture,
                            x = centerX - rowW / 2 + idx * 12, y = y, width = 8, height = 8
                        )
                    }
                    if (textures.size > MARKS_PER_ROW) {
                        context.drawCenteredTextWithShadow(font, "+${textures.size - MARKS_PER_ROW}", centerX, y + 12, 0xAAAAAA)
                        y += 24
                    } else {
                        y += 12
                    }
                }
                line == null -> {
                    // 分割线：1px 灰线，固定 6 格宽（不随证章数量变化）
                    val rowW = 68
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
                    // 第一行（名字★Lv）走公共名字行函数（带公母图标 + 体型字母）
                    drawNameLine(context, line, entry.gender, centerX, y, color, entry.sizeCategory)
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
