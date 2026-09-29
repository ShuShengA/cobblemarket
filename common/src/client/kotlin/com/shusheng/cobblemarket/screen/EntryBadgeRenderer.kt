package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.shusheng.cobblemarket.util.MoveText
import com.shusheng.cobblemarket.util.StatNames
import com.shusheng.cobblemarket.client.currencyUnitWidth
import com.shusheng.cobblemarket.client.drawCurrencyUnit
import com.shusheng.cobblemarket.client.heldIconStack
import com.shusheng.cobblemarket.network.ListingEntry
import com.shusheng.cobblemarket.network.AuctionEntry
import com.shusheng.cobblemarket.util.ItemNameText
import com.shusheng.cobblemarket.util.TypeTextColors
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
     * **一行能放几个证章图标** —— 按**可用宽度**算（每个图标占 12px，至少 1 个）。
     *
     * ⚠⚠ 这里**曾经是写死的 `const val MARKS_PER_ROW = 10`**（2026-09-09 拍板 6 → 10，
     *   理由是「10 个 = 120px，窄于当时提示框最宽文本行 ⇒ 不会把框撑宽」）。**那个前提后来失效了**：
     *   悬停窗陆续加了太晶 / 极巨化 / 初始训练家等条目、**框变宽了**，10 个图标占不满 ——
     *   右边空一片、后面全挤进「+N」（用户 2026-09-27 截图实测指出）。
     * ⇒ 改成**跟随可用宽度**：能放几个放几个，放不下的进第二行「+N」。
     *
     * ⚠ 参数是**可用内容宽**（悬停窗 / 弹窗的 `mw` / `maxWidth` / `dividerW`）——
     *   用户的原话是「跟随**分割线长度**」，而分割线撑满的正是这个宽。
     * ⚠ 调用方**别再把证章宽并进「窗口该多宽」的计算**（原先那种 `mw = max(mw, 10*12)`）——
     *   现在方向反过来了：**窗口宽度决定证章排几个**，而不是证章把窗口撑宽。
     */
    fun marksPerRow(availableWidth: Int): Int = (availableWidth / 12).coerceAtLeast(1)

    /** 分割线占高（1px 灰线 + 上下留白） */
    private const val DIVIDER_H = 10

    /** 技能块内容行高（与其他信息行同高） */
    private const val MOVE_ROW_H = 10

    /** 技能块总高：上分割线 + 两行 + 下分割线 */
    private const val MOVES_BLOCK_H = DIVIDER_H + MOVE_ROW_H * 2 + DIVIDER_H

    /**
     * **居中弹窗那一族里，横向三样东西的统一宽度**：技能块、它上下的两条分割线、**以及证章图标行**
     *（取 132：与悬停窗最宽的信息行 130~140 同量级，不会把框撑宽；技能块两列各占一半、
     * 减去 [MOVE_COL_GAP]）。
     *
     * ⚠ **这是公开常量、不是内部实现细节**：各弹窗**按内容算高度**时必须拿它当
     *   「证章一行排几个」的可用宽（见 [marksBlockHeight] 的 `availableWidth`）。
     *   拿别的值（124 / 240 / `dialogW - k`）去算，行数就与 [drawInfoLines] 实际画的**对不上** ——
     *   **证章一多就溢出弹窗**（2026-09-29 查证：市场购买 / 购买确认界面 / 待交付 / 管理端精灵
     *   四个弹窗各传各的，其中购买确认界面传的是 240，差得最多）。
     * ⚠ **两列弹窗**（出价弹窗 / 管理端拍卖详情）的左列是**固定 124**、证章也是自己画的
     *   ⇒ 那边照旧传 124，**别套这个常量**。
     */
    const val MOVES_MAX_W = 132

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
    fun marksBlockHeight(marks: List<String>, movesRaw: String?, availableWidth: Int): Int {
        if (marks.isEmpty()) return 0
        // 「要不要第二行 +N」现在取决于**可用宽度**（见 [marksPerRow]），不再是固定 10
        val icons = if (marks.size > marksPerRow(availableWidth)) 24 else 12
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
     * 拍卖条目的**带色名字** —— 精灵按**形态主属性色**、物品按**稀有度色**。
     *
     * ⚠ **各处一律走它，别再各拼各的**：拍卖场与管理端拍卖的**物品分支**当年是照精灵分支抄的，
     *   精灵那边有现成的 `typeColor(primaryType)`，物品就直接落成了白字 —— 行内、悬停窗首行、
     *   两个弹窗的信息行四处都漏（2026-09-26 用户报「拍卖场物品行的名字还是白色」，并指出
     *   「其他地方我懒得一个一个找了」）。
     *
     * 物品优先用**完整栈 NBT** 重建（附魔 / 自定义名跟着走，与物品市场同一口径），
     * 重建不出再按 id 取稀有度，最后才退到裸 id。
     */
    fun auctionNameText(entry: AuctionEntry): Text {
        if (entry.type == "ITEM") {
            val rm = MinecraftClient.getInstance().world?.registryManager
            return rm?.let { ItemNameText.rarityColoredNameOfNbt(it, entry.itemNbt) }
                ?: ItemNameText.rarityColoredNameById(entry.species)
                ?: Text.literal(entry.species)
        }
        return TypeTextColors.speciesName(
            Text.translatable(entry.species),
            entry.extraData["primaryType"]?.takeIf { it.isNotEmpty() },
            entry.shiny
        )
    }

    /**
     * 体型徽章（Cobblemon 详情页同款图标）：原图 37×16，按 0.5 缩放显示成 18.5×8，与公母图标齐高。
     * 列表行/悬停窗/详情弹窗统一画图标；聊天播报悬浮窗用字母（服务端 Text，不走本文件）。
     */
    private const val SIZE_ICON_SCALE = 0.5F
    private const val SIZE_ICON_TEX_W = 37
    private const val SIZE_ICON_TEX_H = 16
    /** 徽章显示宽度（取整，居中排版用） */
    private const val SIZE_ICON_W = 19

    /** Cobblemon 原版「不可交易」锁图标（⚠ **内部资源路径**，不是 API —— 见 [drawUntradeableLock]） */
    private val UNTRADEABLE_LOCK_TEX =
        Identifier.of("cobblemon", "textures/gui/trade/trade_slot_icon_locked.png")

    /** 锁图的实际边长（20×20；绘制时按目标尺寸缩放采样） */
    private const val LOCK_TEX_SIZE = 20

    /**
     * 锁占精灵图标的比例 —— **三成**（用户 2026-09-30 逐档试出来的：铺满 → 六成 → 一半都嫌大，
     * 「这个大小让人觉得是不小心放歪了，而不是想放到左上角」⇒ 只有缩到角标那个量级才读得出是**刻意贴的**）。
     */
    private const val LOCK_SIZE_RATIO = 0.3f

    /** 锁的绘制下限：图标再小也别缩到看不清（⚠ 调小 [LOCK_SIZE_RATIO] 时记得一起看它，会顶掉比例值） */
    private const val LOCK_MIN = 5

    /**
     * 锁距精灵图标**左上角**的边距。
     *
     * ⚠ **不居中、还压到一半以下，是为了别挡精灵**（用户 2026-09-30 定）——
     *   「**图标的动态（转头 / 待机动画）本来就是本模组的特色**，盖上一个大锁就只剩锁了，观感不佳」。
     *   ⇒ 以后谁想把这个锁调大或挪回中间，先回来看一眼这句。
     */
    private const val LOCK_MARGIN = 1
    /** 名字后面多个徽章之间的空隙 */
    private const val BADGE_GAP = 3

    /**
     * **名字（含 ★Lv）→ 尾部第一个元素**（性别图标 / 体型徽章）之间的空隙。
     *
     * ⚠ 原来各处写的是 **2**（`cx + 2` / `sx += 2` / `nameWidth + 2`，散在 9 处手写拷贝里）——
     * 2px 再扣掉字形自带的 1px 右内距几乎贴住，而同一行「名字 → Lv」是两个空格（默认字体 8px），
     * 前松后紧，看着就是挤在一起（用户 2026-09-26 在精灵市场悬停窗提出）。
     *
     * ⚠ **公开常量**：列表行是各界面**手写**排版的（不走 [drawNameLineLeft]），
     * 它们必须引用这里，别再各写各的 2 —— 改间距只改这一个数。
     */
    const val NAME_TAIL_GAP = 5

    /**
     * **「性格 + 特性」在同一行**时，两者之间隔几个空格（默认字体 1 空格 = 4px）。
     *
     * ⚠ 原来这 **9 处各写各的 2**（8px）—— 两段**都是白字**，扣掉字形自带内距几乎贴住，
     *   看着像一行连着的文字（用户 2026-09-27 报「性格：悠闲  特性：精神力」）。
     *   收口到 [natureAbilityLine] 后**改间距只改这一个数**，界面里别再出现空格字面量。
     */
    private const val NATURE_ABILITY_GAP = 4

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
     * 在精灵图标上盖一把「不可交易」的锁 —— **直接用 Cobblemon 原版那张图**。
     *
     * 为什么复用原版的图（用户 2026-09-30 定）：玩家在原版交换界面见过这把锁，
     * 同一张图能直接建立「这只不能交易」的认知；自己画一个反而要重新教一遍。
     *
     * ⚠⚠ **这是 Cobblemon 的「内部资源路径」，不是 API** —— 官方升级挪了位置就会画成紫黑格。
     *   它与 `SpeciesText.GATED_FORM_ASPECTS` 那类硬编码同性质：**跟一次 Cobblemon 大版本就复核一眼**
     *   （图还在不在、尺寸变没变）。
     *
     * ⚠⚠ **别照搬它的尺寸**（2026-09-30 用户实测截图报「比整个精灵图标都大、跟行一样高」）：
     *   Cobblemon 的槽位有 30 多像素、锁图 20 只占**六成**，而我们的图标边长本身就是 20
     *   ⇒ 原样画等于把精灵整个盖住。这里缩到**一半**、贴图标的**左上角**（用户实测定档）。
     */
    fun drawUntradeableLock(context: DrawContext, x: Int, y: Int, size: Int) {
        // 屏幕上的目标边长（图标一半，小图标有下限）
        val lock = maxOf((size * LOCK_SIZE_RATIO).toInt(), LOCK_MIN)
        // ⚠⚠ **缩放必须走 matrices，不能把目标尺寸塞给 `drawTexture` 的 width/height** ——
        //   那两个参数同时是「绘制尺寸」和「采样区」：传 12 会变成「从锁图左上角取 12×12 像素」，
        //   于是只画出一角（2026-09-30 用户实测截图报的第二个症状）。这里取**整张 20×20**、
        //   靠矩阵缩到目标大小（口径同 [drawSizeBadgeIcon] 那边的 blitk）。
        val scale = lock.toFloat() / LOCK_TEX_SIZE
        context.matrices.push()
        context.matrices.translate((x + LOCK_MARGIN).toFloat(), (y + LOCK_MARGIN).toFloat(), 0f)
        context.matrices.scale(scale, scale, 1f)
        context.drawTexture(
            UNTRADEABLE_LOCK_TEX, 0, 0, 0f, 0f,
            LOCK_TEX_SIZE, LOCK_TEX_SIZE, LOCK_TEX_SIZE, LOCK_TEX_SIZE
        )
        context.matrices.pop()
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
        if (gender == "MALE" || gender == "FEMALE") w += NAME_TAIL_GAP + 6
        if (size.isNotEmpty()) w += (if (w > 0) BADGE_GAP else NAME_TAIL_GAP) + SIZE_ICON_W
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
     * [nameWithShinyStar] 的 **Text 版**：名字是**词条 key** 构造出来的（而不是现成字符串）时用它，
     * 星标口径完全一致（★ 接在名字后面、金色）。例如聊天播报的悬停窗 ——
     * 那边手里只有物种 ID，名字必须 `Text.translatable("cobblemon.species.<id>.name")` 现翻。
     */
    fun nameWithShinyStar(name: Text, shiny: Boolean): Text =
        if (shiny) Text.literal("").append(name).append(Text.literal(" ★").formatted(Formatting.GOLD))
        else name

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
     * **「性格 + 特性」同一行**的共用组装 —— 两段**都是白字**，全靠间距分开。
     *
     * ⚠ 分隔间距**只在这里定义**（[NATURE_ABILITY_GAP] 个空格）—— **别在各界面写 `"  "` 字面量**：
     *   2026-09-27 用户报「性格：悠闲  特性：精神力」两段挨得太近、**看着像一行连着的文字**，
     *   根因就是那 **9 处各写各的「两空格」**（8px，扣掉字形自带内距几乎贴住）。
     *   收口到这里后，改间距只改一个数（同 [NAME_TAIL_GAP] 的做法）。
     *
     * ⚠ 两个标签都不设色（吃行色）、各段平级 append —— 别让带色的当父节点
     *（见 CLAUDE.md 的「界面文字与聊天消息」约定）。
     */
    fun natureAbilityLine(natureBase: String, nature: String, abilityKey: String): Text =
        Text.literal(Text.translatable("cobblemarket.gui.tooltip_nature").string)
            .append(natureText(natureBase, nature))
            .append(Text.literal("${" ".repeat(NATURE_ABILITY_GAP)}${Text.translatable("cobblemarket.gui.tooltip_ability").string}"))
            .append(Text.translatable(abilityKey))

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
                x = cx + NAME_TAIL_GAP, y = y, width = 6, height = 8
            )
            cx += NAME_TAIL_GAP + 6
        }
        if (size.isNotEmpty()) {
            cx += if (cx > x + nameW) BADGE_GAP else NAME_TAIL_GAP
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
     * 证章图标行：从 [leftX] 开始排列 8×8 图标，**一行放几个跟随可用宽**（见 [marksPerRow]），放不下的第二行居中「+N」。传了鼠标坐标时还会登记「+N」区域的悬停（见 [drawMarksMoreTooltip]）。
     * 服务端直接传纹理路径（不依赖客户端 Marks 注册表解析）。返回该区块占用的高度（12 或 24）。
     * 拍卖 / 管理端拍卖 / 待归还 / 管理端精灵悬停共用（照市场悬停 tooltip 的排法）。
     */
    /** 分割线：1px 灰线，**宽度按所在弹窗的内容栏宽**（照出价界面「撑满自己那一栏」的画法，别用固定值） */
    fun drawDivider(context: DrawContext, centerX: Int, y: Int, width: Int) {
        context.fill(centerX - width / 2, y + 4, centerX + width / 2, y + 5, 0xFF888888.toInt())
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
     * **贴提示框版**技能块 —— 给行悬停窗那一族用（分割线**撑满提示框内容宽**，
     * 内容与 [drawMovesBlock] 相同、只是宽度跟着提示框走）。
     * 返回占用高度；无技能数据返回 0 且什么都不画。
     *
     * ⚠ **两条分割线必须走 `width`，不能卡 [MOVES_MAX_W]**：下面的网格本来就按 `width` 排，
     *   线一卡就变成「技能名铺满整行、分割线只剩中间一小截」（2026-09-21 用户报）。
     *   [MOVES_MAX_W] 只管 [drawMovesBlock]（弹窗里那块居中的窄网格），别把两者统一到同一个常量。
     */
    fun drawMovesBlockLeft(context: DrawContext, raw: String?, leftX: Int, width: Int, startY: Int): Int {
        if (!MoveText.hasAny(raw)) return 0
        var y = startY
        context.fill(leftX, y + 4, leftX + width, y + 5, 0xFF888888.toInt())
        y += DIVIDER_H
        y += drawMoveGrid(context, raw, leftX = leftX, width = width, startY = y)
        context.fill(leftX, y + 4, leftX + width, y + 5, 0xFF888888.toInt())
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

    /**
     * 证章图标行。
     *
     * @param centered **整行是否在 [availableWidth] 里居中** ——
     *   行悬停窗传 `true`：分割线撑满可用宽，而证章数不一定占满（改跟随宽度后会空出右边），
     *   左对齐就显偏左（用户 2026-09-27 实测报出）；
     *   两列弹窗传 `false`：左列本来就是左对齐的一列，居中反而跟上面的文字 / 技能块不齐。
     *   ⚠ **故意不给默认值**：新调用点必须显式选一种（跟项目里 `movesRaw` 那个手法一致，
     *   留默认值会「编译通过但看着偏」静默出错）。
     */
    fun drawMarksRow(
        context: DrawContext, marks: List<String>, leftX: Int, y: Int,
        availableWidth: Int, centered: Boolean,
        // 鼠标坐标：传了（且这一行有「+N」、鼠标正停在这一行上）就**登记**下来，
        // 等一级悬停窗画完之后再画二级的证章总览窗（见 [drawMarksMoreTooltip]）。
        // ⚠ 界面在**画悬停窗**时传它（那儿才有鼠标坐标）；不传 = 没有二级窗
        mouseX: Int = -1, mouseY: Int = -1
    ): Int {
        val textures = marks.mapNotNull { Identifier.tryParse(it) }
        if (textures.isEmpty()) return 0
        // 一行放几个**跟着可用宽走**（见 [marksPerRow]）—— 能放几个放几个，放不下的进「+N」
        val perRow = marksPerRow(availableWidth)
        val shown = minOf(perRow, textures.size)
        // 图标整体占宽：最后一个图标后面没有那 12px 间隙 ⇒ 减 4
        val rowWidth = shown * 12 - 4
        val startX = if (centered) leftX + (availableWidth - rowWidth) / 2 else leftX
        textures.take(perRow).forEachIndexed { idx, texture ->
            com.cobblemon.mod.common.api.gui.blitk(
                matrixStack = context.matrices, texture = texture,
                x = startX + idx * 12, y = y, width = 8, height = 8
            )
        }
        if (textures.size > perRow) {
            // 「+N」居中在**图标行**下方（与图标同基准）
            context.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, "+${textures.size - perRow}", startX + rowWidth / 2, y + 12, 0xAAAAAA)
            // ⭐ 鼠标停在这一块（整行 24px 高，图标 + 「+N」都算）⇒ 等会儿补一个**全部证章**的二级窗。
            //    ⚠ 判定用**整行**而不是只认「+N」那几个像素：玩家想查证章时鼠标落在哪块都有可能
            if (mouseX >= leftX && mouseX < leftX + availableWidth && mouseY >= y && mouseY < y + 24) {
                marksMoreHit = MarksMoreHit(leftX, y, availableWidth, textures, System.currentTimeMillis())
            }
            return 24
        }
        return 12
    }

    /** 本帧鼠标下的「+N」区域（画证章行时登记，见 [drawMarksRow]） */
    private var marksMoreHit: MarksMoreHit? = null

    private class MarksMoreHit(
        val x: Int, val y: Int, val width: Int,
        /** **全部**证章贴图（不只是画出来的那几个）—— 二级窗要列全 */
        val textures: List<Identifier>,
        val at: Long
    )

    /** 二级窗的格子与内边距（照 1.3.0 RP 商城那个证章窗） */
    private const val MARK_CELL = 12
    private const val MARK_TIP_PAD = 4
    /** 二级窗里一行排几个 —— **固定值**：它自己的宽度就是按它反算的（与主窗口那套「跟随可用宽」无关） */
    private const val MARK_TIP_PER_ROW = 10
    /** 登记的有效期：超过这么久没被取用就当过期（防止某帧没画证章行时残留上一帧的） */
    private const val MARKS_MORE_TTL_MS = 120L

    /**
     * **「+N」的二级悬停窗**：鼠标停在证章行上时，列出这只精灵的**全部**证章图标。
     *
     * ⚠ **必须在一级悬停窗画完之后调**（它要盖在一级窗之上）—— 各界面在 [drawMarksRow] 之后、
     *   `renderTooltip` 的**末尾**调一次即可。没登记（鼠标没停在证章行上）时**什么都不画**。
     * ⚠ 落点与项目里其余悬停窗**同一套**（鼠标右移 12、优先放上方、再钳进屏幕），
     *   别自己另发明一套 —— 见 1.3.0 `PointsMarketScreen.renderMarksTooltip` 的同款注释。
     * ⚠ 用完就清，避免下一帧在别处冒出来；再加个 [MARKS_MORE_TTL_MS] 兜底。
     */
    fun drawMarksMoreTooltip(context: DrawContext) {
        val hit = marksMoreHit ?: return
        marksMoreHit = null
        if (System.currentTimeMillis() - hit.at > MARKS_MORE_TTL_MS) return
        val textures = hit.textures
        if (textures.isEmpty()) return

        val rows = (textures.size + MARK_TIP_PER_ROW - 1) / MARK_TIP_PER_ROW
        val w = minOf(textures.size, MARK_TIP_PER_ROW) * MARK_CELL + MARK_TIP_PAD * 2
        val h = rows * MARK_CELL + MARK_TIP_PAD * 2
        // 落点照 HistoryScreen.renderTooltip：鼠标右移 12、**优先放上方 4px**（顶不下才翻到下方），再钳进屏幕
        val screenW = context.scaledWindowWidth
        val screenH = context.scaledWindowHeight
        val tx = minOf(hit.x + hit.width + 12, screenW - w - 4).coerceAtLeast(4)
        val tyAbove = hit.y - h - 4
        val ty = if (tyAbove <= 0) minOf(hit.y + 24 + 4, screenH - h - 4).coerceAtLeast(4) else tyAbove

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx, ty, w, h, 1, ROW_BACKGROUND_TEX_H)
        textures.forEachIndexed { idx, texture ->
            com.cobblemon.mod.common.api.gui.blitk(
                matrixStack = context.matrices, texture = texture,
                x = tx + MARK_TIP_PAD + (idx % MARK_TIP_PER_ROW) * MARK_CELL,
                y = ty + MARK_TIP_PAD + (idx / MARK_TIP_PER_ROW) * MARK_CELL,
                width = 8, height = 8
            )
        }
        context.matrices.pop()
    }

    /**
     * 居中绘制信息行，返回下一行的 y。
     * [dividerW] = 证章分割线宽度；[maxWidth] = 容器可用内容宽（弹窗宽度减去左右内边距，从布局反算，别写死）——
     * 超宽的行按保留分段样式的方式截断。默认 Int.MAX_VALUE = 不截断（无边框的整页界面用）。
     */
    /**
     * 信息行最宽值（像素）——**只量不画**，供弹窗按内容动态定宽（照出价弹窗 / 物品市场那套机制：
     * `dialogW = clamp(max(最小宽, 实测 + 边距), 屏幕宽)`），别再让长精灵名/特性名去撞右列。
     *
     * ⚠ 量的是**会随数据变长**的行：名字★Lv（精灵名）/ 类型 / 性格+特性（特性名）/ 卖家（玩家名）/ 价格。
     *    固定行（IV 六行、亲密度、球种）宽度基本不变，由调用方的最小宽度兜住。
     * ⚠ **改 drawInfoLines 的行列表时，这里要一起改**（两处的行必须对应，否则量出来和画出来的不一致）。
     */
    fun measureInfoLines(entry: ListingEntry, displayName: Text): Int {
        val font = MinecraftClient.getInstance().textRenderer
        val texts = listOfNotNull(
            nameWithLevelLine(displayName, entry.primaryType, entry.level),
            // ⚠ 与 [drawInfoLines] 那行走**同一个组装入口**（含太晶那段）——
            //   两处行必须对应，否则量出来和画出来的不一致
            TypeTextColors.typeLineWithTera(entry.primaryType, entry.secondaryType, entry.teraType),
            // 极巨行：**没有则整行不画**（普通精灵最常见）⇒ listOfNotNull 把 null 丢掉，
            // 与 drawInfoLines 的行列表严格对应
            com.shusheng.cobblemarket.util.PokemonExtraLines.dynamax(entry.gmaxFactor, entry.dmaxLevel),
            natureAbilityLine(entry.natureBase, entry.nature, entry.ability),
            Text.translatable("cobblemarket.gui.tooltip_seller").append(Text.literal(entry.sellerName)),
            Text.translatable("cobblemarket.gui.tooltip_price").append(
                Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ${com.shusheng.cobblemarket.client.displayCurrency(entry.currencyName)}")),
            // 初始训练家行：同样"没有就不画"
            com.shusheng.cobblemarket.util.PokemonExtraLines.originalTrainer(entry.originalTrainer)
        )
        return texts.maxOf { font.getWidth(it) }
    }

    /** 携带物行里物品图标占的宽度（[drawInfoLines] 里那块「+14」），量宽度时要补上 */
    private const val HELD_ICON_W = 14

    /**
     * **信息行面板的内容宽**（像素）—— 只量不画，供**自适应尺寸的悬停窗**定背景框宽度
     * （聊天播报的悬浮窗：它没有弹窗那种写死的宽度可用）。
     *
     * = max(最宽信息行, 技能块宽, 名字行含尾部图标, [MOVES_MAX_W])
     *
     * ⚠ 最后一项是**兜底**：`drawInfoLines` 里的技能块走 [drawMovesBlock]，而它用的是**固定
     *   [MOVES_MAX_W] 宽**（传入的 `dividerW` 只做居中基准、不参与网格宽度）⇒ 内容宽比 132 窄时
     *   技能块会**戳出面板**。弹窗那边框宽都 ≥ 300 所以从没暴露过，自适应面板必须先兜住。
     * ⚠ 证章**不参与**：方向是反的 —— 宽度决定证章排几个（见 [marksPerRow]）。
     */
    fun infoPanelContentWidth(
        entry: ListingEntry, displayName: Text,
        /** 与 [drawInfoLines] 的 `footer` **必须传同一个**（否则宽量不到那几行） */
        footer: List<Pair<Text?, Int>>? = null
    ): Int {
        val font = MinecraftClient.getInstance().textRenderer
        var w = measureInfoLines(entry, displayName)
        // 自带尾部那几行也要量（金额随挂单变化，不量会被截断）
        footer?.forEach { (line, _) -> if (line != null) w = maxOf(w, font.getWidth(line)) }
        w = maxOf(w, movesBlockWidth(entry.moves))
        // 名字行尾部的公母 / 体型图标不计入文本宽，单独补
        w = maxOf(w, nameLineWidth(displayName, entry.gender, entry.sizeCategory))
        // 携带物行：文字 + 物品图标（图标不算在文本宽里）
        if (hasHeldItemLine(entry)) {
            w = maxOf(w, font.getWidth(Text.translatable("cobblemarket.gui.tooltip_held")) + HELD_ICON_W)
        }
        return maxOf(w, MOVES_MAX_W)
    }

    /**
     * **信息行区块的总高度**（像素）—— 只量不画，供**自适应尺寸的悬停窗**先算背景框再画内容
     * （聊天播报的悬浮窗：它不能像弹窗那样写死高度，也不能像列表悬停窗那样边画边累加）。
     *
     * ⚠⚠ 与 [drawInfoLines] 的行列表**必须严格对应**（同 [measureInfoLines] 的约定，
     *   那两处一起改）—— 少算一行 = 背景框比内容矮一截、多算一行 = 下方留白。
     * ⚠ [availableWidth] 决定**证章排几行**（见 [marksPerRow]）⇒ 必须在内容宽定稿之后再调它。
     * ⚠ 这里**不能复用 [marksBlockHeight]**：那个是给弹窗用的，没有技能块时会多算一条前导
     *   分割线（32 vs 实际的 22）——`drawInfoLines` 里证章块与技能块**共用**那条线。
     */
    fun measureInfoLinesHeight(
        entry: ListingEntry, availableWidth: Int,
        /** 与 [drawInfoLines] 的 `footer` **必须传同一个**（否则背景框和内容对不上） */
        footer: List<Pair<Text?, Int>>? = null
    ): Int {
        var h = MOVE_ROW_H                                   // 名字★Lv
        h += MOVE_ROW_H                                      // 属性（含太晶）
        if (com.shusheng.cobblemarket.util.PokemonExtraLines.dynamax(entry.gmaxFactor, entry.dmaxLevel) != null) {
            h += MOVE_ROW_H                                  // 极巨行（有才画）
        }
        h += MOVE_ROW_H                                      // 性格 + 特性
        if (entry.ball.isNotEmpty()) h += MOVE_ROW_H         // 球种（有才画）
        if (hasHeldItemLine(entry)) h += MOVE_ROW_H          // 携带物（有才画）
        h += MOVE_ROW_H                                      // 「个体值」标签
        h += MOVE_ROW_H * 6                                  // IV 六项
        h += MOVE_ROW_H                                      // 亲密度
        if (com.shusheng.cobblemarket.util.PokemonExtraLines.originalTrainer(entry.originalTrainer) != null) {
            h += MOVE_ROW_H                                  // 初始训练家（有才画）
        }
        // 技能块：整块自画（上分割线 + 两行 + 下分割线）
        if (MoveText.hasAny(entry.moves)) h += MOVES_BLOCK_H
        // 证章块：图标行（证章多则第二行「+N」）+ 它下面那条分割线
        if (entry.marks.isNotEmpty()) {
            h += if (entry.marks.size > marksPerRow(availableWidth)) 24 else 12
            h += DIVIDER_H
        }
        if (footer == null) {
            h += MOVE_ROW_H                                  // 卖家
            h += MOVE_ROW_H                                  // 价格
        } else {
            // 自带尾部：null 元素是分割线，占 DIVIDER_H
            footer.forEach { (line, _) -> h += if (line == null) DIVIDER_H else MOVE_ROW_H }
        }
        return h
    }

    fun drawInfoLines(
        context: DrawContext, entry: ListingEntry, displayName: Text,
        centerX: Int, startY: Int, dividerW: Int, maxWidth: Int = Int.MAX_VALUE,
        // **替换默认尾部**（卖家 + 价格）：null = 用默认那两行（市场 / 待交付 / 管理端那一族）。
        // 传入则整段替换 —— 拍卖播报要的是「卖家 / 当前价 / 起拍价 / 加价 / 倒计时 / 领先者」，
        // 而市场那套的「价格」对拍卖没有意义（拍卖有起拍价 / 当前价 / 加价**三个**金额概念）。
        // ⚠ 元素为 null = 分割线（与行列表其余地方同一约定）
        footer: List<Pair<Text?, Int>>? = null,
        // **排版**：false（默认）= 弹窗那一族（**每行都居中**）；
        // true = 列表悬停窗 / 聊天播报窗那一族 —— ⚠ **别理解成「所有行都靠左」，那族是混合的**：
        //   **文字行**贴左缘、**分割线与技能块撑满内容宽**，而**证章行照样在内容宽里居中**
        //  （图标本就不一定占满一行，左对齐会显偏左 —— 市场行悬停窗就是这么画的），
        //   技能块**内部的两列**也各自居中（见 [drawMoveGrid]）。
        // 2026-09-29 用户要求聊天窗「完全照搬精灵行悬停窗的排布」，并**特意提醒了证章这一点**。
        // ⚠ `centerX` 两种模式都传**内容区中心**：左对齐时左缘由它减去半个内容宽算出来
        //（这样调用方不必知道自己该传"中心"还是"左缘"）
        alignLeft: Boolean = false,
        // 鼠标坐标：**只有弹窗传** —— 传了才会在鼠标停到证章行时登记、稍后补一个二级的证章总览窗
        //（见 [drawMarksMoreTooltip]）。
        // ⚠ **行悬停窗 / 聊天播报窗不要传**：那类窗跟着鼠标走、鼠标一移开就没了，
        //   根本不可能停到证章行上 ⇒ 做了也没人用得到（2026-09-29 用户指出）。
        mouseX: Int = -1, mouseY: Int = -1
    ): Int {
        // ⚠ **弹窗自己的货币图标不受门禁管**（2026-09-29）：遮罩一画就把图标禁了，而遮罩是
        //   **这同一个界面**画的 —— 弹窗的图标会被它自己的遮罩挡住，表现是「金额后面没有单位、
        //   而整行还按『文本 + 单位』居中 ⇒ 看着偏左、跟上一行对不齐」（用户报的确认交付弹窗）。
        //   ⚠ 本函数只被**弹窗那一族**调用（购买确认 / 交付审核 / 管理端）—— 列表悬停窗走的是
        //   别的渲染路径 ⇒ 在这里关掉最省，不必去动那 40 处 `drawScreenDimMask`。
        //   不复原：门禁由 ScreenMixin 每帧 HEAD 复位（`NineSlice.kt` 里也是这个约定）。
        com.shusheng.cobblemarket.client.suppressCurrencyIcons = false
        val font = MinecraftClient.getInstance().textRenderer
        val leftX = centerX - dividerW / 2
        // 分割线的**实际**宽度：居中版固定 MOVES_MAX_W（三条线一样长）、左对齐版撑满内容宽。
        // ⚠ 证章「一行排几个」要跟着**它**走，而不是跟着内容宽 `dividerW`（见下面 marksLine 分支的说明）
        val marksAvailW = if (alignLeft) dividerW else MOVES_MAX_W
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
        // 类型行（**含太晶**）：走共用组装入口 —— 全项目这一行只有一份定义
        //（太晶那段稍不留神就漏一处，它在七个地方各有过一份拷贝）
        lines.add(
            TypeTextColors.typeLineWithTera(entry.primaryType, entry.secondaryType, entry.teraType) to 0xFFFFFF
        )
        // 极巨行：属性行**下面**、有才显示（普通精灵两者都是默认值 ⇒ 整行不画，布局不变）。
        // ⚠ 与 measureInfoLines 那行必须对应 —— 那边漏量，弹窗按内容收窄后这行就会被截断
        com.shusheng.cobblemarket.util.PokemonExtraLines.dynamax(entry.gmaxFactor, entry.dmaxLevel)
            ?.let { lines.add(it to 0xFFFFFF) }
        lines.add(
            natureAbilityLine(entry.natureBase, entry.nature, entry.ability) to 0xFFFFFF
        )
        if (entry.ball.isNotEmpty()) {
            // 球名走**稀有度色**（大师球在物品栏里是紫的）—— 共用入口，别再念词条（那样是白字）。
            // ⚠ 这一行在 1.2.x 上漏下浮过：1.3.0 早就改成 ballLine 了
            lines.add(com.shusheng.cobblemarket.util.ItemNameText.ballLine(entry.ball, entry.ballItem) to 0xFFFFFF)
        }
        var heldItemLine = -1
        if (hasHeldItem) {
            heldItemLine = lines.size
            lines.add(Text.translatable("cobblemarket.gui.tooltip_held") to 0xFFFFFF)
        }
        lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs") to 0xFFFFFF)
        lines.add(Text.literal("  $hp: ${TextUtil.ivText(entry.ivsHp, entry.htHp)}").append(Text.literal("  EV: ${entry.evsHp}").formatted(Formatting.RED)) to 0x66FF66)
        lines.add(Text.literal("  $atk: ${TextUtil.ivText(entry.ivsAtk, entry.htAtk)}").append(Text.literal("  EV: ${entry.evsAtk}").formatted(Formatting.RED)) to 0xFF6666)
        lines.add(Text.literal("  $def: ${TextUtil.ivText(entry.ivsDef, entry.htDef)}").append(Text.literal("  EV: ${entry.evsDef}").formatted(Formatting.RED)) to 0xFFCC66)
        lines.add(Text.literal("  $spa: ${TextUtil.ivText(entry.ivsSpAtk, entry.htSpAtk)}").append(Text.literal("  EV: ${entry.evsSpAtk}").formatted(Formatting.RED)) to 0x6699FF)
        lines.add(Text.literal("  $spd: ${TextUtil.ivText(entry.ivsSpDef, entry.htSpDef)}").append(Text.literal("  EV: ${entry.evsSpDef}").formatted(Formatting.RED)) to 0x66FF99)
        lines.add(Text.literal("  $spe: ${TextUtil.ivText(entry.ivsSpd, entry.htSpd)}").append(Text.literal("  EV: ${entry.evsSpd}").formatted(Formatting.RED)) to 0xFF99FF)
        lines.add(Text.translatable("cobblemarket.gui.friendship", entry.friendship) to 0xFF99CC)
        // 初始训练家行：亲密度**下面**、常驻（解析不出名字时整行不画）。
        // ⚠ 同样要跟 measureInfoLines 对应
        com.shusheng.cobblemarket.util.PokemonExtraLines.originalTrainer(entry.originalTrainer)
            ?.let { lines.add(it to 0xFFFFFF) }
        // 技能块（**总是画** —— Cobblemon 里不存在没技能的精灵）：亲密度下方「分割线 + 两行技能 + 分割线」，
        // 整块由 drawMovesBlock 画（此处只占一个坑位）。证章块与它**共用下面那条分割线**，自己不再带前导分割线。
        // 无技能数据（旧挂单）= 整块不画。
        var movesLine = -1
        if (MoveText.hasAny(entry.moves)) {
            movesLine = lines.size
            lines.add(null to 0)
        }
        // 证章区块（证章不影响能力，纯外观展示）：技能块下方，图标行 + 下分割线（一行几个跟随可用宽）
        // 服务端直接传纹理路径（不依赖客户端 Marks 注册表解析）
        var marksLine = -1
        if (entry.marks.isNotEmpty()) {
            marksLine = lines.size
            lines.add(null to 0)
            lines.add(null to 0)
        }
        var priceRow = -1   // ⚠ 必须在**构造行之前**声明（下面 if 里赋值，绘制循环里用）
        if (footer == null) {
            // 默认尾部：卖家 + 价格（市场确认弹窗 / 待交付 / 管理端那一族）
            lines.add(
                Text.translatable("cobblemarket.gui.tooltip_seller").append(Text.literal(entry.sellerName)) to 0xFFFFFF
            )
            // ⚠ 货币单位**不拼进文本**，由下面的绘制循环补在行尾（物品货币时是那件物品的图标）——
            //   先记下标（价格行是这里 add 的，但后面可能还有行，不能靠数）
            priceRow = lines.size
            lines.add(
                Text.translatable("cobblemarket.gui.tooltip_price").append(
                    Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ").formatted(Formatting.GOLD)) to 0xFFFFFF
            )
        } else {
            // 调用方自带的尾部：拍卖播报那条要的是「卖家 / 当前价 / 起拍价 / 加价 / 倒计时 / 领先者」
            // （市场那套的「价格」对拍卖没有意义 —— 拍卖有三个金额概念）
            lines.addAll(footer)
        }

        var y = startY
        var i = 0
        while (i < lines.size) {
            val (line, color) = lines[i]
            when {
                i == movesLine -> {
                    // 技能块：上分割线 + 两行技能 + 下分割线（整块自画，返回值即占用高度）
                    // ⚠ 左对齐走 `...Left` 那一支（分割线**撑满内容宽**）；居中版才是固定的 MOVES_MAX_W
                    y += if (alignLeft) drawMovesBlockLeft(context, entry.moves, leftX, dividerW, y)
                    else drawMovesBlock(context, entry.moves, centerX, y, dividerW)
                }
                line == null && i == marksLine -> {
                    // 证章图标行：**走共用入口 [drawMarksRow]**（一行放几个、居中口径、以及「+N」的
                    // 二级悬停登记都在那边 —— 全项目只有那一份定义，别再在这儿内联一份）。
                    // ⚠ 可用宽必须传**分割线的实际宽度** `marksAvailW`，不是内容宽 `dividerW`：
                    //   居中版的分割线固定 MOVES_MAX_W（三条线一样长），而 dividerW 是内容宽（能到 300+）
                    //   —— 拿内容宽算「一行几个」，图标就会**排到分割线外面**
                    //  （2026-09-29 用户报：市场购买弹窗证章多时第一行超出了分割线）
                    y += drawMarksRow(
                        context = context, marks = entry.marks,
                        leftX = centerX - marksAvailW / 2, y = y,
                        availableWidth = marksAvailW, centered = true,
                        mouseX = mouseX, mouseY = mouseY
                    )
                }
                line == null -> {
                    // 分割线：居中版宽度与技能块统一走 MOVES_MAX_W（三条线一样长）；
                    // 左对齐版**撑满内容宽**（照市场行悬停窗 —— 那边分割线就是跟提示框同宽）
                    if (alignLeft) {
                        context.fill(leftX, y + 4, leftX + dividerW, y + 5, 0xFF888888.toInt())
                    } else {
                        drawDivider(context, centerX, y, MOVES_MAX_W)
                    }
                    y += DIVIDER_H
                }
                i == heldItemLine -> {
                    // 携带物行：文字 + 物品图标整体居中（图标占 14px，文字按去掉图标后的宽度截断）
                    // 玩家在 Cobblemon 里把携带物设成「隐藏」时补一句灰字：模型上不显示它、而信息行照常列出，
                    // 不注明的话玩家会以为「明明带了东西却没显示」是数据错了（用户 2026-09-26）
                    val hiddenNote = if (!entry.heldItemVisible) Text.translatable("cobblemarket.gui.held_hidden") else null
                    val noteW = hiddenNote?.let { font.getWidth(it) } ?: 0
                    val heldText = TextUtil.truncateStyled(line, (maxWidth - 14 - noteW).coerceAtLeast(0))
                    val textW = font.getWidth(heldText)
                    val x = if (alignLeft) leftX else centerX - (textW + 14 + noteW) / 2
                    context.drawTextWithShadow(font, heldText, x, y, color)
                    heldIconStack(entry.heldItemId, entry.heldItemNbt)?.let { heldStack ->
                        com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                            itemStack = heldStack,
                            x = (x + textW + 2).toDouble(), y = y.toDouble(), scale = 0.6, matrixStack = context.matrices
                        )
                    }
                    hiddenNote?.let { context.drawTextWithShadow(font, it, x + textW + 14, y, 0xAAAAAA) }
                    y += 10
                }
                i == 0 -> {
                    // 第一行（名字★Lv）走公共名字行函数（带公母图标 + 体型字母）：
                    // 尾部图标不参与文本宽度，截断预算里先扣掉（居中总宽 = 名字 + 尾部 + 6）
                    val tail = nameTailWidth(entry.gender, entry.sizeCategory)
                    val nameBudget = if (tail == 0) maxWidth else maxWidth - tail - 6
                    val nameText = TextUtil.truncateStyled(line, nameBudget)
                    if (alignLeft) {
                        drawNameLineLeft(context, nameText, entry.gender, leftX, y, color, entry.sizeCategory)
                    } else {
                        drawNameLine(context, nameText, entry.gender, centerX, y, color, entry.sizeCategory)
                    }
                    y += 10
                }
                i == priceRow -> {
                    // 价格行：货币单位补在行尾（物品货币时是那件物品的图标）。
                    // ⚠ 单位不在文本里 ⇒ 居中/左对齐都要按「文本 + 单位」的总宽重算；
                    //   ⚠ `drawTextWithShadow` 返回的是**文本结束坐标**（不是宽度），单位直接画在它上面
                    val drawn = TextUtil.truncateStyled(line, maxWidth)
                    val w = font.getWidth(drawn)
                    val x = if (alignLeft) leftX else centerX - (w + currencyUnitWidth(font)) / 2
                    val endX = context.drawTextWithShadow(font, drawn, x, y, color)
                    drawCurrencyUnit(context, font, endX, y, 0xFFAA00)
                    y += 10
                }
                else -> {
                    // 居中版用"算好 x 再左对齐"的方式画：drawCenteredTextWithShadow
                    // 在这套字体下会让行首汉字比后面的字高一点点，出价界面用 drawTextWithShadow 就正常。
                    // 超宽行按可用宽截断（保留分段着色），避免画出弹窗
                    val drawn = TextUtil.truncateStyled(line, maxWidth)
                    val x = if (alignLeft) leftX else centerX - font.getWidth(drawn) / 2
                    context.drawTextWithShadow(font, drawn, x, y, color)
                    y += 10
                }
            }
            i++
        }
        return y
    }
}
