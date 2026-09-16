package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.PlayerNameSuggestionsPayload
import com.shusheng.cobblemarket.network.RequestPlayerNameSuggestionsPayload
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.permission.PermissionAuditPayload
import com.shusheng.cobblemarket.permission.PermissionListDataPayload
import com.shusheng.cobblemarket.permission.RequestPermissionAuditPayload
import com.shusheng.cobblemarket.permission.RemovePlayerPermissionsPayload
import com.shusheng.cobblemarket.permission.RequestPermissionListPayload
import com.shusheng.cobblemarket.permission.SavePlayerPermissionsPayload
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * 管理权限界面（仅 OP，方案见 docs/permission_nodes_plan.md）：
 * 一行 = 一名被授予过节点的玩家；「编辑」开弹窗多选节点，「删除」清空该玩家全部节点。
 *
 * 骨架照搬 [AdminBanScreen]（搜索框防抖联想 / 列表 / 行内按钮 / 返回按钮位置），
 * 弹窗骨架照搬 [PriceLimitScreen]（遮罩 + 隐藏下层控件 + 关闭时 clearChildren 重建）。
 *
 * 网络层收到 [PermissionListDataPayload] 后调 [onPermissionList]（入口按钮由管理面板接线）。
 */
class PermissionScreen : Screen(Text.translatable("cobblemarket.perm.title")) {

    private val panelWidth = 300
    private val rowHeight = 22
    // 面板自上而下：标题(20) → 页签行(44，高 14) → 搜索行(62，高 16) → 分割线(82) → 列表(86)
    private val tabRowY = 44
    private val searchRowY = 62
    // 页签 1 有搜索行（62~78），列表接在它下面；页签 2 没有这一行，整段高度收掉，
    // 分割线与首行直接贴着页签排（否则搜索行的空位会把列表顶下去，看着空一截）
    private val grantsListStartY = 86
    private val auditListStartY = 66

    /** 当前页签的列表起始 y（分割线画在它上方 4px）：render / renderBackground / 行按钮都走这里 */
    private fun listStartY() = if (currentTab == 0) grantsListStartY else auditListStartY
    private val editBtnX = panelWidth - 98          // 行内「编辑」左边界
    private val removeBtnX = panelWidth - 50        // 行内「删除」左边界（与编辑间隔 4px）
    // 搜索行（搜索框 + 「添加」）整体排在行内按钮的 x 区域左侧：两行按钮虽不同 y，
    // 但列对齐才不会看成同一列控件；宽度由此反推，搜索框、下拉、添加键三者永远对齐
    private val addBtnX = editBtnX - 4 - ADD_BTN_W  // 「添加」左边界（与行内按钮区隔 4px）
    private val searchFieldW = addBtnX - 4 - 2      // 搜索框宽（左边距 2、与添加键隔 4px）

    // ── 页签（照 PriceLimitScreen：tab 按钮重建 + 切换时重建行按钮）──
    private var currentTab = 0                      // 0 = 权限授予，1 = 变更记录
    private var grantsTabButton: NineSliceButton? = null
    private var auditTabButton: NineSliceButton? = null

    // ── 变更记录（页签 2）──
    // 行文本缓存：时间格式化与词条拼接在数据到达时算一次，render 每帧只读
    private data class AuditLine(val time: String, val who: String, val added: String, val removed: String, val tooltip: List<String>)
    private var auditLines = listOf<AuditLine>()

    // ── 主列表状态（页签 1）──
    private var entries = listOf<PermissionListDataPayload.Entry>()
    // 过滤结果与行文本缓存：render 每帧只读 —— 节点短标签要查词条再拼接，
    // 每帧现算在全量列表上是可见的掉帧源（照 PriceLimitScreen 的 rebuildFiltered 思路）
    private var filtered = listOf<PermissionListDataPayload.Entry>()
    private var rowNames = listOf<String>()
    private var rowNodes = listOf<String>()
    private var scrollOffset = 0
    private var hoveredRow = -1
    private var searchField: TextFieldWidget? = null
    private var backButton: NineSliceButton? = null
    // 给「还没在列表里」的玩家授权：列表只显示已有节点的玩家，新玩家只能靠名字新建
    private var addButton: NineSliceButton? = null
    private val editButtons = mutableListOf<NineSliceButton>()
    private val removeButtons = mutableListOf<NineSliceButton>()

    // ── 编辑弹窗状态 ──
    // 非 null = 弹窗打开。保存/按钮回调取它拿 UUID 与名字：列表刷新后对象会换新，但这两个字段不变
    private var editingEntry: PermissionListDataPayload.Entry? = null
    // 弹窗内待提交的节点集合：打开时按该玩家现有节点预填，点按钮切换勾选，保存时一次发全量
    private val selectedNodes = mutableSetOf<String>()
    // 节点 → 按钮：勾选态要回头刷按钮视觉，按索引找易错位，用映射直取
    private val nodeButtonByNode = mutableMapOf<String, NineSliceButton>()
    // 编辑层的保存/取消：二次确认弹窗盖上来时要把整层控件藏掉（节点按钮的 y 区域与确认键重叠，
    // 留着会按添加顺序拦掉确认点击），所以得留引用
    private var saveButton: NineSliceButton? = null
    private var cancelButton: NineSliceButton? = null

    // ── 「服务器配置」二次确认层（照 ServerConfigScreen 蛋交易那套：3 秒冷静期）──
    // true = 确认弹窗盖在编辑弹窗之上；此时编辑层控件 visible=false，只留它的背景被遮罩压暗
    private var configConfirmOpen = false
    private var configConfirmOpenedAt = 0L
    private var configConfirmButton: NineSliceButton? = null

    // ── 搜索框联想（照搬 AdminBanScreen：服务端返回存档所有登录过的玩家名，含离线）──
    private var suggestions = listOf<String>()
    private var suggestionsOpen = false
    private var suggestDirty = false
    private var lastSuggestEdit = 0L
    private var suggestScroll = 0
    // 点选联想填入文本时抑制 changedListener 的防抖（否则列表收起后又重新展开）
    private var suppressSuggest = false
    // 发送请求时的输入快照：响应到达时输入不一致则忽略；点选时置 null 使所有在途响应失效
    private var lastRequestPrefix: String? = null
    private val suggestionButtons = mutableListOf<NineSliceButton>()

    // 列表底部让出 48px 给页码行，避免最后一行压住 "x-y / n"
    private fun maxVisible() = maxOf(0, (height - listStartY() - 48) / rowHeight)

    /** 当前页签的条目数（滚动上限、悬停范围、页码、空态全走它） */
    private fun displayCount() = if (currentTab == 0) filtered.size else auditLines.size

    private companion object {
        const val MAX_SUGGESTION_ROWS = 8
        // 联想下拉与搜索框同宽：下拉里点选的是玩家名，宽度不一致会看不出它属于哪个框；
        // 宽度天然让开行内按钮（点击遍历按添加顺序，先添加的行按钮会拦掉重叠区域的点击）
        const val SUGGEST_ROW_H = 14
        const val ADD_BTN_W = 44                     // 「添加」与行内「编辑/删除」同宽，视觉成列
        /** 全 0 UUID = 「按名字新建/解析」：服务端收到它时用 playerName 查玩家，客户端不查 UUID */
        val EMPTY_UUID: UUID = UUID(0L, 0L)

        const val DIALOG_W = 220
        const val DIALOG_H = 150
        const val DIALOG_MARGIN = 12                 // 弹窗内容左右留白
        const val NODE_W = 94                        // 两列各 94，中间 8px 间隙 = 220 - 2*12
        const val NODE_ROW_H = 20                    // 按钮 16 高 + 4px 上下间隙
        const val NODE_GRID_Y = 40                   // 首行相对弹窗顶部
        const val DIALOG_BTN_Y = 124                 // 保存/取消行（20 高，底部余 6px）

        // 「服务器配置」二次确认弹窗：正文 4 行（红白交替）+ 冷静期确认键
        const val CONFIRM_W = 280
        const val CONFIRM_H = 130
        const val CONFIRM_TEXT_Y = 34                // 正文首行相对弹窗顶部
        const val CONFIRM_LINE_H = 11                // 行距（正文 9px 高 + 2px 空隙）
        const val CONFIRM_BTN_Y = 98                 // 按钮行（20 高，底部余 12px）
        const val CONFIRM_COOLDOWN_MS = 3000L
    }

    override fun init() {
        super.init()
        // resize 与「弹窗关闭（clearChildren + init）」都会重入这里：子控件已被清空，
        // 本地持有的旧按钮引用与联想展开态必须一并清掉，否则会留下"看不见却还拦点击"的幽灵
        editButtons.clear()
        removeButtons.clear()
        suggestionButtons.clear()
        suggestionsOpen = false

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        val backBtn = NineSliceButton(
            // 右缘与背景右缘留 12px，和黑名单/封禁/管理员面板等界面完全对齐：
            // 那些界面 panelWidth=296，写 `leftX + panelWidth - 50` 正好落在这个位置；
            // 本界面 panelWidth=300（列表按 300 标准更宽），照抄会整体右移、离背景边缘只剩 10px
            leftX + panelWidth - 52, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(AdminScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        backButton = backBtn
        addDrawableChild(backBtn)

        rebuildTabButtons()

        val savedSearch = searchField?.text ?: ""
        // 搜索框右边界止于行内按钮区（panelWidth - 98），免得下拉与按钮、文字互相压
        searchField = TextFieldWidget(textRenderer, leftX + 2, searchRowY, searchFieldW, 16, Text.literal(""))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.ban.player_name"))
        searchField?.setChangedListener {
            // 输入即过滤列表并重置页码：否则过滤后残留旧列表的行按钮（同 PriceLimitScreen）
            rebuildFiltered()
            scrollOffset = 0
            hoveredRow = -1
            rebuildRowButtons()
            // 点选联想填入文本时跳过防抖（否则列表收起后又被响应重新展开）
            if (suppressSuggest) {
                suppressSuggest = false
                return@setChangedListener
            }
            // 防抖 250ms 后请求联想（服务端节流 500ms，连续输入只发最终态）
            suggestDirty = true
            lastSuggestEdit = System.currentTimeMillis()
        }
        addDrawableChild(searchField)
        searchField?.text = savedSearch
        // 回填的文本不是用户编辑：清掉防抖，别让 250ms 后凭空弹出一份联想列表
        suggestDirty = false

        // 列表只列已有节点的玩家，新玩家不出现在列表里 —— 「添加」按搜索框里的名字新建一条授予
        val addBtn = NineSliceButton(
            leftX + addBtnX, searchRowY, ADD_BTN_W, 16,
            Text.translatable("cobblemarket.perm.add"),
            { addBySearchName() }
        )
        addButton = addBtn
        addDrawableChild(addBtn)

        // 搜索框与「添加」只属于页签 1：resize 回来也要按当前页签恢复显隐
        val grantsTab = currentTab == 0
        searchField?.visible = grantsTab
        addButton?.visible = grantsTab

        scrollOffset = 0
        rebuildFiltered()
        rebuildRowButtons()
        requestCurrentTabData()
    }

    // ── 页签 ──

    /** 页签按钮（两个 80×14，间隔 4px，居中；选中态用 ● 前缀，照项目 tab 惯例） */
    private fun rebuildTabButtons() {
        val centerX = width / 2
        // info 图标（左上角）：权限审计只在服务端落盘，路径只能靠提示告知 ——
        // 与交易历史 / 借贷账本两个界面同一套做法（页签居中后左边正好空出来）
        val infoBtn = NineSliceButton(
            // y=18 与另外两个界面（交易历史 / 借贷账本）的 info 统一，也和返回按钮同一水平线
            // x 必须是 centerX-148（= 那两个界面的 leftX）：它们 panelWidth=296，直接用 leftX 定位；
            // 本界面 panelWidth=300（列表按 300 标准更宽），写 leftX 会比它们靠左 2px、贴背景边缘更近
            centerX - 148, 18, 16, 16,
            Text.literal(""),
            { },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/info.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        )
        infoBtn.setTooltip(
            net.minecraft.client.gui.tooltip.Tooltip.of(
                Text.translatable("cobblemarket.perm.folder_path", "config/cobblemarket/permissions")
            )
        )
        addDrawableChild(infoBtn)

        val grantsBtn = NineSliceButton(centerX - 82, tabRowY, 80, 14, Text.literal(""), { switchTab(0) })
        val auditBtn = NineSliceButton(centerX + 2, tabRowY, 80, 14, Text.literal(""), { switchTab(1) })
        grantsTabButton = grantsBtn
        auditTabButton = auditBtn
        addDrawableChild(grantsBtn)
        addDrawableChild(auditBtn)
        updateTabButtons()
    }

    private fun updateTabButtons() {
        val grants = Text.translatable("cobblemarket.perm.tab_grants").string
        val audit = Text.translatable("cobblemarket.perm.tab_audit").string
        grantsTabButton?.setMessage(if (currentTab == 0) TextUtil.selectedText(grants) else Text.literal(grants))
        auditTabButton?.setMessage(if (currentTab == 1) TextUtil.selectedText(audit) else Text.literal(audit))
    }

    private fun switchTab(tab: Int) {
        if (currentTab == tab) return
        currentTab = tab
        scrollOffset = 0
        hoveredRow = -1
        updateTabButtons()
        // 搜索框/「添加」只属于页签 1；顺带收起未决的联想请求，免得切回去时冒出旧候选
        val grantsTab = tab == 0
        searchField?.visible = grantsTab
        addButton?.visible = grantsTab
        suggestionsOpen = false
        suggestDirty = false
        lastRequestPrefix = null
        suggestionButtons.forEach { it.visible = false }
        rebuildRowButtons()
        requestCurrentTabData()
    }

    private fun requestCurrentTabData() {
        if (currentTab == 0) sendToServer(RequestPermissionListPayload())
        else sendToServer(RequestPermissionAuditPayload())
    }

    // ── 数据接收 ──

    fun onPermissionList(payload: PermissionListDataPayload) {
        entries = payload.entries
        rebuildFiltered()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, displayCount() - maxVisible()))
        rebuildRowButtons()
    }

    fun onPermissionAudit(payload: PermissionAuditPayload) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")
        val addedLabel = Text.translatable("cobblemarket.perm.audit_added").string
        val removedLabel = Text.translatable("cobblemarket.perm.audit_removed").string
        auditLines = payload.rows.map { row ->
            val time = dateFormat.format(Date(row.timestamp))
            val who = "${row.operator} → ${row.targetName}"
            // 空字段 = 该半边无变更（服务端不再写占位符）：整段不渲染；
            // 两段都空时这行只剩「时间 + 执行者 → 目标」
            val added = row.added.takeIf { it.isNotBlank() }?.let { "$addedLabel: ${nodeLabels(it)}" }.orEmpty()
            val removed = row.removed.takeIf { it.isNotBlank() }?.let { "$removedLabel: ${nodeLabels(it)}" }.orEmpty()
            // 行内会被截断，悬停提示给完整内容
            val tooltip = buildList {
                add("$time  $who")
                if (added.isNotEmpty()) add(added)
                if (removed.isNotEmpty()) add(removed)
            }
            AuditLine(time, who, added, removed, tooltip)
        }
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, displayCount() - maxVisible()))
        rebuildRowButtons()
    }

    fun onNameSuggestions(payload: PlayerNameSuggestionsPayload) {
        // 弹窗打开时忽略（搜索框已隐藏）
        if (editingEntry != null) return
        // 陈旧响应：输入已变化（含点选联想填入的名字）→ 忽略，防止列表收起后又被重新展开
        if ((searchField?.text?.trim() ?: "") != lastRequestPrefix) return
        suggestions = payload.names
        // 有候选且输入非空 → 展开；否则收起
        suggestionsOpen = suggestions.isNotEmpty() && !(searchField?.text?.isBlank() ?: true)
        suggestScroll = 0
        rebuildSuggestionList()
    }

    // ── 列表 ──

    /**
     * 节点显示名：翻译表里有就用词条，没有就退回短名 —— 数据包/未来新增节点没配词条时
     * 显示原始 key（cobblemarket.perm.node.xxx）比显示短名更难认
     */
    private fun nodeLabel(node: String): String {
        val short = node.removePrefix(MarketPermissions.PREFIX)
        val key = "cobblemarket.perm.node.$short"
        val t = Text.translatable(key)
        return if (t.string == key) short else t.string
    }

    /** 审计记录里的节点串 → 本地化名称串（变更记录的行与悬停都走这里，
     *  保证和权限按钮、聊天通知显示的是同一套名字，而不是 blacklist 这种内部 key）。
     *  ⚠ 分隔符按**逗号或空格**都切：新写的 CSV 用 `, `，但历史账本里存的是空格分隔，两种都要认。 */
    private fun nodeLabels(raw: String): String =
        raw.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.joinToString(", ") { nodeLabel(it) }

    /** 重建过滤结果与行文本缓存（搜索变化 / 数据到达 / tab 无关，统一入口） */
    private fun rebuildFiltered() {
        val query = searchField?.text?.trim()?.takeIf { it.isNotEmpty() }
        filtered = if (query == null) entries
        else entries.filter { it.playerName.contains(query, ignoreCase = true) }
        // 缓存拆成两段：名字金色、节点串白色，行渲染要分两次绘制
        rowNames = filtered.map { it.playerName }
        rowNodes = filtered.map { entry -> entry.nodes.joinToString(", ") { nodeLabel(it) } }
    }

    private fun rebuildRowButtons() {
        editButtons.forEach { remove(it) }
        editButtons.clear()
        removeButtons.forEach { remove(it) }
        removeButtons.clear()
        // 行内按钮只属于页签 1：页签 2 是只读记录，切过去不能留着上一页签的按钮
        if (currentTab != 0) return
        val leftX = width / 2 - panelWidth / 2
        filtered.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, entry ->
            val y = listStartY() + i * rowHeight
            val editBtn = NineSliceButton(
                leftX + editBtnX, y + 3, 44, 16,
                Text.translatable("cobblemarket.price_limit.edit"),
                { openEditDialog(entry) }
            )
            editBtn.visible = editingEntry == null
            editButtons.add(editBtn)
            addDrawableChild(editBtn)

            val delBtn = NineSliceButton(
                leftX + removeBtnX, y + 3, 44, 16,
                Text.translatable("cobblemarket.price_limit.remove"),
                // 直接发删除包（照 PriceLimitScreen）：服务端删完回刷新后的列表
                { sendToServer(RemovePlayerPermissionsPayload(entry.playerUuid)) }
            )
            delBtn.visible = editingEntry == null
            removeButtons.add(delBtn)
            addDrawableChild(delBtn)
        }
    }

    // ── 编辑弹窗 ──

    /**
     * 「添加」：用搜索框里的名字给一名玩家授权（列表里没有的玩家也能加）。
     * 这里造一条**本地临时条目**（不来自服务端列表）：UUID 故意全 0，服务端按 playerName 解析玩家，
     * 客户端不查 UUID —— 名字解析涉及在线/离线/改名，规则只在服务端一处维护。
     */
    private fun addBySearchName() {
        val name = searchField?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return
        openEditDialog(PermissionListDataPayload.Entry(EMPTY_UUID, name, emptyList()))
    }

    /**
     * 打开编辑弹窗：勾选预填 [prefill]，缺省用玩家现有节点。
     * 重建（resize / 关闭二次确认）时传当前勾选快照 —— 否则未保存的改动会被 entry.nodes 覆盖回去。
     */
    private fun openEditDialog(entry: PermissionListDataPayload.Entry, prefill: Set<String>? = null) {
        editingEntry = entry
        selectedNodes.clear()
        selectedNodes.addAll(prefill ?: entry.nodes)
        // 收起可能展开的联想列表（否则联想按钮残留在遮罩下，仍可点）
        suggestionsOpen = false
        suggestDirty = false
        lastRequestPrefix = null
        suggestionButtons.forEach { it.visible = false }
        hideMainControls()
        val centerX = width / 2
        val dialogX = centerX - DIALOG_W / 2
        val dialogY = height / 2 - DIALOG_H / 2

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderEditDialogBackground(context)
            }
        })

        // 8 个节点两列 × 四行，点一下切换勾选；勾选态 = 按下视觉（红底），
        // 与黑名单/求购单的「手持物品」按钮同一机制
        nodeButtonByNode.clear()
        MarketPermissions.ALL.forEachIndexed { idx, node ->
            val col = idx % 2
            val row = idx / 2
            val btn = NineSliceButton(
                dialogX + DIALOG_MARGIN + col * (NODE_W + 8), dialogY + NODE_GRID_Y + row * NODE_ROW_H, NODE_W, 16,
                // 词条长度不受控（新语言/新节点都可能更长），按按钮内宽截断防溢出
                TextUtil.truncateText(
                    Text.translatable("cobblemarket.perm.node." + node.removePrefix(MarketPermissions.PREFIX)),
                    NODE_W - 8
                ),
                { onNodeClicked(node) },
                // 三段贴图：第三段是**选中态**（选中节点后保持高亮，鼠标移开也不消失）
                texH = 120
            )
            btn.selectedVisual = node in selectedNodes
            nodeButtonByNode[node] = btn
            addDrawableChild(btn)
        }

        saveButton = NineSliceButton(
            dialogX + 25, dialogY + DIALOG_BTN_Y, 80, 20,
            Text.translatable("cobblemarket.perm.save"),
            { confirmSave() }
        )
        addDrawableChild(saveButton)
        cancelButton = NineSliceButton(
            dialogX + 115, dialogY + DIALOG_BTN_Y, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeEditDialog() }
        )
        addDrawableChild(cancelButton)
    }

    /** 藏掉编辑层全部控件（二次确认弹窗盖上来时用，见 saveButton 注释）；恢复走整层重建 */
    private fun hideEditLayer() {
        nodeButtonByNode.values.forEach { it.visible = false }
        saveButton?.visible = false
        cancelButton?.visible = false
    }

    private fun renderEditDialogBackground(context: DrawContext) {
        // 二次确认层盖上来时这一层整段不画：标题是 render 里直接绘制的文字（不是控件），
        // 遮罩盖不住它（文字与 fill 不在同一层，后画的文字仍会浮在遮罩上），
        // 只能整层跳过 —— 遮罩与弹窗底由确认层自己画，视觉上没有缺失
        if (configConfirmOpen) return

        val centerX = width / 2
        val dialogX = centerX - DIALOG_W / 2
        val dialogY = height / 2 - DIALOG_H / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, DIALOG_W, DIALOG_H, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.perm.dialog_title").formatted(Formatting.GOLD),
            centerX, dialogY + 12, 0xFFFFFF)
        // 节点按钮本身看不出改的是谁，这里点名（超长名字截断，别压到网格上）
        editingEntry?.let {
            context.drawCenteredTextWithShadow(textRenderer,
                TextUtil.truncateText(Text.literal(it.playerName), DIALOG_W - 24), centerX, dialogY + 26, GOLD_COLOR)
        }
    }

    /** 勾选切换：只改本地集合与按钮视觉，不发包（一个节点一个包会撞服务端写节流，中途点击被静默丢弃） */
    private fun toggleNode(node: String) {
        if (!selectedNodes.add(node)) selectedNodes.remove(node)
        nodeButtonByNode[node]?.selectedVisual = node in selectedNodes
    }

    // ── 「服务器配置」二次确认（整个金融系统的参数都在这个节点下，防手滑勾中）──

    private fun onNodeClicked(node: String) {
        // 未选中 → 授予方向，走 3 秒冷静期确认；已选中 → 收回方向，直接切换（收回不需要冷静）
        if (node == MarketPermissions.CONFIG && node !in selectedNodes) openConfigConfirm()
        else toggleNode(node)
    }

    private fun openConfigConfirm() {
        configConfirmOpen = true
        configConfirmOpenedAt = System.currentTimeMillis()
        // 下层编辑弹窗整层控件藏掉：节点按钮与确认键 y 区域重叠，留着会按添加顺序拦掉确认点击
        hideEditLayer()

        // 弹窗背景（含遮罩）画在按钮之下：Drawable 在 children 之前渲染，后添加才能盖住编辑层
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderConfigConfirmBackground(context)
            }
        })

        val centerX = width / 2
        val dialogY = height / 2 - CONFIRM_H / 2
        configConfirmButton = NineSliceButton(
            centerX - 85, dialogY + CONFIRM_BTN_Y, 80, 20,
            Text.translatable("cobblemarket.perm.confirm_yes"),
            { confirmConfigNode() }
        )
        addDrawableChild(configConfirmButton)
        addDrawableChild(NineSliceButton(
            centerX + 5, dialogY + CONFIRM_BTN_Y, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeConfigConfirm() }
        ))
    }

    private fun renderConfigConfirmBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogX = centerX - CONFIRM_W / 2
        val dialogY = height / 2 - CONFIRM_H / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, CONFIRM_W, CONFIRM_H, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.perm.config_confirm_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)
    }

    /** 正文 4 行逐行渲染：红=警告、白=普通（照 ServerConfigScreen 的蛋交易确认正文） */
    private fun renderConfigConfirmText(context: DrawContext) {
        val centerX = width / 2
        val dialogY = height / 2 - CONFIRM_H / 2
        val lines = listOf(
            "cobblemarket.perm.config_confirm_l1_warn" to 0xFF5555,
            "cobblemarket.perm.config_confirm_l2_text" to 0xFFFFFF,
            "cobblemarket.perm.config_confirm_l3_warn" to 0xFF5555,
            "cobblemarket.perm.config_confirm_l4_text" to 0xFFFFFF
        )
        var ty = dialogY + CONFIRM_TEXT_Y
        lines.forEach { (key, color) ->
            val text = Text.translatable(key).string
            // 空行跳过：中英行数/断行不同也不会把整块文字顶下去
            if (text.isEmpty()) return@forEach
            context.drawCenteredTextWithShadow(textRenderer, text, centerX, ty, color)
            ty += CONFIRM_LINE_H
        }
    }

    /** 冷静期：3 秒内确认键置灰（dimmed = 仍可点，点了只播 fail）并显示剩余秒数 */
    private fun updateConfigConfirmButton() {
        val cooldownLeft = (CONFIRM_COOLDOWN_MS / 1000L - (System.currentTimeMillis() - configConfirmOpenedAt) / 1000L).toInt()
        val canConfirm = cooldownLeft <= 0
        configConfirmButton?.dimmed = !canConfirm
        configConfirmButton?.message = if (canConfirm)
            Text.translatable("cobblemarket.perm.confirm_yes")
        else
            Text.translatable("cobblemarket.perm.confirm_yes_countdown", cooldownLeft)
    }

    private fun confirmConfigNode() {
        // 冷静期内点击：置灰按钮仍可点，播 fail 音效提示，不执行
        if (System.currentTimeMillis() - configConfirmOpenedAt < CONFIRM_COOLDOWN_MS) {
            playFailSound()
            return
        }
        // 只改本地勾选（保存仍由编辑弹窗的「保存」统一发全量包），关掉确认层回到编辑弹窗
        selectedNodes.add(MarketPermissions.CONFIG)
        closeConfigConfirm()
    }

    private fun closeConfigConfirm() {
        val entry = editingEntry
        val selected = selectedNodes.toSet()
        configConfirmOpen = false
        configConfirmButton = null
        // 照项目惯例 clearChildren + init 重建，再把编辑弹窗搭回来：
        // 勾选用重建前的快照恢复（含刚确认的「服务器配置」红底），不回退成 entry.nodes
        clearChildren()
        init()
        if (entry != null) openEditDialog(entry, selected)
    }

    private fun confirmSave() {
        val entry = editingEntry ?: return
        // 一次发完整节点集合（服务端算差集）：按 ALL 顺序稳定排列，与列表显示顺序一致
        sendToServer(SavePlayerPermissionsPayload(
            entry.playerUuid,
            entry.playerName,
            MarketPermissions.ALL.filter { it in selectedNodes }
        ))
        closeEditDialog()
    }

    private fun closeEditDialog() {
        editingEntry = null
        selectedNodes.clear()
        nodeButtonByNode.clear()
        saveButton = null
        cancelButton = null
        configConfirmOpen = false
        configConfirmButton = null
        clearChildren()
        init()
    }

    private fun hideMainControls() {
        searchField?.visible = false
        backButton?.visible = false
        addButton?.visible = false
        // 页签也一样：遮罩不是点击屏障，留着能在弹窗开着时把底下的列表换掉
        grantsTabButton?.visible = false
        auditTabButton?.visible = false
        editButtons.forEach { it.visible = false }
        removeButtons.forEach { it.visible = false }
        suggestionButtons.forEach { it.visible = false }
    }

    // ── 玩家名联想 ──

    /** 防抖触发联想请求（render 每帧检查，停止输入 250ms 后发，避免被服务端 500ms 节流丢弃） */
    private fun checkSuggestDebounce() {
        if (!suggestDirty) return
        if (System.currentTimeMillis() - lastSuggestEdit < 250) return
        suggestDirty = false
        lastRequestPrefix = searchField?.text?.trim().orEmpty()
        sendToServer(RequestPlayerNameSuggestionsPayload(lastRequestPrefix ?: ""))
    }

    private fun rebuildSuggestionList() {
        // 收起路径（含点选回调）只切 visible，不在 mouseClicked 栈内 remove 子元素（该操作不可靠）；
        // remove+重建只发生在展开路径（onNameSuggestions 数据到达，该路径一直工作正常）
        if (!suggestionsOpen) {
            suggestionButtons.forEach { it.visible = false }
            return
        }
        suggestionButtons.forEach { remove(it) }
        suggestionButtons.clear()
        val leftX = width / 2 - panelWidth / 2
        suggestions.drop(suggestScroll).take(MAX_SUGGESTION_ROWS).forEachIndexed { i, name ->
            val btn = NineSliceButton(
                leftX + 2, searchRowY + 18 + i * SUGGEST_ROW_H, searchFieldW, SUGGEST_ROW_H,
                Text.literal(name),
                {
                    suppressSuggest = true
                    searchField?.text = name
                    // setText 会触发多次 changed 事件（suppress 只吞第一次，后续会置 dirty）——
                    // 必须在 setText 之后清一次，否则 250ms 后防抖发请求、快照等于完整名被放行重新展开
                    suggestDirty = false
                    lastRequestPrefix = null // 使所有在途响应失效
                    suggestionsOpen = false
                    rebuildSuggestionList()
                    // 点选 = 按完整名字过滤列表（changedListener 已被 suppress 吞掉，这里手动补上）
                    rebuildFiltered()
                    scrollOffset = 0
                    hoveredRow = -1
                    rebuildRowButtons()
                }
            )
            suggestionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val panelLeft = width / 2 - 160
        val panelTop = 2
        val panelBottom = height - 32
        val sliceH = 16

        val top = Identifier.of("cobblemarket", "textures/gui/market_panel_top.png")
        val mid = Identifier.of("cobblemarket", "textures/gui/market_panel_middle.png")
        val bot = Identifier.of("cobblemarket", "textures/gui/market_panel_bottom.png")

        drawPanelSlice(context, top, panelLeft, panelTop)
        var y = panelTop + sliceH
        while (y < panelBottom - sliceH) {
            drawPanelSlice(context, mid, panelLeft, y, minOf(sliceH, panelBottom - sliceH - y))
            y += sliceH
        }
        drawPanelSlice(context, bot, panelLeft, panelBottom - sliceH)

        val leftX = width / 2 - panelWidth / 2
        val visibleRows = maxVisible()
        // 列表起始 y 随页签走（页签 2 没有搜索行，整段上提），悬停范围必须与 render 用同一个值
        val startY = listStartY()
        val listAreaBottom = startY + visibleRows * rowHeight

        hoveredRow = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY in startY..listAreaBottom) {
            val row = (mouseY - startY) / rowHeight
            // 悬停索引按"当前显示的行"算（与 render/rebuildRowButtons 同一套 drop+take），
            // 不能拿它当原始索引去查全量列表
            val shownCount = minOf(visibleRows, displayCount() - scrollOffset)
            if (row in 0 until shownCount) hoveredRow = row
        }

        repeat(minOf(visibleRows, maxOf(0, displayCount() - scrollOffset))) { di ->
            val rowY = startY + di * rowHeight
            val rowState = if (di == hoveredRow) 1 else 0
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, rowState, ROW_BACKGROUND_TEX_H)
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        // 二次确认层最靠上：正文与冷静期倒计时画在编辑弹窗之上（编辑层控件此刻已 visible=false）
        if (configConfirmOpen) {
            renderConfigConfirmText(context)
            updateConfigConfirmButton()
            return
        }

        // 弹窗打开时下半部分由弹窗自己画（列表文字会刺穿遮罩）
        if (editingEntry != null) return

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.perm.title").formatted(Formatting.GOLD),
            centerX, 20, 0xFFFFFF)

        // 防抖触发联想请求
        checkSuggestDebounce()

        // 联想列表展开时跳过分隔线与列表：这些文字画在 children 之后，会刺穿联想按钮
        if (suggestionsOpen) return

        // 列表起始 y 随页签走（页签 2 没有搜索行，分割线与首行整段上提）
        val startY = listStartY()
        drawPanelDivider(context, leftX + panelWidth / 2, startY - 4)

        if (displayCount() == 0) {
            // 区分「本来就没有记录」与「被搜索过滤掉了」：输入一个还没授权的名字时列表会被过滤空，
            // 那时沿用「没有授予记录」会让玩家以为列表坏了 —— 改成告诉他下一步该点「添加」
            // （2026-09-16 用户反馈；不带玩家名是为了避免长名字把提示顶出面板）
            val filteredOut = currentTab == 0 && entries.isNotEmpty() &&
                !searchField?.text?.trim().isNullOrEmpty()
            val msg = if (filteredOut) Text.translatable("cobblemarket.perm.list_no_match")
            else Text.translatable(if (currentTab == 0) "cobblemarket.perm.list_empty" else "cobblemarket.perm.audit_empty")
            context.drawCenteredTextWithShadow(textRenderer, msg.formatted(Formatting.GRAY), centerX, startY + 30, 0xFFFFFF)
            return
        }

        if (currentTab == 0) {
            filtered.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, _ ->
                val y = startY + i * rowHeight
                val idx = scrollOffset + i
                var cursor = leftX + 6
                // 混排行分两段绘制（名字金色 + 节点串白色）：drawCenteredTextWithShadow 会让行首汉字
                // 比同行其他字高一点，这里自己算 x 走 drawTextWithShadow
                val name = rowNames[idx]
                context.drawTextWithShadow(textRenderer, name, cursor, y + 7, GOLD_COLOR)
                cursor += textRenderer.getWidth(name)
                val nodes = rowNodes[idx]
                if (nodes.isNotEmpty()) {
                    val sep = "  ·  "
                    context.drawTextWithShadow(textRenderer, sep, cursor, y + 7, 0x555555)
                    cursor += textRenderer.getWidth(sep)
                    // 8 个节点短名（英文）拼起来远超行宽，按剩余宽度截断；完整节点列表看悬停提示
                    val remain = leftX + editBtnX - 6 - cursor
                    if (remain > 0) {
                        context.drawTextWithShadow(textRenderer, TextUtil.truncateString(nodes, remain), cursor, y + 7, 0xFFFFFF)
                    }
                }
            }
        } else {
            // 变更记录：时间(灰) + 执行者→目标(金) + 新增(绿)/移除(红)。
            // 每段按剩余宽度截断，宁可截尾也不越过面板右边界（悬浮给完整内容）
            val right = leftX + panelWidth - 6
            auditLines.drop(scrollOffset).take(maxVisible()).forEachIndexed { i, line ->
                val y = startY + i * rowHeight
                var cursor = leftX + 6
                context.drawTextWithShadow(textRenderer, line.time, cursor, y + 7, 0x888888)
                cursor += textRenderer.getWidth(line.time) + 6
                val who = TextUtil.truncateString(line.who, (right - cursor - 40).coerceAtLeast(0))
                context.drawTextWithShadow(textRenderer, who, cursor, y + 7, GOLD_COLOR)
                cursor += textRenderer.getWidth(who) + 6
                if (line.added.isNotEmpty() && cursor < right) {
                    val text = TextUtil.truncateString(line.added, right - cursor)
                    context.drawTextWithShadow(textRenderer, text, cursor, y + 7, 0x55FF55)
                    cursor += textRenderer.getWidth(text) + 6
                }
                if (line.removed.isNotEmpty() && cursor < right) {
                    context.drawTextWithShadow(textRenderer,
                        TextUtil.truncateString(line.removed, right - cursor), cursor, y + 7, 0xFF5555)
                }
            }
        }

        if (displayCount() > maxVisible()) {
            context.drawCenteredTextWithShadow(textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible(), displayCount())} / ${displayCount()}",
                centerX, height - 49, 0x888888)
        }

        // 悬停行提示：行内文本会被截断，这里给完整内容
        if (hoveredRow >= 0) {
            val actualIdx = scrollOffset + hoveredRow
            if (currentTab == 0) {
                if (actualIdx in filtered.indices) {
                    val entry = filtered[actualIdx]
                    renderTooltip(context, listOf(
                        entry.playerName,
                        if (entry.nodes.isEmpty()) "-" else entry.nodes.joinToString(", ") { nodeLabel(it) }
                    ), mouseX, mouseY)
                }
            } else if (actualIdx in auditLines.indices) {
                renderTooltip(context, auditLines[actualIdx].tooltip, mouseX, mouseY)
            }
        }
    }

    private fun renderTooltip(context: DrawContext, lines: List<String>, mouseX: Int, mouseY: Int) {
        var maxWidth = 0
        lines.forEach { maxWidth = maxOf(maxWidth, textRenderer.getWidth(it)) }

        val padding = 4
        val tx = minOf(mouseX + 12, width - maxWidth - 12).coerceAtLeast(4)
        val tooltipHeight = lines.size * 10 + padding
        val tyAbove = mouseY - tooltipHeight - 4
        val ty = if (tyAbove <= 0) minOf(mouseY + 12, height - tooltipHeight) else tyAbove

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding, maxWidth + 2 * padding, lines.size * 10 + 2 * padding, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, line ->
            context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, if (i == 0) GOLD_COLOR else 0xFFFFFF)
        }
        context.matrices.pop()
    }

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int, sliceH: Int = 16) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    // ── 交互 ──

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        // 二次确认弹窗打开时不滚动下层列表（重建行按钮会把下层控件又改回可见）
        if (configConfirmOpen) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        // 联想列表滚动
        if (suggestionsOpen) {
            if (suggestions.size > MAX_SUGGESTION_ROWS) {
                suggestScroll = (suggestScroll - verticalAmount.toInt()).coerceIn(0, suggestions.size - MAX_SUGGESTION_ROWS)
                rebuildSuggestionList()
            }
            return true
        }
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, displayCount() - maxVisible()))
        rebuildRowButtons()
        return true
    }

    private fun isInputFieldFocused() = focused?.let { f -> f === searchField } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        // 点击空白处收起联想列表（点联想按钮时回调已收起，此判断不重复执行；
        // 收起时同步清除防抖与快照，防在途响应重新展开）
        if (suggestionsOpen && !isMouseOverSuggestionArea(mouseX, mouseY)) {
            suggestionsOpen = false
            suggestDirty = false
            lastRequestPrefix = null
            rebuildSuggestionList()
        }
        return result
    }

    private fun isMouseOverSuggestionArea(mouseX: Double, mouseY: Double): Boolean {
        if (searchField?.isMouseOver(mouseX, mouseY) == true) return true
        return suggestionButtons.any { it.visible && it.isMouseOver(mouseX, mouseY) }
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        // resize 会 clearChildren + init()（弹窗控件被清掉），按字段原样把弹窗逐层重建回来，
        // 未保存的勾选从 selectedNodes 快照恢复，不请求快照也不回退成旧数据
        val entry = editingEntry
        val selected = selectedNodes.toSet()
        val confirmWasOpen = configConfirmOpen
        super.resize(client, width, height)
        if (entry != null) {
            openEditDialog(entry, selected)
            // 二次确认层照原样盖回去（冷静期重新计时，与 ServerConfigScreen 的 resize 行为一致）
            if (confirmWasOpen) openConfigConfirm()
        }
    }

    override fun shouldPause() = false
}
