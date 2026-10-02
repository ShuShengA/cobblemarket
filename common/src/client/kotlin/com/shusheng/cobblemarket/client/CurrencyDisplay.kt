package com.shusheng.cobblemarket.client

import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/** 虚拟货币标识（Cobblemon Economy POKE/PCO、CobbleDollars、Impactor）—— 一律显示 ₽，不显示货币名 */
private fun isVirtualCurrencyKey(raw: String): Boolean =
    raw == com.shusheng.cobblemarket.config.CurrencyHandler.POKEDOLLARS_KEY ||
    raw == com.shusheng.cobblemarket.config.CurrencyHandler.POKECOINS_KEY ||
    raw == com.shusheng.cobblemarket.config.CurrencyHandler.COBBLEDOLLARS_KEY ||
    raw == com.shusheng.cobblemarket.config.CurrencyHandler.IMPACTOR_KEY

/**
 * 货币显示名：payload 的 currencyName 字段，物品模式下发的是物品 ID，
 * 客户端按玩家自己的语言渲染物品名；虚拟货币下发翻译 key
 * （Cobblemon Economy POKE=PokeDollars，Cobblemon Economy PCO=PokeCoins，CobbleDollars=CobbleDollars），客户端翻译后显示。
 * 服务端语言恒为 en_us 且受资源环境影响，货币名必须在客户端渲染。
 */
fun displayCurrency(raw: String): String {
    // 虚拟货币统一显示 ₽（2026-08-27 用户拍板，不显示货币名）
    if (isVirtualCurrencyKey(raw)) return "₽"
    val id = Identifier.tryParse(raw) ?: return raw
    val item = Registries.ITEM.get(id)
    val air = Registries.ITEM.get(Identifier.of("minecraft", "air"))
    if (item == air) return raw
    val name = item.name.string
    return if (name == item.translationKey) id.path else name
}

/** 行内/弹窗金额单位：所有货币模式统一 ₽（2026-08-27 用户拍板：PCO 不再特殊显示 PCo）。 */
fun inlineCurrencyUnit(): String = "₽"

/** 悬停窗金额单位：物品货币显示物品名，虚拟货币 ₽（2026-08-27 拍板：悬停显物品名、行内显 ₽）。
 *  余额包未到达时 currencyName 为空串，此处兜底 ₽ —— 直接 displayCurrency("") 会返回空串（tryParse 得 null） */
fun activeCurrencyUnit(): String {
    val raw = BalanceCache.currencyName
    return if (raw.isEmpty()) "₽" else displayCurrency(raw)
}

// ───────────────────────── 货币单位的「绘制」形态（2026-09-29） ─────────────────────────
//
// 2026-09-29 用户拍板：**物品本位货币的符号 = 那张物品的图标**（不是物品名，名字太长；也不是 ₽）。
// 普通货币仍是 `数字 + ₽`。
//
// ⚠ **图标进不了文字流** —— MC 1.21.1 的 Text 没有 sprite 类型（那要 1.21.4+），
//   而物品是**动态**的（服主配哪个物品就得画哪个），没法像 RP 那样用一个静态字体定义指过去。
//   ⇒ 单位只能在**画完金额之后单独补一笔**，这就是下面这组函数的由来。
//
// ⚠ **虚拟货币路径必须逐字节照旧**（画 `"$amount ₽"` 一整串、同一个 x、同一个宽度）——
//   全部调用点都要改一遍，这是「改完只有物品货币视觉变化、其余零回归」的唯一保证，
//   也是验收手法：服主用虚拟货币时，改动前后截图必须一模一样。

/**
 * 行内货币图标的显示边长。
 *
 * ⚠ **别照抄 RP 的 8**（`POINTS_SYMBOL_INLINE_SIZE`）：RP 那张符号图的内容**铺满整张画布**，
 * 而**物品贴图在 16×16 里是带留白的**（钻石、大师球都只占中间一块）—— 同样缩到 8，
 * 实际画出来的东西比 RP 符号小一圈，2026-09-29 用户一眼就看出来了（「比球种图标还小」）。
 *
 * 12 = 比文字行高（8）大一半，留白扣掉后视觉上差不多与文字齐平；
 * 再大（16）在悬停窗 10px 行距里会压到上下行。
 */
/**
 * **货币图标的绘制总门禁** —— 界面在弹窗打开时必须把它置 true。
 *
 * ⚠ **为什么需要**：货币图标是 `drawItem` 画的 **3D 模型**，它**不响应**弹窗那层半透明遮罩
 * （遮罩只压暗文字/贴图，3D 图标照样按原亮度画）—— 于是下层界面的货币图标比周围压暗的
 * 文字**亮一截**，看着像"高亮浮在弹窗之上"（2026-09-29 用户报：精灵市场购买弹窗）。
 * 项目里对**物品图标**一直就是这么处理的（弹窗打开时不画下层图标），这批货币图标漏了。
 *
 * 用法：**界面不用自己管** —— `drawScreenDimMask`（`NineSlice.kt`）一画就自动置位，
 * `ScreenMixin` 每帧 HEAD 复位。全项目「只要有弹窗遮罩」的地方因此自动覆盖。
 *
 * ⚠ **唯一的例外：弹窗自己的渲染器要放开**（2026-10-02）—— 遮罩正是**这个界面自己**画的，
 *   不放开的后果是「弹窗自己的金额缺单位」（图标被自己的遮罩挡住，虚拟货币 ₽ 是字形、不受影响，
 *   所以只有物品本位货币会缺）。在**画完遮罩之后**补一句
 *   `suppressCurrencyIcons = false`（照 AuctionScreen / AdminAuctionScreen / EntryBadgeRenderer 的写法；
 *   喵喵支付 ×2 / 借款 ×2 / 存款 / 还款 / 物品购买弹窗这几处的自带遮罩同理）。
 *   门禁由 `ScreenMixin` 每帧复位 ⇒ 放开后**不用复原**。
 * ⚠ 市场两处（MarketScreen / ItemMarketScreen 的 `render()` 里按弹窗状态提前置位）是早期写法，
 *   留着当兜底，别再加这种。
 *
 * ⚠ 已接的门禁有**两个** 3D 图标入口：行内 `drawInlineCurrencyIcon`、角标 `drawCurrencyItemIcon`
 * —— 新增任何画 3D 物品图标的入口，都要把这两行判据一并带上。
 */
var suppressCurrencyIcons: Boolean = false

const val INLINE_CURRENCY_ICON_SIZE = 12

/**
 * 物品货币时，**金额与图标之间的间隙**（px）。
 *
 * ⚠ **不能照抄虚拟货币那个空格**：`₽` 是字形、贴着基线没有留白，而**物品贴图在 16×16 画布里
 * 是居中的**（四周有透明边），缩到 12 后左右各带约 1.5px 透明 —— 再叠一个 4px 的空格，
 * 视觉上就比 `1 ₽` 松一大截（2026-09-29 用户实测：「图片图标作为货币，空格看起来好别扭」）。
 *
 * 2 + 图标自身的透明边 ≈ 与虚拟货币那边观感一致；嫌紧/嫌松直接调这一个数。
 */
const val INLINE_CURRENCY_GAP = 2

/**
 * 物品本位货币时，充当「货币符号」的那只物品；**虚拟货币 / 物品 ID 非法 / 余额包未到 → null**。
 *
 * 调用方拿到 null 一律回退 `₽` 文本 —— 这与改动前的行为完全一致（余额包未到时
 * `activeCurrencyUnit()` 也是兜底 ₽）。物品 ID 非法（含空串、被删掉的模组物品）同样落到这里，
 * 不会画出空气/崩溃。
 *
 * ⚠⚠ **RP 是另一类，别混**（用户 2026-09-29 明确）：本函数取的是**主货币**
 * （`BalanceCache.currencyName`）—— `₽`（虚拟货币）与**物品图标**（物品本位货币）属于**同一类**
 * （主货币长什么样，只看服主把主货币设成了什么），而 **RP 图标是独立的一类**。
 * 一条挂单是什么币种就**只显示那一类**的单位，绝不能混着画。
 * ⇒ 上浮 1.3.0 时：这套图标**只能进 `amountWithUnit` 的 `isPoints = false` 分支**，
 *   碰 `isPoints = true` 就是「RP 挂单显示成物品图标」，属于钱显示错了。
 */
fun currencyUnitStack(): ItemStack? {
    val raw = BalanceCache.currencyName
    if (raw.isEmpty() || isVirtualCurrencyKey(raw)) return null
    val id = Identifier.tryParse(raw) ?: return null
    val item = Registries.ITEM.get(id)
    if (item == Items.AIR) return null
    return ItemStack(item)
}

/**
 * 「金额 + 货币单位」的**整体宽度** —— 任何要按这段文本算布局（居中 / 右对齐 / 截断 / 命中区）的地方都走它。
 *
 * ⚠ 虚拟货币分支与 `textRenderer.getWidth("$amount ₽")` **完全等价**，不要另外写算式。
 */
fun amountUnitWidth(textRenderer: TextRenderer, amount: String): Int {
    if (currencyUnitStack() == null) return textRenderer.getWidth("$amount ₽")
    // 物品货币：金额 + 固定间隙 + 图标（⚠ 不是空格 —— 见 INLINE_CURRENCY_GAP 的说明）。
    // 调用点传进来的金额串末尾可能带空格（历史写法），这里一律 trim 掉再算
    return textRenderer.getWidth(amount.trimEnd()) + INLINE_CURRENCY_GAP + INLINE_CURRENCY_ICON_SIZE
}

/**
 * **货币单位本身**的宽度（不含金额、也不含它前面那个空格）——
 * 词条把「金额」「单位」拆成两段画的地方，用它算第二段的位置与截断预算。
 */
fun currencyUnitWidth(textRenderer: TextRenderer): Int =
    if (currencyUnitStack() == null) textRenderer.getWidth("₽") else INLINE_CURRENCY_ICON_SIZE

/**
 * 画「金额 + 货币单位」，返回**总宽**（调用方接着排后续内容时直接用它，别再自己算）。
 *
 * - **虚拟货币**：照旧整串画 `"$amount ₽"` —— 与改动前逐像素一致（[shadow] 照调用点原来的画法传）
 * - **物品货币**：先画 `"$amount "`，再在其后补一枚 **8×8 物品图标**
 *
 * ⚠ 图标是**单独绘制**的（不是字体字形）⇒ 不受文字颜色、阴影、`truncate` 影响；
 *   但也因此**没法出现在原版 tooltip / 聊天栏**这类纯文本流里（那些地方仍是物品名）。
 */
fun drawAmountWithUnit(
    context: DrawContext,
    textRenderer: TextRenderer,
    amount: String,
    x: Int,
    y: Int,
    color: Int,
    shadow: Boolean = true,
): Int {
    val stack = currencyUnitStack()
    if (stack == null) {
        val line = "$amount ₽"
        if (shadow) context.drawTextWithShadow(textRenderer, line, x, y, color)
        else context.drawText(textRenderer, line, x, y, color, false)
        return textRenderer.getWidth(line)
    }
    // ⚠ 物品货币走「金额 + 固定间隙 + 图标」，**不是**金额 + 空格（见 INLINE_CURRENCY_GAP）；
    //   调用点传进来的金额串末尾可能带空格，trim 掉，免得再叠一层留白
    val head = amount.trimEnd()
    if (shadow) context.drawTextWithShadow(textRenderer, head, x, y, color)
    else context.drawText(textRenderer, head, x, y, color, false)
    val headW = textRenderer.getWidth(head)
    drawInlineCurrencyIcon(context, stack, x + headW + INLINE_CURRENCY_GAP, y)
    return headW + INLINE_CURRENCY_GAP + INLINE_CURRENCY_ICON_SIZE
}

/**
 * **只**画货币单位（金额已经由调用方自己画完了 —— 词条把金额与单位拆开的那种写法用它）。
 * 返回单位宽度。虚拟货币画 `₽` 文本、物品货币画图标，口径与 [drawAmountWithUnit] 一致。
 *
 * [shadow] 必须与**同一行金额**的画法一致（金额无阴影、单位却有，看着就像浮起来一层）。
 */
fun drawCurrencyUnit(
    context: DrawContext,
    textRenderer: TextRenderer,
    x: Int,
    y: Int,
    color: Int,
    shadow: Boolean = true,
): Int {
    val stack = currencyUnitStack()
    if (stack == null) {
        if (shadow) context.drawTextWithShadow(textRenderer, "₽", x, y, color)
        else context.drawText(textRenderer, "₽", x, y, color, false)
        return textRenderer.getWidth("₽")
    }
    // ⚠ 调用点传进来的 x 通常落在「金额后那个空格之后」—— 物品图标自带透明边，
    //   再留整个空格会显空（见 INLINE_CURRENCY_GAP），所以往左收掉「空格宽 − 间隙」这么多。
    //   返回值仍是**图标本身的宽度**（收掉的是它前面的空白，不影响后续布局）
    val shift = textRenderer.getWidth(" ") - INLINE_CURRENCY_GAP
    drawInlineCurrencyIcon(context, stack, x - shift, y)
    return INLINE_CURRENCY_ICON_SIZE
}

/**
 * 「词条 + 货币单位」的**整行**画法 —— 用于界面里成片的 `Text.translatable(key, 金额, 单位)` 行。
 *
 * 用法：词条照旧不动，**单位那个参数改传 `Text.literal("")`**（词条里金额与单位之间的空格保留），
 * 然后整行交给本函数 —— 它画完词条、在行尾补上单位（物品货币时就是那枚物品图标）。
 *
 * ⚠ **为什么用空串占位、而不是把词条改成单个 `%s`**：这类词条界面与聊天消息常有共用，
 *   改词条会把服务端聊天一起带偏 —— 而聊天栏按既定边界**仍显示物品名**（图标进不了纯文本流）。
 *
 * [unitColor] 默认跟行色：原来 `translatable(词条, 金额, 无样式单位)` 就是吃行色，
 * 想跟金额金色（`%s` 参数自带 GOLD 的那种）才显式传 [Formatting]/色值。
 *
 * @return 整行宽度（词条 + 单位）
 */
fun drawAmountLine(
    context: DrawContext,
    textRenderer: TextRenderer,
    line: Text,
    x: Int,
    y: Int,
    color: Int,
    unitColor: Int = color,
): Int {
    // ⚠ MC 的 drawTextWithShadow 返回的是**文本结束的 x 坐标**（不是宽度！）——
    //   单位就画在这个坐标上，别再 `x + 返回值`（那样坐标会翻倍，图标甩出去老远）
    val endX = context.drawTextWithShadow(textRenderer, line, x, y, color)
    return endX - x + drawCurrencyUnit(context, textRenderer, endX, y, unitColor)
}

/**
 * 画那枚 8×8 的物品图标。
 *
 * ⚠ 缩放走 matrices（`drawItem` 恒按 16×16 画，没有尺寸参数）——
 *   与 `drawPointsSymbol` 同款手法。在已经缩放的矩阵里调用会**跟着再缩**，
 *   这是有意的（悬停大图放大预览时单位的图标也跟着放大，见 `drawItemWithBar` 的同款说明）。
 */
/**
 * 画货币物品的图标 —— **不做**「按文字行居中」的垂直偏移，给了什么 y 就画在什么 y 上。
 * 供**按钮角标**这类要自己精确对位的地方用；行内单位走 [drawCurrencyUnit]（那个自带居中偏移）。
 */
fun drawCurrencyItemIcon(context: DrawContext, stack: ItemStack, x: Int, y: Int, size: Int) {
    // ⚠ 同样受总门禁管（见 suppressCurrencyIcons）：这是**另一个** 3D 图标入口
    //   （收款按钮角标走的就是它），弹窗打开时下层不许画 —— 漏了这里角标会比压暗的内容亮一截
    if (suppressCurrencyIcons) return
    val scale = size / 16f
    context.matrices.push()
    context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
    context.matrices.scale(scale, scale, 1f)
    context.drawItem(stack, 0, 0)
    context.matrices.pop()
}

private fun drawInlineCurrencyIcon(context: DrawContext, stack: ItemStack, x: Int, y: Int) {
    if (suppressCurrencyIcons) return   // 弹窗打开：3D 图标不受遮罩压暗，下层一律不画（见 suppressCurrencyIcons）
    val scale = INLINE_CURRENCY_ICON_SIZE / 16f
    // ⚠ 图标比文字行高（8px）高，要**往上挪半个差值**才是垂直居中 —— 贴着 y 画会整体偏下，
    //   看起来像"掉到行下面去了"（[y] 是文字行的顶部，与 drawText 的 y 同一个口径）
    val dy = y - (INLINE_CURRENCY_ICON_SIZE - 8) / 2
    context.matrices.push()
    context.matrices.translate(x.toDouble(), dy.toDouble(), 0.0)
    context.matrices.scale(scale, scale, 1f)
    context.drawItem(stack, 0, 0)
    context.matrices.pop()
}
