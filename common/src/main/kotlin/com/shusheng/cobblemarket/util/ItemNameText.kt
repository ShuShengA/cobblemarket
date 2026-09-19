package com.shusheng.cobblemarket.util

import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.DynamicRegistryManager
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * 服务端聊天消息里的**物品名取色**：与物品栏悬停显示的名字颜色同口径（稀有度色）。
 *
 * 客户端那份在 `client/util/TextUtil`（`rarityColoredName` / `rarityNameColor`），但聊天消息的 Text
 * 是**服务端**构造的（见 network 包），服务端源集引用不到 client 类 —— 所以这里再实现一份。
 * 口径必须一致：原版 tooltip 首行就是名字套 `stack.rarity.formatting`（已在客户端侧核对 1.21.1 字节码）。
 *
 * 名字自带样式（卡片那类）优先于稀有度色 —— 靠的是「子节点显式样式优先于父节点」，
 * 与客户端实现同样是 `name.copy().formatted(...)`。
 *
 * 精灵名不走这里，走 [TypeTextColors]（属性色）。
 */
object ItemNameText {

    /** 稀有度色（int）；拿不到栈时白色 —— 与客户端 `TextUtil.rarityNameColor` 同口径 */
    fun rarityColor(stack: ItemStack?): Int = stack?.rarity?.formatting?.colorValue ?: 0xFFFFFF

    /** 物品名（含自定义名 / 自带样式）+ 稀有度色；[stack] 为 null 时返回 null，由调用方兜底 */
    fun rarityColoredName(stack: ItemStack?): Text? =
        stack?.let { it.name.copy().formatted(it.rarity.formatting) }

    /**
     * 从完整栈 NBT 重建后取名（**首选**：能带上自定义名与组件带来的稀有度 —
     * 挂单存的 itemNbt 就是完整栈，重建口径与 `ItemMarketState` 的返还逻辑一致）。
     * 重建不出物品时返回 null，由调用方兜底。
     */
    fun rarityColoredNameOfNbt(registries: DynamicRegistryManager, nbt: NbtCompound?): Text? {
        val stack = nbt?.let { ItemStack.fromNbtOrEmpty(registries, it) } ?: return null
        return if (stack.isEmpty) null else rarityColoredName(stack)
    }

    /**
     * 只有 itemId、拿不到完整栈时的兜底：用物品默认栈取稀有度。
     * id 非法 / 解析不出物品时返回 null，由调用方兜底（通常是 `Text.literal(itemId)` 原样显示）。
     */
    fun rarityColoredNameById(itemId: String): Text? {
        val item = Identifier.tryParse(itemId)?.let { Registries.ITEM.get(it) } ?: return null
        if (item == net.minecraft.item.Items.AIR) return null
        return Text.translatable(item.translationKey).styled { it.withColor(rarityColor(item.defaultStack)) }
    }
}
