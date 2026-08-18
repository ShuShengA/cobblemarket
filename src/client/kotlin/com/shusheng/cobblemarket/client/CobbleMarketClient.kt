package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.network.AuctionEventPayload
import com.shusheng.cobblemarket.network.AuctionListDataPayload
import com.shusheng.cobblemarket.network.AuctionSettleSoundPayload
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
import com.shusheng.cobblemarket.screen.AdminAuctionScreen
import com.shusheng.cobblemarket.screen.AdminBanScreen
import com.shusheng.cobblemarket.screen.AuctionCreateScreen
import com.shusheng.cobblemarket.screen.AuctionScreen
import com.shusheng.cobblemarket.screen.AdminItemScreen
import com.shusheng.cobblemarket.screen.AdminPokemonScreen
import com.shusheng.cobblemarket.screen.AdminScreen
import com.shusheng.cobblemarket.screen.BuyConfirmScreen
import com.shusheng.cobblemarket.screen.HistoryScreen
import com.shusheng.cobblemarket.screen.MarketEntryScreen
import com.shusheng.cobblemarket.screen.MarketScreen
import com.shusheng.cobblemarket.screen.ItemBlacklistScreen
import com.shusheng.cobblemarket.screen.ItemMarketScreen
import com.shusheng.cobblemarket.screen.ItemReturnScreen
import com.shusheng.cobblemarket.screen.ItemSellScreen
import com.shusheng.cobblemarket.screen.PokemonBlacklistScreen
import com.shusheng.cobblemarket.screen.PokemonReturnScreen
import com.shusheng.cobblemarket.screen.PriceLimitScreen
import com.shusheng.cobblemarket.screen.SellSelectScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.util.InputUtil
import net.minecraft.sound.SoundEvent
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

object CobbleMarketClient : ClientModInitializer {

    val LOGGER = LoggerFactory.getLogger(CobbleMarket.MOD_ID)

    private lateinit var openMarketKey: KeyBinding
    private var wasEPressed = false

    // 成交铃声定时（tick 触发）：落槌立即播放，铃声 0.4 秒后（多拍卖同批结算时铃声只响一次）
    private var bellSoundAt = 0L

    override fun onInitializeClient() {
        openMarketKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.cobblemarket.open_market",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.cobblemarket"
            )
        )
        ClientTickEvents.END_CLIENT_TICK.register { client ->
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
                if (!inputFocused && (screen is MarketScreen || screen is SellSelectScreen || screen is HistoryScreen || screen is MarketEntryScreen ||
                    screen is ItemMarketScreen || screen is ItemSellScreen || screen is ItemReturnScreen || screen is PokemonReturnScreen ||
                    screen is BuyConfirmScreen || screen is AdminScreen || screen is AdminPokemonScreen || screen is AdminItemScreen || screen is AdminBanScreen || screen is PokemonBlacklistScreen || screen is ItemBlacklistScreen || screen is PriceLimitScreen || screen is AuctionScreen || screen is AuctionCreateScreen)
                ) {
                    client.setScreen(null)
                }
            }
            wasEPressed = ePressed
        }

        ClientPlayNetworking.registerGlobalReceiver(OpenMarketPayload.ID) { _, _ ->
            val client = MinecraftClient.getInstance()
            client.execute {
                playEntrySound()
                client.setScreen(MarketEntryScreen())
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(MarketDataPayload.ID) { payload, _ ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                when (screen) {
                    is MarketScreen -> screen.onMarketData(payload)
                    is AdminPokemonScreen -> screen.onMarketData(payload)
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(ItemMarketDataPayload.ID) { payload, _ ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                when (screen) {
                    is ItemMarketScreen -> screen.onItemMarketData(payload)
                    is AdminItemScreen -> screen.onItemMarketData(payload)
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(MyPokemonListPayload.ID) { payload, _ ->
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

        ClientPlayNetworking.registerGlobalReceiver(HistoryDataPayload.ID) { payload, _ ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is HistoryScreen) {
                    screen.onHistoryData(payload)
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(PokemonReturnDataPayload.ID) { payload, _ ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is PokemonReturnScreen) {
                    screen.onReturnData(payload)
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(ItemReturnDataPayload.ID) { payload, _ ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is ItemReturnScreen) {
                    screen.onReturnData(payload)
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(MarketResultPayload.ID) { payload, _ ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                // 出价成功时不播全局成功音：点击「确认出价」时的金币声即为反馈，避免叠音
                if (!(payload.success && screen is AuctionScreen && screen.isBidDialogOpen())) {
                    playResultSound(payload.success)
                }
                // 交易操作后余额可能变化，主动拉取一次最新余额
                ClientPlayNetworking.send(RequestBalancePayload())
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
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(BalanceDataPayload.ID) { payload, _ ->
            MinecraftClient.getInstance().execute {
                BalanceCache.balance = payload.balance
                BalanceCache.pendingBalance = payload.pendingBalance
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(AuctionSettleSoundPayload.ID) { payload, _ ->
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

        ClientPlayNetworking.registerGlobalReceiver(AuctionWarnSoundPayload.ID) { payload, _ ->
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

        ClientPlayNetworking.registerGlobalReceiver(BanListDataPayload.ID) { payload, _ ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is AdminBanScreen) {
                    screen.onBanList(payload)
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(PokemonBlacklistDataPayload.ID) { payload, _ ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is PokemonBlacklistScreen) {
                    screen.onBlacklistData(payload)
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(ItemBlacklistDataPayload.ID) { payload, _ ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is ItemBlacklistScreen) {
                    screen.onItemBlacklistData(payload)
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(PokemonPriceLimitDataPayload.ID) { payload, _ ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is PriceLimitScreen) {
                    screen.onPokemonPriceLimitData(payload)
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(ItemPriceLimitDataPayload.ID) { payload, _ ->
            val client = MinecraftClient.getInstance()
            client.execute {
                val screen = client.currentScreen
                if (screen is PriceLimitScreen) {
                    screen.onItemPriceLimitData(payload)
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(AuctionListDataPayload.ID) { payload, _ ->
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

        ClientPlayNetworking.registerGlobalReceiver(AuctionEventPayload.ID) { payload, _ ->
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
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cobblemon_smartphone")) {
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
