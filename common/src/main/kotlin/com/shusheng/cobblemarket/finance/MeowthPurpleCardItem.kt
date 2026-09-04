package com.shusheng.cobblemarket.finance

import net.minecraft.entity.Entity
import net.minecraft.entity.ItemEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.world.World

/**
 * 喵喵紫卡（批次 7.5）：高额度凭证物品。
 * 额度绑定 FinanceState 持有者状态而非物品——刷物品 bug 复制出的卡没有额外额度；
 * 物品不可堆叠；丢弃到地面后由服务器扫描快速消失（回喵喵银行补发）；
 * 非持有者背包里的卡自检自删（inventoryTick 每 tick 主动执行，不耗服务器全局扫描）。
 */
class MeowthPurpleCardItem : Item(Settings().maxCount(1).fireproof()) {

    override fun inventoryTick(stack: ItemStack, world: World, entity: Entity, slot: Int, selected: Boolean) {
        if (world.isClient) return
        if (entity !is ServerPlayerEntity) return
        // 非持有者（OP 豁免：服主测试/管理不受限）背包里的卡自删——凭证只属于持有者
        val player = entity as ServerPlayerEntity
        if (player.hasPermissionLevel(2)) return
        val state = FinanceState.get(player.server)
        if (state.isPurpleCardHolder(player.uuid)) return
        stack.setCount(0)
        player.sendMessage(
            Text.translatable("cobblemarket.card.not_holder_removed").formatted(Formatting.RED),
            false
        )
    }

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
    }
}
