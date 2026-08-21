package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
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
    val source: String         // CelebrationSource.name
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
            },
            { b ->
                PokemonCelebrationPayload(
                    speciesId = b.readString(),
                    aspects = (0 until b.readVarInt()).map { b.readString() },
                    shiny = b.readBoolean(),
                    source = b.readString()
                )
            }
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
        PayloadTypeRegistry.playS2C().register(PokemonCelebrationPayload.ID, PokemonCelebrationPayload.CODEC)
    }

    fun send(player: ServerPlayerEntity, speciesId: String, aspects: List<String>, shiny: Boolean, source: CelebrationSource) {
        if (!CobbleMarketConfig.celebrationAnimationEnabled) return
        ServerPlayNetworking.send(player, PokemonCelebrationPayload(speciesId, aspects, shiny, source.name))
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
            source = source
        )
    }

    /** 按 UUID 定向发送；接收者离线则跳过（动画是即时特效，不进离线补发队列） */
    fun sendFromEntry(server: MinecraftServer, uuid: UUID, species: String, shiny: Boolean, extraData: Map<String, String>, source: CelebrationSource) {
        server.playerManager.getPlayer(uuid)?.let { sendFromEntry(it, species, shiny, extraData, source) }
    }
}
