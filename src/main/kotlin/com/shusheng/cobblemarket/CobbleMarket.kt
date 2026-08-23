package com.shusheng.cobblemarket

import com.shusheng.cobblemarket.command.MarketCommands
import com.shusheng.cobblemarket.event.TransactionHistory
import com.shusheng.cobblemarket.market.ItemMarketState
import com.shusheng.cobblemarket.market.MarketState
import com.shusheng.cobblemarket.network.AuctionNetwork
import com.shusheng.cobblemarket.network.BalanceNetwork
import com.shusheng.cobblemarket.network.BanNetwork
import com.shusheng.cobblemarket.network.BlacklistNetwork
import com.shusheng.cobblemarket.network.BuyOrderNetwork
import com.shusheng.cobblemarket.network.CelebrationNetwork
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
		BuyOrderNetwork.register()
		CelebrationNetwork.register()
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
			// 玩家退出时 MC 立即保存其玩家数据（货/钱已扣就此落盘），模组状态立即追上，
			// 否则此后杀进程/崩溃会形成错位导致货蒸发（见 PersistHelper 注释）
			com.shusheng.cobblemarket.util.PersistHelper.onPlayerDisconnect(handler.player.server)
		}
		// 交易后节流全量落盘的定时检查（见 PersistHelper）
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register { server ->
			com.shusheng.cobblemarket.util.PersistHelper.tick(server)
		}
		ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
			val player = handler.player
			val state = MarketState.get(player.server)
			val stateServer = player.server

			stateServer.execute {
				val balance = state.getPendingBalance(player.uuid)
				if (balance > 0) {
					player.sendMessage(
						Text.translatable("cobblemarket.cmd.login_earnings", balance, com.shusheng.cobblemarket.config.CurrencyHandler.currencyText())
							.formatted(Formatting.GREEN),
						false
					)
				}
				// 离线通知补发（求购单交付/接受/拒绝等；文本 JSON 反序列化后按玩家语言渲染）
				com.shusheng.cobblemarket.market.OfflineMessageState.get(player.server)
					.take(player.uuid)
					.forEach { json ->
						try {
							// Text.Serialization.fromJson 是包私有：走公开的 TextCodecs.CODEC
							val text = net.minecraft.text.TextCodecs.CODEC.decode(
								com.mojang.serialization.JsonOps.INSTANCE,
								com.google.gson.JsonParser.parseString(json)
							).getOrThrow().first
							player.sendMessage(text, false)
						} catch (e: Exception) {
							LOGGER.warn("Failed to deliver offline message to {}: {}", player.uuid, e.message)
						}
					}
			}
		}

		LOGGER.info("CobbleMarket ready!")
	}

	fun id(path: String): Identifier = Identifier.of(MOD_ID, path)
}
