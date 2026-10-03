package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.icon.ItemHoverPreview
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.client.requestCreditInfo
import com.shusheng.cobblemarket.client.PointsStateCache
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_SRC
import com.shusheng.cobblemarket.client.CURRENCY_BUTTON_ICON_SIZE
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.CurrencyService

import com.shusheng.cobblemarket.network.BuyItemPayload
import com.shusheng.cobblemarket.network.CancelItemPayload
import com.shusheng.cobblemarket.network.CreditInfoPayload
import com.shusheng.cobblemarket.network.CollectBalancePayload
import com.shusheng.cobblemarket.network.RequestBalancePayload
import com.shusheng.cobblemarket.network.ItemEntry
import com.shusheng.cobblemarket.network.ItemMarketDataPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.RequestItemMarketPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.util.UUID
import com.shusheng.cobblemarket.client.drawItemWithBar
import com.shusheng.cobblemarket.client.FilterPanelAnimation
import com.shusheng.cobblemarket.client.ItemCelebrationAnimation
import com.shusheng.cobblemarket.client.clampTooltipRows
import com.shusheng.cobblemarket.client.currencyUnitWidth
import com.shusheng.cobblemarket.client.drawCurrencyUnit

class ItemMarketScreen : Screen(Text.translatable("cobblemarket.item.title")) {

    private val panelWidth = 296
    private val slotSize = 36
    private val gap = 4

    /**
     * 筛选面板的**内容高度**（照精灵市场：由内容定，不跟随主面板铺到屏幕底边）。
     * 面板顶 y=2，三行按钮：排序 y=20 / 我的 y=44 / 分类 y=68，各高 20 ⇒ 末行底 88
     * ⇒ 内容 86 + 底部留白 10 = 96（精灵市场同口径：末行底 310 → 高 318）。
     * ⚠ 往面板里加控件要同步改它，否则新控件会掉出面板背景。
     * ⚠ 展开**分类网格**时面板还要往下长 —— 高度一律走 [filterPanelHeight]，别读这个常量。
     */
    private val FILTER_PANEL_HEIGHT = 96

    /** 「分类」按钮所在行的 y（面板第三行）；分类网格就挂在它下方 */
    private val CATEGORY_BTN_Y = 68

    /** 分类网格：4 列、格间距 2px（格子边长按面板内容宽反算，窄屏自动变小） */
    private val GRID_COLS = 4
    private val GRID_GAP = 2

    private var entries = listOf<ItemEntry>()
    private var currentPage = 1
    private var totalPages = 1

    /** 列表在查哪个币种（货币切换按钮控制）—— **只决定查什么，不参与渲染**：
     *  显示单位一律读挂单自己的 [ItemEntry.isPoints]，否则任何一处漏改就会把 RP 商品标成 ₽ */
    private var currency = com.shusheng.cobblemarket.client.MarketStateCache.marketCurrency

    /** 货币切换按钮（RP 总开关关着时 = null，压根没建） */
    private var currencyButton: NineSliceButton? = null
    // 搜索防抖（照精灵市场）：250ms 静默后统一发请求，避免连续输入被服务端节流丢包
    private var searchDirty = false
    private var lastSearchEdit = 0L
    private var pendingBalance = 0L
    /** 待领取物品数量：>0 时待领取按钮右上角画红点（随市场数据响应刷新） */
    private var pendingReturns = 0

    private var searchField: TextFieldWidget? = null
    private var sortMode = "NEWEST"
    private var showMineOnly = false
    private var collectButton: NineSliceButton? = null
    private var returnsButton: NineSliceButton? = null
    private var backButton: NineSliceButton? = null
    private var sellAddButton: NineSliceButton? = null
    private var sortButton: NineSliceButton? = null
    private var mineButton: NineSliceButton? = null
    /** 面板第一行右侧那枚 ↺：只重置**这块面板里**的筛选项（排序 / 我的 / 分类） */
    private var resetButton: NineSliceButton? = null
    private var prevButton: NineSliceButton? = null
    private var nextButton: NineSliceButton? = null

    // ── 筛选展开（右侧独立面板，照精灵市场）：开关按钮 + 面板开关动画 ──
    // ⚠ 开关**只切可见性、不重建界面**（照精灵市场）：重建会把搜索词、翻页状态一并丢掉；
    //   代价是开关按钮**自己**的图标 / 选中态 / 悬停提示要手动更一次（见 updateFilterToggleButton）
    private var filterToggleButton: NineSliceButton? = null
    private var filterExpanded = false
    private val filterAnim = FilterPanelAnimation()

    // ── 分类筛选（创造模式物品分类，2026-10-03）──────────────────────────────────
    /**
     * 当前选中的分类（null = 不限）。
     * ⚠ 发请求时**只发这个分类 ID**，分类里有哪些物品由**服务端**自己算 —— 一个分类几百上千个物品，
     *   走不了客户端算 ID 列表那条路（`MAX_ITEM_IDS` 只有 256）。见 `util/ItemCategoryIndex`。
     */
    private var categoryFilter: Identifier? = null
    /** 分类网格是否展开（面板第三行那枚「分类」按钮点开） */
    private var categoryGridOpen = false
    /** 网格的**行**滚动 —— 整行滚，保证格子永远对齐（分类多到一屏铺不下时才用得上） */
    private var categoryScrollRow = 0
    private var categoryButton: NineSliceButton? = null
    /**
     * 网格格子按钮：第 0 个是「不限」，其后与 `ItemCategories.all()` 一一对应。
     * 常驻创建（分类在注册表冻结后就是固定的），开关 / 滚动只改位置与可见性。
     */
    private val categoryButtons = mutableListOf<NineSliceButton>()

    private var hoveredSlot = -1
    // 物品栈解析缓存：itemNbt → ItemStack 反序列化只在收到市场数据时做一次，
    // render 每帧直接取用（此前每帧解析 56 个 NBT 树是物品市场掉帧主因）
    private val entryStacks = mutableMapOf<UUID, ItemStack>()
    // tooltip 文本行缓存：内容只取决于条目，悬停同一格时每帧重建文本行是悬停掉帧主因
    private var tooltipCacheKey: UUID? = null
    private var tooltipCacheLines: List<Pair<Text, Int>> = emptyList()
    /** 悬停窗里**价格行**的下标（构建行时记下、渲染时用；-1 = 无）—— 货币单位补在这一行行尾 */
    private var tooltipPriceRow = -1
    /** Shift 展开词条缓存（按住 Shift 才按需构建，防每帧 getTooltip ADVANCED 解析 NBT 掉帧） */
    private var tooltipAdvancedLines: List<Pair<Text, Int>>? = null
    private var tooltipAdvancedType: TooltipType? = null

    private var selectedEntry: ItemEntry? = null

    /**
     * 购买弹窗里那个物品图标的屏幕位置，**渲染时记录**。
     *
     * 给物品庆祝动画当「图标起飞」的起点用 —— 确认购买后弹窗就关了、图标不复存在，坐标必须提前抓下来。
     * 之所以在渲染时记、而不是在 [confirmBuy] 里重算：图标位置跟着 `dialogY` 走，
     * 弹窗高度还会随消费贷开关伸缩（210/170），重算容易错位；渲染时记录则永远和实际画出来的一致。
     */
    private var buyDialogIconOrigin: ItemCelebrationAnimation.Origin? = null
    private var buyCountField: TextFieldWidget? = null
    // 购买数量校验失败提示（按钮上方红字，2 秒后消失；重新输入时清除）
    private var buyErrorText: net.minecraft.text.Text? = null
    private var buyErrorUntil = 0L
    private var cancelEntry: ItemEntry? = null

    // ── 喵喵支付（消费贷，批次 4）：购买弹窗内入口按钮，点击进独立 MeowthPayScreen ──
    private var buyPayButton: NineSliceButton? = null

    /**
     * 购买 / 取消两枚按钮 —— ⚠ **必须存引用**：弹窗里的词条会随 Shift 展开而变多
     *（TM 这类物品的词条是 Cobblemon 自己生成的，**自己会读 Shift，`TooltipType.BASIC` 拦不住**），
     * 弹窗跟着长高、文字整体下移，控件要**每帧**按当前布局重贴（见 [syncBuyDialogControls]）。
     * 没存引用就贴不了 —— 原先这两枚是直接 `addDrawableChild(NineSliceButton(...))` 匿名加进去的。
     */
    private var buyConfirmButton: NineSliceButton? = null
    private var buyCancelButton: NineSliceButton? = null
    /** 消费贷开关：初始读全局缓存（入口界面已拉取）避免首次打开弹窗高度闪烁；关时弹窗 170 无喵喵支付按钮 */
    private var payAvailable = com.shusheng.cobblemarket.client.FinanceCache.consumerLoanEnabled

    private fun columns() = (panelWidth + gap) / (slotSize + gap)
    private fun getGridStartY() = 68
    // 底部预留 72px（照精灵市场：按钮 20 + 底部分割线 4 + 面板底边框 16 + 间距），
    // 保证窗口高度为任意值时翻页按钮都不会遮住面板底部边框
    private fun rows() = maxOf(0, (height - getGridStartY() - 72) / (slotSize + gap))

    private fun sortDisplay(): String = when (sortMode) {
        "PRICE_ASC" -> "cobblemarket.sort.price_asc"
        "PRICE_DESC" -> "cobblemarket.sort.price_desc"
        "NEWEST" -> "cobblemarket.sort.newest"
        else -> "cobblemarket.sort.price_asc"
    }

    override fun init() {
        super.init()
        sendToServer(RequestBalancePayload())
        val leftX = width / 2 - panelWidth / 2

        // 收款 / 返回
        collectButton = NineSliceButton(
            leftX, 13, 50, 16,
            Text.translatable("cobblemarket.gui.collect"), { collectBalance() }
        )
        // ⚠ 初值跟着**当前币种**设：resize 会重建界面而 currency 是字段（值保留），
        //   这里漏了就会出现「在 RP 页 resize 一下，收款按钮又冒出来」
        collectButton?.visible = currency != MarketCurrency.POINTS
        addDrawableChild(collectButton)

        // Expired returns button
        returnsButton = NineSliceButton(
            leftX + 52, 13, 50, 16,
            Text.translatable("cobblemarket.gui.returns"), { client?.setScreen(ItemReturnScreen()) }
        )
        addDrawableChild(returnsButton)
        backButton = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        // 货币切换按钮（返回按钮左侧 4px）：看哪个币种的商品（与精灵市场同一个位置、同一套逻辑）
        // ⚠ 玩家端**也不受开关约束**：卖家要能看到并下架自己的存量 RP 挂单。
        //   买 / 出价仍由服务端拦（pointsGateBlocked）—— 真正的权限在付款那一步，不在能不能看
        currencyButton = NineSliceButton(
                leftX + panelWidth - 74, 13, 20, 16,
                Text.literal(""), { onCurrencyClick() },
                iconTexW = POINTS_SYMBOL_SRC, iconTexH = POINTS_SYMBOL_SRC,
                iconScale = CURRENCY_BUTTON_ICON_SIZE / POINTS_SYMBOL_SRC.toFloat(),
                tooltip = Text.translatable("cobblemarket.gui.currency_switch", com.shusheng.cobblemarket.client.activeCurrencyUnit(), CurrencyService.pointsIcon())
        ).also {
            updateCurrencyButton(it)
            addDrawableChild(it)
        }

        // 搜索框：宽度 = 面板 − 4（左右边距）− 50（小手）− 2 − 2（两侧间隔）− 50（筛选展开）
        // （照精灵市场算式 panelWidth - 108：顶栏三件套「搜索框 | 小手 | 筛选展开」，小手与筛选同宽 50；
        //   2026-10-03 用户要求向精灵市场看齐，排序与「我的」搬进筛选面板）

        val savedSearch = searchField?.text ?: ""
        searchField = TextFieldWidget(textRenderer, leftX + 2, 44, panelWidth - 108, 16, Text.translatable("cobblemarket.item.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.item.search").formatted(Formatting.GRAY))
        // 服务端搜索：文字变化只标记 dirty，tick 防抖后回到第一页重新拉取（与精灵市场一致）
        searchField?.setChangedListener { _ ->
            searchDirty = true
            lastSearchEdit = System.currentTimeMillis()
        }
        addSelectableChild(searchField)
        addDrawableChild(searchField)
        searchField?.text = savedSearch

        // 上架按钮（choose 图标）：宽 50，紧贴搜索框右侧（宽度同精灵市场那枚小手）
        sellAddButton = NineSliceButton(
            leftX + panelWidth - 104, 44, 50, 16,
            Text.literal(""), { openItemSellScreen() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/choose.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            // 纯图标按钮一律给悬停提示（同排搜索框右侧，没提示玩家不知道小手是干什么的）
            tooltip = Text.translatable("cobblemarket.gui.sell_item")
        )
        addDrawableChild(sellAddButton)

        // 筛选展开/收起按钮（顶栏最右，照精灵市场）：去文字化——展开显示 filter、收起显示 filter_close
        filterToggleButton = NineSliceButton(
            leftX + panelWidth - 52, 44, 50, 16,
            Text.literal(""),
            { toggleFilters() },
            iconLeft = Identifier.of("cobblemarket",
                if (filterExpanded) "textures/gui/button/filter.png" else "textures/gui/button/filter_close.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        ).also {
            it.setTooltip(Tooltip.of(Text.translatable(
                if (filterExpanded) "cobblemarket.gui.filter_collapse" else "cobblemarket.gui.filter_expand")))
            // 面板展开期间按钮保持**选中态**（按钮背景第三态，见 NineSliceButton.selectedVisual）——
            // 一眼看出面板当前是开着还是关着（resize 重建时按当前状态设一次）
            it.selectedVisual = filterExpanded
        }
        addDrawableChild(filterToggleButton)

        // ── 以下两枚是**右侧筛选面板**内的控件（**常驻创建**，开关只切可见性）──────────────
        // ⚠ 别用 `if (filterExpanded)` 把它们包起来：那会让开关走"重建界面"的老路，
        //   把搜索词与翻页状态一并丢掉。面板是独立的一块，开关只该影响它自己
        //   （见 updateControlVisibility 的 filterExpanded）。
        val fx = MarketPanelBackground.filterLeft(width) + 4
        val fw = filterPanelBtnW()

        // 排序（整行左边，点一下循环：价格↑ → 价格↓ → 最新）+ 重置 ↺（右侧 20 宽，照精灵市场那一行）
        // ⚠ 排序让出 24px 给 ↺（fw - 24 = 128；英文最长的 "Sort: Price ↓" ≈ 66px，够放）
        sortButton = NineSliceButton(
            fx, 20, fw - 24, 20,
            Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay())),
            { cycleSort() }
        )
        addDrawableChild(sortButton)

        // 重置 ↺：**只重置这块面板里的筛选项**（排序 / 我的 / 分类）—— 不碰搜索框
        resetButton = NineSliceButton(
            fx + fw - 20, 20, 20, 20,
            Text.literal("↺"),
            { resetItemFilters() },
            tooltip = Text.translatable("cobblemarket.gui.reset")
        )
        addDrawableChild(resetButton)

        // 我的（personal/personal_click 双图标：关 = 全部，开 = 仅我的；悬停词条随状态）
        // 搬进面板后占满整行（原来在顶栏只占 18 宽的纯图标位）
        mineButton = NineSliceButton(
            fx, 44, fw, 20,
            Text.literal(""),
            { toggleMineOnly() },
            iconLeft = mineIcon(showMineOnly),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine")
        )
        // 「我的」是**开关**：开启时按钮给选中态（resize 重建时 showMineOnly 可能已经是 true）
        mineButton?.selectedVisual = showMineOnly
        addDrawableChild(mineButton)

        // 分类（第三行）：显示当前分类名，点一下在下方展开**分类网格**（创造模式物品分类）
        categoryButton = NineSliceButton(
            fx, CATEGORY_BTN_Y, fw, 20,
            categoryButtonText(),
            { toggleCategoryGrid() }
        )
        // 按钮文字会截断 ⇒ 悬停提示给**完整**分类名（照项目「不定长内容」的规矩）
        categoryButton?.setTooltip(Tooltip.of(categoryFullText()))
        addDrawableChild(categoryButton)

        // 分类网格的格子：第 0 格是「不限」，其后每格一个创造分类（图标 = 该分类的代表物品）。
        // ⚠ 常驻创建（分类在注册表冻结后是固定的），开关 / 滚动只改位置与可见性。
        //   分类数量随模组增减 ⇒ 网格行数跟着变，这正是要的效果
        val cell = gridCell()
        val gridW = GRID_COLS * cell + (GRID_COLS - 1) * GRID_GAP
        val cellX0 = fx + (fw - gridW) / 2
        val cellIcon = (cell - 14).coerceIn(16, 22)
        categoryButtons.clear()
        val anyCell = NineSliceButton(
            cellX0, gridTopY(), cell, cell,
            Text.translatable("cobblemarket.gui.filter_any"),
            { selectCategory(null) },
            tooltip = Text.translatable("cobblemarket.gui.filter_any")
        )
        addDrawableChild(anyCell)
        categoryButtons += anyCell
        com.shusheng.cobblemarket.client.ItemCategories.all().forEach { cat ->
            val btn = NineSliceButton(
                cellX0, gridTopY(), cell, cell,
                Text.literal(""),            // 纯图标格：名字走悬停提示
                { selectCategory(cat.id) },
                tooltip = cat.name
            )
            btn.iconStack = cat.icon
            btn.iconStackSize = cellIcon
            addDrawableChild(btn)
            categoryButtons += btn
        }
        // 选中态/网格展开态在 init 末尾的 updateControlVisibility 里统一刷（它顺带给格子定位）

        // 分页（照精灵市场：底部分割线在网格最后一行下方 4px 对称，按钮在其与背景底边之间居中偏上 5px）
        val gridBottom = getGridStartY() + rows() * (slotSize + gap)
        val btnY = (gridBottom + 5 + (height - 32)) / 2 - 10 - 5
        prevButton = NineSliceButton(
            leftX, btnY, 80, 20,
            Text.literal(""),
            { prevPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/previous.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.prev")
        )
        addDrawableChild(prevButton)
        nextButton = NineSliceButton(
            leftX + panelWidth - 80, btnY, 80, 20,
            Text.literal(""),
            { nextPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/next.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.next")
        )
        addDrawableChild(nextButton)
        updatePageButtons()
        // 建完先刷一遍可见性：面板控件是**常驻创建**的（默认 visible=true），
        // 面板收起时必须立刻藏起来，否则会挂在屏幕右侧（照精灵市场 init 末尾那一次）
        updateControlVisibility()

        requestFilterRefresh()
    }

    private fun openItemSellScreen() {
        client?.setScreen(ItemSellScreen())
    }

    private fun refreshData(resetPending: Boolean = true) {
        lastListRequestAt = System.currentTimeMillis()
        if (resetPending) pendingPage = 0 // 非翻页路径（搜索/排序/购买后刷新）取消未发出的翻页目标
        val query = searchField?.text?.trim() ?: ""
        sendToServer(
            RequestItemMarketPayload(
                sortMode, currentPage, showMineOnly, columns() * rows(),
                query,
                com.shusheng.cobblemarket.client.ItemSearchIndex.itemIdsMatchingStrict(query),
                com.shusheng.cobblemarket.client.ItemSearchIndex.tmMovesMatching(query).toList(),
                com.shusheng.cobblemarket.client.ItemSearchIndex.enchantsMatching(query).toList(),
                currency.name,
                // 「分类」筛选：只发分类 ID，这个分类里有哪些物品由**服务端**自己算
                //（一个分类几百上千个物品，走 itemIds 那条路会撑爆 256 的上限）
                categoryFilter?.toString() ?: ""
            )
        )
    }

    // 防抖计时器（照精灵市场）：250ms 静默后统一发请求
    override fun tick() {
        // 兜底：翻页请求 1s 未收到响应（如服务端节流静默丢弃）强制复位 inFlight，防止按钮永久锁灰
        if (pageRequestInFlight && System.currentTimeMillis() - lastListRequestAt > 1000) {
            pageRequestInFlight = false
        }
        // 目标页码补发：窗口允许且无在途时发出请求（连点合并到最终目标页；
        // 目标保留到响应确认，请求被丢弃时兜底复位后自动重试）
        if (!pageRequestInFlight && pendingPage != 0 && System.currentTimeMillis() - lastListRequestAt >= PAGE_CLICK_INTERVAL_MS) {
            currentPage = pendingPage.coerceIn(1, maxOf(1, totalPages))
            pageRequestInFlight = true
            updatePageButtons()
            refreshData(resetPending = false)
        }
        // 筛选变更补发：与翻页共用 inFlight/窗口
        if (!pageRequestInFlight && pendingFilterRefresh && System.currentTimeMillis() - lastListRequestAt >= PAGE_CLICK_INTERVAL_MS) {
            pendingFilterRefresh = false
            pageRequestInFlight = true
            refreshData(resetPending = false)
        }
        if (searchDirty && System.currentTimeMillis() - lastSearchEdit >= 250) {
            searchDirty = false
            currentPage = 1
            requestFilterRefresh()
        }
    }

    private fun collectBalance() {
        sendToServer(CollectBalancePayload())
    }

    fun onItemMarketData(payload: ItemMarketDataPayload) {
        entries = payload.entries
        totalPages = payload.totalPages
        when {
            pendingPage == 0 -> currentPage = payload.currentPage
            // 目标页已到达（或被服务端 clamp 出界）：确认，清目标
            payload.currentPage == pendingPage || pendingPage > payload.totalPages -> {
                pendingPage = 0
                currentPage = payload.currentPage
            }
            // 目标未达（罕见）：保留目标由 tick 继续重试，页码维持乐观值
            else -> {}
        }
        pendingBalance = payload.pendingBalance
        pendingReturns = payload.pendingReturns
        pageRequestInFlight = false
        updatePageButtons()
        rebuildEntryStacks()
    }

    /** 一次性解析本页全部物品栈（render 每帧只读缓存，见 entryStacks 注释） */
    private fun rebuildEntryStacks() {
        entryStacks.clear()
        val registry = client?.world?.registryManager ?: return
        entries.forEach { entry ->
            entryStacks[entry.id] = ItemStack.fromNbtOrEmpty(registry, entry.itemNbt)
        }
    }


    private fun cycleSort() {
        sortMode = when (sortMode) {
            "PRICE_ASC" -> "PRICE_DESC"
            "PRICE_DESC" -> "NEWEST"
            else -> "PRICE_ASC"
        }
        sortButton?.message = Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay()))
        currentPage = 1
        requestFilterRefresh()
    }

    /**
     * 「我的」按钮的开/关图标 —— ⚠ **贴图路径只此一处**：1.2.x 在 `gui/` 下、1.3.x 在 `gui/button/` 下，
     * 上浮时改这一个串就够（原先 init 与 toggle 里各写一份，加重置就成了三处）。
     */
    private fun mineIcon(on: Boolean) = Identifier.of("cobblemarket",
        if (on) "textures/gui/button/personal_click.png" else "textures/gui/button/personal.png")

    /** 把「我的」按钮的外观刷成当前状态（图标 / 悬停词条 / 选中态）—— 开关与重置共用 */
    private fun updateMineButton() {
        mineButton?.iconLeft = mineIcon(showMineOnly)
        mineButton?.setTooltip(Tooltip.of(Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine")))
        // 「我的」是个**开关**：开启时除换图标，按钮也给选中态（照手持物品那几个开关）
        mineButton?.selectedVisual = showMineOnly
    }

    private fun toggleMineOnly() {
        showMineOnly = !showMineOnly
        updateMineButton()
        currentPage = 1
        requestFilterRefresh()
    }

    /**
     * 面板第一行那枚 ↺：**只重置这块面板里的筛选项** —— 排序回「最新」、我的关掉、分类回「不限」。
     * ⚠ 不碰**搜索框**（搜索词是玩家自己敲的，不属于"筛选面板的开关"）。
     * ⚠ **分类网格保持展开、滚动位置也不动**（用户 2026-10-03 要求：别"主动"把展开的收起来 ——
     *   与"选中一格也不收"同一条口径）。
     */
    private fun resetItemFilters() {
        sortMode = "NEWEST"
        showMineOnly = false
        categoryFilter = null
        sortButton?.message = Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay()))
        updateMineButton()
        categoryButton?.message = categoryButtonText()
        categoryButton?.setTooltip(Tooltip.of(categoryFullText()))
        currentPage = 1
        requestFilterRefresh()
        updateControlVisibility()
    }

    // ── 右侧筛选面板（照精灵市场：内容定高、展开动画、开关只切可见性）──

    /** 面板内容宽（面板宽 − 两侧各 4）—— 与 init 里的 `fw` 同源，别各写一份 */
    private fun filterPanelBtnW() = MarketPanelBackground.filterWidth(width) - 8

    /**
     * 面板的**实际高度** = 内容高度与屏幕可用高度的较小值（不戳出屏幕底边）。
     * 展开分类网格时往下长、长到把网格包住为止（网格自身**限高 + 整行滚动**，见 [gridMaxRows]）。
     */
    private fun filterPanelHeight(): Int {
        val contentBottom = if (categoryGridOpen) gridBottomY() else CATEGORY_BTN_Y + 20
        return minOf(contentBottom - 2 + 10, height - 34)
    }

    // ── 分类网格的几何（全部按当前窗口现算，resize 后自动跟）──

    /** 格子边长（4 列铺满内容宽；窄屏自动变小，兜底 16） */
    private fun gridCell() = ((filterPanelBtnW() - (GRID_COLS - 1) * GRID_GAP) / GRID_COLS).coerceAtLeast(16)

    /** 网格首行的 y：分类按钮下方 4px */
    private fun gridTopY() = CATEGORY_BTN_Y + 24

    /** 网格总行数（含「不限」那一格） */
    private fun gridRows() = (categoryButtons.size + GRID_COLS - 1) / GRID_COLS

    /** 一屏最多铺几行 —— 往下不越过屏幕底部留白（32，与主面板同一口径） */
    private fun gridMaxRows(): Int {
        val avail = (height - 32) - gridTopY() - 10
        return maxOf(1, avail / (gridCell() + GRID_GAP))
    }

    private fun gridVisibleRows() = minOf(gridRows(), gridMaxRows())
    private fun gridMaxScrollRow() = maxOf(0, gridRows() - gridVisibleRows())
    private fun gridBottomY() = gridTopY() + gridVisibleRows() * (gridCell() + GRID_GAP)

    /** 第 i 格（0 = 不限）的 x —— 与滚动无关 */
    private fun gridCellX(i: Int): Int {
        val cell = gridCell()
        val gridW = GRID_COLS * cell + (GRID_COLS - 1) * GRID_GAP
        return MarketPanelBackground.filterLeft(width) + 4 +
            (filterPanelBtnW() - gridW) / 2 + (i % GRID_COLS) * (cell + GRID_GAP)
    }

    /**
     * 分类网格此刻**该不该显示**：面板展开 + 网格展开 + 没弹窗 + 开关动画播完。
     * ⚠ **单一判据** —— 格子的可见性、深色底、滚轮都读它，别各写一份（分散判定必漏）
     */
    private fun gridShown() = filterExpanded && categoryGridOpen &&
        !filterAnim.isActive() && selectedEntry == null && cancelEntry == null

    private fun isOverGrid(mx: Double, my: Double): Boolean {
        val fl = MarketPanelBackground.filterLeft(width)
        return mx >= fl && mx < fl + MarketPanelBackground.filterWidth(width) &&
            my >= gridTopY() && my < gridBottomY()
    }

    /** 当前分类的**完整**显示名（不限 = 「不限」）；按钮文字与悬停提示都取它 */
    private fun categoryFullText(): Text {
        val name = categoryFilter?.let { id ->
            com.shusheng.cobblemarket.client.ItemCategories.all().firstOrNull { it.id == id }?.name
        } ?: Text.translatable("cobblemarket.gui.filter_any")
        return Text.translatable("cobblemarket.item.category", name)
    }

    /**
     * 「分类」按钮的文字：「分类： 不限」/「分类： 建筑方块」。
     * ⚠ 分类名是**模组起的**、长度不可控 ⇒ 一律按按钮宽**截断**（完整名走悬停提示，见 [updateControlVisibility]）——
     *   原版按钮文字是居中绘制、**不裁剪**的，不截断就会左右各画出一截。
     */
    private fun categoryButtonText(): Text =
        com.shusheng.cobblemarket.util.TextUtil.truncateStyled(categoryFullText(), filterPanelBtnW() - 8)

    /** 展开 / 收起分类网格 */
    private fun toggleCategoryGrid() {
        categoryGridOpen = !categoryGridOpen
        if (!categoryGridOpen) categoryScrollRow = 0
        updateControlVisibility()
    }

    /**
     * 选中一个分类（null = 不限）：回第一页重新拉取。
     *
     * ⚠ **网格保持展开、滚动位置也不动**（2026-10-03 用户要求：选完就收起来没法连着换几个分类看）——
     * 想收起来点「筛选」开关或「分类」按钮即可。
     */
    private fun selectCategory(id: Identifier?) {
        categoryFilter = id
        categoryButton?.message = categoryButtonText()
        categoryButton?.setTooltip(Tooltip.of(categoryFullText()))
        currentPage = 1
        requestFilterRefresh()
        updateControlVisibility()
    }

    /**
     * 筛选展开/收起：只切可见性 + 起动画，**不重建界面**。
     * ⚠ 重建会清空搜索词与翻页状态（照精灵市场的教训）；代价是开关按钮**自己**的
     *   图标 / 选中态 / 悬停提示要手动更一次（见 [updateFilterToggleButton]）。
     */
    private fun toggleFilters() {
        filterExpanded = !filterExpanded
        // 面板开关动画：展开时从「筛选」按钮那里放大长出来、收起时缩回去（照精灵市场）
        filterAnim.start(filterExpanded)
        updateFilterToggleButton()
        updateControlVisibility()
    }

    /** 筛选开关按钮的外观随 [filterExpanded] 更新：图标（开/关两枚）、选中态、悬停提示 */
    private fun updateFilterToggleButton() {
        val btn = filterToggleButton ?: return
        btn.iconLeft = Identifier.of("cobblemarket",
            if (filterExpanded) "textures/gui/button/filter.png" else "textures/gui/button/filter_close.png")
        btn.setTooltip(Tooltip.of(Text.translatable(
            if (filterExpanded) "cobblemarket.gui.filter_collapse" else "cobblemarket.gui.filter_expand")))
        btn.selectedVisual = filterExpanded
    }

    // 翻页请求在途标志：响应到达前不重复发请求
    private var pageRequestInFlight = false

    // 最近一次列表请求发送时间：补发节奏对齐服务端节流窗口（request_item_market 500ms）
    // 并留 100ms 余量——窗口同宽时网络抖动会让请求恰好落入服务端窗口被静默丢弃
    private var lastListRequestAt = 0L

    // 待翻页目标：点击立即更新页码并把目标页记到这里，tick 在窗口允许时补发请求。
    // 连点合并到最终目标页（中间页不发）；补发后保留目标，响应确认到达才清——
    // 请求若被静默丢弃，1s 兜底复位后 tick 自动重试
    private var pendingPage = 0

    // 筛选变更待发标志：筛选按钮连点/搜索防抖后若仍在服务端节流窗口内，记标志由 tick 补发
    private var pendingFilterRefresh = false

    /** 筛选/搜索等「当前筛选态」请求统一入口：窗口允许立即发，否则排队 tick 补发 */
    private fun requestFilterRefresh() {
        if (!pageRequestInFlight && System.currentTimeMillis() - lastListRequestAt >= PAGE_CLICK_INTERVAL_MS) {
            refreshData()
        } else {
            pendingFilterRefresh = true
        }
    }

    /** 点货币按钮：「玩家间 RP 交易」没开就只给反馈、不切（置灰态按钮同样走这里） */
    private fun onCurrencyClick() {
        // ⚠ 不拦开关：卖家要能切过去下架自己的存量 RP 挂单
        toggleCurrency()
    }

    /**
     * 设置当前币种 —— **唯一入口**：改字段、刷按钮外观、写共享缓存，三件事一起做。
     *
     * ⚠⚠ **别直接写 `currency = ...`**：那样容易只改字段、忘了刷外观 ——
     *   于是玩家**看到的**是主货币、**提交的**却是 RP（或反过来）。
     *   ⚠ RP 的来路由服主决定（**可能是真钱充值来的**，也可能只是任务 / 活动奖励），
     *   一旦不一致，轻则玩家卖亏、重则直接是经济损失（用户 2026-09-28 拍板必须堵死）。
     *   自查：全类 `grep "currency = "` 应**只剩本函数里那一处**。
     */
    private fun setCurrency(value: MarketCurrency) {
        currency = value
        currencyButton?.let { updateCurrencyButton(it) }
        com.shusheng.cobblemarket.client.MarketStateCache.marketCurrency = value
    }

    /** 切主货币 ↔ RP：整屏换成另一半商品，**必须重置页码**（原页码在新币种下多半是空的） */
    private fun toggleCurrency() {
        setCurrency(if (currency == MarketCurrency.POINTS) MarketCurrency.PRIMARY else MarketCurrency.POINTS)
        // 「收款」只在主货币页有意义：它收的是**普通货币**的待收款（RP 收入直接进余额，没有待领账本）
        collectButton?.visible = currency != MarketCurrency.POINTS
        currentPage = 1
        requestFilterRefresh()
    }

    /** 刷新货币按钮外观（三态：RP 图标 / 物品图标 / ₽ —— 见 [updateCurrencyButtonIcon]） */
    private fun updateCurrencyButton(btn: NineSliceButton) =
        updateCurrencyButtonIcon(btn, currency == MarketCurrency.POINTS)

    /**
     * 喵喵支付入口是否出现：消费贷开着 **且这页商品是主货币的**。
     * ⚠ RP 挂单一律不给喵喵（跨币种兑换，方案硬要求）；服务端也会拒，这里只防白点。
     */
    private fun meowthAvailable(entry: ItemEntry?) = entry != null && payAvailable && !entry.isPoints

    private fun updatePageButtons() {
        prevButton?.active = currentPage > 1
        nextButton?.active = currentPage < totalPages
    }

    private fun prevPage() {
        if (currentPage <= 1) return
        currentPage--
        pendingPage = currentPage
        updatePageButtons()
    }

    private fun nextPage() {
        if (currentPage >= totalPages) return
        currentPage++
        pendingPage = currentPage
        updatePageButtons()
    }

    private companion object {
        /** 补发请求最小间隔：服务端 request_item_market 节流 500ms + 100ms 余量，防网络抖动边界丢弃 */
        const val PAGE_CLICK_INTERVAL_MS = 600L
        /** 收款按钮右上角金币角标（48×48 源图按 1/6 缩到 8×8） */
        val COIN_ICON = Identifier.of("cobblemarket", "textures/gui/currency/coin_icon.png")
        /** 待领取按钮右上角红点（48×48 源图按 1/6 缩到 8×8） */
        val RED_DOT = Identifier.of("cobblemarket", "textures/gui/icon/red_dot.png")
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // ⚠ 主面板保持居中不动（不传 leftOverride）—— 筛选面板占用它右侧本来就空着的区域，
        //   这样主面板里所有控件的坐标一个都不用动，开关面板时主面板也不会平移（照精灵市场）
        MarketPanelBackground.draw(context, width, height)
        // 面板展开中、或收起动画还没播完（后者要从目标位置缩回按钮）都要画
        if (filterExpanded || filterAnim.isActive()) {
            val fl = MarketPanelBackground.filterLeft(width)
            val panelW = MarketPanelBackground.filterWidth(width)
            val ph = filterPanelHeight()
            val panelCx = fl + panelW / 2f
            val panelCy = 2f + ph / 2f
            // 动画锚点 = 「筛选」按钮中心；按钮还没建时退化成"原地不动"（不影响正常开关）
            val btnCx = filterToggleButton?.let { it.x + it.width / 2f } ?: panelCx
            val btnCy = filterToggleButton?.let { it.y + it.height / 2f } ?: panelCy
            val animating = filterAnim.applyTransform(context, btnCx, btnCy, panelCx, panelCy)
            // ⚠ 收起动画的**最后一帧**：applyTransform 返回 false（进度到 1、内部刚清掉动画状态）
            //   —— 那一帧**不能画**，画了就是"全尺寸面板闪一下"（精灵市场 2026-09-24 实测报过）。
            //   展开状态（filterExpanded）则必须照画：动画播完后 isActive 恒为 false，跳过就再也不画了
            if (filterExpanded || animating) {
                MarketPanelBackground.draw(
                    context, width, height,
                    panelWidth = panelW,
                    leftOverride = fl,
                    // 高度由内容定，但**不超屏幕**（小屏 / 自动 GUI 档位下取屏幕可用高度）
                    heightOverride = ph,
                )
                if (animating) filterAnim.restore(context)
            }
        }
        // 分类网格的深色垫底：格子按钮的背景是半透明的，不垫底的话面板底纹会从格子里透上来
        //（照其余界面「展开列表」的做法）。⚠ 判据与格子的可见性**同源**（gridShown）
        if (gridShown()) {
            val fl = MarketPanelBackground.filterLeft(width)
            context.fill(
                fl + 2, gridTopY() - 2,
                fl + MarketPanelBackground.filterWidth(width) - 2, gridBottomY(),
                0xF0101010.toInt()
            )
            renderGridScrollbar(context)
        }
    }

    /** 分类网格的滚动条（**不可拖**，照 ServerConfigScreen 同款）：只在格子铺不下时才画 */
    private fun renderGridScrollbar(context: DrawContext) {
        val maxScroll = gridMaxScrollRow()
        if (maxScroll <= 0) return
        val x = MarketPanelBackground.filterLeft(width) + MarketPanelBackground.filterWidth(width) - 4
        val top = gridTopY()
        val trackH = gridVisibleRows() * (gridCell() + GRID_GAP)
        val thumbH = maxOf(12, trackH * gridVisibleRows() / gridRows())
        val thumbY = top + (trackH - thumbH) * categoryScrollRow / maxScroll
        context.fill(x, top, x + 2, top + trackH, 0x40FFFFFF)
        context.fill(x, thumbY, x + 2, thumbY + thumbH, 0xC0FFFFFF.toInt())
    }

    override fun mouseScrolled(
        mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double
    ): Boolean {
        // 鼠标停在展开的分类网格上时滚的是网格（**整行**滚，保证格子始终对齐）
        if (gridShown() && gridMaxScrollRow() > 0 && isOverGrid(mouseX, mouseY)) {
            categoryScrollRow = (categoryScrollRow - verticalAmount.toInt()).coerceIn(0, gridMaxScrollRow())
            updateControlVisibility()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    /**
     * 购买 / 下架弹窗是**模态**的：打开时把下层控件全部藏掉。
     *
     * ⚠ 必须 `visible = false` 而不是「不画就行」—— 原版 `ClickableWidget` 只要 visible 就照常
     * 检测 hover 并弹自己的 tooltip（`active = false` 拦不住），于是「弹窗按钮的位置正好压在某个
     * 下层按钮上」时，鼠标停在弹窗按钮上却弹出下层那条提示
     * （2026-09-19 用户实测，精灵市场先报的，本界面同一套结构一并修）。
     */
    private fun updateControlVisibility() {
        val open = selectedEntry != null || cancelEntry != null
        // ⚠ collectButton 从这一批里**摘出来单独设**：它比别的控件多一道「主货币页才显示」的条件，
        //   混在统一赋值里会被覆盖掉（这就是"逐个点名"类写法的坑）
        listOf(
            currencyButton, searchField, returnsButton, backButton,
            sellAddButton, filterToggleButton, prevButton, nextButton
        ).forEach { it?.visible = !open }
        collectButton?.visible = !open && currency != MarketCurrency.POINTS
        // 开关动画进行中：面板内的按钮先藏起来 —— 它们是 MC 控件、塞不进矩阵、不跟着缩放，
        // 留着会像浮在缩小中的面板外面。播完（isActive 转 false）下一帧自动恢复（照精灵市场）
        val panelVisible = filterExpanded && !filterAnim.isActive() && !open
        // ⚠ 面板内控件是**常驻创建**的：面板收起 / 动画中 / 弹窗打开时都要藏。
        //   这里是一个个点名的写法 —— 往面板里加控件必须补进这份名单（漏了不报错，只是它挂在屏幕上）
        listOf(sortButton, resetButton, mineButton, categoryButton).forEach { it?.visible = panelVisible }
        // 分类网格：只在网格展开时显示，且**只显示落在可视行里**的那几行（滚出去的、没点开的一律藏）。
        // ⚠ 每帧重定位（不只在滚动时）—— 窗口尺寸、面板展开状态变了都靠它兜住
        val gridOn = gridShown()
        val selIdx = selectedCategoryIndex()
        categoryButtons.forEachIndexed { i, btn ->
            val row = i / GRID_COLS - categoryScrollRow
            btn.visible = gridOn && row in 0 until gridVisibleRows()
            btn.selectedVisual = i == selIdx
            if (btn.visible) {
                btn.x = gridCellX(i)
                btn.y = gridTopY() + row * (gridCell() + GRID_GAP)
            }
        }
    }

    /** 当前选中分类在网格里的下标（第 0 格是「不限」）；没选中 / 那个分类不在了 ⇒ 落回 0 */
    private fun selectedCategoryIndex(): Int {
        val id = categoryFilter ?: return 0
        val idx = com.shusheng.cobblemarket.client.ItemCategories.all().indexOfFirst { it.id == id }
        return if (idx < 0) 0 else idx + 1

    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // ⚠ 余额包是**异步**到的（进服首帧缓存可能还空着）⇒ 每帧刷一次按钮外观，包一到就换成物品图标
        currencyButton?.let { updateCurrencyButton(it) }
        // 模态层（弹窗）打开时收掉下层控件 —— 不收的话鼠标停在弹窗按钮上会弹下层按钮的 tooltip
        updateControlVisibility()
        // 弹窗打开时下层的货币图标不画：3D 图标不受遮罩压暗，会比压暗的文字亮一截
        //（看起来像"高亮浮在弹窗上"）—— 见 suppressCurrencyIcons
        com.shusheng.cobblemarket.client.suppressCurrencyIcons = selectedEntry != null || cancelEntry != null
        // 悬停格的物品大图：画在面板之下（面板在 renderBackground 里画，会自然盖住溢出到面板上的部分）。
        // 用**上一帧**的 hoveredSlot——本帧的悬停判定要到 super.render 之后才算出，差一帧肉眼无感。
        // 弹窗打开时不出（与精灵版同一规则）。
        if (selectedEntry == null && cancelEntry == null && hoveredSlot in entries.indices) {
            val hovered = entries[hoveredSlot]
            entryStacks[hovered.id]?.let {
                ItemHoverPreview.render(context, it, width / 2 - 160, height)
            }
        }
        super.render(context, mouseX, mouseY, delta)

        if (selectedEntry != null || cancelEntry != null) {
            return
        }

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        // 收款按钮右上角金币角标（8×8，仅 2px 露在角外，红点通知样式）：有待收款时显示
        // （弹窗打开时此段之前已 return，角标随下层一起隐藏）。
        // ⚠ 还要判**当前页的币种**：待收款只有主货币那一份账（RP 收入直接进余额），
        //   RP 页连「收款」按钮和待收款那行都不显示 —— 角标是在这里**单独按坐标画**的，
        //   漏判就会出现「按钮没了、右上角那枚金币还在」（2026-09-28 用户实测报的）
        if (pendingBalance > 0 && currency != MarketCurrency.POINTS) {
            // ⚠ 角标跟随货币（2026-09-29）：虚拟货币仍是那枚金币图，物品本位货币改画**那件物品的图标** ——
            //   否则玩家看到「有待收款 + 一个 ₽ 金币」会以为收的是主货币
            val unitStack = com.shusheng.cobblemarket.client.currencyUnitStack()
            if (unitStack == null) {
                context.matrices.push()
                context.matrices.translate((leftX + 50 - 6).toDouble(), (13 - 2).toDouble(), 0.0)
                context.matrices.scale(1f / 6f, 1f / 6f, 1f)
                context.drawTexture(COIN_ICON, 0, 0, 0f, 0f, 48, 48, 48, 48)
                context.matrices.pop()
            } else {
                // 12×12（与行内单位同尺寸），中心对齐原来那枚 8×8 金币图的中心
                com.shusheng.cobblemarket.client.drawCurrencyItemIcon(context, unitStack, leftX + 42, 9, 12)
            }
        }
        // 待领取按钮右上角红点（与收款角标同位置样式）：有待领取物品时显示
        if (pendingReturns > 0) {
            context.matrices.push()
            context.matrices.translate((leftX + 102 - 6).toDouble(), (13 - 2).toDouble(), 0.0)
            context.matrices.scale(1f / 6f, 1f / 6f, 1f)
            context.drawTexture(RED_DOT, 0, 0, 0f, 0f, 48, 48, 48, 48)
            context.matrices.pop()
        }

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.title").formatted(Formatting.GOLD),
            centerX, 14, 0xFFFFFF)

        // Page indicator（右对齐）—— 先算出来，余额行才知道自己还剩多少可用宽
        val pageText = Text.translatable("cobblemarket.gui.page", currentPage, totalPages).formatted(Formatting.GRAY)
        val pageW = textRenderer.getWidth(pageText)

        // 余额 + 待收款：文字标签默认色，金额蓝/绿（2026-08-24 拍板）。⚠ 与精灵市场同一套三级降级：
        // 两段都没有宽度防护时，金额一长就压住页码（2026-09-21 用户报：两个界面显示不一致）。
        // 可用宽 = 面板宽 − 页码宽 − 左右留白；待收款为 0 整段不显示 → 金额换缩写 → 截断兜底
        // ⚠ 余额跟着**当前看的币种**走（2026-09-28）；**待收款只在主货币页显示** ——
        //   RP 没有待领账本（收入直接进余额，见 CurrencyService.creditSeller），
        //   在 RP 页显示它等于谎称"有 RP 没领"
        val isPoints = currency == MarketCurrency.POINTS
        val cache = com.shusheng.cobblemarket.client.BalanceCache
        val shownBal = if (isPoints) cache.pointsBalance else cache.balance
        // ⚠ 金额与货币单位**拆开画**（2026-09-29）：物品本位货币时单位是**物品图标**，拼不进字符串。
        //   词条里只放金额，单位由 drawCurrencyUnit 补在金额右侧；行宽 = 词条宽 + 空格 + 单位宽。
        //   ⚠ 与精灵市场（MarketScreen）同一处画法，改一处要改另一处
        val unitGapW = textRenderer.getWidth(" ")
        val balUnitW = unitGapW + currencyUnitWidth(textRenderer, isPoints)
        val avail = panelWidth - 10 - pageW
        val balText: Text? = shownBal.takeIf { it.isNotEmpty() }?.let {
            Text.translatable("cobblemarket.gui.balance", Text.literal(it).formatted(Formatting.GOLD))
        }
        val balW = balText?.let { textRenderer.getWidth(it) + balUnitW + 4 } ?: 0
        fun pendingLine(amount: String): Text = Text.translatable("cobblemarket.gui.pending_balance",
            Text.literal(amount).formatted(Formatting.GREEN))
        val pendingFull: Text? = if (!isPoints && pendingBalance > 0)
            pendingLine(com.shusheng.cobblemarket.client.formatBalanceLong(pendingBalance)) else null
        val pendingText: Text? = pendingFull?.takeIf { balW + textRenderer.getWidth(it) + balUnitW <= avail }
            ?: pendingFull?.let { pendingLine(com.shusheng.cobblemarket.client.formatPriceShortLong(pendingBalance)) }

        if (balText != null) {
            val drawn = context.drawTextWithShadow(textRenderer, balText, leftX, 31, 0xFFFFFF)
            drawCurrencyUnit(context, textRenderer, isPoints, drawn + unitGapW, 31, 0xFFAA00)
        }
        pendingText?.let {
            // 截断预算按「整行剩余」算（balW 里已经含单位那份，别重复扣）
            val shown = com.shusheng.cobblemarket.util.TextUtil.truncateStyled(it, (avail - balW).coerceAtLeast(0))
            val drawn = context.drawTextWithShadow(textRenderer, shown, leftX + balW, 31, 0xFFFFFF)
            // ⚠ 待收款**只在主货币页显示**（上面 `!isPoints` 判过）⇒ 单位恒主货币
            drawCurrencyUnit(context, textRenderer, false, drawn + unitGapW, 31, 0x55FF55)
        }
        context.drawTextWithShadow(textRenderer, pageText, leftX + panelWidth - 4 - pageW, 32, 0xFFFFFF)

        if (entries.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.item.empty").formatted(Formatting.GRAY),
                centerX, getGridStartY() + 40, 0xFFFFFF)
        }

        val dividerY = getGridStartY() - 4
        drawPanelDivider(context, leftX + panelWidth / 2, dividerY)
        // 底部分割线：最多显示行数的网格最后一行下方 4px（与顶部对称），分页按钮在其与背景底边之间居中
        val gridBottom = getGridStartY() + rows() * (slotSize + gap)
        drawPanelDivider(context, leftX + panelWidth / 2, gridBottom + 4)

        val cols = columns()
        val rows = rows()
        val displayEntries = entries
        val gridOffsetX = (panelWidth - (cols * slotSize + (cols - 1) * gap)) / 2

        hoveredSlot = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY >= getGridStartY()) {
            val col = (mouseX - leftX - gridOffsetX) / (slotSize + gap)
            val row = (mouseY - getGridStartY()) / (slotSize + gap)
            val idx = row * cols + col
            if (col in 0 until cols && row in 0 until rows && idx in displayEntries.indices) hoveredSlot = idx
        }

        displayEntries.take(cols * rows).forEachIndexed { index, entry ->
            val col = index % cols
            val row = index / cols
            val x = leftX + gridOffsetX + col * (slotSize + gap)
            val y = getGridStartY() + row * (slotSize + gap)

            val rowState = if (index == hoveredSlot) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, x, y, slotSize, slotSize, rowState, ROW_BACKGROUND_TEX_H)

            // 弹窗打开时本函数已在开头 return，此处无需图标条件（不会在弹窗下渲染）
            entryStacks[entry.id]?.let { stack ->
                drawItemWithBar(context, stack, x + (slotSize - 16) / 2, y + (slotSize - 16) / 2)
            }

            // 数量：右上角、右对齐。y+3（原 y+4）—— 再低就跟物品图标顶部贴死了（2026-09-21 用户报）
            val countText = "×${entry.count}"
            context.drawText(textRenderer, countText, x + slotSize - 2 - textRenderer.getWidth(countText), y + 3, 0xFFFFFF, false)

            // 价格：金额与单位**拆开画**（物品货币时单位是物品图标，拼不进字符串）。
            // ⚠ 截断预算先把单位那份扣掉 —— 单位是货币符号，被截掉就等于没标货币
            // ⚠ 截断**只作用于金额**（纯数字）：RP 的单位是私用区字形、字体信息挂在 Text 样式上，
            //   但它由 `drawCurrencyUnit` 单独画、不参与截断 —— 所以不会像 `truncateString(整串)`
            //   那样退化成裸字符、渲染成方块（2026-09-28 用户实测报过那个方框）
            // ⚠ 币种取这条挂单自己的（`entry.isPoints`）
            val priceAmount = com.shusheng.cobblemarket.client.formatPriceShort(entry.price)
            val priceUnitW = textRenderer.getWidth(" ") + currencyUnitWidth(textRenderer, entry.isPoints)
            val priceShown = com.shusheng.cobblemarket.util.TextUtil.truncateString(
                priceAmount, (slotSize - 6 - priceUnitW).coerceAtLeast(0)
            )
            context.drawText(textRenderer, priceShown, x + 3, y + slotSize - 10, 0xFFAA00, false)
            drawCurrencyUnit(
                context, textRenderer, entry.isPoints,
                x + 3 + textRenderer.getWidth(priceShown) + textRenderer.getWidth(" "),
                y + slotSize - 10, 0xFFAA00, shadow = false
            )
        }

        if (hoveredSlot in displayEntries.indices) {
            renderItemTooltip(context, displayEntries[hoveredSlot], mouseX, mouseY)
        }
    }

    private fun renderItemTooltip(context: DrawContext, entry: ItemEntry, mouseX: Int, mouseY: Int) {
        // 文本行缓存：悬停同一格时内容不变，只在悬停目标变化时重建（Shift/Ctrl 切换不影响缓存键）
        if (tooltipCacheKey != entry.id) {
            tooltipCacheKey = entry.id
            tooltipCacheLines = buildTooltipLines(entry, TooltipType.BASIC)
            tooltipAdvancedLines = null
            tooltipAdvancedType = null
        }
        // 按住 Shift 展开完整词条 / Ctrl（F3+H 开启）展开调试信息（照原版背包悬停；类型变化重建缓存）
        val advanced = if (com.shusheng.cobblemarket.client.ItemComponentsDisplay.hoverExpanded()) {
            val type = com.shusheng.cobblemarket.client.ItemComponentsDisplay.tooltipTypeForHover()
            if (tooltipAdvancedType != type) {
                tooltipAdvancedType = type
                tooltipAdvancedLines = buildTooltipLines(entry, type)
            }
            tooltipAdvancedLines
        } else {
            tooltipAdvancedType = null
            null
        }
        // 行数按屏幕高钳制：Shift / Ctrl 展开后可能几十行，画不下时末行换成「…（还有 N 行）」——
        // 否则 `height - tooltipHeight` 变负、悬停窗顶部被切到屏幕外（2026-09-26 用户报隐患）
        val lines = clampTooltipRows(advanced ?: tooltipCacheLines, height) { n ->
            Text.translatable("cobblemarket.gui.tooltip_more", n) to 0xAAAAAA
        }

        // ⚠ 价格行还要加单位宽（单位画在行尾、不在文本里）—— 漏了提示框会算窄、图标顶出边框
        val priceUnitW = currencyUnitWidth(textRenderer, entry.isPoints)
        var maxWidth = 0
        lines.forEachIndexed { i, (line, _) ->
            maxWidth = maxOf(maxWidth, textRenderer.getWidth(line) + if (i == tooltipPriceRow) priceUnitW else 0)
        }

        val padding = 4
        val tx = minOf(mouseX + 12, width - maxWidth - 12)
        val tooltipHeight = lines.size * 10 + padding
        val tyAbove = mouseY - tooltipHeight - 4
        val ty = if (tyAbove <= 0) minOf(mouseY + 12, height - tooltipHeight) else tyAbove

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, lines.size * 10 + 2 * padding, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, (line, color) ->
            val w = context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
            // 价格行：货币单位补在行尾（物品货币时是那件物品的图标）。
            // ⚠ w 是**文本结束坐标**（不是宽度），直接拿它当起点；行被截断时不补
            if (i == tooltipPriceRow) {
                drawCurrencyUnit(context, textRenderer, entry.isPoints, w, ty + i * 10, 0xFFAA00)
            }
        }
        context.matrices.pop()
    }

    private fun isInputFieldFocused() = focused?.let { f -> f === searchField || f === buyCountField } ?: false

    /** 悬停词条构建（BASIC 常驻 / ADVANCED 按 Shift 构建）：物品词条 + 卖家 + 价格 + 数量 */
    private fun buildTooltipLines(entry: ItemEntry, type: TooltipType): List<Pair<Text, Int>> {
        val stack = entryStacks[entry.id]
        val built = mutableListOf<Pair<Text, Int>>()
        if (stack != null) {
            built.addAll(com.shusheng.cobblemarket.client.ItemComponentsDisplay.itemTooltip(stack, client?.player, type).map { it to 0xFFFFFF })
        } else {
            built.add(Text.literal(entry.itemId) to 0xFFFFFF)
        }
        built.add(Text.translatable("cobblemarket.gui.tooltip_seller").append(entry.sellerName) to 0xFFFFFF)
        // 价格行：标签默认色，金额段蓝色（2026-08-24 拍板）。
        // ⚠ 货币单位**不拼进文本**，由渲染循环补在行尾（物品货币时是那件物品的图标、RP 时是字形图标）——
        //   先记下标（它**不是**末行，后面还有数量行；且 Shift 展开时行数会变，所以每次构建都重记）
        tooltipPriceRow = built.size
        built.add(Text.translatable("cobblemarket.item.tooltip_price").append(
            Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ").formatted(Formatting.GOLD)
        ) to 0xFFFFFF)
        built.add(Text.literal("×${entry.count}") to 0xFFFFFF)
        return built
    }

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true ||
        buyCountField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (cancelEntry != null) {
            handleCancelDialogClick(mouseX.toInt(), mouseY.toInt())
            return true
        }
        if (selectedEntry != null) {
            val wasInInput = isInputFieldFocused()
            val result = super.mouseClicked(mouseX, mouseY, button)
            if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
                focused = null
            }
            return true
        }
        val displayEntries = entries
        if (hoveredSlot in displayEntries.indices) {
            val entry = displayEntries[hoveredSlot]
            val playerUuid = client?.player?.uuid
            if (entry.sellerUuid == playerUuid) {
                openCancelDialog(entry)
            } else {
                openBuyDialog(entry)
            }
            return true
        }
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        return result
    }

    /** 弹窗打开时隐藏主界面全部控件：widget 文字画在遮罩（addDrawable）之上，visible=false 才能防穿透与误点 */
    private fun setMainControlsVisible(visible: Boolean) {
        collectButton?.visible = visible && currency != MarketCurrency.POINTS
        returnsButton?.visible = visible
        backButton?.visible = visible
        currencyButton?.visible = visible
        searchField?.visible = visible
        sellAddButton?.visible = visible
        filterToggleButton?.visible = visible
        // 面板内两枚也一并收掉（面板收起时它们本来就是 hidden，这里 `visible=true` 不会让它们冒出来：
        // render 开头的 updateControlVisibility 每帧按 filterExpanded 重设）
        sortButton?.visible = visible
        resetButton?.visible = visible
        mineButton?.visible = visible
        prevButton?.visible = visible
        nextButton?.visible = visible
    }

    private fun openBuyDialog(entry: ItemEntry) {
        selectedEntry = entry
        setMainControlsVisible(false)
        val centerX = width / 2
        // 位置一律读 [buyDialogLayout]（与渲染同源 —— 别再各算一遍，那正是「建与重摆两处各写一遍」的老毛病）
        val L = buyDialogLayout(entry)

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderBuyDialogBackground(context)
            }
        })

        buyCountField = TextFieldWidget(textRenderer, centerX - 80, L.fieldY, 100, 16, Text.literal(""))
        buyCountField?.setPlaceholder(Text.translatable("cobblemarket.item.buy_count"))
        buyCountField?.setTextPredicate { it.length <= 4 && it.all { c -> c.isDigit() } }
        // 重新输入时清除校验失败提示
        buyCountField?.setChangedListener { buyErrorText = null }
        addDrawableChild(buyCountField)

        // 三按钮**并排**（照精灵购买弹窗）：喵喵支付放**中间**，不再另起一行 ——
        // 三枚 80 按钮 + 4px 间隙 = 248，弹窗放得下（用户 2026-09-26 报「为什么不放中间」）
        // ⚠ 用 meowthAvailable(entry)：RP 商品不给喵喵入口（1.3.0 起），此时退回两按钮居中
        val btnW = 80
        val threeButtons = meowthAvailable(entry)
        val confirmX = if (threeButtons) centerX - btnW - 44 else centerX - btnW - 5
        val payX = centerX - 40
        val cancelX = if (threeButtons) centerX + 44 else centerX + 5
        buyConfirmButton = NineSliceButton(
            confirmX, L.buttonY, btnW, 20,
            Text.translatable("cobblemarket.gui.buy"),
            { confirmBuy() }
        )
        addDrawableChild(buyConfirmButton)
        buyCancelButton = NineSliceButton(
            cancelX, L.buttonY, btnW, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeBuyDialog() }
        )
        addDrawableChild(buyCancelButton)

        // 喵喵支付入口（点击进独立 MeowthPayScreen 选方案）；消费贷关、或这页是 RP 商品时不显示
        if (meowthAvailable(entry)) {
            buyPayButton = NineSliceButton(
                payX, L.buttonY, btnW, 20,
                Text.translatable("cobblemarket.buy_confirm.meowth_pay"),
                { openMeowthPay() }
            )
            addDrawableChild(buyPayButton)
        }

        // 拉消费贷开关（喵喵支付按钮显示与弹窗高度依据）
        requestCreditInfo()
    }

    /** 词条行数上限（与「查看待交付」同一口径）—— 再多也只显示这么多，免得弹窗比屏幕还高 */
    private val MAX_INFO_ROWS = 12

    /**
     * 购买弹窗里要显示的词条行。
     *
     * ⚠ **`BASIC` 只挡得住普通物品** —— 原先这里写着「不跟随 Shift 展开、弹窗高度恒定」，
     *   但 **TM（招式学习器）这类物品的词条是 Cobblemon 自己生成的，它自己会读 Shift**，
     *   `TooltipType.BASIC` 根本拦不住 ⇒ 按住 Shift 词条就变多、弹窗跟着长高
     *  （2026-09-29 用户截图证实）。**所以弹窗高度并不是恒定的**，控件必须每帧重贴
     *  （见 [syncBuyDialogControls]）。
     */
    private fun buyInfoLines(entry: ItemEntry?): List<Text> {
        val rm = client?.world?.registryManager ?: return emptyList()
        val stack = entry?.let { ItemStack.fromNbtOrEmpty(rm, it.itemNbt) } ?: return emptyList()
        // ⚠ **丢掉词条第一行** —— `itemTooltip` 的第一行就是物品名，而弹窗已经在图标下方
        //   单独画了一行（[renderBuyDialogBackground]，按稀有度着色）。不丢就是同一个名字显示两遍
        //   （用户 2026-09-26 报「同时存在两个物品名」）。
        //   丢词条这行而不是丢弹窗那行：物品名留在图标正下方、**内容位置一点不动**
        //   （用户 2026-09-26 拍板过「只改宽度别动内容位置」）。
        return com.shusheng.cobblemarket.client.ItemComponentsDisplay
            .itemTooltip(stack, client?.player, TooltipType.BASIC)
            .drop(1)
    }

    /**
     * 实际显示的词条行数：同时受 [MAX_INFO_ROWS] 与**屏幕高**约束
     *（GUI 缩放设「自动」时逻辑屏高可能只有 270，弹窗固定占掉 140，剩下的才轮到词条）。
     */
    private fun infoRows(entry: ItemEntry?): Int {
        val byScreen = ((height - 140 - 20) / 10).coerceAtLeast(0)
        return buyInfoLines(entry).size.coerceAtMost(minOf(MAX_INFO_ROWS, byScreen))
    }

    /**
     * 购买弹窗的**唯一布局来源** —— 渲染与建控件都读它，别在两处各写一遍 y。
     *
     * 布局：标题 / 图标 / 物品名占到头 62 → 词条 N 行 → 数量标签 → 输入框 16 → 总价 → 按钮 20 → 底部 10。
     * ⚠ 此前高度**写死 170/210**，而内容只排到 92 ⇒ 总价与按钮之间白空 36~48px
     *   （用户 2026-09-26 报「总价这一行和按钮这一行离得那么远干嘛呢」）。
     */
    private data class BuyLayout(
        val dialogY: Int, val infoTop: Int, val labelY: Int,
        val fieldY: Int, val totalY: Int, val buttonY: Int
    )

    private fun buyDialogLayout(entry: ItemEntry?): BuyLayout {
        val dialogY = height / 2 - buyDialogH(entry) / 2
        val infoTop = dialogY + 62
        val labelY = infoTop + infoRows(entry) * 10 + 6
        val fieldY = labelY + 10
        val totalY = fieldY + 22
        val buttonY = totalY + 10
        return BuyLayout(dialogY, infoTop, labelY, fieldY, totalY, buttonY)
    }

    /**
     * 把弹窗里的控件贴到**当前**布局上 —— 词条行数会变（Shift 展开），所以布局每帧都得重贴一次。
     *
     * ⚠ 传进来的是渲染**已经在用**的那份 [BuyLayout]，别在这儿再调一次 [buyDialogLayout]()
     *   —— 同一套坐标算两遍，正是历史上「建与重摆两处各写一遍」那个老毛病的温床。
     * ⚠ 只重贴 `y`：控件的 x 由 `centerX` 的固定偏移决定（见 [openBuyDialog]），与弹窗宽度无关；
     *   词条展开只改高度。
     */
    private fun syncBuyDialogControls(L: BuyLayout) {
        buyCountField?.y = L.fieldY
        buyConfirmButton?.y = L.buttonY
        buyCancelButton?.y = L.buttonY
        buyPayButton?.y = L.buttonY
    }

    /** 购买弹窗高度：**按内容算**（= 按钮底 + 距边框 10px）；词条行数决定它 */
    private fun buyDialogH(entry: ItemEntry?): Int = 140 + infoRows(entry) * 10

    /** 消费贷开关快照（打开购买弹窗时拉取）：状态变化时重建弹窗控件（含输入恢复）+ 回写缓存 */
    fun onCreditInfo(payload: CreditInfoPayload) {
        val available = payload.consumerLoanEnabled
        com.shusheng.cobblemarket.client.FinanceCache.consumerLoanEnabled = available
        if (available == payAvailable) return
        payAvailable = available
        val entry = selectedEntry ?: return
        val countText = buyCountField?.text ?: ""
        closeBuyDialog()
        openBuyDialog(entry)
        buyCountField?.text = countText
    }

    private fun closeBuyDialog() {
        selectedEntry = null
        buyDialogIconOrigin = null
        buyCountField = null
        buyPayButton = null
        buyConfirmButton = null
        buyCancelButton = null
        clearChildren()
        init()
    }

    /** 打开喵喵支付独立界面（数量校验同现金购买；确认回调发喵喵支付物品购买请求） */
    private fun openMeowthPay() {
        val entry = selectedEntry ?: return
        val count = buyCountField?.text?.toIntOrNull() ?: run {
            playFailSound()
            return
        }
        if (count <= 0 || count > entry.count) {
            buyErrorText = Text.translatable("cobblemarket.item.buy_too_many").formatted(Formatting.RED)
            buyErrorUntil = System.currentTimeMillis() + 2000
            playFailSound()
            return
        }
        // 物品名走**稀有度色** —— `Registries.ITEM.get(id).name` 不带稀有度样式，
        // 而它会被 append 进 MeowthPayScreen 的 `itemDesc` 一路画出去（2026-09-26 用户报的白字源头之一）
        val itemName = com.shusheng.cobblemarket.util.ItemNameText.rarityColoredNameById(entry.itemId)
            ?: Text.literal(entry.itemId)
        client?.setScreen(MeowthPayScreen(
            itemDesc = Text.literal("$count×").append(itemName),
            amount = entry.price.toLong() * count,
            onConfirm = { plan ->
                sendToServer(BuyItemPayload(entry.id, count, plan))
                client?.setScreen(ItemMarketScreen())
            },
            onBack = { client?.setScreen(ItemMarketScreen()) }
        ))
    }

    private fun openCancelDialog(entry: ItemEntry) {
        cancelEntry = entry
        setMainControlsVisible(false)
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderCancelDialogBackground(context, mouseX, mouseY)
            }
        })
    }

    private fun closeCancelDialog() {
        cancelEntry = null
        clearChildren()
        init()
    }

    private fun handleCancelDialogClick(mx: Int, my: Int) {
        val centerX = width / 2
        val dialogH = 170
        val dialogY = height / 2 - dialogH / 2
        val btnW = 80
        val btnH = 20
        val btnY = dialogY + 128
        val confirmX = centerX - 85
        val cancelX = centerX + 5
        if (mx in confirmX..(confirmX + btnW) && my in btnY..(btnY + btnH)) {
            playClickSound()
            confirmCancel()
        } else if (mx in cancelX..(cancelX + btnW) && my in btnY..(btnY + btnH)) {
            playClickSound()
            closeCancelDialog()
        }
    }

    private fun playClickSound() {
        client?.soundManager?.play(PositionedSoundInstance.master(
            SoundEvent.of(Identifier.of("cobblemarket", "button_click")),
            1.0f
        ))
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val cancel = cancelEntry
        val buy = selectedEntry
        val buyCount = buyCountField?.text ?: ""
        super.resize(client, width, height)
        if (cancel != null) {
            cancelEntry = null
            openCancelDialog(cancel)
        } else if (buy != null) {
            selectedEntry = null
            openBuyDialog(buy)
            buyCountField?.text = buyCount
        }
    }

    private fun confirmCancel() {
        val entry = cancelEntry ?: return
        sendToServer(CancelItemPayload(entry.id))
        closeCancelDialog()
    }

    private fun renderCancelDialogBackground(context: DrawContext, mouseX: Int, mouseY: Int) {
        val entry = cancelEntry ?: return
        val registry = client?.world?.registryManager
        val stack = registry?.let { ItemStack.fromNbtOrEmpty(it, entry.itemNbt) }
        // ── 尺寸全部交给 [DialogGeometry]（样板写法：宽度取最宽的那项、高度取内容底）──
        // 内容宽：物品名 / 提示行 / 按钮行（两枚 80 按钮 + 中间 10px）三者取最大
        val contentW = maxOf(
            stack?.let { textRenderer.getWidth(com.shusheng.cobblemarket.util.TextUtil.rarityColoredName(it)) } ?: 0,
            textRenderer.getWidth(Text.translatable("cobblemarket.item.cancel_hint", entry.count)),
            170
        )
        // 内容高：图标（y+26、16 高）→ 物品名（y+46）→ 提示（y+66），底 ≈ 76
        val g = DialogGeometry.of(width, height, contentW = contentW, contentNeeded = 76)
        val centerX = g.x + g.w / 2

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 100.0)
        drawScreenDimMask(context, width, height)
        context.matrices.pop()

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 200.0)
        drawDialogFrame(context, g, Text.translatable("cobblemarket.item.cancel_title"), textRenderer)

        if (stack != null) {
            drawItemWithBar(context, stack, centerX - 8, g.y + 26)
            // 物品名照物品栏悬浮第一行按稀有度着色
            context.drawCenteredTextWithShadow(textRenderer,
                com.shusheng.cobblemarket.util.TextUtil.rarityColoredName(stack), centerX, g.y + 46, 0xFFFFFF)
        }

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.cancel_hint", entry.count).formatted(Formatting.GRAY),
            centerX, g.y + 66, 0xFFFFFF)

        val btnW = 80
        val btnH = 20
        // 按钮**钉在几何助手算好的底部位置**（内容再长也推不走）
        val btnY = g.buttonY
        val confirmX = centerX - 85
        val cancelX = centerX + 5
        val confirmHover = mouseX in confirmX..(confirmX + btnW) && mouseY in btnY..(btnY + btnH)
        val cancelHover = mouseX in cancelX..(cancelX + btnW) && mouseY in btnY..(btnY + btnH)
        drawNineSlice(context, BUTTON_TEXTURE, confirmX, btnY, btnW, btnH, if (confirmHover) 1 else 0, BUTTON_TEX_H)
        drawNineSlice(context, BUTTON_TEXTURE, cancelX, btnY, btnW, btnH, if (cancelHover) 1 else 0, BUTTON_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("cobblemarket.item.cancel_confirm"), confirmX + btnW / 2, btnY + (btnH - 8) / 2, 0xFFFFFF)
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("cobblemarket.buy_confirm.cancel"), cancelX + btnW / 2, btnY + (btnH - 8) / 2, 0xFFFFFF)
        context.matrices.pop()
    }

    private fun confirmBuy() {
        val entry = selectedEntry ?: return
        val count = buyCountField?.text?.toIntOrNull() ?: run {
            playFailSound()
            return
        }
        if (count <= 0 || count > entry.count) {
            // 无效数量：红字提示 2 秒 + fail 音效（原先静默返回，玩家无感知）
            buyErrorText = Text.translatable("cobblemarket.item.buy_too_many").formatted(Formatting.RED)
            buyErrorUntil = System.currentTimeMillis() + 2000
            MinecraftClient.getInstance().soundManager.play(
                PositionedSoundInstance.master(
                    SoundEvent.of(Identifier.of("cobblemarket", "fail")),
                    1.0f,
                    0.5f // 音量（两参重载固定 0.25 太轻，档位同 playResultSound）
                )
            )
            return
        }
        // 登记物品庆祝动画的起飞起点 —— 就是本弹窗那个物品图标的位置与大小（关弹窗前先抓下来）。
        // 起点带 3 秒过期（见 ItemCelebrationAnimation.setPendingOrigin），购买没成功也不会留脏值。
        buyDialogIconOrigin?.let(ItemCelebrationAnimation::setPendingOrigin)
        sendToServer(BuyItemPayload(entry.id, count, -1))
        closeBuyDialog()
    }

    /** 总价 Long 计算：单价 9 位 × 数量 4 位可超 Int 上限，Int 相乘会溢出显示负数 */
    private fun buyTotal(): Long {
        val entry = selectedEntry ?: return 0
        val count = buyCountField?.text?.toIntOrNull() ?: 0
        return entry.price.toLong() * count
    }

    private fun renderBuyDialogBackground(context: DrawContext) {
        val entry = selectedEntry ?: return
        val centerX = width / 2
        // 物品栈先建：图标与物品名都用它渲染，下面的内容测宽也要用（别建两次）
        val registry = client?.world?.registryManager
        val dialogStack = registry?.let { ItemStack.fromNbtOrEmpty(it, entry.itemNbt) }
        // 宽度**按内容算**：图标 / 物品名 / 按钮都居中在 ±85 内，而两行文字与输入框
        // 左对齐于 centerX − 80（位置保持不动，用户 2026-09-26 拍板）⇒ 左半区固定要 85，
        // 右半区取「文字行宽 − 80」与 85 的较大者；各留 12px 边距。
        // 原先写死 300，内容才 ~170 宽，两侧各白留 65。
        val countLabelW = textRenderer.getWidth(
            Text.translatable("cobblemarket.item.buy_count").string + "（" +
                Text.translatable("cobblemarket.item.sell_max").string + " ${entry.count}）"
        )
        // ⚠ 单位**不拼进文本**（画在行尾，见下面的渲染）⇒ 测量要把它补上，否则弹窗算窄
        val totalLabelW = textRenderer.getWidth(
            Text.translatable("cobblemarket.item.buy_total").append(
                Text.literal("${com.shusheng.cobblemarket.client.formatPriceLong(buyTotal())} "))
        ) + currencyUnitWidth(textRenderer, entry.isPoints)
        val itemNameW = dialogStack?.takeIf { !it.isEmpty }
            ?.let { textRenderer.getWidth(com.shusheng.cobblemarket.util.TextUtil.rarityColoredName(it)) } ?: 0
        // 按钮行也要算进来（三枚 80 并排 = 跨度 248，见 [openBuyDialog]）：漏了它，弹窗只按文字
        // 算到 ~194 宽，三个按钮就戳出左右边框（用户 2026-09-26 报「底部三个按钮超出左右弹窗」）。
        // 两按钮时跨度 170 ⇒ 半宽 85，与原先那个固定下限同值，行为不变。
        val buttonsHalfW = if (meowthAvailable(entry)) 124 else 85
        // ⚠ **词条**是最容易漏的一项 —— 英文词条可长达一整句（如金属涂层那条），
        //   之前没算它 ⇒ 长词条直接画出弹窗两侧（2026-10-03 用户报出）
        val infoW = buyInfoLines(entry).take(MAX_INFO_ROWS).maxOfOrNull { textRenderer.getWidth(it) } ?: 0
        // ⚠ 文字与词条已**居中**（2026-10-03 由「左对齐 centerX−80」改）⇒ 各项取**全宽的一半**（原先才要 −80）
        val halfW = maxOf(buttonsHalfW, countLabelW / 2, totalLabelW / 2, itemNameW / 2, infoW / 2)
        val dialogW = (halfW * 2 + 24)
            .coerceAtMost(width - 40)
            // ⚠ 下限：按钮行本身**不能被屏幕钳制吃掉**，否则小窗口下三枚按钮又戳出弹窗
            //（照精灵市场购买弹窗那一套，见 [MarketScreen] 的 `btnRowW`）
            .coerceAtLeast(maxOf(200, buttonsHalfW * 2 + 24))
        // 高度：**按内容算**（词条行数决定）—— 与宽度一样不再写死。见 [buyDialogLayout]
        val L = buyDialogLayout(entry)
        val dialogH = buyDialogH(entry)
        // ⚠ **每帧把控件贴回当前布局**：词条会随 Shift 展开变多（TM 那类物品的词条是 Cobblemon
        //   自己生成的、它自己读 Shift，`TooltipType.BASIC` 拦不住）⇒ 弹窗长高、文字整体下移；
        //   而控件是**打开弹窗时定位一次**的 ⇒ 不重贴就钉在原地 ——「总价」跟着走了、四个控件没动
        //  （2026-09-29 用户截图报出）。出价弹窗一直就是这么做的（`bidField?.y = …`），这里补同一套
        syncBuyDialogControls(L)
        val dialogX = centerX - dialogW / 2
        val dialogY = L.dialogY

        drawScreenDimMask(context, width, height)
        // ⚠ 弹窗自己的货币图标不受门禁管（同 EntryBadgeRenderer.drawInfoLines 的说明）：
        //   遮罩是这个界面自己画的，不关掉的话弹窗里的单位会被自己的遮罩挡住
        com.shusheng.cobblemarket.client.suppressCurrencyIcons = false
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.buy_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        // 购买数量校验失败提示（数量标签上方，2 秒后消失）
        if (buyErrorText != null && System.currentTimeMillis() < buyErrorUntil) {
            context.drawCenteredTextWithShadow(textRenderer, buyErrorText, centerX, L.labelY - 10, 0xFFFFFF)
        }

        if (registry != null && dialogStack != null) {
            drawItemWithBar(context, dialogStack, centerX - 8, dialogY + 26)
            // 记下这个图标的位置（物品庆祝动画要从这里起飞）；物品图标基准 16×16
            buyDialogIconOrigin = ItemCelebrationAnimation.Origin.ofIcon(
                x = centerX - 8, y = dialogY + 26, size = 16
            )
            // 物品名照物品栏悬浮第一行按稀有度着色
            context.drawCenteredTextWithShadow(textRenderer,
                com.shusheng.cobblemarket.util.TextUtil.rarityColoredName(dialogStack), centerX, dialogY + 46, 0xFFFFFF)
        }

        // 物品词条（与其余所有界面一致；居中排在物品名下方）—— 只列 BASIC，见 [buyInfoLines]
        buyInfoLines(entry).take(MAX_INFO_ROWS).forEachIndexed { i, line ->
            context.drawCenteredTextWithShadow(textRenderer, line, centerX, L.infoTop + i * 10, 0xFFFFFF)
        }

        // ⚠ 两行都居中（2026-10-03 用户指出：原先与输入框一起左对齐于 centerX-80 ⇒ 整块偏左 30px）
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.item.buy_count").string + "（" + Text.translatable("cobblemarket.item.sell_max").string + " ${entry.count}）",
            centerX, L.labelY, 0xAAAAAA)
        // 总价单位同样取这条挂单自己的币种（RP 挂单不会出现喵喵行，词条行数决定弹窗高度）
        // ⚠ 货币单位**不拼进文本** —— 补画在行尾（物品货币时是物品图标、RP 时是字形图标）
        // ⚠ 居中：起点 = centerX − (词条宽 + 单位宽)/2（与上面 halfW 的 totalLabelW 同一套算法）
        val totalLine = Text.translatable("cobblemarket.item.buy_total").append(
            Text.literal("${com.shusheng.cobblemarket.client.formatPriceLong(buyTotal())} ").formatted(Formatting.GOLD))
        val totalW = textRenderer.getWidth(totalLine) + currencyUnitWidth(textRenderer, entry.isPoints)
        val totalEndX = context.drawTextWithShadow(textRenderer, totalLine, centerX - totalW / 2, L.totalY, 0xFFFFFF)
        drawCurrencyUnit(context, textRenderer, entry.isPoints, totalEndX, L.totalY, 0xFFAA00)
    }

    fun onMarketResult(payload: MarketResultPayload) {
        if (payload.success) {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.GREEN), false)
            requestFilterRefresh()
        } else {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.RED), false)
        }
    }

    override fun shouldPause() = false
}
