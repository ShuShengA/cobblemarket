package com.shusheng.cobblemarket.market

import java.util.UUID

/**
 * 对战状态守卫：玩家对战中时禁止上架/拍卖/交付（整个队伍，照「真实放生」模组同款保守拦截）。
 * Cobblemon 对战时精灵仍留在玩家队伍里，战斗系统动态读队伍——抽走任意一只都会出问题：
 * 参战精灵被抽走 → 战斗内模型消失但技能可用；非参战精灵被抽走 → 战斗切换面板仍能切出
 * 已上架的精灵继续战斗（变相复制）。一律拦截整个队伍的交易操作最安全。
 */
object BattleGuard {

    /** 玩家是否正在对战中（参与任意一场战斗） */
    fun isPlayerInBattle(playerUuid: UUID): Boolean =
        com.cobblemon.mod.common.battles.BattleRegistry
            .getBattleByParticipatingPlayerId(playerUuid) != null
}
