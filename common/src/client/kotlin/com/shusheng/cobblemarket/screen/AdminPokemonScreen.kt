package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.util.MoveText
import com.shusheng.cobblemarket.util.StatNames
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.shusheng.cobblemarket.client.icon.PreviewStateHolder
import com.shusheng.cobblemarket.client.TooltipPlacement
import com.shusheng.cobblemarket.client.FilterPanelAnimation
import com.shusheng.cobblemarket.client.heldIconStack
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.client.PointsStateCache
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_SRC
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_TEXTURE
import com.shusheng.cobblemarket.client.CURRENCY_BUTTON_ICON_SIZE
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.client.heldIconStackForModel
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.PokemonHoverPreview
import com.shusheng.cobblemarket.client.icon.PokemonIconData
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.network.AdminCancelPokemonPayload
import com.shusheng.cobblemarket.network.AdminRequestPokemonPayload
import com.shusheng.cobblemarket.network.ListingEntry
import com.shusheng.cobblemarket.network.MarketDataPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

class AdminPokemonScreen : Screen(Text.translatable("cobblemarket.op.pokemon")) {

    private var listings = listOf<ListingEntry>()
    private var currentPage = 1
    private var totalPages = 1

    /** 列表在查哪个币种（货币切换按钮控制）—— **只决定查什么，不参与渲染**：
     *  显示单位一律读挂单自己的 [ListingEntry.isPoints]，否则任何一处漏改就会把 RP 商品标成 ₽ */
    private var currency = MarketCurrency.PRIMARY

    /** 货币切换按钮（RP 总开关关着时 = null，压根没建） */
    private var currencyButton: NineSliceButton? = null
    // 搜索防抖（照精灵市场）：250ms 静默后统一发请求，避免连续输入被服务端节流丢包
    private var searchDirty = false
    private var lastSearchEdit = 0L

    private var searchField: TextFieldWidget? = null
    private var shinyOnly = false
    private var showMineOnly = false
    private var filterExpanded = false
    /** 面板开关动画（展开时从「筛选」按钮放大长出来、收起缩回）。⚠ 必须与 [filterExpanded] 成对用 */
    private val filterAnim = FilterPanelAnimation()
    private var sortMode = "NEWEST"
    private val ivExact = IntArray(6) { -1 }
    // IV 比较方式：0 = 等于（默认），1 = 大于等于，2 = 小于等于
    private val ivOps = IntArray(6)
    private val ivOpButtons = mutableMapOf<Int, NineSliceButton>()
    // 特训筛选三态：0 = 不限，1 = 仅含训练，2 = 仅不含训练
    private var htFilter = 0
    private var htButton: NineSliceButton? = null

    // ── 2026-09-24 追加：筛选面板照精灵市场补齐（性别 / 属性 / 特性 / 性格） ──
    /** 性别：""/MALE/FEMALE */
    private var genderFilter = ""
    /** 属性（资源名如 `fire`）：主属性或副属性任一命中即算 */
    private var typeFilter = ""
    /** 特性（翻译 key，精确匹配）；选项跟着**搜索框里的物种**走，见 [updateAbilityOptions] */
    private var abilityFilter = ""
    private var abilityOptions = listOf<Pair<String, String>>()
    /** 性格（翻译 key，按**生效**性格匹配，薄荷后为薄荷性格） */
    private var natureFilter = ""
    /** 面板内的展开列表："" / "type" / "ability" / "nature"，互斥展开 */
    private var filterListOpen = ""
    private var filterListScroll = 0
    /** 展开列表的顶/底边（垫不透明底、隐藏被它盖住的面板按钮都读它们） */
    private var filterListTopY = 0
    private var filterListBottomY = 0
    private val filterOptionButtons = mutableListOf<NineSliceButton>()
    /** 面板内滚动偏移（内容比面板高时才有用，见 [filterPanelHeight]） */
    private var filterPanelScroll = 0
    /** 面板内控件的「基准 y」（滚动时按它重定位并还原，照精灵市场） */
    private val filterPanelControls =
        mutableListOf<Pair<net.minecraft.client.gui.widget.ClickableWidget, Int>>()
    private var genderButton: NineSliceButton? = null
    private var typeButton: NineSliceButton? = null
    private var abilityButton: NineSliceButton? = null
    private var natureButton: NineSliceButton? = null
    /** 每个 IV 输入框右侧的单框重置 ↺（只清这一项，不必去点整体重置把六项全清掉） */
    private val ivResetButtons = mutableListOf<NineSliceButton>()

    private lateinit var shinyButton: NineSliceButton
    private lateinit var sortButton: ButtonWidget
    private lateinit var resetButton: NineSliceButton
    private lateinit var mineButton: NineSliceButton
    /** NineSliceButton（不是 ButtonWidget）：开关要像精灵市场那样随展开状态换图标 */
    private lateinit var filterToggleButton: NineSliceButton
    private lateinit var prevButton: ButtonWidget
    private lateinit var nextButton: ButtonWidget
    private val cancelButtons = mutableListOf<ButtonWidget>()

    private var hpField: TextFieldWidget? = null
    private var atkField: TextFieldWidget? = null
    private var defField: TextFieldWidget? = null
    private var spaField: TextFieldWidget? = null
    private var spdField: TextFieldWidget? = null
    private var speField: TextFieldWidget? = null

    private var hoveredRow = -1
    private val panelWidth = 296
    private val iconSize = 20

    /**
     * 右侧筛选面板的**内容高度**（由内容定，不跟随主面板铺到屏幕底边，照精灵市场）。
     *
     * 布局：闪光+性别+排序 一行 → 属性 / 特性 / 性格 / 特训 各一行 → 我的+↺ 一行 → IV 六行。
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

    private var confirmEntry: ListingEntry? = null

    /** 确认弹窗信息区滚动偏移（2026-09-20）：弹窗按屏幕钳制后靠滚轮看超出部分（照求购单创建弹窗） */
    private var confirmScroll = 0
    private val confirmContentTop = 60
    private val confirmBottomH = 32

    // ── 行按钮几何（**建按钮与悬停窗避让共用一处定义**，别各写一份）──
    private val rowBtnInset = 42
    private val rowBtnW = 38
    private val rowBtnH = 16
    private val rowBtnDy = 4
    private val rowH = 24

    /** 第 [row] 行的 ✕（下架）按钮矩形 —— 与 `rebuildCancelButtons` 同源 */
    private fun cancelButtonRect(row: Int) = TooltipPlacement.Rect(
        width / 2 - panelWidth / 2 + panelWidth - rowBtnInset,
        getListStartY() + row * rowH + rowBtnDy, rowBtnW, rowBtnH
    )
    private var confirmRenderable: RenderablePokemon? = null
    private var confirmDisplayName = ""
    private val confirmState = PreviewStateHolder()

    /**
     * 下架确认弹窗自动叫一声的**待播时刻**（0 = 无；替玩家点一下弹窗里的精灵图标）。
     *
     * **不能在 [cancelListing] 里直接播**：一是 `addFirstAnimation` 依赖 `state.currentModel`，
     * 它要等 `drawProfilePokemon` 跑过才有值；二是弹窗刚弹出就响，玩家还在"看这是啥"，
     * 反应不过来声音从哪来。所以打开时只记「now + [IconCryClick.AUTO_CRY_DELAY_MS]」，渲染处到点才播。
     */
    private var confirmCryAt = 0L

    private val iconData = mutableMapOf<Int, PokemonIconData>()

    private fun cacheIcons() {
        // ⚠ 按**挂单 id** 复用已有的动画状态，别整体丢掉重建：
        //   FloatingState 里存着动画进度与头部跟随的跨帧平滑值，清掉会让图标从头播一遍 ——
        //   用户 2026-09-24 实测：点一下搜索框（会重新拉列表）行里精灵的动画就重置了。
        //   刷新只改变列表的**子集与顺序**，同一个挂单的图标状态应当跨帧延续（照精灵市场）
        val oldStates = iconData.values.mapNotNull { d -> d.listingId?.let { it to d.state } }.toMap()
        iconData.clear()
        listings.forEachIndexed { index, entry ->
            val id = Identifier.tryParse(entry.speciesId) ?: return@forEachIndexed
            val species = PokemonSpecies.getByIdentifier(id) ?: return@forEachIndexed
            // 用挂单真实 aspects（性别形态/地区形态等），与其他界面一致
            val aspects = entry.aspects.toMutableSet()
            if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
            // 携带物同时喂给模型（Cobblemon 会渲染在手上/嘴上）与行内小图标
            val held = heldIconStack(entry.heldItemId, entry.heldItemNbt)
            iconData[index] = PokemonIconData(
                species.translatedName.string,
                RenderablePokemon(species, aspects, heldIconStackForModel(entry.heldItemVisible, entry.heldItemId, entry.heldItemNbt)),
                // 同一个挂单（id 相同）沿用旧的动画状态，只有新出现的挂单才起一份新的
                oldStates[entry.id] ?: FloatingState(),
                ballStack = Identifier.tryParse(entry.ballItem)?.let { ItemStack(Registries.ITEM.get(it)) },
                heldStack = held,
                listingId = entry.id
            )
        }
    }

    /** 携带物栈（空气视为无）；缓存供行渲染每帧复用 */
    override fun init() {
        super.init()
        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        // 返回
        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(AdminScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        ))

        // 货币切换按钮（返回按钮左侧 4px）：看哪个币种的挂单（与玩家端同一个位置、同一套逻辑）
        // ⚠ **管理端不受 RP 开关约束**：开关管的是「玩家之间能不能用 RP 交易」，
        //   而不是管理员的查看/下架权限 —— 关掉后存量 RP 挂单仍要能被管理（否则永远下架不了）
        currencyButton = NineSliceButton(
                leftX + panelWidth - 74, 13, 20, 16,
                Text.literal(""), { onCurrencyClick() },
                iconTexW = POINTS_SYMBOL_SRC, iconTexH = POINTS_SYMBOL_SRC,
                iconScale = CURRENCY_BUTTON_ICON_SIZE / POINTS_SYMBOL_SRC.toFloat(),
                tooltip = Text.translatable("cobblemarket.gui.currency_switch", com.shusheng.cobblemarket.client.inlineCurrencyUnit(), CurrencyService.pointsIcon())
        ).also {
            updateCurrencyButton(it)
            addDrawableChild(it)
        }

        // 物种搜索框 + 折叠
        val savedSearch = searchField?.text ?: ""
        // 搜索框 = **物种名 + 卖家名**（2026-09-24 合并：原来是两个框，服务端分别过滤；
        // 现在一个框同时匹配两者，与拍卖场 / 精灵市场 / 物品市场同一语义）
        searchField = TextFieldWidget(textRenderer, leftX + 2, 44, panelWidth - 4 - 52, 16, Text.translatable("cobblemarket.gui.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.gui.search_placeholder").formatted(Formatting.GRAY))
        // 服务端搜索：文字变化只标记 dirty，tick 防抖后回到第一页重新拉取（与精灵市场一致）
        searchField?.setChangedListener { t ->
            // 特性选项跟着搜索框里的物种走（照精灵市场）
            updateAbilityOptions(t)
            searchDirty = true
            lastSearchEdit = System.currentTimeMillis()
        }
        addSelectableChild(searchField)
        addDrawableChild(searchField)
        searchField?.text = savedSearch

        // 筛选展开/收起按钮（搜索框右侧）：去文字化——展开显示 filter、收起显示 filter_close
        filterToggleButton = NineSliceButton(
            leftX + panelWidth - 52, 44, 50, 16,
            Text.literal(""),
            { toggleFilters() },
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        )
        addDrawableChild(filterToggleButton)
        // 图标 / 选中态 / 悬停提示统一由 updateFilterToggleVisual 设 —— 构造参数里再写一份就等于两处各写一遍
        updateFilterToggleVisual()

        // （原先这里还有一个「玩家搜索框」：2026-09-24 与上面的搜索框合并成一个 ——
        //   一个框同时搜物种名与卖家名，服务端按 OR 匹配）

        // ── 右侧筛选面板内的控件：**常驻创建**，开关只切可见性 ──
        // 原先这一整块包在 `if (filterExpanded)` 里，开关走 clearChildren+init 整界面重建，两个后果：
        // ① 重建清空 iconData 里的**跨帧动画状态**（正在睡觉的精灵会突然站起来一下再睡回去）；
        // ② 展开时列表起点 88→164，**吞掉 3 行**。搬进独立面板后这两件事一起消失（照精灵市场）。
        val fx = MarketPanelBackground.filterLeft(width) + 4
        val fw = MarketPanelBackground.filterWidth(width) - 8

        // IV 六项：面板内**竖排**，每行 = [输入框 84] [三态 40] [单框重置 ↺ 20]，
        // 行距 22 —— 与精灵市场同数值、同顺序（84 + 4 + 40 + 4 + 20 = 152 正好占满面板内容宽）
        ivOpButtons.clear()
        ivResetButtons.clear()
        hpField  = createIvField(fx, 152, "HP");  addIvOpButton(fx + 88, 152, 0); addIvResetButton(fx + 132, 152, 0)
        atkField = createIvField(fx, 174, "ATK"); addIvOpButton(fx + 88, 174, 1); addIvResetButton(fx + 132, 174, 1)
        defField = createIvField(fx, 196, "DEF"); addIvOpButton(fx + 88, 196, 2); addIvResetButton(fx + 132, 196, 2)
        spaField = createIvField(fx, 218, "SpA"); addIvOpButton(fx + 88, 218, 3); addIvResetButton(fx + 132, 218, 3)
        spdField = createIvField(fx, 240, "SpD"); addIvOpButton(fx + 88, 240, 4); addIvResetButton(fx + 132, 240, 4)
        speField = createIvField(fx, 262, "Spd"); addIvOpButton(fx + 88, 262, 5); addIvResetButton(fx + 132, 262, 5)

        // 第一行：★闪光(30) + ♂性别(24) + 排序（尺寸与顺序照精灵市场）
        shinyButton = NineSliceButton(
            fx, 20, 30, 20,
            Text.literal(if (shinyOnly) "★" else "☆"),
            { toggleShiny() },
            // resize 重建时保持当前状态的颜色
            if (shinyOnly) GOLD_COLOR else UNSET_FILTER_COLOR
        )
        addDrawableChild(shinyButton)

        // 公母（照精灵市场：不限态 ♂♀ 双图标，循环 不限→仅公→仅母）
        genderButton = NineSliceButton(
            fx + 34, 20, 24, 20,
            Text.literal(""),
            { cycleGender() },
            iconTexW = 6, iconTexH = 8, iconScale = 1.5f
        )
        updateGenderButton()
        addDrawableChild(genderButton)

        sortButton = NineSliceButton(
            fx + 62, 20, fw - 62, 20,
            Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay())),
            { cycleSort() }
        )
        addDrawableChild(sortButton)

        // 属性 / 特性 / 性格：各占整行，展开浮层贴在各自按钮的正下方
        typeButton = NineSliceButton(
            fx, 42, fw, 20,
            typeButtonText(),
            { toggleFilterList("type") },
            if (typeFilter.isEmpty()) UNSET_FILTER_COLOR else typeColor(typeFilter)
        )
        addDrawableChild(typeButton)

        // 特性：选项跟着搜索框里的物种走（见 updateAbilityOptions）
        abilityButton = NineSliceButton(
            fx, 64, fw, 20,
            abilityButtonText(),
            { toggleFilterList("ability") },
            if (abilityFilter.isEmpty()) UNSET_FILTER_COLOR else GOLD_COLOR
        )
        addDrawableChild(abilityButton)

        // 性格：25 种，按**生效**性格匹配（薄荷后为薄荷性格）
        natureButton = NineSliceButton(
            fx, 86, fw, 20,
            natureButtonText(),
            { toggleFilterList("nature") },
            if (natureFilter.isEmpty()) UNSET_FILTER_COLOR else GOLD_COLOR
        )
        addDrawableChild(natureButton)

        htButton = NineSliceButton(
            fx, 108, fw, 20,
            htButtonText(),
            { toggleHtFilter() },
            if (htFilter != 0) GOLD_COLOR else UNSET_FILTER_COLOR
        )
        addDrawableChild(htButton)

        // 我的（personal/personal_click 双图标：关 = 全部，开 = 仅我的；悬停词条随状态）
        // 与「重置」同一行：我的占左边、重置是右边那枚窄符号按钮（照精灵市场）
        mineButton = NineSliceButton(
            fx, 130, fw - 24, 20,
            Text.literal(""),
            { toggleMineOnly() },
            iconLeft = Identifier.of("cobblemarket",
                if (showMineOnly) "textures/gui/personal_click.png" else "textures/gui/personal.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine")
        )
        addDrawableChild(mineButton)

        // 重置：改用**符号** ↺（同精灵市场 / 配置界面输入框右侧那枚）——
        // 面板只有 152 宽，文字「重置」/`Reset` 占地方，符号 + 悬停提示足够（纯图标按钮的惯例）
        resetButton = NineSliceButton(
            fx + fw - 20, 130, 20, 20,
            Text.literal("↺"),
            { resetFilters() },
            tooltip = Text.translatable("cobblemarket.gui.reset")
        )
        addDrawableChild(resetButton)

        // 登记面板内控件：记下基准 y，滚动时按它重定位（见 filterPanelControls 的说明）
        // ⚠ **必须放在所有面板控件创建完之后** —— 这里读的是 `shinyButton` 等 lateinit，
        //   摆到前面会在 init 里直接抛 UninitializedPropertyAccessException
        //   （2026-09-24 崩溃：点「所有已上架精灵」一进界面就崩）
        filterPanelControls.clear()
        // ⚠ 性别/属性/特性/性格是可空类型，用 listOfNotNull 过滤；排序是 ButtonWidget（非空）单独加
        listOfNotNull(
            shinyButton, genderButton, typeButton, abilityButton, natureButton,
            htButton, mineButton, resetButton
        ).forEach { filterPanelControls += it to it.y }
        filterPanelControls += sortButton to sortButton.y
        listOfNotNull(hpField, atkField, defField, spaField, spdField, speField)
            .forEach { filterPanelControls += it to it.y }
        ivOpButtons.values.forEach { filterPanelControls += it to it.y }
        ivResetButtons.forEach { filterPanelControls += it to it.y }

        // 分页（照精灵市场：底部分割线在最后一行下方 4px 对称，按钮在其与背景底边之间居中偏上 5px；
        // 列表行数由 getMaxVisibleRows 预留 72 保证按钮不压最后一行）
        val listBottom = getListStartY() + getMaxVisibleRows() * 24
        val btnY = (listBottom + 5 + (height - 32)) / 2 - 10 - 5
        prevButton = NineSliceButton(
            leftX, btnY, 80, 20,
            Text.literal(""),
            { prevPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/previous.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.prev")
        )
        addDrawableChild(prevButton)
        nextButton = NineSliceButton(
            leftX + panelWidth - 80, btnY, 80, 20,
            Text.literal(""),
            { nextPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/next.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.next")
        )
        addDrawableChild(nextButton)
        updatePageButtons()

        rebuildCancelButtons()
        refreshData()
    }

    /** IV 单框重置 ↺：只清这一项的输入框与值（不必去点整体重置把六项全清掉），照精灵市场 */
    private fun addIvResetButton(x: Int, y: Int, index: Int) {
        val btn = NineSliceButton(
            x, y, 20, 16,
            Text.literal("↺"),
            {
                ivExact[index] = -1
                ivOps[index] = 0
                listOf(hpField, atkField, defField, spaField, spdField, speField)[index]?.text = ""
                syncIvOpButtons()
                currentPage = 1
                refreshData()
            },
            tooltip = Text.translatable("cobblemarket.gui.reset")
        )
        ivResetButtons += btn
        addDrawableChild(btn)
    }

    // ── 筛选面板：性别 / 属性 / 特性 / 性格（2026-09-24 照精灵市场补齐） ──

    private fun updateGenderButton() {
        genderButton?.iconLeft = if (genderFilter == "FEMALE") GENDER_ICON_FEMALE else GENDER_ICON_MALE
        // 不限态并排显示 ♂♀，选定态只留对应的那个
        genderButton?.iconLeft2 = if (genderFilter.isEmpty()) GENDER_ICON_FEMALE else null
    }

    private fun cycleGender() {
        genderFilter = when (genderFilter) {
            "" -> "MALE"
            "MALE" -> "FEMALE"
            else -> ""
        }
        updateGenderButton()
        currentPage = 1
        refreshData()
    }

    private fun toggleFilterList(kind: String) {
        filterListOpen = if (filterListOpen == kind) "" else kind
        filterListScroll = 0
        rebuildFilterList()
    }

    /** 当前展开列表的选项：属性 / 特性（跟搜索框里的物种走）/ 性格 */
    private fun filterListOptions(): List<Pair<String, String>> = when (filterListOpen) {
        "type" -> allTypes
        "ability" -> listOf("" to "") + abilityOptions
        "nature" -> listOf("" to "") + natureOptions
        else -> emptyList()
    }

    /** 当前展开列表是被哪枚按钮点开的 —— 浮层贴在它正下方（位置只在这里算一次） */
    private fun filterListSourceButton(): NineSliceButton? = when (filterListOpen) {
        "type" -> typeButton
        "ability" -> abilityButton
        "nature" -> natureButton
        else -> null
    }

    private fun rebuildFilterList() {
        filterOptionButtons.forEach { remove(it) }
        filterOptionButtons.clear()
        if (filterListOpen.isEmpty()) {
            // 收起：浮层范围归零，被盖住的按钮立刻回来
            filterListTopY = 0
            filterListBottomY = 0
            updateControlVisibility()
            return
        }
        // 浮层画在**被点按钮的正下方**；⚠ 读控件自己的 `y`，不维护第二份坐标表
        val sourceBtn = filterListSourceButton() ?: return
        val fx = MarketPanelBackground.filterLeft(width) + 4
        val fw = MarketPanelBackground.filterWidth(width) - 8
        val listTop = sourceBtn.y + 22
        val maxRows = minOf(MAX_FILTER_LIST_ROWS, maxOf(1, (height - 32 - listTop) / FILTER_LIST_ROW_H))
        val options = filterListOptions()
        filterListTopY = listTop
        filterListBottomY = listTop + minOf(options.size - filterListScroll, maxRows) * FILTER_LIST_ROW_H
        options.drop(filterListScroll).take(maxRows).forEachIndexed { i, (key, label) ->
            val display = if (key.isEmpty()) Text.translatable("cobblemarket.gui.filter_any").string
                else Text.translatable(label).string.let { t -> if (t == label) label else t }
            val btn = NineSliceButton(
                fx, listTop + i * FILTER_LIST_ROW_H, fw, FILTER_LIST_ROW_H,
                if (key.isNotEmpty() && isFilterSelected(filterListOpen, key))
                    com.shusheng.cobblemarket.util.TextUtil.selectedText(
                        com.shusheng.cobblemarket.util.TextUtil.truncateString(display, 260)
                    )
                else Text.literal(com.shusheng.cobblemarket.util.TextUtil.truncateString(display, 260)),
                { selectFilterOption(filterListOpen, key) },
                // 选项文字：属性用对应属性色，特性 / 性格保持白色
                if (filterListOpen == "type" && key.isNotEmpty()) typeColor(key) else 0xFFFFFF
            )
            filterOptionButtons.add(btn)
            addDrawableChild(btn)
        }
        // 浮层铺好后，被它盖住的面板按钮藏起来（读各自的 y，见 updateControlVisibility）
        updateControlVisibility()
    }

    private fun isFilterSelected(kind: String, key: String): Boolean = when (kind) {
        "type" -> key.isNotEmpty() && key == typeFilter
        "ability" -> key.isNotEmpty() && key == abilityFilter
        "nature" -> key.isNotEmpty() && key == natureFilter
        else -> false
    }

    private fun selectFilterOption(kind: String, key: String) {
        when (kind) {
            "type" -> {
                typeFilter = key
                typeButton?.setMessage(typeButtonText())
                typeButton?.textColor = if (key.isEmpty()) UNSET_FILTER_COLOR else typeColor(key)
            }
            "ability" -> {
                abilityFilter = key
                abilityButton?.setMessage(abilityButtonText())
                abilityButton?.textColor = if (key.isEmpty()) UNSET_FILTER_COLOR else GOLD_COLOR
            }
            "nature" -> {
                natureFilter = key
                natureButton?.setMessage(natureButtonText())
                natureButton?.textColor = if (key.isEmpty()) UNSET_FILTER_COLOR else GOLD_COLOR
            }
        }
        filterListOpen = ""
        rebuildFilterList()
        currentPage = 1
        refreshData()
    }

    // ── 筛选按钮文字（「标签 + 值」，与精灵市场同一口径） ──

    private fun filterPanelBtnW() = MarketPanelBackground.filterWidth(width) - 8

    private fun typeButtonText(): Text = filterButtonText("cobblemarket.gui.type",
        if (typeFilter.isEmpty()) Text.translatable("cobblemarket.gui.filter_any") else Text.translatable(typeFilter))

    private fun abilityButtonText(): Text = filterButtonText("cobblemarket.buy_order.ability_label",
        if (abilityFilter.isEmpty()) Text.translatable("cobblemarket.gui.filter_any") else Text.translatable(abilityFilter))

    private fun natureButtonText(): Text = filterButtonText("cobblemarket.buy_order.nature_label",
        if (natureFilter.isEmpty()) Text.translatable("cobblemarket.gui.filter_any") else Text.translatable(natureFilter))

    /** 筛选按钮文字 =「标签 + 值」；⚠ 值超宽时按按钮宽截断（与精灵市场同一口径） */
    private fun filterButtonText(labelKey: String, value: Text): Text {
        val prefix = Text.translatable(labelKey)
        return prefix.append(Text.literal(com.shusheng.cobblemarket.util.TextUtil.truncateString(
            value.string, filterPanelBtnW() - 8 - textRenderer.getWidth(prefix))))
    }

    private val allTypes = listOf("" to "") + listOf("normal","fire","water","electric","grass","ice","fighting","poison","ground","flying",
        "psychic","bug","rock","ghost","dragon","dark","steel","fairy").map { it to "cobblemon.type.$it" }

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
            abilityButton?.setMessage(abilityButtonText())
            abilityButton?.textColor = UNSET_FILTER_COLOR
        }
    }

    /** IV 三态比较按钮：= → ≥ → ≤ 循环；切换立即重发筛选请求（过滤条件变了） */
    private fun addIvOpButton(x: Int, y: Int, index: Int) {
        val btn = NineSliceButton(
            // 宽 20 → 40（与精灵市场一致）：搬进面板后右侧有余量，三态符号（= / ≥ / ≤）宽一点更好点
            x, y, 40, 16,
            Text.literal(if (ivOps[index] == 1) "≥" else if (ivOps[index] == 2) "≤" else "="),
            {
                ivOps[index] = (ivOps[index] + 1) % 3
                syncIvOpButtons()
                currentPage = 1
                refreshData()
            },
            textColor = if (ivOps[index] != 0) GOLD_COLOR else 0xFFFFFF
        )
        ivOpButtons[index] = btn
        addDrawableChild(btn)
    }

    private fun syncIvOpButtons() {
        ivOpButtons.forEach { (i, btn) ->
            btn.message = Text.literal(if (ivOps[i] == 1) "≥" else if (ivOps[i] == 2) "≤" else "=")
            btn.textColor = if (ivOps[i] != 0) GOLD_COLOR else 0xFFFFFF
        }
    }

    private fun createIvField(x: Int, y: Int, placeholder: String): TextFieldWidget {
        // 宽 68 → 84：搬进右侧面板后竖排、右侧有余量，与精灵市场的输入框同宽（2026-09-24）
        val field = TextFieldWidget(textRenderer, x, y, 84, 16, Text.literal(""))
        field.setPlaceholder(Text.literal(placeholder))
        field.setTextPredicate { it.length <= 2 && it.all { c -> c.isDigit() } }
        addSelectableChild(field)
        addDrawableChild(field)
        return field
    }

    // 84→88：卖家搜索框底边(80)与顶部分割线(80)贴死，列表起始下移 4px 留出间隙。
    // ⚠ 筛选控件搬进右侧面板后（2026-09-24）起点**恒定 88** —— 原先展开时顶到 164、吞掉 3 行
    // 玩家搜索框删掉后（2026-09-24 合并进上面那个搜索框）这一行让了出来：分割线画在 64、
    // 列表从 68 起 —— 比原来的 88 多显示约一行
    private fun getListStartY() = 68
    // 预留 72 = 分页按钮高 20 + 4 空隙 + 底部边框切片 16 + 背景底边下空隙（照精灵市场：按钮不压背景底部边框）
    private fun getMaxVisibleRows() = maxOf(0, (height - getListStartY() - 72) / 24)

    private fun rebuildCancelButtons() {
        cancelButtons.forEach { remove(it) }
        cancelButtons.clear()
        val leftX = width / 2 - panelWidth / 2
        val startY = getListStartY()
        val rowHeight = 24
        displayedListings().take(getMaxVisibleRows()).forEachIndexed { di, (_, entry) ->
            val y = startY + di * rowHeight
            val btn = NineSliceButton(leftX + panelWidth - rowBtnInset, y + rowBtnDy, rowBtnW, rowBtnH, Text.literal("✕").formatted(Formatting.RED), ButtonWidget.PressAction { cancelListing(entry) })
            cancelButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // 带索引列表缓存：render/renderBackground 每帧调用 displayedListings()，
    // 每帧 withIndex().toList() 是分配热点，改为数据到达时重建
    private var indexedListings = listOf<IndexedValue<ListingEntry>>()

    private fun displayedListings(): List<IndexedValue<ListingEntry>> = indexedListings

    private fun sortDisplay(): String = when (sortMode) {
        "PRICE_ASC" -> "cobblemarket.sort.price_asc"
        "PRICE_DESC" -> "cobblemarket.sort.price_desc"
        "LEVEL_ASC" -> "cobblemarket.sort.level_asc"
        "LEVEL_DESC" -> "cobblemarket.sort.level_desc"
        "NEWEST" -> "cobblemarket.sort.newest"
        else -> "cobblemarket.sort.price_asc"
    }

    private fun toggleShiny() {
        shinyOnly = !shinyOnly
        currentPage = 1
        shinyButton.message = Text.literal(if (shinyOnly) "★" else "☆")
        // 开 = 金色 ★，关 = 白色 ☆（与其他界面闪光按钮一致）
        shinyButton.textColor = if (shinyOnly) GOLD_COLOR else 0xFFFFFF
        refreshData()
    }

    private fun toggleMineOnly() {
        showMineOnly = !showMineOnly
        currentPage = 1
        mineButton.iconLeft = Identifier.of("cobblemarket",
            if (showMineOnly) "textures/gui/personal_click.png" else "textures/gui/personal.png")
        mineButton.setTooltip(Tooltip.of(Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine")))
        refreshData()
    }

    private fun cycleSort() {
        sortMode = when (sortMode) {
            "PRICE_ASC" -> "PRICE_DESC"
            "PRICE_DESC" -> "LEVEL_ASC"
            "LEVEL_ASC" -> "LEVEL_DESC"
            "LEVEL_DESC" -> "NEWEST"
            "NEWEST" -> "PRICE_ASC"
            else -> "PRICE_ASC"
        }
        currentPage = 1
        sortButton.message = Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay()))
        refreshData()
    }

    /**
     * 「筛选」开关：**只切面板可见性，不重建界面**。
     *
     * ⚠ 原先这里是 `clearChildren() + init()` 整界面重建 —— 重建会把 [iconData] 里精灵图标的
     *   **跨帧动画状态**一起清掉，玩家看到的就是「点一下筛选，正在睡觉的精灵突然站起来一下再睡回去」
     *   （精灵市场同一个坑，2026-09-23 实测）。
     */
    private fun toggleFilters() {
        filterExpanded = !filterExpanded
        filterAnim.start(filterExpanded)
        updateFilterToggleVisual()
        updateControlVisibility()
    }

    /** 开关按钮的外观（图标 / 悬停提示）随 [filterExpanded] 更新 —— 不重建就得手动更一次 */
    private fun updateFilterToggleVisual() {
        if (!::filterToggleButton.isInitialized) return
        filterToggleButton.iconLeft = Identifier.of("cobblemarket",
            if (filterExpanded) "textures/gui/filter.png" else "textures/gui/filter_close.png")
        // 面板展开期间按钮保持**选中态**（按钮背景第三态）：一眼看出面板开着还是关着（照精灵市场）
        filterToggleButton.selectedVisual = filterExpanded
        filterToggleButton.setTooltip(Tooltip.of(Text.translatable(
            if (filterExpanded) "cobblemarket.gui.filter_collapse" else "cobblemarket.gui.filter_expand")))
    }

    private fun resetFilters() {
        searchField?.text = ""
        shinyOnly = false
        showMineOnly = false
        htFilter = 0
        sortMode = "NEWEST"
        currentPage = 1
        // 2026-09-24 追加的四项（本界面的重置**连搜索框一起清**，与拍卖场 / 上架选择 / 创建拍卖不同 ——
        // 那三个界面的重置只清筛选条件；这里保持原有行为不动）
        genderFilter = ""
        updateGenderButton()
        typeFilter = ""
        abilityFilter = ""
        natureFilter = ""
        typeButton?.setMessage(typeButtonText())
        typeButton?.textColor = UNSET_FILTER_COLOR
        abilityButton?.setMessage(abilityButtonText())
        abilityButton?.textColor = UNSET_FILTER_COLOR
        natureButton?.setMessage(natureButtonText())
        natureButton?.textColor = UNSET_FILTER_COLOR
        filterListOpen = ""
        filterListScroll = 0
        filterListTopY = 0
        filterListBottomY = 0
        filterPanelScroll = 0
        for (i in 0..5) { ivExact[i] = -1; ivOps[i] = 0 }
        syncIvOpButtons()
        hpField?.text = ""; atkField?.text = ""; defField?.text = ""
        spaField?.text = ""; spdField?.text = ""; speField?.text = ""
        shinyButton.message = Text.literal("☆")
        shinyButton.textColor = UNSET_FILTER_COLOR
        htButton?.setMessage(htButtonText())
        htButton?.textColor = UNSET_FILTER_COLOR
        sortButton.message = Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay()))
        mineButton.iconLeft = Identifier.of("cobblemarket", "textures/gui/personal.png")
        mineButton.setTooltip(Tooltip.of(Text.translatable("cobblemarket.gui.mine")))
        refreshData()
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
        currentPage = 1
        htButton?.setMessage(htButtonText())
        // 筛选生效 = 金色，不限 = 白色（与闪光按钮一致）
        htButton?.textColor = if (htFilter != 0) GOLD_COLOR else 0xFFFFFF
        // 走防抖发送：立即发送会撞上服务端 250ms 节流（搜索刚发过请求时点击，请求被吞，
        // 按钮状态与列表脱节），防抖保证最终态请求落在节流窗口外
        searchDirty = true
        lastSearchEdit = System.currentTimeMillis()
    }

    // 翻页请求在途标志：响应到达前禁用翻页按钮，防止快速连点
    private var pageRequestInFlight = false

    // 最近一次列表请求发送时间：仅用于 tick 兜底超时复位（管理端请求服务端无节流）
    private var lastListRequestAt = 0L

    private fun updatePageButtons() {
        prevButton?.active = !pageRequestInFlight && currentPage > 1
        nextButton?.active = !pageRequestInFlight && currentPage < totalPages
    }

    private fun prevPage() {
        if (pageRequestInFlight || currentPage <= 1) return
        pageRequestInFlight = true
        updatePageButtons()
        currentPage--
        refreshData()
    }

    private fun nextPage() {
        if (pageRequestInFlight || currentPage >= totalPages) return
        pageRequestInFlight = true
        updatePageButtons()
        currentPage++
        refreshData()
    }

    private fun syncIvFromFields(): Boolean {
        val fields = arrayOf(hpField, atkField, defField, spaField, spdField, speField)
        var changed = false
        for (i in 0..5) {
            val raw = fields[i]?.text ?: ""
            val digits = raw.filter { it.isDigit() }.take(2)
            val iv = if (digits.isEmpty()) -1 else digits.toIntOrNull()?.coerceIn(0, 31) ?: -1
            if (digits != raw) fields[i]?.text = if (iv <= 0) "" else iv.toString()
            if (ivExact[i] != iv) changed = true
            ivExact[i] = iv
        }
        return changed
    }

    /** 点货币按钮：「玩家间 RP 交易」没开就只给反馈、不切（置灰态按钮同样走这里） */
    private fun onCurrencyClick() {
        // ⚠ 管理端**不拦开关**：管理员要能查看并下架存量的 RP 挂单
        toggleCurrency()
    }

    /** 切主货币 ↔ RP：整屏换成另一半挂单，**必须重置页码**（原页码在新币种下多半是空的） */
    private fun toggleCurrency() {
        currency = if (currency == MarketCurrency.POINTS) MarketCurrency.PRIMARY else MarketCurrency.POINTS
        currencyButton?.let { updateCurrencyButton(it) }
        currentPage = 1
        refreshData()
    }

    /** 刷新货币按钮外观：主货币显示 `₽`，RP 显示图标（与玩家端/上架界面同一套口径） */
    private fun updateCurrencyButton(btn: NineSliceButton) {
        val points = currency == MarketCurrency.POINTS
        btn.setMessage(Text.literal(if (points) "" else "₽"))
        btn.iconLeft = if (points) POINTS_SYMBOL_TEXTURE else null
    }

    private fun refreshData() {
        lastListRequestAt = System.currentTimeMillis()
        sendToServer(
            AdminRequestPokemonPayload(
                // 中文物种名在客户端转成资源路径 id（照精灵市场），服务端只存英文资源名
                speciesFilter = com.shusheng.cobblemarket.network.localizeSpeciesQuery(searchField?.text?.trim().orEmpty()),
                // 卖家名不再单独发：服务端拿上面的 speciesFilter 同时匹配物种名与卖家名（OR）
                // （字段保留但恒为空串，免得动 payload 的序列化顺序）
                sellerFilter = "",
                shinyOnly = shinyOnly,
                sortMode = sortMode,
                page = currentPage,
                ivExactHp = ivExact[0],
                ivExactAtk = ivExact[1],
                ivExactDef = ivExact[2],
                ivExactSpAtk = ivExact[3],
                ivExactSpDef = ivExact[4],
                ivExactSpd = ivExact[5],
                ivOpHp = ivOps[0],
                ivOpAtk = ivOps[1],
                ivOpDef = ivOps[2],
                ivOpSpAtk = ivOps[3],
                ivOpSpDef = ivOps[4],
                ivOpSpd = ivOps[5],
                pageSize = getMaxVisibleRows(),
                mineOnly = showMineOnly,
                htFilter = htFilter,
                currency = currency.name,
                // 2026-09-24 追加：性别 / 属性 / 特性 / 性格（照精灵市场补齐筛选面板）
                genderFilter = genderFilter,
                typeFilter = typeFilter,
                abilityFilter = abilityFilter,
                natureFilter = natureFilter
            )
        )
    }

    // 防抖计时器（照精灵市场）：250ms 静默后统一发请求
    override fun tick() {
        // 兜底：翻页请求 1s 未收到响应强制复位 inFlight，防止按钮永久锁灰
        if (pageRequestInFlight && System.currentTimeMillis() - lastListRequestAt > 1000) {
            pageRequestInFlight = false
            updatePageButtons()
        }
        if (searchDirty && System.currentTimeMillis() - lastSearchEdit >= 250) {
            searchDirty = false
            currentPage = 1
            refreshData()
        }
    }

    fun onMarketData(payload: MarketDataPayload) {
        pageRequestInFlight = false
        updatePageButtons()
        listings = payload.entries
        totalPages = payload.totalPages
        currentPage = payload.currentPage
        indexedListings = listings.withIndex().toList()
        tooltipCacheKey = null
        cacheIcons()
        rebuildCancelButtons()
    }

    fun onMarketResult(payload: MarketResultPayload) {
        client?.player?.sendMessage(payload.message.copy().formatted(if (payload.success) Formatting.GREEN else Formatting.RED), false)
        if (payload.success) refreshData()
    }

    private fun cancelListing(entry: ListingEntry?) {
        val e = entry ?: return
        confirmEntry = e
        confirmScroll = 0
        confirmRenderable = null
        confirmDisplayName = e.species
        val id = Identifier.tryParse(e.speciesId)
        if (id != null) {
            val species = PokemonSpecies.getByIdentifier(id)
            if (species != null) {
                // 用挂单真实 aspects（性别形态/地区形态等），与其他界面一致
                val aspects = e.aspects.toMutableSet()
                if (e.shiny && "shiny" !in aspects) aspects.add("shiny")
                confirmRenderable = RenderablePokemon(species, aspects, heldIconStackForModel(e.heldItemVisible, e.heldItemId, e.heldItemNbt))
                confirmDisplayName = species.translatedName.string
            }
        }
        // 弹窗打开时自动叫一声（渲染后延迟再播，理由见 confirmCryAt）
        confirmCryAt = System.currentTimeMillis() + IconCryClick.AUTO_CRY_DELAY_MS
    }

    private fun confirmCancel() {
        val entry = confirmEntry ?: return
        sendToServer(AdminCancelPokemonPayload(entry.id))
        confirmEntry = null
    }

    private fun typeColor(typeKey: String): Int = when (typeKey.substringAfterLast(".").lowercase()) {
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
        val centerX = width / 2
        // 主面板**保持居中不动**（不传 leftOverride）—— 筛选面板占用它右侧本来就空着的区域，
        // 这样主面板里所有控件坐标一个都不用动
        MarketPanelBackground.draw(context, width, height)
        // 右侧筛选面板（面板展开、且没有确认弹窗时）：高度由内容定（224），不跟主面板铺到屏幕底边
        // ⚠ 展开中、或收起动画还没播完都要画（后者要从目标位置缩回按钮），照精灵市场
        if (confirmEntry == null && (filterExpanded || filterAnim.isActive())) {
            val fl = MarketPanelBackground.filterLeft(width)
            val fw = MarketPanelBackground.filterWidth(width)
            val ph = filterPanelHeight()
            val panelCx = fl + fw / 2f
            val panelCy = 2f + ph / 2f
            // 动画锚点 = 「筛选」按钮中心；按钮还没建时退化成"原地不动"（照精灵市场）
            val btnCx = if (::filterToggleButton.isInitialized) filterToggleButton.x + filterToggleButton.width / 2f else panelCx
            val btnCy = if (::filterToggleButton.isInitialized) filterToggleButton.y + filterToggleButton.height / 2f else panelCy
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

        val leftX = centerX - panelWidth / 2
        val startY = getListStartY()
        val rowHeight = 24
        val visibleRows = getMaxVisibleRows()
        val displayList = displayedListings()

        hoveredRow = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY in startY..(startY + visibleRows * rowHeight)) {
            val row = (mouseY - startY) / rowHeight
            if (row in displayList.indices) hoveredRow = row
        }

        displayList.take(visibleRows).forEachIndexed { di, _ ->
            val rowY = startY + di * rowHeight
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, if (di == hoveredRow) 1 else 0, ROW_BACKGROUND_TEX_H)
        }
    }

    /**
     * 下架确认弹窗是**模态**的：打开时把下层控件全部藏掉。
     *
     * ⚠ 必须 `visible = false` 而不是「不画就行」—— 原版 `ClickableWidget` 只要 visible 就照常
     * 检测 hover 并弹自己的 tooltip，弹出按钮的位置正好压住某个下层按钮时，鼠标停在弹窗按钮上
     * 弹出的却是下层那条提示（2026-09-19 用户实测，精灵市场先报的，本界面同一套结构一并修）。
     */
    /**
     * 主面板与筛选面板的可见性，每帧在 [render] 开头调一次（两头的开关状态不必各自记着恢复）。
     *
     * ⚠ 面板内的控件现在是**常驻创建**的（2026-09-24 搬进右侧面板），判据只剩两件事：
     *   面板开着没有（[filterExpanded]）、确认弹窗开着没有（弹窗是模态，下层一律 `visible = false` ——
     *   只"不画"不行：原版 `ClickableWidget` 只要 visible 就照常响应点击与悬停、还会弹自己的 tooltip）。
     */
    private fun updateControlVisibility() {
        val dialogOpen = confirmEntry != null
        // ⚠ 动画进行中整块藏起来（面板内是 MC 控件，塞不进矩阵、不会跟着缩放），照精灵市场
        val animHidden = filterAnim.isActive()
        val panelOn = filterExpanded && !dialogOpen && !animHidden
        // 展开列表的浮层：盖住谁就藏谁（读控件自己的 y，不维护第二份坐标表）
        val covered = filterListOpen.isNotEmpty() && filterListBottomY > filterListTopY
        fun coveredAt(y: Int?) = covered && y != null && y >= filterListTopY && y < filterListBottomY
        fun showPanel(w: NineSliceButton?) {
            if (w != null) w.visible = panelOn && !coveredAt(w.y)
        }
        // 主面板：搜索框与货币切换按钮常显（只跟弹窗走）
        searchField?.visible = !dialogOpen
        currencyButton?.visible = !dialogOpen
        // 右侧筛选面板：面板收起 / 弹窗打开时整块藏掉
        listOf(hpField, atkField, defField, spaField, spdField, speField).forEachIndexed { i, f ->
            val vis = panelOn && !coveredAt(ivOpButtons[i]?.y)
            f?.visible = vis
            ivOpButtons[i]?.visible = vis
            ivResetButtons.getOrNull(i)?.visible = vis
        }
        if (::shinyButton.isInitialized) showPanel(shinyButton)
        // 排序在第一行（y=20），浮层从 64 起、够不到它
        if (::sortButton.isInitialized) sortButton.visible = panelOn
        if (::resetButton.isInitialized) showPanel(resetButton)
        if (::mineButton.isInitialized) showPanel(mineButton)
        showPanel(htButton)
        showPanel(genderButton)
        showPanel(typeButton)
        showPanel(abilityButton)
        showPanel(natureButton)
        // 其余主面板控件
        if (::filterToggleButton.isInitialized) filterToggleButton.visible = !dialogOpen
        if (::prevButton.isInitialized) prevButton.visible = !dialogOpen
        if (::nextButton.isInitialized) nextButton.visible = !dialogOpen
        // 面板内滚动：按登记的基准 y 重定位，滚出面板范围的控件藏起来（滚轮改了 filterPanelScroll
        // 之后调本函数重排）。⚠ 放在最后 —— 它会覆盖上面设的 visible
        val panelTop = 4
        val panelBottom = 2 + filterPanelHeight()
        filterPanelControls.forEach { (w, baseY) ->
            w.y = baseY - filterPanelScroll
            if (w.y < panelTop || w.y + w.height > panelBottom) w.visible = false
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        updateControlVisibility()
        super.render(context, mouseX, mouseY, delta)

        if (syncIvFromFields()) {
            currentPage = 1
            refreshData()
        }

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.op.pokemon").formatted(Formatting.GOLD),
            centerX, 14, 0xFFFFFF
        )

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.gui.page", currentPage, totalPages).formatted(Formatting.GRAY),
            centerX, 32, 0xFFFFFF
        )

        val dividerY = getListStartY() - 4
        val startY = getListStartY()
        val rowHeight = 24
        val visibleRows = getMaxVisibleRows()

        drawPanelDivider(context, leftX + panelWidth / 2, dividerY)
        // 底部分割线：最多显示行数的最后一行下方 4px（与顶部 startY-4 对称），分页按钮在其与背景底边之间居中
        val listBottom = getListStartY() + visibleRows * rowHeight
        drawPanelDivider(context, leftX + panelWidth / 2, listBottom + 4)

        val displayList = displayedListings()

        if (displayList.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.gui.no_listings").formatted(Formatting.GRAY),
                centerX, startY + 50, 0xFFFFFF
            )
        }

        displayList.take(visibleRows).forEachIndexed { di, (origIndex, entry) ->
            val y = startY + di * rowHeight

            // 槽背景（GUI 层，弹窗遮罩自动压暗，照常渲染）
            val slotX = leftX + 2
            val slotY = y + 2
            context.matrices.push()
            context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
            context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
            context.drawTexture(SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
            context.matrices.pop()

            // 3D 精灵（弹窗打开时不渲染——模型层在衬底之上，压暗仍会浮在弹窗上）
            if (confirmEntry == null) renderPokemonIcon(context, origIndex, leftX + 2, y + 2, iconSize, delta = delta, preview = di == hoveredRow)

            // 球种（名称左侧；弹窗打开时隐藏——物品图标无色调参数无法变暗）
            if (confirmEntry == null) {
                iconData[origIndex]?.ballStack?.let { ballStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = ballStack,
                        x = leftX + 26.0,
                        y = y + 6.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                }
            }

            // Species name (translated) + 金色闪光星标（★，拆段绘制）
            // 名字截断 54px ≈ 6 个汉字（超出带「…」）：名字后图标链固定 55px，与头像 149 仍留余量
            val displayName = com.shusheng.cobblemarket.util.TextUtil.truncateString(
                iconData[origIndex]?.displayName ?: entry.species, 54)
            context.drawTextWithShadow(textRenderer, displayName, leftX + 40, y + 7, typeColor(entry.primaryType))
            var nameWidth = textRenderer.getWidth(displayName)
            if (entry.shiny) {
                context.drawText(textRenderer, "★", leftX + 40 + nameWidth + 2, y + 7, GOLD_COLOR, false)
                nameWidth += 2 + textRenderer.getWidth("★")
            }

            var iconOffset = 0
            if (entry.gender == "MALE" || entry.gender == "FEMALE") {
                val genderIcon = if (entry.gender == "MALE") GENDER_ICON_MALE else GENDER_ICON_FEMALE
                val genderX = leftX + 40 + nameWidth + 2
                com.cobblemon.mod.common.api.gui.blitk(
                    matrixStack = context.matrices,
                    texture = genderIcon,
                    x = genderX,
                    y = y + 7,
                    width = 6,
                    height = 8
                )
                iconOffset = 8
            }
            if (confirmEntry == null) {
                iconData[origIndex]?.heldStack?.let { heldStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = heldStack,
                        x = leftX + 40 + nameWidth + 2 + iconOffset + 0.0,
                        y = y + 6.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                    iconOffset += 12
                }
            }
            // 体型徽章（排在携带物图标之后，留 3px 空隙）
            if (entry.sizeCategory.isNotEmpty()) {
                if (iconOffset > 0) iconOffset += 3
                iconOffset += EntryBadgeRenderer.drawSizeBadgeIcon(
                    context, entry.sizeCategory, leftX + 40 + nameWidth + 2 + iconOffset, y + 7)
            }

            // 头像位置与精灵市场/拍卖场一致（leftX+149：名字区最坏到 149px——6 字名 + 星 + 性别 + 携带物 + 体型徽章）
            drawSellerAvatar(context, entry.sellerUuid, entry.sellerName, leftX + 149, y + 4, 16)

            // 等级跟随头像右移，保持 20px 间距
            val levelText = Text.translatable("cobblemarket.gui.lv").string + entry.level
            context.drawText(textRenderer, levelText, leftX + 169, y + 7, 0x000000, false)

            // 价格右对齐到取消按钮左缘（行内缩写，同精灵市场；悬停窗仍为完整千分位）
            // 单位走统一入口，币种取**这条挂单自己的**（行内用恒定 ₽ 写法）
            val priceText = com.shusheng.cobblemarket.client.amountWithUnit(
                com.shusheng.cobblemarket.client.formatPriceShort(entry.price),
                entry.isPoints, entry.currencyName, inline = true
            )
            val btnLeft = leftX + panelWidth - 42
            context.drawTextWithShadow(textRenderer, priceText, btnLeft - textRenderer.getWidth(priceText) - 4, y + 7, 0xFFAA00)
        }

        if (confirmEntry == null && hoveredRow in displayList.indices) {
            renderTooltip(context, displayList[hoveredRow].value, displayList[hoveredRow].index, mouseX, mouseY)
        }

        // 每帧刷新按钮状态（render 覆写为最新 currentPage/totalPages，含 inFlight 置灰，见 updatePageButtons）
        updatePageButtons()

        if (confirmEntry != null) {
            renderConfirmDialog(context, mouseX, mouseY, delta)
        }

        // 悬停预览大图：画在本界面内容全部之后才在面板之上。
        // **弹窗打开时一律不画**（照精灵市场）：这时注册表里只剩弹窗自己的图标，鼠标停在弹窗上
        // 照样能命中 entryInRow（它只按 y 匹配行范围 + x 在面板内）→ 左侧无故冒出大图（2026-09-15 实测）
        if (confirmEntry == null) {
            val previewRowL = width / 2 - panelWidth / 2
            IconRegistry.entryInRow(mouseX.toDouble(), mouseY.toDouble(), previewRowL, previewRowL + panelWidth)
                ?.let { PokemonHoverPreview.render(context, it.renderable, it.state, previewRowL, height) }
        }
    }

    private val defaultSkinTexture = Identifier.of("minecraft", "textures/entity/player/wide/steve.png")

    // 皮肤纹理缓存：每行每帧创建 GameProfile 并查 skinProvider 是分配热点，首个结果按 UUID 缓存
    private val skinCache = mutableMapOf<java.util.UUID, Identifier>()

    private fun getSellerSkin(uuid: java.util.UUID, name: String): Identifier {
        skinCache[uuid]?.let { return it }
        val skin = client?.networkHandler?.getPlayerListEntry(uuid)?.skinTextures?.texture()
            ?: client?.skinProvider?.getSkinTextures(com.mojang.authlib.GameProfile(uuid, name))?.texture()
            ?: defaultSkinTexture
        skinCache[uuid] = skin
        return skin
    }

    private fun drawSellerAvatar(context: DrawContext, uuid: java.util.UUID, name: String, x: Int, y: Int, size: Int) {
        val texture = getSellerSkin(uuid, name)
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(size / 8f, size / 8f, 1f)
        context.drawTexture(texture, 0, 0, 8f, 8f, 8, 8, 64, 64)
        context.matrices.pop()
    }

    private fun renderPokemonIcon(
        context: DrawContext, index: Int, x: Int, y: Int, size: Int, delta: Float = 0f,
        /** 本行是否被悬停（跟左侧大图一起走 walk）。**必须由调用方按行索引算**：
         *  早先用 `IconRegistry.isMouseInRow` 只看纵向，鼠标跑到面板外侧、只要 y 平齐也会触发动画（2026-09-15） */
        preview: Boolean = false
    ) {
        val data = iconData[index] ?: return run {
            context.fill(x, y, x + size, y + size, 0x88888888.toInt())
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
    }

    // tooltip 内容缓存：内容只取决于条目，悬停同一行时每帧重建全部文本行是悬停掉帧主因
    private var tooltipCacheKey: java.util.UUID? = null
    private var tooltipCacheLines: List<Pair<Text?, Int>> = emptyList()
    private var tooltipCacheHeldLine = -1
    private var tooltipCacheMaxWidth = 0
    private var tooltipCacheMovesLine = -1
    private var tooltipCacheMarksLine = -1

    private fun renderTooltip(context: DrawContext, entry: ListingEntry, origIndex: Int, mouseX: Int, mouseY: Int) {
        // 文本行缓存：悬停同一行时内容不变，只在悬停目标变化时重建（见 tooltipCacheKey 注释）
        if (tooltipCacheKey != entry.id) {
            tooltipCacheKey = entry.id
            val hp = StatNames.HP
            val atk = StatNames.ATK
            val def = StatNames.DEF
            val spa = StatNames.SP_ATK
            val spd = StatNames.SP_DEF
            val spe = StatNames.SPEED

            val w = 0xFFFFFF
            val ivColors = intArrayOf(0x66FF66, 0xFF6666, 0xFFCC66, 0x6699FF, 0x66FF99, 0xFF99FF)

            val hasHeldItem = entry.heldItemId.isNotEmpty() &&
                Identifier.tryParse(entry.heldItemId)?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true

            val lines = mutableListOf<Pair<Text?, Int>>()
            lines.add(EntryBadgeRenderer.nameWithLevelLine(
                EntryBadgeRenderer.nameWithShinyStar(iconData[origIndex]?.displayName ?: entry.species, entry.shiny),
                entry.primaryType, entry.level) to w)
            lines.add(Text.literal(Text.translatable("cobblemarket.gui.tooltip_type").string).append(EntryBadgeRenderer.typeLine(entry.primaryType, entry.secondaryType)) to w)
            lines.add(Text.literal(Text.translatable("cobblemarket.gui.tooltip_nature").string)
                .append(EntryBadgeRenderer.natureText(entry.natureBase, entry.nature))
                .append(Text.literal("  ${Text.translatable("cobblemarket.gui.tooltip_ability").string}"))
                .append(Text.translatable(entry.ability)) to w)
            var heldItemLine = -1
            if (hasHeldItem) {
                heldItemLine = lines.size
                lines.add(Text.translatable("cobblemarket.gui.tooltip_held") to w)
            }
            lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs") to w)
            lines.add(Text.literal("  $hp: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsHp, entry.htHp)}").append(Text.literal("  EV: ${entry.evsHp}").formatted(Formatting.RED)) to ivColors[0])
            lines.add(Text.literal("  $atk: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsAtk, entry.htAtk)}").append(Text.literal("  EV: ${entry.evsAtk}").formatted(Formatting.RED)) to ivColors[1])
            lines.add(Text.literal("  $def: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsDef, entry.htDef)}").append(Text.literal("  EV: ${entry.evsDef}").formatted(Formatting.RED)) to ivColors[2])
            lines.add(Text.literal("  $spa: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpAtk, entry.htSpAtk)}").append(Text.literal("  EV: ${entry.evsSpAtk}").formatted(Formatting.RED)) to ivColors[3])
            lines.add(Text.literal("  $spd: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpDef, entry.htSpDef)}").append(Text.literal("  EV: ${entry.evsSpDef}").formatted(Formatting.RED)) to ivColors[4])
            lines.add(Text.literal("  $spe: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpd, entry.htSpd)}").append(Text.literal("  EV: ${entry.evsSpd}").formatted(Formatting.RED)) to ivColors[5])
            lines.add(Text.translatable("cobblemarket.gui.friendship", entry.friendship) to 0xFF99CC)
            // 技能块（**总是画** —— Cobblemon 里不存在没技能的精灵）：分割线 + 两行技能 + 分割线
            var movesLine = -1
            if (MoveText.hasAny(entry.moves)) {
                movesLine = lines.size
                lines.add(null to 0)
            }
            // 证章区块：技能块下方，图标行 + 下分割线（与技能块共用上分割线；照市场悬停 tooltip）
            var marksLine = -1
            if (entry.marks.isNotEmpty()) {
                marksLine = lines.size
                lines.add(null to 0)
                lines.add(null to 0)
            }
            lines.add(Text.translatable("cobblemarket.gui.tooltip_seller").append(Text.literal(entry.sellerName)) to w)
            lines.add(Text.translatable("cobblemarket.gui.tooltip_price").append(
                com.shusheng.cobblemarket.client.amountWithUnit(
                    com.shusheng.cobblemarket.client.formatPrice(entry.price), entry.isPoints, entry.currencyName
                ).formatted(Formatting.GOLD)) to w)

            var maxWidth = 0
            lines.forEach { it.first?.let { t -> maxWidth = maxOf(maxWidth, textRenderer.getWidth(t)) } }
            // 名字行尾部图标（公母 + 体型徽章）不计入文本宽度，单独补上
            lines[0].first?.let { maxWidth = maxOf(maxWidth, EntryBadgeRenderer.nameLineWidth(it, entry.gender, entry.sizeCategory)) }
            if (heldItemLine >= 0) {
                maxWidth = maxOf(maxWidth, textRenderer.getWidth(lines[heldItemLine].first) + 14)
            }
            // 证章行宽（每行 MARKS_PER_ROW 个 12px 格）
            if (marksLine >= 0) maxWidth = maxOf(maxWidth, minOf(EntryBadgeRenderer.MARKS_PER_ROW, entry.marks.size) * 12)
            maxWidth = maxOf(maxWidth, EntryBadgeRenderer.movesBlockWidth(entry.moves))
            tooltipCacheLines = lines
            tooltipCacheHeldLine = heldItemLine
            tooltipCacheMaxWidth = maxWidth
            tooltipCacheMovesLine = movesLine
            tooltipCacheMarksLine = marksLine
        }
        val lines = tooltipCacheLines
        val heldItemLine = tooltipCacheHeldLine
        val maxWidth = tooltipCacheMaxWidth
        val movesLine = tooltipCacheMovesLine
        val marksLine = tooltipCacheMarksLine

        val padding = 4
        // 证章超过一行时第二行「+N」额外占 12px
        val marksExtra = if (marksLine >= 0 && entry.marks.size > EntryBadgeRenderer.MARKS_PER_ROW) 12 else 0
        val tooltipHeight = lines.size * 10 + padding + marksExtra + EntryBadgeRenderer.movesBlockExtra(entry.moves)
        // 悬停窗**不许把本行的 ✕（下架）按钮盖掉一半以上**：本界面整屏都是下架按钮，被盖住就没法操作。
        // 矩形按行号算几何（与 rebuildCancelButtons 同源），不去查按钮列表
        val (tx, ty) = TooltipPlacement.place(
            mouseX = mouseX, mouseY = mouseY,
            textW = maxWidth, textH = tooltipHeight, pad = padding,
            screenW = width, screenH = height, avoid = cancelButtonRect(hoveredRow)
        ) ?: return

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, tooltipHeight + padding, 1, ROW_BACKGROUND_TEX_H)
        var rowY = ty
        lines.forEachIndexed { i, (line, color) ->
            if (line == null && i == movesLine) {
                // 技能块：分割线 + 两行技能 + 分割线（左对齐版，分割线撑满提示框全宽）
                rowY += EntryBadgeRenderer.drawMovesBlockLeft(context, entry.moves, tx, maxWidth, rowY)
            } else if (line == null && i == marksLine) {
                // 证章图标行：左对齐排（照市场悬停；超过 6 个第二行左对齐显示「+N」）
                rowY += EntryBadgeRenderer.drawMarksRow(context, entry.marks, tx, rowY)
            } else if (line == null) {
                // 分割线：1px 灰线，撑满提示框全宽（与信息区分隔线一致，不随证章数量变化）
                val rowW = maxWidth
                context.fill(tx, rowY + 4, tx + rowW, rowY + 5, 0xFF888888.toInt())
                rowY += 10
            } else if (i == heldItemLine) {
                context.drawTextWithShadow(textRenderer, line, tx, rowY, color)
                val heldIconX = tx + textRenderer.getWidth(line) + 2
                iconData[origIndex]?.heldStack?.let { heldStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = heldStack,
                        x = heldIconX.toDouble(),
                        y = rowY + 0.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                }
                // 玩家把携带物设成「隐藏」时注明一句（模型上不显示它，而信息行照常列出）—— 用户 2026-09-26
                if (!entry.heldItemVisible) {
                    context.drawTextWithShadow(
                        textRenderer, Text.translatable("cobblemarket.gui.held_hidden"), heldIconX + 12, rowY, 0xAAAAAA
                    )
                }
                rowY += 10
            } else if (i == 0) {
                // 第一行（名字★Lv）带公母图标
                EntryBadgeRenderer.drawNameLineLeft(context, line, entry.gender, tx, rowY, color, entry.sizeCategory)
                rowY += 10
            } else {
                context.drawTextWithShadow(textRenderer, line, tx, rowY, color)
                rowY += 10
            }
        }
        context.matrices.pop()
    }

    /**
     * 下架确认弹窗高度：按信息区实际内容算——起始 +60，13 基础行
     * （名字/类型/性格特性/[球种]/[携带物]/IV 标签/6×IV/亲密度/[证章]/卖家/价格）
     * 加球种/携带物行与证章区块，底部再留 32px 给按钮（按钮高 20 + 4px 间隙 + 8px 边距）。
     * 加新信息行（如技能）时只改这里的 13，按钮位置自动跟随。
     */
    private fun confirmDialogHeight(entry: ListingEntry): Int {
        val infoRows = 13 +
            (if (entry.ball.isNotEmpty()) 1 else 0) +
            (if (EntryBadgeRenderer.hasHeldItemLine(entry)) 1 else 0)
        return 60 + infoRows * 10 +
            EntryBadgeRenderer.movesBlockHeight(entry.moves) +
            EntryBadgeRenderer.marksBlockHeight(entry.marks, entry.moves) + 32
    }

    /** 弹窗实际高度：**按屏幕钳制**（信息行多时设计高度会顶出屏幕，见求购单创建弹窗那套） */
    private fun confirmDialogH(entry: ListingEntry): Int = minOf(confirmDialogHeight(entry), height - 8)

    private fun confirmViewH(entry: ListingEntry): Int =
        (confirmDialogH(entry) - confirmContentTop - confirmBottomH).coerceAtLeast(1)

    private fun confirmContentH(entry: ListingEntry): Int = confirmDialogHeight(entry) - confirmContentTop - confirmBottomH

    private fun confirmMaxScroll(entry: ListingEntry): Int = maxOf(0, confirmContentH(entry) - confirmViewH(entry))

    private fun renderConfirmDialog(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val entry = confirmEntry ?: return
        val centerX = width / 2
        val dialogW = maxOf(300, EntryBadgeRenderer.measureInfoLines(entry, EntryBadgeRenderer.nameWithShinyStar(confirmDisplayName, entry.shiny)) + 40).coerceAtMost(width - 20)
        val dialogH = confirmDialogH(entry)
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 100.0)
        drawScreenDimMask(context, width, height)
        context.matrices.pop()

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 200.0)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.cancel_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        val iconSize = 28
        val iconX = centerX - iconSize / 2
        val iconY = dialogY + 26
        context.matrices.push()
        context.matrices.translate(iconX.toDouble(), iconY.toDouble(), 0.0)
        context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
        context.drawTexture(SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
        context.matrices.pop()
        confirmRenderable?.let { rp ->
            // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
            val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
            // 该弹窗图标原先没有裁剪，沿用 useScissor = false
            PokemonIconRenderer.draw(
                context = context,
                renderable = rp,
                state = confirmState.stateFor(rp),
                x = iconX, y = iconY, size = iconSize,
                delta = delta,
                animate = useFloat,
                useScissor = false
            )
            // 打开弹窗时自动叫一声（替玩家点一下这个图标）：**必须在渲染之后** ——
            // addFirstAnimation 依赖 currentModel，那是 drawProfilePokemon 里才建好的；
            // 再叠一层 AUTO_CRY_DELAY_MS 延迟，等弹窗稳定显示、玩家看清了再叫
            if (confirmCryAt != 0L && System.currentTimeMillis() >= confirmCryAt) {
                confirmCryAt = 0L
                IconCryClick.play(confirmState.stateFor(rp), rp)
            }
        }

        // 完整信息行（与市场列表悬停 tooltip 结构一致）：名字★Lv / 类型 / 性格特性 / 携带物 / IV / 技能 / 证章 / 卖家 / 价格
        // 弹窗被屏幕压矮时**信息区可滚动**（scissor 裁剪 + 起始 y 减偏移）
        confirmScroll = confirmScroll.coerceIn(0, confirmMaxScroll(entry))
        val viewTop = dialogY + confirmContentTop
        val viewBottom = dialogY + dialogH - confirmBottomH
        context.enableScissor(dialogX, viewTop, dialogX + dialogW, viewBottom)
        EntryBadgeRenderer.drawInfoLines(
            context, entry, EntryBadgeRenderer.nameWithShinyStar(confirmDisplayName, entry.shiny),
            centerX, viewTop - confirmScroll, dividerW = dialogW - 156, maxWidth = dialogW - 24
        )
        context.disableScissor()

        // 确认 / 取消按钮
        val btnY = dialogY + dialogH - 28
        val btnW = 80
        val confirmHover = mouseX in (centerX - btnW - 4)..(centerX - 4) && mouseY in btnY..(btnY + 20)
        val cancelHover = mouseX in (centerX + 4)..(centerX + 4 + btnW) && mouseY in btnY..(btnY + 20)
        drawNineSlice(context, BUTTON_TEXTURE, centerX - btnW - 4, btnY, btnW, 20, if (confirmHover) 1 else 0, BUTTON_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("cobblemarket.item.cancel_confirm"), centerX - btnW / 2 - 4, btnY + 6, 0xFFFFFF)
        drawNineSlice(context, BUTTON_TEXTURE, centerX + 4, btnY, btnW, 20, if (cancelHover) 1 else 0, BUTTON_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("cobblemarket.buy_confirm.cancel"), centerX + 4 + btnW / 2, btnY + 6, 0xFFFFFF)
        context.matrices.pop()
    }

    private fun handleConfirmDialogClick(mx: Int, my: Int) {
        val centerX = width / 2
        val dialogH = confirmEntry?.let { confirmDialogH(it) } ?: 240
        val dialogY = height / 2 - dialogH / 2
        val btnY = dialogY + dialogH - 28
        val btnW = 80
        if (my in btnY..(btnY + 20)) {
            if (mx in (centerX - btnW - 4)..(centerX - 4)) {
                playClickSound()
                confirmCancel()
            } else if (mx in (centerX + 4)..(centerX + 4 + btnW)) {
                playClickSound()
                confirmEntry = null
            }
        }
    }

    private fun playClickSound() {
        client?.soundManager?.play(PositionedSoundInstance.master(
            SoundEvent.of(Identifier.of("cobblemarket", "button_click")),
            1.0f
        ))
    }

    private fun isInputFieldFocused() = focused?.let { f ->
        f === searchField || f === hpField || f === atkField || f === defField || f === spaField || f === spdField || f === speField
    } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true ||
        hpField?.isMouseOver(mouseX, mouseY) == true ||
        atkField?.isMouseOver(mouseX, mouseY) == true ||
        defField?.isMouseOver(mouseX, mouseY) == true ||
        spaField?.isMouseOver(mouseX, mouseY) == true ||
        spdField?.isMouseOver(mouseX, mouseY) == true ||
        speField?.isMouseOver(mouseX, mouseY) == true

    /** 确认弹窗开着时：滚轮只滚弹窗信息区（弹窗被屏幕压矮时用） */
    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        // 确认弹窗打开时：滚的是弹窗信息区（照原样，弹窗是模态）
        confirmEntry?.let { entry ->
            val maxScroll = confirmMaxScroll(entry)
            if (maxScroll > 0) {
                confirmScroll = (confirmScroll - verticalAmount.toInt() * 10).coerceIn(0, maxScroll)
            }
            return true
        }
        // 展开列表滚动：滚的是浮层本身，不是下面的列表
        if (filterListOpen.isNotEmpty()) {
            // ⚠ 上限必须与 rebuildFilterList 里的 maxRows 同一算法（小屏时实际显示行数会少于 8）
            val listTop = (filterListSourceButton()?.y ?: 0) + 22
            val maxRows = minOf(MAX_FILTER_LIST_ROWS, maxOf(1, (height - 32 - listTop) / FILTER_LIST_ROW_H))
            val total = filterListOptions().size
            if (total > maxRows) {
                filterListScroll = (filterListScroll - verticalAmount.toInt()).coerceIn(0, total - maxRows)
                rebuildFilterList()
            }
            return true
        }
        // 面板内滚动（内容比面板高时才有用）：鼠标落在面板上才滚，否则交给上层
        if (filterExpanded && filterPanelHeight() < FILTER_PANEL_HEIGHT &&
            mouseX >= MarketPanelBackground.filterLeft(width) &&
            mouseX < MarketPanelBackground.filterLeft(width) + MarketPanelBackground.filterWidth(width)
        ) {
            filterPanelScroll = (filterPanelScroll - verticalAmount.toInt() * 10)
                .coerceIn(0, FILTER_PANEL_HEIGHT - filterPanelHeight())
            updateControlVisibility()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 图标点击叫声：弹窗图标精确命中；**弹窗打开时不做整行触发** —— 整行判定只看 y，
        // 弹窗里精灵图标与输入框的 y 挨得近，会误把点输入框当成点图标
        // 先按图标矩形精确命中（弹窗里的图标照样能点），再考虑整行 —— 整行判定只看 y，
        // 弹窗里精灵图标与输入框挨得近，会误把点输入框当成点图标，所以弹窗时不整行触发
        if (button == 0 && !IconCryClick.playIconAt(mouseX, mouseY) && confirmEntry == null) {
            val rowL = width / 2 - panelWidth / 2
            IconCryClick.playRowAt(mouseX, mouseY, rowL, rowL + panelWidth - 42)
        }
        if (confirmEntry != null) {
            handleConfirmDialogClick(mouseX.toInt(), mouseY.toInt())
            return true
        }
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        return result
    }

    override fun shouldPause() = false

    private companion object {
        val SLOT_TEXTURE = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
        val GENDER_ICON_MALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
        val GENDER_ICON_FEMALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
        /** 筛选按钮「未选中」的文字色：**白色**（与精灵市场 / 拍卖场 / 上架选择同一口径） */
        const val UNSET_FILTER_COLOR = 0xFFFFFF
        /** 展开列表最多显示几行（超出用滚轮翻，照精灵市场） */
        const val MAX_FILTER_LIST_ROWS = 8
        /** 展开列表每行高 */
        const val FILTER_LIST_ROW_H = 14
    }
}
