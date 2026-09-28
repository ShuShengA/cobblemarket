package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.util.RequestThrottle
import com.shusheng.cobblemarket.config.ShopTier
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.PayQrImage
import com.shusheng.cobblemarket.currency.PointCodeState
import com.shusheng.cobblemarket.currency.PointCodes
import com.shusheng.cobblemarket.currency.PointOrderNumbers
import com.shusheng.cobblemarket.currency.PointsAuditLogger
import com.shusheng.cobblemarket.currency.PointsLogType
import com.shusheng.cobblemarket.currency.PointOrderState
import com.shusheng.cobblemarket.currency.PointRecordState
import com.shusheng.cobblemarket.currency.PointRedeemService
import com.shusheng.cobblemarket.currency.PointShopResult
import com.shusheng.cobblemarket.currency.PointShopService
import com.shusheng.cobblemarket.currency.RedeemOutcome
import com.shusheng.cobblemarket.currency.TierIconImage
import com.shusheng.cobblemarket.currency.pointShopErrorText
import com.shusheng.cobblemarket.platform.onPlayerJoin
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import com.shusheng.cobblemarket.util.PersistHelper
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import java.io.File

/**
 * 兑换码输入上限：外部码 32 位按 4 位分组后是 39 字符（32 + 7 个连字符），这里留一倍余量。
 * **超长直接在协议层拒绝**（读串越界会抛异常）——不给伪造客户端塞超长串做无谓解析的机会。
 */
private const val MAX_CODE_LENGTH = 64

/**
 * RP 总开关（S2C）：登录时下发一次，客户端据此决定「RP 商城」入口按钮是否置灰。
 * ⚠ 与市场总开关（[MarketStatePayload]）的区别：那个是**紧急停市**，按钮仍有用（看列表/取回资产）；
 * RP 总开关管的是「这个服不用RP」—— 界面进不去也没损失，所以按钮照喵喵银行的做法**置灰**。
 */
data class PointsStatusPayload(
    val enabled: Boolean,
    /**
     * **玩家间 RP 交易开关**（`pointsMarketEnabled`，默认关）。
     * 客户端据此决定上架界面的货币切换按钮显不显示 —— 关着时玩家只能用主货币定价。
     * 服务端上架请求里也会再拦一道（这里只是不给玩家白点）。
     */
    val marketEnabled: Boolean = false
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PointsStatusPayload>(CobbleMarket.id("points_status"))
        val CODEC: PacketCodec<PacketByteBuf, PointsStatusPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.enabled); b.writeBoolean(p.marketEnabled) },
            { b -> PointsStatusPayload(b.readBoolean(), b.readBoolean()) }
        )
    }
}

/** 玩家在RP 商城界面里兑换（命令 `/market points redeem` 是同一个内核的另一入口） */
data class RedeemPointCodePayload(val code: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RedeemPointCodePayload>(CobbleMarket.id("redeem_point_code"))
        val CODEC: PacketCodec<PacketByteBuf, RedeemPointCodePayload> = PacketCodec.of(
            { p, b -> b.writeString(p.code, MAX_CODE_LENGTH) },
            { b -> RedeemPointCodePayload(b.readString(MAX_CODE_LENGTH)) }
        )
    }
}

// ── 充值（游戏内充值档位 → 收款码 → 我已支付）──

/** 充值档位（下发给客户端绘制）。金额单位是**分**，与配置、订单账本同一口径 */
data class ShopTierInfo(
    val name: String,
    val priceCents: Long,
    val points: Long,
    /** 该档「RP 券」图文件名（空 = 用客户端内置券图，那种情况**不走网络**） */
    val iconFile: String = ""
)

/** 一笔订单的展示信息（状态机在服务端，这里只是给界面看的快照）—— 玩家侧与管理端共用 */
data class PointOrderInfo(
    val orderNo: String,
    val tierName: String,
    val payCents: Long,
    val points: Long,
    /** [PointOrderStatus] 的名字 */
    val status: String,
    /** 待支付超时时刻（epoch ms）；界面据此画倒计时 */
    val expireAt: Long,
    /** 下单玩家名（**管理端要显示是谁付的**；玩家自己那份快照用不到，但仍然带上） */
    val playerName: String = "",
    /** 玩家点「我已支付」的时刻；未支付时 = 0（管理端按它算「已等待」） */
    val paidAt: Long = 0L,
    /** 下单时刻（没有 [paidAt] 时按它算「已等待」） */
    val createdAt: Long = 0L,
    /** 作废原因（[PointOrderVoidReason] 的名字；**空串 = 没作废**）—— 玩家侧据此说清是取消的、超时的还是被作废的 */
    val voidReason: String = ""
)

/**
 * 点券商城信息（S2C）：档位列表 + 有没有收款码 + 玩家当前那笔未完结订单。
 *
 * 下发时机：① 进充值界面时请求（[RequestPointsShopPayload]）；② 下单 / 我已支付 / 取消之后
 * （客户端据此刷新，不必再单独请求）。
 */
data class PointsShopInfoPayload(
    val tiers: List<ShopTierInfo>,
    val hasQr: Boolean,
    /** 玩家**全部**未完结订单（按创建时间升序，可为空）—— 允许多笔，上限见 PointShopService */
    val orders: List<PointOrderInfo>,
    /** 档位金额的货币单位（空 = 不显示单位），服主可在配置界面自定义 */
    val currencyLabel: String
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PointsShopInfoPayload>(CobbleMarket.id("points_shop_info"))

        val CODEC: PacketCodec<PacketByteBuf, PointsShopInfoPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.tiers.size)
                p.tiers.forEach { t ->
                    b.writeString(t.name, MAX_TIER_NAME_LENGTH); b.writeLong(t.priceCents); b.writeLong(t.points)
                    b.writeString(t.iconFile, MAX_FILE_NAME_LENGTH)
                }
                b.writeBoolean(p.hasQr)
                b.writeString(p.currencyLabel, MAX_TIER_NAME_LENGTH)
                b.writeInt(p.orders.size)
                p.orders.forEach { writeOrder(b, it) }
            },
            { b ->
                val count = b.readInt()
                val tiers = ArrayList<ShopTierInfo>(count)
                repeat(count) {
                    tiers.add(
                        // ⚠ 读端一律用具名参数：字段顺序一旦对不上，具名能立刻看出来（payload 顺序铁律）
                        ShopTierInfo(
                            name = b.readString(MAX_TIER_NAME_LENGTH),
                            priceCents = b.readLong(),
                            points = b.readLong(),
                            iconFile = b.readString(MAX_FILE_NAME_LENGTH)
                        )
                    )
                }
                val hasQr = b.readBoolean()
                val currencyLabel = b.readString(MAX_TIER_NAME_LENGTH)
                val orderCount = b.readInt()
                val orders = ArrayList<PointOrderInfo>(orderCount)
                repeat(orderCount) { orders.add(readOrder(b)) }
                PointsShopInfoPayload(tiers = tiers, hasQr = hasQr, orders = orders, currencyLabel = currencyLabel)
            }
        )
    }
}

/**
 * 收款码图片（S2C）：服务端读到的字节**原样下发**，客户端解码成动态纹理。
 *
 * ⚠ **空数组 = 读不出来**（文件不存在 / 不是 PNG / 太大）：客户端据此显示「收款码不可用」，
 * 而不是一直停在「加载中…」。用空数组而不是另发一个失败包，是为了让弹窗状态只有一个来源。
 *
 * ⚠ 用普通 class 而不是 data class：byte[] 的 equals/hashCode 是引用比较，data class 会误导。
 */
class PayQrImagePayload(val bytes: ByteArray) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PayQrImagePayload>(CobbleMarket.id("pay_qr_image"))
        val CODEC: PacketCodec<PacketByteBuf, PayQrImagePayload> = PacketCodec.of(
            { p, b -> b.writeByteArray(p.bytes) },
            { b -> PayQrImagePayload(b.readByteArray()) }
        )
    }
}

/** 进充值界面时请求一次（档位 + 当前订单 + 有没有收款码） */
data class RequestPointsShopPayload(val dummy: Boolean) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestPointsShopPayload>(CobbleMarket.id("request_points_shop"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPointsShopPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.dummy) },
            { b -> RequestPointsShopPayload(b.readBoolean()) }
        )
    }
}

/**
 * 收款码图片按需请求。**带订单号**：每个档位可以用自己的收款码（带金额的固定码），
 * 服务端按**订单上下单时快照的文件名**取图 —— 服主事后换码/改档位也不会指错。
 */
data class RequestPayQrPayload(val orderNo: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestPayQrPayload>(CobbleMarket.id("request_pay_qr"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPayQrPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.orderNo, MAX_ORDER_NO_LENGTH) },
            { b -> RequestPayQrPayload(b.readString(MAX_ORDER_NO_LENGTH)) }
        )
    }
}

/**
 * 请求某个档位的「RP 券」图（C2S）—— 客户端本地没缓存时才发。
 *
 * 与收款码是**两条独立的路**：收款码按**订单**请求（下单后才要，每单可能一张），
 * 券图按**文件名**请求（进充值界面就要，多个档位可能共用同一张图）。
 * 传文件名而不是档位序号：客户端按文件名缓存，两个档位配同一张图只传一次。
 */
data class RequestTierIconPayload(val fileName: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestTierIconPayload>(CobbleMarket.id("request_tier_icon"))
        val CODEC: PacketCodec<PacketByteBuf, RequestTierIconPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.fileName, MAX_FILE_NAME_LENGTH) },
            { b -> RequestTierIconPayload(b.readString(MAX_FILE_NAME_LENGTH)) }
        )
    }
}

/**
 * 档位券图字节（S2C）。
 *
 * ⚠ **空数组 = 读不出来**（文件不存在 / 不是 PNG / 太大）：客户端据此**回退到内置券图**，
 * 而不是留一张空白卡片 —— 与收款码「空包 = 不可用」同一个约定。
 *
 * ⚠ 用普通 class 而非 data class：byte[] 的 equals/hashCode 是引用比较，data class 会误导。
 */
class TierIconPayload(val fileName: String, val bytes: ByteArray) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<TierIconPayload>(CobbleMarket.id("tier_icon"))
        val CODEC: PacketCodec<PacketByteBuf, TierIconPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.fileName, MAX_FILE_NAME_LENGTH)
                b.writeByteArray(p.bytes)
            },
            { b -> TierIconPayload(b.readString(MAX_FILE_NAME_LENGTH), b.readByteArray()) }
        )
    }
}

/** 下单。档位序号从 0 开始；币种不来自客户端（订单只可能是 RP，没有可伪造的字段） */
data class CreatePointOrderPayload(val tierIndex: Int) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<CreatePointOrderPayload>(CobbleMarket.id("create_point_order"))
        val CODEC: PacketCodec<PacketByteBuf, CreatePointOrderPayload> = PacketCodec.of(
            { p, b -> b.writeInt(p.tierIndex) },
            { b -> CreatePointOrderPayload(b.readInt()) }
        )
    }
}

/** 玩家声明「我已支付」（**只是声明不是证据**，发不发由服主核对账单后决定） */
data class MarkPointOrderPaidPayload(val orderNo: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<MarkPointOrderPaidPayload>(CobbleMarket.id("mark_point_order_paid"))
        val CODEC: PacketCodec<PacketByteBuf, MarkPointOrderPaidPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.orderNo, MAX_ORDER_NO_LENGTH) },
            { b -> MarkPointOrderPaidPayload(b.readString(MAX_ORDER_NO_LENGTH)) }
        )
    }
}

/** 取消自己的待支付订单 */
data class CancelPointOrderPayload(val orderNo: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<CancelPointOrderPayload>(CobbleMarket.id("cancel_point_order"))
        val CODEC: PacketCodec<PacketByteBuf, CancelPointOrderPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.orderNo, MAX_ORDER_NO_LENGTH) },
            { b -> CancelPointOrderPayload(b.readString(MAX_ORDER_NO_LENGTH)) }
        )
    }
}

// ── 管理端：订单列表 / 确认发放 / 作废（**服务端判 POINTS_ORDER 节点**，与命令侧同一套内核）──

/**
 * 请求订单列表（C2S，管理端）。
 *
 * - [includeFinished] = false 只取**待确认**的（服主要处理的）
 * - true 取**已完结**的（已发放 + 已作废）—— 方案要求已完结订单保留 90 天供逐笔对账
 * - [query] 非空时按订单号过滤：玩家申诉时报的就是订单号
 */
data class RequestPointOrdersPayload(val includeFinished: Boolean, val query: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestPointOrdersPayload>(CobbleMarket.id("request_point_orders"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPointOrdersPayload> = PacketCodec.of(
            { p, b ->
                b.writeBoolean(p.includeFinished)
                b.writeString(p.query, MAX_CODE_LENGTH)
            },
            { b -> RequestPointOrdersPayload(b.readBoolean(), b.readString(MAX_CODE_LENGTH)) }
        )
    }
}

/** 订单列表（S2C，管理端）。[includeFinished] 原样回显，界面据此确认收到的是自己等的那份 */
data class PointOrdersPayload(
    val orders: List<PointOrderInfo>,
    val includeFinished: Boolean
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PointOrdersPayload>(CobbleMarket.id("point_orders"))
        val CODEC: PacketCodec<PacketByteBuf, PointOrdersPayload> = PacketCodec.of(
            { p, b ->
                b.writeBoolean(p.includeFinished)
                b.writeInt(p.orders.size)
                p.orders.forEach { writeOrder(b, it) }
            },
            { b ->
                val includeFinished = b.readBoolean()
                val count = b.readInt()
                val orders = ArrayList<PointOrderInfo>(count)
                repeat(count) { orders.add(readOrder(b)) }
                PointOrdersPayload(orders = orders, includeFinished = includeFinished)
            }
        )
    }
}

/**
 * 确认发放（C2S，管理端）。**服务端幂等**：只有「待确认」能发放 ——
 * 服主重复点、界面重发、崩溃后重开列表再点，都不会二次加余额。
 */
data class ConfirmPointOrderPayload(val orderNo: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<ConfirmPointOrderPayload>(CobbleMarket.id("confirm_point_order"))
        val CODEC: PacketCodec<PacketByteBuf, ConfirmPointOrderPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.orderNo, MAX_ORDER_NO_LENGTH) },
            { b -> ConfirmPointOrderPayload(b.readString(MAX_ORDER_NO_LENGTH)) }
        )
    }
}

/** 作废订单（C2S，管理端）。已发放的**不能**走这里 —— 那要用 `points take` 单独回收并留审计 */
data class RejectPointOrderPayload(val orderNo: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RejectPointOrderPayload>(CobbleMarket.id("reject_point_order"))
        val CODEC: PacketCodec<PacketByteBuf, RejectPointOrderPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.orderNo, MAX_ORDER_NO_LENGTH) },
            { b -> RejectPointOrderPayload(b.readString(MAX_ORDER_NO_LENGTH)) }
        )
    }
}

/** 订单号长度上限（6 位 + 分隔符，留足余量）；超长在协议层直接拒绝 */
private const val MAX_ORDER_NO_LENGTH = 16

/** 订单**状态 / 作废原因**这类枚举名的长度上限（都是服务端自己写的枚举名，防伪造客户端塞超长串） */
private const val MAX_STATUS_LENGTH = 32

/**
 * 「我的订单」一次最多回多少笔。已完结订单按方案保留 90 天，老玩家可能有几百条 ——
 * 全发会把包撑爆、界面也放不下，取最近这些条足够（真要查更早的，服主侧有完整账本）。
 */
private const val MAX_ORDER_HISTORY = 50

/**
 * 写/读一笔订单。**顶层函数而非 companion 内的私有函数**：玩家那份快照（[PointsShopInfoPayload]）
 * 与管理端列表（[PointOrdersPayload]）共用，两份各写一遍迟早漂移（payload 顺序铁律）。
 */
private fun writeOrder(b: PacketByteBuf, o: PointOrderInfo) {
    b.writeString(o.orderNo, MAX_ORDER_NO_LENGTH)
    b.writeString(o.tierName, MAX_TIER_NAME_LENGTH)
    b.writeLong(o.payCents)
    b.writeLong(o.points)
    b.writeString(o.status, MAX_STATUS_LENGTH)
    b.writeLong(o.expireAt)
    b.writeString(o.playerName, MAX_TIER_NAME_LENGTH)
    b.writeLong(o.paidAt)
    b.writeLong(o.createdAt)
    b.writeString(o.voidReason, MAX_STATUS_LENGTH)
}

private fun readOrder(b: PacketByteBuf) = PointOrderInfo(
    // ⚠ 读端一律用具名参数：字段顺序一旦对不上，具名能立刻看出来（payload 顺序铁律）
    orderNo = b.readString(MAX_ORDER_NO_LENGTH),
    tierName = b.readString(MAX_TIER_NAME_LENGTH),
    payCents = b.readLong(),
    points = b.readLong(),
    status = b.readString(MAX_STATUS_LENGTH),
    expireAt = b.readLong(),
    playerName = b.readString(MAX_TIER_NAME_LENGTH),
    paidAt = b.readLong(),
    createdAt = b.readLong(),
    voidReason = b.readString(MAX_STATUS_LENGTH)
)

// ── RP 配置（**仅服主**；服务器配置界面里的「RP 商城」入口）──

/** 一个档位的可编辑形态（配置界面用；金额是**分**，与账本同口径） */
data class PointsTierConfig(
    val name: String,
    val priceCents: Long,
    val points: Long,
    /** 该档专用收款码文件名（留空 = 走全局收款码） */
    val qr: String,
    /** 该档「RP 券」图文件名（留空 = 用内置券图） */
    val icon: String = ""
)

/** 进配置界面时请求一次当前值（**仅服主**会被响应） */
data class RequestPointsConfigPayload(val dummy: Boolean) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestPointsConfigPayload>(CobbleMarket.id("request_points_config"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPointsConfigPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.dummy) },
            { b -> RequestPointsConfigPayload(b.readBoolean()) }
        )
    }
}

/** 当前 RP 配置快照（S2C，仅发给 OP） */
data class PointsConfigPayload(
    val pointsEnabled: Boolean,
    val shopEnabled: Boolean,
    val payQrImage: String,
    /** 档位券图的**全局默认**文件名（空 = 用内置券图） */
    val defaultIcon: String,
    val orderTimeoutMinutes: Long,
    val tiers: List<PointsTierConfig>,
    /** 档位金额的货币单位（空 = 不显示） */
    val currencyLabel: String,
    /**
     * **玩家间 RP 交易开关**（默认关）。下发那份让配置界面显示当前状态；
     * 保存那份让服主把它改掉（见 `CobbleMarketConfig.pointsMarketEnabled`）。
     */
    val pointsMarketEnabled: Boolean = false,
    /**
     * **「RP 当任务货币」开关**（默认关）：打开后**玩家身份的 OP（等级 ≥ 2）也能发放 / 回收 RP**
     * —— FTB 之类的任务模组要把 RP 当奖励发给玩家（见 `CobbleMarketConfig.rpAsQuestCurrency`）。
     */
    val rpAsQuestCurrency: Boolean = false,
    /**
     * RP 的四个费率（%，精灵上架 / 物品上架 / 拍卖成交 / 求购中介）。
     * 配置界面的「费率配置」二级弹窗用它填初值；「开玩家间交易」的提醒弹窗看这四个值是否全为 0。
     */
    val pointsPokemonFee: Double = 0.0,
    val pointsItemFee: Double = 0.0,
    val pointsAuctionFee: Double = 0.0,
    val pointsBuyOrderFee: Double = 0.0
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PointsConfigPayload>(CobbleMarket.id("points_config"))
        val CODEC: PacketCodec<PacketByteBuf, PointsConfigPayload> = PacketCodec.of(
            { p, b ->
                b.writeBoolean(p.pointsEnabled)
                b.writeBoolean(p.shopEnabled)
                b.writeString(p.payQrImage, MAX_FILE_NAME_LENGTH)
                b.writeString(p.defaultIcon, MAX_FILE_NAME_LENGTH)
                b.writeLong(p.orderTimeoutMinutes)
                b.writeString(p.currencyLabel, MAX_TIER_NAME_LENGTH)
                b.writeBoolean(p.pointsMarketEnabled)
                b.writeBoolean(p.rpAsQuestCurrency)
                b.writeDouble(p.pointsPokemonFee)
                b.writeDouble(p.pointsItemFee)
                b.writeDouble(p.pointsAuctionFee)
                b.writeDouble(p.pointsBuyOrderFee)
                b.writeInt(p.tiers.size)
                p.tiers.forEach { t ->
                    b.writeString(t.name, MAX_TIER_NAME_LENGTH)
                    b.writeLong(t.priceCents)
                    b.writeLong(t.points)
                    b.writeString(t.qr, MAX_FILE_NAME_LENGTH)
                    b.writeString(t.icon, MAX_FILE_NAME_LENGTH)
                }
            },
            { b ->
                val pointsEnabled = b.readBoolean()
                val shopEnabled = b.readBoolean()
                val payQrImage = b.readString(MAX_FILE_NAME_LENGTH)
                val defaultIcon = b.readString(MAX_FILE_NAME_LENGTH)
                val timeout = b.readLong()
                val currencyLabel = b.readString(MAX_TIER_NAME_LENGTH)
                val pointsMarketEnabled = b.readBoolean()
                val rpAsQuestCurrency = b.readBoolean()
                val pointsPokemonFee = b.readDouble()
                val pointsItemFee = b.readDouble()
                val pointsAuctionFee = b.readDouble()
                val pointsBuyOrderFee = b.readDouble()
                val count = b.readInt()
                val tiers = ArrayList<PointsTierConfig>(count)
                repeat(count) {
                    tiers.add(
                        PointsTierConfig(
                            name = b.readString(MAX_TIER_NAME_LENGTH),
                            priceCents = b.readLong(),
                            points = b.readLong(),
                            qr = b.readString(MAX_FILE_NAME_LENGTH),
                            icon = b.readString(MAX_FILE_NAME_LENGTH)
                        )
                    )
                }
                // ⚠ 具名参数：位置参数在字段增删时会静默错位（加 defaultIcon 时就撞到过一次）
                PointsConfigPayload(
                    pointsEnabled = pointsEnabled,
                    shopEnabled = shopEnabled,
                    payQrImage = payQrImage,
                    defaultIcon = defaultIcon,
                    orderTimeoutMinutes = timeout,
                    tiers = tiers,
                    currencyLabel = currencyLabel,
                    pointsMarketEnabled = pointsMarketEnabled,
                    rpAsQuestCurrency = rpAsQuestCurrency,
                    pointsPokemonFee = pointsPokemonFee,
                    pointsItemFee = pointsItemFee,
                    pointsAuctionFee = pointsAuctionFee,
                    pointsBuyOrderFee = pointsBuyOrderFee
                )
            }
        )
    }
}

/**
 * 保存 RP 配置（C2S）。
 *
 * ⚠ 服务端**只认服主**（`isServerOwner`）：配置里装着**外部签名码密钥**，拿到它就能凭空签发兑换码 ——
 * 界面上这个入口确实只在「服务器配置」里、只有服主进得去，**但那只挡得住 UI**：
 * 原先判 OP 时，任何 OP 直接发包就能改（2026-09-25 修正）。
 */
data class SavePointsConfigPayload(
    val pointsEnabled: Boolean,
    val shopEnabled: Boolean,
    val payQrImage: String,
    /** 档位券图的**全局默认**文件名（空 = 用内置券图） */
    val defaultIcon: String,
    val orderTimeoutMinutes: Long,
    val tiers: List<PointsTierConfig>,
    /** 档位金额的货币单位（空 = 不显示） */
    val currencyLabel: String,
    /**
     * **玩家间 RP 交易开关**（默认关）。下发那份让配置界面显示当前状态；
     * 保存那份让服主把它改掉（见 `CobbleMarketConfig.pointsMarketEnabled`）。
     */
    val pointsMarketEnabled: Boolean = false,
    /**
     * **「RP 当任务货币」开关**（默认关）：打开后**玩家身份的 OP（等级 ≥ 2）也能发放 / 回收 RP**
     * —— FTB 之类的任务模组要把 RP 当奖励发给玩家（见 `CobbleMarketConfig.rpAsQuestCurrency`）。
     */
    val rpAsQuestCurrency: Boolean = false,
    /**
     * RP 的四个费率（%，精灵上架 / 物品上架 / 拍卖成交 / 求购中介）。
     * 配置界面的「费率配置」二级弹窗用它填初值；「开玩家间交易」的提醒弹窗看这四个值是否全为 0。
     */
    val pointsPokemonFee: Double = 0.0,
    val pointsItemFee: Double = 0.0,
    val pointsAuctionFee: Double = 0.0,
    val pointsBuyOrderFee: Double = 0.0
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<SavePointsConfigPayload>(CobbleMarket.id("save_points_config"))
        val CODEC: PacketCodec<PacketByteBuf, SavePointsConfigPayload> = PacketCodec.of(
            { p, b ->
                b.writeBoolean(p.pointsEnabled)
                b.writeBoolean(p.shopEnabled)
                b.writeString(p.payQrImage, MAX_FILE_NAME_LENGTH)
                b.writeString(p.defaultIcon, MAX_FILE_NAME_LENGTH)
                b.writeLong(p.orderTimeoutMinutes)
                b.writeString(p.currencyLabel, MAX_TIER_NAME_LENGTH)
                b.writeBoolean(p.pointsMarketEnabled)
                b.writeBoolean(p.rpAsQuestCurrency)
                b.writeDouble(p.pointsPokemonFee)
                b.writeDouble(p.pointsItemFee)
                b.writeDouble(p.pointsAuctionFee)
                b.writeDouble(p.pointsBuyOrderFee)
                b.writeInt(p.tiers.size)
                p.tiers.forEach { t ->
                    b.writeString(t.name, MAX_TIER_NAME_LENGTH)
                    b.writeLong(t.priceCents)
                    b.writeLong(t.points)
                    b.writeString(t.qr, MAX_FILE_NAME_LENGTH)
                    b.writeString(t.icon, MAX_FILE_NAME_LENGTH)
                }
            },
            { b ->
                val pointsEnabled = b.readBoolean()
                val shopEnabled = b.readBoolean()
                val payQrImage = b.readString(MAX_FILE_NAME_LENGTH)
                val defaultIcon = b.readString(MAX_FILE_NAME_LENGTH)
                val timeout = b.readLong()
                val currencyLabel = b.readString(MAX_TIER_NAME_LENGTH)
                val pointsMarketEnabled = b.readBoolean()
                val rpAsQuestCurrency = b.readBoolean()
                val pointsPokemonFee = b.readDouble()
                val pointsItemFee = b.readDouble()
                val pointsAuctionFee = b.readDouble()
                val pointsBuyOrderFee = b.readDouble()
                val count = b.readInt()
                val tiers = ArrayList<PointsTierConfig>(count)
                repeat(count) {
                    tiers.add(
                        PointsTierConfig(
                            name = b.readString(MAX_TIER_NAME_LENGTH),
                            priceCents = b.readLong(),
                            points = b.readLong(),
                            qr = b.readString(MAX_FILE_NAME_LENGTH),
                            icon = b.readString(MAX_FILE_NAME_LENGTH)
                        )
                    )
                }
                // ⚠ 具名参数（同上）
                SavePointsConfigPayload(
                    pointsEnabled = pointsEnabled,
                    shopEnabled = shopEnabled,
                    payQrImage = payQrImage,
                    defaultIcon = defaultIcon,
                    orderTimeoutMinutes = timeout,
                    tiers = tiers,
                    currencyLabel = currencyLabel,
                    pointsMarketEnabled = pointsMarketEnabled,
                    rpAsQuestCurrency = rpAsQuestCurrency,
                    pointsPokemonFee = pointsPokemonFee,
                    pointsItemFee = pointsItemFee,
                    pointsAuctionFee = pointsAuctionFee,
                    pointsBuyOrderFee = pointsBuyOrderFee
                )
            }
        )
    }
}

/** 档位名长度上限（防止伪造客户端塞超长串撑爆配置） */
private const val MAX_TIER_NAME_LENGTH = 32

/** 文件名长度上限 */
private const val MAX_FILE_NAME_LENGTH = 128

// ── C2S：兑换码管理（生成 / 列表 / 作废）—— 与 `/market points code …` 命令同一批内核 ──

/** 生成请求：字段与命令 `code create <amount> <count> <uses> [days] [bound]` 一一对应 */
data class CreatePointCodesPayload(
    val amount: Long,
    val count: Int,
    val uses: Int,
    /** 有效期（天）；≤0 = 永久 */
    val days: Int,
    /** 空串 = 不绑定；填了则解析成玩家，解析不到即拒绝 */
    val boundName: String
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<CreatePointCodesPayload>(CobbleMarket.id("create_point_codes"))
        val CODEC: PacketCodec<PacketByteBuf, CreatePointCodesPayload> = PacketCodec.of(
            { p, b ->
                b.writeLong(p.amount); b.writeVarInt(p.count); b.writeVarInt(p.uses)
                b.writeVarInt(p.days); b.writeString(p.boundName)
            },
            { b ->
                CreatePointCodesPayload(
                    b.readLong(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readString()
                )
            }
        )
    }
}

/**
 * 生成结果。**≤10 张直接回显全部码，>10 张写文件只回路径** —— 方案定的规矩：
 * 码 = 钱，一大串糊在界面里既看不清、也容易被截图外传。
 */
data class PointCodesCreatedPayload(
    val success: Boolean,
    /** 直接回显的码（空 = 走文件） */
    val codes: List<String>,
    /** 写入的文件路径（空 = 直接回显） */
    val filePath: String,
    val message: Text
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PointCodesCreatedPayload>(CobbleMarket.id("point_codes_created"))
        val CODEC: PacketCodec<PacketByteBuf, PointCodesCreatedPayload> = PacketCodec.of(
            { p, b ->
                b.writeBoolean(p.success)
                b.writeVarInt(p.codes.size); p.codes.forEach { b.writeString(it) }
                b.writeString(p.filePath)
                net.minecraft.text.TextCodecs.PACKET_CODEC.encode(b, p.message)
            },
            { b ->
                PointCodesCreatedPayload(
                    b.readBoolean(),
                    (0 until b.readVarInt()).map { b.readString() },
                    b.readString(),
                    net.minecraft.text.TextCodecs.PACKET_CODEC.decode(b)
                )
            }
        )
    }
}

/**
 * 列表请求。
 *
 * ⚠ 列表**含已用完/已过期的码**（由界面标状态）——命令版 `code list` 只列可用的，
 * 界面需要更全：服主得看得见"哪些已经兑完了"，否则会以为码丢了。
 */
class RequestPointCodesPayload : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestPointCodesPayload>(CobbleMarket.id("request_point_codes"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPointCodesPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestPointCodesPayload() }
        )
    }
}

/**
 * 一条码（列表用）。
 *
 * ⚠ `code` 是**完整码**：行内只显示前缀防截图外泄，但每行旁边的**复制按钮要用完整码** ——
 * 所以完整值必须下发（管理员本来就有权限，且不发就没法复制）。
 */
data class PointCodeInfo(
    val code: String,
    val amount: Long,
    val remainingUses: Int,
    val totalUses: Int,
    /** 0 = 永久 */
    val expiresAt: Long,
    /** 空 = 不绑定 */
    val boundName: String,
    val createdBy: String,
    val createdAt: Long
) {
    fun write(buf: PacketByteBuf) {
        buf.writeString(code)
        buf.writeLong(amount)
        buf.writeVarInt(remainingUses)
        buf.writeVarInt(totalUses)
        buf.writeLong(expiresAt)
        buf.writeString(boundName)
        buf.writeString(createdBy)
        buf.writeLong(createdAt)
    }

    companion object {
        fun read(buf: PacketByteBuf) = PointCodeInfo(
            code = buf.readString(),
            amount = buf.readLong(),
            remainingUses = buf.readVarInt(),
            totalUses = buf.readVarInt(),
            expiresAt = buf.readLong(),
            boundName = buf.readString(),
            createdBy = buf.readString(),
            createdAt = buf.readLong()
        )
    }
}

data class PointCodesPayload(val entries: List<PointCodeInfo>) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PointCodesPayload>(CobbleMarket.id("point_codes"))
        val CODEC: PacketCodec<PacketByteBuf, PointCodesPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> PointCodesPayload((0 until b.readVarInt()).map { PointCodeInfo.read(b) }) }
        )
    }
}

/** 一键清理**已失效**的码（用完 / 过期；还能兑的一张都不动）。不可逆，客户端有二次确认 */
class PurgePointCodesPayload : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PurgePointCodesPayload>(CobbleMarket.id("purge_point_codes"))
        val CODEC: PacketCodec<PacketByteBuf, PurgePointCodesPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); PurgePointCodesPayload() }
        )
    }
}

/** 作废一张码（不可逆，客户端有二次确认） */
data class RevokePointCodePayload(val code: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RevokePointCodePayload>(CobbleMarket.id("revoke_point_code"))
        val CODEC: PacketCodec<PacketByteBuf, RevokePointCodePayload> = PacketCodec.of(
            { p, b -> b.writeString(p.code) },
            { b -> RevokePointCodePayload(b.readString()) }
        )
    }
}

// ── C2S：玩家侧「我的兑换记录」（只回自己的那几条）──

/**
 * 请求**自己的**兑换记录。
 *
 * ⚠ 与兑换码管理的 [RequestPointCodesPayload] 不同，这个**不设权限门槛** ——
 * 看的是自己的记录，任何玩家都能查；也查不到别人的（请求里没有任何参数，
 * 服务端按发包玩家的 uuid 取，想伪造也没地方填）。
 */
class RequestPointRecordsPayload : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestPointRecordsPayload>(CobbleMarket.id("request_point_records"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPointRecordsPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestPointRecordsPayload() }
        )
    }
}

/** 一条兑换记录（界面用）：面额 + 到账后余额 + 时间 */
data class PointRecordInfo(val amount: Long, val balanceAfter: Long, val at: Long) {
    fun write(buf: PacketByteBuf) {
        buf.writeLong(amount)
        buf.writeLong(balanceAfter)
        buf.writeLong(at)
    }

    companion object {
        fun read(buf: PacketByteBuf) =
            PointRecordInfo(amount = buf.readLong(), balanceAfter = buf.readLong(), at = buf.readLong())
    }
}

/** 兑换记录列表（**最新在前**，服务端已排好序） */
data class PointRecordsPayload(val entries: List<PointRecordInfo>) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PointRecordsPayload>(CobbleMarket.id("point_records"))
        val CODEC: PacketCodec<PacketByteBuf, PointRecordsPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> PointRecordsPayload((0 until b.readVarInt()).map { PointRecordInfo.read(b) }) }
        )
    }
}

/**
 * **RP 网络层**（GUI 侧）。
 *
 * 与命令侧的关系：两边**共用同一批内核**（兑换走 [PointRedeemService]，充值订单走
 * `PointShopService`），网络层只负责「收包 → 主线程 → 回包」这一层壳，
 * 绝不各自实现一套发放逻辑（方案风险清单第 12 条）。
 */
object PointsNetwork {

    fun register() {
        registerC2S(RedeemPointCodePayload.ID, RedeemPointCodePayload.CODEC) { payload, player ->
            val server = player.server
            // 整段在主线程：「先扣次数再加余额」不可被打断（见 PointRedeemService）
            server.execute {
                // ⚠ 这里**不查 pointsEnabled**：总开关管的是「玩家之间用RP 交易」，
                // 而兑换是发放路径 —— 命令侧 `/market points redeem` 同样不查，两边保持一致
                when (val outcome = PointRedeemService.redeem(server, player, payload.code)) {
                    is RedeemOutcome.Success -> sendToPlayer(
                        player,
                        MarketResultPayload(
                            true,
                            Text.translatable(
                                "cobblemarket.points.redeem_ok",
                                CurrencyService.amountText(outcome.amount, MarketCurrency.POINTS),
                                CurrencyService.amountText(outcome.balanceAfter, MarketCurrency.POINTS)
                            )
                        )
                    )

                    RedeemOutcome.Locked -> sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.points.redeem_locked"))
                    )

                    RedeemOutcome.Invalid -> sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.points.redeem_invalid"))
                    )

                    // 节流：静默丢弃（与命令侧一致，不值得回一条消息）
                    RedeemOutcome.Throttled -> {}
                }
            }
        }

        registerS2CType(PointsStatusPayload.ID, PointsStatusPayload.CODEC)
        // 兑换码管理（生成结果 / 列表）：漏注册客户端会收不到（静默无反应）
        registerS2CType(PointCodesCreatedPayload.ID, PointCodesCreatedPayload.CODEC)
        registerS2CType(PointCodesPayload.ID, PointCodesPayload.CODEC)
        // 玩家侧「我的兑换记录」：⚠ 漏了这行**直接崩客户端启动**（Fabric 的 registerS2C 要求
        // 该 payload 类型已注册，`ClientBridgeKtImpl.registerS2C` 会抛 IllegalArgumentException）
        registerS2CType(PointRecordsPayload.ID, PointRecordsPayload.CODEC)

        // ── RP 配置（服务器配置界面的「RP 商城」入口；⚠ **只认服主**，不下放任何节点）──
        //   原先判 OP：那是「界面上只有服主进得去」被误当成了服务端的把关 ——
        //   任何 OP 绕过界面直接发包就能改，而配置里有外部签名码密钥（能凭空签发兑换码）

        registerS2CType(PointsConfigPayload.ID, PointsConfigPayload.CODEC)

        registerC2S(RequestPointsConfigPayload.ID, RequestPointsConfigPayload.CODEC) { _, player ->
            player.server.execute {
                if (!MarketPermissions.isServerOwner(player)) return@execute
                sendToPlayer(player, buildConfigPayload())
            }
        }

        registerC2S(SavePointsConfigPayload.ID, SavePointsConfigPayload.CODEC) { payload, player ->
            val server = player.server
            server.execute {
                if (!MarketPermissions.isServerOwner(player)) return@execute
                if (!CobbleMarketConfig.pointsEnabled && payload.pointsEnabled) {
                    CobbleMarket.LOGGER.info("RP enabled by {}", player.name.string)
                }
                // 逐条校验：非法档位丢弃（与配置读取同一套判据），不让坏数据写进配置
                val tiers = payload.tiers.mapNotNull { tier ->
                    val name = tier.name.trim()
                    if (name.isBlank() || tier.priceCents <= 0L || tier.points <= 0L) {
                        CobbleMarket.LOGGER.warn(
                            "Ignoring invalid shop tier from config screen (name={}, price={}, points={})",
                            tier.name, tier.priceCents, tier.points
                        )
                        null
                    } else {
                        ShopTier(
                            name = name,
                            priceCents = tier.priceCents,
                            points = tier.points,
                            qr = tier.qr.trim(),
                            icon = tier.icon.trim()
                        )
                    }
                }
                CobbleMarketConfig.setPointsEnabled(payload.pointsEnabled)
                CobbleMarketConfig.setPointsMarketEnabled(payload.pointsMarketEnabled)
                CobbleMarketConfig.setRpAsQuestCurrency(payload.rpAsQuestCurrency)
                // RP 的四个费率（%）—— setter 只改内存，统一下面 save() 落盘
                CobbleMarketConfig.setPointsPokemonListingFeePercent(payload.pointsPokemonFee)
                CobbleMarketConfig.setPointsItemListingFeePercent(payload.pointsItemFee)
                CobbleMarketConfig.setPointsAuctionFeePercent(payload.pointsAuctionFee)
                CobbleMarketConfig.setPointsBuyOrderFeePercent(payload.pointsBuyOrderFee)
                CobbleMarketConfig.setPointsShopEnabled(payload.shopEnabled)
                CobbleMarketConfig.setPointsShopPayQrImage(payload.payQrImage)
                CobbleMarketConfig.setPointsShopDefaultIcon(payload.defaultIcon)
                CobbleMarketConfig.setPointsShopOrderTimeoutMinutes(payload.orderTimeoutMinutes)
                CobbleMarketConfig.setPointsShopCurrencyLabel(payload.currencyLabel)
                CobbleMarketConfig.setPointsShopTiers(tiers)
                CobbleMarketConfig.save()
                // 广播：在线玩家的**入口按钮置灰态**与已开界面立刻跟上（不必重进/重启）。
                // ⚠ 总开关必须单独再发一次 PointsStatusPayload —— 客户端那个缓存只在登录时补发，
                // 不广播的话关掉 RP 后入口按钮仍是亮的、点进去还能用（2026-09-18 用户实测踩到）
                server.playerManager.playerList.forEach {
                    sendToPlayer(
                        it,
                        PointsStatusPayload(
                            CobbleMarketConfig.pointsEnabled, CobbleMarketConfig.pointsMarketEnabled
                        )
                    )
                    sendShopInfo(server, it)
                }
                // **预检图片**：读不出来的当场告诉服主是哪张 —— 否则只能等玩家下单后去翻日志
                // （2026-09-18 用户实测：放了张 .jpg，只认 PNG 的校验把它拒了，界面却毫无提示）。
                // 券图也走同一道预检：配了张读不出来的图，玩家那边只会静默回退到内置券图，
                // 服主根本不知道自己的图没生效。
                val badQr = mutableListOf<String>()
                fun checkImage(name: String, what: String, load: (String) -> ByteArray?) {
                    if (name.isNotBlank() && load(name) == null) badQr += "$name（$what）"
                }
                checkImage(CobbleMarketConfig.pointsShopPayQrImage, "全局", PayQrImage::load)
                checkImage(CobbleMarketConfig.pointsShopDefaultIcon, "默认券图", TierIconImage::load)
                CobbleMarketConfig.pointsShopTiers.forEach {
                    checkImage(it.qr, it.name, PayQrImage::load)
                    checkImage(it.icon, "${it.name} 券图", TierIconImage::load)
                }
                if (badQr.isNotEmpty()) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(
                            false,
                            Text.translatable("cobblemarket.points.config.qr_problem", badQr.joinToString("、"))
                        )
                    )
                } else {
                    sendToPlayer(
                        player,
                        MarketResultPayload(
                            true,
                            Text.translatable("cobblemarket.points.config.saved", CurrencyService.pointsIcon())
                        )
                    )
                }
            }
        }

        // 登录补发总开关：客户端据此把入口按钮置灰（照市场总开关的补发时机）
        onPlayerJoin { player ->
            sendToPlayer(
                player,
                PointsStatusPayload(CobbleMarketConfig.pointsEnabled, CobbleMarketConfig.pointsMarketEnabled)
            )
        }

        // ── 充值：档位 / 收款码 / 下单 / 我已支付 / 取消 ──
        // 业务一律走 PointShopService（命令侧共用同一内核），网络层只做「收包 → 主线程 → 回包」

        registerS2CType(PointsShopInfoPayload.ID, PointsShopInfoPayload.CODEC)
        registerS2CType(PayQrImagePayload.ID, PayQrImagePayload.CODEC)
        registerS2CType(TierIconPayload.ID, TierIconPayload.CODEC)

        registerC2S(RequestPointsShopPayload.ID, RequestPointsShopPayload.CODEC) { _, player ->
            player.server.execute { sendShopInfo(player.server, player) }
        }

        registerC2S(RequestPayQrPayload.ID, RequestPayQrPayload.CODEC) { payload, player ->
            player.server.execute {
                val orderNo = PointOrderNumbers.normalize(payload.orderNo)
                val order = orderNo?.let { PointOrderState.get(player.server).find(it) }
                // 按**订单上快照的文件名**取图（下单时已解析好：专用码优先、否则全局码）
                // 图不存在/不是 PNG/太大 → 发**空包**：弹窗显示「收款码不可用」，
                // 而不是发失败结果（那会播一声 fail 音，玩家一头雾水，2026-09-18 实测踩到）
                val bytes = order?.let { PayQrImage.load(it.qrFile) }
                sendToPlayer(player, PayQrImagePayload(bytes ?: ByteArray(0)))
            }
        }

        registerC2S(RequestTierIconPayload.ID, RequestTierIconPayload.CODEC) { payload, player ->
            player.server.execute {
                // 读不出来就发**空包**（客户端回退到内置券图），而不是不回 ——
                // 不回的话客户端会一直停在「等图」状态，卡片空着
                val bytes = TierIconImage.load(payload.fileName)
                sendToPlayer(player, TierIconPayload(payload.fileName, bytes ?: ByteArray(0)))
            }
        }

        registerC2S(CreatePointOrderPayload.ID, CreatePointOrderPayload.CODEC) { payload, player ->
            val server = player.server
            server.execute {
                when (val result = PointShopService.createOrder(server, player.uuid, player.name.string, payload.tierIndex)) {
                    is PointShopResult.Success -> sendShopInfo(server, player)
                    is PointShopResult.Failure ->
                        sendToPlayer(player, MarketResultPayload(false, pointShopErrorText(result.error)))
                }
            }
        }

        registerC2S(MarkPointOrderPaidPayload.ID, MarkPointOrderPaidPayload.CODEC) { payload, player ->
            val server = player.server
            server.execute {
                val orderNo = PointOrderNumbers.normalize(payload.orderNo)
                if (orderNo == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.points.shop.not_found")))
                    return@execute
                }
                when (val result = PointShopService.markPaid(server, player.uuid, orderNo)) {
                    is PointShopResult.Success -> {
                        sendShopInfo(server, player)
                        sendToPlayer(
                            player,
                            MarketResultPayload(
                                true,
                                Text.translatable(
                                    "cobblemarket.points.shop.paid_ok", PointOrderNumbers.format(orderNo)
                                )
                            )
                        )
                    }

                    is PointShopResult.Failure ->
                        sendToPlayer(player, MarketResultPayload(false, pointShopErrorText(result.error)))
                }
            }
        }

        registerC2S(CancelPointOrderPayload.ID, CancelPointOrderPayload.CODEC) { payload, player ->
            val server = player.server
            server.execute {
                val orderNo = PointOrderNumbers.normalize(payload.orderNo)
                if (orderNo == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.points.shop.not_found")))
                    return@execute
                }
                when (val result = PointShopService.cancelOrder(server, player.uuid, orderNo)) {
                    is PointShopResult.Success -> {
                        sendShopInfo(server, player)
                        sendToPlayer(
                            player,
                            MarketResultPayload(
                                true,
                                Text.translatable(
                                    "cobblemarket.points.shop.cancelled", PointOrderNumbers.format(orderNo)
                                )
                            )
                        )
                    }

                    is PointShopResult.Failure ->
                        sendToPlayer(player, MarketResultPayload(false, pointShopErrorText(result.error)))
                }
            }
        }

        // ── 管理端：订单列表 / 确认发放 / 作废（**判 POINTS_ORDER 节点**）──
        // 业务一律走 PointShopService（命令侧共用同一内核）：发放路径分叉会长出两套幂等与审计
        registerS2CType(PointOrdersPayload.ID, PointOrdersPayload.CODEC)

        // ⚠ 下面三个 = 充值订单的**管理侧**（查看待确认 / 确认到账 / 作废），判 **POINTS_ORDER 节点**：
        //   2026-09-25 从「仅服主」改判为可授予（用户拍板）—— 服主可把核对工作交给别人。
        //   ⚠ **确认 = 加余额**（发钞权），授予前先确认对方真的**看得到收款账单**，
        //   否则「确认到账」这个动作本身不成立（docs/permission_nodes_plan.md ⑩）。
        //   ⚠ 命令行的 `points shop list/confirm/reject/purge` 判**同一个节点**，改这里要一起改。
        //   玩家侧的下单 / 我已支付 / 取消**不在此列**（那三个谁都能用，无需鉴权）
        registerC2S(RequestPointOrdersPayload.ID, RequestPointOrdersPayload.CODEC) { payload, player ->
            player.server.execute {
                if (!MarketPermissions.has(player, MarketPermissions.POINTS_ORDER)) return@execute
                sendOrderList(player, payload.includeFinished, payload.query)
            }
        }

        registerC2S(ConfirmPointOrderPayload.ID, ConfirmPointOrderPayload.CODEC) { payload, player ->
            val server = player.server
            server.execute {
                if (!MarketPermissions.has(player, MarketPermissions.POINTS_ORDER)) return@execute
                val orderNo = PointOrderNumbers.normalize(payload.orderNo)
                if (orderNo == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.points.shop.not_found")))
                    return@execute
                }
                when (val result = PointShopService.confirm(server, player.name.string, orderNo)) {
                    is PointShopResult.Success -> sendToPlayer(
                        player,
                        MarketResultPayload(
                            true,
                            Text.translatable(
                                "cobblemarket.points.shop.confirmed", PointOrderNumbers.format(orderNo)
                            )
                        )
                    )

                    is PointShopResult.Failure ->
                        sendToPlayer(player, MarketResultPayload(false, pointShopErrorText(result.error)))
                }
            }
        }

        registerC2S(RejectPointOrderPayload.ID, RejectPointOrderPayload.CODEC) { payload, player ->
            val server = player.server
            server.execute {
                if (!MarketPermissions.has(player, MarketPermissions.POINTS_ORDER)) return@execute
                val orderNo = PointOrderNumbers.normalize(payload.orderNo)
                if (orderNo == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.points.shop.not_found")))
                    return@execute
                }
                when (val result = PointShopService.reject(server, player.name.string, orderNo)) {
                    is PointShopResult.Success -> sendToPlayer(
                        player,
                        MarketResultPayload(
                            true,
                            Text.translatable(
                                "cobblemarket.points.shop.voided", PointOrderNumbers.format(orderNo)
                            )
                        )
                    )

                    is PointShopResult.Failure ->
                        sendToPlayer(player, MarketResultPayload(false, pointShopErrorText(result.error)))
                }
            }
        }
        // ── 兑换码管理（生成 / 列表 / 作废）──
        // 与命令侧共用 PointCodeState，不另写一套发放逻辑（方案风险清单第 12 条）。
        // 权限用 POINTS 节点（能印钞的节点，与命令的 OP 要求同级敏感）。

        registerC2S(CreatePointCodesPayload.ID, CreatePointCodesPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_CODE)) return@registerC2S
            if (!RequestThrottle.allowNotify(player, "create_point_codes", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 服务端是权威：界面也钳，但伪造客户端可能直接发包
                val count = payload.count.coerceIn(1, MAX_CODE_BATCH)
                if (payload.amount <= 0 || payload.uses <= 0 || payload.days < 0) {
                    sendToPlayer(player, PointCodesCreatedPayload(false, emptyList(), "", Text.translatable("cobblemarket.points.invalid_amount")))
                    return@execute
                }
                val now = System.currentTimeMillis()
                val expiresAt = if (payload.days <= 0) null else now + payload.days * 24L * 60 * 60 * 1000
                // 绑定玩家：与命令同一条解析路径（离线也能绑定，名字唯一即可）
                var boundUuid: java.util.UUID? = null
                var boundNameResolved: String? = null
                val boundRaw = payload.boundName.trim()
                if (boundRaw.isNotEmpty()) {
                    val resolved = BanState.resolvePlayer(server, boundRaw)
                    if (resolved == null) {
                        sendToPlayer(player, PointCodesCreatedPayload(false, emptyList(), "", Text.translatable("cobblemarket.ban.player_not_found", boundRaw)))
                        return@execute
                    }
                    boundUuid = resolved.first
                    boundNameResolved = resolved.second
                }
                val state = PointCodeState.get(server)
                val created = mutableListOf<String>()
                repeat(count) {
                    state.create(payload.amount, payload.uses, expiresAt, player.name.string, boundUuid, boundNameResolved, now)
                        ?.let { created.add(it.code) }
                }
                if (created.isEmpty()) {
                    sendToPlayer(player, PointCodesCreatedPayload(false, emptyList(), "", Text.translatable("cobblemarket.points.code_create_failed")))
                    return@execute
                }
                // 生成码不动任何人的余额（余额列写 0）：单记 CODE_CREATE，不污染「发放」口径（照命令侧）
                created.forEach { code ->
                    try {
                        PointsAuditLogger.log(
                            PointsLogType.CODE_CREATE, boundUuid ?: java.util.UUID(0, 0), boundNameResolved ?: "-",
                            payload.amount * payload.uses, 0, player.name.string,
                            "生成兑换码 ${PointCodes.format(code)}（面额 ${payload.amount} × ${payload.uses} 次）"
                        )
                    } catch (e: Exception) {
                        CobbleMarket.LOGGER.warn("Failed to audit point code create: {}", e.message)
                    }
                }
                PersistHelper.requestSave(server)
                val sizeMsg = Text.translatable("cobblemarket.points.code_created", created.size)
                // ≤10 张直接回显；更多写文件只回路径（码 = 钱，一串糊在界面里看不清也易外传）
                if (created.size <= INLINE_CODE_LIMIT) {
                    sendToPlayer(player, PointCodesCreatedPayload(true, created, "", sizeMsg))
                } else {
                    val path = writeCodesFile(created, payload.amount, payload.uses, expiresAt, boundNameResolved)
                    sendToPlayer(
                        player,
                        PointCodesCreatedPayload(
                            true, emptyList(), path ?: "",
                            if (path == null) Text.translatable("cobblemarket.points.code_file_failed") else sizeMsg
                        )
                    )
                }
            }
        }

        registerC2S(RequestPointCodesPayload.ID, RequestPointCodesPayload.CODEC) { _, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_CODE)) return@registerC2S
            if (!RequestThrottle.allow(player.uuid, "request_point_codes", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 全量下发（含用完/过期的，界面标状态），按创建时间倒序：最新的在最上面
                val entries = PointCodeState.get(server).all()
                    .sortedByDescending { it.createdAt }
                    .map {
                        PointCodeInfo(
                            code = it.code,
                            amount = it.amount,
                            remainingUses = it.remainingUses,
                            totalUses = it.totalUses,
                            expiresAt = it.expiresAt ?: 0L,
                            boundName = it.boundPlayerName ?: "",
                            createdBy = it.createdBy,
                            createdAt = it.createdAt
                        )
                    }
                sendToPlayer(player, PointCodesPayload(entries))
            }
        }

        registerC2S(PurgePointCodesPayload.ID, PurgePointCodesPayload.CODEC) { _, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_CODE)) return@registerC2S
            if (!RequestThrottle.allowNotify(player, "purge_point_codes", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = PointCodeState.get(server)
                val now = System.currentTimeMillis()
                // 只删**已经不能再用**的（用完 / 过期）—— 还能兑的一张都不动
                val dead = state.all().filter { !it.isUsable(now) }
                dead.forEach { state.revoke(it.code) }
                if (dead.isEmpty()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.points.code.purge_none")))
                    return@execute
                }
                PersistHelper.requestSave(server)
                try {
                    // 批量操作记**一条汇总**（逐条写会把审计刷爆）
                    PointsAuditLogger.log(
                        PointsLogType.CODE_REVOKE, java.util.UUID(0, 0), "-", 0, 0, player.name.string,
                        "清理已失效兑换码 ${dead.size} 张"
                    )
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.warn("Failed to audit point code purge: {}", e.message)
                }
                sendToPlayer(
                    player,
                    MarketResultPayload(true, Text.translatable("cobblemarket.points.code.purged", dead.size))
                )
            }
        }

        registerC2S(RevokePointCodePayload.ID, RevokePointCodePayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_CODE)) return@registerC2S
            if (!RequestThrottle.allowNotify(player, "revoke_point_code", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 容错：玩家/界面传来的可能带连字符与大小写差异，先归一化（与兑换同一条路径）
                val code = PointCodes.normalize(payload.code)
                if (code == null || !PointCodeState.get(server).revoke(code)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.points.code_revoke_failed")))
                    return@execute
                }
                PersistHelper.requestSave(server)
                try {
                    PointsAuditLogger.log(
                        PointsLogType.CODE_REVOKE, java.util.UUID(0, 0), "-", 0, 0, player.name.string,
                        "作废兑换码 ${PointCodes.format(code)}"
                    )
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.warn("Failed to audit point code revoke: {}", e.message)
                }
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.points.code_revoked")))
            }
        }

        registerC2S(RequestPointRecordsPayload.ID, RequestPointRecordsPayload.CODEC) { _, player ->
            // ⚠ 不查权限（看的是自己的记录），但**节流照旧**：列表请求一律走排队发，
            //   服务端超频时直接丢弃、不回包（客户端那边有排队补发 + 缓存兜底）
            if (!RequestThrottle.allow(player.uuid, "request_point_records", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                val entries = PointRecordState.get(server).recent(player.uuid)
                    .map { PointRecordInfo(amount = it.amount, balanceAfter = it.balanceAfter, at = it.at) }
                sendToPlayer(player, PointRecordsPayload(entries))
            }
        }
    }

    /**
     * 把订单列表发给请求的 OP（管理端）。
     *
     * - [includeFinished] = false 取待确认、true 取已完结（对账用）
     * - [query] 非空按订单号过滤：**允许带分隔符**（玩家报的就是 `ABC-DEF` 那种写法），
     *   但格式不对时返回空列表而不是全量 —— 搜「abc」搜出一堆无关订单比搜不到更糟
     */
    private fun sendOrderList(player: ServerPlayerEntity, includeFinished: Boolean, query: String) {
        val state = PointOrderState.get(player.server)
        val base = if (includeFinished) state.finished() else state.pendingConfirm()
        val q = query.trim()
        val filtered = if (q.isEmpty()) base else {
            // **订单号精确匹配 或 玩家名模糊匹配**：服主通常记得「是谁充的」，
            // 不记得那串 6 位号 —— 只认订单号的话找人还得先翻聊天记录（2026-09-18 用户提出）
            val normalized = PointOrderNumbers.normalize(q)
            base.filter {
                (normalized != null && it.orderNo == normalized) ||
                    it.playerName.contains(q, ignoreCase = true)
            }
        }
        val orders = filtered.map {
            PointOrderInfo(
                orderNo = it.orderNo,
                tierName = it.tierName,
                payCents = it.payCents,
                points = it.points,
                status = it.status.name,
                expireAt = it.expireAt,
                playerName = it.playerName,
                paidAt = it.paidAt ?: 0L,
                createdAt = it.createdAt,
                voidReason = it.voidReason?.name ?: ""
            )
        }
        sendToPlayer(player, PointOrdersPayload(orders = orders, includeFinished = includeFinished))
    }

    /** 把商城当前状态发给玩家：档位列表 + 有没有收款码 + 他的**全部**未完结订单 + 货币单位 */
    private fun buildConfigPayload() = PointsConfigPayload(
        pointsEnabled = CobbleMarketConfig.pointsEnabled,
        shopEnabled = CobbleMarketConfig.pointsShopEnabled,
        payQrImage = CobbleMarketConfig.pointsShopPayQrImage,
        defaultIcon = CobbleMarketConfig.pointsShopDefaultIcon,
        orderTimeoutMinutes = CobbleMarketConfig.pointsShopOrderTimeoutMinutes,
        tiers = CobbleMarketConfig.pointsShopTiers.map {
            PointsTierConfig(
                name = it.name, priceCents = it.priceCents, points = it.points, qr = it.qr, icon = it.icon
            )
        },
        currencyLabel = CobbleMarketConfig.pointsShopCurrencyLabel,
        pointsMarketEnabled = CobbleMarketConfig.pointsMarketEnabled,
        rpAsQuestCurrency = CobbleMarketConfig.rpAsQuestCurrency,
        pointsPokemonFee = CobbleMarketConfig.pointsPokemonListingFeePercent,
        pointsItemFee = CobbleMarketConfig.pointsItemListingFeePercent,
        pointsAuctionFee = CobbleMarketConfig.pointsAuctionFeePercent,
        pointsBuyOrderFee = CobbleMarketConfig.pointsBuyOrderFeePercent
    )

    private fun sendShopInfo(server: MinecraftServer, player: ServerPlayerEntity) {
        // ⚠ **充值开关关闭时不发档位**：客户端的「购买 RP」入口本来就按"档位非空"显示，
        //   这样一关入口立刻消失、界面也进不去 —— 原来只在下单时拒，玩家照样能进去、点了才被拒，
        //   服主看到的等于"这个开关没效果"（2026-09-18 用户实测反馈）。
        //   兑换码那条路不受影响（那是另一套发放渠道）。
        val tiers = if (CobbleMarketConfig.pointsShopEnabled) {
            // 只带**券图文件名**，不带字节：没配图的档位完全不产生网络开销，
            // 客户端自己对没缓存的文件名发 RequestTierIconPayload 取图。
            // ⚠ 这里**就地把全局默认券图展开**进各档（优先级：该档专用 > 全局默认）：
            //   客户端只管「iconFile 非空就去取图」，不必知道默认图这回事 ——
            //   配置界面回显的仍是服主填的原始值（那份快照走 PointsConfigPayload，不展开）
            CobbleMarketConfig.pointsShopTiers.map {
                ShopTierInfo(
                    name = it.name,
                    priceCents = it.priceCents,
                    points = it.points,
                    iconFile = it.icon.ifBlank { CobbleMarketConfig.pointsShopDefaultIcon }
                )
            }
        } else {
            emptyList()
        }
        // 「我的订单」给**全部订单**（含已发放 / 已作废的历史），按创建时间倒序 ——
        // 只给未完结的话，订单一被处理就从玩家眼前消失，他查不到自己充过什么
        val orders = PointOrderState.get(server).ordersOf(player.uuid, MAX_ORDER_HISTORY).map { o ->
            PointOrderInfo(
                orderNo = o.orderNo,
                tierName = o.tierName,
                payCents = o.payCents,
                points = o.points,
                status = o.status.name,
                expireAt = o.expireAt,
                playerName = o.playerName,
                paidAt = o.paidAt ?: 0L,
                createdAt = o.createdAt,
                voidReason = o.voidReason?.name ?: ""
            )
        }
        sendToPlayer(
            player,
            PointsShopInfoPayload(
                tiers = tiers,
                hasQr = PayQrImage.globalAvailable(),
                orders = orders,
                currencyLabel = CobbleMarketConfig.pointsShopCurrencyLabel
            )
        )

    }

    /** 直接回显的码数量上限：超过就写文件（方案规矩） */
    private const val INLINE_CODE_LIMIT = 10

    /** 一次生成的张数上限（与命令 `integer(1, 100)` 对齐） */
    private const val MAX_CODE_BATCH = 100

    /**
     * 把生成的一批码写成 CSV 并返回**可读的绝对路径**；写失败返回 null（调用方给出提示）。
     *
     * 带 UTF-8 BOM：服主多半用 Excel 打开（照 [com.shusheng.cobblemarket.event.TransactionFileLogger] 的惯例）。
     */
    private fun writeCodesFile(
        codes: List<String>,
        amount: Long,
        uses: Int,
        expiresAt: Long?,
        boundName: String?
    ): String? = try {
        val dir = com.shusheng.cobblemarket.platform.configDir().resolve("cobblemarket/points").toFile()
        dir.mkdirs()
        val stamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val file = File(dir, "codes_$stamp.csv")
        val expires = if (expiresAt == null) "永久" else java.time.Instant.ofEpochMilli(expiresAt)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        file.writeText(
            "﻿码,面额,次数,有效期,绑定玩家\n" +
                codes.joinToString("\n") {
                    "${PointCodes.format(it)},$amount,$uses,$expires,${boundName ?: ""}"
                } + "\n"
        )
        file.absolutePath
    } catch (e: Exception) {
        CobbleMarket.LOGGER.warn("Failed to write point codes file: {}", e.message)
        null
    }
}
