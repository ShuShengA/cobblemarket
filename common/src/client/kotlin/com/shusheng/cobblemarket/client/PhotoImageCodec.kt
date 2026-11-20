package com.shusheng.cobblemarket.client

import net.minecraft.client.texture.NativeImage
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * 照片图片的编解码工具（2026-11-20）。
 *
 * ⚠⚠ **MC 的 `NativeImage.read` 只支持 PNG**（STB 在客户端编译时通常裁掉了 JPEG）——
 *   照片是 JPEG，直接 `NativeImage.read` 会解码失败（实测日志：三档 JPEG 编码成功、解码全挂）。
 *   所以 JPEG 一律走 **ImageIO → BufferedImage → NativeImage** 这条路。
 *
 * 线程：ImageIO 解码与像素转换都是纯内存操作，可以在后台线程做；
 * 只有 `TextureManager.registerTexture` 必须在客户端主线程。
 */
object PhotoImageCodec {

    /** 解码 JPEG（或任意 ImageIO 支持的格式）；失败返回 null */
    fun decode(bytes: ByteArray): BufferedImage? =
        runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()

    /**
     * BufferedImage（ARGB 打包）→ NativeImage。
     * ⚠ `NativeImage.setColor` 吃的是内存 RGBA 布局（小端 int = ABGR），
     *   所以这里把 `a<<24|r<<16|g<<8|b` 转成 `a<<24|b<<16|g<<8|r`。
     */
    fun toNativeImage(image: BufferedImage): NativeImage {
        val native = NativeImage(image.width, image.height, false)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                val a = (argb ushr 24) and 0xFF
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                native.setColor(x, y, (a shl 24) or (b shl 16) or (g shl 8) or r)
            }
        }
        return native
    }
}
