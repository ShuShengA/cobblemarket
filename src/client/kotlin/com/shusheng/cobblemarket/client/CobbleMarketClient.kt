package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.CobbleMarket
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
import com.shusheng.cobblemarket.screen.AdminBanScreen
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
                    screen is BuyConfirmScreen || screen is AdminScreen || screen is AdminPokemonScreen || screen is AdminItemScreen || screen is AdminBanScreen || screen is PokemonBlacklistScreen || screen is ItemBlacklistScreen || screen is PriceLimitScreen)
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
                playResultSound(payload.success)
                val screen = client.currentScreen
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
