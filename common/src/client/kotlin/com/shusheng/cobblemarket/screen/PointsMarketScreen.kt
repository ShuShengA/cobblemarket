package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.client.settings.ServerSettings
import com.cobblemon.mod.common.pokemon.PokemonSizeCategory
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import com.shusheng.cobblemarket.client.PointMarketCache
import com.shusheng.cobblemarket.client.PointsShopCache
import com.shusheng.cobblemarket.client.formatPriceShortLong
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.client.playMarketSound
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.PointShopState
import com.shusheng.cobblemarket.currency.ShopEntry
import com.shusheng.cobblemarket.currency.ShopItemSpec
import com.shusheng.cobblemarket.currency.ShopPokemonSpec
import com.shusheng.cobblemarket.market.ItemRuleComponents
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.RedeemPointCodePayload
import com.shusheng.cobblemarket.network.RemovePointMarketEntryPayload
import com.shusheng.cobblemarket.network.RequestPointMarketPayload
import com.shusheng.cobblemarket.network.RequestPointsShopPayload
import com.shusheng.cobblemarket.network.SavePointMarketEntryPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.util.ItemNameText
import com.shusheng.cobblemarket.util.MoveText
import com.shusheng.cobblemarket.util.SpeciesText
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * **RP 市场**：两级结构里的中间层（入口界面 → 本界面 → 充值界面）。
 *
 * 本层放两样东西：
 * - **兑换RP**（常驻）：输入 / 粘贴兑换码 —— 国内外网站、发卡网、活动码、补偿码都走这条
 * - **购买RP**入口（B2.5b2 接入）：档位列表 → 收款码弹窗；仅当服主配了档位与收款码时显示
 *
 * 商品区（RP 商城）方案未定，本层先**留位置不渲染** —— 内容整体放在背景中偏下，
 * 上方那片留白就是它的位置。
 *
 * 背景渲染（方案 2026-09-17 拍板）：`points_market_background.png` **等比缩放 + 居中 + 四周留 4%**。
 * ⚠ **不用九宫格**：四条边的中段都有红色装饰条，九宫格拉伸会把它们拉长变形（越宽的屏拉得越长）。
 */
class PointsMarketScreen : Screen(
    Text.translatable("cobblemarket.points.market.title", CurrencyService.pointsTitleIcon())
) {

    private var codeField: TextFieldWidget? = null
    /** 输入框内容：界面 resize 会重建控件，靠它把玩家已经敲进去的码恢复回来 */
    private var pendingCodeText = ""

    // ── 商品区（精灵 / 物品 双 tab 网格）──
    private var currentTab = 0
    private var pokemonTabButton: NineSliceButton? = null
    private var itemTabButton: NineSliceButton? = null
    private var prevButton: NineSliceButton? = null
    private var nextButton: NineSliceButton? = null
    private var currentPage = 1
    /** 总页数：**服务端快照说了算**（按最大已填格子序号算，本地不自己推） */
    private val totalPages: Int get() = PointMarketCache.totalPages
    /** 鼠标所在格子索引；-1 = 不在任何格子上。悬停的格子会**放大绘制**（浮在邻居之上） */
    private var hoveredCell = -1

    // ── OP 添加 / 编辑窗口（2026-09-20 用户口述；**只有 OP 能开**，见 [isOperator]）──

    /** 正在编辑的格子**全局序号**（跨页递增，不是页内下标）；-1 = 窗口没开 */
    private var editSlot = -1

    /** 提交失败提示（窗口内、[EDIT_HINT_MS] 毫秒）：画在标题右侧的空位，见 [renderEditDialogBackground] */
    private var editHintMsg: Text? = null
    private var editHintUntil = 0L

    /**
     * 该格原本的完整内容（新增时为 null）。
     *
     * ⚠ 提交时**基于它 `copy()`**，只覆盖窗口接了的字段 —— 界面没接的高级项
     * （EV / 亲密度 / 昵称 / 证章 / 极巨化等级 / 物品组件…）必须原样保留，
     * 否则「什么都不改直接保存」会把这些悄悄抹掉（全量覆盖式表单的铁律，本项目有前科）。
     */
    private var editOriginal: ShopEntry? = null

    // 窗口控件引用（关闭时清空；resize 后靠它们恢复草稿）
    private var editSpeciesField: TextFieldWidget? = null
    private var editLevelField: TextFieldWidget? = null
    private var editPriceField: TextFieldWidget? = null
    private var editStockField: TextFieldWidget? = null
    private var editSpeciesPrev: NineSliceButton? = null
    private var editSpeciesNext: NineSliceButton? = null

    /** 清空搜索框（搜索框右侧第一枚，用户 2026-09-20 加）；与两枚箭头一起藏，见 [updateEditControlsVisibility] */
    private var editSpeciesClear: NineSliceButton? = null
    private var editConfirmButton: NineSliceButton? = null
    private var editCancelButton: NineSliceButton? = null
    private var editRemoveButton: NineSliceButton? = null

    /**
     * 开窗 / 换物种后**自动叫一声**的时刻（0 = 不用叫）。
     * 必须等渲染之后再播 —— `addFirstAnimation` 依赖 draw 里才建好的模型，见 [IconCryClick]。
     */
    private var editCryAt = 0L

    // 物种搜索（照搬黑名单那套：精确优先的候选 + 箭头切换 + 预览槽）
    private var editSpecies: Species? = null
    private var editSpeciesCandidates = listOf<Species>()
    private var editSpeciesIndex = 0
    private var editPreview: RenderablePokemon? = null
    private val editPreviewState = FloatingState()

    // ── 窗口草稿（字段与 ShopPokemonSpec 一一对应；提交时整份拼成 ShopEntry）──

    /** 形态 aspects（不含 `shiny` —— 闪光单独一栏管，免得两处打架） */
    private var editAspects = emptyList<String>()

    /**
     * 闪光概率的**输入框原文**（百分数，空 = 不闪）—— 它是闪光这一项的**唯一真值**，
     * 左边那枚按钮只是快捷档位（点一下把对应数值填进来）。
     * 支持小数（`0.5` = 千分之五），`100` 及以上按必闪处理（写进 aspects 的 `shiny`）。
     */
    private var editShinyText = ""
    private var editShinyField: TextFieldWidget? = null

    /** 性别：0 = 随机，1 = 雄，2 = 雌，3 = 无性别 */
    private var editGender = 0
    private var editNature = ""
    private var editAbility = ""
    private var editBall = ""

    /** 体型：0 = 随机，1..5 = XS/S/M/L/XL（提交时换算成 scale 倍率） */
    private var editSize = 0
    private var editAlpha = false
    private var editTera = ""
    private var editGmax = false
    private var editTradeable = true

    /** 携带物：**完整物品栈 NBT 的 SNBT 串**（与挂单 `heldItemNbt` 同口径）；null = 无 */
    private var editHeldItem: String? = null
    private var editHeldItemName = ""

    /** 保底 V 数（0 = 不保底；**只在六项个体值全空时生效**，与价格限制界面的口径一致） */
    private var editMinIvs = 0

    /**
     * 六项个体值 / 努力值的输入框原文（空 = 该项随机 / 0）。
     * ⚠ 个体值：**整组全空**时才回退到「保底 V 数」；任一项填了就按六项走（数据层两者互斥）。
     */
    private var editIvText = List(STAT_COUNT) { "" }
    private val editIvFields = mutableListOf<TextFieldWidget>()
    private var editEvText = List(STAT_COUNT) { "" }
    private val editEvFields = mutableListOf<TextFieldWidget>()

    /** 亲密度（空 = 默认）/ 携带物是否对他人可见 / 证章 id 列表（可挂多个） */
    private var editFriendshipText = ""
    private var editFriendshipField: TextFieldWidget? = null
    private var editHeldVisible = true
    private var editMarks = emptyList<String>()

    // 物品窗口选中的物品（id + 组件快照 + 显示名；显示名只是给人看的，不进存档）
    private var editItemId = ""
    private var editItemName = ""
    private var editItemComponents: NbtCompound? = null

    /** 四个技能槽的逗号串（空槽是空串，见 [MoveText]） */
    private var editMoves = ""

    /**
     * 展开中的选项列表种类（形态 / 性格 / 特性 / 球 / 太晶 / 技能 / 携带物 共用一套）；
     * null = 没有列表展开。展开时**窗口其余控件全部隐藏** —— 它们先于列表添加，
     * 不隐藏的话点击会被它们拦截、列表永远点不到（黑名单那儿踩过）。
     */
    private var optionListKind: String? = null

    /** 该字段的**全部**候选项（搜索词为过滤前的全集） */
    private var optionListAll: List<OptionItem> = emptyList()

    /** 过滤后的候选项 —— 渲染、点击、滚动全都用它 */
    private var optionListItems: List<OptionItem> = emptyList()
    private var optionListScroll = 0

    /** 列表的搜索框（**只在列表展开期间存在**）与搜索词；纯本地过滤，不发包 */
    private var optionSearchField: TextFieldWidget? = null
    private var optionSearchText = ""

    /**
     * 该列表是否**多选**（目前只有证章）。
     * 多选时点一行只切换勾选、**不关列表** —— 不然挂第二个章还得把按钮重新点开。
     */
    private var optionListMultiSelect = false

    /** 列表底部的「返回」按钮（关列表、回字段界面），随列表一起建/删 */
    private val optionListButtons = mutableListOf<NineSliceButton>()

    /**
     * 展开列表里的一行。
     *
     * [value] 是**游戏内的标识**（资源 id / 名字 / SNBT 串），[label] 只给人看；
     * [stack] 非空时行首画物品图标（球种、携带物这种「值本身就是一件物品」的字段）。
     * 图标在**开列表时预构建**，渲染时直接用 —— 每帧解析 SNBT 会掉帧。
     */
    private class OptionItem(
        val value: String,
        val label: String,
        val stack: ItemStack? = null,
        /**
         * 带样式的显示文本（**技能名要带属性色**）。
         * ⚠ 存成 [label] 那样的 String 会把样式丢干净 —— `displayName.string` 一转就没了
         * （2026-09-20 用户实测：技能列表里全是白字）。
         */
        val labelText: Text = Text.literal(label),
        /** 行首**贴图**图标（证章用，原图 32×32）；球种/携带物那种物品走 [stack] */
        val iconTexture: Identifier? = null
    )

    /**
     * 弹窗控件的**起始下标**：建弹窗时记下 `children().size`，之后新增的都是弹窗的。
     * 用途有两个 —— 重复开窗时清掉残留、列表展开时兜底隐藏（见 [updateEditControlsVisibility]）。
     */
    private var editWidgetBase = -1

    /** 字段按钮按 key 索引（刷新文案时逐个 setMessage；技能槽的 key 形如 `skill0`） */
    private val editButtons = mutableMapOf<String, NineSliceButton>()

    /**
     * 窗口内容的**滚动偏移**（屏幕放不下时才有值）。
     * 只有中间那坨字段网格随它动，价格/库存与按钮是**钉在窗口底部**的（不然够不着）。
     */
    private var editContentScroll = 0

    // 输入框原文：resize 会重建控件，不留下这几份就会清空（占位符那种小字丢得最明显）
    private var editSpeciesText = ""
    private var editLevelText = ""
    private var editPriceText = ""
    private var editStockText = ""

    /** 本次进界面是否已请求过商城快照（入口按钮的显隐要等它回来） */
    private var shopInfoRequested = false

    /** 上次请求商品快照的时刻（客户端侧限流，配合服务端 500ms 节流窗口，见 [tick]） */
    private var lastPageRequestAt = 0L

    /** 「购买 RP」入口（显隐由商城快照决定，见 [onShopInfo]） */
    private var buyEntryButton: NineSliceButton? = null

    /** 背景等比缩放后的矩形（居中） */
    private data class BgRect(val x: Int, val y: Int, val w: Int, val h: Int)

    /** 兑换结果提示（界面内、5 秒）：Text 原样保存，见 [onMarketResult] 的注释 */
    private var resultMsg: Text? = null
    private var resultColor = 0x55FF55
    private var resultUntil = 0L

    /** 「兑换记录」入口（见 init 的注释：位置随「购买 RP」是否可见而变，永不重建界面） */
    private var recordButton: NineSliceButton? = null

    /** 返回按钮与兑换按钮：**弹窗期间要连带隐藏**，所以存成字段（原先都是匿名的） */
    private var backButton: NineSliceButton? = null
    private var redeemButton: NineSliceButton? = null

    /**
     * 摆放「兑换记录」按钮：
     * - 「购买 RP」可见 → 贴在它**左侧**
     * - 不可见（没配档位）→ **顶上它的位置**
     *
     * ⚠ 用 `bgRect()` 现算而不是缓存 init 里的局部量：档位快照到达时只调这个函数，
     * 不能重建界面（会清掉兑换框里已输入的内容）。
     */
    fun layoutRecordButton() {
        val btn = recordButton ?: return
        val showBuy = PointsShopCache.tiers.isNotEmpty()
        // 「购买 RP」左缘；不显示购买入口时，记录按钮**顶上它的位置**（各自按自己的宽度贴右缘）
        val rightEdgeX = bgRect().x + bgRect().w - borderInset()
        btn.x = if (showBuy) rightEdgeX - BUY_ENTRY_W - ROW_GAP - RECORD_ENTRY_W
        else rightEdgeX - RECORD_ENTRY_W
    }

    private fun bgScale(): Float {
        val availW = width * (1f - MARGIN_RATIO * 2f)
        val availH = height * (1f - MARGIN_RATIO * 2f)
        return min(availW / TEX_W, availH / TEX_H)
    }

    private fun bgRect(): BgRect {
        val scale = bgScale()
        val w = (TEX_W * scale).roundToInt()
        val h = (TEX_H * scale).roundToInt()
        return BgRect((width - w) / 2, (height - h) / 2, w, h)
    }

    /** 背景图边框厚度（1280×720 原图约 78px）缩放后的实际值：贴边控件要避开它 */
    private fun borderInset(): Int = (TEX_BORDER * bgScale()).roundToInt()

    /**
     * **原图坐标（1280×720 口径）→ 当前缩放下的逻辑像素**。
     * 用户给的尺寸都是照原图说的（「与顶部 28 像素」），换算只走这一个口子，
     * 免得各处各写一遍缩放、改一个位置要满文件找。
     */
    private fun texPx(value: Int): Int = (value * bgScale()).roundToInt()

    override fun init() {
        super.init()
        val bg = bgRect()
        val inset = borderInset()
        val cx = bg.x + bg.w / 2

        // 返回按钮：**底部居中**，底边距背景底 BACK_BOTTOM（原图口径）
        backButton = NineSliceButton(
            cx - BACK_W / 2, backButtonY(bg), BACK_W, BACK_H,
            Text.literal(""),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        // 兑换区：**内容区左上角**（不占中部的商品区；没有小标题，输入框本身就是说明）
        // 商城快照（档位 / 有没有收款码）决定右上角那个入口显不显示；没请求过就请求一次
        //（init 会被 resize 反复调用，用标志挡住重复请求）
        if (!shopInfoRequested) {
            sendToServer(RequestPointsShopPayload(true))
            shopInfoRequested = true
        }

        val rowY = redeemRowY(bg)
        val fieldX = bg.x + inset
        val buyBtnX = bg.x + bg.w - inset - BUY_ENTRY_W
        // ⚠ 兑换行要挤下**四组**东西（输入框 / 兑换 / 双 tab / 购买），而背景是等比缩放、
        //   控件是固定逻辑像素 —— GUI scale 调大时逻辑分辨率变小（1080p 用 Auto 时只有 640×360，
        //   scale 更大时 480×270），背景缩了控件没缩就会挤成一团。
        //   所以输入框宽度按**剩余空间**算，压到下限为止。
        val tabGroupW = TAB_W * 2 + TAB_GAP
        val rowNeed = REDEEM_BUTTON_W + ROW_GAP + tabGroupW + ROW_GAP + RECORD_ENTRY_W +
            ROW_GAP + BUY_ENTRY_W + ROW_GAP * 2
        val fieldW = minOf(REDEEM_FIELD_W, bg.w - 2 * inset - rowNeed).coerceAtLeast(MIN_REDEEM_FIELD_W)

        // 兑换码输入框（重建后恢复输入；上限按外部码带分隔符的 39 字符留余量）
        codeField = TextFieldWidget(textRenderer, fieldX, rowY, fieldW, ROW_H, Text.literal(""))
        codeField?.setMaxLength(MAX_CODE_INPUT)
        // ⚠ 占位提示**按输入框实际宽度挑**：原版画 placeholder **不做截断也不裁剪**，
        //   GUI scale 自动那一档输入框只有 24px 宽，长的「输入兑换码」会一路画到右边的
        //   「兑换」按钮上（2026-09-19 用户实测）。两条都放不下就干脆不显示 —— 那种宽度下
        //   任何字样都会溢出去（按钮本身就说明了这里是干什么的）。
        codeField?.setPlaceholder(
            listOf(
                "cobblemarket.points.market.code_hint",
                "cobblemarket.points.market.code_hint_short"
            ).asSequence()
                .map { Text.translatable(it) }
                .firstOrNull { textRenderer.getWidth(it) <= fieldW - 8 }   // 8 = 左右各 4px 内边距
        )
        codeField?.setChangedListener { pendingCodeText = it }
        codeField?.text = pendingCodeText
        addSelectableChild(codeField)
        addDrawableChild(codeField)

        // 兑换按钮：输入框右侧 4px（按钮间距规则）
        redeemButton = NineSliceButton(
            fieldX + fieldW + ROW_GAP, rowY, REDEEM_BUTTON_W, ROW_H,
            Text.translatable("cobblemarket.points.market.redeem_button"),
            { doRedeem() }
        )
        addDrawableChild(redeemButton)

        // 「购买 RP」入口：**与左边的兑换码输入框左右对称**（离各自一侧边框的距离都是 inset、
        // 同一水平线）—— 用户 2026-09-18 指出原来放在标题那一行"太右上"。
        // 只判档位、**不判收款码**：没配收款码时入口照样给，点进去弹窗里会提示缺什么（藏起来
        // 会让服主以为功能坏了）。按钮**总是建出来、靠 visible 开关**：快照到达时只改一个标志，
        // 重建整个界面会清掉玩家已经敲进兑换框的内容。
        val buyBtn = NineSliceButton(
            buyBtnX, rowY,
            BUY_ENTRY_W, ROW_H,
            Text.translatable("cobblemarket.points.recharge.entry", CurrencyService.pointsIcon()),
            { client?.setScreen(PointsRechargeScreen()) }
        )
        buyBtn.visible = PointsShopCache.tiers.isNotEmpty()
        buyEntryButton = buyBtn
        addDrawableChild(buyBtn)

        // 「兑换记录」按钮（用户 2026-09-19 定）：**放在「购买 RP」左侧**；购买入口不可见时
        // （没配档位）**顶上它的位置**。与购买入口同理**总是建出来、只改 x/visible** ——
        // 重建界面会清掉玩家已经敲进兑换框的内容。
        val recordBtn = NineSliceButton(
            buyBtnX - ROW_GAP - RECORD_ENTRY_W, rowY, RECORD_ENTRY_W, ROW_H,
            Text.translatable("cobblemarket.points.market.record_entry"),
            { client?.setScreen(PointsRecordScreen()) }
        )
        recordButton = recordBtn
        addDrawableChild(recordBtn)
        layoutRecordButton()

        // 精灵 / 物品 tab：与兑换行同一水平线，**在「兑换按钮右侧 ~ 购买按钮左侧」这段空档里居中**
        // （用户 2026-09-18 定的是"居中"，但死盯背景正中在小分辨率下会压到左边的兑换按钮上 ——
        //  改成居中于剩余空间后，大分辨率下位置与原来一致，小分辨率下自动让位）
        val tabMinLeft = fieldX + fieldW + ROW_GAP + REDEEM_BUTTON_W + ROW_GAP
        // 右边界要给**两个**按钮让位（购买 RP + 它左侧的兑换记录），各自按自己的宽度算
        val tabMaxLeft = buyBtnX - ROW_GAP - RECORD_ENTRY_W - ROW_GAP - tabGroupW
        val tabLeftX =
            if (tabMinLeft <= tabMaxLeft) (cx - tabGroupW / 2).coerceIn(tabMinLeft, tabMaxLeft)
            // 兜底：极端小分辨率下这一行真的挤不下（按钮都已按文字宽度收到最小），
            // 只能让 tab 紧跟兑换按钮 —— 此时它会压到「兑换记录」上，属于「看得见但挤」，
            // 再往下就是字都看不清了，不如保持这个下限。
            else tabMinLeft
        pokemonTabButton = NineSliceButton(tabLeftX, rowY, TAB_W, TAB_H, Text.literal(""), { switchTab(0) })
        itemTabButton = NineSliceButton(
            tabLeftX + TAB_W + TAB_GAP, rowY, TAB_W, TAB_H, Text.literal(""), { switchTab(1) }
        )
        addDrawableChild(pokemonTabButton)
        addDrawableChild(itemTabButton)
        updateTabButtons()

        // 翻页按钮：与返回按钮同一行。**三个按钮按背景宽度三等分、各自在所属等分里居中**
        // （用户 2026-09-18）—— 返回按钮因此仍在正中间，位置不变
        val pageBtnY = backButtonY(bg) - 2
        val third = bg.w / 3
        prevButton = NineSliceButton(
            bg.x + third / 2 - PAGE_BTN_W / 2, pageBtnY, PAGE_BTN_W, PAGE_BTN_H,
            Text.literal(""),
            { prevPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/previous.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.prev")
        )
        nextButton = NineSliceButton(
            bg.x + bg.w - third / 2 - PAGE_BTN_W / 2, pageBtnY, PAGE_BTN_W, PAGE_BTN_H,
            Text.literal(""),
            { nextPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/next.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.next")
        )
        addDrawableChild(prevButton)
        addDrawableChild(nextButton)

        // ⚠ resize 会把控件全部清掉（原版 `Screen.resize` → `clearChildren` + `init`）——
        //   窗口开着就按草稿重建一份，别让它凭空消失、主控件又冒回来
        if (editSlot >= 0) {
            hideMainControls()
            buildEditDialog()
        }
    }

    // ── 位置（**全部走逻辑像素**）──
    // ⚠ 控件尺寸（行高 16）是逻辑像素，而用户给的尺寸（边框 78、标题距顶 28）是原图口径 ——
    // 两者不能混着算：2026-09-18 把「输入框底 = 90 + 16」当真，结果分割线横穿了输入框。

    /** 兑换行（输入框 / 兑换按钮 / 两个 tab）顶边：内容区左上角再下移 [ROW_TOP_OFFSET] */
    private fun redeemRowY(bg: BgRect): Int = bg.y + borderInset() + ROW_TOP_OFFSET

    /** 返回按钮顶边：底边距背景底 BACK_BOTTOM（原图口径） */
    private fun backButtonY(bg: BgRect): Int = bg.y + bg.h - texPx(BACK_BOTTOM) - BACK_H

    /**
     * 上分割线 y：**与兑换行解耦**（写在原兑换行位置的下方，不含 [ROW_TOP_OFFSET]）。
     * 这样把兑换行往下挪时分割线不动，两者的间距随之收窄到 `DIVIDER_GAP − ROW_TOP_OFFSET`。
     */
    private fun dividerTopY(bg: BgRect): Int = bg.y + borderInset() + ROW_H + DIVIDER_GAP

    /** 下分割线 y：返回按钮顶边上方 DIVIDER_GAP */
    private fun dividerBottomY(bg: BgRect): Int = backButtonY(bg) - DIVIDER_GAP

    /**
     * **点击空白失焦**（含 TextFieldWidget 的新界面必做，见 memory `feedback_gui_dialog`）：
     * 不做的话点空白退不出输入状态，E 键会被输入框吃掉、关不掉界面。
     * 先记「点之前焦点在不在输入框」再交给 super —— 让点击先落到控件上（点输入框内部不能误清）。
     */
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 窗口开着：事件**只给窗口**（格子与主界面一律不响应；下层控件已 visible=false）
        if (editSlot >= 0) return handleEditDialogClick(mouseX, mouseY, button)

        val wasInInput = focused != null && focused === codeField
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && codeField?.isMouseOver(mouseX, mouseY) != true) {
            focused = null
        }
        // 点格子：**只有 OP 有反应**（普通玩家点有内容的格子该开购买窗，那一块还没做）
        if (!result && button == 0) {
            val cell = cellAt(mouseX, mouseY, gridLayout(bgRect()))
            if (cell >= 0 && isOperator()) {
                openEditDialog(globalSlot(cell))
                return true
            }
        }
        return result
    }

    /** 窗口内的点击：控件优先 → 列表行 → 点空白失焦 → 点预览图叫一声 */
    private fun handleEditDialogClick(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val focusWasInput = isEditInputFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        // 展开的列表：行是**自绘**的（要画物品图标），命中判定得自己来
        if (optionListKind != null) {
            // ⚠ 搜索框**优先**：点在它上面就直接放行，不再往列表行上判 ——
            //   列表行的命中只看坐标，压在搜索框底下的那行会被误伤（点到输入框却选中了行）
            if (optionSearchField?.isMouseOver(mouseX, mouseY) == true) return true
            if (!result && button == 0) clickOptionRow(mouseX, mouseY)
            focused = null
            return true
        }
        // 点在输入框之外 → 手动失焦（否则 E 键被输入框吃掉、Esc 也关不掉窗口）
        if (focusWasInput && !isOverEditInput(mouseX, mouseY)) focused = null
        // 点预览槽 = 点精灵图标（既有标配：点一下就随机叫一声）
        if (button == 0 && isPokemonSlot() && overEditPreview(mouseX, mouseY)) {
            editPreview?.let { IconCryClick.play(editPreviewState, it) }
        }
        return result
    }

    /** 鼠标在第几个**可见行**上（-1 = 不在任何行上）—— 渲染的悬停高亮与点击判定共用它 */
    private fun hoveredOptionRow(mouseX: Double, mouseY: Double): Int {
        for (i in 0 until optionListRows()) {
            val r = optionRowRect(i)
            if (mouseX >= r.x && mouseX < r.x + r.w && mouseY >= r.y && mouseY < r.y + r.h) return i
        }
        return -1
    }

    /** 点展开列表的某一行；命中就按单选 / 多选处理 */
    private fun clickOptionRow(mouseX: Double, mouseY: Double): Boolean {
        val i = hoveredOptionRow(mouseX, mouseY)
        if (i < 0) return false
        optionListItems.getOrNull(optionListScroll + i)?.let { item ->
            // 多选（证章）只切勾选、不关列表；单选选完就走
            if (optionListMultiSelect) toggleMultiOption(item.value) else selectOption(item.value)
        }
        return true
    }

    override fun mouseScrolled(
        mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double
    ): Boolean {
        // 展开的选项列表：滚轮翻页（技能列表可能几百项）
        if (optionListKind != null) {
            val max = (optionListItems.size - optionListRows()).coerceAtLeast(0)
            if (max > 0) optionListScroll = (optionListScroll - verticalAmount.toInt()).coerceIn(0, max)
            return true
        }
        // 窗口内容滚动（屏幕矮、字段网格装不下时才有效果）
        if (editSlot >= 0 && editMaxScroll() > 0) {
            editContentScroll = (editContentScroll - verticalAmount.toInt()).coerceIn(0, editMaxScroll())
            applyEditScroll()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // Esc：窗口开着就**先收窗口**（列表展开时先收列表），别一按就退出整个界面
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && editSlot >= 0) {
            if (optionListKind != null) closeOptionList() else closeEditDialog()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    /** 窗口里**全部**输入框（固定区三个 + 滚动区那一串）—— 失焦判定两处共用，别各列一份 */
    private fun allEditFields(): List<TextFieldWidget?> =
        listOf(editSpeciesField, editPriceField, editStockField) + scrollableFields().map { it.first }

    private fun isEditInputFocused(): Boolean = allEditFields().any { it != null && focused === it }

    private fun isOverEditInput(mouseX: Double, mouseY: Double): Boolean =
        allEditFields().any { it?.isMouseOver(mouseX, mouseY) == true }

    private fun overEditPreview(mouseX: Double, mouseY: Double): Boolean {
        if (editSlot < 0) return false
        // ⚠ 坐标**只能**从 [editPreviewSlotX] / [editPreviewSlotY] 来（槽位在预览区里是居中的）——
        //   自己拿 [editPreviewX] / [editPreviewY] 算就漏掉居中偏移，判定框整块偏左（见那两个函数的注释）
        val x = editPreviewSlotX()
        val y = editPreviewSlotY()
        val size = editPreviewSize()
        return mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size
    }

    /** 悬停格子：只在鼠标移动时重算（每帧算一遍没必要，格子是静态的） */
    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        super.mouseMoved(mouseX, mouseY)
        // 弹窗开着时不认悬停：鼠标在弹窗上滑过时，底下那格跟着放大闪来闪去没意义
        hoveredCell = if (freezeHover()) -1 else cellAt(mouseX, mouseY, gridLayout(bgRect()))
    }

    /**
     * 商城快照的**补发**（照市场列表的做法）。
     *
     * ⚠ 服务端的列表请求有 500ms 节流，窗口内的请求被**静默丢弃** —— 连点翻页会让列表一直空着，
     * 所以这里盯着「当前页的快照到没到」，没到就按节流间隔重发一次。
     */
    override fun tick() {
        super.tick()
        if (editSlot >= 0) return
        val wantPage = currentPage - 1
        if (PointMarketCache.loaded && PointMarketCache.page == wantPage) return
        val now = System.currentTimeMillis()
        if (now - lastPageRequestAt < PAGE_REQUEST_INTERVAL_MS) return
        lastPageRequestAt = now
        sendToServer(RequestPointMarketPayload(wantPage))
    }

    // ── 商品区：tab / 翻页 / 网格几何 ──

    private fun switchTab(tab: Int) {
        if (currentTab == tab) return
        currentTab = tab
        // 切 tab 重置翻页：换了商品集合还停在第 3 页，看着像空列表
        currentPage = 1
        updateTabButtons()
    }

    private fun updateTabButtons() {
        val pokemonLabel = Text.translatable("cobblemarket.points.market.tab_pokemon").string
        val itemLabel = Text.translatable("cobblemarket.points.market.tab_item").string
        // 选中态用 selectedText（照 PriceLimitScreen 的 tab）——按钮底色图只有一段状态，
        // 靠文字高亮区分选中
        pokemonTabButton?.setMessage(
            if (currentTab == 0) com.shusheng.cobblemarket.util.TextUtil.selectedText(pokemonLabel)
            else Text.literal(pokemonLabel)
        )
        itemTabButton?.setMessage(
            if (currentTab == 1) com.shusheng.cobblemarket.util.TextUtil.selectedText(itemLabel)
            else Text.literal(itemLabel)
        )
    }

    private fun prevPage() {
        if (currentPage <= 1) return
        currentPage--
        // 换了页要立刻拉新快照（把节流计时清零，下一个 tick 就发，不用干等 500ms）
        lastPageRequestAt = 0L
    }

    private fun nextPage() {
        if (currentPage >= totalPages) return
        currentPage++
        lastPageRequestAt = 0L
    }

    /** 网格排布：行列固定，格子尺寸与间隔按可用空间算出来，整体居中 */
    private class GridLayout(
        val startX: Int, val startY: Int,
        val cols: Int, val rows: Int,
        val cell: Int, val gap: Int
    ) {
        fun cellX(index: Int): Int = startX + (index % cols) * (cell + gap)
        fun cellY(index: Int): Int = startY + (index / cols) * (cell + gap)
    }

    private fun gridLayout(bg: BgRect): GridLayout {
        // 跨度与两条分割线一致，视觉上才对得齐
        val left = bg.x + texPx(DIVIDER_BORDER_X) - DIVIDER_EXTEND
        val right = bg.x + bg.w - texPx(DIVIDER_BORDER_X) + DIVIDER_EXTEND
        val top = dividerTopY(bg) + GRID_PADDING
        val bottom = dividerBottomY(bg) - GRID_PADDING
        val availW = right - left
        val availH = bottom - top
        val byW = availW / (GRID_COLS + (GRID_COLS - 1) * GRID_GAP_RATIO)
        val byH = availH / (GRID_ROWS + (GRID_ROWS - 1) * GRID_GAP_RATIO)
        // 格子取哪个值见 [GRID_W_ROWS_TRADE_RATIO]：大分辨率按 [GRID_ROWS] 行算（保住每页 32 格），
        // 小分辨率按宽度铺满、行数自适应（不然格子被高度压得很小、左右白空一大片）
        val cell = (if (byW >= byH * GRID_W_ROWS_TRADE_RATIO) byW else byH).toInt().coerceAtLeast(16)
        val gap = (cell * GRID_GAP_RATIO).toInt()
        // 能放几行：rows × cell + (rows−1) × gap ≤ availH
        val rows = ((availH + gap) / (cell + gap)).coerceAtLeast(1)
        val gridW = GRID_COLS * cell + (GRID_COLS - 1) * gap
        val gridH = rows * cell + (rows - 1) * gap
        return GridLayout(
            startX = left + (availW - gridW) / 2,
            startY = top + (availH - gridH) / 2,
            cols = GRID_COLS,
            rows = rows,
            cell = cell,
            gap = gap
        )
    }

    // ── OP 添加 / 编辑窗口 ──

    /** 商城上下架**只认 OP**（不下放任何权限节点，同 `points give` 的印钞权口径） */
    private fun isOperator(): Boolean = client?.player?.hasPermissionLevel(2) == true

    /** 鼠标落在哪个格子上（**页内下标**）；-1 = 不在任何格子上 */
    private fun cellAt(mouseX: Double, mouseY: Double, layout: GridLayout): Int {
        for (i in 0 until layout.cols * layout.rows) {
            val x = layout.cellX(i)
            val y = layout.cellY(i)
            if (mouseX >= x && mouseX < x + layout.cell && mouseY >= y && mouseY < y + layout.cell) return i
        }
        return -1
    }

    /** 页内下标 → **全局序号**（数据层按全局序号存：第 1 页 = 0~31、第 2 页 = 32~63…） */
    private fun globalSlot(pageCellIndex: Int): Int =
        (currentPage - 1) * PointShopState.POINTS_SHOP_PAGE_SIZE + pageCellIndex

    /**
     * 窗口宽度：**跟着 RP 市场背景的宽度**（用户 2026-09-20 要的「和背景一样大」）。
     *
     * ⚠ 只有宽度跟着背景，高度不能 —— 自动 GUI 下逻辑屏才 270 高、背景本身也才 248，
     * 照背景做反而更矮。所以是**宽着排**：宽一点就多排一列，行数自然掉下来（12 行 → 7~8 行）。
     */
    private fun editDialogW(): Int =
        minOf(EDIT_DIALOG_MAX_W, (bgRect().w * 0.96f).roundToInt()).coerceAtLeast(EDIT_DIALOG_MIN_W)

    private fun editDialogX(): Int = (width - editDialogW()) / 2

    /**
     * 一行几列：按窗口宽度算（列宽不小于 [EDIT_MIN_COL_W]）。
     * 大分辨率下排 5 列、小分辨率（GUI scale 调大）自动退回 3 列，那时才轮到滚动。
     */
    /**
     * 窗口横向一共分 **6 格**（2026-09-20 用户定的新布局）：
     * **左 3 格**放字段（每行 3 个）、**右 3 格**给预览图与个体/努力值输入框。
     * 窗口总宽不变，所以每格比原来 5 列时略窄一点。
     */
    private fun editCols(): Int = EDIT_COLS

    /** 列宽：扣掉左右内边距与列间隔后平分 */
    private fun editColW(): Int =
        (editDialogW() - EDIT_PAD * 2 - (editCols() - 1) * EDIT_COL_GAP) / editCols()

    /**
     * 字段的**排列顺序**（流式：从左到右、从上到下填格子）。
     * 加字段、挪位置只改这一张表 —— 建控件、滚动、可见性、命中判定全都按它算。
     */
    private fun editFieldOrder(): List<String> = buildList {
        // 闪光拆成一格按钮 + 一格概率框（挨着，视觉上是一组）
        // **左 3 格**的 18 个字段，每行 3 个（右边 3 格留给预览图与个体/努力值输入框）。
        // 顺序就是用户 2026-09-20 定的那张表。
        listOf(
            KEY_SHINY, KEY_SHINY_CHANCE, KEY_FORM,
            KEY_LEVEL, KEY_GENDER, KEY_ABILITY,
            KEY_NATURE, KEY_BALL, KEY_SIZE,
            KEY_ALPHA, KEY_GMAX, KEY_TERA,
            KEY_TRADEABLE, KEY_HELD, KEY_HELD_VISIBLE,
            KEY_FRIENDSHIP, KEY_MARKS, KEY_MIN_IVS
        ).forEachIndexed { i, key ->
            add(key)
            if (i % 3 == 2) add(FIELD_BREAK)   // 一行满了就换行，右 3 格留空
        }
        // 六项个体值 / 努力值、四个技能槽**都不在这张表里** —— 它们有各自的固定区
        // （前两者在右侧区、技能在最后一行居中），位置见 [posOf]
    }

    /** 六项个体值 / 努力值在右侧区的位置：`iv0`..`iv5` → 0..5，`ev0`..`ev5` → 6..11 */
    private fun statIndexOf(key: String): Int? = when {
        key.startsWith(KEY_IV) -> key.removePrefix(KEY_IV).toIntOrNull()
        key.startsWith(KEY_EV) -> key.removePrefix(KEY_EV).toIntOrNull()?.plus(STAT_COUNT)
        else -> null
    }

    /**
     * 字段 key → **(行, 列)** —— **一切位置都从这里来**（建控件、滚动、可见性、渲染都走它）。
     * 散在各处各算一份必然错位。
     *
     * 三类字段各自的归属：
     * - **个体值 / 努力值**：右侧 3 格 × 4 行（IV 两行在上、EV 两行在下），见 [EDIT_ROW_STAT]
     * - **技能槽**：最后一行、6 格居中（左右各留一格的边距）
     * - **其余**：按 [editFieldOrder] 流式排在左 3 格
     */
    private fun posOf(key: String): Pair<Int, Int> {
        if (key.startsWith(KEY_SKILL)) {
            val slot = key.removePrefix(KEY_SKILL).toIntOrNull() ?: 0
            // 技能独立成行（在字段网格下面一行），6 格里居中：左右各留一格
            return skillRow() to (1 + slot)
        }
        statIndexOf(key)?.let { idx ->
            // 个体值 / 努力值：**放在左侧那 6 行字段的下面**（用户 2026-09-20 调整过位置）——
            // 一行 6 个：IV 占一行、EV 占一行
            return (fieldRowCount() + idx / STAT_COUNT) to (idx % STAT_COUNT)
        }
        ensureFieldLayout()
        return fieldLayout[key] ?: (0 to 0)
    }

    // 布局缓存：渲染每帧都要查行号，按顺序表现算是白烧；**列数变了才重算**
    private var fieldLayout: Map<String, Pair<Int, Int>> = emptyMap()
    private var fieldLayoutCols = -1
    private var fieldLayoutRows = 0

    private fun ensureFieldLayout() {
        val cols = editCols()
        if (cols == fieldLayoutCols) return
        val map = mutableMapOf<String, Pair<Int, Int>>()
        var row = 0
        var col = 0
        editFieldOrder().forEach { key ->
            if (key == FIELD_BREAK) {
                row++
                col = 0
                return@forEach
            }
            map[key] = row to col
            col++
            if (col >= cols) {
                col = 0
                row++
            }
        }
        fieldLayout = map
        fieldLayoutCols = cols
        fieldLayoutRows = row + if (col > 0) 1 else 0
    }

    /** 字段在网格里的行 / 列（流式排布 + 换行标记，唯一口径） */
    private fun editRowOf(key: String): Int = posOf(key).first

    private fun editColOf(key: String): Int = posOf(key).second

    /** 左侧字段占的行数（0 .. fieldLayoutRows-1） */
    private fun fieldRowCount(): Int {
        ensureFieldLayout()
        return fieldLayoutRows
    }

    /** **技能那一行**的行号（在字段网格与个体/努力值两行之下）—— 单独拆出来，避免和总行数循环依赖 */
    private fun skillRow(): Int = fieldRowCount() + 2

    /** 内容一共几行：字段 6 行 + 个体值 1 行 + 努力值 1 行 + 技能 1 行（滚动上限靠它算） */
    private fun editRowCount(): Int = fieldRowCount() + 3

    /**
     * 窗口高度：设计高见 [EDIT_DIALOG_H]（262），屏幕放不下就**按屏幕钳制**。
     *
     * ⚠ 自动 GUI 尺寸下逻辑屏高只有 270（1080p），设计高必然顶出屏幕 ——
     * 所以下面三段各自定位：**上固定**（标题 + 物种行）、**中间可滚动**（字段网格）、
     * **下固定**（价格/库存 + 按钮，钉在窗口底部）。这一条是项目里连踩多次的老坑。
     */
    private fun editDialogHeight(): Int = minOf(EDIT_DIALOG_H, height - 8)

    /** 窗口顶边：竖直居中 */
    private fun editDialogY(): Int = ((height - editDialogHeight()) / 2).coerceAtLeast(4)

    /** 某列的左缘（列宽随窗口宽度算，见 [editColW]） */
    private fun editColX(col: Int): Int = editDialogX() + EDIT_PAD + col * (editColW() + EDIT_COL_GAP)

    /**
     * 窗口内容的**右缘**：取最后一列字段按钮的右边缘。
     *
     * ⚠ 别用「窗口右缘 − 内边距」—— 列宽是整数除法（余数丢掉），两种算法会差几像素，
     * 顶部的箭头、「1/3」计数和右上角的下架按钮就都跟下面的字段按钮错开（2026-09-20 用户指出）。
     */
    private fun editRowRightX(): Int = editColX(editCols() - 1) + editColW()

    /**
     * 「下架」按钮的**顶边**：让它**距顶 = 距右**（用户 2026-09-20 定）。
     *
     * 原来死钉在窗口顶 +4，而右边到背景缘还空着 10~15px（列宽是整数除法，这个余量随窗口宽度浮动，
     * 见 [editRowRightX]）—— 看着就是「贴着上边、右边却留一条缝」。现在两边取**同一个数值**
     * （=`editRowRightX` 到背景右缘的距离），窗口怎么变都对称。
     */
    private fun editRemoveY(): Int =
        editDialogY() + (editDialogX() + editDialogW() - editRowRightX())

    /**
     * 物种行右端：**对齐左 3 列的右缘**（用户 2026-09-20 定）——
     * 右边那 3 格是预览图和个体值的位置，物种行不该伸过去。
     */
    private fun editSpeciesRightX(): Int = editColX(2) + editColW()

    /**
     * 物种搜索框的**右缘**：对齐「闪光概率」输入框的右边缘（用户 2026-09-20 定）。
     *
     * 右侧余下的地方（[editSpeciesRightX] 之前）正好排「清空 + 两枚候选箭头 + 1/3 计数」——
     * 三者与物种行右缘对齐，所以缩短搜索框腾出的空间刚好够，箭头与计数**原地不动**。
     */
    private fun editSpeciesFieldRightX(): Int = editColX(1) + editColW()

    /** 物种输入框宽度：从左内边距到 [editSpeciesFieldRightX] */
    private fun editSpeciesFieldW(): Int = editSpeciesFieldRightX() - (editDialogX() + EDIT_PAD)

    /**
     * 预览图区：**右侧 3 格 × 上 2 行**（用户 2026-09-20 定的新布局 —— 原来它居中在标题下方，
     * 而右边「性格」以上那两块一直空着，正好把预览放大放过去）。
     */
    private fun editPreviewX(): Int = editColX(3)
    private fun editPreviewY(): Int = editRowY(0)
    private fun editPreviewW(): Int = editColW() * 3 + EDIT_COL_GAP * 2
    // 预览图占**右侧整块**：个体/努力值挪到下面去了，右侧从顶上一直空到「亲密度」行
    private fun editPreviewH(): Int = EDIT_ROW_STEP * fieldRowCount() - (EDIT_ROW_STEP - EDIT_ROW_H)

    /** 预览图的绘制边长（正方形，取区域里短的那边） */
    private fun editPreviewSize(): Int = minOf(editPreviewW(), editPreviewH())

    /**
     * 预览槽**实际画在哪儿** —— 正方形，**居中**在右侧那块预览区里（区域通常比槽宽得多）。
     *
     * ⚠ 渲染与点击判定（[overEditPreview]）**必须共用这两个**：直接拿 [editPreviewX] / [editPreviewY]
     * 当槽位左上角会整体偏左半个差值（宽屏下 70 多像素），点图标就没反应（2026-09-20 用户报，
     * 上回只修了 Y 的基准、没带上居中偏移，于是又犯一次）。
     */
    private fun editPreviewSlotX(): Int = editPreviewX() + (editPreviewW() - editPreviewSize()) / 2

    private fun editPreviewSlotY(): Int = editPreviewY() + (editPreviewH() - editPreviewSize()) / 2

    /**
     * 预览大图的**裁剪框**（用户 2026-09-20 定）：允许精灵长出槽位 ——
     * 高的（伽勒尔椰蛋树这类）能一直伸到弹窗顶部，横向也撑得出去；**下方不动**（脚踩在槽底）。
     *
     * ⚠ **只有这个界面的预览大图用** —— 行内小图标、悬停窗、商城格子仍旧吃
     * [PokemonIconRenderer] 默认的「外扩 4px」，`clip` 传 null 就是那套。
     *
     * | 边 | 位置 |
     * |---|---|
     * | 左 | 形态那一列按钮的右缘（再往左就盖到字段按钮上了） |
     * | 上 | 弹窗背景**顶部边框内** [PREVIEW_CLIP_MARGIN] px |
     * | 右 | 弹窗背景**右边框内** [PREVIEW_CLIP_MARGIN] px |
     * | 下 | 槽底外扩 4px（与默认余量同口径） |
     *
     * 上 / 右**都扣掉边框宽**（`NINE_SLICE_BORDER`）—— 两条边口径一致，不偏不倚（用户 2026-09-20 拍板）。
     */
    private fun editPreviewClip(slotY: Int, slotSize: Int): IntArray = intArrayOf(
        editSpeciesRightX(),
        editDialogY() + NINE_SLICE_BORDER + PREVIEW_CLIP_MARGIN,
        editDialogX() + editDialogW() - NINE_SLICE_BORDER - PREVIEW_CLIP_MARGIN,
        slotY + slotSize + 4
    )

    /** 清空按钮：紧贴搜索框右侧（留 4px，与「输入框→相邻控件」的既有口径一致） */
    private fun editClearX(): Int = editSpeciesFieldRightX() + 4

    /** 两枚候选箭头跟着清空按钮排下去（各自间隔 2px） */
    private fun editArrowX(): Int = editClearX() + SPECIES_CLEAR_W + 2
    private fun editArrow2X(): Int = editArrowX() + EDIT_ARROW_W + 2

    /** 中间滚动区的高度（字段网格能露出来的部分） */
    private fun editScrollViewHeight(): Int =
        (editDialogHeight() - EDIT_ROWS_TOP - EDIT_BOTTOM_BLOCK).coerceAtLeast(EDIT_ROW_STEP)

    /** 滚动上限（物品窗口没有字段网格，行数为 0，自然就不出现滚动空间） */
    private fun editMaxScroll(): Int =
        (editRowCount() * EDIT_ROW_STEP - editScrollViewHeight()).coerceAtLeast(0)

    /** 第 [row] 行（两列字段）的顶边 —— **建控件与渲染共用这一个**，滚动偏移也在这里生效 */
    private fun editRowY(row: Int): Int =
        editDialogY() + EDIT_ROWS_TOP + row * EDIT_ROW_STEP - editContentScroll

    /** 价格 / 库存行的顶边（钉在底部，不随滚动移动） */
    private fun editPriceRowY(): Int = editDialogY() + editDialogHeight() - EDIT_BOTTOM_BLOCK

    /**
     * 价格 / 库存这一组（「标签 + 输入框」× 2，横跨两格）的**总宽**与**左缘**。
     *
     * 整组在窗口内容区里**水平居中**（用户 2026-09-20）—— 原来它俩贴着左边第 0、1 格，
     * 而下面那行按钮是居中的，上下两行看着各偏一边；居中后共用同一条中轴。
     *
     * ⚠ 建控件（[buildEditDialog]）与画标签（[renderEditDialogBackground]）**两处都走这里**，
     *   各算一份必然错开（本项目连踩多次的老坑）。
     */
    private fun editPriceGroupW(): Int = editColW() * 2 + EDIT_COL_GAP

    private fun editPriceGroupX(): Int =
        editDialogX() + EDIT_PAD + (editDialogW() - EDIT_PAD * 2 - editPriceGroupW()) / 2

    /** 底部按钮行的顶边 */
    private fun editButtonRowY(): Int = editDialogY() + editDialogHeight() - EDIT_BOTTOM_BLOCK + 18

    /** 某一行当前露没露在滚动区里（整行判断：半截的行控件本身没法裁剪，只能整体藏） */
    private fun editRowVisible(row: Int): Boolean {
        val top = editRowY(row)
        val viewTop = editDialogY() + EDIT_ROWS_TOP
        return top >= viewTop - 1 && top + EDIT_ROW_H <= viewTop + editScrollViewHeight() + 1
    }

    private fun isPokemonSlot(): Boolean = currentTab == 0

    /**
     * 点格子：OP 点空格 = 添加、点有内容的格 = 编辑。
     * 普通玩家点有内容的格 = 购买窗口（**尚未实现**）；点空格无反应。
     */
    private fun openEditDialog(slot: Int) {
        if (!isOperator()) return
        // 格子是**自绘**的，不走 NineSliceButton 那套内置点击音 —— 不在这里补一下，
        // 点格子的手感就是"没反应"（用户 2026-09-20 报「网格内的交互都没音效」）
        playMarketSound("button_click")
        editSlot = slot
        editOriginal = PointMarketCache.slots[slot]
        resetDraft()
        editOriginal?.let { fillDraft(it) }
        hideMainControls()
        buildEditDialog()
        // 开窗自动叫一声（延迟到渲染之后再播，见 IconCryClick）
        editCryAt = System.currentTimeMillis() + IconCryClick.AUTO_CRY_DELAY_MS
    }

    /**
     * 关窗：清掉窗口状态 + **重建界面**把主控件恢复回来。
     *
     * ⚠ 重建会重跑 init，兑换框里玩家敲了一半的码靠 `pendingCodeText` 恢复（见 init 的注释），
     * 翻页位置等状态也都是字段、不受影响。
     */
    private fun closeEditDialog() {
        editSlot = -1
        editOriginal = null
        editHintMsg = null
        editSpeciesField = null
        editLevelField = null
        editShinyField = null
        editPriceField = null
        editStockField = null
        editSpeciesPrev = null
        editSpeciesNext = null
        editSpeciesClear = null
        editConfirmButton = null
        editRemoveButton = null
        editButtons.clear()
        optionListKind = null
        optionListButtons.clear()
        optionListScroll = 0
        resetDraft()
        clearChildren()
        init()
    }

    /** 弹窗期间把主界面控件全部藏起来（**下层不许留可点的东西**，前科：弹窗没藏住货币按钮） */
    private fun hideMainControls() {
        codeField?.visible = false
        redeemButton?.visible = false
        backButton?.visible = false
        buyEntryButton?.visible = false
        recordButton?.visible = false
        pokemonTabButton?.visible = false
        itemTabButton?.visible = false
        prevButton?.visible = false
        nextButton?.visible = false
    }

    /** 草稿回到「默认」（新增模式 / 关窗时） */
    private fun resetDraft() {
        editAspects = emptyList()
        editShinyText = ""
        editGender = 0
        editNature = ""
        editAbility = ""
        editBall = DEFAULT_BALL_ID
        editSize = 0
        editAlpha = false
        editTera = ""
        editGmax = false
        editTradeable = true
        editHeldItem = null
        editHeldItemName = ""
        editMinIvs = 0
        editMoves = ""
        editItemId = ""
        editItemName = ""
        editItemComponents = null
        editIvText = List(STAT_COUNT) { "" }
        editEvText = List(STAT_COUNT) { "" }
        editFriendshipText = DEFAULT_FRIENDSHIP.toString()
        editHeldVisible = true
        editMarks = emptyList()
        // ⚠ 输入框的暂存文本**必须一起重置** —— 它们是 resize 重建时恢复输入用的，
        //   漏清的话"点第二个格子，里面还是上一格填的内容"（2026-09-20 用户实测）
        editSpeciesText = ""
        // 等级 / 亲密度是**预填默认值**（用户 2026-09-20 定）：新建就是 Lv.1 / 亲密度 50，
        // 而不是空着（空 = 生成时随机掷）。清掉照样能回到随机，这里只是给个起点
        editLevelText = DEFAULT_LEVEL.toString()
        editPriceText = ""
        editStockText = ""
        editSpecies = null
        editSpeciesCandidates = emptyList()
        editSpeciesIndex = 0
        editPreview = null
    }

    /**
     * 编辑模式回填：把该格已存的**完整规格**填进草稿。
     *
     * ⚠ 一个字段都不能漏 —— 漏了的字段在提交时会被默认值覆盖，
     * 「什么都不改直接保存 = 什么都不变」就破了（全量覆盖式表单的铁律）。
     */
    private fun fillDraft(entry: ShopEntry) {
        entry.item?.let { item ->
            editItemId = item.itemId
            editItemComponents = item.componentsSpec
            editItemName = Registries.ITEM.getOrEmpty(Identifier.tryParse(item.itemId))
                .map { Text.translatable(it.translationKey).string }
                .orElse(item.itemId)
            return
        }
        val spec = entry.pokemon ?: return
        // 闪光：固定闪光记在 aspects 里，概率闪光记在 shinyChance 里（数据层两者互斥）
        editAspects = spec.aspects.filter { it != "shiny" }
        editShinyText = when {
            "shiny" in spec.aspects -> "100"
            spec.shinyChance > 0f -> formatChance(spec.shinyChance * 100f)
            else -> ""
        }
        editGender = GENDER_VALUES.indexOf(spec.gender ?: "").coerceAtLeast(0)
        editNature = spec.nature.orEmpty()
        editAbility = spec.ability.orEmpty()
        editBall = spec.ball?.takeIf { it.isNotEmpty() } ?: DEFAULT_BALL_ID
        editSize = sizeIndexOf(spec.scale)
        editAlpha = spec.alpha
        editTera = spec.teraType.orEmpty()
        editGmax = spec.gmaxFactor
        editTradeable = spec.tradeable
        editHeldItem = spec.heldItemSpec
        editHeldItemName = spec.heldItemSpec?.let { heldItemLabel(it) }.orEmpty()
        // 两者互斥：`ivs` 有值时保底不参与（数据层口径，见 ShopPokemonSpec 注释）—— 回填按同一口径走，
        // 免得界面停在「两个都有值」那种生成时说不清听谁的状态
        editMinIvs = if (spec.ivs != null) 0 else spec.minPerfectIvs
        editMoves = spec.moves
        // 六项个体值 / 努力值：空 = 未填（个体值那组 -1 就是"该项随机"）
        editIvText = spec.ivs?.map { if (it < 0) "" else it.toString() } ?: List(STAT_COUNT) { "" }
        editEvText = spec.evs?.map { if (it <= 0) "" else it.toString() } ?: List(STAT_COUNT) { "" }
        editFriendshipText = spec.friendship?.toString().orEmpty()
        // ⚠ 等级**必须在这里显式覆盖** [resetDraft] 预填的那个默认值 —— 输入框那边的 ifEmpty 兜底
        //   只在 `editLevelText` 为空时才生效，预填之后它不再为空，不覆盖的话编辑任何一条已有商品
        //   都会显示成 Lv.1（数据没错、只是显示骗人，但保存下去就成了 1 级）
        editLevelText = spec.level?.toString().orEmpty()
        editHeldVisible = spec.heldItemVisible
        editMarks = spec.marks
        // 物种：解析出来才能显示预览与形态/特性候选
        val species = Identifier.tryParse(spec.species)?.let { PokemonSpecies.getByIdentifier(it) }
        editSpecies = species
        editSpeciesCandidates = listOfNotNull(species)
        editSpeciesIndex = 0
        // ⚠ 这几行 normalize **必须落在物种定下来之后**。原先它们写在上头（那时 editSpecies 还是
        //   [resetDraft] 清掉的 null）⇒ 全是空操作，「编辑老数据时收进合法范围」其实从没生效
        //   （2026-09-20 修；注释当时就写着"物种定了之后"，代码没跟上）
        normalizeGender()
        normalizeGmax()
        normalizeAspects()
        normalizeAbility()
        normalizeMoves()
        editSpeciesField?.text = species?.resourceIdentifier?.toString() ?: spec.species
        refreshEditPreview()
    }

    /**
     * 提交被**本地**校验拦下时的反馈：音效 + 界面内红字 + 聊天栏红字（双通道，同兑换那边）。
     *
     * ⚠ 光响一声 fail 音效玩家不知道哪儿不对（2026-09-20 用户报「价格没填只有音效、没提示」）。
     * 界面内的那一条画在**标题右侧那片空位**（用户指出「标题和下架按钮之间不是还有很多空隙」），
     * 位置见 [renderEditDialogBackground]。
     */
    private fun failSubmit(key: String) {
        playFailSound()
        val msg = Text.translatable("cobblemarket.points.store.hint.$key")
        editHintMsg = msg
        editHintUntil = System.currentTimeMillis() + EDIT_HINT_MS
        client?.player?.sendMessage(msg.copy().formatted(Formatting.RED), false)
    }

    /** 提交：把草稿拼成 [ShopEntry] 发给服务端（服务端还会全量校验一遍） */
    private fun submitEdit() {
        val priceText = editPriceField?.text?.trim().orEmpty()
        val price = priceText.toLongOrNull()
        if (price == null || price < 0) {
            failSubmit("price_required")
            return
        }
        val stockText = editStockField?.text?.trim().orEmpty()
        // 留空 = 不限量（-1）；填 0 没意义（上架即售罄），服务端也会拒
        val stock = if (stockText.isEmpty()) ShopEntry.UNLIMITED else stockText.toIntOrNull() ?: run {
            playFailSound()
            return
        }
        val entry = if (isPokemonSlot()) {
            val spec = buildPokemonSpec() ?: run {
                // 拼不出规格只有一种情况：物种没解析出来（见 buildPokemonSpec 的首行）
                failSubmit("species_required")
                return
            }
            ShopEntry(ShopEntry.KIND_POKEMON, price, stock, pokemon = spec)
        } else {
            if (editItemId.isEmpty()) {
                playFailSound()
                return
            }
            ShopEntry(
                ShopEntry.KIND_ITEM, price, stock,
                item = ShopItemSpec(editItemId, editItemComponents)
            )
        }
        sendToServer(SavePointMarketEntryPayload(editSlot, entry))
        closeEditDialog()
    }

    /**
     * 草稿 → 精灵规格。
     *
     * ⚠ **基于原规格 `copy()`**：窗口没接的高级项（EV / 亲密度 / 昵称 / 证章 / 逐项个体值…）
     * 原样保留，否则编辑一次就被抹平。
     */
    private fun buildPokemonSpec(): ShopPokemonSpec? {
        val base = editOriginal?.pokemon
        val speciesId = editSpecies?.resourceIdentifier?.toString() ?: base?.species ?: return null
        // 闪光两态互斥：固定闪光写进 aspects、概率闪光写进 shinyChance（数据层注释有写）。
        // 输入框填的是**百分数**，到 100% 就按必闪处理
        val chance = (editShinyText.trim().toFloatOrNull() ?: 0f).coerceIn(0f, 100f) / 100f
        val always = chance >= 1f
        val aspects = editAspects.filter { it != "shiny" }.toMutableList()
        if (always) aspects.add("shiny")
        val spec = base ?: ShopPokemonSpec(species = speciesId)
        // 六项个体值 / 努力值：空输入按「未填」算（个体值 -1 = 该项随机，努力值 0 = 不加）
        val ivs = editIvText.map { it.trim().toIntOrNull()?.coerceIn(0, 31) ?: -1 }
        val hasIv = ivs.any { it >= 0 }
        val evs = editEvText.map { it.trim().toIntOrNull()?.coerceIn(0, 252) ?: 0 }
        return spec.copy(
            species = speciesId,
            aspects = aspects,
            shinyChance = if (always) 0f else chance,
            level = editLevelField?.text?.trim()?.toIntOrNull(),
            gender = GENDER_VALUES.getOrNull(editGender)?.takeIf { it.isNotEmpty() },
            nature = editNature.ifEmpty { null },
            ability = editAbility.ifEmpty { null },
            ball = editBall.ifEmpty { DEFAULT_BALL_ID },
            scale = sizeScale(),
            alpha = editAlpha,
            teraType = editTera.ifEmpty { null },
            gmaxFactor = editGmax,
            tradeable = editTradeable,
            heldItemSpec = editHeldItem,
            // 六项个体值：**整组全空**才回退到「保底 V 数」（数据层对这两种玩法是互斥的，见注释）
            ivs = if (hasIv) ivs else null,
            minPerfectIvs = if (hasIv) 0 else editMinIvs,
            evs = if (evs.any { it > 0 }) evs else null,
            friendship = editFriendshipText.trim().toIntOrNull()?.coerceIn(0, 255),
            heldItemVisible = editHeldVisible,
            marks = editMarks,
            moves = editMoves
        )
    }

    /** 体型档位 → scale 倍率（取该档**中点**；分档边界是游戏配置算的，不能写死，见 [PokemonSizeCategory]） */
    private fun sizeScale(): Float? {
        val category = PokemonSizeCategory.entries.getOrNull(editSize - 1) ?: return null
        val min = ServerSettings.pokemonIntrinsicSizeMin
        val max = ServerSettings.pokemonIntrinsicSizeMax
        val segment = (max - min) / PokemonSizeCategory.entries.size
        return min + segment * (category.ordinal + 0.5f)
    }

    /** scale 倍率 → 体型档位下标（0 = 随机） */
    private fun sizeIndexOf(scale: Float?): Int {
        val s = scale ?: return 0
        val category = PokemonSizeCategory.fromScale(s)
        return category.ordinal + 1
    }

    /** 携带物 SNBT → 显示名（解析不出来就退回原文，绝不崩） */
    private fun heldItemLabel(snbt: String): String = runCatching {
        val nbt = net.minecraft.nbt.StringNbtReader.parse(snbt)
        val id = nbt.getString("id")
        val item = net.minecraft.registry.Registries.ITEM.getOrEmpty(Identifier.tryParse(id)).orElse(null)
        item?.let { Text.translatable(it.translationKey).string } ?: id
    }.getOrDefault("")

    // ── 窗口：建控件 ──

    /**
     * 建窗口控件（开窗与 resize 后重建都会调；初值一律从草稿来）。
     *
     * ⚠ **背景必须第一个加**：MC 按添加顺序渲染 children，先加背景、后加控件，
     * 控件才画在背景之上（黑名单弹窗同款做法）。背景作为 Drawable 加进 children，
     * 而不是在 `render` 里画 —— 在 render 里画会盖住 super.render 画出来的控件。
     */
    private fun buildEditDialog() {
        // ⚠ 先把**上一次弹窗残留的控件**清掉：`editButtons` 这类 map 只存最后一次建的引用，
        //   重复建出来的旧对象还挂在 children 上，靠"逐个列名字隐藏"永远管不到它们
        //   （2026-09-20 用户实测：列表展开时左上角冒出 4 个叠在一起的按钮）
        if (editWidgetBase in 0 until children().size) {
            children().drop(editWidgetBase).toList().forEach { remove(it) }
        }
        editWidgetBase = children().size

        val x = editDialogX()
        val y = editDialogY()

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderEditDialogBackground(context, mouseX, mouseY, delta)
            }
        })

        if (isPokemonSlot()) buildPokemonFields(x, y) else buildItemFields()

        // 价格 / 库存（两种窗口都有；**钉在窗口底部**，不随内容滚动）
        val priceY = editPriceRowY()
        editPriceField = TextFieldWidget(
            textRenderer, editPriceGroupX() + EDIT_LABEL_W, priceY,
            editColW() - EDIT_LABEL_W, EDIT_ROW_H, Text.literal("")
        )
        editPriceField?.setTextPredicate { s -> s.length <= 12 && s.all { it.isDigit() } }
        editPriceField?.setChangedListener { editPriceText = it }
        editPriceField?.text = editPriceText.ifEmpty {
            editOriginal?.price?.takeIf { it > 0L }?.toString().orEmpty()
        }
        addSelectableChild(editPriceField)
        addDrawableChild(editPriceField)

        editStockField = TextFieldWidget(
            textRenderer, editPriceGroupX() + editColW() + EDIT_COL_GAP + EDIT_LABEL_W, priceY,
            editColW() - EDIT_LABEL_W, EDIT_ROW_H, Text.literal("")
        )
        editStockField?.setTextPredicate { s -> s.length <= 9 && s.all { it.isDigit() } }
        editStockField?.setPlaceholder(Text.translatable("cobblemarket.points.store.value.unlimited"))
        editStockField?.setChangedListener { editStockText = it }
        // 回填：不限量 → 留空（「不改 = 什么都不变」）
        editStockField?.text = editStockText.ifEmpty {
            editOriginal?.stock?.takeIf { it != ShopEntry.UNLIMITED }?.toString().orEmpty()
        }
        addSelectableChild(editStockField)
        addDrawableChild(editStockField)

        // 底部按钮行：保存/添加 + 取消（居中并排，间距 8）
        val btnY = editButtonRowY()
        val confirmText = if (editOriginal != null) "cobblemarket.points.store.save"
        else "cobblemarket.points.store.add"
        val contentW = editDialogW() - EDIT_PAD * 2
        editConfirmButton = NineSliceButton(
            x + EDIT_PAD + (contentW - EDIT_BUTTON_W * 2 - 8) / 2,
            btnY, EDIT_BUTTON_W, EDIT_BUTTON_H, Text.translatable(confirmText), { submitEdit() }
        )
        addDrawableChild(editConfirmButton)
        // ⚠ 取消按钮**必须存字段**：展开选项列表时要把它一起藏掉，否则它压在列表上、
        //   一点就把整个窗口关了（2026-09-20 用户实测：选形态时点「取消」直接退出了编辑窗）
        editCancelButton = NineSliceButton(
            x + EDIT_PAD + (contentW + 8) / 2,
            btnY, EDIT_BUTTON_W, EDIT_BUTTON_H,
            Text.translatable("cobblemarket.buy_confirm.cancel"), { closeEditDialog() }
        )
        addDrawableChild(editCancelButton)

        // 下架（只在编辑已有内容时出现）：右上角，右缘**与最右列字段按钮对齐**。
        // ⚠ 不能按「窗口右缘 − 内边距」算 —— 列宽是整数除法（余数丢掉），两种算法能差几个像素，
        //   看着就是"没对齐"（2026-09-20 用户指出）。
        if (editOriginal != null) {
            editRemoveButton = NineSliceButton(
                editRowRightX() - EDIT_REMOVE_W, editRemoveY(),
                EDIT_REMOVE_W, EDIT_ROW_H,
                Text.translatable("cobblemarket.points.store.remove"), { confirmRemoveSlot() }
            )
            addDrawableChild(editRemoveButton)
        }

        refreshEditLabels()
        applyEditScroll()
    }

    /**
     * 滚动区里所有输入框的「控件 + 行号 + 列内横向偏移」——
     * 滚动时要按它统一重算坐标与可见性（散在各处各写一遍必然漏掉几个）。
     */
    private fun scrollableFields(): List<Pair<TextFieldWidget?, String>> = buildList {
        add(editShinyField to KEY_SHINY_CHANCE)
        add(editLevelField to KEY_LEVEL)
        add(editFriendshipField to KEY_FRIENDSHIP)
        editIvFields.forEachIndexed { i, f -> add(f to "$KEY_IV$i") }
        editEvFields.forEachIndexed { i, f -> add(f to "$KEY_EV$i") }
    }

    /**
     * 应用滚动：把中间那坨字段控件整体上下移，并按「整行是否露在滚动区里」显隐。
     *
     * ⚠ 原版控件没有裁剪，半截的行只能**整体藏起来**（列表滚动也是这么做的）；
     * 价格/库存与按钮在滚动区之外，不参与。
     */
    private fun applyEditScroll() {
        editContentScroll = editContentScroll.coerceIn(0, editMaxScroll())
        editButtons.forEach { (key, btn) ->
            btn.x = editColX(editColOf(key))
            btn.y = editRowY(editRowOf(key))
        }
        // 滚动区里的所有输入框跟着行一起移动（闪光 / 等级 / 亲密度 / 六项个体 / 六项努力）
        scrollableFields().forEach { (field, key) ->
            field ?: return@forEach
            val row = editRowOf(key)
            // ⚠ 列内缩进必须带上（等级那格的「Lv.」前缀）—— 这行原来直接写 editColX，一滚动就把前缀压掉了
            field.x = editColX(editColOf(key)) + editFieldInsetX(key)
            field.y = editRowY(row)
            // 滚出视野的输入框要失焦，否则按键还在往一个看不见的框里打
            if (!editRowVisible(row) && focused === field) focused = null
        }
        updateEditControlsVisibility()
    }

    /** 精灵窗口的字段控件（物种搜索 + 两列网格） */
    private fun buildPokemonFields(x: Int, y: Int) {
        val field = TextFieldWidget(
            textRenderer, x + EDIT_PAD, y + EDIT_SPECIES_Y, editSpeciesFieldW(), EDIT_ROW_H, Text.literal("")
        )
        field.setPlaceholder(Text.translatable("cobblemarket.points.store.species_hint"))
        // ⚠ 输入变化要重解析候选（精确优先，见 SpeciesText.candidatesByNameOrId）——
        //   直接取第一个会把中文名互为子串的物种静默换掉（「鬼斯」/「鬼斯通」）
        field.setChangedListener { text -> editSpeciesText = text; updateEditSpecies(text) }
        field.text = editSpeciesText.ifEmpty {
            editSpecies?.resourceIdentifier?.toString() ?: editOriginal?.pokemon?.species.orEmpty()
        }
        editSpeciesField = field
        addSelectableChild(field)
        addDrawableChild(field)

        // 清空搜索框（用户 2026-09-20）：只把输入框里的字清掉，不碰别的
        editSpeciesClear = NineSliceButton(
            editClearX(), y + EDIT_SPECIES_Y, SPECIES_CLEAR_W, EDIT_ROW_H,
            Text.translatable("cobblemarket.points.store.species_clear"), { clearEditSpecies() },
            tooltip = Text.translatable("cobblemarket.gui.species_clear")
        )
        addDrawableChild(editSpeciesClear)

        editSpeciesPrev = NineSliceButton(
            editArrowX(), y + EDIT_SPECIES_Y, EDIT_ARROW_W, EDIT_ROW_H, Text.literal(""),
            { cycleEditSpecies(-1) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/previous.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.species_prev")
        )
        editSpeciesNext = NineSliceButton(
            editArrow2X(), y + EDIT_SPECIES_Y, EDIT_ARROW_W, EDIT_ROW_H, Text.literal(""),
            { cycleEditSpecies(1) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/next.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.species_next")
        )
        addDrawableChild(editSpeciesPrev)
        addDrawableChild(editSpeciesNext)

        // ── 字段网格：**按顺序表流式排布**（列数随窗口宽度变，位置一律现算，见 editFieldOrder）──
        editIvFields.clear()
        editEvFields.clear()
        // ⚠ **建控件的遍历源 ≠ 布局表**：`editFieldOrder` 只描述左 3 列那 18 个字段的位置，
        //   个体值 / 努力值 / 技能槽有各自的固定区（见 posOf），必须一起遍历到，
        //   否则它们的控件压根不会被建出来（2026-09-20 用户实测：12 个框和技能全没了）
        val allKeys = buildList {
            addAll(editFieldOrder())
            repeat(STAT_COUNT) { add("$KEY_IV$it") }
            repeat(STAT_COUNT) { add("$KEY_EV$it") }
            repeat(MoveText.SLOTS) { add("$KEY_SKILL$it") }
        }
        for (key in allKeys) {
            when {
                key == KEY_SHINY_CHANCE -> {
                    editShinyField = newEditField(key)
                    editShinyField?.setTextPredicate { s -> s.length <= 6 && s.all { it.isDigit() || it == '.' } }
                    editShinyField?.setPlaceholder(Text.literal("%"))
                    editShinyField?.setChangedListener { text ->
                        editShinyText = text
                        refreshEditPreview()
                        refreshEditLabels()
                    }
                    editShinyField?.text = editShinyText
                }

                key == KEY_LEVEL -> {
                    editLevelField = newEditField(key)
                    editLevelField?.setTextPredicate(intInputIn(1..100))
                    // ⚠ 占位符**不能带冒号**（表单 label 才带），所以与 field.* 分开写（项目文案约定）
                    editLevelField?.setPlaceholder(Text.translatable("cobblemarket.points.store.field.level_hint"))
                    editLevelField?.setChangedListener { editLevelText = it }
                    editLevelField?.text = editLevelText.ifEmpty {
                        editOriginal?.pokemon?.level?.toString().orEmpty()
                    }
                }

                key == KEY_FRIENDSHIP -> {
                    editFriendshipField = newEditField(key)
                    editFriendshipField?.setTextPredicate(intInputIn(0..255))
                    editFriendshipField?.setPlaceholder(
                        Text.translatable("cobblemarket.points.store.field.friendship_hint")
                    )
                    editFriendshipField?.setChangedListener { editFriendshipText = it }
                    editFriendshipField?.text = editFriendshipText
                }

                // 六项个体值 / 努力值：顺序表里各连着 6 个，按序进各自的清单
                key.startsWith(KEY_IV) -> {
                    val i = key.removePrefix(KEY_IV).toInt()
                    val f = newEditField(key)
                    f.setTextPredicate(intInputIn(0..31))
                    // 占位符带上上限（用户 2026-09-20）：输入被范围拒收时，玩家能一眼看出该填多少
                    f.setPlaceholder(Text.translatable("cobblemarket.points.store.iv_hint", STAT_LABELS[i]))
                    f.setChangedListener { t ->
                        editIvText = editIvText.toMutableList().also { it[i] = t }
                        // 保底按钮的置灰**跟着敲键即时走**（互斥，见 applyIvInputLock）——
                        // 只更这一个控件的状态，不用重建整屏文案
                        editButtons[KEY_MIN_IVS]?.dimmed = hasIvInput()
                    }
                    f.text = editIvText.getOrElse(i) { "" }
                    editIvFields.add(f)
                }

                key.startsWith(KEY_EV) -> {
                    val i = key.removePrefix(KEY_EV).toInt()
                    val f = newEditField(key)
                    // ⚠ 除单项 252 外还有**总和上限**（[EV_TOTAL_MAX] = 有效的 508，不是硬上限 510）：
                    //   六项加起来超了的话，游戏设值时会把第三项起**静默拒掉**（[EVs.canSet]），
                    //   出来的是 [252,252,0,0,0,0] 跟填的完全不是一回事；NBT 反序列化那边更是直接报错
                    //   （2026-09-20 用户问「六个框都填 252 怎么办」）。所以这里跨框一起算着拦
                    f.setTextPredicate(intInputIn(0..252) { v -> evSumExcept(i) + v <= EV_TOTAL_MAX })
                    // 加前缀区分两组 —— 项名缩写两组是一样的，不看前缀分不出哪组是哪组。
                    // 上限 252 也写进占位符里（同个体值那组，用户 2026-09-20）
                    f.setPlaceholder(Text.translatable("cobblemarket.points.store.ev_hint", STAT_LABELS[i]))
                    f.setChangedListener { t -> editEvText = editEvText.toMutableList().also { it[i] = t } }
                    f.text = editEvText.getOrElse(i) { "" }
                    editEvFields.add(f)
                }

                // 携带物 / 球种：按钮上直接放**物品图标**（去文字化），图标在 refreshEditLabels 里刷新
                key == KEY_HELD || key == KEY_BALL -> {
                    val btn = NineSliceButton(
                        editColX(editColOf(key)), editRowY(editRowOf(key)),
                        editColW(), EDIT_ROW_H, Text.literal(""),
                        { onFieldClick(key) },
                        iconAfterText = true,
                        // 不加悬停提示：按钮上已有「标签：」+ 图标，多一层提示是噪音（2026-09-20 用户指出）
                    )
                    editButtons[key] = btn
                    addDrawableChild(btn)
                }

                // 头目也单独建：要用 37×16 的 alpha 徽章（与列表行里那张同一张图）
                key == KEY_ALPHA -> {
                    val btn = NineSliceButton(
                        editColX(editColOf(key)), editRowY(editRowOf(key)),
                        editColW(), EDIT_ROW_H, Text.literal(""),
                        { onFieldClick(key) },
                        iconTexW = 37, iconTexH = 16, iconScale = 0.5f,
                        iconAfterText = true
                        // 不加悬停提示：按钮上已有「头目：」+ 徽章，多一层是噪音（2026-09-20 用户指出）
                    )
                    editButtons[key] = btn
                    addDrawableChild(btn)
                }

                // 性别按钮单独建：要 6×8 的 ♂/♀ 图标（尺寸与其余字段按钮不同，构造期就得定）
                key == KEY_GENDER -> {
                    val btn = NineSliceButton(
                        editColX(editColOf(key)), editRowY(editRowOf(key)),
                        editColW(), EDIT_ROW_H, Text.literal(""),
                        { onFieldClick(key) },
                        iconTexW = 6, iconTexH = 8, iconScale = 1.5f
                    )
                    editButtons[key] = btn
                    addDrawableChild(btn)
                }

                else -> editFieldButton(key) { onFieldClick(key) }
            }
        }
    }

    /**
     * 物品窗口的字段：只有「卖什么物品」一项 —— **取服主手里拿着的那件**（连组件一起记下来，
     * 附魔 / 自定义名都跟着走），比再做一个物品搜索列表直接得多。想换就拿着新的点一次。
     */
    private fun buildItemFields() {
        // 物品窗口不走字段顺序表（那张表是精灵的），就一格，位置固定 —— KEY_ITEM 查不到行号时
        // editRowOf/editColOf 返回 0，滚动重算也落在同一格上
        val btn = NineSliceButton(
            editColX(0), editRowY(0), editColW(), EDIT_ROW_H,
            Text.literal(""), { pickEditItem() }
        )
        editButtons[KEY_ITEM] = btn
        addDrawableChild(btn)
    }

    /** 取主手物品作为商品（**组件一起提取**：走 ItemRuleComponents 的白名单口径，同黑名单/限价） */
    private fun pickEditItem() {
        val stack = client?.player?.mainHandStack ?: return
        if (stack.isEmpty) {
            playFailSound()
            return
        }
        val lookup = client?.world?.registryManager ?: return
        editItemId = Registries.ITEM.getId(stack.item).toString()
        editItemName = stack.name.string
        editItemComponents = ItemRuleComponents.extractSpec(stack, lookup)
        refreshEditLabels()
    }

    /** 建一个窗口字段按钮（位置按字段 key 现算 —— 列数随窗口宽度变，不能记死坐标） */
    private fun editFieldButton(key: String, onClick: () -> Unit) {
        val btn = NineSliceButton(
            editColX(editColOf(key)), editRowY(editRowOf(key)), editColW(), EDIT_ROW_H,
            Text.literal(""), { onClick() }
        )
        editButtons[key] = btn
        addDrawableChild(btn)
    }

    /**
     * 「Lv.」前缀占的宽度 —— 就取字面量的实际宽度（词条与全项目等级行同一个）。
     *
     * 数字要**正好接上**（用户 2026-09-20）：输入框自带的 4px 文字内边距刚好当间隙，
     * 前缀宽度取字面量宽度即可，不用另外加。
     */
    private fun levelLabelW(): Int = textRenderer.getWidth(Text.translatable(LEVEL_LABEL_KEY))

    /**
     * 该字段输入框在**列内**的左缩进（目前只有等级那格有前缀）。
     * ⚠ 建控件（[newEditField]）与滚动重排（[applyEditScroll]）**两处都走这里** ——
     *   各算一份的话，一滚动前缀就压到输入框上了。
     */
    private fun editFieldInsetX(key: String): Int = if (key == KEY_LEVEL) levelLabelW() else 0

    /** 该字段输入框的宽度（让出 [editFieldInsetX] 那块前缀） */
    private fun editFieldW(key: String): Int = editColW() - editFieldInsetX(key)

    /**
     * 数值输入框的输入限制：**空串放行**（= 不填 / 随机），其余必须落在 [range] 里。
     *
     * ⚠ 超范围要**当场拒收**（predicate 返回 false 时这次输入根本不进框），别指望提交时那句
     *   `coerceIn` —— 那会把框里显示的 `150` 悄悄改成 `100` 再存下去，玩家看到的和存下的不是一回事。
     *   （2026-09-20 用户指出等级 / 亲密度 / 个体值 / 努力值都只限了位数、没限范围。）
     *
     * 顺带禁前导零（`031` 这种看着不像话）；单独一个 `0` 仍然合法（个体值 / 努力值 / 亲密度都取 0）。
     * 区间口径与服务端 [com.shusheng.cobblemarket.currency.PointMarketService] 的校验一致。
     */
    private fun intInputIn(range: IntRange, extraOk: (Int) -> Boolean = { true }): (String) -> Boolean = { s ->
        s.isEmpty() ||
            (s.length <= 3 && s.all { it.isDigit() } && (s.length == 1 || s[0] != '0') &&
                (s.toIntOrNull() ?: -1) in range &&
                extraOk(s.toIntOrNull() ?: 0))
    }

    /**
     * 六项努力值里**除第 [except] 项之外的和** —— 总和上限（[EV_TOTAL_MAX]）跨项判定用。
     * 读 `editEvText` 暂存文本，敲键时即时算（与 [hasIvInput] 同一套实时口径）。
     */
    private fun evSumExcept(except: Int): Int =
        editEvText.filterIndexed { idx, _ -> idx != except }
            .sumOf { it.trim().toIntOrNull() ?: 0 }

    /** 建一个窗口输入框（位置同样按字段 key 现算；predicate / 占位符 / 监听由调用方补） */
    private fun newEditField(key: String): TextFieldWidget {
        val f = TextFieldWidget(
            textRenderer, editColX(editColOf(key)) + editFieldInsetX(key), editRowY(editRowOf(key)),
            editFieldW(key), EDIT_ROW_H, Text.literal("")
        )
        addSelectableChild(f)
        addDrawableChild(f)
        return f
    }

    /** 字段按钮的点击分发（顺序表里除输入框以外的项都走这里） */
    private fun onFieldClick(key: String) {
        when (key) {
            KEY_SHINY -> cycleShiny()
            KEY_FORM -> openOptionList(OPT_FORM)
            KEY_GENDER -> cycleGender()
            KEY_NATURE -> openOptionList(OPT_NATURE)
            KEY_ABILITY -> openOptionList(OPT_ABILITY)
            KEY_BALL -> openOptionList(OPT_BALL)
            KEY_SIZE -> cycleSize()
            KEY_TERA -> openOptionList(OPT_TERA)
            KEY_HELD -> openOptionList(OPT_CARRY)
            KEY_MARKS -> openOptionList(OPT_MARKS)
            KEY_MIN_IVS -> cycleMinIvs()
            KEY_ALPHA -> {
                editAlpha = !editAlpha
                refreshEditLabels()
            }

            KEY_GMAX -> cycleGmax()

            KEY_TRADEABLE -> {
                editTradeable = !editTradeable
                refreshEditLabels()
            }

            KEY_HELD_VISIBLE -> {
                editHeldVisible = !editHeldVisible
                // 预览要跟着变 —— 这个开关管的就是「别人看不看得到携带物」，
                // 编辑时该看到最终效果（2026-09-20 用户实测：切了没反应）
                refreshEditPreview()
                refreshEditLabels()
            }

            else -> key.removePrefix(KEY_SKILL).toIntOrNull()?.let { openOptionList("$OPT_SKILL$it") }
        }
    }

    // ── 窗口：字段交互 ──

    private fun updateEditSpecies(text: String) {
        editSpeciesCandidates = SpeciesText.candidatesByNameOrId(text)
        editSpeciesIndex = 0
        applyEditSpecies(editSpeciesCandidates.firstOrNull())
        updateEditSpeciesArrows()
    }

    /**
     * 清空物种搜索框（用户 2026-09-20）。
     *
     * 走 `text = ""` 而不是直接改 `editSpeciesText` —— 赋值会触发 changedListener，
     * 候选与预览跟着现有联动自然收掉，不必在这里再抄一遍那套逻辑。
     */
    private fun clearEditSpecies() {
        editSpeciesField?.text = ""
    }

    private fun cycleEditSpecies(delta: Int) {
        if (editSpeciesCandidates.size <= 1) return
        editSpeciesIndex = (editSpeciesIndex + delta + editSpeciesCandidates.size) % editSpeciesCandidates.size
        applyEditSpecies(editSpeciesCandidates[editSpeciesIndex])
        updateEditSpeciesArrows()
        // 切到别的物种了，叫声跟着换（只在这里叫：打字时每个字都会重解析，不能跟着叫）
        editCryAt = System.currentTimeMillis() + IconCryClick.AUTO_CRY_DELAY_MS
    }

    /** 箭头只在候选 ≥2 **且没展开选项列表**时出现（唯一候选没什么可切的；列表展开时它会是干扰） */
    private fun updateEditSpeciesArrows() {
        val multi = editSpeciesCandidates.size > 1 && optionListKind == null
        editSpeciesPrev?.visible = multi
        editSpeciesNext?.visible = multi
    }

    private fun applyEditSpecies(species: Species?) {
        editSpecies = species
        // 换了物种，原来的性别 / 超极巨化 / 形态 / 特性 / 技能可能就不合法了（喷火龙 → 小磁怪）
        // → 各自收进合法范围
        normalizeGender()
        normalizeGmax()
        normalizeAspects()
        normalizeAbility()
        normalizeMoves()
        refreshEditPreview()
        refreshEditLabels()
    }

    /** 按当前形态 + 闪光选择重建预览模型（必闪时叠 `shiny` aspect，预览就渲染闪光形态） */
    private fun refreshEditPreview() {
        val species = editSpecies ?: run { editPreview = null; return }
        val aspects = editAspects.toMutableSet()
        if (isShinyAlways()) aspects.add("shiny")
        // ⚠ **性别 → 形态**：公母长相不同的物种（爱管侍那类）靠 aspects 里的 male / female 区分，
        //   选了性别就叠上去，预览图才会跟着变（2026-09-20 用户要求的"反向生效"）。
        //   ⚠ **不写回 `editAspects`** —— 数据层的 `gender` 字段已经表达了性别，
        //   写回 aspects 会在生成精灵时重复设置。
        when (GENDER_VALUES.getOrNull(editGender).orEmpty()) {
            "MALE" -> {
                aspects.remove("female"); aspects.add("male")
            }

            "FEMALE" -> {
                aspects.remove("male"); aspects.add("female")
            }

            else -> Unit   // 随机 / 无性别：不叠，按物种默认形态渲染
        }
        // ⚠ 第三个参数是**携带物**：不传的话预览里看不到它带了什么（与市场的图标/大图同口径）。
        //   **可见性关着就不传** —— 那正是"别人会看到的样子"，编辑时也要照着显示
        editPreview = RenderablePokemon(
            species, aspects,
            if (editHeldVisible) stackOfSpec(editHeldItem) else ItemStack.EMPTY
        )
    }

    /**
     * 携带物规格串 → 物品栈（预览渲染用）。
     *
     * ⚠ 走项目现成的 [heldIconStack] **而不是自己解 SNBT** —— 它多一层兜底：
     * SNBT 重建失败还能按 `id` 建个基础物品，预览不至于整只消失（2026-09-20 用户实测：
     * 自己那版解不出来就返回空栈，携带物完全不显示）。
     */
    private fun stackOfSpec(snbt: String?): ItemStack {
        val raw = snbt?.takeIf { it.isNotEmpty() } ?: return ItemStack.EMPTY
        val id = runCatching {
            net.minecraft.nbt.StringNbtReader.parse(raw).getString("id")
        }.getOrDefault("")
        return com.shusheng.cobblemarket.client.heldIconStack(id, raw) ?: ItemStack.EMPTY
    }

    /**
     * 闪光档位按钮：点一下切到下一个档位，并把值**填进输入框**（输入框才是真值）。
     * 手动敲了非档位值（如 `0.5`）时，再点按钮会回到「不闪」—— 环从头上继续。
     */
    private fun cycleShiny() {
        val cur = editShinyText.trim()
        val idx = SHINY_PRESETS.indexOfFirst { it == cur }
        val next = SHINY_PRESETS[(idx + 1).mod(SHINY_PRESETS.size)]
        editShinyText = next
        editShinyField?.text = next
        refreshEditPreview()
        refreshEditLabels()
    }

    /** 必闪（概率 ≥ 100%）：预览渲染闪光形态，规格里写进 aspects 的 `shiny` */
    private fun isShinyAlways(): Boolean = (editShinyText.trim().toFloatOrNull() ?: 0f) >= 100f

    /** 存档里的 0~1 概率 → 输入框的百分数原文（整数不带小数点，小数照原样） */
    private fun formatChance(percent: Float): String =
        if (percent == percent.toInt().toFloat()) percent.toInt().toString() else percent.toString()

    /**
     * 该物种**可能**的性别档位下标（照特性按钮的做法：候选受物种约束）。
     *
     * 神兽那种无性别物种只剩「无性别」，只有单一性别的物种同理 —— 此时按钮点也点不动。
     * 「随机」只在有两种以上性别时才有意义（唯一的性别没什么可随机）。
     * 物种还没解析出来时给全部档位。
     */
    private fun availableGenderIndices(): List<Int> {
        val possible = editSpecies?.possibleGenders ?: return GENDER_VALUES.indices.toList()
        return GENDER_VALUES.indices.filter { idx ->
            val name = GENDER_VALUES[idx]
            if (name.isEmpty()) possible.size > 1
            else possible.any { it.name == name }   // 值直接用 Cobblemon 的枚举名
        }
    }

    /**
     * 这个物种**能不能超极巨化** —— 照性别按钮的做法受物种约束。
     *
     * `Species.canGmax()`（Cobblemon 自己判的：形态里有没有 `gmax`）。
     * ⚠ 与「极巨化等级」区分开：**所有**精灵都能极巨化，那个不受物种约束，别一起限制。
     */
    private fun speciesCanGmax(): Boolean = editSpecies?.canGmax() ?: true

    /** 不支持的物种固定为「否」（换物种 / 编辑老数据时原来的「是」可能就不合法了） */
    private fun normalizeGmax() {
        if (!speciesCanGmax()) editGmax = false
    }

    private fun cycleGmax() {
        // 没有超极巨化形态的物种点不动 —— 不是藏起来，是不给切（同性别按钮）
        if (!speciesCanGmax()) return
        editGmax = !editGmax
        refreshEditLabels()
    }

    /** 性别档位收进该物种可能的范围（换物种 / 编辑老数据时原性别可能不合法） */
    private fun normalizeGender() {
        val options = availableGenderIndices()
        if (options.isNotEmpty() && editGender !in options) editGender = options.first()
    }

    /**
     * 形态收进该物种**真有的**范围 —— 与 [normalizeGender] / [normalizeGmax] 同一件事的第三项。
     *
     * 用户 2026-09-20 报：性别与超极巨化都会跟着搜索出的物种收，唯独形态不收 ——
     * 换成没有该形态的精灵（喷火龙 → 小磁怪），形态按钮还挂着上一个物种的形态。
     *
     * 判据与形态候选表**同一个口径**（见 [buildOptionItems] 的 `OPT_FORM`：候选 = 物种各个
     * form 的 aspects）。比对**按集合不按字符串** —— 顺序不该影响合法性判定。
     * 对不上就回到「默认」（`editAspects` 空 = 标准形态，按钮显示「默认」）。
     */
    private fun normalizeAspects() {
        if (editAspects.isEmpty()) return
        // 判据与形态候选**同一个数据源**（[SpeciesText.selectableForms]，已滤掉超极巨化）——
        // 两边不一致的话，选不出来的形态却判成合法、或者选得出来却被清掉
        val ok = SpeciesText.selectableForms(editSpecies)
            .any { it.aspects.isNotEmpty() && it.aspects.toSet() == editAspects.toSet() }
        if (!ok) editAspects = emptyList()
    }

    /**
     * 特性收进该物种**真有的**范围 —— 上面三件（性别 / 超极巨化 / 形态）之外的第四件
     * （用户 2026-09-20 报「特性按钮一样没受钳制」）。
     *
     * 判据与特性候选表**同一个口径**（[buildOptionItems] 的 `OPT_ABILITY` = 该物种的全部可能特性）。
     *
     * ⚠ 物种为空时**也要清**：那时候选表是空的，而 [optionLabelOf] 查不到会把**内部 id 原样显示**
     *   出来（「特性：cobblemon:blaze」这种英文字母，用户实测）—— 清掉回「随机」，界面才说得通。
     */
    private fun normalizeAbility() {
        if (editAbility.isEmpty()) return
        val allowed = editSpecies?.abilities?.map { it.template.name }
        if (allowed == null || editAbility !in allowed) editAbility = ""
    }

    /**
     * 技能槽收进该物种**能学的**范围 —— 第五件（用户 2026-09-20 拍板「要加」）。
     *
     * 判据与技能候选表**同一个口径**（[buildOptionItems] 的兜底分支：`species.moves.getAllLegalMoves()`）。
     * 学不了的槽**清空** —— 即回「未指定」，生成时那一槽随机补招（数据层的既有语义）。
     *
     * ⚠ 别改成「替他随机填一个合法技能」：界面会凭空多出几个不是他选的技能、看着像自己设的，
     *   反而把「换物种后这里失效了」藏起来；清空则显示「技能1：空」，要重挑一目了然。
     *   服务端也接得住 —— 它只校验技能**存在**，不校验该物种能不能学（[PointMarketService]）。
     */
    private fun normalizeMoves() {
        val ids = MoveText.slotIds(editMoves)
        if (ids.none { it.isNotEmpty() }) return
        val allowed = editSpecies?.moves?.getAllLegalMoves()?.map { it.name }?.toSet() ?: emptySet()
        val fixed = ids.map { if (it.isEmpty() || it in allowed) it else "" }
        if (fixed != ids) editMoves = MoveText.encode(fixed)
    }

    private fun cycleGender() {
        val options = availableGenderIndices()
        // 只有一种可能（神兽那种）就点不动 —— 按用户要求，不是藏起来而是不给切
        if (options.size <= 1) return
        val i = options.indexOf(editGender)
        editGender = options[(if (i < 0) 0 else i + 1) % options.size]
        refreshEditPreview()
        refreshEditLabels()
    }

    private fun cycleSize() {
        editSize = (editSize + 1) % (PokemonSizeCategory.entries.size + 1)
        refreshEditLabels()
    }

    /**
     * 六项个体值里**有没有填过** —— 与「保底 V 数」互斥的判据（两个方向见 [applyIvInputLock]）。
     * 读 `editIvText` 暂存文本：输入框的 listener 会实时同步它（见 buildPokemonFields）。
     */
    private fun hasIvInput(): Boolean = editIvText.any { it.isNotBlank() }

    /** 保底 V 数 > 0 ⇒ 六项输入框锁住 */
    private fun ivInputsLocked(): Boolean = editMinIvs > 0

    /**
     * 六项个体值输入框的锁：**保底 V 数 > 0 时不许再输入**（用户 2026-09-20 拍板的互斥反方向）。
     *
     * 为什么反向也要锁：只禁保底按钮的话，「六项填了值 → 保底被静默丢弃」这个坑还在。
     * 两侧都锁死，界面就不可能停在「两个都设了、不知道听谁」的状态上。
     *
     * `active = false` 拦住鼠标（点不进焦点），再**把已有焦点踢掉** —— 原版 Screen 分发按键时
     * 不看 active，留着焦点还能往一个点不动的框里打字。
     */
    private fun applyIvInputLock() {
        val locked = ivInputsLocked()
        editIvFields.forEach { f ->
            f.active = !locked
            if (locked && focused === f) focused = null
        }
    }

    private fun cycleMinIvs() {
        // 六项已逐项指定就不给动 —— 两者互斥，能点的话只会把保底静默丢弃（同性别按钮：不是藏起来，是不给切）
        if (hasIvInput()) return
        editMinIvs = (editMinIvs + 1) % 7
        refreshEditLabels()
    }

    private fun confirmRemoveSlot() {
        if (editSlot < 0) return
        sendToServer(RemovePointMarketEntryPayload(editSlot))
        closeEditDialog()
    }

    // ── 窗口：展开式选项列表（形态 / 性格 / 特性 / 球 / 太晶 / 技能 共用）──

    /**
     * 展开一个选项列表。**展开时窗口其余控件全部隐藏** —— 它们先于列表按钮添加，
     * 不隐藏的话点击会被它们拦截、列表永远点不到（黑名单那儿踩过）。
     */
    private fun openOptionList(kind: String) {
        optionListAll = buildOptionItems(kind)
        // 携带物一进来就是空的（搜索式，见 buildCarryItems），不能当成"这个字段没有候选"
        if (optionListAll.isEmpty() && kind != OPT_CARRY) {
            playFailSound()
            return
        }
        optionListKind = kind
        optionListMultiSelect = kind == OPT_MARKS
        optionSearchText = ""
        applyOptionFilter()
        buildOptionSearchField()
        rebuildOptionList()
    }

    private fun closeOptionList() {
        optionSearchField?.let { remove(it) }
        optionSearchField = null
        optionSearchText = ""
        optionListAll = emptyList()
        optionListItems = emptyList()
        optionListKind = null
        optionListButtons.forEach { remove(it) }
        optionListButtons.clear()
        optionListScroll = 0
        updateEditControlsVisibility()
        refreshEditLabels()
    }

    /**
     * 搜索词过滤（**纯本地**：候选本来就是客户端现成的，不用走服务端的列表请求那套节流）。
     * 中文名直接 `contains`，英文技能名大小写不敏感。
     */
    private fun applyOptionFilter() {
        // 携带物是**搜索式**的：列表内容本身跟着搜索词重建（不是"过滤一份预生成的列表"）
        if (optionListKind == OPT_CARRY) {
            optionListAll = buildCarryItems(optionSearchText)
            optionListItems = optionListAll
            optionListScroll = 0
            return
        }
        val q = optionSearchText.trim()
        optionListItems = if (q.isEmpty()) optionListAll
        else optionListAll.filter { it.label.contains(q, ignoreCase = true) }
        // 过滤后行数变了，滚动位置要回到顶（不然停在一个空白的偏移上）
        optionListScroll = 0
    }

    /** 列表上方的搜索框（列表展开期间才有，关列表时一并移除） */
    private fun buildOptionSearchField() {
        val field = TextFieldWidget(
            textRenderer,
            editDialogX() + EDIT_PAD, editDialogY() + OPTION_SEARCH_Y,
            editDialogW() - EDIT_PAD * 2, OPTION_SEARCH_H,
            Text.literal("")
        )
        // 复用全项目统一的搜索框占位词条（黑名单、市场那些列表都是它）
        field.setPlaceholder(Text.translatable("cobblemarket.gui.search"))
        field.setMaxLength(48)
        field.setChangedListener { text ->
            optionSearchText = text
            applyOptionFilter()
            rebuildOptionList()
        }
        field.text = optionSearchText
        optionSearchField = field
        addSelectableChild(field)
        addDrawableChild(field)
    }

    /**
     * 窗口控件的显隐**统一入口**：展开列表时全藏，否则按滚动可见性。
     * 各写一遍必然漏 —— 展开完列表恢复时会把滚出视野的按钮也一起显示出来。
     */
    private fun updateEditControlsVisibility() {
        val open = optionListKind != null
        editSpeciesField?.visible = !open
        editPriceField?.visible = !open
        editStockField?.visible = !open
        // 滚动区里的输入框：按各自行号的可见性来（见 [scrollableFields]）
        scrollableFields().forEach { (field, key) ->
            field?.visible = !open && editRowVisible(editRowOf(key))
        }
        editConfirmButton?.visible = !open
        editCancelButton?.visible = !open
        editRemoveButton?.visible = !open
        // 清空按钮：跟 open 走就行（**不跟候选数量走** —— 那是箭头的事，清空随时都能点）
        editSpeciesClear?.visible = !open
        editButtons.forEach { (key, btn) -> btn.visible = !open && editRowVisible(editRowOf(key)) }
        // 列表的搜索框：**列表展开时才在**，显式跟着 open 走（别指望它默认 true 一直有效）
        optionSearchField?.visible = open
        // 候选箭头同理：它们也压在列表上（用户实测：选形态时两枚箭头跑到列表界面里了）
        updateEditSpeciesArrows()

        // ⚠ **兜底**：弹窗期间，凡是从 [editWidgetBase] 起建出来、又不在上面那份清单里的控件，
        //   一律隐藏 —— 逐个列名字隐藏必然有漏（上一条注释就是踩过的坑）。
        //   列表自己的控件（搜索框 / 返回）不在兜底范围内，它们本来就该显示。
        if (open) {
            val listOwn = buildSet<net.minecraft.client.gui.widget.ClickableWidget> {
                optionSearchField?.let { add(it) }
                addAll(optionListButtons)
            }
            children().drop(editWidgetBase)
                .filterIsInstance<net.minecraft.client.gui.widget.ClickableWidget>()
                .filter { it !in listOwn }
                .forEach { it.visible = false }
        }
    }

    private fun rebuildOptionList() {
        optionListButtons.forEach { remove(it) }
        optionListButtons.clear()
        val open = optionListKind != null
        updateEditControlsVisibility()
        if (!open) return
        // 行本身**自绘**（见 [renderOptionRows]：要在行首画物品图标，而 NineSliceButton 只收贴图 id）。
        // 这里只建底部那几枚 ——列表展开时其余按钮全藏了，没它们就只能按 Esc 退出。
        val btnY = editDialogY() + editDialogHeight() - EDIT_BUTTON_H - EDIT_PAD
        // 多选列表（证章）多一枚「清空」：挂了好几个时不用一个个点掉（2026-09-20 用户提议）。
        // 清空**不关列表**（清完往往还要接着挑），所以调用后不 closeOptionList。
        if (optionListMultiSelect) {
            val totalW = EDIT_BUTTON_W * 2 + 8
            val startX = editDialogX() + (editDialogW() - totalW) / 2
            val clear = NineSliceButton(
                startX, btnY, EDIT_BUTTON_W, EDIT_BUTTON_H,
                Text.translatable("cobblemarket.points.store.clear"), { clearMarks() }
            )
            // 一个都没挂时置灰（没什么可清的）
            clear.active = editMarks.isNotEmpty()
            optionListButtons.add(clear)
            addDrawableChild(clear)
            val back = NineSliceButton(
                startX + EDIT_BUTTON_W + 8, btnY, EDIT_BUTTON_W, EDIT_BUTTON_H,
                Text.translatable("cobblemarket.gui.back"), { closeOptionList() }
            )
            optionListButtons.add(back)
            addDrawableChild(back)
            return
        }
        val back = NineSliceButton(
            editDialogX() + (editDialogW() - EDIT_BUTTON_W) / 2,
            btnY, EDIT_BUTTON_W, EDIT_BUTTON_H,
            Text.translatable("cobblemarket.gui.back"), { closeOptionList() }
        )
        optionListButtons.add(back)
        addDrawableChild(back)
    }

    /** 清空已挂的证章（多选列表底部的「清空」）—— **不关列表**，清完还能接着挑 */
    private fun clearMarks() {
        editMarks = emptyList()
        refreshEditLabels()
    }

    /** 列表一屏能放几行：**撑满窗口**（顶部到「返回」按钮上方），别在下面空一大片 */
    private fun optionListRows(): Int =
        ((editDialogHeight() - OPTION_LIST_TOP - OPTION_LIST_BOTTOM) / OPTION_ROW_STEP).coerceAtLeast(1)

    /** 列表第 [i] 个可见行的矩形 —— **渲染与点击判定共用这一个**，各算一份必然错位 */
    private fun optionRowRect(i: Int) = com.shusheng.cobblemarket.client.TooltipPlacement.Rect(
        editDialogX() + EDIT_PAD,
        editDialogY() + OPTION_LIST_TOP + i * OPTION_ROW_STEP,
        editDialogW() - EDIT_PAD * 2,
        OPTION_ROW_H
    )

    /** 某行是不是「当前选中」：多选列表看已挂的证章，单选列表比当前值 */
    private fun isOptionSelected(value: String): Boolean =
        if (optionListMultiSelect) value in editMarks else value == currentOptionValue()

    private fun toggleMultiOption(value: String) {
        editMarks = if (value in editMarks) editMarks - value else editMarks + value
        refreshEditLabels()
        // 不关列表、也不用重建控件：行是自绘的，下一帧就带上新的勾选状态
    }

    private fun currentOptionValue(): String? = when (optionListKind) {
        OPT_FORM -> editAspects.joinToString(",")
        OPT_NATURE -> editNature
        OPT_ABILITY -> editAbility
        OPT_BALL -> editBall
        OPT_TERA -> editTera
        OPT_CARRY -> editHeldItem.orEmpty()
        // 证章是多选，没有单一「当前值」（选中判定走 isOptionSelected）
        OPT_MARKS -> null
        else -> optionListKind?.removePrefix(OPT_SKILL)?.toIntOrNull()
            ?.let { MoveText.slotIds(editMoves).getOrNull(it).orEmpty() }
    }

    private fun selectOption(value: String) {
        when (optionListKind) {
            OPT_FORM -> {
                editAspects = value.split(",").filter { it.isNotEmpty() }
                refreshEditPreview()
            }

            OPT_NATURE -> editNature = value
            OPT_ABILITY -> editAbility = value
            OPT_BALL -> editBall = value
            OPT_TERA -> editTera = value
            OPT_CARRY -> {
                editHeldItem = value.ifEmpty { null }
                editHeldItemName = optionListItems.firstOrNull { it.value == value }?.label.orEmpty()
                // 换了携带物，预览要跟着变（不然图标上还是旧的）
                refreshEditPreview()
            }

            else -> {
                val slot = optionListKind?.removePrefix(OPT_SKILL)?.toIntOrNull()
                if (slot != null) {
                    val ids = MoveText.slotIds(editMoves).toMutableList()
                    while (ids.size < MoveText.SLOTS) ids.add("")
                    ids[slot] = value
                    editMoves = MoveText.encode(ids)
                }
            }
        }
        closeOptionList()
    }

    /**
     * 某个字段的候选列表（值, 显示名）。
     * ⚠ 值一律存**游戏内的标识**（资源 id / 名字），显示名才走本地化 —— 反过来存会把中英两套数据搅在一起。
     */
    private fun buildOptionItems(kind: String): List<OptionItem> {
        val species = editSpecies
        return when (kind) {
            // 形态：默认（标准形态）+ 物种的各个 form；一个 form 对应一组 aspects
            OPT_FORM -> {
                val opts = mutableListOf(
                    OptionItem("", Text.translatable("cobblemarket.points.store.value.default").string)
                )
                // 超极巨化形态**不在候选里**：它是 battleOnly 形态，超极巨化由这枚窗口的
                // 「超极巨化」按钮（gmaxFactor）表达，见 SpeciesText.selectableForms
                SpeciesText.selectableForms(species).forEach { form ->
                    val aspects = form.aspects
                    if (aspects.isNotEmpty()) {
                        opts.add(
                            OptionItem(
                                aspects.joinToString(","),
                                aspects.joinToString("/") { SpeciesText.aspectLabel(it) }
                            )
                        )
                    }
                }
                opts.distinctBy { it.value }
            }

            // 性格：显示名走 Cobblemon 自己的词条（照市场筛选面板的写法）
            OPT_NATURE -> com.cobblemon.mod.common.api.pokemon.Natures.all().map { n ->
                val key = "cobblemon.nature.${n.name.path}"
                val t = Text.translatable(key).string
                OptionItem(n.name.toString(), if (t == key) n.displayName else t)
            }

            // 特性：该物种的全部可能特性（含隐藏特性）
            OPT_ABILITY -> species?.abilities?.toList().orEmpty().map { potential ->
                val ability = potential.template
                OptionItem(ability.name, Text.translatable(ability.displayName).string)
            }

            // 球种：**没有 displayName**，显示名走物品词条（`item.cobblemon.poke_ball`，与挂单详情同口径）；
            // 图标就是球物品自己的图标（画在行首，与其余界面的物品图标同一套）
            OPT_BALL -> com.cobblemon.mod.common.api.pokeball.PokeBalls.all().map { ball ->
                val id = ball.name.toString()
                OptionItem(
                    value = id,
                    label = Text.translatable("item.${ball.name.namespace}.${ball.name.path}").string,
                    stack = itemStackOf(id),
                    // 球名带**稀有度色**（大师球该是紫的），与物品栏里一致
                    labelText = com.shusheng.cobblemarket.util.ItemNameText.rarityColoredNameById(id)
                        ?: Text.translatable("item.${ball.name.namespace}.${ball.name.path}")
                )
            }

            // 太晶：名字按**属性色**着色（与精灵/技能的属性着色同一张表）。
            // ⚠ 太晶的 `id.path` 正好是属性名（normal / fire / …），直接喂 TypeTextColors；
            //   星晶那类查不到会落默认色，安全。**别用 `.displayName.string`** —— 转 String 样式全丢。
            OPT_TERA -> com.cobblemon.mod.common.api.types.tera.TeraTypes.toList()
                .map {
                    val label = it.displayName.string
                    OptionItem(
                        value = it.id.toString(),
                        label = label,
                        labelText = Text.literal(label)
                            .styled { s -> s.withColor(com.shusheng.cobblemarket.util.TypeTextColors.color(it.id.path)) }
                    )
                }

            // 携带物是**搜索式**的：内容随搜索词变（见 [buildCarryItems] / [applyOptionFilter]），
            // 这里不预生成列表 —— 开列表时是空的，靠搜索框找
            OPT_CARRY -> emptyList()

            // 证章：Cobblemon 的证章注册表（自带 32×32 图标与名字）。**多选** ——
            // 一只精灵能同时挂好几个，所以这张列表不关窗、点一下切一个
            OPT_MARKS -> com.cobblemon.mod.common.api.mark.Marks.all()
                .map {
                    OptionItem(
                        value = it.identifier.toString(),
                        label = it.getName().string,
                        iconTexture = it.texture
                    )
                }
                .sortedBy { it.label }

            // 技能：**默认解锁该物种的全部可学技能** —— 这只是服主完全自定义捏出来的，
            // 不该受「这只已经学过什么」限制（2026-09-20 用户明确）。
            // 名字走 MoveText.name（**按技能属性着色**，与悬停窗的技能块同一张色表）。
            // ⚠ 第一项是「无」= **清空该槽**（生成时这一槽随机补招）—— 内置选招屏没有这个动作，
            //   服主选完想撤掉就没处点了（2026-09-20 用户指出）
            else -> buildList {
                add(OptionItem("", Text.translatable("cobblemarket.points.store.value.none").string))
                addAll(
                    species?.moves?.getAllLegalMoves().orEmpty()
                        .map { OptionItem(it.name, it.displayName.string, labelText = MoveText.name(it.name)) }
                        .sortedBy { it.label }
                )
            }
        }
    }

    /**
     * 携带物候选：**搜索式**（照物品黑名单的添加对话框：ID / 中文名 / 附魔 / 招式都能搜）。
     *
     * ⚠ 不预先列背包 —— 服主要配的携带物不一定是身上那件（2026-09-20 用户明确）。
     * 值仍是**完整物品栈的 SNBT**（数据层 `heldItemSpec` 的口径）：带组件快照的候选
     * （附魔书 / 招式学习器）按快照重建，普通物品用默认栈。
     */
    private fun buildCarryItems(query: String): List<OptionItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val opts = mutableListOf(
            OptionItem("", Text.translatable("cobblemarket.points.store.value.none").string)
        )
        com.shusheng.cobblemarket.client.ItemCandidateResolver.resolve(q)
            .take(MAX_CARRY_CANDIDATES)
            .forEach { c ->
                val stack = carryStackOf(c) ?: return@forEach
                val snbt = encodeStackSnbt(stack) ?: return@forEach
                opts.add(
                    OptionItem(
                        value = snbt,
                        label = c.displayName,
                        stack = stack.copyWithCount(1),
                        // 物品名带**稀有度色**（附魔金、稀有品紫…），与物品栏里一致
                        labelText = ItemNameText.rarityColoredName(stack) ?: Text.literal(c.displayName)
                    )
                )
            }
        return opts.distinctBy { it.value }
    }

    /** 候选 → 物品栈（带组件快照的按快照重建；解析不出来返回 null，这一条就跳过） */
    private fun carryStackOf(c: com.shusheng.cobblemarket.client.ItemCandidate): ItemStack? = runCatching {
        if (c.componentsSpec == null) {
            Identifier.tryParse(c.itemId)
                ?.let { Registries.ITEM.getOrEmpty(it).orElse(null) }
                ?.let { ItemStack(it) }
        } else {
            ItemStack.fromNbtOrEmpty(
                client?.world?.registryManager ?: return null,
                NbtCompound().apply {
                    putString("id", c.itemId)
                    putInt("count", 1)
                    put("components", c.componentsSpec)
                }
            )
        }
    }.getOrNull()

    /** 资源 id → 单件物品栈（解析不出来返回 null，行首就不画图标） */
    private fun itemStackOf(itemId: String): ItemStack? = runCatching {
        Identifier.tryParse(itemId)
            ?.let { Registries.ITEM.getOrEmpty(it).orElse(null) }
            ?.let { ItemStack(it) }
    }.getOrNull()

    /** 物品栈 → SNBT 串（客户端只能编码**同步过来的**组件，服务端收到后还会自己重建一遍） */
    private fun encodeStackSnbt(stack: ItemStack): String? = runCatching {
        stack.encode(client?.world?.registryManager ?: return null).toString()
    }.getOrNull()

    // ── 窗口：渲染 ──

    /** 画窗口背景与静态文字（**作为第一个 Drawable 添加**，才画在原版控件之下） */
    private fun renderEditDialogBackground(
        context: DrawContext, mouseX: Int, mouseY: Int, delta: Float
    ) {
        val x = editDialogX()
        val y = editDialogY()

        // 遮罩：压暗下层（不压的话下面那排格子会跟弹窗抢注意力）
        drawScreenDimMask(context, width, height)
        // ⚠ 高度必须用 [editDialogHeight]（钳制后的实际值），**不能传设计常量** ——
        //   逻辑屏不足 EDIT_DIALOG_H + 8 时窗口会矮一截，背景照常量画就会往下冒出窗口底边（2026-09-20 修）
        drawNineSlice(
            context, DIALOG_BACKGROUND_TEXTURE, x, y, editDialogW(), editDialogHeight(), 0, DIALOG_BACKGROUND_TEX_H
        )

        // 标题：列表展开时换成「选择 XX」（同一个位置只有一个标题，不叠字）
        val listKind = optionListKind
        val title = if (listKind != null) {
            Text.translatable(
                "cobblemarket.points.store.pick",
                Text.translatable(optionListShortKey(listKind)).string
            )
        } else {
            // 精灵 / 物品两种窗口的标题分开（物品格子点开时写「添加精灵」就驴唇不对马嘴了）
            val pokemon = isPokemonSlot()
            Text.translatable(
                when {
                    editOriginal == null && pokemon -> "cobblemarket.points.store.add_title"
                    editOriginal == null -> "cobblemarket.points.store.add_item_title"
                    pokemon -> "cobblemarket.points.store.edit_title"
                    else -> "cobblemarket.points.store.edit_item_title"
                }
            )
        }
        context.drawCenteredTextWithShadow(
            textRenderer, title.copy().formatted(Formatting.GOLD), x + editDialogW() / 2, y + 10, 0xFFFFFF
        )

        // 提交失败提示：画在**标题右侧那片空位**（标题居中、到右缘还空着一大片，用户 2026-09-20 指出）——
        // 右端对齐到该行可用空间的右缘：编辑时避开右上角的「下架」按钮，新建时就直接是内容右缘。
        // ⚠ 跟着标题一起画在**列表展开之前**（展开选项列表时也得看得见，那时玩家往往正要去改）
        editHintMsg?.let { hint ->
            if (System.currentTimeMillis() < editHintUntil) {
                val rightLimit =
                    if (editRemoveButton != null) editRowRightX() - EDIT_REMOVE_W - 4 else editRowRightX()
                context.drawTextWithShadow(
                    textRenderer, hint, rightLimit - textRenderer.getWidth(hint), y + 10, 0xFF6666
                )
            }
        }

        if (listKind != null) {
            renderOptionRows(context, mouseX, mouseY)
            renderOptionListDecor(context, x, y)
            return
        }

        if (isPokemonSlot()) renderEditPreviewSlot(context, x, y, delta)

        // 等级那一格的「Lv.」前缀（用户 2026-09-20 要的「数字正好接上」）——
        // ⚠ 它跟字段网格一样在**滚动区**里：位置与显隐都得跟着那一行走，写死坐标一滚就露馅
        if (isPokemonSlot()) {
            val lvRow = editRowOf(KEY_LEVEL)
            if (editRowVisible(lvRow)) {
                context.drawTextWithShadow(
                    textRenderer, Text.translatable(LEVEL_LABEL_KEY),
                    editColX(editColOf(KEY_LEVEL)), editRowY(lvRow) + (EDIT_ROW_H - 8) / 2, 0xFFFFFF
                )
            }
        }

        // 价格 / 库存的标签（原版输入框自己画不了标签，位置与它对同一行）
        val labelY = editPriceRowY() + (EDIT_ROW_H - 8) / 2
        context.drawTextWithShadow(
            textRenderer, Text.translatable("cobblemarket.points.store.field.price"),
            editPriceGroupX(), labelY, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer, Text.translatable("cobblemarket.points.store.field.stock"),
            editPriceGroupX() + editColW() + EDIT_COL_GAP, labelY, 0xFFFFFF
        )
    }

    /** 物种预览槽：槽位贴图 + 精灵模型 + 候选计数（照黑名单的添加精灵弹窗） */
    private fun renderEditPreviewSlot(context: DrawContext, x: Int, y: Int, delta: Float) {
        // 预览区：右侧 3 格 × 上 2 行，槽位居中在里头、精灵再在槽里居中（比原来那个 24px 的小图标大得多）
        val slotSize = editPreviewSize()
        val slotX = editPreviewSlotX()
        val slotY = editPreviewSlotY()
        drawPokemonSlot(context, slotX, slotY, slotSize)

        val renderable = editPreview
        if (renderable != null) {
            val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
            try {
                PokemonIconRenderer.draw(
                    context = context,
                    renderable = renderable,
                    state = editPreviewState,
                    x = slotX, y = slotY, size = slotSize,
                    delta = delta,
                    animate = useFloat,
                    // 大图专用裁剪框：允许高出 / 横出槽位（见 editPreviewClip）
                    clip = editPreviewClip(slotY, slotSize)
                )
                // 开窗 / 换物种后自动叫一声：**必须在渲染之后** —— addFirstAnimation 依赖
                // currentModel，那是 draw 里才建好的（照其他弹窗的写法）
                if (editCryAt != 0L && System.currentTimeMillis() >= editCryAt) {
                    editCryAt = 0L
                    IconCryClick.play(editPreviewState, renderable)
                }
            } catch (_: Exception) {
            }
        }

        // 候选计数（1/3）：居中于物种输入框与预览槽之间那段空隙
        if (editSpeciesCandidates.size > 1) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal("${editSpeciesIndex + 1}/${editSpeciesCandidates.size}").formatted(Formatting.GRAY),
                // 预览槽挪去标题下方了，候选计数改贴在箭头右侧那段留白里（居中，右端与字段按钮对齐）
                editSpeciesRightX() - SPECIES_COUNT_W / 2, y + EDIT_SPECIES_Y + 3, 0xFFFFFF
            )
        }
    }

    /**
     * 展开列表的行 —— **自绘**（不用 NineSliceButton）：行首要画物品图标（球种、携带物），
     * 而按钮只支持贴图 id 的图标，球是物品、得走物品渲染。
     */
    private fun renderOptionRows(context: DrawContext, mouseX: Int, mouseY: Int) {
        val rows = optionListRows()
        // 搜索式列表（携带物）还没输入 / 没命中时：给一行提示，别留一片空白看着像坏了
        if (optionListItems.isEmpty()) {
            val r = optionRowRect(0)
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.store.search_item_hint").formatted(Formatting.GRAY),
                r.x + r.w / 2, r.y + (OPTION_ROW_H - 8) / 2, 0xFFFFFF
            )
            return
        }
        val hovered = hoveredOptionRow(mouseX.toDouble(), mouseY.toDouble())
        optionListItems.drop(optionListScroll).take(rows).forEachIndexed { i, opt ->
            val r = optionRowRect(i)
            // 行背景：与其余界面的列表行**同一张** `row_background.png`。
            // ⚠ 三段是 **0 普通 / 1 悬停 / 2 选中**，别拿 1 当选中用（项目里其他列表的 `1` 都是悬停）。
            // ⚠ 也别拿弹窗背景凑合 —— 那是给大窗口的，装饰条压到 16px 高的行上会糊成一条条红线。
            val state = when {
                isOptionSelected(opt.value) -> 2
                i == hovered -> 1
                else -> 0
            }
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, r.x, r.y, r.w, r.h, state, ROW_BACKGROUND_TEX_H)
            // 图标垂直居中（行高比图标高，留出上下余量，别顶在行边缘上）
            val iconY = r.y + (OPTION_ROW_H - OPTION_ROW_ICON_W) / 2
            val stack = opt.stack
            if (stack != null) {
                context.drawItem(stack, r.x + 1, iconY)
            } else opt.iconTexture?.let { tex ->
                // 贴图图标（证章）：原图 32×32，缩到行内高 —— 缩放走 matrices，别直接传目标尺寸
                context.matrices.push()
                context.matrices.translate((r.x + 1).toDouble(), iconY.toDouble(), 0.0)
                val s = OPTION_ROW_ICON_W / 32f
                context.matrices.scale(s, s, 1f)
                context.drawTexture(tex, 0, 0, 0f, 0f, 32, 32, 32, 32)
                context.matrices.pop()
            }
            // 选中态加「● 」前缀（项目统一的选中写法）；**不强制白色** —— 技能名要保持属性色
            var text = if (state == 2) Text.literal("● ").append(opt.labelText) else opt.labelText
            val maxW = r.w - 8
            // 超长才截断（截断后会变纯文本 —— 极少发生，技能名一般十来个字，行宽 240 放得下）
            if (textRenderer.getWidth(text) > maxW) text = Text.literal(TextUtil.truncateString(text.string, maxW))
            // 行很长，文字**居中**才好看；但**不能**用 drawCenteredTextWithShadow（它会让行首汉字
            // 抬高一点），所以自己算 x 再 drawText（混排文字的老坑，见项目约定）
            val drawX = r.x + (r.w - textRenderer.getWidth(text)) / 2
            context.drawTextWithShadow(textRenderer, text, drawX, r.y + (OPTION_ROW_H - 8) / 2, 0xFFFFFF)
        }
    }

    /** 展开列表的滚动条（条目装得下就不画） */
    private fun renderOptionListDecor(context: DrawContext, x: Int, y: Int) {
        val total = optionListItems.size
        val rows = optionListRows()
        if (total <= rows) return
        val top = y + OPTION_LIST_TOP
        val trackH = rows * OPTION_ROW_STEP
        val barX = x + editDialogW() - EDIT_PAD + 3
        context.fill(barX, top, barX + 1, top + trackH, 0x40FFFFFF)
        val thumbH = (trackH * rows / total).coerceAtLeast(6)
        val maxScroll = (total - rows).coerceAtLeast(1)
        val thumbY = top + (trackH - thumbH) * optionListScroll / maxScroll
        context.fill(barX - 1, thumbY, barX + 2, thumbY + thumbH, 0xFFFFFFFF.toInt())
    }

    // ── 窗口：按钮文案 ──

    private fun optionListShortKey(kind: String): String =
        if (kind.startsWith(OPT_SKILL)) "cobblemarket.points.store.short.skill"
        else "cobblemarket.points.store.short.$kind"

    private fun valueText(key: String): String = Text.translatable("cobblemarket.points.store.value.$key").string

    /** 值 → 显示名（从该字段的候选表里查；查不到就显示原值，绝不空着） */
    private fun optionLabelOf(kind: String, value: String): String {
        if (value.isEmpty()) return valueText("random")
        return buildOptionItems(kind).firstOrNull { it.value == value }?.label ?: value
    }

    /**
     * 刷新所有字段按钮的文案。
     *
     * ⚠ 标签与冒号**写进词条**（`field.xxx` = 「闪光：%s」），不在代码里拼 ": " ——
     * 中英标点不同（项目约定，见 CLAUDE.md 的界面文案约定）。
     */
    private fun refreshEditLabels() {
        // 三列下按钮很窄，值（物品名 / 技能名）可能很长 —— 统一截断，别让它溢出按钮外
        fun label(key: String, value: String, width: Int = editColW()): Text =
            Text.translatable(
                "cobblemarket.points.store.field.$key",
                TextUtil.truncateString(value, width - 8)
            )

        refreshShinyButton()
        editButtons[KEY_FORM]?.setMessage(label("form", formLabel()))
        refreshGenderButton()
        editButtons[KEY_NATURE]?.setMessage(label("nature", natureLabel()))
        editButtons[KEY_ABILITY]?.setMessage(label("ability", abilityLabel()))
        editButtons[KEY_BALL]?.setMessage(label("ball", ballLabel()))
        editButtons[KEY_SIZE]?.setMessage(label("size", sizeLabel()))
        refreshAlphaButton()
        // ⚠ 太晶的值要**带属性色**：不能走 label() 那条只收 String 的通道（一转 String 颜色就没了），
        //   直接拼 Text —— 与选项列表、悬停窗的技能块同一张色表（2026-09-20 用户实测：按钮上还是白字）
        editButtons[KEY_TERA]?.setMessage(
            Text.translatable("cobblemarket.points.store.field.tera", teraText())
        )
        editButtons[KEY_GMAX]?.setMessage(label("gmax", yesNoLabel(editGmax)))
        // 这个物种没有超极巨化形态 → 同性别按钮：**整个按钮置灰**（`dimmed` 盖黑遮罩，
        // 就是入口界面喵喵银行关闭时那个做法；`active = false` 只是文字变暗，不够）
        editButtons[KEY_GMAX]?.dimmed = !speciesCanGmax()
        editButtons[KEY_TRADEABLE]?.setMessage(label("tradeable", yesNoLabel(editTradeable)))
        // 携带物 / 球种：**标签 + 物品图标**（「携带物：[图标]」）——
        // 标签只留字段名不带值（值由图标表达，这才是去文字化的本意）；没设时只有标签
        editButtons[KEY_HELD]?.setMessage(label("held", ""))
        editButtons[KEY_HELD]?.iconStack = stackOfSpec(editHeldItem).takeIf { !it.isEmpty }
        editButtons[KEY_BALL]?.setMessage(label("ball", ""))
        editButtons[KEY_BALL]?.iconStack = itemStackOf(editBall)
        editButtons[KEY_MIN_IVS]?.setMessage(label("min_ivs", minIvsLabel()))
        // 「保底 V 数」与「六项个体值」**双向互斥**（用户 2026-09-20 拍板）：
        // 六项填了 → 保底按钮置灰点不动；保底 > 0 → 六项输入框整个锁住。两个方向都在这里统一落地
        editButtons[KEY_MIN_IVS]?.dimmed = hasIvInput()
        applyIvInputLock()
        editButtons[KEY_HELD_VISIBLE]?.setMessage(label("held_visible", yesNoLabel(editHeldVisible)))
        editButtons[KEY_MARKS]?.setMessage(label("marks", marksLabel()))
        // 物品窗口只有这一项（精灵窗口没有这个按钮，`?.` 天然跳过）
        editButtons[KEY_ITEM]?.setMessage(
            label("item", if (editItemId.isEmpty()) valueText("none") else editItemName)
        )

        // 四个技能槽：显示该槽已有的技能名（走 MoveText 的属性色），空槽显示「空」
        val skillIds = MoveText.slotIds(editMoves)
        for (slot in 0 until MoveText.SLOTS) {
            val id = skillIds.getOrNull(slot).orEmpty()
            editButtons["$KEY_SKILL$slot"]?.setMessage(
                Text.translatable(
                    "cobblemarket.points.store.field.skill",
                    (slot + 1).toString(),
                    if (id.isEmpty()) Text.literal(valueText("empty")) else MoveText.name(id)
                )
            )
        }
    }

    private fun yesNoLabel(value: Boolean): String = valueText(if (value) "yes" else "no")

    /**
     * 头目按钮：**图标化**（用列表行里那张 alpha 徽章）—— 是就显示徽章，否留空（靠 tooltip 说明）。
     * 这是项目 1.1.x 起的去文字化方向：能上图标就别写「头目：是」。
     */
    private fun refreshAlphaButton() {
        val btn = editButtons[KEY_ALPHA] ?: return
        // 「头目：图标」—— 徽章**一直在**（别做成没有就空白，那样看不出这格是干什么的），
        // 靠按钮的**选中态**表示「是」（selectedVisual 走贴图第三段）
        // 标签词条里 `%s` 填空串 = 只留「头目：」（值由图标 + 选中态表达）
        btn.setMessage(Text.translatable("cobblemarket.points.store.field.alpha", ""))
        btn.iconLeft = ALPHA_BADGE_ICON
        btn.selectedVisual = editAlpha
    }

    /**
     * 闪光按钮：**图标化**（与头目 / 性别同一套去文字化做法，用户 2026-09-20 定）。
     *
     * 记号就取其他界面那个闪光星标 `★`（[EntryBadgeRenderer] 里闪光一律追加金色 ★），三态：
     * - **必闪**（≥100%）= **金色** ★
     * - **概率**（其余正数）= **金色** ★ + 「：1%」
     * - **不闪**（空 / 0）= 灰色 ★
     *
     * ⚠ 星色判据是「**填了概率就是金色**」，不是「只有必闪才金色」—— 概率那档原先被我写成灰星了
     *   （2026-09-20 用户指出）。灰星只留给「不闪」这一种状态。
     *
     * 星标**一直都在** —— 别做成没有就空白，那样看不出这格是干什么的（头目按钮同理）；
     * 三种状态靠**颜色**与**右侧数字**区分，右侧那个输入框仍是概率的唯一真值。
     *
     * ⚠ 分段**平级挂在空 Text 下**：金色绝不能当父节点，否则冒号与百分比跟着变金（项目文案铁律）。
     */
    private fun refreshShinyButton() {
        val btn = editButtons[KEY_SHINY] ?: return
        val raw = editShinyText.trim()
        val percent = raw.toFloatOrNull()
        val always = percent != null && percent >= 100f
        // 「不闪」= 没填或填 0（0% 闪光就是不闪，别画成金色）
        val hasShiny = percent != null && percent > 0f
        val star = Text.literal(SHINY_STAR)
            .formatted(if (hasShiny) Formatting.GOLD else Formatting.DARK_GRAY)
        btn.setMessage(
            // 概率：金色星 + 冒号 + 百分比。冒号走词条 —— 中文全角 / 英文半角加空格（项目文案约定）
            if (hasShiny && !always) {
                Text.literal("")
                    .append(star)
                    .append(Text.translatable("cobblemarket.points.store.shiny_chance_suffix", "$raw%"))
            } else {
                star
            }
        )
    }

    private fun formLabel(): String =
        if (editAspects.isEmpty()) valueText("default")
        else editAspects.joinToString("/") { SpeciesText.aspectLabel(it) }

    /**
     * 性别按钮：**图标化**（沿用项目 1.1.x 起的去文字化方向，不再写「性别：雄」）。
     * 雄性 ♂ / 雌性 ♀ / 不限 ♂♀ 并排；**无性别没有对应图标**，只有这时才退回文字。
     */
    private fun refreshGenderButton() {
        val btn = editButtons[KEY_GENDER] ?: return
        // 只有一种可能的性别（神兽那种无性别 / 单性别物种）→ **整个按钮置灰**。
        // ⚠ 用 `dimmed`（整块盖半透明黑），**不是** `active = false` ——
        //   后者只把文字/图标调成 0xA0A0A0，按钮底图原样，看着只是"字有点暗"。
        //   模板就是入口界面「喵喵银行关闭」时那个按钮（2026-09-20 用户指出）
        btn.dimmed = availableGenderIndices().size <= 1
        when (GENDER_VALUES.getOrNull(editGender).orEmpty()) {
            "MALE" -> {
                btn.iconLeft = GENDER_ICON_MALE; btn.iconLeft2 = null; btn.setMessage(Text.literal(""))
            }

            "FEMALE" -> {
                btn.iconLeft = GENDER_ICON_FEMALE; btn.iconLeft2 = null; btn.setMessage(Text.literal(""))
            }

            "GENDERLESS" -> {
                btn.iconLeft = null; btn.iconLeft2 = null
                btn.setMessage(Text.translatable("cobblemarket.points.store.value.genderless"))
            }

            else -> {
                btn.iconLeft = GENDER_ICON_MALE; btn.iconLeft2 = GENDER_ICON_FEMALE
                btn.setMessage(Text.literal(""))
            }
        }
    }

    private fun natureLabel(): String = optionLabelOf(OPT_NATURE, editNature)

    private fun abilityLabel(): String = optionLabelOf(OPT_ABILITY, editAbility)

    private fun ballLabel(): String = optionLabelOf(OPT_BALL, editBall)

    /**
     * 太晶属性的**带色文本**（按属性色，与选项列表同一口径）。
     * ⚠ 值存的是资源 id（`cobblemon:water`），`:` 后面那截正好是属性名 —— 直接喂 TypeTextColors。
     */
    private fun teraText(): Text {
        if (editTera.isEmpty()) return Text.translatable("cobblemarket.points.store.value.random")
        val name = com.cobblemon.mod.common.api.types.tera.TeraTypes.toList()
            .firstOrNull { it.id.toString() == editTera }?.displayName?.string ?: editTera
        return Text.literal(name)
            .styled { s -> s.withColor(com.shusheng.cobblemarket.util.TypeTextColors.color(editTera.substringAfter(':'))) }
    }

    private fun teraLabel(): String = optionLabelOf(OPT_TERA, editTera)

    private fun sizeLabel(): String =
        PokemonSizeCategory.entries.getOrNull(editSize - 1)?.name ?: valueText("random")

    private fun minIvsLabel(): String = if (editMinIvs <= 0) valueText("any") else "${editMinIvs}V"

    private fun heldLabel(): String = if (editHeldItem == null) valueText("none") else editHeldItemName

    /** 证章：按钮上只说挂了几个（名字长、还能挂多个，具体是哪些进列表看） */
    private fun marksLabel(): String =
        if (editMarks.isEmpty()) valueText("none") else editMarks.size.toString()

    private fun doRedeem() {
        val code = codeField?.text?.trim().orEmpty()
        if (code.isEmpty()) {
            playFailSound()
            return
        }
        // 不在这里清空：失败时玩家还要改那串码；成功由 onMarketResult 清
        sendToServer(RedeemPointCodePayload(code))
    }

    /** 商城快照到了：切换「购买 RP」入口的显隐（**不重建界面**，免得清掉已输入的内容） */
    fun onShopInfo() {
        buyEntryButton?.visible = PointsShopCache.tiers.isNotEmpty()
        // 购买入口的可见性变了 → 记录按钮跟着换位置（**不重建界面**，见 layoutRecordButton）
        layoutRecordButton()
    }

    /**
     * 兑换结果（服务端 MarketResultPayload 到达；成功才清空输入框 —— 码已用掉，留着没用）。
     *
     * ⚠ **成功与失败都要给出可见反馈**：此前只清空输入框 / 什么都不做，玩家输完码只听到一声音效，
     * 根本不知道兑换成没成、错在哪（2026-09-19 用户实测）。服务端的 message 里本来就写清了原因
     * （成功给了多少 / 码无效 / 冷却中），照其他界面发到聊天栏即可。
     */
    fun onMarketResult(payload: MarketResultPayload) {
        if (payload.success) {
            codeField?.text = ""
            pendingCodeText = ""
        }
        // 界面内提示（5 秒）：玩家正盯着兑换框，只发聊天栏容易被界面挡着漏看
        resultMsg = payload.message
        resultColor = if (payload.success) 0x55FF55 else 0xFF6666
        resultUntil = System.currentTimeMillis() + 5000
        client?.player?.sendMessage(
            payload.message.copy().formatted(if (payload.success) Formatting.GREEN else Formatting.RED),
            false
        )
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val bg = bgRect()
        // ⚠ drawTexture 的 width/height **同时是「从源图采样的区域尺寸」**（1:1 采样），
        // 不是目标缩放尺寸 —— 直接传缩放后的目标尺寸，只会把原图左上角那一块抽出来放大
        // （2026-09-18 用户实测踩到：「只剩整张图的左上角」）。
        // 缩放必须走矩阵：按原图尺寸绘制、再整体缩到目标大小（同入口掉落动画的做法）。
        context.matrices.push()
        context.matrices.translate(bg.x.toDouble(), bg.y.toDouble(), 0.0)
        context.matrices.scale(bgScale(), bgScale(), 1f)
        context.drawTexture(BACKGROUND, 0, 0, 0f, 0f, TEX_W, TEX_H, TEX_W, TEX_H)
        context.matrices.pop()

        // 兑换结果提示：水平居中、落在**返回按钮底边与背景底边之间**那段空隙的中点
        // （用户 2026-09-19 指定）。此前画的是**背景正中** —— 压着界面内容很难看，
        // 注释写着「返回按钮与背景底边之间」但代码没落实，这次补上。
        // ⚠ 传**原来的 Text**，别转成 String 再拼 —— 结果里带 RP 图标字形（字体挂在 Text 样式上），
        //   一转 String 图标就变成方块（本项目反复踩过的坑）
        resultMsg?.let { msg ->
            if (System.currentTimeMillis() < resultUntil) {
                // 返回按钮底边 = bg 底边往上 BACK_BOTTOM（原图口径），这段空隙的中心再上移半行字高
                val slotCenterY = bg.y + bg.h - texPx(BACK_BOTTOM) / 2
                context.drawCenteredTextWithShadow(
                    textRenderer, msg,
                    bg.x + bg.w / 2,
                    slotCenterY - 4,
                    resultColor
                )
            }
        }
    }

    /**
     * 标题 / 两条红色分割线 / 商品区网格。
     *
     * ⚠ **弹窗开着时一律不画** —— 这几样是画在 `super.render()`（也就是全部控件）**之后**的，
     * 不跳过的话标题与网格会整个盖在弹窗上（2026-09-20 用户实测两次抓出）。
     * 这是项目里的既有做法，见 `BlacklistScreen.render` 的 `if (addField != null) return`。
     */
    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // ⚠ **必须在最开头**：清空本帧的图标注册表，并记下当前鼠标坐标。
        //   坐标是**头部跟随鼠标**的唯一来源 —— 漏调的后果是 `mouseX/mouseY` 恒为 0，
        //   本界面所有图标都盯着屏幕左上角一动不动（2026-09-20 用户报「网格与编辑窗的精灵
        //   都不跟随，而其他界面都跟」）。注册表还兼着**点击叫声**的命中判定（见 [IconRegistry]
        //   的注释），不清空会逐帧累积、点到别处的图标。
        //   全项目其余 11 个界面都在 render 开头调它，本界面当初漏了。
        IconRegistry.beginFrame(mouseX, mouseY)
        super.render(context, mouseX, mouseY, delta)
        if (editSlot >= 0) return
        drawMarketDecor(context, delta)
    }

    private fun drawMarketDecor(context: DrawContext, delta: Float) {
        val bg = bgRect()
        // 标题：与其余界面同一套写法（**金色样式挂在 Text 上**，绘制行色仍是白 ——
        // 直接把行色写成金色是错的，那样整行都是金的、与全模组颜色模板不符）
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.market.title", CurrencyService.pointsTitleIcon())
                .formatted(Formatting.GOLD),
            bg.x + bg.w / 2, bg.y + texPx(TITLE_TOP), 0xFFFFFF
        )
        // 两条红色分割线：**夹出中间的商品区**（服主上架的精灵/物品放这里）。
        // 颜色与入口界面/管理面板那两条统一走 GuiColors.DIVIDER（1px 粗，跨度左右避开背景边框，
        // 不盖住边框上的装饰 —— 用户 2026-09-18 定）。
        // 长度：避开边框的基础上，左右各再向边框方向延长 DIVIDER_EXTEND
        val lineLeft = bg.x + texPx(DIVIDER_BORDER_X) - DIVIDER_EXTEND
        val lineRight = bg.x + bg.w - texPx(DIVIDER_BORDER_X) + DIVIDER_EXTEND
        val lineTop = dividerTopY(bg)
        val lineBottom = dividerBottomY(bg)
        val divider = com.shusheng.cobblemarket.client.GuiColors.DIVIDER
        context.fill(lineLeft, lineTop, lineRight, lineTop + 1, divider)
        context.fill(lineLeft, lineBottom, lineRight, lineBottom + 1, divider)

        // 商品区网格：格子尺寸固定、行列按可用空间算、整体居中。
        // **悬停的那个格子最后单独画**（放大后浮在邻居之上），前面的循环跳过它。
        val layout = gridLayout(bg)
        val cellCount = layout.cols * layout.rows
        for (i in 0 until cellCount) {
            if (i == hoveredCell) continue
            drawShopCell(context, layout, i, delta, hover = false)
        }
        if (hoveredCell in 0 until cellCount) {
            drawShopCell(context, layout, hoveredCell, delta, hover = true)
        }
    }

    /** 弹窗开着时鼠标移动到不同格子不该再触发悬停放大（放大动画在弹窗底下闪没意义） */
    private fun freezeHover(): Boolean = editSlot >= 0

    // ── 商品区：格子内容 ──

    /**
     * 画一个格子：底图 + 内容（精灵 3D 图标 / 物品图标）+ 价格 + 库存角标。
     * 空格子只有底图 —— 服主点它上架，玩家点了没反应。
     */
    private fun drawShopCell(
        context: DrawContext, layout: GridLayout, cell: Int, delta: Float, hover: Boolean
    ) {
        val x = layout.cellX(cell)
        val y = layout.cellY(cell)
        val size = layout.cell
        // 悬停的格子放大后**仍然居中于原格子**（两边各外扩一半），且只放大绘制、不动别的格子位置
        val grow = if (hover) (((size * HOVER_SCALE) - size).roundToInt() / 2) else 0
        drawNineSlice(
            context, DIALOG_BACKGROUND_TEXTURE,
            x - grow, y - grow, size + grow * 2, size + grow * 2, 0, DIALOG_BACKGROUND_TEX_H
        )

        val entry = PointMarketCache.slots[globalSlot(cell)] ?: return
        val icon = cellIcon(globalSlot(cell), entry)

        // 内容跟着**当前画出来的那个格子**走（悬停时 drawSize 就是放大后的边长）——
        // 只放大底图、内容原地不动的话，放大后的格子里精灵会缩在中间显得很小。
        // ⚠ 价格与数量是例外：它们只"贴边"不放大（字号不动），文字用矩阵缩放会发糊。
        val drawSize = size + grow * 2
        val drawX = x - grow
        val drawY = y - grow

        // 图标区：上边距取边长 8% **再下移 [ICON_TOP_NUDGE]**，下边到价格行上方，再按比例取小值 ——
        // 小格子里自动缩小、不压到价格（写死比例的话，格子小于约 55px 就会叠在一起）
        //
        // ⚠ 那个 4px 的下移是必要的：格子上边框才 6px 宽，而 8% 的上边距（70px 格子约 6px）
        //   正好把图标顶到边框内缘，3D 模型再往上溢出 4px 就**盖住边框**了（2026-09-20 用户指出）。
        //   代价：底部让给价格行、动不了，所以图标会按比例小一点点（70px 格子约小 3px）。
        val iconTop = drawY + (drawSize * ICON_TOP_RATIO).roundToInt() + ICON_TOP_NUDGE
        val iconBottom = drawY + drawSize - PRICE_BOTTOM_GAP - 2
        val iconSize = (iconBottom - iconTop)
            .coerceAtMost((drawSize * ICON_SIZE_RATIO).roundToInt())
            .coerceAtLeast(MIN_ICON_SIZE)
        val iconX = drawX + (drawSize - iconSize) / 2
        val iconY = iconTop

        val renderable = icon.renderable
        val state = icon.state
        if (renderable != null && state != null) {
            // 槽背景：与列表 / 弹窗里的精灵图标同一张底图（模型直接浮在格子上会显得没有底座）
            drawPokemonSlot(context, iconX, iconY, iconSize)
            val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
            try {
                PokemonIconRenderer.draw(context, renderable, state, iconX, iconY, iconSize, delta, useFloat)
            } catch (_: Exception) {
            }
        } else {
            icon.stack?.let { stack ->
                val scale = iconSize / 16f
                context.matrices.push()
                // 中心与精灵图标**同一个**（iconX/iconY 已经按当前格子算过，别再拿正常格子算一遍）
                context.matrices.translate((iconX + iconSize / 2f).toDouble(), (iconY + iconSize / 2f).toDouble(), 0.0)
                context.matrices.scale(scale, scale, 1f)
                context.drawItem(stack, -8, -8)
                context.matrices.pop()
            }
        }

        // 价格：格子底部居中（走全模组统一的金额文本，自带 RP 图标字形）。
        // ⚠ 贴的是**当前画出来的那个格子**的下边框 —— 悬停时格子从 `y-grow` 放大到 `y+size+grow`，
        //   底边随之往下走，价格也得跟着，才是「始终紧贴格子内边框」；
        //   按正常格子的坐标写死的话，一悬停价格就浮在离下边框一截的位置（2026-09-20 用户指出）。
        //   上边距 12 = 文字高 8 + 离下边框 4（原来 10 会压到边框线上）
        val bottom = y + size + grow
        context.drawCenteredTextWithShadow(
            textRenderer,
            // 金额走**缩写**（`9,999,999` → `9.9M`）：格子就这么宽，完整价格会长到顶破整个格子
            //（用户 2026-09-20 报）。与列表界面同一个口径（[formatPriceShortLong]，它的注释就写着
            //「小格子用的价格缩写」），网格这边当初漏了。
            // ⚠ 缩写串是**字符串**喂进 amountText 的：`goldAmount → formatAmount` 对非数值类型
            //   **原样输出**（不套千分位），所以不会被二次加工；RP 图标仍由 unitText 补，没走字符串拼接
            CurrencyService.amountText(formatPriceShortLong(entry.price), MarketCurrency.POINTS),
            x + size / 2, bottom - PRICE_BOTTOM_GAP, 0xFFFFFF
        )

        // 库存角标：**只有限量格才画**（不限量是默认玩法，画个 ∞ 只是碍眼）；
        // 售罄时变红 0，格子**保留**（服主可以补货或换内容）。同样贴当前格子的右上角内边框
        if (entry.stock != ShopEntry.UNLIMITED) {
            context.drawTextWithShadow(
                textRenderer, Text.literal(entry.stock.toString()),
                x + size + grow - STOCK_BADGE_INSET, y - grow + 3,
                if (entry.isSoldOut()) 0xFF5555 else 0xFFFFFF
            )
        }
    }

    /** 格子图标的构造结果（缓存在 [cellIcons]） */
    private class CellIcon(
        val entry: ShopEntry,
        val renderable: RenderablePokemon? = null,
        val state: FloatingState? = null,
        val stack: ItemStack? = null
    )

    private val cellIcons = mutableMapOf<Int, CellIcon>()

    /**
     * 取格子的图标（没有就构造一份缓存）。
     *
     * ⚠ 缓存比对用**引用**（`===`）：快照一到就会重建整个 [ShopEntry] 对象树，
     * 引用变了就重建图标 —— 服主编辑完商品立刻能看到新预览，不用重开界面。
     */
    private fun cellIcon(index: Int, entry: ShopEntry): CellIcon {
        val cached = cellIcons[index]
        if (cached != null && cached.entry === entry) return cached
        val built = buildCellIcon(entry)
        cellIcons[index] = built
        return built
    }

    private fun buildCellIcon(entry: ShopEntry): CellIcon = when (entry.kind) {
        ShopEntry.KIND_POKEMON -> {
            val spec = entry.pokemon
            val species = spec?.species?.let { Identifier.tryParse(it) }
                ?.let { PokemonSpecies.getByIdentifier(it) }
            if (spec == null || species == null) CellIcon(entry)
            // 预览渲染：**固定闪光出闪光形态；概率闪光出默认形态**（概率只在悬停窗标出来）
            else CellIcon(
                entry = entry,
                // 携带物一起带上（同编辑预览）；**可见性关着的商品，格子里也不显示** ——
                // 那正是「别人看不到它带了什么」该有的效果
                renderable = RenderablePokemon(
                    species, spec.aspects.toMutableSet(),
                    if (spec.heldItemVisible) stackOfSpec(spec.heldItemSpec) else ItemStack.EMPTY
                ),
                state = FloatingState()
            )
        }

        ShopEntry.KIND_ITEM -> {
            val spec = entry.item
            CellIcon(entry, stack = spec?.let { rebuildItemStack(it) }?.takeIf { !it.isEmpty })
        }

        else -> CellIcon(entry)
    }

    /** 按规格重建物品栈（含组件）——重建不出来就是空图标，绝不崩 */
    private fun rebuildItemStack(spec: ShopItemSpec): ItemStack? = runCatching {
        val lookup = client?.world?.registryManager ?: return null
        ItemStack.fromNbtOrEmpty(
            lookup,
            net.minecraft.nbt.NbtCompound().apply {
                putString("id", spec.itemId)
                putInt("count", 1)
                spec.componentsSpec?.let { put("components", it) }
            }
        )
    }.getOrNull()

    private companion object {
        val BACKGROUND = Identifier.of("cobblemarket", "textures/gui/points_market_background.png")
        const val TEX_W = 1280
        const val TEX_H = 720

        /** 四周留白：给游戏画面留一圈（方案拍板 4%） */
        const val MARGIN_RATIO = 0.04f

        /**
         * 原图边框厚度：贴边控件（返回按钮、标题）避开它，别压到装饰条上。
         * 实测（扫描内容区包围盒）：左右 59px、顶部约 85px —— 取 90 统一兜住最厚的一边。
         */
        const val TEX_BORDER = 90

        /** 输入框字符上限：外部码 32 位按 4 位分组 = 39 字符，留一点余量 */
        const val MAX_CODE_INPUT = 48

        /** 标题距背景顶（原图 1280×720 口径，用户 2026-09-18 定） */
        const val TITLE_TOP = 28

        /** 返回按钮：底边距背景底（原图口径）、按钮尺寸 */
        const val BACK_BOTTOM = 75
        const val BACK_W = 50
        const val BACK_H = 16

        /**
         * 兑换区：输入框与按钮尺寸（输入框靠内容区左上角摆放）。
         * ⚠ 120 是**上限**，不是定值：GUI scale 调大后逻辑分辨率会小到放不下这一行，
         * 那时按剩余空间压缩（见 init 里的 `fieldW`）。下限给 40（约 6 个字符，输入框本来就会横向滚动）。
         */
        const val REDEEM_FIELD_W = 120

        /**
         * 兑换框的**下限宽度**。GUI scale 自动（逻辑宽只有 400 出头）时，这一行剩下的空间就是它 ——
         * 从 40 收到 24：那一档本来就只看得见三四个字符（输入框自己会横向滚动），
         * 但少了这 16px，档位 tab 就会顶到「兑换记录」上（用户实测的重叠就是这么来的）。
         */
        const val MIN_REDEEM_FIELD_W = 24

        /** 兑换行各控件之间的间隙（按钮间距规则：至少 4px） */
        const val ROW_GAP = 4

        /**
         * 兑换按钮宽 48：够放下英文 "Redeem"（约 36px）与中文「兑换」（约 18px），
         * 再宽两边就是空白 —— 2026-09-18 用户指出 56 太宽、还会挤到居中的 tab。
         */
        const val REDEEM_BUTTON_W = 48

        /**
         * 商品区网格：**行列固定、格子尺寸自适应**（铺满可用空间）。
         *
         * ⚠ 反过来做（格子固定、行列自适应）不行 —— 2026-09-18 用户实测：格子在大窗口下显得小，
         * 而且高度除不尽时会在上下留下大片空余（截图里 3 行只占了一半高度）。
         * 固定行列后格子会撑满每一寸空间，且格子尺寸随窗口一起变大。
         */
        const val GRID_COLS = 8
        const val GRID_ROWS = 4

        /**
         * 「拿行数换格子大小」的门槛：**按宽度算的格子比按高度算的大出这个比例时**，
         * 才放弃 [GRID_ROWS] 行、改成按宽度铺满 + 行数自适应。
         *
         * 为什么要分情况：可用区宽高比（1080p 下 2.14）比 8×4 网格的（2.06）还宽一点，
         * 所以「按宽铺满」必然掉一行。大分辨率下那点差距只换来 4% 的格子，不值；
         * 小分辨率（GUI scale 调大）下能换来 40% —— 480×270 时按高度算每格只有 27px，
         * 图标小、左右还白空一大片（2026-09-18 用户实测）。
         */
        const val GRID_W_ROWS_TRADE_RATIO = 1.1f

        /**
         * 间隔 = 格子 × 该比例（不写死像素：大小窗口下观感才一致）。
         * 取 0.3 是因为**悬停放大**要留余量 —— 放大 [HOVER_SCALE] 后每边外扩 (0.15/2)=7.5% 的格子，
         * 而间隔是 30%，扣掉两边外扩还剩 15% ⇒ **不会碰到上下左右的邻居**（用户要的效果）。
         */
        const val GRID_GAP_RATIO = 0.3f

        /** 悬停放大倍率：鼠标所在格子放大到这么大，并且**最后绘制**（浮在邻居之上） */
        const val HOVER_SCALE = 1.15f

        /** 格子内容离当前格子下边框的距离（含文字自身高度 8：即文字底边离边框 4） */
        const val PRICE_BOTTOM_GAP = 12

        /** 图标区上限：占当前格子边长的比例（大格子里图标不会顶满） */
        const val ICON_SIZE_RATIO = 0.7f

        /** 图标区上边距占边长的比例 */
        const val ICON_TOP_RATIO = 0.08f

        /**
         * 图标上边距在 [ICON_TOP_RATIO] 之外**再下移的像素** —— 避开格子上边框（才 6px 宽），
         * 给 3D 模型那 4px 的向上溢出留出地方。太大图标会明显变小，见 renderCell 的 iconTop。
         */
        const val ICON_TOP_NUDGE = 4

        /** 图标最小边长（再小就看不清了，宁可挤一点也不缩没） */
        const val MIN_ICON_SIZE = 8

        /** 库存角标离格子右边缘的距离 */
        const val STOCK_BADGE_INSET = 9

        /** 网格与上下两条分割线的间距（逻辑像素） */
        const val GRID_PADDING = 6

        /** 精灵 / 物品 tab：尺寸与两者之间的间隔 */
        const val TAB_W = 56
        const val TAB_H = 16
        const val TAB_GAP = 4

        /** 翻页按钮尺寸（照 MarketScreen 的搬来） */
        const val PAGE_BTN_W = 80
        const val PAGE_BTN_H = 20

        /**
         * 商品快照请求的**客户端限流**间隔：与服务端 `RequestThrottle.READ_INTERVAL_MS` 同值 ——
         * 发得比它密只会被静默丢掉，白费带宽（见 [tick] 的补发逻辑）。
         */
        const val PAGE_REQUEST_INTERVAL_MS = 500L

        /**
         * 右上角「购买 RP」入口宽度。
         *
         * ⚠ 2026-09-19 用户实测：**72 太宽**（文字只占 30~35px，两边全是空白），
         * GUI scale 调成自动时这一行挤不下，档位 tab 被兜底逻辑顶到「兑换记录」上（重叠）。
         * 收到 44：中文「购买 ▣」约 30px、英文 "Buy ▣" 约 35px，两边各留 4~7px。
         */
        const val BUY_ENTRY_W = 44

        /**
         * 「兑换记录」入口宽度（**与购买入口不同宽**）：英文 "History" 约 42px / 中文「兑换记录」36px，
         * 留 5~8px 内边距 → 52（原来跟着购买入口吃 72，白占 20px）。
         */
        const val RECORD_ENTRY_W = 52

        /** 红色分割线左右各避开的背景边框宽度（原图口径，用户 2026-09-18 定 78） */
        const val DIVIDER_BORDER_X = 78

        /** 在「避开边框」的基础上左右各再延长的量：**逻辑像素**（用户 2026-09-18 调） */
        const val DIVIDER_EXTEND = 2

        /** 兑换行高（输入框与按钮）：**逻辑像素** */
        const val ROW_H = 16

        /** 分割线与相邻控件（兑换行 / 返回按钮）的空隙：**逻辑像素** */
        const val DIVIDER_GAP = 6

        /**
         * 兑换行相对内容区顶再下移的量（逻辑像素）。
         * 用户 2026-09-18：照入口界面「求购单按钮顶边距红色分割线」那个间距（3px）往下挪一点。
         * 上分割线不跟着动 ⇒ 两者间距收窄到 `DIVIDER_GAP − ROW_TOP_OFFSET` = 3px。
         */
        const val ROW_TOP_OFFSET = 3

        // ── OP 添加 / 编辑窗口（尺寸固定：18 个字段两列排下来就是这么多，**不随内容伸缩**）──

        /**
         * 窗口高度的**设计值**（放不下按屏幕钳制，见 [editDialogHeight]）。
         *
         * 算式：`44`（[EDIT_ROWS_TOP]）+ 内容 9 行的最后一个行底（`8×19 + 16 = 168`）
         * + `6`（技能行与底部那组的分组间距）+ `44`（[EDIT_BOTTOM_BLOCK]）= **262**。
         *
         * ⚠ 原值 288 比内容高出 32px，全空在技能行与价格行之间（2026-09-20 用户指出，已收紧）。
         * 巧合但正好：自动 GUI（1080p）逻辑屏高 270 ⇒ `270 − 8 = 262`，
         * 所以**大屏小屏现在完全一致**（以前是小屏被钳到 262 才是理想布局）。
         */
        const val EDIT_DIALOG_H = 262

        /**
         * 窗口宽度的上下限：宽度**跟着 RP 市场背景走**（见 [editDialogW]），
         * 只在这两头收一下 —— 大屏上别摊太宽，小屏上别窄到字都放不下。
         */
        const val EDIT_DIALOG_MAX_W = 560
        const val EDIT_DIALOG_MIN_W = 300

        const val EDIT_PAD = 10

        /** 列宽的下限：列数由窗口宽度算出来（见 [editCols]），列宽不小于这个值 */
        const val EDIT_MIN_COL_W = 66
        const val EDIT_COL_GAP = 9

        /**
         * 行高与行距：**16 / 19**（标准按钮高度）。
         *
         * ⚠ 上两轮为了塞进 270 的逻辑屏高，行高压到过 14 —— 太扁了：物品图标是 16×16，
         * 顶在 14 高的按钮里上下全无余量（2026-09-20 用户指出）。宁可让矮屏滚一点点，
         * 也不要把按钮压扁：高屏上窗口本来就放得下，只有逻辑屏不足 279 时才滚十几像素。
         */
        const val EDIT_ROW_H = 16
        const val EDIT_ROW_STEP = 19

        /** 顺序表里的**换行标记**（"另起一行"），见 [editFieldOrder] */
        const val FIELD_BREAK = "\n"

        /** 窗口横向的**总格数**：左 3 格字段 + 右 3 格给预览图与个体/努力值（用户 2026-09-20 定的布局） */
        const val EDIT_COLS = 6

        /** 个体值 / 努力值在右侧区的起始行（个体值第 1 行与「性格」同行，努力值末行与「亲密度」同行） */
        const val EDIT_ROW_STAT = 2

        /** 携带物搜索一次最多给多少个候选（搜「球」这类宽词命中很多，够翻就行） */
        const val MAX_CARRY_CANDIDATES = 200

        /** 物种搜索行（相对窗口顶）：输入框 + 两枚候选箭头 + 「1/3」计数，位置与宽度都随窗口宽算 */
        const val EDIT_SPECIES_Y = 24
        const val EDIT_ARROW_W = 18

        /** 清空按钮（搜索框右侧第一个）的宽度 —— 与两枚候选箭头同宽同高，四个排成一溜 */
        const val SPECIES_CLEAR_W = 18

        /** 「1/3」候选计数占的宽度（右端与物种行右缘对齐，见 [editSpeciesRightX]） */
        const val SPECIES_COUNT_W = 26



        /** 字段网格从这一行开始（相对窗口顶）—— 上面是标题 / 预览 / 物种行（固定不滚） */
        const val EDIT_ROWS_TOP = 44

        /**
         * 字段网格**没有固定行号表** —— 位置一律由 [editFieldOrder] + [editCols] 流式算出来
         *（列数随窗口宽度变，写死行号必然错位）。
         * 价格/库存也不在这个网格里，它们钉在窗口底部（见 [editPriceRowY]）。
         */

        /** 六项属性的占位名（与黑名单的个体值输入框同一套缩写；努力值那组加 EV- 前缀区分） */
        val STAT_LABELS = listOf("HP", "ATK", "DEF", "SPA", "SPD", "SPE")
        const val STAT_COUNT = 6

        /** 底部固定块的高度：价格/库存行(16) + 间距(2) + 按钮行(18) + 底边距(8) */
        const val EDIT_BOTTOM_BLOCK = 44

        /** 底部按钮行（高度收到 18 才和顶部新增的预览行对得上账，见 EDIT_ROW_STEP 的注释） */
        const val EDIT_BUTTON_W = 80
        const val EDIT_BUTTON_H = 18

        /** 「下架」按钮（只在编辑已有内容时出现）的宽度；位置右上角、**距顶 = 距右**，见 [editRemoveY] */
        const val EDIT_REMOVE_W = 44

        /** 「标签 + 输入框」的列内布局（价格 / 库存那两格） */
        const val EDIT_LABEL_W = 32

        /** 预览大图裁剪框离弹窗边（上 / 右）留的空隙，见 [editPreviewClip] */
        const val PREVIEW_CLIP_MARGIN = 2

        /** 展开列表上方的搜索框（只在列表展开期间存在，关列表时一并移除） */
        const val OPTION_SEARCH_Y = 28

        /** 提交失败提示在弹窗内停留的毫秒数（与兑换结果提示同档） */
        const val EDIT_HINT_MS = 5000L
        const val OPTION_SEARCH_H = 16

        /**
         * 展开式选项列表：从搜索框下面起、行距 18px，**行数按窗口高度算满**（见 [optionListRows]）——
         * 写死行数会在底部空出一大片，而球种这类字段有几十项（2026-09-20 用户实测指出）。
         */
        const val OPTION_LIST_TOP = 48

        /** 选项行：行高 18 是为了让 16×16 的物品图标**上下各留 1 像素**（顶格会贴到行边缘） */
        const val OPTION_ROW_STEP = 20
        const val OPTION_ROW_H = 18

        /** 行首物品图标的宽度（球种 / 携带物用） */
        const val OPTION_ROW_ICON_W = 16

        /** 列表底部留给「返回」按钮的区域：按钮 20 + 间距 8 + 底边距 8 */
        const val OPTION_LIST_BOTTOM = 36

        /**
         * 球种的**默认值**：普通精灵球（用户 2026-09-20 定）。
         *
         * 原先空值走「随机」、提交时写 `null` ⇒ 生成的精灵球种不明（Cobblemon 内部会给个默认，
         * 但说不清是哪个）。现在三处都落这个值：新建（[resetDraft]）、编辑老数据（[fillDraft]）、
         * 提交兜底（[buildPokemonSpec]）—— **「不设置」就等于精灵球**，界面上也不再出现「随机」。
         */
        const val DEFAULT_BALL_ID = "cobblemon:poke_ball"

        /**
         * 努力值的**总和**上限 —— 取 **508**，不是 Cobblemon 的硬上限 510。
         *
         * 「**每 4 点 EV 才换 1 点能力值**」，所以 510 里最后那 2 点换不出任何东西：
         * `252/252/6`（510）与 `252/252/4`（508）的**战力完全相同**。
         * 卡 510 的唯一后果是让服主以为自己配满了；卡 508 不违反游戏规则（510 只是"存得下"），
         * 只是不给白填的机会。口径与 `oak_tips` 里那条 EV 说明一致（2026-09-20 用户指出）。
         */
        const val EV_TOTAL_MAX = 508

        /** 新建商品时**预填**的等级与亲密度（用户 2026-09-20 定）—— 清掉即回「随机 / 默认」 */
        const val DEFAULT_LEVEL = 1
        const val DEFAULT_FRIENDSHIP = 50

        // 字段按钮的 key（刷新文案与统一隐藏用）
        const val KEY_SHINY = "shiny"
        const val KEY_FORM = "form"
        const val KEY_GENDER = "gender"
        const val KEY_NATURE = "nature"
        const val KEY_ABILITY = "ability"
        const val KEY_BALL = "ball"
        const val KEY_SIZE = "size"
        const val KEY_ALPHA = "alpha"
        const val KEY_TERA = "tera"
        const val KEY_GMAX = "gmax"
        const val KEY_TRADEABLE = "tradeable"
        const val KEY_HELD = "held"
        const val KEY_MIN_IVS = "min_ivs"
        const val KEY_HELD_VISIBLE = "held_visible"
        const val KEY_MARKS = "marks"
        const val KEY_SKILL = "skill"

        /** 物品窗口的「卖什么物品」按钮（精灵窗口没有这一项） */
        const val KEY_ITEM = "item"

        // 展开式选项列表的种类（技能带槽位后缀，如 "skill0"）
        const val OPT_FORM = "form"
        const val OPT_NATURE = "nature"
        const val OPT_ABILITY = "ability"
        const val OPT_BALL = "ball"
        const val OPT_TERA = "tera"
        const val OPT_CARRY = "carry"
        const val OPT_MARKS = "marks"
        const val OPT_SKILL = "skill"

        // 性别 / 头目的**图标**（沿用项目既有素材，别再找新的）：
        // ♂♂♀♀ 是 Cobblemon 的 pc 图标；alpha 徽章与列表行里用的是同一张
        val GENDER_ICON_MALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
        val GENDER_ICON_FEMALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
        val ALPHA_BADGE_ICON = Identifier.of("cobblemon", "textures/gui/summary/icon_size_alpha.png")

        /** 性别档位（0 = 随机；值直接用 Cobblemon 的枚举名） */
        val GENDER_VALUES = listOf("", "MALE", "FEMALE", "GENDERLESS")

        /**
         * 闪光按钮的快捷档位（百分数原文，空串 = 不闪）：点一下就把这个值填进概率输入框。
         * 档位只是方便，真正的值以输入框为准 —— 服主想写 `0.5` 这种小数直接敲就行。
         */
        /**
         * 闪光档位按钮的**循环顺序**（用户 2026-09-20 定）：不闪 → 1% → 5% → 10% → 25% → 50% → 必闪。
         *
         * ⚠ 必闪排在**最后** —— 它是「100 及以上」那一档，语义上是这条链的终点，
         *   不该紧挨着「不闪」（原顺序是 `"", "100", "1"…`，点一下就从"不闪"直接跳到"必闪"）。
         */
        val SHINY_PRESETS = listOf("", "1", "5", "10", "25", "50", "100")

        /**
         * 闪光星标 —— **与列表行 / 悬停窗同一个记号**（[EntryBadgeRenderer] 里闪光一律追加 ` ★`）。
         * 颜色不在这里定：必闪金、不闪灰，见 [refreshShinyButton]。
         */
        const val SHINY_STAR = "★"

        /** 闪光拆成两格：按钮（显示当前档位）+ 概率输入框（支持小数）—— 挨着排，视觉上是一组 */
        const val KEY_SHINY_CHANCE = "shiny_chance"

        /** 六项个体值 / 努力值的字段前缀（`iv0`..`iv5` / `ev0`..`ev5`） */
        const val KEY_IV = "iv"
        const val KEY_EV = "ev"

        /** 两个输入框字段（在顺序表里有位置，但控件是输入框不是按钮） */
        const val KEY_LEVEL = "level"

        /**
         * 等级那一格的「Lv.」前缀词条 —— **与全项目等级行同一个**（`Lv.25` 那个写法，
         * 见 [EntryBadgeRenderer]），不另起词条，免得两处慢慢写岔。
         */
        const val LEVEL_LABEL_KEY = "cobblemarket.gui.lv"
        const val KEY_FRIENDSHIP = "friendship"
    }
}
