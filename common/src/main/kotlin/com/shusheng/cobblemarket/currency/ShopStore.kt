package com.shusheng.cobblemarket.currency

/**
 * **主 RP 商城的 shopId** —— 入口界面点进去的那个「RP 商城」。
 *
 * 自定义 NPC 商店从 `"custom_<id>"` 这类自己起的 id 走另一套存档（[CustomShopState]），
 * 但两边共用同一套服务层 / 网络层 / 界面，只靠这个字符串区分。
 * ⚠ 自定义商店的 id **不能**叫 `main`（[CustomShopState.create] 会拒绝），否则会串到主商城。
 */
const val MAIN_SHOP_ID = "main"

/**
 * **商城存储的通用接口** —— 主商城 [PointShopState] 与自定义商店 [CustomShop] 都实现它。
 *
 * 存在的理由：两边的格子语义完全一样（精灵 / 物品各一套编号、售罄格保留、-1 = 无限量），
 * 只是装在不同的存档里。服务层 [PointMarketService] 只认这个接口，
 * **购买 / 上架 / 库存 / 校验逻辑一份就够** —— 复制两份必然长出两套幂等与审计。
 *
 * ⚠ 方法语义与 [PointShopState] 上的同名函数**逐字一致**，改一边就要改另一边。
 */
interface ShopStore {

    /** 某个格子的商品；null = 空格子 */
    fun find(kind: String, index: Int): ShopEntry?

    /** 某个种类下的全部已上架格子（序号 → 商品） */
    fun all(kind: String): Map<Int, ShopEntry>

    /** 上架 / 覆盖某格（[kind] 由 [ShopEntry.kind] 决定，调用方已校验过两者一致） */
    fun put(kind: String, index: Int, entry: ShopEntry)

    /** 清空某格；返回是否真的删掉了 */
    fun remove(kind: String, index: Int): Boolean

    /**
     * 卖出 [amount] 份：库存 -amount；无限量不动。
     * 返回 false = **售罄或库存不足**（调用方必须拒绝交易，且此时**一分钱都不能扣**）。
     */
    fun consume(kind: String, index: Int, amount: Int = 1): Boolean

    /** 补货 / 改库存。`amount < 0` = 改成无限量 */
    fun setStock(kind: String, index: Int, amount: Int): Boolean

    /**
     * 某个种类的页数：按**该种类下最大已填序号**算，至少 1 页
     *（一件没上架也有一页空位等着服主填）。
     *
     * 例：填了 0~31 → 2 页（第 2 页全空，供继续添加）；填了 0~32 → 仍是 2 页。
     *
     * ⚠ **只按内容算**（"已填最大序号在第几页"）——**不给空页**。
     *   客户端那边对**有编辑权限的人**再多显示一页（见 `PointsMarketScreen.totalPages`）：
     *   他得能翻到空页去放货；普通玩家看到空页没意义，翻过去只能对着空白发呆
     *   （2026-09-27 用户定的口径）。
     *   ⚠ 服务端**没法**按人算：快照是**广播**给所有人的，只能客户端按自己的权限补。
     */
    fun pageCount(kind: String): Int {
        val maxIndex = all(kind).keys.maxOrNull() ?: return 1
        return maxIndex / PointShopState.POINTS_SHOP_PAGE_SIZE + 1
    }
}
