package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.network.*
import com.shusheng.cobblemarket.screen.SellSelectScreen
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
import com.mojang.authlib.GameProfile
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
    // 特训筛选三态：0 = 不限，1 = 仅含训练，2 = 仅不含训练
    private var htFilter = 0
    private var htButton: NineSliceButton? = null
    private var filterExpanded = false
    private var sortMode = "NEWEST"
    private var genderFilter = ""
    private var typeFilter = ""
    private var typeFilterIndex = 0
    private var abilityFilter = ""   // 特性翻译 key（空 = 不限）
    private var natureFilter = ""    // 性格翻译 key（空 = 不限；按生效性格匹配，不分薄荷）
    private val minIvs = IntArray(6) { -1 }

    // 筛选展开列表：filterListOpen = ""/type/ability/nature；互斥展开、限高滚动
    private var filterListOpen = ""
    private var filterListScroll = 0
    private val filterOptionButtons = mutableListOf<NineSliceButton>()
    // 特性选项（搜索框物种解析后重建）
    private var abilityOptions = listOf<Pair<String, String>>() // (翻译 key, 显示名)

    private lateinit var genderButton: NineSliceButton
    private lateinit var typeButton: ButtonWidget
    private lateinit var abilityButton: ButtonWidget
    private lateinit var natureButton: ButtonWidget
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
    private data class IconData(
        val displayName: String,
        val renderable: RenderablePokemon,
        val state: FloatingState,
        // 球种/携带物栈随图标缓存：render 每帧 new ItemStack 是分配热点，构建一次复用
        val ballStack: ItemStack?,
        val heldStack: ItemStack?
    )
    private val iconData = mutableMapOf<Int, IconData>()

    // tooltip 内容缓存：内容只取决于条目，悬停同一行时每帧重建全部文本行是悬停掉帧主因
    private var tooltipCacheKey: UUID? = null
    private var tooltipCacheLines: List<Pair<Text, Int>> = emptyList()
    private var tooltipCacheHeldLine = -1
    private var tooltipCacheMaxWidth = 0

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
            iconData[index] = IconData(
                displayName,
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

    override fun init() {
        super.init()
        sendToServer(RequestBalancePayload())

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
            // 同步解析物种 → 特性筛选选项；物种变化时重置特性选择
            updateAbilityOptions(it)
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
            Text.translatable("cobblemarket.gui.back"), { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) }
        ))

        // Filter controls only when expanded
        if (filterExpanded) {

        // Row 2 (y=66): 性别图标 | 属性 | 特性 | 性格（四按钮，间距 4px；后三者展开选择）
        genderButton = NineSliceButton(
            leftX + 4, 66, 24, 20,
            Text.literal(""),
            { cycleGender() },
            iconTexW = 6, iconTexH = 8, iconScale = 1.5f
        )
        updateGenderButton()
        addDrawableChild(genderButton)

        typeButton = NineSliceButton(
            leftX + 32, 66, 60, 20,
            typeButtonText(),
            { toggleFilterList("type") },
            if (typeFilter.isNotEmpty()) typeColor("cobblemon.type.$typeFilter") else 0xFFFFFF
        )
        addDrawableChild(typeButton)

        abilityButton = NineSliceButton(
            leftX + 96, 66, 96, 20,
            abilityButtonText(),
            { toggleFilterList("ability") },
            if (abilityFilter.isNotEmpty()) GOLD_COLOR else 0xFFFFFF
        )
        addDrawableChild(abilityButton)

        natureButton = NineSliceButton(
            leftX + 196, 66, 96, 20,
            natureButtonText(),
            { toggleFilterList("nature") },
            if (natureFilter.isNotEmpty()) GOLD_COLOR else 0xFFFFFF
        )
        addDrawableChild(natureButton)

        // Row 3 (y=78): HP, Atk, Def IV inputs
        hpField = createIvField(leftX + 4, 90, "HP")
        atkField = createIvField(leftX + 100, 90, "ATK")
        defField = createIvField(leftX + 196, 90, "DEF")

        // Row 4 (y=102): SpA, SpD, Spd IV inputs
        spaField = createIvField(leftX + 4, 114, "SpA")
        spdField = createIvField(leftX + 100, 114, "SpD")
        speField = createIvField(leftX + 196, 114, "Spd")

        // Row 5 (y=138): Shiny(符号) + Sort + HT + Mine + Reset
        shinyButton = NineSliceButton(
            leftX + 4, 138, 30, 20,
            Text.literal(if (shinyOnly) "★" else "☆"),
            { toggleShiny() },
            // resize 重建时保持当前状态的颜色
            if (shinyOnly) GOLD_COLOR else 0xFFFFFF
        )
        addDrawableChild(shinyButton)

        sortButton = NineSliceButton(
            leftX + 36, 138, 90, 20,
            Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay())),
            { cycleSort() }
        )
        addDrawableChild(sortButton)

        htButton = NineSliceButton(
            leftX + 128, 138, 62, 20,
            htButtonText(),
            { toggleHtFilter() },
            if (htFilter != 0) GOLD_COLOR else 0xFFFFFF
        )
        addDrawableChild(htButton)

        mineButton = NineSliceButton(
            leftX + 192, 138, 50, 20,
            Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine"),
            { toggleMineOnly() }
        )
        addDrawableChild(mineButton)

        resetButton = NineSliceButton(
            leftX + 244, 138, 52, 20,
            Text.translatable("cobblemarket.gui.reset"),
            { resetFilters() }
        )
        addDrawableChild(resetButton)

        } // end if (filterExpanded)

        // 重建（resize/折叠切换）时收起筛选展开列表，避免状态与控件不一致
        filterListOpen = ""
        filterListScroll = 0

        // Page buttons at bottom：底部有对称分割线（最后一行下方 4px，与顶部分割线对称），
        // 按钮放在分割线与背景底边之间居中偏上 5px
        val listBottom = getListStartY() + getMaxVisibleRows() * 24
        val btnY = (listBottom + 5 + (height - 32)) / 2 - 10 - 5

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
    // 预留 72 = 分页按钮高 20 + 4 空隙 + 底部边框切片 16 + 背景底边下空隙：
    // 按钮贴列表最后一行下方、完全不压背景底部边框（原 48 时按钮底越过背景底边 4px）
    private fun getMaxVisibleRows() = maxOf(0, (height - getListStartY() - 72) / 24)

    private fun rebuildBuyButtons() {
        buyButtons.forEach { remove(it) }
        buyButtons.clear()

        // 筛选展开列表/确认弹窗打开时行按钮保持隐藏：事件/刷新触发的重建会把新按钮
        // 追加到 children 末尾，浮在列表按钮/遮罩之上刺穿
        if (filterListOpen.isNotEmpty() || confirmEntry != null || cancelEntry != null) return

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2
        val startY = getListStartY()
        val rowHeight = 24
        val playerUuid = client?.player?.uuid

        displayedListings().take(getMaxVisibleRows()).forEachIndexed { di, (origIndex, entry) ->
            val y = startY + di * rowHeight
            val isMine = playerUuid != null && entry.sellerUuid == playerUuid
            val label = if (isMine) Text.literal("✕").formatted(Formatting.RED) else Text.translatable("cobblemarket.gui.buy")
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
        // 收起可能展开的筛选列表（否则列表按钮残留在遮罩下可点）
        filterListOpen = ""
        rebuildFilterList()
    }

    private fun openCancelDialog(entry: ListingEntry) {
        cancelEntry = entry
        buildConfirmRenderable(entry)
        filterListOpen = ""
        rebuildFilterList()
    }

    private fun closeConfirmDialog() {
        confirmEntry = null
        cancelEntry = null
        confirmRenderable = null
        confirmDisplayName = ""
    }

    private fun confirmPurchase() {
        val entry = confirmEntry ?: return
        sendToServer(BuyFromMarketPayload(entry.id))
        closeConfirmDialog()
    }

    private fun confirmCancel() {
        val entry = cancelEntry ?: return
        sendToServer(CancelFromMarketPayload(entry.id))
        closeConfirmDialog()
    }

    // 带索引列表缓存：render/renderBackground 每帧调用 displayedListings()，
    // 每帧 withIndex().toList() 是分配热点，改为数据到达时重建
    private var indexedListings = listOf<IndexedValue<ListingEntry>>()

    private fun displayedListings(): List<IndexedValue<ListingEntry>> = indexedListings

    // ── Gender filter（公母图标三态循环：♂♀ 不限 → ♂ 仅公 → ♀ 仅母） ──

    private fun updateGenderButton() {
        genderButton.iconLeft = when (genderFilter) {
            "FEMALE" -> GENDER_ICON_FEMALE
            else -> GENDER_ICON_MALE
        }
        genderButton.iconLeft2 = if (genderFilter.isEmpty()) when (genderFilter) {
            "FEMALE" -> null
            else -> GENDER_ICON_FEMALE
        } else null
    }

    private fun cycleGender() {
        genderFilter = when (genderFilter) {
            "" -> "MALE"
            "MALE" -> "FEMALE"
            else -> ""
        }
        currentPage = 1
        updateGenderButton()
        refreshData()
    }

    // ── 属性/特性/性格展开选择（互斥、限高滚动、展开时隐藏被覆盖控件） ──

    private fun typeButtonText(): Text {
        val key = typeOptions.getOrElse(typeFilterIndex) { "" }
        val label = if (key.isEmpty()) Text.translatable("cobblemarket.gui.filter_any")
            else Text.translatable("cobblemon.type.$key")
        return Text.translatable("cobblemarket.gui.type").append(": ").append(label)
    }

    private fun abilityButtonText(): Text {
        val label = if (abilityFilter.isEmpty()) Text.translatable("cobblemarket.gui.filter_any")
            else Text.translatable(abilityFilter)
        return Text.translatable("cobblemarket.buy_order.ability_label").append(": ").append(label)
    }

    private fun natureButtonText(): Text {
        val label = if (natureFilter.isEmpty()) Text.translatable("cobblemarket.gui.filter_any")
            else Text.translatable(natureFilter)
        return Text.translatable("cobblemarket.buy_order.nature_label").append(": ").append(label)
    }

    /** 25 种性格（翻译 key, 显示名），与求购单创建一致 */
    private val natureOptions: List<Pair<String, String>> by lazy {
        com.cobblemon.mod.common.api.pokemon.Natures.all().map { n ->
            val key = "cobblemon.nature.${n.name.path}"
            val t = Text.translatable(key).string
            key to (if (t == key) n.displayName else t)
        }
    }

    private fun toggleFilterList(kind: String) {
        filterListOpen = if (filterListOpen == kind) "" else kind
        filterListScroll = 0
        rebuildFilterList()
    }

    private fun rebuildFilterList() {
        filterOptionButtons.forEach { remove(it) }
        filterOptionButtons.clear()
        val open = filterListOpen.isNotEmpty()
        // 展开时隐藏被覆盖的控件（IV 两行 + 底行按钮 + 行按钮，列表从 y=86 起最多 8 行）；
        // 行按钮必须 visible=false：列表按钮背景贴图中间区域半透明，下层行按钮文字会透出
        listOf(hpField, atkField, defField, spaField, spdField, speField).forEach { it?.visible = !open }
        if (::shinyButton.isInitialized) shinyButton.visible = !open
        if (::sortButton.isInitialized) sortButton.visible = !open
        htButton?.visible = !open
        if (::mineButton.isInitialized) mineButton.visible = !open
        if (::resetButton.isInitialized) resetButton.visible = !open
        buyButtons.forEach { it.visible = !open }
        if (!open) return
        val options: List<Pair<String, String>> = when (filterListOpen) {
            "type" -> listOf("" to "") + typeOptions.filter { it.isNotEmpty() }.map { it to "cobblemon.type.$it" }
            "ability" -> listOf("" to "") + abilityOptions
            "nature" -> listOf("" to "") + natureOptions
            else -> emptyList()
        }
        val leftX = width / 2 - panelWidth / 2
        options.drop(filterListScroll).take(MAX_FILTER_LIST_ROWS).forEachIndexed { i, (key, label) ->
            val idx = filterListScroll + i
            val display = if (key.isEmpty()) Text.translatable("cobblemarket.gui.filter_any").string
                else Text.translatable(label).string.let { t -> if (t == label) label else t }
            val btn = NineSliceButton(
                leftX + 4, 86 + i * 14, 288, 14,
                if (isFilterSelected(filterListOpen, key, idx))
                    com.shusheng.cobblemarket.util.TextUtil.selectedText(
                        com.shusheng.cobblemarket.util.TextUtil.truncateString(display, 260)
                    )
                else Text.literal(com.shusheng.cobblemarket.util.TextUtil.truncateString(display, 260)),
                { selectFilterOption(filterListOpen, key, idx) },
                // 属性选项文字用对应属性色
                if (filterListOpen == "type" && key.isNotEmpty()) typeColor("cobblemon.type.$key") else 0xFFFFFF
            )
            filterOptionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    private fun isFilterSelected(kind: String, key: String, idx: Int): Boolean = when (kind) {
        "type" -> key.isNotEmpty() && key == typeFilter
        "ability" -> key.isNotEmpty() && key == abilityFilter
        "nature" -> key.isNotEmpty() && key == natureFilter
        else -> false
    }

    private fun selectFilterOption(kind: String, key: String, idx: Int) {
        when (kind) {
            "type" -> {
                typeFilter = key
                typeFilterIndex = typeOptions.indexOf(key).coerceAtLeast(0)
                typeButton.message = typeButtonText()
                (typeButton as? NineSliceButton)?.textColor =
                    if (key.isNotEmpty()) typeColor("cobblemon.type.$key") else 0xFFFFFF
            }
            "ability" -> {
                abilityFilter = key
                abilityButton.message = abilityButtonText()
                (abilityButton as? NineSliceButton)?.textColor = if (key.isNotEmpty()) GOLD_COLOR else 0xFFFFFF
            }
            "nature" -> {
                natureFilter = key
                natureButton.message = natureButtonText()
                (natureButton as? NineSliceButton)?.textColor = if (key.isNotEmpty()) GOLD_COLOR else 0xFFFFFF
            }
        }
        filterListOpen = ""
        rebuildFilterList()
        currentPage = 1
        refreshData()
    }

    /** 搜索框物种解析 → 特性筛选选项（与求购单创建同语义）；解析不出则清空选项并重置选择 */
    private fun updateAbilityOptions(text: String) {
        val trimmed = text.trim()
        val species = if (trimmed.isEmpty()) null else {
            if (trimmed.contains(":")) {
                Identifier.tryParse(trimmed)?.let { PokemonSpecies.getByIdentifier(it) }
            } else {
                val byName = try { PokemonSpecies.getByName(trimmed) } catch (_: Exception) { null }
                byName ?: PokemonSpecies.implemented.firstOrNull {
                    it.translatedName.string == trimmed || it.translatedName.string.contains(trimmed)
                }
            }
        }
        abilityOptions = species?.abilities?.map { pa ->
            val key = "cobblemon.ability.${pa.template.name}"
            val t = Text.translatable(key).string
            key to (if (t == key) pa.template.displayName else t)
        } ?: emptyList()
        // 物种变化后旧特性选择失效，重置
        if (abilityFilter.isNotEmpty() && abilityOptions.none { it.first == abilityFilter }) {
            abilityFilter = ""
            abilityButton.message = abilityButtonText()
            (abilityButton as? NineSliceButton)?.textColor = 0xFFFFFF
        }
    }

    // ── Shiny toggle ──

    private fun toggleShiny() {
        shinyOnly = !shinyOnly
        currentPage = 1
        shinyButton.message = Text.literal(if (shinyOnly) "★" else "☆")
        // 开 = 金色 ★，关 = 白色 ☆（与价格限制/黑名单的闪光按钮一致）
        shinyButton.textColor = if (shinyOnly) GOLD_COLOR else 0xFFFFFF
        mineButton.message = Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine")
        refreshData()
    }

    // ── Hyper trained filter ──

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

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        // 筛选展开列表滚动
        if (filterListOpen.isNotEmpty()) {
            val total = when (filterListOpen) {
                "type" -> typeOptions.size
                "ability" -> abilityOptions.size + 1
                "nature" -> natureOptions.size + 1
                else -> 0
            }
            if (total > MAX_FILTER_LIST_ROWS) {
                filterListScroll = (filterListScroll - verticalAmount.toInt()).coerceIn(0, total - MAX_FILTER_LIST_ROWS)
                rebuildFilterList()
            }
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
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
        shinyButton.message = Text.literal("☆")
        shinyButton.textColor = 0xFFFFFF
        htFilter = 0
        htButton?.setMessage(htButtonText())
        htButton?.textColor = 0xFFFFFF
        abilityFilter = ""
        natureFilter = ""
        updateGenderButton()
        typeButton.message = typeButtonText()
        abilityButton.message = abilityButtonText()
        natureButton.message = natureButtonText()
        (typeButton as? NineSliceButton)?.textColor = 0xFFFFFF
        (abilityButton as? NineSliceButton)?.textColor = 0xFFFFFF
        (natureButton as? NineSliceButton)?.textColor = 0xFFFFFF
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
        sendToServer(BuyFromMarketPayload(listingId))
    }

    private fun openSellScreen() {
        client?.setScreen(SellSelectScreen())
    }

    private fun requestCancel(listingId: java.util.UUID) {
        sendToServer(CancelFromMarketPayload(listingId))
    }

    private fun collectBalance() {
        sendToServer(CollectBalancePayload())
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

    // 防抖计时器：搜索框 changedListener 与 IV 输入变化都置 dirty，250ms 静默后统一发请求
    override fun tick() {
        if (searchDirty && System.currentTimeMillis() - lastSearchEdit >= 250) {
            searchDirty = false
            currentPage = 1
            refreshData()
        }
    }

    private fun refreshData(ivs: IntArray = minIvs) {
        sendToServer(
            RequestMarketPayload(
                speciesFilter = localizeSpeciesQuery(searchField?.text?.trim().orEmpty()),
                shinyOnly = shinyOnly,
                minLevel = 0,
                maxLevel = -1, // 哨兵：无上限（与 minLevel 的 0 哨兵对称）
                sortMode = sortMode,
                page = currentPage,
                genderFilter = genderFilter,
                typeFilter = typeFilter,
                abilityFilter = abilityFilter,
                natureFilter = natureFilter,
                minIvsHp = ivs[0],
                minIvsAtk = ivs[1],
                minIvsDef = ivs[2],
                minIvsSpAtk = ivs[3],
                minIvsSpDef = ivs[4],
                minIvsSpd = ivs[5],
                pageSize = getMaxVisibleRows(),
                mineOnly = showMineOnly,
                htFilter = htFilter
            )
        )
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
        // 固拉多第七步（精灵市场背景下层水平右飞）画在 super.render 之前：
        // super.render 内部先调 renderBackground 画市场面板背景，精灵先画会被面板自然盖住
        // 弹窗打开时不画（与末尾上层绘制一致；dialogOpen 是后段局部变量，这里用等价条件）
        if (confirmEntry == null && cancelEntry == null) {
            renderGroudonFly(context, true)
        }
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

        // 余额 + 待收款：文字标签默认色，金额蓝/绿（2026-08-24 拍板）；余额来自全局缓存，交易操作后自动刷新
        val balText: Text? = com.shusheng.cobblemarket.client.BalanceCache.balance.takeIf { it.isNotEmpty() }?.let {
            Text.translatable("cobblemarket.gui.balance",
                Text.literal(it + " " + com.shusheng.cobblemarket.client.inlineCurrencyUnit()).formatted(Formatting.GOLD))
        }
        val balW = balText?.let { textRenderer.getWidth(it) + 4 } ?: 0
        if (balText != null) context.drawTextWithShadow(textRenderer, balText, leftX, 31, 0xFFFFFF)
        context.drawTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.gui.pending_balance",
                Text.literal(com.shusheng.cobblemarket.client.formatBalanceLong(pendingBalance) + " " + com.shusheng.cobblemarket.client.inlineCurrencyUnit()).formatted(Formatting.GREEN)),
            leftX + balW, 31, 0xFFFFFF)

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

        // 筛选展开列表打开时跳过分隔线与行内容（画在 children 之后，会刺穿列表按钮）
        if (filterListOpen.isNotEmpty()) return

        context.fill(leftX, dividerY, leftX + panelWidth, dividerY + 1, 0xFF555555.toInt())
        // 底部分割线：最多显示行数的最后一行下方 4px（与顶部 startY-4 对称），分页按钮在其与背景底边之间居中
        val listBottom = getListStartY() + visibleRows * rowHeight
        context.fill(leftX, listBottom + 4, leftX + panelWidth, listBottom + 5, 0xFF555555.toInt())

        val displayList = displayedListings()

        if (displayList.isEmpty() && filterListOpen.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.gui.no_listings").formatted(Formatting.GRAY),
                centerX, startY + 50, 0xFFFFFF
            )
        }

        // 筛选展开列表打开时跳过行内容与悬停（行内容画在 children 之后，会刺穿筛选列表按钮）
        if (filterListOpen.isNotEmpty()) return

        // 确认弹窗打开时行内图标不渲染（物品/精灵模型图标走独立渲染层，z 平移盖不住，会刺穿遮罩）
        val dialogOpen = confirmEntry != null || cancelEntry != null
        displayList.take(visibleRows).forEachIndexed { di, (origIndex, entry) ->
            val y = startY + di * rowHeight

            // Pokemon icon slot background（GUI 层，弹窗遮罩会自动压暗，照常渲染）
            val slotX = leftX + 2
            val slotY = y + 2
            val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
            context.matrices.push()
            context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
            context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
            context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
            context.matrices.pop()

            // 3D Pokemon icon（弹窗打开时不渲染——模型层在衬底/遮罩之上，压暗仍会浮在弹窗上）
            if (!dialogOpen) renderPokemonIcon(context, origIndex, leftX + 2, y + 2, iconSize)

            // Ball icon（球种统一在精灵名称左侧；弹窗打开时隐藏——物品图标无色调参数无法变暗）
            if (!dialogOpen) {
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

            // Species name (translated) + 金色闪光星标（★，拆段绘制）；
            // 名字带阴影：行背景偏灰，暗色属性名（幽灵/恶等）无阴影看不清
            val displayName = iconData[origIndex]?.displayName ?: entry.species
            context.drawTextWithShadow(textRenderer, displayName, leftX + 40, y + 7, typeColor(entry.primaryType))
            var nameWidth = textRenderer.getWidth(displayName)
            if (entry.shiny) {
                context.drawTextWithShadow(textRenderer, "★", leftX + 40 + nameWidth + 2, y + 7, GOLD_COLOR)
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

            // Held item icon (after gender；弹窗打开时不渲染，防刺穿遮罩)
            if (!dialogOpen) {
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

            // Seller avatar
            drawSellerAvatar(context, entry.sellerUuid, entry.sellerName, leftX + 115, y + 4, 16)

            // Level
            val levelText = Text.translatable("cobblemarket.gui.lv").string + entry.level
            context.drawText(textRenderer, levelText, leftX + 135, y + 7, 0x000000, false)

            // Price（右对齐到按钮左缘：价格再长也只向左延伸，不会遮按钮）
            val priceText = "${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ${com.shusheng.cobblemarket.client.inlineCurrencyUnit()}"
            val btnLeft = leftX + panelWidth - 42
            context.drawTextWithShadow(textRenderer, priceText, btnLeft - textRenderer.getWidth(priceText) - 4, y + 7, 0xFFAA00)
        }

        // Tooltip on hover
        if (confirmEntry == null && hoveredRow in displayList.indices) {
            renderTooltip(context, displayList[hoveredRow].value, mouseX, mouseY)
        }

        prevButton.active = currentPage > 1
        nextButton.active = currentPage < totalPages

        // 固拉多飞行：悬浮在界面之上（盖住面板/行），弹窗打开时不画（遮罩应盖住它）。
        // flush 夹心 + 清深度（照庆祝动画）：行内 3D 精灵图标/物品图标写深度或抬高 z，
        // 普通 GUI 纹理后画也会被它们盖住——先提交全部攒批、清深度后再画固拉多
        if (!dialogOpen) {
            context.draw()
            com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.MinecraftClient.IS_SYSTEM_MAC)
            renderGroudonFly(context, false)
            context.draw()
        }

        if (confirmEntry != null || cancelEntry != null) {
            renderConfirmDialog(context, mouseX, mouseY)
        }
    }

    /** 固拉多飞行（重做第四步）：水平飞入停靠点 2s → 逆时针弧 90° 出屏 1.8s（θ 90°→0°，头朝上竖直出屏）→
     *  屏外切线掉头朝下 → 顺时针绕半圆 180° 到屏幕右半上方 1.2s（屏外提速）→
     *  屏外快速竖直下移 0.4s → 屏幕内加速下坠出屏底（速度与原 3s 版屏幕内段一致）→
     *  第四步（不连接上一步）：镜像图（头朝左、上下正常）从屏幕右上角外水平飞入停在右上角 1.2s，
     *  停留 5s 悬停（等下一步）。
     *  路径点 (px,py) 是图片左侧中间的轨迹（旋转轴心 = 图片左侧中间，贴图坐标 (0,150)），
     *  精灵主体拖在轴心后方，转弯时绕尾部牵引转向。
     *  40 帧拼在 sprite sheet 上只改 UV；共享 smoothRot 切换界面朝向平滑延续 */
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

    private fun renderPokemonIcon(context: DrawContext, index: Int, x: Int, y: Int, size: Int, dark: Boolean = false) {
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
                scale = 4.5f,
                // 弹窗打开时压暗（模型走独立渲染层，遮罩盖不住；颜色系数模拟遮罩效果）
                r = if (dark) 0.35f else 1f,
                g = if (dark) 0.35f else 1f,
                b = if (dark) 0.35f else 1f
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
            lines.add(EntryBadgeRenderer.nameWithShinyStar(iconData[listings.indexOf(entry)]?.displayName ?: entry.species, entry.shiny)
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
            lines.add(Text.literal("  $hp:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsHp, entry.htHp)}") to ivColors[0])
            lines.add(Text.literal("  $atk:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsAtk, entry.htAtk)}") to ivColors[1])
            lines.add(Text.literal("  $def:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsDef, entry.htDef)}") to ivColors[2])
            lines.add(Text.literal("  $spa:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpAtk, entry.htSpAtk)}") to ivColors[3])
            lines.add(Text.literal("  $spd:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpDef, entry.htSpDef)}") to ivColors[4])
            lines.add(Text.literal("  $spe:${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpd, entry.htSpd)}") to ivColors[5])
            lines.add(Text.translatable("cobblemarket.gui.tooltip_seller").append(" ").append(Text.literal(entry.sellerName)) to w)
            lines.add(Text.translatable("cobblemarket.gui.tooltip_price").append(" ").append(
                Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ${com.shusheng.cobblemarket.client.displayCurrency(entry.currencyName)}").formatted(Formatting.GOLD)) to w)

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
                Identifier.tryParse(entry.heldItemId)?.let { heldId ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = ItemStack(Registries.ITEM.get(heldId)),
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

    private companion object {
        val GENDER_ICON_MALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
        val GENDER_ICON_FEMALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
        const val MAX_FILTER_LIST_ROWS = 8
    }

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
        indexedListings = listings.withIndex().toList()
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
