package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.shusheng.cobblemarket.client.icon.PreviewStateHolder
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.client.requestCreditInfo
import com.shusheng.cobblemarket.network.BuyFromMarketPayload
import com.shusheng.cobblemarket.network.CreditInfoPayload
import com.shusheng.cobblemarket.client.heldIconStack
import com.shusheng.cobblemarket.client.heldIconStackForModel
import com.shusheng.cobblemarket.network.ListingEntry
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

class BuyConfirmScreen(private val entry: ListingEntry) : Screen(Text.translatable("cobblemarket.buy_confirm.title")) {

    private var renderable: RenderablePokemon? = null
    private var displayName = ""
    private val state = PreviewStateHolder()

    // ── 喵喵支付（消费贷，批次 4）：底部两行按钮 + 展开三档方案 ──
    private var plans = listOf<Pair<Int, Double>>()
    private var selectedPlan = 0
    private var payModeOpen = false

    /** 信息区滚动偏移（2026-09-20）：内容比可视区高时靠滚轮看（照求购单创建弹窗那套） */
    private var infoScroll = 0
    /** 信息区顶（标题 14 + 图标 26+30 + 8）与底部按钮区高度 */
    private val infoTop = 64
    private val infoBottomH = 58

    /**
     * 信息分割线宽 = 本界面内容栏宽（260，见 `startX = width / 2 - 130`）减去左右各 10。
     *
     * ⚠ **证章一行放几个也按它算**（见 [EntryBadgeRenderer.marksBlockHeight]）—— 两处必须**同源**：
     *   不同源就会出现「分割线比图标行长一截、明明还能多放几个」或反过来（用户 2026-09-27 报的正是这种）。
     */
    private val INFO_DIVIDER_W = 240
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
                renderable = RenderablePokemon(species, aspects, heldIconStackForModel(entry.heldItemVisible, entry.heldItemId, entry.heldItemNbt))
            }
        }

        val btnW = 100
        val btnH = 22
        val gap = 10
        // 按钮**永远贴底**（height − 58）：内容再长也不再被往下推（原先是 maxOf(height-58, 内容底+10)，
        // 内容一长按钮就被推出屏幕外点不到）。放不下的信息行改由**信息区滚动**看，见 render 里的 scissor。
        // 信息区起始 y = 26 + 30 + 8 = 64（13 基础行 + 球种/携带物行 + 技能块 + 证章区块）。
        val row1Y = height - infoBottomH
        val row2Y = row1Y + 24
        buttonRow1Y = row1Y
        infoScroll = 0

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
        IconRegistry.beginFrame(mouseX, mouseY)
        super.render(context, mouseX, mouseY, delta)
        val centerX = width / 2

        // Title
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.buy_confirm.title").formatted(Formatting.GOLD),
            centerX, 14, 0xFFFFFF)

        // 3D icon（尺寸与位置不变；**实际绘制挪到信息区 scissor 关闭之后**，见下方）
        val iconSize = 30
        val iconY = 26

        val infoY = iconY + iconSize + 8

        // 完整信息行（与市场列表悬停 tooltip 结构一致）：名字★Lv / 类型 / 性格特性 / 携带物 / IV / 技能 / 证章 / 卖家 / 价格
        // 内容比可视区高时**可滚动**（scissor 裁剪 + 起始 y 减偏移），按钮始终贴底够得着
        val name = EntryBadgeRenderer.nameWithShinyStar(
            if (displayName.isNotEmpty()) displayName else entry.species, entry.shiny)
        val viewTop = infoTop
        val viewBottom = height - infoBottomH - 8
        infoScroll = infoScroll.coerceIn(0, infoMaxScroll())
        context.enableScissor(0, viewTop, width, viewBottom)
        // 分割线宽 = 本界面内容栏宽（260，见 startX = width/2 - 130）减去左右各 10
        EntryBadgeRenderer.drawInfoLines(
            context, entry, name, centerX, infoY - infoScroll, dividerW = INFO_DIVIDER_W,
            // 这是整页界面（不是跟着鼠标的悬停窗）⇒ 鼠标能停到证章行上，接二级窗
            mouseX = mouseX, mouseY = mouseY
        )
        EntryBadgeRenderer.drawMarksMoreTooltip(context)
        context.disableScissor()

        // ⚠ 精灵 3D 模型画在**信息区 scissor 关闭之后**（2026-10-03）：
        //   ⚠ 真正解决「模型缺一块 / 尾巴被切」的是 PokemonIconRenderer 里的**清深度缓冲**；
        //     这里让模型在 scissor 之外绘制只是额外保险 —— 实测单独调顺序**并不能**解决问题，
        //     别把它当成那个 bug 的修法。模型本就在最上层，放到这里不影响观感。
        renderable?.let { rp ->
            // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
            val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
            // 整屏页原先既无裁剪、纵向锚点也取矩形中心（其余界面是 +1），两处都照旧保留
            PokemonIconRenderer.draw(
                context = context,
                renderable = rp,
                state = state.stateFor(rp),
                x = centerX - iconSize / 2, y = iconY, size = iconSize,
                delta = delta,
                animate = useFloat,
                useScissor = false,
                centerAnchorY = true
            )
            // ⚠ 强制 flush（2026-10-03）：3D 模型的绘制会拖到帧末，而那时 scissor 可能已被
            //   信息区的滚动裁剪改掉 ⇒ 模型被切掉一条直线。这里让它**立即**画出来。
            context.draw()
        }
    }

    /** 信息区内容高（设计值，与 init 里那套算法同源） */
    private fun infoContentH(): Int {
        // 「有才显示」的行数走**共用入口**（别在这自己加，加行时漏改的就是它）
        val infoRows = 13 + EntryBadgeRenderer.optionalRowCount(entry)
        return infoRows * 10 +
            EntryBadgeRenderer.movesBlockHeight(entry.moves) +
            // 证章一行放几个跟随可用宽（本界面固定 INFO_DIVIDER_W，与 render 里 drawInfoLines 的 dividerW 同源）
            // ⚠ 证章的可用宽必须是**分割线的实际宽度**（居中弹窗恒为 MOVES_MAX_W）——
            //   原先传 INFO_DIVIDER_W(240) 与实际画的 132 差 108px，证章一多就**溢出弹窗**
            EntryBadgeRenderer.marksBlockHeight(entry.marks, entry.moves, EntryBadgeRenderer.MOVES_MAX_W)
    }

    private fun infoMaxScroll(): Int =
        maxOf(0, infoContentH() - (height - infoBottomH - 8 - infoTop))

    /** 信息区滚动（内容比可视区高时） */
    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val maxScroll = infoMaxScroll()
        if (maxScroll > 0) {
            infoScroll = (infoScroll - verticalAmount.toInt() * 10).coerceIn(0, maxScroll)
        }
        return true
    }

    override fun shouldPause() = false
}
