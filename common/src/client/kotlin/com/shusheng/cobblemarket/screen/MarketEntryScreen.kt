package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.ClientPermissionCache
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.permission.RequestPermissionSnapshotPayload
import com.shusheng.cobblemarket.client.activeCurrencyUnit
import com.shusheng.cobblemarket.client.inlineCurrencyUnit

import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.client.BalanceHudMode
import com.shusheng.cobblemarket.client.IconAnimMode
import com.shusheng.cobblemarket.client.ClientConfig
import com.shusheng.cobblemarket.client.OakTips
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_TEXTURE
import com.shusheng.cobblemarket.client.requestCreditInfo
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.network.CreditInfoPayload
import com.shusheng.cobblemarket.network.FinanceStatsPayload
import com.shusheng.cobblemarket.network.RequestBalancePayload
import com.shusheng.cobblemarket.network.RequestFinanceStatsPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.lwjgl.opengl.GL11
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class MarketEntryScreen(private val skipDropAnim: Boolean = false) : Screen(Text.translatable("cobblemarket.entry.title")) {

    private var btnStartY = 0
    private var totalH = 0
    // 入口掉落动画开始时间（首次 init 记录；resize 重建不重置）。动画播完消失后才显示入口界面。
    // 从子界面返回入口（skipDropAnim=true）不播，只有按 K/smartphone 等外部打开才播
    private var dropAnimStart = if (skipDropAnim) 0L else -1L

    // 弹窗打开期间需要整体隐藏的下层控件（closeSettingsDialog 的 init 重建会恢复）
    private val entryButtons = mutableListOf<ClickableWidget>()
    private var settingsOpen = false
    private var settingsMarketButton: NineSliceButton? = null
    private var settingsAuctionButton: NineSliceButton? = null
    private var settingsItemMarketButton: NineSliceButton? = null
    private var settingsItemAuctionButton: NineSliceButton? = null
    private var settingsItemHoverPreviewButton: NineSliceButton? = null
    private var settingsCursorButton: NineSliceButton? = null
    private var settingsDropButton: NineSliceButton? = null
    private var settingsEntryDropButton: NineSliceButton? = null
    private var settingsPikachuLoopButton: NineSliceButton? = null
    private var settingsGroudonFlyButton: NineSliceButton? = null
    private var settingsBalanceHudButton: NineSliceButton? = null
    private var settingsBalanceHudPosButton: NineSliceButton? = null
    private var settingsIconAnimButton: NineSliceButton? = null
    private var settingsHoverPreviewButton: NineSliceButton? = null

    /** 设置弹窗的一行：左侧标签 + 右侧控件；[newGroup] = 与上一行之间画一条分隔线（同组的连续行之间不画）。
     *  [button] 为 null 时是本组的小标题行（只画标签、不可交互，但照常占一行高度）。
     *  行的顺序与数量只在 openSettingsDialog 里维护，y / 分隔线 / 标签位置全部由 settingsRows 推出 */
    private class SettingsRow(val label: Text, val newGroup: Boolean, val button: NineSliceButton?)

    private val settingsRows = mutableListOf<SettingsRow>()
    /** 设置弹窗内容的滚动偏移（px）：行数没超出一屏时恒为 0（滚不动） */
    private var settingsScroll = 0

    // 市场关闭提示（入口点击被拦截时显示，3 秒）
    private var closedNoticeUntil = 0L
    // 金融系统关闭提示（点置灰的喵喵银行按钮时显示，3 秒）
    private var financeNoticeUntil = 0L
    // 金融总开关（CreditInfoPayload 拉取；喵喵银行按钮置灰依据，未拉取时默认开=乐观，服务端兜底）
    private var financeEnabled = true
    private var meowthBankBtn: NineSliceButton? = null

    // RP 关闭提示（点置灰的RP 市场按钮时显示，3 秒）
    private var pointsNoticeUntil = 0L
    // RP 市场入口（置灰依据是 PointsStateCache.enabled，登录时服务端补发一次）
    private var pointsMarketBtn: NineSliceButton? = null

    /** 分享按钮与它上方展开的子按钮（点击主按钮切换展开，见 [toggleShareMenu]） */
    private var shareBtn: NineSliceButton? = null
    private var sharePokemonBtn: NineSliceButton? = null
    private var shareItemBtn: NineSliceButton? = null
    private var shareMenuOpen = false

    /** 展开子按钮期间被它压住的按钮：临时收起文字 + 在被覆盖的那块矩形里让路（见 [applyShareMenu]） */
    private val shareCoveredButtons = mutableListOf<TextureButton>()
    private var creditInfoLoaded = false
    // 全服累计成交额（批次 7 入口展示；仅氛围展示不参与任何计算）；
    // 初始读全局缓存（60 秒兜底轮询写入）秒显不闪，打开后仍发请求刷新
    private var totalVolume = com.shusheng.cobblemarket.client.FinanceCache.totalVolume
    private var statsLoaded = false

    // 大木博士知识点气泡：进入入口界面（新建实例）随机抽一句，一直显示；
    // 点击立绘主动换下一条；resize 重建不换句
    private var oakTipText: String? = null

    // 设置弹窗开关切换提示（1.5 秒 toast）
    private var settingsToastUntil = 0L
    private var settingsToastText: Text = Text.literal("")

    /** 金融开关快照（进入入口界面时拉取）：喵喵银行按钮置灰依据 + 写全局缓存供购买弹窗读取 */
    fun onCreditInfo(payload: CreditInfoPayload) {
        financeEnabled = payload.financeEnabled
        com.shusheng.cobblemarket.client.FinanceCache.financeEnabled = payload.financeEnabled
        com.shusheng.cobblemarket.client.FinanceCache.consumerLoanEnabled = payload.consumerLoanEnabled
        meowthBankBtn?.dimmed = !payload.financeEnabled
    }

    /** 金融统计快照（进入入口界面时拉取）：全服累计成交额展示 */
    fun onFinanceStats(payload: FinanceStatsPayload) {
        totalVolume = payload.totalVolume
    }

    /** 大额金额悬停提示：鼠标附近小面板显示完整千分位数字（照 AdminScreen） */
    private fun renderAmountTooltip(context: DrawContext, mx: Int, my: Int, fullText: String) {
        val pad = 4
        val w = textRenderer.getWidth(fullText) + 2 * pad
        val h = 14
        val tx = minOf(mx + 12, width - w - 4)
        val ty = maxOf(my - h - 4, 0)
        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx, ty, w, h, 1, ROW_BACKGROUND_TEX_H)
        context.drawTextWithShadow(textRenderer, fullText, tx + pad, ty + 3, 0xFFD700.toInt())
        context.matrices.pop()
    }

    /**
     * 市场总开关客户端门控：关闭时只提示，不进入任何交易界面。
     * opBypass=true 的入口（管理面板）对 OP 放行——关市期间服主仍需下架/黑名单/价格限制等管理能力。
     */
    /** 上次算出的「能否管理」：权限快照到达时若状态翻转，入口要多一行/少一行 —— 只能重建 */
    private var lastCanManage: Boolean? = null

    /** 收到权限快照时刷新入口布局（登录 / 授予 / 收回时由网络层调用） */
    fun refreshPermissionState() {
        val now = ClientPermissionCache.canManage()
        val changed = lastCanManage != null && lastCanManage != now
        lastCanManage = now
        if (changed) {
            clearChildren()
            init()   // 入口行数变了（多/少一个管理面板按钮），按钮位置全变，必须重建
        }
    }

    private fun openIfMarketEnabled(opBypass: Boolean = false, open: () -> Unit) {
        // ⚠ 判据是 canManage（= 有任一权限节点；服主由服务端下发全部节点），**不是 isServerOwner**。
        // 变量名曾写作 isOp，害我以为「关市时非 OP 的权限持有者进不去管理面板」（2026-09-17 改正）
        val canManage = ClientPermissionCache.canManage()
        if (com.shusheng.cobblemarket.client.MarketStateCache.enabled || (opBypass && canManage)) {
            open()
        } else {
            // 关市拦截：3 秒提示 + fail 音效（玩家点交易入口的即时反馈）
            com.shusheng.cobblemarket.client.playFailSound()
            closedNoticeUntil = System.currentTimeMillis() + 3000
        }
    }

    // 背景图底边（renderBackground 按内容高度居中计算；求购按钮贴此底边）
    private fun bgBottom(): Int {
        val contentTop = btnStartY - 14
        val contentBottom = btnStartY + totalH
        val bgTop = contentTop - (160 - (contentBottom - contentTop)) / 2
        return bgTop + 160
    }

    override fun init() {
        super.init()
        entryButtons.clear()
        // 每次重建（进界面/resize/关弹窗）都从收起状态开始：按钮是新造的，
        // 留着上一轮的展开标记会让子按钮与主按钮高亮对不上（被压住的旧按钮引用也一并丢弃）
        shareMenuOpen = false
        shareCoveredButtons.clear()
        if (dropAnimStart < 0) dropAnimStart = System.currentTimeMillis()
        sendToServer(RequestBalancePayload())
        // ⚠ 每次打开入口界面**主动要一次权限快照**（2026-09-24 用户实测补的）：
        //   `/op <玩家>` 是**原版命令**，模组感知不到、不会重推快照；而登录时那一次推送
        //   可能早于"被 OP"⇒ `canManage` 停在旧值，管理面板按钮不出现
        //   （表现为「给了 OP 也不显示，非要服主再给个节点才行」）。
        //   服务端有节流保护，请求被丢也无害（下次打开界面再要一次）
        sendToServer(RequestPermissionSnapshotPayload())
        // 大木博士知识点：按语言加载 json，每次进入入口界面（新建实例）随机抽一句；
        // 从子界面返回入口也是新建实例，同样换句；resize 重建走 init 但成员已缓存，不换句
        client?.let { OakTips.load(it.resourceManager, it.options.language) }
        if (oakTipText == null) {
            oakTipText = OakTips.randomTip()
        }
        val centerX = width / 2
        val btnW = 87
        val btnH = 24
        // 行距 8（2026-08-21 曾压到 5 更紧凑；背景放大后恢复 8 更舒展）
        val gap = 8
        val colGap = 2
        val totalW = btnW * 2 + colGap
        val leftX = centerX - totalW / 2
        val rightX = leftX + btnW + colGap

        // 管理面板入口：**有管理权限才显示**（没权限的玩家跟从前一样只有两行，入口按权限增减行）
        val canManage = ClientPermissionCache.canManage()
        // ⚠ **必须把这次画出来的状态记进 `lastCanManage`**：`refreshPermissionState()` 靠它判断
        //   「翻转了没有、要不要重建」。不记的话它初值恒为 null，判据 `lastCanManage != null && ...`
        //   永远为 false ⇒ **界面开着时被授权不会重建**，管理面板按钮一直不出现（2026-09-24 用户实测）
        lastCanManage = canManage
        val rowCount = if (canManage) 3 else 2
        // 行 1-2 用 gap 间隙；行 3 与行 2 保持 4px 间隙，不随 gap 增大下移（与分割线/开关按钮上下间隙对称各 2px）
        // ⚠ `- if (canManage) (gap - 5) else 0` 的 if 是**整体分支**（有权限时只减 3）。
        // 别写成 `- gap - 5`（那是减 13）：内容区矮 10px → 背景上移 → 分割线压到第 3 行按钮上（踩过）
        totalH = btnH * rowCount + gap * (rowCount - 1) - if (canManage) (gap - 5) else 0
        // 逻辑高度不足时整体上移：背景贴图（213 高）顶部比逻辑背景（160）再向上多 33px，
        // 贴图顶 = btnStartY - 14 - (160-totalH)/2 - 33，必须 ≥ 屏幕顶（精灵市场面板锚顶 y=2 从不裁）
        val startY = maxOf(height / 2 - totalH / 2, 47 + (160 - totalH) / 2)
        btnStartY = startY

        // 关市（紧急停市）：交易类入口置灰 —— 精灵/物品市场、拍卖场、求购单。
        // dimmed 只压暗观感、**不影响可点性**（见 TextureButton/NineSliceButton），点击仍走
        // openIfMarketEnabled 给 fail 音 + 3 秒红字提示（关市状态另有常驻红字横幅）。
        // ⚠ 设置与交易历史**不置灰也不拦**：设置全是玩家自己客户端的观感开关，交易历史只读账本，
        // 关市是拦交易、不是锁死整个界面（2026-09-16 用户拍板）
        val marketClosed = !com.shusheng.cobblemarket.client.MarketStateCache.enabled

        // 行 1：精灵市场按钮用小卡比兽 8 帧动画图标（100ms/帧，照皮卡丘动画帧率）
        entryButtons += addDrawableChild(TextureButton(
            leftX, startY, btnW, btnH,
            Text.translatable("cobblemarket.entry.pokemon"),
            { openIfMarketEnabled { client?.setScreen(MarketScreen()) } },
            iconFrames = (0..7).map { Identifier.of("cobblemarket", "textures/gui/munchlax/munchlax_$it.png") },
            iconTexSize = 48,
            iconDisplaySize = 18
        ).also { it.dimmed = marketClosed })
        entryButtons += addDrawableChild(TextureButton(
            rightX, startY, btnW, btnH,
            Text.translatable("cobblemarket.entry.item"),
            { openIfMarketEnabled { openItemMarket() } },
            Identifier.of("cobblemarket", "textures/gui/pokeball_icon.png"),
            iconTexSize = 48,
            iconDisplaySize = 18
        ).also { it.dimmed = marketClosed })
        // 行 2
        // 交易历史：不套 openIfMarketEnabled —— 只读账本，关市期间照常可查
        entryButtons += addDrawableChild(TextureButton(
            leftX, startY + btnH + gap, btnW, btnH,
            Text.translatable("cobblemarket.entry.history"),
            { client?.setScreen(HistoryScreen()) },
            Identifier.of("cobblemarket", "textures/gui/history_icon.png"),
            iconTexSize = 48,
            iconDisplaySize = 18
        ))
        entryButtons += addDrawableChild(TextureButton(
            rightX, startY + btnH + gap, btnW, btnH,
            Text.translatable("cobblemarket.entry.auction"),
            { openIfMarketEnabled { client?.setScreen(AuctionScreen()) } },
            Identifier.of("cobblemarket", "textures/gui/auction_gavel_left.png"),
            iconTexSize = 48,
            iconDisplaySize = 18
        ).also { it.dimmed = marketClosed })
        // 行 3：管理面板入口——纯图标按钮居中、悬停显示名称。
        // 2026-09-12：原文字「仅OP」是权限说明而非功能名（语义不对），去掉文字只留图标。
        // 宽度别压太窄：button.png 是胶囊形（两端半圆），TextureButton 整图缩放会把半圆横向压扁，
        // 22 宽时圆角只有 1.65×10 的竖尖角、非常难看；66 宽（圆角约 5×10）观感已接近缩窄前
        // 管理面板入口：**有管理权限才显示**（没权限的玩家看不到，布局与从前一致）
        if (canManage) {
            entryButtons += addDrawableChild(TextureButton(
                centerX - 33, startY + (btnH + gap) + btnH + 4, 66, btnH,
                Text.literal(""),
                { openIfMarketEnabled(opBypass = true) { client?.setScreen(AdminScreen()) } },
                Identifier.of("cobblemarket", "textures/gui/op_icon.png"),
                iconTexSize = 48,
                iconDisplaySize = 18,
                tooltip = Text.translatable("cobblemarket.op.title")
            ))
        }

        // 背景底部红线下的独立小按钮（纯图标，悬停显示名称）：**固定槽位制** ——
        // 槽位坐标与 `AdminScreen` **完全一致**（两界面切换时同一颗按钮不该左右跳）：
        //   左三槽由「中心槽左缘 − 3 个步长」倒推、右端槽贴红线右端，槽间 8px。
        // ⚠ 本界面**中心槽留空**（没有市场开关那样的中心按钮）—— 以后放「市场 ID 卡」
        //   （看自己的余额 / 图鉴收集度）就放这个位置：**其余的槽一个都不用动**，这正是固定槽位制的意义。
        val cornerSize = 22
        val cornerY = bgBottom() - cornerSize - 5
        val iconStep = cornerSize + 8                 // 槽位步长 = 按钮 + 8px 空隙
        val centerSlotX = centerX - cornerSize / 2    // 中心槽左缘（本界面空着，只用于定位）
        val leftGroupX = centerSlotX - iconStep * 3   // 左侧第一槽
        val rightGroupRight = centerX + DIVIDER_HALF  // 右侧槽的右缘（红线右端）
        val buyOrderX = leftGroupX
        val buyOrderY = cornerY
        val buyOrderBtn = NineSliceButton(
            buyOrderX, buyOrderY,
            cornerSize, cornerSize,
            Text.literal(""),
            { openIfMarketEnabled { client?.setScreen(BuyOrderScreen()) } },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/buy_order_icon.png"),
            // 48×48 贴图缩到 18×18 显示（按钮 22×22 留 2px 边距，源图高分辨率保持锐利，照市场总开关按钮）
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            // 入口型按钮专用底色（只有默认 + 悬停两段），与列表行里的操作按钮区分开
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S,
        )
        // 关市置灰（同上方交易类入口：仍可点，点击给 fail 音 + 3 秒红字提示）
        buyOrderBtn.dimmed = marketClosed
        // 纯图标按钮：悬停提示名称（无障碍与可读性）
        buyOrderBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.entry.buy_order")))
        entryButtons += addDrawableChild(buyOrderBtn)

        // 喵喵银行（金融系统入口）：求购单右侧、左组第二颗，所有玩家可见；组内空隙 8px
        // 不套 openIfMarketEnabled：关市期间看账/还款照常可用，界面内「新增借贷」按钮再按开关禁用（只拦新交易不卡资产）
        val meowthBankBtn = NineSliceButton(
            leftGroupX + iconStep, cornerY,
            cornerSize, cornerSize,
            Text.literal(""),
            {
                // 金融总开关关：置灰按钮点击给提示（dimmed 不影响可点性，见 NineSliceButton）
                if (financeEnabled) client?.setScreen(MeowthBankScreen())
                else {
                    com.shusheng.cobblemarket.client.playFailSound()
                    financeNoticeUntil = System.currentTimeMillis() + 3000
                }
            },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/meowth_bank_icon.png"),
            // 48×48 贴图缩到 18×18 显示（照求购单按钮）
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            // 入口型按钮专用底色（只有默认 + 悬停两段），与列表行里的操作按钮区分开
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S,
        )
        meowthBankBtn.dimmed = !financeEnabled
        this.meowthBankBtn = meowthBankBtn
        meowthBankBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.entry.meowth_bank")))
        entryButtons += addDrawableChild(meowthBankBtn)

        // RP 市场：喵喵银行右侧、左组第三颗（位置 2026-09-17 拍板，2026-09-25 随整行按红线重排）。
        // 总开关关闭时置灰 + 点击给 fail 音与 3 秒提示 —— 照喵喵银行按钮的做法
        //（dimmed 只压暗观感、不影响可点性，所以点击必须在这里自己拦）。
        val pointsBtn = NineSliceButton(
            leftGroupX + iconStep * 2, cornerY,
            cornerSize, cornerSize,
            Text.literal(""),
            {
                if (com.shusheng.cobblemarket.client.PointsStateCache.enabled) {
                    client?.setScreen(PointsMarketScreen())
                } else {
                    com.shusheng.cobblemarket.client.playFailSound()
                    pointsNoticeUntil = System.currentTimeMillis() + 3000
                }
            },
            iconLeft = POINTS_SYMBOL_TEXTURE,
            // 48×48 贴图缩到 18×18 显示（照求购单/喵喵银行按钮）
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            // 入口型按钮专用底色 —— ⚠ **别再改回 `ROW_BACKGROUND_TEXTURE`**：那是**列表行**的底色。
            // 这一排其余四颗（求购单 / 喵喵银行 / 设置 / 分享）用的都是 `BUTTON_TEXTURE_S`，
            // 只有这颗是孤例（用户 2026-09-25 指出；管理面板的「充值订单」按钮是同一个错，同批修掉）
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S,
        )
        pointsBtn.dimmed = !com.shusheng.cobblemarket.client.PointsStateCache.enabled
        this.pointsMarketBtn = pointsBtn
        pointsBtn.setTooltip(
            net.minecraft.client.gui.tooltip.Tooltip.of(
                Text.translatable("cobblemarket.points.market.entry", CurrencyService.pointsIcon())
            )
        )
        entryButtons += addDrawableChild(pointsBtn)

        if (!creditInfoLoaded) {
            requestCreditInfo()
            creditInfoLoaded = true
        }
        if (!statsLoaded) {
            sendToServer(RequestFinanceStatsPayload())
            statsLoaded = true
        }

        val settingsBtn = NineSliceButton(
            rightGroupRight - cornerSize, cornerY,
            cornerSize, cornerSize,
            Text.literal(""),
            // 设置：不套 openIfMarketEnabled —— 里面全是玩家自己客户端的观感开关（动画/光标/HUD
            // 位置等），与交易无关，关市不该连设置都进不去（2026-09-16 用户拍板）
            { openSettingsDialog() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/settings_icon.png"),
            // 48×48 贴图缩到 18×18 显示（照求购单按钮）
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            // 入口型按钮专用底色（只有默认 + 悬停两段），与列表行里的操作按钮区分开
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S,
        )
        settingsBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.entry.settings")))
        entryButtons += addDrawableChild(settingsBtn)

        // 分享按钮：设置按钮左边、右组第二颗（组内空隙 8px）；**点击**展开上方子按钮（用户 2026-09-17 拍板，
        // 原方案是悬停——点击能复用 NineSliceButton.selectedVisual 的选中态，还免掉悬停判定区连通的麻烦）
        val shareX = rightGroupRight - cornerSize - iconStep
        val shareBtn = NineSliceButton(
            shareX, cornerY,
            cornerSize, cornerSize,
            Text.literal(""),
            { toggleShareMenu() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/share.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            // 入口型按钮专用底色（只有默认 + 悬停两段），与列表行里的操作按钮区分开
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S
        )
        shareBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.entry.share")))
        this.shareBtn = shareBtn
        entryButtons += addDrawableChild(shareBtn)

        // 子按钮：**左右并排、彼此无间隙**，整组的分界线对准主按钮中心，整组紧贴主按钮上方
        //（用户 2026-09-17 定的形态）。图标沿用两个市场入口的（小卡比兽 / 精灵球），
        // 尺寸与主按钮一致（22×22 纯图标 + 悬停词条），不占宽度
        val subSize = cornerSize
        val subCenterX = shareX + cornerSize / 2
        // y：整组落在红色分割线**上方**，与主按钮相对分割线对称
        //（分割线在 bgBottom()-30，主按钮顶边距它 3px，子按钮底边同样距它 3px）
        val subY = cornerY - (subSize + 6)
        val pokemonBtn = NineSliceButton(
            subCenterX - subSize, subY, subSize, subSize,
            Text.literal(""),
            { openSharePokemon() },
            // 与精灵市场入口同一个会动的小卡比兽（8 帧，100ms/帧）
            iconFrames = (0..7).map { Identifier.of("cobblemarket", "textures/gui/munchlax/munchlax_$it.png") },
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            // 入口型按钮专用底色（只有默认 + 悬停两段），与列表行里的操作按钮区分开
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S
        )
        pokemonBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.share.pokemon")))
        pokemonBtn.visible = false
        this.sharePokemonBtn = pokemonBtn
        entryButtons += addDrawableChild(pokemonBtn)

        val itemBtn = NineSliceButton(
            subCenterX, subY, subSize, subSize,
            Text.literal(""),
            { openShareItem() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/pokeball_icon.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
            // 入口型按钮专用底色（只有默认 + 悬停两段），与列表行里的操作按钮区分开
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S
        )
        itemBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("cobblemarket.share.item")))
        itemBtn.visible = false
        this.shareItemBtn = itemBtn
        entryButtons += addDrawableChild(itemBtn)

        // 市场总开关 / 服务器配置两个 OP 按钮已移到管理面板（AdminScreen 底部，2026-09-12）
    }

    // ── 分享按钮的展开 / 收起 ──

    /** 点击分享按钮：切换上方子按钮的显示，主按钮同步进入选中态（持久高亮，不是按下反馈） */
    private fun toggleShareMenu() {
        shareMenuOpen = !shareMenuOpen
        applyShareMenu()
    }

    private fun collapseShareMenu() {
        if (!shareMenuOpen) return
        shareMenuOpen = false
        applyShareMenu()
    }

    /**
     * 展开/收起子按钮。
     *
     * 展开时子按钮是**浮层**：盖住下方的按钮是正常的（底图被压住看着就是被盖住了），
     * 但它盖不满整个按钮——下方按钮的字排在按钮中上部，会**露在覆盖区外面**，半截字比没有字更难看。
     * 所以与被压住的按钮相交时**只收起它的文字**（[TextureButton.hideText]），底图原样留着：
     * 视觉上就是「那个按钮被浮层盖住了」，收起后文字自动回来。
     * （2026-09-17 用户纠正：早先写的"整个按钮隐藏"太粗暴——按钮会凭空消失。）
     */
    private fun applyShareMenu() {
        shareBtn?.selectedVisual = shareMenuOpen
        sharePokemonBtn?.visible = shareMenuOpen
        shareItemBtn?.visible = shareMenuOpen
        if (shareMenuOpen) {
            val pokemonBtn = sharePokemonBtn ?: return
            val itemBtn = shareItemBtn ?: return
            val menuLeft = pokemonBtn.x
            val menuTop = pokemonBtn.y
            val menuRight = itemBtn.x + itemBtn.width
            val menuBottom = pokemonBtn.y + pokemonBtn.height
            entryButtons.forEach { btn ->
                // 只有带文字的入口按钮需要收字（求购单/喵喵银行/设置那几个是纯图标按钮，没有文字可露）
                val texBtn = btn as? TextureButton ?: return@forEach
                // 判据是「按钮**中心**落在子按钮横向范围内」+ 纵向有重叠，不是普通矩形相交：
                // 有管理权限的人布局多一行、背景随之上移，管理员面板按钮与子按钮在横向上只擦到
                // 约 2px——那是视觉上没被盖住，不该动它（2026-09-17 用户实测踩到）。
                // 真被盖住的那种（普通玩家布局下的「拍卖场」）中心稳稳落在范围内。
                val coveredHorizontally = texBtn.x + texBtn.width / 2 in menuLeft..menuRight
                val overlapsVertically = texBtn.y < menuBottom && texBtn.y + texBtn.height > menuTop
                if (coveredHorizontally && overlapsVertically && texBtn.blockedArea == null) {
                    shareCoveredButtons.add(texBtn)
                    texBtn.hideText = true
                    // 重叠区让给子按钮：MC 的点击分发按添加顺序，子按钮排在这个按钮后面，
                    // 不让路的话「点子上半截」会被这个按钮接走（见 TextureButton.blockedArea）
                    texBtn.blockedArea = intArrayOf(menuLeft, menuTop, menuRight, menuBottom)
                }
            }
        } else {
            shareCoveredButtons.forEach {
                it.hideText = false
                it.blockedArea = null
            }
            shareCoveredButtons.clear()
        }
    }

    private fun openSharePokemon() {
        client?.setScreen(SellSelectScreen(shareMode = true))
    }

    private fun openShareItem() {
        client?.setScreen(ItemSellScreen(shareMode = true))
    }

    // ── 设置弹窗（客户端个人观感开关；以后新开关统一加在这里） ──

    private fun openSettingsDialog() {
        settingsOpen = true
        settingsScroll = 0
        // 隐藏下层控件（弹窗打开期间不可交互；closeSettingsDialog 的 init 重建会恢复）
        entryButtons.forEach { it.visible = false }

        val centerX = width / 2

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderSettingsDialogBackground(context)
            }
        })

        // 行格式：左侧标签文字（统一左列 centerX-80）+ 右侧控件（开关图标 22×22 走 switchX、文字按钮 46×20 走 textBtnX，两者右缘都对齐 centerX+86）。
        // 新增设置项只需在这里按顺序加一行，y / 分隔线 / 标签位置都从行序推出（超过一屏时可滚动，见 layoutSettingsRows）
        val switchX = centerX + 64 // 右列 x：弹窗右缘(centerX+100) 留 14px 边距
        val textBtnX = centerX + 32
        settingsRows.clear()

        // 精灵庆祝动画：沿用旧字段——老配置里把它关掉的玩家，精灵侧原样保持关闭
        addSettingsHeading(Text.translatable("cobblemarket.settings.group_pokemon"))
        val marketBtn = makeSwitchButton(ClientConfig.celebrationOnMarketBuy) { toggleMarketAnimation() }
        settingsMarketButton = marketBtn
        addSettingsRow(marketBtn, switchX, toggleText("cobblemarket.settings.animation_market", ClientConfig.celebrationOnMarketBuy), newGroup = false)
        val auctionBtn = makeSwitchButton(ClientConfig.celebrationOnAuctionAndOrder) { toggleAuctionAnimation() }
        settingsAuctionButton = auctionBtn
        addSettingsRow(auctionBtn, switchX, toggleText("cobblemarket.settings.animation_auction", ClientConfig.celebrationOnAuctionAndOrder), newGroup = false)
        // 图标展示模式（两态循环：静态/动态，同 balanceHud 模板）与预览图也归精灵组
        val iconAnimBtn = NineSliceButton(
            0, 0, 54, 20,
            Text.translatable(iconAnimModeKey()),
            { cycleIconAnim() }
        )
        settingsIconAnimButton = iconAnimBtn
        addSettingsRow(iconAnimBtn, textBtnX, Text.translatable("cobblemarket.settings.icon_anim"), newGroup = false)
        val hoverPreviewBtn = makeSwitchButton(ClientConfig.hoverPreview) { toggleHoverPreview() }
        settingsHoverPreviewButton = hoverPreviewBtn
        addSettingsRow(hoverPreviewBtn, switchX, toggleText("cobblemarket.settings.hover_preview", ClientConfig.hoverPreview), newGroup = false)

        // 物品侧三项：庆祝动画两项 + 悬停预览图，都与精灵侧各自独立（市场直购已接入，拍卖/求购待接）
        addSettingsHeading(Text.translatable("cobblemarket.settings.group_item"))
        val itemMarketBtn = makeSwitchButton(ClientConfig.celebrationItemOnMarketBuy) { toggleItemMarketAnimation() }
        settingsItemMarketButton = itemMarketBtn
        addSettingsRow(itemMarketBtn, switchX, toggleText("cobblemarket.settings.animation_market", ClientConfig.celebrationItemOnMarketBuy), newGroup = false)
        val itemAuctionBtn = makeSwitchButton(ClientConfig.celebrationItemOnAuctionAndOrder) { toggleItemAuctionAnimation() }
        settingsItemAuctionButton = itemAuctionBtn
        addSettingsRow(itemAuctionBtn, switchX, toggleText("cobblemarket.settings.animation_auction", ClientConfig.celebrationItemOnAuctionAndOrder), newGroup = false)
        val itemHoverPreviewBtn = makeSwitchButton(ClientConfig.hoverPreviewItem) { toggleItemHoverPreview() }
        settingsItemHoverPreviewButton = itemHoverPreviewBtn
        addSettingsRow(itemHoverPreviewBtn, switchX, toggleText("cobblemarket.settings.hover_preview_item", ClientConfig.hoverPreviewItem), newGroup = false)

        val dropBtn = makeSwitchButton(ClientConfig.dropOverflowOnClaim) { toggleDropOverflow() }
        settingsDropButton = dropBtn
        addSettingsRow(dropBtn, switchX, toggleText("cobblemarket.settings.drop_overflow", ClientConfig.dropOverflowOnClaim))
        val entryDropBtn = makeSwitchButton(ClientConfig.marketAnimation) { toggleMarketAnim() }
        settingsEntryDropButton = entryDropBtn
        addSettingsRow(entryDropBtn, switchX, toggleText("cobblemarket.settings.animation_entry", ClientConfig.marketAnimation))
        val pikachuBtn = makeSwitchButton(ClientConfig.pikachuRunLoop) { togglePikachuLoop() }
        settingsPikachuLoopButton = pikachuBtn
        addSettingsRow(pikachuBtn, switchX, toggleText("cobblemarket.settings.pikachu_loop", ClientConfig.pikachuRunLoop))
        val groudonBtn = makeSwitchButton(ClientConfig.groudonFly) { toggleGroudonFly() }
        settingsGroudonFlyButton = groudonBtn
        addSettingsRow(groudonBtn, switchX, toggleText("cobblemarket.settings.groudon_fly", ClientConfig.groudonFly))

        // 余额 HUD 两项同组（之间不画分隔线）；三态循环文字按钮（一直/变动/关闭），点击循环切换
        val balanceHudBtn = NineSliceButton(
            0, 0, 54, 20,
            Text.translatable(balanceHudModeKey()),
            { cycleBalanceHud() }
        )
        settingsBalanceHudButton = balanceHudBtn
        addSettingsRow(balanceHudBtn, textBtnX, Text.translatable("cobblemarket.settings.balance_hud"))
        // 余额 HUD 位置：进拖动编辑界面（自定义 → 拖动落位 → 确定）
        val balanceHudPosBtn = NineSliceButton(
            0, 0, 54, 20,
            Text.translatable("cobblemarket.settings.balance_hud_position_custom"),
            { openBalanceHudPosEditor() }
        )
        settingsBalanceHudPosButton = balanceHudPosBtn
        addSettingsRow(balanceHudPosBtn, textBtnX, Text.translatable("cobblemarket.settings.balance_hud_position"), newGroup = false)

        // 自定义鼠标光标：放最下方、独立成组（用系统光标主题的玩家可以关掉）
        val cursorBtn = makeSwitchButton(ClientConfig.customCursor) { toggleCustomCursor() }
        settingsCursorButton = cursorBtn
        addSettingsRow(cursorBtn, switchX, toggleText("cobblemarket.settings.custom_cursor", ClientConfig.customCursor))

        // done 固定在底部，不参与滚动
        addDrawableChild(NineSliceButton(
            centerX - 28, settingsDialogY() + settingsDialogH() - SETTINGS_DONE_INSET, 56, 20,
            Text.translatable("cobblemarket.settings.done"),
            { closeSettingsDialog() }
        ))

        layoutSettingsRows()
    }

    private fun closeSettingsDialog() {
        settingsOpen = false
        settingsMarketButton = null
        settingsAuctionButton = null
        settingsItemMarketButton = null
        settingsItemAuctionButton = null
        settingsItemHoverPreviewButton = null
        settingsCursorButton = null
        settingsDropButton = null
        settingsEntryDropButton = null
        settingsPikachuLoopButton = null
        settingsGroudonFlyButton = null
        settingsBalanceHudButton = null
        settingsBalanceHudPosButton = null
        settingsIconAnimButton = null
        settingsHoverPreviewButton = null
        settingsRows.clear()
        settingsScroll = 0
        clearChildren()
        init()
    }

    /** 打开余额 HUD 位置编辑界面（拖动落位）：整个界面换掉，设置弹窗随之关闭；返回时新建入口界面 */
    private fun openBalanceHudPosEditor() {
        client?.setScreen(BalanceHudPositionScreen())
    }

    /** 开关行标签：只要标签本身，开关状态由右侧图标按钮表达（用户 2026-08-21 要求去掉开/关文字） */
    private fun toggleText(labelKey: String, on: Boolean): Text = Text.translatable(labelKey)

    /** 开关图标按钮：双态 switch_icon（与入口市场总开关同一套贴图），标签文字单独绘制 */
    private fun makeSwitchButton(initialOn: Boolean, action: () -> Unit): NineSliceButton {
        val b = NineSliceButton(
            0, 0, 22, 22,
            Text.literal(""),
            { action() },
            iconLeft = switchIconFor(initialOn),
            iconTexW = 48, iconTexH = 48, iconScale = 0.375f,
        )
        return b
    }

    private fun switchIconFor(on: Boolean): Identifier =
        Identifier.of("cobblemarket", if (on) "textures/gui/switch_icon_on.png" else "textures/gui/switch_icon_off.png")

    private fun toggleMarketAnimation() {
        ClientConfig.setCelebrationOnMarketBuy(!ClientConfig.celebrationOnMarketBuy)
        settingsMarketButton?.iconLeft = switchIconFor(ClientConfig.celebrationOnMarketBuy)
        showSettingsToast("cobblemarket.settings.animation_market", ClientConfig.celebrationOnMarketBuy)
    }

    private fun toggleAuctionAnimation() {
        ClientConfig.setCelebrationOnAuctionAndOrder(!ClientConfig.celebrationOnAuctionAndOrder)
        settingsAuctionButton?.iconLeft = switchIconFor(ClientConfig.celebrationOnAuctionAndOrder)
        showSettingsToast("cobblemarket.settings.animation_auction", ClientConfig.celebrationOnAuctionAndOrder)
    }

    /** 物品侧两个开关的标签与精灵侧共用词条（靠分组标题区分），故 toast 也用同一个 key */
    private fun toggleItemMarketAnimation() {
        ClientConfig.setCelebrationItemOnMarketBuy(!ClientConfig.celebrationItemOnMarketBuy)
        settingsItemMarketButton?.iconLeft = switchIconFor(ClientConfig.celebrationItemOnMarketBuy)
        showSettingsToast("cobblemarket.settings.animation_market", ClientConfig.celebrationItemOnMarketBuy)
    }

    private fun toggleItemAuctionAnimation() {
        ClientConfig.setCelebrationItemOnAuctionAndOrder(!ClientConfig.celebrationItemOnAuctionAndOrder)
        settingsItemAuctionButton?.iconLeft = switchIconFor(ClientConfig.celebrationItemOnAuctionAndOrder)
        showSettingsToast("cobblemarket.settings.animation_auction", ClientConfig.celebrationItemOnAuctionAndOrder)
    }

    private fun toggleItemHoverPreview() {
        ClientConfig.setHoverPreviewItem(!ClientConfig.hoverPreviewItem)
        settingsItemHoverPreviewButton?.iconLeft = switchIconFor(ClientConfig.hoverPreviewItem)
        showSettingsToast("cobblemarket.settings.hover_preview_item", ClientConfig.hoverPreviewItem)
    }

    private fun toggleCustomCursor() {
        ClientConfig.setCustomCursor(!ClientConfig.customCursor)
        settingsCursorButton?.iconLeft = switchIconFor(ClientConfig.customCursor)
        showSettingsToast("cobblemarket.settings.custom_cursor", ClientConfig.customCursor)
    }

    private fun toggleDropOverflow() {
        ClientConfig.setDropOverflowOnClaim(!ClientConfig.dropOverflowOnClaim)
        settingsDropButton?.iconLeft = switchIconFor(ClientConfig.dropOverflowOnClaim)
        showSettingsToast("cobblemarket.settings.drop_overflow", ClientConfig.dropOverflowOnClaim)
    }

    private fun toggleMarketAnim() {
        ClientConfig.setMarketAnimation(!ClientConfig.marketAnimation)
        settingsEntryDropButton?.iconLeft = switchIconFor(ClientConfig.marketAnimation)
        showSettingsToast("cobblemarket.settings.animation_entry", ClientConfig.marketAnimation)
    }

    private fun togglePikachuLoop() {
        ClientConfig.setPikachuRunLoop(!ClientConfig.pikachuRunLoop)
        settingsPikachuLoopButton?.iconLeft = switchIconFor(ClientConfig.pikachuRunLoop)
        showSettingsToast("cobblemarket.settings.pikachu_loop", ClientConfig.pikachuRunLoop)
    }

    private fun toggleGroudonFly() {
        ClientConfig.setGroudonFly(!ClientConfig.groudonFly)
        settingsGroudonFlyButton?.iconLeft = switchIconFor(ClientConfig.groudonFly)
        showSettingsToast("cobblemarket.settings.groudon_fly", ClientConfig.groudonFly)
    }

    private fun toggleHoverPreview() {
        ClientConfig.setHoverPreview(!ClientConfig.hoverPreview)
        settingsHoverPreviewButton?.iconLeft = switchIconFor(ClientConfig.hoverPreview)
        showSettingsToast("cobblemarket.settings.hover_preview", ClientConfig.hoverPreview)
    }

    private fun balanceHudModeKey(): String = when (ClientConfig.balanceHudMode) {
        BalanceHudMode.ALWAYS -> "cobblemarket.settings.balance_hud_always"
        BalanceHudMode.ON_CHANGE -> "cobblemarket.settings.balance_hud_change"
        BalanceHudMode.OFF -> "cobblemarket.settings.balance_hud_off"
    }

    private fun cycleBalanceHud() {
        ClientConfig.cycleBalanceHudMode()
        settingsBalanceHudButton?.message = Text.translatable(balanceHudModeKey())
        settingsToastText = Text.translatable("cobblemarket.settings.balance_hud")
            .append(Text.translatable(balanceHudModeKey()))
        settingsToastUntil = System.currentTimeMillis() + 1500
    }

    private fun iconAnimModeKey(): String = when (ClientConfig.iconAnimMode) {
        IconAnimMode.STATIC -> "cobblemarket.settings.icon_anim_static"
        IconAnimMode.FLOAT -> "cobblemarket.settings.icon_anim_dynamic"
    }

    private fun cycleIconAnim() {
        ClientConfig.cycleIconAnimMode()
        settingsIconAnimButton?.message = Text.translatable(iconAnimModeKey())
        settingsToastText = Text.translatable("cobblemarket.settings.icon_anim")
            .append(Text.translatable(iconAnimModeKey()))
        settingsToastUntil = System.currentTimeMillis() + 1500
    }

    /** 开关切换 toast：「标签 开/关」，1.5 秒后消失 */
    private fun showSettingsToast(labelKey: String, on: Boolean) {
        settingsToastText = Text.translatable(labelKey)
            .append(if (on) Text.translatable("cobblemarket.settings.on_state").formatted(Formatting.GREEN)
                else Text.translatable("cobblemarket.settings.off_state").formatted(Formatting.RED))
        settingsToastUntil = System.currentTimeMillis() + 1500
    }

    private fun renderSettingsDialogBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogX = centerX - DIALOG_W / 2
        val dialogY = settingsDialogY()

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, DIALOG_W, settingsDialogH(), 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.settings.title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF
        )
        // 分隔线：标题下一条固定；其余画在「新组首行」上方 2px（跟着行一起滚，被推出可视区就不画）。
        // 同组的连续行之间不画线（余额 HUD 两项、精灵图标两项各属一组）
        val lineX1 = centerX - 108
        val lineX2 = centerX + 108
        context.fill(lineX1, dialogY + SETTINGS_LINE_TOP, lineX2, dialogY + SETTINGS_LINE_TOP + 1, 0xFF888888.toInt())
        for (i in 1 until settingsRows.size) {
            if (!settingsRows[i].newGroup) continue
            val lineY = settingsRowY(i) - 2
            if (lineY <= dialogY + SETTINGS_LINE_TOP + 1 || lineY > dialogY + settingsViewBottomRel()) continue
            context.fill(lineX1, lineY, lineX2, lineY + 1, 0xFF888888.toInt())
        }
        renderSettingsScrollbar(context)
    }

    /** 设置弹窗的行标签（统一左列），位置与可见性跟右侧控件完全同步 */
    private fun renderSettingsLabels(context: DrawContext) {
        val centerX = width / 2
        for ((i, row) in settingsRows.withIndex()) {
            if (!settingsRowVisible(i)) continue
            context.drawTextWithShadow(
                textRenderer,
                row.label,
                centerX - 100, settingsRowY(i) + settingsRowHeight(i) / 2 - 4, 0xFFFFFF
            )
        }
    }

    // ── 设置弹窗布局（行数据驱动：y / 可见性 / 分隔线 / 滚动共用同一套算法） ──

    /**
     * 设置弹窗高度：设计值 [DIALOG_H]，**但不超过屏幕**。
     *
     * ⚠ 2026-09-19 用户报（**1.2.0 起就有**）：GUI scale 调成自动时逻辑高只有两百多，
     *   写死的 324 直接顶出屏幕、上下都被吞。黄金模板是**服务器配置界面**那一套：
     *   `min(设计高, 屏幕高 − 8)` + 居中 + 内容可滚动（本弹窗本来就能滚，见 [settingsMaxScroll]）。
     */
    private fun settingsDialogH(): Int = minOf(DIALOG_H, height - 8)

    /**
     * 设置弹窗顶边：默认与**背景中心**对齐（背景中心 = `bgBottom − 87`，位置与原来一致），
     * 再**钳进屏幕内** —— 弹窗被屏幕压矮时，中心对齐算出来的位置本身就可能越界。
     */
    private fun settingsDialogY(): Int {
        val h = settingsDialogH()
        return (bgBottom() - 87 - h / 2).coerceIn(4, (height - 4 - h).coerceAtLeast(4))
    }

    /** 内容可视区底（相对弹窗顶）：done 之上 [SETTINGS_VIEW_BOTTOM_INSET] —— 原来写死 287，弹窗矮了就得出界 */
    private fun settingsViewBottomRel(): Int = settingsDialogH() - SETTINGS_VIEW_BOTTOM_INSET

    /** 登记一个设置行：x 固定；y 与可见性由 [layoutSettingsRows] 按滚动偏移统一给 */
    private fun addSettingsRow(button: NineSliceButton, x: Int, label: Text, newGroup: Boolean = true) {
        button.x = x
        settingsRows += SettingsRow(label, newGroup, button)
        addDrawableChild(button)
    }

    /** 登记一个分组小标题行：没有控件，只画一行金色标签（如「精灵」「物品」），照常占一行高度 */
    private fun addSettingsHeading(label: MutableText) {
        settingsRows += SettingsRow(label.formatted(Formatting.GOLD), newGroup = true, button = null)
    }

    /** 行高：标题行没有控件，按控件行统一高 22 算，保证行步进对齐 */
    private fun settingsRowHeight(index: Int): Int = settingsRows[index].button?.height ?: 22

    private fun settingsRowY(index: Int): Int =
        settingsDialogY() + SETTINGS_ROWS_TOP + index * SETTINGS_ROW_STEP - settingsScroll

    /**
     * 整行是否完整落在内容可视区里——越界的行整行隐藏（渲染与点击一并失效）。
     * 不用 enableScissor 裁剪：控件是 children，由 super.render() 渲染，而它内部会先画背景板，
     * 一裁就把入口背景一起裁掉。故以「整行进/出」代替像素级裁剪，代价是滚动时上下边缘最多空出一行。
     */
    private fun settingsRowVisible(index: Int): Boolean {
        val dialogY = settingsDialogY()
        val y = settingsRowY(index)
        return y >= dialogY + SETTINGS_ROWS_TOP && y + settingsRowHeight(index) <= dialogY + settingsViewBottomRel()
    }

    /** 按当前滚动偏移摆放所有行（打开弹窗 / 滚轮 / resize 后调用）；标题行没有控件，只由标签渲染跟随 */
    private fun layoutSettingsRows() {
        for ((i, row) in settingsRows.withIndex()) {
            val button = row.button ?: continue
            button.y = settingsRowY(i)
            button.visible = settingsRowVisible(i)
        }
    }

    /** 内容总高比可视区高出多少像素（0 = 没超出，滚不动） */
    private fun settingsMaxScroll(): Int {
        if (settingsRows.isEmpty()) return 0
        val contentH = (settingsRows.size - 1) * SETTINGS_ROW_STEP + 22
        return maxOf(0, contentH - (settingsViewBottomRel() - SETTINGS_ROWS_TOP))
    }

    /** 右侧滚动条：只有内容超出一屏才画（否则玩家不知道下面还有东西） */
    private fun renderSettingsScrollbar(context: DrawContext) {
        val maxScroll = settingsMaxScroll()
        if (maxScroll <= 0) return
        val dialogY = settingsDialogY()
        val x = width / 2 + DIALOG_W / 2 - 5
        val top = dialogY + SETTINGS_ROWS_TOP
        val trackH = settingsViewBottomRel() - SETTINGS_ROWS_TOP
        val thumbH = maxOf(16, trackH * trackH / (trackH + maxScroll))
        val thumbY = top + (trackH - thumbH) * settingsScroll / maxScroll
        context.fill(x, top, x + 2, top + trackH, 0x40FFFFFF)
        context.fill(x, thumbY, x + 2, thumbY + thumbH, 0xC0FFFFFF.toInt())
    }

    /** 设置弹窗内容滚动：鼠标在弹窗内才吃事件，一次一行（行高 26） */
    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (settingsOpen) {
            val maxScroll = settingsMaxScroll()
            val dialogX = width / 2 - DIALOG_W / 2
            val dialogY = settingsDialogY()
            val inside = mouseX >= dialogX && mouseX <= dialogX + DIALOG_W &&
                mouseY >= dialogY && mouseY <= dialogY + settingsDialogH()
            if (maxScroll > 0 && inside) {
                settingsScroll = (settingsScroll - (verticalAmount * SETTINGS_ROW_STEP).roundToInt()).coerceIn(0, maxScroll)
                layoutSettingsRows()
                return true
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val wasSettingsOpen = settingsOpen
        super.resize(client, width, height)
        if (wasSettingsOpen) {
            settingsOpen = false
            openSettingsDialog()
        }
    }

    private fun openItemMarket() {
        client?.setScreen(ItemMarketScreen())
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val bg = Identifier.of("cobblemarket", "textures/gui/market_entry_background.png")
        // 背景底贴原 160 高背景底边再下移 20、向上扩展（不遮下方 HUD）；按钮布局不动。贴图 256×213
        context.drawTexture(bg, width / 2 - 128, bgBottom() - 213 + 20, 0f, 0f, 256, 213, 256, 213)
    }

    /** 入口掉落动画：图片从屏幕顶外缓出落到入口背景位置（0→1 放大），不弹跳；
     *  位置在掉落阶段结束后固定在目标位，alpha 供淡出阶段渐变 */
    private fun renderDropImage(context: DrawContext, elapsed: Long, alpha: Float) {
        val tex = Identifier.of("cobblemarket", "textures/gui/market_entry_drop.png")
        val targetX = width / 2 - 128
        val targetY = bgBottom() - 213 + 20
        val k = (elapsed.toFloat() / DROP_DURATION_MS).coerceIn(0f, 1f)
        fun smooth(v: Float): Float = v * v * (3f - 2f * v)
        val eased = smooth(k)
        val y = -213 + (targetY + 213) * eased // 起点背景底贴屏幕顶（完全在屏幕外）
        val scale = eased
        val cx = targetX + 128f
        val cy = y + 106.5f
        context.matrices.push()
        context.matrices.translate(cx.toDouble(), cy.toDouble(), 0.0)
        context.matrices.scale(scale, scale, 1f)
        // RGB 随 alpha 一起衰减：先变暗再消失（照精灵动画淡出）
        context.setShaderColor(alpha, alpha, alpha, alpha)
        context.drawTexture(tex, -128, -106, 0f, 0f, 256, 213, 256, 213)
        context.setShaderColor(1f, 1f, 1f, 1f)
        context.matrices.pop()
    }

    /**
     * 中文友好的知识点换行：逐字符累加宽度、超 maxW 才断行（空格当普通字符，不会像 wrapLines 那样
     * 见空格就提前断，避免「《宝可梦 朱/紫》」被拆成「《宝可梦」+「朱/紫》」）；
     * 连续的非 CJK 字符（英文单词/数字）作为整体，超宽时整个挪到下一行，不在词中间断。
     */
    private fun wrapTip(tip: String, maxW: Int, indentPx: Int): List<net.minecraft.text.OrderedText> {
        val result = mutableListOf<net.minecraft.text.OrderedText>()
        val sb = StringBuilder()
        var width = 0
        var i = 0
        while (i < tip.length) {
            val ch = tip[i]
            val cjk = ch.code in 0x2E80..0x9FFF || ch.code in 0x3000..0x303F || ch.code in 0xFF00..0xFFEF
            val word: String
            if (cjk) {
                word = ch.toString()
                i++
            } else {
                val start = i
                while (i < tip.length) {
                    val c = tip[i].code
                    if (c in 0x2E80..0x9FFF || c in 0x3000..0x303F || c in 0xFF00..0xFFEF) break
                    i++
                }
                word = tip.substring(start, i)
            }
            val w = textRenderer.getWidth(word)
            // 首行缩进（中文 2 汉字宽），第一行可用宽度相应减少，避免文字戳出气泡右壁
            val lineMax = if (result.isEmpty()) maxW - indentPx else maxW
            if (width + w > lineMax && sb.isNotEmpty()) {
                result.add(Text.literal(sb.toString()).asOrderedText())
                sb.setLength(0)
                width = 0
            }
            sb.append(word)
            width += w
        }
        if (sb.isNotEmpty()) result.add(Text.literal(sb.toString()).asOrderedText())
        return result
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 掉落动画播完前按钮不可点（事件分发与渲染无关，init 已创建的按钮会照常响应，需显式拦截）
        val total = DROP_DURATION_MS + HOLD_DURATION_MS + FADE_DURATION_MS
        if (ClientConfig.marketAnimation && System.currentTimeMillis() - dropAnimStart < total) return true
        // 点击大木博士立绘（48×128 主体区域，随 oakScaleFactor 缩放）：主动切换下一条知识点
        val oakK = oakScaleFactor()
        val oakRight = width / 2 - 128 - 1
        val oakBottom = bgBottom() - 5
        val oakLeft = oakRight - 48 * oakK
        val oakTop = oakBottom - 128 * oakK
        if (mouseX >= oakLeft && mouseX < oakRight && mouseY >= oakTop && mouseY < oakBottom) {
            oakTipText = OakTips.randomTip()
            // 点击按钮音效（与界面按钮同款）
            MinecraftClient.getInstance().soundManager.play(
                net.minecraft.client.sound.PositionedSoundInstance.master(
                    net.minecraft.sound.SoundEvent.of(net.minecraft.util.Identifier.of("cobblemarket", "button_click")),
                    1.0f
                )
            )
            return true
        }
        // 点主按钮/子按钮以外的地方收起展开的子按钮（点这两个按钮时交给 widget 自己的 onPress 处理）
        if (shareMenuOpen) {
            val overShare = shareBtn?.isMouseOver(mouseX, mouseY) == true ||
                sharePokemonBtn?.isMouseOver(mouseX, mouseY) == true ||
                shareItemBtn?.isMouseOver(mouseX, mouseY) == true
            if (!overShare) collapseShareMenu()
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    /**
     * 大木博士三元素（立绘+气泡+文字）整体缩放系数：锚定立绘底边（bgBottom()-5）与右缘（bgLeft-1），
     * 小逻辑分辨率（GUI Scale 3 等）下按「气泡视觉顶 ≥ 屏幕顶 4px、气泡视觉左缘 ≥ 屏幕左缘 4px」约束缩小
     * （设计偏移：气泡视觉顶距锚点 236、左缘距锚点 155），下限 0.25 防极小窗口缩到看不见。
     */
    private fun oakScaleFactor(): Float {
        val oakBottom = bgBottom() - 5
        val bgLeft = width / 2 - 128
        val kv = (oakBottom - 4) / 236f
        val kh = (bgLeft - 4) / 155f
        // OAK_BASE_SCALE：原始尺寸偏大（立绘+气泡总高接近入口背景高度），设计上整体缩小
        return (minOf(1f, kv, kh) * OAK_BASE_SCALE).coerceAtLeast(0.25f)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 打开入口的掉落动画（照购买精灵动画，个人设置可关）：
        // 下落+停留阶段只画暗底+掉落图；淡出阶段完整渲染入口界面、掉落图渐透明——界面随图淡出慢慢显现
        val animElapsed = System.currentTimeMillis() - dropAnimStart
        if (ClientConfig.marketAnimation) {
            if (animElapsed < DROP_DURATION_MS + HOLD_DURATION_MS) {
                // 下落+停留阶段不画暗化背景（2026-08-28 拍板：只在淡出阶段交叉渐变，避免"暗→亮"的跳变）
                renderDropImage(context, animElapsed, 1f)
                return
            }
            val fadeElapsed = animElapsed - DROP_DURATION_MS - HOLD_DURATION_MS
            if (fadeElapsed < FADE_DURATION_MS) {
                // 交叉淡化：界面在下、掉落图在上渐透明。
                // flush 夹心（照精灵动画）：先提交界面，再画图并立刻提交——否则文字层会盖在图上面透出来；
                // 界面文字写进深度缓冲后会把后画的图整片剔除，画图前清掉（照 PokemonCelebrationAnimation）
                super.render(context, mouseX, mouseY, delta)
                context.draw()
                RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
                renderDropImage(context, animElapsed, 1f - fadeElapsed.toFloat() / FADE_DURATION_MS)
                context.draw()
                return
            }
        }
        super.render(context, mouseX, mouseY, delta)
        // 弹窗打开时标题/余额已被遮罩压住，不再重绘——否则会浮在弹窗之上
        if (settingsOpen) {
            renderSettingsLabels(context)
            if (System.currentTimeMillis() < settingsToastUntil) {
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    settingsToastText,
                    width / 2, settingsDialogY() + settingsDialogH() + 6, 0xFFFFFF
                )
            }
            return
        }
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.entry.title").formatted(Formatting.GOLD, Formatting.BOLD),
            width / 2, btnStartY - 48, 0xFFFFFF
        )
        // 全服累计成交额（标题与余额之间；服务器经济规模氛围展示，不参与任何计算）；
        // 金额单位缩写（k/M/B），悬停显示完整数字
        if (totalVolume >= 0) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(
                    "cobblemarket.entry.total_volume",
                    Text.literal(com.shusheng.cobblemarket.client.formatPriceShortLong(totalVolume)).formatted(Formatting.GOLD),
                    Text.literal(inlineCurrencyUnit()).formatted(Formatting.GOLD)
                ),
                width / 2, btnStartY - 29, 0xFFFFFF
            )
            val lineW = textRenderer.getWidth(
                Text.translatable(
                    "cobblemarket.entry.total_volume",
                    com.shusheng.cobblemarket.client.formatPriceShortLong(totalVolume),
                    inlineCurrencyUnit()
                )
            )
            if (mouseY in (btnStartY - 29)..(btnStartY - 20) && Math.abs(mouseX - width / 2) <= lineW / 2) {
                renderAmountTooltip(
                    context, mouseX, mouseY,
                    "${com.shusheng.cobblemarket.client.formatPriceLong(totalVolume)} ${activeCurrencyUnit()}"
                )
            }
        }
        // 底部三个小按钮上方的分割线（跨度 = 避开背景左右边框各 25）
        // 放在「仅OP行底边」与「小按钮顶边」的正中间：小按钮 top=bgBottom()-27、行3底=bgBottom()-32，
        // 两者相差 5px → 分割线 = bgBottom()-30（上下间隙各 2px）
        context.fill(
            width / 2 - DIVIDER_HALF,
            bgBottom() - 30,
            width / 2 + DIVIDER_HALF,
            bgBottom() - 29,
            com.shusheng.cobblemarket.client.GuiColors.DIVIDER
        )
        // 皮卡丘跑步动画：4 帧贴图循环切换；脚踩背景上沿（底 = 背景顶 + 2）
        val pikaFrame = ((System.currentTimeMillis() / PIKA_FRAME_MS) % 4).toInt()
        val pikaTex = Identifier.of("cobblemarket", "textures/gui/pikachu/pikachu_move_$pikaFrame.png")
        val pikaY = bgBottom() - 213 + 20 - 46
        if (ClientConfig.pikachuRunLoop) {
            // 皮卡丘跑酷：沿背景圆角矩形边缘顺时针环绕跑（圆角半径约 29px）。
            // 脚踩边缘：中心在边缘外侧 22px（=48/2-2，与直线模式脚位一致）；朝向随边旋转（右 90°、下 180°、左 270°，圆弧连续）
            val bgLeft = (width / 2 - 128).toFloat()
            val bgTop = (bgBottom() - 213 + 20).toFloat()
            val w = 256f
            val h = 213f
            val r = 29f
            val foot = 22f                        // 中心距边缘（脚踩线）
            val arc = (PI / 2 * (r + foot)).toFloat() // 每段 1/4 圆弧长（按中心路径半径计，保持角上匀速）
            val sideW = w - 2 * r                 // 上/下直边长
            val sideH = h - 2 * r                 // 左/右直边长
            val totalP = 2 * sideW + 2 * sideH + 4 * arc
            val t = System.currentTimeMillis() % PIKA_LOOP_MS
            val s = t * totalP / PIKA_LOOP_MS
            val s1 = sideW
            val s2 = s1 + arc
            val s3 = s2 + sideH
            val s4 = s3 + arc
            val s5 = s4 + sideW
            val s6 = s5 + arc
            val s7 = s6 + sideH
            val cx: Float
            val cy: Float
            val rotDeg: Float
            when {
                s < s1 -> { cx = bgLeft + r + s; cy = bgTop - foot; rotDeg = 0f }                                                                                // 上直边：左→右，脚朝下
                s < s2 -> { val th = -PI.toFloat() / 2 + (s - s1) / (r + foot); cx = bgLeft + w - r + (r + foot) * cos(th); cy = bgTop + r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }   // 右上圆弧
                s < s3 -> { cx = bgLeft + w + foot; cy = bgTop + r + (s - s2); rotDeg = 90f }                                                                   // 右直边：上→下，脚朝左
                s < s4 -> { val th = (s - s3) / (r + foot); cx = bgLeft + w - r + (r + foot) * cos(th); cy = bgTop + h - r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }                 // 右下圆弧
                s < s5 -> { cx = bgLeft + w - r - (s - s4); cy = bgTop + h + foot; rotDeg = 180f }                                                              // 下直边：右→左，脚朝上
                s < s6 -> { val th = PI.toFloat() / 2 + (s - s5) / (r + foot); cx = bgLeft + r + (r + foot) * cos(th); cy = bgTop + h - r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }   // 左下圆弧
                s < s7 -> { cx = bgLeft - foot; cy = bgTop + h - r - (s - s6); rotDeg = 270f }                                                                  // 左直边：下→上，脚朝右
                else -> { val th = PI.toFloat() + (s - s7) / (r + foot); cx = bgLeft + r + (r + foot) * cos(th); cy = bgTop + r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }            // 左上圆弧
            }
            context.matrices.push()
            context.matrices.translate(cx.toDouble(), cy.toDouble(), 0.0)
            context.matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(rotDeg))
            context.drawTexture(pikaTex, -24, -24, 0f, 0f, 48, 48, 48, 48)
            context.matrices.pop()
        } else {
            // 默认：沿背景顶部从左跑到右（单向循环），跑道左右对称（皮卡丘左右缘距背景左右缘各 8px）
            val trackLeft = width / 2 - 128 + 8
            val trackRight = width / 2 + 128 - 48 - 8
            val trackW = trackRight - trackLeft
            val runT = System.currentTimeMillis() % PIKA_CYCLE_MS
            val pikaX = trackLeft + (runT * trackW / PIKA_CYCLE_MS).toInt()
            context.drawTexture(pikaTex, pikaX, pikaY, 0f, 0f, 48, 48, 48, 48)
        }
        // 大木博士立绘：画在皮卡丘之后，跑酷经过左侧时从立绘下层穿过；
        // 底边与左下角求购单按钮底边齐平（bgBottom()-5）、视觉主体右缘贴背景左缘留 1px。
        // 原图 80×128 左右各 16px 透明边 → uv 裁剪出 48×128 主体。
        // 立绘与气泡作为整体锚定「立绘底边+右缘」，小逻辑分辨率下按 oakScaleFactor() 整体缩放
        val oakBottom = bgBottom() - 5
        val oakK = oakScaleFactor()
        context.matrices.push()
        context.matrices.translate((width / 2 - 128 - 1).toDouble(), oakBottom.toDouble(), 0.0)
        context.matrices.scale(oakK, oakK, 1f)
        context.drawTexture(
            Identifier.of("cobblemarket", "textures/gui/oak.png"),
            -48, -128,
            16f, 0f, 48, 128, 80, 128
        )
        context.matrices.pop()
        // 大木博士知识点气泡：画在立绘之上（最顶层），持续显示；进入界面随机抽、点击立绘换下一条。
        // 贴图 140×152，文字区在图上 (13,58)~(122,121)（110×64，每行 9px）；
        // 排版超 7 行（64/9）的长句整体等比放大 k，文字随矩阵一起放大，气泡内比例不变。
        // 锚定：气泡在博士头顶左侧——本体边界 (3,43)~(139,151)，右缘（x=139）压住立绘左缘 26px（整体右移 30px）、
        // 底边（y=151）贴立绘顶部上方 4px。位置与立绘固定绑定（不做防出屏移动，小窗口超出部分直接裁掉）
        val tip = oakTipText
        if (tip != null) {
            // 字号中英统一（英文 0.75 缩放过小字，用户改回 1:1）；行距 9px。
            // 排版：中文走自定义 wrapTip（空格不提前断行、首行缩进 2 汉字宽）；
            // 英文走原版 wrapLines（按空格断词的标准英文排版，首行顶格不缩进）
            val isZh = (client?.options?.language ?: "en_us").startsWith("zh")
            val fontScale = 1f
            val wrapW = (110 / fontScale).toInt()
            val tipLines = if (isZh) {
                wrapTip(tip, wrapW, (TIP_INDENT_PX / fontScale).roundToInt())
            } else {
                textRenderer.wrapLines(Text.literal(tip), wrapW)
            }
            val bgLeft = width / 2 - 128
            // 气泡随立绘一起整体缩放（oakK），锚点与立绘相同（立绘底边+右缘）；
            // 设计偏移：气泡图原点距锚点 (-158, -279)（= 原 bubbleX/bubbleY 相对 bgLeft-1 / oakBottom）
            val oakBottom = bgBottom() - 5
            // 气泡容量：文字区 63px 高、行高 9 → 原图放得下 7 行；行数超出就把**气泡**放大 k 倍。
            // ⚠ 文字必须**不跟着放大**（下面用 1/k 抵消）—— 两者同比例放大等于没放大，
            //    多出来的行照样画出气泡（2026-09-21 用户实测截图）。
            // k 上限：气泡底边固定在 oakBottom−128，放大后顶边还要往上走 108k，别顶出屏幕上沿
            val maxK = ((oakBottom - 132f) / 108f).coerceAtLeast(1f)
            val k = (tipLines.size / 7f).coerceIn(1f, maxK)
            // 放大后能装下的行数（k 被上限截住时，多余的行不画）
            val maxLines = (7f * k).toInt().coerceIn(1, tipLines.size)
            val oakK = oakScaleFactor()
            context.matrices.push()
            context.matrices.translate((bgLeft - 1).toDouble(), oakBottom.toDouble(), 0.0)
            context.matrices.scale(oakK, oakK, 1f)
            context.matrices.translate(-158.0, -279.0, 0.0)
            // ⚠ 放大要**绕气泡本体右下角 (139,151)**，不能绕图片原点：图 140×152 里本体偏右下（左上留白），
            // 绕原点放大会让气泡往右下长 → 右下角压到大木博士头上（2026-09-17 用户报）。
            // 绕右下角放大 = 长句时气泡向左上扩张，且「右缘压立绘 26px、底边离头顶 4px」两条锚定关系不变。
            context.matrices.translate(139.0, 151.0, 0.0)
            context.matrices.scale(k, k, 1f)
            context.matrices.translate(-139.0, -151.0, 0.0)
            context.drawTexture(Identifier.of("cobblemarket", "textures/gui/chat_bubble.png"), 0, 0, 0f, 0f, 140, 152, 140, 152)
            // 抵消气泡的放大：文字回到 1:1 字号 —— 只是气泡变大、能装更多行
            context.matrices.scale(1f / k, 1f / k, 1f)
            // 文字层再套字号缩放（中文 1:1，英文 0.75）
            context.matrices.scale(fontScale, fontScale, 1f)
            // 文字区左上角 (13,58) 换算到**放大后**的位置
            val tx = (13 * k / fontScale).roundToInt()
            var ty = (58 * k / fontScale).roundToInt()
            val lineH = (9 / fontScale).roundToInt()
            val indent = if (isZh) (TIP_INDENT_PX / fontScale).roundToInt() else 0
            tipLines.take(maxLines).forEachIndexed { index, line ->
                // 中文首行缩进 2 个汉字宽，其余行顶格；英文全部顶格
                context.drawText(textRenderer, line, if (index == 0) tx + indent else tx, ty, 0xFF333333.toInt(), false)
                ty += lineH
            }
            context.matrices.pop()
        }
        // 市场关闭：常驻红字横幅 + 点击入口时的 3 秒提示（OP 同样显示——服主也需要直观看到当前状态）
        if (!com.shusheng.cobblemarket.client.MarketStateCache.enabled) {
            // ⚠ 画在**背景贴图之外、紧贴贴图下沿**（2026-09-24 二次修正）。
            //   ⚠⚠ `bgBottom()` 是**逻辑底边**，**贴图底边 = `bgBottom() + 20`** —— 贴图 213 高，
            //   逻辑区 160 的上方多 33、下方多 20。所以 `bgBottom() + 2` 仍在**贴图内**距底边 18px，
            //   第一版就栽在这个名字上（注释写「贴图底边紧下方」，实现却是逻辑底边）。
            //   要出贴图：从 `bgBottom() + 20` 起算。
            //   原先画在 `btnStartY - 14`（背景内容区顶边），玩家把余额 HUD 拖到那里就会重合；
            //   贴到贴图外沿之后 HUD 不管拖到哪都不会撞上。
            //   `coerceAtMost` 兜底：逻辑高度不足时背景上移挤压（如 854x480 + GUI scale 2，
            //   贴图下方只剩 ~20px），不夹住会超出屏幕底被裁掉
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.market.closed_banner").string,
                width / 2, (bgBottom() + 22).coerceAtMost(height - 11), 0xFF5555
            )
            if (System.currentTimeMillis() < closedNoticeUntil) {
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable("cobblemarket.market.closed").string,
                    width / 2, height - 36, 0xFF5555
                )
            }
        }
        // 金融系统关闭提示（点置灰喵喵银行按钮，3 秒；独立于市场关闭提示）
        if (System.currentTimeMillis() < financeNoticeUntil) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.entry.finance_closed").string,
                width / 2, height - 36, 0xFF5555
            )
        }
        // RP 关闭提示（点置灰的RP 市场按钮，3 秒；与上面两条同位置，极少同时出现）
        if (System.currentTimeMillis() < pointsNoticeUntil) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.market.disabled", CurrencyService.pointsIcon()),
                width / 2, height - 36, 0xFF5555
            )
        }
        // 余额（标题下方，来自全局缓存）
        val bal = com.shusheng.cobblemarket.client.BalanceCache.balance
        if (bal.isNotEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.gui.balance",
                    Text.literal(bal + " " + com.shusheng.cobblemarket.client.inlineCurrencyUnit()).formatted(Formatting.GOLD)),
                width / 2, btnStartY - 18, 0xFFFFFF
            )
        }
    }

    /** RP 总开关变化时同步入口按钮的置灰态（登录补发 / 后续管理端切换，见 PointsStatusPayload） */
    fun updatePointsEntryState() {
        pointsMarketBtn?.dimmed = !com.shusheng.cobblemarket.client.PointsStateCache.enabled
    }

    override fun shouldPause() = false

    companion object {
        /**
         * 底部**红色分割线**的半宽（= 背景半宽 128 − 两端各避开的 25px）。
         *
         * ⚠ **它与管理面板的 `AdminScreen.DIVIDER_HALF` 是同一根线、同一个值** ——
         * 两个界面的图标按钮行都以这根线的左右端为边界（2026-09-25 用户要求统一布局）。
         * **改一个必须改另一个**，否则两界面切换时底部按钮会错位（背景本来就是同一张贴图）。
         */
        private const val DIVIDER_HALF = 103

        private const val DIALOG_W = 240
        private const val DIALOG_H = 324
        // 设置弹窗内容区（相对弹窗顶）：行步进 26 = 控件高 22 + 间隙 4。
        // 内容超过一屏靠滚动显示（整行进出，见 settingsRowVisible）；done 固定在底部不参与滚动
        private const val SETTINGS_ROWS_TOP = 30      // 第一行控件顶 = 内容可视区顶
        private const val SETTINGS_VIEW_BOTTOM_INSET = 37   // 内容可视区底 = 弹窗底往上 37px（done 之上）
        private const val SETTINGS_ROW_STEP = 26
        private const val SETTINGS_LINE_TOP = 27      // 标题下的分隔线（固定，不参与滚动）
        private const val SETTINGS_DONE_INSET = 33         // done 按钮顶 = 弹窗底往上 33px
        // 入口掉落动画三段：下落 → 落地停留 → 淡出（淡出期间入口界面从图下透出，慢慢显现）
        private const val DROP_DURATION_MS = 100L
        private const val HOLD_DURATION_MS = 100L
        private const val FADE_DURATION_MS = 100L
        // 知识点气泡首行缩进（2 个汉字宽 = 16px）
        private const val TIP_INDENT_PX = 16
        // 大木博士三元素设计缩放（原始尺寸偏大，整体缩到 80%）
        private const val OAK_BASE_SCALE = 0.8f
        // 皮卡丘跑步动画：直线一趟时长 / 每帧切换间隔 / 环绕一圈时长
        private const val PIKA_CYCLE_MS = 3000L
        private const val PIKA_FRAME_MS = 100L
        private const val PIKA_LOOP_MS = 8000L
    }
}
