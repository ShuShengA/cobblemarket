package com.shusheng.cobblemarket.permission

import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.command.ServerCommandSource

/**
 * 管理权限节点门面（方案见 docs/permission_nodes_plan.md）。
 *
 * **S0（当前）**：内部仍只查 OP 等级 —— 行为与裸 `hasPermissionLevel(2)` **完全一致**，
 * 只是把散落全项目的判定收成一个口子（零协议改动、可回归验证）。
 * **S1**：接入自建授予表 + 客户端快照，届时**只改这个文件**，59 处调用点一行都不用动。
 *
 * ⚠ 两类判定别混（2026-09-12 分类时发现，混用会出荒谬结果）：
 * - [has] —— **管理职责**：黑名单/封禁/价格限制/下架/配置… 「谁管哪块」，S1 起可授予
 * - [isOp] —— **OP 身份特权**：IP 欠款上限豁免、卡片自删豁免、只发给 OP 的告警。
 *   这些不是管理职责，**授予节点不该顺带获得**，永远只认 OP 等级
 */
object MarketPermissions {

    // ── 管理节点（中粒度：按功能域切，2026-09-12 用户拍板）──

    /** 节点名前缀：命令补全与界面显示用短名（去掉它），判定一律用全名 */
    const val PREFIX = "cobblemarket.admin."

    /** 精灵黑名单 + 物品黑名单 */
    const val BLACKLIST = PREFIX + "blacklist"

    /** 价格限制（精灵 / 物品） */
    const val PRICE_LIMIT = PREFIX + "pricelimit"

    /** 封禁 / 解封 / 封禁名单 */
    const val BAN = PREFIX + "ban"

    /** 管理端下架精灵与物品 + 市场总开关（紧急停市/恢复） */
    const val MARKET = PREFIX + "market"

    /** 金融管理：卡片管理、全服借贷流水、撤销坏账 */
    const val FINANCE = PREFIX + "finance"

    /** 强制取消拍卖 */
    const val AUCTION = PREFIX + "auction"

    /** 强制取消求购单 */
    const val BUY_ORDER = PREFIX + "buyorder"

    /** 服务器配置编辑 + `/market reload` */
    const val CONFIG = PREFIX + "config"

    /**
     * 全部管理节点（快照、入口判定、`/market perm list` 与权限编辑界面的按钮都用它）。
     * **顺序 = 管理面板按钮的排列顺序**（权限编辑界面按此顺序交替分列，两列四行，方便一眼对照
     * 管理面板里按钮的位置）。改这里的顺序会同时改掉界面按钮与 list 输出的顺序。
     */
    val ALL = listOf(MARKET, BAN, BLACKLIST, PRICE_LIMIT, AUCTION, BUY_ORDER, FINANCE, CONFIG)

    /**
     * 管理操作鉴权（**服务端**判定）：OP 恒真，否则查授予表。
     * ⚠ 客户端**不要**调这个 —— 客户端玩家没有 `server` 实例，永远返回 false；
     * 客户端一律读 `ClientPermissionCache`（服务端下发的快照）。
     */
    fun has(player: PlayerEntity?, node: String): Boolean {
        if (player == null) return false
        if (player.hasPermissionLevel(2)) return true       // OP 恒真（零配置、向后兼容）
        val server = player.server ?: return false
        return node in PermissionState.get(server).getNodes(player.uuid)
    }

    /**
     * 命令源版本：玩家实体走节点判定，其余（控制台 / 命令方块 / 无执行者）**沿用命令源自身的权限等级**
     * —— 与改造前的 `ServerCommandSource.hasPermissionLevel(2)` 逐字等价，S0 行为零变化。
     * ⚠ 别写成「非玩家一律放行」：`/execute as @e[type=pig]` 会让 entity 变成非玩家实体，
     * 那样非 OP 就能借它绕过判定。
     */
    fun has(source: ServerCommandSource, node: String): Boolean {
        val entity = source.entity
        return if (entity is PlayerEntity) has(entity, node) else source.hasPermissionLevel(2)
    }

    /**
     * 入口判定：能否进管理面板（OP 或拥有**任一管理节点**）。
     * ⚠ 是「任一**管理**节点」而非「任一权限」—— 否则将来出现非管理类节点（只读开关、卡片映射）时，
     * 会冒出一个「点进去啥也干不了」的管理面板入口。
     * 管理面板内的各按钮再用各自节点的 [has] 单独判定显隐。
     */
    fun canManage(player: PlayerEntity?): Boolean {
        if (player == null) return false
        if (player.hasPermissionLevel(2)) return true
        val server = player.server ?: return false
        return PermissionState.get(server).getNodes(player.uuid).isNotEmpty()
    }

    /**
     * OP 身份特权（豁免类判定）：**永远只认 OP 等级**，不随节点授予变化。
     * 用于：IP 欠款上限豁免、同对交易豁免、卡片自删豁免、只发 OP 的告警。
     */
    fun isOp(player: PlayerEntity?): Boolean =
        player?.hasPermissionLevel(2) == true
}
