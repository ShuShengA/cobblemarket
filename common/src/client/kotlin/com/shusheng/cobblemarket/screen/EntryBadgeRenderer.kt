package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.shusheng.cobblemarket.util.MoveText
import com.shusheng.cobblemarket.util.StatNames
import com.shusheng.cobblemarket.client.heldIconStack
import com.shusheng.cobblemarket.network.ListingEntry
import com.shusheng.cobblemarket.util.TextUtil
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

    /** 分割线占高（1px 灰线 + 上下留白） */
    private const val DIVIDER_H = 10

    /** 技能块内容行高（与其他信息行同高） */
    private const val MOVE_ROW_H = 10

    /** 技能块总高：上分割线 + 两行 + 下分割线 */
    private const val MOVES_BLOCK_H = DIVIDER_H + MOVE_ROW_H * 2 + DIVIDER_H

    /**
     * 技能块（弹窗那版）的整块宽度：取 132 与悬停窗最宽的信息行（价格/性格特性行约 130~140）同量级，
     * 不会把框撑宽。两列各占一半（减去 [MOVE_COL_GAP]）。
     */
    private const val MOVES_MAX_W = 132

    /** 技能块两列之间的间隔：名字长时两列不贴上 */
    private const val MOVE_COL_GAP = 16

    /**
     * 技能块高度：**上分割线 + 两行 + 下分割线**，固定 40；无技能数据（旧挂单）为 0、整块不画。
     *
     * ⚠ 技能块**总是画**（Cobblemon 里不存在没技能的精灵）—— 证章块因此**不再自带那条前导分割线**，
     * 与技能块的下分割线共用一条（见 [marksBlockHeight]）。
     */
    fun movesBlockHeight(raw: String?): Int = if (MoveText.hasAny(raw)) MOVES_BLOCK_H else 0

    /**
     * 技能块在「行列表」模型里的**额外高度**：行列表把它算作 1 行（10px），实际占 [MOVES_BLOCK_H]，
     * 所以按 `lines.size * 10` 估算高度的那些界面要再加这个差值（= 30）。无技能为 0。
     */
    fun movesBlockExtra(raw: String?): Int {
        val h = movesBlockHeight(raw)
        return if (h > 0) h - MOVE_ROW_H else 0
    }

    /**
     * 技能块的最小行宽（各弹窗按内容算提示框宽度时用；无技能为 0）：
     * 取「两列名字相加 + 列间隔」的最大值 —— 比它窄就会挤到。
     */
    fun movesBlockWidth(raw: String?): Int {
        if (!MoveText.hasAny(raw)) return 0
        val font = MinecraftClient.getInstance().textRenderer
        return MoveText.rowsOfIds(raw).maxOf { (a, b) ->
            val wa = if (a.isEmpty()) 0 else font.getWidth(MoveText.name(a))
            val wb = if (b.isEmpty()) 0 else font.getWidth(MoveText.name(b))
            wa + wb + if (wa > 0 && wb > 0) MOVE_COL_GAP else 0
        }
    }

    /**
     * 证章区块高度：图标行（drawMarksRow 返回 12 或 24）+ 下分割线；**有技能块时与它共用上分割线**。
     *
     * ⚠ [movesRaw] **故意不给默认值**：加技能块时 6 个弹窗的高度公式都要显式跟着改，
     * 留默认值会「编译通过但高度差 10」静默错位 —— 让编译器当检查清单。
     */
    fun marksBlockHeight(marks: List<String>, movesRaw: String?): Int {
        if (marks.isEmpty()) return 0
        val icons = if (marks.size > MARKS_PER_ROW) 24 else 12
        // 没有技能块（旧数据）时自己补回那条前导分割线，画面不至于秃
        return icons + DIVIDER_H + if (MoveText.hasAny(movesRaw)) 0 else DIVIDER_H
    }

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
    /**
     * **行内精灵名的颜色**：按**形态**解析主属性再取色（全模组统一口径：名字 = 主属性色）。
     *
     * ⚠ 不能拿基础物种的属性：阿罗拉六尾的基础物种是火系，它自己（以及大图里的模型）却是冰系 ——
     * 用物种属性会做出「图是冰、字是火」的自相矛盾。形态解析直接复用 Cobblemon 的 `Species.getForm`，
     * 与客户端给大图建模型时的口径一致；形态没定义主属性时 `FormData` 会自动回落到物种属性。
     *
     * 解析不到物种（物品条目 / 非法 ID）返回白色。
     */
    fun speciesTypeColor(speciesId: String, aspects: Collection<String>): Int {
        val species = Identifier.tryParse(speciesId)?.let { PokemonSpecies.getByIdentifier(it) } ?: return 0xFFFFFF
        return typeColor("cobblemon.type.${species.getForm(aspects.toSet()).primaryType.name.lowercase()}")
    }

    fun typeColor(typeKey: String): Int = when (typeKey.substringAfterLast(".").lowercase()) {
        "normal" -> 0xAAAA99; "fire" -> 0xFF4422; "water" -> 0x3399FF
        "electric" -> 0xFFCC33; "grass" -> 0x77CC55; "ice" -> 0x66CCFF
        "fighting" -> 0xBB5544; "poison" -> 0xAA5599; "ground" -> 0xDDBB55
        "flying" -> 0x8899FF; "psychic" -> 0xFF5599; "bug" -> 0xAABB22
        "rock" -> 0xBBAA66; "ghost" -> 0x6666BB; "dragon" -> 0x7766EE
        "dark" -> 0x775544; "steel" -> 0xAAAABB; "fairy" -> 0xFFAAFF
        else -> 0xFFFFFF
    }

    /** 类型行属性名分段着色：主属性名主色、「+ 副属性名」副色（标签文字由调用方拼接，默认白）。
     *
     *  ⚠ 三段必须**平级**挂在空节点下 —— MC 的 `Text.visit` 把父节点合并后的样式传给每个
     *  append 进来的段落（`Style.withParent`：自己没设色就取父的），旧写法
     *  `主属性名.append(" + ")` 会让分隔符继承主属性色（2026-09-19 实测踩到）。 */
    fun typeLine(primaryType: String, secondaryType: String): Text {
        val line = Text.literal("")
        line.append(
            Text.translatable(primaryType)
                .setStyle(net.minecraft.text.Style.EMPTY.withColor(typeColor(primaryType)))
        )
        if (secondaryType.isNotEmpty()) {
            line.append(Text.literal(" + "))
            line.append(
                Text.translatable(secondaryType)
                    .setStyle(net.minecraft.text.Style.EMPTY.withColor(typeColor(secondaryType)))
            )
        }
        return line
    }

    /** 名称 + 金色闪光星标（★），非闪光不追加；Text 内嵌样式在绘制时按段渲染 */
    fun nameWithShinyStar(name: String, shiny: Boolean): Text =
        if (shiny) Text.literal(name).append(Text.literal(" ★").formatted(Formatting.GOLD))
        else Text.literal(name)

    /**
     * 「名字★Lv」行：名字本体带主属性色、Lv 段跟行色（白）。**所有界面/悬停窗的第一行统一走此函数**。
     *
     *  ⚠ 两段必须**平级**：MC 的 `Text.visit` 把父节点合并后的样式传给每个 append 进来的段落，
     *  旧写法 `名字.append("  Lv.x")` 配合整行行色 = 属性色，会让 Lv 一起变成属性色
     *  （2026-09-19 用户报「小火龙 Lv.1 连 Lv 都是红的」）。调用方行色一律传 `0xFFFFFF`。
     */
    fun nameWithLevelLine(displayName: Text, primaryType: String, level: Int): Text =
        Text.literal("")
            .append(displayName.copy().styled { it.withColor(typeColor(primaryType)) })
            .append(Text.literal("  ${Text.translatable("cobblemarket.gui.lv").string}$level"))

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
            // 与 drawInfoLines 同理：算好 x 再左对齐，绕开 drawCenteredTextWithShadow
            // （那条路径下汉字会比后面的字符高一点点）
            context.drawTextWithShadow(font, name, centerX - font.getWidth(name) / 2, y, color)
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
    /** 分割线：1px 灰线，**宽度按所在弹窗的内容栏宽**（照出价界面「撑满自己那一栏」的画法，别用固定值） */
    fun drawDivider(context: DrawContext, centerX: Int, y: Int, width: Int) {
        context.fill(centerX - width / 2, y + 4, centerX + width / 2, y + 5, 0xFF555555.toInt())
    }

    /**
     * 画技能块（**上分割线 + 两行 + 下分割线**），返回占用高度；无技能数据返回 0 且**什么都不画**。
     *
     * 两行文本由 [MoveText.rows] 产出（与聊天悬停**同源**）：固定两行、一行两个技能名，空槽留白。
     * 整行宽于 [MOVES_MAX_W] 时**整行等比缩小**（不截断 —— 截断了就不知道是什么招了）。
     * 居中用「算好 x 再 drawTextWithShadow」：`drawCenteredTextWithShadow` 会让行首汉字高一点。
     */
    fun drawMovesBlock(context: DrawContext, raw: String?, centerX: Int, startY: Int, dividerW: Int): Int {
        if (!MoveText.hasAny(raw)) return 0
        var y = startY
        drawDivider(context, centerX, y, MOVES_MAX_W)
        y += DIVIDER_H
        // 两列网格整块宽 MOVES_MAX_W、居中于 centerX
        y += drawMoveGrid(context, raw, leftX = centerX - MOVES_MAX_W / 2, width = MOVES_MAX_W, startY = y)
        drawDivider(context, centerX, y, MOVES_MAX_W)
        y += DIVIDER_H
        return y - startY
    }

    /**
     * **贴提示框版**技能块 —— 给行悬停窗那一族用（分割线撑满提示框全宽，
     * 内容与 [drawMovesBlock] 相同、只是宽度跟着提示框走）。
     * 返回占用高度；无技能数据返回 0 且什么都不画。
     */
    fun drawMovesBlockLeft(context: DrawContext, raw: String?, leftX: Int, width: Int, startY: Int): Int {
        if (!MoveText.hasAny(raw)) return 0
        var y = startY
        context.fill(leftX, y + 4, leftX + minOf(width, MOVES_MAX_W), y + 5, 0xFF555555.toInt())
        y += DIVIDER_H
        y += drawMoveGrid(context, raw, leftX = leftX, width = width, startY = y)
        context.fill(leftX, y + 4, leftX + minOf(width, MOVES_MAX_W), y + 5, 0xFF555555.toInt())
        y += DIVIDER_H
        return y - startY
    }

    /**
     * **两列网格**排版：左列在左半区居中、右列在右半区居中 —— 四个技能、或名字长短不一时两行也能对齐。
     * （2026-09-20 用户实测反馈：原先「整行排 + 固定空格间隔」长短不一会排得很乱。）
     * 返回占用高度。
     */
    private fun drawMoveGrid(context: DrawContext, raw: String?, leftX: Int, width: Int, startY: Int): Int {
        val rows = MoveText.rowsOfIds(raw)
        val colW = ((width - MOVE_COL_GAP) / 2).coerceAtLeast(1)
        rows.forEachIndexed { i, (a, b) ->
            val y = startY + i * MOVE_ROW_H
            drawMoveInColumn(context, a, leftX, colW, y)
            drawMoveInColumn(context, b, leftX + colW + MOVE_COL_GAP, colW, y)
        }
        return rows.size * MOVE_ROW_H
    }

    /** 一个技能名在 [colLeft, colLeft+colW] 这一列里**居中**画；宽过列宽就**只缩这一列**（不截断）。空槽不画 */
    private fun drawMoveInColumn(context: DrawContext, id: String, colLeft: Int, colW: Int, y: Int) {
        if (id.isEmpty()) return
        val font = MinecraftClient.getInstance().textRenderer
        val text = MoveText.name(id)
        val w = font.getWidth(text)
        if (w <= colW) {
            // 算好 x 再左对齐画（drawCenteredTextWithShadow 会让行首汉字高一点）
            context.drawTextWithShadow(font, text, colLeft + (colW - w) / 2, y, 0xFFFFFF)
            return
        }
        val scale = colW.toFloat() / w
        context.matrices.push()
        context.matrices.translate(colLeft.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(scale, scale, 1f)
        context.drawTextWithShadow(font, text, 0, 0, 0xFFFFFF)
        context.matrices.pop()
    }

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
    /**
     * 球种行：标签 + 球名。
     *
     * ⚠ 球名必须走**稀有度色**（[ItemNameText]）—— 大师球在物品栏里是紫的（EPIC），
     * 直接念词条出来是白字；而且**别转成 String 再拼**，那一步会把样式丢干净
     * （2026-09-20 用户实测：同一只精灵，物品栏紫、悬停窗白）。
     * 标签不设色（吃行色）、球名自己带色，两者平级 append —— 别让带色的当父节点。
     */
    fun ballLine(ballKey: String, ballItemId: String): Text {
        val name = ballItemId.takeIf { it.isNotEmpty() }
            ?.let { com.shusheng.cobblemarket.util.ItemNameText.rarityColoredNameById(it) }
            ?: Text.translatable(ballKey)
        return Text.literal("")
            .append(Text.translatable("cobblemarket.gui.tooltip_ball"))
            .append(name)
    }

    /**
     * 信息行最宽值（像素）——**只量不画**，供弹窗按内容动态定宽（照出价弹窗 / 物品市场那套机制：
     * dialogW = clamp(max(最小宽, 实测 + 边距), 屏幕宽)），别让长精灵名/特性名去撞右列。
     *
     * ⚠ 量的是**会随数据变长**的行：名字★Lv（精灵名）/ 类型 / 性格+特性（特性名）/ 卖家（玩家名）/ 价格。
     *    固定行（IV 六行、亲密度、球种）宽度基本不变，由调用方的最小宽度兜住。
     * ⚠ **改 drawInfoLines 的行列表时这里要一起改**（两处行必须对应，否则量出来和画出来的不一致）。
     */
    fun measureInfoLines(entry: ListingEntry, displayName: Text): Int {
        val font = MinecraftClient.getInstance().textRenderer
        val texts = listOf(
            nameWithLevelLine(displayName, entry.primaryType, entry.level),
            Text.literal(Text.translatable("cobblemarket.gui.tooltip_type").string)
                .append(typeLine(entry.primaryType, entry.secondaryType)),
            Text.literal(Text.translatable("cobblemarket.gui.tooltip_nature").string)
                .append(natureText(entry.natureBase, entry.nature))
                .append(Text.literal("  ${Text.translatable("cobblemarket.gui.tooltip_ability").string}"))
                .append(Text.translatable(entry.ability)),
            Text.translatable("cobblemarket.gui.tooltip_seller").append(Text.literal(entry.sellerName)),
            Text.translatable("cobblemarket.gui.tooltip_price").append(
                Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ${com.shusheng.cobblemarket.client.displayCurrency(entry.currencyName)}"))
        )
        return texts.maxOf { font.getWidth(it) }
    }

    fun drawInfoLines(
        context: DrawContext, entry: ListingEntry, displayName: Text,
        centerX: Int, startY: Int, dividerW: Int, maxWidth: Int = Int.MAX_VALUE
    ): Int {
        val font = MinecraftClient.getInstance().textRenderer
        val hp = StatNames.HP
        val atk = StatNames.ATK
        val def = StatNames.DEF
        val spa = StatNames.SP_ATK
        val spd = StatNames.SP_DEF
        val spe = StatNames.SPEED

        val hasHeldItem = hasHeldItemLine(entry)

        // 行列表：null = 分割线（1px 灰线）；证章行 = 图标行（marksLine 索引处画图标）
        val lines = mutableListOf<Pair<Text?, Int>>()
        // 名字行：名字本体主属性色、Lv 段白（统一走 nameWithLevelLine，别再在这里自己拼）
        lines.add(nameWithLevelLine(displayName, entry.primaryType, entry.level) to 0xFFFFFF)
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
            lines.add(ballLine(entry.ball, entry.ballItem) to 0xFFFFFF)
        }
        var heldItemLine = -1
        if (hasHeldItem) {
            heldItemLine = lines.size
            lines.add(Text.translatable("cobblemarket.gui.tooltip_held") to 0xFFFFFF)
        }
        lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs") to 0xFFFFFF)
        lines.add(Text.literal("  $hp: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsHp, entry.htHp)}").append(Text.literal("  EV: ${entry.evsHp}").formatted(Formatting.RED)) to 0x66FF66)
        lines.add(Text.literal("  $atk: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsAtk, entry.htAtk)}").append(Text.literal("  EV: ${entry.evsAtk}").formatted(Formatting.RED)) to 0xFF6666)
        lines.add(Text.literal("  $def: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsDef, entry.htDef)}").append(Text.literal("  EV: ${entry.evsDef}").formatted(Formatting.RED)) to 0xFFCC66)
        lines.add(Text.literal("  $spa: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpAtk, entry.htSpAtk)}").append(Text.literal("  EV: ${entry.evsSpAtk}").formatted(Formatting.RED)) to 0x6699FF)
        lines.add(Text.literal("  $spd: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpDef, entry.htSpDef)}").append(Text.literal("  EV: ${entry.evsSpDef}").formatted(Formatting.RED)) to 0x66FF99)
        lines.add(Text.literal("  $spe: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpd, entry.htSpd)}").append(Text.literal("  EV: ${entry.evsSpd}").formatted(Formatting.RED)) to 0xFF99FF)
        lines.add(Text.translatable("cobblemarket.gui.friendship", entry.friendship) to 0xFF99CC)
        // 技能块（**总是画** —— Cobblemon 里不存在没技能的精灵）：亲密度下方「分割线 + 两行技能 + 分割线」，
        // 整块由 drawMovesBlock 画（此处只占一个坑位）。证章块与它**共用下面那条分割线**，自己不再带前导分割线。
        // 无技能数据（旧挂单）= 整块不画。
        var movesLine = -1
        if (MoveText.hasAny(entry.moves)) {
            movesLine = lines.size
            lines.add(null to 0)
        }
        // 证章区块（证章不影响能力，纯外观展示）：技能块下方，图标行 + 下分割线（每行 MARKS_PER_ROW 个）
        // 服务端直接传纹理路径（不依赖客户端 Marks 注册表解析）
        var marksLine = -1
        if (entry.marks.isNotEmpty()) {
            marksLine = lines.size
            lines.add(null to 0)
            lines.add(null to 0)
        }
        lines.add(
            Text.translatable("cobblemarket.gui.tooltip_seller").append(Text.literal(entry.sellerName)) to 0xFFFFFF
        )
        // 价格行：单位走统一入口，币种取**这条挂单自己的**。
        // ⚠ 这里曾经漏过 —— 购买确认弹窗（精灵）与待交付弹窗都走本函数，
        //   漏了就会把 RP 价格标成 ₽
        lines.add(
            Text.translatable("cobblemarket.gui.tooltip_price").append(
                com.shusheng.cobblemarket.client.amountWithUnit(
                    com.shusheng.cobblemarket.client.formatPrice(entry.price), entry.isPoints, entry.currencyName
                ).formatted(Formatting.GOLD)) to 0xFFFFFF
        )

        var y = startY
        var i = 0
        while (i < lines.size) {
            val (line, color) = lines[i]
            when {
                i == movesLine -> {
                    // 技能块：上分割线 + 两行技能 + 下分割线（整块自画，返回值即占用高度）
                    y += drawMovesBlock(context, entry.moves, centerX, y, dividerW)
                }
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
                    // 分割线：1px 灰线，宽度随弹窗内容栏（与技能块同款，见 drawDivider）
                    drawDivider(context, centerX, y, MOVES_MAX_W)
                    y += DIVIDER_H
                }
                i == heldItemLine -> {
                    // 携带物行：文字 + 物品图标整体居中（图标占 14px，文字按去掉图标后的宽度截断）
                    val heldText = TextUtil.truncateStyled(line, maxWidth - 14)
                    val textW = font.getWidth(heldText)
                    val x = centerX - (textW + 14) / 2
                    context.drawTextWithShadow(font, heldText, x, y, color)
                    heldIconStack(entry.heldItemId, entry.heldItemNbt)?.let { heldStack ->
                        com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                            itemStack = heldStack,
                            x = (x + textW + 2).toDouble(), y = y.toDouble(), scale = 0.6, matrixStack = context.matrices
                        )
                    }
                    y += 10
                }
                i == 0 -> {
                    // 第一行（名字★Lv）走公共名字行函数（带公母图标 + 体型字母）：
                    // 尾部图标不参与文本宽度，截断预算里先扣掉（居中总宽 = 名字 + 尾部 + 6）
                    val tail = nameTailWidth(entry.gender, entry.sizeCategory)
                    val nameBudget = if (tail == 0) maxWidth else maxWidth - tail - 6
                    drawNameLine(context, TextUtil.truncateStyled(line, nameBudget), entry.gender, centerX, y, color, entry.sizeCategory)
                    y += 10
                }
                else -> {
                    // 用"算好 x 再左对齐"的方式画（视觉上仍是居中）：drawCenteredTextWithShadow
                    // 在这套字体下会让行首汉字比后面的字高一点点，出价界面用 drawTextWithShadow 就正常
                    val drawn = TextUtil.truncateStyled(line, maxWidth)
                    context.drawTextWithShadow(font, drawn, centerX - font.getWidth(drawn) / 2, y, color)
                    y += 10
                }
            }
            i++
        }
        return y
    }
}
