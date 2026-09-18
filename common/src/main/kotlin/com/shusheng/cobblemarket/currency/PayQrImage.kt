package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.platform.configDir
import java.io.File

/**
 * **服主收款码图片的读取**（`config/cobblemarket/qr/<文件名>`）。
 *
 * 为什么由服务端读、再经网络下发给客户端：配置文件在服务端，而图要在玩家的客户端上画 ——
 * 专用服务器上玩家本地根本没有那个文件（2026-09-18 拍板，见方案「实施细节」）。
 *
 * ⚠ **放在 `qr/` 子目录**（2026-09-18 用户提的）：`cobblemarket/` 根下已经混着 json 与账本目录
 * `points/`，图片再堆进来更乱。配置里只写文件名（`5.png`），目录是固定的，不用手写路径。
 *
 * 支持**多张码**（2026-09-18 用户提出）：档位可以各配一张"带金额"的收款码
 * （微信/支付宝能生成固定金额的码，玩家扫了直接付、不会输错），没配的档位走全局那张。
 *
 * 安全与健壮性（文件名来自配置，仍按不可信处理）：
 * - **防路径穿越**：只取文件名的最后一段，`../` 之类一律拒绝
 * - **大小上限** [MAX_BYTES]、**只认 PNG**（校验文件头魔数，后缀名不可信）
 * - 任何失败都返回 null（**「没配收款码」是正常状态**，不是错误）
 */
object PayQrImage {

    /**
     * 512KB：收款码正常几十 KB，这个上限只是兜底。
     * ⚠ 不取 1MB —— MC 的 `PacketByteBuf.writeByteArray` 自带 1MB 上限（含长度前缀），
     * 顶着那个值写会直接抛异常；留一半余量稳妥。
     */
    const val MAX_BYTES = 512 shl 10

    /** 收款码子目录名（在 `config/cobblemarket/` 下） */
    const val QR_DIR = "qr"

    /** PNG 文件头（89 50 4E 47 0D 0A 1A 0A） */
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private class Cached(val bytes: ByteArray, val mtime: Long)

    /** 文件名 → 缓存。服主改图通常是替换文件（mtime 变）或换文件名，据此失效即可 */
    private val cache = mutableMapOf<String, Cached>()

    /** 目录说明（首次运行时落一份，之后不覆盖） */
    private val README_TEXT = """
        CobbleMarket - RP market payment QR codes
        =========================================

        Put your WeChat / Alipay payment QR images HERE (plain file name, no path),
        then fill the file name in game: Admin Panel -> Server Config -> RP Market.

        - PNG only, 512KB max per image.
        - A tier may have its OWN QR image ("fixed amount" codes made by WeChat/Alipay):
          that tier then charges the list price exactly. Tiers without one fall back to
          the global image and get a unique amount suffix so you can match the bill.
        - Files are read on demand (no restart needed); replacing an image takes effect
          for the next order opened.

        收款码目录
        ==========
        把微信/支付宝的收款码 PNG 放在这里（只写文件名，不带路径），
        然后在游戏里填：管理面板 -> 服务器配置 -> RP 市场。

        - 只认 PNG，单张不超过 512KB。
        - 档位可以配自己的收款码（微信/支付宝的「设置金额」码），
          该档实付金额就等于码上的金额；没配专用码的档位走全局码、会加唯一尾数便于对账。
        - 读图是按需的（改图不用重启），换了图对下一笔新订单生效。
    """.trimIndent()

    /** 单机切存档 / 换服时复位（静态状态不跨世界） */
    fun reset() {
        cache.clear()
    }

    /**
     * 确保收款码目录存在（顺手放一份说明）。服务端启动时调一次，[load] 里也兜一道 ——
     * 目录存在，服主打开 config 就知道图该往哪放，不必等玩家下单才发现要自己建。
     */
    fun ensureDir() {
        val dir = configDir().resolve("cobblemarket").resolve(QR_DIR).toFile()
        if (!dir.isDirectory) dir.mkdirs()
        val readme = dir.resolve("README.txt")
        // 只写一次，不覆盖服主自己的改动
        if (!readme.isFile) {
            runCatching { readme.writeText(README_TEXT) }
        }
    }

    /**
     * 读指定文件名的收款码；返回 null = 没配 / 读不了 / 不合法。
     * 调用方传**空串**即表示"这一档没有专用码"，同样返回 null（由调用方回退到全局码）。
     */
    fun load(fileName: String): ByteArray? {
        // ⚠ 建目录必须在「空文件名直接返回」**之前** —— 否则只配了档位专用码、
        //   全局码留空的服主永远看不到这个目录（2026-09-18 用户实测踩到）
        ensureDir()
        if (fileName.isBlank()) return null

        // 防路径穿越：只认纯文件名，`../` 与子目录一律拒绝
        val name = File(fileName).name
        if (name != fileName || name.startsWith(".")) {
            CobbleMarket.LOGGER.warn("Payment QR image must be a plain file name, got: {}", fileName)
            return null
        }

        val file = configDir().resolve("cobblemarket").resolve(QR_DIR).resolve(name).toFile()
        if (!file.isFile) {
            CobbleMarket.LOGGER.warn("Payment QR image not found: {}", file.path)
            return null
        }
        val mtime = file.lastModified()
        cache[name]?.let { if (it.mtime == mtime) return it.bytes }

        if (file.length() > MAX_BYTES) {
            CobbleMarket.LOGGER.warn(
                "Payment QR image is too large ({} bytes > {}); ignored", file.length(), MAX_BYTES
            )
            return null
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to read payment QR image: {}", e.message)
            return null
        }
        if (bytes.size < PNG_MAGIC.size || !bytes.copyOfRange(0, PNG_MAGIC.size).contentEquals(PNG_MAGIC)) {
            CobbleMarket.LOGGER.warn("Payment QR image is not a PNG (file header mismatch): {}", name)
            return null
        }
        cache[name] = Cached(bytes, mtime)
        return bytes
    }

    /**
     * 商店"有没有收款码可用"：全局那张能读出来就算有 —— 用于决定入口按钮与提示文案。
     * 单个档位自己的码是否存在，由 [load] 按请求时判断。
     */
    fun globalAvailable(): Boolean = load(CobbleMarketConfig.pointsShopPayQrImage) != null
}
