package com.shusheng.cobblemarket.permission

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtString
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

/** 一名玩家的权限授予记录：一名玩家一条，存其全部节点 */
data class PermissionGrant(
    val playerUuid: UUID,
    /** 最后一次操作时记录的名字（仅供界面显示；判定一律以 UUID 为准，改名字不影响） */
    val playerName: String,
    val nodes: Set<String>,
    /** 最后一次操作的执行者名（命令 = 命令源名；界面 = OP 玩家名） */
    val grantedBy: String,
    val grantedAt: Long
)

/**
 * 管理权限授予表（docs/permission_nodes_plan.md S1）。
 *
 * 与黑名单/封禁名单同构：新建 PersistentState，**不放 config** —— 权限是运行期数据。
 * **服主不写入此表**：`MarketPermissions.has` 里服主恒真，表只记「服主授予他人的额外权限」，
 * 所以收回某人的节点不会波及服主（也收不掉）。OP 与节点无关，同样不在此表。
 */
class PermissionState private constructor() : PersistentState() {

    private val grants = mutableMapOf<UUID, PermissionGrant>()

    /**
     * 覆盖式设置某玩家的节点集合（界面「保存」/ 命令都走这里）。
     * 返回 (新增, 移除) 差集 —— 供广播与 CSV 审计使用。
     */
    fun setNodes(playerUuid: UUID, playerName: String, nodes: Set<String>, by: String): Pair<Set<String>, Set<String>> {
        val before = grants[playerUuid]?.nodes ?: emptySet()
        if (nodes.isEmpty()) {
            grants.remove(playerUuid)
        } else {
            grants[playerUuid] = PermissionGrant(playerUuid, playerName, nodes.toSet(), by, System.currentTimeMillis())
        }
        markDirty()
        return (nodes - before) to (before - nodes)
    }

    /** 单个节点授予；已拥有则返回 false（不重复写、不刷审计） */
    fun grant(playerUuid: UUID, playerName: String, node: String, by: String): Boolean {
        val before = grants[playerUuid]?.nodes ?: emptySet()
        if (node in before) return false
        setNodes(playerUuid, playerName, before + node, by)
        return true
    }

    /** 单个节点收回；本来就没有则返回 false */
    fun revoke(playerUuid: UUID, node: String): Boolean {
        val before = grants[playerUuid]?.nodes ?: return false
        if (node !in before) return false
        setNodes(playerUuid, grants[playerUuid]!!.playerName, before - node, grants[playerUuid]!!.grantedBy)
        return true
    }

    /** 该玩家被授予的节点（查不到 = 空集，不区分「没记录」与「已清空」） */
    fun getNodes(playerUuid: UUID): Set<String> = grants[playerUuid]?.nodes ?: emptySet()

    /** 全部授予记录（界面列表用）；列表型返回副本，外部改不动内部状态 */
    fun getAll(): List<PermissionGrant> = grants.values.toList()

    /** 界面「删除」：移除该玩家全部节点 */
    fun removePlayer(playerUuid: UUID): PermissionGrant? {
        val removed = grants.remove(playerUuid)
        if (removed != null) markDirty()
        return removed
    }

    /** 玩家改名后同步显示名（判权不看名字，只是列表别显示旧名） */
    fun rename(playerUuid: UUID, newName: String) {
        val grant = grants[playerUuid] ?: return
        if (grant.playerName == newName) return
        grants[playerUuid] = grant.copy(playerName = newName)
        markDirty()
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        grants.values.forEach { grant ->
            val c = NbtCompound()
            c.putUuid("uuid", grant.playerUuid)
            c.putString("name", grant.playerName)
            c.putString("grantedBy", grant.grantedBy)
            c.putLong("grantedAt", grant.grantedAt)
            val nodes = NbtList()
            grant.nodes.forEach { nodes.add(NbtString.of(it)) }
            c.put("nodes", nodes)
            list.add(c)
        }
        nbt.put("grants", list)
        return nbt
    }

    companion object {
        private val TYPE = PersistentState.Type(
            { PermissionState() },
            { nbt, _ ->
                PermissionState().apply {
                    nbt.getList("grants", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            val nodeList = c.getList("nodes", NbtList.STRING_TYPE.toInt())
                            val nodes = (0 until nodeList.size).map { nodeList.getString(it) }.toSet()
                            // 只认当前已知的节点：老存档里被裁掉的节点自然失效，不会留在表里
                            val valid = nodes intersect MarketPermissions.ALL.toSet()
                            if (valid.isEmpty()) return@forEach
                            val grant = PermissionGrant(
                                playerUuid = c.getUuid("uuid"),
                                playerName = c.getString("name"),
                                nodes = valid,
                                grantedBy = c.getString("grantedBy"),
                                grantedAt = c.getLong("grantedAt")
                            )
                            grants[grant.playerUuid] = grant
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted permission entry: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): PermissionState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_permissions")
    }
}
