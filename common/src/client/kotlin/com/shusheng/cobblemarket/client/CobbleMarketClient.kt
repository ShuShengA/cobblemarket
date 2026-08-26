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
import com.shusheng.cobblemarket.network.HistoryDataPayload
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
import com.shusheng.cobblemarket.screen.MarketEntryScreen
import com.shusheng.cobblemarket.screen.MarketScreen
import com.shusheng.cobblemarket.screen.ItemMarketScreen
import com.shusheng.cobblemarket.screen.ItemReturnScreen
import com.shusheng.cobblemarket.screen.ItemSellScreen
import com.shusheng.cobblemarket.screen.PokemonReturnScreen
import com.shusheng.cobblemarket.screen.PriceLimitScreen
import com.shusheng.cobblemarket.screen.SellSelectScreen
import com.shusheng.cobblemarket.screen.ServerConfigScreen

import com.shusheng.cobblemarket.platform.isModLoaded
import com.shusheng.cobblemarket.platform.onClientTick
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

object CobbleMarketClient {

    val LOGGER = LoggerFactory.getLogger(CobbleMarket.MOD_ID)

    private lateinit var openMarketKey: KeyBinding
    private var wasEPressed = false

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
                    client.setScreen(null)
                }
            }
            wasEPressed = ePressed
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
private fun isMarketScreen(s: net.minecraft.client.gui.screen.Screen?): Boolean =
    s is MarketScreen || s is SellSelectScreen || s is HistoryScreen || s is MarketEntryScreen ||
        s is ItemMarketScreen || s is ItemSellScreen || s is ItemReturnScreen || s is PokemonReturnScreen ||
        s is BuyConfirmScreen || s is AdminScreen || s is AdminPokemonScreen || s is AdminItemScreen || s is AdminBanScreen ||
        s is BlacklistScreen || s is PriceLimitScreen || s is AuctionScreen || s is AuctionCreateScreen ||
        s is BuyOrderScreen || s is AdminAuctionScreen || s is ServerConfigScreen
