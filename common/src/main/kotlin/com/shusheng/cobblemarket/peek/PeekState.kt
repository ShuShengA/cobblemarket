package com.shusheng.cobblemarket.peek

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

/**
 * **玩家总览「监视」会话的恢复状态**（2026-11-09，方案 `docs/peek_feature_plan.md`）。
 *
 * 每个会话保存的是**监视开始前、被监视者本人的状态**：
 * 位置 / 朝向 / 维度 / 游戏模式 / 状态效果 / 着火 / 氧气。
 *
 * ⚠⚠ **绝不保存背包 / 经验 / 末影箱 / 队伍**：旁观模式本来就不动背包，加进去反而会引入
 *   复制与丢失风险（跨存档、NBT 结构变化）。参考实现 Peek 的 `PlayerState` 同样没有背包字段。
 *
 * ⚠ **写盘顺序是安全性的根**（见 [PeekService.start]）：
 *   先 `put` + [saveNow] 落盘，**再**切旁观 / 传送。这样任何崩溃点都能推出正确结果 ——
 *   - 落盘后、改玩家前崩溃：磁盘有状态、玩家数据仍是原模式 ⇒ 登录 sanity check 丢弃陈旧状态
 *   - 改玩家后崩溃：磁盘有状态、玩家数据可能是旁观者 ⇒ 登录照常还原
 *
 * 存档是**新文件** `cobblemarket_peek.dat`（旧档没有它就是空表）：新数据开新 NBT 键，
 * 不改任何已有结构。读取逐条容错：坏条目跳过 + warn，绝不整体抛。
 *
 * 文件由既有 `StateBackup` 机制覆盖（启动校验 `.bak`、正常关服刷新备份）。
 *
 * 移植参考：Peek 1.0.2 的 `data/peek/PlayerState`（https://github.com/jiazengp/peek，MIT License）。
 * 本项目为 GPL-3.0；本文件是 Kotlin 重写，非逐行拷贝。
 */
class PeekState private constructor() : PersistentState() {

    /**
     * 一次会话的**开始前快照**。
     *
     * 字段与 Peek 的 `data/peek/PlayerState` 对齐（去掉 `vehicleBubbleTime`）；
     * 游戏模式存**枚举名**（`SURVIVAL` / `CREATIVE` / …），读回时用 `valueOf` + 默认值容错。
     */
    class SavedState(
        /** 被监视者 UUID（监视者 UUID 是这张表的键） */
        var targetUuid: UUID,
        /** 维度 registry key 字符串（如 `minecraft:overworld`） */
        var worldKey: String = "",
        var x: Double = 0.0,
        var y: Double = 0.0,
        var z: Double = 0.0,
        var yaw: Float = 0f,
        var pitch: Float = 0f,
        /** `GameMode` 枚举名；非法值读回时落默认 [net.minecraft.world.GameMode.SURVIVAL] */
        var gameMode: String = "SURVIVAL",
        /** `StatusEffectInstance.writeNbt()` 的原样副本（读回走 `fromNbt`，逐条容错） */
        val effects: MutableList<NbtCompound> = mutableListOf(),
        var fireTicks: Int = 0,
        /** 氧气值；默认 300 = 原版陆地上的满值 */
        var air: Int = 300,
        /** 开始时间（epoch ms；只用于日志 / 将来可能的超时，不参与判定） */
        var startedAt: Long = 0L,
    )

    /** `监视者 UUID → 开始前快照` */
    private val sessions = mutableMapOf<UUID, SavedState>()

    fun get(peekerUuid: UUID): SavedState? = sessions[peekerUuid]

    fun hasSessions(): Boolean = sessions.isNotEmpty()

    /** 会话快照（遍历时用；避免 stop 里 remove 造成 ConcurrentModification） */
    fun all(): Map<UUID, SavedState> = sessions.toMap()

    fun put(peekerUuid: UUID, saved: SavedState) {
        sessions[peekerUuid] = saved
        markDirty()
    }

    fun remove(peekerUuid: UUID): SavedState? {
        val removed = sessions.remove(peekerUuid) ?: return null
        markDirty()
        return removed
    }

    /**
     * **同步落盘**（`markDirty` + `PersistentStateManager.save()`）。
     *
     * ⚠ 只在「开始 / 停止 / 登录还原 / 丢弃陈旧状态」各调一次 —— 不每 tick 保存。
     * ⚠ NeoForge 的 SavedData 写盘可能是异步的，崩溃窗口仍然存在；但「先落盘、再改玩家」
     *   的顺序已经把窗口压到最小（见类注释）。写盘失败只记日志：调用方**不能**因为写盘失败
     *   就跳过后续还原，否则玩家会卡在旁观者。
     */
    fun saveNow(server: MinecraftServer) {
        markDirty()
        try {
            server.overworld.persistentStateManager.save()
        } catch (e: Exception) {
            CobbleMarket.LOGGER.error("CobbleMarket: failed to save peek state", e)
        }
    }

    // ── 存档 ──

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        sessions.forEach { (peekerUuid, saved) ->
            val c = NbtCompound()
            c.putUuid("peeker", peekerUuid)
            c.putUuid("target", saved.targetUuid)
            c.putString("world", saved.worldKey)
            c.putDouble("x", saved.x)
            c.putDouble("y", saved.y)
            c.putDouble("z", saved.z)
            c.putFloat("yaw", saved.yaw)
            c.putFloat("pitch", saved.pitch)
            c.putString("mode", saved.gameMode)
            val effects = NbtList()
            saved.effects.forEach { effects.add(it.copy()) }
            c.put("effects", effects)
            c.putInt("fireTicks", saved.fireTicks)
            c.putInt("air", saved.air)
            c.putLong("startedAt", saved.startedAt)
            list.add(c)
        }
        nbt.put("sessions", list)
        return nbt
    }

    private fun readNbt(nbt: NbtCompound) {
        nbt.getList("sessions", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
            try {
                val c = element as NbtCompound
                // UUID 缺失 / 损坏 ⇒ 这条跳过（getUuid 自身可能抛，已在本条 try 内）
                if (!c.containsUuid("peeker") || !c.containsUuid("target")) return@forEach
                val peeker = c.getUuid("peeker") ?: return@forEach
                val target = c.getUuid("target") ?: return@forEach
                val saved = SavedState(
                    targetUuid = target,
                    worldKey = if (c.contains("world")) c.getString("world") else "",
                    x = if (c.contains("x")) c.getDouble("x") else 0.0,
                    y = if (c.contains("y")) c.getDouble("y") else 0.0,
                    z = if (c.contains("z")) c.getDouble("z") else 0.0,
                    yaw = if (c.contains("yaw")) c.getFloat("yaw") else 0f,
                    pitch = if (c.contains("pitch")) c.getFloat("pitch") else 0f,
                    // 枚举名容错：非法值不崩，落默认（本项目数据层铁律第 4 条）
                    gameMode = if (c.contains("mode")) c.getString("mode") else "SURVIVAL",
                    fireTicks = if (c.contains("fireTicks")) c.getInt("fireTicks") else 0,
                    air = if (c.contains("air")) c.getInt("air") else 300,
                    startedAt = if (c.contains("startedAt")) c.getLong("startedAt") else 0L,
                )
                c.getList("effects", NbtList.COMPOUND_TYPE.toInt()).forEach { effectElement ->
                    // 单条效果坏掉不影响其余效果：跳过即可（还原时 fromNbt 还会再兜一层）
                    (effectElement as? NbtCompound)?.let { saved.effects.add(it.copy()) }
                }
                sessions[peeker] = saved
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Skipping corrupted peek session: {}", e.message)
            }
        }
    }

    companion object {
        private val TYPE = PersistentState.Type(
            { PeekState() },
            { nbt, _ -> PeekState().apply { readNbt(nbt) } },
            null
        )

        fun get(server: MinecraftServer): PeekState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_peek")
    }
}
