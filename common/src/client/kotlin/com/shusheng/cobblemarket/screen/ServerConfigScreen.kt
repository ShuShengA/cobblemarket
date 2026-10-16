package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.finance.CardNames
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.network.RequestServerConfigPayload
import com.shusheng.cobblemarket.network.SaveServerConfigPayload
import com.shusheng.cobblemarket.network.ServerConfigDataPayload
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
 * 服务器配置可视化编辑（入口界面「服务器配置」按钮，仅 OP 可达）。
 * 视觉照入口界面的玩家设置弹窗：遮罩 + 居中弹窗 + 左标签右控件 + switch_icon 开关 + done 按钮。
 * 打开时请求快照，数字输入框失焦提交、开关即时提交；服务端钳制后回发新快照刷新界面。
 * 货币配置不在此列（运行时切换账本错乱，需重启生效）；金融配置在独立区块内（上下分割线对）。
 */
class ServerConfigScreen : Screen(Text.translatable("cobblemarket.op.server_config")) {

    // 放宽到 360：文字可用宽 = 宽 − 100（≈260px），放得下英文原文（最长约 221px）；
    // 窗口过小时收进屏幕内，此时标签回落到 truncateString 兜底
    private val dialogW: Int get() = minOf(360, (width - 40).coerceAtLeast(200))
    private val rowHeight = 24

    /** 数字配置行定义：key → 是否整数（费率支持小数） */
    private data class NumDef(val labelKey: String, val isInt: Boolean)

    private val numDefs = listOf(
        NumDef("cobblemarket.op.scfg_pokemon_fee", false) to "pokemonFee",
        NumDef("cobblemarket.op.scfg_item_fee", false) to "itemFee",
        NumDef("cobblemarket.op.scfg_max_pokemon", true) to "maxPokemonListings",
        NumDef("cobblemarket.op.scfg_max_items", true) to "maxItemListings",
        NumDef("cobblemarket.op.scfg_listing_days", true) to "listingDays",
        NumDef("cobblemarket.op.scfg_pending_days", true) to "pendingDays",
        NumDef("cobblemarket.op.scfg_auction_fee", false) to "auctionFee",
        NumDef("cobblemarket.op.scfg_auction_min_bid", true) to "auctionMinBid",
        NumDef("cobblemarket.op.scfg_anti_snipe", true) to "antiSnipe",
        NumDef("cobblemarket.op.scfg_max_auctions", true) to "maxAuctions",
        NumDef("cobblemarket.op.scfg_auction_durations", false) to "auctionDurations",
        NumDef("cobblemarket.op.scfg_buyorder_fee", false) to "buyOrderFee",
        NumDef("cobblemarket.op.scfg_buyorder_days", true) to "buyOrderExpiry",
        NumDef("cobblemarket.op.scfg_max_buyorders", true) to "maxBuyOrders",
    )

    private val toggleDefs = listOf(
        "cobblemarket.op.scfg_egg_trading" to "eggTrading",
    )

    /**
     * ID 卡背景图那一行 —— **有意不放进 [numDefs]**：它不属于任何功能块（上架 / 拍卖 / 求购 / 金融），
     * 要排在**蛋交易之后**（用户 2026-10-16 要求它靠后）。
     * 界面的建控件 / 重排 / 刷新 / 渲染都靠「按调用顺序自增 row」⇒ 只要**调用顺序**对就行。
     */
    private val ID_CARD_DEF = NumDef("cobblemarket.op.scfg_id_card_bg", false)

    private val numFields = mutableMapOf<String, TextFieldWidget>()
    private val toggleButtons = mutableMapOf<String, NineSliceButton>()
    // 每个数字输入框后的重置按钮（恢复该行为服务端快照值）
    private val resetButtons = mutableMapOf<String, NineSliceButton>()
    private var saveButton: NineSliceButton? = null
    private var cancelButton: NineSliceButton? = null
    private var scrollOffset = 0
    // 编辑只改本地状态：开关的本地值（保存时提交，快照刷新时重置）
    private val localToggles = mutableMapOf<String, Boolean>()
    private var savedToastUntil = 0L
    // +3 = 喵喵银行配置入口行 + RP 配置入口行（各是一个左标签 + 右「配置」按钮）
    //       + ID 卡背景图行（它不在 numDefs 里，见 ID_CARD_DEF —— 排在蛋交易之后的那些行里最末）
    private val totalRows = numDefs.size + toggleDefs.size + 3
    private var financeOpenButton: NineSliceButton? = null
    private var pointsOpenButton: NineSliceButton? = null

    // ── 蛋交易二次确认弹窗（照 AdminScreen 原模板：开启有 3 秒冷静期，蛋可绕过精灵黑名单） ──
    private var eggConfirmOpen = false
    private var eggConfirmOpenedAt = 0L
    private var eggConfirmButton: NineSliceButton? = null
    private var eggCancelButton: NineSliceButton? = null
    // 确认弹窗关闭/resize 走 clearChildren+init 重建，未提交的编辑（输入框+开关）需保存/恢复
    private var savedFieldTexts: Map<String, String>? = null
    private var savedToggles: Map<String, Boolean>? = null

    private fun savePendingEdits() {
        savedFieldTexts = numFields.mapValues { it.value.text }
        savedToggles = localToggles.toMap()
    }

    // 弹窗几何：标题区 30 + 行区（滚动）+ 底部提示/done 区 34，总高不超过屏幕
    private fun dialogH() = minOf(height - 8, 30 + totalRows * rowHeight + 45)
    private fun dialogY() = height / 2 - dialogH() / 2
    private fun listStartY() = dialogY() + 30
    /** 内容区高度：底边停在提示行上方 3px。底部三道自底向上 —— 按钮 -30、提示行 -42、内容底 -45，各留 3px
     *  ⚠ 改这三处任意一处，都要同步改 dialogH() 的底部预留，否则弹窗高度与内容区对不上 */
    private fun listAreaH() = dialogH() - 30 - 45
    private fun getMaxVisibleRows() = maxOf(0, listAreaH() / rowHeight)

    companion object {
        /** 最新服务端快照（S2C 到达时更新；Screen 关闭后仍保留，重开可先显示旧值） */
        var latest: ServerConfigDataPayload? = null
            private set

        /** S2C 处理入口（CobbleMarketClient 调用） */
        fun onConfigData(payload: ServerConfigDataPayload) {
            latest = payload
            val screen = MinecraftClient.getInstance().currentScreen
            when (screen) {
                is ServerConfigScreen -> screen.refreshFrom(payload)
                is FinanceConfigScreen -> screen.refreshFrom(payload)
                is PurpleCardConfigScreen -> screen.refreshFrom(payload)
                is PurpleCardApplyConditionsScreen -> screen.refreshFrom(payload)
                is BlackCardConfigScreen -> screen.refreshFrom(payload)
                is BlackCardApplyConditionsScreen -> screen.refreshFrom(payload)
            }
        }
    }

    override fun init() {
        super.init()
        val dialogX = width / 2 - dialogW / 2
        val startY = listStartY()
        fun buildNumRow(def: NumDef, key: String) {
            // 输入框右缘钉死贴 ↺ 按钮左缘（间隙 2px），宽度一律**往左长**（见 ConfigFieldLayout）
            val isDurations = key == "auctionDurations"
            // ID 卡背景图是**文件名**（可能带中文 / 空格 / 点）⇒ 既不限字符集、也不限小数
            // ⚠ 只挡路径分隔符，其余一律交给服务端的 ConfigImage 校验（防路径穿越它自己会拦 + 记日志）
            val isFileName = key == "idCardBackground"
            val fieldW = ConfigFieldLayout.configFieldWidth(textRenderer, dialogW, key, def.labelKey)
            val field = TextFieldWidget(
                textRenderer, ConfigFieldLayout.configFieldRight(dialogX, dialogW) - fieldW,
                startY, fieldW, 16, Text.literal("")
            )
            field.setTextPredicate { text ->
                when {
                    isDurations -> text.all { it.isDigit() || it == ',' }
                    isFileName -> text.none { it == '/' || it == '\\' }
                    def.isInt -> text.all { it.isDigit() }
                    else -> text.all { it.isDigit() || it == '.' }
                }
            }
            field.setMaxLength(if (isDurations || isFileName) 60 else 10)
            numFields[key] = field
            addSelectableChild(field)
            addDrawableChild(field)
            // 重置按钮（↺ 符号，双语通用）：恢复该行为默认值，点下方「保存」统一提交（2026-09-05 拍板，不自动保存）
            val resetBtn = NineSliceButton(
                dialogX + dialogW - 10 - 20, startY, 20, 16,
                Text.literal("↺"),
                {
                    // 只填回默认值不提交：点下方「保存」统一生效（2026-09-05 拍板，重置按钮一律不自动保存）
                    numFields[key]?.text = when (key) {
                        // 拍卖时长重置 = 恢复默认档位（快照值可能已被服主改过，重置回快照等于没变）
                        "auctionDurations" -> "720,1440,2880,4320"
                        // ID 卡背景图重置 = 清空（= 用模组内置的那张）
                        "idCardBackground" -> ""
                        else -> snapshotText(key, null)
                    }
                    // 同上：光标回开头，长值才不会只显示尾巴
                    numFields[key]?.setCursor(0, false)
                }
            )
            resetButtons[key] = resetBtn
            addDrawableChild(resetBtn)
        }
        numDefs.forEach { (def, key) -> buildNumRow(def, key) }
        toggleDefs.forEach { (_, key) ->
            val btn = NineSliceButton(
                dialogX + dialogW - 10 - 22, startY, 22, 22,
                Text.literal(""),
                // 点击只切本地状态（乐观 UI），保存时才提交；蛋交易关→开需二次确认
                {
                    if (key == "eggTrading" && !currentToggleValue("eggTrading")) {
                        openEggConfirmDialog()
                    } else {
                        localToggles[key] = !currentToggleValue(key)
                        toggleButtons[key]?.iconLeft = toggleIconFor(key, null)
                    }
                },
                iconLeft = toggleIcon(key),
                iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            )
            toggleButtons[key] = btn
            addDrawableChild(btn)
        }
        // 底部双按钮：保存（提交全部 + 服务端落盘，等价改文件后 /market reload）、取消（放弃修改关闭）
        saveButton = NineSliceButton(
            width / 2 - 62, dialogY() + dialogH() - 30, 60, 20,
            Text.translatable("cobblemarket.op.scfg_save"),
            { save() }
        )
        addDrawableChild(saveButton)
        cancelButton = NineSliceButton(
            width / 2 + 2, dialogY() + dialogH() - 30, 60, 20,
            Text.translatable("cobblemarket.op.scfg_cancel"),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) }
        )
        addDrawableChild(cancelButton)
        // 喵喵银行配置入口行：列表末尾（标签「喵喵银行」+ 右侧「配置」按钮 → FinanceConfigScreen）
        financeOpenButton = NineSliceButton(
            dialogX + dialogW - 10 - 20 - 2 - 54, startY, 54, 16,
            Text.translatable("cobblemarket.op.finance_open"),
            { client?.setScreen(FinanceConfigScreen()) }
        )
        addDrawableChild(financeOpenButton)
        // RP 配置入口行（在喵喵银行下面；开关/档位/收款码都在那边改，不必碰配置文件）
        pointsOpenButton = NineSliceButton(
            dialogX + dialogW - 10 - 20 - 2 - 54, startY, 54, 16,
            Text.translatable("cobblemarket.op.finance_open"),
            { client?.setScreen(PointsConfigScreen()) }
        )
        addDrawableChild(pointsOpenButton)
        // ID 卡背景图：**排在蛋交易之后**（它不在 numDefs 里，见 ID_CARD_DEF）。
        // ⚠ 必须**建出控件**再调 rebuildPositions —— `placeNumField` 第一行就是
        //   `numFields[key] ?: return`，缺这一步会**占不到 row**、后面所有行整体上移一格；
        //   而且**不能放进下面那个 `if (savedFieldTexts != null)` 分支**里：
        //   那个分支只在「确认弹窗关闭 / resize 重建」时走，首次打开走的是 else（2026-10-16 实测栽过）
        buildNumRow(ID_CARD_DEF, "idCardBackground")
        rebuildPositions()
        // 确认弹窗关闭/resize 走 clearChildren+init 重建：恢复未提交的编辑，且不重新请求快照
        // （请求会把本地编辑的开关状态冲回服务端旧值——蛋交易确认后按钮图标变回关就是这个原因）
        if (savedFieldTexts != null) {
            savedFieldTexts!!.forEach { (key, text) -> numFields[key]?.text = text; numFields[key]?.setCursor(0, false) }
            savedToggles?.let { saved -> localToggles.clear(); localToggles.putAll(saved) }
            toggleDefs.forEach { (_, key) -> toggleButtons[key]?.iconLeft = toggleIconFor(key, null) }
            savedFieldTexts = null
            savedToggles = null
        } else {
            // 首次打开/普通重建：请求快照（服务端回发后 refreshFrom 填值）
            sendToServer(RequestServerConfigPayload())
        }
    }

    private fun rebuildPositions() {
        val dialogX = width / 2 - dialogW / 2
        val startY = listStartY()
        var row = 0
        // visible 必须叠加 !eggConfirmOpen：确认弹窗打开时任何重建（滚动/resize）都不能把下层控件改回可见
        // 行顺序：原数字配置 → 原开关 → 金融数字 → 金融开关（金融区块整体排在列表尾部）
        fun placeNumField(def: NumDef, key: String) {
            val y = startY + (row - scrollOffset) * rowHeight
            val field = numFields[key] ?: return
            val visible = !eggConfirmOpen && row in scrollOffset until scrollOffset + getMaxVisibleRows()
            // ⚠ x 与宽度必须和 init 用同一套算法：只改一处会让右缘戳出弹窗（2026-09-22 踩过）
            field.x = ConfigFieldLayout.configFieldX(textRenderer, dialogX, dialogW, key, def.labelKey)
            field.setWidth(ConfigFieldLayout.configFieldWidth(textRenderer, dialogW, key, def.labelKey))
            field.y = y + 4
            field.visible = visible
            val resetBtn = resetButtons[key]
            resetBtn?.x = dialogX + dialogW - 10 - 20
            resetBtn?.y = y + 4
            resetBtn?.visible = visible
            row++
        }
        fun placeToggle(key: String) {
            val y = startY + (row - scrollOffset) * rowHeight
            val btn = toggleButtons[key] ?: return
            btn.x = dialogX + dialogW - 10 - 22
            btn.y = y + 1
            btn.visible = !eggConfirmOpen && row in scrollOffset until scrollOffset + getMaxVisibleRows()
            row++
        }
        numDefs.forEach { (def, key) -> placeNumField(def, key) }
        toggleDefs.forEach { (_, key) -> placeToggle(key) }
        placeNumField(ID_CARD_DEF, "idCardBackground")
        // 喵喵银行配置入口行（列表末尾：标签「喵喵银行」+ 右侧「配置」按钮）
        val y = startY + (row - scrollOffset) * rowHeight
        val visible = !eggConfirmOpen && row in scrollOffset until scrollOffset + getMaxVisibleRows()
        financeOpenButton?.x = dialogX + dialogW - 10 - 20 - 2 - 54
        financeOpenButton?.y = y + 4
        financeOpenButton?.visible = visible
        // RP 配置入口行（紧挨着喵喵银行下面一行）
        row++
        val pointsY = startY + (row - scrollOffset) * rowHeight
        pointsOpenButton?.x = dialogX + dialogW - 10 - 20 - 2 - 54
        pointsOpenButton?.y = pointsY + 4
        pointsOpenButton?.visible = !eggConfirmOpen && row in scrollOffset until scrollOffset + getMaxVisibleRows()
    }

    // ── 快照 → 界面刷新 ──

    private fun refreshFrom(payload: ServerConfigDataPayload) {
        numDefs.forEach { (_, key) ->
            val field = numFields[key] ?: return@forEach
            // 聚焦中的输入框不覆盖（用户正在输入）；保存后回发的快照会刷新全部
            if (focused !== field) {
                field.text = when (key) {
                    "auctionDurations" -> payload.auctionDurations
                    "loanPlans" -> payload.loanPlans
                    "idCardBackground" -> payload.idCardBackground
                    else -> snapshotText(key, payload)
                }
                // ⚠ 设完值要把光标挪回开头：光标默认停在**末尾**，而框窄、只围绕光标显示一段，
                //   长值（拍卖档位 "720,1440,…"）就会只露出尾巴，看着像"配置被改了"（2026-09-22 用户报）
                field.setCursor(0, false)
            }
        }
        // ID 卡背景图那行**不在 numDefs 里**（见 ID_CARD_DEF），单独刷一次 ——
        // 判据同上：聚焦中的输入框不覆盖，光标挪回开头
        numFields["idCardBackground"]?.let { f ->
            if (focused !== f) {
                f.text = payload.idCardBackground
                f.setCursor(0, false)
            }
        }
        // 服务端快照到达：本地开关状态重置（保存回发 / 打开时首次填充）
        localToggles.clear()
        toggleDefs.forEach { (_, key) ->
            toggleButtons[key]?.iconLeft = toggleIconFor(key, payload)
        }
    }

    private fun snapshotText(key: String, payload: ServerConfigDataPayload?): String {
        val v = numValue(key, payload)
        // 小数用 BigDecimal 明文格式化：Double.toString 对极小值输出科学计数法（1.0E-4），
        // 输入框过滤器只允许数字+小数点，E/- 会被拒导致显示空
        return if (v == v.toLong().toDouble()) v.toLong().toString()
        else java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
    }

    private fun numValue(key: String, payload: ServerConfigDataPayload?): Double = when (key) {
        "pokemonFee" -> payload?.pokemonFee ?: 5.0
        "itemFee" -> payload?.itemFee ?: 5.0
        "maxPokemonListings" -> (payload?.maxPokemonListings ?: 0).toDouble()
        "maxItemListings" -> (payload?.maxItemListings ?: 0).toDouble()
        "listingDays" -> (payload?.listingDays ?: 14).toDouble()
        "pendingDays" -> (payload?.pendingDays ?: 30).toDouble()
        "auctionFee" -> payload?.auctionFee ?: 5.0
        "auctionMinBid" -> (payload?.auctionMinBid ?: 100).toDouble()
        "antiSnipe" -> (payload?.antiSnipe ?: 120).toDouble()
        "maxAuctions" -> (payload?.maxAuctions ?: 3).toDouble()
        "buyOrderFee" -> payload?.buyOrderFee ?: 5.0
        "buyOrderExpiry" -> (payload?.buyOrderExpiry ?: 3).toDouble()
        "maxBuyOrders" -> (payload?.maxBuyOrders ?: 5).toDouble()
        "creditRecent30" -> payload?.creditRecent30 ?: 0.5
        "creditHistory" -> payload?.creditHistory ?: 0.1
        "creditDebt" -> payload?.creditDebt ?: 0.3
        "creditMin" -> (payload?.creditMin ?: 0L).toDouble()
        "creditMax" -> (payload?.creditMax ?: 100_000L).toDouble()
        "creditCooldown" -> (payload?.creditCooldown ?: 24L).toDouble()
        "depositRate" -> payload?.dailyDepositRate ?: 0.0001
        "pairWindow" -> (payload?.tradePairWindowDays ?: 30L).toDouble()
        "pairMax" -> (payload?.tradePairMaxTrades ?: 3L).toDouble()
        "cardCount" -> (payload?.purpleCardCount ?: 20L).toDouble()
        "cardLimit" -> (payload?.purpleCardCreditLimit ?: 1_000_000L).toDouble()
        "ipDebtLimit" -> (payload?.ipDebtLimit ?: 100_000L).toDouble()
        "autoRepayMinBalance" -> (payload?.autoRepayMinBalance ?: 1_000L).toDouble()
        "overdueFeeDouble" -> (payload?.overdueFeeDouble ?: 7).toDouble()
        "overdueFreeze" -> (payload?.overdueFreeze ?: 14).toDouble()
        "overdueBadDebt" -> (payload?.overdueBadDebt ?: 30).toDouble()
        else -> 0.0
    }

    private fun currentToggleValue(key: String): Boolean {
        // 本地未保存的编辑值优先（否则连续点击算出同一个结果，开关只能点一次）
        localToggles[key]?.let { return it }
        val p = latest
        return when (key) {
            "eggTrading" -> p?.eggTrading ?: false
            "financeEnabled" -> p?.financeEnabled ?: false
            "cashLoan" -> p?.cashLoanEnabled ?: true
            "consumerLoan" -> p?.consumerLoanEnabled ?: true
            else -> false
        }
    }

    private fun toggleIcon(key: String): Identifier = toggleIconFor(key, null)

    private fun toggleIconFor(key: String, payload: ServerConfigDataPayload?): Identifier {
        // 优先级：回发快照值 > 本地未保存的编辑值 > 旧快照
        val on = payload?.let { p ->
            when (key) {
                "eggTrading" -> p.eggTrading
                "financeEnabled" -> p.financeEnabled
                "cashLoan" -> p.cashLoanEnabled
                "consumerLoan" -> p.consumerLoanEnabled
                else -> false
            }
        } ?: localToggles[key] ?: when (key) {
            "eggTrading" -> latest?.eggTrading ?: false
            "financeEnabled" -> latest?.financeEnabled ?: false
            "cashLoan" -> latest?.cashLoanEnabled ?: true
            "consumerLoan" -> latest?.consumerLoanEnabled ?: true
            else -> false
        }
        return Identifier.of("cobblemarket", if (on) "textures/gui/button/switch_icon_on.png" else "textures/gui/button/switch_icon_off.png")
    }

    // ── 保存 ──

    /** 保存：收集全部字段（输入框非法/空 → 回退服务端旧值），一次性提交；服务端落盘后回发快照 */
    private fun save() {
        fun intOr(key: String, fallback: Int): Int {
            val text = numFields[key]?.text.orEmpty()
            return text.toIntOrNull() ?: fallback
        }
        fun doubleOr(key: String, fallback: Double): Double {
            val text = numFields[key]?.text.orEmpty()
            return text.toDoubleOrNull() ?: fallback
        }
        fun longOr(key: String, fallback: Long): Long {
            val text = numFields[key]?.text.orEmpty()
            return text.toLongOrNull() ?: fallback
        }
        val p = latest
        sendToServer(SaveServerConfigPayload(
            pokemonFee = doubleOr("pokemonFee", p?.pokemonFee ?: 5.0),
            itemFee = doubleOr("itemFee", p?.itemFee ?: 5.0),
            maxPokemonListings = intOr("maxPokemonListings", p?.maxPokemonListings ?: 0),
            maxItemListings = intOr("maxItemListings", p?.maxItemListings ?: 0),
            listingDays = intOr("listingDays", p?.listingDays ?: 14),
            pendingDays = intOr("pendingDays", p?.pendingDays ?: 30),
            auctionFee = doubleOr("auctionFee", p?.auctionFee ?: 5.0),
            auctionMinBid = intOr("auctionMinBid", p?.auctionMinBid ?: 100),
            antiSnipe = intOr("antiSnipe", p?.antiSnipe ?: 120),
            maxAuctions = intOr("maxAuctions", p?.maxAuctions ?: 3),
            buyOrderFee = doubleOr("buyOrderFee", p?.buyOrderFee ?: 5.0),
            buyOrderExpiry = intOr("buyOrderExpiry", p?.buyOrderExpiry ?: 3),
            maxBuyOrders = intOr("maxBuyOrders", p?.maxBuyOrders ?: 5),
            eggTrading = localToggles["eggTrading"] ?: (p?.eggTrading ?: false),
            auctionDurations = numFields["auctionDurations"]?.text.orEmpty()
                .ifBlank { p?.auctionDurations ?: "" },
            // ⚠ **别照拍卖时长那样 ifBlank 回退旧值** —— 对背景图来说「空」是合法状态
            //   （= 用模组内置那张）。回退旧值会让服主**再也清不掉**已填的名字（2026-10-16 用户实测）
            idCardBackground = numFields["idCardBackground"]?.text.orEmpty(),
            // 金融字段全部回填快照原值（已迁移到 FinanceConfigScreen 编辑）
            financeEnabled = p?.financeEnabled ?: false,
            cashLoanEnabled = p?.cashLoanEnabled ?: true,
            consumerLoanEnabled = p?.consumerLoanEnabled ?: true,
            loanPlans = p?.loanPlans ?: "",
            creditRecent30 = p?.creditRecent30 ?: 0.5,
            creditHistory = p?.creditHistory ?: 0.1,
            creditDebt = p?.creditDebt ?: 0.3,
            creditMin = p?.creditMin ?: 0L,
            creditMax = p?.creditMax ?: 100_000L,
            creditCooldown = p?.creditCooldown ?: 24L,
            dailyDepositRate = p?.dailyDepositRate ?: 0.0001,
            tradePairWindowDays = p?.tradePairWindowDays ?: 30L,
            tradePairMaxTrades = p?.tradePairMaxTrades ?: 3L,
            purpleCardCount = p?.purpleCardCount ?: 20L,
            purpleCardCreditLimit = p?.purpleCardCreditLimit ?: 1_000_000L,
            purpleCardSelfApply = p?.purpleCardSelfApply ?: false,
            purpleCardApplyAsset = p?.purpleCardApplyAsset ?: 0L,
            purpleCardApplyVolume = p?.purpleCardApplyVolume ?: 0L,
            purpleCardApplyCredit = p?.purpleCardApplyCredit ?: 0L,
            purpleCardApplyDeposit = p?.purpleCardApplyDeposit ?: 0L,
            purpleCardApplyNoOverdue = p?.purpleCardApplyNoOverdue ?: false,
            purpleCardApplySeen = p?.purpleCardApplySeen ?: 0L,
            purpleCardApplyDex = p?.purpleCardApplyDex ?: 0L,
            purpleCardApplyFee = p?.purpleCardApplyFee ?: 0L,
            purpleCardRedoFee = p?.purpleCardRedoFee ?: 0L,
            purpleCardFeeDiscount = p?.purpleCardFeeDiscount ?: 0.0,
            // 黑卡字段回填快照（在 BlackCardConfigScreen 编辑）
            blackCardCount = p?.blackCardCount ?: 5L,
            blackCardCreditLimit = p?.blackCardCreditLimit ?: 5_000_000L,
            blackCardSelfApply = p?.blackCardSelfApply ?: false,
            blackCardApplyAsset = p?.blackCardApplyAsset ?: 0L,
            blackCardApplyVolume = p?.blackCardApplyVolume ?: 0L,
            blackCardApplyCredit = p?.blackCardApplyCredit ?: 0L,
            blackCardApplyDeposit = p?.blackCardApplyDeposit ?: 0L,
            blackCardApplyNoOverdue = p?.blackCardApplyNoOverdue ?: false,
            blackCardApplySeen = p?.blackCardApplySeen ?: 0L,
            blackCardApplyDex = p?.blackCardApplyDex ?: 0L,
            blackCardApplyFee = p?.blackCardApplyFee ?: 0L,
            blackCardRedoFee = p?.blackCardRedoFee ?: 0L,
            blackCardFeeDiscount = p?.blackCardFeeDiscount ?: 0.0,
            ipDebtLimit = p?.ipDebtLimit ?: 100_000L,
            autoRepayMinBalance = p?.autoRepayMinBalance ?: 1_000L,
            overdueFeeDouble = p?.overdueFeeDouble ?: 7,
            overdueFreeze = p?.overdueFreeze ?: 14,
            overdueBadDebt = p?.overdueBadDebt ?: 30,
        ))
        // 乐观提示（服务端回发快照确认最终值；保存后按钮侧 toast 1.5 秒）
        savedToastUntil = System.currentTimeMillis() + 1500
    }

    // ── 蛋交易二次确认弹窗（照 AdminScreen 原模板：3 秒冷静期） ──

    private fun setControlsVisible(visible: Boolean) {
        numFields.values.forEach { it.visible = visible }
        resetButtons.values.forEach { it.visible = visible }
        toggleButtons.values.forEach { it.visible = visible }
        saveButton?.visible = visible
        cancelButton?.visible = visible
    }

    private fun openEggConfirmDialog() {
        eggConfirmOpen = true
        eggConfirmOpenedAt = System.currentTimeMillis()
        // 保存未提交的编辑（关闭弹窗走 clearChildren+init 重建，init 里恢复）
        savePendingEdits()
        setControlsVisible(false)

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderEggConfirmBackground(context)
            }
        })

        val centerX = width / 2
        val dialogY = height / 2 - 75
        eggConfirmButton = NineSliceButton(
            centerX - 85, dialogY + 116, 80, 20,
            Text.translatable("cobblemarket.op.egg_confirm_yes"),
            { confirmEggTrading() }
        )
        addDrawableChild(eggConfirmButton)
        eggCancelButton = NineSliceButton(
            centerX + 5, dialogY + 116, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeEggConfirmDialog() }
        )
        addDrawableChild(eggCancelButton)
    }

    private fun closeEggConfirmDialog() {
        eggConfirmOpen = false
        eggConfirmButton = null
        eggCancelButton = null
        clearChildren()
        init()
    }

    private fun confirmEggTrading() {
        // 冷静期内点击：置灰按钮仍可点（dimmed 模式），播 fail 音效提示，不执行
        if (System.currentTimeMillis() - eggConfirmOpenedAt < 3000L) {
            playFailSound()
            return
        }
        // 只切本地状态（保存时才提交）；先更新保存快照再关闭——
        // 否则 init 恢复会用弹窗打开时的旧快照把刚确认的「开」覆盖回关
        localToggles["eggTrading"] = true
        savePendingEdits()
        closeEggConfirmDialog()
    }

    private fun renderEggConfirmBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogW = minOf(340, width - 40).coerceAtLeast(200)
        val dialogH = 150
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.op.egg_confirm_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)
    }

    private fun renderEggConfirmText(context: DrawContext) {
        val centerX = width / 2
        val dialogX = centerX - 140
        val dialogY = height / 2 - 75

        // 逐行渲染：语言文件显式分行（每行红/白两个槽位），红=警告、白=普通；空行跳过（中英行数不同）
        val lines = (1..9).map { i ->
            listOf(
                "cobblemarket.op.egg_l${i}_warn" to 0xFF5555,
                "cobblemarket.op.egg_l${i}_text" to 0xFFFFFF,
            )
        }
        var ty = dialogY + 32
        lines.forEach { line ->
            val segs = line.mapNotNull { (key, color) ->
                val text = Text.translatable(key).string
                if (text.isEmpty()) null else text to color
            }
            if (segs.isEmpty()) return@forEach
            val lineWidth = segs.sumOf { textRenderer.getWidth(it.first) }
            var tx = dialogX + 20 + (240 - lineWidth) / 2
            segs.forEach { (text, color) ->
                context.drawTextWithShadow(textRenderer, text, tx, ty, color)
                tx += textRenderer.getWidth(text)
            }
            ty += 9
        }
    }

    /** 冷静期：3 秒内确认按钮禁用并显示倒计时 */
    private fun updateEggConfirmButtons() {
        val cooldownLeft = 3 - (System.currentTimeMillis() - eggConfirmOpenedAt) / 1000
        val canConfirm = cooldownLeft <= 0
        eggConfirmButton?.dimmed = !canConfirm
        eggConfirmButton?.message = if (canConfirm)
            Text.translatable("cobblemarket.op.egg_confirm_yes")
        else
            Text.translatable("cobblemarket.op.egg_confirm_yes_countdown", cooldownLeft)
    }

    // ── 滚动 ──

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 点击空白处结束输入状态（照 AdminItemScreen 惯例：点前焦点在输入框、点击位置不在任何输入框 → 取消焦点）
        val wasInInput = focused is TextFieldWidget
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !numFields.values.any { it.isMouseOver(mouseX, mouseY) }) {
            focused = null
        }
        // ⭐ 滚动条：**排在最后**（控件 / 行优先）—— [ScrollBar] 的命中区左右各放宽了 3px，
        //   压在列表右缘那一两像素会被它截胡；`!result` = 控件已经吃下这一击时不再判。
        //   ⚠ 确认弹窗开着时滚动条不画（render 提前返回）⇒ 这里也不判，免得点到"看不见的条"
        //   （`totalRows > getMaxVisibleRows()` = 现在确实有得滚；装得下时它不画，几何还停在上一帧）
        if (!result && !eggConfirmOpen && button == 0 && totalRows > getMaxVisibleRows()) {
            ScrollBar.shared.mouseClicked(mouseX, mouseY, scrollOffset)?.let {
                scrollOffset = it
                // 行是**控件** ⇒ 位置得跟着新偏移重摆（同 [mouseScrolled]）
                rebuildPositions()
                return true
            }
        }
        return result
    }

    /** 拖动滚动条（见 [ScrollBar]）—— 本界面里**只有它**在用拖动 */
    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (!eggConfirmOpen) {
            ScrollBar.shared.mouseDragged(mouseY, scrollOffset)?.let {
                scrollOffset = it
                rebuildPositions()
                return true
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    /**
     * 松开：结束滚动条的拖动。
     *
     * ⚠ 结束时要**吃掉这次松开** —— 否则它会在别处又触发一次点击（松手那一刻鼠标往往正压着某个按钮）。
     */
    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (ScrollBar.shared.mouseReleased()) return true
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        // 确认弹窗打开时不滚动下层列表
        if (eggConfirmOpen) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        val maxScroll = maxOf(0, totalRows - getMaxVisibleRows())
        if (maxScroll > 0) {
            scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)
            rebuildPositions()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, totalRows - getMaxVisibleRows()))
        val wasEggConfirmOpen = eggConfirmOpen
        // 重建前保存未提交编辑（init 里恢复且不重新请求快照）
        savePendingEdits()
        super.resize(client, width, height)
        if (wasEggConfirmOpen) {
            eggConfirmOpen = false
            openEggConfirmDialog()
        }
    }

    // ── 渲染（遮罩 + 居中弹窗，照入口设置弹窗） ──

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        drawScreenDimMask(context, width, height)
        val dialogX = width / 2 - dialogW / 2
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY(), dialogW, dialogH(), 0, DIALOG_BACKGROUND_TEX_H)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        if (eggConfirmOpen) {
            renderEggConfirmText(context)
            updateEggConfirmButtons()
            return
        }
        val centerX = width / 2
        val dialogX = centerX - dialogW / 2
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.op.server_config").formatted(Formatting.GOLD),
            centerX, dialogY() + 14, 0xFFFFFF)

        val startY = listStartY()
        var row = 0
        // 行顺序与 rebuildPositions 一致：原数字 → 原开关 → 金融数字 → 金融开关
        fun drawRowLine(rowY: Int) {
            context.fill(dialogX + 6, rowY, dialogX + dialogW - 6, rowY + 1, 0xFF888888.toInt())
        }
        fun drawNumRow(def: NumDef, key: String) {
            if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
                val rowY = startY + (row - scrollOffset) * rowHeight
                // 行间分割线（照设置弹窗：每行上方一条）
                drawRowLine(rowY)
                context.drawTextWithShadow(textRenderer,
                    TextUtil.truncateStyled(
                        CardNames.label(def.labelKey),
                        ConfigFieldLayout.configLabelWidth(textRenderer, dialogW, key, def.labelKey)
                    ),
                    dialogX + 10, rowY + 7, 0xFFFFFF)
            }
            row++
        }
        fun drawToggleRow(labelKey: String) {
            if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
                val rowY = startY + (row - scrollOffset) * rowHeight
                drawRowLine(rowY)
                context.drawTextWithShadow(textRenderer,
                    CardNames.label(labelKey),
                    dialogX + 10, rowY + 7, 0xFFFFFF)
            }
            row++
        }
        numDefs.forEach { (def, key) -> drawNumRow(def, key) }
        toggleDefs.forEach { (labelKey, _) -> drawToggleRow(labelKey) }
        // ID 卡背景图：**排在蛋交易之后**（不在 numDefs 里）
        drawNumRow(ID_CARD_DEF, "idCardBackground")
        // 喵喵银行配置入口行（列表末尾）
        if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
            val rowY = startY + (row - scrollOffset) * rowHeight
            drawRowLine(rowY)
            context.drawTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.op.finance_entry"),
                dialogX + 10, rowY + 7, 0xFFFFFF
            )
        }
        row++
        // RP 配置入口行（喵喵银行下面）
        if (row in scrollOffset until scrollOffset + getMaxVisibleRows()) {
            val rowY = startY + (row - scrollOffset) * rowHeight
            drawRowLine(rowY)
            context.drawTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.config.entry", CurrencyService.pointsIcon()),
                dialogX + 10, rowY + 7, 0xFFFFFF
            )
        }
        row++
        // 底部提示 / 保存成功 toast（1.5 秒）
        if (System.currentTimeMillis() < savedToastUntil) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.op.scfg_saved").formatted(Formatting.GREEN),
                centerX, dialogY() + dialogH() - 42, 0xFFFFFF)
        } else {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.op.scfg_hint").formatted(Formatting.GRAY),
                centerX, dialogY() + dialogH() - 42, 0xFFFFFF)
        }
        renderScrollbar(context)
    }

    /** 右侧滚动条：内容超出一屏才画（否则玩家不知道下面还有东西），样式照入口界面的设置弹窗。
     *  贴弹窗右内缘：控件最右到 dialogW - 10、行分割线到 dialogW - 6，这里占 -5~-3 不重叠。
     *
     *  ⚠ 走共用的 [ScrollBar]（**可拖动** —— 原先只是画出来的两根竖条，按上去没反应）：
     *   几何由它记下来供拖动换算，**别在这儿自己 fill**（自己画的条拖不动）。
     *  ⚠ 三个参数**都是"行"**：本界面的 totalRows / getMaxVisibleRows / scrollOffset 本来就是行口径，
     *   直接喂即可（原来那套 px 换算交给它按比例算）；「装得下就不画」也由它自己判。
     */
    private fun renderScrollbar(context: DrawContext) {
        ScrollBar.shared.draw(
            context, width / 2 + dialogW / 2 - 5, listStartY(), listAreaH(),
            totalRows, getMaxVisibleRows(), scrollOffset
        )
    }

    /**
     * 界面以任何方式离开（返回按钮 / Esc / E / 关闭动画）都在这里收尾：放弃可能还在进行中的滚动条拖动。
     *
     * ⚠ 放 [removed] 而不是 `close()`：Esc 走动画时是 `setScreen(null)`，不经过 `close()`
     *   （同 BalanceHudPositionScreen 那条注释）。不清理会留个"半拖"状态 —— 滚动条是**单例**，
     *   下个界面里鼠标没按住也会跟着走。
     */
    override fun removed() {
        super.removed()
        ScrollBar.shared.cancel()
    }

    override fun shouldPause() = false
}
