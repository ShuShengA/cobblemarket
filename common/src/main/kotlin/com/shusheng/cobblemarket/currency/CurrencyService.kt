package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.config.CurrencyHandler
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
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
 * ⚠ **统计隔离**：点券交易**不能**调 `FinanceState.recordTrade`（它喂着紫卡/黑卡申请门槛与贷款额度公式），
 * 否则玩家能用点券来回刷交易量、白嫖卡片资格和更高额度。交易留痕改走 `TransactionHistory.addRecord`。
 */
object CurrencyService {

    /** 余额。主货币是 BigInteger（物品后端 / 大数值），点券是 Long —— 统一在门面提成 BigInteger */
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
     * 点券是纯数字、不存在放不下的情况，必定全额到账。
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
     * 货币单位文本（跟在金额后面）。**两种货币都金色**：虚拟货币统一金色是既定规则
     * （`₽` 的 goldCurrencyText 也是金色），单位掉色会让整串金额看着"不金"。
     *
     * 点券现在用 **`RP`**（照 PokéMMO 的写法，两个 ASCII 字母，任何字体都画得出来）。
     * ⚠ **B3 会把点券这行换成点券图标字形**（font glyph，见方案「图标渲染」路径 A）——
     * 届时只改这里一处，所有调用方（命令反馈、聊天消息、界面）自动跟着变。
     */
    fun unitText(currency: MarketCurrency): Text = when (currency) {
        MarketCurrency.PRIMARY -> CurrencyHandler.goldCurrencyText()
        MarketCurrency.POINTS -> Text.literal("RP").formatted(Formatting.GOLD)
    }

    /** 「金额 + 单位」一体（金额金色）：聊天消息与命令反馈统一用它拼 */
    fun amountText(amount: Any, currency: MarketCurrency): Text =
        Text.literal("").append(CurrencyHandler.goldAmount(amount)).append(unitText(currency))

    /** 点券不接喵喵支付：准备金池垫付的是主货币，等于跨币种兑换，违反「不可兑换」 */
    fun supportsMeowthPay(currency: MarketCurrency): Boolean = currency == MarketCurrency.PRIMARY

    /** 点券不吃卡片手续费减免（黑金卡/紫金卡的手续费与折扣只跟主货币有关） */
    fun supportsFeeDiscount(currency: MarketCurrency): Boolean = currency == MarketCurrency.PRIMARY
}
