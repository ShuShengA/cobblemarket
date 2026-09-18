package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PointsConfigPayload
import com.shusheng.cobblemarket.network.PointsTierConfig
import com.shusheng.cobblemarket.network.RequestPointsConfigPayload
import com.shusheng.cobblemarket.network.SavePointsConfigPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
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
class PointsConfigScreen : Screen(Text.translatable("cobblemarket.points.config.title")) {

    // ── 本地编辑态（保存时一次性提交；快照回来时覆盖） ──
    private var pointsEnabled = false
    private var shopEnabled = false
    private var payQr = ""
    private var timeoutMinutes = 15L
    /** 档位金额的货币单位（空 = 不显示）—— 国外服主填 USD/$, 国内填 元 */
    private var currencyLabel = ""
    private val tiers = mutableListOf<PointsTierConfig>()
    private var loaded = false

    // ── 控件 ──
    private val toggleButtons = mutableMapOf<String, NineSliceButton>()
    private var qrField: TextFieldWidget? = null
    private var timeoutField: TextFieldWidget? = null
    private var currencyField: TextFieldWidget? = null
    private val tierEditButtons = mutableListOf<NineSliceButton>()
    private val tierDeleteButtons = mutableListOf<NineSliceButton>()
    private var addTierButton: NineSliceButton? = null
    private var saveButton: NineSliceButton? = null
    private var cancelButton: NineSliceButton? = null
    private var backButton: NineSliceButton? = null

    // ── 档位编辑弹窗 ──
    private var editingIndex = -1
    private var editing = false
    /** 保存成功的 toast 截止时刻（1.5 秒） */
    private var savedToastUntil = 0L
    /** 档位编辑弹窗里那一行红字：哪个字段不合法（null = 没错误） */
    private var editError: String? = null
    /** 上一次保存的结果（true = 成功）—— 决定底部提示是绿的还是红的 */
    private var saveResultOk = true
    private var editNameField: TextFieldWidget? = null
    private var editPriceField: TextFieldWidget? = null
    private var editPointsField: TextFieldWidget? = null
    private var editQrField: TextFieldWidget? = null
    private val editButtons = mutableListOf<NineSliceButton>()

    /** 输入框内容跨重建保留（resize / 弹窗开关都会重建控件） */
    private val pendingText = mutableMapOf<String, String>()

    private fun dialogW() = 300
    /** 高度要容得下：4 行固定项 +（最多约 7 个档位）+ 添加档位 + 底部双按钮 */
    private fun dialogH() = 300
    private fun dialogX() = width / 2 - dialogW() / 2
    private fun dialogY() = height / 2 - dialogH() / 2
    private fun listStartY() = dialogY() + 30

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
        shopEnabled = payload.shopEnabled
        payQr = payload.payQrImage
        timeoutMinutes = payload.orderTimeoutMinutes
        currencyLabel = payload.currencyLabel
        tiers.clear()
        tiers += payload.tiers
        loaded = true
        rebuild()
    }

    private fun rebuild() {
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
        addToggle("shop", shopEnabled) { shopEnabled = !shopEnabled; markDirtyAndRebuild() }

        // 收款码文件名（全局码；每个档位还可以在编辑弹窗里配自己的）
        qrField = TextFieldWidget(textRenderer, rightX - 130, y + 2, 152, 16, Text.literal(""))
        qrField?.setMaxLength(64)
        qrField?.setTextPredicate { it.none { c -> c == '/' || c == '\\' } }
        qrField?.setChangedListener { pendingText["qr"] = it }
        qrField?.text = pendingText["qr"] ?: payQr
        addSelectableChild(qrField); addDrawableChild(qrField)
        y += ROW_H

        // 待支付超时（分钟）
        timeoutField = TextFieldWidget(textRenderer, rightX - 60, y + 2, 82, 16, Text.literal(""))
        timeoutField?.setMaxLength(5)
        timeoutField?.setTextPredicate { it.all(Char::isDigit) }
        timeoutField?.setChangedListener { pendingText["timeout"] = it }
        timeoutField?.text = pendingText["timeout"] ?: timeoutMinutes.toString()
        addSelectableChild(timeoutField); addDrawableChild(timeoutField)
        y += ROW_H

        // 货币单位（纯展示，服主自己填「元」/「USD」；留空 = 金额不带单位）
        currencyField = TextFieldWidget(textRenderer, rightX - 60, y + 2, 82, 16, Text.literal(""))
        currencyField?.setMaxLength(16)
        currencyField?.setChangedListener { pendingText["currency"] = it }
        currencyField?.text = pendingText["currency"] ?: currencyLabel
        addSelectableChild(currencyField); addDrawableChild(currencyField)
        y += ROW_H + 4

        // 档位行：名称 / 价格 / 点数 + 编辑 / 删除
        tiers.forEachIndexed { index, tier ->
            val rowY = y + index * ROW_H
            addDrawableChild(
                NineSliceButton(rightX - 54, rowY, 26, 16, Text.translatable("cobblemarket.price_limit.edit"),
                    { openTierDialog(index) }).also { tierEditButtons += it }
            )
            addDrawableChild(
                NineSliceButton(rightX, rowY, 22, 16, Text.translatable("cobblemarket.price_limit.remove"),
                    { tiers.removeAt(index); markDirtyAndRebuild() }).also { tierDeleteButtons += it }
            )
        }
        y += tiers.size * ROW_H

        addTierButton = NineSliceButton(dx + 10, y, 70, 16, Text.translatable("cobblemarket.points.config.add_tier"),
            { openTierDialog(-1) })
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
        // 清掉下层控件的焦点，防 E 键保护误判（照各弹窗惯例）
        focused = null
        buildEditDialog()
    }

    private fun buildEditDialog() {
        // **弹窗打开时下层控件全部隐藏**（全模组弹窗惯例：不隐藏的话它们会透过遮罩显形、还能被点到）
        listOfNotNull(backButton, addTierButton, saveButton, cancelButton, qrField, timeoutField, currencyField)
            .forEach { it.visible = false }
        toggleButtons.values.forEach { it.visible = false }
        tierEditButtons.forEach { it.visible = false }
        tierDeleteButtons.forEach { it.visible = false }

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
        editQrField?.setChangedListener { pendingText["tier_qr"] = it }
        editQrField?.text = pendingText["tier_qr"] ?: ""
        addSelectableChild(editQrField); addDrawableChild(editQrField); y += ROW_H + 16 // 多留一行给错误提示

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
            qr = qr
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
                orderTimeoutMinutes = timeout,
                tiers = tiers.toList(),
                currencyLabel = pendingText["currency"] ?: currencyLabel
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
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        drawScreenDimMask(context, width, height)
        drawNineSlice(
            context, DIALOG_BACKGROUND_TEXTURE, dialogX(), dialogY(), dialogW(), dialogH(), 0,
            DIALOG_BACKGROUND_TEX_H
        )
        // 档位编辑弹窗的背景必须画在**控件之前**（放在 render 里会盖住它自己的输入框）
        if (editing) {
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
        val dx = dialogX()
        val centerX = width / 2
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.config.title").formatted(Formatting.GOLD),
            centerX, dialogY() + 14, 0xFFFFFF
        )

        var y = listStartY()
        fun label(key: String) {
            context.drawTextWithShadow(textRenderer, Text.translatable(key), dx + 10, y + 7, 0xFFFFFF)
            y += ROW_H
        }
        label("cobblemarket.points.config.points_enabled")
        label("cobblemarket.points.config.shop_enabled")
        label("cobblemarket.points.config.pay_qr")
        label("cobblemarket.points.config.timeout")
        label("cobblemarket.points.config.currency_label")
        y += 4
        // 档位行：名称 / 价格 / 点数（带 `*` = 该档配了专用收款码，文件名在编辑弹窗里看）
        tiers.forEach { tier ->
            val name = if (tier.qr.isBlank()) tier.name else tier.name + " *"
            context.drawTextWithShadow(
                textRenderer,
                Text.literal("$name  ${trimPrice(tier.priceCents)}  ${tier.points} RP"),
                dx + 10, y + 5, 0xFFFFFF
            )
            y += ROW_H
        }

        // 保存结果提示（弹窗底部居中）：成功绿、失败红（失败也要说，别只播一声 fail 音）
        if (System.currentTimeMillis() < savedToastUntil) {
            if (saveResultOk) {
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable("cobblemarket.points.config.saved").formatted(Formatting.GREEN),
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
        fun line(key: String) {
            context.drawTextWithShadow(textRenderer, Text.translatable(key), dx + 8, y + 5, 0xFFFFFF)
            y += ROW_H
        }
        line("cobblemarket.points.config.tier_name")
        line("cobblemarket.points.config.tier_price")
        line("cobblemarket.points.config.tier_points")
        line("cobblemarket.points.config.tier_qr")
        // 哪个字段不合法就直说 —— 只播一声 fail 音服主根本不知道该改哪（2026-09-18 用户反馈）
        editError?.let { key ->
            context.drawCenteredTextWithShadow(
                textRenderer, Text.translatable(key), dx + EDIT_W / 2, y, 0xFF5555
            )
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // ⚠ 点击空白失焦（含输入框的界面必做）：漏了点空白退不出输入态、E 键会被输入框吃掉
        val fields = listOfNotNull(
            qrField, timeoutField, currencyField,
            editNameField, editPriceField, editPointsField, editQrField
        )
        val wasInInput = fields.any { it === focused }
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && fields.none { it.isMouseOver(mouseX, mouseY) }) focused = null
        return result
    }

    private companion object {
        const val ROW_H = 20
        /** 档位编辑弹窗尺寸（4 行输入 + 标题 + 按钮） */
        const val EDIT_W = 220
        const val EDIT_H = 162
        // 开关图标沿用服务器配置界面那一对（不再新画）
        val ON_ICON = net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/switch_icon_on.png")
        val OFF_ICON = net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/switch_icon_off.png")
    }
}
