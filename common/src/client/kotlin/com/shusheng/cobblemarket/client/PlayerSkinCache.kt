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
        val client = MinecraftClient.getInstance()

        // 1) 在线玩家：**先查 tab 列表，再读缓存**。
        //
        // ⚠ 顺序不能反（2026-11-19 用户报「换皮肤重进后，在线玩家面板还显示旧头像」）：
        //   之前是「缓存命中直接 return」，玩家重登换了皮肤后 tab 列表已是新纹理，
        //   但缓存里存着旧纹理 ⇒ **一直显示旧的，直到客户端重启**（面板每 2 秒刷新名单也没用，
        //   因为皮肤根本不从名单里取）。
        //   现在每次渲染都以 tab 列表为准并**回写缓存** ⇒ 在线玩家的皮肤变化下一帧就生效。
        //   tab 查询只是一次 map 查找，开销可以忽略。
        //
        // ⚠ **默认皮肤不能落缓存**：tab 条目刚建/皮肤还没加载时 texture 可能是 Steve/Alex，
        //   写进 cache 就永远默认了（KDoc 说"只缓存真实纹理"，但原实现漏了这一步）。
        val handler = client.networkHandler
        handler?.getPlayerListEntry(uuid)?.skinTextures?.texture()?.let { tex ->
            if (tex != DEFAULT_WIDE && tex != DEFAULT_SLIM) {
                cache[uuid] = tex
                return tex
            }
        }
        // 1b) UUID 对不上时按**名字**再查一次 tab 列表：正版服务器 + 离线登录模组（EasyAuth 等）
        //     可能让服务端实体的 UUID 与客户端 tab 条目的 UUID 不同（离线注册玩家尤其明显），
        //     只按 UUID 查会落到 Mojang 异步拉取，离线 UUID 查不到 ⇒ 错误显示默认 Steve；
        //     而 tab 列表里其实有这名玩家的正确皮肤（世界里也显示正常）。按名字兜底即可。
        handler?.getPlayerListEntry(name)?.skinTextures?.texture()?.let { tex ->
            if (tex != DEFAULT_WIDE && tex != DEFAULT_SLIM) {
                cache[uuid] = tex
                return tex
            }
        }

        // 2) 不在线 / tab 里暂时还是默认皮肤：用缓存（离线玩家的唯一来源）。
        cache[uuid]?.let { return it }

        // 3) 离线玩家：异步拉取（按 UUID 去重，失败 30 秒后重试）
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
