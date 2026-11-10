package com.shusheng.cobblemarket.peek

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.network.PeekNetwork
import com.shusheng.cobblemarket.permission.MarketPermissions
import net.minecraft.entity.Entity
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket
import net.minecraft.network.packet.s2c.play.SetCameraEntityS2CPacket
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.world.GameMode
import java.util.UUID

/**
 * 玩家总览的「监视」（Peek）服务端内核（2026-11-09，方案 `docs/peek_feature_plan.md`）。
 *
 * 行为照 Peek 1.0.2（MIT）的实现移植：
 * - 开始：保存被监视者状态 → **同步落盘** → 切旁观 → 传送到目标身边（**只这一次**）
 * - 同维度：不自动拖拽（Peek 是自由观察）；换维度：延迟传送跟随
 * - 结束：清相机 → **先传回原位（仍是旁观者，目标看不见）** → 再切回原模式 → 还原效果/着火/氧气
 *
 * 与 Peek 的差异：
 * 1. 不做请求 / 同意 / 邀请 / 统计 / 黑名单 / 私密 / 粒子 / 音效 —— 共用 `overview` 节点，静默开始
 * 2. 不写附身代码：原版旁观者「点击实体」自带；我们只保证 staff 已旁观并在目标身边
 * 3. 崩溃恢复不用 Fabric 专属的 PlayerDataAPI，改用 [PeekState] + 开始前落盘 + 登录 sanity check
 * 4. 结束顺序改为「先传回、再切模式」（Peek 是先切模式后传回）—— 避免在目标面前现身
 *
 * ⚠ **背包安全**：旁观不改背包；本服务只碰位置/朝向/维度/模式/效果/着火/氧气。
 *   千万别「顺手」加背包保存/还原，那才是复制与丢失的来源。
 *
 * ⚠ 本对象的所有入口都必须在**服务端主线程**调用（tick / join / disconnect / 命令 / C2S 回调都满足）。
 *
 * 移植参考：Peek 1.0.2（https://github.com/jiazengp/peek，MIT License）。
 * 本项目为 GPL-3.0，MIT 与 GPL-3.0 兼容；本文件是 Kotlin 重写（逻辑对齐，非逐行拷贝）。
 */
object PeekService {

    /** 结束原因：决定是否/给监视者发哪条提示（[messageKey] = null 表示静默清理） */
    enum class StopReason(val messageKey: String?) {
        /** 监视者自己 `/market peek stop` */
        MANUAL("cobblemarket.peek.stopped"),

        /** 目标下线 ⇒ 停止会话并还原监视者 */
        TARGET_OFFLINE("cobblemarket.peek.stopped_target_offline"),

        /** 目标死亡 ⇒ 停止会话并还原监视者 */
        TARGET_DEAD("cobblemarket.peek.stopped_target_dead"),

        /** 目标进入了旁观模式（自己开始监视别人 / 手动切旁观）⇒ 停止会话并还原监视者 */
        TARGET_SPECTATOR("cobblemarket.peek.stopped_target_spectator"),

        /** 登录后从崩溃 / 掉线中恢复（玩家当前是旁观者且状态存在） */
        RECOVERED("cobblemarket.peek.stopped_recovered"),

        /** 监视期间 overview 权限被收回（节点 / 服主名单变更）⇒ 立即结束并还原 */
        PERMISSION_REVOKED("cobblemarket.peek.stopped_permission"),

        /** 目标离开了监视者所在维度（本功能只支持同维度监视）⇒ 结束并还原 */
        TARGET_CHANGED_DIMENSION("cobblemarket.peek.stopped_dimension"),

        /** 监视者自己离开了目标所在维度（自己的 /tp、被 OP 强制传送等）⇒ 结束并还原 */
        PEEKER_CHANGED_DIMENSION("cobblemarket.peek.stopped_peeker_dimension"),

        /** 监视者自己切回了其他模式：会话失效，**不还原**（避免覆盖玩家手动操作） */
        PEEKER_NOT_SPECTATOR(null),
    }

    /**
     * 监视期间每 tick 给监视者盖的**传送门冷却**：阻止他的「身体」跟着目标一起走进传送门。
     *
     * 原版 `Entity.tryUsePortal` 看到 `hasPortalCooldown()` 就把冷却清零并返回，不建立传送门进度；
     * 我们在 END_SERVER_TICK 每 tick 补一次 >0 的冷却 ⇒ 传送门管理器永远建不起来。
     * （不是靠高冷却硬顶：`tryUsePortal` 会把它清零，所以必须每 tick 补。）
     */
    private const val PORTAL_BLOCK_TICKS = 5

    /**
     * 监视者（或其附身的**相机实体**）与目标的最大距离；超出即带回目标身边（2026-11-10）。
     *
     * 不限制的话，`overview` 节点就等于附送「无限制旁观飞行」—— 能拿它全图找稀有刷新点 / 矿 /
     * 结构，是一个没写进权限说明的隐性特权。64 格内仍可自由绕圈观察。
     */
    private const val MAX_DISTANCE = 64.0

    /** 牵引提示的行动栏节流（同一人 5 秒最多一条，避免反复越界时刷屏） */
    private const val LEASH_NOTICE_INTERVAL_MS = 5_000L

    /**
     * 停止时：**传回原位之后**等多少 tick 再切回原模式。
     *
     * ⚠ 原版实体位置包 `EntityPositionS2CPacket` 在客户端会做 **3 tick 插值**
     *   （`updateTrackedPositionAndAngles(..., 3)`）。如果传回后立刻切回生存/创造，目标客户端
     *   还看到 staff 从目标身边"滑"回原位的中间帧，于是出现「皮肤闪一下又消失」。
     *   等 5 tick（插值完成 + 1 tick 余量）再切模式，期间 staff 仍是旁观者（目标看不见）。
     */
    private const val FINALIZE_DELAY_TICKS = 5

    /** 登录后延迟多少 tick 做崩溃恢复检查（照 Peek：给玩家数据/客户端同步稳定） */
    private const val CRASH_RECOVERY_DELAY_TICKS = 3

    /** 已传回原位、等待「插值完成后再切模式」的会话 */
    private class PendingFinalize(
        val reason: StopReason,
        val notifyPeeker: Boolean,
        val dueTick: Int,
    )

    /** 监视者 UUID → 等待最终切模式的会话（期间 PeekState 仍然保留） */
    private val pendingFinalizes = mutableMapOf<UUID, PendingFinalize>()

    /**
     * 还原后**延迟重推游戏模式**的到期 tick。
     *
     * ⚠ 跨维度返回时，客户端要先处理 `PlayerRespawnS2CPacket`，而它的 `setGameModes(...)` 用的是
     * **传送那一刻**的 spawnInfo（旁观）——如果我们的模式更新在客户端加载完之前到达，会被这次
     * setGameModes **覆盖回去**，表现就是「服务端已经回到创造/生存，客户端还是观察者视角」。
     * 因此还原后再按 [MODE_RESYNC_DELAYS] 重推几次；每次发的是**当前服务器模式**，不会覆盖玩家
     * 后来自己切的模式。
     */
    private val pendingModeSyncs = mutableMapOf<UUID, MutableList<Int>>()

    /** 还原后重推游戏模式的时间点（tick，相对还原时刻）—— 覆盖客户端跨维度加载较慢的情况 */
    private val MODE_RESYNC_DELAYS = intArrayOf(5, 10, 20, 40, 80, 160)

    /** 还原结果：区分「成功」「不是旁观者（可安全清理）」「异常（必须保留状态）」 */
    private enum class RestoreOutcome { RESTORED, NOT_SPECTATOR, FAILED }

    /** 第一阶段（传送 / 清相机）的结果 */
    private enum class PrepareOutcome { READY, NOT_SPECTATOR, FAILED }

    /** 监视者 UUID → 登录后做崩溃恢复检查的到期 tick */
    private val pendingRestores = mutableMapOf<UUID, Int>()

    /**
     * 「已还原、但**玩家数据还没落盘**」的监视者：此时不能删 PeekState，
     * 也不能让 tick 每 tick 重试（见 [stopInternal] 的落盘顺序说明）。
     * 下次登录 / 手动 stop 会清掉并重试。
     */
    private val blockedCleanup = mutableSetOf<UUID>()

    /** 监视者 UUID → 上次牵引提示的时间（epoch ms，行动栏节流用） */
    private val lastLeashNoticeAt = mutableMapOf<UUID, Long>()

    /**
     * 服务端启动 / 换世界时清空内存待办（进程级静态状态不得跨世界 —— 与 [com.shusheng.cobblemarket.util.PersistHelper.reset] 同理）。
     * 磁盘上的 [PeekState] 不受影响，仍由登录 sanity check 处理。
     */
    fun reset() {
        pendingFinalizes.clear()
        pendingModeSyncs.clear()
        pendingRestores.clear()
        blockedCleanup.clear()
        lastLeashNoticeAt.clear()
    }

    // ── 开始 / 停止 ──

    /**
     * 开始监视（命令与总览按钮共用这个入口）。
     *
     * @return true = 已进入监视（成功或已发红字错误都由本方法负责）
     */
    fun start(server: MinecraftServer, player: ServerPlayerEntity, targetUuid: UUID): Boolean {
        val target = server.playerManager.getPlayer(targetUuid)
        if (target == null) {
            notify(player, "cobblemarket.peek.target_offline", Formatting.RED)
            return false
        }
        return start(server, player, target)
    }

    fun start(server: MinecraftServer, player: ServerPlayerEntity, target: ServerPlayerEntity): Boolean {
        // 服务端再判一次，不信任客户端（按钮显隐只是入口，不是鉴权）
        if (!MarketPermissions.has(player, MarketPermissions.OVERVIEW)) {
            notify(player, "cobblemarket.peek.no_permission", Formatting.RED)
            return false
        }
        if (player.uuid == target.uuid) {
            notify(player, "cobblemarket.peek.self", Formatting.RED)
            return false
        }
        if (!target.isAlive || target.isRemoved) {
            notify(player, "cobblemarket.peek.target_dead", Formatting.RED)
            return false
        }
        // 旁观者（包括正在监视别人的 staff）不能当目标：否则两条会话会互相牵引
        //（2026-11-10 用户实测：A 监视 B、B 又监视 A，B 飞远 >64 格时把 A 拉了过去）
        if (target.isSpectator) {
            notify(player, "cobblemarket.peek.target_spectator", Formatting.RED)
            return false
        }
        // **只支持同维度监视**：跨维度还原会走客户端重生流程，模式同步容易残留（2026-11-09 实测）。
        // 拒绝时带上目标具体在哪个维度（原版三维度友好名 + id，模组/多世界维度显示 id）。
        if (player.serverWorld.registryKey != target.serverWorld.registryKey) {
            notify(
                player, "cobblemarket.peek.target_other_dimension", Formatting.RED,
                dimensionText(target.serverWorld)
            )
            return false
        }

        val state = PeekState.get(server)
        blockedCleanup.remove(player.uuid)
        // 上一次 stop 还在「传回后等插值」的窗口内：先把旧会话立即收尾，再捕获新的开始状态
        if (!finishPendingFinalizeNow(server, player)) {
            notify(player, "cobblemarket.peek.restore_failed", Formatting.RED)
            return false
        }
        // 已经在监视 ⇒ 先停旧会话（还原到原位）再开始新的（与 Peek 的切换一致）。静默，不提示中间步骤
        if (state.get(player.uuid) != null) {
            stopInternal(server, player.uuid, StopReason.MANUAL, notifyPeeker = false)
            // 旧会话现在可能处在 finalize 等待窗口；立刻收尾后再开始新的（否则新状态会被旧任务改模式）
            if (!finishPendingFinalizeNow(server, player)) {
                notify(player, "cobblemarket.peek.restore_failed", Formatting.RED)
                return false
            }
            // 旧会话如果因「玩家数据落盘失败」被标记阻塞，新会话不应继承（新状态会重新落盘）
            blockedCleanup.remove(player.uuid)
        }

        // ① 捕获 → ② 写入并【同步落盘】—— 必须在改玩家之前（崩溃窗口分析见 PeekState 类注释）
        state.put(player.uuid, capture(player, target.uuid))
        state.saveNow(server)

        // ③ 才切旁观 + 传送。已经在旁观（staff 自己先旁观再监视）时 changeGameMode 会返回 false，
        //    那不是失败 —— 已经是目标模式了，跳过即可
        if (!player.isSpectator && !player.interactionManager.changeGameMode(GameMode.SPECTATOR)) {
            // 切模式失败：回滚刚写的状态（玩家此刻还没被改动）
            state.remove(player.uuid)
            state.saveNow(server)
            notify(player, "cobblemarket.peek.start_failed", Formatting.RED)
            return false
        }
        // ⚠ `interactionManager.changeGameMode` 只发 PlayerList + abilities；原版 `/gamemode` 还额外发
        //   `GameStateChange(GAME_MODE_CHANGED)`，客户端的交互管理器（旁观飞行锁定）靠它更新。
        //   漏了它，进入旁观的玩家不会自动飞，要双击空格（2026-11-10 用户实测）。
        forceGameModeSync(player)
        // 监视期间禁用传送门：否则目标进地狱时，staff 的「身体」也会被传送门带走，
        // 与延迟跟随叠加成两次维度切换（客户端相机最容易卡死）
        blockPortals(player)
        try {
            // 若玩家正好处在一次未确认的传送门切换里，先清掉服务端的「传送中」标志，
            // 否则下面的 teleport / 后续还原可能被原版的传送状态卡住
            if (player.isInTeleportationState()) player.onTeleportationDone()
            // 只传这一次：同维度之后不拖拽；跨维度由 tick 的延迟跟随处理
            player.teleport(target.serverWorld, target.x, target.y, target.z, target.yaw, target.pitch)
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Peek: failed to teleport {} to {}: {}", player.gameProfile.name, target.gameProfile.name, e.message)
        }

        // 通知客户端进入监视状态（HUD 显示退出提示、退出键生效；服务端是唯一真相）
        PeekNetwork.sendActive(player, true)
        // 目标玩家**不收到任何提示**（无聊天 / 音效 / 粒子）
        notify(player, "cobblemarket.peek.started", Formatting.GREEN, target.name)
        CobbleMarket.LOGGER.info(
            "Peek started: {} -> {} ({})",
            player.gameProfile.name, target.gameProfile.name, target.serverWorld.registryKey.value
        )
        return true
    }

    /**
     * 停止监视（`/market peek stop`）。
     * 不在监视中 ⇒ 红字提示并返回 false。
     */
    fun stop(server: MinecraftServer, player: ServerPlayerEntity): Boolean {
        if (PeekState.get(server).get(player.uuid) == null) {
            // 状态已经没了 ⇒ 所有与这个玩家相关的待办都失去依据，一并清掉
            pendingFinalizes.remove(player.uuid)
            blockedCleanup.remove(player.uuid)
            // 兜底：会话可能已经因异常被清掉，但客户端相机仍卡在别人/别的维度上。
            // 这里只做**无副作用**的同步：把服务端与客户端的相机都清回自己、把真实游戏模式
            // 重推给客户端，不改服务端的模式 / 位置（合法旁观者执行也不会被强制切模式）。
            player.setCameraEntity(null)
            forceCamera(player, player)
            if (player.isInTeleportationState()) player.onTeleportationDone()
            forceGameModeSync(player)
            scheduleModeResync(player)
            // 没有会话 ⇒ 客户端也不该显示「监视中」（正常不会发生，这里纠正残留 / 丢包）
            PeekNetwork.sendActive(player, false)
            CobbleMarket.LOGGER.warn(
                "Peek: {} ran /market peek stop without a saved session (mode={})",
                player.gameProfile.name, player.interactionManager.gameMode.name
            )
            notify(player, "cobblemarket.peek.not_peeking", Formatting.RED)
            return false
        }
        // 手动 stop 允许重试上一次「玩家数据落盘失败」留下的阻塞
        blockedCleanup.remove(player.uuid)
        return stopInternal(server, player.uuid, StopReason.MANUAL, notifyPeeker = true)
    }

    /**
     * 停止一个会话：还原（若监视者在线上）+ 删除状态 + 落盘。
     *
     * ⚠ 顺序是 **先还原玩家、再删除状态并落盘**（方案 §1 的停止顺序）。
     * 中途崩溃最多留下陈旧状态，登录时 sanity check 丢弃，不会覆盖玩家当前位置。
     */
    private fun stopInternal(
        server: MinecraftServer,
        peekerUuid: UUID,
        reason: StopReason,
        notifyPeeker: Boolean,
    ): Boolean {
        val state = PeekState.get(server)
        val saved = state.get(peekerUuid) ?: return false
        // 已经在「传回原位、等插值结束」阶段：重复调用直接忽略（命令连点 / tick 二次触发）
        if (pendingFinalizes.containsKey(peekerUuid)) return true
        pendingRestores.remove(peekerUuid)

        val peeker = server.playerManager.getPlayer(peekerUuid)
        if (peeker == null) {
            // ⚠ 监视者**不在线**时绝不能删状态：他的玩家数据可能已按「旁观者 + 目标位置」落盘，
            //   删了下次登录就会永久卡在旁观者。保留到登录后由 sanity check / 还原处理（方案 §2.2）。
            //   典型触发：监视者先掉线，之后目标才下线 / 死亡（会话从目标那侧结束）。
            CobbleMarket.LOGGER.info(
                "Peek: session of offline {} kept until next login (reason={})", peekerUuid, reason.name
            )
            return true
        }

        return when (prepareRestore(server, peeker, saved)) {
            PrepareOutcome.FAILED -> {
                // ⚠ 还原抛异常时**保留状态**：删掉会让玩家永远卡在旁观者，且 `/market peek stop` 会说
                //   「没有在监视任何人」，失去唯一的重试入口。留着可重试 / 重登后由 sanity check 处理。
                blockedCleanup += peekerUuid
                if (notifyPeeker) notify(peeker, "cobblemarket.peek.restore_failed", Formatting.RED)
                CobbleMarket.LOGGER.warn(
                    "Peek: restore failed for {} (reason={}); session kept", peeker.gameProfile.name, reason.name
                )
                true
            }

            PrepareOutcome.NOT_SPECTATOR -> {
                // 服务端已不是旁观者：不覆盖模式（prepareRestore 已同步客户端相机 / 模式）
                finishCleanup(server, peeker, peekerUuid, reason, notifyPeeker, restored = false)
            }

            PrepareOutcome.READY -> {
                // 位置已传回（仍是旁观者，目标看不见）。等客户端位置插值完成后再切模式，
                // 避免目标看到「皮肤从身边滑走 / 闪一下」。
                pendingFinalizes[peekerUuid] = PendingFinalize(
                    reason = reason,
                    notifyPeeker = notifyPeeker,
                    dueTick = server.ticks + FINALIZE_DELAY_TICKS,
                )
                CobbleMarket.LOGGER.info(
                    "Peek: {} moved back; mode restore scheduled in {} ticks (reason={})",
                    peeker.gameProfile.name, FINALIZE_DELAY_TICKS, reason.name
                )
                true
            }
        }
    }

    /**
     * 第一阶段：清相机 → 传回原位（仍保持旁观者身份，目标看不见）。
     * 不切模式、不动效果 —— 那些留到 [finishRestore] 在插值结束后做。
     */
    private fun prepareRestore(
        server: MinecraftServer,
        peeker: ServerPlayerEntity,
        saved: PeekState.SavedState,
    ): PrepareOutcome {
        if (!peeker.isSpectator) {
            // 服务端已不是旁观者（自己切回了模式）⇒ 不覆盖服务端模式，只把客户端可能残留的
            // 旁观视觉（相机 / 游戏模式）强制同步掉
            try {
                peeker.setCameraEntity(null)
                forceCamera(peeker, peeker)
                forceGameModeSync(peeker)
                scheduleModeResync(peeker)
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Peek: failed to sync client state for {}: {}", peeker.gameProfile.name, e.message)
            }
            CobbleMarket.LOGGER.info(
                "Peek: {} is no longer a spectator ({}); clearing state without restoring",
                peeker.gameProfile.name, peeker.interactionManager.gameMode.name
            )
            return PrepareOutcome.NOT_SPECTATOR
        }
        return try {
            // 先无条件把**客户端**相机清回自己：附身是客户端相机，服务端 `setCameraEntity(null)`
            // 在「服务端本来就是你」时不会发包，光靠它治不了跨维度后的相机残留。
            peeker.setCameraEntity(null)
            forceCamera(peeker, peeker)
            if (peeker.hasVehicle()) peeker.stopRiding()

            val spot = resolveSpot(server, saved)
            // 客户端卡住时可能留下未确认的传送状态（`inTeleportationState`），会让这次传送
            // 被原版的传送流程吞掉。还原是强制操作，先清标志。
            if (peeker.isInTeleportationState()) peeker.onTeleportationDone()
            // 先传回：此刻仍是旁观者，传送目标看不见；teleport 内部也会再清一次相机
            peeker.teleport(spot.world, spot.x, spot.y, spot.z, saved.yaw, saved.pitch)
            // 监视期间禁用了传送门（见 PORTAL_BLOCK_TICKS），正常结束后恢复
            peeker.resetPortalCooldown()
            peeker.portalManager = null
            // 跨维度传送可能让客户端重建实体，再兜一次相机
            forceCamera(peeker, peeker)

            // 不等实体追踪的下一 tick：主动把新位置广播给其他客户端（服务端位置已立即更新）
            broadcastPosition(server, peeker)
            PrepareOutcome.READY
        } catch (e: Exception) {
            CobbleMarket.LOGGER.error(
                "Peek: failed to move {} back — session kept, the player is still a spectator",
                peeker.gameProfile.name, e
            )
            PrepareOutcome.FAILED
        }
    }

    /**
     * 第二阶段：切回原模式 / 还原效果、着火、氧气。
     * 此时玩家已经**在原位**当了 5 tick 旁观者，客户端位置插值已完成，切模式不会在目标面前闪现。
     */
    private fun finishRestore(peeker: ServerPlayerEntity, saved: PeekState.SavedState): Boolean {
        return try {
            // 双保险：相机仍强制指向自己（跨维度后客户端可能重建过实体）
            forceCamera(peeker, peeker)

            // 枚举容错：非法值 / DEFAULT（伪模式）一律落 SURVIVAL；SPECTATOR 说明开始前本来就是旁观者，保持不变
            val mode = runCatching { GameMode.valueOf(saved.gameMode) }.getOrNull()
                ?.takeIf { it != GameMode.DEFAULT } ?: GameMode.SURVIVAL
            if (mode != GameMode.SPECTATOR) {
                peeker.interactionManager.changeGameMode(mode)
            }
            // 即使 changeGameMode 因「已经是该模式」空跑，也把真实模式再推一次，修客户端残留
            forceGameModeSync(peeker)
            // 跨维度返回时客户端可能还没加载完，重生流程会用旧 spawnInfo 覆盖模式 ⇒ 再延迟重推几次
            scheduleModeResync(peeker)

            // 还原状态效果（逐条容错：某个效果 id 在当前服不存在也不影响其余）
            peeker.clearStatusEffects()
            saved.effects.forEach { effectNbt ->
                try {
                    peeker.addStatusEffect(StatusEffectInstance.fromNbt(effectNbt))
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.warn(
                        "Peek: skipping unreadable saved effect for {}: {}", peeker.gameProfile.name, e.message
                    )
                }
            }
            peeker.setFireTicks(saved.fireTicks)
            peeker.setAir(saved.air)
            // 5 tick 的等待窗口里玩家可能又站进了传送门：收尾时一并清掉，恢复正常使用
            peeker.resetPortalCooldown()
            peeker.portalManager = null
            true
        } catch (e: Exception) {
            CobbleMarket.LOGGER.error(
                "Peek: failed to finish restoring {} — session kept", peeker.gameProfile.name, e
            )
            false
        }
    }

    /** 立即完整还原（关服等「没有下一 tick」的场景） */
    private fun restoreNow(
        server: MinecraftServer,
        peeker: ServerPlayerEntity,
        saved: PeekState.SavedState,
    ): RestoreOutcome = when (prepareRestore(server, peeker, saved)) {
        PrepareOutcome.NOT_SPECTATOR -> RestoreOutcome.NOT_SPECTATOR
        PrepareOutcome.FAILED -> RestoreOutcome.FAILED
        PrepareOutcome.READY ->
            if (finishRestore(peeker, saved)) RestoreOutcome.RESTORED else RestoreOutcome.FAILED
    }

    /**
     * 收尾：**先保存玩家数据、再删 PeekState**（反向崩溃窗口，见 stopInternal 旧注释），然后给提示。
     *
     * @return 总是 true（保持调用方 `return` 语义）；保存失败时保留状态并阻塞自动重试
     */
    private fun finishCleanup(
        server: MinecraftServer,
        peeker: ServerPlayerEntity,
        peekerUuid: UUID,
        reason: StopReason,
        notifyPeeker: Boolean,
        restored: Boolean,
    ): Boolean {
        // ⚠⚠ **先把还原后的玩家数据落盘，再删 PeekState**：
        //   旁观期间 MC 的自动保存可能已把磁盘上的玩家数据存成「旁观者 + 目标位置」；
        //   若先删状态、玩家数据还没保存就崩溃 ⇒ 重启后「磁盘是旁观者、又没有恢复状态」= 永久卡旁观。
        val playerDataSaved = try {
            server.playerManager.saveAllPlayerData()
            true
        } catch (e: Exception) {
            CobbleMarket.LOGGER.error(
                "Peek: failed to save player data after restoring {}; keeping the session for the next login",
                peeker.gameProfile.name, e
            )
            false
        }
        if (!playerDataSaved) {
            // 状态保留，并暂时别让 tick 每 tick 重试（下次登录 / 手动 stop 会清掉并重试）
            blockedCleanup += peekerUuid
            notify(peeker, "cobblemarket.peek.restore_failed", Formatting.RED)
            return true
        }
        blockedCleanup.remove(peekerUuid)
        lastLeashNoticeAt.remove(peekerUuid)

        if (notifyPeeker) {
            val key = reason.messageKey
            if (key != null) notify(peeker, key, Formatting.GREEN)
        }
        val state = PeekState.get(server)
        state.remove(peekerUuid)
        state.saveNow(server)
        // 状态真正删掉之后才通知客户端（还原失败 / 状态保留的路径不会走到这里 ⇒ HUD 继续提示可重试）
        PeekNetwork.sendActive(peeker, false)
        CobbleMarket.LOGGER.info(
            "Peek ended: {} (reason={}, restored={})", peekerUuid, reason.name, restored
        )
        return true
    }

    /**
     * 立即收尾一个挂起的 [PendingFinalize]（重新开始监视 / 关服时用）。
     *
     * @return false = 收尾失败（状态已保留并阻塞），调用方应中止新的开始流程
     */
    private fun finishPendingFinalizeNow(
        server: MinecraftServer,
        player: ServerPlayerEntity,
    ): Boolean {
        val pending = pendingFinalizes.remove(player.uuid) ?: return true
        val state = PeekState.get(server)
        val saved = state.get(player.uuid) ?: return true
        if (!player.isSpectator) {
            // 等待期间玩家自己切了模式：不覆盖，只把客户端残留的旁观视觉同步掉
            try {
                player.setCameraEntity(null)
                forceCamera(player, player)
                forceGameModeSync(player)
                scheduleModeResync(player)
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Peek: failed to sync client state for {}: {}", player.gameProfile.name, e.message)
            }
        } else if (!finishRestore(player, saved)) {
            blockedCleanup += player.uuid
            return false
        }
        // 重新开始 / 切换目标：这是「中间步骤」，不给玩家发旧会话的结束提示
        finishCleanup(
            server, player, player.uuid, pending.reason,
            notifyPeeker = false,
            restored = true,
        )
        return true
    }

    // ── 客户端状态强制同步（跨维度后原版的包可能被静默丢弃，见各处注释）──

    /** 强制客户端把相机切到 [entity]：跳过服务端「没变化就不发包」的短路 */
    private fun forceCamera(player: ServerPlayerEntity, entity: Entity) {
        try {
            player.networkHandler.sendPacket(SetCameraEntityS2CPacket(entity))
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Peek: failed to force camera sync for {}: {}", player.gameProfile.name, e.message)
        }
    }

    /**
     * 把服务端真实游戏模式完整重推给客户端。
     *
     * ⚠ 三个包缺一不可：
     * - `GameStateChange(GAME_MODE_CHANGED)`：更新客户端的 `interactionManager.gameMode`
     *   （旁观飞行锁定 / 破坏方块权限等都看它）；
     * - `PlayerList UPDATE_GAME_MODE`：更新客户端玩家列表条目（`isSpectator` / 皮肤/名单 UI 看它）；
     * - abilities：更新 allowFlying / flying。
     * 原版 `/gamemode` 走 `ServerPlayerEntity.changeGameMode`，这三样都会发；我们直接调
     * `interactionManager.changeGameMode` 时少了 GameState，导致进旁观不自动飞（2026-11-10 实测）。
     */
    private fun forceGameModeSync(player: ServerPlayerEntity) {
        try {
            val mode = player.interactionManager.gameMode
            player.networkHandler.sendPacket(
                GameStateChangeS2CPacket(
                    GameStateChangeS2CPacket.GAME_MODE_CHANGED,
                    mode.id.toFloat()
                )
            )
            player.networkHandler.sendPacket(
                PlayerListS2CPacket(PlayerListS2CPacket.Action.UPDATE_GAME_MODE, player)
            )
            player.sendAbilitiesUpdate()
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Peek: failed to force game mode sync for {}: {}", player.gameProfile.name, e.message)
        }
    }

    /** 还原后排几次延迟重推游戏模式（见 [pendingModeSyncs] 的说明） */
    private fun scheduleModeResync(player: ServerPlayerEntity) {
        val now = player.server.ticks
        pendingModeSyncs[player.uuid] = MODE_RESYNC_DELAYS.map { now + it }.toMutableList()
    }

    /** 处理到期的游戏模式重推；每 tick 都要跑，即使当前没有监视会话 */
    private fun processPendingModeSyncs(server: MinecraftServer, now: Int) {
        if (pendingModeSyncs.isEmpty()) return
        pendingModeSyncs.entries.toList().forEach { (uuid, dueTicks) ->
            val player = server.playerManager.getPlayer(uuid)
            if (player == null) {
                pendingModeSyncs.remove(uuid)
                return@forEach
            }
            var sent = false
            while (dueTicks.isNotEmpty() && dueTicks.first() <= now) {
                dueTicks.removeAt(0)
                sent = true
            }
            if (sent) forceGameModeSync(player)
            if (dueTicks.isEmpty()) pendingModeSyncs.remove(uuid)
        }
    }

    /**
     * 阻止监视者的「身体」使用传送门（见 [PORTAL_BLOCK_TICKS]）。
     *
     * 冷却每 tick 补一次还不够稳：`tryUsePortal` 会把冷却清零，若同一 tick 内门管理器已经建起来，
     * 它会绕过冷却继续倒计时。所以顺手把 [net.minecraft.entity.Entity.portalManager] 清掉 ——
     * 监视者本来就不该靠传送门换维度，换维度统一由我们的延迟跟随负责。
     */
    private fun blockPortals(player: ServerPlayerEntity) {
        player.setPortalCooldown(PORTAL_BLOCK_TICKS)
        player.portalManager = null
    }

    /** 把玩家当前位置立即广播给其他客户端（不等实体追踪的下一 tick） */
    private fun broadcastPosition(server: MinecraftServer, player: ServerPlayerEntity) {
        try {
            val packet = EntityPositionS2CPacket(player)
            server.playerManager.playerList.forEach { other ->
                if (other !== player) {
                    try {
                        other.networkHandler.sendPacket(packet)
                    } catch (e: Exception) {
                        // 单个连接异常不影响其他人
                        CobbleMarket.LOGGER.warn("Peek: failed to broadcast position to {}: {}", other.gameProfile.name, e.message)
                    }
                }
            }
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Peek: failed to broadcast position for {}: {}", player.gameProfile.name, e.message)
        }
    }

    /** 传回的位置；保存的维度不可用时退回主世界出生点（并 warn，供服主排查） */
    private class RestoreSpot(val world: ServerWorld, val x: Double, val y: Double, val z: Double)

    private fun resolveSpot(server: MinecraftServer, saved: PeekState.SavedState): RestoreSpot {
        val key = try {
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of(saved.worldKey))
        } catch (_: Exception) {
            null
        }
        val world = key?.let { server.getWorld(it) }
        if (world != null) return RestoreSpot(world, saved.x, saved.y, saved.z)

        CobbleMarket.LOGGER.warn(
            "Peek: saved world '{}' is unavailable; sending the player to the overworld spawn", saved.worldKey
        )
        val spawn = server.overworld.spawnPos
        return RestoreSpot(server.overworld, spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5)
    }

    /** 捕获监视开始前的状态（**没有背包**，见 PeekState 类注释） */
    private fun capture(player: ServerPlayerEntity, targetUuid: UUID): PeekState.SavedState {
        val effects = player.statusEffects.mapNotNull { effect ->
            try {
                effect.writeNbt() as? net.minecraft.nbt.NbtCompound
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Peek: failed to capture effect for {}: {}", player.gameProfile.name, e.message)
                null
            }
        }.toMutableList()
        return PeekState.SavedState(
            targetUuid = targetUuid,
            worldKey = player.serverWorld.registryKey.value.toString(),
            x = player.x,
            y = player.y,
            z = player.z,
            yaw = player.yaw,
            pitch = player.pitch,
            gameMode = player.interactionManager.gameMode.name,
            effects = effects,
            fireTicks = player.fireTicks,
            air = player.air,
            startedAt = System.currentTimeMillis(),
        )
    }

    // ── 每 tick 校验 ──

    /**
     * END_SERVER_TICK 调用：只遍历会话表（通常 0～几人），开销可忽略。
     * ⚠ 不要把它塞进 PersistHelper；独立调用。
     */
    fun tick(server: MinecraftServer) {
        val now = server.ticks

        // ⓪ 还原后的延迟游戏模式重推（即使当前没有任何监视会话也要跑：
        //   会话在 stop 后就删掉了，而客户端可能还在跨维度加载）
        processPendingModeSyncs(server, now)

        // ① 登录后的崩溃恢复检查（延迟 3 tick）
        if (pendingRestores.isNotEmpty()) {
            pendingRestores.filterValues { now >= it }.keys.toList().forEach { uuid ->
                pendingRestores.remove(uuid)
                val player = server.playerManager.getPlayer(uuid) ?: return@forEach
                runPendingRestore(server, player)
            }
        }

        val state = PeekState.get(server)
        if (!state.hasSessions()) {
            if (pendingFinalizes.isNotEmpty()) pendingFinalizes.clear()
            return
        }

        // ② 已传回原位、等插值结束后切模式的会话
        processPendingFinalizes(server, state, now)

        // ③ 会话有效性
        val toStop = mutableListOf<Pair<UUID, StopReason>>()
        state.all().forEach { (peekerUuid, saved) ->
            val peeker = server.playerManager.getPlayer(peekerUuid)
            if (peeker == null) {
                // 监视者离线：**保留状态**等下次登录还原（方案 §2.2），tick 不处理
                return@forEach
            }
            if (peekerUuid in pendingFinalizes) {
                // 已经在「传回原位、等切模式」的阶段：不要再按监视会话校验；
                // 但仍要压住传送门，避免这 5 tick 里又建起门管理器
                blockPortals(peeker)
                return@forEach
            }
            if (peekerUuid in blockedCleanup) {
                // 已还原、但玩家数据上次没存上：状态留到下次登录 / 手动 stop，别每 tick 重试
                return@forEach
            }
            // 权限被收回（节点 / 服主名单变更）⇒ 会话立刻失效并还原，别让被撤权的人继续旁观
            if (!MarketPermissions.has(peeker, MarketPermissions.OVERVIEW)) {
                toStop += peekerUuid to StopReason.PERMISSION_REVOKED
                return@forEach
            }
            if (!peeker.isSpectator) {
                // 自己切回了其他模式：会话失效，清理状态但**不还原**（避免覆盖玩家手动操作）
                toStop += peekerUuid to StopReason.PEEKER_NOT_SPECTATOR
                return@forEach
            }
            // ⚠ 监视期间**禁止监视者的身体进传送门**：监视只支持同维度，身体被门带走会让
            //   还原变成跨维度（客户端重生流程容易残留游戏模式）。换维度统一由「结束监视」处理。
            blockPortals(peeker)

            val target = server.playerManager.getPlayer(saved.targetUuid)
            if (target == null) {
                toStop += peekerUuid to StopReason.TARGET_OFFLINE
                return@forEach
            }
            if (!target.isAlive || target.isRemoved) {
                toStop += peekerUuid to StopReason.TARGET_DEAD
                return@forEach
            }
            // 目标中途变成旁观者（自己开始监视别人 / 手动切旁观）⇒ 结束并还原：
            // 不堵这个口子的话，软牵引会把监视者一起带到目标的新位置，变成「偷看一个正在偷看别人的旁观者」
            //（2026-11-10 用户实测）。与开始时「旁观者不能当目标」是同一条规则。
            if (target.isSpectator) {
                toStop += peekerUuid to StopReason.TARGET_SPECTATOR
                return@forEach
            }
            if (peeker.serverWorld.registryKey != target.serverWorld.registryKey) {
                // 本功能只支持同维度监视（跨维度还原的客户端模式同步不可靠）。
                // 提示要分清是谁离开的：saved.worldKey 是监视开始时 staff 的维度（开始要求同维度，
                // 所以也是目标的起始维度）；目标当前 key 与它不同 = 目标走的，否则 = staff 走的。
                val targetChanged = target.serverWorld.registryKey.value.toString() != saved.worldKey
                toStop += peekerUuid to if (targetChanged) {
                    StopReason.TARGET_CHANGED_DIMENSION
                } else {
                    StopReason.PEEKER_CHANGED_DIMENSION
                }
                return@forEach
            }
            // 同维度：不自动拖拽（Peek 是自由观察）；但用 64 格软牵引防止把监视当成全图旁观飞行
            leashIfTooFar(peeker, target)
        }
        toStop.forEach { (uuid, reason) -> stopInternal(server, uuid, reason, notifyPeeker = true) }
    }

    /**
     * **软牵引**（2026-11-10 用户拍板）：监视者（或其附身的相机实体）离目标超过 [MAX_DISTANCE] 格时，
     * 清相机 + 把身体带回目标身边，并给一条节流后的行动栏提示。
     *
     * - 附身在**目标本人**身上时相机实体就是目标（距离 0），不触发；
     * - 相机实体在别的维度视同超距（跨维度坐标比较没有意义）；
     * - 目标自己传送 / 跑远也会把监视者一起带回来 —— 目标甩不掉监视；
     * - 只处理距离；换维度仍走 tick 里前面那条「结束并还原」。
     */
    private fun leashIfTooFar(peeker: ServerPlayerEntity, target: ServerPlayerEntity) {
        val focus = peeker.cameraEntity ?: peeker
        val sameWorld = focus.world === target.world
        val tooFar = !sameWorld || focus.squaredDistanceTo(target) > MAX_DISTANCE * MAX_DISTANCE
        if (!tooFar) return
        try {
            // 先清相机：否则会挂着远处被附身的生物 / 玩家的视角继续看
            peeker.setCameraEntity(null)
            forceCamera(peeker, peeker)
            if (peeker.isInTeleportationState()) peeker.onTeleportationDone()
            // 保留当前朝向，视角不跟着甩一下
            peeker.teleport(target.serverWorld, target.x, target.y, target.z, peeker.yaw, peeker.pitch)
            // 监视期间一直压着传送门（见 blockPortals）
            blockPortals(peeker)
            val now = System.currentTimeMillis()
            if (now - (lastLeashNoticeAt[peeker.uuid] ?: 0L) >= LEASH_NOTICE_INTERVAL_MS) {
                lastLeashNoticeAt[peeker.uuid] = now
                peeker.sendMessage(
                    Text.translatable("cobblemarket.peek.leash", MAX_DISTANCE.toInt())
                        .formatted(Formatting.YELLOW),
                    true
                )
            }
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn(
                "Peek: failed to leash {} back to {}: {}",
                peeker.gameProfile.name, target.gameProfile.name, e.message
            )
        }
    }

    /**
     * 处理到期的「传回后切模式」。
     *
     * 第一阶段已把玩家传回原位且仍保持旁观；这里等位置插值完成后再切模式、还原效果，
     * 最后按「先保存玩家数据、再删状态」收尾。
     */
    private fun processPendingFinalizes(server: MinecraftServer, state: PeekState, now: Int) {
        if (pendingFinalizes.isEmpty()) return
        pendingFinalizes.filterValues { now >= it.dueTick }.keys.toList().forEach { peekerUuid ->
            val pending = pendingFinalizes.remove(peekerUuid) ?: return@forEach
            val saved = state.get(peekerUuid)
            if (saved == null) return@forEach
            val peeker = server.playerManager.getPlayer(peekerUuid) ?: return@forEach
            if (!peeker.isSpectator) {
                // 等待期间被玩家/其他插件切了模式：不覆盖，只同步客户端后清理状态
                try {
                    peeker.setCameraEntity(null)
                    forceCamera(peeker, peeker)
                    forceGameModeSync(peeker)
                    scheduleModeResync(peeker)
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.warn("Peek: failed to sync client state for {}: {}", peeker.gameProfile.name, e.message)
                }
                finishCleanup(server, peeker, peekerUuid, pending.reason, pending.notifyPeeker, restored = false)
                return@forEach
            }
            if (!finishRestore(peeker, saved)) {
                // 保留状态 / 阻塞自动重试；等待玩家手动 stop 或重登恢复
                blockedCleanup += peekerUuid
                if (pending.notifyPeeker) notify(peeker, "cobblemarket.peek.restore_failed", Formatting.RED)
                return@forEach
            }
            finishCleanup(server, peeker, peekerUuid, pending.reason, pending.notifyPeeker, restored = true)
        }
    }

    // ── 登录 / 掉线 / 死亡 / 关服 ──

    /**
     * 登录：若磁盘上有该玩家的监视状态，**延迟 3 tick** 做崩溃恢复检查（照 Peek 的 JOIN 调度）。
     *
     * ⚠ 不在 JOIN 当刻立刻还原：玩家数据与客户端刚同步，等 3 tick 更稳。
     */
    fun onPlayerJoin(player: ServerPlayerEntity) {
        val server = player.server
        if (PeekState.get(server).get(player.uuid) == null) return
        // 重进时允许重试上次「玩家数据落盘失败」的清理
        blockedCleanup.remove(player.uuid)
        pendingRestores[player.uuid] = server.ticks + CRASH_RECOVERY_DELAY_TICKS
        // 重进后客户端默认不知道还在监视：当前是旁观者 = 会话仍然有效，先打开 HUD 提示；
        // 3 tick 后还原会发 false。不是旁观者 = 陈旧状态（马上会被丢弃），不发 true 免得提示闪一下。
        if (player.isSpectator) PeekNetwork.sendActive(player, true)
    }

    /**
     * 掉线：
     * - 该玩家是**目标** ⇒ 停止所有以他为目标的会话并还原监视者
     * - 该玩家是**监视者** ⇒ 只清内存待办，**保留 [PeekState]**，等下次登录还原（方案 §2.2）
     */
    fun onPlayerDisconnect(player: ServerPlayerEntity) {
        val server = player.server
        pendingRestores.remove(player.uuid)
        pendingModeSyncs.remove(player.uuid)
        lastLeashNoticeAt.remove(player.uuid)
        // 监视者掉线：挂起的「等插值切模式」取消，状态保留；玩家数据会在掉线时被 MC 存成
        // 「旁观 + 已传回的原位」，下次登录照常还原
        pendingFinalizes.remove(player.uuid)
        blockedCleanup.remove(player.uuid)

        val affected = PeekState.get(server).all()
            .filterValues { it.targetUuid == player.uuid }
            .keys.toList()
        affected.forEach { stopInternal(server, it, StopReason.TARGET_OFFLINE, notifyPeeker = true) }
    }

    /** 玩家死亡：若他是目标 ⇒ 停止会话并还原监视者（照 Peek 的 AFTER_DEATH） */
    fun onPlayerDeath(player: ServerPlayerEntity) {
        val server = player.server
        val affected = PeekState.get(server).all()
            .filterValues { it.targetUuid == player.uuid }
            .keys.toList()
        affected.forEach { stopInternal(server, it, StopReason.TARGET_DEAD, notifyPeeker = true) }
    }

    /**
     * 正常关服：尽力还原**在线**的监视者（此时玩家数据随后会保存，最干净）。
     * 离线会话的状态**保留**在磁盘上，等下次登录还原 —— 不能在这里删，否则对方会卡在旁观者。
     */
    fun onServerStopping(server: MinecraftServer) {
        val state = PeekState.get(server)
        state.all().forEach { (peekerUuid, saved) ->
            val peeker = server.playerManager.getPlayer(peekerUuid) ?: return@forEach
            // 关服没有下一 tick 可以等插值：直接完整还原（此时目标也在下线，不会在意闪现）
            restoreNow(server, peeker, saved)
            // ⚠ 有意**不删状态**、也不在这里额外写盘：
            //   正常关服随后会保存还原后的玩家数据，下次登录 sanity check 看到「不是旁观者」即丢弃陈旧状态；
            //   万一关服过程中崩溃，磁盘上的状态还能在下次登录把玩家救回来。
        }
        pendingFinalizes.clear()
        pendingModeSyncs.clear()
        pendingRestores.clear()
        blockedCleanup.clear()
    }

    /** 登录后的恢复检查：**sanity** —— 当前不是旁观者就丢弃陈旧状态，绝不覆盖玩家当前位置 */
    private fun runPendingRestore(server: MinecraftServer, player: ServerPlayerEntity) {
        val state = PeekState.get(server)
        state.get(player.uuid) ?: return
        if (!player.isSpectator) {
            CobbleMarket.LOGGER.info(
                "Peek: discarding stale session for {} (current mode {})",
                player.gameProfile.name, player.interactionManager.gameMode.name
            )
            state.remove(player.uuid)
            state.saveNow(server)
            return
        }
        CobbleMarket.LOGGER.info("Peek: restoring {} after reconnect / crash", player.gameProfile.name)
        stopInternal(server, player.uuid, StopReason.RECOVERED, notifyPeeker = true)
    }

    // ── 文案 ──

    private fun notify(player: ServerPlayerEntity, key: String, color: Formatting, vararg args: Any) {
        player.sendMessage(Text.translatable(key, *args).formatted(color), false)
    }

    /**
     * 目标维度文本：原版三维度用「友好名（id）」，模组 / 多世界维度直接显示 registry id。
     *
     * ⚠ 服务端只构造 `Text`，不 `.string`、不拼语言相关标点 —— 友好名和括号都交给客户端翻译；
     *   模组维度没有统一的显示名 API，id（`namespace:path`）是唯一精确且跨包可用的标识。
     */
    private fun dimensionText(world: ServerWorld): Text {
        val id = world.registryKey.value.toString()
        val friendlyKey = when (id) {
            "minecraft:overworld" -> "cobblemarket.peek.dimension.overworld"
            "minecraft:the_nether" -> "cobblemarket.peek.dimension.nether"
            "minecraft:the_end" -> "cobblemarket.peek.dimension.end"
            else -> return Text.literal(id)
        }
        return Text.translatable(
            "cobblemarket.peek.dimension.with_id",
            Text.translatable(friendlyKey),
            Text.literal(id)
        )
    }
}
