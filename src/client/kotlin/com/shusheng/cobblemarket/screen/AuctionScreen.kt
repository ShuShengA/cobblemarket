package com.shusheng.cobblemarket.screen

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
import com.mojang.authlib.GameProfile
import java.util.UUID

/**
 * 拍卖大厅：精灵 / 物品 / 我的 三个 tab。
 * 数据：全量 ACTIVE 快照 + 服务端增量事件（NEW/BID/SETTLED）合并刷新，倒计时每帧实时计算。
 */
class AuctionScreen(private val initialTab: Int = 0) : Screen(Text.translatable("cobblemarket.auction.title")) {

    private val panelWidth = 296
    private val rowHeight = 24

    private var currentTab = initialTab.coerceIn(0, 2) // 0 = 精灵, 1 = 物品, 2 = 我的
    private var entries = listOf<AuctionEntry>()
    private var searchField: TextFieldWidget? = null
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
    private var bidField: TextFieldWidget? = null
    private var bidConfirmButton: NineSliceButton? = null
    private var bidCancelButton: NineSliceButton? = null
    private var bidRenderable: RenderablePokemon? = null
    private val bidPreviewState = FloatingState()

    // 精灵图标缓存
    private data class IconData(val displayName: String, val renderable: RenderablePokemon, val state: FloatingState)
    private val iconData = mutableMapOf<Int, IconData>()
    private val iconSize = 20

    private fun getListStartY() = 72
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

    private fun displayPriceText(entry: AuctionEntry): String {
        val price = if (entry.currentPrice > 0) entry.currentPrice else entry.startingPrice
        val text = com.shusheng.cobblemarket.client.formatPrice(price)
        return (if (entry.currentPrice > 0) text else "${Text.translatable("cobblemarket.auction.from").string}$text") + " ◆"
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

        val backBtn = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(MarketEntryScreen()) }
        )
        backButton = backBtn
        addDrawableChild(backBtn)

        // tab 按钮：精灵 / 物品 / 我的
        val tabW = 62
        val tabStart = centerX - (tabW * 3 + 4) / 2
        val tabLabels = listOf(
            "cobblemarket.auction.tab_pokemon",
            "cobblemarket.auction.tab_item",
            "cobblemarket.auction.tab_mine"
        )
        tabButtons.clear()
        tabLabels.forEachIndexed { i, _ ->
            val btn = NineSliceButton(
                tabStart + i * (tabW + 2), 32, tabW, 14,
                Text.literal(""), { switchTab(i) }
            )
            tabButtons.add(btn)
            addDrawableChild(btn)
        }
        updateTabButtons()

        searchField = TextFieldWidget(textRenderer, leftX + 2, 50, panelWidth - 4 - 52 - 20, 16, Text.translatable("cobblemarket.gui.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.gui.search_placeholder").formatted(Formatting.GRAY))
        addSelectableChild(searchField)
        addDrawableChild(searchField)

        val createBtn = NineSliceButton(
            leftX + panelWidth - 72, 50, 18, 16,
            Text.literal("+"), { client?.setScreen(AuctionCreateScreen(currentTab)) }
        )
        createButton = createBtn
        addDrawableChild(createBtn)

        // 规则按钮（+右侧）：悬停显示自绘规则面板（标题金/正文白/重点红/项间分割线）
        val rulesBtn = NineSliceButton(
            leftX + panelWidth - 52, 50, 50, 16,
            Text.translatable("cobblemarket.auction.rules"),
            { }
        )
        rulesButton = rulesBtn
        addDrawableChild(rulesBtn)

        // 不重置 scrollOffset：出价弹窗关闭/resize 重建时保留浏览位置（switchTab 才显式归零）
        ClientPlayNetworking.send(RequestAuctionListPayload())
    }

    private fun updateTabButtons() {
        val keys = listOf("cobblemarket.auction.tab_pokemon", "cobblemarket.auction.tab_item", "cobblemarket.auction.tab_mine")
        tabButtons.forEachIndexed { i, btn ->
            val label = Text.translatable(keys[i]).string
            btn.setMessage(Text.literal(if (currentTab == i) "● $label" else label))
        }
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        searchField?.text = ""
        // placeholder 跟随 tab：物品 tab 显示物品搜索提示，其余保持宝可梦名称
        searchField?.setPlaceholder(Text.translatable(
            if (tab == 1) "cobblemarket.item.search" else "cobblemarket.gui.search_placeholder"
        ).formatted(Formatting.GRAY))
        scrollOffset = 0
        hoveredRow = -1
        updateTabButtons()
        rebuildBidButtons()
    }

    // ── 数据接收 ──

    fun onAuctionList(payload: AuctionListDataPayload) {
        // 结算中的条目保留到动画结束（服务端全量已不含它们），其余以服务端为准
        val settling = entries.filter { it.id in settlingUntil }
        entries = payload.entries + settling.filter { s -> payload.entries.none { it.id == s.id } }
        cacheIcons()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        rebuildBidButtons()
    }

    fun onAuctionEvent(payload: AuctionEventPayload) {
        when (payload.event) {
            "NEW" -> payload.entry?.let { e ->
                if (entries.none { it.id == e.id }) entries = entries + e
            }
            "BID" -> payload.entry?.let { e ->
                entries = entries.map { if (it.id == e.id) e else it }
                // 出价弹窗同步最新数据（他人出价后弹窗内价格/预填不过期）
                if (bidEntry?.id == e.id) {
                    bidEntry = e
                    val minValid = if (e.currentPrice > 0)
                        (e.currentPrice.toLong() + e.minIncrement).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    else e.startingPrice
                    bidField?.text = minValid.toString()
                }
            }
            "SETTLED" -> payload.entry?.let { e ->
                // 延迟移除：保留 1.5 秒显示「结算中」+ 落槌动画，期间出价按钮不生成
                settlingUntil[e.id] = System.currentTimeMillis() + 1500
            }
        }
        cacheIcons()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        rebuildBidButtons()
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

    private fun filtered(): List<AuctionEntry> {
        val query = searchField?.text?.trim()?.takeIf { it.isNotEmpty() }
        return entries.filter { entry ->
            val inTab = when (currentTab) {
                0 -> entry.type == "POKEMON"
                1 -> entry.type == "ITEM"
                else -> {
                    val me = myUuid()
                    entry.sellerUuid == me || entry.currentBidderUuid == me
                }
            }
            if (!inTab) return@filter false
            query == null || displayName(entry).contains(query, ignoreCase = true) ||
                entry.species.contains(query, ignoreCase = true) || entry.sellerName.contains(query, ignoreCase = true)
        }.sortedBy { it.endsAt }
    }

    private fun displayCount() = filtered().size

    private fun isMine(entry: AuctionEntry): Boolean = entry.sellerUuid == myUuid()

    // ── 图标缓存 ──

    private fun cacheIcons() {
        iconData.clear()
        entries.forEachIndexed { index, entry ->
            if (entry.type != "POKEMON") return@forEachIndexed
            val id = Identifier.tryParse(entry.extraData["speciesId"] ?: "") ?: return@forEachIndexed
            val species = PokemonSpecies.getByIdentifier(id) ?: return@forEachIndexed
            val aspects = (entry.extraData["aspects"] ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()
            if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
            iconData[index] = IconData(displayName(entry), RenderablePokemon(species, aspects, ItemStack.EMPTY), FloatingState())
        }
    }

    private fun renderPokemonIcon(context: DrawContext, index: Int, x: Int, y: Int, size: Int) {
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
        bidButtons.forEach { it.visible = false }
        val centerX = width / 2
        val dialogY = height / 2 - 95

        // 精灵预览
        if (entry.type == "POKEMON") {
            val id = Identifier.tryParse(entry.extraData["speciesId"] ?: "")
            val species = id?.let { PokemonSpecies.getByIdentifier(it) }
            if (species != null) {
                val aspects = (entry.extraData["aspects"] ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()
                if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
                bidRenderable = RenderablePokemon(species, aspects, ItemStack.EMPTY)
            }
        }

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderBidDialogBackground(context)
            }
        })

        // 出价输入（预填最低有效出价；Long 计算防 Int 溢出 wrap 成负数）
        val minValid = if (entry.currentPrice > 0)
            (entry.currentPrice.toLong() + entry.minIncrement).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        else entry.startingPrice
        bidField = TextFieldWidget(textRenderer, centerX + 10, dialogY + 106, 120, 16, Text.literal(""))
        bidField?.setPlaceholder(Text.translatable("cobblemarket.auction.bid_placeholder").formatted(Formatting.GRAY))
        bidField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        bidField?.text = minValid.toString()
        addDrawableChild(bidField)

        bidConfirmButton = NineSliceButton(
            centerX + 10, dialogY + 132, 56, 20,
            Text.translatable("cobblemarket.auction.confirm_bid"),
            { confirmBid() },
            clickSound = Identifier.of("cobblemarket", "auction_bid")
        )
        addDrawableChild(bidConfirmButton)
        bidCancelButton = NineSliceButton(
            centerX + 74, dialogY + 132, 56, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeBidDialog() }
        )
        addDrawableChild(bidCancelButton)
    }

    private fun renderBidDialogBackground(context: DrawContext) {
        val entry = bidEntry ?: return
        val centerX = width / 2
        val dialogW = 280
        val dialogH = 190
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        context.fill(0, 0, width, height, 0xC0000000.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.auction.bid_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        // ── 左列：预览槽（精灵 3D / 物品图标） ──
        val slotSize = 28
        val slotX = dialogX + 10
        val slotY = dialogY + 20
        if (entry.type == "POKEMON") {
            val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
            context.matrices.push()
            context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
            context.matrices.scale(slotSize / 66f, slotSize / 66f, 1f)
            context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
            context.matrices.pop()
            bidRenderable?.let { rp ->
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
                        state = bidPreviewState,
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

        // ── 左列：精灵/物品完整信息 ──
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
            // 名字（属性色）+ 金色闪光星 + 等级
            context.drawText(textRenderer, displayName(entry), infoX, iy, tc, false)
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
            infoLine("${Text.translatable("cobblemarket.gui.tooltip_nature").string}${Text.translatable(extra["nature"] ?: "").string}")
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
            infoLine("  $hp:${extra["ivsHp"]}", 0x66FF66); infoLine("  $atk:${extra["ivsAtk"]}", 0xFF6666)
            infoLine("  $def:${extra["ivsDef"]}", 0xFFCC66); infoLine("  $spa:${extra["ivsSpAtk"]}", 0x6699FF)
            infoLine("  $spd:${extra["ivsSpDef"]}", 0x66FF99); infoLine("  $spe:${extra["ivsSpd"]}", 0xFF99FF)
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
        auctionLine("${Text.translatable("cobblemarket.auction.seller").string}: ${entry.sellerName}")
        auctionLine("${Text.translatable("cobblemarket.auction.current_price").string}: ${displayPriceText(entry)}", 0x55FFFF)
        auctionLine("${Text.translatable("cobblemarket.auction.starting_price").string}: ${com.shusheng.cobblemarket.client.formatPrice(entry.startingPrice)} ◆")
        auctionLine("${Text.translatable("cobblemarket.auction.min_increment").string}: ${com.shusheng.cobblemarket.client.formatPrice(entry.minIncrement)} ◆", 0xAAAAAA)
        auctionLine("${Text.translatable("cobblemarket.auction.ends").string}: ${formatRemaining(entry.endsAt)}", 0xAAAAAA)
        auctionLine("${Text.translatable("cobblemarket.auction.bids_count").string}: ${entry.bidCount}", 0xAAAAAA)
        if (entry.currentBidderName.isNotEmpty()) {
            auctionLine("${Text.translatable("cobblemarket.auction.leader").string}: ${entry.currentBidderName}", 0xFFDD66)
        }
    }

    private fun confirmBid() {
        val entry = bidEntry ?: return
        val amount = bidField?.text?.toIntOrNull() ?: return
        if (amount < entry.startingPrice || amount <= entry.currentPrice) return
        if (entry.currentPrice > 0 && amount - entry.currentPrice < entry.minIncrement) return
        ClientPlayNetworking.send(PlaceBidPayload(entry.id, amount))
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
        val bot = Identifier.of("cobblemarket", "textures/gui/market_panel_auction_house_bottom.png")

        drawPanelSlice(context, top, panelLeft, panelTop)
        var y = panelTop + sliceH
        while (y < panelBottom - sliceH) {
            drawPanelSlice(context, mid, panelLeft, y)
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
                Text.translatable("cobblemarket.gui.balance", balText).string,
                leftX + 4, 20, 0x55FFFF)
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
        context.fill(leftX, startY - 2, leftX + panelWidth, startY - 1, 0xFF555555.toInt())

        val displayList = filtered()

        if (displayList.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.auction.empty").formatted(Formatting.GRAY),
                centerX, startY + 50, 0xFFFFFF)
        }

        displayList.drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, entry ->
            val y = startY + i * rowHeight
            val origIndex = entries.indexOf(entry)
            val slotX = leftX + 2
            val slotY = y + 2
            if (entry.type == "POKEMON") {
                val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
                context.matrices.push()
                context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
                context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
                context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
                context.matrices.pop()
                if (iconData.containsKey(origIndex)) {
                    renderPokemonIcon(context, origIndex, slotX, slotY, iconSize)
                }
                // 图标链 + 属性色名称 + 金色闪光星标（照搬 SellSelectScreen 风格，右侧让位价格/倒计时）
                var sx = leftX + 28
                val ballItem = entry.extraData["ballItem"]
                if (!ballItem.isNullOrEmpty()) {
                    Identifier.tryParse(ballItem)?.let { ballId ->
                        val bi = Registries.ITEM.get(ballId)
                        com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                            itemStack = ItemStack(bi), x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                    }
                    sx += 12
                }
                val primaryType = entry.extraData["primaryType"] ?: ""
                val tc = typeColor(if (primaryType.isNotEmpty()) primaryType else "cobblemon.type.normal")
                val name = com.shusheng.cobblemarket.util.TextUtil.truncateString(displayName(entry), 44)
                context.drawText(textRenderer, name, sx, y + 7, tc, false)
                sx += textRenderer.getWidth(name)
                if (entry.shiny) {
                    context.drawText(textRenderer, "★", sx + 2, y + 7, GOLD_COLOR, false)
                    sx += 2 + textRenderer.getWidth("★")
                }
                sx += 2
                val gender = entry.extraData["gender"]
                if (gender == "MALE" || gender == "FEMALE") {
                    val gi = Identifier.of("cobblemon", if (gender == "MALE") "textures/gui/pc/gender_icon_male.png" else "textures/gui/pc/gender_icon_female.png")
                    com.cobblemon.mod.common.api.gui.blitk(matrixStack = context.matrices, texture = gi, x = sx, y = y + 7, width = 6, height = 8)
                    sx += 8
                }
                // 持有物图标（紧跟性别）
                val heldItemId = entry.extraData["heldItemId"].orEmpty()
                if (heldItemId.isNotEmpty()) {
                    Identifier.tryParse(heldItemId)?.let { heldId ->
                        val heldItem = Registries.ITEM.get(heldId)
                        if (heldItem != Registries.ITEM.get(Identifier.of("minecraft", "air"))) {
                            com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                                itemStack = ItemStack(heldItem), x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                            sx += 12
                        }
                    }
                }
            } else {
                Identifier.tryParse(entry.species)?.let { id ->
                    context.drawItem(ItemStack(Registries.ITEM.get(id)), slotX + 2, slotY)
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

            // 价格左侧锤子图标：默认静止，警告声到达时切换敲击状态约 0.3 秒（视觉联动）
            val hammerTex = if ((hammerHitUntil[entry.id] ?: 0L) > System.currentTimeMillis())
                Identifier.of("cobblemarket", "textures/gui/auction_gavel_left.png")
            else
                Identifier.of("cobblemarket", "textures/gui/auction_gavel_left_no.png")
            context.matrices.push()
            context.matrices.translate((priceX - 16).toDouble(), (y + 6).toDouble(), 0.0)
            context.matrices.scale(0.5f, 0.5f, 1f)
            context.drawTexture(hammerTex, 0, 0, 0f, 0f, 24, 24, 24, 24)
            context.matrices.pop()

            context.drawTextWithShadow(textRenderer, priceStr, priceX, y + 7, 0x55FFFF)
            if (bidPart.isNotEmpty()) {
                context.drawTextWithShadow(textRenderer, bidPart,
                    priceX + textRenderer.getWidth(priceStr), y + 7, 0xAAAAAA)
            }

            // 结束倒计时（右移让位给行内图标链），左侧画卖家头像（照搬精灵市场）
            val remaining = formatRemaining(entry.endsAt)
            val remainingColor = if (entry.endsAt - System.currentTimeMillis() < 5 * 60 * 1000) 0xFF6666 else 0xAAAAAA
            val remW = textRenderer.getWidth(remaining)
            context.drawTextWithShadow(textRenderer, remaining,
                leftX + 156 - remW, y + 7, remainingColor)
            drawSellerAvatar(context, entry.sellerUuid, entry.sellerName, leftX + 156 - remW - 20, y + 4, 16)

            // 我的 tab 标记（价格右侧）
            if (currentTab == 2 && isMine(entry)) {
                context.drawTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.auction.mine_mark").string,
                    leftX + 242, y + 7, 0x55FF55)
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

        // 结算中的条目过期（1.5 秒）后从列表移除
        val pollNow = System.currentTimeMillis()
        val settledExpired = settlingUntil.filterValues { it <= pollNow }.keys
        if (settledExpired.isNotEmpty()) {
            entries = entries.filterNot { it.id in settledExpired }
            settledExpired.forEach { settlingUntil.remove(it) }
            rebuildBidButtons()
        }

        // 有到期未结算的拍卖时，每秒重拉一次列表触发服务端结算（停在拍卖场也能自动结算）；
        // 结算中的条目排除（已结算完毕，重拉会全量替换、截断落槌动画）
        if (entries.any { it.endsAt <= pollNow && it.id !in settlingUntil } && pollNow - lastSettlePoll > 1000) {
            lastSettlePoll = pollNow
            ClientPlayNetworking.send(RequestAuctionListPayload())
        }


        // 规则按钮悬停：自绘规则面板
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

        val lines = mutableListOf<Pair<Text?, Int>>()
        lines.add(EntryBadgeRenderer.nameWithShinyStar(displayName(entry), entry.shiny)
            .copy().append(Text.literal("  Lv.${entry.level}")) to 0xFFFFFF)
        lines.add(Text.literal("${Text.translatable("cobblemarket.gui.tooltip_type").string}$typeText") to 0xFFFFFF)
        lines.add(Text.literal("${Text.translatable("cobblemarket.gui.tooltip_nature").string}${Text.translatable(extra["nature"] ?: "").string}  ${Text.translatable("cobblemarket.gui.tooltip_ability").string}${Text.translatable(extra["ability"] ?: "").string}") to 0xFFFFFF)
        val heldItemId = extra["heldItemId"].orEmpty()
        val hasHeldItem = heldItemId.isNotEmpty() &&
            Identifier.tryParse(heldItemId)?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true
        var heldItemLine = -1
        if (hasHeldItem) {
            heldItemLine = lines.size
            lines.add(Text.translatable("cobblemarket.gui.tooltip_held") to 0xFFFFFF)
        }
        lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs") to 0xFFFFFF)
        lines.add(Text.literal("  $hp:${extra["ivsHp"]}") to 0x66FF66); lines.add(Text.literal("  $atk:${extra["ivsAtk"]}") to 0xFF6666)
        lines.add(Text.literal("  $def:${extra["ivsDef"]}") to 0xFFCC66); lines.add(Text.literal("  $spa:${extra["ivsSpAtk"]}") to 0x6699FF)
        lines.add(Text.literal("  $spd:${extra["ivsSpDef"]}") to 0x66FF99); lines.add(Text.literal("  $spe:${extra["ivsSpd"]}") to 0xFF99FF)
        // 分割线：上方精灵信息，下方拍卖信息
        lines.add(null to 0xFFFFFF)
        lines.add(Text.literal("${Text.translatable("cobblemarket.auction.seller").string}: ${entry.sellerName}") to 0xFFFFFF)
        val priceLine = Text.literal("${Text.translatable("cobblemarket.auction.current_price").string}: ${displayPriceText(entry)}")
        lines.add((if (entry.bidCount > 0)
            priceLine.append(Text.literal("  ×${entry.bidCount}").formatted(Formatting.GRAY))
        else priceLine) to 0x55FFFF)
        lines.add(Text.literal("${Text.translatable("cobblemarket.auction.ends").string}: ${formatRemaining(entry.endsAt)}") to 0xFFFFFF)
        if (entry.currentBidderName.isNotEmpty()) {
            lines.add(Text.literal("${Text.translatable("cobblemarket.auction.leader").string}: ${entry.currentBidderName}") to 0xFFDD66)
        }
        if (isMine(entry)) {
            lines.add(Text.literal(Text.translatable("cobblemarket.auction.mine_mark").string) to 0x55FF55)
        }

        var mw = 0; lines.forEach { it.first?.let { t -> mw = maxOf(mw, textRenderer.getWidth(t)) } }
        if (heldItemLine >= 0) {
            mw = maxOf(mw, textRenderer.getWidth(lines[heldItemLine].first!!) + 14)
        }
        val pad = 4
        val tx = minOf(mouseX + 12, width - mw - 12)
        val th = lines.size * 10 + pad
        val ty = if (mouseY - th - 4 <= 0) minOf(mouseY + 12, height - th) else mouseY - th - 4

        context.matrices.push(); context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - pad, ty - pad, mw + 2 * pad, lines.size * 10 + 2 * pad, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, (line, color) ->
            if (line == null) {
                // 分割线行
                context.fill(tx, ty + i * 10 + 4, tx + mw, ty + i * 10 + 5, 0xFF555555.toInt())
            } else if (i == heldItemLine) {
                context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
                Identifier.tryParse(heldItemId)?.let { heldId ->
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

    // 物品悬停信息（简洁版；当前价用货币蓝，与全界面统一）
    private fun renderItemTooltip(context: DrawContext, entry: AuctionEntry, mouseX: Int, mouseY: Int) {
        val lines = mutableListOf<Pair<Text, Int>>()
        lines.add(Text.literal(displayName(entry)) to 0xFFFFFF)
        lines.add(Text.literal("${Text.translatable("cobblemarket.auction.seller").string}: ${entry.sellerName}") to 0xFFFFFF)
        val priceLine = Text.literal("${Text.translatable("cobblemarket.auction.current_price").string}: ${displayPriceText(entry)}")
        lines.add((if (entry.bidCount > 0)
            priceLine.append(Text.literal("  ×${entry.bidCount}").formatted(Formatting.GRAY))
        else priceLine) to 0x55FFFF)
        lines.add(Text.literal("${Text.translatable("cobblemarket.auction.ends").string}: ${formatRemaining(entry.endsAt)}") to 0xFFFFFF)
        if (entry.currentBidderName.isNotEmpty()) {
            lines.add(Text.literal("${Text.translatable("cobblemarket.auction.leader").string}: ${entry.currentBidderName}") to 0xFFDD66)
        }
        if (isMine(entry)) {
            lines.add(Text.literal(Text.translatable("cobblemarket.auction.mine_mark").string) to 0x55FF55)
        }

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

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    // ── 交互 ──

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
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

    override fun shouldPause() = false
}
