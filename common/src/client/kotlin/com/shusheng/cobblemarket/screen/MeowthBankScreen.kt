package com.shusheng.cobblemarket.screen

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/**
 * 喵喵银行：金融系统入口界面（信用借贷/还款/信用钱包）。
 * 当前为占位骨架（标题 + 返回按钮），金融 UI 按 finance-plan 模板映射后续填充：
 * 顶部信用条（余额行模板）/ 我的信用（HistoryScreen 列表模板）/
 * 应急贷款（输入框 + 确认弹窗黄金模板）/ 还款柜台（待领取列表模板）。
 */
class MeowthBankScreen : Screen(Text.translatable("cobblemarket.meowth_bank.title")) {

    private var backButton: NineSliceButton? = null

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

        // 借款历史（左下）：返回按钮的全镜像（y 关于背景中心线对称 + x 水平镜像），80×16，所有玩家可见；
        // 实测再上移 11px、左移 10px、左移 6px
        addDrawableChild(NineSliceButton(
            width / 2 - 105, height / 2 + 58, 80, 16,
            Text.translatable("cobblemarket.meowth_bank.loan_history"),
            { showComingSoon() }
        ))

        // 全部借款历史（右下）：与借款历史关于背景竖直中心线左右镜像，80×16，仅 OP 可见
        if (client?.player?.hasPermissionLevel(2) == true) {
            addDrawableChild(NineSliceButton(
                width / 2 + 25, height / 2 + 58, 80, 16,
                Text.translatable("cobblemarket.meowth_bank.all_loan_history"),
                { showComingSoon() }
            ))
        }
    }

    /** 占位按钮点击：界面未实现，聊天栏提示装修中（金融批次实现后替换为跳转） */
    private fun showComingSoon() {
        client?.player?.sendMessage(
            Text.translatable("cobblemarket.meowth_bank.coming_soon").formatted(Formatting.YELLOW),
            false
        )
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
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.meowth_bank.coming_soon"),
            width / 2, height / 2, 0xAAAAAA
        )
    }

    override fun shouldPause() = false
}
