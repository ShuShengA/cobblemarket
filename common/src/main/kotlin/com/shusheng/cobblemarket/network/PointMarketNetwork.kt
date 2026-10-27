package com.shusheng.cobblemarket.network

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.currency.ClaimedItem
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.CustomShop
import com.shusheng.cobblemarket.currency.CustomShopClaimState
import com.shusheng.cobblemarket.currency.CustomShopCooldownState
import com.shusheng.cobblemarket.currency.CustomShopMode
import com.shusheng.cobblemarket.currency.CustomShopType
import com.shusheng.cobblemarket.currency.DEFAULT_CLAIM_HOURS
import com.shusheng.cobblemarket.currency.MAX_CLAIM_HOURS
import com.shusheng.cobblemarket.currency.MAX_COOLDOWN_HOURS
import com.shusheng.cobblemarket.currency.ShopEditLock
import com.shusheng.cobblemarket.currency.PointMarketClaimResult
import com.shusheng.cobblemarket.currency.MAIN_SHOP_ID
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.PointMarketBuyResult
import com.shusheng.cobblemarket.currency.PointMarketError
import com.shusheng.cobblemarket.currency.PointMarketService
import com.shusheng.cobblemarket.currency.PointShopSnapshotState
import com.shusheng.cobblemarket.currency.PointShopState
import com.shusheng.cobblemarket.currency.ShopEntry
import com.shusheng.cobblemarket.currency.ShopItemSpec
import com.shusheng.cobblemarket.currency.ShopPokemonSpec
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import com.shusheng.cobblemarket.util.ItemNameText
import com.shusheng.cobblemarket.util.PersistHelper
import com.shusheng.cobblemarket.util.PlayerNameText
import com.shusheng.cobblemarket.util.RemainingTime
import com.shusheng.cobblemarket.util.RequestThrottle
import com.shusheng.cobblemarket.util.SpeciesText
import com.shusheng.cobblemarket.util.TypeTextColors
import net.minecraft.nbt.NbtCompound
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.util.Identifier

/**
 * **RP 商城（商品区）的网络层** —— 快照下发 + OP 的上架 / 编辑 / 下架。
 *
 * 三个「市场」别搞混：
 * - [PointMarketNetwork]（本文件）= **RP 商城**：服主上架商品，玩家用 RP 买
 * - [PointsNetwork] = RP 充值 / 兑换码 / 余额（「买 RP」那条线）
 * - [MarketNetwork] = 主市场（玩家之间挂单买卖）
 *
 * 铁律：
 * - **新 C2S 一律写在本文件的 [register] 里**，并且 [register] 必须被 `CobbleMarket.init` 调用 ——
 *   插到别的函数里会**编译通过但永不注册**，一发包就断连（项目踩过）
 * - 规格只在**创建**时来自客户端，且服务端要 [PointMarketService] 全量校验后再入库
 */

// ── 协议层长度上限（防伪造客户端塞超长串；也防服务端自己写下超长数据）──

/** 资源 id（物种 / 物品 / 形态 / 技能）的字符上限 */
private const val MAX_ID_LENGTH = 128

/** 短文本（性格 / 特性 / 球 / 性别 / 太晶属性 / 昵称）的字符上限 */
private const val MAX_TEXT_LENGTH = 64

/** SNBT 串（携带物 / 物品组件）的字符上限 */
private const val MAX_SPEC_LENGTH = 4096

/** 列表长度上限（形态 / 证章） */
private const val MAX_LIST_LENGTH = 32

/** 六项个体值 / 努力值恒为 6 项 */
private const val STAT_COUNT = 6

/** 一页的格子数（与 [PointShopState.POINTS_SHOP_PAGE_SIZE] 同值，但这是**协议**上限、不许跟着数据层改） */
private const val MAX_SLOTS_PER_PAGE = 32

/** 搜索时客户端最多带多少个「名称命中」的候选 id（防伪造客户端塞超长列表） */
private const val MAX_SEARCH_IDS = 512

/** 一格商品（快照里的一项）：格子序号 + 内容 */
data class PointMarketSlotInfo(val index: Int, val entry: ShopEntry)

/**
 * **打开一个自定义（NPC）商店**（S2C）。
 *
 * 由服务端在「右键绑定过的 NPC」或 `/market shop open <id>` 时发；客户端收到后
 * `setScreen(PointsMarketScreen(shopId, shopName))`。
 *
 * ⚠ [shopName] 由服务端带过来（而不是让客户端再查一份）：名字是服主填的、存在服务端存档里，
 *   客户端没有这份数据。界面标题直接用它，快照到达前也能显示正确的店名。
 */
data class OpenCustomShopPayload(
    val shopId: String,
    val shopName: String,
    /** [CustomShopType.id]：客户端据此决定显示双 tab 还是单一类型的网格 */
    val shopType: String = CustomShopType.BOTH.id,
    /** 从管理界面打开的：商品界面的「返回」回管理界面，而不是回游戏 */
    val returnToAdmin: Boolean = false,
    /** [MarketCurrency] 名：客户端据此显示价格 / 货币单位（整店一个币种） */
    val shopCurrency: String = MarketCurrency.POINTS.name,
    /** [CustomShopMode] id：`normal` = 普通购买；`claim` = 定期领取 */
    val shopMode: String = CustomShopMode.NORMAL.id,
    /** 领取周期（小时）—— 只有 `claim` 店用得到 */
    val claimHours: Int = DEFAULT_CLAIM_HOURS,
    /** 整店购买冷却（小时，0 = 不开）—— 只有 `normal` 店用得到 */
    val cooldownHours: Int = 0,
    /** 商品各自冷却（小时，0 = 不开）—— 只有 `normal` 店用得到 */
    val itemCooldownHours: Int = 0
) : CustomPayload {
    override fun getId() = ID

    companion object {
        /** 名字与 id 的上限同 [CustomShopState.MAX_NAME_LENGTH]（协议层再拦一道） */
        const val MAX_SHOP_TEXT = 64

        val ID = CustomPayload.Id<OpenCustomShopPayload>(CobbleMarket.id("open_custom_shop"))
        val CODEC: PacketCodec<PacketByteBuf, OpenCustomShopPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.shopId, MAX_SHOP_TEXT)
                b.writeString(p.shopName, MAX_SHOP_TEXT)
                // ⚠ 追加在**末尾**（payload 顺序铁律）
                b.writeString(p.shopType, MAX_SHOP_TEXT)
                b.writeBoolean(p.returnToAdmin)
                b.writeString(p.shopCurrency, MAX_SHOP_TEXT)
                b.writeString(p.shopMode, MAX_SHOP_TEXT)
                b.writeInt(p.claimHours)
                // ⚠ 追加在**末尾**（payload 顺序铁律）
                b.writeInt(p.cooldownHours)
                b.writeInt(p.itemCooldownHours)
            },
            // ⚠ 读端用具名参数：字段顺序一旦对不上立刻看出来（payload 顺序铁律）
            { b ->
                OpenCustomShopPayload(
                    shopId = b.readString(MAX_SHOP_TEXT),
                    shopName = b.readString(MAX_SHOP_TEXT),
                    shopType = b.readString(MAX_SHOP_TEXT),
                    returnToAdmin = b.readBoolean(),
                    shopCurrency = b.readString(MAX_SHOP_TEXT),
                    shopMode = b.readString(MAX_SHOP_TEXT),
                    claimHours = b.readInt(),
                    cooldownHours = b.readInt(),
                    itemCooldownHours = b.readInt()
                )
            }
        )
    }
}

/**
 * **定期领取店的玩家周期状态**（S2C，2026-10-23）。
 *
 * 给界面显示「每格还能领几个」和「距下次刷新多久」；服务端在打开商店、每次领取后下发。
 * ⚠ 全量（每个有配额的格一项）—— 最多 [MAX_CLAIM_STATUS_ENTRIES] 项，客户端伪造不了（这是 S2C）。
 */
data class CustomShopClaimStatusPayload(
    val shopId: String,
    /** 下次刷新时间（epoch ms）；0 = 还没有进行中的周期（现在就能领） */
    val nextResetAt: Long,
    /** entryId → 剩余可领数量 */
    val remaining: Map<String, Int>
) : CustomPayload {
    override fun getId() = ID

    companion object {
        const val MAX_CLAIM_STATUS_ENTRIES = 256
        val ID = CustomPayload.Id<CustomShopClaimStatusPayload>(CobbleMarket.id("custom_shop_claim_status"))
        val CODEC: PacketCodec<PacketByteBuf, CustomShopClaimStatusPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.shopId, OpenCustomShopPayload.MAX_SHOP_TEXT)
                b.writeLong(p.nextResetAt)
                b.writeInt(p.remaining.size)
                p.remaining.forEach { (entryId, count) ->
                    b.writeString(entryId, OPEN_CLAIM_MAX_ENTRY_ID)
                    b.writeInt(count)
                }
            },
            { b ->
                val shopId = b.readString(OpenCustomShopPayload.MAX_SHOP_TEXT)
                val nextResetAt = b.readLong()
                val size = b.readInt()
                if (size < 0 || size > MAX_CLAIM_STATUS_ENTRIES) {
                    throw IllegalArgumentException("claim status size out of range: $size")
                }
                val remaining = LinkedHashMap<String, Int>(size)
                repeat(size) {
                    remaining[b.readString(OPEN_CLAIM_MAX_ENTRY_ID)] = b.readInt()
                }
                CustomShopClaimStatusPayload(shopId, nextResetAt, remaining)
            }
        )
    }
}

/**
 * **普通购买店的每玩家冷却状态**（S2C，2026-10-25）。
 *
 * 服务端在「打开普通自定义商店」与「每次购买成功后」下发；客户端据此显示剩余冷却、
 * 置灰购买按钮。⚠ 只带**正在生效**的计时（过期的不发）；格子自己的冷却配置值
 * 走 [ShopEntry.cooldownHours] + [OpenCustomShopPayload.cooldownHours]，不在这里重复。
 */
data class CustomShopCooldownStatusPayload(
    val shopId: String,
    /** 整店冷却结束时刻（epoch ms）；0 = 不在冷却 */
    val shopUntil: Long,
    /** entryId → 该格冷却结束时刻（只含独立格子冷却、且正在冷却的格） */
    val slots: Map<String, Long>
) : CustomPayload {
    override fun getId() = ID

    companion object {
        const val MAX_COOLDOWN_STATUS_ENTRIES = 256
        val ID = CustomPayload.Id<CustomShopCooldownStatusPayload>(CobbleMarket.id("custom_shop_cooldown_status"))
        val CODEC: PacketCodec<PacketByteBuf, CustomShopCooldownStatusPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.shopId, OpenCustomShopPayload.MAX_SHOP_TEXT)
                b.writeLong(p.shopUntil)
                b.writeInt(p.slots.size)
                p.slots.forEach { (entryId, until) ->
                    b.writeString(entryId, OPEN_CLAIM_MAX_ENTRY_ID)
                    b.writeLong(until)
                }
            },
            { b ->
                val shopId = b.readString(OpenCustomShopPayload.MAX_SHOP_TEXT)
                val shopUntil = b.readLong()
                val size = b.readInt()
                if (size < 0 || size > MAX_COOLDOWN_STATUS_ENTRIES) {
                    throw IllegalArgumentException("cooldown status size out of range: $size")
                }
                val slots = LinkedHashMap<String, Long>(size)
                repeat(size) {
                    slots[b.readString(OPEN_CLAIM_MAX_ENTRY_ID)] = b.readLong()
                }
                CustomShopCooldownStatusPayload(shopId, shopUntil, slots)
            }
        )
    }
}

/** entryId 是 UUID 字符串（36 字符），给点余量 */
private const val OPEN_CLAIM_MAX_ENTRY_ID = 64

/** 一键领取请求（C2S）：只带店 id，其余全由服务端判定 */
data class ClaimCustomShopPayload(val shopId: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<ClaimCustomShopPayload>(CobbleMarket.id("claim_custom_shop"))
        val CODEC: PacketCodec<PacketByteBuf, ClaimCustomShopPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.shopId, OpenCustomShopPayload.MAX_SHOP_TEXT) },
            { b -> ClaimCustomShopPayload(b.readString(OpenCustomShopPayload.MAX_SHOP_TEXT)) }
        )
    }
}

/**
 * **一键领取的交付回执**（S2C，2026-10-23）：只含**物品**格 —— 客户端拿它给「飞入物品栏」动画
 * 决定飞哪几格、每格带几个 ×N。
 *
 * ⚠ 只发**真的进了背包**的格（部分交付时未交付的不在里面）；精灵不进这个包（不做动画）。
 * ⚠ 客户端只对有**可见起点**（当前页 / 当前 tab）的格子播动画，其余条目直接忽略。
 */
data class ClaimCustomShopResultPayload(
    val shopId: String,
    val claimed: List<ClaimedItem>
) : CustomPayload {
    override fun getId() = ID

    companion object {
        /**
         * 条目数上限。一屏最多 32 格，动画只可能用到可见的那几格；这里给到 256 只是兜底，
         * 防止异常店铺把包写爆。服务端写包前会先截断，两端上下限必须一致（读超限会抛异常）。
         */
        const val MAX_CLAIMED_ENTRIES = 256

        val ID = CustomPayload.Id<ClaimCustomShopResultPayload>(CobbleMarket.id("claim_custom_shop_result"))
        val CODEC: PacketCodec<PacketByteBuf, ClaimCustomShopResultPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.shopId, OpenCustomShopPayload.MAX_SHOP_TEXT)
                val list = p.claimed.take(MAX_CLAIMED_ENTRIES)
                b.writeInt(list.size)
                list.forEach {
                    b.writeString(it.entryId, OPEN_CLAIM_MAX_ENTRY_ID)
                    b.writeInt(it.count)
                }
            },
            { b ->
                val shopId = b.readString(OpenCustomShopPayload.MAX_SHOP_TEXT)
                val size = b.readInt()
                if (size < 0 || size > MAX_CLAIMED_ENTRIES) {
                    throw IllegalArgumentException("claim result size out of range: $size")
                }
                val claimed = ArrayList<ClaimedItem>(size)
                repeat(size) {
                    claimed += ClaimedItem(b.readString(OPEN_CLAIM_MAX_ENTRY_ID), b.readInt())
                }
                ClaimCustomShopResultPayload(shopId, claimed)
            }
        )
    }
}

/**
 * **商城快照**（S2C，按页下发）。
 *
 * 为什么按页而不是全量：一格的精灵规格有二十来个字段，全量发在页数多时会撑爆包；
 * 而客户端翻页本来就知道自己在第几页（翻页纯客户端行为）。
 */
data class PointMarketItemsPayload(
    val page: Int,
    val totalPages: Int,
    /** 这份快照属于哪个 tab（[ShopEntry.KIND_POKEMON] / [ShopEntry.KIND_ITEM]） */
    val kind: String,
    val slots: List<PointMarketSlotInfo>,
    /**
     * 这份快照属于哪个商店（[MAIN_SHOP_ID] = 主商城，其余 = 自定义商店 id）。
     *
     * ⚠ 客户端可能同时收到两个商店的快照（旧请求的迟到包）⇒ 界面必须比对它，
     *   否则会把 A 店的货画到 B 店的格子里（格子序号恰好对得上，看着像"商店串了"）。
     */
    val shopId: String = MAIN_SHOP_ID
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PointMarketItemsPayload>(CobbleMarket.id("point_market_items"))
        val CODEC: PacketCodec<PacketByteBuf, PointMarketItemsPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.page)
                b.writeInt(p.totalPages)
                b.writeString(p.kind, MAX_TEXT_LENGTH)
                b.writeInt(p.slots.size)
                p.slots.forEach { slot ->
                    b.writeInt(slot.index)
                    writeEntry(b, slot.entry)
                }
                // ⚠ 追加在**末尾**（payload 顺序铁律）
                b.writeString(p.shopId, MAX_TEXT_LENGTH)
            },
            { b ->
                val page = b.readInt()
                val totalPages = b.readInt()
                val kind = b.readString(MAX_TEXT_LENGTH)
                val count = b.readInt()
                if (count < 0 || count > MAX_SLOTS_PER_PAGE) {
                    throw IllegalArgumentException("point market slot count out of range: $count")
                }
                PointMarketItemsPayload(
                    page = page,
                    totalPages = totalPages,
                    kind = kind,
                    slots = List(count) { PointMarketSlotInfo(b.readInt(), readEntry(b)) },
                    shopId = b.readString(MAX_TEXT_LENGTH)
                )
            }
        )
    }
}

// ── 自定义购买店：商品搜索（2026-10-26）──
//
// 商店最多 64 页，客户端一次只加载一页 ⇒ 搜索必须由**服务端扫全店**。
// ⚠ 服务端不知道客户端语言的名称（服务端语言 ≠ 客户端语言）⇒ 由**客户端**把搜到的
// 名称解析成候选的物种 / 物品 id 一起发上来；服务端再按 id（以及原始查询串的子串）匹配。

/** C2S：在当前自定义购买店里搜索（跨全部页）。[matchedIds] = 客户端本地名称命中的 id 列表。 */
data class SearchPointMarketPayload(
    val shopId: String,
    val kind: String,
    val query: String,
    val matchedIds: List<String>,
    val page: Int = 0
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<SearchPointMarketPayload>(CobbleMarket.id("search_point_market"))
        val CODEC: PacketCodec<PacketByteBuf, SearchPointMarketPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.shopId, MAX_TEXT_LENGTH)
                b.writeString(p.kind, MAX_TEXT_LENGTH)
                b.writeString(p.query.take(MAX_TEXT_LENGTH), MAX_TEXT_LENGTH)
                val ids = p.matchedIds.take(MAX_SEARCH_IDS)
                b.writeVarInt(ids.size)
                ids.forEach { b.writeString(it.take(MAX_TEXT_LENGTH), MAX_TEXT_LENGTH) }
                b.writeInt(p.page)
            },
            { b ->
                val shopId = b.readString(MAX_TEXT_LENGTH)
                val kind = b.readString(MAX_TEXT_LENGTH)
                val query = b.readString(MAX_TEXT_LENGTH)
                val count = b.readVarInt()
                if (count < 0 || count > MAX_SEARCH_IDS) {
                    throw IllegalArgumentException("search id count out of range: $count")
                }
                SearchPointMarketPayload(
                    shopId = shopId,
                    kind = kind,
                    query = query,
                    matchedIds = List(count) { b.readString(MAX_TEXT_LENGTH) },
                    page = b.readInt()
                )
            }
        )
    }
}

/** S2C：搜索某一页的结果（按全店匹配总数分页，页大小 = 普通商品页的 32）。 */
data class PointMarketSearchPayload(
    val shopId: String,
    val kind: String,
    val query: String,
    val page: Int,
    val totalPages: Int,
    val total: Int,
    val slots: List<PointMarketSlotInfo>
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PointMarketSearchPayload>(CobbleMarket.id("point_market_search_result"))
        val CODEC: PacketCodec<PacketByteBuf, PointMarketSearchPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.shopId, MAX_TEXT_LENGTH)
                b.writeString(p.kind, MAX_TEXT_LENGTH)
                b.writeString(p.query.take(MAX_TEXT_LENGTH), MAX_TEXT_LENGTH)
                b.writeInt(p.page)
                b.writeInt(p.totalPages)
                b.writeInt(p.total)
                b.writeInt(p.slots.size)
                p.slots.forEach { slot ->
                    b.writeInt(slot.index)
                    writeEntry(b, slot.entry)
                }
            },
            { b ->
                val shopId = b.readString(MAX_TEXT_LENGTH)
                val kind = b.readString(MAX_TEXT_LENGTH)
                val query = b.readString(MAX_TEXT_LENGTH)
                val page = b.readInt()
                val totalPages = b.readInt()
                val total = b.readInt()
                val count = b.readInt()
                if (count < 0 || count > MAX_SLOTS_PER_PAGE) {
                    throw IllegalArgumentException("search slot count out of range: $count")
                }
                PointMarketSearchPayload(
                    shopId, kind, query, page, totalPages, total,
                    List(count) { PointMarketSlotInfo(b.readInt(), readEntry(b)) }
                )
            }
        )
    }
}

/**
 * 请求某一页某个 tab 的商城快照（进界面 / 翻页 / 切 tab 时发）。
 *
 * [shopId] 为空 / 非法时服务端回空快照；[MAIN_SHOP_ID] = 主商城。
 */
data class RequestPointMarketPayload(
    val page: Int,
    val kind: String,
    val shopId: String = MAIN_SHOP_ID
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestPointMarketPayload>(CobbleMarket.id("request_point_market"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPointMarketPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.page)
                b.writeString(p.kind, MAX_TEXT_LENGTH)
                b.writeString(p.shopId, MAX_TEXT_LENGTH)
            },
            // ⚠ 读端用具名参数：字段顺序一旦对不上立刻看出来（payload 顺序铁律）
            { b ->
                RequestPointMarketPayload(
                    page = b.readInt(),
                    kind = b.readString(MAX_TEXT_LENGTH),
                    shopId = b.readString(MAX_TEXT_LENGTH)
                )
            }
        )
    }
}

/** 持 `points.shop` 的人上架 / 编辑一格（服务端全量校验后再入库，见 [PointMarketService.save]） */
data class SavePointMarketEntryPayload(
    val index: Int,
    val entry: ShopEntry,
    val shopId: String = MAIN_SHOP_ID
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<SavePointMarketEntryPayload>(CobbleMarket.id("save_point_market_entry"))
        val CODEC: PacketCodec<PacketByteBuf, SavePointMarketEntryPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.index)
                writeEntry(b, p.entry)
                // ⚠ 追加在**末尾**（payload 顺序铁律）
                b.writeString(p.shopId, MAX_TEXT_LENGTH)
            },
            { b ->
                // ⚠ 读端用具名参数：字段顺序一旦对不上，具名能立刻看出来（payload 顺序铁律）
                SavePointMarketEntryPayload(
                    index = b.readInt(),
                    entry = readEntry(b),
                    shopId = b.readString(MAX_TEXT_LENGTH)
                )
            }
        )
    }
}

/** 补货：只改这一格的**库存**（`-1` = 不限量），别的什么都不动 */
data class UpdatePointMarketStockPayload(
    val index: Int,
    val kind: String,
    val stock: Int,
    val shopId: String = MAIN_SHOP_ID
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<UpdatePointMarketStockPayload>(CobbleMarket.id("update_point_market_stock"))
        // ⚠ 读写**顺序必须一一对应**（payload 顺序铁律）；读端用具名参数，错位时一眼看得出来
        val CODEC: PacketCodec<PacketByteBuf, UpdatePointMarketStockPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.index)
                b.writeString(p.kind, MAX_TEXT_LENGTH)
                b.writeInt(p.stock)
                // ⚠ 追加在**末尾**（payload 顺序铁律）
                b.writeString(p.shopId, MAX_TEXT_LENGTH)
            },
            { b ->
                UpdatePointMarketStockPayload(
                    index = b.readInt(),
                    kind = b.readString(MAX_TEXT_LENGTH),
                    stock = b.readInt(),
                    shopId = b.readString(MAX_TEXT_LENGTH)
                )
            }
        )
    }
}

/** 下架一格（清空该格）。⚠ 要带 [kind] —— 同一序号在精灵 / 物品两个 tab 下是两个格子 */
data class RemovePointMarketEntryPayload(
    val index: Int,
    val kind: String,
    val shopId: String = MAIN_SHOP_ID
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RemovePointMarketEntryPayload>(CobbleMarket.id("remove_point_market_entry"))
        val CODEC: PacketCodec<PacketByteBuf, RemovePointMarketEntryPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.index)
                b.writeString(p.kind, MAX_TEXT_LENGTH)
                // ⚠ 追加在**末尾**（payload 顺序铁律）
                b.writeString(p.shopId, MAX_TEXT_LENGTH)
            },
            { b ->
                RemovePointMarketEntryPayload(
                    index = b.readInt(),
                    kind = b.readString(MAX_TEXT_LENGTH),
                    shopId = b.readString(MAX_TEXT_LENGTH)
                )
            }
        )
    }
}

/**
 * **玩家用 RP 买一格商品**（商城唯一"花钱"的包）。
 *
 * ⚠ [count] **只对物品格有意义** —— 精灵格买一次就是一只，服务端不读这个值
 *（见 [PointMarketService.buy]），数量和金额都以服务端为准，客户端说了不算。
 */
data class BuyPointMarketEntryPayload(
    val index: Int,
    val count: Int,
    val kind: String,
    /**
     * 玩家**看到的那一条**的 id（打开购买窗时从快照记下）。
     * 服务端比对：对不上说明这格在玩家看它之后**换过内容** —— **拒绝成交、分文不扣**。
     */
    val entryId: String,
    /** 哪个商店（[MAIN_SHOP_ID] = 主商城）；服务端按它找存储并判自定义商店的启用 / 距离 */
    val shopId: String = MAIN_SHOP_ID
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<BuyPointMarketEntryPayload>(CobbleMarket.id("buy_point_market_entry"))
        val CODEC: PacketCodec<PacketByteBuf, BuyPointMarketEntryPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.index)
                b.writeInt(p.count)
                b.writeString(p.kind, MAX_TEXT_LENGTH)
                b.writeString(p.entryId, MAX_TEXT_LENGTH)
                // ⚠ 追加在**末尾**（payload 顺序铁律）
                b.writeString(p.shopId, MAX_TEXT_LENGTH)
            },
            // ⚠ 读端用具名参数：字段顺序一旦对不上，具名能立刻看出来（payload 顺序铁律）
            { b ->
                BuyPointMarketEntryPayload(
                    index = b.readInt(),
                    count = b.readInt(),
                    kind = b.readString(MAX_TEXT_LENGTH),
                    entryId = b.readString(MAX_TEXT_LENGTH),
                    shopId = b.readString(MAX_TEXT_LENGTH)
                )
            }
        )
    }
}

// ── 编辑期间暂停购买（2026-10-26）──
//
// 服主 / 持 `points.shop` 的人打开编辑窗 → 这家店进入「编辑中」：
//  · 服务端 [PointMarketService.buy] 直接拒绝（防伪造包）；
//  · 客户端把购买窗的「购买」按钮置灰、点击只响失败音；
//  · 关窗 / 断线后解除。按**商店**记，编辑窗开着时客户端定期发 editing=true 当心跳。

/** C2S：进入 / 离开商店编辑窗（也可当心跳，见 [ShopEditLock.begin]）。 */
data class SetShopEditingPayload(
    val shopId: String = MAIN_SHOP_ID,
    val editing: Boolean = true
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<SetShopEditingPayload>(CobbleMarket.id("set_shop_editing"))
        val CODEC: PacketCodec<PacketByteBuf, SetShopEditingPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.shopId, MAX_TEXT_LENGTH)
                b.writeBoolean(p.editing)
            },
            { b ->
                SetShopEditingPayload(
                    shopId = b.readString(MAX_TEXT_LENGTH),
                    editing = b.readBoolean()
                )
            }
        )
    }
}

/** S2C：这家店现在是否「正在被编辑」（客户端据此置灰购买按钮）。 */
data class ShopEditLockPayload(
    val shopId: String,
    val locked: Boolean
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<ShopEditLockPayload>(CobbleMarket.id("shop_edit_lock"))
        val CODEC: PacketCodec<PacketByteBuf, ShopEditLockPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.shopId, MAX_TEXT_LENGTH)
                b.writeBoolean(p.locked)
            },
            { b ->
                ShopEditLockPayload(
                    shopId = b.readString(MAX_TEXT_LENGTH),
                    locked = b.readBoolean()
                )
            }
        )
    }
}

// ── 配方快照（服主用：把配好的规格存下来复用）──
//
// 与"商品"是两回事：商品在格子里（有位置、有库存），快照是**配方库**（没有位置，随时取用）。
// 四个包：请求列表 / 下发列表 / 存一条 / 删一条。⚠ 全都判 `points.shop` 节点。

/** 一条快照：名字 + 整份规格 */
data class ShopSnapshotInfo(val name: String, val entry: ShopEntry)

/** 快照列表（S2C，只发给请求过的人） */
data class ShopSnapshotsPayload(val snapshots: List<ShopSnapshotInfo>) : CustomPayload {
    override fun getId() = ID

    companion object {
        /** 一包最多几条（与数据层的上限同值，防伪造客户端塞超长列表） */
        const val MAX_PER_PACKET = 64

        val ID = CustomPayload.Id<ShopSnapshotsPayload>(CobbleMarket.id("shop_snapshots"))
        val CODEC: PacketCodec<PacketByteBuf, ShopSnapshotsPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.snapshots.size)
                p.snapshots.forEach { s ->
                    b.writeString(s.name, MAX_TEXT_LENGTH)
                    writeEntry(b, s.entry)
                }
            },
            { b ->
                val n = b.readInt()
                if (n < 0 || n > MAX_PER_PACKET) {
                    throw IllegalArgumentException("shop snapshot count out of range: $n")
                }
                ShopSnapshotsPayload(
                    List(n) { ShopSnapshotInfo(b.readString(MAX_TEXT_LENGTH), readEntry(b)) }
                )
            }
        )
    }
}

/** 请求快照列表（进添加窗时发） */
data class RequestShopSnapshotsPayload(val unused: Boolean = false) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestShopSnapshotsPayload>(CobbleMarket.id("request_shop_snapshots"))
        val CODEC: PacketCodec<PacketByteBuf, RequestShopSnapshotsPayload> = PacketCodec.of(
            { _, _ -> },
            { RequestShopSnapshotsPayload() }
        )
    }
}

/** 存一条快照（**重名由服务端自动加序号**，不覆盖）；存完服务端回发最新列表 */
data class SaveShopSnapshotPayload(val name: String, val entry: ShopEntry) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<SaveShopSnapshotPayload>(CobbleMarket.id("save_shop_snapshot"))
        val CODEC: PacketCodec<PacketByteBuf, SaveShopSnapshotPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.name, MAX_TEXT_LENGTH)
                writeEntry(b, p.entry)
            },
            { b -> SaveShopSnapshotPayload(name = b.readString(MAX_TEXT_LENGTH), entry = readEntry(b)) }
        )
    }
}

/** 删一条快照 */
data class DeleteShopSnapshotPayload(val name: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<DeleteShopSnapshotPayload>(CobbleMarket.id("delete_shop_snapshot"))
        val CODEC: PacketCodec<PacketByteBuf, DeleteShopSnapshotPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.name, MAX_TEXT_LENGTH) },
            { b -> DeleteShopSnapshotPayload(b.readString(MAX_TEXT_LENGTH)) }
        )
    }
}

// ── 形态候选表（服主动态拉；见 [SpeciesText.allFeatureAspects]）──
//
// 为什么要有这对包：RP 商城编辑窗的「形态」列表要同时列 **forms（客户端本地就有）** 与
// **species feature 候选（客户端拿不到）** —— choice 型 provider 的 `visible = false`，
// 被 Cobblemon 的同步包过滤掉了，客户端翻不出来。所以候选由服务端算好、按需下发。

/** 请求形态候选表（打开添加窗时发；⚠ 判 `points.shop` 节点 —— 只有服主用得上这份数据） */
data class RequestSpeciesFeatureAspectsPayload(val unused: Boolean = false) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestSpeciesFeatureAspectsPayload>(
            CobbleMarket.id("request_species_feature_aspects")
        )
        val CODEC: PacketCodec<PacketByteBuf, RequestSpeciesFeatureAspectsPayload> = PacketCodec.of(
            { _, _ -> },
            { RequestSpeciesFeatureAspectsPayload() }
        )
    }
}

/**
 * 全部物种的变体候选（`物种资源 id → 各个 feature 维度`）。**只含真有 choice feature 的物种**，
 * 所以表本身不大（绝大多数物种没有这个键，客户端查不到就只有形态按钮那一份）。
 *
 * ⚠ **必须带分组**（[FeatureCandidates.key]）：客户端要"点 mega_y 只换掉 mega_x、不碰花纹"
 *   就得知道哪些 aspect 同属一个维度；扁平列表做不到。`default` 也要带 —— 界面靠它决定
 *   「不指定」那一项叫「随机」还是「默认」。
 */
data class SpeciesFeatureAspectsPayload(
    val bySpecies: Map<String, List<com.shusheng.cobblemarket.util.FeatureCandidates>>
) : CustomPayload {
    override fun getId() = ID

    companion object {
        /** 物种数上限（防伪造客户端塞超长表；实际已实现物种约一千） */
        const val MAX_SPECIES = 4096

        /** 单个物种的**维度数**上限（晃晃斑最多，5 个；真超了说明数据包异常） */
        const val MAX_GROUPS_PER_SPECIES = 32

        /** 单个维度的候选数上限（花纹 22、帽子 8 …… 正常几十） */
        const val MAX_ASPECTS_PER_GROUP = 128

        val ID = CustomPayload.Id<SpeciesFeatureAspectsPayload>(
            CobbleMarket.id("species_feature_aspects")
        )
        val CODEC: PacketCodec<PacketByteBuf, SpeciesFeatureAspectsPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.bySpecies.size)
                p.bySpecies.forEach { (species, groups) ->
                    b.writeString(species, MAX_ID_LENGTH)
                    b.writeInt(groups.size)
                    groups.forEach { g ->
                        b.writeString(g.key, MAX_ID_LENGTH)
                        b.writeString(g.default, MAX_TEXT_LENGTH)
                        b.writeInt(g.aspects.size)
                        g.aspects.forEach { b.writeString(it, MAX_ID_LENGTH) }
                    }
                }
            },
            // ⚠ 读端用具名参数：字段顺序一旦对不上立刻看出来（payload 顺序铁律）
            { b ->
                val speciesCount = b.readInt()
                if (speciesCount < 0 || speciesCount > MAX_SPECIES) {
                    throw IllegalArgumentException("species feature table size out of range: $speciesCount")
                }
                val map = LinkedHashMap<String, List<com.shusheng.cobblemarket.util.FeatureCandidates>>(speciesCount)
                repeat(speciesCount) {
                    val species = b.readString(MAX_ID_LENGTH)
                    val groupCount = b.readInt()
                    if (groupCount < 0 || groupCount > MAX_GROUPS_PER_SPECIES) {
                        throw IllegalArgumentException("species feature group count out of range: $groupCount")
                    }
                    map[species] = List(groupCount) {
                        val key = b.readString(MAX_ID_LENGTH)
                        val default = b.readString(MAX_TEXT_LENGTH)
                        val aspectCount = b.readInt()
                        if (aspectCount < 0 || aspectCount > MAX_ASPECTS_PER_GROUP) {
                            throw IllegalArgumentException("species feature aspect count out of range: $aspectCount")
                        }
                        com.shusheng.cobblemarket.util.FeatureCandidates(
                            key = key,
                            default = default,
                            aspects = List(aspectCount) { b.readString(MAX_ID_LENGTH) }
                        )
                    }
                }
                SpeciesFeatureAspectsPayload(bySpecies = map)
            }
        )
    }
}

// ── 完整招式表（服主动态拉；见 [SpeciesText.learnsetBuckets] / [SpeciesText.MoveSources]）──
//
// 为什么要有这对包：Cobblemon 把物种同步给客户端时，**招式表只传三桶**
// （升级 / 招式机 / 遗传）—— `tutor` / `form_change` / `evolution` **一列都不传**
// （`Learnset.encode/decode` 就这么写的），而服务端生成商品时用的 `getAllLegalMoves()`
// 是**含**这些的 ⇒ 专用服务器上，商城编辑窗的技能候选会少掉教学招（治愈铃声 / 伏特攻击 /
// 流星群那些），服主只能靠「自由选择」开关去补 —— 那条路做出来的是贴着「非法」的精灵。
// ⇒ 完整表由服务端算好、按需下发（**按物种**，不是整表 —— 整表一千多个物种太大）。

/** 请求某个物种的完整招式表（编辑窗定下物种 / 打开技能列表时发） */
data class RequestSpeciesMovesPayload(val speciesId: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestSpeciesMovesPayload>(
            CobbleMarket.id("request_species_moves")
        )
        val CODEC: PacketCodec<PacketByteBuf, RequestSpeciesMovesPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.speciesId, MAX_ID_LENGTH) },
            { b -> RequestSpeciesMovesPayload(speciesId = b.readString(MAX_ID_LENGTH)) }
        )
    }
}

/**
 * **一个物种的完整招式表，按来源分成六桶**（`桶 → 招式 id`，见 [SpeciesText.MoveSources]）。
 *
 * - [base] = 标准形态那份（**形态自己没写招式表的都回落它**，与 `FormData.moves` 的回落一致）
 * - [forms] = **自带招式表**的那些形态：`form.aspects` 拼成的串 → 六桶
 *   （洛托姆的五个家电各有一份自己的表 —— 「过热」只在加热形态那张里）
 *
 * ⚠ 客户端拿它做两件事：**技能候选**（六桶并集 = 服务端认可的合法招）与**按来源筛选**。
 */
data class SpeciesMovesPayload(
    val speciesId: String,
    val base: Map<String, List<String>>,
    val forms: Map<String, Map<String, List<String>>>
) : CustomPayload {
    override fun getId() = ID

    companion object {
        /** 桶数上限（实际就 [SpeciesText.MoveSources.ORDER] 那六个） */
        const val MAX_BUCKETS = 8

        /** 单桶招式数上限（实测最多的表 342 条，留一倍余量） */
        const val MAX_MOVES_PER_BUCKET = 512

        /** 自带招式表的形态数上限（洛托姆 5 个；真超了说明数据包异常） */
        const val MAX_FORMS = 32

        val ID = CustomPayload.Id<SpeciesMovesPayload>(
            CobbleMarket.id("species_moves")
        )
        val CODEC: PacketCodec<PacketByteBuf, SpeciesMovesPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.speciesId, MAX_ID_LENGTH)
                b.writeMoveBuckets(p.base)
                b.writeInt(p.forms.size)
                p.forms.forEach { (key, buckets) ->
                    b.writeString(key, MAX_ID_LENGTH)
                    b.writeMoveBuckets(buckets)
                }
            },
            { b ->
                val speciesId = b.readString(MAX_ID_LENGTH)
                val base = b.readMoveBuckets()
                val formCount = b.readInt()
                if (formCount < 0 || formCount > MAX_FORMS) {
                    throw IllegalArgumentException("species move form count out of range: $formCount")
                }
                val forms = LinkedHashMap<String, Map<String, List<String>>>(formCount)
                repeat(formCount) {
                    forms[b.readString(MAX_ID_LENGTH)] = b.readMoveBuckets()
                }
                SpeciesMovesPayload(speciesId = speciesId, base = base, forms = forms)
            }
        )

        /**
         * **服务端**：某个物种的完整招式表。
         *
         * ⚠ 只发**真有自己那张表**的形态 —— `FormData.moves` 在形态没写表时回落物种级
         *   （引用就是同一个 `Learnset`），发一份一模一样的没有意义（120 个形态 vs 1025 个物种）。
         *   ⚠ 判据用**引用相等**（`===`）：那正是 `FormData.moves` 的回落写法，
         *     换成"内容相等"的话，一张与物种级恰好同内容的表会被漏掉、那份就成了 `base`（等价，无害但白算）。
         */
        fun of(species: Species): SpeciesMovesPayload = SpeciesMovesPayload(
            speciesId = species.resourceIdentifier.toString(),
            base = SpeciesText.learnsetBuckets(species.moves),
            forms = species.forms.mapNotNull { form ->
                val aspects = form.aspects
                if (aspects.isEmpty() || form.moves === species.moves) null
                else aspects.joinToString(",") to SpeciesText.learnsetBuckets(form.moves)
            }.toMap()
        )
    }
}

private fun PacketByteBuf.writeMoveBuckets(buckets: Map<String, List<String>>) {
    writeInt(buckets.size)
    buckets.forEach { (key, ids) ->
        writeString(key, MAX_TEXT_LENGTH)
        writeInt(ids.size)
        ids.forEach { writeString(it, MAX_ID_LENGTH) }
    }
}

private fun PacketByteBuf.readMoveBuckets(): Map<String, List<String>> {
    val count = readInt()
    if (count < 0 || count > SpeciesMovesPayload.MAX_BUCKETS) {
        throw IllegalArgumentException("move bucket count out of range: $count")
    }
    val out = LinkedHashMap<String, List<String>>(count)
    repeat(count) {
        val key = readString(MAX_TEXT_LENGTH)
        val moveCount = readInt()
        if (moveCount < 0 || moveCount > SpeciesMovesPayload.MAX_MOVES_PER_BUCKET) {
            throw IllegalArgumentException("move bucket size out of range: $moveCount")
        }
        out[key] = List(moveCount) { readString(MAX_ID_LENGTH) }
    }
    return out
}

// ── 商品规格的读写（顶层函数：快照与保存请求共用一份，两份各写一遍必然漂移）──

private fun writeEntry(b: PacketByteBuf, e: ShopEntry) {
    b.writeString(e.kind, MAX_TEXT_LENGTH)
    b.writeLong(e.price)
    b.writeInt(e.stock)
    // 两种规格各带一个存在位：种别与规格是否匹配由服务端校验，传输层只管原样搬运
    val pokemon = e.pokemon
    b.writeBoolean(pokemon != null)
    pokemon?.let { writePokemonSpec(b, it) }
    val item = e.item
    b.writeBoolean(item != null)
    item?.let { writeItemSpec(b, it) }
    // 条目 id：客户端打开购买窗时记下、点购买时带回来，服务端比对（见 ShopEntry.entryId）。
    // ⚠ 加在**末尾**、读写顺序保持一致（payload 顺序铁律）
    b.writeString(e.entryId, MAX_TEXT_LENGTH)
    // 格子冷却（小时；0 = 跟随商店）—— 同样加在**末尾**
    b.writeInt(e.cooldownHours)
}

private fun readEntry(b: PacketByteBuf) = ShopEntry(
    // ⚠ 读端一律用具名参数：字段顺序一旦对不上，具名能立刻看出来（payload 顺序铁律）
    kind = b.readString(MAX_TEXT_LENGTH),
    price = b.readLong(),
    stock = b.readInt(),
    pokemon = if (b.readBoolean()) readPokemonSpec(b) else null,
    item = if (b.readBoolean()) readItemSpec(b) else null,
    entryId = b.readString(MAX_TEXT_LENGTH),
    cooldownHours = b.readInt()
)

private fun writePokemonSpec(b: PacketByteBuf, s: ShopPokemonSpec) {
    b.writeString(s.species, MAX_ID_LENGTH)
    writeIdList(b, s.aspects)
    b.writeFloat(s.shinyChance)
    b.writeInt(s.level ?: -1)
    b.writeString(s.gender ?: "", MAX_TEXT_LENGTH)
    b.writeString(s.nature ?: "", MAX_TEXT_LENGTH)
    b.writeString(s.ability ?: "", MAX_TEXT_LENGTH)
    b.writeString(s.ball ?: "", MAX_TEXT_LENGTH)
    b.writeBoolean(s.scale != null)
    s.scale?.let { b.writeFloat(it) }
    b.writeBoolean(s.alpha)
    b.writeString(s.teraType ?: "", MAX_TEXT_LENGTH)
    b.writeBoolean(s.gmaxFactor)
    b.writeInt(s.dmaxLevel)
    b.writeBoolean(s.heldItemVisible)
    b.writeBoolean(s.tradeable)
    writeOptionalStatList(b, s.ivs)
    b.writeInt(s.minPerfectIvs)
    writeOptionalStatList(b, s.evs)
    b.writeInt(s.friendship ?: -1)
    b.writeString(s.nickname ?: "", MAX_TEXT_LENGTH)
    b.writeString(s.heldItemSpec ?: "", MAX_SPEC_LENGTH)
    writeIdList(b, s.marks)
    b.writeString(s.moves, MAX_SPEC_LENGTH)
    // 服主勾的「非法」—— ⚠ 加在**末尾**（payload 顺序铁律），两端同版才读得对
    b.writeBoolean(s.illegal)
    // 初始训练家（配信假训练家）—— ⚠ 同样加在**末尾**（payload 顺序铁律），两端同版才读得对
    b.writeString(s.originalTrainer ?: "", MAX_TEXT_LENGTH)
}

private fun readPokemonSpec(b: PacketByteBuf) = ShopPokemonSpec(
    species = b.readString(MAX_ID_LENGTH),
    aspects = readIdList(b),
    shinyChance = b.readFloat().coerceIn(0f, 1f),
    level = b.readInt().takeIf { it >= 0 },
    gender = b.readString(MAX_TEXT_LENGTH).ifEmpty { null },
    nature = b.readString(MAX_TEXT_LENGTH).ifEmpty { null },
    ability = b.readString(MAX_TEXT_LENGTH).ifEmpty { null },
    ball = b.readString(MAX_TEXT_LENGTH).ifEmpty { null },
    scale = if (b.readBoolean()) b.readFloat() else null,
    alpha = b.readBoolean(),
    teraType = b.readString(MAX_TEXT_LENGTH).ifEmpty { null },
    gmaxFactor = b.readBoolean(),
    dmaxLevel = b.readInt(),
    heldItemVisible = b.readBoolean(),
    tradeable = b.readBoolean(),
    ivs = readOptionalStatList(b),
    minPerfectIvs = b.readInt(),
    evs = readOptionalStatList(b),
    friendship = b.readInt().takeIf { it >= 0 },
    nickname = b.readString(MAX_TEXT_LENGTH).ifEmpty { null },
    heldItemSpec = b.readString(MAX_SPEC_LENGTH).ifEmpty { null },
    marks = readIdList(b),
    moves = b.readString(MAX_SPEC_LENGTH),
    illegal = b.readBoolean(),
    originalTrainer = b.readString(MAX_TEXT_LENGTH).ifEmpty { null }
)

private fun writeItemSpec(b: PacketByteBuf, s: ShopItemSpec) {
    b.writeString(s.itemId, MAX_ID_LENGTH)
    b.writeBoolean(s.componentsSpec != null)
    s.componentsSpec?.let { b.writeNbt(it) }
}

private fun readItemSpec(b: PacketByteBuf) = ShopItemSpec(
    itemId = b.readString(MAX_ID_LENGTH),
    componentsSpec = if (b.readBoolean()) b.readNbt() else null
)

private fun writeIdList(b: PacketByteBuf, list: List<String>) {
    b.writeInt(list.size)
    list.forEach { b.writeString(it, MAX_ID_LENGTH) }
}

private fun readIdList(b: PacketByteBuf): List<String> {
    val count = b.readInt()
    // 伪造客户端可能声明一个超大数量：读串越界本来就会抛异常断连，这里先显式拦一层更好定位
    if (count < 0 || count > MAX_LIST_LENGTH) {
        throw IllegalArgumentException("point market id list length out of range: $count")
    }
    return List(count) { b.readString(MAX_ID_LENGTH) }
}

/** 个体值 / 努力值：null 与「六项」用 -1 长度区分，不用额外的存在位 */
private fun writeOptionalStatList(b: PacketByteBuf, list: List<Int>?) {
    if (list == null) {
        b.writeInt(-1)
        return
    }
    b.writeInt(list.size)
    list.forEach { b.writeInt(it) }
}

private fun readOptionalStatList(b: PacketByteBuf): List<Int>? {
    val count = b.readInt()
    if (count < 0) return null
    if (count > STAT_COUNT) {
        throw IllegalArgumentException("point market stat list length out of range: $count")
    }
    return List(count) { b.readInt() }
}

/**
 * RP 商城的错误码 → 提示文本。**网络层与将来的命令侧共用这一份**
 *（两处各写一遍必然漂移，同 [pointShopErrorText] 的道理）。
 */
fun pointMarketErrorText(error: PointMarketError, currency: MarketCurrency = MarketCurrency.POINTS): Text = Text.translatable(
    when (error) {
        PointMarketError.NO_PERMISSION -> "cobblemarket.points.store.err.no_permission"
        PointMarketError.BAD_INDEX -> "cobblemarket.points.store.err.bad_index"
        PointMarketError.NOT_FOUND -> "cobblemarket.points.store.err.not_found"
        PointMarketError.BAD_KIND -> "cobblemarket.points.store.err.bad_kind"
        PointMarketError.BAD_SPEC -> "cobblemarket.points.store.err.bad_spec"
        PointMarketError.BAD_SPECIES -> "cobblemarket.points.store.err.bad_species"
        PointMarketError.BAD_MOVE -> "cobblemarket.points.store.err.bad_move"
        PointMarketError.BAD_ITEM -> "cobblemarket.points.store.err.bad_item"
        PointMarketError.BAD_NUMBER -> "cobblemarket.points.store.err.bad_number"
        PointMarketError.BAD_EV_TOTAL -> "cobblemarket.points.store.err.bad_ev_total"
        PointMarketError.BAD_COUNT -> "cobblemarket.points.store.err.bad_count"
        PointMarketError.SOLD_OUT -> "cobblemarket.points.store.err.sold_out"
        PointMarketError.SHOP_BEING_EDITED -> "cobblemarket.points.store.err.shop_being_edited"
        PointMarketError.STOCK_NOT_ENOUGH -> "cobblemarket.points.store.err.stock_not_enough"
        PointMarketError.NOT_ENOUGH_FUNDS -> "cobblemarket.points.store.err.not_enough_points"
        PointMarketError.NO_SPACE -> "cobblemarket.points.store.err.no_space"
        PointMarketError.GENERATE_FAILED -> "cobblemarket.points.store.err.generate_failed"
        PointMarketError.STALE_ENTRY -> "cobblemarket.points.store.err.stale_entry"
        PointMarketError.SHOP_NOT_FOUND -> "cobblemarket.points.store.err.shop_not_found"
        PointMarketError.SHOP_DISABLED -> "cobblemarket.points.store.err.shop_disabled"
        PointMarketError.TOO_FAR -> "cobblemarket.points.store.err.too_far"
        PointMarketError.KIND_NOT_ALLOWED -> "cobblemarket.points.store.err.kind_not_allowed"
        // 与市场其它 RP 操作同一条提示（词条自带 %s 货币图标）
        PointMarketError.POINTS_DISABLED -> "cobblemarket.network.points_disabled"
        PointMarketError.MAIN_SHOP_DISABLED -> "cobblemarket.points.store.err.main_shop_disabled"
        PointMarketError.NOT_FOR_SALE -> "cobblemarket.points.store.err.not_for_sale"
        PointMarketError.NOT_A_CLAIM_SHOP -> "cobblemarket.points.store.err.not_a_claim_shop"
        // 冷却的详细提示（带剩余时间）在购买回执处单独拼；这里只是兜底
        PointMarketError.ON_COOLDOWN -> "cobblemarket.points.store.err.on_cooldown_simple"
        PointMarketError.ONE_PER_PURCHASE -> "cobblemarket.points.store.err.one_per_purchase"
    },
    // 统一传**这家店的货币单位**：含 %s 的词条（如「%s 余额不足」）用它，不含的多余参数会被忽略。
    // RP 是图标、主货币是 ₽ / 物品名 —— 主货币店的余额不足不能再说「RP 不足」。
    CurrencyService.unitText(currency)
)

object PointMarketNetwork {

    /**
     * 玩家最近一次**通过哪个 NPC** 打开的自定义商店（只给「买到闪光」播报取前缀）。
     *
     * ⚠ **不持久化**：重连 / 服务器重启后为空 ⇒ 播报退回店名，绝不会用错人。
     * 玩家断线时由 [onPlayerDisconnect] 清掉，长期运行也不会按人数无限增长。
     */
    private data class OpenedFromNpc(val shopId: String, val npcUuid: UUID)
    /** ⚠ 并发表：写入在主线程，[onPlayerDisconnect] 在**网络线程**清（同 [RequestThrottle] 的做法） */
    private val openedFromNpc = ConcurrentHashMap<UUID, OpenedFromNpc>()

    fun onPlayerDisconnect(player: ServerPlayerEntity) {
        openedFromNpc.remove(player.uuid)
        // 编辑锁：断线立刻释放（不等 15 分钟超时），给正在看这些店的玩家广播解锁。
        // ⚠ 这个回调在**网络线程**：广播必须回到主线程做。
        val released = ShopEditLock.endAll(player.uuid)
        if (released.isNotEmpty()) {
            val server = player.server
            server.execute { released.forEach { broadcastEditLock(server, it, false) } }
        }
    }

    /**
     * 每个玩家**最后请求过的 (页, tab, 商店)**。
     *
     * 广播快照时按**各自在看的那一份**发 —— 统一发"操作者那一页"会把别人跳到他没在看的一页去，
     * tab 不同更是会串（精灵 tab 收到物品快照），商店不同会**直接串货**（A 店的商品画到 B 店格子里）。
     * 判据是「请求过」：服务端**不知道谁的界面开着**（自定义 Screen 不上报给服务端），
     * 但请求过就说明他的界面在用这份数据；一次没请求过的玩家一个包都不发。
     */
    private data class LastRequest(val page: Int, val kind: String, val shopId: String)

    private val lastRequested = mutableMapOf<java.util.UUID, LastRequest>()

    fun register() {
        registerS2CType(PointMarketItemsPayload.ID, PointMarketItemsPayload.CODEC)
        // 自定义购买店：搜索结果分页
        registerS2CType(PointMarketSearchPayload.ID, PointMarketSearchPayload.CODEC)
        // 「编辑中」锁：编辑窗开 / 关时广播给正在看这家店的玩家（购买按钮置灰）
        registerS2CType(ShopEditLockPayload.ID, ShopEditLockPayload.CODEC)
        // 打开自定义商店：右键绑定 NPC / `/market shop open` 由服务端发
        registerS2CType(OpenCustomShopPayload.ID, OpenCustomShopPayload.CODEC)
        // 定期领取店的周期状态（打开 / 每次领取后下发）
        registerS2CType(CustomShopClaimStatusPayload.ID, CustomShopClaimStatusPayload.CODEC)
        // 普通购买店的每玩家冷却状态（打开 / 每次购买成功后下发）
        registerS2CType(CustomShopCooldownStatusPayload.ID, CustomShopCooldownStatusPayload.CODEC)
        // 一键领取的交付回执（只有物品；客户端的飞入动画据此决定飞哪几格）
        registerS2CType(ClaimCustomShopResultPayload.ID, ClaimCustomShopResultPayload.CODEC)
        // ⚠ S2C 是**两步**注册：类型在这里注册（两端各一次），客户端 `registerS2C` 只挂 handler。
        //   漏了这一行 → 客户端启动即崩：fabric 的客户端 registerS2C **忽略 codec 参数**，
        //   类型只能靠这里；而 neoforge 那边自带 playToClient，漏了也不显形（踩坑记录见 CLAUDE.md）
        registerS2CType(ShopSnapshotsPayload.ID, ShopSnapshotsPayload.CODEC)
        registerS2CType(SpeciesFeatureAspectsPayload.ID, SpeciesFeatureAspectsPayload.CODEC)
        registerS2CType(SpeciesMovesPayload.ID, SpeciesMovesPayload.CODEC)

        // 请求某一页：进界面与翻页都走它（节流与其余列表请求同档）
        registerC2S(RequestPointMarketPayload.ID, RequestPointMarketPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "point_market_list", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            // 记下他在看哪一页、哪个 tab、哪个商店 —— 之后别人买 / 上架 / 下架时按这个给他推新快照
            lastRequested[player.uuid] = LastRequest(
                page = PointMarketService.clampPage(payload.page),
                kind = payload.kind,
                shopId = payload.shopId
            )
            server.execute {
                sendToPlayer(player, buildSnapshot(server, payload.page, payload.kind, payload.shopId))
                // 顺带告知这家店是否正在被编辑：购买窗的「购买」按钮据此置灰
                sendToPlayer(player, ShopEditLockPayload(payload.shopId, ShopEditLock.isLocked(payload.shopId)))
            }
        }

        // 自定义购买店搜索：客户端把名称解析成候选 id 一起发上来，服务端扫整家店并按页返回
        registerC2S(SearchPointMarketPayload.ID, SearchPointMarketPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "point_market_search", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                val shop = PointMarketService.customShop(server, payload.shopId) ?: return@execute
                // 搜索只给自定义**普通购买店**（领取店 / 主商城没有这个入口）
                if (shop.mode != CustomShopMode.NORMAL) return@execute
                val kind = if (payload.kind == ShopEntry.KIND_ITEM) ShopEntry.KIND_ITEM else ShopEntry.KIND_POKEMON
                val query = payload.query.trim()
                val pageSize = PointShopState.POINTS_SHOP_PAGE_SIZE
                if (query.isEmpty() || !shop.type.allows(kind)) {
                    sendToPlayer(
                        player,
                        PointMarketSearchPayload(payload.shopId, kind, query, 0, 1, 0, emptyList())
                    )
                    return@execute
                }
                val ids = payload.matchedIds.take(MAX_SEARCH_IDS).mapTo(HashSet()) { it.lowercase() }
                val lowerQuery = query.lowercase()
                val matches = PointMarketService.store(server, payload.shopId)?.all(kind).orEmpty()
                    .toSortedMap().entries.mapNotNull { (index, entry) ->
                        val id = if (kind == ShopEntry.KIND_POKEMON) entry.pokemon?.species.orEmpty()
                        else entry.item?.itemId.orEmpty()
                        val lower = id.lowercase()
                        if (lower.isNotEmpty() && (lower in ids || lower.contains(lowerQuery))) {
                            PointMarketSlotInfo(index, entry)
                        } else null
                    }
                val total = matches.size
                val totalPages = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
                val page = payload.page.coerceIn(0, totalPages - 1)
                sendToPlayer(
                    player,
                    PointMarketSearchPayload(
                        payload.shopId, kind, query, page, totalPages, total,
                        matches.drop(page * pageSize).take(pageSize)
                    )
                )
            }
        }

        // 编辑窗开 / 关（也可当心跳）：按商店记「编辑中」，广播给正在看这家店的玩家
        registerC2S(SetShopEditingPayload.ID, SetShopEditingPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return@registerC2S
            val server = player.server
            server.execute {
                val changed = if (payload.editing) {
                    ShopEditLock.begin(payload.shopId, player.uuid)
                } else {
                    ShopEditLock.end(payload.shopId, player.uuid)
                }
                // 只在「可买 ↔ 编辑中」切换时广播：心跳 / 多编辑者不会反复打扰
                if (changed) broadcastEditLock(server, payload.shopId, ShopEditLock.isLocked(payload.shopId))
            }
        }

        // 上架 / 编辑：服务端全量校验后才入库，成功后**回发该格所在页**让 OP 的界面立刻刷新
        registerC2S(SavePointMarketEntryPayload.ID, SavePointMarketEntryPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "point_market_save", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                val error = PointMarketService.save(server, player, payload.index, payload.entry, payload.shopId)
                if (error == null) {
                    player.sendMessage(
                        Text.translatable("cobblemarket.points.store.saved", payload.index + 1)
                            .formatted(Formatting.GREEN),
                        false
                    )
                    // **广播**而不是只回发给自己：别的玩家可能也开着商城，上架 / 改价他们得跟着变
                    broadcastSnapshot(server)
                    // 结果音效同别处：成功 / 失败各一声。⚠ 这两个包**不发 MarketResultPayload**
                    //   （回执是聊天栏文字 + 快照），所以那条统一播音效的路走不到这儿 ——
                    //   不补的话服主点保存只有按钮的 click 声，成没成全靠看聊天栏（用户 2026-09-20 报）
                    sendSound(player, "result_success")
                } else {
                    player.sendMessage(pointMarketErrorText(error).copy().formatted(Formatting.RED), false)
                    sendSound(player, "result_fail")
                }
            }
        }

        // 补货：只改库存（⚠ 服务端刻意**不动 entryId** —— 补货不是"换内容"，见 PointMarketService.updateStock）
        registerC2S(UpdatePointMarketStockPayload.ID, UpdatePointMarketStockPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "point_market_stock", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                val error = PointMarketService.updateStock(
                    server, player, payload.kind, payload.index, payload.stock, payload.shopId
                )
                if (error == null) {
                    player.sendMessage(
                        Text.translatable("cobblemarket.points.store.stock_updated", payload.index + 1)
                            .formatted(Formatting.GREEN),
                        false
                    )
                    // 广播：别的玩家（以及服主自己）正开着的界面都要跟着刷新库存
                    broadcastSnapshot(server)
                    sendSound(player, "result_success")
                } else {
                    player.sendMessage(pointMarketErrorText(error).copy().formatted(Formatting.RED), false)
                    sendSound(player, "result_fail")
                }
            }
        }

        registerC2S(RemovePointMarketEntryPayload.ID, RemovePointMarketEntryPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "point_market_remove", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                val error = PointMarketService.remove(server, player, payload.kind, payload.index, payload.shopId)
                if (error == null) {
                    player.sendMessage(
                        Text.translatable("cobblemarket.points.store.removed", payload.index + 1)
                            .formatted(Formatting.GREEN),
                        false
                    )
                    // 广播（同 save：下架也会让别人的界面过期）
                    broadcastSnapshot(server)
                    // 下架的结果音效（同上，这两个包不走 MarketResultPayload）
                    sendSound(player, "result_success")
                } else {
                    player.sendMessage(pointMarketErrorText(error).copy().formatted(Formatting.RED), false)
                    sendSound(player, "result_fail")
                }
            }
        }

        // 形态候选表：编辑窗的「形态」列表要列 species feature（花纹 / 帽子 / mega），
        // 而客户端拿不到（choice 型不同步，见 [SpeciesText.allFeatureAspects]）⇒ 服务端算好发过去。
        // ⚠ 判 `points.shop`：这份数据只有能上架的人用得上，没必要发给普通玩家
        registerC2S(RequestSpeciesFeatureAspectsPayload.ID, RequestSpeciesFeatureAspectsPayload.CODEC) { _, player ->
            // ⚠ **三个界面都要这张表**（RP 商城 / 黑名单 / 价格限制）⇒ 闸门必须是"任一相关节点"：
            //   只授了黑名单或价格限制、没授 RP 商城的管理员，请求会被**静默丢掉** ——
            //   表现是变体列表**空白**（等于配不了变体规则）+ 悬停窗只剩裸值，且一声不响
            //   （2026-09-28 发现；表是只读数据，给了那几个节点本来就该看得到）
            val allowed = MarketPermissions.has(player, MarketPermissions.POINTS_SHOP) ||
                MarketPermissions.has(player, MarketPermissions.BLACKLIST) ||
                MarketPermissions.has(player, MarketPermissions.PRICE_LIMIT)
            if (!allowed) return@registerC2S
            if (!RequestThrottle.allow(player.uuid, "species_feature_aspects", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                sendToPlayer(player, SpeciesFeatureAspectsPayload(SpeciesText.allFeatureAspects()))
            }
        }

        // 完整招式表（**按物种**拉）：客户端那份只有三桶，教学 / 形态专属 / 进化招拿不到，
        // 而服务端生成时用的是全的 ⇒ 不发的话服主只能开「自由选择」补（= 贴着「非法」）
        registerC2S(RequestSpeciesMovesPayload.ID, RequestSpeciesMovesPayload.CODEC) { payload, player ->
            // ⚠ 闸门**有意比形态候选表窄**：那份三个界面共用（商城 / 黑名单 / 价格限制），
            //   这份只有 RP 商城的编辑窗用得上 ⇒ 只认 `points.shop`（与编辑窗本身同一个节点）
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return@registerC2S
            if (!RequestThrottle.allow(player.uuid, "species_moves", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerC2S
            }
            // 物种 id 非法 / 该物种没装（附属模组卸了）⇒ **静默不发**：客户端会一直用本地三桶兜底，
            // 技能列表照常能用（它本来就有那一份），只是少了教学 / 形态专属那几个筛选项的内容
            val species = Identifier.tryParse(payload.speciesId)
                ?.let { PokemonSpecies.getByIdentifier(it) } ?: return@registerC2S
            val server = player.server
            server.execute {
                sendToPlayer(player, SpeciesMovesPayload.of(species))
            }
        }

        // ── 配方快照：全判 `points.shop`（与上架同一档 —— 那是"发给玩家的东西"的把关权）──

        registerC2S(RequestShopSnapshotsPayload.ID, RequestShopSnapshotsPayload.CODEC) { _, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return@registerC2S
            if (!RequestThrottle.allow(player.uuid, "shop_snapshot_list", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute { sendToPlayer(player, buildSnapshots(server)) }
        }

        registerC2S(SaveShopSnapshotPayload.ID, SaveShopSnapshotPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return@registerC2S
            if (!RequestThrottle.allowNotify(player, "shop_snapshot_save", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                val name = payload.name.trim().take(PointShopSnapshotState.MAX_NAME_LENGTH)
                if (name.isEmpty()) return@execute
                // ⚠ 规格**原样存**（不做 normalize）：服主存的是"配方"，真正把关在**上架那一刻**
                //   （PointMarketService.save 会全量校验）—— 这里再校一遍只会让"存快照"变成一个有
                //   各种拒绝理由的操作。退回读档时的容错语义：坏数据读回来是 null、跳过并 warn
                // 重名**不覆盖**、自动加序号（服主的同一只精灵可能配好几套，见 PointShopSnapshotState.add）
                val savedName = PointShopSnapshotState.get(server).add(name, payload.entry)
                if (savedName == null) {
                    player.sendMessage(
                        Text.translatable(
                            "cobblemarket.points.store.snapshot_full", PointShopSnapshotState.MAX_SNAPSHOTS
                        ).formatted(Formatting.RED),
                        false
                    )
                    return@execute
                }
                if (savedName != name) {
                    // 撞名并存成了别的名字 —— **必须告诉他**，否则他在列表里找不到刚存的那条
                    player.sendMessage(
                        Text.translatable("cobblemarket.points.store.snapshot_renamed", savedName)
                            .formatted(Formatting.YELLOW),
                        false
                    )
                }
                PersistHelper.requestSave(server)
                // 回发最新列表（界面不用自己猜存到哪了）
                sendToPlayer(player, buildSnapshots(server))
            }
        }

        registerC2S(DeleteShopSnapshotPayload.ID, DeleteShopSnapshotPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return@registerC2S
            if (!RequestThrottle.allowNotify(player, "shop_snapshot_delete", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                PointShopSnapshotState.get(server).remove(payload.name)
                PersistHelper.requestSave(server)
                sendToPlayer(player, buildSnapshots(server))
            }
        }

        // 定期领取店：一键领取全部（免费；服务端判周期 / 距离 / 启用状态）
        registerC2S(ClaimCustomShopPayload.ID, ClaimCustomShopPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "custom_shop_claim", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                when (val result = PointMarketService.claimAll(server, player, payload.shopId)) {
                    is PointMarketClaimResult.Failure -> {
                        player.sendMessage(
                            pointMarketErrorText(result.error).copy().formatted(Formatting.RED), false
                        )
                        sendSound(player, "result_fail")
                    }

                    is PointMarketClaimResult.Success -> {
                        // 交付内容按**精灵 / 物品分开**报：领取店两类都能卖，笼统写「N 件物品」会让
                        // 「领到 1 只精灵」也显示成 Claimed 1 item(s)（2026-10-24 用户报）。
                        val itemCount = result.items.sumOf { it.count }
                        val pokemonCount = (result.delivered - itemCount).coerceAtLeast(0)
                        val goods: Text = when {
                            pokemonCount > 0 && itemCount > 0 -> Text.translatable(
                                "cobblemarket.points.store.claim_goods_mixed",
                                Text.translatable("cobblemarket.points.store.claim_goods_pokemon", pokemonCount),
                                Text.translatable("cobblemarket.points.store.claim_goods_items", itemCount)
                            )
                            pokemonCount > 0 ->
                                Text.translatable("cobblemarket.points.store.claim_goods_pokemon", pokemonCount)
                            else ->
                                Text.translatable("cobblemarket.points.store.claim_goods_items", itemCount)
                        }
                        val text = when {
                            // 有可领内容但一件都没放下 = 空间不足
                            result.delivered == 0 && result.hadRemaining ->
                                Text.translatable("cobblemarket.points.store.claim_no_space").formatted(Formatting.RED)
                            result.delivered == 0 ->
                                Text.translatable("cobblemarket.points.store.claim_nothing").formatted(Formatting.YELLOW)
                            // 领了一部分：提醒剩余还在店里（本期有效）
                            result.leftBehind -> Text.translatable(
                                "cobblemarket.points.store.claim_partial", goods
                            ).formatted(Formatting.GREEN)
                            else -> Text.translatable(
                                "cobblemarket.points.store.claim_success", goods
                            ).formatted(Formatting.GREEN)
                        }
                        player.sendMessage(text, false)
                        sendSound(player, if (result.delivered > 0) "result_success" else "result_fail")
                        // 把最新的剩余 / 刷新时间回给客户端
                        PointMarketService.customShop(server, payload.shopId)?.let {
                            sendToPlayer(player, buildClaimStatus(player, it))
                        }
                        // 交付明细（只用于飞入动画）：没有物品（只领到精灵 / 一件都没领到）就不发
                        if (result.items.isNotEmpty()) {
                            sendToPlayer(
                                player, ClaimCustomShopResultPayload(payload.shopId, result.items)
                            )
                        }
                        // 领取到**闪光精灵** ⇒ 全服播报（措辞是「领取到」，不是购买）
                        announceShinyClaim(server, player, payload.shopId, result.shinyPokemon)
                    }
                }
            }
        }

        // 购买：玩家侧唯一"花钱"的入口（**不判权限** —— 谁有 RP 谁能买）。
        // 库存 / 余额 / 收件空间全在服务端校验，客户端只是发起请求
        registerC2S(BuyPointMarketEntryPayload.ID, BuyPointMarketEntryPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "point_market_buy", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                when (
                    val result = PointMarketService.buy(
                        server, player, payload.kind, payload.index, payload.count, payload.entryId, payload.shopId
                    )
                ) {
                    is PointMarketBuyResult.Failure -> {
                        // 冷却拒绝要带**剩余时间**（通用错误词条只有一句"冷却中"，帮不上忙）
                        val message = if (result.error == PointMarketError.ON_COOLDOWN) {
                            Text.translatable(
                                "cobblemarket.points.store.err.on_cooldown",
                                RemainingTime.text(
                                    System.currentTimeMillis() + result.remainingMs,
                                    "cobblemarket.points.store.cooldown_ready",
                                    localizedUnits = true
                                )
                            ).formatted(Formatting.RED)
                        } else {
                            pointMarketErrorText(
                                result.error, PointMarketService.currencyOf(server, payload.shopId)
                            ).copy().formatted(Formatting.RED)
                        }
                        player.sendMessage(message, false)
                        sendSound(player, "result_fail")
                        // 失败也回发**他自己**那一页：库存以服务端为准 —— 别人刚买走最后一件时，
                        // 这边还停在旧数字上，不刷新就会一直「看着有货、点下去却说售罄」
                        sendToPlayer(
                            player,
                            buildSnapshot(server, pageOf(payload.index), payload.kind, payload.shopId)
                        )
                    }

                    is PointMarketBuyResult.Success -> {
                        player.sendMessage(
                            boughtText(server, payload.kind, payload.index, result, payload.shopId), false
                        )
                        sendSound(player, "result_success")
                        sendPurchaseCelebration(server, player, payload.kind, payload.index, result, payload.shopId)
                        // 买到**闪光精灵** ⇒ 全服播报（只购买；领取店领到的不播）
                        announceShinyPurchase(server, player, payload.kind, payload.shopId, result)
                        // 买走一件会改库存 —— **广播**给所有在看商城的人（服主那边那格也要跟着减）
                        broadcastSnapshot(server)
                        // 冷却已开始：把最新的剩余时间回给买家（界面据此置灰 / 倒计时）
                        PointMarketService.customShop(server, payload.shopId)?.let { shop ->
                            if (shop.mode == CustomShopMode.NORMAL) {
                                sendToPlayer(player, buildCooldownStatus(player, shop))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 购买成功的回执：**买的是什么 × 几份、花了多少（按这家店的币种）**。
     *
     * ⚠ 数量与金额都取 [PointMarketBuyResult.Success] 里的**实际成交值**、不重算 ——
     * 精灵格恒 1 份、金额以服务端真正扣掉的为准，回执不能报出与实际不符的数字。
     * ⚠ 金额走 [CurrencyService.amountText] —— RP 的单位是**字体图标**，
     * 用字符串模板一拼就变成方块（项目踩过）。
     */
    private fun boughtText(
        server: MinecraftServer,
        kind: String,
        index: Int,
        result: PointMarketBuyResult.Success,
        shopId: String
    ): Text {
        // 买完再读一次即可：库存变了，但 kind 与规格不会因为一次购买而变
        val entry = PointMarketService.store(server, shopId)?.find(kind, index) ?: return Text.empty()
        return Text.translatable(
            "cobblemarket.points.store.bought",
            goodsName(entry),
            result.count,
            CurrencyService.amountText(
                result.total, PointMarketService.currencyOf(server, shopId)
            )
        ).formatted(Formatting.GREEN)
    }

    /**
     * 购买成功的**庆祝动画** —— 与主市场 / 拍卖 / 求购同一套（所有权首次转到该玩家名下时播一次）。
     *
     * ⚠ 用**实际造出来的实物**而不是商品规格：概率闪光只有掷完才知道闪没闪，
     * 拿规格去猜会把"买到闪光"报成不闪。
     * ⚠ 动画起点由**客户端购买窗**在关窗前登记（同主市场那套）；没有登记就从顶部空降。
     */
    private fun sendPurchaseCelebration(
        server: MinecraftServer,
        player: ServerPlayerEntity,
        kind: String,
        index: Int,
        result: PointMarketBuyResult.Success,
        shopId: String
    ) {
        result.pokemon?.let { pokemon ->
            CelebrationNetwork.send(
                player,
                speciesId = pokemon.species.resourceIdentifier.toString(),
                aspects = pokemon.aspects.toList(),
                shiny = pokemon.shiny,
                source = CelebrationSource.STORE,
                // 携带物只在带组件时才非空（与挂单 / 拍卖 / 求购同口径）
                heldItemNbt = PointMarketService.store(server, shopId)
                    ?.find(kind, index)?.pokemon?.heldItemSpec.orEmpty(),
                // 「模型手里拿不拿」：取**实际造出来这只**的可见性（它由 PointMarketFactory 从商品规格拷来），
                // 与购买窗右侧那个图标同源 —— 漏了这行动画里的精灵会又把手里的东西显示出来（用户 2026-09-26 报）
                heldItemVisible = pokemon.heldItemVisible
            )
            return
        }
        // ⚠ 空栈要**显式挡掉**（下面两条 warn 是「写操作不再静默」的**修法本体，不是临时日志，别删**）：
        //   `ItemStack.encode` 对空栈会抛 `IllegalStateException (Cannot encode empty ItemStack)`，
        //   而这里抛出去会被 `server.execute` 吞掉 ——
        //   表现就是"购买成功、聊天有提示、**就是没动画**"（2026-09-26 服务端日志抓到的就是这个）。
        val stack = result.items.firstOrNull()?.takeIf { !it.isEmpty } ?: run {
            CobbleMarket.LOGGER.warn("[庆祝] RP商城买物品：没有可用的实物栈，不发包")
            return
        }
        // ⚠ 同理：encode 本身也可能抛，一律兜住（这里只影响动画，绝不该连累购买流程）
        val nbt = runCatching { stack.encode(player.serverWorld.registryManager) as? NbtCompound }
            .getOrNull() ?: run {
            CobbleMarket.LOGGER.warn("[庆祝] RP商城买物品：encode 失败，不发包")
            return
        }
        CelebrationNetwork.sendItem(player, nbt, result.count, CelebrationSource.STORE)
    }

    /**
     * **买到闪光精灵的全服播报**（2026-10-24 用户要求）。
     *
     * - 触发：**购买**（不含领取店领取）且**实际造出来的精灵**是闪光 —— 不按 5% 的概率字段判，
     *   概率摇完才知道闪没闪（与 [sendPurchaseCelebration] 同一口径）
     * - 前缀：自定义 NPC 商店 = 玩家**右键进店的那个 NPC** 的名字（没有自定义名 / 不是从 NPC 进的 ⇒ 店名）；
     *   主 RP 商城 = 固定词条 `[RP商城]`（主商城同样有闪光概率设置，两边共用一个编辑窗）
     * - 精灵名可悬停：复用分享那套大图快照（[ShareNetwork.hoverablePokemonName]）⇒ 大图 + 完整悬停窗
     */
    private fun announceShinyPurchase(
        server: MinecraftServer,
        player: ServerPlayerEntity,
        kind: String,
        shopId: String,
        result: PointMarketBuyResult.Success
    ) {
        if (kind != ShopEntry.KIND_POKEMON) return
        val pokemon = result.pokemon ?: return
        if (!pokemon.shiny) return

        val shop = PointMarketService.customShop(server, shopId)
        val prefix = if (shop == null) {
            // customShop 对未知 / 已删除的店也返回 null ⇒ 只认主商城
            if (shopId != MAIN_SHOP_ID) return
            mainShopTag()
        } else {
            customShopTag(player, shop)
        }
        broadcastShiny(server, player, prefix, "cobblemarket.chat.shiny_purchase", listOf(pokemon))
    }

    /**
     * **领取到闪光精灵**的全服播报（2026-10-24 用户要求）。
     *
     * 领取店没有购买流程；`claimAll` 会把**实际交付的闪光精灵**放进 [PointMarketClaimResult.Success.shinyPokemon]。
     * 文案用「领取到」而不是「购买到」，前缀仍是这家店的 NPC 名字（跟购买播报同一套）。
     */
    private fun announceShinyClaim(
        server: MinecraftServer,
        player: ServerPlayerEntity,
        shopId: String,
        shinyPokemon: List<Pokemon>
    ) {
        if (shinyPokemon.isEmpty()) return
        val shop = PointMarketService.customShop(server, shopId) ?: return
        broadcastShiny(server, player, customShopTag(player, shop), "cobblemarket.chat.shiny_claim", shinyPokemon)
    }

    /** 自定义商店的播报前缀：右键进店的那个 NPC 的名字（没有自定义名 / 从命令进店 ⇒ 店名） */
    private fun customShopTag(player: ServerPlayerEntity, shop: CustomShop): Text {
        val openedNpc = openedFromNpc[player.uuid]?.takeIf { it.shopId == shop.id }?.npcUuid
        return Text.translatable(
            "cobblemarket.chat.npc_shop_tag",
            Text.literal(PointMarketService.merchantName(player, shop, openedNpc))
        ).formatted(Formatting.GOLD)
    }

    /**
     * 主商城前缀：[Ⓡ 商城] / [Ⓡ Shop]。
     * ⚠ 游戏内**不出现「RP」字样**：用与商城标题同一个写法（`pointsIcon()` 字体图标字形）。
     */
    private fun mainShopTag(): Text = Text.translatable(
        "cobblemarket.chat.rp_shop_tag",
        Text.translatable("cobblemarket.points.market.title", CurrencyService.pointsIcon())
    ).formatted(Formatting.GOLD)

    /** 全服播报一段「拿到闪光」消息；多只闪光各播一条（每条都带自己的悬停快照） */
    private fun broadcastShiny(
        server: MinecraftServer,
        player: ServerPlayerEntity,
        prefix: Text,
        bodyKey: String,
        pokemonList: List<Pokemon>
    ) {
        val playerName = PlayerNameText.of(player.name.string)
        for (pokemon in pokemonList) {
            val message = prefix.copy()
                .append(Text.literal(" "))
                .append(
                    Text.translatable(
                        bodyKey, playerName, ShareNetwork.hoverablePokemonName(player, pokemon)
                    ).formatted(Formatting.BLUE)
                )
            server.playerManager.playerList.forEach { it.sendMessage(message) }
        }
    }

    /** 商品名（带色）：精灵照**主属性**色 + 闪光星标、物品照**稀有度**色 —— 与聊天消息、悬停窗同一套口径 */
    private fun goodsName(entry: ShopEntry): Text = when (entry.kind) {
        ShopEntry.KIND_POKEMON -> {
            val spec = entry.pokemon
            val species = spec?.species?.let { Identifier.tryParse(it) }?.let { PokemonSpecies.getByIdentifier(it) }
            if (species == null) {
                Text.literal(spec?.species.orEmpty())
            } else {
                // 主属性取**默认形态**的：商品可能带形态（如地区形态属性会变），
                // 但为此在回执里重跑一遍形态推导不值当，颜色只是陪衬
                val typeKey = species.standardForm.types.firstOrNull()?.name?.lowercase()
                TypeTextColors.speciesName(
                    SpeciesText.translated(species),
                    typeKey?.let { "cobblemon.type.$it" },
                    "shiny" in (spec.aspects)
                )
            }
        }

        else -> ItemNameText.rarityColoredNameById(entry.item?.itemId.orEmpty())
            ?: Text.literal(entry.item?.itemId.orEmpty())
    }

    /**
     * 把最新的商城快照推给**所有正在看商城的玩家**（按各自的页）。
     *
     * 为什么需要：买走一件、上架、下架都会改库存或内容，而**别的玩家**可能正开着这个界面 ——
     * 只回发给操作者的话，他们那边会一直停在旧值。2026-09-25 用户实测报的就是这个：
     * 「玩家买走一只后，服主界面上那格的数量不变」「服主改完内容，玩家点开还是旧的」。
     *
     * ⚠ 服务端不知道谁的界面开着，所以判据是「他请求过商城快照」（见 [lastRequested]）——
     * 宁可少发（没来看过的不发），也别给全服每个人塞包。
     */
    /**
     * 「编辑中」状态变化：只发给**正在看这家店**的玩家（判据同 [broadcastSnapshot]：请求过快照）。
     *
     * 不发给全服 —— 没开界面的玩家不需要知道，也没地方显示。
     */
    private fun broadcastEditLock(server: MinecraftServer, shopId: String, locked: Boolean) {
        val online = server.playerManager.playerList
        val onlineIds = online.mapTo(HashSet()) { it.uuid }
        lastRequested.keys.retainAll(onlineIds)
        online.forEach { target ->
            val req = lastRequested[target.uuid] ?: return@forEach
            if (req.shopId != shopId) return@forEach
            sendToPlayer(target, ShopEditLockPayload(shopId, locked))
        }
    }

    private fun broadcastSnapshot(server: MinecraftServer) {
        val online = server.playerManager.playerList
        // 顺手清掉已离线的记录：长期开着的服务器上，这个 map 会随着来过的玩家越攒越大
        val onlineIds = online.mapTo(HashSet()) { it.uuid }
        lastRequested.keys.retainAll(onlineIds)
        online.forEach { target ->
            // 按**他自己**在看的那一页 + tab + 商店发（三者都可能和别人不同）
            val req = lastRequested[target.uuid] ?: return@forEach
            sendToPlayer(target, buildSnapshot(server, req.page, req.kind, req.shopId))
        }
    }

    /**
     * 让某个玩家打开一个自定义商店（右键绑定 NPC / `/market shop open` 共用这一处）。
     *
     * ⚠ 客户端收到后自己建界面并请求快照；服务端**不在下发里塞商品** ——
     * 与主商城同一条链路（[RequestPointMarketPayload]），少一套数据格式。
     */
    fun openCustomShop(
        player: ServerPlayerEntity,
        shop: CustomShop,
        returnToAdmin: Boolean = false,
        npcUuid: UUID? = null
    ) {
        // 记下「这次是从哪个 NPC 进的店」—— 买到闪光精灵时播报的前缀要用它的名字。
        // ⚠ 不能按"附近最近的绑定 NPC"猜：两家 NPC 挨得近时，玩家离另一家更近就会用错名字。
        // ⚠ 从命令 / 管理界面打开（没有 NPC）⇒ 清掉，前缀退回店名。
        if (npcUuid != null) openedFromNpc[player.uuid] = OpenedFromNpc(shop.id, npcUuid)
        else openedFromNpc.remove(player.uuid)
        sendToPlayer(
            player,
            OpenCustomShopPayload(
                shop.id, shop.name, shop.type.id, returnToAdmin, shop.currency.name,
                shop.mode.id, shop.claimHours, shop.cooldownHours, shop.itemCooldownHours
            )
        )
        // 领取店：把本玩家的周期状态一并下发（每格还能领几个 / 何时刷新）
        // 普通店：下发本玩家的冷却状态（整店 / 每格剩余），界面据此倒计时与置灰
        if (shop.mode == CustomShopMode.CLAIM) {
            sendToPlayer(player, buildClaimStatus(player, shop))
        } else {
            sendToPlayer(player, buildCooldownStatus(player, shop))
        }
    }

    /** 组装某个玩家在一家普通购买店的冷却状态（整店剩余 + 各格独立冷却剩余） */
    fun buildCooldownStatus(
        player: ServerPlayerEntity,
        shop: CustomShop
    ): CustomShopCooldownStatusPayload {
        val now = System.currentTimeMillis()
        val state = CustomShopCooldownState.get(player.server)
        val shopCooldownMs = shop.cooldownHours.coerceIn(0, MAX_COOLDOWN_HOURS) * 3_600_000L
        val shopUntil = state.shopUntil(shop.id, player.uuid, shopCooldownMs, now)
        val slots = LinkedHashMap<String, Long>()
        for (kind in listOf(ShopEntry.KIND_POKEMON, ShopEntry.KIND_ITEM)) {
            if (!shop.type.allows(kind)) continue
            shop.all(kind).values.forEach { entry ->
                // 协议上限：极端情况下（两家店/上千格同时冷却）只发前 256 条；
                // 没发到的格子仍由服务端正常拦截，只是界面不显示倒计时
                if (slots.size >= CustomShopCooldownStatusPayload.MAX_COOLDOWN_STATUS_ENTRIES) return@forEach
                // 该格各自冷却 = max(商品各自冷却, 单格覆盖)；两者都为 0 = 这格不锁
                val slotHours = maxOf(shop.itemCooldownHours, entry.cooldownHours)
                if (slotHours <= 0) return@forEach
                val slotMs = slotHours.coerceIn(0, MAX_COOLDOWN_HOURS) * 3_600_000L
                val until = state.slotUntil(shop.id, player.uuid, entry.entryId, slotMs, now)
                if (until > 0) slots[entry.entryId] = until
            }
        }
        return CustomShopCooldownStatusPayload(shop.id, shopUntil, slots)
    }

    /** 组装某个玩家在一家领取店的当前周期状态（每格剩余 + 下次刷新时间） */
    fun buildClaimStatus(player: ServerPlayerEntity, shop: CustomShop): CustomShopClaimStatusPayload {
        val now = System.currentTimeMillis()
        val periodMs = shop.claimHours.coerceIn(1, MAX_CLAIM_HOURS) * 3_600_000L
        val state = CustomShopClaimState.get(player.server)
        val period = state.periodFor(shop.id, player.uuid, now, periodMs)
        val remaining = LinkedHashMap<String, Int>()
        for (kind in listOf(ShopEntry.KIND_POKEMON, ShopEntry.KIND_ITEM)) {
            if (!shop.type.allows(kind)) continue
            shop.all(kind).values.forEach { entry ->
                if (entry.stock > 0) remaining[entry.entryId] = state.remaining(period, entry.entryId, entry.stock)
            }
        }
        return CustomShopClaimStatusPayload(
            shop.id, state.nextResetAt(shop.id, player.uuid, periodMs), remaining
        )
    }

    /** 组装配方快照列表（只发给请求过的人 / 刚操作过的人，不做广播 —— 它是服主的私人配方库） */
    private fun buildSnapshots(server: MinecraftServer): ShopSnapshotsPayload =
        ShopSnapshotsPayload(
            PointShopSnapshotState.get(server).all().map { ShopSnapshotInfo(it.name, it.entry) }
        )

    /** 组装某一页某个 tab 的快照（页号收进合法范围 —— 客户端翻页参数不可信） */
    private fun buildSnapshot(
        server: MinecraftServer,
        page: Int,
        kind: String,
        shopId: String
    ): PointMarketItemsPayload {
        val safePage = PointMarketService.clampPage(page)
        val safeKind = if (kind == ShopEntry.KIND_ITEM) ShopEntry.KIND_ITEM else ShopEntry.KIND_POKEMON
        // ⚠ 自定义商店不存在 / 被删 ⇒ 回一个**空快照**（而不是什么都不发）：客户端在等这一份，
        //   不发的话它会一直重试；空快照让界面显示"没有商品"，与真实的空商店一致
        return PointMarketItemsPayload(
            page = safePage,
            totalPages = PointMarketService.pageCount(server, safeKind, shopId),
            kind = safeKind,
            slots = PointMarketService.pageEntries(server, safePage, safeKind, shopId)
                .map { (index, entry) -> PointMarketSlotInfo(index, entry) },
            shopId = shopId
        )
    }

    /** 格子序号 → 所在页（回发快照用） */
    private fun pageOf(index: Int): Int = index / PointShopState.POINTS_SHOP_PAGE_SIZE
}
