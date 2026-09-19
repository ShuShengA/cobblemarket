package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.util.ConfigImage

/**
 * **服主收款码图片**（`config/cobblemarket/qr/<文件名>`）。
 *
 * ⚠ **放在 `qr/` 子目录**（2026-09-18 用户提的）：`cobblemarket/` 根下已经混着 json 与账本目录
 * `points/`，图片再堆进来更乱。配置里只写文件名（`5.png`），目录是固定的，不用手写路径。
 *
 * 支持**多张码**（2026-09-18 用户提出）：档位可以各配一张"带金额"的收款码
 * （微信/支付宝能生成固定金额的码，玩家扫了直接付、不会输错），没配的档位走全局那张。
 *
 * 读取、安全校验（防路径穿越 / PNG 魔数 / 512KB 上限）与缓存都在 [ConfigImage]，
 * 这里只管本目录的名字与说明。
 */
object PayQrImage {

    /** 收款码子目录名（在 `config/cobblemarket/` 下） */
    const val QR_DIR = "qr"

    /** 目录说明（首次运行时落一份，之后不覆盖） */
    private val README_TEXT = """
        CobbleMarket - RP top-up payment QR codes
        =========================================

        Put YOUR payment QR image HERE (plain file name, no path), then fill in that
        file name in game: Admin Panel -> Server Config -> RP Market.

        ANY payment method that gives you a QR code works: PayPal, Cash App, Venmo,
        Ko-fi, Buy Me a Coffee, a bank transfer QR, WeChat / Alipay, ...

        - PNG only. Max 512KB and max 2048x2048 pixels per image.
        - A payment QR is square: about 512x512 is plenty. The popup draws the image
          at most 208x110 pixels, so a very wide image gets scaled down and hard to scan.
        - The mod cannot verify payments (no provider gives us a callback), so players
          tap "I have paid" as a CLAIM and you grant the RP after checking your own
          statement. The mod shows the order number and the exact amount right next to
          the QR, and never bakes them into the image.
        - ⚠ IF YOUR PROVIDER CHARGES A FEE (PayPal, Stripe, ...): the sum that actually
          reaches you will NOT equal what the player sent, so the amount alone cannot
          be matched. Use the ORDER NUMBER instead, and tell players to
          put it in the payment note (the popup shows it prominently for that reason).
        - A tier may have its OWN QR image ("fixed amount" codes, e.g. made with
          WeChat/Alipay's "set amount" feature): that tier then charges the list price
          exactly. Tiers without one fall back to the global image, and are charged the
          list price too.
        - Files are read on demand (no restart needed); replacing an image takes effect
          for the next order opened.

        收款码目录
        ==========
        把微信/支付宝的收款码 PNG 放在这里（只写文件名，不带路径），
        然后在游戏里填：管理面板 -> 服务器配置 -> RP 市场。

        - 只认 PNG，单张不超过 512KB、像素不超过 2048×2048。
        - 收款码本身是正方形，512×512 左右就够。弹窗里最大只画 208×110 像素，
          宽扁的图会被缩得很小、容易扫不出来。
        - 档位可以配自己的收款码（微信/支付宝的「设置金额」码），
          该档实付金额就等于码上的金额；没配专用码的档位走全局码，实付同样是档位标价。
        - 读图是按需的（改图不用重启），换了图对下一笔新订单生效。
        - ⚠ 用**收手续费**的渠道（PayPal / Stripe 等）时到账金额会少于玩家付的，
          此时请改用**订单号**核对（弹窗里订单号是显眼展示的，
          提醒玩家把它填进付款备注）。
    """.trimIndent()

    /** 单机切存档 / 换服时复位（静态状态不跨世界） */
    fun reset() {
        ConfigImage.reset()
    }

    /** 确保收款码目录存在（顺手放一份说明）。服务端启动时调一次 */
    fun ensureDir() {
        ConfigImage.writeReadmeIfAbsent(QR_DIR, README_TEXT)
    }

    /** 读指定文件名的收款码；null = 没配 / 读不了 / 不合法（由调用方回退到全局码） */
    fun load(fileName: String): ByteArray? = ConfigImage.load(QR_DIR, fileName)

    /**
     * 商店"有没有收款码可用"：全局那张能读出来就算有 —— 用于决定入口按钮与提示文案。
     * 单个档位自己的码是否存在，由 [load] 按请求时判断。
     */
    fun globalAvailable(): Boolean = load(CobbleMarketConfig.pointsShopPayQrImage) != null
}
