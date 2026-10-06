package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.util.MoveText
import com.shusheng.cobblemarket.util.StatNames
import com.shusheng.cobblemarket.util.TypeFilter
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.client.FilterPanelAnimation
import com.shusheng.cobblemarket.client.heldIconStack
import com.shusheng.cobblemarket.client.heldIconStackForModel
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.PokemonHoverPreview
import com.shusheng.cobblemarket.client.icon.PokemonIconData
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.*
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 选择要上架的精灵界面；求购单交付模式（deliverOrderId 非 null）复用本界面：
 * 选择精灵后返回求购单界面继续交付流程（价格在交付弹窗输入）。
 */
class SellSelectScreen(private val deliverOrderId: java.util.UUID? = null) : Screen(Text.translatable(
    if (deliverOrderId != null) "cobblemarket.sell.deliver_title" else "cobblemarket.sell.title"
)) {

    private val deliverMode = deliverOrderId != null

    private var pokemonList = listOf<PokemonPreview>()
    private var selectedIndex = -1
    private var priceField: TextFieldWidget? = null
    private var searchField: TextFieldWidget? = null
    private var loaded = false
    // 分页拉取状态：loadedAll 为 true 表示服务端已返回全部 PC 精灵
    private var loadedAll = false
    private var closed = false
    // 防串扰：秒关再开界面时，旧界面的迟到响应会被 requestId 比对丢弃
    private val requestId = nextRequestId++
    private val rowHeight = 24
    private var scrollOffset = 0
    private var shinyOnly = false
    // 性别筛选（照精灵市场：""=不限 → MALE → FEMALE 循环）
    private var genderFilter = ""
    private var typeFilter = ""
    /** 太晶属性筛选（`cobblemon.type.xxx` **完整词条 key**；与「属性」共用同一套候选；空 = 不限） */
    private var teraFilter = ""
    /** 特性筛选（翻译 key，精确匹配）；选项跟着搜索框里的物种走，见 [updateAbilityOptions] */
    private var abilityFilter = ""
    private var abilityOptions = listOf<Pair<String, String>>()
    /** 性格筛选（翻译 key，按**生效**性格匹配，薄荷后为薄荷性格） */
    private var natureFilter = ""
    /** 超极巨化三态：0 = 不限，1 = 仅超极巨，2 = 仅非超极巨（照拍卖场那枚循环按钮） */
    private var gmaxFilter = 0

    // [PC] 图标：Cobblemon 电脑物品栈（lazy 缓存，避免每帧查注册表）
    private val pcItemStack: ItemStack by lazy { ItemStack(Registries.ITEM.get(Identifier.of("cobblemon", "pc"))) }
    private val ivExact = IntArray(6) { -1 }
    // IV 比较方式：0 = 等于（默认），1 = 大于等于，2 = 小于等于
    private val ivOps = IntArray(6)
    private val ivOpButtons = mutableMapOf<Int, NineSliceButton>()

    /**
     * 列表起始 y。⚠ **渲染 / 点击命中 / 滚动上限三处都用它**，别再写死数字 ——
     * 2026-09-22 实测：滚动上限那处曾写死 `96`、而渲染处是 `100`，**两处不一致**差 4px ⇒
     * 在特定屏幕高度下算出的可视行数相差 1 ⇒ 滚轮允许的最大偏移比渲染时少 1 ⇒ 最后一行永远滚不到。
     * （同款坑此前在「选中行图标 y」那处也犯过一次并被修，见 `selectedY` 附近的注释。）
     *
     * ⚠ **起点 + 底部预留（48）与行高（24）的整除关系**决定底部留多少空 ——
     * 老值 100 时 `100+48 = 148 = 6×24 + 4`，永远差 4px 够不着一行、余下 20px 空隙；
     * 96 时 `144` 整除 ⇒ 多显示一行（与「选择上架的**物品**界面」余数行为一致）；
     * 现在 72：`72+48 = 120 = 5×24` 同样整除。
     * ⚠ 别把它误认成"那个写死 96 的老 bug" —— 那是**三处不一致**，现在是三处**一致地用同一个值**。
     *
     * 96 → 72（2026-09-24）：筛选控件搬进右侧面板后主面板只剩「搜索框那一行」（y=44、底 64），
     * 分割线画在 68、列表从 72 起 —— 比原来的 96 又多显示一行。
     */
    private val listStartY = 72
    // 特训筛选三态：0 = 不限，1 = 仅含训练，2 = 仅不含训练
    private var htFilter = 0
    private var hpF: TextFieldWidget? = null; private var atkF: TextFieldWidget? = null; private var defF: TextFieldWidget? = null
    private var spaF: TextFieldWidget? = null; private var spdF: TextFieldWidget? = null; private var speF: TextFieldWidget? = null

    // 面板内的展开列表（照精灵市场：不限 + 18 属性 / 特性 / 性格，限高滚动、盖住谁藏谁；
    // 「属性」与「太晶」共用同一份属性候选）
    /** 当前展开的是哪一个："" / "type" / "tera" / "ability" / "nature"（互斥展开） */
    private var filterListOpen = ""
    private var filterListScroll = 0
    /** 展开列表的实际底边 y（每行 14，从被点按钮的正下方起）。面板里被它盖住的按钮要 `visible = false` */
    private var filterListBottomY = 0
    private val filterOptionButtons = mutableListOf<NineSliceButton>()
    private var shinyBtn: NineSliceButton? = null
    private var genderBtn: NineSliceButton? = null
    private var typeBtn: NineSliceButton? = null
    private var teraBtn: NineSliceButton? = null
    private var gmaxBtn: NineSliceButton? = null
    private var abilityBtn: NineSliceButton? = null
    private var natureBtn: NineSliceButton? = null
    private var sellBtn: NineSliceButton? = null
    private var htBtn: NineSliceButton? = null
    private var filterToggleButton: NineSliceButton? = null
    /** 每个 IV 输入框右侧的单框重置 ↺（只清这一项，不必去点总重置把六项全清掉） */
    private val ivResetButtons = mutableListOf<NineSliceButton>()
    /** 整体重置 ↺（面板第一行右端） */
    private var resetBtn: NineSliceButton? = null

    /**
     * 右侧筛选面板是否展开（「筛选」按钮开关，**默认收起**，照精灵市场）。
     * ⚠ 开关**只切可见性、不重建界面**：重建会清空 [iconData] 里精灵图标的跨帧动画状态
     *   —— 表现就是「点一下筛选，正在睡觉的精灵突然站起来一下再睡回去」（精灵市场踩过的坑）。
     */
    private var filterExpanded = false
    /** 面板开关动画（展开时从「筛选」按钮放大长出来、收起缩回）。⚠ 必须与 [filterExpanded] 成对用 */
    private val filterAnim = FilterPanelAnimation()
    /** 面板内展开列表的顶/底边（垫不透明底、隐藏被它盖住的面板按钮，都读这两个值） */
    private var filterListTopY = 0

    /** 面板内滚动偏移（内容比面板高时才有用，见 [filterPanelHeight]） */
    private var filterPanelScroll = 0

    /**
     * 面板内控件的「基准 y」（= 未滚动时的位置）。
     * 滚动要改控件的 `y`，还得能还原 —— 所以创建时统一登记一次，滚动只做 `base - scroll`。
     * ⚠ 别再另抄一份坐标常量表：那正是"三处各写一遍、改一处漏两处"的老毛病（照精灵市场）。
     */
    private val filterPanelControls =
        mutableListOf<Pair<net.minecraft.client.gui.widget.ClickableWidget, Int>>()

    /**
     * 右侧筛选面板的**内容高度**（由内容定，不跟随主面板铺到屏幕底边，照精灵市场）。
     *
     * 布局：闪光+性别+↺ 一行 → 属性 / 太晶 / 特性 / 性格 各一行 → 特训+超极巨化 一行
     *      → IV 六行（每行 22）。
     * 最后一行 `Spd` 输入框在 y=262、高 16（底 278），加 12 底部余量 ⇒ 290。
     * ⚠ 面板里再加一行时记得同步改这里。
     */
    private val FILTER_PANEL_HEIGHT = 290

    /**
     * 面板的**实际高度** = 内容高度与屏幕可用高度的较小值。
     *
     * ⚠ GUI 缩放设「自动」时逻辑屏高会变小（1080p + 缩放 4 只有 270），写死 290 会让面板戳出屏幕、
     *   底部的 IV 输入框看不见 —— 超出部分靠**面板内滚动**看（照精灵市场）。
     */
    private fun filterPanelHeight() = minOf(FILTER_PANEL_HEIGHT, height - 34)

    // 3D icon cache
    private val iconData = mutableMapOf<Int, PokemonIconData>()

    // 物种显示名缓存：species 字段是翻译 key，客户端本地翻译（随客户端语言），搜索与渲染共用。
    // 翻译缺失（数据包物种未配语言文件）时返回 key 原文，fallback 到资源名；缓存随界面实例销毁，
    // 语言切换重开界面即重建。
    private val speciesNameCache = mutableMapOf<String, String>()
    private fun speciesDisplay(p: PokemonPreview): String =
        speciesNameCache.getOrPut(p.species) {
            val t = Text.translatable(p.species).string
            if (t == p.species) p.speciesName else t
        }

    // startIdx > 0 时只构建新增条目的图标，避免分页追加时全量重建
    private fun buildIconCache(startIdx: Int = 0) {
        if (startIdx == 0) iconData.clear()
        for (i in startIdx until pokemonList.size) {
            val p = pokemonList[i]
            val id = Identifier.tryParse(p.speciesId) ?: continue
            val species = PokemonSpecies.getByIdentifier(id) ?: continue
            // 用精灵真实 aspects（性别形态/地区形态等），不再只加 shiny——
            // 否则雌性爱管侍这类性别差异物种会渲染成默认雄性模型
            val aspects = p.aspects.toMutableSet()
            if (p.shiny && "shiny" !in aspects) aspects.add("shiny")
            // 携带物同时喂给模型（Cobblemon 会渲染在手上/嘴上）与行内小图标
            val held = heldIconStack(p.heldItemId, p.heldItemNbt)
            iconData[i] = PokemonIconData(
                renderable = RenderablePokemon(species, aspects, heldIconStackForModel(p.heldItemVisible, p.heldItemId, p.heldItemNbt)),
                state = FloatingState(),
                ballStack = buildBallStack(p.ball),
                heldStack = held
            )
        }
    }

    /** 球种栈（解析失败为 null；渲染逻辑与原来一致：ball 非空就占位 12px，无论解析成败） */
    private fun buildBallStack(ball: String): ItemStack? {
        val ballId = Identifier.tryParse(ball.removePrefix("item.").replaceFirst(".", ":")) ?: return null
        return ItemStack(Registries.ITEM.get(ballId))
    }

    /** 携带物栈（空气视为无）；缓存供行渲染每帧复用 */
    override fun init() {
        super.init()
        val lx = width / 2 - 148
        val panelW = 296

        // Row 1: Search (full width)
        // 重建（弹窗关闭等 clearChildren+init）会清空搜索词与 IV 输入：保存恢复
        val savedSearch = searchField?.text ?: ""
        val savedIv = arrayOf(
            hpF?.text ?: "", atkF?.text ?: "", defF?.text ?: "",
            spaF?.text ?: "", spdF?.text ?: "", speF?.text ?: ""
        )
        // ⚠ 价格也必须存一份：窗口 resize / 改 GUI 缩放会重跑 init，不恢复的话玩家填好的价格就没了
        //   （搜索词与 IV 一直是存的，价格原先只在已删除的 rebuild() 里存，等于 resize 时漏掉）
        val savedPrice = priceField?.text ?: ""
        // Row 1 (y=44)：搜索框 | 价格框 | 上架·选择 | 筛选开关 —— 一行排到内容区右缘（lx+296）。
        // 2026-09-24 用户定：**搜索框缩短**，价格框与上架按钮挪到它右边；搜索框同时**加高到 20**
        // （原来 16，同行按钮 20 —— 原版输入框的文字基线算法与 NineSliceButton 同款，
        //  矮一截就会看着偏上、发细，用户原话「这个搜索框看起来很细」）
        // y 30 → 44（用户次日反馈「和上方返回按钮空隙太小」）：返回按钮底边 29，30 只有 1px 间隙；
        // 44 是精灵市场 / 管理端同一位置（间隙 15px），三个界面对齐
        searchField = TextFieldWidget(textRenderer, lx + 2, 44, 130, 20, Text.translatable("cobblemarket.sell.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.sell.search"))
        // 搜索/筛选变化时重建过滤缓存（render 每帧只读，见 rebuildFiltered 注释）
        searchField?.setChangedListener { updateAbilityOptions(it); rebuildFiltered() }
        addSelectableChild(searchField)
        addDrawableChild(searchField)
        searchField?.text = savedSearch

        // 返回按钮：右上角（与精灵市场统一；交付模式返回求购单界面）
        addDrawableChild(NineSliceButton(
            lx + panelW - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(if (deliverMode) BuyOrderScreen() else MarketScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        ))

        // 价格框 / 上架·选择按钮：与搜索框**同一行**（2026-09-24 用户定：搜索框缩短，价格框与上架
        // 按钮挪到它右边）。整行从内容区左缘排到右缘 lx+296：
        //   上架：搜索框 130 | 价格框 74 | 上架 28 | 筛选开关 50
        //   交付：搜索框 130 |      选择 106      | 筛选开关 50
        // ⚠ 价格框宽度 74 **不要缩**（1.3.0 那边中间还夹着货币切换按钮，挤了就缩搜索框）
        // ⚠ 价格框与上架按钮之间留 4px 标准间隙：紧贴看着像两个控件粘在一起（2026-09-21 用户报）
        // ⚠ 高度一律 20（搜索框也一样）：原版输入框文字 y = y+(h-8)/2 与 NineSliceButton 同一算式，
        //   h 不一致会出现「输入框比按钮高 1px、底边短 2px」的错位（2026-09-21 用户报过）
        priceField = TextFieldWidget(textRenderer, lx + 136, 44, 74, 20, Text.literal(""))
        priceField?.setPlaceholder(Text.translatable("cobblemarket.sell.price_placeholder"))
        priceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addSelectableChild(priceField)
        addDrawableChild(priceField)
        priceField?.text = savedPrice
        // 交付模式：价格在求购单交付弹窗输入，隐藏价格框（选择按钮占这一格）
        priceField?.visible = !deliverMode

        // 上架按钮：普通模式用图标（sell.png）替掉「上架」二字，按钮能窄一大截；交付模式仍是文字，
        // 宽度补满到筛选开关左边（106 = lx+136 到 lx+242，减去与开关之间的 4px 间隙）
        sellBtn = NineSliceButton(
            if (deliverMode) lx + 136 else lx + 214, 44, if (deliverMode) 106 else 28, 20,
            if (deliverMode) Text.translatable("cobblemarket.sell.deliver_select") else Text.literal(""),
            { if (deliverMode) deliverSelected() else sellSelected() },
            iconLeft = if (deliverMode) null else Identifier.of("cobblemarket", "textures/gui/sell.png"),
            // 图标 16×16：按钮高 20 减上下各 2px（同市场那批图标按钮的惯例）。贴图 48×48 纵向自带
            // 约 12px 透明留白，画出来红块 ≈16×11.7；还想更醒目可加大（20 即撑满按钮高度）
            iconTexW = 48, iconTexH = 48, iconScale = 16 / 48f,
            // 普通模式是纯图标（sell.png），要给悬停提示；交付模式本身就是文字按钮，不需要
            tooltip = if (deliverMode) null else Text.translatable("cobblemarket.sell.sell")
        )
        addDrawableChild(sellBtn)

        // 「筛选」开关（行右端）：开关右边那块筛选面板（上架/交付两种模式都有筛选需求）
        filterToggleButton = NineSliceButton(
            lx + panelW - 50, 44, 50, 20,
            Text.literal(""),
            { toggleFilters() },
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        )
        addDrawableChild(filterToggleButton)
        updateFilterToggleVisual()

        // ── 右侧筛选面板内的控件：**常驻创建**，开关只切可见性（别用 `if (filterExpanded)` 包起来
        //    —— 那会让开关走"重建界面"的老路，而重建会清空精灵图标的跨帧动画状态）──
        // 搬过来的理由与精灵市场完全一样：原先它们在主面板 y=50 / y=72，属性点开是**浮层**、
        // 会盖住下面的列表行 ⇒ 要维护四类补丁（被盖住的行不画 / 不算悬停 / 隐藏下层控件 / 点击排除）。
        // 现在浮层只盖同一块面板里的几个按钮，那四类补丁整个删掉；列表起点也从 100 回到 58。
        val fx = MarketPanelBackground.filterLeft(width) + 4
        val fw = MarketPanelBackground.filterWidth(width) - 8
        // 「特训 + 超极巨化」那一行对半分（4px 间距）：中文「超极巨化：是」54px / 英文 "Gmax: Yes" 54px，
        // 半宽（最窄一档 72px）放得下
        val half = (fw - 4) / 2

        // 第一行：★闪光(30) + ♂公母(24) + ↺重置（贴面板右端）—— 与精灵市场同尺寸、同顺序
        // （精灵市场那一行是「闪光 | 性别 | 排序」，本界面没有排序，把 ↺ 补在空出来的右端）
        shinyBtn = NineSliceButton(
            fx, 20, 30, 20,
            Text.literal(if (shinyOnly) "★" else "☆"),
            { toggleShiny() },
            if (shinyOnly) GOLD_COLOR else UNSET_FILTER_COLOR
        )
        addDrawableChild(shinyBtn)

        // 公母按钮：照精灵市场（不限态 ♂♀ 双图标，循环 不限→仅公→仅母）
        genderBtn = NineSliceButton(
            fx + 34, 20, 24, 20,
            Text.literal(""),
            { cycleGender() },
            iconTexW = 6, iconTexH = 8, iconScale = 1.5f
        )
        updateGenderButton()
        addDrawableChild(genderBtn)

        // 重置：↺ **符号**（与精灵市场 / 管理端 / 创建拍卖同一个观感；纯图标按钮给悬停提示）
        resetBtn = NineSliceButton(
            fx + fw - 20, 20, 20, 20,
            Text.literal("↺"),
            { resetFilters() },
            tooltip = Text.translatable("cobblemarket.gui.reset")
        )
        addDrawableChild(resetBtn)

        // 属性 / 太晶 / 特性 / 性格 / 特训：各占整行，展开浮层贴在各自按钮的正下方
        typeBtn = NineSliceButton(
            fx, 42, fw, 20,
            typeButtonText(),
            { toggleFilterList("type") },
            UNSET_FILTER_COLOR   // 颜色由「值」自己带（见 filterButtonText），这里必须留白
        )
        addDrawableChild(typeBtn)

        // 太晶属性：与「属性」共用同一套候选列表（18 属性 +「不限」）
        // ⚠ 与属性各占**一整行**：英文带前缀最长 "Tera: Electric" = 84px，半宽（最窄 72px）塞不下
        teraBtn = NineSliceButton(
            fx, 64, fw, 20,
            teraButtonText(),
            { toggleFilterList("tera") },
            UNSET_FILTER_COLOR   // 同属性按钮：颜色由「值」自己带
        )
        addDrawableChild(teraBtn)

        // 特性：选项跟着搜索框里的物种走（见 updateAbilityOptions）
        abilityBtn = NineSliceButton(
            fx, 86, fw, 20,
            abilityButtonText(),
            { toggleFilterList("ability") },
            UNSET_FILTER_COLOR   // 颜色由「值」自己带
        )
        addDrawableChild(abilityBtn)

        // 性格：25 种，按**生效**性格匹配（薄荷后为薄荷性格）
        natureBtn = NineSliceButton(
            fx, 108, fw, 20,
            natureButtonText(),
            { toggleFilterList("nature") },
            UNSET_FILTER_COLOR   // 颜色由「值」自己带
        )
        addDrawableChild(natureBtn)

        // 特训筛选（三态循环）：与超极巨化同排各占一半
        // ⚠ 文案是**一整个词条**（「特训：不限」/「仅特训」/「不含特训」），没有"前缀 + 值"可拆 ⇒ 整段金色
        htBtn = NineSliceButton(
            fx, 130, half, 20,
            htButtonText(),
            { toggleHtFilter() },
            if (htFilter != 0) GOLD_COLOR else UNSET_FILTER_COLOR
        )
        addDrawableChild(htBtn)

        // 超极巨化：三态循环（不限 → 仅超极巨 → 仅非超极巨），与特训同排各占一半
        gmaxBtn = NineSliceButton(
            fx + half + 4, 130, half, 20,
            gmaxButtonText(),
            { toggleGmax() },
            UNSET_FILTER_COLOR   // 颜色由「值」自己带
        )
        addDrawableChild(gmaxBtn)

        // IV 六项：面板内**竖排**，每行 = [输入框 84] [三态 40] [单框重置 ↺ 20]，
        // 行距 22 —— 与精灵市场同数值、同顺序（84 + 4 + 40 + 4 + 20 = 152 正好占满面板内容宽）
        fun mkIv(x: Int, y: Int, ph: String): TextFieldWidget {
            val f = TextFieldWidget(textRenderer, x, y, 84, 16, Text.literal(""))
            f.setPlaceholder(Text.literal(ph))
            f.setTextPredicate { it.length <= 2 && it.all { c -> c.isDigit() } }
            addSelectableChild(f); addDrawableChild(f)
            return f
        }
        ivOpButtons.clear()
        ivResetButtons.clear()
        hpF  = mkIv(fx, 152, "HP");  addIvOpButton(fx + 88, 152, 0); addIvResetButton(fx + 132, 152, 0)
        atkF = mkIv(fx, 174, "ATK"); addIvOpButton(fx + 88, 174, 1); addIvResetButton(fx + 132, 174, 1)
        defF = mkIv(fx, 196, "DEF"); addIvOpButton(fx + 88, 196, 2); addIvResetButton(fx + 132, 196, 2)
        spaF = mkIv(fx, 218, "SpA"); addIvOpButton(fx + 88, 218, 3); addIvResetButton(fx + 132, 218, 3)
        spdF = mkIv(fx, 240, "SpD"); addIvOpButton(fx + 88, 240, 4); addIvResetButton(fx + 132, 240, 4)
        speF = mkIv(fx, 262, "Spd"); addIvOpButton(fx + 88, 262, 5); addIvResetButton(fx + 132, 262, 5)
        val ivFields = arrayOf(hpF, atkF, defF, spaF, spdF, speF)
        savedIv.forEachIndexed { i, t -> ivFields[i]?.text = t }

        // 登记面板内控件：记下基准 y，滚动时按它重定位（见 filterPanelControls 的说明）
        filterPanelControls.clear()
        listOfNotNull(
            shinyBtn, genderBtn, resetBtn, typeBtn, teraBtn, abilityBtn,
            natureBtn, htBtn, gmaxBtn, hpF, atkF, defF, spaF, spdF, speF
        ).forEach { filterPanelControls += it to it.y }
        ivOpButtons.values.forEach { filterPanelControls += it to it.y }
        ivResetButtons.forEach { filterPanelControls += it to it.y }
        // 建完先刷一遍位置与可见性：面板收起时它们必须立刻藏起来
        applyFilterVisibility()

        rebuildFiltered()

        if (!loaded) {
            sendToServer(RequestMyPokemonPayload(0, requestId))
            loaded = true
        }
    }

    // （原 `rebuild()` = clearChildren + init + 恢复输入，2026-09-24 删除：筛选控件搬进右侧面板后
    //   它就没有调用点了 —— 筛选变化改成 rebuildFiltered()，不再推倒重建界面）

    override fun close() {
        closed = true
        super.close()
    }

    /**
     * 属性候选（key = **裸属性名**如 `fire`，label = 翻译 key `cobblemon.type.fire`）。
     *
     * ⚠ 匹配一律走 [TypeFilter.matches] —— 它内部按 `substringAfterLast(".")` 规范化，
     *   裸名与完整词条 key **两种写法都能命中**（[PokemonPreview.primaryType] 存的是完整 key，照样对得上）。
     *   存裸名是因为：按钮要按 `cobblemon.type.$key` 拼词条显示，多选时还要用 `|` 把两个拼起来。
     */
    private val allTypes = listOf("" to "") + listOf("normal","fire","water","electric","grass","ice","fighting","poison","ground","flying",
        "psychic","bug","rock","ghost","dragon","dark","steel","fairy").map { it to "cobblemon.type.$it" }

    // ── 属性展开列表（照精灵市场：不限+19 属性、属性色、限高滚动、展开隐藏下层与行内容） ──

    // ── 展开列表（照精灵市场：不限 + 选项、限高滚动、点选后收起） ──

    private fun toggleFilterList(kind: String) {
        filterListOpen = if (filterListOpen == kind) "" else kind
        filterListScroll = 0
        rebuildFilterList()
    }

    /** 当前展开列表的选项：属性 / 太晶（与属性同一份）/ 特性（跟搜索框里的物种走）/ 性格 */
    private fun filterListOptions(): List<Pair<String, String>> = when (filterListOpen) {
        "type" -> allTypes
        // 太晶与属性共用同一份候选（18 属性 +「不限」）
        "tera" -> allTypes
        "ability" -> listOf("" to "") + abilityOptions
        "nature" -> listOf("" to "") + natureOptions
        else -> emptyList()
    }

    /** 当前展开列表是被哪枚按钮点开的 —— 浮层贴在它正下方（位置只在这里算一次，别人别重写一遍） */
    private fun filterListSourceButton(): NineSliceButton? = when (filterListOpen) {
        "type" -> typeBtn
        "tera" -> teraBtn
        "ability" -> abilityBtn
        "nature" -> natureBtn
        else -> null
    }

    private fun rebuildFilterList() {
        filterOptionButtons.forEach { remove(it) }
        filterOptionButtons.clear()
        if (filterListOpen.isEmpty()) {
            // 收起：浮层范围归零，被盖住的按钮立刻回来
            filterListTopY = 0
            filterListBottomY = 0
            applyFilterVisibility()
            return
        }
        // 浮层画在**被点按钮的正下方**（会盖住它下面那几个按钮，收起就回来）——
        // 右侧面板里只有筛选控件、没有列表行，所以"盖住几个按钮"远比原来"盖住列表行"可接受
        // （原来那套「被盖住的行不渲染 / 不算悬停 / 隐藏下层控件 / 点击排除」的补丁因此整个删掉）。
        // ⚠ 读控件自己的 `y`，不再维护第二份坐标表
        val sourceBtn = filterListSourceButton() ?: return
        val fx = MarketPanelBackground.filterLeft(width) + 4
        val fw = MarketPanelBackground.filterWidth(width) - 8
        val listTop = sourceBtn.y + 22
        // 面板底边往下就没地方了，自动限制行数（小屏 / GUI 缩放自动档）
        val maxRows = minOf(MAX_FILTER_LIST_ROWS, maxOf(1, (height - 32 - listTop) / FILTER_LIST_ROW_H))
        val options = filterListOptions()
        filterListTopY = listTop
        filterListBottomY = listTop + minOf(options.size - filterListScroll, maxRows) * FILTER_LIST_ROW_H
        options.drop(filterListScroll).take(maxRows).forEachIndexed { i, (key, label) ->
            val display = if (key.isEmpty()) Text.translatable("cobblemarket.gui.filter_any").string
                else Text.translatable(label).string.let { t -> if (t == label) label else t }
            val btn = NineSliceButton(
                fx, listTop + i * FILTER_LIST_ROW_H, fw, FILTER_LIST_ROW_H,
                Text.literal(com.shusheng.cobblemarket.util.TextUtil.truncateString(display, 260)),
                { selectFilterOption(filterListOpen, key) },
                // 选项文字：属性 / 太晶用对应属性色（两份列表共用同一套候选），特性 / 性格保持白色
                if ((filterListOpen == "type" || filterListOpen == "tera") && key.isNotEmpty()) typeColor(key) else 0xFFFFFF
            )
            // 选中项靠**按钮自己的选中态**（背景第三段）表达，不再在文字前加 `● `
            // （用户 2026-09-28：按钮已经有选中态了，再用圆圈是同一件事的两套表达）
            btn.selectedVisual = isFilterSelected(filterListOpen, key)
            filterOptionButtons.add(btn)
            addDrawableChild(btn)
        }
        // 浮层铺好后，被它盖住的面板按钮藏起来（读各自的 y，见 applyFilterVisibility）
        applyFilterVisibility()
    }

    private fun isFilterSelected(kind: String, key: String): Boolean = when (kind) {
        // ⚠ 属性是**多选**（最多 2 个）⇒ 选中态要问"在不在已选集合里"，不是 `==`
        "type" -> key.isNotEmpty() && key in TypeFilter.keys(typeFilter)
        "tera" -> key.isNotEmpty() && key == teraFilter
        "ability" -> key.isNotEmpty() && key == abilityFilter
        "nature" -> key.isNotEmpty() && key == natureFilter
        else -> false
    }

    private fun selectFilterOption(kind: String, key: String) {
        when (kind) {
            "type" -> {
                // 多选切换：已选 → 取消；未选 → 加上；**已满 2 个再点第 3 个 → 失败音、不改状态**
                val picked = TypeFilter.toggle(TypeFilter.keys(typeFilter), key)
                if (picked == null) playFailSound() else typeFilter = TypeFilter.join(picked)
                typeBtn?.setMessage(typeButtonText())
                typeBtn?.textColor = UNSET_FILTER_COLOR   // 颜色由「值」自己带
            }
            "tera" -> {
                teraFilter = key
                teraBtn?.setMessage(teraButtonText())
                teraBtn?.textColor = UNSET_FILTER_COLOR   // 颜色由「值」自己带
            }
            "ability" -> {
                abilityFilter = key
                abilityBtn?.setMessage(abilityButtonText())
                abilityBtn?.textColor = UNSET_FILTER_COLOR   // 颜色由「值」自己带
            }
            "nature" -> {
                natureFilter = key
                natureBtn?.setMessage(natureButtonText())
                natureBtn?.textColor = UNSET_FILTER_COLOR   // 颜色由「值」自己带
            }
        }
        // ⚠ **属性是多选**（最多 2 个）⇒ 选中 / 取消都**不关浮层**（选完第一个窗就没了的话没法接着选第二个）。
        //   收起仍靠再点一下「属性」按钮（[toggleFilterList]）。`rebuildFilterList()` 照调：刷新选中态高亮
        if (kind != "type") filterListOpen = ""
        rebuildFilterList()
        // 筛选变化只重建过滤缓存 + 回到顶部，**不重建界面**（重建会清空精灵图标的跨帧动画状态）
        rebuildFiltered()
        scrollOffset = 0
    }

    // ── 筛选按钮文字（「标签 + 值」，与精灵市场同一口径） ──

    private fun filterPanelBtnW() = MarketPanelBackground.filterWidth(width) - 8

    /**
     * 属性按钮文字 =「属性：」+ 各属性名 —— **每段属性名自己带属性色**：「属性：」前缀与 `+`
     * 都是陪衬文字不设色（CLAUDE.md：带色的文字不能当父节点）。最多 2 个，顺序 = 玩家点击顺序。
     *
     * ⚠ 这里**不走 [filterButtonText]**：那个函数按 `value.string` 截断，会把分段样式整段丢掉。
     *   宽度也不必截断 —— 英文最坏 `Type: Fighting+Electric` = 138px，最窄一档按钮内容宽 140px。
     */
    private fun typeButtonText(): Text {
        val picked = TypeFilter.keys(typeFilter)
        val out = Text.translatable("cobblemarket.gui.type")
        if (picked.isEmpty()) return out.append(Text.translatable("cobblemarket.gui.filter_any"))
        picked.forEachIndexed { i, k ->
            if (i > 0) out.append(Text.literal("+"))
            out.append(Text.translatable("cobblemon.type.$k")
                .setStyle(net.minecraft.text.Style.EMPTY.withColor(typeColor(k))))
        }
        return out
    }

    /** 太晶属性：与「属性」同一套候选列表（18 属性 +「不限」），颜色同样按属性上色 */
    private fun teraButtonText(): Text {
        val any = teraFilter.isEmpty()
        val label = if (any) Text.translatable("cobblemarket.gui.filter_any") else Text.translatable(teraFilter)
        return filterButtonText("cobblemarket.gui.tera", label, if (any) null else typeColor(teraFilter))
    }

    /** 超极巨化：0 = 不限（只显示标签）、1 = 仅超极巨、2 = 仅非超极巨 */
    private fun gmaxButtonText(): Text {
        val label = when (gmaxFilter) {
            1 -> Text.translatable("cobblemarket.gui.filter_yes")
            2 -> Text.translatable("cobblemarket.gui.filter_no")
            else -> Text.translatable("cobblemarket.gui.filter_any")
        }
        return filterButtonText("cobblemarket.gui.gmax", label, if (gmaxFilter == 0) null else GOLD_COLOR)
    }

    private fun toggleGmax() {
        gmaxFilter = (gmaxFilter + 1) % 3
        gmaxBtn?.setMessage(gmaxButtonText())
        gmaxBtn?.textColor = UNSET_FILTER_COLOR   // 颜色由「值」自己带
        rebuildFiltered()
        scrollOffset = 0
    }

    private fun abilityButtonText(): Text {
        val any = abilityFilter.isEmpty()
        val label = if (any) Text.translatable("cobblemarket.gui.filter_any") else Text.translatable(abilityFilter)
        return filterButtonText("cobblemarket.buy_order.ability_label", label, if (any) null else GOLD_COLOR)
    }

    private fun natureButtonText(): Text {
        val any = natureFilter.isEmpty()
        val label = if (any) Text.translatable("cobblemarket.gui.filter_any") else Text.translatable(natureFilter)
        return filterButtonText("cobblemarket.buy_order.nature_label", label, if (any) null else GOLD_COLOR)
    }

    /**
     * 筛选按钮文字 =「标签 + 值」（「特性：静电」/「特性：任意」）—— **与精灵市场同一口径**。
     * ⚠ 值超宽时按按钮宽截断（两侧各留 4px）。
     *
     * ⚠ **颜色只上在「值」上，前缀是陪衬文字不设色**（用户 2026-09-28 实测报的：原来整段染成属性色，
     *   连「属性：」这几个字都跟着变色）。走的是 CLAUDE.md 那条规矩：带色的文字不能当父节点 ——
     *   前缀（无色）当父、把颜色设在**值**自己的样式上，前缀就吃按钮的默认白字。
     *   ⚠ 同时按钮的 `textColor` 必须留**白色**：那是"整段默认色"，设成属性色会把前缀又染一遍。
     */
    private fun filterButtonText(labelKey: String, value: Text, valueColor: Int? = null): Text {
        val prefix = Text.translatable(labelKey)
        val shown = Text.literal(com.shusheng.cobblemarket.util.TextUtil.truncateString(
            value.string, filterPanelBtnW() - 8 - textRenderer.getWidth(prefix)))
        val valueText = if (valueColor != null)
            shown.setStyle(net.minecraft.text.Style.EMPTY.withColor(valueColor))
        else shown
        return prefix.append(valueText)
    }

    /** 25 种性格（翻译 key, 显示名），与精灵市场 / 求购单一致 */
    private val natureOptions: List<Pair<String, String>> by lazy {
        com.cobblemon.mod.common.api.pokemon.Natures.all().map { n ->
            val key = "cobblemon.nature.${n.name.path}"
            val t = Text.translatable(key).string
            key to (if (t == key) n.displayName else t)
        }
    }

    /** 搜索框物种解析 → 特性筛选选项；物种变化时重置失效选择（照精灵市场） */
    private fun updateAbilityOptions(text: String) {
        val trimmed = text.trim()
        val species = if (trimmed.isEmpty()) null else {
            if (trimmed.contains(":")) {
                Identifier.tryParse(trimmed)?.let { PokemonSpecies.getByIdentifier(it) }
            } else {
                val byName = try { PokemonSpecies.getByName(trimmed) } catch (_: Exception) { null }
                // 精确优先：中文名互为子串时别让「鬼斯」被「鬼斯通」截胡（见 SpeciesText）
                byName ?: com.shusheng.cobblemarket.util.SpeciesText.candidatesByNameOrId(trimmed).firstOrNull()
            }
        }
        abilityOptions = species?.abilities?.map { pa ->
            val key = "cobblemon.ability.${pa.template.name}"
            val t = Text.translatable(key).string
            key to (if (t == key) pa.template.displayName else t)
        } ?: emptyList()
        if (abilityFilter.isNotEmpty() && abilityOptions.none { it.first == abilityFilter }) {
            abilityFilter = ""
            abilityBtn?.setMessage(abilityButtonText())
            abilityBtn?.textColor = UNSET_FILTER_COLOR
        }
    }

    // 过滤结果缓存（照黑名单界面的 rebuildFiltered 模式）：数据/搜索/IV/筛选变化时重建，
    // render 每帧只读。indexedFilteredCache 同缓存带原始下标，省去行循环里每帧 indexOf。
    private var filteredCache = listOf<PokemonPreview>()
    private var indexedFilteredCache = listOf<IndexedValue<PokemonPreview>>()

    private fun filteredList(): List<PokemonPreview> = filteredCache

    private fun rebuildFiltered() {
        syncIvFields()
        // 先 withIndex 再过滤：IndexedValue.index 保持 pokemonList 原始下标，
        // 供图标缓存 iconData（按原列表索引构建）正确取值（filteredCache.withIndex() 的序号是过滤后位置，搜索后首行图标会错位）
        indexedFilteredCache = pokemonList.withIndex().filter { (_, p) ->
            val q = searchField?.text?.trim()?.takeIf { it.isNotEmpty() }
            (q == null || speciesDisplay(p).contains(q, ignoreCase = true) || p.speciesName.contains(q, ignoreCase = true)) &&
            (!shinyOnly || p.shiny) &&
            (genderFilter.isEmpty() || p.gender == genderFilter) &&
            // 属性：可选 1 个或 2 个（`fire` / `fire|ghost`）——
            // 1 个 = 主属性或副属性命中；2 个 = 主副**集合精确相等**（见 TypeFilter）
            TypeFilter.matches(p.primaryType, p.secondaryType, typeFilter) &&
            // 太晶属性：**精确匹配**（口径与精灵市场 / 拍卖场一致）。p.teraType 是 `cobblemon.type.xxx`
            // 完整词条 key ⇒ [allTypes] 里存的也必须是完整 key，拿裸的 `fire` 比就是恒不命中
            (teraFilter.isEmpty() || p.teraType == teraFilter) &&
            // 超极巨化：1 = 仅超极巨，2 = 仅非超极巨（照拍卖场那枚三态循环按钮）
            when (gmaxFilter) {
                1 -> p.gmaxFactor
                2 -> !p.gmaxFactor
                else -> true
            } &&
            // 特性精确匹配（翻译 key）；性格按**生效**性格匹配（薄荷改成该性格也算），照精灵市场
            (abilityFilter.isEmpty() || p.ability == abilityFilter) &&
            (natureFilter.isEmpty() || p.nature == natureFilter) &&
            // IV 按有效值匹配：特训项用特训值，未特训用真实值（原生 31 与训练 31 都命中）
            // 比较方式按 ivOps（默认等于）
            (ivExact[0] < 0 || ivMatch(ivOps[0], if (p.htHp >= 0) p.htHp else p.ivsHp, ivExact[0])) &&
            (ivExact[1] < 0 || ivMatch(ivOps[1], if (p.htAtk >= 0) p.htAtk else p.ivsAtk, ivExact[1])) &&
            (ivExact[2] < 0 || ivMatch(ivOps[2], if (p.htDef >= 0) p.htDef else p.ivsDef, ivExact[2])) &&
            (ivExact[3] < 0 || ivMatch(ivOps[3], if (p.htSpAtk >= 0) p.htSpAtk else p.ivsSpAtk, ivExact[3])) &&
            (ivExact[4] < 0 || ivMatch(ivOps[4], if (p.htSpDef >= 0) p.htSpDef else p.ivsSpDef, ivExact[4])) &&
            (ivExact[5] < 0 || ivMatch(ivOps[5], if (p.htSpd >= 0) p.htSpd else p.ivsSpd, ivExact[5])) &&
            // 特训筛选三态
            when (htFilter) {
                1 -> p.htHp >= 0 || p.htAtk >= 0 || p.htDef >= 0 || p.htSpAtk >= 0 || p.htSpDef >= 0 || p.htSpd >= 0
                2 -> !(p.htHp >= 0 || p.htAtk >= 0 || p.htDef >= 0 || p.htSpAtk >= 0 || p.htSpDef >= 0 || p.htSpd >= 0)
                else -> true
            }
        }.toList()
        filteredCache = indexedFilteredCache.map { it.value }
    }

    private fun updateGenderButton() {
        genderBtn?.iconLeft = if (genderFilter == "FEMALE") GENDER_ICON_FEMALE else GENDER_ICON_MALE
        // 不限态并排显示 ♂♀，选定态只留对应的那个
        genderBtn?.iconLeft2 = if (genderFilter.isEmpty()) GENDER_ICON_FEMALE else null
    }

    private fun cycleGender() {
        genderFilter = when (genderFilter) {
            "" -> "MALE"
            "MALE" -> "FEMALE"
            else -> ""
        }
        // ⚠ 原来靠 `rebuild()` 重建按钮来刷新图标；现在筛选不重建界面，得手动更一次
        updateGenderButton()
        rebuildFiltered()
        scrollOffset = 0
    }

    /** 闪光筛选（面板第一行那枚 ★/☆）：开 = 金色 ★，关 = 白色 ☆ */
    private fun toggleShiny() {
        shinyOnly = !shinyOnly
        shinyBtn?.setMessage(Text.literal(if (shinyOnly) "★" else "☆"))
        shinyBtn?.textColor = if (shinyOnly) GOLD_COLOR else UNSET_FILTER_COLOR
        rebuildFiltered()
        scrollOffset = 0
    }

    /** IV 单框重置 ↺：只清这一项的输入框与值（不必去点整体重置把六项全清掉），照精灵市场 */
    private fun addIvResetButton(x: Int, y: Int, index: Int) {
        val btn = NineSliceButton(
            x, y, 20, 16,
            Text.literal("↺"),
            {
                ivExact[index] = -1
                ivOps[index] = 0
                arrayOf(hpF, atkF, defF, spaF, spdF, speF)[index]?.text = ""
                syncIvOpButtons()
                rebuildFiltered()
                scrollOffset = 0
            },
            tooltip = Text.translatable("cobblemarket.gui.reset")
        )
        ivResetButtons += btn
        addDrawableChild(btn)
    }

    /** IV 三态比较按钮：= → ≥ → ≤ 循环（默认 = 与旧行为一致；非默认金色高亮） */
    private fun addIvOpButton(x: Int, y: Int, index: Int) {
        val btn = NineSliceButton(
            // 宽 16 → 40（与精灵市场一致）：搬进面板后右侧有余量，三态符号宽一点更好点
            x, y, 40, 16,
            Text.literal(if (ivOps[index] == 1) "≥" else if (ivOps[index] == 2) "≤" else "="),
            {
                ivOps[index] = (ivOps[index] + 1) % 3
                syncIvOpButtons()
                rebuildFiltered()
            },
            textColor = if (ivOps[index] != 0) GOLD_COLOR else 0xFFFFFF
        )
        ivOpButtons[index] = btn
        addDrawableChild(btn)
    }

    // ── 右侧筛选面板（照精灵市场：主面板保持居中，面板贴在它右缘外） ──

    /**
     * 面板内控件的可见性：**面板开着**才显示。
     *
     * ⚠ 被展开列表盖住的按钮必须 `visible = false`，**不能只靠"垫一块不透明底"**：
     *   原版 `ClickableWidget` 只要 `visible` 就照常响应点击 / 悬停 / 弹自己的 tooltip
     *   （`active = false` 拦不住），浮层下面的按钮会连点带悬停一起生效。
     *   判据一律**读控件自己的 `y`**，不维护第二份坐标表。
     */
    private fun applyFilterVisibility() {
        // ⚠ 动画进行中整块藏起来（面板内是 MC 控件，塞不进矩阵、不会跟着缩放），照精灵市场
        val animHidden = filterAnim.isActive()
        val covered = filterListOpen.isNotEmpty() && filterListBottomY > filterListTopY
        /** 某个 `y` 是否落在展开浮层的覆盖范围内（读控件自己的 y，不维护第二份坐标表） */
        fun coveredAt(y: Int?) = covered && y != null && y >= filterListTopY && y < filterListBottomY
        fun show(w: NineSliceButton?) {
            if (w != null) w.visible = filterExpanded && !coveredAt(w.y) && !animHidden
        }
        show(shinyBtn)
        show(genderBtn)
        show(resetBtn)
        show(typeBtn)
        show(teraBtn)
        show(abilityBtn)
        show(natureBtn)
        show(gmaxBtn)
        show(htBtn)
        listOf(hpF, atkF, defF, spaF, spdF, speF).forEachIndexed { i, f ->
            val vis = filterExpanded && !coveredAt(ivOpButtons[i]?.y) && !animHidden
            f?.visible = vis
            ivOpButtons[i]?.visible = vis
            ivResetButtons.getOrNull(i)?.visible = vis
        }
        // ⚠ 展开列表的**浮层按钮**是一组动态创建的控件，不在上面那份点名名单里 —— 单独按面板状态藏，
        //   否则面板收起 / 开关动画期间它们会留在屏幕上（正常路径下 rebuildFilterList 会 remove 掉）
        filterOptionButtons.forEach { it.visible = filterExpanded && !animHidden }
        filterToggleButton?.visible = true
        // 面板内滚动：按登记的基准 y 重定位，滚出面板范围的控件藏起来（滚轮改了 filterPanelScroll
        // 之后调本函数重排）。⚠ 放在最后 —— 它会覆盖上面 show() 设的 visible
        val panelTop = 4
        val panelBottom = 2 + filterPanelHeight()
        filterPanelControls.forEach { (w, baseY) ->
            w.y = baseY - filterPanelScroll
            if (w.y < panelTop || w.y + w.height > panelBottom) w.visible = false
        }
    }

    /**
     * 重置全部筛选条件（面板第一行右端那枚 ↺）。**不重建界面**，逐个还原各控件的文字与颜色。
     * ⚠ 不清搜索框：重置的是**筛选条件**，搜索词是玩家自己敲的、保留更符合预期（同拍卖场 / 创建拍卖）。
     */
    private fun resetFilters() {
        shinyOnly = false
        shinyBtn?.setMessage(Text.literal("☆"))
        shinyBtn?.textColor = UNSET_FILTER_COLOR
        genderFilter = ""
        updateGenderButton()
        typeFilter = ""
        typeBtn?.setMessage(typeButtonText())
        typeBtn?.textColor = UNSET_FILTER_COLOR
        teraFilter = ""
        teraBtn?.setMessage(teraButtonText())
        teraBtn?.textColor = UNSET_FILTER_COLOR
        abilityFilter = ""
        abilityBtn?.setMessage(abilityButtonText())
        abilityBtn?.textColor = UNSET_FILTER_COLOR
        natureFilter = ""
        natureBtn?.setMessage(natureButtonText())
        natureBtn?.textColor = UNSET_FILTER_COLOR
        htFilter = 0
        htBtn?.setMessage(htButtonText())
        htBtn?.textColor = UNSET_FILTER_COLOR
        gmaxFilter = 0
        gmaxBtn?.setMessage(gmaxButtonText())
        gmaxBtn?.textColor = UNSET_FILTER_COLOR
        filterListOpen = ""
        filterListScroll = 0
        filterListTopY = 0
        filterListBottomY = 0
        filterPanelScroll = 0
        ivExact.fill(-1)
        for (i in 0..5) ivOps[i] = 0
        syncIvOpButtons()
        arrayOf(hpF, atkF, defF, spaF, spdF, speF).forEach { it?.text = "" }
        scrollOffset = 0
        // 选中行下标是相对**过滤结果**算的，筛选变了必须清掉（否则会指向另一只精灵）
        selectedIndex = -1
        rebuildFilterList()
        rebuildFiltered()
        applyFilterVisibility()
    }

    /** 「筛选」开关：**只切面板可见性，不重建界面**（重建会清空精灵图标的跨帧动画状态） */
    private fun toggleFilters() {
        filterExpanded = !filterExpanded
        filterAnim.start(filterExpanded)
        if (!filterExpanded) {
            // 收起时顺手把展开列表收掉：浮层按钮会被隐藏弄成"状态与控件不一致"
            filterListOpen = ""
            filterListScroll = 0
        }
        updateFilterToggleVisual()
        rebuildFilterList()
        applyFilterVisibility()
    }

    /** 开关按钮的外观（图标 / 选中态 / 悬停提示）随 [filterExpanded] 更新 —— 不重建就得手动更一次 */
    private fun updateFilterToggleVisual() {
        val b = filterToggleButton ?: return
        b.iconLeft = Identifier.of("cobblemarket",
            if (filterExpanded) "textures/gui/filter.png" else "textures/gui/filter_close.png")
        b.selectedVisual = filterExpanded
        b.setTooltip(Tooltip.of(Text.translatable(
            if (filterExpanded) "cobblemarket.gui.filter_collapse" else "cobblemarket.gui.filter_expand")))
    }

    private fun htButtonText(): Text = Text.translatable(when (htFilter) {
        0 -> "cobblemarket.gui.filter_ht_any"
        1 -> "cobblemarket.gui.filter_ht_on"
        else -> "cobblemarket.gui.filter_ht_off"
    })

    private fun toggleHtFilter() {
        // 循环顺序：不限(0) → 不含特训(2) → 仅特训(1) → 不限
        htFilter = when (htFilter) {
            0 -> 2
            2 -> 1
            else -> 0
        }
        // 原来靠调用方 `rebuild()` 刷新按钮文字；现在筛选不重建界面，得手动更一次
        htBtn?.setMessage(htButtonText())
        htBtn?.textColor = if (htFilter != 0) GOLD_COLOR else UNSET_FILTER_COLOR
        rebuildFiltered()
        scrollOffset = 0
    }

    /** IV 比较匹配：0 = 等于，1 = 大于等于，2 = 小于等于 */
    private fun ivMatch(op: Int, eff: Int, value: Int): Boolean = when (op) {
        1 -> eff >= value
        2 -> eff <= value
        else -> eff == value
    }

    private fun syncIvOpButtons() {
        ivOpButtons.forEach { (i, btn) ->
            btn.message = Text.literal(if (ivOps[i] == 1) "≥" else if (ivOps[i] == 2) "≤" else "=")
            btn.textColor = if (ivOps[i] != 0) GOLD_COLOR else 0xFFFFFF
        }
    }

    /** IV 输入框 → ivExact 同步；返回是否有变化（变化时调用方重建过滤缓存） */
    private fun syncIvFields(): Boolean {
        val fields = arrayOf(hpF, atkF, defF, spaF, spdF, speF)
        var changed = false
        for (i in 0..5) {
            val raw = fields[i]?.text ?: ""
            val digits = raw.filter { it.isDigit() }.take(2)
            val v = digits.toIntOrNull()?.coerceIn(0, 31) ?: -1
            if (digits != raw) fields[i]?.text = if (v < 0) "" else v.toString()
            if (ivExact[i] != v) changed = true
            ivExact[i] = v
        }
        return changed
    }

    fun onPokemonList(payload: MyPokemonListPayload) {
        if (closed || payload.requestId != requestId) return
        if (payload.page == 0) {
            pokemonList = payload.pokemon
            selectedIndex = -1
            buildIconCache()
        } else {
            // 追加后续页：图标只增量构建。客户端串行请求，服务端按序处理，不会乱序。
            val startIdx = pokemonList.size
            pokemonList = pokemonList + payload.pokemon
            buildIconCache(startIdx)
        }
        rebuildFiltered()
        if (payload.hasMore) {
            // 响应驱动串行拉取，直到服务端说没有更多页
            sendToServer(RequestMyPokemonPayload(payload.page + 1, requestId))
        } else {
            loadedAll = true
        }
    }

    private fun sellSelected() {
        val filtered = filteredList()
        if (selectedIndex !in filtered.indices) return
        val price = priceField?.text?.toIntOrNull() ?: run {
            playFailSound()
            return
        }
        if (price <= 0) { playFailSound(); return }
        sendToServer(SellFromStoragePayload(filtered[selectedIndex].uuid, price))
    }

    /** 交付模式：把所选精灵回传求购单界面（静态暂存），返回继续交付流程 */
    private fun deliverSelected() {
        val filtered = filteredList()
        if (selectedIndex !in filtered.indices) return
        BuyOrderScreen.pendingDeliverPokemon = filtered[selectedIndex]
        client?.setScreen(BuyOrderScreen())
    }

    private fun typeColor(tk: String) = when (tk.substringAfterLast(".").lowercase()) {
        "normal" -> 0xAAAA99; "fire" -> 0xFF4422; "water" -> 0x3399FF
        "electric" -> 0xFFCC33; "grass" -> 0x77CC55; "ice" -> 0x66CCFF
        "fighting" -> 0xBB5544; "poison" -> 0xAA5599; "ground" -> 0xDDBB55
        "flying" -> 0x8899FF; "psychic" -> 0xFF5599; "bug" -> 0xAABB22
        "rock" -> 0xBBAA66; "ghost" -> 0x6666BB; "dragon" -> 0x7766EE
        "dark" -> 0x775544; "steel" -> 0xAAAABB; "fairy" -> 0xFFAAFF
        else -> 0xFFFFFF
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        IconRegistry.beginFrame(mouseX, mouseY)
        // 主面板**保持居中不动**（不传 leftOverride）：筛选面板占用它右侧本来就空着的区域，
        // 这样主面板里所有控件的坐标一个都不用动
        MarketPanelBackground.draw(context, width, height)
        // 右侧筛选面板（面板展开时）：高度由内容定（290），不跟主面板铺到屏幕底边
        // ⚠ 展开中、或收起动画还没播完都要画（后者要从目标位置缩回按钮），照精灵市场
        if (filterExpanded || filterAnim.isActive()) {
            val fl = MarketPanelBackground.filterLeft(width)
            val fw = MarketPanelBackground.filterWidth(width)
            val ph = filterPanelHeight()
            val panelCx = fl + fw / 2f
            val panelCy = 2f + ph / 2f
            // 动画锚点 = 「筛选」按钮中心；按钮还没建时退化成"原地不动"（照精灵市场）
            val btnCx = filterToggleButton?.let { it.x + it.width / 2f } ?: panelCx
            val btnCy = filterToggleButton?.let { it.y + it.height / 2f } ?: panelCy
            val animating = filterAnim.applyTransform(context, btnCx, btnCy, panelCx, panelCy)
            // ⚠ 收起动画的最后一帧（applyTransform 返回 false）不能画；展开状态必须照画
            if (filterExpanded || animating) {
                MarketPanelBackground.draw(
                    context, width, height,
                    panelWidth = fw,
                    leftOverride = fl,
                    heightOverride = ph,
                )
                if (animating) filterAnim.restore(context)
            }
        }
        // 展开列表的**不透明底块**：列表行的按钮背景中间是半透明的，不垫底的话它下面那几个
        // 筛选按钮会透上来（照精灵市场：面板里靠"藏被盖住的按钮 + 垫底"两件事一起解决）
        if (filterListOpen.isNotEmpty() && filterListBottomY > filterListTopY) {
            val fl = MarketPanelBackground.filterLeft(width)
            context.fill(
                fl + 2, filterListTopY - 1,
                fl + MarketPanelBackground.filterWidth(width) - 2, filterListBottomY,
                0xF0101010.toInt()
            )
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // ⚠ **每帧**重算面板控件的显隐（照精灵市场与 `AdminPokemonScreen`）：面板开合走的是**动画**，
        //   而动画播完那一刻**没有任何事件回调** —— 只在 toggle / 滚轮里算的话，
        //   展开动画结束后控件会永远停在「动画期间藏起来」的状态（2026-09-25 用户报）
        applyFilterVisibility()
        // 固拉多第七步（精灵市场背景下层水平右飞）画在 super.render 之前：
        // super.render 内部先调 renderBackground 画市场面板背景，精灵先画会被面板自然盖住
        renderGroudonFly(context, true)
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable(if (deliverMode) "cobblemarket.sell.deliver_title" else "cobblemarket.sell.title").formatted(Formatting.GOLD),
            width / 2, 20, 0xFFFFFF)
        val lx = width / 2 - 148
        val panelW = 296
        val iconSize = 20
        val startY = listStartY
        // 顶部分割线：列表起始上方 4px（与其他列表界面一致；此前这屏漏画了）
        drawPanelDivider(context, lx + 148, startY - 4)
        // IV 输入变化时重建过滤缓存（原 filteredList 每次调用都先 syncIvFields，缓存后在此统一检测）
        if (syncIvFields()) rebuildFiltered()
        val filtered = filteredList()
        val maxVisible = maxOf(0, (height - startY - 48) / rowHeight)
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, filtered.size - maxVisible))

        // 底部计数/加载进度画在屏幕底部（height-49），在展开列表下方，照常显示
        if (loaded && !loadedAll) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("cobblemarket.sell.loading_more", pokemonList.size).formatted(Formatting.GRAY), width / 2, height - 49, 0x888888)
        } else if (filtered.size > maxVisible) {
            context.drawCenteredTextWithShadow(textRenderer, "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible, filtered.size)} / ${filtered.size}", width / 2, height - 49, 0x888888)
        }

        // 属性浮层已搬进右侧面板、盖不到列表 ⇒ 悬停判定与逐行渲染的遮挡判断全部取消
        // （原先为它们维护的四类补丁：不画空提示 / 被盖住的行不画 / 不算悬停 / 点击排除）
        var hovered = -1
        if (mouseX in lx..(lx + panelW) && mouseY >= startY) {
            val row = ((mouseY - startY) / rowHeight) + scrollOffset
            if (row in filtered.indices) hovered = row
        }
        // List
        for (i in scrollOffset until minOf(scrollOffset + maxVisible, filtered.size)) {
            val indexed = indexedFilteredCache[i]
            val e = indexed.value
            val origIdx = indexed.index
            val y = startY + (i - scrollOffset) * rowHeight

            val rowState = when { i == selectedIndex -> 2; i == hovered -> 1; else -> 0 }
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, lx, y, panelW, rowHeight, rowState, ROW_BACKGROUND_TEX_H)

            // 3D icon slot background
            val slotX = lx + 2
            val slotY = y + 2
            context.matrices.push()
            context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
            context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
            context.drawTexture(POKEMON_SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
            context.matrices.pop()
            val iconX = lx + 2
            val iconY = y + 2
            // ⚠ **选中行也要 preview**：本界面的选中行**大图常驻**（见下方 `hoveredEntry ?: selectedEntry`），
            //   小图不跟着走的话，同一个 state 会被两个调用点用不同意图掰、姿势过渡反复触发
            //   （2026-09-22 实测，详见 `PokemonIconRenderer.draw` 里 `isPreviewing` 那段注释）。
            //   这里显式写出来是为了**当帧立即生效**；通用兜底（`IconRegistry.markPreviewing`）
            //   要下一帧才起作用 —— 两者都留着，一处管"意图"、一处管"防忘"。
            renderPokemonIcon(
                context, origIdx, iconX, iconY, iconSize, delta = delta,
                preview = i == hovered || i == selectedIndex
            )

            // Species（[队]/[PC] 固定色，精灵名属性色 + 金色闪光星标拆段绘制；PC 用电脑物品图标，[] 保持原色）
            val tc = typeColor(if (e.primaryType.isNotEmpty()) e.primaryType else "cobblemon.type.normal")
            val srcColor = if (e.source == "party") 0x55FF55 else 0x55AAFF
            var sx = lx + 26
            if (e.source == "party") {
                val src = Text.translatable("cobblemarket.sell.party").string
                context.drawText(textRenderer, src, sx, y + 7, srcColor, false)
                sx += textRenderer.getWidth(src) + 4
            } else {
                context.drawText(textRenderer, "[", sx, y + 7, srcColor, false)
                sx += textRenderer.getWidth("[")
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = pcItemStack, x = sx.toDouble(), y = y + 5.0, scale = 0.75, matrixStack = context.matrices)
                sx += 12
                context.drawText(textRenderer, "]", sx, y + 7, srcColor, false)
                sx += textRenderer.getWidth("]") + 4
            }

            // Ball icon（球种统一在精灵名称左侧；ball 非空就占位 12px，无论解析成败，与原来一致）
            if (e.ball.isNotEmpty()) {
                iconData[origIdx]?.ballStack?.let { ballStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = ballStack, x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                }
                sx += 12
            }

            context.drawTextWithShadow(textRenderer, speciesDisplay(e), sx, y + 7, tc)
            sx += textRenderer.getWidth(speciesDisplay(e))
            if (e.shiny) {
                context.drawText(textRenderer, "★", sx + 2, y + 7, GOLD_COLOR, false)
                sx += 2 + textRenderer.getWidth("★")
            }
            sx += EntryBadgeRenderer.NAME_TAIL_GAP

            // Gender icon（紧跟名字）
            if (e.gender == "MALE" || e.gender == "FEMALE") {
                val gi = if (e.gender == "MALE") GENDER_ICON_MALE else GENDER_ICON_FEMALE
                com.cobblemon.mod.common.api.gui.blitk(matrixStack = context.matrices, texture = gi, x = sx, y = y + 7, width = 6, height = 8)
                sx += 8
            }

            // Held item icon（紧跟性别；空气/空 id 已过滤为 null）
            iconData[origIdx]?.heldStack?.let { heldStack ->
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = heldStack, x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                sx += 12
            }

            // 体型徽章（排在携带物图标之后，留 3px 空隙）
            if (e.sizeCategory.isNotEmpty()) {
                sx += 3
                sx += EntryBadgeRenderer.drawSizeBadgeIcon(context, e.sizeCategory, sx, y + 7)
            }

            // Level（位置与拍卖场选择精灵界面一致：名字再长、图标再全也不会盖住）
            val levelText = Text.translatable("cobblemarket.gui.lv").string + e.level
            context.drawText(textRenderer, levelText, lx + 200, y + 7, 0x000000, false)
        }

        // Tooltip on hover
        if (hovered in filtered.indices) {
            renderTooltip(context, filtered[hovered], mouseX, mouseY, if (hovered == selectedIndex) 2 else 1)
        }

        // 列表区提示（筛选浮层已搬进右侧面板，不再盖到列表区，不必再判它开没开）
        if (filtered.isEmpty() && loaded && loadedAll) {
            // 只有全部分页拉完才能断言"没有"，否则匹配项可能还在未加载的页里
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("cobblemarket.sell.no_pokemon").formatted(Formatting.GRAY), width / 2, startY + 50, 0xFFFFFF)
        }
        if (!loaded) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("cobblemarket.sell.loading").formatted(Formatting.GRAY), width / 2, startY + 50, 0xFFFFFF)
        }

        // 固拉多飞行：悬浮在界面之上（与精灵市场同公式，切换界面位置延续）。
        // flush 夹心 + 清深度：行内 3D 精灵图标/球种图标写深度或抬高 z，会盖住普通 GUI 纹理
        // 悬停预览大图：画在固拉多之前（固拉多是最上层的特效）。
        // **优先鼠标所在行；鼠标不悬停任何行时，若有选中行则显示选中那只**（常驻，用户 2026-09-11 拍板）。
        // 注意这不是"选中优先于悬停"—— 悬停别的行照样切过去，选中只是鼠标离开后的兜底。
        // 弹窗打开时行内图标不渲染 → 两边都找不到 → 自动不显示，不用额外判断弹窗
        val previewRowL = width / 2 - 148
        val hoveredEntry = IconRegistry.entryInRow(mouseX.toDouble(), mouseY.toDouble(), previewRowL, previewRowL + 296)
        val selectedEntry = if (hoveredEntry == null && selectedIndex >= 0) {
            // 选中行的**图标**屏幕 y：与行渲染严格同源（行顶 = startY + n*rowHeight，图标再 +2，
            // 见下方 iconY = y + 2）。⚠ 这里原先写死 96，而 startY 是 100，查询点落进了上一行
            // 图标的 ±2 容差区间 → 鼠标移出行外时大图显示的是选中行的【上一行】（2026-09-15 修）。
            // 滚出可视区时该位置没有图标，自然找不到。
            val selectedY = startY + (selectedIndex - scrollOffset) * rowHeight + 2
            IconRegistry.entryInRow(previewRowL + 2.0, selectedY.toDouble(), previewRowL, previewRowL + 296)
        } else null
        (hoveredEntry ?: selectedEntry)?.let {
            PokemonHoverPreview.render(context, it.renderable, it.state, previewRowL, height)
        }

        context.draw()
        com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.MinecraftClient.IS_SYSTEM_MAC)
        renderGroudonFly(context, false)
        context.draw()
    }

    /** 固拉多飞行（照精灵市场，重做第四步）：水平飞入停靠点 2s → 逆时针弧 90° 出屏 1.8s（θ 90°→0°，头朝上竖直出屏）→
     *  屏外切线掉头朝下 → 顺时针绕半圆 180° 到屏幕右半上方 1.2s（屏外提速）→
     *  屏外快速竖直下移 0.4s → 屏幕内加速下坠出屏底（速度与原 3s 版屏幕内段一致）→
     *  第四步（不连接上一步）：镜像图（头朝左、上下正常）从屏幕右上角外水平飞入停在右上角 1.2s，
     *  停留 5s 悬停（等下一步）。
     *  路径点 (px,py) 是图片左侧中间的轨迹（旋转轴心 = 图片左侧中间，贴图坐标 (0,150)），
     *  精灵主体拖在轴心后方，转弯时绕尾部牵引转向 */
    private fun renderGroudonFly(context: DrawContext, under: Boolean) {
        if (!com.shusheng.cobblemarket.client.ClientConfig.groudonFly) return
        val g = com.shusheng.cobblemarket.client.GroudonFly
        // 计时起点在全局 GroudonFly（照 PokemonCelebrationAnimation 的全局状态）：
        // 切换界面动画不重置，时间线延续
        if (g.startAt == 0L) g.startAt = System.currentTimeMillis()
        val t = (System.currentTimeMillis() - g.startAt).toFloat()
        // 轴心停靠点：左侧中间停在 (35,110) 时主体中心落在 (140,110)，与第一步停靠位置一致；
        // 半径 150 的圆弧：圆心在轴心正上方 150 处 (35,-40)（屏幕顶边外）
        val pivotX = 35f
        val pivotY = 110f
        val radius = 150f
        val centerX = pivotX
        val centerY = pivotY - radius
        val theta0 = atan2((pivotY - centerY).toDouble(), (pivotX - centerX).toDouble()).toFloat()
        // 逆时针段 θ: 90°→0°（绕 90°，头朝上出屏）
        val endTheta = 0f
        val arcEndMs = g.FLY_IN_MS + g.ARC_MS
        val arc2EndMs = arcEndMs + g.ARC2_MS
        // 下坠段：屏外 170px 用 DROP_OUT_MS 快速通过；屏幕内部分复刻原 3s k² 时间表，
        // 入屏时刻 tIn = 3000·√(170/(height+370))，之后按原曲线到 3000ms 出屏底，屏幕内速度感不变
        val tIn = 3000f * kotlin.math.sqrt(170f / (height + 370f))
        val dropEndMs = arc2EndMs + g.DROP_OUT_MS + (3000f - tIn)
        val flyIn2EndMs = dropEndMs + g.FLY_IN2_MS
        val hoverEndMs = flyIn2EndMs + g.HOVER_MS
        val flyLeftEndMs = hoverEndMs + g.FLY_LEFT_MS
        val flyIn3EndMs = flyLeftEndMs + g.FLY_IN2_MS
        val flyRightEndMs = flyIn3EndMs + g.FLY_RIGHT_MS
        // 顺时针半圆 180°：起点 = 出屏点（圆 (35,-40) 半径 150 的右端点 (185,-40)），
        // 终点 = 屏幕左半上方（1/4 宽处 (-170 高度)，倒放下坠段即「从左下飞到左上」）；圆心取两点中点，弧线拱向上方
        val arc2FromX = centerX + radius
        val arc2FromY = centerY
        val arc2ToX = width * 0.25f
        val arc2ToY = -170f
        val arc2CX = (arc2FromX + arc2ToX) / 2f
        val arc2CY = (arc2FromY + arc2ToY) / 2f
        val arc2R = kotlin.math.hypot(arc2FromX - arc2CX, arc2FromY - arc2CY)
        val arc2Theta0 = atan2((arc2FromY - arc2CY).toDouble(), (arc2FromX - arc2CX).toDouble()).toFloat()
        val arc2Theta1 = atan2((arc2ToY - arc2CY).toDouble(), (arc2ToX - arc2CX).toDouble()).toFloat() + (2f * PI.toFloat())
        // 整轮动画（上半 + 下半 = 2×flyRightEndMs）在精灵市场/上架选择界面循环播放：t 取模
        // 下半部分 = 上半部分绕屏幕中心的中心对称（180° 旋转：x、y 都翻转，顺序相同）：
        // tUp 从 0 顺序重演；视觉旋转方向不变（逆时针弧仍是逆时针，只是向下出屏底），运动方向反向；
        // 镜像图状态取反（头朝运动方向、正立不倒置）；层次判定按 tUp
        val tLoop = t % (2f * flyRightEndMs)
        val tUp: Float
        val flip: Boolean
        if (tLoop < flyRightEndMs) {
            tUp = tLoop
            flip = false
        } else {
            tUp = tLoop - flyRightEndMs
            flip = true
        }
        var px: Float
        var py: Float
        var targetRot: Float
        when {
            tUp < g.FLY_IN_MS -> {
                // 飞入：轴心从屏幕左边缘外水平飞向停靠点（朝右时图片整体在轴心右侧，轴心 x<-210 即全出屏），朝右。
                // 缓动末速度≈131px/s 与逆时针弧初线速度一致，速度连续无停顿（纯 ease-out 会停稳再启动）
                val k = tUp / g.FLY_IN_MS
                val s = 0.465f * (2f * k - k * k) + 0.535f * k
                px = -245f + (pivotX + 245f) * s
                py = pivotY
                targetRot = 0f
            }
            tUp < arcEndMs -> {
                // 逆时针弧 90° 出屏（视觉逆时针 = θ 减小，切线角 θ-90°，末段头朝上）
                val k = (tUp - g.FLY_IN_MS) / g.ARC_MS
                val theta = theta0 + (endTheta - theta0) * k
                px = centerX + radius * cos(theta)
                py = centerY + radius * sin(theta)
                targetRot = Math.toDegrees(theta.toDouble()).toFloat() - 90f
            }
            tUp < arc2EndMs -> {
                // 顺时针绕半圆 180°（θ 增大，切线角 θ+90°）：屏外掉头，弧线拱向上方到屏幕右半
                val k = (tUp - arcEndMs) / g.ARC2_MS
                val theta = arc2Theta0 + (arc2Theta1 - arc2Theta0) * k
                px = arc2CX + arc2R * cos(theta)
                py = arc2CY + arc2R * sin(theta)
                targetRot = Math.toDegrees(theta.toDouble()).toFloat() + 90f
            }
            tUp < dropEndMs -> {
                // 头朝下竖直下坠：屏外快速下移（不可见段提速），入屏后按原 3s 版屏幕内速度曲线加速
                val dt = tUp - arc2EndMs
                px = arc2ToX
                if (dt < g.DROP_OUT_MS) {
                    py = arc2ToY * (1f - dt / g.DROP_OUT_MS)
                } else {
                    val kk = (tIn + (dt - g.DROP_OUT_MS)) / 3000f
                    py = arc2ToY + (height + 200f - arc2ToY) * (kk * kk)
                }
                targetRot = 90f
            }
            tUp < flyIn2EndMs -> {
                // 第四步：镜像图（头朝左）从屏幕右上角外水平 ease-out 飞入，
                // 停在右上角（轴心 (width,90)：主体 x [width-210,width]、y [0,180] 刚好贴住右上角）
                val k = (tUp - dropEndMs) / g.FLY_IN2_MS
                val s = 1f - (1f - k) * (1f - k)
                px = (width + 245f) - 245f * s
                py = 90f
                targetRot = 0f
            }
            tUp < hoverEndMs -> {
                // 右上角悬停 5s，头正朝左
                px = width.toFloat()
                py = 90f
                targetRot = 0f
            }
            tUp < flyLeftEndMs -> {
                // 第五步：水平向左飞出屏幕，smoothstep 缓动（悬停平滑启动、出屏减速停稳）
                val k = (tUp - hoverEndMs) / g.FLY_LEFT_MS
                val s = 3f * k * k - 2f * k * k * k
                px = width.toFloat() - (width + 245f) * s
                py = 90f
                targetRot = 0f
            }
            tUp < flyIn3EndMs -> {
                // 第六步：恢复原图（再次镜像，头朝右）从左下角外水平飞入，穿过左下角不停留；
                // 缓动末速 ≈269px/s 与第七步匀速一致（速度连续，无停→启动停顿）
                val k = (tUp - flyLeftEndMs) / g.FLY_IN2_MS
                val s = 0.68f * k + 0.32f * k * k
                px = -245f + 245f * s
                py = height - 90f
                targetRot = 0f
            }
            tUp < flyRightEndMs -> {
                // 第七步：在市场面板背景下层水平向右飞（绘制在面板之前，被背景盖住），
                // 匀速 ≈269px/s 飞出屏幕右侧
                val k = (tUp - flyIn3EndMs) / g.FLY_RIGHT_MS
                px = 0f + (width + 245f) * k
                py = height - 90f
                targetRot = 0f
            }
            else -> {
                // 停在屏幕右侧外（等下一步）
                px = width + 245f
                py = height - 90f
                targetRot = 0f
            }
        }
        // 下半部分中心对称修正：x、y 都绕屏幕中心翻转（180° 旋转），朝向角不变（运动方向自然反向）
        if (flip) {
            px = width - px
            py = height - py
        }
        // 层次过滤：第六步（左下角飞入）与第七步（背景下层水平右飞）从飞出开始全程画在背景下层，
        // 避免第六步上层→第七步下层的层级突变；其余段画在上层。每帧只绘制一次，smoothRot 也只更新一次
        val isUnder = tUp >= flyLeftEndMs && tUp < flyRightEndMs
        if (isUnder != under) return
        g.smoothRot += (((targetRot - g.smoothRot + 540f) % 360f) - 180f) * 0.15f
        val rotDeg = g.smoothRot
        val flyFrame = ((System.currentTimeMillis() / g.FRAME_MS) % 40).toInt()
        val flyTex = Identifier.of("cobblemarket", "textures/gui/groudon_sheet.png")
        val flyCol = flyFrame % 10
        val flyRow = flyFrame / 10
        val flyW = 210f
        // 第四、五步用镜像图（scale x 取负，静态镜像非旋转中翻面）：头朝左、上下正常；
        // 第六步起恢复原图（再次镜像）。负 scale 翻转三角形朝向，行内 3D 精灵图标渲染
        // 可能残留背面剔除开启会整图不画——绘制时临时关 cull，画完按原状态恢复
        // 正向镜像区间为第四、五步；下半部分（中心对称）运动方向相反，镜像状态取反（头朝运动方向、正立不倒置）
        val mirrored = (tUp >= dropEndMs && tUp < flyLeftEndMs) != flip
        val wasCull = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_CULL_FACE)
        if (mirrored) org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_CULL_FACE)
        context.matrices.push()
        context.matrices.translate(px.toDouble(), py.toDouble(), 0.0)
        context.matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(rotDeg))
        context.matrices.scale(if (mirrored) -flyW / 350f else flyW / 350f, 180f / 300f, 1f)
        context.drawTexture(flyTex, 0, -150, (flyCol * 350).toFloat(), (flyRow * 300).toFloat(), 350, 300, 3500, 1200)
        context.matrices.pop()
        if (mirrored && wasCull) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_CULL_FACE)
    }

    private fun renderPokemonIcon(
        context: DrawContext, origIdx: Int, x: Int, y: Int, size: Int, delta: Float = 0f,
        /** 本行是否被悬停（跟左侧大图一起走 walk）。**必须由调用方按行索引算**：
         *  早先用 `IconRegistry.isMouseInRow` 只看纵向，鼠标跑到面板外侧、只要 y 平齐也会触发动画（2026-09-15） */
        preview: Boolean = false
    ) {
        val data = iconData[origIdx] ?: return run {
            val tc = 0x88888888.toInt()
            context.fill(x, y, x + size, y + size, tc)
        }
        // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
        val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
            com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
        try {
            PokemonIconRenderer.draw(
                context = context,
                renderable = data.renderable,
                state = data.state,
                x = x, y = y, size = size,
                delta = delta,
                animate = useFloat,
                preview = preview
            )
        } catch (_: Exception) {
        }
        // 不可交易：在这只图标上盖一把锁（Cobblemon 原版那张图，见 [EntryBadgeRenderer.drawUntradeableLock]）。
        // ⚠ 取数走 [origIdx] —— 它恒是 [pokemonList] 的**原始下标**（筛选后也保持，见 indexedFilteredCache）
        if (pokemonList.getOrNull(origIdx)?.tradeable == false) {
            EntryBadgeRenderer.drawUntradeableLock(context, x, y, size)
        }
    }

    // tooltip 内容缓存：内容只取决于条目，悬停同一行时每帧重建全部文本行是悬停掉帧主因
    private var tooltipCacheKey: java.util.UUID? = null
    private var tooltipCacheLines: List<Pair<Text?, Int>> = emptyList()
    private var tooltipCacheHeldLine = -1
    private var tooltipCacheMovesLine = -1
    private var tooltipCacheMarksLine = -1
    private var tooltipCacheMarksRows = 0
    private var tooltipCacheMaxWidth = 0

    private fun renderTooltip(context: DrawContext, p: PokemonPreview, mx: Int, my: Int, bgState: Int) {
        // 文本行缓存：悬停同一行时内容不变，只在悬停目标变化时重建（见 tooltipCacheKey 注释）
        if (tooltipCacheKey != p.uuid) {
            tooltipCacheKey = p.uuid
            val hp = StatNames.HP
            val atk = StatNames.ATK
            val def = StatNames.DEF
            val spa = StatNames.SP_ATK
            val spd = StatNames.SP_DEF
            val spe = StatNames.SPEED
            val typeText = Text.translatable(p.primaryType).string +
                if (p.secondaryType.isNotEmpty()) " + ${Text.translatable(p.secondaryType).string}" else ""

            val hasHeldItem = p.heldItemId.isNotEmpty() &&
                Identifier.tryParse(p.heldItemId)?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true

            // 行列表：null = 分割线；证章行在 marksLine 索引处画图标
            val lines = mutableListOf<Pair<Text?, Int>>()
            lines.add(EntryBadgeRenderer.nameWithLevelLine(
                EntryBadgeRenderer.nameWithShinyStar(speciesDisplay(p), p.shiny),
                p.primaryType, p.level) to 0xFFFFFF)
            // 昵称行：**名字下面一行**、有才画（用户 2026-10-06 定）——
            // ⚠ 必须插在**名字行之后**（索引 0 那行会额外画公母/体型图标）
            EntryBadgeRenderer.nicknameLine(p.nickname)?.let { lines.add(it to 0xFFFFFF) }
            lines.add(com.shusheng.cobblemarket.util.TypeTextColors.typeLineWithTera(
                p.primaryType, p.secondaryType, p.teraType) to 0xFFFFFF)
            // 极巨行：属性行下面、有才显示（与市场侧走同一个组装入口）
            com.shusheng.cobblemarket.util.PokemonExtraLines.dynamax(p.gmaxFactor, p.dmaxLevel)
                ?.let { lines.add(it to 0xFFFFFF) }
            // 性格 + 特性同一行：走共用入口（两段间的间距只在 EntryBadgeRenderer 里定义）
            lines.add(EntryBadgeRenderer.natureAbilityLine(p.natureBase, p.nature, p.ability) to 0xFFFFFF)
            // 球种行：性格特性下面（与 EntryBadgeRenderer 的行顺序一致）；球名走**稀有度色**
            p.ball.takeIf { it.isNotEmpty() }?.let {
                lines.add(com.shusheng.cobblemarket.util.ItemNameText.ballLine(it, p.ballItem) to 0xFFFFFF)
            }
            var heldItemLine = -1
            if (hasHeldItem) {
                heldItemLine = lines.size
                lines.add(Text.translatable("cobblemarket.gui.tooltip_held") to 0xFFFFFF)
            }
            lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs") to 0xFFFFFF)
            lines.add(Text.literal("  $hp: ${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsHp, p.htHp)}").append(Text.literal("  EV: ${p.evsHp}").formatted(Formatting.RED)) to 0x66FF66); lines.add(Text.literal("  $atk: ${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsAtk, p.htAtk)}").append(Text.literal("  EV: ${p.evsAtk}").formatted(Formatting.RED)) to 0xFF6666)
            lines.add(Text.literal("  $def: ${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsDef, p.htDef)}").append(Text.literal("  EV: ${p.evsDef}").formatted(Formatting.RED)) to 0xFFCC66); lines.add(Text.literal("  $spa: ${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpAtk, p.htSpAtk)}").append(Text.literal("  EV: ${p.evsSpAtk}").formatted(Formatting.RED)) to 0x6699FF)
            lines.add(Text.literal("  $spd: ${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpDef, p.htSpDef)}").append(Text.literal("  EV: ${p.evsSpDef}").formatted(Formatting.RED)) to 0x66FF99); lines.add(Text.literal("  $spe: ${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpd, p.htSpd)}").append(Text.literal("  EV: ${p.evsSpd}").formatted(Formatting.RED)) to 0xFF99FF); lines.add(Text.translatable("cobblemarket.gui.friendship", p.friendship) to 0xFF99CC)
            // 初始训练家行：亲密度下面（与市场侧同位置；没有则整行不占位）
            com.shusheng.cobblemarket.util.PokemonExtraLines.originalTrainer(p.originalTrainer)
                ?.let { lines.add(it to 0xFFFFFF) }
            // 技能块（**总是画** —— Cobblemon 里不存在没技能的精灵）：分割线 + 两行技能 + 分割线，整块在 drawMovesBlockLeft 里画
            var movesLine = -1
            if (MoveText.hasAny(p.moves)) {
                movesLine = lines.size
                lines.add(null to 0)
            }
            // 证章区块（拥有的全部证章，纯外观）：技能块下方，图标行 + 下分割线（与技能块共用上分割线）
            var marksLine = -1
            var marksRows = 0
            val markTextures = p.marks.mapNotNull { Identifier.tryParse(it) }
            if (markTextures.isNotEmpty()) {
                marksLine = lines.size
                lines.add(null to 0)
                lines.add(null to 0)
            }

            var mw = 0; lines.forEach { if (it.first != null) mw = maxOf(mw, textRenderer.getWidth(it.first)) }
            // 名字行尾部图标（公母 + 体型徽章）不计入文本宽度，单独补上
            lines[0].first?.let { mw = maxOf(mw, EntryBadgeRenderer.nameLineWidth(it, p.gender, p.sizeCategory)) }
            if (heldItemLine >= 0) {
                mw = maxOf(mw, textRenderer.getWidth(lines[heldItemLine].first) + 14)
            }
            mw = maxOf(mw, EntryBadgeRenderer.movesBlockWidth(p.moves))
            // ⚠ 证章**不再参与** mw（方向反过来：**窗口宽度决定证章排几个**，见 [EntryBadgeRenderer.marksPerRow]）
            //   ⇒ 必须在 mw 定稿之后才算「占几行」
            marksRows = when {
                markTextures.isEmpty() -> 0
                markTextures.size > EntryBadgeRenderer.marksPerRow(mw) -> 2
                else -> 1
            }
            tooltipCacheLines = lines
            tooltipCacheHeldLine = heldItemLine
            tooltipCacheMovesLine = movesLine
            tooltipCacheMarksLine = marksLine
            tooltipCacheMarksRows = marksRows
            tooltipCacheMaxWidth = mw
        }
        val lines = tooltipCacheLines
        val heldItemLine = tooltipCacheHeldLine
        val movesLine = tooltipCacheMovesLine
        val marksLine = tooltipCacheMarksLine
        val marksRows = tooltipCacheMarksRows
        val mw = tooltipCacheMaxWidth
        val pad = 4
        val tx = minOf(mx + 12, width - mw - 12)
        val th = lines.size * 10 + pad + (marksRows - 1).coerceAtLeast(0) * 12 +
            EntryBadgeRenderer.movesBlockExtra(p.moves)
        val ty = if (my - th - 4 <= 0) minOf(my + 12, height - th) else my - th - 4

        context.matrices.push(); context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - pad, ty - pad, mw + 2 * pad, th + pad, bgState, ROW_BACKGROUND_TEX_H)
        var rowY = ty
        lines.forEachIndexed { i, (line, color) ->
            if (line == null && i == movesLine) {
                // 技能块：分割线 + 两行技能 + 分割线（左对齐版，分割线撑满提示框全宽）
                rowY += EntryBadgeRenderer.drawMovesBlockLeft(context, p.moves, tx, mw, rowY)
            } else if (line == null && i == marksLine) {
                // 证章图标行：**一行放几个跟随可用宽**（见 [EntryBadgeRenderer.marksPerRow]）；放不下的第二行「+N」
                val textures = p.marks.mapNotNull { Identifier.tryParse(it) }
                val perRow = EntryBadgeRenderer.marksPerRow(mw)
                val shown = minOf(perRow, textures.size)
                // 整行在可用宽里**居中**（分割线撑满可用宽，图标不占满时会显偏左）
                val rowWidth = shown * 12 - 4
                val startX = tx + (mw - rowWidth) / 2
                textures.take(perRow).forEachIndexed { idx, texture ->
                    com.cobblemon.mod.common.api.gui.blitk(
                        matrixStack = context.matrices, texture = texture,
                        x = startX + idx * 12, y = rowY, width = 8, height = 8
                    )
                }
                if (textures.size > perRow) {
                    context.drawCenteredTextWithShadow(textRenderer, "+${textures.size - perRow}", startX + rowWidth / 2, rowY + 12, 0xAAAAAA)
                    rowY += 24
                } else {
                    rowY += 12
                }
            } else if (line == null) {
                // 分割线：1px 灰线，撑满提示框全宽（与信息区分隔线一致，不随证章数量变化）
                val rowW = mw
                context.fill(tx, rowY + 4, tx + rowW, rowY + 5, 0xFF888888.toInt())
                rowY += 10
            } else if (i == heldItemLine) {
                context.drawTextWithShadow(textRenderer, line, tx, rowY, color)
                val heldIconX = tx + textRenderer.getWidth(line) + 2
                heldIconStack(p.heldItemId, p.heldItemNbt)?.let { heldStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = heldStack,
                        x = heldIconX.toDouble(),
                        y = rowY + 0.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                }
                // 玩家把携带物设成「隐藏」时注明一句（模型上不显示它，而信息行照常列出）—— 用户 2026-09-26
                if (!p.heldItemVisible) {
                    context.drawTextWithShadow(
                        textRenderer, Text.translatable("cobblemarket.gui.held_hidden"), heldIconX + 12, rowY, 0xAAAAAA
                    )
                }
                rowY += 10
            } else if (i == 0) {
                // 第一行（名字★Lv）带公母图标
                EntryBadgeRenderer.drawNameLineLeft(context, line, p.gender, tx, rowY, color, p.sizeCategory)
                rowY += 10
            } else {
                context.drawTextWithShadow(textRenderer, line, tx, rowY, color)
                rowY += 10
            }
        }
        context.matrices.pop()
    }

    private fun isInputFieldFocused() = focused?.let { f ->
        f === searchField || f === hpF || f === atkF || f === defF || f === spaF || f === spdF || f === speF || f === priceField
    } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true ||
        hpF?.isMouseOver(mouseX, mouseY) == true ||
        atkF?.isMouseOver(mouseX, mouseY) == true ||
        defF?.isMouseOver(mouseX, mouseY) == true ||
        spaF?.isMouseOver(mouseX, mouseY) == true ||
        spdF?.isMouseOver(mouseX, mouseY) == true ||
        speF?.isMouseOver(mouseX, mouseY) == true ||
        priceField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mx: Double, my: Double, btn: Int): Boolean {
        // 图标点击叫声：该界面行图标可选中、行内无按钮，所以触区是整行
        if (btn == 0 && !IconCryClick.playIconAt(mx, my)) {
            val rowL = width / 2 - 148
            IconCryClick.playRowAt(mx, my, rowL, rowL + 296)
        }
        val wasInInput = isInputFieldFocused()
        val r = super.mouseClicked(mx, my, btn)
        if (wasInInput && !isMouseOverAnyInput(mx, my)) {
            focused = null
        }
        // 与 render 相同的 IV 检测：事件读缓存前确保 ivExact 与输入框一致
        if (syncIvFields()) rebuildFiltered()
        val filtered = filteredList()
        val lx = width / 2 - 148
        val startY = listStartY
        val maxVisible = maxOf(0, (height - startY - 48) / rowHeight)
        // 展开浮层搬进右侧面板后不再盖住列表行，点击不必再让位（原先那层展开状态判断随之删掉）
        if (mx in lx.toDouble()..(lx + 296).toDouble() && my >= startY) {
            val row = ((my - startY) / rowHeight).toInt() + scrollOffset
            if (row in filtered.indices) {
                // 不可交易的精灵**选不了**（与原版交换界面一致）：响一声失败音 + 一句说明。
                // ⚠ 别静默 return —— 玩家点了没反应会以为是界面坏了
                val picked = filtered[row]
                if (!picked.tradeable) {
                    playFailSound()
                    client?.player?.sendMessage(
                        Text.translatable("cobblemarket.trade.untradeable").formatted(Formatting.RED), false
                    )
                    return true
                }
                selectedIndex = row
                return true
            }
        }
        return r
    }

    override fun mouseScrolled(mx: Double, my: Double, h: Double, v: Double): Boolean {
        // 展开列表滚动：滚的是浮层本身，不是下面的列表
        if (filterListOpen.isNotEmpty()) {
            // ⚠ 上限必须与 rebuildFilterList 里的 maxRows 同一算法（小屏时实际显示行数会少于 8），
            //   否则能滚到"浮层里根本没显示"的偏移量
            val listTop = (filterListSourceButton()?.y ?: 0) + 22
            val maxRows = minOf(MAX_FILTER_LIST_ROWS, maxOf(1, (height - 32 - listTop) / FILTER_LIST_ROW_H))
            val total = filterListOptions().size
            if (total > maxRows) {
                filterListScroll = (filterListScroll - v.toInt()).coerceIn(0, total - maxRows)
                rebuildFilterList()
            }
            return true
        }
        // 面板内滚动（内容比面板高时才有用）：鼠标落在面板上才滚，否则滚的是下面的列表
        if (filterExpanded && filterPanelHeight() < FILTER_PANEL_HEIGHT &&
            mx >= MarketPanelBackground.filterLeft(width) &&
            mx < MarketPanelBackground.filterLeft(width) + MarketPanelBackground.filterWidth(width)
        ) {
            filterPanelScroll = (filterPanelScroll - v.toInt() * 10)
                .coerceIn(0, FILTER_PANEL_HEIGHT - filterPanelHeight())
            applyFilterVisibility()
            return true
        }
        if (syncIvFields()) rebuildFiltered()
        val filtered = filteredList()
        // ⚠ 用 [listStartY]，别写死数字 —— 这里原先写死 96（渲染处是 100），差 4px
        //    在特定屏幕高度下算出的可视行数不同 ⇒ 滚动上限比渲染时少 1 ⇒ 最后一行滚不到
        val maxVisible = maxOf(0, (height - listStartY - 48) / rowHeight)
        scrollOffset = (scrollOffset - v.toInt()).coerceIn(0, maxOf(0, filtered.size - maxVisible))
        return true
    }

    fun onMarketResult(payload: MarketResultPayload) {
        if (payload.success) {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.GREEN), false)
            priceField?.text = ""
            selectedIndex = -1
            // 上架成功后从第一页重新拉取全量列表
            pokemonList = listOf()
            iconData.clear()
            loadedAll = false
            rebuildFiltered()
            sendToServer(RequestMyPokemonPayload(0, requestId))
        } else {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.RED), false)
        }
    }

    override fun shouldPause() = false

    companion object {
        private var nextRequestId = 0
        /** 展开列表最多显示几行（超出用滚轮翻，照精灵市场） */
        const val MAX_FILTER_LIST_ROWS = 8
        /** 展开列表每行高 */
        private const val FILTER_LIST_ROW_H = 14
        /** 筛选按钮「未选中」的文字色：灰色 = 该项不过滤（与精灵市场 / 拍卖场同一口径，2026-09-23） */
        /**
         * 筛选按钮「未选中」的文字色：**白色**（与 MarketScreen / AuctionScreen 同一口径）。
         * ⚠ 一度是 0xAAAAAA（灰色），用户不认可；2026-09-23 三个界面一起改回白色。
         */
        const val UNSET_FILTER_COLOR = 0xFFFFFF
        val GENDER_ICON_MALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
        val GENDER_ICON_FEMALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
        val POKEMON_SLOT_TEXTURE = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
    }
}
