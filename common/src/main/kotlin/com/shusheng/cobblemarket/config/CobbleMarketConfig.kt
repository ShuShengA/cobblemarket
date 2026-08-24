package com.shusheng.cobblemarket.config

import com.google.gson.GsonBuilder
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.platform.cobecoAvailable
import com.shusheng.cobblemarket.platform.configDir
import net.minecraft.item.Item
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import java.io.File

object CobbleMarketConfig {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val configFile: File
        get() = configDir().resolve("cobblemarket.json").toFile()

    var cobbledollars: Boolean = false
        private set
    /** 装了 Cobblemon Economy 时是否优先走它的货币 API（其内部桥接可路由到 CobbleDollars/Impactor 后端） */
    var cobblemonEconomy: Boolean = false
        private set
    /** cobeco 模式下的结算货币：POKE=PokeDollars（默认），PCO=PokeCoins；仅 cobblemonEconomy=true 时生效 */
    var cobecoCurrency: String = "POKE"
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
    var auctionDurationOptions: List<Int> = listOf(720, 1440, 2880, 4320) // 分钟制：12h/24h/48h/72h
        private set
    var auctionMinBidIncrement: Int = 100
        private set
    var auctionAntiSnipeSeconds: Int = 120
        private set
    var maxAuctionsPerPlayer: Int = 3
        private set
    var eggTradingEnabled: Boolean = false
        private set
    var buyOrderFeePercent: Double = 5.0
        private set
    var buyOrderExpiryDays: Int = 3
        private set
    var maxBuyOrdersPerPlayer: Int = 5
        private set
    var celebrationAnimationEnabled: Boolean = true
        private set
    var marketEnabled: Boolean = true
        private set

    /** 市场总开关切换并落盘（仅管理端命令/面板调用） */
    fun setMarketEnabled(v: Boolean) {
        marketEnabled = v
        save()
    }

    /** 蛋交易开关切换并落盘（仅管理面板调用） */
    fun setEggTradingEnabled(v: Boolean) {
        eggTradingEnabled = v
        save()
    }

    fun load() {
        val hasCD = try { Class.forName("fr.harmex.cobbledollars.common.utils.CobbleDollarsPlayer"); true } catch (_: Exception) { false }
        val hasCobeco = cobecoAvailable()
        if (!configFile.exists()) {
            cobbledollars = hasCD
            cobblemonEconomy = hasCobeco
            save()
        } else {
            try {
                val json = configFile.readText()
                val data = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: emptyMap<Any?, Any?>()
                // 升级兼容：旧版配置缺少新字段时，本次读取按默认值生效，
                // 读取完成后 save() 把缺失字段补写进文件——服主升级后打开配置即可见全部新字段
                val knownKeys = setOf(
                    "currency", "pokemonListingFeePercent", "itemListingFeePercent",
                    "maxPokemonListingsPerPlayer", "maxItemListingsPerPlayer", "listingDurationDays",
                    "pendingReturnRetentionDays", "auctionFeePercent", "auctionDurationOptions",
                    "auctionMinBidIncrement", "auctionAntiSnipeSeconds", "maxAuctionsPerPlayer",
                    "eggTradingEnabled", "buyOrderFeePercent", "buyOrderExpiryDays",
                    "maxBuyOrdersPerPlayer", "celebrationAnimationEnabled", "marketEnabled"
                )
                var missingKeys = knownKeys.any { !data.containsKey(it) }
                val currency = data["currency"] as? Map<*, *>
                if (currency != null) {
                    cobbledollars = (currency["cobbledollars"] as? Boolean ?: hasCD) && hasCD
                    // cobblemonEconomy 旧配置缺失时固定 false（升级无感），勿改成「缺失按探测兜底」：
                    // 老服主已用 cobbledollars=true 运营市场，升级后若因装了 cobeco 被自动切换后端，
                    // 余额存储会变（除非服主在 cobeco 里开了 main_currency 桥接），行为突变。
                    // 探测值只在无配置文件的全新安装时作为默认（见上方 !configFile.exists() 分支）
                    cobblemonEconomy = (currency["cobblemonEconomy"] as? Boolean ?: false) && hasCobeco
                    // 结算货币归一化：大小写/全名/缩写都认（PCO/pco/PokeCoins → PCO），其余回 POKE（防服主写错值静默用错货币）
                    cobecoCurrency = when (currency["cobecoCurrency"]?.toString()?.lowercase()) {
                        "pco", "pokecoins" -> "PCO"
                        else -> "POKE"
                    }
                    currencyItem = currency["item"] as? String ?: "minecraft:diamond"
                    if (!currency.containsKey("cobbledollars") || !currency.containsKey("cobblemonEconomy") || !currency.containsKey("cobecoCurrency") || !currency.containsKey("item")) missingKeys = true
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
                auctionDurationOptions = rawDurations?.takeIf { it.isNotEmpty() } ?: listOf(720, 1440, 2880, 4320)
                auctionMinBidIncrement = ((data["auctionMinBidIncrement"] as? Double)?.toInt() ?: 100).coerceAtLeast(1)
                auctionAntiSnipeSeconds = ((data["auctionAntiSnipeSeconds"] as? Double)?.toInt() ?: 120).coerceAtLeast(0)
                maxAuctionsPerPlayer = (data["maxAuctionsPerPlayer"] as? Double)?.toInt() ?: 3
                eggTradingEnabled = data["eggTradingEnabled"] as? Boolean ?: false
                buyOrderFeePercent = ((data["buyOrderFeePercent"] as? Double) ?: 5.0).coerceIn(0.0, 100.0)
                val rawBuyOrderExpiry = (data["buyOrderExpiryDays"] as? Double)?.toInt()
                buyOrderExpiryDays = (rawBuyOrderExpiry ?: 3).coerceAtLeast(1)
                maxBuyOrdersPerPlayer = ((data["maxBuyOrdersPerPlayer"] as? Double)?.toInt() ?: 5).coerceAtLeast(0)
                celebrationAnimationEnabled = data["celebrationAnimationEnabled"] as? Boolean ?: true
                marketEnabled = data["marketEnabled"] as? Boolean ?: true
                // 缺失字段补写：旧设置保留，新字段以默认值落盘（服主无需删配置）
                if (missingKeys) {
                    CobbleMarket.LOGGER.info("Config missing fields detected; rewriting with defaults for new keys")
                    save()
                }
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
                "currency.cobbledollars" to "是否使用 CobbleDollars 货币（true/false，cobblemonEconomy=true 时被忽略）。⚠ 货币配置仅在服务器启动时读取，修改后需重启生效 / Whether to use CobbleDollars currency (true/false, ignored when cobblemonEconomy=true). ⚠ Currency settings are read only at server startup — restart after changes",
                "currency.cobblemonEconomy" to "是否优先使用 Cobblemon Economy 的货币 API（true/false）。true 时市场余额走 cobeco 后端，其内置桥接可路由到 CobbleDollars/Impactor——若服主在 cobeco 配置里把 main_currency 设为 cobbledollars，市场与 CobbleDollars 商人共享同一余额；旧配置升级默认 false（行为不变），全新安装默认按探测自动开启 / Prefer Cobblemon Economy's currency API (true/false). When true the market uses the cobeco backend, whose built-in bridge can route to CobbleDollars/Impactor — if main_currency=cobbledollars in cobeco config, the market and CobbleDollars merchants share one balance; defaults to false on config upgrade (no behavior change) and to auto-detection on fresh installs",
                "currency.cobecoCurrency" to "Cobblemon Economy 结算货币：POKE=PokeDollars（默认），PCO=PokeCoins（写 PCO 或 PokeCoins 均可，不区分大小写）。仅 cobblemonEconomy=true 时生效；PCO 与 PokeDollars 是两套独立账本，市场用 PCO 结算时玩家 /pco 查到的余额就是市场余额 / Cobblemon Economy settlement currency: POKE=PokeDollars (default), PCO=PokeCoins (either PCO or PokeCoins, case-insensitive). Only used when cobblemonEconomy=true; PCO and PokeDollars are separate ledgers — with PCO the market balance equals what players see via /pco",
                "currency.item" to "货币物品 ID（cobbledollars 与 cobblemonEconomy 均为 false 时生效）/ Currency item ID (used when cobbledollars and cobblemonEconomy are both false)",
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
                "eggTradingEnabled" to "蛋交易开关（默认关闭）。蛋走物品交易链路，不经过精灵黑名单（个体值/形态/闪光）校验；若蛋加密关闭，部分模组可显示蛋内精灵数据，玩家可提前筛选，精灵黑名单对蛋失效——开启前请评估风险 / Egg trading switch (off by default). Eggs bypass the Pokémon blacklist (IV/form/shiny) checks; with egg encryption off, some mods can reveal egg data, letting players pick eggs before hatching — evaluate the risk before enabling",
                "buyOrderFeePercent" to "求购单中介费百分比：买家成交时从卖家实收中扣除（0=免中介费）/ Buy order fee percentage charged on seller's actual payment (0=no fee)",
                "buyOrderExpiryDays" to "求购单过期天数（到期自动关闭，剩余冻结金退买家待领余额）/ Buy order expiry in days (expired orders close automatically and refund frozen money)",
                "marketEnabled" to "市场总开关（默认开启）：紧急情况可整体关闭市场功能——所有买卖/拍卖/求购操作被拦截并提示，但待领取、余额等取回自己资产的操作仍可用。可在游戏内用 /market on|off 切换 / Master market switch (on by default): emergency kill switch for the entire market — all buy/sell/auction/buy-order operations are blocked with a notice, while claiming returns and collecting balances still work. Toggle in-game via /market on|off",
                "maxBuyOrdersPerPlayer" to "每个玩家同时进行的求购单数量上限，精灵与物品合计（0=不限制）。求购单列表全量下发给所有客户端，玩家较多的服务器建议保持较小值，避免全服活跃求购单总量过大导致卡顿 / Max concurrent buy orders per player, Pokémon and items combined (0=unlimited). The buy order list is broadcast in full to every client, so on crowded servers keep this small to avoid lag from too many active orders",
                "celebrationAnimationEnabled" to "获得精灵时的庆祝动画开关（默认开启）。买到精灵、拍到精灵、求购单接受交付时，在获得者屏幕中央播放该精灵的弹跳动画；关闭后服务端不再下发动画包 / Celebration animation switch when obtaining a Pokémon (on by default). Plays a bouncing animation of the Pokémon on the receiver's screen when buying, winning an auction, or accepting a buy order delivery; when off the server stops sending the animation packet"
            ),
            "currency" to mapOf("cobbledollars" to cobbledollars, "cobblemonEconomy" to cobblemonEconomy, "cobecoCurrency" to cobecoCurrency, "item" to currencyItem),
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
            "eggTradingEnabled" to eggTradingEnabled,
            "buyOrderFeePercent" to buyOrderFeePercent,
            "buyOrderExpiryDays" to buyOrderExpiryDays,
            "maxBuyOrdersPerPlayer" to maxBuyOrdersPerPlayer,
            "celebrationAnimationEnabled" to celebrationAnimationEnabled,
            "marketEnabled" to marketEnabled
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
