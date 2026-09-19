package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PointsConfigPayload
import com.shusheng.cobblemarket.network.PointsTierConfig
import com.shusheng.cobblemarket.network.RequestPointsConfigPayload
import com.shusheng.cobblemarket.network.SavePointsConfigPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import kotlin.math.roundToInt

/**
 * **RP 市场配置**（服务器配置界面 → 「RP 市场」入口；仅 OP）。
 *
 * 宗旨（用户 2026-09-18）：**除了服务器货币必须改配置文件，其余都要能在界面里改** ——
 * 开关、档位、收款码、超时全都在这儿，保存即生效、不用重启、不用碰 json。
 *
 * ⚠ 权限只认 **OP**（`hasPermissionLevel(2)`），不下放任何权限节点 ——
 * 与"RP 市场上架全交给 OP"同一口径（服务端也会再校验一次）。
 */
class PointsConfigScreen : Screen(
    Text.translatable("cobblemarket.points.config.title", CurrencyService.pointsTitleIcon())
) {

    // ── 本地编辑态（保存时一次性提交；快照回来时覆盖） ──
    private var pointsEnabled = false

    /** 玩家间 RP 交易开关（默认关）；控制上架界面的货币切换按钮显不显示 */
    private var pointsMarketEnabled = false

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
    private var codeScreenButton: NineSliceButton? = null
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

    /** 档位列表滚到第几行（档位多到放不下时才有得滚） */
    private var scrollOffset = 0
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
     * 上下边界、连保存按钮都点不到 —— 2026-09-18 用户报的 RP 市场同类问题。
     */
    private fun dialogH() = minOf(DIALOG_H, height - 16)
    private fun dialogX() = width / 2 - dialogW() / 2

    /**
     * 弹窗打开时隐藏主界面**全部**控件。
     *
     * ⚠ **新增常驻控件必须加进这里**（`codeScreenButton` 就是这么漏的）——
     * 这个界面原来手写了**三份一模一样的名单**（费率提醒 / 费率编辑 / 档位编辑），
     * 加「兑换码」入口时三份都没追上，弹窗盖着按钮还是能点（用户实测）。
     * 恢复不用管：关闭弹窗走 `clearChildren() + init()` 全量重建。
     */
    /**
     * 档位行上两个按钮的布局（相对 [rightX]）。
     *
     * ⚠ **必须两处共用**：`buildEditDialog` 建按钮时用一次，`applyTierScroll` 滚动重摆时用一次。
     * 之前两处各写各的 —— 改了建的那处、滚动那处还是旧值，结果「一滚动按钮就集体右移超出背景」
     * （用户实测）。宽度按**英文** "Edit"/"Remove" 留，别照中文两个字缩。
     */
    private val TIER_EDIT_DX = -60
    private val TIER_EDIT_W = 32
    private val TIER_DELETE_DX = -24
    private val TIER_DELETE_W = 46

    private fun hideMainControls() {
        listOfNotNull(
            backButton, infoButton, addTierButton, saveButton, cancelButton,
            qrField, defaultIconField, timeoutField, currencyField, feeCfgButton,
            codeScreenButton
        ).forEach { it.visible = false }
        toggleButtons.values.forEach { it.visible = false }
        tierEditButtons.forEach { it.visible = false }
        tierDeleteButtons.forEach { it.visible = false }
    }
    private fun dialogY() = height / 2 - dialogH() / 2
    private fun listStartY() = dialogY() + 30

    /** 档位列表区顶部：固定项下方 */
    private fun tierListTop() = listStartY() + FIXED_ROWS * ROW_H + 4

    /**
     * 档位列表区的**可用上限**（底边）= 「添加档位」按钮上方。
     * ⚠ 那个按钮**贴底**（在底部双按钮上方），不跟档位列表走 ——
     * 列表可滚动，跟着它会被一起滚走/滚出屏幕。
     */
    private fun tierAreaMax() =
        dialogY() + dialogH() - BOTTOM_BUTTON_BOTTOM - BOTTOM_GAP - TIER_ADD_H - BOTTOM_GAP

    /** 列表区一次**最多**能显示几行档位（至少 1 行）。按可用上限算，与下面的收缩无关 */
    private fun visibleTierRows() = ((tierAreaMax() - tierListTop()) / ROW_H).coerceAtLeast(1)

    /**
     * 档位列表区**实际**底边 = 可用上限 与「刚好装下现有档位」取小 ——
     * 下面那条分割线（画在它 +2 处）就跟着走。
     *
     * ⚠ 档位不满一屏时区域必须收缩：固定用可用上限的话，底下永远吊着一条空档，
     * **档位越少越显眼**（2026-09-19 用户报：5 个档位、第 5 行和分割线之间空一大截）。
     * 档位超出一屏时两者相等（= 可用上限），滚动照旧。
     */
    private fun tierListBottom(): Int =
        minOf(
            tierAreaMax(),
            tierListTop() + minOf(tiers.size, visibleTierRows()).coerceAtLeast(1) * ROW_H
        )

    /** 档位多到放不下时最多能往下滚几行（0 = 不用滚） */
    private fun maxScrollOffset() = (tiers.size - visibleTierRows()).coerceAtLeast(0)

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
        scrollOffset = scrollOffset.coerceIn(0, maxScrollOffset())
        clearChildren()
        toggleButtons.clear()
        tierEditButtons.clear()
        tierDeleteButtons.clear()
        editing = false
        buildMain()
    }

    // ── 主界面 ──

    private fun buildMain() {
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

        var y = listStartY()
        fun addToggle(key: String, value: Boolean, onClick: () -> Unit) {
            // onPress 是 (ButtonWidget) -> Unit，包一层丢掉那个参数
            val btn = NineSliceButton(rightX, y, 22, 18, Text.literal(""), { onClick() })
            btn.iconLeft = if (value) ON_ICON else OFF_ICON
            toggleButtons[key] = btn
            addDrawableChild(btn)
            y += ROW_H
        }
        addToggle("points", pointsEnabled) { pointsEnabled = !pointsEnabled; markDirtyAndRebuild() }
        addToggle("points_market", pointsMarketEnabled) { onPointsMarketToggle() }
        // RP 四个费率的入口：右侧一个「配置」按钮开二级弹窗 —— 四个费率直接摊在主弹窗里放不下
        feeCfgButton = NineSliceButton(
            rightX - 18, y, 40, 18,
            Text.translatable("cobblemarket.points.config.fee_cfg_btn"), { openFeeDialog() }
        )
        addDrawableChild(feeCfgButton)
        y += ROW_H
        addToggle("shop", shopEnabled) { shopEnabled = !shopEnabled; markDirtyAndRebuild() }

        // 收款码文件名（全局码；每个档位还可以在编辑弹窗里配自己的）
        qrField = TextFieldWidget(textRenderer, rightX - 130, y + 2, 152, 16, Text.literal(""))
        qrField?.setMaxLength(64)
        qrField?.setTextPredicate { it.none { c -> c == '/' || c == '\\' } }
        // 留空是什么意思，只有 placeholder 说得清（标签栏 62px 塞不下，info 图标太远）
        qrField?.setPlaceholder(Text.translatable("cobblemarket.points.config.qr_hint"))
        qrField?.setChangedListener { if (!programmaticText) pendingText["qr"] = it }
        fillField(qrField, "qr", payQr)
        addSelectableChild(qrField); addDrawableChild(qrField)
        y += ROW_H

        // 默认券图（档位「RP 券」的全局默认；放 config/cobblemarket/icons/ 下，留空 = 用内置券图）
        defaultIconField = TextFieldWidget(textRenderer, rightX - 130, y + 2, 152, 16, Text.literal(""))
        defaultIconField?.setMaxLength(64)
        defaultIconField?.setTextPredicate { it.none { c -> c == '/' || c == '\\' } }
        defaultIconField?.setPlaceholder(Text.translatable("cobblemarket.points.config.icon_hint"))
        defaultIconField?.setChangedListener { if (!programmaticText) pendingText["defaultIcon"] = it }
        fillField(defaultIconField, "defaultIcon", defaultIcon)
        addSelectableChild(defaultIconField); addDrawableChild(defaultIconField)
        y += ROW_H

        // 待支付超时（分钟）
        timeoutField = TextFieldWidget(textRenderer, rightX - 60, y + 2, 82, 16, Text.literal(""))
        timeoutField?.setMaxLength(5)
        timeoutField?.setTextPredicate { it.all(Char::isDigit) }
        timeoutField?.setChangedListener { if (!programmaticText) pendingText["timeout"] = it }
        fillField(timeoutField, "timeout", timeoutMinutes.toString())
        addSelectableChild(timeoutField); addDrawableChild(timeoutField)
        y += ROW_H

        // 货币单位（纯展示，服主自己填「元」/「USD」；留空 = 金额不带单位）
        currencyField = TextFieldWidget(textRenderer, rightX - 60, y + 2, 82, 16, Text.literal(""))
        currencyField?.setMaxLength(16)
        // 这个框只有 82px 宽，placeholder 得短（超了会画到框外）
        currencyField?.setPlaceholder(Text.translatable("cobblemarket.points.config.currency_hint"))
        currencyField?.setChangedListener { if (!programmaticText) pendingText["currency"] = it }
        fillField(currencyField, "currency", currencyLabel)
        addSelectableChild(currencyField); addDrawableChild(currencyField)
        y += ROW_H + 4

        // 档位行：名称 / 价格 / 点数 + 编辑 / 删除（放不下就滚，见 mouseScrolled）
        val rows = visibleTierRows()
        tiers.forEachIndexed { index, tier ->
            val relRow = index - scrollOffset
            val rowY = tierListTop() + relRow * ROW_H
            val visible = relRow in 0 until rows
            // 宽度按**英文**留：原来的 Edit 26 / Remove 22 是照中文两个字定的，
            // 英文 "Remove" 要 36px 左右，22px 的按钮直接溢出（用户 2026-09-19 实测）。
            // 两个按钮**相邻**（间距 4，原来空了 28）、整体右缘不变（只左移 6px，不挤左边的列）
            val edit = NineSliceButton(
                rightX + TIER_EDIT_DX, rowY, TIER_EDIT_W, 16, Text.translatable("cobblemarket.price_limit.edit"),
                { openTierDialog(index) }
            )
            val delete = NineSliceButton(
                rightX + TIER_DELETE_DX, rowY, TIER_DELETE_W, 16, Text.translatable("cobblemarket.price_limit.remove"),
                { tiers.removeAt(index); markDirtyAndRebuild() }
            )
            // 滚出列表区的行要藏起来：留着会画到别的行/按钮上，还点得到
            edit.visible = visible
            delete.visible = visible
            tierEditButtons += edit
            tierDeleteButtons += delete
            addDrawableChild(edit)
            addDrawableChild(delete)
        }

        // 「添加档位」的位置：
        // - 档位**满一屏** → 贴底（在底部双按钮上方）：列表可滚动，跟着列表会被滚走
        // - 档位**不满一屏** → 贴着档位区下方。⚠ 这半边是 2026-09-19 补的：档位区做了自适应
        //   收缩之后，如果按钮还钉在底部，那块空白只是从「档位下方」搬到「按钮上方」，
        //   看着还是空一大截（用户实测）
        val tierAddY =
            if (tierListBottom() < tierAreaMax()) tierListBottom() + BOTTOM_GAP
            else dialogY() + dialogH() - BOTTOM_BUTTON_BOTTOM - BOTTOM_GAP - TIER_ADD_H
        // 兑换码管理入口：与「添加档位」**同一行右对齐**（对称）——不新增行、不挤压档位列表
        codeScreenButton = NineSliceButton(
            dx + dialogW() - 10 - 70, tierAddY, 70, TIER_ADD_H,
            Text.translatable("cobblemarket.points.config.code_entry"), { client?.setScreen(PointsCodeScreen()) }
        )
        addDrawableChild(codeScreenButton)

        addTierButton = NineSliceButton(
            dx + 10, tierAddY, 70, TIER_ADD_H,
            Text.translatable("cobblemarket.points.config.add_tier"), { openTierDialog(-1) }
        )
        addDrawableChild(addTierButton)

        // 底部双按钮：**照服务器配置界面那一对**（位置、尺寸、间距都一致 —— 同类型界面要能对上）
        saveButton = NineSliceButton(
            width / 2 - 62, dialogY() + dialogH() - 26, 60, 20,
            Text.translatable("cobblemarket.op.scfg_save"),
            { save() }
        )
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
        if (editing || maxScrollOffset() <= 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }
        val target = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxScrollOffset())
        if (target == scrollOffset) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }
        scrollOffset = target
        applyTierScroll()
        return true
    }

    /**
     * 右侧滚动条：档位超出一屏才画（否则玩家根本不知道下面还有档位）。
     *
     * 画法与**服务器配置界面**同一套（那边就是同一个「固定项 + 可滚动列表」的结构）：
     * 2px 宽，轨道半透明、滑块不透明，滑块高按「可见行 / 总行数」的比例。
     */
    private fun renderScrollbar(context: DrawContext) {
        val maxScroll = maxScrollOffset()
        if (maxScroll <= 0) return
        val x = dialogX() + dialogW() - 5
        val top = tierListTop()
        val trackH = tierListBottom() - tierListTop()
        val thumbH = maxOf(16, trackH * trackH / (trackH + maxScroll * ROW_H))
        val thumbY = top + (trackH - thumbH) * scrollOffset / maxScroll
        context.fill(x, top, x + 2, top + trackH, 0x40FFFFFF)
        context.fill(x, thumbY, x + 2, thumbY + thumbH, 0xC0FFFFFF.toInt())
    }

    /** 按当前滚动位置重排档位行上的两个按钮（含可见性） */
    private fun applyTierScroll() {
        val rightX = dialogX() + dialogW() - 10 - 22
        // ⚠ 走与 buildEditDialog 同一组常量（两处各写各的就会「滚动后按钮跳位」）
        tierEditButtons.forEachIndexed { index, button ->
            placeTierRowButton(index, button, rightX + TIER_EDIT_DX)
        }
        tierDeleteButtons.forEachIndexed { index, button ->
            placeTierRowButton(index, button, rightX + TIER_DELETE_DX)
        }
    }

    private fun placeTierRowButton(index: Int, button: NineSliceButton, x: Int) {
        val relRow = index - scrollOffset
        val visible = relRow in 0 until visibleTierRows()
        button.visible = visible
        if (visible) button.setPosition(x, tierListTop() + relRow * ROW_H)
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

        var y = listStartY()
        /** 配置项标签；[arg] 填词条里的那个 `%s`（图标类词条传 `CurrencyService.pointsIcon()`） */
        fun label(key: String, arg: Text? = null) {
            val text = if (arg == null) Text.translatable(key) else Text.translatable(key, arg)
            context.drawTextWithShadow(textRenderer, text, dx + 10, y + 7, 0xFFFFFF)
            y += ROW_H
        }
        label("cobblemarket.points.config.points_enabled", CurrencyService.pointsIcon())
        label("cobblemarket.points.config.points_market", CurrencyService.pointsIcon())
        label("cobblemarket.points.config.points_fee", CurrencyService.pointsIcon())
        label("cobblemarket.points.config.shop_enabled")
        label("cobblemarket.points.config.pay_qr")
        label("cobblemarket.points.config.default_icon")
        label("cobblemarket.points.config.timeout")
        label("cobblemarket.points.config.currency_label")
        y += 4
        // 档位行：名称 / 价格 / 点数（带 `*` = 该档配了专用收款码，文件名在编辑弹窗里看）。
        // 按滚动位置画，滚出列表区的行不画。
        tiers.forEachIndexed { index, tier ->
            val relRow = index - scrollOffset
            if (relRow !in 0 until visibleTierRows()) return@forEachIndexed
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
                dx + 10, tierListTop() + relRow * ROW_H + 5, 0xFFFFFF
            )
        }

        // 档位滚动区**上下各一条分隔线**，把它和上面的固定项、下面的「添加档位」按钮分开
        //（灰色 1px、左右各内缩 6px —— 与服务器配置界面列表里的行线同款）
        val lineL = dx + 6
        val lineR = dx + dialogW() - 6
        context.fill(lineL, tierListTop() - 4, lineR, tierListTop() - 3, 0xFF555555.toInt())
        context.fill(lineL, tierListBottom() + 2, lineR, tierListBottom() + 3, 0xFF555555.toInt())
        renderScrollbar(context)

        // 保存结果提示（弹窗底部居中）：成功绿、失败红（失败也要说，别只播一声 fail 音）
        if (System.currentTimeMillis() < savedToastUntil) {
            if (saveResultOk) {
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable("cobblemarket.points.config.saved", CurrencyService.pointsIcon())
                        .formatted(Formatting.GREEN),
                    centerX, dialogY() + dialogH() - 38, 0xFFFFFF
                )
            } else {
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable("cobblemarket.points.config.qr_problem_short").formatted(Formatting.RED),
                    centerX, dialogY() + dialogH() - 38, 0xFFFFFF
                )
            }
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

    private companion object {
        const val ROW_H = 20
        /** 档位编辑弹窗尺寸（5 行输入 + 标题 + 按钮） */
        const val EDIT_W = 220
        const val EDIT_H = 182

        /**
         * 主弹窗的设计高度；实际会被屏幕高度钳制（见 [dialogH]）。
         *
         * ⚠ **改它之前先重算档位区高度**，否则会空出一条或挤掉一行：
         * ```
         * 档位区高 = DIALOG_H − BOTTOM_BUTTON_BOTTOM − 2×BOTTOM_GAP − TIER_ADD_H
         *                    − (30 + FIXED_ROWS×ROW_H + 4)
         *          = DIALOG_H − 54 − (30 + FIXED_ROWS×20 + 4)
         * ```
         * FIXED_ROWS=6 时是 `DIALOG_H − 208`，328 正好 120px = 6 行；
         * **加「玩家间交易」开关后 FIXED_ROWS → 7**，公式变成 `DIALOG_H − 228`，
         * 于是 DIALOG_H 同步 +20 → 348，仍是 120px = 6 行、一个像素不剩。
         */
        const val DIALOG_H = 368

        /** 档位列表上方那几行的固定项数量（总开关/充值开关/默认收款码/默认券图/超时/货币单位） */
        const val FIXED_ROWS = 8

        /** 底部双按钮的高度、以及它离弹窗底边的距离 */
        const val BOTTOM_BUTTON_BOTTOM = 26

        /** 「添加档位」按钮高度 */
        const val TIER_ADD_H = 16

        /** 相邻控件之间的最小空隙（按钮间距规则） */
        const val BOTTOM_GAP = 6
        // 开关图标沿用服务器配置界面那一对（不再新画）
        val ON_ICON = net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/switch_icon_on.png")
        val OFF_ICON = net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/switch_icon_off.png")
    }
}
