package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.finance.CardNames
import com.shusheng.cobblemarket.overview.PlayerOverviewRow
import com.shusheng.cobblemarket.util.RemainingTime
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/** 金额金色 —— **能花的钱**（余额 / 存款 / 利息 / 交易额…），与入口界面标题同色 */
const val OVERVIEW_GOLD = 0xFFAA00

/** 账本蓝 —— **不能花的数字**（可借额度 / 已借待还），与金融界面同口径 */
const val OVERVIEW_LEDGER_BLUE = 0x55FFFF

/**
 * 总览的一行文本。
 *
 * - [money] = 金额行：**行尾要补货币单位**（物品货币时是那件物品的图标）
 * - [color] = 金额数字与单位的颜色（非金额行不生效）
 * - [isGroup] = 分组标题行（ID 卡按分组块分列时靠它判断）
 * - [fullAmount] = 金额的**完整千分位**写法（界面上显示的是缩写，悬停时用这个展全）
 * - [icon] = **行首小图标**（图鉴那两行；标签文字由它替代，见 [LineIcon]）
 */
class OverviewLine(
    val text: Text,
    val money: Boolean = false,
    val color: Int = OVERVIEW_GOLD,
    val isGroup: Boolean = false,
    val fullAmount: String? = null,
    val icon: LineIcon? = null,
    /** 这一行占的高度 —— 普通行 [OVERVIEW_LINE_HEIGHT]，带图标的行见 [OVERVIEW_ICON_ROW_HEIGHT] */
    val height: Int = OVERVIEW_LINE_HEIGHT,
)

/**
 * 行首的小图标 —— 目前只有**图鉴**那两行用（2026-10-16 用户拍板：用 Cobblemon 图鉴的原图，
 * **替换掉「图鉴遇见 / 图鉴捕捉」两个文字标签**，玩家一眼就认得）。
 *
 * ⚠ 两张原图都是 **14×14 一格**：单张图整张就是一格，组合图（14×28）靠 [v] 取上下半
 *   —— 取样区固定 14×14，见 [OVERVIEW_ICON_CELL]。
 * ⚠ 图标**不缩放**（[OVERVIEW_ICON_SIZE] 就是原尺寸）—— 装不下普通行距的是**行**，
 *   所以带图标那两行的行高单独放宽，见 [OVERVIEW_ICON_ROW_HEIGHT]。
 */
class LineIcon(
    val id: Identifier,
    val u: Float = 0f,
    /** 取样起点 Y —— 组合图取下半时传 14（目前只用到上半，留着备用） */
    val v: Float = 0f,
    val texW: Int = OVERVIEW_ICON_CELL,
    val texH: Int = OVERVIEW_ICON_CELL,
)

/** 普通行的行高 —— ID 卡与悬停窗都按它排（与项目里其它密集列表一致） */
const val OVERVIEW_LINE_HEIGHT = 10

/** 图标原图的格子边长（两张图都是 14×14 一格） */
const val OVERVIEW_ICON_CELL = 14

/**
 * 行首图标的绘制尺寸 —— **就是原尺寸**（像素图不缩放 ⇒ 不糊）。
 *
 * ⚠ 别退回「缩到 10 塞进 10px 行距」那条：2026-10-16 用户实测报「太紧凑、数字与图标对不齐」
 *   —— 图标占满行高时上下两行直接相连看着是一坨；而且两张图**内容在格子里的分布不同**
 *   （球是圆的、上下留白），按固定偏移怎么调都对不齐。
 *   ⇒ 正解 = **行高单独放宽**（[OVERVIEW_ICON_ROW_HEIGHT]）+ 图标保持原尺寸 + 数字按行居中。
 */
const val OVERVIEW_ICON_SIZE = OVERVIEW_ICON_CELL

/**
 * 带图标那两行的**行高** —— 比普通行高一档：多出来的高度**上下各分一半**，
 * 图标之间因此有 1px 空隙、不再连成一坨（数字也按行居中，与图标同一中线）。
 *
 * ⚠ 渲染端必须**逐行累加高度**排（不是 `下标 × 10`）—— ID 卡分列与悬停窗都是。
 */
const val OVERVIEW_ICON_ROW_HEIGHT = 16

/** 图标与右侧文字的间隙 */
const val OVERVIEW_ICON_GAP = 2

/** 带图标的那一行，图标总共占的横向宽度（渲染端算截断 / 悬停窗宽度用） */
const val OVERVIEW_ICON_W = OVERVIEW_ICON_SIZE + OVERVIEW_ICON_GAP

/**
 * 图标行里，文字相对「按行高居中」还要**再下移**这么多。
 *
 * 为什么不能只靠居中：数字字形只有 8px 高、**贴着文字行的上沿**（行高 9 的字体里，
 * 底下那 1px 是留给 g / y 那种下降部字母的），所以"按行高中居"算出来数字仍然偏上
 * —— 2026-10-16 用户实测报「捕捉数的数字偏上，跟左边图标没对齐」。
 * ⚠ 改动这个值要**两处渲染一起生效**（它们都走 [lineTextY]，不会各算各的）。
 */
const val OVERVIEW_ICON_TEXT_DY = 2

/**
 * 行内文字的顶边 —— **ID 卡与总览悬停窗共用同一份算式**（两处各自算同一个量迟早不同步）。
 * 普通行：行高就是 [OVERVIEW_LINE_HEIGHT] ⇒ 偏移 0，与以前逐字节一致。
 */
fun lineTextY(rowY: Int, line: OverviewLine): Int =
    rowY + (line.height - OVERVIEW_LINE_HEIGHT) / 2 + if (line.icon != null) OVERVIEW_ICON_TEXT_DY else 0

/** 行内图标的顶边（按行高居中；普通行没有图标，取值无意义） */
fun lineIconY(rowY: Int, line: OverviewLine): Int =
    rowY + (line.height - OVERVIEW_ICON_SIZE) / 2

/**
 * 画行首小图标 —— **ID 卡与总览悬停窗共用**（与 [overviewLines] 同理：分开写迟早不一致）。
 *
 * [y] 传的是**图标自身的顶边** —— 调用方按行的实际高度居中算好（见两处渲染的 `iconY`）。
 * 尺寸就是原图尺寸、1:1 绘制 ⇒ 不用动矩阵（真要缩放时记得 `drawTexture` 的 width/height
 * 是**采样区**、显示尺寸只能靠 `matrices.scale`，本项目栽过好几次）。
 */
fun drawLineIcon(context: DrawContext, icon: LineIcon, x: Int, y: Int) {
    context.drawTexture(icon.id, x, y, icon.u, icon.v, OVERVIEW_ICON_CELL, OVERVIEW_ICON_CELL, icon.texW, icon.texH)
}

/**
 * 把一行数据**文本化** —— 全服总览的悬停窗与市场 ID 卡**共用同一份**。
 *
 * 为什么要抽出来：方案要求「ID 卡的内容与悬停窗逐项一致」；两边各写一遍的话，
 * 加字段时迟早只改一处 ⇒ 两个界面悄悄不一致（而且编译不报错）。
 *
 * ⚠ **数量与金额一律全列**（哪怕是 0）—— 2026-10-16 用户实测揪出：原先照搬总览悬停窗的
 *   「0 就不显示」，结果 ID 卡里"没存款"的人金融段只剩两行，看起来像字段被删了。
 *   悬停窗那套"省掉零值"的思路只适合**服主扫别人**；自己看自己得看得见每个格子。
 *   （状态类仍只在**异常时**出现 —— 显示一堆「封禁：否」纯属噪音。）
 *
 * ⚠ **金额一律缩写**（`9.8k` / `1.2M`，照 `formatPriceShortLong`），完整千分位数字
 *   放进 [OverviewLine.fullAmount]、由界面在**悬停**时展全（照入口界面的全服累计成交额）。
 *
 * @param groups 要不要分组标题（悬停窗要；ID 卡那块空间紧，按更紧凑的排版由调用方决定）
 * @param showOnline 首行要不要跟「在线 / 离线时记录」（**ID 卡传 false** —— 能打开 ID 卡的人
 *   必然在线，写出来是废话；用户 2026-10-16 指出）
 */
fun overviewLines(
    r: PlayerOverviewRow,
    groups: Boolean = true,
    showOnline: Boolean = true,
): List<OverviewLine> {
    val out = mutableListOf<OverviewLine>()

    // 首行：名字（+ 在线状态 —— 离线的那两项另有「离线时记录」的说明）
    val nameText = Text.literal(r.name).styled { it.withColor(if (r.online) 0xFFFFFF else 0xAAAAAA) }
    out += if (showOnline) {
        OverviewLine(
            // ⚠ 复制一份再 append —— MutableText.append 是**改自己**，直接挂会把 nameText 也改掉
            nameText.copy()
                .append(Text.literal("  "))
                .append(
                    if (r.online) Text.translatable("cobblemarket.overview.value.online").styled { it.withColor(0x55FF55) }
                    else Text.translatable("cobblemarket.overview.note.snapshot").styled { it.withColor(0x888888) }
                )
        )
    } else {
        OverviewLine(nameText)
    }

    if (groups) out += group("market")
    out += line("cobblemarket.overview.field.pokemon_listings", r.pokemonListings.toString())
    out += line("cobblemarket.overview.field.item_listings", r.itemListings.toString())
    out += line("cobblemarket.overview.field.auctions", r.activeAuctions.toString())
    out += line("cobblemarket.overview.field.buy_orders", r.openBuyOrders.toString())
    out += line("cobblemarket.overview.field.pending_pokemon", r.pendingPokemon.toString())
    out += line("cobblemarket.overview.field.pending_items", r.pendingItems.toString())
    // 待领余额：能领出来花 ⇒ 金色
    out += moneyLine("cobblemarket.overview.field.pending_balance", r.pendingBalance, OVERVIEW_GOLD)

    if (groups) out += group("money")
    val balance = r.balance
    out += if (balance == null) line("cobblemarket.overview.field.balance", noneValue())
    else moneyLine("cobblemarket.overview.field.balance", balance, OVERVIEW_GOLD)
    // PCO 余额（装了 CE 且主货币不是 PCO 时才有）—— 与主货币是**两套独立账本**，单独列一行
    r.pcoBalance?.let { out += moneyLine("cobblemarket.overview.field.pco", it, OVERVIEW_GOLD) }

    if (groups) out += group("finance")
    // 额度类：**不能花**，只活在账本上 ⇒ 蓝色（与金融界面同口径）
    // ⚠ 这里**只有一个额度数**：`creditLimitFor` 返回的已经是「信用基础 + 人工修正 − 欠款」
    //   （2026-10-16 用户实测揪出：原先又减了一次欠款、还多出一行「还能借」，是重复扣减的 bug）
    out += moneyLine("cobblemarket.overview.field.credit_limit", r.creditLimit, OVERVIEW_LEDGER_BLUE)
    // 已借 = 本金 + 利息（同样是不能花的账本数字 ⇒ 蓝色）
    out += moneyLine("cobblemarket.overview.field.debt", r.debt + r.debtInterest, OVERVIEW_LEDGER_BLUE)
    // 存款与利息：能取出来花 ⇒ 金色
    out += moneyLine("cobblemarket.overview.field.deposit", r.depositPrincipal, OVERVIEW_GOLD)
    out += moneyLine("cobblemarket.overview.field.deposit_interest", r.depositInterest, OVERVIEW_GOLD)
    out += moneyLine("cobblemarket.overview.field.net_deposit", r.netDeposit, OVERVIEW_GOLD)

    if (groups) out += group("status")
    // 状态类：只在**异常时**出现（显示一堆「封禁：否」是噪音）
    if (r.banned) {
        val until = r.banUntil
        // 永久封禁显示「永久」；有时限的显示剩余时间（走 RemainingTime 统一入口，别自己算）
        val value = if (until == null) Text.translatable("cobblemarket.overview.value.permanent").string
        else RemainingTime.text(until, "cobblemarket.overview.value.permanent").string
        out += line("cobblemarket.overview.field.banned", value)
    }
    if (r.loanBlocked) out += line("cobblemarket.overview.field.loan_blocked", Text.translatable("cobblemarket.overview.value.yes").string)
    if (r.overdue) out += line("cobblemarket.overview.field.overdue", Text.translatable("cobblemarket.overview.value.yes").string)
    if (r.badDebt) out += line("cobblemarket.overview.field.bad_debt", Text.translatable("cobblemarket.overview.value.yes").string)
    if (r.purpleCard || r.blackCard) {
        // ⚠ 卡名必须走 **CardNames** —— 它才是「逐字上色 + **颜色随时间流动**」的那份实现：
        //   按当前时刻算配色偏移（每 350ms 推一格），靠调用方**每帧重绘**来"走"。
        //   词条里写死的 § 色码是**静态文本**、渲染一次就定死，看着就是"不滚动"
        //   （2026-10-16 用户实测报的就是这个：卡名有颜色但一动不动）。
        out += lineText(
            "cobblemarket.overview.field.card",
            // ⚠ `withPrefix = false`：卡片格子窄，带「喵·」会撑不下被截成 `…Genesis C…`
            if (r.blackCard) CardNames.genesis(withPrefix = false) else CardNames.volt(withPrefix = false)
        )
    }
    out += moneyLine("cobblemarket.overview.field.volume", r.totalVolume, OVERVIEW_GOLD)

    if (groups) out += group("dex")
    out += iconLine(r.dexSeen, DEX_SEEN_ICON)
    out += iconLine(r.dexCaught, DEX_CAUGHT_ICON)

    return out
}

/**
 * 图鉴「遇见」图标 —— Cobblemon 图鉴那张**组合图的上半**（下半是「捕获」）。
 * ⚠ 直接引用 Cobblemon 自己的资源（不复制进我们这边）：模组本来就依赖它，客户端一定有。
 */
private val DEX_SEEN_ICON = LineIcon(
    Identifier.of("cobblemon", "textures/gui/pokedex/caught_seen_icon.png"),
    v = 0f, texH = 28,
)

/** 图鉴「捕获」图标 —— Cobblemon 图鉴的单张捕获图（详情页用的就是它） */
private val DEX_CAUGHT_ICON = LineIcon(
    Identifier.of("cobblemon", "textures/gui/pokedex/caught_icon.png"),
)

/**
 * **图标行**：行首是图标、右侧只有数值 —— 图鉴那两行专用
 * （2026-10-16 用户拍板：标签文字由图标替代，不再显示「图鉴遇见 / 图鉴捕捉」）。
 *
 * ⚠ 拿不到的（离线且还没记过快照）照旧显示「—」，**图标照画** ⇒ 行的位置稳定、不跳。
 */
private fun iconLine(value: Int?, icon: LineIcon): OverviewLine =
    OverviewLine(
        Text.literal(value?.toString() ?: noneValue()),
        icon = icon,
        // 图标行比普通行高（原尺寸图标塞不进 10px 的行距）—— 渲染端按它**逐行累加**高度
        height = OVERVIEW_ICON_ROW_HEIGHT,
    )

/**
 * 「标签：值」—— 冒号在**词条**里（中文全角 / 英文半角），别在代码里拼。
 *
 * @param color 传了就是**金额行**：数字与单位染这个色、行尾要补单位；不传 = 纯文字行（整行行色）
 */
private fun line(labelKey: String, value: String, color: Int? = null): OverviewLine {
    // ⚠ 金额行末尾留**一个空格**：货币单位是分开绘制的（`drawCurrencyUnit` 只画单位本身），
    //   这个空格由这里提供 —— 项目规约是「金额 + 一个空格 + 单位」，别去掉
    val shown = if (color != null) "$value " else value
    val valueText = if (color == null) Text.literal(shown)
    else Text.literal(shown).styled { it.withColor(color) }
    return OverviewLine(
        Text.translatable("cobblemarket.overview.line", Text.translatable(labelKey), valueText),
        money = color != null,
        color = color ?: OVERVIEW_GOLD,
    )
}

/**
 * **金额行**：显示走**缩写**（`9.8k` / `1.2M`，4 位以内原样），完整千分位存进 [OverviewLine.fullAmount]，
 * 由界面在悬停时展全 —— 照入口界面「全服累计成交额」的做法（那里也是缩写 + 悬停出完整值）。
 *
 * ⚠ 缩写规则复用 `formatPriceShortLong`：**负数的符号先摘出来再缩写**（它内部处理好了），
 *   别自己写一套。
 */
private fun moneyLine(labelKey: String, amount: Long, color: Int): OverviewLine =
    line(labelKey, formatPriceShortLong(amount), color)
        .let { OverviewLine(it.text, money = true, color = color, fullAmount = formatPriceLong(amount)) }

/** 值本身就是个带样式的 **Text**（如 § 色码逐字滚动的卡片名）—— 别先 `.string` 再包一层，样式会丢 */
private fun lineText(labelKey: String, value: Text): OverviewLine =
    OverviewLine(Text.translatable("cobblemarket.overview.line", Text.translatable(labelKey), value))

private fun group(name: String): OverviewLine =
    OverviewLine(
        Text.translatable("cobblemarket.overview.group.$name").formatted(Formatting.GOLD),
        isGroup = true
    )

/** 拿不到的值统一显示成「—」（离线且还没记过快照的余额 / 图鉴数）—— 悬停窗与 ID 卡共用 */
fun noneValue(): String = Text.translatable("cobblemarket.overview.value.none").string
