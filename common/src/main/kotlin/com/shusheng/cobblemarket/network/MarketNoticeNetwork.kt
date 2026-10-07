package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import net.minecraft.nbt.NbtCompound
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.MinecraftServer
import java.util.UUID

/**
 * S2C：**市场 / 拍卖场有新东西上架**的通知（用户 2026-10-07 定）。
 *
 * 触发的是「**别人**上架」—— 上架者本人**不发**（他自己知道刚做了什么）。
 * 客户端收到后在**屏幕顶部**弹一个圆形气泡：底图 `notification.png`、圆心画那只精灵的 3D 图 /
 * 那件物品的图标，配合一声提示音（音效走现成的 [sendSound]，不在这条包上）。
 *
 * ## 为什么一个包分两种载荷
 *
 * 精灵与物品的**字段完全不同**（一个要物种 + 形态 + 闪光 + 携带物，一个要整栈 NBT + 数量），
 * 但**渲染是同一个气泡**、去向也相同 ⇒ 学 [ObjectPreviewPayload] 用一个 `isPokemon` 分支，
 * 而不是像庆祝动画那样拆两个包（那两边的**渲染器**本身就是两套，才值得拆）。
 *
 * ⚠ 「数量」单独传而不从 NBT 里取 —— 挂单 NBT 存的是上架总量，而通知要显示的是
 * **这一次**的数量（与 [ItemCelebrationPayload] 同款处理）。
 */
data class MarketNoticePayload(
    val isPokemon: Boolean,
    /** 精灵：物种资源 ID（如 `cobblemon:pikachu`）；物品时为空串 */
    val speciesId: String = "",
    /** 精灵形态（客户端会自己补 `shiny`） */
    val aspects: List<String> = emptyList(),
    val shiny: Boolean = false,
    /**
     * 精灵的携带物完整 NBT（**SNBT 字符串**，照 [PokemonCelebrationPayload] 的做法）。
     * Cobblemon 把它渲染在模型的手 / 嘴上 —— 不传的话气泡里的精灵是空手的。
     */
    val heldItemNbt: String = "",
    /** 携带物在**精灵模型上是否渲染**（玩家可在 Cobblemon 队伍详情里关掉）；缺省 = 显示 */
    val heldItemVisible: Boolean = true,
    /** 物品：整栈 NBT（含 id 与组件），客户端用 `ItemStack.fromNbtOrEmpty` 重建 */
    val itemNbt: NbtCompound? = null,
    /** 物品数量 */
    val count: Int = 1,
    /**
     * 来源：`false` = 市场挂单、`true` = 拍卖场。
     *
     * ⚠ 目前**两个来源共用同一个客户端开关**（「市场、拍卖场通知」），这里先带着 ——
     *   将来若要拆成两个开关，只改客户端读取处即可，不用动协议（照 [CelebrationSource] 的先例）。
     */
    val fromAuction: Boolean = false
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<MarketNoticePayload>(CobbleMarket.id("market_notice"))
        val CODEC: PacketCodec<PacketByteBuf, MarketNoticePayload> = PacketCodec.of(
            { p, b ->
                b.writeBoolean(p.isPokemon)
                b.writeString(p.speciesId)
                b.writeVarInt(p.aspects.size); p.aspects.forEach { b.writeString(it) }
                b.writeBoolean(p.shiny)
                b.writeString(p.heldItemNbt)
                b.writeBoolean(p.heldItemVisible)
                // ⚠ 可空 NBT 用「先写布尔再写体」的写法：PacketCodecs.NBT_COMPOUND 不接受 null
                b.writeBoolean(p.itemNbt != null)
                p.itemNbt?.let { PacketCodecs.NBT_COMPOUND.encode(b, it) }
                b.writeVarInt(p.count)
                b.writeBoolean(p.fromAuction)
            },
            { b ->
                val isPokemon = b.readBoolean()
                val speciesId = b.readString()
                val aspects = (0 until b.readVarInt()).map { b.readString() }
                val shiny = b.readBoolean()
                val heldItemNbt = b.readString()
                val heldItemVisible = b.readBoolean()
                val itemNbt = if (b.readBoolean()) PacketCodecs.NBT_COMPOUND.decode(b) else null
                val count = b.readVarInt()
                val fromAuction = b.readBoolean()
                MarketNoticePayload(
                    isPokemon = isPokemon, speciesId = speciesId, aspects = aspects, shiny = shiny,
                    heldItemNbt = heldItemNbt, heldItemVisible = heldItemVisible,
                    itemNbt = itemNbt, count = count, fromAuction = fromAuction
                )
            }
        )
    }
}

/**
 * 把这条通知发给**除 [exceptUuid] 之外**的所有在线玩家（服务端调用）。
 *
 * ⚠ **[exceptUuid] 就是上架者本人** —— 用户 2026-10-07 明确「自己上架的没有提示和提示音」。
 * 传 null 表示不排除任何人（留给"将来的系统消息"用）。
 */
fun broadcastMarketNotice(
    server: MinecraftServer,
    payload: MarketNoticePayload,
    exceptUuid: UUID? = null
) {
    server.playerManager.playerList.forEach { other ->
        if (exceptUuid != null && other.uuid == exceptUuid) return@forEach
        sendToPlayer(other, payload)
        // 提示音跟在通知后面发：客户端那条开关由 `ClientConfig` 在**播放入口**把关
        //（与庆祝动画的"个人开关在客户端过滤"同一口径，服务端不做判断）
        sendSound(other, "market_notice")
    }
}

object MarketNoticeNetwork {
    fun register() {
        registerS2CType(MarketNoticePayload.ID, MarketNoticePayload.CODEC)
    }
}
