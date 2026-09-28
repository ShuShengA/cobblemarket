package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PointsConfigPayload
import com.shusheng.cobblemarket.network.PointsTierConfig
import com.shusheng.cobblemarket.network.RequestPointsConfigPayload
import com.shusheng.cobblemarket.network.SavePointsConfigPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import kotlin.math.roundToInt

/**
 * **RP 配置**（服务器配置界面 → 「RP 配置」入口；**仅服主**）。
 *
 * 宗旨（用户 2026-09-18）：**除了服务器货币必须改配置文件，其余都要能在界面里改** ——
 * 开关、档位、收款码、超时全都在这儿，保存即生效、不用重启、不用碰 json。
 *
 * ⚠ 权限**只认服主**，不下放任何权限节点 —— 与界面入口（服务器配置只服主进得去）同口径。
 * 服务端另有一道 `isServerOwner` 校验（`PointsNetwork` 的两个配置包）：
 * 配置里装着**外部签名码密钥**，只靠界面把关挡不住直接发包的 OP（2026-09-25 修正）。
 */
class PointsConfigScreen : Screen(
    Text.translatable("cobblemarket.points.config.title", CurrencyService.pointsTitleIcon())
) {

    // ── 本地编辑态（保存时一次性提交；快照回来时覆盖） ──
    private var pointsEnabled = false

    /** 玩家间 RP 交易开关（默认关）；控制上架界面的货币切换按钮显不显示 */
    private var pointsMarketEnabled = false

    /** 「RP 当任务货币」开关（默认关）：打开后玩家身份的 OP / FTB 任务模组也能发放 RP */
    private var rpAsQuestCurrency = false

    /** RP 的四个费率（%，服务端下发）：「费率配置」二级弹窗填初值，提醒弹窗看它们是否全 0 */
    private var pointsPokemonFee = 0.0
    private var pointsItemFee = 0.0
    private var pointsAuctionFee = 0.0
    private var pointsBuyOrderFee = 0.0


    /** 上面那个提醒弹窗开着没有 */
    private var feeWarn = false

    /** 提醒弹窗的按钮（只在它开着时创建） */
    private val feeWarnButtons = mutableListOf<NineSliceButton>()

    /** 费率配置二级弹窗开着没有 */
    private var feeDialog = false

    /** 主弹窗那一行的「配置」入口按钮 */
    private var feeCfgButton: NineSliceButton? = null

    /** 费率弹窗的四个输入框与两个按钮（只在它开着时创建） */
    private val feeFields = mutableListOf<TextFieldWidget>()
    private val feeDialogButtons = mutableListOf<NineSliceButton>()

    /** 四个费率里有没有非 0 的（提醒弹窗与界面显示共用） */
    private fun anyFee() =
        pointsPokemonFee > 0.0 || pointsItemFee > 0.0 || pointsAuctionFee > 0.0 || pointsBuyOrderFee > 0.0

    /** 开费率弹窗：**先重建再建自己的控件**（顺序同 [openFeeWarn]，反了会被 rebuild 清掉） */
    private fun openFeeDialog() {
        feeDialog = true
        feeFields.clear()
        feeDialogButtons.clear()
        rebuild()
        hideMainControls()

        val dx = editDialogX()
        val initial = listOf(pointsPokemonFee, pointsItemFee, pointsAuctionFee, pointsBuyOrderFee)
        // 坐标照**服务器配置界面**那套：输入框 + 右侧 ↺ 重置按钮（间隙 2px）。
        // ⚠ 行 y 与 renderFeeDialog 里画标签的 y **必须同源**（那边是第一行的 y + 4），
        //   否则标签和输入框会错开半行（2026-09-19 用户实测）
        listOf(
            "cobblemarket.points.config.fee_pokemon",
            "cobblemarket.points.config.fee_item",
            "cobblemarket.points.config.fee_auction",
            "cobblemarket.points.config.fee_buyorder"
        ).forEachIndexed { i, _ ->
            val y = editDialogY() + 30 + i * ROW_H
            val f = TextFieldWidget(textRenderer, dx + 138, y, 54, 16, Text.literal(""))
            f.setMaxLength(6)
            // 只收数字与一个小数点（费率是百分数，允许 2.5 这种）
            f.setTextPredicate { s -> s.count { it == '.' } <= 1 && s.all { it.isDigit() || it == '.' } }
            f.text = if (initial[i] > 0.0) initial[i].toString() else "0"
            addSelectableChild(f); addDrawableChild(f); feeFields += f
            // ↺ 重置：**只填回默认值 0，不提交**（照服务器配置界面 2026-09-05 拍板的两条）
            addDrawableChild(
                NineSliceButton(dx + 194, y, 20, 16, Text.literal("↺"), { f.text = "0" })
                    .also { feeDialogButtons += it }
            )
        }
        val closeY = editDialogY() + EDIT_H - 26
        addDrawableChild(
            NineSliceButton(dx + 22, closeY, 80, 16,
                Text.translatable("cobblemarket.op.scfg_save"), { commitFeeDialog() })
                .also { feeDialogButtons += it }
        )
        addDrawableChild(
            NineSliceButton(dx + EDIT_W - 102, closeY, 80, 16,
                Text.translatable("cobblemarket.op.scfg_cancel"), { closeFeeDialog() })
                .also { feeDialogButtons += it }
        )
    }

    private fun closeFeeDialog() {
        feeDialog = false
        markDirtyAndRebuild()
    }

    /** 保存四个费率：非法/超范围一律钳到 [0,100]（与配置层的 coerceIn 同一口径） */
    private fun commitFeeDialog() {
        val v = feeFields.map { it.text.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 0.0 }
        while (v.size < 4) return  // 防御：四个框没建全就不提交
        pointsPokemonFee = v[0]; pointsItemFee = v[1]; pointsAuctionFee = v[2]; pointsBuyOrderFee = v[3]
        feeDialog = false
        // ⚠ 必须**重建**：只把 feeDialog 置 false 只是不再画弹窗，刚才建的输入框/重置/保存按钮
        //   还留在 children 里 —— 它们会直接浮在主界面上（2026-09-19 用户实测）
        markDirtyAndRebuild()
        save()
    }

    /** 费率弹窗的内容（面板由 [renderBackground] 画好） */
    private fun renderFeeDialog(context: DrawContext) {
        val dx = editDialogX()
        val cx = width / 2
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.config.fee_title").formatted(Formatting.GOLD),
            cx, editDialogY() + 14, 0xFFFFFF
        )
        // ⚠ 标签的 y 必须与 openFeeDialog 里输入框的 y **同一套坐标**（那边第 i 行 = +30+i*ROW_H），
        //   这里写 +34 是让 8px 高的文字正好垂直居中于 16px 的输入框 —— 两边对不上就会错开半行
        var y = editDialogY() + 34
        for (key in listOf(
            "cobblemarket.points.config.fee_pokemon",
            "cobblemarket.points.config.fee_item",
            "cobblemarket.points.config.fee_auction",
            "cobblemarket.points.config.fee_buyorder"
        )) {
            context.drawTextWithShadow(textRenderer, Text.translatable(key), dx + 10, y, 0xFFFFFF)
            y += ROW_H
        }
    }
    private var shopEnabled = false
    private var payQr = ""
    /** 档位券图的**全局默认**文件名（空 = 用内置券图） */
    private var defaultIcon = ""
    private var timeoutMinutes = 15L
    /** 档位金额的货币单位（空 = 不显示）—— 国外服主填 USD/$, 国内填 元 */
    private var currencyLabel = ""
    private val tiers = mutableListOf<PointsTierConfig>()
    private var loaded = false

    // ── 控件 ──
    private val toggleButtons = mutableMapOf<String, NineSliceButton>()
    private var qrField: TextFieldWidget? = null
    private var defaultIconField: TextFieldWidget? = null
    private var timeoutField: TextFieldWidget? = null
    private var currencyField: TextFieldWidget? = null
    private val tierEditButtons = mutableListOf<NineSliceButton>()
    private val tierDeleteButtons = mutableListOf<NineSliceButton>()
    private var addTierButton: NineSliceButton? = null

    /** 「兑换码」入口：与「添加档位」同一行右对齐 */
    private var saveButton: NineSliceButton? = null
    private var cancelButton: NineSliceButton? = null
    private var backButton: NineSliceButton? = null
    /** 左上角 info 图标（与右上角的返回按钮对称）：悬停告诉服主图片目录在哪 */
    private var infoButton: NineSliceButton? = null

    // ── 档位编辑弹窗 ──
    private var editingIndex = -1
    private var editing = false
    /** 保存成功的 toast 截止时刻（1.5 秒） */
    private var savedToastUntil = 0L
    /** 档位编辑弹窗里那一行红字：哪个字段不合法（null = 没错误） */
    private var editError: String? = null
    /** 上一次保存的结果（true = 成功）—— 决定底部提示是绿的还是红的 */
    private var saveResultOk = true

    /** 上一次保存被挡下了（快照还没加载完）—— 底部提示改说这一条 */
    private var saveBlocked = false

    /** 内容区滚到第**几行**（行号，不是像素；见上面「内容区与整块滚动」那段） */
    private var scrollRow = 0
    private var editNameField: TextFieldWidget? = null
    private var editPriceField: TextFieldWidget? = null
    private var editPointsField: TextFieldWidget? = null
    private var editQrField: TextFieldWidget? = null
    /** 该档「RP 券」图文件名（`config/cobblemarket/icons/` 下；留空 = 用内置券图） */
    private var editIconField: TextFieldWidget? = null
    private val editButtons = mutableListOf<NineSliceButton>()

    /** 输入框内容跨重建保留（resize / 弹窗开关都会重建控件） */
    private val pendingText = mutableMapOf<String, String>()

    /**
     * 程序性 setText（服务端回填 / 重建恢复）进行中 —— 此时**不写** [pendingText]。
     *
     * ⚠ MC 的 `TextFieldWidget.setText` **会触发 changedListener**（已核对 1.21.1 字节码：
     * setText 末尾就是 `invokevirtual onChanged`）—— 把回填也算成「玩家编辑」的话，首次 build
     * 写进去的是**初始值**（货币单位 `""`、超时 `15`、收款码 `""`），pendingText 就被它们占住；
     * 而回填读的是 `pendingText[key] ?: 服务端值`，**空串不是 null、`?:` 不兜底** ⇒ 快照到了也
     * 永远显示初始值。
     *
     * 2026-09-18 用户实测：货币单位「保存只能生效一次，下次进来输入框又是空的」；收款码文件名
     * 与超时是同一个坑（超时更阴——服主配了 30 也一直显示 15，**一保存就被改回 15**）。
     * 更严重的是「什么都不改直接保存」会把这些字段清空/覆盖回默认（全量覆盖式提交的表单，
     * 初始值必须是「当前状态」，见 memory `feedback_form_initial_state`）。
     */
    private var programmaticText = false

    /** 回填输入框：优先恢复玩家正在编辑的内容，否则用服务端值。程序性写入不污染 [pendingText] */
    private fun fillField(field: TextFieldWidget?, key: String, serverValue: String) {
        if (field == null) return
        programmaticText = true
        field.text = pendingText[key] ?: serverValue
        programmaticText = false
    }

    private fun dialogW() = 300

    /**
     * 弹窗高度：设计值 [DIALOG_H]，**超过屏幕就压进屏幕内**。
     * ⚠ GUI scale 调大时逻辑分辨率很小（1080p + Auto 可能只有 480×270），定死的高度会顶出
     * 上下边界、连保存按钮都点不到 —— 2026-09-18 用户报的 RP 商城同类问题。
     */
    private fun dialogH() = minOf(DIALOG_H, height - 16)
    private fun dialogX() = width / 2 - dialogW() / 2

    /**
     * 弹窗打开时隐藏主界面**全部**控件。
     *
     * ⚠ **新增常驻控件必须加进这里**（这个界面的「兑换码」入口就是这么漏的 —— 用户实测：
     * 弹窗盖着按钮还是能点。那个入口 2026-09-25 已挪去管理面板，教训留着）。
     * 这个界面原来手写了**三份一模一样的名单**（费率提醒 / 费率编辑 / 档位编辑），三份都没追上。
     * 恢复不用管：关闭弹窗走 `clearChildren() + init()` 全量重建。
     */
    /**
     * 档位行上两个按钮的布局（相对 [rightX]）。
     *
     * ⚠ **只有 [layoutRows] 一处用**（建控件的地方不摆位置，见那里的注释）。
     * 宽度按**英文** "Edit"/"Remove" 留，别照中文两个字缩 —— 英文 "Remove" 要 36px 左右。
     */
    private val TIER_EDIT_DX = -60
    private val TIER_EDIT_W = 32
    private val TIER_DELETE_DX = -24
    private val TIER_DELETE_W = 46

    /** 有弹窗开着吗（档位编辑 / 费率提醒 / 费率编辑）—— 滚动与行可见性都要看它 */
    private fun anyDialogOpen() = editing || feeWarn || feeDialog

    private fun hideMainControls() {
        listOfNotNull(
            backButton, infoButton, addTierButton, saveButton, cancelButton,
            qrField, defaultIconField, timeoutField, currencyField, feeCfgButton
        ).forEach { it.visible = false }
        toggleButtons.values.forEach { it.visible = false }
        tierEditButtons.forEach { it.visible = false }
        tierDeleteButtons.forEach { it.visible = false }
    }
    private fun dialogY() = height / 2 - dialogH() / 2
    private fun listStartY() = dialogY() + 26

    // ── 内容区（8 行设置项 + N 行档位）与整块滚动 ──
    //
    // 照**服务器配置界面**那一套：整块内容**按行滚动**，装不下的行直接藏起来（不画、也点不到）。
    //
    // ⚠ 2026-09-19 用户实测：原来是「8 行设置项固定 + 档位区自己滚」——GUI scale 调成自动后
    //   逻辑高度只剩 ~240，弹窗被压矮而设置项不让位，直接压到档位区与底部按钮上（元素全乱）。
    //   并成一份滚动之后，任何 GUI 尺寸下都不会重叠（设置项装不下就滚，跟服务器配置界面一致）。

    /** 内容区（可滚动部分）的顶边 */
    private fun contentTop() = listStartY()

    /** 内容区可视高度：从内容顶到「添加档位 / 兑换码」那一行上方（底部两行是钉死的，不参与滚动） */
    private fun viewportH() =
        (dialogY() + dialogH() - BOTTOM_BUTTON_BOTTOM - BOTTOM_GAP - TIER_ADD_H - BOTTOM_GAP) - listStartY()

    /** 内容总行数：8 行设置项 + N 行档位 */
    private fun contentRowCount() = FIXED_ROWS + tiers.size

    /** 第 [row] 行的高（设置项 [ROW_H] / 档位行 [TIER_ROW_H]） */
    private fun rowHeightOf(row: Int) = if (row < FIXED_ROWS) ROW_H else TIER_ROW_H

    /**
     * 第 [row] 行在**内容坐标**里的顶边。
     * 设置项与档位行之间留 [TIER_SECTION_H]，那条分割线就画在这段缝里。
     */
    private fun rowTop(row: Int): Int =
        if (row < FIXED_ROWS) row * ROW_H
        else FIXED_ROWS * ROW_H + TIER_SECTION_H + (row - FIXED_ROWS) * TIER_ROW_H

    /**
     * 最多能滚到第几行：从末行往上累加，累到超过可视高就停 ——
     * 这样滚到底时**最后一行贴着可视区底边**，不会滚出一片空白。
     */
    private fun maxScrollRow(): Int {
        var h = 0
        var row = contentRowCount()
        while (row > 0) {
            val prev = row - 1
            val add = rowHeightOf(prev) + (if (prev == FIXED_ROWS) TIER_SECTION_H else 0)
            if (h + add > viewportH()) break
            h += add
            row = prev
        }
        // 极端小屏（一行都装不下）时别让 scrollRow 跑到内容之外 —— 那会一行都画不出来
        return row.coerceIn(0, (contentRowCount() - 1).coerceAtLeast(0))
    }

    /** 第 [row] 行的屏幕 y */
    private fun rowY(row: Int) = contentTop() + rowTop(row) - rowTop(scrollRow)

    /**
     * 这一行**整行**装得下吗。
     * ⚠ 露半截的行一律不画也不给点 —— 否则那半截会糊到下面钉死的按钮上（就是「元素都乱了」的样子）。
     */
    private fun rowFits(row: Int): Boolean {
        val y = rowTop(row) - rowTop(scrollRow)
        return y >= 0 && y + rowHeightOf(row) <= viewportH()
    }

    override fun init() {
        super.init()
        loaded = false
        buildMain()
        if (!configRequested) {
            sendToServer(RequestPointsConfigPayload(true))
            configRequested = true
        } else {
            // 已有本地编辑态（从弹窗返回/重建）：直接按它铺界面
            loaded = true
        }
    }

    private var configRequested = false

    /** 服务端快照到了：填本地态并重建（**不覆盖已有未保存编辑**由 loaded 标志保证） */
    fun onConfig(payload: PointsConfigPayload) {
        pointsEnabled = payload.pointsEnabled
        pointsMarketEnabled = payload.pointsMarketEnabled
        rpAsQuestCurrency = payload.rpAsQuestCurrency
        pointsPokemonFee = payload.pointsPokemonFee
        pointsItemFee = payload.pointsItemFee
        pointsAuctionFee = payload.pointsAuctionFee
        pointsBuyOrderFee = payload.pointsBuyOrderFee
        shopEnabled = payload.shopEnabled
        payQr = payload.payQrImage
        defaultIcon = payload.defaultIcon
        timeoutMinutes = payload.orderTimeoutMinutes
        currencyLabel = payload.currencyLabel
        tiers.clear()
        tiers += payload.tiers
        loaded = true
        rebuild()
    }

    private fun rebuild() {
        // 档位可能变少（删除/快照刷新）：把滚动位置夹回合法范围，否则会停在一片空白上
        scrollRow = scrollRow.coerceIn(0, maxScrollRow())
        clearChildren()
        toggleButtons.clear()
        // 两个档位行按钮名单由 buildMain() 自己清（只有一个入口，见那里的注释）
        editing = false
        buildMain()
    }

    // ── 主界面 ──

    private fun buildMain() {
        // ⚠ **每次重建都必须清这两个名单**：原版 resize 会 `clearChildren()` 清掉屏幕上的控件，
        //   但**不会清我们这份名单** —— 不清就翻倍（旧按钮引用还留在里面），而 [layoutRows]
        //   是**按名单下标**算行号的：新建的按钮会被排到旧按钮占掉的错误行上（用户实测「滚动后错位」）。
        //   ⚠ 放在这里而不是 `init()` / `rebuild()` 各自清：重建入口只有这一个，以后再加路径不会漏。
        //   （`toggleButtons` 是 Map、按 key 覆盖写，不用清）
        tierEditButtons.clear()
        tierDeleteButtons.clear()
        val dx = dialogX()
        val dy = dialogY()
        val rightX = dx + dialogW() - 10 - 22

        backButton = NineSliceButton(
            dx + dialogW() - 58, dy + 8, 50, 16, Text.literal(""),
            { client?.setScreen(ServerConfigScreen()) },
            iconLeft = net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        // 左上角 info 图标（与右上角返回按钮对称，照「全部交易历史」那个写）：
        // 悬停说明图片该放哪 —— 收款码 / 券图两个目录、格式与尺寸限制。
        // 配置界面里塞不下这些字，README 又得先知道目录在哪才看得到，所以放这儿。
        val info = NineSliceButton(
            dx + 8, dy + 8, 16, 16,
            Text.literal(""),
            { },
            iconLeft = net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/info.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f
        )
        info.setTooltip(Tooltip.of(Text.translatable("cobblemarket.points.config.paths_hint")))
        infoButton = info
        addDrawableChild(info)

        // ── 内容区：8 行设置项 + N 行档位 ──
        // ⚠ 这里**只建控件**（给个占位坐标），真正的 x / y / visible 一律由 [layoutRows] 按行号摆 ——
        //   建的写一套坐标、摆的另写一套，就是「一滚动位置就错」的根源（今天已经踩过）。
        fun addToggle(key: String, value: Boolean, onClick: () -> Unit) {
            // onPress 是 (ButtonWidget) -> Unit，包一层丢掉那个参数
            val btn = NineSliceButton(rightX, listStartY(), 22, 18, Text.literal(""), { onClick() })
            btn.iconLeft = if (value) ON_ICON else OFF_ICON
            toggleButtons[key] = btn
            addDrawableChild(btn)
        }
        addToggle("points", pointsEnabled) { pointsEnabled = !pointsEnabled; markDirtyAndRebuild() }
        addToggle("points_market", pointsMarketEnabled) { onPointsMarketToggle() }
        // ⚠ 「RP 当任务货币」开关（`rpAsQuestCurrency`）**尚未上界面** —— 它要多占一行设置项，
        //   而本弹窗高度被屏幕钳死、加一行就少显示一行档位（见 [DIALOG_H] 的算式）。
        //   取舍待用户拍板，见 docs/points_plan.md。**后端已可用**（手编配置文件即可开）。
        // RP 四个费率的入口：右侧一个「配置」按钮开二级弹窗 —— 四个费率直接摊在主弹窗里放不下
        feeCfgButton = NineSliceButton(
            rightX - 18, listStartY(), 40, 18,
            Text.translatable("cobblemarket.points.config.fee_cfg_btn"), { openFeeDialog() }
        )
        addDrawableChild(feeCfgButton)
        addToggle("shop", shopEnabled) { shopEnabled = !shopEnabled; markDirtyAndRebuild() }

        // 收款码文件名（全局码；每个档位还可以在编辑弹窗里配自己的）
        qrField = TextFieldWidget(textRenderer, rightX - 130, listStartY(), 152, 16, Text.literal(""))
        qrField?.setMaxLength(64)
        qrField?.setTextPredicate { it.none { c -> c == '/' || c == '\\' } }
        // 留空是什么意思，只有 placeholder 说得清（标签栏 62px 塞不下，info 图标太远）
        qrField?.setPlaceholder(Text.translatable("cobblemarket.points.config.qr_hint"))
        qrField?.setChangedListener { if (!programmaticText) pendingText["qr"] = it }
        fillField(qrField, "qr", payQr)
        addSelectableChild(qrField); addDrawableChild(qrField)

        // 默认券图（档位「RP 券」的全局默认；放 config/cobblemarket/icons/ 下，留空 = 用内置券图）
        defaultIconField = TextFieldWidget(textRenderer, rightX - 130, listStartY(), 152, 16, Text.literal(""))
        defaultIconField?.setMaxLength(64)
        defaultIconField?.setTextPredicate { it.none { c -> c == '/' || c == '\\' } }
        defaultIconField?.setPlaceholder(Text.translatable("cobblemarket.points.config.icon_hint"))
        defaultIconField?.setChangedListener { if (!programmaticText) pendingText["defaultIcon"] = it }
        fillField(defaultIconField, "defaultIcon", defaultIcon)
        addSelectableChild(defaultIconField); addDrawableChild(defaultIconField)

        // 待支付超时（分钟）
        timeoutField = TextFieldWidget(textRenderer, rightX - 60, listStartY(), 82, 16, Text.literal(""))
        timeoutField?.setMaxLength(5)
        timeoutField?.setTextPredicate { it.all(Char::isDigit) }
        timeoutField?.setChangedListener { if (!programmaticText) pendingText["timeout"] = it }
        fillField(timeoutField, "timeout", timeoutMinutes.toString())
        addSelectableChild(timeoutField); addDrawableChild(timeoutField)

        // 货币单位（纯展示，服主自己填「元」/「USD」；留空 = 金额不带单位）
        currencyField = TextFieldWidget(textRenderer, rightX - 60, listStartY(), 82, 16, Text.literal(""))
        currencyField?.setMaxLength(16)
        // 这个框只有 82px 宽，placeholder 得短（超了会画到框外）
        currencyField?.setPlaceholder(Text.translatable("cobblemarket.points.config.currency_hint"))
        currencyField?.setChangedListener { if (!programmaticText) pendingText["currency"] = it }
        fillField(currencyField, "currency", currencyLabel)
        addSelectableChild(currencyField); addDrawableChild(currencyField)

        // 档位行：名称 / 价格 / 点数 + 编辑 / 删除（每档一行，行号 = FIXED_ROWS + 下标）
        tiers.forEachIndexed { index, tier ->
            // 宽度按**英文**留：原来的 Edit 26 / Remove 22 是照中文两个字定的，
            // 英文 "Remove" 要 36px 左右，22px 的按钮直接溢出（用户 2026-09-19 实测）。
            // 两个按钮**相邻**（间距 4，原来空了 28）、整体右缘不变（只左移 6px，不挤左边的列）
            val edit = NineSliceButton(
                rightX + TIER_EDIT_DX, listStartY(), TIER_EDIT_W, TIER_BTN_H,
                Text.translatable("cobblemarket.price_limit.edit"),
                { openTierDialog(index) }
            )
            val delete = NineSliceButton(
                rightX + TIER_DELETE_DX, listStartY(), TIER_DELETE_W, TIER_BTN_H,
                Text.translatable("cobblemarket.price_limit.remove"),
                { tiers.removeAt(index); markDirtyAndRebuild() }
            )
            tierEditButtons += edit
            tierDeleteButtons += delete
            addDrawableChild(edit)
            addDrawableChild(delete)
        }
        // 建完统一按当前滚动位置摆一遍（含可见性）
        layoutRows()

        // 「添加档位 / 兑换码」：**紧贴在保存取消那一行的上面**（用户 2026-09-19 定）。
        //
        // ⚠ 这里原来还有个「档位不满一屏就上浮贴住列表」的分支 —— 那是当天早些时候为
        //   反过来的问题加的，结果把空隙搬到了这一行与保存取消之间（用户看到的正是那截空隙）。
        //   现在**一律钉死在保存取消正上方**，两行之间只留 [BOTTOM_GAP]。
        val tierAddY = dialogY() + dialogH() - BOTTOM_BUTTON_BOTTOM - BOTTOM_GAP - TIER_ADD_H
        addTierButton = NineSliceButton(
            dx + 10, tierAddY, 70, TIER_ADD_H,
            Text.translatable("cobblemarket.points.config.add_tier"), { openTierDialog(-1) }
        )
        addDrawableChild(addTierButton)

        // ⚠ 兑换码管理入口**已挪走**（2026-09-25 用户拍板 → 管理面板的图标按钮行，判 `points.code` 节点）：
        //   原先放在这里时，只有服主进得来本界面 ⇒ 被授予节点的普通管理员在界面上**没有落点**，只有命令能用。
        //   挪走后 `PointsCodeScreen` 的返回按钮也改回管理面板，别再从这里入口（见该文件 init）。

        // 底部双按钮：**照服务器配置界面那一对**（位置、尺寸、间距都一致 —— 同类型界面要能对上）
        saveButton = NineSliceButton(
            width / 2 - 62, dialogY() + dialogH() - 26, 60, 20,
            Text.translatable("cobblemarket.op.scfg_save"),
            { save() }
        )
        // 快照没到就置灰（点了仍会给提示，见 save()）—— 这时候保存会把配置清空
        saveButton?.dimmed = !loaded
        addDrawableChild(saveButton)
        cancelButton = NineSliceButton(
            width / 2 + 2, dialogY() + dialogH() - 26, 60, 20,
            Text.translatable("cobblemarket.op.scfg_cancel"),
            { client?.setScreen(ServerConfigScreen()) }
        )
        addDrawableChild(cancelButton)
    }

    private fun markDirtyAndRebuild() {
        rebuild()
    }

    /**
     * 点「玩家间交易」开关。
     *
     * ⚠ **只在「要打开」且 RP 费率非 0 时**先弹一次提醒：这个组合意味着玩家上架会被
     * 从余额里抽成，而 RP 若是真钱充值的，那抽的就是真钱。默认费率是 0，所以正常服
     * 一辈子见不到这个弹窗；只有**自己配过非 0 费率的服**才会被拦一下 ——
     * 那种服多半是早先把 RP 当纯游戏币配的，后来改成充值货币时想不起配置文件里还有个费率。
     */
    private fun onPointsMarketToggle() {
        if (!pointsMarketEnabled && anyFee()) {
            openFeeWarn()
            return
        }
        pointsMarketEnabled = !pointsMarketEnabled
        markDirtyAndRebuild()
    }

    /** 开提醒弹窗：**先重建**（清掉旧控件）**再建自己的两个按钮**，顺序不能反 */
    private fun openFeeWarn() {
        feeWarn = true
        feeWarnButtons.clear()
        rebuild()
        // **弹窗打开时下层控件全部隐藏**（全模组弹窗惯例：不隐藏的话它们会透过遮罩显形、还能被点到）。
        // ⚠ 顺序不能反：必须放在 rebuild() **之后** —— rebuild 会把下层控件重新建出来且 visible=true
        // ⚠ 这是一份**手写列表**：界面新增控件时三处都要追（这里、openFeeDialog、mouseClicked 的失焦名单）
        hideMainControls()
        val cx = width / 2
        val by = editDialogY() + EDIT_H - 26
        addDrawableChild(
            NineSliceButton(
                cx - 84, by, 80, 16,
                Text.translatable("cobblemarket.points.config.fee_warn_proceed"), { closeFeeWarn(true) }
            ).also { feeWarnButtons += it }
        )
        addDrawableChild(
            NineSliceButton(
                cx + 4, by, 80, 16,
                Text.translatable("cobblemarket.op.scfg_cancel"), { closeFeeWarn(false) }
            ).also { feeWarnButtons += it }
        )
    }

    /** 提醒弹窗：仍然开启（放行）/ 回去改（关弹窗、开关保持不动） */
    private fun closeFeeWarn(proceed: Boolean) {
        feeWarn = false
        if (proceed) pointsMarketEnabled = true
        markDirtyAndRebuild()
    }

    /**
     * 档位列表滚轮翻行（只有档位多到放不下时才有得滚）。
     *
     * 只挪按钮、**不重建界面** —— 重建会清掉玩家正在敲的输入框焦点（内容靠 pendingText 保得住，
     * 但焦点与光标位置会丢，滚一下就把人家正在编辑的框踢出输入态很难受）。
     */
    override fun mouseScrolled(
        mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double
    ): Boolean {
        if (anyDialogOpen() || maxScrollRow() <= 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }
        val target = (scrollRow - verticalAmount.toInt()).coerceIn(0, maxScrollRow())
        if (target == scrollRow) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }
        scrollRow = target
        layoutRows()
        return true
    }

    /**
     * 右侧滚动条：内容装不下才画（2px 宽，轨道半透明、滑块不透明 —— 与服务器配置界面同一套）。
     * 轨道 = 内容可视区；滑块高按「可视高 / 内容总高」的比例。
     */
    private fun renderScrollbar(context: DrawContext) {
        val maxScroll = maxScrollRow()
        if (maxScroll <= 0) return
        val x = dialogX() + dialogW() - 5
        val top = contentTop()
        val trackH = viewportH()
        val hidden = rowTop(contentRowCount()) - viewportH()
        val thumbH = maxOf(16, trackH * trackH / (trackH + hidden))
        val thumbY = top + (trackH - thumbH) * scrollRow / maxScroll
        context.fill(x, top, x + 2, top + trackH, 0x40FFFFFF)
        context.fill(x, thumbY, x + 2, thumbY + thumbH, 0xC0FFFFFF.toInt())
    }

    /**
     * 按当前 [scrollRow] 摆放**所有内容行**的控件（8 行设置项 + N 行档位）。
     *
     * ⚠ `visible` 必须叠加 [anyDialogOpen]：弹窗开着时任何重建（滚动 / resize）都不能把下层控件
     *   改回可见（照服务器配置界面 `!eggConfirmOpen &&` 的写法）。
     * ⚠ 行号 → 控件的对应关系是**唯一一份**：建控件的地方（[buildMain]）不自己算坐标，
     *   行文字与标签（render）也走 [rowY] / [rowFits] —— 三处共用同一套行坐标。
     */
    private fun layoutRows() {
        scrollRow = scrollRow.coerceIn(0, maxScrollRow())
        val rightX = dialogX() + dialogW() - 10 - 22

        fun place(row: Int, widget: ClickableWidget?, x: Int, yOff: Int) {
            if (widget == null) return
            val fits = rowFits(row) && !anyDialogOpen()
            widget.visible = fits
            if (fits) widget.setPosition(x, rowY(row) + yOff)
        }
        // 设置项 8 行（行号见 ROW_* 常量；与 render 里的标签顺序一一对应）
        place(ROW_POINTS, toggleButtons["points"], rightX, 0)
        place(ROW_POINTS_MARKET, toggleButtons["points_market"], rightX, 0)
        place(ROW_FEE, feeCfgButton, rightX - 18, 0)
        place(ROW_SHOP, toggleButtons["shop"], rightX, 0)
        place(ROW_QR, qrField, rightX - 130, 2)
        place(ROW_ICON, defaultIconField, rightX - 130, 2)
        place(ROW_TIMEOUT, timeoutField, rightX - 60, 2)
        place(ROW_CURRENCY, currencyField, rightX - 60, 2)
        // 档位行：行号 = FIXED_ROWS + 下标（按钮比行矮，垂直居中 → +2）
        tierEditButtons.forEachIndexed { index, btn ->
            place(FIXED_ROWS + index, btn, rightX + TIER_EDIT_DX, (TIER_ROW_H - TIER_BTN_H) / 2)
        }
        tierDeleteButtons.forEachIndexed { index, btn ->
            place(FIXED_ROWS + index, btn, rightX + TIER_DELETE_DX, (TIER_ROW_H - TIER_BTN_H) / 2)
        }
    }

    // ── 档位编辑弹窗 ──

    private fun openTierDialog(index: Int) {
        editingIndex = index
        editing = true
        editError = null
        val existing = tiers.getOrNull(index)
        pendingText["tier_name"] = existing?.name ?: ""
        pendingText["tier_price"] = existing?.let { trimPrice(it.priceCents) } ?: ""
        pendingText["tier_points"] = existing?.points?.toString() ?: ""
        pendingText["tier_qr"] = existing?.qr ?: ""
        pendingText["tier_icon"] = existing?.icon ?: ""
        // 清掉下层控件的焦点，防 E 键保护误判（照各弹窗惯例）
        focused = null
        buildEditDialog()
    }

    private fun buildEditDialog() {
        // 弹窗打开时下层控件全部隐藏（走统一入口，见 hideMainControls 的注释：
        // 这里原来是手写的第三份名单，且**漏了 feeCfgButton** —— 用户实测「费率配置按钮透过来」）
        hideMainControls()

        val dx = editDialogX()
        val fieldX = dx + 70
        var y = editDialogY() + 26

        editNameField = TextFieldWidget(textRenderer, fieldX, y, EDIT_W - 78, 16, Text.literal(""))
        editNameField?.setMaxLength(32)
        editNameField?.setChangedListener { pendingText["tier_name"] = it }
        editNameField?.text = pendingText["tier_name"] ?: ""
        addSelectableChild(editNameField); addDrawableChild(editNameField); y += ROW_H

        editPriceField = TextFieldWidget(textRenderer, fieldX, y, EDIT_W - 78, 16, Text.literal(""))
        editPriceField?.setMaxLength(10)
        editPriceField?.setTextPredicate { it.all { c -> c.isDigit() || c == '.' } }
        editPriceField?.setChangedListener { pendingText["tier_price"] = it }
        editPriceField?.text = pendingText["tier_price"] ?: ""
        addSelectableChild(editPriceField); addDrawableChild(editPriceField); y += ROW_H

        editPointsField = TextFieldWidget(textRenderer, fieldX, y, EDIT_W - 78, 16, Text.literal(""))
        editPointsField?.setMaxLength(12)
        editPointsField?.setTextPredicate { it.all(Char::isDigit) }
        editPointsField?.setChangedListener { pendingText["tier_points"] = it }
        editPointsField?.text = pendingText["tier_points"] ?: ""
        addSelectableChild(editPointsField); addDrawableChild(editPointsField); y += ROW_H

        editQrField = TextFieldWidget(textRenderer, fieldX, y, EDIT_W - 78, 16, Text.literal(""))
        editQrField?.setMaxLength(64)
        editQrField?.setTextPredicate { it.none { c -> c == '/' || c == '\\' } }
        editQrField?.setPlaceholder(Text.translatable("cobblemarket.points.config.tier_qr_hint"))
        editQrField?.setChangedListener { pendingText["tier_qr"] = it }
        editQrField?.text = pendingText["tier_qr"] ?: ""
        addSelectableChild(editQrField); addDrawableChild(editQrField); y += ROW_H

        // RP 券图文件名（放 config/cobblemarket/icons/ 下；留空 = 用内置券图）
        editIconField = TextFieldWidget(textRenderer, fieldX, y, EDIT_W - 78, 16, Text.literal(""))
        editIconField?.setMaxLength(64)
        editIconField?.setTextPredicate { it.none { c -> c == '/' || c == '\\' } }
        editIconField?.setPlaceholder(Text.translatable("cobblemarket.points.config.tier_icon_hint"))
        editIconField?.setChangedListener { pendingText["tier_icon"] = it }
        editIconField?.text = pendingText["tier_icon"] ?: ""
        addSelectableChild(editIconField); addDrawableChild(editIconField); y += ROW_H + 16 // 多留一行给错误提示

        addDrawableChild(
            NineSliceButton(dx + 22, y, 80, 16, Text.translatable("cobblemarket.op.scfg_save"), { commitTier() })
                .also { editButtons += it }
        )
        addDrawableChild(
            NineSliceButton(dx + EDIT_W - 102, y, 80, 16, Text.translatable("cobblemarket.op.scfg_cancel"), { closeTierDialog() })
                .also { editButtons += it }
        )
    }

    private fun commitTier() {
        // ⚠ 逐个字段判断并**记下是哪一个不合格**：原来是一串 || 混在一起、失败了只播一声 fail
        //   音，服主完全不知道哪项错了（2026-09-18 用户实测："一直是 fail 音效，也没有反馈"）
        val name = (pendingText["tier_name"] ?: "").trim()
        val price = (pendingText["tier_price"] ?: "").toDoubleOrNull()
        val points = (pendingText["tier_points"] ?: "").toLongOrNull()
        val qr = (pendingText["tier_qr"] ?: "").trim()
        val errorKey = when {
            name.isEmpty() -> "cobblemarket.points.config.err_name"
            price == null || price <= 0.0 -> "cobblemarket.points.config.err_price"
            points == null || points <= 0L -> "cobblemarket.points.config.err_points"
            else -> null
        }
        if (errorKey != null) {
            editError = errorKey
            // 诊断日志：连原始串一起打，省得下次还得靠猜（pendingText 与解析结果都记）
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.warn(
                "Shop tier rejected: name='{}' priceRaw='{}' pointsRaw='{}' qr='{}' → name={} price={} points={}",
                pendingText["tier_name"], pendingText["tier_price"], pendingText["tier_points"],
                pendingText["tier_qr"], name, price, points
            )
            com.shusheng.cobblemarket.client.playFailSound()
            return
        }
        val entry = PointsTierConfig(
            name = name,
            priceCents = (price!! * 100).roundToInt().toLong(),
            points = points!!,
            qr = qr,
            icon = (pendingText["tier_icon"] ?: "").trim()
        )
        if (editingIndex in tiers.indices) tiers[editingIndex] = entry else tiers += entry
        closeTierDialog()
    }

    private fun closeTierDialog() {
        editing = false
        focused = null
        rebuild()
    }

    /** 元 → 展示串（去掉多余的 .00） */
    private fun trimPrice(cents: Long): String =
        if (cents % 100L == 0L) (cents / 100L).toString() else "%.2f".format(cents / 100.0)

    /** 保存：提交后**留在界面**并显示 1.5 秒提示（照服务器配置界面；直接关掉会让服主以为没保存） */
    private fun save() {
        // ⚠ **服务端快照还没到就绝不保存**：保存是**全量覆盖**，而这时本地态还是空的 ——
        //   一按下去档位、收款码、券图、货币单位会被整体清空写进配置，且无法追回。
        //   2026-09-19 用户实测踩到（档位就是这么没的：配置文件里变成 "tiers": []）。
        if (!loaded) {
            saveBlocked = true
            savedToastUntil = System.currentTimeMillis() + 3000
            return
        }
        saveBlocked = false
        val timeout = (pendingText["timeout"] ?: timeoutMinutes.toString()).toLongOrNull()?.coerceIn(1L, 1440L) ?: 15L
        sendToServer(
            SavePointsConfigPayload(
                pointsEnabled = pointsEnabled,
                shopEnabled = shopEnabled,
                payQrImage = pendingText["qr"] ?: payQr,
                defaultIcon = pendingText["defaultIcon"] ?: defaultIcon,
                orderTimeoutMinutes = timeout,
                tiers = tiers.toList(),
                currencyLabel = pendingText["currency"] ?: currencyLabel,
                pointsMarketEnabled = pointsMarketEnabled,
                rpAsQuestCurrency = rpAsQuestCurrency,
                pointsPokemonFee = pointsPokemonFee,
                pointsItemFee = pointsItemFee,
                pointsAuctionFee = pointsAuctionFee,
                pointsBuyOrderFee = pointsBuyOrderFee
            )
        )
    }

    /**
     * 保存结果（服务端落盘并广播后回发）：在弹窗底部显示一行提示。
     *
     * ⚠ **成功与失败都要显示在界面里**：失败时（配置存了但收款码读不出来）原来只在聊天栏说，
     * 服主看到的就是「一声 fail 音 + 没有任何反馈」，根本不知道发生了什么（2026-09-18 实测）。
     * 详细内容（是哪个文件）消息太长放不下，仍以聊天栏那条为准。
     */
    fun onMarketResult(payload: MarketResultPayload) {
        saveResultOk = payload.success
        savedToastUntil = System.currentTimeMillis() + (if (payload.success) 1500 else 4000)
        // ⚠ 失败时**必须把详细内容发到聊天栏**：界面那行只放得下「有图片读不出来」，
        //   究竟是哪一张、错在哪都在 message 里 —— 而上面那行提示写的就是「详见聊天栏」。
        //   不发的话服主只能看到一个指不到任何地方的指引（2026-09-18 用户实测）。
        //   成功时不发：界面那行绿字已经把话说完了，再发一遍是刷屏。
        if (!payload.success) {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.RED), false)
        }
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        drawScreenDimMask(context, width, height)
        drawNineSlice(
            context, DIALOG_BACKGROUND_TEXTURE, dialogX(), dialogY(), dialogW(), dialogH(), 0,
            DIALOG_BACKGROUND_TEX_H
        )
        // 档位编辑弹窗的背景必须画在**控件之前**（放在 render 里会盖住它自己的输入框）
        if (editing || feeWarn || feeDialog) {
            drawNineSlice(
                context, DIALOG_BACKGROUND_TEXTURE,
                editDialogX(), editDialogY(), EDIT_W, EDIT_H, 0, DIALOG_BACKGROUND_TEX_H
            )
        }
    }

    /**
     * 费率提醒弹窗的内容（面板与遮罩已由 [renderBackground] 画好）。
     * 三行正文**手动拆行**而不是靠自动换行 —— 弹窗宽度固定，长句必须自己断在标点处。
     */
    private fun renderFeeWarn(context: DrawContext) {
        val cx = width / 2
        val top = editDialogY()
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.config.fee_warn_title").formatted(Formatting.GOLD),
            cx, top + 16, 0xFFFFFF
        )
        // ⚠ 正文里的货币单位一律用**图标**：RP 只是内部代号，玩家侧界面不该出现这两个字母
        var y = top + 42
        for (key in listOf(
            "cobblemarket.points.config.fee_warn_body1",
            "cobblemarket.points.config.fee_warn_body2"
        )) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(key, CurrencyService.pointsIcon()),
                cx, y, 0xFFFFFF
            )
            y += 13
        }
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.config.fee_warn_body3"),
            cx, y, 0xFFFFFF
        )
    }

    // 档位编辑弹窗：**屏幕居中**（照全模组弹窗惯例）。
    // 之前写成"相对主弹窗偏移"是错的 —— 弹出的二级弹窗一律居中，不跟着父窗口挪
    private fun editDialogX() = width / 2 - EDIT_W / 2
    private fun editDialogY() = height / 2 - EDIT_H / 2

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        // ⚠ 编辑弹窗打开时**下层文字一律不画**：遮罩画在 renderBackground（子控件之前），
        // 而这些文字画在 render（子控件之后）—— 照画的话它们会浮在遮罩之上，
        // 看起来就是"下层文字透到弹窗上"（2026-09-18 用户实测）。
        if (editing) {
            renderEditDialog(context)
            return
        }
        if (feeWarn) {
            renderFeeWarn(context)
            return
        }
        if (feeDialog) {
            renderFeeDialog(context)
            return
        }
        val dx = dialogX()
        val centerX = width / 2
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.config.title", CurrencyService.pointsTitleIcon())
                .formatted(Formatting.GOLD),
            centerX, dialogY() + 14, 0xFFFFFF
        )

        // 设置项标签：**行号与 layoutRows 里的 ROW_* 一一对应**（前 3 行是 RP 相关，标签带 RP 图标）。
        // 装不下的行一律不画（跟控件同一套可见性判据）。
        val settingLabels = listOf(
            ROW_POINTS to "cobblemarket.points.config.points_enabled",
            ROW_POINTS_MARKET to "cobblemarket.points.config.points_market",
            ROW_FEE to "cobblemarket.points.config.points_fee",
            ROW_SHOP to "cobblemarket.points.config.shop_enabled",
            ROW_QR to "cobblemarket.points.config.pay_qr",
            ROW_ICON to "cobblemarket.points.config.default_icon",
            ROW_TIMEOUT to "cobblemarket.points.config.timeout",
            ROW_CURRENCY to "cobblemarket.points.config.currency_label"
        )
        settingLabels.forEach { (row, key) ->
            if (!rowFits(row)) return@forEach
            val text =
                if (row <= ROW_FEE) Text.translatable(key, CurrencyService.pointsIcon())
                else Text.translatable(key)
            context.drawTextWithShadow(textRenderer, text, dx + 10, rowY(row) + 7, 0xFFFFFF)
        }
        // 档位行：名称 / 价格 / 点数（带 `*` = 该档配了专用收款码，文件名在编辑弹窗里看）。
        // 行号 = FIXED_ROWS + 下标；装不下的行不画。
        tiers.forEachIndexed { index, tier ->
            val row = FIXED_ROWS + index
            if (!rowFits(row)) return@forEachIndexed
            // 配了专用收款码的档位挂个 [码] 标记（走词条，英文服显示 [QR]）。
            // ⚠ 原来这里是个裸 `*`，没有任何解释 —— 服主自己都看不出它是什么意思（2026-09-19 用户问的）
            val name = if (tier.qr.isBlank()) tier.name
            else tier.name + " " + Text.translatable("cobblemarket.points.config.tier_qr_mark").string
            // 价格要带**服主自己填的货币单位**（上面那个输入框；留空就只给数字）——
            // 与充值界面、订单列表同一个口径，漏了的话这里只剩一个裸数字「5」，
            // 和后面那个点数「500」连在一起看，根本分不清哪个是钱、哪个是 RP。
            val price = if (currencyLabel.isBlank()) trimPrice(tier.priceCents)
            else "${trimPrice(tier.priceCents)} $currencyLabel"
            context.drawTextWithShadow(
                textRenderer,
                Text.literal("$name  $price  ")
                    // 点数（发多少 RP）走金额配色：数字金色 + 图标原色。
                    // ⚠ 别整行都用 drawTextWithShadow 的白 —— 那样名字和数字糊成一片
                    .append(Text.literal("${tier.points} ").formatted(Formatting.GOLD))
                    .append(CurrencyService.pointsIcon()),
                dx + 10, rowY(row) + (TIER_ROW_H - 8) / 2, 0xFFFFFF
            )
        }

        // 档位滚动区**上下各一条分隔线**，把它和上面的固定项、下面的「添加档位」按钮分开
        //（灰色 1px、左右各内缩 6px —— 与服务器配置界面列表里的行线同款）
        val lineL = dx + 6
        val lineR = dx + dialogW() - 6
        // 分割线 ①：设置项与档位行之间那条 —— **跟着内容滚**（它就画在两段之间的缝里），
        // 滚出可视区就不画（否则会糊到底部钉死的按钮上）。
        val sectionLineY = rowY(FIXED_ROWS) - TIER_SECTION_H
        if (sectionLineY >= contentTop() && sectionLineY < contentTop() + viewportH()) {
            context.fill(lineL, sectionLineY, lineR, sectionLineY + 1, 0xFF555555.toInt())
        }
        // 分割线 ②：内容区与底部两行按钮之间的那条 —— **钉死在可视区底边**，不跟内容滚
        val bottomLineY = contentTop() + viewportH()
        context.fill(lineL, bottomLineY, lineR, bottomLineY + 1, 0xFF555555.toInt())
        renderScrollbar(context)

        // 保存结果提示（弹窗底部居中）：成功绿、失败红（失败也要说，别只播一声 fail 音）；
        // 「配置还没加载完就点保存」单独一条红字。
        // ⚠ 位置：**内容区底边上方 10px**（那段是底部两行按钮区，放不下字）——
        //   2026-09-19 收紧底部留白后，原来那个 `dialogH − 38` 正好压在「添加档位」按钮上。
        if (System.currentTimeMillis() < savedToastUntil) {
            val y = dialogY() + dialogH() - BOTTOM_BUTTON_BOTTOM - BOTTOM_GAP - TIER_ADD_H - BOTTOM_GAP - 10
            val text = when {
                saveBlocked -> Text.translatable("cobblemarket.points.config.not_loaded").formatted(Formatting.RED)
                saveResultOk ->
                    Text.translatable("cobblemarket.points.config.saved", CurrencyService.pointsIcon())
                        .formatted(Formatting.GREEN)

                else -> Text.translatable("cobblemarket.points.config.qr_problem_short").formatted(Formatting.RED)
            }
            context.drawCenteredTextWithShadow(textRenderer, text, centerX, y, 0xFFFFFF)
        }
    }

    private fun renderEditDialog(context: DrawContext) {
        val dx = editDialogX()
        var y = editDialogY() + 8
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable(
                if (editingIndex in tiers.indices) "cobblemarket.price_limit.edit"
                else "cobblemarket.points.config.add_tier"
            ).formatted(Formatting.GOLD),
            dx + EDIT_W / 2, y, 0xFFFFFF
        )
        y += 16
        /** 档位编辑弹窗里的行；[arg] 同上 */
        fun line(key: String, arg: Text? = null) {
            val text = if (arg == null) Text.translatable(key) else Text.translatable(key, arg)
            context.drawTextWithShadow(textRenderer, text, dx + 8, y + 5, 0xFFFFFF)
            y += ROW_H
        }
        line("cobblemarket.points.config.tier_name")
        line("cobblemarket.points.config.tier_price")
        line("cobblemarket.points.config.tier_points")
        line("cobblemarket.points.config.tier_qr")
        line("cobblemarket.points.config.tier_icon", CurrencyService.pointsIcon())
        // 哪个字段不合法就直说 —— 只播一声 fail 音服主根本不知道该改哪（2026-09-18 用户反馈）
        editError?.let { key ->
            context.drawCenteredTextWithShadow(
                textRenderer, Text.translatable(key), dx + EDIT_W / 2, y, 0xFF5555
            )
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // ⚠ 点击空白失焦（含输入框的界面必做）：漏了点空白退不出输入态、E 键会被输入框吃掉
        // ⚠ **新增输入框务必加进这个列表** —— 它是一份手写的白名单，漏了就不参与失焦
        //   （费率弹窗那四个就是这么漏过一次，2026-09-19 用户实测）
        val fields = listOfNotNull(
            qrField, defaultIconField, timeoutField, currencyField,
            editNameField, editPriceField, editPointsField, editQrField, editIconField
        ) + feeFields
        val wasInInput = fields.any { it === focused }
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && fields.none { it.isMouseOver(mouseX, mouseY) }) focused = null
        return result
    }

    override fun shouldPause() = false

    private companion object {
        const val ROW_H = 20

        /**
         * **档位行高**（比固定项的 [ROW_H] 矮 2px）。
         *
         * 2026-09-19 用户要求「档位区多显示一行」：弹窗高度已经被屏幕钳死、没得加，所以只能
         * ①把各处留白收紧（见 [DIALOG_H] 的算式）②把档位行从 20 收到 18 ——
         * 两者合起来正好凑够一整行（用户实测的屏幕上 3 行 → 4 行）。
         * 档位行里只有 8px 文字 + [TIER_BTN_H] 高的按钮，18px 装得下。
         */
        const val TIER_ROW_H = 18

        /** 档位行里两个按钮（编辑 / 删除）的高 —— `TIER_ROW_H − TIER_BTN_H = 4`，刚好满足按钮间距规则 */
        const val TIER_BTN_H = 14
        /** 档位编辑弹窗尺寸（5 行输入 + 标题 + 按钮） */
        const val EDIT_W = 220
        const val EDIT_H = 182

        /**
         * 主弹窗的设计高度；实际会被屏幕高度钳制（见 [dialogH]）。
         *
         * ⚠ **改它之前先重算内容可视区高度**，否则会白空一条或挤掉一行：
         * ```
         * 可视区高 = DIALOG_H − (BOTTOM_BUTTON_BOTTOM + GAP + TIER_ADD_H + GAP)   ← 底部两行（钉死）
         *                    − 26                                                ← 标题区
         *          = DIALOG_H − 48 − 26 = DIALOG_H − 74
         *          = 368 − 74 = 294px
         * ```
         * 可视区里依次排：8 行设置项（8×20=160）+ [TIER_SECTION_H] + N 行档位（N×[TIER_ROW_H]）。
         * 弹窗没被钳短时 294 − 162 = 132 ÷ 18 = **7 行档位**同时可见；用户屏幕钳到 308 时
         * `234 − 162 = 72 ÷ 18 = 4 行`（改这个式子的任何一项都可能让它掉回 3 行）。
         * ⚠ **FIXED_ROWS 每 +1（加一行设置项）就少 20px**，加设置项前先按上式算一遍。
         * ⚠ 内容装不下**不会重叠**：装不下的行直接藏起来、可滚动（2026-09-19 改成整块滚动）。
         */
        const val DIALOG_H = 368

        /**
         * 设置项各行的**行号**（0 起）—— **唯一一份**：
         * [layoutRows] 按它摆控件、render 按它画标签，加/删设置项时改这里 + 那一处列表即可。
         */
        const val ROW_POINTS = 0
        const val ROW_POINTS_MARKET = 1
        const val ROW_FEE = 2
        const val ROW_SHOP = 3
        const val ROW_QR = 4
        const val ROW_ICON = 5
        const val ROW_TIMEOUT = 6
        const val ROW_CURRENCY = 7

        /** 设置项占的行数（总开关 / 玩家间交易 / 费率配置 / 充值开关 / 收款码 / 券图 / 超时 / 货币单位） */
        const val FIXED_ROWS = ROW_CURRENCY + 1

        /**
         * 设置项与档位行之间留的缝（那条 1px 分割线画在这段缝里）。
         *
         * ⚠ **只能给 2**：这段缝是**内容**的一部分，给多了就会把可视区里的一整行档位挤掉
         *   （用户屏幕上 4 行 → 3 行只差 2px），要加宽得同步加大 [DIALOG_H]。
         */
        const val TIER_SECTION_H = 2

        /** 底部双按钮的高度、以及它离弹窗底边的距离（24 = 按钮 20 + 下边距 4） */
        const val BOTTOM_BUTTON_BOTTOM = 24

        /** 「添加档位」按钮高度 */
        const val TIER_ADD_H = 16

        /**
         * 相邻控件之间的最小空隙（按钮间距规则是 4，这里就取 4 —— 留白每多 1px 都可能少一行档位）；
         * 底部区一共用两次：内容可视区底边「↔ 添加档位」与「添加档位 ↔ 底部双按钮」（见 [viewportH]）。
         */
        const val BOTTOM_GAP = 4
        // 开关图标沿用服务器配置界面那一对（不再新画）
        val ON_ICON = net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/switch_icon_on.png")
        val OFF_ICON = net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/switch_icon_off.png")
    }
}
