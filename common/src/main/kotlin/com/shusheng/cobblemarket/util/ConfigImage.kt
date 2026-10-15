package com.shusheng.cobblemarket.util

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.platform.configDir
import java.io.File

/**
 * **配置目录下图片的读取**（`config/cobblemarket/<子目录>/<文件名>`）。
 *
 * 为什么由服务端读、再经网络下发给客户端：配置文件在服务端，而图要在玩家的客户端上画 ——
 * 专用服务器上玩家本地根本没有那个文件。
 *
 * ⚠ 逻辑只此一份：防路径穿越、PNG 魔数、大小上限、mtime 缓存这些是**安全相关**的，
 * 各写一遍迟早只修一处。
 *
 * 安全与健壮性（文件名来自配置，仍按不可信处理）：
 * - **防路径穿越**：只取文件名的最后一段，`../` 与子目录一律拒绝
 * - **大小上限** [MAX_BYTES]、**只认 PNG**（校验文件头魔数，后缀名不可信）
 * - 任何失败都返回 null（**「没配图」是正常状态**，不是错误）
 */
object ConfigImage {

    /**
     * 512KB：这类图正常几十 KB，这个上限只是兜底。
     * ⚠ 不取 1MB —— MC 的 `PacketByteBuf.writeByteArray` 自带 1MB 上限（含长度前缀），
     * 顶着那个值写会直接抛异常；留一半余量稳妥。
     */
    const val MAX_BYTES = 512 shl 10

    /**
     * 单边像素上限，**与客户端解码时的上限一致**（客户端那张纹理的 MAX_SIDE）。
     *
     * ⚠ 服务端必须在**这里**就拦下来：客户端超限是**静默忽略**（只写日志），服主只会看到
     * 「图没生效」，根本不知道是尺寸的问题。拦在这里还能顺着保存配置那条路报给服主。
     */
    const val MAX_SIDE = 2048

    /** PNG 文件头（89 50 4E 47 0D 0A 1A 0A） */
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private class Cached(val bytes: ByteArray, val mtime: Long)

    /** `子目录/文件名` → 缓存。服主改图通常是替换文件（mtime 变）或换文件名，据此失效即可 */
    private val cache = mutableMapOf<String, Cached>()

    /** 单机切存档 / 换服时复位（静态状态不跨世界） */
    fun reset() {
        cache.clear()
    }

    /** 子目录的绝对路径（不建目录，纯换算） */
    private fun dirOf(dir: String): File = configDir().resolve("cobblemarket").resolve(dir).toFile()

    /**
     * 确保子目录存在。服务端启动时调一次，[load] 里也兜一道 ——
     * 目录存在，服主打开 config 就知道图该往哪放，不必等玩家用到才发现要自己建。
     */
    fun ensureDir(dir: String) {
        val d = dirOf(dir)
        if (!d.isDirectory) d.mkdirs()
    }

    /**
     * 落一份目录说明。内容与预期一致就不动（不白写盘）；**不一致就覆盖** ——
     * 说明文件是模组的一部分，得跟着版本走：模组补了尺寸/格式限制，老服主的 README
     * 也得跟上，否则「只写一次」会让新说明永远到不了已经生成过目录的服主那里。
     */
    fun writeReadmeIfAbsent(dir: String, text: String) {
        ensureDir(dir)
        val readme = dirOf(dir).resolve("README.txt")
        if (readme.isFile && runCatching { readme.readText() }.getOrNull() == text) return
        runCatching { readme.writeText(text) }
    }

    /**
     * 读子目录下指定文件名的图；返回 null = 没配 / 读不了 / 不合法。
     * 调用方传**空串**即表示"这一项没配"，同样返回 null。
     */
    fun load(dir: String, fileName: String): ByteArray? = loadVersioned(dir, fileName)?.bytes

    /** 一张图 + 它的版本戳（版本的含义见 [loadVersioned]） */
    class Versioned(val bytes: ByteArray, val version: String)

    /**
     * 带**版本戳**读图：读盘、校验、缓存与 [load] 共用同一份逻辑，只是多回一个版本号。
     *
     * 版本 = **`文件名@修改时间`** —— 换名会变，**就地覆盖同名文件也会变**。
     * 用途：客户端把手里那张图的版本回传给服务端，服务端比对相同就**不重传图**
     * （回几十字节 vs 几十 KB）。判据（文件名 + mtime）只有服务端手里有，所以判定在服务端做。
     *
     * 返回 null = 没配 / 读不了 / 不合法（这时**连版本也没有**，调用方按"没有图"处理）。
     */
    fun loadVersioned(dir: String, fileName: String): Versioned? {
        // ⚠ 建目录必须在「空文件名直接返回」**之前** —— 否则没配图的服主
        //   永远看不到这个目录
        ensureDir(dir)
        if (fileName.isBlank()) return null

        // 防路径穿越：只认纯文件名，`../` 与子目录一律拒绝
        val name = File(fileName).name
        if (name != fileName || name.startsWith(".")) {
            CobbleMarket.LOGGER.warn("Config image must be a plain file name, got: {} (in {}/)", fileName, dir)
            return null
        }

        val key = "$dir/$name"
        val file = dirOf(dir).resolve(name)
        if (!file.isFile) {
            CobbleMarket.LOGGER.warn("Config image not found: {}", file.path)
            return null
        }
        val mtime = file.lastModified()
        cache[key]?.let { if (it.mtime == mtime) return Versioned(it.bytes, versionOf(name, it.mtime)) }

        if (file.length() > MAX_BYTES) {
            CobbleMarket.LOGGER.warn(
                "Config image is too large ({} bytes > {}): {}", file.length(), MAX_BYTES, key
            )
            return null
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to read config image {}: {}", key, e.message)
            return null
        }
        if (bytes.size < PNG_MAGIC.size || !bytes.copyOfRange(0, PNG_MAGIC.size).contentEquals(PNG_MAGIC)) {
            CobbleMarket.LOGGER.warn("Config image is not a PNG (file header mismatch): {}", key)
            return null
        }
        val size = pngSize(bytes)
        if (size == null) {
            CobbleMarket.LOGGER.warn("Config image has a broken PNG header: {}", key)
            return null
        }
        if (size.first > MAX_SIDE || size.second > MAX_SIDE) {
            CobbleMarket.LOGGER.warn(
                "Config image {} is {}x{}; at most {}x{} is supported (clients would silently ignore it)",
                key, size.first, size.second, MAX_SIDE, MAX_SIDE
            )
            return null
        }
        cache[key] = Cached(bytes, mtime)
        return Versioned(bytes, versionOf(name, mtime))
    }

    /** 版本戳：`文件名@修改时间`。客户端只原样回传、不解析，改格式也不会牵连客户端 */
    private fun versionOf(name: String, mtime: Long): String = "$name@$mtime"

    /**
     * 从 PNG 字节里量出宽高：`8 字节签名 + 4 字节块长度 + "IHDR" + 宽 4 + 高 4`，
     * 所以宽在偏移 16、高在偏移 20（大端）。
     * **不整图解码** —— 服务端只为量个尺寸，没必要解压整张图。
     */
    private fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        fun u32(off: Int): Int =
            ((bytes[off].toInt() and 0xFF) shl 24) or
                ((bytes[off + 1].toInt() and 0xFF) shl 16) or
                ((bytes[off + 2].toInt() and 0xFF) shl 8) or
                (bytes[off + 3].toInt() and 0xFF)
        return u32(16) to u32(20)
    }
}
