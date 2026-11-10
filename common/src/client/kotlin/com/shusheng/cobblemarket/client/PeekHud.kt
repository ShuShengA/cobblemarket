package com.shusheng.cobblemarket.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * 监视（Peek）的客户端状态 + 「按 X 退出监视」HUD 提示（2026-11-10）。
 *
 * - [active] 完全由服务端的 `PeekActivePayload` 驱动，**客户端不本地伪造**；
 *   断线 / 换世界时由 [CobbleMarketClient] 的 tick 清掉。
 * - 位置与显隐条件照 CobbleKit 的追踪 / 热力图 HUD（用户指定参考）：
 *   屏幕底部居中、快捷栏上方 `h - 62`；F1 隐藏 HUD、有界面、未进世界时不画。
 * - 键位可改、可解绑：解绑时**整条提示不显示**（显示「按 未知」只会误导 ——
 *   与快速展示同一口径，见 `CobbleMarketClient.quickShowcaseKeyText`）。
 */
object PeekHud {

    /** 是否正在监视（服务端下发；false = 不显示提示、退出键不做事） */
    var active: Boolean = false

    /** 离屏幕底部的距离：与 CobbleKit 的「按 H 取消追踪」提示一致（`h - 62`） */
    private const val BOTTOM_OFFSET = 62

    fun render(context: DrawContext) {
        if (!active) return
        val client = MinecraftClient.getInstance()
        // 照 CobbleKit：F1 隐藏 HUD / 有界面（Esc、背包等）时不画
        if (client.player == null || client.options.hudHidden || client.currentScreen != null) return
        // 键位可改、可解绑：取当前绑定；解绑时整条不显示
        val keyName = CobbleMarketClient.peekExitKeyText() ?: return
        context.drawCenteredTextWithShadow(
            client.textRenderer,
            Text.translatable("cobblemarket.peek.exit_hint", keyName),
            context.scaledWindowWidth / 2,
            context.scaledWindowHeight - BOTTOM_OFFSET,
            0xFFFFFF
        )
    }
}
