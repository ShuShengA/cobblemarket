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

/** 注册客户端本地指令（聊天可点击按钮的落地路径；不经过服务端）。
 *  onRun 收到指令参数原文（首个空格后的全部内容）。 */
@ExpectPlatform
fun registerClientCommand(name: String, onRun: (args: String) -> Unit): Unit = throw AssertionError()

/**
 * 让「游戏内操作键」（移动 / 后退 / 左右 / 跳跃 / 潜行 / 冲刺）在**界面打开时**也生效，
 * [active] = false 时还原。取景界面用（拍照时玩家要能边走边取景）。
 *
 * ⚠ **只有 NeoForge 需要**：它把原版这几个键的冲突上下文设成 `IN_GAME`
 *   （`currentScreen == null` 才激活）⇒ 取景界面（Screen）打开时 `KeyBinding.isPressed()`
 *   恒 false，界面里每 tick `KeyBinding.updatePressedStates()` 同步物理按键**也救不回来**
 *   —— 界面层的同步写不进 `isPressed()` 的冲突检查（2026-11-21 用户实测：NeoForge 取景时
 *   视角能转（走 mouseMoved）但不能移动（走 KeyBinding））。这里临时改成 `UNIVERSAL`，关闭时还原。
 * Fabric 的原版键没有「冲突上下文」概念 ⇒ 两端都是空操作 / 只是切上下文。
 */
@ExpectPlatform
fun setMovementKeysActiveInScreens(active: Boolean): Unit = throw AssertionError()
