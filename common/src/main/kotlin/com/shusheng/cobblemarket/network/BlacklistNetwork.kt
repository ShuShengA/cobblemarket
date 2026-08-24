package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.market.PokemonBlacklistEntry
import com.shusheng.cobblemarket.market.PokemonBlacklistState
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
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
    buf.writeInt(htFilter)
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
    shinyFilter = buf.readInt(),
    htFilter = buf.readInt()
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
    val shinyFilter: Int,
    // 特训限定：0 = 不限，1 = 仅特训，2 = 不含特训
    val htFilter: Int,
    // 编辑语义：非空 = 替换原条目（先删旧再插新），null = 新增
    val originalId: UUID?
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
                b.writeInt(p.htFilter)
                b.writeBoolean(p.originalId != null); p.originalId?.let { b.writeUuid(it) }
            },
            { b ->
                AddPokemonBlacklistPayload(
                    b.readString(),
                    b.readInt(), b.readInt(), b.readInt(),
                    b.readInt(), b.readInt(), b.readInt(),
                    (0 until b.readVarInt()).map { b.readString() },
                    b.readInt(),
                    b.readInt(),
                    if (b.readBoolean()) b.readUuid() else null
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
        registerS2CType(PokemonBlacklistDataPayload.ID, PokemonBlacklistDataPayload.CODEC)

        registerC2S(RequestPokemonBlacklistPayload.ID, RequestPokemonBlacklistPayload.CODEC) { _, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                val entries = PokemonBlacklistState.get(server).getAll()
                sendToPlayer(player, PokemonBlacklistDataPayload(entries.reversed()))
            }
        }

        registerC2S(AddPokemonBlacklistPayload.ID, AddPokemonBlacklistPayload.CODEC) { payload, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
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
                    shinyFilter = payload.shinyFilter.coerceIn(PokemonBlacklistEntry.SHINY_ANY, PokemonBlacklistEntry.SHINY_YES),
                    htFilter = payload.htFilter.coerceIn(PokemonBlacklistEntry.HT_ANY, PokemonBlacklistEntry.HT_NONE)
                )
                val state = PokemonBlacklistState.get(server)
                // 编辑语义：替换原条目（改了形态/IV/闪光/特训等字段时，旧条目不再残留）
                payload.originalId?.let { state.remove(it) }
                state.add(entry)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                val entries = PokemonBlacklistState.get(server).getAll()
                sendToPlayer(player, PokemonBlacklistDataPayload(entries.reversed()))
            }
        }

        registerC2S(RemovePokemonBlacklistPayload.ID, RemovePokemonBlacklistPayload.CODEC) { payload, player ->
            if (!player.hasPermissionLevel(2)) return@registerC2S
            val server = player.server
            server.execute {
                PokemonBlacklistState.get(server).remove(payload.id)
                // 交易后强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
                com.shusheng.cobblemarket.util.PersistHelper.requestSave(server)
                val entries = PokemonBlacklistState.get(server).getAll()
                sendToPlayer(player, PokemonBlacklistDataPayload(entries.reversed()))
            }
        }
    }
}
