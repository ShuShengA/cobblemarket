package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.network.SpeciesRestPayload
import com.shusheng.cobblemarket.permission.PermissionAuditPayload
import com.shusheng.cobblemarket.permission.PermissionListDataPayload
import com.shusheng.cobblemarket.screen.PermissionScreen
import com.shusheng.cobblemarket.screen.PointsCodeScreen
import com.shusheng.cobblemarket.screen.PointsRecordScreen
import com.shusheng.cobblemarket.permission.PermissionSnapshotPayload
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.network.AuctionDurationsPayload
import com.shusheng.cobblemarket.network.AuctionEventPayload
import com.shusheng.cobblemarket.network.AuctionListDataPayload
import com.shusheng.cobblemarket.network.AuctionSettleSoundPayload
import com.shusheng.cobblemarket.network.ItemCelebrationPayload
import com.shusheng.cobblemarket.network.PokemonCelebrationPayload
import com.shusheng.cobblemarket.network.MarketStatePayload
import com.shusheng.cobblemarket.network.PayQrImagePayload
import com.shusheng.cobblemarket.network.PointCodesCreatedPayload
import com.shusheng.cobblemarket.network.PointCodesPayload
import com.shusheng.cobblemarket.network.PointMarketItemsPayload
import com.shusheng.cobblemarket.network.ShopSnapshotsPayload
import com.shusheng.cobblemarket.network.SpeciesFeatureAspectsPayload
import com.shusheng.cobblemarket.network.PointRecordsPayload
import com.shusheng.cobblemarket.network.PointsConfigPayload
import com.shusheng.cobblemarket.network.PointsShopInfoPayload
import com.shusheng.cobblemarket.network.PointsStatusPayload
import com.shusheng.cobblemarket.network.PendingReturnsPayload
import com.shusheng.cobblemarket.network.PlaySoundPayload
import com.shusheng.cobblemarket.network.BuyOrderEventPayload
import com.shusheng.cobblemarket.network.BuyOrderListDataPayload
import com.shusheng.cobblemarket.network.PlayerNameSuggestionsPayload
import com.shusheng.cobblemarket.network.AuctionWarnSoundPayload
import com.shusheng.cobblemarket.network.BalanceDataPayload
import com.shusheng.cobblemarket.network.BanChangesPayload
import com.shusheng.cobblemarket.network.BanListDataPayload
import com.shusheng.cobblemarket.network.CreditInfoPayload
import com.shusheng.cobblemarket.network.HistoryDataPayload
import com.shusheng.cobblemarket.network.DepositInfoPayload
import com.shusheng.cobblemarket.network.PurpleCardApplyInfoPayload
import com.shusheng.cobblemarket.network.BlackCardApplyInfoPayload
import com.shusheng.cobblemarket.network.CardCelebrationPayload
import com.shusheng.cobblemarket.network.CardHolderBoardPayload
import com.shusheng.cobblemarket.network.CardHolderListPayload
import com.shusheng.cobblemarket.network.FinanceStatsPayload
import com.shusheng.cobblemarket.network.RequestCreditInfoPayload
import com.shusheng.cobblemarket.network.RequestFinanceStatsPayload
import com.shusheng.cobblemarket.network.CreditAdjustListPayload
import com.shusheng.cobblemarket.network.CreditLookupPayload
import com.shusheng.cobblemarket.network.CreditRankPayload
import com.shusheng.cobblemarket.network.FinanceChangesPayload
import com.shusheng.cobblemarket.network.LoanBlockListPayload
import com.shusheng.cobblemarket.network.LoanHistoryDataPayload
import com.shusheng.cobblemarket.network.RepayListDataPayload
import com.shusheng.cobblemarket.network.ItemBlacklistDataPayload
import com.shusheng.cobblemarket.network.ItemMarketDataPayload
import com.shusheng.cobblemarket.network.ItemPriceLimitDataPayload
import com.shusheng.cobblemarket.network.MarketDataPayload
import com.shusheng.cobblemarket.network.ItemReturnDataPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.MyPokemonListPayload
import com.shusheng.cobblemarket.network.ObjectPreviewPayload
import com.shusheng.cobblemarket.network.OpenMarketPayload
import com.shusheng.cobblemarket.network.PokemonBlacklistDataPayload
import com.shusheng.cobblemarket.network.PokemonPriceLimitDataPayload
import com.shusheng.cobblemarket.network.PointOrdersPayload
import com.shusheng.cobblemarket.network.PokemonReturnDataPayload
import com.shusheng.cobblemarket.network.RequestBalancePayload
import com.shusheng.cobblemarket.network.ServerConfigDataPayload
import com.shusheng.cobblemarket.network.TierIconPayload
import com.shusheng.cobblemarket.screen.AdminAuctionScreen
import com.shusheng.cobblemarket.screen.AdminBanScreen
import com.shusheng.cobblemarket.screen.AdminFinanceScreen
import com.shusheng.cobblemarket.screen.AuctionCreateScreen
import com.shusheng.cobblemarket.screen.AuctionScreen
import com.shusheng.cobblemarket.screen.BlacklistScreen
import com.shusheng.cobblemarket.screen.AdminItemScreen
import com.shusheng.cobblemarket.screen.AdminPokemonScreen
import com.shusheng.cobblemarket.screen.AdminPointOrdersScreen
import com.shusheng.cobblemarket.screen.AdminScreen
import com.shusheng.cobblemarket.screen.BalanceHudPositionScreen
import com.shusheng.cobblemarket.screen.BuyConfirmScreen
import com.shusheng.cobblemarket.screen.BuyOrderScreen
import com.shusheng.cobblemarket.screen.HistoryScreen
import com.shusheng.cobblemarket.screen.DepositScreen
import com.shusheng.cobblemarket.screen.FinanceConfigScreen
import com.shusheng.cobblemarket.screen.GatedFormScreen
import com.shusheng.cobblemarket.screen.PurpleCardApplyConditionsScreen
import com.shusheng.cobblemarket.screen.PurpleCardApplyScreen
import com.shusheng.cobblemarket.screen.BlackCardApplyScreen
import com.shusheng.cobblemarket.screen.BlackCardConfigScreen
import com.shusheng.cobblemarket.screen.BlackCardApplyConditionsScreen
import com.shusheng.cobblemarket.screen.PurpleCardConfigScreen
import com.shusheng.cobblemarket.screen.LoanHistoryScreen
import com.shusheng.cobblemarket.screen.MeowthPayScreen
import com.shusheng.cobblemarket.screen.RepayScreen
import com.shusheng.cobblemarket.screen.LoanScreen
import com.shusheng.cobblemarket.screen.MarketEntryScreen
import com.shusheng.cobblemarket.screen.QuickShowcaseScreen
import com.shusheng.cobblemarket.screen.MarketScreen
import com.shusheng.cobblemarket.screen.MeowthBankScreen
import com.shusheng.cobblemarket.screen.ItemMarketScreen
import com.shusheng.cobblemarket.screen.ItemReturnScreen
import com.shusheng.cobblemarket.screen.ItemSellScreen
import com.shusheng.cobblemarket.screen.ItemVariantSelectScreen
import com.shusheng.cobblemarket.screen.PokemonReturnScreen
import com.shusheng.cobblemarket.screen.PointsConfigScreen
import com.shusheng.cobblemarket.screen.PointsMarketScreen
import com.shusheng.cobblemarket.screen.PointsRechargeScreen
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

private val HUD_BALANCE_BG = net.minecraft.util.Identifier.of("cobblemarket", "textures/gui/background/hud_balance_bg.png")
private const val HUD_BALANCE_BG_TEX_H = 40

object CobbleMarketClient {

    val LOGGER = LoggerFactory.getLogger(CobbleMarket.MOD_ID)

    private lateinit var openMarketKey: KeyBinding
    private var wasEPressed = false

    /** 快速展示键（默认 X；**按住**期间显示两个大按钮，见 [QuickShowcaseScreen]） */
    private lateinit var quickShowcaseKey: KeyBinding

    /**
     * **快速展示键当前绑定的显示文本**（入口界面那三处悬停提示用，见 `MarketEntryScreen.withQuickKeyHint`）。
     *
     * 返回 null = **那一行整个不显示**：还没初始化，或玩家**把键解绑了**（显示「按住 未知」只会误导）。
     * ⚠ 键位**可改** ⇒ 提示里不能写死 X，必须读当前绑定。
     *
     * ⚠⚠ **必须用原版这个访问器，别自己 `Text.translatable(boundKeyTranslationKey)`**：
     *   字母 / 数字键（X、Z、1…）**根本不在语言表里** —— 原版对「GLFW 能给出名字」的键是
     *   直接 `Text.literal(名字.toUpperCase())`（`InputUtil$Type` 里那段），
     *   只有 escape / f1 这类**没有 GLFW 名字**的键才去查表（那 85 条 `key.keyboard.*` 全是这种）。
     *   自己拼的后果：提示里原样显示成 `key.keyboard.x`（2026-10-06 用户实测报出）。
     * ⚠ 末尾的 `.string` 是**客户端**取词条（这条提示只用在客户端界面），
     *   与「服务端不能 .string 拼语言文本」那条规矩不冲突；取成 String 再交给词条的 `%s`
     *   （避免把"待翻译的 Text"当参数嵌进去）。
     */
    fun quickShowcaseKeyText(): String? {
        if (!::quickShowcaseKey.isInitialized) return null
        if (quickShowcaseKey.isUnbound) return null
        return quickShowcaseKey.boundKeyLocalizedText.string
    }

    /** 上一帧的按住状态（边沿检测：上升沿开、下降沿收尾） */
    private var wasQuickShowcasePressed = false

    /** 余额 HUD 低频兜底轮询计时（交易响应已即时刷新，这里兜住离线收益补发等） */
    private var lastBalancePollAt = 0L
    private var lastFinancePollAt = 0L


    // 成交铃声定时（tick 触发）：落槌立即播放，铃声 0.4 秒后（多拍卖同批结算时铃声只响一次）
    private var bellSoundAt = 0L

    /** 由各平台客户端入口类（fabric 的 CobbleMarketClientFabric 等）在对应初始化阶段调用。 */
    fun init() {
        ClientConfig.load()
        // 「有新版本可用」提示：起个后台线程查官网版本文件（查完什么都不做，等玩家真打开市场界面时才提）
        UpdateNotice.init()
        PokemonCelebrationAnimation.register()
        ItemCelebrationAnimation.register()
        CardCelebrationAnimation.register()
        openMarketKey = registerKeyBinding(
            KeyBinding(
                "key.cobblemarket.open_market",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.cobblemarket"
            )
        )
        // 快速展示：默认 X。照上面那套注册 ⇒ **自动出现在「按键设置」里、玩家可改**
        quickShowcaseKey = registerKeyBinding(
            KeyBinding(
                "key.cobblemarket.quick_showcase",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                "category.cobblemarket"
            )
        )
        onClientTick { client ->
            // 「有新版本可用」：查到结果后，等玩家第一次打开市场界面时说一次（每个世界一次）
            UpdateNotice.tick(client)
            // 成交铃声定时（落槌后 0.4 秒）
            val tickNow = System.currentTimeMillis()
            if (bellSoundAt > 0 && tickNow >= bellSoundAt) {
                bellSoundAt = 0
                client.soundManager.play(
                    PositionedSoundInstance.master(
                        SoundEvent.of(Identifier.of("cobblemarket", "auction_bell")),
                        1.0f,
                        0.5f // 音量（两参重载固定 0.25 太轻，同 playResultSound）
                    )
                )
            }
            // 「离线收益」提示音：登录推送时安排了待播时刻，到点且已进世界才响（一次上线一次）
            if (MarketStateCache.offlineRewardAt > 0 && tickNow >= MarketStateCache.offlineRewardAt &&
                client.player != null
            ) {
                MarketStateCache.offlineRewardAt = 0
                playMarketSound("offline_reward")
            }
            while (openMarketKey.wasPressed()) {
                playEntrySound()
                client.setScreen(MarketEntryScreen())
            }
            // ── 快速展示：**按住**显示两个大按钮、**松开**就展示（2026-09-29 用户定）──
            // ⚠⚠ **必须读 GLFW 的真实按键状态，不能用 `KeyBinding.isPressed`** ——
            //   MC 打开界面时会清空按键绑定状态（防卡键），于是这个界面**自己把自己关掉**：
            //   按住 X ⇒ 读到"按下" ⇒ setScreen(界面) ⇒ 状态被清 ⇒ 下 tick 读到"松开" ⇒ 关界面
            //   ⇒ 又读到"按下"…… **每两 tick 开关一次**，表现是按钮落一点就闪、连别家 HUD 都跟着闪
            //   （2026-09-29 用户实测报的）。照 E 键那套（下面几行）读原始状态就没这问题。
            // ⚠ 键码从**绑定键**反解（`boundKey` 字段是 private，只能走翻译 key）⇒
            //   玩家在「按键设置」里改过键照样生效，不是硬编码 X
            val quickKeyCode =
                InputUtil.fromTranslationKey(quickShowcaseKey.boundKeyTranslationKey).code
            val quickPressed = InputUtil.isKeyPressed(client.window.handle, quickKeyCode)
            if (quickPressed && !wasQuickShowcasePressed) {
                if (client.currentScreen == null) client.setScreen(QuickShowcaseScreen())
            } else if (!quickPressed && wasQuickShowcasePressed) {
                // 松键：悬停哪个就展示哪个，没悬停就什么都不做，然后关界面
                (client.currentScreen as? QuickShowcaseScreen)?.finishAndClose()
            }
            wasQuickShowcasePressed = quickPressed
            val ePressed = InputUtil.isKeyPressed(client.window.handle, GLFW.GLFW_KEY_E)
            if (ePressed && !wasEPressed) {
                val screen = client.currentScreen
                val inputFocused = screen?.focused is TextFieldWidget
                if (!inputFocused && isMarketScreen(screen)) {
                    // ⚠ 「叠在别的界面上」的界面（形态说明）按 E 要**回它的上层**，不能回游戏 ——
                    //   服主是**在编辑窗配到一半**时点进来看说明的，回游戏等于把那一窗草稿丢了。
                    //   其余界面 null = 照旧回游戏。
                    val backTo = (screen as? GatedFormScreen)?.backScreen
                    if (ClientConfig.marketAnimation) {
                        // 关闭动画：界面整体上滑出屏，200ms 后真正关闭（tick 兜底见 CloseAnimation.onTick）
                        CloseAnimation.start(backTo)
                    } else {
                        // 动画关：音效仍播（与动画解绑），直接关闭
                        CloseAnimation.playCloseSound()
                        client.setScreen(backTo)
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
            // 这里的额度请求是兜底轮询，故意裸发不走 requestCreditInfo：它不指望某次响应，
            // 用重试路径只会把界面的待响应状态搅乱
            if (client.player != null && (lastFinancePollAt == 0L || tickNow - lastFinancePollAt >= 60_000)) {
                lastFinancePollAt = tickNow
                sendToServer(RequestCreditInfoPayload())
                sendToServer(RequestFinanceStatsPayload())
            }
            // 界面额度请求未被响应（连点界面时被服务端限流吞掉）则重发，见 requestCreditInfo
            tickCreditInfoRetry(client, tickNow)
        }
        registerHudRender { context, _ ->
            // HUD 层（onTop=false，画在界面之前）：本模组界面时函数内部会跳过（顶层那份负责，避免重影），
            // 原版 / 其他模组界面则照画 —— 被界面的半透明遮罩压暗，即「背包 / ESC 菜单里淡淡透出来」
            renderBalanceHud(context, onTop = false)
        }

        // 聊天可点击按钮的落地路径（点击 = RUN_COMMAND 本地执行，不经过聊天栏回显、不发服务端）：
        // 求购单待确认通知的 [查看待交付] 与拍卖播报的拍品名都指向这里；进场动画 + 打开市场音效
        registerClientCommand("cobblemarket") { args ->
            val client = MinecraftClient.getInstance()
            val parts = args.trim().split(" ")
            // 第三段是**这条单自己的币种**（服务端生成通知时写入）：两个界面一次只看一种货币，
            // 带上它就能一次请求命中；没有这一段（老格式 / 手动输入）传 null，由界面的
            // 「找不到就换另一种币种再找」兜底。用严格版解析：垃圾值 = null = 走兜底，不能静默当主货币
            val currency = parts.getOrNull(2)
                ?.let { com.shusheng.cobblemarket.currency.MarketCurrency.fromRequest(it) }
            when (parts.getOrNull(0)) {
                "review" -> parts.getOrNull(1)?.let { uuidStr ->
                    runCatching { java.util.UUID.fromString(uuidStr) }.getOrNull()?.let { id ->
                        EnterAnimation.start()
                        client.setScreen(
                            com.shusheng.cobblemarket.screen.BuyOrderScreen(
                                initialReviewOrderId = id, initialCurrency = currency
                            )
                        )
                    }
                }
                "auction" -> parts.getOrNull(1)?.let { uuidStr ->
                    runCatching { java.util.UUID.fromString(uuidStr) }.getOrNull()?.let { id ->
                        EnterAnimation.start()
                        client.setScreen(
                            com.shusheng.cobblemarket.screen.AuctionScreen(
                                initialBidAuctionId = id, initialCurrency = currency
                            )
                        )
                    }
                }
                // "share"：分享播报的精灵/物品名上挂的指令，唯一用途是让聊天悬浮大图
                // （ChatObjectPreview）顺着 clickEvent 认出这条消息。**点击不跳转**是需求，
                // 所以这里刻意什么都不做——别以为是漏了实现
                "share" -> {}
                // "auctionpeek"：同 "share" —— 拍卖播报的**名字**上挂的指令（名字只做悬停、不做跳转，
                // 能点的只有它末尾的 [点击出价]，见 AuctionNetwork.announceAuction）。
                // 同样**刻意什么都不做**——别以为是漏了实现、也别"顺手"让它去打开出价界面
                "auctionpeek" -> {}
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
                // 响应到了：停掉未响应重试（这个赋值必须在 execute 内——重试状态只在主线程读写）
                creditInfoPending = false
                creditInfoRetries = 0
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

        registerS2C(PurpleCardApplyInfoPayload.ID, PurpleCardApplyInfoPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is PurpleCardApplyScreen) {
                    screen.onApplyInfo(payload)
                }
            }
        }

        registerS2C(BlackCardApplyInfoPayload.ID, BlackCardApplyInfoPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is BlackCardApplyScreen) {
                    screen.onApplyInfo(payload)
                }
            }
        }

        registerS2C(CardHolderListPayload.ID, CardHolderListPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                // 卡片管理已搬进金融管理界面当页签（原 `CardManageScreen` 已删）
                if (screen is AdminFinanceScreen) screen.onCardHolders(payload)
            }
        }

        registerS2C(LoanBlockListPayload.ID, LoanBlockListPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is AdminFinanceScreen) {
                    screen.onLoanBlocks(payload)
                }
            }
        }

        registerS2C(CreditAdjustListPayload.ID, CreditAdjustListPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is AdminFinanceScreen) screen.onCreditRows(payload)
            }
        }

        registerS2C(CreditLookupPayload.ID, CreditLookupPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is AdminFinanceScreen) screen.onCreditLookup(payload)
            }
        }

        // 金融管理额度排行（「额度排行」页签）
        registerS2C(CreditRankPayload.ID, CreditRankPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                val screen = MinecraftClient.getInstance().currentScreen
                if (screen is AdminFinanceScreen) screen.onCreditRank(payload)
            }
        }

        // 金融管理变更记录（金融管理界面的「变更记录」页签）
        registerS2C(FinanceChangesPayload.ID, FinanceChangesPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                val screen = MinecraftClient.getInstance().currentScreen
                if (screen is AdminFinanceScreen) screen.onFinanceChanges(payload)
            }
        }

        registerS2C(CardHolderBoardPayload.ID, CardHolderBoardPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is MeowthBankScreen) {
                    screen.onCardHolderBoard(payload)
                }
            }
        }

        registerS2C(CardCelebrationPayload.ID, CardCelebrationPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                CardCelebrationAnimation.trigger(payload.kind)
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
                // 展示到聊天框成功时播专属音（分享出去的反馈，与买卖的成功音不是一回事）；
                // 失败仍走通用失败音。判据看界面是不是展示模式——收到包时界面还没被关掉，
                // 关界面是在下面 onMarketResult 里做的
                val shareSucceeded = payload.success && when (screen) {
                    is SellSelectScreen -> screen.isShareMode()
                    is ItemSellScreen -> screen.isShareMode()
                    // 快速展示：那个界面整个就是为展示而生的，不用再判模式
                    is QuickShowcaseScreen -> true
                    else -> false
                }
                when {
                    shareSucceeded -> playMarketSound("share")
                    // 出价成功时不播全局成功音：点击「确认出价」时的金币声即为反馈，避免叠音
                    payload.success && screen is AuctionScreen && screen.isBidDialogOpen() -> {}
                    else -> playResultSound(payload.success)
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
                    is AdminFinanceScreen -> screen.onResult(payload)
                    is AuctionScreen -> screen.onMarketResult(payload)
                    is AuctionCreateScreen -> screen.onMarketResult(payload)
                    is AdminAuctionScreen -> screen.onMarketResult(payload)
                    is BuyOrderScreen -> screen.onMarketResult(payload)
                    is PointsMarketScreen -> screen.onMarketResult(payload)
                    is PointsConfigScreen -> screen.onMarketResult(payload)
                    is PointsCodeScreen -> screen.onMarketResult(payload)
                    // 快速展示：回包到了才关界面（展示专属音效的判定靠"回包时界面还开着"）
                    is QuickShowcaseScreen -> screen.onMarketResult(payload)
                    // 下单被拒（订单数达上限等）要在充值界面里说一声：只发聊天栏 + 一声失败音会被漏看
                    is PointsRechargeScreen -> screen.onMarketResult(payload)
                    // 确认发放 / 作废的结果：管理端界面要刷新列表（详细原因在 message 里，界面负责显示）
                    is AdminPointOrdersScreen -> screen.onResult(payload)
                    // 借款被拒也走结果包（失败音效挂在它上面）—— 见 LoanScreen.onMarketResult
                    is LoanScreen -> screen.onMarketResult(payload)
                }
            }
        }

        registerS2C(BalanceDataPayload.ID, BalanceDataPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                BalanceCache.balance = payload.balance
                BalanceCache.balanceRaw = payload.balanceRaw
                BalanceCache.pendingBalance = payload.pendingBalance
                BalanceCache.currencyName = payload.currencyName
                // RP 那一行与主货币同一份快照到达，不会出现「主货币更新了、RP 还是旧的」
                BalanceCache.pointsBalance = payload.pointsBalance
                BalanceCache.pointsRaw = payload.pointsRaw
            }
        }

        registerS2C(AuctionSettleSoundPayload.ID, AuctionSettleSoundPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                // 落槌立即播放（与条目消失同帧；受众已定向为卖家/参与者，多拍卖同批时各自播放）
                MinecraftClient.getInstance().soundManager.play(
                    // 同样走三参：落槌要的是**满音量** 1.0（三声警告 0.4/0.6/0.8 的递进终点）；
                    // 两参版会把音量压成 0.25，那样落槌反而比警告声还轻
                    PositionedSoundInstance.master(
                        SoundEvent.of(Identifier.of("cobblemarket", "auction_gavel")),
                        1.0f,
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
                // 拍卖是**异步结算**——出价时还不知道会不会赢，所以起点只能在**包到达这一刻**看现场：
                // 玩家正好开着出价弹窗就从那个图标起飞，否则传 null 走原本的掉落弹跳（那才是拍卖的默认路径）。
                // 购买那条走的是另一套：弹窗关闭前用 setPendingOrigin 提前登记。
                val origin = (MinecraftClient.getInstance().currentScreen as? AuctionScreen)?.bidDialogIconOrigin()
                PokemonCelebrationAnimation.trigger(
                    payload.speciesId, payload.aspects, payload.shiny, payload.source, payload.heldItemNbt,
                    payload.heldItemVisible, origin
                )
            }
        }

        registerS2C(ItemCelebrationPayload.ID, ItemCelebrationPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                // 获得物品庆祝动画：与精灵侧同一口径，渲染是独立的一套（物品图标而非 3D 模型）
                ItemCelebrationAnimation.trigger(payload.itemNbt, payload.count, payload.source)
            }
        }

        registerS2C(MarketStatePayload.ID, MarketStatePayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                MarketStateCache.enabled = payload.enabled
                // 管理面板打开时同步开关按钮图标（登录补发 / 他人命令切换都能跟上）；
                // 开关按钮 2026-09-12 从入口界面移到了管理面板
                (MinecraftClient.getInstance().currentScreen as? AdminScreen)?.updateMarketSwitchIcon()
                // 开市/停市音**不在这里播**：管理面板的开关按钮会先乐观更新 MarketStateCache，
                // 广播回来时"状态看起来没变"，客户端判断不出刚切换过 —— 改由服务端发（toggleMarketEnabled）
            }
        }

        registerS2C(PointsStatusPayload.ID, PointsStatusPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                PointsStateCache.enabled = payload.enabled
                // 玩家间交易开关：上架界面的货币按钮按它显隐（那边每次 init 时读缓存，不用额外通知）
                PointsStateCache.marketEnabled = payload.marketEnabled
                // 界面已打开时同步按钮置灰态（登录补发 / 后续管理端切换都能跟上）
                (MinecraftClient.getInstance().currentScreen as? MarketEntryScreen)?.updatePointsEntryState()
                // ⚠ 总开关一变，HUD 那一行要立刻跟着显 / 隐 —— 而 RP 余额只在**余额包**里下发，
                //   光更新 enabled 不够（开关打开时余额还是空的、关掉时又留着旧值），所以补拉一次
                sendToServer(RequestBalancePayload())
            }
        }

        // 点券商城快照（档位 / 有没有收款码 / 当前订单）：**先落缓存再分发给界面** ——
        // 包在请求时到，而弹窗要等有单才建，不缓存的话「包先到弹窗后建」会拿到空数据
        registerS2C(PointsShopInfoPayload.ID, PointsShopInfoPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                PointsShopCache.tiers = payload.tiers
                PointsShopCache.hasQr = payload.hasQr
                PointsShopCache.orders = payload.orders
                PointsShopCache.currencyLabel = payload.currencyLabel
                PointsShopCache.loaded = true
                when (val screen = MinecraftClient.getInstance().currentScreen) {
                    is PointsRechargeScreen -> screen.onShopInfo()
                    is PointsMarketScreen -> screen.onShopInfo()
                }
            }
        }

        // RP 商城（商品区）快照：**先落缓存**（同上面点券商城快照的理由）——
        // 界面每帧直接读 PointMarketCache，包一到下一帧就画出来，不需要通知重建
        registerS2C(PointMarketItemsPayload.ID, PointMarketItemsPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                PointMarketCache.accept(payload)
            }
        }

        // 配方快照列表（只有持 points.shop 的人会收到）：落缓存，快照弹窗直接读它
        registerS2C(ShopSnapshotsPayload.ID, ShopSnapshotsPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                PointMarketCache.acceptSnapshots(payload)
            }
        }

        // 形态候选表（species feature；同上只有持 points.shop 的人会收到）：落缓存，编辑窗直接读它
        registerS2C(SpeciesFeatureAspectsPayload.ID, SpeciesFeatureAspectsPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                PointMarketCache.acceptFeatureAspects(payload)
            }
        }

        // RP 配置快照（仅服主会收到）：填进配置界面的本地编辑态
        registerS2C(PointsConfigPayload.ID, PointsConfigPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                (MinecraftClient.getInstance().currentScreen as? PointsConfigScreen)?.onConfig(payload)
            }
        }
        // 兑换码管理：生成结果与列表（照上面按「当前界面是不是它」分发）
        registerS2C(PointCodesCreatedPayload.ID, PointCodesCreatedPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                (MinecraftClient.getInstance().currentScreen as? PointsCodeScreen)?.onPointCodesCreated(payload)
            }
        }
        registerS2C(PointCodesPayload.ID, PointCodesPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                (MinecraftClient.getInstance().currentScreen as? PointsCodeScreen)?.onPointCodes(payload)
            }
        }

        // 我的兑换记录（玩家侧）：**先落缓存再分发给界面** —— 同上面点券商城快照，
        // 包到的时候界面可能还没建出来（返回再进 / resize 重建），不缓存就白跑一趟
        registerS2C(PointRecordsPayload.ID, PointRecordsPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                PointsRecordCache.entries = payload.entries
                PointsRecordCache.loaded = true
                (MinecraftClient.getInstance().currentScreen as? PointsRecordScreen)?.onRecords()
            }
        }

        // 档位「RP 券」图（服务端读的字节 → 本地动态纹理）；**空数组 = 读不出来**（回退内置券图）。
        // 不需要通知界面重建：充值界面的 render 每帧直接读 TierIconTextures，图到了下一帧就画上
        registerS2C(TierIconPayload.ID, TierIconPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                TierIconTextures.accept(payload.fileName, payload.bytes)
            }
        }

        // 管理端订单列表（仅 OP 会收到）：订单界面自己管 tab；管理面板只借它数一下
        // 「有几笔待确认」来点入口红点 —— 两份列表共用同一个包，靠 includeFinished 区分
        registerS2C(PointOrdersPayload.ID, PointOrdersPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                when (val screen = MinecraftClient.getInstance().currentScreen) {
                    is AdminPointOrdersScreen -> screen.onOrders(payload)
                    is AdminScreen -> screen.onPointOrders(payload)
                }
            }
        }

        // 收款码图片（服务端读的字节 → 本地动态纹理）；**空数组 = 读不出来**
        registerS2C(PayQrImagePayload.ID, PayQrImagePayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                if (payload.bytes.isEmpty()) PayQrTexture.markUnavailable() else PayQrTexture.accept(payload.bytes)
                (MinecraftClient.getInstance().currentScreen as? PointsRechargeScreen)?.onQrImage()
            }
        }

        // 通用音效通道：服务端事件（出价被超越 / 自动划扣 / 逾期 / 卖出到账 / 权限变更）→ 播一个音效
        registerS2C(PlaySoundPayload.ID, PlaySoundPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute { playMarketSound(payload.soundId) }
        }

        // 聊天播报悬浮大图的数据（客户端悬停在拍卖/求购播报上时请求，见 ChatObjectPreview）
        registerS2C(ObjectPreviewPayload.ID, ObjectPreviewPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute { ChatObjectPreview.onData(payload) }
        }

        // 上线待办概况（登录时服务端推一次）：有东西要领就安排一声「离线收益」提示音，
        // 延迟 2 秒等世界加载完（到点由下面的 tick 检查播，一次上线只响一次）
        registerS2C(PendingReturnsPayload.ID, PendingReturnsPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                MarketStateCache.pendingReturns = payload.count
                MarketStateCache.offlineRewardAt =
                    if (payload.count > 0 || payload.hasBalance) System.currentTimeMillis() + 2_000 else 0L
            }
        }

        // 我的权限节点快照（登录 + 授予/收回/服主名单变更时推送）：客户端据此决定管理入口与各按钮的可用状态
        registerS2C(PermissionSnapshotPayload.ID, PermissionSnapshotPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                ClientPermissionCache.update(payload.nodes, payload.owner, payload.canManage, payload.ownerListEmpty)
                // 界面正开着时即时重算置灰（被授予/收回的玩家不用重开界面）
                (MinecraftClient.getInstance().currentScreen as? AdminScreen)?.refreshPermissionState()
                (MinecraftClient.getInstance().currentScreen as? MarketEntryScreen)?.refreshPermissionState()
            }
        }

        // 权限列表数据（权限管理界面；界面没开就丢弃）
        registerS2C(PermissionListDataPayload.ID, PermissionListDataPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                (MinecraftClient.getInstance().currentScreen as? PermissionScreen)?.onPermissionList(payload)
            }
        }

        // 权限变更审计（权限管理界面的「变更记录」页签）
        registerS2C(PermissionAuditPayload.ID, PermissionAuditPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                (MinecraftClient.getInstance().currentScreen as? PermissionScreen)?.onPermissionAudit(payload)
            }
        }

        // 物种作息表（图标睡眠姿态的依据）：Cobblemon 不做 resting 的网络同步，
        // 专用服务器上客户端读到的永远是默认值（canSleep=false），必须靠这张表
        registerS2C(SpeciesRestPayload.ID, SpeciesRestPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                ClientRestCache.update(payload.entries)
            }
        }

        registerS2C(AuctionWarnSoundPayload.ID, AuctionWarnSoundPayload.CODEC) { payload ->
            MinecraftClient.getInstance().execute {
                // 渐强警告声：1→0.4、2→0.6、3→0.8（与成交落槌 1.0 递进）
                // ⚠ 必须走**三参**重载：两参版 `master(sound, x)` 的 x 是 **pitch**、音量被写死成 0.25。
                //   原先传两参等于「响度不变、锤子越敲越尖」，人耳听感反而像渐弱（2026-09-15 用户听出来的）。
                //   要调音量就得显式给 pitch：保持 1.0 = 原音高。
                val volume = when (payload.knock) { 1 -> 0.4f; 2 -> 0.6f; else -> 0.8f }
                MinecraftClient.getInstance().soundManager.play(
                    PositionedSoundInstance.master(
                        SoundEvent.of(Identifier.of("cobblemarket", "auction_gavel")),
                        1.0f,
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

        // 封禁变更记录（封禁管理界面的「变更记录」页签）
        registerS2C(BanChangesPayload.ID, BanChangesPayload.CODEC) { payload ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is AdminBanScreen) {
                    screen.onBanChanges(payload)
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
                } else if (screen is AdminFinanceScreen) {
                    // 金融管理「禁止借款」页签的搜索框用的同一套联想
                    screen.onNameSuggestions(payload)
                } else if (screen is PermissionScreen) {
                    // 权限管理界面也用同一套服务端玩家名联想（服务端那处权限已放宽到「任一管理权限」）
                    screen.onNameSuggestions(payload)
                } else if (screen is PointsCodeScreen) {
                    // 兑换码管理：生成弹窗的「绑定玩家」输入框（同样含离线玩家）
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
                1.0f,
                0.5f // 音量（两参重载固定 0.25 太轻，同 playResultSound）
            )
        )
    }

    /** 交易操作结果音效（成功/失败），所有 MarketResultPayload 到达时统一播放。 */
    fun playResultSound(success: Boolean) {
        MinecraftClient.getInstance().soundManager.play(
            PositionedSoundInstance.master(
                SoundEvent.of(Identifier.of("cobblemarket", if (success) "result_success" else "result_fail")),
                1.0f,
                // 音量必须走三参版显式给：两参重载写死 0.25（那是 MC 给 UI 按钮音的档位），反馈音太轻。
                // 0.5 是 2026-09-16 试听后定的档位（1.0 在正常游戏音量下偏大）
                0.5f
            )
        )
    }
}

/** 客户端本地校验失败音效（弹窗输入非法、关市拦截等场景共用） */
fun playFailSound() {
    MinecraftClient.getInstance().soundManager.play(
        PositionedSoundInstance.master(
            SoundEvent.of(Identifier.of("cobblemarket", "fail")),
            1.0f,
            0.5f // 音量（两参重载固定 0.25 太轻，档位同 playResultSound）
        )
    )
}

/**
 * 「旧素材」音效（在剪映里增强过的那批）：走服务端通道时用 0.5 档，
 * 与客户端本地直接播的 [playResultSound] / [playFailSound] / [playEntrySound] 保持同档 ——
 * 同一枚素材（success.ogg / fail.ogg）两条通道两个音量会明显听出响度差。
 */
private val LEGACY_VOLUME_SOUNDS = setOf("result_success", "result_fail")

/**
 * 播一个模组音效。服务端事件音效走 [PlaySoundPayload]，客户端本地事件（开市/停市、离线收益）直接调这里。
 *
 * ⚠ 音量按**素材响度**分两档给（不是按用途）：
 * - **0.9**：2026-09-16 那批按「别做音量增强」导出的新素材（出价被超越、划扣、逾期、卖出到账、
 *   权限变更、开/停市、离线收益、金币…）—— 同样用 0.5 播会明显偏轻（用户实机反馈）
 * - **0.5**：[LEGACY_VOLUME_SOUNDS] 那几枚旧素材，与客户端本地播的同名音效同档
 *
 * 若以后新旧素材统一重做，这两档可以合并回一个值。
 */
fun playMarketSound(soundId: String) {
    MinecraftClient.getInstance().soundManager.play(
        PositionedSoundInstance.master(
            SoundEvent.of(Identifier.of("cobblemarket", soundId)),
            1.0f,
            if (soundId in LEGACY_VOLUME_SOUNDS) 0.5f else 0.9f
        )
    )
}

/**
 * **本地按钮点击**音效 —— 给**自绘元素**用（不走 `NineSliceButton` 的格子 / 列表行 / 选项行）。
 *
 * ⚠ 必须与 `NineSliceButton` 内置的点击音**同档**：那个走
 * `PositionedSoundInstance.master(sound, pitch)`，音量是**默认的 0.25**。
 * 而 [playMarketSound] 是按**素材响度**分档的（0.9 / 0.5）、供**服务端事件音效**使用 ——
 * 拿它播点击音会**响三倍多**。
 *
 * ⚠ **这个坑 2026-09-25 连踩两次**：先是点商品格子（用户报「所有按钮就这一个音量最大」），
 * 接着是点快照行（用户报「按钮点击声很大」）—— 两次都是把点击音喂给了 [playMarketSound]。
 * ⇒ **凡自绘元素要播点击音，一律调这个函数，别碰 playMarketSound。**
 */
fun playButtonClickSound() {
    MinecraftClient.getInstance().soundManager.play(
        PositionedSoundInstance.master(
            SoundEvent.of(Identifier.of("cobblemarket", "button_click")),
            1.0f
        )
    )
}

// ── 额度快照请求：未响应自动重试 ──

/** 重发间隔：大于服务端 500ms 限流窗口，保证重发必定被受理 */
private const val CREDIT_INFO_RETRY_MS = 600L

/** 最多重发次数：正常 600ms 内必回，回不来多半是服务端异常，用尽后等下次进界面 */
private const val CREDIT_INFO_MAX_RETRIES = 3

private var creditInfoPending = false
private var creditInfoRequestedAt = 0L
private var creditInfoRetries = 0

/**
 * 界面向服务端要额度快照（CreditInfoPayload）走这里，别直接发 RequestCreditInfoPayload。
 *
 * 服务端读请求有 500ms 限流（RequestThrottle），超限的请求被**静默丢弃**；而界面是
 * 「init 里发一次就置 infoLoaded」的写法，收不到响应就永久停在空数据——应急贷款的三档
 * 方案按钮、确认弹窗内容都依赖这份快照，被吞掉后弹窗连背景框都不画（整片空白）。
 * 玩家快速连点两个界面（喵喵银行 → 应急贷款）时后一次必被吞，这里记录待响应状态，
 * 超时未收到响应就重发；重发间隔大于限流窗口，重发必定被受理。
 *
 * 仅界面初始化走这条路径；60 秒兜底轮询在 tick 里裸发，不参与重试。
 */
fun requestCreditInfo() {
    creditInfoPending = true
    creditInfoRequestedAt = System.currentTimeMillis()
    creditInfoRetries = 0
    sendToServer(RequestCreditInfoPayload())
}

/** 客户端每 tick 检查：额度请求迟迟没回就重发（收到响应在 registerS2C 里清 pending） */
private fun tickCreditInfoRetry(client: MinecraftClient, now: Long) {
    if (!creditInfoPending) return
    if (client.player == null) {
        // 已离开世界：没有连接可发，作废待响应状态（重新进世界后由 60 秒兜底轮询补上）
        creditInfoPending = false
        return
    }
    if (now - creditInfoRequestedAt < CREDIT_INFO_RETRY_MS) return
    if (creditInfoRetries >= CREDIT_INFO_MAX_RETRIES) {
        creditInfoPending = false
        return
    }
    creditInfoRetries++
    creditInfoRequestedAt = now
    sendToServer(RequestCreditInfoPayload())
}

/**
 * 支持按 E 返回游戏的本模组界面白名单。
 * 新增界面要支持 E 键关闭 = 在这里补一行（别把白名单散回 tick 里）。
 * 输入框聚焦时 E 不生效（打字保护在调用侧判断）。
 */
/** 余额 HUD：金额 + 货币符号（金色，金额规范色）；位置可配（设置 → 余额HUD位置设置 → 自定义拖动）；只在本模组界面上常驻（无界面时照常；渲染在最顶层，弹窗打开时也可见——竞价/购买时玩家能看到剩余余额）；开关关闭、未进世界时不画。
 *  公开顶层函数：HudRenderCallback（无界面）与 ScreenMixin（界面之上）两处调用。 */

/** 位置编辑中的实时归一化坐标（位置编辑界面打开时非 null；渲染优先于配置值，实现拖动时 HUD 跟随） */
var hudPosOverride: Pair<Float, Float>? = null

/** 余额 HUD 左上角像素坐标：归一化比例 × (屏尺寸 − HUD 尺寸)，钳制在屏内。
 *  HUD 两处渲染点与位置编辑界面共用，位置换算只此一处。 */
fun balanceHudPixelPos(screenW: Int, screenH: Int, hudW: Int, hudH: Int): Pair<Int, Int> {
    val (nx, ny) = hudPosOverride ?: (ClientConfig.balanceHudX to ClientConfig.balanceHudY)
    val maxX = (screenW - hudW).coerceAtLeast(0)
    val maxY = (screenH - hudH).coerceAtLeast(0)
    return ((nx * maxX + 0.5f).toInt()).coerceIn(0, maxX) to ((ny * maxY + 0.5f).toInt()).coerceIn(0, maxY)
}

/** 余额 HUD 当前尺寸（宽随最长那行文字自适应，**高按显示几行**：只有主货币 = 16，带 RP = 32） */
fun balanceHudSize(client: net.minecraft.client.MinecraftClient): Pair<Int, Int> {
    // ⚠ 宽度必须与绘制同源（`amountUnitWidth`）：物品本位货币时单位是那件物品的图标，
    //   与 `₽` 宽度不同 —— 自己写 `getWidth("$金额 ₽")` 会算窄，图标就压出框外
    val mainW = amountUnitWidth(client.textRenderer, hudBalanceText(client), isPoints = false)
    val hasPoints = pointsHudRowVisible()
    val pointsW = if (hasPoints) client.textRenderer.getWidth(BalanceCache.pointsBalance) + unitGap(client.textRenderer) + POINTS_SYMBOL_INLINE_SIZE else 0
    val h = if (hasPoints) HUD_TWO_LINE_H else HUD_ROW_H
    return (maxOf(mainW, pointsW) + 10) to h
}

/** HUD 单行时的高度（**原有值，一行时观感一字不动**） */
private const val HUD_ROW_H = 16

/** 文字相对框顶的偏移（文字 8px 高）。两行时它同时是**上留白**和**下留白** */
private const val HUD_TEXT_DY = 4

/** 两行时**两行文字之间**那道缝。上下留白走 [HUD_TEXT_DY]，这个只管中间 */
private const val HUD_TWO_LINE_GAP = 2

/**
 * 两行时的框高 = 上留白 + 文字 8 + 行间缝 + 文字 8 + 下留白。
 *
 * ⚠ **三处留白必须各自独立**：早先按「每行高 × 2 + 4」推框高，底部留白被绑死成
 * 「行高 − 8」，和行间距联动 —— 想只压两行中间那道缝根本压不动，改了也看不出变化
 * （2026-09-19 用户指出的就是这里）。要调就调上面三个量，别再合成一个。
 */
private const val HUD_TWO_LINE_H = HUD_TEXT_DY * 2 + 8 * 2 + HUD_TWO_LINE_GAP

/**
 * RP 那一行现在**有没有东西可显示**：服务器开着 RP（没开就压根不下发余额）且下发了余额。
 *
 * ⚠ 显隐**跟着主行走**（2026-09-28 把两个开关合并了）—— 这里不再判开关，
 * 三态（常显 / 变动时显 5 秒 / 关）由 [renderBalanceHud] 对**整个框**统一判。
 */
private fun pointsHudRowVisible(): Boolean =
    PointsStateCache.enabled && BalanceCache.pointsBalance.isNotEmpty()

/** RP 行 ON_CHANGE 的变动检测用（与主行各自独立） */
private var lastHudPointsText: String? = null
private var lastPointsChangeAt = 0L
private var lastPointsRawForDiff: Long? = null
private var hudPointsDiff = 0L
private var hudPointsDiffUntil = 0L

/** 上次 HUD 余额文本（ON_CHANGE 模式变动检测） */
private var lastHudBalanceText: String? = null

/** ON_CHANGE 模式：余额最近一次变动时刻 */
private var lastBalanceChangeAt = 0L

/** 余额变动提示：上次原始值 + 差值 + 显示截止时间（+绿/-红浮字 2 秒，照 CobbleDollars 的变动提示） */
private var lastBalanceRaw: Long? = null
private var hudDiff = 0L
private var hudDiffUntil = 0L

/**
 * 余额 HUD 的绘制。
 *
 * @param onTop 本帧从**哪条路径**来 —— 两条路径分工互补，缺一就会重影、或者该显示的不显示：
 * - `true` = 界面**之上**（`ScreenMixin.renderWithTooltip` 的 TAIL）：**只在本模组界面**画 ——
 *   竞价 / 购买弹窗里要清清楚楚看到余额
 * - `false` = **HUD 层**（`registerHudRender`，在界面**之前**画）：本模组界面时**跳过**（上面那份会画，
 *   两份叠一起是重影），**其余界面照画** ⇒ 被界面的半透明遮罩**自然压暗** ——
 *   这正是「打开背包 / ESC 菜单时，余额 HUD 在下面淡淡地透出来」的效果（用户 2026-09-23 要求），
 *   不用自己做暗色滤镜
 */
fun renderBalanceHud(context: net.minecraft.client.gui.DrawContext, onTop: Boolean) {
    val client = MinecraftClient.getInstance()
    if (client.player == null) return
    // 位置编辑界面：无视显示模式与淡出强制常亮（否则「关闭」模式下玩家看不到 HUD，无从拖动）
    val editingPos = client.currentScreen is BalanceHudPositionScreen
    // F3 调试界面打开时不画（左上角帧率区会被 HUD 挡住）；F1 隐藏 HUD 时也不画（原版 HUD 全没了，
    // 只剩余额 HUD 挂在画面上很碍眼——截图/录屏时尤其明显）。shouldShowDebugHud 内含 hudHidden 判断，
    // F1 后它会返回 false，所以 hudHidden 必须单独判一次
    if (!editingPos && (client.debugHud.shouldShowDebugHud() || client.options.hudHidden)) return
    // 两条路径的分工见上面的 KDoc。判据与自定义光标共用同一份界面白名单
    //（新增本模组界面记得补进 isMarketScreen，否则那个界面看不到余额 HUD）。
    // ⚠ 位置编辑界面本身也在白名单里，所以它天然走「顶层画、HUD 层跳过」，不会重影
    val screen = client.currentScreen
    val marketScreen = screen != null && isMarketScreen(screen)
    // 「淡淡透出来」只对**原版界面**（背包 / ESC 菜单 / 设置 / 聊天）生效 ——
    // 其他模组的界面（Cobblemon PC、JEI 等）不显示，这是用户 2026-09-23 定的范围
    val vanillaScreen = screen != null && screen.javaClass.name.startsWith("net.minecraft.")
    if (onTop) {
        if (!marketScreen) return          // 顶层：只在本模组界面画
    } else {
        if (marketScreen) return           // HUD 层：本模组界面跳过（顶层那份会画，避免重影）
        if (screen != null && !vanillaScreen) return
    }
    val hudAmount = hudBalanceText(client)
    // 这段 text 只用于 ON_CHANGE 模式的变动比对（单位一并参与：切换货币也算「变了」），
    // 真正画到屏幕上的是下面 amountUnitWidth / drawAmountWithUnit 那一对
    val text = "$hudAmount ${inlineCurrencyUnit()}"
    // 余额变动检测（每帧，OFF 模式也跟踪避免切回时误报）：差值驱动 +绿/-红浮字
    val rawNow = hudBalanceRaw(client)
    if (lastBalanceRaw != null && rawNow != lastBalanceRaw!!) {
        hudDiff = rawNow - lastBalanceRaw!!
        hudDiffUntil = System.currentTimeMillis() + 2_000
    }
    lastBalanceRaw = rawNow
    // ON_CHANGE 的计时基准：两行**各自**记录自己的变动时刻（这样 RP 变了同样能把框唤出来），
    // 取更晚的那个当整框的截止时间 —— 一个开关管整框 ⇒ 两行一起亮、一起淡出
    val nowMs = System.currentTimeMillis()
    if (text != lastHudBalanceText) {
        lastHudBalanceText = text
        lastBalanceChangeAt = nowMs
    }
    val pointsText = BalanceCache.pointsBalance
    if (pointsText != lastHudPointsText) {
        lastHudPointsText = pointsText
        lastPointsChangeAt = nowMs
    }
    val lastChangeAt = maxOf(lastBalanceChangeAt, lastPointsChangeAt)
    // 三态显示判断：ALWAYS 恒显；ON_CHANGE **任一行**变动后显 5 秒；OFF 不显
    val visible = editingPos || when (ClientConfig.balanceHudMode) {
        BalanceHudMode.OFF -> false
        BalanceHudMode.ALWAYS -> true
        BalanceHudMode.ON_CHANGE -> nowMs - lastChangeAt < 5_000
    }
    if (!visible) return
    // 「适应」模式淡出：显示期最后 800ms 背景+文字整体线性渐隐（入口动画暗淡同款手法）
    var alphaF = 1f
    if (!editingPos && ClientConfig.balanceHudMode == BalanceHudMode.ON_CHANGE) {
        val remaining = 5_000 - (nowMs - lastChangeAt)
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
    // ── RP 行：与主行**同一个开关**（一个框一起显隐、一起淡出），框高随之 +16 ──
    // ⚠ 仍受「服务器开着 RP 且下发了余额」限制（见 pointsHudRowVisible）
    val showPointsRow = pointsHudRowVisible()

    // 背景框：HUD 专属九宫格贴图（40×40），宽随**最长那行**自适应，高按显示几行（16 / 32）
    // ⚠ 主行宽度必须与绘制同源（`amountUnitWidth`）：物品本位货币时单位是那件物品的图标，
    //   与 `₽` 宽度不同 —— 按「金额 + ₽」的宽度算会让图标压出框外
    val textW = amountUnitWidth(client.textRenderer, hudAmount, isPoints = false)
    val pointsInnerW = client.textRenderer.getWidth(BalanceCache.pointsBalance)
    val pointsTextW = pointsInnerW + unitGap(client.textRenderer) + POINTS_SYMBOL_INLINE_SIZE
    val hudW = maxOf(textW, if (showPointsRow) pointsTextW else 0) + 10
    // 框高：单行 16；两行 = 上留白 + 文字 + 行间缝 + 文字 + 下留白（三处各自独立，见 HUD_TWO_LINE_H）
    val hudH = if (showPointsRow) HUD_TWO_LINE_H else HUD_ROW_H
    val textDy = HUD_TEXT_DY
    val hudPos = balanceHudPixelPos(context.scaledWindowWidth, context.scaledWindowHeight, hudW, hudH)
    val hudX = hudPos.first
    val hudY = hudPos.second
    drawNineSlice(
        context,
        HUD_BALANCE_BG,
        hudX, hudY, hudW, hudH,
        0, HUD_BALANCE_BG_TEX_H
    )
    // ⚠ 主行：金额与单位**拆开画**（物品本位货币时单位是那件物品的图标，拼不进 Text）
    drawAmountWithUnit(context, client.textRenderer, hudAmount, false, hudX + 5, hudY + textDy, 0xFFAA00)
    if (showPointsRow) {
        // RP 行：**数字在前、图标在后当单位**（与主行 `1000 ₽` 同一个格局）——
        // 图标放前面的话它占着左边起始位、数字被顶到右边，两行的数字就不在一条竖线上了。
        // RP 行文字的 y：第一行文字底（hudY + textDy + 8）+ 行间缝
        val pointsRowY = hudY + textDy + 8 + HUD_TWO_LINE_GAP
        // alpha 用外层已经设好的整框 alphaF（一个开关 ⇒ 两行一起淡出），这里不再单独乘
        context.drawTextWithShadow(client.textRenderer, BalanceCache.pointsBalance, hudX + 5, pointsRowY, 0xFFAA00)
        val iconX = hudX + 5 + client.textRenderer.getWidth(BalanceCache.pointsBalance) + unitGap(client.textRenderer)
        drawPointsSymbol(context, iconX, pointsRowY, POINTS_SYMBOL_INLINE_SIZE)
        // RP 变动浮字（照主行那份）：跟在这一行右边
        val rawPoints = BalanceCache.pointsRaw
        if (lastPointsRawForDiff != null && rawPoints != lastPointsRawForDiff!!) {
            hudPointsDiff = rawPoints - lastPointsRawForDiff!!
            hudPointsDiffUntil = System.currentTimeMillis() + 2_000
        }
        lastPointsRawForDiff = rawPoints
        if (hudPointsDiff != 0L && System.currentTimeMillis() < hudPointsDiffUntil) {
            val dt = if (hudPointsDiff > 0) "+${formatBalanceLong(hudPointsDiff)}" else formatBalanceLong(hudPointsDiff)
            val dc = if (hudPointsDiff > 0) 0x55FF55 else 0xFF5555
            val dw = client.textRenderer.getWidth(dt)
            // ⚠ 贴**框右缘之外**（2026-09-26 用户报：用 RP 购买时那条红字落在背景框**里面**）。
            //   框宽按两行里**最长**的那行算（见上面 hudW），而从"本行数字右缘"起画的话，
            //   短行（通常是 RP 行）的浮字必然落在框内 —— 用 RP 购买时变的正是 RP 行。
            //   ⚠ 2026-09-18 那条「贴框边会让浮字飘在半空」是针对**当时只有一行**说的：
            //     那时框宽 = 该行宽度，贴框边就等于紧跟数字；加了 RP 行之后这个前提不成立了。
            //     统一贴框右缘还有个好处：两行的浮字对齐在同一条竖线上。
            val rx = hudX + hudW + 4
            val dx2 = if (rx + dw <= context.scaledWindowWidth) rx else (hudX - 4 - dw).coerceAtLeast(0)
            context.drawTextWithShadow(client.textRenderer, dt, dx2, pointsRowY, dc)
        }
    }
    // 余额变动浮字：+绿/-红，2 秒后消失（照 CobbleDollars 右下角的变动提示）
    if (hudDiff != 0L && System.currentTimeMillis() < hudDiffUntil) {
        val diffText = if (hudDiff > 0) "+${formatBalanceLong(hudDiff)}" else formatBalanceLong(hudDiff)
        val diffColor = if (hudDiff > 0) 0x55FF55 else 0xFF5555
        // 默认画在 HUD 右侧；右侧放不下（HUD 贴右边缘）才改画左侧——否则紧贴右边缘的 HUD
        // 会把浮字整条推出屏幕。按「右侧实际放得下」判断，而不是「HUD 中心落在哪半边」：
        // 后者会把整个右半屏（含吸附到屏幕中心的 HUD）都判成左侧
        val diffW = client.textRenderer.getWidth(diffText)
        // 同上：贴**框右缘之外**（理由见上面 RP 行那处）—— 主行不是最长行时（RP 比主货币长），
        // 从"本行数字右缘"起画同样会落进框里
        val rightX = hudX + hudW + 4
        val diffX = if (rightX + diffW <= context.scaledWindowWidth) rightX
        else (hudX - 4 - diffW).coerceAtLeast(0)
        context.drawTextWithShadow(client.textRenderer, diffText, diffX, hudY + textDy, diffColor)
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

internal fun isMarketScreen(s: net.minecraft.client.gui.screen.Screen?): Boolean =
    s is QuickShowcaseScreen ||
        s is MarketScreen || s is SellSelectScreen || s is HistoryScreen || s is MarketEntryScreen ||
        s is ItemMarketScreen || s is ItemSellScreen || s is ItemReturnScreen || s is PokemonReturnScreen ||
        s is BuyConfirmScreen || s is AdminScreen || s is PermissionScreen || s is AdminPokemonScreen || s is AdminItemScreen || s is AdminBanScreen ||
        s is BlacklistScreen || s is PriceLimitScreen || s is AuctionScreen || s is AuctionCreateScreen ||
        s is BuyOrderScreen || s is AdminAuctionScreen || s is ServerConfigScreen || s is FinanceConfigScreen ||
        s is PurpleCardConfigScreen || s is PurpleCardApplyScreen || s is PurpleCardApplyConditionsScreen ||
        s is BlackCardConfigScreen || s is BlackCardApplyScreen || s is BlackCardApplyConditionsScreen || s is ItemVariantSelectScreen ||
        s is AdminFinanceScreen ||
        s is MeowthBankScreen || s is LoanScreen || s is LoanHistoryScreen || s is RepayScreen || s is MeowthPayScreen ||
        s is DepositScreen || s is BalanceHudPositionScreen || s is PointsMarketScreen || s is PointsRechargeScreen ||
        s is PointsConfigScreen || s is AdminPointOrdersScreen || s is PointsCodeScreen || s is PointsRecordScreen ||
        s is GatedFormScreen
