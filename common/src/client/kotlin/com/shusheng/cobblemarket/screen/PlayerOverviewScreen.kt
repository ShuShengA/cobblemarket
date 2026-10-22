package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.OVERVIEW_ICON_W
import com.shusheng.cobblemarket.client.PlayerOverviewCache
import com.shusheng.cobblemarket.client.PointsStateCache
import com.shusheng.cobblemarket.client.TooltipPlacement
import com.shusheng.cobblemarket.client.currencyUnitWidth
import com.shusheng.cobblemarket.client.drawCurrencyUnit
import com.shusheng.cobblemarket.client.drawLineIcon
import com.shusheng.cobblemarket.client.formatPriceLong
import com.shusheng.cobblemarket.client.lineIconY
import com.shusheng.cobblemarket.client.lineTextY
import com.shusheng.cobblemarket.client.noneValue
import com.shusheng.cobblemarket.client.overviewLines
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.overview.PlayerOverviewRow
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/**
 * **全服玩家总览**（服主 / `overview` 节点持有者）。
 *
 * 版式照 `LoanHistoryScreen` 逐项抄：面板背景三张拼接、行高 24、底部预留 48、滚动指示器在
 * `height - 49`、悬停窗走 `TooltipPlacement`。
 *
 * 与参考屏的差别：
 * - 顶栏多了**搜索框 + 两个排序按钮**（一个选字段、一个切方向）——
 *   本界面没有别的可筛项，**有意不开筛选面板**（方案第三节）
 * - **搜索与排序全在本地做**（服务端一次全量下发，见方案第六节）⇒ 切排序 / 打字零延迟、不发请求
 * - 行 = 玩家名 + **当前排序字段的值**（能按利息排却不显示利息的话，排完还得挨个悬停才看得出谁高）
 *
 * ⚠ 界面不画精灵图标 ⇒ 按项目约定**不需要** `IconRegistry.beginFrame`。
 */
class PlayerOverviewScreen : Screen(Text.translatable("cobblemarket.overview.title")) {

    /**
     * **内容宽度 296** —— 照同类界面（黑名单 / 交易历史）的惯例。
     * ⚠ 面板**背景**仍是 `MarketPanelBackground.WIDTH`（320），比内容宽、居中留边，
     *   两者不是一回事（见 docs/gui_pitfalls.md 的「320 vs 300」那条）。
     */
    private val panelWidth = 296

    /** 排序字段（列表顺序 = 字段按钮的循环顺序） */
    private enum class SortField(val key: String) {
        NAME("name"),
        BALANCE("balance"),
        /** ⚠ RP 余额 —— 与主货币是**两套账本**；按钮文字用货币图标开头（界面里不出现「RP」字样） */
        POINTS("points"),
        CREDIT("credit"),
        DEBT("debt"),
        DEPOSIT("deposit"),
        INTEREST("interest"),
        LISTINGS("listings"),
        VOLUME("volume"),
        /** ⚠ PCO 余额 —— **只有装了 Cobblemon Economy 才有这个概念**，没装的服不列这个选项 */
        PCO("pco"),
        DEX_SEEN("dex_seen"),
        DEX_CAUGHT("dex_caught"),
    }

    private var sortField = SortField.NAME

    /** 方向：默认按**名字升序**（方案第三节）；切到数值字段时自动改降序（谁多谁在前更实用） */
    private var descending = false

    /** 搜索框内容。⚠ 切排序会 `clearAndInit()` 重建控件，靠它把已敲的字恢复回去 */
    private var searchText = ""
    private var searchField: TextFieldWidget? = null
    private var hoveredRow = -1
    private var scrollOffset = 0

    /** 只向服务端要一次数据 —— `init()` 会被 resize / 切排序重建控件时反复调，别每次都发请求 */
    private var requested = false

    /**
     * 排序字段的**展开列表**是否打开。
     * ⚠ 2026-10-16 用户实测指出：别用最早那种「点一下循环切一个」—— 项目里其他界面
     *   （形态 / 属性 / 特性 / 性格的选项）都是**展开选中**、选中项靠 `selectedVisual` 高亮。
     */
    private var sortListOpen = false
    private val sortOptionButtons = mutableListOf<NineSliceButton>()
    private var sortFieldButton: NineSliceButton? = null

    // ── 顶栏几何（搜索框与按钮**同一行**、y = 58、左右各留 2px）──
    // 字段按钮只显示字段名本身（英文最长 "Remaining credit" ≈ 96px），不带宽的「排序：」前缀
    private val searchW = 114
    private val fieldBtnW = 100
    private val dirBtnW = 72

    /** 顶栏 y；顶栏底 74 → 分割线 80 → 列表首行 86（上下各 6px） */
    private val topRowY = 58

    override fun init() {
        super.init()
        val leftX = width / 2 - panelWidth / 2
        val topY = topRowY

        // 返回按钮（右上，照参考屏：y = 13）
        addDrawableChild(NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(AdminScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        ))

        // 搜索框（靠左，左右各留 2px —— 照 BlacklistScreen）
        val field = TextFieldWidget(textRenderer, leftX + 2, topY, searchW, 16, Text.literal(""))
        field.setPlaceholder(Text.translatable("cobblemarket.overview.search"))
        field.text = searchText
        field.setChangedListener { searchText = it; scrollOffset = 0 }
        searchField = addDrawableChild(field)

        // 排序：字段按钮 + 方向按钮（与搜索框**同一行**，靠右并排、间距 4、右缘留 2px）
        val dirX = leftX + panelWidth - 2 - dirBtnW
        val fieldX = dirX - 4 - fieldBtnW
        sortFieldButton = addDrawableChild(NineSliceButton(
            fieldX, topY, fieldBtnW, 16,
            sortFieldText(sortField),
            { toggleSortList() },
            tooltip = Text.translatable("cobblemarket.overview.sort_by_hint")
        ))
        // 方向按钮：**只显示上下箭头**（状态由符号本身表达，符合「切换类按钮」的口径）
        addDrawableChild(NineSliceButton(
            dirX, topY, dirBtnW, 16,
            Text.translatable(if (descending) "cobblemarket.overview.sort_desc" else "cobblemarket.overview.sort_asc"),
            { descending = !descending },
            tooltip = Text.translatable("cobblemarket.overview.sort_dir_hint")
        ))

        // 展开列表的底板（先 add ⇒ 画在选项按钮**下面**；不铺底板的话会压着列表行看不清）
        addDrawable(object : net.minecraft.client.gui.Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                if (!sortListOpen) return
                val lx = width / 2 - panelWidth / 2
                // ⚠ 用**纯色不透明底**，别套九宫格：九宫格自带边框，会和选项按钮自己的边框
                //   叠成"多了一圈"的双层边（2026-10-16 用户实测报）
                // ⚠ 底板要**与选项按钮完全重合**：之前四周各多留 2~4px，露出来的就是一圈明显的
                //   黑框（2026-10-16 用户实测报「底部有这么明显的黑色框」）。
                //   按钮之间没有间隙，铺满就被盖住、只剩边角不露白
                context.fill(
                    lx + 120, topY + 18,
                    lx + 240, topY + 18 + availableFields().size * 14,
                    0xF0101010.toInt()
                )
            }
        })
        // resize / rebuild 后把展开的选项按钮补回来（状态保留在 sortListOpen）
        rebuildSortList()

        if (!requested) {
            PlayerOverviewCache.request()
            requested = true
        }
    }

    /**
     * 当前**可用**的排序字段。
     * ⚠ PCO 只在装了 Cobblemon Economy 时才有 —— 判据走**数据**（有没有谁的 PCO 余额读得到），
     *   不去查模组：专用服务器上客户端不一定装了 CE，查了也不准。
     */
    private fun availableFields(): List<SortField> =
        SortField.entries.filter {
            when (it) {
                SortField.PCO -> PlayerOverviewCache.rows.any { r -> r.pcoBalance != null }
                // RP 总开关关着时不列（全员都是 0，排了也没意义）—— 与入口界面那行 RP 的显隐同源
                SortField.POINTS -> PointsStateCache.enabled
                else -> true
            }
        }

    /**
     * 排序字段的显示文字。
     * ⚠ RP 那一档**用货币图标开头**（项目规矩：界面里不出现「RP」文字）——
     *   与主货币的「余额」同字，靠图标区分是两套账本。
     */
    private fun sortFieldText(f: SortField): Text =
        if (f == SortField.POINTS)
            Text.empty()
                .append(CurrencyService.pointsIcon())
                .append(" ")
                .append(Text.translatable("cobblemarket.overview.sort.points"))
        else Text.translatable("cobblemarket.overview.sort.${f.key}")

    private fun toggleSortList() {
        sortListOpen = !sortListOpen
        rebuildSortList()
    }

    /** 选中一个排序字段：收起列表、按钮文字跟着换；从名字切到数值字段时自动改降序（反之回升序） */
    private fun selectSortField(f: SortField) {
        sortField = f
        descending = f != SortField.NAME
        sortListOpen = false
        hoveredRow = -1
        scrollOffset = 0
        rebuildSortList()
        sortFieldButton?.setMessage(sortFieldText(f))
    }

    /** 重建展开的字段列表（照 BlacklistScreen.rebuildFormList：每次全清全建） */
    private fun rebuildSortList() {
        sortOptionButtons.forEach { remove(it) }
        sortOptionButtons.clear()
        if (!sortListOpen) return
        val fields = availableFields()
        // 当前选的字段要是不再可用（比如从装了 CE 的服换到没装的）⇒ 回退到按名字
        if (sortField !in fields) sortField = SortField.NAME
        val leftX = width / 2 - panelWidth / 2
        fields.forEachIndexed { i, f ->
            val btn = NineSliceButton(
                leftX + 120, topRowY + 18 + i * 14, 120, 14,
                sortFieldText(f),
                { selectSortField(f) }
            )
            // 选中项靠按钮自己的选中态（背景第三段），不在文字前加 ●
            btn.selectedVisual = f == sortField
            sortOptionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // ── 数据：本地搜索 + 本地排序 ──

    private fun displayRows(): List<PlayerOverviewRow> {
        val q = searchText.trim().lowercase()
        val filtered = if (q.isEmpty()) PlayerOverviewCache.rows
        else PlayerOverviewCache.rows.filter { it.name.lowercase().contains(q) }
        if (sortField == SortField.NAME) {
            val sorted = filtered.sortedBy { it.name.lowercase() }
            return if (descending) sorted.reversed() else sorted
        }
        // ⚠ 拿不到的（null = 离线且还没记过快照）**恒排最后**、不随方向翻转 ——
        //   用 `sortedWith(nullsLast{})` 再整体 `reversed()` 会把它们**翻到最前面**
        val (has, missing) = filtered.partition { sortNumeric(it) != null }
        val sorted = if (descending) has.sortedByDescending { sortNumeric(it) } else has.sortedBy { sortNumeric(it) }
        return sorted + missing
    }

    /** 当前排序字段的数值（名字字段不走这里） */
    private fun sortNumeric(r: PlayerOverviewRow): Long? = when (sortField) {
        SortField.NAME -> null
        SortField.BALANCE -> r.balance
        SortField.POINTS -> r.pointsBalance
        SortField.CREDIT -> r.creditLimit
        SortField.DEBT -> r.debt + r.debtInterest
        SortField.DEPOSIT -> r.depositPrincipal
        SortField.INTEREST -> r.depositInterest
        SortField.LISTINGS -> (r.pokemonListings + r.itemListings + r.activeAuctions + r.openBuyOrders).toLong()
        SortField.VOLUME -> r.totalVolume
        SortField.PCO -> r.pcoBalance
        SortField.DEX_SEEN -> r.dexSeen?.toLong()
        SortField.DEX_CAUGHT -> r.dexCaught?.toLong()
    }

    /** 行右侧显示的值；null = 不显示（排序字段是名字时，右边那格留空不画） */
    private fun rowValueText(r: PlayerOverviewRow): Text? = when (sortField) {
        SortField.NAME -> null
        SortField.BALANCE -> Text.literal(r.balance?.let { formatPriceLong(it) } ?: noneValue())
        SortField.POINTS -> Text.literal(formatPriceLong(r.pointsBalance))
        SortField.CREDIT -> Text.literal(formatPriceLong(r.creditLimit))
        SortField.DEBT -> Text.literal(formatPriceLong(r.debt + r.debtInterest))
        SortField.DEPOSIT -> Text.literal(formatPriceLong(r.depositPrincipal))
        SortField.INTEREST -> Text.literal(formatPriceLong(r.depositInterest))
        SortField.LISTINGS ->
            Text.literal(((r.pokemonListings + r.itemListings + r.activeAuctions + r.openBuyOrders)).toString())
        SortField.VOLUME -> Text.literal(formatPriceLong(r.totalVolume))
        SortField.PCO -> Text.literal(r.pcoBalance?.let { formatPriceLong(it) } ?: noneValue())
        SortField.DEX_SEEN -> Text.literal(r.dexSeen?.toString() ?: noneValue())
        SortField.DEX_CAUGHT -> Text.literal(r.dexCaught?.toString() ?: noneValue())
    }

    /** 行值那一列是不是金额（要在行尾补货币单位：物品货币时是那件物品的图标） */
    private fun rowValueIsMoney(): Boolean = when (sortField) {
        SortField.BALANCE, SortField.POINTS, SortField.CREDIT, SortField.DEBT, SortField.DEPOSIT,
        SortField.INTEREST, SortField.VOLUME, SortField.PCO -> true
        else -> false
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        MarketPanelBackground.draw(context, width, height)

        val leftX = width / 2 - panelWidth / 2
        val startY = 86
        val rowHeight = 24
        val rows = displayRows()
        val maxVisible = maxOf(3, (height - 48 - startY) / rowHeight)
        val shownCount = minOf(maxVisible, maxOf(0, rows.size - scrollOffset))

        // 悬停行：鼠标落在列表区域内才算（先框内、再除以行高 —— 照参考屏）
        hoveredRow = -1
        if (mouseX in leftX..(leftX + panelWidth) && mouseY in startY until (startY + shownCount * rowHeight)) {
            val row = (mouseY - startY) / rowHeight
            if (row in 0 until shownCount) hoveredRow = row
        }
        repeat(shownCount) { i ->
            drawNineSlice(
                context, ROW_BACKGROUND_TEXTURE, leftX, startY + i * rowHeight, panelWidth, rowHeight,
                if (i == hoveredRow) 1 else 0, ROW_BACKGROUND_TEX_H
            )
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.overview.title").formatted(Formatting.GOLD),
            width / 2, 20, 0xFFFFFF
        )

        val leftX = width / 2 - panelWidth / 2
        val startY = 86
        val rowHeight = 24
        val maxVisible = maxOf(3, (height - 48 - startY) / rowHeight)
        val rows = displayRows()

        // 顶栏与列表之间的分割线：顶栏底 74 → 线 80 → 首行 86（上下各 6px）
        // ⚠ 展开排序列表时**不画** —— 选项按钮是**控件**（在 super.render 里画），而这行分割线
        //   画在控件之后 ⇒ 会横穿列表第一项（2026-10-16 用户实测报「分割线透上来」）
        if (!sortListOpen) drawPanelDivider(context, width / 2, startY - 6)

        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, rows.size - maxVisible))

        if (rows.isEmpty()) {
            val key = if (PlayerOverviewCache.rows.isEmpty()) "cobblemarket.overview.loading" else "cobblemarket.overview.empty"
            context.drawCenteredTextWithShadow(
                textRenderer, Text.translatable(key).formatted(Formatting.GRAY),
                width / 2, startY + 20, 0xFFFFFF
            )
        }

        val visible = rows.drop(scrollOffset).take(maxVisible)
        val isMoney = rowValueIsMoney()
        // ⚠ 单位按**这一列自己的币种**：RP 列补 RP 图标，其余补主货币单位（两者绝不能混）
        val isPoints = sortField == SortField.POINTS
        visible.forEachIndexed { i, r ->
            val y = startY + i * rowHeight

            // 名字：在线白、离线灰（一眼看出这行是不是实时数据）
            val name = TextUtil.truncateText(Text.literal(r.name), panelWidth / 2 - 10)
            context.drawTextWithShadow(textRenderer, name, leftX + 5, y + 8, if (r.online) 0xFFFFFF else 0xAAAAAA)

            // 右侧：当前排序字段的值（右对齐行尾；排序字段是名字时这一格不画）
            val value = rowValueText(r) ?: return@forEachIndexed
            val unitW = if (isMoney) currencyUnitWidth(textRenderer, isPoints) else 0
            val valueX = leftX + panelWidth - 5 - textRenderer.getWidth(value) - unitW
            val drawn = context.drawTextWithShadow(textRenderer, value, valueX, y + 8, 0x55FFFF)
            if (isMoney) drawCurrencyUnit(context, textRenderer, isPoints, drawn, y + 8, 0x55FFFF)
        }

        // 悬停窗 = 行的完整版（方案第二节那一整张表）
        if (hoveredRow in visible.indices) {
            renderOverviewTooltip(context, visible[hoveredRow], mouseX, mouseY)
        }

        if (rows.size > maxVisible) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible, rows.size)} / ${rows.size}",
                width / 2, height - 49, 0x888888
            )
        }
    }

    // ── 悬停窗：行文本走 `overviewLines`（与市场 ID 卡**共用同一份**，见 client/OverviewText.kt）──

    private fun renderOverviewTooltip(context: DrawContext, r: PlayerOverviewRow, mouseX: Int, mouseY: Int) {
        val lines = overviewLines(r)
        // ⚠ 金额行行尾还要补货币单位 ⇒ 宽度得把它算进去，否则单位会顶出面板右缘
        //   （2026-10-16 用户实测报「货币符号超出右侧边框」）
        var maxWidth = 0
        // 面板总高 = 各行高度**之和**（带图标的那两行比普通行高，见 OVERVIEW_ICON_ROW_HEIGHT）
        var contentH = 0
        lines.forEach {
            // 图标行还要把行首那块图标算进去（图鉴那两行）
            val iconW = if (it.icon != null) OVERVIEW_ICON_W else 0
            // ⚠ 单位宽度**按行算**：RP 行补的是 RP 图标、主货币行补 ₽ / 物品图标，两者宽度不同 ——
            //   共用一个宽度会把 RP 行的单位算错、顶出面板右缘
            val unitW = if (it.money) currencyUnitWidth(textRenderer, it.isPoints) else 0
            maxWidth = maxOf(maxWidth, textRenderer.getWidth(it.text) + iconW + unitW)
            contentH += it.height
        }

        val padding = 4
        val (tx, ty) = TooltipPlacement.place(
            mouseX = mouseX, mouseY = mouseY,
            textW = maxWidth, textH = contentH + padding, pad = padding,
            screenW = width, screenH = height
        ) ?: return

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        // ⚠ 九宫格贴图是**半透明**的，直接铺会透出下面的分割线 / 行边框 ⇒ 先垫一层不透明底
        //   （2026-10-16 用户实测报「分割线透过悬停窗」）
        context.fill(
            tx - padding, ty - padding,
            tx + maxWidth + padding, ty + contentH + padding,
            0xF0101010.toInt()
        )
        drawNineSlice(
            context, ROW_BACKGROUND_TEXTURE, tx - padding, ty - padding,
            maxWidth + 2 * padding, contentH + 2 * padding, 1, ROW_BACKGROUND_TEX_H
        )
        // ⚠ 行 y 同样**逐行累加**（不是 `下标 × 10`）—— 带图标的那两行更高
        var rowY = ty
        lines.forEach { line ->
            // ⚠ 行内位置**只用这两个算式**（与 ID 卡共用同一份，别在这里另算一遍）
            val textY = lineTextY(rowY, line)
            val iconY = lineIconY(rowY, line)
            // 行首图标（图鉴那两行）—— 文字要**右移**，别压在图标上
            val iconW = if (line.icon != null) OVERVIEW_ICON_W else 0
            line.icon?.let { drawLineIcon(context, it, tx, iconY) }
            val drawn = context.drawTextWithShadow(textRenderer, line.text, tx + iconW, textY, 0xFFFFFF)
            // 金额行：数字的颜色已由词条参数自带（金 = 能花的钱 / 蓝 = 额度、待还），这里只补行尾的单位
            if (line.money) drawCurrencyUnit(context, textRenderer, line.isPoints, drawn, textY, line.color)
            rowY += line.height
        }
        context.matrices.pop()
    }

    private fun isInputFieldFocused() = searchField?.isFocused == true

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double) =
        searchField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val wasInInput = isInputFieldFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        // 点空白处让搜索框失焦 —— 否则 E 键会被输入框吃掉、整个界面关不掉
        // （老坑，见 memory 的「新建界面必查清单」第一条）
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        return result
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val rowHeight = 24
        // ⚠ 两个 48 与渲染处同源（列表起始 + 底部预留）—— 参考屏特意注明过：改一处必须两处一起改
        // ⚠ 86 = 列表起点（与渲染处同源）、48 = 底部预留 —— 参考屏特意注明过：改一处必须两处一起改
        val maxVisible = maxOf(3, (height - 86 - 48) / rowHeight)
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, displayRows().size - maxVisible))
        return true
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        super.resize(client, width, height)
        hoveredRow = -1
    }

    override fun shouldPause() = false
}
