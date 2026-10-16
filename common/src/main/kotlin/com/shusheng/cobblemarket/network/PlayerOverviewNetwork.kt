package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.overview.PlayerOverviewRow
import com.shusheng.cobblemarket.overview.PlayerOverviewService
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/** 请求全服玩家总览（C2S）—— 打开界面时发一次，服务端现算后全量回。 */
data class RequestPlayerOverviewPayload(val dummy: Int = 0) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestPlayerOverviewPayload>(CobbleMarket.id("request_player_overview"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPlayerOverviewPayload> = PacketCodec.of(
            { _, _ -> },
            { _ -> RequestPlayerOverviewPayload() }
        )
    }
}

/**
 * 全服玩家总览（S2C）—— **一次全量下发**，搜索与排序都在客户端本地做
 * （200 人 × 二十几个字段 ≈ 几十 KB，一包没问题；也避开了分页那一整套坑）。
 */
data class PlayerOverviewPayload(val rows: List<PlayerOverviewRow>) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PlayerOverviewPayload>(CobbleMarket.id("player_overview"))

        val CODEC: PacketCodec<PacketByteBuf, PlayerOverviewPayload> = PacketCodec.of(
            { p, b ->
                b.writeVarInt(p.rows.size)
                p.rows.forEach { r -> OverviewRowCodec.write(b, r) }
            },
            { b ->
                val n = b.readVarInt()
                // ⚠ 读端一律**具名传参**：顺序写错会静默串位（整列显示错位，编译不报）—— 照 BanChangesPayload
                PlayerOverviewPayload(List(n) { OverviewRowCodec.read(b) })
            }
        )
    }
}

/**
 * 全服玩家总览的网络通道。
 *
 * ⚠⚠ **S2C 是两步注册，缺一不可**（项目为此栽过两次）：
 * 1. 这里的 `registerS2CType` —— 漏了**客户端启动即崩**
 * 2. 客户端 `CobbleMarketClient` 的 `registerS2C` —— 漏了**静默无反应**
 * ⚠ **NeoForge 不显形**（它的 `playToClient` 自带类型注册）⇒ 漏第 1 步只在 Fabric 上崩。
 */
object PlayerOverviewNetwork {

    fun register() {
        registerS2CType(PlayerOverviewPayload.ID, PlayerOverviewPayload.CODEC)

        // ⚠ C2S 必须写在**本函数体内** —— 插到别的函数里会编译通过但永不注册，第一次发包直接连
        registerC2S(RequestPlayerOverviewPayload.ID, RequestPlayerOverviewPayload.CODEC) { _, player ->
            // 判据 = overview 节点（服主恒真）。客户端那侧只管显示入口，这里才是真正拦人的地方
            if (!MarketPermissions.has(player, MarketPermissions.OVERVIEW)) return@registerC2S
            sendToPlayer(player, PlayerOverviewPayload(PlayerOverviewService.buildRows(player.server)))
        }
    }
}

/**
 * 一行的读写 —— **总览与 ID 卡共用同一份**（两处各写一遍的话，加字段时迟早只改一处 ⇒ 静默串位）。
 *
 * ⚠ **读端一律具名传参**：顺序写错会静默串位（整列显示错位，编译不报），照 `BanChangesPayload`。
 */
internal object OverviewRowCodec {

    fun write(b: PacketByteBuf, r: PlayerOverviewRow) {
        b.writeUuid(r.uuid)
        b.writeString(r.name)
        b.writeBoolean(r.online)
        b.writeVarInt(r.pokemonListings)
        b.writeVarInt(r.itemListings)
        b.writeVarInt(r.activeAuctions)
        b.writeVarInt(r.openBuyOrders)
        b.writeVarInt(r.pendingPokemon)
        b.writeVarInt(r.pendingItems)
        b.writeLong(r.pendingBalance)
        b.writeBoolean(r.balance != null)
        r.balance?.let { b.writeLong(it) }
        b.writeBoolean(r.pcoBalance != null)
        r.pcoBalance?.let { b.writeLong(it) }
        b.writeBoolean(r.fromSnapshot)
        b.writeLong(r.creditLimit)
        b.writeLong(r.debt)
        b.writeLong(r.debtInterest)
        b.writeLong(r.depositPrincipal)
        b.writeLong(r.depositInterest)
        b.writeLong(r.netDeposit)
        b.writeBoolean(r.overdue)
        b.writeBoolean(r.badDebt)
        b.writeBoolean(r.purpleCard)
        b.writeBoolean(r.blackCard)
        b.writeBoolean(r.banned)
        b.writeBoolean(r.banUntil != null)
        r.banUntil?.let { b.writeLong(it) }
        b.writeBoolean(r.loanBlocked)
        b.writeLong(r.totalVolume)
        b.writeBoolean(r.dexSeen != null)
        r.dexSeen?.let { b.writeVarInt(it) }
        b.writeBoolean(r.dexCaught != null)
        r.dexCaught?.let { b.writeVarInt(it) }
    }

    fun read(b: PacketByteBuf): PlayerOverviewRow = PlayerOverviewRow(
        uuid = b.readUuid(),
        name = b.readString(),
        online = b.readBoolean(),
        pokemonListings = b.readVarInt(),
        itemListings = b.readVarInt(),
        activeAuctions = b.readVarInt(),
        openBuyOrders = b.readVarInt(),
        pendingPokemon = b.readVarInt(),
        pendingItems = b.readVarInt(),
        pendingBalance = b.readLong(),
        balance = if (b.readBoolean()) b.readLong() else null,
        pcoBalance = if (b.readBoolean()) b.readLong() else null,
        fromSnapshot = b.readBoolean(),
        creditLimit = b.readLong(),
        debt = b.readLong(),
        debtInterest = b.readLong(),
        depositPrincipal = b.readLong(),
        depositInterest = b.readLong(),
        netDeposit = b.readLong(),
        overdue = b.readBoolean(),
        badDebt = b.readBoolean(),
        purpleCard = b.readBoolean(),
        blackCard = b.readBoolean(),
        banned = b.readBoolean(),
        banUntil = if (b.readBoolean()) b.readLong() else null,
        loanBlocked = b.readBoolean(),
        totalVolume = b.readLong(),
        dexSeen = if (b.readBoolean()) b.readVarInt() else null,
        dexCaught = if (b.readBoolean()) b.readVarInt() else null,
    )
}
