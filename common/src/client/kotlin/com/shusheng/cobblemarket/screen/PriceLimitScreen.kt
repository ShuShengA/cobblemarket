package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.clampTooltipRows
import com.shusheng.cobblemarket.client.TooltipPlacement
import com.shusheng.cobblemarket.client.activeCurrencyUnit
import com.shusheng.cobblemarket.client.formatPrice
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.client.playFailSound

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.shusheng.cobblemarket.client.icon.PreviewStateHolder
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.PokemonHoverPreview
import com.shusheng.cobblemarket.client.icon.PokemonIconData
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
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
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import com.shusheng.cobblemarket.client.drawItemWithBar

/**
 * 价格限制管理：精灵 / 物品 两个 tab。
 * 精灵规则：物种（留空 = 全部精灵）+ V 档（-1 = 不限，0~6 = 恰好 N 个 31）+ 价格范围。
 * 物品规则：物品 + 价格范围。列表骨架、对话框、展开列表交互均照搬黑名单界面。
 */
class PriceLimitScreen : Screen(Text.translatable("cobblemarket.op.price_limit")) {

    private val panelWidth = 296
    private val rowHeight = 24
    private val MAX_ITEM_LIST_ROWS = 8

    /**
     * 展开列表的**起始行**（相对 dialogY）—— 接着搜索框下面排，把上面那几行按钮整个占掉。
     * ⚠ 「新开了个界面」与「原地把按钮藏起来」的唯一区别就在这个起点（2026-09-27 用户指出）。
     */
    private val LIST_TOP = 48

    /** 列表最多几行：从 [LIST_TOP] 到底部按钮上方能排几行（(154-48)/16 = 6.6） */
    private val MAX_FORM_LIST_ROWS = 6

    // ── 行按钮几何（**建按钮与悬停窗避让共用一处定义**，别各写一份）──
    private val rowEditBtnInset = 98
    private val rowRemoveBtnInset = 50
    private val rowBtnW = 44
    private val rowBtnH = 16
    private val rowBtnDy = 4

    /** 第 [row] 行「编辑 + 删除」两枚按钮的**并集**矩形 —— 与 `rebuildRowButtons` 同源 */
    private fun rowButtonsRect(row: Int) = TooltipPlacement.Rect(
        width / 2 - panelWidth / 2 + panelWidth - rowEditBtnInset,
        getListStartY() + row * rowHeight + rowBtnDy,
        rowEditBtnInset - rowRemoveBtnInset + rowBtnW, rowBtnH
    )

    /** V 档 / 形态两个并排选择按钮的宽度；文字截断阈值按此反算（= 宽 − 8，两侧各留 4px） */
    private val selectBtnW = 68

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
    // 物品图标栈缓存（条目 → 解析栈）：行内每帧 tryParse + ItemStack 是分配热点，数据到达时构建；
    // 带组件快照的条目重建完整物品（真实组件渲染）
    private val itemIconStacks = mutableMapOf<ItemPriceLimitEntry, ItemStack>()
    // tooltip 行缓存：内容只取决于条目，悬停同一行时每帧重建是悬停掉帧主因。
    // 富文本行 = Text + 行色：首行精灵名要走主属性色，纯 String 带不了颜色（见 buildPokemonTooltipLines）
    private var tooltipCacheKey: Any? = null
    private var tooltipCacheLines: List<Pair<Text, Int>> = emptyList()
    // 物品行 tooltip 富文本缓存（Text 保留词条自带颜色，照物品市场；Shift 展开按需构建）
    private var itemTooltipCacheKey: Any? = null
    private var itemTooltipLines: List<Pair<Text, Int>> = emptyList()
    private var itemTooltipAdvancedLines: List<Pair<Text, Int>>? = null
    private var itemTooltipAdvancedType: TooltipType? = null
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

    // ── 「全部」开关 + 「变体」列表（2026-09-27 与黑名单同一套三档：全部 / 形态 / 变体）──
    /** 「全部」勾上 = 形态与变体一起限（下面两个按钮置灰、先前选的也不参与判定） */
    // ⚠ **默认勾选**（用户 2026-09-27 定）：什么都不动直接提交时，限的应该是这只精灵的
    //   **全部形态与变体**（老行为就是全封形态，加了变体维度后要连它一起）
    private var allForms = true
    private var allFormsButton: NineSliceButton? = null
    private var variantButton: NineSliceButton? = null
    private var variantListOpen = false
    private var variantListScroll = 0
    private val variantOptionButtons = mutableListOf<NineSliceButton>()
    /** 已勾选的变体 aspect（**多选**：限价的是"哪些要限"） */
    private var selectedVariants = setOf<String>()

    private fun currentVariantGroups(): List<com.shusheng.cobblemarket.util.FeatureCandidates> =
        previewSpecies?.resourceIdentifier?.toString()
            ?.let { com.shusheng.cobblemarket.client.PointMarketCache.featureAspects[it] }.orEmpty()

    /**
     * 该物种**可以列出来**的变体（扁平）—— 走共用过滤器（去掉已在形态里的、去掉该物种没模型的）。
     * ⚠ 列表渲染与滚轮上限**都用这一个**。
     */
    private fun currentVariantAspects(): List<String> =
        com.shusheng.cobblemarket.client.VariantCandidates.aspectList(previewSpecies, currentVariantGroups())

    /** 「全部」：勾上 = 形态与变体一起限（下面两个按钮置灰、先选的也不参与判定） */
    private fun toggleAllForms() {
        allForms = !allForms
        updateAllFormsButton()
        formButton?.dimmed = allForms
        variantButton?.dimmed = allForms
        if (allForms) {
            formListOpen = false
            vListOpen = false
            variantListOpen = false
        }
        updateVariantButton()
    }

    /** ⚠ 选中态设在这里（唯一落点）—— toggle 与编辑预填都走它，分散设迟早漏一处 */
    private fun updateAllFormsButton() {
        allFormsButton?.setMessage(Text.translatable("cobblemarket.blacklist.all"))
        allFormsButton?.selectedVisual = allForms
        // 两个子按钮的置灰也收在这儿：创建时默认就是勾选的，那时也得灰着
        formButton?.dimmed = allForms
        variantButton?.dimmed = allForms
    }

    private fun updateVariantButton() {
        variantButton?.dimmed = allForms
        // 一个没勾写「不限」而不是「0 项」—— "0 项"看着像这儿没东西可选（同黑名单）
        variantButton?.setMessage(
            if (selectedVariants.isEmpty()) Text.translatable("cobblemarket.blacklist.variant")
            else Text.translatable("cobblemarket.blacklist.variant_count", selectedVariants.size.toString())
        )
        // ⚠ **悬停窗挂在这枚按钮上**：按钮只有数量，具体选了哪几个得悬停看（同 RP 商城那枚「变体」按钮）
        variantButton?.setTooltip(
            if (selectedVariants.isEmpty()) null
            else net.minecraft.client.gui.tooltip.Tooltip.of(
                // ⚠ **带维度名**；⚠⚠ 候选表没打开过列表就没有 ⇒ 走 [variantLinesOf] 的退化路径
                //   （查不到维度名就只显示值），不然整段空白
                Text.literal(variantLinesOf(selectedVariants.toList()).joinToString("\n"))
            )
        )
    }

    private fun toggleVariantList() {
        // 同上：勾着「全部」时点了给声失败音
        if (allForms) return playFailSound()
        variantListOpen = !variantListOpen
        variantListScroll = 0
        if (variantListOpen) {
            // 候选只能服务端给；打开时问一次（服务端有节流兜底）
            sendToServer(com.shusheng.cobblemarket.network.RequestSpeciesFeatureAspectsPayload())
            // 三个列表**互斥**（同时开着会互相压、点击也打架）
            formListOpen = false
            vListOpen = false
        }
        // ⚠ 顺序：先让另外两套把控件恢复成可见，再让本套按自己的状态重新隐藏
        rebuildVList()
        rebuildFormList()
        rebuildVariantList()
    }

    /**
     * 展开的变体列表：**多选**（限价的是"哪些要限"，同一维度可以勾好几个）。
     * ⚠ 展开时隐藏被它盖住的控件 —— 同 [rebuildFormList] 的理由，不隐藏会透出来、还会拦点击。
     */
    /** 上次建列表时用的候选表版本（见 [PointMarketCache.featureAspectsVersion]） */
    private var variantAspectsSeen = -1

    /** 列表展开时底部那两枚：左「清空」（一键取消所有变体选择）右「返回」 */
    private var listBackButton: NineSliceButton? = null
    private var listClearButton: NineSliceButton? = null

    /** 清空**所有变体选择** —— 只清变体，形态那些归形态按钮管，不动它 */
    private fun clearSelectedVariants() {
        selectedVariants = emptySet()
        rebuildVariantList()
        updateVariantButton()
    }

    /** 关掉当前展开的列表（「返回」按钮用）—— 三个列表一起收，反正同时只可能开一个 */
    private fun closeAnyList() {
        vListOpen = false
        formListOpen = false
        variantListOpen = false
        rebuildVList()
        rebuildFormList()
        rebuildVariantList()
    }

    /**
     * 候选表**异步**回来（打开列表时才请求）⇒ 到了就重建一次（不重建会停在空列表上，
     * 同黑名单那边，用户 2026-09-27 实测报的）
     */
    override fun tick() {
        super.tick()
        val version = com.shusheng.cobblemarket.client.PointMarketCache.featureAspectsVersion
        if (variantListOpen && variantAspectsSeen != version) {
            variantAspectsSeen = version
            rebuildVariantList()
        }
    }

    /**
     * 变体列表的**滚动条**（候选装不下时才有）。
     *
     * ⚠ 列表行是**控件**，滚动条没法跟着走 ⇒ 只能自绘，且要画在主 `render` 里（背景层会被控件盖住）。
     * ⚠ 判据用 [currentVariantRows]（标题行也占一行），与列表渲染同一口径。
     */
    private fun renderVariantScrollbar(context: DrawContext) {
        if (!variantListOpen) return
        val total = currentVariantRows().size
        if (total <= MAX_FORM_LIST_ROWS) return
        val centerX = width / 2
        val top = height / 2 - 92 + LIST_TOP
        val trackH = MAX_FORM_LIST_ROWS * 16
        val barX = centerX + 62
        context.fill(barX, top, barX + 1, top + trackH, 0x40FFFFFF)
        val thumbH = (trackH * MAX_FORM_LIST_ROWS / total).coerceAtLeast(6)
        val maxScroll = (total - MAX_FORM_LIST_ROWS).coerceAtLeast(1)
        val thumbY = top + (trackH - thumbH) * variantListScroll / maxScroll
        context.fill(barX - 1, thumbY, barX + 2, thumbY + thumbH, 0xFFFFFFFF.toInt())
    }

    private fun rebuildVariantList() {
        variantOptionButtons.forEach { remove(it) }
        variantOptionButtons.clear()
        addConfirmButton?.visible = !variantListOpen
        addCancelButton?.visible = !variantListOpen
        listBackButton?.visible = vListOpen || formListOpen || variantListOpen
        // 「清空」只在**变体列表**开着时显示（另外两个列表没有"多选"这回事）
        listClearButton?.visible = variantListOpen
        ruleHtButton?.visible = !variantListOpen
        minField?.visible = !variantListOpen
        maxField?.visible = !variantListOpen
        allFormsButton?.visible = !variantListOpen
        formButton?.visible = !variantListOpen
        vButton?.visible = !variantListOpen
        // ⚠ 变体按钮**自己**也要隐藏（它在 dialogY+66，正是列表起排的那一行）
        variantButton?.visible = !variantListOpen
        if (!variantListOpen) return
        val centerX = width / 2
        val dialogY = height / 2 - 92
        val rows = currentVariantRows()
        rows.drop(variantListScroll).take(MAX_FORM_LIST_ROWS).forEachIndexed { i, row ->
            val isHeader = row.second == null
            val label = if (isHeader) {
                // 分节标题：**置灰的按钮**当占位（这儿的列表是控件，画不了不可点的自绘行）
                "── ${com.shusheng.cobblemarket.util.SpeciesText.featureGroupLabel(row.first)} ──"
            } else {
                com.shusheng.cobblemarket.util.TextUtil.truncateString(
                    com.shusheng.cobblemarket.util.SpeciesText.featureValueLabel(row.second!!), 124
                )
            }
            val btn = NineSliceButton(
                centerX - 80, dialogY + LIST_TOP + i * 16, 140, 14,
                Text.literal(label),
                { if (!isHeader) toggleVariant(row.second!!) },
                // ⚠ `texH = 120` = **三段贴图**，第三段是选中态
                texH = 120
                // ⚠ **列表行不加悬停**：文字就在眼前；该做悬停的是上面那枚「变体」按钮 —— 见 [updateVariantButton]
            )
            // **选中态**（用按钮高亮，不要 ● 前缀）
            btn.selectedVisual = !isHeader && row.second in selectedVariants
            btn.active = !isHeader
            variantOptionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    /**
     * 变体列表要画的**每一行**（`维度名 → 值`，值为 null = 该维度的标题行）。
     * ⚠ 列表渲染与滚轮上限**都用这一个**。
     */
    private fun currentVariantRows(): List<Pair<String, String?>> {
        val rows = mutableListOf<Pair<String, String?>>()
        com.shusheng.cobblemarket.client.VariantCandidates
            .filteredGroups(previewSpecies, currentVariantGroups())
            .forEach { g ->
                rows.add(g.key to null)
                g.aspects.forEach { a -> rows.add(g.key to a) }
            }
        return rows
    }

    /**
     * 勾选一个变体（**不关列表**）。
     *
     * ⚠ **组内单选、组间共存**（用户 2026-09-27 定，与 RP 商城的变体列表同一套语义）——
     *   同一维度只能选一个（点另一个换掉它），不同维度可共存。
     */
    private fun toggleVariant(aspect: String) {
        // **多选**（用户 2026-09-27 确认）：限价是"哪些要限"，同一维度可以勾好几个
        selectedVariants = if (aspect in selectedVariants) selectedVariants - aspect
        else selectedVariants + aspect
        rebuildVariantList()
        updateVariantButton()
    }

    // ── 对话框公共状态 ──
    private var addField: TextFieldWidget? = null
    private var minField: TextFieldWidget? = null
    private var maxField: TextFieldWidget? = null
    private var addConfirmButton: NineSliceButton? = null
    private var addCancelButton: NineSliceButton? = null
    // 本地提示（画在弹窗下沿外；错误红字、信息绿字；输入变更时清除）
    private var dialogError: String? = null
    private var dialogErrorColor = 0xFF5555

    // ── 对话框状态（精灵） ──
    private var editingPokemon: PokemonPriceLimitEntry? = null
    private var previewRenderable: RenderablePokemon? = null
    private val previewState = PreviewStateHolder()
    private var previewSpecies: Species? = null
    /**
     * 物种候选 + 当前选中下标（**多候选时预览槽位下方出现左右箭头切换**）。
     *
     * 中文物种名互为子串的情形很多（「鬼斯」是「鬼斯通」的前缀），解析必须**精确优先**、
     * 其余候选交回玩家挑 —— 只取第一个会把玩家输入的名字静默换成别的物种，而这是在配限价规则，
     * 配错物种玩家极难察觉。候选顺序由 `SpeciesText.candidatesByNameOrId` 保证（精确的排最前）。
     */
    private var speciesCandidates = listOf<Species>()
    private var speciesIndex = 0
    private var speciesPrevButton: NineSliceButton? = null
    private var speciesNextButton: NineSliceButton? = null
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
    private var matchedItems = listOf<com.shusheng.cobblemarket.client.ItemCandidate>()
    private var selectedItemIndex = -1
    private var itemListOpen = false
    private var itemListScroll = 0
    private val itemOptionButtons = mutableListOf<NineSliceButton>()
    private var itemSelectButton: NineSliceButton? = null
    private var heldAddButton: NineSliceButton? = null
    // 手持添加模式：小手图标选中态（红底），点「添加」时提交手持物品条目
    private var heldAddMode = false
    private var previewCandidate: com.shusheng.cobblemarket.client.ItemCandidate? = null

    private val iconData = mutableMapOf<Int, PokemonIconData>()
    private val iconSize = 20

    private fun getListStartY() = 86
    private fun getMaxVisibleRows() = maxOf(0, (height - getListStartY() - 48) / rowHeight)

    // ── 通用文本 ──

    private fun vLabel(vCount: Int): String =
        if (vCount >= 0) "${vCount}V" else Text.translatable("cobblemarket.price_limit.v_any").string

    private fun priceText(min: Int?, max: Int?, unit: String): String = when {
        min != null && max != null -> "${formatPrice(min)} ~ ${formatPrice(max)} $unit"
        min != null -> "≥ ${formatPrice(min)} $unit"
        max != null -> "≤ ${formatPrice(max)} $unit"
        else -> Text.translatable("cobblemarket.price_limit.unlimited").string
    }

    private fun pokemonName(speciesId: String): String {
        if (speciesId.isEmpty()) return Text.translatable("cobblemarket.price_limit.all_pokemon").string
        val species = Identifier.tryParse(speciesId)?.let { PokemonSpecies.getByIdentifier(it) }
        return species?.let { com.shusheng.cobblemarket.util.SpeciesText.displayName(it) } ?: speciesId
    }

    private fun itemDisplay(itemId: String): String =
        com.shusheng.cobblemarket.client.ItemCandidateResolver.displayNameOf(itemId)

    override fun init() {
        super.init()
        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        val backBtn = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(AdminScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
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

        // 宽 50（2026-09-21 用户拍板，与其他界面统一）；x 由 relayoutSearchRow() 按右侧按钮可见性重排
        val addBtn = NineSliceButton(
            leftX + panelWidth - 114, 62, 50, 16,
            Text.literal(""), { openAddDialog() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/choose.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            // 纯图标按钮一律给悬停提示（同排搜索框右侧，没提示玩家不知道小手是干什么的）
            tooltip = Text.translatable("cobblemarket.gui.limit_add")
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

        relayoutSearchRow()

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
        relayoutSearchRow()
        requestCurrentTabData()
    }

    /** 搜索行动态排布：特训筛选按钮可见时添加按钮在其左，否则添加按钮贴右；搜索框填满剩余空间 */
    private fun relayoutSearchRow() {
        val leftX = width / 2 - panelWidth / 2
        val rightVisible = htFilterButton?.visible == true
        // 添加按钮宽 50（见构造处）：特训筛选（60 宽）可见时它排在左边、间距 4px，
        // 否则自己贴右（右缘保持 panelWidth-2）。⚠ 这里只改 x，**宽度是在构造处定的**，两边要一起改
        val addX = if (rightVisible) leftX + panelWidth - 60 - 4 - 50 else leftX + panelWidth - 52
        addButton?.x = addX
        searchField?.setWidth(addX - 4 - (leftX + 2))
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
        // ⚠ **每次开窗都要重置**（都是字段，不重置会把上一条的状态带过来）：
        //   `allForms`（编辑过"只按形态"后会带 false）+ `selectedVariants`（新建时不进编辑块，
        //   不清的话上一只选的变体还挂着 —— 2026-09-28 用户实测）
        allForms = true
        selectedVariants = emptySet()
        val centerX = width / 2
        // ⚠ 与渲染那边的 `dialogH = 184` **成对**：92 = 184 / 2
        val dialogY = height / 2 - 92

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderPokemonDialogBackground(context, delta)
            }
        })

        addField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 30, 140, 16, Text.literal(""))
        addField?.setPlaceholder(Text.translatable("cobblemarket.price_limit.species_placeholder"))
        addField?.setChangedListener { updatePreview(it) }
        addDrawableChild(addField)

        // 闪光三态循环按钮：物种输入框左侧
        shinyButton = NineSliceButton(centerX - 101, dialogY + 30, 20, 16, Text.literal(""), { cycleShiny() })
        // 候选切换箭头（**对齐到预览槽位正下方**）：候选 ≥2 才显示，切换预览图与形态选项。
        // 槽位是 centerX+66 起、28 宽 → 中心 centerX+80，两枚 18 宽按钮按它左右排开（组中心 +82）；
        // 左缘 +62 与形态按钮（到 +60）留 2px，右缘 +102 距对话框边框 8px 不进边框
        speciesPrevButton = NineSliceButton(
            centerX + 62, dialogY + 54, 18, 16, Text.literal(""),
            { cycleSpecies(-1) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/previous.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.species_prev")
        ).also { it.visible = false; addDrawableChild(it) }
        speciesNextButton = NineSliceButton(
            centerX + 84, dialogY + 54, 18, 16, Text.literal(""),
            { cycleSpecies(1) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/next.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.species_next")
        ).also { it.visible = false; addDrawableChild(it) }
        addDrawableChild(shinyButton)
        updateShinyButton()

        vButton = NineSliceButton(centerX - 80, dialogY + 48, selectBtnW, 14, Text.literal(""), { toggleVList() })
        vButton?.visible = false
        addDrawableChild(vButton)

        // 形态选择按钮：与 V 数按钮并排各占 68px（中间留 4px 空隙），只有解析出多形态物种时显示
        // ⚠ 创建时就把文案设上 —— 原来给的是空的 `Text.literal("")`，没搜精灵时它是个**空壳按钮**
        //   （同黑名单，用户 2026-09-27 报过）
        formButton = NineSliceButton(
            centerX - 8, dialogY + 48, selectBtnW, 14, formButtonText(), { toggleFormList() }
        )

        // 「全部」开关 + 「变体」按钮（2026-09-27 用户定，与黑名单同一套三档：全部 / 形态 / 变体）——
        // ⚠ 价格限制这边第一行是**两个并排**（V数 + 形态），所以另开一行放这两个、也并排
        // `texH = 120` = 三段贴图，第三段是**选中态**（用户要的是勾上高亮，不是文字在是/否之间切）
        allFormsButton = NineSliceButton(
            centerX - 80, dialogY + 66, selectBtnW, 14, Text.translatable("cobblemarket.blacklist.all"),
            { toggleAllForms() }, texH = 120
        )
        addDrawableChild(allFormsButton)
        updateAllFormsButton()
        variantButton = NineSliceButton(
            centerX - 8, dialogY + 66, selectBtnW, 14, Text.literal(""), { toggleVariantList() }
        )
        addDrawableChild(variantButton)
        updateVariantButton()
        // ⚠ **再刷一次「全部」**：上面那次调它时形态/变体按钮还没建出来，置灰设不上
        updateAllFormsButton()
        // ⚠ 形态按钮**一直在**（与黑名单同一口径，2026-09-27 用户定）——
        //   与「V数 / 形态」同行的 V 数按钮、下一行的「全部 / 变体」一样，不许因为"还没搜精灵"就缺
        //   （老代码是搜出精灵才放出来，那正是用户反复指出的问题）
        formButton?.visible = true
        addDrawableChild(formButton)

        // 特训限定按钮（两态：不限 ↔ 不含特训），V/形态按钮下方留 4px 空隙
        // 上面多了「全部 / 变体」一行，这里连同下面整体下移 18
        ruleHtButton = NineSliceButton(centerX - 80, dialogY + 84, 140, 14, ruleHtButtonText(), { toggleRuleHtFilter() })
        addDrawableChild(ruleHtButton)

        minField = createPriceField(centerX - 48, dialogY + 102, "cobblemarket.price_limit.min_placeholder")
        maxField = createPriceField(centerX + 2, dialogY + 102, "cobblemarket.price_limit.max_placeholder")

        addConfirmButton = NineSliceButton(
            centerX - 85, dialogY + 154, 80, 20,
            Text.translatable("cobblemarket.blacklist.add"),
            { confirmPokemonAdd() }
        )
        addDrawableChild(addConfirmButton)
        addCancelButton = NineSliceButton(
            centerX + 5, dialogY + 154, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeDialog() }
        )
        // ⚠ 列表展开时的**退路**（2026-09-27 用户指出）：列表顶到 [LIST_TOP] 后触发它的按钮被藏起来，
        //   「再点一次关掉」那条路没了 ⇒ 展开后出不去。沿用「添加/取消」那行的位置（列表展开时它们本来就藏着）
        // 两枚并排占「添加/取消」那两个位置（列表展开时它们本来就藏着）：**左「清空」右「返回」**
        listClearButton = NineSliceButton(
            centerX - 85, dialogY + 154, 80, 20,
            Text.translatable("cobblemarket.points.store.clear"), { clearSelectedVariants() }
        ).also { it.visible = false; addDrawableChild(it) }
        listBackButton = NineSliceButton(
            centerX + 5, dialogY + 154, 80, 20,
            Text.translatable("cobblemarket.gui.back"), { closeAnyList() }
        ).also { it.visible = false; addDrawableChild(it) }
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
            // 「全部」开关：数据层就是"形态全封 + 变体全封"，两个键都为 `*` 才算勾上
            allForms = PokemonPriceLimitEntry.ALL_FORMS in entry.aspects &&
                PokemonPriceLimitEntry.ALL_VARIANTS in entry.variantAspects
            updateAllFormsButton()
            formButton?.dimmed = allForms
            variantButton?.dimmed = allForms
            // 变体：**原样回填**（多选）—— 不回填的话一提交就把它清空了
            selectedVariants = entry.variantAspects.toSet()
            updateVariantButton()
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

    private fun renderPokemonDialogBackground(context: DrawContext, delta: Float) {
        val centerX = width / 2
        val dialogW = minOf(300, width - 40).coerceAtLeast(200)
        // ⚠ 150 → 168（多了「全部 / 变体」一行）→ 152（收掉底部空白）→ **184**：
        //   列表改成从 [LIST_TOP] 起排之后要放下 6 行（48 + 6*16 = 144），
        //   再给底部按钮行留位置（154 + 20 + 10 = 184）。
        //   **必须与建控件那边的 `height / 2 - 92` 成对改**
        val dialogH = 184
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.price_limit.add_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        // 本地校验错误提示（服务端校验兜底）：画在弹窗下沿外，避免与 +30 起的输入框/控件重叠
        dialogError?.let {
            context.drawCenteredTextWithShadow(textRenderer, it, centerX, dialogY + dialogH + 8, 0xFF5555)
        }

        // ⚠ **三个列表任意一个开着都要跳过** —— 这行是**渲染的文字**（不是控件），
        //   隐藏名单管不到它，只能在这儿判（2026-09-27 用户报：展开变体后它透过来）
        // ⚠ 位置也得跟着弹窗加高走：152 → 184 之后按钮在 154，原来那个 128 会压上去
        if (!vListOpen && !formListOpen && !variantListOpen) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.price_limit.hint"),
                centerX, dialogY + 132, 0xAAAAAA)
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
            // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
            val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
            try {
                PokemonIconRenderer.draw(
                    context = context,
                    renderable = rp,
                    state = previewState.stateFor(rp),
                    x = slotX, y = slotY, size = slotSize,
                    delta = delta,
                    animate = useFloat
                )
            } catch (_: Exception) {
            }
        }

        // 候选计数（1/3）：让玩家知道有几个可切、现在是第几个（居中于两枚箭头之下）
        if (speciesCandidates.size > 1) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal("${speciesIndex + 1}/${speciesCandidates.size}").formatted(Formatting.GRAY),
                centerX + 82, dialogY + 72, 0xFFFFFF
            )
        }
    }

    private fun openItemDialog(entry: ItemPriceLimitEntry?) {
        editingPokemon = null
        editingItem = entry
        hideMainControls()
        heldAddMode = false
        val centerX = width / 2
        val dialogY = height / 2 - 71

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderItemDialogBackground(context)
            }
        })

        // 从手持物品添加（组件粒度）：搜索框左侧小手图标按钮，点击变红选中（获取手持物品），
        // 再点「添加」确认才提交；服务端读主手物品提取组件快照，与输入框价格一并生效
        heldAddButton = NineSliceButton(
            centerX - 104, dialogY + 40, 16, 16,
            Text.literal(""),
            { toggleHeldAdd() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/hand.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            // 三段贴图：第三段是**选中态**（进入手持添加模式后保持高亮）
            texH = 120,
            // 悬停提示走**原生 tooltip**（原先是自己在 render 里手绘一个，样式与别处不一致）
            tooltip = Text.translatable("cobblemarket.blacklist.add_held_item")
        )
        addDrawableChild(heldAddButton)

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
        val dialogW = minOf(300, width - 40).coerceAtLeast(200)
        val dialogH = 142
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.price_limit.add_item_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)
        // 标题下提示：搜不到的物品可输入真实 id（F3+H 显示高级提示框）
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.gui.item_id_hint").string,
            centerX, dialogY + 26, 0xFFAAAAAA.toInt())

        // 本地校验错误/信息提示：画在弹窗下沿外，避免与 +40 起的物品输入框/控件重叠
        // 手持模式实时读主手：换手/丢物品后文字与图标同步，不会文字与图标各说各话
        if (heldAddMode) {
            val held = heldPreviewStack()
            if (held != null) {
                context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.blacklist.held_item_selected", held.name).string,
                    centerX, dialogY + dialogH + 8, 0x55FF55)
            } else {
                context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.blacklist.held_item_empty").string,
                    centerX, dialogY + dialogH + 8, 0xFF5555)
            }
        } else dialogError?.let {
            context.drawCenteredTextWithShadow(textRenderer, it, centerX, dialogY + dialogH + 8, dialogErrorColor)
        }

        if (!itemListOpen) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.price_limit.hint"),
                centerX, dialogY + 126, 0xAAAAAA)
        }

        // 物品预览：手持模式显示主手物品（文字+图标双重提醒），否则显示搜索预览
        val heldPreview = if (heldAddMode) heldPreviewStack() else null
        if (heldPreview != null) {
            drawItemWithBar(context, heldPreview, centerX + 66, dialogY + 42)
        } else if (!heldAddMode) {
            previewCandidate?.let { candidate ->
                com.shusheng.cobblemarket.client.ItemComponentsDisplay
                    .iconStack(candidate.itemId, candidate.componentsSpec)
                    ?.let { stack -> if (!stack.isEmpty) drawItemWithBar(context, stack, centerX + 66, dialogY + 42) }
            }
        }
    }

    // ── 精灵：物种解析预览（照搬黑名单） ──

    private fun updatePreview(text: String) {
        dialogError = null
        // 解析出**全部**候选（精确优先已由 candidatesByNameOrId 保证），默认选第一个：
        // 输入完整名字（鬼斯）直接就是它；输入片段（鬼）时用预览槽位下方的箭头在旁边挑
        speciesCandidates = com.shusheng.cobblemarket.util.SpeciesText.candidatesByNameOrId(text)
        speciesIndex = 0
        applySpecies(speciesCandidates.firstOrNull())
        updateSpeciesArrows()
    }

    /** 左右箭头切换候选（循环）；切换后预览图与形态选项一起刷新 */
    private fun cycleSpecies(delta: Int) {
        if (speciesCandidates.size <= 1) return
        speciesIndex = (speciesIndex + delta + speciesCandidates.size) % speciesCandidates.size
        applySpecies(speciesCandidates[speciesIndex])
        updateSpeciesArrows()
    }

    /** 箭头只在候选 ≥2 时出现（唯一候选没什么可切的） */
    private fun updateSpeciesArrows() {
        val multi = speciesCandidates.size > 1
        speciesPrevButton?.visible = multi
        speciesNextButton?.visible = multi
    }

    /** 应用选中的物种：重建预览模型与形态选项（输入变化与箭头切换共用） */
    private fun applySpecies(species: Species?) {
        previewRenderable = null
        previewSpecies = null
        formOptions = listOf()
        formIndex = 0
        formListOpen = false
        rebuildFormList()
        if (species != null) {
            previewSpecies = species
            // 形态选项语义（与服务端匹配）：
            //   ["*"] = 全部形态（显式全选）
            //   []    = 默认形态（标准形态无 aspect 声明且物种有 forms 时提供）
            //   [x..] = 各 form 的 aspects
            // ⚠ **「不限」也要在候选里**（同黑名单）：它表示"形态不参与判定、只看变体"，
            //   是「只按变体限价」那一档的形态侧 —— 服务端 [PokemonPriceLimitEntry.ANY_FORMS] 早就支持了
            val formAny = setOf(PokemonPriceLimitEntry.ANY_FORMS)
            val seen = mutableSetOf(formAny, setOf(PokemonPriceLimitEntry.ALL_FORMS))
            val opts = mutableListOf(
                FormOption(Text.translatable("cobblemarket.blacklist.form_any").string, formAny),
                FormOption(Text.translatable("cobblemarket.blacklist.form_all").string, setOf(PokemonPriceLimitEntry.ALL_FORMS))
            )
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
            // 超极巨化形态**不在候选里**（battleOnly 形态，超极巨化由 gmaxFactor 表达，玩家也拿不到）——
            // 判据与滤法统一在 SpeciesText.selectableForms，别再各写一份
            com.shusheng.cobblemarket.util.SpeciesText.selectableForms(species).forEach { f ->
                val a = f.aspects.toSet()
                if (seen.add(a)) opts.add(FormOption(formLabel(a), a))
            }
            formOptions = opts
            formIndex = 0
            refreshPreviewModel()
            // ⚠ **解析出物种就显示**（2026-09-27 用户定，与黑名单同一口径）——
            //   原来判 `size > 1`，单形态物种永远不显示、那一行空着
            formButton?.visible = true
            // ⚠ **换物种要把不属于它的变体清掉**（同黑名单，2026-09-28 用户报）
            // ⚠ 候选表**没到**时不能清（那时"合法值"是空的，会把选中的全清光）
            if (com.shusheng.cobblemarket.client.PointMarketCache.featureAspectsLoaded) {
                val valid = com.shusheng.cobblemarket.client.VariantCandidates
                    .aspectList(species, currentVariantGroups()).toSet()
                selectedVariants = selectedVariants intersect valid
                updateVariantButton()
            }
            formButton?.setMessage(formButtonText())
        } else {
            // 物种没解析出来**也照常显示**（同上）
            formButton?.visible = true
        }
    }

    /** 按当前形态选择 + 闪光选择重建预览模型：仅闪光时叠加 shiny aspect 渲染闪光形态 */
    private fun refreshPreviewModel() {
        val species = previewSpecies ?: run { previewRenderable = null; return }
        val aspects = formOptions.getOrNull(formIndex)?.aspects
            // ⚠ 「全部形态」和「不限」都不是合法 aspect（是判据里的哨兵值），预览时都要滤掉
            ?.filter { it != PokemonPriceLimitEntry.ALL_FORMS && it != PokemonPriceLimitEntry.ANY_FORMS }
            ?.toMutableSet() ?: mutableSetOf()
        if (shinyFilter == PokemonPriceLimitEntry.SHINY_YES) aspects.add("shiny")
        previewRenderable = RenderablePokemon(species, aspects, ItemStack.EMPTY)
    }

    // 物种名解析统一走 SpeciesText.candidatesByNameOrId（精确优先 + 返回全部候选，见 updatePreview）

    /** 物品输入变更：重建匹配列表（照搬物品黑名单） */
    private fun updateItemPreview(text: String) {
        // 打字即退出手持模式（两条添加路径互斥），随后按搜索路径走
        exitHeldAddMode()
        dialogError = null
        matchedItems = resolveMatchingItems(text)
        // 唯一匹配自动选中；多匹配等待用户点选
        selectedItemIndex = if (matchedItems.size == 1) 0 else -1
        itemListOpen = false
        itemListScroll = 0
        rebuildItemList()
        updateItemSelectButton()
        previewCandidate = matchedItems.getOrNull(selectedItemIndex) ?: matchedItems.firstOrNull()
    }

    // ── 精灵：V 档选择（照搬形态展开列表模式） ──

    private fun vButtonText() = Text.literal(
        com.shusheng.cobblemarket.util.TextUtil.truncateString(
            "${Text.translatable("cobblemarket.price_limit.v_label").string}${vLabel(vOptions[vIndex])}",
            selectBtnW - 8
        )
    )

    private fun toggleVList() {
        // ⚠ 「全部」勾着时给声失败音 —— 按钮是 `dimmed`（还点得动），不出声玩家会以为界面卡了
        if (allForms) return playFailSound()
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
        // ⚠ 列表现在从 [LIST_TOP]（= 原来「V数 / 形态」那一行）起排，所以**这两个按钮自己也要隐藏**，
        //   不然它们会浮在列表上（2026-09-27 用户指出：这是"原地展开"观感的来源）
        formButton?.visible = !vListOpen
        vButton?.visible = !vListOpen
        ruleHtButton?.visible = !vListOpen
        minField?.visible = !vListOpen
        maxField?.visible = !vListOpen
        // ⚠ 新加的两个按钮也要隐藏 —— 漏了会透到列表下面（黑名单那边就是这么被抓的）
        allFormsButton?.visible = !vListOpen
        variantButton?.visible = !vListOpen
        addConfirmButton?.visible = !vListOpen
        addCancelButton?.visible = !vListOpen
        // 任一列表开着就显示「返回」（三个列表共用一枚，判据写全 —— 重建任一列表都会重设它）
        listBackButton?.visible = vListOpen || formListOpen || variantListOpen
        // 「清空」只在**变体列表**开着时显示（另外两个列表没有"多选"这回事）
        listClearButton?.visible = variantListOpen
        if (!vListOpen) return
        val centerX = width / 2
        // ⚠ 同 [openPokemonDialog]：弹窗高度变了（150→168），列表落点跟着走
        val dialogY = height / 2 - 92
        vOptions.forEachIndexed { idx, v ->
            val label = vLabel(v)
            val btn = NineSliceButton(
                centerX - 80, dialogY + LIST_TOP + idx * 16, 140, 14,
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

    /**
     * 形态的**裸值**（选项列表与按钮拼接用）。
     * ⚠ **哨兵值必须先判**：`*`（全部）和 `**`（不限）都不是物种 aspect，落到 `else` 会原样打出机器串。
     * ⚠ 行首标签与悬停窗要用**带前缀**的 [formTextOf]，两边要求不同。
     */
    private fun formLabel(aspects: Set<String>): String = when {
        PokemonPriceLimitEntry.ANY_FORMS in aspects -> Text.translatable("cobblemarket.blacklist.form_any").string
        PokemonPriceLimitEntry.ALL_FORMS in aspects -> Text.translatable("cobblemarket.blacklist.form_all").string
        aspects.isEmpty() -> Text.translatable("cobblemarket.blacklist.form_default").string
        else -> aspects.joinToString("/") { aspectLabel(it) }
    }

    /** 行首标签与悬停窗里的形态文字 —— **带「形态：」前缀**（光一个「不限」看不出是什么不限） */
    private fun formTextOf(aspects: Set<String>): String =
        Text.translatable("cobblemarket.blacklist.form").string + formLabel(aspects)

    /**
     * **行首标签**里的变体文字（带「变体：」前缀，横向 `/` 连）；**一个没勾返回 null**。
     * ⚠ 词条用 `variant_label`（「变体：%s」）**不是** `variant_count`（「变体：N 项」）——
     *   后者是给按钮显示数量的，拼在值后面会多出一个「项」。
     */
    private fun variantTextOf(variantAspects: List<String>): String? =
        variantAspects.takeIf { it.isNotEmpty() }
            ?.joinToString("/") { com.shusheng.cobblemarket.util.SpeciesText.featureValueLabel(it) }
            ?.let { Text.translatable("cobblemarket.blacklist.variant_label", it).string }

    /**
     * **悬停窗**里的变体行 —— **每个变体单独一行**，带维度名。
     * ⚠ 悬停窗不能横着排（晃晃斑 5 个就连成一条横跨屏幕的长串）；带维度名是因为官方词条并不全
     *（`face-spots-1` 那种没名字，只显示原文就不知道属于哪一维）。
     */
    private fun variantLinesOf(variantAspects: List<String>): List<String> {
        if (variantAspects.isEmpty()) return emptyList()
        val picked = variantAspects.toSet()
        val withGroup = currentVariantGroups().flatMap { g ->
            g.aspects.filter { it in picked }.map {
                "${com.shusheng.cobblemarket.util.SpeciesText.featureGroupLabel(g.key)}：" +
                    com.shusheng.cobblemarket.util.SpeciesText.featureValueLabel(it)
            }
        }
        // ⚠⚠ **候选表没到时要退化**：维度名只能从候选表查，而它**只有打开过变体列表才请求得到** ——
        //   主界面直接悬停列表行时它是空的 ⇒ `.flatMap` 出来是空 ⇒ 整行消失（2026-09-28 用户报）。
        return withGroup.ifEmpty {
            variantAspects.map { com.shusheng.cobblemarket.util.SpeciesText.featureValueLabel(it) }
        }
    }

    /** ⚠ 物种没解析出来时也要有字（「形态：不限」）—— 这按钮一直显示，空壳看着就是坏了 */
    private fun formButtonText(): Text {
        val form = formOptions.getOrNull(formIndex)
            ?: return Text.translatable("cobblemarket.blacklist.form_any")
        return Text.literal(
            com.shusheng.cobblemarket.util.TextUtil.truncateString(
                "${Text.translatable("cobblemarket.blacklist.form").string}${form.label}",
                selectBtnW - 8
            )
        )
    }

    private fun toggleFormList() {
        // 同上：勾着「全部」时点了给声失败音
        if (allForms) return playFailSound()
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
    // 展开时隐藏被列表覆盖的控件（价格框 + 确认/取消按钮）——它们先于形态选项添加，
    // MC 点击遍历按添加顺序，不隐藏的话点击会被它们拦截，形态选项永远点不到。
    // V 数按钮与形态按钮并排（dialogY+48 行），列表从 dialogY+62 起在其下方，不覆盖不隐藏
    private fun rebuildFormList() {
        formOptionButtons.forEach { remove(it) }
        formOptionButtons.clear()
        // 形态列表展开时覆盖特训按钮位置（dialogY+62 起），隐藏避免点击拦截
        ruleHtButton?.visible = !formListOpen
        minField?.visible = !formListOpen
        maxField?.visible = !formListOpen
        // ⚠ 同上：新加的两个按钮必须一起隐藏
        allFormsButton?.visible = !formListOpen
        variantButton?.visible = !formListOpen
        // ⚠ 列表从 [LIST_TOP]（原来「V数 / 形态」那行）起排 ⇒ 这两个按钮自己也要隐藏
        formButton?.visible = !formListOpen
        vButton?.visible = !formListOpen
        addConfirmButton?.visible = !formListOpen
        addCancelButton?.visible = !formListOpen
        listBackButton?.visible = vListOpen || formListOpen || variantListOpen
        // 「清空」只在**变体列表**开着时显示（另外两个列表没有"多选"这回事）
        listClearButton?.visible = variantListOpen
        if (!formListOpen) return
        val centerX = width / 2
        // ⚠ 同上：弹窗高度 150→168
        val dialogY = height / 2 - 92
        formOptions.drop(formListScroll).take(MAX_FORM_LIST_ROWS).forEachIndexed { i, opt ->
            val idx = formListScroll + i
            // 数据包自创的超长 aspect 名截断，防止溢出按钮
            val label = com.shusheng.cobblemarket.util.TextUtil.truncateString(opt.label, 124)
            val btn = NineSliceButton(
                centerX - 80, dialogY + LIST_TOP + i * 16, 140, 14,
                if (idx == formIndex) com.shusheng.cobblemarket.util.TextUtil.selectedText(label) else Text.literal(label),
                { selectForm(idx) }
            )
            formOptionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // ── 物品：匹配列表选择（照搬物品黑名单） ──

    private fun resolveMatchingItems(input: String): List<com.shusheng.cobblemarket.client.ItemCandidate> =
        com.shusheng.cobblemarket.client.ItemCandidateResolver.resolve(input)

    private fun updateItemSelectButton() {
        itemSelectButton?.visible = matchedItems.isNotEmpty()
        val label = matchedItems.getOrNull(selectedItemIndex)?.displayName
            ?: if (matchedItems.size > 1)
                Text.translatable("cobblemarket.blacklist.item_matches", matchedItems.size).string
            else ""
        itemSelectButton?.setMessage(Text.literal(
            com.shusheng.cobblemarket.util.TextUtil.truncateString(
                "${Text.translatable("cobblemarket.blacklist.item_label").string}$label",
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
        // 点选搜索项同样退出手持模式（互斥）
        exitHeldAddMode()
        selectedItemIndex = idx
        itemListOpen = false
        dialogError = null
        rebuildItemList()
        updateItemSelectButton()
        previewCandidate = matchedItems.getOrNull(idx)
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
        val dialogY = height / 2 - 71
        matchedItems.drop(itemListScroll).take(MAX_ITEM_LIST_ROWS).forEachIndexed { i, candidate ->
            val idx = itemListScroll + i
            val label = com.shusheng.cobblemarket.util.TextUtil.truncateString(candidate.displayName, 124)
            val btn = NineSliceButton(
                centerX - 80, dialogY + 72 + i * 16, 140, 14,
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
            // ⚠ 「全部」开关在**数据层没有自己的字段** —— 它就是"形态全封 + 变体全封"：
            //   勾上时直接写两个 `*`（下面两个按钮已置灰、值不参与判定）
            aspects = if (allForms) listOf(PokemonPriceLimitEntry.ALL_FORMS)
            else formOptions.getOrNull(formIndex)?.aspects?.toList() ?: listOf(PokemonPriceLimitEntry.ALL_FORMS),
            // 编辑模式带原条目：服务端先删旧再插新（改了形态等字段也不会残留旧条目）
            original = editingPokemon,
            htFilter = ruleHtFilter,
            // 变体（多选）；「全部」勾上时写 `*` = 任意变体都限
            variantAspects = if (allForms) listOf(PokemonPriceLimitEntry.ALL_VARIANTS)
            else selectedVariants.toList()
        ))
        closeDialog()
    }

    private fun confirmItemAdd() {
        // 手持模式：校验价格后直接提交手持物品条目（组件快照由服务端读主手提取）
        if (heldAddMode) {
            val prices = validatePrices() ?: return
            sendToServer(com.shusheng.cobblemarket.network.AddHeldItemPriceLimitPayload(prices.first, prices.second))
            closeDialog()
            return
        }
        // 先检查物品输入（空输入直接返回，不置价格提示），再校验价格
        val input = addField?.text?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            playFailSound()
            return
        }
        val prices = validatePrices() ?: return
        val selected = matchedItems.getOrNull(selectedItemIndex)
        sendToServer(AddItemPriceLimitPayload(
            itemName = selected?.itemId ?: resolveMatchingItems(input).firstOrNull()?.itemId ?: input,
            componentsSpec = selected?.componentsSpec,
            minPrice = prices.first,
            maxPrice = prices.second,
            // 编辑模式带原物品：服务端先删旧再插新（改选了物品也不会残留旧条目）
            originalItemId = editingItem?.itemId,
            originalComponentsSpec = editingItem?.componentsSpec
        ))
        closeDialog()
    }

    /** 主手物品（空手返回 null）：手持模式文字与图标的统一数据源，实时读取保证换手后同步 */
    private fun heldPreviewStack(): ItemStack? =
        client?.player?.mainHandStack?.takeIf { !it.isEmpty }

    /** 退出手持模式：开始搜索/点选物品时调用。两条添加路径互斥，避免提交的物品与界面显示不符 */
    private fun exitHeldAddMode() {
        if (!heldAddMode) return
        heldAddMode = false
        heldAddButton?.selectedVisual = false
    }

    private fun toggleHeldAdd() {
        // 预检主手：空手播 fail 音效 + 弹窗下沿红字提醒，不切换模式；服务端红字提示仅兜底
        if (heldPreviewStack() == null) {
            dialogError = Text.translatable("cobblemarket.blacklist.held_item_empty").string
            dialogErrorColor = 0xFF5555
            playFailSound()
            return
        }
        heldAddMode = !heldAddMode
        // 选中态 = 按下视觉（红底），与黑名单/求购单手持按钮一致
        // 选中后的文字/图标由渲染时实时读主手（换手同步）；取消时留一条绿字确认
        heldAddButton?.selectedVisual = heldAddMode
        dialogError = if (heldAddMode) null
            else Text.translatable("cobblemarket.blacklist.held_item_cancelled").string
        dialogErrorColor = 0x55FF55
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
        // ⚠ 变体那套的字段**也在这里清**（原本的做法就是"关窗统一重置"）—— 漏了的话，关窗时列表
        //   还开着，下次开窗控件已重建、`variantOptionButtons` 却指着旧控件
        allForms = true
        allFormsButton = null
        selectedVariants = emptySet()
        variantButton = null
        variantListOpen = false
        variantListScroll = 0
        variantOptionButtons.clear()
        listBackButton = null
        listClearButton = null
        previewRenderable = null
        previewSpecies = null
        speciesCandidates = listOf()
        speciesIndex = 0
        speciesPrevButton = null
        speciesNextButton = null
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
        previewCandidate = null
        matchedItems = listOf()
        selectedItemIndex = -1
        itemListOpen = false
        itemListScroll = 0
        itemOptionButtons.clear()
        itemSelectButton = null
        heldAddButton = null
        heldAddMode = false
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
        itemTooltipCacheKey = null
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
            com.shusheng.cobblemarket.client.ItemComponentsDisplay.iconStack(entry.itemId, entry.componentsSpec)
                ?.let { itemIconStacks[entry] = it }
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
            iconData[index] = PokemonIconData(com.shusheng.cobblemarket.util.SpeciesText.displayName(species), RenderablePokemon(species, aspects, ItemStack.EMPTY), FloatingState())
        }
    }

    private fun renderPokemonIcon(
        context: DrawContext, index: Int, x: Int, y: Int, size: Int, delta: Float = 0f,
        /** 本行是否被悬停（跟左侧大图一起走 walk）。**必须由调用方按行索引算**：
         *  早先用 `IconRegistry.isMouseInRow` 只看纵向，鼠标跑到面板外侧、只要 y 平齐也会触发动画（2026-09-15） */
        preview: Boolean = false
    ) {
        val data = iconData[index] ?: return
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
            buildString {
                append(pokemonName(entry.speciesId))
                append(vText)
                append(" · ")
                append(formTextOf(entry.aspects.toSet()))
                // 变体：勾了才加（2026-09-28 用户报"选了变体不显示"）
                variantTextOf(entry.variantAspects)?.let { append(" · ").append(it) }
                append(" · ")
                append(priceText(entry.minPrice, entry.maxPrice, inlineCurrencyUnit()))
            }
        }
        // 搜索索引匹配（itemId + 名称 + tooltip 文本 + TM 招式，见 ItemSearchIndex；原版创造模式同款语义）
        // 条目级精确过滤：带组件条目按组件精确（搜「打鼾」只出打鼾 TM 条目），无组件条目仅物品文本命中
        filteredItemsCache = if (query == null) itemEntries else {
            itemEntries.filter { com.shusheng.cobblemarket.client.ItemSearchIndex.ruleEntryMatches(it.itemId, it.componentsSpec, query) }
        }
        itemRowTexts = filteredItemsCache.map { entry ->
            val summary = com.shusheng.cobblemarket.client.ItemComponentsDisplay.summary(entry.componentsSpec)
            val name = if (summary.isEmpty()) itemDisplay(entry.itemId) else "${itemDisplay(entry.itemId)}（$summary）"
            "$name · ${priceText(entry.minPrice, entry.maxPrice, inlineCurrencyUnit())}"
        }
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
                    leftX + panelWidth - rowEditBtnInset, y + rowBtnDy, rowBtnW, rowBtnH,
                    Text.translatable("cobblemarket.price_limit.edit"),
                    { openPokemonDialog(entry) }
                )
                editBtn.visible = addField == null
                editButtons.add(editBtn)
                addDrawableChild(editBtn)
                val delBtn = NineSliceButton(
                    leftX + panelWidth - rowRemoveBtnInset, y + rowBtnDy, rowBtnW, rowBtnH,
                    Text.translatable("cobblemarket.price_limit.remove"),
                    { sendToServer(RemovePokemonPriceLimitPayload(entry.speciesId, entry.vCount, entry.shinyFilter, entry.aspects, entry.htFilter, entry.variantAspects)) }
                )
                delBtn.visible = addField == null
                removeButtons.add(delBtn)
                addDrawableChild(delBtn)
            }
        } else {
            filteredItems().drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, entry ->
                val y = startY + i * rowHeight
                val editBtn = NineSliceButton(
                    leftX + panelWidth - rowEditBtnInset, y + rowBtnDy, rowBtnW, rowBtnH,
                    Text.translatable("cobblemarket.price_limit.edit"),
                    { openItemDialog(entry) }
                )
                editBtn.visible = addField == null
                editButtons.add(editBtn)
                addDrawableChild(editBtn)
                val delBtn = NineSliceButton(
                    leftX + panelWidth - rowRemoveBtnInset, y + rowBtnDy, rowBtnW, rowBtnH,
                    Text.translatable("cobblemarket.price_limit.remove"),
                    { sendToServer(RemoveItemPriceLimitPayload(entry.itemId, entry.componentsSpec)) }
                )
                delBtn.visible = addField == null
                removeButtons.add(delBtn)
                addDrawableChild(delBtn)
            }
        }
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        IconRegistry.beginFrame(mouseX, mouseY)
        MarketPanelBackground.draw(context, width, height)

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
            renderVariantScrollbar(context)
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
        drawPanelDivider(context, leftX + panelWidth / 2, startY - 2)

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
                    if (addField == null) renderPokemonIcon(context, origIndex, slotX, slotY, iconSize, delta = delta, preview = i == hoveredRow)
                } else {
                    // 物种留空（全部精灵）：槽位画 "?"
                    context.drawCenteredTextWithShadow(textRenderer, "?", slotX + iconSize / 2, slotY + iconSize / 2 - 4, 0xFFFFFF)
                }
                // 闪光标记用符号：金色 ★ = 仅闪光，白色 ☆ = 仅非闪光（与按钮一致）
                val line = rowTexts[scrollOffset + i]
                var cursor = leftX + 26
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
                // 精灵名单独上色（主属性色，全模组口径）：名字是行文本的前缀，拆两段画。
                // 「全部精灵」不是物种（物种留空），保持白色
                val nameText = pokemonName(entry.speciesId)
                val nameWidth = textRenderer.getWidth(nameText)
                context.drawTextWithShadow(textRenderer, nameText, cursor, y + 7,
                    if (entry.speciesId.isEmpty()) 0xFFFFFF
                    else EntryBadgeRenderer.speciesTypeColor(entry.speciesId, entry.aspects))
                cursor += nameWidth
                context.drawTextWithShadow(textRenderer,
                    com.shusheng.cobblemarket.util.TextUtil.truncateString(line.removePrefix(nameText), 170 - (cursor - (leftX + 26))),
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
                    itemIconStacks[entry]?.let { drawItemWithBar(context, it, leftX + 4, y + 4) }
                }
                // 物品名单独上色（稀有度色，全模组口径：界面出现物品名就不许白字）：名字是行文本前缀，
                // 拆两段画（名字带色 + 后半段白字）。
                // 后半段的截断要扣掉名字宽：整行预算 170 是含名字算的，不扣就变成「名字 + 170」，
                // 两段相加超宽压到右侧编辑/删除按钮
                val fullText = rowTexts[scrollOffset + i]
                val nameText = itemDisplay(entry.itemId)
                val nameWidth = textRenderer.getWidth(nameText)
                context.drawTextWithShadow(textRenderer, nameText, leftX + 24, y + 7,
                    com.shusheng.cobblemarket.util.TextUtil.rarityNameColor(itemIconStacks[entry]))
                context.drawTextWithShadow(textRenderer,
                    com.shusheng.cobblemarket.util.TextUtil.truncateString(fullText.removePrefix(nameText), 170 - nameWidth),
                    leftX + 24 + nameWidth, y + 7, 0xFFFFFF)
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

        // 悬停预览大图：画在本界面内容全部之后才在面板之上。
        // **弹窗打开时一律不画**（照精灵市场）：addField 是两种弹窗（精灵/物品）共用的输入框字段，
        // 非空即弹窗打开；这时注册表里只剩弹窗自己的图标，鼠标停在弹窗上照样能命中 entryInRow
        // （它只按 y 匹配行范围 + x 在面板内）→ 左侧无故冒出大图（2026-09-15 实测）
        if (addField == null) {
            val previewRowL = width / 2 - panelWidth / 2
            IconRegistry.entryInRow(mouseX.toDouble(), mouseY.toDouble(), previewRowL, previewRowL + panelWidth)
                ?.let { PokemonHoverPreview.render(context, it.renderable, it.state, previewRowL, height) }
        }
    }

    private fun renderPokemonTooltip(context: DrawContext, entry: PokemonPriceLimitEntry, mouseX: Int, mouseY: Int) {
        // 文本行缓存：悬停同一行时内容不变，只在悬停目标变化时重建（见 tooltipCacheKey 注释）
        if (tooltipCacheKey != entry) {
            tooltipCacheKey = entry
            tooltipCacheLines = buildPokemonTooltipLines(entry)
        }
        // 走富文本重载（物品悬停窗同款）：首行名字要单独上属性色，纯 String 版只能整行白。
        // 行文本内容与原先逐字相同 → 宽度/行高计算不变（宽度仍按各行完整文本量）
        drawRichTooltip(context, tooltipCacheLines, mouseX, mouseY, rowButtonsRect(hoveredRow))
    }

    private fun buildPokemonTooltipLines(entry: PokemonPriceLimitEntry): List<Pair<Text, Int>> {
        // 名字行 = 主属性色（全模组口径：界面出现精灵名就不许白字）。
        // 物种留空 = 「全部精灵」，不是物种 → 保持白字；物种解析不到时 speciesTypeColor 自己也返回白色
        val nameColor = if (entry.speciesId.isEmpty()) 0xFFFFFF
        else EntryBadgeRenderer.speciesTypeColor(entry.speciesId, entry.aspects)
        val lines = mutableListOf<Pair<Text, Int>>(Text.literal(pokemonName(entry.speciesId)) to nameColor)
        lines.add(Text.literal(Text.translatable("cobblemarket.price_limit.v_label").string + vLabel(entry.vCount)) to 0xFFFFFF)
        // ⚠ 带「闪光：」前缀（同黑名单）—— 悬停窗里是无条件列出来的，光一个「不限」看不出是什么
        lines.add(
            Text.literal(
                Text.translatable("cobblemarket.blacklist.shiny").string + shinyLabel(entry.shinyFilter)
            ) to 0xFFFFFF
        )
        lines.add(Text.literal(formTextOf(entry.aspects.toSet())) to 0xFFFFFF)
        // 变体：勾了才加（同列表行，用同一个函数保证口径一致）
        // 变体：**每个单独一行**（横向排会横跨整个屏幕，同黑名单）
        variantLinesOf(entry.variantAspects).forEach { lines.add(Text.literal(it) to 0xFFFFFF) }
        lines.add(Text.literal(htLabel(entry.htFilter)) to 0xFFFFFF)
        lines.add(Text.literal(priceText(entry.minPrice, entry.maxPrice, activeCurrencyUnit())) to 0xFFFFFF)
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
        // 照物品市场悬浮：真实物品词条（BASIC 常驻 / Shift 完整词条 / Ctrl 调试信息，Text 保留词条自带颜色）+ 价格行
        if (itemTooltipCacheKey != entry) {
            itemTooltipCacheKey = entry
            itemTooltipLines = buildItemTooltipLines(entry, TooltipType.BASIC)
            itemTooltipAdvancedLines = null
            itemTooltipAdvancedType = null
        }
        val advanced = if (com.shusheng.cobblemarket.client.ItemComponentsDisplay.hoverExpanded()) {
            val type = com.shusheng.cobblemarket.client.ItemComponentsDisplay.tooltipTypeForHover()
            if (itemTooltipAdvancedType != type) {
                itemTooltipAdvancedType = type
                itemTooltipAdvancedLines = buildItemTooltipLines(entry, type)
            }
            itemTooltipAdvancedLines
        } else {
            itemTooltipAdvancedType = null
            null
        }
        drawRichTooltip(context, advanced ?: itemTooltipLines, mouseX, mouseY, rowButtonsRect(hoveredRow))
    }

    private fun buildItemTooltipLines(entry: ItemPriceLimitEntry, type: TooltipType): List<Pair<Text, Int>> {
        val lines = mutableListOf<Pair<Text, Int>>()
        val stack = itemIconStacks[entry]
        if (stack != null) {
            // 栈在手上时不自己拼名字：原版 getTooltip 首行就是 name 套稀有度色（1.21.1 字节码已核对），
            // 行色参数被 Text 自带样式盖过，名字自然是稀有度色
            lines.addAll(com.shusheng.cobblemarket.client.ItemComponentsDisplay.itemTooltip(stack, client?.player, type).map { it to 0xFFFFFF })
        } else {
            // 名字行照行内口径上稀有度色（该行只有名字，没有后半段要拆）；
            // 本分支＝解析不出栈的条目（物品 id 非法），rarityNameColor 取不到栈只能落白
            lines.add(Text.literal(itemDisplay(entry.itemId)) to
                com.shusheng.cobblemarket.util.TextUtil.rarityNameColor(stack))
        }
        lines.add(Text.literal(priceText(entry.minPrice, entry.maxPrice, activeCurrencyUnit())) to 0xFFFFFF)
        return lines
    }

    /** 富文本行 tooltip 渲染（照物品市场：Text 自带颜色样式优先于行色参数）；
     *  [avoid] 为本行按钮矩形（按行号算几何，见 rowButtonsRect），传 null = 不避让 */
    private fun drawRichTooltip(context: DrawContext, lines0: List<Pair<Text, Int>>, mouseX: Int, mouseY: Int, avoid: TooltipPlacement.Rect? = null) {
        // 行数按屏幕高钳制（Shift / Ctrl 展开可能几十行）—— 见 [clampTooltipRows]
        val lines = clampTooltipRows(lines0, height) { n -> Text.translatable("cobblemarket.gui.tooltip_more", n) to 0xAAAAAA }
        var maxWidth = 0
        lines.forEach { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it.first)) }

        val padding = 4
        val tooltipHeight = lines.size * 10 + padding
        // 悬停窗**不许把本行的行按钮盖掉一半以上**：矩形按行号算几何（与 rebuildRowButtons 同源）
        val (tx, ty) = TooltipPlacement.place(
            mouseX = mouseX, mouseY = mouseY,
            textW = maxWidth, textH = tooltipHeight, pad = padding,
            screenW = width, screenH = height, avoid = avoid
        ) ?: return

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, lines.size * 10 + 2 * padding, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, (line, color) ->
            context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
        }
        context.matrices.pop()
    }

    // ── 交互 ──

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        // 形态列表展开且超过可见行数时，滚轮滚动形态列表（照黑名单）
        // 变体列表同理（候选可能几十个 —— 阿柏怪光花纹就 22 个）
        // ⚠ 按**行**算（标题行也占一行），与 [rebuildVariantList] 同一口径
        val variantRows = currentVariantRows().size
        if (variantListOpen && variantRows > MAX_FORM_LIST_ROWS) {
            variantListScroll = (variantListScroll - verticalAmount.toInt())
                .coerceIn(0, variantRows - MAX_FORM_LIST_ROWS)
            rebuildVariantList()
            return true
        }
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
        // 图标点击叫声：弹窗图标精确命中；**弹窗打开时不做整行触发** —— 整行判定只看 y，
        // 弹窗里精灵图标与输入框的 y 挨得近，会误把点输入框当成点图标
        // 先按图标矩形精确命中（弹窗里的图标照样能点），再考虑整行 —— 整行判定只看 y，
        // 弹窗里精灵图标与输入框挨得近，会误把点输入框当成点图标，所以弹窗时不整行触发
        if (button == 0 && !IconCryClick.playIconAt(mouseX, mouseY) && addField == null) {
            val rowL = width / 2 - panelWidth / 2
            IconCryClick.playRowAt(mouseX, mouseY, rowL, rowL + panelWidth - 98)
        }
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
                    previewCandidate = matchedItems.getOrNull(selectedItemIndex)
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
