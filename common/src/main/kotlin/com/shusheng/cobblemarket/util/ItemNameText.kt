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

    /**
     * **球种行**：「球种：」标签 + 球名（**按稀有度色** —— 大师球在物品栏里是紫的（EPIC），
     * 直接念词条出来是白字）。**三处共用这一个**：精灵悬停窗 / 拍卖播报的聊天悬停 / 展示到聊天。
     *
     * ⚠ **各写一份就会出「同一只精灵，悬停窗里球名是紫的、聊天里是白的」** ——
     * 2026-09-20 用户实测过一次（当时只修了悬停窗那条路），2026-09-25 又发现拍卖播报与
     * 展示这两条路没跟上，所以这次收到这里来，别再各拼各的。
     *
     * ⚠ 标签不设色（吃行色）、球名自带色，两者**平级 append** —— 别让带色的当父节点
     *（见 CLAUDE.md 的界面文字约定）。
     *
     * @param ballKey    球名的**词条 key**（`item.<ns>.<path>`）；id 解析不出来时用它兜底
     * @param ballItemId 球的 **item id**（`<ns>:<path>`），拿它取稀有度色
     */
    fun ballLine(ballKey: String, ballItemId: String): Text =
        Text.literal("")
            .append(Text.translatable("cobblemarket.gui.tooltip_ball"))
            .append(rarityColoredNameById(ballItemId) ?: Text.translatable(ballKey))
}
