package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.permission.MarketPermissions
import com.cobblemon.mod.common.Cobblemon
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.config.CurrencyHandler
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.event.TransactionCategory
import com.shusheng.cobblemarket.event.TransactionHistory
import com.shusheng.cobblemarket.event.TransactionRecord
import com.shusheng.cobblemarket.event.TransactionType
import com.shusheng.cobblemarket.market.BuyOrder
import com.shusheng.cobblemarket.market.BuyOrderState
import com.shusheng.cobblemarket.market.BuyOrderStatus
import com.shusheng.cobblemarket.market.BuyOrderType
import com.shusheng.cobblemarket.market.PendingDelivery
import com.shusheng.cobblemarket.market.ItemBlacklistState
import com.shusheng.cobblemarket.market.ItemListing
import com.shusheng.cobblemarket.market.ItemMarketState
import com.shusheng.cobblemarket.market.ListingStatus
import com.shusheng.cobblemarket.market.MarketListing
import com.shusheng.cobblemarket.market.MarketState
import com.shusheng.cobblemarket.market.PokemonBlacklistEntry
import com.shusheng.cobblemarket.market.PokemonBlacklistState
import com.shusheng.cobblemarket.util.RequestThrottle
import com.shusheng.cobblemarket.util.TypeTextColors
import com.shusheng.cobblemarket.platform.onServerTickEnd
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer

import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID

// ── DTO：精简求购单条目（客户端列表用） ──

/** 待确认交付的 DTO（买家确认弹窗展示用；null = 无待确认） */
data class BuyOrderPendingEntry(
    val id: UUID,
    val sellerName: String,
    val price: Int,
    val speciesKey: String?,   // 精灵 = 翻译 key（客户端本地翻译）；物品 = null
    val level: Int,
    val shiny: Boolean,
    val extraData: Map<String, String>, // 精灵展示字段（IV/球种等）
    val count: Int,            // 物品件数（精灵 = 1）
    /** 物品交付的组件 NBT（附魔/名称等验收核对用；精灵交付为 null） */
    val itemNbt: NbtCompound?
) {
    fun write(buf: PacketByteBuf) {
        buf.writeUuid(id)
        buf.writeString(sellerName)
        buf.writeInt(price)
        buf.writeBoolean(speciesKey != null)
        speciesKey?.let { buf.writeString(it) }
        buf.writeInt(level)
        buf.writeBoolean(shiny)
        buf.writeVarInt(extraData.size)
        extraData.forEach { (k, v) -> buf.writeString(k); buf.writeString(v) }
        buf.writeInt(count)
        buf.writeBoolean(itemNbt != null)
        itemNbt?.let { PacketCodecs.NBT_COMPOUND.encode(buf, it) }
    }

    companion object {
        fun read(buf: PacketByteBuf) = BuyOrderPendingEntry(
            id = buf.readUuid(),
            sellerName = buf.readString(),
            price = buf.readInt(),
            speciesKey = if (buf.readBoolean()) buf.readString() else null,
            level = buf.readInt(),
            shiny = buf.readBoolean(),
            extraData = (0 until buf.readVarInt()).associate { buf.readString() to buf.readString() },
            count = buf.readInt(),
            itemNbt = if (buf.readBoolean()) PacketCodecs.NBT_COMPOUND.decode(buf) else null
        )
    }
}

data class BuyOrderEntry(
    val id: UUID,
    val type: String,          // "POKEMON" / "ITEM"
    val buyerUuid: UUID,
    val buyerName: String,
    val speciesId: String?,    // 精灵 = 物种 ID；null = 任意精灵（客户端交付预核对用）
    val speciesKey: String?,   // 精灵 = 翻译 key（客户端本地翻译）；null = 任意精灵；物品 = null
    val shinyFilter: Int,
    val htFilter: Int,
    val ivHp: Int, val ivAtk: Int, val ivDef: Int,
    val ivSpAtk: Int, val ivSpDef: Int, val ivSpd: Int,
    val aspects: List<String>,
    val abilityKey: String?,
    val natureKey: String?,
    val itemId: String,        // 物品单的物品 ID（精灵单为空串）
    val itemComponentsSpec: net.minecraft.nbt.NbtCompound?, // 物品单的组件要求（null = 所有变体）
    val totalCount: Int,
    val remainingCount: Int,
    val minPrice: Int,
    val maxPrice: Int,
    val note: String,          // 买家备注（额外需求，可为空）
    val createdAt: Long,
    val expiresAt: Long,
    val pending: BuyOrderPendingEntry?, // 待确认交付（锁定策略下最多 1 条）
    /**
     * 这一单的币种（枚举名，**服务端权威值**）。
     *
     * ⚠ 与市场/拍卖同理：**币种是订单自己的属性**，客户端显示金额单位一律读它，
     * 别读界面上的「当前币种」状态 —— 漏一处就会把 RP 求购单的价格标成 ₽。
     * 客户端另外在构造时解析出 `isPoints`（见 BuyOrderScreen），行渲染每帧不再 parse。
     */
    val currency: String,
    // 属性（1.2.0 末尾追加 + 默认值）：强制下架弹窗要显示「类型：」；来源是物种，不是买家的需求条件
    val primaryType: String = "",
    val secondaryType: String = ""
) {
    /** 这一单是不是 RP 计价 —— **渲染货币单位就读它**（构造时解析一次，行渲染每帧不再 parse，照 AuctionEntry） */
    val isPoints: Boolean = MarketCurrency.fromStored(currency) == MarketCurrency.POINTS

    fun write(buf: PacketByteBuf) {
        buf.writeUuid(id)
        buf.writeString(type)
        buf.writeUuid(buyerUuid)
        buf.writeString(buyerName)
        buf.writeBoolean(speciesId != null)
        speciesId?.let { buf.writeString(it) }
        buf.writeBoolean(speciesKey != null)
        speciesKey?.let { buf.writeString(it) }
        buf.writeInt(shinyFilter)
        buf.writeInt(htFilter)
        buf.writeInt(ivHp); buf.writeInt(ivAtk); buf.writeInt(ivDef)
        buf.writeInt(ivSpAtk); buf.writeInt(ivSpDef); buf.writeInt(ivSpd)
        buf.writeVarInt(aspects.size)
        aspects.forEach { buf.writeString(it) }
        buf.writeBoolean(abilityKey != null)
        abilityKey?.let { buf.writeString(it) }
        buf.writeBoolean(natureKey != null)
        natureKey?.let { buf.writeString(it) }
        buf.writeString(itemId)
        buf.writeNbt(itemComponentsSpec)
        buf.writeInt(totalCount)
        buf.writeInt(remainingCount)
        buf.writeInt(minPrice)
        buf.writeInt(maxPrice)
        buf.writeString(note)
        buf.writeLong(createdAt)
        buf.writeLong(expiresAt)
        buf.writeBoolean(pending != null)
        pending?.let { it.write(buf) }
        buf.writeString(currency)
        buf.writeString(primaryType)
        buf.writeString(secondaryType)
    }

    companion object {
        fun read(buf: PacketByteBuf) = BuyOrderEntry(
            id = buf.readUuid(),
            type = buf.readString(),
            buyerUuid = buf.readUuid(),
            buyerName = buf.readString(),
            speciesId = if (buf.readBoolean()) buf.readString() else null,
            speciesKey = if (buf.readBoolean()) buf.readString() else null,
            shinyFilter = buf.readInt(),
            htFilter = buf.readInt(),
            ivHp = buf.readInt(), ivAtk = buf.readInt(), ivDef = buf.readInt(),
            ivSpAtk = buf.readInt(), ivSpDef = buf.readInt(), ivSpd = buf.readInt(),
            aspects = (0 until buf.readVarInt()).map { buf.readString() },
            abilityKey = if (buf.readBoolean()) buf.readString() else null,
            natureKey = if (buf.readBoolean()) buf.readString() else null,
            itemId = buf.readString(),
            itemComponentsSpec = buf.readNbt(),
            totalCount = buf.readInt(),
            remainingCount = buf.readInt(),
            minPrice = buf.readInt(),
            maxPrice = buf.readInt(),
            note = buf.readString(),
            createdAt = buf.readLong(),
            expiresAt = buf.readLong(),
            pending = if (buf.readBoolean()) BuyOrderPendingEntry.read(buf) else null,
            currency = buf.readString(),
            primaryType = buf.readString(),
            secondaryType = buf.readString()
        )
    }
}

// ⚠ 物种属性 key 的解析已提到 [com.shusheng.cobblemarket.util.TypeTextColors.typeKeyOf] **共用**：
//   求购单的聊天通知（`BuyOrderState.requirementText()`）也要用它给精灵名上色，
//   留第二份就又回到「同一个东西两处各写一遍、改一处漏一处」的老路（2026-09-26 收口）。

fun buyOrderToEntry(o: BuyOrder, server: MinecraftServer): BuyOrderEntry = BuyOrderEntry(
    id = o.id,
    type = o.type.name,
    buyerUuid = o.buyerUuid,
    buyerName = o.buyerName,
    speciesId = o.speciesId,
    primaryType = com.shusheng.cobblemarket.util.TypeTextColors.typeKeyOf(o.speciesId, true),
    secondaryType = com.shusheng.cobblemarket.util.TypeTextColors.typeKeyOf(o.speciesId, false),
    speciesKey = o.speciesKey,
    shinyFilter = o.shinyFilter,
    htFilter = o.htFilter,
    ivHp = o.ivHp, ivAtk = o.ivAtk, ivDef = o.ivDef,
    ivSpAtk = o.ivSpAtk, ivSpDef = o.ivSpDef, ivSpd = o.ivSpd,
    aspects = o.aspects,
    abilityKey = o.abilityKey,
    natureKey = o.natureKey,
    itemId = o.itemId,
    itemComponentsSpec = o.itemComponentsSpec,
    totalCount = o.totalCount,
    remainingCount = o.remainingCount,
    minPrice = o.minPrice,
    maxPrice = o.maxPrice,
    note = o.note,
    createdAt = o.createdAt,
    expiresAt = o.expiresAt,
    pending = o.pendingDeliveries.firstOrNull()?.let { p ->
        // 极巨化 / 初始训练家：与太晶 / moves 同款，从待交付精灵自带的完整 NBT 现读
        val dyn = com.shusheng.cobblemarket.util.PokemonDynamax.fromNbt(p.pokemonNbt)
        BuyOrderPendingEntry(
            id = p.id,
            sellerName = p.sellerName,
            price = p.price,
            speciesKey = p.extraData["speciesKey"],
            level = p.level,
            shiny = p.shiny,
            // 待交付的精灵同理：旧数据没有 moves 快照时从 NBT 现读
            extraData = p.extraData + (
                "moves" to com.shusheng.cobblemarket.util.MoveText.resolve(p.extraData["moves"], p.pokemonNbt)
                ) +
                ("gmaxFactor" to dyn.gmaxFactor.toString()) +
                ("dmaxLevel" to dyn.dmaxLevel.toString()) +
                ("ot" to com.shusheng.cobblemarket.util.PokemonOT.fromNbt(p.pokemonNbt, server)),
            count = p.count,
            itemNbt = p.itemNbt
        )
    },
    currency = o.currency.name
)

// ── C2S：请求求购单列表（币种必选：列表一次只看一种货币，照拍卖场） ──

/** [currency] 用 **String 不解析**：严格校验留在 handler（`MarketCurrency.fromRequest`） */
class RequestBuyOrderListPayload(val currency: String) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestBuyOrderListPayload>(CobbleMarket.id("request_buy_order_list"))
        val CODEC: PacketCodec<PacketByteBuf, RequestBuyOrderListPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.currency) },
            { b -> RequestBuyOrderListPayload(b.readString()) }
        )
    }
}

// ── S2C：求购单列表数据 ──

data class BuyOrderListDataPayload(val entries: List<BuyOrderEntry>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<BuyOrderListDataPayload>(CobbleMarket.id("buy_order_list_data"))
        val CODEC: PacketCodec<PacketByteBuf, BuyOrderListDataPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> BuyOrderListDataPayload((0 until b.readVarInt()).map { BuyOrderEntry.read(b) }) }
        )
    }
}

// ── S2C：求购单增量事件（NEW/UPDATED/CLOSED；CLOSED 也带条目供按 id 移除行） ──

data class BuyOrderEventPayload(val event: String, val entry: BuyOrderEntry) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<BuyOrderEventPayload>(CobbleMarket.id("buy_order_event"))
        val CODEC: PacketCodec<PacketByteBuf, BuyOrderEventPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.event); p.entry.write(b) },
            { b -> BuyOrderEventPayload(b.readString(), BuyOrderEntry.read(b)) }
        )
    }
}

// ── C2S：创建精灵求购单 ──

data class CreatePokemonBuyOrderPayload(
    val speciesId: String,     // 空串 = 任意精灵
    val shinyFilter: Int,      // -1 不限 / 0 仅非闪 / 1 仅闪
    val htFilter: Int,         // 0 不限 / 1 仅特训 / 2 不含特训
    val ivHp: Int, val ivAtk: Int, val ivDef: Int,   // -1 = 不限
    val ivSpAtk: Int, val ivSpDef: Int, val ivSpd: Int,
    val aspects: List<String>, // ["*"] = 不限形态
    val abilityKey: String,    // 空串 = 不限
    val natureKey: String,     // 空串 = 不限
    val minPrice: Int,
    val maxPrice: Int,
    val note: String,          // 买家备注（可为空）
    /** 这一单收哪种钱（枚举名）。**币种只在创建时来自客户端**，校验后写进订单对象，此后一律读对象 */
    val currency: String,
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CreatePokemonBuyOrderPayload>(CobbleMarket.id("create_pokemon_buy_order"))
        val CODEC: PacketCodec<PacketByteBuf, CreatePokemonBuyOrderPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.speciesId); b.writeInt(p.shinyFilter); b.writeInt(p.htFilter)
                b.writeInt(p.ivHp); b.writeInt(p.ivAtk); b.writeInt(p.ivDef)
                b.writeInt(p.ivSpAtk); b.writeInt(p.ivSpDef); b.writeInt(p.ivSpd)
                b.writeVarInt(p.aspects.size); p.aspects.forEach { b.writeString(it) }
                b.writeString(p.abilityKey); b.writeString(p.natureKey)
                b.writeInt(p.minPrice); b.writeInt(p.maxPrice)
                b.writeString(p.note)
                b.writeString(p.currency)
            },
            { b ->
                CreatePokemonBuyOrderPayload(
                    speciesId = b.readString(), shinyFilter = b.readInt(), htFilter = b.readInt(),
                    ivHp = b.readInt(), ivAtk = b.readInt(), ivDef = b.readInt(),
                    ivSpAtk = b.readInt(), ivSpDef = b.readInt(), ivSpd = b.readInt(),
                    aspects = (0 until b.readVarInt()).map { b.readString() },
                    abilityKey = b.readString(), natureKey = b.readString(),
                    minPrice = b.readInt(), maxPrice = b.readInt(), note = b.readString(),
                    currency = b.readString()
                )
            }
        )
    }
}

// ── C2S：创建物品求购单 ──

data class CreateItemBuyOrderPayload(
    val itemId: String,
    val totalCount: Int,
    val minPrice: Int,
    val maxPrice: Int,
    val note: String,          // 买家备注（可为空）
    val useHeldItem: Boolean,  // true = 用手持物品（含组件快照）作为求购要求
    // 搜索路径选中的具体变体组件快照（如「招式学习器 · 打鼾」）；手持路径为 null（服务端读主手）
    val componentsSpec: net.minecraft.nbt.NbtCompound? = null,
    /** 这一单收哪种钱（枚举名）。语义同 [CreatePokemonBuyOrderPayload.currency] */
    val currency: String,
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CreateItemBuyOrderPayload>(CobbleMarket.id("create_item_buy_order"))
        val CODEC: PacketCodec<PacketByteBuf, CreateItemBuyOrderPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.itemId); b.writeInt(p.totalCount); b.writeInt(p.minPrice); b.writeInt(p.maxPrice)
                b.writeString(p.note)
                b.writeBoolean(p.useHeldItem)
                b.writeNbt(p.componentsSpec)
                b.writeString(p.currency)
            },
            { b -> CreateItemBuyOrderPayload(
                itemId = b.readString(),
                totalCount = b.readInt(),
                minPrice = b.readInt(),
                maxPrice = b.readInt(),
                note = b.readString(),
                useHeldItem = b.readBoolean(),
                componentsSpec = b.readNbt(),
                currency = b.readString(),
            ) }
        )
    }
}

// ── C2S：买家关闭求购单（退剩余冻结金） ──

data class CancelBuyOrderPayload(val orderId: UUID) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CancelBuyOrderPayload>(CobbleMarket.id("cancel_buy_order"))
        val CODEC: PacketCodec<PacketByteBuf, CancelBuyOrderPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.orderId) },
            { b -> CancelBuyOrderPayload(b.readUuid()) }
        )
    }
}

// ── C2S：OP 强制下架求购单（复用 closeByBuyer：冻结金退买家、待确认交付退卖家） ──

data class ForceCancelBuyOrderPayload(val orderId: UUID) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<ForceCancelBuyOrderPayload>(CobbleMarket.id("force_cancel_buy_order"))
        val CODEC: PacketCodec<PacketByteBuf, ForceCancelBuyOrderPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.orderId) },
            { b -> ForceCancelBuyOrderPayload(b.readUuid()) }
        )
    }
}

// ── C2S：卖家交付精灵（1 只即凑满，订单自动关闭） ──

data class DeliverPokemonBuyOrderPayload(
    val orderId: UUID,
    val pokemonUuid: UUID,
    val price: Int
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<DeliverPokemonBuyOrderPayload>(CobbleMarket.id("deliver_pokemon_buy_order"))
        val CODEC: PacketCodec<PacketByteBuf, DeliverPokemonBuyOrderPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.orderId); b.writeUuid(p.pokemonUuid); b.writeInt(p.price) },
            { b -> DeliverPokemonBuyOrderPayload(b.readUuid(), b.readUuid(), b.readInt()) }
        )
    }
}

// ── C2S：卖家交付物品（按件成交，多个卖家可部分交付） ──

data class DeliverItemBuyOrderPayload(
    val orderId: UUID,
    val count: Int,
    val price: Int,
    /** 所选形态的序列化（客户端背包示例栈）：服务端重建为权威参考栈，不信任客户端一致性 */
    val variantNbt: NbtCompound,
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<DeliverItemBuyOrderPayload>(CobbleMarket.id("deliver_item_buy_order"))
        val CODEC: PacketCodec<PacketByteBuf, DeliverItemBuyOrderPayload> = PacketCodec.of(
            { p, b ->
                b.writeUuid(p.orderId); b.writeInt(p.count); b.writeInt(p.price)
                PacketCodecs.NBT_COMPOUND.encode(b, p.variantNbt)
            },
            { b -> DeliverItemBuyOrderPayload(b.readUuid(), b.readInt(), b.readInt(), PacketCodecs.NBT_COMPOUND.decode(b)) }
        )
    }
}

// ── C2S：买家接受待确认交付（结算：扣冻结金实际价+退差价、卖家收钱-费、货进买家待领取） ──

data class AcceptPendingDeliverPayload(
    val orderId: UUID,
    val deliveryId: UUID
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AcceptPendingDeliverPayload>(CobbleMarket.id("accept_pending_deliver"))
        val CODEC: PacketCodec<PacketByteBuf, AcceptPendingDeliverPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.orderId); b.writeUuid(p.deliveryId) },
            { b -> AcceptPendingDeliverPayload(b.readUuid(), b.readUuid()) }
        )
    }
}

// ── C2S：买家拒绝待确认交付（货退回卖家待领取，订单保持 OPEN；reason = 给卖家的留言，可为空） ──

data class RejectPendingDeliverPayload(
    val orderId: UUID,
    val deliveryId: UUID,
    val reason: String
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RejectPendingDeliverPayload>(CobbleMarket.id("reject_pending_deliver"))
        val CODEC: PacketCodec<PacketByteBuf, RejectPendingDeliverPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.orderId); b.writeUuid(p.deliveryId); b.writeString(p.reason) },
            { b -> RejectPendingDeliverPayload(b.readUuid(), b.readUuid(), b.readString()) }
        )
    }
}

object BuyOrderNetwork {

    /** 备注最大长度（字符，服务端钳制；客户端输入框同值限制） */
    const val NOTE_MAX_LENGTH = 100

    fun register() {
        registerS2CType(BuyOrderListDataPayload.ID, BuyOrderListDataPayload.CODEC)
        registerS2CType(BuyOrderEventPayload.ID, BuyOrderEventPayload.CODEC)

        // 到期自动关闭：每秒轮询（照拍卖结算定时器），退款+广播不依赖玩家打开界面
        var tick = 0
        onServerTickEnd { server ->
            tick++
            if (tick < 20) return@onServerTickEnd
            tick = 0
            settleExpiredAndBroadcast(server)
        }

        registerC2S(RequestBuyOrderListPayload.ID, RequestBuyOrderListPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "buy_order_list", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            // 币种严格校验：拿容错版会把伪造的垃圾值静默当成主货币（等于放行），见 MarketCurrency 注释
            val currency = MarketCurrency.fromRequest(payload.currency)
            if (currency == null) {
                sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_currency")))
                return@registerC2S
            }
            // ⚠ 查询类**不拦开关**（展示层过滤碰不到钱货）：关掉「玩家间 RP 交易」后存量 RP 求购单
            //   仍要能被看见 —— 买家要能关单取回冻结金、卖家要能看到还有人在收、自己的货被退回后能取回
            val server = player.server
            server.execute {
                settleExpiredAndBroadcast(server)
                sendToPlayer(
                    player,
                    BuyOrderListDataPayload(BuyOrderState.get(server).getOpenOrders(currency).map { buyOrderToEntry(it, server) })
                )
            }
        }

        registerC2S(CreatePokemonBuyOrderPayload.ID, CreatePokemonBuyOrderPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "create_pokemon_buy_order", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                // 币种校验（非法拒 / RP 过两道闸）：创建是**币种唯一的来源**，此后一律读订单对象
                val currency = validateCreateCurrency(player, payload.currency) ?: return@execute
                if (!checkPrices(player, payload.minPrice, payload.maxPrice)) return@execute
                if (!checkOrderLimit(server, player)) return@execute
                // 形态列表上限：恶意客户端可发海量 aspects 随订单持久化 + 全服广播（正常客户端一个物种形态最多几十个）
                if (payload.aspects.size > PokemonBlacklistEntry.MAX_ASPECTS) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                // 物种解析：空 = 任意精灵；解析失败明确反馈
                val speciesId = payload.speciesId.trim().ifEmpty { null }
                var speciesKey: String? = null
                if (speciesId != null) {
                    val resolved = com.shusheng.cobblemarket.util.SpeciesText.resolveByNameOrId(speciesId)
                    if (resolved == null) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.species_not_found")))
                        return@execute
                    }
                    speciesKey = resolvedSpeciesKey(resolved) ?: return@execute
                }
                // 冻结金 = maxPrice × 1（精灵固定 1 只），扣款失败即余额不足（按这一单的币种扣）
                if (!CurrencyService.remove(player, currency, payload.maxPrice.toLong())) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.not_enough")))
                    return@execute
                }
                val now = System.currentTimeMillis()
                val order = BuyOrder(
                    id = UUID.randomUUID(),
                    buyerUuid = player.uuid,
                    buyerName = player.name.string,
                    type = BuyOrderType.POKEMON,
                    speciesId = speciesId,
                    speciesKey = speciesKey,
                    shinyFilter = payload.shinyFilter.coerceIn(PokemonBlacklistEntry.SHINY_ANY, PokemonBlacklistEntry.SHINY_YES),
                    htFilter = payload.htFilter.coerceIn(PokemonBlacklistEntry.HT_ANY, PokemonBlacklistEntry.HT_NONE),
                    ivHp = payload.ivHp.coerceIn(-1, 31),
                    ivAtk = payload.ivAtk.coerceIn(-1, 31),
                    ivDef = payload.ivDef.coerceIn(-1, 31),
                    ivSpAtk = payload.ivSpAtk.coerceIn(-1, 31),
                    ivSpDef = payload.ivSpDef.coerceIn(-1, 31),
                    ivSpd = payload.ivSpd.coerceIn(-1, 31),
                    // 归一化：含 "*" 即不限形态，其余 aspect 原样存
                    aspects = if (PokemonBlacklistEntry.ALL_FORMS in payload.aspects) listOf(PokemonBlacklistEntry.ALL_FORMS) else payload.aspects,
                    abilityKey = payload.abilityKey.trim().ifEmpty { null },
                    natureKey = payload.natureKey.trim().ifEmpty { null },
                    itemId = "",
                    totalCount = 1,
                    remainingCount = 1,
                    minPrice = payload.minPrice,
                    maxPrice = payload.maxPrice,
                    // 备注长度服务端钳制（防刷屏/超长 NBT）
                    note = payload.note.take(NOTE_MAX_LENGTH),
                    createdAt = now,
                    expiresAt = now + CobbleMarketConfig.buyOrderExpiryDays * 24L * 60 * 60 * 1000,
                    status = BuyOrderStatus.OPEN,
                    returnedAt = null,
                    currency = currency
                )
                BuyOrderState.get(server).addOrder(order)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.created")))
                broadcastEvent(server, "NEW", buyOrderToEntry(order, server))
                recordOrder(server, order, TransactionType.ORDER)
            }
        }

        registerC2S(CreateItemBuyOrderPayload.ID, CreateItemBuyOrderPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "create_item_buy_order", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                val currency = validateCreateCurrency(player, payload.currency) ?: return@execute
                if (!checkPrices(player, payload.minPrice, payload.maxPrice)) return@execute
                if (!checkOrderLimit(server, player)) return@execute
                // 手持物品模式：用手持物品（含组件快照）作为求购要求；否则用输入框的 itemId
                val heldStack = if (payload.useHeldItem) player.mainHandStack.copy() else net.minecraft.item.ItemStack.EMPTY
                val itemId = if (payload.useHeldItem) {
                    if (heldStack.isEmpty) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.held_item_empty")))
                        return@execute
                    }
                    Registries.ITEM.getId(heldStack.item).toString()
                } else payload.itemId.trim()
                val itemIdentifier = net.minecraft.util.Identifier.tryParse(itemId)
                if (itemIdentifier == null || !Registries.ITEM.getOrEmpty(itemIdentifier).isPresent) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.item_not_found")))
                    return@execute
                }
                // 组件快照：手持路径读主手提取；搜索路径用客户端传的快照重建后重新提取白名单组件（不盲信）
                val itemComponentsSpec = if (payload.useHeldItem) {
                    com.shusheng.cobblemarket.market.ItemRuleComponents.extractSpec(heldStack, player.serverWorld.registryManager)
                } else com.shusheng.cobblemarket.market.ItemRuleComponents.sanitizeSpec(
                    itemIdentifier.toString(), payload.componentsSpec, player.serverWorld.registryManager
                )
                val totalCount = payload.totalCount.coerceAtLeast(1)
                // 冻结金上限钳制：主货币后端是 Int 口径、RP 是 Long，但两种币都不接受这个量级的冻结
                // （正常订单远到不了，超过一律拒绝，防溢出吞钱）
                val frozen = payload.maxPrice.toLong() * totalCount
                if (frozen > Int.MAX_VALUE) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.too_large")))
                    return@execute
                }
                if (!CurrencyService.remove(player, currency, frozen)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.not_enough")))
                    return@execute
                }
                val now = System.currentTimeMillis()
                val order = BuyOrder(
                    id = UUID.randomUUID(),
                    buyerUuid = player.uuid,
                    buyerName = player.name.string,
                    type = BuyOrderType.ITEM,
                    speciesId = null,
                    speciesKey = null,
                    shinyFilter = PokemonBlacklistEntry.SHINY_ANY,
                    htFilter = PokemonBlacklistEntry.HT_ANY,
                    ivHp = -1, ivAtk = -1, ivDef = -1,
                    ivSpAtk = -1, ivSpDef = -1, ivSpd = -1,
                    aspects = emptyList(),
                    abilityKey = null,
                    natureKey = null,
                    itemId = itemIdentifier.toString(),
                    itemComponentsSpec = itemComponentsSpec,
                    totalCount = totalCount,
                    remainingCount = totalCount,
                    minPrice = payload.minPrice,
                    maxPrice = payload.maxPrice,
                    note = payload.note.take(NOTE_MAX_LENGTH),
                    createdAt = now,
                    expiresAt = now + CobbleMarketConfig.buyOrderExpiryDays * 24L * 60 * 60 * 1000,
                    status = BuyOrderStatus.OPEN,
                    returnedAt = null,
                    currency = currency
                )
                BuyOrderState.get(server).addOrder(order)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.created")))
                broadcastEvent(server, "NEW", buyOrderToEntry(order, server))
                recordOrder(server, order, TransactionType.ORDER)
            }
        }

        registerC2S(CancelBuyOrderPayload.ID, CancelBuyOrderPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "cancel_buy_order", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 封禁只拦交易；关闭求购单是取回自己的冻结金，允许（照取消挂单语义）
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                if (order.buyerUuid != player.uuid) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.cmd.not_your_listing")))
                    return@execute
                }
                val refund = order.frozenRemaining()
                if (!state.closeByBuyer(server, order)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                sendToPlayer(
                    player,
                    // 退款金额按**订单自己的币种**（关掉「玩家间 RP 交易」后关单照常，退款也要显示成 RP）
                    MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.closed", CurrencyService.amountText(refund, order.currency)))
                )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                broadcastEvent(server, "CLOSED", buyOrderToEntry(order, server))
                recordOrder(server, order, TransactionType.CANCEL, "user")
            }
        }

        registerC2S(ForceCancelBuyOrderPayload.ID, ForceCancelBuyOrderPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "force_cancel_buy_order", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            if (!MarketPermissions.has(player, MarketPermissions.BUY_ORDER)) return@registerC2S
            val server = player.server
            server.execute {
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                // 下架前记下待确认交付的卖家（closeByBuyer 会退回货物并清空 pending）
                val pendingSellers = order.pendingDeliveries.map { it.sellerUuid }.toSet()
                if (!state.closeByBuyer(server, order)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                // 通知买家（冻结金已退还）与待确认交付的卖家（货物已退回）；离线则入队补发
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                    server, order.buyerUuid,
                    Text.translatable("cobblemarket.buy_order.force_cancelled_buyer").formatted(Formatting.RED)
                )
                pendingSellers.forEach { seller ->
                    com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                        server, seller,
                        Text.translatable("cobblemarket.buy_order.force_cancelled_seller").formatted(Formatting.RED)
                    )
                }
                sendToPlayer(
                    player,
                    MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.force_cancelled_msg", order.buyerName, order.requirementText()))
                )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                broadcastEvent(server, "CLOSED", buyOrderToEntry(order, server))
                recordOrder(server, order, TransactionType.CANCEL, "admin")
            }
        }

        registerC2S(DeliverPokemonBuyOrderPayload.ID, DeliverPokemonBuyOrderPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "deliver_pokemon_buy_order", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.type != BuyOrderType.POKEMON) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                // 不能交付自己的单：自买自卖只会白扣中介费
                if (order.buyerUuid == player.uuid) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.cannot_deliver_own")))
                    return@execute
                }
                // RP 单交付 = 玩家之间用 RP 换东西，过买家侧同一道闸（关掉「玩家间 RP 交易」后，
                // 存量 RP 单不能再交付；关单退款、被退回后取回货物不受影响 —— 那是收回自己的资产）
                if (pointsGateBlocked(player, order.currency)) return@execute
                // 已有待确认交付时锁定新交付（买家确认/拒绝后才解锁）
                if (order.pendingDeliveries.isNotEmpty()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.pending_locked")))
                    return@execute
                }
                if (payload.price < order.minPrice || payload.price > order.maxPrice) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_out_of_range")))
                    return@execute
                }

                val party = Cobblemon.storage.getParty(player)
                val pc = Cobblemon.storage.getPC(player)
                var pokemon = party.find { it.uuid == payload.pokemonUuid }
                val fromParty = pokemon != null
                if (pokemon == null) pokemon = pc.find { it.uuid == payload.pokemonUuid }
                if (pokemon == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                // 对战中不可交付求购单（整个队伍）：战斗系统动态读队伍，抽走任何精灵都可能造成战斗内模型消失或变相复制
                if (com.shusheng.cobblemarket.market.BattleGuard.isPlayerInBattle(player.uuid)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.in_battle")))
                    return@execute
                }
                if (fromParty && party.occupied() <= 1) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.party_last")))
                    return@execute
                }
                if (!order.matchesPokemon(pokemon)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.not_matching")))
                    return@execute
                }
                // 治理校验（照拍卖上架路径）：黑名单 + 携带物黑名单 + 价格限制
                // （价格限制不含特性/性格维度，实际交付精灵按 形态/V数/闪光/特训 匹配规则取 bounds）
                if (PokemonBlacklistState.get(server).isBlacklisted(pokemon)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.blocked")))
                    return@execute
                }
                val heldItem = pokemon.heldItem()
                val heldItemId = if (heldItem.isEmpty) null
                    else Registries.ITEM.getId(heldItem.item).toString()
                if (heldItemId != null && ItemBlacklistState.get(server)
                        .matches(heldItem, player.serverWorld.registryManager)
                ) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.held_item_blocked")))
                    return@execute
                }
                // ⚠ 限价表是**主货币口径**，RP 单整块跳过（拿主货币的上下限去套 RP 价只会误伤，与市场/拍卖同）
                val bounds = if (order.currency == MarketCurrency.PRIMARY) {
                    val pokemonBounds = com.shusheng.cobblemarket.market.PokemonPriceLimitState.get(server)
                        .getPriceBounds(pokemon)
                    val itemBounds = if (heldItemId != null)
                        com.shusheng.cobblemarket.market.ItemPriceLimitState.get(server)
                            .getPriceBounds(heldItem, player.serverWorld.registryManager)
                    else null
                    com.shusheng.cobblemarket.market.mergePriceBounds(pokemonBounds, itemBounds)
                } else null
                if (bounds != null) {
                    // 空区间 = 多条同档限价规则交叉锁死，任何价格都过不了校验：明确告知而不是轮流报上下限
                    if (bounds.isEmptyRange) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.conflict")))
                        return@execute
                    }
                    if (bounds.min != null && payload.price < bounds.min) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_limit_below", CurrencyHandler.formatAmount(bounds.min))))
                        return@execute
                    }
                    if (bounds.max != null && payload.price > bounds.max) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_limit_above", CurrencyHandler.formatAmount(bounds.max))))
                        return@execute
                    }
                }

                // 先完成所有可能失败的操作（序列化 + 数据构建）
                val nbt = try {
                    pokemon.saveToNBT(player.serverWorld.registryManager, NbtCompound())
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to serialize pokemon {} for buy order {} delivery by {}: {}", payload.pokemonUuid, order.id, player.uuid, e.message)
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                val now = System.currentTimeMillis()

                // 副作用阶段：移除精灵 → 构建待确认交付挂入订单（货悬空，买家确认时结算）
                val removed = try {
                    if (fromParty) party.remove(pokemon) else pc.remove(pokemon)
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to remove pokemon {} for buy order {} delivery by {}: {}", payload.pokemonUuid, order.id, player.uuid, e.message)
                    false
                }
                if (!removed) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                // 快照算一次、两处用：存进交付记录 + 给买家那条通知的悬停详情用
                //（2026-09-25：通知原先只挂了点击，悬停一个字都没有）
                val deliveryExtra = buildDeliveryExtra(pokemon, heldItem, player.serverWorld.registryManager, player.server)
                val delivery = PendingDelivery(
                    id = UUID.randomUUID(),
                    sellerUuid = player.uuid,
                    sellerName = player.name.string,
                    price = payload.price,
                    pokemonNbt = nbt,
                    species = pokemon.species.name,
                    level = pokemon.level,
                    shiny = pokemon.shiny,
                    extraData = deliveryExtra,
                    itemNbt = null,
                    count = 1,
                    deliveredAt = now
                )
                order.pendingDeliveries.add(delivery)
                state.markModified()
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.delivered_pending")))
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(server, order.buyerUuid,
                    pendingReviewNotice(order, deliveryExtra))
                broadcastEvent(server, "UPDATED", buyOrderToEntry(order, server))
            }
        }

        registerC2S(DeliverItemBuyOrderPayload.ID, DeliverItemBuyOrderPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "deliver_item_buy_order", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.type != BuyOrderType.ITEM) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.buyerUuid == player.uuid) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.cannot_deliver_own")))
                    return@execute
                }
                // RP 单交付过买家侧同一道闸（理由同精灵交付 handler）
                if (pointsGateBlocked(player, order.currency)) return@execute
                // 已有待确认交付时锁定新交付（买家确认/拒绝后才解锁）
                if (order.pendingDeliveries.isNotEmpty()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.pending_locked")))
                    return@execute
                }
                if (payload.count <= 0 || payload.count > order.remainingCount) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.wrong_count")))
                    return@execute
                }
                if (payload.price < order.minPrice || payload.price > order.maxPrice) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_out_of_range")))
                    return@execute
                }
                // 治理即时生效：蛋交易开关关闭/物品黑名单/价格限制拦截存量订单的交付
                if (isEggItem(order.itemId) && !CobbleMarketConfig.eggTradingEnabled) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.egg_trading_disabled")))
                    return@execute
                }
                // 参考栈 = 客户端所选形态（经 ItemVariantSelectScreen 选择后随包发送的序列化），
                // 服务端重建为权威栈（不信任客户端一致性）。求购单只指定 itemId，但同一次交付
                // 必须组件一致——否则「32 锋利V + 32 保护I」会被按参考栈标准化成 64 个锋利V，
                // 凭空放大价值（拍卖/上架路径同样按组件匹配）。
                val main = player.inventory.main
                val referenceStack = try {
                    ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, payload.variantNbt)
                        .takeIf { !it.isEmpty && Registries.ITEM.getId(it.item).toString() == order.itemId }
                } catch (e: Exception) {
                    null
                }
                if (referenceStack == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }

                // 求购单组件要求校验：买家指定组件快照（如「锋利V」）时，交付物品组件须包含之
                if (!com.shusheng.cobblemarket.market.ItemRuleComponents.matches(
                        referenceStack, order.itemId, order.itemComponentsSpec, player.serverWorld.registryManager
                    )) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.item_variant_not_matching")))
                    return@execute
                }
                // 治理即时生效：物品黑名单/价格限制拦截存量订单的交付（组件粒度）
                if (ItemBlacklistState.get(server).matches(referenceStack, player.serverWorld.registryManager)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.item_blocked")))
                    return@execute
                }
                // 物品价格限制是单价语义：交付单价直接对照 bounds
                // ⚠ 限价表是**主货币口径**，RP 单整块跳过（理由同精灵交付）
                val itemBounds = if (order.currency == MarketCurrency.PRIMARY)
                    com.shusheng.cobblemarket.market.ItemPriceLimitState.get(server)
                        .getPriceBounds(referenceStack, player.serverWorld.registryManager)
                else null
                if (itemBounds != null) {
                    // 空区间 = 多条同档限价规则交叉锁死，任何价格都过不了校验：明确告知而不是轮流报上下限
                    if (itemBounds.isEmptyRange) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.conflict")))
                        return@execute
                    }
                    if (itemBounds.min != null && payload.price < itemBounds.min) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_limit_below", CurrencyHandler.formatAmount(itemBounds.min))))
                        return@execute
                    }
                    if (itemBounds.max != null && payload.price > itemBounds.max) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.price_limit_above", CurrencyHandler.formatAmount(itemBounds.max))))
                        return@execute
                    }
                }

                // 容器内容校验：交付的容器物品内不得含黑名单/限价物品/未开开关的蛋（防塞箱绕过）
                val containerReject = com.shusheng.cobblemarket.market.ContainerTradeCheck.check(referenceStack, server)
                if (containerReject != null) {
                    sendToPlayer(player, MarketResultPayload(false, containerReject))
                    return@execute
                }
                // 背包足额：只统计与参考栈组件一致的部分
                var available = 0
                for (i in 0 until main.size) {
                    val stack = main[i]
                    if (itemsEqualForTrading(stack, referenceStack)) available += stack.count
                }
                if (available < payload.count) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.item_variant_not_enough")))
                    return@execute
                }
                // 物品参考栈 NBT（待领取重建用，带组件物品保真）
                val listingNbt = try {
                    referenceStack.encode(player.serverWorld.registryManager) as? NbtCompound
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to encode item stack for buy order {} delivery by {}: {}", order.id, player.uuid, e.message)
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                if (listingNbt == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }

                // 副作用阶段：扣物品（只扣与参考栈组件一致的，照物品上架路径）
                var remaining = payload.count
                for (i in 0 until main.size) {
                    val stack = main[i]
                    if (itemsEqualForTrading(stack, referenceStack)) {
                        val r = minOf(remaining, stack.count)
                        val before = stack.count
                        stack.decrement(r)
                        // 按「实际扣掉多少」计，而不是「计划扣多少」：若外部模组在数据层拦截了
                        // decrement（静默不生效），实际扣减量为 0 → remaining 不减 → 最终触发下面的
                        // 防御性回滚，不会出现「挂单已入库但物品仍在背包」的虚增。
                        // ⚠ 钳在 [0, r]：静默失败时段 0；外部往该栈加物品时（before-count 可能为负）段 r
                        //   —— 后者保证与改动前行为一致（直接相减会把回滚量算成负数）。
                        remaining -= (before - stack.count).coerceIn(0, r)
                        if (remaining <= 0) break
                    }
                }
                if (remaining > 0) {
                    // 防御性校验：available 预检已保证足额，正常不可达；异常时退还已扣部分
                    giveBackItem(ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, listingNbt).copyWithCount(payload.count - remaining), player)
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                player.inventory.markDirty()
                player.currentScreenHandler.sendContentUpdates()
                val now = System.currentTimeMillis()

                // 构建待确认交付挂入订单（货悬空，买家确认时结算）
                val delivery = PendingDelivery(
                    id = UUID.randomUUID(),
                    sellerUuid = player.uuid,
                    sellerName = player.name.string,
                    price = payload.price,
                    pokemonNbt = null,
                    species = "",
                    level = 0,
                    shiny = false,
                    extraData = emptyMap(),
                    itemNbt = listingNbt,
                    count = payload.count,
                    deliveredAt = now
                )
                order.pendingDeliveries.add(delivery)
                state.markModified()
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.delivered_items_pending", payload.count))
                )
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(server, order.buyerUuid,
                    pendingReviewNotice(order))
                broadcastEvent(server, "UPDATED", buyOrderToEntry(order, server))
            }
        }

        registerC2S(AcceptPendingDeliverPayload.ID, AcceptPendingDeliverPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "accept_pending_deliver", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 接受 = 交易行为，封禁拦截
                if (banBlocked(server, player)) return@execute
                if (marketBlocked(player)) return@execute
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.buyerUuid != player.uuid) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.cmd.not_your_listing")))
                    return@execute
                }
                // 接受 = 冻结金结算给卖家（RP 从买家流向卖家），过买家侧同一道闸。
                // 关掉开关后货仍悬空在订单里，等订单过期会自动退回卖家 —— 不会丢货，也不会有新的 RP 转移
                if (pointsGateBlocked(player, order.currency)) return@execute
                val pending = order.pendingDeliveries.firstOrNull { it.id == payload.deliveryId }
                if (pending == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                val now = System.currentTimeMillis()
                // 货进买家待领取（失败保持 pending 原状，买家可重试）
                val enqueued = state.returnPending(server, order, pending, order.buyerUuid, order.buyerName, ListingStatus.SOLD)
                if (!enqueued) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                // 资金结算：买家冻结金按实际价扣（差价退待领），卖家收实际价-中介费
                val unitDiff = (order.maxPrice - pending.price).toLong()
                val gross = pending.price.toLong() * pending.count
                val fee = deliveryFee(server, order.buyerUuid, gross, now, order.currency)
                // 差价退买家、货款给卖家：主货币进待领余额、RP **直接到账**（见 CurrencyService.creditSeller）。
                // ⚠ RP 不进待领账本 —— 「收款」按钮与角标只统计主货币，这正是「RP 收入直接到账」的落点
                CurrencyService.creditSeller(server, order.buyerUuid, order.currency, unitDiff * pending.count)
                CurrencyService.creditSeller(server, pending.sellerUuid, order.currency, gross - fee)
                // 卖家到账音：交付被接受、钱进了他的待领余额；在线才发（离线由「离线收益」提示兜底）
                server.playerManager.getPlayer(pending.sellerUuid)?.let { sendSound(it, "market_sold") }
                // 金融系统成交挂钩子：求购交付成交计入买家（求购发起方）交易额
                // ⚠ **RP 单不调** —— recordTrade 喂着紫卡/黑卡申请门槛与贷款额度公式，
                //   让 RP 来回刷交易量等于白送资格（方案「统计隔离」）
                if (order.currency == MarketCurrency.PRIMARY) {
                    com.shusheng.cobblemarket.finance.FinanceState.get(server).recordTrade(
                        server, order.buyerUuid, pending.sellerUuid, gross, now
                    )
                }
                order.pendingDeliveries.remove(pending)
                order.remainingCount -= pending.count
                val category = if (order.type == BuyOrderType.POKEMON)
                    com.shusheng.cobblemarket.event.TransactionCategory.POKEMON
                else
                    com.shusheng.cobblemarket.event.TransactionCategory.ITEM
                if (order.remainingCount <= 0) {
                    order.status = BuyOrderStatus.CLOSED
                    order.returnedAt = now
                    state.removeOrder(order.id)
                }
                state.markModified()
                try {
                    com.shusheng.cobblemarket.event.TransactionHistory.get(server).addRecord(
                        com.shusheng.cobblemarket.event.TransactionRecord(
                            timestamp = now,
                            type = com.shusheng.cobblemarket.event.TransactionType.PURCHASE,
                            category = category,
                            sellerUuid = pending.sellerUuid,
                            sellerName = pending.sellerName,
                            buyerUuid = order.buyerUuid,
                            buyerName = order.buyerName,
                            species = if (order.type == BuyOrderType.POKEMON)
                                (pending.extraData["speciesKey"] ?: pending.species)
                            else order.itemId,
                            price = gross.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            fee = fee.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            currency = order.currency,
                            detail = if (order.type == BuyOrderType.POKEMON)
                                com.shusheng.cobblemarket.util.RecordDetail.pokemon(pending.extraData, pending.level, pending.shiny)
                            else
                                com.shusheng.cobblemarket.util.RecordDetail.item(pending.itemNbt, pending.count, server.registryManager)
                        )
                    )
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.warn("Failed to record buy order accepted delivery {}: {}", order.id, e.message)
                }
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.accepted")))
                // 买家庆祝动画：接受交付即所有权转移（货进买家待领取，精灵单固定 1 只）
                if (order.type == BuyOrderType.POKEMON) {
                    CelebrationNetwork.sendFromEntry(player, pending.species, pending.shiny, pending.extraData, CelebrationSource.BUY_ORDER)
                } else {
                    // 物品求购单：交付的物品栈与数量（待交付记录里存着）
                    pending.itemNbt?.let {
                        CelebrationNetwork.sendItem(player, it, pending.count, CelebrationSource.BUY_ORDER)
                    }
                }
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(server, pending.sellerUuid,
                    // 到账金额 + 手续费一并提示：买家付的是 gross，卖家到手 gross-fee，不说清会产生疑问。
                    // 金额单位取**订单自己的币种**（RP 单显示 RP 图标，卖家才知道到账的是哪种钱）
                    Text.translatable("cobblemarket.buy_order.accepted_seller", order.requirementText(),
                        CurrencyService.amountText(gross - fee, order.currency),
                        CurrencyService.amountText(fee, order.currency)).formatted(Formatting.GREEN))
                broadcastEvent(server, if (order.isOpen()) "UPDATED" else "CLOSED", buyOrderToEntry(order, server))
            }
        }

        registerC2S(RejectPendingDeliverPayload.ID, RejectPendingDeliverPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "reject_pending_deliver", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 拒绝 = 货退回卖家，不涉及买家资产变动，封禁不拦（照取消挂单语义）
                settleExpiredAndBroadcast(server)
                val state = BuyOrderState.get(server)
                val order = state.getOrder(payload.orderId)
                if (order == null || !order.isOpen()) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.ended")))
                    return@execute
                }
                if (order.buyerUuid != player.uuid) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.cmd.not_your_listing")))
                    return@execute
                }
                val pending = order.pendingDeliveries.firstOrNull { it.id == payload.deliveryId }
                if (pending == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                // 货退回卖家待领取（失败保持 pending 原状，买家可重试）
                val enqueued = state.returnPending(server, order, pending, pending.sellerUuid, pending.sellerName, ListingStatus.CANCELLED)
                if (!enqueued) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed")))
                    return@execute
                }
                order.pendingDeliveries.remove(pending)
                state.markModified()
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.buy_order.rejected")))
                // 卖家反馈：被退回 + 买家留言（有留言时附上；卖家离线则上线补发）
                val reason = payload.reason.take(100)
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(server, pending.sellerUuid,
                    if (reason.isNotBlank())
                        Text.translatable("cobblemarket.buy_order.rejected_seller_reason",
                            order.buyerName, order.requirementText(), reason).formatted(Formatting.YELLOW)
                    else
                        Text.translatable("cobblemarket.buy_order.rejected_seller",
                            order.buyerName, order.requirementText()).formatted(Formatting.YELLOW)
                )
                broadcastEvent(server, "UPDATED", buyOrderToEntry(order, server))
            }
        }
    }

    // ── 共享辅助 ──

    /** 结算到期求购单并广播 CLOSED 事件（数据请求入口与定时器共用，实现惰性+周期双结算） */
    fun settleExpiredAndBroadcast(server: MinecraftServer) {
        val closed = BuyOrderState.get(server).settleExpiredOrders(server, System.currentTimeMillis())
        if (closed.isNotEmpty()) {
            // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
            com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
        }
        closed.forEach { order ->
            broadcastEvent(server, "CLOSED", buyOrderToEntry(order, server))
            com.shusheng.cobblemarket.market.OfflineMessageState.notify(server, order.buyerUuid,
                Text.translatable("cobblemarket.buy_order.expired", order.requirementText()).formatted(Formatting.YELLOW))
            recordOrder(server, order, TransactionType.CANCEL, "expired")
        }
    }

    private fun broadcastEvent(server: MinecraftServer, event: String, entry: BuyOrderEntry) {
        server.playerManager.playerList.forEach { p ->
            sendToPlayer(p, BuyOrderEventPayload(event, entry))
        }
    }

    /**
     * 中介费：成交总额 × 费率，向上取整（照拍卖成交费算法，**按订单币种选费率**）。
     *
     * ⚠ RP 单只按 `pointsBuyOrderFeePercent` 收，**卡片折扣与逾期翻倍一概不参与** ——
     * 那两样都是金融系统的东西（持卡等级、贷款逾期），只跟主货币有关，混进来等于拿主货币的
     * 金融身份去影响 RP 费率（同 CurrencyService.supportsFeeDiscount 的口径）。
     *
     * [payerUuid] 是**买家**：逾期翻倍看的是买家的金融状态（照原实现），钱仍从卖家所得里扣。
     */
    private fun deliveryFee(
        server: MinecraftServer,
        payerUuid: java.util.UUID,
        gross: Long,
        now: Long,
        currency: MarketCurrency
    ): Long {
        val isPoints = currency == MarketCurrency.POINTS
        val feePercent = if (isPoints) CobbleMarketConfig.pointsBuyOrderFeePercent
        else CobbleMarketConfig.buyOrderFeePercent
        if (feePercent <= 0) return 0L
        val base = Math.ceil(gross * feePercent / 100.0).toLong().coerceAtMost(Int.MAX_VALUE.toLong())
        if (isPoints) return base
        return com.shusheng.cobblemarket.finance.FinanceService.applyHolderDiscount(
            com.shusheng.cobblemarket.finance.FinanceState.get(server),
            payerUuid,
            com.shusheng.cobblemarket.finance.FinanceService.applyFeeMultiplier(
                com.shusheng.cobblemarket.finance.FinanceState.get(server), payerUuid, now, base
            )
        )
    }

    private fun checkPrices(player: net.minecraft.server.network.ServerPlayerEntity, minPrice: Int, maxPrice: Int): Boolean {
        if (minPrice >= 1 && maxPrice >= minPrice) return true
        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_price")))
        return false
    }

    /**
     * 求购单数量上限（精灵与物品合计，照拍卖 maxAuctionsPerPlayer）。
     * 求购单列表是全量下发给所有客户端的，没有上限时单个玩家就能把全服的列表撑大。
     */
    private fun checkOrderLimit(server: MinecraftServer, player: net.minecraft.server.network.ServerPlayerEntity): Boolean {
        val max = CobbleMarketConfig.maxBuyOrdersPerPlayer
        if (max > 0 && BuyOrderState.get(server).countOpenByBuyer(player.uuid) >= max) {
            sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.buy_order.max_active", max)))
            return false
        }
        return true
    }

    private fun banBlocked(server: MinecraftServer, player: net.minecraft.server.network.ServerPlayerEntity): Boolean {
        val banInfo = BanState.get(server).getBanInfo(player.uuid, System.currentTimeMillis())
        if (banInfo == null) return false
        // 保留 Text 对象而非 .string：翻译在客户端语言下渲染
        val timeDesc: Text = if (banInfo.isPermanent)
            Text.translatable("cobblemarket.ban.permanent")
        else
            Text.translatable("cobblemarket.ban.remaining", BanState.formatRemaining(banInfo.expiresAt!! - System.currentTimeMillis()))
        val banMsg = if (banInfo.reason.isNotBlank())
            Text.translatable("cobblemarket.ban.banned_msg_time_reason", timeDesc, com.shusheng.cobblemarket.market.BanState.reasonText(banInfo.reason))
        else
            Text.translatable("cobblemarket.ban.banned_msg_time", timeDesc)
        sendToPlayer(player, MarketResultPayload(false, banMsg))
        return true
    }

    private fun resolvedSpeciesKey(resolvedSpeciesId: String): String? {
        val species = com.cobblemon.mod.common.api.pokemon.PokemonSpecies.implemented
            .firstOrNull { it.resourceIdentifier.toString() == resolvedSpeciesId }
        if (species == null) {
            CobbleMarket.LOGGER.warn("Resolved species {} not found in registry", resolvedSpeciesId)
            return null
        }
        return com.shusheng.cobblemarket.util.SpeciesText.translationKey(species)
    }

    /** 交付精灵的待领取展示字段（照拍卖/上架的 extraData 字段集，保证待领取列表渲染一致） */
    private fun buildDeliveryExtra(
        pokemon: com.cobblemon.mod.common.pokemon.Pokemon,
        heldItemStack: ItemStack,
        registryManager: net.minecraft.registry.RegistryWrapper.WrapperLookup,
        // 初始训练家要把玩家 UUID 解析成名字（`PokemonOT`），故需要 server
        server: MinecraftServer
    ): Map<String, String> {
        val htIvs = pokemon.ivs.hyperTrainedIVs // 极限特训值表（-1 = 未特训）
        val extra = mutableMapOf(
            "speciesId" to pokemon.species.resourceIdentifier.toString(),
            "speciesName" to pokemon.species.translatedName.string,
            "speciesKey" to com.shusheng.cobblemarket.util.SpeciesText.translationKey(pokemon.species),
            "primaryType" to "cobblemon.type.${pokemon.primaryType.name.lowercase()}",
            // 太晶属性（官方字段 `Pokemon.teraType`）：**常驻下发** —— 理由见 MarketNetwork.buildListingExtra
            // 的同名键（别写回「只在 ≠ 主属性时才下发」，那条被用户否过）
            "teraType" to "cobblemon.type.${pokemon.teraType.id.path}",
            "ivsHp" to pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.HP].toString(),
            "ivsAtk" to pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK].toString(),
            "ivsDef" to pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE].toString(),
            "ivsSpAtk" to pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK].toString(),
            "ivsSpDef" to pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE].toString(),
            "ivsSpd" to pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED].toString(),
            "htHp" to (htIvs[com.cobblemon.mod.common.api.pokemon.stats.Stats.HP] ?: -1).toString(),
            "htAtk" to (htIvs[com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK] ?: -1).toString(),
            "htDef" to (htIvs[com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE] ?: -1).toString(),
            "htSpAtk" to (htIvs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK] ?: -1).toString(),
            "htSpDef" to (htIvs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE] ?: -1).toString(),
            "htSpd" to (htIvs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED] ?: -1).toString(),
            "nature" to "cobblemon.nature.${pokemon.effectiveNature.name.path}",
            // 原生性格（薄荷不改）：与 nature 不同 = 用过薄荷，客户端斜体显示
            "natureBase" to "cobblemon.nature.${pokemon.nature.name.path}",
            "ability" to "cobblemon.ability.${pokemon.ability.name}",
            "gender" to pokemon.gender.name,
            "ball" to "item.${pokemon.caughtBall.name.namespace}.${pokemon.caughtBall.name.path}",
            "ballItem" to pokemon.caughtBall.name.toString(),
            // 携带物：**总是**下发 —— 行信息 / 弹窗 / 图标 / 大图都要如实显示它带了什么。
            // 「精灵模型手里拿不拿」是另一回事，由 heldItemVisible 单独下发、客户端渲染模型时读
            //（用户 2026-09-26 拍板：隐藏只管模型；此前隐藏就整个不下发，交易信息凭空少一行）
            "heldItemId" to if (heldItemStack.isEmpty) "" else Registries.ITEM.getId(heldItemStack.item).toString(),
            "heldItemNbt" to encodeHeldItemNbt(heldItemStack, registryManager),
            "heldItemVisible" to pokemon.heldItemVisible.toString(),
            "aspects" to pokemon.aspects.joinToString(","),
            // 体型分类（XS/S/M/L/XL/ALPHA）：待交付弹窗名字右侧那个徽章用。
            // ⚠ 键名 `size` 与拍卖侧（`auctionToEntry`）保持一致，两处客户端读的也是同一个键
            "size" to com.shusheng.cobblemarket.util.PokemonSize.from(pokemon),
            "marks" to pokemon.marks.map { it.texture.toString() }.joinToString(","),
            // 技能槽快照（4 个槽用「,」连接、空槽空串）：悬停窗技能块用，见 MoveText
            "moves" to com.shusheng.cobblemarket.util.MoveText.of(pokemon),
            // 极巨化 / 初始训练家：交付的是**活精灵**，直接从 Pokemon 取。这份快照同时喂给
            // 「交付通知的悬停」（buildDeliveryHover）与「待交付弹窗」（pendingToListing）—— 两处都要显示
            "gmaxFactor" to pokemon.gmaxFactor.toString(),
            "dmaxLevel" to pokemon.dmaxLevel.toString(),
            "ot" to com.shusheng.cobblemarket.util.PokemonOT.from(pokemon, server)
        )
        pokemon.secondaryType?.let { extra["secondaryType"] = "cobblemon.type.${it.name.lowercase()}" }
        return extra
    }

    /** 背包返还兜底（照上架路径的退还逻辑） */
    private fun giveBackItem(stack: ItemStack, player: net.minecraft.server.network.ServerPlayerEntity) {
        if (stack.isEmpty) return
        player.inventory.insertStack(stack)
        if (!stack.isEmpty) {
            // 背包满兜底：直接丢到玩家脚下，绝不吞玩家物品
            val dropped = player.dropItem(stack, true)
            if (dropped != null) dropped.setToDefaultPickupDelay()
        }
        player.inventory.markDirty()
    }

    /** CSV 账本记录：发布=求购（ORDER，发起者占卖家列）、关闭/过期/强制下架=下架（reason 区分）；交付成交已有 PURCHASE 记录 */
    private fun recordOrder(server: MinecraftServer, order: BuyOrder, type: TransactionType, reason: String? = null) {
        val category = if (order.type == BuyOrderType.POKEMON) TransactionCategory.POKEMON else TransactionCategory.ITEM
        TransactionHistory.get(server).addRecord(TransactionRecord(
            timestamp = System.currentTimeMillis(),
            type = type,
            category = category,
            // 求购单无货物：发起者（买家）占卖家列，对账按名字筛选
            sellerUuid = order.buyerUuid,
            sellerName = order.buyerName,
            buyerUuid = null,
            buyerName = "",
            species = if (order.type == BuyOrderType.POKEMON) (order.speciesKey ?: "any") else order.itemId,
            price = order.maxPrice,
            fee = 0,
            currency = order.currency,
            detail = reason ?: ""
        ))
    }

    /**
     * 交付精灵的**悬停详情**（多行彩色文字）—— 对标拍卖播报的 `AuctionNetwork.buildAnnouncementHover`。
     *
     * ⚠ **两边的 extra 字段不同，不能直接复用那支函数**：交付快照有 `ht*`（特训）与 `marks`，
     * 没有 `evs*` / `friendship` / `markKeys` —— 硬套会画出一堆 0 和空行。
     *
     * ⚠ 球名必须走**稀有度色**（大师球在物品栏里是紫的，直接念词条 key 出来是白字）——
     * 与悬停窗、拍卖播报同一口径（2026-09-25 用户报「聊天悬停球种没做颜色」那次一并收的）。
     *
     * 物品交付（`extraData` 是空 map）只给一行物品名：买家看名字就够，
     * 具体数值进界面看 —— 那条路本来就通向确认交付弹窗。
     */
    private fun buildDeliveryHover(
        order: com.shusheng.cobblemarket.market.BuyOrder,
        extra: Map<String, String>?
    ): Text? {
        val lines = mutableListOf<Text>()
        if (extra.isNullOrEmpty()) {
            if (order.itemId.isEmpty()) return null
            com.shusheng.cobblemarket.util.ItemNameText.rarityColoredNameById(order.itemId)?.let { lines.add(it) }
            return joinHoverLines(lines)
        }
        // 属性行（主 + 副）：属性名按属性色 —— 与拍卖播报 / 出价弹窗同一口径
        extra["primaryType"]?.takeIf { it.isNotEmpty() }?.let { primary ->
            lines.add(
                Text.translatable("cobblemarket.gui.tooltip_type").append(
                    Text.translatable(primary).styled { s -> s.withColor(TypeTextColors.color(primary)) }
                ).append(
                    extra["secondaryType"]?.takeIf { it.isNotEmpty() }?.let { secondary ->
                        Text.literal(" + ").append(
                            Text.translatable(secondary).styled { s -> s.withColor(TypeTextColors.color(secondary)) }
                        )
                    } ?: Text.literal("")
                ).append(
                    // 太晶属性：官方 `Pokemon.teraType` 默认 = 主属性 ⇒ 只在 ≠ 主属性时才有值，
                    // 空串 = 整段不画（口径同拍卖播报）
                    extra["teraType"].orEmpty().takeIf { it.isNotEmpty() }?.let { tera ->
                        Text.translatable(
                            "cobblemarket.gui.tooltip_tera",
                            Text.translatable(tera).styled { s -> s.withColor(TypeTextColors.color(tera)) }
                        )
                    } ?: Text.literal("")
                )
            )
        }
        // 极巨行：属性行下面、有才显示（与各处界面走同一个组装入口）
        com.shusheng.cobblemarket.util.PokemonExtraLines.dynamax(
            extra["gmaxFactor"] == "true", extra["dmaxLevel"]?.toIntOrNull() ?: 0
        )?.let { lines.add(it) }
        // 初始训练家：⚠ 这条悬停**没有亲密度行**（交付快照不含 friendship）⇒ OT 贴着极巨行放；
        // 其余界面（有亲密度的）都是"亲密度下面" —— 位置差异是刻意的，别无脑对齐
        com.shusheng.cobblemarket.util.PokemonExtraLines.originalTrainer(extra["ot"].orEmpty())
            ?.let { lines.add(it) }
        // 性格 / 特性 / 球种
        extra["nature"]?.takeIf { it.isNotEmpty() }
            ?.let { lines.add(Text.translatable("cobblemarket.gui.tooltip_nature").append(Text.translatable(it))) }
        extra["ability"]?.takeIf { it.isNotEmpty() }
            ?.let { lines.add(Text.translatable("cobblemarket.gui.tooltip_ability").append(Text.translatable(it))) }
        extra["ball"]?.takeIf { it.isNotEmpty() }
            ?.let { lines.add(com.shusheng.cobblemarket.util.ItemNameText.ballLine(it, extra["ballItem"].orEmpty())) }
        // 携带物（稀有度色，与物品栏悬停一致）
        extra["heldItemId"].orEmpty().takeIf { it.isNotEmpty() }
            ?.let { com.shusheng.cobblemarket.util.ItemNameText.rarityColoredNameById(it) }
            ?.let {
                val line = Text.translatable("cobblemarket.gui.tooltip_held").append(it)
                // 玩家把携带物设成「隐藏」时注明一句：模型上不显示它、而这里照常列出（用户 2026-09-26）
                if (extra["heldItemVisible"] == "false") {
                    line.append(Text.translatable("cobblemarket.gui.held_hidden").formatted(Formatting.GRAY))
                }
                lines.add(line)
            }
        // IV 六行：交付快照**没有 EV**，所以只画「stat:IV」那半段（拍卖播报那边是 IV + EV）；
        // 特训过的补一个绿色的 +N，否则买家看不出这只被特训过
        lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs"))
        val stats = listOf(
            Triple("HP", "ivsHp", "htHp") to Formatting.GREEN,
            Triple("Atk", "ivsAtk", "htAtk") to Formatting.RED,
            Triple("Def", "ivsDef", "htDef") to Formatting.GOLD,
            Triple("SpA", "ivsSpAtk", "htSpAtk") to Formatting.BLUE,
            Triple("SpD", "ivsSpDef", "htSpDef") to Formatting.GREEN,
            Triple("Spe", "ivsSpd", "htSpd") to Formatting.LIGHT_PURPLE
        )
        stats.forEach { (keys, color) ->
            val (label, ivKey, htKey) = keys
            var line = Text.literal("  ").append(Text.literal(label))
                .append(Text.literal(": ${extra[ivKey]?.toIntOrNull() ?: 0}"))
                .formatted(color)
            val ht = extra[htKey]?.toIntOrNull() ?: -1
            if (ht > 0) {
                line = line.append(
                    Text.literal(" (+$ht)").styled { s -> s.withColor(0x55FF55) }
                )
            }
            lines.add(line)
        }
        // 技能（两行、一行两个）—— 与悬停窗**同源**（MoveText.rows，词条由客户端本地翻译）；
        // 交付快照一定有 moves，`resolve` 的第二个参数（NBT 兜底）传 null 即可
        com.shusheng.cobblemarket.util.MoveText
            .rows(com.shusheng.cobblemarket.util.MoveText.resolve(extra["moves"], null))
            .forEach { row -> if (row.string.isNotEmpty()) lines.add(row) }
        return joinHoverLines(lines)
    }

    /** 悬停详情多行合并：段间插 `\n`（原版 tooltip 按行渲染） */
    private fun joinHoverLines(lines: List<Text>): Text? {
        if (lines.isEmpty()) return null
        var result: net.minecraft.text.MutableText = Text.literal("")
        lines.forEachIndexed { i, line ->
            if (i > 0) result = result.append(Text.literal("\n"))
            result = result.append(line)
        }
        return result
    }

    /** 求购单待确认通知（绿，聊天颜色模板）+ 金色下划线 [查看待交付] 按钮：
     *  点击走客户端指令 /cobblemarket review <orderId> <币种>，打开界面直达确认交付弹窗（看货后再决定接受/拒绝）。
     *
     *  ⚠ 命令末尾**必须带上这一单的币种**：求购单界面一次只看一种货币，不带币种时客户端只能先查主货币、
     *  发现没有这一单再换另一种重查（多一轮往返 + 等节流窗口，实测反馈「弹窗慢半拍」）。带币种一次命中。
     *  客户端对多出来的这一段是容错的（悬停预览只取前两段，见 ChatObjectPreview）。
     *
     *  ⚠ **悬停也要给详情**（2026-09-25 用户报）：原先这条通知**只挂了点击**，悬停一个字都没有 ——
     *  买家在聊天里看不出卖家交的是什么（拍卖播报的悬停早就有完整详情，两边不一致）。
     *  交付那一刻的精灵快照就在手上（`buildDeliveryExtra` 的返回值），直接挂上去即可。 */
    private fun pendingReviewNotice(
        order: com.shusheng.cobblemarket.market.BuyOrder,
        deliveryExtra: Map<String, String>? = null
    ): Text {
        val hover = buildDeliveryHover(order, deliveryExtra)
        val button = Text.translatable("cobblemarket.chat.review_button").styled {
            var style = it.withColor(Formatting.GOLD)
                .withUnderline(true)
                .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cobblemarket review ${order.id} ${order.currency.name}"))
            if (hover != null) style = style.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
            style
        }
        // 需求里的**物种名按主属性色**（全模组统一口径，同拍卖播报）：
        // 下面整行 `.formatted(GREEN)` 会把名字一起染绿，而**子样式优先于父级** —— 显式给名字上色即可。
        // 物品名与「任意精灵」不上色（没有属性色可言），保持通知绿
        val speciesId = order.speciesId
        val typeKey = if (order.type == BuyOrderType.POKEMON && speciesId != null) {
            com.cobblemon.mod.common.api.pokemon.PokemonSpecies.implemented
                .firstOrNull { it.resourceIdentifier.toString() == speciesId }
                ?.let { species ->
                    // ⚠ 必须按**形态**取属性，不能拿基础物种：阿罗拉六尾的基础物种是火系，它自己和大图里的
                    // 模型却是冰系 —— 用物种属性会做出「图是冰、字是火」的自相矛盾。
                    // 解析规则直接复用 Cobblemon 自己的 getForm（形态的 aspects 全包含在给定 aspects 里 → 取最后一个），
                    // 与客户端给大图建模型时的解析口径一致；形态没定义主属性时 FormData 会回落到物种属性
                    val aspects = if (com.shusheng.cobblemarket.market.PokemonBlacklistEntry.ALL_FORMS in order.aspects)
                        emptySet() else order.aspects.toSet()
                    "cobblemon.type.${species.getForm(aspects).primaryType.name.lowercase()}"
                }
        } else null
        val requirement = if (typeKey != null) {
            // ⚠ 得先 copy()：requirementText() 返回的是 Text 接口，`styled` 只在 MutableText 上
            order.requirementText().copy().styled { style ->
                style.withColor(com.shusheng.cobblemarket.util.TypeTextColors.color(typeKey))
            }
        } else order.requirementText()
        return Text.translatable("cobblemarket.buy_order.pending_buyer", requirement).formatted(Formatting.GREEN)
            .append(Text.literal(" ")).append(button)
    }
}
