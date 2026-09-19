package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.config.CurrencyHandler
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.math.BigInteger

/**
 * **交易流程唯一的币种门面** —— 业务代码只认它，不直接碰 [CurrencyHandler] 或 [PointsState]。
 *
 * 为什么包一层而不改 [CurrencyHandler]：主货币那 4 个后端（Cobblemon Economy / CobbleDollars /
 * Impactor / 物品）有 100+ 处调用点，动它的回归面远大于收益；这里只做币种分派，主货币路径原样透传。
 *
 * ⚠ **币种的来源**（方案「信任边界」）：只有**创建**类请求的币种来自客户端（校验后写进服务端对象），
 * 此后一切操作都从**服务端对象**读币种 —— 相关 payload 里不许有币种字段，有就会被伪造。
 *
 * ⚠ **统计隔离**：RP 交易**不能**调 `FinanceState.recordTrade`（它喂着紫卡/黑卡申请门槛与贷款额度公式），
 * 否则玩家能用RP 来回刷交易量、白嫖卡片资格和更高额度。交易留痕改走 `TransactionHistory.addRecord`。
 */
object CurrencyService {

    /** 余额。主货币是 BigInteger（物品后端 / 大数值），RP 是 Long —— 统一在门面提成 BigInteger */
    fun balance(player: ServerPlayerEntity, currency: MarketCurrency): BigInteger =
        when (currency) {
            MarketCurrency.PRIMARY -> CurrencyHandler.getBalance(player)
            MarketCurrency.POINTS -> BigInteger.valueOf(PointsState.get(player.server).getBalance(player.uuid))
        }

    /**
     * 扣款。**不足 / 失败一律 false 且分文不动**（绝不部分扣 —— 部分扣会让账目对不上）。
     *
     * ⚠ 主货币的后端接口是 Int 口径：超过 `Int.MAX_VALUE` 直接判失败，
     * **不静默截断** —— 截断会变成「少扣钱多给货」。
     */
    fun remove(player: ServerPlayerEntity, currency: MarketCurrency, amount: Long): Boolean {
        if (amount <= 0) return false
        return when (currency) {
            MarketCurrency.PRIMARY ->
                amount <= Int.MAX_VALUE && CurrencyHandler.remove(player, amount.toInt())

            MarketCurrency.POINTS ->
                PointsState.get(player.server).removeBalance(player.uuid, amount)
        }
    }

    /**
     * 发放，返回**实际发放量**。主货币的物品后端可能因背包满而少发（未发部分由调用方留在账本），
     * RP 是纯数字、不存在放不下的情况，必定全额到账。
     */
    fun give(player: ServerPlayerEntity, currency: MarketCurrency, amount: Long): Long {
        if (amount <= 0) return 0L
        return when (currency) {
            MarketCurrency.PRIMARY -> CurrencyHandler.give(player, amount)

            MarketCurrency.POINTS -> {
                PointsState.get(player.server).addBalance(player.uuid, amount)
                amount
            }
        }
    }

    /**
     * 货币单位文本（跟在金额后面）。主货币金色（`₽` 的 goldCurrencyText 就是金色）。
     *
     * RP 走 **`points_symbol.png` 图标字形**（font glyph，方案「图标渲染」路径 A）——
     * 这里是唯一的落点：命令反馈、聊天消息、界面全都经 [unitText] / [amountText] 取单位，
     * 改这一处全项目跟着变，没有第二处要改。
     */
    fun unitText(currency: MarketCurrency): Text = when (currency) {
        MarketCurrency.PRIMARY -> CurrencyHandler.goldCurrencyText()
        MarketCurrency.POINTS -> pointsIcon()
    }

    /**
     * RP 图标（行内货币单位，8×8，与文字行高齐平）。
     *
     * **必须单独设白色**：MC 会用文字颜色**乘算**字形，不设白的话彩色图会被金额行的金色染成金色调。
     *
     * ⚠ **降级开关就是这个函数体** —— 图标出问题时（服主换的图不合格、字体没加载上等）
     * 换成 `Text.literal("RP").formatted(Formatting.GOLD)` 即可全项目退回文字形态：
     * 所有调用方都经 [unitText] 取单位，没有别的地方要动。
     */
    fun pointsIcon(): Text = Text.literal(POINTS_GLYPH)
        .styled { it.withFont(POINTS_FONT).withColor(0xFFFFFF) }

    /**
     * RP 图标的字形字符 **U+E000**（私用区首字符）。
     *
     * ⚠ 必须写成转义形式（反斜杠 + uE000）—— 别直接嵌那个不可见字符本身，
     * 编辑器/工具链会悄悄吃掉它、变成空串，而且**不报错**，只表现为聊天里少个图标。
     */
    private const val POINTS_GLYPH = "\uE000"

    /** 字形所在字体：`assets/cobblemarket/font/points.json`，它的 bitmap 指向 `textures/gui/points_symbol.png` */
    private val POINTS_FONT = Identifier.of("cobblemarket", "points")

    /**
     * 同一个图标、**标题尺寸（18×18）**：给界面上的大标题用（余额 HUD 那种行内单位仍走 [pointsIcon]）。
     *
     * 为什么要另开一个字形、而不是把行内那个放大：**字形尺寸在字体 json 里是写死的**，
     * 渲染时缩放不了单个字形 —— 想让标题上的图标大，只能再注册一个 `height: 18` 的字形。
     * 两张字体 json 指同一个 `points_symbol.png`，所以服主换图时两处一起变，不会分叉。
     */
    fun pointsTitleIcon(): Text = Text.literal(POINTS_TITLE_GLYPH)
        .styled { it.withFont(POINTS_TITLE_FONT).withColor(0xFFFFFF) }

    /** 标题字形的字符 **U+E001**（⚠ 转义写法，理由同 [POINTS_GLYPH]） */
    private const val POINTS_TITLE_GLYPH = "\uE001"

    /** 标题字形所在字体：`assets/cobblemarket/font/points_title.json`（同图，height 18 / ascent 10） */
    private val POINTS_TITLE_FONT = Identifier.of("cobblemarket", "points_title")

    /**
     * 「金额 + 单位」一体（金额金色，**中间带一个空格**）：聊天消息与命令反馈统一用它拼。
     *
     * ⚠ 空格在**这里**拼，别让调用方自己加 —— 调用点几十处，漏一个就是「1,000▣」贴着，
     * 而且图标比 `₽` 更容易"糊"在数字上，没有空格看不出边界。
     */
    fun amountText(amount: Any, currency: MarketCurrency): Text =
        Text.literal("").append(CurrencyHandler.goldAmount(amount))
            .append(" ").append(unitText(currency))

    /** RP 不接喵喵支付：准备金池垫付的是主货币，等于跨币种兑换，违反「不可兑换」 */
    fun supportsMeowthPay(currency: MarketCurrency): Boolean = currency == MarketCurrency.PRIMARY

    /** RP 不吃卡片手续费减免（黑金卡/紫金卡的手续费与折扣只跟主货币有关） */
    fun supportsFeeDiscount(currency: MarketCurrency): Boolean = currency == MarketCurrency.PRIMARY
}
