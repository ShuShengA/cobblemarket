package com.shusheng.cobblemarket.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream

/**
 * **档位「RP 券」图的客户端动态纹理**（多张，按**文件名**缓存）。
 *
 * 与 [PayQrTexture] 的分工：收款码同一时刻只看一笔订单、只存在一张；券图要给**每个档位**画，
 * 所以这里是一张表 —— 两个档位配了同一张图时只解码一次。
 *
 * ⚠ 注册/销毁都必须在**客户端主线程**（调用方在 `client.execute {}` 里调）。
 */
object TierIconTextures {

    /** 一张已就绪的券图：绘制用的纹理 id + 原始像素尺寸（界面按它算等比缩放） */
    class Entry(val id: Identifier, val width: Int, val height: Int)

    private val entries = mutableMapOf<String, Entry>()

    /**
     * 服务端明确回了「读不出来」（空包 / 解码失败）的文件名。
     * 记下来是为了**别反复请求**（每次快照都重发一个注定失败的包）；界面据此回退到内置券图。
     */
    private val failed = mutableSetOf<String>()

    private var nextId = 0

    /** 取一张已就绪的券图；null = 还没到货 / 读不出来（调用方回退到内置券图） */
    fun get(fileName: String): Entry? = entries[fileName]

    /** 这个文件名是不是已经确认取不到了（别再请求） */
    fun isFailed(fileName: String): Boolean = fileName in failed

    /**
     * 重进界面时给失败过的文件名**一次重试机会**：服主可能刚把图补上，
     * 而客户端除非重连否则不会清这个记录（不清的话玩家得重启游戏才看得到新图）。
     */
    fun retryFailed() {
        failed.clear()
    }

    /**
     * 收到服务端下发的字节：解出一张动态纹理。
     *
     * **空数组 = 服务端读不出来**（文件不存在 / 不是 PNG / 太大）—— 与收款码同一个约定。
     * 任何失败都只记进 [failed]，不抛：一张图取不到不该让整个界面打不开。
     */
    fun accept(fileName: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            failed += fileName
            return
        }
        val image = try {
            NativeImage.read(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.warn(
                "Failed to decode tier icon {}: {}", fileName, e.message
            )
            failed += fileName
            return
        }
        // 纹理尺寸上限：即便服务端已限 512KB，解压后的像素仍可能很大（PNG 压缩率高）
        if (image.width <= 0 || image.height <= 0 || image.width > MAX_SIDE || image.height > MAX_SIDE) {
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.warn(
                "Tier icon {} has an unreasonable size ({}x{}); ignored", fileName, image.width, image.height
            )
            image.close()
            failed += fileName
            return
        }

        // 同名重复到货（服主换图后玩家重进界面）：先释放旧的，避免纹理泄漏
        entries.remove(fileName)?.let { runCatching { MinecraftClient.getInstance().textureManager.destroyTexture(it.id) } }

        // 纹理 id 用**自增序号**而不是文件名：文件名是服主起的，可能含空格/中文/非法字符
        val id = Identifier.of("cobblemarket", "dynamic/tier_icon_${nextId++}")
        try {
            MinecraftClient.getInstance().textureManager.registerTexture(id, NativeImageBackedTexture(image))
        } catch (e: Exception) {
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.warn(
                "Failed to register tier icon texture {}: {}", fileName, e.message
            )
            image.close()
            failed += fileName
            return
        }
        entries[fileName] = Entry(id, image.width, image.height)
        failed -= fileName
    }

    /** 断开连接 / 换服时释放（纹理归 MC 管，这里只清自己的状态并让它销毁） */
    fun reset() {
        entries.values.forEach { entry ->
            runCatching { MinecraftClient.getInstance().textureManager.destroyTexture(entry.id) }
        }
        entries.clear()
        failed.clear()
        nextId = 0
    }

    /** 单边上限 2048：券图从没有这么大，超过基本是误放的图 */
    private const val MAX_SIDE = 2048
}
