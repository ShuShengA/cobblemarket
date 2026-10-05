package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.TooltipPlacement
import com.shusheng.cobblemarket.client.activeCurrencyUnit
import com.shusheng.cobblemarket.client.currencyUnitWidth
import com.shusheng.cobblemarket.client.drawCurrencyUnit
import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.finance.FinanceChangeLogger
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.network.FinanceChangesPayload
import com.shusheng.cobblemarket.network.RequestFinanceChangesPayload
import com.shusheng.cobblemarket.network.AdminCreditSetPayload
import com.shusheng.cobblemarket.network.AdminLoanBlockPayload
import com.shusheng.cobblemarket.network.AdminLoanUnblockPayload
import com.shusheng.cobblemarket.network.CreditAdjustListPayload
import com.shusheng.cobblemarket.network.CreditLookupPayload
import com.shusheng.cobblemarket.network.CreditRankPayload
import com.shusheng.cobblemarket.network.CreditRowInfo
import com.shusheng.cobblemarket.network.RequestCreditRankPayload
import com.shusheng.cobblemarket.network.RequestCreditAdjustListPayload
import com.shusheng.cobblemarket.network.RequestCreditLookupPayload
import com.shusheng.cobblemarket.network.CardHolderEntry
import com.shusheng.cobblemarket.network.CardHolderListPayload
import com.shusheng.cobblemarket.network.LoanBlockInfo
import com.shusheng.cobblemarket.network.LoanBlockListPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PlayerNameSuggestionsPayload
import com.shusheng.cobblemarket.network.RequestCardHolderListPayload
import com.shusheng.cobblemarket.network.RequestLoanBlockListPayload
import com.shusheng.cobblemarket.network.RequestCardRevokePayload
import com.shusheng.cobblemarket.network.RequestPlayerNameSuggestionsPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/**
 * 金融管理（FINANCE 节点；喵喵银行界面的入口，2026-10-05 建）。
 *
 * 与 [AdminBanScreen]（封禁管理）**结构对称**：一个管交易封禁、一个管金融 —— 版式照它抄
 * （标题 20 / info 18 / 页签行 44 / 表单行 / 分割线 / 列表），本屏唯一的新东西是**页签会有多个**。
 *
 * ### 页签
 *
 * | 页签 | 内容 | 需要表单行 |
 * |---|---|---|
 * | 禁止借款 | 名单 + 搜索框/时长/按钮（照封禁管理那套） | ✓ |
 * | 卡片管理 | 现有 `CardManageScreen` 的列表搬进来（持有者 + 收回） | ✗ |
 *
 * ⚠⭐ 版式数字**一律由 [tabRows] / [formRowY] 推导**，别在别处写字面量 ——
 *   页签从 1 行涨到 2 行时，下面所有 y 都要跟着走（本项目「挪版式忘改下游」栽过 6 次）。
 * ⚠ 控件可见性**收口在 [hideMainControls]**：遮罩只挡视线、挡不住点击（封禁界面实测报过）。
 */
class AdminFinanceScreen : Screen(Text.translatable("cobblemarket.finance.title")) {

    // 296：与黑名单 / 封禁管理等**同背景的列表界面**统一基准
    private val panelWidth = 296
    /** 列表行高：记录/列表类界面一律 24（项目约定） */
    private val rowHeight = 24
    private val tabRowY = 44

    /**
     * 页签行数：≤3 枚排一行，4~5 枚排两行（用户 2026-10-05 定：宁可让出列表一行，
     * 也不把页签标签压成 `No-Loan` 这种缩写）。
     */
    private val tabRows = if (TABS.size <= 3) 1 else 2

    /** 表单行 y（第一行正文）；⚠ 由页签行数推导，别写死 */
    private val formRowY = tabRowY + 14 + 4 + (tabRows - 1) * 18

    /** 列表起始 y：需要表单行的页签接在表单下面，不需要的直接贴页签行 */
    private fun listStartY() = formRowY + if (currentTab in FORM_TABS) 24 else 4

    private var currentTab = 0
    private val tabButtons = mutableListOf<NineSliceButton>()

    // ── 页签 0：禁止借款 ──
    private var nameField: TextFieldWidget? = null
    private var durationField: TextFieldWidget? = null
    private var blockButton: NineSliceButton? = null
    private var entries = listOf<LoanBlockInfo>()
    private var hoveredRow = -1
    private var scrollOffset = 0
    /** 行按钮待重建（下一帧做，见 [requestRowRebuild]） */
    private var rowRebuildPending = false
    private val rowButtons = mutableListOf<NineSliceButton>()

    // ── 页签 1：卡片管理 ──
    private var cardEntries = listOf<CardHolderEntry>()
    private val cardButtons = mutableListOf<Pair<NineSliceButton, String>>()

    // ── 页签 2：额度管理 ──
    /**
     * 被人工调整过额度的玩家（服务端实算的额度，不是全服）。
     * ⚠⭐ 这张表里的「额度」是**算出来的**（`公式base + 人工修正 − 欠款`）⇒ 玩家在别处还一笔款，
     *   这个数当场就变。放成静止快照就是**假数据**（用户 2026-10-05 实测：把某人调到 0，
     *   那人还清欠款后自己看到 30，这边还显示 0）。⇒ 停留在这个页签时按 [CREDIT_REFRESH_MS] 重拉。
     */
    private var creditRows = listOf<CreditRowInfo>()

    /** 额度列表上次重拉的时刻（见 [creditRows] 的说明） */
    private var lastCreditRefreshAt = 0L
    private var creditQueryField: TextFieldWidget? = null
    private var creditQueryButton: NineSliceButton? = null
    private val creditButtons = mutableListOf<NineSliceButton>()

    // 调整额度弹窗（查询到目标后才开）
    private var creditTarget: CreditRowInfo? = null
    private var creditAmountField: TextFieldWidget? = null
    private var creditReasonField: TextFieldWidget? = null
    private val creditDialogButtons = mutableListOf<NineSliceButton>()

    // ── 页签 4：额度排行 ──
    /**
     * 全服额度前 50（服务端算好、**顺序即名次**）。
     * ⚠⭐ **只在进页签 / 点刷新时拉**：它是"全服算一遍"的结果，不像额度列表那样每 2 秒重拉
     *   （那个只算几个人）—— 排行看的是趋势，不需要灵敏。
     */
    private var rankRows = listOf<CreditRowInfo>()
    private var rankRefreshButton: NineSliceButton? = null

    // ── 页签 3：变更记录 ──
    /**
     * 一条变更记录 → 行内文本 + 悬停窗内容（**数据到达时算一次**，render 每帧只读 ——
     * 时间格式化 + 词条拼接每帧现算，在全量列表上就是可见的掉帧源，照 `AdminBanScreen`）。
     */
    private data class ChangeLine(val text: Text, val tooltip: List<Text>)
    private var changeLines = listOf<ChangeLine>()

    private var backButton: NineSliceButton? = null
    private var infoButton: NineSliceButton? = null

    // ── 禁借确认弹窗（照封禁确认弹窗：先确认再发） ──
    private var pendingName = ""
    private var pendingDuration = ""
    private var reasonField: TextFieldWidget? = null
    private var confirmButtons = mutableListOf<NineSliceButton>()

    // 玩家名联想（覆盖存档所有登录过的玩家，含离线）：输入防抖后请求服务端，展开列表点选
    private var suggestions = listOf<String>()
    private var suggestionsOpen = false
    private var suggestDirty = false
    private var lastSuggestEdit = 0L
    private var suggestScroll = 0
    private var suppressSuggest = false
    private var lastRequestPrefix: String? = null
    private val suggestionButtons = mutableListOf<NineSliceButton>()

    companion object {
        /**
         * 页签名连成一行（喵喵银行那枚「金融管理」入口按钮的悬停提示用）。
         *
         * ⚠⭐ **别再手写这份清单** —— 用户 2026-10-05 实测报的就是它漏了两枚页签
         *   （提示里只有「禁止借款 / 卡片」，额度管理与变更记录都没写）。从 [TABS] 生成 ⇒ 增删页签自动跟上。
         */
        fun tabListText(): Text {
            val out = Text.literal("")
            TABS.forEachIndexed { i, spec ->
                if (i > 0) out.append(Text.literal(" / "))
                out.append(Text.translatable(spec.key))
            }
            return out
        }

        private const val MAX_SUGGESTION_ROWS = 8

        /** 文字页签的统一宽度（照权限管理 / 封禁管理那两屏的规格） */
        const val TAB_W = 80

        /**
         * 图标页签的宽度 —— 只放两张卡图标，用不到 80（用户 2026-10-05：「可以不用这么长」）；
         * 48 正好是原来喵喵银行上那枚按钮的宽度，观感对得上。
         */
        const val TAB_W_ICON = 48

        /** 图标页签上卡图标的边长：页签只有 14 高（图标原尺寸 16 会上下各顶出 1px） */
        const val TAB_ICON = 14

        /**
         * 一个页签：词条 + 宽度 + 是否用**两张卡图标**代替文字。
         * ⚠ 图标页签的词条仍要填 —— 它挂在悬停提示上（光两张图看不出来是什么）。
         */
        private data class TabSpec(val key: String, val width: Int, val icon: Boolean = false)

        /**
         * 当前实现的页签（**按实现进度往里加**；加一枚就自动重排 —— 每枚自己的宽度由 [TabSpec] 带，
         * 不用手改坐标、也不会因为某枚变窄把别人挤歪）。
         */
        // ⚠ 保持 private：它的元素类型 TabSpec 是私有的，公开出去编译器会拦（伴生对象对外只用 tabListText()）
        private val TABS = listOf(
            TabSpec("cobblemarket.finance.tab_loan_block", TAB_W),
            // 「卡片管理」用**两张卡图标**（用户 2026-10-05 定：就是原来喵喵银行那枚按钮的样子）
            TabSpec("cobblemarket.finance.tab_cards", TAB_W_ICON, icon = true),
            TabSpec("cobblemarket.finance.tab_credit", TAB_W),
            TabSpec("cobblemarket.finance.tab_changes", TAB_W),
            TabSpec("cobblemarket.finance.tab_rank", TAB_W)
        )

        /** 带表单行（搜索框 / 操作按钮）的页签下标 —— 列表起始 y 与控件可见性都按它判 */
        val FORM_TABS = setOf(0, 2, 4)

        /**
         * 额度列表的自动重拉间隔（照余额 HUD 那档 2 秒）—— 只在停留在额度页签、且没开弹窗时发。
         * ⚠ 别的页签**不这么干**：禁借列表的「剩余时间」虽然也会走，但封禁管理那边同样是快照口径
         *   （用户实测过、认可），为了保持一致不额外轮询；额度这里轮询是因为**行里的主内容就是个算出来的数**。
         */
        const val CREDIT_REFRESH_MS = 2000L

        /**
         * 禁借确认弹窗高度 —— 控件定位与背景绘制**共用这一个数**（照封禁那枚 `DIALOG_H` 的教训）。
         * ⚠ 与封禁确认弹窗**取同一个值**（那边也是 148：按钮 118 + 20 + 距底边框 10）——
         *   两个弹窗长得一样，改一处记得另一处一起看
         */
        const val DIALOG_H = 148

        /**
         * 调整额度弹窗高度 —— 比禁借弹窗多一行「当前额度」和一行理由输入框。
         * 版式：标题 14 / 目标 40 / 当前额度 58 / 新额度框 76(h16) / 理由框 100(h16) /
         *       按钮 126(h20) / 距底边框 10（约定值）
         */
        const val CREDIT_DIALOG_H = 156

        /** 变更记录**行内**的时间：只到分钟就够（当天看的是"什么时候"的大致位置） */
        private val changeTimeFormat = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")

        /** 悬停窗里的时间：给**完整日期 + 秒** —— 账本本来就是按秒记的，查账时要能对得上 */
        private val changeTooltipTimeFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2

        val backBtn = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(MeowthBankScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        backButton = backBtn
        addDrawableChild(backBtn)

        // info 图标（左上角）：这个界面管什么、不影响什么 —— 与封禁管理 / 交易历史同一套做法
        val infoBtn = NineSliceButton(
            width / 2 - 148, 18, 16, 16,
            Text.literal(""),
            { },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/info.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        )
        infoBtn.setTooltip(
            net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.finance.info_tip"))
        )
        infoButton = infoBtn
        addDrawableChild(infoBtn)

        rebuildTabs()

        val savedName = nameField?.text ?: ""
        nameField = TextFieldWidget(textRenderer, leftX + 2, formRowY, 120, 16, Text.literal(""))
        nameField?.setPlaceholder(Text.translatable("cobblemarket.finance.player_name"))
        nameField?.setChangedListener {
            if (suppressSuggest) {
                suppressSuggest = false
                return@setChangedListener
            }
            suggestDirty = true
            lastSuggestEdit = System.currentTimeMillis()
        }
        addSelectableChild(nameField)
        addDrawableChild(nameField)
        nameField?.text = savedName

        durationField = TextFieldWidget(textRenderer, leftX + 126, formRowY, 90, 16, Text.literal(""))
        durationField?.setPlaceholder(Text.translatable("cobblemarket.ban.duration"))
        addSelectableChild(durationField)
        addDrawableChild(durationField)

        val blockBtn = NineSliceButton(
            leftX + 220, formRowY, 76, 16,
            Text.translatable("cobblemarket.finance.block"),
            { openConfirmDialog() }
        )
        blockButton = blockBtn
        addDrawableChild(blockBtn)

        // 额度管理页签的表单行：搜索框（同一个联想通道）+「调整额度」按钮
        // ⚠ 宽度**不跟禁止借款那排一样**：那边中间要让给「时长」输入框（起于 +126），
        //   这边没有时长框 ⇒ 搜索框一路占满到按钮左边留 4px（用户 2026-10-05 实测指出：
        //   照搬 120 会在中间空出近百像素）
        val savedQuery = creditQueryField?.text ?: ""
        creditQueryField = TextFieldWidget(textRenderer, leftX + 2, formRowY, 214, 16, Text.literal(""))
        creditQueryField?.setPlaceholder(Text.translatable("cobblemarket.finance.player_name"))
        creditQueryField?.setChangedListener {
            if (suppressSuggest) {
                suppressSuggest = false
                return@setChangedListener
            }
            suggestDirty = true
            lastSuggestEdit = System.currentTimeMillis()
        }
        addSelectableChild(creditQueryField)
        addDrawableChild(creditQueryField)
        creditQueryField?.text = savedQuery

        val creditBtn = NineSliceButton(
            leftX + 220, formRowY, 76, 16,
            Text.translatable("cobblemarket.finance.credit_adjust"),
            { queryCreditTarget() }
        )
        creditQueryButton = creditBtn
        addDrawableChild(creditBtn)

        // 额度排行页签的表单行：一枚「刷新」（主动重拉最新排行）。
        // ⚠ 位置与其它页签的动作按钮**同 x 同宽**（+220 / 76），换页签时按钮不会跳位
        val rankBtn = NineSliceButton(
            leftX + 220, formRowY, 76, 16,
            Text.translatable("cobblemarket.finance.rank_refresh"),
            { sendToServer(RequestCreditRankPayload()) }
        )
        rankRefreshButton = rankBtn
        addDrawableChild(rankBtn)

        applyTabVisibility()
        scrollOffset = 0
        // ⚠ 关弹窗走的是 `clearChildren()` + `init()` ⇒ 行按钮被清掉了，这里补一次重建请求
        //   （只靠 `requestCurrentTabData` 的回包也行，但那要等一个网络来回、会看到按钮空一下）
        requestRowRebuild()
        requestCurrentTabData()
    }

    /** 拉当前页签的数据（**每次切页签都重拉** —— 别人刚改过的状态要能看见，照 `AdminBanScreen`） */
    private fun requestCurrentTabData() {
        when (currentTab) {
            0 -> sendToServer(RequestLoanBlockListPayload())
            1 -> sendToServer(RequestCardHolderListPayload())
            2 -> sendToServer(RequestCreditAdjustListPayload())
            4 -> sendToServer(RequestCreditRankPayload())
            // ⚠⭐ 变更记录**每次切过来都重拉**（照封禁那张账本）：刚在别的页签做过的操作、
            //   别的管理员做的、系统的，都可能在你停留期间出现 —— 写成"只拉一次"就会一直显示旧数据
            else -> sendToServer(RequestFinanceChangesPayload())
        }
    }

    /** 当前页签那个「输入玩家名」的框（禁止借款 / 额度管理各有一个，联想通道共用） */
    private fun queryField(): TextFieldWidget? = if (currentTab == 2) creditQueryField else nameField

    // ── 页签几何（按 TABS 数量自动排版，加页签不用手改坐标） ──

    /**
     * 建页签按钮。
     * ⚠ 位置与文案**一处算**：x 由本函数按「当前几枚」居中推出 —— 页签从 2 枚长到 5 枚
     *   只多一枚按钮，不需要重算别人的坐标（这正是「挪版式」最容易漏下游的地方）。
     */
    private fun rebuildTabs() {
        tabButtons.forEach { remove(it) }
        tabButtons.clear()
        // ≤3 枚一行；4 枚起分两行、且**两行一样多**（4 ⇒ 2+2、5 ⇒ 3+2）——
        // 用 3+1 会在第二行留一枚孤零零的页签，看着像没做完
        val perRow = if (TABS.size <= 3) 3 else (TABS.size + 1) / 2
        val gap = 4
        // 每行按**本行实际那几枚**的宽度和居中（各枚宽度不同 ⇒ 必须逐枚累加，不能拿「枚数×统一宽」算）
        val rows = TABS.indices.groupBy { it / perRow }
        rows.forEach { (row, idxInRow) ->
            val rowW = idxInRow.sumOf { TABS[it].width } + (idxInRow.size - 1) * gap
            var x = width / 2 - rowW / 2
            val y = tabRowY + row * 18
            idxInRow.forEach { i ->
                val spec = TABS[i]
                val btn = NineSliceButton(
                    x, y, spec.width, 14,
                    // 图标页签不放文字（改成两张卡图标，见 render 的叠加绘制），
                    // 但把词条挂成悬停提示 —— 光两张图看不出来是"卡片管理"
                    if (spec.icon) Text.literal("") else Text.translatable(spec.key),
                    { switchTab(i) },
                    tooltip = if (spec.icon) Text.translatable(spec.key) else null
                )
                tabButtons.add(btn)
                addDrawableChild(btn)
                x += spec.width + gap
            }
        }
        updateTabButtons()
    }

    private fun updateTabButtons() {
        // 选中的页签靠**按钮自己的选中态**表达（背景第三段），不在文字前加 `● `
        tabButtons.forEachIndexed { i, btn -> btn.selectedVisual = i == currentTab }
    }

    private fun switchTab(tab: Int) {
        if (currentTab == tab) return
        currentTab = tab
        scrollOffset = 0
        hoveredRow = -1
        updateTabButtons()
        // 联想列表只属于「禁止借款」页签；切页签时先收掉（否则联想按钮会残留在新页签上可点）
        suggestionsOpen = false
        suggestDirty = false
        lastRequestPrefix = null
        rebuildSuggestionList()
        applyTabVisibility()
        requestRowRebuild()
        requestCurrentTabData()
    }

    /** 表单行（玩家名 / 时长 / 按钮）只属于需要它的页签（见 `FORM_TABS`） */
    private fun applyTabVisibility() {
        val isBlockTab = currentTab == 0
        nameField?.visible = isBlockTab
        durationField?.visible = isBlockTab
        blockButton?.visible = isBlockTab
        val isCreditTab = currentTab == 2
        creditQueryField?.visible = isCreditTab
        creditQueryButton?.visible = isCreditTab
        rankRefreshButton?.visible = currentTab == 4
    }

    // ── 数据到达 ──

    fun onLoanBlocks(payload: LoanBlockListPayload) {
        entries = payload.entries
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, entries.size - maxVisible()))
        requestRowRebuild()
    }

    fun onCardHolders(payload: CardHolderListPayload) {
        cardEntries = payload.entries
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, cardEntries.size - maxVisible()))
        requestRowRebuild()
    }

    fun onCreditRows(payload: CreditAdjustListPayload) {
        creditRows = payload.rows
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, creditRows.size - maxVisible()))
        requestRowRebuild()
    }

    fun onCreditRank(payload: CreditRankPayload) {
        rankRows = payload.rows
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, rankRows.size - maxVisible()))
        requestRowRebuild()
    }

    /** 查询结果到达 ⇒ 打开调整弹窗（新额度预填当前额度，服主在上面改） */
    fun onCreditLookup(payload: CreditLookupPayload) {
        openCreditDialog(payload.row)
    }

    /** 表单行那个按钮：查当前额度（查到了服务端回 `CreditLookupPayload`，再开弹窗） */
    private fun queryCreditTarget() {
        val name = creditQueryField?.text?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            playFailSound()
            return
        }
        sendToServer(RequestCreditLookupPayload(name))
    }

    fun onResult(payload: MarketResultPayload) {
        client?.player?.sendMessage(
            payload.message.copy().formatted(if (payload.success) Formatting.GREEN else Formatting.RED), false
        )
        requestCurrentTabData()
    }

    private fun maxVisible() = maxOf(0, (height - 48 - listStartY()) / rowHeight)

    private fun rowCount() = when (currentTab) {
        0 -> entries.size
        1 -> cardEntries.size
        2 -> creditRows.size
        4 -> rankRows.size
        else -> changeLines.size
    }

    // ── 行按钮 ──

    /**
     * 任一确认弹窗是否开着（禁借 / 调整额度）。
     * ⚠ 弹窗状态不止一个字段 —— 两处弹窗的判断都必须走这里，别再各写各的。
     */
    private fun dialogOpen() = pendingName.isNotEmpty() || creditTarget != null

    /**
     * 请求重建行按钮（**下一帧**才真建）+ **立刻把现有的全部藏掉**。
     *
     * ⚠⭐ 不能在 `mouseClicked` 的调用栈里 `remove()` 子元素 —— **该操作不可靠**（本项目既有结论，
     *   封禁界面栽过，那里为此专门写了注释）。而**页签切换正是从按钮的 onPress 里调过来的** ⇒
     *   旧页签的行按钮会残留下来，跟新页签的行按钮**重合**、点下去执行的还是旧页签那个
     *   （用户 2026-10-05 实测：从额度管理切到禁止借款，「调整额度」压着「解除」，点了开的是额度弹窗）。
     *
     * ⇒ 一律延迟到下一帧（`render` 开头）重建；这中间先把旧的藏起来，别留那一下点得到的窗口。
     */
    private fun requestRowRebuild() {
        rowButtons.forEach { it.visible = false }
        cardButtons.forEach { (b, _) -> b.visible = false }
        creditButtons.forEach { it.visible = false }
        rowRebuildPending = true
    }

    private fun rebuildRowButtons() {
        rowButtons.forEach { remove(it) }
        rowButtons.clear()
        cardButtons.forEach { (btn, _) -> remove(btn) }
        cardButtons.clear()
        // ⚠ 额度行按钮**也必须清**：不清的话每次重建都往 children 末尾再堆一批（旧的还在、点得到）
        creditButtons.forEach { remove(it) }
        creditButtons.clear()
        // ⚠⭐ 弹窗开着 / 联想列表展开时**一个行按钮都不建**。
        //   理由不只是"别挡视线"：新建的按钮会被追加到 children **末尾** ⇒ **画在遮罩之上、还点得到**
        //   （用户 2026-10-05 实测报的「调整额度弹窗下层那个按钮亮着、还能点」）。
        //   ⚠ 光靠 `hideMainControls` 事后隐藏不够 —— 开弹窗那条路上 `rebuildSuggestionList` 会被调一次，
        //   它内部会重建行按钮，把刚隐藏的又建回来（封禁界面是用 `pendingBanName.isEmpty()` 做的同一件事）
        if (suggestionsOpen || dialogOpen()) return
        val leftX = width / 2 - panelWidth / 2
        val startY = listStartY()
        if (currentTab == 0) {
            // ⚠ 捕获该行的 entry（别存下标回头查：滚动 / 数据刷新后下标会错位）
            entries.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, e ->
                val btn = NineSliceButton(
                    leftX + panelWidth - 60, startY + i * rowHeight + 3, 56, 16,
                    Text.translatable("cobblemarket.finance.unblock"),
                    { sendToServer(AdminLoanUnblockPayload(e.playerUuid)) }
                )
                btn.visible = pendingName.isEmpty()
                rowButtons.add(btn)
                addDrawableChild(btn)
            }
            return
        }
        // 额度管理（2）与额度排行（4）**共用同一套行按钮**：都是「调整额度」、都开同一个弹窗，
        // 只是行的来源不同（被调整过的 / 前 50 名）—— 排行的行**也能直接点开调**，这正是做排行的目的
        if (currentTab == 2 || currentTab == 4) {
            val rows = if (currentTab == 4) rankRows else creditRows
            val creditInset = 68
            val creditW = 64
            rows.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, row ->
                val btn = NineSliceButton(
                    leftX + panelWidth - creditInset, startY + i * rowHeight + 4, creditW, 16,
                    Text.translatable("cobblemarket.finance.credit_adjust"),
                    {
                        // ⚠ 置灰用 `dimmed`（`active = false` 是 MC 的"禁用"、点了**没声音**）——
                        //   所以拦截要写在这里：点了给对方一声 fail，别让人以为是卡住了
                        if (row.hasCard) playFailSound() else openCreditDialog(row)
                    },
                    tooltip = if (row.hasCard) Text.translatable("cobblemarket.finance.credit_card_fixed_tip") else null
                )
                // 持卡玩家：额度 = 配置固定值、不走公式 ⇒ 调了也没意义（服务端也拦，这里只是别让人白点）
                btn.dimmed = row.hasCard
                creditButtons.add(btn)
                addDrawableChild(btn)
            }
            return
        }
        // 页签 3（变更记录）没有行按钮 —— 上面两个分支都没命中就到这里，直接收工
        if (currentTab != 1) return
        // 页签 1：卡片管理 —— 卡组（图标 + 按钮）右对齐行尾，双持时紫组在黑组左侧
        // （算式照 `CardManageScreen`：按钮永远在行末尾，图标在按钮左）
        val rightEdge = leftX + panelWidth - 8
        val btnW = 44
        val iconW = 16
        val gap = 4
        cardEntries.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, e ->
            val y = startY + i * rowHeight + 4
            var cursor = rightEdge
            if (e.holdsBlack) {
                val btn = NineSliceButton(
                    cursor - btnW, y, btnW, 16,
                    Text.translatable("cobblemarket.card.manage_revoke"),
                    { sendToServer(RequestCardRevokePayload(e.uuid, "black")) }
                )
                cardButtons.add(btn to "black")
                addDrawableChild(btn)
                cursor -= btnW + gap + iconW + gap + gap
            }
            if (e.holdsPurple) {
                val btn = NineSliceButton(
                    cursor - btnW, y, btnW, 16,
                    Text.translatable("cobblemarket.card.manage_revoke"),
                    { sendToServer(RequestCardRevokePayload(e.uuid, "purple")) }
                )
                cardButtons.add(btn to "purple")
                addDrawableChild(btn)
            }
        }
    }

    /**
     * 弹窗打开时藏掉主界面**所有可点控件**。
     * ⚠⭐ **新增任何按钮/输入框都要加进这里** —— 遮罩只挡视线、**挡不住点击**
     *   （封禁界面实测报过：弹窗开着还能点到底下的页签）。这份名单必须收口成一处。
     * （关闭走 `closeConfirmDialog` → `clearChildren()` + `init()`，控件是重建的，不用手动恢复）
     */
    private fun hideMainControls() {
        nameField?.visible = false
        durationField?.visible = false
        blockButton?.visible = false
        backButton?.visible = false
        infoButton?.visible = false
        tabButtons.forEach { it.visible = false }
        rowButtons.forEach { it.visible = false }
        cardButtons.forEach { (b, _) -> b.visible = false }
        creditQueryField?.visible = false
        creditQueryButton?.visible = false
        rankRefreshButton?.visible = false
        creditButtons.forEach { it.visible = false }
    }

    // ── 禁借确认弹窗 ──

    private fun openConfirmDialog() {
        val name = nameField?.text?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            playFailSound()
            return
        }
        pendingName = name
        pendingDuration = durationField?.text?.trim() ?: ""
        hideMainControls()
        suggestionsOpen = false
        suggestDirty = false
        lastRequestPrefix = null
        rebuildSuggestionList()
        val centerX = width / 2
        val dialogY = height / 2 - DIALOG_H / 2

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderDialogBackground(context)
            }
        })

        reasonField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 92, 160, 16, Text.literal(""))
        reasonField?.setPlaceholder(Text.translatable("cobblemarket.finance.reason"))
        reasonField?.setTextPredicate { it.length <= 100 }
        addDrawableChild(reasonField)

        confirmButtons.forEach { remove(it) }
        confirmButtons.clear()
        val confirmBtn = NineSliceButton(
            centerX - 85, dialogY + 118, 80, 20,
            Text.translatable("cobblemarket.finance.confirm_block"),
            { confirmBlock() }
        )
        val cancelBtn = NineSliceButton(
            centerX + 5, dialogY + 118, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeConfirmDialog() }
        )
        confirmButtons.add(confirmBtn)
        confirmButtons.add(cancelBtn)
        addDrawableChild(confirmBtn)
        addDrawableChild(cancelBtn)
    }

    private fun renderDialogBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogW = minOf(300, width - 40).coerceAtLeast(200)
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - DIALOG_H / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, DIALOG_H, 0, DIALOG_BACKGROUND_TEX_H)

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.finance.confirm_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.finance.target").string + pendingName,
            dialogX + 12, dialogY + 40, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.ban.by").string + (client?.player?.name?.string ?: ""),
            dialogX + 12, dialogY + 58, 0xFFFFFF
        )
        // 时长：空串 = 永久（与输入框同一口径）—— 确认界面就该把正要确认的东西全列出来
        // ⚠ 版式与封禁确认弹窗**逐行对齐**（标题 14 / 目标 40 / 执行人 58 / 时长 76 / 理由框 92 /
        //   按钮 118）：两个弹窗长得一样，改一处记得另一处一起看
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.finance.duration_label").string +
                pendingDuration.ifBlank { Text.translatable("cobblemarket.ban.permanent").string },
            dialogX + 12, dialogY + 76, 0xFFFFFF
        )
    }

    private fun confirmBlock() {
        val reason = reasonField?.text?.trim() ?: ""
        sendToServer(AdminLoanBlockPayload(pendingName, pendingDuration, reason))
        closeConfirmDialog()
        nameField?.text = ""
        durationField?.text = ""
    }

    private fun closeConfirmDialog() {
        pendingName = ""
        pendingDuration = ""
        reasonField = null
        confirmButtons.forEach { remove(it) }
        confirmButtons.clear()
        clearChildren()
        init()
    }

    // ── 调整额度弹窗（比禁借弹窗多一行「当前额度」和一行输入框） ──

    /**
     * 打开调整额度弹窗。[row] 是服务端刚回的那条（额度/欠款/是否持卡都是**服务端实算**的，
     * 客户端不自己算 —— 额度公式在服务端，客户端重算必然两处不一致）。
     */
    private fun openCreditDialog(row: CreditRowInfo) {
        creditTarget = row
        // ⚠ 收口在这里（不再只在"查询回来"那条路上做）：resize 后重开弹窗也会走这个函数，
        //   不藏控件的话下层会亮着、还能点
        hideMainControls()
        suggestionsOpen = false
        suggestDirty = false
        lastRequestPrefix = null
        rebuildSuggestionList()
        val centerX = width / 2
        val dialogY = height / 2 - CREDIT_DIALOG_H / 2

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderCreditDialogBackground(context)
            }
        })

        creditAmountField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 76, 160, 16, Text.literal(""))
        creditAmountField?.setPlaceholder(Text.translatable("cobblemarket.finance.credit_new_limit"))
        creditAmountField?.setTextPredicate { t -> t.length <= 15 && t.all { it.isDigit() } }
        // 预填**当前额度**（要清零就把它删掉填 0）
        creditAmountField?.text = row.limit.toString()
        addSelectableChild(creditAmountField)
        addDrawableChild(creditAmountField)

        creditReasonField = TextFieldWidget(textRenderer, centerX - 80, dialogY + 100, 160, 16, Text.literal(""))
        creditReasonField?.setPlaceholder(Text.translatable("cobblemarket.finance.reason"))
        creditReasonField?.setTextPredicate { it.length <= 100 }
        addSelectableChild(creditReasonField)
        addDrawableChild(creditReasonField)

        creditDialogButtons.forEach { remove(it) }
        creditDialogButtons.clear()
        val ok = NineSliceButton(
            centerX - 85, dialogY + 126, 80, 20,
            Text.translatable("cobblemarket.finance.confirm_set"),
            { confirmCreditSet() }
        )
        val cancel = NineSliceButton(
            centerX + 5, dialogY + 126, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeCreditDialog() }
        )
        creditDialogButtons.add(ok)
        creditDialogButtons.add(cancel)
        addDrawableChild(ok)
        addDrawableChild(cancel)
    }

    private fun renderCreditDialogBackground(context: DrawContext) {
        val row = creditTarget ?: return
        val centerX = width / 2
        val dialogW = minOf(300, width - 40).coerceAtLeast(200)
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - CREDIT_DIALOG_H / 2

        drawScreenDimMask(context, width, height)
        // ⚠⭐ **弹窗自己的货币图标不受门禁管**（照 `LoanScreen` / `RepayScreen` 那几处）：
        //   `drawScreenDimMask` 会把 `suppressCurrencyIcons` 置 true（那是给"被遮罩盖住的**下层**"用的 ——
        //   货币图标是 3D 模型、不响应半透明黑，会亮得像浮在弹窗上）⇒ 本弹窗里画金额之前必须置回 false，
        //   否则「当前额度」的单位**画不出来**（2026-10-05 用户实测报的正是这个）
        com.shusheng.cobblemarket.client.suppressCurrencyIcons = false
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, CREDIT_DIALOG_H, 0, DIALOG_BACKGROUND_TEX_H)

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.finance.credit_dialog_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.finance.target").string + row.playerName,
            dialogX + 12, dialogY + 40, 0xFFFFFF
        )
        // 当前额度：让服主知道"从多少改成多少"（不然填数字全靠猜）。
        // ⚠⭐ 金额的**两个必备元素**：① 颜色走金额蓝 `0x55FFFF`（能花才用金）② 必须带**货币单位** ——
        //   而单位可能是符号 `₽`、也可能是**物品图标**（物品本位货币）⇒ 只能走 `CurrencyDisplay`：
        //   词条的单位参数传**空串占位**，再单独补画单位（照 `MeowthBankScreen` 的可借上限那两行）
        val limitLine = Text.translatable(
            "cobblemarket.finance.credit_current", formatPriceLong(row.limit), Text.literal("")
        )
        context.drawTextWithShadow(textRenderer, limitLine, dialogX + 12, dialogY + 58, 0x55FFFF)
        drawCurrencyUnit(
            context, textRenderer,
            dialogX + 12 + textRenderer.getWidth(limitLine), dialogY + 58, 0x55FFFF
        )
    }

    private fun confirmCreditSet() {
        val row = creditTarget ?: return
        val amount = creditAmountField?.text?.trim()?.toLongOrNull()
        if (amount == null || amount < 0) {
            playFailSound()
            return
        }
        sendToServer(AdminCreditSetPayload(row.playerName, amount, creditReasonField?.text?.trim() ?: ""))
        closeCreditDialog()
        // 确认后清空搜索框 —— 照封禁管理那边：主界面的输入框是"新操作"用的，
        // 办完了把人家上次搜的名字留着，下次一点「调整额度」会直接查那个人（用户 2026-10-05 报的）
        creditQueryField?.text = ""
    }

    private fun closeCreditDialog() {
        creditTarget = null
        creditAmountField = null
        creditReasonField = null
        creditDialogButtons.forEach { remove(it) }
        creditDialogButtons.clear()
        clearChildren()
        init()
    }

    // ── 联想（照 AdminBanScreen，一字不改地照搬） ──

    fun onNameSuggestions(payload: PlayerNameSuggestionsPayload) {
        if (pendingName.isNotEmpty() || creditTarget != null) return
        // ⚠ 一律走 queryField()：禁止借款与额度管理各有一个输入框，联想通道是同一条
        if ((queryField()?.text?.trim() ?: "") != lastRequestPrefix) return
        suggestions = payload.names
        suggestionsOpen = suggestions.isNotEmpty() && !(queryField()?.text?.isBlank() ?: true)
        suggestScroll = 0
        rebuildSuggestionList()
    }

    private fun checkSuggestDebounce() {
        if (!suggestDirty) return
        if (System.currentTimeMillis() - lastSuggestEdit < 250) return
        suggestDirty = false
        lastRequestPrefix = queryField()?.text?.trim().orEmpty()
        sendToServer(RequestPlayerNameSuggestionsPayload(lastRequestPrefix ?: ""))
    }

    private fun rebuildSuggestionList() {
        if (!suggestionsOpen) {
            suggestionButtons.forEach { it.visible = false }
            requestRowRebuild()
            return
        }
        suggestionButtons.forEach { remove(it) }
        suggestionButtons.clear()
        val leftX = width / 2 - panelWidth / 2
        suggestions.drop(suggestScroll).take(MAX_SUGGESTION_ROWS).forEachIndexed { i, name ->
            val btn = NineSliceButton(
                // ⚠ 起点**跟着表单行走**（写死数字会在页签换行时压到输入框上 —— 封禁界面栽过）
                // ⚠ 宽度跟着**当前页签那个输入框**（额度管理的搜索框更长，联想列表跟着拉长才不别扭）
                leftX + 2, formRowY + 18 + i * 14, queryField()?.width ?: 118, 14,
                Text.literal(name),
                {
                    suppressSuggest = true
                    queryField()?.text = name
                    suggestDirty = false
                    lastRequestPrefix = null
                    suggestionsOpen = false
                    rebuildSuggestionList()
                }
            )
            suggestionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        MarketPanelBackground.draw(context, width, height)

        val leftX = width / 2 - panelWidth / 2
        val visibleRows = maxVisible()
        val startY = listStartY()
        val listAreaBottom = startY + visibleRows * rowHeight
        val rows = rowCount()

        hoveredRow = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY in startY..listAreaBottom) {
            val row = (mouseY - startY) / rowHeight
            val shownCount = minOf(visibleRows, rows - scrollOffset)
            if (row in 0 until shownCount) hoveredRow = row
        }

        val drawn = minOf(visibleRows, maxOf(0, rows - scrollOffset))
        for (di in 0 until drawn) {
            val rowY = startY + di * rowHeight
            val rowState = if (di == hoveredRow) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, rowState, ROW_BACKGROUND_TEX_H)
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        // 行按钮的延迟重建（见 requestRowRebuild：不能在 mouseClicked 栈里 remove 子元素）。
        // ⚠ 放在最前面：别让"该建的没建"影响到本帧的悬停/绘制判断
        if (rowRebuildPending) {
            rowRebuildPending = false
            rebuildRowButtons()
        }

        // 任一弹窗开着就只画弹窗（弹窗内容由 addDrawable 那条路径画）
        if (pendingName.isNotEmpty() || creditTarget != null) return

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.finance.title").formatted(Formatting.GOLD),
            centerX, 20, 0xFFFFFF
        )

        renderCardTabIcons(context)

        // 额度页签：定时重拉（行里那个数是算出来的，见 creditRows 的说明）。
        // ⚠ 放在这里 ⇒ 弹窗开着时上面已经 return（不打扰正在填表的人）
        if (currentTab == 2 && System.currentTimeMillis() - lastCreditRefreshAt >= CREDIT_REFRESH_MS) {
            lastCreditRefreshAt = System.currentTimeMillis()
            sendToServer(RequestCreditAdjustListPayload())
        }

        if (currentTab in FORM_TABS) checkSuggestDebounce()
        if (suggestionsOpen) return

        drawPanelDivider(context, leftX + panelWidth / 2, listStartY() - 4)

        when (currentTab) {
            0 -> renderBlockList(context, centerX, leftX, mouseX, mouseY)
            1 -> renderCardList(context, centerX, leftX, mouseX, mouseY)
            2 -> renderCreditList(context, centerX, leftX, mouseX, mouseY, creditRows, showRank = false)
            4 -> renderCreditList(context, centerX, leftX, mouseX, mouseY, rankRows, showRank = true)
            else -> renderChangeList(context, centerX, leftX, mouseX, mouseY)
        }
    }

    /** 变更记录回包 → 行文本与悬停内容（**在这里算一次**，render 每帧只读） */
    fun onFinanceChanges(payload: FinanceChangesPayload) {
        changeLines = payload.rows.map { buildChangeLine(it) }
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, changeLines.size - maxVisible()))
    }

    /** 一条变更记录 → 行内文本 + 悬停窗（两处的分段与配色一一对应，照封禁那张账本） */
    private fun buildChangeLine(row: FinanceChangesPayload.Row): ChangeLine {
        fun at(format: java.time.format.DateTimeFormatter) = format.format(
            java.time.Instant.ofEpochMilli(row.timestamp).atZone(java.time.ZoneId.systemDefault())
        )
        val whoRow = Text.literal("${BanState.reasonText(row.operator).string} → ${row.targetName}")
            .formatted(Formatting.GOLD)
        fun actionKeyOf(k: String) = "$k".removePrefix("\$")
        val actionRow = Text.translatable(actionKeyOf(row.actionKey)).formatted(
            when (row.actionKey) {
                FinanceChangeLogger.ACTION_CARD_GRANT, FinanceChangeLogger.ACTION_LOAN_UNBLOCK -> Formatting.GREEN
                FinanceChangeLogger.ACTION_CARD_REVOKE, FinanceChangeLogger.ACTION_LOAN_BLOCK -> Formatting.RED
                else -> Formatting.YELLOW   // 调整额度：不是"加"也不是"撤"，给个中性又显眼的色
            }
        )
        // 详情按动作类型拼（**每类只有一两段**，空的就不显示）：
        //   卡片 ⇒ 卡种；禁借 ⇒ 时长；调额度 ⇒ 额度：改前 → 改后
        // ⚠ 先取成局部变量：这几个字段来自别的模块（`network`），Kotlin 不给做智能转换
        val kind = row.cardKind
        val durationMs = row.durationMs
        val value = row.value
        val prevValue = row.prevValue
        val detail: Text? = when {
            kind.isNotBlank() -> Text.translatable(
                if (kind == "black") "cobblemarket.card.black_name" else "cobblemarket.card.purple_name"
            )
            durationMs != null -> BanState.remainingText(durationMs)
            value != null -> Text.translatable(
                "cobblemarket.finance.changes_credit",
                formatPriceLong(prevValue ?: 0L), formatPriceLong(value),
                // ⚠ 这一处金额在**行中间**（后面还跟着理由）⇒ 图标塞不进去，
                //   按项目既有口径退回**物品名/₽ 文本**（`activeCurrencyUnit()`，同"截断链/串中间"那条）
                activeCurrencyUnit()
            ).formatted(Formatting.AQUA)   // 账本口径 = 蓝
            else -> null
        }

        // 行内：时间 · 执行者→目标 · 动作 [· 详情] [· 理由]
        // ⚠ 全部**平级**挂在空父节点下：带色的段自己带色，` · ` 这些陪衬文字不设色（吃行色）
        val text = Text.literal("")
            .append(Text.literal(at(changeTimeFormat)).formatted(Formatting.GRAY)).append("  ·  ")
            .append(whoRow).append("  ·  ")
            .append(actionRow)
        detail?.let { text.append("  ·  ").append(it) }
        if (row.reason.isNotBlank()) text.append("  ·  ").append(BanState.reasonText(row.reason))

        // 悬停窗：同样的分段各自成行（时间给到秒），颜色与行内一一对应
        val tooltip = buildList {
            add(
                Text.literal("").append(Text.literal(at(changeTooltipTimeFormat)).formatted(Formatting.GRAY))
                    .append("   ").append(whoRow)
            )
            add(Text.literal("").append(actionRow).apply { detail?.let { append("  ·  ").append(it) } })
            if (row.reason.isNotBlank()) {
                add(Text.literal("").append(Text.translatable("cobblemarket.finance.reason_label")).append(BanState.reasonText(row.reason)))
            }
        }
        return ChangeLine(text, tooltip)
    }

    /** 变更记录列表（行样式照封禁那张账本） */
    private fun renderChangeList(context: DrawContext, centerX: Int, leftX: Int, mouseX: Int, mouseY: Int) {
        if (changeLines.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.finance.changes_empty").formatted(Formatting.GRAY),
                centerX, listStartY() + 30, 0xFFFFFF
            )
            return
        }
        changeLines.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, line ->
            val y = listStartY() + i * rowHeight
            // ⚠⭐ 必须 truncateStyled：`truncateText` 会把分段颜色压平成一段（封禁界面实测报过）
            context.drawTextWithShadow(
                textRenderer, TextUtil.truncateStyled(line.text, panelWidth - 8), leftX + 4, y + 7, 0xFFFFFF
            )
        }
        if (changeLines.size > maxVisible()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible(), changeLines.size)} / ${changeLines.size}",
                centerX, height - 49, 0x888888
            )
        }
        if (hoveredRow >= 0) {
            val idx = scrollOffset + hoveredRow
            if (idx in changeLines.indices) {
                renderTextTooltip(context, changeLines[idx].tooltip, mouseX, mouseY)
            }
        }
    }

    /** 额度管理页签的列表：`名字 · 额度 X`（行尾「调整额度」按钮；持卡玩家那枚是灰的） */
    /**
     * 额度列表 —— 额度管理页签（[rows] = 被人工调整过的玩家）与额度排行页签（[rows] = 前 50 名、
     * [showRank] = true 时行首带名次）**共用这一个渲染**，别各写一份。
     */
    private fun renderCreditList(
        context: DrawContext, centerX: Int, leftX: Int, mouseX: Int, mouseY: Int,
        rows: List<CreditRowInfo>, showRank: Boolean
    ) {
        if (rows.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(
                    if (showRank) "cobblemarket.finance.rank_empty" else "cobblemarket.finance.credit_empty"
                ).formatted(Formatting.GRAY),
                centerX, listStartY() + 30, 0xFFFFFF
            )
            return
        }
        rows.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, row ->
            val y = listStartY() + i * rowHeight
            // 行：`名字(白) · 额度：(白) <金额(蓝)> <货币单位>`。
            // ⚠⭐ 金额的**两个必备元素**：蓝色 `0x55FFFF`（账本口径；能花才用金）+ 货币单位 ——
            //   单位可能是符号 `₽`、也可能是**物品图标** ⇒ 只能单独补画（照喵喵银行的可借上限那两行）
            // ⚠ 金额串**必须带尾随空格**（单位写法恒为 `金额 + 空格 + 单位`）——
            //   `drawCurrencyUnit` 是"假设末尾已有一个空格"再往左收 2px 画的，
            //   少了那个空格图标就贴死在数字上（用户 2026-10-05 报的，见 memory 里那条约定）
            val amount = Text.literal("${formatPriceLong(row.limit)} ")
            val amountW = textRenderer.getWidth(amount)
            val unitW = currencyUnitWidth(textRenderer)
            // 行文字画到「调整额度按钮左侧留 4px」为止（按钮起于 panelWidth-68）；
            // 名字那截**先按"给金额与单位留够"反算预算再截断**，否则长名字会把金额挤出可用宽
            val nameBudget = panelWidth - 76 - amountW - unitW
            // 排行版在行首带名次（实时按下标算：滚动时名次跟着变，不能靠数据里存）
            val head = Text.literal("")
            if (showRank) head.append(Text.literal("${scrollOffset + i + 1}. ").formatted(Formatting.GRAY))
            head.append(row.playerName).append("  ·  ")
                .append(Text.translatable("cobblemarket.finance.credit_amount_label"))
            val namePart = TextUtil.truncateStyled(head, nameBudget)
            context.drawTextWithShadow(textRenderer, namePart, leftX + 4, y + 7, 0xFFFFFF)
            val amountX = leftX + 4 + textRenderer.getWidth(namePart)
            context.drawTextWithShadow(textRenderer, amount, amountX, y + 7, 0x55FFFF)
            drawCurrencyUnit(context, textRenderer, amountX + amountW, y + 7, 0x55FFFF)
        }
        if (rows.size > maxVisible()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible(), rows.size)} / ${rows.size}",
                centerX, height - 49, 0x888888
            )
        }
        if (hoveredRow >= 0) {
            val idx = scrollOffset + hoveredRow
            if (idx in rows.indices) renderCreditTooltip(context, idx + 1, rows[idx], mouseX, mouseY)
        }
    }

    /** 额度行的悬停窗：把行内被截断的东西给全（额度是主信息，另有修正量与是否持卡） */
    private fun renderCreditTooltip(
        context: DrawContext, rank: Int, row: CreditRowInfo, mouseX: Int, mouseY: Int
    ) {
        val amountLines = mutableSetOf<Int>()
        val lines = mutableListOf<Text>()
        // 排行页签给行号（[rank] > 0）；额度管理那边不传（它没有名次的概念）
        if (rank > 0) lines.add(Text.literal("No.$rank").formatted(Formatting.GRAY))
        lines.add(Text.literal("${Text.translatable("cobblemarket.finance.target").string} ${row.playerName}"))
        // 金额行：标签白、金额蓝，**货币单位单独补画**（可能是物品图标、进不了文本流）⇒ 记下行号
        lines.add(
            Text.literal("").append(Text.translatable("cobblemarket.finance.credit_amount_label"))
                .append(Text.literal("${formatPriceLong(row.limit)} ").formatted(Formatting.AQUA))
        )
        amountLines.add(lines.size - 1)
        if (row.adjust != 0L) {
            lines.add(
                Text.literal("").append(Text.translatable("cobblemarket.finance.credit_adjust_label"))
                    .append(Text.literal("${formatPriceLong(row.adjust)} ").formatted(Formatting.AQUA))
            )
            amountLines.add(lines.size - 1)
        }
        if (row.hasCard) lines.add(Text.translatable("cobblemarket.finance.credit_card_fixed_tip"))
        renderTextTooltip(context, lines, mouseX, mouseY, avoid = creditRowRect(hoveredRow), unitLines = amountLines)
    }

    private fun creditRowRect(row: Int) = TooltipPlacement.Rect(
        width / 2 - panelWidth / 2 + panelWidth - 68, listStartY() + row * rowHeight + 4, 68, 16
    )

    /**
     * 「卡片管理」页签上的两张卡图标（紫卡 + 黑卡，居中等距）。
     *
     * ⚠ 用**物品图标**而不是贴图：卡片是物品（`meowth_purple_card` / `meowth_black_card`），
     *   照原来喵喵银行那枚入口的做法（`renderScaledGuiItemIcon`）。
     * ⚠ 页签高 14 ⇒ 图标缩到 14（原尺寸 16 会上下各顶出 1px）。
     */
    private fun renderCardTabIcons(context: DrawContext) {
        if (!TABS.any { it.icon }) return
        val purple = net.minecraft.registry.Registries.ITEM.get(Identifier.of("cobblemarket", "meowth_purple_card"))
        val black = net.minecraft.registry.Registries.ITEM.get(Identifier.of("cobblemarket", "meowth_black_card"))
        val air = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of("minecraft", "air"))
        val gap = 4
        TABS.forEachIndexed { i, spec ->
            if (!spec.icon) return@forEachIndexed
            val btn = tabButtons.getOrNull(i) ?: return@forEachIndexed
            if (!btn.visible) return@forEachIndexed
            val startX = btn.x + (btn.width - (TAB_ICON * 2 + gap)) / 2
            if (purple != air) {
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = net.minecraft.item.ItemStack(purple),
                    x = startX.toDouble(), y = btn.y.toDouble(), scale = TAB_ICON / 16.0,
                    matrixStack = context.matrices
                )
            }
            if (black != air) {
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = net.minecraft.item.ItemStack(black),
                    x = (startX + TAB_ICON + gap).toDouble(), y = btn.y.toDouble(), scale = TAB_ICON / 16.0,
                    matrixStack = context.matrices
                )
            }
        }
    }

    private fun renderBlockList(context: DrawContext, centerX: Int, leftX: Int, mouseX: Int, mouseY: Int) {
        if (entries.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.finance.block_empty").formatted(Formatting.GRAY),
                centerX, listStartY() + 30, 0xFFFFFF
            )
            return
        }
        entries.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, e ->
            val y = listStartY() + i * rowHeight
            val durationText = if (e.expiresAt == null) Text.translatable("cobblemarket.ban.permanent")
            else BanState.remainingText(e.remainingMs)
            // 行文字画到「解除按钮左侧留 4px」为止：按钮起于 panelWidth-60、文字起于 +4
            context.drawTextWithShadow(
                textRenderer,
                // ⚠ 必须 truncateStyled：truncateText 会把分段颜色压平成一段（封禁界面实测报过）
                TextUtil.truncateStyled(
                    Text.literal("${e.playerName}  ·  ").append(BanState.reasonText(e.blockedBy))
                        .append("  ·  ").append(durationText),
                    panelWidth - 68
                ),
                leftX + 4, y + 7, 0xFFFFFF
            )
        }
        if (entries.size > maxVisible()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible(), entries.size)} / ${entries.size}",
                centerX, height - 49, 0x888888
            )
        }
        if (hoveredRow >= 0) {
            val idx = scrollOffset + hoveredRow
            if (idx in entries.indices) renderBlockTooltip(context, entries[idx], mouseX, mouseY)
        }
    }

    private fun renderCardList(context: DrawContext, centerX: Int, leftX: Int, mouseX: Int, mouseY: Int) {
        if (cardEntries.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.card.manage_empty").formatted(Formatting.GRAY),
                centerX, listStartY() + 30, 0xFFFFFF
            )
            return
        }
        val purpleItem = net.minecraft.registry.Registries.ITEM.get(Identifier.of("cobblemarket", "meowth_purple_card"))
        val blackItem = net.minecraft.registry.Registries.ITEM.get(Identifier.of("cobblemarket", "meowth_black_card"))
        val air = net.minecraft.registry.Registries.ITEM.get(Identifier.of("minecraft", "air"))
        val visible = cardEntries.drop(scrollOffset).take(maxVisible())
        visible.forEachIndexed { i, e ->
            val y = listStartY() + i * rowHeight
            // 名字截断到卡组左侧（双持时紫图标最靠左）—— 完整名字看悬停窗
            context.drawTextWithShadow(
                textRenderer,
                Text.literal(truncateName(e.name, 140)),
                leftX + 8, y + 7, 0xFFFFFF
            )
            if (e.holdsPurple && purpleItem != air) {
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = net.minecraft.item.ItemStack(purpleItem),
                    x = (leftX + iconXOf(e, "purple")).toDouble(), y = (y + 4).toDouble(), scale = 1.0,
                    matrixStack = context.matrices
                )
            }
            if (e.holdsBlack && blackItem != air) {
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = net.minecraft.item.ItemStack(blackItem),
                    x = (leftX + iconXOf(e, "black")).toDouble(), y = (y + 4).toDouble(), scale = 1.0,
                    matrixStack = context.matrices
                )
            }
        }
        if (cardEntries.size > maxVisible()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible(), cardEntries.size)} / ${cardEntries.size}",
                centerX, height - 49, 0x888888
            )
        }
        if (hoveredRow >= 0) {
            val idx = scrollOffset + hoveredRow
            if (idx in cardEntries.indices) renderCardTooltip(context, cardEntries[idx], mouseX, mouseY)
        }
    }

    /** 卡组（图标 + 按钮）右对齐行尾的图标偏移 —— 算式与 `CardManageScreen` / [rebuildRowButtons] 同源 */
    private fun iconXOf(e: CardHolderEntry, kind: String): Int {
        val rightEdge = panelWidth - 8
        val btnW = 44
        val iconW = 16
        val gap = 4
        return when (kind) {
            "black" -> rightEdge - btnW - gap - iconW
            "purple" -> if (e.holdsBlack) rightEdge - btnW - gap - iconW - gap - gap - btnW - gap - iconW
            else rightEdge - btnW - gap - iconW
            else -> 0
        }
    }

    private fun truncateName(name: String, maxWidth: Int): String {
        if (textRenderer.getWidth(name) <= maxWidth) return name
        var cut = name.length
        while (cut > 1 && textRenderer.getWidth(name.substring(0, cut) + "…") > maxWidth) cut--
        return name.substring(0, cut) + "…"
    }

    /**
     * 禁借行的悬停窗 —— **行的完整版**：行内会被截断（玩家名一长两头都看不到），
     * 这里把行内每一段都给全（照封禁行那五行的口径）。
     */
    private fun renderBlockTooltip(context: DrawContext, e: LoanBlockInfo, mouseX: Int, mouseY: Int) {
        val lines: List<Text> = buildList {
            add(Text.literal("${Text.translatable("cobblemarket.finance.target").string} ${e.playerName}"))
            add(Text.literal("${Text.translatable("cobblemarket.ban.by").string} ${BanState.reasonText(e.blockedBy).string}"))
            add(
                Text.translatable(
                    "cobblemarket.ban.remaining",
                    if (e.expiresAt == null) Text.translatable("cobblemarket.ban.permanent")
                    else BanState.remainingText(e.remainingMs)
                )
            )
            add(Text.literal("${Text.translatable("cobblemarket.finance.reason_label").string} ${BanState.reasonText(e.reason).string.ifBlank { "-" }}"))
        }
        // 悬停窗不许盖掉本行的「解除」按钮
        renderTextTooltip(context, lines, mouseX, mouseY, avoid = rowButtonRect(hoveredRow))
    }

    private fun renderCardTooltip(context: DrawContext, e: CardHolderEntry, mouseX: Int, mouseY: Int) {
        val lines: List<Text> = buildList {
            add(Text.literal("${Text.translatable("cobblemarket.finance.target").string} ${e.name}"))
            if (e.holdsPurple) add(Text.translatable("cobblemarket.card.purple_name"))
            if (e.holdsBlack) add(Text.translatable("cobblemarket.card.black_name"))
        }
        renderTextTooltip(context, lines, mouseX, mouseY, avoid = rowButtonRect(hoveredRow))
    }

    private fun rowButtonRect(row: Int) = TooltipPlacement.Rect(
        width / 2 - panelWidth / 2 + panelWidth - 60, listStartY() + row * rowHeight + 3, 60, 16
    )

    /**
     * 悬停窗的通用绘制（禁借行 / 卡片行共用）。
     * ⚠ 与 `AdminBanScreen.renderTextTooltip` 是**同一份实现的两次拷贝** —— 等第三个界面要用时
     *   抽成共用件（本次不动那个刚实测过的屏，避免搬代码搬出新问题）。
     */
    private fun renderTextTooltip(
        context: DrawContext, lines: List<Text>, mouseX: Int, mouseY: Int,
        avoid: TooltipPlacement.Rect? = null,
        /** 哪几行是**金额行** ⇒ 那几行末尾要补画货币单位（可能是物品图标，进不了文本流） */
        unitLines: Set<Int> = emptySet()
    ) {
        // ⚠⭐ **测量与绘制同源**：金额行的货币单位是**单独补画**的（可能是物品图标、进不了文本流）
        //   ⇒ 量宽度时必须把它算进去，否则悬停窗按"只有文本"的宽度画，单位会戳到框外
        //   （用户 2026-10-05 实测报的正是这个；照 `MeowthBankScreen` 的可借上限那两行）
        val unitW = currencyUnitWidth(textRenderer)
        var maxWidth = 0
        lines.forEachIndexed { i, line ->
            maxWidth = maxOf(maxWidth, textRenderer.getWidth(line) + if (i in unitLines) unitW else 0)
        }

        val padding = 4
        val tooltipHeight = lines.size * 10 + padding
        val (tx, ty) = TooltipPlacement.place(
            mouseX = mouseX, mouseY = mouseY,
            textW = maxWidth, textH = tooltipHeight, pad = padding,
            screenW = width, screenH = height, avoid = avoid
        ) ?: return

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, lines.size * 10 + 2 * padding, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, line ->
            context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, 0xFFFFFF)
            // 金额行末尾补货币单位（可能与图标同宽的空格也要让出来 ⇒ 宽度算进 maxWidth 之外，
            // 悬停窗够宽自然没问题；这里只负责画）
            if (i in unitLines) {
                drawCurrencyUnit(context, textRenderer, tx + textRenderer.getWidth(line), ty + i * 10, 0x55FFFF)
            }
        }
        context.matrices.pop()
    }

    // ── 交互 ──

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (suggestionsOpen) {
            if (suggestions.size > MAX_SUGGESTION_ROWS) {
                suggestScroll = (suggestScroll - verticalAmount.toInt()).coerceIn(0, suggestions.size - MAX_SUGGESTION_ROWS)
                rebuildSuggestionList()
            }
            return true
        }
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, rowCount() - maxVisible()))
        requestRowRebuild()
        return true
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val name = pendingName
        val duration = pendingDuration
        val reason = reasonField?.text ?: ""
        val creditRow = creditTarget
        val creditAmount = creditAmountField?.text ?: ""
        val creditReason = creditReasonField?.text ?: ""
        super.resize(client, width, height)
        if (name.isNotEmpty()) {
            pendingName = ""
            pendingDuration = ""
            openConfirmDialogFor(name, duration)
            reasonField?.text = reason
        }
        if (creditRow != null) {
            creditTarget = null
            openCreditDialog(creditRow)
            creditAmountField?.text = creditAmount
            creditReasonField?.text = creditReason
        }
    }

    /** resize 后重开弹窗：名字不能再从输入框取（它已被隐藏 / 清空），故单独入口 */
    private fun openConfirmDialogFor(name: String, duration: String) {
        nameField?.text = name
        durationField?.text = duration
        openConfirmDialog()
    }

    private fun isInputFieldFocused() = focused?.let { f ->
        f === nameField || f === durationField || f === creditQueryField ||
            f === creditAmountField || f === creditReasonField
    } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        nameField?.isMouseOver(mouseX, mouseY) == true ||
        durationField?.isMouseOver(mouseX, mouseY) == true ||
        creditQueryField?.isMouseOver(mouseX, mouseY) == true ||
        creditAmountField?.isMouseOver(mouseX, mouseY) == true ||
        creditReasonField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        if (suggestionsOpen && !isMouseOverSuggestionArea(mouseX, mouseY)) {
            suggestionsOpen = false
            suggestDirty = false
            lastRequestPrefix = null
            rebuildSuggestionList()
        }
        return result
    }

    private fun isMouseOverSuggestionArea(mouseX: Double, mouseY: Double): Boolean {
        if (nameField?.isMouseOver(mouseX, mouseY) == true) return true
        return suggestionButtons.any { it.visible && it.isMouseOver(mouseX, mouseY) }
    }

    override fun shouldPause() = false
}
