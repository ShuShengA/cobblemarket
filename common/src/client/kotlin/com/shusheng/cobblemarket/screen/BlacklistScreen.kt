package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.playFailSound

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import com.shusheng.cobblemarket.market.ItemBlacklistEntry
import com.shusheng.cobblemarket.market.PokemonBlacklistEntry
import com.shusheng.cobblemarket.network.AddItemBlacklistPayload
import com.shusheng.cobblemarket.network.AddPokemonBlacklistPayload
import com.shusheng.cobblemarket.network.ItemBlacklistDataPayload
import com.shusheng.cobblemarket.network.PokemonBlacklistDataPayload
import com.shusheng.cobblemarket.network.RemoveItemBlacklistPayload
import com.shusheng.cobblemarket.network.RemovePokemonBlacklistPayload
import com.shusheng.cobblemarket.network.RequestItemBlacklistPayload
import com.shusheng.cobblemarket.network.RequestPokemonBlacklistPayload
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
import org.joml.Quaternionf

/**
 * 黑名单管理：精灵 / 物品 两个 tab（骨架照搬价格限制界面）。
 * 精灵规则：物种 + 形态 + IV + 闪光；物品规则：物品 ID。
 * 两个黑名单 payload 独立，tab 切换分别请求。
 */
class BlacklistScreen : Screen(Text.translatable("cobblemarket.op.blacklist")) {

    private val panelWidth = 296
    private val rowHeight = 24
    private val MAX_FORM_LIST_ROWS = 8
    private val MAX_ITEM_LIST_ROWS = 8

    // ── 主列表状态 ──
    private var currentTab = 0 // 0 = 精灵, 1 = 物品
    private var pokemonEntries = listOf<PokemonBlacklistEntry>()
    private var itemEntries = listOf<ItemBlacklistEntry>()
    // 过滤结果缓存：数据/搜索/筛选变化时重建，render 每帧只读（避免每帧全量 filter）
    private var filteredPokemonCache = listOf<PokemonBlacklistEntry>()
    private var filteredItemsCache = listOf<ItemBlacklistEntry>()
    // 过滤结果带原索引（iconData 以 pokemonEntries 索引为 key）：行渲染每帧 indexOf 是 O(n) 扫描，
    // 重建时一并构建；行显示字符串（物种解析 + 多段翻译拼接）同样随重建缓存
    private var filteredPokemonIndexed = listOf<IndexedValue<PokemonBlacklistEntry>>()
    private var pokemonRowTexts = listOf<String>()
    private var itemRowTexts = listOf<String>()
    // 物品图标栈缓存（条目 → 解析栈）：行内每帧 tryParse + ItemStack 是分配热点，数据到达时构建；
    // 带组件快照的条目重建完整物品（真实组件渲染）
    private val itemIconStacks = mutableMapOf<ItemBlacklistEntry, ItemStack>()
    // tooltip 文本行缓存：内容只取决于条目，悬停同一行时每帧重建是悬停掉帧主因
    private var tooltipCacheKey: Any? = null
    private var tooltipCacheLines: List<String> = emptyList()
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
    private var unbanAllButton: NineSliceButton? = null
    private val removeButtons = mutableListOf<NineSliceButton>()
    private val editButtons = mutableListOf<NineSliceButton>()

    // 列表特训筛选两态：0 = 不限（默认），2 = 不含特训（仅精灵 tab；规则无「仅特训」维度，无需第三态）
    private var listHtFilter = PokemonBlacklistEntry.HT_ANY
    private var htFilterButton: NineSliceButton? = null
    // 对话框规则特训维度两态：不限（默认）↔ 不含特训
    private var ruleHtFilter = PokemonBlacklistEntry.HT_ANY
    private var ruleHtButton: NineSliceButton? = null
    // 编辑中的条目（编辑按钮打开对话框时预填），null = 新增
    private var editingEntry: PokemonBlacklistEntry? = null

    // ── 对话框状态（精灵） ──
    private var addField: TextFieldWidget? = null
    private var ivHpField: TextFieldWidget? = null
    private var ivAtkField: TextFieldWidget? = null
    private var ivDefField: TextFieldWidget? = null
    private var ivSpAtkField: TextFieldWidget? = null
    private var ivSpDefField: TextFieldWidget? = null
    private var ivSpdField: TextFieldWidget? = null
    private var previewRenderable: RenderablePokemon? = null
    private val previewState = FloatingState()
    // 添加对话框的形态选择：全部形态 + 物种的各个 form；aspects 为空 = 封禁所有形态。
    // 形态按钮点击展开列表（每行一个选项，可滚动），点选后收起。
    private data class FormOption(val label: String, val aspects: Set<String>)
    private var previewSpecies: Species? = null
    private var formOptions = listOf<FormOption>()
    private var formIndex = 0
    private var formButton: NineSliceButton? = null
    private var formListOpen = false
    private var formListScroll = 0
    private val formOptionButtons = mutableListOf<NineSliceButton>()
    private var addConfirmButton: NineSliceButton? = null
    private var addCancelButton: NineSliceButton? = null
    // 闪光三态（与价格限制一致的循环按钮）：不限 → 闪光 → 非闪光 → 不限
    private var shinyFilter = PokemonBlacklistEntry.SHINY_ANY
    private var shinyButton: NineSliceButton? = null

    // ── 对话框状态（物品） ──
    // 添加对话框的匹配物品选择：输入后列出全部匹配物品（如"钻石"→钻石/钻石剑/钻石原矿…），点选确认
    private var matchedItems = listOf<String>()
    // 物品添加对话框本地提示（画在弹窗下沿外；错误红字、信息绿字；输入变更时清除）
    private var dialogError: String? = null
    private var dialogErrorColor = 0xFF5555
    private var selectedItemIndex = -1
    private var itemListOpen = false
    private var itemListScroll = 0
    private val itemOptionButtons = mutableListOf<NineSliceButton>()
    private var itemSelectButton: NineSliceButton? = null
    private var batchAddButton: NineSliceButton? = null
    private var heldAddButton: NineSliceButton? = null
    // 手持添加模式：小手图标选中态（红底），点「添加」时提交手持物品条目
    private var heldAddMode = false
    private var previewItemId: String? = null

    private data class IconData(val displayName: String, val renderable: RenderablePokemon, val state: FloatingState)
    private val iconData = mutableMapOf<Int, IconData>()
    private val iconSize = 20

    private fun getListStartY() = 86
    private fun getMaxVisibleRows() = maxOf(0, (height - getListStartY() - 48) / rowHeight)

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
        // 搜索变化时重建行按钮：否则过滤后残留旧列表的删除按钮（与价格限制同款问题）
        searchField?.setChangedListener { _ ->
            rebuildFiltered()
            updateUnbanAllButton()
            scrollOffset = 0
            rebuildRemoveButtons()
        }
        addSelectableChild(searchField)
        addDrawableChild(searchField)
        searchField?.text = savedSearch

        val addBtn = NineSliceButton(
            leftX + panelWidth - 84, 62, 18, 16,
            Text.literal(""), { openAddDialog() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/choose.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        )
        addButton = addBtn
        addDrawableChild(addBtn)

        // 全部解封：仅物品 tab、搜索框有内容且匹配非空时显示，解封当前搜索匹配的全部条目
        unbanAllButton = NineSliceButton(
            leftX + panelWidth - 60, 62, 60, 16,
            Text.translatable("cobblemarket.blacklist.remove_all"),
            { confirmUnbanAll() }
        )
        unbanAllButton?.visible = false
        addDrawableChild(unbanAllButton)

        // 特训筛选（仅精灵 tab，与全部解封按钮互斥位置）：列表按规则的特训维度过滤
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
        rebuildFiltered()
        updateUnbanAllButton()
        requestCurrentTabData()
    }

    private fun updateTabButtons() {
        val pokemonLabel = Text.translatable("cobblemarket.blacklist.tab_pokemon").string
        val itemLabel = Text.translatable("cobblemarket.blacklist.tab_item").string
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
        updateUnbanAllButton()
        relayoutSearchRow()
        requestCurrentTabData()
    }

    // 搜索框占位符随 tab 切换：精灵 = 宝可梦名称...，物品 = 搜索物品
    private fun updateSearchPlaceholder() {
        searchField?.setPlaceholder(Text.translatable(
            if (currentTab == 0) "cobblemarket.gui.search_placeholder" else "cobblemarket.item.search"
        ).formatted(Formatting.GRAY))
    }

    private fun requestCurrentTabData() {
        if (currentTab == 0) sendToServer(RequestPokemonBlacklistPayload())
        else sendToServer(RequestItemBlacklistPayload())
    }

    private fun updateUnbanAllButton() {
        val hasSearch = !searchField?.text?.trim().isNullOrEmpty()
        unbanAllButton?.visible = currentTab == 1 && hasSearch && filteredItems().isNotEmpty()
        relayoutSearchRow()
    }

    /** 搜索行动态排布：右侧按钮（全部解封/特训筛选）可见时添加按钮在其左，否则添加按钮贴右；搜索框填满剩余空间 */
    private fun relayoutSearchRow() {
        val leftX = width / 2 - panelWidth / 2
        val rightVisible = (unbanAllButton?.visible == true) || (htFilterButton?.visible == true)
        val addX = if (rightVisible) leftX + panelWidth - 84 else leftX + panelWidth - 20
        addButton?.x = addX
        searchField?.setWidth(addX - 4 - (leftX + 2))
    }

    private fun confirmUnbanAll() {
        val list = filteredItems()
        if (list.isEmpty()) return
        // 所见即所得：按过滤出的条目键精确解封（搜索到变体级时只删显示的条目）
        sendToServer(com.shusheng.cobblemarket.network.RemoveItemsBlacklistPayload(list))
    }

    // ── 添加对话框 ──

    private fun openAddDialog() {
        if (currentTab == 0) openPokemonDialog(null) else openItemDialog()
    }

    private fun hideMainControls() {
        searchField?.visible = false
        backButton?.visible = false
        addButton?.visible = false
        pokemonTabButton?.visible = false
        itemTabButton?.visible = false
        unbanAllButton?.visible = false
        htFilterButton?.visible = false
        removeButtons.forEach { it.visible = false }
        editButtons.forEach { it.visible = false }
    }

    private fun openPokemonDialog(entry: PokemonBlacklistEntry?) {
        editingEntry = entry
        hideMainControls()
        val centerX = width / 2
        val dialogY = height / 2 - 90

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderPokemonDialogBackground(context, delta)
            }
        })

        addField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 30, 140, 16, Text.literal(""))
        addField?.setPlaceholder(Text.translatable("cobblemarket.blacklist.add_placeholder"))
        addField?.setChangedListener { updatePokemonPreview(it) }
        addDrawableChild(addField)

        // 闪光三态循环按钮：物种输入框左侧
        shinyButton = NineSliceButton(centerX - 101, dialogY + 30, 20, 16, Text.literal(""), { cycleShiny() })
        addDrawableChild(shinyButton)
        updateShinyButton()

        // 形态选择按钮：只有解析出多形态物种时显示，点击展开/收起形态列表
        formButton = NineSliceButton(centerX - 80, dialogY + 48, 140, 14, Text.literal(""), { toggleFormList() })
        formButton?.visible = false
        addDrawableChild(formButton)

        // 特训限定按钮（两态：不限 ↔ 不含特训），形态按钮下方留 4px 空隙
        ruleHtButton = NineSliceButton(centerX - 80, dialogY + 66, 140, 14, ruleHtButtonText(), { toggleRuleHtFilter() })
        addDrawableChild(ruleHtButton)

        ivHpField = createIvField(centerX - 75, dialogY + 84, "HP")
        ivAtkField = createIvField(centerX - 23, dialogY + 84, "ATK")
        ivDefField = createIvField(centerX + 29, dialogY + 84, "DEF")
        ivSpAtkField = createIvField(centerX - 75, dialogY + 106, "SPA")
        ivSpDefField = createIvField(centerX - 23, dialogY + 106, "SPD")
        ivSpdField = createIvField(centerX + 29, dialogY + 106, "SPE")

        addConfirmButton = NineSliceButton(
            centerX - 85, dialogY + 128, 80, 20,
            Text.translatable("cobblemarket.blacklist.add"),
            { confirmPokemonAdd() }
        )
        addDrawableChild(addConfirmButton)
        addCancelButton = NineSliceButton(
            centerX + 5, dialogY + 128, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeDialog() }
        )
        addDrawableChild(addCancelButton)

        // 编辑模式预填：物种 ID 经 text setter 触发 updatePokemonPreview 解析出形态选项
        if (entry != null) {
            shinyFilter = entry.shinyFilter
            updateShinyButton()
            addField?.text = entry.speciesId
            // text setter 已触发 updatePokemonPreview 重建形态选项，恢复保存的形态选择与预览
            val savedFormIndex = formOptions.indexOfFirst { it.aspects == entry.aspects.toSet() }
            if (savedFormIndex >= 0) {
                formIndex = savedFormIndex
                formButton?.setMessage(formButtonText())
                refreshPreviewModel()
            }
            if (entry.ivHp >= 0) ivHpField?.text = entry.ivHp.toString()
            if (entry.ivAtk >= 0) ivAtkField?.text = entry.ivAtk.toString()
            if (entry.ivDef >= 0) ivDefField?.text = entry.ivDef.toString()
            if (entry.ivSpAtk >= 0) ivSpAtkField?.text = entry.ivSpAtk.toString()
            if (entry.ivSpDef >= 0) ivSpDefField?.text = entry.ivSpDef.toString()
            if (entry.ivSpd >= 0) ivSpdField?.text = entry.ivSpd.toString()
            ruleHtFilter = entry.htFilter
            ruleHtButton?.setMessage(ruleHtButtonText())
        }
    }

    private fun createIvField(x: Int, y: Int, placeholder: String): TextFieldWidget {
        val field = TextFieldWidget(textRenderer, x, y, 46, 16, Text.literal(""))
        field.setPlaceholder(Text.literal(placeholder))
        field.setTextPredicate { it.length <= 2 && it.all { c -> c.isDigit() } }
        addDrawableChild(field)
        return field
    }

    private fun renderPokemonDialogBackground(context: DrawContext, delta: Float) {
        val centerX = width / 2
        val dialogW = 220
        val dialogH = 180
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.blacklist.add_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        // 特训限定按钮说明：位于形态按钮下方（dialogY+62），iv_hint 位置被占用，已移除

        // 精灵预览槽位
        val slotSize = 28
        val slotX = centerX + 66
        val slotY = dialogY + 24
        context.matrices.push()
        context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
        context.matrices.scale(slotSize / 66f, slotSize / 66f, 1f)
        context.drawTexture(SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
        context.matrices.pop()

        previewRenderable?.let { rp ->
            val matrices = context.matrices
            matrices.push()
            try {
                context.enableScissor(slotX - 4, slotY - 4, slotX + slotSize + 4, slotY + slotSize + 4)
                matrices.translate(slotX + slotSize / 2.0, slotY + 1.0, 0.0)
                matrices.scale(slotSize / 25f * 2.5f, slotSize / 25f * 2.5f, 1f)
                // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
                val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                    com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
                drawProfilePokemon(
                    renderablePokemon = rp,
                    matrixStack = matrices,
                    rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                    state = previewState,
                    partialTicks = if (useFloat) delta else 0f,
                    scale = 4.5f
                )
            } catch (_: Exception) {
            } finally {
                context.disableScissor()
                matrices.pop()
            }
        }
    }

    private fun confirmPokemonAdd() {
        val input = addField?.text?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            playFailSound()
            return
        }
        // 优先发送客户端本地解析出的物种 ID（中文输入在客户端解析）；
        // 服务端只有英文环境，发原文会导致中文名解析失败被静默丢弃
        val speciesInput = previewSpecies?.resourceIdentifier?.toString() ?: input
        sendToServer(AddPokemonBlacklistPayload(
            speciesId = speciesInput,
            ivHp = parseIv(ivHpField?.text),
            ivAtk = parseIv(ivAtkField?.text),
            ivDef = parseIv(ivDefField?.text),
            ivSpAtk = parseIv(ivSpAtkField?.text),
            ivSpDef = parseIv(ivSpDefField?.text),
            ivSpd = parseIv(ivSpdField?.text),
            aspects = formOptions.getOrNull(formIndex)?.aspects?.toList() ?: emptyList(),
            shinyFilter = shinyFilter,
            htFilter = ruleHtFilter,
            // 编辑模式带原条目：服务端先删旧再插新（改了形态等字段也不会残留旧条目）
            originalId = editingEntry?.id
        ))
        closeDialog()
    }

    private fun ruleHtButtonText(): Text = Text.translatable(
        if (ruleHtFilter == PokemonBlacklistEntry.HT_ANY) "cobblemarket.gui.filter_ht_any" else "cobblemarket.gui.filter_ht_off"
    )

    private fun toggleRuleHtFilter() {
        // 两态互切：不限(0) ↔ 不含特训(2)
        ruleHtFilter = if (ruleHtFilter == PokemonBlacklistEntry.HT_ANY) PokemonBlacklistEntry.HT_NONE else PokemonBlacklistEntry.HT_ANY
        ruleHtButton?.setMessage(ruleHtButtonText())
    }

    private fun shinyLabel(state: Int): String = when (state) {
        PokemonBlacklistEntry.SHINY_YES -> Text.translatable("cobblemarket.gui.shiny_yes").string
        PokemonBlacklistEntry.SHINY_NO -> Text.translatable("cobblemarket.gui.shiny_no").string
        else -> Text.translatable("cobblemarket.gui.shiny_any").string
    }

    private fun alphaLabel(state: Int): String = ""

    // 特训维度标签（tooltip 用）：不限 / 不含特训
    private fun htLabel(filter: Int): String = Text.translatable(
        if (filter == PokemonBlacklistEntry.HT_ANY) "cobblemarket.gui.filter_ht_any" else "cobblemarket.gui.filter_ht_off"
    ).string

    // 按钮只显示符号：★ = 仅闪光（金色），☆ = 仅非闪光，不限 = 默认文字（行显示/tooltip 仍用完整词）
    private fun shinyButtonText(): String = when (shinyFilter) {
        PokemonBlacklistEntry.SHINY_YES -> "★"
        PokemonBlacklistEntry.SHINY_NO -> "☆"
        else -> Text.translatable("cobblemarket.gui.shiny_any").string
    }

    private fun updateShinyButton() {
        shinyButton?.setMessage(Text.literal(shinyButtonText()))
        shinyButton?.textColor = if (shinyFilter == PokemonBlacklistEntry.SHINY_YES) GOLD_COLOR else 0xFFFFFF
    }

    private fun cycleShiny() {
        shinyFilter = when (shinyFilter) {
            PokemonBlacklistEntry.SHINY_ANY -> PokemonBlacklistEntry.SHINY_YES
            PokemonBlacklistEntry.SHINY_YES -> PokemonBlacklistEntry.SHINY_NO
            else -> PokemonBlacklistEntry.SHINY_ANY
        }
        updateShinyButton()
        // 预览模型同步闪光状态
        refreshPreviewModel()
    }

    private fun parseIv(text: String?): Int {
        val t = text?.trim()
        if (t.isNullOrEmpty()) return -1
        return t.toIntOrNull()?.coerceIn(0, 31) ?: -1
    }

    // ── 精灵：物种解析预览（照搬原精灵黑名单） ──

    private fun updatePokemonPreview(text: String) {
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
            //   ["*"] = 全部形态（显式全封）
            //   []    = 默认形态（标准形态无 aspect 声明且物种有 forms 时提供）
            //   [x..] = 各 form 的 aspects
            val seen = mutableSetOf<Set<String>>(setOf(PokemonBlacklistEntry.ALL_FORMS))
            val opts = mutableListOf(FormOption(Text.translatable("cobblemarket.blacklist.form_all").string, setOf(PokemonBlacklistEntry.ALL_FORMS)))
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
            ?.filter { it != PokemonBlacklistEntry.ALL_FORMS }?.toMutableSet() ?: mutableSetOf()
        if (shinyFilter == PokemonBlacklistEntry.SHINY_YES) aspects.add("shiny")
        previewRenderable = RenderablePokemon(species, aspects, ItemStack.EMPTY)
    }

    // aspect 显示名：走本模组翻译表，未收录的 aspect 显示原文
    private fun aspectLabel(aspect: String): String {
        val t = Text.translatable("cobblemarket.aspect.$aspect")
        return if (t.string == "cobblemarket.aspect.$aspect") aspect else t.string
    }

    private fun formLabel(aspects: Set<String>): String = when {
        PokemonBlacklistEntry.ALL_FORMS in aspects -> Text.translatable("cobblemarket.blacklist.form_all").string
        aspects.isEmpty() -> Text.translatable("cobblemarket.blacklist.form_default").string
        else -> aspects.joinToString("/") { aspectLabel(it) }
    }

    private fun formButtonText() = Text.literal(
        com.shusheng.cobblemarket.util.TextUtil.truncateString(
            "${Text.translatable("cobblemarket.blacklist.form").string}: ${formOptions.getOrNull(formIndex)?.label ?: ""}",
            132
        )
    )

    private fun toggleFormList() {
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
    // 展开时隐藏被列表覆盖的控件（IV 输入框 + 确认/取消按钮）——它们先于形态选项添加，
    // MC 点击遍历按添加顺序，不隐藏的话点击会被它们拦截，形态选项永远点不到。
    private fun rebuildFormList() {
        formOptionButtons.forEach { remove(it) }
        formOptionButtons.clear()
        val ivFields = listOf(ivHpField, ivAtkField, ivDefField, ivSpAtkField, ivSpDefField, ivSpdField)
        ivFields.forEach { it?.visible = !formListOpen }
        addConfirmButton?.visible = !formListOpen
        addCancelButton?.visible = !formListOpen
        // 形态列表展开时覆盖特训按钮位置（dialogY+62 起），隐藏避免点击拦截
        ruleHtButton?.visible = !formListOpen
        if (!formListOpen) return
        val centerX = width / 2
        val dialogY = height / 2 - 90
        formOptions.drop(formListScroll).take(MAX_FORM_LIST_ROWS).forEachIndexed { i, opt ->
            val idx = formListScroll + i
            // 数据包自创的超长 aspect 名截断，防止溢出按钮
            val label = com.shusheng.cobblemarket.util.TextUtil.truncateString(opt.label, 124)
            val btn = NineSliceButton(
                centerX - 80, dialogY + 62 + i * 16, 140, 14,
                if (idx == formIndex) com.shusheng.cobblemarket.util.TextUtil.selectedText(label) else Text.literal(label),
                { selectForm(idx) }
            )
            formOptionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    private fun resolveByChineseName(name: String): Species? {
        return try {
            PokemonSpecies.implemented.firstOrNull { it.translatedName.string == name || it.translatedName.string.contains(name) }
        } catch (_: Exception) {
            null
        }
    }

    // ── 物品对话框 ──

    private fun openItemDialog() {
        hideMainControls()
        heldAddMode = false
        val centerX = width / 2
        val dialogY = height / 2 - 71

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderItemDialogBackground(context)
                // 手持添加按钮悬停提示（小手图标，功能说明见词条）
                heldAddButton?.takeIf { it.isHovered }?.let {
                    drawTooltip(context, listOf(Text.translatable("cobblemarket.blacklist.add_held_item").string), mouseX, mouseY)
                }
            }
        })

        // 从手持物品添加（组件粒度）：搜索框左侧小手图标按钮，点击变红选中（获取手持物品），
        // 再点「添加」确认才提交；服务端读主手物品提取组件快照
        heldAddButton = NineSliceButton(
            centerX - 104, dialogY + 40, 16, 16,
            Text.literal(""),
            { toggleHeldAdd() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/hand.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        )
        addDrawableChild(heldAddButton)

        addField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 40, 140, 16, Text.literal(""))
        addField?.setPlaceholder(Text.translatable("cobblemarket.blacklist.item_add_placeholder"))
        addField?.setChangedListener { updateItemPreview(it) }
        addDrawableChild(addField)

        // 物品选择按钮：点击展开匹配列表，点选具体物品
        itemSelectButton = NineSliceButton(centerX - 80, dialogY + 58, 140, 14, Text.literal(""), { toggleItemList() })
        itemSelectButton?.visible = false
        addDrawableChild(itemSelectButton)

        addConfirmButton = NineSliceButton(
            centerX - 85, dialogY + 82, 80, 20,
            Text.translatable("cobblemarket.blacklist.add"),
            { confirmItemAdd() }
        )
        addDrawableChild(addConfirmButton)
        addCancelButton = NineSliceButton(
            centerX + 5, dialogY + 82, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeDialog() }
        )
        addDrawableChild(addCancelButton)

        // 批量拉黑：匹配多个时显示（如蛋的全部属性变体）
        batchAddButton = NineSliceButton(
            centerX - 40, dialogY + 106, 80, 20,
            Text.translatable("cobblemarket.blacklist.add_all"),
            { confirmAddAll() }
        )
        batchAddButton?.visible = false
        addDrawableChild(batchAddButton)
    }

    private fun confirmAddAll() {
        if (matchedItems.isEmpty()) {
            playFailSound()
            return
        }
        sendToServer(com.shusheng.cobblemarket.network.AddItemsBlacklistPayload(matchedItems))
        closeDialog()
    }

    private fun renderItemDialogBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogW = 220
        val dialogH = 142
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.blacklist.add_item_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)
        // 标题下提示：搜不到的物品可输入真实 id（F3+H 显示高级提示框）
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.gui.item_id_hint").string,
            centerX, dialogY + 26, 0xFFAAAAAA.toInt())

        // 本地校验错误/信息提示：画在弹窗下沿外，避免与 +40 起的物品输入框/控件重叠
        dialogError?.let {
            context.drawCenteredTextWithShadow(textRenderer, it, centerX, dialogY + dialogH + 8, dialogErrorColor)
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

    private fun confirmItemAdd() {
        // 手持模式：直接提交手持物品条目（组件快照由服务端读主手提取）
        if (heldAddMode) {
            sendToServer(com.shusheng.cobblemarket.network.AddHeldItemBlacklistPayload())
            closeDialog()
            return
        }
        val input = addField?.text?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            playFailSound()
            return
        }
        // 优先发送用户点选的物品 ID；未点选时回退到自动解析（唯一匹配/原文）
        val selected = matchedItems.getOrNull(selectedItemIndex)
        sendToServer(AddItemBlacklistPayload(selected ?: resolveMatchingItems(input).firstOrNull() ?: input))
        closeDialog()
    }

    private fun toggleHeldAdd() {
        // 预检主手：空手播 fail 音效 + 弹窗下沿红字提醒，不切换模式；服务端红字提示仅兜底
        val held = client?.player?.mainHandStack
        if (held == null || held.isEmpty) {
            dialogError = Text.translatable("cobblemarket.blacklist.held_item_empty").string
            dialogErrorColor = 0xFF5555
            playFailSound()
            return
        }
        heldAddMode = !heldAddMode
        // 选中态 = 按下视觉（红底），与求购单手持按钮一致；绿字提示明确当前状态与物品
        heldAddButton?.pressedVisual = heldAddMode
        dialogError = if (heldAddMode)
            Text.translatable("cobblemarket.blacklist.held_item_selected", held.name).string
        else
            Text.translatable("cobblemarket.blacklist.held_item_cancelled").string
        dialogErrorColor = 0x55FF55
    }

    // 收集全部匹配物品（优先级：ID 路径精确 > 翻译名精确 > 翻译名包含），保持注册表顺序稳定
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
        // 搜索索引追加（TM 招式/附魔/tooltip 文本命中，见 ItemSearchIndex；精确匹配仍排前）
        com.shusheng.cobblemarket.client.ItemSearchIndex.itemIdsMatching(input).forEach { result.add(it) }
        return result.toList()
    }

    private fun itemDisplay(itemId: String): String {
        val id = Identifier.tryParse(itemId) ?: return itemId
        val item = Registries.ITEM.get(id)
        val name = item.name.string
        // 无翻译的物品（第三方模组缺 lang）会显示翻译 key 原文（超长难读），fallback 到资源路径
        return if (name == item.translationKey) id.path else name
    }

    /** 物品输入变更：重建匹配列表（照搬原物品黑名单） */
    private fun updateItemPreview(text: String) {
        dialogError = null
        matchedItems = resolveMatchingItems(text)
        // 唯一匹配自动选中；多匹配等待用户点选
        selectedItemIndex = if (matchedItems.size == 1) 0 else -1
        itemListOpen = false
        itemListScroll = 0
        rebuildItemList()
        updateItemSelectButton()
        batchAddButton?.visible = matchedItems.size > 1
        previewItemId = matchedItems.getOrNull(selectedItemIndex) ?: matchedItems.firstOrNull()
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
        rebuildItemList()
        updateItemSelectButton()
        previewItemId = matchedItems.getOrNull(idx)
    }

    // 展开的匹配列表：与精灵形态列表同模式，展开时隐藏被覆盖的确认/取消按钮
    private fun rebuildItemList() {
        itemOptionButtons.forEach { remove(it) }
        itemOptionButtons.clear()
        addConfirmButton?.visible = !itemListOpen
        addCancelButton?.visible = !itemListOpen
        batchAddButton?.visible = !itemListOpen && matchedItems.size > 1
        if (!itemListOpen) return
        val centerX = width / 2
        val dialogY = height / 2 - 71
        matchedItems.drop(itemListScroll).take(MAX_ITEM_LIST_ROWS).forEachIndexed { i, itemId ->
            val idx = itemListScroll + i
            val label = com.shusheng.cobblemarket.util.TextUtil.truncateString(itemDisplay(itemId), 124)
            val btn = NineSliceButton(
                centerX - 80, dialogY + 72 + i * 16, 140, 14,
                if (idx == selectedItemIndex) com.shusheng.cobblemarket.util.TextUtil.selectedText(label) else Text.literal(label),
                { selectItem(idx) }
            )
            itemOptionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    private fun closeDialog() {
        addField = null
        editingEntry = null
        previewRenderable = null
        previewSpecies = null
        formOptions = listOf()
        formIndex = 0
        formButton = null
        formListOpen = false
        formListScroll = 0
        formOptionButtons.clear()
        shinyFilter = PokemonBlacklistEntry.SHINY_ANY
        shinyButton = null
        ruleHtFilter = PokemonBlacklistEntry.HT_ANY
        ruleHtButton = null
        ivHpField = null
        ivAtkField = null
        ivDefField = null
        ivSpAtkField = null
        ivSpDefField = null
        ivSpdField = null
        previewItemId = null
        matchedItems = listOf()
        selectedItemIndex = -1
        itemListOpen = false
        itemListScroll = 0
        itemOptionButtons.clear()
        itemSelectButton = null
        addConfirmButton = null
        batchAddButton = null
        heldAddButton = null
        heldAddMode = false
        addCancelButton = null
        dialogError = null
        clearChildren()
        init()
    }

    // ── 数据接收 ──

    fun onBlacklistData(payload: PokemonBlacklistDataPayload) {
        pokemonEntries = payload.entries
        tooltipCacheKey = null
        rebuildFiltered()
        cacheIcons()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, pokemonEntries.size - getMaxVisibleRows()))
        rebuildRemoveButtons()
    }

    fun onItemBlacklistData(payload: ItemBlacklistDataPayload) {
        itemEntries = payload.entries
        tooltipCacheKey = null
        itemTooltipCacheKey = null
        rebuildItemIconStacks()
        rebuildFiltered()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, itemEntries.size - getMaxVisibleRows()))
        // 数据到达后刷新全部解封按钮可见性（原物品界面漏了这一步：搜索词先于数据到达时按钮不显示）
        updateUnbanAllButton()
        rebuildRemoveButtons()
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
            if (entry.shinyFilter == PokemonBlacklistEntry.SHINY_YES) aspects.add("shiny")
            iconData[index] = IconData(com.shusheng.cobblemarket.util.SpeciesText.displayName(species), RenderablePokemon(species, aspects, ItemStack.EMPTY), FloatingState())
        }
    }

    private fun renderPokemonIcon(context: DrawContext, index: Int, x: Int, y: Int, size: Int, dark: Boolean = false, delta: Float = 0f) {
        val data = iconData[index] ?: return
        val matrices = context.matrices
        matrices.push()
        try {
            context.enableScissor(x - 4, y - 4, x + size + 4, y + size + 4)
            matrices.translate(x + size / 2.0, y + 1.0, 0.0)
            matrices.scale(size / 25f * 2.5f, size / 25f * 2.5f, 1f)
            // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
            val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
            drawProfilePokemon(
                renderablePokemon = data.renderable,
                matrixStack = matrices,
                rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                state = data.state,
                partialTicks = if (useFloat) delta else 0f,
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

    private fun filteredPokemon(): List<PokemonBlacklistEntry> = filteredPokemonCache

    /** 数据/搜索/筛选变化时重建过滤缓存（render 每帧只读）；
     *  索引/行显示字符串随过滤一并重建，行渲染不再每帧 indexOf / 拼文本 */
    private fun rebuildFiltered() {
        val query = searchField?.text?.trim()?.takeIf { it.isNotEmpty() }
        filteredPokemonIndexed = pokemonEntries.mapIndexedNotNull { index, entry ->
            val species = Identifier.tryParse(entry.speciesId)?.let { PokemonSpecies.getByIdentifier(it) }
            val name = species?.translatedName?.string ?: entry.speciesId
            if ((query == null || name.contains(query, ignoreCase = true) || entry.speciesId.contains(query, ignoreCase = true)) &&
                // 特训筛选：0 = 不限，2 = 不含特训
                (listHtFilter == 0 || entry.htFilter == listHtFilter)
            ) IndexedValue(index, entry) else null
        }
        filteredPokemonCache = filteredPokemonIndexed.map { it.value }
        pokemonRowTexts = filteredPokemonCache.map { pokemonEntryDisplay(it) }
        // 搜索索引匹配（itemId + 名称 + tooltip 文本 + TM 招式，见 ItemSearchIndex；原版创造模式同款语义）
        // 条目级精确过滤：带组件条目按组件精确（搜「打鼾」只出打鼾 TM 条目），无组件条目仅物品文本命中
        filteredItemsCache = if (query == null) itemEntries else {
            itemEntries.filter { com.shusheng.cobblemarket.client.ItemSearchIndex.ruleEntryMatches(it.itemId, it.componentsSpec, query) }
        }
        itemRowTexts = filteredItemsCache.map { itemEntryDisplay(it) }
    }

    private fun htFilterButtonText(): Text = Text.translatable(
        if (listHtFilter == PokemonBlacklistEntry.HT_ANY) "cobblemarket.gui.filter_ht_any" else "cobblemarket.gui.filter_ht_off"
    )

    private fun toggleListHtFilter() {
        // 两态互切：不限(0) ↔ 不含特训(2)
        listHtFilter = if (listHtFilter == PokemonBlacklistEntry.HT_ANY) PokemonBlacklistEntry.HT_NONE else PokemonBlacklistEntry.HT_ANY
        htFilterButton?.setMessage(htFilterButtonText())
        htFilterButton?.textColor = if (listHtFilter != 0) GOLD_COLOR else 0xFFFFFF
        rebuildFiltered()
        scrollOffset = 0
        hoveredRow = -1
        rebuildRemoveButtons()
    }

    private fun filteredItems(): List<ItemBlacklistEntry> = filteredItemsCache

    private fun displayCount(): Int = if (currentTab == 0) filteredPokemon().size else filteredItems().size

    // ── 行按钮（删除） ──

    private fun rebuildRemoveButtons() {
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
                val btn = NineSliceButton(
                    leftX + panelWidth - 50, y + 4, 44, 16,
                    Text.translatable("cobblemarket.blacklist.remove"),
                    { sendToServer(RemovePokemonBlacklistPayload(entry.id)) }
                )
                btn.visible = addField == null
                removeButtons.add(btn)
                addDrawableChild(btn)
            }
        } else {
            filteredItems().drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, entry ->
                val y = startY + i * rowHeight
                val btn = NineSliceButton(
                    leftX + panelWidth - 50, y + 4, 44, 16,
                    Text.translatable("cobblemarket.blacklist.remove"),
                    { sendToServer(RemoveItemBlacklistPayload(entry.itemId, entry.componentsSpec)) }
                )
                btn.visible = addField == null
                removeButtons.add(btn)
                addDrawableChild(btn)
            }
        }
    }

    private fun pokemonEntryDisplay(entry: PokemonBlacklistEntry): String {
        val species = Identifier.tryParse(entry.speciesId)?.let { PokemonSpecies.getByIdentifier(it) }
        val name = species?.let { com.shusheng.cobblemarket.util.SpeciesText.displayName(it) } ?: entry.speciesId
        val parts = mutableListOf<String>()
        if (entry.shinyFilter != PokemonBlacklistEntry.SHINY_ANY) parts.add(shinyLabel(entry.shinyFilter))
        // 形态标签：全部形态/默认形态/具体 aspect，始终显示
        parts.add(formLabel(entry.aspects.toSet()))
        if (entry.ivHp >= 0) parts.add("HP${entry.ivHp}")
        if (entry.ivAtk >= 0) parts.add("${Text.translatable("cobblemon.stat.attack.name").string}${entry.ivAtk}")
        if (entry.ivDef >= 0) parts.add("${Text.translatable("cobblemon.stat.defence.name").string}${entry.ivDef}")
        if (entry.ivSpAtk >= 0) parts.add("${Text.translatable("cobblemon.stat.special_attack.name").string}${entry.ivSpAtk}")
        if (entry.ivSpDef >= 0) parts.add("${Text.translatable("cobblemon.stat.special_defence.name").string}${entry.ivSpDef}")
        if (entry.ivSpd >= 0) parts.add("${Text.translatable("cobblemon.stat.speed.name").string}${entry.ivSpd}")
        return if (parts.isEmpty()) name else "$name（${parts.joinToString(" ")}）"
    }

    private fun itemEntryDisplay(entry: ItemBlacklistEntry): String {
        val name = itemDisplay(entry.itemId)
        // 带组件快照的条目追加摘要（附魔名+等级等）；主列表条目区宽约 210px，超长名截断防止与删除按钮重叠
        val summary = com.shusheng.cobblemarket.client.ItemComponentsDisplay.summary(entry.componentsSpec)
        val full = if (summary.isEmpty()) name else "$name（$summary）"
        return com.shusheng.cobblemarket.util.TextUtil.truncateString(full, 210)
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
            Text.translatable("cobblemarket.op.blacklist").formatted(Formatting.GOLD),
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
                Text.translatable("cobblemarket.blacklist.empty").formatted(Formatting.GRAY),
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
                // 弹窗打开时不渲染 3D（模型层在衬底之上，压暗仍会浮在弹窗上）
                if (addField == null) renderPokemonIcon(context, origIndex, slotX, slotY, iconSize, delta = delta)
                // 截断防止超长条目（六项个体值全填）与编辑/删除按钮重叠；完整信息在悬停 tooltip
                context.drawTextWithShadow(textRenderer,
                    com.shusheng.cobblemarket.util.TextUtil.truncateString(rowTexts[scrollOffset + i], 170),
                    leftX + 28, y + 7, 0xFFFFFF)
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
                    itemIconStacks[entry]?.let { context.drawItem(it, leftX + 4, y + 4) }
                }
                context.drawTextWithShadow(textRenderer, rowTexts[scrollOffset + i], leftX + 24, y + 7, 0xFFFFFF)
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

    private fun renderPokemonTooltip(context: DrawContext, entry: PokemonBlacklistEntry, mouseX: Int, mouseY: Int) {
        // 文本行缓存：悬停同一行时内容不变，只在悬停目标变化时重建（见 tooltipCacheKey 注释）
        if (tooltipCacheKey != entry.id) {
            tooltipCacheKey = entry.id
            tooltipCacheLines = buildPokemonTooltipLines(entry)
        }
        drawTooltip(context, tooltipCacheLines, mouseX, mouseY)
    }

    private fun buildPokemonTooltipLines(entry: PokemonBlacklistEntry): List<String> {
        val species = Identifier.tryParse(entry.speciesId)?.let { PokemonSpecies.getByIdentifier(it) }
        val name = species?.let { com.shusheng.cobblemarket.util.SpeciesText.displayName(it) } ?: entry.speciesId
        val lines = mutableListOf(name)
        lines.add(shinyLabel(entry.shinyFilter))
        lines.add(formLabel(entry.aspects.toSet()))
        lines.add(htLabel(entry.htFilter))
        if (entry.ivHp >= 0) lines.add("${Text.translatable("cobblemon.stat.hp.name").string}: ${entry.ivHp}")
        if (entry.ivAtk >= 0) lines.add("${Text.translatable("cobblemon.stat.attack.name").string}: ${entry.ivAtk}")
        if (entry.ivDef >= 0) lines.add("${Text.translatable("cobblemon.stat.defence.name").string}: ${entry.ivDef}")
        if (entry.ivSpAtk >= 0) lines.add("${Text.translatable("cobblemon.stat.special_attack.name").string}: ${entry.ivSpAtk}")
        if (entry.ivSpDef >= 0) lines.add("${Text.translatable("cobblemon.stat.special_defence.name").string}: ${entry.ivSpDef}")
        if (entry.ivSpd >= 0) lines.add("${Text.translatable("cobblemon.stat.speed.name").string}: ${entry.ivSpd}")
        return lines
    }

    private fun renderItemTooltip(context: DrawContext, entry: ItemBlacklistEntry, mouseX: Int, mouseY: Int) {
        // 照物品市场悬浮：真实物品词条（BASIC 常驻 / Shift 完整词条 / Ctrl 调试信息，Text 保留词条自带颜色）+ itemId 行
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
        drawRichTooltip(context, advanced ?: itemTooltipLines, mouseX, mouseY)
    }

    private fun buildItemTooltipLines(entry: ItemBlacklistEntry, type: TooltipType): List<Pair<Text, Int>> {
        val lines = mutableListOf<Pair<Text, Int>>()
        val stack = itemIconStacks[entry]
        if (stack != null) {
            lines.addAll(com.shusheng.cobblemarket.client.ItemComponentsDisplay.itemTooltip(stack, client?.player, type).map { it to 0xFFFFFF })
        } else {
            lines.add(Text.literal(itemEntryDisplay(entry)) to 0xFFFFFF)
        }
        lines.add(Text.literal(entry.itemId).formatted(Formatting.DARK_GRAY) to 0xFFFFFF)
        return lines
    }

    /** 富文本行 tooltip 渲染（照物品市场：Text 自带颜色样式优先于行色参数） */
    private fun drawRichTooltip(context: DrawContext, lines: List<Pair<Text, Int>>, mouseX: Int, mouseY: Int) {
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
        // 形态列表展开且超过可见行数时，滚轮滚动形态列表
        if (formListOpen && formOptions.size > MAX_FORM_LIST_ROWS) {
            formListScroll = (formListScroll - verticalAmount.toInt())
                .coerceIn(0, formOptions.size - MAX_FORM_LIST_ROWS)
            rebuildFormList()
            return true
        }
        // 匹配列表展开且超过可见行数时，滚轮滚动匹配列表
        if (itemListOpen && matchedItems.size > MAX_ITEM_LIST_ROWS) {
            itemListScroll = (itemListScroll - verticalAmount.toInt())
                .coerceIn(0, matchedItems.size - MAX_ITEM_LIST_ROWS)
            rebuildItemList()
            return true
        }
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        rebuildRemoveButtons()
        return true
    }

    private fun isInputFieldFocused() = focused?.let { f ->
        f === searchField || f === addField || f === ivHpField || f === ivAtkField || f === ivDefField ||
        f === ivSpAtkField || f === ivSpDefField || f === ivSpdField
    } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true ||
        addField?.isMouseOver(mouseX, mouseY) == true ||
        ivHpField?.isMouseOver(mouseX, mouseY) == true ||
        ivAtkField?.isMouseOver(mouseX, mouseY) == true ||
        ivDefField?.isMouseOver(mouseX, mouseY) == true ||
        ivSpAtkField?.isMouseOver(mouseX, mouseY) == true ||
        ivSpDefField?.isMouseOver(mouseX, mouseY) == true ||
        ivSpdField?.isMouseOver(mouseX, mouseY) == true

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
        val hp = ivHpField?.text ?: ""
        val atk = ivAtkField?.text ?: ""
        val def = ivDefField?.text ?: ""
        val spa = ivSpAtkField?.text ?: ""
        val spd = ivSpDefField?.text ?: ""
        val spe = ivSpdField?.text ?: ""
        val savedFormIndex = formIndex
        val savedShinyFilter = shinyFilter
        val savedRuleHtFilter = ruleHtFilter
        val savedSelectedItemIndex = selectedItemIndex
        super.resize(client, width, height)
        if (wasOpen) {
            addField = null
            if (wasPokemonDialog) {
                openPokemonDialog(editingEntry)
                addField?.text = name
                ivHpField?.text = hp
                ivAtkField?.text = atk
                ivDefField?.text = def
                ivSpAtkField?.text = spa
                ivSpDefField?.text = spd
                ivSpdField?.text = spe
                shinyFilter = savedShinyFilter
                updateShinyButton()
                ruleHtFilter = savedRuleHtFilter
                ruleHtButton?.setMessage(ruleHtButtonText())
                // 文本恢复已触发 updatePokemonPreview 重建形态选项，这里恢复索引与预览；形态列表保持收起
                formListOpen = false
                rebuildFormList()
                if (formOptions.size > 1) {
                    formIndex = savedFormIndex.coerceIn(0, formOptions.size - 1)
                    formButton?.visible = true
                    formButton?.setMessage(formButtonText())
                }
                refreshPreviewModel()
            } else {
                openItemDialog()
                addField?.text = name
                // 文本恢复已触发 updateItemPreview 重建匹配列表，这里恢复选中项与预览
                if (savedSelectedItemIndex in matchedItems.indices) {
                    selectedItemIndex = savedSelectedItemIndex
                    updateItemSelectButton()
                    previewItemId = matchedItems.getOrNull(selectedItemIndex)
                }
            }
        }
    }

    override fun shouldPause() = false

    private companion object {
        val SLOT_TEXTURE = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
    }
}
