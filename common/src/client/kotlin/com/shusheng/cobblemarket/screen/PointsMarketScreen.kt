package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.PointsShopCache
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.RedeemPointCodePayload
import com.shusheng.cobblemarket.network.RequestPointsShopPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
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
class PointsMarketScreen : Screen(Text.translatable("cobblemarket.points.market.title")) {

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
    /** 商品数据到位（RP 商城方案定稿）前恒为 1 页 —— 翻页按钮先按到位，数据接上即可用 */
    private var totalPages = 1
    /** 鼠标所在格子索引；-1 = 不在任何格子上。悬停的格子会**放大绘制**（浮在邻居之上） */
    private var hoveredCell = -1

    /** 本次进界面是否已请求过商城快照（入口按钮的显隐要等它回来） */
    private var shopInfoRequested = false

    /** 「购买 RP」入口（显隐由商城快照决定，见 [onShopInfo]） */
    private var buyEntryButton: NineSliceButton? = null

    /** 背景等比缩放后的矩形（居中） */
    private data class BgRect(val x: Int, val y: Int, val w: Int, val h: Int)

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
        addDrawableChild(
            NineSliceButton(
                cx - BACK_W / 2, backButtonY(bg), BACK_W, BACK_H,
                Text.literal(""),
                { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) },
                iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
                iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
                tooltip = Text.translatable("cobblemarket.gui.back")
            )
        )

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
        val rowNeed = REDEEM_BUTTON_W + ROW_GAP + tabGroupW + ROW_GAP + BUY_ENTRY_W + ROW_GAP * 2
        val fieldW = minOf(REDEEM_FIELD_W, bg.w - 2 * inset - rowNeed).coerceAtLeast(MIN_REDEEM_FIELD_W)

        // 兑换码输入框（重建后恢复输入；上限按外部码带分隔符的 39 字符留余量）
        codeField = TextFieldWidget(textRenderer, fieldX, rowY, fieldW, ROW_H, Text.literal(""))
        codeField?.setMaxLength(MAX_CODE_INPUT)
        codeField?.setPlaceholder(Text.translatable("cobblemarket.points.market.code_hint"))
        codeField?.setChangedListener { pendingCodeText = it }
        codeField?.text = pendingCodeText
        addSelectableChild(codeField)
        addDrawableChild(codeField)

        // 兑换按钮：输入框右侧 4px（按钮间距规则）
        addDrawableChild(
            NineSliceButton(
                fieldX + fieldW + ROW_GAP, rowY, REDEEM_BUTTON_W, ROW_H,
                Text.translatable("cobblemarket.points.market.redeem_button"),
                { doRedeem() }
            )
        )

        // 「购买 RP」入口：**与左边的兑换码输入框左右对称**（离各自一侧边框的距离都是 inset、
        // 同一水平线）—— 用户 2026-09-18 指出原来放在标题那一行"太右上"。
        // 只判档位、**不判收款码**：没配收款码时入口照样给，点进去弹窗里会提示缺什么（藏起来
        // 会让服主以为功能坏了）。按钮**总是建出来、靠 visible 开关**：快照到达时只改一个标志，
        // 重建整个界面会清掉玩家已经敲进兑换框的内容。
        val buyBtn = NineSliceButton(
            buyBtnX, rowY,
            BUY_ENTRY_W, ROW_H,
            Text.translatable("cobblemarket.points.recharge.entry"),
            { client?.setScreen(PointsRechargeScreen()) }
        )
        buyBtn.visible = PointsShopCache.tiers.isNotEmpty()
        buyEntryButton = buyBtn
        addDrawableChild(buyBtn)

        // 精灵 / 物品 tab：与兑换行同一水平线，**在「兑换按钮右侧 ~ 购买按钮左侧」这段空档里居中**
        // （用户 2026-09-18 定的是"居中"，但死盯背景正中在小分辨率下会压到左边的兑换按钮上 ——
        //  改成居中于剩余空间后，大分辨率下位置与原来一致，小分辨率下自动让位）
        val tabMinLeft = fieldX + fieldW + ROW_GAP + REDEEM_BUTTON_W + ROW_GAP
        val tabMaxLeft = buyBtnX - ROW_GAP - tabGroupW
        val tabLeftX =
            if (tabMinLeft <= tabMaxLeft) (cx - tabGroupW / 2).coerceIn(tabMinLeft, tabMaxLeft)
            else tabMinLeft // 极端小分辨率：紧跟兑换按钮（再挤下去就不是重叠而是看不看得见的问题了）
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
        val wasInInput = focused != null && focused === codeField
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && codeField?.isMouseOver(mouseX, mouseY) != true) {
            focused = null
        }
        return result
    }

    /** 悬停格子：只在鼠标移动时重算（每帧算一遍没必要，格子是静态的） */
    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        super.mouseMoved(mouseX, mouseY)
        val layout = gridLayout(bgRect())
        hoveredCell = -1
        for (i in 0 until layout.cols * layout.rows) {
            val x = layout.cellX(i)
            val y = layout.cellY(i)
            if (mouseX >= x && mouseX < x + layout.cell && mouseY >= y && mouseY < y + layout.cell) {
                hoveredCell = i
                break
            }
        }
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
    }

    private fun nextPage() {
        if (currentPage >= totalPages) return
        currentPage++
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
    }

    /** 兑换结果（服务端 MarketResultPayload 到达；成功才清空输入框 —— 码已用掉，留着没用） */
    fun onMarketResult(payload: MarketResultPayload) {
        if (payload.success) {
            codeField?.text = ""
            pendingCodeText = ""
        }
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
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        val bg = bgRect()
        // 标题：与其余界面同一套写法（**金色样式挂在 Text 上**，绘制行色仍是白 ——
        // 直接把行色写成金色是错的，那样整行都是金的、与全模组颜色模板不符）
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.market.title").formatted(Formatting.GOLD),
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
            drawNineSlice(
                context, DIALOG_BACKGROUND_TEXTURE,
                layout.cellX(i), layout.cellY(i), layout.cell, layout.cell, 0, DIALOG_BACKGROUND_TEX_H
            )
        }
        if (hoveredCell in 0 until cellCount) {
            // 放大后**仍然居中于原格子**，且只放大绘制、不动别的格子位置
            val grow = ((layout.cell * HOVER_SCALE) - layout.cell).roundToInt() / 2
            drawNineSlice(
                context, DIALOG_BACKGROUND_TEXTURE,
                layout.cellX(hoveredCell) - grow, layout.cellY(hoveredCell) - grow,
                layout.cell + grow * 2, layout.cell + grow * 2, 0, DIALOG_BACKGROUND_TEX_H
            )
        }
    }

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
        const val MIN_REDEEM_FIELD_W = 40

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

        /** 网格与上下两条分割线的间距（逻辑像素） */
        const val GRID_PADDING = 6

        /** 精灵 / 物品 tab：尺寸与两者之间的间隔 */
        const val TAB_W = 56
        const val TAB_H = 16
        const val TAB_GAP = 4

        /** 翻页按钮尺寸（照 MarketScreen 的搬来） */
        const val PAGE_BTN_W = 80
        const val PAGE_BTN_H = 20

        /** 右上角「购买 RP」入口宽度：够放下中文「购买 RP」与英文 "Buy RP" */
        const val BUY_ENTRY_W = 72

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
    }
}
