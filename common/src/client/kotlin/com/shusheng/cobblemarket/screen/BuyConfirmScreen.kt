package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.client.requestCreditInfo
import com.shusheng.cobblemarket.network.BuyFromMarketPayload
import com.shusheng.cobblemarket.network.CreditInfoPayload
import com.shusheng.cobblemarket.network.ListingEntry
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.joml.Quaternionf

class BuyConfirmScreen(private val entry: ListingEntry) : Screen(Text.translatable("cobblemarket.buy_confirm.title")) {

    private var renderable: RenderablePokemon? = null
    private var displayName = ""
    private val state = FloatingState()

    // ── 喵喵支付（消费贷，批次 4）：底部两行按钮 + 展开三档方案 ──
    private var plans = listOf<Pair<Int, Double>>()
    private var selectedPlan = 0
    private var payModeOpen = false
    /** 第一行按钮 y（init 按信息区高度算好，分期方案行跟随其上移） */
    private var buttonRow1Y = 0
    private val planButtons = mutableListOf<TextureButton>()
    private var payButton: TextureButton? = null

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
        // 按钮位置按信息区实际内容算：内容短时贴底（height - 58），内容长（证章/球种/携带物）时整体下移，
        // 不再与价格行重叠。信息区起始 y = 26 + 30 + 8，13 基础行 + 球种/携带物行 + 证章区块。
        // 加新信息行（如技能）时只改这里的 13。
        val infoRows = 13 +
            (if (entry.ball.isNotEmpty()) 1 else 0) +
            (if (EntryBadgeRenderer.hasHeldItemLine(entry)) 1 else 0)
        val contentBottom = 64 + infoRows * 10 + EntryBadgeRenderer.marksBlockHeight(entry.marks)
        val row1Y = maxOf(height - 58, contentBottom + 10)
        val row2Y = row1Y + 24
        buttonRow1Y = row1Y

        addDrawableChild(TextureButton(
            centerX - btnW - gap / 2, row1Y, btnW, btnH,
            Text.translatable("cobblemarket.buy_confirm.confirm"),
            { confirm() }
        ))

        addDrawableChild(TextureButton(
            centerX + gap / 2, row1Y, btnW, btnH,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { client?.setScreen(MarketScreen()) }
        ))

        // 喵喵支付（点开前）/ 确认支付（点开后）
        payButton = TextureButton(
            centerX - btnW - gap / 2, row2Y, btnW, btnH,
            payLabel(),
            { togglePay() }
        )
        addDrawableChild(payButton)

        rebuildPlanButtons()

        // 拉分期方案（CreditInfoPayload.plans；服务端回发后方案按钮才有内容）
        requestCreditInfo()
    }

    private fun payLabel(): Text = Text.translatable(
        if (payModeOpen) "cobblemarket.buy_confirm.confirm_pay" else "cobblemarket.buy_confirm.meowth_pay"
    )

    private fun togglePay() {
        if (!payModeOpen) {
            payModeOpen = true
            rebuildPlanButtons()
            payButton?.setMessage(payLabel())
        } else {
            sendToServer(BuyFromMarketPayload(entry.id, selectedPlan))
            client?.setScreen(MarketScreen())
        }
    }

    private fun confirm() {
        sendToServer(BuyFromMarketPayload(entry.id, -1))
        client?.setScreen(MarketScreen())
    }

    /** 方案按钮行：展开时显示（84×16 并排 4px 间隙，选中档前缀 ▶，照 LoanScreen） */
    private fun rebuildPlanButtons() {
        planButtons.forEach(::remove)
        planButtons.clear()
        if (!payModeOpen) return
        val startX = width / 2 - 130
        // 方案行在第一行按钮上方（按钮高 22 + 4px 间隙 = 26）
        val y = buttonRow1Y - 26
        plans.forEachIndexed { i, (periods, fee) ->
            val btn = TextureButton(
                startX + i * 88, y, 84, 16,
                Text.literal(planLabel(periods, fee, i == selectedPlan)),
                {
                    selectedPlan = i
                    planButtons.forEachIndexed { j, b ->
                        b.setMessage(Text.literal(planLabel(plans[j].first, plans[j].second, j == selectedPlan)))
                    }
                }
            )
            planButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    private fun planLabel(periods: Int, feeRate: Double, selected: Boolean): String {
        val base = Text.translatable("cobblemarket.loan.plan_btn", periods, planPercentText(feeRate)).string
        return if (selected) "▶$base" else base
    }

    /** 费率百分比文本（0.005 → 0.5%；截断一位小数，自实现避开 Locale，照 LoanScreen） */
    private fun planPercentText(feeRate: Double): String {
        val tenths = Math.round(feeRate * 1000)
        return if (tenths % 10 == 0L) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"
    }

    /** 分期方案快照（进入弹窗时拉取） */
    fun onCreditInfo(payload: CreditInfoPayload) {
        val parsed = payload.plans.split(',')
            .mapNotNull { part ->
                val seg = part.trim().split(':')
                if (seg.size != 2) return@mapNotNull null
                val p = seg[0].trim().toIntOrNull()?.coerceAtLeast(1)
                val r = seg[1].trim().toDoubleOrNull()?.coerceIn(0.0, 1.0)
                if (p != null && r != null) p to r else null
            }
        if (parsed.isNotEmpty() && parsed != plans) {
            plans = parsed
            selectedPlan = selectedPlan.coerceIn(0, plans.size - 1)
            if (payModeOpen) rebuildPlanButtons()
        }
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
            // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
            val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
            drawProfilePokemon(
                renderablePokemon = rp, matrixStack = matrices,
                rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                state = state, partialTicks = if (useFloat) delta else 0f, scale = 4.5f
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
