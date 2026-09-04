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
import com.shusheng.cobblemarket.network.FinanceNetwork
import com.shusheng.cobblemarket.network.ItemBlacklistNetwork
import com.shusheng.cobblemarket.network.MarketNetwork
import com.shusheng.cobblemarket.network.ServerConfigNetwork
import com.shusheng.cobblemarket.network.PriceLimitNetwork
import com.shusheng.cobblemarket.platform.onPlayerDisconnect
import com.shusheng.cobblemarket.platform.onPlayerJoin
import com.shusheng.cobblemarket.platform.onServerStarted
import com.shusheng.cobblemarket.platform.onServerStarting
import com.shusheng.cobblemarket.platform.onServerStopped
import com.shusheng.cobblemarket.platform.onServerTickEnd
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory

object CobbleMarket {
	const val MOD_ID: String = "cobblemarket"

	val LOGGER = LoggerFactory.getLogger(MOD_ID)

	/** 紫卡丢弃扫描节流计数（每 10 tick = 0.5 秒扫一次） */
	private var cardScanTick = 0
	/** 非持有者背包紫卡收回扫描节流（每 1200 tick = 60 秒扫一次） */
	private var cardInvScanTick = 0

	/** 由各平台入口类（fabric 的 CobbleMarketFabric 等）在对应初始化阶段调用。 */
	fun init() {
		LOGGER.info("CobbleMarket initializing...")
		com.shusheng.cobblemarket.config.CobbleMarketConfig.load()
		// 喵喵紫卡物品注册（额度凭证，绑定 FinanceState 持有者状态）
		com.shusheng.cobblemarket.platform.registerItems(
			listOf(
				id("meowth_purple_card") to { com.shusheng.cobblemarket.finance.MeowthPurpleCardItem() }
			)
		)
		MarketNetwork.register()
		BanNetwork.register()
		BlacklistNetwork.register()
		ItemBlacklistNetwork.register()
		PriceLimitNetwork.register()
		AuctionNetwork.register()
		BuyOrderNetwork.register()
		CelebrationNetwork.register()
		ServerConfigNetwork.register()
		BalanceNetwork.register()
		FinanceNetwork.register()
		MarketCommands.register()
		com.shusheng.cobblemarket.event.TransactionLogger.register()
		TransactionHistory.register()

		onServerStarting { server ->
			// 清空上一世界残留的保存待办（单机切存档同进程，静态状态不得跨世界——见 PersistHelper.reset）
			com.shusheng.cobblemarket.util.PersistHelper.reset()
			// 世界加载前校验 PersistentState 数据文件：损坏则从 .bak 恢复，再制作新备份
			com.shusheng.cobblemarket.util.StateBackup.verifyAndBackup(server)
		}
		onServerStarted { server ->
			TransactionHistory.historyRef = TransactionHistory.get(server)
			// 金融系统状态预热（照 TransactionHistory 模式；批次 3 起实际读写）
			com.shusheng.cobblemarket.finance.FinanceState.get(server)
		}
		onServerStopped { server ->
			// 正常关服保存完成后，用最新数据刷新备份
			com.shusheng.cobblemarket.util.StateBackup.backupOnStop(server)
			// 备份完成后释放历史记录静态引用；不在 STOPPING 提前置 null，
			// 覆盖 STOPPING→STOPPED 窗口内的事件写入（CSV 文件日志同步落盘）
			TransactionHistory.historyRef = null
		}

		onPlayerDisconnect { player ->
			com.shusheng.cobblemarket.util.RequestThrottle.onDisconnect(player.uuid)
			// 玩家退出时 MC 立即保存其玩家数据（货/钱已扣就此落盘），模组状态立即追上，
			// 否则此后杀进程/崩溃会形成错位导致货蒸发（见 PersistHelper 注释）
			com.shusheng.cobblemarket.util.PersistHelper.onPlayerDisconnect(player.server)
		}
		// 交易后节流全量落盘的定时检查（见 PersistHelper）
		onServerTickEnd { server ->
			com.shusheng.cobblemarket.util.PersistHelper.tick(server)
			// 金融系统自动划扣扫描（内部 60 秒节流，见 FinanceService）
			com.shusheng.cobblemarket.finance.FinanceService.tick(server)
			// 喵喵紫卡丢弃即消失扫描（0.5 秒节流，近乎立即可见消失）
			if (++cardScanTick >= 10) {
				cardScanTick = 0
				com.shusheng.cobblemarket.finance.MeowthPurpleCardItem.scanAndDiscardDroppedCards(server)
			}
			// 非持有者背包紫卡收回扫描（60 秒节流，与金融兜底同频）
			if (++cardInvScanTick >= 1200) {
				cardInvScanTick = 0
				com.shusheng.cobblemarket.finance.MeowthPurpleCardItem.scanPlayerInventories(server)
			}
		}
		onPlayerJoin { player ->
			val state = MarketState.get(player.server)
			val stateServer = player.server

			stateServer.execute {
				// 金融系统：登录即检查该玩家贷款的到期划扣（覆盖「到期后玩家才上线」场景）
				com.shusheng.cobblemarket.finance.FinanceService.onPlayerJoin(player)
				val balance = state.getPendingBalance(player.uuid)
				if (balance > 0) {
					player.sendMessage(
						Text.translatable("cobblemarket.cmd.login_earnings",
							com.shusheng.cobblemarket.config.CurrencyHandler.goldAmount(balance),
							com.shusheng.cobblemarket.config.CurrencyHandler.goldCurrencyText())
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
