package com.shusheng.cobblemarket.client

import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ItemStack

/**
 * 画物品图标 **+ 耐久条**。
 *
 * 项目各界面原先直接调 [DrawContext.drawItem]，它**不带耐久条** —— 玩家在市场里看不出
 * 物品还剩多少耐久，可能花大价钱买到快报废的工具。本函数补上这一笔。
 *
 * 为什么不直接用原版的 `DrawContext.drawItemInSlot`：那个会把**数量角标**一起画，而本项目
 * 各界面有自己的「×N」角标（位置也不同：原版右下、项目右上），直接用会变成双份数量。
 *
 * 显示规则完全照原版：**满耐久不画、受损才画**（`isItemBarVisible`）—— 所以变体选择、
 * 上架预览那些新建的样品栈（满耐久）自动不显示，界面不会被弄花。
 *
 * ⚠ 坐标口径与 `drawItem` 一致（**图标左上角**），在缩放矩阵里调用也会跟着缩放
 * （如悬停大图的放大预览），调用点无需特殊处理。
 */
fun drawItemWithBar(context: DrawContext, stack: ItemStack, x: Int, y: Int) {
    context.drawItem(stack, x, y)
    if (!stack.isItemBarVisible) return
    val step = stack.itemBarStep
    val color = stack.itemBarColor
    // 原版布局：黑底 + 彩色条，压在图标左下（相对图标左上角 +2,+13，13×2 像素）
    context.fill(x + 2, y + 13, x + 15, y + 15, 0xFF000000.toInt())
    context.fill(x + 2, y + 13, x + 2 + step, y + 14, color or 0xFF000000.toInt())
}
