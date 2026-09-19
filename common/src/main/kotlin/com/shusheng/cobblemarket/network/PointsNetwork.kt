package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.config.ShopTier
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.PayQrImage
import com.shusheng.cobblemarket.currency.PointOrderNumbers
import com.shusheng.cobblemarket.currency.PointOrderState
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
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text

/**
 * 兑换码输入上限：外部码 32 位按 4 位分组后是 39 字符（32 + 7 个连字符），这里留一倍余量。
 * **超长直接在协议层拒绝**（读串越界会抛异常）——不给伪造客户端塞超长串做无谓解析的机会。
 */
private const val MAX_CODE_LENGTH = 64

/**
 * RP 总开关（S2C）：登录时下发一次，客户端据此决定「RP 市场」入口按钮是否置灰。
 * ⚠ 与市场总开关（[MarketStatePayload]）的区别：那个是**紧急停市**，按钮仍有用（看列表/取回资产）；
 * RP 总开关管的是「这个服不用RP」—— 界面进不去也没损失，所以按钮照喵喵银行的做法**置灰**。
 */
data class PointsStatusPayload(val enabled: Boolean) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PointsStatusPayload>(CobbleMarket.id("points_status"))
        val CODEC: PacketCodec<PacketByteBuf, PointsStatusPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.enabled) },
            { b -> PointsStatusPayload(b.readBoolean()) }
        )
    }
}

/** 玩家在RP 市场界面里兑换（命令 `/market points redeem` 是同一个内核的另一入口） */
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
    val createdAt: Long = 0L
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

// ── 管理端：订单列表 / 确认发放 / 作废（**服务端只认 OP**，与命令侧同一套内核）──

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
    b.writeString(o.status, 32)
    b.writeLong(o.expireAt)
    b.writeString(o.playerName, MAX_TIER_NAME_LENGTH)
    b.writeLong(o.paidAt)
    b.writeLong(o.createdAt)
}

private fun readOrder(b: PacketByteBuf) = PointOrderInfo(
    // ⚠ 读端一律用具名参数：字段顺序一旦对不上，具名能立刻看出来（payload 顺序铁律）
    orderNo = b.readString(MAX_ORDER_NO_LENGTH),
    tierName = b.readString(MAX_TIER_NAME_LENGTH),
    payCents = b.readLong(),
    points = b.readLong(),
    status = b.readString(32),
    expireAt = b.readLong(),
    playerName = b.readString(MAX_TIER_NAME_LENGTH),
    paidAt = b.readLong(),
    createdAt = b.readLong()
)

// ── RP 市场配置（仅 OP；服务器配置界面里的「RP 市场」入口）──

/** 一个档位的可编辑形态（配置界面用；金额是**分**，与账本同口径） */
data class PointsTierConfig(
    val name: String,
    val priceCents: Long,
    val points: Long,
    /** 该档专用收款码文件名（留空 = 走全局码 + 尾数） */
    val qr: String,
    /** 该档「RP 券」图文件名（留空 = 用内置券图） */
    val icon: String = ""
)

/** 进配置界面时请求一次当前值（仅 OP 会被响应） */
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

/** 当前 RP 市场配置快照（S2C，仅发给 OP） */
data class PointsConfigPayload(
    val pointsEnabled: Boolean,
    val shopEnabled: Boolean,
    val payQrImage: String,
    /** 档位券图的**全局默认**文件名（空 = 用内置券图） */
    val defaultIcon: String,
    val orderTimeoutMinutes: Long,
    val tiers: List<PointsTierConfig>,
    /** 档位金额的货币单位（空 = 不显示） */
    val currencyLabel: String
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
                    currencyLabel = currencyLabel
                )
            }
        )
    }
}

/** 保存 RP 市场配置（C2S）。⚠ 服务端只认 OP（`hasPermissionLevel(2)`），不下放权限节点 */
data class SavePointsConfigPayload(
    val pointsEnabled: Boolean,
    val shopEnabled: Boolean,
    val payQrImage: String,
    /** 档位券图的**全局默认**文件名（空 = 用内置券图） */
    val defaultIcon: String,
    val orderTimeoutMinutes: Long,
    val tiers: List<PointsTierConfig>,
    /** 档位金额的货币单位（空 = 不显示） */
    val currencyLabel: String
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
                    currencyLabel = currencyLabel
                )
            }
        )
    }
}

/** 档位名长度上限（防止伪造客户端塞超长串撑爆配置） */
private const val MAX_TIER_NAME_LENGTH = 32

/** 文件名长度上限 */
private const val MAX_FILE_NAME_LENGTH = 128

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

        // ── RP 市场配置（服务器配置界面的「RP 市场」入口；⚠ 仅 OP，不下放任何节点）──

        registerS2CType(PointsConfigPayload.ID, PointsConfigPayload.CODEC)

        registerC2S(RequestPointsConfigPayload.ID, RequestPointsConfigPayload.CODEC) { _, player ->
            player.server.execute {
                if (!player.hasPermissionLevel(2)) return@execute
                sendToPlayer(player, buildConfigPayload())
            }
        }

        registerC2S(SavePointsConfigPayload.ID, SavePointsConfigPayload.CODEC) { payload, player ->
            val server = player.server
            server.execute {
                if (!player.hasPermissionLevel(2)) return@execute
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
                    sendToPlayer(it, PointsStatusPayload(CobbleMarketConfig.pointsEnabled))
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
            sendToPlayer(player, PointsStatusPayload(CobbleMarketConfig.pointsEnabled))
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

        // ── 管理端：订单列表 / 确认发放 / 作废（**只认 OP**）──
        // 业务一律走 PointShopService（命令侧共用同一内核）：发放路径分叉会长出两套幂等与审计
        registerS2CType(PointOrdersPayload.ID, PointOrdersPayload.CODEC)

        registerC2S(RequestPointOrdersPayload.ID, RequestPointOrdersPayload.CODEC) { payload, player ->
            player.server.execute {
                if (!player.hasPermissionLevel(2)) return@execute
                sendOrderList(player, payload.includeFinished, payload.query)
            }
        }

        registerC2S(ConfirmPointOrderPayload.ID, ConfirmPointOrderPayload.CODEC) { payload, player ->
            val server = player.server
            server.execute {
                if (!player.hasPermissionLevel(2)) return@execute
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
                if (!player.hasPermissionLevel(2)) return@execute
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
                createdAt = it.createdAt
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
        currencyLabel = CobbleMarketConfig.pointsShopCurrencyLabel
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
                createdAt = o.createdAt
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
}
