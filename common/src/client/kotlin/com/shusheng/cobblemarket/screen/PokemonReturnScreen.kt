package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import net.minecraft.registry.Registries
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.network.ClaimPokemonReturnPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PokemonPreview
import com.shusheng.cobblemarket.network.PokemonReturnDataPayload
import com.shusheng.cobblemarket.network.RequestPokemonReturnPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.joml.Quaternionf
import java.util.UUID

class PokemonReturnScreen : Screen(Text.translatable("cobblemarket.return.title")) {

    private val panelWidth = 296
    private val iconSize = 20
    private val rowHeight = 24

    private var pokemon = listOf<PokemonPreview>()
    private var loaded = false
    private var hoveredRow = -1
    private var currentPage = 1
    private var totalPages = 1
    private var prevButton: NineSliceButton? = null
    private var nextButton: NineSliceButton? = null
    private var claimButton: NineSliceButton? = null

    private data class IconData(
        val renderable: RenderablePokemon,
        val state: FloatingState,
        // 球种/携带物栈随图标缓存：render 每帧 new ItemStack 是分配热点，构建一次复用
        val ballStack: ItemStack?,
        val heldStack: ItemStack?
    )
    private val iconData = mutableMapOf<Int, IconData>()

    private fun getListStartY() = 48
    private fun maxVisible() = maxOf(0, (height - getListStartY() - 72) / rowHeight)

    // 协议分页：服务端按请求的 pageSize 切片，本地只做防溢出截断
    private fun pageItems(): List<PokemonPreview> = pokemon.take(maxOf(0, maxVisible()))

    private fun requestData(resetPending: Boolean = true) {
        lastListRequestAt = System.currentTimeMillis()
        if (resetPending) pendingPage = 0 // 非翻页路径（领取后刷新、拍卖结算等）取消未发出的翻页目标
        val size = minOf(maxVisible(), 30).coerceAtLeast(1)
        sendToServer(RequestPokemonReturnPayload(currentPage, size))
    }

    /** 拍卖结算完成事件：刷新列表（货已进待取回，不用重开界面） */
    fun onAuctionSettled() {
        requestData()
    }

    // 翻页请求在途标志：响应到达前不重复发请求
    private var pageRequestInFlight = false

    // 最近一次列表请求发送时间：补发节奏与服务端节流窗口（request_pokemon_return 500ms）对齐，
    // 窗口内不发——请求若被服务端静默丢弃则无响应，inFlight 会永久卡死按钮
    private var lastListRequestAt = 0L

    // 待翻页目标：点击立即更新页码并把目标页记到这里，tick 在窗口允许时补发请求。
    // 连点合并到最终目标页（中间页不发），点击永远有立即反馈，且请求永不撞节流窗口
    private var pendingPage = 0

    private fun updatePageButtons() {
        prevButton?.active = currentPage > 1
        nextButton?.active = currentPage < totalPages
    }

    // 兜底 + 补发：1s 未收到响应强制复位 inFlight（防锁灰）；窗口允许且无在途时补发翻页目标
    override fun tick() {
        if (pageRequestInFlight && System.currentTimeMillis() - lastListRequestAt > 1000) {
            pageRequestInFlight = false
        }
        // 目标保留到响应确认，请求被丢弃时兜底复位后自动重试
        if (!pageRequestInFlight && pendingPage != 0 && System.currentTimeMillis() - lastListRequestAt >= PAGE_CLICK_INTERVAL_MS) {
            currentPage = pendingPage.coerceIn(1, maxOf(1, totalPages))
            pageRequestInFlight = true
            updatePageButtons()
            requestData(resetPending = false)
        }
    }

    private fun prevPage() {
        if (currentPage <= 1) return
        currentPage--
        pendingPage = currentPage
        updatePageButtons()
    }
    private fun nextPage() {
        if (currentPage >= totalPages) return
        currentPage++
        pendingPage = currentPage
        updatePageButtons()
    }

    // 物种显示名缓存：species 字段是翻译 key，客户端本地翻译（随客户端语言），
    // 翻译缺失时 fallback 到资源名（照搬上架界面的处理）
    private val speciesNameCache = mutableMapOf<String, String>()
    private fun speciesDisplay(p: PokemonPreview): String =
        speciesNameCache.getOrPut(p.species) {
            val t = Text.translatable(p.species).string
            if (t == p.species) p.speciesName else t
        }

    private fun buildIconCache() {
        iconData.clear()
        pokemon.forEachIndexed { i, p ->
            val id = Identifier.tryParse(p.speciesId) ?: return@forEachIndexed
            val species = PokemonSpecies.getByIdentifier(id) ?: return@forEachIndexed
            val aspects = mutableSetOf<String>()
            if (p.shiny) aspects.add("shiny")
            iconData[i] = IconData(
                RenderablePokemon(species, aspects, ItemStack.EMPTY),
                FloatingState(),
                ballStack = Identifier.tryParse(p.ball.removePrefix("item.").replaceFirst(".", ":"))?.let { ItemStack(Registries.ITEM.get(it)) },
                heldStack = buildHeldStack(p.heldItemId)
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

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(MarketScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        ))

        // 按钮放在底部分割线（listBottom+4）与背景底边（height-32）之间居中（照精灵市场分页按钮布局）
        val listBottom = getListStartY() + maxVisible() * rowHeight
        val btnY = (listBottom + 5 + (height - 32)) / 2 - 10 - 5
        prevButton = NineSliceButton(
            leftX, btnY, 80, 20,
            Text.literal(""),
            { prevPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/previous.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.prev")
        )
        addDrawableChild(prevButton)
        updatePageButtons()
        claimButton = NineSliceButton(width / 2 - 50, btnY, 100, 20, Text.translatable("cobblemarket.return.claim"), { claimAll() })
        addDrawableChild(claimButton)
        nextButton = NineSliceButton(
            leftX + panelWidth - 80, btnY, 80, 20,
            Text.literal(""),
            { nextPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/next.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.next")
        )
        addDrawableChild(nextButton)

        requestData()
        loaded = true
    }

    private fun claimAll() {
        sendToServer(ClaimPokemonReturnPayload())
    }

    fun onReturnData(payload: PokemonReturnDataPayload) {
        pageRequestInFlight = false
        pokemon = payload.pokemon
        totalPages = payload.totalPages
        when {
            pendingPage == 0 -> currentPage = payload.currentPage
            // 目标页已到达（或被服务端 clamp 出界）：确认，清目标
            payload.currentPage == pendingPage || pendingPage > payload.totalPages -> {
                pendingPage = 0
                currentPage = payload.currentPage
            }
            // 目标未达（罕见）：保留目标由 tick 继续重试，页码维持乐观值
            else -> {}
        }
        updatePageButtons()
        loaded = true
        tooltipCacheKey = null
        buildIconCache()
    }

    fun onMarketResult(payload: MarketResultPayload) {
        client?.player?.sendMessage(payload.message.copy().formatted(if (payload.success) Formatting.GREEN else Formatting.RED), false)
        currentPage = 1
        requestData()
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
        val visible = pageItems()

        hoveredRow = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY >= startY) {
            val row = (mouseY - startY) / rowHeight
            if (row in visible.indices) hoveredRow = row
        }

        visible.forEachIndexed { i, _ ->
            val rowY = startY + i * rowHeight
            val rowState = if (i == hoveredRow) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, rowState, ROW_BACKGROUND_TEX_H)
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.return.title").formatted(Formatting.GOLD),
            centerX, 20, 0xFFFFFF)

        val tp = totalPages
        if (currentPage > tp) currentPage = tp
        if (currentPage < 1) currentPage = 1
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.gui.page", currentPage, tp).formatted(Formatting.GRAY),
            centerX, 32, 0xFFFFFF)

        val dividerY = 44
        context.fill(leftX, dividerY, leftX + panelWidth, dividerY + 1, 0xFF555555.toInt())
        // 底部分割线：列表最后一行下方 4px（照精灵市场分页布局），按钮在其与背景底边之间居中
        val listBottom = getListStartY() + maxVisible() * rowHeight
        context.fill(leftX, listBottom + 4, leftX + panelWidth, listBottom + 5, 0xFF555555.toInt())

        if (pokemon.isEmpty() && loaded) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.return.pokemon_empty").formatted(Formatting.GRAY),
                centerX, 100, 0xFFFFFF)
        }

        val startY = getListStartY()
        val visible = pageItems()

        val startOffset = 0
        visible.forEachIndexed { i, p ->
            val y = startY + i * rowHeight
            val iconX = leftX + 2
            val iconY = y + 2

            context.matrices.push()
            context.matrices.translate(iconX.toDouble(), iconY.toDouble(), 0.0)
            context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
            context.drawTexture(SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
            context.matrices.pop()

            // iconData 按当前页重建，索引即行号
            renderPokemonIcon(context, startOffset + i, iconX, iconY, iconSize, delta = delta)

            val tc = typeColor(if (p.primaryType.isNotEmpty()) p.primaryType else "cobblemon.type.normal")
            // 图标链：球种 → 名字（属性色）→ 星 → 性别 → 持有物（照搬精灵市场行）
            var sx = leftX + 28
            if (p.ball.isNotEmpty()) {
                iconData[startOffset + i]?.ballStack?.let { ballStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = ballStack, x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                }
                sx += 12
            }
            val name = speciesDisplay(p)
            context.drawTextWithShadow(textRenderer, name, sx, y + 7, tc)
            sx += textRenderer.getWidth(name)
            if (p.shiny) {
                context.drawText(textRenderer, "★", sx + 2, y + 7, GOLD_COLOR, false)
                sx += 2 + textRenderer.getWidth("★")
            }
            sx += 2
            if (p.gender == "MALE" || p.gender == "FEMALE") {
                val gi = if (p.gender == "MALE") GENDER_ICON_MALE else GENDER_ICON_FEMALE
                com.cobblemon.mod.common.api.gui.blitk(matrixStack = context.matrices, texture = gi, x = sx, y = y + 7, width = 6, height = 8)
                sx += 8
            }
            iconData[startOffset + i]?.heldStack?.let { heldStack ->
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = heldStack, x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                sx += 12
            }
            val levelText = Text.translatable("cobblemarket.gui.lv").string + p.level
            context.drawText(textRenderer, levelText, leftX + 135, y + 7, 0x000000, false)
        }

        if (hoveredRow in visible.indices) {
            renderTooltip(context, visible[hoveredRow], hoveredRow, mouseX, mouseY)
        }

        prevButton?.active = currentPage > 1
        nextButton?.active = currentPage < totalPages
        claimButton?.active = pokemon.isNotEmpty()
    }

    private fun renderPokemonIcon(context: DrawContext, index: Int, x: Int, y: Int, size: Int, delta: Float = 0f) {
        val data = iconData[index] ?: return run {
            val tc = 0x88888888.toInt()
            context.fill(x, y, x + size, y + size, tc)
        }
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
                renderablePokemon = data.renderable, matrixStack = matrices,
                rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                state = data.state, partialTicks = if (useFloat) delta else 0f, scale = 4.5f
            )
        } catch (_: Exception) {
        } finally {
            context.disableScissor()
            matrices.pop()
        }
    }

    // tooltip 内容缓存：内容只取决于条目，悬停同一行时每帧重建全部文本行是悬停掉帧主因
    private var tooltipCacheKey: UUID? = null
    private var tooltipCacheLines: List<Pair<Text, Int>> = emptyList()
    private var tooltipCacheHeldLine = -1
    private var tooltipCacheMaxWidth = 0

    private fun renderTooltip(context: DrawContext, p: PokemonPreview, index: Int, mx: Int, my: Int) {
        // 文本行缓存：悬停同一行时内容不变，只在悬停目标变化时重建（见 tooltipCacheKey 注释）
        if (tooltipCacheKey != p.uuid) {
            tooltipCacheKey = p.uuid
            val hp = Text.translatable("cobblemon.stat.hp.name").string
            val atk = Text.translatable("cobblemon.stat.attack.name").string
            val def = Text.translatable("cobblemon.stat.defence.name").string
            val spa = Text.translatable("cobblemon.stat.special_attack.name").string
            val spd = Text.translatable("cobblemon.stat.special_defence.name").string
            val spe = Text.translatable("cobblemon.stat.speed.name").string
            val typeText = Text.translatable(p.primaryType).string +
                if (p.secondaryType.isNotEmpty()) " + ${Text.translatable(p.secondaryType).string}" else ""

            val lines = mutableListOf<Pair<Text, Int>>()
            lines.add(EntryBadgeRenderer.nameWithShinyStar(speciesDisplay(p), p.shiny).copy().append(Text.literal("  Lv.${p.level}")) to typeColor(p.primaryType))
            lines.add(Text.literal(Text.translatable("cobblemarket.gui.tooltip_type").string).append(EntryBadgeRenderer.typeLine(p.primaryType, p.secondaryType)) to 0xFFFFFF)
            lines.add(Text.literal(Text.translatable("cobblemarket.gui.tooltip_nature").string)
                .append(EntryBadgeRenderer.natureText(p.natureBase, p.nature))
                .append(Text.literal("  ${Text.translatable("cobblemarket.gui.tooltip_ability").string}"))
                .append(Text.translatable(p.ability)) to 0xFFFFFF)
            val hasHeldItem = p.heldItemId.isNotEmpty() &&
                Identifier.tryParse(p.heldItemId)?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true
            var heldItemLine = -1
            if (hasHeldItem) {
                heldItemLine = lines.size
                lines.add(Text.translatable("cobblemarket.gui.tooltip_held") to 0xFFFFFF)
            }
            lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs") to 0xFFFFFF)
            lines.add(Text.literal("  $hp:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsHp, p.htHp)}").append(Text.literal("  EV:${p.evsHp}").formatted(Formatting.RED)) to 0x66FF66); lines.add(Text.literal("  $atk:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsAtk, p.htAtk)}").append(Text.literal("  EV:${p.evsAtk}").formatted(Formatting.RED)) to 0xFF6666)
            lines.add(Text.literal("  $def:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsDef, p.htDef)}").append(Text.literal("  EV:${p.evsDef}").formatted(Formatting.RED)) to 0xFFCC66); lines.add(Text.literal("  $spa:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpAtk, p.htSpAtk)}").append(Text.literal("  EV:${p.evsSpAtk}").formatted(Formatting.RED)) to 0x6699FF)
            lines.add(Text.literal("  $spd:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpDef, p.htSpDef)}").append(Text.literal("  EV:${p.evsSpDef}").formatted(Formatting.RED)) to 0x66FF99); lines.add(Text.literal("  $spe:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpd, p.htSpd)}").append(Text.literal("  EV:${p.evsSpd}").formatted(Formatting.RED)) to 0xFF99FF); lines.add(Text.translatable("cobblemarket.gui.friendship", p.friendship) to 0xFF99CC)

            var mw = 0; lines.forEach { mw = maxOf(mw, textRenderer.getWidth(it.first)) }
            if (heldItemLine >= 0) {
                mw = maxOf(mw, textRenderer.getWidth(lines[heldItemLine].first) + 14)
            }
            tooltipCacheLines = lines
            tooltipCacheHeldLine = heldItemLine
            tooltipCacheMaxWidth = mw
        }
        val lines = tooltipCacheLines
        val heldItemLine = tooltipCacheHeldLine
        val mw = tooltipCacheMaxWidth

        val pad = 4
        val tx = minOf(mx + 12, width - mw - 12)
        val th = lines.size * 10 + pad
        val ty = if (my - th - 4 <= 0) minOf(my + 12, height - th) else my - th - 4

        context.matrices.push(); context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - pad, ty - pad, mw + 2 * pad, lines.size * 10 + 2 * pad, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, (line, color) ->
            if (i == heldItemLine) {
                context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
                iconData[index]?.heldStack?.let { heldStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = heldStack,
                        x = tx + textRenderer.getWidth(line) + 2.0,
                        y = ty + i * 10 + 0.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                }
            } else if (i == 0) {
                // 第一行（名字★Lv）带公母图标
                EntryBadgeRenderer.drawNameLineLeft(context, line, p.gender, tx, ty + i * 10, color)
            } else {
                context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
            }
        }
        context.matrices.pop()
    }

    override fun shouldPause() = false

    private companion object {
        val SLOT_TEXTURE = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
        val GENDER_ICON_MALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
        val GENDER_ICON_FEMALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
        /** 补发请求最小间隔：服务端 request_pokemon_return 节流 500ms + 100ms 余量，防网络抖动边界丢弃 */
        const val PAGE_CLICK_INTERVAL_MS = 600L
    }
}
