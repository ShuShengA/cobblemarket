// 宿主类名必须恰好是 ClientBridgeKtImpl（transform 按「桩类名 + Impl」查找）；
// 默认顶层函数宿主类名 = 文件名 + Kt，会多出 Kt 后缀，故用 JvmName 指定
@file:JvmName("ClientBridgeKtImpl")

package com.shusheng.cobblemarket.platform.fabric

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/**
 * ClientBridge 桩方法的 fabric 实现。
 * 文件名必须为 ClientBridgeKtImpl.kt（原因见 PlatformBridgeKtImpl.kt 头注释）。
 */

// ── 网络 ──

fun <T : CustomPayload> registerS2C(
    id: CustomPayload.Id<T>,
    // 投影与 common 桩（PacketByteBuf）不同：fabric 注册 API 要求 ? super RegistryByteBuf，
    // 具体类型参数（不用 in 投影）避开捕获类型检查；泛型擦除后 JVM 描述符一致，不影响 transform 按 name+desc 转发。
    // 注意：S2C 类型注册只在服务端（registerS2CType），客户端只做接收注册，
    // 两端都注册会抛 "Packet type ... is already registered"
    codec: PacketCodec<net.minecraft.network.RegistryByteBuf, T>,
    handler: (payload: T) -> Unit,
) {
    ClientPlayNetworking.registerGlobalReceiver(id) { payload, _ ->
        handler(payload)
    }
}

fun sendToServer(payload: CustomPayload) {
    ClientPlayNetworking.send(payload)
}

// ── tick / 按键 / HUD ──

fun onClientTick(handler: (client: MinecraftClient) -> Unit) {
    ClientTickEvents.END_CLIENT_TICK.register { client -> handler(client) }
}

fun registerKeyBinding(binding: KeyBinding): KeyBinding =
    KeyBindingHelper.registerKeyBinding(binding)

fun registerHudRender(handler: (context: DrawContext) -> Unit) {
    HudRenderCallback.EVENT.register { context, _ -> handler(context) }
}
