package com.shusheng.cobblemarket.finance

import net.minecraft.entity.ItemEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.server.MinecraftServer
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * 喵喵紫卡（批次 7.5）：高额度凭证物品。
 * 额度绑定 FinanceState 持有者状态而非物品——刷物品 bug 复制出的卡没有额外额度；
 * 物品不可堆叠；丢弃到地面后由服务器扫描快速消失（回喵喵银行补发）；
 * 非持有者背包里的卡定期收回（凭证只属于持有者）。
 */
class MeowthPurpleCardItem : Item(Settings().maxCount(1).fireproof()) {

    companion object {
        /** 判断栈是否紫卡（用于地面实体扫描） */
        fun isCard(stack: ItemStack): Boolean = stack.item is MeowthPurpleCardItem

        /** 地面扫描：丢弃的紫卡实体快速消失（丢弃即消失；持有者可从喵喵银行补发凭证） */
        fun scanAndDiscardDroppedCards(server: MinecraftServer) {
            server.worlds.forEach { world ->
                world.iterateEntities().forEach { entity ->
                    if (entity is ItemEntity && isCard(entity.stack)) {
                        entity.discard()
                    }
                }
            }
        }

        /** 背包扫描：非持有者背包里的紫卡移除（不管从哪拿到的，凭证只属于持有者） */
        fun scanPlayerInventories(server: MinecraftServer) {
            val state = FinanceState.get(server)
            server.playerManager.playerList.forEach { player ->
                if (state.isPurpleCardHolder(player.uuid)) return@forEach
                val inv = player.inventory
                var removed = false
                for (i in 0 until inv.size()) {
                    if (isCard(inv.getStack(i))) {
                        inv.setStack(i, ItemStack.EMPTY)
                        removed = true
                    }
                }
                if (removed) {
                    inv.markDirty()
                    player.sendMessage(
                        Text.translatable("cobblemarket.card.not_holder_removed").formatted(Formatting.RED),
                        false
                    )
                }
            }
        }
    }
}
