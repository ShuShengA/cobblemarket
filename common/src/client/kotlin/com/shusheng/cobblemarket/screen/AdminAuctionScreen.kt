package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.util.MoveText
import com.shusheng.cobblemarket.util.StatNames
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.shusheng.cobblemarket.client.icon.PreviewStateHolder
import com.shusheng.cobblemarket.client.heldIconStack
import com.shusheng.cobblemarket.client.iconStackOf
import com.shusheng.cobblemarket.client.PointsStateCache
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_SRC
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_TEXTURE
import com.shusheng.cobblemarket.client.CURRENCY_BUTTON_ICON_SIZE
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
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
    private fun onCurrencyClick() {
        // ⚠ 管理端**不拦开关**：管理员要能查看并下架存量的 RP 挂单
        currency = if (currency == MarketCurrency.POINTS) MarketCurrency.PRIMARY else MarketCurrency.POINTS
        currencyButton?.let { updateCurrencyButton(it) }
        // 本地先按新币种过一遍（旧币种条目立刻消失），再向服务端要新币种的全量列表
        rebuildFiltered()
        sendToServer(RequestAuctionListPayload(currency.name))
    }

    /** 刷新货币按钮外观：主货币显示 `₽`，RP 显示图标（与市场/上架界面同一套口径） */
    private fun updateCurrencyButton(btn: NineSliceButton) {
        val points = currency == MarketCurrency.POINTS
        btn.setMessage(Text.literal(if (points) "" else "₽"))
        btn.iconLeft = if (points) POINTS_SYMBOL_TEXTURE else null
    }

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

    /** 价格文本：金额+单位金色；无出价时「起拍」前缀白色（2026-08-24 拍板：文字默认色）。
     *  单位按**这场拍卖自己的币种**（主货币 ₽ / RP 图标），走统一入口 `amountWithUnit` */
    private fun displayPriceText(entry: AuctionEntry): Text {
        val price = if (entry.currentPrice > 0) entry.currentPrice else entry.startingPrice
        val priceT = com.shusheng.cobblemarket.client.amountWithUnit(
            com.shusheng.cobblemarket.client.formatPrice(price), entry.isPoints, "", inline = true
        ).formatted(Formatting.GOLD)
        return if (entry.currentPrice > 0) priceT
        else Text.translatable("cobblemarket.auction.from").append(priceT)
    }

    /** 行内价格（缩写、无「起拍」前缀，与物品市场/求购单行内一致）：悬停与出价弹窗仍走 [displayPriceText] 千分位 */
    private fun displayPriceCompactText(entry: AuctionEntry): Text =
        com.shusheng.cobblemarket.client.amountWithUnit(
            com.shusheng.cobblemarket.client.formatPriceShort(
                if (entry.currentPrice > 0) entry.currentPrice else entry.startingPrice
            ),
            entry.isPoints, "", inline = true
        ).formatted(Formatting.GOLD)

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
            iconData[i] = PokemonIconData(displayName(entry), RenderablePokemon(species, aspects, held ?: ItemStack.EMPTY), FloatingState())
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
    }

    // ── 初始化与数据 ──

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        val backBtn = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(AdminScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
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
                tooltip = Text.translatable("cobblemarket.gui.currency_switch", inlineCurrencyUnit(), CurrencyService.pointsIcon())
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
                cancelRenderable = RenderablePokemon(species, aspects, heldIconStack(entry.extraData) ?: ItemStack.EMPTY)
            }
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
            centerX - 50, dialogY + dialogH - 30, 80, 20,
            Text.translatable("cobblemarket.auction.force_cancel"),
            { confirmCancel() }
        )
        addDrawableChild(cancelConfirmButton)
        cancelCancelButton = NineSliceButton(
            centerX + 34, dialogY + dialogH - 30, 80, 20,
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
                Text.translatable("cobblemarket.auction.empty").formatted(Formatting.GRAY),
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
                sx += 2
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
                val name = com.shusheng.cobblemarket.util.TextUtil.truncateString(
                    displayName(entry), 100 - textRenderer.getWidth(countSuffix)) + countSuffix
                context.drawTextWithShadow(textRenderer, name, leftX + 28, y + 7, 0xFFFFFF)
            }

            // 当前价（行内缩写）+ 出价次数（灰，拆段）：货币单位紧跟 ×次数，中间不留空格
            val priceStr = displayPriceCompactText(entry)
            val bidPart = if (entry.bidCount > 0) "×${entry.bidCount}" else ""
            // 价格区右端与拍卖场一致（本界面无行内出价按钮，右侧为纯余量）
            val priceX = leftX + panelWidth - 54 - textRenderer.getWidth(priceStr) - textRenderer.getWidth(bidPart)

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
            if (bidPart.isNotEmpty()) {
                context.drawTextWithShadow(textRenderer, bidPart,
                    priceX + textRenderer.getWidth(priceStr), y + 7, 0xAAAAAA)
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
            staticLines.add(Text.literal(Text.translatable("cobblemarket.gui.tooltip_type").string)
                .append(EntryBadgeRenderer.typeLine(primaryType, secondaryType)) to 0xFFFFFF)
            staticLines.add(Text.literal(Text.translatable("cobblemarket.gui.tooltip_nature").string)
                .append(EntryBadgeRenderer.natureText(extra["natureBase"] ?: "", extra["nature"] ?: ""))
                .append(Text.literal("  ${Text.translatable("cobblemarket.gui.tooltip_ability").string}"))
                .append(Text.translatable(extra["ability"] ?: "")) to 0xFFFFFF)
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
            // 证章行宽（每行 MARKS_PER_ROW 个 12px 格）
            if (marksLine >= 0) {
                mw = maxOf(mw, minOf(EntryBadgeRenderer.MARKS_PER_ROW, extra["marks"].orEmpty().split(",").count { it.isNotEmpty() }) * 12)
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
        // 分割线：上方精灵信息，下方拍卖信息
        lines.add(null to 0xFFFFFF)
        lines.add(Text.translatable("cobblemarket.auction.seller").append(entry.sellerName) to 0xFFFFFF)
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
        val marksExtra = if (marksLine >= 0 && marks.size > EntryBadgeRenderer.MARKS_PER_ROW) 12 else 0
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
                // 证章图标行：左对齐排（照市场悬停；超过 6 个第二行左对齐显示「+N」）
                rowY += EntryBadgeRenderer.drawMarksRow(context, marks, tx, rowY)
            } else if (line == null) {
                // 分割线：1px 灰线，撑满提示框全宽（与信息区分隔线一致，不随证章数量变化）
                val rowW = mw
                context.fill(tx, rowY + 4, tx + rowW, rowY + 5, 0xFF888888.toInt())
                rowY += 10
            } else if (i == heldItemLine) {
                context.drawTextWithShadow(textRenderer, line, tx, rowY, color)
                heldIconStack(entry.extraData)?.let { heldStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = heldStack,
                        x = tx + textRenderer.getWidth(line) + 2.0,
                        y = rowY + 0.0,
                        scale = 0.6,
                        matrixStack = context.matrices
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
            staticLines.add(Text.literal(displayName(entry)) to 0xFFFFFF)
            staticLines.add(Text.translatable("cobblemarket.auction.seller").append(entry.sellerName) to 0xFFFFFF)
            var mw = 0
            staticLines.forEach { mw = maxOf(mw, textRenderer.getWidth(it.first)) }
            itemTooltipCacheLines = staticLines
            itemTooltipCacheMaxWidth = mw
        }
        val lines = itemTooltipCacheLines.toMutableList()
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

        var maxWidth = itemTooltipCacheMaxWidth
        for (i in itemTooltipCacheLines.size until lines.size) {
            maxWidth = maxOf(maxWidth, textRenderer.getWidth(lines[i].first))
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
            context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
        }
        context.matrices.pop()
    }

    // ── 强制下架弹窗渲染（照搬出价弹窗两列布局） ──

    /**
     * 强制下架弹窗的证章区块额外高度（0 / 44 / 56）：
     * 上下分割线 20 + 图标行 12（超过一行再加 12 的「+N」行）+ 余量（保证按钮不压到左列最后一行）。
     */
    /**
     * 强制取消弹窗高度：按左列信息区实际内容算——起始 +56，12 基础行
     * （名字/类型/性格/特性/IV 标签/6×IV/亲密度）+ 球种/携带物行 + 证章区块，
     * 底部再留 58px（提示两行在 -54/-44、按钮在 -30，照出价弹窗布局）。
     * 加新信息行（如技能）时只改这里的 12。
     */
    private fun cancelDialogHeight(entry: AuctionEntry): Int {
        val marks = entry.extraData["marks"].orEmpty().split(",").filter { it.isNotEmpty() }
        val moves = entry.extraData["moves"].orEmpty()
        val infoRows = 12 +
            (if (entry.extraData["ball"].orEmpty().isNotEmpty()) 1 else 0) +
            (if (EntryBadgeRenderer.hasHeldItemLine(entry.extraData["heldItemId"].orEmpty())) 1 else 0)
        return 56 + infoRows * 10 +
            EntryBadgeRenderer.movesBlockHeight(moves) +
            EntryBadgeRenderer.marksBlockHeight(marks, moves) + 58
    }

    private fun renderCancelDialogBackground(context: DrawContext, delta: Float) {
        val entry = cancelEntry ?: return
        val centerX = width / 2
        // 右列（竞拍信息）实测最宽：金额随拍品变化（100,000,000 这类），不量的话会长出弹窗右边框
        val rightW = listOf(
            Text.translatable("cobblemarket.auction.seller").append(entry.sellerName),
            Text.translatable("cobblemarket.auction.current_price").append(displayPriceText(entry)),
            Text.translatable("cobblemarket.auction.starting_price_label").append(
                Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.startingPrice)} ${com.shusheng.cobblemarket.client.inlineCurrencyUnit()}")),
            Text.translatable("cobblemarket.auction.min_increment_label").append(
                Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.minIncrement)} ${com.shusheng.cobblemarket.client.inlineCurrencyUnit()}"))
        ).maxOf { textRenderer.getWidth(it) }
        // 左列固定 140（infoX+10 ~ auctionX），右列 rightW + 20 右边距
        val dialogW = maxOf(340, 180 + rightW).coerceAtMost(width - 20)
        val dialogH = cancelDialogHeight(entry)
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

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
            val tc = typeColor(if (primaryType.isNotEmpty()) primaryType else "cobblemon.type.normal")
            context.drawTextWithShadow(textRenderer, displayName(entry), infoX, iy, tc)
            var cx = infoX + textRenderer.getWidth(displayName(entry))
            if (entry.shiny) {
                context.drawText(textRenderer, "★", cx + 2, iy, GOLD_COLOR, false)
                cx += 2 + textRenderer.getWidth("★")
            }
            context.drawText(textRenderer, "  Lv.${entry.level}", cx + 2, iy, 0xFFFFFF, false)
            iy += 10
            val secondaryType = extra["secondaryType"] ?: ""
            val typeText = (if (primaryType.isNotEmpty()) Text.translatable(primaryType).string else "-") +
                if (secondaryType.isNotEmpty()) " + ${Text.translatable(secondaryType).string}" else ""
            infoLineText(Text.literal(Text.translatable("cobblemarket.gui.tooltip_type").string)
                .append(EntryBadgeRenderer.typeLine(primaryType, secondaryType)))
            // 性格（薄荷约定：原生斜体+括号生效）
            context.drawTextWithShadow(textRenderer,
                Text.literal(Text.translatable("cobblemarket.gui.tooltip_nature").string)
                    .append(EntryBadgeRenderer.natureText(extra["natureBase"] ?: "", extra["nature"] ?: "")),
                infoX, iy, 0xFFFFFF)
            iy += 10
            infoLine("${Text.translatable("cobblemarket.gui.tooltip_ability").string}${Text.translatable(extra["ability"] ?: "").string}")
            val heldItemId = extra["heldItemId"].orEmpty()
            val hasHeldItem = heldItemId.isNotEmpty() &&
                Identifier.tryParse(heldItemId)?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true
            if (hasHeldItem) {
                // 携带物行：标签（白色，与其他行一致）+ 右侧物品图标
                val heldLabel = Text.translatable("cobblemarket.gui.tooltip_held").string
                val heldY = iy
                infoLine(heldLabel)
                heldIconStack(heldItemId, extra["heldItemNbt"])?.let { heldStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = heldStack,
                        x = infoX + textRenderer.getWidth(heldLabel) + 2.0,
                        y = heldY + 0.0,
                        scale = 0.6,
                        matrixStack = context.matrices
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
            // 技能块（**总是画** —— Cobblemon 里不存在没技能的精灵）：分割线 + 两行技能 + 分割线
            val movesRaw = extra["moves"].orEmpty()
            // ⚠ 与技术块/证章的分割线口径对齐（市场确认弹窗、管理端审核弹窗都是 dialogW − 20）：
            // infoX 已经是 dialogX + 10，所以右边正好也留 10px，分割线居中且与弹窗边距一致。
            // 原先写 dialogW − 156（左列宽）—— 长度和别处对不上，右边空一大片。
            val rowW = dialogW - 156
            if (MoveText.hasAny(movesRaw)) {
                iy += EntryBadgeRenderer.drawMovesBlockLeft(context, movesRaw, infoX, rowW, iy)
            }
            // 证章区块：技能块下方，图标行 + 下分割线（左对齐，每行 MARKS_PER_ROW 个，照出价弹窗）
            // 没有技能块（旧数据）时自己补回上分割线
            val marks = extra["marks"].orEmpty().split(",").filter { it.isNotEmpty() }
            if (marks.isNotEmpty()) {
                if (!MoveText.hasAny(movesRaw)) {
                    context.fill(infoX, iy + 4, infoX + rowW, iy + 5, 0xFF888888.toInt())
                    iy += 10
                }
                iy += EntryBadgeRenderer.drawMarksRow(context, marks, infoX, iy)
                context.fill(infoX, iy + 4, infoX + rowW, iy + 5, 0xFF888888.toInt())
                iy += 10
            }
        } else {
            infoLine(displayName(entry))
            infoLine("×${entry.count}")
        }

        // ── 右列：竞拍信息 ──
        val auctionX = dialogX + dialogW - (rightW + 20)
        var ay = dialogY + 28
        fun auctionLine(text: String, color: Int = 0xFFFFFF) {
            context.drawTextWithShadow(textRenderer, text, auctionX, ay, color)
            ay += 10
        }
        fun auctionLine(text: Text, color: Int = 0xFFFFFF) {
            context.drawTextWithShadow(textRenderer, text, auctionX, ay, color)
            ay += 10
        }
        auctionLine(Text.translatable("cobblemarket.auction.seller").append(entry.sellerName))
        // 价格三行：标签默认色，金额段蓝色（2026-08-24 拍板）
        auctionLine(Text.translatable("cobblemarket.auction.current_price").append(
            displayPriceText(entry)))
        auctionLine(Text.translatable("cobblemarket.auction.starting_price_label").append(
            com.shusheng.cobblemarket.client.amountWithUnit(
                com.shusheng.cobblemarket.client.formatPrice(entry.startingPrice), entry.isPoints, "", inline = true
            ).formatted(Formatting.GOLD)))
        auctionLine(Text.translatable("cobblemarket.auction.min_increment_label").append(
            com.shusheng.cobblemarket.client.amountWithUnit(
                com.shusheng.cobblemarket.client.formatPrice(entry.minIncrement), entry.isPoints, "", inline = true
            ).formatted(Formatting.GOLD)))
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
        val POKEMON_SLOT_TEXTURE = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
        val HAMMER_TEX_LEFT = Identifier.of("cobblemarket", "textures/gui/auction_gavel_left.png")
        val HAMMER_TEX_LEFT_NO = Identifier.of("cobblemarket", "textures/gui/auction_gavel_left_no.png")
    }
}
