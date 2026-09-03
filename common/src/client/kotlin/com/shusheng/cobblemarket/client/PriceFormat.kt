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
    return "${tenths / 10}.${tenths % 10}"
}

/**
 * Long 版价格缩写（管理面板大额告警行用）：4 位以内原样显示，更大用 k/M/B（截断 1 位小数）。
 * 完整数字见悬停 tooltip（formatPriceLong）。
 */
fun formatPriceShortLong(price: Long): String = when {
    price < 10_000 -> price.toString()
    price < 1_000_000 -> "${oneDecimal(price / 1000.0)}k"
    price < 1_000_000_000 -> "${oneDecimal(price / 1_000_000.0)}M"
    else -> "${oneDecimal(price / 1_000_000_000.0)}B"
}
