package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.network.*
import com.shusheng.cobblemarket.screen.SellSelectScreen
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
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
import com.mojang.authlib.GameProfile
import java.util.UUID

class MarketScreen : Screen(Text.translatable("cobblemarket.gui.title")) {

    private var listings = listOf<ListingEntry>()
    private var currentPage = 1
    private var totalPages = 1

    private var searchField: TextFieldWidget? = null
    // 搜索防抖状态：tick 里检查，停止输入 250ms 后才发请求
    private var searchDirty = false
    private var lastSearchEdit = 0L
    private var shinyOnly = false
    private var showMineOnly = false
    private var filterExpanded = false
    private var sortMode = "NEWEST"
    private var genderFilter = ""
    private var typeFilter = ""
    private var typeFilterIndex = 0
    private val minIvs = IntArray(6) { -1 }

    private lateinit var genderButton: ButtonWidget
    private lateinit var typeButton: ButtonWidget
    private lateinit var shinyButton: NineSliceButton
    private lateinit var sortButton: ButtonWidget
    private lateinit var resetButton: ButtonWidget
    private lateinit var mineButton: ButtonWidget
    private lateinit var filterToggleButton: ButtonWidget
    private lateinit var prevButton: ButtonWidget
    private lateinit var nextButton: ButtonWidget
    private val buyButtons = mutableListOf<ButtonWidget>()

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
    private var cancelEntry: ListingEntry? = null
    private var confirmRenderable: RenderablePokemon? = null
    private var confirmDisplayName = ""
    private val confirmState = FloatingState()

    private val typeOptions = listOf(
        "", "normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison", "ground",
        "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy"
    )

    // Cached renderables per listing index
    private data class IconData(val displayName: String, val renderable: RenderablePokemon, val state: FloatingState)
    private val iconData = mutableMapOf<Int, IconData>()

    private fun cacheIcons() {
        iconData.clear()
        listings.forEachIndexed { index, entry ->
            val id = Identifier.tryParse(entry.speciesId) ?: return@forEachIndexed
            val species = PokemonSpecies.getByIdentifier(id) ?: return@forEachIndexed
            // 用挂单携带的真实 aspects（性别形态/地区形态等），不再只加 shiny——
            // 否则雌性爱管侍这类性别差异物种会渲染成默认雄性模型
            val aspects = entry.aspects.toMutableSet()
            if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
            val displayName = com.shusheng.cobblemarket.util.SpeciesText.displayName(species)
            iconData[index] = IconData(displayName, RenderablePokemon(species, aspects, ItemStack.EMPTY), FloatingState())
        }
    }

    private val defaultSkinTexture = Identifier.of("minecraft", "textures/entity/player/wide/steve.png")

    private fun getSellerSkin(uuid: UUID, name: String): Identifier {
        client?.networkHandler?.getPlayerListEntry(uuid)?.skinTextures?.texture()?.let { return it }
        return client?.skinProvider?.getSkinTextures(GameProfile(uuid, name))?.texture() ?: defaultSkinTexture
    }

    private fun drawSellerAvatar(context: DrawContext, uuid: UUID, name: String, x: Int, y: Int, size: Int) {
        val texture = getSellerSkin(uuid, name)
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(size / 8f, size / 8f, 1f)
        context.drawTexture(texture, 0, 0, 8f, 8f, 8, 8, 64, 64)
        context.matrices.pop()
    }

    override fun init() {
        super.init()
        ClientPlayNetworking.send(RequestBalancePayload())

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        // Row 1 (y=32): Search text field (full width)
        searchField = TextFieldWidget(textRenderer, leftX + 2, 44, panelWidth - 4 - 52 - 20, 16, Text.translatable("cobblemarket.gui.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.gui.search_placeholder").formatted(Formatting.GRAY))
        // 防抖：停止输入 250ms 后才发请求。连续编辑时只发最终态，
        // 避免中间态请求与最终态请求挤在服务端 250ms 节流窗口内，最终态被静默丢弃导致列表不刷新。
        searchField?.setChangedListener {
            searchDirty = true
            lastSearchEdit = System.currentTimeMillis()
        }
        addSelectableChild(searchField)
        addDrawableChild(searchField)

        // Collapse/expand filters toggle (right of search field)
        filterToggleButton = NineSliceButton(
            leftX + panelWidth - 52, 44, 50, 16,
            Text.literal(if (filterExpanded) "▲" else "▼"),
            { toggleFilters() }
        )
        addDrawableChild(filterToggleButton)

        // Sell button (right of search, left of filter toggle)
        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 72, 44, 18, 16,
            Text.literal("+"), { openSellScreen() }
        ))

        // Collect balance button (top-left)
        addDrawableChild(NineSliceButton(
            leftX, 13, 50, 16,
            Text.translatable("cobblemarket.gui.collect"), { collectBalance() }
        ))

        // Expired returns button
        addDrawableChild(NineSliceButton(
            leftX + 52, 13, 50, 16,
            Text.translatable("cobblemarket.gui.returns"), { client?.setScreen(PokemonReturnScreen()) }
        ))

        // Back button (top-right, symmetric with collect)
        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.translatable("cobblemarket.gui.back"), { client?.setScreen(MarketEntryScreen()) }
        ))

        // Filter controls only when expanded
        if (filterExpanded) {

        // Row 2 (y=54): Gender button + Type button
        genderButton = NineSliceButton(
            leftX + 4, 66, 144, 20,
            genderButtonText(),
            { cycleGender() }
        )
        addDrawableChild(genderButton)

        typeButton = NineSliceButton(
            leftX + 152, 66, 144, 20,
            typeButtonText(),
            { cycleType() }
        )
        addDrawableChild(typeButton)

        // Row 3 (y=78): HP, Atk, Def IV inputs
        hpField = createIvField(leftX + 4, 90, "HP")
        atkField = createIvField(leftX + 100, 90, "ATK")
        defField = createIvField(leftX + 196, 90, "DEF")

        // Row 4 (y=102): SpA, SpD, Spd IV inputs
        spaField = createIvField(leftX + 4, 114, "SpA")
        spdField = createIvField(leftX + 100, 114, "SpD")
        speField = createIvField(leftX + 196, 114, "Spd")

        // Row 5 (y=126): Shiny + Sort + Mine + Reset
        shinyButton = NineSliceButton(
            leftX + 4, 138, 90, 20,
            Text.translatable(if (shinyOnly) "cobblemarket.gui.shiny_on" else "cobblemarket.gui.shiny_off"),
            { toggleShiny() },
            // resize 重建时保持当前状态的颜色
            if (shinyOnly) GOLD_COLOR else 0xFFFFFF
        )
        addDrawableChild(shinyButton)

        sortButton = NineSliceButton(
            leftX + 98, 138, 90, 20,
            Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay())),
            { cycleSort() }
        )
        addDrawableChild(sortButton)

        mineButton = NineSliceButton(
            leftX + 192, 138, 50, 20,
            Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine"),
            { toggleMineOnly() }
        )
        addDrawableChild(mineButton)

        resetButton = NineSliceButton(
            leftX + 246, 138, 50, 20,
            Text.translatable("cobblemarket.gui.reset"),
            { resetFilters() }
        )
        addDrawableChild(resetButton)

        } // end if (filterExpanded)

        // Page buttons at bottom（动态贴在列表最后一行下方）
        val listBottom = getListStartY() + getMaxVisibleRows() * 24
        val btnY = listBottom

        prevButton = NineSliceButton(
            leftX, btnY, 80, 20,
            Text.translatable("cobblemarket.gui.prev"),
            { prevPage() }
        )
        addDrawableChild(prevButton)

        nextButton = NineSliceButton(
            leftX + panelWidth - 80, btnY, 80, 20,
            Text.translatable("cobblemarket.gui.next"),
            { nextPage() }
        )
        addDrawableChild(nextButton)

        rebuildBuyButtons()
        refreshData()
        applyFilterVisibility() // Apply current collapsed state
    }

    private fun createIvField(x: Int, y: Int, placeholder: String): TextFieldWidget {
        val field = TextFieldWidget(textRenderer, x, y, 92, 16, Text.literal(""))
        field.setPlaceholder(Text.literal(placeholder))
        field.setTextPredicate { it.length <= 2 && it.all { c -> c.isDigit() } }
        addSelectableChild(field)
        addDrawableChild(field)
        return field
    }

    private fun getListStartY() = (if (filterExpanded) 160 else 64) + 4
    private fun getMaxVisibleRows() = maxOf(0, (height - getListStartY() - 68) / 24)

    private fun rebuildBuyButtons() {
        buyButtons.forEach { remove(it) }
        buyButtons.clear()

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2
        val startY = getListStartY()
        val rowHeight = 24
        val playerUuid = client?.player?.uuid

        displayedListings().take(getMaxVisibleRows()).forEachIndexed { di, (origIndex, entry) ->
            val y = startY + di * rowHeight
            val isMine = playerUuid != null && entry.sellerUuid == playerUuid
            val label = if (isMine) Text.literal("✕") else Text.translatable("cobblemarket.gui.buy")
            val action = if (isMine)
                ButtonWidget.PressAction { openCancelDialog(entry) }
            else
                ButtonWidget.PressAction { openConfirmDialog(entry) }
            val btn = NineSliceButton(leftX + panelWidth - 42, y + 4, 38, 16, label, action)
            buyButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // 确认弹窗的 3D 渲染：用挂单真实 aspects（含性别形态），与列表图标一致
    private fun buildConfirmRenderable(entry: ListingEntry) {
        confirmRenderable = null
        confirmDisplayName = ""
        val id = Identifier.tryParse(entry.speciesId) ?: return
        val species = PokemonSpecies.getByIdentifier(id) ?: return
        val aspects = entry.aspects.toMutableSet()
        if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
        confirmRenderable = RenderablePokemon(species, aspects, ItemStack.EMPTY)
        confirmDisplayName = com.shusheng.cobblemarket.util.SpeciesText.displayName(species)
    }

    private fun openConfirmDialog(entry: ListingEntry) {
        confirmEntry = entry
        buildConfirmRenderable(entry)
    }

    private fun openCancelDialog(entry: ListingEntry) {
        cancelEntry = entry
        buildConfirmRenderable(entry)
    }

    private fun closeConfirmDialog() {
        confirmEntry = null
        cancelEntry = null
        confirmRenderable = null
        confirmDisplayName = ""
    }

    private fun confirmPurchase() {
        val entry = confirmEntry ?: return
        ClientPlayNetworking.send(BuyFromMarketPayload(entry.id))
        closeConfirmDialog()
    }

    private fun confirmCancel() {
        val entry = cancelEntry ?: return
        ClientPlayNetworking.send(CancelFromMarketPayload(entry.id))
        closeConfirmDialog()
    }

    private fun displayedListings(): List<IndexedValue<ListingEntry>> = listings.withIndex().toList()

    // ── Gender filter ──

    private fun genderButtonText(): Text {
        val label = when (genderFilter) {
            "MALE" -> Text.translatable("cobblemarket.gui.filter_male")
            "FEMALE" -> Text.translatable("cobblemarket.gui.filter_female")
            else -> Text.translatable("cobblemarket.gui.filter_any")
        }
        return Text.translatable("cobblemarket.gui.gender").append(": ").append(label)
    }

    private fun cycleGender() {
        genderFilter = when (genderFilter) {
            "" -> "MALE"
            "MALE" -> "FEMALE"
            "FEMALE" -> ""
            else -> ""
        }
        currentPage = 1
        genderButton.message = genderButtonText()
        refreshData()
    }

    // ── Type filter ──

    private fun typeButtonText(): Text {
        val key = typeOptions.getOrElse(typeFilterIndex) { "" }
        val label = if (key.isEmpty()) Text.translatable("cobblemarket.gui.filter_any")
            else Text.translatable("cobblemon.type.$key")
        return Text.translatable("cobblemarket.gui.type").append(": ").append(label)
    }

    private fun cycleType() {
        typeFilterIndex = (typeFilterIndex + 1) % typeOptions.size
        typeFilter = typeOptions[typeFilterIndex]
        currentPage = 1
        typeButton.message = typeButtonText()
        refreshData()
    }

    // ── Shiny toggle ──

    private fun toggleShiny() {
        shinyOnly = !shinyOnly
        currentPage = 1
        shinyButton.message = Text.translatable(if (shinyOnly) "cobblemarket.gui.shiny_on" else "cobblemarket.gui.shiny_off")
        // 开 = 金色 ★，关 = 白色 ☆（与价格限制/黑名单的闪光按钮一致）
        shinyButton.textColor = if (shinyOnly) GOLD_COLOR else 0xFFFFFF
        mineButton.message = Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine")
        refreshData()
    }

    // ── Sort cycling ──

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

    private fun sortDisplay(): String = when (sortMode) {
        "PRICE_ASC" -> "cobblemarket.sort.price_asc"
        "PRICE_DESC" -> "cobblemarket.sort.price_desc"
        "LEVEL_ASC" -> "cobblemarket.sort.level_asc"
        "LEVEL_DESC" -> "cobblemarket.sort.level_desc"
        "NEWEST" -> "cobblemarket.sort.newest"
        else -> "cobblemarket.sort.price_asc"
    }

    // ── Reset all filters ──

    private fun applyFilterVisibility() {
        filterToggleButton.message = Text.translatable(if (filterExpanded) "cobblemarket.gui.filter_collapse" else "cobblemarket.gui.filter_expand")
    }

    private fun toggleFilters() {
        filterExpanded = !filterExpanded
        applyFilterVisibility()
        // Rebuild the screen to correctly show/hide filter controls
        clearChildren()
        init()
        if (client != null) rebuildBuyButtons()
    }

    private fun toggleMineOnly() {
        showMineOnly = !showMineOnly
        mineButton.message = Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine")
        currentPage = 1
        refreshData()
    }

    private fun resetFilters() {
        searchField?.text = ""
        shinyOnly = false
        showMineOnly = false
        genderFilter = ""
        typeFilter = ""
        typeFilterIndex = 0
        sortMode = "NEWEST"
        currentPage = 1
        for (i in 0..5) minIvs[i] = -1
        hpField?.text = ""
        atkField?.text = ""
        defField?.text = ""
        spaField?.text = ""
        spdField?.text = ""
        speField?.text = ""
        shinyButton.message = Text.translatable("cobblemarket.gui.shiny_off")
        shinyButton.textColor = 0xFFFFFF
        genderButton.message = genderButtonText()
        typeButton.message = typeButtonText()
        sortButton.message = Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay()))
        refreshData()
    }

    // ── Pagination ──

    private fun prevPage() {
        if (currentPage > 1) { currentPage--; refreshData() }
    }

    private fun nextPage() {
        if (currentPage < totalPages) { currentPage++; refreshData() }
    }

    private fun requestBuy(listingId: java.util.UUID) {
        ClientPlayNetworking.send(BuyFromMarketPayload(listingId))
    }

    private fun openSellScreen() {
        client?.setScreen(SellSelectScreen())
    }

    private fun requestCancel(listingId: java.util.UUID) {
        ClientPlayNetworking.send(CancelFromMarketPayload(listingId))
    }

    private fun collectBalance() {
        ClientPlayNetworking.send(CollectBalancePayload())
    }

    // ── Render ──

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

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (confirmEntry != null || cancelEntry != null) {
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

    private fun syncIvFromFields(): Boolean {
        val fields = arrayOf(hpField, atkField, defField, spaField, spdField, speField)
        var changed = false
        for (i in 0..5) {
            val raw = fields[i]?.text ?: ""
            val digits = raw.filter { it.isDigit() }.take(2)
            // -1 sentinel: field is empty/unset, skip filtering
            // 0: user explicitly typed "0", filter for IV == 0
            val iv = if (digits.isEmpty()) -1 else digits.toIntOrNull()?.coerceIn(0, 31) ?: -1
            if (digits != raw) fields[i]?.text = if (iv <= 0) "" else iv.toString()
            if (minIvs[i] != iv) changed = true
            minIvs[i] = iv
        }
        return changed
    }

    // 中文物种名 → 资源路径名：服务端只存英文资源名（listing.species），客户端用本地语言翻译名映射。
    // 纯 ASCII 输入原样发送（服务端按资源名/英文名 ignoreCase 匹配）；映射不到就发原文。
    private fun localizeSpeciesQuery(raw: String): String {
        val q = raw.trim()
        if (q.isEmpty() || q.all { it.code < 128 }) return q
        PokemonSpecies.implemented
            .firstOrNull { it.translatedName.string.contains(q) }
            ?.let { return it.resourceIdentifier.path }
        return q
    }

    // 防抖计时器：搜索框 changedListener 与 IV 输入变化都置 dirty，250ms 静默后统一发请求
    override fun tick() {
        if (searchDirty && System.currentTimeMillis() - lastSearchEdit >= 250) {
            searchDirty = false
            currentPage = 1
            refreshData()
        }
    }

    private fun refreshData(ivs: IntArray = minIvs) {
        ClientPlayNetworking.send(
            RequestMarketPayload(
                speciesFilter = localizeSpeciesQuery(searchField?.text?.trim().orEmpty()),
                shinyOnly = shinyOnly,
                minLevel = 0,
                maxLevel = -1, // 哨兵：无上限（与 minLevel 的 0 哨兵对称）
                sortMode = sortMode,
                page = currentPage,
                genderFilter = genderFilter,
                typeFilter = typeFilter,
                minIvsHp = ivs[0],
                minIvsAtk = ivs[1],
                minIvsDef = ivs[2],
                minIvsSpAtk = ivs[3],
                minIvsSpDef = ivs[4],
                minIvsSpd = ivs[5],
                pageSize = getMaxVisibleRows(),
                mineOnly = showMineOnly
            )
        )
    }

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f, 1f)
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
            drawPanelSlice(context, mid, panelLeft, y)
            y += sliceH
        }
        drawPanelSlice(context, bot, panelLeft, panelBottom - sliceH)

        // 列表行背景（先于按钮渲染，避免覆盖按钮）
        val leftX = centerX - panelWidth / 2
        val startY = getListStartY()
        val rowHeight = 24
        val visibleRows = getMaxVisibleRows()
        val listAreaBottom = startY + visibleRows * rowHeight
        val displayList = displayedListings()

        hoveredRow = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY in startY..listAreaBottom) {
            val relY = mouseY - startY
            val row = relY / rowHeight
            if (row in displayList.indices) hoveredRow = row
        }

        displayList.take(visibleRows).forEachIndexed { di, _ ->
            val rowY = startY + di * rowHeight
            val rowState = if (di == hoveredRow) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, rowState, ROW_BACKGROUND_TEX_H)
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        // IV 变化与搜索框共用 250ms 防抖（searchDirty/lastSearchEdit，见 init 注释）：
        // 立即发包时，快速输入 "31" 会先发 atk=3 再发 atk=31，后一个请求落在
        // 服务端 250ms 节流窗口内被静默丢弃，列表停留在旧结果上。
        // minIvs 每帧同步，防抖期间其他按钮触发的 refreshData 仍带最新 IV 值。
        if (syncIvFromFields()) {
            searchDirty = true
            lastSearchEdit = System.currentTimeMillis()
        }

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        // Row 0 (y=8): Title
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.gui.title").formatted(Formatting.GOLD),
            centerX, 14, 0xFFFFFF
        )

        // 余额（货币蓝）+ 待收款（绿），按钮行下方，左对齐；余额来自全局缓存，交易操作后自动刷新
        val balPrefix = com.shusheng.cobblemarket.client.BalanceCache.balance.takeIf { it.isNotEmpty() }
            ?.let { Text.translatable("cobblemarket.gui.balance", it).string + "  " } ?: ""
        context.drawTextWithShadow(textRenderer, balPrefix, leftX, 31, 0x55FFFF)
        context.drawTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.gui.pending_balance", com.shusheng.cobblemarket.client.formatPriceLong(pendingBalance) + " ◆").string,
            leftX + textRenderer.getWidth(balPrefix), 31, 0x55FF55)

        // Page indicator（右对齐：左侧放余额行，长数字互不干扰）
        val pageText = Text.translatable("cobblemarket.gui.page", currentPage, totalPages).formatted(Formatting.GRAY)
        context.drawTextWithShadow(
            textRenderer,
            pageText,
            leftX + panelWidth - 4 - textRenderer.getWidth(pageText), 32, 0xFFFFFF
        )

        val dividerY = getListStartY() - 4
        val startY = getListStartY()
        val rowHeight = 24
        val visibleRows = getMaxVisibleRows()

        context.fill(leftX, dividerY, leftX + panelWidth, dividerY + 1, 0xFF555555.toInt())

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

            // Pokemon icon slot background
            val slotX = leftX + 2
            val slotY = y + 2
            val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
            context.matrices.push()
            context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
            context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
            context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
            context.matrices.pop()

            // 3D Pokemon icon
            val iconX = leftX + 2
            val iconY = y + 2
            renderPokemonIcon(context, origIndex, iconX, iconY, iconSize)

            // Ball icon（球种统一在精灵名称左侧）
            val ballId = Identifier.tryParse(entry.ballItem)
            if (ballId != null) {
                val ballItem = Registries.ITEM.get(ballId)
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = ItemStack(ballItem),
                    x = leftX + 26.0,
                    y = y + 6.0,
                    scale = 0.6,
                    matrixStack = context.matrices
                )
            }

            // Species name (translated) + 金色闪光星标（★，拆段绘制）
            val displayName = iconData[origIndex]?.displayName ?: entry.species
            context.drawText(textRenderer, displayName, leftX + 40, y + 7, typeColor(entry.primaryType), false)
            var nameWidth = textRenderer.getWidth(displayName)
            if (entry.shiny) {
                context.drawText(textRenderer, "★", leftX + 40 + nameWidth + 2, y + 7, GOLD_COLOR, false)
                nameWidth += 2 + textRenderer.getWidth("★")
            }

            // Gender icon (Cobblemon blue/red arrows)
            var iconOffset = 0
            if (entry.gender == "MALE" || entry.gender == "FEMALE") {
                val genderIcon = if (entry.gender == "MALE")
                    Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
                else
                    Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
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

            // Held item icon (after gender)
            if (entry.heldItemId.isNotEmpty()) {
                Identifier.tryParse(entry.heldItemId)?.let { heldId ->
                    val heldItem = Registries.ITEM.get(heldId)
                    if (heldItem != Registries.ITEM.get(Identifier.of("minecraft", "air"))) {
                        com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                            itemStack = ItemStack(heldItem),
                            x = leftX + 40 + nameWidth + 2 + iconOffset + 0.0,
                            y = y + 6.0,
                            scale = 0.6,
                            matrixStack = context.matrices
                        )
                        iconOffset += 12
                    }
                }
            }

            // Seller avatar
            drawSellerAvatar(context, entry.sellerUuid, entry.sellerName, leftX + 115, y + 4, 16)

            // Level
            val levelText = Text.translatable("cobblemarket.gui.lv").string + entry.level
            context.drawText(textRenderer, levelText, leftX + 135, y + 7, 0x000000, false)

            // Price（右对齐到按钮左缘：价格再长也只向左延伸，不会遮按钮）
            val priceText = "${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ◆"
            val btnLeft = leftX + panelWidth - 42
            context.drawTextWithShadow(textRenderer, priceText, btnLeft - textRenderer.getWidth(priceText) - 4, y + 7, 0x55FFFF)
        }

        // Tooltip on hover
        if (confirmEntry == null && hoveredRow in displayList.indices) {
            renderTooltip(context, displayList[hoveredRow].value, mouseX, mouseY)
        }

        prevButton.active = currentPage > 1
        nextButton.active = currentPage < totalPages

        if (confirmEntry != null || cancelEntry != null) {
            renderConfirmDialog(context, mouseX, mouseY)
        }
    }

    private fun renderConfirmDialog(context: DrawContext, mouseX: Int, mouseY: Int) {
        val entry = confirmEntry ?: cancelEntry ?: return
        val centerX = width / 2
        val dialogW = 220
        val dialogH = 250
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        // 遮罩（提高 z，盖住底层列表）
        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 100.0)
        context.fill(0, 0, width, height, 0xC0000000.toInt())
        context.matrices.pop()

        // 弹窗背景（z 高于遮罩）
        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 200.0)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)

        // 标题
        val titleKey = if (cancelEntry != null) "cobblemarket.item.cancel_title" else "cobblemarket.buy_confirm.title"
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable(titleKey).formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        // 精灵 3D 图标（槽背景 + 精灵，对齐列表图标渲染）
        val iconSize = 28
        val iconX = centerX - iconSize / 2
        val iconY = dialogY + 28
        val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
        context.matrices.push()
        context.matrices.translate(iconX.toDouble(), iconY.toDouble(), 0.0)
        context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
        context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
        context.matrices.pop()
        confirmRenderable?.let { rp ->
            val matrices = context.matrices
            matrices.push()
            matrices.translate((iconX + iconSize / 2).toDouble(), (iconY + 1).toDouble(), 0.0)
            matrices.scale(iconSize / 25f * 2.5f, iconSize / 25f * 2.5f, 1f)
            drawProfilePokemon(
                renderablePokemon = rp, matrixStack = matrices,
                rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                state = confirmState, partialTicks = 0f, scale = 4.5f
            )
            matrices.pop()
        }

        // 完整信息行（与市场列表悬停 tooltip 结构一致）：名字★Lv / 类型 / 性格特性 / 携带物 / IV / 卖家 / 价格
        val name = EntryBadgeRenderer.nameWithShinyStar(
            if (confirmDisplayName.isNotEmpty()) confirmDisplayName else entry.species, entry.shiny)
        EntryBadgeRenderer.drawInfoLines(context, entry, name, centerX, dialogY + 62)

        // 按钮
        val btnW = 80
        val btnH = 20
        val btnY = dialogY + dialogH - 30
        val confirmX = centerX - btnW - 5
        val cancelX = centerX + 5
        val confirmHover = mouseX in confirmX..(confirmX + btnW) && mouseY in btnY..(btnY + btnH)
        val cancelHover = mouseX in cancelX..(cancelX + btnW) && mouseY in btnY..(btnY + btnH)

        drawNineSlice(context, BUTTON_TEXTURE, confirmX, btnY, btnW, btnH, if (confirmHover) 1 else 0, BUTTON_TEX_H)
        drawNineSlice(context, BUTTON_TEXTURE, cancelX, btnY, btnW, btnH, if (cancelHover) 1 else 0, BUTTON_TEX_H)
        val confirmKey = if (cancelEntry != null) "cobblemarket.item.cancel_confirm" else "cobblemarket.buy_confirm.confirm"
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable(confirmKey), confirmX + btnW / 2, btnY + (btnH - 8) / 2, 0xFFFFFF)
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("cobblemarket.buy_confirm.cancel"), cancelX + btnW / 2, btnY + (btnH - 8) / 2, 0xFFFFFF)
        context.matrices.pop()
    }

    private fun handleConfirmDialogClick(mx: Int, my: Int) {
        val centerX = width / 2
        val dialogH = 250
        val dialogY = height / 2 - dialogH / 2
        val btnW = 80
        val btnH = 20
        val btnY = dialogY + dialogH - 30
        val confirmX = centerX - btnW - 5
        val cancelX = centerX + 5
        if (mx in confirmX..(confirmX + btnW) && my in btnY..(btnY + btnH)) {
            playClickSound()
            if (cancelEntry != null) confirmCancel() else confirmPurchase()
        } else if (mx in cancelX..(cancelX + btnW) && my in btnY..(btnY + btnH)) {
            playClickSound()
            closeConfirmDialog()
        }
    }

    private fun playClickSound() {
        client?.soundManager?.play(PositionedSoundInstance.master(
            SoundEvent.of(Identifier.of("cobblemarket", "button_click")),
            1.0f
        ))
    }

    // ── 3D Pokemon icon rendering ──

    private fun renderPokemonIcon(context: DrawContext, index: Int, x: Int, y: Int, size: Int) {
        val data = iconData[index] ?: run {
            // Fallback: type-colored placeholder
            val entry = listings.getOrNull(index) ?: return
            val tc = typeColor(entry.primaryType)
            context.fill(x, y, x + size, y + size, 0x88000000.toInt())
            context.fill(x + 1, y + 1, x + size - 1, y + size - 1, tc or 0xCC000000.toInt())
            val initial = (iconData[index]?.displayName ?: entry.species).take(2)
            val tw = textRenderer.getWidth(initial)
            context.drawTextWithShadow(textRenderer, initial, x + (size - tw) / 2, y + (size - 8) / 2, 0xFFFFFF)
            if (entry.shiny) context.drawTextWithShadow(textRenderer, "★", x + size - 6, y - 5, GOLD_COLOR)
            return
        }
        val matrices = context.matrices
        matrices.push()
        try {
            context.enableScissor(x - 1, y + 1, x + size + 2, y + size + 2)
            matrices.translate(x + size / 2.0, y + 1.0, 0.0)
            matrices.scale(size / 25f * 2.5f, size / 25f * 2.5f, 1f)

            drawProfilePokemon(
                renderablePokemon = data.renderable,
                matrixStack = matrices,
                rotation = Quaternionf().rotateXYZ(
                    Math.toRadians(13.0).toFloat(),
                    Math.toRadians(35.0).toFloat(),
                    0f
                ),
                state = data.state,
                partialTicks = 0f,
                scale = 4.5f
            )

            matrices.pop()
            context.disableScissor()
        } catch (e: Exception) {
            // 清理残留的 scissor/matrices，避免泄漏
            context.disableScissor()
            matrices.pop()
            // Fallback on error
            val entry = listings.getOrNull(index) ?: return
            val tc = typeColor(entry.primaryType)
            context.fill(x, y, x + size, y + size, 0x88000000.toInt())
            context.fill(x + 1, y + 1, x + size - 1, y + size - 1, tc or 0xCC000000.toInt())
            val initial = (iconData[index]?.displayName ?: entry.species).take(2)
            val tw = textRenderer.getWidth(initial)
            context.drawTextWithShadow(textRenderer, initial, x + (size - tw) / 2, y + (size - 8) / 2, 0xFFFFFF)
        }
    }

    // ── Tooltip ──

    private fun renderTooltip(context: DrawContext, entry: ListingEntry, mouseX: Int, mouseY: Int) {
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
        lines.add(EntryBadgeRenderer.nameWithShinyStar(iconData[listings.indexOf(entry)]?.displayName ?: entry.species, entry.shiny)
            .copy().append(Text.literal("  ${Text.translatable("cobblemarket.gui.lv").string}${entry.level}")) to w)
        lines.add(Text.literal("${Text.translatable("cobblemarket.gui.tooltip_type").string}${Text.translatable(entry.primaryType).string}${if (entry.secondaryType.isNotEmpty()) " + ${Text.translatable(entry.secondaryType).string}" else ""}") to w)
        lines.add(Text.literal("${Text.translatable("cobblemarket.gui.tooltip_nature").string}${Text.translatable(entry.nature).string}  ${Text.translatable("cobblemarket.gui.tooltip_ability").string}${Text.translatable(entry.ability).string}") to w)
        var heldItemLine = -1
        if (hasHeldItem) {
            heldItemLine = lines.size
            lines.add(Text.translatable("cobblemarket.gui.tooltip_held") to w)
        }
        lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs") to w)
        lines.add(Text.literal("  $hp:${entry.ivsHp}") to ivColors[0])
        lines.add(Text.literal("  $atk:${entry.ivsAtk}") to ivColors[1])
        lines.add(Text.literal("  $def:${entry.ivsDef}") to ivColors[2])
        lines.add(Text.literal("  $spa:${entry.ivsSpAtk}") to ivColors[3])
        lines.add(Text.literal("  $spd:${entry.ivsSpDef}") to ivColors[4])
        lines.add(Text.literal("  $spe:${entry.ivsSpd}") to ivColors[5])
        lines.add(Text.literal("${Text.translatable("cobblemarket.gui.tooltip_seller").formatted(Formatting.GRAY).string} ${entry.sellerName}") to w)
        lines.add(Text.literal("${Text.translatable("cobblemarket.gui.tooltip_price").formatted(Formatting.GRAY).string} ${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ${entry.currencyName}") to w)

        var maxWidth = 0
        lines.forEach { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it.first)) }
        if (heldItemLine >= 0) {
            maxWidth = maxOf(maxWidth, textRenderer.getWidth(lines[heldItemLine].first) + 14)
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
            if (i == heldItemLine) {
                context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
                Identifier.tryParse(entry.heldItemId)?.let { heldId ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = ItemStack(Registries.ITEM.get(heldId)),
                        x = tx + textRenderer.getWidth(line) + 2.0,
                        y = ty + i * 10 + 0.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                }
            } else {
                context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
            }
        }
        context.matrices.pop()
    }

    // ── Type color mapping ──

    private fun typeColor(typeKey: String): Int = when (typeKey.substringAfterLast(".").lowercase()) {
        "normal" -> 0xAAAA99; "fire" -> 0xFF4422; "water" -> 0x3399FF
        "electric" -> 0xFFCC33; "grass" -> 0x77CC55; "ice" -> 0x66CCFF
        "fighting" -> 0xBB5544; "poison" -> 0xAA5599; "ground" -> 0xDDBB55
        "flying" -> 0x8899FF; "psychic" -> 0xFF5599; "bug" -> 0xAABB22
        "rock" -> 0xBBAA66; "ghost" -> 0x6666BB; "dragon" -> 0x7766EE
        "dark" -> 0x775544; "steel" -> 0xAAAABB; "fairy" -> 0xFFAAFF
        else -> 0xFFFFFF
    }

    override fun shouldPause(): Boolean = false

    override fun resize(client: net.minecraft.client.MinecraftClient, width: Int, height: Int) {
        val oldSearch = searchField?.text ?: ""
        val oldIv = if (filterExpanded) arrayOf(
            hpField?.text ?: "", atkField?.text ?: "", defField?.text ?: "",
            spaField?.text ?: "", spdField?.text ?: "", speField?.text ?: ""
        ) else emptyArray()
        super.resize(client, width, height)
        searchField?.text = oldSearch
        if (filterExpanded) {
            val fields = arrayOf(hpField, atkField, defField, spaField, spdField, speField)
            oldIv.forEachIndexed { i, t -> fields[i]?.text = t }
        }
    }

    private var pendingBalance = 0L

    fun onMarketData(payload: MarketDataPayload) {
        listings = payload.entries
        totalPages = payload.totalPages
        currentPage = payload.currentPage
        pendingBalance = payload.pendingBalance
        cacheIcons()
        rebuildBuyButtons()
    }

    fun onMarketResult(payload: MarketResultPayload) {
        if (client?.player != null) {
            val color = if (payload.success) Formatting.GREEN else Formatting.RED
            client!!.player!!.sendMessage(payload.message.copy().formatted(color), false)
            // Note: payload.message is already translated server-side (single-player compatible)
        }
        if (payload.success) refreshData()
    }
}
