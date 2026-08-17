package com.shusheng.cobblemarket

import com.shusheng.cobblemarket.command.MarketCommands
import com.shusheng.cobblemarket.event.TransactionHistory
import com.shusheng.cobblemarket.market.ItemMarketState
import com.shusheng.cobblemarket.market.MarketState
import com.shusheng.cobblemarket.network.AuctionNetwork
import com.shusheng.cobblemarket.network.BalanceNetwork
import com.shusheng.cobblemarket.network.BanNetwork
import com.shusheng.cobblemarket.network.BlacklistNetwork
import com.shusheng.cobblemarket.network.ItemBlacklistNetwork
import com.shusheng.cobblemarket.network.MarketNetwork
import com.shusheng.cobblemarket.network.PriceLimitNetwork
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory

object CobbleMarket : ModInitializer {
	const val MOD_ID: String = "cobblemarket"

	val LOGGER = LoggerFactory.getLogger(MOD_ID)

	override fun onInitialize() {
		LOGGER.info("CobbleMarket initializing...")
		com.shusheng.cobblemarket.config.CobbleMarketConfig.load()
		MarketNetwork.register()
		BanNetwork.register()
		BlacklistNetwork.register()
		ItemBlacklistNetwork.register()
		PriceLimitNetwork.register()
		AuctionNetwork.register()
		BalanceNetwork.register()
		MarketCommands.register()
		com.shusheng.cobblemarket.event.TransactionLogger.register()
		TransactionHistory.register()

		ServerLifecycleEvents.SERVER_STARTING.register { server ->
			// 世界加载前校验 PersistentState 数据文件：损坏则从 .bak 恢复，再制作新备份
			com.shusheng.cobblemarket.util.StateBackup.verifyAndBackup(server)
		}
		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			TransactionHistory.historyRef = TransactionHistory.get(server)
		}
		ServerLifecycleEvents.SERVER_STOPPED.register { server ->
			// 正常关服保存完成后，用最新数据刷新备份
			com.shusheng.cobblemarket.util.StateBackup.backupOnStop(server)
			// 备份完成后释放历史记录静态引用；不在 STOPPING 提前置 null，
			// 覆盖 STOPPING→STOPPED 窗口内的事件写入（CSV 文件日志同步落盘）
			TransactionHistory.historyRef = null
		}

		ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
			com.shusheng.cobblemarket.util.RequestThrottle.onDisconnect(handler.player.uuid)
		}
		ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
			val player = handler.player
			val state = MarketState.get(player.server)
			val stateServer = player.server

			stateServer.execute {
				val balance = state.getPendingBalance(player.uuid)
				if (balance > 0) {
					player.sendMessage(
						Text.translatable("cobblemarket.cmd.login_earnings", balance, com.shusheng.cobblemarket.config.CurrencyHandler.getName())
							.formatted(Formatting.GREEN),
						false
					)
				}
			}
		}

		LOGGER.info("CobbleMarket ready!")
	}

	fun id(path: String): Identifier = Identifier.of(MOD_ID, path)
}
