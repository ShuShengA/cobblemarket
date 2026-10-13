// 宿主类名必须恰好是 PlatformBridgeKtImpl（transform 按「桩类名 + Impl」查找）；
// 默认顶层函数宿主类名 = 文件名 + Kt，会多出 Kt 后缀，故用 JvmName 指定
@file:JvmName("PlatformBridgeKtImpl")

package com.shusheng.cobblemarket.platform.fabric

import com.cobblemon.economy.fabric.CobblemonEconomy
import com.mojang.brigadier.CommandDispatcher
import net.impactdev.impactor.api.economy.EconomyService
import java.util.concurrent.TimeUnit
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import java.math.BigDecimal
import java.nio.file.Path
import java.util.UUID

/**
 * PlatformBridge 桩方法的 fabric 实现。
 * 文件名必须为 PlatformBridgeKtImpl.kt：顶层函数宿主类 = 文件名 + Kt，
 * architectury transform 按「桩类名 + Impl」查找本实现类。
 */

// ── 环境 ──

fun configDir(): Path = FabricLoader.getInstance().configDir

fun getPlayerIp(player: ServerPlayerEntity): String? =
    player.ip.takeIf { it.isNotEmpty() }

fun isModLoaded(modId: String): Boolean = FabricLoader.getInstance().isModLoaded(modId)

fun modVersion(): String =
    FabricLoader.getInstance().getModContainer("cobblemarket")
        .map { it.metadata.version.friendlyString }
        .orElse("")

// ── 网络 ──

fun <T : CustomPayload> registerC2S(
    id: CustomPayload.Id<T>,
    // 投影与 common 桩（PacketByteBuf）不同：fabric 注册 API 要求 ? super RegistryByteBuf，
    // 具体类型参数（不用 in 投影）避开捕获类型检查；泛型擦除后 JVM 描述符一致，不影响 transform 按 name+desc 转发。
    codec: PacketCodec<net.minecraft.network.RegistryByteBuf, T>,
    handler: (payload: T, player: ServerPlayerEntity) -> Unit,
) {
    PayloadTypeRegistry.playC2S().register(id, codec)
    ServerPlayNetworking.registerGlobalReceiver(id) { payload, context ->
        handler(payload, context.player())
    }
}

fun sendToPlayer(player: ServerPlayerEntity, payload: CustomPayload) {
    ServerPlayNetworking.send(player, payload)
}

fun <T : CustomPayload> registerS2CType(
    id: CustomPayload.Id<T>,
    codec: PacketCodec<net.minecraft.network.RegistryByteBuf, T>,
) {
    PayloadTypeRegistry.playS2C().register(id, codec)
}

// ── 服务端生命周期 / tick / 连接事件 ──

fun onServerStarting(handler: (server: MinecraftServer) -> Unit) {
    ServerLifecycleEvents.SERVER_STARTING.register { server -> handler(server) }
}

fun onServerStarted(handler: (server: MinecraftServer) -> Unit) {
    ServerLifecycleEvents.SERVER_STARTED.register { server -> handler(server) }
}

fun onServerStopped(handler: (server: MinecraftServer) -> Unit) {
    ServerLifecycleEvents.SERVER_STOPPED.register { server -> handler(server) }
}

fun onServerTickEnd(handler: (server: MinecraftServer) -> Unit) {
    ServerTickEvents.END_SERVER_TICK.register { server -> handler(server) }
}

fun onPlayerJoin(handler: (player: ServerPlayerEntity) -> Unit) {
    ServerPlayConnectionEvents.JOIN.register { networkHandler, _, _ ->
        handler(networkHandler.player)
    }
}

fun onPlayerDisconnect(handler: (player: ServerPlayerEntity) -> Unit) {
    ServerPlayConnectionEvents.DISCONNECT.register { networkHandler, _ ->
        handler(networkHandler.player)
    }
}

// ── 服务端数据包重载监听 ──

fun registerServerDataReload(
    id: net.minecraft.util.Identifier,
    handler: (net.minecraft.resource.ResourceManager, MinecraftServer) -> Unit,
) {
    // ⚠ **先把"当前服务端"记下来**：重载回调只给 `ResourceManager`，而读规则要
    //   `server.getSavePath(...)`（见 common 侧那段的说明）⇒ 这里存一份，回调时一起传出去。
    //   ⚠ 必须在 `SERVER_STARTING` 就记 —— 世界重载可能早于 `SERVER_STARTED`。
    ServerLifecycleEvents.SERVER_STARTING.register { s -> currentServer = s }
    ServerLifecycleEvents.SERVER_STOPPED.register { currentServer = null }

    // fabric-resource-loader-v0 的 `IdentifiableResourceReloadListener` 自带唯一 id（`getFabricId`），
    // 框架按它去重 ⇒ id 本身就是防重复注册的闸门（id 必须落在 cobblemarket 命名空间下）。
    //
    // ⚠ 用 `SynchronousResourceReloader`（**接口**，不是要等 prepare/apply 两个 future 的那种）：
    //   它把全部活儿放在 apply 阶段跑完 ⇒ 回调返回时规则表**已经是新的**，后面的事件直接看得见。
    val listener = object : net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener,
        net.minecraft.resource.SynchronousResourceReloader {
        override fun getFabricId(): net.minecraft.util.Identifier = id
        override fun reload(manager: net.minecraft.resource.ResourceManager) {
            // 服务端还没起来（比如客户端资源重载）⇒ 没有世界数据包可读，跳过
            val server = currentServer ?: return
            handler(manager, server)
        }
    }
    net.fabricmc.fabric.api.resource.ResourceManagerHelper
        .get(net.minecraft.resource.ResourceType.SERVER_DATA)
        .registerReloadListener(listener)
}

/** 当前服务端（fabric）：重载回调只给 `ResourceManager`，读规则要 `server` ⇒ 这里存一份 */
private var currentServer: MinecraftServer? = null

// ── 命令 ──

fun registerCommands(
    handler: (
        dispatcher: CommandDispatcher<ServerCommandSource>,
        registryAccess: CommandRegistryAccess,
        environment: CommandManager.RegistrationEnvironment,
    ) -> Unit,
) {
    CommandRegistrationCallback.EVENT.register { dispatcher, registryAccess, environment ->
        handler(dispatcher, registryAccess, environment)
    }
}

// ── Cobblemon Economy──

fun cobecoAvailable(): Boolean = isModLoaded("cobblemon-economy")

fun cobecoGetBalance(uuid: UUID, usePco: Boolean): BigDecimal? = try {
    val eco = CobblemonEconomy.getEconomyManager() ?: return null
    if (usePco) eco.getPco(uuid) else eco.getBalance(uuid)
} catch (_: Throwable) {
    null
}

fun cobecoRemove(uuid: UUID, amount: BigDecimal, usePco: Boolean): Boolean = try {
    val eco = CobblemonEconomy.getEconomyManager() ?: return false
    if (usePco) eco.subtractPco(uuid, amount) else eco.subtractBalance(uuid, amount)
} catch (_: Throwable) {
    false
}

fun cobecoAdd(uuid: UUID, amount: BigDecimal, usePco: Boolean): Boolean = try {
    val eco = CobblemonEconomy.getEconomyManager() ?: return false
    if (usePco) eco.addPco(uuid, amount) else eco.addBalance(uuid, amount)
    true
} catch (_: Throwable) {
    false
}

// ── Impactor 直连 ──

fun impactorAvailable(): Boolean = isModLoaded("impactor")

fun impactorGetBalance(uuid: UUID): BigDecimal? = try {
    val service = EconomyService.instance()
    service.account(uuid).get(2, TimeUnit.SECONDS).balance()
} catch (_: Throwable) {
    null
}

fun impactorRemove(uuid: UUID, amount: BigDecimal): Boolean = try {
    val service = EconomyService.instance()
    service.account(uuid).get(2, TimeUnit.SECONDS).withdraw(amount).successful()
} catch (_: Throwable) {
    false
}

fun impactorAdd(uuid: UUID, amount: BigDecimal): Boolean = try {
    val service = EconomyService.instance()
    service.account(uuid).get(2, TimeUnit.SECONDS).deposit(amount).successful()
} catch (_: Throwable) {
    false
}

fun registerItems(items: List<Pair<net.minecraft.util.Identifier, () -> net.minecraft.item.Item>>) {
    // fabric 物品注册：common 初始化阶段直接执行（此时 Registry 可写）
    items.forEach { (id, factory) ->
        net.minecraft.registry.Registry.register(net.minecraft.registry.Registries.ITEM, id, factory())
    }
}
