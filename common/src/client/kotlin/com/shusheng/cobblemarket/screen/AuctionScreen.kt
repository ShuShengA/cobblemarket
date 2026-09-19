package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.playFailSound

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.network.AuctionEntry
import com.shusheng.cobblemarket.network.AuctionEventPayload
import com.shusheng.cobblemarket.network.AuctionListDataPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PlaceBidPayload
import com.shusheng.cobblemarket.network.RequestAuctionListPayload
import com.shusheng.cobblemarket.network.RequestBalancePayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.sound.SoundEvent
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
import com.mojang.authlib.GameProfile
import java.util.UUID
import com.shusheng.cobblemarket.client.drawItemWithBar

/**
 * 拍卖大厅：精灵 / 物品 / 我的 三个 tab。
 * 数据：全量 ACTIVE 快照 + 服务端增量事件（NEW/BID/SETTLED）合并刷新，倒计时每帧实时计算。
 */
class AuctionScreen(
    private val initialTab: Int = 0,
    /** 聊天播报点击直达：打开界面后列表到达时自动弹出该拍品的出价弹窗（找不到则静默等待下次刷新） */
    private val initialBidAuctionId: java.util.UUID? = null
) : Screen(Text.translatable("cobblemarket.auction.title")) {

    private val panelWidth = 296
    private val rowHeight = 24

    private var currentTab = initialTab.coerceIn(0, 2) // 0 = 精灵, 1 = 物品, 2 = 我的
    // 特训筛选三态：0 = 不限，1 = 仅含训练，2 = 仅不含训练（仅精灵 tab）
    private var htFilter = 0
    private var htButton: NineSliceButton? = null
    private var entries = listOf<AuctionEntry>()
    // 过滤结果缓存：数据/搜索/筛选变化时重建，render 每帧只读（避免每帧全量 filter+sort）
    private var filteredCache = listOf<AuctionEntry>()
    // 带索引列表缓存（IndexedValue.index = entries 中的原始索引）：render 行循环每帧 entries.indexOf 是扫描热点，重建过滤缓存时同步构建
    private var indexedFiltered = listOf<IndexedValue<AuctionEntry>>()
    private var searchField: TextFieldWidget? = null
    // 筛选（仅精灵 tab；拍卖列表全量下发，客户端过滤）：性别/属性/特性/性格
    private var genderFilter = ""
    private var typeFilter = ""
    private var abilityFilter = ""
    private var natureFilter = ""
    private var filterListOpen = "" // ""/type/ability/nature；互斥展开
    private var filterListScroll = 0
    private val filterOptionButtons = mutableListOf<NineSliceButton>()
    private var abilityOptions = listOf<Pair<String, String>>()
    private var genderButton: NineSliceButton? = null
    private var typeButton: NineSliceButton? = null
    private var abilityButton: NineSliceButton? = null
    private var natureButton: NineSliceButton? = null
    private var hoveredRow = -1
    private var scrollOffset = 0
    private var backButton: NineSliceButton? = null
    private var createButton: NineSliceButton? = null
    private var rulesButton: NineSliceButton? = null
    private val tabButtons = mutableListOf<NineSliceButton>()
    private val bidButtons = mutableListOf<NineSliceButton>()

    // 操作结果提示（服务端 MarketResult 到达后短暂显示）
    private var resultMsg: String? = null
    private var resultUntil = 0L

    // 到期结算轮询：存在到期未结算条目时周期重拉，触发服务端结算
    private var lastSettlePoll = 0L


    // ── 出价弹窗 ──
    private var bidEntry: AuctionEntry? = null
    /** 待定位的拍品（聊天点击直达）：列表数据每次更新后尝试打开出价弹窗，成功即清除 */
    private var pendingBidLocate: java.util.UUID? = initialBidAuctionId
    /** 出价弹窗物品词条行（附魔/名称等，弹窗打开时从 itemNbt 重建一次；去首行物品名——弹窗已显示） */
    private var bidItemTooltipLines: List<Text> = emptyList()
    /** Shift 展开的高级词条（弹窗打开时双份构建，渲染按 Shift 状态切换，照背包悬停） */
    private var bidItemAdvancedLines: List<Text> = emptyList()
    /** 出价弹窗高度扩展行数（物品词条超基线行数时弹窗加高 + 按钮下移，防 ADVANCED 展开溢出） */
    private var bidDialogExtraRows = 0
    /** 出价弹窗宽度（按词条最大行宽自适应，防 ADVANCED 横排行超框；精灵类型恒 280） */
    private var bidDialogW = 280
    /** 高级词条是否在 Shift 按住状态下构建（Fabric tooltip 的信息块只在构建时 Shift 按住才生成，松开后重建） */
    private var bidAdvancedBuiltWithShift = false
    private var bidAdvancedType: TooltipType? = null
    private var bidField: TextFieldWidget? = null
    /** 玩家是否手动编辑过出价输入：BID 广播只在未编辑时更新预填，不覆盖玩家输入 */
    private var bidEdited = false
    private var bidConfirmButton: NineSliceButton? = null
    // 出价校验失败提示（按钮下方红字，2 秒后消失；重新输入时清除）
    private var bidErrorText: net.minecraft.text.Text? = null
    private var bidErrorUntil = 0L
    private var bidCancelButton: NineSliceButton? = null
    private var bidRenderable: RenderablePokemon? = null
    private val bidPreviewState = FloatingState()

    // 精灵图标缓存
    private data class IconData(val displayName: String, val renderable: RenderablePokemon, val state: FloatingState)
    private val iconData = mutableMapOf<Int, IconData>()
    private val iconSize = 20

    // 行内物品栈缓存（球种/持有物/物品拍卖图标）：render 每帧 new ItemStack 是分配热点，数据到达时构建一次。
    // 与 iconData 分离：球种/持有物渲染不依赖物种解析成功，独立缓存保持与原逐帧解析完全一致的行为
    private data class RowStacks(val ballStack: ItemStack?, val heldStack: ItemStack?, val itemStack: ItemStack?)
    private val rowStacks = mutableMapOf<Int, RowStacks>()

    // 精灵悬停静态行缓存：内容只取决于条目（精灵信息不随出价变化），悬停同一行时每帧重建全部文本行是悬停掉帧主因；
    // 倒计时/当前价/领先者等动态行每帧单独构建（BID 事件替换条目对象后仍取最新值）
    private var tooltipCacheKey: UUID? = null
    private var tooltipCacheStaticLines: List<Pair<Text?, Int>> = emptyList()
    private var tooltipCacheHeldLine = -1
    private var tooltipCacheMaxWidth = 0
    /** 证章图标行在静态行列表中的索引（-1 = 无证章） */
    private var tooltipCacheMarksLine = -1

    // 物品悬停缓存（同 tooltipCacheKey 模式；行类型无 null 分割线，单独一组字段）
    private var itemTooltipCacheKey: UUID? = null
    private var itemTooltipCacheLines: List<Pair<Text, Int>> = emptyList()
    private var itemTooltipCacheMaxWidth = 0
    /** Shift 展开的高级词条缓存（按住 Shift 才按需构建；null = 未构建） */
    private var itemTooltipAdvancedLines: List<Pair<Text, Int>>? = null
    private var itemTooltipAdvancedType: TooltipType? = null
    private var itemTooltipAdvancedMaxWidth = 0

    // 精灵 tab：搜索框下方有性别/属性/特性/性格筛选按钮行，列表起点靠下；
    // 物品 tab 与「我的」tab **都没有**筛选按钮行（筛选控件只在精灵 tab 显示，见 applyFilterVisibility），
    // 所以列表起点贴近搜索框、分割线下面直接接列表
    private fun getListStartY() = if (currentTab == 0) 92 else 70
    private fun getMaxVisibleRows() = maxOf(0, (height - getListStartY() - 48) / rowHeight)

    // ── 文本工具 ──

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

    private fun formatRemaining(endsAt: Long): String {
        val ms = endsAt - System.currentTimeMillis()
        if (ms <= 0) return Text.translatable("cobblemarket.auction.ending").string
        val totalSec = ms / 1000
        val totalMin = totalSec / 60
        // 3 分钟内显示秒级：反狙击延长的效果清晰可见（1m59s → 2m0s）
        if (totalMin < 1) return "${totalSec}s"
        if (totalMin < 3) return "${totalMin}m${totalSec % 60}s"
        if (totalMin < 60) return "${totalMin}m"
        val h = totalMin / 60
        val m = totalMin % 60
        if (h < 24) return "${h}h${m}m"
        val d = h / 24
        val hh = h % 24
        return "${d}d${hh}h"
    }

    /** 价格文本：金额+单位金色；无出价时「起拍」前缀白色（2026-08-24 拍板：文字默认色） */
    private fun displayPriceText(entry: AuctionEntry): Text {
        val price = if (entry.currentPrice > 0) entry.currentPrice else entry.startingPrice
        val priceT = Text.literal(com.shusheng.cobblemarket.client.formatPrice(price) + " " + com.shusheng.cobblemarket.client.inlineCurrencyUnit()).formatted(Formatting.GOLD)
        return if (entry.currentPrice > 0) priceT
        else Text.translatable("cobblemarket.auction.from").append(priceT)
    }

    /** 行内价格（缩写、无「起拍」前缀，与物品市场/求购单行内一致）：悬停与出价弹窗仍走 [displayPriceText] 千分位 */
    private fun displayPriceCompactText(entry: AuctionEntry): Text {
        val price = if (entry.currentPrice > 0) entry.currentPrice else entry.startingPrice
        return Text.literal(com.shusheng.cobblemarket.client.formatPriceShort(price) + " " + com.shusheng.cobblemarket.client.inlineCurrencyUnit()).formatted(Formatting.GOLD)
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

    // 卖家头像（照搬精灵市场）
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

        val backBtn = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        backButton = backBtn
        addDrawableChild(backBtn)

        // tab 按钮 + 规则按钮：显示顺序 我的 | 精灵 | 物品 | 规则，四个按钮整体水平居中
        val tabW = 62
        val rulesW = 50
        val totalW = tabW * 3 + rulesW + 2 * 3
        val tabStart = centerX - totalW / 2
        tabButtons.clear()
        // 显示顺序：我的 | 精灵 | 物品（tabOrder 映射到真实 tab 索引，switchTab 语义不变）
        val tabOrder = listOf(2, 0, 1)
        tabOrder.forEachIndexed { i, tabIndex ->
            val btn = NineSliceButton(
                tabStart + i * (tabW + 2), 32, tabW, 14,
                Text.literal(""), { switchTab(tabIndex) }
            )
            tabButtons.add(btn)
            addDrawableChild(btn)
        }
        updateTabButtons()

        // 规则按钮：tab 组最后一位（悬停显示自绘规则面板，标题金/正文白/重点红/项间分割线）
        val rulesBtn = NineSliceButton(
            tabStart + 3 * (tabW + 2), 32, rulesW, 14,
            Text.translatable("cobblemarket.auction.rules"),
            { }
        )
        rulesButton = rulesBtn
        addDrawableChild(rulesBtn)

        val savedSearch = searchField?.text ?: ""
        searchField = TextFieldWidget(textRenderer, leftX + 2, 50, panelWidth - 4 - 52 - 20, 16, Text.translatable("cobblemarket.gui.search"))
        updateSearchPlaceholder()
        searchField?.setChangedListener { updateAbilityOptions(it); rebuildFiltered(); rebuildBidButtons() }
        addSelectableChild(searchField)
        addDrawableChild(searchField)
        searchField?.text = savedSearch

        val createBtn = NineSliceButton(
            leftX + panelWidth - 72, 50, 18, 16,
            Text.literal(""), { client?.setScreen(AuctionCreateScreen(currentTab)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/choose.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        )
        createButton = createBtn
        // 「我的」tab 是自己的挂单列表，没有上架入口
        createBtn.visible = currentTab != 2
        addDrawableChild(createBtn)

        // 特训筛选按钮（+右侧，原规则按钮位置），仅精灵 tab 显示（拍卖列表全量下发，本地过滤即可）
        htButton = NineSliceButton(
            leftX + panelWidth - 52, 50, 50, 16,
            htButtonText(),
            { toggleHtFilter() },
            if (htFilter != 0) GOLD_COLOR else 0xFFFFFF
        )
        htButton?.visible = currentTab == 0
        addDrawableChild(htButton)

        // 筛选行（y=68）：性别图标 | 属性 | 特性 | 性格（仅精灵 tab；照市场筛选区）
        genderButton = NineSliceButton(
            leftX + 4, 68, 24, 20,
            Text.literal(""),
            { cycleGender() },
            iconTexW = 6, iconTexH = 8, iconScale = 1.5f
        )
        updateGenderButton()
        addDrawableChild(genderButton)
        typeButton = NineSliceButton(
            leftX + 32, 68, 60, 20,
            typeButtonText(),
            { toggleFilterList("type") },
            if (typeFilter.isNotEmpty()) typeColor("cobblemon.type.$typeFilter") else 0xFFFFFF
        )
        addDrawableChild(typeButton)
        abilityButton = NineSliceButton(
            leftX + 96, 68, 96, 20,
            abilityButtonText(),
            { toggleFilterList("ability") },
            if (abilityFilter.isNotEmpty()) GOLD_COLOR else 0xFFFFFF
        )
        addDrawableChild(abilityButton)
        natureButton = NineSliceButton(
            leftX + 196, 68, 96, 20,
            natureButtonText(),
            { toggleFilterList("nature") },
            if (natureFilter.isNotEmpty()) GOLD_COLOR else 0xFFFFFF
        )
        addDrawableChild(natureButton)
        applyFilterVisibility()

        // 不重置 scrollOffset：出价弹窗关闭/resize 重建时保留浏览位置（switchTab 才显式归零）
        sendToServer(RequestAuctionListPayload())
        rebuildFiltered()
    }

    // ── 筛选（仅精灵 tab；照市场筛选区：性别图标三态 + 属性/特性/性格展开选择） ──

    private fun applyFilterVisibility() {
        val visible = currentTab == 0
        genderButton?.visible = visible
        typeButton?.visible = visible
        abilityButton?.visible = visible
        natureButton?.visible = visible
    }

    private fun updateGenderButton() {
        val g = genderButton ?: return
        g.iconLeft = if (genderFilter == "FEMALE") GENDER_ICON_FEMALE else GENDER_ICON_MALE
        g.iconLeft2 = if (genderFilter.isEmpty()) GENDER_ICON_FEMALE else null
    }

    private fun cycleGender() {
        genderFilter = when (genderFilter) {
            "" -> "MALE"
            "MALE" -> "FEMALE"
            else -> ""
        }
        updateGenderButton()
        rebuildFiltered()
        scrollOffset = 0
        hoveredRow = -1
        rebuildBidButtons()
    }

    private fun typeButtonText(): Text {
        val label = if (typeFilter.isEmpty()) Text.translatable("cobblemarket.gui.filter_any")
            else Text.translatable("cobblemon.type.$typeFilter")
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

    private val typeOptions = listOf("normal","fire","water","electric","grass","ice","fighting","poison","ground","flying",
        "psychic","bug","rock","ghost","dragon","dark","steel","fairy")

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
        // 展开时只隐藏**被列表覆盖**的控件：列表从 y=88 起最多 8 行盖到 200，
        // 所以只有行按钮需要让位——且必须 visible=false，因为列表按钮背景贴图中间区域半透明，
        // 下层行按钮文字会透出。搜索框与特训按钮都在 y=50~66、筛选按钮行在 y=68~88，
        // 全在列表上方不被遮挡，展开期间保持可见（照精灵市场）。
        // 出价弹窗打开时才真的要隐藏（openBidDialog 会调用本函数收起列表，不能把控件恢复可见）
        searchField?.visible = bidEntry == null
        htButton?.visible = currentTab == 0 && bidEntry == null
        // 「我的」tab 没有上架入口（出价弹窗期间已在 openBidDialog 里隐藏，这里补上 tab 条件即可）
        createButton?.visible = currentTab != 2 && bidEntry == null
        bidButtons.forEach { it.visible = !open }
        if (!open) return
        val options: List<Pair<String, String>> = when (filterListOpen) {
            "type" -> listOf("" to "") + typeOptions.map { it to "cobblemon.type.$it" }
            "ability" -> listOf("" to "") + abilityOptions
            "nature" -> listOf("" to "") + natureOptions
            else -> emptyList()
        }
        val leftX = width / 2 - panelWidth / 2
        options.drop(filterListScroll).take(MAX_FILTER_LIST_ROWS).forEachIndexed { i, (key, label) ->
            val display = if (key.isEmpty()) Text.translatable("cobblemarket.gui.filter_any").string
                else Text.translatable(label).string.let { t -> if (t == label) label else t }
            val btn = NineSliceButton(
                leftX + 4, 88 + i * 14, 288, 14,
                if (isFilterSelected(filterListOpen, key))
                    com.shusheng.cobblemarket.util.TextUtil.selectedText(
                        com.shusheng.cobblemarket.util.TextUtil.truncateString(display, 260)
                    )
                else Text.literal(com.shusheng.cobblemarket.util.TextUtil.truncateString(display, 260)),
                { selectFilterOption(filterListOpen, key) },
                if (filterListOpen == "type" && key.isNotEmpty()) typeColor("cobblemon.type.$key") else 0xFFFFFF
            )
            filterOptionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    private fun isFilterSelected(kind: String, key: String): Boolean = when (kind) {
        "type" -> key.isNotEmpty() && key == typeFilter
        "ability" -> key.isNotEmpty() && key == abilityFilter
        "nature" -> key.isNotEmpty() && key == natureFilter
        else -> false
    }

    private fun selectFilterOption(kind: String, key: String) {
        when (kind) {
            "type" -> {
                typeFilter = key
                typeButton?.setMessage(typeButtonText())
                typeButton?.textColor = if (key.isNotEmpty()) typeColor("cobblemon.type.$key") else 0xFFFFFF
            }
            "ability" -> {
                abilityFilter = key
                abilityButton?.setMessage(abilityButtonText())
                abilityButton?.textColor = if (key.isNotEmpty()) GOLD_COLOR else 0xFFFFFF
            }
            "nature" -> {
                natureFilter = key
                natureButton?.setMessage(natureButtonText())
                natureButton?.textColor = if (key.isNotEmpty()) GOLD_COLOR else 0xFFFFFF
            }
        }
        filterListOpen = ""
        rebuildFilterList()
        rebuildFiltered()
        scrollOffset = 0
        hoveredRow = -1
        rebuildBidButtons()
    }

    /** 搜索框物种解析 → 特性筛选选项；物种变化时重置失效选择 */
    private fun updateAbilityOptions(text: String) {
        val trimmed = text.trim()
        val species = if (trimmed.isEmpty()) null else {
            if (trimmed.contains(":")) {
                Identifier.tryParse(trimmed)?.let { PokemonSpecies.getByIdentifier(it) }
            } else {
                val byName = try { PokemonSpecies.getByName(trimmed) } catch (_: Exception) { null }
                // 精确优先：中文名互为子串时别让「鬼斯」被「鬼斯通」截胡（见 SpeciesText）
                byName ?: com.shusheng.cobblemarket.util.SpeciesText.candidatesByNameOrId(trimmed).firstOrNull()
            }
        }
        abilityOptions = species?.abilities?.map { pa ->
            val key = "cobblemon.ability.${pa.template.name}"
            val t = Text.translatable(key).string
            key to (if (t == key) pa.template.displayName else t)
        } ?: emptyList()
        if (abilityFilter.isNotEmpty() && abilityOptions.none { it.first == abilityFilter }) {
            abilityFilter = ""
            abilityButton?.setMessage(abilityButtonText())
            abilityButton?.textColor = 0xFFFFFF
        }
    }

    private fun updateTabButtons() {
        // 与 init 的 tabOrder 一致：显示顺序 我的 | 精灵 | 物品
        val keys = listOf("cobblemarket.auction.tab_mine", "cobblemarket.auction.tab_pokemon", "cobblemarket.auction.tab_item")
        val tabOrder = listOf(2, 0, 1)
        tabButtons.forEachIndexed { i, btn ->
            val label = Text.translatable(keys[i]).string
            btn.setMessage(if (currentTab == tabOrder[i]) com.shusheng.cobblemarket.util.TextUtil.selectedText(label) else Text.literal(label))
        }
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        searchField?.text = ""
        updateSearchPlaceholder()
        scrollOffset = 0
        hoveredRow = -1
        filterListOpen = ""
        rebuildFilterList()
        updateTabButtons()
        htButton?.visible = currentTab == 0
        // 「我的」tab 没有上架入口
        createButton?.visible = currentTab != 2
        applyFilterVisibility()
        rebuildFiltered()
        rebuildBidButtons()
    }

    // 搜索框占位符随 tab 切换：精灵 = 宝可梦名称...，物品 = 搜索物品，我的（精灵+物品混合）= 通用提示
    // 抽成函数是因为 init() 也要调：resize 与「进创建界面再返回」都会重跑 init，写死会退回精灵提示
    private fun updateSearchPlaceholder() {
        searchField?.setPlaceholder(Text.translatable(
            when (currentTab) {
                0 -> "cobblemarket.gui.search_placeholder"
                1 -> "cobblemarket.item.search"
                else -> "cobblemarket.auction.search_any"
            }
        ).formatted(Formatting.GRAY))
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
        htButton?.setMessage(htButtonText())
        // 筛选生效 = 金色，不限 = 白色（与闪光按钮一致）
        htButton?.textColor = if (htFilter != 0) GOLD_COLOR else 0xFFFFFF
        rebuildFiltered()
        scrollOffset = 0
        hoveredRow = -1
        rebuildBidButtons()
    }

    // ── 数据接收 ──

    fun onAuctionList(payload: AuctionListDataPayload) {
        // 结算中的条目保留到动画结束（服务端全量已不含它们），其余以服务端为准
        val settling = entries.filter { it.id in settlingUntil }
        entries = payload.entries + settling.filter { s -> payload.entries.none { it.id == s.id } }
        rebuildFiltered()
        cacheIcons()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        rebuildBidButtons()
        tryLocateInitialBid()
    }

    /** 聊天点击直达：列表数据就绪后定位拍品并弹其出价弹窗；
     *  自己发布的拍品不弹（与界面内行为一致：卖家行不生成出价按钮） */
    private fun tryLocateInitialBid() {
        pendingBidLocate?.let { id ->
            entries.find { it.id == id }?.let { e ->
                pendingBidLocate = null
                if (!isMine(e)) openBidDialog(e)
            }
        }
    }

    fun onAuctionEvent(payload: AuctionEventPayload) {
        when (payload.event) {
            "NEW" -> payload.entry?.let { e ->
                // 最新的在最上面（与服务端列表倒序一致）
                if (entries.none { it.id == e.id }) entries = listOf(e) + entries
            }
            "BID" -> payload.entry?.let { e ->
                entries = entries.map { if (it.id == e.id) e else it }
                // 出价弹窗同步最新数据（他人出价后弹窗内价格/预填不过期）；
                // 玩家已开始输入时不再覆盖（bidEdited 由 changedListener 置位），保护输入内容
                if (bidEntry?.id == e.id) {
                    bidEntry = e
                    if (!bidEdited) {
                        val minValid = if (e.currentPrice > 0)
                            (e.currentPrice.toLong() + e.minIncrement).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        else e.startingPrice
                        bidField?.text = minValid.toString()
                    }
                }
            }
            "SETTLED" -> payload.entry?.let { e ->
                // 延迟移除：保留 1.5 秒显示「结算中」+ 落槌动画，期间出价按钮不生成
                settlingUntil[e.id] = System.currentTimeMillis() + 1500
            }
            "CANCELLED" -> payload.entry?.let { e ->
                // 强制下架：立即从列表移除（不播落槌动画），settlingUntil 清理防残留
                entries = entries.filterNot { it.id == e.id }
                settlingUntil.remove(e.id)
            }
        }
        rebuildFiltered()
        cacheIcons()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        rebuildBidButtons()
        tryLocateInitialBid()
    }

    /** 出价弹窗是否打开（供全局音效判断：出价成功不播 result_success，避免与金币声叠音） */
    fun isBidDialogOpen(): Boolean = bidEntry != null

    // 锤子图标敲击动画：拍卖 id → 敲击状态结束时间戳（警告声/落槌到达时设置，行渲染读取）
    private val hammerHitUntil = mutableMapOf<java.util.UUID, Long>()

    /** 警告声到达：对应拍卖行的锤子图标切换敲击状态约 0.3 秒 */
    fun onWarnSound(auctionId: java.util.UUID, knock: Int) {
        hammerHitUntil[auctionId] = System.currentTimeMillis() + 300
    }

    /** 落槌到达：对应拍卖行的锤子图标敲击一下（结算中条目延迟移除期间可见） */
    fun onSettleSound(auctionId: java.util.UUID) {
        hammerHitUntil[auctionId] = System.currentTimeMillis() + 300
    }

    // 结算中条目：SETTLED 事件后保留 1.5 秒（显示「结算中」+ 落槌动画），过期后移除
    private val settlingUntil = mutableMapOf<java.util.UUID, Long>()

    fun onMarketResult(payload: MarketResultPayload) {
        if (payload.success) {
            // 出价成功：广播事件会自动刷新列表，这里只关弹窗
            if (bidEntry != null) closeBidDialog()
            // 结果同步发聊天框（与上架界面一致）
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.GREEN), false)
        } else {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.RED), false)
            // 拍卖已结算（条目从列表消失）时自动关闭出价弹窗，避免停在已不存在的拍卖上
            if (bidEntry?.id?.let { id -> entries.none { it.id == id } } == true) {
                closeBidDialog()
            }
        }
        resultMsg = payload.message.string
        resultUntil = System.currentTimeMillis() + 3000
    }

    // ── 过滤 ──

    private fun myUuid() = client?.player?.uuid

    private fun filtered(): List<AuctionEntry> = filteredCache

    /** 数据/搜索/筛选任一变化时重建过滤缓存（排序只在这里做一次，render 每帧只读） */
    private fun rebuildFiltered() {
        val query = searchField?.text?.trim()?.takeIf { it.isNotEmpty() }
        filteredCache = entries.filter { entry ->
            val inTab = when (currentTab) {
                0 -> entry.type == "POKEMON"
                1 -> entry.type == "ITEM"
                else -> {
                    val me = myUuid()
                    entry.sellerUuid == me || entry.currentBidderUuid == me
                }
            }
            if (!inTab) return@filter false
            // 特训筛选三态：六项特训值（-1 = 未特训）任一 >= 0 即有特训
            if (htFilter != 0 && entry.type == "POKEMON") {
                val d = entry.extraData
                val hasHt = (d["htHp"]?.toIntOrNull() ?: -1) >= 0 || (d["htAtk"]?.toIntOrNull() ?: -1) >= 0 ||
                    (d["htDef"]?.toIntOrNull() ?: -1) >= 0 || (d["htSpAtk"]?.toIntOrNull() ?: -1) >= 0 ||
                    (d["htSpDef"]?.toIntOrNull() ?: -1) >= 0 || (d["htSpd"]?.toIntOrNull() ?: -1) >= 0
                if (htFilter == 1 && !hasHt) return@filter false
                if (htFilter == 2 && hasHt) return@filter false
            }
            // 筛选（仅精灵 tab 生效）：性别/属性/特性/性格（生效性格，不分薄荷）
            if (entry.type == "POKEMON") {
                val d = entry.extraData
                if (genderFilter.isNotEmpty() && d["gender"] != genderFilter) return@filter false
                if (typeFilter.isNotEmpty()) {
                    val primary = d["primaryType"] ?: ""
                    val secondary = d["secondaryType"] ?: ""
                    if (!primary.contains(typeFilter, ignoreCase = true) && !secondary.contains(typeFilter, ignoreCase = true)) {
                        return@filter false
                    }
                }
                if (abilityFilter.isNotEmpty() && d["ability"] != abilityFilter) return@filter false
                if (natureFilter.isNotEmpty() && d["nature"] != natureFilter) return@filter false
            }
            query == null || displayName(entry).contains(query, ignoreCase = true) ||
                entry.species.contains(query, ignoreCase = true) || entry.sellerName.contains(query, ignoreCase = true) ||
                // 物品条目走搜索索引（名称/tooltip/TM 招式精确匹配，见 ItemSearchIndex）
                (entry.type == "ITEM" && com.shusheng.cobblemarket.client.ItemSearchIndex.entryMatches(entry.species, entry.itemNbt, query))
        }.sortedByDescending { it.createdAt }
        indexedFiltered = filteredCache.map { IndexedValue(entries.indexOf(it), it) }
    }

    private fun displayCount() = filtered().size

    private fun isMine(entry: AuctionEntry): Boolean = entry.sellerUuid == myUuid()

    // ── 图标缓存 ──

    private fun cacheIcons() {
        iconData.clear()
        rowStacks.clear()
        entries.forEachIndexed { index, entry ->
            if (entry.type != "POKEMON") {
                rowStacks[index] = RowStacks(
                    ballStack = null,
                    heldStack = null,
                    itemStack = Identifier.tryParse(entry.species)?.let { ItemStack(Registries.ITEM.get(it)) }
                )
                return@forEachIndexed
            }
            rowStacks[index] = RowStacks(
                ballStack = entry.extraData["ballItem"]?.let { Identifier.tryParse(it)?.let { id -> ItemStack(Registries.ITEM.get(id)) } },
                heldStack = buildHeldStack(entry.extraData["heldItemId"].orEmpty()),
                itemStack = null
            )
            val id = Identifier.tryParse(entry.extraData["speciesId"] ?: "") ?: return@forEachIndexed
            val species = PokemonSpecies.getByIdentifier(id) ?: return@forEachIndexed
            val aspects = (entry.extraData["aspects"] ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()
            if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
            iconData[index] = IconData(displayName(entry), RenderablePokemon(species, aspects, ItemStack.EMPTY), FloatingState())
        }
    }

    /** 携带物栈（空气视为无）；缓存供行渲染每帧复用（照精灵市场） */
    private fun buildHeldStack(heldItemId: String): ItemStack? {
        if (heldItemId.isEmpty()) return null
        val heldId = Identifier.tryParse(heldItemId) ?: return null
        val heldItem = Registries.ITEM.get(heldId)
        return if (heldItem != Registries.ITEM.get(Identifier.of("minecraft", "air"))) ItemStack(heldItem) else null
    }

    private fun renderPokemonIcon(context: DrawContext, index: Int, x: Int, y: Int, size: Int, delta: Float = 0f) {
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
                scale = 4.5f
            )
        } catch (_: Exception) {
        } finally {
            context.disableScissor()
            matrices.pop()
        }
    }

    // ── 行按钮 ──

    private fun rebuildBidButtons() {
        bidButtons.forEach { remove(it) }
        bidButtons.clear()
        // 筛选展开列表/出价弹窗打开时行按钮保持隐藏：事件触发的重建会把新按钮
        // 追加到 children 末尾，浮在列表按钮/遮罩之上刺穿
        if (filterListOpen.isNotEmpty() || bidEntry != null) return
        val leftX = width / 2 - panelWidth / 2
        val startY = getListStartY()
        filtered().drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, entry ->
            val y = startY + i * rowHeight
            if (isMine(entry)) return@forEachIndexed
            // 结算中的条目不生成出价按钮（拍卖已结束，延迟移除期间仅展示落槌动画）
            if (entry.id in settlingUntil) return@forEachIndexed
            val btn = NineSliceButton(
                leftX + panelWidth - 50, y + 4, 44, 16,
                Text.translatable("cobblemarket.auction.bid"),
                { openBidDialog(entry) }
            )
            btn.visible = bidEntry == null
            bidButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // ── 出价弹窗（照搬确认弹窗模式） ──

    private fun openBidDialog(entry: AuctionEntry) {
        bidEntry = entry
        bidRenderable = null
        searchField?.visible = false
        backButton?.visible = false
        createButton?.visible = false
        tabButtons.forEach { it.visible = false }
        rulesButton?.visible = false
        htButton?.visible = false
        genderButton?.visible = false
        typeButton?.visible = false
        abilityButton?.visible = false
        natureButton?.visible = false
        filterListOpen = ""
        rebuildFilterList()
        bidButtons.forEach { it.visible = false }
        val centerX = width / 2

        // 精灵预览
        if (entry.type == "POKEMON") {
            // 左列信息区实际高度折算成 10px 行，弹窗同步变高：起始 +56，12 基础行
            // （名字/类型/性格/特性/IV 标签/6×IV/亲密度）+ 球种/携带物行 + 证章区块。
            // 基线 190 只够 12 行，超出部分向上取整成整行（含底部 10px 余量）。
            // 加新信息行（如技能）时只改这里的 12。
            val marks = entry.extraData["marks"].orEmpty().split(",").filter { it.isNotEmpty() }
            val infoRows = 12 +
                (if (entry.extraData["ball"].orEmpty().isNotEmpty()) 1 else 0) +
                (if (EntryBadgeRenderer.hasHeldItemLine(entry.extraData["heldItemId"].orEmpty())) 1 else 0)
            val leftH = 56 + infoRows * 10 + EntryBadgeRenderer.marksBlockHeight(marks)
            bidDialogExtraRows = ((leftH + 10 - 190 + 9) / 10).coerceAtLeast(0)
            bidDialogW = 280
            val id = Identifier.tryParse(entry.extraData["speciesId"] ?: "")
            val species = id?.let { PokemonSpecies.getByIdentifier(it) }
            if (species != null) {
                val aspects = (entry.extraData["aspects"] ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()
                if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
                bidRenderable = RenderablePokemon(species, aspects, ItemStack.EMPTY)
            }
        } else {
            // 物品词条行（附魔等）：打开时构建 BASIC + ADVANCED 双份（供布局估算；ADVANCED 此刻无 Shift，
            // Fabric tooltip 的信息块不会生成——渲染时按住 Shift 会重建，见 renderBidDialogBackground）
            bidItemTooltipLines = buildBidItemLines(entry, TooltipType.BASIC)
            bidItemAdvancedLines = buildBidItemLines(entry, TooltipType.ADVANCED)
            bidAdvancedBuiltWithShift = false
            // 弹窗高度随词条行数扩展（按两份最大行数预留，Shift 展开不溢出；上限 9 行防超高）
            // 基线 7 行：词条区底收在确认按钮上方，横排宽行的末尾不压按钮
            bidDialogExtraRows = (minOf(maxOf(bidItemTooltipLines.size, bidItemAdvancedLines.size), 20) - 7)
                .coerceIn(0, 9)
            // 宽度初始只按 BASIC 行宽（未按 Shift 的初始状态不撑宽；渲染帧按当前 Shift 状态动态覆盖）
            var maxLineW = 0
            bidItemTooltipLines.forEach { maxLineW = maxOf(maxLineW, textRenderer.getWidth(it)) }
            bidDialogW = (maxOf(280, maxLineW + 170)).coerceAtMost(width - 20)
        }
        val dialogY = height / 2 - (190 + bidDialogExtraRows * 10) / 2

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderBidDialogBackground(context, delta)
            }
        })

        // 出价输入（预填最低有效出价；Long 计算防 Int 溢出 wrap 成负数）
        val minValid = if (entry.currentPrice > 0)
            (entry.currentPrice.toLong() + entry.minIncrement).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        else entry.startingPrice
        bidField = TextFieldWidget(textRenderer, centerX + 10, dialogY + 106 + bidDialogExtraRows * 10, 120, 16, Text.literal(""))
        bidField?.setPlaceholder(Text.translatable("cobblemarket.auction.bid_placeholder").formatted(Formatting.GRAY))
        bidField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        bidField?.text = minValid.toString()
        // 打开弹窗时重置「玩家已编辑」标志：之后 BID 广播只更新未动过的预填，不覆盖玩家输入
        bidEdited = false
        // 重新输入时清除校验失败提示
        bidField?.setChangedListener { bidEdited = true; bidErrorText = null }
        addDrawableChild(bidField)

        // 音效由 confirmBid 按校验结果播放（失败 fail.ogg / 成功 auction_bid 金币音效）
        bidConfirmButton = NineSliceButton(
            centerX + 10, dialogY + 132 + bidDialogExtraRows * 10, 56, 20,
            Text.translatable("cobblemarket.auction.confirm_bid"),
            { confirmBid() },
            clickSound = null
        )
        addDrawableChild(bidConfirmButton)
        bidCancelButton = NineSliceButton(
            centerX + 74, dialogY + 132 + bidDialogExtraRows * 10, 56, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeBidDialog() }
        )
        addDrawableChild(bidCancelButton)
    }

    private fun renderBidDialogBackground(context: DrawContext, delta: Float) {
        val entry = bidEntry ?: return
        val centerX = width / 2
        // ── 物品词条选择（先于布局）：按住 Shift/Ctrl 时按需构建（Fabric tooltip 的信息块
        // 如 TM 招式详情只在构建时刻按键按住时生成；类型变化重建）
        var itemLines: List<Text>? = null
        if (entry.type != "POKEMON") {
            itemLines = if (com.shusheng.cobblemarket.client.ItemComponentsDisplay.hoverExpanded()) {
                val type = com.shusheng.cobblemarket.client.ItemComponentsDisplay.tooltipTypeForHover()
                if (!bidAdvancedBuiltWithShift || bidAdvancedType != type) {
                    bidItemAdvancedLines = buildBidItemLines(entry, type)
                    bidAdvancedBuiltWithShift = true
                    bidAdvancedType = type
                }
                bidItemAdvancedLines
            } else {
                bidAdvancedBuiltWithShift = false
                bidAdvancedType = null
                bidItemTooltipLines
            }
        }
        // 布局随当前词条行数/宽度动态伸缩（Shift 展开信息块不溢出），按钮位置每帧同步；
        // 基线 7 行：词条区底收在确认按钮上方（56 + 7*10 ≈ 按钮顶），横排宽行末尾不压按钮
        val lineCount = itemLines?.size ?: 0
        // 精灵模式用 init 按证章区块算好的行数余量；物品模式按当前词条行数每帧算
        val extra = if (entry.type == "POKEMON") bidDialogExtraRows else (minOf(lineCount, 20) - 7).coerceIn(0, 9)
        val dialogH = 190 + extra * 10
        var maxLineW = 0
        itemLines?.forEach { maxLineW = maxOf(maxLineW, textRenderer.getWidth(it)) }
        val dialogW = if (entry.type == "POKEMON") 280 else (maxOf(280, maxLineW + 170)).coerceAtMost(width - 20)
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2
        if (entry.type != "POKEMON") {
            bidField?.y = dialogY + 106 + extra * 10
            bidConfirmButton?.y = dialogY + 132 + extra * 10
            bidCancelButton?.y = dialogY + 132 + extra * 10
            // x 相对弹窗右缘锚定：弹窗随词条行宽变宽时输入框/按钮随右列一起右移，不被宽行压住
            val rightAnchor = dialogX + dialogW - 130
            bidField?.x = rightAnchor
            bidConfirmButton?.x = rightAnchor
            bidCancelButton?.x = rightAnchor + 62
        }

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.auction.bid_title").formatted(Formatting.GOLD),
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
            bidRenderable?.let { rp ->
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
                        state = bidPreviewState,
                        partialTicks = if (useFloat) delta else 0f,
                        scale = 4.5f
                    )
                } catch (_: Exception) {
                } finally {
                    context.disableScissor()
                    matrices.pop()
                }
            }
        } else {
            Identifier.tryParse(entry.species)?.let { id ->
                val item = Registries.ITEM.get(id)
                if (item != Registries.ITEM.get(Identifier.of("minecraft", "air"))) {
                    val stack = ItemStack(item, entry.count)
                    drawItemWithBar(context, stack, slotX + 6, slotY + 6)
                }
            }
        }

        // ── 左列：精灵/物品完整信息 ──
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
            // 名字（属性色）+ 金色闪光星 + 等级 + 公母图标
            context.drawTextWithShadow(textRenderer, displayName(entry), infoX, iy, tc)
            var cx = infoX + textRenderer.getWidth(displayName(entry))
            if (entry.shiny) {
                context.drawText(textRenderer, "★", cx + 2, iy, GOLD_COLOR, false)
                cx += 2 + textRenderer.getWidth("★")
            }
            context.drawText(textRenderer, "  Lv.${entry.level}", cx + 2, iy, 0xFFFFFF, false)
            cx += 2 + textRenderer.getWidth("  Lv.${entry.level}")
            val gender = extra["gender"]
            var genderW = 0
            if (gender == "MALE" || gender == "FEMALE") {
                val gi = if (gender == "MALE") GENDER_ICON_MALE else GENDER_ICON_FEMALE
                com.cobblemon.mod.common.api.gui.blitk(
                    matrixStack = context.matrices, texture = gi,
                    x = cx + 2, y = iy, width = 6, height = 8
                )
                genderW = 2 + 6
            }
            // 体型徽章（紧跟公母图标，留 3px 空隙）
            val sizeBadge = extra["size"].orEmpty()
            if (sizeBadge.isNotEmpty()) {
                EntryBadgeRenderer.drawSizeBadgeIcon(context, sizeBadge, cx + genderW + if (genderW > 0) 3 else 2, iy)
            }
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
            // 球种（特性行下，与其他界面顺序统一；自定义球玩家需要文字说明——行内小图标认不出）
            extra["ball"]?.takeIf { it.isNotEmpty() }?.let {
                infoLine("${Text.translatable("cobblemarket.gui.tooltip_ball").string}${Text.translatable(it).string}")
            }
            val heldItemId = extra["heldItemId"].orEmpty()
            val hasHeldItem = heldItemId.isNotEmpty() &&
                Identifier.tryParse(heldItemId)?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true
            if (hasHeldItem) {
                // 携带物行：标签（白色，与其他行一致）+ 右侧物品图标
                val heldLabel = Text.translatable("cobblemarket.gui.tooltip_held").string
                val heldY = iy
                infoLine(heldLabel)
                Identifier.tryParse(heldItemId)?.let { heldId ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = ItemStack(Registries.ITEM.get(heldId)),
                        x = infoX + textRenderer.getWidth(heldLabel) + 2.0,
                        y = heldY + 0.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                }
            }
            val hp = Text.translatable("cobblemon.stat.hp.name").string
            val atk = Text.translatable("cobblemon.stat.attack.name").string
            val def = Text.translatable("cobblemon.stat.defence.name").string
            val spa = Text.translatable("cobblemon.stat.special_attack.name").string
            val spd = Text.translatable("cobblemon.stat.special_defence.name").string
            val spe = Text.translatable("cobblemon.stat.speed.name").string
            infoLine(Text.translatable("cobblemarket.gui.tooltip_ivs").string)
            infoLineText(Text.literal("  $hp:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsHp"]?.toIntOrNull() ?: 0, htExtra(extra, "htHp"))}").append(Text.literal("  EV:${extra["evsHp"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)), 0x66FF66); infoLineText(Text.literal("  $atk:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsAtk"]?.toIntOrNull() ?: 0, htExtra(extra, "htAtk"))}").append(Text.literal("  EV:${extra["evsAtk"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)), 0xFF6666)
            infoLineText(Text.literal("  $def:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsDef"]?.toIntOrNull() ?: 0, htExtra(extra, "htDef"))}").append(Text.literal("  EV:${extra["evsDef"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)), 0xFFCC66); infoLineText(Text.literal("  $spa:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpAtk"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpAtk"))}").append(Text.literal("  EV:${extra["evsSpAtk"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)), 0x6699FF)
            infoLineText(Text.literal("  $spd:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpDef"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpDef"))}").append(Text.literal("  EV:${extra["evsSpDef"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)), 0x66FF99); infoLineText(Text.literal("  $spe:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpd"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpd"))}").append(Text.literal("  EV:${extra["evsSpd"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)), 0xFF99FF); infoLineText(Text.translatable("cobblemarket.gui.friendship", extra["friendship"]?.toIntOrNull() ?: 0), 0xFF99CC)
            // 证章区块：上下分割线夹证章图标行（左对齐，每行 MARKS_PER_ROW 个，照悬停 tooltip）
            val marks = extra["marks"].orEmpty().split(",").filter { it.isNotEmpty() }
            if (marks.isNotEmpty()) {
                val rowW = dialogW - 156
                context.fill(infoX, iy + 4, infoX + rowW, iy + 5, 0xFF555555.toInt())
                iy += 10
                iy += EntryBadgeRenderer.drawMarksRow(context, marks, infoX, iy)
                context.fill(infoX, iy + 4, infoX + rowW, iy + 5, 0xFF555555.toInt())
                iy += 10
            }
        } else {
            infoLine(displayName(entry))
            infoLine("×${entry.count}")
            // 物品词条（附魔/名称等，原版 tooltip 去首行物品名；列表已在函数头按 Shift 状态选好）
            itemLines?.forEach { infoLineText(it, 0xFFFFFF) }
        }

        // ── 右列：竞拍信息 ──
        val auctionX = dialogX + dialogW - 140
        var ay = dialogY + 28
        fun auctionLine(text: String, color: Int = 0xFFFFFF) {
            context.drawTextWithShadow(textRenderer, text, auctionX, ay, color)
            ay += 10
        }
        fun auctionLine(text: Text, color: Int = 0xFFFFFF) {
            context.drawTextWithShadow(textRenderer, text, auctionX, ay, color)
            ay += 10
        }
        auctionLine("${Text.translatable("cobblemarket.auction.seller").string}: ${entry.sellerName}")
        // 价格三行：标签默认色，金额段蓝色（2026-08-24 拍板）
        auctionLine(Text.translatable("cobblemarket.auction.current_price").append(": ").append(
            displayPriceText(entry)))
        auctionLine(Text.translatable("cobblemarket.auction.starting_price").append(": ").append(
            Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.startingPrice)} ${com.shusheng.cobblemarket.client.inlineCurrencyUnit()}").formatted(Formatting.GOLD)))
        auctionLine(Text.translatable("cobblemarket.auction.min_increment").append(": ").append(
            Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.minIncrement)} ${com.shusheng.cobblemarket.client.inlineCurrencyUnit()}").formatted(Formatting.GOLD)))
        auctionLine("${Text.translatable("cobblemarket.auction.ends").string}: ${formatRemaining(entry.endsAt)}", 0xAAAAAA)
        auctionLine("${Text.translatable("cobblemarket.auction.bids_count").string}: ${entry.bidCount}", 0xAAAAAA)
        if (entry.currentBidderName.isNotEmpty()) {
            auctionLine("${Text.translatable("cobblemarket.auction.leader").string}: ${entry.currentBidderName}", 0xFFDD66)
        }

        // 出价校验失败提示（两个按钮下方居中，2 秒后消失；重新输入时清除）。
        // x 取「确认出价 / 取消」按钮组的中心而非弹窗中心：精灵模式左列证章图标行横向可到弹窗中线附近，
        // 居中于弹窗会压在证章行上；y 跟随弹窗变高偏移（同输入框/按钮），否则会飘到输入框上
        if (bidErrorText != null && System.currentTimeMillis() < bidErrorUntil) {
            val errorX = if (entry.type == "POKEMON") centerX + 70 else dialogX + dialogW - 71
            context.drawCenteredTextWithShadow(textRenderer, bidErrorText, errorX, dialogY + 158 + extra * 10, 0xFFFFFF)
        }
    }

    private fun confirmBid() {
        val entry = bidEntry ?: return
        val amount = bidField?.text?.toIntOrNull() ?: run {
            playFailSound()
            return
        }
        val invalid = amount < entry.startingPrice || amount <= entry.currentPrice ||
            (entry.currentPrice > 0 && amount - entry.currentPrice < entry.minIncrement)
        if (invalid) {
            // 无效出价：红字提示 2 秒 + fail 音效（原先是静默返回，玩家无感知）
            bidErrorText = Text.translatable("cobblemarket.auction.bid_too_low").formatted(Formatting.RED)
            bidErrorUntil = System.currentTimeMillis() + 2000
            MinecraftClient.getInstance().soundManager.play(
                PositionedSoundInstance.master(
                    SoundEvent.of(Identifier.of("cobblemarket", "fail")),
                    1.0f,
                    0.5f // 音量（两参重载固定 0.25 太轻，档位同 playResultSound）
                )
            )
            return
        }
        // 余额预判：不足时只给 fail 反馈，不播金币声也不发请求（金币声会让玩家误以为出价成功；
        // 服务端扣款仍会二次校验，此处仅避免误报音效）。自己连续加价只扣差价，与服务端扣款口径一致。
        val deduct = if (entry.currentBidderUuid == myUuid() && entry.currentPrice > 0) amount - entry.currentPrice else amount
        if (com.shusheng.cobblemarket.client.BalanceCache.balanceRaw < deduct) {
            bidErrorText = Text.translatable("cobblemarket.auction.not_enough").formatted(Formatting.RED)
            bidErrorUntil = System.currentTimeMillis() + 2000
            playFailSound()
            return
        }
        // 有效出价：金币音效（照原按钮 clickSound）
        MinecraftClient.getInstance().soundManager.play(
            PositionedSoundInstance.master(
                SoundEvent.of(Identifier.of("cobblemarket", "auction_bid")),
                1.0f,
                0.5f // 音量（两参重载固定 0.25 太轻，同 playResultSound）
            )
        )
        sendToServer(PlaceBidPayload(entry.id, amount))
    }

    private fun closeBidDialog() {
        bidEntry = null
        bidField = null
        bidConfirmButton = null
        bidCancelButton = null
        bidRenderable = null
        clearChildren()
        init()
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val panelLeft = width / 2 - 160
        val panelTop = 2
        val panelBottom = height - 32
        val sliceH = 16

        val top = Identifier.of("cobblemarket", "textures/gui/market_panel_auction_house_top.png")
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

        if (bidEntry != null) {
            return
        }

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.auction.title").formatted(Formatting.GOLD),
            centerX, 20, 0xFFFFFF)

        // 余额（左上角，来自全局缓存）
        val balText = com.shusheng.cobblemarket.client.BalanceCache.balance
        if (balText.isNotEmpty()) {
            context.drawTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.gui.balance",
                    Text.literal(balText + " " + com.shusheng.cobblemarket.client.inlineCurrencyUnit()).formatted(Formatting.GOLD)),
                leftX + 4, 20, 0xFFFFFF)
        }

        // 操作结果提示
        if (resultMsg != null) {
            if (System.currentTimeMillis() > resultUntil) {
                resultMsg = null
            } else {
                context.drawCenteredTextWithShadow(textRenderer, resultMsg!!, centerX, height - 40, 0x55FF55)
            }
        }

        val startY = getListStartY()

        // 底部计数画在屏幕底部（height-49），在展开列表下方，照常显示
        if (displayCount() > getMaxVisibleRows()) {
            context.drawCenteredTextWithShadow(textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + getMaxVisibleRows(), displayCount())} / ${displayCount()}",
                centerX, height - 49, 0x888888)
        }

        // ── 状态更新与轮询：跟筛选列表展不展开无关，必须放在 early return 之前 ──
        // 结算中的条目过期（1.5 秒）后从列表移除
        val pollNow = System.currentTimeMillis()
        val settledExpired = settlingUntil.filterValues { it <= pollNow }.keys
        if (settledExpired.isNotEmpty()) {
            entries = entries.filterNot { it.id in settledExpired }
            settledExpired.forEach { settlingUntil.remove(it) }
            rebuildFiltered()
            // entries 变化后索引移动：行缓存（3D 图标/物品栈）按索引建，必须同步重建
            cacheIcons()
            rebuildBidButtons()
        }

        // 有到期未结算的拍卖时，每秒重拉一次列表触发服务端结算（停在拍卖场也能自动结算）；
        // 结算中的条目排除（已结算完毕，重拉会全量替换、截断落槌动画）
        if (entries.any { it.endsAt <= pollNow && it.id !in settlingUntil } && pollNow - lastSettlePoll > 1000) {
            lastSettlePoll = pollNow
            sendToServer(RequestAuctionListPayload())
        }

        // 筛选展开列表打开时跳过分隔线与行内容（画在 children 之后，会刺穿列表按钮）；
        // 规则悬停面板是浮层、必须最后画，所以两条路径各画一次
        if (filterListOpen.isNotEmpty()) {
            if (rulesButton?.isHovered == true) renderRulesPanel(context, mouseX, mouseY)
            return
        }

        context.fill(leftX, startY - 2, leftX + panelWidth, startY - 1, 0xFF555555.toInt())

        val displayList = filtered()

        if (displayList.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.auction.empty").formatted(Formatting.GRAY),
                centerX, startY + 50, 0xFFFFFF)
        }

        indexedFiltered.drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, (origIndex, entry) ->
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
                    renderPokemonIcon(context, origIndex, slotX, slotY, iconSize, delta = delta)
                }
                // 图标链 + 属性色名称 + 金色闪光星标（照搬 SellSelectScreen 风格，右侧让位价格/倒计时）
                var sx = leftX + 28
                val ballItem = entry.extraData["ballItem"]
                if (!ballItem.isNullOrEmpty()) {
                    rowStacks[origIndex]?.ballStack?.let { ballStack ->
                        com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                            itemStack = ballStack, x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                    }
                    sx += 12
                }
                val primaryType = entry.extraData["primaryType"] ?: ""
                val tc = typeColor(if (primaryType.isNotEmpty()) primaryType else "cobblemon.type.normal")
                // 名字截断 46px ≈ 5 个汉字（中文 9px/字）：4 字 36px、5 字 45px 都完整显示，6 字起带「…」
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
                // 持有物图标（紧跟性别；空气/解析失败 = null 不渲染，与原逐帧解析行为一致）
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
                    drawItemWithBar(context, it, slotX + 2, slotY)
                }
                // 数量宽度先扣出来再截断名字：整串一起截断会先吃掉「×N」，模组长名物品看不到卖多少个
                val countSuffix = " ×${entry.count}"
                val name = com.shusheng.cobblemarket.util.TextUtil.truncateString(
                    displayName(entry), 100 - textRenderer.getWidth(countSuffix)) + countSuffix
                context.drawTextWithShadow(textRenderer, name, leftX + 28, y + 7, 0xFFFFFF)
            }

            // 当前价（行内缩写）+ 出价次数（灰，拆段）：货币单位紧跟 ×次数，中间不留空格
            val priceStr = displayPriceCompactText(entry)
            val bidPart = if (entry.bidCount > 0) "×${entry.bidCount}" else ""
            // 价格区右端与出价按钮（左缘 leftX+panelWidth-50）留 4px
            val priceX = leftX + panelWidth - 54 - textRenderer.getWidth(priceStr) - textRenderer.getWidth(bidPart)

            // 价格左侧锤子图标：默认静止，警告声到达时切换敲击状态约 0.3 秒（视觉联动）
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

            // 结束倒计时 + 卖家头像：头像位置与精灵市场对齐（leftX+149，各行对齐）；
            // 名字区最坏到 141px（名字截断 46px + 星 + 性别 + 携带物 + 体型徽章，起点 leftX+40），
            // 149 再留 8px 余量。倒计时紧跟其右，价格区异常宽时整块左移兜底
            val remaining = formatRemaining(entry.endsAt)
            val remainingColor = if (entry.endsAt - System.currentTimeMillis() < 5 * 60 * 1000) 0xFF6666 else 0xAAAAAA
            val remW = textRenderer.getWidth(remaining)
            val avatarX = leftX + 149
            val shift = maxOf(0, avatarX + 20 + remW - (priceX - 16 - 6))
            context.drawTextWithShadow(textRenderer, remaining,
                avatarX + 20 - shift, y + 7, remainingColor)
            drawSellerAvatar(context, entry.sellerUuid, entry.sellerName, avatarX - shift, y + 4, 16)

            // 我的 tab 标记（价格右侧；价格区右端已推到 leftX+242，标记对齐出价按钮左缘避免贴死）
            if (currentTab == 2 && isMine(entry)) {
                context.drawTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.auction.mine_mark").string,
                    leftX + 246, y + 7, 0x55FF55)
            }
        }

        if (hoveredRow >= 0) {
            val actualIdx = scrollOffset + hoveredRow
            if (actualIdx in displayList.indices) {
                renderTooltip(context, displayList[actualIdx], mouseX, mouseY)
            }
        }

        // 规则按钮悬停：自绘规则面板（浮层，最后画）
        if (rulesButton?.isHovered == true) {
            renderRulesPanel(context, mouseX, mouseY)
        }
    }

    // 拍卖规则悬停面板：标题金色（§6）、正文白色、重点红色（§c）、规则项间分割线
    private fun renderRulesPanel(context: DrawContext, mx: Int, my: Int) {
        val maxTextWidth = 280
        val rules = (1..7).map { i ->
            textRenderer.wrapLines(Text.translatable("cobblemarket.auction.rule.$i"), maxTextWidth)
        }
        val allLines = rules.flatten()
        var mw = 0
        allLines.forEach { mw = maxOf(mw, textRenderer.getWidth(it)) }
        val pad = 4
        val lineH = 10
        val dividerH = 4
        val panelW = mw + 2 * pad
        val panelH = allLines.size * lineH + (rules.size - 1) * dividerH + 2 * pad
        val tx = minOf(mx + 12, width - panelW - 12)
        val ty = if (my - panelH - 4 <= 0) minOf(my + 12, height - panelH) else my - panelH - 4

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx, ty, panelW, panelH, 1, ROW_BACKGROUND_TEX_H)
        var y = ty + pad
        rules.forEachIndexed { ri, ruleLines ->
            if (ri > 0) {
                // 分割线画在当前 y（与上一条文字底留 2px 间隙），随后推进 dividerH
                context.fill(tx + pad, y, tx + panelW - pad, y + 1, 0xFF555555.toInt())
                y += dividerH
            }
            ruleLines.forEach { line ->
                context.drawTextWithShadow(textRenderer, line, tx + pad, y, 0xFFFFFF)
                y += lineH
            }
        }
        context.matrices.pop()
    }

    private fun renderTooltip(context: DrawContext, entry: AuctionEntry, mouseX: Int, mouseY: Int) {
        if (entry.type == "POKEMON") {
            renderPokemonTooltip(context, entry, mouseX, mouseY)
        } else {
            renderItemTooltip(context, entry, mouseX, mouseY)
        }
    }

    // 精灵悬停信息：类型/性格/特性/持有物/IV + 拍卖状态（照搬上架界面 tooltip 风格）
    private fun renderPokemonTooltip(context: DrawContext, entry: AuctionEntry, mouseX: Int, mouseY: Int) {
        // 静态行缓存：内容只取决于条目（见 tooltipCacheKey 字段注释），悬停目标变化时才重建
        if (tooltipCacheKey != entry.id) {
            tooltipCacheKey = entry.id
            val extra = entry.extraData
            val hp = Text.translatable("cobblemon.stat.hp.name").string
            val atk = Text.translatable("cobblemon.stat.attack.name").string
            val def = Text.translatable("cobblemon.stat.defence.name").string
            val spa = Text.translatable("cobblemon.stat.special_attack.name").string
            val spd = Text.translatable("cobblemon.stat.special_defence.name").string
            val spe = Text.translatable("cobblemon.stat.speed.name").string
            val primaryType = extra["primaryType"] ?: ""
            val secondaryType = extra["secondaryType"] ?: ""
            val typeText = (if (primaryType.isNotEmpty()) Text.translatable(primaryType).string else "-") +
                if (secondaryType.isNotEmpty()) " + ${Text.translatable(secondaryType).string}" else ""

            val staticLines = mutableListOf<Pair<Text?, Int>>()
            staticLines.add(EntryBadgeRenderer.nameWithShinyStar(displayName(entry), entry.shiny)
                .copy().append(Text.literal("  Lv.${entry.level}")) to typeColor(primaryType))
            staticLines.add(Text.literal(Text.translatable("cobblemarket.gui.tooltip_type").string)
                .append(EntryBadgeRenderer.typeLine(primaryType, secondaryType)) to 0xFFFFFF)
            staticLines.add(Text.literal(Text.translatable("cobblemarket.gui.tooltip_nature").string)
                .append(EntryBadgeRenderer.natureText(extra["natureBase"] ?: "", extra["nature"] ?: ""))
                .append(Text.literal("  ${Text.translatable("cobblemarket.gui.tooltip_ability").string}"))
                .append(Text.translatable(extra["ability"] ?: "")) to 0xFFFFFF)
            extra["ball"]?.takeIf { it.isNotEmpty() }?.let {
                staticLines.add(Text.literal("${Text.translatable("cobblemarket.gui.tooltip_ball").string}${Text.translatable(it).string}") to 0xFFFFFF)
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
            staticLines.add(Text.literal("  $hp:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsHp"]?.toIntOrNull() ?: 0, htExtra(extra, "htHp"))}").append(Text.literal("  EV:${extra["evsHp"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)) to 0x66FF66); staticLines.add(Text.literal("  $atk:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsAtk"]?.toIntOrNull() ?: 0, htExtra(extra, "htAtk"))}").append(Text.literal("  EV:${extra["evsAtk"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)) to 0xFF6666)
            staticLines.add(Text.literal("  $def:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsDef"]?.toIntOrNull() ?: 0, htExtra(extra, "htDef"))}").append(Text.literal("  EV:${extra["evsDef"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)) to 0xFFCC66); staticLines.add(Text.literal("  $spa:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpAtk"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpAtk"))}").append(Text.literal("  EV:${extra["evsSpAtk"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)) to 0x6699FF)
            staticLines.add(Text.literal("  $spd:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpDef"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpDef"))}").append(Text.literal("  EV:${extra["evsSpDef"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)) to 0x66FF99); staticLines.add(Text.literal("  $spe:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpd"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpd"))}").append(Text.literal("  EV:${extra["evsSpd"]?.toIntOrNull() ?: 0}").formatted(Formatting.RED)) to 0xFF99FF); staticLines.add(Text.translatable("cobblemarket.gui.friendship", extra["friendship"]?.toIntOrNull() ?: 0) to 0xFF99CC)

            // 证章区块：亲密度下方分割线 + 证章图标行（下方那条分割线由拍卖信息前的分割线充当）
            var marksLine = -1
            if (extra["marks"].orEmpty().isNotEmpty()) {
                staticLines.add(null to 0)
                marksLine = staticLines.size
                staticLines.add(null to 0)
            }
            var mw = 0; staticLines.forEach { it.first?.let { t -> mw = maxOf(mw, textRenderer.getWidth(t)) } }
            // 名字行尾部图标（公母 + 体型徽章）不计入文本宽度，单独补上
            staticLines[0].first?.let { mw = maxOf(mw, EntryBadgeRenderer.nameLineWidth(it, extra["gender"] ?: "", extra["size"] ?: "")) }
            if (heldItemLine >= 0) {
                mw = maxOf(mw, textRenderer.getWidth(staticLines[heldItemLine].first!!) + 14)
            }
            // 证章行宽（每行 MARKS_PER_ROW 个 12px 格）
            if (marksLine >= 0) {
                mw = maxOf(mw, minOf(EntryBadgeRenderer.MARKS_PER_ROW, extra["marks"].orEmpty().split(",").count { it.isNotEmpty() }) * 12)
            }
            tooltipCacheStaticLines = staticLines
            tooltipCacheHeldLine = heldItemLine
            tooltipCacheMaxWidth = mw
            tooltipCacheMarksLine = marksLine
        }
        // 动态行（当前价/倒计时/领先者随出价与时间变化）每帧构建
        val lines = tooltipCacheStaticLines.toMutableList()
        // 分割线：上方精灵信息，下方拍卖信息
        lines.add(null to 0xFFFFFF)
        lines.add(Text.literal("${Text.translatable("cobblemarket.auction.seller").string}: ${entry.sellerName}") to 0xFFFFFF)
        val priceLine = Text.translatable("cobblemarket.auction.current_price").append(": ").append(displayPriceText(entry))
        lines.add((if (entry.bidCount > 0)
            priceLine.append(Text.literal("  ×${entry.bidCount}").formatted(Formatting.GRAY))
        else priceLine) to 0xFFFFFF)
        lines.add(Text.literal("${Text.translatable("cobblemarket.auction.ends").string}: ${formatRemaining(entry.endsAt)}") to 0xFFFFFF)
        if (entry.currentBidderName.isNotEmpty()) {
            lines.add(Text.literal("${Text.translatable("cobblemarket.auction.leader").string}: ${entry.currentBidderName}") to 0xFFDD66)
        }
        if (isMine(entry)) {
            lines.add(Text.literal(Text.translatable("cobblemarket.auction.mine_mark").string) to 0x55FF55)
        }

        var mw = tooltipCacheMaxWidth
        for (i in tooltipCacheStaticLines.size until lines.size) {
            lines[i].first?.let { t -> mw = maxOf(mw, textRenderer.getWidth(t)) }
        }
        val heldItemLine = tooltipCacheHeldLine
        val marksLine = tooltipCacheMarksLine
        val marks = if (marksLine >= 0) entry.extraData["marks"].orEmpty().split(",").filter { it.isNotEmpty() } else emptyList()
        val pad = 4
        val tx = minOf(mouseX + 12, width - mw - 12)
        // 证章超过一行时第二行「+N」额外占 12px
        val marksExtra = if (marksLine >= 0 && marks.size > EntryBadgeRenderer.MARKS_PER_ROW) 12 else 0
        val th = lines.size * 10 + pad + marksExtra
        val ty = if (mouseY - th - 4 <= 0) minOf(mouseY + 12, height - th) else mouseY - th - 4

        context.matrices.push(); context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - pad, ty - pad, mw + 2 * pad, lines.size * 10 + 2 * pad + marksExtra, 1, ROW_BACKGROUND_TEX_H)
        var rowY = ty
        lines.forEachIndexed { i, (line, color) ->
            if (line == null && i == marksLine) {
                // 证章图标行：左对齐排（照市场悬停；超过 6 个第二行左对齐显示「+N」）
                rowY += EntryBadgeRenderer.drawMarksRow(context, marks, tx, rowY)
            } else if (line == null) {
                // 分割线：1px 灰线，撑满提示框全宽（与信息区分隔线一致，不随证章数量变化）
                val rowW = mw
                context.fill(tx, rowY + 4, tx + rowW, rowY + 5, 0xFF555555.toInt())
                rowY += 10
            } else if (i == heldItemLine) {
                context.drawTextWithShadow(textRenderer, line, tx, rowY, color)
                Identifier.tryParse(entry.extraData["heldItemId"].orEmpty())?.let { heldId ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = ItemStack(Registries.ITEM.get(heldId)),
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

    // 物品悬停信息（简洁版；当前价用货币蓝，与全界面统一）
    private fun renderItemTooltip(context: DrawContext, entry: AuctionEntry, mouseX: Int, mouseY: Int) {
        // 静态行（名称/卖家）缓存；价格/倒计时/领先者为动态行每帧构建（见 itemTooltipCacheKey 字段注释）
        if (itemTooltipCacheKey != entry.id) {
            itemTooltipCacheKey = entry.id
            itemTooltipCacheLines = buildItemStaticLines(entry, TooltipType.BASIC)
            var mw = 0
            itemTooltipCacheLines.forEach { mw = maxOf(mw, textRenderer.getWidth(it.first)) }
            itemTooltipCacheMaxWidth = mw
            itemTooltipAdvancedLines = null
            itemTooltipAdvancedMaxWidth = 0
        }
        // 按住 Shift 展开完整词条 / Ctrl（F3+H 开启）展开调试信息（照原版背包悬停；按需构建缓存防每帧解析 NBT 掉帧）
        var staticBase = itemTooltipCacheLines
        var maxWidth = itemTooltipCacheMaxWidth
        if (com.shusheng.cobblemarket.client.ItemComponentsDisplay.hoverExpanded()) {
            val type = com.shusheng.cobblemarket.client.ItemComponentsDisplay.tooltipTypeForHover()
            if (itemTooltipAdvancedLines == null || itemTooltipAdvancedType != type) {
                itemTooltipAdvancedType = type
                itemTooltipAdvancedLines = buildItemStaticLines(entry, type)
                var amw = 0
                itemTooltipAdvancedLines!!.forEach { amw = maxOf(amw, textRenderer.getWidth(it.first)) }
                itemTooltipAdvancedMaxWidth = amw
            }
            staticBase = itemTooltipAdvancedLines!!
            maxWidth = itemTooltipAdvancedMaxWidth
        } else {
            itemTooltipAdvancedType = null
        }
        val lines = staticBase.toMutableList()
        val priceLine = Text.translatable("cobblemarket.auction.current_price").append(": ").append(displayPriceText(entry))
        lines.add((if (entry.bidCount > 0)
            priceLine.append(Text.literal("  ×${entry.bidCount}").formatted(Formatting.GRAY))
        else priceLine) to 0xFFFFFF)
        lines.add(Text.literal("${Text.translatable("cobblemarket.auction.ends").string}: ${formatRemaining(entry.endsAt)}") to 0xFFFFFF)
        if (entry.currentBidderName.isNotEmpty()) {
            lines.add(Text.literal("${Text.translatable("cobblemarket.auction.leader").string}: ${entry.currentBidderName}") to 0xFFDD66)
        }
        if (isMine(entry)) {
            lines.add(Text.literal(Text.translatable("cobblemarket.auction.mine_mark").string) to 0x55FF55)
        }

        for (i in staticBase.size until lines.size) {
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

    /** 出价弹窗物品词条构建（原版 tooltip 去首行物品名；type 区分 BASIC/ADVANCED） */
    private fun buildBidItemLines(entry: AuctionEntry, type: TooltipType): List<Text> {
        val out = mutableListOf<Text>()
        entry.itemNbt?.let { nbt ->
            client?.world?.registryManager?.let { rm ->
                val stack = ItemStack.fromNbtOrEmpty(rm, nbt)
                if (!stack.isEmpty) {
                    out.addAll(com.shusheng.cobblemarket.client.ItemComponentsDisplay.itemTooltip(stack, client?.player, type).drop(1))
                }
            }
        }
        return out
    }

    /** 物品悬停静态行构建（名称 + 物品词条 + 卖家）；type 区分 BASIC/ADVANCED（Shift 展开） */
    private fun buildItemStaticLines(entry: AuctionEntry, type: TooltipType): List<Pair<Text, Int>> {
        val staticLines = mutableListOf<Pair<Text, Int>>()
        staticLines.add(Text.literal(displayName(entry)) to 0xFFFFFF)
        // 物品词条（附魔/名称等；原版 tooltip 去首行物品名，与名称行去重）
        entry.itemNbt?.let { nbt ->
            client?.world?.registryManager?.let { rm ->
                val stack = ItemStack.fromNbtOrEmpty(rm, nbt)
                if (!stack.isEmpty) {
                    staticLines.addAll(
                        com.shusheng.cobblemarket.client.ItemComponentsDisplay.itemTooltip(stack, client?.player, type)
                            .drop(1).map { it to 0xFFFFFF }
                    )
                }
            }
        }
        staticLines.add(Text.literal("${Text.translatable("cobblemarket.auction.seller").string}: ${entry.sellerName}") to 0xFFFFFF)
        return staticLines
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
        // 筛选展开列表滚动
        if (filterListOpen.isNotEmpty()) {
            val total = when (filterListOpen) {
                "type" -> typeOptions.size + 1
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
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        rebuildBidButtons()
        return true
    }

    private fun isInputFieldFocused() = focused?.let { f -> f === searchField || f === bidField } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true ||
        bidField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        return result
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val wasOpen = bidEntry != null
        val savedEntry = bidEntry
        val savedText = bidField?.text ?: ""
        super.resize(client, width, height)
        if (wasOpen) {
            bidEntry = null
            openBidDialog(savedEntry!!)
            bidField?.text = savedText
        }
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
        const val MAX_FILTER_LIST_ROWS = 8
    }
}
