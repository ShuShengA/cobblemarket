package com.shusheng.cobblemarket.platform.neoforge

import net.minecraft.util.Identifier
import net.neoforged.bus.api.IEventBus
import java.util.Collections

/** neoforge 网络 payload 版本号：双端注册须一致（服务端桩与客户端桩共用）。 */
const val PAYLOAD_VERSION = "1"

/** payload 流向标识（去重 key 用） */
const val DIR_C2S = "c2s"
const val DIR_S2C = "s2c"

/**
 * neoforge 平台共享状态。
 *
 * - modBus：@Mod 构造器注入 IEventBus 后存这里。21.1 已移除 FMLJavaModLoadingContext，
 *   且 ModLoadingContext 的 activeContainer 在 mod 类加载后即清空，桩实现无法自行
 *   获取本 mod 的 bus，只能由入口类静态中转。
 * - registeredPayloadKeys：本进程已注册的 payload（RegisterPayloadHandlersEvent 阶段填充），
 *   key = 流向 + "/" + id。neoforge 网络协商要求双端注册集合完全一致
 *   （缺任何非 optional payload 直接断连），因此单机（客户端进程内同时跑服务端与客户端注册）
 *   会双端各注册一遍同 id payload，而 NetworkRegistry 对同 id 重复注册抛异常。
 *   靠此集合按「流向 + id」去重：先到者注册并标记，后到者发现已标记则跳过
 *   （客户端真 handler 注册优先，见 ClientBridgeKtImpl）。按流向区分后，
 *   未来同 id 双向复用的 payload 也能两个方向各自注册一次。
 */
object NeoForgePlatform {
    lateinit var modBus: IEventBus

    private val registeredPayloadKeys: MutableSet<String> =
        Collections.synchronizedSet(HashSet())

    fun modEventBus(): IEventBus = modBus

    /** 标记 payload 已注册；返回 true = 首次标记（应继续注册），false = 已存在（跳过）。 */
    fun markPayloadRegistered(id: Identifier, direction: String): Boolean =
        registeredPayloadKeys.add("$direction/$id")
}
