package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.CustomShopAdminCache
import com.shusheng.cobblemarket.client.NpcAvatarRenderer
import com.shusheng.cobblemarket.client.TooltipPlacement
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.playButtonClickSound
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.currency.CustomShopMode
import com.shusheng.cobblemarket.currency.CustomShopState
import com.shusheng.cobblemarket.currency.CustomShopType
import com.shusheng.cobblemarket.currency.DEFAULT_CLAIM_HOURS
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.customShopCurrencyText
import com.shusheng.cobblemarket.currency.customShopTypeText
import com.shusheng.cobblemarket.network.BindCustomShopNpcPayload
import com.shusheng.cobblemarket.network.CreateCustomShopPayload
import com.shusheng.cobblemarket.network.CustomShopAdminInfo
import com.shusheng.cobblemarket.network.CustomShopNearbyInfo
import com.shusheng.cobblemarket.network.CustomShopNpcInfo
import com.shusheng.cobblemarket.network.DeleteCustomShopPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.RenameCustomShopPayload
import com.shusheng.cobblemarket.network.RequestCustomShopAdminPayload
import com.shusheng.cobblemarket.network.RequestOpenCustomShopPayload
import com.shusheng.cobblemarket.network.SetCustomShopEnabledPayload
import com.shusheng.cobblemarket.network.SetCustomShopTypePayload
import com.shusheng.cobblemarket.network.UnbindCustomShopNpcPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/**
 * **自定义（NPC）商店管理界面**（2026-10-21）。
 *
 * 版式照 `LoanHistoryScreen` 逐项抄：面板 296、行高 24、列表从 48、底部预留 48、
 * 滚动指示器在 `height - 49`、悬停窗走 `TooltipPlacement`。
 *
 * 两个模式：
 * - **商店列表**：一行一家店；NPC 3D 头像 + 店名 + NPC 名；行尾「管理」按钮
 * - **选择 NPC**：附近 16 格内的实体列表；点未绑定 = 绑定，点已绑本店 = 解绑
 *
 * 弹窗：创建 / 改名 / 删除确认 / 商店详情。管理界面的写操作全部在服务端判 `points.shop`，
 * 这里只负责发请求与展示；结果走 `MarketResultPayload`（成功绿、失败红，聊天栏 + 弹窗红字）。
 *
 * ⚠ 管理端**不要求距离**（除绑定 NPC 要 ≤16 格）；绑定/解绑、改名、类型、开关、删除都不动钱。
 * ⚠ 3D 头像见 [NpcAvatarRenderer]：按实体类型画默认模型，自定义皮肤本期不传；
 *   宝可梦另带物种 + aspects（否则所有宝可梦是同一个随机小模型、名字全叫「宝可梦」）。
 */
class CustomShopAdminScreen : Screen(Text.translatable("cobblemarket.shop_admin.title")) {

    private val panelWidth = 296
    private val rowHeight = 24
    private val startY = 48

    private enum class Mode { LIST, PICK }
    private enum class Dialog { NONE, CREATE, RENAME, DELETE, DETAIL }
    private enum class Pending { CREATE, RENAME, TYPE, ENABLE, DELETE }

    private var mode = Mode.LIST
    private var dialog = Dialog.NONE
    private var pending: Pending? = null
    private var scrollOffset = 0
    private var hoveredRow = -1
    private var requested = false

    /** 已发出列表请求、还在等回包 —— 用来做 500ms 重试（服务端列表请求有节流，首包可能被静默丢弃） */
    private var waitingForList = false
    private var lastListRequestAt = 0L

    /** 选择 NPC 模式的目标商店 */
    private var selectedShopId = ""

    /** 详情 / 删除弹窗的目标商店 */
    private var detailShopId = ""

    /** 创建成功后自动进入「选择 NPC」模式 */
    private var pendingCreateId = ""

    // ── 表单状态（resize / 弹窗重建靠它们恢复）──
    private var createId = ""
    private var createName = ""
    private var createType = CustomShopType.BOTH
    private var createCurrency = MarketCurrency.PRIMARY
    private var createMode = CustomShopMode.NORMAL
    private var createHoursText = DEFAULT_CLAIM_HOURS.toString()
    private var renameName = ""
    private var dialogError: Text? = null

    private var createIdField: TextFieldWidget? = null
    private var createNameField: TextFieldWidget? = null
    private var createHoursField: TextFieldWidget? = null
    private var renameField: TextFieldWidget? = null

    private val rowButtons = mutableListOf<NineSliceButton>()
    private val dialogButtons = mutableListOf<NineSliceButton>()
    private var createTypeButtons = mutableListOf<Pair<CustomShopType, NineSliceButton>>()
    private var detailTypeButtons = mutableListOf<Pair<CustomShopType, NineSliceButton>>()
    private var createCurrencyButtons = mutableListOf<Pair<MarketCurrency, NineSliceButton>>()
    private var createModeButtons = mutableListOf<Pair<CustomShopMode, NineSliceButton>>()

    /** 「管理」按钮宽度：按当前语言量出来（英文 Manage 比中文宽） */
    private var manageW = 44

    private fun leftX(): Int = width / 2 - panelWidth / 2

    private fun maxVisible(): Int = maxOf(3, (height - 48 - startY) / rowHeight)

    private fun currentCount(): Int =
        if (mode == Mode.LIST) CustomShopAdminCache.shops.size else CustomShopAdminCache.nearby.size

    private fun selectedShopName(): String =
        CustomShopAdminCache.shop(selectedShopId)?.name ?: selectedShopId

    // ── 初始化 / 控件 ──

    override fun init() {
        super.init()
        // resize 会重建输入框；旧的焦点引用必须清掉，否则键入会落到已移除的控件上
        focused = null
        rowButtons.clear()
        dialogButtons.clear()
        createTypeButtons = mutableListOf()
        detailTypeButtons = mutableListOf()
        createCurrencyButtons = mutableListOf()
        createModeButtons = mutableListOf()
        // 控件已随 clearChildren 移除，旧引用不能留（否则失焦判定会拿已移除的输入框）
        createIdField = null
        createNameField = null
        createHoursField = null
        renameField = null
        manageW = maxOf(44, textRenderer.getWidth(Text.translatable("cobblemarket.shop_admin.manage").string) + 12)

        if (!requested) {
            sendToServer(RequestCustomShopAdminPayload())
            requested = true
            waitingForList = true
            lastListRequestAt = System.currentTimeMillis()
        }

        if (dialog != Dialog.NONE) {
            buildDialog()
            return
        }

        val left = leftX()
        addDrawableChild(NineSliceButton(
            left + panelWidth - 50, 18, 50, 16,
            Text.literal(""),
            {
                if (mode == Mode.PICK) backToList()
                else client?.setScreen(AdminScreen())
            },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        ))

        if (mode == Mode.LIST) {
            val createLabel = Text.translatable("cobblemarket.shop_admin.create")
            addDrawableChild(NineSliceButton(
                left + 2, 18, maxOf(60, textRenderer.getWidth(createLabel.string) + 16), 16,
                createLabel,
                { openCreateDialog() }
            ))
        }
        rebuildRowButtons()
    }

    private fun rebuildRowButtons() {
        rowButtons.forEach(::remove)
        rowButtons.clear()
        if (dialog != Dialog.NONE || mode != Mode.LIST) return
        val left = leftX()
        val shown = minOf(maxVisible(), maxOf(0, CustomShopAdminCache.shops.size - scrollOffset))
        CustomShopAdminCache.shops.drop(scrollOffset).take(shown).forEachIndexed { i, shop ->
            val y = startY + i * rowHeight
            val btn = NineSliceButton(
                left + panelWidth - 5 - manageW, y + 4, manageW, 16,
                Text.translatable("cobblemarket.shop_admin.manage"),
                { openDetail(shop.id) }
            )
            rowButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    // ── 模式 / 弹窗切换 ──

    private fun backToList() {
        mode = Mode.LIST
        scrollOffset = 0
        hoveredRow = -1
        focused = null
        clearChildren()
        init()
    }

    private fun openDetail(shopId: String) {
        detailShopId = shopId
        openDialog(Dialog.DETAIL)
    }

    private fun openCreateDialog() {
        createId = ""
        createName = ""
        createType = CustomShopType.BOTH
        createCurrency = MarketCurrency.PRIMARY
        createMode = CustomShopMode.NORMAL
        createHoursText = DEFAULT_CLAIM_HOURS.toString()
        openDialog(Dialog.CREATE)
    }

    private fun openRenameDialog() {
        renameName = CustomShopAdminCache.shop(detailShopId)?.name.orEmpty()
        openDialog(Dialog.RENAME)
    }

    private fun openDeleteDialog() {
        openDialog(Dialog.DELETE)
    }

    private fun openDialog(target: Dialog) {
        dialog = target
        dialogError = null
        pending = null
        hoveredRow = -1
        focused = null
        clearChildren()
        init()
    }

    private fun closeDialog() {
        dialog = Dialog.NONE
        dialogError = null
        pending = null
        focused = null
        clearChildren()
        init()
    }

    private fun openPicker(shopId: String) {
        selectedShopId = shopId
        mode = Mode.PICK
        scrollOffset = 0
        dialog = Dialog.NONE
        dialogError = null
        pending = null
        focused = null
        clearChildren()
        init()
    }

    // ── 弹窗控件 ──

    private fun dialogRect(kind: Dialog): IntArray {
        val w = when (kind) {
            Dialog.CREATE -> minOf(360, width - 40).coerceAtLeast(260)
            Dialog.RENAME -> minOf(320, width - 40).coerceAtLeast(240)
            Dialog.DELETE -> minOf(340, width - 40).coerceAtLeast(260)
            Dialog.DETAIL -> minOf(400, width - 40).coerceAtLeast(280)
            Dialog.NONE -> 0
        }
        val h = when (kind) {
            // ID / 名字 / 模式 / 出售类型 / 货币或领取周期 各一行
            Dialog.CREATE -> 250
            Dialog.RENAME -> 120
            Dialog.DELETE -> 130
            // 信息行变多（领取店多一行周期）时弹窗跟着长高，底部两排按钮才不会压到类型行
            Dialog.DETAIL -> maxOf(200, detailTypeRowY(0) + 80)
            Dialog.NONE -> 0
        }
        return intArrayOf((width - w) / 2, (height - h) / 2, w, h)
    }

    /**
     * 详情弹窗「类型」区的位置。
     *
     * 信息区行数不固定（领取店多一行周期；NPC 名单永远有一行）——
     * 写死 `dy + 106` 会让名单行压到按钮上（2026-10-23 用户截图报）。
     * 行高 11、文字高 8：最后一行底 = `30 + (lines-1)*11 + 8`，标签放在它下面 4px、按钮再下 12px。
     */
    private fun detailTypeLabelLocalY(): Int {
        val shop = CustomShopAdminCache.shop(detailShopId)
        val claim = shop != null && CustomShopMode.fromId(shop.mode) == CustomShopMode.CLAIM
        // ID / 状态 / 货币 / 模式 / [周期] / 商品 / NPC / 名单（或"未绑定"）
        val lines = 7 + (if (claim) 1 else 0)
        val infoBottom = 30 + (lines - 1) * 11 + 8
        return maxOf(94, infoBottom + 4)
    }

    /** 类型按钮那一行的 y（= 标签 y + 12） */
    private fun detailTypeRowY(dy: Int): Int = dy + detailTypeLabelLocalY() + 12

    private fun buildDialog() {
        val rect = dialogRect(dialog)
        val dx = rect[0]
        val dy = rect[1]
        val dw = rect[2]
        val dh = rect[3]
        val buttonY = dy + dh - 10 - 16

        when (dialog) {
            Dialog.CREATE -> {
                val fieldW = dw - 32
                createIdField = TextFieldWidget(textRenderer, dx + 16, dy + 46, fieldW, 16, Text.literal("")).apply {
                    setMaxLength(CustomShopState.MAX_NAME_LENGTH)
                    text = createId
                    setChangedListener { createId = it }
                    setPlaceholder(Text.translatable("cobblemarket.shop_admin.create.id_hint"))
                    addSelectableChild(this); addDrawableChild(this)
                }
                createNameField = TextFieldWidget(textRenderer, dx + 16, dy + 82, fieldW, 16, Text.literal("")).apply {
                    setMaxLength(CustomShopState.MAX_NAME_LENGTH)
                    text = createName
                    setChangedListener { createName = it }
                    setPlaceholder(Text.translatable("cobblemarket.shop_admin.create.name_hint"))
                    addSelectableChild(this); addDrawableChild(this)
                }
                // 模式（普通购买 / 定期领取）：**建店后不可改**
                buildModeButtons(dx + 16, dy + 118, dw - 32, 16, createMode) {
                    createMode = it
                    createModeButtons.forEach { (m, b) -> b.selectedVisual = m == it }
                    // 普通店要选货币、领取店要填周期 —— 两套控件常驻，只切可见性
                    updateCreateRowVisibility()
                }
                buildTypeButtons(dx + 16, dy + 154, dw - 32, 16, createType) {
                    createType = it
                    createTypeButtons.forEach { (t, b) -> b.selectedVisual = t == it }
                }
                buildCurrencyButtons(dx + 16, dy + 190, dw - 32, 16, createCurrency) {
                    createCurrency = it
                    createCurrencyButtons.forEach { (c, b) -> b.selectedVisual = c == it }
                }
                createHoursField = TextFieldWidget(textRenderer, dx + 16, dy + 190, dw - 32, 16, Text.literal("")).apply {
                    setMaxLength(5)
                    text = createHoursText
                    setChangedListener {
                        createHoursText = it.filter(Char::isDigit)
                        if (it != createHoursText) text = createHoursText
                    }
                    setPlaceholder(Text.translatable("cobblemarket.shop_admin.create.claim_hours_hint"))
                    addSelectableChild(this); addDrawableChild(this)
                }
                updateCreateRowVisibility()
                addDialogPairButtons(
                    dx, buttonY, dw,
                    Text.translatable("cobblemarket.shop_admin.create.confirm"), { sendCreate() },
                    Text.translatable("cobblemarket.shop_admin.cancel"), { closeDialog() }
                )
            }

            Dialog.RENAME -> {
                renameField = TextFieldWidget(textRenderer, dx + 16, dy + 46, dw - 32, 16, Text.literal("")).apply {
                    setMaxLength(CustomShopState.MAX_NAME_LENGTH)
                    text = renameName
                    setChangedListener { renameName = it }
                    setPlaceholder(Text.translatable("cobblemarket.shop_admin.create.name_hint"))
                    addSelectableChild(this); addDrawableChild(this)
                }
                addDialogPairButtons(
                    dx, buttonY, dw,
                    Text.translatable("cobblemarket.shop_admin.rename.confirm"), { sendRename() },
                    Text.translatable("cobblemarket.shop_admin.cancel"), { closeDialog() }
                )
            }

            Dialog.DELETE -> {
                addDialogPairButtons(
                    dx, buttonY, dw,
                    Text.translatable("cobblemarket.shop_admin.delete.confirm"), { sendDelete() },
                    Text.translatable("cobblemarket.shop_admin.cancel"), { closeDialog() }
                )
            }

            Dialog.DETAIL -> {
                val shop = CustomShopAdminCache.shop(detailShopId) ?: run { closeDialog(); return }
                val gap = 4
                val btnW = (dw - 32 - gap * 2) / 3
                val row1 = dy + dh - 10 - 16 - gap - 16
                // 类型三选一（放在信息区下方；信息行数会随「领取店 / 周期」变多，位置按行数让）
                buildTypeButtons(dx + 16, detailTypeRowY(dy), dw - 32, 14, CustomShopType.fromId(shop.type)) { sendType(it) }
                val actions = listOf<Pair<Text, () -> Unit>>(
                    Text.translatable("cobblemarket.shop_admin.detail.open") to { sendOpen() },
                    Text.translatable("cobblemarket.shop_admin.detail.bind") to { openPicker(detailShopId) },
                    Text.translatable("cobblemarket.shop_admin.detail.rename") to { openRenameDialog() },
                    Text.translatable(
                        if (shop.enabled) "cobblemarket.shop_admin.detail.disable"
                        else "cobblemarket.shop_admin.detail.enable"
                    ) to { sendEnabled(!shop.enabled) },
                    Text.translatable("cobblemarket.shop_admin.detail.delete") to { openDeleteDialog() },
                    Text.translatable("cobblemarket.shop_admin.detail.close") to { closeDialog() }
                )
                actions.forEachIndexed { i, (label, action) ->
                    val col = i % 3
                    val row = i / 3
                    val x = dx + 16 + col * (btnW + gap)
                    val y = row1 + row * (16 + gap)
                    val b = NineSliceButton(x, y, btnW, 16, label, onPress = { action() })
                    dialogButtons.add(b)
                    addDrawableChild(b)
                }
            }

            Dialog.NONE -> Unit
        }
    }

    /** 三选一类型按钮（创建 / 详情共用） */
    private fun buildTypeButtons(
        x: Int, y: Int, totalW: Int, h: Int, selected: CustomShopType,
        onPick: (CustomShopType) -> Unit
    ) {
        val gap = 4
        val btnW = (totalW - gap * 2) / 3
        val types = listOf(CustomShopType.POKEMON, CustomShopType.ITEM, CustomShopType.BOTH)
        val list = if (dialog == Dialog.CREATE) createTypeButtons else detailTypeButtons
        types.forEachIndexed { i, type ->
            val b = NineSliceButton(
                x + i * (btnW + gap), y, btnW, h,
                // ⚠ 按钮用短标签：英文 "Pokémon and items" 在 280 宽弹窗里放不下
                Text.translatable(
                    when (type) {
                        CustomShopType.POKEMON -> "cobblemarket.shop_admin.type.pokemon"
                        CustomShopType.ITEM -> "cobblemarket.shop_admin.type.item"
                        CustomShopType.BOTH -> "cobblemarket.shop_admin.type.both"
                    }
                ),
                { onPick(type) }
            )
            b.selectedVisual = type == selected
            list.add(type to b)
            dialogButtons.add(b)
            addDrawableChild(b)
        }
    }

    /** 创建弹窗的**商店模式**一行：普通购买 / 定期领取（建店后不可改） */
    private fun buildModeButtons(
        x: Int, y: Int, totalW: Int, h: Int, selected: CustomShopMode,
        onPick: (CustomShopMode) -> Unit
    ) {
        val gap = 4
        val btnW = (totalW - gap) / 2
        listOf(CustomShopMode.NORMAL, CustomShopMode.CLAIM).forEachIndexed { i, mode ->
            val b = NineSliceButton(
                x + i * (btnW + gap), y, btnW, h,
                Text.translatable(
                    if (mode == CustomShopMode.CLAIM) "cobblemarket.shop_admin.mode.claim"
                    else "cobblemarket.shop_admin.mode.normal"
                ),
                { onPick(mode) }
            )
            b.selectedVisual = mode == selected
            createModeButtons.add(mode to b)
            dialogButtons.add(b)
            addDrawableChild(b)
        }
    }

    /** 第 4 行两套控件（货币按钮 / 领取周期输入框）只切可见性，不重建界面 */
    private fun updateCreateRowVisibility() {
        val claim = createMode == CustomShopMode.CLAIM
        createCurrencyButtons.forEach { (_, b) -> b.visible = !claim }
        createHoursField?.visible = claim
    }

    /**
     * 创建弹窗的**货币类型**一行：两个按钮（主货币 / RP 图标）。
     *
     * ⚠ RP 按项目口径用**图标字形**，界面里不出现「RP」字样。
     * ⚠ 建店后只有空店能改币种（服务端 `/market shop currency`），创建这一步是主要入口。
     */
    private fun buildCurrencyButtons(
        x: Int, y: Int, totalW: Int, h: Int, selected: MarketCurrency,
        onPick: (MarketCurrency) -> Unit
    ) {
        val gap = 4
        val btnW = (totalW - gap) / 2
        listOf(MarketCurrency.PRIMARY, MarketCurrency.POINTS).forEachIndexed { i, currency ->
            val b = NineSliceButton(
                x + i * (btnW + gap), y, btnW, h,
                customShopCurrencyText(currency),
                { onPick(currency) }
            )
            b.selectedVisual = currency == selected
            createCurrencyButtons.add(currency to b)
            dialogButtons.add(b)
            addDrawableChild(b)
        }
    }

    private fun addDialogPairButtons(
        dx: Int, y: Int, dw: Int,
        confirm: Text, onConfirm: () -> Unit,
        cancel: Text, onCancel: () -> Unit
    ) {
        val w = 90
        val gap = 4
        val start = dx + (dw - w * 2 - gap) / 2
        val ok = NineSliceButton(start, y, w, 16, confirm, onPress = { onConfirm() })
        val no = NineSliceButton(start + w + gap, y, w, 16, cancel, onPress = { onCancel() })
        dialogButtons.add(ok); dialogButtons.add(no)
        addDrawableChild(ok); addDrawableChild(no)
    }

    // ── 发送操作 ──

    private fun sendCreate() {
        val id = createId.trim().lowercase()
        if (!CustomShopState.SHOP_ID_PATTERN.matches(id) || id == "main") {
            dialogError = Text.translatable("cobblemarket.shop.err.invalid_id")
            playFailSound()
            return
        }
        // 领取周期：空白 / 非法 → 24；超出范围直接在客户端拦一下（服务端还会再判）
        // ⚠ 普通店用不到周期字段 —— 一律发默认值，免得切回普通模式时带着刚才输的极端值
        val hours = if (createMode == CustomShopMode.CLAIM) {
            val typed = createHoursText.toIntOrNull() ?: DEFAULT_CLAIM_HOURS
            if (typed !in 1..com.shusheng.cobblemarket.currency.MAX_CLAIM_HOURS) {
                dialogError = Text.translatable("cobblemarket.shop.err.invalid_period", typed)
                playFailSound()
                return
            }
            typed
        } else DEFAULT_CLAIM_HOURS
        dialogError = null
        pending = Pending.CREATE
        pendingCreateId = id
        sendToServer(
            CreateCustomShopPayload(
                id, createName.trim(), createType.id, createCurrency.name, createMode.id, hours
            )
        )
    }

    private fun sendRename() {
        val name = renameName.trim()
        if (name.length > CustomShopState.MAX_NAME_LENGTH || name.contains('§')) {
            dialogError = Text.translatable("cobblemarket.shop.err.invalid_name")
            playFailSound()
            return
        }
        dialogError = null
        pending = Pending.RENAME
        sendToServer(RenameCustomShopPayload(detailShopId, name))
    }

    private fun sendType(type: CustomShopType) {
        dialogError = null
        pending = Pending.TYPE
        sendToServer(SetCustomShopTypePayload(detailShopId, type.id))
        detailTypeButtons.forEach { (t, b) -> b.selectedVisual = t == type }
    }

    private fun sendEnabled(enabled: Boolean) {
        dialogError = null
        pending = Pending.ENABLE
        sendToServer(SetCustomShopEnabledPayload(detailShopId, enabled))
    }

    private fun sendDelete() {
        dialogError = null
        pending = Pending.DELETE
        sendToServer(DeleteCustomShopPayload(detailShopId))
    }

    private fun sendOpen() {
        sendToServer(RequestOpenCustomShopPayload(detailShopId))
    }

    private fun bindNpc(shopId: String, npc: CustomShopNearbyInfo) {
        if (npc.boundShopId.isNotEmpty() && npc.boundShopId != shopId) {
            playFailSound()
            client?.player?.sendMessage(
                Text.translatable("cobblemarket.shop_admin.picker.already_bound", npc.boundShopName)
                    .formatted(Formatting.RED),
                false
            )
            return
        }
        if (npc.boundShopId == shopId) {
            playButtonClickSound()
            sendToServer(UnbindCustomShopNpcPayload(npc.uuid))
        } else {
            playButtonClickSound()
            sendToServer(BindCustomShopNpcPayload(shopId, npc.uuid))
        }
    }

    // ── 服务端回执 ──

    /**
     * 管理操作结果（`MarketResultPayload`；成功/失败都已在客户端统一播音效与聊天）。
     * 这里负责：成功时推进界面状态（创建完 → 选 NPC；其余 → 回列表），失败时把原因放进弹窗。
     */
    fun onResult(payload: MarketResultPayload) {
        val success = payload.success
        client?.player?.sendMessage(
            payload.message.copy().formatted(if (success) Formatting.GREEN else Formatting.RED),
            false
        )
        val action = pending
        pending = null
        if (!success) {
            // 创建 / 改名 / 删除 / 详情弹窗都留着，让服主改完重试；原因同时进了聊天栏
            when (dialog) {
                Dialog.CREATE, Dialog.RENAME, Dialog.DELETE, Dialog.DETAIL -> dialogError = payload.message
                else -> Unit
            }
            return
        }
        // 成功操作后服务端会紧跟一份新列表；万一丢了，tick 里的 500ms 重试能补上
        waitingForList = true
        lastListRequestAt = System.currentTimeMillis()
        when (action) {
            Pending.CREATE -> {
                val newId = pendingCreateId
                pendingCreateId = ""
                // 建完直接进「选 NPC」：创建 → 绑 NPC → 返回列表，是完整流程
                openPicker(newId)
            }
            Pending.RENAME, Pending.TYPE, Pending.ENABLE, Pending.DELETE -> closeDialog()
            null -> Unit
        }
    }

    /** 列表数据到达：重建行按钮；正在看的目标被删了就收掉弹窗 / 回列表 */
    fun onListData() {
        waitingForList = false
        if (dialog == Dialog.DETAIL && CustomShopAdminCache.shop(detailShopId) == null) {
            closeDialog()
            return
        }
        if (dialog == Dialog.DELETE && CustomShopAdminCache.shop(detailShopId) == null) {
            closeDialog()
            return
        }
        if (mode == Mode.PICK && CustomShopAdminCache.shop(selectedShopId) == null) {
            backToList()
            return
        }
        if (dialog == Dialog.NONE) rebuildRowButtons()
    }

    override fun tick() {
        super.tick()
        // 列表请求被服务端节流静默丢弃时，别让界面永远停在「加载中」——每 500ms 重发一次
        if (waitingForList && System.currentTimeMillis() - lastListRequestAt >= 500L) {
            sendToServer(RequestCustomShopAdminPayload())
            lastListRequestAt = System.currentTimeMillis()
        }
    }

    // ── 渲染 ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        MarketPanelBackground.draw(context, width, height)

        if (dialog != Dialog.NONE) {
            drawScreenDimMask(context, width, height)
            val rect = dialogRect(dialog)
            drawNineSlice(
                context, DIALOG_BACKGROUND_TEXTURE,
                rect[0], rect[1], rect[2], rect[3], 0, DIALOG_BACKGROUND_TEX_H
            )
            return
        }

        // 行背景（照 LoanHistoryScreen）
        val left = leftX()
        val count = currentCount()
        val shown = minOf(maxVisible(), maxOf(0, count - scrollOffset))
        hoveredRow = -1
        if (mouseX in left..(left + panelWidth) && mouseY in startY until (startY + shown * rowHeight)) {
            val row = (mouseY - startY) / rowHeight
            if (row in 0 until shown) hoveredRow = row
        }
        repeat(shown) { i ->
            drawNineSlice(
                context, ROW_BACKGROUND_TEXTURE, left, startY + i * rowHeight, panelWidth, rowHeight,
                if (i == hoveredRow) 1 else 0, ROW_BACKGROUND_TEX_H
            )
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // ⚠ 必须在最开头：宝可梦头像走 PokemonIconRenderer（头部跟随 / 点击叫声靠本帧的登记表）
        IconRegistry.beginFrame(mouseX, mouseY)
        super.render(context, mouseX, mouseY, delta)

        if (dialog != Dialog.NONE) {
            renderDialog(context, mouseX, mouseY, delta)
            return
        }

        val left = leftX()
        val title = if (mode == Mode.LIST) {
            Text.translatable("cobblemarket.shop_admin.title")
        } else {
            // 选择模式标题带店名（最长 32 字符）：截到不压右上角返回按钮
            TextUtil.truncateStyled(
                Text.translatable("cobblemarket.shop_admin.picker_title", selectedShopName()),
                panelWidth - 120
            )
        }
        context.drawCenteredTextWithShadow(
            textRenderer, title.copy().formatted(Formatting.GOLD), width / 2, 14, 0xFFFFFF
        )
        drawPanelDivider(context, width / 2, 41)

        if (!CustomShopAdminCache.loaded) {
            context.drawCenteredTextWithShadow(
                textRenderer, Text.translatable("cobblemarket.gui.loading"),
                width / 2, startY + 20, 0xAAAAAA
            )
            return
        }

        val count = currentCount()
        val maxVisible = maxVisible()
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, count - maxVisible))

        if (count == 0) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(
                    if (mode == Mode.LIST) "cobblemarket.shop_admin.empty"
                    else "cobblemarket.shop_admin.picker.empty"
                ).formatted(Formatting.GRAY),
                width / 2, startY + 20, 0xFFFFFF
            )
            return
        }

        if (mode == Mode.LIST) {
            val visible = CustomShopAdminCache.shops.drop(scrollOffset).take(maxVisible)
            visible.forEachIndexed { i, shop -> renderShopRow(context, shop, left, startY + i * rowHeight) }
            visible.forEachIndexed { i, shop ->
                val npc = displayNpc(shop)
                NpcAvatarRenderer.draw(
                    context, left + 4, startY + i * rowHeight + 2, 20,
                    npc?.typeId.orEmpty(), npc?.species.orEmpty(), npc?.aspects.orEmpty(), delta
                )
            }
            if (hoveredRow in visible.indices) {
                val shop = visible[hoveredRow]
                val btnX = left + panelWidth - 5 - manageW
                val avoid = TooltipPlacement.Rect(btnX, startY + hoveredRow * rowHeight + 4, manageW, 16)
                renderTooltip(context, shopTooltip(shop), mouseX, mouseY, avoid)
            }
        } else {
            val visible = CustomShopAdminCache.nearby.drop(scrollOffset).take(maxVisible)
            visible.forEachIndexed { i, npc -> renderNearbyRow(context, npc, left, startY + i * rowHeight) }
            visible.forEachIndexed { i, npc ->
                NpcAvatarRenderer.draw(
                    context, left + 4, startY + i * rowHeight + 2, 20,
                    npc.typeId, npc.species, npc.aspects, delta
                )
            }
            if (hoveredRow in visible.indices) {
                renderTooltip(context, nearbyTooltip(visible[hoveredRow]), mouseX, mouseY)
            }
        }

        if (count > maxVisible) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible, count)} / $count",
                width / 2, height - 49, 0x888888
            )
        }
    }

    /**
     * 行里用来显示头像/名字的 NPC：**优先选能解析出类型或名字的那个**。
     *
     * 旧绑定（加 `npcMeta` 之前绑的）可能是失效 UUID，排在 `npcUuids` 最前面；
     * 直接取 `first` 会让整行变成「未知实体」，即使后面还有能认出来的 NPC。
     */
    private fun displayNpc(shop: CustomShopAdminInfo): CustomShopNpcInfo? =
        shop.npcs.firstOrNull { it.typeId.isNotBlank() || it.customName.isNotBlank() }
            ?: shop.npcs.firstOrNull()

    /** 商店行：**一行**「店名 · NPC 名/类型 · +N · 状态」；右侧留给「管理」按钮 */
    private fun renderShopRow(context: DrawContext, shop: CustomShopAdminInfo, left: Int, y: Int) {
        val textX = left + 28
        val maxW = (left + panelWidth - 5 - manageW - 6 - textX).coerceAtLeast(10)
        val line = Text.literal("")
        line.append(Text.literal(shop.name).formatted(if (shop.enabled) Formatting.WHITE else Formatting.GRAY))
        line.append(Text.literal("  ·  ").formatted(Formatting.DARK_GRAY))

        val npc = displayNpc(shop)
        if (npc == null) {
            line.append(Text.translatable("cobblemarket.shop_admin.row.unbound").formatted(Formatting.GRAY))
        } else {
            if (npc.customName.isNotBlank()) {
                line.append(Text.literal(npc.customName).formatted(Formatting.GRAY))
                line.append(Text.literal(" · ").formatted(Formatting.DARK_GRAY))
                line.append(NpcAvatarRenderer.typeName(npc.typeId, npc.species, npc.aspects).copy().formatted(Formatting.DARK_GRAY))
            } else {
                line.append(NpcAvatarRenderer.typeName(npc.typeId, npc.species, npc.aspects).copy().formatted(Formatting.GRAY))
            }
            if (shop.npcCount > 1) {
                line.append(Text.literal(" +${shop.npcCount - 1}").formatted(Formatting.DARK_GRAY))
            }
        }
        if (!shop.enabled) {
            line.append(Text.literal(" · ").formatted(Formatting.DARK_GRAY))
            line.append(Text.translatable("cobblemarket.shop.state.closed").formatted(Formatting.RED))
        }
        context.drawTextWithShadow(
            textRenderer, TextUtil.truncateStyled(line, maxW), textX, y + 8, 0xFFFFFF
        )
    }

    /** 附近实体行：**一行**「名字 · 类型 · 距离 · 绑定状态」 */
    private fun renderNearbyRow(context: DrawContext, npc: CustomShopNearbyInfo, left: Int, y: Int) {
        val textX = left + 28
        val maxW = (left + panelWidth - 5 - textX).coerceAtLeast(10)
        val line = Text.literal("")
        if (npc.customName.isNotBlank()) {
            line.append(Text.literal(npc.customName).formatted(Formatting.WHITE))
            line.append(Text.literal(" · ").formatted(Formatting.DARK_GRAY))
            line.append(NpcAvatarRenderer.typeName(npc.typeId, npc.species, npc.aspects).copy().formatted(Formatting.GRAY))
        } else {
            // 没有自定义名时类型名就是名字，别重复显示两遍（宝可梦的物种名自带属性色，别再覆盖）
            line.append(NpcAvatarRenderer.typeName(npc.typeId, npc.species, npc.aspects))
        }
        line.append(Text.literal(" · ").formatted(Formatting.DARK_GRAY))
        line.append(Text.translatable("cobblemarket.shop_admin.picker.distance", npc.distance).formatted(Formatting.GRAY))
        when {
            npc.boundShopId == selectedShopId -> {
                line.append(Text.literal(" · ").formatted(Formatting.DARK_GRAY))
                line.append(Text.translatable("cobblemarket.shop_admin.picker.bound_this").formatted(Formatting.GREEN))
            }
            npc.boundShopId.isNotEmpty() -> {
                line.append(Text.literal(" · ").formatted(Formatting.DARK_GRAY))
                line.append(
                    Text.translatable("cobblemarket.shop_admin.picker.bound_other", npc.boundShopName)
                        .formatted(Formatting.GOLD)
                )
            }
        }
        context.drawTextWithShadow(
            textRenderer, TextUtil.truncateStyled(line, maxW), textX, y + 8, 0xFFFFFF
        )
    }

    // ── 悬停窗 ──

    private fun shopTooltip(shop: CustomShopAdminInfo): List<Text> {
        val status = Text.translatable(
            if (shop.enabled) "cobblemarket.shop.state.open" else "cobblemarket.shop.state.closed"
        ).formatted(if (shop.enabled) Formatting.GREEN else Formatting.RED)
        val lines = mutableListOf(
            Text.literal("").append(Text.literal(shop.name).formatted(Formatting.GOLD))
                .append(Text.literal("  ")).append(status),
            Text.translatable("cobblemarket.shop_admin.hover.id", shop.id).formatted(Formatting.GRAY),
            Text.translatable("cobblemarket.shop_admin.hover.type", customShopTypeText(CustomShopType.fromId(shop.type)))
                .formatted(Formatting.GRAY),
            Text.translatable(
                "cobblemarket.shop_admin.hover.currency",
                customShopCurrencyText(MarketCurrency.fromRequest(shop.currency) ?: MarketCurrency.PRIMARY)
            ).formatted(Formatting.GRAY),
            Text.translatable(
                "cobblemarket.shop_admin.hover.mode",
                Text.translatable(
                    if (CustomShopMode.fromId(shop.mode) == CustomShopMode.CLAIM) "cobblemarket.shop_admin.mode.claim"
                    else "cobblemarket.shop_admin.mode.normal"
                )
            ).formatted(Formatting.GRAY),
            Text.translatable("cobblemarket.shop_admin.hover.offers", shop.pokemonCount, shop.itemCount)
                .formatted(Formatting.GRAY),
            Text.translatable("cobblemarket.shop_admin.hover.npcs", shop.npcCount).formatted(Formatting.GRAY)
        )
        // 领取店补一行周期（普通店不显示，免得一行废话）
        if (CustomShopMode.fromId(shop.mode) == CustomShopMode.CLAIM) {
            lines += Text.translatable("cobblemarket.shop_admin.hover.period", shop.claimHours)
                .formatted(Formatting.GRAY)
        }
        if (shop.npcs.isEmpty()) {
            lines += Text.translatable("cobblemarket.shop_admin.row.unbound").formatted(Formatting.DARK_GRAY)
        } else {
            shop.npcs.take(6).forEach { npc ->
                val line = Text.literal("")
                if (npc.customName.isNotBlank()) {
                    line.append(Text.literal(npc.customName).formatted(Formatting.WHITE))
                    line.append(Text.literal(" (").formatted(Formatting.DARK_GRAY))
                    line.append(NpcAvatarRenderer.typeName(npc.typeId, npc.species, npc.aspects).copy().formatted(Formatting.GRAY))
                    line.append(Text.literal(")").formatted(Formatting.DARK_GRAY))
                } else {
                    // 没有自定义名时类型名就是名字，别写成「类型 (类型)」（宝可梦的物种名自带属性色）
                    line.append(NpcAvatarRenderer.typeName(npc.typeId, npc.species, npc.aspects))
                }
                lines += line
            }
            if (shop.npcCount > 6) {
                lines += Text.literal("+${shop.npcCount - 6}").formatted(Formatting.DARK_GRAY)
            }
        }
        return lines
    }

    private fun nearbyTooltip(npc: CustomShopNearbyInfo): List<Text> {
        val lines = mutableListOf<Text>()
        if (npc.customName.isNotBlank()) {
            lines += Text.literal(npc.customName).formatted(Formatting.WHITE)
            lines += Text.translatable(
                "cobblemarket.shop_admin.hover.npc_type",
                NpcAvatarRenderer.typeName(npc.typeId, npc.species, npc.aspects)
            ).formatted(Formatting.GRAY)
        } else {
            // 没有自定义名时类型名就是名字，不再重复一行「类型」（宝可梦的物种名自带属性色）
            lines += NpcAvatarRenderer.typeName(npc.typeId, npc.species, npc.aspects)
        }
        lines += Text.translatable("cobblemarket.shop_admin.picker.distance", npc.distance)
            .formatted(Formatting.GRAY)
        if (npc.boundShopId.isNotEmpty()) {
            lines += Text.translatable("cobblemarket.shop_admin.picker.bound_other", npc.boundShopName)
                .formatted(Formatting.GOLD)
        }
        return lines
    }

    /** 悬停窗（位置走 TooltipPlacement；骨架照 LoanHistoryScreen） */
    private fun renderTooltip(
        context: DrawContext, lines: List<Text>, mouseX: Int, mouseY: Int,
        avoid: TooltipPlacement.Rect? = null
    ) {
        // 小分辨率下长店名 / 长 NPC 名不能把悬停窗撑出屏幕（撑出去 TooltipPlacement 会直接不画）
        val maxLineWidth = (width - 20).coerceAtLeast(60)
        var maxWidth = 0
        lines.forEach { maxWidth = maxOf(maxWidth, minOf(textRenderer.getWidth(it), maxLineWidth)) }
        val padding = 4
        val tooltipHeight = lines.size * 10 + padding
        val (tx, ty) = TooltipPlacement.place(
            mouseX = mouseX, mouseY = mouseY,
            textW = maxWidth, textH = tooltipHeight, pad = padding,
            screenW = width, screenH = height, avoid = avoid
        ) ?: return
        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(
            context, ROW_BACKGROUND_TEXTURE,
            tx - padding, ty - padding, maxWidth + 2 * padding, lines.size * 10 + 2 * padding,
            1, ROW_BACKGROUND_TEX_H
        )
        lines.forEachIndexed { i, line ->
            context.drawTextWithShadow(
                textRenderer, TextUtil.truncateStyled(line, maxLineWidth), tx, ty + i * 10, 0xFFFFFF
            )
        }
        context.matrices.pop()
    }

    // ── 弹窗渲染 ──

    private fun renderDialog(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val rect = dialogRect(dialog)
        val dx = rect[0]
        val dy = rect[1]
        val dw = rect[2]
        val dh = rect[3]
        val centerX = dx + dw / 2

        when (dialog) {
            Dialog.CREATE -> {
                context.drawCenteredTextWithShadow(
                    textRenderer, Text.translatable("cobblemarket.shop_admin.create.title").formatted(Formatting.GOLD),
                    centerX, dy + 12, 0xFFFFFF
                )
                context.drawTextWithShadow(textRenderer, Text.translatable("cobblemarket.shop_admin.create.id"), dx + 16, dy + 34, 0xFFFFFF)
                context.drawTextWithShadow(textRenderer, Text.translatable("cobblemarket.shop_admin.create.name"), dx + 16, dy + 70, 0xFFFFFF)
                context.drawTextWithShadow(textRenderer, Text.translatable("cobblemarket.shop_admin.create.mode"), dx + 16, dy + 106, 0xFFFFFF)
                context.drawTextWithShadow(textRenderer, Text.translatable("cobblemarket.shop_admin.create.type"), dx + 16, dy + 142, 0xFFFFFF)
                // 第 4 行：普通店 = 货币类型；领取店 = 领取周期（小时）
                context.drawTextWithShadow(
                    textRenderer,
                    Text.translatable(
                        if (createMode == CustomShopMode.CLAIM) "cobblemarket.shop_admin.create.claim_hours"
                        else "cobblemarket.shop_admin.create.currency"
                    ),
                    dx + 16, dy + 178, 0xFFFFFF
                )
                drawDialogError(context, dx, dy + 212, dw)
            }
            Dialog.RENAME -> {
                context.drawCenteredTextWithShadow(
                    textRenderer, Text.translatable("cobblemarket.shop_admin.rename.title").formatted(Formatting.GOLD),
                    centerX, dy + 12, 0xFFFFFF
                )
                context.drawTextWithShadow(textRenderer, Text.translatable("cobblemarket.shop_admin.create.name"), dx + 16, dy + 34, 0xFFFFFF)
                drawDialogError(context, dx, dy + 70, dw)
            }
            Dialog.DELETE -> {
                val shop = CustomShopAdminCache.shop(detailShopId)
                context.drawCenteredTextWithShadow(
                    textRenderer, Text.translatable("cobblemarket.shop_admin.delete.title").formatted(Formatting.GOLD),
                    centerX, dy + 12, 0xFFFFFF
                )
                val name = shop?.name ?: detailShopId
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    TextUtil.truncateStyled(
                        Text.translatable("cobblemarket.shop_admin.delete.line1", name).formatted(Formatting.WHITE),
                        dw - 24
                    ),
                    centerX, dy + 36, 0xFFFFFF
                )
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    TextUtil.truncateStyled(
                        Text.translatable(
                            "cobblemarket.shop_admin.delete.line2",
                            (shop?.pokemonCount ?: 0) + (shop?.itemCount ?: 0),
                            shop?.npcCount ?: 0
                        ).formatted(Formatting.GRAY),
                        dw - 24
                    ),
                    centerX, dy + 50, 0xFFFFFF
                )
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable("cobblemarket.shop_admin.delete.warn").formatted(Formatting.RED),
                    centerX, dy + 66, 0xFFFFFF
                )
                drawDialogError(context, dx, dy + 82, dw)
            }
            Dialog.DETAIL -> {
                val shop = CustomShopAdminCache.shop(detailShopId) ?: return
                context.drawCenteredTextWithShadow(
                    textRenderer, Text.literal(shop.name).formatted(Formatting.GOLD),
                    centerX, dy + 12, 0xFFFFFF
                )
                // 大号头像
                val npc = displayNpc(shop)
                NpcAvatarRenderer.draw(
                    context, dx + 16, dy + 28, 44,
                    npc?.typeId.orEmpty(), npc?.species.orEmpty(), npc?.aspects.orEmpty(), delta
                )
                val infoX = dx + 70
                var ty = dy + 30
                context.drawTextWithShadow(
                    textRenderer, Text.translatable("cobblemarket.shop_admin.hover.id", shop.id),
                    infoX, ty, 0xAAAAAA
                ); ty += 11
                context.drawTextWithShadow(
                    textRenderer,
                    Text.translatable(
                        "cobblemarket.shop_admin.detail.status",
                        Text.translatable(
                            if (shop.enabled) "cobblemarket.shop.state.open" else "cobblemarket.shop.state.closed"
                        ).formatted(if (shop.enabled) Formatting.GREEN else Formatting.RED)
                    ),
                    infoX, ty, 0xFFFFFF
                ); ty += 11
                context.drawTextWithShadow(
                    textRenderer,
                    Text.translatable(
                        "cobblemarket.shop_admin.hover.currency",
                        customShopCurrencyText(MarketCurrency.fromRequest(shop.currency) ?: MarketCurrency.PRIMARY)
                    ),
                    infoX, ty, 0xFFFFFF
                ); ty += 11
                val claimMode = CustomShopMode.fromId(shop.mode) == CustomShopMode.CLAIM
                context.drawTextWithShadow(
                    textRenderer,
                    Text.translatable(
                        "cobblemarket.shop_admin.hover.mode",
                        Text.translatable(
                            if (claimMode) "cobblemarket.shop_admin.mode.claim"
                            else "cobblemarket.shop_admin.mode.normal"
                        )
                    ),
                    infoX, ty, 0xFFFFFF
                ); ty += 11
                if (claimMode) {
                    context.drawTextWithShadow(
                        textRenderer,
                        Text.translatable("cobblemarket.shop_admin.hover.period", shop.claimHours),
                        infoX, ty, 0xFFFFFF
                    ); ty += 11
                }
                context.drawTextWithShadow(
                    textRenderer,
                    Text.translatable("cobblemarket.shop_admin.hover.offers", shop.pokemonCount, shop.itemCount),
                    infoX, ty, 0xFFFFFF
                ); ty += 11
                context.drawTextWithShadow(
                    textRenderer,
                    Text.translatable("cobblemarket.shop_admin.hover.npcs", shop.npcCount),
                    infoX, ty, 0xFFFFFF
                ); ty += 11
                if (shop.npcs.isNotEmpty()) {
                    val names = Text.literal("")
                    shop.npcs.take(3).forEach { npc ->
                        if (names.string.isNotEmpty()) names.append(Text.literal(", ").formatted(Formatting.DARK_GRAY))
                        names.append(
                            NpcAvatarRenderer.displayName(npc.customName, npc.typeId, npc.species, npc.aspects)
                                .copy().formatted(Formatting.GRAY)
                        )
                    }
                    if (shop.npcCount > 3) names.append(Text.literal(" +${shop.npcCount - 3}").formatted(Formatting.DARK_GRAY))
                    context.drawTextWithShadow(
                        textRenderer,
                        TextUtil.truncateStyled(names, dw - 70 - 16),
                        infoX, ty, 0xFFFFFF
                    )
                } else {
                    context.drawTextWithShadow(
                        textRenderer, Text.translatable("cobblemarket.shop_admin.row.unbound").formatted(Formatting.GRAY),
                        infoX, ty, 0xFFFFFF
                    )
                }
                val typeRowY = detailTypeRowY(dy)
                context.drawTextWithShadow(
                    textRenderer, Text.translatable("cobblemarket.shop_admin.detail.type"),
                    dx + 16, typeRowY - 12, 0xFFFFFF
                )
                drawDialogError(context, dx, typeRowY + 22, dw)
            }
            Dialog.NONE -> Unit
        }
    }

    private fun drawDialogError(context: DrawContext, x: Int, y: Int, dw: Int) {
        dialogError?.let {
            context.drawTextWithShadow(
                textRenderer, TextUtil.truncateStyled(it, dw - 32), x + 16, y, 0xFF6666
            )
        }
    }

    // ── 输入 ──

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (dialog != Dialog.NONE) {
            val overField = dialogTextFields().any { it.isMouseOver(mouseX, mouseY) }
            super.mouseClicked(mouseX, mouseY, button)
            // 点输入框之外手动失焦（项目通用要求：否则 E 键被输入框吃掉、Esc 关不掉）
            if (focused != null && !overField) focused = null
            return true
        }

        val result = super.mouseClicked(mouseX, mouseY, button)
        if (!result && button == 0) {
            val row = rowAt(mouseX, mouseY)
            if (row >= 0) {
                if (mode == Mode.LIST) {
                    CustomShopAdminCache.shops.getOrNull(scrollOffset + row)?.let {
                        playButtonClickSound()
                        openDetail(it.id)
                    }
                } else {
                    CustomShopAdminCache.nearby.getOrNull(scrollOffset + row)?.let {
                        bindNpc(selectedShopId, it)
                    }
                }
                return true
            }
        }
        return result
    }

    private fun rowAt(mouseX: Double, mouseY: Double): Int {
        val left = leftX()
        if (mouseX < left || mouseX >= left + panelWidth) return -1
        if (mouseY < startY) return -1
        val row = ((mouseY - startY) / rowHeight).toInt()
        val shown = minOf(maxVisible(), maxOf(0, currentCount() - scrollOffset))
        return if (row in 0 until shown) row else -1
    }

    /**
     * 弹窗里**全部**输入框 —— 失焦判定（[mouseClicked]）唯一依据，别各列一份。
     *
     * ⚠ **新增弹窗输入框必须加进这里**：漏了的话，点它时 `super` 先把焦点给它、
     *   紧接着这段逻辑因为"点的不是输入框"又把焦点清掉 ⇒ **点了无法编辑**。
     *   2026-10-24 用户报的「创建商店的领取周期框点不了」就是漏了 [createHoursField]。
     */
    private fun dialogTextFields(): List<TextFieldWidget> =
        listOfNotNull(createIdField, createNameField, createHoursField, renameField)

    override fun mouseScrolled(
        mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double
    ): Boolean {
        if (dialog != Dialog.NONE) return true
        val max = maxOf(0, currentCount() - maxVisible())
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, max)
        rebuildRowButtons()
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            when {
                dialog != Dialog.NONE -> closeDialog()
                mode == Mode.PICK -> backToList()
                else -> return super.keyPressed(keyCode, scanCode, modifiers)
            }
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun shouldPause(): Boolean = false
}
