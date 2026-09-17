package com.shusheng.cobblemarket.currency

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * **外部签名码**的解析与验签（完整协议见 `docs/points_plan.md`「外部签名码协议」）。
 *
 * 用途：服主在自己的网站 / 发卡网卖点券 —— 付款后服主侧签发一个码，玩家在游戏内兑换。
 * 设计目标是**网站与 MC 服务器零耦合**（不开端口、不做内网穿透、网站挂了不影响游戏），
 * 任何能算 HMAC 的平台都能签码。
 *
 * ⚠ **secret 只能放在服务端**（网站后端 / 服主本机），绝不能进客户端、日志或公开仓库。
 * 服务端日志只记码前缀，不记全码（码 = 钱）。
 *
 * ⚠ 与本地码的关系：**两条路径共用一个兑换入口** —— 先试外部验签，再查本地码表。
 */
object ExternalPointCode {

    /** 协议版本；不认识的版本一律拒绝 */
    const val VERSION = 0x01

    /** 码的二进制长度：20 字节 = 正好 32 个 Base32 字符（无 padding） */
    const val LENGTH_BYTES = 20

    private const val PAYLOAD_BYTES = 15

    /** 签名截断到 5 字节（40 bit）：配合兑换节流与失败冷却，伪造不可行 */
    private const val SIGNATURE_BYTES = 5

    /** 过期日基准：自 2026-01-01 UTC 起的天数（协议固定常量，改动即协议变更、需升 version） */
    private val EPOCH_DAYS = Instant.parse("2026-01-01T00:00:00Z").epochSecond / 86400

    private const val MS_PER_DAY = 86_400_000L

    /** 验签通过的码 */
    data class Decoded(val serial: String, val amount: Long, val expiresAt: Long?)

    /**
     * 解析并验签。**返回 null = 一律拒绝**（不区分原因地拒绝，避免给爆破者反馈；
     * 服务端日志由调用方按需记码前缀）。
     *
     * 校验顺序照协议：长度/编码 → version → **签名**（先验签再谈其它）→ 过期 → 面额 → 有效期上限。
     * serial 是否已用（防重放）由调用方查账本，不在本函数内。
     */
    fun verify(raw: String?, secret: String, now: Long, maxDays: Int): Decoded? {
        if (secret.isBlank()) return null // 留空 = 关闭外部码，只接受本地码
        val bytes = PointCodes.decodeToBytes(raw, LENGTH_BYTES) ?: return null

        // 先验签：签名输入是 payload 的**原始 15 字节**（不是 Base32 字符串、不是十六进制文本），
        // 避免不同实现因编码差异验签失败。比较用常量时间，不用 == 逐字节比。
        val expected = runCatching {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            mac.doFinal(bytes.copyOfRange(0, PAYLOAD_BYTES)).copyOfRange(0, SIGNATURE_BYTES)
        }.getOrNull() ?: return null
        val actual = bytes.copyOfRange(PAYLOAD_BYTES, LENGTH_BYTES)
        if (!MessageDigest.isEqual(expected, actual)) return null

        val buffer = ByteBuffer.wrap(bytes)
        val version = buffer.get().toInt() and 0xFF
        if (version != VERSION) return null

        val serialBytes = ByteArray(6)
        buffer.get(serialBytes)
        val serial = serialBytes.joinToString("") { "%02x".format(it) }

        val amount = buffer.int.toLong()
        val expiresDays = buffer.short.toInt() and 0xFFFF
        // flags / reserved 当前全 0，只为后续扩展占位，这里不校验（升 version 时才谈兼容）

        if (amount <= 0) return null
        val expiresAt = if (expiresDays == 0) null else (EPOCH_DAYS + expiresDays) * MS_PER_DAY
        if (expiresAt != null) {
            // 已过期（服务端时间，不信客户端）
            if (now > expiresAt) return null
            // 有效期超出服主设的上限：视为签发方配置不当，拒绝（防"永久码"满天飞）
            if (maxDays in 1..Int.MAX_VALUE && (expiresAt - now) / MS_PER_DAY > maxDays) return null
        }
        return Decoded(serial, amount, expiresAt)
    }
}
