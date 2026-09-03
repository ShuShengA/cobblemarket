package com.shusheng.cobblemarket.finance

import net.minecraft.entity.ItemEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.server.MinecraftServer

/**
 * 喵喵紫卡（批次 7.5）：高额度凭证物品。
 * 额度绑定 FinanceState 持有者状态而非物品——刷物品 bug 复制出的卡没有额外额度；
 * 物品不可堆叠；丢弃到地面后由服务器扫描立即消失（回喵喵银行补发）。
 */
class MeowthPurpleCardItem : Item(Settings().maxCount(1).fireproof()) {

    companion object {
        /** 判断栈是否紫卡（用于地面实体扫描） */
        fun isCard(stack: ItemStack): Boolean = stack.item is MeowthPurpleCardItem

        /** 全服扫描：地面上的紫卡实体立即删除（丢弃即消失；持有者可从喵喵银行补发凭证） */
        fun scanAndDiscardDroppedCards(server: MinecraftServer) {
            server.worlds.forEach { world ->
                world.iterateEntities().forEach { entity ->
                    if (entity is ItemEntity && isCard(entity.stack)) {
                        entity.discard()
                    }
                }
            }
        }
    }
}
