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
