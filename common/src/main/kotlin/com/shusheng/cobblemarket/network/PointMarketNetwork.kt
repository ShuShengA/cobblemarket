package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.currency.PointMarketError
import com.shusheng.cobblemarket.currency.PointMarketService
import com.shusheng.cobblemarket.currency.PointShopState
import com.shusheng.cobblemarket.currency.ShopEntry
import com.shusheng.cobblemarket.currency.ShopItemSpec
import com.shusheng.cobblemarket.currency.ShopPokemonSpec
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import com.shusheng.cobblemarket.util.RequestThrottle
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.text.Text
import net.minecraft.util.Formatting

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
    val slots: List<PointMarketSlotInfo>
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PointMarketItemsPayload>(CobbleMarket.id("point_market_items"))
        val CODEC: PacketCodec<PacketByteBuf, PointMarketItemsPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.page)
                b.writeInt(p.totalPages)
                b.writeInt(p.slots.size)
                p.slots.forEach { slot ->
                    b.writeInt(slot.index)
                    writeEntry(b, slot.entry)
                }
            },
            { b ->
                val page = b.readInt()
                val totalPages = b.readInt()
                val count = b.readInt()
                if (count < 0 || count > MAX_SLOTS_PER_PAGE) {
                    throw IllegalArgumentException("point market slot count out of range: $count")
                }
                PointMarketItemsPayload(
                    page = page,
                    totalPages = totalPages,
                    slots = List(count) { PointMarketSlotInfo(b.readInt(), readEntry(b)) }
                )
            }
        )
    }
}

/** 请求某一页的商城快照（进界面 / 翻页时发） */
data class RequestPointMarketPayload(val page: Int) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestPointMarketPayload>(CobbleMarket.id("request_point_market"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPointMarketPayload> = PacketCodec.of(
            { p, b -> b.writeInt(p.page) },
            { b -> RequestPointMarketPayload(b.readInt()) }
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

/** OP 下架一格（清空该格） */
data class RemovePointMarketEntryPayload(val index: Int) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RemovePointMarketEntryPayload>(CobbleMarket.id("remove_point_market_entry"))
        val CODEC: PacketCodec<PacketByteBuf, RemovePointMarketEntryPayload> = PacketCodec.of(
            { p, b -> b.writeInt(p.index) },
            { b -> RemovePointMarketEntryPayload(b.readInt()) }
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
}

private fun readEntry(b: PacketByteBuf) = ShopEntry(
    // ⚠ 读端一律用具名参数：字段顺序一旦对不上，具名能立刻看出来（payload 顺序铁律）
    kind = b.readString(MAX_TEXT_LENGTH),
    price = b.readLong(),
    stock = b.readInt(),
    pokemon = if (b.readBoolean()) readPokemonSpec(b) else null,
    item = if (b.readBoolean()) readItemSpec(b) else null
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
    }
)

object PointMarketNetwork {

    fun register() {
        registerS2CType(PointMarketItemsPayload.ID, PointMarketItemsPayload.CODEC)

        // 请求某一页：进界面与翻页都走它（节流与其余列表请求同档）
        registerC2S(RequestPointMarketPayload.ID, RequestPointMarketPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "point_market_list", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute { sendToPlayer(player, buildSnapshot(server, payload.page)) }
        }

        // 上架 / 编辑：服务端全量校验后才入库，成功后**回发该格所在页**让 OP 的界面立刻刷新
        registerC2S(SavePointMarketEntryPayload.ID, SavePointMarketEntryPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "point_market_save", RequestThrottle.WRITE_INTERVAL_MS)) {
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
                    sendToPlayer(player, buildSnapshot(server, pageOf(payload.index)))
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

        registerC2S(RemovePointMarketEntryPayload.ID, RemovePointMarketEntryPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "point_market_remove", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                val error = PointMarketService.remove(server, player, payload.index)
                if (error == null) {
                    player.sendMessage(
                        Text.translatable("cobblemarket.points.store.removed", payload.index + 1)
                            .formatted(Formatting.GREEN),
                        false
                    )
                    sendToPlayer(player, buildSnapshot(server, pageOf(payload.index)))
                    // 下架的结果音效（同上，这两个包不走 MarketResultPayload）
                    sendSound(player, "result_success")
                } else {
                    player.sendMessage(pointMarketErrorText(error).copy().formatted(Formatting.RED), false)
                    sendSound(player, "result_fail")
                }
            }
        }
    }

    /** 组装某一页的快照（页号收进合法范围 —— 客户端翻页参数不可信） */
    private fun buildSnapshot(server: MinecraftServer, page: Int): PointMarketItemsPayload {
        val safePage = PointMarketService.clampPage(page)
        return PointMarketItemsPayload(
            page = safePage,
            totalPages = PointMarketService.pageCount(server),
            slots = PointMarketService.pageEntries(server, safePage)
                .map { (index, entry) -> PointMarketSlotInfo(index, entry) }
        )
    }

    /** 格子序号 → 所在页（回发快照用） */
    private fun pageOf(index: Int): Int = index / PointShopState.POINTS_SHOP_PAGE_SIZE
}
