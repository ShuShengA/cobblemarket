package com.shusheng.cobblemarket.permission

import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandOutput
import net.minecraft.server.command.ServerCommandSource

/**
 * 管理权限节点门面（方案见 docs/permission_nodes_plan.md）。
 *
 * **服主制（2026-09-23）**：服主名单（配置项 `serverOwners`，只存 UUID）是**唯一恒真的超级权限**；
 * 名单里的人还独享「权限管理」与「服务器配置」两个界面。**市场里不认 OP 等级** ——
 * 任何 OP 都能用 `/op` 把权限传给别人，而服主名单只能从服务器控制台改，
 * 这就是「节点不可传递」的根基（OP 在别人服里可以照常当 OP，在市场里则与普通玩家无异）。
 *
 * 三类判定别混（混用会出荒谬结果）：
 * - [has] —— **管理职责**：黑名单/封禁/价格限制/下架/市场开关…「谁管哪块」。服主恒真，其余查授予表
 * - [isServerOwner] —— **服主身份**：权限管理、服务器配置、撤销坏账（服主干预手段）、
 *   IP 欠款上限豁免、同对交易豁免、卡片自删豁免、只发服主的告警。**不随节点授予变化**
 * - [canManage] —— **入口判定**：能否进管理面板（**OP 保留入口**，进面板后一切按各自节点判，
 *   2026-09-23 用户拍板：这样 OP 才看得到「权限管理/服务器配置」两个置灰按钮与它们的悬停教学）
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

    /**
     * 金融管理：卡片管理、全服借贷流水。
     * ⚠ **撤销坏账不在此列** —— 那是服主干预手段，只认服主（[isServerOwner]），2026-09-12 用户明确纠正过
     */
    const val FINANCE = PREFIX + "finance"

    /** 强制取消拍卖 */
    const val AUCTION = PREFIX + "auction"

    /** 强制取消求购单 */
    const val BUY_ORDER = PREFIX + "buyorder"

    /**
     * 全部管理节点（快照、入口判定、`/market perm list` 与权限编辑界面的按钮都用它）。
     * **顺序 = 管理面板按钮的排列顺序**（权限编辑界面按此顺序交替分列，两列四行，方便一眼对照
     * 管理面板里按钮的位置）。改这里的顺序会同时改掉界面按钮与 list 输出的顺序。
     *
     * ⚠ 原 `CONFIG`（服务器配置）节点已于 2026-09-23 删除：服务器配置改为**服主专属**，
     * 不再作为可授予的节点 —— 想放权就把对方 UUID 加进 `serverOwners`。
     */
    val ALL = listOf(MARKET, BAN, BLACKLIST, PRICE_LIMIT, AUCTION, BUY_ORDER, FINANCE)

    /**
     * 是不是服主（**唯一恒真的超级权限**）：UUID 在配置的 `serverOwners` 名单里。
     * ⚠ 名单为空 = 谁都不是服主（安全默认）。服务端判定；客户端读 `ClientPermissionCache`。
     */
    fun isServerOwner(player: PlayerEntity?): Boolean =
        player != null && CobbleMarketConfig.isServerOwner(player.uuid)

    /**
     * 管理操作鉴权（**服务端**判定）：服主恒真，其余查授予表。
     * ⚠ **不再认 OP 等级**：OP 可被任何 OP 传递，认它等于把整套节点作废。
     * ⚠ 客户端**不要**调这个 —— 客户端玩家没有 `server` 实例，永远返回 false；
     * 客户端一律读 `ClientPermissionCache`（服务端下发的快照）。
     */
    fun has(player: PlayerEntity?, node: String): Boolean {
        if (player == null) return false
        if (isServerOwner(player)) return true       // 服主恒真（唯一恒真的超级权限）
        val server = player.server ?: return false
        return node in PermissionState.get(server).getNodes(player.uuid)
    }

    /**
     * 命令源版本：玩家实体走 [has]，非玩家源**只认服务器控制台**。
     *
     * ⚠ 三条历史坑，改这里之前先读：
     * 1. **别写「非玩家一律放行」**：`/execute as @e[type=pig]` 会让 entity 变成非玩家实体，
     *    那样非 OP 借它就能绕过判定
     * 2. **别借命令源自身的权限等级**（`source.hasPermissionLevel(2)`）：那是改造前的写法，
     *    OP 用 `/execute as @e[type=pig]` 即可绕过整套节点（`execute` 保留原 source 的等级）
     * 3. **别用 `entity == null` 判控制台**：命令方块执行时 entity 同样是 null，
     *    OP 在游戏里放个命令方块就能提权 —— 只能用 `output` 类型判（见 [isServerSource]）
     */
    fun has(source: ServerCommandSource, node: String): Boolean {
        val entity = source.entity
        return when {
            entity is PlayerEntity -> has(entity, node)
            entity != null -> false          // /execute as <非玩家实体>：不借命令源等级放行
            else -> isConsole(source)        // entity == null：控制台 / 命令方块 / RCON，只放行控制台
        }
    }

    /**
     * `ServerCommandSource` 里那个 `CommandOutput` 字段（= 命令源背后的实际输出目标）。
     *
     * ⚠ **只能反射**：该字段是 `private` 且 MC **没有提供 getter**（1.21.1 实测，
     * `AbstractServerCommandSource` 也没有暴露），而它又是区分「控制台 / 命令方块 / RCON」
     * 的唯一判据 —— 三者在 `entity`、`name` 上都可能一模一样。
     *
     * ⚠ **按字段类型定位，不按字段名**：Fabric 与 NeoForge 的 mappings 不同，字段名可能是
     * `output`（yarn）也可能是别的；`CommandOutput` 类型字段全场只有一个，按类型找跨平台都稳。
     */
    private val OUTPUT_FIELD: java.lang.reflect.Field? = run {
        try {
            ServerCommandSource::class.java.declaredFields
                .firstOrNull { CommandOutput::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }
                ?: null.also {
                    CobbleMarket.LOGGER.warn(
                        "ServerCommandSource has no CommandOutput field; console detection disabled " +
                            "(server-console-only commands will be rejected — edit config/cobblemarket.json by hand instead)"
                    )
                }
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to resolve ServerCommandSource output field: {}", e.message)
            null
        }
    }

    /**
     * 是不是服务器控制台 —— `output` 类型是唯一可靠判据：
     *
     * | 发起方 | `output` 类型 | |
     * |---|---|---|
     * | 服务器控制台 | `MinecraftServer` | ✅ |
     * | 命令方块 | `CommandBlockBlockEntity` | ❌ |
     * | 玩家 | `ServerPlayerEntity` | ❌ |
     * | RCON | `RconCommandOutput` | ❌ 不放行（服主一定有控制台，少一个入口少一类绕过） |
     *
     * ⚠ **别改成 `source.entity == null`**：命令方块同为 null（见 [has] 第 3 条）
     * ⚠ 反射拿不到字段时**返回 false**（拒绝）：宁可让服主改用配置文件，
     *   也不能让命令方块提权成功 —— 这是安全判定，失败方向必须是"拒"
     */
    fun isConsole(source: ServerCommandSource): Boolean {
        val field = OUTPUT_FIELD ?: return false
        val output = try {
            field.get(source)
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to read command source output: {}", e.message)
            return false
        }
        return output === source.server
    }

    /**
     * 命令源是不是服主 —— 玩家看名单，**非玩家只认控制台**（控制台 = 服主本人，是他的逃生舱：
     * `/market owner add` 之外，控制台也能直接跑管理命令）。
     *
     * 用于 `/market perm`、`/market loan clear`、`/market owner` 的 `requires`。
     */
    fun isServerOwner(source: ServerCommandSource): Boolean {
        val entity = source.entity
        return when {
            entity is PlayerEntity -> isServerOwner(entity)
            entity != null -> false
            else -> isConsole(source)
        }
    }

    /**
     * 入口判定：能否进管理面板（**OP 或** 服主 或 拥有任一管理节点）。
     *
     * ⚠ **OP 保留入口是有意的**（2026-09-23 用户拍板）：OP 进得去，但面板里每个按钮都按各自
     * 节点置灰，包括只认服主的「权限管理」「服务器配置」——他因此才看得到那两个按钮和它们的
     * 悬停教学（「让服主在控制台执行 /market owner add 你」）。把入口也砍掉，这套教学就没落点了。
     *
     * ⚠ 是「任一**管理**节点」而非「任一权限」—— 否则将来出现非管理类节点（只读开关、卡片映射）时，
     * 会冒出一个「点进去啥也干不了」的管理面板入口。
     * 管理面板内的各按钮再用各自节点的 [has] 单独判定显隐。
     */
    fun canManage(player: PlayerEntity?): Boolean {
        if (player == null) return false
        if (player.hasPermissionLevel(2)) return true    // OP 保留入口（见上方 ⚠）
        if (isServerOwner(player)) return true
        val server = player.server ?: return false
        return PermissionState.get(server).getNodes(player.uuid).isNotEmpty()
    }
}
