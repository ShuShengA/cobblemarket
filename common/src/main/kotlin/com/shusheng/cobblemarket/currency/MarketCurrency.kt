package com.shusheng.cobblemarket.currency

/**
 * 市场结算币种：主货币（现有 4 个后端）与**点券**（模组自有的第二货币）并行流通。
 *
 * 两者**不可兑换、不可混用**：点券不参与金融系统（不能存取款生息、不能还贷、不能买卡），
 * 黑金卡/紫金卡的手续费与折扣也只跟主货币有关。详见 `docs/points_plan.md`。
 *
 * ⚠ **两套解析函数分开写、不要合并**（方案「三条硬要求」第 2 条）—— 两者的失败语义是相反的：
 * - [fromStored]（存档 / NBT）：缺字段、值是垃圾 → **容错成 PRIMARY**，兼容老档
 * - [fromRequest]（客户端 payload）：非法值 → **返回 null，调用方必须拒绝该请求**（防篡改）
 *
 * 混用的后果：拿容错版去解析请求 = 把伪造的币种静默当成主货币（等于放行），
 * 拿严格版去读老档 = 老存档直接读不出来。
 */
enum class MarketCurrency {
    PRIMARY,
    POINTS;

    companion object {
        /** 存档读取：老档没有 `currency` 键、或值不认识 → 一律当主货币（不抛异常、不 warn） */
        fun fromStored(raw: String?): MarketCurrency = fromId(raw) ?: PRIMARY

        /** 请求校验：非法值返回 null，**调用方必须拒绝该请求**（不要拿它的返回值当默认值用） */
        fun fromRequest(raw: String?): MarketCurrency? = fromId(raw)

        /** 大小写不敏感；不认识的值返回 null（不猜） */
        private fun fromId(raw: String?): MarketCurrency? =
            raw?.let { value -> entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
    }
}
