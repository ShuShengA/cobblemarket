package com.shusheng.cobblemarket.client

/**
 * 全模组共用的 GUI 颜色常量 —— 同一类元素在哪个界面都取这里，别再各写各的
 *（跨界面同类元素必须一致，散写必然改漏一处）。
 */
object GuiColors {

    /**
     * 界面底部的**红色分割线**（共 4 条：入口界面 1、管理员面板 1、RP 市场 2）。
     *
     * 色值取自RP 市场背景贴图上的装饰红 `#990425` —— 原本用的 `0xFFFF5555` 太亮、扎眼
     *（2026-09-18 用户提出改深红）。1px 粗、跨度左右避开背景边框的画法见各界面 render。
     */
    val DIVIDER: Int = 0xFF990425.toInt()
}
