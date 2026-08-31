package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/**
 * 服务器配置可视化编辑（入口界面「服务器配置」按钮，仅 OP）：
 * 打开界面请求快照 → 数字输入框失焦提交 / 开关即时提交 → 服务端钳制 + 落盘 + 回发新快照。
 * 货币配置不在此列（运行时切换账本错乱，需重启生效，见 /market reload）；
 * auctionDurationOptions 列表项也不在此列（保留 json 编辑）。
 */

// ── C2S: 打开配置界面时请求快照 ──

data class RequestServerConfigPayload(val dummy: Int = 0) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestServerConfigPayload>(CobbleMarket.id("request_server_config"))
        // 不用 PacketCodec.unit：unit 校验发送对象与注册实例 identity 一致，
        // 每次 sendToServer 都是新实例会直接抛 "Can't encode" 断连（neoforge 实测踩坑）
        val CODEC: PacketCodec<PacketByteBuf, RequestServerConfigPayload> = PacketCodec.of(
            { p, b -> b.writeInt(p.dummy) },
            { b -> RequestServerConfigPayload(b.readInt()) }
        )
    }
}

// ── S2C: 配置快照 ──

data class ServerConfigDataPayload(
    val pokemonFee: Double,
    val itemFee: Double,
    val maxPokemonListings: Int,
    val maxItemListings: Int,
    val listingDays: Int,
    val pendingDays: Int,
    val auctionFee: Double,
    val auctionMinBid: Int,
    val antiSnipe: Int,
    val maxAuctions: Int,
    val buyOrderFee: Double,
    val buyOrderExpiry: Int,
    val maxBuyOrders: Int,
    val eggTrading: Boolean,
    val celebration: Boolean,
    /** 拍卖时长选项，逗号分隔（分钟）；服务端解析为 List<Int> */
    val auctionDurations: String,
    // ── 金融系统（喵喵银行）配置 ──
    val financeEnabled: Boolean,
    val cashLoanEnabled: Boolean,
    val consumerLoanEnabled: Boolean,
    /** 分期方案文本（"3:0.005,6:0.008,12:0.012"） */
    val loanPlans: String,
    val creditRecent30: Double,
    val creditHistory: Double,
    val creditDebt: Double,
    val creditMin: Long,
    val creditMax: Long,
    val autoRepayMinBalance: Long,
    val overdueFeeDouble: Int,
    val overdueFreeze: Int,
    val overdueBadDebt: Int,
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<ServerConfigDataPayload>(CobbleMarket.id("server_config_data"))
        val CODEC: PacketCodec<PacketByteBuf, ServerConfigDataPayload> = PacketCodec.of(
            { p, b ->
                b.writeDouble(p.pokemonFee)
                b.writeDouble(p.itemFee)
                b.writeInt(p.maxPokemonListings)
                b.writeInt(p.maxItemListings)
                b.writeInt(p.listingDays)
                b.writeInt(p.pendingDays)
                b.writeDouble(p.auctionFee)
                b.writeInt(p.auctionMinBid)
                b.writeInt(p.antiSnipe)
                b.writeInt(p.maxAuctions)
                b.writeDouble(p.buyOrderFee)
                b.writeInt(p.buyOrderExpiry)
                b.writeInt(p.maxBuyOrders)
                b.writeBoolean(p.eggTrading)
                b.writeBoolean(p.celebration)
                b.writeString(p.auctionDurations)
                b.writeBoolean(p.financeEnabled)
                b.writeBoolean(p.cashLoanEnabled)
                b.writeBoolean(p.consumerLoanEnabled)
                b.writeString(p.loanPlans)
                b.writeDouble(p.creditRecent30)
                b.writeDouble(p.creditHistory)
                b.writeDouble(p.creditDebt)
                b.writeLong(p.creditMin)
                b.writeLong(p.creditMax)
                b.writeLong(p.autoRepayMinBalance)
                b.writeInt(p.overdueFeeDouble)
                b.writeInt(p.overdueFreeze)
                b.writeInt(p.overdueBadDebt)
            },
            // 读端用具名参数，读写字段顺序必须一致
            { b -> ServerConfigDataPayload(
                pokemonFee = b.readDouble(),
                itemFee = b.readDouble(),
                maxPokemonListings = b.readInt(),
                maxItemListings = b.readInt(),
                listingDays = b.readInt(),
                pendingDays = b.readInt(),
                auctionFee = b.readDouble(),
                auctionMinBid = b.readInt(),
                antiSnipe = b.readInt(),
                maxAuctions = b.readInt(),
                buyOrderFee = b.readDouble(),
                buyOrderExpiry = b.readInt(),
                maxBuyOrders = b.readInt(),
                eggTrading = b.readBoolean(),
                celebration = b.readBoolean(),
                auctionDurations = b.readString(),
                financeEnabled = b.readBoolean(),
                cashLoanEnabled = b.readBoolean(),
                consumerLoanEnabled = b.readBoolean(),
                loanPlans = b.readString(),
                creditRecent30 = b.readDouble(),
                creditHistory = b.readDouble(),
                creditDebt = b.readDouble(),
                creditMin = b.readLong(),
                creditMax = b.readLong(),
                autoRepayMinBalance = b.readLong(),
                overdueFeeDouble = b.readInt(),
                overdueFreeze = b.readInt(),
                overdueBadDebt = b.readInt(),
            ) }
        )
    }
}

// ── C2S: 批量保存（点击「保存」时提交全部字段；服务端钳制 + 落盘 + 回发快照，效果等价于改文件后 /market reload） ──

data class SaveServerConfigPayload(
    val pokemonFee: Double,
    val itemFee: Double,
    val maxPokemonListings: Int,
    val maxItemListings: Int,
    val listingDays: Int,
    val pendingDays: Int,
    val auctionFee: Double,
    val auctionMinBid: Int,
    val antiSnipe: Int,
    val maxAuctions: Int,
    val buyOrderFee: Double,
    val buyOrderExpiry: Int,
    val maxBuyOrders: Int,
    val eggTrading: Boolean,
    val celebration: Boolean,
    /** 拍卖时长选项，逗号分隔（分钟）；服务端解析为 List<Int>，解析失败保持旧值 */
    val auctionDurations: String,
    // ── 金融系统（喵喵银行）配置 ──
    val financeEnabled: Boolean,
    val cashLoanEnabled: Boolean,
    val consumerLoanEnabled: Boolean,
    /** 分期方案文本（"3:0.005,6:0.008,12:0.012"）；解析失败保持旧值 */
    val loanPlans: String,
    val creditRecent30: Double,
    val creditHistory: Double,
    val creditDebt: Double,
    val creditMin: Long,
    val creditMax: Long,
    val autoRepayMinBalance: Long,
    val overdueFeeDouble: Int,
    val overdueFreeze: Int,
    val overdueBadDebt: Int,
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<SaveServerConfigPayload>(CobbleMarket.id("save_server_config"))
        val CODEC: PacketCodec<PacketByteBuf, SaveServerConfigPayload> = PacketCodec.of(
            { p, b ->
                b.writeDouble(p.pokemonFee)
                b.writeDouble(p.itemFee)
                b.writeInt(p.maxPokemonListings)
                b.writeInt(p.maxItemListings)
                b.writeInt(p.listingDays)
                b.writeInt(p.pendingDays)
                b.writeDouble(p.auctionFee)
                b.writeInt(p.auctionMinBid)
                b.writeInt(p.antiSnipe)
                b.writeInt(p.maxAuctions)
                b.writeDouble(p.buyOrderFee)
                b.writeInt(p.buyOrderExpiry)
                b.writeInt(p.maxBuyOrders)
                b.writeBoolean(p.eggTrading)
                b.writeBoolean(p.celebration)
                b.writeString(p.auctionDurations)
                b.writeBoolean(p.financeEnabled)
                b.writeBoolean(p.cashLoanEnabled)
                b.writeBoolean(p.consumerLoanEnabled)
                b.writeString(p.loanPlans)
                b.writeDouble(p.creditRecent30)
                b.writeDouble(p.creditHistory)
                b.writeDouble(p.creditDebt)
                b.writeLong(p.creditMin)
                b.writeLong(p.creditMax)
                b.writeLong(p.autoRepayMinBalance)
                b.writeInt(p.overdueFeeDouble)
                b.writeInt(p.overdueFreeze)
                b.writeInt(p.overdueBadDebt)
            },
            // 读端用具名参数，读写字段顺序必须一致
            { b -> SaveServerConfigPayload(
                pokemonFee = b.readDouble(),
                itemFee = b.readDouble(),
                maxPokemonListings = b.readInt(),
                maxItemListings = b.readInt(),
                listingDays = b.readInt(),
                pendingDays = b.readInt(),
                auctionFee = b.readDouble(),
                auctionMinBid = b.readInt(),
                antiSnipe = b.readInt(),
                maxAuctions = b.readInt(),
                buyOrderFee = b.readDouble(),
                buyOrderExpiry = b.readInt(),
                maxBuyOrders = b.readInt(),
                eggTrading = b.readBoolean(),
                celebration = b.readBoolean(),
                auctionDurations = b.readString(),
                financeEnabled = b.readBoolean(),
                cashLoanEnabled = b.readBoolean(),
                consumerLoanEnabled = b.readBoolean(),
                loanPlans = b.readString(),
                creditRecent30 = b.readDouble(),
                creditHistory = b.readDouble(),
                creditDebt = b.readDouble(),
                creditMin = b.readLong(),
                creditMax = b.readLong(),
                autoRepayMinBalance = b.readLong(),
                overdueFeeDouble = b.readInt(),
                overdueFreeze = b.readInt(),
                overdueBadDebt = b.readInt(),
            ) }
        )
    }
}

// ── 注册与处理（照 MarketNetwork 主线程读写约定） ──

object ServerConfigNetwork {

    fun register() {
        // 服务端 S2C 类型注册：neoforge 协商要求双端集合一致，缺了会直接断连
        registerS2CType(ServerConfigDataPayload.ID, ServerConfigDataPayload.CODEC)

        registerC2S(RequestServerConfigPayload.ID, RequestServerConfigPayload.CODEC) { _, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute { sendToPlayer(player, snapshot()) }
        }

        registerC2S(SaveServerConfigPayload.ID, SaveServerConfigPayload.CODEC) { payload, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                applyAll(payload)
                CobbleMarketConfig.save()
                sendToPlayer(player, snapshot())
            }
        }
    }

    /** 当前配置快照（S2C 回发用） */
    fun snapshot(): ServerConfigDataPayload = ServerConfigDataPayload(
        pokemonFee = CobbleMarketConfig.pokemonListingFeePercent,
        itemFee = CobbleMarketConfig.itemListingFeePercent,
        maxPokemonListings = CobbleMarketConfig.maxPokemonListingsPerPlayer,
        maxItemListings = CobbleMarketConfig.maxItemListingsPerPlayer,
        listingDays = CobbleMarketConfig.listingDurationDays,
        pendingDays = CobbleMarketConfig.pendingReturnRetentionDays,
        auctionFee = CobbleMarketConfig.auctionFeePercent,
        auctionMinBid = CobbleMarketConfig.auctionMinBidIncrement,
        antiSnipe = CobbleMarketConfig.auctionAntiSnipeSeconds,
        maxAuctions = CobbleMarketConfig.maxAuctionsPerPlayer,
        buyOrderFee = CobbleMarketConfig.buyOrderFeePercent,
        buyOrderExpiry = CobbleMarketConfig.buyOrderExpiryDays,
        maxBuyOrders = CobbleMarketConfig.maxBuyOrdersPerPlayer,
        eggTrading = CobbleMarketConfig.eggTradingEnabled,
        celebration = CobbleMarketConfig.celebrationAnimationEnabled,
        auctionDurations = CobbleMarketConfig.auctionDurationOptions.joinToString(","),
        financeEnabled = CobbleMarketConfig.financeEnabled,
        cashLoanEnabled = CobbleMarketConfig.cashLoanEnabled,
        consumerLoanEnabled = CobbleMarketConfig.consumerLoanEnabled,
        loanPlans = CobbleMarketConfig.loanPlansText(),
        creditRecent30 = CobbleMarketConfig.creditLimitRecent30Weight,
        creditHistory = CobbleMarketConfig.creditLimitHistoryWeight,
        creditDebt = CobbleMarketConfig.creditLimitDebtWeight,
        creditMin = CobbleMarketConfig.creditLimitMin,
        creditMax = CobbleMarketConfig.creditLimitMax,
        autoRepayMinBalance = CobbleMarketConfig.autoRepayMinBalance,
        overdueFeeDouble = CobbleMarketConfig.overdueFeeDoubleDays,
        overdueFreeze = CobbleMarketConfig.overdueFreezeDays,
        overdueBadDebt = CobbleMarketConfig.overdueBadDebtDays,
    )

    /** 批量应用（钳制规则与各 setter 一致）；eggTrading 走既有 setter（含 save，重复落盘无害） */
    private fun applyAll(p: SaveServerConfigPayload) {
        CobbleMarketConfig.setPokemonListingFeePercent(p.pokemonFee)
        CobbleMarketConfig.setItemListingFeePercent(p.itemFee)
        CobbleMarketConfig.setMaxPokemonListingsPerPlayer(p.maxPokemonListings)
        CobbleMarketConfig.setMaxItemListingsPerPlayer(p.maxItemListings)
        CobbleMarketConfig.setListingDurationDays(p.listingDays)
        CobbleMarketConfig.setPendingReturnRetentionDays(p.pendingDays)
        CobbleMarketConfig.setAuctionFeePercent(p.auctionFee)
        CobbleMarketConfig.setAuctionMinBidIncrement(p.auctionMinBid)
        CobbleMarketConfig.setAuctionAntiSnipeSeconds(p.antiSnipe)
        CobbleMarketConfig.setMaxAuctionsPerPlayer(p.maxAuctions)
        CobbleMarketConfig.setBuyOrderFeePercent(p.buyOrderFee)
        CobbleMarketConfig.setBuyOrderExpiryDays(p.buyOrderExpiry)
        CobbleMarketConfig.setMaxBuyOrdersPerPlayer(p.maxBuyOrders)
        CobbleMarketConfig.setEggTradingEnabled(p.eggTrading)
        CobbleMarketConfig.setCelebrationAnimationEnabled(p.celebration)
        CobbleMarketConfig.setAuctionDurationOptions(p.auctionDurations)
        CobbleMarketConfig.setFinanceEnabled(p.financeEnabled)
        CobbleMarketConfig.setCashLoanEnabled(p.cashLoanEnabled)
        CobbleMarketConfig.setConsumerLoanEnabled(p.consumerLoanEnabled)
        CobbleMarketConfig.setLoanPlansText(p.loanPlans)
        CobbleMarketConfig.setCreditLimitRecent30Weight(p.creditRecent30)
        CobbleMarketConfig.setCreditLimitHistoryWeight(p.creditHistory)
        CobbleMarketConfig.setCreditLimitDebtWeight(p.creditDebt)
        CobbleMarketConfig.setCreditLimitMin(p.creditMin)
        CobbleMarketConfig.setCreditLimitMax(p.creditMax)
        CobbleMarketConfig.setAutoRepayMinBalance(p.autoRepayMinBalance)
        CobbleMarketConfig.setOverdueFeeDoubleDays(p.overdueFeeDouble)
        CobbleMarketConfig.setOverdueFreezeDays(p.overdueFreeze)
        CobbleMarketConfig.setOverdueBadDebtDays(p.overdueBadDebt)
    }
}
