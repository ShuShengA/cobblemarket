package com.shusheng.cobblemarket.client

import com.mojang.authlib.GameProfile
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Identifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * **玩家头像纹理缓存（全项目共用）**。
 *
 * 各交易界面（精灵市场 / 拍卖场 / 求购单 / 喵喵银行 / 两个管理端）都要画卖家头像。
 * 原实现是「在线走 tab 列表皮肤，离线走 `skinProvider.getSkinTextures`，失败拿默认皮肤」，
 * 而且**把默认皮肤也写进了缓存** —— 一旦某次查到默认（卖家不在线、皮肤还没加载），
 * 这个 UUID 就永远是 Steve（2026-10-27 用户报：卖家下线后头像全变原版）。
 *
 * 这里改成：
 *  - 只缓存**真实纹理**，默认皮肤不落缓存；
 *  - 离线 UUID 首次出现时用 [net.minecraft.client.texture.PlayerSkinProvider.fetchSkinTextures]
 *    异步拉一次（Mojang 会话服务），成功后落缓存，界面下一帧自动生效；
 *  - 请求按 UUID 去重；失败 30 秒后才允许重试（避免每帧狂发请求）。
 *
 * ⚠ 离线 UUID（`OfflinePlayer` 生成的 v3 UUID）在 Mojang 查不到皮肤 —— 这类玩家永远拿不到
 *   官方皮肤，只能显示默认；正版 UUID 的玩家拉一次后就能一直显示。
 */
object PlayerSkinCache {

    /** 默认皮肤（宽/瘦两种模型）；`fetchSkinTextures` 对「没有皮肤」的玩家也会返回它们 */
    private val DEFAULT_WIDE = Identifier.of("minecraft", "textures/entity/player/wide/steve.png")
    private val DEFAULT_SLIM = Identifier.of("minecraft", "textures/entity/player/slim/alex.png")

    /** 真实纹理缓存：UUID → 皮肤纹理 */
    private val cache = ConcurrentHashMap<UUID, Identifier>()

    /** 已经发过请求的 UUID → 上次请求时间（去重 + 失败冷却） */
    private val pending = ConcurrentHashMap<UUID, Long>()

    /** 失败后至少隔这么久才重试 */
    private const val RETRY_MS = 30_000L

    /**
     * 取某个玩家头像的纹理；拿不到时返回默认皮肤。
     *
     * 首次遇到离线玩家会**异步**发起一次皮肤拉取（不阻塞渲染），完成后的下一次渲染即生效。
     */
    fun skinFor(uuid: UUID, name: String): Identifier {
        cache[uuid]?.let { return it }
        val client = MinecraftClient.getInstance()

        // 1) 在线玩家：tab 列表里已经有皮肤纹理
        client.networkHandler?.getPlayerListEntry(uuid)?.skinTextures?.texture()?.let { tex ->
            cache[uuid] = tex
            return tex
        }

        // 2) 离线玩家：异步拉取（按 UUID 去重，失败 30 秒后重试）
        val now = System.currentTimeMillis()
        val last = pending[uuid]
        if (last == null || now - last > RETRY_MS) {
            pending[uuid] = now
            try {
                client.skinProvider.fetchSkinTextures(GameProfile(uuid, name)).thenAccept { textures ->
                    val tex = textures?.texture()
                    // ⚠ 拉到的若是**默认皮肤**（没有皮肤的玩家 / 离线 UUID 查不到），
                    //   不能当真实纹理缓存 —— 否则又回到「永远是 Steve」的老毛病
                    if (tex != null && tex != DEFAULT_WIDE && tex != DEFAULT_SLIM) {
                        cache[uuid] = tex
                        pending.remove(uuid)
                    }
                }
            } catch (_: Throwable) {
                // 拉取失败：保留 pending 时间戳，冷却后再试
            }
        }

        return DEFAULT_WIDE
    }
}
