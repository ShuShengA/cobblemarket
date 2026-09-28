package com.shusheng.cobblemarket.util

/**
 * 「属性筛选」的解析与匹配 —— 宝可梦最多两个属性，所以玩家可以选 **1 个或 2 个**。
 *
 * 筛选值统一存成**裸属性名**、多个用 `|` 连接（如 `fire` / `fire|ghost`，最多 2 个）。
 * ⚠ `|` 只用于**存储与传输**；界面上显示的是 `+`（「属性：幽灵+火」，与悬停窗一致，
 *   用户 2026-09-28 明确否掉了 `/` —— 那读起来是"或"）。
 *
 * **匹配语义**（用户 2026-09-28 两轮定稿，别改）：
 * - **1 个**：主属性**或**副属性命中它就算 —— 这是筛选器原本的行为，必须保持
 * - **2 个**：主副属性的**集合精确相等**（「幽灵+火」只筛主副恰好是这两个的那只，
 *   **不是**"幽灵或火"），且**不分主副顺序**（「幽灵+火」与「火+幽灵」是同一只精灵的两种写法）
 *
 * ⚠ 调用方传进来的属性可能是**裸名**（`fire`）也可能是**翻译 key**（`cobblemon.type.fire`）——
 * [matches] 内部按 `substringAfterLast(".")` 统一规范化，两种写法等价（五个界面的存法不一致，
 * 这里兜住，别再要求调用方统一）。
 */
object TypeFilter {

    /** 最多能选几个属性（宝可梦最多两个属性）—— 选满后再点第 3 个由调用方给失败音 */
    const val MAX_PICKS = 2

    /** 解析：`fire|ghost` → `["fire", "ghost"]`（空串 → 空表；去重、保持顺序） */
    fun keys(filter: String): List<String> =
        filter.split('|').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 选中集合 → 存进 `typeFilter` 的字符串（顺序 = 玩家的点击顺序，显示也照这个顺序） */
    fun join(keys: List<String>): String = keys.joinToString("|")

    /**
     * 点一下某个属性：已选 → 取消；未选 → 加上；**已满 [MAX_PICKS] 个还想加第 3 个 → 返回 null**
     * （调用方据此给失败音且不改状态 —— 用户明确要求"不能选 3 个"）。
     */
    fun toggle(current: List<String>, key: String): List<String>? = when {
        // ⚠ 候选列表第一项是「不限」，它的 key 是**空串** ⇒ 语义是**清空全部选择**
        //   （不能当成"加一个空名字"，那会拼出 `fire|` 这种值）
        key.isEmpty() -> emptyList()
        key in current -> current - key
        current.size >= MAX_PICKS -> null
        else -> current + key
    }

    /**
     * 这只精灵是否命中筛选。[primary] / [secondary] 传裸名或翻译 key 都行（见类注释）；
     * [filter] 为空 = 不限 ⇒ 恒真。
     */
    fun matches(primary: String, secondary: String, filter: String): Boolean {
        val want = keys(filter)
        if (want.isEmpty()) return true
        val have = setOfNotNull(norm(primary), norm(secondary))
        return if (want.size == 1) want[0] in have else have == want.toSet()
    }

    /** 取属性名：`cobblemon.type.fire` → `fire`；空串 → null（该侧没有属性） */
    private fun norm(raw: String): String? {
        if (raw.isBlank()) return null
        return raw.substringAfterLast('.').lowercase()
    }
}
