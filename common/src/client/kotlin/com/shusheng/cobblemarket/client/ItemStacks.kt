package com.shusheng.cobblemarket.client

import net.minecraft.client.MinecraftClient
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.StringNbtReader
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier

/**
 * 从「物品 ID + 可选完整 NBT」重建**图标用**的物品栈。
 *
 * **优先走 NBT**：附魔、自定义名称、染色等都在物品组件里，只按 itemId 建会得到「光板物品」——
 * 附魔书显示成普通书、改名物品显示成本名。旧数据没存 NBT（或解析失败）时退回按 itemId 建的空栈。
 *
 * 2026-09-12：拍卖场与管理端拍卖的列表行、详情弹窗此前一律只按 itemId 建（4 处），统一改走这里。
 * 其余界面（物品市场、待领取、管理端物品、求购单交付）本就用的 `fromNbtOrEmpty`，无需改。
 */
fun iconStackOf(itemId: String, itemNbt: NbtCompound?): ItemStack? {
    itemNbt?.let { nbt ->
        MinecraftClient.getInstance().world?.registryManager?.let { rm ->
            val built = ItemStack.fromNbtOrEmpty(rm, nbt)
            if (!built.isEmpty) return built
        }
    }
    return Identifier.tryParse(itemId)?.let { ItemStack(Registries.ITEM.get(it)) }
}

/**
 * 精灵**携带物**的图标栈：优先用完整 NBT 重建（附魔光效、自定义名称），
 * 旧数据没存 NBT（空串 / 解析失败）时退回按 ID 建的空栈。
 *
 * [heldItemNbt] 是服务端 `encodeHeldItemNbt` 写出的 SNBT 串，**只在携带物带组件时才非空**——
 * 简单携带物不传完整 NBT，省体积。2026-09-12 之前只传 ID，附魔/改名的携带物图标显示成光板。
 */
fun heldIconStack(extra: Map<String, String>): ItemStack? =
    heldIconStack(extra["heldItemId"].orEmpty(), extra["heldItemNbt"])

/**
 * **模型专用**的携带物栈：玩家在 Cobblemon 队伍详情里把携带物设成「隐藏」（`heldItemVisible` = false）时
 * 返回空栈 —— 模型手里就不拿东西。
 *
 * ⚠ **只给渲染精灵模型用**（各处 `RenderablePokemon(...)` 的第三个参数 + 庆祝动画）。
 *   行信息 / 弹窗 / 行内小图标 / 悬停大图一律照常走 [heldIconStack]（用户 2026-09-26 拍板：
 *   隐藏是「不渲染在模型上」、不是「没带东西」，交易信息不该跟着一起消失）。
 */
fun heldIconStackForModel(extra: Map<String, String>): ItemStack =
    if (extra["heldItemVisible"] == "false") ItemStack.EMPTY else heldIconStack(extra) ?: ItemStack.EMPTY

fun heldIconStackForModel(visible: Boolean, heldItemId: String, heldItemNbt: String?): ItemStack =
    if (!visible) ItemStack.EMPTY else heldIconStack(heldItemId, heldItemNbt) ?: ItemStack.EMPTY

fun heldIconStack(heldItemId: String, heldItemNbt: String?): ItemStack? {
    heldItemNbt?.takeIf { it.isNotEmpty() }?.let { snbt ->
        runCatching { StringNbtReader.parse(snbt) }.getOrNull()?.let { nbt ->
            MinecraftClient.getInstance().world?.registryManager?.let { rm ->
                val built = ItemStack.fromNbtOrEmpty(rm, nbt)
                if (!built.isEmpty) return built
            }
        }
    }
    if (heldItemId.isEmpty()) return null
    val heldId = Identifier.tryParse(heldItemId) ?: return null
    val heldItem = Registries.ITEM.get(heldId)
    return if (heldItem != Registries.ITEM.get(Identifier.of("minecraft", "air"))) ItemStack(heldItem) else null
}
