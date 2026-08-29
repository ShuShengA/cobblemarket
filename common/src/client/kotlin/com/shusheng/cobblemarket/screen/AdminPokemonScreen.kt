package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.network.AdminCancelPokemonPayload
import com.shusheng.cobblemarket.network.AdminRequestPokemonPayload
import com.shusheng.cobblemarket.network.ListingEntry
import com.shusheng.cobblemarket.network.MarketDataPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.joml.Quaternionf

class AdminPokemonScreen : Screen(Text.translatable("cobblemarket.op.pokemon")) {

    private var listings = listOf<ListingEntry>()
    private var currentPage = 1
    private var totalPages = 1
    // 搜索防抖（照精灵市场）：250ms 静默后统一发请求，避免连续输入被服务端节流丢包
    private var searchDirty = false
    private var lastSearchEdit = 0L

    private var searchField: TextFieldWidget? = null
    private var sellerField: TextFieldWidget? = null
    private var shinyOnly = false
    private var showMineOnly = false
    private var filterExpanded = false
    private var sortMode = "NEWEST"
    private val ivExact = IntArray(6) { -1 }
    // IV 比较方式：0 = 等于（默认），1 = 大于等于，2 = 小于等于
    private val ivOps = IntArray(6)
    private val ivOpButtons = mutableMapOf<Int, NineSliceButton>()
    // 特训筛选三态：0 = 不限，1 = 仅含训练，2 = 仅不含训练
    private var htFilter = 0
    private var htButton: NineSliceButton? = null

    private lateinit var shinyButton: NineSliceButton
    private lateinit var sortButton: ButtonWidget
    private lateinit var resetButton: ButtonWidget
    private lateinit var mineButton: ButtonWidget
    private lateinit var filterToggleButton: ButtonWidget
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

    private var confirmEntry: ListingEntry? = null
    private var confirmRenderable: RenderablePokemon? = null
    private var confirmDisplayName = ""
    private val confirmState = FloatingState()

    private data class IconData(
        val displayName: String,
        val renderable: RenderablePokemon,
        val state: FloatingState,
        // 球种/携带物栈随图标缓存：render 每帧 new ItemStack 是分配热点，构建一次复用
        val ballStack: ItemStack?,
        val heldStack: ItemStack?
    )
    private val iconData = mutableMapOf<Int, IconData>()

    private fun cacheIcons() {
        iconData.clear()
        listings.forEachIndexed { index, entry ->
            val id = Identifier.tryParse(entry.speciesId) ?: return@forEachIndexed
            val species = PokemonSpecies.getByIdentifier(id) ?: return@forEachIndexed
            // 用挂单真实 aspects（性别形态/地区形态等），与其他界面一致
            val aspects = entry.aspects.toMutableSet()
            if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
            iconData[index] = IconData(
                species.translatedName.string,
                RenderablePokemon(species, aspects, ItemStack.EMPTY),
                FloatingState(),
                ballStack = Identifier.tryParse(entry.ballItem)?.let { ItemStack(Registries.ITEM.get(it)) },
                heldStack = buildHeldStack(entry.heldItemId)
            )
        }
    }

    /** 携带物栈（空气视为无）；缓存供行渲染每帧复用 */
    private fun buildHeldStack(heldItemId: String): ItemStack? {
        if (heldItemId.isEmpty()) return null
        val heldId = Identifier.tryParse(heldItemId) ?: return null
        val heldItem = Registries.ITEM.get(heldId)
        return if (heldItem != Registries.ITEM.get(Identifier.of("minecraft", "air"))) ItemStack(heldItem) else null
    }

    override fun init() {
        super.init()
        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        // 返回
        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(AdminScreen()) }
        ))

        // 物种搜索框 + 折叠
        val savedSearch = searchField?.text ?: ""
        val savedSeller = sellerField?.text ?: ""
        searchField = TextFieldWidget(textRenderer, leftX + 2, 44, panelWidth - 4 - 52, 16, Text.translatable("cobblemarket.gui.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.gui.search_placeholder").formatted(Formatting.GRAY))
        // 服务端搜索：文字变化只标记 dirty，tick 防抖后回到第一页重新拉取（与精灵市场一致）
        searchField?.setChangedListener { _ ->
            searchDirty = true
            lastSearchEdit = System.currentTimeMillis()
        }
        addSelectableChild(searchField)
        addDrawableChild(searchField)
        searchField?.text = savedSearch

        filterToggleButton = NineSliceButton(
            leftX + panelWidth - 52, 44, 50, 16,
            Text.translatable(if (filterExpanded) "cobblemarket.gui.filter_collapse" else "cobblemarket.gui.filter_expand"),
            { toggleFilters() }
        )
        addDrawableChild(filterToggleButton)

        // 玩家搜索框（专属，始终显示）
        sellerField = TextFieldWidget(textRenderer, leftX + 2, 64, panelWidth - 4, 16, Text.translatable("cobblemarket.op.seller_search"))
        sellerField?.setPlaceholder(Text.translatable("cobblemarket.op.seller_search").formatted(Formatting.GRAY))
        sellerField?.setChangedListener { _ ->
            currentPage = 1
            refreshData()
        }
        addSelectableChild(sellerField)
        addDrawableChild(sellerField)
        sellerField?.text = savedSeller

        if (filterExpanded) {
            hpField = createIvField(leftX + 4, 88, "HP")
            atkField = createIvField(leftX + 100, 88, "ATK")
            defField = createIvField(leftX + 196, 88, "DEF")

            spaField = createIvField(leftX + 4, 112, "SpA")
            spdField = createIvField(leftX + 100, 112, "SpD")
            speField = createIvField(leftX + 196, 112, "Spd")

            // 三态比较按钮（= / ≥ / ≤ 循环，默认 = 与旧行为一致；非默认金色高亮）
            ivOpButtons.clear()
            listOf(0 to (leftX + 4), 1 to (leftX + 100), 2 to (leftX + 196)).forEach { (i, bx) ->
                addIvOpButton(bx + 69, 88, i)
                addIvOpButton(bx + 69, 112, i + 3)
            }

            shinyButton = NineSliceButton(
                leftX + 4, 136, 30, 20,
                Text.literal(if (shinyOnly) "★" else "☆"),
                { toggleShiny() },
                // resize 重建时保持当前状态的颜色
                if (shinyOnly) GOLD_COLOR else 0xFFFFFF
            )
            addDrawableChild(shinyButton)

            sortButton = NineSliceButton(
                leftX + 36, 136, 90, 20,
                Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay())),
                { cycleSort() }
            )
            addDrawableChild(sortButton)

            htButton = NineSliceButton(
                leftX + 128, 136, 62, 20,
                htButtonText(),
                { toggleHtFilter() },
                if (htFilter != 0) GOLD_COLOR else 0xFFFFFF
            )
            addDrawableChild(htButton)

            mineButton = NineSliceButton(
                leftX + 192, 136, 50, 20,
                Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine"),
                { toggleMineOnly() }
            )
            addDrawableChild(mineButton)

            resetButton = NineSliceButton(
                leftX + 244, 136, 52, 20,
                Text.translatable("cobblemarket.gui.reset"),
                { resetFilters() }
            )
            addDrawableChild(resetButton)
        }

        // 分页（照精灵市场：底部分割线在最后一行下方 4px 对称，按钮在其与背景底边之间居中偏上 5px；
        // 列表行数由 getMaxVisibleRows 预留 72 保证按钮不压最后一行）
        val listBottom = getListStartY() + getMaxVisibleRows() * 24
        val btnY = (listBottom + 5 + (height - 32)) / 2 - 10 - 5
        prevButton = NineSliceButton(leftX, btnY, 80, 20, Text.translatable("cobblemarket.gui.prev"), { prevPage() })
        addDrawableChild(prevButton)
        nextButton = NineSliceButton(leftX + panelWidth - 80, btnY, 80, 20, Text.translatable("cobblemarket.gui.next"), { nextPage() })
        addDrawableChild(nextButton)
        updatePageButtons()

        rebuildCancelButtons()
        refreshData()
    }

    /** IV 三态比较按钮：= → ≥ → ≤ 循环；切换立即重发筛选请求（过滤条件变了） */
    private fun addIvOpButton(x: Int, y: Int, index: Int) {
        val btn = NineSliceButton(
            x, y, 20, 16,
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
        val field = TextFieldWidget(textRenderer, x, y, 68, 16, Text.literal(""))
        field.setPlaceholder(Text.literal(placeholder))
        field.setTextPredicate { it.length <= 2 && it.all { c -> c.isDigit() } }
        addSelectableChild(field)
        addDrawableChild(field)
        return field
    }

    // 84→88：卖家搜索框底边(80)与顶部分割线(80)贴死，列表起始下移 4px 留出间隙
    private fun getListStartY() = if (filterExpanded) 160 else 88
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
            val btn = NineSliceButton(leftX + panelWidth - 42, y + 4, 38, 16, Text.literal("✕").formatted(Formatting.RED), ButtonWidget.PressAction { cancelListing(entry) })
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
        mineButton.message = Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine")
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

    private fun toggleFilters() {
        filterExpanded = !filterExpanded
        clearChildren()
        init()
        rebuildCancelButtons()
    }

    private fun resetFilters() {
        searchField?.text = ""
        sellerField?.text = ""
        shinyOnly = false
        showMineOnly = false
        htFilter = 0
        sortMode = "NEWEST"
        currentPage = 1
        for (i in 0..5) { ivExact[i] = -1; ivOps[i] = 0 }
        syncIvOpButtons()
        hpField?.text = ""; atkField?.text = ""; defField?.text = ""
        spaField?.text = ""; spdField?.text = ""; speField?.text = ""
        shinyButton.message = Text.literal("☆")
        shinyButton.textColor = 0xFFFFFF
        htButton?.setMessage(htButtonText())
        htButton?.textColor = 0xFFFFFF
        sortButton.message = Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay()))
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

    private fun refreshData() {
        lastListRequestAt = System.currentTimeMillis()
        sendToServer(
            AdminRequestPokemonPayload(
                // 中文物种名在客户端转成资源路径 id（照精灵市场），服务端只存英文资源名
                speciesFilter = com.shusheng.cobblemarket.network.localizeSpeciesQuery(searchField?.text?.trim().orEmpty()),
                sellerFilter = sellerField?.text?.trim() ?: "",
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
                htFilter = htFilter
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
        confirmRenderable = null
        confirmDisplayName = e.species
        val id = Identifier.tryParse(e.speciesId)
        if (id != null) {
            val species = PokemonSpecies.getByIdentifier(id)
            if (species != null) {
                // 用挂单真实 aspects（性别形态/地区形态等），与其他界面一致
                val aspects = e.aspects.toMutableSet()
                if (e.shiny && "shiny" !in aspects) aspects.add("shiny")
                confirmRenderable = RenderablePokemon(species, aspects, ItemStack.EMPTY)
                confirmDisplayName = species.translatedName.string
            }
        }
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

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int, sliceH: Int = 16) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val centerX = width / 2
        val panelLeft = centerX - 160
        val panelTop = 2
        val panelBottom = height - 32
        val sliceH = 16

        val top = Identifier.of("cobblemarket", "textures/gui/market_panel_top.png")
        val mid = Identifier.of("cobblemarket", "textures/gui/market_panel_middle.png")
        val bot = Identifier.of("cobblemarket", "textures/gui/market_panel_bottom.png")

        drawPanelSlice(context, top, panelLeft, panelTop)
        var y = panelTop + sliceH
        while (y < panelBottom - sliceH) {
            drawPanelSlice(context, mid, panelLeft, y, minOf(sliceH, panelBottom - sliceH - y))
            y += sliceH
        }
        drawPanelSlice(context, bot, panelLeft, panelBottom - sliceH)

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

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
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

        context.fill(leftX, dividerY, leftX + panelWidth, dividerY + 1, 0xFF555555.toInt())
        // 底部分割线：最多显示行数的最后一行下方 4px（与顶部 startY-4 对称），分页按钮在其与背景底边之间居中
        val listBottom = getListStartY() + visibleRows * rowHeight
        context.fill(leftX, listBottom + 4, leftX + panelWidth, listBottom + 5, 0xFF555555.toInt())

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
            if (confirmEntry == null) renderPokemonIcon(context, origIndex, leftX + 2, y + 2, iconSize)

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
            val displayName = iconData[origIndex]?.displayName ?: entry.species
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

            drawSellerAvatar(context, entry.sellerUuid, entry.sellerName, leftX + 115, y + 4, 16)

            val levelText = Text.translatable("cobblemarket.gui.lv").string + entry.level
            context.drawText(textRenderer, levelText, leftX + 135, y + 7, 0x000000, false)

            // 价格右对齐到取消按钮左缘
            val priceText = "${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ${com.shusheng.cobblemarket.client.inlineCurrencyUnit()}"
            val btnLeft = leftX + panelWidth - 42
            context.drawTextWithShadow(textRenderer, priceText, btnLeft - textRenderer.getWidth(priceText) - 4, y + 7, 0xFFAA00)
        }

        if (confirmEntry == null && hoveredRow in displayList.indices) {
            renderTooltip(context, displayList[hoveredRow].value, displayList[hoveredRow].index, mouseX, mouseY)
        }

        // 每帧刷新按钮状态（render 覆写为最新 currentPage/totalPages，含 inFlight 置灰，见 updatePageButtons）
        updatePageButtons()

        if (confirmEntry != null) {
            renderConfirmDialog(context, mouseX, mouseY)
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

    private fun renderPokemonIcon(context: DrawContext, index: Int, x: Int, y: Int, size: Int, dark: Boolean = false) {
        val data = iconData[index] ?: return run {
            context.fill(x, y, x + size, y + size, 0x88888888.toInt())
        }
        val matrices = context.matrices
        matrices.push()
        try {
            context.enableScissor(x - 1, y + 1, x + size + 2, y + size + 2)
            matrices.translate(x + size / 2.0, y + 1.0, 0.0)
            matrices.scale(size / 25f * 2.5f, size / 25f * 2.5f, 1f)
            drawProfilePokemon(
                renderablePokemon = data.renderable, matrixStack = matrices,
                rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                state = data.state, partialTicks = 0f, scale = 4.5f,
                // 弹窗打开时压暗（模型走独立渲染层，遮罩盖不住；颜色系数模拟遮罩效果）
                r = if (dark) 0.35f else 1f,
                g = if (dark) 0.35f else 1f,
                b = if (dark) 0.35f else 1f
            )
        } catch (_: Exception) {
        } finally {
            context.disableScissor()
            matrices.pop()
        }
    }

    // tooltip 内容缓存：内容只取决于条目，悬停同一行时每帧重建全部文本行是悬停掉帧主因
    private var tooltipCacheKey: java.util.UUID? = null
    private var tooltipCacheLines: List<Pair<Text, Int>> = emptyList()
    private var tooltipCacheHeldLine = -1
    private var tooltipCacheMaxWidth = 0

    private fun renderTooltip(context: DrawContext, entry: ListingEntry, origIndex: Int, mouseX: Int, mouseY: Int) {
        // 文本行缓存：悬停同一行时内容不变，只在悬停目标变化时重建（见 tooltipCacheKey 注释）
        if (tooltipCacheKey != entry.id) {
            tooltipCacheKey = entry.id
            val hp = Text.translatable("cobblemon.stat.hp.name").string
            val atk = Text.translatable("cobblemon.stat.attack.name").string
            val def = Text.translatable("cobblemon.stat.defence.name").string
            val spa = Text.translatable("cobblemon.stat.special_attack.name").string
            val spd = Text.translatable("cobblemon.stat.special_defence.name").string
            val spe = Text.translatable("cobblemon.stat.speed.name").string

            val w = 0xFFFFFF
            val ivColors = intArrayOf(0x66FF66, 0xFF6666, 0xFFCC66, 0x6699FF, 0x66FF99, 0xFF99FF)

            val hasHeldItem = entry.heldItemId.isNotEmpty() &&
                Identifier.tryParse(entry.heldItemId)?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true

            val lines = mutableListOf<Pair<Text, Int>>()
            lines.add(EntryBadgeRenderer.nameWithShinyStar(iconData[origIndex]?.displayName ?: entry.species, entry.shiny)
                .copy().append(Text.literal("  ${Text.translatable("cobblemarket.gui.lv").string}${entry.level}")) to w)
            lines.add(Text.literal("${Text.translatable("cobblemarket.gui.tooltip_type").string}${Text.translatable(entry.primaryType).string}${if (entry.secondaryType.isNotEmpty()) " + ${Text.translatable(entry.secondaryType).string}" else ""}") to w)
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
            lines.add(Text.literal("  $hp:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsHp, entry.htHp)}").append(Text.literal("   EV:${entry.evsHp}").formatted(Formatting.RED)) to ivColors[0])
            lines.add(Text.literal("  $atk:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsAtk, entry.htAtk)}").append(Text.literal("   EV:${entry.evsAtk}").formatted(Formatting.RED)) to ivColors[1])
            lines.add(Text.literal("  $def:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsDef, entry.htDef)}").append(Text.literal("   EV:${entry.evsDef}").formatted(Formatting.RED)) to ivColors[2])
            lines.add(Text.literal("  $spa:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpAtk, entry.htSpAtk)}").append(Text.literal("   EV:${entry.evsSpAtk}").formatted(Formatting.RED)) to ivColors[3])
            lines.add(Text.literal("  $spd:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpDef, entry.htSpDef)}").append(Text.literal("   EV:${entry.evsSpDef}").formatted(Formatting.RED)) to ivColors[4])
            lines.add(Text.literal("  $spe:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpd, entry.htSpd)}").append(Text.literal("   EV:${entry.evsSpd}").formatted(Formatting.RED)) to ivColors[5])
            lines.add(Text.translatable("cobblemarket.gui.tooltip_seller").append(" ").append(Text.literal(entry.sellerName)) to w)
            lines.add(Text.translatable("cobblemarket.gui.tooltip_price").append(" ").append(
                Text.literal("${entry.price} ${com.shusheng.cobblemarket.client.displayCurrency(entry.currencyName)}").formatted(Formatting.GOLD)) to w)

            var maxWidth = 0
            lines.forEach { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it.first)) }
            if (heldItemLine >= 0) {
                maxWidth = maxOf(maxWidth, textRenderer.getWidth(lines[heldItemLine].first) + 14)
            }
            tooltipCacheLines = lines
            tooltipCacheHeldLine = heldItemLine
            tooltipCacheMaxWidth = maxWidth
        }
        val lines = tooltipCacheLines
        val heldItemLine = tooltipCacheHeldLine
        val maxWidth = tooltipCacheMaxWidth

        val padding = 4
        val tx = minOf(mouseX + 12, width - maxWidth - 12)
        val tooltipHeight = lines.size * 10 + padding
        val tyAbove = mouseY - tooltipHeight - 4
        val ty = if (tyAbove <= 0) minOf(mouseY + 12, height - tooltipHeight) else tyAbove

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, lines.size * 10 + 2 * padding, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, (line, color) ->
            if (i == heldItemLine) {
                context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
                iconData[origIndex]?.heldStack?.let { heldStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = heldStack,
                        x = tx + textRenderer.getWidth(line) + 2.0,
                        y = ty + i * 10 + 0.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                }
            } else if (i == 0) {
                // 第一行（名字★Lv）带公母图标
                EntryBadgeRenderer.drawNameLineLeft(context, line, entry.gender, tx, ty + i * 10, color)
            } else {
                context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
            }
        }
        context.matrices.pop()
    }

    private fun renderConfirmDialog(context: DrawContext, mouseX: Int, mouseY: Int) {
        val entry = confirmEntry ?: return
        val centerX = width / 2
        val dialogW = 220
        val dialogH = 240
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
            val im = context.matrices
            im.push()
            im.translate(iconX + iconSize / 2.0, iconY + 1.0, 0.0)
            im.scale(iconSize / 25f * 2.5f, iconSize / 25f * 2.5f, 1f)
            drawProfilePokemon(
                renderablePokemon = rp, matrixStack = im,
                rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                state = confirmState, partialTicks = 0f, scale = 4.5f
            )
            im.pop()
        }

        // 完整信息行（与市场列表悬停 tooltip 结构一致）：名字★Lv / 类型 / 性格特性 / 携带物 / IV / 卖家 / 价格
        EntryBadgeRenderer.drawInfoLines(
            context, entry, EntryBadgeRenderer.nameWithShinyStar(confirmDisplayName, entry.shiny),
            centerX, dialogY + 60
        )

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
        val dialogH = 240
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
        f === searchField || f === sellerField || f === hpField || f === atkField || f === defField || f === spaField || f === spdField || f === speField
    } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true ||
        sellerField?.isMouseOver(mouseX, mouseY) == true ||
        hpField?.isMouseOver(mouseX, mouseY) == true ||
        atkField?.isMouseOver(mouseX, mouseY) == true ||
        defField?.isMouseOver(mouseX, mouseY) == true ||
        spaField?.isMouseOver(mouseX, mouseY) == true ||
        spdField?.isMouseOver(mouseX, mouseY) == true ||
        speField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
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
    }
}
