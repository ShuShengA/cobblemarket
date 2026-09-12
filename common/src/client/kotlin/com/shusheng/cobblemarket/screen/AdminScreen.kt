package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.ClientPermissionCache
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.client.ClientConfig
import com.shusheng.cobblemarket.client.MarketStateCache
import com.shusheng.cobblemarket.client.activeCurrencyUnit
import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.formatPriceShortLong
import com.shusheng.cobblemarket.client.inlineCurrencyUnit
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.FinanceStatsPayload
import com.shusheng.cobblemarket.network.RequestFinanceStatsPayload
import com.shusheng.cobblemarket.network.SetMarketEnabledPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class AdminScreen : Screen(Text.translatable("cobblemarket.op.title")) {

    private var btnStartY = 0
    private var totalH = 0

    // 金融系统 OP 告警（批次 7）：准备金池（负值红色告警）+ 坏账总额；-1 = 未拉取
    private var reservePool = -1L
    private var badDebtTotal = -1L
    private var statsLoaded = false

    // 弹窗打开期间需要整体隐藏的下层控件（closeMarketConfirmDialog 的 init 重建会恢复）
    private val menuButtons = mutableListOf<ClickableWidget>()

    // 底部图标按钮（2026-09-12 从入口界面移来）：市场总开关（双态图标，仅 OP 界面本来就只有 OP 能进）
    private var marketSwitchBtn: NineSliceButton? = null
    // 停市确认弹窗（照入口界面原实现：3 秒冷静期 + 红白双色文字）
    private var marketConfirmOpen = false
    private var marketConfirmOpenedAt = 0L
    private var marketConfirmButton: NineSliceButton? = null
    private var marketCancelButton: NineSliceButton? = null

    companion object {
        // 皮卡丘跑步动画参数（与入口界面一致，共享时间基准实现切换不重置）
        private const val PIKA_CYCLE_MS = 3000L
        private const val PIKA_FRAME_MS = 100L
        private const val PIKA_LOOP_MS = 8000L
    }

    /** 金融统计快照（进入管理面板时拉取）：准备金池告警行 */
    fun onFinanceStats(payload: FinanceStatsPayload) {
        reservePool = payload.reservePool
        badDebtTotal = payload.badDebtTotal
    }

    /** 大额金额悬停提示：鼠标附近小面板显示完整千分位数字 */
    private fun renderAmountTooltip(context: DrawContext, mx: Int, my: Int, fullText: String) {
        val pad = 4
        val w = textRenderer.getWidth(fullText) + 2 * pad
        val h = 14
        val tx = minOf(mx + 12, width - w - 4)
        val ty = maxOf(my - h - 4, 0)
        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx, ty, w, h, 1, ROW_BACKGROUND_TEX_H)
        context.drawTextWithShadow(textRenderer, fullText, tx + pad, ty + 3, 0xFFD700.toInt())
        context.matrices.pop()
    }

    private fun addMenuButton(x: Int, y: Int, w: Int, h: Int, text: Text, action: net.minecraft.client.gui.widget.ButtonWidget.PressAction, iconLeft: Identifier? = null, iconTexSize: Int = 24, iconDisplaySize: Int = 12, tooltip: Text? = null): TextureButton {
        val btn = TextureButton(x, y, w, h, text, action, iconLeft = iconLeft, iconTexSize = iconTexSize, iconDisplaySize = iconDisplaySize, tooltip = tooltip)
        addDrawableChild(btn)
        menuButtons += btn
        return btn
    }

    /** 权限相关按钮的置灰刷新器：收到新快照时重算（init 重建会清空并重新登记） */
    private val permRefreshers = mutableListOf<() -> Unit>()

    /** 管理按钮：按权限节点置灰。**常驻不隐藏** —— 布局永远不变，无权限的点击给提示（用户 2026-09-12 拍板） */
    private fun addPermButton(x: Int, y: Int, w: Int, h: Int, text: Text, node: String, open: () -> Unit): TextureButton {
        val btn = addMenuButton(x, y, w, h, text, { openIfPermitted(node, open) })
        permRefreshers += { btn.dimmed = !ClientPermissionCache.has(node) }
        return btn
    }

    /** 有权限才执行；否则提示。dimmed 不影响可点性，正是为了这里能给提示（照喵喵银行先例） */
    private fun openIfPermitted(node: String, open: () -> Unit) {
        if (ClientPermissionCache.has(node)) open() else denyFeedback()
    }

    /** 只限 OP 的按钮（权限管理）：同样常驻 + 置灰 + 点击提示。
     *  提示词与节点类按钮分开 —— 权限管理不给任何节点，说「仅 OP」比「你没有这项管理权限」准确 */
    private fun openIfOp(open: () -> Unit) {
        if (ClientPermissionCache.isOp()) open() else denyFeedback("cobblemarket.perm.op_only")
    }

    private fun denyFeedback(key: String = "cobblemarket.perm.no_permission") {
        playFailSound()
        client?.player?.sendMessage(Text.translatable(key).formatted(Formatting.RED), true)
    }

    /** 收到权限快照时重算按钮置灰（登录 / 授予 / 收回时由网络层调用；界面没开就什么都不用做） */
    fun refreshPermissionState() {
        permRefreshers.forEach { it() }
    }

    override fun init() {
        super.init()
        menuButtons.clear()
        permRefreshers.clear()
        val centerX = width / 2
        val btnW = 87
        val btnH = 20
        val gap = 6
        val colGap = 2
        val totalW = btnW * 2 + colGap
        val leftX = centerX - totalW / 2
        val rightX = leftX + btnW + colGap
        // 行 5 取消：返回按钮改到右上角（与其他界面一致）。但起始 Y 仍按「5 行」的高度算，
        // 不按实际行数重新居中——按钮区矮 26px 会让整个界面下沉 13px，背景与皮卡丘（其位置是
        // 硬编码的绝对坐标）就跟入口界面错位，两界面切换背景会跳。省下的空间留给底部分割线与图标按钮
        val rowCount = 4
        totalH = btnH * rowCount + gap * (rowCount - 1)
        val startY = height / 2 - (btnH * 5 + gap * 4) / 2 + 5 // 按 5 行高度居中 = 与入口界面对齐
        btnStartY = startY

        if (!statsLoaded) {
            sendToServer(RequestFinanceStatsPayload())
            statsLoaded = true
        }

        // 行 1
        addPermButton(
            leftX, startY, btnW, btnH,
            Text.translatable("cobblemarket.op.pokemon"),
            MarketPermissions.MARKET
        ) { client?.setScreen(AdminPokemonScreen()) }
        addPermButton(
            rightX, startY, btnW, btnH,
            Text.translatable("cobblemarket.op.item"),
            MarketPermissions.MARKET
        ) { client?.setScreen(AdminItemScreen()) }
        // 行 2
        addPermButton(
            leftX, startY + btnH + gap, btnW, btnH,
            Text.translatable("cobblemarket.ban.title"),
            MarketPermissions.BAN
        ) { client?.setScreen(AdminBanScreen()) }
        addPermButton(
            rightX, startY + btnH + gap, btnW, btnH,
            Text.translatable("cobblemarket.entry.all_history"),
            MarketPermissions.MARKET
        ) { client?.setScreen(HistoryScreen(true)) }
        // 行 3：黑名单（左）+ 价格限制（右）
        addPermButton(
            leftX, startY + (btnH + gap) * 2, btnW, btnH,
            Text.translatable("cobblemarket.op.blacklist"),
            MarketPermissions.BLACKLIST
        ) { client?.setScreen(BlacklistScreen()) }
        addPermButton(
            rightX, startY + (btnH + gap) * 2, btnW, btnH,
            Text.translatable("cobblemarket.op.price_limit"),
            MarketPermissions.PRICE_LIMIT
        ) { client?.setScreen(PriceLimitScreen()) }
        // 行 4：所有拍卖（左）+ 所有求购（右）
        addPermButton(
            leftX, startY + (btnH + gap) * 3, btnW, btnH,
            Text.translatable("cobblemarket.op.auction"),
            MarketPermissions.AUCTION
        ) { client?.setScreen(AdminAuctionScreen()) }
        addPermButton(
            rightX, startY + (btnH + gap) * 3, btnW, btnH,
            Text.translatable("cobblemarket.op.buy_order"),
            MarketPermissions.BUY_ORDER
        ) { client?.setScreen(BuyOrderScreen(adminMode = true)) }
        // 返回：右上角，**右边缘与入口界面「设置」按钮的右边缘对齐**（入口：centerX + 96 - 22 - 6 + 22）
        // 且**只向右固定、往左扩宽**（宽度改大时右边缘不动）。纵向**底边与标题底边平齐**
        // （-40+16 = 标题的 -33+9，再往下会压住「准备金池」那行金额）。
        // 宽度 44：同 TextureButton 的胶囊形圆角问题（24 宽时两端圆角只有 1.8×8 的尖角），
        // 再宽会贴到居中的标题（标题右缘约 centerX + 22，留 4px 间隙时上限约 64）
        addMenuButton(
            centerX + 90 - 44, btnStartY - 40, 44, 16,
            Text.literal(""),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexSize = 48, iconDisplaySize = 12,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        // 底部图标按钮（2026-09-12 从入口界面移来）：市场总开关居中，服务器配置贴近右边缘
        // ——右边缘与左下角权限按钮左边缘左右对称（centerX ± 90，2026-09-12 用户要求）。
        // 纵向在背景底部（红色分割线下方）。不占菜单按钮行，按钮区/背景/分割线布局不动
        val iconSize = 22
        val iconY = bgBottom() - iconSize - 5
        marketSwitchBtn = NineSliceButton(
            centerX - iconSize / 2, iconY, iconSize, iconSize,
            Text.literal(""),
            { openIfPermitted(MarketPermissions.MARKET) { toggleMarketEnabled() } },
            iconLeft = marketSwitchIcon(),
            // 48×48 贴图缩到 18×18 显示（按钮 22×22 留 2px 边距，源图高分辨率保持锐利）
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            texture = ROW_BACKGROUND_TEXTURE,
            texH = ROW_BACKGROUND_TEX_H
        )
        marketSwitchBtn?.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.entry.market_switch")))
        menuButtons += addDrawableChild(marketSwitchBtn!!)

        val serverConfigBtn = NineSliceButton(
            centerX + 90 - iconSize, iconY, iconSize, iconSize,
            Text.literal(""),
            { openIfPermitted(MarketPermissions.CONFIG) { client?.setScreen(ServerConfigScreen()) } },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/server_config_icon.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            texture = ROW_BACKGROUND_TEXTURE,
            texH = ROW_BACKGROUND_TEX_H
        )
        serverConfigBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.op.server_config")))
        menuButtons += addDrawableChild(serverConfigBtn)

        // 权限管理入口（左下角）：左边缘与入口界面「求购单」按钮对齐（入口：centerX - 96 + 6）。
        // 权限节点功能（docs/permission_nodes_plan.md）S1 的界面入口：图标与位置先定下来，
        // 点击暂未接界面（S1 实装时把 action 接上权限管理界面）
        val permissionBtn = NineSliceButton(
            centerX - 96 + 6, iconY, iconSize, iconSize,
            Text.literal(""),
            { openIfOp { client?.setScreen(PermissionScreen()) } },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/permission.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            texture = ROW_BACKGROUND_TEXTURE,
            texH = ROW_BACKGROUND_TEX_H
        )
        permissionBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.op.permission")))
        menuButtons += addDrawableChild(permissionBtn)

        // 底部三个图标按钮的置灰依据：总开关 / 服务器配置走节点，权限管理只限 OP
        permRefreshers += { marketSwitchBtn?.dimmed = !ClientPermissionCache.has(MarketPermissions.MARKET) }
        permRefreshers += { serverConfigBtn.dimmed = !ClientPermissionCache.has(MarketPermissions.CONFIG) }
        permRefreshers += { permissionBtn.dimmed = !ClientPermissionCache.isOp() }
        refreshPermissionState()

        // resize 重建按钮后 visible 全是 true，停市确认弹窗开着时需恢复隐藏（老坑，照入口界面）
        if (marketConfirmOpen) menuButtons.forEach { it.visible = false }
    }

    /** 贴图内「逻辑背景」（160 高那层）的底边——**与入口界面 bgBottom() 同义**，底部那排图标按钮
     *  与分割线都相对它定位（贴图自身在这条线以下还多延伸 20px 给 HUD 让位，绘制时才另加）。
     *  ⚠ 别当成贴图底边用：贴图底边 = 这里 + 20，拿它定位会让按钮整体偏低 20px */
    private fun bgBottom(): Int = btnStartY + 131

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val wasMarketConfirmOpen = marketConfirmOpen
        super.resize(client, width, height)
        if (wasMarketConfirmOpen) {
            marketConfirmOpen = false
            openMarketConfirmDialog()
        }
    }

    // ── 市场总开关按钮（2026-09-12 从入口界面移来）：乐观切换 + 服务端广播回状态 ──

    private fun marketSwitchIcon(): Identifier =
        Identifier.of("cobblemarket", if (MarketStateCache.enabled) "textures/gui/emergency_button_on.png" else "textures/gui/emergency_button_off.png")

    /** 服务端广播 MarketStatePayload 时同步刷新图标（切换后 / 他处命令切换时都能跟上） */
    fun updateMarketSwitchIcon() {
        marketSwitchBtn?.iconLeft = marketSwitchIcon()
    }

    private fun toggleMarketEnabled() {
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
        sendToServer(SetMarketEnabledPayload(target))
        updateMarketSwitchIcon()
        // 服务端广播（MarketStatePayload）会以权威状态刷新状态；这里先乐观更新，避免等待的迟滞感
    }

    // ── 停市确认弹窗（照入口界面原实现：3 秒冷静期 + 红白双色文字） ──

    private fun openMarketConfirmDialog() {
        marketConfirmOpen = true
        marketConfirmOpenedAt = System.currentTimeMillis()
        // 隐藏下层控件（弹窗打开期间不可交互；closeMarketConfirmDialog 的 init 重建会恢复）
        menuButtons.forEach { it.visible = false }

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
        // 冷静期内点击：置灰按钮仍可点（dimmed 模式），播 fail 音效提示，不执行
        if (System.currentTimeMillis() - marketConfirmOpenedAt < 3000L) {
            playFailSound()
            return
        }
        sendMarketEnabled(false)
        closeMarketConfirmDialog()
    }

    private fun renderMarketConfirmBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogW = 280
        val dialogH = 150
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        drawScreenDimMask(context, width, height)
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
        marketConfirmButton?.dimmed = !canConfirm
        marketConfirmButton?.message = if (canConfirm)
            Text.translatable("cobblemarket.market.confirm_yes")
        else
            Text.translatable("cobblemarket.market.confirm_yes_countdown", cooldownLeft)
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val bg = Identifier.of("cobblemarket", "textures/gui/market_entry_background.png")
        // 背景底贴原 160 高背景底边再下移 20、向上扩展（不遮下方 HUD）；按钮布局不动。贴图 256×213
        // -29：补偿按钮下移的 5px，背景保持与入口界面（OP 三行时）完全一致，两界面切换背景不跳动
        val bgTop = btnStartY - 29
        context.drawTexture(bg, width / 2 - 128, bgTop + 160 - 213 + 20, 0f, 0f, 256, 213, 256, 213)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        // 停市确认弹窗：下层控件已隐藏，标题/告警/皮卡丘不再重绘（否则会浮在弹窗遮罩之上），照入口界面
        if (marketConfirmOpen) {
            renderMarketConfirmText(context)
            updateMarketConfirmButtons()
            return
        }
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.op.title").formatted(Formatting.GOLD, Formatting.BOLD),
            width / 2, btnStartY - 33, 0xFFFFFF
        )
        // 底部按钮上方的分割线：与入口界面完全同款（颜色、跨度、相对底边的间距都一致），
        // 两界面本来就是同一张贴图，视觉上要能对齐
        context.fill(
            width / 2 - 128 + 25,
            bgBottom() - 30,
            width / 2 + 128 - 25,
            bgBottom() - 29,
            0xFFFF5555.toInt()
        )
        // 金融系统告警行（批次 7）：准备金池 + 坏账总额；准备金为负（服主负债）整行红色告警
        if (reservePool >= 0 || badDebtTotal >= 0) {
            // 金额用单位缩写（k/M/B），悬停显示完整数字；金额金色，准备金为负整行红；
            // 准备金行已拉取（!= -1）就渲染——负值走红色告警不能丢行
            val alert = reservePool < 0
            val poolY = btnStartY - 20
            val debtY = btnStartY - 10
            if (reservePool != -1L) {
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable(
                        "cobblemarket.op.reserve_line",
                        Text.literal(formatPriceShortLong(reservePool)).formatted(if (alert) Formatting.RED else Formatting.GOLD),
                        Text.literal(inlineCurrencyUnit()).formatted(if (alert) Formatting.RED else Formatting.GOLD)
                    ),
                    width / 2, poolY, 0xFFFFFF
                )
                // 悬停显示完整数字（X 限行文字范围内，防面板外触发）
                val lineW = textRenderer.getWidth(
                    Text.translatable(
                        "cobblemarket.op.reserve_line",
                        formatPriceShortLong(reservePool), inlineCurrencyUnit()
                    )
                )
                if (mouseY in poolY..(poolY + 9) && Math.abs(mouseX - width / 2) <= lineW / 2) {
                    renderAmountTooltip(context, mouseX, mouseY, "${formatPriceLong(reservePool)} ${activeCurrencyUnit()}")
                }
            }
            if (badDebtTotal != -1L) {
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable(
                        "cobblemarket.op.bad_debt_line",
                        Text.literal(formatPriceShortLong(badDebtTotal)).formatted(Formatting.GOLD),
                        Text.literal(inlineCurrencyUnit()).formatted(Formatting.GOLD)
                    ),
                    width / 2, debtY, 0xFFFFFF
                )
                val lineW = textRenderer.getWidth(
                    Text.translatable(
                        "cobblemarket.op.bad_debt_line",
                        formatPriceShortLong(badDebtTotal), inlineCurrencyUnit()
                    )
                )
                if (mouseY in debtY..(debtY + 9) && Math.abs(mouseX - width / 2) <= lineW / 2) {
                    renderAmountTooltip(context, mouseX, mouseY, "${formatPriceLong(badDebtTotal)} ${activeCurrencyUnit()}")
                }
            }
        }
        // 皮卡丘跑步动画（照入口界面，同一时间基准公式——两界面切换时位置/帧延续，不从头跑）
        val pikaFrame = ((System.currentTimeMillis() / PIKA_FRAME_MS) % 4).toInt()
        val pikaTex = Identifier.of("cobblemarket", "textures/gui/pikachu/pikachu_move_$pikaFrame.png")
        if (ClientConfig.pikachuRunLoop) {
            // 皮卡丘跑酷：沿背景圆角矩形边缘顺时针环绕跑（圆角半径约 29px）。
            // 脚踩边缘：中心在边缘外侧 22px（=48/2-2，与直线模式脚位一致）；朝向随边旋转（右 90°、下 180°、左 270°，圆弧连续）。
            // bgTop 与入口界面统一用绝对位置（height/2-119）：切换界面环绕位置不跳动
            val bgLeft = (width / 2 - 128).toFloat()
            val bgTop = (height / 2 - 119).toFloat()
            val w = 256f
            val h = 213f
            val r = 29f
            val foot = 22f
            val arc = (PI / 2 * (r + foot)).toFloat() // 按中心路径半径计，保持角上匀速
            val sideW = w - 2 * r
            val sideH = h - 2 * r
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
                s < s1 -> { cx = bgLeft + r + s; cy = bgTop - foot; rotDeg = 0f }
                s < s2 -> { val th = -PI.toFloat() / 2 + (s - s1) / (r + foot); cx = bgLeft + w - r + (r + foot) * cos(th); cy = bgTop + r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }
                s < s3 -> { cx = bgLeft + w + foot; cy = bgTop + r + (s - s2); rotDeg = 90f }
                s < s4 -> { val th = (s - s3) / (r + foot); cx = bgLeft + w - r + (r + foot) * cos(th); cy = bgTop + h - r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }
                s < s5 -> { cx = bgLeft + w - r - (s - s4); cy = bgTop + h + foot; rotDeg = 180f }
                s < s6 -> { val th = PI.toFloat() / 2 + (s - s5) / (r + foot); cx = bgLeft + r + (r + foot) * cos(th); cy = bgTop + h - r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }
                s < s7 -> { cx = bgLeft - foot; cy = bgTop + h - r - (s - s6); rotDeg = 270f }
                else -> { val th = PI.toFloat() + (s - s7) / (r + foot); cx = bgLeft + r + (r + foot) * cos(th); cy = bgTop + r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }
            }
            context.matrices.push()
            context.matrices.translate(cx.toDouble(), cy.toDouble(), 0.0)
            context.matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(rotDeg))
            context.drawTexture(pikaTex, -24, -24, 0f, 0f, 48, 48, 48, 48)
            context.matrices.pop()
        } else {
            // 默认：沿背景顶部从左跑到右，跑道左右对称（皮卡丘左右缘距背景左右缘各 8px），脚踩背景上沿；
            // y 与入口界面（OP 时）绝对屏幕位置一致：切换界面皮卡丘不跳动
            val trackLeft = width / 2 - 128 + 8
            val trackRight = width / 2 + 128 - 48 - 8
            val trackW = trackRight - trackLeft
            val runT = System.currentTimeMillis() % PIKA_CYCLE_MS
            val pikaX = trackLeft + (runT * trackW / PIKA_CYCLE_MS).toInt()
            val pikaY = height / 2 - 165
            context.drawTexture(pikaTex, pikaX, pikaY, 0f, 0f, 48, 48, 48, 48)
        }
    }

    override fun shouldPause() = false
}
