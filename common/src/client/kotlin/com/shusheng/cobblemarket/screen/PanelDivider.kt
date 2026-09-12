package com.shusheng.cobblemarket.screen

import net.minecraft.client.gui.DrawContext

/**
 * 面板列表的**顶部分割线**：固定 300px 宽，以 [centerX] 为中心。
 *
 * 用 `market_panel_*` 那套贴图的界面背景实际画出来是 320 宽（贴图 640 × scale 0.5），
 * 两端各留 10px 就是这条线 —— 长度基准取自封禁管理界面（用户 2026-09-13 定的规矩）。
 *
 * ⚠ **不要在各界面用 `panelWidth` 自己算跨度**：各界面 panelWidth 不一致（封禁 300 / 其他 296），
 * 那样线会随界面长短不齐（2026-09-13 踩过：用户看到「所有分割线变短了」）。
 * 要调长度只改这里。
 */
fun drawPanelDivider(context: DrawContext, centerX: Int, y: Int) {
    context.fill(centerX - 150, y, centerX + 150, y + 1, 0xFF555555.toInt())
}
