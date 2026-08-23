package com.shusheng.cobblemarket.client

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.io.File

/**
 * 客户端个人设置（观感偏好，每个玩家自己一份，与服务端 CobbleMarketConfig 各管各的）。
 *
 * 与服务端总开关的关系：服务端关掉是**压根不发包**（全服都没有），这里的开关只决定
 * “收到包之后播不播”。两层互不依赖，服主能一刀切，玩家也能自己关。
 *
 * 动画开关按**频率**分两个而不是按来源分三个：市场直购随时可买、最高频，
 * 拍卖成交与求购单接受都是低频事件，合用一个开关。
 */
object ClientConfig {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val configFile: File
        get() = FabricLoader.getInstance().configDir.resolve("cobblemarket-client.json").toFile()

    /** 市场直接购买精灵时的庆祝动画 */
    var celebrationOnMarketBuy: Boolean = true
        private set

    /** 拍卖成交、求购单接受交付时的庆祝动画 */
    var celebrationOnAuctionAndOrder: Boolean = true
        private set

    /** 入口界面打开时的掉落动画 */
    var entryDropAnimation: Boolean = true
        private set

    /** 皮卡丘跑酷：开启后皮卡丘绕入口/管理面板背景边缘环绕跑（默认直线顶部跑） */
    var pikachuRunLoop: Boolean = false
        private set

    /** 固拉多飞行：开启后在精灵市场/上架选择界面穿梭飞行（默认关） */
    var groudonFly: Boolean = false
        private set

    /** 领取待领取物品时装不下的部分掉落在地（默认关=留在待领取，下次再领） */
    var dropOverflowOnClaim: Boolean = false
        private set

    fun load() {
        if (!configFile.exists()) {
            save()
            return
        }
        try {
            val data = gson.fromJson(configFile.readText(), Map::class.java) as? Map<*, *> ?: emptyMap<Any?, Any?>()
            // 兼容早期只有单个总开关的配置：拿它当两个新开关的初值，随后落盘换成新字段
            val legacy = data["celebrationAnimationEnabled"] as? Boolean
            celebrationOnMarketBuy = data["celebrationOnMarketBuy"] as? Boolean ?: legacy ?: true
            celebrationOnAuctionAndOrder = data["celebrationOnAuctionAndOrder"] as? Boolean ?: legacy ?: true
            dropOverflowOnClaim = data["dropOverflowOnClaim"] as? Boolean ?: false
            entryDropAnimation = data["entryDropAnimation"] as? Boolean ?: true
            pikachuRunLoop = data["pikachuRunLoop"] as? Boolean ?: false
            groudonFly = data["groudonFly"] as? Boolean ?: false
            if (legacy != null) save()
        } catch (e: Exception) {
            CobbleMarketClient.LOGGER.warn("Failed to load client config: ${e.message}")
            save()
        }
    }

    fun setCelebrationOnMarketBuy(v: Boolean) {
        celebrationOnMarketBuy = v
        save()
    }

    fun setCelebrationOnAuctionAndOrder(v: Boolean) {
        celebrationOnAuctionAndOrder = v
        save()
    }

    fun setDropOverflowOnClaim(v: Boolean) {
        dropOverflowOnClaim = v
        save()
    }

    fun setEntryDropAnimation(v: Boolean) {
        entryDropAnimation = v
        save()
    }

    fun setPikachuRunLoop(v: Boolean) {
        pikachuRunLoop = v
        save()
    }

    fun setGroudonFly(v: Boolean) {
        groudonFly = v
        save()
    }

    private fun save() {
        try {
            configFile.writeText(
                gson.toJson(
                    mapOf(
                        "_comments" to mapOf(
                            "celebrationOnMarketBuy" to "市场直接购买精灵时是否播放庆祝动画（个人设置，可在市场入口界面右下角的设置里改）/ Whether to play the celebration animation when buying a Pokémon directly from the market (personal setting, editable via the gear button on the market entry screen)",
                            "celebrationOnAuctionAndOrder" to "拍卖成交、求购单接受交付时是否播放庆祝动画（个人设置，同上）/ Whether to play the celebration animation when winning an auction or accepting a buy order delivery (personal setting, same place)",
                            "dropOverflowOnClaim" to "领取待领取物品时，装不下的部分掉落在地（可能消失或被他人捡走，风险自负）/ When claiming item returns, drop the parts that don't fit into the inventory onto the ground (they may despawn or be picked up by others — at your own risk)",
                            "entryDropAnimation" to "按快捷键/手机App打开市场入口时是否播放进入市场动画（个人设置）/ Whether to play the market entry animation when opening the market via hotkey or smartphone app (personal setting)",
                            "pikachuRunLoop" to "皮卡丘跑步机：开启后皮卡丘绕入口/管理面板背景边缘环绕跑（默认沿背景顶部直线跑）/ Pikachu Treadmill: when on, Pikachu runs around the border of the entry/admin background instead of the straight top run",
                            "groudonFly" to "据说固拉多一生都在寻找这个按钮：开启后固拉多在精灵市场/上架选择界面穿梭飞行 / It is said Groudon spends its whole life looking for this button: when on, Groudon flies across the pokemon market and sell-select screens",
                            "_note" to "服主还可在服务端配置 cobblemarket.json 的 celebrationAnimationEnabled 里全局关闭动画，那种情况下本文件的开关不起作用 / The server owner can also disable animations globally via celebrationAnimationEnabled in the server-side cobblemarket.json, in which case these switches have no effect"
                        ),
                        "celebrationOnMarketBuy" to celebrationOnMarketBuy,
                        "celebrationOnAuctionAndOrder" to celebrationOnAuctionAndOrder,
                        "dropOverflowOnClaim" to dropOverflowOnClaim,
                        "entryDropAnimation" to entryDropAnimation,
                        "pikachuRunLoop" to pikachuRunLoop,
                        "groudonFly" to groudonFly
                    )
                )
            )
        } catch (e: Exception) {
            CobbleMarketClient.LOGGER.warn("Failed to save client config: ${e.message}")
        }
    }
}
