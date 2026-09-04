package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.network.CreditInfoPayload
import com.shusheng.cobblemarket.network.RequestCreditInfoPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/**
 * 喵喵银行：金融系统入口界面。
 * 背景与入口界面同锚定公式（背景顶 bgTop 随窗口/OP 行数动态），切换界面背景不跳动；
 * 全部元素按背景顶相对偏移布局（原「背景垂直居中」方案比入口低 21px，2026-09-02 对齐）。
 * 标题下「可用额度/当前欠款」信息行 → 应急贷款按钮（LoanScreen）→ 还款柜台按钮（RepayScreen）；
 * 底部左「借款历史」/ 右「全部借款历史」（仅 OP）→ LoanHistoryScreen。
 * 进入界面时拉取额度信息（RequestCreditInfoPayload）。
 */
class MeowthBankScreen : Screen(Text.translatable("cobblemarket.meowth_bank.title")) {

    private var backButton: NineSliceButton? = null
    private var rulesButton: NineSliceButton? = null
    // 初始读全局缓存（60 秒兜底轮询写入）秒显不闪；-1 = 未拉取，响应到达后更新
    private var limit = com.shusheng.cobblemarket.client.FinanceCache.creditLimit
    private var debt = com.shusheng.cobblemarket.client.FinanceCache.creditDebt
    private var infoLoaded = false

    /**
     * 背景顶（照 MarketEntryScreen.bgBottom 同款锚定公式：普通 2 行 / OP 3 行 → 背景位置与入口完全一致）
     */
    private fun bgTop(): Int {
        val isAdmin = client?.player?.hasPermissionLevel(2) == true
        val rowCount = if (isAdmin) 3 else 2
        val btnH = 24
        val gap = 8
        val totalH = btnH * rowCount + gap * (rowCount - 1) - if (isAdmin) gap - 5 else 0
        val startY = maxOf(height / 2 - totalH / 2, 47 + (160 - totalH) / 2)
        return startY - 14 - (160 - totalH) / 2 - 33
    }

    override fun init() {
        super.init()
        val bgTop = bgTop()

        // 返回按钮：右边缘与「全部借款历史」按钮右边缘对齐
        // （全部借款历史：x=width/2+25 宽 80 → 右边缘 width/2+105；返回按钮宽 50 → x=width/2+55）
        backButton = NineSliceButton(
            width / 2 + 55, bgTop + 47, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) }
        )
        addDrawableChild(backButton)

        // 规则按钮：返回按钮下方 4px（悬停显示借款规则与后果面板，照拍卖场规则按钮）
        rulesButton = NineSliceButton(
            width / 2 + 55, bgTop + 67, 50, 16,
            Text.translatable("cobblemarket.meowth_bank.rules"),
            { }
        )
        addDrawableChild(rulesButton)

        // 应急贷款入口（信息组：额度/欠款两行 + 按钮组成；100×16；批次 7.5 整体上移给存款按钮腾位）
        addDrawableChild(NineSliceButton(
            width / 2 - 50, bgTop + 96, 100, 16,
            Text.translatable("cobblemarket.loan.title"),
            { client?.setScreen(LoanScreen()) }
        ))

        // 还款柜台入口（应急贷款下方 4px；100×16）→ RepayScreen
        addDrawableChild(NineSliceButton(
            width / 2 - 50, bgTop + 116, 100, 16,
            Text.translatable("cobblemarket.repay.button"),
            { client?.setScreen(RepayScreen()) }
        ))

        // 存款/取款入口（还款柜台下方 4px；100×16）→ DepositScreen
        // （补发紫卡按钮已移除：后续功能换别的入口，服务端补发协议保留待用）
        addDrawableChild(NineSliceButton(
            width / 2 - 50, bgTop + 136, 100, 16,
            Text.translatable("cobblemarket.deposit.button"),
            { client?.setScreen(DepositScreen()) }
        ))

        // 借款历史（左下）：所有玩家可见 → 我的借贷流水
        // （右移 3px：背景贴图内部边框不对称（左 17px/右 13px），按视觉边框对齐两边各 9px）
        addDrawableChild(NineSliceButton(
            width / 2 - 102, bgTop + 164, 80, 16,
            Text.translatable("cobblemarket.meowth_bank.loan_history"),
            { client?.setScreen(LoanHistoryScreen(showAll = false)) }
        ))

        // 全部借款历史（右下）：仅 OP → 全服借贷流水审计
        if (client?.player?.hasPermissionLevel(2) == true) {
            addDrawableChild(NineSliceButton(
                width / 2 + 25, bgTop + 164, 80, 16,
                Text.translatable("cobblemarket.meowth_bank.all_loan_history"),
                { client?.setScreen(LoanHistoryScreen(showAll = true)) }
            ))
        }

        if (!infoLoaded) {
            sendToServer(RequestCreditInfoPayload())
            infoLoaded = true
        }
    }

    /** 额度信息快照（进入界面时拉取） */
    fun onCreditInfo(payload: CreditInfoPayload) {
        limit = payload.limit
        debt = payload.debt
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 背景 256×213，顶边与入口界面同锚定公式（切换界面背景不跳动）
        val bg = Identifier.of("cobblemarket", "textures/gui/meowth_bank_background.png")
        context.drawTexture(bg, width / 2 - 128, bgTop(), 0f, 0f, 256, 213, 256, 213)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val bgTop = bgTop()
        // 标题：背景内顶部边框（27px）下方 4px 处
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.meowth_bank.title").formatted(Formatting.GOLD, Formatting.BOLD),
            width / 2, bgTop + 31, 0xFFFFFF
        )
        // 信息组：「可用额度 / 当前欠款」两行（价格+货币名照全模组规矩用蓝色）；
        // 缓存未拉取（-1）时按 0 显示（秒显优先，响应到达即更新）；批次 7.5 上移 10px 给存款按钮腾位
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan.limit_line", formatPriceLong(limit.coerceAtLeast(0)), inlineCurrencyUnit()),
            width / 2, bgTop + 71, 0x55FFFF
        )
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan.debt_line", formatPriceLong(debt.coerceAtLeast(0)), inlineCurrencyUnit()),
            width / 2, bgTop + 79, 0x55FFFF
        )

        // 紫卡持有者：左侧展示旋转的紫卡（物品落地默认动画，绕 Y 轴每 2 秒一圈）
        if (com.shusheng.cobblemarket.client.FinanceCache.hasPurpleCard) {
            val cardItem = net.minecraft.registry.Registries.ITEM.get(
                net.minecraft.util.Identifier.of("cobblemarket", "meowth_purple_card")
            )
            if (cardItem != net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of("minecraft", "air"))) {
                val cardSize = 24
                val cardX = width / 2 - 96
                val cardY = bgTop + 92
                val angle = (System.currentTimeMillis() % 2000) / 2000f * 360f
                context.matrices.push()
                context.matrices.translate((cardX + cardSize / 2).toDouble(), (cardY + cardSize / 2).toDouble(), 100.0)
                context.matrices.multiply(org.joml.Quaternionf().rotateY(Math.toRadians(angle.toDouble()).toFloat()))
                context.matrices.translate(-(cardX + cardSize / 2).toDouble(), -(cardY + cardSize / 2).toDouble(), 0.0)
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = net.minecraft.item.ItemStack(cardItem),
                    x = cardX.toDouble(), y = cardY.toDouble(), scale = 1.5,
                    matrixStack = context.matrices
                )
                context.matrices.pop()
            }
        }

        // 规则按钮悬停面板（照拍卖场规则面板：自绘 + 悬停位置自适应）
        if (rulesButton?.isHovered == true) {
            renderRulesPanel(context, mouseX, mouseY)
        }
    }

    /** 借款规则与后果面板（照拍卖场规则面板；每条 = 红色关键字 + 白色正文，正文超宽换行） */
    private fun renderRulesPanel(context: DrawContext, mx: Int, my: Int) {
        val maxTextWidth = 280
        val pad = 4
        val lineH = 10
        val dividerH = 4
        // 每条 = (红段 warn, 白段 text)；text 按「面板宽 − warn 宽 − 4」逐字符填满换行（wrapTip 中文友好断行）
        val rules = (1..7).map { i ->
            Text.translatable("cobblemarket.meowth_bank.rule.${i}_warn").string to
                Text.translatable("cobblemarket.meowth_bank.rule.${i}_text").string
        }
        val wrappedTexts = rules.map { (warn, text) ->
            val warnW = textRenderer.getWidth(warn)
            wrapRuleText(text, maxTextWidth - warnW - 4)
        }
        val totalLines = wrappedTexts.sumOf { maxOf(1, it.size) }
        val panelW = maxTextWidth + 2 * pad
        val panelH = totalLines * lineH + (rules.size - 1) * dividerH + 2 * pad
        val tx = minOf(mx + 12, width - panelW - 12)
        val ty = if (my - panelH - 4 <= 0) minOf(my + 12, height - panelH) else my - panelH - 4

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx, ty, panelW, panelH, 1, ROW_BACKGROUND_TEX_H)
        var y = ty + pad
        rules.forEachIndexed { ri, (warn, _) ->
            if (ri > 0) {
                context.fill(tx + pad, y, tx + panelW - pad, y + 1, 0xFF555555.toInt())
                y += dividerH
            }
            val warnW = textRenderer.getWidth(warn)
            if (warn.isNotEmpty()) {
                context.drawTextWithShadow(textRenderer, warn, tx + pad, y, 0xFF5555)
            }
            wrappedTexts[ri].forEachIndexed { li, line ->
                val lx = if (li == 0 && warn.isNotEmpty()) tx + pad + warnW + 4 else tx + pad
                context.drawTextWithShadow(textRenderer, line, lx, y, 0xFFFFFF)
                y += lineH
            }
        }
        context.matrices.pop()
    }

    /**
     * 规则面板断行：按空格分词（英文友好），超长词（中文整串无空格）逐字符硬断填满。
     * wrapTip（气泡专用，空格当普通字符）不适用英文长句——整句当一个词会溢出面板。
     */
    private fun wrapRuleText(text: String, maxW: Int): List<net.minecraft.text.OrderedText> {
        val result = mutableListOf<net.minecraft.text.OrderedText>()
        val sb = StringBuilder()
        fun width(): Int = textRenderer.getWidth(sb.toString())
        fun flush() {
            if (sb.isNotEmpty()) {
                result.add(Text.literal(sb.toString()).asOrderedText())
                sb.setLength(0)
            }
        }
        for (token in text.split(' ')) {
            if (token.isEmpty()) continue
            val tokenW = textRenderer.getWidth(token)
            if (tokenW > maxW) {
                // 超长词（中文整串）：逐字符硬断填满
                flush()
                var cur = token
                while (cur.isNotEmpty()) {
                    var cut = cur.length
                    while (cut > 1 && textRenderer.getWidth(cur.substring(0, cut)) > maxW) cut--
                    result.add(Text.literal(cur.substring(0, cut)).asOrderedText())
                    cur = cur.substring(cut)
                }
                continue
            }
            if (sb.isNotEmpty() && width() + textRenderer.getWidth(" ") + tokenW > maxW) flush()
            sb.append(if (sb.isEmpty()) token else " $token")
        }
        flush()
        return result
    }
    override fun shouldPause() = false
}
