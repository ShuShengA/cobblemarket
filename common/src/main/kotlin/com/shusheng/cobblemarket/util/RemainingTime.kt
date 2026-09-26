package com.shusheng.cobblemarket.util

import net.minecraft.text.Text

/**
 * 剩余时间文本（拍卖场 / 管理端拍卖 / 求购单 / 聊天播报共用）。
 *
 * 2026-09-19 收口：此前这套格式化在 `AuctionScreen`、`AdminAuctionScreen`、`BuyOrderScreen`
 * 各抄了一份，聊天播报的悬停窗还自成一派（`结束时间719分钟`）—— 四处显示各不相同，
 * 现在统一走这里（已结束/结算中走词条，按玩家语言渲染；数字部分用语言无关的 s/m/h/d 紧凑写法）。
 *
 * 精度分档：1 分钟内给秒、3 分钟内给「分+秒」（反狙击延长的效果看得见，1m59s → 2m0s）、
 * 1 小时内给分、24 小时内给「时+分」、超过给「天+时」。
 */
object RemainingTime {

    /** 「即将到期」的告警窗口：剩余不足 5 分钟就变红 */
    private const val WARN_WINDOW_MS = 5 * 60 * 1000L

    /** 剩余不足 [WARN_WINDOW_MS] 时的告警色 */
    const val URGENT_COLOR = 0xFF6666

    /** 还没到告警窗口时的常规色 */
    const val NORMAL_COLOR = 0xAAAAAA

    /**
     * 剩余时间的**显示色** —— 列表行内与悬停窗**必须共用这一个**。
     *
     * ⚠ 此前这套配色在 `AuctionScreen` / `AdminAuctionScreen` / `BuyOrderScreen` 的**行内**各写了一遍，
     *   而这三处的**悬停窗压根没上色**（整行白）：行里是最醒目的红色告警，悬停窗里却白得毫无提示
     *（用户 2026-09-26 报「行内是彩色、悬浮窗是白色」）。同 [text]，也收到这里来。
     */
    fun color(endsAt: Long, now: Long = System.currentTimeMillis()): Int =
        if (endsAt - now < WARN_WINDOW_MS) URGENT_COLOR else NORMAL_COLOR

    /**
     * @param endsAt 结束时刻（毫秒时间戳）
     * @param endedKey 已结束时的词条 key（拍卖与求购单各有一句，按玩家语言渲染）
     * @param now 当前时刻，默认取系统时间（传入是为了可测）
     * @param secondPrecision 是否显示秒级精度 —— 求购单没有反狙击延长，不需要秒，传 false
     */
    fun text(
        endsAt: Long,
        endedKey: String,
        now: Long = System.currentTimeMillis(),
        secondPrecision: Boolean = true
    ): Text {
        val ms = endsAt - now
        if (ms <= 0) return Text.translatable(endedKey)
        val totalSec = ms / 1000
        val totalMin = totalSec / 60
        val text = when {
            totalMin < 1 -> "${totalSec}s"
            totalMin < 3 && secondPrecision -> "${totalMin}m${totalSec % 60}s"
            totalMin < 60 -> "${totalMin}m"
            else -> {
                val h = totalMin / 60
                if (h < 24) "${h}h${totalMin % 60}m" else "${h / 24}d${h % 24}h"
            }
        }
        return Text.literal(text)
    }
}
