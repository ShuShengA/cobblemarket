package com.shusheng.cobblemarket.util

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.server.MinecraftServer
import net.minecraft.util.WorldSavePath
import net.minecraft.util.Formatting
import java.io.File

/**
 * 交易数据强制落盘：
 *
 * MC 的默认保存时机是自动保存（默认 5 分钟）+ 正常关服。玩家退出时 MC 会立即保存该玩家数据，
 * 而模组状态（挂单/订单/拍卖）仍等在自动保存里——此时杀进程/崩溃会形成错位：玩家数据已落盘
 * （货已扣）、模组状态回滚（挂单没了），货/钱蒸发；反过来保存顺序错位则货复制。
 *
 * 策略：
 * 1. 交易成功后 requestSave：3 秒节流合并，到点执行全量 saveAll——玩家数据与模组状态同步落盘，
 *    杀进程时两边一起回滚（或一起保留），绝无错位；
 * 2. 玩家断开时 onPlayerDisconnect：玩家数据刚被 MC 保存，立即保存模组状态追上去（蒸发洞窗口 = 0）；
 * 3. 每次保存前刷新 .bak 备份（防保存过程中断电写坏文件）；
 * 4. 保存后按文件 mtime 验证是否真的写盘（MC 会吞掉 PersistentState 写盘异常并清除脏标志，
 *    不做验证的话「以为存了其实没存」）——失败时日志 error + 给在线 OP 发显眼告警。
 */
object PersistHelper {

    private const val SAVE_COOLDOWN_MS = 3000L
    /** neoforge 的 PersistentState.save 走 IO worker 异步写盘，验证须延迟到写盘完成后 */
    private const val VERIFY_DELAY_MS = 1000L
    private const val VERIFY_RETRY_MS = 2000L
    private const val VERIFY_MAX_ATTEMPTS = 3

    private var pendingSave = false
    private var lastTradeAt = 0L

    // 延迟验证状态：mtime 对比推迟到异步 IO 写盘完成后（见 tick）
    private var pendingVerifyBefore: Map<String, Long?> = emptyMap()
    private var pendingVerifyAt = 0L
    private var verifyAttempts = 0

    /** 交易成功后调用：节流合并，到点全量落盘 */
    fun requestSave(server: MinecraftServer) {
        pendingSave = true
        lastTradeAt = System.currentTimeMillis()
    }

    /**
     * 服务器停止/新世界启动时清空全部待办：PersistHelper 是进程级静态，单机切换存档
     * （同一进程）时未到期的延迟验证/节流保存会残留到新世界，用旧世界的 mtime 快照
     * 对比新世界文件导致误报「保存失败」。数据本身由 MC 关服保存保证落盘，无需补救。
     */
    fun reset() {
        pendingSave = false
        lastTradeAt = 0L
        pendingVerifyAt = 0L
        pendingVerifyBefore = emptyMap()
        verifyAttempts = 0
    }

    /** 玩家断开后调用（延迟一 tick，确保 MC 已完成该玩家数据的保存）：立即保存模组状态 */
    fun onPlayerDisconnect(server: MinecraftServer) {
        server.execute { saveModStates(server) }
    }

    /** END_SERVER_TICK 调用：延迟验证到期检查 + 节流到点执行全量保存 */
    fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        verifyPending(server, now)
        if (pendingSave && now - lastTradeAt >= SAVE_COOLDOWN_MS) {
            pendingSave = false
            StateBackup.backupAll(server)
            val before = stateFileMtimes(server)
            val ok = server.saveAll(false, false, false)
            if (!ok) {
                // saveAll 返回 false = 明确失败，立即告警（无需等 mtime）
                alertSaveFailed(server)
            } else {
                scheduleVerify(before)
            }
        }
    }

    /** 立即保存模组状态（玩家数据由 MC 在断开时已保存，这里只追模组，窗口 0 防蒸发） */
    private fun saveModStates(server: MinecraftServer) {
        // 无未落盘变更时跳过：状态已与玩家数据一致；此时 save() 因无脏数据不写文件，
        // mtime 验证会把「无需保存」误报为保存失败
        if (!pendingSave) return
        pendingSave = false
        StateBackup.backupAll(server)
        val before = stateFileMtimes(server)
        server.overworld.persistentStateManager.save()
        scheduleVerify(before)
    }

    /** 延迟验证：neoforge 的 save/saveAll 异步写盘，立即查 mtime 会误报「保存失败」 */
    private fun scheduleVerify(before: Map<String, Long?>) {
        pendingVerifyBefore = before
        pendingVerifyAt = System.currentTimeMillis() + VERIFY_DELAY_MS
        verifyAttempts = 1
    }

    private fun verifyPending(server: MinecraftServer, now: Long) {
        if (pendingVerifyAt <= 0 || now < pendingVerifyAt) return
        val changed = stateFileMtimes(server).any { (name, mtime) -> mtime != pendingVerifyBefore[name] }
        if (!changed && verifyAttempts < VERIFY_MAX_ATTEMPTS) {
            // 写盘可能仍在进行（异步 IO），延长重试
            verifyAttempts++
            pendingVerifyAt = now + VERIFY_RETRY_MS
            return
        }
        pendingVerifyAt = 0
        if (!changed) {
            alertSaveFailed(server)
        }
    }

    /** 保存失败告警：日志 error + 给在线 OP 发显眼提示 */
    private fun alertSaveFailed(server: MinecraftServer) {
        CobbleMarket.LOGGER.error("CobbleMarket state save failed or was skipped (mtime unchanged after {} verification attempts)", verifyAttempts)
        val msg = net.minecraft.text.Text.translatable("cobblemarket.persist.save_failed").formatted(Formatting.RED)
        server.playerManager.playerList
            .filter { it.hasPermissionLevel(2) }
            .forEach { it.sendMessage(msg, false) }
    }

    /** world/data 下全部 cobblemarket*.dat 的修改时间快照（不存在 = null） */
    private fun stateFileMtimes(server: MinecraftServer): Map<String, Long?> {
        val dataDir = server.getSavePath(WorldSavePath.ROOT).resolve("data").toFile()
        if (!dataDir.isDirectory) return emptyMap()
        val result = mutableMapOf<String, Long?>()
        dataDir.listFiles { f -> f.isFile && f.name.startsWith(CobbleMarket.MOD_ID) && f.name.endsWith(".dat") }
            ?.forEach { f -> result[f.name] = f.lastModified() }
        // 空目录时对比两边都为空 → changed=false 会误报；但交易后必有状态文件存在，兜底放一个哨兵
        if (result.isEmpty()) result["(none)"] = 0L
        return result
    }
}
