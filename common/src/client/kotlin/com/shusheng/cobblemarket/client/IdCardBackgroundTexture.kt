package com.shusheng.cobblemarket.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream

/**
 * **市场 ID 卡背景图的客户端动态纹理**。
 *
 * 与券图那一套（多张、按文件名缓存的表）的差别：背景**同一时刻只有一张**，
 * 所以这里存单张 —— 但**仍然记住它对应哪个文件名**，服主换图后能判断要不要重新请求。
 *
 * ⚠ 注册/销毁都必须在**客户端主线程**（调用方在 `client.execute {}` 里调）。
 */
object IdCardBackgroundTexture {

    /** 已就绪的背景：绘制用的纹理 id + 原始像素尺寸（界面按它算等比缩放） */
    class Entry(val id: Identifier, val width: Int, val height: Int, val fileName: String)

    private var entry: Entry? = null

    /**
     * 服务端明确回了「读不出来」（空包 / 解码失败）的文件名。
     * 记下来是为了**别反复请求**（每次打开界面都重发一个注定失败的包）；界面据此回退到内置背景。
     */
    private var failedName: String? = null

    private var nextId = 0

    /** 取已就绪的背景；null = 还没到货 / 读不出来（调用方回退到内置背景） */
    fun get(fileName: String): Entry? = entry?.takeIf { it.fileName == fileName }

    /** 这个文件名是不是已经确认取不到了（别再请求） */
    fun isFailed(fileName: String): Boolean = fileName == failedName

    /**
     * 重进界面时给失败过的文件名**一次重试机会**：服主可能刚把图补上，
     * 而客户端除非重连否则不会清这个记录（不清的话玩家得重启游戏才看得到新图）。
     */
    fun retryFailed() {
        failedName = null
    }

    /**
     * 收到服务端下发的字节：解出一张动态纹理。
     *
     * **空数组 = 服务端读不出来**（文件不存在 / 不是 PNG / 太大）—— 客户端据此回退到内置背景。
     * 任何失败都只记进 [failedName]，不抛：一张图取不到不该让整个界面打不开。
     */
    fun accept(fileName: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            failedName = fileName
            return
        }
        val image = try {
            NativeImage.read(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.warn(
                "Failed to decode ID card background {}: {}", fileName, e.message
            )
            failedName = fileName
            return
        }
        // 纹理尺寸上限：即便服务端已限 512KB，解压后的像素仍可能很大（PNG 压缩率高）
        if (image.width <= 0 || image.height <= 0 || image.width > MAX_SIDE || image.height > MAX_SIDE) {
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.warn(
                "ID card background {} has an unreasonable size ({}x{}); ignored",
                fileName, image.width, image.height
            )
            image.close()
            failedName = fileName
            return
        }

        // 同名重复到货（服主换图后玩家重开界面）：先释放旧的，避免纹理泄漏
        entry?.let { runCatching { MinecraftClient.getInstance().textureManager.destroyTexture(it.id) } }

        // 纹理 id 用**自增序号**而不是文件名：文件名是服主起的，可能含空格/中文/非法字符
        val id = Identifier.of("cobblemarket", "dynamic/id_card_bg_${nextId++}")
        try {
            MinecraftClient.getInstance().textureManager.registerTexture(id, NativeImageBackedTexture(image))
        } catch (e: Exception) {
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.warn(
                "Failed to register ID card background texture {}: {}", fileName, e.message
            )
            image.close()
            failedName = fileName
            return
        }
        entry = Entry(id, image.width, image.height, fileName)
        failedName = null
    }

    /** 断开连接 / 换服时释放（纹理归 MC 管，这里只清自己的状态并让它销毁） */
    fun reset() {
        entry?.let { runCatching { MinecraftClient.getInstance().textureManager.destroyTexture(it.id) } }
        entry = null
        failedName = null
        nextId = 0
    }

    /** 单边上限 2048（与服务端 `ConfigImage.MAX_SIDE` 一致） */
    private const val MAX_SIDE = 2048
}
