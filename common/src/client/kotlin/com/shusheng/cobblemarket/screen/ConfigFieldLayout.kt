package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.finance.CardNames
import net.minecraft.client.font.TextRenderer

/**
 * 配置弹窗里「标签 + 输入框 + ↺ 重置按钮」这一行的横向布局，六个配置界面共用
 * （服务器 / 金融 / 紫电卡 / 金轮卡 / 两张卡的申请条件）。
 *
 * 这一行自右向左排：弹窗右内缘 10px → ↺ 按钮 20px → 间隙 2px → **输入框** → 间隙 8px → 标签。
 *
 * ⚠ **输入框右缘钉死在 [configFieldRight]**，加宽一律**往左长**。
 * 2026-09-22 踩过：加宽时只改了「创建控件」那处的 x、漏改「滚动重建」那处的 x，
 * 右缘就按老宽度往右戳出弹窗背景（用户看到「输入框右边又戳出一个框」）。
 * 所以 x 与宽度**只在 [configFieldX] / [configFieldWidth] 里算**，界面里别再各写一份。
 *
 * 宽度**从本行标签宽反算**（而不是写死一个数）：标签占得多的行，框自动让出几像素。
 * 6e47e08 把弹窗放宽到 360px 才让那批长英文标签放得下，框要是把这点余量吃掉，
 * 英文标签就又压到框上了 —— 自适应比按中文观感写死安全。
 */
object ConfigFieldLayout {

    /** 数字框最宽：金额最多 10 位（`maxLength = 10` ≈ 60px）+ 左右内边距（原来写死的 54 差几个像素） */
    const val NARROW_W = 72

    /** 数字框最窄的下限 = 原来的宽度：标签再长也不再往下缩，改为截断标签兜底 */
    const val MIN_W = 54

    /** 长文本框宽度上限：再宽就把该行的标签挤没了，装不下的部分靠光标滚动看 */
    const val WIDE_W = 152

    /** 值是**一长串**的配置项（逗号分隔的档位 / 期数:费率）：窄框连一半都看不全。
     *  各界面按自己有的取，不在这张表里的 key 命中不了、无副作用 */
    private val LONG_TEXT_KEYS = setOf("auctionDurations", "loanPlans")

    /** 输入框右缘 = ↺ 按钮左缘再退 2px。**固定不动**，加宽只往左长 */
    fun configFieldRight(dialogX: Int, dialogW: Int) = dialogX + dialogW - 10 - 20 - 2

    /**
     * 本行扣掉标签后还剩多少给输入框（10 右内缘 + 20 按钮 + 2 间隙 + 10 标签起点 + 8 标签右间隙）。
     *
     * ⚠ 标签必须经 [CardNames.label] 展开再测 —— 卡名类标签的词条里是 `%1$s` 占位，
     * 直接 `Text.translatable` 量到的是占位符的宽度（约 24px），比卡名实际宽度（约 54px）小一大截，
     * 输入框会按错宽度算、压到标签上。
     */
    private fun availableWidth(textRenderer: TextRenderer, dialogW: Int, labelKey: String): Int =
        dialogW - 10 - 20 - 2 - 10 - 8 - textRenderer.getWidth(CardNames.label(labelKey))

    /** 该行输入框的宽度：普通数字项 [MIN_W]~[NARROW_W]；长文本项最宽 [WIDE_W]，两者都不挤标签 */
    fun configFieldWidth(textRenderer: TextRenderer, dialogW: Int, key: String, labelKey: String): Int {
        val avail = availableWidth(textRenderer, dialogW, labelKey)
        return if (key in LONG_TEXT_KEYS) avail.coerceIn(NARROW_W, WIDE_W)
        else avail.coerceIn(MIN_W, NARROW_W)
    }

    /** 该行输入框的左边缘（右缘固定，宽度往左摊） */
    fun configFieldX(textRenderer: TextRenderer, dialogX: Int, dialogW: Int, key: String, labelKey: String): Int =
        configFieldRight(dialogX, dialogW) - configFieldWidth(textRenderer, dialogW, key, labelKey)

    /** 标签可用宽：窗口小到连 [MIN_W] 都放不下时才截断兜底，别被右侧输入框压住 */
    fun configLabelWidth(textRenderer: TextRenderer, dialogW: Int, key: String, labelKey: String): Int =
        configFieldRight(0, dialogW) - 10 - configFieldWidth(textRenderer, dialogW, key, labelKey) - 8
}
