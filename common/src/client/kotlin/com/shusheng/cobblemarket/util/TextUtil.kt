package com.shusheng.cobblemarket.util

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.text.MutableText
import net.minecraft.text.Text

object TextUtil {

    /**
     * 输入框占位符：原版画占位符**不裁剪**，长了会溢出到隔壁格（用户 2026-10-08 报
     * 「Max unit price 的 e 顶出框外」）。按**量出来的宽度**从长到短挑第一个放得下的 ——
     * 中文放得下就一直用全称，英文放不下才退回短版。
     * `8` = 原版输入框左右各 4px 内边距（照 RP 商城 `code_hint` / EV hint 的先例）。
     *
     * ⚠ 新增占位符前先量宽度：英文半角 **6px/字符**、中文 **9px/字符**（默认位图字体），
     *   别按中文观感定长短 ——「最高单价」36px、「Max unit price」84px，同一格中文刚好、英文溢出。
     * ⚠ 传**控件本身**而不是宽度数字：框宽改了这里自动跟着走，不会两处不同步。
     */
    fun fieldHint(field: TextFieldWidget, longKey: String, shortKey: String): MutableText =
        listOf(longKey, shortKey).map { Text.translatable(it) }
            .firstOrNull { MinecraftClient.getInstance().textRenderer.getWidth(it) <= field.width - 8 }
            ?: Text.translatable(shortKey)

    /**
     * **并排**的一组框共用同一个判断（按**最窄**的那个）—— 逐框各判的话，相邻两格可能一个用全称、
     * 一个退回短版，并排摆着就不对称了（用户 2026-10-08 指出）。
     *
     * ⚠ 用这个重载时，**调用点必须等这一组框都创建完**再设占位符（否则列表里还少一个，
     *   又退化成逐框判断 —— 跟没用一样）。
     */
    fun fieldHint(fields: List<TextFieldWidget>, longKey: String, shortKey: String): MutableText =
        fields.minByOrNull { it.width }?.let { fieldHint(it, longKey, shortKey) }
            ?: Text.translatable(shortKey)

    // 截断到 maxWidth 像素：超宽时截断尾部加 "…"；未超宽原样返回。
    // 用于黑名单按钮/列表等固定宽度区域，防止无翻译物品的超长 key 原文溢出控件。
    fun truncateString(s: String, maxWidth: Int): String {
        val font = MinecraftClient.getInstance().textRenderer
        if (font.getWidth(s) <= maxWidth) return s
        var end = s.length - 1
        while (end > 0 && font.getWidth(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }

    fun truncateText(t: Text, maxWidth: Int): Text = Text.literal(truncateString(t.string, maxWidth)).setStyle(t.style)

    // 保留分段样式的截断（富文本版 truncateString）：
    // truncateText 会把整串压成一段 —— 类型行的属性色、IV 行的 EV 红、闪光星标的金都会丢。
    // 这里按 visit 出来的样式段逐段累计宽度，超预算的段裁掉，各段颜色原样保留。
    fun truncateStyled(t: Text, maxWidth: Int): Text {
        val font = MinecraftClient.getInstance().textRenderer
        if (font.getWidth(t) <= maxWidth) return t
        val budget = maxWidth - font.getWidth("…")
        val out = Text.empty()
        var used = 0
        var full = false
        t.visit({ style, s ->
            if (!full && s.isNotEmpty()) {
                var cut = s
                while (cut.isNotEmpty() && used + font.getWidth(cut) > budget) cut = cut.dropLast(1)
                if (cut.isNotEmpty()) {
                    out.append(Text.literal(cut).setStyle(style))
                    used += font.getWidth(cut)
                }
                if (cut.length < s.length) full = true
            }
            java.util.Optional.empty<Unit>()
        }, net.minecraft.text.Style.EMPTY)
        return out.append(Text.literal("…"))
    }

    // 物品显示名照物品栏悬浮第一行着色（按稀有度；名字自带样式码的物品如卡片不受影响）
    fun rarityColoredName(stack: ItemStack): Text = stack.name.copy().formatted(stack.rarity.formatting)

    // 物品名颜色（= 物品栏悬浮的名字颜色，即稀有度色）；没有栈 / 没有稀有度时返回白色。
    // 与 rarityColoredName 同一口径（原版 getTooltip 首行就是 name 套 rarity.formatting，已核对 1.21.1 字节码），
    // 供「名字要拆成独立一段画」的场景（行内两段绘制、悬停窗两段混排）取色，Text 版取不到纯色值。
    fun rarityNameColor(stack: ItemStack?): Int = stack?.rarity?.formatting?.colorValue ?: 0xFFFFFF

    /**
     * 客户端语言是不是中文 —— **全项目唯一判据**，别在别处再写一遍 `startsWith("zh")`
     * （此前有三处各写各的，将来必然不同步）。
     */
    fun isZhClient(): Boolean = MinecraftClient.getInstance().options.language.startsWith("zh")

    /**
     * 左 / 右括号：中文全角、英文半角。
     * ⚠ 英文**必须**半角 —— 全角括号在英文里占 16px（原先 `ivText` 那处注释记的坑：
     * 强制下架弹窗左列 IV 行会被挤到下架按钮上）；反过来说，改半角只会更宽松、不会引发溢出。
     * ⚠ 服务端**不能**用这几个（拿不到客户端语言）—— 那边要拼标点就走词条。
     */
    fun parenOpen(): String = if (isZhClient()) "（" else "("
    fun parenClose(): String = if (isZhClient()) "）" else ")"

    /** 用括号包住内容，按客户端语言取形：中文 `（x）`、英文 `(x)` */
    fun paren(inner: String): String = parenOpen() + inner + parenClose()

    /** 并列项分隔符：中文顿号、英文逗号加空格 */
    fun listSeparator(): String = if (isZhClient()) "、" else ", "

    // IV 显示：极限特训过（hyper trained）时显示「真实值（特训值）」，未特训只显示真实值。
    // 用于市场/拍卖/上架等界面的个体值展示，与 Cobblemon 队伍详情格式一致。
    // 括号按语言（走上面的共用函数，别再内联判断）
    fun ivText(real: Int, hyperTrained: Int): String {
        if (hyperTrained < 0) return "$real"
        return "$real" + paren("$hyperTrained")
    }
}
