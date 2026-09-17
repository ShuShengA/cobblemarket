package com.shusheng.cobblemarket.currency

import java.security.SecureRandom

/**
 * 兑换码的字符集与编解码。
 *
 * ⚠ **Crockford Base32**（剔除了 I / L / O / U）：
 * - 剔除 I、L、O 是为了**手抄不出歧义**（1 与 I/L、0 与 O 长得太像）
 * - 剔除 U 是 Crockford 的原有设计（避免随机拼出脏话）
 * - 解析时把 I/L 归一到 1、O 归一到 0 —— 玩家抄错也能兑上
 *
 * 码 = 本地码与外部签名码**共用同一套字符集**（外部码 32 位、本地码 12 位，见方案「外部签名码协议」）。
 */
object PointCodes {

    /** Crockford Base32 字符表（下标即值，0-31） */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 本地码长度：12 位 × 5 bit = 60 bit 熵 —— SecureRandom 生成，不可枚举 */
    const val LENGTH = 12

    /** 外部签名码长度：32 位 = 160 bit（见方案「外部签名码协议」） */
    const val EXTERNAL_LENGTH = 32

    private val random = SecureRandom()

    /** 生成一个新码（归一化形式：大写、无分隔符）。**碰撞由调用方查表处理并重抽** */
    fun generate(length: Int = LENGTH): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /**
     * 归一化用户输入：去分隔符、转大写、I/L→1、O→0。
     * 返回 null = 长度不对或含非法字符（**不要猜测修正**，直接判非法）。
     */
    fun normalize(raw: String?, length: Int = LENGTH): String? {
        if (raw == null) return null
        val cleaned = raw.trim().uppercase()
            .replace("-", "").replace(" ", "")
            .replace('I', '1').replace('L', '1').replace('O', '0')
        if (cleaned.length != length) return null
        if (cleaned.any { it !in ALPHABET }) return null
        return cleaned
    }

    /** 展示形式：按 4 位一组加连字符（12 位 → XXXX-XXXX-XXXX；32 位 → 8 组），方便读与抄 */
    fun format(code: String): String = code.chunked(4).joinToString("-")

    /**
     * 归一化码 → 20 字节二进制（外部签名码用；本地码不需要）。
     * 返回 null = 长度不对或含非法字符。
     */
    fun decodeToBytes(raw: String?, byteLength: Int): ByteArray? {
        val cleaned = normalize(raw, byteLength * 8 / 5) ?: return null
        val out = ByteArray(byteLength)
        var bitBuffer = 0
        var bitCount = 0
        var outIndex = 0
        for (ch in cleaned) {
            val value = ALPHABET.indexOf(ch)
            if (value < 0) return null
            bitBuffer = (bitBuffer shl 5) or value
            bitCount += 5
            if (bitCount >= 8) {
                bitCount -= 8
                out[outIndex++] = ((bitBuffer shr bitCount) and 0xFF).toByte()
            }
        }
        return if (outIndex == byteLength) out else null
    }

    /** 20 字节二进制 → 32 位 Base32 字符串（签名器与外部码验签用） */
    fun encodeBytes(bytes: ByteArray): String {
        val sb = StringBuilder()
        var bitBuffer = 0
        var bitCount = 0
        for (b in bytes) {
            bitBuffer = (bitBuffer shl 8) or (b.toInt() and 0xFF)
            bitCount += 8
            while (bitCount >= 5) {
                bitCount -= 5
                sb.append(ALPHABET[(bitBuffer shr bitCount) and 0x1F])
            }
        }
        if (bitCount > 0) sb.append(ALPHABET[(bitBuffer shl (5 - bitCount)) and 0x1F])
        return sb.toString()
    }
}
