package com.shusheng.cobblemarket.network

import net.minecraft.item.ItemStack
import net.minecraft.registry.RegistryWrapper

/**
 * 精灵携带物的完整 NBT（SNBT 串），供客户端重建携带物图标。
 *
 * **只在携带物带组件（附魔、自定义名称、染色等）时才有值**，简单携带物返回空串——
 * 那种情况客户端按物品 ID 建图标即可，不值得为它多传一份完整物品 NBT。
 *
 * 背景：此前只传 `heldItemId`，客户端按 ID 建栈会得到「光板物品」，
 * 附了魔的携带物看不到附魔光效、改了名的看不到自定义名（2026-09-12 修）。
 */
internal fun encodeHeldItemNbt(stack: ItemStack, registryManager: RegistryWrapper.WrapperLookup): String =
    if (stack.isEmpty || stack.components.isEmpty) "" else stack.encode(registryManager).toString()
