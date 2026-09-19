package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.GuiColors
import com.shusheng.cobblemarket.client.POINTS_ICON_TEXTURE
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_INLINE_SIZE
import com.shusheng.cobblemarket.client.PayQrTexture
import com.shusheng.cobblemarket.client.PointsShopCache
import com.shusheng.cobblemarket.client.TierIconTextures
import com.shusheng.cobblemarket.client.unitGap
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.PointOrderNumbers
import com.shusheng.cobblemarket.currency.PointOrderStatus
import com.shusheng.cobblemarket.currency.PointOrderVoidReason
import com.shusheng.cobblemarket.currency.PointShopService
import com.shusheng.cobblemarket.network.CancelPointOrderPayload
import com.shusheng.cobblemarket.network.CreatePointOrderPayload
import com.shusheng.cobblemarket.network.MarkPointOrderPaidPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PointOrderInfo
import com.shusheng.cobblemarket.network.RequestPayQrPayload
import com.shusheng.cobblemarket.network.RequestPointsShopPayload
import com.shusheng.cobblemarket.network.RequestTierIconPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * **购买 RP**（充值界面）：档位卡 →（内容区右上角「查看订单」）→ **订单列表弹窗** → 收款码弹窗。
 *
 * 流程（方案「RP 充值」）：选档位下单 → 拿订单号 + 收款码 + **实付金额** → 玩家扫码付款 →
 * 点「我已支付」→ 服务端转「待确认」→ 服主对着收款账单点确认才发放。
 *
 * ⚠ **模组不知道玩家有没有真的付款**，「我已支付」只是声明；订单号与金额才是服主核对账单的键。
 *
 * ⚠ 一个玩家**可以同时有多笔**未完结订单（2026-09-18 用户拍板：想改充别的档位不该被卡死），
 * 所以**不再进入界面就强弹收款码**，而是给一个「查看订单」入口自己翻。
 *
 * ⚠ **两个弹窗的面板一律在 [renderBackground] 里画**（照 AdminAuctionScreen 的确认弹窗）：
 * 控件由 `super.render()` 绘制，面板若放在 `render()` 里会**盖住弹窗自己的按钮**
 * （2026-09-18 修：原来收款码弹窗的面板就画在 render 里，三个按钮全被压住）。
 */
class PointsRechargeScreen : Screen(
    Text.translatable("cobblemarket.points.recharge.title", CurrencyService.pointsTitleIcon())
) {

    private val tierButtons = mutableListOf<NineSliceButton>()
    private val orderButtons = mutableListOf<NineSliceButton>()
    private val dialogButtons = mutableListOf<NineSliceButton>()
    private var backButton: NineSliceButton? = null
    private var ordersEntryButton: NineSliceButton? = null
    private var ordersCloseButton: NineSliceButton? = null

    /** 订单列表展开中 */
    private var orderListOpen = false

    /** 订单弹窗滚到第几行（订单现在含历史，可能有几十笔，一屏放不下） */
    private var orderScroll = 0

    /** 订单弹窗一次显示几行（至少 1 行） */
    private fun visibleOrderRows(): Int =
        minOf(PointsShopCache.orders.size, MAX_VISIBLE_ORDER_ROWS).coerceAtLeast(1)

    private fun maxOrderScroll(): Int =
        (PointsShopCache.orders.size - visibleOrderRows()).coerceAtLeast(0)

    /** 按当前滚动位置重排订单行的「查看」按钮（不重建界面，免得把玩家正在输入的兑换码框踢出焦点） */
    private fun applyOrderScroll() {
        val dy = ordersDialogY()
        orderButtons.forEachIndexed { index, button ->
            val rel = index - orderScroll
            val visible = rel in 0 until visibleOrderRows()
            button.visible = visible
            if (visible) {
                button.setPosition(
                    button.x,
                    dy + ORDERS_DIALOG_HEADER_H + rel * ORDER_ROW_H + (ORDER_ROW_H - ORDER_VIEW_BTN_H) / 2
                )
            }
        }
    }
    /** 正在看收款码的那笔订单号（null = 没开收款码弹窗） */
    private var viewingOrderNo: String? = null
    /** 已为哪一笔订单请求过收款码（换单要重拉：每档可能配了自己的码） */
    private var qrRequestedFor: String? = null
    /** 本次进界面是否已请求过快照（init 会被 resize 反复调用，别重复发） */
    private var requested = false

    /**
     * 本次进界面已请求过的券图文件名 —— 快照连来时别把同一个请求重发好几遍。
     * 界面的实例每次进都新建，所以重进界面会自然重试一次（合理：服主可能刚把图补上）。
     */
    private val requestedIcons = mutableSetOf<String>()

    /** 档位卡滚到第几行（0 = 顶部）；只有档位行数超过「装得下的行数」时才有得滚（见 [maxScrollRow]） */
    private var scrollRow = 0

    /**
     * 滚动位置（**像素**，缓动后的当前值）：渲染与按钮定位都读它。
     * 用像素而不是行号，滚动才是「滑」出来的而不是一行一行跳（2026-09-18 用户指出跳得看不出滚动）。
     */
    private var scrollPx = 0f

    /** 滚轮改的是这个目标值，[scrollPx] 每帧朝它逼近 */
    private var scrollTargetPx = 0f

    /**
     * 点档位按钮那一刻的订单号快照 —— 快照回来据此认出**刚下的那一笔**，自动弹它的收款码。
     * 用「前一份订单号集合」而不是「取列表最后一条」：下单可能被拒（订单数达上限），
     * 那时列表没变，最后一条是旧订单，弹出来就张冠李戴了。
     */
    private var ordersBeforeCreate: Set<String>? = null

    /** 结果提示（下单被拒 / 取消成功 / 已支付已提交…）；null = 没有提示要显示 */
    private var resultText: Text? = null

    /** 上面那条提示是成功还是失败（决定颜色：成功绿 / 失败红） */
    private var resultOk = false
    private var resultToastUntil = 0L

    override fun init() {
        super.init()
        if (!requested) {
            // 重进界面时给上次读不出来的券图**一次重试机会**：服主可能刚把图补上，
            // 而客户端除非重连否则不会自己清那个记录
            TierIconTextures.retryFailed()
            sendToServer(RequestPointsShopPayload(true))
            requested = true
        }
        rebuild()
    }

    /**
     * 请求还没到货的自定义券图。**没配图的档位一个包都不发**（那是绝大多数情况）——
     * 券图文件名随快照下发，字节按需取，客户端按文件名缓存。
     */
    private fun requestMissingTierIcons() {
        PointsShopCache.tiers.forEach { tier ->
            val file = tier.iconFile
            if (file.isEmpty()) return@forEach
            if (TierIconTextures.get(file) != null || TierIconTextures.isFailed(file)) return@forEach
            if (!requestedIcons.add(file)) return@forEach
            sendToServer(RequestTierIconPayload(file))
        }
    }

    /**
     * 档位卡滚轮翻行（只有档位行数超过「装得下的行数」时才有得滚，见 [maxScrollRow]）。
     *
     * 只挪按钮位置、**不重建界面** —— 重建会把玩家正在敲的兑换码输入框内容与焦点搅乱。
     */
    override fun mouseScrolled(
        mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double
    ): Boolean {
        // 订单弹窗打开时滚它自己的列表
        if (orderListOpen) {
            val max = maxOrderScroll()
            if (max <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
            val t = (orderScroll - verticalAmount.toInt()).coerceIn(0, max)
            if (t == orderScroll) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
            orderScroll = t
            applyOrderScroll()
            return true
        }
        // 其余弹窗 / 没得滚时就交给 super（别把别处的滚动吃掉）
        if (viewingOrderNo != null || maxScrollRow() <= 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }
        val target = (scrollRow - verticalAmount.toInt()).coerceIn(0, maxScrollRow())
        if (target == scrollRow) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }
        scrollRow = target
        // 滚轮只改**目标**位置；实际滚动由 render 每帧缓动推进（stepTierScroll），
        // 所以是滑过去的而不是瞬间跳一行
        val cardW = tierCardW(PointsMarketBackground.rect(width, height))
        scrollTargetPx = (target * tierRowStride(cardW)).toFloat()
        return true
    }

    /**
     * 右侧滚动条：有装不下的档位行才画 —— 否则玩家不知道下面还有档位。
     *
     * 画法与服务器配置界面 / RP 市场配置界面同一套（2px 宽、半透明轨道 + 不透明滑块）。
     * 位置：**两条红线之间、档位卡区右侧那条缝里** —— 卡片区右边缘取 `inset`，
     * 而红线右端到 `texPx(78)`，前者更靠内，中间正好腾出十几像素。
     */
    private fun renderScrollbar(context: DrawContext, bg: PointsMarketBackground.Rect) {
        val maxScroll = maxScrollRow()
        if (maxScroll <= 0) return
        val inset = PointsMarketBackground.borderInset(width, height)
        val x = bg.x + bg.w - inset + 3
        val top = tierAreaTop(bg)
        val trackH = (tierAreaBottom(bg) - tierAreaTop(bg)).coerceAtLeast(1)
        // 这里是**按行**滚动：滑块高直接按「装得下的行数 / 总行数」算
        val thumbH = maxOf(16, trackH * fittedTierRows(tierCardW(bg)) / tierRows())
        val thumbY = top + (trackH - thumbH) * scrollRow / maxScroll
        context.fill(x, top, x + 2, top + trackH, 0x40FFFFFF)
        context.fill(x, thumbY, x + 2, thumbY + thumbH, 0xC0FFFFFF.toInt())
    }

    /** 把档位卡按钮挪到当前滚动位置（含可见性） */
    private fun applyTierScroll() {
        val bg = PointsMarketBackground.rect(width, height)
        val cardW = tierCardW(bg)
        // 同上面的 scissor：按钮也裁到**红线上**（半露的那截不能糊在分割线外侧）
        val areaTop = dividerTopY(bg)
        val areaBottom = dividerBottomY(bg)
        tierButtons.forEachIndexed { index, button ->
            val visible = isRowVisible(index / TIER_COLS, cardW)
            button.visible = visible
            // ⚠ 半露在档位区外的按钮要**裁到区内**：券图那层有外层的 scissor 兜着，
            //   但控件是 super.render() 统一画的、吃不到那个 scissor —— 不裁的话滚出去的
            //   半截按钮会糊在上下分割线上（2026-09-19 用户实测）
            button.clipRect = if (visible) intArrayOf(0, areaTop, width, areaBottom) else null
            if (visible) {
                val (x, y) = tierCardPos(bg, index, cardW)
                button.setPosition(x, y + tierIconH(cardW) + TIER_ICON_V_GAP)
            }
        }
    }

    /** 服务端下发快照后重走一遍（档位、订单列表、货币单位都可能变了） */
    fun onShopInfo() {
        // 刚点过档位：快照里**新冒出来的那一笔**就是它 —— 自动弹出收款码。
        // 方案原文是「选档位下单 → **拿订单号 + 收款码 + 实付金额**」；那句话里的「不再强弹收款码」
        // 指的是**进界面时**不弹历史订单，不是下单后不弹 —— 漏了这一步就成了
        // 「点一下毫无反应、却已经多了一笔订单」，玩家只能自己去「查看订单」里翻，
        // 还容易因此连点出好几笔（2026-09-18 用户实测）。
        ordersBeforeCreate?.let { before ->
            ordersBeforeCreate = null
            PointsShopCache.orders.firstOrNull { it.orderNo !in before }?.let { fresh ->
                viewingOrderNo = fresh.orderNo
                qrRequestedFor = null
            }
        }
        requestMissingTierIcons()
        rebuild()
    }

    /**
     * 下单 / 取消 / 我已支付的结果：清掉待认领标志并**显示在界面里**。
     * 光靠聊天栏那一行 + 一声提示音容易被漏看，玩家看到的就是「点了没反应」。
     *
     * ⚠ **成功也必须显示**（2026-09-19 用户实测）：原来这里 `if (payload.success) return`，
     * 于是点了「取消订单」之后界面上什么都没有 —— 虽然服务端真的作废了，玩家却以为没生效
     * （服务端那句「订单 XXX 已取消」也没发给界面）。
     */
    fun onMarketResult(payload: MarketResultPayload) {
        ordersBeforeCreate = null
        resultText = payload.message
        resultOk = payload.success
        resultToastUntil = System.currentTimeMillis() + 4000
    }

    /** 收款码图片到了：render 每帧读 PayQrTexture，这里无需额外动作 */
    fun onQrImage() {
    }

    private fun rebuild() {
        // 档位数可能被服主改少（快照刷新）：把滚动位置夹回合法范围，否则会停在一片空白上
        scrollRow = scrollRow.coerceIn(0, maxScrollRow())
        orderScroll = orderScroll.coerceIn(0, maxOrderScroll())
        clearChildren()
        tierButtons.clear()
        orderButtons.clear()
        dialogButtons.clear()
        backButton = null
        ordersEntryButton = null
        ordersCloseButton = null
        // 快照刷新后，正看着的那笔订单可能已经没了（被服主处理/超时作废）
        if (viewingOrderNo != null && PointsShopCache.orders.none { it.orderNo == viewingOrderNo }) {
            viewingOrderNo = null
        }
        if (PointsShopCache.orders.isEmpty()) orderListOpen = false

        when {
            viewingOrderNo != null -> buildQrDialog(PointsShopCache.orders.first { it.orderNo == viewingOrderNo })
            orderListOpen -> buildOrdersDialog()
            else -> buildMain()
        }
    }

    // ── 主界面：档位卡 ──

    private fun buildMain() {
        val bg = PointsMarketBackground.rect(width, height)
        val inset = PointsMarketBackground.borderInset(width, height)
        val cx = bg.x + bg.w / 2

        backButton = NineSliceButton(
            cx - BACK_W / 2, backButtonY(bg), BACK_W, BACK_H,
            Text.literal(""),
            { client?.setScreen(PointsMarketScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        // 「查看订单」：**内容区右上角**，有未完结订单时才显示。
        // ⚠ 位置与 RP 市场界面的「购买 RP」按钮同一规则（内容区顶 + ROW_TOP_OFFSET）：
        // 原来跟标题对齐（texPx(TITLE_TOP)），而标题本来就在背景图顶部那条装饰边框里
        // （边框厚 90 原图口径 ≈ 1080p 下 124px，标题只在 28 处）⇒ 按钮压在边框装饰条上（2026-09-18 用户报）。
        val ordersBtn = NineSliceButton(
            bg.x + bg.w - inset - ORDERS_W, bg.y + inset + ROW_TOP_OFFSET,
            ORDERS_W, ROW_H,
            // 有未完结订单时带上数量，没有就只写「我的订单」——
            // 按钮**常驻**（订单列表现在含历史，随时可查，不再是有单才冒出来）
            if (PointsShopCache.orders.isNotEmpty())
                Text.translatable("cobblemarket.points.recharge.view_orders", PointsShopCache.orders.size)
            else Text.translatable("cobblemarket.points.recharge.orders_title"),
            { orderListOpen = true; rebuild() }
        )
        ordersEntryButton = ordersBtn
        addDrawableChild(ordersBtn)

        // 档位卡：一行 3 张、从内容区左上铺开（每张卡 = RP 券图 + 按钮 + 下方点数）
        val tiers = PointsShopCache.tiers
        if (tiers.isEmpty()) return
        val cardW = tierCardW(bg)
        // 重建（resize / 快照刷新）后按当前行号重新对齐像素位置：tierRowStride 依赖 cardW，
        // 而 cardW 又依赖背景尺寸，只有在这里才算得出来。
        // 注意**直接对齐、不做缓动** —— 重建瞬间本来就不该有滚动动画
        scrollPx = (scrollRow * tierRowStride(cardW)).toFloat()
        scrollTargetPx = scrollPx
        tiers.forEachIndexed { index, tier ->
            val (x, y) = tierCardPos(bg, index, cardW)
            val btnW = tierBtnW(cardW)
            val button = NineSliceButton(
                x, y + tierIconH(cardW) + TIER_ICON_V_GAP, btnW, TIER_BTN_H,
                // 金额超宽也要截断：窄卡片上「1000.00 元」同样会画到按钮外
                Text.literal(fitText(moneyText(tier.priceCents), btnW - 8)).formatted(Formatting.GOLD),
                {
                    // 记下当前订单号：快照回来靠它认出新下的那一笔（见 onShopInfo）
                    ordersBeforeCreate = PointsShopCache.orders.mapTo(mutableSetOf()) { it.orderNo }
                    sendToServer(CreatePointOrderPayload(index))
                }
            )
            // 滚出可见区的行要**藏起来**：留着的按钮会画到分割线/返回按钮上，还点得到
            button.visible = isRowVisible(index / TIER_COLS, cardW)
            tierButtons += button
            addDrawableChild(button)
        }
        // 建好后立刻按当前滚动位置刷一次**位置 + 裁剪矩形**（上面循环里只设了 visible）。
        // ⚠ 必须补这一次：缓动只在滚动时跑（[stepTierScroll] 一停下就 return），
        //   界面刚打开、还没滚过时没人调 [applyTierScroll]，clipRect 就一直是 null = 不裁剪。
        applyTierScroll()
    }

    // ── 订单列表弹窗 ──

    /**
     * 订单列表**弹窗**（2026-09-18 用户报：原来点「查看订单」会把整个界面切走变成一整屏列表，
     * 应该像收款码那样在本界面之上弹一层）。面板背景统一在 [renderBackground] 里画。
     */
    private fun buildOrdersDialog() {
        val dx = ordersDialogX()
        val dy = ordersDialogY()
        val viewBtnX = dx + ORDERS_DIALOG_W - DIALOG_PAD - ORDER_VIEW_BTN_W

        // ⚠ **每笔订单都要建按钮**（不可见的用 visible 隐藏），**不能"跳过不可见的"**：
        //   跳过后 `orderButtons` 的下标就与 `PointsShopCache.orders` 的下标脱节，
        //   而 `applyOrderScroll` 是按「orderButtons 下标 − orderScroll」算行位置的 ——
        //   一滚动就全乱：按钮带着**错的订单**、还摆在**错的位置**（用户实测「滚动后点击就错位」）。
        //   不滚动时两边恰好重合，所以"第一条没问题"、越往下越错。
        PointsShopCache.orders.forEachIndexed { index, order ->
            val rel = index - orderScroll
            val rowY = dy + ORDERS_DIALOG_HEADER_H + rel * ORDER_ROW_H
            val button = NineSliceButton(
                viewBtnX, rowY + (ORDER_ROW_H - ORDER_VIEW_BTN_H) / 2,
                ORDER_VIEW_BTN_W, ORDER_VIEW_BTN_H,
                Text.translatable("cobblemarket.points.recharge.view_order"),
                { viewingOrderNo = order.orderNo; qrRequestedFor = null; rebuild() }
            )
            button.visible = rel in 0 until visibleOrderRows()
            orderButtons += button
            addDrawableChild(button)
        }

        // 关闭按钮：弹窗底部居中（与收款码弹窗的按钮同一尺寸）
        ordersCloseButton = NineSliceButton(
            dx + ORDERS_DIALOG_W / 2 - DIALOG_BTN_W / 2, dy + ordersDialogH() - 26,
            DIALOG_BTN_W, DIALOG_BTN_H,
            Text.translatable("cobblemarket.gui.close"),
            { orderListOpen = false; rebuild() }
        )
        ordersCloseButton?.let { addDrawableChild(it) }
    }

    // ── 收款码弹窗 ──

    private fun buildQrDialog(order: PointOrderInfo) {
        val status = statusOf(order)
        // 待支付要展示收款码：**按订单请求**（每档可能配了自己的码）；换单先清掉上一张图
        if (status == PointOrderStatus.PENDING_PAYMENT && qrRequestedFor != order.orderNo) {
            PayQrTexture.reset()
            sendToServer(RequestPayQrPayload(order.orderNo))
            qrRequestedFor = order.orderNo
        }

        val dx = orderDialogX()
        val dy = orderDialogY(order)
        val buttonY = dy + orderDialogH(order) - 26
        if (status == PointOrderStatus.PENDING_PAYMENT) {
            dialogButtons += NineSliceButton(dx + 14, buttonY, 88, 18,
                Text.translatable("cobblemarket.points.recharge.mark_paid"),
                { sendToServer(MarkPointOrderPaidPayload(order.orderNo)) })
            dialogButtons += NineSliceButton(dx + ORDER_DIALOG_W - 102, buttonY, 88, 18,
                Text.translatable("cobblemarket.points.recharge.cancel_order"),
                { sendToServer(CancelPointOrderPayload(order.orderNo)) })
        } else {
            dialogButtons += NineSliceButton(dx + ORDER_DIALOG_W / 2 - 44, buttonY, 88, 18,
                Text.translatable("cobblemarket.gui.close"),
                { viewingOrderNo = null; rebuild() })
        }
        // 「返回列表」：多笔订单时得能翻回去看别的。
        // **用返回图标而不是文字**（全模组弹窗里的返回一律是图标，2026-09-18 用户指出）
        dialogButtons += NineSliceButton(dx + ORDER_DIALOG_W - 56, dy + 8, 48, 16,
            Text.literal(""),
            { viewingOrderNo = null; orderListOpen = true; rebuild() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.points.recharge.back_to_list"))
        dialogButtons.forEach { addDrawableChild(it) }
    }

    private fun orderDialogX(): Int = width / 2 - ORDER_DIALOG_W / 2

    /**
     * 收款码弹窗的高度**按状态分两档**：
     * - 待支付要放收款码图 ⇒ [ORDER_DIALOG_H]；
     * - 待确认只有三行文字 + 一个「关闭」，再用那个高度会空掉一大片（2026-09-18 用户指出）。
     */
    private fun orderDialogH(order: PointOrderInfo): Int =
        if (statusOf(order) == PointOrderStatus.PENDING_PAYMENT) ORDER_DIALOG_H else CONFIRM_DIALOG_H

    private fun orderDialogY(order: PointOrderInfo): Int = height / 2 - orderDialogH(order) / 2

    /** 订单列表弹窗：宽度固定，高度按订单笔数自适应（未完结订单上限由服务端 `MAX_ACTIVE_ORDERS` 把关） */
    private fun ordersDialogH(): Int =
        ORDERS_DIALOG_HEADER_H + visibleOrderRows() * ORDER_ROW_H + ORDERS_DIALOG_FOOTER_H

    private fun ordersDialogX(): Int = width / 2 - ORDERS_DIALOG_W / 2
    private fun ordersDialogY(): Int = height / 2 - ordersDialogH() / 2

    /** 金额 + 服主自定义的货币单位（单位留空就只显示数字） */
    private fun moneyText(cents: Long): String {
        val label = PointsShopCache.currencyLabel
        return if (label.isEmpty()) PointShopService.formatCents(cents)
        else "${PointShopService.formatCents(cents)} $label"
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        PointsMarketBackground.draw(context, width, height)
        // 弹窗：遮罩 + **面板**都在这里画。⚠ 面板不能放 render 里 —— 控件由 super.render() 绘制，
        // 面板画在它之后会把自己的按钮整个盖住（面板是纯背景，按钮只是看不见、点还是点得到，最难查）。
        when {
            viewingOrderNo != null -> {
                drawScreenDimMask(context, width, height)
                // 高度按状态取（待确认那份矮得多），所以这里也要拿到订单本身
                PointsShopCache.orders.firstOrNull { it.orderNo == viewingOrderNo }?.let { order ->
                    drawNineSlice(
                        context, DIALOG_BACKGROUND_TEXTURE,
                        orderDialogX(), orderDialogY(order), ORDER_DIALOG_W, orderDialogH(order), 0,
                        DIALOG_BACKGROUND_TEX_H
                    )
                }
            }
            orderListOpen -> {
                drawScreenDimMask(context, width, height)
                drawNineSlice(
                    context, DIALOG_BACKGROUND_TEXTURE,
                    ordersDialogX(), ordersDialogY(), ORDERS_DIALOG_W, ordersDialogH(), 0, DIALOG_BACKGROUND_TEX_H
                )
            }
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val bg = PointsMarketBackground.rect(width, height)
        val centerX = bg.x + bg.w / 2

        // 弹窗打开时下层文字一律不画（遮罩在它们下面，照画会浮到弹窗上）
        if (viewingOrderNo != null) {
            PointsShopCache.orders.firstOrNull { it.orderNo == viewingOrderNo }?.let { renderQrDialog(context, it) }
            renderResultToast(context, bg)
            return
        }
        if (orderListOpen) {
            renderOrdersDialog(context)
            renderResultToast(context, bg)
            return
        }

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.recharge.title", CurrencyService.pointsTitleIcon())
                .formatted(Formatting.GOLD),
            centerX, bg.y + texPx(TITLE_TOP), 0xFFFFFF
        )

        // 结果提示：放在返回按钮上方（弹窗打开时那边是空的，不会重叠）
        renderResultToast(context, bg)

        // 上下两条红色分割线：**夹出中间的档位卡区**（与 RP 市场界面「兑换行 / 红线下是商品区」
        // 同一种结构；跨度避开背景边框，2026-09-18 用户定）。
        // 档位卡的排版与滚动范围都取这两条线之间的区域 —— 卡片不会画到线外。
        val lineLeft = bg.x + texPx(DIVIDER_BORDER_X) - DIVIDER_EXTEND
        val lineRight = bg.x + bg.w - texPx(DIVIDER_BORDER_X) + DIVIDER_EXTEND
        val lineTop = dividerTopY(bg)
        val lineBottom = dividerBottomY(bg)
        context.fill(lineLeft, lineTop, lineRight, lineTop + 1, GuiColors.DIVIDER)
        context.fill(lineLeft, lineBottom, lineRight, lineBottom + 1, GuiColors.DIVIDER)
        // 档位卡多到一屏放不下时，右侧补一根滚动条（两条红线之间）
        renderScrollbar(context, bg)
        // 平滑滚动：每帧朝目标推进一点（没有 tick 回调，逐帧推进最贴合渲染节奏）
        stepTierScroll()

        // 档位卡的「RP 券」图与点数（按钮是控件、由 super.render 画）。
        // ⚠ 缩放走 matrices：drawTexture 的 width/height 是源图采样区域，不能直接喂目标尺寸。
        val tiers = PointsShopCache.tiers
        if (tiers.isEmpty()) {
            // 兜底：服务端把充值关掉后（快照不再发档位），已经开着的界面得说一声，
            // 否则就是一片空白（正常情况下入口已经消失、进不来这里）
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.recharge.disabled"),
                centerX, bg.y + bg.h / 2, 0xFF5555
            )
            return
        }
        val cardW = tierCardW(bg)
        // 券图显示框：用**内置图的比例**定死（`tierCardPos` 排版也按它）——
        // 服主换什么比例的图都不会把档位卡的布局顶歪
        val boxH = tierIconH(cardW)
        // ⚠ 滚动时**必须裁剪**到档位区：`isRowVisible` 判的是「和本区有重叠」，
        //   所以缓动期间**半露**的那两行会被整行画出来 —— 券图上下露出边界看着就是「残影」
        //   （2026-09-19 用户实测）。scissor 要夹住整个 forEach，但**别包住上面那个 return**
        //   （tiers 为空时提前返回，包进去就没机会 disableScissor 了）。
        // ⚠ 裁剪边界取**两条红分割线**本身，而不是 [tierAreaTop]/[tierAreaBottom]（那两个各内缩了
        //   TIERS_PADDING）—— 卡片装不下时最后一行会被裁，裁口必须**正好落在红线上**；
        //   取内缩后的区域会在红线前留 6px 的缝，看着就是「第二行悬在红线上面一截」
        //   （2026-09-19 用户实测截图）。
        context.enableScissor(0, dividerTopY(bg), width, dividerBottomY(bg))
        tiers.forEachIndexed { index, tier ->
            if (!isRowVisible(index / TIER_COLS, cardW)) return@forEachIndexed
            val (x, y) = tierCardPos(bg, index, cardW)
            // 有自定义券图就用它（在框内按**原图宽高比**等比缩放居中，不变形），
            // 没配 / 还没到货 / 读不出来 → 内置券图
            val custom = tier.iconFile.takeIf { it.isNotEmpty() }?.let { TierIconTextures.get(it) }
            val texW = custom?.width ?: TIER_ICON_TEX_W
            val texH = custom?.height ?: TIER_ICON_TEX_H
            val scale = min(cardW.toFloat() / texW, boxH.toFloat() / texH)
            val drawW = (texW * scale).roundToInt()
            val drawH = (texH * scale).roundToInt()
            context.matrices.push()
            context.matrices.translate(
                (x + (cardW - drawW) / 2).toDouble(),
                (y + (boxH - drawH) / 2).toDouble(),
                0.0
            )
            context.matrices.scale(scale, scale, 1f)
            context.drawTexture(custom?.id ?: TIER_ICON, 0, 0, 0f, 0f, texW, texH, texW, texH)
            context.matrices.pop()
            // 「N + RP 图标」并到按钮那一行：画在按钮右侧剩下的区域里，与按钮垂直居中。
            // ⚠ 小分辨率（GUI scale 调大）下这块区域可能只有二三十像素，文字必须**截断** ——
            //   否则居中后会往两边溢出，正好盖到左边的金额按钮上。
            //   截断只针对数字：末尾那个图标宽度是固定值，得先留出来再算数字的可用宽度。
            val btnW = tierBtnW(cardW)
            val pointsW = cardW - btnW
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal(
                    fitText(
                        "${tier.points}",
                        (pointsW - POINTS_SYMBOL_INLINE_SIZE - unitGap(textRenderer)).coerceAtLeast(0)
                    )
                ).formatted(Formatting.GOLD).append(" ").append(CurrencyService.pointsIcon()),
                x + btnW + pointsW / 2,
                y + boxH + TIER_ICON_V_GAP + (TIER_BTN_H - 8) / 2,
                0xFFFFFF
            )
        }
        context.disableScissor()
    }

    /**
     * 订单列表弹窗的内容（面板已在 [renderBackground] 画好）。
     *
     * 单行紧凑：档位名 / 实付金额 / 状态+倒计时，右端一个「查看」按钮。
     * 三列走**固定 x** 而不是空格拼接 —— 空格宽度随中英字形变化，拼出来的列一定歪。
     */
    private fun renderOrdersDialog(context: DrawContext) {
        val dx = ordersDialogX()
        val dy = ordersDialogY()
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.recharge.orders_title").formatted(Formatting.GOLD),
            dx + ORDERS_DIALOG_W / 2, dy + 10, 0xFFFFFF
        )
        PointsShopCache.orders.forEachIndexed { index, order ->
            val rel = index - orderScroll
            if (rel !in 0 until visibleOrderRows()) return@forEachIndexed
            val rowY = dy + ORDERS_DIALOG_HEADER_H + rel * ORDER_ROW_H
            val textY = rowY + (ORDER_ROW_H - 8) / 2
            context.drawTextWithShadow(
                textRenderer,
                Text.literal(fitText(order.tierName, COL_NAME_MAX_W)),
                dx + COL_NAME_X, textY, 0xFFFFFF
            )
            context.drawTextWithShadow(
                textRenderer,
                Text.literal(moneyText(order.payCents)).formatted(Formatting.GOLD),
                dx + COL_MONEY_X, textY, 0xFFFFFF
            )
            context.drawTextWithShadow(
                textRenderer,
                Text.literal(statusLine(order)),
                dx + COL_STATUS_X, textY, statusColor(order)
            )
            // 订单号也分段展示（`ABC-DEF`，与收款码弹窗同一种写法）
            context.drawTextWithShadow(
                textRenderer,
                Text.literal(PointOrderNumbers.format(order.orderNo)),
                dx + COL_ORDER_X, textY, 0xAAAAAA
            )
            // 下单时间（`MM-dd HH:mm`，与交易历史同一种格式）—— 订单含历史后，
            // 光有状态看不出「这是什么时候充的」
            context.drawTextWithShadow(
                textRenderer, Text.literal(orderTimeText(order.createdAt)), dx + COL_TIME_X, textY, 0xAAAAAA
            )
        }
    }

    /**
     * 行内状态列：待支付带上剩余时间（玩家要知道还赶不赶得上），待确认不超时、不带时间。
     *
     * ⚠ 倒计时归零后改说「超时」而不是继续显示 `0:00`：服务端这时进的是**宽限期**
     * （仍接受「我已支付」30 分钟，见 `PointShopService.PAYMENT_GRACE_MS`），
     * `0:00` 会让人以为这单已经废了 —— 2026-09-18 用户就是照 `0:00` 理解成「该消失了」的。
     */
    private fun statusLine(order: PointOrderInfo): String {
        // 作废的要说清是哪种（见 voidKey）—— 原来这里落到 else 给了个**空字符串**，
        // 玩家看到的就是「一行没有状态的订单」，自然以为取消没生效（2026-09-19 用户实测）
        voidKey(order, dialog = false)?.let { return Text.translatable(it).string }
        return when (statusOf(order)) {
            PointOrderStatus.PENDING_PAYMENT -> {
                val unpaid = Text.translatable("cobblemarket.points.recharge.status_unpaid").string
                if (isOverdue(order)) "$unpaid ${Text.translatable("cobblemarket.points.recharge.status_overdue").string}"
                else "$unpaid ${countdownText(order.expireAt)}"
            }
            PointOrderStatus.PENDING_CONFIRM ->
                Text.translatable("cobblemarket.points.recharge.status_confirm").string
            // 已发放以前也是空的 —— 玩家查历史时同样看不出这单成没成
            PointOrderStatus.GRANTED ->
                Text.translatable("cobblemarket.points.recharge.status_granted").string
            else -> ""
        }
    }

    /** 状态列的文字颜色：到账=绿、作废=灰（全项目「已失效」都是这个灰），其余白 */
    private fun statusColor(order: PointOrderInfo): Int = when (statusOf(order)) {
        PointOrderStatus.GRANTED -> 0x55FF55
        PointOrderStatus.VOID -> 0x888888
        else -> 0xFFFFFF
    }

    /**
     * 这单**作废了吗**：作废则返回该显示的词条键，没作废返回 null。
     * [dialog] = false 取**列表用的短词条**，true 取弹窗里的整句。
     *
     * ⚠ 列表与弹窗**共用这一张表**：同一件事两处说法不一致，玩家会当成两回事。
     * ⚠ 词条键写成**字面量**，别用前缀拼接 —— 满项目扫死词条时，拼出来的键会被判成没人用。
     * ⚠ 认不出原因（老订单没记 / 将来新增）只说「已作废」，**不猜**是哪一种。
     */
    private fun voidKey(order: PointOrderInfo, dialog: Boolean): String? {
        if (statusOf(order) != PointOrderStatus.VOID) return null
        return when (runCatching { PointOrderVoidReason.valueOf(order.voidReason) }.getOrNull()) {
            PointOrderVoidReason.PLAYER_CANCEL ->
                if (dialog) "cobblemarket.points.recharge.dialog_void_cancel"
                else "cobblemarket.points.recharge.status_void_cancel"

            PointOrderVoidReason.TIMEOUT ->
                if (dialog) "cobblemarket.points.recharge.dialog_void_timeout"
                else "cobblemarket.points.recharge.status_void_timeout"

            PointOrderVoidReason.OPERATOR ->
                if (dialog) "cobblemarket.points.recharge.dialog_void_operator"
                else "cobblemarket.points.recharge.status_void_operator"

            null ->
                if (dialog) "cobblemarket.points.recharge.dialog_void"
                else "cobblemarket.points.recharge.status_void"
        }
    }

    /**
     * 结果提示（下单被拒 / 取消成功 / 已支付已提交 …）：返回按钮上方一行，4 秒后消失。
     *
     * ⚠ **三个分支都要调**（主界面 / 收款码弹窗 / 订单列表弹窗）—— 取消订单就发生在收款码弹窗里，
     * 只画在主界面的话那声「已取消」玩家根本看不见。
     */
    private fun renderResultToast(context: DrawContext, bg: PointsMarketBackground.Rect) {
        if (System.currentTimeMillis() >= resultToastUntil) return
        val text = resultText ?: return
        context.drawCenteredTextWithShadow(
            textRenderer, text, bg.x + bg.w / 2,
            bg.y + bg.h - texPx(BACK_BOTTOM) - BACK_H - 20,
            if (resultOk) 0x55FF55 else 0xFF5555
        )
    }

    /** 支付倒计时已归零（但还在宽限期内，服务端仍接受「我已支付」） */
    private fun isOverdue(order: PointOrderInfo): Boolean = order.expireAt <= System.currentTimeMillis()

    /** 下单时间文本（`MM-dd HH:mm`，与交易历史界面同一种格式，玩家一眼能看懂） */
    private fun orderTimeText(createdAt: Long): String =
        if (createdAt <= 0L) "" else ORDER_TIME_FORMAT.format(java.util.Date(createdAt))

    /** 超宽截断加省略号（档位名由服主自定义，长度不可控，不能让它压到金额列上） */
    private fun fitText(text: String, maxW: Int): String {
        if (textRenderer.getWidth(text) <= maxW) return text
        var end = text.length
        while (end > 0 && textRenderer.getWidth(text.substring(0, end) + "…") > maxW) end--
        return text.substring(0, end) + "…"
    }

    /** 收款码弹窗的内容（面板已在 [renderBackground] 画好，这里只画图与文字） */
    private fun renderQrDialog(context: DrawContext, order: PointOrderInfo) {
        val dx = orderDialogX()
        val dy = orderDialogY(order)
        val status = statusOf(order)
        val centerX = dx + ORDER_DIALOG_W / 2
        var y = dy + DIALOG_CONTENT_TOP

        if (status == PointOrderStatus.PENDING_PAYMENT) {
            if (PayQrTexture.available) {
                val availW = ORDER_DIALOG_W - 24
                val scale = min(availW.toFloat() / PayQrTexture.width, QR_MAX_H.toFloat() / PayQrTexture.height)
                val drawW = (PayQrTexture.width * scale).roundToInt()
                val drawH = (PayQrTexture.height * scale).roundToInt()
                context.matrices.push()
                context.matrices.translate((centerX - drawW / 2).toDouble(), y.toDouble(), 0.0)
                context.matrices.scale(scale, scale, 1f)
                context.drawTexture(
                    PayQrTexture.textureId(), 0, 0, 0f, 0f,
                    PayQrTexture.width, PayQrTexture.height, PayQrTexture.width, PayQrTexture.height
                )
                context.matrices.pop()
                y += drawH + 8
            } else if (PayQrTexture.isUnavailable) {
                context.drawCenteredTextWithShadow(
                    textRenderer, Text.translatable("cobblemarket.points.recharge.qr_unavailable"), centerX, y, 0xFF5555
                )
                y += 14
            } else {
                context.drawCenteredTextWithShadow(
                    textRenderer, Text.translatable("cobblemarket.points.recharge.loading_qr"), centerX, y, 0xAAAAAA
                )
                y += 14
            }
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(
                    "cobblemarket.points.recharge.order_no",
                    PointOrderNumbers.format(order.orderNo)
                ),
                centerX, y, 0xFFFFFF
            )
            y += 12
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.recharge.pay_amount", moneyText(order.payCents))
                    .formatted(Formatting.GOLD),
                centerX, y, 0xFFFFFF
            )
            y += 12
            // 归零后说的是**宽限期**里的事实：还能提交，不是「已经作废了」
            val overdue = isOverdue(order)
            context.drawCenteredTextWithShadow(
                textRenderer,
                if (overdue) Text.translatable("cobblemarket.points.recharge.remaining_overdue")
                else Text.translatable("cobblemarket.points.recharge.remaining", countdownText(order.expireAt)),
                centerX, y, if (overdue) 0xFFAA00 else 0xAAAAAA
            )
        } else {
            // 待确认没有收款码，同样从 [DIALOG_CONTENT_TOP] 起画
            y = dy + DIALOG_CONTENT_TOP
            // ⚠ 这一段原来只写了「待确认」一种情况，而 `else` 把**作废**也兜进来了 ——
            //   玩家点了「取消订单」之后弹窗反倒显示「等待管理员确认」，像是订单还活着
            //   （2026-09-19 用户实测）。作废的必须说清是哪种，红色（同本弹窗的「收款码不可用」）。
            val voidKey = voidKey(order, dialog = true)
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(voidKey ?: "cobblemarket.points.recharge.waiting_confirm")
                    .formatted(if (voidKey != null) Formatting.RED else Formatting.GOLD),
                centerX, y, 0xFFFFFF
            )
            y += 16
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(
                    "cobblemarket.points.recharge.order_no",
                    PointOrderNumbers.format(order.orderNo)
                ),
                centerX, y, 0xFFFFFF
            )
            y += 12
            // ⚠ 这里**不是**「请支付」：玩家已经点过「我已支付」了（哪怕只是声明），
            // 再让人「请支付」自相矛盾。显示的是这一单的金额 —— 也就是服主对着收款账单
            // 核对的那笔数，2026-09-18 用户看到「已提交…请支付 5.00」时问过。
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.recharge.order_amount", moneyText(order.payCents))
                    .formatted(Formatting.GOLD),
                centerX, y, 0xFFFFFF
            )
        }
    }

    /** 剩余时间 mm:ss；已过期为 0:00 */
    private fun countdownText(expireAt: Long): String {
        val remain = ((expireAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(remain / 60, remain % 60)
    }

    private fun statusOf(order: PointOrderInfo): PointOrderStatus? =
        runCatching { PointOrderStatus.valueOf(order.status) }.getOrNull()

    private fun texPx(value: Int): Int = PointsMarketBackground.texPx(value, width, height)

    /** 返回按钮顶边（底边距背景底 [BACK_BOTTOM]） */
    private fun backButtonY(bg: PointsMarketBackground.Rect): Int =
        bg.y + bg.h - texPx(BACK_BOTTOM) - BACK_H

    /** 上分割线：内容区顶那一行（「查看订单」）下方 [DIVIDER_GAP] */
    private fun dividerTopY(bg: PointsMarketBackground.Rect): Int =
        bg.y + PointsMarketBackground.borderInset(width, height) + ROW_H + DIVIDER_GAP

    /** 下分割线：返回按钮上方 [DIVIDER_GAP]（与上分割线对称，2026-09-18 用户定） */
    private fun dividerBottomY(bg: PointsMarketBackground.Rect): Int = backButtonY(bg) - DIVIDER_GAP

    /**
     * 档位卡区的上边界 = 上分割线下方 [TIERS_PADDING]（= 首行卡片的顶边）。
     * 与 RP 市场界面「红线下才是内容区」同构。
     */
    private fun tierAreaTop(bg: PointsMarketBackground.Rect): Int = dividerTopY(bg) + TIERS_PADDING

    /** 档位卡区的下边界 = 下分割线上方 [TIERS_PADDING]（滚动与绘制都不越过这两条线） */
    private fun tierAreaBottom(bg: PointsMarketBackground.Rect): Int =
        dividerBottomY(bg) - TIERS_PADDING

    /**
     * 档位卡宽度：**宽与高同时约束，取小的那个**（照 RP 市场界面商品网格的算法）。
     *
     * ⚠ 只按宽度算（`可用宽 ÷ 3`）是不够的：卡片高度是由宽度推出来的（图标按内置图的
     * 256:160 比例），所以档位排到**第二行**时总高是硬撑出来的，撑不下就顶到返回按钮上
     * —— 2026-09-18 用户实测「两排时第二排不够放」。加上高度约束后档位越多卡片自动越小，
     * 两排三排都落在内容区里。
     */
    private fun tierCardW(bg: PointsMarketBackground.Rect): Int {
        val inset = PointsMarketBackground.borderInset(width, height)
        val byW = (bg.w - 2 * inset - (TIER_COLS - 1) * TIER_CARD_GAP) / TIER_COLS
        return byW.coerceAtLeast(MIN_CARD_W)
    }

    /** 图标高度：按 256×160 的原图比例算，换图不变形 */
    private fun tierIconH(cardW: Int): Int = cardW * TIER_ICON_TEX_H / TIER_ICON_TEX_W

    /** 卡片高度：图标 + 间隙 + 按钮（点数并到按钮那一行里了，所以只有这些） */
    private fun tierCardH(cardW: Int): Int = tierIconH(cardW) + TIER_ICON_V_GAP + TIER_BTN_H

    /** 金额按钮宽度：卡片宽的 [TIER_BTN_W_RATIO]，右边留给「N RP」 */
    private fun tierBtnW(cardW: Int): Int = (cardW * TIER_BTN_W_RATIO).toInt()

    /** 档位卡位置；行号已经减掉 [scrollRow]，所以被滚出去的行会落在区域外（调用方据此隐藏它） */
    /** 一行占的像素高（卡片 + 行间距）：滚动换算与缓动都用它 */
    private fun tierRowStride(cardW: Int): Int = tierCardH(cardW) + TIER_CARD_GAP

    /** 档位卡位置：行号减掉**像素**滚动量（[scrollPx]），所以滚动是连续滑动而不是逐行跳 */
    private fun tierCardPos(bg: PointsMarketBackground.Rect, index: Int, cardW: Int): Pair<Int, Int> {
        val inset = PointsMarketBackground.borderInset(width, height)
        val col = index % TIER_COLS
        val row = index / TIER_COLS
        return (bg.x + inset + col * tierColStride(bg, cardW)) to
            (tierAreaTop(bg) + (row * tierRowStride(cardW) - scrollPx).toInt())
    }

    /**
     * 相邻两列的横向步长：**把 [TIER_COLS] 张卡在内容区里均匀铺开**。
     *
     * ⚠ 卡片宽度取的是「宽 / 高两个约束里小的那个」（见 [tierCardW]）—— 小分辨率下**高度**会是
     *   那个约束，卡片变窄，而按 `cardW + 卡间距` 排的话第 4 张的右边缘离内容区右边缘还差一大截，
     *   看上去就是「所有卡片连带按钮、点数全缩在左边」（2026-09-19 用户实测）。
     *   改成按内容区宽度均分：首列贴左、末列贴右，卡片小时自动拉开间距。
     *   ⚠ 宽度是约束时两者**恰好相等**（`contentW − cardW = 3×(cardW + GAP)`），所以正常分辨率下
     *   位置与原来一模一样。
     */
    private fun tierColStride(bg: PointsMarketBackground.Rect, cardW: Int): Int {
        val inset = PointsMarketBackground.borderInset(width, height)
        val contentW = bg.w - 2 * inset
        val spread = (contentW - cardW) / (TIER_COLS - 1)
        return maxOf(cardW + TIER_CARD_GAP, spread)
    }

    /**
     * 平滑滚动：每帧朝 [scrollTargetPx] 逼近（走剩余距离的 [SCROLL_EASE]），差到 0.5px 内就贴上去。
     * 在 render 里调 —— 没有 tick 回调，逐帧推进最容易也对得上渲染节奏。
     */
    private fun stepTierScroll() {
        if (scrollPx == scrollTargetPx) return
        val diff = scrollTargetPx - scrollPx
        scrollPx = if (kotlin.math.abs(diff) < 0.5f) scrollTargetPx else scrollPx + diff * SCROLL_EASE
        applyTierScroll()
    }

    /** 档位总行数 */
    private fun tierRows(): Int = (PointsShopCache.tiers.size + TIER_COLS - 1) / TIER_COLS

    /** 最多能往下滚几行（0 = 不用滚） */
    /**
     * 档位区一次能**完整**显示几行（至少 1 行）。
     *
     * ⚠ 卡片大小**不再随行数变**（见 [tierCardW]）：装不下就滚，所以这里是「实际装得下几行」，
     *   而不是一个写死的上限 —— GUI scale 调成自动时可能只装得下 1 行，其余靠滚轮翻。
     */
    private fun fittedTierRows(cardW: Int): Int {
        val bg = PointsMarketBackground.rect(width, height)
        val areaH = tierAreaBottom(bg) - tierAreaTop(bg)
        return ((areaH + TIER_CARD_GAP) / tierRowStride(cardW)).coerceAtLeast(1)
    }

    /** 最多能往下滚几行（0 = 不用滚）。取当前卡片尺寸下装不下的那些行 */
    private fun maxScrollRow(): Int {
        val cardW = tierCardW(PointsMarketBackground.rect(width, height))
        return (tierRows() - fittedTierRows(cardW)).coerceAtLeast(0)
    }

    /**
     * 这一行现在可见吗（滚出可见区的行要藏起来：按钮不能留在屏幕上，画也不能画）。
     * 用**像素范围**判而不是行号区间 —— 滚动缓动期间行会滑过边界，按行号判会让边缘那行闪。
     */
    private fun isRowVisible(rowIndex: Int, cardW: Int): Boolean {
        val bg = PointsMarketBackground.rect(width, height)
        val top = tierAreaTop(bg) + (rowIndex * tierRowStride(cardW) - scrollPx).toInt()
        return top + tierCardH(cardW) > tierAreaTop(bg) && top < tierAreaBottom(bg)
    }

    private companion object {
        const val TITLE_TOP = 28
        const val BACK_BOTTOM = 75
        const val BACK_W = 50
        const val BACK_H = 16
        const val ROW_H = 16

        /**
         * 档位卡一行几张。**2026-09-18 由 3 改成 4**：点数并到按钮那一行后卡片矮了一截，
         * 高度不再是瓶颈，而 3 张时卡片被高度约束卡住、右侧空出一大片
         * —— 改 4 张后改由宽度决定，正好铺满内容区。
         */
        const val TIER_COLS = 4
        const val TIER_CARD_GAP = 12
        const val TIER_BTN_H = 16
        const val MIN_CARD_W = 48

        /** 订单弹窗最多同时显示几行；订单现在含历史（最多回 50 笔），超出的滚轮翻 */
        const val MAX_VISIBLE_ORDER_ROWS = 8

        /** 滚动缓动系数：每帧走剩余距离的比例（0.25 ≈ 三四帧到位，跟手感，不会拖泥带水） */
        const val SCROLL_EASE = 0.25f

        /** 券图与价格按钮之间的间隙（卡片内部） */
        const val TIER_ICON_V_GAP = 4

        /**
         * 金额按钮占卡片宽的比例（**一半**，剩下那半画「N RP」点数）——
         * 2026-09-18 用户：原来按钮跟券图一样宽、点数另占一行，又长又浪费一格高度；
         * 并排后金额与点数同一行、卡片还矮一截。比例给到 0.5 是为了让长数字（10000 RP）放得下，
         * 两边都放不下时各自截断（见渲染处）。
         */
        const val TIER_BTN_W_RATIO = 0.5f

        /** 「RP 券」图：**服主可用资源包替换**换掉这套样式 */
        val TIER_ICON = POINTS_ICON_TEXTURE
        const val TIER_ICON_TEX_W = 256
        const val TIER_ICON_TEX_H = 160

        /**
         * 贴内容区顶的控件（「查看订单」）再下移的量：**逻辑像素**。
         * ⚠ 必须配 `borderInset()` 用：标题在 28（原图口径）而边框厚 90，跟着标题走就压到
         * 背景图顶部那条装饰边框上了（2026-09-18 用户报）。与 RP 市场界面的「购买 RP」按钮同一规则。
         */
        const val ROW_TOP_OFFSET = 3

        /** 内容区右上角「查看订单」按钮宽度 */
        const val ORDERS_W = 68

        /** 红色分割线左右各避开的背景边框宽度（**原图口径**，与 RP 市场界面同一个值） */
        const val DIVIDER_BORDER_X = 78

        /** 在「避开边框」的基础上左右各再延长的量：**逻辑像素**（与 RP 市场界面同一个值） */
        const val DIVIDER_EXTEND = 2

        /** 分割线与相邻控件（内容区顶那一行）的空隙：**逻辑像素** */
        const val DIVIDER_GAP = 6

        /** 档位卡区与上下两条分割线的间距（照 RP 市场界面商品区与分割线的间距） */
        const val TIERS_PADDING = 6

        const val ORDER_DIALOG_W = 232

        /**
         * 收款码弹窗高度（待支付）：内容最高 = 起始 30 + 码图 [QR_MAX_H] 110 + 8 + 三行 12×3
         * + 与按钮的间距 10 + 按钮 18 + 底边距 8 ≈ 216。
         */
        const val ORDER_DIALOG_H = 216

        /** 待确认弹窗高度：只有三行文字 + 一个「关闭」，用 [ORDER_DIALOG_H] 会空掉一大片 */
        const val CONFIRM_DIALOG_H = 100

        /**
         * 弹窗内容起始 y（两种状态共用）：**让开右上角的「返回列表」图标**（它占到 dy+24）。
         * ⚠ 码图也必须从这里起画 —— 正方形码缩到 110 宽时右边缘只到 dx+171（图标从 dx+176 起，
         * 侥幸不碰），但**横版码会撑满 208 宽顶到 dx+220**，正好把图标压住（2026-09-18 用户确认）。
         */
        const val DIALOG_CONTENT_TOP = 30

        const val QR_MAX_H = 110

        /** 弹窗左右留白 */
        const val DIALOG_PAD = 12

        /** 弹窗主体按钮尺寸（收款码弹窗的「我已支付」等与订单弹窗的「关闭」共用） */
        const val DIALOG_BTN_W = 88
        const val DIALOG_BTN_H = 18

        /**
         * 订单列表弹窗宽度：按**单行放满**的最坏情况定（中英都算过）——
         * 档位名 88 + 金额 56 + 状态 64 + 订单号 44 + 「查看」44，再加四列间距与左右留白。
         */
        const val ORDERS_DIALOG_W = 408

        /** 订单弹窗纵向分区：标题区 / 每行 / 底部按钮区（高度按订单笔数自适应） */
        const val ORDERS_DIALOG_HEADER_H = 24
        const val ORDER_ROW_H = 20
        const val ORDERS_DIALOG_FOOTER_H = 32

        /** 行内「查看」按钮：比弹窗底部按钮小一号（行高只有 20） */
        const val ORDER_VIEW_BTN_W = 44
        const val ORDER_VIEW_BTN_H = 16

        /**
         * 订单行四列相对弹窗左边缘的 x：**固定列**而非空格拼接 ——
         * 空格宽度随中英字形变化，拼出来的列一定歪。
         * 列序照方案里管理端列表的「档位 / 实付金额 / 订单号」来，订单号靠右便于对着念。
         *
         * ⚠ 最坏宽度（中英都算过）：档位名截断到 88、「5.37 CNY」约 45、
         * 状态约 64（英文 "Confirming" 10 字符 + 超时态的 "Unpaid late" 59）、订单号 "ABC-DEF" 约 40。
         * 状态列 174+64 = 238 < 订单号列 248；订单号 248+40 = 288 < 按钮左边缘 352−12−44 = 296。
         */
        const val COL_NAME_X = 12
        const val COL_NAME_MAX_W = 84
        const val COL_MONEY_X = 100
        const val COL_STATUS_X = 156

        /**
         * 订单号列：**玩家对着收款账单申诉时唯一能报的东西**。
         * 原来列表里没有它，两笔同样档位同样金额的订肉眼分不出新旧
         * （2026-09-18 用户实测：取消后被误当成「删掉的订单又回来了」）。
         */
        const val COL_ORDER_X = 220

        /** 下单时间（`MM-dd HH:mm`）：订单含历史后，光有状态看不出「这是什么时候充的」 */
        const val COL_TIME_X = 268

        /** 下单时间的展示格式（与交易历史界面一致） */
        val ORDER_TIME_FORMAT = java.text.SimpleDateFormat("MM-dd HH:mm")
    }
}
