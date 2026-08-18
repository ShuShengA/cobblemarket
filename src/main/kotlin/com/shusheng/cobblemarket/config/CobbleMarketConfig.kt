package com.shusheng.cobblemarket.config

import com.google.gson.GsonBuilder
import com.shusheng.cobblemarket.CobbleMarket
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.item.Item
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import java.io.File

object CobbleMarketConfig {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val configFile: File
        get() = FabricLoader.getInstance().configDir.resolve("cobblemarket.json").toFile()

    var cobbledollars: Boolean = false
        private set
    var currencyItem: String = "minecraft:diamond"
        private set
    var pokemonListingFeePercent: Double = 5.0
        private set
    var itemListingFeePercent: Double = 5.0
        private set
    var maxPokemonListingsPerPlayer: Int = 0
        private set
    var maxItemListingsPerPlayer: Int = 0
        private set
    var listingDurationDays: Int = 14
        private set
    var pendingReturnRetentionDays: Int = 30
        private set
    var auctionFeePercent: Double = 5.0
        private set
    var auctionDurationOptions: List<Int> = listOf(3, 10, 30, 720) // 分钟制：3m/10m/30m/12h
        private set
    var auctionMinBidIncrement: Int = 100
        private set
    var auctionAntiSnipeSeconds: Int = 120
        private set
    var maxAuctionsPerPlayer: Int = 3
        private set
    var eggTradingEnabled: Boolean = false
        private set

    /** 蛋交易开关切换并落盘（仅管理面板调用） */
    fun setEggTradingEnabled(v: Boolean) {
        eggTradingEnabled = v
        save()
    }

    fun load() {
        val hasCD = try { Class.forName("fr.harmex.cobbledollars.common.utils.CobbleDollarsPlayer"); true } catch (_: Exception) { false }
        if (!configFile.exists()) {
            cobbledollars = hasCD
            save()
        } else {
            try {
                val json = configFile.readText()
                val data = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: emptyMap<Any?, Any?>()
                val currency = data["currency"] as? Map<*, *>
                if (currency != null) {
                    cobbledollars = (currency["cobbledollars"] as? Boolean ?: hasCD) && hasCD
                    currencyItem = currency["item"] as? String ?: "minecraft:diamond"
                }
                val legacyFee = data["listingFeePercent"] as? Double
                // 手续费钳制 0~100：超过 100% 会让卖家账本变负数（抵消后续所有收入）
                pokemonListingFeePercent = ((data["pokemonListingFeePercent"] as? Double) ?: legacyFee ?: 5.0).coerceIn(0.0, 100.0)
                itemListingFeePercent = ((data["itemListingFeePercent"] as? Double) ?: legacyFee ?: 5.0).coerceIn(0.0, 100.0)
                maxPokemonListingsPerPlayer = (data["maxPokemonListingsPerPlayer"] as? Double)?.toInt() ?: 0
                maxItemListingsPerPlayer = (data["maxItemListingsPerPlayer"] as? Double)?.toInt() ?: 0
                val rawDuration = (data["listingDurationDays"] as? Double)?.toInt()
                listingDurationDays = (rawDuration ?: 14).coerceAtLeast(1)
                if (rawDuration != null && rawDuration < 1) {
                    CobbleMarket.LOGGER.warn("Config listingDurationDays={} is invalid (must be positive); clamped to 1", rawDuration)
                }
                pendingReturnRetentionDays = ((data["pendingReturnRetentionDays"] as? Double)?.toInt() ?: 30).coerceAtLeast(0) // 负数钳制为 0（永不清理）
                auctionFeePercent = ((data["auctionFeePercent"] as? Double) ?: pokemonListingFeePercent).coerceIn(0.0, 100.0)
                val rawDurations = (data["auctionDurationOptions"] as? List<*>)
                    ?.mapNotNull { (it as? Number)?.toInt()?.coerceAtLeast(1) } // 0/负数 → 1 分钟（上架即到期无意义）
                auctionDurationOptions = rawDurations?.takeIf { it.isNotEmpty() } ?: listOf(3, 10, 30, 720)
                auctionMinBidIncrement = ((data["auctionMinBidIncrement"] as? Double)?.toInt() ?: 100).coerceAtLeast(1)
                auctionAntiSnipeSeconds = ((data["auctionAntiSnipeSeconds"] as? Double)?.toInt() ?: 120).coerceAtLeast(0)
                maxAuctionsPerPlayer = (data["maxAuctionsPerPlayer"] as? Double)?.toInt() ?: 3
                eggTradingEnabled = data["eggTradingEnabled"] as? Boolean ?: false
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Failed to load config: ${e.message}")
                save()
            }
        }
        CurrencyHandler.load(this)
    }

    fun save() {
        val data = mapOf(
            "_comments" to mapOf(
                "currency.cobbledollars" to "是否使用 CobbleDollars 货币（true/false）/ Whether to use CobbleDollars currency (true/false)",
                "currency.item" to "货币物品 ID（cobbledollars=false 时生效）/ Currency item ID (used when cobbledollars=false)",
                "pokemonListingFeePercent" to "精灵市场上架手续费百分比（0=免手续费）/ Pokémon listing fee percentage (0=no fee)",
                "itemListingFeePercent" to "物品市场上架手续费百分比（0=免手续费）/ Item listing fee percentage (0=no fee)",
                "maxPokemonListingsPerPlayer" to "每个玩家同时活跃的精灵上架数量上限（0=不限制）/ Max active Pokémon listings per player (0=unlimited)",
                "maxItemListingsPerPlayer" to "每个玩家同时活跃的物品上架数量上限（0=不限制）/ Max active item listings per player (0=unlimited)",
                "listingDurationDays" to "上架过期天数 / Listing duration in days",
                "pendingReturnRetentionDays" to "待领取退回保留天数（自进入退回列表起算）。超期未领取的退回将被永久删除，资产不保留！0 = 永不清理。/ Days to keep unclaimed returns (counted from entering the return list). Overdue unclaimed returns will be permanently DELETED with NO refund! 0 = keep forever.",
                "auctionFeePercent" to "拍卖成交手续费百分比（0=免手续费）/ Auction fee percentage charged on final price (0=no fee)",
                "auctionDurationOptions" to "拍卖时长档位（分钟）/ Auction duration options in minutes",
                "auctionMinBidIncrement" to "默认最低加价幅度（卖家上架时可自定，留空用此值）/ Default minimum bid increment (sellers may override per auction)",
                "auctionAntiSnipeSeconds" to "反狙击延长秒数：结束前该窗口内的出价会把结束时间延长到该秒数（0=关闭）/ Anti-snipe extension in seconds: bids within this window extend the end time (0=off)",
                "maxAuctionsPerPlayer" to "每个玩家同时进行的拍卖数量上限，精灵与物品合计（0=不限制）。玩家较多的服务器建议保持较小值，避免全服活跃拍卖总量过大导致服务器卡顿 / Max concurrent auctions per player, Pokémon and items combined (0=unlimited). On crowded servers keep this small to avoid server lag from too many active auctions",
                "eggTradingEnabled" to "蛋交易开关（默认关闭）。蛋走物品交易链路，不经过精灵黑名单（个体值/形态/闪光）校验；若蛋加密关闭，部分模组可显示蛋内精灵数据，玩家可提前筛选，精灵黑名单对蛋失效——开启前请评估风险 / Egg trading switch (off by default). Eggs bypass the Pokémon blacklist (IV/form/shiny) checks; with egg encryption off, some mods can reveal egg data, letting players pick eggs before hatching — evaluate the risk before enabling"
            ),
            "currency" to mapOf("cobbledollars" to cobbledollars, "item" to currencyItem),
            "pokemonListingFeePercent" to pokemonListingFeePercent,
            "itemListingFeePercent" to itemListingFeePercent,
            "maxPokemonListingsPerPlayer" to maxPokemonListingsPerPlayer,
            "maxItemListingsPerPlayer" to maxItemListingsPerPlayer,
            "listingDurationDays" to listingDurationDays,
            "pendingReturnRetentionDays" to pendingReturnRetentionDays,
            "auctionFeePercent" to auctionFeePercent,
            "auctionDurationOptions" to auctionDurationOptions,
            "auctionMinBidIncrement" to auctionMinBidIncrement,
            "auctionAntiSnipeSeconds" to auctionAntiSnipeSeconds,
            "maxAuctionsPerPlayer" to maxAuctionsPerPlayer,
            "eggTradingEnabled" to eggTradingEnabled
        )
        configFile.writeText(gson.toJson(data))
    }

    fun getCurrencyItem(): Item {
        val id = Identifier.tryParse(currencyItem) ?: Identifier.of("minecraft", "diamond")
        return Registries.ITEM.get(id)
    }

    fun getCurrencyName(): String {
        return getCurrencyItem().name.string
    }
}
