package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.ClientConfig
import com.shusheng.cobblemarket.network.RequestEggTradingPayload
import com.shusheng.cobblemarket.network.SetEggTradingPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.platform.isModLoaded
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Drawable
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

    // 蛋交易开关（服务端状态，进入界面时请求）
    private var eggTradingEnabled = false
    private var eggButton: TextureButton? = null
    private var eggConfirmOpen = false
    private var eggConfirmOpenedAt = 0L
    private var eggConfirmButton: NineSliceButton? = null
    private var eggCancelButton: NineSliceButton? = null
    // 主面板全部按钮：蛋交易确认弹窗打开时统一隐藏（防透过遮罩显示/交互）
    private val menuButtons = mutableListOf<TextureButton>()

    companion object {
        // 客户端没装 Cobbreeding 就没有蛋物品，蛋交易开关按钮不显示（本地检测，无需网络包）
        private val COBBREEDING_AVAILABLE = isModLoaded("cobbreeding")
        // 皮卡丘跑步动画参数（与入口界面一致，共享时间基准实现切换不重置）
        private const val PIKA_CYCLE_MS = 3000L
        private const val PIKA_FRAME_MS = 100L
        private const val PIKA_LOOP_MS = 8000L
    }

    private fun addMenuButton(x: Int, y: Int, w: Int, h: Int, text: Text, action: net.minecraft.client.gui.widget.ButtonWidget.PressAction, iconLeft: Identifier? = null): TextureButton {
        val btn = TextureButton(x, y, w, h, text, action, iconLeft = iconLeft)
        menuButtons.add(btn)
        addDrawableChild(btn)
        return btn
    }

    private fun setMenuButtonsVisible(visible: Boolean) {
        menuButtons.forEach { it.visible = visible }
    }

    fun onEggTradingState(enabled: Boolean) {
        eggTradingEnabled = enabled
        updateEggButton()
    }

    // 按钮文案：前缀白色，开=绿 / 关=红（Text 内嵌颜色，TextureButton 原样渲染）
    private fun eggTradingButtonText(): Text =
        Text.translatable("cobblemarket.op.egg_trading").append(
            if (eggTradingEnabled)
                Text.translatable("cobblemarket.op.egg_trading_on_state").formatted(Formatting.GREEN)
            else
                Text.translatable("cobblemarket.op.egg_trading_off_state").formatted(Formatting.RED)
        )

    private fun updateEggButton() {
        eggButton?.message = eggTradingButtonText()
    }

    override fun init() {
        super.init()
        menuButtons.clear()
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
        // 行 5：蛋交易开关（左，仅装有 Cobbreeding 时显示）+ 返回；无 Cobbreeding 时返回按钮放左
        if (COBBREEDING_AVAILABLE) {
            val eggBtn = addMenuButton(
                leftX, startY + (btnH + gap) * 4, btnW, btnH,
                eggTradingButtonText(),
                { toggleEggTrading() },
                iconLeft = Identifier.of("cobblemarket", "textures/gui/pokemon_egg.png")
            )
            eggButton = eggBtn
            addMenuButton(
                rightX, startY + (btnH + gap) * 4, btnW, btnH,
                Text.translatable("cobblemarket.gui.back"),
                { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) }
            )
        } else {
            // 无 Cobbreeding：蛋交易开关不显示，返回按钮单独居中
            addMenuButton(
                centerX - btnW / 2, startY + (btnH + gap) * 4, btnW, btnH,
                Text.translatable("cobblemarket.gui.back"),
                { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) }
            )
        }

        // resize 重建按钮后 visible 全是 true，弹窗开着时需恢复隐藏（老坑）
        if (eggConfirmOpen) setMenuButtonsVisible(false)
        if (COBBREEDING_AVAILABLE) sendToServer(RequestEggTradingPayload())
    }

    private fun toggleEggTrading() {
        if (eggTradingEnabled) {
            // 关闭无需确认
            sendToServer(SetEggTradingPayload(false))
        } else {
            // 开启需二次确认（蛋可绕过精灵黑名单），确认按钮带 3 秒冷静期
            openEggConfirmDialog()
        }
    }

    private fun confirmEggTrading() {
        sendToServer(SetEggTradingPayload(true))
        closeEggConfirmDialog()
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
        if (eggConfirmOpen) {
            renderEggConfirmText(context)
            updateEggConfirmButtons()
            return
        }
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

    // ── 蛋交易二次确认弹窗（照搬 AdminAuctionScreen 下架确认弹窗模板） ──

    private fun openEggConfirmDialog() {
        eggConfirmOpen = true
        eggConfirmOpenedAt = System.currentTimeMillis()
        // 隐藏下层控件（弹窗打开期间不可交互；closeEggConfirmDialog 的 init 重建会恢复）
        setMenuButtonsVisible(false)

        // 弹窗背景画在按钮之下（Drawable 在 children 之前渲染）
        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderEggConfirmBackground(context)
            }
        })

        val centerX = width / 2
        val dialogY = height / 2 - 75
        eggConfirmButton = NineSliceButton(
            centerX - 85, dialogY + 116, 80, 20,
            Text.translatable("cobblemarket.op.egg_confirm_yes"),
            { confirmEggTrading() }
        )
        addDrawableChild(eggConfirmButton)
        eggCancelButton = NineSliceButton(
            centerX + 5, dialogY + 116, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeEggConfirmDialog() }
        )
        addDrawableChild(eggCancelButton)
    }

    private fun closeEggConfirmDialog() {
        eggConfirmOpen = false
        eggConfirmButton = null
        eggCancelButton = null
        clearChildren()
        init()
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val wasOpen = eggConfirmOpen
        super.resize(client, width, height)
        if (wasOpen) {
            eggConfirmOpen = false
            openEggConfirmDialog()
        }
    }

    private fun renderEggConfirmBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogW = 280
        val dialogH = 150
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        context.fill(0, 0, width, height, 0xC0000000.toInt())
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.op.egg_confirm_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)
    }

    private fun renderEggConfirmText(context: DrawContext) {
        val centerX = width / 2
        val dialogX = centerX - 140
        val dialogY = height / 2 - 75

        // 逐行渲染：语言文件显式分行（每行红/白两个槽位），红=警告、白=普通；空行跳过（中英行数不同）
        // 每行按实际宽度在弹窗内水平居中，避免短行右侧大片留白
        val lines = (1..9).map { i ->
            listOf(
                "cobblemarket.op.egg_l${i}_warn" to 0xFF5555,
                "cobblemarket.op.egg_l${i}_text" to 0xFFFFFF,
            )
        }
        var ty = dialogY + 32
        lines.forEach { line ->
            val segs = line.mapNotNull { (key, color) ->
                val text = Text.translatable(key).string
                if (text.isEmpty()) null else text to color
            }
            if (segs.isEmpty()) return@forEach
            val lineWidth = segs.sumOf { textRenderer.getWidth(it.first) }
            var tx = dialogX + 20 + (240 - lineWidth) / 2
            segs.forEach { (text, color) ->
                context.drawTextWithShadow(textRenderer, text, tx, ty, color)
                tx += textRenderer.getWidth(text)
            }
            ty += 9
        }
    }

    // 冷静期：3 秒内确认按钮禁用并显示倒计时
    private fun updateEggConfirmButtons() {
        val cooldownLeft = 3 - (System.currentTimeMillis() - eggConfirmOpenedAt) / 1000
        val canConfirm = cooldownLeft <= 0
        eggConfirmButton?.active = canConfirm
        eggConfirmButton?.message = if (canConfirm)
            Text.translatable("cobblemarket.op.egg_confirm_yes")
        else
            Text.translatable("cobblemarket.op.egg_confirm_yes_countdown", cooldownLeft)
    }

    override fun shouldPause() = false
}
