package com.shusheng.cobblemarket.platform

import dev.architectury.injectables.annotations.ExpectPlatform
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/**
 * 平台桥接（客户端部分）：common 声明 @ExpectPlatform 桩，平台模块在
 * `<本包>.<platform>` 子包提供 `ClientBridgeKtImpl` 顶层函数实现（见 PlatformBridge.kt 头注释）。
 *
 * fabric 实现：fabric/src/client/kotlin/com/shusheng/cobblemarket/platform/fabric/ClientBridgeKtImpl.kt
 * neoforge 实现：neoforge/src/client/kotlin/com/shusheng/cobblemarket/platform/neoforge/ClientBridgeKtImpl.kt
 */

// ── 网络：S2C 注册 + 客户端发送 ──

@ExpectPlatform
fun <T : CustomPayload> registerS2C(
    id: CustomPayload.Id<out T>,
    codec: PacketCodec<in PacketByteBuf, T>,
    handler: (payload: T) -> Unit,
): Unit = throw AssertionError()

@ExpectPlatform
fun sendToServer(payload: CustomPayload): Unit = throw AssertionError()

// ── 客户端 tick / 按键 / HUD ──

@ExpectPlatform
fun onClientTick(handler: (client: MinecraftClient) -> Unit): Unit = throw AssertionError()

@ExpectPlatform
fun registerKeyBinding(binding: KeyBinding): KeyBinding = throw AssertionError()

@ExpectPlatform
fun registerHudRender(handler: (context: DrawContext, delta: Float) -> Unit): Unit = throw AssertionError()
