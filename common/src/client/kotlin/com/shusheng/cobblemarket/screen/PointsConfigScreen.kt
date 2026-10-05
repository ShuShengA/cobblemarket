package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.POINTS_ICON_TEXTURE
import com.shusheng.cobblemarket.client.playFailSound
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
import com.shusheng.cobblemarket.util.TextUtil
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

    /** 「give/take 权限下放」开关（默认关）：打开后玩家身份的 OP / FTB 任务模组也能发放 RP */
    private var rpAsQuestCurrency = false

    /** RP 的四个费率（%，服务端下发）：「费率配置」二级弹窗填初值，提醒弹窗看它们是否全 0 */
    private var pointsPokemonFee = 0.0
    private var pointsItemFee = 0.0
    private var pointsAuctionFee = 0.0
    private var pointsBuyOrderFee = 0.0



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

    // ── 「give/take 权限下放」确认弹窗（点**开启**时弹；确认按钮 5 秒冷静期）──
    //
    // 为什么要有这个弹窗（用户 2026-09-28）：这个开关把「发放 / 回收 RP」的权限**交出去**，
    // 后果不可逆（开了之后任何 OP 都能给自己发钱），而开关本身只有一行标签、说不清这么多。
    // ⇒ 打开前用弹窗把**作用 / 用途 / 风险**讲清楚，并用 5 秒冷静期防手滑。

    /**
     * 当前开着的确认弹窗是哪一种：`""` = 没开、`"grant"` = 权限下放、`"market"` = 玩家间交易。
     *
     * ⚠ **两处复用同一套弹窗** —— 它们都是"打开就有不可逆后果"的开关，机制完全一样
     *   （讲清含义 + 确认按钮 5 秒冷静期），差别只在**词条**与**确认后写哪个字段**。
     */
    private var confirmKind = ""

    /** 确认按钮**解禁的时刻**：打开弹窗时 = now + [GRANT_CONFIRM_COOLDOWN_MS] */
    private var confirmReadyAt = 0L

    /** 确认弹窗的按钮（只在它开着时创建）—— `[0]` 是确认、`[1]` 是取消 */
    private val confirmDialogButtons = mutableListOf<NineSliceButton>()

    /** give/take 权限下放：点**开启**时走这里先弹确认窗（关闭不需要确认，直接关） */
    private fun onGrantToggle() {
        if (rpAsQuestCurrency) {
            rpAsQuestCurrency = false
            markDirtyAndRebuild()
        } else {
            openConfirmDialog("grant")
        }
    }

    /**
     * 玩家间交易：点**开启**时弹确认窗。
     *
     * ⚠ 这个弹窗的首要任务是**破除一个误解**（用户 2026-09-28）：服主看到"玩家间交易"多半会
     *   以为**玩家能直接给另一个人转 RP**（像 `/pay` 那样）。实际机制是**只能靠买卖东西** ——
     *   没有"转账"这回事。所以正文写"可以互相买卖"，红字段明确否掉转账。
     * ⚠ 费率提醒（原先的 `feeWarn`）**并进了这个弹窗**：有费率时多一行红字提示去调 0。
     *   否则会出现"确认窗 → 又弹一个费率窗"的两层弹出。
     */
    private fun onMarketToggle() {
        if (pointsMarketEnabled) {
            pointsMarketEnabled = false
            markDirtyAndRebuild()
        } else {
            openConfirmDialog("market")
        }
    }

    /** 开确认弹窗：**先重建再建自己的控件**（顺序同 [openFeeDialog]，反了会被 rebuild 清掉） */
    private fun openConfirmDialog(kind: String) {
        // ⚠ 冷静期的起点**要在 rebuild 之前**记：rebuild 会重跑 init，之后算会少一帧（无妨但没必要）
        confirmReadyAt = System.currentTimeMillis() + GRANT_CONFIRM_COOLDOWN_MS
        confirmKind = kind
        confirmDialogButtons.clear()
        rebuild()
        hideMainControls()

        val dx = editDialogX()
        val closeY = editDialogY() + EDIT_H - 26
        addDrawableChild(
            NineSliceButton(dx + 22, closeY, 80, 16,
                Text.translatable("cobblemarket.points.config.grant_confirm"), { commitConfirmDialog() })
                .also { confirmDialogButtons += it }
        )
        addDrawableChild(
            NineSliceButton(dx + EDIT_W - 102, closeY, 80, 16,
                Text.translatable("cobblemarket.op.scfg_cancel"), { closeConfirmDialog() })
                .also { confirmDialogButtons += it }
        )
    }

    private fun closeConfirmDialog() {
        confirmKind = ""
        markDirtyAndRebuild()
    }

    /** 确认开启：**冷静期内点它无效**（按钮已置灰，这里是硬拦，防快捷键/连点绕过） */
    private fun commitConfirmDialog() {
        if (System.currentTimeMillis() < confirmReadyAt) {
            playFailSound()
            return
        }
        when (confirmKind) {
            "grant" -> rpAsQuestCurrency = true
            "market" -> pointsMarketEnabled = true
        }
        confirmKind = ""
        markDirtyAndRebuild()
        save()
    }

    /**
     * 确认按钮的冷静期表现：**剩余秒数显示在按钮上 + 置灰**，到点自动解禁。
     *
     * ⚠ 在 `render` 里每帧调 —— 到点那一刻没有任何事件回调，只在别处算会一直停在灰的
     *   （同"面板开合动画播完那一刻"那个坑）。
     */
    private fun updateConfirmButton() {
        val btn = confirmDialogButtons.firstOrNull() ?: return
        val left = ((confirmReadyAt - System.currentTimeMillis()) / 1000L).toInt()
        if (left > 0) {
            btn.dimmed = true
            btn.setMessage(Text.translatable("cobblemarket.points.config.grant_wait", left))
        } else {
            btn.dimmed = false
            btn.setMessage(Text.translatable("cobblemarket.points.config.grant_confirm"))
        }
    }

    /**
     * 确认弹窗的内容（面板由 [renderBackground] 画好）。
     *
     * ⚠ 说明文字**每行一个独立词条**，不写成一整句 —— 中英行宽差得多，长句英文必然溢出
     *   （`EDIT_W = 220`，可用宽约 200px）。
     */
    private fun renderConfirmDialog(context: DrawContext) {
        val titleKey = when (confirmKind) {
            "grant" -> "cobblemarket.points.config.grant_title"
            "market" -> "cobblemarket.points.config.market_title"
            else -> return
        }
        val lineKeys = when (confirmKind) {
            "grant" -> listOf(
                "cobblemarket.points.config.grant_line1",
                "cobblemarket.points.config.grant_line2",
                "cobblemarket.points.config.grant_line3"
            )
            else -> listOf(
                "cobblemarket.points.config.market_line1",
                "cobblemarket.points.config.market_line2",
                "cobblemarket.points.config.market_line3"
            )
        }
        // 红字段：玩家间交易那条在**配了费率**时多一行（原先的 `feeWarn` 弹窗并进来了）
        val warnKeys = when (confirmKind) {
            "grant" -> listOf(
                "cobblemarket.points.config.grant_warn1",
                "cobblemarket.points.config.grant_warn2"
            )
            else -> buildList {
                // ⚠ 首要任务是**破除"玩家之间能转账"这个误解**（用户 2026-09-28）——
                //   服主看到"玩家间交易"多半以为能像 /pay 那样直接转，实际只能靠买卖东西
                add("cobblemarket.points.config.market_warn1")
                if (anyFee()) add("cobblemarket.points.config.market_warn_fee")
            }
        }

        val dx = editDialogX()
        context.drawCenteredTextWithShadow(
            textRenderer, Text.translatable(titleKey).formatted(Formatting.GOLD),
            width / 2, editDialogY() + 14, 0xFFFFFF
        )
        // ⚠ 货币单位一律 `pointsIcon()`（项目规矩：游戏内文案不出现「RP」字样）；
        //   没有 `%s` 的条目多传参数会被忽略，所以统一传、不必按 key 分支
        var y = editDialogY() + 34
        lineKeys.forEach { key ->
            context.drawTextWithShadow(
                textRenderer, Text.translatable(key, CurrencyService.pointsIcon()), dx + 10, y, 0xFFFFFF
            )
            y += 12
        }
        // ⚠ 风险提示单独一段、**红字** —— 这两个开关都有不可逆后果，要显眼
        y += 6
        warnKeys.forEach { key ->
            context.drawTextWithShadow(
                textRenderer, Text.translatable(key, CurrencyService.pointsIcon()), dx + 10, y, 0xFF5555
            )
            y += 12
        }
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
    private fun anyDialogOpen() = editing || feeDialog || confirmKind.isNotEmpty()

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
            iconLeft = net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/button/back.png"),
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
            iconLeft = net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/icon/info.png"),
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
        addToggle("points_market", pointsMarketEnabled) { onMarketToggle() }
        // ⚠ 本行多占了一行设置项 ⇒ **档位区少显示一行**（见 [DIALOG_H] 的算式）。
        //   用户 2026-09-28 拍板接受这个取舍（他 09-19 特意要求过"档位多显示一行"，所以不能擅自加）。
        // ⚠ 点**开启**先弹确认窗（讲清交出了什么权限 + 5 秒冷静期）；**关闭**不用确认，直接关
        addToggle("rp_quest", rpAsQuestCurrency) { onGrantToggle() }
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
     * 右侧滚动条：内容装不下才画。
     * 轨道 = 内容可视区；滑块高按「可见行数 / 总行数」的比例。
     *
     * ⚠ 走共用的 [ScrollBar]（**可拖动** —— 原先只是画出来的两根竖条，按上去没反应）：
     *   几何由它记下来供拖动换算，**别在这儿自己 fill**（自己画的条拖不动）。
     * ⚠ 三个参数**都是"行"**。行高有两种（设置项 [ROW_H] / 档位行 [TIER_ROW_H]），所以
     *   「可见行数」不能按 `viewportH() / ROW_H` 估 —— 那样算出的 maxScroll 与 [maxScrollRow]
     *   对不上（滑块行程与滚轮范围会错位）。这里由 [maxScrollRow] **反推**：
     *   可见行数 = 总行数 − 最多能滚的行数 ⇒ 两者的 maxScroll 天然一致。
     */
    private fun renderScrollbar(context: DrawContext) {
        val rows = (contentRowCount() - maxScrollRow()).coerceAtLeast(1)
        ScrollBar.shared.draw(
            context, dialogX() + dialogW() - 5, contentTop(), viewportH(),
            contentRowCount(), rows, scrollRow
        )
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
        place(ROW_RP_QUEST, toggleButtons["rp_quest"], rightX, 0)
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
        // ⚠⚠ **点「保存」就要真的保存**（2026-09-30 用户报「添加/编辑档位点保存不触发保存」）：
        //   这颗按钮用的是 `scfg_save`「保存」，而**隔壁费率弹窗的 [commitFeeDialog] 就是
        //   提交后直接 [save]** —— 当初这里漏了这一行，表现成「按钮叫保存、却只改了本地列表」，
        //   服主以为存了、要 `/market reload` 才生效。
        // ⚠ 顺序必须是**先关弹窗、再保存**：`closeTierDialog()` 会 rebuild 一次，
        //   而 [save] 要么成功发提示、要么因「快照没到」给 `saveBlocked` 提示 ——
        //   那两条提示都画在**主界面**底部，弹窗还盖着就看不见了。
        save()
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
        if (anyDialogOpen()) {
            drawNineSlice(
                context, DIALOG_BACKGROUND_TEXTURE,
                editDialogX(), editDialogY(), EDIT_W, EDIT_H, 0, DIALOG_BACKGROUND_TEX_H
            )
        }
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
        if (feeDialog) {
            renderFeeDialog(context)
            return
        }
        if (confirmKind.isNotEmpty()) {
            // ⚠ 冷静期的按钮文字要**每帧刷**：到点那一刻没有任何事件回调，
            //   只在打开时算一次会一直停在"请稍候"（同"动画播完那一刻"那个坑）
            updateConfirmButton()
            renderConfirmDialog(context)
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

        // 设置项标签：**行号与 layoutRows 里的 ROW_* 一一对应**（前 4 行是 RP 相关，标签带 RP 图标）。
        // 装不下的行一律不画（跟控件同一套可见性判据）。
        val settingLabels = listOf(
            ROW_POINTS to "cobblemarket.points.config.points_enabled",
            ROW_POINTS_MARKET to "cobblemarket.points.config.points_market",
            ROW_RP_QUEST to "cobblemarket.points.config.rp_quest",
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
            // 「默认券图」这行：标签后面**用括号把内置券图那张图括起来画**（2026-09-30 用户定）——
            // 光看输入框里那句「留空 = 用内置券图」，服主不知道内置的那张长什么样。
            //
            // ⚠ **括号不能少**（用户强调了三轮）：整组是「（ ＋ 图 ＋ ）」，三段各画各的。
            //   括号走普通文字 —— 图是绘制出来的、进不了 Text 流，所以只能这么拼。
            // ⚠ 画的是**真图** `points_icon.png`（[PointsRechargeScreen] 档位卡的默认券图也是它）
            //   ⇒ 服主或资源包换过它时这里跟着一起变，**不存第二份、不用同步**。
            // ⚠ 缩放必须走 matrices —— `drawTexture` 的 width/height 是**采样区**不是绘制尺寸
            //   （照 `PointsRechargeScreen` 画券图那套）。
            // ⚠ 竖直方向：两个括号与**标签文字同一条基线**（`rowY + 7`），图对准文字的中心，
            //   这样「默认券图（图）」读起来是连续的一串，不是三块浮着的东西。
            // ⚠ 位置：标签右缘 + 4px 起。这行空档 150px（标签到 dx+46、输入框在 dx+196），
            //   整组画满也只有 ~84px，**碰不到输入框**。
            if (row == ROW_ICON) {
                val iconH = BUILTIN_ICON_H
                // ⚠ 宽度**向上取整**，别用整除：图是按 `iconH / 160` 缩放画的，实际画出来
                //   25.6px 宽，而整除得 25 ⇒ 少推进 0.6px，右括号就压到图上（2026-09-30 用户报的）
                val iconW = (iconH * BUILTIN_ICON_TEX_W + BUILTIN_ICON_TEX_H - 1) / BUILTIN_ICON_TEX_H
                val parenY = rowY(row) + 7
                var px = dx + 10 + textRenderer.getWidth(text) + 4
                context.drawTextWithShadow(textRenderer, Text.literal(TextUtil.parenOpen()), px, parenY, 0xFFFFFF)
                // ⚠ 括号与图之间**两侧补同样宽的空**：`（`/`）` 的字形自带边距，
                //   不补的话右边看着比左边"贴死"（用户原话），补上才左右对称
                px += textRenderer.getWidth(TextUtil.parenOpen()) + BUILTIN_ICON_PAREN_GAP
                context.matrices.push()
                // 文字中心 = parenY + 4（8px 高的字）⇒ 图对准它
                context.matrices.translate(px.toDouble(), (parenY + 4 - iconH / 2).toDouble(), 0.0)
                context.matrices.scale(iconH / BUILTIN_ICON_TEX_H.toFloat(), iconH / BUILTIN_ICON_TEX_H.toFloat(), 1f)
                context.drawTexture(
                    POINTS_ICON_TEXTURE, 0, 0, 0f, 0f,
                    BUILTIN_ICON_TEX_W, BUILTIN_ICON_TEX_H, BUILTIN_ICON_TEX_W, BUILTIN_ICON_TEX_H
                )
                context.matrices.pop()
                px += iconW + BUILTIN_ICON_PAREN_GAP
                context.drawTextWithShadow(textRenderer, Text.literal(TextUtil.parenClose()), px, parenY, 0xFFFFFF)
            }
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

                // ⚠ **保存本身是成功的**、只是有图读不出来 ⇒ 前半句走**绿**（"存了"这件事是真的）、
                //   后半句走**红**（图片的问题）。整条都红会被读成"没保存成功" ——
                //   2026-09-30 用户原话：「fail 音让人以为保存没生效，而不是没读出图」。
                // ⚠ 两段必须**平级**挂在 `Text.literal("")` 下、各自带色：带色的文字当父节点
                //   会让后面整段的样式继承它（CLAUDE.md 的界面文字约定）。
                else -> Text.literal("")
                    .append(
                        Text.translatable("cobblemarket.points.config.qr_problem_ok")
                            .formatted(Formatting.GREEN)
                    )
                    .append(
                        Text.translatable("cobblemarket.points.config.qr_problem_short_bad")
                            .formatted(Formatting.RED)
                    )
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
        // ⭐ 滚动条：**排在最后**（控件 / 行优先）—— [ScrollBar] 的命中区左右各放宽了 3px，
        //   压在内容行右缘那一两像素会被它截胡。
        //   ⚠ 弹窗盖着时滚动条不画（render 提前返回）⇒ 这里也不判，免得点到"看不见的条"
        // `!result` = 控件已经吃下这一击时不再判（行上的按钮 / 输入框优先）；
        // `maxScrollRow() > 0` = 现在确实有得滚（装得下时它不画，几何还停在上一帧）
        if (!result && !anyDialogOpen() && button == 0 && maxScrollRow() > 0) {
            ScrollBar.shared.mouseClicked(mouseX, mouseY, scrollRow)?.let {
                scrollRow = it
                // 行是**控件** ⇒ 位置得跟着新偏移重摆（同 [mouseScrolled]）
                layoutRows()
                return true
            }
        }
        return result
    }

    /** 拖动滚动条（见 [ScrollBar]）—— 本界面里**只有它**在用拖动 */
    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (!anyDialogOpen()) {
            ScrollBar.shared.mouseDragged(mouseY, scrollRow)?.let {
                scrollRow = it
                layoutRows()
                return true
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    /**
     * 松开：结束滚动条的拖动。
     *
     * ⚠ 结束时要**吃掉这次松开** —— 否则它会在别处又触发一次点击（松手那一刻鼠标往往正压着一行 / 一个按钮）。
     */
    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (ScrollBar.shared.mouseReleased()) return true
        return super.mouseReleased(mouseX, mouseY, button)
    }

    /**
     * 界面以任何方式离开（返回按钮 / Esc / E / 关闭动画）都在这里收尾：
     * 放弃可能还在进行中的滚动条拖动（滚动条是**单例**，不清理会留个"半拖"状态给下个界面）。
     *
     * ⚠ 放 [removed] 而不是 `close()`：Esc 走动画时是 `setScreen(null)`，不经过 `close()`
     *   （同 BalanceHudPositionScreen 那条注释）。
     */
    override fun removed() {
        super.removed()
        ScrollBar.shared.cancel()
    }

    override fun shouldPause() = false

    private companion object {
        const val ROW_H = 20
        /**
         * 「默认券图」那行**括号里那张内置券图**的真实像素尺寸。
         *
         * ⚠ 与 `currency/points_icon.png` 的实际尺寸对齐（256×160，**不是正方形**）。
         *   缩放走 matrices、按这个比例等比缩到 [BUILTIN_ICON_H] 高 —— 同 `POKEMON_SLOT_TEX_SIZE`
         *   的口径（常量与文件真实尺寸对齐）。
         */
        const val BUILTIN_ICON_TEX_W = 256
        const val BUILTIN_ICON_TEX_H = 160
        /** 括号里那张图的显示高度（行高 20，留 4px 余量）。**要调大小改这一个数就行** */
        const val BUILTIN_ICON_H = 16
        /** 括号与图之间的空隙。**要调疏密改这一个数**（两侧同时生效，别只调一边） */
        const val BUILTIN_ICON_PAREN_GAP = 2

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
         * 「give/take 权限下放」确认按钮的**冷静期**（毫秒）。
         *
         * 这个开关一开，**任何 OP 都能给自己发 RP** —— 手滑的代价太大，所以确认按钮前 5 秒置灰
         * 并显示倒计时（用户 2026-09-28 定）。
         */
        const val GRANT_CONFIRM_COOLDOWN_MS = 5000L

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
        const val ROW_RP_QUEST = 2
        const val ROW_FEE = 3
        const val ROW_SHOP = 4
        const val ROW_QR = 5
        const val ROW_ICON = 6
        const val ROW_TIMEOUT = 7
        const val ROW_CURRENCY = 8

        /** 设置项占的行数（总开关 / 玩家间交易 / give/take 权限下放 / 费率配置 / 充值开关 / 收款码 / 券图 / 超时 / 货币单位） */
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
        val ON_ICON = net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/button/switch_icon_on.png")
        val OFF_ICON = net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/button/switch_icon_off.png")
    }
}
