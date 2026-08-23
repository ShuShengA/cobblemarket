package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.mojang.authlib.GameProfile
import com.shusheng.cobblemarket.network.AuctionEntry
import com.shusheng.cobblemarket.network.AuctionEventPayload
import com.shusheng.cobblemarket.network.AuctionListDataPayload
import com.shusheng.cobblemarket.network.ForceCancelAuctionPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.RequestAuctionListPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.joml.Quaternionf
import java.util.UUID

/**
 * 管理员「所有拍卖」管理页。行渲染/悬停/确认弹窗全部照搬拍卖场（AuctionScreen），
 * 区别：点击行打开「强制下架」确认弹窗（两列布局照搬出价弹窗）。
 */
class AdminAuctionScreen : Screen(Text.translatable("cobblemarket.op.auction")) {

    private val panelWidth = 296
    private val rowHeight = 24
    private val iconSize = 20

    private var entries = listOf<AuctionEntry>()
    // 过滤结果缓存：数据/搜索变化时重建，render 每帧只读（避免每帧全量 filter）
    private var filteredCache = listOf<AuctionEntry>()
    // 带索引列表缓存（IndexedValue.index = entries 中的原始索引）：render 行循环每帧 entries.indexOf 是扫描热点，重建过滤缓存时同步构建
    private var indexedFiltered = listOf<IndexedValue<AuctionEntry>>()
    private var hoveredRow = -1
    private var scrollOffset = 0
    private var cancelEntry: AuctionEntry? = null
    private var searchField: net.minecraft.client.gui.widget.TextFieldWidget? = null
    private var backButton: NineSliceButton? = null

    // 到期结算轮询（照搬拍卖场）：存在到期未结算条目时周期重拉，触发服务端结算
    private var lastSettlePoll = 0L

    // 锤子图标动画（照搬拍卖场；管理界面只做图标动画不播音效）
    private val hammerHitUntil = mutableMapOf<UUID, Long>()
    private data class WarnState(val endsAt: Long, val knocks: Int)
    private val warnStates = mutableMapOf<UUID, WarnState>()
    private val settlingUntil = mutableMapOf<UUID, Long>()

    private fun knockFor(remaining: Long): Int = when {
        remaining > 10_000L -> 0
        remaining > 6_000L -> 1
        remaining > 3_000L -> 2
        else -> 3
    }

    private var cancelRenderable: RenderablePokemon? = null
    private val cancelPreviewState = FloatingState()
    private var cancelConfirmButton: NineSliceButton? = null
    private var cancelCancelButton: NineSliceButton? = null

    private fun getListStartY() = 68
    private fun getMaxVisibleRows() = maxOf(0, (height - getListStartY() - 40) / rowHeight)

    /** 搜索过滤：按名称 + 卖家名（精灵与物品共用，客户端本地过滤） */
    private fun filteredEntries(): List<AuctionEntry> = filteredCache

    /** 数据/搜索变化时重建过滤缓存（render 每帧只读） */
    private fun rebuildFiltered() {
        val query = searchField?.text?.trim()?.takeIf { it.isNotEmpty() }
        filteredCache = if (query == null) entries else entries.filter {
            displayName(it).contains(query, ignoreCase = true) || it.sellerName.contains(query, ignoreCase = true)
        }
        indexedFiltered = filteredCache.map { IndexedValue(entries.indexOf(it), it) }
    }

    // ── 照搬拍卖场的显示工具 ──

    private data class IconData(val displayName: String, val renderable: RenderablePokemon, val state: FloatingState)
    private val iconData = mutableMapOf<Int, IconData>()

    // 行内物品栈缓存（球种/持有物/物品拍卖图标）：render 每帧 new ItemStack 是分配热点，数据到达时构建一次。
    // 与 iconData 分离：球种/持有物渲染不依赖物种解析成功，独立缓存保持与原逐帧解析完全一致的行为
    private data class RowStacks(val ballStack: ItemStack?, val heldStack: ItemStack?, val itemStack: ItemStack?)
    private val rowStacks = mutableMapOf<Int, RowStacks>()

    // tooltip 缓存（照拍卖场同款拆分）：静态行只取决于条目，动态行（价格/倒计时/领先者）每帧构建
    private var tooltipCacheKey: UUID? = null
    private var tooltipCacheStaticLines: List<Pair<Text?, Int>> = emptyList()
    private var tooltipCacheHeldLine = -1
    private var tooltipCacheMaxWidth = 0
    private var itemTooltipCacheKey: UUID? = null
    private var itemTooltipCacheLines: List<Pair<Text, Int>> = emptyList()
    private var itemTooltipCacheMaxWidth = 0

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

    /** 价格文本：金额+单位金色；无出价时「起拍」前缀白色（2026-08-24 拍板：文字默认色） */
    private fun displayPriceText(entry: AuctionEntry): Text {
        val price = if (entry.currentPrice > 0) entry.currentPrice else entry.startingPrice
        val priceT = Text.literal(com.shusheng.cobblemarket.client.formatPrice(price) + " " + com.shusheng.cobblemarket.client.inlineCurrencyUnit()).formatted(Formatting.GOLD)
        return if (entry.currentPrice > 0) priceT
        else Text.translatable("cobblemarket.auction.from").append(priceT)
    }

    private fun formatRemaining(endsAt: Long): String {
        val ms = endsAt - System.currentTimeMillis()
        if (ms <= 0) return Text.translatable("cobblemarket.auction.ending").string
        val totalSec = ms / 1000
        val totalMin = totalSec / 60
        if (totalMin < 1) return "${totalSec}s"
        if (totalMin < 3) return "${totalMin}m${totalSec % 60}s"
        if (totalMin < 60) return "${totalMin}m"
        val h = totalMin / 60
        val m = totalMin % 60
        if (h < 24) return "${h}h${m}m"
        return "${h / 24}d${h % 24}h"
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

    private fun cacheIcons() {
        iconData.clear()
        rowStacks.clear()
        entries.forEachIndexed { i, entry ->
            if (entry.type != "POKEMON") {
                rowStacks[i] = RowStacks(
                    ballStack = null,
                    heldStack = null,
                    itemStack = Identifier.tryParse(entry.species)?.let { ItemStack(Registries.ITEM.get(it)) }
                )
                return@forEachIndexed
            }
            rowStacks[i] = RowStacks(
                ballStack = entry.extraData["ballItem"]?.let { Identifier.tryParse(it)?.let { id -> ItemStack(Registries.ITEM.get(id)) } },
                heldStack = buildHeldStack(entry.extraData["heldItemId"].orEmpty()),
                itemStack = null
            )
            val id = Identifier.tryParse(entry.extraData["speciesId"] ?: "") ?: return@forEachIndexed
            val species = PokemonSpecies.getByIdentifier(id) ?: return@forEachIndexed
            val aspects = (entry.extraData["aspects"] ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()
            if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
            iconData[i] = IconData(displayName(entry), RenderablePokemon(species, aspects, ItemStack.EMPTY), FloatingState())
        }
    }

    /** 携带物栈（空气视为无）；缓存供行渲染每帧复用（照精灵市场） */
    private fun buildHeldStack(heldItemId: String): ItemStack? {
        if (heldItemId.isEmpty()) return null
        val heldId = Identifier.tryParse(heldItemId) ?: return null
        val heldItem = Registries.ITEM.get(heldId)
        return if (heldItem != Registries.ITEM.get(Identifier.of("minecraft", "air"))) ItemStack(heldItem) else null
    }

    private fun renderPokemonIcon(context: DrawContext, index: Int, x: Int, y: Int, size: Int) {
        val data = iconData[index] ?: return run {
            val tc = 0x88888888.toInt()
            context.fill(x, y, x + size, y + size, tc)
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

    // ── 初始化与数据 ──

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        val backBtn = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(AdminScreen()) }
        )
        backButton = backBtn
        addDrawableChild(backBtn)

        searchField = net.minecraft.client.gui.widget.TextFieldWidget(
            textRenderer, leftX + 2, 44, panelWidth - 4, 16, Text.translatable("cobblemarket.gui.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.auction.search_placeholder").formatted(Formatting.GRAY))
        // 搜索变化失效过滤缓存（render 每帧读缓存，不再实时过滤）
        searchField?.setChangedListener { rebuildFiltered() }
        addSelectableChild(searchField)
        addDrawableChild(searchField)

        ClientPlayNetworking.send(RequestAuctionListPayload())
        rebuildFiltered()
    }

    fun onAuctionList(payload: AuctionListDataPayload) {
        // 结算中条目保留到动画结束（照搬拍卖场），其余以服务端为准
        val settling = entries.filter { it.id in settlingUntil }
        entries = payload.entries + settling.filter { s -> payload.entries.none { it.id == s.id } }
        rebuildFiltered()
        cacheIcons()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, entries.size - getMaxVisibleRows()))
    }

    fun onAuctionEvent(payload: AuctionEventPayload) {
        when (payload.event) {
            "NEW" -> payload.entry?.let { e ->
                if (entries.none { it.id == e.id }) entries = entries + e
            }
            "BID" -> payload.entry?.let { e ->
                entries = entries.map { if (it.id == e.id) e else it }
                // 弹窗同步最新数据（与拍卖场一致，弹窗内价格不过期）
                if (cancelEntry?.id == e.id) cancelEntry = e
            }
            "SETTLED" -> payload.entry?.let { e ->
                // 延迟 1.5 秒移除（照搬拍卖场：显示「结算中」+ 落槌图标敲击动画）
                settlingUntil[e.id] = System.currentTimeMillis() + 1500
                hammerHitUntil[e.id] = System.currentTimeMillis() + 300
                // 拍卖已结算：若弹窗正开着则自动关闭（确认也会被服务端拒绝）
                if (cancelEntry?.id == e.id) closeCancelDialog()
            }
        }
        rebuildFiltered()
        cacheIcons()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, entries.size - getMaxVisibleRows()))
    }

    fun onMarketResult(payload: MarketResultPayload) {
        client?.player?.sendMessage(payload.message.copy().formatted(if (payload.success) Formatting.GREEN else Formatting.RED), false)
        if (payload.success) {
            ClientPlayNetworking.send(RequestAuctionListPayload())
        }
    }

    // ── 强制下架确认弹窗（照搬出价弹窗两列布局） ──

    private fun openCancelDialog(entry: AuctionEntry) {
        cancelEntry = entry
        cancelRenderable = null
        // 隐藏下层控件（弹窗打开期间不可交互；closeCancelDialog 的 init 重建会恢复）
        searchField?.visible = false
        backButton?.visible = false
        if (entry.type == "POKEMON") {
            val id = Identifier.tryParse(entry.extraData["speciesId"] ?: "")
            val species = id?.let { PokemonSpecies.getByIdentifier(it) }
            if (species != null) {
                val aspects = (entry.extraData["aspects"] ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()
                if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
                cancelRenderable = RenderablePokemon(species, aspects, ItemStack.EMPTY)
            }
        }
        val centerX = width / 2
        val dialogY = height / 2 - 95

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染，照搬出价弹窗）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderCancelDialogBackground(context)
            }
        })

        cancelConfirmButton = NineSliceButton(
            centerX - 60, dialogY + 148, 56, 20,
            Text.translatable("cobblemarket.auction.force_cancel"),
            { confirmCancel() }
        )
        addDrawableChild(cancelConfirmButton)
        cancelCancelButton = NineSliceButton(
            centerX + 4, dialogY + 148, 56, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeCancelDialog() }
        )
        addDrawableChild(cancelCancelButton)
    }

    private fun closeCancelDialog() {
        cancelEntry = null
        cancelRenderable = null
        cancelConfirmButton = null
        cancelCancelButton = null
        clearChildren()
        init()
    }

    private fun confirmCancel() {
        val entry = cancelEntry ?: return
        ClientPlayNetworking.send(ForceCancelAuctionPayload(entry.id))
        closeCancelDialog()
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val cancel = cancelEntry
        super.resize(client, width, height)
        if (cancel != null) {
            cancelEntry = null
            openCancelDialog(cancel)
        }
    }

    // ── 渲染 ──

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int, sliceH: Int = 16) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

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

        hoveredRow = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY in startY..(startY + visibleRows * rowHeight)) {
            val row = (mouseY - startY) / rowHeight
            if (row in 0 until minOf(visibleRows, filteredEntries().size - scrollOffset)) hoveredRow = row
        }

        repeat(minOf(visibleRows, maxOf(0, filteredEntries().size - scrollOffset))) { di ->
            val rowY = startY + di * rowHeight
            val rowState = if (di == hoveredRow) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, rowState, ROW_BACKGROUND_TEX_H)
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        if (cancelEntry != null) {
            return
        }

        val pollNow = System.currentTimeMillis()

        // 结算中条目过期（1.5 秒）后从列表移除（照搬拍卖场）
        val settledExpired = settlingUntil.filterValues { it <= pollNow }.keys
        if (settledExpired.isNotEmpty()) {
            entries = entries.filterNot { it.id in settledExpired }
            settledExpired.forEach { settlingUntil.remove(it) }
            rebuildFiltered()
            // entries 变化后索引移动：行缓存（3D 图标/物品栈）按索引建，必须同步重建
            cacheIcons()
        }

        // 结束前警告：本地检测 10s/6s/3s，只触发图标敲击动画。
        // 音效不由这里负责：管理员若出价参与过，服务端定向事件会正常送达并全局播放
        val toKnock = entries.filter { e ->
            val k = knockFor(e.endsAt - pollNow)
            if (k <= 0) return@filter false
            val st = warnStates[e.id]
            (st == null || st.endsAt != e.endsAt || st.knocks < k)
        }
        if (toKnock.isNotEmpty()) {
            toKnock.forEach { e ->
                warnStates[e.id] = WarnState(e.endsAt, knockFor(e.endsAt - pollNow))
                hammerHitUntil[e.id] = pollNow + 300
            }
        }

        // 有到期未结算的拍卖时，每秒重拉一次列表触发服务端结算（与拍卖场一致，避免一直显示「结算中」）；
        // 结算中条目排除（已结算完毕，重拉会全量替换、截断动画）
        if (entries.any { it.endsAt <= pollNow && it.id !in settlingUntil } && pollNow - lastSettlePoll > 1000) {
            lastSettlePoll = pollNow
            ClientPlayNetworking.send(RequestAuctionListPayload())
        }

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.op.auction").formatted(Formatting.GOLD),
            centerX, 20, 0xFFFFFF)

        val startY = getListStartY()
        val visibleRows = getMaxVisibleRows()
        val filtered = filteredEntries()
        // 按过滤后大小钳制滚动（搜索词变化后不显示空白区域）
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, filtered.size - visibleRows))

        if (filtered.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.auction.empty").formatted(Formatting.GRAY),
                centerX, startY + 30, 0xFFFFFF)
        }

        indexedFiltered.drop(scrollOffset).take(visibleRows).forEachIndexed { i, (origIndex, entry) ->
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
                    renderPokemonIcon(context, origIndex, slotX, slotY, iconSize)
                }
                // 图标链 + 属性色名称 + 金色闪光星标（照搬拍卖场行）
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
                val name = com.shusheng.cobblemarket.util.TextUtil.truncateString(displayName(entry), 44)
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
                // 持有物图标（空气/解析失败 = null 不渲染，与原逐帧解析行为一致）
                rowStacks[origIndex]?.heldStack?.let { heldStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = heldStack, x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                }
            } else {
                rowStacks[origIndex]?.itemStack?.let {
                    context.drawItem(it, slotX + 2, slotY)
                }
                val name = "${displayName(entry)} ×${entry.count}"
                context.drawTextWithShadow(textRenderer,
                    com.shusheng.cobblemarket.util.TextUtil.truncateString(name, 100),
                    leftX + 28, y + 7, 0xFFFFFF)
            }

            // 当前价（货币蓝）+ 出价次数（灰，拆段）
            val priceStr = displayPriceText(entry)
            val bidPart = if (entry.bidCount > 0) " ×${entry.bidCount}" else ""
            val priceX = leftX + panelWidth - 56 - textRenderer.getWidth(priceStr) - textRenderer.getWidth(bidPart)

            // 价格左侧锤子图标：默认静止，敲击/落槌时切换敲击状态约 0.3 秒（照搬拍卖场）
            val hammerTex = if ((hammerHitUntil[entry.id] ?: 0L) > System.currentTimeMillis())
                HAMMER_TEX_LEFT
            else
                HAMMER_TEX_LEFT_NO
            context.matrices.push()
            context.matrices.translate((priceX - 16).toDouble(), (y + 6).toDouble(), 0.0)
            context.matrices.scale(0.5f, 0.5f, 1f)
            context.drawTexture(hammerTex, 0, 0, 0f, 0f, 24, 24, 24, 24)
            context.matrices.pop()

            context.drawTextWithShadow(textRenderer, priceStr, priceX, y + 7, 0xFFFFFF)
            if (bidPart.isNotEmpty()) {
                context.drawTextWithShadow(textRenderer, bidPart,
                    priceX + textRenderer.getWidth(priceStr), y + 7, 0xAAAAAA)
            }

            // 结束倒计时，左侧画卖家头像（照搬拍卖场）
            val remaining = formatRemaining(entry.endsAt)
            val remainingColor = if (entry.endsAt - System.currentTimeMillis() < 5 * 60 * 1000) 0xFF6666 else 0xAAAAAA
            val remW = textRenderer.getWidth(remaining)
            context.drawTextWithShadow(textRenderer, remaining,
                leftX + 156 - remW, y + 7, remainingColor)
            drawSellerAvatar(context, entry.sellerUuid, entry.sellerName, leftX + 156 - remW - 20, y + 4, 16)
        }

        if (hoveredRow >= 0) {
            val filtered = filteredEntries()
            val idx = scrollOffset + hoveredRow
            if (idx in filtered.indices) {
                renderTooltip(context, filtered[idx], mouseX, mouseY)
            }
        }

        if (filteredEntries().size > visibleRows) {
            context.drawCenteredTextWithShadow(textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + visibleRows, filteredEntries().size)} / ${filteredEntries().size}",
                centerX, height - 24, 0x888888)
        }
    }

    // ── 悬停（照搬拍卖场，去掉「我的」标记） ──

    private fun renderTooltip(context: DrawContext, entry: AuctionEntry, mouseX: Int, mouseY: Int) {
        if (entry.type == "POKEMON") renderPokemonTooltip(context, entry, mouseX, mouseY)
        else renderItemTooltip(context, entry, mouseX, mouseY)
    }

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
                .copy().append(Text.literal("  Lv.${entry.level}")) to 0xFFFFFF)
            staticLines.add(Text.literal("${Text.translatable("cobblemarket.gui.tooltip_type").string}$typeText") to 0xFFFFFF)
            staticLines.add(Text.literal(Text.translatable("cobblemarket.gui.tooltip_nature").string)
                .append(EntryBadgeRenderer.natureText(extra["natureBase"] ?: "", extra["nature"] ?: ""))
                .append(Text.literal("  ${Text.translatable("cobblemarket.gui.tooltip_ability").string}"))
                .append(Text.translatable(extra["ability"] ?: "")) to 0xFFFFFF)
            val heldItemId = extra["heldItemId"].orEmpty()
            val hasHeldItem = heldItemId.isNotEmpty() &&
                Identifier.tryParse(heldItemId)?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true
            var heldItemLine = -1
            if (hasHeldItem) {
                heldItemLine = staticLines.size
                staticLines.add(Text.translatable("cobblemarket.gui.tooltip_held") to 0xFFFFFF)
            }
            staticLines.add(Text.translatable("cobblemarket.gui.tooltip_ivs") to 0xFFFFFF)
            staticLines.add(Text.literal("  $hp:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsHp"]?.toIntOrNull() ?: 0, htExtra(extra, "htHp"))}") to 0x66FF66); staticLines.add(Text.literal("  $atk:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsAtk"]?.toIntOrNull() ?: 0, htExtra(extra, "htAtk"))}") to 0xFF6666)
            staticLines.add(Text.literal("  $def:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsDef"]?.toIntOrNull() ?: 0, htExtra(extra, "htDef"))}") to 0xFFCC66); staticLines.add(Text.literal("  $spa:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpAtk"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpAtk"))}") to 0x6699FF)
            staticLines.add(Text.literal("  $spd:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpDef"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpDef"))}") to 0x66FF99); staticLines.add(Text.literal("  $spe:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpd"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpd"))}") to 0xFF99FF)

            var mw = 0; staticLines.forEach { it.first?.let { t -> mw = maxOf(mw, textRenderer.getWidth(t)) } }
            if (heldItemLine >= 0) {
                mw = maxOf(mw, textRenderer.getWidth(staticLines[heldItemLine].first!!) + 14)
            }
            tooltipCacheStaticLines = staticLines
            tooltipCacheHeldLine = heldItemLine
            tooltipCacheMaxWidth = mw
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

        var mw = tooltipCacheMaxWidth
        for (i in tooltipCacheStaticLines.size until lines.size) {
            lines[i].first?.let { t -> mw = maxOf(mw, textRenderer.getWidth(t)) }
        }
        val heldItemLine = tooltipCacheHeldLine
        val pad = 4
        val tx = minOf(mouseX + 12, width - mw - 12)
        val th = lines.size * 10 + pad
        val ty = if (mouseY - th - 4 <= 0) minOf(mouseY + 12, height - th) else mouseY - th - 4

        context.matrices.push(); context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - pad, ty - pad, mw + 2 * pad, lines.size * 10 + 2 * pad, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, (line, color) ->
            if (line == null) {
                context.fill(tx, ty + i * 10 + 4, tx + mw, ty + i * 10 + 5, 0xFF555555.toInt())
            } else if (i == heldItemLine) {
                context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
                Identifier.tryParse(entry.extraData["heldItemId"].orEmpty())?.let { heldId ->
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
                EntryBadgeRenderer.drawNameLineLeft(context, line, entry.extraData["gender"] ?: "", tx, ty + i * 10, color)
            } else {
                context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
            }
        }
        context.matrices.pop()
    }

    private fun renderItemTooltip(context: DrawContext, entry: AuctionEntry, mouseX: Int, mouseY: Int) {
        // 静态行（名称/卖家）缓存；价格/倒计时/领先者为动态行每帧构建（见 itemTooltipCacheKey 字段注释）
        if (itemTooltipCacheKey != entry.id) {
            itemTooltipCacheKey = entry.id
            val staticLines = mutableListOf<Pair<Text, Int>>()
            staticLines.add(Text.literal(displayName(entry)) to 0xFFFFFF)
            staticLines.add(Text.literal("${Text.translatable("cobblemarket.auction.seller").string}: ${entry.sellerName}") to 0xFFFFFF)
            var mw = 0
            staticLines.forEach { mw = maxOf(mw, textRenderer.getWidth(it.first)) }
            itemTooltipCacheLines = staticLines
            itemTooltipCacheMaxWidth = mw
        }
        val lines = itemTooltipCacheLines.toMutableList()
        val priceLine = Text.translatable("cobblemarket.auction.current_price").append(": ").append(displayPriceText(entry))
        lines.add((if (entry.bidCount > 0)
            priceLine.append(Text.literal("  ×${entry.bidCount}").formatted(Formatting.GRAY))
        else priceLine) to 0xFFFFFF)
        lines.add(Text.literal("${Text.translatable("cobblemarket.auction.ends").string}: ${formatRemaining(entry.endsAt)}") to 0xFFFFFF)
        if (entry.currentBidderName.isNotEmpty()) {
            lines.add(Text.literal("${Text.translatable("cobblemarket.auction.leader").string}: ${entry.currentBidderName}") to 0xFFDD66)
        }

        var maxWidth = itemTooltipCacheMaxWidth
        for (i in itemTooltipCacheLines.size until lines.size) {
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

    // ── 强制下架弹窗渲染（照搬出价弹窗两列布局） ──

    private fun renderCancelDialogBackground(context: DrawContext) {
        val entry = cancelEntry ?: return
        val centerX = width / 2
        val dialogW = 280
        val dialogH = 190
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        context.fill(0, 0, width, height, 0xC0000000.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.auction.force_cancel").formatted(Formatting.GOLD),
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
            cancelRenderable?.let { rp ->
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
                        state = cancelPreviewState,
                        partialTicks = 0f,
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
                    context.drawItem(stack, slotX + 6, slotY + 6)
                }
            }
        }

        // ── 左列：精灵/物品完整信息（照搬出价弹窗） ──
        val infoX = dialogX + 10
        var iy = dialogY + 56
        fun infoLine(text: String, color: Int = 0xFFFFFF) {
            context.drawTextWithShadow(textRenderer, text, infoX, iy, color)
            iy += 10
        }
        if (entry.type == "POKEMON") {
            val extra = entry.extraData
            val primaryType = extra["primaryType"] ?: ""
            val tc = typeColor(if (primaryType.isNotEmpty()) primaryType else "cobblemon.type.normal")
            context.drawTextWithShadow(textRenderer, displayName(entry), infoX, iy, tc)
            var cx = infoX + textRenderer.getWidth(displayName(entry))
            if (entry.shiny) {
                context.drawText(textRenderer, "★", cx + 2, iy, GOLD_COLOR, false)
                cx += 2 + textRenderer.getWidth("★")
            }
            context.drawText(textRenderer, "  Lv.${entry.level}", cx + 2, iy, 0xFFFFFF, false)
            iy += 10
            val secondaryType = extra["secondaryType"] ?: ""
            val typeText = (if (primaryType.isNotEmpty()) Text.translatable(primaryType).string else "-") +
                if (secondaryType.isNotEmpty()) " + ${Text.translatable(secondaryType).string}" else ""
            infoLine("${Text.translatable("cobblemarket.gui.tooltip_type").string}$typeText")
            // 性格（薄荷约定：原生斜体+括号生效）
            context.drawTextWithShadow(textRenderer,
                Text.literal(Text.translatable("cobblemarket.gui.tooltip_nature").string)
                    .append(EntryBadgeRenderer.natureText(extra["natureBase"] ?: "", extra["nature"] ?: "")),
                infoX, iy, 0xFFFFFF)
            iy += 10
            infoLine("${Text.translatable("cobblemarket.gui.tooltip_ability").string}${Text.translatable(extra["ability"] ?: "").string}")
            val heldItemId = extra["heldItemId"].orEmpty()
            val hasHeldItem = heldItemId.isNotEmpty() &&
                Identifier.tryParse(heldItemId)?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true
            if (hasHeldItem) {
                infoLine(Text.translatable("cobblemarket.gui.tooltip_held").string, 0xAAAAAA)
            }
            val hp = Text.translatable("cobblemon.stat.hp.name").string
            val atk = Text.translatable("cobblemon.stat.attack.name").string
            val def = Text.translatable("cobblemon.stat.defence.name").string
            val spa = Text.translatable("cobblemon.stat.special_attack.name").string
            val spd = Text.translatable("cobblemon.stat.special_defence.name").string
            val spe = Text.translatable("cobblemon.stat.speed.name").string
            infoLine(Text.translatable("cobblemarket.gui.tooltip_ivs").string)
            infoLine("  $hp:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsHp"]?.toIntOrNull() ?: 0, htExtra(extra, "htHp"))}", 0x66FF66); infoLine("  $atk:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsAtk"]?.toIntOrNull() ?: 0, htExtra(extra, "htAtk"))}", 0xFF6666)
            infoLine("  $def:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsDef"]?.toIntOrNull() ?: 0, htExtra(extra, "htDef"))}", 0xFFCC66); infoLine("  $spa:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpAtk"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpAtk"))}", 0x6699FF)
            infoLine("  $spd:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpDef"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpDef"))}", 0x66FF99); infoLine("  $spe:${com.shusheng.cobblemarket.util.TextUtil.ivText(extra["ivsSpd"]?.toIntOrNull() ?: 0, htExtra(extra, "htSpd"))}", 0xFF99FF)
        } else {
            infoLine(displayName(entry))
            infoLine("×${entry.count}")
        }

        // ── 右列：竞拍信息 ──
        val auctionX = dialogX + 150
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
        // 提示两排放按钮上方居中，避开左右两列信息
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.auction.force_cancel_confirm_1").formatted(Formatting.GRAY),
            centerX, dialogY + 120, 0xFFFFFF)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.auction.force_cancel_confirm_2").formatted(Formatting.GRAY),
            centerX, dialogY + 130, 0xFFFFFF)
    }

    // ── 交互 ──

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (cancelEntry != null) return false
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, filteredEntries().size - getMaxVisibleRows()))
        return true
    }

    private fun isInputFieldFocused() = focused === searchField

    private fun isMouseOverSearch(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (cancelEntry != null) {
            return super.mouseClicked(mouseX, mouseY, button)
        }
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverSearch(mouseX, mouseY)) {
            focused = null
        }
        if (hoveredRow >= 0) {
            val filtered = filteredEntries()
            val idx = scrollOffset + hoveredRow
            if (idx in filtered.indices) {
                openCancelDialog(filtered[idx])
                return true
            }
        }
        return result
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
    }
}
