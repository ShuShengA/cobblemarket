package com.shusheng.cobblemarket.util

import net.minecraft.text.MutableText
import net.minecraft.text.Text

/**
 * 精灵「附加信息行」的**共用组装入口** —— 超极巨化/极巨等级、初始训练家。
 *
 * ⚠ 与 [TypeTextColors.typeLineWithTera] 同一个理由：这两行出现在**多处**
 *   （市场悬停窗与各弹窗、拍卖列表与聊天播报、求购待交付、分享到聊天…），
 *   各拼各的迟早漏一处、或者几处文案不一致 —— **界面与服务端聊天共用这一份**。
 *
 * ⚠ 文案遵循项目约定「名字带色、陪衬文字不设色、段落平级」：内容全部挂在
 *   `Text.literal("")` 下平级 append，绝不让带色段当父节点（MC 会把父样式传给每段）。
 */
object PokemonExtraLines {

    /**
     * 极巨化行；**两者都是默认值（false / 0）时返回 null**（调用方按"整行不画"处理）——
     * 这是绝大多数精灵的情况，布局完全不受影响。
     *
     * 三种形态（⚠ 超极巨化与极巨等级**不是二选一**：超极巨走同一条 HP 倍率公式）：
     * - 都有：`超极巨化（极巨等级：5）`
     * - 只解锁超极巨：`超极巨化`
     * - 只喂过糖果：`极巨等级：3`
     *
     * ⚠ 括号内那一条走**独立词条**：中文全角「（）」、英文半角加空格，必须分语言，
     *   不能在代码里手工拼括号。
     */
    fun dynamax(gmaxFactor: Boolean, dmaxLevel: Int): MutableText? {
        if (!gmaxFactor && dmaxLevel <= 0) return null
        val line = Text.literal("")
        if (gmaxFactor) line.append(Text.translatable("cobblemarket.gui.tooltip_gmax"))
        if (dmaxLevel > 0) {
            line.append(
                if (gmaxFactor) Text.translatable("cobblemarket.gui.tooltip_dmax_inline", dmaxLevel)
                else Text.translatable("cobblemarket.gui.tooltip_dmax", dmaxLevel)
            )
        }
        return line
    }

    /**
     * 初始训练家行；**空串返回 null**（没有 OT / 玩家名解析不出来 ⇒ 整行不画，
     * 宁可少一行也不把一串 UUID 甩给玩家看）。
     */
    fun originalTrainer(name: String): MutableText? {
        if (name.isBlank()) return null
        return Text.literal("")
            .append(Text.translatable("cobblemarket.gui.tooltip_ot"))
            .append(Text.literal(name))
    }
}
