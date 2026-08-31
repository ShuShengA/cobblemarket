package com.shusheng.cobblemarket.screen

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting

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
        // 返回按钮：左上角，返回入口界面（子界面返回不播掉落动画）
        backButton = NineSliceButton(
            12, 13, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) }
        )
        addDrawableChild(backButton)
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 占位暗底；金融 UI 定型时换成面板背景（照 HistoryScreen 列表模板）
        context.fill(0, 0, width, height, 0xC0101010.toInt())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.meowth_bank.title").formatted(Formatting.GOLD, Formatting.BOLD),
            width / 2, 30, 0xFFFFFF
        )
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.meowth_bank.coming_soon"),
            width / 2, height / 2, 0xAAAAAA
        )
    }

    override fun shouldPause() = false
}
