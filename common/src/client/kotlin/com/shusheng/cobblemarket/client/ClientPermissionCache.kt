package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.permission.MarketPermissions

/**
 * 我的权限节点快照（客户端侧，docs/permission_nodes_plan.md S1）。
 *
 * 服务端在**登录时**与**授予/收回时**推送；客户端只读服务端算好的列表，
 * 不自己拼集合（省事 + 防伪造）。
 *
 * ⚠ 客户端所有权限显隐判断都读这里 —— **别调 `MarketPermissions.has`**（那是服务端判定，
 * 客户端玩家没有 server 实例，永远返回 false）。
 */
object ClientPermissionCache {

    var nodes: Set<String> = emptySet()
        private set

    /** 是不是真 OP（服务端在快照里明说）。**别再用「节点数 == 总数」反推**：
     *  被授予全部 8 个节点的普通玩家数量也是 8，会被误判成 OP、能用只认 OP 的权限管理入口 */
    private var op: Boolean = false

    fun update(list: List<String>, isOp: Boolean) {
        nodes = list.toSet()
        op = isOp
    }

    fun has(node: String): Boolean = node in nodes

    /** 入口判定：能进管理面板（任一管理节点即可；OP 会拿到全部节点，自然为真） */
    fun canManage(): Boolean = nodes.isNotEmpty()

    /** 是否真 OP（只认 OP 的入口用它，如权限管理）——由服务端下发，不是从节点数反推的 */
    fun isOp(): Boolean = op

    /** 退出服务器时清空，防止切换服务器后残留（下次登录服务端会重新推） */
    fun clear() {
        nodes = emptySet()
        op = false
    }
}
