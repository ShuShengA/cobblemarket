package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.RedeemPointCodePayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * **点券市场**：两级结构里的中间层（入口界面 → 本界面 → 充值界面）。
 *
 * 本层放两样东西：
 * - **兑换点券**（常驻）：输入 / 粘贴兑换码 —— 国内外网站、发卡网、活动码、补偿码都走这条
 * - **购买点券**入口（B2.5b2 接入）：档位列表 → 收款码弹窗；仅当服主配了档位与收款码时显示
 *
 * 商品区（点券商城）方案未定，本层先**留位置不渲染** —— 内容整体放在背景中偏下，
 * 上方那片留白就是它的位置。
 *
 * 背景渲染（方案 2026-09-17 拍板）：`points_market_background.png` **等比缩放 + 居中 + 四周留 4%**。
 * ⚠ **不用九宫格**：四条边的中段都有红色装饰条，九宫格拉伸会把它们拉长变形（越宽的屏拉得越长）。
 */
class PointsMarketScreen : Screen(Text.translatable("cobblemarket.points.market.title")) {

    private var codeField: TextFieldWidget? = null
    /** 输入框内容：界面 resize 会重建控件，靠它把玩家已经敲进去的码恢复回来 */
    private var pendingCodeText = ""

    /** 背景等比缩放后的矩形（居中） */
    private data class BgRect(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun bgScale(): Float {
        val availW = width * (1f - MARGIN_RATIO * 2f)
        val availH = height * (1f - MARGIN_RATIO * 2f)
        return min(availW / TEX_W, availH / TEX_H)
    }

    private fun bgRect(): BgRect {
        val scale = bgScale()
        val w = (TEX_W * scale).roundToInt()
        val h = (TEX_H * scale).roundToInt()
        return BgRect((width - w) / 2, (height - h) / 2, w, h)
    }

    /** 背景图边框厚度（1280×720 原图约 78px）缩放后的实际值：贴边控件要避开它 */
    private fun borderInset(): Int = (TEX_BORDER * bgScale()).roundToInt()

    override fun init() {
        super.init()
        val bg = bgRect()
        val inset = borderInset()
        val cx = bg.x + bg.w / 2

        // 返回按钮：背景内右上角（照 DepositScreen 弹窗的返回按钮，内移一小段避开圆角边框）
        addDrawableChild(
            NineSliceButton(
                bg.x + bg.w - inset - 50, bg.y + inset + 2, 50, 16,
                Text.literal(""),
                { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) },
                iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
                iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
                tooltip = Text.translatable("cobblemarket.gui.back")
            )
        )

        val rowY = redeemRowY(bg)
        // 兑换码输入框（重建后恢复输入；上限按外部码带分隔符的 39 字符留余量）
        codeField = TextFieldWidget(textRenderer, cx - 100, rowY, 140, 16, Text.literal(""))
        codeField?.setMaxLength(MAX_CODE_INPUT)
        codeField?.setPlaceholder(Text.translatable("cobblemarket.points.market.code_hint"))
        codeField?.setChangedListener { pendingCodeText = it }
        codeField?.text = pendingCodeText
        addSelectableChild(codeField)
        addDrawableChild(codeField)

        // 兑换按钮：输入框右侧 4px（按钮间距规则）
        addDrawableChild(
            NineSliceButton(
                cx + 44, rowY, 56, 16,
                Text.translatable("cobblemarket.points.market.redeem_button"),
                { doRedeem() }
            )
        )
    }

    /** 兑换行（输入框与按钮同一行）相对背景的位置：整体压在背景中偏下，上方留给商品区 */
    private fun redeemRowY(bg: BgRect): Int = bg.y + bg.h / 2 + 16

    private fun doRedeem() {
        val code = codeField?.text?.trim().orEmpty()
        if (code.isEmpty()) {
            playFailSound()
            return
        }
        // 不在这里清空：失败时玩家还要改那串码；成功由 onMarketResult 清
        sendToServer(RedeemPointCodePayload(code))
    }

    /** 兑换结果（服务端 MarketResultPayload 到达；成功才清空输入框 —— 码已用掉，留着没用） */
    fun onMarketResult(payload: MarketResultPayload) {
        if (payload.success) {
            codeField?.text = ""
            pendingCodeText = ""
        }
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val bg = bgRect()
        // 等比缩放 + 居中：不变形优先于铺满（4:3 / 超宽屏留边，留边正好露出游戏画面）
        context.drawTexture(BACKGROUND, bg.x, bg.y, 0f, 0f, bg.w, bg.h, TEX_W, TEX_H)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val bg = bgRect()
        val centerX = bg.x + bg.w / 2
        context.drawCenteredTextWithShadow(
            textRenderer, title, centerX, bg.y + borderInset() + 8, 0xFFFFFF
        )
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.market.redeem_section"),
            centerX, redeemRowY(bg) - 16, 0xFFFFFF
        )
    }

    private companion object {
        val BACKGROUND = Identifier.of("cobblemarket", "textures/gui/points_market_background.png")
        const val TEX_W = 1280
        const val TEX_H = 720

        /** 四周留白：给游戏画面留一圈（方案拍板 4%） */
        const val MARGIN_RATIO = 0.04f

        /** 原图边框大致厚度：贴边控件（返回按钮、标题）避开它，别压到装饰条上 */
        const val TEX_BORDER = 78

        /** 输入框字符上限：外部码 32 位按 4 位分组 = 39 字符，留一点余量 */
        const val MAX_CODE_INPUT = 48
    }
}
