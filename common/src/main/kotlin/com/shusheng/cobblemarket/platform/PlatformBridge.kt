package com.shusheng.cobblemarket.platform

import com.mojang.brigadier.CommandDispatcher
import dev.architectury.injectables.annotations.ExpectPlatform
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
 * 平台桥接（服务端 + 通用部分）：common 声明 @ExpectPlatform 桩（方法体恒 throw），
 * 各平台模块在 `<本包>.<platform>` 子包提供 `PlatformBridgeKtImpl` 顶层函数实现，
 * architectury transform 会把本类的桩方法替换为转发到对应平台实现。
 *
 * fabric 实现：fabric/src/main/kotlin/com/shusheng/cobblemarket/platform/fabric/PlatformBridgeKtImpl.kt
 * neoforge 实现：neoforge/src/main/kotlin/com/shusheng/cobblemarket/platform/neoforge/PlatformBridgeKtImpl.kt
 *
 * 注意：实现文件名必须为 `PlatformBridgeKtImpl.kt`（顶层函数宿主类 = 文件名 + Kt，
 * transform 按「本类名 + Impl」查找实现类）。
 * 客户端专属的桥接见 client 源集的 ClientBridge.kt（实现类 ClientBridgeKtImpl）。
 */

// ── 环境 ──

@ExpectPlatform
fun configDir(): Path = throw AssertionError()

@ExpectPlatform
fun isModLoaded(modId: String): Boolean = throw AssertionError()

// ── 网络：C2S 注册 + 服务端发送 ──

@ExpectPlatform
fun <T : CustomPayload> registerC2S(
    id: CustomPayload.Id<out T>,
    codec: PacketCodec<in PacketByteBuf, T>,
    handler: (payload: T, player: ServerPlayerEntity) -> Unit,
): Unit = throw AssertionError()

@ExpectPlatform
fun sendToPlayer(player: ServerPlayerEntity, payload: CustomPayload): Unit = throw AssertionError()

/** S2C payload 类型注册（服务端发送侧需要；客户端接收侧走 registerS2C）。 */
@ExpectPlatform
fun <T : CustomPayload> registerS2CType(
    id: CustomPayload.Id<out T>,
    codec: PacketCodec<in PacketByteBuf, T>,
): Unit = throw AssertionError()

// ── 服务端生命周期 / tick / 玩家连接事件 ──

@ExpectPlatform
fun onServerStarting(handler: (server: MinecraftServer) -> Unit): Unit = throw AssertionError()

@ExpectPlatform
fun onServerStarted(handler: (server: MinecraftServer) -> Unit): Unit = throw AssertionError()

@ExpectPlatform
fun onServerStopped(handler: (server: MinecraftServer) -> Unit): Unit = throw AssertionError()

@ExpectPlatform
fun onServerTickEnd(handler: (server: MinecraftServer) -> Unit): Unit = throw AssertionError()

@ExpectPlatform
fun onPlayerJoin(handler: (player: ServerPlayerEntity) -> Unit): Unit = throw AssertionError()

@ExpectPlatform
fun onPlayerDisconnect(handler: (player: ServerPlayerEntity) -> Unit): Unit = throw AssertionError()

// ── 命令注册（三个参数均为原版类型，双端通用） ──

@ExpectPlatform
fun registerCommands(
    handler: (
        dispatcher: CommandDispatcher<ServerCommandSource>,
        registryAccess: CommandRegistryAccess,
        environment: CommandManager.RegistrationEnvironment,
    ) -> Unit,
): Unit = throw AssertionError()

// ── Cobblemon Economy（cobeco）货币桥接 ──
// 仅 fabric 存在 cobeco；neoforge 端恒不可用（cobecoAvailable=false），货币自动降级。

/** cobeco 是否可用（模组存在）；配置开关在 CurrencyHandler。neoforge 恒 false。 */
@ExpectPlatform
fun cobecoAvailable(): Boolean = throw AssertionError()

/** 查 cobeco 余额；不可用/异常返回 null。usePco=true 走 PokeCoins，否则 PokeDollars。 */
@ExpectPlatform
fun cobecoGetBalance(uuid: UUID, usePco: Boolean): BigDecimal? = throw AssertionError()

/** 扣款；不可用返回 false。 */
@ExpectPlatform
fun cobecoRemove(uuid: UUID, amount: BigDecimal, usePco: Boolean): Boolean = throw AssertionError()

/** 入账；不可用返回 false。 */
@ExpectPlatform
fun cobecoAdd(uuid: UUID, amount: BigDecimal, usePco: Boolean): Boolean = throw AssertionError()
