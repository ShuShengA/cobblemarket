package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.permission.MarketPermissions
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.shusheng.cobblemarket.config.CurrencyHandler
import com.shusheng.cobblemarket.util.PokemonSize
import com.shusheng.cobblemarket.util.RequestThrottle
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.market.BanState
import com.shusheng.cobblemarket.market.ItemListing
import com.shusheng.cobblemarket.market.ItemMarketState
import com.shusheng.cobblemarket.market.ListingStatus
import com.shusheng.cobblemarket.market.MarketListing
import com.shusheng.cobblemarket.market.MarketState
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.PointsAuditLogger
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer

import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID

// ── Data transfer objects ──

data class ListingEntry(
    val id: UUID,
    val sellerUuid: UUID,
    val species: String,
    val speciesId: String,
    val level: Int,
    val shiny: Boolean,
    val price: Int,
    val sellerName: String,
    val primaryType: String,
    val secondaryType: String,
    val ivsHp: Int, val ivsAtk: Int, val ivsDef: Int,
    val ivsSpAtk: Int, val ivsSpDef: Int, val ivsSpd: Int,
    // 极限特训值（hyper training，-1 = 未特训）：显示「真实值（特训值）」用
    val htHp: Int, val htAtk: Int, val htDef: Int,
    val htSpAtk: Int, val htSpDef: Int, val htSpd: Int,
    // 努力值（EV）：上架时的训练快照，详情显示用
    val evsHp: Int, val evsAtk: Int, val evsDef: Int,
    val evsSpAtk: Int, val evsSpDef: Int, val evsSpd: Int,
    /** 亲密度（上架时快照） */
    val friendship: Int,
    val nature: String,
    val natureBase: String,    // 原生性格（与 nature 不同 = 用过薄荷）
    val ability: String,
    val gender: String,
    val ball: String,
    val ballItem: String,
    val heldItemId: String,
    /** 携带物完整 NBT（SNBT，仅带组件时非空）：客户端据此重建图标，否则丢附魔光效/自定义名。
     *  旧数据无此字段 = 空串，客户端回退按 ID 建图标 */
    val heldItemNbt: String = "",
    val currencyName: String,
    val aspects: List<String>, // 精灵形态（性别/地区等），客户端渲染 3D 图标用
    /** 拥有的证章 id 列表（外观展示用，证章不影响能力；上架时快照） */
    val marks: List<String> = emptyList(),
    /** 体型分类（XS/S/M/L/XL/ALPHA），空串 = 无信息（旧数据） */
    val sizeCategory: String = "",
    /**
     * 这条挂单的币种（枚举名，服务端权威值）。
     *
     * ⚠ **币种是挂单自己的属性，不是"这一屏"的属性** —— 界面显示价格单位必须读它，
     * 别读界面上的什么"当前币种"状态：那样任何一处漏改就会把 RP 商品标成主货币
     *（购买弹窗、悬停窗、待领取列表都是公开渲染工具，漏一处就是误导玩家）。
     * 客户端解析走 `MarketCurrency.fromStored`（服务端发的，容错即可，别用 fromRequest）。
     *
     * **故意不给默认值**：服务端哪一处漏填就编译不过，好过静默当成主货币。
     */
    val currency: String,
    /**
     * 技能槽快照：**4 个槽用「,」连接、空槽是空串**（形如 `thunderbolt,quickattack,,`），
     * 见 [com.shusheng.cobblemarket.util.MoveText]。旧数据 = 空串 ⇒ 悬停窗技能块整块不画。
     */
    val moves: String = "",
    /**
     * 携带物在**精灵模型上是否渲染**（`Pokemon.heldItemVisible`，玩家可在 Cobblemon 队伍详情里关掉它）。
     * ⚠ 只管**模型手里**：行信息 / 弹窗 / 图标 / 大图一律照常显示（用户 2026-09-26 拍板——
     *   隐藏是「不渲染在模型上」，不是「没带东西」，交易信息不该跟着消失）。旧数据没有这一位 = true
     */
    val heldItemVisible: Boolean = true,
    /**
     * 太晶属性翻译 key（`cobblemon.type.xxx`）—— **空串 = 没有**（不显示，例如老挂单）。
     *
     * 服务端**常驻下发**（官方 `Pokemon.teraType`，默认值就是主属性）。
     * ⚠ 曾经写成「只在 ≠ 主属性时才下发」，被用户否掉了：那等于把它定义成**附属模组专属功能**，
     *   而**玩家不一定知道这只精灵的主属性是哪个**（异兽、形态变化尤其）—— 太晶本身就该写明。
     */
    val teraType: String = "",
    /**
     * 超极巨化（官方 `Pokemon.gmaxFactor`）：变的**是不是**超极巨（外观 + 专属 G-Max 招式）。
     * ⚠ 与 [dmaxLevel] **不是二选一** —— 超极巨走同一条 HP 倍率公式，所以两者可以同时有值。
     * 旧数据 / 普通精灵没有这一位 = false。
     */
    val gmaxFactor: Boolean = false,
    /**
     * 极巨等级（官方 `Pokemon.dmaxLevel`）：极巨化时 HP 倍率 = 1.5 + 0.05×等级；0 = 没喂过。
     * 服务端从挂单自带的完整 NBT **现读**（同 [sizeCategory] / [moves] 的套路）
     * ⇒ 升级上来的老挂单也显示，玩家不用重新上架。
     */
    val dmaxLevel: Int = 0,
    /**
     * 初始训练家（官方 `Pokemon.originalTrainer`）的**显示名** —— 服务端已把玩家 UUID 解析成
     * 玩家名（见 [com.shusheng.cobblemarket.util.PokemonOT]），假训练家直接用原名。
     * **空串 = 没有 / 解析不到**（那一行整行不画）。
     */
    val originalTrainer: String = ""
) {
    /**
     * 这条挂单是不是 RP 计价 —— **渲染货币单位就读它**。
     * 构造时解析一次（行渲染每帧都判断，别每帧重新 parse 字符串）。
     */
    val isPoints: Boolean = MarketCurrency.fromStored(currency) == MarketCurrency.POINTS

    fun write(buf: PacketByteBuf) {
        buf.writeUuid(id)
        buf.writeUuid(sellerUuid)
        buf.writeString(species)
        buf.writeString(speciesId)
        buf.writeInt(level)
        buf.writeBoolean(shiny)
        buf.writeInt(price)
        buf.writeString(sellerName)
        buf.writeString(primaryType)
        buf.writeString(secondaryType)
        buf.writeInt(ivsHp); buf.writeInt(ivsAtk); buf.writeInt(ivsDef)
        buf.writeInt(ivsSpAtk); buf.writeInt(ivsSpDef); buf.writeInt(ivsSpd)
        buf.writeInt(htHp); buf.writeInt(htAtk); buf.writeInt(htDef)
        buf.writeInt(htSpAtk); buf.writeInt(htSpDef); buf.writeInt(htSpd)
        buf.writeInt(evsHp); buf.writeInt(evsAtk); buf.writeInt(evsDef)
        buf.writeInt(evsSpAtk); buf.writeInt(evsSpDef); buf.writeInt(evsSpd)
        buf.writeInt(friendship)
        buf.writeString(nature)
        buf.writeString(natureBase)
        buf.writeString(ability)
        buf.writeString(gender)
        buf.writeString(ball)
        buf.writeString(ballItem)
        buf.writeString(heldItemId)
        buf.writeString(heldItemNbt)
        buf.writeString(currencyName)
        buf.writeVarInt(aspects.size); aspects.forEach { buf.writeString(it) }
        buf.writeVarInt(marks.size); marks.forEach { buf.writeString(it) }
        buf.writeString(sizeCategory)
        // ⚠ 字段顺序必须与 read 一致：sizeCategory → currency → moves
        buf.writeString(currency)
        buf.writeString(moves)
        buf.writeBoolean(heldItemVisible)
        buf.writeString(teraType)
        // ⚠ 新增字段一律**追加在末尾**（读端严格同序）；两端必须同步升级
        buf.writeBoolean(gmaxFactor)
        buf.writeInt(dmaxLevel)
        buf.writeString(originalTrainer)
    }

    companion object {
        fun read(buf: PacketByteBuf): ListingEntry = ListingEntry(
            id = buf.readUuid(),
            sellerUuid = buf.readUuid(),
            species = buf.readString(),
            speciesId = buf.readString(),
            level = buf.readInt(),
            shiny = buf.readBoolean(),
            price = buf.readInt(),
            sellerName = buf.readString(),
            primaryType = buf.readString(),
            secondaryType = buf.readString(),
            ivsHp = buf.readInt(), ivsAtk = buf.readInt(), ivsDef = buf.readInt(),
            ivsSpAtk = buf.readInt(), ivsSpDef = buf.readInt(), ivsSpd = buf.readInt(),
            htHp = buf.readInt(), htAtk = buf.readInt(), htDef = buf.readInt(),
            htSpAtk = buf.readInt(), htSpDef = buf.readInt(), htSpd = buf.readInt(),
            evsHp = buf.readInt(), evsAtk = buf.readInt(), evsDef = buf.readInt(),
            evsSpAtk = buf.readInt(), evsSpDef = buf.readInt(), evsSpd = buf.readInt(),
            friendship = buf.readInt(),
            nature = buf.readString(),
            natureBase = buf.readString(),
            ability = buf.readString(),
            gender = buf.readString(),
            ball = buf.readString(),
            ballItem = buf.readString(),
            heldItemId = buf.readString(),
            heldItemNbt = buf.readString(),
            currencyName = buf.readString(),
            aspects = (0 until buf.readVarInt()).map { buf.readString() },
            marks = (0 until buf.readVarInt()).map { buf.readString() },
            sizeCategory = buf.readString(),
            currency = buf.readString(),
            moves = buf.readString(),
            heldItemVisible = buf.readBoolean(),
            teraType = buf.readString(),
            // 与 write 端严格同序（write 在 teraType 之后）—— 用具名参数防错位
            gmaxFactor = buf.readBoolean(),
            dmaxLevel = buf.readInt(),
            originalTrainer = buf.readString()
        )
    }
}

// ── S2C: 市场总开关状态（登录补发 + /market on|off 切换时全员广播） ──

data class MarketStatePayload(val enabled: Boolean) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<MarketStatePayload>(CobbleMarket.id("market_state"))
        val CODEC: PacketCodec<PacketByteBuf, MarketStatePayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.enabled) },
            { b -> MarketStatePayload(b.readBoolean()) }
        )
    }
}

// ── S2C: 待领取概况（登录时推一次） ──

/** 上线时的待办概况（待领取数量 + 有无待领余额）：客户端据此在**世界加载完成后**响一声
 *  「离线收益」提示音——玩家刚上线还没打开市场，这时提醒才有意义；等他开了市场界面，
 *  界面上本来就有「收款」「待领取」按钮和红点，再响就是多余。
 *  一次上线只提示一次（客户端用时间戳调度，见 MarketStateCache.offlineRewardAt）。 */
data class PendingReturnsPayload(val count: Int, val hasBalance: Boolean) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<PendingReturnsPayload>(CobbleMarket.id("pending_returns"))
        val CODEC: PacketCodec<PacketByteBuf, PendingReturnsPayload> = PacketCodec.of(
            { p, b -> b.writeInt(p.count); b.writeBoolean(p.hasBalance) },
            { b -> PendingReturnsPayload(b.readInt(), b.readBoolean()) }
        )
    }
}

/** 推一次上线待办概况（登录时调用；待领取 = 精灵 + 物品合计，hasBalance = 待领余额是否有钱） */
fun sendPendingReturns(player: ServerPlayerEntity, server: net.minecraft.server.MinecraftServer) {
    val state = MarketState.get(server)
    val count = state.getPendingReturns(player.uuid).size +
        ItemMarketState.get(server).getPendingReturns(player.uuid).size
    sendToPlayer(player, PendingReturnsPayload(count, state.getPendingBalance(player.uuid) > 0))
}

// ── C2S: OP 在入口界面切换市场总开关 ──

data class SetMarketEnabledPayload(val enabled: Boolean) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<SetMarketEnabledPayload>(CobbleMarket.id("set_market_enabled"))
        val CODEC: PacketCodec<PacketByteBuf, SetMarketEnabledPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.enabled) },
            { b -> SetMarketEnabledPayload(b.readBoolean()) }
        )
    }
}

/** 市场总开关切换（命令与入口按钮共用）：落盘 + 全员广播 */
/** 搜索词解析出的物品 ID 上限：恶意客户端可发海量 ID 放大 O(n²) 过滤（正常搜索词最多解析出几十个）；
 *  服务端 take 钳制 + toSet() 把「挂单数 × ID 数」线性查找降为 O(1) */
private const val MAX_ITEM_IDS = 256

fun toggleMarketEnabled(server: net.minecraft.server.MinecraftServer, enabled: Boolean) {
    com.shusheng.cobblemarket.config.CobbleMarketConfig.setMarketEnabled(enabled)
    // 全服播报：开市/停市音效由客户端在收到 MarketStatePayload 时自己播（见 CobbleMarketClient），
    // 这里只发文字——两条同时到达，玩家才能把"听到的声音"和"市场关了"对上
    val broadcast = Text.translatable(
        if (enabled) "cobblemarket.market.broadcast_open" else "cobblemarket.market.broadcast_close"
    ).formatted(Formatting.GOLD)
    server.playerManager.playerList.forEach {
        sendToPlayer(it, MarketStatePayload(enabled))
        it.sendMessage(broadcast, false)
        // 开市/停市音由服务端发，**不能让客户端自己判断**：管理面板的开关按钮会先乐观更新
        // MarketStateCache（见 AdminScreen.sendMarketEnabled），广播回来时客户端看"状态没变"，
        // 判断不出刚切换过；命令与按钮两条路径在这里统一覆盖（登录补发不走本函数，不会误响）
        sendSound(it, if (enabled) "market_open" else "market_close")
    }
}

/**
 * 市场总开关拦截：marketEnabled=false 时拒绝一切交易写操作并提示。
 * 只拦交易，不拦取回资产（待领取/余额领取等入口不调用本函数，与封禁语义一致）。
 * 坏账的市场交易限制走 FINANCE 来源封禁（服主可解封放行，见 FinanceService.syncFreeze）。
 */
fun marketBlocked(player: net.minecraft.server.network.ServerPlayerEntity): Boolean {
    if (com.shusheng.cobblemarket.config.CobbleMarketConfig.marketEnabled) return false
    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.market.closed")))
    return true
}

/**
 * **创建类请求的币种校验**（上架精灵 / 上架物品 / 创建拍卖 / 创建求购单共用）。
 *
 * 两件事：① 币种值非法 → 直接拒；② 是 RP → 再过两道闸（RP 总开关 / 玩家间 RP 交易）。
 * **返回 null 表示已经拒绝并发过提示**，调用方直接 `return@execute`。
 *
 * ⚠ 只能用 `fromRequest` 严格解析：拿容错版（fromStored）会把伪造的垃圾值静默当成主货币放行。
 * ⚠ 只拦**创建**：关闭开关后**存量** RP 挂单/拍卖/订单的成交与下架照常（那几条路走对象自带币种）。
 */
fun validateCreateCurrency(
    player: net.minecraft.server.network.ServerPlayerEntity,
    raw: String
): com.shusheng.cobblemarket.currency.MarketCurrency? {
    val currency = com.shusheng.cobblemarket.currency.MarketCurrency.fromRequest(raw)
    if (currency == null) {
        sendToPlayer(
            player,
            MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_currency"))
        )
        return null
    }
    if (pointsGateBlocked(player, currency)) return null
    return currency
}

/**
 * **RP 的两道闸**（创建侧与买家侧共用）：币种不是 RP 直接放行；是 RP 就查
 * 「RP 总开关」与「玩家间 RP 交易」。返回 true = **已拒绝并发过提示**，调用方直接 `return@execute`。
 *
 * ⚠ **买家侧同样要过这道闸**（直购 / 拍卖出价 / 求购交付）：配置里 `pointsMarketEnabled`
 * 的注释写得很明白 ——「玩家 A 挂 RP 单、玩家 B 买下，本质就是把 RP 从 B 转到 A，
 * 这是那条『不可转让』设定在市场上唯一的后门」。只拦创建不拦成交 = 后门照开。
 *
 * ⚠ 只拦**买家付 RP 出去**；卖家侧的退款 / 入账 / 下架 / 领取不受影响（那是收回自己的资产，
 * 关掉开关后存量 RP 挂单与拍卖仍能下架取回，不会变成死单）。
 */
fun pointsGateBlocked(
    player: net.minecraft.server.network.ServerPlayerEntity,
    currency: com.shusheng.cobblemarket.currency.MarketCurrency
): Boolean {
    if (currency != com.shusheng.cobblemarket.currency.MarketCurrency.POINTS) return false
    val cfg = com.shusheng.cobblemarket.config.CobbleMarketConfig
    if (!cfg.pointsEnabled) {
        sendToPlayer(
            player,
            MarketResultPayload(
                false,
                Text.translatable("cobblemarket.network.points_disabled", CurrencyService.pointsIcon())
            )
        )
        return true
    }
    if (!cfg.pointsMarketEnabled) {
        sendToPlayer(
            player,
            MarketResultPayload(
                false,
                Text.translatable("cobblemarket.network.points_market_disabled", CurrencyService.pointsIcon())
            )
        )
        return true
    }
    return false
}

// ── C2S: Request market data ──

data class RequestMarketPayload(
    val speciesFilter: String,
    val shinyOnly: Boolean,
    val minLevel: Int,
    val maxLevel: Int,
    val sortMode: String,
    val page: Int,
    val genderFilter: String,
    val typeFilter: String,
    // 特性/性格筛选（翻译 key；空串 = 不限）
    val abilityFilter: String,
    val natureFilter: String,
    val ivExactHp: Int,
    val ivExactAtk: Int,
    val ivExactDef: Int,
    val ivExactSpAtk: Int,
    val ivExactSpDef: Int,
    val ivExactSpd: Int,
    // IV 比较方式：0 = 等于（默认），1 = 大于等于，2 = 小于等于
    val ivOpHp: Int,
    val ivOpAtk: Int,
    val ivOpDef: Int,
    val ivOpSpAtk: Int,
    val ivOpSpDef: Int,
    val ivOpSpd: Int,
    val pageSize: Int,
    val mineOnly: Boolean,
    // 特训筛选三态：0 = 不限，1 = 仅含训练，2 = 仅不含训练
    val htFilter: Int,
    /**
     * 查询币种 —— **原始字符串，在这里不解析**（理由同 [SellFromStoragePayload.currency]：
     * 解码处容错会把伪造值静默当成主货币，必须留给 handler 用 `fromRequest` 严格校验）。
     *
     * 与上架类 payload 的区别：这是**展示层过滤**，只决定"看哪一半商品" —— 客户端传任意
     * 合法币种都无害（伪造它最多看到另一类商品的列表，碰不到钱货，见 `docs/points_plan.md` 安全设计）。
     */
    val currency: String,
    // ⚠ 下面两项也在末尾（[currency] 在前、这两项在后）—— 写读顺序必须一致
    // 太晶属性筛选（`cobblemon.type.xxx` **完整词条 key**；空串 = 不限）
    val teraFilter: String = "",
    // 超极巨化筛选：0 = 不限，1 = 仅超极巨，2 = 仅非超极巨
    val gmaxFilter: Int = 0
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<RequestMarketPayload>(CobbleMarket.id("request_market"))
        val CODEC: PacketCodec<PacketByteBuf, RequestMarketPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.speciesFilter)
                b.writeBoolean(p.shinyOnly)
                b.writeInt(p.minLevel)
                b.writeInt(p.maxLevel)
                b.writeString(p.sortMode)
                b.writeInt(p.page)
                b.writeString(p.genderFilter)
                b.writeString(p.typeFilter)
                b.writeString(p.abilityFilter)
                b.writeString(p.natureFilter)
                b.writeInt(p.ivExactHp)
                b.writeInt(p.ivExactAtk)
                b.writeInt(p.ivExactDef)
                b.writeInt(p.ivExactSpAtk)
                b.writeInt(p.ivExactSpDef)
                b.writeInt(p.ivExactSpd)
                b.writeInt(p.ivOpHp)
                b.writeInt(p.ivOpAtk)
                b.writeInt(p.ivOpDef)
                b.writeInt(p.ivOpSpAtk)
                b.writeInt(p.ivOpSpDef)
                b.writeInt(p.ivOpSpd)
                b.writeInt(p.pageSize)
                b.writeBoolean(p.mineOnly)
                b.writeInt(p.htFilter)
                b.writeString(p.currency)
                b.writeString(p.teraFilter)
                b.writeInt(p.gmaxFilter)
            },
            // 读端用具名参数，读写字段顺序必须一致
            { b ->
                RequestMarketPayload(
                    speciesFilter = b.readString(),
                    shinyOnly = b.readBoolean(),
                    minLevel = b.readInt(),
                    maxLevel = b.readInt(),
                    sortMode = b.readString(),
                    page = b.readInt(),
                    genderFilter = b.readString(),
                    typeFilter = b.readString(),
                    abilityFilter = b.readString(),
                    natureFilter = b.readString(),
                    ivExactHp = b.readInt(),
                    ivExactAtk = b.readInt(),
                    ivExactDef = b.readInt(),
                    ivExactSpAtk = b.readInt(),
                    ivExactSpDef = b.readInt(),
                    ivExactSpd = b.readInt(),
                    ivOpHp = b.readInt(),
                    ivOpAtk = b.readInt(),
                    ivOpDef = b.readInt(),
                    ivOpSpAtk = b.readInt(),
                    ivOpSpDef = b.readInt(),
                    ivOpSpd = b.readInt(),
                    pageSize = b.readInt(),
                    mineOnly = b.readBoolean(),
                    htFilter = b.readInt(),
                    currency = b.readString(),
                    teraFilter = b.readString(),
                    gmaxFilter = b.readInt()
                )
            }
        )
    }
}

// ── C2S: Admin request all Pokemon ──

data class AdminRequestPokemonPayload(
    val speciesFilter: String,
    val sellerFilter: String,
    val shinyOnly: Boolean,
    val sortMode: String,
    val page: Int,
    val ivExactHp: Int,
    val ivExactAtk: Int,
    val ivExactDef: Int,
    val ivExactSpAtk: Int,
    val ivExactSpDef: Int,
    val ivExactSpd: Int,
    // IV 比较方式：0 = 等于（默认），1 = 大于等于，2 = 小于等于
    val ivOpHp: Int,
    val ivOpAtk: Int,
    val ivOpDef: Int,
    val ivOpSpAtk: Int,
    val ivOpSpDef: Int,
    val ivOpSpd: Int,
    val pageSize: Int,
    val mineOnly: Boolean,
    // 特训筛选三态：0 = 不限，1 = 仅含训练，2 = 仅不含训练
    val htFilter: Int,
    /** 查询币种（原始字符串，不在这里解析；理由同 [RequestMarketPayload.currency]） */
    val currency: String,
    // ── 以下四项 2026-09-24 追加（管理端筛选面板照精灵市场补齐）：空串 = 不限 ──
    /** 性别：""/MALE/FEMALE */
    val genderFilter: String = "",
    /** 属性（资源名如 `fire`）：主属性或副属性任一命中即算 */
    val typeFilter: String = "",
    /** 特性（翻译 key，精确匹配） */
    val abilityFilter: String = "",
    /** 性格（翻译 key，按**生效**性格匹配，薄荷后为薄荷性格） */
    val natureFilter: String = "",
    // ── 以下两项 2026-09-28 追加（管理端筛选面板照精灵市场补「太晶 / 超极巨化」）──
    /**
     * 太晶属性：**完整词条 key**（`cobblemon.type.fire`）。
     * ⚠ `MarketState.search` 拿它跟 `extraData["teraType"]`（= 完整 key）做 `equals` **精确**比对 ⇒
     *   传裸的 `fire` 会恒不匹配 —— 与 [typeFilter] 的裸 key **不是**一套口径。
     */
    val teraFilter: String = "",
    /** 超极巨化筛选：0 = 不限 / 1 = 仅超极巨 / 2 = 仅非超极巨 */
    val gmaxFilter: Int = 0
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<AdminRequestPokemonPayload>(CobbleMarket.id("admin_request_pokemon"))
        val CODEC: PacketCodec<PacketByteBuf, AdminRequestPokemonPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.speciesFilter)
                b.writeString(p.sellerFilter)
                b.writeBoolean(p.shinyOnly)
                b.writeString(p.sortMode)
                b.writeInt(p.page)
                b.writeInt(p.ivExactHp)
                b.writeInt(p.ivExactAtk)
                b.writeInt(p.ivExactDef)
                b.writeInt(p.ivExactSpAtk)
                b.writeInt(p.ivExactSpDef)
                b.writeInt(p.ivExactSpd)
                b.writeInt(p.ivOpHp)
                b.writeInt(p.ivOpAtk)
                b.writeInt(p.ivOpDef)
                b.writeInt(p.ivOpSpAtk)
                b.writeInt(p.ivOpSpDef)
                b.writeInt(p.ivOpSpd)
                b.writeInt(p.pageSize)
                b.writeBoolean(p.mineOnly)
                b.writeInt(p.htFilter)
                b.writeString(p.currency)
                b.writeString(p.genderFilter)
                b.writeString(p.typeFilter)
                b.writeString(p.abilityFilter)
                b.writeString(p.natureFilter)
                // ⚠ 新增字段一律**追加在末尾**；两端必须同步升级
                b.writeString(p.teraFilter)
                b.writeInt(p.gmaxFilter)
            },
            // 读端用具名参数，读写字段顺序必须一致
            { b ->
                AdminRequestPokemonPayload(
                    speciesFilter = b.readString(),
                    sellerFilter = b.readString(),
                    shinyOnly = b.readBoolean(),
                    sortMode = b.readString(),
                    page = b.readInt(),
                    ivExactHp = b.readInt(),
                    ivExactAtk = b.readInt(),
                    ivExactDef = b.readInt(),
                    ivExactSpAtk = b.readInt(),
                    ivExactSpDef = b.readInt(),
                    ivExactSpd = b.readInt(),
                    ivOpHp = b.readInt(),
                    ivOpAtk = b.readInt(),
                    ivOpDef = b.readInt(),
                    ivOpSpAtk = b.readInt(),
                    ivOpSpDef = b.readInt(),
                    ivOpSpd = b.readInt(),
                    pageSize = b.readInt(),
                    mineOnly = b.readBoolean(),
                    htFilter = b.readInt(),
                    currency = b.readString(),
                    genderFilter = b.readString(),
                    typeFilter = b.readString(),
                    abilityFilter = b.readString(),
                    natureFilter = b.readString(),
                    teraFilter = b.readString(),
                    gmaxFilter = b.readInt()
                )
            }
        )
    }
}

// ── C2S: Admin cancel Pokemon ──

data class AdminCancelPokemonPayload(val listingId: UUID) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<AdminCancelPokemonPayload>(CobbleMarket.id("admin_cancel_pokemon"))
        val CODEC: PacketCodec<PacketByteBuf, AdminCancelPokemonPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.listingId) },
            { b -> AdminCancelPokemonPayload(b.readUuid()) }
        )
    }
}

// ── S2C: Market data response ──

data class MarketDataPayload(
    val entries: List<ListingEntry>,
    val totalPages: Int,
    val currentPage: Int,
    val pendingBalance: Long,
    /** 待领取精灵数量（>0 时客户端收款按钮旁红点提示；管理端响应恒 0） */
    val pendingReturns: Int
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<MarketDataPayload>(CobbleMarket.id("market_data"))
        val CODEC: PacketCodec<PacketByteBuf, MarketDataPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.entries.size)
                p.entries.forEach { it.write(b) }
                b.writeInt(p.totalPages)
                b.writeInt(p.currentPage)
                b.writeLong(p.pendingBalance)
                b.writeVarInt(p.pendingReturns)
            },
            { b ->
                val size = b.readVarInt()
                val entries = (0 until size).map { ListingEntry.read(b) }
                MarketDataPayload(
                    entries = entries,
                    totalPages = b.readInt(),
                    currentPage = b.readInt(),
                    pendingBalance = b.readLong(),
                    pendingReturns = b.readVarInt()
                )
            }
        )
    }
}

// ── C2S: Buy listing ──

data class BuyFromMarketPayload(val listingId: UUID, val planIndex: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<BuyFromMarketPayload>(CobbleMarket.id("buy_from_market"))
        val CODEC: PacketCodec<PacketByteBuf, BuyFromMarketPayload> = PacketCodec.of(
            // planIndex：-1 = 现金购买；≥0 = 喵喵支付分期方案下标
            { p, b -> b.writeUuid(p.listingId); b.writeInt(p.planIndex) },
            { b -> BuyFromMarketPayload(b.readUuid(), b.readInt()) }
        )
    }
}

// ── C2S: Cancel listing ──

data class CancelFromMarketPayload(val listingId: UUID) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<CancelFromMarketPayload>(CobbleMarket.id("cancel_from_market"))
        val CODEC: PacketCodec<PacketByteBuf, CancelFromMarketPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.listingId) },
            { b -> CancelFromMarketPayload(b.readUuid()) }
        )
    }
}

// ── S2C: Action result ──

data class MarketResultPayload(
    val success: Boolean,
    val message: Text
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<MarketResultPayload>(CobbleMarket.id("market_result"))
        val CODEC: PacketCodec<PacketByteBuf, MarketResultPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.success); net.minecraft.text.TextCodecs.PACKET_CODEC.encode(b, p.message) },
            { b -> MarketResultPayload(b.readBoolean(), net.minecraft.text.TextCodecs.PACKET_CODEC.decode(b)) }
        )
    }
}

// ── S2C: Open market screen ──

data class OpenMarketPayload(val dummy: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<OpenMarketPayload>(CobbleMarket.id("open_market"))
        val CODEC: PacketCodec<PacketByteBuf, OpenMarketPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); OpenMarketPayload(0) }
        )
    }
}

// ── Pokemon preview for sell selection ──

data class PokemonPreview(
    val uuid: UUID,
    val species: String,
    val speciesId: String,
    val speciesName: String,
    val level: Int,
    val shiny: Boolean,
    val gender: String,
    val nature: String,        // 生效性格（薄荷后为薄荷性格）
    val natureBase: String,    // 原生性格（遗传用；与 nature 不同 = 用过薄荷）
    val ability: String,
    val ivsHp: Int, val ivsAtk: Int, val ivsDef: Int, val ivsSpAtk: Int, val ivsSpDef: Int, val ivsSpd: Int,
    // 极限特训值（hyper training，-1 = 未特训）：显示「真实值（特训值）」用
    val htHp: Int, val htAtk: Int, val htDef: Int, val htSpAtk: Int, val htSpDef: Int, val htSpd: Int,
    val ball: String,
    val primaryType: String,
    val secondaryType: String,
    val source: String, // "party" or "pc"
    val slot: Int,
    val heldItemId: String,
    /** 携带物完整 NBT（SNBT，仅带组件时非空）：客户端据此重建图标，否则丢附魔光效/自定义名。
     *  旧数据无此字段 = 空串，客户端回退按 ID 建图标 */
    val heldItemNbt: String = "",
    val aspects: List<String>, // 精灵形态（shiny/性别/地区形态等），客户端渲染 3D 图标用
    // 努力值（EV）：上架选择/待领取等场景的详情显示用
    val evsHp: Int, val evsAtk: Int, val evsDef: Int, val evsSpAtk: Int, val evsSpDef: Int, val evsSpd: Int,
    /** 亲密度（预览快照） */
    val friendship: Int,
    /** 拥有的证章纹理路径列表（外观展示；服务端直接传纹理，客户端不依赖 Marks 注册表） */
    val marks: List<String> = emptyList(),
    /** 体型分类（XS/S/M/L/XL/ALPHA），空串 = 无信息 */
    val sizeCategory: String = "",
    /** 技能槽快照（4 槽用「,」连接、空槽空串）；旧数据 = 空串 ⇒ 悬停窗技能块不画。见 [com.shusheng.cobblemarket.util.MoveText] */
    val moves: String = "",
    /**
     * 携带物在**精灵模型上是否渲染**（`Pokemon.heldItemVisible`，玩家可在 Cobblemon 队伍详情里关掉它）。
     * ⚠ 只管**模型手里**：行信息 / 弹窗 / 图标 / 大图一律照常显示（用户 2026-09-26 拍板——
     *   隐藏是「不渲染在模型上」，不是「没带东西」，交易信息不该跟着消失）。旧数据没有这一位 = true
     */
    val heldItemVisible: Boolean = true,
    /**
     * 太晶属性翻译 key（`cobblemon.type.xxx`）—— **空串 = 没有**（不显示，例如老挂单）。
     *
     * 服务端**常驻下发**（官方 `Pokemon.teraType`，默认值就是主属性）。
     * ⚠ 曾经写成「只在 ≠ 主属性时才下发」，被用户否掉了：那等于把它定义成**附属模组专属功能**，
     *   而**玩家不一定知道这只精灵的主属性是哪个**（异兽、形态变化尤其）—— 太晶本身就该写明。
     */
    val teraType: String = "",
    /**
     * 超极巨化（官方 `Pokemon.gmaxFactor`）：变的**是不是**超极巨。
     * ⚠ 与 [dmaxLevel] **不是二选一** —— 超极巨走同一条 HP 倍率公式。
     * 预览的是**活精灵**（队伍/PC 里那只），直接从 `Pokemon` 读，不走 NBT。
     */
    val gmaxFactor: Boolean = false,
    /** 极巨等级（官方 `Pokemon.dmaxLevel`）：极巨化时 HP 倍率 = 1.5 + 0.05×等级；0 = 没喂过。 */
    val dmaxLevel: Int = 0,
    /**
     * 初始训练家（官方 `Pokemon.originalTrainer`）的**显示名**，见
     * [com.shusheng.cobblemarket.util.PokemonOT]。**空串 = 没有 / 解析不到**（那一行整行不画）。
     */
    val originalTrainer: String = "",
    /**
     * 球的 **item id**（`<ns>:<path>`，如 `cobblemon:master_ball`）：球种行取**稀有度色**用。
     * ⚠ 只有球名的词条 key 不够 —— 直接念词条出来是**白字**（大师球在物品栏里是紫的），
     *   见 [com.shusheng.cobblemarket.util.ItemNameText.ballLine]。旧数据缺这一位 = 空串（回退白字）
     */
    val ballItem: String = ""
) {
    fun write(buf: PacketByteBuf) {
        buf.writeUuid(uuid); buf.writeString(species); buf.writeString(speciesId); buf.writeString(speciesName)
        buf.writeInt(level); buf.writeBoolean(shiny); buf.writeString(gender)
        buf.writeString(nature); buf.writeString(natureBase); buf.writeString(ability)
        buf.writeInt(ivsHp); buf.writeInt(ivsAtk); buf.writeInt(ivsDef)
        buf.writeInt(ivsSpAtk); buf.writeInt(ivsSpDef); buf.writeInt(ivsSpd)
        buf.writeInt(htHp); buf.writeInt(htAtk); buf.writeInt(htDef)
        buf.writeInt(htSpAtk); buf.writeInt(htSpDef); buf.writeInt(htSpd)
        buf.writeString(ball); buf.writeString(primaryType); buf.writeString(secondaryType)
        buf.writeString(source); buf.writeInt(slot); buf.writeString(heldItemId); buf.writeString(heldItemNbt)
        buf.writeVarInt(aspects.size); aspects.forEach { buf.writeString(it) }
        buf.writeInt(evsHp); buf.writeInt(evsAtk); buf.writeInt(evsDef)
        buf.writeInt(evsSpAtk); buf.writeInt(evsSpDef); buf.writeInt(evsSpd)
        buf.writeInt(friendship)
        buf.writeVarInt(marks.size); marks.forEach { buf.writeString(it) }
        buf.writeString(sizeCategory)
        buf.writeString(moves)
        buf.writeBoolean(heldItemVisible)
        buf.writeString(teraType)
        // ⚠ 新增字段一律**追加在末尾**（读端严格同序）；两端必须同步升级
        buf.writeBoolean(gmaxFactor)
        buf.writeInt(dmaxLevel)
        buf.writeString(originalTrainer)
        buf.writeString(ballItem)
    }

    companion object {
        fun read(buf: PacketByteBuf) = PokemonPreview(
            buf.readUuid(), buf.readString(), buf.readString(), buf.readString(),
            buf.readInt(), buf.readBoolean(), buf.readString(),
            buf.readString(), buf.readString(), buf.readString(),
            buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
            buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
            buf.readString(), buf.readString(), buf.readString(),
            buf.readString(), buf.readInt(), buf.readString(),
            buf.readString(),   // heldItemNbt：与 write 端严格同序（write 在 heldItemId 之后）
            (0 until buf.readVarInt()).map { buf.readString() },
            buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
            buf.readInt(),
            (0 until buf.readVarInt()).map { buf.readString() },
            buf.readString(),
            buf.readString(),   // moves：与 write 端严格同序（write 在 sizeCategory 之后）
            buf.readBoolean(),   // heldItemVisible：与 write 端严格同序（write 在 moves 之后）
            buf.readString(),    // teraType：与 write 端同序（write 在 heldItemVisible 之后）
            buf.readBoolean(),   // gmaxFactor：与 write 端严格同序（write 在 teraType 之后）
            buf.readInt(),       // dmaxLevel
            buf.readString(),    // originalTrainer
            buf.readString()     // ballItem
        )
    }
}

// ── C2S: Request my Pokémon list ──

class RequestMyPokemonPayload(val page: Int, val requestId: Int) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestMyPokemonPayload>(CobbleMarket.id("request_my_pokemon"))
        val CODEC: PacketCodec<PacketByteBuf, RequestMyPokemonPayload> = PacketCodec.of(
            { p, b -> b.writeInt(p.page); b.writeInt(p.requestId) },
            { b -> RequestMyPokemonPayload(b.readInt(), b.readInt()) }
        )
    }
}

// ── S2C: My Pokémon list response ──

data class MyPokemonListPayload(
    val pokemon: List<PokemonPreview>,
    val page: Int,
    val requestId: Int,
    val hasMore: Boolean
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<MyPokemonListPayload>(CobbleMarket.id("my_pokemon_list"))
        val CODEC: PacketCodec<PacketByteBuf, MyPokemonListPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.page); b.writeInt(p.requestId); b.writeBoolean(p.hasMore)
                b.writeVarInt(p.pokemon.size); p.pokemon.forEach { it.write(b) }
            },
            { b ->
                val page = b.readInt()
                val requestId = b.readInt()
                val hasMore = b.readBoolean()
                MyPokemonListPayload(
                    (0 until b.readVarInt()).map { PokemonPreview.read(b) },
                    page, requestId, hasMore
                )
            }
        )
    }
}

// ── C2S: Collect pending balance ──

class CollectBalancePayload : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<CollectBalancePayload>(CobbleMarket.id("collect_balance"))
        val CODEC: PacketCodec<PacketByteBuf, CollectBalancePayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); CollectBalancePayload() }
        )
    }
}

// ── History ──

data class HistoryEntry(
    val type: String,
    val category: String,
    val species: String,
    val price: Int,
    val buyerName: String,
    val sellerName: String,
    val timestamp: Long,
    /** 这笔流水是什么币种（`MarketCurrency.name`）—— 界面按它决定显示 `₽` 还是 RP 图标 */
    val currency: String = MarketCurrency.PRIMARY.name,
    /** 精灵名上色用（`cobblemon.type.fire` 这种 key，形态感知）：物品记录与旧记录为空串 */
    val primaryType: String = "",
    /**
     * **物品名上色用**：稀有度枚举名（`COMMON`/`UNCOMMON`/`RARE`/`EPIC`）。
     *
     * 物品没有 `primaryType` 那样的现成字段 —— 界面只有 itemId 时只能拿 `defaultStack` 取默认栈的
     * 稀有度，「附魔把稀有度顶上去」那一档就丢了（物品栏里是黄的、历史里是白的，用户 2026-09-26 报）。
     * 服务端在产生记录时（手上还有 nbt）算好，随记录一起存。
     * ⚠ **末尾追加 + 默认值**：精灵记录与旧记录为空串 → 界面退回按 itemId 取色（行为不变）。
     */
    val itemRarity: String = "",
    /**
     * 是不是 **RP 商城**卖出的（`TransactionRecord.fromShop`）—— 界面据此把卖家列画成「▣ 商城」
     * 而不是上架的管理员名。⚠ 末尾追加 + 默认值：旧记录落 false。
     */
    val fromShop: Boolean = false
) {
    fun write(buf: PacketByteBuf) {
        buf.writeString(type); buf.writeString(category); buf.writeString(species); buf.writeInt(price); buf.writeString(
            buyerName
        ); buf.writeString(sellerName); buf.writeLong(timestamp); buf.writeString(currency); buf.writeString(primaryType)
        buf.writeString(itemRarity); buf.writeBoolean(fromShop)
    }

    companion object {
        // ⚠ 读端一律具名：位置参数一旦与 write 顺序错开就会静默错位（字段都是 String/Int，不会报类型错）
        fun read(buf: PacketByteBuf) = HistoryEntry(
            type = buf.readString(),
            category = buf.readString(),
            species = buf.readString(),
            price = buf.readInt(),
            buyerName = buf.readString(),
            sellerName = buf.readString(),
            timestamp = buf.readLong(),
            currency = buf.readString(),
            primaryType = buf.readString(),
            itemRarity = buf.readString(),
            fromShop = buf.readBoolean()
        )
    }
}

data class RequestHistoryPayload(val all: Boolean) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestHistoryPayload>(CobbleMarket.id("request_history"))
        val CODEC: PacketCodec<PacketByteBuf, RequestHistoryPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.all) },
            { b -> RequestHistoryPayload(b.readBoolean()) }
        )
    }
}

data class HistoryDataPayload(val entries: List<HistoryEntry>) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<HistoryDataPayload>(CobbleMarket.id("history_data"))
        val CODEC: PacketCodec<PacketByteBuf, HistoryDataPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> HistoryDataPayload((0 until b.readVarInt()).map { HistoryEntry.read(b) }) }
        )
    }
}

// ── C2S: Sell from storage ──

data class SellFromStoragePayload(
    val pokemonUuid: UUID,
    val price: Int,
    /**
     * 上架币种 —— **原始字符串，在这里不解析**。
     *
     * ⚠ 类型必须是 String 而不是 [MarketCurrency]：codec 解码时没有「拒绝」这个选项，
     * 若在解码处就 `fromStored` 容错，伪造的垃圾值会被**静默当成主货币**（等于放行）。
     * 服务端 handler 用 `MarketCurrency.fromRequest` 严格校验，非法直接拒绝请求。
     *
     * 默认值只为客户端构造方便，服务端**不认**这个默认。
     */
    val currency: String = MarketCurrency.PRIMARY.name
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<SellFromStoragePayload>(CobbleMarket.id("sell_from_storage"))
        val CODEC: PacketCodec<PacketByteBuf, SellFromStoragePayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.pokemonUuid); b.writeInt(p.price); b.writeString(p.currency) },
            { b ->
                val uuid = b.readUuid()
                val price = b.readInt()
                SellFromStoragePayload(uuid, price, b.readString())
            }
        )
    }
}

// ── C2S: Sell item ──

data class SellItemPayload(
    val itemId: String,
    val itemNbt: NbtCompound,
    val count: Int,
    val price: Int,
    /**
     * 上架币种 —— **原始字符串，在这里不解析**（理由同 [SellFromStoragePayload.currency]：
     * 解码处容错会把伪造值静默放行，必须留给 handler 用 `fromRequest` 严格校验）。
     */
    val currency: String = MarketCurrency.PRIMARY.name
) :
    CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<SellItemPayload>(CobbleMarket.id("sell_item"))
        val CODEC: PacketCodec<PacketByteBuf, SellItemPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.itemId); PacketCodecs.NBT_COMPOUND.encode(
                b,
                p.itemNbt
            ); b.writeInt(p.count); b.writeInt(p.price); b.writeString(p.currency)
            },
            { b ->
                val itemId = b.readString()
                val itemNbt = PacketCodecs.NBT_COMPOUND.decode(b)
                val count = b.readInt()
                val price = b.readInt()
                SellItemPayload(itemId, itemNbt, count, price, b.readString())
            }
        )
    }
}

// ── C2S: Request item market ──

data class RequestItemMarketPayload(
    val sortMode: String,
    val page: Int,
    val mineOnly: Boolean,
    val pageSize: Int,
    val query: String,
    val itemIds: List<String>,
    /** 查询命中的招式 ID 集合（TM 搜索机制：条目 NBT 组件招式名精确过滤，见 ItemSearchIndex） */
    val tmMoves: List<String>,
    /** 查询命中的附魔 ID 集合（附魔书搜索的精确过滤，见 ItemSearchIndex） */
    val enchants: List<String>,
    /** 查询币种（原始字符串，不在这里解析；理由同 [RequestMarketPayload.currency]） */
    val currency: String
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestItemMarketPayload>(CobbleMarket.id("request_item_market"))
        val CODEC: PacketCodec<PacketByteBuf, RequestItemMarketPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.sortMode); b.writeInt(p.page); b.writeBoolean(p.mineOnly); b.writeInt(p.pageSize); b.writeString(p.query)
                b.writeVarInt(p.itemIds.size); p.itemIds.forEach { b.writeString(it) }
                b.writeVarInt(p.tmMoves.size); p.tmMoves.forEach { b.writeString(it) }
                b.writeVarInt(p.enchants.size); p.enchants.forEach { b.writeString(it) }
                b.writeString(p.currency)
            },
            // 读端用具名参数，读写字段顺序必须一致
            { b ->
                RequestItemMarketPayload(
                    sortMode = b.readString(),
                    page = b.readInt(),
                    mineOnly = b.readBoolean(),
                    pageSize = b.readInt(),
                    query = b.readString(),
                    itemIds = (0 until b.readVarInt()).map { b.readString() },
                    tmMoves = (0 until b.readVarInt()).map { b.readString() },
                    enchants = (0 until b.readVarInt()).map { b.readString() },
                    currency = b.readString()
                )
            }
        )
    }
}

// ── S2C: Item market data ──

data class ItemMarketDataPayload(
    val entries: List<ItemEntry>,
    val totalPages: Int,
    val currentPage: Int,
    val pendingBalance: Long,
    /** 待领取物品数量（>0 时客户端收款按钮旁红点提示；管理端响应恒 0） */
    val pendingReturns: Int
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<ItemMarketDataPayload>(CobbleMarket.id("item_market_data"))
        val CODEC: PacketCodec<PacketByteBuf, ItemMarketDataPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.entries.size)
                p.entries.forEach { it.write(b) }
                b.writeInt(p.totalPages)
                b.writeInt(p.currentPage)
                b.writeLong(p.pendingBalance)
                b.writeVarInt(p.pendingReturns)
            },
            { b ->
                val size = b.readVarInt()
                val entries = (0 until size).map { ItemEntry.read(b) }
                ItemMarketDataPayload(
                    entries = entries,
                    totalPages = b.readInt(),
                    currentPage = b.readInt(),
                    pendingBalance = b.readLong(),
                    pendingReturns = b.readVarInt()
                )
            }
        )
    }
}

// ── C2S: Admin request all items ──

data class AdminRequestItemPayload(
    val sellerFilter: String,
    val itemFilter: String,
    val itemIds: List<String>,
    /** 查询命中的招式 ID 集合（TM 搜索机制，见 ItemSearchIndex） */
    val tmMoves: List<String>,
    /** 查询命中的附魔 ID 集合（附魔书搜索的精确过滤，见 ItemSearchIndex） */
    val enchants: List<String>,
    val sortMode: String,
    val page: Int,
    val pageSize: Int,
    val mineOnly: Boolean,
    /** 查询币种（原始字符串，不在这里解析；理由同 [RequestMarketPayload.currency]） */
    val currency: String
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<AdminRequestItemPayload>(CobbleMarket.id("admin_request_item"))
        val CODEC: PacketCodec<PacketByteBuf, AdminRequestItemPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.sellerFilter); b.writeString(p.itemFilter)
                b.writeVarInt(p.itemIds.size); p.itemIds.forEach { b.writeString(it) }
                b.writeVarInt(p.tmMoves.size); p.tmMoves.forEach { b.writeString(it) }
                b.writeVarInt(p.enchants.size); p.enchants.forEach { b.writeString(it) }
                b.writeString(p.sortMode); b.writeInt(p.page); b.writeInt(p.pageSize); b.writeBoolean(
                p.mineOnly
            )
                b.writeString(p.currency)
            },
            // 读端用具名参数，读写字段顺序必须一致
            { b ->
                AdminRequestItemPayload(
                    sellerFilter = b.readString(),
                    itemFilter = b.readString(),
                    itemIds = (0 until b.readVarInt()).map { b.readString() },
                    tmMoves = (0 until b.readVarInt()).map { b.readString() },
                    enchants = (0 until b.readVarInt()).map { b.readString() },
                    sortMode = b.readString(),
                    page = b.readInt(),
                    pageSize = b.readInt(),
                    mineOnly = b.readBoolean(),
                    currency = b.readString()
                )
            }
        )
    }
}

// ── C2S: Admin cancel item ──

data class AdminCancelItemPayload(val listingId: UUID) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<AdminCancelItemPayload>(CobbleMarket.id("admin_cancel_item"))
        val CODEC: PacketCodec<PacketByteBuf, AdminCancelItemPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.listingId) },
            { b -> AdminCancelItemPayload(b.readUuid()) }
        )
    }
}

// ── C2S: Buy item ──

data class BuyItemPayload(val listingId: UUID, val count: Int, val planIndex: Int) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<BuyItemPayload>(CobbleMarket.id("buy_item"))
        val CODEC: PacketCodec<PacketByteBuf, BuyItemPayload> = PacketCodec.of(
            // planIndex：-1 = 现金购买；≥0 = 喵喵支付分期方案下标
            { p, b -> b.writeUuid(p.listingId); b.writeInt(p.count); b.writeInt(p.planIndex) },
            { b -> BuyItemPayload(b.readUuid(), b.readInt(), b.readInt()) }
        )
    }
}

// ── C2S: Cancel item ──

data class CancelItemPayload(val listingId: UUID) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<CancelItemPayload>(CobbleMarket.id("cancel_item"))
        val CODEC: PacketCodec<PacketByteBuf, CancelItemPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.listingId) },
            { b -> CancelItemPayload(b.readUuid()) }
        )
    }
}

// ── C2S: Request pokemon returns ──

data class RequestPokemonReturnPayload(val page: Int, val pageSize: Int) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestPokemonReturnPayload>(CobbleMarket.id("request_pokemon_return"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPokemonReturnPayload> = PacketCodec.of(
            { p, b -> b.writeInt(p.page); b.writeInt(p.pageSize) },
            { b -> RequestPokemonReturnPayload(b.readInt(), b.readInt()) }
        )
    }
}

// ── S2C: Pokemon return data ──

data class PokemonReturnDataPayload(
    val pokemon: List<PokemonPreview>,
    val totalPages: Int,
    val currentPage: Int
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PokemonReturnDataPayload>(CobbleMarket.id("pokemon_return_data"))
        val CODEC: PacketCodec<PacketByteBuf, PokemonReturnDataPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.pokemon.size); p.pokemon.forEach { it.write(b) }
                b.writeInt(p.totalPages); b.writeInt(p.currentPage)
            },
            { b ->
                val pokemon = (0 until b.readVarInt()).map { PokemonPreview.read(b) }
                PokemonReturnDataPayload(pokemon, b.readInt(), b.readInt())
            }
        )
    }
}

// ── C2S: Claim pokemon returns ──

class ClaimPokemonReturnPayload : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<ClaimPokemonReturnPayload>(CobbleMarket.id("claim_pokemon_return"))
        val CODEC: PacketCodec<PacketByteBuf, ClaimPokemonReturnPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); ClaimPokemonReturnPayload() }
        )
    }
}

// ── C2S: Request item returns ──

data class RequestItemReturnPayload(val page: Int, val pageSize: Int) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestItemReturnPayload>(CobbleMarket.id("request_item_return"))
        val CODEC: PacketCodec<PacketByteBuf, RequestItemReturnPayload> = PacketCodec.of(
            { p, b -> b.writeInt(p.page); b.writeInt(p.pageSize) },
            { b -> RequestItemReturnPayload(b.readInt(), b.readInt()) }
        )
    }
}

// ── S2C: Item return data ──

data class ItemReturnDataPayload(
    val items: List<ItemEntry>,
    val totalPages: Int,
    val currentPage: Int
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<ItemReturnDataPayload>(CobbleMarket.id("item_return_data"))
        val CODEC: PacketCodec<PacketByteBuf, ItemReturnDataPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.items.size); p.items.forEach { it.write(b) }
                b.writeInt(p.totalPages); b.writeInt(p.currentPage)
            },
            { b ->
                val items = (0 until b.readVarInt()).map { ItemEntry.read(b) }
                ItemReturnDataPayload(items, b.readInt(), b.readInt())
            }
        )
    }
}

// ── C2S: Claim item returns ──

data class ClaimItemReturnPayload(
    /** 玩家个人设置：装不下的部分掉在地上（默认关=留在待领取） */
    val dropOverflow: Boolean
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<ClaimItemReturnPayload>(CobbleMarket.id("claim_item_return"))
        val CODEC: PacketCodec<PacketByteBuf, ClaimItemReturnPayload> = PacketCodec.of(
            { p, b -> b.writeBoolean(p.dropOverflow) },
            { b -> ClaimItemReturnPayload(dropOverflow = b.readBoolean()) }
        )
    }
}

// ── Registration ──

private fun parseSortMode(name: String): com.shusheng.cobblemarket.market.SortMode =
    com.shusheng.cobblemarket.market.SortMode.entries.firstOrNull { it.name == name }
        ?: com.shusheng.cobblemarket.market.SortMode.PRICE_ASC

// 退还物品到背包：insertStack 会扣减传入栈的 count（放入部分），
// 未完全放入的剩余部分掉落到地面（拾取延迟 0），确保玩家不损失物品
fun giveBackItem(stack: ItemStack, player: ServerPlayerEntity) {
    if (stack.isEmpty) return
    player.inventory.insertStack(stack)
    if (!stack.isEmpty) {
        val entity = player.dropItem(stack, false)
        entity?.setPickupDelay(0)
    }
    player.inventory.markDirty()
}

/** 是否为蛋类物品（Cobbreeding 蛋：namespace 为 cobbreeding、path 以 pokemon_egg 结尾，含通用蛋与全部属性蛋；不依赖其类名） */
fun isEggItem(itemId: String): Boolean {
    val id = net.minecraft.util.Identifier.tryParse(itemId) ?: return false
    return id.namespace == "cobbreeding" && id.path.endsWith("pokemon_egg")
}

/** 客户端语言物种名 → 资源路径名（照 MarketScreen 原私有实现；服务端只存英文资源名，中文搜索词必须在客户端转 id） */
fun localizeSpeciesQuery(raw: String): String {
    val q = raw.trim()
    if (q.isEmpty() || q.all { it.code < 128 }) return q
    // ⚠ 必须返回**全部**候选（`|` 连接），不能只取第一个：中文物种名互为子串的情形很多
    //   （「鬼斯」是「鬼斯通」的前缀、小火龙/火恐龙/喷火龙、伊布一家…），只取一个的话
    //   玩家搜「鬼斯」出来的可能是鬼斯通 —— 命中谁取决于物种表的遍历顺序，玩家看来就是随机的。
    //   服务端按 `|` 拆开做「任一命中」。
    //   全 ASCII 输入直接透传（英文名/资源 id 搜索照旧）；一个都没命中时也透传原文，
    //   服务端还有按物种名包含匹配兜底。
    val ids = com.cobblemon.mod.common.api.pokemon.PokemonSpecies.implemented
        .filter { it.translatedName.string.contains(q) }
        .map { it.resourceIdentifier.path }
    // ⚠ **原文必须一并带上**：服务端还要拿这些 token 匹配**卖家名**（同一个搜索框既搜精灵也搜玩家），
    //   而玩家名不随语言变化 —— 中文查询经这里转成物种 id（`eevee`）之后，就再也匹配不上
    //   一个叫「伊布」的玩家了。全 ASCII 的查询在前面已直接透传，不受影响
    return if (ids.isEmpty()) q else (ids + q).joinToString("|")
}

/** 客户端语言物品名搜索 → 匹配的物品 id 集合（服务端语言与客户端不同时靠 id 传递过滤；含 id 路径匹配，英文查询同样覆盖） */
/**
 * TM 栈的招式 ID（非 TM 或解析失败返回 null；TM 搜索机制的条目级精确过滤用）。
 * ⚠ 调用方须先判断条目 itemId == cobblemon:technical_machine 再调用，避免逐条目解析 NBT。
 */
fun tmMoveOfItemNbt(itemNbt: NbtCompound?, registryLookup: net.minecraft.registry.RegistryWrapper.WrapperLookup): String? {
    if (itemNbt == null) return null
    return try {
        val stack = net.minecraft.item.ItemStack.fromNbtOrEmpty(registryLookup, itemNbt)
        if (stack.isEmpty) null
        else com.cobblemon.mod.common.item.components.TMMoveComponent.getTMMove(stack)?.name
    } catch (_: Throwable) { null }
}

/** 条目 NBT 的附魔 ID 列表（非附魔书或解析失败返回空；附魔搜索的精确过滤用） */
fun enchantsOfItemNbt(itemNbt: NbtCompound?, registryLookup: net.minecraft.registry.RegistryWrapper.WrapperLookup): List<String> {
    if (itemNbt == null) return emptyList()
    return try {
        val stack = net.minecraft.item.ItemStack.fromNbtOrEmpty(registryLookup, itemNbt)
        if (stack.isEmpty) emptyList()
        else {
            // 附魔书存 stored_enchantments 组件（普通物品才是 enchantments）——读 stored，回退普通
            val comp = stack.get(net.minecraft.component.DataComponentTypes.STORED_ENCHANTMENTS)
                ?: stack.enchantments
            comp.enchantments.mapNotNull { it.key.map { k -> k.value.toString() }.orElse(null) }
        }
    } catch (_: Throwable) { emptyList() }
}

/** 搜索条目过滤：itemId 命中 || TM 条目且 NBT 招式命中 || 附魔书条目且 NBT 附魔命中（搜索机制的精确过滤，服务端） */
fun matchesItemQuery(
    itemId: String,
    itemNbt: NbtCompound?,
    itemIds: Set<String>,
    tmMoves: Set<String>,
    enchants: Set<String>,
    registryLookup: net.minecraft.registry.RegistryWrapper.WrapperLookup
): Boolean {
    if (itemId in itemIds) return true
    if (itemId == "cobblemon:technical_machine" && tmMoves.isNotEmpty() &&
        tmMoveOfItemNbt(itemNbt, registryLookup)?.let { it in tmMoves } == true
    ) return true
    return itemId == "minecraft:enchanted_book" && enchants.isNotEmpty() &&
        enchantsOfItemNbt(itemNbt, registryLookup).any { it in enchants }
}

/**
 * 交易匹配：物品相同且组件一致；动态组件（cobbreeding 蛋的孵化计时 timer/second 持续变化、
 * version 组件在服务端重建时会被 verifyComponentsAfterLoad 迁移改写）导致完整比较永远失败——
 * 剔除动态组件后再比较一次。
 * 宽松分支仅对蛋类物品生效：非蛋物品一律严格比较（避免未来其它物品的 :timer 等组件承载区分性数据时被误判相同）。
 * 蛋的安全边界：只忽略 :timer / :second / :version 结尾的组件，蛋数据（egg_info）等其余组件仍严格比较，不会混淆不同物品。
 */
fun itemsEqualForTrading(a: ItemStack, b: ItemStack): Boolean {
    if (a.isEmpty || b.isEmpty) return false
    if (!a.isOf(b.item)) return false
    if (ItemStack.areItemsAndComponentsEqual(a, b)) return true
    if (!isEggItem(Registries.ITEM.getId(a.item).toString())) return false
    return componentEntriesIgnoringDynamic(a) == componentEntriesIgnoringDynamic(b)
}

private fun componentEntriesIgnoringDynamic(stack: ItemStack): Set<Pair<String, Any?>> {
    val out = mutableSetOf<Pair<String, Any?>>()
    stack.components.stream().forEach { entry ->
        val id = Registries.DATA_COMPONENT_TYPE.getId(entry.type()).toString()
        if (!id.endsWith(":timer") && !id.endsWith(":second") && !id.endsWith(":version")) {
            out.add(id to entry.value())
        }
    }
    return out
}

// 预检：insertStack（PlayerInventory）只往 main 放（getEmptySlot 只遍历 main），
// 预检同样只数 main，与实际插入行为一致，不会误拒合法交易
internal fun canFitInInventory(player: ServerPlayerEntity, stack: ItemStack): Boolean {
    var remaining = stack.count
    val main = player.inventory.main
    for (i in 0 until main.size) {
        val slot = main[i]
        if (!slot.isEmpty && itemsEqualForTrading(slot, stack)) {
            remaining -= (slot.maxCount - slot.count)
            if (remaining <= 0) return true
        }
    }
    for (i in 0 until main.size) {
        if (main[i].isEmpty) {
            remaining -= stack.maxCount
            if (remaining <= 0) return true
        }
    }
    return false
}

object MarketNetwork {

    fun register() {
        registerS2CType(MarketStatePayload.ID, MarketStatePayload.CODEC)
        registerS2CType(PendingReturnsPayload.ID, PendingReturnsPayload.CODEC)
        registerS2CType(MyPokemonListPayload.ID, MyPokemonListPayload.CODEC)
        registerS2CType(HistoryDataPayload.ID, HistoryDataPayload.CODEC)
        registerS2CType(OpenMarketPayload.ID, OpenMarketPayload.CODEC)
        registerS2CType(MarketDataPayload.ID, MarketDataPayload.CODEC)
        registerS2CType(ItemMarketDataPayload.ID, ItemMarketDataPayload.CODEC)
        registerS2CType(PokemonReturnDataPayload.ID, PokemonReturnDataPayload.CODEC)
        registerS2CType(ItemReturnDataPayload.ID, ItemReturnDataPayload.CODEC)
        registerS2CType(MarketResultPayload.ID, MarketResultPayload.CODEC)

        registerC2S(RequestMarketPayload.ID, RequestMarketPayload.CODEC) { payload, player ->
            // 250ms：与客户端搜索防抖（250ms）配合——防抖保证正常操作下两次请求间隔
            // 至少 250ms，最终态请求不会被节流误丢；仍拦得住恶意高频全量搜索
            if (!RequestThrottle.allow(player.uuid, "request_market", 250L)) return@registerC2S
            val server = player.server
            server.execute {
                val state = MarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())

                val sortMode = parseSortMode(payload.sortMode)
                // 币种（**展示层过滤**）—— 非法值直接拒绝，不能拿 fromStored 容错
                //（那等于把伪造值静默当成主货币）；合法值一律放行，它只决定看哪一半商品
                val currency = MarketCurrency.fromRequest(payload.currency)
                if (currency == null) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_currency"))
                    )
                    return@execute
                }
                val results = state.search(
                    currency = currency,
                    species = payload.speciesFilter.ifBlank { null },
                    shiny = if (payload.shinyOnly) true else null,
                    minLevel = if (payload.minLevel > 0) payload.minLevel else null,
                    maxLevel = if (payload.maxLevel >= 0) payload.maxLevel else null,
                    sortBy = sortMode,
                    gender = payload.genderFilter.ifBlank { null },
                    typeFilter = payload.typeFilter.ifBlank { null },
                    ability = payload.abilityFilter.ifBlank { null },
                    nature = payload.natureFilter.ifBlank { null },
                    ivExact = buildMap {
                        if (payload.ivExactHp >= 0) put("ivsHp", payload.ivExactHp)
                        if (payload.ivExactAtk >= 0) put("ivsAtk", payload.ivExactAtk)
                        if (payload.ivExactDef >= 0) put("ivsDef", payload.ivExactDef)
                        if (payload.ivExactSpAtk >= 0) put("ivsSpAtk", payload.ivExactSpAtk)
                        if (payload.ivExactSpDef >= 0) put("ivsSpDef", payload.ivExactSpDef)
                        if (payload.ivExactSpd >= 0) put("ivsSpd", payload.ivExactSpd)
                    },
                    ivOps = buildMap {
                        if (payload.ivExactHp >= 0) put("ivsHp", payload.ivOpHp.coerceIn(0, 2))
                        if (payload.ivExactAtk >= 0) put("ivsAtk", payload.ivOpAtk.coerceIn(0, 2))
                        if (payload.ivExactDef >= 0) put("ivsDef", payload.ivOpDef.coerceIn(0, 2))
                        if (payload.ivExactSpAtk >= 0) put("ivsSpAtk", payload.ivOpSpAtk.coerceIn(0, 2))
                        if (payload.ivExactSpDef >= 0) put("ivsSpDef", payload.ivOpSpDef.coerceIn(0, 2))
                        if (payload.ivExactSpd >= 0) put("ivsSpd", payload.ivOpSpd.coerceIn(0, 2))
                    },
                    sellerUuid = if (payload.mineOnly) player.uuid else null,
                    htFilter = payload.htFilter,
                    teraFilter = payload.teraFilter.ifBlank { null },
                    gmax = when (payload.gmaxFilter) {
                        1 -> true
                        2 -> false
                        else -> null
                    }
                )

                val pageSize = payload.pageSize.coerceIn(1, 30)
                val totalPages = ((results.size - 1) / pageSize) + 1
                val clampedPage = payload.page.coerceIn(1, maxOf(1, totalPages))

                val pageEntries = if (results.isEmpty()) emptyList() else {
                    val start = (clampedPage - 1) * pageSize
                    results.drop(start).take(pageSize).map { listing ->
                        // 太晶：**老挂单没有这项快照**（那时还没快照它）⇒ 从挂单自带的完整 NBT 现读。
                        // 与 `size` / `moves` 同一个套路（见 auctionToEntry 的注释），玩家不用重新上架
                        val detail = listing.extraData + ("teraType" to
                            com.shusheng.cobblemarket.util.PokemonTera.resolve(
                                listing.extraData["teraType"], listing.pokemonNbt))
                        // 极巨化 / 初始训练家：**同样从挂单自带的完整 NBT 现读**（与太晶同款）
                        // ⇒ 升级上来的老挂单也显示，玩家不用重新上架。见 PokemonDynamax / PokemonOT
                        val dyn = com.shusheng.cobblemarket.util.PokemonDynamax.fromNbt(listing.pokemonNbt)
                        val otName = com.shusheng.cobblemarket.util.PokemonOT.fromNbt(listing.pokemonNbt, server)
                        ListingEntry(
                            id = listing.id,
                            sellerUuid = listing.sellerUuid,
                            species = listing.species,
                            speciesId = detail["speciesId"] ?: listing.species.lowercase().replace(" ", "_"),
                            level = listing.level,
                            shiny = listing.shiny,
                            price = listing.price,
                            sellerName = listing.sellerName,
                            primaryType = detail["primaryType"] ?: "normal",
                            secondaryType = detail["secondaryType"] ?: "",
                            ivsHp = detail["ivsHp"]?.toIntOrNull() ?: 0,
                            ivsAtk = detail["ivsAtk"]?.toIntOrNull() ?: 0,
                            ivsDef = detail["ivsDef"]?.toIntOrNull() ?: 0,
                            ivsSpAtk = detail["ivsSpAtk"]?.toIntOrNull() ?: 0,
                            ivsSpDef = detail["ivsSpDef"]?.toIntOrNull() ?: 0,
                            ivsSpd = detail["ivsSpd"]?.toIntOrNull() ?: 0,
                            htHp = detail["htHp"]?.toIntOrNull() ?: -1,
                            htAtk = detail["htAtk"]?.toIntOrNull() ?: -1,
                            htDef = detail["htDef"]?.toIntOrNull() ?: -1,
                            htSpAtk = detail["htSpAtk"]?.toIntOrNull() ?: -1,
                            htSpDef = detail["htSpDef"]?.toIntOrNull() ?: -1,
                            htSpd = detail["htSpd"]?.toIntOrNull() ?: -1,
                            evsHp = detail["evsHp"]?.toIntOrNull() ?: 0,
                            evsAtk = detail["evsAtk"]?.toIntOrNull() ?: 0,
                            evsDef = detail["evsDef"]?.toIntOrNull() ?: 0,
                            evsSpAtk = detail["evsSpAtk"]?.toIntOrNull() ?: 0,
                            evsSpDef = detail["evsSpDef"]?.toIntOrNull() ?: 0,
                            evsSpd = detail["evsSpd"]?.toIntOrNull() ?: 0,
                            friendship = detail["friendship"]?.toIntOrNull() ?: 0,
                            nature = detail["nature"] ?: "?",
                            natureBase = detail["natureBase"] ?: detail["nature"] ?: "?",
                            ability = detail["ability"] ?: "?",
                            gender = detail["gender"] ?: "?",
                            ball = detail["ball"] ?: "?",
                            ballItem = detail["ballItem"] ?: "cobblemon:poke_ball",
                            heldItemId = detail["heldItemId"] ?: "",
                            heldItemNbt = detail["heldItemNbt"] ?: "",
                            // 「模型手里拿不拿」：缺这一位（旧挂单 / 旧服务端）= 显示，与字段默认值一致
                            heldItemVisible = detail["heldItemVisible"] != "false",
                            // 太晶属性：**空串 = 不显示**（服务端只在 ≠ 主属性时才写这个键）
                            teraType = detail["teraType"] ?: "",
                            currencyName = com.shusheng.cobblemarket.config.CurrencyHandler.getCurrencyId(),
                            aspects = parseAspects(detail),
                            marks = parseMarks(detail),
                            sizeCategory = PokemonSize.fromNbt(listing.pokemonNbt),
                            currency = currency.name,
                            moves = com.shusheng.cobblemarket.util.MoveText.resolve(detail["moves"], listing.pokemonNbt),
                            // 极巨化 / 初始训练家（值见上面现读出来的 dyn / otName）
                            gmaxFactor = dyn.gmaxFactor,
                            dmaxLevel = dyn.dmaxLevel,
                            originalTrainer = otName
                        )
                    }
                }

                sendToPlayer(
                    player,
                    MarketDataPayload(
                        pageEntries,
                        maxOf(1, totalPages),
                        clampedPage,
                        state.getPendingBalance(player.uuid),
                        state.getPendingReturns(player.uuid).size
                    )
                )
            }
        }

        registerC2S(BuyFromMarketPayload.ID, BuyFromMarketPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "buy_pokemon", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val banCheckTime = System.currentTimeMillis()
                val banInfo = BanState.get(server).getBanInfo(player.uuid, banCheckTime)
                if (marketBlocked(player)) return@execute
                if (banInfo != null) {
                    // 保留 Text 对象而非 .string：翻译在客户端语言下渲染（服务端语言 ≠ 客户端语言）
                    val timeDesc: Text = if (banInfo.isPermanent)
                        Text.translatable("cobblemarket.ban.permanent")
                    else
                        Text.translatable(
                            "cobblemarket.ban.remaining",
                            BanState.formatRemaining(banInfo.expiresAt!! - banCheckTime)
                        )
                    val banMsg = if (banInfo.reason.isNotBlank())
                        Text.translatable("cobblemarket.ban.banned_msg_time_reason", timeDesc, com.shusheng.cobblemarket.market.BanState.reasonText(banInfo.reason))
                    else
                        Text.translatable("cobblemarket.ban.banned_msg_time", timeDesc)
                    sendToPlayer(player, MarketResultPayload(false, banMsg))
                    return@execute
                }
                val state = MarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())
                val listing = state.getListing(payload.listingId)

                if (listing == null || !listing.isActive()) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                if (listing.sellerUuid == player.uuid) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.cannot_buy_own"))
                    )
                    return@execute
                }
                // 先校验挂单数据可加载，再扣款，避免解析失败吞掉买家的钱
                val registryLookup = player.serverWorld.registryManager
                val pokemon = try {
                    com.shusheng.cobblemarket.util.PokemonLoader.fromNbt(registryLookup, listing.pokemonNbt)
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.warn("Failed to load pokemon NBT for listing {}: {}", listing.id, e.message)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }

                // 黑名单检查：拦截上架后被加入黑名单的存量挂单（治理即时生效）
                // 拦下时把**命中的那条规则**一并说清（见 RuleReasonText）
                val blacklisted = com.shusheng.cobblemarket.market.PokemonBlacklistState.get(server)
                    .findMatch(pokemon)
                if (blacklisted != null) {
                    val msg = com.shusheng.cobblemarket.market.RuleReasonText.blacklistBlocked(blacklisted)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, msg)
                    )
                    return@execute
                }
                // ⚠ **买家侧币种闸**：买 RP 挂单 = 把 RP 从买家转到卖家，正是「玩家间 RP 交易」
                //   开关要拦的那条后门（只拦创建不拦成交 = 后门照开）
                if (pointsGateBlocked(player, listing.currency)) return@execute
                // 喵喵支付（消费贷）：信用校验 + 准备金垫付；否则现金扣款
                val meowth = payload.planIndex >= 0
                // ⚠ **RP 挂单不接受喵喵支付**：准备金池垫付的是主货币，等于跨币种兑换，违反「不可兑换」
                //   （方案「另三处必须特殊处理」）。客户端购买弹窗也会藏掉喵喵入口，这里是服务端兜底。
                if (meowth && listing.currency != MarketCurrency.PRIMARY) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(
                            false,
                            Text.translatable(
                                "cobblemarket.network.no_meowth_for_points", CurrencyService.pointsIcon()
                            )
                        )
                    )
                    return@execute
                }
                if (meowth) {
                    val payErr = com.shusheng.cobblemarket.finance.FinanceService.meowthCheck(
                        player, listing.price.toLong(), payload.planIndex, System.currentTimeMillis()
                    )
                    if (payErr != null) {
                        sendToPlayer(player, MarketResultPayload(false, payErr))
                        return@execute
                    }
                    com.shusheng.cobblemarket.finance.FinanceState.get(server).withdrawReserve(listing.price.toLong())
                } else if (!CurrencyService.remove(player, listing.currency, listing.price.toLong())) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(
                            false,
                            Text.translatable(
                                "cobblemarket.network.need_diamonds",
                                CurrencyService.amountText(listing.price, listing.currency)
                            )
                        )
                    )
                    return@execute
                }

                val party = Cobblemon.storage.getParty(player)
                val inBattle = com.shusheng.cobblemarket.market.BattleGuard.isPlayerInBattle(player.uuid)
                val added = if (inBattle) {
                    // 对战中购买的精灵进待领取：战斗系统动态读队伍，进队会被切换面板切出参战（凭空补员）
                    state.addPendingReturn(player.uuid, listing)
                    true
                } else {
                    try {
                        party.add(pokemon)
                    } catch (e: Exception) {
                        CobbleMarket.LOGGER.warn("Failed to add pokemon to party for buyer {}: {}", player.uuid, e.message)
                        false
                    }
                }
                if (!added) {
                    if (meowth) {
                        // 信用支付失败回池（贷款未创建，无需撤销）
                        com.shusheng.cobblemarket.finance.FinanceState.get(server).depositReserve(listing.price.toLong())
                    } else {
                        // ⚠ 退款与扣款**同币种**（都取自挂单对象）：扣的是 RP 就不能退成主货币
                        val refunded = CurrencyService.give(player, listing.currency, listing.price.toLong())
                        if (refunded < listing.price.toLong()) {
                            // 退款未全部到账（背包满）：差额转入待领余额兜底，避免买家钱被吞
                            state.addPendingBalance(player.uuid, listing.price.toLong() - refunded)
                            CobbleMarket.LOGGER.error(
                                "Partial refund for buyer {} on listing {}; {} moved to pending balance",
                                player.uuid, listing.id, listing.price.toLong() - refunded
                            )
                        }
                    }
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.storage_full"))
                    )
                    return@execute
                }

                // 喵喵支付记账（货物已入队）：创建消费贷 + 审计 CSV + IP 记录
                if (meowth) {
                    com.shusheng.cobblemarket.finance.FinanceService.meowthPaySettle(
                        server, player, listing.price.toLong(), payload.planIndex,
                        com.shusheng.cobblemarket.finance.LoanSource.POKEMON_BUY, System.currentTimeMillis()
                    )
                }

                // 卖家入账：主货币进待领余额、RP 直接到账（见 CurrencyService.creditSeller）
                CurrencyService.creditSeller(server, listing.sellerUuid, listing.currency, listing.price.toLong())
                // RP 审计账本：玩家间 RP 交易（此前只落交易历史，服主拿审计账本对账会漏掉一整类）。
                // 精灵市场无手续费 ⇒ 两侧等值
                if (listing.currency == MarketCurrency.POINTS) {
                    PointsAuditLogger.logMarketTrade(
                        server, player.uuid, player.name.string,
                        listing.sellerUuid, listing.sellerName,
                        listing.price.toLong(), listing.price.toLong(),
                        "市场购买：${listing.species}",
                        "Market purchase: ${listing.species}"
                    )
                }
                // 卖家到账音：在线才发（离线那位由上线时的「离线收益」提示兜底，见 MarketEntryScreen）
                server.playerManager.getPlayer(listing.sellerUuid)?.let { sendSound(it, "market_sold") }
                listing.status = ListingStatus.SOLD
                state.markModified()
                com.shusheng.cobblemarket.event.MarketEvents.PURCHASE.trigger(
                    com.shusheng.cobblemarket.event.PurchaseEvent(
                        player.uuid,
                        player.name.string,
                        listing.sellerUuid,
                        listing,
                        listing.price
                    )
                )

                // 金融系统成交挂钩子：买家成交计入其交易额（额度公式数据源；防刷三层在 recordTrade 内成交时快照判定；
                // 喵喵支付成交标 loanFunded → 不计入交易额，防借→买→额度涨→再借循环）。
                // ⚠ **RP 交易整块跳过**（方案「统计隔离」）：totalCountedVolume 喂着紫卡/黑卡申请门槛与
                //   贷款额度公式，让 RP 来回刷交易量等于白嫖卡片资格和更高额度；RP 留痕走交易历史（带币种）
                if (listing.currency == MarketCurrency.PRIMARY) {
                    com.shusheng.cobblemarket.finance.FinanceState.get(server).recordTrade(
                        server, player.uuid, listing.sellerUuid, listing.price.toLong(), System.currentTimeMillis(),
                        loanFunded = meowth
                    )
                }

                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(
                        true,
                        if (meowth)
                            Text.translatable(
                                "cobblemarket.buy_confirm.paid_by_meowth",
                                // 闪光带金★：与卖家侧「你的 ★ xxx 已卖出」写法一致（2026-09-19 用户拍板）
                                com.shusheng.cobblemarket.util.TypeTextColors.withShinyStar(listing.speciesText(), listing.shiny),
                                CurrencyService.amountText(listing.price, listing.currency),
                                com.shusheng.cobblemarket.config.CobbleMarketConfig.loanPlans.getOrNull(payload.planIndex)?.periods ?: 1
                            )
                        else
                            Text.translatable(
                                "cobblemarket.network.bought",
                                // 闪光带金★：与卖家侧「你的 ★ xxx 已卖出」写法一致（2026-09-19 用户拍板）
                                com.shusheng.cobblemarket.util.TypeTextColors.withShinyStar(listing.speciesText(), listing.shiny),
                                CurrencyService.amountText(listing.price, listing.currency)
                            )
                    )
                )
                // 喵喵支付成功后回发额度快照（欠款已变，喵喵银行/应急贷款界面即时刷新）
                if (meowth) {
                    com.shusheng.cobblemarket.network.FinanceNetwork.sendCreditInfo(
                        player, com.shusheng.cobblemarket.finance.FinanceState.get(server), System.currentTimeMillis()
                    )
                }

                // 买家庆祝动画：精灵已进队伍，所有权转移完成
                CelebrationNetwork.sendFromEntry(player, listing.species, listing.shiny, listing.extraData, CelebrationSource.MARKET)

                // 卖家离线则入队补发。主体绿（与「上架成功 / 买入成功」同一配色），名字带主属性色
                // 与闪光金★ —— 富文本拼接：名字自己带样式，不会被外层绿盖掉。
                // ⚠ 买家侧「你购买了 xxx」**同样带 ★**：同一只精灵在买卖两侧写法一致（2026-09-19 用户拍板）
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                    server, listing.sellerUuid,
                    Text.translatable(
                        "cobblemarket.network.sold",
                        com.shusheng.cobblemarket.util.TypeTextColors.withShinyStar(listing.speciesText(), listing.shiny)
                    ).formatted(Formatting.GREEN)
                )

                // 精灵已进买家队伍，挂单生命周期终结，立即删除避免存档膨胀
                state.removeListing(listing.id)
            }
        }

        registerC2S(CancelFromMarketPayload.ID, CancelFromMarketPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "cancel_pokemon", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 封禁只禁止交易；取消挂单是取回自己的资产，允许
                val state = MarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())
                val listing = state.getListing(payload.listingId)

                if (listing == null || !listing.isActive()) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                if (listing.sellerUuid != player.uuid) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.cmd.not_your_listing"))
                    )
                    return@execute
                }

                val pokemon = try {
                    com.shusheng.cobblemarket.util.PokemonLoader.fromNbt(
                        player.serverWorld.registryManager, listing.pokemonNbt
                    )
                } catch (e: Exception) {
                    // 挂单数据损坏时保留 ACTIVE 状态，等待管理员处理，避免精灵凭空消失
                    CobbleMarket.LOGGER.warn("Failed to load pokemon NBT for listing {}: {}", listing.id, e.message)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                val party = Cobblemon.storage.getParty(player)
                if (com.shusheng.cobblemarket.market.BattleGuard.isPlayerInBattle(player.uuid)) {
                    // 对战中下架的精灵进待领取，战斗结束后领取（进队会被切换面板切出参战=凭空补员）
                    state.addPendingReturn(player.uuid, listing)
                } else if (!party.add(pokemon)) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.storage_full"))
                    )
                    return@execute
                }

                listing.status = ListingStatus.CANCELLED
                state.markModified()
                com.shusheng.cobblemarket.event.MarketEvents.CANCEL.trigger(
                    com.shusheng.cobblemarket.event.CancelEvent(
                        player.uuid,
                        listing
                    )
                )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(true, Text.translatable("cobblemarket.cmd.cancelled", listing.speciesText()))
                )

                // 精灵已回卖家队伍，挂单生命周期终结，立即删除避免存档膨胀
                state.removeListing(listing.id)
            }
        }

        registerC2S(AdminRequestPokemonPayload.ID, AdminRequestPokemonPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.MARKET)) return@registerC2S
            val server = player.server
            server.execute {
                val state = MarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())

                val sortMode = parseSortMode(payload.sortMode)
                // 币种（展示层过滤）：非法值直接拒绝，理由同 RequestMarketPayload
                val currency = MarketCurrency.fromRequest(payload.currency)
                if (currency == null) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_currency"))
                    )
                    return@execute
                }
                val results = state.search(
                    currency = currency,
                    species = payload.speciesFilter.ifBlank { null },
                    shiny = if (payload.shinyOnly) true else null,
                    sortBy = sortMode,
                    ivExact = buildMap {
                        if (payload.ivExactHp >= 0) put("ivsHp", payload.ivExactHp)
                        if (payload.ivExactAtk >= 0) put("ivsAtk", payload.ivExactAtk)
                        if (payload.ivExactDef >= 0) put("ivsDef", payload.ivExactDef)
                        if (payload.ivExactSpAtk >= 0) put("ivsSpAtk", payload.ivExactSpAtk)
                        if (payload.ivExactSpDef >= 0) put("ivsSpDef", payload.ivExactSpDef)
                        if (payload.ivExactSpd >= 0) put("ivsSpd", payload.ivExactSpd)
                    },
                    ivOps = buildMap {
                        if (payload.ivExactHp >= 0) put("ivsHp", payload.ivOpHp.coerceIn(0, 2))
                        if (payload.ivExactAtk >= 0) put("ivsAtk", payload.ivOpAtk.coerceIn(0, 2))
                        if (payload.ivExactDef >= 0) put("ivsDef", payload.ivOpDef.coerceIn(0, 2))
                        if (payload.ivExactSpAtk >= 0) put("ivsSpAtk", payload.ivOpSpAtk.coerceIn(0, 2))
                        if (payload.ivExactSpDef >= 0) put("ivsSpDef", payload.ivOpSpDef.coerceIn(0, 2))
                        if (payload.ivExactSpd >= 0) put("ivsSpd", payload.ivOpSpd.coerceIn(0, 2))
                    },
                    sellerUuid = if (payload.mineOnly) player.uuid else null,
                    sellerName = payload.sellerFilter.ifBlank { null },
                    htFilter = payload.htFilter,
                    // 2026-09-24 追加：性别 / 属性 / 特性 / 性格（照精灵市场补齐筛选面板）
                    gender = payload.genderFilter.ifBlank { null },
                    typeFilter = payload.typeFilter.ifBlank { null },
                    ability = payload.abilityFilter.ifBlank { null },
                    nature = payload.natureFilter.ifBlank { null },
                    // 2026-09-28 追加：太晶（**完整词条 key**，见 payload 字段说明）/ 超极巨化
                    teraFilter = payload.teraFilter.ifBlank { null },
                    gmax = when (payload.gmaxFilter) {
                        1 -> true
                        2 -> false
                        else -> null
                    }
                )

                val pageSize = payload.pageSize.coerceIn(1, 30)
                val totalPages = ((results.size - 1) / pageSize) + 1
                val clampedPage = payload.page.coerceIn(1, maxOf(1, totalPages))

                val pageEntries = if (results.isEmpty()) emptyList() else {
                    val start = (clampedPage - 1) * pageSize
                    results.drop(start).take(pageSize).map { listing ->
                        // 太晶：**老挂单没有这项快照**（那时还没快照它）⇒ 从挂单自带的完整 NBT 现读。
                        // 与 `size` / `moves` 同一个套路（见 auctionToEntry 的注释），玩家不用重新上架
                        val detail = listing.extraData + ("teraType" to
                            com.shusheng.cobblemarket.util.PokemonTera.resolve(
                                listing.extraData["teraType"], listing.pokemonNbt))
                        // 极巨化 / 初始训练家：**同样从挂单自带的完整 NBT 现读**（与太晶同款）
                        // ⇒ 升级上来的老挂单也显示，玩家不用重新上架。见 PokemonDynamax / PokemonOT
                        val dyn = com.shusheng.cobblemarket.util.PokemonDynamax.fromNbt(listing.pokemonNbt)
                        val otName = com.shusheng.cobblemarket.util.PokemonOT.fromNbt(listing.pokemonNbt, server)
                        ListingEntry(
                            id = listing.id,
                            sellerUuid = listing.sellerUuid,
                            species = listing.species,
                            speciesId = detail["speciesId"] ?: listing.species.lowercase().replace(" ", "_"),
                            level = listing.level,
                            shiny = listing.shiny,
                            price = listing.price,
                            sellerName = listing.sellerName,
                            primaryType = detail["primaryType"] ?: "normal",
                            secondaryType = detail["secondaryType"] ?: "",
                            ivsHp = detail["ivsHp"]?.toIntOrNull() ?: 0,
                            ivsAtk = detail["ivsAtk"]?.toIntOrNull() ?: 0,
                            ivsDef = detail["ivsDef"]?.toIntOrNull() ?: 0,
                            ivsSpAtk = detail["ivsSpAtk"]?.toIntOrNull() ?: 0,
                            ivsSpDef = detail["ivsSpDef"]?.toIntOrNull() ?: 0,
                            ivsSpd = detail["ivsSpd"]?.toIntOrNull() ?: 0,
                            htHp = detail["htHp"]?.toIntOrNull() ?: -1,
                            htAtk = detail["htAtk"]?.toIntOrNull() ?: -1,
                            htDef = detail["htDef"]?.toIntOrNull() ?: -1,
                            htSpAtk = detail["htSpAtk"]?.toIntOrNull() ?: -1,
                            htSpDef = detail["htSpDef"]?.toIntOrNull() ?: -1,
                            htSpd = detail["htSpd"]?.toIntOrNull() ?: -1,
                            evsHp = detail["evsHp"]?.toIntOrNull() ?: 0,
                            evsAtk = detail["evsAtk"]?.toIntOrNull() ?: 0,
                            evsDef = detail["evsDef"]?.toIntOrNull() ?: 0,
                            evsSpAtk = detail["evsSpAtk"]?.toIntOrNull() ?: 0,
                            evsSpDef = detail["evsSpDef"]?.toIntOrNull() ?: 0,
                            evsSpd = detail["evsSpd"]?.toIntOrNull() ?: 0,
                            friendship = detail["friendship"]?.toIntOrNull() ?: 0,
                            nature = detail["nature"] ?: "?",
                            natureBase = detail["natureBase"] ?: detail["nature"] ?: "?",
                            ability = detail["ability"] ?: "?",
                            gender = detail["gender"] ?: "?",
                            ball = detail["ball"] ?: "?",
                            ballItem = detail["ballItem"] ?: "cobblemon:poke_ball",
                            heldItemId = detail["heldItemId"] ?: "",
                            heldItemNbt = detail["heldItemNbt"] ?: "",
                            // 「模型手里拿不拿」：缺这一位（旧挂单 / 旧服务端）= 显示，与字段默认值一致
                            heldItemVisible = detail["heldItemVisible"] != "false",
                            // 太晶属性：**空串 = 不显示**（服务端只在 ≠ 主属性时才写这个键）
                            teraType = detail["teraType"] ?: "",
                            currencyName = com.shusheng.cobblemarket.config.CurrencyHandler.getCurrencyId(),
                            aspects = parseAspects(detail),
                            marks = parseMarks(detail),
                            sizeCategory = PokemonSize.fromNbt(listing.pokemonNbt),
                            currency = currency.name,
                            moves = com.shusheng.cobblemarket.util.MoveText.resolve(detail["moves"], listing.pokemonNbt),
                            // 极巨化 / 初始训练家（值见上面现读出来的 dyn / otName）
                            gmaxFactor = dyn.gmaxFactor,
                            dmaxLevel = dyn.dmaxLevel,
                            originalTrainer = otName
                        )
                    }
                }

                sendToPlayer(player, MarketDataPayload(pageEntries, maxOf(1, totalPages), clampedPage, 0L, 0))
            }
        }

        registerC2S(AdminCancelPokemonPayload.ID, AdminCancelPokemonPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.MARKET)) return@registerC2S
            val server = player.server
            server.execute {
                val state = MarketState.get(server)
                val listing = state.getListing(payload.listingId)
                if (listing == null || !listing.isActive()) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                listing.status = ListingStatus.CANCELLED
                state.addPendingReturn(listing.sellerUuid, listing)
                state.markModified()
                com.shusheng.cobblemarket.event.MarketEvents.CANCEL.trigger(
                    com.shusheng.cobblemarket.event.CancelEvent(listing.sellerUuid, listing)
                )
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                    server, listing.sellerUuid,
                    // 强制下架专属红通知（与拍卖/求购对齐；主动下架的白色通用词条不动）
                    Text.translatable("cobblemarket.op.cancelled_notice", listing.speciesText())
                        .formatted(Formatting.RED)
                )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(
                        true,
                        Text.translatable("cobblemarket.op.cancelled", listing.sellerName, listing.speciesText())
                    )
                )
            }
        }

        registerC2S(AdminRequestItemPayload.ID, AdminRequestItemPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.MARKET)) return@registerC2S
            val server = player.server
            server.execute {
                val state = ItemMarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())

                val sortMode = parseSortMode(payload.sortMode)
                // 币种（展示层过滤）：非法值直接拒绝，理由同 RequestMarketPayload
                val currency = MarketCurrency.fromRequest(payload.currency)
                if (currency == null) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_currency"))
                    )
                    return@execute
                }
                val results = state.search(
                    currency = currency,
                    sortBy = sortMode,
                    sellerUuid = if (payload.mineOnly) player.uuid else null,
                    sellerName = payload.sellerFilter.ifBlank { null }
                ).let { list ->
                    val query = payload.itemFilter.trim()
                    if (query.isEmpty()) list
                    // 同一个搜索框也匹配**卖家名**（2026-09-24 与管理端的两个搜索框合并），
                    // 与拍卖场的客户端过滤同一语义
                    else list.filter {
                        it.sellerName.contains(query, ignoreCase = true) ||
                            matchesItemQuery(it.itemId, it.itemNbt, payload.itemIds.take(MAX_ITEM_IDS).toSet(), payload.tmMoves.toSet(), payload.enchants.toSet(), server.overworld.registryManager)
                    }
                }

                // 上限 84（12 行）：网格页容量随窗口，上限低于容量会导致末行空槽+多余分页；
                // 84 是服务器压力折中：恶意高频请求（250ms 节流下每秒 4 次）的带宽攻击面减半，正常窗口（≤12 行）无感知
                // 物品 itemNbt 通常几百字节（蛋较大，最坏全蛋页约 1MB，可接受）
                val pageSize = payload.pageSize.coerceIn(1, 84)
                val totalPages = ((results.size - 1) / pageSize) + 1
                val clampedPage = payload.page.coerceIn(1, maxOf(1, totalPages))

                val pageEntries = if (results.isEmpty()) emptyList() else {
                    val start = (clampedPage - 1) * pageSize
                    results.drop(start).take(pageSize).map { listing ->
                        ItemEntry(
                            id = listing.id,
                            sellerUuid = listing.sellerUuid,
                            sellerName = listing.sellerName,
                            itemId = listing.itemId,
                            itemNbt = listing.itemNbt,
                            count = listing.count,
                            price = listing.price,
                            currencyName = CurrencyHandler.getCurrencyId(),
                            currency = currency.name
                        )
                    }
                }

                sendToPlayer(
                    player,
                    ItemMarketDataPayload(pageEntries, maxOf(1, totalPages), clampedPage, 0L, 0)
                )
            }
        }

        registerC2S(AdminCancelItemPayload.ID, AdminCancelItemPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.MARKET)) return@registerC2S
            val server = player.server
            server.execute {
                val state = ItemMarketState.get(server)
                val listing = state.getListing(payload.listingId)
                if (listing == null || !listing.isActive()) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                listing.status = ListingStatus.CANCELLED
                state.addPendingReturn(listing.sellerUuid, listing)
                state.markModified()
                com.shusheng.cobblemarket.event.TransactionHistory.get(server).addRecord(
                    com.shusheng.cobblemarket.event.TransactionRecord(
                        timestamp = System.currentTimeMillis(),
                        type = com.shusheng.cobblemarket.event.TransactionType.CANCEL,
                        category = com.shusheng.cobblemarket.event.TransactionCategory.ITEM,
                        sellerUuid = listing.sellerUuid,
                        sellerName = listing.sellerName,
                        buyerUuid = null,
                        buyerName = "",
                        species = listing.itemId,
                        price = listing.price,
                        fee = 0,
                        detail = com.shusheng.cobblemarket.util.RecordDetail.item(listing.itemNbt, listing.count, server.registryManager),
                        currency = listing.currency
                    )
                )
                // 物品名传翻译 Text（客户端按玩家语言渲染），与精灵下架的 speciesText() 一致，不能传裸 itemId；
                // 颜色走稀有度色（= 物品栏悬停的名字颜色）
                val itemName = com.shusheng.cobblemarket.util.ItemNameText
                    .rarityColoredNameOfNbt(server.registryManager, listing.itemNbt)
                    ?: com.shusheng.cobblemarket.util.ItemNameText.rarityColoredNameById(listing.itemId)
                    ?: Text.literal(listing.itemId)
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                    server, listing.sellerUuid,
                    // 强制下架专属红通知（与拍卖/求购对齐；主动下架的白色通用词条不动）
                    Text.translatable("cobblemarket.op.cancelled_notice", itemName)
                        .formatted(Formatting.RED)
                )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(
                        true,
                        Text.translatable("cobblemarket.op.cancelled", listing.sellerName, itemName)
                    )
                )
            }
        }

        registerC2S(RequestMyPokemonPayload.ID, RequestMyPokemonPayload.CODEC) { payload, player ->
            // page 超出合理范围直接丢弃，防 page * pageSize 溢出
            if (payload.page !in 0..10000) return@registerC2S
            // page 0 是开关界面的入口，保留节流防刷；分页请求由响应驱动（拿到响应才会发下一个），
            // 天然有节奏且只读无副作用，不节流——本地服务器往返 <1ms，500ms 冷却会把整条链全部拒掉。
            if (payload.page == 0 && !RequestThrottle.allow(player.uuid, "request_my_pokemon", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                val pageSize = 100
                val previews = mutableListOf<PokemonPreview>()
                if (payload.page == 0) {
                    // 队伍精灵固定显示在列表顶部，只随第一页发送
                    val party = Cobblemon.storage.getParty(player)
                    for (i in 0..5) {
                        try {
                            val p = party.get(PartyPosition(i)) ?: continue
                            previews.add(toPreview(p, "party", i, server.registryManager, server))
                        } catch (_: Exception) {
                        }
                    }
                }
                var hasMore = false
                try {
                    val pc = Cobblemon.storage.getPC(player)
                    // PC 按全局偏移分页切片：坏精灵计入偏移但不占名额，保证翻页切片不漂移
                    var idx = 0
                    var sent = 0
                    val skip = payload.page * pageSize
                    val iter = pc.iterator()
                    while (iter.hasNext()) {
                        val p = iter.next()
                        if (idx++ < skip) continue
                        if (sent >= pageSize) { hasMore = true; break }
                        try {
                            previews.add(toPreview(p, "pc", idx - 1, server.registryManager, server))
                            sent++
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
                sendToPlayer(player, MyPokemonListPayload(previews, payload.page, payload.requestId, hasMore))
            }
        }

        registerC2S(SellFromStoragePayload.ID, SellFromStoragePayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "sell_from_storage", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val banCheckTime = System.currentTimeMillis()
                val banInfo = BanState.get(server).getBanInfo(player.uuid, banCheckTime)
                if (marketBlocked(player)) return@execute
                if (banInfo != null) {
                    // 保留 Text 对象而非 .string：翻译在客户端语言下渲染（服务端语言 ≠ 客户端语言）
                    val timeDesc: Text = if (banInfo.isPermanent)
                        Text.translatable("cobblemarket.ban.permanent")
                    else
                        Text.translatable(
                            "cobblemarket.ban.remaining",
                            BanState.formatRemaining(banInfo.expiresAt!! - banCheckTime)
                        )
                    val banMsg = if (banInfo.reason.isNotBlank())
                        Text.translatable("cobblemarket.ban.banned_msg_time_reason", timeDesc, com.shusheng.cobblemarket.market.BanState.reasonText(banInfo.reason))
                    else
                        Text.translatable("cobblemarket.ban.banned_msg_time", timeDesc)
                    sendToPlayer(player, MarketResultPayload(false, banMsg))
                    return@execute
                }
                val party = Cobblemon.storage.getParty(player)
                val pc = Cobblemon.storage.getPC(player)
                val pokemonUuid = payload.pokemonUuid

                // Find in party first
                var pokemon = party.find { it.uuid == pokemonUuid }
                val fromParty = pokemon != null
                if (pokemon == null) {
                    pokemon = pc.find { it.uuid == pokemonUuid }
                }
                if (pokemon == null) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                // 对战中不可上架（整个队伍）：战斗系统动态读队伍，抽走任何精灵都可能造成战斗内模型消失或变相复制
                if (com.shusheng.cobblemarket.market.BattleGuard.isPlayerInBattle(player.uuid)) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.in_battle"))
                    )
                    return@execute
                }
                if (payload.price <= 0) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_price"))
                    )
                    return@execute
                }
                // ⚠ 币种来自**创建类请求**，必须严格校验：非法值直接拒绝。
                //   这里**不能**用 fromStored 容错 —— 那等于把伪造的币种静默当成主货币放行。
                val currency = MarketCurrency.fromRequest(payload.currency)
                if (currency == null) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_currency"))
                    )
                    return@execute
                }
                // RP 上架的两道闸 —— 关闭后**存量** RP 挂单仍能**下架**取回（那条路走挂单自带币种，
                // 不会变成死单）；但**成交**另算：买家侧（直购/出价）同样要过闸，见 pointsGateBlocked：
                // ① RP 总开关没开 → 整个 RP 不可用；② 玩家间 RP 交易没开 → 玩家不能拿 RP 换别人的东西
                if (currency == MarketCurrency.POINTS) {
                    val pointsCfg = com.shusheng.cobblemarket.config.CobbleMarketConfig
                    if (!pointsCfg.pointsEnabled) {
                        sendToPlayer(
                            player,
                            MarketResultPayload(
                                false,
                                Text.translatable(
                                    "cobblemarket.network.points_disabled", CurrencyService.pointsIcon()
                                )
                            )
                        )
                        return@execute
                    }
                    if (!pointsCfg.pointsMarketEnabled) {
                        sendToPlayer(
                            player,
                            MarketResultPayload(
                                false,
                                Text.translatable(
                                    "cobblemarket.network.points_market_disabled", CurrencyService.pointsIcon()
                                )
                            )
                        )
                        return@execute
                    }
                }

                // 队伍至少要留一只精灵
                if (fromParty && party.occupied() <= 1) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.party_last"))
                    )
                    return@execute
                }

                // 黑名单检查（物种 + IV + 形态：["*"]=全形态，[]=默认形态，非空=子集匹配）
                // 拦下时把**命中的那条规则**一并说清（见 RuleReasonText）
                val blacklisted = com.shusheng.cobblemarket.market.PokemonBlacklistState.get(server)
                    .findMatch(pokemon)
                if (blacklisted != null) {
                    val msg = com.shusheng.cobblemarket.market.RuleReasonText.blacklistBlocked(blacklisted)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, msg)
                    )
                    return@execute
                }

                // 携带物黑名单检查：拉黑的物品不允许随精灵上架（防绕过物品黑名单，与蛋交易联动同语义）
                val heldItem = pokemon.heldItem()
                val heldItemId = if (heldItem.isEmpty) null
                    else net.minecraft.registry.Registries.ITEM.getId(heldItem.item).toString()
                if (heldItemId != null && com.shusheng.cobblemarket.market.ItemBlacklistState.get(server)
                        .matches(heldItem, player.serverWorld.registryManager)
                ) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.held_item_blocked"))
                    )
                    return@execute
                }
                // 价格限制检查：精灵规则（物种可空=全部精灵、V 档可空=不限、形态照黑名单语义）+ 携带物规则合并。
                // ⚠ **RP 挂单整块跳过**：限价表是**主货币口径**，拿主货币的上下限去套 RP 价只会误伤（方案已拍板）
                // pokemonLimit 顺带带回"决定上下限的那两条规则"——拦下时要把理由说清（见 RuleReasonText）
                val pokemonLimit = if (currency != MarketCurrency.PRIMARY) null else
                    com.shusheng.cobblemarket.market.PokemonPriceLimitState.get(server).check(pokemon)
                val priceBounds = pokemonLimit?.let { m ->
                    val itemBounds = if (heldItemId != null)
                        com.shusheng.cobblemarket.market.ItemPriceLimitState.get(server)
                            .getPriceBounds(heldItem, player.serverWorld.registryManager)
                    else null
                    com.shusheng.cobblemarket.market.mergePriceBounds(m.bounds, itemBounds)
                }
                if (priceBounds != null) {
                    // 空区间 = 多条同档限价规则交叉锁死，任何价格都过不了校验：明确告知而不是轮流报上下限
                    if (priceBounds.isEmptyRange) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.conflict")))
                        return@execute
                    }
                    // 携带物参与限价时用带说明的提示，玩家才知道总价里包含了携带物部分
                    if (priceBounds.min != null && payload.price < priceBounds.min) {
                        val key = if (heldItemId != null) "cobblemarket.price_limit.held_below_min" else "cobblemarket.price_limit.below_min"
                        val msg = com.shusheng.cobblemarket.market.RuleReasonText.priceLimit(
                            Text.translatable(key, CurrencyHandler.formatAmount(priceBounds.min)),
                            pokemonLimit.minEntry
                        )
                        sendToPlayer(player, MarketResultPayload(false, msg))
                        return@execute
                    }
                    if (priceBounds.max != null && payload.price > priceBounds.max) {
                        val key = if (heldItemId != null) "cobblemarket.price_limit.held_above_max" else "cobblemarket.price_limit.above_max"
                        val msg = com.shusheng.cobblemarket.market.RuleReasonText.priceLimit(
                            Text.translatable(key, CurrencyHandler.formatAmount(priceBounds.max)),
                            pokemonLimit.maxEntry
                        )
                        sendToPlayer(player, MarketResultPayload(false, msg))
                        return@execute
                    }
                }

                // 上架数量上限检查
                val maxPokemonListings = com.shusheng.cobblemarket.config.CobbleMarketConfig.maxPokemonListingsPerPlayer
                if (maxPokemonListings > 0 && MarketState.get(server)
                        .countActiveBySeller(player.uuid) >= maxPokemonListings
                ) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(
                            false,
                            Text.translatable("cobblemarket.cmd.max_listings_pokemon", maxPokemonListings)
                        )
                    )
                    return@execute
                }

                // 先完成所有可能失败的操作（序列化 + 数据构建），失败时零副作用
                val heldItemStack = pokemon.heldItem()
                val world = player.serverWorld
                val nbt = try {
                    pokemon.saveToNBT(world.registryManager, NbtCompound())
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to serialize pokemon {} for listing by {}: {}", pokemonUuid, player.uuid, e.message)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed"))
                    )
                    return@execute
                }
                val now = System.currentTimeMillis()
                val extra = try {
                    buildListingExtra(pokemon, heldItemStack, player.serverWorld.registryManager)
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to build listing data for pokemon {} by {}: {}", pokemonUuid, player.uuid, e.message)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed"))
                    )
                    return@execute
                }
                val listing = MarketListing(
                    id = UUID.randomUUID(),
                    sellerUuid = player.uuid,
                    sellerName = player.name.string,
                    pokemonNbt = nbt,
                    species = pokemon.species.name,
                    level = pokemon.level,
                    shiny = pokemon.shiny,
                    price = payload.price,
                    createdAt = now,
                    expiresAt = now + com.shusheng.cobblemarket.config.CobbleMarketConfig.listingDurationDays * 24L * 60 * 60 * 1000,
                    status = ListingStatus.ACTIVE,
                    extraData = extra,
                    currency = currency
                )

                // 副作用阶段：扣手续费 → 移除精灵 → 挂单入库
                // Listing fee check
                // 费率按**这一单的币种**选：RP 有自己的费率（默认 0 = 免，见配置项注释）
                val feePercent = if (currency == MarketCurrency.POINTS)
                    com.shusheng.cobblemarket.config.CobbleMarketConfig.pointsPokemonListingFeePercent
                else com.shusheng.cobblemarket.config.CobbleMarketConfig.pokemonListingFeePercent
                // toLong 先提升：Int×Int 在价格×费率超过 21.5 亿时环绕溢出（fee 可算成负/0，逃税或凭空生钱）；
                // 逾期制裁：上架者逾期 ≥7 天 → 手续费翻倍
                // ⚠ **卡片折扣与逾期翻倍只跟主货币有关**（方案「四条铁律」第 4 条）：RP 即使把
                //   费率配成非 0，也不吃折扣、不翻倍 —— 打折等于让 RP 白嫖黑金卡权益，翻倍则是
                //   把主货币的逾期制裁套到 RP 头上。费率本身由 points*ListingFeePercent 配置。
                val feeBase = Math.ceil(payload.price.toLong() * feePercent / 100.0).toLong()
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                val fee = if (feePercent > 0) {
                    if (currency == MarketCurrency.PRIMARY) {
                        com.shusheng.cobblemarket.finance.FinanceService.applyHolderDiscount(
                            com.shusheng.cobblemarket.finance.FinanceState.get(server),
                            player.uuid,
                            com.shusheng.cobblemarket.finance.FinanceService.applyFeeMultiplier(
                                com.shusheng.cobblemarket.finance.FinanceState.get(server), player.uuid, now, feeBase
                            )
                        ).toInt()
                    } else {
                        feeBase.toInt()
                    }
                } else 0
                if (fee > 0 && !CurrencyService.remove(player, currency, fee.toLong())) {
                    sendToPlayer(
                        player, MarketResultPayload(
                            false,
                            Text.translatable("cobblemarket.cmd.need_fee", CurrencyService.amountText(fee, currency))
                        )
                    )
                    return@execute
                }

                // 移除精灵
                val removed = try {
                    if (fromParty) party.remove(pokemon) else pc.remove(pokemon)
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to remove pokemon {} from storage for listing by {}: {}", pokemonUuid, player.uuid, e.message)
                    false
                }
                if (!removed) {
                    // 精灵未实际移除：退还手续费并中止，避免同一精灵同时存在于存储和挂单
                    if (fee > 0) {
                        // ⚠ 退款**必须与扣款同币种**（都取自挂单对象）—— 扣的是 RP 就不能退成主货币
                        val refunded = CurrencyService.give(player, currency, fee.toLong())
                        if (refunded < fee.toLong()) {
                            // 只有主货币的物品后端会「少发」（背包满）；RP 是纯数字，必定全额到账
                            MarketState.get(server).addPendingBalance(player.uuid, fee.toLong() - refunded)
                            CobbleMarket.LOGGER.error(
                                "Partial fee refund for seller {} after failed pokemon removal; {} moved to pending balance",
                                player.uuid, fee.toLong() - refunded
                            )
                        }
                    }
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed"))
                    )
                    return@execute
                }

                val state = MarketState.get(server)
                state.addListing(listing)
                com.shusheng.cobblemarket.event.MarketEvents.ADD.trigger(
                    com.shusheng.cobblemarket.event.AddEvent(
                        listing,
                        fee
                    )
                )

                // 金额 + 单位合成一个 amountText（词条里也从 `%d %s` 合并成了单个 `%s`）
                val listedMsg: Text = if (fee > 0)
                    Text.translatable(
                        "cobblemarket.cmd.listed_fee",
                        pokemon.species.translatedName,
                        pokemon.level,
                        CurrencyService.amountText(payload.price, currency),
                        CurrencyService.amountText(fee, currency)
                    )
                else
                    Text.translatable(
                        "cobblemarket.cmd.listed",
                        pokemon.species.translatedName,
                        pokemon.level,
                        CurrencyService.amountText(payload.price, currency)
                    )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, listedMsg))
            }
        }

        registerC2S(SellItemPayload.ID, SellItemPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "sell_item", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val banCheckTime = System.currentTimeMillis()
                val banInfo = BanState.get(server).getBanInfo(player.uuid, banCheckTime)
                if (marketBlocked(player)) return@execute
                if (banInfo != null) {
                    // 保留 Text 对象而非 .string：翻译在客户端语言下渲染（服务端语言 ≠ 客户端语言）
                    val timeDesc: Text = if (banInfo.isPermanent)
                        Text.translatable("cobblemarket.ban.permanent")
                    else
                        Text.translatable(
                            "cobblemarket.ban.remaining",
                            BanState.formatRemaining(banInfo.expiresAt!! - banCheckTime)
                        )
                    val banMsg = if (banInfo.reason.isNotBlank())
                        Text.translatable("cobblemarket.ban.banned_msg_time_reason", timeDesc, com.shusheng.cobblemarket.market.BanState.reasonText(banInfo.reason))
                    else
                        Text.translatable("cobblemarket.ban.banned_msg_time", timeDesc)
                    sendToPlayer(player, MarketResultPayload(false, banMsg))
                    return@execute
                }
                if (payload.count <= 0 || payload.price <= 0) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }

                // ⚠ 币种来自**创建类请求**，必须严格校验：非法值直接拒绝（不能用 fromStored 容错）
                val currency = MarketCurrency.fromRequest(payload.currency)
                if (currency == null) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_currency"))
                    )
                    return@execute
                }
                // RP 上架的两道闸（理由同精灵上架）
                if (currency == MarketCurrency.POINTS) {
                    val pointsCfg = com.shusheng.cobblemarket.config.CobbleMarketConfig
                    if (!pointsCfg.pointsEnabled) {
                        sendToPlayer(
                            player,
                            MarketResultPayload(
                                false,
                                Text.translatable(
                                    "cobblemarket.network.points_disabled", CurrencyService.pointsIcon()
                                )
                            )
                        )
                        return@execute
                    }
                    if (!pointsCfg.pointsMarketEnabled) {
                        sendToPlayer(
                            player,
                            MarketResultPayload(
                                false,
                                Text.translatable(
                                    "cobblemarket.network.points_market_disabled", CurrencyService.pointsIcon()
                                )
                            )
                        )
                        return@execute
                    }
                }

                // 重建目标物品，以服务端重建的物品为准（不信任客户端 itemId/itemNbt 的一致性）
                val diagId = net.minecraft.util.Identifier.tryParse(payload.itemId)
                val diagItem = diagId?.let { Registries.ITEM.getOrEmpty(it).orElse(null) }
                val targetStack = ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, payload.itemNbt)
                if (targetStack.isEmpty) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                val authoritativeItemId = Registries.ITEM.getId(targetStack.item).toString()

                // 蛋交易开关（默认关闭）：蛋走物品链路不经过精灵黑名单，需服主显式开启
                if (isEggItem(authoritativeItemId) && !com.shusheng.cobblemarket.config.CobbleMarketConfig.eggTradingEnabled) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.egg_trading_disabled"))
                    )
                    return@execute
                }

                // 物品黑名单检查（以权威重建的物品为准，组件粒度）
                if (com.shusheng.cobblemarket.market.ItemBlacklistState.get(server)
                        .matches(targetStack, player.serverWorld.registryManager)
                ) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.item_blocked"))
                    )
                    return@execute
                }

                // 价格限制检查。
                // ⚠ **RP 挂单整块跳过**：限价表是**主货币口径**，拿主货币的上下限去套 RP 价只会误伤（方案已拍板）
                val itemPriceBounds = if (currency != MarketCurrency.PRIMARY) null else
                    com.shusheng.cobblemarket.market.ItemPriceLimitState.get(server)
                        .getPriceBounds(targetStack, player.serverWorld.registryManager)
                if (itemPriceBounds != null) {
                    // 空区间 = 多条同档限价规则交叉锁死，任何价格都过不了校验：明确告知而不是轮流报上下限
                    if (itemPriceBounds.isEmptyRange) {
                        sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.conflict")))
                        return@execute
                    }
                    if (itemPriceBounds.min != null && payload.price < itemPriceBounds.min) {
                        sendToPlayer(
                            player,
                            MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.below_min", CurrencyHandler.formatAmount(itemPriceBounds.min)))
                        )
                        return@execute
                    }
                    if (itemPriceBounds.max != null && payload.price > itemPriceBounds.max) {
                        sendToPlayer(
                            player,
                            MarketResultPayload(false, Text.translatable("cobblemarket.price_limit.above_max", CurrencyHandler.formatAmount(itemPriceBounds.max)))
                        )
                        return@execute
                    }
                }

                // 容器内容校验：黑名单/限价/蛋开关对容器内物品同样生效（防塞箱绕过）
                val containerReject = com.shusheng.cobblemarket.market.ContainerTradeCheck.check(targetStack, server)
                if (containerReject != null) {
                    sendToPlayer(player, MarketResultPayload(false, containerReject))
                    return@execute
                }

                val main = player.inventory.main
                var available = 0
                for (i in 0 until main.size) {
                    val stack = main[i]
                    if (itemsEqualForTrading(stack, targetStack)) available += stack.count
                }
                if (available < payload.count) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }

                // 上架数量上限检查
                val maxItemListings = com.shusheng.cobblemarket.config.CobbleMarketConfig.maxItemListingsPerPlayer
                if (maxItemListings > 0 && ItemMarketState.get(server)
                        .countActiveBySeller(player.uuid) >= maxItemListings
                ) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.cmd.max_listings_item", maxItemListings))
                    )
                    return@execute
                }

                // 预编码：先验证真实栈可序列化，再开始扣物品（避免扣到一半失败导致物品+手续费双失）
                val listingNbt = try {
                    main.firstOrNull { itemsEqualForTrading(it, targetStack) }
                        ?.encode(player.serverWorld.registryManager) as? NbtCompound
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.error("Failed to encode item stack for listing by {}: {}", player.uuid, e.message)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed"))
                    )
                    return@execute
                }
                if (listingNbt == null) {
                    // available 检查已保证存在匹配栈，这里只做兜底
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }

                // 副作用阶段：先扣物品、再扣手续费。
                // 顺序不能反过来：货币物品 == 上架物品时（默认钻石货币 + 上架钻石），
                // 先扣手续费会从 main 的同一栈扣掉一部分，导致物品扣除不足额；
                // 若不校验剩余量，挂单仍按 count 全额入库 = 凭空虚增货物（经济漏洞）。
                var remaining = payload.count
                for (i in 0 until main.size) {
                    val stack = main[i]
                    if (itemsEqualForTrading(stack, targetStack)) {
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
                    // 防御性校验：available 检查已保证足额，正常不可达；异常时退还已扣部分，绝不虚增挂单
                    giveBackItem(targetStack.copyWithCount(payload.count - remaining), player)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.listing_failed"))
                    )
                    return@execute
                }
                player.inventory.markDirty()

                // 手续费（基于总价）；失败时退还已扣物品并中止；逾期制裁：上架者逾期 ≥7 天 → 翻倍
                // 费率按**这一单的币种**选（同精灵上架）
                val feePercent = if (currency == MarketCurrency.POINTS)
                    com.shusheng.cobblemarket.config.CobbleMarketConfig.pointsItemListingFeePercent
                else com.shusheng.cobblemarket.config.CobbleMarketConfig.itemListingFeePercent
                val totalPrice = payload.price.toLong() * payload.count
                // ⚠ 卡片折扣与逾期翻倍只跟主货币有关（理由见精灵上架那段），RP 不吃
                val feeBase = Math.ceil(totalPrice * feePercent / 100.0).toLong()
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                val fee = if (feePercent > 0) {
                    if (currency == MarketCurrency.PRIMARY) {
                        com.shusheng.cobblemarket.finance.FinanceService.applyHolderDiscount(
                            com.shusheng.cobblemarket.finance.FinanceState.get(server),
                            player.uuid,
                            com.shusheng.cobblemarket.finance.FinanceService.applyFeeMultiplier(
                                com.shusheng.cobblemarket.finance.FinanceState.get(server), player.uuid, System.currentTimeMillis(),
                                feeBase
                            )
                        ).toInt()
                    } else {
                        feeBase.toInt()
                    }
                } else 0
                if (fee > 0 && !CurrencyService.remove(player, currency, fee.toLong())) {
                    giveBackItem(targetStack.copyWithCount(payload.count), player)
                    sendToPlayer(
                        player, MarketResultPayload(
                            false,
                            Text.translatable("cobblemarket.cmd.need_fee", CurrencyService.amountText(fee, currency))
                        )
                    )
                    return@execute
                }

                // 手动同步背包，确保客户端先收到背包更新、再收到上架结果
                player.currentScreenHandler.sendContentUpdates()

                val now = System.currentTimeMillis()
                val listing = ItemListing(
                    id = UUID.randomUUID(),
                    sellerUuid = player.uuid,
                    sellerName = player.name.string,
                    itemId = authoritativeItemId,
                    itemNbt = listingNbt,
                    count = payload.count,
                    price = payload.price,
                    createdAt = now,
                    expiresAt = now + com.shusheng.cobblemarket.config.CobbleMarketConfig.listingDurationDays * 24L * 60 * 60 * 1000,
                    status = ListingStatus.ACTIVE,
                    currency = currency
                )

                val state = ItemMarketState.get(server)
                state.addListing(listing)

                com.shusheng.cobblemarket.event.TransactionHistory.get(server).addRecord(
                    com.shusheng.cobblemarket.event.TransactionRecord(
                        timestamp = System.currentTimeMillis(),
                        type = com.shusheng.cobblemarket.event.TransactionType.ADD,
                        category = com.shusheng.cobblemarket.event.TransactionCategory.ITEM,
                        sellerUuid = player.uuid,
                        sellerName = player.name.string,
                        buyerUuid = null,
                        buyerName = "",
                        species = payload.itemId,
                        price = payload.price,
                        fee = fee,
                        detail = com.shusheng.cobblemarket.util.RecordDetail.item(listing.itemNbt, listing.count, server.registryManager),
                        currency = currency
                    )
                )

                val listedMsg: Text = if (fee > 0)
                    Text.translatable(
                        "cobblemarket.item.listed_fee",
                        payload.count,
                        com.shusheng.cobblemarket.util.ItemNameText.rarityColoredName(targetStack)
                            ?: targetStack.item.name,
                        CurrencyService.amountText(payload.price, currency),
                        CurrencyService.amountText(fee, currency)
                    )
                else
                    Text.translatable(
                        "cobblemarket.item.listed",
                        payload.count,
                        com.shusheng.cobblemarket.util.ItemNameText.rarityColoredName(targetStack)
                            ?: targetStack.item.name,
                        CurrencyService.amountText(payload.price, currency)
                    )
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, listedMsg))
            }
        }

        registerC2S(RequestItemMarketPayload.ID, RequestItemMarketPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_item_market", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = ItemMarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())

                val sortMode = parseSortMode(payload.sortMode)
                // 币种（展示层过滤）：非法值直接拒绝，理由同 RequestMarketPayload
                val currency = MarketCurrency.fromRequest(payload.currency)
                if (currency == null) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_currency"))
                    )
                    return@execute
                }
                val results = state.search(
                    currency = currency,
                    sortBy = sortMode,
                    sellerUuid = if (payload.mineOnly) player.uuid else null
                ).let { list ->
                    val query = payload.query.trim()
                    if (query.isEmpty()) list
                    // 同一个搜索框也匹配**卖家名**（2026-09-24，与精灵市场 / 拍卖场同一口径）
                    else list.filter {
                        it.sellerName.contains(query, ignoreCase = true) ||
                            matchesItemQuery(it.itemId, it.itemNbt, payload.itemIds.take(MAX_ITEM_IDS).toSet(), payload.tmMoves.toSet(), payload.enchants.toSet(), server.overworld.registryManager)
                    }
                }

                // 上限 84（12 行）：网格页容量随窗口，上限低于容量会导致末行空槽+多余分页；
                // 84 是服务器压力折中：恶意高频请求（250ms 节流下每秒 4 次）的带宽攻击面减半，正常窗口（≤12 行）无感知
                // 物品 itemNbt 通常几百字节（蛋较大，最坏全蛋页约 1MB，可接受）
                val pageSize = payload.pageSize.coerceIn(1, 84)
                val totalPages = ((results.size - 1) / pageSize) + 1
                val clampedPage = payload.page.coerceIn(1, maxOf(1, totalPages))

                val pageEntries = if (results.isEmpty()) emptyList() else {
                    val start = (clampedPage - 1) * pageSize
                    results.drop(start).take(pageSize).map { listing ->
                        ItemEntry(
                            id = listing.id,
                            sellerUuid = listing.sellerUuid,
                            sellerName = listing.sellerName,
                            itemId = listing.itemId,
                            itemNbt = listing.itemNbt,
                            count = listing.count,
                            price = listing.price,
                            currencyName = CurrencyHandler.getCurrencyId(),
                            currency = currency.name
                        )
                    }
                }

                sendToPlayer(
                    player,
                    ItemMarketDataPayload(
                        pageEntries,
                        maxOf(1, totalPages),
                        clampedPage,
                        MarketState.get(server).getPendingBalance(player.uuid),
                        ItemMarketState.get(server).getPendingReturns(player.uuid).size
                    )
                )
            }
        }

        registerC2S(BuyItemPayload.ID, BuyItemPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "buy_item", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val banCheckTime = System.currentTimeMillis()
                val banInfo = BanState.get(server).getBanInfo(player.uuid, banCheckTime)
                if (marketBlocked(player)) return@execute
                if (banInfo != null) {
                    // 保留 Text 对象而非 .string：翻译在客户端语言下渲染（服务端语言 ≠ 客户端语言）
                    val timeDesc: Text = if (banInfo.isPermanent)
                        Text.translatable("cobblemarket.ban.permanent")
                    else
                        Text.translatable(
                            "cobblemarket.ban.remaining",
                            BanState.formatRemaining(banInfo.expiresAt!! - banCheckTime)
                        )
                    val banMsg = if (banInfo.reason.isNotBlank())
                        Text.translatable("cobblemarket.ban.banned_msg_time_reason", timeDesc, com.shusheng.cobblemarket.market.BanState.reasonText(banInfo.reason))
                    else
                        Text.translatable("cobblemarket.ban.banned_msg_time", timeDesc)
                    sendToPlayer(player, MarketResultPayload(false, banMsg))
                    return@execute
                }
                val state = ItemMarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())
                val listing = state.getListing(payload.listingId)
                if (listing == null || !listing.isActive()) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                if (listing.sellerUuid == player.uuid) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.cannot_buy_own"))
                    )
                    return@execute
                }
                // 黑名单检查：拦截上架后被加入黑名单的存量挂单（治理即时生效，组件粒度）
                val listingStack = ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, listing.itemNbt)
                if (com.shusheng.cobblemarket.market.ItemBlacklistState.get(server)
                        .matches(listingStack, player.serverWorld.registryManager)
                ) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.blacklist.item_blocked"))
                    )
                    return@execute
                }
                // 蛋交易开关：关闭后拦截存量蛋挂单的购买（与黑名单一致，治理即时生效）
                if (isEggItem(listing.itemId) && !com.shusheng.cobblemarket.config.CobbleMarketConfig.eggTradingEnabled) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.egg_trading_disabled"))
                    )
                    return@execute
                }
                val count = payload.count
                if (count <= 0) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                // 库存不足（可能被其他玩家抢先购买）：提示剩余数量，引导重新输入
                if (count > listing.count) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.insufficient_stock", listing.count))
                    )
                    return@execute
                }
                val stack = ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, listing.itemNbt)
                if (stack.isEmpty) {
                    // 挂单物品数据已失效（如 mod 被移除）：拒绝交易，避免买家买空气
                    CobbleMarket.LOGGER.warn("Item listing {} has invalid item data; rejecting purchase", listing.id)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.item_invalid"))
                    )
                    return@execute
                }
                stack.count = count

                if (!canFitInInventory(player, stack)) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.inventory_full"))
                    )
                    return@execute
                }

                val totalPriceLong = listing.price.toLong() * count
                if (totalPriceLong <= 0 || totalPriceLong > Int.MAX_VALUE) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.invalid_price"))
                    )
                    return@execute
                }
                val totalPrice = totalPriceLong.toInt()
                // ⚠ **买家侧币种闸**：买 RP 挂单 = 把 RP 从买家转到卖家，正是「玩家间 RP 交易」
                //   开关要拦的那条后门（只拦创建不拦成交 = 后门照开）
                if (pointsGateBlocked(player, listing.currency)) return@execute
                // 喵喵支付（消费贷）：信用校验 + 准备金垫付；否则现金扣款
                val meowth = payload.planIndex >= 0
                // ⚠ **RP 挂单不接受喵喵支付**：准备金池垫付的是主货币，等于跨币种兑换，违反「不可兑换」
                //   （方案「另三处必须特殊处理」）。客户端购买弹窗也会藏掉喵喵入口，这里是服务端兜底。
                if (meowth && listing.currency != MarketCurrency.PRIMARY) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(
                            false,
                            Text.translatable(
                                "cobblemarket.network.no_meowth_for_points", CurrencyService.pointsIcon()
                            )
                        )
                    )
                    return@execute
                }
                if (meowth) {
                    val payErr = com.shusheng.cobblemarket.finance.FinanceService.meowthCheck(
                        player, totalPriceLong, payload.planIndex, System.currentTimeMillis()
                    )
                    if (payErr != null) {
                        sendToPlayer(player, MarketResultPayload(false, payErr))
                        return@execute
                    }
                    com.shusheng.cobblemarket.finance.FinanceState.get(server).withdrawReserve(totalPriceLong)
                } else if (!CurrencyService.remove(player, listing.currency, totalPriceLong)) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(
                            false,
                            Text.translatable(
                                "cobblemarket.network.need_diamonds",
                                CurrencyService.amountText(totalPrice, listing.currency)
                            )
                        )
                    )
                    return@execute
                }

                player.inventory.insertStack(stack)
                if (!stack.isEmpty) {
                    // 未完全放入（insertStack 返回 true 也可能只是部分插入）：回滚已放入部分，避免复制
                    val inserted = count - stack.count
                    if (inserted > 0) {
                        var toRemove = inserted
                        // 只遍历 main：insertStack 只往 main 放，回滚范围与插入范围严格一致，
                        // 避免误扣玩家 armor/offhand 里本来就有的相同物品
                        for (i in 0 until player.inventory.main.size) {
                            val slot = player.inventory.main[i]
                            if (itemsEqualForTrading(slot, stack)) {
                                val r = minOf(toRemove, slot.count)
                                slot.decrement(r)
                                toRemove -= r
                                if (toRemove <= 0) break
                            }
                        }
                        player.inventory.markDirty()
                    }
                    if (meowth) {
                        // 信用支付失败回池（贷款未创建，无需撤销）
                        com.shusheng.cobblemarket.finance.FinanceState.get(server).depositReserve(totalPriceLong)
                    } else {
                        // ⚠ 退款与扣款**同币种**（都取自挂单对象）：扣的是 RP 就不能退成主货币
                        val refunded = CurrencyService.give(player, listing.currency, totalPrice.toLong())
                        if (refunded < totalPrice.toLong()) {
                            // 退款未全部到账（背包满）：差额转入待领余额兜底，避免买家钱被吞
                            MarketState.get(server).addPendingBalance(player.uuid, totalPrice.toLong() - refunded)
                            CobbleMarket.LOGGER.error(
                                "Partial refund for buyer {} on item listing {}; {} moved to pending balance",
                                player.uuid, listing.id, totalPrice.toLong() - refunded
                            )
                        }
                    }
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.inventory_full"))
                    )
                    return@execute
                }

                // 喵喵支付记账（货物已放入）：创建消费贷 + 审计 CSV + IP 记录
                if (meowth) {
                    com.shusheng.cobblemarket.finance.FinanceService.meowthPaySettle(
                        server, player, totalPriceLong, payload.planIndex,
                        com.shusheng.cobblemarket.finance.LoanSource.ITEM_BUY, System.currentTimeMillis()
                    )
                }
                // 卖家入账：主货币进待领余额、RP 直接到账（见 CurrencyService.creditSeller）
                CurrencyService.creditSeller(server, listing.sellerUuid, listing.currency, totalPrice.toLong())
                // 卖家到账音：在线才发（离线那位由上线时的「离线收益」提示兜底）
                server.playerManager.getPlayer(listing.sellerUuid)?.let { sendSound(it, "market_sold") }
                // 金融系统成交挂钩子：物品购买成交计入买家交易额（喵喵支付标 loanFunded 不计额，防借→买循环）。
                // ⚠ **RP 交易整块跳过**（方案「统计隔离」）：让 RP 来回刷交易量 = 白嫖卡片资格与更高额度
                if (listing.currency == MarketCurrency.PRIMARY) {
                    com.shusheng.cobblemarket.finance.FinanceState.get(server).recordTrade(
                        server, player.uuid, listing.sellerUuid, totalPrice.toLong(), System.currentTimeMillis(),
                        loanFunded = meowth
                    )
                }
                // RP 审计账本：主货币那本走上面的 recordTrade，RP 这本走这里（服主逐笔对账用）。
                // 物品市场无手续费 ⇒ 两侧等值
                if (listing.currency == MarketCurrency.POINTS) {
                    PointsAuditLogger.logMarketTrade(
                        server, player.uuid, player.name.string,
                        listing.sellerUuid, listing.sellerName,
                        totalPriceLong, totalPrice.toLong(),
                        "市场购买：${listing.itemId} ×$count",
                        "Market purchase: ${listing.itemId} x$count"
                    )
                }
                listing.count -= count
                if (listing.count <= 0) {
                    listing.status = ListingStatus.SOLD
                    // 库存清空且货物已全部交付买家，挂单生命周期终结，立即删除避免存档膨胀
                    state.removeListing(listing.id)
                } else {
                    state.markModified()
                }

                com.shusheng.cobblemarket.event.TransactionHistory.get(server).addRecord(
                    com.shusheng.cobblemarket.event.TransactionRecord(
                        timestamp = System.currentTimeMillis(),
                        type = com.shusheng.cobblemarket.event.TransactionType.PURCHASE,
                        category = com.shusheng.cobblemarket.event.TransactionCategory.ITEM,
                        sellerUuid = listing.sellerUuid,
                        sellerName = listing.sellerName,
                        buyerUuid = player.uuid,
                        buyerName = player.name.string,
                        species = listing.itemId,
                        price = totalPrice,
                        fee = 0,
                        detail = com.shusheng.cobblemarket.util.RecordDetail.item(listing.itemNbt, listing.count, server.registryManager),
                        currency = listing.currency
                    )
                )

                // 物品庆祝动画：与精灵同一口径——所有权首次转到买家名下时播一次。
                // 数量按本次实际到手传（listing.itemNbt 存的是上架总量，部分购买时不能直接用它）
                CelebrationNetwork.sendItem(player, listing.itemNbt, count, CelebrationSource.MARKET)

                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                // 物品名走稀有度色（= 物品栏悬停的名字颜色）。优先用完整栈重建：
                // 能带上自定义名与组件带来的稀有度；重建不出才退回裸 itemId
                val boughtItemName = com.shusheng.cobblemarket.util.ItemNameText
                    .rarityColoredNameOfNbt(server.registryManager, listing.itemNbt)
                    ?: com.shusheng.cobblemarket.util.ItemNameText.rarityColoredNameById(listing.itemId)
                    ?: Text.literal(listing.itemId)
                sendToPlayer(
                    player,
                    MarketResultPayload(
                        true,
                        if (meowth)
                            Text.translatable(
                                "cobblemarket.buy_confirm.paid_by_meowth_item",
                                count,
                                boughtItemName,
                                CurrencyService.amountText(totalPrice, listing.currency),
                                com.shusheng.cobblemarket.config.CobbleMarketConfig.loanPlans.getOrNull(payload.planIndex)?.periods ?: 1
                            )
                        else
                            Text.translatable(
                                "cobblemarket.item.bought",
                                count,
                                boughtItemName,
                                CurrencyService.amountText(totalPrice, listing.currency)
                            )
                    )
                )
                // 喵喵支付成功后回发额度快照（欠款已变）
                if (meowth) {
                    com.shusheng.cobblemarket.network.FinanceNetwork.sendCreditInfo(
                        player, com.shusheng.cobblemarket.finance.FinanceState.get(server), System.currentTimeMillis()
                    )
                }

                // 物品名走稀有度色；主体绿 —— 与精灵版「已卖出」同一配色
                val soldItemName = com.shusheng.cobblemarket.util.ItemNameText
                    .rarityColoredNameOfNbt(server.registryManager, listing.itemNbt)
                    ?: com.shusheng.cobblemarket.util.ItemNameText.rarityColoredNameById(listing.itemId)
                    ?: Text.literal(listing.itemId)
                // 卖家离线则入队补发
                com.shusheng.cobblemarket.market.OfflineMessageState.notify(
                    server, listing.sellerUuid,
                    Text.translatable("cobblemarket.network.sold", soldItemName).formatted(Formatting.GREEN)
                )
            }
        }

        registerC2S(CancelItemPayload.ID, CancelItemPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "cancel_item", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 封禁只禁止交易；取消挂单是取回自己的资产，允许
                val state = ItemMarketState.get(server)
                state.expireOldListings(System.currentTimeMillis())
                val listing = state.getListing(payload.listingId)
                if (listing == null || !listing.isActive()) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found"))
                    )
                    return@execute
                }
                if (listing.sellerUuid != player.uuid) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.cmd.not_your_listing"))
                    )
                    return@execute
                }

                val stack = ItemStack.fromNbtOrEmpty(player.serverWorld.registryManager, listing.itemNbt)
                if (stack.isEmpty) {
                    // 挂单物品数据已失效（如 mod 被移除）：保留挂单状态，等待管理员处理
                    CobbleMarket.LOGGER.warn("Item listing {} has invalid item data; keeping it active", listing.id)
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.item_invalid"))
                    )
                    return@execute
                }
                stack.count = listing.count
                if (!canFitInInventory(player, stack)) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.inventory_full"))
                    )
                    return@execute
                }
                player.inventory.insertStack(stack)
                if (!stack.isEmpty) {
                    // 未完全放入（insertStack 返回 true 也可能只是部分插入）：回滚已放入部分，挂单保持 ACTIVE，避免物品凭空消失
                    val inserted = listing.count - stack.count
                    if (inserted > 0) {
                        var toRemove = inserted
                        // 只遍历 main：insertStack 只往 main 放，回滚范围与插入范围严格一致，
                        // 避免误扣玩家 armor/offhand 里本来就有的相同物品
                        for (i in 0 until player.inventory.main.size) {
                            val slot = player.inventory.main[i]
                            if (itemsEqualForTrading(slot, stack)) {
                                val r = minOf(toRemove, slot.count)
                                slot.decrement(r)
                                toRemove -= r
                                if (toRemove <= 0) break
                            }
                        }
                        player.inventory.markDirty()
                    }
                    CobbleMarket.LOGGER.warn(
                        "Failed to return item to seller {} for listing {}; keeping it active",
                        player.uuid, listing.id
                    )
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.inventory_full"))
                    )
                    return@execute
                }
                listing.status = ListingStatus.CANCELLED
                state.markModified()

                com.shusheng.cobblemarket.event.TransactionHistory.get(server).addRecord(
                    com.shusheng.cobblemarket.event.TransactionRecord(
                        timestamp = System.currentTimeMillis(),
                        type = com.shusheng.cobblemarket.event.TransactionType.CANCEL,
                        category = com.shusheng.cobblemarket.event.TransactionCategory.ITEM,
                        sellerUuid = player.uuid,
                        sellerName = listing.sellerName,
                        buyerUuid = null,
                        buyerName = "",
                        species = listing.itemId,
                        price = listing.price,
                        fee = 0,
                        detail = com.shusheng.cobblemarket.util.RecordDetail.item(listing.itemNbt, listing.count, server.registryManager),
                        currency = listing.currency
                    )
                )

                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(
                    player,
                    MarketResultPayload(true, Text.translatable("cobblemarket.item.cancelled"))
                )

                // 物品已回卖家背包，挂单生命周期终结，立即删除避免存档膨胀
                state.removeListing(listing.id)
            }
        }

        registerC2S(CollectBalancePayload.ID, CollectBalancePayload.CODEC) { _, player ->
            if (!RequestThrottle.allowNotify(player, "collect_balance", RequestThrottle.WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = MarketState.get(server)
                // 先发钱、按实际发放量清账，发不完的留在账本，避免余额蒸发
                val amount = state.getPendingBalance(player.uuid)
                if (amount <= 0) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.cmd.no_earnings"))
                    )
                    return@execute
                }
                val given = CurrencyHandler.give(player, amount)
                if (given <= 0) {
                    sendToPlayer(
                        player,
                        MarketResultPayload(false, Text.translatable("cobblemarket.network.collect_failed"))
                    )
                    return@execute
                }
                // 只清掉已实际发放的部分，差额留在账本（单方法内完成，无中间态）
                state.claimPendingBalance(player.uuid, given)
                val msg = if (given < amount)
                    Text.translatable("cobblemarket.cmd.collected_partial", CurrencyHandler.goldAmount(given), CurrencyHandler.goldCurrencyText())
                else
                    Text.translatable("cobblemarket.cmd.collected", CurrencyHandler.goldAmount(amount), CurrencyHandler.goldCurrencyText())
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, msg))
            }
        }

        // 管理面板底部的市场总开关按钮：写配置 + 全员广播，与命令同逻辑。
        // ⚠ 2026-09-24 起改判 **MARKET_SWITCH**（原先跟 MARKET 走，等于下架权限附赠停市权）
        registerC2S(SetMarketEnabledPayload.ID, SetMarketEnabledPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.MARKET_SWITCH)) return@registerC2S
            val server = player.server
            server.execute { toggleMarketEnabled(server, payload.enabled) }
        }

        registerC2S(RequestHistoryPayload.ID, RequestHistoryPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_history", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val history = com.shusheng.cobblemarket.event.TransactionHistory.get(server)
                // ⚠ 2026-09-24 起改判 HISTORY（原先跟 MARKET 走）：能下架挂单 ≠ 该看全服账本
                val isAdmin = MarketPermissions.has(player, MarketPermissions.HISTORY)
                val all = payload.all && isAdmin
                // 数据源：最近 14 天 CSV 账本 + 内存记录兜底合并去重（键用 CSV 秒级时间）——
                // 内存上限（全服 200 条）外的个人历史从 CSV 回读，不再被其他玩家的交易挤空
                val csvRecords = com.shusheng.cobblemarket.event.TransactionFileLogger.readRecentRecords(
                    if (all) null else player.uuid,
                    if (all) null else player.name.string,
                    14)
                val memoryRecords = if (all) history.getRecords() else history.getRecordsByPlayer(player.uuid)
                fun keyOf(r: com.shusheng.cobblemarket.event.TransactionRecord): String =
                    "${com.shusheng.cobblemarket.event.TransactionFileLogger.timestampKey(r.timestamp)}|${r.type}|${r.category}|${r.sellerName}|${r.buyerName}|${r.species}|${r.price}"
                val records = (csvRecords + memoryRecords).distinctBy { keyOf(it) }
                    .sortedByDescending { it.timestamp }
                // 界面内最多展示 500 条（滚动浏览），更早记录见 config/cobblemarket/history/ CSV 日志
                val entries = records.take(500).map { r ->
                    // BUY 判定：新记录按 UUID；旧格式 CSV 行（占位 UUID）回退名字（服务器上名字唯一）
                    val t = if (r.type == com.shusheng.cobblemarket.event.TransactionType.PURCHASE &&
                        (r.buyerUuid == player.uuid ||
                            (r.buyerUuid == null && r.buyerName == player.name.string) ||
                            (r.sellerUuid == com.shusheng.cobblemarket.event.LEGACY_RECORD_UUID && r.buyerName == player.name.string))
                    ) "BUY" else r.type.name
                    HistoryEntry(
                        t, r.category.name, r.species, r.price, r.buyerName, r.sellerName, r.timestamp,
                        r.currency.name, r.primaryType,
                        // 物品名上色用：稀有度**就存在详情列里**（产生记录时手上还有 nbt，见 RecordDetail.item）
                        // ⇒ 这里只提取那段短字符串发下去，不把整个详情列塞进 payload
                        com.shusheng.cobblemarket.util.RecordDetail.rarityFromDetail(r.detail),
                        r.fromShop
                    )
                }
                sendToPlayer(player, HistoryDataPayload(entries))
            }
        }

        registerC2S(RequestPokemonReturnPayload.ID, RequestPokemonReturnPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_pokemon_return", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                AuctionNetwork.settleAndBroadcast(server) // 打开返还界面即触发到期拍卖结算
                val state = MarketState.get(server)
                val returns = state.getPendingReturns(player.uuid)
                // 协议分页：只打包当前页，避免退回列表过大时打出超大包
                val pageSize = payload.pageSize.coerceIn(1, 30)
                val totalPages = maxOf(1, ((returns.size - 1) / pageSize) + 1)
                val clampedPage = payload.page.coerceIn(1, totalPages)
                val pageItems = if (returns.isEmpty()) emptyList() else {
                    returns.drop((clampedPage - 1) * pageSize).take(pageSize)
                }
                val previews = pageItems.mapIndexedNotNull { i, listing ->
                    try {
                        val pokemon = com.shusheng.cobblemarket.util.PokemonLoader.fromNbt(
                            player.serverWorld.registryManager, listing.pokemonNbt
                        )
                        toPreview(pokemon, "return", i, player.serverWorld.registryManager, player.server)
                    } catch (e: Exception) {
                        // 单条损坏不影响其他退回的预览
                        CobbleMarket.LOGGER.warn("Failed to build return preview for listing {}: {}", listing.id, e.message)
                        null
                    }
                }
                sendToPlayer(player, PokemonReturnDataPayload(previews, totalPages, clampedPage))
            }
        }

        registerC2S(ClaimPokemonReturnPayload.ID, ClaimPokemonReturnPayload.CODEC) { _, player ->
            if (!RequestThrottle.allowNotify(player, "claim_pokemon_return", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                // 对战中不可领取：精灵进队会被战斗切换面板切出参战（凭空补员），战斗结束后再领
                if (com.shusheng.cobblemarket.market.BattleGuard.isPlayerInBattle(player.uuid)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.in_battle")))
                    return@execute
                }
                val state = MarketState.get(server)
                val returned = state.claimReturns(player)
                val remaining = state.getPendingReturns(player.uuid).size
                val msg = if (remaining > 0)
                    Text.translatable("cobblemarket.return.claimed", returned, remaining)
                else
                    Text.translatable("cobblemarket.return.claimed_all", returned)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, msg))
            }
        }

        registerC2S(RequestItemReturnPayload.ID, RequestItemReturnPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "request_item_return", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                AuctionNetwork.settleAndBroadcast(server) // 打开返还界面即触发到期拍卖结算
                val state = ItemMarketState.get(server)
                val returns = state.getPendingReturns(player.uuid)
                // 协议分页：只打包当前页，避免退回列表过大时打出超大包
                val pageSize = payload.pageSize.coerceIn(1, 42)
                val totalPages = maxOf(1, ((returns.size - 1) / pageSize) + 1)
                val clampedPage = payload.page.coerceIn(1, totalPages)
                val pageItems = if (returns.isEmpty()) emptyList() else {
                    returns.drop((clampedPage - 1) * pageSize).take(pageSize)
                }
                val entries = pageItems.map { listing ->
                    ItemEntry(
                        id = listing.id,
                        sellerUuid = listing.sellerUuid,
                        sellerName = listing.sellerName,
                        itemId = listing.itemId,
                        itemNbt = listing.itemNbt,
                        count = listing.count,
                        price = listing.price,
                        currencyName = CurrencyHandler.getCurrencyId(),
                        // 待领取里显示的是**这条挂单原本的币种**（可能就是 RP），不能按当前主货币渲染
                        currency = listing.currency.name
                    )
                }
                sendToPlayer(player, ItemReturnDataPayload(entries, totalPages, clampedPage))
            }
        }

        registerC2S(ClaimItemReturnPayload.ID, ClaimItemReturnPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "claim_item_return", RequestThrottle.REPEAT_WRITE_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val state = ItemMarketState.get(server)
                val returned = state.claimReturns(player, payload.dropOverflow)
                val remaining = state.getPendingReturns(player.uuid).size
                val msg = if (remaining > 0)
                    Text.translatable("cobblemarket.return.item_claimed", returned, remaining)
                else
                    Text.translatable("cobblemarket.return.item_claimed_all", returned)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                sendToPlayer(player, MarketResultPayload(true, msg))
            }
        }
    }

    /** 构建挂单展示数据；调用方负责 try-catch（上架路径要求零副作用后才扣费/移除）。 */
    // 旧挂单（aspects 功能上线前上架的）没有 aspects 数据，按性别兜底：
    // 性别差异物种的形态 aspect 恰好名为 male/female
    /** 证章 id 列表解析（extraData 逗号分隔；外观展示用） */
    private fun parseMarks(detail: Map<String, String>): List<String> =
        detail["marks"]?.takeIf { it.isNotBlank() }?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()

    private fun parseAspects(detail: Map<String, String>): List<String> =
        detail["aspects"]?.takeIf { it.isNotBlank() }?.split(",")
            ?: when (detail["gender"]) {
                "MALE" -> listOf("male")
                "FEMALE" -> listOf("female")
                else -> emptyList()
            }

    private fun buildListingExtra(
        pokemon: com.cobblemon.mod.common.pokemon.Pokemon,
        heldItemStack: ItemStack,
        registryManager: net.minecraft.registry.RegistryWrapper.WrapperLookup
    ): Map<String, String> {
        val htIvs = pokemon.ivs.hyperTrainedIVs // 极限特训值表（-1 = 未特训）
        val extra = mutableMapOf(
            "speciesId" to pokemon.species.resourceIdentifier.toString(),
            "speciesName" to pokemon.species.translatedName.string,
            "speciesKey" to com.shusheng.cobblemarket.util.SpeciesText.translationKey(pokemon.species),
            "primaryType" to "cobblemon.type.${pokemon.primaryType.name.lowercase()}",
            // 太晶属性（Cobblemon **官方字段** `Pokemon.teraType`）—— **常驻下发**。
            //
            // ⚠ 曾经写成「只在 ≠ 主属性时才下发」，被用户否掉了：那等于把它定义成**附属模组专属功能**
            //   （没装模组的玩家界面一模一样），而**玩家不一定知道这只精灵的主属性是哪个**
            //   （异兽、形态变化尤其），太晶本身就该是写明的一项，不该由「跟主属性比一比」决定给不给看。
            // 附属模组只能写官方这个字段 ⇒ 我们读它 = 天然兼容，不需要特判。
            "teraType" to "cobblemon.type.${pokemon.teraType.id.path}",
            // 超极巨化：**挂单时就写下来**，供列表筛选用（旧挂单没这个键 ⇒ 筛选那侧按缺键回退读 NBT）
            "gmaxFactor" to pokemon.gmaxFactor.toString(),
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
            "evsHp" to pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.HP].toString(),
            "evsAtk" to pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK].toString(),
            "evsDef" to pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE].toString(),
            "evsSpAtk" to pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK].toString(),
            "evsSpDef" to pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE].toString(),
            "evsSpd" to pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED].toString(),
            "friendship" to pokemon.friendship.toString(),
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
            // 精灵形态（性别/地区等），客户端渲染 3D 图标用；逗号分隔，aspect 名不含逗号
            "aspects" to pokemon.aspects.joinToString(","),
            "marks" to pokemon.marks.map { it.texture.toString() }.joinToString(","),
            // 技能槽快照（4 个槽用「,」连接、空槽空串）：悬停窗技能块用，见 MoveText
            "moves" to com.shusheng.cobblemarket.util.MoveText.of(pokemon)
        )
        pokemon.secondaryType?.let { extra["secondaryType"] = "cobblemon.type.${it.name.lowercase()}" }
        return extra
    }

    private fun toPreview(
        pokemon: com.cobblemon.mod.common.pokemon.Pokemon,
        source: String,
        slot: Int,
        registryManager: net.minecraft.registry.RegistryWrapper.WrapperLookup,
        // 初始训练家要把玩家 UUID 解析成名字（`PokemonOT`），故需要 server
        server: net.minecraft.server.MinecraftServer
    ) =
        PokemonPreview(
            uuid = pokemon.uuid,
            // 只发翻译 key，客户端本地翻译——服务端无玩家语言上下文，translatedName 永远是默认语言。
            // 必须走 SpeciesText.translationKey（直接取 Cobblemon 生成的翻译 key）：
            // 手拼 key 容易踩 species.name（显示名，如 "Indeedee" 大写）与资源路径的差异，key 拼错
            // 客户端只能显示 key 原文，且中文搜索跟着失效。
            species = com.shusheng.cobblemarket.util.SpeciesText.translationKey(pokemon.species),
            speciesId = pokemon.species.resourceIdentifier.toString(),
            speciesName = pokemon.species.name,
            level = pokemon.level,
            shiny = pokemon.shiny,
            gender = pokemon.gender.name,
            nature = "cobblemon.nature.${pokemon.effectiveNature.name.path}",
            natureBase = "cobblemon.nature.${pokemon.nature.name.path}",
            ability = "cobblemon.ability.${pokemon.ability.name}",
            ivsHp = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.HP] ?: 0,
            ivsAtk = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK] ?: 0,
            ivsDef = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE] ?: 0,
            ivsSpAtk = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK] ?: 0,
            ivsSpDef = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE] ?: 0,
            ivsSpd = pokemon.ivs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED] ?: 0,
            htHp = pokemon.ivs.hyperTrainedIVs[com.cobblemon.mod.common.api.pokemon.stats.Stats.HP] ?: -1,
            htAtk = pokemon.ivs.hyperTrainedIVs[com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK] ?: -1,
            htDef = pokemon.ivs.hyperTrainedIVs[com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE] ?: -1,
            htSpAtk = pokemon.ivs.hyperTrainedIVs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK] ?: -1,
            htSpDef = pokemon.ivs.hyperTrainedIVs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE] ?: -1,
            htSpd = pokemon.ivs.hyperTrainedIVs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED] ?: -1,
            ball = "item.${pokemon.caughtBall.name.namespace}.${pokemon.caughtBall.name.path}",
            primaryType = "cobblemon.type.${pokemon.primaryType.name.lowercase()}",
            secondaryType = pokemon.secondaryType?.let { "cobblemon.type.${it.name.lowercase()}" } ?: "",
            // ⚠ **这一行曾经漏了**（2026-09-26 用户实测抓到）：`write` / `read` 早就有 `teraType`，
            //   但构造时没传 ⇒ 一直用默认值空串 ⇒ **所有「选自己的精灵」的界面（上架选择 /
            //   拍卖上架选择 / 求购单交付）永远不显示太晶**。
            //   而精灵市场那边显示的是**挂单的 extraData**（那条路填了值）⇒ 表现成「只有上架过的
            //   精灵才显示太晶」，看着像"旧数据没有"。
            //   口径与挂单那条完全一致（`MarketNetwork` 写 extraData 处），别各写各的。
            teraType = "cobblemon.type.${pokemon.teraType.id.path}",
            source = source,
            slot = slot,
            // 携带物：**总是**下发（行信息 / 弹窗 / 图标都要如实显示它带了什么）；
            // 「模型手里拿不拿」由 heldItemVisible 单独带下去，客户端渲染模型时读
            heldItemId = if (pokemon.heldItem().isEmpty) "" else Registries.ITEM.getId(pokemon.heldItem().item).toString(),
            heldItemNbt = encodeHeldItemNbt(pokemon.heldItem(), registryManager),
            heldItemVisible = pokemon.heldItemVisible,
            aspects = pokemon.aspects.toList(),
            evsHp = pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.HP] ?: 0,
            evsAtk = pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK] ?: 0,
            evsDef = pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE] ?: 0,
            evsSpAtk = pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK] ?: 0,
            evsSpDef = pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE] ?: 0,
            evsSpd = pokemon.evs[com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED] ?: 0,
            friendship = pokemon.friendship,
            marks = pokemon.marks.map { it.texture.toString() },
            sizeCategory = PokemonSize.from(pokemon),
            // 技能槽快照（4 槽、空槽空串）：悬停窗技能块用，见 MoveText
            moves = com.shusheng.cobblemarket.util.MoveText.of(pokemon),
            // 极巨化 / 初始训练家：预览的是**活精灵**，直接从 Pokemon 读（不走 NBT）
            gmaxFactor = pokemon.gmaxFactor,
            dmaxLevel = pokemon.dmaxLevel,
            originalTrainer = com.shusheng.cobblemarket.util.PokemonOT.from(pokemon, server),
            // 球的 item id：球种行取稀有度色用（词条 key 单独走 ball 字段）——
            // 与拍卖/求购侧的 `"ballItem"` 同款，见 ItemNameText.ballLine
            ballItem = pokemon.caughtBall.name.toString()
        )

    fun openScreen(player: ServerPlayerEntity) {
        sendToPlayer(player, OpenMarketPayload(0))
    }

    private fun removeCurrency(player: ServerPlayerEntity, amount: Int) =
        com.shusheng.cobblemarket.config.CurrencyHandler.remove(player, amount)

    private fun giveCurrency(player: ServerPlayerEntity, amount: Int): Long =
        com.shusheng.cobblemarket.config.CurrencyHandler.give(player, amount.toLong())
}
