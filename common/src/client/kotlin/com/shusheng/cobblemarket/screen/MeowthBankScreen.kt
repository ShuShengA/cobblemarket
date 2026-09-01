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
 * 标题下「可用额度/当前欠款」信息行 → 应急贷款按钮（LoanScreen）；
 * 底部左「借款历史」/ 右「全部借款历史」（仅 OP）→ LoanHistoryScreen。
 * 进入界面时拉取额度信息（RequestCreditInfoPayload）。
 */
class MeowthBankScreen : Screen(Text.translatable("cobblemarket.meowth_bank.title")) {

    private var backButton: NineSliceButton? = null
    private var limit = 0L
    private var debt = 0L
    private var infoLoaded = false

    override fun init() {
        super.init()
        // 返回按钮：背景右上区域（照全模组惯例——列表界面均为面板右上角 50×16；
        // 背景垂直居中顶边 height/2-106，位置按实测左移 9px、上移 5px）
        backButton = NineSliceButton(
            width / 2 + 39, height / 2 - 85, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) }
        )
        addDrawableChild(backButton)

        // 应急贷款入口（背景中部：额度/欠款两行 + 按钮组成信息组，居中放在标题与底部按钮之间的空档；100×16）
        addDrawableChild(NineSliceButton(
            width / 2 - 50, height / 2 + 2, 100, 16,
            Text.translatable("cobblemarket.loan.title"),
            { client?.setScreen(LoanScreen()) }
        ))

        // 借款历史（左下）：所有玩家可见 → 我的借贷流水
        addDrawableChild(NineSliceButton(
            width / 2 - 105, height / 2 + 58, 80, 16,
            Text.translatable("cobblemarket.meowth_bank.loan_history"),
            { client?.setScreen(LoanHistoryScreen(showAll = false)) }
        ))

        // 全部借款历史（右下）：仅 OP → 全服借贷流水审计
        if (client?.player?.hasPermissionLevel(2) == true) {
            addDrawableChild(NineSliceButton(
                width / 2 + 25, height / 2 + 58, 80, 16,
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
        // 背景垂直居中（与入口界面视觉位置一致，入口贴图中心 ≈ 屏幕中心偏上十几 px）；贴图 256×213
        val bg = Identifier.of("cobblemarket", "textures/gui/meowth_bank_background.png")
        context.drawTexture(bg, width / 2 - 128, height / 2 - 106, 0f, 0f, 256, 213, 256, 213)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        // 标题：背景内顶部边框（27px）下方 4px 处
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.meowth_bank.title").formatted(Formatting.GOLD, Formatting.BOLD),
            width / 2, height / 2 - 75, 0xFFFFFF
        )
        // 背景中部信息组：「可用额度 / 当前欠款」两行 + 应急贷款按钮，
        // 居中放在标题（底 ≈ height/2−71）与底部按钮（顶 height/2+50）之间的空档（价格+货币名照全模组规矩用蓝色）
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan.limit_line", formatPriceLong(limit), inlineCurrencyUnit()),
            width / 2, height / 2 - 25, 0x55FFFF
        )
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.loan.debt_line", formatPriceLong(debt), inlineCurrencyUnit()),
            width / 2, height / 2 - 15, 0x55FFFF
        )
    }

    override fun shouldPause() = false
}
