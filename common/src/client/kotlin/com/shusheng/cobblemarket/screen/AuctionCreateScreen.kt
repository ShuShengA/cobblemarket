package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.util.MoveText
import com.shusheng.cobblemarket.util.StatNames
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.client.PointsStateCache
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_SRC
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_TEXTURE
import com.shusheng.cobblemarket.client.CURRENCY_BUTTON_ICON_SIZE
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.CurrencyService

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.shusheng.cobblemarket.client.icon.PreviewStateHolder
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.FilterPanelAnimation
import com.shusheng.cobblemarket.client.heldIconStack
import com.shusheng.cobblemarket.client.heldIconStackForModel
import com.shusheng.cobblemarket.client.icon.ItemHoverPreview
import com.shusheng.cobblemarket.client.icon.PokemonHoverPreview
import com.shusheng.cobblemarket.client.icon.PokemonIconData
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.network.CreateItemAuctionPayload
import com.shusheng.cobblemarket.network.CreatePokemonAuctionPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.MyPokemonListPayload
import com.shusheng.cobblemarket.network.PokemonPreview
import com.shusheng.cobblemarket.network.RequestBalancePayload
import com.shusheng.cobblemarket.network.RequestMyPokemonPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import com.shusheng.cobblemarket.client.drawItemWithBar

/**
 * 上架拍卖：精灵 / 物品 双 tab。
 * 精灵列表照搬 SellSelectScreen（服务端分页拉取），物品列表照搬 ItemSellScreen（本地背包扫描）。
 * 点击行打开拍卖设置弹窗（起拍价 / 加价幅度（可空=默认）/ 时长档位）。
 */
class AuctionCreateScreen(private val initialTab: Int = 0) : Screen(Text.translatable("cobblemarket.auction.create_title")) {

    private val panelWidth = 296
    private val rowHeight = 24

    // 时长档位（分钟制）：进入界面时向服务端请求真实配置，收到前用默认值占位
    private var durationOptions = listOf(720, 1440, 2880, 4320)

    private var currentTab = initialTab.coerceIn(0, 1) // 0 = 精灵, 1 = 物品
    private var backButton: NineSliceButton? = null
    private val tabButtons = mutableListOf<NineSliceButton>()
    private var shinyButton: NineSliceButton? = null
    private var genderButton: NineSliceButton? = null
    private var typeButton: NineSliceButton? = null
    private var abilityButton: NineSliceButton? = null
    private var natureButton: NineSliceButton? = null
    private var resetButton: NineSliceButton? = null

    // ── 精灵 tab（照搬 SellSelectScreen） ──
    private var pokemonList = listOf<PokemonPreview>()
    private var loaded = false
    private var loadedAll = false
    private var closed = false
    // 防串扰：秒关再开界面时，旧界面的迟到响应会被 requestId 比对丢弃（独立计数器）
    private val requestId = auctionRequestId++
    private var scrollOffset = 0
    private var hoveredRow = -1
    private var searchField: TextFieldWidget? = null
    private var shinyOnly = false
    private var typeFilter = ""
    private var typeIdx = 0
    /** 特性筛选（翻译 key，精确匹配）；选项跟着搜索框里的物种走，见 [updateAbilityOptions] */
    private var abilityFilter = ""
    private var abilityOptions = listOf<Pair<String, String>>()
    /** 性格筛选（翻译 key，按**生效**性格匹配，薄荷后为薄荷性格） */
    private var natureFilter = ""
    /** 公母筛选（照上架选择界面）：""=不限 / MALE / FEMALE */
    private var genderFilter = ""
    // 面板内的展开列表（照精灵市场：不限 + 19 属性 / 特性 / 性格，限高滚动、盖住谁藏谁）
    /** 当前展开的是哪一个："" / "type" / "ability" / "nature"（互斥展开） */
    private var filterListOpen = ""
    private var filterListScroll = 0
    /** 展开列表的实际底边 y（每行 14，从被点按钮的正下方起）。面板里被它盖住的按钮要 `visible = false` */
    private var filterListBottomY = 0
    private val filterOptionButtons = mutableListOf<NineSliceButton>()
    private val ivExact = IntArray(6) { -1 }
    // IV 比较方式：0 = 等于（默认），1 = 大于等于，2 = 小于等于
    private val ivOps = IntArray(6)
    private val ivOpButtons = mutableMapOf<Int, NineSliceButton>()
    /** 每个 IV 输入框右侧的单框重置 ↺（只清这一项，不必去点总重置把六项全清掉） */
    private val ivResetButtons = mutableListOf<NineSliceButton>()
    // 特训筛选三态：0 = 不限，1 = 仅含训练，2 = 仅不含训练
    private var htFilter = 0

    // [PC] 图标：Cobblemon 电脑物品栈（lazy 缓存，避免每帧查注册表）
    private val pcItemStack: ItemStack by lazy { ItemStack(Registries.ITEM.get(Identifier.of("cobblemon", "pc"))) }
    private var htButton: NineSliceButton? = null
    private var filterToggleButton: NineSliceButton? = null

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
     * 布局：闪光+性别+↺ 一行 → 属性 / 特性 / 性格 / 特训 各一行 → IV 六行（每行 22）。
     * 最后一行 `Spd` 输入框在 y=240、高 16（底 256），加 12 底部余量 ⇒ 268。
     * ⚠ 面板里再加一行时记得同步改这里。
     */
    private val FILTER_PANEL_HEIGHT = 268

    /**
     * 面板的**实际高度** = 内容高度与屏幕可用高度的较小值。
     *
     * ⚠ GUI 缩放设「自动」时逻辑屏高会变小（1080p + 缩放 4 只有 270），写死 268 会让面板戳出屏幕、
     *   底部的 IV 输入框看不见 —— 超出部分靠**面板内滚动**看（照精灵市场）。
     */
    private fun filterPanelHeight() = minOf(FILTER_PANEL_HEIGHT, height - 34)
    private var hpF: TextFieldWidget? = null; private var atkF: TextFieldWidget? = null; private var defF: TextFieldWidget? = null
    private var spaF: TextFieldWidget? = null; private var spdF: TextFieldWidget? = null; private var speF: TextFieldWidget? = null

    /** 筛选按钮「未选中」的文字色：**白色**（与精灵市场 / 拍卖场 / 上架选择同一口径，2026-09-24） */
    private val UNSET_FILTER_COLOR = 0xFFFFFF

    private val allTypes = listOf("" to "") + listOf("normal","fire","water","electric","grass","ice","fighting","poison","ground","flying",
        "psychic","bug","rock","ghost","dragon","dark","steel","fairy").map { it to "cobblemon.type.$it" }

    // ── 展开列表（照精灵市场：不限 + 选项、限高滚动、点选后收起） ──

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

    /** 当前展开列表是被哪枚按钮点开的 —— 浮层贴在它正下方（位置只在这里算一次，别人别重写一遍） */
    private fun filterListSourceButton(): NineSliceButton? = when (filterListOpen) {
        "type" -> typeButton
        "ability" -> abilityButton
        "nature" -> natureButton
        else -> null
    }

    private fun rebuildFilterList() {
        // 先撤掉旧的选项按钮：展开/收起来回切、以及界面重建（clearChildren）后都要从干净状态重建
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
        // 浮层铺好后，被它盖住的面板按钮藏起来（读各自的 y，见 applyFilterVisibility）
        applyFilterVisibility()
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
                typeIdx = allTypes.indexOfFirst { it.second == key }.coerceAtLeast(0)
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
        // 筛选变化只重建过滤缓存 + 回到顶部，**不重建界面**（重建会清空精灵图标的跨帧动画状态）
        rebuildFiltered()
        scrollOffset = 0
    }

    // ── 筛选按钮文字（「标签 + 值」，与精灵市场同一口径） ──

    private fun filterPanelBtnW() = MarketPanelBackground.filterWidth(width) - 8

    private fun typeButtonText(): Text = filterButtonText("cobblemarket.gui.type",
        if (typeFilter.isEmpty()) Text.translatable("cobblemarket.gui.filter_any") else Text.translatable(typeFilter))

    private fun abilityButtonText(): Text = filterButtonText("cobblemarket.buy_order.ability_label",
        if (abilityFilter.isEmpty()) Text.translatable("cobblemarket.gui.filter_any") else Text.translatable(abilityFilter))

    private fun natureButtonText(): Text = filterButtonText("cobblemarket.buy_order.nature_label",
        if (natureFilter.isEmpty()) Text.translatable("cobblemarket.gui.filter_any") else Text.translatable(natureFilter))

    /**
     * 筛选按钮文字 =「标签 + 值」（「特性：静电」/「特性：任意」）—— **与精灵市场同一口径**。
     * ⚠ 值超宽时按按钮宽截断（两侧各留 4px）。
     */
    private fun filterButtonText(labelKey: String, value: Text): Text {
        val prefix = Text.translatable(labelKey)
        return prefix.append(Text.literal(com.shusheng.cobblemarket.util.TextUtil.truncateString(
            value.string, filterPanelBtnW() - 8 - textRenderer.getWidth(prefix))))
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
            abilityButton?.setMessage(abilityButtonText())
            abilityButton?.textColor = UNSET_FILTER_COLOR
        }
    }

    private fun updateGenderButton() {
        genderButton?.iconLeft = if (genderFilter == "FEMALE") GENDER_ICON_FEMALE else GENDER_ICON_MALE
        // 不限态显示双图标（♂♀），单态只显示一个
        genderButton?.iconLeft2 = if (genderFilter.isEmpty()) GENDER_ICON_FEMALE else null
    }

    private fun cycleGender() {
        genderFilter = when (genderFilter) {
            "" -> "MALE"
            "MALE" -> "FEMALE"
            else -> ""
        }
        // 原来靠 `rebuild()` 重建按钮来刷新图标；现在筛选不重建界面，得手动更一次
        updateGenderButton()
        rebuildFiltered()
        scrollOffset = 0
    }

    private val iconData = mutableMapOf<Int, PokemonIconData>()

    private val speciesNameCache = mutableMapOf<String, String>()
    private fun speciesDisplay(p: PokemonPreview): String =
        speciesNameCache.getOrPut(p.species) {
            val t = Text.translatable(p.species).string
            if (t == p.species) p.speciesName else t
        }

    private fun buildIconCache(startIdx: Int = 0) {
        if (startIdx == 0) iconData.clear()
        for (i in startIdx until pokemonList.size) {
            val p = pokemonList[i]
            val id = Identifier.tryParse(p.speciesId) ?: continue
            val species = PokemonSpecies.getByIdentifier(id) ?: continue
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
    // ── 物品 tab（照搬 ItemSellScreen 本地背包扫描） ──
    private data class SellItem(val stack: ItemStack, val count: Int)
    private var items = listOf<SellItem>()

    private fun loadInventory() {
        val player = client?.player ?: return
        val list = mutableListOf<SellItem>()
        val main = player.inventory.main
        for (i in 0 until main.size) {
            val stack = main[i]
            if (!stack.isEmpty) {
                val idx = list.indexOfFirst { ItemStack.areItemsAndComponentsEqual(it.stack, stack) }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(count = list[idx].count + stack.count)
                } else {
                    list.add(SellItem(stack.copy(), stack.count))
                }
            }
        }
        items = list
    }

    private fun itemDisplay(stack: ItemStack): String {
        val id = Registries.ITEM.getId(stack.item)
        val name = stack.name.string
        return if (name == stack.item.translationKey) id.path else name
    }

    // ── 拍卖设置弹窗 ──
    private var dialogPokemon: PokemonPreview? = null
    private var dialogItem: SellItem? = null
    private var startingField: TextFieldWidget? = null
    private var incrementField: TextFieldWidget? = null

    /** 服务端真实的最低加价默认值（`onDurations` 到达时缓存）——上架弹窗建输入框时补设占位符要用它 */
    private var serverMinIncrement = 0
    private var countField: TextFieldWidget? = null
    private var durationIndex = 0
    private var durationButton: NineSliceButton? = null

    /** 这场拍卖收哪种币（每次开弹窗重置为主货币）；按钮没建时（RP 总开关关）恒为主货币 */
    private var currency = MarketCurrency.PRIMARY

    /** 货币切换按钮（起拍价输入框右侧）：RP 总开关关着时为 null = 压根没建 */
    private var currencyButton: NineSliceButton? = null
    private var confirmButton: NineSliceButton? = null
    private var cancelButton: NineSliceButton? = null
    private var dialogRenderable: RenderablePokemon? = null
    private val dialogPreviewState = PreviewStateHolder()
    /** 弹窗自动叫声的时刻（0 = 无）：打开时排、渲染后到点播，见 renderPokemonSlot 里的判断 */
    private var dialogCryAt = 0L

    // 精灵 tab：搜索框那一行（y=50、高 20、底 70）⇒ 分割线 74、列表 78
    //   （筛选控件搬进右侧面板前是 114 —— 主面板上压着 IV 行 + 筛选按钮行两排）
    // 物品 tab 没有搜索框也没有筛选，tab 行下面直接接列表
    private fun getListStartY() = if (currentTab == 0) 78 else 54
    private fun getMaxVisibleRows() = maxOf(0, (height - getListStartY() - 48) / rowHeight)

    override fun init() {
        super.init()
        sendToServer(RequestBalancePayload())
        // 请求服务端真实时长档位（配置可自定义，按钮显示跟随）
        sendToServer(com.shusheng.cobblemarket.network.RequestAuctionDurationsPayload())
        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        val backBtn = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(AuctionScreen(currentTab)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        backButton = backBtn
        addDrawableChild(backBtn)

        // tab 按钮
        val tabW = 62
        val tabStart = centerX - (tabW * 2 + 2) / 2
        tabButtons.clear()
        listOf("cobblemarket.auction.tab_pokemon", "cobblemarket.auction.tab_item").forEachIndexed { i, _ ->
            val btn = NineSliceButton(tabStart + i * (tabW + 2), 32, tabW, 14, Text.literal(""), { switchTab(i) })
            tabButtons.add(btn)
            addDrawableChild(btn)
        }
        updateTabButtons()

        if (currentTab == 0) {
            // Row 1 (y=50)：搜索框 + 行右端的「筛选」开关（开关右边那块筛选面板）。
            // 搜索框高 20 = 同行按钮高（原版输入框文字 y = y+(h-8)/2 与 NineSliceButton 同一算式，
            // 矮一截会看着偏上、发细）
            searchField = TextFieldWidget(textRenderer, leftX + 2, 50, panelWidth - 58, 20, Text.translatable("cobblemarket.sell.search"))
            searchField?.setPlaceholder(Text.translatable("cobblemarket.sell.search"))
            // 搜索变化时重建过滤缓存（render 每帧只读，见 rebuildFiltered 注释）
            searchField?.setChangedListener { updateAbilityOptions(it); rebuildFiltered() }
            addSelectableChild(searchField)
            addDrawableChild(searchField)

            val filterToggle = NineSliceButton(
                leftX + panelWidth - 52, 50, 50, 20,
                Text.literal(""),
                { toggleFilters() },
                iconTexW = 48, iconTexH = 48, iconScale = 0.25f
            )
            filterToggleButton = filterToggle
            addDrawableChild(filterToggle)
            updateFilterToggleVisual()

            // ── 右侧筛选面板内的控件：**常驻创建**，开关只切可见性（别用 `if (filterExpanded)` 包起来
            //    —— 那会让开关走"重建界面"的老路，而重建会清空精灵图标的跨帧动画状态）──
            // 搬过来的理由与精灵市场一样：原先它们在主面板 y=70/y=90 两排，属性点开是**浮层**、
            // 盖住下面的列表行 ⇒ 要维护四类补丁。现在浮层只盖同一块面板里的几个按钮，补丁整个删掉；
            // 精灵 tab 的列表起点也从 114 回到 78。
            val fx = MarketPanelBackground.filterLeft(width) + 4
            val fw = MarketPanelBackground.filterWidth(width) - 8

            // 第一行：★闪光(30) + ♂公母(24) + ↺重置（贴面板右端）—— 与精灵市场同尺寸、同顺序
            // （精灵市场那一行是「闪光 | 性别 | 排序」，本界面没有排序，把 ↺ 补在空出来的右端）
            shinyButton = NineSliceButton(
                fx, 20, 30, 20,
                Text.literal(if (shinyOnly) "★" else "☆"),
                { toggleShiny() },
                // 开 = 金色 ★，关 = 白色 ☆（与其他界面闪光按钮一致）
                if (shinyOnly) GOLD_COLOR else UNSET_FILTER_COLOR
            )
            addDrawableChild(shinyButton)
            // 公母（照上架选择界面：不限态 ♂♀ 双图标，循环 不限→仅公→仅母）
            genderButton = NineSliceButton(
                fx + 34, 20, 24, 20,
                Text.literal(""),
                { cycleGender() },
                iconTexW = 6, iconTexH = 8, iconScale = 1.5f
            )
            updateGenderButton()
            addDrawableChild(genderButton)
            // 重置：↺ **符号**（与精灵市场 / 管理端 / 上架选择同一个观感；纯图标按钮给悬停提示）
            resetButton = NineSliceButton(
                fx + fw - 20, 20, 20, 20,
                Text.literal("↺"),
                { resetFilters() },
                tooltip = Text.translatable("cobblemarket.gui.reset")
            )
            addDrawableChild(resetButton)

            // 属性 / 特性 / 性格 / 特训：各占整行，展开浮层贴在各自按钮的正下方
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
            // 特训筛选（三态循环）
            htButton = NineSliceButton(
                fx, 108, fw, 20,
                htButtonText(),
                { toggleHtFilter() },
                if (htFilter != 0) GOLD_COLOR else UNSET_FILTER_COLOR
            )
            addDrawableChild(htButton)

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
            hpF  = mkIv(fx, 130, "HP");  addIvOpButton(fx + 88, 130, 0); addIvResetButton(fx + 132, 130, 0)
            atkF = mkIv(fx, 152, "ATK"); addIvOpButton(fx + 88, 152, 1); addIvResetButton(fx + 132, 152, 1)
            defF = mkIv(fx, 174, "DEF"); addIvOpButton(fx + 88, 174, 2); addIvResetButton(fx + 132, 174, 2)
            spaF = mkIv(fx, 196, "SpA"); addIvOpButton(fx + 88, 196, 3); addIvResetButton(fx + 132, 196, 3)
            spdF = mkIv(fx, 218, "SpD"); addIvOpButton(fx + 88, 218, 4); addIvResetButton(fx + 132, 218, 4)
            speF = mkIv(fx, 240, "Spd"); addIvOpButton(fx + 88, 240, 5); addIvResetButton(fx + 132, 240, 5)

            // 登记面板内控件：记下基准 y，滚动时按它重定位（见 filterPanelControls 的说明）
            filterPanelControls.clear()
            listOfNotNull(
                shinyButton, genderButton, resetButton, typeButton, abilityButton,
                natureButton, htButton, hpF, atkF, defF, spaF, spdF, speF
            ).forEach { filterPanelControls += it to it.y }
            ivOpButtons.values.forEach { filterPanelControls += it to it.y }
            ivResetButtons.forEach { filterPanelControls += it to it.y }
            // 建完先刷一遍位置与可见性：面板收起时它们必须立刻藏起来
            applyFilterVisibility()

            if (!loaded) {
                sendToServer(RequestMyPokemonPayload(0, requestId))
                loaded = true
            }
            rebuildFiltered()
        } else {
            loadInventory()
        }
    }

    /** IV 单框重置 ↺：只清这一项的输入框与值（不必去点整行重置把六项全清掉），照精灵市场 */
    private fun addIvResetButton(x: Int, y: Int, index: Int) {
        val btn = NineSliceButton(
            x, y, 20, 16,
            Text.literal("↺"),
            {
                ivExact[index] = -1
                ivOps[index] = 0
                listOf(hpF, atkF, defF, spaF, spdF, speF)[index]?.text = ""
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

    /** 闪光筛选：开 = 金色 ★，关 = 白色 ☆ */
    private fun toggleShiny() {
        shinyOnly = !shinyOnly
        shinyButton?.setMessage(Text.literal(if (shinyOnly) "★" else "☆"))
        shinyButton?.textColor = if (shinyOnly) GOLD_COLOR else UNSET_FILTER_COLOR
        rebuildFiltered()
        scrollOffset = 0
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
        // 拍卖设置弹窗是模态：打开时面板整块藏掉（原来 openDialog 里逐个列了要藏的控件，
        // 那份名单**漏了公母按钮**；现在统一走这里，不会再漏）
        val dialogOpen = dialogPokemon != null || dialogItem != null
        // ⚠ 动画进行中整块藏起来（面板内是 MC 控件，塞不进矩阵、不会跟着缩放），照精灵市场
        val animHidden = filterAnim.isActive()
        val covered = filterListOpen.isNotEmpty() && filterListBottomY > filterListTopY
        /** 某个 `y` 是否落在展开浮层的覆盖范围内 */
        fun coveredAt(y: Int?) = covered && y != null && y >= filterListTopY && y < filterListBottomY
        fun show(w: NineSliceButton?) {
            if (w != null) w.visible = filterExpanded && !dialogOpen && !coveredAt(w.y) && !animHidden
        }
        show(shinyButton)
        show(genderButton)
        show(resetButton)
        show(typeButton)
        show(abilityButton)
        show(natureButton)
        show(htButton)
        listOf(hpF, atkF, defF, spaF, spdF, speF).forEachIndexed { i, f ->
            val vis = filterExpanded && !dialogOpen && !coveredAt(ivOpButtons[i]?.y) && !animHidden
            f?.visible = vis
            ivOpButtons[i]?.visible = vis
            ivResetButtons.getOrNull(i)?.visible = vis
        }
        filterToggleButton?.visible = !dialogOpen
        // 面板内滚动：按登记的基准 y 重定位，滚出面板范围的控件藏起来（滚轮改了 filterPanelScroll
        // 之后调本函数重排）。⚠ 放在最后 —— 它会覆盖上面 show() 设的 visible
        val panelTop = 4
        val panelBottom = 2 + filterPanelHeight()
        filterPanelControls.forEach { (w, baseY) ->
            w.y = baseY - filterPanelScroll
            if (w.y < panelTop || w.y + w.height > panelBottom) w.visible = false
        }
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

    private fun updateTabButtons() {
        val keys = listOf("cobblemarket.auction.tab_pokemon", "cobblemarket.auction.tab_item")
        tabButtons.forEachIndexed { i, btn ->
            val label = Text.translatable(keys[i]).string
            btn.setMessage(if (currentTab == i) com.shusheng.cobblemarket.util.TextUtil.selectedText(label) else Text.literal(label))
        }
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        scrollOffset = 0
        hoveredRow = -1
        // 切 tab 前收起属性展开列表：浮层按钮会被 clearChildren 清掉，状态若留着，
        // applyFilterVisibility 会继续按残留的范围隐藏面板按钮（浮层却已经不在了）
        filterListOpen = ""
        filterListScroll = 0
        filterListTopY = 0
        filterListBottomY = 0
        clearChildren()
        init()
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

    // （原 `rebuild(keepInputs)` = clearChildren + init + 恢复输入，2026-09-24 删除：筛选控件搬进右侧
    //   面板后它就没有调用点了 —— 筛选变化一律改成 rebuildFiltered()，不再推倒重建界面。
    //   ⚠ switchTab 仍走它自己的 clearChildren + init：切 tab 后列表整体换掉，本就该重建）

    /**
     * 重置全部筛选条件（面板右端那枚）。**不重建界面**，逐个还原各控件的文字与颜色
     * —— 走重建的话会把精灵图标的跨帧动画一起清掉（与"开关面板"同一个坑）。
     * ⚠ 不清搜索框：重置的是**筛选条件**，搜索词是玩家自己敲的、保留更符合预期（同拍卖场）。
     */
    private fun resetFilters() {
        shinyOnly = false
        shinyButton?.setMessage(Text.literal("☆"))
        shinyButton?.textColor = UNSET_FILTER_COLOR
        genderFilter = ""
        updateGenderButton()
        typeFilter = ""
        typeIdx = 0
        typeButton?.setMessage(typeButtonText())
        typeButton?.textColor = UNSET_FILTER_COLOR
        abilityFilter = ""
        abilityButton?.setMessage(abilityButtonText())
        abilityButton?.textColor = UNSET_FILTER_COLOR
        natureFilter = ""
        natureButton?.setMessage(natureButtonText())
        natureButton?.textColor = UNSET_FILTER_COLOR
        htFilter = 0
        htButton?.setMessage(htButtonText())
        htButton?.textColor = UNSET_FILTER_COLOR
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
        hoveredRow = -1
        rebuildFilterList()
        rebuildFiltered()
        applyFilterVisibility()
    }

    fun onPokemonList(payload: MyPokemonListPayload) {
        if (closed || payload.requestId != requestId) return
        if (payload.page == 0) {
            pokemonList = payload.pokemon
            buildIconCache()
        } else {
            val startIdx = pokemonList.size
            pokemonList = pokemonList + payload.pokemon
            buildIconCache(startIdx)
        }
        rebuildFiltered()
        if (payload.hasMore) {
            sendToServer(RequestMyPokemonPayload(payload.page + 1, requestId))
        } else {
            loadedAll = true
        }
    }

    fun onMarketResult(payload: MarketResultPayload) {
        createInFlight = false
        if (payload.success) {
            // 上架成功：回到拍卖场并保持当前 tab（物品上架留在物品 tab）
            client?.setScreen(AuctionScreen(currentTab))
        } else {
            // 创建失败（价格限制/黑名单/数量上限等）：聊天框红字提示，弹窗保留供改价重试
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.RED), false)
        }
    }

    // ── 拍卖设置弹窗 ──

    /** 点货币按钮：「玩家间 RP 交易」没开就只给反馈、不切（置灰态按钮同样走这里） */
    private fun onCurrencyClick() {
        // ⚠ 总开关关着时按钮只是置灰、仍可点：先给「未开启 RP」的提示（两个词条对应两种"不能用"，别混用）
        if (!PointsStateCache.enabled) {
            playFailSound()
            client?.player?.sendMessage(
                Text.translatable("cobblemarket.network.points_disabled", CurrencyService.pointsIcon())
                    .formatted(Formatting.RED),
                false
            )
            return
        }
        if (!PointsStateCache.marketEnabled) {
            playFailSound()
            client?.player?.sendMessage(
                // ⚠ 提醒类消息用红色，和普通反馈区分开（图标自带白色，不受影响）
                Text.translatable("cobblemarket.network.points_market_disabled", CurrencyService.pointsIcon())
                    .formatted(Formatting.RED),
                false
            )
            return
        }
        currency = if (currency == MarketCurrency.POINTS) MarketCurrency.PRIMARY else MarketCurrency.POINTS
        currencyButton?.let { updateCurrencyButton(it) }
    }

    /** 刷新货币按钮外观：主货币显示 `₽`，RP 显示图标（与市场/上架界面同一套口径） */
    private fun updateCurrencyButton(btn: NineSliceButton) {
        val points = currency == MarketCurrency.POINTS
        btn.setMessage(Text.literal(if (points) "" else "₽"))
        btn.iconLeft = if (points) POINTS_SYMBOL_TEXTURE else null
    }

    private fun openDialog(pokemon: PokemonPreview?, item: SellItem?) {
        dialogPokemon = pokemon
        dialogItem = item
        dialogRenderable = null
        dialogCryAt = 0L
        backButton?.visible = false
        tabButtons.forEach { it.visible = false }
        searchField?.visible = false
        // 筛选面板里的控件与「筛选」开关一并藏掉（统一走 applyFilterVisibility，别在这儿列名单）
        applyFilterVisibility()
        val centerX = width / 2
        val dialogY = height / 2 - (if (item != null) 180 else 150) / 2

        if (pokemon != null) {
            val id = Identifier.tryParse(pokemon.speciesId)
            val species = id?.let { PokemonSpecies.getByIdentifier(it) }
            if (species != null) {
                val aspects = pokemon.aspects.toMutableSet()
                if (pokemon.shiny && "shiny" !in aspects) aspects.add("shiny")
                dialogRenderable = RenderablePokemon(species, aspects, heldIconStackForModel(pokemon.heldItemVisible, pokemon.heldItemId, pokemon.heldItemNbt))
            }
        }
        // 点行 → 弹窗弹出 → 由弹窗里的精灵叫一声（用户拍板的「叫声转移」）。
        // ⚠ 这里只**排一个时刻**，真正的播放在渲染之后（见 renderPokemonSlot）：
        // `addFirstAnimation` 依赖的 `currentModel` 是 `drawProfilePokemon` 里才建好的，
        // 打开时立刻播会空放 —— 表现为「第一次打开没声音，开过一次之后才叫」
        // （其余 5 个界面 2026-09-16 已统一改成这个写法，这里是漏网的最后一处）
        if (dialogRenderable != null) {
            dialogCryAt = System.currentTimeMillis() + IconCryClick.AUTO_CRY_DELAY_MS
        }

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderDialogBackground(context)
            }
        })

        startingField = TextFieldWidget(textRenderer, centerX - 20, dialogY + 52, 100, 16, Text.literal(""))
        startingField?.setPlaceholder(Text.translatable("cobblemarket.auction.starting_placeholder").formatted(Formatting.GRAY))
        startingField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addDrawableChild(startingField)

        // 货币切换按钮（起拍价输入框右侧 4px）：与市场/上架界面同一个按钮、同一套三层拦截。
        // ⚠ RP 总开关关着 → **整个按钮不建**（那时 RP 系统停摆，留个点不动的灰按钮更难看）；
        //   「玩家间 RP 交易」没开 → 建但置灰，点了给失败音 + 红字提示
        currency = MarketCurrency.PRIMARY
        // ⚠ **总开关关着时也建**（只是置灰）：按钮的位置是排好的，凭空消失会空出一块。
        //   （用户 2026-09-24 定：创建类界面的货币按钮统一「建 + 置灰」。查看类界面本来就放行、不置灰）
        currencyButton = NineSliceButton(
            centerX + 84, dialogY + 52, 20, 16, Text.literal(""), { onCurrencyClick() },
            iconTexW = POINTS_SYMBOL_SRC, iconTexH = POINTS_SYMBOL_SRC,
            iconScale = CURRENCY_BUTTON_ICON_SIZE / POINTS_SYMBOL_SRC.toFloat(),
            tooltip = Text.translatable("cobblemarket.gui.currency_switch", inlineCurrencyUnit(), CurrencyService.pointsIcon())
        ).also {
            // 两种"不能用"都置灰：RP 总开关关 / 玩家间 RP 交易没开
            it.dimmed = !PointsStateCache.enabled || !PointsStateCache.marketEnabled
            updateCurrencyButton(it)
            addDrawableChild(it)
        }

        incrementField = TextFieldWidget(textRenderer, centerX - 20, dialogY + 72, 100, 16, Text.literal(""))
        // 先铺兜底文案「留空=默认」，再用已缓存的服务器值覆盖 ——
        // ⚠ 那个包多半**比这个弹窗先到**（init 里就发了请求），onDurations 只是缓存了值，
        //   它执行时 incrementField 还不存在，所以占位符必须在这里补设（2026-09-15 修的时序 bug）
        incrementField?.setPlaceholder(
            Text.translatable("cobblemarket.auction.increment_placeholder",
                Text.translatable("cobblemarket.auction.increment_default")).formatted(Formatting.GRAY))
        applyIncrementPlaceholder()
        // 再请求一次：上架界面一直开着时配置可能被管理员改过，重开弹窗应当看到新值
        sendToServer(com.shusheng.cobblemarket.network.RequestAuctionDurationsPayload())
        incrementField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addDrawableChild(incrementField)

        durationButton = NineSliceButton(centerX - 20, dialogY + 92, 100, 14, Text.literal(""), { cycleDuration() })
        addDrawableChild(durationButton)
        updateDurationButton()

        if (item != null) {
            countField = TextFieldWidget(textRenderer, centerX - 20, dialogY + 112, 100, 16, Text.literal(""))
            countField?.setPlaceholder(Text.translatable("cobblemarket.auction.count_placeholder", item.count).formatted(Formatting.GRAY))
            countField?.setTextPredicate { it.length <= 4 && it.all { c -> c.isDigit() } }
            addDrawableChild(countField)
        }

        val btnY = if (item != null) dialogY + 134 else dialogY + 112
        confirmButton = NineSliceButton(
            centerX - 85, btnY, 80, 20,
            Text.translatable("cobblemarket.auction.create"),
            { confirmCreate() }
        )
        addDrawableChild(confirmButton)
        cancelButton = NineSliceButton(
            centerX + 5, btnY, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeDialog() }
        )
        addDrawableChild(cancelButton)
    }

    // 弹窗打开时：预览精灵画在起拍价输入框上方，以输入框水平居中
    private fun renderPreviewAboveField(context: DrawContext, delta: Float) {
        val slotSize = 28
        val dialogY = height / 2 - (if (dialogItem != null) 180 else 150) / 2
        val fieldX = width / 2 - 20
        val fieldW = 100
        val slotX = fieldX + (fieldW - slotSize) / 2
        val slotY = dialogY + 52 - slotSize - 4
        val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
        context.matrices.push()
        context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
        context.matrices.scale(slotSize / 66f, slotSize / 66f, 1f)
        context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
        context.matrices.pop()
        val rp = dialogRenderable ?: return
        // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
        val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
            com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
        try {
            PokemonIconRenderer.draw(
                context = context,
                renderable = rp,
                state = dialogPreviewState.stateFor(rp),
                x = slotX, y = slotY, size = slotSize,
                delta = delta,
                animate = useFloat
            )
        } catch (_: Exception) {
        }
        // 弹窗打开后的自动叫声：**必须画完再播**（currentModel 刚建好），再叠一层延迟让玩家看清弹窗
        if (dialogCryAt != 0L && System.currentTimeMillis() >= dialogCryAt) {
            dialogCryAt = 0L
            IconCryClick.play(dialogPreviewState.stateFor(rp), rp)
        }
    }

    private fun renderDialogBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogH = if (dialogItem != null) 180 else 150
        val dialogW = minOf(300, width - 40).coerceAtLeast(200)
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.auction.create_dialog_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        // 预览槽
        val slotSize = 28
        val slotX = centerX + 66
        val slotY = dialogY + 24
        if (dialogItem != null) {
            drawItemWithBar(context, dialogItem!!.stack, slotX + 6, slotY + 6)
        }

        // 字段标签
        val labelX = dialogX + 10
        var ly = dialogY + 56
        fun label(text: Text) {
            context.drawTextWithShadow(textRenderer, text.string, labelX, ly, 0xAAAAAA)
            ly += 20
        }
        label(Text.translatable("cobblemarket.auction.starting_price"))
        label(Text.translatable("cobblemarket.auction.min_increment"))
        ly -= 2
        label(Text.translatable("cobblemarket.auction.duration"))
        if (dialogItem != null) {
            label(Text.translatable("cobblemarket.auction.count"))
            // 物品拍卖：起拍价为整组总价提示
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.auction.price_total_hint").formatted(Formatting.GRAY),
                centerX, dialogY + 158, 0xFFFFFF)
        }
    }

    private fun formatDuration(minutes: Int): String =
        if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"

    /**
     * 按缓存的服务器默认值刷新最低加价输入框的占位符。
     *
     * 没拿到值（或配置为 0）时什么都不做，保留初始的「留空=默认」兜底文案。
     * 单独抽出来是因为有两个调用时机：包到达时、以及上架弹窗建好输入框时（见 [openDialog]）。
     */
    private fun applyIncrementPlaceholder() {
        if (serverMinIncrement <= 0) return
        incrementField?.setPlaceholder(
            Text.translatable("cobblemarket.auction.increment_placeholder",
                "${com.shusheng.cobblemarket.client.formatPrice(serverMinIncrement)} ${com.shusheng.cobblemarket.client.inlineCurrencyUnit()}").formatted(Formatting.GRAY))
    }

    /** 服务端真实时长档位到达：更新选项与按钮显示（服务器自定义配置时按钮不再失真） */
    fun onDurations(payload: com.shusheng.cobblemarket.network.AuctionDurationsPayload) {
        if (closed) return
        // 默认最低加价：占位符显示服务器真实配置值（原先写死「留空=默认」，玩家看不到具体数额）。
        // **先缓存再刷新** —— 这个包常在上架弹窗打开之前就到了，那时 incrementField 还是 null，
        // 只 setPlaceholder 会静默空过（2026-09-15 修的时序 bug）
        serverMinIncrement = payload.minIncrement
        applyIncrementPlaceholder()
        if (payload.durations.isEmpty()) return
        durationOptions = payload.durations
        durationIndex = durationIndex.coerceIn(0, durationOptions.size - 1)
        updateDurationButton()
    }

    private fun updateDurationButton() {
        durationButton?.setMessage(Text.literal(
            "${Text.translatable("cobblemarket.auction.duration_label").string}${formatDuration(durationOptions[durationIndex])}"
        ))
    }

    private fun cycleDuration() {
        durationIndex = (durationIndex + 1) % durationOptions.size
        updateDurationButton()
    }

    /** 创建请求在途标志：连点防重（onMarketResult 响应时复位） */
    private var createInFlight = false

    private fun confirmCreate() {
        // 防重：请求在途时忽略连点（服务端有 UUID 校验+节流兜底，这里防误发与响应混杂）
        if (createInFlight) return
        val starting = startingField?.text?.toIntOrNull() ?: run {
            playFailSound()
            return
        }
        if (starting <= 0) { playFailSound(); return }
        val increment = incrementField?.text?.toIntOrNull() ?: 0 // 0 = 用服务器默认
        if (increment < 0) { playFailSound(); return }
        val durationIndexToSend = durationIndex
        val pokemon = dialogPokemon
        val item = dialogItem
        // ⚠ 提交前**再查一次实时缓存**（不是开弹窗时的快照）：服主可能在弹窗开着时把
        //   「玩家间 RP 交易」关掉，那这一次必须拦下并切回主货币 —— 服务端也会拒，
        //   这里是为了给个明确提示，而不是让玩家对着一笔静默失败的拍卖发懵
        if (currency == MarketCurrency.POINTS && !PointsStateCache.marketEnabled) {
            currency = MarketCurrency.PRIMARY
            currencyButton?.let { updateCurrencyButton(it) }
            client?.player?.sendMessage(
                Text.translatable("cobblemarket.network.points_market_disabled", CurrencyService.pointsIcon())
                    .formatted(Formatting.RED),
                false
            )
            playFailSound()
            return
        }
        if (pokemon != null) {
            createInFlight = true
            sendToServer(CreatePokemonAuctionPayload(pokemon.uuid, starting, increment, durationIndexToSend, currency.name))
        } else if (item != null) {
            val count = countField?.text?.toIntOrNull() ?: run {
                playFailSound()
                return
            }
            if (count <= 0 || count > item.count) { playFailSound(); return }
            val registry = client?.world?.registryManager ?: run {
                playFailSound()
                return
            }
            val itemId = Registries.ITEM.getId(item.stack.item).toString()
            val itemNbt = item.stack.encode(registry, NbtCompound()) as? NbtCompound ?: NbtCompound()
            createInFlight = true
            sendToServer(CreateItemAuctionPayload(itemId, itemNbt, count, starting, increment, durationIndexToSend, currency.name))
        }
        // 不立即关闭：等 onMarketResult 响应（成功跳转拍卖场，失败保留弹窗供改价重试）
    }

    private fun closeDialog() {
        dialogPokemon = null
        dialogItem = null
        startingField = null
        incrementField = null
        countField = null
        durationIndex = 0
        durationButton = null
        currencyButton = null
        confirmButton = null
        cancelButton = null
        dialogRenderable = null
        dialogCryAt = 0L
        clearChildren()
        init()
    }

    // ── 列表渲染 ──

    // 过滤结果缓存（照黑名单界面的 rebuildFiltered 模式）：数据/搜索/IV/筛选变化时重建，
    // render/renderBackground 每帧只读。indexedFilteredCache 同缓存带原始下标，省去行循环里每帧 indexOf。
    private var filteredCache = listOf<PokemonPreview>()
    private var indexedFilteredCache = listOf<IndexedValue<PokemonPreview>>()

    private fun filteredPokemon(): List<PokemonPreview> = filteredCache

    private fun rebuildFiltered() {
        syncIvFields()
        // 先 withIndex 再过滤：IndexedValue.index 保持 pokemonList 原始下标，
        // 供图标缓存 iconData（按原列表索引构建）正确取值（filteredCache.withIndex() 的序号是过滤后位置，搜索后首行图标会错位）
        indexedFilteredCache = pokemonList.withIndex().filter { (_, p) ->
            val q = searchField?.text?.trim()?.takeIf { it.isNotEmpty() }
            (q == null || speciesDisplay(p).contains(q, ignoreCase = true) || p.speciesName.contains(q, ignoreCase = true)) &&
            (!shinyOnly || p.shiny) &&
            (genderFilter.isEmpty() || p.gender == genderFilter) &&
            (typeFilter.isEmpty() || p.primaryType == typeFilter || p.secondaryType == typeFilter) &&
            // 特性精确匹配（翻译 key）；性格按**生效**性格匹配（薄荷改成该性格也算），照精灵市场
            (abilityFilter.isEmpty() || p.ability == abilityFilter) &&
            (natureFilter.isEmpty() || p.nature == natureFilter) &&
            // IV 按有效值匹配：特训项用特训值，未特训用真实值（原生 31 与训练 31 都命中）
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
        htButton?.setMessage(htButtonText())
        htButton?.textColor = if (htFilter != 0) GOLD_COLOR else UNSET_FILTER_COLOR
        rebuildFiltered()
        scrollOffset = 0
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

    private fun typeColor(tk: String) = when (tk.substringAfterLast(".").lowercase()) {
        "normal" -> 0xAAAA99; "fire" -> 0xFF4422; "water" -> 0x3399FF
        "electric" -> 0xFFCC33; "grass" -> 0x77CC55; "ice" -> 0x66CCFF
        "fighting" -> 0xBB5544; "poison" -> 0xAA5599; "ground" -> 0xDDBB55
        "flying" -> 0x8899FF; "psychic" -> 0xFF5599; "bug" -> 0xAABB22
        "rock" -> 0xBBAA66; "ghost" -> 0x6666BB; "dragon" -> 0x7766EE
        "dark" -> 0x775544; "steel" -> 0xAAAABB; "fairy" -> 0xFFAAFF
        else -> 0xFFFFFF
    }

    private fun displayCount(): Int = if (currentTab == 0) filteredPokemon().size else items.size

    private fun renderPokemonIcon(
        context: DrawContext, index: Int, x: Int, y: Int, size: Int, delta: Float = 0f,
        /** 本行是否被悬停（跟左侧大图一起走 walk）。**必须由调用方按行索引算**：
         *  早先用 `IconRegistry.isMouseInRow` 只看纵向，鼠标跑到面板外侧、只要 y 平齐也会触发动画（2026-09-15） */
        preview: Boolean = false
    ) {
        val data = iconData[index] ?: return run {
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
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        IconRegistry.beginFrame(mouseX, mouseY)
        // 顶部身份位放拍卖锤图标（复用界面内已有的 48x48 图标，见 MarketPanelBackground）
        MarketPanelBackground.draw(context, width, height, HAMMER_TEX_LEFT)
        // 右侧筛选面板（精灵 tab、面板展开、无弹窗时）：**主面板保持居中不动**，
        // 筛选面板占用它右侧本来就空着的区域。高度由内容定（246），不跟主面板铺到屏幕底边
        // ⚠ 展开中、或收起动画还没播完都要画（后者要从目标位置缩回按钮），照精灵市场
        if (currentTab == 0 && dialogPokemon == null && dialogItem == null
            && (filterExpanded || filterAnim.isActive())) {
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

        // IV 输入变化时重建过滤缓存（原 filteredPokemon 每次调用都先 syncIvFields，缓存后在此统一检测；
        // renderBackground 每帧先于 render 运行，检测一次即可覆盖本帧全部读缓存点）
        if (syncIvFields()) rebuildFiltered()

        val leftX = width / 2 - panelWidth / 2
        val startY = getListStartY()
        val visibleRows = getMaxVisibleRows()
        val listAreaBottom = startY + visibleRows * rowHeight
        val count = displayCount()

        // 弹窗打开时不再绘制行背景/悬停行（下层列表对弹窗不可见）。
        // ⚠ 属性浮层已搬进右侧面板、盖不到列表 ⇒ 悬停判定与逐行渲染的遮挡判断全部取消
        //   （原先那套：浮层展开时不算悬停、被盖住的行不画背景）
        if (dialogPokemon == null && dialogItem == null) {
            hoveredRow = -1
            if (mouseX in leftX..(leftX + panelWidth) && mouseY in startY..listAreaBottom) {
                val row = (mouseY - startY) / rowHeight
                val shownCount = minOf(visibleRows, count - scrollOffset)
                if (row in 0 until shownCount) hoveredRow = row
            }

            repeat(minOf(visibleRows, maxOf(0, count - scrollOffset))) { di ->
                val rowY = startY + di * rowHeight
                val rowState = if (di == hoveredRow) 1 else 0
                drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, rowState, ROW_BACKGROUND_TEX_H)
            }
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // ⚠ **每帧**重算面板控件的显隐（照精灵市场与 `AdminPokemonScreen`）：面板开合走的是**动画**，
        //   而动画播完那一刻**没有任何事件回调** —— 只在 toggle / 滚轮 / 弹窗开关里算的话，
        //   展开动画结束后控件会永远停在「动画期间藏起来」的状态（2026-09-25 用户报）。
        //   必须放在 super.render 之前：下面有弹窗分支的提前 return，放后面会漏
        applyFilterVisibility()
        super.render(context, mouseX, mouseY, delta)

        if (dialogPokemon != null || dialogItem != null) {
            // 弹窗打开时：精灵预览显示在起拍价输入框上方（以输入框居中）
            if (dialogPokemon != null && dialogRenderable != null) {
                renderPreviewAboveField(context, delta)
            }
            return
        }

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        val titleText = Text.translatable("cobblemarket.auction.create_title").formatted(Formatting.GOLD)
        context.drawCenteredTextWithShadow(textRenderer, titleText, centerX, 20, 0xFFFFFF)

        // 余额（左上角，来自全局缓存）；与标题同行，余额一长就压上去，降级规则见 drawHeaderBalance
        drawHeaderBalance(context, textRenderer, centerX, leftX + 4, textRenderer.getWidth(titleText))

        val startY = getListStartY()
        // 分割线在列表起点上方 **4px**（⚠ 别用 -2：那样它离行背景只有 2px，看着"紧贴精灵行"；
        // 上架选择 / 精灵市场 / 管理端都是 4px —— 2026-09-24 用户比对两个界面后指出）
        drawPanelDivider(context, leftX + panelWidth / 2, startY - 4)

        if (currentTab == 0) {
            if (!loaded) {
                context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.sell.loading").formatted(Formatting.GRAY),
                    centerX, startY + 50, 0xFFFFFF)
            } else if (displayCount() == 0) {
                context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.auction.create_empty").formatted(Formatting.GRAY),
                    centerX, startY + 50, 0xFFFFFF)
            }
        } else if (displayCount() == 0) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.auction.create_empty").formatted(Formatting.GRAY),
                centerX, startY + 50, 0xFFFFFF)
        }

        // Tooltip on hover（照搬 SellSelectScreen）
        if (currentTab == 0 && hoveredRow >= 0) {
            val filtered = filteredPokemon()
            val absIdx = scrollOffset + hoveredRow
            if (absIdx in filtered.indices) {
                renderTooltip(context, filtered[absIdx], mouseX, mouseY, 1)
            }
        }
        // 物品行悬停：真实物品词条（BASIC 常驻 / Shift 展开 ADVANCED，Text 保留词条自带颜色，照黑名单）
        if (currentTab == 1 && hoveredRow >= 0) {
            val absIdx = scrollOffset + hoveredRow
            if (absIdx in items.indices) {
                renderItemTooltip(context, items[absIdx], mouseX, mouseY)
            }
        }

        if (currentTab == 0) {
            val displayList = indexedFilteredCache
            displayList.drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, (origIndex, p) ->
                val y = startY + i * rowHeight
                // 槽背景（GUI 层，弹窗遮罩自动压暗，照常渲染）；3D 精灵弹窗打开时颜色压暗
                val slotX = leftX + 2
                val slotY = y + 2
                context.matrices.push()
                context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
                context.matrices.scale(20f / 66f, 20f / 66f, 1f)
                context.drawTexture(POKEMON_SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
                context.matrices.pop()
                // 预览弹窗打开时不渲染 3D（模型层在衬底之上，压暗仍会浮在弹窗上）
                if (dialogPokemon == null && dialogItem == null) renderPokemonIcon(context, origIndex, slotX, slotY, 20, delta = delta, preview = i == hoveredRow)

                // 来源（[队]/[PC] 固定色，精灵名属性色 + 金色闪光星标，照搬 SellSelectScreen；PC 用电脑物品图标，[] 保持原色）
                val tc = typeColor(if (p.primaryType.isNotEmpty()) p.primaryType else "cobblemon.type.normal")
                val srcColor = if (p.source == "party") 0x55FF55 else 0x55AAFF
                var sx = leftX + 26
                if (p.source == "party") {
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

                // Ball icon（球种统一在精灵名称左侧；弹窗打开时不渲染；ball 非空就占位 12px，与原来一致）
                if (dialogPokemon == null && dialogItem == null && p.ball.isNotEmpty()) {
                    iconData[origIndex]?.ballStack?.let { ballStack ->
                        com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                            itemStack = ballStack, x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                    }
                    sx += 12
                }

                val name = speciesDisplay(p)
                context.drawTextWithShadow(textRenderer, name, sx, y + 7, tc)
                sx += textRenderer.getWidth(name)
                if (p.shiny) {
                    context.drawText(textRenderer, "★", sx + 2, y + 7, GOLD_COLOR, false)
                    sx += 2 + textRenderer.getWidth("★")
                }
                sx += 2

                // Gender icon（紧跟名字）
                if (p.gender == "MALE" || p.gender == "FEMALE") {
                    val gi = if (p.gender == "MALE") GENDER_ICON_MALE else GENDER_ICON_FEMALE
                    com.cobblemon.mod.common.api.gui.blitk(matrixStack = context.matrices, texture = gi, x = sx, y = y + 7, width = 6, height = 8)
                    sx += 8
                }

                // Held item icon（紧跟性别；弹窗打开时不渲染；空气/空 id 已过滤为 null）
                if (dialogPokemon == null && dialogItem == null) {
                    iconData[origIndex]?.heldStack?.let { heldStack ->
                        com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                            itemStack = heldStack, x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                        sx += 12
                    }
                }

                // 体型徽章（排在携带物图标之后，留 3px 空隙）
                if (p.sizeCategory.isNotEmpty()) {
                    sx += 3
                    sx += EntryBadgeRenderer.drawSizeBadgeIcon(context, p.sizeCategory, sx, y + 7)
                }

                // Level
                val levelText = Text.translatable("cobblemarket.gui.lv").string + p.level
                context.drawText(textRenderer, levelText, leftX + 200, y + 7, 0x000000, false)
            }
        } else {
            items.drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, item ->
                val y = startY + i * rowHeight
                drawItemWithBar(context, item.stack, leftX + 4, y + 4)
                // 行名照物品栏悬浮第一行按稀有度着色（组件明细看悬停词条，行内不重复显示）
                val display = com.shusheng.cobblemarket.util.TextUtil.rarityColoredName(item.stack)
                    .copy().append(Text.literal(" ×${item.count}"))
                context.drawTextWithShadow(textRenderer,
                    com.shusheng.cobblemarket.util.TextUtil.truncateText(display, 200),
                    leftX + 24, y + 7, 0xFFFFFF)
            }
        }

        if (currentTab == 0 && loaded && !loadedAll) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.sell.loading_more", pokemonList.size).formatted(Formatting.GRAY),
                centerX, height - 49, 0x888888)
        } else if (displayCount() > getMaxVisibleRows()) {
            context.drawCenteredTextWithShadow(textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + getMaxVisibleRows(), displayCount())} / ${displayCount()}",
                centerX, height - 49, 0x888888)
        }

        // 悬停预览大图：画在本界面内容全部之后才在面板之上。
        // **弹窗打开时一律不画**（照精灵市场）：这时注册表里只剩弹窗自己的图标，鼠标停在弹窗上
        // 照样能命中 entryInRow（它只按 y 匹配行范围 + x 在面板内）→ 左侧无故冒出大图（2026-09-15 实测）
        if (dialogPokemon == null && dialogItem == null) {
            val previewRowL = width / 2 - panelWidth / 2
            IconRegistry.entryInRow(mouseX.toDouble(), mouseY.toDouble(), previewRowL, previewRowL + panelWidth)
                ?.let { PokemonHoverPreview.render(context, it.renderable, it.state, previewRowL, height) }
            // 物品 tab 的大图：物品行没有注册表条目，按绝对索引从本地背包列表取
            if (currentTab == 1 && hoveredRow >= 0) {
                items.getOrNull(scrollOffset + hoveredRow)?.let {
                    ItemHoverPreview.render(context, it.stack, previewRowL, height)
                }
            }
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
    // 物品行 tooltip 富文本缓存（Text 保留词条颜色，照黑名单；Shift/Ctrl 展开按需构建）
    private var itemTooltipCacheKey: SellItem? = null
    private var itemTooltipLines: List<Pair<Text, Int>> = emptyList()
    private var itemTooltipAdvancedLines: List<Pair<Text, Int>>? = null
    private var itemTooltipAdvancedType: net.minecraft.item.tooltip.TooltipType? = null

    // 悬停提示（照搬 SellSelectScreen）
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
            lines.add(Text.literal(Text.translatable("cobblemarket.gui.tooltip_type").string).append(EntryBadgeRenderer.typeLine(p.primaryType, p.secondaryType)) to 0xFFFFFF)
            lines.add(Text.literal(Text.translatable("cobblemarket.gui.tooltip_nature").string)
                .append(EntryBadgeRenderer.natureText(p.natureBase, p.nature))
                .append(Text.literal("  ${Text.translatable("cobblemarket.gui.tooltip_ability").string}"))
                .append(Text.translatable(p.ability)) to 0xFFFFFF)
            var heldItemLine = -1
            if (hasHeldItem) {
                heldItemLine = lines.size
                lines.add(Text.translatable("cobblemarket.gui.tooltip_held") to 0xFFFFFF)
            }
            lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs") to 0xFFFFFF)
            lines.add(Text.literal("  $hp: ${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsHp, p.htHp)}").append(Text.literal("  EV: ${p.evsHp}").formatted(Formatting.RED)) to 0x66FF66); lines.add(Text.literal("  $atk: ${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsAtk, p.htAtk)}").append(Text.literal("  EV: ${p.evsAtk}").formatted(Formatting.RED)) to 0xFF6666)
            lines.add(Text.literal("  $def: ${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsDef, p.htDef)}").append(Text.literal("  EV: ${p.evsDef}").formatted(Formatting.RED)) to 0xFFCC66); lines.add(Text.literal("  $spa: ${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpAtk, p.htSpAtk)}").append(Text.literal("  EV: ${p.evsSpAtk}").formatted(Formatting.RED)) to 0x6699FF)
            lines.add(Text.literal("  $spd: ${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpDef, p.htSpDef)}").append(Text.literal("  EV: ${p.evsSpDef}").formatted(Formatting.RED)) to 0x66FF99); lines.add(Text.literal("  $spe: ${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpd, p.htSpd)}").append(Text.literal("  EV: ${p.evsSpd}").formatted(Formatting.RED)) to 0xFF99FF); lines.add(Text.translatable("cobblemarket.gui.friendship", p.friendship) to 0xFF99CC)
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
                marksRows = if (markTextures.size > EntryBadgeRenderer.MARKS_PER_ROW) 2 else 1
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
            if (marksRows > 0) mw = maxOf(mw, minOf(EntryBadgeRenderer.MARKS_PER_ROW, markTextures.size) * 12)
            mw = maxOf(mw, EntryBadgeRenderer.movesBlockWidth(p.moves))
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
                // 证章图标行：第一行最多 MARKS_PER_ROW 个；超出的部分第二行显示「+N」
                val textures = p.marks.mapNotNull { Identifier.tryParse(it) }
                textures.take(EntryBadgeRenderer.MARKS_PER_ROW).forEachIndexed { idx, texture ->
                    com.cobblemon.mod.common.api.gui.blitk(
                        matrixStack = context.matrices, texture = texture,
                        x = tx + idx * 12, y = rowY, width = 8, height = 8
                    )
                }
                if (textures.size > EntryBadgeRenderer.MARKS_PER_ROW) {
                    context.drawCenteredTextWithShadow(textRenderer, "+${textures.size - EntryBadgeRenderer.MARKS_PER_ROW}", tx + minOf(EntryBadgeRenderer.MARKS_PER_ROW, textures.size) * 6, rowY + 12, 0xAAAAAA)
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

    /** 物品行悬停：真实物品词条（BASIC 常驻 / Shift 完整词条 / Ctrl 调试信息，Text 保留词条自带颜色，照黑名单/物品市场） */
    private fun renderItemTooltip(context: DrawContext, item: SellItem, mouseX: Int, mouseY: Int) {
        if (itemTooltipCacheKey != item) {
            itemTooltipCacheKey = item
            itemTooltipLines = buildItemTooltipLines(item, net.minecraft.item.tooltip.TooltipType.BASIC)
            itemTooltipAdvancedLines = null
            itemTooltipAdvancedType = null
        }
        val advanced = if (com.shusheng.cobblemarket.client.ItemComponentsDisplay.hoverExpanded()) {
            val type = com.shusheng.cobblemarket.client.ItemComponentsDisplay.tooltipTypeForHover()
            if (itemTooltipAdvancedType != type) {
                itemTooltipAdvancedType = type
                itemTooltipAdvancedLines = buildItemTooltipLines(item, type)
            }
            itemTooltipAdvancedLines
        } else {
            itemTooltipAdvancedType = null
            null
        }
        val lines = advanced ?: itemTooltipLines

        var maxWidth = 0
        lines.forEach { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it.first)) }

        val padding = 4
        val tx = minOf(mouseX + 12, width - maxWidth - 12)
        val tooltipHeight = lines.size * 10 + padding
        val tyAbove = mouseY - tooltipHeight - 4
        val ty = if (tyAbove <= 0) minOf(mouseY + 12, height - tooltipHeight) else tyAbove

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, lines.size * 10 + 2 * padding, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, (line, color) ->
            context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
        }
        context.matrices.pop()
    }

    private fun buildItemTooltipLines(item: SellItem, type: net.minecraft.item.tooltip.TooltipType): List<Pair<Text, Int>> =
        com.shusheng.cobblemarket.client.ItemComponentsDisplay.itemTooltip(item.stack, client?.player, type).map { it to 0xFFFFFF }

    // ── 交互 ──

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (dialogPokemon != null || dialogItem != null) return false
        // 展开列表滚动：展开时滚的是浮层本身，不是下面的列表
        if (filterListOpen.isNotEmpty()) {
            // ⚠ 上限必须与 rebuildFilterList 里的 maxRows 同一算法（小屏时实际显示行数会少于 8），
            //   否则能滚到"浮层里根本没显示"的偏移量
            val listTop = (filterListSourceButton()?.y ?: 0) + 22
            val maxRows = minOf(MAX_FILTER_LIST_ROWS, maxOf(1, (height - 32 - listTop) / FILTER_LIST_ROW_H))
            val total = filterListOptions().size
            if (total > maxRows) {
                filterListScroll = (filterListScroll - verticalAmount.toInt()).coerceIn(0, total - maxRows)
                rebuildFilterList()
            }
            return true
        }
        // 面板内滚动（内容比面板高时才有用）：鼠标落在面板上才滚，否则滚的是下面的列表
        if (filterExpanded && filterPanelHeight() < FILTER_PANEL_HEIGHT &&
            mouseX >= MarketPanelBackground.filterLeft(width) &&
            mouseX < MarketPanelBackground.filterLeft(width) + MarketPanelBackground.filterWidth(width)
        ) {
            filterPanelScroll = (filterPanelScroll - verticalAmount.toInt() * 10)
                .coerceIn(0, FILTER_PANEL_HEIGHT - filterPanelHeight())
            applyFilterVisibility()
            return true
        }
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        return true
    }

    private fun isInputFieldFocused() = focused?.let { f ->
        f === searchField || f === hpF || f === atkF || f === defF || f === spaF || f === spdF || f === speF
    } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true ||
        hpF?.isMouseOver(mouseX, mouseY) == true ||
        atkF?.isMouseOver(mouseX, mouseY) == true ||
        defF?.isMouseOver(mouseX, mouseY) == true ||
        spaF?.isMouseOver(mouseX, mouseY) == true ||
        spdF?.isMouseOver(mouseX, mouseY) == true ||
        speF?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 图标点击叫声：本界面**点行会开设置弹窗**，下层行图标随即不再渲染（主 render 直接 return），
        // 触发在它身上的叫声会哑掉 —— 所以这里不做行内整行触发，叫声改由弹窗图标承担（见 openDialog）
        if (button == 0) IconCryClick.playIconAt(mouseX, mouseY)
        if (dialogPokemon != null || dialogItem != null) {
            return super.mouseClicked(mouseX, mouseY, button)
        }
        val wasInInput = isInputFieldFocused()
        // 点击行 → 打开拍卖设置弹窗
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        val leftX = width / 2 - panelWidth / 2
        val startY = getListStartY()
        if (hoveredRow >= 0 &&
            mouseX in leftX.toDouble()..(leftX + panelWidth - 52).toDouble() &&
            mouseY in (startY + hoveredRow * rowHeight).toDouble()..(startY + (hoveredRow + 1) * rowHeight).toDouble()
        ) {
            val idx = scrollOffset + hoveredRow
            if (currentTab == 0) {
                val list = filteredPokemon()
                if (idx in list.indices) openDialog(list[idx], null)
            } else {
                if (idx in items.indices) openDialog(null, items[idx])
            }
        }
        return result
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val wasOpen = dialogPokemon != null || dialogItem != null
        val savedPokemon = dialogPokemon
        val savedItem = dialogItem
        val savedStarting = startingField?.text ?: ""
        val savedIncrement = incrementField?.text ?: ""
        val savedCount = countField?.text ?: ""
        val savedDuration = durationIndex
        super.resize(client, width, height)
        if (wasOpen) {
            dialogPokemon = null
            dialogItem = null
            openDialog(savedPokemon, savedItem)
            startingField?.text = savedStarting
            incrementField?.text = savedIncrement
            countField?.text = savedCount
            durationIndex = savedDuration
            updateDurationButton()
        }
    }

    override fun close() {
        closed = true
        super.close()
    }

    override fun shouldPause() = false

    companion object {
        private var auctionRequestId = 0
        /** 展开列表最多显示几行（超出用滚轮翻，照精灵市场） */
        const val MAX_FILTER_LIST_ROWS = 8
        // ⚠ 浮层起点不再是常量：改成「被点按钮自己的 y + 22」，读控件、不维护第二份坐标表
        private const val FILTER_LIST_ROW_H = 14
        val GENDER_ICON_MALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
        val GENDER_ICON_FEMALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
        val POKEMON_SLOT_TEXTURE = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")

        /** 顶部身份位的拍卖锤图标（48x48，与拍卖场界面同一枚） */
        val HAMMER_TEX_LEFT = Identifier.of("cobblemarket", "textures/gui/auction_gavel_left.png")
    }
}
