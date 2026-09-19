package com.shusheng.cobblemarket.config

import com.google.gson.GsonBuilder
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.platform.cobecoAvailable
import com.shusheng.cobblemarket.platform.configDir
import com.shusheng.cobblemarket.platform.impactorAvailable
import net.minecraft.item.Item
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import java.io.File

/** 分期方案（每期 7 天）：期数 + 每期费率（0.005 = 0.5%） */
data class LoanPlan(val periods: Int, val feeRate: Double)

/**
 * 游戏内充值档位。
 *
 * ⚠ [priceCents] 与 [points] 都用整数（分 / 个）：金额要跟服主的收款账单逐笔对上，
 * 浮点误差（5.00 存成 4.999…）在对账场景里是致命的。
 *
 * [qr] = 该档**专用**的收款码文件名（可空，放在 `config/cobblemarket/qr/` 下）：
 * - **留空** → 走全局 [CobbleMarketConfig.pointsShopPayQrImage]
 * - **配了** → 用这张码（微信/支付宝的"设置金额"码）。⚠ 此时**档位标价必须与码上金额一致**，
 *   否则玩家付的钱对不上订单
 *
 * 两种情况下玩家看到的实付金额都是**档位标价本身**，没有任何额外加项。
 */
data class ShopTier(
    val name: String,
    val priceCents: Long,
    val points: Long,
    val qr: String = "",
    /** 该档的「RP 券」图文件名（`config/cobblemarket/icons/` 下）；**留空 = 用内置券图** */
    val icon: String = ""
)

object CobbleMarketConfig {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val configFile: File
        get() = configDir().resolve("cobblemarket.json").toFile()

    /** 热重载时文件里的货币配置与运行时值不一致（供 reload 命令回显提示） */
    private var currencyChangedSinceReload = false

    var cobbledollars: Boolean = false
        private set
    /** 装了 Cobblemon Economy 时是否优先走它的货币 API（其内部桥接可路由到 CobbleDollars/Impactor 后端）。
     *  ⚠ 仅 Fabric 平台生效：Cobblemon Economy 无 NeoForge 版，NeoForge 上此开关恒被忽略 */
    var cobblemonEconomy: Boolean = false
        private set
    /** Cobblemon Economy 模式下的结算货币：POKE=PokeDollars（默认），PCO=PokeCoins；仅 cobblemonEconomy=true 时生效 */
    var cobecoCurrency: String = "POKE"
        private set
    /** Impactor 直连：不装 Cobblemon Economy 时直接走 Impactor 的 EconomyService API（双平台可用）。
     *  优先级低于 Cobblemon Economy 与 CobbleDollars（两者任一开启时此开关被忽略）；默认 false 且不参与
     *  全新安装自动探测（Impactor 常作为其它模组的基础依赖被装，自动开启会静默切换老服主货币） */
    var impactor: Boolean = false
        private set
    var currencyItem: String = "minecraft:diamond"
        private set
    var pokemonListingFeePercent: Double = 5.0
        private set
    var itemListingFeePercent: Double = 5.0
        private set
    /**
     * **RP** 精灵 / 物品上架的费率（默认 **0** = 免手续费）。
     *
     * 为什么默认与主货币不同：RP 在多数服上是**真钱充值的单向货币**，扣它在玩家眼里就是
     * 「服主连手续费都收真钱」，为几 RP 惹这种纠纷不值，所以默认免。
     * 但 RP 不一定是充值货币（服主也可能当纯游戏币发），那种服就该有这个回收手段 ——
     * 所以做成**可配置**，而不是在代码里写死免收（2026-09-19 用户拍板）。
     */
    var pointsPokemonListingFeePercent: Double = 0.0
        private set

    /** 见 [pointsPokemonListingFeePercent] */
    var pointsItemListingFeePercent: Double = 0.0
        private set
    /**
     * **RP** 的拍卖成交费率（默认 **0** = 免），理由同 [pointsPokemonListingFeePercent]。
     * ⚠ 拍卖与求购要到 B5/B6 才实现，这两个配置项**先备着** —— 到那时直接用，配置层不用再动。
     */
    var pointsAuctionFeePercent: Double = 0.0
        private set

    /** 见 [pointsAuctionFeePercent] */
    var pointsBuyOrderFeePercent: Double = 0.0
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
    var marketEnabled: Boolean = true
        private set
    /** 玩家展示开关：关闭后玩家不能把精灵/物品展示到全服聊天（运营开关，与市场总开关同类） */
    var shareEnabled: Boolean = true
        private set
    /**
     * RP 总开关（默认关：不用RP 的服零感知）。关闭后玩家设置价格的界面不出现RP 按钮，
     * 除RP 商城外的任何地方都不能用RP 交易；但服主仍可在RP 商城上架供持有RP 的玩家购买。
     */
    var pointsEnabled: Boolean = false
        private set
    /**
     * **玩家间 RP 交易开关**（默认关，需 [pointsEnabled] 同时开启）。
     *
     * RP 在设定上「不可转让」，而玩家 A 挂 RP 单、玩家 B 买下，本质就是把 RP 从 B 转到 A ——
     * 这是那条设定在市场上唯一的后门。默认**关**：服主若只把 RP 当充值货币用、不想要
     * 玩家之间拿 RP 换东西（那等于绕开主货币做金钱交易），什么都不用配；想让 RP 真正流通再打开。
     *
     * ⚠ 关掉只禁**玩家之间**：充值、RP 商城（服主自己卖的东西）、余额显示全不受影响。
     */
    var pointsMarketEnabled: Boolean = false
        private set
    /**
     * 外部签名码的 HMAC 密钥（服主在网站/发卡网卖RP 时用）。**留空 = 关闭外部码**，只接受本地生成的码。
     * ⚠ 只能放在服务端（网站后端 / 服主本机），绝不能进客户端、日志或公开仓库。
     */
    var pointsExternalSecret: String = ""
        private set
    /** 外部码的有效期上限（天）：签发超出这个跨度的码一律拒绝，防"永久码"满天飞 */
    var pointsExternalMaxDays: Int = 365
        private set
    /**
     * **游戏内充值开关**（默认关，需 [pointsEnabled] 同时开启）。
     *
     * 国内小服主普遍用个人收款码卖东西，而个人收款码**没有支付回调** —— 任何程序都无法自动
     * 得知「谁付了多少」。本功能因此不做自动收款，只把对账成本压到最低：
     * 游戏内下单（带订单号、金额就是标价）→ 玩家扫码付款 → 点「我已支付」→ 服主看账单确认发放。
     */
    var pointsShopEnabled: Boolean = false
        private set
    /**
     * 收款码图片文件名（放在 `config/cobblemarket/qr/` 下，如 `pay.png`）。
     * **留空 = 不开放「购买RP」**（玩家仍可兑换码充值）。服务端读取后经网络下发给客户端绘制。
     */
    var pointsShopPayQrImage: String = ""
        private set
    /**
     * 档位「RP 券」的**全局默认**图文件名（放在 `config/cobblemarket/icons/` 下）。
     *
     * 优先级：`tiers[].icon`（该档专用）> 本项（全局默认）> **模组内置券图**。
     * 有了它，服主换整套档位的券图只要放一张图 + 填一次；不必逐档填，也不必打资源包。
     */
    var pointsShopDefaultIcon: String = ""
        private set
    /** 待支付订单超时（分钟，默认 15）：超时后作废，玩家可重新下单 */
    var pointsShopOrderTimeoutMinutes: Long = 15L
        private set
    /** 充值档位列表（空 = 不开放「购买RP」）；非法档位在读取时被丢弃并记日志 */
    var pointsShopTiers: List<ShopTier> = emptyList()
        private set
    /**
     * 档位金额的**货币单位**（纯展示用，服主自己填 `元` / `USD` / `$`…）。
     * **留空 = 只显示数字不带单位**。⚠ 模组不做任何汇率换算 —— 钱直接进服主账户，
     * 这里只是给玩家看的一个字（2026-09-18 用户提：国外服主不该被写死的「元」绑住）。
     */
    var pointsShopCurrencyLabel: String = ""
        private set

    // ── 金融系统（finance 段，启动级：reload 跳过应用仅比较差异，改后需重启） ──

    /** 金融系统总开关（默认关闭：借贷影响经济安全，不主动打开） */
    var financeEnabled: Boolean = false
        private set
    /** 现金贷开关（借呗——银行柜台主动借款） */
    var cashLoanEnabled: Boolean = true
        private set
    /** 消费贷开关（喵喵支付——购买/拍卖出价时垫付） */
    var consumerLoanEnabled: Boolean = true
        private set
    /** 分期方案数组（每期 7 天，期数 + 每期费率） */
    var loanPlans: List<LoanPlan> = listOf(LoanPlan(3, 0.005), LoanPlan(6, 0.008), LoanPlan(12, 0.012))
        private set
    /** 额度公式系数：近30天交易额权重 */
    var creditLimitRecent30Weight: Double = 0.5
        private set
    /** 额度公式系数：历史交易额权重 */
    var creditLimitHistoryWeight: Double = 0.1
        private set
    /** @deprecated 欠款权重已废弃（2026-09-02 拍板：欠款改全额扣减，信用卡模型）；字段保留仅兼容旧配置 */
    var creditLimitDebtWeight: Double = 0.3
        private set
    var creditLimitMin: Long = 0L
        private set
    var creditLimitMax: Long = 100_000L
        private set
    /** 额度冷却时长（小时）：成交后 N 小时内不计入额度（防「现刷现借」组团套现跑路）；0 = 不冷却 */
    var creditLimitCooldownHours: Long = 24L
        private set
    /** 活期存款日利率（默认 0.0001 = 0.01%/天，年化约 3.65%）；利息实算，查看/取款时结算 */
    var dailyDepositRate: Double = 0.0001
        private set
    /** 交易对检测窗口（天）：同对成交的计数窗口（与额度公式的「近30天交易额」窗口无关） */
    var tradePairWindowDays: Long = 30L
        private set
    /** 交易对检测笔数：窗口内同一买卖对达到该笔数后，该对后续成交不计入交易额 */
    var tradePairMaxTrades: Long = 3L
        private set
    /** 喵喵紫卡全服上限（张） */
    var purpleCardCount: Long = 20L
        private set
    /** 喵喵紫卡持有者额度（固定值，不受额度公式/上下限钳制） */
    var purpleCardCreditLimit: Long = 1_000_000L
        private set
    /** 允许玩家自行申请紫卡（默认关；开启后玩家从喵喵银行紫卡入口申请） */
    var purpleCardSelfApply: Boolean = false
        private set
    /** 自行申请条件：玩家当前现金余额门槛（0 = 不要求） */
    var purpleCardApplyAsset: Long = 0L
        private set
    /** 自行申请条件：历史买入成交额累计门槛（0 = 不要求） */
    var purpleCardApplyVolume: Long = 0L
        private set
    /** 自行申请条件：信用基础（无欠款额度公式值）门槛（0 = 不要求） */
    var purpleCardApplyCredit: Long = 0L
        private set
    /** 自行申请条件：喵喵银行存款余额门槛（0 = 不要求） */
    var purpleCardApplyDeposit: Long = 0L
        private set
    /** 自行申请条件：要求无逾期/坏账记录（默认关） */
    var purpleCardApplyNoOverdue: Boolean = false
        private set
    /** 自行申请条件：图鉴遇见数（SEEN 及以上，含已捕捉）门槛（0 = 不要求） */
    var purpleCardApplySeen: Long = 0L
        private set
    /** 自行申请条件：图鉴捕捉数（已捕捉物种数）门槛（0 = 不要求） */
    var purpleCardApplyDex: Long = 0L
        private set
    /** 自行申请费用（申请时一次性支付，进准备金池；0 = 免费） */
    var purpleCardApplyFee: Long = 0L
        private set
    /** 补发紫卡凭证费用（0 = 免费） */
    var purpleCardRedoFee: Long = 0L
        private set
    /** 紫卡持有者市场手续费减免比例（0~1：0.5=减半、0.2=减免 20%；覆盖上架费/拍卖成交费/求购中介费） */
    var purpleCardFeeDiscount: Double = 0.0
        private set
    // ── 喵喵黑卡（比紫卡高一级：额度/减免黑卡覆盖紫卡；自行申请硬条件=必须持有紫卡） ──
    /** 黑卡全服发放上限（0 = 不限制） */
    var blackCardCount: Long = 5L
        private set
    /** 黑卡持有者借款额度（默认 500 万，高于紫卡） */
    var blackCardCreditLimit: Long = 5_000_000L
        private set
    /** 允许玩家自行申请黑卡（默认关；申请硬条件=持有紫卡） */
    var blackCardSelfApply: Boolean = false
        private set
    /** 自行申请条件：玩家当前现金余额门槛（0 = 不要求） */
    var blackCardApplyAsset: Long = 0L
        private set
    /** 自行申请条件：历史买入成交额累计门槛（0 = 不要求） */
    var blackCardApplyVolume: Long = 0L
        private set
    /** 自行申请条件：信用基础（无欠款额度公式值）门槛（0 = 不要求） */
    var blackCardApplyCredit: Long = 0L
        private set
    /** 自行申请条件：喵喵银行存款余额门槛（0 = 不要求） */
    var blackCardApplyDeposit: Long = 0L
        private set
    /** 自行申请条件：要求无逾期/坏账记录（默认关） */
    var blackCardApplyNoOverdue: Boolean = false
        private set
    /** 自行申请条件：图鉴遇见数（SEEN 及以上，含已捕捉）门槛（0 = 不要求） */
    var blackCardApplySeen: Long = 0L
        private set
    /** 自行申请条件：图鉴捕捉数（已捕捉物种数）门槛（0 = 不要求） */
    var blackCardApplyDex: Long = 0L
        private set
    /** 自行申请费用（申请时一次性支付，进准备金池；0 = 免费） */
    var blackCardApplyFee: Long = 0L
        private set
    /** 补发黑卡凭证费用（0 = 免费） */
    var blackCardRedoFee: Long = 0L
        private set
    /** 黑卡持有者市场手续费减免比例（0~1；覆盖上架费/拍卖成交费/求购中介费，与紫卡同时持有取黑卡） */
    var blackCardFeeDiscount: Double = 0.0
        private set
    /** 到期自动划扣最低保留：最多划到余额=此值为止（划不足进 OVERDUE） */
    var autoRepayMinBalance: Long = 1_000L
        private set
    /** 同 IP 未结清欠款总和上限（防同 IP 多小号分散借款转给主账号；OP 豁免；0=不限制） */
    var ipDebtLimit: Long = 100_000L
        private set
    /** 逾期天数三档（可改）：手续费翻倍 */
    var overdueFeeDoubleDays: Int = 7
        private set
    /** 逾期天数三档（可改）：冻结挂单/待领取（复用 BanState，来源 FINANCE） */
    var overdueFreezeDays: Int = 14
        private set
    /** 逾期天数三档（可改）：坏账冲销 */
    var overdueBadDebtDays: Int = 30
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

    /** 分享开关切换并落盘（仅服务端配置界面调用） */
    fun setShareEnabled(v: Boolean) {
        shareEnabled = v
        save()
    }

    /** RP 总开关切换并落盘（仅服务端配置界面调用） */
    fun setPointsEnabled(v: Boolean) {
        pointsEnabled = v
        save()
    }

    /** 玩家间 RP 交易开关切换并落盘（仅服务端配置界面调用） */
    fun setPointsMarketEnabled(v: Boolean) {
        pointsMarketEnabled = v
        save()
    }

    /** 游戏内充值开关切换并落盘（仅服务端配置界面调用） */
    fun setPointsShopEnabled(v: Boolean) {
        pointsShopEnabled = v
        save()
    }

    // ── RP 市场配置界面的编辑 setter（钳制规则与 load() 一致；save() 由调用方统一执行） ──

    fun setPointsShopTiers(v: List<ShopTier>) {
        pointsShopTiers = v
    }

    fun setPointsShopPayQrImage(v: String) {
        pointsShopPayQrImage = v.trim()
    }

    fun setPointsShopDefaultIcon(v: String) {
        pointsShopDefaultIcon = v.trim()
    }

    fun setPointsShopOrderTimeoutMinutes(v: Long) {
        pointsShopOrderTimeoutMinutes = v.coerceIn(1L, 1440L)
    }

    fun setPointsShopCurrencyLabel(v: String) {
        pointsShopCurrencyLabel = v.trim()
    }

    /**
     * 解析一个充值档位。非法档位**直接丢弃**（返回 null）并记日志 ——
     * 标价/点数非正数的档位等于白送RP，绝不能让它静默生效。
     */
    private fun parseShopTier(raw: Any?, index: Int): ShopTier? {
        val map = raw as? Map<*, *> ?: run {
            CobbleMarket.LOGGER.warn("Shop tier #{} is not an object; ignored", index + 1)
            return null
        }
        val name = (map["name"] as? String)?.trim().orEmpty()
        val price = (map["price"] as? Number)?.toDouble()
        val points = (map["points"] as? Number)?.toLong()
        if (name.isBlank() || price == null || points == null || price <= 0.0 || points <= 0L) {
            CobbleMarket.LOGGER.warn(
                "Shop tier #{} is invalid (name/price/points must be present and positive); ignored", index + 1
            )
            return null
        }
        val cents = Math.round(price * 100.0)
        if (cents <= 0L) {
            CobbleMarket.LOGGER.warn("Shop tier #{} price rounds to 0; ignored", index + 1)
            return null
        }
        return ShopTier(
            name = name,
            priceCents = cents,
            points = points,
            qr = (map["qr"] as? String)?.trim().orEmpty(),
            icon = (map["icon"] as? String)?.trim().orEmpty()
        )
    }

    // ── 服务器配置 GUI 编辑 setter（钳制规则与 load() 一致；save() 由调用方统一执行） ──

    fun setPokemonListingFeePercent(v: Double) { pokemonListingFeePercent = v.coerceIn(0.0, 100.0) }
    fun setItemListingFeePercent(v: Double) { itemListingFeePercent = v.coerceIn(0.0, 100.0) }

    fun setPointsPokemonListingFeePercent(v: Double) { pointsPokemonListingFeePercent = v.coerceIn(0.0, 100.0) }

    fun setPointsItemListingFeePercent(v: Double) { pointsItemListingFeePercent = v.coerceIn(0.0, 100.0) }

    fun setPointsAuctionFeePercent(v: Double) { pointsAuctionFeePercent = v.coerceIn(0.0, 100.0) }

    fun setPointsBuyOrderFeePercent(v: Double) { pointsBuyOrderFeePercent = v.coerceIn(0.0, 100.0) }
    fun setMaxPokemonListingsPerPlayer(v: Int) { maxPokemonListingsPerPlayer = v.coerceAtLeast(0) }
    fun setMaxItemListingsPerPlayer(v: Int) { maxItemListingsPerPlayer = v.coerceAtLeast(0) }
    fun setListingDurationDays(v: Int) { listingDurationDays = v.coerceAtLeast(1) }
    fun setPendingReturnRetentionDays(v: Int) { pendingReturnRetentionDays = v.coerceAtLeast(0) }
    fun setAuctionFeePercent(v: Double) { auctionFeePercent = v.coerceIn(0.0, 100.0) }
    fun setAuctionMinBidIncrement(v: Int) { auctionMinBidIncrement = v.coerceAtLeast(1) }
    fun setAuctionAntiSnipeSeconds(v: Int) { auctionAntiSnipeSeconds = v.coerceAtLeast(0) }
    fun setMaxAuctionsPerPlayer(v: Int) { maxAuctionsPerPlayer = v.coerceAtLeast(0) }
    fun setBuyOrderFeePercent(v: Double) { buyOrderFeePercent = v.coerceIn(0.0, 100.0) }
    fun setBuyOrderExpiryDays(v: Int) { buyOrderExpiryDays = v.coerceAtLeast(1) }
    fun setMaxBuyOrdersPerPlayer(v: Int) { maxBuyOrdersPerPlayer = v.coerceAtLeast(0) }

    // ── 金融系统 setter（ServerConfigScreen 金融区块；利息快照制：改配置只影响新贷款，存量贷款用创建时快照） ──

    fun setFinanceEnabled(v: Boolean) { financeEnabled = v }
    fun setCashLoanEnabled(v: Boolean) { cashLoanEnabled = v }
    fun setConsumerLoanEnabled(v: Boolean) { consumerLoanEnabled = v }

    /** 分期方案文本（"3:0.005,6:0.008,12:0.012"）；解析为空/全非法时保持旧值 */
    fun setLoanPlansText(raw: String) {
        val parsed = raw.split(',')
            .mapNotNull { part ->
                val seg = part.trim().split(':')
                if (seg.size != 2) return@mapNotNull null
                val p = seg[0].trim().toIntOrNull()?.coerceAtLeast(1)
                val r = seg[1].trim().toDoubleOrNull()?.coerceIn(0.0, 1.0)
                if (p != null && r != null) LoanPlan(p, r) else null
            }
        if (parsed.isNotEmpty()) {
            loanPlans = parsed
        }
    }

    /** 分期方案序列化文本（GUI 回显/快照用） */
    fun loanPlansText(): String = loanPlans.joinToString(",") { "${it.periods}:${it.feeRate}" }

    fun setCreditLimitRecent30Weight(v: Double) { creditLimitRecent30Weight = v.coerceIn(0.0, 10.0) }
    fun setCreditLimitHistoryWeight(v: Double) { creditLimitHistoryWeight = v.coerceIn(0.0, 10.0) }
    fun setCreditLimitDebtWeight(v: Double) { creditLimitDebtWeight = v.coerceIn(0.0, 10.0) }
    fun setCreditLimitMin(v: Long) { creditLimitMin = v.coerceAtLeast(0L) }
    fun setCreditLimitMax(v: Long) { creditLimitMax = v.coerceAtLeast(creditLimitMin) }
    fun setCreditLimitCooldownHours(v: Long) { creditLimitCooldownHours = v.coerceAtLeast(0L) }
    fun setDailyDepositRate(v: Double) { dailyDepositRate = v.coerceIn(0.0, 1.0) }

    /**
     * 存贷利率护栏：存款日利率不得超过最便宜贷款方案的套利上界。
     * 上界 = min(每期费率 × (期数+1) ÷ (14×期数))——覆盖任何还法（按期划扣/提前结清/自有资金还贷而贷款存满全程）
     * 下存款利息收入 ≤ 贷款利息支出，套利无收益；免息方案（费率 0）时存款日利率同步钳 0。
     */
    fun enforceDepositRateGuard() {
        val cap = loanPlans.minOfOrNull { it.feeRate * (it.periods + 1) / (14.0 * it.periods) } ?: 0.0
        if (dailyDepositRate > cap) {
            CobbleMarket.LOGGER.warn("Config dailyDepositRate={} exceeds loan arbitrage bound {}; clamped", dailyDepositRate, cap)
            dailyDepositRate = cap
        }
    }

    /**
     * 卡片申请门槛跨项校验：捕捉数门槛隐含遇见数门槛（抓到的必然遇见过），
     * 遇见数填得比捕捉数低属自相矛盾的配置 —— 自动抬平到捕捉数。
     * 反向不降：要求「遇见过 50 种里抓 10 种」是合法配置，不动它。
     * 走 load/save 全量收口（勿在单 setter 里钳：那时另一项可能尚未更新）。
     */
    fun enforceCardApplySeenGuard() {
        if (purpleCardApplySeen < purpleCardApplyDex) purpleCardApplySeen = purpleCardApplyDex
        if (blackCardApplySeen < blackCardApplyDex) blackCardApplySeen = blackCardApplyDex
    }
    fun setTradePairWindowDays(v: Long) { tradePairWindowDays = v.coerceAtLeast(1L) }
    fun setTradePairMaxTrades(v: Long) { tradePairMaxTrades = v.coerceAtLeast(1L) }
    fun setPurpleCardCount(v: Long) { purpleCardCount = v.coerceAtLeast(0L) }
    fun setPurpleCardCreditLimit(v: Long) { purpleCardCreditLimit = v.coerceAtLeast(0L) }
    fun setPurpleCardSelfApply(v: Boolean) { purpleCardSelfApply = v }
    fun setPurpleCardApplyAsset(v: Long) { purpleCardApplyAsset = v.coerceAtLeast(0L) }
    fun setPurpleCardApplyVolume(v: Long) { purpleCardApplyVolume = v.coerceAtLeast(0L) }
    fun setPurpleCardApplyCredit(v: Long) { purpleCardApplyCredit = v.coerceAtLeast(0L) }
    fun setPurpleCardApplyDeposit(v: Long) { purpleCardApplyDeposit = v.coerceAtLeast(0L) }
    fun setPurpleCardApplyNoOverdue(v: Boolean) { purpleCardApplyNoOverdue = v }
    fun setPurpleCardApplySeen(v: Long) { purpleCardApplySeen = v.coerceAtLeast(0L) }
    fun setPurpleCardApplyDex(v: Long) { purpleCardApplyDex = v.coerceAtLeast(0L) }
    fun setPurpleCardApplyFee(v: Long) { purpleCardApplyFee = v.coerceAtLeast(0L) }
    fun setPurpleCardRedoFee(v: Long) { purpleCardRedoFee = v.coerceAtLeast(0L) }
    fun setPurpleCardFeeDiscount(v: Double) { purpleCardFeeDiscount = v.coerceIn(0.0, 1.0) }
    fun setBlackCardCount(v: Long) { blackCardCount = v.coerceAtLeast(0L) }
    fun setBlackCardCreditLimit(v: Long) { blackCardCreditLimit = v.coerceAtLeast(0L) }
    fun setBlackCardSelfApply(v: Boolean) { blackCardSelfApply = v }
    fun setBlackCardApplyAsset(v: Long) { blackCardApplyAsset = v.coerceAtLeast(0L) }
    fun setBlackCardApplyVolume(v: Long) { blackCardApplyVolume = v.coerceAtLeast(0L) }
    fun setBlackCardApplyCredit(v: Long) { blackCardApplyCredit = v.coerceAtLeast(0L) }
    fun setBlackCardApplyDeposit(v: Long) { blackCardApplyDeposit = v.coerceAtLeast(0L) }
    fun setBlackCardApplyNoOverdue(v: Boolean) { blackCardApplyNoOverdue = v }
    fun setBlackCardApplySeen(v: Long) { blackCardApplySeen = v.coerceAtLeast(0L) }
    fun setBlackCardApplyDex(v: Long) { blackCardApplyDex = v.coerceAtLeast(0L) }
    fun setBlackCardApplyFee(v: Long) { blackCardApplyFee = v.coerceAtLeast(0L) }
    fun setBlackCardRedoFee(v: Long) { blackCardRedoFee = v.coerceAtLeast(0L) }
    fun setBlackCardFeeDiscount(v: Double) { blackCardFeeDiscount = v.coerceIn(0.0, 1.0) }
    fun setAutoRepayMinBalance(v: Long) { autoRepayMinBalance = v.coerceAtLeast(0L) }
    fun setIpDebtLimit(v: Long) { ipDebtLimit = v.coerceAtLeast(0L) }
    fun setOverdueFeeDoubleDays(v: Int) { overdueFeeDoubleDays = v.coerceAtLeast(0) }
    fun setOverdueFreezeDays(v: Int) { overdueFreezeDays = v.coerceAtLeast(0) }
    fun setOverdueBadDebtDays(v: Int) { overdueBadDebtDays = v.coerceAtLeast(0) }

    /** 拍卖时长选项（逗号分隔分钟，如 "720,1440"）；解析为空/全非法时保持旧值 */
    fun setAuctionDurationOptions(raw: String) {
        val parsed = raw.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .map { it.coerceAtLeast(1) }
        if (parsed.isNotEmpty()) {
            auctionDurationOptions = parsed
        }
    }

    /**
     * 读取配置文件。skipCurrency=true（/market reload 专用）时不应用货币字段、不重建货币 handler——
     * 运行时切换货币后端会账本错乱（挂单/冻结金按旧货币记账），只比较并记录差异供命令回显。
     * 金融段可正常热重载（利息快照制：改配置只影响新贷款，存量贷款用创建时的 dailyRate 快照）。
     */
    fun load(skipCurrency: Boolean = false) {
        val hasCD = try { Class.forName("fr.harmex.cobbledollars.common.utils.CobbleDollarsPlayer"); true } catch (_: Exception) { false }
        val hasCobeco = cobecoAvailable()
        val hasImpactor = impactorAvailable()
        if (!configFile.exists()) {
            cobbledollars = hasCD
            cobblemonEconomy = hasCobeco
            // impactor 默认 false：不参与全新安装自动探测（Impactor 常被其它模组当作基础依赖装，
            // 自动开启会静默切换货币后端；服主显式写 true 才启用）
            impactor = false
            save()
        } else {
            try {
                val json = configFile.readText()
                val data = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: emptyMap<Any?, Any?>()
                // 升级兼容：旧版配置缺少新字段时，本次读取按默认值生效，
                // 读取完成后 save() 把缺失字段补写进文件——服主升级后打开配置即可见全部新字段
                val knownKeys = setOf(
                    "currency", "pokemonListingFeePercent", "itemListingFeePercent",
                    "pointsPokemonListingFeePercent", "pointsItemListingFeePercent",
                    "pointsAuctionFeePercent", "pointsBuyOrderFeePercent",
                    "maxPokemonListingsPerPlayer", "maxItemListingsPerPlayer", "listingDurationDays",
                    "pendingReturnRetentionDays", "auctionFeePercent", "auctionDurationOptions",
                    "auctionMinBidIncrement", "auctionAntiSnipeSeconds", "maxAuctionsPerPlayer",
                    "eggTradingEnabled", "buyOrderFeePercent", "buyOrderExpiryDays",
                    "maxBuyOrdersPerPlayer", "marketEnabled",
                    "shareEnabled", "pointsEnabled", "pointsMarketEnabled",
                    "pointsExternalSecret", "pointsExternalMaxDays",
                    "pointsShop",
                    "finance"
                )
                var missingKeys = knownKeys.any { !data.containsKey(it) }
                val currency = data["currency"] as? Map<*, *>
                if (currency != null) {
                    val fileCobbledollars = (currency["cobbledollars"] as? Boolean ?: hasCD) && hasCD
                    // cobblemonEconomy 旧配置缺失时固定 false（升级无感），勿改成「缺失按探测兜底」：
                    // 老服主已用 cobbledollars=true 运营市场，升级后若因装了 Cobblemon Economy 被自动切换后端，
                    // 余额存储会变（除非服主在 Cobblemon Economy 里开了 main_currency 桥接），行为突变。
                    // 探测值只在无配置文件的全新安装时作为默认（见上方 !configFile.exists() 分支）
                    // ⚠ Cobblemon Economy 在 Cobblemon 1.8+ 上会导致崩服（CE 引用了已改名的图鉴字段），
                    //   但**本模组刻意不做任何自动处理**：配置照原样生效、货币照常用，只在启动时给一条警告
                    //   （见 CurrencyHandler.warnIfCobecoIncompatible）。理由：静默切换货币后端比崩溃危险得多 ——
                    //   崩溃立刻可见、有人来查；货币悄悄换掉会让玩家余额对不上，往往几天后才发现。
                    val fileCobeco = (currency["cobblemonEconomy"] as? Boolean ?: false) && hasCobeco
                    // 结算货币归一化：大小写/全名/缩写都认（PCO/pco/PokeCoins → PCO），其余回 POKE（防服主写错值静默用错货币）
                    val fileCobecoCurrency = when (currency["cobecoCurrency"]?.toString()?.lowercase()) {
                        "pco", "pokecoins" -> "PCO"
                        else -> "POKE"
                    }
                    val fileImpactor = (currency["impactor"] as? Boolean ?: false) && hasImpactor
                    val fileCurrencyItem = currency["item"] as? String ?: "minecraft:diamond"
                    if (!currency.containsKey("cobbledollars") || !currency.containsKey("cobblemonEconomy") || !currency.containsKey("cobecoCurrency") || !currency.containsKey("impactor") || !currency.containsKey("item")) missingKeys = true
                    if (skipCurrency) {
                        currencyChangedSinceReload = fileCobbledollars != cobbledollars || fileCobeco != cobblemonEconomy ||
                            fileCobecoCurrency != cobecoCurrency || fileImpactor != impactor || fileCurrencyItem != currencyItem
                    } else {
                        cobbledollars = fileCobbledollars
                        cobblemonEconomy = fileCobeco
                        cobecoCurrency = fileCobecoCurrency
                        impactor = fileImpactor
                        currencyItem = fileCurrencyItem
                    }
                }
                val finance = data["finance"] as? Map<*, *>
                if (finance != null) {
                    val financeKeys = setOf(
                        "enabled", "cashLoanEnabled", "consumerLoanEnabled", "loanPlans",
                        "creditLimit", "autoRepayMinBalance", "ipDebtLimit", "overdueDays"
                    )
                    if (financeKeys.any { !finance.containsKey(it) }) missingKeys = true
                    val fileEnabled = finance["enabled"] as? Boolean ?: false
                    val fileCash = finance["cashLoanEnabled"] as? Boolean ?: true
                    val fileConsumer = finance["consumerLoanEnabled"] as? Boolean ?: true
                    val filePlans = (finance["loanPlans"] as? List<*>)
                        ?.mapNotNull { it as? Map<*, *> }
                        ?.mapNotNull { m ->
                            val p = (m["periods"] as? Number)?.toInt()?.coerceAtLeast(1)
                            val r = (m["feeRate"] as? Number)?.toDouble()?.coerceIn(0.0, 1.0)
                            if (p != null && r != null) LoanPlan(p, r) else null
                        } ?: emptyList()
                    val creditLimit = finance["creditLimit"] as? Map<*, *>
                    val fileRecent30 = (creditLimit?.get("recent30Weight") as? Number)?.toDouble() ?: 0.5
                    val fileHistory = (creditLimit?.get("historyWeight") as? Number)?.toDouble() ?: 0.1
                    val fileDebt = (creditLimit?.get("debtWeight") as? Number)?.toDouble() ?: 0.3
                    val fileLimitMin = (creditLimit?.get("min") as? Number)?.toLong() ?: 0L
                    val fileCooldown = (creditLimit?.get("cooldownHours") as? Number)?.toLong() ?: 24L
                    val fileLimitMax = (creditLimit?.get("max") as? Number)?.toLong() ?: 100_000L
                    val fileMinBalance = (finance["autoRepayMinBalance"] as? Number)?.toLong() ?: 1_000L
                    val fileDepositRate = (finance["dailyDepositRate"] as? Number)?.toDouble() ?: 0.0001
                    val filePairWindow = (finance["tradePairWindowDays"] as? Number)?.toLong() ?: 30L
                    val filePairMax = (finance["tradePairMaxTrades"] as? Number)?.toLong() ?: 3L
                    val fileCardCount = (finance["purpleCardCount"] as? Number)?.toLong() ?: 20L
                    val fileCardLimit = (finance["purpleCardCreditLimit"] as? Number)?.toLong() ?: 1_000_000L
                    val fileCardSelfApply = finance["purpleCardSelfApply"] as? Boolean ?: false
                    val fileApplyAsset = (finance["purpleCardApplyAsset"] as? Number)?.toLong() ?: 0L
                    val fileApplyVolume = (finance["purpleCardApplyVolume"] as? Number)?.toLong() ?: 0L
                    val fileApplyCredit = (finance["purpleCardApplyCredit"] as? Number)?.toLong() ?: 0L
                    val fileApplyDeposit = (finance["purpleCardApplyDeposit"] as? Number)?.toLong() ?: 0L
                    val fileApplyNoOverdue = finance["purpleCardApplyNoOverdue"] as? Boolean ?: false
                    val fileApplySeen = (finance["purpleCardApplySeen"] as? Number)?.toLong() ?: 0L
                    val fileApplyDex = (finance["purpleCardApplyDex"] as? Number)?.toLong() ?: 0L
                    val fileApplyFee = (finance["purpleCardApplyFee"] as? Number)?.toLong() ?: 0L
                    val fileRedoFee = (finance["purpleCardRedoFee"] as? Number)?.toLong() ?: 0L
                    val fileFeeDiscount = (finance["purpleCardFeeDiscount"] as? Number)?.toDouble() ?: 0.0
                    val fileBlackCardCount = (finance["blackCardCount"] as? Number)?.toLong() ?: 5L
                    val fileBlackCardLimit = (finance["blackCardCreditLimit"] as? Number)?.toLong() ?: 5_000_000L
                    val fileBlackCardSelfApply = finance["blackCardSelfApply"] as? Boolean ?: false
                    val fileBlackApplyAsset = (finance["blackCardApplyAsset"] as? Number)?.toLong() ?: 0L
                    val fileBlackApplyVolume = (finance["blackCardApplyVolume"] as? Number)?.toLong() ?: 0L
                    val fileBlackApplyCredit = (finance["blackCardApplyCredit"] as? Number)?.toLong() ?: 0L
                    val fileBlackApplyDeposit = (finance["blackCardApplyDeposit"] as? Number)?.toLong() ?: 0L
                    val fileBlackApplyNoOverdue = finance["blackCardApplyNoOverdue"] as? Boolean ?: false
                    val fileBlackApplySeen = (finance["blackCardApplySeen"] as? Number)?.toLong() ?: 0L
                    val fileBlackApplyDex = (finance["blackCardApplyDex"] as? Number)?.toLong() ?: 0L
                    val fileBlackApplyFee = (finance["blackCardApplyFee"] as? Number)?.toLong() ?: 0L
                    val fileBlackRedoFee = (finance["blackCardRedoFee"] as? Number)?.toLong() ?: 0L
                    val fileBlackFeeDiscount = (finance["blackCardFeeDiscount"] as? Number)?.toDouble() ?: 0.0
                    val fileIpDebtLimit = (finance["ipDebtLimit"] as? Number)?.toLong() ?: 100_000L
                    val overdueDays = finance["overdueDays"] as? Map<*, *>
                    val fileFeeDouble = (overdueDays?.get("feeDouble") as? Number)?.toInt() ?: 7
                    val fileFreeze = (overdueDays?.get("freeze") as? Number)?.toInt() ?: 14
                    val fileBadDebt = (overdueDays?.get("badDebt") as? Number)?.toInt() ?: 30
                    financeEnabled = fileEnabled
                    cashLoanEnabled = fileCash
                    consumerLoanEnabled = fileConsumer
                    loanPlans = filePlans.ifEmpty { listOf(LoanPlan(3, 0.005), LoanPlan(6, 0.008), LoanPlan(12, 0.012)) }
                    creditLimitRecent30Weight = fileRecent30
                    creditLimitHistoryWeight = fileHistory
                    creditLimitDebtWeight = fileDebt
                    creditLimitMin = fileLimitMin.coerceAtLeast(0L)
                    creditLimitMax = fileLimitMax.coerceAtLeast(fileLimitMin)
                    creditLimitCooldownHours = fileCooldown.coerceAtLeast(0L)
                    autoRepayMinBalance = fileMinBalance.coerceAtLeast(0L)
                    dailyDepositRate = fileDepositRate.coerceIn(0.0, 1.0)
                    enforceDepositRateGuard() // loanPlans 已应用（上一行之前），套利上界可算
                    tradePairWindowDays = filePairWindow.coerceAtLeast(1L)
                    tradePairMaxTrades = filePairMax.coerceAtLeast(1L)
                    purpleCardCount = fileCardCount.coerceAtLeast(0L)
                    purpleCardCreditLimit = fileCardLimit.coerceAtLeast(0L)
                    purpleCardSelfApply = fileCardSelfApply
                    purpleCardApplyAsset = fileApplyAsset.coerceAtLeast(0L)
                    purpleCardApplyVolume = fileApplyVolume.coerceAtLeast(0L)
                    purpleCardApplyCredit = fileApplyCredit.coerceAtLeast(0L)
                    purpleCardApplyDeposit = fileApplyDeposit.coerceAtLeast(0L)
                    purpleCardApplyNoOverdue = fileApplyNoOverdue
                    purpleCardApplySeen = fileApplySeen.coerceAtLeast(0L)
                    purpleCardApplyDex = fileApplyDex.coerceAtLeast(0L)
                    purpleCardApplyFee = fileApplyFee.coerceAtLeast(0L)
                    purpleCardRedoFee = fileRedoFee.coerceAtLeast(0L)
                    purpleCardFeeDiscount = fileFeeDiscount.coerceIn(0.0, 1.0)
                    blackCardCount = fileBlackCardCount.coerceAtLeast(0L)
                    blackCardCreditLimit = fileBlackCardLimit.coerceAtLeast(0L)
                    blackCardSelfApply = fileBlackCardSelfApply
                    blackCardApplyAsset = fileBlackApplyAsset.coerceAtLeast(0L)
                    blackCardApplyVolume = fileBlackApplyVolume.coerceAtLeast(0L)
                    blackCardApplyCredit = fileBlackApplyCredit.coerceAtLeast(0L)
                    blackCardApplyDeposit = fileBlackApplyDeposit.coerceAtLeast(0L)
                    blackCardApplyNoOverdue = fileBlackApplyNoOverdue
                    blackCardApplySeen = fileBlackApplySeen.coerceAtLeast(0L)
                    blackCardApplyDex = fileBlackApplyDex.coerceAtLeast(0L)
                    blackCardApplyFee = fileBlackApplyFee.coerceAtLeast(0L)
                    blackCardRedoFee = fileBlackRedoFee.coerceAtLeast(0L)
                    blackCardFeeDiscount = fileBlackFeeDiscount.coerceIn(0.0, 1.0)
                    ipDebtLimit = fileIpDebtLimit.coerceAtLeast(0L)
                    // 逾期三档钳制：0 也可（关闭该档位动作），负数钳 0
                    overdueFeeDoubleDays = fileFeeDouble.coerceAtLeast(0)
                    overdueFreezeDays = fileFreeze.coerceAtLeast(0)
                    overdueBadDebtDays = fileBadDebt.coerceAtLeast(0)
                }
                val legacyFee = data["listingFeePercent"] as? Double
                // 手续费钳制 0~100：超过 100% 会让卖家账本变负数（抵消后续所有收入）
                pokemonListingFeePercent = ((data["pokemonListingFeePercent"] as? Double) ?: legacyFee ?: 5.0).coerceIn(0.0, 100.0)
                itemListingFeePercent = ((data["itemListingFeePercent"] as? Double) ?: legacyFee ?: 5.0).coerceIn(0.0, 100.0)
                // RP 费率：缺失按 0（免）—— 老配置升级上来不会突然开始扣 RP
                pointsPokemonListingFeePercent =
                    ((data["pointsPokemonListingFeePercent"] as? Double) ?: 0.0).coerceIn(0.0, 100.0)
                pointsItemListingFeePercent =
                    ((data["pointsItemListingFeePercent"] as? Double) ?: 0.0).coerceIn(0.0, 100.0)
                pointsAuctionFeePercent =
                    ((data["pointsAuctionFeePercent"] as? Double) ?: 0.0).coerceIn(0.0, 100.0)
                pointsBuyOrderFeePercent =
                    ((data["pointsBuyOrderFeePercent"] as? Double) ?: 0.0).coerceIn(0.0, 100.0)
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
                marketEnabled = data["marketEnabled"] as? Boolean ?: true
                shareEnabled = data["shareEnabled"] as? Boolean ?: true
                pointsEnabled = data["pointsEnabled"] as? Boolean ?: false
                // 缺失按 false（保守）：老配置升级上来时玩家间 RP 交易一律先关着
                pointsMarketEnabled = data["pointsMarketEnabled"] as? Boolean ?: false
                pointsExternalSecret = data["pointsExternalSecret"] as? String ?: ""
                pointsExternalMaxDays = ((data["pointsExternalMaxDays"] as? Double)?.toInt() ?: 365).coerceAtLeast(0)
                val shop = data["pointsShop"] as? Map<*, *>
                if (shop != null) {
                    val shopKeys =
                        setOf("enabled", "payQrImage", "defaultIcon", "orderTimeoutMinutes", "tiers", "currencyLabel")
                    if (shopKeys.any { !shop.containsKey(it) }) missingKeys = true
                    pointsShopEnabled = shop["enabled"] as? Boolean ?: false
                    pointsShopPayQrImage = (shop["payQrImage"] as? String)?.trim().orEmpty()
                    pointsShopDefaultIcon = (shop["defaultIcon"] as? String)?.trim().orEmpty()
                    // 超时钳 1 分钟~24 小时：0 会让订单一创建就过期，过大则废单长期挂在玩家列表里
                    pointsShopOrderTimeoutMinutes =
                        ((shop["orderTimeoutMinutes"] as? Number)?.toLong() ?: 15L).coerceIn(1L, 1440L)
                    pointsShopTiers = (shop["tiers"] as? List<*>)
                        ?.mapIndexedNotNull { index, element -> parseShopTier(element, index) }
                        ?: emptyList()
                    pointsShopCurrencyLabel = (shop["currencyLabel"] as? String)?.trim().orEmpty()
                }
                // 跨项校验（手工改过 config.json 也兜住）
                enforceCardApplySeenGuard()
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
        if (!skipCurrency) CurrencyHandler.load(this)
    }

    /** /market reload 专用：重载除货币外的全部配置；返回 true = 文件里货币配置与运行时不一致（需重启生效） */
    fun reload(): Boolean {
        currencyChangedSinceReload = false
        load(skipCurrency = true)
        return currencyChangedSinceReload
    }

    fun save() {
        enforceDepositRateGuard() // 双保险：落盘值恒为钳后值
        val data = mapOf(
            "_comments" to mapOf(
                "currency.cobbledollars" to "是否使用 CobbleDollars 货币（true/false，cobblemonEconomy=true 时被忽略）。⚠ 货币配置仅在服务器启动时读取，修改后需重启生效 / Whether to use CobbleDollars currency (true/false, ignored when cobblemonEconomy=true). ⚠ Currency settings are read only at server startup — restart after changes",
                "currency.cobblemonEconomy" to "⚠ 与 Cobblemon 1.8+ 不兼容（会导致玩家选择初始精灵时崩服），启用时服务器启动会给出警告。是否优先使用 Cobblemon Economy 的货币 API（true/false）。true 时市场余额走 Cobblemon Economy 后端，其内置桥接可路由到 CobbleDollars/Impactor——若服主在 Cobblemon Economy 配置里把 main_currency 设为 cobbledollars，市场与 CobbleDollars 商人共享同一余额；旧配置升级默认 false（行为不变），全新安装默认按探测自动开启。⚠ 仅 Fabric 平台生效：Cobblemon Economy 无 NeoForge 版，NeoForge 上此开关恒被忽略 / ⚠ Incompatible with Cobblemon 1.8+ (crashes the server when a player picks a starter Pokémon) — enabling it logs a warning at startup. Prefer Cobblemon Economy's currency API (true/false). When true the market uses the Cobblemon Economy backend, whose built-in bridge can route to CobbleDollars/Impactor — if main_currency=cobbledollars in Cobblemon Economy config, the market and CobbleDollars merchants share one balance; defaults to false on config upgrade (no behavior change) and to auto-detection on fresh installs. ⚠ Fabric only: Cobblemon Economy has no NeoForge build, so this switch is always ignored on NeoForge",
                "currency.cobecoCurrency" to "Cobblemon Economy 结算货币：POKE=PokeDollars（默认），PCO=PokeCoins（写 PCO 或 PokeCoins 均可，不区分大小写）。仅 cobblemonEconomy=true 时生效；PCO 与 PokeDollars 是两套独立账本，市场用 PCO 结算时玩家 /pco 查到的余额就是市场余额 / Cobblemon Economy settlement currency: POKE=PokeDollars (default), PCO=PokeCoins (either PCO or PokeCoins, case-insensitive). Only used when cobblemonEconomy=true; PCO and PokeDollars are separate ledgers — with PCO the market balance equals what players see via /pco",
                "currency.impactor" to "Impactor 直连开关（true/false，双平台可用）：不装 Cobblemon Economy 时直接走 Impactor 的 EconomyService API，市场余额即 Impactor 主货币账户。优先级低于 cobblemonEconomy 与 cobbledollars（两者任一开启时被忽略）；默认 false 且不参与全新安装自动探测（Impactor 常被其它模组当作基础依赖安装，自动开启会静默切换货币后端），想用请显式写 true / Direct Impactor integration (true/false, works on both loaders): without Cobblemon Economy, the market talks to Impactor's EconomyService API directly and the market balance is the Impactor primary currency account. Lower priority than cobblemonEconomy and cobbledollars (ignored when either is on); defaults to false and is NOT auto-detected on fresh installs (Impactor is often installed as a library by other mods — auto-enabling would silently switch the currency backend), set true explicitly to use it",
                "currency.item" to "货币物品 ID（cobbledollars / cobblemonEconomy / impactor 均为 false 时生效）/ Currency item ID (used when cobbledollars, cobblemonEconomy and impactor are all false)",
                "pokemonListingFeePercent" to "精灵市场上架手续费百分比（0=免手续费）/ Pokémon listing fee percentage (0=no fee)",
                "itemListingFeePercent" to "物品市场上架手续费百分比（0=免手续费）/ Item listing fee percentage (0=no fee)",
                "pointsPokemonListingFeePercent" to "RP 精灵市场上架手续费百分比（默认 0=免手续费）。RP 若是真钱充值的货币建议保持 0——扣它等于回收玩家花真钱买的东西 / RP Pokémon listing fee percentage (default 0 = no fee). Keep it 0 if RP is bought with real money",
                "pointsItemListingFeePercent" to "RP 物品市场上架手续费百分比（默认 0=免手续费），同上 / RP item listing fee percentage (default 0 = no fee), see above",
                "pointsAuctionFeePercent" to "RP 拍卖成交手续费百分比（默认 0=免手续费），同上 / RP auction fee percentage (default 0 = no fee), see above",
                "pointsBuyOrderFeePercent" to "RP 求购中介费百分比（默认 0=免手续费），同上 / RP buy-order fee percentage (default 0 = no fee), see above",
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
                "pointsExternalSecret" to "外部签名码的 HMAC 密钥：服主在自己的网站 / 发卡网卖RP 时，用同一个密钥签码，玩家在游戏内兑换。⚠ **留空 = 关闭外部码**（只接受 /market points code create 生成的本地码）。**必须放在服务端**（网站后端 / 服主本机），绝不能进客户端、日志或公开仓库；服务端日志只记码前缀不记全码（码 = 钱）。建议 ≥32 字符随机串 / HMAC secret for externally signed RP codes: sign codes on your website / card shop with the same secret, players redeem them in game. ⚠ **Leave blank to disable external codes** (only locally generated codes work). **Server-side only** (website backend / your machine) — never ship it in a client, log, or public repo. Logs record only a code prefix, never the full code (a code is money). Recommended: 32+ random characters",
                "pointsExternalMaxDays" to "外部码的有效期上限（天，默认 365）：签发时有效期跨度超过它的码一律拒绝，防「永久码」满天飞（永久码的 serial 必须永久保留，会让存档持续膨胀）/ Maximum validity for external codes in days (default 365): codes valid for longer are rejected, preventing \"forever codes\" (whose serials must be kept forever and would bloat the save)",
                "pointsEnabled" to "RP 总开关（默认关闭）：RP 是本模组的第二货币，与主货币并行流通但**不可兑换、不可转让、不参与金融**。关闭时：玩家设置价格的界面不出现RP 按钮，除RP 商城外的任何地方都不能用RP 交易（服主仍可在RP 商城上架精灵/物品供持有RP 的玩家购买）。⚠ RP 等同于充值货币，开启前请先配置发放渠道 / RP master switch (off by default): RP is this mod's second currency, running alongside the primary one but **not convertible, not transferable, and outside the finance system**. When off, the RP button is hidden from every price-setting screen and RP cannot be used anywhere except the RP Shop (owners can still stock the RP Shop for players who already hold RP). ⚠ RP is effectively a paid currency — set up a distribution channel before enabling",
                "shareEnabled" to "玩家展示开关（默认开启）：开启后玩家可把精灵/物品展示到全服聊天（悬停看详情），每人 10 秒一次。关闭后展示按钮不再生效 / Player showcase switch (on by default): players can showcase Pokémon/items to server chat (hover for details), once per 10 seconds each. When off, the showcase button stops working",
                "pointsShop.enabled" to "游戏内充值开关（默认关闭，需 pointsEnabled 同时开启）。国内小服主普遍用个人收款码，而个人收款码没有支付回调——本功能**不做自动收款**，只把对账成本压到最低：玩家在游戏内下单（实付金额就是档位标价 + 6 位订单号）→ 扫码付款 → 点「我已支付」→ 服主对着账单点确认发放。⚠ 模组无法判断是否真的付了款，「我已支付」只是玩家的声明：**未确认一律不发放** / In-game top-up switch (off by default, requires pointsEnabled too). Personal payment QR codes have no payment callback, so this feature does NOT collect payments automatically — it only minimizes reconciliation work: the player orders in game (paying the list price, with a 6-char order number) → scans the QR → pays → taps \"I have paid\" → the owner checks the bill and confirms the grant. ⚠ The mod cannot tell whether a payment actually happened: \"I have paid\" is a claim, and nothing is granted until the owner confirms",
                "pointsShop.payQrImage" to "收款码图片文件名（放在 config/cobblemarket/qr/ 下，如 pay.png；PNG 格式，单张 ≤512KB、像素 ≤2048×2048，收款码本身是正方形、512×512 左右就够）。**留空 = 走全局默认**。图片由服务端读取后下发给客户端绘制；订单号与实付金额由模组**动态绘制在图片旁**（不写进图片文件），防止玩家截图转给别人代付后认领。⚠ 每个档位还能在「RP 市场配置」界面里配**自己的**收款码（微信/支付宝的『设置金额』码），配了的那一档用那张码，实付金额同样是标价 / Payment QR image file name under config/cobblemarket/qr/ (e.g. pay.png; PNG, 512KB max and 2048x2048 pixels max — a payment QR is square, 512x512 is plenty). **Leave blank to fall back to the global default.** The server reads it and sends it to clients for drawing; the order number and exact amount are drawn by the mod next to the image (never baked into the file), so a shared screenshot cannot be claimed by someone else. ⚠ Each tier can also have its OWN QR image (set it in the in-game RP Market config screen) — a \"fixed amount\" code from your payment app; that tier then use its own code, still charging the list price",
                "pointsShop.defaultIcon" to "档位「RP 券」的**全局默认**图文件名（放在 config/cobblemarket/icons/ 下，如 coupon.png；PNG 格式，单张 ≤512KB、像素 ≤2048×2048，建议 256×160 或它的整数倍 512×320）。填了它，所有没单独配券图的档位都用这张；留空 = 用模组内置券图。**每个档位还能配自己的**（见 tiers 的 icon）。图片由服务端读取后下发给客户端绘制，什么宽高比都行（卡片按原图比例缩放，不会变形）/ **Global default** RP coupon image file name under config/cobblemarket/icons/ (e.g. coupon.png; PNG, 512KB max and 2048x2048 pixels max — 256x160 or a multiple such as 512x320 recommended). Every tier without its OWN coupon image uses this one; leave blank to use the mod's built-in coupon image. **Each tier can also have its own** (see the icon field of tiers). The server reads it and sends it to clients; any aspect ratio works (the card scales the image to fit without distortion)",
                "pointsShop.orderTimeoutMinutes" to "待支付订单超时分钟数（默认 15，钳 1~1440）：超时后订单作废，玩家可重新下单。⚠ 超时后有 30 分钟宽限期，期内玩家仍可提交「我已支付」（他可能已经付款、只是回来点得慢；作废是安全的失败方向，误判成已付款才危险）/ Minutes before an unpaid order expires (default 15, clamped 1–1440). Expired orders are voided so the player can order again. ⚠ A 30-minute grace period follows, during which \"I have paid\" is still accepted (the player may have paid but tapped late; voiding is the safe failure direction, wrongly granting is not)",
                "pointsShop.tiers" to "充值档位列表：[{name=展示名, price=标价, points=发放点数, qr=该档收款码文件名(可留空), icon=该档RP券图文件名(可留空，放在 config/cobblemarket/icons/ 下，留空则用内置券图)}]。**实付金额 = 档位标价**——玩家在界面上看到多少就付多少，模组不加任何钱。留空数组 = 不开放「购买RP」/ Top-up tiers: [{name=display name, price=list price, points=points granted, qr=this tier's own QR file name (optional), icon=this tier's RP coupon image file name (optional; put it under config/cobblemarket/icons/, blank = built-in coupon image)}]. **Amount to pay = the list price** — the player pays exactly what the screen shows; the mod adds nothing. An empty array disables the in-game purchase entry",
                "maxBuyOrdersPerPlayer" to "每个玩家同时进行的求购单数量上限，精灵与物品合计（0=不限制）。求购单列表全量下发给所有客户端，玩家较多的服务器建议保持较小值，避免全服活跃求购单总量过大导致卡顿 / Max concurrent buy orders per player, Pokémon and items combined (0=unlimited). The buy order list is broadcast in full to every client, so on crowded servers keep this small to avoid lag from too many active orders",
                "finance.enabled" to "金融系统总开关（默认关闭）。开启后玩家可借贷/使用喵喵支付；关闭时禁止新增借贷与信用支付，已有贷款照常运行（还款/逾期/坏账不受影响） / Master switch for the finance system (off by default). When on, players can take loans and use Meowth Pay; when off, new loans and credit payments are blocked while existing loans keep running (repayment/overdue/bad debt unaffected)",
                "finance.cashLoanEnabled" to "现金贷开关（借呗）：玩家在喵喵银行柜台主动借款 / Cash loan switch (Jiebei-style): players borrow cash directly at the Meowth Bank counter",
                "finance.consumerLoanEnabled" to "消费贷开关（喵喵支付）：购买精灵/物品、拍卖出价时可选择信用垫付 / Consumer loan switch (Meowth Pay): credit payment option when buying Pokémon/items or bidding in auctions",
                "finance.loanPlans" to "分期方案数组：periods=期数（每期 7 天），feeRate=每期费率（0.005=0.5%）。UI 只显示每期费率，不写年化 / Loan plan array: periods=number of periods (7 days each), feeRate=fee per period (0.005=0.5%). The UI shows only the per-period fee, never an annualized rate",
                "finance.creditLimit" to "额度公式系数：信用基础 = 近30天交易额×recent30Weight + 历史交易额×historyWeight（钳 min）；可用额度 = 信用基础 − 当前欠款全额（欠多少扣多少，欠款不打折），再钳 max。借贷来源的交易不计入交易额（防借→买→额度涨→再借循环）。 / Credit limit weights: credit base = last-30-day volume×recent30Weight + all-time volume×historyWeight (clamped to min); available limit = credit base − full outstanding debt (no discount on debt), then clamped to max. Loan-funded trades never count toward volume (prevents borrow→buy→limit-up→borrow loops)",
                "finance.autoRepayMinBalance" to "到期自动划扣最低保留：每期到期自动从玩家市场余额全额划扣当期应还（本金+利息），最多划到余额=此值为止；划不足进入逾期流程 / Minimum balance kept during auto-repayment: on each due date the full period payment is auto-deducted from the player's market balance, stopping at this floor; any shortfall enters the overdue flow",
                "finance.ipDebtLimit" to "同 IP 未结清欠款总和上限（防同 IP 多小号分散借款转账给主账号；OP 豁免；0=不限制）：借款/喵喵支付时，同 IP 30 天窗口内所有玩家的未结清欠款总和+本次金额超过此值则拒绝 / Cap on total outstanding debt per IP (blocks many alt accounts on one IP borrowing and funneling money to a main account; OPs exempt; 0=disabled): when borrowing or paying via Meowth Pay, the request is rejected if the combined outstanding debt of all players seen on the same IP within 30 days plus this amount exceeds the cap",
                "finance.overdueDays" to "逾期天数三档：feeDouble=逾期该天数后市场手续费翻倍，freeze=冻结挂单/待领取（拦交易不拦取回），badDebt=坏账冲销 / Overdue day tiers: feeDouble=fee doubling after this many days overdue, freeze=freeze listings/returns (blocks trading, not withdrawals), badDebt=write-off as bad debt",
                "finance.dailyDepositRate" to "活期存款日利率（0.0001=每天0.01%≈年化3.65%），按实际存入时长计息、不满一天按比例；利息从准备金池支出。⚠ 有防套利护栏：不得超过最便宜贷款方案的套利上界，超出自动钳制并记日志；贷款方案全免息时此值自动钳为 0 / Daily interest rate for demand deposits (0.0001=0.01% per day ≈ 3.65% per year). Accrues by actual deposit time, prorated for partial days; paid from the reserve pool. ⚠ Guarded against arbitrage: it cannot exceed the cheapest loan plan's safe bound — values above are auto-clamped and logged; with all loan plans fee-free it is clamped to 0",
                "finance.tradePairWindowDays" to "交易对检测窗口天数：同一买卖对在此窗口内成交超过笔数上限后，该对后续成交不计入借款额度（防互买对刷，默认 30）/ Same-pair detection window in days: once the same buyer-seller pair exceeds the trade cap within this window, their later trades stop counting toward credit limits (anti wash-trading, default 30)",
                "finance.tradePairMaxTrades" to "交易对检测笔数上限：同一买卖对在窗口内成交达到此笔数后，该对后续成交不计入借款额度（防互买对刷，默认 3）/ Same-pair trade cap: once a buyer-seller pair reaches this many trades within the window, their later trades stop counting toward credit limits (anti wash-trading, default 3)",
                "finance.purpleCardCount" to "喵·紫金卡全服发放上限（0=不限制，默认 20）。额度绑定持有者状态而非物品，复制出的卡无效 / Server-wide cap on Meow·Purple Gold Cards (0=unlimited, default 20). The limit is bound to holder state, not the item — duplicated cards are worthless",
                "finance.purpleCardCreditLimit" to "喵·紫金卡持有者的固定借款额度（默认 100 万）/ Fixed borrowing limit for Meow·Purple Gold Card holders (default 1,000,000)",
                "finance.purpleCardSelfApply" to "是否允许玩家自行申请喵·紫金卡（需满足下方七项门槛 + 缴纳申请费）；关闭时仅服主可用 /market card give 发放 / Allow players to self-apply for the Purple Gold Card (must pass the seven conditions below and pay the fee); when off, only owners can issue via /market card give",
                "finance.purpleCardApplyAsset" to "紫卡申请门槛·资产：玩家当前现金余额达到该值才可申请（0=不要求）/ Purple apply condition · assets: the player's current cash balance must reach this to apply (0=not required)",
                "finance.purpleCardApplyVolume" to "紫卡申请门槛·消费金额：玩家历史买入成交额累计达到该值才可申请（0=不要求）/ Purple apply condition · spending: the player's all-time counted buying volume must reach this to apply (0=not required)",
                "finance.purpleCardApplyCredit" to "紫卡申请门槛·额度：玩家信用基础（无欠款时的额度公式值）达到该值才可申请（0=不要求）/ Purple apply condition · credit: the player's credit base (limit formula value without debt) must reach this to apply (0=not required)",
                "finance.purpleCardApplyDeposit" to "紫卡申请门槛·净存款：玩家净存款（活期存款 − 未还欠款）达到该值才可申请——借钱充存款无效（0=不要求）/ Purple apply condition · net deposit: the player's net deposit (demand deposit − outstanding debt) must reach this to apply — borrowed money can't inflate it (0=not required)",
                "finance.purpleCardApplyNoOverdue" to "紫卡申请门槛·无逾期：true=有逾期或坏账记录的玩家不能申请 / Purple apply condition · clean record: true=players with overdue or bad-debt records cannot apply",
                "finance.purpleCardApplySeen" to "紫卡申请门槛·图鉴遇见数：玩家图鉴已遇见物种数（含已捕捉）达到该值才可申请（0=不要求）/ Purple apply condition · Pokédex encounters: the player's encountered-species count (includes caught) must reach this to apply (0=not required)",
                "finance.purpleCardApplyDex" to "紫卡申请门槛·图鉴捕捉数：玩家图鉴已捕捉物种数达到该值才可申请（0=不要求）/ Purple apply condition · Pokédex caught: the player's caught-species count must reach this to apply (0=not required)",
                "finance.purpleCardApplyFee" to "喵·紫金卡申请费用（申请成功时扣除，进入准备金池；0=免费）/ Purple Gold Card application fee (charged on success, goes to the reserve pool; 0=free)",
                "finance.purpleCardRedoFee" to "补发喵·紫金卡凭证费用（持有者丢弃凭证后在喵喵银行重新领取时扣除，进入准备金池；0=免费）/ Purple Gold Card reissue fee (charged when a holder re-obtains a lost card at Meowth Bank, goes to the reserve pool; 0=free)",
                "finance.purpleCardFeeDiscount" to "喵·紫金卡持有者的市场手续费减免比例（上架费/拍卖成交费/求购中介费全覆盖，与逾期翻倍叠加；0=无减免）/ Market fee discount ratio for Purple Gold Card holders (covers listing/auction/buy-order fees, stacks with overdue doubling; 0=no discount)",
                "finance.blackCardCount" to "喵·黑金卡全服发放上限（0=不限制，默认 5）。申请硬条件为持有喵·紫金卡；获得黑卡自动移除紫卡资格（升级替代）/ Server-wide cap on Meow·Black Gold Cards (0=unlimited, default 5). Applying requires holding the Purple Gold Card; obtaining the Black Gold Card auto-removes the Purple Gold Card qualification (upgrade replacement)",
                "finance.blackCardCreditLimit" to "喵·黑金卡持有者的固定借款额度（默认 500 万）/ Fixed borrowing limit for Meow·Black Gold Card holders (default 5,000,000)",
                "finance.blackCardSelfApply" to "是否允许玩家自行申请喵·黑金卡（需持有喵·紫金卡 + 满足七项门槛 + 缴纳申请费）；关闭时仅服主可用 /market card give 发放 / Allow players to self-apply for the Black Gold Card (requires holding the Purple Gold Card + the seven conditions + the fee); when off, only owners can issue via /market card give",
                "finance.blackCardApplyAsset" to "黑卡申请门槛·资产：玩家当前现金余额达到该值才可申请（0=不要求）/ Black apply condition · assets: the player's current cash balance must reach this to apply (0=not required)",
                "finance.blackCardApplyVolume" to "黑卡申请门槛·消费金额：玩家历史买入成交额累计达到该值才可申请（0=不要求）/ Black apply condition · spending: the player's all-time counted buying volume must reach this to apply (0=not required)",
                "finance.blackCardApplyCredit" to "黑卡申请门槛·额度：玩家信用基础（无欠款时的额度公式值）达到该值才可申请（0=不要求）/ Black apply condition · credit: the player's credit base (limit formula value without debt) must reach this to apply (0=not required)",
                "finance.blackCardApplyDeposit" to "黑卡申请门槛·净存款：玩家净存款（活期存款 − 未还欠款）达到该值才可申请——借钱充存款无效（0=不要求）/ Black apply condition · net deposit: the player's net deposit (demand deposit − outstanding debt) must reach this to apply — borrowed money can't inflate it (0=not required)",
                "finance.blackCardApplyNoOverdue" to "黑卡申请门槛·无逾期：true=有逾期或坏账记录的玩家不能申请 / Black apply condition · clean record: true=players with overdue or bad-debt records cannot apply",
                "finance.blackCardApplySeen" to "黑卡申请门槛·图鉴遇见数：玩家图鉴已遇见物种数（含已捕捉）达到该值才可申请（0=不要求）/ Black apply condition · Pokédex encounters: the player's encountered-species count (includes caught) must reach this to apply (0=not required)",
                "finance.blackCardApplyDex" to "黑卡申请门槛·图鉴捕捉数：玩家图鉴已捕捉物种数达到该值才可申请（0=不要求）/ Black apply condition · Pokédex caught: the player's caught-species count must reach this to apply (0=not required)",
                "finance.blackCardApplyFee" to "喵·黑金卡申请费用（申请成功时扣除，进入准备金池；0=免费）/ Black Gold Card application fee (charged on success, goes to the reserve pool; 0=free)",
                "finance.blackCardRedoFee" to "补发喵·黑金卡凭证费用（持有者丢弃凭证后在喵喵银行重新领取时扣除，进入准备金池；0=免费）/ Black Gold Card reissue fee (charged when a holder re-obtains a lost card at Meowth Bank, goes to the reserve pool; 0=free)",
                "finance.blackCardFeeDiscount" to "喵·黑金卡持有者的市场手续费减免比例（上架费/拍卖成交费/求购中介费全覆盖，与逾期翻倍叠加；0=无减免）/ Market fee discount ratio for Black Gold Card holders (covers listing/auction/buy-order fees, stacks with overdue doubling; 0=no discount)"
            ),
            "currency" to mapOf("cobbledollars" to cobbledollars, "cobblemonEconomy" to cobblemonEconomy, "cobecoCurrency" to cobecoCurrency, "impactor" to impactor, "item" to currencyItem),
            "pokemonListingFeePercent" to pokemonListingFeePercent,
            "itemListingFeePercent" to itemListingFeePercent,
            "pointsPokemonListingFeePercent" to pointsPokemonListingFeePercent,
            "pointsItemListingFeePercent" to pointsItemListingFeePercent,
            "pointsAuctionFeePercent" to pointsAuctionFeePercent,
            "pointsBuyOrderFeePercent" to pointsBuyOrderFeePercent,
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
            "marketEnabled" to marketEnabled,
            "shareEnabled" to shareEnabled,
            "pointsEnabled" to pointsEnabled,
            // ⚠ 落盘这一行曾经漏过：读取端有、写入端没有 → 开关当次有效、重启就落回默认 false
            "pointsMarketEnabled" to pointsMarketEnabled,
            "pointsExternalSecret" to pointsExternalSecret,
            "pointsExternalMaxDays" to pointsExternalMaxDays,
            "pointsShop" to mapOf(
                "enabled" to pointsShopEnabled,
                "payQrImage" to pointsShopPayQrImage,
                "defaultIcon" to pointsShopDefaultIcon,
                "orderTimeoutMinutes" to pointsShopOrderTimeoutMinutes,
                "currencyLabel" to pointsShopCurrencyLabel,
                // 金额按分存、落盘时还原成「元」的写法（5.5）——服主手改配置时按元写最直观
                "tiers" to pointsShopTiers.map {
                    mapOf(
                        "name" to it.name,
                        "price" to it.priceCents / 100.0,
                        "points" to it.points,
                        "qr" to it.qr,
                        "icon" to it.icon
                    )
                }
            ),
            "finance" to mapOf(
                "enabled" to financeEnabled,
                "cashLoanEnabled" to cashLoanEnabled,
                "consumerLoanEnabled" to consumerLoanEnabled,
                "loanPlans" to loanPlans.map { mapOf("periods" to it.periods, "feeRate" to it.feeRate) },
                "creditLimit" to mapOf(
                    "recent30Weight" to creditLimitRecent30Weight,
                    "historyWeight" to creditLimitHistoryWeight,
                    "min" to creditLimitMin,
                    "max" to creditLimitMax,
                    "cooldownHours" to creditLimitCooldownHours
                ),
                "autoRepayMinBalance" to autoRepayMinBalance,
                "dailyDepositRate" to dailyDepositRate,
                "tradePairWindowDays" to tradePairWindowDays,
                "tradePairMaxTrades" to tradePairMaxTrades,
                "purpleCardCount" to purpleCardCount,
                "purpleCardCreditLimit" to purpleCardCreditLimit,
                "purpleCardSelfApply" to purpleCardSelfApply,
                "purpleCardApplyAsset" to purpleCardApplyAsset,
                "purpleCardApplyVolume" to purpleCardApplyVolume,
                "purpleCardApplyCredit" to purpleCardApplyCredit,
                "purpleCardApplyDeposit" to purpleCardApplyDeposit,
                "purpleCardApplyNoOverdue" to purpleCardApplyNoOverdue,
                "purpleCardApplySeen" to purpleCardApplySeen,
                "purpleCardApplyDex" to purpleCardApplyDex,
                "purpleCardApplyFee" to purpleCardApplyFee,
                "purpleCardRedoFee" to purpleCardRedoFee,
                "purpleCardFeeDiscount" to purpleCardFeeDiscount,
                "blackCardCount" to blackCardCount,
                "blackCardCreditLimit" to blackCardCreditLimit,
                "blackCardSelfApply" to blackCardSelfApply,
                "blackCardApplyAsset" to blackCardApplyAsset,
                "blackCardApplyVolume" to blackCardApplyVolume,
                "blackCardApplyCredit" to blackCardApplyCredit,
                "blackCardApplyDeposit" to blackCardApplyDeposit,
                "blackCardApplyNoOverdue" to blackCardApplyNoOverdue,
                "blackCardApplySeen" to blackCardApplySeen,
                "blackCardApplyDex" to blackCardApplyDex,
                "blackCardApplyFee" to blackCardApplyFee,
                "blackCardRedoFee" to blackCardRedoFee,
                "blackCardFeeDiscount" to blackCardFeeDiscount,
                "ipDebtLimit" to ipDebtLimit,
                "overdueDays" to mapOf(
                    "feeDouble" to overdueFeeDoubleDays,
                    "freeze" to overdueFreezeDays,
                    "badDebt" to overdueBadDebtDays
                )
            )
        )
        // ⚠ 落盘前先留一份上一版：这个文件是**整体重写**的（没有迁移分支），
        //   一旦有哪条路径带着空/默认状态写进来，服主辛苦配的档位、收款码、货币单位就一次性没了 ——
        //   2026-09-19 用户实测踩到（档位 + 货币单位被清空，无从追回）。有 .bak 至少能捞回来。
        runCatching {
            if (configFile.exists()) configFile.copyTo(File(configFile.parentFile, configFile.name + ".bak"), overwrite = true)
        }.onFailure { CobbleMarket.LOGGER.warn("Failed to back up config before save: {}", it.message) }
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
