package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.util.MoveText
import com.shusheng.cobblemarket.util.StatNames
import com.shusheng.cobblemarket.util.TypeFilter
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.shusheng.cobblemarket.client.clampTooltipRows
import com.shusheng.cobblemarket.client.icon.PreviewStateHolder
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.client.requestCreditInfo
import com.shusheng.cobblemarket.network.*
import com.shusheng.cobblemarket.screen.SellSelectScreen
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import com.shusheng.cobblemarket.client.heldIconStack
import com.shusheng.cobblemarket.client.heldIconStackForModel
import com.shusheng.cobblemarket.client.PokemonCelebrationAnimation
import com.shusheng.cobblemarket.client.FilterPanelAnimation
import com.shusheng.cobblemarket.client.TooltipPlacement
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.PokemonHoverPreview
import com.shusheng.cobblemarket.client.icon.PokemonIconData
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.mojang.authlib.GameProfile
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MarketScreen : Screen(Text.translatable("cobblemarket.gui.title")) {

    private var listings = listOf<ListingEntry>()
    private var currentPage = 1
    private var totalPages = 1

    private var searchField: TextFieldWidget? = null
    // 搜索防抖状态：tick 里检查，停止输入 250ms 后才发请求
    private var searchDirty = false
    private var lastSearchEdit = 0L
    private var shinyOnly = false
    private var showMineOnly = false
    // 特训筛选三态：0 = 不限，1 = 仅含训练，2 = 仅不含训练
    private var htFilter = 0
    private var htButton: NineSliceButton? = null
    private var filterExpanded = false
    /** 面板开关动画（只作用于面板背景；面板内控件在动画期间先藏起来，见 [updateControlVisibility]） */
    private val filterAnim = FilterPanelAnimation()
    private var sortMode = "NEWEST"
    private var genderFilter = ""
    /** 属性筛选：**裸属性名**、最多 2 个用 `|` 连接（`fire` / `fire|ghost`）；空 = 不限。见 [TypeFilter] */
    private var typeFilter = ""
    /**
     * 太晶筛选：**完整词条 key**（`cobblemon.type.fire`），不是 `typeFilter` 那种裸 key。
     *
     * ⚠ 服务端 `MarketState.search` 是拿它跟 `extraData["teraType"]`（= `cobblemon.type.xxx`）
     *   做 `equals` **精确**比对的 ⇒ 传裸的 `fire` 会**恒不匹配**（选了太晶 → 列表全空）。
     *   口径与上架选择 / 创建拍卖 / 管理端一致。
     */
    private var teraFilter = ""
    // 超极巨化三态：0 = 不限，1 = 仅超极巨，2 = 仅非超极巨（照闪光那种循环按钮）
    private var gmaxFilter = 0
    private var abilityFilter = ""   // 特性翻译 key（空 = 不限）
    private var natureFilter = ""    // 性格翻译 key（空 = 不限；按生效性格匹配，不分薄荷）
    private val ivExact = IntArray(6) { -1 }
    // IV 比较方式：0 = 等于（默认），1 = 大于等于，2 = 小于等于
    private val ivOps = IntArray(6)
    // IV 序号 → 按钮（创建顺序与 IV 序号不一致，按 index 映射避免状态同步错位）
    private val ivOpButtons = mutableMapOf<Int, NineSliceButton>()

    // 筛选展开列表：filterListOpen = ""/type/ability/nature；互斥展开、限高滚动
    private var filterListOpen = ""
    private var filterListScroll = 0
    private val filterOptionButtons = mutableListOf<NineSliceButton>()
    /** 筛选展开列表的实际底边 y（每行 14、从 86 起）。被它盖住的行不渲染——反正看不见，画了反而刺穿列表 */
    /**
     * 展开列表（属性/特性/性格）浮层的上下缘 —— render 要在它下面垫一块**不透明底**。
     *
     * ⚠ 与改造前那个同名变量用途完全不同：那时它用来判断"主面板哪些行被盖住、不渲染"，
     *   现在展开列表在右侧面板里、盖不到主面板；留它是因为列表行的背景贴图中间半透明，
     *   不垫底的话下面几个按钮会透上来（原来是靠隐藏那些控件解决的，做法变了）。
     */
    private var filterListTopY = 0
    private var filterListBottomY = 0
    // 特性选项（搜索框物种解析后重建）
    private var abilityOptions = listOf<Pair<String, String>>() // (翻译 key, 显示名)

    private lateinit var genderButton: NineSliceButton
    private lateinit var typeButton: ButtonWidget
    private lateinit var teraButton: ButtonWidget
    private lateinit var gmaxButton: ButtonWidget
    private lateinit var abilityButton: ButtonWidget
    private lateinit var natureButton: ButtonWidget
    private lateinit var shinyButton: NineSliceButton
    private lateinit var sortButton: ButtonWidget
    private lateinit var resetButton: ButtonWidget
    private lateinit var mineButton: NineSliceButton
    // 声明成具体类型 NineSliceButton（不是父类 ButtonWidget）：开关时要更它的 iconLeft / selectedVisual
    private lateinit var filterToggleButton: NineSliceButton
    private lateinit var prevButton: ButtonWidget
    private lateinit var nextButton: ButtonWidget
    private val buyButtons = mutableListOf<ButtonWidget>()

    private var hpField: TextFieldWidget? = null
    private var atkField: TextFieldWidget? = null
    private var defField: TextFieldWidget? = null
    private var spaField: TextFieldWidget? = null
    private var spdField: TextFieldWidget? = null
    private var speField: TextFieldWidget? = null

    private var hoveredRow = -1
    private val panelWidth = 296

    // 主面板内容左缘 = 背景左缘 + 12（背景 320、内容 296，两侧各 12），即 `centerX - panelWidth/2`。
    // ⚠ 主面板**保持居中**，筛选面板贴在它右边（见 MarketPanelBackground.filterLeft），主面板不参与偏移
    private val iconSize = 20

    /** 筛选按钮宽度（三枚并排：32 起 60 / 96 起 96 / 196 起 96，铺满整行到面板右缘），文字截断按此反算 */
    /**
     * 筛选按钮「未选中」的文字色。
     * ⚠ 一度改成 0xAAAAAA（灰色 = 不过滤），用户 2026-09-23 看到后问「为什么把属性/特性/性格
     *   改成了暗色」—— 他不认可这个视觉区分，**恢复白色**（与选中态的前景色一致，靠值本身区分）。
     */
    private val UNSET_FILTER_COLOR = 0xFFFFFF

    // 筛选按钮宽度：搬进右侧筛选面板后三枚改成**竖排**、每枚占满面板内容宽 152，
    // 不再受"三枚并排挤在一行"的限制 ⇒ 宽度恢复充裕，前缀（「特性：静电」）也回来了（用户 2026-09-23 定）。
    // 这三个常量现在还只是文字截断的反算依据。
    /**
     * 右侧筛选面板的**内容高度** —— 由内容定，不跟随主面板铺到屏幕底边（2026-09-23 用户要求）。
     * 最后一行的 Spd 输入框在 y=268、高 16（底 284），加 12 底部余量 ⇒ 296。
     * ⚠ 面板内容再加行时记得同步改这里。
     */
    private val FILTER_PANEL_HEIGHT = 318

    /** 面板内滚动偏移（内容比面板高时才有用，见 [filterPanelHeight]） */
    private var filterPanelScroll = 0

    /**
     * 面板内控件的「基准 y」（= 未滚动时的位置）。
     *
     * 滚动要改控件的 `y`，还得能还原 —— 所以创建时统一登记一次，滚动只做 `base - scroll`。
     * ⚠ 别再另抄一份坐标常量表：那正是"三处各写一遍、改一处漏两处"的老毛病。
     */
    private val filterPanelControls = mutableListOf<Pair<net.minecraft.client.gui.widget.ClickableWidget, Int>>()

    /**
     * 筛选面板的**实际高度** = 内容高度与屏幕可用高度的较小值。
     *
     * ⚠ GUI 缩放设「自动」时逻辑屏高会变小（1080p + 缩放 4 只有 270），写死 296 会让面板戳出屏幕、
     *   底部的 IV 输入框看不见 —— 超出部分靠**面板内滚动**看（用户 2026-09-23 实测要求）。
     */
    private fun filterPanelHeight() = minOf(FILTER_PANEL_HEIGHT, height - 34)

    private val filterTypeW = 152
    private val filterValueW = 152
    private val filterNatureW = 152

    private var confirmEntry: ListingEntry? = null

    // ── 行按钮几何（**创建按钮与悬停窗避让共用一处定义**，别各写一份）──
    private val rowBtnInset = 42
    private val rowBtnW = 38
    private val rowBtnH = 16
    private val rowBtnDy = 4
    private val rowH = 24

    /** 第 [row] 行那个按钮（购买 / ✕）的矩形 —— 与 `rebuildBuyButtons` 同源 */
    private fun rowButtonRect(row: Int) =
        TooltipPlacement.Rect(width / 2 - panelWidth / 2 + panelWidth - rowBtnInset, getListStartY() + row * rowH + rowBtnDy, rowBtnW, rowBtnH)

    /**
     * 确认/下架弹窗信息区的滚动偏移（2026-09-20）：弹窗高度按屏幕钳制后，
     * 内容超出的部分靠滚轮看（照求购单创建弹窗那套）。打开弹窗时归零。
     */
    private var confirmScroll = 0
    /** 信息区设计顶（标题/图标之下起名字行）与底部按钮区高度 */
    private val confirmContentTop = 62
    private val confirmBottomH = 34
    private var cancelEntry: ListingEntry? = null
    private var confirmRenderable: RenderablePokemon? = null
    private var confirmDisplayName = ""
    private val confirmState = PreviewStateHolder()

    /**
     * 确认弹窗里那个精灵图标的屏幕位置，**渲染时记录**。
     *
     * 给庆祝动画当起飞起点用 —— 确认购买后弹窗就关了、图标不复存在，坐标必须提前抓下来。
     * 之所以在渲染时记、而不是在 [confirmPurchase] 里重算：图标位置跟着 `dialogY` 走，
     * 弹窗布局一改重算就会错位；渲染时记录则永远和实际画出来的一致。
     */
    private var confirmIconOrigin: PokemonCelebrationAnimation.Origin? = null

    /**
     * 弹窗自动叫一声的**待播时刻**（0 = 无；替玩家点一下弹窗里的图标）。
     *
     * **不能在 [openConfirmDialog] 里直接播**：一是 `addFirstAnimation` 依赖 `state.currentModel`，
     * 它要等 `drawProfilePokemon` 跑过才有值（弹窗刚打开时 state 还是空的）；二是弹窗刚弹出就响，
     * 玩家还在"看这是啥"，反应不过来声音从哪来。所以打开时只记「now + [IconCryClick.AUTO_CRY_DELAY_MS]」，
     * 渲染处到点才播（见 renderConfirmDialog）——「模型已就绪」与「延迟到点」两个条件同时满足。
     */
    private var confirmCryAt = 0L

    /** 喵喵支付可用（消费贷开关）：初始读全局缓存（入口界面已拉取），避免首次打开弹窗宽度闪烁 */
    private var payAvailable = com.shusheng.cobblemarket.client.FinanceCache.consumerLoanEnabled

    private val typeOptions = listOf(
        "", "normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison", "ground",
        "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy"
    )

    // Cached renderables per listing index
    private val iconData = mutableMapOf<Int, PokemonIconData>()

    /** 六项 IV 各自的「清空本框」按钮（↺），与输入框一一对应 */
    private val ivResetButtons = mutableListOf<NineSliceButton>()

    // tooltip 内容缓存：内容只取决于条目，悬停同一行时每帧重建全部文本行是悬停掉帧主因
    private var tooltipCacheKey: UUID? = null
    private var tooltipCacheLines: List<Pair<Text?, Int>> = emptyList()
    private var tooltipCacheHeldLine = -1
    private var tooltipCacheMovesLine = -1
    private var tooltipCacheMarksLine = -1
    private var tooltipCacheMarksRows = 0
    private var tooltipCacheMaxWidth = 0

    private fun cacheIcons() {
        // ⚠ 按**挂单 id** 复用已有的动画状态，别整体丢掉重建：
        //   FloatingState 里存着动画进度与头部跟随的跨帧平滑值，清掉会让图标从头播一遍 ——
        //   用户 2026-09-23 实测：点「我的 / 重置 / 三态(=)」按钮（它们都走 requestFilterRefresh
        //   重新拉列表）时，精灵的头会「转回去再立刻转回来」，动画还会顿两下。
        //   筛选 / 排序只改变列表的**子集与顺序**，同一个挂单的图标状态应当跨帧延续。
        val oldStates = iconData.values.mapNotNull { d -> d.listingId?.let { it to d.state } }.toMap()
        iconData.clear()
        listings.forEachIndexed { index, entry ->
            val id = Identifier.tryParse(entry.speciesId) ?: return@forEachIndexed
            val species = PokemonSpecies.getByIdentifier(id) ?: return@forEachIndexed
            // 用挂单携带的真实 aspects（性别形态/地区形态等），不再只加 shiny——
            // 否则雌性爱管侍这类性别差异物种会渲染成默认雄性模型
            val aspects = entry.aspects.toMutableSet()
            if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
            val displayName = com.shusheng.cobblemarket.util.SpeciesText.displayName(species)
            // 携带物同时喂给模型（Cobblemon 会渲染在手上/嘴上）与行内小图标
            val held = heldIconStack(entry.heldItemId, entry.heldItemNbt)
            iconData[index] = PokemonIconData(
                displayName,
                RenderablePokemon(species, aspects, heldIconStackForModel(entry.heldItemVisible, entry.heldItemId, entry.heldItemNbt)),
                // 同一个挂单（id 相同）沿用旧的动画状态，只有新出现的挂单才起一份新的
                oldStates[entry.id] ?: FloatingState(),
                listingId = entry.id,
                ballStack = Identifier.tryParse(entry.ballItem)?.let { ItemStack(Registries.ITEM.get(it)) },
                heldStack = held
            )
        }
    }

    /** 携带物栈（空气视为无）；缓存供行渲染每帧复用 */
    private val defaultSkinTexture = Identifier.of("minecraft", "textures/entity/player/wide/steve.png")

    // 皮肤纹理缓存：每行每帧创建 GameProfile 并查 skinProvider 是分配热点，首个结果按 UUID 缓存
    private val skinCache = mutableMapOf<UUID, Identifier>()

    private fun getSellerSkin(uuid: UUID, name: String): Identifier {
        skinCache[uuid]?.let { return it }
        val skin = client?.networkHandler?.getPlayerListEntry(uuid)?.skinTextures?.texture()
            ?: client?.skinProvider?.getSkinTextures(GameProfile(uuid, name))?.texture()
            ?: defaultSkinTexture
        skinCache[uuid] = skin
        return skin
    }

    private fun drawSellerAvatar(context: DrawContext, uuid: UUID, name: String, x: Int, y: Int, size: Int) {
        val texture = getSellerSkin(uuid, name)
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(size / 8f, size / 8f, 1f)
        context.drawTexture(texture, 0, 0, 8f, 8f, 8, 8, 64, 64)
        context.matrices.pop()
    }

    override fun init() {
        super.init()
        sendToServer(RequestBalancePayload())

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        // Row 1 (y=32): Search text field (full width)
        // 宽度 = 面板 − 4（左右边距）− 50（小手）− 2 − 2（两侧间隔）− 50（筛选展开）；
        // 小手与筛选同宽后搜索框相应缩短（2026-09-21 用户拍板：再长的精灵名也用不到这么长）
        searchField = TextFieldWidget(textRenderer, leftX + 2, 44, panelWidth - 108, 16, Text.translatable("cobblemarket.gui.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.gui.search_placeholder").formatted(Formatting.GRAY))
        // 防抖：停止输入 250ms 后才发请求。连续编辑时只发最终态，
        // 避免中间态请求与最终态请求挤在服务端 250ms 节流窗口内，最终态被静默丢弃导致列表不刷新。
        searchField?.setChangedListener {
            searchDirty = true
            lastSearchEdit = System.currentTimeMillis()
            // 同步解析物种 → 特性筛选选项；物种变化时重置特性选择
            updateAbilityOptions(it)
        }
        addSelectableChild(searchField)
        addDrawableChild(searchField)

        // 筛选展开/收起按钮（搜索框右侧）：去文字化——展开显示 filter、收起显示 filter_close
        filterToggleButton = NineSliceButton(
            leftX + panelWidth - 52, 44, 50, 16,
            Text.literal(""),
            { toggleFilters() },
            iconLeft = Identifier.of("cobblemarket",
                if (filterExpanded) "textures/gui/filter.png" else "textures/gui/filter_close.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        ).also {
            it.setTooltip(Tooltip.of(Text.translatable(
                if (filterExpanded) "cobblemarket.gui.filter_collapse" else "cobblemarket.gui.filter_expand")))
            // 面板展开期间按钮保持**选中态**（按钮背景的第三态，见 NineSliceButton.selectedVisual）——
            // 一眼看出筛选面板当前是开着还是关着。开关会重建界面，所以创建时设一次就够。
            it.selectedVisual = filterExpanded
        }
        addDrawableChild(filterToggleButton)

        // Sell button (right of search, left of filter toggle)：宽 50 与右侧「筛选展开」对齐
        // （2026-09-21 用户拍板；腾出的宽度来自搜索框，见上面 searchField 的宽度算式）
        // ⚠ 纯图标按钮**一律要给悬停提示**（同排的筛选按钮就有）—— 小手图标不给提示的话，
        //   玩家根本不知道去哪儿上架精灵（2026-09-21 用户报）
        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 104, 44, 50, 16,
            Text.literal(""), { openSellScreen() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/choose.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.sell_pokemon")
        ))

        // Collect balance button (top-left)
        collectButton = NineSliceButton(
            leftX, 13, 50, 16,
            Text.translatable("cobblemarket.gui.collect"), { collectBalance() }
        )
        addDrawableChild(collectButton)

        // Expired returns button
        addDrawableChild(NineSliceButton(
            leftX + 52, 13, 50, 16,
            Text.translatable("cobblemarket.gui.returns"), { client?.setScreen(PokemonReturnScreen()) }
        ))

        // Back button (top-right, symmetric with collect)
        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        ))

        // ── 以下全部是**右侧筛选面板**内的控件（**常驻创建**，开关只切可见性）──────────────
        // ⚠ 别再用 `if (filterExpanded)` 把这一段包起来：那会让开关走"重建界面"的老路，
        //   而重建会清空 iconData 里的**跨帧动画状态** —— 用户 2026-09-23 实测：
        //   「点筛选展开，正在睡觉的精灵会突然站起来一下、然后再睡回去」。
        //   面板已经是独立的一块，开关只该影响它自己（见 updateControlVisibility 的 filterExpanded）。
        // 搬过来的理由（用户 2026-09-23 提的方案）：原来这些挤在主面板上方，展开时把列表起点
        // 从 64 顶到 160、整整吞掉 4 行；现在它们有自己的面板，主面板只剩「搜索行 + 列表」，
        // 列表起点恒定，也不用再处理「展开列表盖住下面的行」。
        val fx = MarketPanelBackground.filterLeft(width) + 4
        val fw = MarketPanelBackground.filterWidth(width) - 8
        // 「特训 + 超极巨化」那行对半分（4px 间距）；两个按钮的文案在双语下都放得下
        val half = (fw - 4) / 2

        // Row 2 (y=66): 性别图标 | 属性 | 特性 | 性格（四按钮，间距 4px；后三者展开选择）
        genderButton = NineSliceButton(
            fx + 34, 20, 24, 20,
            Text.literal(""),
            { cycleGender() },
            iconTexW = 6, iconTexH = 8, iconScale = 1.5f
        )
        updateGenderButton()
        addDrawableChild(genderButton)

        typeButton = NineSliceButton(
            fx, 42, fw, 20,
            typeButtonText(),
            { toggleFilterList("type") },
            UNSET_FILTER_COLOR   // 颜色由「值」自己带（见 filterButtonText），这里必须留白
        )
        addDrawableChild(typeButton)

        // 太晶属性：与「属性」同一套候选列表（18 属性）+ 「不限」
        // ⚠ 与属性各占**一整行**：英文带前缀最长 "Tera: Electric" = 84px，半宽（74px）塞不下
        teraButton = NineSliceButton(
            fx, 64, fw, 20,
            teraButtonText(),
            { toggleFilterList("tera") },
            UNSET_FILTER_COLOR   // 同属性按钮：颜色由「值」自己带
        )
        addDrawableChild(teraButton)

        abilityButton = NineSliceButton(
            fx, 86, fw, 20,
            abilityButtonText(),
            { toggleFilterList("ability") },
            UNSET_FILTER_COLOR   // 颜色由「值」自己带
        )
        addDrawableChild(abilityButton)

        natureButton = NineSliceButton(
            fx, 108, fw, 20,
            natureButtonText(),
            { toggleFilterList("nature") },
            UNSET_FILTER_COLOR   // 颜色由「值」自己带
        )
        addDrawableChild(natureButton)

        // IV 六项：右侧面板内**竖排**（每行「输入框 108 + 三态按钮 20」，行高 18）。
        // 搬过来之前它们占主面板两行（44px），现在主面板一格不占 ⇒ 列表起点恒定为 68。
        ivOpButtons.clear()
        ivResetButtons.clear()
        // 每行 = [输入框 84] [三态 40] [单框重置 ↺ 20]（2026-09-23 用户要求：输入框缩短、三态左移、
        // 右侧再给每项一枚重置 —— 为了清一个框不必去点总重置把六项全清掉）
        hpField  = createIvField(fx, 180, "HP",  84); addIvOpButton(fx + 88, 180, 0); addIvResetButton(fx + 132, 180, 0)
        atkField = createIvField(fx, 202, "ATK", 84); addIvOpButton(fx + 88, 202, 1); addIvResetButton(fx + 132, 202, 1)
        defField = createIvField(fx, 224, "DEF", 84); addIvOpButton(fx + 88, 224, 2); addIvResetButton(fx + 132, 224, 2)
        spaField = createIvField(fx, 246, "SpA", 84); addIvOpButton(fx + 88, 246, 3); addIvResetButton(fx + 132, 246, 3)
        spdField = createIvField(fx, 268, "SpD", 84); addIvOpButton(fx + 88, 268, 4); addIvResetButton(fx + 132, 268, 4)
        speField = createIvField(fx, 290, "Spd", 84); addIvOpButton(fx + 88, 290, 5); addIvResetButton(fx + 132, 290, 5)

        // 三态比较按钮（= / ≥ / ≤）：已随 IV 一起搬进右侧面板，见上面那六行里的 addIvOpButton

        // Row 5 (y=138): Shiny(符号) + Sort + HT + Mine + Reset
        shinyButton = NineSliceButton(
            fx, 20, 30, 20,
            Text.literal(if (shinyOnly) "★" else "☆"),
            { toggleShiny() },
            // resize 重建时保持当前状态的颜色
            if (shinyOnly) GOLD_COLOR else 0xFFFFFF
        )
        addDrawableChild(shinyButton)

        // 排序：跟在性别右边（用户 2026-09-23 要求第一排放「闪光 | 性别 | 排序」，排序相应缩窄）
        sortButton = NineSliceButton(
            fx + 62, 20, fw - 62, 20,
            Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay())),
            { cycleSort() }
        )
        addDrawableChild(sortButton)

        htButton = NineSliceButton(
            fx, 130, half, 20,
            htButtonText(),
            { toggleHtFilter() },
            if (htFilter != 0) GOLD_COLOR else 0xFFFFFF
        )
        addDrawableChild(htButton)

        // 超极巨化：三态循环（不限 → 仅超极巨 → 仅非超极巨），与特训同排各占一半 ——
        // 中文「超极巨化：是」54px / 英文 "Gmax: Yes" 54px，半宽放得下
        gmaxButton = NineSliceButton(
            fx + half + 4, 130, half, 20,
            gmaxButtonText(),
            { toggleGmax() },
            0xFFFFFF   // 颜色由「值」自己带
        )
        addDrawableChild(gmaxButton)

        // 我的（personal/personal_click 双图标：关 = 全部，开 = 仅我的；悬停词条随状态）
        // 「我的」与「重置」同一行（用户 2026-09-23 要求）：我的占左边、重置是右边那枚窄符号按钮
        mineButton = NineSliceButton(
            fx, 152, fw - 24, 20,
            Text.literal(""),
            { toggleMineOnly() },
            iconLeft = Identifier.of("cobblemarket",
                if (showMineOnly) "textures/gui/personal_click.png" else "textures/gui/personal.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine")
        )
        addDrawableChild(mineButton)

        // 重置：与「我的」同行、改用**符号** ↺（照配置界面输入框右侧那枚，2026-09-23 用户要求）——
        // 面板只有 152 宽，文字「重置」/`Reset` 占地方，符号 + 悬停提示足够（纯图标按钮的惯例）
        resetButton = NineSliceButton(
            fx + fw - 20, 152, 20, 20,
            Text.literal("↺"),
            { resetFilters() },
            tooltip = Text.translatable("cobblemarket.gui.reset")
        )
        addDrawableChild(resetButton)

        // （筛选面板控件常驻创建到此为止，见上面的说明）
        // 统一登记面板内控件：记下基准 y，滚动时按它重定位（见 filterPanelControls 的说明）
        filterPanelControls.clear()
        listOfNotNull(
            shinyButton, genderButton, sortButton, htButton, mineButton,
            typeButton, teraButton, abilityButton, natureButton, resetButton,
            gmaxButton,
            hpField, atkField, defField, spaField, spdField, speField
        ).forEach { filterPanelControls += it to it.y }
        ivOpButtons.values.forEach { filterPanelControls += it to it.y }
        ivResetButtons.forEach { filterPanelControls += it to it.y }
        // 建完先刷一遍位置与可见性：面板收起时它们必须立刻藏起来（常驻创建的控件默认 visible=true，
        // 不刷的话收起状态下面板里的按钮会照常显示在屏幕上）
        updateControlVisibility()

        // 重建（resize/折叠切换）时收起筛选展开列表，避免状态与控件不一致
        filterListOpen = ""
        filterListScroll = 0

        // Page buttons at bottom：底部有对称分割线（最后一行下方 4px，与顶部分割线对称），
        // 按钮放在分割线与背景底边之间居中偏上 5px
        val listBottom = getListStartY() + getMaxVisibleRows() * 24
        val btnY = (listBottom + 5 + (height - 32)) / 2 - 10 - 5

        prevButton = NineSliceButton(
            leftX, btnY, 80, 20,
            Text.literal(""),
            { prevPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/previous.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.prev")
        )
        addDrawableChild(prevButton)

        nextButton = NineSliceButton(
            leftX + panelWidth - 80, btnY, 80, 20,
            Text.literal(""),
            { nextPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/next.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.next")
        )
        addDrawableChild(nextButton)

        rebuildBuyButtons()
        requestFilterRefresh()
    }

    private fun ivOpSymbol(op: Int): String = when (op) {
        1 -> "≥"
        2 -> "≤"
        else -> "="
    }

    /** IV 三态比较按钮：= → ≥ → ≤ 循环；切换立即重发筛选请求（过滤条件变了） */
    /**
     * 单项 IV 的「清空本框」按钮（↺）—— 只清这一项的数字与三态，不动其它五项。
     * ⚠ 与下行那个「重置全部筛选」的总按钮是两件事，别合并。
     */
    private fun addIvResetButton(x: Int, y: Int, index: Int) {
        val btn = NineSliceButton(
            x, y, 20, 16,
            Text.literal("↺"),
            {
                ivExact[index] = -1
                ivOps[index] = 0
                listOf(hpField, atkField, defField, spaField, spdField, speField)[index]?.text = ""
                syncIvOpButtons()
                currentPage = 1
                requestFilterRefresh()
            },
            tooltip = Text.translatable("cobblemarket.gui.reset")
        )
        ivResetButtons += btn
        addDrawableChild(btn)
    }

    private fun addIvOpButton(x: Int, y: Int, index: Int) {
        val btn = NineSliceButton(
            // 宽度 20 → 40（2026-09-23 用户要求）：右侧还有余量，三态符号（= / ≥ / ≤）宽一点更好点
            x, y, 40, 16,
            Text.literal(ivOpSymbol(ivOps[index])),
            {
                ivOps[index] = (ivOps[index] + 1) % 3
                syncIvOpButtons()
                currentPage = 1
                requestFilterRefresh()
            },
            textColor = if (ivOps[index] != 0) GOLD_COLOR else 0xFFFFFF
        )
        ivOpButtons[index] = btn
        addDrawableChild(btn)
    }

    private fun syncIvOpButtons() {
        ivOpButtons.forEach { (index, btn) ->
            btn.message = Text.literal(ivOpSymbol(ivOps[index]))
            btn.textColor = if (ivOps[index] != 0) GOLD_COLOR else 0xFFFFFF
        }
    }

    /**
     * IV 输入框。
     * ⚠ 2026-09-23 搬进右侧筛选面板时加了 [width] 参数（原先写死 68）：那边一列只放一个，
     *   输入框放宽到 108，与右侧的三态按钮合起来正好占满面板内容宽。
     */
    private fun createIvField(x: Int, y: Int, placeholder: String, width: Int = 68): TextFieldWidget {
        val field = TextFieldWidget(textRenderer, x, y, width, 16, Text.literal(""))
        field.setPlaceholder(Text.literal(placeholder))
        field.setTextPredicate { it.length <= 2 && it.all { c -> c.isDigit() } }
        addSelectableChild(field)
        addDrawableChild(field)
        return field
    }

    /**
     * 列表起点 —— **恒定**，不再随筛选展开而变。
     *
     * ⚠ 改造前是 `(if (filterExpanded) 160 else 64) + 4`：展开筛选时列表起点从 64 跳到 160，
     *   整整吞掉 4 行。筛选区搬到右侧面板后这个跳变就没有了（2026-09-23 用户提的方案）。
     */
    private fun getListStartY() = 64 + 4
    // 预留 72 = 分页按钮高 20 + 4 空隙 + 底部边框切片 16 + 背景底边下空隙：
    // 按钮贴列表最后一行下方、完全不压背景底部边框（原 48 时按钮底越过背景底边 4px）
    private fun getMaxVisibleRows() = maxOf(0, (height - getListStartY() - 72) / 24)

    private fun rebuildBuyButtons() {
        buyButtons.forEach { remove(it) }
        buyButtons.clear()

        // 筛选展开列表/确认弹窗打开时行按钮保持隐藏：事件/刷新触发的重建会把新按钮
        // 追加到 children 末尾，浮在列表按钮/遮罩之上刺穿
        if (filterListOpen.isNotEmpty() || confirmEntry != null || cancelEntry != null) return

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2
        val startY = getListStartY()
        val rowHeight = 24
        val playerUuid = client?.player?.uuid

        displayedListings().take(getMaxVisibleRows()).forEachIndexed { di, (origIndex, entry) ->
            val y = startY + di * rowHeight
            val isMine = playerUuid != null && entry.sellerUuid == playerUuid
            val label = if (isMine) Text.literal("✕").formatted(Formatting.RED) else Text.translatable("cobblemarket.gui.buy")
            val action = if (isMine)
                ButtonWidget.PressAction { openCancelDialog(entry) }
            else
                ButtonWidget.PressAction { openConfirmDialog(entry) }
            val btn = NineSliceButton(leftX + panelWidth - rowBtnInset, y + rowBtnDy, rowBtnW, rowBtnH, label, action)
            buyButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // 确认弹窗的 3D 渲染：用挂单真实 aspects（含性别形态），与列表图标一致
    private fun buildConfirmRenderable(entry: ListingEntry) {
        confirmRenderable = null
        confirmDisplayName = ""
        val id = Identifier.tryParse(entry.speciesId) ?: return
        val species = PokemonSpecies.getByIdentifier(id) ?: return
        val aspects = entry.aspects.toMutableSet()
        if (entry.shiny && "shiny" !in aspects) aspects.add("shiny")
        // 携带物一并带上：确认弹窗里的精灵也要拿东西（详情弹窗渲染时由 drawHeldItem 画出来）
        confirmRenderable = RenderablePokemon(species, aspects, heldIconStackForModel(entry.heldItemVisible, entry.heldItemId, entry.heldItemNbt))
        confirmDisplayName = com.shusheng.cobblemarket.util.SpeciesText.displayName(species)
    }

    private fun openConfirmDialog(entry: ListingEntry) {
        confirmEntry = entry
        confirmScroll = 0
        buildConfirmRenderable(entry)
        // 弹窗打开时自动叫一声（渲染后延迟再播，理由见 confirmCryAt）
        confirmCryAt = System.currentTimeMillis() + IconCryClick.AUTO_CRY_DELAY_MS
        // 收起可能展开的筛选列表（否则列表按钮残留在遮罩下可点）
        filterListOpen = ""
        rebuildFilterList()
        // 拉消费贷开关（喵喵支付按钮显示依据）
        requestCreditInfo()
    }

    /** 消费贷开关快照（打开购买确认弹窗时拉取）：控制喵喵支付按钮显示与弹窗宽度 + 回写缓存 */
    fun onCreditInfo(payload: CreditInfoPayload) {
        payAvailable = payload.consumerLoanEnabled
        com.shusheng.cobblemarket.client.FinanceCache.consumerLoanEnabled = payload.consumerLoanEnabled
    }

    private fun openCancelDialog(entry: ListingEntry) {
        cancelEntry = entry
        confirmScroll = 0
        buildConfirmRenderable(entry)
        // 弹窗打开时自动叫一声（与购买弹窗同一套；渲染后延迟再播，理由见 confirmCryAt）
        confirmCryAt = System.currentTimeMillis() + IconCryClick.AUTO_CRY_DELAY_MS
        filterListOpen = ""
        rebuildFilterList()
    }

    private fun closeConfirmDialog() {
        confirmEntry = null
        cancelEntry = null
        confirmRenderable = null
        confirmDisplayName = ""
        confirmIconOrigin = null
    }

    private fun confirmPurchase() {
        val entry = confirmEntry ?: return
        registerCelebrationOrigin()
        sendToServer(BuyFromMarketPayload(entry.id, -1))
        closeConfirmDialog()
    }

    /**
     * 关闭弹窗前登记庆祝动画的起飞起点 —— 就是本弹窗那个精灵图标的位置与大小。
     * 起点带 3 秒过期（见 [PokemonCelebrationAnimation.setPendingOrigin]），购买没成功也不会留脏值。
     */
    private fun registerCelebrationOrigin() {
        confirmIconOrigin?.let(PokemonCelebrationAnimation::setPendingOrigin)
    }

    private fun confirmCancel() {
        val entry = cancelEntry ?: return
        sendToServer(CancelFromMarketPayload(entry.id))
        closeConfirmDialog()
    }

    /** 打开喵喵支付独立界面（MeowthPayScreen）：确认回调发喵喵支付购买请求 */
    private fun openMeowthPay() {
        val entry = confirmEntry ?: return
        // 名字带主属性色（与购买确认弹窗同口径，取 entry.primaryType —— 服务端已按形态填好）：
        // 给名字段着色即可，闪光★的 GOLD 是子段显式样式、不会被父段覆盖
        val descName = EntryBadgeRenderer.nameWithShinyStar(
            if (confirmDisplayName.isNotEmpty()) confirmDisplayName else entry.species, entry.shiny
        ).copy().styled { it.withColor(EntryBadgeRenderer.typeColor(entry.primaryType)) }
        client?.setScreen(MeowthPayScreen(
            itemDesc = descName,
            amount = entry.price.toLong(),
            onConfirm = { plan ->
                registerCelebrationOrigin()
                sendToServer(BuyFromMarketPayload(entry.id, plan))
                client?.setScreen(MarketScreen())
            },
            onBack = { client?.setScreen(MarketScreen()) }
        ))
    }

    // 带索引列表缓存：render/renderBackground 每帧调用 displayedListings()，
    // 每帧 withIndex().toList() 是分配热点，改为数据到达时重建
    private var indexedListings = listOf<IndexedValue<ListingEntry>>()

    private fun displayedListings(): List<IndexedValue<ListingEntry>> = indexedListings

    // ── Gender filter（公母图标三态循环：♂♀ 不限 → ♂ 仅公 → ♀ 仅母） ──

    private fun updateGenderButton() {
        genderButton.iconLeft = when (genderFilter) {
            "FEMALE" -> GENDER_ICON_FEMALE
            else -> GENDER_ICON_MALE
        }
        genderButton.iconLeft2 = if (genderFilter.isEmpty()) when (genderFilter) {
            "FEMALE" -> null
            else -> GENDER_ICON_FEMALE
        } else null
    }

    private fun cycleGender() {
        genderFilter = when (genderFilter) {
            "" -> "MALE"
            "MALE" -> "FEMALE"
            else -> ""
        }
        currentPage = 1
        updateGenderButton()
        requestFilterRefresh()
    }

    // ── 属性/特性/性格展开选择（互斥、限高滚动、展开时隐藏被覆盖控件） ──

    /**
     * 属性按钮文字 =「属性：」+ 各属性名 —— **每段属性名自己带属性色**：「属性：」前缀与 `+`
     * 都是陪衬文字不设色（CLAUDE.md：带色的文字不能当父节点）。最多 2 个，顺序 = 玩家点击顺序。
     *
     * ⚠ 这里**不走 [filterButtonText]**：那个函数按 `value.string` 截断，会把分段样式整段丢掉。
     *   宽度也不必截断 —— 英文最坏 `Type: Fighting+Electric` = 138px，最窄一档按钮内容宽 140px。
     */
    private fun typeButtonText(): Text {
        val picked = TypeFilter.keys(typeFilter)
        val out = Text.translatable("cobblemarket.gui.type")
        if (picked.isEmpty()) return out.append(Text.translatable("cobblemarket.gui.filter_any"))
        picked.forEachIndexed { i, k ->
            if (i > 0) out.append(Text.literal("+"))
            out.append(Text.translatable("cobblemon.type.$k")
                .setStyle(net.minecraft.text.Style.EMPTY.withColor(typeColor(k))))
        }
        return out
    }

    private fun teraButtonText(): Text {
        val any = teraFilter.isEmpty()
        val label = if (any) Text.translatable("cobblemarket.gui.filter_any")
            else Text.translatable(teraFilter)
        return filterButtonText(
            "cobblemarket.gui.tera", label, filterTypeW, any,
            if (any) null else typeColor(teraFilter)
        )
    }

    /** 超极巨化：0 = 不限（只显示标签）、1 = 仅超极巨、2 = 仅非超极巨 */
    private fun gmaxButtonText(): Text {
        val label = when (gmaxFilter) {
            1 -> Text.translatable("cobblemarket.gui.filter_yes")
            2 -> Text.translatable("cobblemarket.gui.filter_no")
            else -> Text.translatable("cobblemarket.gui.filter_any")
        }
        return filterButtonText(
            "cobblemarket.gui.gmax", label, (MarketPanelBackground.filterWidth(width) - 8 - 4) / 2, gmaxFilter == 0,
            if (gmaxFilter == 0) null else GOLD_COLOR
        )
    }

    private fun toggleGmax() {
        gmaxFilter = (gmaxFilter + 1) % 3
        gmaxButton.message = gmaxButtonText()
        (gmaxButton as? NineSliceButton)?.textColor = 0xFFFFFF
    }

    private fun abilityButtonText(): Text {
        val any = abilityFilter.isEmpty()
        val label = if (any) Text.translatable("cobblemarket.gui.filter_any")
            else Text.translatable(abilityFilter)
        return filterButtonText(
            "cobblemarket.buy_order.ability_label", label, filterValueW, any,
            if (any) null else GOLD_COLOR
        )
    }

    private fun natureButtonText(): Text {
        val any = natureFilter.isEmpty()
        val label = if (any) Text.translatable("cobblemarket.gui.filter_any")
            else Text.translatable(natureFilter)
        return filterButtonText(
            "cobblemarket.buy_order.nature_label", label, filterValueW, any,
            if (any) null else GOLD_COLOR
        )
    }

    /**
     * 筛选按钮文字：**未选中** = 只显示标签「特性」，**选中** = 只显示值「静电」（用户 2026-09-23 定）。
     *
     * ⚠ 两种状态都**不拼「任意」、也不拼前缀**：三枚按钮并排铺满整行、加宽不了，「特性：任意」
     *   这截前缀白占宽度（这也是本次能把按钮从 96 收到 64、给拍卖场腾出「闪光 + 重置」的前提）。
     *   未选中态靠**灰色**表示"不过滤"（[UNSET_FILTER_COLOR]），选中态用该项自己的颜色，一眼分得清。
     * ⚠ 值超宽时按按钮宽度截断（两侧各留 4px）。**不需要悬停提示**：展开列表里的选项按钮是整行的宽，
     *   玩家展开时就能看到完整名字、选之前已经知道是什么（用户 2026-09-23 定）。
     */
    private fun filterButtonText(labelKey: String, value: Text, buttonW: Int, isAny: Boolean, valueColor: Int? = null): Text {
        // ⚠ `isAny` 已无用（2026-09-23 恢复「标签 + 值」前缀后，两种状态都要拼标签）——
        //   留着只是少改几个调用点，下次动这块时一并清掉。
        val prefix = Text.translatable(labelKey)
        val shown = Text.literal(TextUtil.truncateString(value.string, buttonW - 8 - textRenderer.getWidth(prefix)))
        // ⚠ **颜色只上在「值」上，前缀是陪衬文字不设色**（用户 2026-09-28 实测报的：原来整段染成属性色，
        //   连「属性：」这几个字都跟着变色）。走的是 CLAUDE.md 那条规矩：带色的文字不能当父节点 ——
        //   前缀（无色）当父、把颜色设在**值**自己的样式上，前缀就吃按钮的默认白字。
        //   ⚠ 同时按钮的 `textColor` 必须留**白色**：那是"整段默认色"，设成属性色会把前缀again染色。
        val valueText = if (valueColor != null)
            shown.setStyle(net.minecraft.text.Style.EMPTY.withColor(valueColor))
        else shown
        return prefix.append(valueText)
    }

    /** 25 种性格（翻译 key, 显示名），与求购单创建一致 */
    private val natureOptions: List<Pair<String, String>> by lazy {
        com.cobblemon.mod.common.api.pokemon.Natures.all().map { n ->
            val key = "cobblemon.nature.${n.name.path}"
            val t = Text.translatable(key).string
            key to (if (t == key) n.displayName else t)
        }
    }

    private fun toggleFilterList(kind: String) {
        filterListOpen = if (filterListOpen == kind) "" else kind
        filterListScroll = 0
        rebuildFilterList()
    }

    /**
     * 筛选面板与确认弹窗都是**模态**的：打开时把会被盖住的下层控件全部藏掉。
     *
     * ⚠ **必须 `visible = false`，不是「不画就行」** —— 原版 `ClickableWidget` 只要 visible
     * 就照常检测 hover 并弹自己的 tooltip（`active = false` 拦不住），于是「弹窗按钮的位置正好
     * 压在某个下层按钮上」时，鼠标停在弹窗按钮上却弹出下层那条提示
     * （2026-09-19 用户实测：精灵市场的购买弹窗里「确认购买」压住「上一页」，弹出的就是上一页的提示）。
     *
     * 每帧在 [render] 里调一次，开关弹窗的两头不必各自记着恢复。
     */
    /**
     * 该控件是否被展开列表的浮层盖住 —— **读控件自己的 y**，不维护第二份坐标表。
     *
     * ⚠ 盖住的**必须 `visible = false`，不能只靠"垫一块底、不画就行"**：原版 `ClickableWidget`
     *   只要 `visible` 就照常检测 hover、响应点击、弹自己的 tooltip（`active = false` 拦不住），
     *   浮层下面的按钮会连点带悬停一起生效，文字也会从半透明浮层里透上来。
     *   （2026-09-23 用户实测：「属性/特性/性格展开后，下层文字透上来并且可以点击互动」）
     */
    private fun coveredByFilterList(w: ButtonWidget?): Boolean =
        w != null && filterListOpen.isNotEmpty() && w.y >= filterListTopY && w.y < filterListBottomY

    private fun updateControlVisibility() {
        val dialogOpen = confirmEntry != null || cancelEntry != null
        // 浮层只盖得到**右侧面板内部**：主面板的行按钮 / 分页按钮只跟弹窗走
        // （老布局下浮层压在主面板上，那时连行按钮都要一起藏）
        // ⚠ 面板收起时（!filterExpanded）面板内的控件要**一起藏** —— 它们是常驻创建的（见 init 里的说明）
        // 开关动画进行中：面板内的按钮 / 输入框先藏起来 —— 它们是 MC 控件、塞不进矩阵、不跟着缩放，
        // 留着会像浮在缩小中的面板外面。播完（isActive 转 false）下一帧自动恢复
        val animHidden = filterAnim.isActive()
        fun show(w: ButtonWidget?) {
            if (w != null) w.visible = filterExpanded && !dialogOpen && !coveredByFilterList(w) && !animHidden
        }
        listOf(hpField, atkField, defField, spaField, spdField, speField).forEachIndexed { i, f ->
            val vis = filterExpanded && !dialogOpen && !coveredByFilterList(ivOpButtons[i]) && !animHidden
            f?.visible = vis
            ivOpButtons[i]?.visible = vis
            ivResetButtons.getOrNull(i)?.visible = vis
        }
        // ⚠ 一律走 isInitialized：这里面好几个是 lateinit **且只在筛选展开时才创建**
        //   （`if (filterExpanded)` 分支里），筛选没展开时它们压根不存在，直接读会抛
        //   UninitializedPropertyAccessException —— 2026-09-19 用户一打开精灵市场就崩
        if (::shinyButton.isInitialized) show(shinyButton)
        if (::genderButton.isInitialized) show(genderButton)
        if (::sortButton.isInitialized) show(sortButton)
        if (::typeButton.isInitialized) show(typeButton)
        // ⚠ **新加的筛选控件必须补进这个名单**（这里是一个个点名的，不补 = 面板收起时它还挂在屏幕上）
        if (::teraButton.isInitialized) show(teraButton)
        if (::gmaxButton.isInitialized) show(gmaxButton)
        if (::abilityButton.isInitialized) show(abilityButton)
        if (::natureButton.isInitialized) show(natureButton)
        show(htButton)
        if (::mineButton.isInitialized) show(mineButton)
        if (::resetButton.isInitialized) show(resetButton)
        // ⚠ 展开列表的**浮层按钮**是一组动态创建的控件，不在上面那份点名名单里 —— 单独按面板状态藏。
        //   否则面板收起 / 弹窗打开 / 开关动画期间它们会留在屏幕上（正常路径下 rebuildFilterList 会把
        //   它们 remove 掉，但弹窗这类路径不重建列表，这里兜住）
        filterOptionButtons.forEach { it.visible = filterExpanded && !dialogOpen && !animHidden }
        buyButtons.forEach { it.visible = !dialogOpen }
        if (::prevButton.isInitialized) prevButton.visible = !dialogOpen
        if (::nextButton.isInitialized) nextButton.visible = !dialogOpen
        if (::filterToggleButton.isInitialized) filterToggleButton.visible = !dialogOpen
        searchField?.visible = !dialogOpen
        // 面板内滚动：按登记的基准 y 重定位，滚出面板范围的控件藏起来（滚轮改了 filterPanelScroll
        // 之后调本函数重排）。⚠ 放在最后 —— 它会覆盖上面 show() 设的 visible。
        val panelTop = 4
        val panelBottom = 2 + filterPanelHeight()
        filterPanelControls.forEach { (w, baseY) ->
            w.y = baseY - filterPanelScroll
            if (w.y < panelTop || w.y + w.height > panelBottom) w.visible = false
        }
    }

    private fun rebuildFilterList() {
        filterOptionButtons.forEach { remove(it) }
        filterOptionButtons.clear()
        updateControlVisibility()
        val open = filterListOpen.isNotEmpty()
        if (!open) return
        val options: List<Pair<String, String>> = when (filterListOpen) {
            "type" -> listOf("" to "") + typeOptions.filter { it.isNotEmpty() }.map { it to "cobblemon.type.$it" }
            // ⚠ 太晶的 key 用**完整词条 key**（与 typeFilter 的裸 key 不同，见 `teraFilter` 的说明）
            "tera" -> listOf("" to "") + typeOptions.filter { it.isNotEmpty() }.map { "cobblemon.type.$it" to "cobblemon.type.$it" }
            "ability" -> listOf("" to "") + abilityOptions
            "nature" -> listOf("" to "") + natureOptions
            else -> emptyList()
        }
        val fx = MarketPanelBackground.filterLeft(width) + 4
        val fw = MarketPanelBackground.filterWidth(width) - 8
        // 浮层画在**被点按钮的正下方**（会盖住它下面那几个按钮，收起就回来）——
        // 右侧面板里只有筛选控件、没有精灵行，所以"盖住几个按钮"远比原来"盖住列表行"可接受
        // （原来那套「被盖住的行不渲染 / 不算悬停 / 隐藏下层控件」的补丁因此整个删掉）。
        // 浮层贴在被点的那枚按钮正下方 —— **读控件自己的 y**，不再维护第二份坐标表
        // （三处坐标各写一遍正是"展开列表飘位置"这类事故的来源）
        // ⚠ 别写显式类型 `ButtonWidget?`：那样 `?: return` 之后 Kotlin 不会把它当非空（声明类型仍是可空）
        val sourceBtn = when (filterListOpen) {
            "type" -> if (::typeButton.isInitialized) typeButton else null
            "tera" -> if (::teraButton.isInitialized) teraButton else null
            "ability" -> if (::abilityButton.isInitialized) abilityButton else null
            "nature" -> if (::natureButton.isInitialized) natureButton else null
            else -> null
        } ?: return
        val listTop = sourceBtn.y + 22
        // 面板底边（与 MarketPanelBackground.BOTTOM_MARGIN 一致）往下就没地方了，自动限制行数
        val maxRows = minOf(MAX_FILTER_LIST_ROWS, maxOf(1, (height - 32 - listTop) / 14))
        // 记下浮层范围，render 据此垫一块不透明底（见两个字段的注释）
        filterListTopY = listTop
        filterListBottomY = listTop + minOf(options.size - filterListScroll, maxRows) * 14
        options.drop(filterListScroll).take(maxRows).forEachIndexed { i, (key, label) ->
            val idx = filterListScroll + i
            val display = if (key.isEmpty()) Text.translatable("cobblemarket.gui.filter_any").string
                else Text.translatable(label).string.let { t -> if (t == label) label else t }
            val btn = NineSliceButton(
                fx, listTop + i * 14, fw, 14,
                Text.literal(TextUtil.truncateString(display, 260)),
                { selectFilterOption(filterListOpen, key, idx) },
                // 属性 / 太晶选项文字用对应属性色（两份列表共用同一套候选）
                // ⚠ 两种 key 形式都喂给 typeColor：属性是裸 key（`fire`）、太晶是完整 key，
                //   它内部走 `substringAfterLast(".")`，两种都能取到属性名
                if ((filterListOpen == "type" || filterListOpen == "tera") && key.isNotEmpty())
                    typeColor(key) else 0xFFFFFF
            )
            // 选中项靠**按钮自己的选中态**（背景第三段）表达，不再在文字前加 `● `
            // （用户 2026-09-28：按钮已经有选中态了，再用圆圈是同一件事的两套表达）
            btn.selectedVisual = isFilterSelected(filterListOpen, key, idx)
            filterOptionButtons.add(btn)
            addDrawableChild(btn)
        }
        // ⚠ **几何算完必须再刷一次可见性**：上面 920 行那次是在 `filterListTopY/BottomY` **算出来之前**跑的
        //   ⇒ 判"被浮层盖住"用的是上一次的（首次展开还是 0）⇒ 浮层下面那几个按钮**不会立刻藏**，
        //   文字就从浮层里透出来了（用户实测报的是太晶展开时超极巨化的字透过来）
        updateControlVisibility()
    }

    private fun isFilterSelected(kind: String, key: String, idx: Int): Boolean = when (kind) {
        // ⚠ 属性是**多选**（最多 2 个）⇒ 选中态要问"在不在已选集合里"，不是 `==`
        "type" -> key.isNotEmpty() && key in TypeFilter.keys(typeFilter)
        "tera" -> key.isNotEmpty() && key == teraFilter
        "ability" -> key.isNotEmpty() && key == abilityFilter
        "nature" -> key.isNotEmpty() && key == natureFilter
        else -> false
    }

    private fun selectFilterOption(kind: String, key: String, idx: Int) {
        when (kind) {
            "type" -> {
                // 多选切换：已选 → 取消；未选 → 加上；**已满 2 个再点第 3 个 → 失败音、不改状态**
                val picked = TypeFilter.toggle(TypeFilter.keys(typeFilter), key)
                if (picked == null) playFailSound() else typeFilter = TypeFilter.join(picked)
                typeButton.message = typeButtonText()
                (typeButton as? NineSliceButton)?.textColor = 0xFFFFFF
            }
            "tera" -> {
                teraFilter = key
                teraButton.message = teraButtonText()
                (teraButton as? NineSliceButton)?.textColor = 0xFFFFFF
            }
            "ability" -> {
                abilityFilter = key
                abilityButton.message = abilityButtonText()
                (abilityButton as? NineSliceButton)?.textColor = 0xFFFFFF
            }
            "nature" -> {
                natureFilter = key
                natureButton.message = natureButtonText()
                (natureButton as? NineSliceButton)?.textColor = 0xFFFFFF
            }
        }
        // ⚠ **属性是多选**（最多 2 个）⇒ 选中 / 取消都**不关浮层** —— 原来"选中就关"在双选下没法用
        //   （选完第一个窗就没了，还得重新点开）。收起仍靠再点一下「属性」按钮（[toggleFilterList]）。
        //   `rebuildFilterList()` 照调：浮层还开着，要重建来刷新选中态高亮
        if (kind != "type") filterListOpen = ""
        rebuildFilterList()
        currentPage = 1
        requestFilterRefresh()
    }

    /** 搜索框物种解析 → 特性筛选选项（与求购单创建同语义）；解析不出则清空选项并重置选择 */
    private fun updateAbilityOptions(text: String) {
        val trimmed = text.trim()
        val species = if (trimmed.isEmpty()) null else {
            if (trimmed.contains(":")) {
                Identifier.tryParse(trimmed)?.let { PokemonSpecies.getByIdentifier(it) }
            } else {
                val byName = try { PokemonSpecies.getByName(trimmed) } catch (_: Exception) { null }
                // 精确优先：中文名互为子串时别让「鬼斯」被「鬼斯通」截胡（见 SpeciesText）
                byName ?: com.shusheng.cobblemarket.util.SpeciesText.candidatesByNameOrId(trimmed).firstOrNull()
            }
        }
        abilityOptions = species?.abilities?.map { pa ->
            val key = "cobblemon.ability.${pa.template.name}"
            val t = Text.translatable(key).string
            key to (if (t == key) pa.template.displayName else t)
        } ?: emptyList()
        // 物种变化后旧特性选择失效，重置
        if (abilityFilter.isNotEmpty() && abilityOptions.none { it.first == abilityFilter }) {
            abilityFilter = ""
            abilityButton.message = abilityButtonText()
            (abilityButton as? NineSliceButton)?.textColor = 0xFFFFFF
        }
    }

    // ── Shiny toggle ──

    private fun toggleShiny() {
        shinyOnly = !shinyOnly
        currentPage = 1
        shinyButton.message = Text.literal(if (shinyOnly) "★" else "☆")
        // 开 = 金色 ★，关 = 白色 ☆（与价格限制/黑名单的闪光按钮一致）
        shinyButton.textColor = if (shinyOnly) GOLD_COLOR else 0xFFFFFF
        requestFilterRefresh()
    }

    // ── Hyper trained filter ──

    private fun htButtonText(): Text = Text.translatable(when (htFilter) {
        0 -> "cobblemarket.gui.filter_ht_any"
        1 -> "cobblemarket.gui.filter_ht_on"
        else -> "cobblemarket.gui.filter_ht_off"
    })

    private fun toggleHtFilter() {
        // 循环顺序：不限(0) → 不含特训(2) → 仅特训(1) → 不限
        htFilter = when (htFilter) {
            0 -> 2
            2 -> 1
            else -> 0
        }
        currentPage = 1
        htButton?.setMessage(htButtonText())
        // 筛选生效 = 金色，不限 = 白色（与闪光按钮一致）
        htButton?.textColor = if (htFilter != 0) GOLD_COLOR else 0xFFFFFF
        // 走防抖发送：立即发送会撞上服务端 250ms 节流（搜索刚发过请求时点击，请求被吞，
        // 按钮状态与列表脱节），防抖保证最终态请求落在节流窗口外
        searchDirty = true
        lastSearchEdit = System.currentTimeMillis()
    }

    // ── Sort cycling ──

    private fun cycleSort() {
        sortMode = when (sortMode) {
            "PRICE_ASC" -> "PRICE_DESC"
            "PRICE_DESC" -> "LEVEL_ASC"
            "LEVEL_ASC" -> "LEVEL_DESC"
            "LEVEL_DESC" -> "NEWEST"
            "NEWEST" -> "PRICE_ASC"
            else -> "PRICE_ASC"
        }
        currentPage = 1
        sortButton.message = Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay()))
        requestFilterRefresh()
    }

    private fun sortDisplay(): String = when (sortMode) {
        "PRICE_ASC" -> "cobblemarket.sort.price_asc"
        "PRICE_DESC" -> "cobblemarket.sort.price_desc"
        "LEVEL_ASC" -> "cobblemarket.sort.level_asc"
        "LEVEL_DESC" -> "cobblemarket.sort.level_desc"
        "NEWEST" -> "cobblemarket.sort.newest"
        else -> "cobblemarket.sort.price_asc"
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        // 确认/下架弹窗开着时：滚轮只滚弹窗信息区（弹窗被屏幕压矮时用），别把滚动漏给下层列表
        val dialogEntry = confirmEntry ?: cancelEntry
        if (dialogEntry != null) {
            val maxScroll = confirmMaxScroll(dialogEntry)
            if (maxScroll > 0) {
                confirmScroll = (confirmScroll - verticalAmount.toInt() * 10).coerceIn(0, maxScroll)
            }
            return true
        }
        // 右侧筛选面板**面板内滚动**：鼠标在面板上、且内容比面板高时（小屏 / GUI 缩放「自动」），
        // 滚轮滚面板而不是下层精灵列表 —— 否则底部的 IV 输入框根本够不到
        if (filterExpanded && filterPanelHeight() < FILTER_PANEL_HEIGHT &&
            mouseX >= MarketPanelBackground.filterLeft(width)
        ) {
            filterPanelScroll = (filterPanelScroll - verticalAmount.toInt() * 10)
                .coerceIn(0, FILTER_PANEL_HEIGHT - filterPanelHeight())
            updateControlVisibility()
            return true
        }
        // 筛选展开列表滚动
        if (filterListOpen.isNotEmpty()) {
            val total = when (filterListOpen) {
                "type" -> typeOptions.size
                // ⚠ 太晶与属性共用同一份候选 ⇒ 总数也要跟这里对上，漏了 = **展开后滚不动**（用户实测报的）
                "tera" -> typeOptions.size
                "ability" -> abilityOptions.size + 1
                "nature" -> natureOptions.size + 1
                else -> 0
            }
            if (total > MAX_FILTER_LIST_ROWS) {
                filterListScroll = (filterListScroll - verticalAmount.toInt()).coerceIn(0, total - MAX_FILTER_LIST_ROWS)
                rebuildFilterList()
            }
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    // ── Reset all filters ──

    private fun toggleFilters() {
        // clearChildren+init 重建会清空输入框（搜索词/IV 值丢失，玩家无法继续筛选）：
        // 重建前保存、重建后恢复（照 resize 的 oldSearch/oldIv 处理）
        val oldSearch = searchField?.text ?: ""
        val oldIv = if (filterExpanded) arrayOf(
            hpField?.text ?: "", atkField?.text ?: "", defField?.text ?: "",
            spaField?.text ?: "", spdField?.text ?: "", speField?.text ?: ""
        ) else emptyArray()
        filterExpanded = !filterExpanded
        // 面板开关动画：展开时从「筛选」按钮那里放大长出来、收起时缩回去（照市场进出场动画的手法）
        filterAnim.start(filterExpanded)
        // ⚠ 收起时把展开的选项列表**一并收掉**：那一组是**动态创建的独立控件**，面板收起不会自动
        //   跟着藏（它们不在 updateControlVisibility 的点名名单里），只能靠 rebuildFilterList 移除。
        //   而且 `filterListOpen` 不清的话，面板**再展开时浮层会自己冒出来**。
        //   （2026-09-28 用户实测报的：「属性 / 太晶展开时点筛选关掉，展开的按钮都还在」）
        if (!filterExpanded) {
            filterListOpen = ""
            filterListScroll = 0
            rebuildFilterList()
        }
        // ⚠ **不重建界面**：筛选面板的控件常驻（见 init 里的说明），开关只要切可见性。
        //   重建会清空 iconData 里的跨帧动画状态（睡觉的精灵会站起来一下再睡回去），
        //   还会把输入框内容、滚动位置一并丢掉。
        //   ⇒ 代价：开关按钮**自己**的图标 / 选中态 / 悬停提示要手动更一次（以前靠重建刷新）
        updateFilterToggleButton()
        updateControlVisibility()
    }

    /** 筛选开关按钮的外观随 [filterExpanded] 更新：图标（开/关两枚）、选中态、悬停提示 */
    private fun updateFilterToggleButton() {
        if (!::filterToggleButton.isInitialized) return
        filterToggleButton.iconLeft = Identifier.of("cobblemarket",
            if (filterExpanded) "textures/gui/filter.png" else "textures/gui/filter_close.png")
        filterToggleButton.selectedVisual = filterExpanded
        filterToggleButton.setTooltip(Tooltip.of(Text.translatable(
            if (filterExpanded) "cobblemarket.gui.filter_collapse" else "cobblemarket.gui.filter_expand")))
    }

    private fun toggleMineOnly() {
        showMineOnly = !showMineOnly
        mineButton.iconLeft = Identifier.of("cobblemarket",
            if (showMineOnly) "textures/gui/personal_click.png" else "textures/gui/personal.png")
        mineButton.setTooltip(Tooltip.of(Text.translatable(if (showMineOnly) "cobblemarket.gui.mine_active" else "cobblemarket.gui.mine")))
        currentPage = 1
        requestFilterRefresh()
    }

    private fun resetFilters() {
        searchField?.text = ""
        shinyOnly = false
        showMineOnly = false
        genderFilter = ""
        typeFilter = ""
        sortMode = "NEWEST"
        currentPage = 1
        for (i in 0..5) { ivExact[i] = -1; ivOps[i] = 0 }
        syncIvOpButtons()
        hpField?.text = ""
        atkField?.text = ""
        defField?.text = ""
        spaField?.text = ""
        spdField?.text = ""
        speField?.text = ""
        shinyButton.message = Text.literal("☆")
        shinyButton.textColor = 0xFFFFFF
        htFilter = 0
        htButton?.setMessage(htButtonText())
        htButton?.textColor = 0xFFFFFF
        abilityFilter = ""
        natureFilter = ""
        teraFilter = ""
        gmaxFilter = 0
        teraButton.message = teraButtonText()
        gmaxButton.message = gmaxButtonText()
        (teraButton as? NineSliceButton)?.textColor = 0xFFFFFF
        (gmaxButton as? NineSliceButton)?.textColor = 0xFFFFFF
        updateGenderButton()
        typeButton.message = typeButtonText()
        abilityButton.message = abilityButtonText()
        natureButton.message = natureButtonText()
        (typeButton as? NineSliceButton)?.textColor = 0xFFFFFF
        (abilityButton as? NineSliceButton)?.textColor = 0xFFFFFF
        (natureButton as? NineSliceButton)?.textColor = 0xFFFFFF
        sortButton.message = Text.translatable("cobblemarket.gui.sort", Text.translatable(sortDisplay()))
        mineButton.iconLeft = Identifier.of("cobblemarket", "textures/gui/personal.png")
        mineButton.setTooltip(Tooltip.of(Text.translatable("cobblemarket.gui.mine")))
        requestFilterRefresh()
    }

    // ── Pagination ──

    // 翻页请求在途标志：响应到达前不重复发请求
    private var pageRequestInFlight = false

    // 最近一次列表请求发送时间：补发节奏对齐服务端节流窗口（request_market 250ms）
    // 并留 100ms 余量——窗口同宽时网络抖动会让请求恰好落入服务端窗口被静默丢弃
    private var lastListRequestAt = 0L

    // 待翻页目标：点击立即更新页码并把目标页记到这里，tick 在窗口允许时补发请求。
    // 连点合并到最终目标页（中间页不发）；补发后保留目标，响应确认到达才清——
    // 请求若被静默丢弃，1s 兜底复位后 tick 自动重试
    private var pendingPage = 0

    // 筛选变更待发标志：筛选按钮连点/搜索防抖后若仍在服务端节流窗口内，记标志由 tick 补发
    // （否则第二次请求被 250ms 节流静默丢弃，按钮状态与列表数据错位）
    private var pendingFilterRefresh = false

    /** 筛选/搜索等「当前筛选态」请求统一入口：窗口允许立即发，否则排队 tick 补发 */
    private fun requestFilterRefresh() {
        if (!pageRequestInFlight && System.currentTimeMillis() - lastListRequestAt >= PAGE_CLICK_INTERVAL_MS) {
            refreshData()
        } else {
            pendingFilterRefresh = true
        }
    }

    private fun updatePageButtons() {
        prevButton?.active = currentPage > 1
        nextButton?.active = currentPage < totalPages
    }

    private fun prevPage() {
        if (currentPage <= 1) return
        currentPage--
        pendingPage = currentPage
        updatePageButtons()
    }

    private fun nextPage() {
        if (currentPage >= totalPages) return
        currentPage++
        pendingPage = currentPage
        updatePageButtons()
    }

    private fun requestBuy(listingId: java.util.UUID) {
        sendToServer(BuyFromMarketPayload(listingId, -1))
    }

    private fun openSellScreen() {
        client?.setScreen(SellSelectScreen())
    }

    private fun requestCancel(listingId: java.util.UUID) {
        sendToServer(CancelFromMarketPayload(listingId))
    }

    private fun collectBalance() {
        sendToServer(CollectBalancePayload())
    }

    // ── Render ──

    private fun isInputFieldFocused() = focused?.let { f ->
        f === searchField || f === hpField || f === atkField || f === defField || f === spaField || f === spdField || f === speField
    } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true ||
        hpField?.isMouseOver(mouseX, mouseY) == true ||
        atkField?.isMouseOver(mouseX, mouseY) == true ||
        defField?.isMouseOver(mouseX, mouseY) == true ||
        spaField?.isMouseOver(mouseX, mouseY) == true ||
        spdField?.isMouseOver(mouseX, mouseY) == true ||
        speField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (confirmEntry != null || cancelEntry != null) {
            // 弹窗是模态的：只响应弹窗自己的图标（此刻注册表里也只有它，下层行图标没渲染），
            // 下层行一律不响应 —— 与悬停预览共用同一条规则
            if (button == 0) IconCryClick.hitAt(mouseX, mouseY)?.let { IconCryClick.play(it.state, it.renderable) }
            handleConfirmDialogClick(mouseX.toInt(), mouseY.toInt())
            return true
        }
        // 行内：点整行触发叫声（图标只有 20px，点图标本身点不中）。右边界收窄到行尾按钮左侧，
        // 免得点到按钮也响；**不 return** —— 点行原有的行为照常叠加
        if (button == 0 && hoveredRow >= 0) {
            val rowLeftX = width / 2 - panelWidth / 2
            if (mouseX < rowLeftX + panelWidth - 42) {
                displayedListings().getOrNull(hoveredRow)?.let { entry ->
                    iconData[entry.index]?.let { IconCryClick.play(it.state, it.renderable) }
                }
            }
        }
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        return result
    }

    private fun syncIvFromFields(): Boolean {
        val fields = arrayOf(hpField, atkField, defField, spaField, spdField, speField)
        var changed = false
        for (i in 0..5) {
            val raw = fields[i]?.text ?: ""
            val digits = raw.filter { it.isDigit() }.take(2)
            // -1 sentinel: field is empty/unset, skip filtering
            // 0: user explicitly typed "0", filter for IV == 0
            val iv = if (digits.isEmpty()) -1 else digits.toIntOrNull()?.coerceIn(0, 31) ?: -1
            if (digits != raw) fields[i]?.text = if (iv <= 0) "" else iv.toString()
            if (ivExact[i] != iv) changed = true
            ivExact[i] = iv
        }
        return changed
    }

    // 防抖计时器：搜索框 changedListener 与 IV 输入变化都置 dirty，250ms 静默后统一发请求
    override fun tick() {
        // 兜底：翻页请求 1s 未收到响应（如服务端节流静默丢弃）强制复位 inFlight，防止按钮永久锁灰
        if (pageRequestInFlight && System.currentTimeMillis() - lastListRequestAt > 1000) {
            pageRequestInFlight = false
        }
        // 目标页码补发：窗口允许且无在途时发出请求（连点合并到最终目标页；
        // 目标保留到响应确认，请求被丢弃时兜底复位后自动重试）
        if (!pageRequestInFlight && pendingPage != 0 && System.currentTimeMillis() - lastListRequestAt >= PAGE_CLICK_INTERVAL_MS) {
            currentPage = pendingPage.coerceIn(1, maxOf(1, totalPages))
            pageRequestInFlight = true
            updatePageButtons()
            refreshData(resetPending = false)
        }
        // 筛选变更补发：与翻页共用 inFlight/窗口（筛选优先级低于翻页目标，翻页完成后再发筛选态）
        if (!pageRequestInFlight && pendingFilterRefresh && System.currentTimeMillis() - lastListRequestAt >= PAGE_CLICK_INTERVAL_MS) {
            pendingFilterRefresh = false
            pageRequestInFlight = true
            refreshData(resetPending = false)
        }
        if (searchDirty && System.currentTimeMillis() - lastSearchEdit >= 250) {
            searchDirty = false
            currentPage = 1
            requestFilterRefresh()
        }
    }

    private fun refreshData(ivs: IntArray = ivExact, resetPending: Boolean = true) {
        lastListRequestAt = System.currentTimeMillis()
        if (resetPending) pendingPage = 0 // 非翻页路径（搜索/筛选/购买后刷新）取消未发出的翻页目标
        sendToServer(
            RequestMarketPayload(
                speciesFilter = localizeSpeciesQuery(searchField?.text?.trim().orEmpty()),
                shinyOnly = shinyOnly,
                minLevel = 0,
                maxLevel = -1, // 哨兵：无上限（与 minLevel 的 0 哨兵对称）
                sortMode = sortMode,
                page = currentPage,
                genderFilter = genderFilter,
                typeFilter = typeFilter,
                abilityFilter = abilityFilter,
                natureFilter = natureFilter,
                teraFilter = teraFilter,
                gmaxFilter = gmaxFilter,
                ivExactHp = ivs[0],
                ivExactAtk = ivs[1],
                ivExactDef = ivs[2],
                ivExactSpAtk = ivs[3],
                ivExactSpDef = ivs[4],
                ivExactSpd = ivs[5],
                ivOpHp = ivOps[0],
                ivOpAtk = ivOps[1],
                ivOpDef = ivOps[2],
                ivOpSpAtk = ivOps[3],
                ivOpSpDef = ivOps[4],
                ivOpSpd = ivOps[5],
                pageSize = getMaxVisibleRows(),
                mineOnly = showMineOnly,
                htFilter = htFilter
            )
        )
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        IconRegistry.beginFrame(mouseX, mouseY)
        val centerX = width / 2
        // 「筛选」按钮开关的是**右侧那块面板**（原来开关的是界面上方的一行）。
        // ⚠ **主面板保持居中不动**（不传 leftOverride）—— 筛选面板占用它右侧本来就空着的区域，
        //   这样主面板里所有控件的坐标一个都不动；开关面板时主面板也不会平移。
        MarketPanelBackground.draw(context, width, height)
        // 面板展开中、或收起动画还没播完（后者要从目标位置缩回按钮）都要画
        if (filterExpanded || filterAnim.isActive()) {
            val fl = MarketPanelBackground.filterLeft(width)
            val fw = MarketPanelBackground.filterWidth(width)
            val ph = filterPanelHeight()
            val panelCx = fl + fw / 2f
            val panelCy = 2f + ph / 2f
            // 动画锚点 = 「筛选」按钮中心；按钮还没建时退化成"原地不动"（不影响正常开关）
            val btnCx = if (::filterToggleButton.isInitialized) filterToggleButton.x + filterToggleButton.width / 2f else panelCx
            val btnCy = if (::filterToggleButton.isInitialized) filterToggleButton.y + filterToggleButton.height / 2f else panelCy
            val animating = filterAnim.applyTransform(context, btnCx, btnCy, panelCx, panelCy)
            // ⚠ 收起动画的**最后一帧**：applyTransform 返回 false（进度到 1、内部刚清掉动画状态）
            //   —— 那一帧**不能画**，画了就是"全尺寸面板闪一下"（用户 2026-09-24 报：关闭动画结束后
            //   右侧闪了一下空的展开区域）。展开状态（filterExpanded）则必须照画：动画播完后
            //   isActive 恒为 false，若在这里跳过，主面板就再也不画了
            if (filterExpanded || animating) {
                MarketPanelBackground.draw(
                    context, width, height,
                    panelWidth = fw,
                    leftOverride = fl,
                    // 高度由内容定（2026-09-23 用户要求），但**不超屏幕**：小屏/自动档下取屏幕可用高度
                    heightOverride = ph,
                )
                if (animating) filterAnim.restore(context)
            }
        }
        // 展开列表的**不透明底块**：列表行的按钮背景中间是半透明的，不垫底的话它下面那几个
        // 筛选按钮会透上来（原来靠"隐藏被盖住的控件"解决，位置搬到右侧面板后改成垫底）
        if (filterListOpen.isNotEmpty() && filterListBottomY > filterListTopY) {
            val fl = MarketPanelBackground.filterLeft(width)
            context.fill(
                fl + 2, filterListTopY - 1,
                fl + MarketPanelBackground.filterWidth(width) - 2, filterListBottomY,
                0xF0101010.toInt()
            )
        }

        // 列表行背景（先于按钮渲染，避免覆盖按钮）
        val leftX = centerX - panelWidth / 2
        val startY = getListStartY()
        val rowHeight = 24
        val visibleRows = getMaxVisibleRows()
        val listAreaBottom = startY + visibleRows * rowHeight
        val displayList = displayedListings()

        hoveredRow = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY in startY..listAreaBottom) {
            val relY = mouseY - startY
            val row = relY / rowHeight
            if (row in displayList.indices) hoveredRow = row
        }

        displayList.take(visibleRows).forEachIndexed { di, _ ->
            val rowY = startY + di * rowHeight
            val rowState = if (di == hoveredRow) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, rowState, ROW_BACKGROUND_TEX_H)
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 固拉多第七步（精灵市场背景下层水平右飞）画在 super.render 之前：
        // super.render 内部先调 renderBackground 画市场面板背景，精灵先画会被面板自然盖住
        // 弹窗打开时不画（与末尾上层绘制一致；dialogOpen 是后段局部变量，这里用等价条件）
        if (confirmEntry == null && cancelEntry == null) {
            renderGroudonFly(context, true)
        }
        super.render(context, mouseX, mouseY, delta)

        // IV 变化与搜索框共用 250ms 防抖（searchDirty/lastSearchEdit，见 init 注释）：
        // 立即发包时，快速输入 "31" 会先发 atk=3 再发 atk=31，后一个请求落在
        // 服务端 250ms 节流窗口内被静默丢弃，列表停留在旧结果上。
        // ivExact 每帧同步，防抖期间其他按钮触发的 refreshData 仍带最新 IV 值。
        if (syncIvFromFields()) {
            searchDirty = true
            lastSearchEdit = System.currentTimeMillis()
        }

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        // 收款按钮右上角金币角标（8×8，仅 2px 露在角外，红点通知样式）：有待收款时显示；
        // 弹窗打开时此段在遮罩之前渲染，角标随下层一起被压暗
        if (pendingBalance > 0) {
            context.matrices.push()
            context.matrices.translate((leftX + 50 - 6).toDouble(), (13 - 2).toDouble(), 0.0)
            context.matrices.scale(1f / 6f, 1f / 6f, 1f)
            context.drawTexture(COIN_ICON, 0, 0, 0f, 0f, 48, 48, 48, 48)
            context.matrices.pop()
        }
        // 待领取按钮右上角红点（与收款角标同位置样式）：有待领取精灵时显示
        if (pendingReturns > 0) {
            context.matrices.push()
            context.matrices.translate((leftX + 102 - 6).toDouble(), (13 - 2).toDouble(), 0.0)
            context.matrices.scale(1f / 6f, 1f / 6f, 1f)
            context.drawTexture(RED_DOT, 0, 0, 0f, 0f, 48, 48, 48, 48)
            context.matrices.pop()
        }

        // Row 0 (y=8): Title
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.gui.title").formatted(Formatting.GOLD),
            centerX, 14, 0xFFFFFF
        )

        // Page indicator（右对齐）—— 先算出来，余额行才知道自己还剩多少可用宽
        val pageText = Text.translatable("cobblemarket.gui.page", currentPage, totalPages).formatted(Formatting.GRAY)
        val pageW = textRenderer.getWidth(pageText)

        // 余额 + 待收款：文字标签默认色，金额蓝/绿（2026-08-24 拍板）；余额来自全局缓存，交易操作后自动刷新。
        // ⚠ 两段都没有任何宽度防护：金额一长就压住页码、再长画出面板（296 的面板装不下大额）。
        // 可用宽 = 面板宽 − 页码宽 − 左右留白（从布局反算，别写死）；超宽时按三级降级：
        // 待收款为 0 整段不显示 → 金额换缩写（999,999,999 → 1.0B）→ 截断兜底
        val balUnit = com.shusheng.cobblemarket.client.inlineCurrencyUnit()
        val avail = panelWidth - 10 - pageW
        val balText: Text? = com.shusheng.cobblemarket.client.BalanceCache.balance.takeIf { it.isNotEmpty() }?.let {
            Text.translatable("cobblemarket.gui.balance", Text.literal("$it $balUnit").formatted(Formatting.GOLD))
        }
        val balW = balText?.let { textRenderer.getWidth(it) + 4 } ?: 0
        fun pendingLine(amount: String): Text = Text.translatable("cobblemarket.gui.pending_balance",
            Text.literal("$amount $balUnit").formatted(Formatting.GREEN))
        // 待收款为 0 时不占位：没有待领的东西就整段不显示
        val pendingFull: Text? = if (pendingBalance > 0)
            pendingLine(com.shusheng.cobblemarket.client.formatBalanceLong(pendingBalance)) else null
        val pendingText: Text? = pendingFull?.takeIf { balW + textRenderer.getWidth(it) <= avail }
            ?: pendingFull?.let { pendingLine(com.shusheng.cobblemarket.client.formatPriceShortLong(pendingBalance)) }

        if (balText != null) context.drawTextWithShadow(textRenderer, balText, leftX, 31, 0xFFFFFF)
        pendingText?.let {
            context.drawTextWithShadow(
                textRenderer,
                TextUtil.truncateStyled(it, avail - balW), leftX + balW, 31, 0xFFFFFF
            )
        }
        context.drawTextWithShadow(textRenderer, pageText, leftX + panelWidth - 4 - pageW, 32, 0xFFFFFF)

        val dividerY = getListStartY() - 4
        val startY = getListStartY()
        val rowHeight = 24
        val visibleRows = getMaxVisibleRows()

        // 分隔线照常画：筛选展开列表已经搬到右侧面板，不再盖住主面板的任何内容
        // （原先这里要判断"是否落在展开列表覆盖范围内"—— 那套补丁随搬迁一起删了）
        drawPanelDivider(context, leftX + panelWidth / 2, dividerY)
        // 底部分割线：最多显示行数的最后一行下方 4px（与顶部 startY-4 对称），分页按钮在其与背景底边之间居中
        val listBottom = getListStartY() + visibleRows * rowHeight
        drawPanelDivider(context, leftX + panelWidth / 2, listBottom + 4)

        val displayList = displayedListings()

        if (displayList.isEmpty() && filterListOpen.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.gui.no_listings").formatted(Formatting.GRAY),
                centerX, startY + 50, 0xFFFFFF
            )
        }

        // 确认弹窗打开时行内图标不渲染（物品/精灵模型图标走独立渲染层，z 平移盖不住，会刺穿遮罩）
        val dialogOpen = confirmEntry != null || cancelEntry != null
        // 模态层打开时下层控件的显隐（含弹窗 —— 不收掉的话鼠标停在弹窗按钮上会弹下层按钮的 tooltip）
        updateControlVisibility()
        displayList.take(visibleRows).forEachIndexed { di, (origIndex, entry) ->
            val y = startY + di * rowHeight

            // Pokemon icon slot background（GUI 层，弹窗遮罩会自动压暗，照常渲染）
            val slotX = leftX + 2
            val slotY = y + 2
            val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
            context.matrices.push()
            context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
            context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
            context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
            context.matrices.pop()

            // 3D Pokemon icon（弹窗打开时不渲染——模型层在衬底/遮罩之上，压暗仍会浮在弹窗上）
            // preview = 悬停行且预览图开关打开：该行图标切走路姿势，与大图保持一致（两者共用同一个 state）
            if (!dialogOpen) renderPokemonIcon(
                context, origIndex, leftX + 2, y + 2, iconSize, delta = delta,
                preview = com.shusheng.cobblemarket.client.ClientConfig.hoverPreview && di == hoveredRow
            )

            // Ball icon（球种统一在精灵名称左侧；弹窗打开时隐藏——物品图标无色调参数无法变暗）
            if (!dialogOpen) {
                iconData[origIndex]?.ballStack?.let { ballStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = ballStack,
                        x = leftX + 26.0,
                        y = y + 6.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                }
            }

            // Species name (translated) + 金色闪光星标（★，拆段绘制）；
            // 名字带阴影：行背景偏灰，暗色属性名（幽灵/恶等）无阴影看不清
            // 名字截断 54px ≈ 6 个汉字（超出带「…」）：名字后图标链固定 55px，与头像 149 仍留余量
            val displayName = TextUtil.truncateString(
                iconData[origIndex]?.displayName ?: entry.species, 54)
            context.drawTextWithShadow(textRenderer, displayName, leftX + 40, y + 7, typeColor(entry.primaryType))
            var nameWidth = textRenderer.getWidth(displayName)
            if (entry.shiny) {
                context.drawTextWithShadow(textRenderer, "★", leftX + 40 + nameWidth + 2, y + 7, GOLD_COLOR)
                nameWidth += 2 + textRenderer.getWidth("★")
            }

            // Gender icon (Cobblemon blue/red arrows)
            var iconOffset = 0
            if (entry.gender == "MALE" || entry.gender == "FEMALE") {
                val genderIcon = if (entry.gender == "MALE")
                    Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
                else
                    Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
                val genderX = leftX + 40 + nameWidth + EntryBadgeRenderer.NAME_TAIL_GAP
                com.cobblemon.mod.common.api.gui.blitk(
                    matrixStack = context.matrices,
                    texture = genderIcon,
                    x = genderX,
                    y = y + 7,
                    width = 6,
                    height = 8
                )
                iconOffset = 8
            }
            // Held item icon (after gender；弹窗打开时不渲染，防刺穿遮罩)
            if (!dialogOpen) {
                iconData[origIndex]?.heldStack?.let { heldStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = heldStack,
                        x = leftX + 40 + nameWidth + EntryBadgeRenderer.NAME_TAIL_GAP + iconOffset + 0.0,
                        y = y + 6.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                    iconOffset += 12
                }
            }
            // 体型徽章（排在携带物图标之后，留 3px 空隙）
            if (entry.sizeCategory.isNotEmpty()) {
                if (iconOffset > 0) iconOffset += 3
                iconOffset += EntryBadgeRenderer.drawSizeBadgeIcon(
                    context, entry.sizeCategory,
                    leftX + 40 + nameWidth + EntryBadgeRenderer.NAME_TAIL_GAP + iconOffset, y + 7
                )
            }

            // Seller avatar（名字区最坏到 149px——6 字名 + 星 + 性别 + 携带物 + 体型徽章，此处再留 8px）
            drawSellerAvatar(context, entry.sellerUuid, entry.sellerName, leftX + 149, y + 4, 16)

            // Level（跟随头像右移，保持 20px 间距）
            val levelText = Text.translatable("cobblemarket.gui.lv").string + entry.level
            context.drawText(textRenderer, levelText, leftX + 169, y + 7, 0x000000, false)

            // Price（行内缩写，同拍卖场/物品市场；悬停窗仍为完整千分位。右对齐到按钮左缘：价格再长也只向左延伸，不会遮按钮）
            val priceText = "${com.shusheng.cobblemarket.client.formatPriceShort(entry.price)} ${com.shusheng.cobblemarket.client.inlineCurrencyUnit()}"
            val btnLeft = leftX + panelWidth - 42
            context.drawTextWithShadow(textRenderer, priceText, btnLeft - textRenderer.getWidth(priceText) - 4, y + 7, 0xFFAA00)
        }

        // Tooltip on hover
        if (confirmEntry == null && hoveredRow in displayList.indices) {
            renderTooltip(context, displayList[hoveredRow].value, mouseX, mouseY)
        }

        prevButton.active = currentPage > 1
        nextButton.active = currentPage < totalPages

        // 悬停预览大图（最简版，先验证上层层级）：悬停哪行就显示哪只，位置固定在左侧空白区。
        // 姿势的切换与还原都由解析器按「本行是否预览态」每帧决定，这里不需要手动清理
        if (!dialogOpen && hoveredRow in displayList.indices) {
            val previewIndex = displayList[hoveredRow].index
            iconData[previewIndex]?.let { data ->
                PokemonHoverPreview.render(context, data.renderable, data.state, leftX, height)
            }
        }

        // 固拉多飞行：悬浮在界面之上（盖住面板/行），弹窗打开时不画（遮罩应盖住它）。
        // flush 夹心 + 清深度（照庆祝动画）：行内 3D 精灵图标/物品图标写深度或抬高 z，
        // 普通 GUI 纹理后画也会被它们盖住——先提交全部攒批、清深度后再画固拉多
        if (!dialogOpen) {
            context.draw()
            com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.MinecraftClient.IS_SYSTEM_MAC)
            renderGroudonFly(context, false)
            context.draw()
        }

        if (confirmEntry != null || cancelEntry != null) {
            renderConfirmDialog(context, mouseX, mouseY, delta)
        }
    }

    /** 固拉多飞行（重做第四步）：水平飞入停靠点 2s → 逆时针弧 90° 出屏 1.8s（θ 90°→0°，头朝上竖直出屏）→
     *  屏外切线掉头朝下 → 顺时针绕半圆 180° 到屏幕右半上方 1.2s（屏外提速）→
     *  屏外快速竖直下移 0.4s → 屏幕内加速下坠出屏底（速度与原 3s 版屏幕内段一致）→
     *  第四步（不连接上一步）：镜像图（头朝左、上下正常）从屏幕右上角外水平飞入停在右上角 1.2s，
     *  停留 5s 悬停（等下一步）。
     *  路径点 (px,py) 是图片左侧中间的轨迹（旋转轴心 = 图片左侧中间，贴图坐标 (0,150)），
     *  精灵主体拖在轴心后方，转弯时绕尾部牵引转向。
     *  40 帧拼在 sprite sheet 上只改 UV；共享 smoothRot 切换界面朝向平滑延续 */
    private fun renderGroudonFly(context: DrawContext, under: Boolean) {
        if (!com.shusheng.cobblemarket.client.ClientConfig.groudonFly) return
        val g = com.shusheng.cobblemarket.client.GroudonFly
        // 计时起点在全局 GroudonFly（照 PokemonCelebrationAnimation 的全局状态）：
        // 切换界面动画不重置，时间线延续
        if (g.startAt == 0L) g.startAt = System.currentTimeMillis()
        val t = (System.currentTimeMillis() - g.startAt).toFloat()
        // 轴心停靠点：左侧中间停在 (35,110) 时主体中心落在 (140,110)，与第一步停靠位置一致；
        // 半径 150 的圆弧：圆心在轴心正上方 150 处 (35,-40)（屏幕顶边外）
        val pivotX = 35f
        val pivotY = 110f
        val radius = 150f
        val centerX = pivotX
        val centerY = pivotY - radius
        val theta0 = atan2((pivotY - centerY).toDouble(), (pivotX - centerX).toDouble()).toFloat()
        // 逆时针段 θ: 90°→0°（绕 90°，头朝上出屏）
        val endTheta = 0f
        val arcEndMs = g.FLY_IN_MS + g.ARC_MS
        val arc2EndMs = arcEndMs + g.ARC2_MS
        // 下坠段：屏外 170px 用 DROP_OUT_MS 快速通过；屏幕内部分复刻原 3s k² 时间表，
        // 入屏时刻 tIn = 3000·√(170/(height+370))，之后按原曲线到 3000ms 出屏底，屏幕内速度感不变
        val tIn = 3000f * kotlin.math.sqrt(170f / (height + 370f))
        val dropEndMs = arc2EndMs + g.DROP_OUT_MS + (3000f - tIn)
        val flyIn2EndMs = dropEndMs + g.FLY_IN2_MS
        val hoverEndMs = flyIn2EndMs + g.HOVER_MS
        val flyLeftEndMs = hoverEndMs + g.FLY_LEFT_MS
        val flyIn3EndMs = flyLeftEndMs + g.FLY_IN2_MS
        val flyRightEndMs = flyIn3EndMs + g.FLY_RIGHT_MS
        // 顺时针半圆 180°：起点 = 出屏点（圆 (35,-40) 半径 150 的右端点 (185,-40)），
        // 终点 = 屏幕左半上方（1/4 宽处 (-170 高度)，倒放下坠段即「从左下飞到左上」）；圆心取两点中点，弧线拱向上方
        val arc2FromX = centerX + radius
        val arc2FromY = centerY
        val arc2ToX = width * 0.25f
        val arc2ToY = -170f
        val arc2CX = (arc2FromX + arc2ToX) / 2f
        val arc2CY = (arc2FromY + arc2ToY) / 2f
        val arc2R = kotlin.math.hypot(arc2FromX - arc2CX, arc2FromY - arc2CY)
        val arc2Theta0 = atan2((arc2FromY - arc2CY).toDouble(), (arc2FromX - arc2CX).toDouble()).toFloat()
        val arc2Theta1 = atan2((arc2ToY - arc2CY).toDouble(), (arc2ToX - arc2CX).toDouble()).toFloat() + (2f * PI.toFloat())
        // 整轮动画（上半 + 下半 = 2×flyRightEndMs）在精灵市场/上架选择界面循环播放：t 取模
        // 下半部分 = 上半部分绕屏幕中心的中心对称（180° 旋转：x、y 都翻转，顺序相同）：
        // tUp 从 0 顺序重演；视觉旋转方向不变（逆时针弧仍是逆时针，只是向下出屏底），运动方向反向；
        // 镜像图状态取反（头朝运动方向、正立不倒置）；层次判定按 tUp
        val tLoop = t % (2f * flyRightEndMs)
        val tUp: Float
        val flip: Boolean
        if (tLoop < flyRightEndMs) {
            tUp = tLoop
            flip = false
        } else {
            tUp = tLoop - flyRightEndMs
            flip = true
        }
        var px: Float
        var py: Float
        var targetRot: Float
        when {
            tUp < g.FLY_IN_MS -> {
                // 飞入：轴心从屏幕左边缘外水平飞向停靠点（朝右时图片整体在轴心右侧，轴心 x<-210 即全出屏），朝右。
                // 缓动末速度≈131px/s 与逆时针弧初线速度一致，速度连续无停顿（纯 ease-out 会停稳再启动）
                val k = tUp / g.FLY_IN_MS
                val s = 0.465f * (2f * k - k * k) + 0.535f * k
                px = -245f + (pivotX + 245f) * s
                py = pivotY
                targetRot = 0f
            }
            tUp < arcEndMs -> {
                // 逆时针弧 90° 出屏（视觉逆时针 = θ 减小，切线角 θ-90°，末段头朝上）
                val k = (tUp - g.FLY_IN_MS) / g.ARC_MS
                val theta = theta0 + (endTheta - theta0) * k
                px = centerX + radius * cos(theta)
                py = centerY + radius * sin(theta)
                targetRot = Math.toDegrees(theta.toDouble()).toFloat() - 90f
            }
            tUp < arc2EndMs -> {
                // 顺时针绕半圆 180°（θ 增大，切线角 θ+90°）：屏外掉头，弧线拱向上方到屏幕右半
                val k = (tUp - arcEndMs) / g.ARC2_MS
                val theta = arc2Theta0 + (arc2Theta1 - arc2Theta0) * k
                px = arc2CX + arc2R * cos(theta)
                py = arc2CY + arc2R * sin(theta)
                targetRot = Math.toDegrees(theta.toDouble()).toFloat() + 90f
            }
            tUp < dropEndMs -> {
                // 头朝下竖直下坠：屏外快速下移（不可见段提速），入屏后按原 3s 版屏幕内速度曲线加速
                val dt = tUp - arc2EndMs
                px = arc2ToX
                if (dt < g.DROP_OUT_MS) {
                    py = arc2ToY * (1f - dt / g.DROP_OUT_MS)
                } else {
                    val kk = (tIn + (dt - g.DROP_OUT_MS)) / 3000f
                    py = arc2ToY + (height + 200f - arc2ToY) * (kk * kk)
                }
                targetRot = 90f
            }
            tUp < flyIn2EndMs -> {
                // 第四步：镜像图（头朝左）从屏幕右上角外水平 ease-out 飞入，
                // 停在右上角（轴心 (width,90)：主体 x [width-210,width]、y [0,180] 刚好贴住右上角）
                val k = (tUp - dropEndMs) / g.FLY_IN2_MS
                val s = 1f - (1f - k) * (1f - k)
                px = (width + 245f) - 245f * s
                py = 90f
                targetRot = 0f
            }
            tUp < hoverEndMs -> {
                // 右上角悬停 5s，头正朝左
                px = width.toFloat()
                py = 90f
                targetRot = 0f
            }
            tUp < flyLeftEndMs -> {
                // 第五步：水平向左飞出屏幕，smoothstep 缓动（悬停平滑启动、出屏减速停稳）
                val k = (tUp - hoverEndMs) / g.FLY_LEFT_MS
                val s = 3f * k * k - 2f * k * k * k
                px = width.toFloat() - (width + 245f) * s
                py = 90f
                targetRot = 0f
            }
            tUp < flyIn3EndMs -> {
                // 第六步：恢复原图（再次镜像，头朝右）从左下角外水平飞入，穿过左下角不停留；
                // 缓动末速 ≈269px/s 与第七步匀速一致（速度连续，无停→启动停顿）
                val k = (tUp - flyLeftEndMs) / g.FLY_IN2_MS
                val s = 0.68f * k + 0.32f * k * k
                px = -245f + 245f * s
                py = height - 90f
                targetRot = 0f
            }
            tUp < flyRightEndMs -> {
                // 第七步：在市场面板背景下层水平向右飞（绘制在面板之前，被背景盖住），
                // 匀速 ≈269px/s 飞出屏幕右侧
                val k = (tUp - flyIn3EndMs) / g.FLY_RIGHT_MS
                px = 0f + (width + 245f) * k
                py = height - 90f
                targetRot = 0f
            }
            else -> {
                // 停在屏幕右侧外（等下一步）
                px = width + 245f
                py = height - 90f
                targetRot = 0f
            }
        }
        // 下半部分中心对称修正：x、y 都绕屏幕中心翻转（180° 旋转），朝向角不变（运动方向自然反向）
        if (flip) {
            px = width - px
            py = height - py
        }
        // 层次过滤：第六步（左下角飞入）与第七步（背景下层水平右飞）从飞出开始全程画在背景下层，
        // 避免第六步上层→第七步下层的层级突变；其余段画在上层。每帧只绘制一次，smoothRot 也只更新一次
        val isUnder = tUp >= flyLeftEndMs && tUp < flyRightEndMs
        if (isUnder != under) return
        g.smoothRot += (((targetRot - g.smoothRot + 540f) % 360f) - 180f) * 0.15f
        val rotDeg = g.smoothRot
        val flyFrame = ((System.currentTimeMillis() / g.FRAME_MS) % 40).toInt()
        val flyTex = Identifier.of("cobblemarket", "textures/gui/groudon_sheet.png")
        val flyCol = flyFrame % 10
        val flyRow = flyFrame / 10
        val flyW = 210f
        // 第四、五步用镜像图（scale x 取负，静态镜像非旋转中翻面）：头朝左、上下正常；
        // 第六步起恢复原图（再次镜像）。负 scale 翻转三角形朝向，行内 3D 精灵图标渲染
        // 可能残留背面剔除开启会整图不画——绘制时临时关 cull，画完按原状态恢复
        // 正向镜像区间为第四、五步；下半部分（中心对称）运动方向相反，镜像状态取反（头朝运动方向、正立不倒置）
        val mirrored = (tUp >= dropEndMs && tUp < flyLeftEndMs) != flip
        val wasCull = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_CULL_FACE)
        if (mirrored) org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_CULL_FACE)
        context.matrices.push()
        context.matrices.translate(px.toDouble(), py.toDouble(), 0.0)
        context.matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(rotDeg))
        context.matrices.scale(if (mirrored) -flyW / 350f else flyW / 350f, 180f / 300f, 1f)
        context.drawTexture(flyTex, 0, -150, (flyCol * 350).toFloat(), (flyRow * 300).toFloat(), 350, 300, 3500, 1200)
        context.matrices.pop()
        if (mirrored && wasCull) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_CULL_FACE)
    }

    /**
     * 确认/下架弹窗高度：按信息区实际内容算——起始 +62，13 基础行
     * （名字/类型/性格特性/[球种]/[携带物]/IV 标签/6×IV/亲密度/[证章]/卖家/价格）
     * 加球种/携带物行与证章区块，底部再留 34px 给按钮（按钮高 20 + 4px 间隙 + 10px 边距）。
     * 加新信息行（如技能）时只改这里的 13，按钮位置自动跟随。
     */
    private fun confirmDialogHeight(entry: ListingEntry): Int {
        // 极巨行 / 初始训练家行：有才显示（见 PokemonExtraLines），行数要跟着加
        val infoRows = 13 +
            (if (entry.gmaxFactor || entry.dmaxLevel > 0) 1 else 0) +
            (if (entry.originalTrainer.isNotEmpty()) 1 else 0) +
            (if (entry.ball.isNotEmpty()) 1 else 0) +
            (if (EntryBadgeRenderer.hasHeldItemLine(entry)) 1 else 0)
        return 62 + infoRows * 10 +
            EntryBadgeRenderer.movesBlockHeight(entry.moves) +
            // 证章一行几个跟随可用宽（见 marksPerRow）—— 保守传最小宽 ⇒ 高度宁多不少
            EntryBadgeRenderer.marksBlockHeight(entry.marks, entry.moves, 124) + 34
    }

    /**
     * 弹窗实际高度：**按屏幕钳制**（照求购单创建弹窗）—— 信息行多（技能块 / 两行证章 / 球种 / 携带物）
     * 时设计高度能到 320+，而自动 GUI 尺寸在 1080p 下逻辑高只有 270，不钳制会顶出屏幕。
     */
    private fun confirmDialogH(entry: ListingEntry): Int = minOf(confirmDialogHeight(entry), height - 8)

    /** 信息区可视高（弹窗高 − 顶部标题/图标 − 底部按钮区） */
    private fun confirmViewH(entry: ListingEntry): Int =
        (confirmDialogH(entry) - confirmContentTop - confirmBottomH).coerceAtLeast(1)

    /** 信息区内容高（设计值） */
    private fun confirmContentH(entry: ListingEntry): Int = confirmDialogHeight(entry) - confirmContentTop - confirmBottomH

    private fun confirmMaxScroll(entry: ListingEntry): Int =
        maxOf(0, confirmContentH(entry) - confirmViewH(entry))

    private fun renderConfirmDialog(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val entry = confirmEntry ?: cancelEntry ?: return
        val centerX = width / 2
        // 喵喵支付可用时加宽 280 容纳三枚按钮；不可用回退原宽度 220（两枚）
        val dialogW = maxOf(if (payAvailable) 340 else 300, EntryBadgeRenderer.measureInfoLines(entry, EntryBadgeRenderer.nameWithShinyStar(if (confirmDisplayName.isNotEmpty()) confirmDisplayName else entry.species, entry.shiny)) + 40).coerceAtMost(width - 20)
            // ⚠ 下限 280：喵喵支付三按钮固定占 248（±124），上限钳制不能把它吃掉
            .coerceAtLeast(280)
        val dialogH = confirmDialogH(entry)
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        // 遮罩（提高 z，盖住底层列表）
        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 100.0)
        drawScreenDimMask(context, width, height)
        context.matrices.pop()

        // 弹窗背景（z 高于遮罩）
        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 200.0)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)

        // 标题
        val titleKey = if (cancelEntry != null) "cobblemarket.item.cancel_title" else "cobblemarket.buy_confirm.title"
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable(titleKey).formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        // 精灵 3D 图标（槽背景 + 精灵，对齐列表图标渲染）
        val iconSize = 28
        val iconX = centerX - iconSize / 2
        val iconY = dialogY + 28
        val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
        context.matrices.push()
        context.matrices.translate(iconX.toDouble(), iconY.toDouble(), 0.0)
        context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
        context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
        context.matrices.pop()
        // 记下这个图标的位置（庆祝动画要从这里起飞）；拿不到 renderable 时清掉，免得沿用上一只的旧坐标
        confirmIconOrigin = if (confirmRenderable == null) null
        else PokemonCelebrationAnimation.Origin.ofIcon(
            x = iconX, y = iconY, size = iconSize, centerAnchorY = false
        )
        confirmRenderable?.let { rp ->
            // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
            val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
            // 弹窗图标原先没有裁剪，沿用 useScissor = false
            PokemonIconRenderer.draw(
                context = context,
                renderable = rp,
                state = confirmState.stateFor(rp),
                x = iconX, y = iconY, size = iconSize,
                delta = delta,
                animate = useFloat,
                useScissor = false
            )
            // 打开弹窗时自动叫一声（替玩家点一下这个图标）：**必须在渲染之后** ——
            // addFirstAnimation 依赖 currentModel，那是 drawProfilePokemon 里才建好的；
            // 再叠一层 AUTO_CRY_DELAY_MS 延迟，等弹窗稳定显示、玩家看清了再叫
            if (confirmCryAt != 0L && System.currentTimeMillis() >= confirmCryAt) {
                confirmCryAt = 0L
                IconCryClick.play(confirmState.stateFor(rp), rp)
            }
        }

        // 完整信息行（与市场列表悬停 tooltip 结构一致）：名字★Lv / 类型 / 性格特性 / 携带物 / IV / 技能 / 证章 / 卖家 / 价格
        // 弹窗被屏幕压矮时**信息区可滚动**：只画可视区（scissor 裁剪），起始 y 减去滚动偏移
        val name = EntryBadgeRenderer.nameWithShinyStar(
            if (confirmDisplayName.isNotEmpty()) confirmDisplayName else entry.species, entry.shiny)
        confirmScroll = confirmScroll.coerceIn(0, confirmMaxScroll(entry))
        val viewTop = dialogY + confirmContentTop
        val viewBottom = dialogY + dialogH - confirmBottomH
        context.enableScissor(dialogX, viewTop, dialogX + dialogW, viewBottom)
        EntryBadgeRenderer.drawInfoLines(
            context, entry, name, centerX, viewTop - confirmScroll, dividerW = dialogW - 156, maxWidth = dialogW - 24
        )
        context.disableScissor()

        // 按钮行（btnY = 弹窗底 -30；购买且喵喵支付可用：三枚并排；否则两枚）
        val btnW = 80
        val btnH = 20
        val btnY = dialogY + dialogH - 30
        val threeButtons = cancelEntry == null && payAvailable
        val confirmX = if (threeButtons) centerX - btnW - 44 else centerX - btnW - 5
        val payX3 = centerX - 40
        val cancelX = if (threeButtons) centerX + 44 else centerX + 5
        val confirmHover = mouseX in confirmX..(confirmX + btnW) && mouseY in btnY..(btnY + btnH)
        val cancelHover = mouseX in cancelX..(cancelX + btnW) && mouseY in btnY..(btnY + btnH)
        val payHover3 = mouseX in payX3..(payX3 + btnW) && mouseY in btnY..(btnY + btnH)

        drawNineSlice(context, BUTTON_TEXTURE, confirmX, btnY, btnW, btnH, if (confirmHover) 1 else 0, BUTTON_TEX_H)
        drawNineSlice(context, BUTTON_TEXTURE, cancelX, btnY, btnW, btnH, if (cancelHover) 1 else 0, BUTTON_TEX_H)
        val confirmKey = if (cancelEntry != null) "cobblemarket.item.cancel_confirm" else "cobblemarket.buy_confirm.confirm"
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable(confirmKey), confirmX + btnW / 2, btnY + (btnH - 8) / 2, 0xFFFFFF)
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("cobblemarket.buy_confirm.cancel"), cancelX + btnW / 2, btnY + (btnH - 8) / 2, 0xFFFFFF)
        // 中间喵喵支付按钮（点击进独立 MeowthPayScreen）
        if (threeButtons) {
            drawNineSlice(context, BUTTON_TEXTURE, payX3, btnY, btnW, btnH, if (payHover3) 1 else 0, BUTTON_TEX_H)
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.buy_confirm.meowth_pay"),
                payX3 + btnW / 2, btnY + (btnH - 8) / 2, 0xFFFFFF
            )
        }
        context.matrices.pop()
    }

    private fun handleConfirmDialogClick(mx: Int, my: Int) {
        val centerX = width / 2
        val confirmOrCancel = confirmEntry ?: cancelEntry
        val dialogH = confirmOrCancel?.let { confirmDialogH(it) } ?: 250
        val dialogY = height / 2 - dialogH / 2
        // 中间喵喵支付按钮（购买确认弹窗且消费贷开）：点击进独立 MeowthPayScreen
        if (cancelEntry == null && payAvailable) {
            val btnY = dialogY + dialogH - 30
            val payX = centerX - 40
            if (mx in payX..(payX + 80) && my in btnY..(btnY + 20)) {
                playClickSound()
                openMeowthPay()
                return
            }
        }
        val btnW = 80
        val btnH = 20
        val btnY = dialogY + dialogH - 30
        val threeButtons = cancelEntry == null && payAvailable
        val confirmX = if (threeButtons) centerX - btnW - 44 else centerX - btnW - 5
        val cancelX = if (threeButtons) centerX + 44 else centerX + 5
        if (mx in confirmX..(confirmX + btnW) && my in btnY..(btnY + btnH)) {
            playClickSound()
            if (cancelEntry != null) confirmCancel() else confirmPurchase()
        } else if (mx in cancelX..(cancelX + btnW) && my in btnY..(btnY + btnH)) {
            playClickSound()
            closeConfirmDialog()
        }
    }

    private fun playClickSound() {
        client?.soundManager?.play(PositionedSoundInstance.master(
            SoundEvent.of(Identifier.of("cobblemarket", "button_click")),
            1.0f
        ))
    }

    // ── 3D Pokemon icon rendering ──

    private fun renderPokemonIcon(context: DrawContext, index: Int, x: Int, y: Int, size: Int, delta: Float = 0f, preview: Boolean = false) {
        val data = iconData[index] ?: run {
            // Fallback: type-colored placeholder
            val entry = listings.getOrNull(index) ?: return
            val tc = typeColor(entry.primaryType)
            context.fill(x, y, x + size, y + size, 0x88000000.toInt())
            context.fill(x + 1, y + 1, x + size - 1, y + size - 1, tc or 0xCC000000.toInt())
            val initial = (iconData[index]?.displayName ?: entry.species).take(2)
            val tw = textRenderer.getWidth(initial)
            context.drawTextWithShadow(textRenderer, initial, x + (size - tw) / 2, y + (size - 8) / 2, 0xFFFFFF)
            if (entry.shiny) context.drawTextWithShadow(textRenderer, "★", x + size - 6, y - 5, GOLD_COLOR)
            return
        }
        // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
        val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
            com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
        try {
            PokemonIconRenderer.draw(
                context = context,
                renderable = data.renderable,
                state = data.state,
                x = x, y = y, size = size,
                delta = delta,
                animate = useFloat,
                preview = preview
            )
        } catch (e: Exception) {
            // Fallback on error（残留的 scissor/matrices 由 PokemonIconRenderer 的 finally 收尾）
            val entry = listings.getOrNull(index) ?: return
            val tc = typeColor(entry.primaryType)
            context.fill(x, y, x + size, y + size, 0x88000000.toInt())
            context.fill(x + 1, y + 1, x + size - 1, y + size - 1, tc or 0xCC000000.toInt())
            val initial = (iconData[index]?.displayName ?: entry.species).take(2)
            val tw = textRenderer.getWidth(initial)
            context.drawTextWithShadow(textRenderer, initial, x + (size - tw) / 2, y + (size - 8) / 2, 0xFFFFFF)
        }
    }

    // ── Tooltip ──

    private fun renderTooltip(context: DrawContext, entry: ListingEntry, mouseX: Int, mouseY: Int) {
        // 文本行缓存：悬停同一行时内容不变，只在悬停目标变化时重建（见 tooltipCacheKey 注释）
        if (tooltipCacheKey != entry.id) {
            tooltipCacheKey = entry.id
            val hp = StatNames.HP
            val atk = StatNames.ATK
            val def = StatNames.DEF
            val spa = StatNames.SP_ATK
            val spd = StatNames.SP_DEF
            val spe = StatNames.SPEED

            val w = 0xFFFFFF
            val ivColors = intArrayOf(0x66FF66, 0xFF6666, 0xFFCC66, 0x6699FF, 0x66FF99, 0xFF99FF)

            val hasHeldItem = entry.heldItemId.isNotEmpty() &&
                Identifier.tryParse(entry.heldItemId)?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true

            // 行列表：null = 分割线（1px 灰线）；证章行在 marksLine 索引处画图标
            val lines = mutableListOf<Pair<Text?, Int>>()
            lines.add(EntryBadgeRenderer.nameWithLevelLine(
                EntryBadgeRenderer.nameWithShinyStar(iconData[listings.indexOf(entry)]?.displayName ?: entry.species, entry.shiny),
                entry.primaryType, entry.level) to w)
            lines.add(com.shusheng.cobblemarket.util.TypeTextColors.typeLineWithTera(
                entry.primaryType, entry.secondaryType, entry.teraType) to w)
            // 极巨行：属性行下面、有才显示（与市场侧走同一个组装入口）
            com.shusheng.cobblemarket.util.PokemonExtraLines.dynamax(entry.gmaxFactor, entry.dmaxLevel)
                ?.let { lines.add(it to w) }
            // 性格 + 特性同一行：走共用入口（两段间的间距只在 EntryBadgeRenderer 里定义）
            lines.add(EntryBadgeRenderer.natureAbilityLine(entry.natureBase, entry.nature, entry.ability) to w)
            if (entry.ball.isNotEmpty()) {
                // 球名走**稀有度色**（大师球在物品栏里是紫的）—— 共用入口 ItemNameText.ballLine
                lines.add(com.shusheng.cobblemarket.util.ItemNameText.ballLine(entry.ball, entry.ballItem) to w)
            }
            var heldItemLine = -1
            if (hasHeldItem) {
                heldItemLine = lines.size
                lines.add(Text.translatable("cobblemarket.gui.tooltip_held") to w)
            }
            lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs") to w)
            lines.add(Text.literal("  $hp: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsHp, entry.htHp)}").append(Text.literal("  EV: ${entry.evsHp}").formatted(Formatting.RED)) to ivColors[0])
            lines.add(Text.literal("  $atk: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsAtk, entry.htAtk)}").append(Text.literal("  EV: ${entry.evsAtk}").formatted(Formatting.RED)) to ivColors[1])
            lines.add(Text.literal("  $def: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsDef, entry.htDef)}").append(Text.literal("  EV: ${entry.evsDef}").formatted(Formatting.RED)) to ivColors[2])
            lines.add(Text.literal("  $spa: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpAtk, entry.htSpAtk)}").append(Text.literal("  EV: ${entry.evsSpAtk}").formatted(Formatting.RED)) to ivColors[3])
            lines.add(Text.literal("  $spd: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpDef, entry.htSpDef)}").append(Text.literal("  EV: ${entry.evsSpDef}").formatted(Formatting.RED)) to ivColors[4])
            lines.add(Text.literal("  $spe: ${com.shusheng.cobblemarket.util.TextUtil.ivText(entry.ivsSpd, entry.htSpd)}").append(Text.literal("  EV: ${entry.evsSpd}").formatted(Formatting.RED)) to ivColors[5])
            lines.add(Text.translatable("cobblemarket.gui.friendship", entry.friendship) to 0xFF99CC)
            // 初始训练家行：亲密度下面（与市场侧同位置；没有则整行不占位）
            com.shusheng.cobblemarket.util.PokemonExtraLines.originalTrainer(entry.originalTrainer)
                ?.let { lines.add(it to 0xFFFFFF) }
            // 技能块（**总是画** —— Cobblemon 里不存在没技能的精灵）：分割线 + 两行技能 + 分割线，整块在 drawMovesBlockLeft 里画
            var movesLine = -1
            if (MoveText.hasAny(entry.moves)) {
                movesLine = lines.size
                lines.add(null to 0)
            }
            // 证章区块（拥有的全部证章，纯外观）：技能块下方，图标行 + 下分割线（与技能块共用上分割线）
            // 服务端直接传纹理路径（不依赖客户端 Marks 注册表解析）
            var marksLine = -1
            var marksRows = 0
            val markTextures = entry.marks.mapNotNull { Identifier.tryParse(it) }
            if (markTextures.isNotEmpty()) {
                marksLine = lines.size
                lines.add(null to 0)
                lines.add(null to 0)
            }
            lines.add(Text.translatable("cobblemarket.gui.tooltip_seller").append(Text.literal(entry.sellerName)) to w)
            lines.add(Text.translatable("cobblemarket.gui.tooltip_price").append(
                Text.literal("${com.shusheng.cobblemarket.client.formatPrice(entry.price)} ${com.shusheng.cobblemarket.client.displayCurrency(entry.currencyName)}").formatted(Formatting.GOLD)) to w)

            var maxWidth = 0
            lines.forEach { if (it.first != null) maxWidth = maxOf(maxWidth, textRenderer.getWidth(it.first)) }
            // 名字行尾部图标（公母 + 体型徽章）不计入文本宽度，单独补上
            lines[0].first?.let { maxWidth = maxOf(maxWidth, EntryBadgeRenderer.nameLineWidth(it, entry.gender, entry.sizeCategory)) }
            if (heldItemLine >= 0) {
                maxWidth = maxOf(maxWidth, textRenderer.getWidth(lines[heldItemLine].first) + 14)
            }
            maxWidth = maxOf(maxWidth, EntryBadgeRenderer.movesBlockWidth(entry.moves))
            // ⚠ 证章**不再参与** maxWidth（原先那行 `maxOf(maxWidth, minOf(10, size) * 12)` 已删）——
            //   方向反过来：**窗口宽度决定证章排几个**（见 [EntryBadgeRenderer.marksPerRow]），
            //   所以必须在 maxWidth 定稿之后才算「占几行」
            marksRows = when {
                markTextures.isEmpty() -> 0
                markTextures.size > EntryBadgeRenderer.marksPerRow(maxWidth) -> 2
                else -> 1
            }
            tooltipCacheLines = lines
            tooltipCacheHeldLine = heldItemLine
            tooltipCacheMovesLine = movesLine
            tooltipCacheMarksLine = marksLine
            tooltipCacheMarksRows = marksRows
            tooltipCacheMaxWidth = maxWidth
        }
        // 行数按屏幕高钳制（叠上技能块 / 证章后仍可能超屏）—— 见 [clampTooltipRows]
        val lines = clampTooltipRows(tooltipCacheLines, height) { n ->
            Text.translatable("cobblemarket.gui.tooltip_more", n) to 0xAAAAAA
        }
        val heldItemLine = tooltipCacheHeldLine
        val movesLine = tooltipCacheMovesLine
        val marksLine = tooltipCacheMarksLine
        val marksRows = tooltipCacheMarksRows
        val maxWidth = tooltipCacheMaxWidth

        val padding = 4
        val tooltipHeight = lines.size * 10 + padding + (marksRows - 1).coerceAtLeast(0) * 12 +
            EntryBadgeRenderer.movesBlockExtra(entry.moves)
        // 悬停窗**不许把本行的按钮（购买 / ✕）整个盖住**：自动 GUI 尺寸下逻辑屏宽小，靠右缘一挤就压在按钮上。
        // 矩形**按行号算几何**（`rowButtonRect`，与建按钮同源）—— 不去查按钮列表，免得受列表构建时序/下标影响
        val avoid = rowButtonRect(hoveredRow)
        val (tx, ty) = TooltipPlacement.place(
            mouseX = mouseX, mouseY = mouseY,
            textW = maxWidth, textH = tooltipHeight, pad = padding,
            screenW = width, screenH = height, avoid = avoid
        ) ?: return

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, tooltipHeight + padding, 1, ROW_BACKGROUND_TEX_H)
        var rowY = ty
        lines.forEachIndexed { i, (line, color) ->
            if (line == null && i == movesLine) {
                // 技能块：分割线 + 两行技能 + 分割线（左对齐版，分割线撑满提示框全宽）
                rowY += EntryBadgeRenderer.drawMovesBlockLeft(context, entry.moves, tx, maxWidth, rowY)
            } else if (line == null && i == marksLine) {
                // 证章图标行：**一行放几个跟随可用宽**（见 [EntryBadgeRenderer.marksPerRow]）；放不下的第二行「+N」
                val textures = entry.marks.mapNotNull { Identifier.tryParse(it) }
                val perRow = EntryBadgeRenderer.marksPerRow(maxWidth)
                val shown = minOf(perRow, textures.size)
                // 整行在可用宽里**居中**（分割线撑满可用宽，图标不占满时会显偏左）
                val rowWidth = shown * 12 - 4
                val startX = tx + (maxWidth - rowWidth) / 2
                textures.take(perRow).forEachIndexed { idx, texture ->
                    com.cobblemon.mod.common.api.gui.blitk(
                        matrixStack = context.matrices, texture = texture,
                        x = startX + idx * 12, y = rowY, width = 8, height = 8
                    )
                }
                if (textures.size > perRow) {
                    context.drawCenteredTextWithShadow(textRenderer, "+${textures.size - perRow}", startX + rowWidth / 2, rowY + 12, 0xAAAAAA)
                    rowY += 24
                } else {
                    rowY += 12
                }
            } else if (line == null) {
                // 分割线：1px 灰线，撑满提示框全宽（与信息区分隔线一致，不随证章数量变化）
                val rowW = maxWidth
                context.fill(tx, rowY + 4, tx + rowW, rowY + 5, 0xFF888888.toInt())
                rowY += 10
            } else if (i == heldItemLine) {
                context.drawTextWithShadow(textRenderer, line, tx, rowY, color)
                val heldIconX = tx + textRenderer.getWidth(line) + 2
                heldIconStack(entry.heldItemId, entry.heldItemNbt)?.let { heldStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = heldStack,
                        x = heldIconX.toDouble(),
                        y = rowY + 0.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                }
                // 玩家把携带物设成「隐藏」时注明一句（模型上不显示它，而信息行照常列出）—— 用户 2026-09-26
                if (!entry.heldItemVisible) {
                    context.drawTextWithShadow(
                        textRenderer, Text.translatable("cobblemarket.gui.held_hidden"), heldIconX + 12, rowY, 0xAAAAAA
                    )
                }
                rowY += 10
            } else if (i == 0) {
                // 第一行（名字★Lv）带公母图标
                EntryBadgeRenderer.drawNameLineLeft(context, line, entry.gender, tx, rowY, color, entry.sizeCategory)
                rowY += 10
            } else {
                context.drawTextWithShadow(textRenderer, line, tx, rowY, color)
                rowY += 10
            }
        }
        context.matrices.pop()
    }

    // ── Type color mapping ──

    private fun typeColor(typeKey: String): Int = when (typeKey.substringAfterLast(".").lowercase()) {
        "normal" -> 0xAAAA99; "fire" -> 0xFF4422; "water" -> 0x3399FF
        "electric" -> 0xFFCC33; "grass" -> 0x77CC55; "ice" -> 0x66CCFF
        "fighting" -> 0xBB5544; "poison" -> 0xAA5599; "ground" -> 0xDDBB55
        "flying" -> 0x8899FF; "psychic" -> 0xFF5599; "bug" -> 0xAABB22
        "rock" -> 0xBBAA66; "ghost" -> 0x6666BB; "dragon" -> 0x7766EE
        "dark" -> 0x775544; "steel" -> 0xAAAABB; "fairy" -> 0xFFAAFF
        else -> 0xFFFFFF
    }

    override fun shouldPause(): Boolean = false

    private companion object {
        val GENDER_ICON_MALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
        val GENDER_ICON_FEMALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
        const val MAX_FILTER_LIST_ROWS = 8
        /** 补发请求最小间隔：服务端 request_market 节流 250ms + 100ms 余量，防网络抖动边界丢弃 */
        const val PAGE_CLICK_INTERVAL_MS = 350L
        /** 收款按钮右上角金币角标（48×48 源图按 1/6 缩到 8×8） */
        val COIN_ICON = Identifier.of("cobblemarket", "textures/gui/coin_icon.png")
        /** 待领取按钮右上角红点（48×48 源图按 1/6 缩到 8×8） */
        val RED_DOT = Identifier.of("cobblemarket", "textures/gui/red_dot.png")
    }

    override fun resize(client: net.minecraft.client.MinecraftClient, width: Int, height: Int) {
        val oldSearch = searchField?.text ?: ""
        val oldIv = if (filterExpanded) arrayOf(
            hpField?.text ?: "", atkField?.text ?: "", defField?.text ?: "",
            spaField?.text ?: "", spdField?.text ?: "", speField?.text ?: ""
        ) else emptyArray()
        super.resize(client, width, height)
        searchField?.text = oldSearch
        if (filterExpanded) {
            val fields = arrayOf(hpField, atkField, defField, spaField, spdField, speField)
            oldIv.forEachIndexed { i, t -> fields[i]?.text = t }
        }
    }

    private var pendingBalance = 0L
    /** 待领取精灵数量：>0 时待领取按钮右上角画红点（随市场数据响应刷新） */
    private var pendingReturns = 0
    /** 收款按钮引用：右上角金币角标按 pendingBalance 显隐（render 里直接按坐标画，不依赖按钮状态） */
    private var collectButton: NineSliceButton? = null

    fun onMarketData(payload: MarketDataPayload) {
        pageRequestInFlight = false
        listings = payload.entries
        totalPages = payload.totalPages
        when {
            pendingPage == 0 -> currentPage = payload.currentPage
            // 目标页已到达（或被服务端 clamp 出界）：确认，清目标
            payload.currentPage == pendingPage || pendingPage > payload.totalPages -> {
                pendingPage = 0
                currentPage = payload.currentPage
            }
            // 目标未达（罕见）：保留目标由 tick 继续重试，页码维持乐观值
            else -> {}
        }
        updatePageButtons()
        pendingBalance = payload.pendingBalance
        pendingReturns = payload.pendingReturns
        indexedListings = listings.withIndex().toList()
        cacheIcons()
        rebuildBuyButtons()
    }

    fun onMarketResult(payload: MarketResultPayload) {
        if (client?.player != null) {
            val color = if (payload.success) Formatting.GREEN else Formatting.RED
            client!!.player!!.sendMessage(payload.message.copy().formatted(color), false)
            // Note: payload.message is already translated server-side (single-player compatible)
        }
        if (payload.success) refreshData()
    }
}
