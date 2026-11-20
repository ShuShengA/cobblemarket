// 宿主类名必须恰好是 ClientBridgeKtImpl（transform 按「桩类名 + Impl」查找）；
// 默认顶层函数宿主类名 = 文件名 + Kt，会多出 Kt 后缀，故用 JvmName 指定
@file:JvmName("ClientBridgeKtImpl")

package com.shusheng.cobblemarket.platform.neoforge

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.LayeredDrawer
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.render.RenderTickCounter
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.command.CommandManager
import net.minecraft.util.Identifier
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.settings.IKeyConflictContext
import net.neoforged.neoforge.client.settings.KeyConflictContext
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

/**
 * ClientBridge 桩方法的 neoforge 实现（neoforge 不支持 split 源集，本文件在 main 源集；
 * 只有客户端入口调用其中的函数，专用服务器不会执行）。
 * 文件名必须为 ClientBridgeKtImpl.kt（原因见 PlatformBridgeKtImpl.kt 头注释）。
 */

// ── 网络：S2C 注册 + 客户端发送 ──

fun <T : CustomPayload> registerS2C(
    id: CustomPayload.Id<T>,
    // 与 fabric 实现同理：具体类型参数（不用 in 投影）避开捕获类型检查
    codec: PacketCodec<RegistryByteBuf, T>,
    handler: (payload: T) -> Unit,
) {
    // HIGH：客户端业务 handler 必须优先注册——单机时服务端 S2C 类型空注册（NORMAL）晚到，
    // 发现同 id 已注册则跳过（见 PlatformBridgeKtImpl.registerS2CType）
    NeoForgePlatform.modEventBus().addListener(EventPriority.HIGH, RegisterPayloadHandlersEvent::class.java) { event ->
        event.registrar(PAYLOAD_VERSION).playToClient(id, codec) { payload, _ -> handler(payload) }
        NeoForgePlatform.markPayloadRegistered(id.id(), DIR_S2C)
    }
}

fun sendToServer(payload: CustomPayload) {
    PacketDistributor.sendToServer(payload)
}

// ── 客户端 tick / 按键 / HUD ──

fun onClientTick(handler: (client: MinecraftClient) -> Unit) {
    NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ ->
        handler(MinecraftClient.getInstance())
    }
}

fun registerKeyBinding(binding: KeyBinding): KeyBinding {
    NeoForgePlatform.modEventBus().addListener(RegisterKeyMappingsEvent::class.java) { event ->
        // 与 RegisterGuiLayersEvent 同族注册类事件：启动流程可能重复触发，幂等处理防重复注册
        try {
            event.register(binding)
        } catch (_: IllegalArgumentException) {
            // 按键已注册：忽略
        }
    }
    return binding
}

fun registerHudRender(handler: (context: DrawContext, delta: Float) -> Unit) {
    // RenderGuiEvent.Post（EVENT_BUS）：每帧 HUD 渲染后触发，含无界面时的游戏画面——
    // RegisterGuiLayersEvent 的层只在有 Screen 时渲染，无界面时余额 HUD 会消失
    NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post::class.java) { event ->
        handler(event.guiGraphics, event.partialTick.getTickDelta(true))
    }
}

// ── 取景 / 拍照期间的移动键 ──

/**
 * 被临时改成 UNIVERSAL 的键 → 原冲突上下文（关闭取景时逐个还原）。
 * 原值一律是 [KeyConflictContext.IN_GAME]（NeoForge `GameOptions.setForgeKeybindProperties`
 * 给原版键设的），这里仍记原值而不是硬写 IN_GAME —— 以后 NeoForge 改动默认值时不会跟着坏。
 */
private val movementKeyContextBackup = mutableMapOf<KeyBinding, IKeyConflictContext>()

/**
 * NeoForge 把移动 / 跳跃 / 潜行 / 冲刺这几个原版键的冲突上下文设为 `IN_GAME`：
 * `KeyBinding.isPressed()` = `pressed && 上下文激活`，而 `IN_GAME` 只在
 * `currentScreen == null` 时激活 ⇒ **任何界面打开时这些键恒为"未按下"**，
 * 界面里每 tick `KeyBinding.updatePressedStates()` 同步物理按键也没用
 * （2026-11-21 用户实测：NeoForge 取景界面视角能转、WASD 不能移动）。
 * 取景期间把它们的上下文临时改成 `UNIVERSAL`（恒激活），关闭时还原。
 *
 * ⚠ 只动这 7 个键：`attackKey` / `chatKey` / `commandKey` 等同样被设成 IN_GAME，
 *   但取景时它们本来就不该响应（左键是拍照、不聊天、不切视角）。
 */
fun setMovementKeysActiveInScreens(active: Boolean) {
    val options = MinecraftClient.getInstance().options
    val keys = listOf(
        options.forwardKey,
        options.backKey,
        options.leftKey,
        options.rightKey,
        options.jumpKey,
        options.sneakKey,
        options.sprintKey,
    )
    if (active) {
        keys.forEach { key ->
            movementKeyContextBackup.putIfAbsent(key, key.keyConflictContext)
            key.setKeyConflictContext(KeyConflictContext.UNIVERSAL)
        }
    } else {
        movementKeyContextBackup.forEach { (key, context) -> key.setKeyConflictContext(context) }
        movementKeyContextBackup.clear()
    }
}

fun registerClientCommand(name: String, onRun: (args: String) -> Unit) {
    NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent::class.java) { event ->
        event.dispatcher.register(
            CommandManager.literal(name)
                .requires { true } // 客户端本地指令：任何玩家点击都可用（原版 literal 默认 OP 权限）
                .then(
                    CommandManager.argument("args", StringArgumentType.greedyString())
                        .executes { ctx ->
                            onRun(StringArgumentType.getString(ctx, "args"))
                            1
                        }
                )
        )
    }
}
