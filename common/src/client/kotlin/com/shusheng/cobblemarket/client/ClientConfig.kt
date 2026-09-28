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
 * 庆祝动画开关按**精灵 / 物品**分两组，每组内再按频率分两个：市场直购随时可买、最高频，
 * 拍卖成交与求购单接受都是低频事件，合用一个开关。两组各自独立——玩家关掉精灵动画，
 * 本意是「我买精灵别弹」，不该波及物品。
 */
/** 精灵图标展示模式 */
enum class IconAnimMode {
    /** 完全静态：固定姿势 + 固定旋转角（视觉偏好选项，性能与浮动无差别） */
    STATIC,
    /** 动态（默认）：播放 idle 待机动画（与 Cobblemon 队伍界面同款），几乎零开销 */
    FLOAT
}

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

    /** 市场上直接购买物品时的庆祝动画（物品侧，与精灵侧各自独立；默认开） */
    var celebrationItemOnMarketBuy: Boolean = true
        private set

    /** 拍卖成交、求购单接受交付（物品侧）时的庆祝动画（默认开） */
    var celebrationItemOnAuctionAndOrder: Boolean = true
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

    /**
     * 余额 HUD 显示模式（个人设置，设置弹窗三态循环）：ALWAYS 一直显示 / ON_CHANGE 余额变动后显示 5 秒 / OFF 关闭。
     *
     * ⚠ **管的是整个 HUD**（含下面 RP 那一行）—— 两行共用同一个背景框，所以同显同隐、一起淡出。
     * 2026-09-28 合并：原先 RP 行有自己的 `pointsHudMode`，但那个开关**没有任何界面入口**
     *（只能手改 json），而一个框挂两个开关本身也说不通。ON_CHANGE 的判据是**任一行**余额变动 ——
     * 主货币变了、或 RP 变了，都会把整框唤出来
     */
    var balanceHudMode: BalanceHudMode = BalanceHudMode.ALWAYS
        private set

    /** 精灵图标展示模式（个人设置，设置弹窗两态循环）：STATIC 静态 / FLOAT 动态（默认） */
    var iconAnimMode: IconAnimMode = IconAnimMode.FLOAT
        private set

    /** 悬停精灵行时，在屏幕左侧显示该精灵的放大预览（个人设置，设置弹窗开关，默认开） */
    var hoverPreview: Boolean = true
        private set

    /** 悬停物品格时，在屏幕左侧显示该物品的放大预览（与精灵侧各自独立，默认开） */
    var hoverPreviewItem: Boolean = true
        private set

    /** 自定义鼠标光标：在本模组的界面里用模组自带的图标代替系统箭头（默认开）。
     *  默认开是因为绝大多数玩家用的是 Windows 默认箭头；但用系统光标主题的玩家会看着别扭，故留开关 */
    var customCursor: Boolean = true
        private set

    /** 余额 HUD 位置（归一化 0~1：x 是 HUD 左缘在「屏宽 − HUD 宽」中的占比，0=贴左、1=贴右；y 同理按高度）。
     *  默认 (0, 0) = 左上角（老配置无此字段即保持原行为）。由「设置 → 余额HUD位置设置 → 自定义」拖动写入 */
    var balanceHudX: Float = 0f
        private set
    var balanceHudY: Float = 0f
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
            // 物品侧两组与精灵侧各自独立，**不继承** legacy/精灵侧的取值：老玩家关掉精灵动画，
            // 本意是「我买精灵别弹」，不该波及物品；物品侧给默认「开」是新增功能该有的默认值
            celebrationItemOnMarketBuy = data["celebrationItemOnMarketBuy"] as? Boolean ?: true
            celebrationItemOnAuctionAndOrder = data["celebrationItemOnAuctionAndOrder"] as? Boolean ?: true
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
            // 只有显式存过 0（静态）才保持静态；无字段（旧版本升级）、1（浮动）、2（已砍掉的完整动画占位）一律按新默认浮动
            iconAnimMode = if (data["iconAnimMode"] as? Double == 0.0) IconAnimMode.STATIC else IconAnimMode.FLOAT
            // 无字段（旧版本升级）按新默认「开」
            hoverPreview = data["hoverPreview"] as? Boolean ?: true
            // 物品侧独立开关：无字段（旧版本升级）同样按新默认「开」
            hoverPreviewItem = data["hoverPreviewItem"] as? Boolean ?: true
            customCursor = data["customCursor"] as? Boolean ?: true
            // 位置：无字段（旧版本升级）按左上角；越界值钳回 0~1
            balanceHudX = ((data["balanceHudX"] as? Double)?.toFloat() ?: 0f).coerceIn(0f, 1f)
            balanceHudY = ((data["balanceHudY"] as? Double)?.toFloat() ?: 0f).coerceIn(0f, 1f)
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

    fun setCelebrationItemOnMarketBuy(v: Boolean) {
        celebrationItemOnMarketBuy = v
        save()
    }

    fun setCelebrationItemOnAuctionAndOrder(v: Boolean) {
        celebrationItemOnAuctionAndOrder = v
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

    fun setHoverPreview(v: Boolean) {
        hoverPreview = v
        save()
    }

    fun setHoverPreviewItem(v: Boolean) {
        hoverPreviewItem = v
        save()
    }

    fun setCustomCursor(v: Boolean) {
        customCursor = v
        save()
    }

    fun setBalanceHudMode(v: BalanceHudMode) {
        balanceHudMode = v
        save()
    }

    /** 两态循环：STATIC ↔ FLOAT */
    fun cycleIconAnimMode(): IconAnimMode {
        iconAnimMode = when (iconAnimMode) {
            IconAnimMode.STATIC -> IconAnimMode.FLOAT
            IconAnimMode.FLOAT -> IconAnimMode.STATIC
        }
        save()
        return iconAnimMode
    }

    /** 保存余额 HUD 位置（归一化坐标，越界自动钳制）：位置编辑界面点「确定」时调用 */
    fun setBalanceHudPosition(x: Float, y: Float) {
        balanceHudX = x.coerceIn(0f, 1f)
        balanceHudY = y.coerceIn(0f, 1f)
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
                            "celebrationOnMarketBuy" to "市场直接购买精灵时是否播放庆祝动画（含 RP 商城；个人设置，可在市场入口界面右下角的设置里改）/ Whether to play the celebration animation when buying a Pokémon directly from the market (including the RP shop; personal setting, editable via the gear button on the market entry screen)",
                            "celebrationOnAuctionAndOrder" to "拍卖成交、求购单接受交付时是否播放庆祝动画（个人设置，同上）/ Whether to play the celebration animation when winning an auction or accepting a buy order delivery (personal setting, same place)",
                            "celebrationItemOnMarketBuy" to "市场上直接购买物品时是否播放庆祝动画（含 RP 商城；个人设置，与精灵侧各自独立，同上）/ Whether to play the celebration animation when buying an item directly from the market (including the RP shop; personal setting, independent from the Pokemon side, same place)",
                            "celebrationItemOnAuctionAndOrder" to "拍卖成交、求购单接受交付（物品）时是否播放庆祝动画（个人设置，同上）/ Whether to play the celebration animation when winning an auction or accepting a buy order delivery (items) (personal setting, same place)",
                            "dropOverflowOnClaim" to "领取待领取物品时，装不下的部分掉落在地（可能消失或被他人捡走，风险自负）/ When claiming item returns, drop the parts that don't fit into the inventory onto the ground (they may despawn or be picked up by others — at your own risk)",
                            "marketAnimation" to "市场动画总开关：控制进入市场动画（入口掉落）与关闭市场动画（界面整体上滑出屏）（个人设置，可在市场入口界面右下角的设置里改）/ Master switch for market animations: controls the entry animation (drop onto the entry screen) and the close animation (screen slides up and out) (personal setting, editable via the gear button on the market entry screen)",
                            "pikachuRunLoop" to "皮卡丘跑步机：开启后皮卡丘绕入口/管理面板背景边缘环绕跑（默认沿背景顶部直线跑）/ Pikachu Treadmill: when on, Pikachu runs around the border of the entry/admin background instead of the straight top run",
                            "groudonFly" to "据说固拉多一生都在寻找这个按钮：开启后固拉多在精灵市场/上架选择界面穿梭飞行 / It is said Groudon spends its whole life looking for this button: when on, Groudon flies across the pokemon market and sell-select screens",
                            "balanceHudMode" to "余额 HUD 显示模式（含 RP 那一行，两行同显同隐）：0=一直显示（默认），1=余额变动时显示 5 秒，2=关闭；可在市场入口界面右下角的设置里改 / Balance HUD mode (includes the RP row — both rows show and hide together): 0=always show (default), 1=show 5 seconds when the balance changes, 2=off; editable via the gear button on the market entry screen",
                            "iconAnimMode" to "精灵图标展示模式：0=完全静态，1=动态（默认，播放 Cobblemon 内置待机动画）；可在市场入口界面右下角的设置里改 / Pokemon icon mode: 0=static, 1=dynamic (default, plays Cobblemon's built-in idle animation); editable via the gear button on the market entry screen",
                            "hoverPreview" to "悬停精灵行时在屏幕左侧显示该精灵的放大预览（个人设置，可在市场入口界面右下角的设置里改）/ Show a large preview of the Pokemon on the left side of the screen when hovering its row (personal setting, editable via the gear button on the market entry screen)",
                            "hoverPreviewItem" to "悬停物品格时在屏幕左侧显示该物品的放大预览（个人设置，与精灵侧各自独立，同上）/ Show a large preview of the item on the left side of the screen when hovering its slot (personal setting, independent from the Pokemon side, same place)",
                            "customCursor" to "在本模组的界面里用模组自带的图标代替系统鼠标箭头（个人设置，默认开启；用系统光标主题的玩家可关掉）/ Replace the system mouse arrow with the mod's own icon while in this mod's screens (personal setting, on by default; turn off if you use a custom system cursor theme)",
                            "balanceHudX" to "余额 HUD 水平位置（0=贴左，1=贴右，0.5=居中）；由设置里的「余额HUD位置设置 → 自定义」拖动写入，手改请填 0~1 / Balance HUD horizontal position (0=left, 1=right, 0.5=centered); written by dragging in Settings → Balance HUD position → Custom; keep within 0~1 if editing by hand",
                            "balanceHudY" to "余额 HUD 垂直位置（0=贴顶，1=贴底，0.5=居中）；同样由拖动写入 / Balance HUD vertical position (0=top, 1=bottom, 0.5=centered); likewise written by dragging",
                            "_note" to "庆祝动画完全由玩家自己决定（市场入口右下角齿轮 → 设置）：服务端不再有总开关，本文件的开关就是唯一口径 / Celebration animations are entirely up to each player (gear button at the bottom-right of the market entry screen → Settings): there is no server-side master switch any more, these switches are the only thing that matters"
                        ),
                        "celebrationOnMarketBuy" to celebrationOnMarketBuy,
                        "celebrationOnAuctionAndOrder" to celebrationOnAuctionAndOrder,
                        "celebrationItemOnMarketBuy" to celebrationItemOnMarketBuy,
                        "celebrationItemOnAuctionAndOrder" to celebrationItemOnAuctionAndOrder,
                        "dropOverflowOnClaim" to dropOverflowOnClaim,
                        "marketAnimation" to marketAnimation,
                        "pikachuRunLoop" to pikachuRunLoop,
                        "groudonFly" to groudonFly,
                        "balanceHudMode" to balanceHudMode.ordinal,
                        "iconAnimMode" to iconAnimMode.ordinal,
                        "hoverPreview" to hoverPreview,
                        "hoverPreviewItem" to hoverPreviewItem,
                        "customCursor" to customCursor,
                        "balanceHudX" to balanceHudX,
                        "balanceHudY" to balanceHudY
                    )
                )
            )
        } catch (e: Exception) {
            CobbleMarketClient.LOGGER.warn("Failed to save client config: ${e.message}")
        }
    }
}
