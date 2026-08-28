package com.shusheng.cobblemarket.client

import com.google.gson.GsonBuilder
import com.shusheng.cobblemarket.platform.configDir
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
/** 余额 HUD 显示模式 */
enum class BalanceHudMode {
    /** 一直显示 */
    ALWAYS,
    /** 余额变动后显示 5 秒 */
    ON_CHANGE,
    /** 关闭 */
    OFF
}

object ClientConfig {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val configFile: File
        get() = configDir().resolve("cobblemarket-client.json").toFile()

    /** 市场直接购买精灵时的庆祝动画 */
    var celebrationOnMarketBuy: Boolean = true
        private set

    /** 拍卖成交、求购单接受交付时的庆祝动画 */
    var celebrationOnAuctionAndOrder: Boolean = true
        private set

    /** 市场动画总开关：控制进入市场动画（入口掉落）与关闭市场动画（界面整体上滑出屏） */
    var marketAnimation: Boolean = true
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

    /** 余额 HUD 显示模式（个人设置，设置弹窗三态循环）：ALWAYS 一直显示 / ON_CHANGE 余额变动后显示 5 秒 / OFF 关闭 */
    var balanceHudMode: BalanceHudMode = BalanceHudMode.ALWAYS
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
            marketAnimation = data["marketAnimation"] as? Boolean ?: data["entryDropAnimation"] as? Boolean ?: true
            pikachuRunLoop = data["pikachuRunLoop"] as? Boolean ?: false
            groudonFly = data["groudonFly"] as? Boolean ?: false
            // 旧布尔开关升级映射：true → ALWAYS，false → OFF；新配置存三态 Int
            balanceHudMode = when (data["balanceHudMode"] as? Double) {
                1.0 -> BalanceHudMode.ON_CHANGE
                2.0 -> BalanceHudMode.OFF
                else -> if (data["showBalanceHud"] as? Boolean == false) BalanceHudMode.OFF else BalanceHudMode.ALWAYS
            }
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

    fun setMarketAnimation(v: Boolean) {
        marketAnimation = v
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

    fun setBalanceHudMode(v: BalanceHudMode) {
        balanceHudMode = v
        save()
    }

    /** 三态循环：ALWAYS → ON_CHANGE → OFF → ALWAYS */
    fun cycleBalanceHudMode(): BalanceHudMode {
        balanceHudMode = when (balanceHudMode) {
            BalanceHudMode.ALWAYS -> BalanceHudMode.ON_CHANGE
            BalanceHudMode.ON_CHANGE -> BalanceHudMode.OFF
            BalanceHudMode.OFF -> BalanceHudMode.ALWAYS
        }
        save()
        return balanceHudMode
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
                            "marketAnimation" to "市场动画总开关：控制进入市场动画（入口掉落）与关闭市场动画（界面整体上滑出屏）（个人设置，可在市场入口界面右下角的设置里改）/ Master switch for market animations: controls the entry animation (drop onto the entry screen) and the close animation (screen slides up and out) (personal setting, editable via the gear button on the market entry screen)",
                            "pikachuRunLoop" to "皮卡丘跑步机：开启后皮卡丘绕入口/管理面板背景边缘环绕跑（默认沿背景顶部直线跑）/ Pikachu Treadmill: when on, Pikachu runs around the border of the entry/admin background instead of the straight top run",
                            "groudonFly" to "据说固拉多一生都在寻找这个按钮：开启后固拉多在精灵市场/上架选择界面穿梭飞行 / It is said Groudon spends its whole life looking for this button: when on, Groudon flies across the pokemon market and sell-select screens",
                            "balanceHudMode" to "余额 HUD 显示模式：0=一直显示（默认），1=余额变动时显示 5 秒，2=关闭；可在市场入口界面右下角的设置里改 / Balance HUD mode: 0=always show (default), 1=show 5 seconds when the balance changes, 2=off; editable via the gear button on the market entry screen",
                            "_note" to "服主还可在服务端配置 cobblemarket.json 的 celebrationAnimationEnabled 里全局关闭动画，那种情况下本文件的开关不起作用 / The server owner can also disable animations globally via celebrationAnimationEnabled in the server-side cobblemarket.json, in which case these switches have no effect"
                        ),
                        "celebrationOnMarketBuy" to celebrationOnMarketBuy,
                        "celebrationOnAuctionAndOrder" to celebrationOnAuctionAndOrder,
                        "dropOverflowOnClaim" to dropOverflowOnClaim,
                        "marketAnimation" to marketAnimation,
                        "pikachuRunLoop" to pikachuRunLoop,
                        "groudonFly" to groudonFly,
                        "balanceHudMode" to balanceHudMode.ordinal
                    )
                )
            )
        } catch (e: Exception) {
            CobbleMarketClient.LOGGER.warn("Failed to save client config: ${e.message}")
        }
    }
}
