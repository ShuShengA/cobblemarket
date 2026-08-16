package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.network.ClaimPokemonReturnPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PokemonPreview
import com.shusheng.cobblemarket.network.PokemonReturnDataPayload
import com.shusheng.cobblemarket.network.RequestPokemonReturnPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.joml.Quaternionf

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

    private data class IconData(val renderable: RenderablePokemon, val state: FloatingState)
    private val iconData = mutableMapOf<Int, IconData>()

    private fun getListStartY() = 48
    private fun maxVisible() = maxOf(0, (height - getListStartY() - 72) / rowHeight)

    // 协议分页：服务端按请求的 pageSize 切片，本地只做防溢出截断
    private fun pageItems(): List<PokemonPreview> = pokemon.take(maxOf(0, maxVisible()))

    private fun requestData() {
        val size = minOf(maxVisible(), 30).coerceAtLeast(1)
        ClientPlayNetworking.send(RequestPokemonReturnPayload(currentPage, size))
    }
    private fun prevPage() {
        if (currentPage > 1) { currentPage--; requestData() }
    }
    private fun nextPage() {
        if (currentPage < totalPages) { currentPage++; requestData() }
    }

    private fun buildIconCache() {
        iconData.clear()
        pokemon.forEachIndexed { i, p ->
            val id = Identifier.tryParse(p.speciesId) ?: return@forEachIndexed
            val species = PokemonSpecies.getByIdentifier(id) ?: return@forEachIndexed
            val aspects = mutableSetOf<String>()
            if (p.shiny) aspects.add("shiny")
            iconData[i] = IconData(RenderablePokemon(species, aspects, ItemStack.EMPTY), FloatingState())
        }
    }

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(MarketScreen()) }
        ))

        val listBottom = getListStartY() + maxVisible() * rowHeight
        prevButton = NineSliceButton(leftX, listBottom, 80, 20, Text.translatable("cobblemarket.gui.prev"), { prevPage() })
        addDrawableChild(prevButton)
        claimButton = NineSliceButton(width / 2 - 50, listBottom, 100, 20, Text.translatable("cobblemarket.return.claim"), { claimAll() })
        addDrawableChild(claimButton)
        nextButton = NineSliceButton(leftX + panelWidth - 80, listBottom, 80, 20, Text.translatable("cobblemarket.gui.next"), { nextPage() })
        addDrawableChild(nextButton)

        requestData()
        loaded = true
    }

    private fun claimAll() {
        ClientPlayNetworking.send(ClaimPokemonReturnPayload())
    }

    fun onReturnData(payload: PokemonReturnDataPayload) {
        pokemon = payload.pokemon
        totalPages = payload.totalPages
        currentPage = payload.currentPage
        loaded = true
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

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f, 1f)
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
            drawPanelSlice(context, mid, panelLeft, y)
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

        if (pokemon.isEmpty() && loaded) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.return.pokemon_empty").formatted(Formatting.GRAY),
                centerX, 100, 0xFFFFFF)
        }

        val startY = getListStartY()
        val visible = pageItems()
        val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")

        val startOffset = 0
        visible.forEachIndexed { i, p ->
            val y = startY + i * rowHeight
            val iconX = leftX + 2
            val iconY = y + 2

            context.matrices.push()
            context.matrices.translate(iconX.toDouble(), iconY.toDouble(), 0.0)
            context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
            context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
            context.matrices.pop()

            // iconData 按当前页重建，索引即行号
            renderPokemonIcon(context, startOffset + i, iconX, iconY, iconSize)

            val tc = typeColor(if (p.primaryType.isNotEmpty()) p.primaryType else "cobblemon.type.normal")
            context.drawText(textRenderer, p.species, leftX + 28, y + 7, tc, false)
            if (p.shiny) {
                context.drawText(textRenderer, "★", leftX + 28 + textRenderer.getWidth(p.species) + 2, y + 7, GOLD_COLOR, false)
            }
            context.drawText(textRenderer, "Lv.${p.level}", leftX + 135, y + 7, 0xAAAAAA, false)
        }

        if (hoveredRow in visible.indices) {
            renderTooltip(context, visible[hoveredRow], mouseX, mouseY)
        }

        prevButton?.active = currentPage > 1
        nextButton?.active = currentPage < totalPages
        claimButton?.active = pokemon.isNotEmpty()
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

    private fun renderTooltip(context: DrawContext, p: PokemonPreview, mx: Int, my: Int) {
        val hp = Text.translatable("cobblemon.stat.hp.name").string
        val atk = Text.translatable("cobblemon.stat.attack.name").string
        val def = Text.translatable("cobblemon.stat.defence.name").string
        val spa = Text.translatable("cobblemon.stat.special_attack.name").string
        val spd = Text.translatable("cobblemon.stat.special_defence.name").string
        val spe = Text.translatable("cobblemon.stat.speed.name").string
        val typeText = Text.translatable(p.primaryType).string +
            if (p.secondaryType.isNotEmpty()) " + ${Text.translatable(p.secondaryType).string}" else ""

        val lines = listOf(
            EntryBadgeRenderer.nameWithShinyStar(p.species, p.shiny).copy().append(Text.literal("  Lv.${p.level}")) to 0xFFFFFF,
            Text.literal("${Text.translatable("cobblemarket.gui.tooltip_type").string}$typeText") to 0xFFFFFF,
            Text.literal("${Text.translatable("cobblemarket.gui.tooltip_nature").string}${Text.translatable(p.nature).string}  ${Text.translatable("cobblemarket.gui.tooltip_ability").string}${Text.translatable(p.ability).string}") to 0xFFFFFF,
            Text.translatable("cobblemarket.gui.tooltip_ivs") to 0xFFFFFF,
            Text.literal("  $hp:${p.ivsHp}") to 0x66FF66, Text.literal("  $atk:${p.ivsAtk}") to 0xFF6666,
            Text.literal("  $def:${p.ivsDef}") to 0xFFCC66, Text.literal("  $spa:${p.ivsSpAtk}") to 0x6699FF,
            Text.literal("  $spd:${p.ivsSpDef}") to 0x66FF99, Text.literal("  $spe:${p.ivsSpd}") to 0xFF99FF
        )
        var mw = 0; lines.forEach { mw = maxOf(mw, textRenderer.getWidth(it.first)) }
        val pad = 4
        val tx = minOf(mx + 12, width - mw - 12)
        val th = lines.size * 10 + pad
        val ty = if (my - th - 4 <= 0) minOf(my + 12, height - th) else my - th - 4

        context.matrices.push(); context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - pad, ty - pad, mw + 2 * pad, lines.size * 10 + 2 * pad, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, (line, color) -> context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color) }
        context.matrices.pop()
    }

    override fun shouldPause() = false
}
