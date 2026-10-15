package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.network.RequestIdCardBackgroundPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream

/**
 * **市场 ID 卡背景图的客户端动态纹理**。
 *
 * 与券图那一套（多张、按文件名缓存的表）的差别：背景**同一时刻只有一张**，所以这里存单张。
 *
 * 一轮交互：打开 ID 卡界面 → [requestRefresh] 把手里那版的版本号报上去 →
 * 服务端比对后回 [accept]（带新图，或只说"没变"）。
 *
 * ⚠ 注册/销毁都必须在**客户端主线程**（调用方在 `client.execute {}` 里调）。
 */
object IdCardBackgroundTexture {

    /** 已就绪的背景：绘制用的纹理 id + 原始像素尺寸（界面按它算等比缩放） */
    class Entry(val id: Identifier, val width: Int, val height: Int)

    private var entry: Entry? = null

    /**
     * 服务端最近一次告知的版本（空 = 没配图 / 还没问过）。
     * 请求时回传它 ⇒ 服务端**没变就不重传图**；也是 [accept] 里区分"没变"与"读不出来"的判据。
     */
    private var knownVersion: String = ""

    private var nextId = 0

    /** 取已就绪的背景；null = 没配 / 还没到货 / 读不出来（调用方回退到内置背景） */
    fun get(): Entry? = entry

    /**
     * **打开 ID 卡界面时调一次**：把手里那版的版本号报给服务端，由它决定回不回图。
     *
     * 每次打开都问（一个几十字节的往返）—— 换来的是服主换图后**无需等玩家重连**，
     * 下次打开界面就是新图（就地覆盖同名文件也算）。
     */
    fun requestRefresh() {
        sendToServer(RequestIdCardBackgroundPayload(knownVersion))
    }

    /**
     * 收到服务端的回应：解出一张动态纹理，或清掉手里那张。
     *
     * ⚠ **先比版本**：版本与手里那版相同 + 没带字节 = 服务端说"还用手里这张" ⇒ 直接返回。
     * 漏了这一步会把"没变"误判成"读不出来"、把好好的图退回内置背景（见 payload 的注释表）。
     *
     * 版本变了 + 空字节 = 没配 / 读不出来 ⇒ 回退内置背景。
     * 任何失败都只记日志、不抛：一张图取不到不该让整个界面打不开。
     */
    fun accept(version: String, bytes: ByteArray) {
        if (version == knownVersion && bytes.isEmpty()) return

        // 换版本（含"改成没配图" / 换服）：先释放旧纹理，旧图不该再留着
        entry?.let { runCatching { MinecraftClient.getInstance().textureManager.destroyTexture(it.id) } }
        entry = null
        knownVersion = version

        if (bytes.isEmpty()) return

        val image = try {
            NativeImage.read(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.warn(
                "Failed to decode ID card background ({}): {}", version, e.message
            )
            return
        }
        // 纹理尺寸上限：即便服务端已限 512KB，解压后的像素仍可能很大（PNG 压缩率高）
        if (image.width <= 0 || image.height <= 0 || image.width > MAX_SIDE || image.height > MAX_SIDE) {
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.warn(
                "ID card background ({}) has an unreasonable size ({}x{}); ignored",
                version, image.width, image.height
            )
            image.close()
            return
        }

        // 纹理 id 用**自增序号**而不是文件名：文件名是服主起的，可能含空格/中文/非法字符
        val id = Identifier.of("cobblemarket", "dynamic/id_card_bg_${nextId++}")
        try {
            MinecraftClient.getInstance().textureManager.registerTexture(id, NativeImageBackedTexture(image))
        } catch (e: Exception) {
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.warn(
                "Failed to register ID card background texture ({}): {}", version, e.message
            )
            image.close()
            return
        }
        entry = Entry(id, image.width, image.height)
    }

    /** 断开连接 / 换服时释放（纹理归 MC 管，这里只清自己的状态并让它销毁） */
    fun reset() {
        entry?.let { runCatching { MinecraftClient.getInstance().textureManager.destroyTexture(it.id) } }
        entry = null
        knownVersion = ""
        nextId = 0
    }

    /** 单边上限 2048（与服务端 `ConfigImage.MAX_SIDE` 一致） */
    private const val MAX_SIDE = 2048
}
