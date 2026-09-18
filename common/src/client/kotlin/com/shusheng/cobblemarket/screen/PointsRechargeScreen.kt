package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.PayQrTexture
import com.shusheng.cobblemarket.client.PointsShopCache
import com.shusheng.cobblemarket.currency.PointOrderStatus
import com.shusheng.cobblemarket.currency.PointShopService
import com.shusheng.cobblemarket.network.CancelPointOrderPayload
import com.shusheng.cobblemarket.network.CreatePointOrderPayload
import com.shusheng.cobblemarket.network.MarkPointOrderPaidPayload
import com.shusheng.cobblemarket.network.PointOrderInfo
import com.shusheng.cobblemarket.network.RequestPayQrPayload
import com.shusheng.cobblemarket.network.RequestPointsShopPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * **购买 RP**（充值界面）：档位卡 →（右上角「查看订单」）→ 订单列表 → 收款码弹窗。
 *
 * 流程（方案「RP 充值」）：选档位下单 → 拿订单号 + 收款码 + **实付金额** → 玩家扫码付款 →
 * 点「我已支付」→ 服务端转「待确认」→ 服主对着收款账单点确认才发放。
 *
 * ⚠ **模组不知道玩家有没有真的付款**，「我已支付」只是声明；订单号与金额才是服主核对账单的键。
 *
 * ⚠ 一个玩家**可以同时有多笔**未完结订单（2026-09-18 用户拍板：想改充别的档位不该被卡死），
 * 所以**不再进入界面就强弹收款码**，而是给一个「查看订单」入口自己翻。
 */
class PointsRechargeScreen : Screen(Text.translatable("cobblemarket.points.recharge.title")) {

    private val tierButtons = mutableListOf<NineSliceButton>()
    private val orderButtons = mutableListOf<NineSliceButton>()
    private val dialogButtons = mutableListOf<NineSliceButton>()
    private var backButton: NineSliceButton? = null
    private var ordersEntryButton: NineSliceButton? = null
    private var ordersCloseButton: NineSliceButton? = null

    /** 订单列表展开中 */
    private var orderListOpen = false
    /** 正在看收款码的那笔订单号（null = 没开收款码弹窗） */
    private var viewingOrderNo: String? = null
    /** 已为哪一笔订单请求过收款码（换单要重拉：每档可能配了自己的码） */
    private var qrRequestedFor: String? = null
    /** 本次进界面是否已请求过快照（init 会被 resize 反复调用，别重复发） */
    private var requested = false

    override fun init() {
        super.init()
        if (!requested) {
            sendToServer(RequestPointsShopPayload(true))
            requested = true
        }
        rebuild()
    }

    /** 服务端下发快照后重走一遍（档位、订单列表、货币单位都可能变了） */
    fun onShopInfo() {
        rebuild()
    }

    /** 收款码图片到了：render 每帧读 PayQrTexture，这里无需额外动作 */
    fun onQrImage() {
    }

    private fun rebuild() {
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
            orderListOpen -> buildOrderList()
            else -> buildMain()
        }
    }

    // ── 主界面：档位卡 ──

    private fun buildMain() {
        val bg = PointsMarketBackground.rect(width, height)
        val inset = PointsMarketBackground.borderInset(width, height)
        val cx = bg.x + bg.w / 2

        backButton = NineSliceButton(
            cx - BACK_W / 2, bg.y + bg.h - texPx(BACK_BOTTOM) - BACK_H, BACK_W, BACK_H,
            Text.literal(""),
            { client?.setScreen(PointsMarketScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        // 「查看订单」：**右上角**（与左边的标题同一水平线），有未完结订单时才显示
        val ordersBtn = NineSliceButton(
            bg.x + bg.w - inset - ORDERS_W, bg.y + texPx(TITLE_TOP) - 4,
            ORDERS_W, ROW_H,
            Text.translatable("cobblemarket.points.recharge.view_orders", PointsShopCache.orders.size),
            { orderListOpen = true; rebuild() }
        )
        ordersBtn.visible = PointsShopCache.orders.isNotEmpty()
        ordersEntryButton = ordersBtn
        addDrawableChild(ordersBtn)

        // 档位卡：一行 3 张、从内容区左上铺开（每张卡 = RP 券图 + 按钮 + 下方点数）
        val tiers = PointsShopCache.tiers
        if (tiers.isEmpty()) return
        val cardW = tierCardW(bg)
        tiers.forEachIndexed { index, tier ->
            val (x, y) = tierCardPos(bg, index, cardW)
            val button = NineSliceButton(
                x, y + tierIconH(cardW) + 4, cardW, TIER_BTN_H,
                Text.literal(moneyText(tier.priceCents)).formatted(Formatting.GOLD),
                { sendToServer(CreatePointOrderPayload(index)) }
            )
            tierButtons += button
            addDrawableChild(button)
        }
    }

    // ── 订单列表 ──

    private fun buildOrderList() {
        val bg = PointsMarketBackground.rect(width, height)
        val inset = PointsMarketBackground.borderInset(width, height)
        val listW = ORDER_ROW_W
        val startX = bg.x + bg.w / 2 - listW / 2
        val startY = bg.y + inset + ROW_H + 10

        ordersCloseButton = NineSliceButton(
            bg.x + bg.w - inset - ORDERS_W, bg.y + texPx(TITLE_TOP) - 4,
            ORDERS_W, ROW_H,
            Text.translatable("cobblemarket.gui.close"),
            { orderListOpen = false; rebuild() }
        )
        addDrawableChild(ordersCloseButton)

        PointsShopCache.orders.forEachIndexed { index, order ->
            val y = startY + index * (ROW_H + 4)
            val button = NineSliceButton(
                startX + listW - 52, y, 52, ROW_H,
                Text.translatable("cobblemarket.points.recharge.view_order"),
                { viewingOrderNo = order.orderNo; qrRequestedFor = null; rebuild() }
            )
            orderButtons += button
            addDrawableChild(button)
        }
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
        val dy = orderDialogY()
        val buttonY = dy + ORDER_DIALOG_H - 26
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
        // 「返回列表」：多笔订单时得能翻回去看别的
        dialogButtons += NineSliceButton(dx + ORDER_DIALOG_W - 56, dy + 8, 48, 16,
            Text.translatable("cobblemarket.points.recharge.back_to_list"),
            { viewingOrderNo = null; orderListOpen = true; rebuild() })
        dialogButtons.forEach { addDrawableChild(it) }
    }

    private fun orderDialogX(): Int = width / 2 - ORDER_DIALOG_W / 2
    private fun orderDialogY(): Int = height / 2 - ORDER_DIALOG_H / 2

    /** 金额 + 服主自定义的货币单位（单位留空就只显示数字） */
    private fun moneyText(cents: Long): String {
        val label = PointsShopCache.currencyLabel
        return if (label.isEmpty()) PointShopService.formatCents(cents)
        else "${PointShopService.formatCents(cents)} $label"
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        PointsMarketBackground.draw(context, width, height)
        if (viewingOrderNo != null) drawScreenDimMask(context, width, height)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val bg = PointsMarketBackground.rect(width, height)
        val centerX = bg.x + bg.w / 2

        // 收款码弹窗打开时下层文字一律不画（遮罩在它们下面，照画会浮到弹窗上）
        if (viewingOrderNo != null) {
            PointsShopCache.orders.firstOrNull { it.orderNo == viewingOrderNo }?.let { renderQrDialog(context, it) }
            return
        }

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.recharge.title").formatted(Formatting.GOLD),
            centerX, bg.y + texPx(TITLE_TOP), 0xFFFFFF
        )

        if (orderListOpen) {
            renderOrderList(context, bg)
            return
        }

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
        val iconH = tierIconH(cardW)
        val iconScale = cardW.toFloat() / TIER_ICON_TEX_W
        tiers.forEachIndexed { index, tier ->
            val (x, y) = tierCardPos(bg, index, cardW)
            context.matrices.push()
            context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
            context.matrices.scale(iconScale, iconScale, 1f)
            context.drawTexture(
                TIER_ICON, 0, 0, 0f, 0f,
                TIER_ICON_TEX_W, TIER_ICON_TEX_H, TIER_ICON_TEX_W, TIER_ICON_TEX_H
            )
            context.matrices.pop()
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal("${tier.points} RP").formatted(Formatting.GOLD),
                x + cardW / 2, y + iconH + 4 + TIER_BTN_H + 2, 0xFFFFFF
            )
        }
    }

    private fun renderOrderList(context: DrawContext, bg: PointsMarketBackground.Rect) {
        val inset = PointsMarketBackground.borderInset(width, height)
        val listW = ORDER_ROW_W
        val startX = bg.x + bg.w / 2 - listW / 2
        val startY = bg.y + inset + ROW_H + 10
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.recharge.orders_title").formatted(Formatting.GOLD),
            bg.x + bg.w / 2, bg.y + texPx(TITLE_TOP), 0xFFFFFF
        )
        PointsShopCache.orders.forEachIndexed { index, order ->
            val y = startY + index * (ROW_H + 4)
            val statusKey = when (statusOf(order)) {
                PointOrderStatus.PENDING_PAYMENT -> "cobblemarket.points.recharge.status_unpaid"
                PointOrderStatus.PENDING_CONFIRM -> "cobblemarket.points.recharge.status_confirm"
                else -> null
            }
            val statusText = statusKey?.let { Text.translatable(it).string } ?: ""
            val remain = countdownText(order.expireAt)
            context.drawTextWithShadow(
                textRenderer,
                Text.literal("${order.tierName}   ${moneyText(order.payCents)}   $statusText   $remain"),
                startX, y + 6, 0xFFFFFF
            )
        }
    }

    private fun renderQrDialog(context: DrawContext, order: PointOrderInfo) {
        val dx = orderDialogX()
        val dy = orderDialogY()
        drawNineSlice(
            context, DIALOG_BACKGROUND_TEXTURE, dx, dy, ORDER_DIALOG_W, ORDER_DIALOG_H, 0,
            DIALOG_BACKGROUND_TEX_H
        )
        val status = statusOf(order)
        val centerX = dx + ORDER_DIALOG_W / 2
        var y = dy + 12

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
                    com.shusheng.cobblemarket.currency.PointOrderNumbers.format(order.orderNo)
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
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.recharge.remaining", countdownText(order.expireAt)),
                centerX, y, 0xAAAAAA
            )
        } else {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.recharge.waiting_confirm").formatted(Formatting.GOLD),
                centerX, y, 0xFFFFFF
            )
            y += 16
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(
                    "cobblemarket.points.recharge.order_no",
                    com.shusheng.cobblemarket.currency.PointOrderNumbers.format(order.orderNo)
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

    /** 档位卡宽度：可用宽度平分给 [TIER_COLS] 列 */
    private fun tierCardW(bg: PointsMarketBackground.Rect): Int {
        val avail = bg.w - 2 * PointsMarketBackground.borderInset(width, height)
        return ((avail - (TIER_COLS - 1) * TIER_CARD_GAP) / TIER_COLS).coerceAtLeast(MIN_CARD_W)
    }

    /** 图标高度：按 256×160 的原图比例算，换图不变形 */
    private fun tierIconH(cardW: Int): Int = cardW * TIER_ICON_TEX_H / TIER_ICON_TEX_W

    private fun tierCardH(cardW: Int): Int = tierIconH(cardW) + 4 + TIER_BTN_H

    private fun tierCardPos(bg: PointsMarketBackground.Rect, index: Int, cardW: Int): Pair<Int, Int> {
        val inset = PointsMarketBackground.borderInset(width, height)
        val col = index % TIER_COLS
        val row = index / TIER_COLS
        return (bg.x + inset + col * (cardW + TIER_CARD_GAP)) to
            (bg.y + inset + row * (tierCardH(cardW) + TIER_CARD_GAP))
    }

    private companion object {
        const val TITLE_TOP = 28
        const val BACK_BOTTOM = 75
        const val BACK_W = 50
        const val BACK_H = 16
        const val ROW_H = 16

        /** 档位卡：**一行固定 3 张**（用户 2026-09-18 定），宽度按可用空间平分 */
        const val TIER_COLS = 3
        const val TIER_CARD_GAP = 12
        const val TIER_BTN_H = 16
        const val MIN_CARD_W = 48

        /** 「RP 券」图：**服主可用资源包替换**换掉这套样式 */
        val TIER_ICON = Identifier.of("cobblemarket", "textures/gui/points_icon.png")
        const val TIER_ICON_TEX_W = 256
        const val TIER_ICON_TEX_H = 160

        /** 右上角「查看订单」按钮宽度 */
        const val ORDERS_W = 68
        /** 订单列表每行宽度 */
        const val ORDER_ROW_W = 240

        const val ORDER_DIALOG_W = 232
        const val ORDER_DIALOG_H = 208
        const val QR_MAX_H = 110
    }
}
