package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.market.AuctionListing
import com.shusheng.cobblemarket.market.AuctionState
import com.shusheng.cobblemarket.market.AuctionType
import com.shusheng.cobblemarket.market.BuyOrder
import com.shusheng.cobblemarket.market.BuyOrderState
import com.shusheng.cobblemarket.market.BuyOrderType
import com.shusheng.cobblemarket.market.PendingDelivery
import com.shusheng.cobblemarket.market.PokemonBlacklistEntry
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import com.shusheng.cobblemarket.util.PokemonLoader
import com.shusheng.cobblemarket.util.RequestThrottle
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.MinecraftServer
import java.util.UUID

/**
 * **聊天播报的悬浮大图**：鼠标停在拍卖/求购的全服播报上时，客户端要画那个拍品的放大预览。
 *
 * 数据只在服务端手里（拍品 NBT 不在广播包里），所以走一次「悬停 → 请求 → 定向回包」；
 * 走服务端而不是把 NBT 塞进聊天消息里，是为了让每个拍品的字节只发给**真的看了**的那个玩家。
 *
 * 客户端侧见 `client/ChatObjectPreview.kt`（悬停判定、缓存、渲染），本文件只管数据往返。
 */

// ── C2S：请求某个拍品的大图数据 ──

/** [kind] 用 [ObjectPreviewNetwork.KIND_AUCTION] / [ObjectPreviewNetwork.KIND_BUY_ORDER]；[id] = 拍品/订单的 UUID 文本 */
data class RequestObjectPreviewPayload(val kind: String, val id: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestObjectPreviewPayload>(CobbleMarket.id("request_object_preview"))
        val CODEC: PacketCodec<PacketByteBuf, RequestObjectPreviewPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.kind); b.writeString(p.id) },
            { b -> RequestObjectPreviewPayload(kind = b.readString(), id = b.readString()) }
        )
    }
}

// ── S2C：拍品的大图数据（只带渲染需要的字段，不带价格/卖家等聊天里已有的东西） ──

/**
 * 精灵与物品共用一条包（[isPokemon] 分流），因为两者本来就是同一个入口的两种结果；
 * 客户端重建栈/模型需要的东西都在这里：
 * - 精灵：物种 ID + 形态 + 闪光 + 携带物 NBT（**完整 NBT**，只给 ID 会丢附魔光效与自定义名）
 * - 物品：两种来源的 NBT 格式不同 —— 拍卖存的是**整栈 NBT**（`ItemStack.fromNbtOrEmpty` 可直接解），
 *   求购单存的是**组件快照**（要套 `{id,count,components}` 外壳），故分两个字段发，
 *   客户端按哪个非空决定走哪条重建路径
 */
data class ObjectPreviewPayload(
    val id: String,                       // 拍品/订单 UUID 文本（客户端的缓存键）
    val isPokemon: Boolean,
    val speciesId: String,
    val aspects: List<String>,
    val shiny: Boolean,
    val heldItemNbt: NbtCompound?,
    val itemId: String,
    val itemNbt: NbtCompound?,            // 拍卖物品：整栈 NBT
    val itemComponentsSpec: NbtCompound?, // 求购物品：组件快照
    val count: Int,
    /**
     * 携带物在**精灵模型上是否渲染**（`Pokemon.heldItemVisible`，玩家可在 Cobblemon 队伍详情里关掉）。
     * ⚠ 只管大图里的**精灵模型手里** —— 携带物 NBT 照常下发、信息照常显示（用户 2026-09-26 拍板）
     */
    val heldItemVisible: Boolean = true,
    /**
     * **精灵的完整信息行数据**（[ListingEntry]）—— 客户端聊天悬停窗据此画出
     * **与精灵市场行悬停窗逐字段一致**的信息行（等级 / 属性+太晶 / 极巨 / 性格 / 特性 /
     * 球种 / 携带物 / IV+EV / 亲密度 / 初始训练家 / 技能 / 证章 / 卖家 / 价格）。
     *
     * 为什么要它：悬停窗那套渲染器（`EntryBadgeRenderer.drawInfoLines`）吃的是 [ListingEntry]，
     * 而这条包原本只有物种 / 形态 / 闪光 / 携带物 —— **画不出信息行**。
     * 服务端本来就握着完整精灵 NBT，顺手组装一份即可（`MarketNetwork.buildListingEntry`）。
     *
     * ⚠ **物品侧恒为 null**：物品词条由客户端拿原版 tooltip 现算（附魔 / 药水 / 属性修饰符
     *   全自动继承），不需要服务端组装。
     * ⚠ 求购的**需求**预览同样为 null —— 那是「买家想要什么」而不是实物，按它画完整信息行
     *   会误导玩家；只有**待交付的实物**才有（见 `deliveryPreview`）。
     */
    val listing: ListingEntry? = null
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<ObjectPreviewPayload>(CobbleMarket.id("object_preview"))
        val CODEC: PacketCodec<PacketByteBuf, ObjectPreviewPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.id)
                b.writeBoolean(p.isPokemon)
                b.writeString(p.speciesId)
                b.writeVarInt(p.aspects.size); p.aspects.forEach { b.writeString(it) }
                b.writeBoolean(p.shiny)
                b.writeBoolean(p.heldItemNbt != null)
                p.heldItemNbt?.let { PacketCodecs.NBT_COMPOUND.encode(b, it) }
                b.writeString(p.itemId)
                b.writeBoolean(p.itemNbt != null)
                p.itemNbt?.let { PacketCodecs.NBT_COMPOUND.encode(b, it) }
                b.writeBoolean(p.itemComponentsSpec != null)
                p.itemComponentsSpec?.let { PacketCodecs.NBT_COMPOUND.encode(b, it) }
                b.writeInt(p.count)
                b.writeBoolean(p.heldItemVisible)
                // ⚠ 新增字段**一律追加在末尾**，读端严格同序；两端必须同步升级
                b.writeBoolean(p.listing != null)
                p.listing?.write(b)
            },
            { b ->
                // 具名参数：这段读序与上面的写序一一对应，错位了会静默读出垃圾数据
                ObjectPreviewPayload(
                    id = b.readString(),
                    isPokemon = b.readBoolean(),
                    speciesId = b.readString(),
                    aspects = (0 until b.readVarInt()).map { b.readString() },
                    shiny = b.readBoolean(),
                    heldItemNbt = if (b.readBoolean()) PacketCodecs.NBT_COMPOUND.decode(b) else null,
                    itemId = b.readString(),
                    itemNbt = if (b.readBoolean()) PacketCodecs.NBT_COMPOUND.decode(b) else null,
                    itemComponentsSpec = if (b.readBoolean()) PacketCodecs.NBT_COMPOUND.decode(b) else null,
                    count = b.readInt(),
                    heldItemVisible = b.readBoolean(),
                    // 与 write 端严格同序（write 在 heldItemVisible 之后）
                    listing = if (b.readBoolean()) ListingEntry.read(b) else null
                )
            }
        )
    }
}

object ObjectPreviewNetwork {

    /** 拍卖播报的 kind（与客户端指令 `/cobblemarket auction <id>` 的后半段一致） */
    const val KIND_AUCTION = "auction"

    /** 求购单播报的 kind（客户端指令是 `/cobblemarket review <id>`，用词与指令保持一致） */
    const val KIND_BUY_ORDER = "buyorder"

    fun register() {
        registerS2CType(ObjectPreviewPayload.ID, ObjectPreviewPayload.CODEC)

        registerC2S(RequestObjectPreviewPayload.ID, RequestObjectPreviewPayload.CODEC) { payload, player ->
            // 只读数据，但同样要限流：客户端悬停即请求，扫过一屏聊天能连发几十条
            if (!RequestThrottle.allow(player.uuid, "object_preview", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            val server = player.server
            server.execute {
                val uuid = runCatching { UUID.fromString(payload.id) }.getOrNull() ?: return@execute
                // 查不到（已结算/已关闭/条目损坏）就静默不发：聊天里那条播报已经过期，客户端没有可展示的东西
                val preview = when (payload.kind) {
                    KIND_AUCTION -> AuctionState.get(server).getAuction(uuid)?.let { auctionPreview(server, it) }
                    // 求购单：**有待交付时画卖家交来的那只实物**（带了什么携带物、什么形态、是不是闪光，
                    // 只能以实体为准）；没有才退回买家的需求 —— 悬停大图的语义是「看货」，
                    // 用需求画等于要买家凭想象判断（2026-09-25 用户指出）
                    KIND_BUY_ORDER -> BuyOrderState.get(server).getOrder(uuid)?.let { order ->
                        order.pendingDeliveries.lastOrNull()?.let { deliveryPreview(server, it) }
                            ?: buyOrderPreview(order)
                    }
                    else -> null
                } ?: return@execute
                sendToPlayer(player, preview)
            }
        }
    }

    /** 拍卖拍品：精灵读挂单里的完整 NBT，物品读 `species`（物品 id 存在这个字段里）+ `itemNbt` */
    private fun auctionPreview(server: MinecraftServer, auction: AuctionListing): ObjectPreviewPayload? =
        if (auction.type == AuctionType.POKEMON) {
            val pokemon = auction.pokemonNbt?.let { nbt ->
                try {
                    PokemonLoader.fromNbt(server.registryManager, nbt)
                } catch (e: Exception) {
                    // 单条 NBT 损坏不该让玩家掉线：退回下面的 extraData 兜底（只有物种与形态，但大图仍画得出来）
                    CobbleMarket.LOGGER.warn("Failed to load pokemon NBT for preview {}: {}", auction.id, e.message)
                    null
                }
            }
            if (pokemon != null) {
                ObjectPreviewPayload(
                    id = auction.id.toString(),
                    isPokemon = true,
                    speciesId = pokemon.species.resourceIdentifier.toString(),
                    aspects = pokemon.aspects.toList(),
                    shiny = pokemon.shiny,
                    // 携带物**总是**下发（大图要如实显示它带了什么）；「模型手里拿不拿」由 heldItemVisible 带下去
                    heldItemNbt = encodeStack(pokemon.heldItem(), server),
                    heldItemVisible = pokemon.heldItemVisible,
                    itemId = "",
                    itemNbt = null,
                    itemComponentsSpec = null,
                    count = 1,
                    // 完整信息行（悬停窗右侧那套）：从拍品自带的数据组装，与市场行悬停窗同源
                    listing = MarketNetwork.buildListingEntry(auction, server)
                )
            } else {
                // pokemonNbt 为空/损坏的兜底：extraData 里有物种 ID 与形态（老拍卖也带），拿它画默认形态
                ObjectPreviewPayload(
                    id = auction.id.toString(),
                    isPokemon = true,
                    speciesId = auction.extraData["speciesId"] ?: "cobblemon:${auction.species.lowercase()}",
                    aspects = auction.extraData["aspects"].orEmpty().split(',').filter { it.isNotBlank() },
                    shiny = auction.shiny,
                    heldItemNbt = null,
                    itemId = "",
                    itemNbt = null,
                    itemComponentsSpec = null,
                    count = 1
                )
            }
        } else {
            ObjectPreviewPayload(
                id = auction.id.toString(),
                isPokemon = false,
                speciesId = "",
                aspects = emptyList(),
                shiny = false,
                heldItemNbt = null,
                itemId = auction.species,
                itemNbt = auction.itemNbt,
                itemComponentsSpec = null,
                count = auction.count
            )
        }

    /** 求购单：**是"需求"不是实精灵/实物**，只能按需求字段画一只「长这样的」——物种/形态/闪光而已 */
    /**
     * **待交付的实物**预览（「查看待交付」悬停时的大图）。
     *
     * ⚠ 与 [buyOrderPreview] 的区别：这支画的是**卖家交来的那一只**，不是买家的需求 ——
     * 携带物、形态、闪光都以实体为准（2026-09-25 用户指出：用需求画会误导买家）。
     *
     * 锁定策略下待交付**最多一条**（`BuyOrderPendingEntry.pending` 的注释），调用方取那一条即可；
     * 买家拒绝后列表清空 ⇒ 悬停历史通知时将退回需求预览（或买家那条单已经没了）。
     */
    private fun deliveryPreview(server: MinecraftServer, delivery: PendingDelivery): ObjectPreviewPayload? {
        if (delivery.itemNbt != null) {
            // 物品交付：整栈 NBT（与拍卖同一条重建路径）
            return ObjectPreviewPayload(
                id = delivery.id.toString(),
                isPokemon = false,
                speciesId = "",
                aspects = emptyList(),
                shiny = false,
                heldItemNbt = null,
                itemId = "",
                itemNbt = delivery.itemNbt,
                itemComponentsSpec = null,
                count = delivery.count
            )
        }
        // 精灵交付：物种与形态走交付快照（一定有），闪光从记录里取（快照不含）
        val extra = delivery.extraData
        val speciesId = extra["speciesId"] ?: return null
        return ObjectPreviewPayload(
            id = delivery.id.toString(),
            isPokemon = true,
            speciesId = speciesId,
            aspects = extra["aspects"].orEmpty().split(",").filter { it.isNotBlank() },
            shiny = delivery.shiny,
            heldItemNbt = null,
            itemId = "",
            itemNbt = null,
            itemComponentsSpec = null,
            count = 1,
            // 与拍卖同源：交付快照（`buildDeliveryExtra`）的键名与挂单快照一致 ⇒ 信息行同样是完整的
            listing = MarketNetwork.buildListingEntry(delivery, server)
        )
    }

    private fun buyOrderPreview(order: BuyOrder): ObjectPreviewPayload? =
        if (order.type == BuyOrderType.POKEMON) {
            // 「任意精灵」没有具体物种可画，静默不发（客户端拿不到数据就不显示大图）
            val speciesId = order.speciesId ?: return null
            ObjectPreviewPayload(
                id = order.id.toString(),
                isPokemon = true,
                speciesId = speciesId,
                // 形态照黑名单语义：["*"] = 不限，画默认形态（空 aspects）；否则用需求里的形态列表
                aspects = if (PokemonBlacklistEntry.ALL_FORMS in order.aspects) emptyList() else order.aspects,
                // 仅闪需求画成闪光形态（不是闪的买家也收，画出来更有辨识度）
                shiny = order.shinyFilter == PokemonBlacklistEntry.SHINY_YES,
                heldItemNbt = null,
                itemId = "",
                itemNbt = null,
                itemComponentsSpec = null,
                count = 1
            )
        } else {
            ObjectPreviewPayload(
                id = order.id.toString(),
                isPokemon = false,
                speciesId = "",
                aspects = emptyList(),
                shiny = false,
                heldItemNbt = null,
                itemId = order.itemId,
                itemNbt = null,
                itemComponentsSpec = order.itemComponentsSpec,
                count = order.totalCount
            )
        }

    /** 物品栈 → 整栈 NBT（客户端 `ItemStack.fromNbtOrEmpty` 直接解）；空栈返回 null */
    private fun encodeStack(stack: ItemStack, server: MinecraftServer): NbtCompound? {
        if (stack.isEmpty) return null
        return try {
            stack.encode(server.registryManager) as? NbtCompound
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to encode item stack for preview: {}", e.message)
            null
        }
    }
}
