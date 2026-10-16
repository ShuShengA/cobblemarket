package com.shusheng.cobblemarket.finance

import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Util

/**
 * 卡片名字的彩色渲染：逐字上色 + **颜色沿文字流动**。
 *
 * 流动靠调用方**每帧重绘**实现（物品栏悬停的 tooltip、喵喵银行的持有者面板标题都是每帧构建的），
 * 这里按当前时刻算配色偏移即可 —— 聊天消息是服务端构造的静态文本、渲染一次就定死，**流动不了**，
 * 那边用词条里写死的 § 色码（16 色近似，见 card.purple_name / card.black_name）。
 *
 * 「喵·」前缀**保持金色不动**，只有卡名部分参与着色（用户 2026-09-22 拍板）。
 */
object CardNames {

    /** 紫电卡配色（取自卡片贴图上的蓝紫主色，不是估的）。三色循环 —— 两色时每推一格是整体反相，像闪不像流动 */
    private val VOLT_PALETTE = intArrayOf(
        0x6059E4, // 紫水晶 · 亮紫蓝
        0xFFCC33, // 电属性 · 黄
        0x8A5CD1, // 紫水晶 · 中调紫（补的第三色，让颜色能"走"起来）
    )

    /**
     * 18 属性色，顺序照宝可梦图鉴。
     * ⚠ 与 `EntryBadgeRenderer.typeColor` **同源** —— 那边改了这里要跟着改。
     * （不能直接引用它：那个在 `client/` 源集，而 `getName()` 覆写写在双方共用的 main 侧。）
     */
    private val TYPE_COLORS = intArrayOf(
        0xAAAA99, // normal  一般
        0xFF4422, // fire    火
        0x3399FF, // water   水
        0xFFCC33, // electric 电
        0x77CC55, // grass   草
        0x66CCFF, // ice     冰
        0xBB5544, // fighting 格斗
        0xAA5599, // poison  毒
        0xDDBB55, // ground  地面
        0x8899FF, // flying  飞行
        0xFF5599, // psychic 超能力
        0xAABB22, // bug     虫
        0xBBAA66, // rock    岩石
        0x6666BB, // ghost   幽灵
        0x7766EE, // dragon  龙
        0x775544, // dark    恶
        0xAAAABB, // steel   钢
        0xFFAAFF, // fairy   妖精
    )

    /**
     * 流动速度：每这么多毫秒把配色往前推一个字。调小 = 跑得更快。
     *
     * ⚠ 紫电卡只有紫/黄两色、卡名又只有三个字，**每推一格是整体反相**（紫电紫 → 电紫电）——
     * 调太快就不像"流动"而像"闪烁"了（2026-09-22 用户反馈 160ms 偏快）。金轮卡 18 色没有这个问题。
     */
    private const val SHIFT_MS = 350L

    // 两张卡各存一份缓存 —— ⚠ 别合并成一份：两者会在同一帧里先后被调用，共用一个键会互相清空、每帧都重建。
    // 键带上「时间片 + 文案」：同一时间片内复用，客户端切语言时文案变了自动失效。
    private var voltTick = Long.MIN_VALUE
    private var voltPlain: String? = null
    private var voltText: Text? = null

    private var genesisTick = Long.MIN_VALUE
    private var genesisPlain: String? = null
    private var genesisText: Text? = null

    // 「去掉前缀」版单独一套缓存 —— ⚠ 别和上面共用：两者会在同一帧里先后被调用，共键会互相清空、每帧重建
    private var voltShortTick = Long.MIN_VALUE
    private var voltShortPlain: String? = null
    private var voltShortText: Text? = null

    private var genesisShortTick = Long.MIN_VALUE
    private var genesisShortPlain: String? = null
    private var genesisShortText: Text? = null

    /**
     * 喵·紫电卡（紫水晶紫 ↔ 电黄 交替，颜色沿文字流动）。
     * [withPrefix] = false ⇒ **只出卡名本身、不带「喵·」** —— 窄格子里带前缀会撑不下
     * （2026-10-16 用户要求：界面里直接用卡片名字，别加 Meowth·）。
     */
    fun volt(withPrefix: Boolean = true): Text {
        val t = tick()
        val plain = plainName("cobblemarket.card.purple_name")
        if (!withPrefix) {
            if (voltShortText == null || t != voltShortTick || plain != voltShortPlain) {
                voltShortTick = t
                voltShortPlain = plain
                voltShortText = buildShort(plain) { i ->
                    VOLT_PALETTE[Math.floorMod(i - shift(VOLT_PALETTE.size), VOLT_PALETTE.size)]
                }
            }
            return voltShortText!!
        }
        if (voltText == null || t != voltTick || plain != voltPlain) {
            voltTick = t
            voltPlain = plain
            voltText = build(plain) { i ->
                VOLT_PALETTE[Math.floorMod(i - shift(VOLT_PALETTE.size), VOLT_PALETTE.size)]
            }
        }
        return voltText!!
    }

    /**
     * 喵·金轮卡（18 属性色循环流动）。[withPrefix] = false 同 [volt]：只出卡名本身。
     */
    fun genesis(withPrefix: Boolean = true): Text {
        val t = tick()
        val plain = plainName("cobblemarket.card.black_name")
        if (!withPrefix) {
            if (genesisShortText == null || t != genesisShortTick || plain != genesisShortPlain) {
                genesisShortTick = t
                genesisShortPlain = plain
                genesisShortText = buildShort(plain) { i ->
                    TYPE_COLORS[Math.floorMod(i - shift(TYPE_COLORS.size), TYPE_COLORS.size)]
                }
            }
            return genesisShortText!!
        }
        if (genesisText == null || t != genesisTick || plain != genesisPlain) {
            genesisTick = t
            genesisPlain = plain
            genesisText = build(plain) { i ->
                TYPE_COLORS[Math.floorMod(i - shift(TYPE_COLORS.size), TYPE_COLORS.size)]
            }
        }
        return genesisText!!
    }

    /** 同 [build]，但**不带「喵·」前缀**、只给卡名逐字着色（窄格子用） */
    private fun buildShort(plain: String, colorAt: (Int) -> Int): Text {
        val out = Text.empty()
        val dot = plain.indexOf('·')
        val name = if (dot < 0) plain else plain.substring(dot + 1)
        name.forEachIndexed { i, c -> out.append(colored(c, colorAt(i))) }
        return out
    }

    /**
     * **词条标签里的卡名占位** → 动态彩色卡名。
     *
     * 词条里写 `%1$s`（紫电卡位置）、`%2$s`（金轮卡位置），没用到的不写即可；
     * 例：`"申请 %1$s"`、`"%1$s 全服上限（张）"`、`"%2$s 持有者手续费减免"`。
     *
     * ⚠ **词条里不要再内嵌卡名本身**（`§6喵§d·…§r` 那种）—— 内嵌的是静态文本，不会流动。
     * ⚠ 需要按标签**测宽**的地方（如 `ConfigFieldLayout`）也必须调这里，不能拿 `%1$s` 去量。
     */
    fun label(key: String): MutableText = Text.translatable(key, volt(), genesis())

    /**
     * 同 [label]，但返回**字符串** —— 给 `drawText(String)` 这类只能吃字符串的渲染路径用
     * （当前只有配置界面「文案含 | 时拆两行」那条）。字符串带不了 RGB 样式，所以这里用
     * **16 色 § 码近似**；每帧调用时同样会流动。
     */
    fun labelString(key: String): String = Text.translatable(key).string
        .replace("%1\$s", coloredCardString("cobblemarket.card.purple_name", FMT_PURPLE))
        .replace("%2\$s", coloredCardString("cobblemarket.card.black_name", FMT_RAINBOW))

    /** 紫电卡的 16 色近似：§9 蓝 / §e 黄 / §d 浅紫，顺序与 [VOLT_PALETTE] 一致 */
    private val FMT_PURPLE = charArrayOf('9', 'e', 'd')

    /** 18 属性映射到最近的 § 码，顺序与 [TYPE_COLORS] 一致 */
    private val FMT_RAINBOW = charArrayOf(
        '7', 'c', '9', 'e', 'a', 'b', '4', '5', '6', 'b', 'd', 'a', '6', '5', '5', '8', '7', 'd',
    )

    /** 把卡名拼成「金色前缀 + §d· + 逐字 § 色码」的字符串 */
    private fun coloredCardString(key: String, palette: CharArray): String {
        val plain = plainName(key)
        val dot = plain.indexOf('·')
        if (dot < 0) return plain
        val sb = StringBuilder("§6").append(plain, 0, dot).append("§d·")
        val s = shift(palette.size)
        plain.substring(dot + 1).forEachIndexed { i, c ->
            sb.append('§').append(palette[Math.floorMod(i - s, palette.size)]).append(c)
        }
        return sb.toString()
    }

    /**
     * 卡片图标的**悬停提示内容**（卡名 + 风味描述），文案与颜色都跟物品栏悬停完全一致。
     *
     * 给**非物品栏**的卡片图标用 —— 喵喵银行入口左右两张卡、两张申请界面顶部的大图都是
     * `renderScaledGuiItemIcon` 直绘的，绕过了 `ItemStack` 的 tooltip 机制，得自己画。
     */
    fun voltTooltip(): List<Text> = listOf(
        volt(),
        Text.translatable("item.cobblemarket.meowth_purple_card.desc").formatted(Formatting.LIGHT_PURPLE),
    )

    fun genesisTooltip(): List<Text> = listOf(
        genesis(),
        Text.translatable("item.cobblemarket.meowth_black_card.desc").formatted(Formatting.YELLOW),
    )

    /** 当前时间片（每 [SHIFT_MS] 毫秒前进一格） */
    private fun tick(): Long = Util.getMeasuringTimeMs() / SHIFT_MS

    /** 当前配色偏移，落在 [0, period) */
    private fun shift(period: Int): Int = (tick() % period).toInt()

    /** 词条文本剥掉 § 色码：词条本身是给聊天用的 16 色版，这里只要文字 */
    private fun plainName(key: String): String =
        Text.translatable(key).string.replace(Regex("§."), "")

    /**
     * 「前缀 + · + 卡名」拆开着色：前缀金色、分隔点浅紫（与原来的 `§6喵§d·` 一致），卡名逐字走 [colorAt]。
     * 按 `·` 定位而不是写死字数 —— 中文是「喵」英文是「Meowth」，长度不同。
     */
    private fun build(plain: String, colorAt: (Int) -> Int): Text {
        val out = Text.empty()
        val dot = plain.indexOf('·')
        if (dot < 0) {
            // 兜底：没有分隔点时整体着色
            plain.forEachIndexed { i, c -> out.append(colored(c, colorAt(i))) }
            return out
        }
        out.append(Text.literal(plain.substring(0, dot)).formatted(Formatting.GOLD))
        out.append(Text.literal("·").formatted(Formatting.LIGHT_PURPLE))
        plain.substring(dot + 1).forEachIndexed { i, c -> out.append(colored(c, colorAt(i))) }
        return out
    }

    private fun colored(c: Char, rgb: Int): Text =
        Text.literal(c.toString()).styled { it.withColor(rgb) }
}
