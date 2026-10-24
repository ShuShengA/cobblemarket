package com.shusheng.cobblemarket.platform

import com.mojang.brigadier.CommandDispatcher
import dev.architectury.injectables.annotations.ExpectPlatform
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.resource.ResourceManager
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier
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

/**
 * 本模组版本号（如 `1.3.0`）—— 「有新版本可用」提示拿它跟官网 `version.json` 比对。
 * 取不到时返回空串：调用方据此静默跳过，绝不误报。
 */
@ExpectPlatform
fun modVersion(): String = throw AssertionError()

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

/** 在线玩家连接 IP 文本（金融防刷同 IP 降权用）；离线/获取失败返回 null。 */
@ExpectPlatform
fun getPlayerIp(player: ServerPlayerEntity): String? = throw AssertionError()

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

/** 物品注册（双平台）：传入 (id, 物品工厂) 列表，在对应平台的物品注册时机逐一注册 */
@ExpectPlatform
fun registerItems(items: List<Pair<net.minecraft.util.Identifier, () -> net.minecraft.item.Item>>): Unit = throw AssertionError()

@ExpectPlatform
fun onPlayerDisconnect(handler: (player: ServerPlayerEntity) -> Unit): Unit = throw AssertionError()

/**
 * **服务端「玩家右键实体」回调** —— 返回 `true` = 这次交互已被我们处理（取消实体的默认交互）。
 *
 * 用途：自定义商店绑定 NPC 后，右键 NPC 打开本模组的商城界面，**不让 NPC 自己再弹对话 / 交易**。
 *
 * ⚠ 回调在**双端**都会收到（Fabric 的 UseEntityCallback 与 NeoForge 的 PlayerInteractEvent 都是），
 *   handler 里必须自己判 `player is ServerPlayerEntity` —— 这里不做过滤，
 *   因为平台侧的过滤方式不同（NeoForge 走事件、Fabric 走回调值）。
 * ⚠ 只处理**主手**：副手右键同一次交互会各来一遍，不过滤会打开两次界面。
 * ⚠ 实现侧都只拦「普通右键（interact）」；Fabric 没有 `interactAt` 的标准回调
 *   （NeoForge 两个事件都接）—— 普通 NPC 模组走的是 interact，够用。
 */
@ExpectPlatform
fun onUseEntity(handler: (player: ServerPlayerEntity, entity: net.minecraft.entity.Entity) -> Boolean): Unit =
    throw AssertionError()

// ── 服务端数据包重载监听 ──

/**
 * 注册一个**服务端数据包（datapack）重载监听**：每次世界加载 / `/reload` 后回调一次，
 * 回调参数是该次重载的 [ResourceManager] 与**服务端实例**。
 *
 * ⚠ 为什么要把 `server` 一起给出去：读规则的落点要 `server.resourceManager` 扫数据包
 *   （`RewardRules.load` 用 `findResources` 发现文件 —— 文件夹与 zip 都认）。
 * ⚠ 2026-10-13 曾误判「`findResources` 对世界数据包失效」—— 真因是规则路径多套了一层
 *   （见 `RewardRules.load` 注释）；2026-10-23 起仍用它，并因此**支持 zip 数据包**。
 *
 * ⚠ 回调**在重载线程上同步执行**（fabric `SynchronousResourceReloader` / neoforge
 *   `AddReloadListenerEvent` 都保证）⇒ 回调里只做"读盘 + 解析 + 换掉内存里的规则表"，
 *   别在里头碰世界状态、别发网络包。
 * ⚠ 同一个 [id] 只该注册一次（重复注册会回调两遍）。本模组只有 RP 奖励规则用得到它。
 * ⚠ 实现侧**拿不到 server 时会跳过回调**（服务器还没起来，这时候本来也没世界数据包可读）。
 */
@ExpectPlatform
fun registerServerDataReload(id: Identifier, handler: (ResourceManager, MinecraftServer) -> Unit): Unit = throw AssertionError()

// ── 命令注册（三个参数均为原版类型，双端通用） ──

@ExpectPlatform
fun registerCommands(
    handler: (
        dispatcher: CommandDispatcher<ServerCommandSource>,
        registryAccess: CommandRegistryAccess,
        environment: CommandManager.RegistrationEnvironment,
    ) -> Unit,
): Unit = throw AssertionError()

// ── Cobblemon Economy货币桥接 ──
// 仅 fabric 存在 Cobblemon Economy；neoforge 端恒不可用（Cobblemon EconomyAvailable=false），货币自动降级。

/** Cobblemon Economy 是否可用（模组存在）；配置开关在 CurrencyHandler。neoforge 恒 false。 */
@ExpectPlatform
fun cobecoAvailable(): Boolean = throw AssertionError()

/** 查 Cobblemon Economy 余额；不可用/异常返回 null。usePco=true 走 PokeCoins，否则 PokeDollars。 */
@ExpectPlatform
fun cobecoGetBalance(uuid: UUID, usePco: Boolean): BigDecimal? = throw AssertionError()

/** 扣款；不可用返回 false。 */
@ExpectPlatform
fun cobecoRemove(uuid: UUID, amount: BigDecimal, usePco: Boolean): Boolean = throw AssertionError()

/** 入账；不可用返回 false。 */
@ExpectPlatform
fun cobecoAdd(uuid: UUID, amount: BigDecimal, usePco: Boolean): Boolean = throw AssertionError()

// ── Impactor 直连（双平台）：不依赖 Cobblemon Economy，直接走 Impactor 的 EconomyService API。
// 与 Cobblemon Economy 桥接互斥使用（Cobblemon Economy 在场时优先 Cobblemon Economy，见 CurrencyHandler 优先级）。

/** Impactor 是否可用（模组存在）；配置开关在 CurrencyHandler */
@ExpectPlatform
fun impactorAvailable(): Boolean = throw AssertionError()

/** 查 Impactor 主货币余额；不可用/异常返回 null */
@ExpectPlatform
fun impactorGetBalance(uuid: UUID): BigDecimal? = throw AssertionError()

/** 扣款；不可用/余额不足返回 false */
@ExpectPlatform
fun impactorRemove(uuid: UUID, amount: BigDecimal): Boolean = throw AssertionError()

/** 入账；不可用返回 false */
@ExpectPlatform
fun impactorAdd(uuid: UUID, amount: BigDecimal): Boolean = throw AssertionError()
