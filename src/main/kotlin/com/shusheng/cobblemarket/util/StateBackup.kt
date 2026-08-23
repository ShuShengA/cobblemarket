package com.shusheng.cobblemarket.util

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.server.MinecraftServer
import net.minecraft.util.WorldSavePath
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * PersistentState 数据文件（world/data/cobblemarket*.dat）的校验与备份。
 *
 * Minecraft 的 PersistentStateManager 在文件整体损坏时会静默丢弃整个状态，
 * 对经济模组等于全部挂单与余额蒸发。本工具：
 * 1. SERVER_STARTING（世界加载前）：校验 .dat 可完整解压，损坏则尝试从 .bak 恢复；
 * 2. SERVER_STOPPED（正常关服保存完成后）：用最新 .dat 刷新 .bak。
 *
 * .bak 只有在通过可读性校验后才被刷新，因此它永远是一个可恢复的完整文件，
 * 不会把运行中写坏一半的 .dat 复制进备份链。
 */
object StateBackup {

    fun verifyAndBackup(server: MinecraftServer) {
        try {
            val stateFiles = listStateFiles(server) ?: return
            stateFiles.forEach { file ->
                val backup = backupFile(file)
                if (!isReadable(file)) {
                    if (backup.exists()) {
                        try {
                            Files.copy(backup.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            CobbleMarket.LOGGER.warn(
                                "CobbleMarket state file {} was corrupted; restored from backup",
                                file.name
                            )
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.error("Failed to restore {} from backup: {}", file.name, e.message)
                        }
                    } else {
                        // 无备份可恢复：把损坏文件改名保留（.corrupt），给人工/工具修复留机会。
                        // MC 启动时找不到 .dat 会静默新建空状态——不保留的话损坏原件会被覆盖，彻底没救
                        val corruptFile = File(file.parentFile, file.name + ".corrupt")
                        try {
                            Files.move(file.toPath(), corruptFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            CobbleMarket.LOGGER.error(
                                "CobbleMarket state file {} is corrupted and no backup exists; preserved as {} for manual recovery",
                                file.name, corruptFile.name
                            )
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.error("Failed to preserve corrupted state file {}: {}", file.name, e.message)
                        }
                    }
                } else {
                    copyToBackup(file)
                }
            }
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("CobbleMarket state backup failed: {}", e.message)
        }
    }

    /** 正常关服后调用：此时 PersistentState 刚写完盘，刷新 .bak 为最新完整数据。 */
    fun backupOnStop(server: MinecraftServer) {
        backupAll(server)
    }

    /** 通用备份：交易保存/断开保存前调用，刷新 .bak 为当前 .dat 内容（防保存过程断电写坏） */
    fun backupAll(server: MinecraftServer) {
        try {
            val stateFiles = listStateFiles(server) ?: return
            stateFiles.forEach { file -> copyToBackup(file) }
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("CobbleMarket stop backup failed: {}", e.message)
        }
    }

    private fun listStateFiles(server: MinecraftServer): Array<File>? {
        val dataDir = server.getSavePath(WorldSavePath.ROOT).resolve("data").toFile()
        if (!dataDir.isDirectory) return null
        return dataDir.listFiles { f ->
            f.isFile && f.name.startsWith(CobbleMarket.MOD_ID) && f.name.endsWith(".dat")
        }
    }

    private fun backupFile(file: File): File = File(file.parentFile, file.name + ".bak")

    private fun isReadable(file: File): Boolean = try {
        NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes())
        true
    } catch (e: Exception) {
        false
    }

    private fun copyToBackup(file: File) {
        if (!isReadable(file)) {
            // 不把读不出来的文件写进 .bak，保持恢复链始终可用
            CobbleMarket.LOGGER.warn("State file {} unreadable; keeping existing backup", file.name)
            return
        }
        try {
            Files.copy(file.toPath(), backupFile(file).toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to back up {}: {}", file.name, e.message)
        }
    }
}
