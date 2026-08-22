package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
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
import java.util.UUID

/**
 * 求购单：玩家发布"我要买"订单（精灵/物品+单价区间），全服可见；
 * 卖家交付符合要求的货，按实际单价成交；买家「我的求购」tab 可关闭退冻结金。
 * 数据：全量 OPEN 快照 + 服务端增量事件（NEW/UPDATED/CLOSED）合并刷新。
 */
class BuyOrderScreen(
    private val initialTab: Int = 0,
    /** 管理员模式（管理面板「所有求购」入口）：无 tab/发布按钮，行按钮改为「下架」，点行弹强制下架确认 */
    private val adminMode: Boolean = false
) : Screen(Text.translatable("cobblemarket.buy_order.title")) {

    // 防串扰：界面关闭后到达的响应丢弃（照 SellSelectScreen）
    private var closed = false

    private val panelWidth = 296
    private val rowHeight = 24

    private var currentTab = initialTab.coerceIn(0, 1) // 0 = 全部, 1 = 我的
    private var entries = listOf<BuyOrderEntry>()
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
    private var createSpeciesField: TextFieldWidget? = null
    private var createMinPriceField: TextFieldWidget? = null
    private var createMaxPriceField: TextFieldWidget? = null
    private var createNoteField: TextFieldWidget? = null
    private var createItemField: TextFieldWidget? = null
    private var createCountField: TextFieldWidget? = null
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
    private var matchedItems = listOf<String>()
    private var selectedItemIndex = -1
    private var itemListOpen = false
    private var itemListScroll = 0
    private var itemSelectButton: NineSliceButton? = null
    private val itemOptionButtons = mutableListOf<NineSliceButton>()

    // ── 买家确认弹窗状态（处理待确认交付） ──
    private var reviewEntry: BuyOrderEntry? = null
    /** 管理员模式：强制下架确认弹窗的条目与按钮 */
    private var forceCancelEntry: BuyOrderEntry? = null
    private var forceCancelConfirmButton: NineSliceButton? = null
    private var forceCancelCancelButton: NineSliceButton? = null
    private var reviewReasonField: TextFieldWidget? = null
    private var reviewAcceptButton: NineSliceButton? = null
    private var reviewRejectButton: NineSliceButton? = null
    private var reviewCancelButton: NineSliceButton? = null

    // ── 交付对话框状态 ──
    private var deliverEntry: BuyOrderEntry? = null
    // 精灵交付：所选精灵（经 SellSelectScreen 交付模式选择后回传）
    private var deliverSelectedPokemon: PokemonPreview? = null
    private var deliverSelectButton: NineSliceButton? = null
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

    // 行首图标缓存（指定物种的精灵单渲染 3D 图标，照拍卖模式）
    private data class IconData(val displayName: String, val renderable: RenderablePokemon?, val state: FloatingState)
    private val iconData = mutableMapOf<Int, IconData>()
    private val iconSize = 20

    private fun cacheIcons() {
        iconData.clear()
        entries.forEachIndexed { index, entry ->
            if (entry.type != "POKEMON" || entry.speciesId == null) return@forEachIndexed
            val id = Identifier.tryParse(entry.speciesId) ?: return@forEachIndexed
            val species = PokemonSpecies.getByIdentifier(id) ?: return@forEachIndexed
            // 仅闪要求时叠加 shiny aspect 渲染闪光形态；其余用标准形态
            val aspects = mutableSetOf<String>()
            if (entry.shinyFilter == PokemonBlacklistEntry.SHINY_YES) aspects.add("shiny")
            val displayName = com.shusheng.cobblemarket.util.SpeciesText.displayName(species)
            iconData[index] = IconData(displayName, RenderablePokemon(species, aspects, ItemStack.EMPTY), FloatingState())
        }
    }

    private fun renderPokemonIcon(context: DrawContext, index: Int, x: Int, y: Int, size: Int, dark: Boolean = false) {
        val data = iconData[index] ?: return
        val renderable = data.renderable ?: return
        val matrices = context.matrices
        matrices.push()
        try {
            context.enableScissor(x - 1, y + 1, x + size + 2, y + size + 2)
            matrices.translate(x + size / 2.0, y + 1.0, 0.0)
            matrices.scale(size / 25f * 2.5f, size / 25f * 2.5f, 1f)
            drawProfilePokemon(
                renderablePokemon = renderable,
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

    private fun entryName(entry: BuyOrderEntry): String {
        if (entry.type == "ITEM") {
            val id = Identifier.tryParse(entry.itemId) ?: return entry.itemId
            val item = Registries.ITEM.get(id)
            val name = item.name.string
            return if (name == item.translationKey) id.path else name
        }
        val key = entry.speciesKey ?: return Text.translatable("cobblemarket.buy_order.any_pokemon").string
        val t = Text.translatable(key).string
        return if (t == key) key else t
    }

    private fun formatRemaining(endsAt: Long): String {
        val ms = endsAt - System.currentTimeMillis()
        if (ms <= 0) return Text.translatable("cobblemarket.buy_order.ending").string
        val totalSec = ms / 1000
        val totalMin = totalSec / 60
        if (totalMin < 1) return "${totalSec}s"
        if (totalMin < 60) return "${totalMin}m"
        val h = totalMin / 60
        val m = totalMin % 60
        if (h < 24) return "${h}h${m}m"
        val d = h / 24
        val hh = h % 24
        return "${d}d${hh}h"
    }

    // 行内价格区间用缩写（与物品市场网格同款 k/M/B）；完整价格见悬停面板
    private fun priceRangeText(entry: BuyOrderEntry): String =
        "${com.shusheng.cobblemarket.client.formatPriceShort(entry.minPrice)}~" +
        "${com.shusheng.cobblemarket.client.formatPriceShort(entry.maxPrice)}◆"

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
        ClientPlayNetworking.send(RequestBalancePayload())
        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        // 返回按钮：右缘避开背景右边框（PANEL_BORDER_X）、顶边 10px；求购单界面按钮统一用专属贴图
        backButton = NineSliceButton(
            width / 2 + panelWidth / 2 - PANEL_BORDER_X - 50, 10, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(if (adminMode) AdminScreen() else MarketEntryScreen()) },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(backButton)

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

            // 发布求购按钮（tab 行右侧，避开背景右边框）
            val createBtn = NineSliceButton(
                leftX + panelWidth - PANEL_BORDER_X - 28, 32, 28, 14,
                Text.literal("+"),
                { openCreateDialog() },
                texture = BUY_ORDER_BUTTON_TEXTURE,
                texH = BUY_ORDER_BUTTON_TEX_H
            )
            createBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.buy_order.create")))
            createButton = createBtn
            addDrawableChild(createBtn)
        }

        // 搜索框（y=48；与行区域同宽，避开背景左右边框；本地过滤——列表全量下发，照拍卖场模式）
        searchField = TextFieldWidget(textRenderer, rowLeftX(), 48, rowW(), 16, Text.translatable("cobblemarket.gui.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.search").formatted(Formatting.GRAY))
        // 本地过滤无网络请求，无需防抖；搜索变化重置滚动并重建行按钮
        searchField?.setChangedListener {
            scrollOffset = 0
            hoveredRow = -1
            rebuildRowButtons()
        }
        addSelectableChild(searchField)
        addDrawableChild(searchField)

        // 不重置 scrollOffset：交付弹窗关闭/resize 重建时保留浏览位置（switchTab 才显式归零）
        ClientPlayNetworking.send(RequestBuyOrderListPayload())
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
        scrollOffset = 0
        hoveredRow = -1
        updateTabButtons()
        rebuildRowButtons()
    }

    // ── 数据接收 ──

    fun onBuyOrderList(payload: BuyOrderListDataPayload) {
        entries = payload.entries
        cacheIcons()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        rebuildRowButtons()
        // 交付流程回传：从精灵选择界面返回后恢复交付弹窗并带入所选精灵
        val orderId = pendingDeliverOrderId ?: return
        pendingDeliverOrderId = null
        val order = entries.firstOrNull { it.id == orderId }
        if (order == null) {
            // 订单已结束（期间被关闭/凑满），丢弃所选精灵
            pendingDeliverPokemon = null
            return
        }
        val pokemon = pendingDeliverPokemon
        pendingDeliverPokemon = null
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
        cacheIcons()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        rebuildRowButtons()
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
        resultUntil = System.currentTimeMillis() + 3000
    }

    // ── 过滤与列表 ──

    private fun displayList(): List<BuyOrderEntry> {
        val query = searchField?.text?.trim()?.takeIf { it.isNotEmpty() }
        return entries.filter { entry ->
            // 管理员模式显示全部（无「我的」过滤）
            if (adminMode || currentTab == 0) true else isMine(entry)
        }.filter { entry ->
            // 搜索：物种名/物品名/买家名（本地过滤，照拍卖场模式）
            query == null || entryName(entry).contains(query, ignoreCase = true) ||
                entry.buyerName.contains(query, ignoreCase = true)
        }.sortedByDescending { it.createdAt }
    }

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
            // 行按钮语义：我的+待确认=处理交付；我的=关闭；他人+待确认=待确认（锁定）；他人=交付
            val btn = NineSliceButton(
                rowLeftX() + rowW() - 50, y + 4, 44, 16,
                Text.translatable(when {
                    mine && hasPending -> "cobblemarket.buy_order.review"
                    mine -> "cobblemarket.buy_order.close"
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
        ClientPlayNetworking.send(CancelBuyOrderPayload(entry.id))
    }

    /** 任意弹窗打开中（创建/交付/审查/强制下架）——所有「弹窗状态检查点」统一走这里，新弹窗只加一处 */
    private fun anyDialogOpen(): Boolean =
        createTabButtons.isNotEmpty() || deliverEntry != null || reviewEntry != null || forceCancelEntry != null

    // ── 管理员强制下架确认弹窗（照 AdminAuctionScreen 下架弹窗模板） ──

    private fun openForceCancelDialog(entry: BuyOrderEntry) {
        forceCancelEntry = entry
        // 隐藏下层控件（弹窗打开期间不可交互；closeForceCancelDialog 的 init 重建会恢复）
        searchField?.visible = false
        backButton?.visible = false
        rowButtons.forEach { it.visible = false }
        val centerX = width / 2
        val dialogY = height / 2 - 85

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderForceCancelBackground(context)
            }
        })

        forceCancelConfirmButton = NineSliceButton(
            centerX - 60, dialogY + 148, 56, 20,
            Text.translatable("cobblemarket.buy_order.force_cancel"),
            { confirmForceCancel() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(forceCancelConfirmButton)
        forceCancelCancelButton = NineSliceButton(
            centerX + 4, dialogY + 148, 56, 20,
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
        ClientPlayNetworking.send(ForceCancelBuyOrderPayload(entry.id))
        closeForceCancelDialog()
    }

    private fun handleForceCancelDialogClick(mx: Int, my: Int) {
        val centerX = width / 2
        val dialogY = height / 2 - 85
        val btnW = 56
        val btnH = 20
        val btnY = dialogY + 148
        val confirmX = centerX - 60
        val cancelX = centerX + 4
        if (mx in confirmX..(confirmX + btnW) && my in btnY..(btnY + btnH)) {
            confirmForceCancel()
        } else if (mx in cancelX..(cancelX + btnW) && my in btnY..(btnY + btnH)) {
            closeForceCancelDialog()
        }
    }

    private fun renderForceCancelBackground(context: DrawContext) {
        val entry = forceCancelEntry ?: return
        val centerX = width / 2
        val dialogW = 220
        val dialogH = 190
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        context.fill(0, 0, width, height, 0xC0000000.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.buy_order.force_cancel_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)
        context.drawCenteredTextWithShadow(textRenderer,
            entryName(entry),
            centerX, dialogY + 34, 0xFFFFFF)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.buy_order.force_cancel_hint1").formatted(Formatting.GRAY),
            centerX, dialogY + 62, 0xFFFFFF)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.buy_order.force_cancel_hint2").formatted(Formatting.GRAY),
            centerX, dialogY + 78, 0xFFFFFF)
        if (entry.pending != null) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.buy_order.force_cancel_hint3").formatted(Formatting.RED),
                centerX, dialogY + 98, 0xFFFFFF)
        }
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
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
                Text.translatable("cobblemarket.gui.balance", balText).string,
                leftX + PANEL_BORDER_X + 2, 20, 0x55FFFF)
        }

        // 操作结果提示
        if (resultMsg != null) {
            if (System.currentTimeMillis() > resultUntil) {
                resultMsg = null
            } else {
                // height-52：面板底部边框带（height-48 起）上方 4px，避免提示文字与边框重合
                context.drawCenteredTextWithShadow(textRenderer, resultMsg!!, centerX, height - 52, 0x55FF55)
            }
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
        displayList.drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, entry ->
            val y = startY + i * rowHeight
            val origIndex = entries.indexOf(entry)
            val slotX = rowL + 2
            val slotY = y + 2

            // 行首图标槽（照精灵市场）：精灵单 3D 渲染（弹窗打开时不渲染——模型层在衬底之上，压暗仍会浮在弹窗背景上；
            // 任意精灵画 ?），物品单画物品图标（弹窗打开时隐藏）
            if (entry.type == "POKEMON") {
                val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
                context.matrices.push()
                context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
                context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
                context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
                context.matrices.pop()
                if (iconData.containsKey(origIndex)) {
                    if (!anyDialogOpen) renderPokemonIcon(context, origIndex, slotX, slotY, iconSize)
                } else {
                    context.drawCenteredTextWithShadow(textRenderer, "?", slotX + iconSize / 2, slotY + 6, 0xAAAAAA)
                }
            } else if (!anyDialogOpen) {
                Identifier.tryParse(entry.itemId)?.let { id ->
                    context.drawItem(ItemStack(Registries.ITEM.get(id)), slotX + 2, slotY)
                }
            }

            // 名称（属性色，照精灵市场）+ 条件徽章（★/☆/HT）
            val nameColor = if (entry.type == "POKEMON") {
                val species = entry.speciesId?.let { Identifier.tryParse(it)?.let { id -> PokemonSpecies.getByIdentifier(id) } }
                species?.let { typeColor("cobblemon.type.${it.primaryType.name.lowercase()}") } ?: 0xFFFFFF
            } else 0xFFFFFF
            val name = com.shusheng.cobblemarket.util.TextUtil.truncateString(entryName(entry), if (entry.type == "ITEM") 46 else 36)
            context.drawTextWithShadow(textRenderer, name, rowL + 40, y + 7, nameColor)
            var sx = rowL + 40 + textRenderer.getWidth(name)
            conditionBadges(entry).forEach { (badge, color) ->
                context.drawText(textRenderer, badge, sx + 2, y + 7, color, false)
                sx += 2 + textRenderer.getWidth(badge)
            }
            // 行内不显示备注（空间不足几字截断无意义），完整留言见悬停面板/交付弹窗

            // 买家头像 + 剩余时间（左移，给右侧数量+价格区间让位，避免与长价格重合）
            drawSellerAvatar(context, entry.buyerUuid, entry.buyerName, rowL + 96, y + 4, 16)
            val remaining = formatRemaining(entry.expiresAt)
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
                val rightText = if (countStr.isEmpty()) priceRangeText(entry) else "$countStr  ${priceRangeText(entry)}"
                // 管理员模式无行按钮，价格区间贴右缘；普通模式给行按钮留位
                val btnLeft = if (adminMode) rowL + rowW() - 4 else rowL + rowW() - 50
                context.drawTextWithShadow(textRenderer, rightText, btnLeft - 4 - textRenderer.getWidth(rightText), y + 7, 0x55FFFF)
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
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(panelWidth / 320f, 0.5f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 320, 32, 320, 32)
        context.matrices.pop()
    }

    private fun drawSliceStretched(context: DrawContext, texture: Identifier, x: Int, y: Int, h: Int) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(panelWidth / 320f, h / 32f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 320, 32, 320, 32)
        context.matrices.pop()
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 前景（标题/余额/行内容/tooltip）全部在 renderBackground 绘制（早于弹窗遮罩），
        // 这里只渲染 children（弹窗遮罩/背景 Drawable 与各控件）
        super.render(context, mouseX, mouseY, delta)
    }

    // 悬停信息面板：照精灵市场 tooltip 结构（自绘九宫格面板 + 固定行高，信息同市场列表）
    private fun renderTooltip(context: DrawContext, entry: BuyOrderEntry, mouseX: Int, mouseY: Int) {
        val w = 0xFFFFFF
        val ivColors = intArrayOf(0x66FF66, 0xFF6666, 0xFFCC66, 0x6699FF, 0x66FF99, 0xFF99FF)

        val lines = mutableListOf<Pair<net.minecraft.text.OrderedText?, Int>>() // null = 分割线行
        lines.add(Text.literal(entryName(entry)).asOrderedText() to w)
        if (entry.type == "POKEMON") {
            // 条件行：闪光用星星图标（★ 金 = 仅闪，☆ 灰 = 仅非闪，不限不显示）+ 特训
            // （filter_ht_any 自带「特训：」前缀，不能叠加 tooltip_ht 标签；此处统一用不带前缀的三态文案）
            val htLabel = when (entry.htFilter) {
                PokemonBlacklistEntry.HT_ONLY -> Text.translatable("cobblemarket.gui.filter_ht_on").string
                PokemonBlacklistEntry.HT_NONE -> Text.translatable("cobblemarket.gui.filter_ht_off").string
                else -> Text.translatable("cobblemarket.buy_order.any").string
            }
            val condLine = Text.literal("")
            when (entry.shinyFilter) {
                PokemonBlacklistEntry.SHINY_YES -> condLine.append(Text.literal("★").formatted(Formatting.GOLD))
                PokemonBlacklistEntry.SHINY_NO -> condLine.append(Text.literal("☆").formatted(Formatting.GRAY))
            }
            condLine.append(Text.literal("  ${Text.translatable("cobblemarket.buy_order.tooltip_ht").string} $htLabel"))
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
            val hp = Text.translatable("cobblemon.stat.hp.name").string
            val atk = Text.translatable("cobblemon.stat.attack.name").string
            val def = Text.translatable("cobblemon.stat.defence.name").string
            val spa = Text.translatable("cobblemon.stat.special_attack.name").string
            val spd = Text.translatable("cobblemon.stat.special_defence.name").string
            val spe = Text.translatable("cobblemon.stat.speed.name").string
            lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs").asOrderedText() to w)
            fun ivReq(v: Int) = if (v < 0) "—" else v.toString()
            lines.add(Text.literal("  $hp:${ivReq(entry.ivHp)}").asOrderedText() to ivColors[0])
            lines.add(Text.literal("  $atk:${ivReq(entry.ivAtk)}").asOrderedText() to ivColors[1])
            lines.add(Text.literal("  $def:${ivReq(entry.ivDef)}").asOrderedText() to ivColors[2])
            lines.add(Text.literal("  $spa:${ivReq(entry.ivSpAtk)}").asOrderedText() to ivColors[3])
            lines.add(Text.literal("  $spd:${ivReq(entry.ivSpDef)}").asOrderedText() to ivColors[4])
            lines.add(Text.literal("  $spe:${ivReq(entry.ivSpd)}").asOrderedText() to ivColors[5])
        }
        // 数量（独立行，仅物品单；精灵单固定 1 只不显示）
        if (entry.type == "ITEM") {
            val qty = if (entry.remainingCount < entry.totalCount)
                "${entry.remainingCount}/${entry.totalCount}" else entry.totalCount.toString()
            lines.add(Text.literal("${Text.translatable("cobblemarket.buy_order.tooltip_quantity").string} $qty").asOrderedText() to w)
        }
        // 价格区间（独立行，照精灵市场价格行的灰色标签格式）
        lines.add(Text.literal(
            "${Text.translatable("cobblemarket.buy_order.tooltip_price").formatted(Formatting.GRAY).string} " +
            "${com.shusheng.cobblemarket.client.formatPrice(entry.minPrice)}~${com.shusheng.cobblemarket.client.formatPrice(entry.maxPrice)}◆"
        ).asOrderedText() to w)
        // 买家留言区块：分割线夹多行内容（照拍卖规则面板样式），无备注不显示
        if (entry.note.isNotEmpty()) {
            lines.add(null to w)
            val noteLabel = Text.literal(Text.translatable("cobblemarket.buy_order.tooltip_note").string)
                .append(Text.literal(entry.note))
            textRenderer.wrapLines(noteLabel, MAX_TOOLTIP_W).forEach { lines.add(it to 0xFFDD99) }
            lines.add(null to w)
        }
        // 买家 + 到期（照精灵市场卖家/价格行的灰标签格式）
        lines.add(Text.literal("${Text.translatable("cobblemarket.buy_order.tooltip_buyer").formatted(Formatting.GRAY).string} ${entry.buyerName}").asOrderedText() to w)
        lines.add(Text.literal("${Text.translatable("cobblemarket.buy_order.tooltip_expires").formatted(Formatting.GRAY).string} ${formatRemaining(entry.expiresAt)}").asOrderedText() to w)

        var maxWidth = 0
        lines.forEach { (line, _) -> line?.let { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it)) } }

        val padding = 4
        val tx = minOf(mouseX + 12, width - maxWidth - 12)
        val tooltipHeight = lines.size * 10 + padding
        val tyAbove = mouseY - tooltipHeight - 4
        val ty = if (tyAbove <= 0) minOf(mouseY + 12, height - tooltipHeight) else tyAbove

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

    private fun getSellerSkin(uuid: UUID, name: String): Identifier {
        client?.networkHandler?.getPlayerListEntry(uuid)?.skinTextures?.texture()?.let { return it }
        return client?.skinProvider?.getSkinTextures(com.mojang.authlib.GameProfile(uuid, name))?.texture() ?: defaultSkinTexture
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
        tabButtons.forEach { it.visible = false }
        rowButtons.forEach { it.visible = false }

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderCreateDialogBackground(context)
            }
        })

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
        itemSelectButton?.let { remove(it) }
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

        createConfirmButton = NineSliceButton(
            centerX - 85, dialogY + confirmY(), 80, 20,
            Text.translatable("cobblemarket.buy_order.create_confirm"),
            { confirmCreate() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(createConfirmButton)
        createCancelButton = NineSliceButton(
            centerX + 5, dialogY + confirmY(), 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeDialogs() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(createCancelButton)
    }

    private fun createDialogY(): Int = if (createTab == 0) height / 2 - 138 else height / 2 - 98

    private fun confirmY(): Int = if (createTab == 0) 230 else 160

    private fun buildPokemonCreateWidgets(dialogY: Int) {
        val centerX = width / 2

        // 闪光三态按钮（物种输入框左侧，照黑名单添加框）
        createShinyButton = NineSliceButton(
            centerX - 104, dialogY + 48, 20, 16,
            Text.literal(""),
            { cycleCreateShiny() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(createShinyButton)
        updateCreateShinyButton()

        createSpeciesField = TextFieldWidget(textRenderer, centerX - 82, dialogY + 48, 148, 16, Text.literal(""))
        createSpeciesField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_species").formatted(Formatting.GRAY))
        createSpeciesField?.setChangedListener { updatePokemonPreview(it) }
        addDrawableChild(createSpeciesField)

        // 形态选择按钮：只有解析出多形态物种时显示，点击展开/收起形态列表
        createFormButton = NineSliceButton(
            centerX - 82, dialogY + 66, 148, 14,
            Text.literal(""),
            { toggleFormList() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        createFormButton?.visible = false
        addDrawableChild(createFormButton)

        // 特训三态按钮
        createHtButton = NineSliceButton(
            centerX - 82, dialogY + 84, 148, 14,
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
                textRenderer, centerX - 75 + col * 52, dialogY + 102 + row * 18, 46, 16, Text.literal("")
            )
            field.setPlaceholder(Text.literal(ivPlaceholders[i]))
            field.setTextPredicate { it.length <= 2 && it.all { c -> c.isDigit() } }
            createIvFields[i] = field
            addDrawableChild(field)
        }

        // 特性按钮：物种解析后列出其可用特性点选（留空/未解析 = 不限）
        createAbilityButton = NineSliceButton(
            centerX - 82, dialogY + 138, 148, 14,
            Text.literal(""),
            { toggleAbilityList() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(createAbilityButton)
        updateAbilityButton()

        // 性格按钮：25 种性格固定列表点选
        createNatureButton = NineSliceButton(
            centerX - 82, dialogY + 156, 148, 14,
            Text.literal(""),
            { toggleNatureList() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(createNatureButton)
        updateNatureButton()

        // 单价区间（并排，placeholder 短不溢出）
        createMinPriceField = TextFieldWidget(textRenderer, centerX - 75, dialogY + 174, 74, 16, Text.literal(""))
        createMinPriceField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_min_price").formatted(Formatting.GRAY))
        createMinPriceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addDrawableChild(createMinPriceField)
        createMaxPriceField = TextFieldWidget(textRenderer, centerX + 3, dialogY + 174, 74, 16, Text.literal(""))
        createMaxPriceField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_max_price").formatted(Formatting.GRAY))
        createMaxPriceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addDrawableChild(createMaxPriceField)

        // 备注（额外需求提醒卖家，选填）
        createNoteField = TextFieldWidget(textRenderer, centerX - 82, dialogY + 194, 148, 16, Text.literal(""))
        createNoteField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_note").formatted(Formatting.GRAY))
        createNoteField?.setMaxLength(100)
        addDrawableChild(createNoteField)
    }

    private fun buildItemCreateWidgets(dialogY: Int) {
        val centerX = width / 2

        createItemField = TextFieldWidget(textRenderer, centerX - 82, dialogY + 48, 148, 16, Text.literal(""))
        createItemField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_item").formatted(Formatting.GRAY))
        createItemField?.setChangedListener { updateItemPreview(it) }
        addDrawableChild(createItemField)

        // 物品选择按钮：模糊匹配列表点选（照黑名单物品对话框）
        itemSelectButton = NineSliceButton(
            centerX - 82, dialogY + 66, 148, 14,
            Text.literal(""),
            { toggleItemList() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        itemSelectButton?.visible = false
        addDrawableChild(itemSelectButton)

        createCountField = TextFieldWidget(textRenderer, centerX - 82, dialogY + 84, 148, 16, Text.literal(""))
        createCountField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_count").formatted(Formatting.GRAY))
        createCountField?.setTextPredicate { it.length <= 4 && it.all { c -> c.isDigit() } }
        addDrawableChild(createCountField)

        createMinPriceField = TextFieldWidget(textRenderer, centerX - 75, dialogY + 106, 74, 16, Text.literal(""))
        createMinPriceField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_min_price").formatted(Formatting.GRAY))
        createMinPriceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addDrawableChild(createMinPriceField)
        createMaxPriceField = TextFieldWidget(textRenderer, centerX + 3, dialogY + 106, 74, 16, Text.literal(""))
        createMaxPriceField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_max_price").formatted(Formatting.GRAY))
        createMaxPriceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addDrawableChild(createMaxPriceField)

        // 备注（额外需求提醒卖家，选填）
        createNoteField = TextFieldWidget(textRenderer, centerX - 82, dialogY + 126, 148, 16, Text.literal(""))
        createNoteField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.create_note").formatted(Formatting.GRAY))
        createNoteField?.setMaxLength(100)
        addDrawableChild(createNoteField)
    }

    private fun renderCreateDialogBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogW = 280
        val dialogH = if (createTab == 0) 276 else 196
        val dialogX = centerX - dialogW / 2
        val dialogY = createDialogY()

        context.fill(0, 0, width, height, 0xC0000000.toInt())
        // 不透明衬底：弹窗背景贴图中间区域半透明，下层行内容（精灵图标/数量/价格）会透过
        context.fill(dialogX, dialogY, dialogX + dialogW, dialogY + dialogH, 0xFF2A2A2A.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.buy_order.create_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        if (createTab == 0) {
            // 精灵预览槽（右侧）
            val slotSize = 28
            val slotX = centerX + 66
            val slotY = dialogY + 42
            val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
            context.matrices.push()
            context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
            context.matrices.scale(slotSize / 66f, slotSize / 66f, 1f)
            context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
            context.matrices.pop()
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
            // 冻结提示：发布时冻结 maxPrice × 1（性格列表展开时隐藏——提示在列表覆盖区内）
            if (!natureListOpen) {
                context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.buy_order.frozen_hint",
                        (createMaxPriceField?.text?.toIntOrNull() ?: 0).toLong(),
                        com.shusheng.cobblemarket.client.displayActiveCurrency()).string,
                    centerX, dialogY + 214, 0xAAAAAA)
            }
        } else {
            // 物品预览图标（右侧，优先显示点选项）
            val previewId = matchedItems.getOrNull(selectedItemIndex) ?: matchedItems.firstOrNull()
            previewId?.let { idStr ->
                Identifier.tryParse(idStr)?.let { id ->
                    val item = Registries.ITEM.get(id)
                    if (item != Registries.ITEM.get(Identifier.of("minecraft", "air"))) {
                        context.drawItem(ItemStack(item), centerX + 72, dialogY + 44)
                    }
                }
            }
            // 冻结提示：发布时冻结 maxPrice × 件数（物品匹配列表展开时隐藏——提示在列表覆盖区内）
            if (!itemListOpen) {
                val maxPrice = createMaxPriceField?.text?.toIntOrNull() ?: 0
                val count = createCountField?.text?.toIntOrNull() ?: 0
                context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.buy_order.frozen_hint", maxPrice.toLong() * count,
                        com.shusheng.cobblemarket.client.displayActiveCurrency()).string,
                    centerX, dialogY + 146, 0xAAAAAA)
            }
        }
    }

    private fun confirmCreate() {
        val minPrice = createMinPriceField?.text?.toIntOrNull() ?: 0
        val maxPrice = createMaxPriceField?.text?.toIntOrNull() ?: 0
        if (minPrice < 1 || maxPrice < minPrice) {
            resultMsg = Text.translatable("cobblemarket.buy_order.invalid_price").string
            resultUntil = System.currentTimeMillis() + 3000
            return
        }
        if (createTab == 0) {
            // 物种：优先发送客户端本地解析出的物种 ID（中文输入在客户端解析，服务端只有英文环境）
            val input = createSpeciesField?.text?.trim().orEmpty()
            val speciesInput = previewSpecies?.resourceIdentifier?.toString() ?: input
            ClientPlayNetworking.send(CreatePokemonBuyOrderPayload(
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
                note = createNoteField?.text?.trim().orEmpty()
            ))
        } else {
            // 优先发送点选项；未点选时取自动匹配首个；无匹配则报错
            val itemId = matchedItems.getOrNull(selectedItemIndex)
                ?: matchedItems.firstOrNull()
                ?: resolveMatchingItems(createItemField?.text.orEmpty()).firstOrNull()
            val count = createCountField?.text?.toIntOrNull() ?: 0
            if (itemId == null) {
                resultMsg = Text.translatable("cobblemarket.buy_order.item_not_found").string
                resultUntil = System.currentTimeMillis() + 3000
                return
            }
            if (count < 1) {
                resultMsg = Text.translatable("cobblemarket.buy_order.invalid_price").string
                resultUntil = System.currentTimeMillis() + 3000
                return
            }
            ClientPlayNetworking.send(CreateItemBuyOrderPayload(itemId, count, minPrice, maxPrice, createNoteField?.text?.trim().orEmpty()))
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
        previewRenderable = null
        previewSpecies = null
        formOptions = listOf()
        formIndex = 0
        formListOpen = false
        abilityListOpen = false
        natureListOpen = false
        rebuildFormList()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            createFormButton?.visible = false
            abilityOptions = listOf()
            abilityIndex = -1
            updateAbilityButton()
            refreshListVisibility()
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
            species.forms.forEach { f ->
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

    private fun resolveByChineseName(text: String): com.cobblemon.mod.common.pokemon.Species? {
        val trimmed = text.trim()
        val lower = trimmed.lowercase().replace(" ", "_")
        val all = PokemonSpecies.implemented
        all.firstOrNull { s ->
            s.showdownId() == lower || s.name == lower
        }?.let { return it }
        all.firstOrNull { s ->
            val translated = s.translatedName.string
            translated == trimmed || translated.contains(trimmed)
        }?.let { return it }
        return null
    }

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
            "${Text.translatable("cobblemarket.blacklist.form").string}: ${formOptions.getOrNull(formIndex)?.label ?: ""}",
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
        return "${Text.translatable("cobblemarket.buy_order.ability_label").string}: ${com.shusheng.cobblemarket.util.TextUtil.truncateString(label, 90)}"
    }

    private fun updateAbilityButton() {
        createAbilityButton?.setMessage(Text.literal(abilityButtonText()))
    }

    private fun natureButtonText(): String {
        val sel = natureOptions.getOrNull(natureIndex)?.second
        val label = sel ?: Text.translatable("cobblemarket.buy_order.any").string
        return "${Text.translatable("cobblemarket.buy_order.nature_label").string}: $label"
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
                centerX - 82, dialogY + 82 + i * 14, 148, 14,
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
            centerX - 82, dialogY + 140, 148, 14,
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
                centerX - 82, dialogY + 154 + i * 14, 148, 14,
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
            centerX - 82, dialogY + 158, 148, 14,
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
                centerX - 82, dialogY + 172 + i * 14, 148, 14,
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
    private fun resolveMatchingItems(input: String): List<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.contains(":")) {
            // 显式 ID：唯一结果（无论注册表是否存在，服务端会校验）
            return listOf(trimmed)
        }
        val lower = trimmed.lowercase().replace(" ", "_")
        val result = LinkedHashSet<String>()
        // cobblemon / minecraft 前缀优先（无命名空间的常用物品 ID）
        listOf("cobblemon", "minecraft").forEach { ns ->
            Identifier.tryParse("$ns:$lower")?.let { id ->
                val item = Registries.ITEM.get(id)
                if (item != Registries.ITEM.get(Identifier.of("minecraft", "air"))) result.add(id.toString())
            }
        }
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

    private fun itemDisplay(itemId: String): String {
        val id = Identifier.tryParse(itemId) ?: return itemId
        val item = Registries.ITEM.get(id)
        val name = item.name.string
        // 无翻译的物品（第三方模组缺 lang）会显示翻译 key 原文（超长难读），fallback 到资源路径
        return if (name == item.translationKey) id.path else name
    }

    /** 物品输入变更：重建匹配列表（照黑名单物品对话框） */
    private fun updateItemPreview(text: String) {
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
        val label = matchedItems.getOrNull(selectedItemIndex)?.let { itemDisplay(it) }
            ?: if (matchedItems.size > 1)
                Text.translatable("cobblemarket.buy_order.item_matches", matchedItems.size).string
            else ""
        itemSelectButton?.setMessage(Text.literal(
            com.shusheng.cobblemarket.util.TextUtil.truncateString(
                "${Text.translatable("cobblemarket.buy_order.item_label").string}: $label",
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
        matchedItems.drop(itemListScroll).take(MAX_ITEM_LIST_ROWS).forEachIndexed { i, itemId ->
            val idx = itemListScroll + i
            val label = com.shusheng.cobblemarket.util.TextUtil.truncateString(itemDisplay(itemId), 124)
            val btn = NineSliceButton(
                centerX - 82, dialogY + 66 + i * 14, 148, 14,
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

    private fun openReviewDialog(entry: BuyOrderEntry) {
        reviewEntry = entry
        // 隐藏主界面控件
        backButton?.visible = false
        createButton?.visible = false
        searchField?.visible = false
        tabButtons.forEach { it.visible = false }
        rowButtons.forEach { it.visible = false }

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderReviewDialogBackground(context)
            }
        })

        val centerX = width / 2
        val dialogY = if (entry.type == "POKEMON") height / 2 - 110 else height / 2 - 95
        val btnY = if (entry.type == "POKEMON") dialogY + 180 else dialogY + 104

        // 拒绝原因输入框（选填，发给卖家）
        reviewReasonField = TextFieldWidget(textRenderer, centerX - 120, dialogY + if (entry.type == "POKEMON") 154 else 76, 240, 16, Text.literal(""))
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

    private fun renderReviewDialogBackground(context: DrawContext) {
        val entry = reviewEntry ?: return
        val pending = entry.pending ?: return
        val centerX = width / 2
        val dialogW = 280
        // 精灵单：完整信息行（照市场确认弹窗），弹窗更高；物品单：简洁布局
        val dialogH = if (entry.type == "POKEMON") 220 else 190
        val dialogX = centerX - dialogW / 2
        val dialogY = if (entry.type == "POKEMON") height / 2 - 110 else height / 2 - 95

        context.fill(0, 0, width, height, 0xC0000000.toInt())
        // 不透明衬底：弹窗背景贴图中间区域半透明，下层行内容（精灵图标/数量/价格）会透过
        context.fill(dialogX, dialogY, dialogX + dialogW, dialogY + dialogH, 0xFF2A2A2A.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.buy_order.review_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        if (entry.type == "POKEMON") {
            // 完整精灵信息行（与市场购买确认弹窗同一渲染器：名字★Lv/类型/性格特性/携带物/IVs 六行/卖家/价格）
            val listing = pendingToListing(entry, pending)
            val name = pending.speciesKey?.let {
                val t = Text.translatable(it).string
                if (t == it) pending.extraData["speciesName"] ?: it else t
            } ?: "?"
            EntryBadgeRenderer.drawInfoLines(
                context, listing,
                EntryBadgeRenderer.nameWithShinyStar(name, pending.shiny),
                centerX, dialogY + 22
            )
        } else {
            val itemName = itemDisplay(entry.itemId)
            context.drawCenteredTextWithShadow(textRenderer,
                "$itemName ×${pending.count}",
                centerX, dialogY + 28, 0xFFFFFF)
            // 卖家 + 出价
            context.drawCenteredTextWithShadow(textRenderer,
                "${Text.translatable("cobblemarket.buy_order.review_seller").string}${pending.sellerName}  " +
                "${Text.translatable("cobblemarket.buy_order.review_price").string}${com.shusheng.cobblemarket.client.formatPrice(pending.price)}${com.shusheng.cobblemarket.client.displayActiveCurrency()}",
                centerX, dialogY + 58, 0x55FFFF)
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
            nature = d["nature"] ?: "",
            natureBase = d["natureBase"] ?: d["nature"] ?: "",
            ability = d["ability"] ?: "",
            gender = d["gender"] ?: "",
            ball = d["ball"] ?: "",
            ballItem = d["ballItem"] ?: "",
            heldItemId = d["heldItemId"] ?: "",
            currencyName = "",
            aspects = (d["aspects"] ?: "").split(",").filter { it.isNotEmpty() }
        )
    }

    private fun acceptReview() {
        val entry = reviewEntry ?: return
        val pending = entry.pending ?: return
        ClientPlayNetworking.send(AcceptPendingDeliverPayload(entry.id, pending.id))
    }

    private fun rejectReview() {
        val entry = reviewEntry ?: return
        val pending = entry.pending ?: return
        ClientPlayNetworking.send(RejectPendingDeliverPayload(entry.id, pending.id, reviewReasonField?.text?.trim().orEmpty()))
    }

    private fun openDeliverDialog(entry: BuyOrderEntry) {
        deliverEntry = entry
        deliverSelectedPokemon = null

        // 隐藏主界面控件
        backButton?.visible = false
        createButton?.visible = false
        searchField?.visible = false
        tabButtons.forEach { it.visible = false }
        rowButtons.forEach { it.visible = false }

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderDeliverDialogBackground(context)
            }
        })

        val centerX = width / 2
        val dialogY = deliverDialogY()

        if (entry.type == "POKEMON") {
            // 选择精灵按钮：打开上架选择界面（交付模式），选完回传本弹窗
            deliverSelectButton = NineSliceButton(
                centerX - 82, dialogY + 46, 148, 16,
                Text.literal(""),
                { openDeliverSelect() },
                texture = BUY_ORDER_BUTTON_TEXTURE,
                texH = BUY_ORDER_BUTTON_TEX_H
            )
            addDrawableChild(deliverSelectButton)
            updateDeliverSelectButton()

            deliverPriceField = TextFieldWidget(textRenderer, centerX - 40, dialogY + 84, 80, 16, Text.literal(""))
            deliverPriceField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.deliver_price").formatted(Formatting.GRAY))
            deliverPriceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
            deliverPriceField?.setChangedListener { updateDeliverConfirmActive() }
            deliverPriceField?.text = entry.minPrice.toString()
            addDrawableChild(deliverPriceField)
        } else {
            val backCount = backpackCount(entry.itemId)
            val prefillCount = minOf(entry.remainingCount, backCount).coerceAtLeast(1)
            deliverCountField = TextFieldWidget(textRenderer, centerX - 40, dialogY + 62, 80, 16, Text.literal(""))
            deliverCountField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.deliver_count").formatted(Formatting.GRAY))
            deliverCountField?.setTextPredicate { it.length <= 4 && it.all { c -> c.isDigit() } }
            deliverCountField?.setChangedListener { updateDeliverConfirmActive() }
            deliverCountField?.text = if (backCount > 0) prefillCount.toString() else ""
            addDrawableChild(deliverCountField)
            deliverPriceField = TextFieldWidget(textRenderer, centerX - 40, dialogY + 84, 80, 16, Text.literal(""))
            deliverPriceField?.setPlaceholder(Text.translatable("cobblemarket.buy_order.deliver_price").formatted(Formatting.GRAY))
            deliverPriceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
            deliverPriceField?.setChangedListener { updateDeliverConfirmActive() }
            deliverPriceField?.text = entry.minPrice.toString()
            addDrawableChild(deliverPriceField)
        }

        // 按钮顺序照惯例：确认在左，取消在右
        deliverConfirmButton = NineSliceButton(
            centerX - 68, dialogY + confirmDeliverY(), 56, 20,
            Text.translatable("cobblemarket.buy_order.deliver_confirm"),
            { confirmDeliver() },
            texture = BUY_ORDER_BUTTON_TEXTURE,
            texH = BUY_ORDER_BUTTON_TEX_H
        )
        addDrawableChild(deliverConfirmButton)
        deliverCancelButton = NineSliceButton(
            centerX + 8, dialogY + confirmDeliverY(), 56, 20,
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

    private fun renderDeliverDialogBackground(context: DrawContext) {
        val entry = deliverEntry ?: return
        val centerX = width / 2
        val dialogW = 280
        val dialogH = if (entry.type == "POKEMON") 150 else 152
        val dialogX = centerX - dialogW / 2
        val dialogY = deliverDialogY()

        context.fill(0, 0, width, height, 0xC0000000.toInt())
        // 不透明衬底：弹窗背景贴图中间区域半透明，下层行内容（精灵图标/数量/价格）会透过
        context.fill(dialogX, dialogY, dialogX + dialogW, dialogY + dialogH, 0xFF2A2A2A.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.buy_order.deliver_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        // 订单摘要
        context.drawCenteredTextWithShadow(textRenderer,
            "${entryName(entry)}  ${priceRangeText(entry)}",
            centerX, dialogY + 24, 0x55FFFF)

        // 买家留言（有备注时显示，灰色截断；卖家交付前须知）
        if (entry.note.isNotEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                com.shusheng.cobblemarket.util.TextUtil.truncateString(
                    "${Text.translatable("cobblemarket.buy_order.tooltip_note").string}${entry.note}", 240
                ),
                centerX, dialogY + 34, 0xFFDD99)
        }

        if (entry.type == "POKEMON") {
            // 已选精灵信息（名称 + 匹配状态；未选择时提示）
            val p = deliverSelectedPokemon
            if (p != null) {
                val name = com.shusheng.cobblemarket.util.TextUtil.truncateString(speciesDisplay(p), 60)
                context.drawTextWithShadow(textRenderer,
                    name,
                    centerX - 118, dialogY + 66, 0xFFFFFF)
                if (clientMatchesPokemon(p, entry)) {
                    context.drawTextWithShadow(textRenderer,
                        Text.translatable("cobblemarket.buy_order.match_ok_full").string,
                        centerX - 118 + textRenderer.getWidth(name) + 4,
                        dialogY + 66, 0x55FF55)
                } else {
                    context.drawTextWithShadow(textRenderer,
                        Text.translatable("cobblemarket.buy_order.match_no_full").string,
                        centerX - 118 + textRenderer.getWidth(name) + 4,
                        dialogY + 66, 0xFF6666)
                }
            } else {
                context.drawTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.buy_order.not_selected").formatted(Formatting.GRAY),
                    centerX - 118, dialogY + 66, 0xAAAAAA)
            }
            // 单价输入标签：紧贴输入框左缘（输入框 x = centerX-40）
            val priceLabel = Text.translatable("cobblemarket.buy_order.deliver_price").string
            context.drawTextWithShadow(textRenderer,
                priceLabel,
                centerX - 44 - textRenderer.getWidth(priceLabel), dialogY + 84, 0xFFFFFF)
        } else {
            val backCount = backpackCount(entry.itemId)
            // 背包持有量 + 件数输入标签
            context.drawTextWithShadow(textRenderer,
                "${Text.translatable("cobblemarket.buy_order.backpack").string} $backCount",
                centerX - 120, dialogY + 48, 0xAAAAAA)
            // 件数/单价标签：紧贴对应输入框左缘（输入框 x = centerX-40）
            val countLabel = Text.translatable("cobblemarket.buy_order.deliver_count").string
            context.drawTextWithShadow(textRenderer,
                countLabel,
                centerX - 44 - textRenderer.getWidth(countLabel), dialogY + 62, 0xFFFFFF)
            val priceLabel2 = Text.translatable("cobblemarket.buy_order.deliver_price").string
            context.drawTextWithShadow(textRenderer,
                priceLabel2,
                centerX - 44 - textRenderer.getWidth(priceLabel2), dialogY + 84, 0xFFFFFF)
        }
    }

    private fun speciesDisplay(p: PokemonPreview): String {
        val t = Text.translatable(p.species).string
        return if (t == p.species) p.speciesName else t
    }

    /** 打开上架选择界面（交付模式）：订单 id 静态暂存，选完精灵回传后恢复交付弹窗 */
    private fun openDeliverSelect() {
        val entry = deliverEntry ?: return
        pendingDeliverOrderId = entry.id
        client?.setScreen(SellSelectScreen(entry.id))
    }

    private fun updateDeliverSelectButton() {
        val p = deliverSelectedPokemon
        val label = if (p != null) {
            "${Text.translatable("cobblemarket.buy_order.select_pokemon").string}: ${com.shusheng.cobblemarket.util.TextUtil.truncateString(speciesDisplay(p), 60)}"
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
            ClientPlayNetworking.send(DeliverPokemonBuyOrderPayload(entry.id, p.uuid, price))
        } else {
            val count = deliverCountField?.text?.toIntOrNull() ?: 0
            if (count !in 1..entry.remainingCount || count > backpackCount(entry.itemId)) {
                client?.player?.sendMessage(Text.translatable("cobblemarket.buy_order.wrong_count").formatted(Formatting.RED), false)
                return
            }
            ClientPlayNetworking.send(DeliverItemBuyOrderPayload(entry.id, count, price))
        }
    }

    private fun closeDialogs() {
        deliverEntry = null
        deliverSelectedPokemon = null
        deliverSelectButton = null
        deliverPriceField = null
        deliverCountField = null
        deliverConfirmButton = null
        reviewEntry = null
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
        createFormButton = null
        createAbilityButton = null
        createNatureButton = null
        createConfirmButton = null
        createCancelButton = null
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
            formIndex, abilityIndex, natureIndex, selectedItemIndex, texts
        )
    }

    private fun restoreCreateDialog(snapshot: CreateDialogSnapshot) {
        openCreateDialog()
        createTab = snapshot.tab
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
        scrollOffset = savedScroll.coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        rebuildRowButtons()
    }

    override fun shouldPause() = false

    override fun close() {
        closed = true
        super.close()
    }

    companion object {
        // 背景三段贴图左右边框实际像素 17（用户实测），内容与行背景统一避开此宽度
        // 横竖边框分离：2026-08-22 用户更换背景图，左右边框 17→8（行/搜索框/按钮向外扩 18px），上下仍 17
        private const val PANEL_BORDER_X = 8
        private const val PANEL_BORDER_Y = 17
        private val BUY_ORDER_PANEL_TOP = Identifier.of("cobblemarket", "textures/gui/buy_order_panel_top.png")
        private val BUY_ORDER_PANEL_MIDDLE = Identifier.of("cobblemarket", "textures/gui/buy_order_panel_middle.png")
        private val BUY_ORDER_PANEL_BOTTOM = Identifier.of("cobblemarket", "textures/gui/buy_order_panel_bottom.png")
        // 按钮贴图（40×80 = 普通/悬停两段），求购单界面所有按钮统一使用
        private val BUY_ORDER_BUTTON_TEXTURE = Identifier.of("cobblemarket", "textures/gui/buy_order_button.png")
        private const val BUY_ORDER_BUTTON_TEX_H = 80
        // 创建弹窗形态列表最大可见行数（超出滚动，照黑名单添加框）
        private const val MAX_FORM_LIST_ROWS = 8
        // 悬停面板备注换行宽度上限
        private const val MAX_TOOLTIP_W = 260
        // 性格列表最大可见行数（25 种滚动浏览，弹窗高度受限）
        private const val MAX_NATURE_LIST_ROWS = 6
        // 物品匹配列表最大可见行数
        private const val MAX_ITEM_LIST_ROWS = 6

        // 交付流程跨界面传递（SellSelectScreen 交付模式 → 新建的 BuyOrderScreen）：
        // 界面切换时实例销毁，静态暂存订单 id 与所选精灵
        var pendingDeliverOrderId: UUID? = null
        var pendingDeliverPokemon: PokemonPreview? = null
    }
}
