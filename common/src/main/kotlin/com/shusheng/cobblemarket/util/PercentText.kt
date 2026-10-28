package com.shusheng.cobblemarket.util

/**
 * **折扣率（0~1）→ 百分比文本**（2026-10-28）。
 *
 * ⚠ 不能用 `rate * 100` 直接 `toString`：`0.07 * 100` 是 `7.000000000000001`，
 * 会显示成「7.000000000000001%」，塞进 4 字符的输入框也放不下。
 * 这里先按 **0.01% 精度四舍五入**，再去掉多余的小数：
 * `0.05 → "5"`、`0.125 → "12.5"`、`1.0 → "100"`。
 *
 * 收口原因：成功回执、购买窗提示、管理端输入框、卡配置输入框**四处**都要格式化 ——
 * 各写一遍必然会有一处漏掉四舍五入（项目里 `RemainingTime` 四份拷贝就是这样散掉的）。
 */
fun discountPercentText(rate: Double): String {
    val pct = Math.round(rate * 100.0 * 100.0) / 100.0
    return if (pct == pct.toLong().toDouble()) pct.toLong().toString() else pct.toString()
}

/** 同 [discountPercentText]，带百分号（`0.05 → "5%"`） */
fun discountPercentTextWithSymbol(rate: Double): String = "${discountPercentText(rate)}%"
