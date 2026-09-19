package com.shusheng.cobblemarket.currency

import java.security.SecureRandom

/**
 * **充值订单号**：6 位 Crockford Base32（与兑换码同一套字符集，剔除 I/L/O/U 防手抄歧义）。
 *
 * 结构：**前 5 位随机 + 末位校验位**（5 位 × 5 bit = 25 bit 熵，约 3300 万组合）。
 * 订单号不需要防猜测 —— 它只是服主核对账单的索引，猜中别人的号也领不走别人的RP
 * （确认发放由服主操作，领不领得到跟订单号本身无关）；**需要的恰恰是防手抄错**，
 * 所以末位放校验位：玩家申诉时报错一位，服主侧一眼就能看出「这号不存在」而不是查半天。
 *
 * 校验算法：`Σ digit_i × 31^i (mod 32)` —— 位置权重取 31（≡ -1 mod 32）而非 32，
 * 这样**任意单字符抄错都必然改变校验值**（用 32 作权重的话高位会被 mod 32 抹掉，等于没校验）。
 *
 * ⚠ 玩家输入一律先过 [normalize]（去分隔符、转大写、I/L→1、O→0），抄错形近字也能对上。
 */
object PointOrderNumbers {

    /** 展示长度（含校验位） */
    const val LENGTH = 6

    /** 随机部分长度，剩下 1 位是校验位 */
    private const val PAYLOAD_LENGTH = 5

    private const val BASE = 32

    /** 进位权重：31 ≡ -1 (mod 32)，保证单字符错误一定改变校验值 */
    private const val WEIGHT_STEP = 31

    private val random = SecureRandom()

    /** 生成一个订单号（归一化形式：大写、无分隔符）。**唯一性由调用方查账本处理并重抽** */
    fun generate(): String {
        val digits = IntArray(PAYLOAD_LENGTH) { random.nextInt(BASE) }
        return build(digits)
    }

    /**
     * 归一化用户输入。返回 null = 长度不对或含非法字符
     * （校验位**不在这里判**：号抄错了要说「查无此单」，而不是「格式错误」）。
     */
    fun normalize(raw: String?): String? = PointCodes.normalize(raw, LENGTH)

    /** 校验位对不对（供 GUI 边输边提示；命令侧不拦，交给「查无此单」兜底） */
    fun isValid(orderNo: String): Boolean {
        if (orderNo.length != LENGTH) return false
        val digits = IntArray(LENGTH) { PointCodes.valueOf(orderNo[it]) }
        if (digits.any { it < 0 }) return false
        return checksum(digits.copyOf(PAYLOAD_LENGTH)) == digits[PAYLOAD_LENGTH]
    }

    /** 按 3 位一组加连字符展示（`ABC-DEF`），念给客服听时不容易串行 */
    fun format(orderNo: String): String = orderNo.chunked(3).joinToString("-")

    private fun build(digits: IntArray): String {
        val sb = StringBuilder(LENGTH)
        digits.forEach { sb.append(PointCodes.charAt(it)) }
        sb.append(PointCodes.charAt(checksum(digits)))
        return sb.toString()
    }

    private fun checksum(digits: IntArray): Int {
        var sum = 0
        var weight = 1
        digits.forEach { digit ->
            sum = (sum + digit * weight) % BASE
            weight = (weight * WEIGHT_STEP) % BASE
        }
        return sum
    }
}
