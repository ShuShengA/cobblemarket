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

class AdminPokemonScreen : Screen(Text.translatable("cobblemarket.op.pokemon")) {

    private var listings = listOf<ListingEntry>()
    private var currentPage = 1
    private var totalPages = 1

    private var searchField: TextFieldWidget? = null
    private var sellerField: TextFieldWidget? = null
    private var shinyOnly = false
    private var showMineOnly = false
    private var filterExpanded = false
    private var sortMode = "NEWEST"
    private val minIvs = IntArray(6) { -1 }

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

    private data class IconData(val displayName: String, val renderable: RenderablePokemon, val state: FloatingState)
    private val iconData = mutableMapOf<Int, IconData>()

    private fun cacheIcons() {
        iconData.clear()
        listings.forEachIndexed { index, entry ->
            val id = Identifier.tryParse(entry.speciesId) ?: return@forEachIndexed
            val species = PokemonSpecies.getByIdentifier(id) ?: return@forEachIndexed
            // 用挂单真实 aspects（性别形态/地区形态等），与其他界面一致
            val aspects = entry.aspects.toMutableSet()
            if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
            iconData[index] = IconData(species.translatedName.string, RenderablePokemon(species, aspects, ItemStack.EMPTY), FloatingState())
        }
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
        searchField = TextFieldWidget(textRenderer, leftX + 2, 44, panelWidth - 4 - 52, 16, Text.translatable("cobblemarket.gui.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.gui.search_placeholder").formatted(Formatting.GRAY))
        addSelectableChild(searchField)
        addDrawableChild(searchField)

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

        if (filterExpanded) {
            hpField = createIvField(leftX + 4, 88, "HP")
            atkField = createIvField(leftX + 100, 88, "ATK")
            defField = createIvField(leftX + 196, 88, "DEF")

            spaField = createIvField(leftX + 4, 112, "SpA")
            spdField = createIvField(leftX + 100, 112, "SpD")
            speField = createIvField(leftX + 196, 112, "Spd")

            shinyButton = NineSliceButton(
                leftX + 4, 136, 90, 20,
                Text.translatable(if (shinyOnly) "cobblemarket.gui.shiny_on" else "cobblemarket.gui.shiny_off"),
                { toggleShiny() },
                // resize 重建时保持当前状态的颜色
                if (shinyOnly) GOLD_COLOR else 0xFFFFFF
            )
            addDrawableChild(shinyButton)

            sortButton = NineSliceButton(
                leftX + 98, 136, 90, 20,
                Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay())),
                { cycleSort() }
            )
            addDrawableChild(sortButton)

            mineButton = NineSliceButton(
                leftX + 192, 136, 50, 20,
                Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine"),
                { toggleMineOnly() }
            )
            addDrawableChild(mineButton)

            resetButton = NineSliceButton(
                leftX + 246, 136, 50, 20,
                Text.translatable("cobblemarket.gui.reset"),
                { resetFilters() }
            )
            addDrawableChild(resetButton)
        }

        // 分页
        val listBottom = getListStartY() + getMaxVisibleRows() * 24
        prevButton = NineSliceButton(leftX, listBottom, 80, 20, Text.translatable("cobblemarket.gui.prev"), { prevPage() })
        addDrawableChild(prevButton)
        nextButton = NineSliceButton(leftX + panelWidth - 80, listBottom, 80, 20, Text.translatable("cobblemarket.gui.next"), { nextPage() })
        addDrawableChild(nextButton)

        rebuildCancelButtons()
        refreshData()
    }

    private fun createIvField(x: Int, y: Int, placeholder: String): TextFieldWidget {
        val field = TextFieldWidget(textRenderer, x, y, 92, 16, Text.literal(""))
        field.setPlaceholder(Text.literal(placeholder))
        field.setTextPredicate { it.length <= 2 && it.all { c -> c.isDigit() } }
        addSelectableChild(field)
        addDrawableChild(field)
        return field
    }

    private fun getListStartY() = if (filterExpanded) 160 else 84
    private fun getMaxVisibleRows() = maxOf(0, (height - getListStartY() - 48) / 24)

    private fun rebuildCancelButtons() {
        cancelButtons.forEach { remove(it) }
        cancelButtons.clear()
        val leftX = width / 2 - panelWidth / 2
        val startY = getListStartY()
        val rowHeight = 24
        displayedListings().take(getMaxVisibleRows()).forEachIndexed { di, (_, entry) ->
            val y = startY + di * rowHeight
            val btn = NineSliceButton(leftX + panelWidth - 42, y + 4, 38, 16, Text.literal("✕"), ButtonWidget.PressAction { cancelListing(entry) })
            cancelButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    private fun displayedListings(): List<IndexedValue<ListingEntry>> {
        var result = listings.withIndex().toList()
        val query = searchField?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return result
        result = result.filter { (origIndex, entry) ->
            entry.species.contains(query, ignoreCase = true) ||
            (iconData[origIndex]?.displayName?.contains(query, ignoreCase = true) == true)
        }
        return result
    }

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
        shinyButton.message = Text.translatable(if (shinyOnly) "cobblemarket.gui.shiny_on" else "cobblemarket.gui.shiny_off")
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
        sortMode = "NEWEST"
        currentPage = 1
        for (i in 0..5) minIvs[i] = -1
        hpField?.text = ""; atkField?.text = ""; defField?.text = ""
        spaField?.text = ""; spdField?.text = ""; speField?.text = ""
        shinyButton.message = Text.translatable("cobblemarket.gui.shiny_off")
        shinyButton.textColor = 0xFFFFFF
        sortButton.message = Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay()))
        refreshData()
    }

    private fun prevPage() { if (currentPage > 1) { currentPage--; refreshData() } }
    private fun nextPage() { if (currentPage < totalPages) { currentPage++; refreshData() } }

    private fun syncIvFromFields(): Boolean {
        val fields = arrayOf(hpField, atkField, defField, spaField, spdField, speField)
        var changed = false
        for (i in 0..5) {
            val raw = fields[i]?.text ?: ""
            val digits = raw.filter { it.isDigit() }.take(2)
            val iv = if (digits.isEmpty()) -1 else digits.toIntOrNull()?.coerceIn(0, 31) ?: -1
            if (digits != raw) fields[i]?.text = if (iv <= 0) "" else iv.toString()
            if (minIvs[i] != iv) changed = true
            minIvs[i] = iv
        }
        return changed
    }

    private fun refreshData() {
        ClientPlayNetworking.send(
            AdminRequestPokemonPayload(
                speciesFilter = "",
                sellerFilter = sellerField?.text?.trim() ?: "",
                shinyOnly = shinyOnly,
                sortMode = sortMode,
                page = currentPage,
                minIvsHp = minIvs[0],
                minIvsAtk = minIvs[1],
                minIvsDef = minIvs[2],
                minIvsSpAtk = minIvs[3],
                minIvsSpDef = minIvs[4],
                minIvsSpd = minIvs[5],
                pageSize = getMaxVisibleRows(),
                mineOnly = showMineOnly
            )
        )
    }

    fun onMarketData(payload: MarketDataPayload) {
        listings = payload.entries
        totalPages = payload.totalPages
        currentPage = payload.currentPage
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
        ClientPlayNetworking.send(AdminCancelPokemonPayload(entry.id))
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

            val slotX = leftX + 2
            val slotY = y + 2
            val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
            context.matrices.push()
            context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
            context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
            context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
            context.matrices.pop()

            renderPokemonIcon(context, origIndex, leftX + 2, y + 2, iconSize)

            // 球种（名称左侧）+ 物种名 + 性别图标 + 携带物 + 头像 + 等级 + 价格——与精灵市场界面布局一致
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

            drawSellerAvatar(context, entry.sellerUuid, entry.sellerName, leftX + 115, y + 4, 16)

            val levelText = Text.translatable("cobblemarket.gui.lv").string + entry.level
            context.drawText(textRenderer, levelText, leftX + 135, y + 7, 0xAAAAAA, false)

            // 价格右对齐到取消按钮左缘
            val priceText = "${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ◆"
            val btnLeft = leftX + panelWidth - 42
            context.drawTextWithShadow(textRenderer, priceText, btnLeft - textRenderer.getWidth(priceText) - 4, y + 7, 0x55FFFF)
        }

        if (confirmEntry == null && hoveredRow in displayList.indices) {
            renderTooltip(context, displayList[hoveredRow].value, mouseX, mouseY)
        }

        prevButton.active = currentPage > 1
        nextButton.active = currentPage < totalPages

        if (confirmEntry != null) {
            renderConfirmDialog(context, mouseX, mouseY)
        }
    }

    private val defaultSkinTexture = Identifier.of("minecraft", "textures/entity/player/wide/steve.png")

    private fun getSellerSkin(uuid: java.util.UUID, name: String): Identifier {
        client?.networkHandler?.getPlayerListEntry(uuid)?.skinTextures?.texture()?.let { return it }
        return client?.skinProvider?.getSkinTextures(com.mojang.authlib.GameProfile(uuid, name))?.texture() ?: defaultSkinTexture
    }

    private fun drawSellerAvatar(context: DrawContext, uuid: java.util.UUID, name: String, x: Int, y: Int, size: Int) {
        val texture = getSellerSkin(uuid, name)
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(size / 8f, size / 8f, 1f)
        context.drawTexture(texture, 0, 0, 8f, 8f, 8, 8, 64, 64)
        context.matrices.pop()
    }

    private fun renderPokemonIcon(context: DrawContext, index: Int, x: Int, y: Int, size: Int) {
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
                state = data.state, partialTicks = 0f, scale = 4.5f
            )
        } catch (_: Exception) {
        } finally {
            context.disableScissor()
            matrices.pop()
        }
    }

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
        lines.add(Text.literal("${Text.translatable("cobblemarket.gui.tooltip_price").formatted(Formatting.GRAY).string} ${entry.price} ${com.shusheng.cobblemarket.client.displayCurrency(entry.currencyName)}") to w)

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

    private fun renderConfirmDialog(context: DrawContext, mouseX: Int, mouseY: Int) {
        val entry = confirmEntry ?: return
        val centerX = width / 2
        val dialogW = 220
        val dialogH = 240
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 100.0)
        context.fill(0, 0, width, height, 0xC0000000.toInt())
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
        val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
        context.matrices.push()
        context.matrices.translate(iconX.toDouble(), iconY.toDouble(), 0.0)
        context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
        context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
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
}
