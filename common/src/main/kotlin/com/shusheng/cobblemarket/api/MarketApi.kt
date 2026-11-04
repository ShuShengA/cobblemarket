package com.shusheng.cobblemarket.api

import com.shusheng.cobblemarket.config.CurrencyHandler
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.PointsAuditLogger
import com.shusheng.cobblemarket.currency.PointsLogType
import com.shusheng.cobblemarket.currency.PointsState
import com.shusheng.cobblemarket.market.AuctionState
import com.shusheng.cobblemarket.market.AuctionType
import com.shusheng.cobblemarket.market.MarketState
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import java.math.BigInteger

/**
 * **给附属模组用的只读市场查询 API**（2026-10-30 新增，1.3.5）。
 *
 * 约定：
 * - 这里只暴露"读"与"扣款"，**不暴露任何可变的市场内部结构** —— 附属模组
 *   不需要、也不要直接改 `MarketState` 的挂单表。
 * - 这些方法全部在**服务端**调用（有 `MinecraftServer` / `ServerPlayerEntity` 参数）。
 * - **签名一旦发布就不要再改**：外部的附属模组是编译期依赖，改签名 = 逼它跟着发版。
 *   要加功能就**新增方法/字段**，别改旧的。
 */
object MarketQueryApi {

	/**
	 * 某物种在某币种下的在售信息。
	 *
	 * @property lowestPrice 当前**在售**挂单里的最低单价
	 * @property listingCount 当前在售条数（让人知道"这个价有没有货"）
	 * @property lowestLevel 最低价那条挂单的等级（同种不同等级价格差很大）
	 */
	data class PriceEntry(
		val currency: MarketCurrency,
		val lowestPrice: Int,
		val listingCount: Int,
		val lowestLevel: Int,
	)

	/**
	 * 一个物种的在售汇总。**普通与闪光分开** —— 闪光挂单会把"最低价"整个带偏，
	 * 两者混在一起看没有意义（2026-10-30 与用户定的口径）。
	 */
	data class SpeciesPrice(
		val normal: List<PriceEntry>,
		val shiny: List<PriceEntry>,
	) {
		fun isEmpty(): Boolean = normal.isEmpty() && shiny.isEmpty()

		/** 取指定币种 + 是否闪光的汇总；没有挂单返回 null */
		fun entry(currency: MarketCurrency, shiny: Boolean): PriceEntry? =
			(if (shiny) this.shiny else normal).firstOrNull { it.currency == currency }
	}

	/**
	 * 单个物种的在售汇总。
	 *
	 * @param species 物种名（`Pikachu`）或资源 id（`cobblemon:pikachu`）都可以；大小写不敏感
	 */
	fun price(server: MinecraftServer, species: String): SpeciesPrice = prices(server, listOf(species))[species] ?: EMPTY

	/**
	 * 批量查询（导航器一次列几十个物种时用这个，别循环调 [price] —— 那是 O(n×挂单数)）。
	 *
	 * 返回的 key = 传入的原始字符串；查询不到的物种不会出现在 map 里（或值为空汇总）。
	 */
	fun prices(server: MinecraftServer, species: Collection<String>): Map<String, SpeciesPrice> {
		if (species.isEmpty()) return emptyMap()
		val wanted = species.map { it to normalizeSpecies(it) }.toMap()
		val wantedKeys = wanted.values.toSet()

		// key = 归一化物种名 → 普通 / 闪光 → 币种 → 当前最低价那条
		val normal = mutableMapOf<String, MutableMap<MarketCurrency, PriceEntry>>()
		val shiny = mutableMapOf<String, MutableMap<MarketCurrency, PriceEntry>>()
		val now = System.currentTimeMillis()

		MarketState.get(server).getActiveListings().forEach { listing ->
			// status=ACTIVE 但可能还没被过期清理扫到 —— 这里按时间再判一次
			if (listing.expiresAt <= now) return@forEach
			val key = normalizeSpecies(listing.species)
			if (key !in wantedKeys) return@forEach
			val target = if (listing.shiny) shiny else normal
			val byCurrency = target.getOrPut(key) { mutableMapOf() }
			val current = byCurrency[listing.currency]
			byCurrency[listing.currency] = if (current == null || listing.price < current.lowestPrice) {
				PriceEntry(listing.currency, listing.price, 1, listing.level)
			} else {
				current.copy(listingCount = current.listingCount + 1)
			}
		}

		val byKey = mutableMapOf<String, SpeciesPrice>()
		wantedKeys.forEach { key ->
			val normalEntries = normal[key]?.values?.sortedBy { it.currency.ordinal } ?: emptyList()
			val shinyEntries = shiny[key]?.values?.sortedBy { it.currency.ordinal } ?: emptyList()
			byKey[key] = SpeciesPrice(normalEntries, shinyEntries)
		}
		return wanted.entries.associate { (original, key) -> original to (byKey[key] ?: EMPTY) }
	}

	/**
	 * 拍卖场在售数量（**2026-11-05 新增，1.3.6**）。
	 *
	 * 只给数量、不给价格 —— 导航器只回答「有几只在拍」（用户 2026-11-05 定：市场行不写价格，
	 * 同种两只价差可能很大，只显示最低价会误导；拍卖场同理只显示数量）。
	 * 只统计**精灵**拍卖（`AuctionType.POKEMON`），物品拍卖不算。
	 * 与 [prices] 用同一套物种匹配（大小写不敏感、剥 `cobblemon:` 前缀）。
	 */
	data class AuctionCount(val normalCount: Int, val shinyCount: Int)

	fun auctionCounts(server: MinecraftServer, species: Collection<String>): Map<String, AuctionCount> {
		if (species.isEmpty()) return emptyMap()
		val wantedKeys = species.map(::normalizeSpecies).toSet()
		val result = mutableMapOf<String, AuctionCount>()
		AuctionState.get(server).getActiveAuctions().forEach { auction ->
			if (auction.type != AuctionType.POKEMON) return@forEach
			val key = normalizeSpecies(auction.species)
			if (key !in wantedKeys) return@forEach
			val current = result[key] ?: AuctionCount(0, 0)
			result[key] = if (auction.shiny) {
				current.copy(shinyCount = current.shinyCount + 1)
			} else {
				current.copy(normalCount = current.normalCount + 1)
			}
		}
		return result
	}

	private val EMPTY = SpeciesPrice(emptyList(), emptyList())

	/** `cobblemon:pikachu` / `Pikachu` → `pikachu`；只用于内部匹配，不暴露 */
	private fun normalizeSpecies(raw: String): String = raw.substringAfter(':').lowercase()
}

/**
 * **给附属模组用的主货币 / RP 余额与扣款 API**（2026-10-30 新增，1.3.5）。
 *
 * ⚠ 计费统一走这里，附属模组**不要**自己读写 `CurrencyHandler` / `PointsState`：
 * - 主货币的后端（CobbleDollars / Cobble Economy / Impactor / 物品货币）由主模组配置决定，
 *   绕过这里自己扣款会漏掉后端差异与审计。
 * - RP 扣款会写 `PointsAuditLogger`（与 `/market points take` 同一本账），方便服主对账。
 *
 * 与 `MarketCurrency` 一样：**主货币与 RP 不可混用**，金额永远是"该币种的整数"。
 */
object EconomyApi {

	enum class ChargeResult {
		/** 扣款成功（金额 0 也返回它） */
		SUCCESS,

		/** 余额不足（主货币 / RP 任一） */
		INSUFFICIENT_FUNDS,

		/** 金额超出主货币后端的单次上限（主货币最多 Int 范围，工具费用不该到这个量级） */
		AMOUNT_TOO_LARGE,
	}

	/** 当前余额（主货币可能大于 Long 范围，这里按 Long 截断；工具计费不会到这个量级） */
	fun balance(player: ServerPlayerEntity, currency: MarketCurrency): Long = when (currency) {
		MarketCurrency.PRIMARY -> CurrencyHandler.getBalance(player)
			.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()

		MarketCurrency.POINTS -> PointsState.get(player.server).getBalance(player.uuid)
	}

	fun canAfford(player: ServerPlayerEntity, amount: Long, currency: MarketCurrency): Boolean =
		amount <= 0L || balance(player, currency) >= amount

	/**
	 * 扣款（**只在服务端调用**）。
	 *
	 * @param detailZh 审计账本里的中文说明（如「导航器解锁」）
	 * @param detailEn 英文说明（不传则与中文相同；账本中英各一份）
	 * @return 是否扣成功；失败时调用方**不要**执行工具动作
	 */
	fun charge(
		player: ServerPlayerEntity,
		amount: Long,
		currency: MarketCurrency,
		detailZh: String,
		detailEn: String = detailZh,
	): ChargeResult {
		if (amount <= 0L) return ChargeResult.SUCCESS
		return when (currency) {
			MarketCurrency.PRIMARY -> {
				if (amount > Int.MAX_VALUE) return ChargeResult.AMOUNT_TOO_LARGE
				if (CurrencyHandler.getBalance(player) < BigInteger.valueOf(amount)) {
					return ChargeResult.INSUFFICIENT_FUNDS
				}
				if (!CurrencyHandler.remove(player, amount.toInt())) {
					ChargeResult.INSUFFICIENT_FUNDS
				} else {
					ChargeResult.SUCCESS
				}
			}

			MarketCurrency.POINTS -> {
				val state = PointsState.get(player.server)
				if (state.getBalance(player.uuid) < amount || !state.removeBalance(player.uuid, amount)) {
					return ChargeResult.INSUFFICIENT_FUNDS
				}
				PointsAuditLogger.log(
					type = PointsLogType.TAKE,
					targetUuid = player.uuid,
					targetName = player.name.string,
					amount = amount,
					balanceAfter = state.getBalance(player.uuid),
					operatorName = player.name.string,
					detail = detailZh,
					detailEn = detailEn,
				)
				ChargeResult.SUCCESS
			}
		}
	}
}
