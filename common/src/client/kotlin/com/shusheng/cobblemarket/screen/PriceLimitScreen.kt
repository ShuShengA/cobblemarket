package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.playFailSound

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import com.shusheng.cobblemarket.market.ItemPriceLimitEntry
import com.shusheng.cobblemarket.market.PokemonPriceLimitEntry
import com.shusheng.cobblemarket.network.AddItemPriceLimitPayload
import com.shusheng.cobblemarket.network.AddPokemonPriceLimitPayload
import com.shusheng.cobblemarket.network.ItemPriceLimitDataPayload
import com.shusheng.cobblemarket.network.PokemonPriceLimitDataPayload
import com.shusheng.cobblemarket.network.RemoveItemPriceLimitPayload
import com.shusheng.cobblemarket.network.RemovePokemonPriceLimitPayload
import com.shusheng.cobblemarket.network.RequestItemPriceLimitPayload
import com.shusheng.cobblemarket.network.RequestPokemonPriceLimitPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.joml.Quaternionf

/**
 * 价格限制管理：精灵 / 物品 两个 tab。
 * 精灵规则：物种（留空 = 全部精灵）+ V 档（-1 = 不限，0~6 = 恰好 N 个 31）+ 价格范围。
 * 物品规则：物品 + 价格范围。列表骨架、对话框、展开列表交互均照搬黑名单界面。
 */
class PriceLimitScreen : Screen(Text.translatable("cobblemarket.op.price_limit")) {

    private val panelWidth = 296
    private val rowHeight = 24
    private val MAX_ITEM_LIST_ROWS = 8
    private val MAX_FORM_LIST_ROWS = 8

    // ── 主列表状态 ──
    private var currentTab = 0 // 0 = 精灵, 1 = 物品
    private var pokemonEntries = listOf<PokemonPriceLimitEntry>()
    private var itemEntries = listOf<ItemPriceLimitEntry>()
    // 过滤结果缓存：数据/搜索/筛选变化时重建，render 每帧只读（避免每帧全量 filter）
    private var filteredPokemonCache = listOf<PokemonPriceLimitEntry>()
    private var filteredItemsCache = listOf<ItemPriceLimitEntry>()
    // 过滤结果带原索引（iconData 以 pokemonEntries 索引为 key）：行渲染每帧 indexOf 是 O(n) 扫描，
    // 重建时一并构建；行显示字符串（物种解析 + 多段翻译拼接）同样随重建缓存
    private var filteredPokemonIndexed = listOf<IndexedValue<PokemonPriceLimitEntry>>()
    private var pokemonRowTexts = listOf<String>()
    private var itemRowTexts = listOf<String>()
    // 物品图标栈缓存（itemId → 解析栈）：行内每帧 tryParse + ItemStack 是分配热点，数据到达时构建
    private val itemIconStacks = mutableMapOf<String, ItemStack>()
    // tooltip 文本行缓存：内容只取决于条目，悬停同一行时每帧重建是悬停掉帧主因
    private var tooltipCacheKey: Any? = null
    private var tooltipCacheLines: List<String> = emptyList()
    private var searchField: TextFieldWidget? = null
    private var hoveredRow = -1
    private var scrollOffset = 0
    private var backButton: NineSliceButton? = null
    private var addButton: NineSliceButton? = null
    private var pokemonTabButton: NineSliceButton? = null
    private var itemTabButton: NineSliceButton? = null
    private val removeButtons = mutableListOf<NineSliceButton>()
    private val editButtons = mutableListOf<NineSliceButton>()

    // 列表特训筛选两态：0 = 不限（默认），2 = 不含特训（仅精灵 tab）
    private var listHtFilter = PokemonPriceLimitEntry.HT_ANY
    private var htFilterButton: NineSliceButton? = null
    // 对话框规则特训维度两态：不限（默认）↔ 不含特训
    private var ruleHtFilter = PokemonPriceLimitEntry.HT_ANY
    private var ruleHtButton: NineSliceButton? = null

    // ── 对话框公共状态 ──
    private var addField: TextFieldWidget? = null
    private var minField: TextFieldWidget? = null
    private var maxField: TextFieldWidget? = null
    private var addConfirmButton: NineSliceButton? = null
    private var addCancelButton: NineSliceButton? = null
    private var dialogError: String? = null

    // ── 对话框状态（精灵） ──
    private var editingPokemon: PokemonPriceLimitEntry? = null
    private var previewRenderable: RenderablePokemon? = null
    private val previewState = FloatingState()
    private var previewSpecies: Species? = null
    // 闪光三态（与黑名单一致的循环按钮）：不限 → 闪光 → 非闪光 → 不限
    private var shinyFilter = PokemonPriceLimitEntry.SHINY_ANY
    private var shinyButton: NineSliceButton? = null
    // V 档选项：索引 0 = 不限(-1)，1..7 = 0V..6V
    private val vOptions = listOf(-1, 0, 1, 2, 3, 4, 5, 6)
    private var vIndex = 0
    private var vButton: NineSliceButton? = null
    private var vListOpen = false
    private val vOptionButtons = mutableListOf<NineSliceButton>()
    // 形态选择（照黑名单）：全部形态 + 物种的各个 form；aspects 为空 = 默认形态。
    // 形态按钮与 V 数按钮并排各占一半（70px），点击展开列表（每行一个选项，可滚动），点选后收起
    private data class FormOption(val label: String, val aspects: Set<String>)
    private var formOptions = listOf<FormOption>()
    private var formIndex = 0
    private var formButton: NineSliceButton? = null
    private var formListOpen = false
    private var formListScroll = 0
    private val formOptionButtons = mutableListOf<NineSliceButton>()

    // ── 对话框状态（物品） ──
    private var editingItem: ItemPriceLimitEntry? = null
    private var matchedItems = listOf<String>()
    private var selectedItemIndex = -1
    private var itemListOpen = false
    private var itemListScroll = 0
    private val itemOptionButtons = mutableListOf<NineSliceButton>()
    private var itemSelectButton: NineSliceButton? = null
    private var previewItemId: String? = null

    private data class IconData(val displayName: String, val renderable: RenderablePokemon, val state: FloatingState)
    private val iconData = mutableMapOf<Int, IconData>()
    private val iconSize = 20

    private fun getListStartY() = 80
    private fun getMaxVisibleRows() = maxOf(0, (height - getListStartY() - 48) / rowHeight)

    // ── 通用文本 ──

    private fun vLabel(vCount: Int): String =
        if (vCount >= 0) "${vCount}V" else Text.translatable("cobblemarket.price_limit.v_any").string

    private fun priceText(min: Int?, max: Int?): String = when {
        min != null && max != null -> "$min ~ $max"
        min != null -> "≥ $min"
        max != null -> "≤ $max"
        else -> Text.translatable("cobblemarket.price_limit.unlimited").string
    }

    private fun pokemonName(speciesId: String): String {
        if (speciesId.isEmpty()) return Text.translatable("cobblemarket.price_limit.all_pokemon").string
        val species = Identifier.tryParse(speciesId)?.let { PokemonSpecies.getByIdentifier(it) }
        return species?.let { com.shusheng.cobblemarket.util.SpeciesText.displayName(it) } ?: speciesId
    }

    private fun itemDisplay(itemId: String): String {
        val id = Identifier.tryParse(itemId) ?: return itemId
        val item = Registries.ITEM.get(id)
        val name = item.name.string
        return if (name == item.translationKey) id.path else name
    }

    override fun init() {
        super.init()
        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        val backBtn = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(AdminScreen()) }
        )
        backButton = backBtn
        addDrawableChild(backBtn)

        // tab 切换按钮（标题下方留出物品 id 提示行）
        pokemonTabButton = NineSliceButton(centerX - 62, 44, 60, 14, Text.literal(""), { switchTab(0) })
        itemTabButton = NineSliceButton(centerX + 2, 44, 60, 14, Text.literal(""), { switchTab(1) })
        addDrawableChild(pokemonTabButton)
        addDrawableChild(itemTabButton)
        updateTabButtons()

        val savedSearch = searchField?.text ?: ""
        searchField = TextFieldWidget(textRenderer, leftX + 2, 62, panelWidth - 4 - 84 - 20, 16, Text.translatable("cobblemarket.gui.search"))
        updateSearchPlaceholder()
        // 搜索变化时重建行按钮：否则过滤后残留旧列表的编辑/删除按钮（与物品黑名单同款问题）
        searchField?.setChangedListener { _ ->
            rebuildFiltered()
            scrollOffset = 0
            rebuildRowButtons()
        }
        addSelectableChild(searchField)
        addDrawableChild(searchField)
        searchField?.text = savedSearch

        val addBtn = NineSliceButton(
            leftX + panelWidth - 84, 62, 18, 16,
            Text.literal("+"), { openAddDialog() }
        )
        addButton = addBtn
        addDrawableChild(addBtn)

        // 特训筛选（仅精灵 tab）：列表按规则的特训维度过滤
        htFilterButton = NineSliceButton(
            leftX + panelWidth - 60, 62, 60, 16,
            htFilterButtonText(),
            { toggleListHtFilter() },
            if (listHtFilter != 0) GOLD_COLOR else 0xFFFFFF
        )
        htFilterButton?.visible = currentTab == 0
        addDrawableChild(htFilterButton)

        scrollOffset = 0
        requestCurrentTabData()
        rebuildFiltered()
    }

    private fun updateTabButtons() {
        val pokemonLabel = Text.translatable("cobblemarket.price_limit.tab_pokemon").string
        val itemLabel = Text.translatable("cobblemarket.price_limit.tab_item").string
        pokemonTabButton?.setMessage(if (currentTab == 0) com.shusheng.cobblemarket.util.TextUtil.selectedText(pokemonLabel) else Text.literal(pokemonLabel))
        itemTabButton?.setMessage(if (currentTab == 1) com.shusheng.cobblemarket.util.TextUtil.selectedText(itemLabel) else Text.literal(itemLabel))
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        searchField?.text = ""
        updateSearchPlaceholder()
        rebuildFiltered()
        scrollOffset = 0
        hoveredRow = -1
        updateTabButtons()
        htFilterButton?.visible = currentTab == 0
        requestCurrentTabData()
    }

    // 搜索框占位符随 tab 切换：精灵 = 宝可梦名称...，物品 = 搜索物品
    private fun updateSearchPlaceholder() {
        searchField?.setPlaceholder(Text.translatable(
            if (currentTab == 0) "cobblemarket.gui.search_placeholder" else "cobblemarket.item.search"
        ).formatted(Formatting.GRAY))
    }

    private fun requestCurrentTabData() {
        if (currentTab == 0) sendToServer(RequestPokemonPriceLimitPayload())
        else sendToServer(RequestItemPriceLimitPayload())
    }

    // ── 添加/编辑对话框 ──

    private fun openAddDialog() {
        if (currentTab == 0) openPokemonDialog(null) else openItemDialog(null)
    }

    private fun hideMainControls() {
        searchField?.visible = false
        backButton?.visible = false
        addButton?.visible = false
        pokemonTabButton?.visible = false
        itemTabButton?.visible = false
        htFilterButton?.visible = false
        removeButtons.forEach { it.visible = false }
        editButtons.forEach { it.visible = false }
    }

    private fun openPokemonDialog(entry: PokemonPriceLimitEntry?) {
        editingPokemon = entry
        editingItem = null
        hideMainControls()
        val centerX = width / 2
        val dialogY = height / 2 - 75

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderPokemonDialogBackground(context)
            }
        })

        addField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 30, 140, 16, Text.literal(""))
        addField?.setPlaceholder(Text.translatable("cobblemarket.price_limit.species_placeholder"))
        addField?.setChangedListener { updatePreview(it) }
        addDrawableChild(addField)

        // 闪光三态循环按钮：物种输入框左侧
        shinyButton = NineSliceButton(centerX - 101, dialogY + 30, 20, 16, Text.literal(""), { cycleShiny() })
        addDrawableChild(shinyButton)
        updateShinyButton()

        vButton = NineSliceButton(centerX - 80, dialogY + 48, 68, 14, Text.literal(""), { toggleVList() })
        vButton?.visible = false
        addDrawableChild(vButton)

        // 形态选择按钮：与 V 数按钮并排各占 68px（中间留 4px 空隙），只有解析出多形态物种时显示
        formButton = NineSliceButton(centerX - 8, dialogY + 48, 68, 14, Text.literal(""), { toggleFormList() })
        formButton?.visible = false
        addDrawableChild(formButton)

        // 特训限定按钮（两态：不限 ↔ 不含特训），V/形态按钮下方留 4px 空隙
        ruleHtButton = NineSliceButton(centerX - 80, dialogY + 66, 140, 14, ruleHtButtonText(), { toggleRuleHtFilter() })
        addDrawableChild(ruleHtButton)

        minField = createPriceField(centerX - 48, dialogY + 84, "cobblemarket.price_limit.min_placeholder")
        maxField = createPriceField(centerX + 2, dialogY + 84, "cobblemarket.price_limit.max_placeholder")

        addConfirmButton = NineSliceButton(
            centerX - 85, dialogY + 104, 80, 20,
            Text.translatable("cobblemarket.blacklist.add"),
            { confirmPokemonAdd() }
        )
        addDrawableChild(addConfirmButton)
        addCancelButton = NineSliceButton(
            centerX + 5, dialogY + 104, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeDialog() }
        )
        addDrawableChild(addCancelButton)

        // 编辑模式预填：物种 ID 经 text setter 触发 updatePreview 解析出模型与形态选项
        vIndex = (entry?.vCount ?: -1) + 1
        vButton?.visible = true
        vButton?.setMessage(vButtonText())
        if (entry != null) {
            shinyFilter = entry.shinyFilter
            updateShinyButton()
            addField?.text = entry.speciesId
            // text setter 已触发 updatePreview 重建形态选项，恢复保存的形态选择与预览
            val savedFormIndex = formOptions.indexOfFirst { it.aspects == entry.aspects.toSet() }
            if (savedFormIndex >= 0) {
                formIndex = savedFormIndex
                formButton?.setMessage(formButtonText())
                refreshPreviewModel()
            }
            ruleHtFilter = entry.htFilter
            ruleHtButton?.setMessage(ruleHtButtonText())
            entry.minPrice?.let { minField?.text = it.toString() }
            entry.maxPrice?.let { maxField?.text = it.toString() }
        }
    }

    private fun shinyLabel(state: Int): String = when (state) {
        PokemonPriceLimitEntry.SHINY_YES -> Text.translatable("cobblemarket.gui.shiny_yes").string
        PokemonPriceLimitEntry.SHINY_NO -> Text.translatable("cobblemarket.gui.shiny_no").string
        else -> Text.translatable("cobblemarket.gui.shiny_any").string
    }

    // 按钮只显示符号：★ = 仅闪光（金色），☆ = 仅非闪光，不限 = 默认文字（行显示/tooltip 仍用完整词）
    private fun shinyButtonText(): String = when (shinyFilter) {
        PokemonPriceLimitEntry.SHINY_YES -> "★"
        PokemonPriceLimitEntry.SHINY_NO -> "☆"
        else -> Text.translatable("cobblemarket.gui.shiny_any").string
    }

    private fun updateShinyButton() {
        shinyButton?.setMessage(Text.literal(shinyButtonText()))
        shinyButton?.textColor = if (shinyFilter == PokemonPriceLimitEntry.SHINY_YES) GOLD_COLOR else 0xFFFFFF
    }

    private fun cycleShiny() {
        shinyFilter = when (shinyFilter) {
            PokemonPriceLimitEntry.SHINY_ANY -> PokemonPriceLimitEntry.SHINY_YES
            PokemonPriceLimitEntry.SHINY_YES -> PokemonPriceLimitEntry.SHINY_NO
            else -> PokemonPriceLimitEntry.SHINY_ANY
        }
        updateShinyButton()
        // 预览模型同步闪光状态
        refreshPreviewModel()
    }

    private fun createPriceField(x: Int, y: Int, placeholderKey: String): TextFieldWidget {
        val field = TextFieldWidget(textRenderer, x, y, 46, 16, Text.literal(""))
        field.setPlaceholder(Text.translatable(placeholderKey).formatted(Formatting.GRAY))
        field.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        field.setChangedListener { dialogError = null }
        addDrawableChild(field)
        return field
    }

    private fun renderPokemonDialogBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogW = 220
        val dialogH = 150
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        context.fill(0, 0, width, height, 0xC0000000.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.price_limit.add_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        // 本地校验错误提示（服务端校验兜底）：画在弹窗下沿外，避免与 +30 起的输入框/控件重叠
        dialogError?.let {
            context.drawCenteredTextWithShadow(textRenderer, it, centerX, dialogY + dialogH + 8, 0xFF5555)
        }

        if (!vListOpen && !formListOpen) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.price_limit.hint"),
                centerX, dialogY + 128, 0xAAAAAA)
        }

        // 精灵预览槽位（dialogY+24 与黑名单一致：槽位 28 高与输入框 16 高垂直中心对齐）
        val slotSize = 28
        val slotX = centerX + 66
        val slotY = dialogY + 24
        context.matrices.push()
        context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
        context.matrices.scale(slotSize / 66f, slotSize / 66f, 1f)
        context.drawTexture(SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
        context.matrices.pop()

        // 物种留空（= 全部精灵）时槽位画 "?"
        if (previewRenderable == null) {
            context.drawCenteredTextWithShadow(textRenderer, "?", slotX + slotSize / 2, slotY + slotSize / 2 - 4, 0xFFFFFF)
        }

        previewRenderable?.let { rp ->
            val matrices = context.matrices
            matrices.push()
            try {
                context.enableScissor(slotX - 1, slotY + 1, slotX + slotSize + 2, slotY + slotSize + 2)
                matrices.translate(slotX + slotSize / 2.0, slotY + 1.0, 0.0)
                matrices.scale(slotSize / 25f * 2.5f, slotSize / 25f * 2.5f, 1f)
                drawProfilePokemon(
                    renderablePokemon = rp,
                    matrixStack = matrices,
                    rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                    state = previewState,
                    partialTicks = 0f,
                    scale = 4.5f
                )
            } catch (_: Exception) {
            } finally {
                context.disableScissor()
                matrices.pop()
            }
        }
    }

    private fun openItemDialog(entry: ItemPriceLimitEntry?) {
        editingPokemon = null
        editingItem = entry
        hideMainControls()
        val centerX = width / 2
        val dialogY = height / 2 - 71

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderItemDialogBackground(context)
            }
        })

        addField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 40, 140, 16, Text.literal(""))
        addField?.setPlaceholder(Text.translatable("cobblemarket.blacklist.item_add_placeholder"))
        addField?.setChangedListener { updateItemPreview(it) }
        addDrawableChild(addField)

        itemSelectButton = NineSliceButton(centerX - 80, dialogY + 58, 140, 14, Text.literal(""), { toggleItemList() })
        itemSelectButton?.visible = false
        addDrawableChild(itemSelectButton)

        minField = createPriceField(centerX - 48, dialogY + 78, "cobblemarket.price_limit.min_placeholder")
        maxField = createPriceField(centerX + 2, dialogY + 78, "cobblemarket.price_limit.max_placeholder")

        addConfirmButton = NineSliceButton(
            centerX - 85, dialogY + 100, 80, 20,
            Text.translatable("cobblemarket.blacklist.add"),
            { confirmItemAdd() }
        )
        addDrawableChild(addConfirmButton)
        addCancelButton = NineSliceButton(
            centerX + 5, dialogY + 100, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeDialog() }
        )
        addDrawableChild(addCancelButton)

        if (entry != null) {
            addField?.text = entry.itemId
            updatePreview(entry.itemId)
            entry.minPrice?.let { minField?.text = it.toString() }
            entry.maxPrice?.let { maxField?.text = it.toString() }
        }
    }

    private fun renderItemDialogBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogW = 220
        val dialogH = 142
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        context.fill(0, 0, width, height, 0xC0000000.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.price_limit.add_item_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)
        // 标题下提示：搜不到的物品可输入真实 id（F3+H 显示高级提示框）
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.gui.item_id_hint").string,
            centerX, dialogY + 26, 0xFFAAAAAA.toInt())

        // 本地校验错误提示：画在弹窗下沿外，避免与 +40 起的物品输入框/控件重叠
        dialogError?.let {
            context.drawCenteredTextWithShadow(textRenderer, it, centerX, dialogY + dialogH + 8, 0xFF5555)
        }

        if (!itemListOpen) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.price_limit.hint"),
                centerX, dialogY + 126, 0xAAAAAA)
        }

        // 物品预览
        previewItemId?.let { itemId ->
            Identifier.tryParse(itemId)?.let { id ->
                val item = Registries.ITEM.get(id)
                if (item != Registries.ITEM.get(Identifier.of("minecraft", "air"))) {
                    context.drawItem(ItemStack(item), centerX + 66, dialogY + 42)
                }
            }
        }
    }

    // ── 精灵：物种解析预览（照搬黑名单） ──

    private fun updatePreview(text: String) {
        dialogError = null
        previewRenderable = null
        previewSpecies = null
        formOptions = listOf()
        formIndex = 0
        formListOpen = false
        rebuildFormList()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            formButton?.visible = false
            return
        }
        val species = if (trimmed.contains(":")) {
            Identifier.tryParse(trimmed)?.let { PokemonSpecies.getByIdentifier(it) }
        } else {
            val byName = try { PokemonSpecies.getByName(trimmed) } catch (_: Exception) { null }
            byName ?: resolveByChineseName(trimmed)
        }
        if (species != null) {
            previewSpecies = species
            // 形态选项语义（与服务端匹配）：
            //   ["*"] = 全部形态（显式全选）
            //   []    = 默认形态（标准形态无 aspect 声明且物种有 forms 时提供）
            //   [x..] = 各 form 的 aspects
            val seen = mutableSetOf<Set<String>>(setOf(PokemonPriceLimitEntry.ALL_FORMS))
            val opts = mutableListOf(FormOption(Text.translatable("cobblemarket.blacklist.form_all").string, setOf(PokemonPriceLimitEntry.ALL_FORMS)))
            val standardAspects = species.standardForm.aspects.toSet()
            if (standardAspects.isNotEmpty()) {
                // 标准形态自带 aspect（爱管侍雄性 = ["male"]）：作为独立选项
                seen.add(standardAspects)
                opts.add(FormOption(formLabel(standardAspects), standardAspects))
            } else if (species.forms.isNotEmpty()) {
                // 标准形态无 aspect（四季鹿春季）：提供"默认形态"选项（aspects 空集）
                seen.add(emptySet())
                opts.add(FormOption(Text.translatable("cobblemarket.blacklist.form_default").string, emptySet()))
            }
            species.forms.forEach { f ->
                val a = f.aspects.toSet()
                if (seen.add(a)) opts.add(FormOption(formLabel(a), a))
            }
            formOptions = opts
            formIndex = 0
            refreshPreviewModel()
            formButton?.visible = formOptions.size > 1
            formButton?.setMessage(formButtonText())
        } else {
            formButton?.visible = false
        }
    }

    /** 按当前形态选择 + 闪光选择重建预览模型：仅闪光时叠加 shiny aspect 渲染闪光形态 */
    private fun refreshPreviewModel() {
        val species = previewSpecies ?: run { previewRenderable = null; return }
        val aspects = formOptions.getOrNull(formIndex)?.aspects
            ?.filter { it != PokemonPriceLimitEntry.ALL_FORMS }?.toMutableSet() ?: mutableSetOf()
        if (shinyFilter == PokemonPriceLimitEntry.SHINY_YES) aspects.add("shiny")
        previewRenderable = RenderablePokemon(species, aspects, ItemStack.EMPTY)
    }

    private fun resolveByChineseName(name: String): Species? {
        return try {
            PokemonSpecies.implemented.firstOrNull { it.translatedName.string == name || it.translatedName.string.contains(name) }
        } catch (_: Exception) {
            null
        }
    }

    /** 物品输入变更：重建匹配列表（照搬物品黑名单） */
    private fun updateItemPreview(text: String) {
        dialogError = null
        matchedItems = resolveMatchingItems(text)
        // 唯一匹配自动选中；多匹配等待用户点选
        selectedItemIndex = if (matchedItems.size == 1) 0 else -1
        itemListOpen = false
        itemListScroll = 0
        rebuildItemList()
        updateItemSelectButton()
        previewItemId = matchedItems.getOrNull(selectedItemIndex) ?: matchedItems.firstOrNull()
    }

    // ── 精灵：V 档选择（照搬形态展开列表模式） ──

    private fun vButtonText() = Text.literal(
        com.shusheng.cobblemarket.util.TextUtil.truncateString(
            "${Text.translatable("cobblemarket.price_limit.v_label").string}: ${vLabel(vOptions[vIndex])}",
            62
        )
    )

    private fun toggleVList() {
        // V 档列表与形态列表互斥展开
        formListOpen = false
        rebuildFormList()
        vListOpen = !vListOpen
        rebuildVList()
    }

    private fun selectV(idx: Int) {
        vIndex = idx
        vListOpen = false
        dialogError = null
        rebuildVList()
        vButton?.setMessage(vButtonText())
    }

    // 展开的 V 档列表：8 项（不限 + 0V~6V）全部可见。展开时隐藏被列表覆盖的价格框、确认/取消按钮与形态按钮
    private fun rebuildVList() {
        vOptionButtons.forEach { remove(it) }
        vOptionButtons.clear()
        formButton?.visible = !vListOpen
        // V 档列表展开时覆盖特训按钮位置（dialogY+62 起），隐藏避免点击拦截
        ruleHtButton?.visible = !vListOpen
        minField?.visible = !vListOpen
        maxField?.visible = !vListOpen
        addConfirmButton?.visible = !vListOpen
        addCancelButton?.visible = !vListOpen
        if (!vListOpen) return
        val centerX = width / 2
        val dialogY = height / 2 - 75
        vOptions.forEachIndexed { idx, v ->
            val label = vLabel(v)
            val btn = NineSliceButton(
                centerX - 80, dialogY + 62 + idx * 14, 140, 14,
                if (idx == vIndex) com.shusheng.cobblemarket.util.TextUtil.selectedText(label) else Text.literal(label),
                { selectV(idx) }
            )
            vOptionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // ── 精灵：形态选择（照搬黑名单） ──

    // aspect 显示名：走本模组翻译表，未收录的 aspect 显示原文
    private fun aspectLabel(aspect: String): String {
        val t = Text.translatable("cobblemarket.aspect.$aspect")
        return if (t.string == "cobblemarket.aspect.$aspect") aspect else t.string
    }

    private fun formLabel(aspects: Set<String>): String = when {
        PokemonPriceLimitEntry.ALL_FORMS in aspects -> Text.translatable("cobblemarket.blacklist.form_all").string
        aspects.isEmpty() -> Text.translatable("cobblemarket.blacklist.form_default").string
        else -> aspects.joinToString("/") { aspectLabel(it) }
    }

    private fun formButtonText() = Text.literal(
        com.shusheng.cobblemarket.util.TextUtil.truncateString(
            "${Text.translatable("cobblemarket.blacklist.form").string}: ${formOptions.getOrNull(formIndex)?.label ?: ""}",
            62
        )
    )

    private fun toggleFormList() {
        // 形态列表与 V 档列表互斥展开
        vListOpen = false
        rebuildVList()
        formListOpen = !formListOpen
        formListScroll = formListScroll.coerceIn(0, maxOf(0, formOptions.size - MAX_FORM_LIST_ROWS))
        rebuildFormList()
    }

    private fun selectForm(idx: Int) {
        formIndex = idx
        formListOpen = false
        rebuildFormList()
        formButton?.setMessage(formButtonText())
        // 预览随所选形态切换：["*"]/[] 渲染标准形态模型，具体 aspects 传入（闪光在 refreshPreviewModel 叠加）
        refreshPreviewModel()
    }

    // 展开的形态列表：每行一个选项，最多 8 行可见，超出滚动。
    // 展开时隐藏被列表覆盖的控件（价格框 + 确认/取消按钮 + V 数按钮）——它们先于形态选项添加，
    // MC 点击遍历按添加顺序，不隐藏的话点击会被它们拦截，形态选项永远点不到。
    private fun rebuildFormList() {
        formOptionButtons.forEach { remove(it) }
        formOptionButtons.clear()
        vButton?.visible = !formListOpen
        // 形态列表展开时覆盖特训按钮位置（dialogY+62 起），隐藏避免点击拦截
        ruleHtButton?.visible = !formListOpen
        minField?.visible = !formListOpen
        maxField?.visible = !formListOpen
        addConfirmButton?.visible = !formListOpen
        addCancelButton?.visible = !formListOpen
        if (!formListOpen) return
        val centerX = width / 2
        val dialogY = height / 2 - 75
        formOptions.drop(formListScroll).take(MAX_FORM_LIST_ROWS).forEachIndexed { i, opt ->
            val idx = formListScroll + i
            // 数据包自创的超长 aspect 名截断，防止溢出按钮
            val label = com.shusheng.cobblemarket.util.TextUtil.truncateString(opt.label, 124)
            val btn = NineSliceButton(
                centerX - 80, dialogY + 62 + i * 14, 140, 14,
                if (idx == formIndex) com.shusheng.cobblemarket.util.TextUtil.selectedText(label) else Text.literal(label),
                { selectForm(idx) }
            )
            formOptionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // ── 物品：匹配列表选择（照搬物品黑名单） ──

    private fun resolveMatchingItems(input: String): List<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.contains(":")) return listOf(trimmed)
        val lower = trimmed.lowercase().replace(" ", "_")
        val result = LinkedHashSet<String>()
        Registries.ITEM.forEach { item ->
            val id = Registries.ITEM.getId(item)
            if (id.path == lower) result.add(id.toString())
        }
        Registries.ITEM.forEach { item ->
            val id = Registries.ITEM.getId(item)
            if (item.name.string == trimmed) result.add(id.toString())
        }
        Registries.ITEM.forEach { item ->
            val id = Registries.ITEM.getId(item)
            if (item.name.string.contains(trimmed)) result.add(id.toString())
        }
        return result.toList()
    }

    private fun updateItemSelectButton() {
        itemSelectButton?.visible = matchedItems.isNotEmpty()
        val label = matchedItems.getOrNull(selectedItemIndex)?.let { itemDisplay(it) }
            ?: if (matchedItems.size > 1)
                Text.translatable("cobblemarket.blacklist.item_matches", matchedItems.size).string
            else ""
        itemSelectButton?.setMessage(Text.literal(
            com.shusheng.cobblemarket.util.TextUtil.truncateString(
                "${Text.translatable("cobblemarket.blacklist.item_label").string}: $label",
                132
            )
        ))
    }

    private fun toggleItemList() {
        itemListOpen = !itemListOpen
        itemListScroll = itemListScroll.coerceIn(0, maxOf(0, matchedItems.size - MAX_ITEM_LIST_ROWS))
        rebuildItemList()
    }

    private fun selectItem(idx: Int) {
        selectedItemIndex = idx
        itemListOpen = false
        dialogError = null
        rebuildItemList()
        updateItemSelectButton()
        previewItemId = matchedItems.getOrNull(idx)
    }

    private fun rebuildItemList() {
        itemOptionButtons.forEach { remove(it) }
        itemOptionButtons.clear()
        minField?.visible = !itemListOpen
        maxField?.visible = !itemListOpen
        addConfirmButton?.visible = !itemListOpen
        addCancelButton?.visible = !itemListOpen
        if (!itemListOpen) return
        val centerX = width / 2
        val dialogY = height / 2 - 65
        matchedItems.drop(itemListScroll).take(MAX_ITEM_LIST_ROWS).forEachIndexed { i, itemId ->
            val idx = itemListScroll + i
            val label = com.shusheng.cobblemarket.util.TextUtil.truncateString(itemDisplay(itemId), 124)
            val btn = NineSliceButton(
                centerX - 80, dialogY + 62 + i * 14, 140, 14,
                if (idx == selectedItemIndex) com.shusheng.cobblemarket.util.TextUtil.selectedText(label) else Text.literal(label),
                { selectItem(idx) }
            )
            itemOptionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // ── 确认 / 关闭 ──

    private fun parsePrice(text: String?): Int? = text?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    /** 本地校验价格合法性；通过返回 (min, max)，失败置 dialogError + fail 音效并返回 null */
    private fun validatePrices(): Pair<Int?, Int?>? {
        val min = parsePrice(minField?.text)
        val max = parsePrice(maxField?.text)
        if (min == null && max == null) {
            dialogError = Text.translatable("cobblemarket.price_limit.need_one").string
            playFailSound()
            return null
        }
        if (min != null && max != null && min > max) {
            dialogError = Text.translatable("cobblemarket.price_limit.invalid_range").string
            playFailSound()
            return null
        }
        return min to max
    }

    private fun confirmPokemonAdd() {
        val prices = validatePrices() ?: return
        val input = addField?.text?.trim() ?: ""
        // 非空时优先发送客户端本地解析出的物种 ID（中文输入在客户端解析）；空 = 全部精灵
        val speciesInput = if (input.isEmpty()) "" else previewSpecies?.resourceIdentifier?.toString() ?: input
        sendToServer(AddPokemonPriceLimitPayload(
            speciesId = speciesInput,
            vCount = vOptions[vIndex],
            shinyFilter = shinyFilter,
            minPrice = prices.first,
            maxPrice = prices.second,
            aspects = formOptions.getOrNull(formIndex)?.aspects?.toList() ?: listOf(PokemonPriceLimitEntry.ALL_FORMS),
            // 编辑模式带原条目：服务端先删旧再插新（改了形态等字段也不会残留旧条目）
            original = editingPokemon,
            htFilter = ruleHtFilter
        ))
        closeDialog()
    }

    private fun confirmItemAdd() {
        // 先检查物品输入（空输入直接返回，不置价格提示），再校验价格
        val input = addField?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val prices = validatePrices() ?: return
        val selected = matchedItems.getOrNull(selectedItemIndex)
        sendToServer(AddItemPriceLimitPayload(
            itemName = selected ?: resolveMatchingItems(input).firstOrNull() ?: input,
            minPrice = prices.first,
            maxPrice = prices.second,
            // 编辑模式带原物品：服务端先删旧再插新（改选了物品也不会残留旧条目）
            originalItemId = editingItem?.itemId
        ))
        closeDialog()
    }

    private fun closeDialog() {
        addField = null
        minField = null
        maxField = null
        addConfirmButton = null
        addCancelButton = null
        dialogError = null
        editingPokemon = null
        editingItem = null
        previewRenderable = null
        previewSpecies = null
        shinyFilter = PokemonPriceLimitEntry.SHINY_ANY
        shinyButton = null
        vIndex = 0
        vButton = null
        vListOpen = false
        vOptionButtons.clear()
        formOptions = listOf()
        formIndex = 0
        formButton = null
        formListOpen = false
        formListScroll = 0
        formOptionButtons.clear()
        ruleHtFilter = PokemonPriceLimitEntry.HT_ANY
        ruleHtButton = null
        previewItemId = null
        matchedItems = listOf()
        selectedItemIndex = -1
        itemListOpen = false
        itemListScroll = 0
        itemOptionButtons.clear()
        itemSelectButton = null
        clearChildren()
        init()
    }

    // ── 数据接收 ──

    fun onPokemonPriceLimitData(payload: PokemonPriceLimitDataPayload) {
        pokemonEntries = payload.entries
        tooltipCacheKey = null
        rebuildFiltered()
        cacheIcons()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, pokemonEntries.size - getMaxVisibleRows()))
        rebuildRowButtons()
    }

    fun onItemPriceLimitData(payload: ItemPriceLimitDataPayload) {
        itemEntries = payload.entries
        tooltipCacheKey = null
        rebuildItemIconStacks()
        rebuildFiltered()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, itemEntries.size - getMaxVisibleRows()))
        rebuildRowButtons()
    }

    /** 一次性解析全部物品图标栈（render 每帧只读缓存，见 itemIconStacks 注释） */
    private fun rebuildItemIconStacks() {
        itemIconStacks.clear()
        itemEntries.forEach { entry ->
            Identifier.tryParse(entry.itemId)?.let { id -> itemIconStacks[entry.itemId] = ItemStack(Registries.ITEM.get(id)) }
        }
    }

    private fun cacheIcons() {
        iconData.clear()
        pokemonEntries.forEachIndexed { index, entry ->
            val id = Identifier.tryParse(entry.speciesId) ?: return@forEachIndexed
            val species = PokemonSpecies.getByIdentifier(id) ?: return@forEachIndexed
            // 条目带形态时按形态渲染（雌性爱管侍显示雌性模型），空 aspects = 标准形态；
            // 仅闪光条目叠加 shiny aspect 渲染闪光配色（与对话框预览 refreshPreviewModel 一致）
            val aspects = entry.aspects.toMutableSet()
            if (entry.shinyFilter == PokemonPriceLimitEntry.SHINY_YES) aspects.add("shiny")
            iconData[index] = IconData(com.shusheng.cobblemarket.util.SpeciesText.displayName(species), RenderablePokemon(species, aspects, ItemStack.EMPTY), FloatingState())
        }
    }

    private fun renderPokemonIcon(context: DrawContext, index: Int, x: Int, y: Int, size: Int, dark: Boolean = false) {
        val data = iconData[index] ?: return
        val matrices = context.matrices
        matrices.push()
        try {
            context.enableScissor(x - 1, y + 1, x + size + 2, y + size + 2)
            matrices.translate(x + size / 2.0, y + 1.0, 0.0)
            matrices.scale(size / 25f * 2.5f, size / 25f * 2.5f, 1f)
            drawProfilePokemon(
                renderablePokemon = data.renderable,
                matrixStack = matrices,
                rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                state = data.state,
                partialTicks = 0f,
                scale = 4.5f,
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

    // ── 搜索过滤 ──

    private fun pokemonFilterText(entry: PokemonPriceLimitEntry): String {
        val name = pokemonName(entry.speciesId)
        return if (entry.vCount >= 0) "$name ${entry.vCount}V" else name
    }

    private fun filteredPokemon(): List<PokemonPriceLimitEntry> = filteredPokemonCache

    /** 数据/搜索/筛选变化时重建过滤缓存（render 每帧只读）；
     *  索引/行显示字符串随过滤一并重建，行渲染不再每帧 indexOf / 拼文本 */
    private fun rebuildFiltered() {
        val query = searchField?.text?.trim()?.takeIf { it.isNotEmpty() }
        filteredPokemonIndexed = pokemonEntries.mapIndexedNotNull { index, entry ->
            if ((query == null || pokemonFilterText(entry).contains(query, ignoreCase = true) ||
                    entry.speciesId.contains(query, ignoreCase = true)) &&
                // 特训筛选：0 = 不限，2 = 不含特训
                (listHtFilter == PokemonPriceLimitEntry.HT_ANY || entry.htFilter == listHtFilter)
            ) IndexedValue(index, entry) else null
        }
        filteredPokemonCache = filteredPokemonIndexed.map { it.value }
        pokemonRowTexts = filteredPokemonCache.map { entry ->
            val vText = if (entry.vCount >= 0) " · ${entry.vCount}V" else ""
            "${pokemonName(entry.speciesId)}$vText · ${formLabel(entry.aspects.toSet())} · ${priceText(entry.minPrice, entry.maxPrice)}"
        }
        filteredItemsCache = if (query == null) itemEntries else itemEntries.filter { entry ->
            itemDisplay(entry.itemId).contains(query, ignoreCase = true) ||
                entry.itemId.contains(query, ignoreCase = true)
        }
        itemRowTexts = filteredItemsCache.map { "${itemDisplay(it.itemId)} · ${priceText(it.minPrice, it.maxPrice)}" }
    }

    private fun htFilterButtonText(): Text = Text.translatable(
        if (listHtFilter == PokemonPriceLimitEntry.HT_ANY) "cobblemarket.gui.filter_ht_any" else "cobblemarket.gui.filter_ht_off"
    )

    private fun toggleListHtFilter() {
        // 两态互切：不限(0) ↔ 不含特训(2)
        listHtFilter = if (listHtFilter == PokemonPriceLimitEntry.HT_ANY) PokemonPriceLimitEntry.HT_NONE else PokemonPriceLimitEntry.HT_ANY
        htFilterButton?.setMessage(htFilterButtonText())
        htFilterButton?.textColor = if (listHtFilter != 0) GOLD_COLOR else 0xFFFFFF
        rebuildFiltered()
        scrollOffset = 0
        hoveredRow = -1
        rebuildRowButtons()
    }

    private fun filteredItems(): List<ItemPriceLimitEntry> = filteredItemsCache

    private fun displayCount(): Int = if (currentTab == 0) filteredPokemon().size else filteredItems().size

    // ── 行按钮（编辑/删除） ──

    private fun rebuildRowButtons() {
        removeButtons.forEach { remove(it) }
        removeButtons.clear()
        editButtons.forEach { remove(it) }
        editButtons.clear()
        val leftX = width / 2 - panelWidth / 2
        val startY = getListStartY()
        if (currentTab == 0) {
            filteredPokemon().drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, entry ->
                val y = startY + i * rowHeight
                val editBtn = NineSliceButton(
                    leftX + panelWidth - 98, y + 4, 44, 16,
                    Text.translatable("cobblemarket.price_limit.edit"),
                    { openPokemonDialog(entry) }
                )
                editBtn.visible = addField == null
                editButtons.add(editBtn)
                addDrawableChild(editBtn)
                val delBtn = NineSliceButton(
                    leftX + panelWidth - 50, y + 4, 44, 16,
                    Text.translatable("cobblemarket.price_limit.remove"),
                    { sendToServer(RemovePokemonPriceLimitPayload(entry.speciesId, entry.vCount, entry.shinyFilter, entry.aspects, entry.htFilter)) }
                )
                delBtn.visible = addField == null
                removeButtons.add(delBtn)
                addDrawableChild(delBtn)
            }
        } else {
            filteredItems().drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, entry ->
                val y = startY + i * rowHeight
                val editBtn = NineSliceButton(
                    leftX + panelWidth - 98, y + 4, 44, 16,
                    Text.translatable("cobblemarket.price_limit.edit"),
                    { openItemDialog(entry) }
                )
                editBtn.visible = addField == null
                editButtons.add(editBtn)
                addDrawableChild(editBtn)
                val delBtn = NineSliceButton(
                    leftX + panelWidth - 50, y + 4, 44, 16,
                    Text.translatable("cobblemarket.price_limit.remove"),
                    { sendToServer(RemoveItemPriceLimitPayload(entry.itemId)) }
                )
                delBtn.visible = addField == null
                removeButtons.add(delBtn)
                addDrawableChild(delBtn)
            }
        }
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val panelLeft = width / 2 - 160
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

        val leftX = width / 2 - panelWidth / 2
        val startY = getListStartY()
        val visibleRows = getMaxVisibleRows()
        val listAreaBottom = startY + visibleRows * rowHeight
        val count = displayCount()

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

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        if (addField != null) {
            return
        }

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.op.price_limit").formatted(Formatting.GOLD),
            centerX, 20, 0xFFFFFF)

        // 物品 tab：储存类模组容器（自定义存储格式）无法校验内容，兜底=把容器物品本身加黑名单
        if (currentTab == 1) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.op.storage_container_hint").formatted(Formatting.GRAY),
                centerX, 30, 0xFFFFFF)
        }

        val startY = getListStartY()
        // 分割线贴搜索行底部（y=66）：tab 行让出 2px 后搜索框在 50~66，线画 66~67 不重叠
        context.fill(leftX, startY - 2, leftX + panelWidth, startY - 1, 0xFF555555.toInt())

        if (displayCount() == 0) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.price_limit.empty").formatted(Formatting.GRAY),
                centerX, startY + 50, 0xFFFFFF)
        }

        if (currentTab == 0) {
            val displayList = filteredPokemon()
            val indexedList = filteredPokemonIndexed
            val rowTexts = pokemonRowTexts
            displayList.drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, entry ->
                val y = startY + i * rowHeight
                val origIndex = indexedList[scrollOffset + i].index
                // 槽背景（GUI 层，弹窗遮罩自动压暗，照常渲染）；3D 精灵弹窗打开时颜色压暗
                val slotX = leftX + 2
                val slotY = y + 2
                context.matrices.push()
                context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
                context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
                context.drawTexture(SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
                context.matrices.pop()
                if (iconData.containsKey(origIndex)) {
                    // 弹窗打开时不渲染 3D（模型层在衬底之上，压暗仍会浮在弹窗上）
                    if (addField == null) renderPokemonIcon(context, origIndex, slotX, slotY, iconSize)
                } else {
                    // 物种留空（全部精灵）：槽位画 "?"
                    context.drawCenteredTextWithShadow(textRenderer, "?", slotX + iconSize / 2, slotY + iconSize / 2 - 4, 0xFFFFFF)
                }
                // 闪光标记用符号：金色 ★ = 仅闪光，白色 ☆ = 仅非闪光（与按钮一致）
                val line = rowTexts[scrollOffset + i]
                var cursor = leftX + 28
                when (entry.shinyFilter) {
                    PokemonPriceLimitEntry.SHINY_YES -> {
                        context.drawTextWithShadow(textRenderer, "★ ", cursor, y + 7, GOLD_COLOR)
                        cursor += textRenderer.getWidth("★ ")
                    }
                    PokemonPriceLimitEntry.SHINY_NO -> {
                        context.drawTextWithShadow(textRenderer, "☆ ", cursor, y + 7, 0xFFFFFF)
                        cursor += textRenderer.getWidth("☆ ")
                    }
                }
                context.drawTextWithShadow(textRenderer,
                    com.shusheng.cobblemarket.util.TextUtil.truncateString(line, 170 - (cursor - (leftX + 28))),
                    cursor, y + 7, 0xFFFFFF)
            }
            if (hoveredRow >= 0) {
                val actualIdx = scrollOffset + hoveredRow
                if (actualIdx in displayList.indices) {
                    renderPokemonTooltip(context, displayList[actualIdx], mouseX, mouseY)
                }
            }
        } else {
            val displayList = filteredItems()
            val rowTexts = itemRowTexts
            displayList.drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, entry ->
                val y = startY + i * rowHeight
                // 弹窗打开时行内物品图标不渲染（drawItem 硬编码 z 抬高，会刺穿弹窗遮罩）
                if (addField == null) {
                    itemIconStacks[entry.itemId]?.let { context.drawItem(it, leftX + 4, y + 4) }
                }
                context.drawTextWithShadow(textRenderer,
                    com.shusheng.cobblemarket.util.TextUtil.truncateString(rowTexts[scrollOffset + i], 170),
                    leftX + 24, y + 7, 0xFFFFFF)
            }
            if (hoveredRow >= 0) {
                val actualIdx = scrollOffset + hoveredRow
                if (actualIdx in displayList.indices) {
                    renderItemTooltip(context, displayList[actualIdx], mouseX, mouseY)
                }
            }
        }

        if (displayCount() > getMaxVisibleRows()) {
            context.drawCenteredTextWithShadow(textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + getMaxVisibleRows(), displayCount())} / ${displayCount()}",
                centerX, height - 49, 0x888888)
        }
    }

    private fun renderPokemonTooltip(context: DrawContext, entry: PokemonPriceLimitEntry, mouseX: Int, mouseY: Int) {
        // 文本行缓存：悬停同一行时内容不变，只在悬停目标变化时重建（见 tooltipCacheKey 注释）
        if (tooltipCacheKey != entry) {
            tooltipCacheKey = entry
            tooltipCacheLines = buildPokemonTooltipLines(entry)
        }
        drawTooltip(context, tooltipCacheLines, mouseX, mouseY)
    }

    private fun buildPokemonTooltipLines(entry: PokemonPriceLimitEntry): List<String> {
        val lines = mutableListOf(pokemonName(entry.speciesId))
        lines.add(Text.translatable("cobblemarket.price_limit.v_label").string + ": " + vLabel(entry.vCount))
        lines.add(shinyLabel(entry.shinyFilter))
        lines.add(formLabel(entry.aspects.toSet()))
        lines.add(htLabel(entry.htFilter))
        lines.add(priceText(entry.minPrice, entry.maxPrice))
        return lines
    }

    // 特训维度标签（tooltip 用）：不限 / 不含特训
    private fun htLabel(filter: Int): String = Text.translatable(
        if (filter == PokemonPriceLimitEntry.HT_ANY) "cobblemarket.gui.filter_ht_any" else "cobblemarket.gui.filter_ht_off"
    ).string

    private fun ruleHtButtonText(): Text = Text.translatable(
        if (ruleHtFilter == PokemonPriceLimitEntry.HT_ANY) "cobblemarket.gui.filter_ht_any" else "cobblemarket.gui.filter_ht_off"
    )

    private fun toggleRuleHtFilter() {
        // 两态互切：不限(0) ↔ 不含特训(2)
        ruleHtFilter = if (ruleHtFilter == PokemonPriceLimitEntry.HT_ANY) PokemonPriceLimitEntry.HT_NONE else PokemonPriceLimitEntry.HT_ANY
        ruleHtButton?.setMessage(ruleHtButtonText())
    }

    private fun renderItemTooltip(context: DrawContext, entry: ItemPriceLimitEntry, mouseX: Int, mouseY: Int) {
        if (tooltipCacheKey != entry) {
            tooltipCacheKey = entry
            tooltipCacheLines = mutableListOf(itemDisplay(entry.itemId), entry.itemId, priceText(entry.minPrice, entry.maxPrice))
        }
        drawTooltip(context, tooltipCacheLines, mouseX, mouseY)
    }

    private fun drawTooltip(context: DrawContext, lines: List<String>, mouseX: Int, mouseY: Int) {
        var maxWidth = 0
        lines.forEach { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it)) }

        val padding = 4
        val tx = minOf(mouseX + 12, width - maxWidth - 12)
        val tooltipHeight = lines.size * 10 + padding
        val tyAbove = mouseY - tooltipHeight - 4
        val ty = if (tyAbove <= 0) minOf(mouseY + 12, height - tooltipHeight) else tyAbove

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, lines.size * 10 + 2 * padding, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, line ->
            context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, 0xFFFFFF)
        }
        context.matrices.pop()
    }

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int, sliceH: Int = 16) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    // ── 交互 ──

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        // 形态列表展开且超过可见行数时，滚轮滚动形态列表（照黑名单）
        if (formListOpen && formOptions.size > MAX_FORM_LIST_ROWS) {
            formListScroll = (formListScroll - verticalAmount.toInt())
                .coerceIn(0, formOptions.size - MAX_FORM_LIST_ROWS)
            rebuildFormList()
            return true
        }
        // V 档列表展开时滚轮无操作（8 项全部可见）
        if (itemListOpen && matchedItems.size > MAX_ITEM_LIST_ROWS) {
            itemListScroll = (itemListScroll - verticalAmount.toInt())
                .coerceIn(0, matchedItems.size - MAX_ITEM_LIST_ROWS)
            rebuildItemList()
            return true
        }
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        rebuildRowButtons()
        return true
    }

    private fun isInputFieldFocused() = focused?.let { f ->
        f === searchField || f === addField || f === minField || f === maxField
    } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true ||
        addField?.isMouseOver(mouseX, mouseY) == true ||
        minField?.isMouseOver(mouseX, mouseY) == true ||
        maxField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        return result
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val wasOpen = addField != null
        // 对话框只在当前 tab 打开（打开后 tab 按钮被隐藏，不会切换）
        val wasPokemonDialog = currentTab == 0
        val name = addField?.text ?: ""
        val min = minField?.text ?: ""
        val max = maxField?.text ?: ""
        val savedVIndex = vIndex
        val savedSelectedItemIndex = selectedItemIndex
        val savedShinyFilter = shinyFilter
        val savedFormIndex = formIndex
        val savedRuleHtFilter = ruleHtFilter
        super.resize(client, width, height)
        if (wasOpen) {
            addField = null
            if (wasPokemonDialog) {
                openPokemonDialog(editingPokemon)
                addField?.text = name
                vIndex = savedVIndex
                vButton?.setMessage(vButtonText())
                shinyFilter = savedShinyFilter
                updateShinyButton()
                ruleHtFilter = savedRuleHtFilter
                ruleHtButton?.setMessage(ruleHtButtonText())
                // 文本恢复已触发 updatePreview 重建形态选项，这里恢复索引与预览；形态列表保持收起
                formListOpen = false
                rebuildFormList()
                if (formOptions.size > 1) {
                    formIndex = savedFormIndex.coerceIn(0, formOptions.size - 1)
                    formButton?.visible = true
                    formButton?.setMessage(formButtonText())
                }
                refreshPreviewModel()
                minField?.text = min
                maxField?.text = max
            } else {
                openItemDialog(editingItem)
                addField?.text = name
                // 文本恢复已触发 updatePreview 重建匹配列表，这里恢复选中项与预览
                if (savedSelectedItemIndex in matchedItems.indices) {
                    selectedItemIndex = savedSelectedItemIndex
                    updateItemSelectButton()
                    previewItemId = matchedItems.getOrNull(selectedItemIndex)
                }
                minField?.text = min
                maxField?.text = max
            }
        }
    }

    override fun shouldPause() = false

    private companion object {
        val SLOT_TEXTURE = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
    }
}
