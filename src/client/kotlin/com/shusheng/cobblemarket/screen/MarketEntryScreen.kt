package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.ClientConfig
import com.shusheng.cobblemarket.client.MarketStateCache
import com.shusheng.cobblemarket.network.RequestBalancePayload
import com.shusheng.cobblemarket.network.SetMarketEnabledPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

class MarketEntryScreen : Screen(Text.translatable("cobblemarket.entry.title")) {

    private var btnStartY = 0
    private var totalH = 0

    // 弹窗打开期间需要整体隐藏的下层控件（closeSettingsDialog 的 init 重建会恢复）
    private val entryButtons = mutableListOf<ClickableWidget>()
    private var settingsOpen = false
    private var settingsMarketButton: NineSliceButton? = null
    private var settingsAuctionButton: NineSliceButton? = null
    private var settingsDropButton: NineSliceButton? = null
    // 入口底部居中的市场总开关（仅 OP 可见）
    private var marketSwitchBtn: NineSliceButton? = null
    // 停市确认弹窗（照 AdminScreen 蛋交易确认弹窗：3 秒冷静期 + 红白双色文字）
    private var marketConfirmOpen = false
    private var marketConfirmOpenedAt = 0L
    private var marketConfirmButton: NineSliceButton? = null
    private var marketCancelButton: NineSliceButton? = null

    // 市场关闭提示（入口点击被拦截时显示，3 秒）
    private var closedNoticeUntil = 0L

    // 设置弹窗开关切换提示（1.5 秒 toast）
    private var settingsToastUntil = 0L
    private var settingsToastText: Text = Text.literal("")

    /**
     * 市场总开关客户端门控：关闭时只提示，不进入任何交易界面。
     * opBypass=true 的入口（管理面板）对 OP 放行——关市期间服主仍需下架/黑名单/价格限制等管理能力。
     */
    private fun openIfMarketEnabled(opBypass: Boolean = false, open: () -> Unit) {
        val isOp = client?.player?.hasPermissionLevel(2) == true
        if (com.shusheng.cobblemarket.client.MarketStateCache.enabled || (opBypass && isOp)) {
            open()
        } else {
            closedNoticeUntil = System.currentTimeMillis() + 3000
        }
    }

    // 背景图底边（renderBackground 按内容高度居中计算；求购按钮贴此底边）
    private fun bgBottom(): Int {
        val contentTop = btnStartY - 14
        val contentBottom = btnStartY + totalH
        val bgTop = contentTop - (160 - (contentBottom - contentTop)) / 2
        return bgTop + 160
    }

    override fun init() {
        super.init()
        entryButtons.clear()
        ClientPlayNetworking.send(RequestBalancePayload())
        val centerX = width / 2
        val btnW = 87
        val btnH = 24
        // 行距 8→5：上面三行（含仅OP）更紧凑、整体上移，与底部三个小按钮拉开距离（用户 2026-08-21 要求）
        val gap = 5
        val colGap = 2
        val totalW = btnW * 2 + colGap
        val leftX = centerX - totalW / 2
        val rightX = leftX + btnW + colGap

        val isAdmin = client?.player?.hasPermissionLevel(2) == true
        val rowCount = if (isAdmin) 3 else 2
        totalH = btnH * rowCount + gap * (rowCount - 1)
        val startY = height / 2 - totalH / 2
        btnStartY = startY

        // 行 1
        entryButtons += addDrawableChild(TextureButton(
            leftX, startY, btnW, btnH,
            Text.translatable("cobblemarket.entry.pokemon"),
            { openIfMarketEnabled { client?.setScreen(MarketScreen()) } },
            Identifier.of("cobblemarket", "textures/gui/pokeball_icon.png")
        ))
        entryButtons += addDrawableChild(TextureButton(
            rightX, startY, btnW, btnH,
            Text.translatable("cobblemarket.entry.item"),
            { openIfMarketEnabled { openItemMarket() } },
            Identifier.of("cobblemarket", "textures/gui/item_icon.png")
        ))
        // 行 2
        entryButtons += addDrawableChild(TextureButton(
            leftX, startY + btnH + gap, btnW, btnH,
            Text.translatable("cobblemarket.entry.history"),
            { openIfMarketEnabled { client?.setScreen(HistoryScreen()) } },
            Identifier.of("cobblemarket", "textures/gui/history_icon.png")
        ))
        entryButtons += addDrawableChild(TextureButton(
            rightX, startY + btnH + gap, btnW, btnH,
            Text.translatable("cobblemarket.entry.auction"),
            { openIfMarketEnabled { client?.setScreen(AuctionScreen()) } },
            Identifier.of("cobblemarket", "textures/gui/auction_gavel_left.png")
        ))
        // 行 3：管理员面板居中（仅 OP），左侧放 OP 图标
        if (isAdmin) {
            entryButtons += addDrawableChild(TextureButton(
                centerX - btnW / 2, startY + (btnH + gap) * 2, btnW, btnH,
                Text.translatable("cobblemarket.entry.op"),
                { openIfMarketEnabled(opBypass = true) { client?.setScreen(AdminScreen()) } },
                Identifier.of("cobblemarket", "textures/gui/op_icon.png")
            ))
        }

        // 背景底部两角的独立小按钮（纯图标 + row_background 风格，悬停显示名称），
        // OP：求购单移到设置按钮正上方（右列、与仅OP同排）；非 OP：求购单仍在左下角
        val cornerSize = 22
        val cornerY = bgBottom() - cornerSize - 6
        // 设置按钮 x 中心 = centerX+79，求购单以同一中心对齐（正上方），22 宽 → 左缘 centerX+68
        val buyOrderX = if (isAdmin) centerX + 68 else centerX - 96 + 6
        val buyOrderY = if (isAdmin) startY + (btnH + gap) * 2 + 1 else cornerY
        val buyOrderBtn = NineSliceButton(
            buyOrderX, buyOrderY,
            cornerSize, cornerSize,
            Text.literal(""),
            { openIfMarketEnabled { client?.setScreen(BuyOrderScreen()) } },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/buy_order_icon.png"),
            texture = ROW_BACKGROUND_TEXTURE,
            texH = ROW_BACKGROUND_TEX_H
        )
        // 纯图标按钮：悬停提示名称（无障碍与可读性）
        buyOrderBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.entry.buy_order")))
        entryButtons += addDrawableChild(buyOrderBtn)

        val settingsBtn = NineSliceButton(
            centerX + 96 - cornerSize - 6, cornerY,
            cornerSize, cornerSize,
            Text.literal(""),
            { openIfMarketEnabled { openSettingsDialog() } },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/settings_icon.png"),
            texture = ROW_BACKGROUND_TEXTURE,
            texH = ROW_BACKGROUND_TEX_H
        )
        settingsBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.entry.settings")))
        entryButtons += addDrawableChild(settingsBtn)

        // 市场总开关（仅 OP 可见）：紧急停市/恢复，与求购单/设置按钮同尺寸，居底
        if (isAdmin) {
            marketSwitchBtn = NineSliceButton(
                centerX - cornerSize / 2, cornerY,
                cornerSize, cornerSize,
                Text.literal(""),
                { toggleMarketEnabled() },
                iconLeft = marketSwitchIcon(),
                // 48×48 贴图缩到 18×18 显示（按钮 22×22 留 2px 边距，源图高分辨率保持锐利）
                iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
                texture = ROW_BACKGROUND_TEXTURE,
                texH = ROW_BACKGROUND_TEX_H
            )
            marketSwitchBtn?.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.entry.market_switch")))
            entryButtons += addDrawableChild(marketSwitchBtn!!)
        }

        // resize 重建按钮后 visible 全是 true，停市确认弹窗开着时需恢复隐藏（老坑，照 AdminScreen）
        if (marketConfirmOpen) entryButtons.forEach { it.visible = false }
    }

    // ── 市场总开关按钮（OP）：乐观切换 + 服务端广播回状态 ──

    private fun marketSwitchIcon(): Identifier =
        Identifier.of("cobblemarket", if (MarketStateCache.enabled) "textures/gui/switch_icon_on.png" else "textures/gui/switch_icon_off.png")

    /** 服务端广播 MarketStatePayload 时同步刷新图标（切换后 / 他处命令切换时都能跟上） */
    fun updateMarketSwitchIcon() {
        marketSwitchBtn?.iconLeft = marketSwitchIcon()
    }

    private fun toggleMarketEnabled() {
        if (settingsOpen) return  // 设置弹窗打开时开关按钮不可点（弹窗之下）
        if (MarketStateCache.enabled) {
            // 停市需二次确认（3 秒冷静期，红白双色警告，照蛋交易确认弹窗）
            openMarketConfirmDialog()
        } else {
            // 恢复立即生效——紧急止损后的恢复不能被弹窗挡一步
            sendMarketEnabled(true)
        }
    }

    private fun sendMarketEnabled(target: Boolean) {
        MarketStateCache.enabled = target
        ClientPlayNetworking.send(SetMarketEnabledPayload(target))
        updateMarketSwitchIcon()
        // 服务端广播（MarketStatePayload）会以权威状态刷新横幅；这里先乐观更新，避免等待的迟滞感
    }

    // ── 设置弹窗（客户端个人观感开关；以后新开关统一加在这里） ──

    private fun openSettingsDialog() {
        settingsOpen = true
        // 隐藏下层控件（弹窗打开期间不可交互；closeSettingsDialog 的 init 重建会恢复）
        entryButtons.forEach { it.visible = false }

        val centerX = width / 2
        val dialogY = height / 2 - DIALOG_H / 2

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderSettingsDialogBackground(context)
            }
        })

        // 动画开关按频率分两个：市场直购最高频，拍卖成交与求购单接受都是低频，合用一个
        // 行格式：左侧标签文字 + 右侧开关图标按钮（switch_icon_on/off，22×22）
        settingsMarketButton = makeSwitchButton(ClientConfig.celebrationOnMarketBuy) { toggleMarketAnimation() }
        // 按钮 x = 标签右缘 + 4px：紧跟文字，不留大空档（英文标签更长，自动适配）
        settingsMarketButton?.let { b -> b.x = labelRight("cobblemarket.settings.animation_market") + 4; b.y = dialogY + 30; addDrawableChild(b) }
        settingsAuctionButton = makeSwitchButton(ClientConfig.celebrationOnAuctionAndOrder) { toggleAuctionAnimation() }
        settingsAuctionButton?.let { b -> b.x = labelRight("cobblemarket.settings.animation_auction") + 4; b.y = dialogY + 56; addDrawableChild(b) }
        settingsDropButton = makeSwitchButton(ClientConfig.dropOverflowOnClaim) { toggleDropOverflow() }
        settingsDropButton?.let { b -> b.x = labelRight("cobblemarket.settings.drop_overflow") + 4; b.y = dialogY + 82; addDrawableChild(b) }
        addDrawableChild(NineSliceButton(
            centerX - 28, dialogY + 108, 56, 20,
            Text.translatable("cobblemarket.settings.done"),
            { closeSettingsDialog() }
        ))
    }

    private fun closeSettingsDialog() {
        settingsOpen = false
        settingsMarketButton = null
        settingsAuctionButton = null
        settingsDropButton = null
        clearChildren()
        init()
    }

    /** 开关行标签：只要标签本身，开关状态由右侧图标按钮表达（用户 2026-08-21 要求去掉开/关文字） */
    private fun toggleText(labelKey: String, on: Boolean): Text = Text.translatable(labelKey)

    /** 开关图标按钮：双态 switch_icon（与入口市场总开关同一套贴图），标签文字单独绘制 */
    private fun makeSwitchButton(initialOn: Boolean, action: () -> Unit): NineSliceButton {
        val b = NineSliceButton(
            0, 0, 22, 22,
            Text.literal(""),
            { action() },
            iconLeft = switchIconFor(initialOn),
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            texture = ROW_BACKGROUND_TEXTURE,
            texH = ROW_BACKGROUND_TEX_H
        )
        return b
    }

    private fun switchIconFor(on: Boolean): Identifier =
        Identifier.of("cobblemarket", if (on) "textures/gui/switch_icon_on.png" else "textures/gui/switch_icon_off.png")

    private fun toggleMarketAnimation() {
        ClientConfig.setCelebrationOnMarketBuy(!ClientConfig.celebrationOnMarketBuy)
        settingsMarketButton?.iconLeft = switchIconFor(ClientConfig.celebrationOnMarketBuy)
        showSettingsToast("cobblemarket.settings.animation_market", ClientConfig.celebrationOnMarketBuy)
    }

    private fun toggleAuctionAnimation() {
        ClientConfig.setCelebrationOnAuctionAndOrder(!ClientConfig.celebrationOnAuctionAndOrder)
        settingsAuctionButton?.iconLeft = switchIconFor(ClientConfig.celebrationOnAuctionAndOrder)
        showSettingsToast("cobblemarket.settings.animation_auction", ClientConfig.celebrationOnAuctionAndOrder)
    }

    private fun toggleDropOverflow() {
        ClientConfig.setDropOverflowOnClaim(!ClientConfig.dropOverflowOnClaim)
        settingsDropButton?.iconLeft = switchIconFor(ClientConfig.dropOverflowOnClaim)
        showSettingsToast("cobblemarket.settings.drop_overflow", ClientConfig.dropOverflowOnClaim)
    }

    /** 开关切换 toast：「标签 开/关」，1.5 秒后消失 */
    private fun showSettingsToast(labelKey: String, on: Boolean) {
        settingsToastText = Text.translatable(labelKey).append(" ")
            .append(if (on) Text.translatable("cobblemarket.settings.on_state").formatted(Formatting.GREEN)
                else Text.translatable("cobblemarket.settings.off_state").formatted(Formatting.RED))
        settingsToastUntil = System.currentTimeMillis() + 1500
    }

    private fun renderSettingsDialogBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogX = centerX - DIALOG_W / 2
        val dialogY = height / 2 - DIALOG_H / 2

        context.fill(0, 0, width, height, 0xC0000000.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, DIALOG_W, DIALOG_H, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.settings.title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF
        )
    }

    /** 标签文本右缘 x（按钮紧跟其后 4px） */
    private fun labelRight(key: String): Int =
        width / 2 - 80 + textRenderer.getWidth(Text.translatable(key).string)

    /** 设置弹窗两行开关的标签文字（左列，与右侧图标按钮垂直对齐） */
    private fun renderSettingsLabels(context: DrawContext) {
        val centerX = width / 2
        val dialogY = height / 2 - DIALOG_H / 2
        context.drawTextWithShadow(
            textRenderer,
            toggleText("cobblemarket.settings.animation_market", ClientConfig.celebrationOnMarketBuy),
            centerX - 80, dialogY + 37, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer,
            toggleText("cobblemarket.settings.animation_auction", ClientConfig.celebrationOnAuctionAndOrder),
            centerX - 80, dialogY + 63, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer,
            toggleText("cobblemarket.settings.drop_overflow", ClientConfig.dropOverflowOnClaim),
            centerX - 80, dialogY + 89, 0xFFFFFF
        )
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val wasSettingsOpen = settingsOpen
        val wasMarketConfirmOpen = marketConfirmOpen
        super.resize(client, width, height)
        if (wasSettingsOpen) {
            settingsOpen = false
            openSettingsDialog()
        }
        if (wasMarketConfirmOpen) {
            marketConfirmOpen = false
            openMarketConfirmDialog()
        }
    }

    // ── 停市确认弹窗（照 AdminScreen 蛋交易确认弹窗模板） ──

    private fun openMarketConfirmDialog() {
        marketConfirmOpen = true
        marketConfirmOpenedAt = System.currentTimeMillis()
        // 隐藏下层控件（弹窗打开期间不可交互；closeMarketConfirmDialog 的 init 重建会恢复）
        entryButtons.forEach { it.visible = false }

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderMarketConfirmBackground(context)
            }
        })

        val centerX = width / 2
        val dialogY = height / 2 - 75
        marketConfirmButton = NineSliceButton(
            centerX - 85, dialogY + 116, 80, 20,
            Text.translatable("cobblemarket.market.confirm_yes"),
            { confirmMarketClose() }
        )
        addDrawableChild(marketConfirmButton)
        marketCancelButton = NineSliceButton(
            centerX + 5, dialogY + 116, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeMarketConfirmDialog() }
        )
        addDrawableChild(marketCancelButton)
    }

    private fun closeMarketConfirmDialog() {
        marketConfirmOpen = false
        marketConfirmButton = null
        marketCancelButton = null
        clearChildren()
        init()
    }

    private fun confirmMarketClose() {
        sendMarketEnabled(false)
        closeMarketConfirmDialog()
    }

    private fun renderMarketConfirmBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogW = 280
        val dialogH = 150
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        context.fill(0, 0, width, height, 0xC0000000.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.market.confirm_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)
    }

    private fun renderMarketConfirmText(context: DrawContext) {
        val centerX = width / 2
        val dialogX = centerX - 140
        val dialogY = height / 2 - 75

        // 逐行渲染：语言文件显式分行（每行红/白两个槽位），红=警告、白=普通；空行跳过（中英行数不同）
        val lines = (1..6).map { i ->
            listOf(
                "cobblemarket.market.confirm_l${i}_warn" to 0xFF5555,
                "cobblemarket.market.confirm_l${i}_text" to 0xFFFFFF,
            )
        }
        var ty = dialogY + 32
        lines.forEachIndexed { li, line ->
            val segs = line.mapNotNull { (key, color) ->
                val text = Text.translatable(key).string
                if (text.isEmpty()) null else text to color
            }
            if (segs.isEmpty()) return@forEachIndexed
            // 第 5、6 行（进行中拍卖不暂停的提醒，拆成两行避免溢出）加粗：重要性高于其他行
            val bold = li == 4 || li == 5
            fun display(text: String): net.minecraft.text.OrderedText =
                if (bold) Text.literal(text).formatted(Formatting.BOLD).asOrderedText()
                else Text.literal(text).asOrderedText()
            val ordered = segs.map { (text, color) -> display(text) to color }
            val lineWidth = ordered.sumOf { textRenderer.getWidth(it.first) }
            var tx = dialogX + 20 + (240 - lineWidth) / 2
            ordered.forEach { (text, color) ->
                context.drawTextWithShadow(textRenderer, text, tx, ty, color)
                tx += textRenderer.getWidth(text)
            }
            ty += 9
        }
    }

    // 冷静期：3 秒内确认按钮禁用并显示倒计时
    private fun updateMarketConfirmButtons() {
        val cooldownLeft = 3 - (System.currentTimeMillis() - marketConfirmOpenedAt) / 1000
        val canConfirm = cooldownLeft <= 0
        marketConfirmButton?.active = canConfirm
        marketConfirmButton?.message = if (canConfirm)
            Text.translatable("cobblemarket.market.confirm_yes")
        else
            Text.translatable("cobblemarket.market.confirm_yes_countdown", cooldownLeft)
    }

    private fun openItemMarket() {
        client?.setScreen(ItemMarketScreen())
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val bg = Identifier.of("cobblemarket", "textures/gui/market_entry_background.png")
        context.drawTexture(bg, width / 2 - 96, bgBottom() - 160, 0f, 0f, 192, 160, 192, 160)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        if (marketConfirmOpen) {
            renderMarketConfirmText(context)
            updateMarketConfirmButtons()
            return
        }
        // 弹窗打开时标题/余额已被遮罩压住，不再重绘——否则会浮在弹窗之上
        if (settingsOpen) {
            renderSettingsLabels(context)
            if (System.currentTimeMillis() < settingsToastUntil) {
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    settingsToastText,
                    width / 2, height / 2 + DIALOG_H / 2 + 6, 0xFFFFFF
                )
            }
            return
        }
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.entry.title").formatted(Formatting.GOLD),
            width / 2, btnStartY - 14, 0xFFFFFF
        )
        // 底部三个小按钮上方的分割线（跨度 = 求购单按钮左缘到设置按钮右缘）
        // 放在「仅OP行底边」与「小按钮顶边」的正中间：小按钮 top=bgBottom()-28、行3底=btnStartY+totalH，
        // 两者相差 8px → 分割线 = bgBottom()-30（即小按钮上方 4px）
        context.fill(
            width / 2 - 96 + 6,
            bgBottom() - 30,
            width / 2 + 96 - 6,
            bgBottom() - 29,
            0xFF555555.toInt()
        )
        // 市场关闭：常驻红字横幅 + 点击入口时的 3 秒提示（OP 两者都不显示——
        // 开关按钮的双态图标就是 OP 自己的状态指示，横幅对 OP 是冗余噪音）
        if (!com.shusheng.cobblemarket.client.MarketStateCache.enabled) {
            val isOp = client?.player?.hasPermissionLevel(2) == true
            if (!isOp) {
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable("cobblemarket.market.closed_banner").string,
                    width / 2, btnStartY - 38, 0xFF5555
                )
                if (System.currentTimeMillis() < closedNoticeUntil) {
                    context.drawCenteredTextWithShadow(
                        textRenderer,
                        Text.translatable("cobblemarket.market.closed").string,
                        width / 2, height - 36, 0xFF5555
                    )
                }
            }
        }
        // 余额（标题上方，来自全局缓存）
        val bal = com.shusheng.cobblemarket.client.BalanceCache.balance
        if (bal.isNotEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.gui.balance", bal).string,
                width / 2, btnStartY - 26, 0x55FFFF
            )
        }
    }

    override fun shouldPause() = false

    companion object {
        private const val DIALOG_W = 200
        private const val DIALOG_H = 142
    }
}
