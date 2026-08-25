// 宿主类名必须恰好是 ClientBridgeKtImpl（transform 按「桩类名 + Impl」查找）；
// 默认顶层函数宿主类名 = 文件名 + Kt，会多出 Kt 后缀，故用 JvmName 指定
@file:JvmName("ClientBridgeKtImpl")

package com.shusheng.cobblemarket.platform.neoforge

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.LayeredDrawer
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.client.event.ClientTickEvent
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
        event.register(binding)
    }
    return binding
}

fun registerHudRender(handler: (context: DrawContext, tickCounter: RenderTickCounter) -> Unit) {
    NeoForgePlatform.modEventBus().addListener(RegisterGuiLayersEvent::class.java) { event ->
        // fabric HudRenderCallback 的对应物：最顶层 GUI 层（无界面时也会渲染，语义一致）
        event.registerAboveAll(Identifier.of("cobblemarket", "hud"), LayeredDrawer.Layer { context, tickCounter ->
            handler(context, tickCounter)
        })
    }
}
