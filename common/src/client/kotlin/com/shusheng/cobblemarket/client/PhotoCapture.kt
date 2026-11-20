package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.network.PhotoNetwork
import com.shusheng.cobblemarket.screen.PhotoCaptureScreen
import com.shusheng.cobblemarket.screen.PhotoConfirmScreen
import com.shusheng.cobblemarket.screen.QuickShowcaseScreen
import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.client.util.ScreenshotRecorder
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * **拍照流程**（功能2，2026-11-20）：快速功能界面的相机按钮 → 收起界面 → 打开**取景界面**
 * （[PhotoCaptureScreen]，四角角标）→ 左键拍照 → 关掉取景界面 → 等世界画两帧 → 截屏 →
 * 按相框内框比例裁剪 → JPEG 自适应压缩 → 打开确认界面（[PhotoConfirmScreen]）。
 *
 * 分辨率与压缩（2026-11-20 用户反馈「太糊」后升级）：
 * - 原图裁剪后缩到**宽 1280**（高度按相框内框比例，约 1280×675），再 **JPEG** 压缩；
 *   质量 0.85 起每档 −0.05，直到 ≤ [PhotoNetwork.MAX_PHOTO_BYTES]（256KB），最低 0.35；
 *   还不达标才降分辨率（1024 → 768）。聊天悬停的显示上限只有 320 逻辑宽，1280 足够清晰
 * - **JPEG 压缩在后台线程**：1280 宽的 ImageIO 编码要几百毫秒，放主线程会让拍照明显卡顿
 * - 截图与转 BufferedImage 仍在主线程（渲染线程），完成后**立即恢复取景时的 FOV** ——
 *   画面已经拍下来了，后台压缩不影响照片
 *
 * 截图不含任何模组界面：先真正关掉取景界面、再等两 tick 让世界画面渲染干净。
 */
object PhotoCapture {

    /** 相框内框的宽高比（几何收在 [PhotoFrame]，此处只引用，别另写一份） */
    val FRAME_ASPECT: Double = PhotoFrame.INNER_ASPECT

    /**
     * 上传图的宽度候选（高度按内框比例算）。JPEG 压到上限仍不达标才逐档降；
     * 宽于源图的档直接用源图（放大不会更清晰）。
     */
    private val WIDTHS = listOf(1280, 1024, 768)

    /** 单张上限（与服务端一致） */
    private const val MAX_BYTES = PhotoNetwork.MAX_PHOTO_BYTES

    /** JPEG 质量：0.85 起，每档 −0.05，最低 0.35（再低画质不可用，宁可降分辨率） */
    private const val MAX_QUALITY = 0.85f
    private const val MIN_QUALITY = 0.35f
    private const val QUALITY_STEP = 0.05f

    private var waitingForClose = false
    /** 关掉当前界面后是打开取景界面（true）还是直接截图（false） */
    private var openViewfinderAfterClose = false
    private var delayTicks = -1

    /**
     * 拍照期间需要**延后恢复**的 FOV（取景界面滚轮改过）：左键拍照时由取景界面写入，
     * 截图完成后恢复 —— 否则界面一关 FOV 就复原，照片拍不到放大效果。
     */
    var pendingFovRestore: Int? = null

    /**
     * 拍照期间需要**延后恢复**的「HUD 隐藏」状态：取景界面像 F1 一样隐藏全部 HUD
     * （2026-11-20 用户要求），左键拍照时由界面写入原值，截图完成后恢复 ——
     * 否则界面一关 HUD 就回来，照片又会带上物品栏 / 准星 / 血量。
     */
    var pendingHudRestore: Boolean? = null

    /** 后台压缩进行中：这段时间里新的拍照直接丢弃（只有几百毫秒，避免结果串台） */
    private var processing = false

    private var nextTextureId = 0

    /** 相机按钮点击时调用：等快速功能界面收起后，打开取景界面 */
    fun armViewfinder() {
        waitingForClose = true
        openViewfinderAfterClose = true
        delayTicks = -1
    }

    /** 取景界面左键时调用：等它关闭后截图 */
    fun armCapture() {
        waitingForClose = true
        openViewfinderAfterClose = false
        delayTicks = -1
    }

    /** 等待期间是否还有拍照任务 */
    fun isPending(): Boolean = waitingForClose || delayTicks >= 0

    /** 由 `CobbleMarketClient` 每 tick 调用 */
    fun tick(client: MinecraftClient) {
        if (!waitingForClose && delayTicks < 0) return
        val screen = client.currentScreen
        if (waitingForClose) {
            // 我们自己的两个界面还在（快速功能界面收起动画 / 取景界面刚点关闭）—— 继续等它们消失
            if (screen is QuickShowcaseScreen || screen is PhotoCaptureScreen) return
            // 等待期间开出了别的界面（背包 / 命令打开的界面）或玩家已离开世界：
            // 放弃拍照，别截到不该拍的东西；取景时改过的 FOV 一并恢复
            if (screen != null || client.player == null) {
                cancel(client)
                return
            }
            waitingForClose = false
            if (openViewfinderAfterClose) {
                // 快速功能界面已关：进入取景界面，接下来由界面里的左键触发 armCapture
                openViewfinderAfterClose = false
                client.setScreen(PhotoCaptureScreen())
                return
            }
            // 取景界面已关：再等两 tick，让世界画面真的渲染到 framebuffer
            // （截图读的是上一帧，界面消失后的下一帧才干净）
            delayTicks = 2
            return
        }
        if (screen != null || client.player == null) {
            cancel(client)
            return
        }
        if (--delayTicks > 0) return
        reset()

        // 截图那一刻的玩家位置（供「分享拍摄位置」用；压缩期间玩家可能移动，不能等确认界面再取）
        val location = client.player?.let {
            Triple(it.blockX, it.blockY, it.blockZ)
        }
        // 主线程：截图 + 裁剪缩放 + 转 BufferedImage；完成后**立即恢复 FOV / HUD**（画面已拍下）
        val buffered = captureToBuffered(client)
        restoreFov(client)
        restoreHud(client)
        if (buffered != null) startProcessing(client, buffered, location)
    }

    private fun cancel(client: MinecraftClient) {
        reset()
        restoreFov(client)
        restoreHud(client)
    }

    private fun restoreFov(client: MinecraftClient) {
        val original = pendingFovRestore ?: return
        pendingFovRestore = null
        runCatching { client.options.getFov().setValue(original) }
    }

    private fun restoreHud(client: MinecraftClient) {
        val original = pendingHudRestore ?: return
        pendingHudRestore = null
        client.options.hudHidden = original
    }

    private fun reset() {
        waitingForClose = false
        openViewfinderAfterClose = false
        delayTicks = -1
    }

    // ── 截图与预处理（主线程）──

    private fun captureToBuffered(client: MinecraftClient): BufferedImage? {
        val shot = runCatching { ScreenshotRecorder.takeScreenshot(client.framebuffer) }.getOrNull()
        if (shot == null || shot.width <= 0 || shot.height <= 0) {
            shot?.close()
            fail(client)
            return null
        }
        return try {
            // 居中裁剪成相框内框的比例（截图更方就裁上下、更宽就裁左右）
            var cropW = shot.width
            var cropH = (shot.width / FRAME_ASPECT).toInt()
            if (cropH > shot.height) {
                cropH = shot.height
                cropW = (shot.height * FRAME_ASPECT).toInt()
            }
            cropW = cropW.coerceAtLeast(1)
            cropH = cropH.coerceAtLeast(1)
            val cropX = (shot.width - cropW) / 2
            val cropY = (shot.height - cropH) / 2

            // 目标宽：不放大源图（低分辨率窗口保留原始像素）
            val width = minOf(WIDTHS.first(), cropW)
            val height = (width / FRAME_ASPECT).toInt().coerceAtLeast(1)
            val scaled = NativeImage(width, height, false)
            try {
                shot.resizeSubRectTo(cropX, cropY, cropW, cropH, scaled)
                // NativeImage 的 getColor 返回 ABGR（内存 RGBA 小端打包）⇒ 解包成 BufferedImage 的 ARGB 布局。
                // 截图不透明，用 TYPE_INT_RGB（JPEG 也不支持透明）。
                val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val abgr = scaled.getColor(x, y)
                        val r = abgr and 0xFF
                        val g = (abgr ushr 8) and 0xFF
                        val b = (abgr ushr 16) and 0xFF
                        buffered.setRGB(x, y, (r shl 16) or (g shl 8) or b)
                    }
                }
                buffered
            } finally {
                scaled.close()
            }
        } catch (e: Exception) {
            // ⚠ 打印完整异常（含堆栈）：只打 message 时 NPE 之类会变成一行空白，没法查
            CobbleMarketClient.LOGGER.warn("Failed to capture photo", e)
            fail(client)
            null
        } finally {
            shot.close()
        }
    }

    // ── 后台压缩与上传确认 ──

    private fun startProcessing(
        client: MinecraftClient,
        buffered: BufferedImage,
        location: Triple<Int, Int, Int>?,
    ) {
        if (processing) return
        processing = true
        val thread = Thread({
            val result = runCatching { processBuffered(buffered) }.getOrElse {
                CobbleMarketClient.LOGGER.warn("Failed to encode photo", it)
                null
            }
            client.execute {
                processing = false
                if (result == null) {
                    fail(client)
                    return@execute
                }
                val (bytes, preview) = result
                // 玩家已退出世界 / 压缩期间打开了别的界面：丢弃，别把确认界面硬塞回去
                if (client.player == null || client.currentScreen != null) {
                    preview.close()
                    return@execute
                }
                val textureId = Identifier.of("cobblemarket", "dynamic/photo_${nextTextureId++}")
                try {
                    client.textureManager.registerTexture(textureId, NativeImageBackedTexture(preview))
                } catch (e: Exception) {
                    preview.close()
                    CobbleMarketClient.LOGGER.warn("Failed to register photo texture: {}", e.message)
                    fail(client)
                    return@execute
                }
                client.setScreen(
                    PhotoConfirmScreen(textureId, preview.width, preview.height, bytes, location)
                )
            }
        }, "cobblemarket-photo-processor")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * 后台线程：按 [WIDTHS] 由大到小尝试，JPEG 质量 0.85→0.35 自适应压到 [MAX_BYTES] 以内；
     * 成功后把字节**再解码成预览纹理**（与聊天悬停的解码路径一致，颜色不会错）。
     */
    private fun processBuffered(source: BufferedImage): Pair<ByteArray, NativeImage>? {
        for (width in WIDTHS) {
            val image = if (width >= source.width) source else scaleBuffered(source, width)
            val bytes = encodeJpeg(image, MAX_BYTES) ?: continue
            // ⚠ 不能用 NativeImage.read 解 JPEG（MC 的 STB 只带 PNG）——
            //   实测日志：JPEG 编码成功、NativeImage.read 全部失败，见 PhotoImageCodec
            val decoded = PhotoImageCodec.decode(bytes)
            if (decoded == null) {
                CobbleMarketClient.LOGGER.warn(
                    "Photo preview decode failed ({}x{}, {} bytes)", image.width, image.height, bytes.size
                )
                continue
            }
            val preview = PhotoImageCodec.toNativeImage(decoded)
            CobbleMarketClient.LOGGER.info(
                "Photo processed: {}x{} ({} bytes)", image.width, image.height, bytes.size
            )
            return bytes to preview
        }
        return null
    }

    /** JPEG 自适应质量：超上限就降一档重压；降到 [MIN_QUALITY] 还超则返回 null（调用方降分辨率） */
    private fun encodeJpeg(image: BufferedImage, maxBytes: Int): ByteArray? {
        var quality = MAX_QUALITY
        var firstError: Throwable? = null
        while (quality >= MIN_QUALITY - 0.001f) {
            val result = runCatching { writeJpeg(image, quality) }
            val bytes = result.getOrNull()
            if (bytes != null && bytes.size <= maxBytes) return bytes
            if (bytes == null && firstError == null) firstError = result.exceptionOrNull()
            quality -= QUALITY_STEP
        }
        // 编码抛异常（例如运行环境缺 JPEG writer）必须留痕，否则只剩一行「截图失败」没法查
        firstError?.let {
            CobbleMarketClient.LOGGER.warn("JPEG encode failed ({}x{})", image.width, image.height, it)
        }
        return null
    }

    private fun writeJpeg(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        try {
            val param = writer.defaultWriteParam
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = quality
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), param)
            }
            return out.toByteArray()
        } finally {
            writer.dispose()
        }
    }

    /** 等比缩到指定宽度（双线性；高度按相框内框比例） */
    private fun scaleBuffered(source: BufferedImage, width: Int): BufferedImage {
        val height = (width.toDouble() / FRAME_ASPECT).toInt().coerceAtLeast(1)
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun fail(client: MinecraftClient) {
        client.player?.sendMessage(
            Text.translatable("cobblemarket.photo.capture_failed").formatted(Formatting.RED), false
        )
    }
}
