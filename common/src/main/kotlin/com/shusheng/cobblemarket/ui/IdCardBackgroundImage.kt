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
        - The card is drawn at a fixed size on screen. The image is scaled so that its
          bottom "width x 9/16" part fills the card exactly; anything above that is an
          overflow decoration drawn above the card (e.g. a Pokemon sitting on the card's
          top edge). The built-in image deliberately leaves that area blank, so server
          packs can put their own mascot / title there.
        - Leave "idCardBackground" blank to use the mod's built-in background.
        - Images are read on demand (no restart needed); replacing one takes effect the
          next time a player opens the ID card (overwriting the same file in place works too).

        市场 ID 卡背景图目录
        ====================
        把 ID 卡的背景图放在这里（**只写文件名，不带路径**），
        然后把文件名填进 `config/cobblemarket.json` 的 `idCardBackground`。

        - 只认 PNG，单张不超过 512KB、像素不超过 2048×2048。
        - 卡片在屏幕上按固定尺寸绘制。图按「**从底边往上、宽 × 9/16 那块 = 卡片本体**」缩放：
          那块正好填满卡片，上面多出来的部分是**溢出装饰**，会画在卡片上方
          （比如一只趴在卡片顶边的精灵）。模组内置的那张**刻意把这块留空**，
          留给服主放自己的吉祥物 / 标题。
        - `idCardBackground` **留空 = 用模组内置的背景图**。
        - 读图是按需的（改图不用重启），换图对下一次打开 ID 卡生效（就地覆盖同名文件也算）。
    """.trimIndent()

    /** 确保背景图目录存在（顺手放一份说明）。服务端启动时调一次 */
    fun ensureDir() {
        ConfigImage.writeReadmeIfAbsent(BG_DIR, README_TEXT)
    }

    /**
     * 带**版本戳**读指定文件名的背景图；null = 没配 / 读不了 / 不合法（客户端回退到内置背景）。
     *
     * 版本（`文件名@修改时间`）要发给客户端、由它下次回传 ⇒ 服务端据此判断**要不要重传图**
     * （详见 [com.shusheng.cobblemarket.network.IdCardBackgroundPayload]）。
     */
    fun loadVersioned(fileName: String): ConfigImage.Versioned? = ConfigImage.loadVersioned(BG_DIR, fileName)
}
