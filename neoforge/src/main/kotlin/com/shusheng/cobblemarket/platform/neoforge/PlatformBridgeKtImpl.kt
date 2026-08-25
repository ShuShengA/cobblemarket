// 宿主类名必须恰好是 PlatformBridgeKtImpl（transform 按「桩类名 + Impl」查找）；
// 默认顶层函数宿主类名 = 文件名 + Kt，会多出 Kt 后缀，故用 JvmName 指定
@file:JvmName("PlatformBridgeKtImpl")

package com.shusheng.cobblemarket.platform.neoforge

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.neoforged.bus.api.EventPriority
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.math.BigDecimal
import java.nio.file.Path
import java.util.UUID

/**
 * PlatformBridge 桩方法的 neoforge 实现。
 * 文件名必须为 PlatformBridgeKtImpl.kt（原因见 fabric 同文件头注释）。
 *
 * 注册优先级约定（配合 NeoForgePlatform.markPayloadRegistered 去重）：
 * - C2S / 客户端 S2C（带业务 handler）用 HIGH 先注册；
 * - 服务端 S2C 类型 / 客户端 C2S 空注册用 NORMAL 晚到，发现同 id 已注册则跳过。
 * 专用服务器/专用客户端进程内只有一侧逻辑，去重集合为空侧，注册照常。
 */

// ── 环境 ──

fun configDir(): Path = FMLPaths.CONFIGDIR.get()

fun isModLoaded(modId: String): Boolean = ModList.get().isLoaded(modId)

// ── 网络：C2S 注册 + 服务端发送 ──

fun <T : CustomPayload> registerC2S(
    id: CustomPayload.Id<T>,
    // 与 fabric 实现同理：具体类型参数（不用 in 投影）避开捕获类型检查；
    // 泛型擦除后 JVM 描述符一致，不影响 transform 按 name+desc 转发。
    codec: PacketCodec<RegistryByteBuf, T>,
    handler: (payload: T, player: ServerPlayerEntity) -> Unit,
) {
    // HIGH：单机时先于客户端 C2S 空注册（NORMAL）注册业务 handler，空注册晚到跳过
    NeoForgePlatform.modEventBus().addListener(EventPriority.HIGH, RegisterPayloadHandlersEvent::class.java) { event ->
        event.registrar(PAYLOAD_VERSION).playToServer(id, codec) { payload, context ->
            handler(payload, context.player() as ServerPlayerEntity)
        }
        NeoForgePlatform.markPayloadRegistered(id.id(), DIR_C2S)
    }
}

fun sendToPlayer(player: ServerPlayerEntity, payload: CustomPayload) {
    PacketDistributor.sendToPlayer(player, payload)
}

fun <T : CustomPayload> registerS2CType(
    id: CustomPayload.Id<T>,
    codec: PacketCodec<RegistryByteBuf, T>,
) {
    NeoForgePlatform.modEventBus().addListener(RegisterPayloadHandlersEvent::class.java) { event ->
        // 单机时客户端已先行注册同 id（真 handler，HIGH），这里跳过；专用服务器正常注册
        if (NeoForgePlatform.markPayloadRegistered(id.id(), DIR_S2C)) {
            event.registrar(PAYLOAD_VERSION).playToClient(id, codec) { _, _ -> }
        }
    }
}

// ── 服务端生命周期 / tick / 玩家连接事件 ──

fun onServerStarting(handler: (server: MinecraftServer) -> Unit) {
    NeoForge.EVENT_BUS.addListener(ServerStartingEvent::class.java) { event -> handler(event.server) }
}

fun onServerStarted(handler: (server: MinecraftServer) -> Unit) {
    NeoForge.EVENT_BUS.addListener(ServerStartedEvent::class.java) { event -> handler(event.server) }
}

fun onServerStopped(handler: (server: MinecraftServer) -> Unit) {
    NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { event -> handler(event.server) }
}

fun onServerTickEnd(handler: (server: MinecraftServer) -> Unit) {
    NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { event -> handler(event.server) }
}

fun onPlayerJoin(handler: (player: ServerPlayerEntity) -> Unit) {
    NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { event ->
        handler(event.entity as ServerPlayerEntity)
    }
}

fun onPlayerDisconnect(handler: (player: ServerPlayerEntity) -> Unit) {
    NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event ->
        handler(event.entity as ServerPlayerEntity)
    }
}

// ── 命令注册（三个参数均为原版类型，双端通用） ──

fun registerCommands(
    handler: (
        dispatcher: CommandDispatcher<ServerCommandSource>,
        registryAccess: CommandRegistryAccess,
        environment: CommandManager.RegistrationEnvironment,
    ) -> Unit,
) {
    NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { event ->
        handler(event.dispatcher, event.buildContext, event.commandSelection)
    }
}

// ── Cobblemon Economy（cobeco）──
// cobeco 无 neoforge 版（fabric-only）：恒不可用，货币自动降级（见 CurrencyHandler）

fun cobecoAvailable(): Boolean = false

fun cobecoGetBalance(uuid: UUID, usePco: Boolean): BigDecimal? = null

fun cobecoRemove(uuid: UUID, amount: BigDecimal, usePco: Boolean): Boolean = false

fun cobecoAdd(uuid: UUID, amount: BigDecimal, usePco: Boolean): Boolean = false
