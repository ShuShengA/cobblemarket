package com.shusheng.cobblemarket

import com.shusheng.cobblemarket.network.RestSyncNetwork
import com.shusheng.cobblemarket.permission.PermissionNetwork
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
import com.shusheng.cobblemarket.network.MarketNoticeNetwork
import com.shusheng.cobblemarket.network.ObjectPreviewNetwork
import com.shusheng.cobblemarket.network.PointMarketNetwork
import com.shusheng.cobblemarket.network.PointsNetwork
import com.shusheng.cobblemarket.network.ServerConfigNetwork
import com.shusheng.cobblemarket.network.ShareNetwork
import com.shusheng.cobblemarket.network.SoundNetwork
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

	/** 由各平台入口类（fabric 的 CobbleMarketFabric 等）在对应初始化阶段调用。 */
	fun init() {
		LOGGER.info("CobbleMarket initializing...")
		com.shusheng.cobblemarket.config.CobbleMarketConfig.load()
		// 精灵标记（cobblemarket:shop / cobblemarket:illegal）：注册之后 PokemonProperties 的解析器
		// 就认得这两个 key ⇒ 数据包 / NPC 定义 / spawn 规则里能直接写（详见 PokemonTags 的 KDoc）
		com.shusheng.cobblemarket.util.PokemonTags.register()
		// 喵喵紫卡/黑卡物品注册（额度凭证，绑定 FinanceState 持有者状态）
		com.shusheng.cobblemarket.platform.registerItems(
			listOf(
				id("meowth_purple_card") to { com.shusheng.cobblemarket.finance.MeowthPurpleCardItem() },
				id("meowth_black_card") to { com.shusheng.cobblemarket.finance.MeowthBlackCardItem() }
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
		PermissionNetwork.register()
		RestSyncNetwork.register()
		SoundNetwork.register()
		ObjectPreviewNetwork.register()
		ShareNetwork.register()
		PointsNetwork.register()
		// RP 商城（商品区）：与 RP 充值那条线分开的一套包（商城是服主上架、玩家用 RP 买）
		PointMarketNetwork.register()
		// 上架通知（1.2.2 新增，上浮时与本线独有的 RP 那几行并存）
		MarketNoticeNetwork.register()
		// 市场 ID 卡背景图（可替换贴图：服务端读配置目录 → 下发字节 → 客户端动态纹理）
		com.shusheng.cobblemarket.network.IdCardNetwork.register()
		com.shusheng.cobblemarket.network.PlayerOverviewNetwork.register()
		MarketCommands.register()
		com.shusheng.cobblemarket.event.TransactionLogger.register()
		TransactionHistory.register()
		// RP 奖励系统（数据包驱动）：注册数据包重载监听 + 订阅五个 Cobblemon 事件。
		// ⚠ **默认整块关着**（配置里的 pointsRewardsEnabled）—— 见该字段的 KDoc
		com.shusheng.cobblemarket.currency.rewards.PointsRewards.register()

		onServerStarting { server ->
			// 清空上一世界残留的保存待办（单机切存档同进程，静态状态不得跨世界——见 PersistHelper.reset）
			com.shusheng.cobblemarket.util.PersistHelper.reset()
			com.shusheng.cobblemarket.currency.PointShopService.reset()
			// 收款码 / RP 券图目录：启动时就建好 + 各落一份说明，服主打开 config 就知道图该往哪放
			com.shusheng.cobblemarket.currency.PayQrImage.ensureDir()
			com.shusheng.cobblemarket.currency.TierIconImage.ensureDir()
			// 配置目录的读图缓存（ID 卡背景图等）同理：虽按文件 mtime 自失效，
			// 但静态状态不该跨世界，统一起点更干净
			com.shusheng.cobblemarket.util.ConfigImage.reset()
			// 世界加载前校验 PersistentState 数据文件：损坏则从 .bak 恢复，再制作新备份
			com.shusheng.cobblemarket.util.StateBackup.verifyAndBackup(server)
		}
		onServerStarted { server ->
			TransactionHistory.historyRef = TransactionHistory.get(server)
			// CSV 详情重建精灵要 registries + 解析初始训练家 —— 事件订阅那边拿不到 server，这里挂一份
			TransactionHistory.serverRef = server
			// ⚠⚠ **RP 奖励规则必须在这里再加载一次**（2026-10-13 实测踩到）：
			//   平台层注册的重载监听**只在 `/reload` 时被回调**，**进世界那次加载不经过它** ——
			//   玩家直接进存档时规则表是空的 ⇒ "我明明配了、抓精灵却没奖励"，
			//   而他手动 `/reload` 一下就好了（看起来像"每次都要手动开一次数据包"，
			//   其实包一直是启用的、缺的是这次加载）。
			//   ⇒ 世界启动时主动读一次，之后 `/reload` 仍由监听器接管。
			com.shusheng.cobblemarket.currency.rewards.PointsRewards.onReload(server.resourceManager, server)
			// 金融系统状态预热（照 TransactionHistory 模式；批次 3 起实际读写）
			com.shusheng.cobblemarket.finance.FinanceState.get(server)
			// 贷款期界换算一律走 LoanRecord.PERIOD_MS_LONG（一期 PERIOD_DAYS 天），这行日志用于一眼核对：
			// 7 天 = 604800000 ms；若与此不符，说明 LoanRecord.PERIOD_DAYS 被动过
			LOGGER.info(
				"CobbleMarket: loan period = {} day(s)/period ({} ms)",
				com.shusheng.cobblemarket.finance.LoanRecord.PERIOD_DAYS,
				com.shusheng.cobblemarket.finance.LoanRecord.PERIOD_MS_LONG
			)
			// 服主名单为空 = 谁都进不去「服务器配置」与「权限管理」（安全默认）。升级后老服主
			// 会发现入口点不动了，这里在启动日志里提醒一次怎么配（兜底三件套之一，方案见 docs/permission_nodes_plan.md）
			//
			// ⚠ **非空时也要回显一行**（2026-09-23 加）：配置里那几个 UUID 是手写的字符串数组，
			//   写错了（缺引号 / 填了游戏名 / 格式不对）此前是**静默失效**的 —— 服主只会觉得
			//   "这功能怎么没反应"，根本不知道自己配错了。打印一行「已加载 N 名服主」，
			//   他重启后一眼就能确认成没成。
			val owners = com.shusheng.cobblemarket.config.CobbleMarketConfig.serverOwners
			if (owners.isEmpty()) {
				LOGGER.warn(
					"CobbleMarket: no server owner configured — nobody can edit the market config or grant admin permissions.\n" +
						"  • Put your UUID into \"serverOwners\" in config/cobblemarket.json, like this —\n" +
						"        \"serverOwners\": [\"6b8d9b8a-9580-4f8c-b72d-220328d4aea3\"]\n" +
						"    (each entry needs its quotes; several owners are separated by commas)\n" +
						"    Get your UUID from usercache.json, or run /data get entity @s UUID in game — that form is accepted too.\n" +
						"    Then run /market reload in game (with cheats on) or restart the server."
				)
			} else {
				LOGGER.info("CobbleMarket: {} server owners loaded from config", owners.size)
			}
		}
		onServerStopped { server ->
			// 正常关服保存完成后，用最新数据刷新备份
			com.shusheng.cobblemarket.util.StateBackup.backupOnStop(server)
			// RP 奖励：清掉待发的动作栏提示与内存里的规则表（单机切存档同进程，静态状态不得跨世界）
			com.shusheng.cobblemarket.currency.rewards.PointsRewards.reset()
			// 备份完成后释放历史记录静态引用；不在 STOPPING 提前置 null，
			// 覆盖 STOPPING→STOPPED 窗口内的事件写入（CSV 文件日志同步落盘）
			TransactionHistory.historyRef = null
			TransactionHistory.serverRef = null
		}

		onPlayerDisconnect { player ->
			com.shusheng.cobblemarket.util.RequestThrottle.onDisconnect(player.uuid)
			// 玩家退出时 MC 立即保存其玩家数据（货/钱已扣就此落盘），模组状态立即追上，
			// 否则此后杀进程/崩溃会形成错位导致货蒸发（见 PersistHelper 注释）
			com.shusheng.cobblemarket.util.PersistHelper.onPlayerDisconnect(player.server)
			// 总览的离线快照：记下这一刻的余额与图鉴数（离线期间它们不会再变，见 PlayerSnapshotState）
			com.shusheng.cobblemarket.overview.PlayerOverviewService.onPlayerDisconnect(player)
		}
		// 交易后节流全量落盘的定时检查（见 PersistHelper）
		onServerTickEnd { server ->
			com.shusheng.cobblemarket.util.PersistHelper.tick(server)
			// 金融系统自动划扣扫描（内部 60 秒节流，见 FinanceService）
			com.shusheng.cobblemarket.finance.FinanceService.tick(server)
			// 充值订单维护：超时作废 + 完结订单清理（内部 60 秒节流，见 PointShopService）
			com.shusheng.cobblemarket.currency.PointShopService.tick(server)
			// RP 奖励的动作栏提示：1 秒合并窗口到点就发（见 PointsRewards.queueActionBar）
			com.shusheng.cobblemarket.currency.rewards.PointsRewards.onServerTick(server)
			// RP 余额排行导出：按配置分钟数节流，**内容没变就不碰磁盘**（见 PointsRankingExport）
			com.shusheng.cobblemarket.currency.PointsRankingExport.tick(server)
			// 总览快照兜底刷新（内部 5 分钟节流、只刷在线的；正常下线另有下线事件兜）
			com.shusheng.cobblemarket.overview.PlayerOverviewService.tick(server)
			// 喵喵紫卡/黑卡丢弃即消失扫描（0.5 秒节流，近乎立即可见消失；
			// 非持有者背包自删走物品自身的 inventoryTick，无需服务器扫描）
			if (++cardScanTick >= 10) {
				cardScanTick = 0
				com.shusheng.cobblemarket.finance.MeowthPurpleCardItem.scanAndDiscardDroppedCards(server)
				com.shusheng.cobblemarket.finance.MeowthBlackCardItem.scanAndDiscardDroppedCards(server)
			}
		}
		onPlayerJoin { player ->
			val state = MarketState.get(player.server)
			val stateServer = player.server

			// 封禁提示：登录即清掉会话标记 ⇒ 本次会话首次开市场时会补一条（见 BanState.notifyOnMarketOpen）
			com.shusheng.cobblemarket.market.BanState.clearSessionNotified(player.uuid)

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
