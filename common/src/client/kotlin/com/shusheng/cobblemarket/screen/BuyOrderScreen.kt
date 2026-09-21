package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.util.StatNames
import com.shusheng.cobblemarket.client.TooltipPlacement
import com.shusheng.cobblemarket.client.playFailSound

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.ItemHoverPreview
import com.shusheng.cobblemarket.client.icon.PokemonHoverPreview
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.shusheng.cobblemarket.client.PokemonCelebrationAnimation
import com.shusheng.cobblemarket.client.ItemCelebrationAnimation
import com.shusheng.cobblemarket.client.heldIconStack
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.market.PokemonBlacklistEntry
import com.shusheng.cobblemarket.network.AcceptPendingDeliverPayload
import com.shusheng.cobblemarket.network.BuyOrderEntry
import com.shusheng.cobblemarket.network.BuyOrderPendingEntry
import com.shusheng.cobblemarket.network.ListingEntry
import com.shusheng.cobblemarket.network.BuyOrderEventPayload
import com.shusheng.cobblemarket.network.BuyOrderListDataPayload
import com.shusheng.cobblemarket.network.CancelBuyOrderPayload
import com.shusheng.cobblemarket.network.CreateItemBuyOrderPayload
import com.shusheng.cobblemarket.network.CreatePokemonBuyOrderPayload
import com.shusheng.cobblemarket.network.DeliverItemBuyOrderPayload
import com.shusheng.cobblemarket.network.ForceCancelBuyOrderPayload
import com.shusheng.cobblemarket.network.DeliverPokemonBuyOrderPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PokemonPreview
import com.shusheng.cobblemarket.network.RejectPendingDeliverPayload
import com.shusheng.cobblemarket.network.RequestBuyOrderListPayload
import com.shusheng.cobblemarket.network.RequestBalancePayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.registry.Registries
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.util.UUID
import com.shusheng.cobblemarket.client.drawItemWithBar
import com.shusheng.cobblemarket.client.PointsStateCache
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_SRC
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_TEXTURE
import com.shusheng.cobblemarket.client.CURRENCY_BUTTON_ICON_SIZE
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.client.amountWithUnit
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.MarketCurrency

/**
 * 求购单：玩家发布"我要买"订单（精灵/物品+单价区间），全服可见；
 * 卖家交付符合要求的货，按实际单价成交；买家「我的求购」tab 可关闭退冻结金。
 * 数据：全量 OPEN 快照 + 服务端增量事件（NEW/UPDATED/CLOSED）合并刷新。
 */
class BuyOrderScreen(
    private val initialTab: Int = 0,
    /** 管理员模式（管理面板「所有求购」入口）：无 tab/发布按钮，行按钮改为「下架」，点行弹强制下架确认 */
    private val adminMode: Boolean = false,
    /** 聊天通知点击直达：打开界面后列表到达时自动弹出该单的确认交付弹窗（找不到则静默等待下次刷新） */
    private val initialReviewOrderId: java.util.UUID? = null,
    /**
     * 聊天通知里**顺带带来的币种**（服务端写入命令第三段）。
     *
     * 界面一次只看一种货币，有了它就一次请求命中；没有的话（老格式通知）只能先查主货币、
     * 发现没这一单再换另一种重查 —— 多一轮往返 + 等节流窗口，实测反馈「弹窗慢半拍」。
     */
    private val initialCurrency: MarketCurrency? = null
) : Screen(Text.translatable("cobblemarket.buy_order.title")) {

    // 防串扰：界面关闭后到达的响应丢弃（照 SellSelectScreen）
    private var closed = false

    private val panelWidth = 296
    private val rowHeight = 24

    // ── 行按钮几何（建按钮与悬停窗避让共用一处定义）──
    private val rowBtnInset = 50
    private val rowBtnW = 44
    private val rowBtnH = 16
    private val rowBtnDy = 4

    private var currentTab = initialTab.coerceIn(0, 1) // 0 = 全部, 1 = 我的

    /**
     * 列表在查哪个币种（货币切换按钮控制）—— **只决定查什么，不参与渲染**：
     * 显示单位一律读订单自己的 [BuyOrderEntry.isPoints]，否则任何一处漏改就会把 RP 求购单标成 ₽。
     */
    // 通知带了币种就用它（一次命中）；否则用交付流程跨界面暂存的；都没有则主货币。
    // ⚠ 写成「先消费再覆盖」：consumeReturnCurrency() 有副作用（清空静态暂存），短路求值会跳过它
    private var currency = consumeReturnCurrency().let { initialCurrency ?: it }

    /** 货币切换按钮（RP 总开关关着时 = null，压根没建；照市场/拍卖场） */
    private var currencyButton: NineSliceButton? = null

    private var entries = listOf<BuyOrderEntry>()
    // 过滤结果缓存：数据/搜索变化时重建，render 每帧只读（避免每帧全量 filter+sort）
    private var displayCache = listOf<BuyOrderEntry>()
    // 带索引列表缓存（IndexedValue.index = entries 中的原始索引）：render 行循环每帧 entries.indexOf 是扫描热点，重建显示缓存时同步构建
    private var indexedDisplay = listOf<IndexedValue<BuyOrderEntry>>()
    private var searchField: TextFieldWidget? = null
    private var scrollOffset = 0
    private var hoveredRow = -1
    private var backButton: NineSliceButton? = null
    private var createButton: NineSliceButton? = null
    private val tabButtons = mutableListOf<NineSliceButton>()
    private val rowButtons = mutableListOf<NineSliceButton>()

    // 操作结果提示（服务端 MarketResult 到达后短暂显示）
    private var resultMsg: String? = null
    private var resultUntil = 0L
    // 提示颜色：服务端结果消息默认绿，本地错误提示红（如空手点手持按钮）
    private var resultMsgColor = 0x55FF55

    private data class FormOption(val label: String, val aspects: Set<String>)

    // 25 种性格（翻译 key, 显示名）：key 与服务端 effectiveNature.name.path 拼法一致，保证匹配
    private val natureOptions: List<Pair<String, String>> by lazy {
        com.cobblemon.mod.common.api.pokemon.Natures.all().map { n ->
            val key = "cobblemon.nature.${n.name.path}"
            val t = Text.translatable(key).string
            key to (if (t == key) n.displayName else t)
        }
    }

    // ── 创建对话框状态 ──
    private var createTab = 0 // 0 = 精灵, 1 = 物品

    /**
     * 这一单收哪种钱。**与列表的 [currency] 是两个独立状态** —— 共用一个字段会让「在弹窗里改币种」
     * 顺手把背后的列表也切走（视觉上很突兀）。只在**打开弹窗时**继承当前视图的币种：
     * 看着 RP 单点发布，默认就发 RP 单（单位看错会让价格量级差好几个数量级）。
     *
     * ⚠ 请求里发的是 `createCurrency.name`，服务端校验后才写进订单对象，此后一律读对象。
     */
    private var createCurrency = MarketCurrency.PRIMARY

    /** 创建对话框里的货币切换按钮（RP 总开关关着时 = null，压根不建） */
    private var createCurrencyButton: NineSliceButton? = null
    private var createSpeciesField: TextFieldWidget? = null
    private var createMinPriceField: TextFieldWidget? = null
    private var createMaxPriceField: TextFieldWidget? = null
    private var createNoteField: TextFieldWidget? = null
    private var createItemField: TextFieldWidget? = null
    private var createCountField: TextFieldWidget? = null
    private var heldItemButton: NineSliceButton? = null
    private var heldItemMode = false
    private val createIvFields = arrayOfNulls<TextFieldWidget>(6)
    private var createShinyFilter = PokemonBlacklistEntry.SHINY_ANY
    private var createHtFilter = PokemonBlacklistEntry.HT_ANY
    private var createShinyButton: NineSliceButton? = null
    private var createHtButton: NineSliceButton? = null
    private var createFormButton: NineSliceButton? = null
    private var createAbilityButton: NineSliceButton? = null
    private var createNatureButton: NineSliceButton? = null
    private var createTabButtons = mutableListOf<NineSliceButton>()
    private var createConfirmButton: NineSliceButton? = null
    private var createCancelButton: NineSliceButton? = null
    // 精灵预览（物种解析 → 形态选项 → 3D 模型）
    private var previewSpecies: com.cobblemon.mod.common.pokemon.Species? = null
    private var previewRenderable: RenderablePokemon? = null
    private val previewState = FloatingState()
    private var formOptions = listOf<FormOption>()
    private var formIndex = 0
    private var formListOpen = false
    private var formListScroll = 0
    private val formListButtons = mutableListOf<NineSliceButton>()
    /**
     * 物种候选 + 当前选中下标（**多候选时预览槽位右侧出现左右箭头切换**）。
     *
     * 中文物种名互为子串的情形很多（「鬼斯」是「鬼斯通」的前缀），解析必须**精确优先**、
     * 其余候选交回玩家挑 —— 只取第一个会把玩家输入的名字静默换成别的物种，而这是在发求购单，
     * 发错物种的求购单会被别有用心的玩家拿去交付。候选顺序由 `SpeciesText.candidatesByNameOrId` 保证。
     */
    private var speciesCandidates = listOf<com.cobblemon.mod.common.pokemon.Species>()
    private var speciesIndex = 0
    private var speciesPrevButton: NineSliceButton? = null
    private var speciesNextButton: NineSliceButton? = null
    // 特性/性格选择（-1 = 不限；选项由物种解析重建，性格为全表固定 25 种）
    private var abilityOptions = listOf<Pair<String, String>>() // (翻译 key, 显示名)
    private var abilityIndex = -1
    private var natureIndex = -1
    private var abilityListOpen = false
    private var natureListOpen = false
    private var natureListScroll = 0
    private val abilityListButtons = mutableListOf<NineSliceButton>()
    private val natureListButtons = mutableListOf<NineSliceButton>()
    // 物品匹配（模糊搜索，照黑名单物品对话框）
    private var matchedItems = listOf<com.shusheng.cobblemarket.client.ItemCandidate>()
    private var selectedItemIndex = -1
    private var itemListOpen = false
    private var itemListScroll = 0
    private var itemSelectButton: NineSliceButton? = null
    private val itemOptionButtons = mutableListOf<NineSliceButton>()

    // ── 买家确认弹窗状态（处理待确认交付） ──
    private var reviewEntry: BuyOrderEntry? = null

    /** 审核弹窗信息区滚动偏移（2026-09-20）：精灵单弹窗按屏幕钳制后靠滚轮看超出部分 */
    private var reviewScroll = 0
    private val reviewContentTop = 62
    private val reviewBottomH = 72
    /** 待定位的求购单（聊天点击直达）：列表数据每次更新后尝试打开确认交付弹窗，成功即清除 */
    private var pendingReviewLocate: java.util.UUID? = initialReviewOrderId

    /** 直达定位是否已经试过「换到另一种币种再找」——防止找不到时来回切币种 */
    private var reviewLocateSwitched = false
    /** 确认交付弹窗物品词条行（附魔/名称等，打开时从 itemNbt 重建一次；去首行物品名）与额外行数（布局下移用） */
    private var reviewItemTooltipLines: List<Text> = emptyList()
    /** Shift 展开的高级词条（弹窗打开时双份构建，渲染按 Shift 状态切换） */
    private var reviewItemAdvancedLines: List<Text> = emptyList()
    /** 高级词条是否在 Shift 按住状态下构建（Fabric tooltip 信息块只在构建时 Shift 按住才生成） */
    private var reviewAdvancedBuiltWithShift = false
    private var reviewAdvancedType: TooltipType? = null
    private var reviewItemExtraRows = 0
    /** 管理员模式：强制下架确认弹窗的条目与按钮 */
    private var forceCancelEntry: BuyOrderEntry? = null
    private var forceCancelConfirmButton: NineSliceButton? = null
    private var forceCancelCancelButton: NineSliceButton? = null
    private var reviewReasonField: TextFieldWidget? = null
    private var reviewAcceptButton: NineSliceButton? = null
    private var reviewRejectButton: NineSliceButton? = null
    private var reviewCancelButton: NineSliceButton? = null
    /** 确认交付弹窗里的精灵模型（打开弹窗时按 pending 重建一次；物品单/物种解析失败为 null） */
    private var reviewRenderable: RenderablePokemon? = null
    private val reviewState = FloatingState()
    /** 强制下架弹窗的精灵图标（标题正下方；2026-09-21 用户要求补上） */
    private var forceRenderable: RenderablePokemon? = null
    private val forceState = FloatingState()
    /** 弹窗打开时自动叫一声的**待播时刻**（0 = 无）。照市场确认弹窗：不能打开就播 ——
     *  `addFirstAnimation` 依赖 `state.currentModel`，要等 drawProfilePokemon 跑过才有值；
     *  且刚弹出就响玩家反应不过来。所以记「now + AUTO_CRY_DELAY_MS」，渲染处到点才播。*/
    private var forceCryAt = 0L

    /**
     * 确认交付弹窗里那个精灵图标的屏幕位置，**渲染时记录**。
     *
     * 给庆祝动画当起飞起点用 —— 确认交付后弹窗就关了、图标不复存在，坐标必须提前抓下来。
     * 之所以在渲染时记、而不是在 [acceptReview] 里重算：图标位置跟着 `dialogY` 走，
     * 弹窗布局一改重算就会错位；渲染时记录则永远和实际画出来的一致。
     */
    private var reviewIconOrigin: PokemonCelebrationAnimation.Origin? = null

    /** 确认交付弹窗里那个**物品**图标的屏幕位置（物品单才非 null）——物品庆祝动画的起飞起点，理由同上 */
    private var reviewItemIconOrigin: ItemCelebrationAnimation.Origin? = null

    /**
     * 确认交付弹窗自动叫一声的**待播时刻**（0 = 无；替玩家点一下弹窗里的精灵图标）。
     *
     * **不能在 [openReviewDialog] 里直接播**：一是 `addFirstAnimation` 依赖 `state.currentModel`，
     * 它要等 `drawProfilePokemon` 跑过才有值；二是弹窗刚弹出就响，玩家还在"看这是啥"，
     * 反应不过来声音从哪来。所以打开时只记「now + [IconCryClick.AUTO_CRY_DELAY_MS]」，渲染处到点才播。
     */
    private var reviewCryAt = 0L

    // ── 交付对话框状态 ──
    private var deliverEntry: BuyOrderEntry? = null
    // 精灵交付：所选精灵（经 SellSelectScreen 交付模式选择后回传）
    private var deliverSelectedPokemon: PokemonPreview? = null
    /** 物品交付所选形态（多形态时经 ItemVariantSelectScreen 选择；单形态自动取唯一一组） */
    private var deliverVariant: ItemStack? = null
    private var deliverSelectButton: NineSliceButton? = null
    private var deliverVariantButton: NineSliceButton? = null
    private var deliverPriceField: TextFieldWidget? = null
    private var deliverConfirmButton: NineSliceButton? = null
    private var deliverCancelButton: NineSliceButton? = null
    // 物品交付
    private var deliverCountField: TextFieldWidget? = null

    private fun getListStartY() = 68
    private fun getMaxVisibleRows() = maxOf(0, (height - getListStartY() - 48) / rowHeight)

    // 行区域避开背景边框（照精灵市场：面板与行背景错开边框宽度）
    private fun rowLeftX() = width / 2 - panelWidth / 2 + PANEL_BORDER_X
    private fun rowW() = panelWidth - 2 * PANEL_BORDER_X

    /**
     * 第 [row] 行那个按钮（交付 / 处理交付 / ✕ 下架）的矩形 —— **与 `rebuildRowButtons` 同源**
     * （建按钮和悬停窗避让共用这一处定义，别各写一份）。
     */
    private fun rowButtonRect(row: Int) = TooltipPlacement.Rect(
        rowLeftX() + rowW() - rowBtnInset, getListStartY() + row * rowHeight + rowBtnDy, rowBtnW, rowBtnH
    )

    // 行首图标/行显示数据缓存（数据到达时构建，render 每帧只读）：
    // name/nameColor/itemStack 此前每行每帧解析（物种查找只为属性色），是行渲染分配热点
    private data class IconData(
        val name: String,            // 行内截断后的显示名
        val renderable: RenderablePokemon?,
        val state: FloatingState,
        val nameColor: Int,          // 属性色（物种解析失败/任意精灵/物品为白）
        val itemStack: ItemStack?    // 物品单行图标栈
    )
    private val iconData = mutableMapOf<Int, IconData>()
    private val iconSize = 20

    // tooltip 内容缓存：静态行只取决于条目，悬停同一行时每帧重建全部文本行是悬停掉帧主因；
    // 数量（交付中剩余量会变）与到期倒计时为动态行，每帧单独构建
    private var tooltipCacheKey: UUID? = null
    private var tooltipCacheLines: List<Pair<net.minecraft.text.OrderedText?, Int>> = emptyList()
    private var tooltipCacheQuantityLine = -1  // 物品单数量行的插入点（缓存行中的索引），-1 = 无数量行
    private var tooltipCacheMaxWidth = 0

    private fun cacheIcons() {
        iconData.clear()
        entries.forEachIndexed { index, entry ->
            // 行内截断长度与行渲染一致（物品 56 / 精灵 54）
            val name = com.shusheng.cobblemarket.util.TextUtil.truncateString(
                entryNameRaw(entry), if (entry.type == "ITEM") 56 else 54)
            if (entry.type == "ITEM") {
                iconData[index] = IconData(
                    name = name, renderable = null, state = FloatingState(), nameColor = 0xFFFFFF,
                    itemStack = com.shusheng.cobblemarket.client.ItemComponentsDisplay.iconStack(entry.itemId, entry.itemComponentsSpec)
                )
                return@forEachIndexed
            }
            val species = entry.speciesId?.let { Identifier.tryParse(it)?.let { id -> PokemonSpecies.getByIdentifier(id) } }
            val nameColor = species?.let { typeColor("cobblemon.type.${it.primaryType.name.lowercase()}") } ?: 0xFFFFFF
            if (species == null) {
                // 任意精灵单/物种解析失败：不渲染 3D（行内画「?」，与无缓存时一致），名称与颜色仍用缓存
                iconData[index] = IconData(name, null, FloatingState(), nameColor, null)
                return@forEachIndexed
            }
            // 用求购单真实 aspects 渲染形态（地区形态/性别差异等，照拍卖场行图标）；
            // 仅闪要求时叠加 shiny aspect 渲染闪光形态
            val aspects = entry.aspects.toMutableSet()
            if (entry.shinyFilter == PokemonBlacklistEntry.SHINY_YES && "shiny" !in aspects) aspects.add("shiny")
            iconData[index] = IconData(name, RenderablePokemon(species, aspects, ItemStack.EMPTY), FloatingState(), nameColor, null)
        }
    }

    private fun renderPokemonIcon(
        context: DrawContext, index: Int, x: Int, y: Int, size: Int, delta: Float = 0f,
        /** 本行是否被悬停（跟左侧大图一起走 walk）。**必须由调用方按行索引算**：
         *  早先用 `IconRegistry.isMouseInRow` 只看纵向，鼠标跑到面板外侧、只要 y 平齐也会触发动画（2026-09-15） */
        preview: Boolean = false
    ) {
        val data = iconData[index] ?: return
        val renderable = data.renderable ?: return
        // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
        val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
            com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
        try {
            PokemonIconRenderer.draw(
                context = context,
                renderable = renderable,
                state = data.state,
                x = x, y = y, size = size,
                delta = delta,
                animate = useFloat,
                preview = preview
            )
        } catch (_: Exception) {
        }
    }

    // 属性色（照精灵市场）
    private fun typeColor(tk: String) = when (tk.substringAfterLast(".").lowercase()) {
        "normal" -> 0xAAAA99; "fire" -> 0xFF4422; "water" -> 0x3399FF
        "electric" -> 0xFFCC33; "grass" -> 0x77CC55; "ice" -> 0x66CCFF
        "fighting" -> 0xBB5544; "poison" -> 0xAA5599; "ground" -> 0xDDBB55
        "flying" -> 0x8899FF; "psychic" -> 0xFF5599; "bug" -> 0xAABB22
        "rock" -> 0xBBAA66; "ghost" -> 0x6666BB; "dragon" -> 0x7766EE
        "dark" -> 0x775544; "steel" -> 0xAAAABB; "fairy" -> 0xFFAAFF
        else -> 0xFFFFFF
    }

    // ── 文本工具 ──

    /**
     * **纯字符串版（无颜色）** —— 只给「搜索匹配 / 量文字宽度 / 按钮文字」这类**不直接把名字画出去**的场合用。
     *
     * ⚠ 要**画名字**请用 [entryNameText]（带属性色）；拿这个去画就是**白字** ——
     * 2026-09-20 用户实测发现求购单的悬停窗 / 强制下架弹窗正是因为直接画了它而漏色，故改名以标明区别。
     */
    private fun entryNameRaw(entry: BuyOrderEntry): String {
        if (entry.type == "ITEM") {
            val id = Identifier.tryParse(entry.itemId) ?: return entry.itemId
            val item = Registries.ITEM.get(id)
            val name = if (item.name.string == item.translationKey) id.path else item.name.string
            // 带组件要求的订单追加摘要（如「锋利V」）
            val summary = com.shusheng.cobblemarket.client.ItemComponentsDisplay.summary(entry.itemComponentsSpec)
            return if (summary.isEmpty()) name else "$name（$summary）"
        }
        val key = entry.speciesKey ?: return Text.translatable("cobblemarket.buy_order.any_pokemon").string
        val t = Text.translatable(key).string
        return if (t == key) key else t
    }

    /**
     * [entryNameRaw] 的**带色版**：精灵名按**属性色**（与市场/黑名单同口径，走 [EntryBadgeRenderer.speciesTypeColor]）。
     * ⚠ 凡是要**画出去**的地方都用这个 —— 纯 String 版是白色，2026-09-20 用户实测发现悬停窗/强制下架弹窗漏了。
     * 物品单与「任意精灵」保持白色（物品名走稀有度色是另一套，行内已单独处理）。
     */
    private fun entryNameText(entry: BuyOrderEntry): Text =
        Text.literal(entryNameRaw(entry)).styled {
            it.withColor(EntryBadgeRenderer.speciesTypeColor(entry.speciesId.orEmpty(), entry.aspects))
        }

    /**
     * 行内价格区间用缩写（与物品市场网格同款 k/M/B）；完整价格见悬停面板。
     *
     * 单位按**订单自己的币种**（`entry.isPoints`），走统一入口 `amountWithUnit` —— 别各写各的
     * `if (isPoints)`，漏一处就会把 RP 求购单标成 ₽。返回 MutableText 而不是 String：
     * RP 单位是**字体字形**，转成 String 再拼接会把字体丢掉（图标变豆腐块），只能用 Text 拼。
     */
    private fun priceRangeText(entry: BuyOrderEntry): MutableText = amountWithUnit(
        "${com.shusheng.cobblemarket.client.formatPriceShort(entry.minPrice)}~" +
            com.shusheng.cobblemarket.client.formatPriceShort(entry.maxPrice),
        entry.isPoints, "", inline = true
    )

    // 精灵单固定 1 只，不显示数量（空串 = 无数量段）
    private fun countText(entry: BuyOrderEntry): String =
        if (entry.type == "ITEM") {
            if (entry.remainingCount < entry.totalCount) "${entry.remainingCount}/${entry.totalCount}" else "×${entry.totalCount}"
        } else ""

    private fun isMine(entry: BuyOrderEntry): Boolean = entry.buyerUuid == client?.player?.uuid

    // 精灵条件徽章（行内名称右侧）：★ = 仅闪, ☆ = 仅非闪；特训要求不在行内显示（悬停面板有完整文字）
    private fun conditionBadges(entry: BuyOrderEntry): List<Pair<String, Int>> {
        val badges = mutableListOf<Pair<String, Int>>()
        if (entry.type != "POKEMON") return badges
        when (entry.shinyFilter) {
            PokemonBlacklistEntry.SHINY_YES -> badges.add("★" to GOLD_COLOR)
            PokemonBlacklistEntry.SHINY_NO -> badges.add("☆" to 0xAAAAAA)
        }
        return badges
    }

    // ── 主界面 ──

    override fun init() {
        super.init()
        sendToServer(RequestBalancePayload())
        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        // 返回按钮：右缘离面板右缘 12px（边框视觉宽约 10~12，内缩 8 仍压框；顶边 13px 与拍卖场一致）
        backButton = NineSliceButton(
            width / 2 + panelWidth / 2 - 12 - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(if (adminMode) AdminScreen() else MarketEntryScreen(skipDropAnim = true)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back"),
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(backButton)

        // 货币切换按钮（返回按钮左侧 4px）：切换**看哪个币种的求购单**。
        // ⚠ 与市场/拍卖场同一个按钮、同一套逻辑，只是本界面的返回按钮右缘内缩了 12px，
        //   所以跟着它取 panelWidth-86（那四处是 -74）—— 对齐的是返回按钮，不是绝对值。
        // ⚠ RP 总开关关着 → 整个按钮不建（那时 RP 系统停摆，留个点不动的灰按钮更难看）。
        //   玩家端**不受「玩家间 RP 交易」开关约束**：买家要能看到并关掉自己的存量 RP 求购单
        //   （取回冻结金）、卖家要能看到自己的货被退回；交付 / 接受仍由服务端拦（pointsGateBlocked）
        currencyButton = if (PointsStateCache.enabled) {
            NineSliceButton(
                leftX + panelWidth - 86, 13, 20, 16,
                Text.literal(""), { onCurrencyClick() },
                iconTexW = POINTS_SYMBOL_SRC, iconTexH = POINTS_SYMBOL_SRC,
                iconScale = CURRENCY_BUTTON_ICON_SIZE / POINTS_SYMBOL_SRC.toFloat(),
                tooltip = Text.translatable("cobblemarket.gui.currency_switch", inlineCurrencyUnit(), CurrencyService.pointsIcon())
            ).also {
                updateCurrencyButton(it, currency == MarketCurrency.POINTS)
                addDrawableChild(it)
            }
        } else null

        // 管理员模式：无 tab、无发布按钮，只有列表 + 下架
        if (!adminMode) {
            // tab 按钮：全部求购 | 我的求购（水平居中，间距 4px）
            val tabW = 70
            val tabGap = 4
            val tabStart = centerX - tabW - tabGap / 2
            tabButtons.clear()
            listOf(0, 1).forEachIndexed { i, tab ->
                val btn = NineSliceButton(
                    tabStart + i * (tabW + tabGap), 32, tabW, 14,
                    Text.literal(""),
                    { switchTab(tab) },
                    texture = BUY_ORDER_BUTTON_TEXTURE,
                    texH = BUY_ORDER_BUTTON_TEX_H
                )
                tabButtons.add(btn)
                addDrawableChild(btn)
            }
            updateTabButtons()

            // 发布求购按钮：**挪到搜索框那一行**（原在 tab 行右侧，2026-09-21 用户拍板 ——
            // 搜索框本来占满整行、用不了这么长，让给它正好）。
            // 宽 50、高 16（与搜索框齐平）；右缘 = rowLeftX()+rowW()，与原来的"离面板右缘 12px"一致
            val createBtn = NineSliceButton(
                rowLeftX() + rowW() - 50, 48, 50, 16,
                Text.literal(""),
                { openCreateDialog() },
                iconLeft = Identifier.of("cobblemarket", "textures/gui/choose.png"),
                iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
                texture = BUY_ORDER_BUTTON_TEXTURE,
                texH = BUY_ORDER_BUTTON_TEX_H
            )
            createBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.buy_order.create")))
            createButton = createBtn
            addDrawableChild(createBtn)
        }

        // 搜索框（y=48；与行区域同宽、右侧让出 50+2 给发布按钮；本地过滤——列表全量下发，照拍卖场模式）
        val savedSearch = searchField?.text ?: ""
        searchField = TextFieldWidget(textRenderer, rowLeftX(), 48, rowW() - 52, 16, Text.translatable("cobblemarket.gui.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.search").formatted(Formatting.GRAY))
        // 本地过滤无网络请求，无需防抖；搜索变化重置滚动并重建行按钮
        searchField?.setChangedListener {
            rebuildDisplayList()
            scrollOffset = 0
            hoveredRow = -1
            rebuildRowButtons()
        }
        addSelectableChild(searchField)
        addDrawableChild(searchField)
        searchField?.text = savedSearch

        // 不重置 scrollOffset：交付弹窗关闭/resize 重建时保留浏览位置（switchTab 才显式归零）
        requestBuyOrderList()
        rebuildDisplayList()
    }

    // ── 列表请求（节流排队） ──

    /** 最近一次列表请求的发送时刻；补发节奏对齐服务端节流窗口并留 100ms 余量 */
    private var lastListRequestAt = 0L

    /**
     * 有请求在排队（本轮节流窗口内被推迟）。
     *
     * ⚠ 服务端对 `buy_order_list` 有 500ms 节流，**超频的请求是静默丢弃的**（不回错误）。
     * 而币种切换是「本地先按新币种过滤（旧行立刻消失）+ 再向服务端要新币种的全量列表」，
     * 一旦这次请求被丢弃，本地过滤留下的就是**空列表** —— 表现为「飞快连点两次货币按钮，
     * 列表就空了」。所以窗口内的请求必须排队，由 [tick] 补发（照 MarketScreen 的成熟做法）。
     */
    private var pendingListRequest = false

    /** 列表请求统一入口：窗口允许立即发，否则排队等 tick 补发 */
    private fun requestBuyOrderList() {
        if (System.currentTimeMillis() - lastListRequestAt >= LIST_REQUEST_INTERVAL_MS) {
            lastListRequestAt = System.currentTimeMillis()
            pendingListRequest = false
            sendToServer(RequestBuyOrderListPayload(currency.name))
        } else {
            pendingListRequest = true
        }
    }

    override fun tick() {
        super.tick()
        // 补发排队中的列表请求：发的是**当时的** currency，所以连点会自动合并到最终选择的那一种
        if (pendingListRequest && System.currentTimeMillis() - lastListRequestAt >= LIST_REQUEST_INTERVAL_MS) {
            lastListRequestAt = System.currentTimeMillis()
            pendingListRequest = false
            sendToServer(RequestBuyOrderListPayload(currency.name))
        }
    }

    /** 点货币按钮：列表是**查看**用途，不拦开关（买家要能切过去关掉存量 RP 单取回冻结金） */
    private fun onCurrencyClick() {
        // ⚠ 连点两次时第二次必然落在上一次的节流窗口内（500ms），走排队入口才不会丢掉这次请求
        currency = if (currency == MarketCurrency.POINTS) MarketCurrency.PRIMARY else MarketCurrency.POINTS
        currencyButton?.let { updateCurrencyButton(it, currency == MarketCurrency.POINTS) }
        // 本地先按新币种过一遍（旧币种条目立刻消失），回到顶部，再向服务端要新币种的全量列表。
        // ⚠ 本界面没有分页，「重置页码」对应的就是滚动位置归零
        rebuildDisplayList()
        scrollOffset = 0
        hoveredRow = -1
        rebuildRowButtons()
        requestBuyOrderList()
    }

    /** 刷新货币按钮外观：主货币显示 `₽`，RP 显示图标（与市场/拍卖/上架界面同一套口径）。
     *  列表按钮与创建对话框按钮共用（[points] 由各自的币种状态传入） */
    private fun updateCurrencyButton(btn: NineSliceButton, points: Boolean) {
        btn.setMessage(Text.literal(if (points) "" else "₽"))
        btn.iconLeft = if (points) POINTS_SYMBOL_TEXTURE else null
    }

    /** 点创建对话框的货币按钮：「玩家间 RP 交易」没开就只给反馈、不切（**创建是拦截点**，与上架/拍卖创建一致） */
    private fun onCreateCurrencyClick() {
        if (!PointsStateCache.marketEnabled) {
            playFailSound()
            client?.player?.sendMessage(
                // ⚠ 提醒类消息用红色，和普通反馈区分开（图标自带白色，不受影响）
                Text.translatable("cobblemarket.network.points_market_disabled", CurrencyService.pointsIcon())
                    .formatted(Formatting.RED),
                false
            )
            return
        }
        createCurrency = if (createCurrency == MarketCurrency.POINTS) MarketCurrency.PRIMARY else MarketCurrency.POINTS
        createCurrencyButton?.let { updateCurrencyButton(it, createCurrency == MarketCurrency.POINTS) }
    }

    private fun updateTabButtons() {
        val keys = listOf("cobblemarket.buy_order.tab_all", "cobblemarket.buy_order.tab_mine")
        tabButtons.forEachIndexed { i, btn ->
            val label = Text.translatable(keys[i]).string
            btn.setMessage(if (currentTab == i) com.shusheng.cobblemarket.util.TextUtil.selectedText(label) else Text.literal(label))
        }
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        searchField?.text = ""
        rebuildDisplayList()
        scrollOffset = 0
        hoveredRow = -1
        updateTabButtons()
        rebuildRowButtons()
    }

    // ── 数据接收 ──

    fun onBuyOrderList(payload: BuyOrderListDataPayload) {
        entries = payload.entries
        rebuildDisplayList()
        cacheIcons()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        rebuildRowButtons()
        tryLocateInitialReview(allowSwitch = true)
        // 交付流程回传：从精灵选择界面返回后恢复交付弹窗并带入所选精灵
        val orderId = pendingDeliverOrderId ?: return
        pendingDeliverOrderId = null
        val order = entries.firstOrNull { it.id == orderId }
        if (order == null) {
            // 订单已结束（期间被关闭/凑满），丢弃所选精灵/形态
            pendingDeliverPokemon = null
            pendingDeliverVariant = null
            return
        }
        val pokemon = pendingDeliverPokemon
        pendingDeliverPokemon = null
        // 物品形态选择回传：先消费到 deliverVariant 再打开弹窗，防「多形态又跳回选择界面」死循环
        val variant = pendingDeliverVariant
        pendingDeliverVariant = null
        if (variant != null) deliverVariant = variant
        openDeliverDialog(order)
        if (pokemon != null) {
            deliverSelectedPokemon = pokemon
            updateDeliverSelectButton()
        }
        updateDeliverConfirmActive()
    }

    fun onBuyOrderEvent(payload: BuyOrderEventPayload) {
        val e = payload.entry
        when (payload.event) {
            "NEW" -> if (entries.none { it.id == e.id }) entries = listOf(e) + entries // 最新的在最上面
            "UPDATED" -> entries = entries.map { if (it.id == e.id) e else it }
            "CLOSED" -> entries = entries.filterNot { it.id == e.id }
        }
        rebuildDisplayList()
        cacheIcons()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        rebuildRowButtons()
        tryLocateInitialReview()
    }

    /**
     * 聊天点击直达：列表数据就绪后定位求购单并弹其确认交付弹窗。
     *
     * [allowSwitch] 只在**服务端全量列表**到达时为 true：当前币种里没有 → 那张单多半在另一种币种
     * （直达时界面默认看主货币），换过去再找一次，仍然没有就放弃（单已结束 / 被强制下架）。
     * 增量事件不能触发切换与放弃 —— 等待另一个币种列表的途中飘进来一条事件就会把定位搅黄。
     */
    private fun tryLocateInitialReview(allowSwitch: Boolean = false) {
        val id = pendingReviewLocate ?: return
        entries.find { it.id == id }?.let { e ->
            pendingReviewLocate = null
            openReviewDialog(e)
            return
        }
        if (!allowSwitch) return
        // 通知带了币种（正常情况）→ 那是服务端的权威值，没必要再换着找：找不到就是这单已经结束了。
        // 直接放弃，省掉一轮请求，也免得列表当着玩家的面从一种币跳回另一种
        if (initialCurrency != null) {
            pendingReviewLocate = null
            return
        }
        if (!reviewLocateSwitched) {
            reviewLocateSwitched = true
            onCurrencyClick()
        } else {
            pendingReviewLocate = null
        }
    }

    fun onMarketResult(payload: MarketResultPayload) {
        if (payload.success) {
            // 创建/关闭/交付/确认成功：广播事件会刷新列表，这里只关弹窗
            if (deliverEntry != null || reviewEntry != null) closeDialogs()
            // 结果同步发聊天框（与拍卖界面一致）
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.GREEN), false)
        } else {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.RED), false)
            // 求购单已结束（条目从列表消失）时自动关闭交付/确认弹窗
            val deliverGone = deliverEntry?.id?.let { id -> entries.none { it.id == id } } == true
            val reviewGone = reviewEntry?.id?.let { id -> entries.none { it.id == id } } == true
            if (deliverGone || reviewGone) {
                closeDialogs()
            }
        }
        resultMsg = payload.message.string
        // 服务端结果消息统一绿色（覆盖本地错误提示的红色残留）
        resultMsgColor = 0x55FF55
        resultUntil = System.currentTimeMillis() + 3000
    }

    // ── 过滤与列表 ──

    private fun displayList(): List<BuyOrderEntry> = displayCache

    /** 数据/搜索变化时重建显示缓存（排序只在这里做一次，render 每帧只读） */
    private fun rebuildDisplayList() {
        val query = searchField?.text?.trim()?.takeIf { it.isNotEmpty() }
        displayCache = entries.filter { entry ->
            // 币种先行：服务端列表已按币种过滤，但**增量事件（NEW/UPDATED/CLOSED）是全币种广播**的，
            // 不在这里再筛一道，别的币种的求购单就会飘进当前列表（照拍卖场）
            if (entry.isPoints != (currency == MarketCurrency.POINTS)) return@filter false
            // 管理员模式显示全部（无「我的」过滤）
            if (adminMode || currentTab == 0) true else isMine(entry)
        }.filter { entry ->
            // 搜索：物种名/物品名/买家名（本地过滤，照拍卖场模式）
            query == null || entryNameRaw(entry).contains(query, ignoreCase = true) ||
                entry.buyerName.contains(query, ignoreCase = true) ||
                // 物品单走搜索索引：带组件要求的订单按组件精确（搜「锋利」只出锋利V 订单、搜「打鼾」只出打鼾 TM 订单），
                // 无组件订单仅物品文本命中（搜「锋利」不再带出所有附魔书订单）
                (entry.type == "ITEM" && entryMatchesItem(entry, query))
        }.sortedByDescending { it.createdAt }
        indexedDisplay = displayCache.map { IndexedValue(entries.indexOf(it), it) }
    }

    /** 求购单条目级搜索匹配：带组件要求的订单按组件精确，无组件订单仅物品文本命中（共用 ItemSearchIndex.ruleEntryMatches） */
    private fun entryMatchesItem(entry: BuyOrderEntry, query: String): Boolean =
        com.shusheng.cobblemarket.client.ItemSearchIndex.ruleEntryMatches(entry.itemId, entry.itemComponentsSpec, query)

    private fun displayCount() = displayList().size

    private fun rebuildRowButtons() {
        rowButtons.forEach { remove(it) }
        rowButtons.clear()
        // 弹窗打开时行按钮保持隐藏：事件广播触发的重建会新建默认可见的按钮，
        // 必须在这里拦截（弹窗关闭时 closeDialogs→init 会正常重建）
        if (anyDialogOpen()) return
        // 管理员模式无行按钮：点击行直接弹下架确认（照 AdminAuctionScreen）
        if (adminMode) return
        val startY = getListStartY()
        displayList().drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, entry ->
            val y = startY + i * rowHeight
            val mine = isMine(entry)
            val hasPending = entry.pending != null
            // 行按钮语义：我的+待确认=处理交付；我的=关闭（下架，统一红 ✕）；他人+待确认=待确认（锁定）；他人=交付
            val btn = NineSliceButton(
                rowLeftX() + rowW() - rowBtnInset, y + rowBtnDy, rowBtnW, rowBtnH,
                if (mine && !hasPending) Text.literal("✕").formatted(Formatting.RED)
                else Text.translatable(when {
                    mine && hasPending -> "cobblemarket.buy_order.review"
                    hasPending -> "cobblemarket.buy_order.pending_mark"
                    else -> "cobblemarket.buy_order.deliver"
                }),
                {
                    when {
                        mine && hasPending -> openReviewDialog(entry)
                        mine -> confirmCloseOrder(entry)
                        else -> openDeliverDialog(entry)
                    }
                },
                texture = BUY_ORDER_BUTTON_TEXTURE,
                texH = BUY_ORDER_BUTTON_TEX_H
            )
            // 他人订单有待确认交付：按钮视觉禁用（服务端同样锁定）
            if (hasPending && !mine) btn.active = false
            rowButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    private fun confirmCloseOrder(entry: BuyOrderEntry) {
        sendToServer(CancelBuyOrderPayload(entry.id))
    }

    /** 任意弹窗打开中（创建/交付/审查/强制下架）——所有「弹窗状态检查点」统一走这里，新弹窗只加一处 */
    private fun anyDialogOpen(): Boolean =
        createTabButtons.isNotEmpty() || deliverEntry != null || reviewEntry != null || forceCancelEntry != null

    // ── 管理员强制下架确认弹窗（照 AdminAuctionScreen 下架弹窗模板） ──

    private fun openForceCancelDialog(entry: BuyOrderEntry) {
        buildForceRenderable(entry)
        forceCryAt = System.currentTimeMillis() + com.shusheng.cobblemarket.client.icon.IconCryClick.AUTO_CRY_DELAY_MS
        forceCancelEntry = entry
        forceScroll = 0
        // 隐藏下层控件（弹窗打开期间不可交互；closeForceCancelDialog 的 init 重建会恢复）
        searchField?.visible = false
        backButton?.visible = false
        currencyButton?.visible = false
        rowButtons.forEach { it.visible = false }
        val centerX = width / 2
        val btnY = forceButtonY(entry)

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderForceCancelBackground(context, delta)
            }
        })

        forceCancelConfirmButton = NineSliceButton(
            centerX - 60, btnY, 56, 20,
            Text.translatable("cobblemarket.buy_order.force_cancel"),
            { confirmForceCancel() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(forceCancelConfirmButton)
        forceCancelCancelButton = NineSliceButton(
            centerX + 4, btnY, 56, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeForceCancelDialog() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(forceCancelCancelButton)
    }

    private fun closeForceCancelDialog() {
        forceCancelEntry = null
        forceCancelConfirmButton = null
        forceCancelCancelButton = null
        clearChildren()
        init()
    }

    private fun confirmForceCancel() {
        val entry = forceCancelEntry ?: return
        sendToServer(ForceCancelBuyOrderPayload(entry.id))
        closeForceCancelDialog()
    }

    private fun handleForceCancelDialogClick(mx: Int, my: Int) {
        val entry = forceCancelEntry ?: return
        val centerX = width / 2
        val btnW = 56
        val btnH = 20
        val btnY = forceButtonY(entry)
        val confirmX = centerX - 60
        val cancelX = centerX + 4
        if (mx in confirmX..(confirmX + btnW) && my in btnY..(btnY + btnH)) {
            confirmForceCancel()
        } else if (mx in cancelX..(cancelX + btnW) && my in btnY..(btnY + btnH)) {
            closeForceCancelDialog()
        }
    }

    /** 「数量」动态行（物品单：剩余/总数）—— 悬停窗与强制下架弹窗共用 */
    private fun quantityLineOf(entry: BuyOrderEntry): Pair<net.minecraft.text.OrderedText?, Int> {
        val qty = if (entry.remainingCount < entry.totalCount)
            "${entry.remainingCount}/${entry.totalCount}" else entry.totalCount.toString()
        return Text.literal("${Text.translatable("cobblemarket.buy_order.tooltip_quantity").string} $qty")
            .asOrderedText() to 0xFFFFFF
    }

    /** 「到期」动态行 —— 悬停窗与强制下架弹窗共用 */
    private fun expiresLineOf(entry: BuyOrderEntry): Pair<net.minecraft.text.OrderedText?, Int> =
        Text.translatable("cobblemarket.buy_order.tooltip_expires").append(
            com.shusheng.cobblemarket.util.RemainingTime.text(
                entry.expiresAt, "cobblemarket.buy_order.ending", secondPrecision = false)
        ).asOrderedText() to 0xFFFFFF

    /**
     * 强制下架弹窗要显示的**完整信息行** = [requirementStaticLines] + 数量（物品单）+ 到期，
     * 与行悬停窗内容一致。弹窗只在打开时看，直接全量构建（不像悬停窗那样把静态行缓存起来）。
     */
    private fun requirementDialogLines(entry: BuyOrderEntry): List<Pair<net.minecraft.text.OrderedText?, Int>> {
        val (static, qtyAt) = requirementStaticLines(entry, forceContentW())
        val out = ArrayList<Pair<net.minecraft.text.OrderedText?, Int>>(static.size + 2)
        if (qtyAt >= 0) {
            out.addAll(static.subList(0, qtyAt))
            out.add(quantityLineOf(entry))
            out.addAll(static.subList(qtyAt, static.size))
        } else {
            out.addAll(static)
        }
        out.add(expiresLineOf(entry))
        return out
    }

    // ── 强制下架弹窗几何（2026-09-20：加信息区后必须钳制高度，照其余弹窗那套）──
    /** 精灵图标顶（标题正下方，28px） */
    private val forceIconTop = 26
    private val forceIconSize = 28
    /** 信息区顶：图标(26~54) + 名称行(60) 之下 */
    private val forceInfoTop = 74
    private val forcePad = 10
    private val forceDialogW: Int get() = minOf(300, width - 40).coerceAtLeast(200)
    private var forceScroll = 0

    private fun forceContentW() = forceDialogW - 2 * forcePad
    /** 底部区高：提示 2 行（待确认交付时 3 行）+ 与信息区/按钮的间隔 + 按钮 20 + 底边距 */
    // 54（原 78，-24）：信息区下移 24px 给精灵图标腾位后，可视区底同步下移，
    // 可视高度因此恢复原值（总高不变、内容不再被截断）；提示与按钮钉在底部，不受本值影响
    private fun forceFooterH(entry: BuyOrderEntry): Int = 54 + if (entry.pending != null) 10 else 0

    private fun forceDesignH(entry: BuyOrderEntry): Int =
        // ⚠ 信息区下移 24px 给图标腾位，footer 同步 -24 —— 两头相抵：
        // 弹窗总高不变、可视高度也不变（2026-09-21 用户明确「无需增高」）
        forceInfoTop + requirementDialogLines(entry).size * 10 + forceFooterH(entry)

    /** 弹窗实际高度：**按屏幕钳制**（信息行多时设计高度会顶出屏幕），超出部分靠滚轮看 */
    private fun forceDialogH(entry: BuyOrderEntry): Int = minOf(forceDesignH(entry), height - 8)
    private fun forceDialogY(entry: BuyOrderEntry): Int = height / 2 - forceDialogH(entry) / 2
    private fun forceViewTop(entry: BuyOrderEntry): Int = forceDialogY(entry) + forceInfoTop
    private fun forceViewBottom(entry: BuyOrderEntry): Int = forceDialogY(entry) + forceDialogH(entry) - forceFooterH(entry)
    private fun forceMaxScroll(entry: BuyOrderEntry): Int =
        maxOf(0, requirementDialogLines(entry).size * 10 - (forceViewBottom(entry) - forceViewTop(entry)))

    /** 按钮 Y（钉弹窗底）—— 建控件与命中判定共用这一处 */
    private fun forceButtonY(entry: BuyOrderEntry): Int = forceDialogY(entry) + forceDialogH(entry) - 30

    private fun renderForceCancelBackground(context: DrawContext, delta: Float) {
        val entry = forceCancelEntry ?: return
        val centerX = width / 2
        val dialogW = forceDialogW
        val dialogH = forceDialogH(entry)
        val dialogX = centerX - dialogW / 2
        val dialogY = forceDialogY(entry)

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.buy_order.force_cancel_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)
        // 精灵图标（标题正下方，2026-09-21 补）：非精灵单 / 任意精灵单不画
        forceRenderable?.let { rp ->
            val iconX = centerX - forceIconSize / 2
            val iconY = dialogY + forceIconTop
            context.matrices.push()
            context.matrices.translate(iconX.toDouble(), iconY.toDouble(), 0.0)
            context.matrices.scale(forceIconSize / 66f, forceIconSize / 66f, 1f)
            context.drawTexture(
                net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png"),
                0, 0, 0f, 0f, 66, 66, 66, 66
            )
            context.matrices.pop()
            PokemonIconRenderer.draw(
                context = context, renderable = rp, state = forceState,
                x = iconX, y = iconY, size = forceIconSize, delta = delta,
                animate = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                    com.shusheng.cobblemarket.client.IconAnimMode.FLOAT,
                useScissor = false
            )
            // 铁律：含精灵图标的弹窗打开要自动叫一声（到点且模型已就绪才播）
            if (forceCryAt != 0L && System.currentTimeMillis() >= forceCryAt) {
                forceCryAt = 0L
                com.shusheng.cobblemarket.client.icon.IconCryClick.play(forceState, rp)
            }
        }
        context.drawCenteredTextWithShadow(textRenderer,
            entryNameText(entry),
            centerX, dialogY + 60, 0xFFFFFF)

        // 完整需求信息（与行悬停窗同一份构造）：左对齐、超出可视区时**可滚动**（scissor 裁剪）
        val infoLines = requirementDialogLines(entry)
        forceScroll = forceScroll.coerceIn(0, forceMaxScroll(entry))
        val viewTop = forceViewTop(entry)
        val viewBottom = forceViewBottom(entry)
        val infoX = dialogX + forcePad
        val contentW = forceContentW()
        context.enableScissor(dialogX, viewTop, dialogX + dialogW, viewBottom)
        // 第一行是精灵名（标题下已居中显示过一次）→ 跳过，避免同一名字画两遍
        infoLines.drop(1).forEachIndexed { i, (line, color) ->
            val rowY = viewTop + i * 10 - forceScroll
            if (line == null) {
                context.fill(centerX - contentW / 2, rowY + 4, centerX + contentW / 2, rowY + 5, 0xFF555555.toInt())
            } else {
                context.drawCenteredTextWithShadow(textRenderer, line, centerX, rowY, color)
            }
        }
        context.disableScissor()

        // 提示（2 行；有待确认交付时多 1 行红字）—— 钉在按钮上方，从下往上排
        val hints = buildList {
            add(Text.translatable("cobblemarket.buy_order.force_cancel_hint1").formatted(Formatting.GRAY))
            add(Text.translatable("cobblemarket.buy_order.force_cancel_hint2").formatted(Formatting.GRAY))
            if (entry.pending != null) {
                add(Text.translatable("cobblemarket.buy_order.force_cancel_hint3").formatted(Formatting.RED))
            }
        }
        val lastHintY = forceButtonY(entry) - 14
        hints.forEachIndexed { i, hint ->
            context.drawCenteredTextWithShadow(
                textRenderer, hint, centerX, lastHintY - (hints.size - 1 - i) * 10, 0xFFFFFF
            )
        }

        // 按钮位置每帧同步（弹窗高度随内容/屏幕变，按钮必须跟着走）
        val btnY = forceButtonY(entry)
        forceCancelConfirmButton?.y = btnY
        forceCancelCancelButton?.y = btnY
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        IconRegistry.beginFrame(mouseX, mouseY)
        val panelLeft = width / 2 - panelWidth / 2
        val panelTop = 2
        val panelBottom = height - 32
        val sliceH = 16

        drawSlice(context, BUY_ORDER_PANEL_TOP, panelLeft, panelTop)
        var y = panelTop + sliceH
        // 整段 middle，末段按剩余高度拉伸（防止压进 bottom 区域）
        while (y + sliceH <= panelBottom - sliceH) {
            drawSlice(context, BUY_ORDER_PANEL_MIDDLE, panelLeft, y)
            y += sliceH
        }
        if (y < panelBottom - sliceH) {
            drawSliceStretched(context, BUY_ORDER_PANEL_MIDDLE, panelLeft, y, panelBottom - sliceH - y)
        }
        drawSlice(context, BUY_ORDER_PANEL_BOTTOM, panelLeft, panelBottom - sliceH)

        val startY = getListStartY()
        val visibleRows = getMaxVisibleRows()
        val listAreaBottom = startY + visibleRows * rowHeight
        val count = displayCount()

        hoveredRow = -1
        val rowL = rowLeftX()
        if (mouseX in rowL..(rowL + rowW()) && mouseY in startY..listAreaBottom) {
            val row = (mouseY - startY) / rowHeight
            val shownCount = minOf(visibleRows, count - scrollOffset)
            if (row in 0 until shownCount) hoveredRow = row
        }

        // 行背景避开面板左右边框（不覆盖边框装饰）
        repeat(minOf(visibleRows, maxOf(0, count - scrollOffset))) { di ->
            val rowY = startY + di * rowHeight
            val rowState = if (di == hoveredRow) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, rowL, rowY, rowW(), rowHeight, rowState, ROW_BACKGROUND_TEX_H)
        }

        // ── 前景绘制（标题/余额/行内容/tooltip 全部在 renderBackground 绘制：
        //     渲染顺序早于 children 中的弹窗遮罩） ──
        // 弹窗打开时直接跳过前景绘制——遮罩/衬底在不同渲染层（文字/模型）下不可靠，
        // 行内容不渲染才是彻底无透的保证（黑名单/价格限制弹窗同款）
        if (anyDialogOpen()) return

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable(if (adminMode) "cobblemarket.op.buy_order" else "cobblemarket.buy_order.title").formatted(Formatting.GOLD),
            centerX, 20, 0xFFFFFF)

        // 余额（左上角，避开背景左边框，来自全局缓存）
        val balText = com.shusheng.cobblemarket.client.BalanceCache.balance
        if (balText.isNotEmpty()) {
            context.drawTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.gui.balance",
                    Text.literal(balText + " " + com.shusheng.cobblemarket.client.inlineCurrencyUnit()).formatted(Formatting.GOLD)),
                leftX + PANEL_BORDER_X + 2, 20, 0xFFFFFF)
        }

        // 分隔线缩进到行区域（不压背景左右边框）
        context.fill(rowL, startY - 2, rowL + rowW(), startY - 1, 0xFF555555.toInt())

        val displayList = displayList()

        if (displayList.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.buy_order.empty").formatted(Formatting.GRAY),
                centerX, startY + 50, 0xFFFFFF)
        }

        // 弹窗打开时行内图标不渲染（物品/精灵模型图标走独立渲染层，z 平移盖不住，会刺穿遮罩）
        val anyDialogOpen = anyDialogOpen()
        indexedDisplay.drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, (origIndex, entry) ->
            val y = startY + i * rowHeight
            val slotX = rowL + 2
            val slotY = y + 2

            // 行首图标槽（照精灵市场）：精灵单 3D 渲染（弹窗打开时不渲染——模型层在衬底之上，压暗仍会浮在弹窗背景上；
            // 任意精灵画 ?），物品单画物品图标（弹窗打开时隐藏）
            val rowData = iconData[origIndex]
            if (entry.type == "POKEMON") {
                context.matrices.push()
                context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
                context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
                context.drawTexture(POKEMON_SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
                context.matrices.pop()
                if (rowData?.renderable != null) {
                    if (!anyDialogOpen) renderPokemonIcon(context, origIndex, slotX, slotY, iconSize, delta = delta, preview = i == hoveredRow)
                } else {
                    context.drawCenteredTextWithShadow(textRenderer, "?", slotX + iconSize / 2, slotY + 6, 0xAAAAAA)
                }
            } else if (!anyDialogOpen) {
                // ⚠ 同上：物品图标按行高居中，别跟精灵槽共用 slotY（那个是行顶 +2）
                rowData?.itemStack?.let { drawItemWithBar(context, it, slotX + 2, y + (rowHeight - 16) / 2) }
            }

            // 名称（属性色，照精灵市场）+ 条件徽章（★/☆/HT）；物品行名照物品栏悬浮按稀有度着色。
            // 名字起点 28：3D 图标被 scissor 裁到 rowL+26 为止，吃回原来白留的 14px；
            // 名字区 28 到头像 96，扣掉闪光徽章（★/☆ 约 11px）后精灵名可用 57px → 截断 54px = 6 汉字完整
            val nameColor = rowData?.nameColor ?: 0xFFFFFF
            val name: Text = if (entry.type == "ITEM") {
                val stack = rowData?.itemStack
                if (stack != null && !stack.isEmpty) {
                    com.shusheng.cobblemarket.util.TextUtil.truncateText(
                        com.shusheng.cobblemarket.util.TextUtil.rarityColoredName(stack), 56)
                } else {
                    Text.literal(rowData?.name ?: com.shusheng.cobblemarket.util.TextUtil.truncateString(entryNameRaw(entry), 56))
                }
            } else {
                Text.literal(rowData?.name ?: com.shusheng.cobblemarket.util.TextUtil.truncateString(entryNameRaw(entry), 54))
            }
            context.drawTextWithShadow(textRenderer, name, rowL + 26, y + 7, nameColor)
            var sx = rowL + 26 + textRenderer.getWidth(name)
            conditionBadges(entry).forEach { (badge, color) ->
                context.drawText(textRenderer, badge, sx + 2, y + 7, color, false)
                sx += 2 + textRenderer.getWidth(badge)
            }
            // 行内不显示备注（空间不足几字截断无意义），完整留言见悬停面板/交付弹窗

            // 买家头像 + 剩余时间（左移，给右侧数量+价格区间让位，避免与长价格重合）
            drawSellerAvatar(context, entry.buyerUuid, entry.buyerName, rowL + 96, y + 4, 16)
            // 求购单没有反狙击延长，不需要秒级精度
            val remaining = com.shusheng.cobblemarket.util.RemainingTime.text(
                entry.expiresAt, "cobblemarket.buy_order.ending", secondPrecision = false)
            val remainingColor = if (entry.expiresAt - System.currentTimeMillis() < 5 * 60 * 1000) 0xFF6666 else 0xAAAAAA
            context.drawTextWithShadow(textRenderer, remaining, rowL + 116, y + 7, remainingColor)
            if (entry.pending != null) {
                context.drawTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.buy_order.pending_mark").string,
                    rowL + 116 + textRenderer.getWidth(remaining) + 4, y + 7, GOLD_COLOR)
            }

            // 数量 + 价格区间（右对齐，与行按钮左缘留 4px 空隙，不压按钮）；
            // 待确认时隐藏（锁定期展示无意义，由「待确认」标记取代，避免与标记文字重合）
            if (entry.pending == null) {
                val countStr = countText(entry)
                // ⚠ 必须用 Text 拼、不能用字符串模板：RP 单位是字体字形，转 String 会把字体丢掉（图标变豆腐块）
                val rightText: MutableText = if (countStr.isEmpty()) priceRangeText(entry)
                    else Text.literal("$countStr  ").append(priceRangeText(entry))
                // 管理员模式无行按钮，价格区间贴右缘；普通模式给行按钮留位
                val btnLeft = if (adminMode) rowL + rowW() - 4 else rowL + rowW() - 50
                context.drawTextWithShadow(textRenderer, rightText, btnLeft - 4 - textRenderer.getWidth(rightText), y + 7, 0xFFAA00)
            }
        }

        if (hoveredRow >= 0) {
            val actualIdx = scrollOffset + hoveredRow
            if (actualIdx in displayList.indices) {
                renderTooltip(context, displayList[actualIdx], mouseX, mouseY)
            }
        }

        if (displayCount() > getMaxVisibleRows()) {
            context.drawCenteredTextWithShadow(textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + getMaxVisibleRows(), displayCount())} / ${displayCount()}",
                centerX, height - 49, 0x888888)
        }
    }

    // 320×32 纹理：整图（含左右边框）横向拉伸至面板宽、纵向 0.5 缩为 16 逻辑高。
    // 只画局部再拉伸会导致右边框丢失，按钮看起来超出背景
    private fun drawSlice(context: DrawContext, texture: Identifier, x: Int, y: Int) {
        // 贴图 640×32：x 方向按面板宽/640 缩放，采样整张（320 旧贴图时是 /320 采样 320 宽）
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(panelWidth / 640f, 0.5f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    private fun drawSliceStretched(context: DrawContext, texture: Identifier, x: Int, y: Int, h: Int) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(panelWidth / 640f, h / 32f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 前景（标题/余额/行内容/tooltip）全部在 renderBackground 绘制（早于弹窗遮罩），
        // 这里只渲染 children（弹窗遮罩/背景 Drawable 与各控件）
        super.render(context, mouseX, mouseY, delta)
        // 操作结果提示画在最后（弹窗遮罩之上），否则创建弹窗打开时被遮罩压暗看不见
        if (resultMsg != null) {
            if (System.currentTimeMillis() > resultUntil) {
                resultMsg = null
            } else {
                // height-52：面板底部边框带（height-48 起）上方 4px，避免提示文字与边框重合
                context.drawCenteredTextWithShadow(textRenderer, resultMsg!!, width / 2, height - 52, resultMsgColor)
            }
        }

        // 悬停预览大图：画在本界面内容全部之后才在面板之上。
        // **弹窗打开时一律不画**（照精灵市场）：大图画在 `super.render()` 之后，不拦会压住弹窗遮罩；
        // 而且这时注册表里只剩弹窗自己的图标，鼠标停在弹窗上照样能命中 `entryInRow`（2026-09-15 实测）——
        // 「弹窗打开 → 行内图标不渲染 → 自动查不到」这个旧假设不成立
        if (!anyDialogOpen()) {
            val previewRowL = rowLeftX()
            IconRegistry.entryInRow(mouseX.toDouble(), mouseY.toDouble(), previewRowL, previewRowL + rowW())
                ?.let { PokemonHoverPreview.render(context, it.renderable, it.state, previewRowL, height) }
            // 物品求购单行的大图：物品图标不进注册表，从行缓存取（精灵行的 itemStack 为 null）。
            // ⚠ 必须走 indexedDisplay 的原始索引（同上方行渲染），不能用 scrollOffset + hoveredRow ——
            // 后者是**过滤后**列表的行号，而 iconData 的 key 是 entries 的索引，有过滤时不是一回事
            // （2026-09-12 踩过：拍卖场同样写法导致精灵行弹出物品大图）
            if (hoveredRow >= 0) {
                indexedDisplay.getOrNull(scrollOffset + hoveredRow)?.let { (absIdx, _) ->
                    iconData[absIdx]?.itemStack?.let {
                        ItemHoverPreview.render(context, it, previewRowL, height)
                    }
                }
            }
        }
    }

    // 悬停信息面板：照精灵市场 tooltip 结构（自绘九宫格面板 + 固定行高，信息同市场列表）
    /**
     * 求购单的**静态信息行**：名字 / 条件 / 性格特性 / 形态 / IV 要求 / 单价区间 / 备注 / 买家。
     *
     * ⚠ **行悬停窗与「强制下架」弹窗共用这一份**（2026-09-20 用户要求两处信息一致）——
     * 以后加信息行只改这里，别在弹窗里另写一份。
     *
     * @param wrapW 备注的换行宽度：悬停窗按 [MAX_TOOLTIP_W]，弹窗窄、按自己的内容宽传
     * @return first = 行列表（`null` = 分割线行）；second = 「数量行」的插入位置（-1 = 该单不显示数量）
     */
    private fun requirementStaticLines(entry: BuyOrderEntry, wrapW: Int):
        Pair<MutableList<Pair<net.minecraft.text.OrderedText?, Int>>, Int> {
        val w = 0xFFFFFF
        val ivColors = intArrayOf(0x66FF66, 0xFF6666, 0xFFCC66, 0x6699FF, 0x66FF99, 0xFF99FF)

        val lines = mutableListOf<Pair<net.minecraft.text.OrderedText?, Int>>() // null = 分割线行
        lines.add(entryNameText(entry).asOrderedText() to w)
        // 属性行（1.2.0）：主属性主色 + 副属性副色，与市场/悬停窗同一口径
        if (entry.primaryType.isNotEmpty()) {
            lines.add(
                Text.literal(Text.translatable("cobblemarket.gui.tooltip_type").string)
                    .append(EntryBadgeRenderer.typeLine(entry.primaryType, entry.secondaryType))
                    .asOrderedText() to w
            )
        }
        if (entry.type == "POKEMON") {
            // 条件行：闪光用星星图标（★ 金 = 仅闪，☆ 灰 = 仅非闪，不限不显示）+ 特训
            // （filter_ht_any 自带「特训：」前缀，不能叠加 tooltip_ht 标签；此处统一用不带前缀的三态文案）
            val htLabel = when (entry.htFilter) {
                PokemonBlacklistEntry.HT_ONLY -> Text.translatable("cobblemarket.gui.filter_ht_on").string
                PokemonBlacklistEntry.HT_NONE -> Text.translatable("cobblemarket.gui.filter_ht_off").string
                else -> Text.translatable("cobblemarket.buy_order.any").string
            }
            val condLine = Text.literal("")
            // ⚠ 那两格间隔**只在真有 ★/☆ 时才拼**：无条件拼会让「不限闪光」的行凭空缩进两个空格，
            // 整张悬停窗都是左对齐，那一行就显得既没居中也没对齐（2026-09-20 用户指出）
            val hasShinyMark = entry.shinyFilter == PokemonBlacklistEntry.SHINY_YES ||
                entry.shinyFilter == PokemonBlacklistEntry.SHINY_NO
            when (entry.shinyFilter) {
                PokemonBlacklistEntry.SHINY_YES -> condLine.append(Text.literal("★").formatted(Formatting.GOLD))
                PokemonBlacklistEntry.SHINY_NO -> condLine.append(Text.literal("☆").formatted(Formatting.GRAY))
            }
            if (hasShinyMark) condLine.append(Text.literal("  "))
            condLine.append(Text.literal("${Text.translatable("cobblemarket.buy_order.tooltip_ht").string} $htLabel"))
            lines.add(condLine.asOrderedText() to w)
            // 性格 + 特性（照精灵市场同排格式，通用 key 自带冒号）
            val natureTxt = entry.natureKey?.let { Text.translatable(it).string } ?: Text.translatable("cobblemarket.buy_order.any").string
            val abilityTxt = entry.abilityKey?.let { Text.translatable(it).string } ?: Text.translatable("cobblemarket.buy_order.any").string
            lines.add(Text.literal(
                "${Text.translatable("cobblemarket.gui.tooltip_nature").string}$natureTxt  " +
                "${Text.translatable("cobblemarket.gui.tooltip_ability").string}$abilityTxt"
            ).asOrderedText() to w)
            // 形态（有限定时）
            if (entry.aspects.isNotEmpty() && PokemonBlacklistEntry.ALL_FORMS !in entry.aspects) {
                val formLabel = if (entry.aspects.isEmpty()) Text.translatable("cobblemarket.blacklist.form_default").string
                    else entry.aspects.joinToString("/") { aspectLabel(it) }
                lines.add(Text.literal("${Text.translatable("cobblemarket.buy_order.tooltip_form").string} $formLabel").asOrderedText() to w)
            }
            // IV 要求（照精灵市场六行彩色；-1 = 不限显示 —）
            val hp = StatNames.HP
            val atk = StatNames.ATK
            val def = StatNames.DEF
            val spa = StatNames.SP_ATK
            val spd = StatNames.SP_DEF
            val spe = StatNames.SPEED
            lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs").asOrderedText() to w)
            fun ivReq(v: Int) = if (v < 0) "—" else v.toString()
            lines.add(Text.literal("  $hp: ${ivReq(entry.ivHp)}").asOrderedText() to ivColors[0])
            lines.add(Text.literal("  $atk: ${ivReq(entry.ivAtk)}").asOrderedText() to ivColors[1])
            lines.add(Text.literal("  $def: ${ivReq(entry.ivDef)}").asOrderedText() to ivColors[2])
            lines.add(Text.literal("  $spa: ${ivReq(entry.ivSpAtk)}").asOrderedText() to ivColors[3])
            lines.add(Text.literal("  $spd: ${ivReq(entry.ivSpDef)}").asOrderedText() to ivColors[4])
            lines.add(Text.literal("  $spe: ${ivReq(entry.ivSpd)}").asOrderedText() to ivColors[5])
        }
        // 数量行是动态行（交付期间剩余量会变），只记录插入点（价格行之前，与原顺序一致）
        var quantityLine = -1
        if (entry.type == "ITEM") quantityLine = lines.size
        // 价格区间：标签默认色，金额段蓝色（2026-08-24 拍板）
        lines.add(Text.translatable("cobblemarket.buy_order.tooltip_price").append(
            // ⚠ 单位按**订单自己的币种**（1.3.0 口径）：悬停窗与强制下架弹窗共用这一行，标错单位就是误导
            amountWithUnit(
                "${com.shusheng.cobblemarket.client.formatPrice(entry.minPrice)}~${com.shusheng.cobblemarket.client.formatPrice(entry.maxPrice)}",
                entry.isPoints, "", inline = true
            ).formatted(Formatting.GOLD)
        ).asOrderedText() to w)
        // 买家留言区块：分割线夹多行内容（照拍卖规则面板样式），无备注不显示
        if (entry.note.isNotEmpty()) {
            lines.add(null to w)
            val noteLabel = Text.literal(Text.translatable("cobblemarket.buy_order.tooltip_note").string)
                .append(Text.literal(entry.note))
            textRenderer.wrapLines(noteLabel, wrapW).forEach { lines.add(it to 0xFFDD99) }
            lines.add(null to w)
        }
        // 买家：纯文字行默认色
        lines.add(Text.translatable("cobblemarket.buy_order.tooltip_buyer").append(Text.literal(entry.buyerName)).asOrderedText() to w)
        return lines to quantityLine
    }

    private fun renderTooltip(context: DrawContext, entry: BuyOrderEntry, mouseX: Int, mouseY: Int) {
        // 静态行缓存：内容只取决于条目（见 tooltipCacheKey 字段注释），悬停目标变化时才重建
        if (tooltipCacheKey != entry.id) {
            tooltipCacheKey = entry.id
            // 信息行的构造抽到 requirementStaticLines（**强制下架弹窗共用同一份**，见该函数注释）
            val built = requirementStaticLines(entry, MAX_TOOLTIP_W)
            tooltipCacheLines = built.first
            tooltipCacheQuantityLine = built.second
            tooltipCacheMaxWidth = built.first.fold(0) { acc, (line, _) ->
                if (line == null) acc else maxOf(acc, textRenderer.getWidth(line))
            }
            // 构造在 requirementStaticLines（含 1.3.0 的 RP 币种口径），见该函数

        }
        // 动态行（数量 + 到期倒计时）每帧构建
        val staticLines = tooltipCacheLines
        val quantityLine = tooltipCacheQuantityLine
        val lines = ArrayList<Pair<net.minecraft.text.OrderedText?, Int>>(staticLines.size + 2)
        if (quantityLine >= 0) {
            lines.addAll(staticLines.subList(0, quantityLine))
            lines.add(quantityLineOf(entry))
            lines.addAll(staticLines.subList(quantityLine, staticLines.size))
        } else {
            lines.addAll(staticLines)
        }
        lines.add(expiresLineOf(entry))

        var maxWidth = tooltipCacheMaxWidth
        if (quantityLine >= 0) {
            lines[quantityLine].first?.let { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it)) }
        }
        lines.last().first?.let { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it)) }

        val padding = 4
        val tooltipHeight = lines.size * 10 + padding
        // 悬停窗**不许把本行的按钮盖掉一半以上**（交付 / 处理交付 / ✕ 下架）。
        // 矩形按行号算几何（与 rebuildRowButtons 同源）；**管理端模式没有行按钮** → 不避让
        val (tx, ty) = TooltipPlacement.place(
            mouseX = mouseX, mouseY = mouseY,
            textW = maxWidth, textH = tooltipHeight, pad = padding,
            screenW = width, screenH = height,
            avoid = if (adminMode) null else rowButtonRect(hoveredRow)
        )

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, lines.size * 10 + 2 * padding, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, (line, color) ->
            if (line == null) {
                // 分割线（照拍卖规则面板：1px 灰线，行内居中）
                context.fill(tx, ty + i * 10 + 4, tx + maxWidth, ty + i * 10 + 5, 0xFF555555.toInt())
            } else {
                context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
            }
        }
        context.matrices.pop()
    }

    // aspect 显示名：走本模组翻译表，未收录的 aspect 显示原文
    private fun aspectLabel(aspect: String): String {
        val t = Text.translatable("cobblemarket.aspect.$aspect")
        return if (t.string == "cobblemarket.aspect.$aspect") aspect else t.string
    }

    // 买家头像（照精灵市场）
    private val defaultSkinTexture = Identifier.of("minecraft", "textures/entity/player/wide/steve.png")

    // 皮肤纹理缓存：每行每帧创建 GameProfile 并查 skinProvider 是分配热点，首个结果按 UUID 缓存
    private val skinCache = mutableMapOf<UUID, Identifier>()

    private fun getSellerSkin(uuid: UUID, name: String): Identifier {
        skinCache[uuid]?.let { return it }
        val skin = client?.networkHandler?.getPlayerListEntry(uuid)?.skinTextures?.texture()
            ?: client?.skinProvider?.getSkinTextures(com.mojang.authlib.GameProfile(uuid, name))?.texture()
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

    // ── 创建对话框 ──

    private fun openCreateDialog() {
        // 重置对话框字段（每次打开都是新表单）
        createTab = 0
        // 币种跟着「当前在看哪种币」——看 RP 单时点发布，默认就发 RP 单（单位对不上会差好几个数量级）
        createCurrency = currency
        // 表单滚动位置也归零（上次的滚动量留着的话，一打开就停在中间）
        createScroll = 0
        createScrollHidden.clear()
        createShinyFilter = PokemonBlacklistEntry.SHINY_ANY
        createHtFilter = PokemonBlacklistEntry.HT_ANY
        previewSpecies = null
        previewRenderable = null
        formOptions = listOf()
        formIndex = 0
        formListOpen = false
        formListScroll = 0
        abilityOptions = listOf()
        abilityIndex = -1
        natureIndex = -1
        abilityListOpen = false
        natureListOpen = false
        natureListScroll = 0
        matchedItems = listOf()
        selectedItemIndex = -1
        itemListOpen = false
        itemListScroll = 0

        // 隐藏主界面控件
        backButton?.visible = false
        createButton?.visible = false
        searchField?.visible = false
        currencyButton?.visible = false
        tabButtons.forEach { it.visible = false }
        rowButtons.forEach { it.visible = false }

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderCreateDialogBackground(context, delta)
            }
        })

        // 每次打开创建对话框重置手持模式（不跨次残留选中态）
        heldItemMode = false
        rebuildCreateDialogWidgets()
    }

    private fun rebuildCreateDialogWidgets() {
        // 移除旧的对话框控件（tab 切换/重建时调用）
        createSpeciesField?.let { remove(it) }
        createMinPriceField?.let { remove(it) }
        createMaxPriceField?.let { remove(it) }
        createNoteField?.let { remove(it) }
        createItemField?.let { remove(it) }
        createCountField?.let { remove(it) }
        createIvFields.forEach { it?.let { f -> remove(f) } }
        createShinyButton?.let { remove(it) }
        createHtButton?.let { remove(it) }
        createFormButton?.let { remove(it) }
        createAbilityButton?.let { remove(it) }
        createNatureButton?.let { remove(it) }
        createTabButtons.forEach { remove(it) }
        createTabButtons.clear()
        createConfirmButton?.let { remove(it) }
        createCancelButton?.let { remove(it) }
        createCurrencyButton?.let { remove(it) }
        // 候选箭头也在重建之列：重建后可见性要按当前候选数（和页签）恢复
        speciesPrevButton?.let { remove(it) }
        speciesNextButton?.let { remove(it) }
        speciesPrevButton = null
        speciesNextButton = null
        itemSelectButton?.let { remove(it) }
        heldItemButton?.let { remove(it) }
        heldItemButton = null
        formListButtons.forEach { remove(it) }
        formListButtons.clear()
        abilityListButtons.forEach { remove(it) }
        abilityListButtons.clear()
        natureListButtons.forEach { remove(it) }
        natureListButtons.clear()
        itemOptionButtons.forEach { remove(it) }
        itemOptionButtons.clear()

        val centerX = width / 2
        val dialogY = createDialogY()

        // tab 按钮：精灵 | 物品
        val tabW = 60
        listOf(0, 1).forEachIndexed { i, tab ->
            val btn = NineSliceButton(
                centerX - tabW - 2 + i * (tabW + 4), dialogY + 28, tabW, 14,
                Text.literal(""),
                { if (createTab != tab) { createTab = tab; formListOpen = false; abilityListOpen = false; natureListOpen = false; itemListOpen = false; rebuildCreateDialogWidgets() } },
                texture = BUY_ORDER_BUTTON_TEXTURE,
                texH = BUY_ORDER_BUTTON_TEX_H
            )
            val label = Text.translatable(if (tab == 0) "cobblemarket.buy_order.create_tab_pokemon" else "cobblemarket.buy_order.create_tab_item").string
            btn.setMessage(if (createTab == tab) com.shusheng.cobblemarket.util.TextUtil.selectedText(label) else Text.literal(label))
            createTabButtons.add(btn)
            addDrawableChild(btn)
        }

        if (createTab == 0) buildPokemonCreateWidgets(dialogY) else buildItemCreateWidgets(dialogY)

        // 货币切换按钮（价格输入框右侧 4px）：与市场/上架/拍卖创建界面同一个按钮、同一套三层拦截。
        // ⚠ 两个 tab 的价格框位置不同（精灵 tab 的价格行比物品 tab 靠右），按钮跟着各自的价格框右缘走
        createCurrencyButton = if (PointsStateCache.enabled) {
            val bx = if (createTab == 0) centerX + 81 else centerX + 70
            val by = dialogY + (if (createTab == 0) 174 else 106)
            NineSliceButton(
                bx, by, 20, 16, Text.literal(""), { onCreateCurrencyClick() },
                iconTexW = POINTS_SYMBOL_SRC, iconTexH = POINTS_SYMBOL_SRC,
                iconScale = CURRENCY_BUTTON_ICON_SIZE / POINTS_SYMBOL_SRC.toFloat(),
                tooltip = Text.translatable("cobblemarket.gui.currency_switch", inlineCurrencyUnit(), CurrencyService.pointsIcon())
            ).also {
                // dimmed = 置灰但**保留可点击性**，点了给失败音 + 提示，比一个"死了"的按钮好懂
                it.dimmed = !PointsStateCache.marketEnabled
                updateCurrencyButton(it, createCurrency == MarketCurrency.POINTS)
                addDrawableChild(it)
            }
        } else null

        createConfirmButton = NineSliceButton(
            centerX - 85, dialogY + createDialogH() - createButtonInset(), 80, 20,
            Text.translatable("cobblemarket.buy_order.create_confirm"),
            { confirmCreate() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(createConfirmButton)
        createCancelButton = NineSliceButton(
            centerX + 5, dialogY + createDialogH() - createButtonInset(), 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeDialogs() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(createCancelButton)
        // 建完统一按当前滚动位置摆一遍（弹窗装得下时与上面写的坐标完全一致）
        layoutCreateForm()
    }

    /** 创建弹窗表单的**滚动量**（px）：弹窗装得下时恒为 0 —— 布局与改动前完全一致 */
    private var createScroll = 0

    /** 被**滚动**藏起来的控件（与「状态隐藏」区分开：滚回来要恢复；见 [layoutCreateForm]） */
    private val createScrollHidden = mutableSetOf<ClickableWidget>()

    /** 创建弹窗的设计高度（精灵 276 / 物品 196 —— 表单本来就按这两套坐标写的） */
    // +10（276→286 / 196→206）+ createButtonInset 同步 +10：给「冻结提示」拆成两行后腾一行
    private fun createDesignH(): Int = if (createTab == 0) 286 else 206

    /**
     * 创建弹窗高度：设计值，**但不超过屏幕**。
     *
     * ⚠ 2026-09-19 用户报（1.2.0 起就有）：GUI scale 设成自动时逻辑高只有两百多，
     *   写死的 276 会顶出屏幕、上下都被吞。照「服务器配置界面」那套：**钳到屏幕 + 内容可滚**。
     *   ⚠ 表单行距 18、输入框高 16 —— 已经压无可压，所以只能靠滚动，不能靠缩。
     */
    private fun createDialogH(): Int = minOf(createDesignH(), height - 8)

    private fun createDialogY(): Int = height / 2 - createDialogH() / 2

    /** 确认/取消按钮相对**弹窗底**的内缩（设计值 276−230 / 196−160）—— 按钮钉在弹窗底部，永远够得到 */
    private fun createButtonInset(): Int = createDesignH() - (if (createTab == 0) 240 else 170)

    /** 表单可视区 = 标题/tab 之下 [CREATE_FORM_TOP] 起，到按钮上方 6px 止 */
    private fun createViewTop(dialogY: Int): Int = dialogY + CREATE_FORM_TOP

    private fun createViewBottom(dialogY: Int): Int = dialogY + createDialogH() - createButtonInset() - 6

    /** 表单内容总高（设计坐标，从表单顶算）：精灵算到「冻结提示」（214+8），物品算到 146+8 ——
     *  ⚠ 提示也在内容里：不算进来的话，弹窗被压矮时它永远滚不进可视区（等于消失） */
    private fun createContentH(): Int = if (createTab == 0) 178 else 110

    private fun createMaxScroll(): Int =
        maxOf(0, createContentH() - (createViewBottom(createDialogY()) - createViewTop(createDialogY())))

    /**
     * 创建弹窗**表单**里的纵向坐标：设计偏移 → 屏幕 y（含滚动量）。
     * ⚠ 表单控件一律走它，别在别处再写一遍 `dialogY + N` —— 滚动后就对不上了。
     *   （弹窗框、标题不滚，用 `createDialogY() + N`；确认/取消钉在弹窗底，用 [createButtonInset]）
     */
    private fun createY(designOffset: Int): Int = createDialogY() + designOffset - createScroll

    /**
     * 表单里**画出来的东西**（精灵预览图 / 候选计数 / 冻结提示）现在该不该画。
     *
     * ⚠ 它们不是控件，[layoutCreateForm] 的可见性管不到 —— 漏了就会出现「输入框滚走了、
     *   右边的精灵占位图和 1/3 还留在原地」，或者「冻结提示画到弹窗外面去」
     *   （2026-09-19 用户实测）。判据与控件**完全一致**（用同一行的设计坐标），这样同进同出。
     */
    private fun createFormVisible(designOffset: Int, h: Int = 16): Boolean {
        val dialogY = createDialogY()
        val y = dialogY + designOffset - createScroll
        return y >= createViewTop(dialogY) && y + h <= createViewBottom(dialogY)
    }

    /**
     * 按当前滚动量重排创建弹窗**表单控件**（只改 y，x 不动）。
     *
     * ⚠ 这里写的偏移就是两个 builder 里的**设计坐标**（48 / 66 / 84 / …）——**加控件时两处都要加**，
     *   否则新控件不会跟着滚（也就意味着弹窗被压矮时它会留在原地/跑出弹窗）。
     * ⚠ 可见性只处理「滚出可视区」这一种，而且**只恢复自己藏起来的那批**（[createScrollHidden]）：
     *   这些控件另有状态可见性（形态列表展开时隐藏 HT 与个体值等），一律覆盖会把它们弄乱。
     */
    private fun layoutCreateForm() {
        createScroll = createScroll.coerceIn(0, createMaxScroll())
        val dialogY = createDialogY()
        val viewTop = createViewTop(dialogY)
        val viewBottom = createViewBottom(dialogY)

        fun place(w: ClickableWidget?, designOffset: Int) {
            if (w == null) return
            val y = dialogY + designOffset - createScroll
            w.y = y
            val fits = y >= viewTop && y + w.height <= viewBottom
            if (fits) {
                if (createScrollHidden.remove(w)) w.visible = true
            } else {
                if (w.visible) createScrollHidden += w
                w.visible = false
            }
        }

        if (createTab == 0) {
            place(createShinyButton, 48)
            place(createSpeciesField, 48)
            place(speciesPrevButton, 48)
            place(speciesNextButton, 48)
            place(createFormButton, 66)
            place(createHtButton, 84)
            createIvFields.forEachIndexed { i, f -> place(f, 102 + (i / 3) * 18) }
            place(createAbilityButton, 138)
            place(createNatureButton, 156)
            place(createMinPriceField, 174)
            place(createMaxPriceField, 174)
            place(createNoteField, 194)
        } else {
            place(heldItemButton, 48)
            place(createItemField, 48)
            place(itemSelectButton, 66)
            place(createCountField, 84)
            place(createMinPriceField, 106)
            place(createMaxPriceField, 106)
            place(createNoteField, 126)
        }
    }

    private fun buildPokemonCreateWidgets(dialogY: Int) {
        val centerX = width / 2

        // 闪光三态按钮（物种输入框左侧，照黑名单添加框）
        createShinyButton = NineSliceButton(
            centerX - 104, createY(48), 20, 16,
            Text.literal(""),
            { cycleCreateShiny() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(createShinyButton)
        updateCreateShinyButton()

        // 位置照黑名单添加弹窗（centerX-80 宽 140，与精灵槽 centerX+66 间距 6px）
        createSpeciesField = TextFieldWidget(textRenderer, centerX - 80, createY(48), 140, 16, Text.literal(""))
        createSpeciesField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_species").formatted(Formatting.GRAY))
        createSpeciesField?.setChangedListener { updatePokemonPreview(it) }
        addDrawableChild(createSpeciesField)

        // 候选切换箭头（预览槽位右侧，与槽位垂直居中）：候选 ≥2 才显示，切换预览图与形态/特性选项。
        // 这个对话框宽 280（黑名单/限价是 220），右侧腾得出 44px 放两枚箭头
        speciesPrevButton = NineSliceButton(
            centerX + 96, createY(48), 18, 16, Text.literal(""),
            { cycleSpecies(-1) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/previous.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.species_prev")
        ).also { it.visible = false; addDrawableChild(it) }
        speciesNextButton = NineSliceButton(
            centerX + 118, createY(48), 18, 16, Text.literal(""),
            { cycleSpecies(1) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/next.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.species_next")
        ).also { it.visible = false; addDrawableChild(it) }

        // 形态选择按钮：只有解析出多形态物种时显示，点击展开/收起形态列表
        createFormButton = NineSliceButton(
            centerX - 82, createY(66), 148, 14,
            Text.literal(""),
            { toggleFormList() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        createFormButton?.visible = false
        addDrawableChild(createFormButton)

        // 特训三态按钮
        createHtButton = NineSliceButton(
            centerX - 82, createY(84), 148, 14,
            Text.literal(""),
            { cycleCreateHt() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(createHtButton)
        updateCreateHtButton()

        // IV 六项
        val ivPlaceholders = listOf("HP", "ATK", "DEF", "SpA", "SpD", "Spd")
        for (i in 0..5) {
            val col = i % 3
            val row = i / 3
            val field = TextFieldWidget(
                textRenderer, centerX - 75 + col * 52, createY(102) + row * 18, 46, 16, Text.literal("")
            )
            field.setPlaceholder(Text.literal(ivPlaceholders[i]))
            field.setTextPredicate { it.length <= 2 && it.all { c -> c.isDigit() } }
            createIvFields[i] = field
            addDrawableChild(field)
        }

        // 特性按钮：物种解析后列出其可用特性点选（留空/未解析 = 不限）
        createAbilityButton = NineSliceButton(
            centerX - 82, createY(138), 148, 14,
            Text.literal(""),
            { toggleAbilityList() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(createAbilityButton)
        updateAbilityButton()

        // 性格按钮：25 种性格固定列表点选
        createNatureButton = NineSliceButton(
            centerX - 82, createY(156), 148, 14,
            Text.literal(""),
            { toggleNatureList() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(createNatureButton)
        updateNatureButton()

        // 单价区间（并排，placeholder 短不溢出）
        createMinPriceField = TextFieldWidget(textRenderer, centerX - 75, createY(174), 74, 16, Text.literal(""))
        createMinPriceField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_min_price").formatted(Formatting.GRAY))
        createMinPriceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addDrawableChild(createMinPriceField)
        createMaxPriceField = TextFieldWidget(textRenderer, centerX + 3, createY(174), 74, 16, Text.literal(""))
        createMaxPriceField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_max_price").formatted(Formatting.GRAY))
        createMaxPriceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addDrawableChild(createMaxPriceField)

        // 备注（额外需求提醒卖家，选填）
        createNoteField = TextFieldWidget(textRenderer, centerX - 74, createY(194), 148, 16, Text.literal(""))
        createNoteField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_note").formatted(Formatting.GRAY))
        createNoteField?.setMaxLength(100)
        addDrawableChild(createNoteField)

        // 控件重建后恢复候选箭头的可见性（换页签/窗口缩放都会走到这儿）
        updateSpeciesArrows()
    }

    private fun buildItemCreateWidgets(dialogY: Int) {
        val centerX = width / 2

        // 使用手持物品（组件粒度）：搜索框左侧小手图标按钮，按下态 = 手持模式；
        // 服务端读主手物品提取组件快照作为求购要求
        heldItemButton = NineSliceButton(
            centerX - 106, createY(48), 16, 16,
            Text.literal(""),
            { toggleHeldItemMode() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/hand.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            // 三段贴图：第三段是**选中态**（进入手持物品模式后保持高亮）
            texH = 120,
            // 悬停提示走**原生 tooltip**（原先是自己在 render 里手绘一个，样式与别处不一致）
            tooltip = Text.translatable("cobblemarket.buy_order.use_held_item")
        )
        addDrawableChild(heldItemButton)
        updateHeldItemButton()

        createItemField = TextFieldWidget(textRenderer, centerX - 82, createY(48), 148, 16, Text.literal(""))
        createItemField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_item").formatted(Formatting.GRAY))
        createItemField?.setChangedListener { updateItemPreview(it) }
        addDrawableChild(createItemField)

        // 物品选择按钮：模糊匹配列表点选（照黑名单物品对话框）
        itemSelectButton = NineSliceButton(
            centerX - 82, createY(66), 148, 14,
            Text.literal(""),
            { toggleItemList() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        itemSelectButton?.visible = false
        addDrawableChild(itemSelectButton)

        createCountField = TextFieldWidget(textRenderer, centerX - 82, createY(84), 148, 16, Text.literal(""))
        createCountField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_count").formatted(Formatting.GRAY))
        createCountField?.setTextPredicate { it.length <= 4 && it.all { c -> c.isDigit() } }
        addDrawableChild(createCountField)

        // min/max/备注与上方搜索框左右缘完全对齐（搜索框 centerX-82 宽 148）
        createMinPriceField = TextFieldWidget(textRenderer, centerX - 82, createY(106), 72, 16, Text.literal(""))
        createMinPriceField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_min_price").formatted(Formatting.GRAY))
        createMinPriceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addDrawableChild(createMinPriceField)
        createMaxPriceField = TextFieldWidget(textRenderer, centerX - 6, createY(106), 72, 16, Text.literal(""))
        createMaxPriceField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_max_price").formatted(Formatting.GRAY))
        createMaxPriceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addDrawableChild(createMaxPriceField)

        // 备注（额外需求提醒卖家，选填）
        createNoteField = TextFieldWidget(textRenderer, centerX - 82, createY(126), 148, 16, Text.literal(""))
        createNoteField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_note").formatted(Formatting.GRAY))
        createNoteField?.setMaxLength(100)
        addDrawableChild(createNoteField)
    }

    private fun updateHeldItemButton() {
        // 选中态（selectedVisual）：手持物品模式期间按钮保持第三段高亮，见 NineSliceButton
        heldItemButton?.selectedVisual = heldItemMode
    }

    /** 主手物品（空手返回 null）：手持模式图标的统一数据源，实时读取保证换手后同步 */
    private fun heldPreviewStack(): ItemStack? =
        client?.player?.mainHandStack?.takeIf { !it.isEmpty }

    /** 退出手持模式：开始搜索/点选物品时调用。两条添加路径互斥，避免提交的物品与界面显示不符 */
    private fun exitHeldItemMode() {
        if (!heldItemMode) return
        heldItemMode = false
        updateHeldItemButton()
    }

    private fun toggleHeldItemMode() {
        // 客户端预检主手：空手播 fail 音效 + 红字提示，不切换模式
        val held = heldPreviewStack()
        if (held == null) {
            playFailSound()
            resultMsg = Text.translatable("cobblemarket.buy_order.held_item_empty").string
            resultMsgColor = 0xFF5555
            resultUntil = System.currentTimeMillis() + 3000
            return
        }
        heldItemMode = !heldItemMode
        updateHeldItemButton()
        // 明确反馈当前模式与物品，玩家才明白红色按钮含义
        resultMsg = if (heldItemMode)
            Text.translatable("cobblemarket.buy_order.held_item_selected", held.name).string
        else
            Text.translatable("cobblemarket.buy_order.held_item_cancelled").string
        resultMsgColor = 0x55FF55
        resultUntil = System.currentTimeMillis() + 3000
    }

    /** 简版悬停提示（纯文字行）：发布对话框小手图标按钮用（行 tooltip 见 renderRowTooltip） */
    private fun renderCreateDialogBackground(context: DrawContext, delta: Float) {
        val centerX = width / 2
        val dialogW = minOf(340, width - 40).coerceAtLeast(200)
        val dialogH = createDialogH()   // 设计高度按屏幕钳制（见 createDialogH）
        val dialogX = centerX - dialogW / 2
        val dialogY = createDialogY()

        drawScreenDimMask(context, width, height)
        // 不透明衬底：弹窗背景贴图中间区域半透明，下层行内容（精灵图标/数量/价格）会透过
        context.fill(dialogX, dialogY, dialogX + dialogW, dialogY + dialogH, 0xFF2A2A2A.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.buy_order.create_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        // ⚠ 下面这些**绘制**都要过 createFormVisible：它们不是控件，滚动裁决管不到，
        //   漏一处就会出现「输入框滚走了、精灵图/1/3/冻结提示还留在原地或画到弹窗外面」。
        if (createTab == 0) {
            // 精灵预览槽（右侧）：**与物种输入框同进同出**（同一行的设计坐标；滚走了整块不画）
            if (createFormVisible(48)) {
                val slotSize = 28
                val slotX = centerX + 66
                val slotY = createY(42)
                context.matrices.push()
                context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
                context.matrices.scale(slotSize / 66f, slotSize / 66f, 1f)
                context.drawTexture(POKEMON_SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
                context.matrices.pop()
                previewRenderable?.let { rp ->
                    // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
                    val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                        com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
                    try {
                        PokemonIconRenderer.draw(
                            context = context,
                            renderable = rp,
                            state = previewState,
                            x = slotX, y = slotY, size = slotSize,
                            delta = delta,
                            animate = useFloat
                        )
                    } catch (_: Exception) {
                    }
                }
            }
            // 候选计数（1/3）：让玩家知道有几个可切、现在是第几个（**与形态行同进同出**）
            if (speciesCandidates.size > 1 && createFormVisible(66, 14)) {
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.literal("${speciesIndex + 1}/${speciesCandidates.size}").formatted(Formatting.GRAY),
                    centerX + 116, createY(68), 0xFFFFFF
                )
            }
            // 冻结提示：发布时冻结 maxPrice × 1（性格列表展开时隐藏——提示在列表覆盖区内）
            // 文字默认色，金额+单位金色（2026-08-24 拍板）
            if (!natureListOpen && createFormVisible(214, 8)) {
                context.drawCenteredTextWithShadow(textRenderer,
                    // 冻结金额 + 单位合成一个 amountText（单位取创建对话框里选的 createCurrency，
                    // 词条也随之从 `%d %s` 合并成单个 `%s`，与全项目口径一致）
                    Text.translatable("cobblemarket.buy_order.frozen_hint",
                        amountWithUnit(
                            com.shusheng.cobblemarket.client.formatPriceLong((createMaxPriceField?.text?.toIntOrNull() ?: 0).toLong()),
                            createCurrency == MarketCurrency.POINTS, "", inline = true
                        ).formatted(Formatting.GOLD)),
                    centerX, createY(214), 0xFFFFFF)
                context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.buy_order.frozen_hint_note"),
                    centerX, createY(214) + 10, 0xFFFFFF)
            }
        } else {
            // 物品预览图标（右侧）：手持模式显示主手物品（文字+图标双重提醒），否则优先显示点选项
            // **与物品输入框同进同出**（同一行的设计坐标；滚走了整块不画）
            if (createFormVisible(48)) {
                val heldPreview = if (heldItemMode) heldPreviewStack() else null
                if (heldPreview != null) {
                    drawItemWithBar(context, heldPreview, centerX + 72, createY(48))
                } else {
                    val preview = matchedItems.getOrNull(selectedItemIndex) ?: matchedItems.firstOrNull()
                    preview?.let { candidate ->
                        com.shusheng.cobblemarket.client.ItemComponentsDisplay
                            .iconStack(candidate.itemId, candidate.componentsSpec)
                            ?.let { stack -> if (!stack.isEmpty) drawItemWithBar(context, stack, centerX + 72, createY(48)) }
                    }
                }
            }
            // 冻结提示：发布时冻结 maxPrice × 件数（物品匹配列表展开时隐藏——提示在列表覆盖区内）
            // 文字默认色，金额+单位金色（2026-08-24 拍板）
            if (!itemListOpen && createFormVisible(146, 8)) {
                val maxPrice = createMaxPriceField?.text?.toIntOrNull() ?: 0
                val count = createCountField?.text?.toIntOrNull() ?: 0
                context.drawCenteredTextWithShadow(textRenderer,
                    // 冻结金额 + 单位合成一个 amountText（单位取 createCurrency，同精灵 tab）
                    Text.translatable("cobblemarket.buy_order.frozen_hint",
                        amountWithUnit(
                            com.shusheng.cobblemarket.client.formatPriceLong(maxPrice.toLong() * count),
                            createCurrency == MarketCurrency.POINTS, "", inline = true
                        ).formatted(Formatting.GOLD)),
                    centerX, createY(146), 0xFFFFFF)
                context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.buy_order.frozen_hint_note"),
                    centerX, createY(146) + 10, 0xFFFFFF)
            }
        }
    }

    private fun confirmCreate() {
        val minPrice = createMinPriceField?.text?.toIntOrNull() ?: 0
        val maxPrice = createMaxPriceField?.text?.toIntOrNull() ?: 0
        if (minPrice < 1 || maxPrice < minPrice) {
            resultMsg = Text.translatable("cobblemarket.buy_order.invalid_price").string
            resultUntil = System.currentTimeMillis() + 3000
            playFailSound()
            return
        }
        if (createTab == 0) {
            // 物种：优先发送客户端本地解析出的物种 ID（中文输入在客户端解析，服务端只有英文环境）
            val input = createSpeciesField?.text?.trim().orEmpty()
            val speciesInput = previewSpecies?.resourceIdentifier?.toString() ?: input
            sendToServer(CreatePokemonBuyOrderPayload(
                speciesId = speciesInput,
                shinyFilter = createShinyFilter,
                htFilter = createHtFilter,
                ivHp = parseIv(createIvFields[0]?.text),
                ivAtk = parseIv(createIvFields[1]?.text),
                ivDef = parseIv(createIvFields[2]?.text),
                ivSpAtk = parseIv(createIvFields[3]?.text),
                ivSpDef = parseIv(createIvFields[4]?.text),
                ivSpd = parseIv(createIvFields[5]?.text),
                // 未解析出形态选项（含未填物种）= 不限形态；有选项时按所选
                aspects = formOptions.getOrNull(formIndex)?.aspects?.toList() ?: listOf(PokemonBlacklistEntry.ALL_FORMS),
                // 特性/性格来自点选按钮（-1 = 不限）；key 与服务端匹配语义一致
                abilityKey = abilityOptions.getOrNull(abilityIndex)?.first ?: "",
                natureKey = natureOptions.getOrNull(natureIndex)?.first ?: "",
                minPrice = minPrice,
                maxPrice = maxPrice,
                note = createNoteField?.text?.trim().orEmpty(),
                currency = createCurrency.name
            ))
        } else {
            val count = createCountField?.text?.toIntOrNull() ?: 0
            if (count < 1) {
                resultMsg = Text.translatable("cobblemarket.buy_order.invalid_price").string
                resultUntil = System.currentTimeMillis() + 3000
                return
            }
            if (heldItemMode) {
                // 手持模式：itemId 由服务端读主手决定（含组件快照），客户端只传模式标志
                sendToServer(CreateItemBuyOrderPayload("", count, minPrice, maxPrice, createNoteField?.text?.trim().orEmpty(), true, currency = createCurrency.name))
            } else {
                // 优先发送点选项；未点选时取自动匹配首个；无匹配则报错
                val candidate = matchedItems.getOrNull(selectedItemIndex)
                    ?: matchedItems.firstOrNull()
                    ?: resolveMatchingItems(createItemField?.text.orEmpty()).firstOrNull()
                if (candidate == null) {
                    resultMsg = Text.translatable("cobblemarket.buy_order.item_not_found").string
                    resultUntil = System.currentTimeMillis() + 3000
                    return
                }
                sendToServer(CreateItemBuyOrderPayload(
                    itemId = candidate.itemId,
                    totalCount = count,
                    minPrice = minPrice,
                    maxPrice = maxPrice,
                    note = createNoteField?.text?.trim().orEmpty(),
                    useHeldItem = false,
                    componentsSpec = candidate.componentsSpec,
                    currency = createCurrency.name,
                ))
            }
        }
        closeDialogs()
    }

    private fun parseIv(text: String?): Int {
        val t = text?.trim()
        if (t.isNullOrEmpty()) return -1
        return t.toIntOrNull()?.coerceIn(0, 31) ?: -1
    }

    private fun cycleCreateShiny() {
        createShinyFilter = when (createShinyFilter) {
            PokemonBlacklistEntry.SHINY_ANY -> PokemonBlacklistEntry.SHINY_YES
            PokemonBlacklistEntry.SHINY_YES -> PokemonBlacklistEntry.SHINY_NO
            else -> PokemonBlacklistEntry.SHINY_ANY
        }
        updateCreateShinyButton()
        refreshPreviewModel()
    }

    private fun updateCreateShinyButton() {
        val text = when (createShinyFilter) {
            PokemonBlacklistEntry.SHINY_YES -> "★"
            PokemonBlacklistEntry.SHINY_NO -> "☆"
            else -> Text.translatable("cobblemarket.gui.shiny_any").string
        }
        createShinyButton?.setMessage(Text.literal(text))
        createShinyButton?.textColor = if (createShinyFilter == PokemonBlacklistEntry.SHINY_YES) GOLD_COLOR else 0xFFFFFF
    }

    private fun cycleCreateHt() {
        // 循环顺序：不限(0) → 仅特训(1) → 不含特训(2) → 不限
        createHtFilter = when (createHtFilter) {
            PokemonBlacklistEntry.HT_ANY -> PokemonBlacklistEntry.HT_ONLY
            PokemonBlacklistEntry.HT_ONLY -> PokemonBlacklistEntry.HT_NONE
            else -> PokemonBlacklistEntry.HT_ANY
        }
        updateCreateHtButton()
    }

    private fun updateCreateHtButton() {
        val key = when (createHtFilter) {
            PokemonBlacklistEntry.HT_ONLY -> "cobblemarket.gui.filter_ht_on"
            PokemonBlacklistEntry.HT_NONE -> "cobblemarket.gui.filter_ht_off"
            else -> "cobblemarket.gui.filter_ht_any"
        }
        createHtButton?.setMessage(Text.translatable(key))
    }

    // ── 创建对话框：物种解析预览（照黑名单添加框） ──

    private fun updatePokemonPreview(text: String) {
        // 解析出**全部**候选（精确优先已由 candidatesByNameOrId 保证），默认选第一个：
        // 输入完整名字（鬼斯）直接就是它；输入片段（鬼）时用预览槽位下方的箭头在旁边挑
        speciesCandidates = com.shusheng.cobblemarket.util.SpeciesText.candidatesByNameOrId(text)
        speciesIndex = 0
        applySpecies(speciesCandidates.firstOrNull())
        updateSpeciesArrows()
        refreshListVisibility()
    }

    /** 左右箭头切换候选（循环）；切换后预览图、形态与特性选项一起刷新 */
    private fun cycleSpecies(delta: Int) {
        if (speciesCandidates.size <= 1) return
        speciesIndex = (speciesIndex + delta + speciesCandidates.size) % speciesCandidates.size
        applySpecies(speciesCandidates[speciesIndex])
        updateSpeciesArrows()
    }

    /** 箭头只在精灵页签、且候选 ≥2 时出现（唯一候选没什么可切的；物品页签没有物种可切） */
    private fun updateSpeciesArrows() {
        val multi = createTab == 0 && speciesCandidates.size > 1
        speciesPrevButton?.visible = multi
        speciesNextButton?.visible = multi
    }

    /** 应用选中的物种：重建预览模型、形态与特性选项（输入变化与箭头切换共用） */
    private fun applySpecies(species: com.cobblemon.mod.common.pokemon.Species?) {
        previewRenderable = null
        previewSpecies = null
        formOptions = listOf()
        formIndex = 0
        formListOpen = false
        abilityListOpen = false
        natureListOpen = false
        rebuildFormList()
        if (species != null) {
            previewSpecies = species
            // 特性选项：物种可用特性（含隐藏），template.name 拼翻译 key（与服务端 ability.name 语义一致）
            abilityOptions = species.abilities.map { pa ->
                val key = "cobblemon.ability.${pa.template.name}"
                val t = Text.translatable(key).string
                key to (if (t == key) pa.template.displayName else t)
            }
            abilityIndex = -1
            updateAbilityButton()
            val seen = mutableSetOf<Set<String>>(setOf(PokemonBlacklistEntry.ALL_FORMS))
            val opts = mutableListOf(FormOption(Text.translatable("cobblemarket.blacklist.form_all").string, setOf(PokemonBlacklistEntry.ALL_FORMS)))
            val standardAspects = species.standardForm.aspects.toSet()
            if (standardAspects.isNotEmpty()) {
                seen.add(standardAspects)
                opts.add(FormOption(formLabel(standardAspects), standardAspects))
            } else if (species.forms.isNotEmpty()) {
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
            createFormButton?.visible = formOptions.size > 1
            createFormButton?.setMessage(formButtonText())
        } else {
            createFormButton?.visible = false
            abilityOptions = listOf()
            abilityIndex = -1
            updateAbilityButton()
        }
        refreshListVisibility()
    }

    // 物种名解析统一走 SpeciesText.candidatesByNameOrId（精确优先 + 返回全部候选，见 updatePokemonPreview）

    /** 按当前形态选择 + 闪光选择重建预览模型：仅闪光时叠加 shiny aspect 渲染闪光形态 */
    private fun refreshPreviewModel() {
        val species = previewSpecies ?: run { previewRenderable = null; return }
        val aspects = formOptions.getOrNull(formIndex)?.aspects
            ?.filter { it != PokemonBlacklistEntry.ALL_FORMS }?.toMutableSet() ?: mutableSetOf()
        if (createShinyFilter == PokemonBlacklistEntry.SHINY_YES) aspects.add("shiny")
        previewRenderable = RenderablePokemon(species, aspects, ItemStack.EMPTY)
    }

    private fun formLabel(aspects: Set<String>): String = when {
        PokemonBlacklistEntry.ALL_FORMS in aspects -> Text.translatable("cobblemarket.blacklist.form_all").string
        aspects.isEmpty() -> Text.translatable("cobblemarket.blacklist.form_default").string
        else -> aspects.joinToString("/") { aspectLabel(it) }
    }

    private fun formButtonText() = Text.literal(
        com.shusheng.cobblemarket.util.TextUtil.truncateString(
            "${Text.translatable("cobblemarket.blacklist.form").string}${formOptions.getOrNull(formIndex)?.label ?: ""}",
            132
        )
    )

    /** 形态点选（照黑名单 selectForm：先收列表，再刷按钮与预览模型） */
    private fun selectForm(idx: Int) {
        formIndex = idx
        formListOpen = false
        rebuildFormList()
        createFormButton?.setMessage(formButtonText())
        refreshPreviewModel()
    }

    /** 特性按钮文本：不限 / 所选特性显示名 */
    private fun abilityButtonText(): String {
        val sel = abilityOptions.getOrNull(abilityIndex)?.second
        val label = sel ?: Text.translatable("cobblemarket.buy_order.any").string
        return "${Text.translatable("cobblemarket.buy_order.ability_label").string}${com.shusheng.cobblemarket.util.TextUtil.truncateString(label, 90)}"
    }

    private fun updateAbilityButton() {
        createAbilityButton?.setMessage(Text.literal(abilityButtonText()))
    }

    private fun natureButtonText(): String {
        val sel = natureOptions.getOrNull(natureIndex)?.second
        val label = sel ?: Text.translatable("cobblemarket.buy_order.any").string
        return "${Text.translatable("cobblemarket.buy_order.nature_label").string}$label"
    }

    private fun updateNatureButton() {
        createNatureButton?.setMessage(Text.literal(natureButtonText()))
    }

    private fun toggleFormList() {
        formListOpen = !formListOpen
        abilityListOpen = false
        natureListOpen = false
        formListScroll = formListScroll.coerceIn(0, maxOf(0, formOptions.size - MAX_FORM_LIST_ROWS))
        rebuildFormList()
        rebuildAbilityList()
        rebuildNatureList()
    }

    private fun toggleAbilityList() {
        if (abilityOptions.isEmpty()) return
        abilityListOpen = !abilityListOpen
        formListOpen = false
        natureListOpen = false
        rebuildFormList()
        rebuildAbilityList()
        rebuildNatureList()
    }

    private fun toggleNatureList() {
        natureListOpen = !natureListOpen
        formListOpen = false
        abilityListOpen = false
        natureListScroll = natureListScroll.coerceIn(0, maxOf(0, natureOptions.size - MAX_NATURE_LIST_ROWS))
        rebuildFormList()
        rebuildAbilityList()
        rebuildNatureList()
    }

    // 展开的形态列表：每行一个选项，最多 8 行可见，超出滚动（照黑名单添加框）。
    // 展开时隐藏被列表覆盖的控件——它们先于形态选项添加，
    // 不隐藏的话点击会被它们拦截、文字从按钮间隙透出来。
    private fun rebuildFormList() {
        formListButtons.forEach { remove(it) }
        formListButtons.clear()
        refreshListVisibility()
        if (!formListOpen) return
        val centerX = width / 2
        val dialogY = createDialogY()
        formOptions.drop(formListScroll).take(MAX_FORM_LIST_ROWS).forEachIndexed { i, opt ->
            val idx = formListScroll + i
            // 数据包自创的超长 aspect 名截断，防止溢出按钮
            val label = com.shusheng.cobblemarket.util.TextUtil.truncateString(opt.label, 124)
            val btn = NineSliceButton(
                centerX - 82, createY(82 + i * 14), 148, 14,
                if (idx == formIndex) com.shusheng.cobblemarket.util.TextUtil.selectedText(label) else Text.literal(label),
                { selectForm(idx) },
                texture = BUY_ORDER_BUTTON_TEXTURE,
                texH = BUY_ORDER_BUTTON_TEX_H
            )
            formListButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // 展开的特性列表（第一行固定「不限」，其余为物种可用特性，最多 3 个无需滚动）
    private fun rebuildAbilityList() {
        abilityListButtons.forEach { remove(it) }
        abilityListButtons.clear()
        refreshListVisibility()
        if (!abilityListOpen) return
        val centerX = width / 2
        val dialogY = createDialogY()
        val anyLabel = Text.translatable("cobblemarket.buy_order.any").string
        // 第一行固定：不限（abilityIndex = -1）
        val anyBtn = NineSliceButton(
            centerX - 82, createY(140), 148, 14,
            if (abilityIndex == -1) com.shusheng.cobblemarket.util.TextUtil.selectedText(anyLabel) else Text.literal(anyLabel),
            {
                abilityIndex = -1
                abilityListOpen = false
                updateAbilityButton()
                rebuildAbilityList()
            },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        abilityListButtons.add(anyBtn)
        addDrawableChild(anyBtn)
        abilityOptions.forEachIndexed { i, (_, label) ->
            val btn = NineSliceButton(
                centerX - 82, createY(154 + i * 14), 148, 14,
                if (i == abilityIndex) com.shusheng.cobblemarket.util.TextUtil.selectedText(
                    com.shusheng.cobblemarket.util.TextUtil.truncateString(label, 124)
                ) else Text.literal(com.shusheng.cobblemarket.util.TextUtil.truncateString(label, 124)),
                {
                    abilityIndex = i
                    abilityListOpen = false
                    updateAbilityButton()
                    rebuildAbilityList()
                },
                texture = BUY_ORDER_BUTTON_TEXTURE,
                texH = BUY_ORDER_BUTTON_TEX_H
            )
            abilityListButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // 展开的性格列表（第一行固定「不限」，其余 25 种性格最多 6 行滚动）
    private fun rebuildNatureList() {
        natureListButtons.forEach { remove(it) }
        natureListButtons.clear()
        refreshListVisibility()
        if (!natureListOpen) return
        val centerX = width / 2
        val dialogY = createDialogY()
        val anyLabel = Text.translatable("cobblemarket.buy_order.any").string
        // 第一行固定：不限（natureIndex = -1，不参与滚动）
        val anyBtn = NineSliceButton(
            centerX - 82, createY(158), 148, 14,
            if (natureIndex == -1) com.shusheng.cobblemarket.util.TextUtil.selectedText(anyLabel) else Text.literal(anyLabel),
            {
                natureIndex = -1
                natureListOpen = false
                updateNatureButton()
                rebuildNatureList()
            },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        natureListButtons.add(anyBtn)
        addDrawableChild(anyBtn)
        natureOptions.drop(natureListScroll).take(MAX_NATURE_LIST_ROWS).forEachIndexed { i, (_, label) ->
            val idx = natureListScroll + i
            val btn = NineSliceButton(
                centerX - 82, createY(172 + i * 14), 148, 14,
                if (idx == natureIndex) com.shusheng.cobblemarket.util.TextUtil.selectedText(label) else Text.literal(label),
                {
                    natureIndex = idx
                    natureListOpen = false
                    updateNatureButton()
                    rebuildNatureList()
                },
                texture = BUY_ORDER_BUTTON_TEXTURE,
                texH = BUY_ORDER_BUTTON_TEX_H
            )
            natureListButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    /** 任一列表展开时隐藏其覆盖的下层控件（三个列表互斥，此函数统一维护可见性） */
    private fun refreshListVisibility() {
        if (createTab != 0) return
        val anyOpen = formListOpen || abilityListOpen || natureListOpen
        // 形态列表（y+82 起 8 行）盖：特训/IV/特性按钮/性格按钮/价格/备注
        createHtButton?.visible = !formListOpen
        createIvFields.forEach { it?.visible = !formListOpen }
        createAbilityButton?.visible = !formListOpen && !abilityListOpen
        createNatureButton?.visible = !anyOpen
        // 特性列表（y+140 起 ≤3 行）盖：性格按钮/价格/备注；性格列表（y+158 起 6 行）盖：价格/备注/确认/取消
        createMinPriceField?.visible = !anyOpen
        createMaxPriceField?.visible = !anyOpen
        createNoteField?.visible = !anyOpen
        createConfirmButton?.visible = !natureListOpen
        createCancelButton?.visible = !natureListOpen
    }

    // ── 创建对话框：物品解析 ──

    // 收集全部匹配物品（优先级：ID 路径精确 > 翻译名精确 > 翻译名包含），保持注册表顺序稳定（照黑名单物品对话框）。
    // 必须 tryParse（Identifier.of 对中文/空格等非法字符直接抛异常，输入即崩溃）
    private fun resolveMatchingItems(input: String): List<com.shusheng.cobblemarket.client.ItemCandidate> =
        com.shusheng.cobblemarket.client.ItemCandidateResolver.resolve(input)

    private fun itemDisplay(itemId: String): String =
        com.shusheng.cobblemarket.client.ItemCandidateResolver.displayNameOf(itemId)

    /** 物品输入变更：重建匹配列表（照黑名单物品对话框） */
    private fun updateItemPreview(text: String) {
        // 打字即退出手持模式（两条添加路径互斥），随后按搜索路径走
        exitHeldItemMode()
        matchedItems = resolveMatchingItems(text)
        // 唯一匹配自动选中；多匹配等待用户点选
        selectedItemIndex = if (matchedItems.size == 1) 0 else -1
        itemListOpen = false
        itemListScroll = 0
        rebuildItemList()
        updateItemSelectButton()
    }

    private fun updateItemSelectButton() {
        // 列表展开时按钮隐藏（rebuildItemList 已处理，这里防御其他路径恢复显示）
        itemSelectButton?.visible = matchedItems.isNotEmpty() && !itemListOpen
        val label = matchedItems.getOrNull(selectedItemIndex)?.displayName
            ?: if (matchedItems.size > 1)
                Text.translatable("cobblemarket.buy_order.item_matches", matchedItems.size).string
            else ""
        itemSelectButton?.setMessage(Text.literal(
            com.shusheng.cobblemarket.util.TextUtil.truncateString(
                "${Text.translatable("cobblemarket.buy_order.item_label").string}$label",
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
        exitHeldItemMode()
        selectedItemIndex = idx
        itemListOpen = false
        rebuildItemList()
        updateItemSelectButton()
    }

    // 展开的匹配列表：与形态/性格列表同模式，展开时隐藏被覆盖的件数/价格/确认/取消，
    // 并隐藏选择按钮自身（列表从按钮原位展开，否则按钮文字与列表行视觉打架）
    private fun rebuildItemList() {
        itemOptionButtons.forEach { remove(it) }
        itemOptionButtons.clear()
        if (createTab != 1) return
        itemSelectButton?.visible = !itemListOpen
        createCountField?.visible = !itemListOpen
        createMinPriceField?.visible = !itemListOpen
        createMaxPriceField?.visible = !itemListOpen
        createNoteField?.visible = !itemListOpen
        createConfirmButton?.visible = !itemListOpen
        createCancelButton?.visible = !itemListOpen
        if (!itemListOpen) return
        val centerX = width / 2
        val dialogY = createDialogY()
        matchedItems.drop(itemListScroll).take(MAX_ITEM_LIST_ROWS).forEachIndexed { i, candidate ->
            val idx = itemListScroll + i
            val label = com.shusheng.cobblemarket.util.TextUtil.truncateString(candidate.displayName, 124)
            val btn = NineSliceButton(
                centerX - 82, createY(66 + i * 14), 148, 14,
                if (idx == selectedItemIndex) com.shusheng.cobblemarket.util.TextUtil.selectedText(label) else Text.literal(label),
                { selectItem(idx) },
                texture = BUY_ORDER_BUTTON_TEXTURE,
                texH = BUY_ORDER_BUTTON_TEX_H
            )
            itemOptionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // ── 交付对话框 ──

    // ── 买家确认弹窗（处理待确认交付） ──

    /** 验收弹窗物品词条构建（ADVANCED，去首行物品名；Shift 按住时调用才有 Fabric 信息块） */
    private fun buildReviewItemLines(entry: BuyOrderEntry, type: TooltipType): List<Text> {
        val out = mutableListOf<Text>()
        entry.pending?.itemNbt?.let { nbt ->
            client?.world?.registryManager?.let { rm ->
                val stack = ItemStack.fromNbtOrEmpty(rm, nbt)
                if (!stack.isEmpty) {
                    out.addAll(com.shusheng.cobblemarket.client.ItemComponentsDisplay.itemTooltip(stack, client?.player, type).drop(1))
                }
            }
        }
        return out
    }

    /**
     * 审核弹窗高度：精灵单按信息区实际内容算——起始 +26，13 基础行
     * （名字/类型/性格特性/[球种]/[携带物]/IV 标签/6×IV/亲密度/[证章]/卖家/价格）
     * 加球种/携带物行与证章区块，底部再留 72px（输入框 16 + 6px 间隙 + 按钮 20 + 30px 边距）。
     * 物品单按词条行数算。加新信息行（如技能）时只改这里的 13。
     */
    private fun reviewDialogHeight(entry: BuyOrderEntry, itemExtra: Int): Int =
        if (entry.type != "POKEMON") 149 + itemExtra * 10
        else {
            val listing = entry.pending?.let { pendingToListing(entry, it) }
            val infoRows = 13 +
                (if (listing?.ball?.isNotEmpty() == true) 1 else 0) +
                (if (listing != null && EntryBadgeRenderer.hasHeldItemLine(listing)) 1 else 0)
            val marksH = if (listing != null) EntryBadgeRenderer.marksBlockHeight(listing.marks, listing.moves) else 0
            val movesH = if (listing != null) EntryBadgeRenderer.movesBlockHeight(listing.moves) else 0
            // 顶部 62 = 标题 + 28 高的图标（占 +28~+56）+ 6px 间隙，名字行才起在 +62（照市场购买确认弹窗）
            62 + infoRows * 10 + movesH + marksH + 72
        }

    /**
     * 按待确认交付的精灵重建弹窗里的 3D 模型（照市场购买确认弹窗的 buildConfirmRenderable）。
     *
     * speciesId / aspects 都取自 pending.extraData —— 就是卖家交付来的那一只，形态与实物一致；
     * 闪光再兜底叠加（shiny 是独立字段）。携带物一并带上，弹窗里的精灵也拿东西。
     */
    /**
     * 强制下架弹窗的精灵图标模型（标题正下方）。
     * 非精灵单、或「任意精灵」单（speciesId 为空）不画；aspects 用挂单自己的（性别/地区形态），
     * 与审核弹窗同一口径 —— 别再自己加 shiny。
     */
    private fun buildForceRenderable(entry: BuyOrderEntry) {
        forceRenderable = entry.speciesId
            ?.let { net.minecraft.util.Identifier.tryParse(it) }
            ?.let { com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getByIdentifier(it) }
            ?.let { RenderablePokemon(it, entry.aspects.toMutableSet(), ItemStack.EMPTY) }
    }

    private fun buildReviewRenderable(entry: BuyOrderEntry) {
        val pending = entry.pending
        if (entry.type != "POKEMON" || pending == null) { reviewRenderable = null; return }
        val species = pending.extraData["speciesId"]
            ?.let { Identifier.tryParse(it)?.let { id -> PokemonSpecies.getByIdentifier(id) } }
        if (species == null) { reviewRenderable = null; return }
        val aspects = (pending.extraData["aspects"] ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()
        if (pending.shiny && "shiny" !in aspects) aspects.add("shiny")
        reviewRenderable = RenderablePokemon(species, aspects, heldIconStack(pending.extraData) ?: ItemStack.EMPTY)
    }

    /**
     * 审核弹窗**实际**高度：精灵单**按屏幕钳制**（信息行多时设计高度能顶出屏幕——技能块 / 两行证章 /
     * 球种 / 携带物齐全时约 358，而自动 GUI 尺寸在 1080p 下逻辑高只有 270）；物品单保持原样（词条行数自限）。
     */
    private fun reviewDialogH(entry: BuyOrderEntry, itemExtra: Int): Int =
        if (entry.type == "POKEMON") minOf(reviewDialogHeight(entry, itemExtra), height - 8)
        else reviewDialogHeight(entry, itemExtra)

    private fun reviewViewH(entry: BuyOrderEntry, itemExtra: Int): Int =
        (reviewDialogH(entry, itemExtra) - reviewContentTop - reviewBottomH).coerceAtLeast(1)

    private fun reviewContentH(entry: BuyOrderEntry, itemExtra: Int): Int =
        reviewDialogHeight(entry, itemExtra) - reviewContentTop - reviewBottomH

    private fun reviewMaxScroll(entry: BuyOrderEntry, itemExtra: Int): Int =
        maxOf(0, reviewContentH(entry, itemExtra) - reviewViewH(entry, itemExtra))

    private fun openReviewDialog(entry: BuyOrderEntry) {
        reviewEntry = entry
        reviewScroll = 0
        buildReviewRenderable(entry)
        // 两个起点都清掉：弹窗可能在精灵单 / 物品单之间来回换，留着另一边会登记出错误起点
        reviewIconOrigin = null
        reviewItemIconOrigin = null
        // 弹窗打开时自动叫一声（渲染后延迟再播，理由见 reviewCryAt）
        reviewCryAt = System.currentTimeMillis() + IconCryClick.AUTO_CRY_DELAY_MS
        // 物品词条行（附魔等）：打开时重建一次，弹窗渲染每帧只读；行数决定后续控件下移量；
        // BASIC/ADVANCED 双份构建（渲染按 Shift 切换；ADVANCED 打开时无 Shift 缺信息块，
        // 渲染按住 Shift 会重建）——布局按两份最大行数 + 6 行信息块余量预留（防展开溢出）
        reviewItemTooltipLines = emptyList()
        reviewItemAdvancedLines = emptyList()
        reviewAdvancedBuiltWithShift = false
        reviewItemExtraRows = 0
        if (entry.type == "ITEM") {
            entry.pending?.itemNbt?.let { nbt ->
                client?.world?.registryManager?.let { rm ->
                    val stack = ItemStack.fromNbtOrEmpty(rm, nbt)
                    if (!stack.isEmpty) {
                        // 走统一入口（别直连 getTooltip）：受损物品才会多一行「耐久：X / Y」
                        // drop(1) 丢掉物品名（弹窗自己会画），耐久行紧随其后会被保留
                        val lines = com.shusheng.cobblemarket.client.ItemComponentsDisplay
                            .itemTooltip(stack, client?.player, TooltipType.BASIC).drop(1)
                        val advanced = com.shusheng.cobblemarket.client.ItemComponentsDisplay.itemTooltip(stack, client?.player, TooltipType.ADVANCED).drop(1)
                        reviewItemTooltipLines = lines
                        reviewItemAdvancedLines = advanced
                        reviewItemExtraRows = minOf(maxOf(lines.size, advanced.size), MAX_ITEM_EXTRA_ROWS)
                    }
                }
            }
        }
        // 隐藏主界面控件
        backButton?.visible = false
        createButton?.visible = false
        searchField?.visible = false
        currencyButton?.visible = false
        tabButtons.forEach { it.visible = false }
        rowButtons.forEach { it.visible = false }

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderReviewDialogBackground(context, delta)
            }
        })

        val centerX = width / 2
        val dialogH = reviewDialogH(entry, reviewItemExtraRows)
        val dialogY = height / 2 - dialogH / 2
        // 精灵单：输入框/按钮相对弹窗底部锚定（弹窗高度随信息区内容变，底部间距固定）
        val btnY = if (entry.type == "POKEMON") dialogY + dialogH - 40 else dialogY + 67 + reviewItemExtraRows * 10

        // 拒绝原因输入框（选填，发给卖家）
        reviewReasonField = TextFieldWidget(textRenderer, centerX - 120, dialogY + if (entry.type == "POKEMON") dialogH - 66 else 43 + reviewItemExtraRows * 10, 240, 16, Text.literal(""))
        reviewReasonField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.review_reason").formatted(Formatting.GRAY))
        reviewReasonField?.setMaxLength(100)
        addDrawableChild(reviewReasonField)

        // 按钮顺序照惯例：取消在右
        reviewAcceptButton = NineSliceButton(
            centerX - 94, btnY, 56, 20,
            Text.translatable("cobblemarket.buy_order.review_accept"),
            { acceptReview() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(reviewAcceptButton)
        reviewRejectButton = NineSliceButton(
            centerX - 32, btnY, 56, 20,
            Text.translatable("cobblemarket.buy_order.review_reject"),
            { rejectReview() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(reviewRejectButton)
        reviewCancelButton = NineSliceButton(
            centerX + 30, btnY, 56, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeDialogs() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(reviewCancelButton)
    }

    private fun renderReviewDialogBackground(context: DrawContext, delta: Float) {
        val entry = reviewEntry ?: return
        val pending = entry.pending ?: return
        val centerX = width / 2
        val dialogW = minOf(340, width - 40).coerceAtLeast(200)
        // 物品词条选择（先于布局）：Shift 完整词条 / Ctrl 调试信息按需构建（Fabric tooltip 信息块只在构建时按键按住才生成）
        var itemLines: List<Text>? = null
        var itemExtra = 0
        if (entry.type == "ITEM") {
            val tooltipLines = if (com.shusheng.cobblemarket.client.ItemComponentsDisplay.hoverExpanded()) {
                val type = com.shusheng.cobblemarket.client.ItemComponentsDisplay.tooltipTypeForHover()
                if (!reviewAdvancedBuiltWithShift || reviewAdvancedType != type) {
                    reviewItemAdvancedLines = buildReviewItemLines(entry, type)
                    reviewAdvancedBuiltWithShift = true
                    reviewAdvancedType = type
                }
                reviewItemAdvancedLines
            } else {
                reviewAdvancedBuiltWithShift = false
                reviewAdvancedType = null
                reviewItemTooltipLines
            }
            itemLines = tooltipLines
            itemExtra = minOf(tooltipLines.size, MAX_ITEM_EXTRA_ROWS)
        }
        // 精灵单：完整信息行（照市场确认弹窗），弹窗更高；物品单：词条行数决定弹窗高度（Shift 展开动态伸缩）
        // 物品单 149：词条区（+44 起，行距 10 与物品市场一致）+ 卖家/出价/总价竖排三行 + 输入框 + 按钮，底部留白 18
        val dialogH = reviewDialogH(entry, itemExtra)
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2
        // 物品分支控件位置每帧同步（Shift 展开/收起时布局动态伸缩，不挤压不空档）
        if (entry.type == "ITEM") {
            val baseY = dialogY + 44 + itemExtra * 10
            reviewReasonField?.y = baseY + 43
            reviewAcceptButton?.y = baseY + 67
            reviewRejectButton?.y = baseY + 67
            reviewCancelButton?.y = baseY + 67
        }

        drawScreenDimMask(context, width, height)
        // 不透明衬底：弹窗背景贴图中间区域半透明，下层行内容（精灵图标/数量/价格）会透过
        context.fill(dialogX, dialogY, dialogX + dialogW, dialogY + dialogH, 0xFF2A2A2A.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.buy_order.review_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        if (entry.type == "POKEMON") {
            // 精灵 3D 图标（槽背景 + 精灵）：与市场购买确认弹窗同一摆法——标题下方居中
            val iconSize = 28
            val iconX = centerX - iconSize / 2
            val iconY = dialogY + 28
            context.matrices.push()
            context.matrices.translate(iconX.toDouble(), iconY.toDouble(), 0.0)
            context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
            context.drawTexture(POKEMON_SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
            context.matrices.pop()
            // 记下这个图标的位置（庆祝动画要从这里起飞）；拿不到 renderable 时清掉，免得沿用上一只的旧坐标
            reviewIconOrigin = if (reviewRenderable == null) null
            else PokemonCelebrationAnimation.Origin.ofIcon(
                x = iconX, y = iconY, size = iconSize, centerAnchorY = false
            )
            reviewRenderable?.let { rp ->
                // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
                val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                    com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
                try {
                    // 弹窗图标不做裁剪（照市场购买确认弹窗，useScissor = false）
                    PokemonIconRenderer.draw(
                        context = context,
                        renderable = rp,
                        state = reviewState,
                        x = iconX, y = iconY, size = iconSize,
                        delta = delta,
                        animate = useFloat,
                        useScissor = false
                    )
                    // 打开确认交付弹窗时自动叫一声（替玩家点一下这个图标）：**必须在渲染之后** ——
                    // addFirstAnimation 依赖 currentModel，那是 drawProfilePokemon 里才建好的；
                    // 再叠一层 AUTO_CRY_DELAY_MS 延迟，等弹窗稳定显示、玩家看清了再叫
                    if (reviewCryAt != 0L && System.currentTimeMillis() >= reviewCryAt) {
                        reviewCryAt = 0L
                        IconCryClick.play(reviewState, rp)
                    }
                } catch (_: Exception) {
                }
            }

            // 完整精灵信息行（与市场购买确认弹窗同一渲染器：名字★Lv/类型/性格特性/携带物/IVs 六行/卖家/价格）
            val listing = pendingToListing(entry, pending)
            val name = pending.speciesKey?.let {
                val t = Text.translatable(it).string
                if (t == it) pending.extraData["speciesName"] ?: it else t
            } ?: "?"
            // 弹窗被屏幕压矮时**信息区可滚动**（scissor 裁剪 + 起始 y 减偏移）
            reviewScroll = reviewScroll.coerceIn(0, reviewMaxScroll(entry, itemExtra))
            val viewTop = dialogY + reviewContentTop
            val viewBottom = dialogY + dialogH - reviewBottomH
            context.enableScissor(dialogX, viewTop, dialogX + dialogW, viewBottom)
            EntryBadgeRenderer.drawInfoLines(
                context, listing,
                EntryBadgeRenderer.nameWithShinyStar(name, pending.shiny),
                // +62：图标占 +28~+55，留 6px 间隙后起名字行（与市场购买确认弹窗一致）
                centerX, viewTop - reviewScroll, dividerW = dialogW - 156, maxWidth = dialogW - 24
            )
            context.disableScissor()
        } else {
            // 物品行：图标 + 名称 + 数量整体居中（照交付弹窗形态行格式）；
            // 垂直在标题（+14）与卖家行（+58）中间：物品行中心 ≈ 36 → 图标 y=28、文字基线 y=32
            val itemName = itemDisplay(entry.itemId)
            val countStr = "×${pending.count}"
            // 图标用带 NBT 的重建（附魔光效可见）；失败回退纯物品
            val itemStack = pending.itemNbt?.let { nbt ->
                client?.world?.registryManager?.let { rm -> ItemStack.fromNbtOrEmpty(rm, nbt) }?.takeIf { !it.isEmpty }
            } ?: (Identifier.tryParse(entry.itemId)?.let { ItemStack(Registries.ITEM.get(it)) } ?: ItemStack.EMPTY)
            val totalW = 16 + 4 + textRenderer.getWidth(itemName) + 4 + textRenderer.getWidth(countStr)
            val startX = centerX - totalW / 2
            drawItemWithBar(context, itemStack, startX, dialogY + 28)
            // 记下这个图标的位置（物品庆祝动画要从这里起飞）；物品图标基准 16×16
            reviewItemIconOrigin = ItemCelebrationAnimation.Origin.ofIcon(
                x = startX, y = dialogY + 28, size = 16
            )
            // 物品名照物品栏悬浮第一行按稀有度着色
            context.drawTextWithShadow(textRenderer,
                if (itemStack.isEmpty) Text.literal(itemName)
                else com.shusheng.cobblemarket.util.TextUtil.rarityColoredName(itemStack),
                startX + 20, dialogY + 32, 0xFFFFFF)
            context.drawTextWithShadow(textRenderer, countStr, startX + 20 + textRenderer.getWidth(itemName) + 4, dialogY + 32, 0xAAAAAA)
            // 物品词条（附魔/名称等，去首行物品名；超过上限才截断以「…」收尾）；按住 Shift 展开高级词条
            //（列表已在函数头按 Shift 状态选好）；行距 10 与物品市场悬停一致（9 太贴）
            var ty = dialogY + 44
            val tooltipLines = itemLines ?: emptyList()
            val shown = if (tooltipLines.size > MAX_ITEM_EXTRA_ROWS) tooltipLines.take(MAX_ITEM_EXTRA_ROWS - 1) + Text.literal("…") else tooltipLines
            shown.forEach { line ->
                context.drawCenteredTextWithShadow(textRenderer, line, centerX, ty, 0xFFFFFF)
                ty += 10
            }
            // 卖家 / 出价 / 总价竖排三行（2026-09-06 拍板：原来一行式太挤且底部留白过大；
            // 标签默认色、金额金色照颜色模板；行距 10 与物品市场悬停一致）
            val baseY = dialogY + 44 + itemExtra * 10
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.buy_order.review_seller").append(Text.literal(pending.sellerName)),
                centerX, baseY + 10, 0xFFFFFF)
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.buy_order.review_price").append(
                    // 单价按**订单自己的币种**（这是买家验收时看到的价，标错单位就是误导）
                    amountWithUnit(com.shusheng.cobblemarket.client.formatPrice(pending.price), entry.isPoints, "", inline = true).formatted(Formatting.GOLD)),
                centerX, baseY + 20, 0xFFFFFF)
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.buy_order.review_total").append(
                    amountWithUnit(com.shusheng.cobblemarket.client.formatPriceLong(pending.price.toLong() * pending.count), entry.isPoints, "", inline = true).formatted(Formatting.GOLD)),
                centerX, baseY + 30, 0xFFFFFF)
        }
        // 拒绝原因输入框占据原说明行位置（placeholder 已说明用途）
    }

    /** pending 的 extraData 构造 ListingEntry（复用市场确认弹窗的完整信息行渲染器） */
    private fun pendingToListing(entry: BuyOrderEntry, pending: BuyOrderPendingEntry): ListingEntry {
        val d = pending.extraData
        fun i(key: String) = d[key]?.toIntOrNull() ?: 0
        fun ht(key: String) = d[key]?.toIntOrNull() ?: -1
        return ListingEntry(
            id = pending.id,
            sellerUuid = entry.buyerUuid,
            species = pending.speciesKey ?: "",
            speciesId = d["speciesId"] ?: "",
            level = pending.level,
            shiny = pending.shiny,
            price = pending.price,
            sellerName = pending.sellerName,
            primaryType = d["primaryType"] ?: "",
            secondaryType = d["secondaryType"] ?: "",
            ivsHp = i("ivsHp"), ivsAtk = i("ivsAtk"), ivsDef = i("ivsDef"),
            ivsSpAtk = i("ivsSpAtk"), ivsSpDef = i("ivsSpDef"), ivsSpd = i("ivsSpd"),
            htHp = ht("htHp"), htAtk = ht("htAtk"), htDef = ht("htDef"),
            htSpAtk = ht("htSpAtk"), htSpDef = ht("htSpDef"), htSpd = ht("htSpd"),
            evsHp = i("evsHp"), evsAtk = i("evsAtk"), evsDef = i("evsDef"),
            evsSpAtk = i("evsSpAtk"), evsSpDef = i("evsSpDef"), evsSpd = i("evsSpd"),
            friendship = i("friendship"),
            nature = d["nature"] ?: "",
            natureBase = d["natureBase"] ?: d["nature"] ?: "",
            ability = d["ability"] ?: "",
            gender = d["gender"] ?: "",
            ball = d["ball"] ?: "",
            ballItem = d["ballItem"] ?: "",
            heldItemId = d["heldItemId"] ?: "",
            currencyName = "",
            aspects = (d["aspects"] ?: "").split(",").filter { it.isNotEmpty() },
            marks = (d["marks"] ?: "").split(",").filter { it.isNotEmpty() },
            // 币种跟着**订单**走（pending 挂在订单上，自己没有币种字段）
            currency = entry.currency,
            moves = d["moves"].orEmpty()
        )
    }

    private fun acceptReview() {
        val entry = reviewEntry ?: return
        val pending = entry.pending ?: return
        // 登记庆祝动画的起飞起点 —— 就是本弹窗那个精灵图标的位置与大小（照市场购买确认弹窗）。
        // 起点带 3 秒过期（见 PokemonCelebrationAnimation.setPendingOrigin），交付没成功也不会留脏值。
        reviewIconOrigin?.let(PokemonCelebrationAnimation::setPendingOrigin)
        // 物品单走物品那套（精灵 / 物品只有一个字段有值，另一边恒为 null）
        reviewItemIconOrigin?.let(ItemCelebrationAnimation::setPendingOrigin)
        sendToServer(AcceptPendingDeliverPayload(entry.id, pending.id))
    }

    private fun rejectReview() {
        val entry = reviewEntry ?: return
        val pending = entry.pending ?: return
        sendToServer(RejectPendingDeliverPayload(entry.id, pending.id, reviewReasonField?.text?.trim().orEmpty()))
    }

    private fun openDeliverDialog(entry: BuyOrderEntry) {
        deliverEntry = entry
        deliverSelectedPokemon = null

        // 隐藏主界面控件
        backButton?.visible = false
        createButton?.visible = false
        searchField?.visible = false
        currencyButton?.visible = false
        tabButtons.forEach { it.visible = false }
        rowButtons.forEach { it.visible = false }

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderDeliverDialogBackground(context)
            }
        })

        val centerX = width / 2
        val dialogY = deliverDialogY()
        // 有买家留言时，其下所有控件整体下移一行（留言画在 +38，否则会被形态行按钮盖住）
        val shift = deliverNoteShift()

        if (entry.type == "POKEMON") {
            // 选择精灵按钮：打开上架选择界面（交付模式），选完回传本弹窗
            deliverSelectButton = NineSliceButton(
                centerX - 74, dialogY + 46 + shift, 148, 16,
                Text.literal(""),
                { openDeliverSelect() },
                texture = BUY_ORDER_BUTTON_TEXTURE,
                texH = BUY_ORDER_BUTTON_TEX_H
            )
            addDrawableChild(deliverSelectButton)
            updateDeliverSelectButton()

            deliverPriceField = TextFieldWidget(textRenderer, centerX - 40, dialogY + 84 + shift, 80, 16, Text.literal(""))
            deliverPriceField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.deliver_price").formatted(Formatting.GRAY))
            deliverPriceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
            deliverPriceField?.setChangedListener { updateDeliverConfirmActive() }
            deliverPriceField?.text = entry.minPrice.toString()
            addDrawableChild(deliverPriceField)
        } else {
            // 物品交付形态：默认取第一组；多形态时「选择形态」按钮可换（照精灵交付的选择精灵按钮），
            // 不自动跳转选择界面——自动跳转会导致返回时 pending 残留、下次打开列表误跳（已修）
            if (deliverVariant == null) {
                deliverVariant = variantGroups(entry.itemId).firstOrNull()?.first
            }

            val backCount = deliverVariant?.count ?: 0
            val prefillCount = minOf(entry.remainingCount, backCount).coerceAtLeast(1)
            deliverCountField = TextFieldWidget(textRenderer, centerX - 40, dialogY + 62 + shift, 80, 16, Text.literal(""))
            deliverCountField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.deliver_count").formatted(Formatting.GRAY))
            deliverCountField?.setTextPredicate { it.length <= 4 && it.all { c -> c.isDigit() } }
            deliverCountField?.setChangedListener { updateDeliverConfirmActive() }
            deliverCountField?.text = if (backCount > 0) prefillCount.toString() else ""
            addDrawableChild(deliverCountField)
            deliverPriceField = TextFieldWidget(textRenderer, centerX - 40, dialogY + 84 + shift, 80, 16, Text.literal(""))
            deliverPriceField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.deliver_price").formatted(Formatting.GRAY))
            deliverPriceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
            deliverPriceField?.setChangedListener { updateDeliverConfirmActive() }
            deliverPriceField?.text = entry.minPrice.toString()
            addDrawableChild(deliverPriceField)

            // 选择形态按钮（照精灵交付的「选择精灵」按钮：严格居中 centerX-74 宽 148）：
            // 多形态时显示在形态行位置（替代图标+名称行，文案显示当前形态名）
            deliverVariantButton = NineSliceButton(
                centerX - 74, dialogY + 44 + shift, 148, 16,
                Text.literal(""),
                {
                    val e = deliverEntry ?: return@NineSliceButton
                    pendingDeliverOrderId = e.id
                    stashReturnCurrency(currency)   // 理由同 openDeliverSelect
                    client?.setScreen(ItemVariantSelectScreen(e.id, e.itemId))
                },
                texture = BUY_ORDER_BUTTON_TEXTURE,
                texH = BUY_ORDER_BUTTON_TEX_H
            )
            deliverVariantButton?.visible = variantGroups(entry.itemId).size > 1
            addDrawableChild(deliverVariantButton)
            updateDeliverVariantButton()
        }

        // 按钮顺序照惯例：确认在左，取消在右
        deliverConfirmButton = NineSliceButton(
            centerX - 68, dialogY + confirmDeliverY() + shift, 56, 20,
            Text.translatable("cobblemarket.buy_order.deliver_confirm"),
            { confirmDeliver() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(deliverConfirmButton)
        deliverCancelButton = NineSliceButton(
            centerX + 8, dialogY + confirmDeliverY() + shift, 56, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeDialogs() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(deliverCancelButton)
        updateDeliverConfirmActive()
    }

    private fun deliverDialogY(): Int {
        val entry = deliverEntry ?: return height / 2 - 75
        return if (entry.type == "POKEMON") height / 2 - 75 else height / 2 - 76
    }

    private fun confirmDeliverY(): Int {
        val entry = deliverEntry ?: return 112
        return if (entry.type == "POKEMON") 112 else 110
    }

    /**
     * 买家留言占一行时的垂直偏移（留言画在 +38，约 9px 高）。
     * 其后的形态行（选择形态 / 选择精灵按钮）与各输入框、按钮整体下移这一行，
     * 否则留言会被按钮盖住（买家写「耐久不限」这类要求时卖家根本看不见）。
     * 布局与渲染共用本函数，保证两侧算出的 y 始终一致。
     */
    private fun deliverNoteShift(): Int =
        if (deliverEntry?.note?.isNotEmpty() == true) 11 else 0

    private fun renderDeliverDialogBackground(context: DrawContext) {
        val entry = deliverEntry ?: return
        val centerX = width / 2
        val dialogW = minOf(340, width - 40).coerceAtLeast(200)
        // 有买家留言时弹窗加高一行，与控件下移量一致（否则底部按钮会贴边）
        val noteShift = deliverNoteShift()
        val dialogH = (if (entry.type == "POKEMON") 150 else 152) + noteShift
        val dialogX = centerX - dialogW / 2
        val dialogY = deliverDialogY()

        drawScreenDimMask(context, width, height)
        // 不透明衬底：弹窗背景贴图中间区域半透明，下层行内容（精灵图标/数量/价格）会透过
        context.fill(dialogX, dialogY, dialogX + dialogW, dialogY + dialogH, 0xFF2A2A2A.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.buy_order.deliver_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        // 订单摘要：名称默认色，价格区间金色（2026-08-24 拍板；区间是单价区间，标注「单价」防误读）
        context.drawCenteredTextWithShadow(textRenderer,
            Text.empty().append(entryNameText(entry)).append(Text.literal("  ")).append(
                // ⚠ 前缀与价格同样用 Text 拼接（`.string +` 会丢掉 RP 图标的字体）
                Text.translatable("cobblemarket.buy_order.price_range_prefix")
                    .append(priceRangeText(entry)).formatted(Formatting.GOLD)
            ),
            centerX, dialogY + 27, 0xFFFFFF)

        // 买家留言（有备注时显示，灰色截断；卖家交付前须知）
        if (entry.note.isNotEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                com.shusheng.cobblemarket.util.TextUtil.truncateString(
                    "${Text.translatable("cobblemarket.buy_order.tooltip_note").string}${entry.note}", 240
                ),
                centerX, dialogY + 38, 0xFFDD99)
        }

        if (entry.type == "POKEMON") {
            // 已选精灵信息（名称 + 匹配状态；未选择时提示）：
            // 整体居中，垂直在按钮（+46~62）与输入框（+84）中间（区域中心 73 → 文字基线 70）
            val p = deliverSelectedPokemon
            if (p != null) {
                val name = com.shusheng.cobblemarket.util.TextUtil.truncateString(speciesDisplay(p), 60)
                val matchText = if (clientMatchesPokemon(p, entry))
                    Text.translatable("cobblemarket.buy_order.match_ok_full").string to 0x55FF55
                else
                    Text.translatable("cobblemarket.buy_order.match_no_full").string to 0xFF6666
                val totalW = textRenderer.getWidth(name) + 4 + textRenderer.getWidth(matchText.first)
                val startX = centerX - totalW / 2
                context.drawTextWithShadow(textRenderer, name, startX, dialogY + 70 + noteShift, EntryBadgeRenderer.typeColor(p.primaryType))
                context.drawTextWithShadow(textRenderer, matchText.first, startX + textRenderer.getWidth(name) + 4, dialogY + 70 + noteShift, matchText.second)
            } else {
                context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.buy_order.not_selected").formatted(Formatting.GRAY),
                    centerX, dialogY + 70 + noteShift, 0xAAAAAA)
            }
            // 单价输入标签：紧贴输入框左缘（输入框 x = centerX-40；+88 = 文字中心对齐输入框中心）
            val priceLabel = Text.translatable("cobblemarket.buy_order.deliver_price").string
            context.drawTextWithShadow(textRenderer,
                priceLabel,
                centerX - 44 - textRenderer.getWidth(priceLabel), dialogY + 88 + noteShift, 0xFFFFFF)
        } else {
            // 形态行（统一「图标 + 名称 + 数量」格式，照形态选择界面的行）：
            // 单形态=图标+名称+数量整体居中；多形态=图标 + 「选择形态」按钮（按钮文案含名称）；
            // 无物品=居中提示
            val variant = deliverVariant
            val multiVariant = variantGroups(entry.itemId).size > 1
            if (variant != null && !multiVariant) {
                // 名称完全显示（不截断——物品名本身不长，弹窗内空间足够）
                val name = variant.name.string
                val countStr = "×${variant.count}"
                // 整体居中：图标 16 + 间距 4 + 名称 + 间距 4 + 数量
                val totalW = 16 + 4 + textRenderer.getWidth(name) + 4 + textRenderer.getWidth(countStr)
                val startX = centerX - totalW / 2
                drawItemWithBar(context, variant, startX, dialogY + 44 + noteShift)
                context.drawTextWithShadow(textRenderer, name, startX + 20, dialogY + 48 + noteShift, 0xFFFFFF)
                context.drawTextWithShadow(textRenderer, countStr, startX + 20 + textRenderer.getWidth(name) + 4, dialogY + 48 + noteShift, 0xAAAAAA)
            } else if (variant != null) {
                // 多形态：图标画在「选择形态」按钮左侧（按钮 centerX-74，图标与按钮间隙 6px）
                drawItemWithBar(context, variant, centerX - 96, dialogY + 44 + noteShift)
            } else {
                context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.buy_order.variant_empty").formatted(Formatting.GRAY),
                    centerX, dialogY + 48 + noteShift, 0xAAAAAA)
            }
            // 件数/单价标签：紧贴对应输入框左缘（输入框 x = centerX-40；+4 = 文字中心对齐输入框中心）
            val countLabel = Text.translatable("cobblemarket.buy_order.deliver_count").string
            context.drawTextWithShadow(textRenderer,
                countLabel,
                centerX - 44 - textRenderer.getWidth(countLabel), dialogY + 66 + noteShift, 0xFFFFFF)
            val priceLabel2 = Text.translatable("cobblemarket.buy_order.deliver_price").string
            context.drawTextWithShadow(textRenderer,
                priceLabel2,
                centerX - 44 - textRenderer.getWidth(priceLabel2), dialogY + 88 + noteShift, 0xFFFFFF)
        }
    }

    private fun speciesDisplay(p: PokemonPreview): String {
        val t = Text.translatable(p.species).string
        return if (t == p.species) p.speciesName else t
    }

    /** 形态按钮文案：显示当前所选形态名（未选时仅提示文字） */
    private fun updateDeliverVariantButton() {
        val v = deliverVariant
        val label = if (v != null) {
            "${Text.translatable("cobblemarket.buy_order.variant_select_label").string}${com.shusheng.cobblemarket.util.TextUtil.truncateString(v.name.string, 46)}"
        } else {
            Text.translatable("cobblemarket.buy_order.variant_select").string
        }
        deliverVariantButton?.setMessage(Text.literal(label))
    }

    /** 打开上架选择界面（交付模式）：订单 id 静态暂存，选完精灵回传后恢复交付弹窗 */
    private fun openDeliverSelect() {
        val entry = deliverEntry ?: return
        pendingDeliverOrderId = entry.id
        stashReturnCurrency(currency)   // 选完精灵回来是新建实例，币种要带回去（否则 RP 单从列表消失）
        client?.setScreen(SellSelectScreen(entry.id))
    }

    private fun updateDeliverSelectButton() {
        val p = deliverSelectedPokemon
        val label = if (p != null) {
            "${Text.translatable("cobblemarket.buy_order.select_pokemon_label").string}${com.shusheng.cobblemarket.util.TextUtil.truncateString(speciesDisplay(p), 60)}"
        } else {
            Text.translatable("cobblemarket.buy_order.select_pokemon").string
        }
        deliverSelectButton?.setMessage(Text.literal(label))
    }

    /** 客户端本地预核对（服务端为最终权威）：物种/闪光/特训/IV 有效值/形态/特性/性格 */
    private fun clientMatchesPokemon(p: PokemonPreview, e: BuyOrderEntry): Boolean {
        if (e.type != "POKEMON") return false
        if (e.speciesId != null && e.speciesId != p.speciesId) return false
        if (e.shinyFilter != PokemonBlacklistEntry.SHINY_ANY && p.shiny != (e.shinyFilter == PokemonBlacklistEntry.SHINY_YES)) return false
        val hasHt = p.htHp >= 0 || p.htAtk >= 0 || p.htDef >= 0 || p.htSpAtk >= 0 || p.htSpDef >= 0 || p.htSpd >= 0
        if (e.htFilter != PokemonBlacklistEntry.HT_ANY && hasHt != (e.htFilter == PokemonBlacklistEntry.HT_ONLY)) return false
        fun effIvs(ht: Int, iv: Int) = if (ht >= 0) ht else iv
        if (e.ivHp >= 0 && effIvs(p.htHp, p.ivsHp) != e.ivHp) return false
        if (e.ivAtk >= 0 && effIvs(p.htAtk, p.ivsAtk) != e.ivAtk) return false
        if (e.ivDef >= 0 && effIvs(p.htDef, p.ivsDef) != e.ivDef) return false
        if (e.ivSpAtk >= 0 && effIvs(p.htSpAtk, p.ivsSpAtk) != e.ivSpAtk) return false
        if (e.ivSpDef >= 0 && effIvs(p.htSpDef, p.ivsSpDef) != e.ivSpDef) return false
        if (e.ivSpd >= 0 && effIvs(p.htSpd, p.ivsSpd) != e.ivSpd) return false
        if (PokemonBlacklistEntry.ALL_FORMS !in e.aspects) {
            val species = e.speciesId?.let { Identifier.tryParse(it)?.let { id -> PokemonSpecies.getByIdentifier(id) } }
            if (species != null) {
                val formAspectUnion = buildSet {
                    addAll(species.standardForm.aspects)
                    species.forms.forEach { addAll(it.aspects) }
                }
                if (e.aspects.isEmpty()) {
                    if (formAspectUnion.any { it in p.aspects }) return false
                } else if (!p.aspects.containsAll(e.aspects)) {
                    return false
                }
            }
            // 拿不到物种对象（数据包物种客户端不存在）：形态检查交由服务端把关
        }
        if (e.abilityKey != null && e.abilityKey != p.ability) return false
        if (e.natureKey != null && e.natureKey != p.nature) return false
        return true
    }

    private fun backpackCount(itemId: String): Int {
        val inv = client?.player?.inventory ?: return 0
        var total = 0
        for (i in 0 until inv.size()) {
            val stack = inv.getStack(i)
            if (!stack.isEmpty && Registries.ITEM.getId(stack.item).toString() == itemId) total += stack.count
        }
        return total
    }

    /** 背包中该 itemId 的形态分组：每组 = (示例栈, 数量)，按组件一致聚合 */
    private fun variantGroups(itemId: String): List<Pair<ItemStack, Int>> {
        val inv = client?.player?.inventory ?: return emptyList()
        val groups = mutableListOf<Pair<ItemStack, Int>>()
        for (i in 0 until inv.size()) {
            val stack = inv.getStack(i)
            if (stack.isEmpty || Registries.ITEM.getId(stack.item).toString() != itemId) continue
            val idx = groups.indexOfFirst { com.shusheng.cobblemarket.network.itemsEqualForTrading(it.first, stack) }
            if (idx >= 0) {
                groups[idx] = groups[idx].first to groups[idx].second + stack.count
            } else {
                // copy：背包栈是活引用，交付扣减会动它，示例栈必须独立
                groups.add(stack.copy() to stack.count)
            }
        }
        return groups
    }

    // 确认按钮保持可点：点击时逐项校验并给出明确红字反馈（禁用按钮点击无反应，玩家不知道原因）
    private fun updateDeliverConfirmActive() {
        deliverConfirmButton?.active = true
    }

    private fun confirmDeliver() {
        val entry = deliverEntry ?: return
        val price = deliverPriceField?.text?.toIntOrNull() ?: 0
        if (price !in entry.minPrice..entry.maxPrice) {
            client?.player?.sendMessage(Text.translatable("cobblemarket.buy_order.price_out_of_range").formatted(Formatting.RED), false)
            return
        }
        if (entry.type == "POKEMON") {
            val p = deliverSelectedPokemon
            if (p == null) {
                client?.player?.sendMessage(Text.translatable("cobblemarket.buy_order.not_selected").formatted(Formatting.RED), false)
                return
            }
            // 客户端预核对先拦并解释原因；服务端二次核对兜底（判定不一致时同样有红字反馈）
            if (!clientMatchesPokemon(p, entry)) {
                client?.player?.sendMessage(Text.translatable("cobblemarket.buy_order.not_matching").formatted(Formatting.RED), false)
                return
            }
            sendToServer(DeliverPokemonBuyOrderPayload(entry.id, p.uuid, price))
        } else {
            val count = deliverCountField?.text?.toIntOrNull() ?: 0
            val variantCount = deliverVariant?.count ?: 0
            if (count !in 1..entry.remainingCount || count > variantCount) {
                client?.player?.sendMessage(Text.translatable("cobblemarket.buy_order.wrong_count").formatted(Formatting.RED), false)
                return
            }
            // 所选形态序列化随包发送：服务端重建权威参考栈（不信任客户端），照上架路径
            val variantNbt = try {
                deliverVariant?.encode(client?.world?.registryManager) as? net.minecraft.nbt.NbtCompound
            } catch (e: Exception) {
                null
            }
            if (variantNbt == null) {
                client?.player?.sendMessage(Text.translatable("cobblemarket.buy_order.wrong_count").formatted(Formatting.RED), false)
                return
            }
            sendToServer(DeliverItemBuyOrderPayload(entry.id, count, price, variantNbt))
        }
    }

    private fun closeDialogs() {
        deliverEntry = null
        deliverSelectedPokemon = null
        deliverVariant = null
        deliverVariantButton = null
        deliverSelectButton = null
        deliverPriceField = null
        deliverCountField = null
        deliverConfirmButton = null
        reviewEntry = null
        reviewRenderable = null
        reviewIconOrigin = null
        reviewItemIconOrigin = null
        reviewReasonField = null
        reviewAcceptButton = null
        reviewRejectButton = null
        reviewCancelButton = null
        deliverCancelButton = null
        createSpeciesField = null
        createMinPriceField = null
        createMaxPriceField = null
        createNoteField = null
        createItemField = null
        createCountField = null
        for (i in 0..5) createIvFields[i] = null
        createShinyButton = null
        createHtButton = null
        speciesCandidates = listOf()
        speciesIndex = 0
        speciesPrevButton = null
        speciesNextButton = null
        createFormButton = null
        createAbilityButton = null
        createNatureButton = null
        createConfirmButton = null
        createCancelButton = null
        createCurrencyButton = null
        createTabButtons.clear()
        formListButtons.clear()
        abilityListButtons.clear()
        natureListButtons.clear()
        itemOptionButtons.clear()
        itemSelectButton = null
        previewRenderable = null
        previewSpecies = null
        clearChildren()
        init()
    }

    // ── 交互 ──

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 图标点击叫声：弹窗图标精确命中；**弹窗打开时不做整行触发** —— 整行判定只看 y，
        // 弹窗里精灵图标与输入框的 y 挨得近，会误把点输入框当成点图标
        // 先按图标矩形精确命中（弹窗里的图标照样能点），再考虑整行 —— 整行判定只看 y，
        // 弹窗里精灵图标与输入框挨得近，会误把点输入框当成点图标，所以弹窗时不整行触发
        if (button == 0 && !IconCryClick.playIconAt(mouseX, mouseY) && !anyDialogOpen()) {
            val rowL = rowLeftX()
            IconCryClick.playRowAt(mouseX, mouseY, rowL, rowL + rowW() - 50)
        }
        // 管理员模式：点击行弹下架确认（照 AdminAuctionScreen；弹窗打开时行点击由 anyDialogOpen 拦截下的空列表跳过）
        if (adminMode && forceCancelEntry == null) {
            val startY = getListStartY()
            val rowL = rowLeftX()
            // mouseX/mouseY 是 Double，不能用 in IntRange，显式比较
            if (mouseX >= rowL && mouseX < rowL + rowW() && mouseY >= startY) {
                val row = ((mouseY - startY) / rowHeight).toInt()
                val list = displayList()
                val actualIdx = scrollOffset + row
                if (actualIdx in list.indices) {
                    openForceCancelDialog(list[actualIdx])
                    return true
                }
            }
        }
        val result = super.mouseClicked(mouseX, mouseY, button)
        // 输入框失焦
        if (focused is TextFieldWidget && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        updateDeliverConfirmActive()
        return result
    }

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean {
        val fields = listOfNotNull(
            searchField,
            createSpeciesField, createMinPriceField, createMaxPriceField, createNoteField,
            createItemField, createCountField, deliverPriceField, deliverCountField,
            reviewReasonField
        ) + createIvFields.filterNotNull()
        return fields.any { it.isMouseOver(mouseX, mouseY) }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        // 强制下架弹窗开着时：滚轮只滚弹窗信息区（弹窗被屏幕压矮时用）
        val forceEntry = forceCancelEntry
        if (forceEntry != null) {
            val maxScroll = forceMaxScroll(forceEntry)
            if (maxScroll > 0) {
                forceScroll = (forceScroll - verticalAmount.toInt() * 10).coerceIn(0, maxScroll)
            }
            return true
        }
        // 审核弹窗（精灵单）开着时：滚轮只滚弹窗信息区（弹窗被屏幕压矮时用）
        val reviewing = reviewEntry
        if (reviewing != null && reviewing.type == "POKEMON") {
            val maxScroll = reviewMaxScroll(reviewing, reviewItemExtraRows)
            if (maxScroll > 0) {
                reviewScroll = (reviewScroll - verticalAmount.toInt() * 10).coerceIn(0, maxScroll)
            }
            return true
        }
        // 创建弹窗：形态列表展开时滚动列表
        if (formListOpen && formOptions.size > MAX_FORM_LIST_ROWS) {
            formListScroll = (formListScroll - verticalAmount.toInt())
                .coerceIn(0, formOptions.size - MAX_FORM_LIST_ROWS)
            rebuildFormList()
            return true
        }
        // 创建弹窗：性格列表展开时滚动列表
        if (natureListOpen && natureOptions.size > MAX_NATURE_LIST_ROWS) {
            natureListScroll = (natureListScroll - verticalAmount.toInt())
                .coerceIn(0, natureOptions.size - MAX_NATURE_LIST_ROWS)
            rebuildNatureList()
            return true
        }
        // 创建弹窗：物品匹配列表展开时滚动列表
        if (itemListOpen && matchedItems.size > MAX_ITEM_LIST_ROWS) {
            itemListScroll = (itemListScroll - verticalAmount.toInt())
                .coerceIn(0, matchedItems.size - MAX_ITEM_LIST_ROWS)
            rebuildItemList()
            return true
        }
        // 创建弹窗打开时：滚轮翻**表单**（只有弹窗被屏幕压矮、内容装不下才有得滚）
        // ⚠ 放在三个列表分支之后：列表展开时滚轮先给列表（那是原本的行为）
        if (createTabButtons.isNotEmpty()) {
            val maxScroll = createMaxScroll()
            if (maxScroll > 0) {
                val target = (createScroll - verticalAmount.toInt() * 14).coerceIn(0, maxScroll)
                if (target != createScroll) {
                    createScroll = target
                    layoutCreateForm()
                }
                return true
            }
        }
        val entry = deliverEntry
        if (entry == null && createTabButtons.isEmpty()) {
            scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
            rebuildRowButtons()
            return true
        }
        return true
    }

    // ── resize：弹窗打开时保存输入状态，重建后恢复 ──

    private data class CreateDialogSnapshot(
        val tab: Int,
        val shinyFilter: Int,
        val htFilter: Int,
        val formIndex: Int,
        val abilityIndex: Int,
        val natureIndex: Int,
        val selectedItemIndex: Int,
        /** 玩家在弹窗里选的币种：不存下来的话 resize 重建会回落到列表币种（选择被静默改掉） */
        val currency: MarketCurrency,
        val texts: Map<String, String>
    )

    private fun snapshotCreateDialog(): CreateDialogSnapshot {
        val texts = mutableMapOf<String, String>()
        createSpeciesField?.let { texts["species"] = it.text }
        createMinPriceField?.let { texts["min"] = it.text }
        createMaxPriceField?.let { texts["max"] = it.text }
        createItemField?.let { texts["item"] = it.text }
        createCountField?.let { texts["count"] = it.text }
        createNoteField?.let { texts["note"] = it.text }
        for (i in 0..5) createIvFields[i]?.let { texts["iv$i"] = it.text }
        return CreateDialogSnapshot(
            createTab, createShinyFilter, createHtFilter,
            formIndex, abilityIndex, natureIndex, selectedItemIndex, createCurrency, texts
        )
    }

    private fun restoreCreateDialog(snapshot: CreateDialogSnapshot) {
        openCreateDialog()
        createTab = snapshot.tab
        // 币种必须在 rebuild 之前恢复：openCreateDialog 会把它重置成「当前列表币种」，
        // 而按钮外观是在 rebuild 里按 createCurrency 建的
        createCurrency = snapshot.currency
        rebuildCreateDialogWidgets()
        createShinyFilter = snapshot.shinyFilter
        createHtFilter = snapshot.htFilter
        updateCreateShinyButton()
        updateCreateHtButton()
        // 物种输入 setter 会触发解析重建形态/特性选项
        snapshot.texts["species"]?.let { createSpeciesField?.text = it }
        snapshot.texts["min"]?.let { createMinPriceField?.text = it }
        snapshot.texts["max"]?.let { createMaxPriceField?.text = it }
        snapshot.texts["item"]?.let { createItemField?.text = it }
        snapshot.texts["count"]?.let { createCountField?.text = it }
        snapshot.texts["note"]?.let { createNoteField?.text = it }
        for (i in 0..5) snapshot.texts["iv$i"]?.let { createIvFields[i]?.text = it }
        // 恢复选择状态（物种解析已重建 formOptions/abilityOptions，物品解析已重建 matchedItems）
        formIndex = snapshot.formIndex.coerceIn(0, maxOf(0, formOptions.size - 1))
        createFormButton?.setMessage(formButtonText())
        refreshPreviewModel()
        abilityIndex = snapshot.abilityIndex.coerceIn(-1, abilityOptions.size - 1)
        updateAbilityButton()
        natureIndex = snapshot.natureIndex.coerceIn(-1, natureOptions.size - 1)
        updateNatureButton()
        selectedItemIndex = snapshot.selectedItemIndex.coerceIn(-1, matchedItems.size - 1)
        updateItemSelectButton()
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val wasCreating = createTabButtons.isNotEmpty() && deliverEntry == null
        val createSnapshot = if (wasCreating) snapshotCreateDialog() else null
        val wasDelivering = deliverEntry != null
        val savedEntry = deliverEntry
        val savedPokemon = deliverSelectedPokemon
        val savedPrice = deliverPriceField?.text ?: ""
        val savedCount = deliverCountField?.text ?: ""
        val wasReviewing = reviewEntry != null
        val savedReview = reviewEntry
        val savedReason = reviewReasonField?.text ?: ""
        val wasForceCancelling = forceCancelEntry != null
        val savedForceCancel = forceCancelEntry
        val savedSearch = searchField?.text ?: ""
        val savedScroll = scrollOffset
        super.resize(client, width, height)
        if (wasCreating && createSnapshot != null) {
            restoreCreateDialog(createSnapshot)
        } else if (wasDelivering && savedEntry != null) {
            deliverEntry = null
            openDeliverDialog(savedEntry)
            deliverSelectedPokemon = savedPokemon
            updateDeliverSelectButton()
            deliverPriceField?.text = savedPrice
            deliverCountField?.text = savedCount
            updateDeliverConfirmActive()
        } else if (wasReviewing && savedReview != null) {
            reviewEntry = null
            openReviewDialog(savedReview)
            reviewReasonField?.text = savedReason
        } else if (wasForceCancelling && savedForceCancel != null) {
            forceCancelEntry = null
            openForceCancelDialog(savedForceCancel)
        }
        // 主界面搜索文本恢复（弹窗分支下 searchField 被隐藏，恢复文本无害）；
        // text setter 会触发监听器把滚动归零，恢复后还原滚动位置
        searchField?.text = savedSearch
        rebuildDisplayList()
        scrollOffset = savedScroll.coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        rebuildRowButtons()
    }

    override fun shouldPause() = false

    override fun close() {
        closed = true
        super.close()
    }

    companion object {
        // 确认交付弹窗物品词条行数上限（附魔+属性行最多约此量级；超出截断「…」防异常 NBT 撑爆弹窗）
        private const val MAX_ITEM_EXTRA_ROWS = 12
        // 背景三段贴图左右边框实际像素 17（用户实测），内容与行背景统一避开此宽度
        // 横竖边框分离：2026-08-22 用户更换背景图，左右边框 17→8（行/搜索框/按钮向外扩 18px），上下仍 17
        // 内容区左右内缩：求购单专属面板纹理的边框视觉宽约 10~12px，8px 会盖住边框装饰（行/分割线/按钮统一 12）
        private const val PANEL_BORDER_X = 12
        private const val PANEL_BORDER_Y = 17
        private val BUY_ORDER_PANEL_TOP = Identifier.of("cobblemarket", "textures/gui/buy_order_panel_top.png")
        // 中间/底部与精灵市场共用（贴图内容一致，顶部专用）
        private val BUY_ORDER_PANEL_MIDDLE = Identifier.of("cobblemarket", "textures/gui/market_panel_middle.png")
        private val BUY_ORDER_PANEL_BOTTOM = Identifier.of("cobblemarket", "textures/gui/market_panel_bottom.png")
        // 精灵槽背景（行内与创建弹窗预览槽共用）
        private val POKEMON_SLOT_TEXTURE = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
        // 按钮贴图（40×80 = 普通/悬停两段），求购单界面所有按钮统一使用。
        // 2026-09-16：改用全项目通用的 button_9slice —— 原先的 buy_order_button 只是同一套按钮的
        // 另一个配色（实测差异仅约 21% 通道差、肉眼难辨），两份资源没必要，专属贴图已删
        private val BUY_ORDER_BUTTON_TEXTURE = Identifier.of("cobblemarket", "textures/gui/button_9slice.png")
        // 与 button_9slice 的总高一致（三段：普通 / 悬停 / 选中）
        private const val BUY_ORDER_BUTTON_TEX_H = 120
        // 创建弹窗形态列表最大可见行数（超出滚动，照黑名单添加框）
        private const val MAX_FORM_LIST_ROWS = 8
        /** 创建弹窗表单可视区的顶（相对弹窗顶）：标题与 tab 之下留一点，第一行控件在 48 */
        private const val CREATE_FORM_TOP = 44
        // 悬停面板备注换行宽度上限
        private const val MAX_TOOLTIP_W = 260
        // 性格列表最大可见行数（25 种滚动浏览，弹窗高度受限）
        private const val MAX_NATURE_LIST_ROWS = 6
        // 物品匹配列表最大可见行数
        private const val MAX_ITEM_LIST_ROWS = 6

        // 列表请求的最小间隔：服务端 `buy_order_list` 节流窗口 500ms + 100ms 余量
        // （窗口同宽时网络抖动会让请求恰好落进服务端窗口被静默丢弃，照 MarketScreen 的取值口径）
        private const val LIST_REQUEST_INTERVAL_MS = 600L

        // 交付流程跨界面传递（SellSelectScreen 交付模式 → 新建的 BuyOrderScreen）：
        // 界面切换时实例销毁，静态暂存订单 id 与所选精灵/物品形态
        var pendingDeliverOrderId: UUID? = null
        var pendingDeliverPokemon: PokemonPreview? = null
        var pendingDeliverVariant: ItemStack? = null

        /**
         * 交付流程往返时暂存的**列表币种**（同理 [pendingDeliverOrderId]）。
         *
         * 精灵选择 / 物品形态选择界面回来时是 `BuyOrderScreen()` **新建实例**（成员变量归零）：
         * 不暂存的话，卖家在 RP 视图下点交付、选完货回来就被踢回主货币列表 ——
         * 他刚在交付的那张 RP 单直接消失，得手动再切回去。
         */
        private var pendingReturnCurrency: MarketCurrency? = null

        /** 跳去选择界面前调用（见 [openDeliverSelect] 与交付弹窗的形态按钮） */
        fun stashReturnCurrency(currency: MarketCurrency) {
            pendingReturnCurrency = currency
        }

        /** 构造时**一次性消费**：其它入口（市场入口 / 聊天直达）拿到的仍是主货币 */
        private fun consumeReturnCurrency(): MarketCurrency =
            (pendingReturnCurrency ?: MarketCurrency.PRIMARY).also { pendingReturnCurrency = null }
    }
}
