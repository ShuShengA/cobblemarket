package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.currency.CustomShopError
import com.shusheng.cobblemarket.currency.CustomShopState
import com.shusheng.cobblemarket.currency.CustomShopType
import com.shusheng.cobblemarket.currency.customShopErrorText
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import com.shusheng.cobblemarket.util.PersistHelper
import com.shusheng.cobblemarket.util.RequestThrottle
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import kotlin.math.sqrt
import java.util.UUID

// ── 协议上限（客户端伪造也撑不爆包；与数据层上限同值）──

private const val ADMIN_MAX_TEXT = 64
private const val ADMIN_MAX_ID = 128
private const val ADMIN_MAX_SHOPS = 64
private const val ADMIN_MAX_NPCS_PER_SHOP = 16
private const val ADMIN_MAX_NEARBY = 32

/** 管理界面的「附近实体」半径（格）—— 绑定 / 解绑都在这个范围内 */
private const val ADMIN_NEARBY_RADIUS = 16.0

/** 绑定操作服务端复核的距离（与列表同值；防伪造包远程绑） */
private const val ADMIN_BIND_RADIUS = 16.0

/** 管理列表里一个商城 NPC 的显示信息 */
data class CustomShopNpcInfo(
    val uuid: String,
    val typeId: String,
    val customName: String,
    /** 距玩家多少格；-1 = 不在玩家所在世界 / 区块未加载 */
    val distance: Int
)

/** 管理列表里的一家商店 */
data class CustomShopAdminInfo(
    val id: String,
    val name: String,
    val type: String,
    val enabled: Boolean,
    val pokemonCount: Int,
    val itemCount: Int,
    /** 实际绑定的 NPC 总数（[npcs] 最多只带 16 条） */
    val npcCount: Int,
    val npcs: List<CustomShopNpcInfo>
)

/** 管理界面「附近实体」列表的一项 */
data class CustomShopNearbyInfo(
    val uuid: String,
    val typeId: String,
    val customName: String,
    val distance: Int,
    /** 已绑定到哪家店（空 = 未绑定） */
    val boundShopId: String,
    val boundShopName: String
)

/**
 * 商店管理界面的数据快照（S2C）。
 * 商店列表与「附近实体」一次下发：界面打开时只需要一个往返。
 */
data class CustomShopAdminListPayload(
    val shops: List<CustomShopAdminInfo>,
    val nearby: List<CustomShopNearbyInfo>
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<CustomShopAdminListPayload>(CobbleMarket.id("custom_shop_admin_list"))
        val CODEC: PacketCodec<PacketByteBuf, CustomShopAdminListPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.shops.size)
                p.shops.forEach { s ->
                    b.writeString(s.id, ADMIN_MAX_TEXT)
                    b.writeString(s.name, ADMIN_MAX_TEXT)
                    b.writeString(s.type, ADMIN_MAX_TEXT)
                    b.writeBoolean(s.enabled)
                    b.writeInt(s.pokemonCount)
                    b.writeInt(s.itemCount)
                    b.writeInt(s.npcCount)
                    b.writeInt(s.npcs.size)
                    s.npcs.forEach { n ->
                        b.writeString(n.uuid, ADMIN_MAX_TEXT)
                        b.writeString(n.typeId, ADMIN_MAX_ID)
                        b.writeString(n.customName, ADMIN_MAX_TEXT)
                        b.writeInt(n.distance)
                    }
                }
                b.writeInt(p.nearby.size)
                p.nearby.forEach { n ->
                    b.writeString(n.uuid, ADMIN_MAX_TEXT)
                    b.writeString(n.typeId, ADMIN_MAX_ID)
                    b.writeString(n.customName, ADMIN_MAX_TEXT)
                    b.writeInt(n.distance)
                    b.writeString(n.boundShopId, ADMIN_MAX_TEXT)
                    b.writeString(n.boundShopName, ADMIN_MAX_TEXT)
                }
            },
            { b ->
                val shopCount = b.readInt()
                if (shopCount < 0 || shopCount > ADMIN_MAX_SHOPS) {
                    throw IllegalArgumentException("custom shop admin count out of range: $shopCount")
                }
                val shops = List(shopCount) {
                    val id = b.readString(ADMIN_MAX_TEXT)
                    val name = b.readString(ADMIN_MAX_TEXT)
                    val type = b.readString(ADMIN_MAX_TEXT)
                    val enabled = b.readBoolean()
                    val pokemonCount = b.readInt()
                    val itemCount = b.readInt()
                    val npcCount = b.readInt()
                    val npcListCount = b.readInt()
                    if (npcListCount < 0 || npcListCount > ADMIN_MAX_NPCS_PER_SHOP) {
                        throw IllegalArgumentException("custom shop admin npc count out of range: $npcListCount")
                    }
                    val npcs = List(npcListCount) {
                        CustomShopNpcInfo(
                            uuid = b.readString(ADMIN_MAX_TEXT),
                            typeId = b.readString(ADMIN_MAX_ID),
                            customName = b.readString(ADMIN_MAX_TEXT),
                            distance = b.readInt()
                        )
                    }
                    CustomShopAdminInfo(id, name, type, enabled, pokemonCount, itemCount, npcCount, npcs)
                }
                val nearbyCount = b.readInt()
                if (nearbyCount < 0 || nearbyCount > ADMIN_MAX_NEARBY) {
                    throw IllegalArgumentException("custom shop admin nearby count out of range: $nearbyCount")
                }
                val nearby = List(nearbyCount) {
                    CustomShopNearbyInfo(
                        uuid = b.readString(ADMIN_MAX_TEXT),
                        typeId = b.readString(ADMIN_MAX_ID),
                        customName = b.readString(ADMIN_MAX_TEXT),
                        distance = b.readInt(),
                        boundShopId = b.readString(ADMIN_MAX_TEXT),
                        boundShopName = b.readString(ADMIN_MAX_TEXT)
                    )
                }
                CustomShopAdminListPayload(shops, nearby)
            }
        )
    }
}

// ── C2S ──

data class RequestCustomShopAdminPayload(val unused: Boolean = false) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestCustomShopAdminPayload>(CobbleMarket.id("request_custom_shop_admin"))
        val CODEC: PacketCodec<PacketByteBuf, RequestCustomShopAdminPayload> = PacketCodec.of(
            { _, _ -> }, { RequestCustomShopAdminPayload() }
        )
    }
}

data class CreateCustomShopPayload(val id: String, val name: String, val type: String) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<CreateCustomShopPayload>(CobbleMarket.id("create_custom_shop"))
        val CODEC: PacketCodec<PacketByteBuf, CreateCustomShopPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.id, ADMIN_MAX_TEXT)
                b.writeString(p.name, ADMIN_MAX_TEXT)
                b.writeString(p.type, ADMIN_MAX_TEXT)
            },
            { b -> CreateCustomShopPayload(b.readString(ADMIN_MAX_TEXT), b.readString(ADMIN_MAX_TEXT), b.readString(ADMIN_MAX_TEXT)) }
        )
    }
}

data class RenameCustomShopPayload(val id: String, val name: String) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RenameCustomShopPayload>(CobbleMarket.id("rename_custom_shop"))
        val CODEC: PacketCodec<PacketByteBuf, RenameCustomShopPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.id, ADMIN_MAX_TEXT); b.writeString(p.name, ADMIN_MAX_TEXT) },
            { b -> RenameCustomShopPayload(b.readString(ADMIN_MAX_TEXT), b.readString(ADMIN_MAX_TEXT)) }
        )
    }
}

data class SetCustomShopTypePayload(val id: String, val type: String) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<SetCustomShopTypePayload>(CobbleMarket.id("set_custom_shop_type"))
        val CODEC: PacketCodec<PacketByteBuf, SetCustomShopTypePayload> = PacketCodec.of(
            { p, b -> b.writeString(p.id, ADMIN_MAX_TEXT); b.writeString(p.type, ADMIN_MAX_TEXT) },
            { b -> SetCustomShopTypePayload(b.readString(ADMIN_MAX_TEXT), b.readString(ADMIN_MAX_TEXT)) }
        )
    }
}

data class SetCustomShopEnabledPayload(val id: String, val enabled: Boolean) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<SetCustomShopEnabledPayload>(CobbleMarket.id("set_custom_shop_enabled"))
        val CODEC: PacketCodec<PacketByteBuf, SetCustomShopEnabledPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.id, ADMIN_MAX_TEXT); b.writeBoolean(p.enabled) },
            { b -> SetCustomShopEnabledPayload(b.readString(ADMIN_MAX_TEXT), b.readBoolean()) }
        )
    }
}

data class DeleteCustomShopPayload(val id: String) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<DeleteCustomShopPayload>(CobbleMarket.id("delete_custom_shop"))
        val CODEC: PacketCodec<PacketByteBuf, DeleteCustomShopPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.id, ADMIN_MAX_TEXT) },
            { b -> DeleteCustomShopPayload(b.readString(ADMIN_MAX_TEXT)) }
        )
    }
}

data class BindCustomShopNpcPayload(val shopId: String, val npcUuid: String) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<BindCustomShopNpcPayload>(CobbleMarket.id("bind_custom_shop_npc"))
        val CODEC: PacketCodec<PacketByteBuf, BindCustomShopNpcPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.shopId, ADMIN_MAX_TEXT); b.writeString(p.npcUuid, ADMIN_MAX_TEXT) },
            { b -> BindCustomShopNpcPayload(b.readString(ADMIN_MAX_TEXT), b.readString(ADMIN_MAX_TEXT)) }
        )
    }
}

data class UnbindCustomShopNpcPayload(val npcUuid: String) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<UnbindCustomShopNpcPayload>(CobbleMarket.id("unbind_custom_shop_npc"))
        val CODEC: PacketCodec<PacketByteBuf, UnbindCustomShopNpcPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.npcUuid, ADMIN_MAX_TEXT) },
            { b -> UnbindCustomShopNpcPayload(b.readString(ADMIN_MAX_TEXT)) }
        )
    }
}

data class RequestOpenCustomShopPayload(val shopId: String) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestOpenCustomShopPayload>(CobbleMarket.id("request_open_custom_shop"))
        val CODEC: PacketCodec<PacketByteBuf, RequestOpenCustomShopPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.shopId, ADMIN_MAX_TEXT) },
            { b -> RequestOpenCustomShopPayload(b.readString(ADMIN_MAX_TEXT)) }
        )
    }
}

/**
 * **自定义商店管理界面的服务端**（2026-10-21）。
 *
 * 全部操作判 `points.shop`（不认 OP；控制台不是玩家，进不了这个界面）。
 * 管理端的写操作**不要求距离**（与上架 / 补货一致）；只有**绑定 NPC**要复核 16 格，
 * 防伪造包远程把实体绑走。
 */
object CustomShopAdminNetwork {

    fun register() {
        registerS2CType(CustomShopAdminListPayload.ID, CustomShopAdminListPayload.CODEC)

        registerC2S(RequestCustomShopAdminPayload.ID, RequestCustomShopAdminPayload.CODEC) { _, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return@registerC2S
            if (!RequestThrottle.allow(player.uuid, "custom_shop_admin_list", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerC2S
            }
            player.server.execute { sendToPlayer(player, buildList(player)) }
        }

        registerC2S(CreateCustomShopPayload.ID, CreateCustomShopPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return@registerC2S
            if (!RequestThrottle.allowNotify(player, "custom_shop_admin_create", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            player.server.execute {
                val type = parseType(payload.type)
                if (type == null) {
                    sendResult(player, false, customShopErrorText(CustomShopError.INVALID_TYPE, payload.type))
                    return@execute
                }
                val state = CustomShopState.get(player.server)
                val error = state.create(payload.id, payload.name, type)
                if (error != null) {
                    sendResult(player, false, customShopErrorText(error))
                    return@execute
                }
                PersistHelper.requestSave(player.server)
                val shop = state.find(payload.id.trim().lowercase())
                sendResult(
                    player,
                    true,
                    Text.translatable("cobblemarket.shop.created", shop?.id.orEmpty(), shop?.name.orEmpty())
                )
                sendToPlayer(player, buildList(player))
            }
        }

        registerC2S(RenameCustomShopPayload.ID, RenameCustomShopPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return@registerC2S
            if (!RequestThrottle.allowNotify(player, "custom_shop_admin_rename", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            player.server.execute {
                val state = CustomShopState.get(player.server)
                val error = state.rename(payload.id, payload.name)
                if (error != null) {
                    sendResult(player, false, customShopErrorText(error, payload.id))
                    return@execute
                }
                PersistHelper.requestSave(player.server)
                sendResult(
                    player,
                    true,
                    Text.translatable(
                        "cobblemarket.shop.renamed", payload.id,
                        state.find(payload.id)?.name.orEmpty()
                    )
                )
                sendToPlayer(player, buildList(player))
            }
        }

        registerC2S(SetCustomShopTypePayload.ID, SetCustomShopTypePayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return@registerC2S
            if (!RequestThrottle.allowNotify(player, "custom_shop_admin_type", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            player.server.execute {
                val type = parseType(payload.type)
                if (type == null) {
                    sendResult(player, false, customShopErrorText(CustomShopError.INVALID_TYPE, payload.type))
                    return@execute
                }
                val state = CustomShopState.get(player.server)
                val error = state.setType(payload.id, type)
                if (error != null) {
                    sendResult(player, false, customShopErrorText(error, payload.id))
                    return@execute
                }
                PersistHelper.requestSave(player.server)
                sendResult(
                    player,
                    true,
                    Text.translatable(
                        "cobblemarket.shop.type_set", payload.id,
                        com.shusheng.cobblemarket.currency.customShopTypeText(type)
                    )
                )
                sendToPlayer(player, buildList(player))
            }
        }

        registerC2S(SetCustomShopEnabledPayload.ID, SetCustomShopEnabledPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return@registerC2S
            if (!RequestThrottle.allowNotify(player, "custom_shop_admin_enabled", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            player.server.execute {
                if (!CustomShopState.get(player.server).setEnabled(payload.id, payload.enabled)) {
                    sendResult(player, false, customShopErrorText(CustomShopError.NOT_FOUND, payload.id))
                    return@execute
                }
                PersistHelper.requestSave(player.server)
                sendResult(
                    player,
                    true,
                    Text.translatable(
                        if (payload.enabled) "cobblemarket.shop.opened" else "cobblemarket.shop.closed_admin",
                        payload.id
                    )
                )
                sendToPlayer(player, buildList(player))
            }
        }

        registerC2S(DeleteCustomShopPayload.ID, DeleteCustomShopPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return@registerC2S
            if (!RequestThrottle.allowNotify(player, "custom_shop_admin_delete", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            player.server.execute {
                val state = CustomShopState.get(player.server)
                val shop = state.find(payload.id)
                if (shop == null) {
                    sendResult(player, false, customShopErrorText(CustomShopError.NOT_FOUND, payload.id))
                    return@execute
                }
                val name = shop.name
                val entries = shop.entryCount()
                state.delete(payload.id)
                PersistHelper.requestSave(player.server)
                sendResult(
                    player,
                    true,
                    Text.translatable("cobblemarket.shop.deleted", payload.id, name, entries)
                )
                sendToPlayer(player, buildList(player))
            }
        }

        registerC2S(BindCustomShopNpcPayload.ID, BindCustomShopNpcPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return@registerC2S
            if (!RequestThrottle.allowNotify(player, "custom_shop_admin_bind", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            player.server.execute {
                val state = CustomShopState.get(player.server)
                val shop = state.find(payload.shopId)
                if (shop == null) {
                    sendResult(player, false, customShopErrorText(CustomShopError.NOT_FOUND, payload.shopId))
                    return@execute
                }
                val uuid = runCatching { UUID.fromString(payload.npcUuid) }.getOrNull()
                if (uuid == null) {
                    sendResult(player, false, customShopErrorText(CustomShopError.NPC_NOT_FOUND))
                    return@execute
                }
                val entity = player.serverWorld.getEntity(uuid)
                if (entity == null || !entity.isAlive) {
                    sendResult(player, false, customShopErrorText(CustomShopError.NPC_NOT_FOUND))
                    return@execute
                }
                if (entity is ServerPlayerEntity) {
                    sendResult(player, false, Text.translatable("cobblemarket.shop.err.bind_player"))
                    return@execute
                }
                if (entity.squaredDistanceTo(player) > ADMIN_BIND_RADIUS * ADMIN_BIND_RADIUS) {
                    sendResult(player, false, customShopErrorText(CustomShopError.NPC_TOO_FAR))
                    return@execute
                }
                val typeId = Registries.ENTITY_TYPE.getId(entity.type).toString()
                val customName = entity.customName?.string.orEmpty()
                when (val error = state.bind(payload.shopId, uuid, typeId, customName)) {
                    null -> {
                        PersistHelper.requestSave(player.server)
                        sendResult(player, true, Text.translatable("cobblemarket.shop.bound", shop.name, entity.name))
                        sendToPlayer(player, buildList(player))
                    }
                    CustomShopError.ALREADY_BOUND -> {
                        val owner = state.shopForNpc(uuid)?.name.orEmpty()
                        sendResult(
                            player,
                            false,
                            customShopErrorText(CustomShopError.ALREADY_BOUND, entity.name, owner)
                        )
                    }
                    else -> sendResult(player, false, customShopErrorText(error))
                }
            }
        }

        registerC2S(UnbindCustomShopNpcPayload.ID, UnbindCustomShopNpcPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return@registerC2S
            if (!RequestThrottle.allowNotify(player, "custom_shop_admin_unbind", RequestThrottle.WRITE_INTERVAL_MS)) {
                return@registerC2S
            }
            player.server.execute {
                val state = CustomShopState.get(player.server)
                val uuid = runCatching { UUID.fromString(payload.npcUuid) }.getOrNull()
                if (uuid == null) {
                    sendResult(player, false, customShopErrorText(CustomShopError.NPC_NOT_FOUND))
                    return@execute
                }
                val shop = state.shopForNpc(uuid)
                if (shop == null) {
                    sendResult(player, false, Text.translatable("cobblemarket.shop.err.not_bound_generic"))
                    return@execute
                }
                val entity = player.serverWorld.getEntity(uuid)
                val label = entity?.name ?: shop.npcMetaOf(uuid)?.customName?.takeIf { it.isNotEmpty() }
                    ?.let { Text.literal(it) } ?: Text.literal(uuid.toString())
                val shopName = shop.name
                state.unbind(uuid)
                PersistHelper.requestSave(player.server)
                sendResult(player, true, Text.translatable("cobblemarket.shop.unbound", label, shopName))
                sendToPlayer(player, buildList(player))
            }
        }

        registerC2S(RequestOpenCustomShopPayload.ID, RequestOpenCustomShopPayload.CODEC) { payload, player ->
            if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return@registerC2S
            if (!RequestThrottle.allow(player.uuid, "custom_shop_admin_open", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerC2S
            }
            player.server.execute {
                val shop = CustomShopState.get(player.server).find(payload.shopId) ?: return@execute
                // returnToAdmin = true：商品编辑界面的「返回」回到这个管理界面，而不是回游戏
                PointMarketNetwork.openCustomShop(player, shop, returnToAdmin = true)
            }
        }
    }

    // ── 数据组装 ──

    private fun parseType(raw: String): CustomShopType? {
        val id = raw.trim().lowercase()
        return CustomShopType.values().firstOrNull { it.id == id }
    }

    private fun sendResult(player: ServerPlayerEntity, success: Boolean, message: Text) {
        sendToPlayer(player, MarketResultPayload(success, message))
    }

    private fun buildList(player: ServerPlayerEntity): CustomShopAdminListPayload {
        val state = CustomShopState.get(player.server)
        val shops = state.all().map { shop ->
            val npcEntries = shop.npcMetaEntries()
            val npcs = npcEntries.take(ADMIN_MAX_NPCS_PER_SHOP).map { (uuid, meta) ->
                val entity = player.serverWorld.getEntity(uuid)
                val typeId = meta?.typeId?.takeIf { it.isNotEmpty() }
                    ?: entity?.let { Registries.ENTITY_TYPE.getId(it.type).toString() }.orEmpty()
                val customName = meta?.customName?.takeIf { it.isNotEmpty() }
                    ?: entity?.customName?.string.orEmpty()
                val distance = entity?.let { sqrt(it.squaredDistanceTo(player)).toInt() } ?: -1
                CustomShopNpcInfo(uuid.toString(), typeId, customName, distance)
            }
            CustomShopAdminInfo(
                id = shop.id,
                name = shop.name,
                type = shop.type.id,
                enabled = shop.enabled,
                pokemonCount = shop.pokemonCount(),
                itemCount = shop.itemCount(),
                npcCount = shop.npcCount(),
                npcs = npcs
            )
        }

        val nearby = player.serverWorld
            .getOtherEntities(player, player.boundingBox.expand(ADMIN_NEARBY_RADIUS)) { e ->
                e.isAlive && e !is ServerPlayerEntity && e is net.minecraft.entity.LivingEntity
            }
            .sortedBy { it.squaredDistanceTo(player) }
            .take(ADMIN_MAX_NEARBY)
            .map { entity ->
                val bound = state.shopForNpc(entity.uuid)
                CustomShopNearbyInfo(
                    uuid = entity.uuid.toString(),
                    typeId = Registries.ENTITY_TYPE.getId(entity.type).toString(),
                    customName = entity.customName?.string.orEmpty(),
                    distance = sqrt(entity.squaredDistanceTo(player)).toInt(),
                    boundShopId = bound?.id.orEmpty(),
                    boundShopName = bound?.name.orEmpty()
                )
            }

        return CustomShopAdminListPayload(shops, nearby)
    }
}
