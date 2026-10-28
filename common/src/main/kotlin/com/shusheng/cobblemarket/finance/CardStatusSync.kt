package com.shusheng.cobblemarket.finance

import net.minecraft.scoreboard.ScoreboardCriterion
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import java.util.UUID

/**
 * **把「持卡状态」同步给原版 Minecraft 可读的两条通道**（2026-10-29 用户需求）。
 *
 * 起因：卡片已经能带来官方商店折扣，服主还想把它用在**自己的玩法**里
 * （例：持卡免疫某维度的持续扣血、持卡才能进入某区域）。
 * 数据包**调不到模组 Java API**，所以必须落到原版认识的东西上：
 *
 * 1. **实体 tag**（推荐，命令 / 数据包直接用）：
 *    `cobblemarket_purple_card` / `cobblemarket_black_card`
 *    ```mcfunction
 *    execute as @a[tag=!cobblemarket_purple_card, tag=!cobblemarket_black_card] run damage @s 1 minecraft:magic
 *    ```
 * 2. **计分板** `cm_purple` / `cm_black` = 1/0（objective 名有 **16 字符上限**所以用短名）：
 *    ```mcfunction
 *    execute if score @s cm_purple matches 1 run ...
 *    ```
 *
 * ⚠ 语义 = **持卡名单**（`FinanceState.isPurpleCardHolder` / `isBlackCardHolder`），
 *   不是"背包里有卡物品"。黑卡持有者拿到卡时紫卡资格已被移除 ⇒ 两个值不会同时为 1。
 * ⚠ 只对**在线玩家**有意义（tag 跟着实体走；计分板值下线保留但上线时会重刷）。
 * ⚠ **只在"名单可能变化"的地方调**（申请 / 发放 / 收回 / 登录），不要放进 tick。
 */
object CardStatusSync {

    /**
     * 持卡实体的 tag。
     *
     * ⚠ **不能带冒号**：`@a[tag=cobblemarket:purple_card]` 会让选择器在 `:` 处解析失败、
     * 报「选项的方括号不成对」（2026-10-29 用户实测踩到）⇒ 用 `cobblemarket_` 下划线前缀
     * 避免与服主自己的 tag 撞名。
     */
    const val TAG_PURPLE = "cobblemarket_purple_card"
    const val TAG_BLACK = "cobblemarket_black_card"

    /**
     * 开发期用过的**带冒号**旧 tag（选择器引用不了，但会残留在玩家 NBT 里）——
     * `sync` 时顺手摘掉，免得玩家 `/tag list` 里挂着一个用不了的 tag。
     */
    private const val LEGACY_TAG_PURPLE = "cobblemarket:purple_card"
    private const val LEGACY_TAG_BLACK = "cobblemarket:black_card"

    /** 计分板 objective 名（≤16 字符；`cm_` = CobbleMarket） */
    const val SCORE_PURPLE = "cm_purple"
    const val SCORE_BLACK = "cm_black"

    /** 按 UUID 同步（不在线 = 什么都不做 —— 下次登录会补） */
    fun sync(server: MinecraftServer, uuid: UUID) {
        val player = server.playerManager.getPlayer(uuid) ?: return
        sync(server, player)
    }

    /** 把某个在线玩家的 tag 与计分板刷成当前存档值 */
    fun sync(server: MinecraftServer, player: ServerPlayerEntity) {
        val state = FinanceState.get(server)
        val purple = state.isPurpleCardHolder(player.uuid)
        val black = state.isBlackCardHolder(player.uuid)
        setTag(player, TAG_PURPLE, purple)
        setTag(player, TAG_BLACK, black)
        // 清开发期残留（见 LEGACY_TAG_* 的说明）
        player.removeCommandTag(LEGACY_TAG_PURPLE)
        player.removeCommandTag(LEGACY_TAG_BLACK)
        setScore(server, player, SCORE_PURPLE, purple, "CobbleMarket Purple Card")
        setScore(server, player, SCORE_BLACK, black, "CobbleMarket Black Card")
    }

    private fun setTag(player: ServerPlayerEntity, tag: String, on: Boolean) {
        if (on) player.addCommandTag(tag) else player.removeCommandTag(tag)
    }

    private fun setScore(
        server: MinecraftServer,
        player: ServerPlayerEntity,
        name: String,
        on: Boolean,
        displayName: String
    ) {
        val scoreboard = server.scoreboard
        // objective 只建一次（第一个玩家登录时）；之后复用
        val objective = scoreboard.getNullableObjective(name) ?: scoreboard.addObjective(
            name,
            ScoreboardCriterion.DUMMY,
            Text.literal(displayName),
            ScoreboardCriterion.RenderType.INTEGER,
            false,
            null
        )
        scoreboard.getOrCreateScore(player, objective).setScore(if (on) 1 else 0)
    }
}
