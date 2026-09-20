package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.client.requestCreditInfo
import com.shusheng.cobblemarket.network.CardHolderBoardEntry
import com.shusheng.cobblemarket.network.CardHolderBoardPayload
import com.shusheng.cobblemarket.network.CreditInfoPayload
import com.shusheng.cobblemarket.network.RequestCardHolderBoardPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.util.TextUtil
import com.mojang.authlib.GameProfile
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.util.UUID

/**
 * 喵喵银行：金融系统入口界面。
 * 背景与入口界面同锚定公式（背景顶 bgTop 随窗口/OP 行数动态），切换界面背景不跳动；
 * 全部元素按背景顶相对偏移布局（原「背景垂直居中」方案比入口低 21px，2026-09-02 对齐）。
 * 标题下「可用额度/当前欠款」信息行 → 应急贷款按钮（LoanScreen）→ 还款柜台按钮（RepayScreen）；
 * 底部左「借款历史」/ 右「全部借款历史」（仅 OP）→ LoanHistoryScreen。
 * 进入界面时拉取额度信息（RequestCreditInfoPayload）。
 */
class MeowthBankScreen : Screen(Text.translatable("cobblemarket.meowth_bank.title")) {

    private companion object {
        /**
         * 卡片与持有者面板的宽度（面板宽度就是照上方卡片定的，二者恒等）。
         * 卡片是 16px 图标按 scale 放大 → scale = CARD_W / 16。
         * ⚠ 上限 128：面板贴在背景外侧、只能往屏幕中心加宽，再加宽会越过左下「借款历史」按钮
         *   （按钮左缘 = width/2 − 102，面板右缘 = width/2 − (CARD_OUTER − CARD_W)）。
         */
        const val CARD_W = 128

        /** 卡片/面板「外缘」距屏幕中心的距离：紫卡左缘 −CARD_OUTER、黑卡右缘 +CARD_OUTER（加宽只往中心扩） */
        const val CARD_OUTER = 238

        /** 卡片顶边相对背景顶的偏移 */
        const val CARD_TOP = 2

        /** 面板顶边相对背景顶的偏移：卡片底（CARD_TOP + CARD_W）往上压 18px，让开卡片贴图的透明边距 */
        const val PANEL_TOP = CARD_TOP + CARD_W - 18

        /** 面板高度（标题区 29 + 5 行 × 20 + 底部余量 4） */
        const val PANEL_H = 133

        /**
         * 卡片「可见内容」的上下边界（相对卡片顶）：贴图 256px 的内容 y∈[46,208]，上下各约 18% 是透明边距，
         * 点击区域按可见内容收窄。按 CARD_W 等比换算（104px 时为 [16,88]，128px 时为 [20,108]）。
         */
        const val CARD_SHOW_TOP = 20
        const val CARD_SHOW_BOTTOM = 108
    }

    private var backButton: NineSliceButton? = null
    private var rulesButton: NineSliceButton? = null
    /** 卡片管理入口（仅 OP；规则按钮下方，左端两张卡微缩图标在 render 叠加） */
    private var cardManageButton: NineSliceButton? = null
    /** 关市时点置灰的「喵喵的帮助」按钮：fail 音 + 3 秒红字提示的截止时刻（照入口界面 financeNoticeUntil） */
    private var loanNoticeUntil = 0L
    // 初始读全局缓存（60 秒兜底轮询写入）秒显不闪；-1 = 未拉取，响应到达后更新
    private var limit = com.shusheng.cobblemarket.client.FinanceCache.creditLimit
    private var debt = com.shusheng.cobblemarket.client.FinanceCache.creditDebt
    /** 有坏账记录：额度被锁定（服务端 limit 传 0），额度行下方补一行红字说明 */
    private var hasBadDebt = false
    // 持有者面板数据（两张卡下方，所有人可见；进入时拉取，服务端变化时广播刷新）
    private var purpleBoardEntries = listOf<CardHolderBoardEntry>()
    private var purpleBoardMax = 0L
    private var blackBoardEntries = listOf<CardHolderBoardEntry>()
    private var blackBoardMax = 0L
    private var purpleBoardOffset = 0
    private var blackBoardOffset = 0
    // 皮肤头像（照 AdminAuctionScreen 模板：每行每帧查 skinProvider 是分配热点，按 UUID 缓存）
    private val skinCache = mutableMapOf<UUID, Identifier>()
    private val defaultSkinTexture = Identifier.of("minecraft", "textures/entity/player/wide/steve.png")
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
        // ⚠ 这里是 146 不是 160：入口的等效式是 startY-47-(146-totalH)/2，
        // 写成 160 会让背景比入口高 7px（(160-146)/2），从入口进喵喵银行会觉得整体上移
        return startY - 14 - (146 - totalH) / 2 - 33
    }

    override fun init() {
        super.init()
        val bgTop = bgTop()

        // 返回按钮：右边缘与「全部借款历史」按钮右边缘对齐（48 宽 → x=width/2+57）
        // （全部借款历史：x=width/2+25 宽 80 → 右边缘 width/2+105）
        backButton = NineSliceButton(
            width / 2 + 57, bgTop + 76, 48, 16,
            Text.literal(""),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        // 规则按钮：返回按钮下方 4px，与「喵喵的帮助」按钮同一水平（悬停显示借款规则与后果面板，照拍卖场规则按钮）
        rulesButton = NineSliceButton(
            width / 2 + 57, bgTop + 96, 48, 16,
            Text.translatable("cobblemarket.meowth_bank.rules"),
            { }
        )
        addDrawableChild(rulesButton)

        // 卡片管理入口（仅 OP）：规则按钮下方 4px；与规则按钮同宽同 x（48×16），
        // 两张卡微缩图标在 render 叠加：紫卡左端、黑卡右端，互不粘连
        if (client?.player?.hasPermissionLevel(2) == true) {
            cardManageButton = NineSliceButton(
                width / 2 + 57, bgTop + 116, 48, 16,
                Text.literal(""),
                { client?.setScreen(CardManageScreen()) }
            )
            addDrawableChild(cardManageButton)
        }

        // 应急贷款入口（信息组：额度/欠款两行 + 按钮组成；100×16；批次 7.5 整体上移给存款按钮腾位）
        // 关市（紧急停市）时置灰：与服务端 RequestLoanPayload 的 marketBlocked 同一口径
        //（借款是唯一让资金流出准备金池的金融操作）。
        // ⚠ dimmed 只压暗观感、不影响可点性（见 NineSliceButton）—— 所以**点击必须在 onPress 里
        // 自己拦**：否则玩家一路填完借款表单、点确认才被服务端拒。拦在进入界面之前，
        // 给 fail 音 + 3 秒红字提示（照入口界面「金融总开关关」同款；2026-09-17 与 1.2.0 同步）
        val loanBtn = NineSliceButton(
            width / 2 - 50, bgTop + 96, 100, 16,
            Text.translatable("cobblemarket.loan.title"),
            {
                if (com.shusheng.cobblemarket.client.MarketStateCache.enabled) client?.setScreen(LoanScreen())
                else {
                    com.shusheng.cobblemarket.client.playFailSound()
                    loanNoticeUntil = System.currentTimeMillis() + 3000
                }
            }
        )
        loanBtn.dimmed = !com.shusheng.cobblemarket.client.MarketStateCache.enabled
        addDrawableChild(loanBtn)

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
            requestCreditInfo()
            infoLoaded = true
        }
        // 持有者面板数据（每次进入拉取；服务端变化时广播刷新）
        sendToServer(RequestCardHolderBoardPayload())
    }

    /** 额度信息快照（进入界面时拉取） */
    fun onCreditInfo(payload: CreditInfoPayload) {
        limit = payload.limit
        debt = payload.debt
        hasBadDebt = payload.hasBadDebt
    }

    /** 持有者面板快照（进入拉取/服务端广播刷新；滚动位置钳制到新名单长度） */
    fun onCardHolderBoard(payload: CardHolderBoardPayload) {
        purpleBoardEntries = payload.purple
        purpleBoardMax = payload.purpleMax
        blackBoardEntries = payload.black
        blackBoardMax = payload.blackMax
        purpleBoardOffset = purpleBoardOffset.coerceIn(0, maxOf(0, payload.purple.size - boardVisibleRows()))
        blackBoardOffset = blackBoardOffset.coerceIn(0, maxOf(0, payload.black.size - boardVisibleRows()))
    }

    private fun boardVisibleRows(): Int = 5

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
        // 缓存未拉取（-1）时按 0 显示（秒显优先，响应到达即更新）；
        // 行距 14px；上移给右侧按钮列（返回按钮 y+76）让位，避免文字右缘压按钮
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan.limit_line", formatPriceLong(limit.coerceAtLeast(0)), inlineCurrencyUnit()),
            width / 2, bgTop + 50, 0x55FFFF
        )
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan.debt_line", formatPriceLong(debt.coerceAtLeast(0)), inlineCurrencyUnit()),
            width / 2, bgTop + 64, 0x55FFFF
        )
        // 坏账警示：额度行已被服务端置 0，补一行红字说明原因（行高 9px，不与 bgTop+88 起的持有者面板重叠）
        if (hasBadDebt) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.meowth_bank.bad_debt_locked").formatted(Formatting.RED),
                width / 2, bgTop + 78, 0xFFFFFF
            )
        }

        // 左侧紫卡展示（所有玩家可见，无动画；点击打开申请紫卡弹窗）
        val cardItem = net.minecraft.registry.Registries.ITEM.get(
            net.minecraft.util.Identifier.of("cobblemarket", "meowth_purple_card")
        )
        if (cardItem != net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of("minecraft", "air"))) {
            // 显示尺寸 = 16 × scale = 128px（与下方持有者面板同宽，面板宽度就是照卡片定的）；
            // 移出背景并留 6px 空隙：卡右缘 = 背景左缘 − 6 → x = −238（外缘不动，只往中心扩）
            // ⚠ 上限 128：再加宽面板会越过左下「借款历史」按钮（按钮左缘 = width/2 − 102）
            val scale = CARD_W / 16.0
            val cardX = width / 2 - CARD_OUTER
            val cardY = bgTop + 2
            com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                itemStack = net.minecraft.item.ItemStack(cardItem),
                x = cardX.toDouble(), y = cardY.toDouble(), scale = scale.toDouble(),
                matrixStack = context.matrices
            )
        }
        // 右侧黑卡展示（与紫卡对称：卡左缘 = 背景右缘 + 6 空隙 → x = width/2 + 134；点击打开申请黑卡弹窗）
        val blackCardItem = net.minecraft.registry.Registries.ITEM.get(
            net.minecraft.util.Identifier.of("cobblemarket", "meowth_black_card")
        )
        if (blackCardItem != net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of("minecraft", "air"))) {
            // 与紫卡对称：卡右缘保持 +238 不动 → 左缘 = 238 − 128 = +110
            val scale = CARD_W / 16.0
            val cardX = width / 2 + CARD_OUTER - CARD_W
            val cardY = bgTop + 2
            com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                itemStack = net.minecraft.item.ItemStack(blackCardItem),
                x = cardX.toDouble(), y = cardY.toDouble(), scale = scale.toDouble(),
                matrixStack = context.matrices
            )
        }

        // 两张卡下方的持有者面板（所有人可见）：左右边缘与上方卡片对齐（同 x、同宽 128），
        // 纵向跟着卡片长高下移 24px —— 与卡片保持原有的 18px 重叠（让出卡片贴图的透明边距）
        renderHolderBoard(context, width / 2 - CARD_OUTER, "cobblemarket.card.board_purple_title", 0xFF55FF,
            purpleBoardEntries, purpleBoardMax, purpleBoardOffset)
        renderHolderBoard(context, width / 2 + CARD_OUTER - CARD_W, "cobblemarket.card.board_black_title", 0x555555,
            blackBoardEntries, blackBoardMax, blackBoardOffset)

        // 规则按钮悬停面板（照拍卖场规则面板：自绘 + 悬停位置自适应）
        if (rulesButton?.isHovered == true) {
            renderRulesPanel(context, mouseX, mouseY)
        }
        // 卡片管理按钮两张卡微缩图标（16px 满按钮高，贴图上下透明像素自然留边）：紫卡左端、黑卡右端
        cardManageButton?.let { btn ->
            if (btn.visible) {
                val purpleItem = net.minecraft.registry.Registries.ITEM.get(
                    net.minecraft.util.Identifier.of("cobblemarket", "meowth_purple_card")
                )
                val blackItem = net.minecraft.registry.Registries.ITEM.get(
                    net.minecraft.util.Identifier.of("cobblemarket", "meowth_black_card")
                )
                val air = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of("minecraft", "air"))
                if (purpleItem != air) {
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = net.minecraft.item.ItemStack(purpleItem),
                        x = (btn.x + 2).toDouble(), y = (btn.y + 0).toDouble(), scale = 1.0,
                        matrixStack = context.matrices
                    )
                }
                if (blackItem != air) {
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = net.minecraft.item.ItemStack(blackItem),
                        x = (btn.x + btn.width - 2 - 16).toDouble(), y = (btn.y + 0).toDouble(), scale = 1.0,
                        matrixStack = context.matrices
                    )
                }
            }
        }
        // 关市时点置灰的「喵喵的帮助」：3 秒红字提示（文案与入口界面关市提示同一口径）。
        // 位置取背景底部居中，只有两侧持有者面板之间这条缝是空的 → 完整句子放不下一行，
        // 故按缝隙宽折行居中（词条不动，入口界面那边空间够、仍是单行显示）
        if (System.currentTimeMillis() < loanNoticeUntil) {
            val gapW = (CARD_OUTER - CARD_W) * 2 - 12   // 两侧面板内缘之间，再各留 6px 安全边距
            textRenderer.wrapLines(Text.literal(Text.translatable("cobblemarket.market.closed").string), gapW)
                .forEachIndexed { i, line ->
                    context.drawCenteredTextWithShadow(textRenderer, line, width / 2, bgTop + 186 + i * 10, 0xFF5555)
                }
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
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (button != 0) return result
        // 左侧紫卡点击 → 申请紫卡弹窗（区域与渲染恒等：x −CARD_OUTER、宽 CARD_W、顶 bgTop+CARD_TOP）
        // 上下按可见内容收窄（贴图上下各约 18% 透明），收窄量见 CARD_SHOW_TOP / CARD_SHOW_BOTTOM
        val cardX = width / 2 - CARD_OUTER
        val cardY = bgTop() + CARD_TOP
        if (mouseX >= cardX && mouseX < cardX + CARD_W && mouseY >= cardY + CARD_SHOW_TOP && mouseY < cardY + CARD_SHOW_BOTTOM) {
            client?.setScreen(PurpleCardApplyScreen())
            return true
        }
        // 右侧黑卡点击 → 申请黑卡弹窗（与紫卡左右对称：x = +CARD_OUTER − CARD_W，收窄同理）
        val blackCardX = width / 2 + CARD_OUTER - CARD_W
        if (mouseX >= blackCardX && mouseX < blackCardX + CARD_W && mouseY >= cardY + CARD_SHOW_TOP && mouseY < cardY + CARD_SHOW_BOTTOM) {
            client?.setScreen(BlackCardApplyScreen())
            return true
        }
        return result
    }

    override fun shouldPause() = false

    // ── 持有者面板（卡下方 104×100：标题「喵喵紫卡（3/20）」+ 行列表：名字左/皮肤头像右/行间分割线/滚动） ──

    private fun renderHolderBoard(
        context: DrawContext,
        panelX: Int,
        titleKey: String,
        titleColor: Int,
        entries: List<CardHolderBoardEntry>,
        max: Long,
        offset: Int
    ) {
        // 纵向 +112（原 +88）：上方卡片加高 24px 后同步下移，保持与卡片的相对位置不变
        // 宽度 128（原 104）：与上方卡片同宽；上限 128 —— 再加宽会越过左下「借款历史」按钮
        val panelY = bgTop() + PANEL_TOP
        val panelW = CARD_W
        // 标题拆两行（卡名 + 计数）：英文全名太长一行放不下；
        // 面板顶在卡片可见内容下方（不遮卡片），标题整体下移避开面板顶部边框；标题区 29 + 5 行 × 20 + 底部余量 4 = 133
        val panelH = PANEL_H
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, panelX, panelY, panelW, panelH, 0, DIALOG_BACKGROUND_TEX_H)
        // 第一行卡名（英文全名超宽时格式码安全截断：§ 码不计宽、不拆码，截断处补 …）；第二行计数
        val nameText = Text.translatable(titleKey)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.literal(truncateFormatted(nameText.string, panelW - 12)),
            panelX + panelW / 2, panelY + 7, titleColor
        )
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.card.board_count", entries.size, max).formatted(Formatting.GRAY),
            panelX + panelW / 2, panelY + 18, 0xFFFFFF
        )
        val rowHeight = 20
        val startY = panelY + 29
        if (entries.isEmpty()) {
            // 空态文案同样要按面板宽截断：英文 "No card holders yet"（约 114px）曾比 104 的面板还宽，
            // 居中绘制 ⇒ 两侧各露出一截（2026-09-20 用户实测截图，扫描报告没抓到）
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal(
                    TextUtil.truncateString(Text.translatable("cobblemarket.card.manage_empty").string, panelW - 12)
                ).formatted(Formatting.GRAY),
                panelX + panelW / 2, startY + 8, 0xFFFFFF
            )
            return
        }
        entries.drop(offset).take(boardVisibleRows()).forEachIndexed { i, e ->
            val y = startY + i * rowHeight
            context.fill(panelX + 4, y, panelX + panelW - 4, y + 1, 0xFF555555.toInt())
            // 名字与头像都行内垂直居中（9px 字考虑基线取 y+6）；名字右移 2、头像左移 2，向中间靠拢。
            // 名字截断阈值从 panelW 反算（左内边距 6 + 名字右间隙 4 + 头像区 22），面板加宽时自动跟着走 ——
            // 原先写死 72 是照旧面板 104 算的，面板加宽到 128 后没跟着调、名字被白白提前截掉 24px
            context.drawTextWithShadow(textRenderer, Text.literal(truncateName(e.name, panelW - 32)), panelX + 6, y + 6, 0xFFFFFF)
            // 头像 16px 在行高 20 内上下各 2px 居中（上下分割线正中）
            drawAvatar(context, e.uuid, e.name, panelX + panelW - 4 - 16 - 2, y + 2, 16)
        }
    }

    private fun truncateName(name: String, maxWidth: Int): String {
        if (textRenderer.getWidth(name) <= maxWidth) return name
        var cut = name.length
        while (cut > 1 && textRenderer.getWidth(name.substring(0, cut) + "…") > maxWidth) cut--
        return name.substring(0, cut) + "…"
    }

    /** 带格式码文本截断（§ 码不计宽、不拆码；超宽时截断处补 …，保留已读入的颜色码） */
    private fun truncateFormatted(text: String, maxWidth: Int): String {
        var width = 0
        var cut = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '§' && i + 1 < text.length) {
                i += 2
                cut = i
                continue
            }
            width += textRenderer.getWidth(text[i].toString())
            if (width > maxWidth) break
            i++
            cut = i
        }
        return if (cut >= text.length) text else text.substring(0, cut) + "…"
    }

    // ── 皮肤头像（照 AdminAuctionScreen 模板：在线走列表条目纹理，离线走 skinProvider，失败 steve 兜底；按 UUID 缓存） ──

    private fun getSkin(uuid: UUID, name: String): Identifier {
        skinCache[uuid]?.let { return it }
        val skin = client?.networkHandler?.getPlayerListEntry(uuid)?.skinTextures?.texture()
            ?: client?.skinProvider?.getSkinTextures(GameProfile(uuid, name))?.texture()
            ?: defaultSkinTexture
        skinCache[uuid] = skin
        return skin
    }

    private fun drawAvatar(context: DrawContext, uuid: UUID, name: String, x: Int, y: Int, size: Int) {
        val texture = getSkin(uuid, name)
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(size / 8f, size / 8f, 1f)
        context.drawTexture(texture, 0, 0, 8f, 8f, 8, 8, 64, 64)
        context.matrices.pop()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val panelY = bgTop() + PANEL_TOP
        fun inPanel(panelX: Int) = mouseX >= panelX && mouseX < panelX + CARD_W && mouseY >= panelY && mouseY < panelY + PANEL_H
        when {
            inPanel(width / 2 - CARD_OUTER) && purpleBoardEntries.size > boardVisibleRows() -> {
                purpleBoardOffset = (purpleBoardOffset - verticalAmount.toInt())
                    .coerceIn(0, purpleBoardEntries.size - boardVisibleRows())
                return true
            }
            inPanel(width / 2 + CARD_OUTER - CARD_W) && blackBoardEntries.size > boardVisibleRows() -> {
                blackBoardOffset = (blackBoardOffset - verticalAmount.toInt())
                    .coerceIn(0, blackBoardEntries.size - boardVisibleRows())
                return true
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }
}
