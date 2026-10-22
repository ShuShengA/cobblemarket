package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.network.PointMarketNetwork
import com.shusheng.cobblemarket.platform.onUseEntity
import net.minecraft.entity.Entity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * **自定义商店的 NPC 入口**：玩家右键一个绑定过商店的实体 → 服务端校验 → 打开对应商店。
 *
 * 设计口径（2026-10-20 用户拍板）：
 * - **不生成我们自己的 NPC**，只绑定服务器现有实体（Easy NPC / Impactor / 原版村民…都行）
 * - **取消实体默认交互**：返回 true 让平台层回 SUCCESS / 取消事件 —— 不然 NPC 自己的对话
 *   会和商城界面一起弹（或者先弹它、商城永远打不开）
 * - 一个实体**只服务一个商店**（绑定时的规则见 [CustomShopState.bind]）
 * - 距离：与购买侧同一个上限（8 格）；右键本来就在交互距离内，这里防的是数据异常 / 手改存档
 *
 * ⚠ 只在**服务端**执行业务：客户端侧的平台回调也会到（`player` 是 ClientPlayerEntity）
 *   ⇒ 这里的 `as? ServerPlayerEntity` 直接返回 false，界面由服务端发包打开。
 */
object CustomShopInteraction {

    /** 与 [PointMarketService] 的 `MAX_SHOP_DISTANCE` 同值（那边是 private；改这里时同步改那边） */
    private const val MAX_OPEN_DISTANCE = 8.0

    fun register() {
        onUseEntity { player, entity -> handle(player, entity) }
    }

    /**
     * @return true = 这次右键由本模组处理（平台层要取消实体默认交互）
     */
    private fun handle(player: ServerPlayerEntity, entity: Entity): Boolean {
        // 旁观者不拦：让原版/其它模组的旁观交互照旧（他们本来也买不了）
        if (player.isSpectator) return false
        // 玩家实体不能当商店 NPC（绑定时也排除了；这里防手改存档）
        if (entity is ServerPlayerEntity) return false
        val shop = CustomShopState.get(player.server).shopForNpc(entity.uuid) ?: return false

        if (!shop.enabled) {
            player.sendMessage(
                Text.translatable("cobblemarket.shop.closed", shop.name).formatted(Formatting.RED),
                false
            )
            return true
        }
        if (entity.squaredDistanceTo(player) > MAX_OPEN_DISTANCE * MAX_OPEN_DISTANCE) {
            // 正常右键到不了这里（交互距离只有几格）；留一句明确的提示，免得"点了没反应"
            player.sendMessage(
                Text.translatable("cobblemarket.shop.too_far").formatted(Formatting.RED),
                false
            )
            return true
        }
        PointMarketNetwork.openCustomShop(player, shop)
        return true
    }
}
