package com.shusheng.cobblemarket.util

import net.minecraft.item.ItemGroup
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import net.minecraft.world.World
import org.slf4j.LoggerFactory

/**
 * 服务端按**创造模式分类 ID** 解析出该分类包含哪些物品（物品市场「分类」筛选用）。
 *
 * 为什么在服务端做：市场列表是**服务端分页**的，筛选必须在服务端发生；而客户端**不能**把分类里的
 * 物品 ID 列表发过来 —— 一个分类动辄几百上千个物品（「建筑方块」600+），既撑爆
 * `MAX_ITEM_IDS`（256，当初为防带宽攻击设的上限），也把每个请求撑到几十 KB。
 * ⇒ 客户端只发**分类 ID**，服务端自己认（见 `MarketNetwork` 的 request_item_market 处理）。
 *
 * ⚠ 分类内容要靠 `updateEntries` 现算（原版只在**客户端**开创造栏时才做这件事，专用服务端从不做）——
 *   所以这里是"服务端第一次主动构建创造栏内容"。某个模组的分类生成器若碰了客户端专属的类就会抛异常，
 *   因此**整段包 try/catch**：那个分类退化成"筛不出任何东西"（并缓存空集，不反复重试），**不会崩服**。
 */
object ItemCategoryIndex {

    private val LOGGER = LoggerFactory.getLogger("CobbleMarket/ItemCategory")

    /**
     * 分类 ID → 该分类的物品 ID 集合。
     *
     * ⚠ 用 **ConcurrentHashMap**：单机里客户端（拍卖场是**客户端本地筛**的，也在用本函数）
     *   与集成服务端是**两个线程**共用这一个 JVM 静态对象，普通 HashMap 会被并发写坏。
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<Identifier, Set<String>>()

    /** 上次构建用的特性开关 —— 数据包可以开关原版特性（如收纳袋），变了内容就得重算 */
    @Volatile
    private var cachedFeatures: Any? = null

    /**
     * @return 该分类的物品 ID 集合；`null` = 这个 ID 不是有效分类（未知 ID / 不是 CATEGORY 型），
     *         调用方应当**忽略这条筛选**（照"不限"处理），别把结果清空
     */
    fun itemIds(world: World, id: Identifier): Set<String>? {
        val features = world.enabledFeatures
        if (features !== cachedFeatures) {
            cache.clear()
            cachedFeatures = features
        }
        cache[id]?.let { return it }

        val group = Registries.ITEM_GROUP.get(id) ?: return null
        if (group.type != ItemGroup.Type.CATEGORY) return null

        val ids = try {
            // ⚠ operatorEnabled 传 **false**：项目铁律「模组内 OP 一律按普通玩家对待」——
            //   不能让"有 OP 的分类内容"成为筛选口径的一部分（命令方块那类不该在这出现）
            group.updateEntries(ItemGroup.DisplayContext(features, false, world.registryManager))
            group.displayStacks.mapNotNull { stack ->
                if (stack.isEmpty) null else Registries.ITEM.getId(stack.item)?.toString()
            }.toSet()
        } catch (t: Throwable) {
            // 某个模组的分类生成器炸了：只记一次（空集进缓存），这个分类筛不出东西，但服务器照常跑
            LOGGER.warn("无法构建创造分类 '{}' 的物品列表，该分类筛选将不可用", id, t)
            emptySet<String>()
        }
        cache[id] = ids
        return ids
    }
}
