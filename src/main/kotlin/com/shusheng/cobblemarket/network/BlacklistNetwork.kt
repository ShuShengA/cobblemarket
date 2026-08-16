package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.market.PokemonBlacklistEntry
import com.shusheng.cobblemarket.market.PokemonBlacklistState
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import java.util.UUID

fun PokemonBlacklistEntry.write(buf: PacketByteBuf) {
    buf.writeUuid(id)
    buf.writeString(speciesId)
    buf.writeInt(ivHp)
    buf.writeInt(ivAtk)
    buf.writeInt(ivDef)
    buf.writeInt(ivSpAtk)
    buf.writeInt(ivSpDef)
    buf.writeInt(ivSpd)
    buf.writeVarInt(aspects.size); aspects.forEach { buf.writeString(it) }
    buf.writeInt(shinyFilter)
}

fun readBlacklistEntry(buf: PacketByteBuf): PokemonBlacklistEntry = PokemonBlacklistEntry(
    id = buf.readUuid(),
    speciesId = buf.readString(),
    ivHp = buf.readInt(),
    ivAtk = buf.readInt(),
    ivDef = buf.readInt(),
    ivSpAtk = buf.readInt(),
    ivSpDef = buf.readInt(),
    ivSpd = buf.readInt(),
    aspects = (0 until buf.readVarInt()).map { buf.readString() },
    shinyFilter = buf.readInt()
)

// ── C2S: 请求黑名单 ──

class RequestPokemonBlacklistPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestPokemonBlacklistPayload>(CobbleMarket.id("request_pokemon_blacklist"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPokemonBlacklistPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestPokemonBlacklistPayload() }
        )
    }
}

// ── C2S: 添加黑名单 ──

data class AddPokemonBlacklistPayload(
    val speciesId: String,
    val ivHp: Int,
    val ivAtk: Int,
    val ivDef: Int,
    val ivSpAtk: Int,
    val ivSpDef: Int,
    val ivSpd: Int,
    val aspects: List<String>,
    val shinyFilter: Int
) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<AddPokemonBlacklistPayload>(CobbleMarket.id("add_pokemon_blacklist"))
        val CODEC: PacketCodec<PacketByteBuf, AddPokemonBlacklistPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.speciesId)
                b.writeInt(p.ivHp); b.writeInt(p.ivAtk); b.writeInt(p.ivDef)
                b.writeInt(p.ivSpAtk); b.writeInt(p.ivSpDef); b.writeInt(p.ivSpd)
                b.writeVarInt(p.aspects.size); p.aspects.forEach { b.writeString(it) }
                b.writeInt(p.shinyFilter)
            },
            { b ->
                AddPokemonBlacklistPayload(
                    b.readString(),
                    b.readInt(), b.readInt(), b.readInt(),
                    b.readInt(), b.readInt(), b.readInt(),
                    (0 until b.readVarInt()).map { b.readString() },
                    b.readInt()
                )
            }
        )
    }
}

// ── C2S: 删除黑名单 ──

data class RemovePokemonBlacklistPayload(val id: UUID) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RemovePokemonBlacklistPayload>(CobbleMarket.id("remove_pokemon_blacklist"))
        val CODEC: PacketCodec<PacketByteBuf, RemovePokemonBlacklistPayload> = PacketCodec.of(
            { p, b -> b.writeUuid(p.id) },
            { b -> RemovePokemonBlacklistPayload(b.readUuid()) }
        )
    }
}

// ── S2C: 黑名单列表 ──

data class PokemonBlacklistDataPayload(val entries: List<PokemonBlacklistEntry>) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<PokemonBlacklistDataPayload>(CobbleMarket.id("pokemon_blacklist_data"))
        val CODEC: PacketCodec<PacketByteBuf, PokemonBlacklistDataPayload> = PacketCodec.of(
            { p, b -> b.writeVarInt(p.entries.size); p.entries.forEach { it.write(b) } },
            { b -> PokemonBlacklistDataPayload((0 until b.readVarInt()).map { readBlacklistEntry(b) }) }
        )
    }
}

object BlacklistNetwork {

    fun register() {
        PayloadTypeRegistry.playC2S().register(RequestPokemonBlacklistPayload.ID, RequestPokemonBlacklistPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(AddPokemonBlacklistPayload.ID, AddPokemonBlacklistPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(RemovePokemonBlacklistPayload.ID, RemovePokemonBlacklistPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(PokemonBlacklistDataPayload.ID, PokemonBlacklistDataPayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(RequestPokemonBlacklistPayload.ID) { _, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val entries = PokemonBlacklistState.get(server).getAll()
                ServerPlayNetworking.send(player, PokemonBlacklistDataPayload(entries))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(AddPokemonBlacklistPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                val speciesId = com.shusheng.cobblemarket.util.SpeciesText.resolveByNameOrId(payload.speciesId)
                if (speciesId == null) {
                    // 解析失败明确反馈，不再静默丢弃
                    player.sendMessage(
                        net.minecraft.text.Text.translatable("cobblemarket.blacklist.not_found")
                            .formatted(net.minecraft.util.Formatting.RED), false)
                    return@execute
                }
                val entry = PokemonBlacklistEntry(
                    id = UUID.randomUUID(),
                    speciesId = speciesId,
                    ivHp = payload.ivHp,
                    ivAtk = payload.ivAtk,
                    ivDef = payload.ivDef,
                    ivSpAtk = payload.ivSpAtk,
                    ivSpDef = payload.ivSpDef,
                    ivSpd = payload.ivSpd,
                    aspects = payload.aspects,
                    shinyFilter = payload.shinyFilter.coerceIn(PokemonBlacklistEntry.SHINY_ANY, PokemonBlacklistEntry.SHINY_YES)
                )
                PokemonBlacklistState.get(server).add(entry)
                val entries = PokemonBlacklistState.get(server).getAll()
                ServerPlayNetworking.send(player, PokemonBlacklistDataPayload(entries))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(RemovePokemonBlacklistPayload.ID) { payload, context ->
            val player = context.player()
            if (!player.hasPermissionLevel(2)) return@registerGlobalReceiver
            val server = player.server
            server.execute {
                PokemonBlacklistState.get(server).remove(payload.id)
                val entries = PokemonBlacklistState.get(server).getAll()
                ServerPlayNetworking.send(player, PokemonBlacklistDataPayload(entries))
            }
        }
    }
}
