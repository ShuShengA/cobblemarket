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

    /** 挂单管理：管理端查看 / 下架任意玩家的精灵与物品（总开关见 [MARKET_SWITCH]、全服历史见 [HISTORY]） */
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
     * 市场开关：紧急停市 / 恢复（**影响全服的核按钮**）。
     *
     * ⚠ **2026-09-24 从 [MARKET] 里拆出来**（用户提的）：原先总开关与「下架挂单」共用一个节点，
     * 等于「想找人帮忙看挂单，就得把停市权一起给他」—— 两者份量差一个数量级
     * （撤一个违规挂单 vs 全服经济停摆）。拆开后想合并的人**同时勾两个**即可，组合自由。
     */
    const val MARKET_SWITCH = PREFIX + "marketswitch"

    /**
     * 交易历史：查看**全服所有人**的交易记录（谁买了什么、花了多少、什么时候）。
     *
     * ⚠ **2026-09-24 从 [MARKET] 里拆出来**（用户提的），理由与 [MARKET_SWITCH] 不同 ——
     * 这是**只读**权限，但成交记录是**非公开**信息（挂单全服可见、账本不是）：
     * 「找人帮忙下架违规挂单」不等于该让他看到全服每个人的经济行为。读与写该分开。
     */
    const val HISTORY = PREFIX + "history"

    /**
     * 查余额：查看**任何人**的 RP 余额（查自己不需要节点 —— 自己的数据自己看）。
     * 纯只读，可放心给客服做核对。
     */
    const val POINTS_CHECK = PREFIX + "points.check"

    /**
     * 兑换码：生成 / 列表 / 作废 / 清理。
     *
     * ⚠ **这是印发钞权的节点之一** —— 生成兑换码等于凭空造钱，与 [POINTS_ORDER] 同级敏感，
     * 随意下放等于把服务器经济交出去。命令侧 `/market points code` 同判此节点（不再认 OP）。
     *
     * ⚠⚠ **别以为「不给生成就安全」—— 连列表也是印钞权**（2026-09-25 核实）：
     *   列表每行都有**复制按钮，复制的是完整码**（服务端下发的 `PointCodeInfo.code` 就是完整码，
     *   屏幕上只显示前缀而已）⇒ 持有人复制任意一张未使用的码、自己兑换，照样凭空得钱。
     *   所以这个节点**拆不出安全中间态**（拆出「生成」也无用，列表那条路还在）——
     *   服主授予时按「**发码权 = 印钞权**」整体理解即可，别指望细分成"只读"和"可写"。
     */
    const val POINTS_CODE = PREFIX + "points.code"

    /**
     * 充值订单：列表 / 确认发放 / 作废。
     *
     * ⚠ **确认订单 = 加余额**（玩家扫码付款后由管理员核对账单放行），所以同属发钞权 ——
     * 授予前先确认对方**看得到收款账单**，否则「确认到账」这个动作本身不成立。
     *
     * ⚠ **2026-09-25 从「仅服主」改判为可授予节点**（用户拍板）：服主可把核对工作交出去。
     *   命令行的 `points shop list/confirm/reject/purge` 与界面 **同判此节点** ——
     *   两处指向同一批订单，判据必须一致，否则「界面能确认、命令敲不进去」。
     */
    const val POINTS_ORDER = PREFIX + "points.order"

    /**
     * RP 商城：上架 / 编辑 / 下架**用 RP 购买的商品**（服务端内核 `PointMarketService`）。
     *
     * ⚠ 与 [POINTS_ORDER] 那条「买 RP」的充值线是**两码事**（两条线只共用余额与审计）：
     * 本节点管的是「玩家花 RP 能换到什么」—— 商品规格（精灵 NBT、价格、库存）全由持有人填，
     * 等于**发给玩家的东西**的质量把关权。
     *
     * ⚠ 2026-09-25 从 OP 改判为节点（原先判 `hasPermissionLevel(2)`）。
     *
     * ⚠ 2026-10-20 起**也管自定义（NPC）商店**：创建 / 删除 / 改名 / 开关 / 绑定 NPC
     *   （`/market shop ...`）与在自定义商店里上架商品都判这个节点 —— 用户拍板沿用，不另开节点。
     *   玩家侧不需要任何节点：右键绑定过的 NPC 即可进店（购买仍要花 RP）。
     */
    const val POINTS_SHOP = PREFIX + "points.shop"

    /**
     * 玩家总览：查看**全服每个玩家**的市场与经济数据 —— 余额、挂单数、可借额度、已借与剩余、
     * 存款与已赚利息、图鉴遇见 / 捕捉数、封禁与禁借状态（2026-10-15 用户拍板新开）。
     *
     * ⚠ **授予即等于让对方看到全服所有人的余额与债务** —— 节点授予是全给、收不回来，
     *   服主得清楚自己给出去的是什么（节点说明里也写了这句）。
     * ⚠ 它不塞进 [FINANCE]：这张表跨了市场与金融（有挂单数），塞进金融语义不符。
     * ⚠ 1.3.x 上 `/market points top`（全服余额排行）**与它共用本节点**（那条命令原本只服主）——
     *   否则会出现「界面里看得见、敲命令却被拒」的怪事。1.2.x 没有那条命令。
     */
    const val OVERVIEW = PREFIX + "overview"

    /**
     * 全部管理节点（快照、入口判定、`/market perm list` 与权限编辑界面的按钮都用它）。
     * **顺序 = 权限编辑弹窗里按钮的排列顺序**（按此顺序交替分列，每行两个）。
     *
     * ⚠ **2026-09-25 用户重排：按语义配对两两成行** ——
     *   挂单管理｜交易历史 / 封禁｜黑名单 / 价格限制｜拍卖管理 / 求购管理｜金融管理 / 市场开关。
     *   ⚠ 原先那条「前 7 个与**管理面板功能按钮**一一对应」的约定**就此作废**：面板里是
     *   8 个按钮（MARKET 占 2 个）、这里 10 个节点，本来就对不齐，对齐的价值不如语义相邻。
     *   [MARKET_SWITCH] 在面板里对应**底部那颗图标按钮**、[HISTORY] 对应**「全部交易历史」按钮**，
     *   但它们在弹窗里的落点由本列表顺序决定，别再假设「位置 = 面板按钮位置」。
     * 末尾四个是本线（1.3.0）的 RP 节点，同样两两成行：
     *   查余额｜兑换码 / 充值订单｜RP 商城（**先只读后写**，与上面那批同一习惯）。
     * 最后是 [OVERVIEW]（从 1.2.2 上浮上来的「玩家总览」）—— **有意排在最末**：
     *   它本身就是「看全服」的重权限；且 14 个正好凑满 7 行（与 13 个同高，权限弹窗高度不变）。
     * 改这里的顺序会同时改掉界面按钮与 list 输出的顺序。
     *
     * ⚠ **加 / 删节点时权限弹窗的高度会跟着变**：`PermissionScreen` 的高度由本列表长度推导
     *   （两列、每行 2 个），别再手写死高度。14 个 = 7 行 = 214 高。
     *
     * ⚠ 原 `CONFIG`（服务器配置）节点已于 2026-09-23 删除：服务器配置改为**服主专属**，
     * 不再作为可授予的节点 —— 想放权就把对方 UUID 加进 `serverOwners`。
     */
    val ALL = listOf(
        MARKET, HISTORY, BAN, BLACKLIST, PRICE_LIMIT, AUCTION, BUY_ORDER, FINANCE, MARKET_SWITCH,
        POINTS_CHECK, POINTS_CODE, POINTS_ORDER, POINTS_SHOP, OVERVIEW
    )

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
     * 2026-10-01 起 `/market owner` 只剩只读的 `list`，但控制台照旧能直接跑管理命令）。
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
     * 悬停教学（「让服主把 UUID 写进 config/cobblemarket.json 的 serverOwners」）。把入口也砍掉，这套教学就没落点了。
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
