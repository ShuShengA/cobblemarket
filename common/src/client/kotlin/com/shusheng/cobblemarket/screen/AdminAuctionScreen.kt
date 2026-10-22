package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.util.MoveText
import com.shusheng.cobblemarket.util.StatNames
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.shusheng.cobblemarket.client.LoadState
import com.shusheng.cobblemarket.client.clampTooltipRows
import com.shusheng.cobblemarket.client.currencyUnitWidth
import com.shusheng.cobblemarket.client.drawCurrencyUnit
import com.shusheng.cobblemarket.client.icon.PreviewStateHolder
import com.shusheng.cobblemarket.client.heldIconStack
import com.shusheng.cobblemarket.client.heldIconStackForModel
import com.shusheng.cobblemarket.client.iconStackOf
import com.shusheng.cobblemarket.client.PointsStateCache
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_SRC
import com.shusheng.cobblemarket.client.CURRENCY_BUTTON_ICON_SIZE
import com.shusheng.cobblemarket.client.playButtonClickSound
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.PokemonHoverPreview
import com.shusheng.cobblemarket.client.icon.PokemonIconData
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.mojang.authlib.GameProfile
import com.shusheng.cobblemarket.network.AuctionEntry
import com.shusheng.cobblemarket.network.AuctionEventPayload
import com.shusheng.cobblemarket.network.AuctionListDataPayload
import com.shusheng.cobblemarket.network.ForceCancelAuctionPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.RequestAuctionListPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.util.UUID
import com.shusheng.cobblemarket.client.drawItemWithBar

/**
 * 管理员「所有拍卖」管理页。行渲染/悬停/确认弹窗全部照搬拍卖场（AuctionScreen），
 * 区别：点击行打开「强制下架」确认弹窗（两列布局照搬出价弹窗）。
 */
class AdminAuctionScreen : Screen(Text.translatable("cobblemarket.op.auction")) {

    private val panelWidth = 296
    private val rowHeight = 24
    private val iconSize = 20

    private var entries = listOf<AuctionEntry>()
    /** 首包状态：回来前显示「加载中…」，回来后才允许显示「暂无拍卖」 */
    private val loadState = LoadState()
    // 过滤结果缓存：数据/搜索变化时重建，render 每帧只读（避免每帧全量 filter）
    private var filteredCache = listOf<AuctionEntry>()
    // 带索引列表缓存（IndexedValue.index = entries 中的原始索引）：render 行循环每帧 entries.indexOf 是扫描热点，重建过滤缓存时同步构建
    private var indexedFiltered = listOf<IndexedValue<AuctionEntry>>()
    private var hoveredRow = -1
    private var scrollOffset = 0
    private var cancelEntry: AuctionEntry? = null
    private var searchField: net.minecraft.client.gui.widget.TextFieldWidget? = null
    private var backButton: NineSliceButton? = null

    /** 列表在查哪个币种（货币切换按钮控制）—— **只决定查什么**：显示单位一律读条目自己的 [AuctionEntry.isPoints] */
    private var currency = MarketCurrency.PRIMARY

    /** 货币切换按钮（RP 总开关关着时为 null = 压根没建） */
    private var currencyButton: NineSliceButton? = null

    /** 点货币按钮：「玩家间 RP 交易」没开就只给反馈、不切（置灰态按钮同样走这里） */
    /**
     * 设置当前币种 —— **唯一入口**：改字段 + 刷按钮外观一起做。
     *
     * ⚠⚠ **别直接写 `currency = ...`**：只改字段、忘了刷外观，就会出现
     *   「看到的是一半挂单、操作的却是另一半」—— 管理端下架 / 强制取消时同样会出事。
     *   自查：全类 `grep "currency = "` 应**只剩本函数里那一处**。
     * ⚠ 管理端**不碰** `MarketStateCache.marketCurrency`：那是玩家侧市场系界面共用的，
     *   管理端之间各有各的币种，也不该被玩家侧的选择带着走。
     */
    private fun setCurrency(value: MarketCurrency) {
        currency = value
        currencyButton?.let { updateCurrencyButton(it) }
    }

    private fun onCurrencyClick() {
        // ⚠ 管理端**不拦开关**：管理员要能查看并下架存量的 RP 挂单
        setCurrency(if (currency == MarketCurrency.POINTS) MarketCurrency.PRIMARY else MarketCurrency.POINTS)
        // 新币种是**另一份列表**：在新回包前重新显示「加载中…」（同 AuctionScreen）
        loadState.reset()
        // 本地先按新币种过一遍（旧币种条目立刻消失），再向服务端要新币种的全量列表
        rebuildFiltered()
        sendToServer(RequestAuctionListPayload(currency.name))
    }

    /** 刷新货币按钮外观（三态：RP 图标 / 物品图标 / ₽ —— 见 [updateCurrencyButtonIcon]） */
    private fun updateCurrencyButton(btn: NineSliceButton) =
        updateCurrencyButtonIcon(btn, currency == MarketCurrency.POINTS)

    // 到期结算轮询（照搬拍卖场）：存在到期未结算条目时周期重拉，触发服务端结算
    private var lastSettlePoll = 0L

    // 锤子图标动画（照搬拍卖场；管理界面只做图标动画不播音效）
    private val hammerHitUntil = mutableMapOf<UUID, Long>()
    private data class WarnState(val endsAt: Long, val knocks: Int)
    private val warnStates = mutableMapOf<UUID, WarnState>()
    private val settlingUntil = mutableMapOf<UUID, Long>()

    private fun knockFor(remaining: Long): Int = when {
        remaining > 10_000L -> 0
        remaining > 6_000L -> 1
        remaining > 3_000L -> 2
        else -> 3
    }

    private var cancelRenderable: RenderablePokemon? = null
    private val cancelPreviewState = PreviewStateHolder()

    /**
     * 强制下架弹窗自动叫一声的**待播时刻**（0 = 无；替玩家点一下弹窗里的精灵图标）。
     *
     * **不能在 [openCancelDialog] 里直接播**：一是 `addFirstAnimation` 依赖 `state.currentModel`，
     * 而它要等 `drawProfilePokemon` 跑过才有值 —— 这份 state 虽然常驻，但「从没渲染过」时它仍是 null，
     * 于是表现为「第一次开这个弹窗不叫、之后再开才叫」；二是弹窗刚弹出就响，玩家反应不过来声音从哪来。
     * 所以打开时只记「now + [IconCryClick.AUTO_CRY_DELAY_MS]」，渲染处到点才播。
     */
    private var cancelCryAt = 0L
    // ── 强制下架弹窗的**物品词条**（基础 / Shift·Ctrl 展开）—— 照搬出价弹窗那一套 ──
    // ⚠ 原先物品模式左列**只有「物品名 + 数量」两行、根本没有词条区**，所以按 Shift 也没反应；
    //   这里补齐后，它与出价弹窗才真正「逻辑一致」（2026-09-29 用户选 A）
    /** 基础词条（`TooltipType.BASIC`，已丢掉首行物品名 —— 弹窗自己画了名字） */
    private var cancelItemTooltipLines: List<Text> = emptyList()
    /** 展开词条（Shift / Ctrl 按住时的 `ADVANCED`） */
    private var cancelItemAdvancedLines: List<Text> = emptyList()
    /** 展开词条是不是**按当前按键组合**建的（组合变了要重建，见渲染处） */
    private var cancelAdvancedBuiltWithShift = false
    private var cancelAdvancedType: TooltipType? = null

    /** 弹窗底部两枚按钮**相对弹窗底边**的距离 —— 创建处与每帧重贴处必须同源（改就改这一处） */
    private val cancelButtonDy = 30

    private var cancelConfirmButton: NineSliceButton? = null
    private var cancelCancelButton: NineSliceButton? = null

    private fun getListStartY() = 68
    private fun getMaxVisibleRows() = maxOf(0, (height - getListStartY() - 40) / rowHeight)

    /** 搜索过滤：按名称 + 卖家名（精灵与物品共用，客户端本地过滤） */
    private fun filteredEntries(): List<AuctionEntry> = filteredCache

    /** 数据/搜索变化时重建过滤缓存（render 每帧只读） */
    private fun rebuildFiltered() {
        val query = searchField?.text?.trim()?.takeIf { it.isNotEmpty() }
        // 币种先行：服务端列表已按币种过滤，但**增量事件（NEW/BID/SETTLED）是全币种广播**的，
        // 不在这里再筛一道，别的币种的拍卖就会飘进当前列表
        val byCurrency = entries.filter { it.isPoints == (currency == MarketCurrency.POINTS) }
        filteredCache = if (query == null) byCurrency else byCurrency.filter {
            displayName(it).contains(query, ignoreCase = true) || it.sellerName.contains(query, ignoreCase = true) ||
                // 物品条目走搜索索引（名称/tooltip/TM 招式精确匹配，见 ItemSearchIndex）
                (it.type == "ITEM" && com.shusheng.cobblemarket.client.ItemSearchIndex.entryMatches(it.species, it.itemNbt, query))
        }
        indexedFiltered = filteredCache.map { IndexedValue(entries.indexOf(it), it) }
    }

    // ── 照搬拍卖场的显示工具 ──

    private val iconData = mutableMapOf<Int, PokemonIconData>()

    // 行内物品栈缓存（球种/持有物/物品拍卖图标）：render 每帧 new ItemStack 是分配热点，数据到达时构建一次。
    // 与 iconData 分离：球种/持有物渲染不依赖物种解析成功，独立缓存保持与原逐帧解析完全一致的行为
    private data class RowStacks(val ballStack: ItemStack?, val heldStack: ItemStack?, val itemStack: ItemStack?)
    private val rowStacks = mutableMapOf<Int, RowStacks>()

    // tooltip 缓存（照拍卖场同款拆分）：静态行只取决于条目，动态行（价格/倒计时/领先者）每帧构建
    private var tooltipCacheKey: UUID? = null
    private var tooltipCacheStaticLines: List<Pair<Text?, Int>> = emptyList()
    private var tooltipCacheHeldLine = -1
    private var tooltipCacheMaxWidth = 0
    /** 证章图标行在静态行列表中的索引（-1 = 无证章） */
    private var tooltipCacheMovesLine = -1
    private var tooltipCacheMarksLine = -1
    private var itemTooltipCacheKey: UUID? = null
    private var itemTooltipCacheLines: List<Pair<Text, Int>> = emptyList()
    private var itemTooltipCacheMaxWidth = 0

    private fun displayName(entry: AuctionEntry): String {
        if (entry.type == "ITEM") {
            val id = Identifier.tryParse(entry.species) ?: return entry.species
            val item = Registries.ITEM.get(id)
            val name = item.name.string
            return if (name == item.translationKey) id.path else name
        }
        val t = Text.translatable(entry.species).string
        return if (t == entry.species) (entry.extraData["speciesName"] ?: entry.species) else t
    }

    /** 价格文本：金额金色、无出价时带「起拍」前缀（2026-08-24 拍板：文字默认色）。
     *  ⚠ 两个函数都**不含货币单位**（单位由调用方补画在行尾）—— 币种是**这场拍卖自己的**
     *  （`entry.isPoints`），调用方补画时要传同一个值 */
    private fun displayPriceText(entry: AuctionEntry): Text {
        val price = if (entry.currentPrice > 0) entry.currentPrice else entry.startingPrice
        // ⚠ **不含货币单位** —— 单位由调用方补画在行尾（物品货币时是那件物品的图标、RP 时是字形图标）。
        //   末尾那个空格留在文本里，调用方按 `getWidth(本文本)` 就能定位单位
        val priceT = Text.literal(com.shusheng.cobblemarket.client.formatPrice(price) + " ").formatted(Formatting.GOLD)
        return if (entry.currentPrice > 0) priceT
        else Text.translatable("cobblemarket.auction.from").append(priceT)
    }

    /** 行内价格（缩写、无「起拍」前缀，与物品市场/求购单行内一致）：悬停与出价弹窗仍走 [displayPriceText] 千分位。
     *  ⚠ **不含货币单位**（2026-09-29）—— 物品本位货币时单位是**物品图标**、拼不进 Text，由调用方补画；
     *    末尾那个空格留在文本里，调用方按 `getWidth(本文本)` 就能定位单位 */
    private fun displayPriceCompactText(entry: AuctionEntry): Text {
        val price = if (entry.currentPrice > 0) entry.currentPrice else entry.startingPrice
        return Text.literal(com.shusheng.cobblemarket.client.formatPriceShort(price) + " ").formatted(Formatting.GOLD)
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

    private val defaultSkinTexture = Identifier.of("minecraft", "textures/entity/player/wide/steve.png")

    // 皮肤纹理缓存：每行每帧创建 GameProfile 并查 skinProvider 是分配热点，首个结果按 UUID 缓存
    private val skinCache = mutableMapOf<UUID, Identifier>()

    private fun getSellerSkin(uuid: UUID, name: String): Identifier {
        skinCache[uuid]?.let { return it }
        val skin = client?.networkHandler?.getPlayerListEntry(uuid)?.skinTextures?.texture()
            ?: client?.skinProvider?.getSkinTextures(GameProfile(uuid, name))?.texture()
            ?: defaultSkinTexture
        skinCache[uuid] = skin
        return skin
    }

    private fun drawSellerAvatar(context: DrawContext, uuid: UUID, name: String, x: Int, y: Int, size: Int) {
        val texture = getSellerSkin(uuid, name)
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(size / 8f, size / 8f, 1f)
        context.drawTexture(texture, 0, 0, 8f, 8f, 8, 8, 64, 64)
        context.matrices.pop()
    }

    private fun cacheIcons() {
        iconData.clear()
        rowStacks.clear()
        entries.forEachIndexed { i, entry ->
            if (entry.type != "POKEMON") {
                rowStacks[i] = RowStacks(
                    ballStack = null,
                    heldStack = null,
                    itemStack = iconStackOf(entry.species, entry.itemNbt)
                )
                return@forEachIndexed
            }
            // 携带物同时喂给模型（Cobblemon 会渲染在手上/嘴上）与行内小图标
            val held = heldIconStack(entry.extraData)
            rowStacks[i] = RowStacks(
                ballStack = entry.extraData["ballItem"]?.let { Identifier.tryParse(it)?.let { id -> ItemStack(Registries.ITEM.get(id)) } },
                heldStack = held,
                itemStack = null
            )
            val id = Identifier.tryParse(entry.extraData["speciesId"] ?: "") ?: return@forEachIndexed
            val species = PokemonSpecies.getByIdentifier(id) ?: return@forEachIndexed
            val aspects = (entry.extraData["aspects"] ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()
            if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
            iconData[i] = PokemonIconData(displayName(entry), RenderablePokemon(species, aspects, heldIconStackForModel(entry.extraData)), FloatingState())
        }
    }

    /** 携带物栈（空气视为无）；缓存供行渲染每帧复用（照精灵市场） */
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
        // 「非法」角标：图标**右上角**那枚印章（与小锁镜像对称，见 [EntryBadgeRenderer.drawIllegalBadge]）。
        // ⚠ 下标口径与建图标缓存那处一致（按 `entries` 的下标建表、调用方传 `origIndex`）
        if (entries.getOrNull(index)?.illegal == true) {
            EntryBadgeRenderer.drawIllegalBadge(context, x, y, size)
        }
    }

    // ── 初始化与数据 ──

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        val backBtn = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(AdminScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        backButton = backBtn

        // 货币切换按钮（返回按钮左侧 4px）：看哪个币种的拍卖（与玩家端同一个位置、同一套逻辑）
        // ⚠ **管理端不受 RP 开关约束**：开关管的是「玩家之间能不能用 RP 交易」，
        //   而不是管理员的查看/下架权限 —— 关掉后存量 RP 挂单仍要能被管理（否则永远下架不了）
        currencyButton = NineSliceButton(
                leftX + panelWidth - 74, 13, 20, 16, Text.literal(""), { onCurrencyClick() },
                iconTexW = POINTS_SYMBOL_SRC, iconTexH = POINTS_SYMBOL_SRC,
                iconScale = CURRENCY_BUTTON_ICON_SIZE / POINTS_SYMBOL_SRC.toFloat(),
                tooltip = Text.translatable("cobblemarket.gui.currency_switch", com.shusheng.cobblemarket.client.activeCurrencyUnit(), CurrencyService.pointsIcon())
        ).also {
            updateCurrencyButton(it)
            addDrawableChild(it)
        }
        addDrawableChild(backBtn)

        val savedSearch = searchField?.text ?: ""
        searchField = net.minecraft.client.gui.widget.TextFieldWidget(
            textRenderer, leftX + 2, 44, panelWidth - 4, 16, Text.translatable("cobblemarket.gui.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.auction.search_placeholder").formatted(Formatting.GRAY))
        // 搜索变化失效过滤缓存（render 每帧读缓存，不再实时过滤）
        searchField?.setChangedListener { rebuildFiltered() }
        addSelectableChild(searchField)
        addDrawableChild(searchField)
        searchField?.text = savedSearch

        sendToServer(RequestAuctionListPayload(currency.name))
        rebuildFiltered()
    }

    fun onAuctionList(payload: AuctionListDataPayload) {
        // 结算中条目保留到动画结束（照搬拍卖场），其余以服务端为准
        val settling = entries.filter { it.id in settlingUntil }
        entries = payload.entries + settling.filter { s -> payload.entries.none { it.id == s.id } }
        loadState.markLoaded()
        rebuildFiltered()
        cacheIcons()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, entries.size - getMaxVisibleRows()))
    }

    fun onAuctionEvent(payload: AuctionEventPayload) {
        when (payload.event) {
            "NEW" -> payload.entry?.let { e ->
                if (entries.none { it.id == e.id }) entries = entries + e
            }
            "BID" -> payload.entry?.let { e ->
                entries = entries.map { if (it.id == e.id) e else it }
                // 弹窗同步最新数据（与拍卖场一致，弹窗内价格不过期）
                if (cancelEntry?.id == e.id) cancelEntry = e
            }
            "CANCELLED" -> payload.entry?.let { e ->
                // 强制下架：立即从列表移除（不播落槌动画），settlingUntil 清理防残留
                entries = entries.filterNot { it.id == e.id }
                settlingUntil.remove(e.id)
            }
            "SETTLED" -> payload.entry?.let { e ->
                // 延迟 1.5 秒移除（照搬拍卖场：显示「结算中」+ 落槌图标敲击动画）
                settlingUntil[e.id] = System.currentTimeMillis() + 1500
                hammerHitUntil[e.id] = System.currentTimeMillis() + 300
                // 拍卖已结算：若弹窗正开着则自动关闭（确认也会被服务端拒绝）
                if (cancelEntry?.id == e.id) closeCancelDialog()
            }
        }
        rebuildFiltered()
        cacheIcons()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, entries.size - getMaxVisibleRows()))
    }

    fun onMarketResult(payload: MarketResultPayload) {
        client?.player?.sendMessage(payload.message.copy().formatted(if (payload.success) Formatting.GREEN else Formatting.RED), false)
        if (payload.success) {
            sendToServer(RequestAuctionListPayload(currency.name))
        }
    }

    // ── 强制下架确认弹窗（照搬出价弹窗两列布局） ──

    private fun openCancelDialog(entry: AuctionEntry) {
        cancelEntry = entry
        cancelRenderable = null
        // 隐藏下层控件（弹窗打开期间不可交互；closeCancelDialog 的 init 重建会恢复）
        searchField?.visible = false
        backButton?.visible = false
        // ⚠ 货币按钮同样要隐藏：本界面在 mouseClicked 里是 `return super.mouseClicked(...)`，
        //   没被隐藏的控件照常响应点击 —— 漏一个就是「弹窗盖着、下面还能点」
        currencyButton?.visible = false
        if (entry.type == "POKEMON") {
            val id = Identifier.tryParse(entry.extraData["speciesId"] ?: "")
            val species = id?.let { PokemonSpecies.getByIdentifier(it) }
            if (species != null) {
                val aspects = (entry.extraData["aspects"] ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()
                if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
                cancelRenderable = RenderablePokemon(species, aspects, heldIconStackForModel(entry.extraData))
            }
        } else {
            // 物品：两套词条**在打开时就建好**（基础 + 展开）—— 渲染每帧直接取用，
            // 别每帧重新构建（原版 tooltip 构建不便宜）。照搬出价弹窗的做法
            cancelItemTooltipLines = buildCancelItemLines(entry, TooltipType.BASIC)
            cancelItemAdvancedLines = buildCancelItemLines(entry, TooltipType.ADVANCED)
            cancelAdvancedBuiltWithShift = false
            cancelAdvancedType = null
        }
        // 点行 → 弹窗弹出 → 由弹窗里的精灵叫一声（用户拍板的「叫声转移」）。
        // ⚠ 只记待播时刻、不在这里直接播：addFirstAnimation 依赖 state.currentModel，
        //   而它要等 drawProfilePokemon 跑过才有值 —— 常驻 state 在「从没渲染过」时仍是 null，
        //   原先直接播的写法只在开过一次之后才叫（见 cancelCryAt）；渲染处到点才播
        cancelCryAt = System.currentTimeMillis() + IconCryClick.AUTO_CRY_DELAY_MS
        val centerX = width / 2
        // 弹窗高度按左列信息区实际内容算（球种/携带物/证章都计入），按钮/提示统一相对弹窗底定位
        val dialogH = cancelDialogHeight(entry)
        val dialogY = height / 2 - dialogH / 2

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染，照搬出价弹窗）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderCancelDialogBackground(context, delta)
            }
        })

        // 按钮宽 80（原 56 放不下英文「Force Cancel」≈72px）；**左缘保持 -50 不动**（往左会压左列），
        // 整体往右长：-50/+34，间距 4px。原注：-60 会压左列信息区（王冠 IV 行等长文字），
        // 英文下左列行宽更大，原位置会压到按钮；右列竞拍信息在 y 28~100 不冲突
        cancelConfirmButton = NineSliceButton(
            centerX - 50, dialogY + dialogH - cancelButtonDy, 80, 20,
            Text.translatable("cobblemarket.auction.force_cancel"),
            { confirmCancel() }
        )
        addDrawableChild(cancelConfirmButton)
        cancelCancelButton = NineSliceButton(
            centerX + 34, dialogY + dialogH - cancelButtonDy, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeCancelDialog() }
        )
        addDrawableChild(cancelCancelButton)
    }

    private fun closeCancelDialog() {
        cancelEntry = null
        cancelRenderable = null
        cancelConfirmButton = null
        cancelCancelButton = null
        clearChildren()
        init()
    }

    private fun confirmCancel() {
        val entry = cancelEntry ?: return
        sendToServer(ForceCancelAuctionPayload(entry.id))
        closeCancelDialog()
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val cancel = cancelEntry
        super.resize(client, width, height)
        if (cancel != null) {
            cancelEntry = null
            openCancelDialog(cancel)
        }
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        IconRegistry.beginFrame(mouseX, mouseY)
        // 顶部身份位放拍卖锤图标（复用界面内已有的 48x48 图标，见 MarketPanelBackground）
        MarketPanelBackground.draw(context, width, height, HAMMER_TEX_LEFT)

        val leftX = width / 2 - panelWidth / 2
        val startY = getListStartY()
        val visibleRows = getMaxVisibleRows()

        hoveredRow = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY in startY..(startY + visibleRows * rowHeight)) {
            val row = (mouseY - startY) / rowHeight
            if (row in 0 until minOf(visibleRows, filteredEntries().size - scrollOffset)) hoveredRow = row
        }

        repeat(minOf(visibleRows, maxOf(0, filteredEntries().size - scrollOffset))) { di ->
            val rowY = startY + di * rowHeight
            val rowState = if (di == hoveredRow) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, rowState, ROW_BACKGROUND_TEX_H)
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // ⚠ 余额包是**异步**到的（进服首帧缓存可能还空着）⇒ 每帧刷一次按钮外观，包一到就换成物品图标
        currencyButton?.let { updateCurrencyButton(it) }
        super.render(context, mouseX, mouseY, delta)

        if (cancelEntry != null) {
            return
        }

        val pollNow = System.currentTimeMillis()

        // 结算中条目过期（1.5 秒）后从列表移除（照搬拍卖场）
        val settledExpired = settlingUntil.filterValues { it <= pollNow }.keys
        if (settledExpired.isNotEmpty()) {
            entries = entries.filterNot { it.id in settledExpired }
            settledExpired.forEach { settlingUntil.remove(it) }
            rebuildFiltered()
            // entries 变化后索引移动：行缓存（3D 图标/物品栈）按索引建，必须同步重建
            cacheIcons()
        }

        // 结束前警告：本地检测 10s/6s/3s，只触发图标敲击动画。
        // 音效不由这里负责：管理员若出价参与过，服务端定向事件会正常送达并全局播放
        val toKnock = entries.filter { e ->
            val k = knockFor(e.endsAt - pollNow)
            if (k <= 0) return@filter false
            val st = warnStates[e.id]
            (st == null || st.endsAt != e.endsAt || st.knocks < k)
        }
        if (toKnock.isNotEmpty()) {
            toKnock.forEach { e ->
                warnStates[e.id] = WarnState(e.endsAt, knockFor(e.endsAt - pollNow))
                hammerHitUntil[e.id] = pollNow + 300
            }
        }

        // 有到期未结算的拍卖时，每秒重拉一次列表触发服务端结算（与拍卖场一致，避免一直显示「结算中」）；
        // 结算中条目排除（已结算完毕，重拉会全量替换、截断动画）
        if (entries.any { it.endsAt <= pollNow && it.id !in settlingUntil } && pollNow - lastSettlePoll > 1000) {
            lastSettlePoll = pollNow
            sendToServer(RequestAuctionListPayload(currency.name))
        }

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.op.auction").formatted(Formatting.GOLD),
            centerX, 20, 0xFFFFFF)

        // 顶部分割线：列表起始上方 4px（与其他列表界面一致；此前这屏漏画了）
        drawPanelDivider(context, leftX + panelWidth / 2, getListStartY() - 4)
        val startY = getListStartY()
        val visibleRows = getMaxVisibleRows()
        val filtered = filteredEntries()
        // 按过滤后大小钳制滚动（搜索词变化后不显示空白区域）
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, filtered.size - visibleRows))

        if (filtered.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable(loadState.emptyKey("cobblemarket.auction.empty")).formatted(Formatting.GRAY),
                centerX, startY + 30, 0xFFFFFF)
        }

        indexedFiltered.drop(scrollOffset).take(visibleRows).forEachIndexed { i, (origIndex, entry) ->
            val y = startY + i * rowHeight
            val slotX = leftX + 2
            val slotY = y + 2
            if (entry.type == "POKEMON") {
                context.matrices.push()
                context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
                context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
                context.drawTexture(POKEMON_SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
                context.matrices.pop()
                if (iconData.containsKey(origIndex)) {
                    renderPokemonIcon(context, origIndex, slotX, slotY, iconSize, delta = delta, preview = i == hoveredRow)
                }
                // 图标链 + 属性色名称 + 金色闪光星标（照搬拍卖场行）
                var sx = leftX + 26
                val ballItem = entry.extraData["ballItem"]
                if (!ballItem.isNullOrEmpty()) {
                    rowStacks[origIndex]?.ballStack?.let { ballStack ->
                        com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                            itemStack = ballStack, x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                    }
                    sx += 14
                }
                val primaryType = entry.extraData["primaryType"] ?: ""
                val tc = typeColor(if (primaryType.isNotEmpty()) primaryType else "cobblemon.type.normal")
                // 名字截断 46px ≈ 5 个汉字（与拍卖场一致）
                val name = com.shusheng.cobblemarket.util.TextUtil.truncateString(displayName(entry), 46)
                context.drawTextWithShadow(textRenderer, name, sx, y + 7, tc)
                sx += textRenderer.getWidth(name)
                if (entry.shiny) {
                    context.drawText(textRenderer, "★", sx + 2, y + 7, GOLD_COLOR, false)
                    sx += 2 + textRenderer.getWidth("★")
                }
                sx += EntryBadgeRenderer.NAME_TAIL_GAP
                val gender = entry.extraData["gender"]
                if (gender == "MALE" || gender == "FEMALE") {
                    val gi = if (gender == "MALE") GENDER_ICON_MALE else GENDER_ICON_FEMALE
                    com.cobblemon.mod.common.api.gui.blitk(matrixStack = context.matrices, texture = gi, x = sx, y = y + 7, width = 6, height = 8)
                    sx += 8
                }
                // 持有物图标（空气/解析失败 = null 不渲染，与原逐帧解析行为一致）
                rowStacks[origIndex]?.heldStack?.let { heldStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = heldStack, x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                    sx += 12
                }
                // 体型徽章（排在携带物图标之后，留 3px 空隙）
                val sizeBadge = entry.extraData["size"].orEmpty()
                if (sizeBadge.isNotEmpty()) {
                    sx += 3
                    sx += EntryBadgeRenderer.drawSizeBadgeIcon(context, sizeBadge, sx, y + 7)
                }
            } else {
                rowStacks[origIndex]?.itemStack?.let {
                    // ⚠ 物品图标 16 高、精灵槽 20 高，两者不能共用 slotY（行顶 +2）——
                    //   物品图标得自己按行高居中，否则上留 2 下留 6 看着偏上（同 ItemSellScreen）
                    drawItemWithBar(context, it, slotX + 2, y + (rowHeight - 16) / 2)
                }
                // 数量宽度先扣出来再截断名字（照拍卖场）：整串一起截断会先吃掉「×N」
                val countSuffix = " ×${entry.count}"
                // ⚠ 名字走**稀有度色**、` ×N` 保持白 —— 两段分别画（照拍卖场，那里也是这么修的）
                val name = com.shusheng.cobblemarket.util.TextUtil.truncateStyled(
                    EntryBadgeRenderer.auctionNameText(entry), 100 - textRenderer.getWidth(countSuffix))
                context.drawTextWithShadow(textRenderer, name, leftX + 28, y + 7, 0xFFFFFF)
                context.drawTextWithShadow(
                    textRenderer, countSuffix, leftX + 28 + textRenderer.getWidth(name), y + 7, 0xFFFFFF)
            }

            // 当前价（行内缩写）+ 出价次数（灰，拆段）：货币单位紧跟 ×次数，中间不留空格
            val priceStr = displayPriceCompactText(entry)
            val bidPart = if (entry.bidCount > 0) "×${entry.bidCount}" else ""
            val priceUnitW = currencyUnitWidth(textRenderer, entry.isPoints)
            // 价格区右端与拍卖场一致（本界面无行内出价按钮，右侧为纯余量）
            val priceX = leftX + panelWidth - 54 - textRenderer.getWidth(priceStr) - priceUnitW - textRenderer.getWidth(bidPart)

            // 价格左侧锤子图标：默认静止，敲击/落槌时切换敲击状态约 0.3 秒（照搬拍卖场）
            val hammerTex = if ((hammerHitUntil[entry.id] ?: 0L) > System.currentTimeMillis())
                HAMMER_TEX_LEFT
            else
                HAMMER_TEX_LEFT_NO
            context.matrices.push()
            context.matrices.translate((priceX - 16).toDouble(), (y + 6).toDouble(), 0.0)
            // 48×48 贴图按 0.25 缩到 12×12 显示（原 24 贴图 0.5 缩放的视觉尺寸不变，清晰度翻倍）
            context.matrices.scale(0.25f, 0.25f, 1f)
            context.drawTexture(hammerTex, 0, 0, 0f, 0f, 48, 48, 48, 48)
            context.matrices.pop()

            context.drawTextWithShadow(textRenderer, priceStr, priceX, y + 7, 0xFFFFFF)
            val unitX = priceX + textRenderer.getWidth(priceStr)
            drawCurrencyUnit(context, textRenderer, entry.isPoints, unitX, y + 7, 0xFFAA00)
            if (bidPart.isNotEmpty()) {
                context.drawTextWithShadow(textRenderer, bidPart,
                    unitX + priceUnitW, y + 7, 0xAAAAAA)
            }

            // 结束倒计时 + 卖家头像：头像位置与拍卖场一致（leftX+149，各行对齐）；
            // 名字区最坏到 141px（名字截断 46px + 星 + 性别 + 携带物 + 体型徽章，起点 leftX+40），
            // 149 再留 8px 余量。倒计时紧跟其右，价格区异常宽时整块左移兜底
            val remaining = com.shusheng.cobblemarket.util.RemainingTime.text(entry.endsAt, "cobblemarket.auction.ending")
            val remainingColor = com.shusheng.cobblemarket.util.RemainingTime.color(entry.endsAt)
            val remW = textRenderer.getWidth(remaining)
            val avatarX = leftX + 149
            val shift = maxOf(0, avatarX + 20 + remW - (priceX - 16 - 6))
            context.drawTextWithShadow(textRenderer, remaining,
                avatarX + 20 - shift, y + 7, remainingColor)
            drawSellerAvatar(context, entry.sellerUuid, entry.sellerName, avatarX - shift, y + 4, 16)
        }

        if (hoveredRow >= 0) {
            val filtered = filteredEntries()
            val idx = scrollOffset + hoveredRow
            if (idx in filtered.indices) {
                renderTooltip(context, filtered[idx], mouseX, mouseY)
            }
        }

        if (filteredEntries().size > visibleRows) {
            context.drawCenteredTextWithShadow(textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + visibleRows, filteredEntries().size)} / ${filteredEntries().size}",
                centerX, height - 24, 0x888888)
        }

        // 悬停预览大图：画在本界面内容全部之后才在面板之上。
        // **弹窗打开时一律不画**（照精灵市场）：这时注册表里只剩弹窗自己的图标，鼠标停在弹窗上
        // 照样能命中 entryInRow（它只按 y 匹配行范围 + x 在面板内）→ 左侧无故冒出大图（2026-09-15 实测）
        if (cancelEntry == null) {
            val previewRowL = width / 2 - panelWidth / 2
            IconRegistry.entryInRow(mouseX.toDouble(), mouseY.toDouble(), previewRowL, previewRowL + panelWidth)
                ?.let { PokemonHoverPreview.render(context, it.renderable, it.state, previewRowL, height) }
        }
    }

    // ── 悬停（照搬拍卖场，去掉「我的」标记） ──

    private fun renderTooltip(context: DrawContext, entry: AuctionEntry, mouseX: Int, mouseY: Int) {
        if (entry.type == "POKEMON") renderPokemonTooltip(context, entry, mouseX, mouseY)
        else renderItemTooltip(context, entry, mouseX, mouseY)
    }

    private fun renderPokemonTooltip(context: DrawContext, entry: AuctionEntry, mouseX: Int, mouseY: Int) {
        // 静态行缓存：内容只取决于条目（见 tooltipCacheKey 字段注释），悬停目标变化时才重建
        if (tooltipCacheKey != entry.id) {
            tooltipCacheKey = entry.id
            val extra = entry.extraData
            val hp = StatNames.HP
            val atk = StatNames.ATK
            val def = StatNames.DEF
            val spa = StatNames.SP_ATK
            val spd = StatNames.SP_DEF
            val spe = StatNames.SPEED
            val primaryType = extra["primaryType"] ?: ""
            val secondaryType = extra["secondaryType"] ?: ""
            val typeText = (if (primaryType.isNotEmpty()) Text.translatable(primaryType).string else "-") +
                if (secondaryType.isNotEmpty()) " + ${Text.translatable(secondaryType).string}" else ""

            val staticLines = mutableListOf<Pair<Text?, Int>>()
            staticLines.add(EntryBadgeRenderer.nameWithLevelLine(
                EntryBadgeRenderer.nameWithShinyStar(displayName(entry), entry.shiny),
                primaryType, entry.level) to 0xFFFFFF)
            // 昵称行：**名字下面一行**、有才画（用户 2026-10-06 定）——
            // ⚠ 必须插在**名字行之后**（索引 0 那行会额外画公母/体型图标）
            EntryBadgeRenderer.nicknameLine(extra["nickname"])?.let { staticLines.add(it to 0xFFFFFF) }
            // 属性行（**含太晶**）：与其余各处共用同一个组装入口，别再各拼各的
            staticLines.add(
                com.shusheng.cobblemarket.util.TypeTextColors.typeLineWithTera(
                    primaryType, secondaryType, extra["teraType"].orEmpty()
                ) to 0xFFFFFF)
            // 极巨行：属性行下面、有才显示（与市场侧走同一个组装入口）
            com.shusheng.cobblemarket.util.PokemonExtraLines.dynamax(
                extra["gmaxFactor"] == "true", extra["dmaxLevel"]?.toIntOrNull() ?: 0
            )?.let { staticLines.add(it to 0xFFFFFF) }
            // 性格 + 特性同一行：走共用入口（两段间的间距只在 EntryBadgeRenderer 里定义）
            staticLines.add(
                EntryBadgeRenderer.natureAbilityLine(
                    extra["natureBase"] ?: "", extra["nature"] ?: "", extra["ability"] ?: ""
                ) to 0xFFFFFF
            )
            // 球种行：性格特性下面（原缺失，2026-09-27 补齐）；球名走**稀有度色**
            extra["ball"]?.takeIf { it.isNotEmpty() }?.let {
                staticLines.add(
                    com.shusheng.cobblemarket.util.ItemNameText.ballLine(it, extra["ballItem"].orEmpty()) to 0xFFFFFF
                )
            }
            val heldItemId = extra["heldItemId"].orEmpty()
            val hasHeldItem = heldItemId.isNotEmpty() &&
                Identifier.tryParse(heldItemId)?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true
            var heldItemLine = -1
            if (hasHeldItem) {
                heldItemLine = staticLines.size
                staticLines.add(Text.translatable("cobblemarket.gui.tooltip_held") to 0xFFFFFF)
            }
            staticLines.add(Text.translatable("cobblemarket.gui.tooltip_ivs") to 0xFFFFFF)
            staticLines.add(Text.literal("  $hp: ${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsHp"]?.toIntOrNull() ?: 0, htExtra(extra, "htHp"))}").append(Text.literal("  EV: ${extra["evsHp"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)) to 0x66FF66); staticLines.add(Text.literal("  $atk: ${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsAtk"]?.toIntOrNull() ?: 0, htExtra(extra, "htAtk"))}").append(Text.literal("  EV: ${extra["evsAtk"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)) to 0xFF6666)
            staticLines.add(Text.literal("  $def: ${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsDef"]?.toIntOrNull() ?: 0, htExtra(extra, "htDef"))}").append(Text.literal("  EV: ${extra["evsDef"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)) to 0xFFCC66); staticLines.add(Text.literal("  $spa: ${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpAtk"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpAtk"))}").append(Text.literal("  EV: ${extra["evsSpAtk"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)) to 0x6699FF)
            staticLines.add(Text.literal("  $spd: ${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpDef"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpDef"))}").append(Text.literal("  EV: ${extra["evsSpDef"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)) to 0x66FF99); staticLines.add(Text.literal("  $spe: ${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpd"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpd"))}").append(Text.literal("  EV: ${extra["evsSpd"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)) to 0xFF99FF); staticLines.add(Text.translatable("cobblemarket.gui.friendship", extra["friendship"]?.toIntOrNull() ?: 0) to 0xFF99CC)
            // 初始训练家行：亲密度下面（与市场侧同位置）
            com.shusheng.cobblemarket.util.PokemonExtraLines.originalTrainer(extra["ot"].orEmpty())
                ?.let { staticLines.add(it to 0xFFFFFF) }

            // 技能块（**总是画** —— Cobblemon 里不存在没技能的精灵）：分割线 + 两行技能 + 分割线
            var movesLine = -1
            if (MoveText.hasAny(extra["moves"].orEmpty())) {
                movesLine = staticLines.size
                staticLines.add(null to 0)
            }
            // 证章区块：技能块下方，只有图标行（下方那条分割线由拍卖信息前的分割线充当）
            var marksLine = -1
            if (extra["marks"].orEmpty().isNotEmpty()) {
                marksLine = staticLines.size
                staticLines.add(null to 0)
            }
            var mw = 0; staticLines.forEach { it.first?.let { t -> mw = maxOf(mw, textRenderer.getWidth(t)) } }
            // 名字行尾部图标（公母 + 体型徽章）不计入文本宽度，单独补上
            staticLines[0].first?.let { mw = maxOf(mw, EntryBadgeRenderer.nameLineWidth(it, entry.extraData["gender"] ?: "", entry.extraData["size"] ?: "")) }
            if (heldItemLine >= 0) {
                mw = maxOf(mw, textRenderer.getWidth(staticLines[heldItemLine].first!!) + 14)
            }
            // 证章行宽：⚠ **不再参与** mw（原先按固定 10 个算 12px 格的方式已废弃）
            if (marksLine >= 0) {
                // ⚠ 证章**不再参与** mw（方向反过来：窗口宽度决定证章排几个，见 marksPerRow）
            }
            mw = maxOf(mw, EntryBadgeRenderer.movesBlockWidth(extra["moves"].orEmpty()))
            tooltipCacheStaticLines = staticLines
            tooltipCacheHeldLine = heldItemLine
            tooltipCacheMaxWidth = mw
            tooltipCacheMovesLine = movesLine
            tooltipCacheMarksLine = marksLine
        }
        // 动态行（当前价/倒计时/领先者随出价与时间变化）每帧构建
        val lines = tooltipCacheStaticLines.toMutableList()
        // 分割线：上方精灵信息，下方拍卖信息。
        // ⚠ **只有存在证章块时才加**（这条线本是证章块的下分割线）—— 与拍卖场同处一并修
        //（2026-09-29 用户报：没有证章时凭空多一条线）
        if (tooltipCacheMarksLine >= 0) lines.add(null to 0xFFFFFF)
        lines.add(Text.translatable("cobblemarket.auction.seller").append(entry.sellerName) to 0xFFFFFF)
        // ⚠ 货币单位**不拼进文本**，由下面的渲染循环补在行尾（物品货币时是那件物品的图标）——
        //   先记下这行的下标（lines 里还混着分割线 / 技能块 / 证章块这些 null 行，不能靠数）
        val priceRow = lines.size
        val priceLine = Text.translatable("cobblemarket.auction.current_price").append(displayPriceText(entry))
        lines.add((if (entry.bidCount > 0)
            priceLine.append(Text.literal("  ×${entry.bidCount}").formatted(Formatting.GRAY))
        else priceLine) to 0xFFFFFF)
        lines.add(
            // ⚠ 与行内同源（见 [com.shusheng.cobblemarket.util.RemainingTime.color]）
            Text.translatable("cobblemarket.auction.ends").append(
                com.shusheng.cobblemarket.util.RemainingTime.text(entry.endsAt, "cobblemarket.auction.ending")
            ) to com.shusheng.cobblemarket.util.RemainingTime.color(entry.endsAt))
        if (entry.currentBidderName.isNotEmpty()) {
            lines.add(Text.translatable("cobblemarket.auction.leader").append(entry.currentBidderName) to 0xFFDD66)
        }

        // 行数按屏幕高钳制（精灵信息本身可控，但叠上技能块 / 证章后仍可能超屏）—— 见 [clampTooltipRows]
        clampTooltipRows(lines, height) { n -> Text.translatable("cobblemarket.gui.tooltip_more", n) to 0xAAAAAA }
            .let { if (it !== lines) { lines.clear(); lines.addAll(it) } }
        var mw = tooltipCacheMaxWidth
        for (i in tooltipCacheStaticLines.size until lines.size) {
            lines[i].first?.let { t -> mw = maxOf(mw, textRenderer.getWidth(t)) }
        }
        val heldItemLine = tooltipCacheHeldLine
        val movesLine = tooltipCacheMovesLine
        val marksLine = tooltipCacheMarksLine
        val marks = if (marksLine >= 0) entry.extraData["marks"].orEmpty().split(",").filter { it.isNotEmpty() } else emptyList()
        val movesRaw = entry.extraData["moves"].orEmpty()
        val pad = 4
        val tx = minOf(mouseX + 12, width - mw - 12)
        // 证章超过一行时第二行「+N」额外占 12px
        val marksExtra = if (marksLine >= 0 && marks.size > EntryBadgeRenderer.marksPerRow(mw)) 12 else 0
        val th = lines.size * 10 + pad + marksExtra + EntryBadgeRenderer.movesBlockExtra(movesRaw)
        val ty = if (mouseY - th - 4 <= 0) minOf(mouseY + 12, height - th) else mouseY - th - 4

        context.matrices.push(); context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - pad, ty - pad, mw + 2 * pad, th + pad, 1, ROW_BACKGROUND_TEX_H)
        var rowY = ty
        lines.forEachIndexed { i, (line, color) ->
            if (line == null && i == movesLine) {
                // 技能块：分割线 + 两行技能 + 分割线（左对齐版，分割线撑满提示框全宽）
                rowY += EntryBadgeRenderer.drawMovesBlockLeft(context, movesRaw, tx, mw, rowY)
            } else if (line == null && i == marksLine) {
                // 证章图标行：**居中**排（2026-09-29 统一口径，见 docs/pokemon_info_surfaces.md 第七节；「+N」同样居中）
                rowY += EntryBadgeRenderer.drawMarksRow(context, marks, tx, rowY, mw, centered = true)
            } else if (line == null) {
                // 分割线：1px 灰线，撑满提示框全宽（与信息区分隔线一致，不随证章数量变化）
                val rowW = mw
                context.fill(tx, rowY + 4, tx + rowW, rowY + 5, 0xFF888888.toInt())
                rowY += 10
            } else if (i == priceRow) {
                // 当前价行：货币单位补在行尾（物品货币时是那件物品的图标），颜色照金额那段的金色
                val w = context.drawTextWithShadow(textRenderer, line, tx, rowY, color)
                drawCurrencyUnit(context, textRenderer, entry.isPoints, w, rowY, 0xFFAA00)
                rowY += 10
            } else if (i == heldItemLine) {
                context.drawTextWithShadow(textRenderer, line, tx, rowY, color)
                val heldIconX = tx + textRenderer.getWidth(line) + 2
                heldIconStack(entry.extraData)?.let { heldStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = heldStack,
                        x = heldIconX.toDouble(),
                        y = rowY + 0.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                }
                // 玩家把携带物设成「隐藏」时注明一句（模型上不显示它，而信息行照常列出）—— 用户 2026-09-26
                if (entry.extraData["heldItemVisible"] == "false") {
                    context.drawTextWithShadow(
                        textRenderer, Text.translatable("cobblemarket.gui.held_hidden"), heldIconX + 12, rowY, 0xAAAAAA
                    )
                }
                rowY += 10
            } else if (i == 0) {
                // 第一行（名字★Lv）带公母图标
                EntryBadgeRenderer.drawNameLineLeft(context, line, entry.extraData["gender"] ?: "", tx, rowY, color, entry.extraData["size"] ?: "")
                rowY += 10
            } else {
                context.drawTextWithShadow(textRenderer, line, tx, rowY, color)
                rowY += 10
            }
        }
        context.matrices.pop()
    }

    private fun renderItemTooltip(context: DrawContext, entry: AuctionEntry, mouseX: Int, mouseY: Int) {
        // 静态行（名称/卖家）缓存；价格/倒计时/领先者为动态行每帧构建（见 itemTooltipCacheKey 字段注释）
        if (itemTooltipCacheKey != entry.id) {
            itemTooltipCacheKey = entry.id
            val staticLines = mutableListOf<Pair<Text, Int>>()
            // 物品名走**稀有度色**（原先 `Text.literal(String)` ⇒ 白字）—— 见 [EntryBadgeRenderer.auctionNameText]
            staticLines.add(EntryBadgeRenderer.auctionNameText(entry) to 0xFFFFFF)
            staticLines.add(Text.translatable("cobblemarket.auction.seller").append(entry.sellerName) to 0xFFFFFF)
            var mw = 0
            staticLines.forEach { mw = maxOf(mw, textRenderer.getWidth(it.first)) }
            itemTooltipCacheLines = staticLines
            itemTooltipCacheMaxWidth = mw
        }
        val lines = itemTooltipCacheLines.toMutableList()
        // ⚠ 货币单位**不拼进文本**，由下面的渲染循环补在行尾（物品货币时是那件物品的图标）——
        //   先记下这行的下标（lines 里还混着分割线 / 技能块 / 证章块这些 null 行，不能靠数）
        val priceRow = lines.size
        val priceLine = Text.translatable("cobblemarket.auction.current_price").append(displayPriceText(entry))
        lines.add((if (entry.bidCount > 0)
            priceLine.append(Text.literal("  ×${entry.bidCount}").formatted(Formatting.GRAY))
        else priceLine) to 0xFFFFFF)
        lines.add(
            // ⚠ 与行内同源（见 [com.shusheng.cobblemarket.util.RemainingTime.color]）
            Text.translatable("cobblemarket.auction.ends").append(
                com.shusheng.cobblemarket.util.RemainingTime.text(entry.endsAt, "cobblemarket.auction.ending")
            ) to com.shusheng.cobblemarket.util.RemainingTime.color(entry.endsAt))
        if (entry.currentBidderName.isNotEmpty()) {
            lines.add(Text.translatable("cobblemarket.auction.leader").append(entry.currentBidderName) to 0xFFDD66)
        }

        // 行数按屏幕高钳制（Shift / Ctrl 展开可能几十行）—— 见 [clampTooltipRows]
        clampTooltipRows(lines, height) { n -> Text.translatable("cobblemarket.gui.tooltip_more", n) to 0xAAAAAA }
            .let { if (it !== lines) { lines.clear(); lines.addAll(it) } }
        // ⚠ 价格行还要加单位宽（单位画在行尾、不在文本里）—— 漏了提示框会算窄、图标顶出边框
        val priceUnitW = currencyUnitWidth(textRenderer, entry.isPoints)
        var maxWidth = itemTooltipCacheMaxWidth
        for (i in itemTooltipCacheLines.size until lines.size) {
            maxWidth = maxOf(maxWidth, textRenderer.getWidth(lines[i].first) + if (i == priceRow) priceUnitW else 0)
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
            // 当前价行：货币单位补在行尾（物品货币时是那件物品的图标）
            if (i == priceRow) drawCurrencyUnit(context, textRenderer, entry.isPoints, w, ty + i * 10, 0xFFAA00)
        }
        context.matrices.pop()
    }

    // ── 强制下架弹窗渲染（照搬出价弹窗两列布局） ──

    /**
     * 强制下架弹窗的证章区块额外高度（0 / 44 / 56）：
     * 上下分割线 20 + 图标行 12（超过一行再加 12 的「+N」行）+ 余量（保证按钮不压到左列最后一行）。
     */
    /**
     * 把弹窗底部两枚按钮贴到**当前**布局上 —— 弹窗高度会随 Shift 展开变，
     * 而按钮是打开时定位一次的（见渲染处的说明）。只重贴 `y`：x 与弹窗宽度无关。
     */
    private fun syncCancelDialogButtons(dialogY: Int, dialogH: Int) {
        val y = dialogY + dialogH - cancelButtonDy
        cancelConfirmButton?.y = y
        cancelCancelButton?.y = y
    }

    /** 强制下架弹窗的**物品词条**构建（原版 tooltip 去掉首行物品名；`type` 区分 BASIC / ADVANCED）
     *  —— 照搬出价弹窗的同名函数 */
    private fun buildCancelItemLines(entry: AuctionEntry, type: TooltipType): List<Text> {
        val out = mutableListOf<Text>()
        entry.itemNbt?.let { nbt ->
            client?.world?.registryManager?.let { rm ->
                val stack = ItemStack.fromNbtOrEmpty(rm, nbt)
                if (!stack.isEmpty) {
                    out.addAll(
                        com.shusheng.cobblemarket.client.ItemComponentsDisplay
                            .itemTooltip(stack, client?.player, type).drop(1)
                    )
                }
            }
        }
        return out
    }

    /**
     * 当前该显示哪一份物品词条：按住 Shift / Ctrl 用**展开**那份，否则用基础那份。
     *
     * ⚠ 与出价弹窗同款：按住时**按当前按键组合重建** —— Shift、Ctrl、Shift+Ctrl 展开出的东西
     *   不一样（`tooltipTypeForHover()` 分三种），只在组合真的变了才重建。
     */
    private fun cancelItemLines(entry: AuctionEntry): List<Text> {
        if (!com.shusheng.cobblemarket.client.ItemComponentsDisplay.hoverExpanded()) {
            cancelAdvancedBuiltWithShift = false
            cancelAdvancedType = null
            return cancelItemTooltipLines
        }
        val type = com.shusheng.cobblemarket.client.ItemComponentsDisplay.tooltipTypeForHover()
        if (!cancelAdvancedBuiltWithShift || cancelAdvancedType != type) {
            cancelItemAdvancedLines = buildCancelItemLines(entry, type)
            cancelAdvancedBuiltWithShift = true
            cancelAdvancedType = type
        }
        return cancelItemAdvancedLines
    }

    /**
     * 强制取消弹窗高度：按左列信息区实际内容算——起始 +56，12 基础行
     * （名字/类型/性格/特性/IV 标签/6×IV/亲密度）+ 球种/携带物行 + 证章区块，
     * 底部再留 58px（提示两行在 -54/-44、按钮在 -30，照出价弹窗布局）。
     * 加新信息行（如技能）时只改这里的 12。
     */
    private fun cancelDialogHeight(entry: AuctionEntry): Int {
        // 物品：左列 = 物品名 + 数量 + 词条（取**当前按键下**的那一份：基础 / 展开）
        // ⚠ 与出价弹窗一样**每帧算** ⇒ 按住 Shift 弹窗跟着长高、按钮与提示相对弹窗底定位
        if (entry.type != "POKEMON") {
            return 56 + (2 + cancelItemLines(entry).size) * 10 + 58
        }
        val marks = entry.extraData["marks"].orEmpty().split(",").filter { it.isNotEmpty() }
        val moves = entry.extraData["moves"].orEmpty()
        // 「有才显示」的行数（球种 / 携带物 / 极巨行 / 初始训练家行）走**共用入口** ——
        // 别在这自己加，加行时漏改的就是它（2026-09-27 实测踩过）
        val infoRows = 12 + EntryBadgeRenderer.optionalRowCount(entry.extraData, entry.extraData["heldItemId"].orEmpty())
        return 56 + infoRows * 10 +
            EntryBadgeRenderer.movesBlockHeight(moves) +
            // 证章一行几个跟随可用宽（见 marksPerRow）—— 传本弹窗左列宽（与 rowW 同源）；
            // ⚠ 取**最小**可能宽是保守做法：perRow 取小 ⇒ 更容易判「两行」⇒ 高度**宁多不少**
            EntryBadgeRenderer.marksBlockHeight(marks, moves, 124) + 58
    }

    private fun renderCancelDialogBackground(context: DrawContext, delta: Float) {
        val entry = cancelEntry ?: return
        val centerX = width / 2
        // 右列（竞拍信息）实测最宽：金额随拍品变化（100,000,000 这类），不量的话会长出弹窗右边框
        // ⚠ 测量必须与**绘制**同源：这三行现在都把货币单位**画在行尾**（不拼进文本，
        //   见 auctionLine 的 unitColor），所以每行宽度 = 文本宽 + 单位宽
        val rightW = maxOf(
            textRenderer.getWidth(Text.translatable("cobblemarket.auction.seller").append(entry.sellerName)),
            textRenderer.getWidth(
                Text.translatable("cobblemarket.auction.current_price").append(displayPriceText(entry))
            ) + currencyUnitWidth(textRenderer, entry.isPoints),
            textRenderer.getWidth(Text.translatable("cobblemarket.auction.starting_price_label")) +
                textRenderer.getWidth(com.shusheng.cobblemarket.client.formatPrice(entry.startingPrice) + " ") +
                currencyUnitWidth(textRenderer, entry.isPoints),
            textRenderer.getWidth(Text.translatable("cobblemarket.auction.min_increment_label")) +
                textRenderer.getWidth(com.shusheng.cobblemarket.client.formatPrice(entry.minIncrement) + " ") +
                currencyUnitWidth(textRenderer, entry.isPoints)
        )
        // 左列默认 140（infoX+10 ~ auctionX），右列 rightW + 20 右边距；
        // ⚠ **物品模式**左列还有词条，长行会把左列撑宽 ⇒ 并上词条实测宽（与出价弹窗同一套做法）
        val itemLines = if (entry.type == "POKEMON") emptyList() else cancelItemLines(entry)
        var maxLineW = 0
        itemLines.forEach { maxLineW = maxOf(maxLineW, textRenderer.getWidth(it)) }
        val dialogW = maxOf(340, 180 + rightW, maxLineW + rightW + 50).coerceAtMost(width - 20)
        val dialogH = cancelDialogHeight(entry)
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2
        // ⚠ **每帧把按钮贴回当前布局**：词条会随 Shift 展开变多 ⇒ 弹窗长高、下面那两行提示跟着下移，
        //   而按钮是**打开弹窗时定位一次**的 ⇒ 不重贴就钉在原地，还会把已经下移的提示**压住**
        //  （2026-09-29 用户截图报出）。与物品购买弹窗的 `syncBuyDialogControls` 是同一类修法
        syncCancelDialogButtons(dialogY, dialogH)

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.auction.force_cancel").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        // ── 左列：预览槽（精灵 3D / 物品图标） ──
        val slotSize = 28
        val slotX = dialogX + 10
        val slotY = dialogY + 20
        if (entry.type == "POKEMON") {
            context.matrices.push()
            context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
            context.matrices.scale(slotSize / 66f, slotSize / 66f, 1f)
            context.drawTexture(POKEMON_SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
            context.matrices.pop()
            cancelRenderable?.let { rp ->
                // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
                val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                    com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
                try {
                    PokemonIconRenderer.draw(
                        context = context,
                        renderable = rp,
                        state = cancelPreviewState.stateFor(rp),
                        x = slotX, y = slotY, size = slotSize,
                        delta = delta,
                        animate = useFloat
                    )
                    // 打开弹窗时自动叫一声（替玩家点一下这个图标）：**必须在渲染之后** ——
                    // addFirstAnimation 依赖 currentModel，那是 drawProfilePokemon 里才建好的；
                    // 再叠一层 AUTO_CRY_DELAY_MS 延迟，等弹窗稳定显示、玩家看清了再叫
                    if (cancelCryAt != 0L && System.currentTimeMillis() >= cancelCryAt) {
                        cancelCryAt = 0L
                        IconCryClick.play(cancelPreviewState.stateFor(rp), rp)
                    }
                } catch (_: Exception) {
                }
            }
            // 「非法」角标：贴图标**右上角**（见 [EntryBadgeRenderer.drawIllegalBadge]）。
            // ⚠ 只在**精灵拍卖**这一支（物品拍卖画的是物品图标，没有"这只精灵"的概念）
            if (entry.illegal) {
                EntryBadgeRenderer.drawIllegalBadge(context, slotX, slotY, slotSize)
            }
        } else {
            // 物品拍卖：走完整 NBT 重建，附魔光效与自定义名称才显示得出来
            iconStackOf(entry.species, entry.itemNbt)?.let { stack ->
                if (!stack.isEmpty) drawItemWithBar(context, stack, slotX + 6, slotY + 6)
            }
        }

        // ── 左列：精灵/物品完整信息（照搬出价弹窗） ──
        val infoX = dialogX + 10
        var iy = dialogY + 56
        fun infoLine(text: String, color: Int = 0xFFFFFF) {
            context.drawTextWithShadow(textRenderer, text, infoX, iy, color)
            iy += 10
        }
        // Text 版（EV 红字等富文本行用；color = IV 行基础色，Text 内 formatted 段颜色覆盖 EV 段）
        fun infoLineText(text: net.minecraft.text.Text, color: Int = 0xFFFFFF) {
            context.drawTextWithShadow(textRenderer, text, infoX, iy, color)
            iy += 10
        }
        if (entry.type == "POKEMON") {
            val extra = entry.extraData
            val primaryType = extra["primaryType"] ?: ""
            // 名字★Lv 行走全项目统一入口（名字本体主属性色、★ 金、Lv 吃行色白）——
            // 原来这里是手写的三段（名字 / ★ / Lv 各画一次），★ 前导 2px、Lv 前导 2px+两个空格，
            // 与别处的「一个空格 + 两个空格」对不上，★ 也漏了阴影。
            context.drawTextWithShadow(
                textRenderer,
                EntryBadgeRenderer.nameWithLevelLine(
                    EntryBadgeRenderer.nameWithShinyStar(displayName(entry), entry.shiny),
                    // primaryType 为空时原代码退到普通系色，改走统一入口后要把这个 fallback 显式带上
                    // （`typeColor("")` 会落 else → 纯白，颜色就变了）
                    if (primaryType.isNotEmpty()) primaryType else "cobblemon.type.normal",
                    entry.level
                ),
                infoX, iy, 0xFFFFFF
            )
            iy += 10
            // 昵称行：**名字下面一行**、有才画（用户 2026-10-06 定）——
            // ⚠ 这里逐行 `iy` 增量自画（不走 drawInfoLines），弹窗高度公式那边也加了同一判断，两处成对
            EntryBadgeRenderer.nicknameLine(extra["nickname"])?.let { infoLineText(it) }
            val secondaryType = extra["secondaryType"] ?: ""
            val typeText = (if (primaryType.isNotEmpty()) Text.translatable(primaryType).string else "-") +
                if (secondaryType.isNotEmpty()) " + ${Text.translatable(secondaryType).string}" else ""
            // 属性行（**含太晶**）：共用同一个组装入口
            infoLineText(com.shusheng.cobblemarket.util.TypeTextColors.typeLineWithTera(
                primaryType, secondaryType, extra["teraType"].orEmpty()))
            // 极巨行：属性行下面、有才显示（与市场侧走同一个组装入口；没有则整行不占位）
            com.shusheng.cobblemarket.util.PokemonExtraLines.dynamax(
                extra["gmaxFactor"] == "true", extra["dmaxLevel"]?.toIntOrNull() ?: 0
            )?.let { infoLineText(it) }
            // 性格（薄荷约定：原生斜体+括号生效）
            context.drawTextWithShadow(textRenderer,
                Text.literal(Text.translatable("cobblemarket.gui.tooltip_nature").string)
                    .append(EntryBadgeRenderer.natureText(extra["natureBase"] ?: "", extra["nature"] ?: "")),
                infoX, iy, 0xFFFFFF)
            iy += 10
            infoLine("${Text.translatable("cobblemarket.gui.tooltip_ability").string}${Text.translatable(extra["ability"] ?: "").string}")
            // 球种行：特性下面（原缺失，2026-09-27 补齐）；球名走**稀有度色**
            extra["ball"]?.takeIf { it.isNotEmpty() }?.let {
                infoLineText(com.shusheng.cobblemarket.util.ItemNameText.ballLine(it, extra["ballItem"].orEmpty()))
            }
            val heldItemId = extra["heldItemId"].orEmpty()
            val hasHeldItem = heldItemId.isNotEmpty() &&
                Identifier.tryParse(heldItemId)?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true
            if (hasHeldItem) {
                // 携带物行：标签（白色，与其他行一致）+ 右侧物品图标
                val heldLabel = Text.translatable("cobblemarket.gui.tooltip_held").string
                val heldY = iy
                infoLine(heldLabel)
                val heldIconX = infoX + textRenderer.getWidth(heldLabel) + 2
                heldIconStack(heldItemId, extra["heldItemNbt"])?.let { heldStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = heldStack,
                        x = heldIconX.toDouble(),
                        y = heldY + 0.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                }
                // 玩家把携带物设成「隐藏」时注明一句（模型上不显示它，而信息行照常列出）—— 用户 2026-09-26
                if (extra["heldItemVisible"] == "false") {
                    context.drawTextWithShadow(
                        textRenderer, Text.translatable("cobblemarket.gui.held_hidden"), heldIconX + 12, heldY, 0xAAAAAA
                    )
                }
            }
            val hp = StatNames.HP
            val atk = StatNames.ATK
            val def = StatNames.DEF
            val spa = StatNames.SP_ATK
            val spd = StatNames.SP_DEF
            val spe = StatNames.SPEED
            infoLine(Text.translatable("cobblemarket.gui.tooltip_ivs").string)
            infoLineText(Text.literal("  $hp: ${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsHp"]?.toIntOrNull() ?: 0, htExtra(extra, "htHp"))}").append(Text.literal("  EV: ${extra["evsHp"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)), 0x66FF66); infoLineText(Text.literal("  $atk: ${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsAtk"]?.toIntOrNull() ?: 0, htExtra(extra, "htAtk"))}").append(Text.literal("  EV: ${extra["evsAtk"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)), 0xFF6666)
            infoLineText(Text.literal("  $def: ${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsDef"]?.toIntOrNull() ?: 0, htExtra(extra, "htDef"))}").append(Text.literal("  EV: ${extra["evsDef"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)), 0xFFCC66); infoLineText(Text.literal("  $spa: ${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpAtk"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpAtk"))}").append(Text.literal("  EV: ${extra["evsSpAtk"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)), 0x6699FF)
            infoLineText(Text.literal("  $spd: ${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpDef"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpDef"))}").append(Text.literal("  EV: ${extra["evsSpDef"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)), 0x66FF99); infoLineText(Text.literal("  $spe: ${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpd"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpd"))}").append(Text.literal("  EV: ${extra["evsSpd"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)), 0xFF99FF); infoLineText(Text.translatable("cobblemarket.gui.friendship", extra["friendship"]?.toIntOrNull() ?: 0), 0xFF99CC)
            // 初始训练家行：亲密度下面（与市场侧同位置；没有则整行不占位）
            com.shusheng.cobblemarket.util.PokemonExtraLines.originalTrainer(extra["ot"].orEmpty())
                ?.let { infoLineText(it) }
            // 技能块（**总是画** —— Cobblemon 里不存在没技能的精灵）：分割线 + 两行技能 + 分割线
            val movesRaw = extra["moves"].orEmpty()
            // ⚠ **固定 124** —— 与出价弹窗（`AuctionScreen` 的同名处）**同一个口径**：
            //   这两个弹窗是同一套两列布局（左列精灵信息 + 右列竞拍信息），技能块宽度必须一致。
            // ⚠ **别写成 `dialogW − k`**：那样弹窗一宽技能块就跟着拉长，跟出价弹窗对不上，
            //   而且左列信息区根本没这么宽（用户 2026-09-27 对比两张弹窗截图实测报出）。
            //   出价弹窗那边早就写明过这条（它的注释：「写成 dialogW − 156 的话，技能块和上下两条
            //   分割线会跟着弹窗一起变长」）—— 这里当时漏了。
            val rowW = 124
            if (MoveText.hasAny(movesRaw)) {
                iy += EntryBadgeRenderer.drawMovesBlockLeft(context, movesRaw, infoX, rowW, iy)
            }
            // 证章区块：技能块下方，图标行 + 下分割线（居中，一行几个跟随可用宽 —— 2026-09-29 与出价弹窗统一）
            // 没有技能块（旧数据）时自己补回上分割线
            val marks = extra["marks"].orEmpty().split(",").filter { it.isNotEmpty() }
            if (marks.isNotEmpty()) {
                if (!MoveText.hasAny(movesRaw)) {
                    context.fill(infoX, iy + 4, infoX + rowW, iy + 5, 0xFF888888.toInt())
                    iy += 10
                }
                // ⚠ `centered = true`：与出价弹窗同一处口径 —— 证章在内容宽里居中，别左对齐
                //（2026-09-29 与出价界面一起改的，见 AuctionScreen 同处的说明）
                iy += EntryBadgeRenderer.drawMarksRow(
                    context, marks, infoX, iy, rowW, centered = true,
                    // 弹窗 ⇒ 接「+N」的二级证章窗（拿不到 render 鼠标参数，用登记的当前帧坐标）
                    mouseX = com.shusheng.cobblemarket.client.icon.IconRegistry.mouseX,
                    mouseY = com.shusheng.cobblemarket.client.icon.IconRegistry.mouseY
                )
                EntryBadgeRenderer.drawMarksMoreTooltip(context)
                context.fill(infoX, iy + 4, infoX + rowW, iy + 5, 0xFF888888.toInt())
                iy += 10
            }
        } else {
            // 物品名走**稀有度色** —— `displayName()` 返回 String，直接画必然白字（2026-09-26 用户报）
            infoLineText(EntryBadgeRenderer.auctionNameText(entry))
            infoLine("×${entry.count}")
            // 完整词条（附魔 / 属性加成 / 药水 / 自定义说明 / 耐久…）：原版 tooltip 渲染，与背包里
            // 悬停看到的一模一样；**按住 Shift / Ctrl 换成展开那份**（工具提示里带 "按住 Shift" 之类的
            // 原版语义也一并继承）—— 与出价弹窗逻辑一致（2026-09-29 用户选 A）
            cancelItemLines(entry).forEach { infoLineText(it) }
        }

        // ── 右列：竞拍信息 ──
        val auctionX = dialogX + dialogW - (rightW + 20)
        var ay = dialogY + 28
        // ⚠ 货币单位**不拼进文本**，画完本行后补在行尾（物品货币时是那件物品的图标）；
        //   由 `unitColor` 非空开启，颜色照同一行里金额那一段（金额金色 ⇒ 单位也金色）
        // ⚠ 弹窗自己的货币图标不受门禁管（同 EntryBadgeRenderer.drawInfoLines 的说明）：
        //   遮罩是这个界面自己画的，不关掉的话弹窗里的单位会被自己的遮罩挡住
        com.shusheng.cobblemarket.client.suppressCurrencyIcons = false
        fun auctionLine(text: Text, color: Int = 0xFFFFFF, unitColor: Int? = null) {
            val w = context.drawTextWithShadow(textRenderer, text, auctionX, ay, color)
            if (unitColor != null) drawCurrencyUnit(context, textRenderer, entry.isPoints, w, ay, unitColor)
            ay += 10
        }
        auctionLine(Text.translatable("cobblemarket.auction.seller").append(entry.sellerName))
        // 价格三行：标签默认色，金额段蓝色（2026-08-24 拍板）
        // ⚠ 三行金额的单位都**不拼进文本**，由 auctionLine 补画在行尾（物品货币时是物品图标）
        auctionLine(Text.translatable("cobblemarket.auction.current_price").append(
            displayPriceText(entry)), unitColor = 0xFFAA00)
        auctionLine(Text.translatable("cobblemarket.auction.starting_price_label").append(
            Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.startingPrice)} ").formatted(Formatting.GOLD)), unitColor = 0xFFAA00)
        auctionLine(Text.translatable("cobblemarket.auction.min_increment_label").append(
            Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.minIncrement)} ").formatted(Formatting.GOLD)), unitColor = 0xFFAA00)
        auctionLine(
            Text.translatable("cobblemarket.auction.ends").append(
                com.shusheng.cobblemarket.util.RemainingTime.text(entry.endsAt, "cobblemarket.auction.ending")
            ), 0xAAAAAA)
        auctionLine(Text.translatable("cobblemarket.auction.bids_count").append("${entry.bidCount}"), 0xAAAAAA)
        if (entry.currentBidderName.isNotEmpty()) {
            auctionLine(Text.translatable("cobblemarket.auction.leader").append(entry.currentBidderName), 0xFFDD66)
        }
        // 提示两排放按钮上方居中，避开左右两列信息
        // 灰字与按钮组中心对齐（按钮右移 10px 后中心在 centerX+10）
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.auction.force_cancel_confirm_1").formatted(Formatting.GRAY),
            centerX + 32, dialogY + dialogH - 54, 0xFFFFFF)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.auction.force_cancel_confirm_2").formatted(Formatting.GRAY),
            centerX + 32, dialogY + dialogH - 44, 0xFFFFFF)
    }

    // ── 交互 ──

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (cancelEntry != null) return false
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, filteredEntries().size - getMaxVisibleRows()))
        return true
    }

    private fun isInputFieldFocused() = focused === searchField

    private fun isMouseOverSearch(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 图标点击叫声：本界面**点行会开取消弹窗**，下层行图标随即不再渲染（主 render 直接 return），
        // 触发在它身上的叫声会哑掉 —— 所以这里不做行内整行触发，叫声改由弹窗图标承担（见 openCancelDialog）
        if (button == 0) IconCryClick.playIconAt(mouseX, mouseY)
        if (cancelEntry != null) {
            return super.mouseClicked(mouseX, mouseY, button)
        }
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverSearch(mouseX, mouseY)) {
            focused = null
        }
        if (hoveredRow >= 0) {
            val filtered = filteredEntries()
            val idx = scrollOffset + hoveredRow
            if (idx in filtered.indices) {
                // 行是**自绘**的（不走 NineSliceButton），点击音得自己播 ——
                // 缺了它，点行弹「取消拍卖确认」时只有这一处没声音
                playButtonClickSound()
                openCancelDialog(filtered[idx])
                return true
            }
        }
        return result
    }

    // 挂单 extra 中的特训值（字符串，缺省/负数 = 未特训）
    private fun htExtra(extra: Map<String, String>, key: String): Int = extra[key]?.toIntOrNull() ?: -1

    override fun shouldPause() = false

    private companion object {
        val GENDER_ICON_MALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
        val GENDER_ICON_FEMALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
        val POKEMON_SLOT_TEXTURE = Identifier.of("cobblemarket", "textures/gui/pokemon/pokemon_slot.png")
        val HAMMER_TEX_LEFT = Identifier.of("cobblemarket", "textures/gui/auction/auction_gavel_left.png")
        val HAMMER_TEX_LEFT_NO = Identifier.of("cobblemarket", "textures/gui/auction/auction_gavel_left_no.png")
    }
}
