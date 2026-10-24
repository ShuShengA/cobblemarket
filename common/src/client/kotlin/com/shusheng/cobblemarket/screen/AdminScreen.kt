package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.ClientPermissionCache
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.client.ClientConfig
import com.shusheng.cobblemarket.client.IdCardBackgroundTexture
import com.shusheng.cobblemarket.client.MarketStateCache
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_TEXTURE
import com.shusheng.cobblemarket.client.activeCurrencyUnit
import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.formatPriceShortLong
import com.shusheng.cobblemarket.client.currencyUnitWidth
import com.shusheng.cobblemarket.client.drawCurrencyUnit
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.FinanceStatsPayload
import com.shusheng.cobblemarket.network.PointOrdersPayload
import com.shusheng.cobblemarket.network.RequestFinanceStatsPayload
import com.shusheng.cobblemarket.network.RequestPointOrdersPayload
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
    /** 充值订单入口（权限管理右边）：有待确认订单时在它右上角点一个红点 */
    private var orderEntryBtn: NineSliceButton? = null
    private var pendingOrderCount = 0
    /** 玩家总览入口：图标每帧跟随服主换的 ID 卡背景图（与入口界面那颗同源），留个引用 */
    private var overviewBtn: NineSliceButton? = null
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

        /**
         * 底部**红色分割线**的半宽（= 背景半宽 128 − 两端各避开的 25px）。
         *
         * ⚠ **它与入口界面的 `MarketEntryScreen.DIVIDER_HALF` 是同一根线、同一个值** ——
         * 两个界面的图标按钮行都以这根线的左右端为边界（2026-09-25 用户要求统一布局）。
         * **改一个必须改另一个**，否则两界面切换时底部按钮会错位（背景本来就是同一张贴图）。
         */
        private const val DIVIDER_HALF = 103

        /** 充值订单入口的图标 */
        val POINT_ORDERS_ICON = Identifier.of("cobblemarket", "textures/gui/icon/point_orders_icon.png")

        /** 有待处理订单时挂在它右上角的角标（48×48 源图按 1/6 缩到 8×8，同精灵市场的红点） */
        val POINTS_SYMBOL = POINTS_SYMBOL_TEXTURE

        // 玩家总览入口的「一叠 ID 卡」：每张缩到这么宽、层间往右上错开这么多像素。
        // 按钮 22×22、内置背景比例 1280:940 ⇒ 单张 14×10、整组（含错开余量）18×14，四边都留得出余量
        // ⚠ 错开 3 → 2（2026-10-17 实测：3px 看着太散）
        private const val OVERVIEW_CARD_W = 14
        private const val OVERVIEW_LAYER_STEP = 2
    }

    /** 金融统计快照（进入管理面板时拉取）：准备金池告警行 */
    /**
     * 待确认充值订单数（红点用）。空列表也照收 —— 服主处理完最后一笔，红点要能灭掉。
     * 只认 `includeFinished = false` 那份：已完结列表是订单界面自己看的，跟红点无关。
     */
    fun onPointOrders(payload: PointOrdersPayload) {
        if (payload.includeFinished) return
        pendingOrderCount = payload.orders.size
    }

    fun onFinanceStats(payload: FinanceStatsPayload) {
        reservePool = payload.reservePool
        badDebtTotal = payload.badDebtTotal
    }

    /** 大额金额悬停提示：鼠标附近小面板显示完整千分位数字 */
    /** [amount] 只传**金额**（不带单位）—— 货币单位由这里补画在右侧（物品货币时是那件物品的图标） */
    private fun renderAmountTooltip(context: DrawContext, mx: Int, my: Int, amount: String) {
        val pad = 4
        val w = com.shusheng.cobblemarket.client.amountUnitWidth(textRenderer, amount, false) + 2 * pad
        val h = 14
        val tx = minOf(mx + 12, width - w - 4)
        val ty = maxOf(my - h - 4, 0)
        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx, ty, w, h, 1, ROW_BACKGROUND_TEX_H)
        // ⚠ 准备金池 / 坏账都是**银行口径**（RP 不进金融系统）⇒ isPoints 恒 false
        com.shusheng.cobblemarket.client.drawAmountWithUnit(
            context, textRenderer, amount, false, tx + pad, ty + 3, 0xFFD700.toInt()
        )
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
    private fun addPermButton(
        x: Int, y: Int, w: Int, h: Int, text: Text, node: String,
        // 按钮**专属**的悬停说明；不传则退回节点说明（`perm.node.<短名>.desc`）。
        // ⚠ **一个节点管多个按钮时必须传**（如 MARKET 管着精灵 / 物品两个入口）：节点说明回答的是
        //   「这个权限能干什么」，按钮说明回答的是「点这个入口进去能干什么」—— 在精灵按钮上写
        //   「精灵 / 物品」会让服主以为点进去两样都能看到（2026-09-25 用户报）。
        //   权限编辑弹窗里按**节点**列，那边用节点说明才对，两个界面不是同一件事
        tooltipKey: String? = null,
        open: () -> Unit
    ): TextureButton {
        val btn = addMenuButton(x, y, w, h, text, { openIfPermitted(node, open) })
        val key = tooltipKey ?: ("cobblemarket.perm.node." + node.removePrefix(MarketPermissions.PREFIX) + ".desc")
        btn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable(key)))
        permRefreshers += { btn.dimmed = !ClientPermissionCache.has(node) }
        return btn
    }

    /** 有权限才执行；否则提示。dimmed 不影响可点性，正是为了这里能给提示（照喵喵银行先例） */
    private fun openIfPermitted(node: String, open: () -> Unit) {
        if (ClientPermissionCache.has(node)) open() else denyFeedback()
    }

    /** 只限**服主**的按钮（权限管理 / 服务器配置）：同样常驻 + 置灰 + 点击提示。
     *  提示词与节点类按钮分开 —— 这两个不给任何节点（想放权就把对方加进服主名单），
     *  说「仅服主」比「你没有这项管理权限」准确 */
    private fun openIfOwner(open: () -> Unit) {
        if (ClientPermissionCache.isServerOwner()) open() else denyFeedback("cobblemarket.perm.owner_only")
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
        // 87 → 102：英文菜单项最长 "All Listed Pokémon"（约 95px），87 的按钮放不下、此前只能缩短词条。
        // ⚠ 上限 102 由**底部分割线**定死：红线 = centerX ± 103（宽 206），按钮组不得超过它
        //   （2026-09-21 用户报：110 时组宽 222，比红线宽 16px、视觉上压到了装饰边框）
        val btnW = 102
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
            MarketPermissions.MARKET,
            "cobblemarket.perm.panel.pokemon"        // 只进精灵管理 → 用按钮专属说明（见 addPermButton 的 ⚠）
        ) { client?.setScreen(AdminPokemonScreen()) }
        addPermButton(
            rightX, startY, btnW, btnH,
            Text.translatable("cobblemarket.op.item"),
            MarketPermissions.MARKET,
            "cobblemarket.perm.panel.item"
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
            MarketPermissions.HISTORY
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
        // 返回：右上角，**右边缘与入口界面「设置」按钮的右边缘对齐**
        //（两者 2026-09-25 起都贴红线右端 = `centerX + DIVIDER_HALF`，入口那边也是这么算的）。
        // 且**只向右固定、往左扩宽**（宽度改大时右边缘不动）。纵向**底边与标题底边平齐**
        // （-40+16 = 标题的 -33+9，再往下会压住「准备金池」那行金额）。
        // 宽度 44：同 TextureButton 的胶囊形圆角问题（24 宽时两端圆角只有 1.8×8 的尖角），
        // 再宽会贴到居中的标题（标题右缘约 centerX + 22，留 4px 间隙时**宽度上限 = 103 − 26 = 77**）
        addMenuButton(
            centerX + DIVIDER_HALF - 44, btnStartY - 40, 44, 16,
            Text.literal(""),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/back.png"),
            iconTexSize = 48, iconDisplaySize = 12,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        // 底部图标按钮（2026-09-12 从入口界面移来）：市场总开关居中，最左 / 最右两颗贴**底部分割线**两端。
        // ⚠ 2026-09-25 用户重新定边界：以红线（centerX ± 103，绘制见 render 末尾）为准 ——
        //   权限管理**左移**贴左端、服务器配置**右移**贴右端，其余按钮在左侧**成组**（组内 6px）。
        //   ⚠ 原先的两条约定就此作废：① 权限管理「与入口界面求购单按钮对齐」（centerX - 96 + 6）、
        //   ② 服务器配置与它「± 90 左右对称」。
        // 纵向在背景底部（红色分割线下方）。不占菜单按钮行，按钮区/背景/分割线布局不动
        val iconSize = 22
        val iconY = bgBottom() - iconSize - 5
        val iconRowHalf = DIVIDER_HALF               // 红线半宽（companion 里那个，与入口界面共用一根线）
        // 左侧三颗（权限管理 / 充值订单 / 兑换码）与**居中的**市场开关之间**空隙完全相等**（各 8px）。
        // ⚠ 由市场开关左缘**倒推**，不从红线左端正推 —— 正推时区间 92px 减 3 个按钮余 26px，
        //   除不尽（8.67/段），必然出现一处偏大。倒推的代价是**权限管理比红线左端收进 2px**
        //   （92 − 3×30 = 2）—— 用户 2026-09-25 拍板：宁可端点差 2px，也不要空隙不均。
        // ⚠ **右侧「市场开关 → 服务器配置」必然是大空隙（70px）**：左边 3 颗、右边 1 颗，
        //   「全行等距 + 市场开关居中 + 两端贴齐红线」三者数学上不可兼得
        //   （全行等距会是每段 24px，那时市场开关落在 +35、不再居中）。用户要的是后两者。
        val iconGap = 8                              // 左侧三处空隙，完全相等
        val iconStep = iconSize + iconGap
        val iconLeftX = (centerX - iconSize / 2) - iconStep * 3
        marketSwitchBtn = NineSliceButton(
            centerX - iconSize / 2, iconY, iconSize, iconSize,
            Text.literal(""),
            { openIfPermitted(MarketPermissions.MARKET_SWITCH) { toggleMarketEnabled() } },
            iconLeft = marketSwitchIcon(),
            // 48×48 贴图缩到 18×18 显示（按钮 22×22 留 2px 边距，源图高分辨率保持锐利）
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            // 入口型按钮专用底色（只有默认 + 悬停两段），与列表行里的操作按钮区分开
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S,
        )
        // 悬停补一行「需要哪个节点」—— 与右边两颗服主专属按钮的「怎么加入名单」同一思路：
        // OP 进得来面板、看得见这颗按钮，但没节点时它是灰的，得让他知道该找谁要（2026-09-24 拆节点后补）
        marketSwitchBtn?.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
            Text.translatable("cobblemarket.entry.market_switch")
                .append(Text.literal("\n"))
                .append(Text.translatable("cobblemarket.perm.need_node", Text.translatable("cobblemarket.perm.node.marketswitch")))
        ))
        menuButtons += addDrawableChild(marketSwitchBtn!!)

        // 自定义商店管理入口（2026-10-21 用户要求）：市场开关**右侧**第一颗。
        // 右边界不动：市场开关右缘 + 8px = centerX + 19；它与「玩家总览」之间因此是 10px
        //（不是左边那排统一的 8px）—— 右边三颗的右缘要贴红线 / 保持原位置，只能让这一处多 2px。
        // 图标 = 用户 2026-10-24 给的正式图（48×48）。显示 **16×16**（本排其它入口是 18×18）——
        // 用户看过后要求缩一点，摊位图细节多，略小一点反而清爽。
        val customShopBtn = NineSliceButton(
            centerX + iconSize / 2 + iconGap, iconY, iconSize, iconSize,
            Text.literal(""),
            { openIfPermitted(MarketPermissions.POINTS_SHOP) { client?.setScreen(CustomShopAdminScreen()) } },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/custom_shop.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 1f / 3f,
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S,
        )
        customShopBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
            Text.translatable("cobblemarket.op.custom_shops")
                .append(Text.literal("\n"))
                .append(
                    Text.translatable(
                        "cobblemarket.perm.need_node",
                        Text.translatable("cobblemarket.perm.node.points.shop", CurrencyService.pointsIcon())
                    )
                )
        ))
        menuButtons += addDrawableChild(customShopBtn)
        permRefreshers += { customShopBtn.dimmed = !ClientPermissionCache.has(MarketPermissions.POINTS_SHOP) }

        // 玩家总览入口（2026-10-16）：在「服务器配置」**左侧**（用户 2026-10-15 定）。
        // ⚠ 它与旁边两颗的判据不同：**不看服主身份**，有 overview 节点就能进
        //   （`canManage` 认「有任何节点」，所以节点持有人本来就进得来管理面板）。
        // ⚠⚠ **坐标不能照抄 1.2.2 的 `centerX + 42`**：那边服务器配置贴的是 centerX ± 90、
        //   这颗紧挨它左边（间隙 4），而**本线这排多了充值订单 / 兑换码两颗**、服务器配置改贴
        //   **红线右端**（centerX + 103）⇒ 写死值会让它悬在半空（左空 31px、右空 17px，
        //   2026-10-17 用户一眼看出「位置不对」）。改成**由服务器配置左缘倒推**、空隙取本排
        //   统一的 [iconGap]（左边三颗之间也是它），以后谁挪服务器配置这颗都跟着走。
        val overviewX = centerX + iconRowHalf - iconSize - iconGap - iconSize
        // 图标 = **一叠 ID 卡**（三张背景图往右上错开叠放，2026-10-17 用户定）：
        //   「一张卡 = 一个玩家」⇒ 一叠 = 全服玩家。源图与入口界面那颗**同源** ——
        //   跟着服主换的背景图走，所以尺寸/缩放每帧同步（见 [syncIdCardIcon]）。
        // ⚠ 构造时先按**内置**图摆好（下一帧 render 就会覆盖成当前生效那张），照入口界面那颗的写法
        val overviewBtn = NineSliceButton(
            overviewX, iconY, iconSize, iconSize,
            Text.literal(""),
            { openIfPermitted(MarketPermissions.OVERVIEW) { client?.setScreen(PlayerOverviewScreen()) } },
            iconLeft = IdCardBackgroundTexture.BUILTIN_TEX,
            iconTexW = IdCardBackgroundTexture.BUILTIN_WIDTH, iconTexH = IdCardBackgroundTexture.BUILTIN_HEIGHT,
            iconScale = OVERVIEW_CARD_W.toFloat() / IdCardBackgroundTexture.BUILTIN_WIDTH,
            iconLayers = 3, iconLayerStep = OVERVIEW_LAYER_STEP,
            // 入口型按钮专用底色（只有默认 + 悬停两段），与列表行里的操作按钮区分开
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S,
        )
        overviewBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
            Text.translatable("cobblemarket.perm.node.overview")
                .append(Text.literal("\n"))
                .append(Text.translatable("cobblemarket.perm.node.overview.desc"))
        ))
        menuButtons += addDrawableChild(overviewBtn)
        this.overviewBtn = overviewBtn
        // 跟入口界面同款：打开就问一次背景图（服主换了图这两颗按钮一起变；没变只回版本号、不重传图）
        IdCardBackgroundTexture.requestRefresh()

        val serverConfigBtn = NineSliceButton(
            centerX + iconRowHalf - iconSize, iconY, iconSize, iconSize,
            Text.literal(""),
            { openIfOwner { client?.setScreen(ServerConfigScreen()) } },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/icon/server_config_icon.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            // 入口型按钮专用底色（只有默认 + 悬停两段），与列表行里的操作按钮区分开
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S,
        )
        // 服主专属：悬停第二行**教怎么把自己加进服主名单** —— OP 进得来面板、点不动这两个按钮，
        // 这句提示就是他的出路（与启动日志的提醒配套，方案⑦）
        serverConfigBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
            Text.translatable("cobblemarket.op.server_config")
                .append(Text.literal("\n"))
                .append(Text.translatable("cobblemarket.perm.owner_only_hint"))
        ))
        menuButtons += addDrawableChild(serverConfigBtn)

        // 权限管理入口（这一行最左）：左缘贴**底部分割线**左端。
        // 只限服主：权限授予直接决定「谁管哪块」，下放给任何节点都能自我提权
        val permissionBtn = NineSliceButton(
            iconLeftX, iconY, iconSize, iconSize,
            Text.literal(""),
            { openIfOwner { client?.setScreen(PermissionScreen()) } },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/icon/permission.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            // 入口型按钮专用底色（只有默认 + 悬停两段），与列表行里的操作按钮区分开
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S,
        )
        permissionBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
            Text.translatable("cobblemarket.op.permission")
                .append(Text.literal("\n"))
                .append(Text.translatable("cobblemarket.perm.owner_only_hint"))
        ))
        menuButtons += addDrawableChild(permissionBtn)

        // 充值订单入口：**权限管理右边**（用户 2026-09-18：这一行当时就是给以后新功能留的位置）。
        // 待确认订单得有人处理玩家才拿得到 RP，所以除了入口还要**红点**提醒有活要干。
        // ⚠ 入口判 **`points.order` 节点**（2026-09-25 从「仅服主」改判为用户拍板的可授予）：
        //   与服务端（PointsNetwork 的三个管理包 + `/market points shop` 的管理子命令）**同一判据** ——
        //   两处必须一起改，否则会出现「服务端放行、界面却看不到入口」的半成品
        // ⚠ 授予前先确认对方看得到收款账单：**确认到账 = 加余额**（发钞权）
        val orderBtn = NineSliceButton(
            iconLeftX + iconStep, iconY, iconSize, iconSize,
            Text.literal(""),
            { openIfPermitted(MarketPermissions.POINTS_ORDER) { client?.setScreen(AdminPointOrdersScreen()) } },
            iconLeft = POINT_ORDERS_ICON,
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            // 入口型按钮专用底色 —— ⚠ **别再改回 `ROW_BACKGROUND_TEXTURE`**：那是**列表行**的底色，
            // 同排其余四颗用的都是 `BUTTON_TEXTURE_S`，混用会让这颗看起来像一行数据（用户 2026-09-25 指出）
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S,
        )
        // 悬停补一行「需要哪个节点」—— 与市场开关同一套写法：OP 进得来面板、但没节点时它是灰的，
        // 得让他知道该找谁要（2026-09-25 改判节点后补，原先只认服主时不需要）
        orderBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
            Text.translatable("cobblemarket.op.point_orders")
                .append(Text.literal("\n"))
                .append(
                    Text.translatable(
                        "cobblemarket.perm.need_node",
                        Text.translatable("cobblemarket.perm.node.points.order", CurrencyService.pointsIcon())
                    )
                )
        ))
        menuButtons += addDrawableChild(orderBtn)
        orderEntryBtn = orderBtn
        permRefreshers += { orderBtn.dimmed = !ClientPermissionCache.has(MarketPermissions.POINTS_ORDER) }
        // 进来就问一次有没有待确认的（只要数量，不要列表）；结果由 onPointOrders 落进红点
        sendToServer(RequestPointOrdersPayload(false, ""))

        // 兑换码入口：**充值订单右边** —— 2026-09-25 用户拍板从「服务器配置 → RP 配置」里挪出来，
        // 因为那个界面只有服主进得去，持 `points.code` 节点的人在界面上根本没有落点（只有命令能用）。
        // ⚠ 判 **`points.code` 节点**：**生成兑换码 = 印钞**，且这个界面会列出码前缀、能复制完整码 ——
        //   授予前先想清楚给谁（详见 docs/permission_nodes_plan.md ⑩ 的遗留段）
        val codeBtn = NineSliceButton(
            iconLeftX + iconStep * 2, iconY, iconSize, iconSize,
            Text.literal(""),
            { openIfPermitted(MarketPermissions.POINTS_CODE) { client?.setScreen(PointsCodeScreen()) } },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/icon/redeem_code_icon.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S,
        )
        codeBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
            Text.translatable("cobblemarket.op.redeem_codes")
                .append(Text.literal("\n"))
                .append(
                    Text.translatable(
                        "cobblemarket.perm.need_node",
                        Text.translatable("cobblemarket.perm.node.points.code", CurrencyService.pointsIcon())
                    )
                )
        ))
        menuButtons += addDrawableChild(codeBtn)
        permRefreshers += { codeBtn.dimmed = !ClientPermissionCache.has(MarketPermissions.POINTS_CODE) }

        // 底部图标按钮的置灰依据：市场开关 / 充值订单 / 兑换码各走自己的节点（**都不认 OP**），
        // 服务器配置与权限管理**只限服主**
        permRefreshers += { marketSwitchBtn?.dimmed = !ClientPermissionCache.has(MarketPermissions.MARKET_SWITCH) }
        permRefreshers += { serverConfigBtn.dimmed = !ClientPermissionCache.isServerOwner() }
        permRefreshers += { permissionBtn.dimmed = !ClientPermissionCache.isServerOwner() }
        // 玩家总览：走 overview 节点（与旁边两颗「只限服主」的不同）；没节点的 OP 看得到置灰按钮 + 悬停说明
        permRefreshers += { overviewBtn.dimmed = !ClientPermissionCache.has(MarketPermissions.OVERVIEW) }
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
        Identifier.of("cobblemarket", if (MarketStateCache.enabled) "textures/gui/button/emergency_button_on.png" else "textures/gui/button/emergency_button_off.png")

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
        val bg = Identifier.of("cobblemarket", "textures/gui/background/market_entry_background.png")
        // 背景底贴原 160 高背景底边再下移 20、向上扩展（不遮下方 HUD）；按钮布局不动。贴图 256×213
        // -29：补偿按钮下移的 5px，背景保持与入口界面（OP 三行时）完全一致，两界面切换背景不跳动
        val bgTop = btnStartY - 29
        context.drawTexture(bg, width / 2 - 128, bgTop + 160 - 213 + 20, 0f, 0f, 256, 213, 256, 213)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 总览那颗的图标跟着服主换的 ID 卡背景图走（与入口界面那颗同源，照它每帧同步）
        overviewBtn?.syncIdCardIcon(OVERVIEW_CARD_W)
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
        // 有待确认的充值订单 → 入口按钮右上角挂个 RP 符号。这是**唯一必须服主亲手做**的事
        //（个人收款码没有回调，模组判断不了玩家付没付），所以要显眼。
        // 画法照精灵市场「待领取」按钮那个红点：translate 到按钮右上角 + 1/6 缩放（48×48 → 8×8）
        if (pendingOrderCount > 0) {
            orderEntryBtn?.let { btn ->
                context.matrices.push()
                context.matrices.translate((btn.x + btn.width - 6).toDouble(), (btn.y - 2).toDouble(), 0.0)
                context.matrices.scale(1f / 6f, 1f / 6f, 1f)
                context.drawTexture(POINTS_SYMBOL, 0, 0, 0f, 0f, 48, 48, 48, 48)
                context.matrices.pop()
            }
        }
        // 服主名单为空（2026-09-24 用户要求）：背景贴图**外**紧贴下沿一行提示（位置与入口界面的关市提醒同款）。
        // ⚠⚠ 位置同入口界面：`bgBottom()` 是**逻辑底边**，**贴图底边 = `bgBottom() + 20`**
        //   （两界面同一张贴图、`bgBottom()` 同义），所以"贴图外"要从 `+20` 起算。
        //   `coerceAtMost` 兜底同入口界面：屏幕太矮时不夹住会超出屏幕底被裁掉
        // ⚠ 文案落在**「功能不可用」**而不是「不能使用管理面板」—— OP 本来就进得来（看到一片置灰），
        //   说"用不了面板"自相矛盾。它要回答的是 OP 此刻的疑问：为什么全灰？怎么解决？（添加方法见启动日志）
        if (ClientPermissionCache.isOwnerListEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.perm.no_owner_hint").string,
                width / 2, (bgBottom() + 22).coerceAtMost(height - 11), 0xFF5555
            )
        }
        // 底部按钮上方的分割线：与入口界面完全同款（颜色、跨度、相对底边的间距都一致），
        // 两界面本来就是同一张贴图，视觉上要能对齐。颜色统一走 GuiColors.DIVIDER（4 条线共用）
        context.fill(
            width / 2 - DIVIDER_HALF,
            bgBottom() - 30,
            width / 2 + DIVIDER_HALF,
            bgBottom() - 29,
            com.shusheng.cobblemarket.client.GuiColors.DIVIDER
        )
        // 金融系统告警行（批次 7）：准备金池 + 坏账总额；准备金为负（服主负债）整行红色告警
        if (reservePool >= 0 || badDebtTotal >= 0) {
            // 金额用单位缩写（k/M/B），悬停显示完整数字；金额金色，准备金为负整行红；
            // 准备金行已拉取（!= -1）就渲染——负值走红色告警不能丢行
            val alert = reservePool < 0
            val poolY = btnStartY - 20
            val debtY = btnStartY - 10
            if (reservePool != -1L) {
                // ⚠ 金额与单位拆开画（物品货币时单位是物品图标）：词条单位参数传空串占位；
                //   居中锚点与悬停命中区都按「整行含单位」的总宽算（原来单位在文本里，getWidth 已含）
                val poolLine = Text.translatable(
                    "cobblemarket.op.reserve_line",
                    Text.literal(formatPriceShortLong(reservePool)).formatted(if (alert) Formatting.RED else Formatting.GOLD),
                    Text.literal("")
                )
                val lineW = textRenderer.getWidth(poolLine) + currencyUnitWidth(textRenderer, false)
                val lineX = width / 2 - lineW / 2
                context.drawTextWithShadow(textRenderer, poolLine, lineX, poolY, 0xFFFFFF)
                drawCurrencyUnit(context, textRenderer, false, lineX + textRenderer.getWidth(poolLine), poolY,
                    if (alert) 0xFF5555 else 0xFFAA00)
                // 悬停显示完整数字（X 限行文字范围内，防面板外触发）
                if (mouseY in poolY..(poolY + 9) && Math.abs(mouseX - width / 2) <= lineW / 2) {
                    renderAmountTooltip(context, mouseX, mouseY, formatPriceLong(reservePool))
                }
            }
            if (badDebtTotal != -1L) {
                // 同上：单位单独画，居中锚点与悬停命中区都按含单位的总宽算
                val debtLine = Text.translatable(
                    "cobblemarket.op.bad_debt_line",
                    Text.literal(formatPriceShortLong(badDebtTotal)).formatted(Formatting.GOLD),
                    Text.literal("")
                )
                val lineW = textRenderer.getWidth(debtLine) + currencyUnitWidth(textRenderer, false)
                val lineX = width / 2 - lineW / 2
                context.drawTextWithShadow(textRenderer, debtLine, lineX, debtY, 0xFFFFFF)
                drawCurrencyUnit(context, textRenderer, false, lineX + textRenderer.getWidth(debtLine), debtY, 0xFFAA00)
                if (mouseY in debtY..(debtY + 9) && Math.abs(mouseX - width / 2) <= lineW / 2) {
                    renderAmountTooltip(context, mouseX, mouseY, formatPriceLong(badDebtTotal))
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
