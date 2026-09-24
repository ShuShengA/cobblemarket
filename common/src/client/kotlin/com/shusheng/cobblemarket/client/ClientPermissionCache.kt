package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.permission.MarketPermissions

/**
 * 我的权限节点快照（客户端侧，docs/permission_nodes_plan.md S1）。
 *
 * 服务端在**登录时**与**授予/收回/服主名单变更时**推送；客户端只读服务端算好的列表，
 * 不自己拼集合（省事 + 防伪造）。
 *
 * ⚠ 客户端所有权限显隐判断都读这里 —— **别调 `MarketPermissions.has`**（那是服务端判定，
 * 客户端玩家没有 server 实例，永远返回 false）。
 */
object ClientPermissionCache {

    var nodes: Set<String> = emptySet()
        private set

    /** 是不是**服主**（服务端在快照里明说）。**别再用「节点数 == 总数」反推**：
     *  被授予全部 7 个节点的普通玩家数量也是 7，会被误判成服主、能用只认服主的权限管理入口 */
    private var owner: Boolean = false

    /** 能不能进管理面板 —— **也由服务端算好下发**（OP 的 nodes 是空的，本地推不出来） */
    private var canManageFlag: Boolean = false

    /** **服主名单是空的**（服务端下发）：管理面板据此在背景下方提示「尚未配置服主」。
     *  ⚠ 与 [canManage] 无关：名单空时 OP 照样能进面板（看到一片置灰），这行字是给他解释的 */
    private var ownerListEmptyFlag: Boolean = false

    fun update(list: List<String>, isServerOwner: Boolean, canManage: Boolean, ownerListEmpty: Boolean) {
        nodes = list.toSet()
        owner = isServerOwner
        canManageFlag = canManage
        ownerListEmptyFlag = ownerListEmpty
    }

    /** 服主名单是否为空（管理面板的提示用） */
    fun isOwnerListEmpty(): Boolean = ownerListEmptyFlag

    fun has(node: String): Boolean = node in nodes

    /** 入口判定：能进管理面板（OP 或 服主 或有任一管理节点）—— 服务端算好下发，别自己推 */
    fun canManage(): Boolean = canManageFlag

    /** 是否服主（只认服主的入口用它，如权限管理、服务器配置、撤销坏账）——由服务端下发，不是从节点数反推的 */
    fun isServerOwner(): Boolean = owner

    /** 退出服务器时清空，防止切换服务器后残留（下次登录服务端会重新推） */
    fun clear() {
        nodes = emptySet()
        owner = false
        canManageFlag = false
        ownerListEmptyFlag = false
    }
}
