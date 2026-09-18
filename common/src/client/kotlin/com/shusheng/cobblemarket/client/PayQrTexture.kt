package com.shusheng.cobblemarket.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream

/**
 * **收款码图片的客户端动态纹理**（全项目第一处：服务端下发字节 → 本地解码 → 动态纹理）。
 *
 * 为什么客户端要自己注册纹理：收款码是服主配在服务端 `config/` 下的**任意一张图**，
 * 不在资源包里，客户端拿不到 `Identifier` 指向的静态贴图 —— 只能收到字节后现场解码注册
 * （2026-09-18 拍板，见方案「实施细节」）。
 *
 * ⚠ 注册/销毁都必须在**客户端主线程**（调用方在 `client.execute {}` 里调）。
 */
object PayQrTexture {

    /** 动态纹理的固定 id —— 同一会话只存在一张收款码 */
    private val ID = Identifier.of("cobblemarket", "dynamic/pay_qr")

    private var uploaded = false
    private var textureW = 0
    private var textureH = 0

    /** 服务端明确回了「读不出来」（空包）：文件不存在 / 不是 PNG / 太大 */
    private var unavailable = false

    /** 有没有可画的图（服务端下发过且解码成功） */
    val available: Boolean get() = uploaded

    /** 服务端说这张图取不到 —— 界面据此显示「收款码不可用」，别一直停在「加载中」 */
    val isUnavailable: Boolean get() = unavailable

    /** 服务端回了空包（图读不出来） */
    fun markUnavailable() {
        unavailable = true
        uploaded = false
        textureW = 0
        textureH = 0
    }

    /** 图宽高（界面按它算等比绘制尺寸）；未就绪时都是 0 */
    val width: Int get() = textureW
    val height: Int get() = textureH

    /** 绘制用的纹理 id（[available] 为 false 时不要用） */
    fun textureId(): Identifier = ID

    /**
     * 收到服务端下发的字节：解码成动态纹理。
     *
     * 失败（不是合法 PNG / 尺寸离谱）只记日志并保持 [available] = false ——
     * **弹窗照常显示订单号与金额**，没有二维码比整个弹窗打不开好得多。
     */
    fun accept(bytes: ByteArray) {
        val image = try {
            NativeImage.read(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.warn("Failed to decode payment QR image: {}", e.message)
            return
        }
        // 纹理尺寸上限：即便服务端已限 512KB，解压后的像素仍可能很大（PNG 压缩率高）
        if (image.width <= 0 || image.height <= 0 || image.width > MAX_SIDE || image.height > MAX_SIDE) {
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.warn(
                "Payment QR image has an unreasonable size ({}x{}); ignored", image.width, image.height
            )
            image.close()
            return
        }
        try {
            // 同名重复注册时 MC 会自己把旧纹理排进销毁队列，这里不用手动 close
            MinecraftClient.getInstance().textureManager.registerTexture(ID, NativeImageBackedTexture(image))
        } catch (e: Exception) {
            com.shusheng.cobblemarket.CobbleMarket.LOGGER.warn("Failed to register payment QR texture: {}", e.message)
            image.close()
            return
        }
        textureW = image.width
        textureH = image.height
        uploaded = true
        unavailable = false
    }

    /** 断开连接 / 换服 / 换订单时释放（纹理归 MC 管，这里只清自己的状态并让它销毁） */
    fun reset() {
        if (uploaded) {
            runCatching { MinecraftClient.getInstance().textureManager.destroyTexture(ID) }
        }
        uploaded = false
        unavailable = false
        textureW = 0
        textureH = 0
    }

    /** 单边上限 2048：收款码从没有这么大，超过基本是误放的图 */
    private const val MAX_SIDE = 2048
}
