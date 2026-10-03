package com.shusheng.cobblemarket.client

import net.minecraft.item.ItemGroup
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * 创造模式物品分类（原版创造栏顶上那一排 tab）—— 物品市场 / 拍卖场物品 tab 的「分类」筛选用。
 *
 * ⚠ **不列 `ItemGroups.getGroupsToDisplay()`**：它内部是 `shouldDisplay()`，而分类型 tab 的
 *   `shouldDisplay()` 是 `hasStacks()`（**内容为空就不显示**），内容又得先 `updateEntries` 才填得出来
 *   ⇒ 等于要在客户端把**所有分类**都构建一遍（整合包下几十毫秒的卡顿），而这里只需要图标和名字。
 *   直接列 `Registries.ITEM_GROUP` 再按 `type == CATEGORY` 过滤即可。
 *   （`CATEGORY` 之外的三个是 背包 / 快捷栏 / 搜索，不是分类，排除）
 *
 * 注册表在启动后就冻结了，**模组注册的分类本来就在里面** ⇒ 只构建一次、永久缓存。
 * 服务端不认识模组？不 —— 服务端认的是同一个分类 ID（见 `util/ItemCategoryIndex`）。
 */
object ItemCategories {

    /** 一个分类：id（发给服务端识别）+ 显示名（悬停提示）+ 图标（格子上那个物品图） */
    data class Category(val id: Identifier, val name: Text, val icon: ItemStack)

    private var cache: List<Category> = emptyList()
    private var built = false

    /** 全部分类型 tab，按注册顺序（原版在前、模组的按注册顺序接在后面） */
    fun all(): List<Category> {
        if (built) return cache
        built = true
        cache = try {
            // ⚠ 用 Kotlin 原生集合（Registry 本身就是 Iterable）：Java 的 `Stream` 没有 mapNotNull，
            //   而 `getId` 是**可空**的（@Nullable），得靠 mapNotNull 挡掉
            Registries.ITEM_GROUP
                .filter { it.type == ItemGroup.Type.CATEGORY }
                .mapNotNull { group ->
                    val id = Registries.ITEM_GROUP.getId(group) ?: return@mapNotNull null
                    Category(id, group.displayName, group.icon)
                }
        } catch (_: Throwable) {
            // 注册表没就绪（理论上不会）：退化成空列表 —— 网格画不出来，但界面照常能用
            emptyList()
        }
        return cache
    }
}
