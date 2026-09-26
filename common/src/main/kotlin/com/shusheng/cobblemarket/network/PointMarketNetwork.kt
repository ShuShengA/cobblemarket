package com.shusheng.cobblemarket.network

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.currency.CurrencyService
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

/** 一格商品（快照里的一项）：格子序号 + 内容 */
data class PointMarketSlotInfo(val index: Int, val entry: ShopEntry)

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
    val slots: List<PointMarketSlotInfo>
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
                    slots = List(count) { PointMarketSlotInfo(b.readInt(), readEntry(b)) }
                )
            }
        )
    }
}

/** 请求某一页某个 tab 的商城快照（进界面 / 翻页 / 切 tab 时发） */
data class RequestPointMarketPayload(val page: Int, val kind: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestPointMarketPayload>(CobbleMarket.id("request_point_market"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPointMarketPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.page)
                b.writeString(p.kind, MAX_TEXT_LENGTH)
            },
            // ⚠ 读端用具名参数：字段顺序一旦对不上立刻看出来（payload 顺序铁律）
            { b -> RequestPointMarketPayload(page = b.readInt(), kind = b.readString(MAX_TEXT_LENGTH)) }
        )
    }
}

/** OP 上架 / 编辑一格（**只有 OP 会被受理**，见 [PointMarketService.save]） */
data class SavePointMarketEntryPayload(val index: Int, val entry: ShopEntry) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<SavePointMarketEntryPayload>(CobbleMarket.id("save_point_market_entry"))
        val CODEC: PacketCodec<PacketByteBuf, SavePointMarketEntryPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.index)
                writeEntry(b, p.entry)
            },
            { b -> SavePointMarketEntryPayload(b.readInt(), readEntry(b)) }
        )
    }
}

/** OP 下架一格（清空该格）。⚠ 要带 [kind] —— 同一序号在精灵 / 物品两个 tab 下是两个格子 */
/** 补货：只改这一格的**库存**（`-1` = 不限量），别的什么都不动 */
data class UpdatePointMarketStockPayload(val index: Int, val kind: String, val stock: Int) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<UpdatePointMarketStockPayload>(CobbleMarket.id("update_point_market_stock"))
        // ⚠ 读写**顺序必须一一对应**（payload 顺序铁律）；读端用具名参数，错位时一眼看得出来
        val CODEC: PacketCodec<PacketByteBuf, UpdatePointMarketStockPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.index)
                b.writeString(p.kind, MAX_TEXT_LENGTH)
                b.writeInt(p.stock)
            },
            { b ->
                UpdatePointMarketStockPayload(
                    index = b.readInt(),
                    kind = b.readString(MAX_TEXT_LENGTH),
                    stock = b.readInt()
                )
            }
        )
    }
}

data class RemovePointMarketEntryPayload(val index: Int, val kind: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RemovePointMarketEntryPayload>(CobbleMarket.id("remove_point_market_entry"))
        val CODEC: PacketCodec<PacketByteBuf, RemovePointMarketEntryPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.index)
                b.writeString(p.kind, MAX_TEXT_LENGTH)
            },
            { b -> RemovePointMarketEntryPayload(index = b.readInt(), kind = b.readString(MAX_TEXT_LENGTH)) }
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
    val entryId: String
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
            },
            // ⚠ 读端用具名参数：字段顺序一旦对不上，具名能立刻看出来（payload 顺序铁律）
            { b ->
                BuyPointMarketEntryPayload(
                    index = b.readInt(),
                    count = b.readInt(),
                    kind = b.readString(MAX_TEXT_LENGTH),
                    entryId = b.readString(MAX_TEXT_LENGTH)
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
}

private fun readEntry(b: PacketByteBuf) = ShopEntry(
    // ⚠ 读端一律用具名参数：字段顺序一旦对不上，具名能立刻看出来（payload 顺序铁律）
    kind = b.readString(MAX_TEXT_LENGTH),
    price = b.readLong(),
    stock = b.readInt(),
    pokemon = if (b.readBoolean()) readPokemonSpec(b) else null,
    item = if (b.readBoolean()) readItemSpec(b) else null,
    entryId = b.readString(MAX_TEXT_LENGTH)
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
    moves = b.readString(MAX_SPEC_LENGTH)
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
fun pointMarketErrorText(error: PointMarketError): Text = Text.translatable(
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
        PointMarketError.STOCK_NOT_ENOUGH -> "cobblemarket.points.store.err.stock_not_enough"
        PointMarketError.NOT_ENOUGH_POINTS -> "cobblemarket.points.store.err.not_enough_points"
        PointMarketError.NO_SPACE -> "cobblemarket.points.store.err.no_space"
        PointMarketError.GENERATE_FAILED -> "cobblemarket.points.store.err.generate_failed"
        PointMarketError.STALE_ENTRY -> "cobblemarket.points.store.err.stale_entry"
    },
    // 统一传货币图标：含 %s 的词条（如「%s 余额不足」）用它，不含的多余参数会被忽略
    CurrencyService.pointsIcon()
)

object PointMarketNetwork {

    /**
     * 每个玩家**最后请求过的 (页, tab)**。
     *
     * 广播快照时按**各自在看的那一份**发 —— 统一发"操作者那一页"会把别人跳到他没在看的一页去，
     * tab 不同更是会串（精灵 tab 收到物品快照）。
     * 判据是「请求过」：服务端**不知道谁的界面开着**（自定义 Screen 不上报给服务端），
     * 但请求过就说明他的界面在用这份数据；一次没请求过的玩家一个包都不发。
     */
    private val lastRequested = mutableMapOf<java.util.UUID, Pair<Int, String>>()

    fun register() {
        registerS2CType(PointMarketItemsPayload.ID, PointMarketItemsPayload.CODEC)
        // ⚠ S2C 是**两步**注册：类型在这里注册（两端各一次），客户端 `registerS2C` 只挂 handler。
        //   漏了这一行 → 客户端启动即崩：fabric 的客户端 registerS2C **忽略 codec 参数**，
        //   类型只能靠这里；而 neoforge 那边自带 playToClient，漏了也不显形（踩坑记录见 CLAUDE.md）
        registerS2CType(ShopSnapshotsPayload.ID, ShopSnapshotsPayload.CODEC)

        // 请求某一页：进界面与翻页都走它（节流与其余列表请求同档）
        registerC2S(RequestPointMarketPayload.ID, RequestPointMarketPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "point_market_list", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            // 记下他在看哪一页、哪个 tab —— 之后别人买 / 上架 / 下架时按这个给他推新快照
            lastRequested[player.uuid] = PointMarketService.clampPage(payload.page) to payload.kind
            server.execute { sendToPlayer(player, buildSnapshot(server, payload.page, payload.kind)) }
        }

        // 上架 / 编辑：服务端全量校验后才入库，成功后**回发该格所在页**让 OP 的界面立刻刷新
        registerC2S(SavePointMarketEntryPayload.ID, SavePointMarketEntryPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allowNotify(player, "point_market_save", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                val error = PointMarketService.save(server, player, payload.index, payload.entry)
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
                val error = PointMarketService.updateStock(server, player, payload.kind, payload.index, payload.stock)
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
                val error = PointMarketService.remove(server, player, payload.kind, payload.index)
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
                        server, player, payload.kind, payload.index, payload.count, payload.entryId
                    )
                ) {
                    is PointMarketBuyResult.Failure -> {
                        player.sendMessage(
                            pointMarketErrorText(result.error).copy().formatted(Formatting.RED), false
                        )
                        sendSound(player, "result_fail")
                        // 失败也回发**他自己**那一页：库存以服务端为准 —— 别人刚买走最后一件时，
                        // 这边还停在旧数字上，不刷新就会一直「看着有货、点下去却说售罄」
                        sendToPlayer(player, buildSnapshot(server, pageOf(payload.index), payload.kind))
                    }

                    is PointMarketBuyResult.Success -> {
                        player.sendMessage(boughtText(server, payload.kind, payload.index, result), false)
                        sendSound(player, "result_success")
                        sendPurchaseCelebration(server, player, payload.kind, payload.index, result)
                        // 买走一件会改库存 —— **广播**给所有在看商城的人（服主那边那格也要跟着减）
                        broadcastSnapshot(server)
                    }
                }
            }
        }
    }

    /**
     * 购买成功的回执：**买的是什么 × 几份、花了多少 RP**。
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
        result: PointMarketBuyResult.Success
    ): Text {
        // 买完再读一次即可：库存变了，但 kind 与规格不会因为一次购买而变
        val entry = PointShopState.get(server).find(kind, index) ?: return Text.empty()
        return Text.translatable(
            "cobblemarket.points.store.bought",
            goodsName(entry),
            result.count,
            CurrencyService.amountText(result.total, MarketCurrency.POINTS)
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
        result: PointMarketBuyResult.Success
    ) {
        result.pokemon?.let { pokemon ->
            CelebrationNetwork.send(
                player,
                speciesId = pokemon.species.resourceIdentifier.toString(),
                aspects = pokemon.aspects.toList(),
                shiny = pokemon.shiny,
                source = CelebrationSource.STORE,
                // 携带物只在带组件时才非空（与挂单 / 拍卖 / 求购同口径）
                heldItemNbt = PointShopState.get(server).find(kind, index)?.pokemon?.heldItemSpec.orEmpty()
            )
            return
        }
        // ⚠ **临时诊断日志**（2026-09-26 排「RP 商城买物品没有庆祝动画」，查到根因后删）——
        //   两边服务端（主市场 / RP 商城）在这之前完全对称，只有 nbt 的来源不同，所以要看清它走到哪一步
        // ⚠ 空栈要**显式挡掉**：`ItemStack.encode` 对空栈会抛 `IllegalStateException
        //   (Cannot encode empty ItemStack)`，而这里抛出去会被 `server.execute` 吞掉 ——
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
     * ⚠ 服务端不知道谁的界面开着，所以判据是「他请求过商城快照」（见 [lastRequestedPage]）——
     * 宁可少发（没来看过的不发），也别给全服每个人塞包。
     */
    private fun broadcastSnapshot(server: MinecraftServer) {
        val online = server.playerManager.playerList
        // 顺手清掉已离线的记录：长期开着的服务器上，这个 map 会随着来过的玩家越攒越大
        val onlineIds = online.mapTo(HashSet()) { it.uuid }
        lastRequested.keys.retainAll(onlineIds)
        online.forEach { target ->
            // 按**他自己**在看的那一页 + 那个 tab 发（页与 tab 都可能和别人不同）
            val (page, kind) = lastRequested[target.uuid] ?: return@forEach
            sendToPlayer(target, buildSnapshot(server, page, kind))
        }
    }

    /** 组装配方快照列表（只发给请求过的人 / 刚操作过的人，不做广播 —— 它是服主的私人配方库） */
    private fun buildSnapshots(server: MinecraftServer): ShopSnapshotsPayload =
        ShopSnapshotsPayload(
            PointShopSnapshotState.get(server).all().map { ShopSnapshotInfo(it.name, it.entry) }
        )

    /** 组装某一页某个 tab 的快照（页号收进合法范围 —— 客户端翻页参数不可信） */
    private fun buildSnapshot(server: MinecraftServer, page: Int, kind: String): PointMarketItemsPayload {
        val safePage = PointMarketService.clampPage(page)
        val safeKind = if (kind == ShopEntry.KIND_ITEM) ShopEntry.KIND_ITEM else ShopEntry.KIND_POKEMON
        return PointMarketItemsPayload(
            page = safePage,
            totalPages = PointMarketService.pageCount(server, safeKind),
            kind = safeKind,
            slots = PointMarketService.pageEntries(server, safePage, safeKind)
                .map { (index, entry) -> PointMarketSlotInfo(index, entry) }
        )
    }

    /** 格子序号 → 所在页（回发快照用） */
    private fun pageOf(index: Int): Int = index / PointShopState.POINTS_SHOP_PAGE_SIZE
}
