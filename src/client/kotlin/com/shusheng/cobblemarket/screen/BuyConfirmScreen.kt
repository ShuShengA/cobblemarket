package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.network.BuyFromMarketPayload
import com.shusheng.cobblemarket.network.ListingEntry
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.joml.Quaternionf

class BuyConfirmScreen(private val entry: ListingEntry) : Screen(Text.translatable("cobblemarket.buy_confirm.title")) {

    private var renderable: RenderablePokemon? = null
    private var displayName = ""
    private val state = FloatingState()

    override fun init() {
        super.init()
        val centerX = width / 2

        // Build renderable for 3D icon
        val id = Identifier.tryParse(entry.speciesId)
        if (id != null) {
            val species = PokemonSpecies.getByIdentifier(id)
            if (species != null) {
                displayName = com.shusheng.cobblemarket.util.SpeciesText.displayName(species)
                // 用挂单真实 aspects（含性别形态），否则雌性爱管侍会渲染成默认雄性模型
                val aspects = entry.aspects.toMutableSet()
                if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
                renderable = RenderablePokemon(species, aspects, ItemStack.EMPTY)
            }
        }

        val btnW = 100
        val btnH = 22
        val gap = 10
        val btnY = height - 36

        addDrawableChild(TextureButton(
            centerX - btnW - gap / 2, btnY, btnW, btnH,
            Text.translatable("cobblemarket.buy_confirm.confirm"),
            { confirm() }
        ))

        addDrawableChild(TextureButton(
            centerX + gap / 2, btnY, btnW, btnH,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { client?.setScreen(MarketScreen()) }
        ))
    }

    private fun confirm() {
        ClientPlayNetworking.send(BuyFromMarketPayload(entry.id))
        client?.setScreen(MarketScreen())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val centerX = width / 2

        // Title
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.buy_confirm.title").formatted(Formatting.GOLD),
            centerX, 14, 0xFFFFFF)

        // 3D icon
        val iconSize = 30
        val iconY = 26
        renderable?.let { rp ->
            val matrices = context.matrices
            matrices.push()
            matrices.translate(centerX.toDouble(), (iconY + iconSize / 2).toDouble(), 0.0)
            matrices.scale(iconSize / 25f * 2.5f, iconSize / 25f * 2.5f, 1f)
            drawProfilePokemon(
                renderablePokemon = rp, matrixStack = matrices,
                rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                state = state, partialTicks = 0f, scale = 4.5f
            )
            matrices.pop()
        }

        val infoY = iconY + iconSize + 8

        // 完整信息行（与市场列表悬停 tooltip 结构一致）：名字★Lv / 类型 / 性格特性 / 携带物 / IV / 卖家 / 价格
        val name = EntryBadgeRenderer.nameWithShinyStar(
            if (displayName.isNotEmpty()) displayName else entry.species, entry.shiny)
        EntryBadgeRenderer.drawInfoLines(context, entry, name, centerX, infoY)
    }

    override fun shouldPause() = false
}
