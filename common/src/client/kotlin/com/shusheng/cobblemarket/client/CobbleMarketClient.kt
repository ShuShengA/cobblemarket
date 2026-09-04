package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.network.AuctionDurationsPayload
import com.shusheng.cobblemarket.network.AuctionEventPayload
import com.shusheng.cobblemarket.network.AuctionListDataPayload
import com.shusheng.cobblemarket.network.AuctionSettleSoundPayload
import com.shusheng.cobblemarket.network.PokemonCelebrationPayload
import com.shusheng.cobblemarket.network.MarketStatePayload
import com.shusheng.cobblemarket.network.BuyOrderEventPayload
import com.shusheng.cobblemarket.network.BuyOrderListDataPayload
import com.shusheng.cobblemarket.network.PlayerNameSuggestionsPayload
import com.shusheng.cobblemarket.network.AuctionWarnSoundPayload
import com.shusheng.cobblemarket.network.BalanceDataPayload
import com.shusheng.cobblemarket.network.BanListDataPayload
import com.shusheng.cobblemarket.network.CreditInfoPayload
import com.shusheng.cobblemarket.network.HistoryDataPayload
import com.shusheng.cobblemarket.network.DepositInfoPayload
import com.shusheng.cobblemarket.network.FinanceStatsPayload
import com.shusheng.cobblemarket.network.RequestCreditInfoPayload
import com.shusheng.cobblemarket.network.RequestFinanceStatsPayload
import com.shusheng.cobblemarket.network.LoanHistoryDataPayload
import com.shusheng.cobblemarket.network.RepayListDataPayload
import com.shusheng.cobblemarket.network.ItemBlacklistDataPayload
import com.shusheng.cobblemarket.network.ItemMarketDataPayload
import com.shusheng.cobblemarket.network.ItemPriceLimitDataPayload
import com.shusheng.cobblemarket.network.MarketDataPayload
import com.shusheng.cobblemarket.network.ItemReturnDataPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.MyPokemonListPayload
import com.shusheng.cobblemarket.network.OpenMarketPayload
import com.shusheng.cobblemarket.network.PokemonBlacklistDataPayload
import com.shusheng.cobblemarket.network.PokemonPriceLimitDataPayload
import com.shusheng.cobblemarket.network.PokemonReturnDataPayload
import com.shusheng.cobblemarket.network.RequestBalancePayload
import com.shusheng.cobblemarket.network.ServerConfigDataPayload
import com.shusheng.cobblemarket.screen.AdminAuctionScreen
import com.shusheng.cobblemarket.screen.AdminBanScreen
import com.shusheng.cobblemarket.screen.AuctionCreateScreen
import com.shusheng.cobblemarket.screen.AuctionScreen
import com.shusheng.cobblemarket.screen.BlacklistScreen
import com.shusheng.cobblemarket.screen.AdminItemScreen
import com.shusheng.cobblemarket.screen.AdminPokemonScreen
import com.shusheng.cobblemarket.screen.AdminScreen
import com.shusheng.cobblemarket.screen.BuyConfirmScreen
import com.shusheng.cobblemarket.screen.BuyOrderScreen
import com.shusheng.cobblemarket.screen.HistoryScreen
import com.shusheng.cobblemarket.screen.DepositScreen
import com.shusheng.cobblemarket.screen.FinanceConfigScreen
import com.shusheng.cobblemarket.screen.PurpleCardApplyConditionsScreen
import com.shusheng.cobblemarket.screen.PurpleCardApplyScreen
import com.shusheng.cobblemarket.screen.PurpleCardConfigScreen
import com.shusheng.cobblemarket.screen.LoanHistoryScreen
import com.shusheng.cobblemarket.screen.MeowthPayScreen
import com.shusheng.cobblemarket.screen.RepayScreen
import com.shusheng.cobblemarket.screen.LoanScreen
import com.shusheng.cobblemarket.screen.MarketEntryScreen
import com.shusheng.cobblemarket.screen.MarketScreen
import com.shusheng.cobblemarket.screen.MeowthBankScreen
import com.shusheng.cobblemarket.screen.ItemMarketScreen
import com.shusheng.cobblemarket.screen.ItemReturnScreen
import com.shusheng.cobblemarket.screen.ItemSellScreen
import com.shusheng.cobblemarket.screen.ItemVariantSelectScreen
import com.shusheng.cobblemarket.screen.PokemonReturnScreen
import com.shusheng.cobblemarket.screen.PriceLimitScreen
import com.shusheng.cobblemarket.screen.SellSelectScreen
import com.shusheng.cobblemarket.screen.ServerConfigScreen
import com.shusheng.cobblemarket.screen.drawNineSlice

import com.shusheng.cobblemarket.platform.isModLoaded
import com.shusheng.cobblemarket.platform.onClientTick
import com.shusheng.cobblemarket.platform.registerClientCommand
import com.shusheng.cobblemarket.platform.registerHudRender
import com.shusheng.cobblemarket.platform.registerKeyBinding

import com.shusheng.cobblemarket.platform.registerS2C
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.util.InputUtil
import net.minecraft.sound.SoundEvent
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

private val HUD_BALANCE_BG = net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/hud_balance_bg.png")
private const val HUD_BALANCE_BG_TEX_H = 40

object CobbleMarketClient {

    val LOGGER = LoggerFactory.getLogger(CobbleMarket.MOD_ID)

    private lateinit var openMarketKey: KeyBinding
    private var wasEPressed = false

    /** 余额 HUD 低频兜底轮询计时（交易响应已即时刷新，这里兜住离线收益补发等） */
    private var lastBalancePollAt = 0L
    private var lastFinancePollAt = 0L


    // 成交铃声定时（tick 触发）：落槌立即播放，铃声 0.4 秒后（多拍卖同批结算时铃声只响一次）
    private var bellSoundAt = 0L

    /** 由各平台客户端入口类（fabric 的 CobbleMarketClientFabric 等）在对应初始化阶段调用。 */
    fun init() {
        ClientConfig.load()
        PokemonCelebrationAnimation.register()
        openMarketKey = registerKeyBinding(
            KeyBinding(
                "key.cobblemarket.open_market",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.cobblemarket"
            )
        )
        onClientTick { client ->
            // 成交铃声定时（落槌后 0.4 秒）
            val tickNow = System.currentTimeMillis()
            if (bellSoundAt > 0 && tickNow >= bellSoundAt) {
                bellSoundAt = 0
                client.soundManager.play(
                    PositionedSoundInstance.master(
                        SoundEvent.of(Identifier.of("cobblemarket", "auction_bell")),
                        1.0f
                    )
                )
            }
            while (openMarketKey.wasPressed()) {
                playEntrySound()
                client.setScreen(MarketEntryScreen())
            }
            val ePressed = InputUtil.isKeyPressed(client.window.handle, GLFW.GLFW_KEY_E)
            if (ePressed && !wasEPressed) {
                val screen = client.currentScreen
                val inputFocused = screen?.focused is TextFieldWidget
                if (!inputFocused && isMarketScreen(screen)) {
                    if (ClientConfig.marketAnimation) {
                        // 关闭动画：界面整体上滑出屏，200ms 后真正关闭（tick 兜底见 CloseAnimation.onTick）
                        CloseAnimation.start()
                    } else {
                        // 动画关：音效仍播（与动画解绑），直接关闭
                        CloseAnimation.playCloseSound()
                        client.setScreen(null)
                    }
                }
            }
            wasEPressed = ePressed
            // 关闭动画播完真正关闭界面
            CloseAnimation.onTick(client)
            // 余额 HUD 兜底刷新：2 秒一次（覆盖 bank/指令/其它 mod 等外部余额变化；
            // 市场交易仍走响应即时刷新；负载每人每分钟 30 次轻量查询，可忽略）
            if (ClientConfig.balanceHudMode != BalanceHudMode.OFF && client.player != null &&
                (lastBalancePollAt == 0L || tickNow - lastBalancePollAt >= 2_000)
            ) {
                lastBalancePollAt = tickNow
                sendToServer(RequestBalancePayload())
            }
            // 金融数据兜底刷新：60 秒一次（额度/欠款/累计成交额进全局缓存，界面打开秒显不闪；
            // 进服后首个 tick 即拉，玩家开市场前缓存已就绪）
            if (client.player != null && (lastFinancePollAt == 0L || tickNow - lastFinancePollAt >= 60_000)) {
                lastFinancePollAt = tickNow
                sendToServer(RequestCreditInfoPayload())
                sendToServer(RequestFinanceStatsPayload())
            }
        }
        registerHudRender { context, _ ->
            renderBalanceHud(context)
        }

        // 聊天可点击按钮的落地路径（点击 = RUN_COMMAND 本地执行，不经过聊天栏回显、不发服务端）：
        // 求购单待确认通知的 [查看待交付] 与拍卖播报的拍品名都指向这里；进场动画 + 打开市场音效
        registerClientCommand("cobblemarket") { args ->
            val client = MinecraftClient.getInstance()
            val parts = args.trim().split(" ")
            when (parts.getOrNull(0)) {
                "review" -> parts.getOrNull(1)?.let { uuidStr ->
                    runCatching { java.util.UUID.fromString(uuidStr) }.getOrNull()?.let { id ->
                        EnterAnimation.start()
                        client.setScreen(com.shusheng.cobblemarket.screen.BuyOrderScreen(initialReviewOrderId = id))
                    }
                }
                "auction" -> parts.getOrNull(1)?.let { uuidStr ->
                    runCatching { java.util.UUID.fromString(uuidStr) }.getOrNull()?.let { id ->
                        EnterAnimation.start()
                        client.setScreen(com.shusheng.cobblemarket.screen.AuctionScreen(initialBidAuctionId = id))
                    }
                }
            }
        }

        registerS2C(OpenMarketPayload.ID, OpenMarketPayload.CODEC) { _ ->
            val client = MinecraftClient.getInstance()
            client.execute {
                playEntrySound()
                client.setScreen(MarketEntryScreen())
            }
        }

        registerS2C(MarketDataPayload.ID, MarketDataPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                when (screen) {
                    is MarketScreen -> screen.onMarketData(payload)
                    is AdminPokemonScreen -> screen.onMarketData(payload)
                }
            }
        }

        registerS2C(ItemMarketDataPayload.ID, ItemMarketDataPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                when (screen) {
                    is ItemMarketScreen -> screen.onItemMarketData(payload)
                    is AdminItemScreen -> screen.onItemMarketData(payload)
                }
            }
        }

        registerS2C(MyPokemonListPayload.ID, MyPokemonListPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is SellSelectScreen) {
                    screen.onPokemonList(payload)
                } else if (screen is AuctionCreateScreen) {
                    screen.onPokemonList(payload)
                }
            }
        }

        registerS2C(HistoryDataPayload.ID, HistoryDataPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is HistoryScreen) {
                    screen.onHistoryData(payload)
                }
            }
        }

        registerS2C(CreditInfoPayload.ID, CreditInfoPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                // 先写全局缓存（额度/欠款/开关），再转发界面——界面打开读缓存秒显
                val cache = FinanceCache
                cache.financeEnabled = payload.financeEnabled
                cache.consumerLoanEnabled = payload.consumerLoanEnabled
                cache.creditLimit = payload.limit
                cache.creditDebt = payload.debt
                cache.hasPurpleCard = payload.hasPurpleCard
                val screen = client.currentScreen
                when (screen) {
                    // 喵喵银行与应急贷款共用同一份额度快照（借款成功后服务端回发刷新）；
                    // 入口界面/购买弹窗/喵喵支付界面各取所需（开关/plans）
                    is MeowthBankScreen -> screen.onCreditInfo(payload)
                    is LoanScreen -> screen.onCreditInfo(payload)
                    is BuyConfirmScreen -> screen.onCreditInfo(payload)
                    is MeowthPayScreen -> screen.onCreditInfo(payload)
                    is MarketEntryScreen -> screen.onCreditInfo(payload)
                    is MarketScreen -> screen.onCreditInfo(payload)
                    is ItemMarketScreen -> screen.onCreditInfo(payload)
                }
            }
        }

        registerS2C(LoanHistoryDataPayload.ID, LoanHistoryDataPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is LoanHistoryScreen) {
                    screen.onLoanHistoryData(payload)
                }
            }
        }

        registerS2C(RepayListDataPayload.ID, RepayListDataPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is RepayScreen) {
                    screen.onRepayListData(payload)
                }
            }
        }

        registerS2C(FinanceStatsPayload.ID, FinanceStatsPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                // 先写全局缓存（累计成交额），再转发界面
                FinanceCache.totalVolume = payload.totalVolume
                val screen = client.currentScreen
                when (screen) {
                    // 入口界面：全服累计成交额；管理面板：准备金池/坏账总额告警
                    is MarketEntryScreen -> screen.onFinanceStats(payload)
                    is AdminScreen -> screen.onFinanceStats(payload)
                }
            }
        }

        registerS2C(DepositInfoPayload.ID, DepositInfoPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is DepositScreen) {
                    screen.onDepositInfo(payload)
                }
            }
        }

        registerS2C(PokemonReturnDataPayload.ID, PokemonReturnDataPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is PokemonReturnScreen) {
                    screen.onReturnData(payload)
                }
            }
        }

        registerS2C(ItemReturnDataPayload.ID, ItemReturnDataPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is ItemReturnScreen) {
                    screen.onReturnData(payload)
                }
            }
        }

        registerS2C(MarketResultPayload.ID, MarketResultPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                // 出价成功时不播全局成功音：点击「确认出价」时的金币声即为反馈，避免叠音
                if (!(payload.success && screen is AuctionScreen && screen.isBidDialogOpen())) {
                    playResultSound(payload.success)
                }
                // 交易操作后余额可能变化，主动拉取一次最新余额
                sendToServer(RequestBalancePayload())
                when (screen) {
                    is MarketScreen -> screen.onMarketResult(payload)
                    is SellSelectScreen -> screen.onMarketResult(payload)
                    is ItemSellScreen -> screen.onMarketResult(payload)
                    is ItemMarketScreen -> screen.onMarketResult(payload)
                    is PokemonReturnScreen -> screen.onMarketResult(payload)
                    is ItemReturnScreen -> screen.onMarketResult(payload)
                    is AdminPokemonScreen -> screen.onMarketResult(payload)
                    is AdminItemScreen -> screen.onMarketResult(payload)
                    is AdminBanScreen -> screen.onResult(payload)
                    is AuctionScreen -> screen.onMarketResult(payload)
                    is AuctionCreateScreen -> screen.onMarketResult(payload)
                    is AdminAuctionScreen -> screen.onMarketResult(payload)
                    is BuyOrderScreen -> screen.onMarketResult(payload)
                }
            }
        }

        registerS2C(BalanceDataPayload.ID, BalanceDataPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                BalanceCache.balance = payload.balance
                BalanceCache.balanceRaw = payload.balanceRaw
                BalanceCache.pendingBalance = payload.pendingBalance
                BalanceCache.currencyName = payload.currencyName
            }
        }

        registerS2C(AuctionSettleSoundPayload.ID, AuctionSettleSoundPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                // 落槌立即播放（与条目消失同帧；受众已定向为卖家/参与者，多拍卖同批时各自播放）
                MinecraftClient.getInstance().soundManager.play(
                    PositionedSoundInstance.master(
                        SoundEvent.of(Identifier.of("cobblemarket", "auction_gavel")),
                        1.0f
                    )
                )
                bellSoundAt = System.currentTimeMillis() + 400
                // 拍卖场界面内同步落槌动画
                val screen = MinecraftClient.getInstance().currentScreen
                if (screen is AuctionScreen) {
                    screen.onSettleSound(payload.auctionId)
                }
            }
        }

        registerS2C(PokemonCelebrationPayload.ID, PokemonCelebrationPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                // 获得精灵庆祝动画：买到/拍到/求购单接受交付均走这里（拍卖场景与落槌铃声同批触发，天然同步）
                PokemonCelebrationAnimation.trigger(payload.speciesId, payload.aspects, payload.shiny, payload.source)
            }
        }

        registerS2C(MarketStatePayload.ID, MarketStatePayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                MarketStateCache.enabled = payload.enabled
                // 入口界面打开时同步开关按钮图标（登录补发 / 他人命令切换都能跟上）
                (MinecraftClient.getInstance().currentScreen as? MarketEntryScreen)?.updateMarketSwitchIcon()
            }
        }

        registerS2C(AuctionWarnSoundPayload.ID, AuctionWarnSoundPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                // 渐强警告声：1→0.4、2→0.6、3→0.8（与成交落槌 1.0 递进）
                val volume = when (payload.knock) { 1 -> 0.4f; 2 -> 0.6f; else -> 0.8f }
                MinecraftClient.getInstance().soundManager.play(
                    PositionedSoundInstance.master(
                        SoundEvent.of(Identifier.of("cobblemarket", "auction_gavel")),
                        volume
                    )
                )
                // 拍卖场界面内同步锤子图标敲击动画
                val screen = MinecraftClient.getInstance().currentScreen
                if (screen is AuctionScreen) {
                    screen.onWarnSound(payload.auctionId, payload.knock)
                }
            }
        }

        // 蛋交易状态统一走服务器配置快照（ServerConfigDataPayload）；原 egg_trading_state 通道已随管理面板开关移除

        registerS2C(AuctionDurationsPayload.ID, AuctionDurationsPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                val screen = MinecraftClient.getInstance().currentScreen
                if (screen is AuctionCreateScreen) {
                    screen.onDurations(payload)
                }
            }
        }

        registerS2C(BuyOrderListDataPayload.ID, BuyOrderListDataPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                val screen = MinecraftClient.getInstance().currentScreen
                if (screen is BuyOrderScreen) {
                    screen.onBuyOrderList(payload)
                }
            }
        }

        registerS2C(BuyOrderEventPayload.ID, BuyOrderEventPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                val screen = MinecraftClient.getInstance().currentScreen
                if (screen is BuyOrderScreen) {
                    screen.onBuyOrderEvent(payload)
                }
            }
        }

        registerS2C(BanListDataPayload.ID, BanListDataPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is AdminBanScreen) {
                    screen.onBanList(payload)
                }
            }
        }

        registerS2C(ServerConfigDataPayload.ID, ServerConfigDataPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                ServerConfigScreen.onConfigData(payload)
            }
        }

        registerS2C(PlayerNameSuggestionsPayload.ID, PlayerNameSuggestionsPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                val screen = MinecraftClient.getInstance().currentScreen
                if (screen is AdminBanScreen) {
                    screen.onNameSuggestions(payload)
                }
            }
        }

        registerS2C(PokemonBlacklistDataPayload.ID, PokemonBlacklistDataPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is BlacklistScreen) {
                    screen.onBlacklistData(payload)
                }
            }
        }

        registerS2C(ItemBlacklistDataPayload.ID, ItemBlacklistDataPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is BlacklistScreen) {
                    screen.onItemBlacklistData(payload)
                }
            }
        }

        registerS2C(PokemonPriceLimitDataPayload.ID, PokemonPriceLimitDataPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is PriceLimitScreen) {
                    screen.onPokemonPriceLimitData(payload)
                }
            }
        }

        registerS2C(ItemPriceLimitDataPayload.ID, ItemPriceLimitDataPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is PriceLimitScreen) {
                    screen.onItemPriceLimitData(payload)
                }
            }
        }

        registerS2C(AuctionListDataPayload.ID, AuctionListDataPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is AuctionScreen) {
                    screen.onAuctionList(payload)
                }
                if (screen is AdminAuctionScreen) {
                    screen.onAuctionList(payload)
                }
            }
        }

        registerS2C(AuctionEventPayload.ID, AuctionEventPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is AuctionScreen) {
                    screen.onAuctionEvent(payload)
                }
                if (screen is AdminAuctionScreen) {
                    screen.onAuctionEvent(payload)
                }
                // 拍卖结算完成：若正停在返还界面，自动刷新（不用重开界面）
                if (payload.event == "SETTLED") {
                    when (screen) {
                        is PokemonReturnScreen -> screen.onAuctionSettled()
                        is ItemReturnScreen -> screen.onAuctionSettled()
                    }
                }
            }
        }

        // 集成 cobblemon_smartphone：注册"市场"App 按钮（仅在安装了该模组时）
        if (isModLoaded("cobblemon_smartphone")) {
            com.nbp.cobblemon_smartphone.api.SmartphoneActionRegistry.register(
                com.shusheng.cobblemarket.compat.OpenMarketAction
            )
        }
    }

    /** 打开入口界面时播放音效（K 键、/market gui、smartphone 三个入口共用）。 */
    fun playEntrySound() {
        MinecraftClient.getInstance().soundManager.play(
            PositionedSoundInstance.master(
                SoundEvent.of(Identifier.of("cobblemarket", "open_entry")),
                1.0f
            )
        )
    }

    /** 交易操作结果音效（成功/失败），所有 MarketResultPayload 到达时统一播放。 */
    fun playResultSound(success: Boolean) {
        MinecraftClient.getInstance().soundManager.play(
            PositionedSoundInstance.master(
                SoundEvent.of(Identifier.of("cobblemarket", if (success) "result_success" else "result_fail")),
                1.0f
            )
        )
    }
}

/** 客户端本地校验失败音效（弹窗输入非法、关市拦截等场景共用） */
fun playFailSound() {
    MinecraftClient.getInstance().soundManager.play(
        PositionedSoundInstance.master(
            SoundEvent.of(Identifier.of("cobblemarket", "fail")),
            1.0f
        )
    )
}

/**
 * 支持按 E 返回游戏的本模组界面白名单。
 * 新增界面要支持 E 键关闭 = 在这里补一行（别把白名单散回 tick 里）。
 * 输入框聚焦时 E 不生效（打字保护在调用侧判断）。
 */
/** 余额 HUD：左上角金额 + 货币符号（金色，金额规范色）；常驻所有界面（渲染在最顶层，弹窗打开时也可见——竞价/购买时玩家能看到剩余余额）；开关关闭、未进世界时不画。
 *  公开顶层函数：HudRenderCallback（无界面）与 ScreenMixin（界面之上）两处调用。 */
/** 上次 HUD 余额文本（ON_CHANGE 模式变动检测） */
private var lastHudBalanceText: String? = null

/** ON_CHANGE 模式：余额最近一次变动时刻 */
private var lastBalanceChangeAt = 0L

/** 余额变动提示：上次原始值 + 差值 + 显示截止时间（+绿/-红浮字 2 秒，照 CobbleDollars 的变动提示） */
private var lastBalanceRaw: Long? = null
private var hudDiff = 0L
private var hudDiffUntil = 0L

fun renderBalanceHud(context: net.minecraft.client.gui.DrawContext) {
    val client = MinecraftClient.getInstance()
    if (client.player == null) return
    // F3 调试界面打开时不画（左上角帧率区会被 HUD 挡住）；shouldShowDebugHud 封装了「F3 开且 HUD 未隐藏」的判断
    if (client.debugHud.shouldShowDebugHud()) return
    val text = "${hudBalanceText(client)} ${inlineCurrencyUnit()}"
    // 余额变动检测（每帧，OFF 模式也跟踪避免切回时误报）：差值驱动 +绿/-红浮字
    val rawNow = hudBalanceRaw(client)
    if (lastBalanceRaw != null && rawNow != lastBalanceRaw!!) {
        hudDiff = rawNow - lastBalanceRaw!!
        hudDiffUntil = System.currentTimeMillis() + 2_000
    }
    lastBalanceRaw = rawNow
    // 三态显示判断：ALWAYS 恒显；ON_CHANGE 文本变化后显 5 秒；OFF 不显
    val visible = when (ClientConfig.balanceHudMode) {
        BalanceHudMode.OFF -> false
        BalanceHudMode.ALWAYS -> true
        BalanceHudMode.ON_CHANGE -> {
            if (text != lastHudBalanceText) {
                lastHudBalanceText = text
                lastBalanceChangeAt = System.currentTimeMillis()
            }
            System.currentTimeMillis() - lastBalanceChangeAt < 5_000
        }
    }
    if (!visible) return
    // 「适应」模式淡出：显示期最后 800ms 背景+文字整体线性渐隐（入口动画暗淡同款手法）
    var alphaF = 1f
    if (ClientConfig.balanceHudMode == BalanceHudMode.ON_CHANGE) {
        val remaining = 5_000 - (System.currentTimeMillis() - lastBalanceChangeAt)
        if (remaining < 800) alphaF = (remaining / 800f).coerceIn(0f, 1f)
    }
    if (alphaF <= 0f) return
    // 与庆祝动画同款三层：flush 提交遮罩 → 清深度缓冲（遮罩用 z=100 平移写入深度，
    // 不清会被深度测试拒绝）→ 画 HUD → 立即提交
    context.draw()
    com.mojang.blaze3d.systems.RenderSystem.clear(
        org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT,
        MinecraftClient.IS_SYSTEM_MAC
    )
    // RGB 随 alpha 一起衰减（照入口动画淡出）：context.setShaderColor 同时作用于
    // drawTexture（背景贴图）与文字渲染；RenderSystem 全局色不响应 drawTexture，勿混用
    context.setShaderColor(alphaF, alphaF, alphaF, alphaF)
    // 背景框：HUD 专属九宫格贴图（40×40），宽随文字自适应，高 16
    val textW = client.textRenderer.getWidth(text)
    drawNineSlice(
        context,
        HUD_BALANCE_BG,
        0, 0, textW + 10, 16,
        0, HUD_BALANCE_BG_TEX_H
    )
    context.drawTextWithShadow(client.textRenderer, text, 5, 4, 0xFFAA00)
    // 余额变动浮字：+绿/-红，2 秒后消失（照 CobbleDollars 右下角的变动提示）
    if (hudDiff != 0L && System.currentTimeMillis() < hudDiffUntil) {
        val diffText = if (hudDiff > 0) "+${formatBalanceLong(hudDiff)}" else formatBalanceLong(hudDiff).toString()
        val diffColor = if (hudDiff > 0) 0x55FF55 else 0xFF5555
        context.drawTextWithShadow(client.textRenderer, diffText, textW + 14, 4, diffColor)
    }
    context.setShaderColor(1f, 1f, 1f, 1f)
    context.draw()
}

/** HUD 余额文本：虚拟货币用服务端下发缓存；物品货币本地实时数背包（丢/捡物品下一帧即变，零网络开销）。
 *  判断用虚拟 key 列表（与 displayCurrency 一致）——不要用 Identifier.tryParse 区分：
 *  无冒号的虚拟 key 会被解析成 minecraft:xxx（默认命名空间）误入物品分支，HUD 恒显 0 */
private fun hudBalanceText(client: MinecraftClient): String {
    val raw = BalanceCache.currencyName
    val virtual = raw == com.shusheng.cobblemarket.config.CurrencyHandler.POKEDOLLARS_KEY ||
        raw == com.shusheng.cobblemarket.config.CurrencyHandler.POKECOINS_KEY ||
        raw == com.shusheng.cobblemarket.config.CurrencyHandler.COBBLEDOLLARS_KEY ||
        raw == com.shusheng.cobblemarket.config.CurrencyHandler.IMPACTOR_KEY
    if (virtual) return BalanceCache.balance
    return formatBalanceLong(hudBalanceRaw(client))
}

/** HUD 余额原始数值：虚拟货币用服务端下发；物品货币本地数背包（丢/捡物品下一帧即变） */
private fun hudBalanceRaw(client: MinecraftClient): Long {
    val raw = BalanceCache.currencyName
    val virtual = raw == com.shusheng.cobblemarket.config.CurrencyHandler.POKEDOLLARS_KEY ||
        raw == com.shusheng.cobblemarket.config.CurrencyHandler.POKECOINS_KEY ||
        raw == com.shusheng.cobblemarket.config.CurrencyHandler.COBBLEDOLLARS_KEY ||
        raw == com.shusheng.cobblemarket.config.CurrencyHandler.IMPACTOR_KEY
    if (virtual) return BalanceCache.balanceRaw
    val itemId = net.minecraft.util.Identifier.tryParse(raw) ?: return BalanceCache.balanceRaw
    val item = net.minecraft.registry.Registries.ITEM.get(itemId)
    val player = client.player ?: return BalanceCache.balanceRaw
    var total = 0L
    val inv = player.inventory
    for (i in 0 until inv.size()) {
        val stack = inv.getStack(i)
        if (stack.isOf(item)) total += stack.count
    }
    return total
}

private fun isMarketScreen(s: net.minecraft.client.gui.screen.Screen?): Boolean =
    s is MarketScreen || s is SellSelectScreen || s is HistoryScreen || s is MarketEntryScreen ||
        s is ItemMarketScreen || s is ItemSellScreen || s is ItemReturnScreen || s is PokemonReturnScreen ||
        s is BuyConfirmScreen || s is AdminScreen || s is AdminPokemonScreen || s is AdminItemScreen || s is AdminBanScreen ||
        s is BlacklistScreen || s is PriceLimitScreen || s is AuctionScreen || s is AuctionCreateScreen ||
        s is BuyOrderScreen || s is AdminAuctionScreen || s is ServerConfigScreen || s is FinanceConfigScreen ||
        s is PurpleCardConfigScreen || s is PurpleCardApplyScreen || s is PurpleCardApplyConditionsScreen || s is ItemVariantSelectScreen ||
        s is MeowthBankScreen || s is LoanScreen || s is LoanHistoryScreen || s is RepayScreen || s is MeowthPayScreen ||
        s is DepositScreen
