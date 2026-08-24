package com.shusheng.cobblemarket.market

import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.network.isEggItem
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.registry.DynamicRegistryManager
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.text.Text

/**
 * 容器物品内容校验：黑名单 / 价格限制 / 蛋交易开关对容器内物品同样生效，
 * 防止玩家把受限物品装进潜影箱等容器绕过治理。
 *
 * 容器识别两条路：
 * 1. 原版 `Container` 数据组件（1.20.5+ 标准：潜影箱/收纳袋等；遵标准的模组容器同样覆盖）
 * 2. `BlockEntityTag.Items` 老格式兼容扫描（1.20.4- 容器结构，大量模组沿用）
 * 完全自定义存储格式的模组容器不在覆盖内——服主可把该容器物品本身加黑名单兜底。
 *
 * 只查内容物 itemId（与黑名单/价格限制既有的 itemId 粒度一致）；挂单物品自身的
 * 检查由各入口现有逻辑负责，本对象只负责容器内部。
 */
object ContainerTradeCheck {

    /** 递归深度上限：防嵌套容器套娃导致过深解析（超限直接拒绝，安全侧）。 */
    private const val MAX_DEPTH = 8

    /** 校验容器内容；返回 null=通过，返回拒绝原因 Text=应拒绝。 */
    fun check(stack: ItemStack, server: MinecraftServer): Text? =
        checkStack(stack, server.registryManager, server, 0)

    private fun checkStack(
        stack: ItemStack,
        registry: DynamicRegistryManager,
        server: MinecraftServer,
        depth: Int,
    ): Text? {
        if (depth > MAX_DEPTH) {
            return Text.translatable("cobblemarket.blacklist.container_too_deep")
        }
        // 原版标准：Container 组件
        stack.get(DataComponentTypes.CONTAINER)?.let { container ->
            container.iterateNonEmpty().forEach { inner ->
                checkItemId(Registries.ITEM.getId(inner.item).toString(), server)?.let { return it }
                checkStack(inner, registry, server, depth + 1)?.let { return it }
            }
        }
        // 模组老格式兼容：BlockEntityTag.Items（仅递归 Items 列表，老格式嵌套容器
        // 在子项 tag.BlockEntityTag 里，见 checkLegacyItems）
        stack.get(DataComponentTypes.BLOCK_ENTITY_DATA)?.let { beData ->
            checkLegacyItems(beData.nbt, server, depth + 1)?.let { return it }
        }
        return null
    }

    /** 老格式 Items 列表：每项 {Slot,id,Count,tag}，只取 id 查治理、tag.BlockEntityTag 递归。 */
    private fun checkLegacyItems(nbt: NbtCompound, server: MinecraftServer, depth: Int): Text? {
        if (depth > MAX_DEPTH) {
            return Text.translatable("cobblemarket.blacklist.container_too_deep")
        }
        if (!nbt.contains("Items", NbtElement.LIST_TYPE.toInt())) return null
        val items = nbt.getList("Items", NbtElement.COMPOUND_TYPE.toInt())
        for (i in 0 until items.size) {
            val entry = items.getCompound(i)
            val id = entry.getString("id")
            if (id.isNotEmpty()) {
                checkItemId(id, server)?.let { return it }
            }
            // 老格式嵌套容器：子物品 tag.BlockEntityTag.Items 递归（无法识别的自定义结构跳过）
            val tag = entry.getCompound("tag")
            if (!tag.isEmpty && tag.contains("BlockEntityTag", NbtElement.COMPOUND_TYPE.toInt())) {
                checkLegacyItems(tag.getCompound("BlockEntityTag"), server, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun checkItemId(itemId: String, server: MinecraftServer): Text? {
        if (ItemBlacklistState.get(server).contains(itemId)) {
            return Text.translatable("cobblemarket.blacklist.container_item_blocked")
        }
        if (ItemPriceLimitState.get(server).getPriceBounds(itemId) != null) {
            return Text.translatable("cobblemarket.price_limit.container_item_blocked")
        }
        // 蛋交易开关：蛋塞容器同样算绕过，与直挂共用开关语义
        if (isEggItem(itemId) && !CobbleMarketConfig.eggTradingEnabled) {
            return Text.translatable("cobblemarket.network.egg_trading_disabled_container")
        }
        return null
    }
}
