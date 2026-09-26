package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import net.minecraft.nbt.NbtCompound
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID

// ── S2C：获得精灵庆祝动画（仅定向发给获得者）：屏幕中央播放该精灵的弹跳动画 ──

/**
 * 动画来源。这里按**真实来源**分三个值，而不是按客户端开关分组——
 * 客户端目前把 AUCTION 与 BUY_ORDER 映射到同一个开关（都是低频、都带仪式感），
 * 将来若要拆成三个独立开关，只改客户端映射即可，不用动网络协议。
 */
enum class CelebrationSource { MARKET, AUCTION, BUY_ORDER }

data class PokemonCelebrationPayload(
    val speciesId: String,     // 物种资源 ID
    val aspects: List<String>, // 精灵形态（客户端叠加 shiny 渲染）
    val shiny: Boolean,
    val source: String,        // CelebrationSource.name
    /** 携带物完整 NBT（SNBT，仅带组件时非空）。客户端重建模型时喂给 RenderablePokemon——
     *  Cobblemon 会把手/嘴上的物品渲染出来，不传的话动画里的精灵是空手的 */
    val heldItemNbt: String = "",
    /**
     * 携带物在**精灵模型上是否渲染** —— 庆祝动画里的精灵同样是模型，隐藏时手上不拿东西
     *（用户 2026-09-26 拍板：隐藏只管模型，其余地方照常显示）。缺省 true = 显示
     */
    val heldItemVisible: Boolean = true
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<PokemonCelebrationPayload>(CobbleMarket.id("pokemon_celebration"))
        val CODEC: PacketCodec<PacketByteBuf, PokemonCelebrationPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.speciesId)
                b.writeVarInt(p.aspects.size); p.aspects.forEach { b.writeString(it) }
                b.writeBoolean(p.shiny)
                b.writeString(p.source)
                b.writeString(p.heldItemNbt)
                b.writeBoolean(p.heldItemVisible)
            },
            { b ->
                PokemonCelebrationPayload(
                    speciesId = b.readString(),
                    aspects = (0 until b.readVarInt()).map { b.readString() },
                    shiny = b.readBoolean(),
                    source = b.readString(),
                    heldItemNbt = b.readString(),
                    heldItemVisible = b.readBoolean()
                )
            }
        )
    }
}

// ── S2C：获得物品庆祝动画（仅定向发给买家）：屏幕中央播放该物品的弹跳动画 ──

/**
 * 物品与精灵的庆祝动画是**两套独立渲染**（精灵画 3D 模型、物品画 GUI 图标栈），字段也完全不同，
 * 所以单独一个包，而不是往 [PokemonCelebrationPayload] 里塞类型分支。
 *
 * [count] 单独传而不从 NBT 里取：挂单 NBT 存的是上架时的总量，买家可能只买走一部分。
 */
data class ItemCelebrationPayload(
    val itemNbt: NbtCompound,  // 物品栈 NBT（含 id 与组件），客户端用 ItemStack.fromNbtOrEmpty 重建
    val count: Int,            // 本次实际到手的数量
    val source: String         // CelebrationSource.name
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<ItemCelebrationPayload>(CobbleMarket.id("item_celebration"))
        val CODEC: PacketCodec<PacketByteBuf, ItemCelebrationPayload> = PacketCodec.of(
            { p, b ->
                PacketCodecs.NBT_COMPOUND.encode(b, p.itemNbt)
                b.writeInt(p.count)
                b.writeString(p.source)
            },
            { b ->
                ItemCelebrationPayload(
                    itemNbt = PacketCodecs.NBT_COMPOUND.decode(b),
                    count = b.readInt(),
                    source = b.readString()
                )
            }
        )
    }
}

// ── S2C：发卡动画（紫电卡/金轮卡首次获得或补发）：卡片从申请界面图标位置放大飞到屏幕中央 ──

data class CardCelebrationPayload(
    val kind: String   // "purple" / "black"
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CardCelebrationPayload>(CobbleMarket.id("card_celebration"))
        val CODEC: PacketCodec<PacketByteBuf, CardCelebrationPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.kind) },
            { b -> CardCelebrationPayload(b.readString()) }
        )
    }
}

/**
 * 获得精灵时的庆祝动画下发。
 *
 * 触发口径：**精灵的所有权首次转到该玩家名下时播一次**——买到、拍到、求购单接受交付。
 * 过期退回 / 取消挂单 / 管理端强制下架 / 从待领取领取都不播：那些精灵本来就是自己的，
 * 领取只是取货（否则攒一堆一次领会连播一长串）。
 *
 * 总开关判断集中在这里，各调用点无需重复判断；关闭时服务端直接不发包。
 */
object CelebrationNetwork {

    fun register() {
        registerS2CType(PokemonCelebrationPayload.ID, PokemonCelebrationPayload.CODEC)
        registerS2CType(ItemCelebrationPayload.ID, ItemCelebrationPayload.CODEC)
        registerS2CType(CardCelebrationPayload.ID, CardCelebrationPayload.CODEC)
    }

    fun send(
        player: ServerPlayerEntity,
        speciesId: String,
        aspects: List<String>,
        shiny: Boolean,
        source: CelebrationSource,
        heldItemNbt: String = "",
        heldItemVisible: Boolean = true
    ) {
        sendToPlayer(player, PokemonCelebrationPayload(speciesId, aspects, shiny, source.name, heldItemNbt, heldItemVisible))
    }

    /**
     * 获得物品时的庆祝动画下发。触发口径与精灵完全一致：**物品所有权首次转到该玩家名下时播一次**
     * （买到 / 拍到 / 求购单接受交付），领取待领取、退回、下架都不播。
     */
    fun sendItem(player: ServerPlayerEntity, itemNbt: NbtCompound, count: Int, source: CelebrationSource) {
        sendToPlayer(player, ItemCelebrationPayload(itemNbt, count, source.name))
    }

    /** 按 UUID 定向发送；接收者离线则跳过（同精灵版：动画是即时特效，不进离线补发队列） */
    fun sendItem(server: MinecraftServer, uuid: UUID, itemNbt: NbtCompound, count: Int, source: CelebrationSource) {
        server.playerManager.getPlayer(uuid)?.let { sendItem(it, itemNbt, count, source) }
    }

    /**
     * 发卡动画（紫电卡/金轮卡首次获得或补发）：卡片从申请界面图标位置放大飞到屏幕中央，
     * 像真的在发卡（客户端卡片放大动画，起点由申请界面点击时登记）。
     */
    fun sendCard(player: ServerPlayerEntity, kind: String) {
        sendToPlayer(player, CardCelebrationPayload(kind))
    }

    /**
     * 从条目的持久化字段取参数：挂单、拍卖、求购单交付的 extraData 都带
     * speciesId 与逗号分隔的 aspects（分别由 buildListingExtra/buildAuctionExtra/buildDeliveryExtra 写入），
     * 不必重新解析 NBT。
     */
    fun sendFromEntry(player: ServerPlayerEntity, species: String, shiny: Boolean, extraData: Map<String, String>, source: CelebrationSource) {
        send(
            player,
            speciesId = extraData["speciesId"] ?: species,
            aspects = (extraData["aspects"] ?: "").split(",").filter { it.isNotEmpty() },
            shiny = shiny,
            source = source,
            // 携带物一并下发：三个触发点的 extraData 都带着这个键（拍卖/求购/市场挂单同款口径）
            heldItemNbt = extraData["heldItemNbt"].orEmpty(),
            // 「模型手里拿不拿」：缺这个键（旧挂单 / 旧服务端）= 显示
            heldItemVisible = extraData["heldItemVisible"] != "false"
        )
    }

    /** 按 UUID 定向发送；接收者离线则跳过（动画是即时特效，不进离线补发队列） */
    fun sendFromEntry(server: MinecraftServer, uuid: UUID, species: String, shiny: Boolean, extraData: Map<String, String>, source: CelebrationSource) {
        server.playerManager.getPlayer(uuid)?.let { sendFromEntry(it, species, shiny, extraData, source) }
    }
}
