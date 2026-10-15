package com.shusheng.cobblemarket.ui

import com.shusheng.cobblemarket.util.ConfigImage

/**
 * **市场 ID 卡的背景图**（`config/cobblemarket/idcard/<文件名>`）。
 *
 * 服务端读盘 → S2C 下发字节 → 客户端动态纹理（照收款码 / RP 券图那一套：
 * 配置文件在服务端，图要在玩家客户端上画）。
 *
 * - **没配 = 客户端用模组内置的背景**（`textures/gui/` 下那张，仍可用资源包替换）
 *   ⇒ 没配图的服**完全不产生网络流量**。
 * - 尺寸不写死：客户端按图片**实际宽高比**缩放居中，服主给什么比例都不会变形。
 *
 * 读取、安全校验（防路径穿越 / PNG 魔数 / 512KB 与 2048×2048 上限）与缓存都在 [ConfigImage]。
 */
object IdCardBackgroundImage {

    /** 背景图子目录名（在 `config/cobblemarket/` 下） */
    const val BG_DIR = "idcard"

    /** 目录说明（随版本覆盖，别改成"只写一次"—— 新限制要能到老服主手里） */
    private val README_TEXT = """
        CobbleMarket - Market ID card background
        ========================================

        Put your ID card background image HERE (plain file name, no path), then put that
        file name into config/cobblemarket.json -> "idCardBackground".

        - PNG only. Max 512KB, max 2048x2048 pixels per image.
        - The card is drawn at a fixed size on screen; the image is scaled to fit and
          centred, so any aspect ratio works (matching the card's ratio fills it exactly).
        - Leave "idCardBackground" blank to use the mod's built-in background.
        - Images are read on demand (no restart needed); replacing one takes effect the
          next time a player opens the ID card.

        市场 ID 卡背景图目录
        ====================
        把 ID 卡的背景图放在这里（**只写文件名，不带路径**），
        然后把文件名填进 `config/cobblemarket.json` 的 `idCardBackground`。

        - 只认 PNG，单张不超过 512KB、像素不超过 2048×2048。
        - 卡片在屏幕上按固定尺寸绘制，图会等比缩放居中，什么比例的图都不会变形
          （与卡片比例一致的图正好填满）。
        - `idCardBackground` **留空 = 用模组内置的背景图**。
        - 读图是按需的（改图不用重启），换图对下一次打开 ID 卡生效。
    """.trimIndent()

    /** 确保背景图目录存在（顺手放一份说明）。服务端启动时调一次 */
    fun ensureDir() {
        ConfigImage.writeReadmeIfAbsent(BG_DIR, README_TEXT)
    }

    /** 读指定文件名的背景图；null = 没配 / 读不了 / 不合法（客户端回退到内置背景） */
    fun load(fileName: String): ByteArray? = ConfigImage.load(BG_DIR, fileName)
}
