package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.ClientConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class AdminScreen : Screen(Text.translatable("cobblemarket.op.title")) {

    private var btnStartY = 0
    private var totalH = 0

    companion object {
        // 皮卡丘跑步动画参数（与入口界面一致，共享时间基准实现切换不重置）
        private const val PIKA_CYCLE_MS = 3000L
        private const val PIKA_FRAME_MS = 100L
        private const val PIKA_LOOP_MS = 8000L
    }

    private fun addMenuButton(x: Int, y: Int, w: Int, h: Int, text: Text, action: net.minecraft.client.gui.widget.ButtonWidget.PressAction, iconLeft: Identifier? = null): TextureButton {
        val btn = TextureButton(x, y, w, h, text, action, iconLeft = iconLeft)
        addDrawableChild(btn)
        return btn
    }

    override fun init() {
        super.init()
        val centerX = width / 2
        val btnW = 87
        val btnH = 20
        val gap = 6
        val colGap = 2
        val totalW = btnW * 2 + colGap
        val leftX = centerX - totalW / 2
        val rightX = leftX + btnW + colGap
        val rowCount = 5
        totalH = btnH * rowCount + gap * (rowCount - 1)
        val startY = height / 2 - totalH / 2 + 5 // 按钮整体下移 5px
        btnStartY = startY

        // 行 1
        addMenuButton(
            leftX, startY, btnW, btnH,
            Text.translatable("cobblemarket.op.pokemon"),
            { client?.setScreen(AdminPokemonScreen()) }
        )
        addMenuButton(
            rightX, startY, btnW, btnH,
            Text.translatable("cobblemarket.op.item"),
            { client?.setScreen(AdminItemScreen()) }
        )
        // 行 2
        addMenuButton(
            leftX, startY + btnH + gap, btnW, btnH,
            Text.translatable("cobblemarket.ban.title"),
            { client?.setScreen(AdminBanScreen()) }
        )
        addMenuButton(
            rightX, startY + btnH + gap, btnW, btnH,
            Text.translatable("cobblemarket.entry.all_history"),
            { client?.setScreen(HistoryScreen(true)) }
        )
        // 行 3：黑名单（左）+ 价格限制（右）
        addMenuButton(
            leftX, startY + (btnH + gap) * 2, btnW, btnH,
            Text.translatable("cobblemarket.op.blacklist"),
            { client?.setScreen(BlacklistScreen()) }
        )
        addMenuButton(
            rightX, startY + (btnH + gap) * 2, btnW, btnH,
            Text.translatable("cobblemarket.op.price_limit"),
            { client?.setScreen(PriceLimitScreen()) }
        )
        // 行 4：所有拍卖（左）+ 所有求购（右）
        addMenuButton(
            leftX, startY + (btnH + gap) * 3, btnW, btnH,
            Text.translatable("cobblemarket.op.auction"),
            { client?.setScreen(AdminAuctionScreen()) }
        )
        addMenuButton(
            rightX, startY + (btnH + gap) * 3, btnW, btnH,
            Text.translatable("cobblemarket.op.buy_order"),
            { client?.setScreen(BuyOrderScreen(adminMode = true)) }
        )
        // 行 5：返回按钮居中（蛋交易开关已移到服务器配置界面）
        addMenuButton(
            centerX - btnW / 2, startY + (btnH + gap) * 4, btnW, btnH,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) }
        )
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val bg = Identifier.of("cobblemarket", "textures/gui/market_entry_background.png")
        // 背景底贴原 160 高背景底边再下移 20、向上扩展（不遮下方 HUD）；按钮布局不动。贴图 256×213
        // -29：补偿按钮下移的 5px，背景保持与入口界面（OP 三行时）完全一致，两界面切换背景不跳动
        val bgTop = btnStartY - 29
        context.drawTexture(bg, width / 2 - 128, bgTop + 160 - 213 + 20, 0f, 0f, 256, 213, 256, 213)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.op.title").formatted(Formatting.GOLD, Formatting.BOLD),
            width / 2, btnStartY - 33, 0xFFFFFF
        )
        // 皮卡丘跑步动画（照入口界面，同一时间基准公式——两界面切换时位置/帧延续，不从头跑）
        val pikaFrame = ((System.currentTimeMillis() / PIKA_FRAME_MS) % 4).toInt()
        val pikaTex = Identifier.of("cobblemarket", "textures/gui/pikachu/pikachu_move_$pikaFrame.png")
        if (ClientConfig.pikachuRunLoop) {
            // 皮卡丘跑酷：沿背景圆角矩形边缘顺时针环绕跑（圆角半径约 29px）。
            // 脚踩边缘：中心在边缘外侧 22px（=48/2-2，与直线模式脚位一致）；朝向随边旋转（右 90°、下 180°、左 270°，圆弧连续）。
            // bgTop 与入口界面统一用绝对位置（height/2-119）：切换界面环绕位置不跳动
            val bgLeft = (width / 2 - 128).toFloat()
            val bgTop = (height / 2 - 119).toFloat()
            val w = 256f
            val h = 213f
            val r = 29f
            val foot = 22f
            val arc = (PI / 2 * (r + foot)).toFloat() // 按中心路径半径计，保持角上匀速
            val sideW = w - 2 * r
            val sideH = h - 2 * r
            val totalP = 2 * sideW + 2 * sideH + 4 * arc
            val t = System.currentTimeMillis() % PIKA_LOOP_MS
            val s = t * totalP / PIKA_LOOP_MS
            val s1 = sideW
            val s2 = s1 + arc
            val s3 = s2 + sideH
            val s4 = s3 + arc
            val s5 = s4 + sideW
            val s6 = s5 + arc
            val s7 = s6 + sideH
            val cx: Float
            val cy: Float
            val rotDeg: Float
            when {
                s < s1 -> { cx = bgLeft + r + s; cy = bgTop - foot; rotDeg = 0f }
                s < s2 -> { val th = -PI.toFloat() / 2 + (s - s1) / (r + foot); cx = bgLeft + w - r + (r + foot) * cos(th); cy = bgTop + r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }
                s < s3 -> { cx = bgLeft + w + foot; cy = bgTop + r + (s - s2); rotDeg = 90f }
                s < s4 -> { val th = (s - s3) / (r + foot); cx = bgLeft + w - r + (r + foot) * cos(th); cy = bgTop + h - r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }
                s < s5 -> { cx = bgLeft + w - r - (s - s4); cy = bgTop + h + foot; rotDeg = 180f }
                s < s6 -> { val th = PI.toFloat() / 2 + (s - s5) / (r + foot); cx = bgLeft + r + (r + foot) * cos(th); cy = bgTop + h - r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }
                s < s7 -> { cx = bgLeft - foot; cy = bgTop + h - r - (s - s6); rotDeg = 270f }
                else -> { val th = PI.toFloat() + (s - s7) / (r + foot); cx = bgLeft + r + (r + foot) * cos(th); cy = bgTop + r + (r + foot) * sin(th); rotDeg = 90f + th * 180f / PI.toFloat() }
            }
            context.matrices.push()
            context.matrices.translate(cx.toDouble(), cy.toDouble(), 0.0)
            context.matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(rotDeg))
            context.drawTexture(pikaTex, -24, -24, 0f, 0f, 48, 48, 48, 48)
            context.matrices.pop()
        } else {
            // 默认：沿背景顶部从左跑到右，跑道左右对称（皮卡丘左右缘距背景左右缘各 8px），脚踩背景上沿；
            // y 与入口界面（OP 时）绝对屏幕位置一致：切换界面皮卡丘不跳动
            val trackLeft = width / 2 - 128 + 8
            val trackRight = width / 2 + 128 - 48 - 8
            val trackW = trackRight - trackLeft
            val runT = System.currentTimeMillis() % PIKA_CYCLE_MS
            val pikaX = trackLeft + (runT * trackW / PIKA_CYCLE_MS).toInt()
            val pikaY = height / 2 - 165
            context.drawTexture(pikaTex, pikaX, pikaY, 0f, 0f, 48, 48, 48, 48)
        }
    }

    override fun shouldPause() = false
}
