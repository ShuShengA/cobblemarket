package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.finance.CardNames
import com.shusheng.cobblemarket.network.RequestServerConfigPayload
import com.shusheng.cobblemarket.network.SaveServerConfigPayload
import com.shusheng.cobblemarket.network.ServerConfigDataPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * 自行申请黑卡条件配置界面（照紫卡条件配置界面）：六项门槛 + 申请费（0 = 不要求）。
 * ⚠ 没有「额度要求」：申请黑卡硬性要求持有紫卡，持卡者额度固定（2026-10-28 用户拍板移除）。
 * 「持有紫卡」为硬条件不可配置，不在此界面。保存时条件字段提交编辑值、其余回填快照。
 */
class BlackCardApplyConditionsScreen : Screen(CardNames.label("cobblemarket.op.black_card_conditions")) {

    // 放宽到 360：文字可用宽 = 宽 − 100（≈260px），放得下英文原文（最长约 221px）；
    // 窗口过小时收进屏幕内，此时标签回落到 truncateString 兜底
    private val dialogW: Int get() = minOf(360, (width - 40).coerceAtLeast(200))
    private val rowHeight = 24

    private data class NumDef(val labelKey: String, val isInt: Boolean)

    private val numDefs = listOf(
        NumDef("cobblemarket.op.scfg_apply_asset", true) to "applyAsset",
        NumDef("cobblemarket.op.scfg_apply_volume", true) to "applyVolume",
        // 「额度要求」已移除：申请黑卡硬性要求持有紫卡，持卡者额度固定，该项没有筛选意义（2026-10-28）
        NumDef("cobblemarket.op.scfg_apply_deposit", true) to "applyDeposit",
        NumDef("cobblemarket.op.scfg_apply_seen", true) to "applySeen",
        NumDef("cobblemarket.op.scfg_apply_dex", true) to "applyDex",
        NumDef("cobblemarket.op.scfg_apply_fee", true) to "applyFee",
    )

    private val toggleDefs = listOf(
        "cobblemarket.op.scfg_apply_no_overdue" to "applyNoOverdue",
    )

    private val numFields = mutableMapOf<String, TextFieldWidget>()
    private val resetButtons = mutableMapOf<String, NineSliceButton>()
    private val toggleButtons = mutableMapOf<String, NineSliceButton>()
    private val localToggles = mutableMapOf<String, Boolean>()
    private var saveButton: NineSliceButton? = null
    private var cancelButton: NineSliceButton? = null
    private var scrollOffset = 0
    private var savedToastUntil = 0L
    private val totalRows = numDefs.size + toggleDefs.size

    private fun dialogH() = minOf(height - 8, 30 + totalRows * rowHeight + 55)
    private fun dialogY() = height / 2 - dialogH() / 2
    private fun listStartY() = dialogY() + 30
    /** 内容区高度：底边停在提示行上方 3px。底部三道自底向上 —— 按钮 -30、提示行 -42、内容底 -45，各留 3px
     *  ⚠ 改这三处任意一处，都要同步改 dialogH() 的底部预留，否则弹窗高度与内容区对不上 */
    private fun listAreaH() = dialogH() - 30 - 45
    private fun getMaxVisibleRows() = maxOf(0, listAreaH() / rowHeight)

    override fun init() {
        super.init()
        val dialogX = width / 2 - dialogW / 2
        val startY = listStartY()
        numDefs.forEach { (def, key) ->
            // 输入框右缘钉死贴 ↺ 按钮左缘（间隙 2px），宽度一律**往左长**（见 ConfigFieldLayout）
            val fieldW = ConfigFieldLayout.configFieldWidth(textRenderer, dialogW, key, def.labelKey)
            val field = TextFieldWidget(
                textRenderer, ConfigFieldLayout.configFieldRight(dialogX, dialogW) - fieldW,
                startY, fieldW, 16, Text.literal("")
            )
            field.setTextPredicate { text -> if (def.isInt) text.all { it.isDigit() } else text.all { it.isDigit() || it == '.' } }
            field.setMaxLength(10)
            // 口径解释（服主向）
            val tipKey = when (key) {
                "applyAsset" -> "cobblemarket.op.scfg_apply_asset_tip"
                "applyVolume" -> "cobblemarket.op.scfg_apply_volume_tip"
                "applyCredit" -> "cobblemarket.op.scfg_apply_credit_tip"
                "applyDeposit" -> "cobblemarket.op.scfg_apply_deposit_tip"
                "applySeen" -> "cobblemarket.op.scfg_apply_seen_tip"
                "applyDex" -> "cobblemarket.op.scfg_apply_dex_tip"
                "applyFee" -> "cobblemarket.op.scfg_apply_fee_tip"
                else -> null
            }
            if (tipKey != null) {
                field.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable(tipKey)))
            }
            numFields[key] = field
            addSelectableChild(field)
            addDrawableChild(field)
            val resetBtn = NineSliceButton(
                dialogX + dialogW - 10 - 20, startY, 20, 16,
                Text.literal("↺"),
                {
                    // 只填回默认值不提交：点下方「保存」统一生效（2026-09-05 拍板，重置按钮一律不自动保存）
                    numFields[key]?.text = snapshotText(key, null)
                    // ⚠ 设完值要把光标挪回开头：光标默认停在**末尾**，而框窄、只围绕光标显示一段，
                    //   长值（如拍卖档位 "720,1440,…"）就会只露出尾巴，看着像"配置被改过"（2026-09-22 用户报）
                    numFields[key]?.setCursor(0, false)
                }
            )
            resetButtons[key] = resetBtn
            addDrawableChild(resetBtn)
        }
        toggleDefs.forEach { (_, key) ->
            val btn = NineSliceButton(
                dialogX + dialogW - 10 - 22, startY, 22, 22,
                Text.literal(""),
                {
                    localToggles[key] = !currentToggleValue(key)
                    toggleButtons[key]?.iconLeft = toggleIconFor(key, null)
                },
                iconLeft = toggleIcon(key),
                iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            )
            toggleButtons[key] = btn
            addDrawableChild(btn)
        }
        saveButton = NineSliceButton(
            width / 2 - 62, dialogY() + dialogH() - 30, 60, 20,
            Text.translatable("cobblemarket.op.scfg_save"),
            { save() }
        )
        addDrawableChild(saveButton)
        cancelButton = NineSliceButton(
            width / 2 + 2, dialogY() + dialogH() - 30, 60, 20,
            Text.translatable("cobblemarket.op.scfg_cancel"),
            { client?.setScreen(BlackCardConfigScreen()) }
        )
        addDrawableChild(cancelButton)
        rebuildPositions()
        sendToServer(RequestServerConfigPayload())
    }

    private fun snapshotText(key: String, payload: ServerConfigDataPayload?): String {
        val v = numValue(key, payload)
        return if (v == v.toLong().toDouble()) v.toLong().toString()
        else java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
    }

    private fun numValue(key: String, payload: ServerConfigDataPayload?): Double = when (key) {
        "applyAsset" -> (payload?.blackCardApplyAsset ?: 0L).toDouble()
        "applyVolume" -> (payload?.blackCardApplyVolume ?: 0L).toDouble()
        "applyDeposit" -> (payload?.blackCardApplyDeposit ?: 0L).toDouble()
        "applySeen" -> (payload?.blackCardApplySeen ?: 0L).toDouble()
        "applyDex" -> (payload?.blackCardApplyDex ?: 0L).toDouble()
        "applyFee" -> (payload?.blackCardApplyFee ?: 0L).toDouble()
        else -> 0.0
    }

    private fun toggleIcon(key: String): net.minecraft.util.Identifier? = toggleIconFor(key, null)

    private fun toggleIconFor(key: String, p: ServerConfigDataPayload?): net.minecraft.util.Identifier? {
        // 优先级：回发快照值 > 本地未保存的编辑值 > 旧快照（照 ServerConfigScreen）
        val on = p?.let { snapshot ->
            when (key) {
                "applyNoOverdue" -> snapshot.blackCardApplyNoOverdue
                else -> false
            }
        } ?: localToggles[key] ?: when (key) {
            "applyNoOverdue" -> ServerConfigScreen.latest?.blackCardApplyNoOverdue ?: false
            else -> false
        }
        return if (on)
            net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/button/switch_icon_on.png")
        else
            net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/button/switch_icon_off.png")
    }

    private fun currentToggleValue(key: String): Boolean {
        val p = ServerConfigScreen.latest
        return when (key) {
            "applyNoOverdue" -> localToggles["applyNoOverdue"] ?: (p?.blackCardApplyNoOverdue ?: false)
            else -> false
        }
    }

    fun refreshFrom(payload: ServerConfigDataPayload) {
        numDefs.forEach { (_, key) ->
            val field = numFields[key] ?: return@forEach
            if (focused !== field) {
                field.text = snapshotText(key, payload)
                field.setCursor(0, false)   // 光标回开头，长值才不会只显示尾巴
            }
        }
        toggleDefs.forEach { (_, key) ->
            toggleButtons[key]?.iconLeft = toggleIconFor(key, payload)
        }
        localToggles.clear()
    }

    private fun save() {
        val p = ServerConfigScreen.latest
        fun longOr(key: String, fallback: Long): Long = numFields[key]?.text?.toLongOrNull() ?: fallback
        sendToServer(SaveServerConfigPayload(
            pokemonFee = p?.pokemonFee ?: 5.0,
            itemFee = p?.itemFee ?: 5.0,
            maxPokemonListings = p?.maxPokemonListings ?: 0,
            maxItemListings = p?.maxItemListings ?: 0,
            listingDays = p?.listingDays ?: 14,
            pendingDays = p?.pendingDays ?: 30,
            auctionFee = p?.auctionFee ?: 5.0,
            auctionMinBid = p?.auctionMinBid ?: 100,
            antiSnipe = p?.antiSnipe ?: 120,
            maxAuctions = p?.maxAuctions ?: 3,
            buyOrderFee = p?.buyOrderFee ?: 5.0,
            buyOrderExpiry = p?.buyOrderExpiry ?: 3,
            maxBuyOrders = p?.maxBuyOrders ?: 5,
            eggTrading = p?.eggTrading ?: false,
            auctionDurations = p?.auctionDurations ?: "",
            idCardBackground = p?.idCardBackground ?: "",
            financeEnabled = p?.financeEnabled ?: false,
            cashLoanEnabled = p?.cashLoanEnabled ?: true,
            consumerLoanEnabled = p?.consumerLoanEnabled ?: true,
            loanPlans = p?.loanPlans ?: "",
            creditRecent30 = p?.creditRecent30 ?: 0.5,
            creditHistory = p?.creditHistory ?: 0.1,
            creditDebt = p?.creditDebt ?: 0.3,
            creditMin = p?.creditMin ?: 0L,
            creditMax = p?.creditMax ?: 100_000L,
            creditCooldown = p?.creditCooldown ?: 24L,
            dailyDepositRate = p?.dailyDepositRate ?: 0.0001,
            tradePairWindowDays = p?.tradePairWindowDays ?: 30L,
            tradePairMaxTrades = p?.tradePairMaxTrades ?: 3L,
            // 紫卡字段全部回填快照（本界面不编辑）
            purpleCardCount = p?.purpleCardCount ?: 20L,
            purpleCardCreditLimit = p?.purpleCardCreditLimit ?: 1_000_000L,
            purpleCardSelfApply = p?.purpleCardSelfApply ?: false,
            purpleCardApplyAsset = p?.purpleCardApplyAsset ?: 0L,
            purpleCardApplyVolume = p?.purpleCardApplyVolume ?: 0L,
            purpleCardApplyCredit = p?.purpleCardApplyCredit ?: 0L,
            purpleCardApplyDeposit = p?.purpleCardApplyDeposit ?: 0L,
            purpleCardApplyNoOverdue = p?.purpleCardApplyNoOverdue ?: false,
            purpleCardApplySeen = p?.purpleCardApplySeen ?: 0L,
            purpleCardApplyDex = p?.purpleCardApplyDex ?: 0L,
            purpleCardApplyFee = p?.purpleCardApplyFee ?: 0L,
            purpleCardRedoFee = p?.purpleCardRedoFee ?: 0L,
            purpleCardFeeDiscount = p?.purpleCardFeeDiscount ?: 0.0,
            // 黑卡字段：条件提交编辑值，其余回填快照
            blackCardCount = p?.blackCardCount ?: 5L,
            blackCardCreditLimit = p?.blackCardCreditLimit ?: 5_000_000L,
            blackCardSelfApply = p?.blackCardSelfApply ?: false,
            blackCardApplyAsset = longOr("applyAsset", p?.blackCardApplyAsset ?: 0L),
            blackCardApplyVolume = longOr("applyVolume", p?.blackCardApplyVolume ?: 0L),
            blackCardApplyDeposit = longOr("applyDeposit", p?.blackCardApplyDeposit ?: 0L),
            blackCardApplyNoOverdue = localToggles["applyNoOverdue"] ?: (p?.blackCardApplyNoOverdue ?: false),
            blackCardApplySeen = longOr("applySeen", p?.blackCardApplySeen ?: 0L),
            blackCardApplyDex = longOr("applyDex", p?.blackCardApplyDex ?: 0L),
            blackCardApplyFee = longOr("applyFee", p?.blackCardApplyFee ?: 0L),
            blackCardRedoFee = p?.blackCardRedoFee ?: 0L,
            blackCardFeeDiscount = p?.blackCardFeeDiscount ?: 0.0,
            ipDebtLimit = p?.ipDebtLimit ?: 100_000L,
            autoRepayMinBalance = p?.autoRepayMinBalance ?: 1_000L,
            overdueFeeDouble = p?.overdueFeeDouble ?: 7,
            overdueFreeze = p?.overdueFreeze ?: 14,
            overdueBadDebt = p?.overdueBadDebt ?: 30,
            shareCooldownSeconds = p?.shareCooldownSeconds ?: 10,
            pokemonMarketEnabled = p?.pokemonMarketEnabled ?: true,
            itemMarketEnabled = p?.itemMarketEnabled ?: true,
            auctionEnabled = p?.auctionEnabled ?: true,
            buyOrderEnabled = p?.buyOrderEnabled ?: true,
            auctionPokemonEnabled = p?.auctionPokemonEnabled ?: true,
            auctionItemEnabled = p?.auctionItemEnabled ?: true,
            buyOrderPokemonEnabled = p?.buyOrderPokemonEnabled ?: true,
            buyOrderItemEnabled = p?.buyOrderItemEnabled ?: true,
        ))
        savedToastUntil = System.currentTimeMillis() + 1500
    }

    private fun rebuildPositions() {
        val dialogX = width / 2 - dialogW / 2
        val startY = listStartY()
        var row = 0
        numDefs.forEach { (def, key) ->
            val y = startY + (row - scrollOffset) * rowHeight
            val field = numFields[key] ?: return@forEach
            val visible = row in scrollOffset until scrollOffset + getMaxVisibleRows()
            // ⚠ x 与宽度必须和 init 用同一套算法：只改一处会让右缘戳出弹窗（2026-09-22 踩过）
            field.x = ConfigFieldLayout.configFieldX(textRenderer, dialogX, dialogW, key, def.labelKey)
            field.setWidth(ConfigFieldLayout.configFieldWidth(textRenderer, dialogW, key, def.labelKey))
            field.y = y + 4
            field.visible = visible
            resetButtons[key]?.x = dialogX + dialogW - 10 - 20
            resetButtons[key]?.y = y + 4
            resetButtons[key]?.visible = visible
            row++
        }
        toggleDefs.forEach { (_, key) ->
            val y = startY + (row - scrollOffset) * rowHeight
            val btn = toggleButtons[key] ?: return@forEach
            btn.x = dialogX + dialogW - 10 - 22
            btn.y = y + 1
            btn.visible = row in scrollOffset until scrollOffset + getMaxVisibleRows()
            row++
        }
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val dialogX = width / 2 - dialogW / 2
        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY(), dialogW, dialogH(), 0, DIALOG_BACKGROUND_TEX_H)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val centerX = width / 2
        val dialogX = width / 2 - dialogW / 2
        context.drawCenteredTextWithShadow(
            textRenderer,
            CardNames.label("cobblemarket.op.black_card_conditions").formatted(Formatting.GOLD, Formatting.BOLD),
            centerX, dialogY() + 10, 0xFFFFFF
        )
        val startY = listStartY()
        var row = 0
        fun drawRowLine(rowY: Int) {
            context.fill(dialogX + 6, rowY, dialogX + dialogW - 6, rowY + 1, 0xFF888888.toInt())
        }
        fun drawNumRow(def: NumDef, key: String) {
            if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
                val rowY = startY + (row - scrollOffset) * rowHeight
                drawRowLine(rowY)
                context.drawTextWithShadow(
                    textRenderer,
                    TextUtil.truncateStyled(
                        CardNames.label(def.labelKey),
                        ConfigFieldLayout.configLabelWidth(textRenderer, dialogW, key, def.labelKey)
                    ),
                    dialogX + 10, rowY + 7, 0xFFFFFF
                )
            }
            row++
        }
        fun drawToggleRow(labelKey: String) {
            if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
                val rowY = startY + (row - scrollOffset) * rowHeight
                drawRowLine(rowY)
                context.drawTextWithShadow(
                    textRenderer,
                    CardNames.label(labelKey),
                    dialogX + 10, rowY + 7, 0xFFFFFF
                )
            }
            row++
        }
        numDefs.forEach { (def, key) -> drawNumRow(def, key) }
        toggleDefs.forEach { (labelKey, _) -> drawToggleRow(labelKey) }
        if (System.currentTimeMillis() < savedToastUntil) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.op.scfg_saved").formatted(Formatting.GREEN),
                centerX, dialogY() + dialogH() - 42, 0xFFFFFF
            )
        } else {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.op.scfg_hint").formatted(Formatting.GRAY),
                centerX, dialogY() + dialogH() - 42, 0xFFFFFF
            )
        }
        renderScrollbar(context)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        scrollOffset = (scrollOffset - verticalAmount.toInt())
            .coerceIn(0, maxOf(0, totalRows - getMaxVisibleRows()))
        rebuildPositions()
        return true
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 点击空白处结束输入状态（照 LoanScreen 惯例）
        val wasInInput = focused is TextFieldWidget
        val result = super.mouseClicked(mouseX, mouseY, button)
        // ⭐ **滚动条**（在弹窗右缘往里那一小条）：按下滑块 / 点轨道空白都直接改 scroll，
        //   点轨道 = **跳转**（见 [ScrollBar] 的交互口径）。
        //   ⚠ 本界面控件位置是**预算好**的（[rebuildPositions]），改完 scroll 必须跟着挪行，
        //     否则滑块动了、行不动。⚠ 行上的判定（输入框 / ↺ / 开关）在 [super.mouseClicked] 里已经先跑过了
        if (button == 0) {
            ScrollBar.shared.mouseClicked(mouseX, mouseY, scrollOffset)?.let {
                scrollOffset = it
                rebuildPositions()
                return true
            }
        }
        if (wasInInput && numFields.values.none { it.isMouseOver(mouseX, mouseY) }) {
            focused = null
        }
        return result
    }

    /**
     * 拖动中 —— 本界面里**只有滚动条**在用（见 [ScrollBar]）：拖出轨道也照样跟手，
     * 换算出的新 scroll 要立刻 [rebuildPositions]（否则滑块跟手了、行没跟）。
     */
    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        ScrollBar.shared.mouseDragged(mouseY, scrollOffset)?.let {
            scrollOffset = it
            rebuildPositions()
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    /**
     * 松开：结束滚动条的拖动。
     *
     * ⚠ 结束拖动时要**吃掉这次松开** —— 否则它会在别处又触发一次点击（松手那刻鼠标往往正压着某个按钮）。
     */
    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (ScrollBar.shared.mouseReleased()) return true
        return super.mouseReleased(mouseX, mouseY, button)
    }

    /**
     * 界面被关掉 / 被替换（ESC、点「取消」跳回黑卡配置界面）时复位滚动条的拖动状态。
     *
     * ⚠ 不调会留个"半拖"：按住滑块直接按 ESC，`dragging` 还是 true ⇒ 下个界面里**没按在条上**
     *   的拖动也会被当成在拖条（见 [ScrollBar.cancel]）。
     */
    override fun removed() {
        ScrollBar.shared.cancel()
        super.removed()
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        super.resize(client, width, height)
        rebuildPositions()
    }

    /**
     * 右侧滚动条（**可拖动**，2026-09-30 用户报「滚动条点不动」后统一）：
     * 走共用的 [ScrollBar]，**别再自己 fill** —— 自己画的条拖不动。
     *
     * ⚠ 传的 `totalRows` / `getMaxVisibleRows()` / `scrollOffset` **本来就是"行"**（与 [ScrollBar] 同一口径），
     *   不需要再像原来那样绕 `rowHeight` 换成像素（滑块高 / 滑块位置由 [ScrollBar.draw] 自己按行数比例算）。
     * ⚠ 「装得下就不画」也交给它（`total <= rows` 时它直接返回 false）—— 本函数不用再判。
     */
    private fun renderScrollbar(context: DrawContext) {
        ScrollBar.shared.draw(
            context,
            width / 2 + dialogW / 2 - 5, listStartY(), listAreaH(),
            totalRows, getMaxVisibleRows(), scrollOffset
        )
    }

    override fun shouldPause() = false
}
