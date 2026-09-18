package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.util.ConfigImage

/**
 * **档位「RP 券」图片**（`config/cobblemarket/icons/<文件名>`，2026-09-18 用户提出）。
 *
 * 与收款码同一个套路（服务端读 → S2C 下发字节 → 客户端动态纹理），但用途不同：
 * 收款码是**下单后按订单**才要，券图是**进充值界面就要**（档位卡上一直画着）。
 *
 * - 每个档位可配自己的券图（`tiers[].icon`）；**没配的档位走内置图**
 *   （`textures/gui/points_icon.png`，仍可用资源包替换）—— 所以没配图的档位**不走网络**。
 * - 尺寸不写死：客户端按**图片实际宽高比**算卡片高度，服主给什么比例的图都不会变形。
 *
 * 读取、安全校验（防路径穿越 / PNG 魔数 / 512KB 上限）与缓存都在 [ConfigImage]。
 */
object TierIconImage {

    /** 券图子目录名（在 `config/cobblemarket/` 下，与 `qr/` 平级） */
    const val ICON_DIR = "icons"

    /** 目录说明（首次运行时落一份，之后不覆盖） */
    private val README_TEXT = """
        CobbleMarket - RP coupon images
        ===============================

        Put your RP coupon images HERE (plain file name, no path), then fill the file name
        for each tier in game: Admin Panel -> Server Config -> RP Market -> edit a tier.

        - PNG only. Max 512KB and max 2048x2048 pixels per image.
        - Recommended size: 256x160 (the built-in coupon's ratio) or a multiple of it
          such as 512x320. The tier card's image box has that ratio, so a matching image
          fills it exactly; other ratios are scaled to fit and leave blank space around.
        - Tiers without one use the built-in coupon image (which server packs may replace).
        - Files are read on demand (no restart needed); replacing an image takes effect
          the next time a player opens the top-up screen.

        RP 券图目录
        ==========
        把档位的「RP 券」图放在这里（只写文件名，不带路径），
        然后在游戏里填：管理面板 -> 服务器配置 -> RP 市场
        （主界面的「默认券图」一行管全部档位；单档还可以在「编辑档位」里各配一张）。

        - 只认 PNG，单张不超过 512KB、像素不超过 2048×2048。
        - **建议尺寸 256×160**（内置券图的比例）或它的整数倍（512×320）：
          档位卡的图框就是这个比例，同比例的图正好填满；其他比例会等比缩放居中、四周留白。
        - 没配的档位走「默认券图」，连默认都没配才用模组内置的那张。
        - 读图是按需的（改图不用重启），换图对下一次打开充值界面生效。
    """.trimIndent()

    /** 确保券图目录存在（顺手放一份说明）。服务端启动时调一次 */
    fun ensureDir() {
        ConfigImage.writeReadmeIfAbsent(ICON_DIR, README_TEXT)
    }

    /** 读指定文件名的券图；null = 没配 / 读不了 / 不合法（客户端回退到内置图） */
    fun load(fileName: String): ByteArray? = ConfigImage.load(ICON_DIR, fileName)
}
