package com.shusheng.cobblemarket.screen

import com.mojang.blaze3d.systems.RenderSystem
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
import org.lwjgl.opengl.GL11
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MarketEntryScreen(private val skipDropAnim: Boolean = false) : Screen(Text.translatable("cobblemarket.entry.title")) {

    private var btnStartY = 0
    private var totalH = 0
    // 入口掉落动画开始时间（首次 init 记录；resize 重建不重置）。动画播完消失后才显示入口界面。
    // 从子界面返回入口（skipDropAnim=true）不播，只有按 K/smartphone 等外部打开才播
    private var dropAnimStart = if (skipDropAnim) 0L else -1L

    // 弹窗打开期间需要整体隐藏的下层控件（closeSettingsDialog 的 init 重建会恢复）
    private val entryButtons = mutableListOf<ClickableWidget>()
    private var settingsOpen = false
    private var settingsMarketButton: NineSliceButton? = null
    private var settingsAuctionButton: NineSliceButton? = null
    private var settingsDropButton: NineSliceButton? = null
    private var settingsEntryDropButton: NineSliceButton? = null
    private var settingsPikachuLoopButton: NineSliceButton? = null
    private var settingsGroudonFlyButton: NineSliceButton? = null
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
        if (dropAnimStart < 0) dropAnimStart = System.currentTimeMillis()
        ClientPlayNetworking.send(RequestBalancePayload())
        val centerX = width / 2
        val btnW = 87
        val btnH = 24
        // 行距 8（2026-08-21 曾压到 5 更紧凑；背景放大后恢复 8 更舒展）
        val gap = 8
        val colGap = 2
        val totalW = btnW * 2 + colGap
        val leftX = centerX - totalW / 2
        val rightX = leftX + btnW + colGap

        val isAdmin = client?.player?.hasPermissionLevel(2) == true
        val rowCount = if (isAdmin) 3 else 2
        // 行 1-2 用 gap 间隙；行 3（仅OP）与行 2 保持 4px 间隙，不随 gap 增大下移（与分割线/开关按钮上下间隙对称各 2px）
        totalH = btnH * rowCount + gap * (rowCount - 1) - if (isAdmin) gap - 5 else 0
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
                centerX - btnW / 2, startY + (btnH + gap) + btnH + 4, btnW, btnH,
                Text.translatable("cobblemarket.entry.op"),
                { openIfMarketEnabled(opBypass = true) { client?.setScreen(AdminScreen()) } },
                Identifier.of("cobblemarket", "textures/gui/op_icon.png")
            ))
        }

        // 背景底部两角的独立小按钮（纯图标 + row_background 风格，悬停显示名称），
        // OP：求购单移到设置按钮正上方（右列、与仅OP同排）；非 OP：求购单仍在左下角
        val cornerSize = 22
        val cornerY = bgBottom() - cornerSize - 5
        // 设置按钮 x 中心 = centerX+79，求购单以同一中心对齐（正上方），22 宽 → 左缘 centerX+68
        val buyOrderX = if (isAdmin) centerX + 68 else centerX - 96 + 6
        val buyOrderY = if (isAdmin) startY + (btnH + gap) + btnH + 4 + 1 else cornerY
        val buyOrderBtn = NineSliceButton(
            buyOrderX, buyOrderY,
            cornerSize, cornerSize,
            Text.literal(""),
            { openIfMarketEnabled { client?.setScreen(BuyOrderScreen()) } },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/buy_order_icon.png"),
            // 48×48 贴图缩到 18×18 显示（按钮 22×22 留 2px 边距，源图高分辨率保持锐利，照市场总开关按钮）
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
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
        Identifier.of("cobblemarket", if (MarketStateCache.enabled) "textures/gui/emergency_button_on.png" else "textures/gui/emergency_button_off.png")

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
        val dialogY = bgBottom() - 87 - DIALOG_H / 2 // 弹窗中心与背景中心对齐（背景中心 = bgBottom - 87）

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderSettingsDialogBackground(context)
            }
        })

        // 开关行格式：左侧标签文字（统一左列 centerX-80）+ 右侧开关图标按钮（统一右列，switch_icon_on/off，22×22）
        val switchX = centerX + 64 // 右列 x：弹窗右缘(centerX+100) 留 14px 边距
        settingsMarketButton = makeSwitchButton(ClientConfig.celebrationOnMarketBuy) { toggleMarketAnimation() }
        settingsMarketButton?.let { b -> b.x = switchX; b.y = dialogY + 30; addDrawableChild(b) }
        settingsAuctionButton = makeSwitchButton(ClientConfig.celebrationOnAuctionAndOrder) { toggleAuctionAnimation() }
        settingsAuctionButton?.let { b -> b.x = switchX; b.y = dialogY + 56; addDrawableChild(b) }
        settingsDropButton = makeSwitchButton(ClientConfig.dropOverflowOnClaim) { toggleDropOverflow() }
        settingsDropButton?.let { b -> b.x = switchX; b.y = dialogY + 82; addDrawableChild(b) }
        settingsEntryDropButton = makeSwitchButton(ClientConfig.entryDropAnimation) { toggleEntryDropAnimation() }
        settingsEntryDropButton?.let { b -> b.x = switchX; b.y = dialogY + 108; addDrawableChild(b) }
        settingsPikachuLoopButton = makeSwitchButton(ClientConfig.pikachuRunLoop) { togglePikachuLoop() }
        settingsPikachuLoopButton?.let { b -> b.x = switchX; b.y = dialogY + 134; addDrawableChild(b) }
        settingsGroudonFlyButton = makeSwitchButton(ClientConfig.groudonFly) { toggleGroudonFly() }
        settingsGroudonFlyButton?.let { b -> b.x = switchX; b.y = dialogY + 160; addDrawableChild(b) }
        addDrawableChild(NineSliceButton(
            centerX - 28, dialogY + 186, 56, 20,
            Text.translatable("cobblemarket.settings.done"),
            { closeSettingsDialog() }
        ))
    }

    private fun closeSettingsDialog() {
        settingsOpen = false
        settingsMarketButton = null
        settingsAuctionButton = null
        settingsDropButton = null
        settingsEntryDropButton = null
        settingsPikachuLoopButton = null
        settingsGroudonFlyButton = null
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

    private fun toggleEntryDropAnimation() {
        ClientConfig.setEntryDropAnimation(!ClientConfig.entryDropAnimation)
        settingsEntryDropButton?.iconLeft = switchIconFor(ClientConfig.entryDropAnimation)
        showSettingsToast("cobblemarket.settings.animation_entry", ClientConfig.entryDropAnimation)
    }

    private fun togglePikachuLoop() {
        ClientConfig.setPikachuRunLoop(!ClientConfig.pikachuRunLoop)
        settingsPikachuLoopButton?.iconLeft = switchIconFor(ClientConfig.pikachuRunLoop)
        showSettingsToast("cobblemarket.settings.pikachu_loop", ClientConfig.pikachuRunLoop)
    }

    private fun toggleGroudonFly() {
        ClientConfig.setGroudonFly(!ClientConfig.groudonFly)
        settingsGroudonFlyButton?.iconLeft = switchIconFor(ClientConfig.groudonFly)
        showSettingsToast("cobblemarket.settings.groudon_fly", ClientConfig.groudonFly)
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
        val dialogY = bgBottom() - 87 - DIALOG_H / 2 // 弹窗中心与背景中心对齐（背景中心 = bgBottom - 87）

        context.fill(0, 0, width, height, 0xC0000000.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, DIALOG_W, DIALOG_H, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.settings.title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF
        )
        // 开关项分割线：标题下 + 每两行之间（行按钮 y=30/56/82/108/134/160、行高 22 → 线在 27/54/80/106/132/158）
        val lineX1 = centerX - 88
        val lineX2 = centerX + 88
        for (lineY in intArrayOf(27, 54, 80, 106, 132, 158)) {
            context.fill(lineX1, dialogY + lineY, lineX2, dialogY + lineY + 1, 0xFF555555.toInt())
        }
    }

    /** 设置弹窗四行开关的标签文字（统一左列，与右侧图标按钮垂直对齐） */
    private fun renderSettingsLabels(context: DrawContext) {
        val centerX = width / 2
        val dialogY = bgBottom() - 87 - DIALOG_H / 2 // 弹窗中心与背景中心对齐（背景中心 = bgBottom - 87）
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
        context.drawTextWithShadow(
            textRenderer,
            toggleText("cobblemarket.settings.animation_entry", ClientConfig.entryDropAnimation),
            centerX - 80, dialogY + 115, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer,
            toggleText("cobblemarket.settings.pikachu_loop", ClientConfig.pikachuRunLoop),
            centerX - 80, dialogY + 141, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer,
            toggleText("cobblemarket.settings.groudon_fly", ClientConfig.groudonFly),
            centerX - 80, dialogY + 167, 0xFFFFFF
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
        // 背景底贴原 160 高背景底边再下移 20、向上扩展（不遮下方 HUD）；按钮布局不动。贴图 256×213
        context.drawTexture(bg, width / 2 - 128, bgBottom() - 213 + 20, 0f, 0f, 256, 213, 256, 213)
    }

    /** 入口掉落动画：图片从屏幕顶外缓出落到入口背景位置（0→1 放大），不弹跳；
     *  位置在掉落阶段结束后固定在目标位，alpha 供淡出阶段渐变 */
    private fun renderDropImage(context: DrawContext, elapsed: Long, alpha: Float) {
        val tex = Identifier.of("cobblemarket", "textures/gui/market_entry_drop.png")
        val targetX = width / 2 - 128
        val targetY = bgBottom() - 213 + 20
        val k = (elapsed.toFloat() / DROP_DURATION_MS).coerceIn(0f, 1f)
        fun smooth(v: Float): Float = v * v * (3f - 2f * v)
        val eased = smooth(k)
        val y = -213 + (targetY + 213) * eased // 起点背景底贴屏幕顶（完全在屏幕外）
        val scale = eased
        val cx = targetX + 128f
        val cy = y + 106.5f
        context.matrices.push()
        context.matrices.translate(cx.toDouble(), cy.toDouble(), 0.0)
        context.matrices.scale(scale, scale, 1f)
        // RGB 随 alpha 一起衰减：先变暗再消失（照精灵动画淡出）
        context.setShaderColor(alpha, alpha, alpha, alpha)
        context.drawTexture(tex, -128, -106, 0f, 0f, 256, 213, 256, 213)
        context.setShaderColor(1f, 1f, 1f, 1f)
        context.matrices.pop()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 掉落动画播完前按钮不可点（事件分发与渲染无关，init 已创建的按钮会照常响应，需显式拦截）
        val total = DROP_DURATION_MS + HOLD_DURATION_MS + FADE_DURATION_MS
        if (ClientConfig.entryDropAnimation && System.currentTimeMillis() - dropAnimStart < total) return true
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 打开入口的掉落动画（照购买精灵动画，个人设置可关）：
        // 下落+停留阶段只画暗底+掉落图；淡出阶段完整渲染入口界面、掉落图渐透明——界面随图淡出慢慢显现
        val animElapsed = System.currentTimeMillis() - dropAnimStart
        if (ClientConfig.entryDropAnimation) {
            if (animElapsed < DROP_DURATION_MS + HOLD_DURATION_MS) {
                renderDarkening(context)
                renderDropImage(context, animElapsed, 1f)
                return
            }
            val fadeElapsed = animElapsed - DROP_DURATION_MS - HOLD_DURATION_MS
            if (fadeElapsed < FADE_DURATION_MS) {
                // 交叉淡化：界面在下、掉落图在上渐透明。
                // flush 夹心（照精灵动画）：先提交界面，再画图并立刻提交——否则文字层会盖在图上面透出来；
                // 界面文字写进深度缓冲后会把后画的图整片剔除，画图前清掉（照 PokemonCelebrationAnimation）
                super.render(context, mouseX, mouseY, delta)
                context.draw()
                RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
                renderDropImage(context, animElapsed, 1f - fadeElapsed.toFloat() / FADE_DURATION_MS)
                context.draw()
                return
            }
        }
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
                    width / 2, bgBottom() - 87 - DIALOG_H / 2 + DIALOG_H + 6, 0xFFFFFF
                )
            }
            return
        }
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.entry.title").formatted(Formatting.GOLD, Formatting.BOLD),
            width / 2, btnStartY - 48, 0xFFFFFF
        )
        // 底部三个小按钮上方的分割线（跨度 = 避开背景左右边框各 25）
        // 放在「仅OP行底边」与「小按钮顶边」的正中间：小按钮 top=bgBottom()-27、行3底=bgBottom()-32，
        // 两者相差 5px → 分割线 = bgBottom()-30（上下间隙各 2px）
        context.fill(
            width / 2 - 128 + 25,
            bgBottom() - 30,
            width / 2 + 128 - 25,
            bgBottom() - 29,
            0xFFFF5555.toInt()
        )
        // 皮卡丘跑步动画：4 帧贴图循环切换；脚踩背景上沿（底 = 背景顶 + 2）
        val pikaFrame = ((System.currentTimeMillis() / PIKA_FRAME_MS) % 4).toInt()
        val pikaTex = Identifier.of("cobblemarket", "textures/gui/pikachu/pikachu_move_$pikaFrame.png")
        val pikaY = bgBottom() - 213 + 20 - 46
        if (ClientConfig.pikachuRunLoop) {
            // 皮卡丘跑酷：沿背景圆角矩形边缘顺时针环绕跑（圆角半径约 29px）。
            // 脚踩边缘：中心在边缘外侧 22px（=48/2-2，与直线模式脚位一致）；朝向随边旋转（右 90°、下 180°、左 270°，圆弧连续）
            val bgLeft = (width / 2 - 128).toFloat()
            val bgTop = (bgBottom() - 213 + 20).toFloat()
            val w = 256f
            val h = 213f
            val r = 29f
            val foot = 22f                        // 中心距边缘（脚踩线）
            val arc = (PI / 2 * (r + foot)).toFloat() // 每段 1/4 圆弧长（按中心路径半径计，保持角上匀速）
            val sideW = w - 2 * r                 // 上/下直边长
            val sideH = h - 2 * r                 // 左/右直边长
            val totalP = 2 * sideW + 2 * sideH + 4 * arc
            val t = System.currentTimeMillis() % PIKA_LOOP_MS
            val s = t * totalP / PIKA_LOOP_MS
            val s1 = sideW
            val s2 = s1 + arc
            val s3 = s2 + sideH
            val s4 = s3 + arc
            val s5 = s4 + sideW
            val s6 = s5 + arc
            val s7 = s6 + sideH
            val cx: Float
            val cy: Float
            val rotDeg: Float
            when {
                s < s1 -> { cx = bgLeft + r + s; cy = bgTop - foot; rotDeg = 0f }                                                                                // 上直边：左→右，脚朝下
                s < s2 -> { val th = -PI.toFloat() / 2 + (s - s1) / (r + foot); cx = bgLeft + w - r + (r + foot) * cos(th); cy = bgTop + r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }   // 右上圆弧
                s < s3 -> { cx = bgLeft + w + foot; cy = bgTop + r + (s - s2); rotDeg = 90f }                                                                   // 右直边：上→下，脚朝左
                s < s4 -> { val th = (s - s3) / (r + foot); cx = bgLeft + w - r + (r + foot) * cos(th); cy = bgTop + h - r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }                 // 右下圆弧
                s < s5 -> { cx = bgLeft + w - r - (s - s4); cy = bgTop + h + foot; rotDeg = 180f }                                                              // 下直边：右→左，脚朝上
                s < s6 -> { val th = PI.toFloat() / 2 + (s - s5) / (r + foot); cx = bgLeft + r + (r + foot) * cos(th); cy = bgTop + h - r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }   // 左下圆弧
                s < s7 -> { cx = bgLeft - foot; cy = bgTop + h - r - (s - s6); rotDeg = 270f }                                                                  // 左直边：下→上，脚朝右
                else -> { val th = PI.toFloat() + (s - s7) / (r + foot); cx = bgLeft + r + (r + foot) * cos(th); cy = bgTop + r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }            // 左上圆弧
            }
            context.matrices.push()
            context.matrices.translate(cx.toDouble(), cy.toDouble(), 0.0)
            context.matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(rotDeg))
            context.drawTexture(pikaTex, -24, -24, 0f, 0f, 48, 48, 48, 48)
            context.matrices.pop()
        } else {
            // 默认：沿背景顶部从左跑到右（单向循环），跑道左右对称（皮卡丘左右缘距背景左右缘各 8px）
            val trackLeft = width / 2 - 128 + 8
            val trackRight = width / 2 + 128 - 48 - 8
            val trackW = trackRight - trackLeft
            val runT = System.currentTimeMillis() % PIKA_CYCLE_MS
            val pikaX = trackLeft + (runT * trackW / PIKA_CYCLE_MS).toInt()
            context.drawTexture(pikaTex, pikaX, pikaY, 0f, 0f, 48, 48, 48, 48)
        }
        // 市场关闭：常驻红字横幅 + 点击入口时的 3 秒提示（OP 两者都不显示——
        // 开关按钮的双态图标就是 OP 自己的状态指示，横幅对 OP 是冗余噪音）
        if (!com.shusheng.cobblemarket.client.MarketStateCache.enabled) {
            val isOp = client?.player?.hasPermissionLevel(2) == true
            if (!isOp) {
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable("cobblemarket.market.closed_banner").string,
                    width / 2, btnStartY - 14, 0xFF5555
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
        // 余额（标题下方，来自全局缓存）
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
        private const val DIALOG_H = 220
        // 入口掉落动画三段：下落 → 落地停留 → 淡出（淡出期间入口界面从图下透出，慢慢显现）
        private const val DROP_DURATION_MS = 300L
        private const val HOLD_DURATION_MS = 100L
        private const val FADE_DURATION_MS = 120L
        // 皮卡丘跑步动画：直线一趟时长 / 每帧切换间隔 / 环绕一圈时长
        private const val PIKA_CYCLE_MS = 3000L
        private const val PIKA_FRAME_MS = 100L
        private const val PIKA_LOOP_MS = 8000L
    }
}
