package com.shusheng.cobblemarket.client

/**
 * 千分位格式化价格显示（如 99999999 → 99,999,999）。
 * 自实现而非 String.format("%,d")：后者依赖系统 Locale，部分地区的
 * 分组分隔符不是逗号，会导致显示不一致。
 */
fun formatPrice(price: Int): String =
    price.toString().reversed().chunked(3).joinToString(",").reversed()

/** 千分位格式化 Long 价格显示（待领余额等）。 */
fun formatPriceLong(price: Long): String =
    price.toString().reversed().chunked(3).joinToString(",").reversed()

/**
 * 待领余额显示：不足 10 亿原样千分位，达到 10 亿用 B 单位（截断 1 位小数）。
 * 与服务端余额格式同规则；不用 k/M。
 */
fun formatBalanceLong(price: Long): String {
    if (price < 1_000_000_000L) return price.toString().reversed().chunked(3).joinToString(",").reversed()
    val tenths = price / 100_000_000L // price×10÷10亿，截断到 1 位小数
    return "${tenths / 10}.${tenths % 10}B"
}

/**
 * 小格子用的价格缩写：4 位以内原样显示，更大用 k/M/B（截断到 1 位小数）。
 * 完整价格见 tooltip（formatPrice）。
 */
fun formatPriceShort(price: Int): String = when {
    price < 10_000 -> price.toString()
    price < 1_000_000 -> "${oneDecimal(price / 1000.0)}k"
    price < 1_000_000_000 -> "${oneDecimal(price / 1_000_000.0)}M"
    else -> "${oneDecimal(price / 1_000_000_000.0)}B"
}

/** 截断到 1 位小数（自实现，避开 String.format 的 Locale 小数点问题）。 */
private fun oneDecimal(v: Double): String {
    val tenths = (v * 10).toInt()
    // 值 ≥ 10 直接取整：网格/列表格子很窄，`200.0k ₽` 会顶出边框，`200k ₽` 才放得下（5.0M 仍保留小数）
    return if (tenths >= 100) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

/**
 * Long 版价格缩写（管理面板大额告警行用）：4 位以内原样显示，更大用 k/M/B（截断 1 位小数）。
 * 完整数字见悬停 tooltip（formatPriceLong）。
 *
 * ⚠ **负数要先取符号再缩写**：原来直接把 `price` 丢进 `when`，而 `price < 10_000` 会把**所有负数**
 * 都兜住 → 原样铺开。准备金池为负（服主负债告警）正是负数的典型场景：负债越大显示越长，
 * 且与下一行坏账的正数缩写风格对不上（2026-09-21 用户报）。
 * `Long.MIN_VALUE` 取绝对值仍是它自己，单独特判（现实中取不到，防呆）。
 */
fun formatPriceShortLong(price: Long): String {
    val sign = if (price < 0) "-" else ""
    val v = if (price == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(price)
    return sign + when {
        v < 10_000 -> v.toString()
        v < 1_000_000 -> "${oneDecimal(v / 1000.0)}k"
        v < 1_000_000_000 -> "${oneDecimal(v / 1_000_000.0)}M"
        else -> "${oneDecimal(v / 1_000_000_000.0)}B"
    }
}
