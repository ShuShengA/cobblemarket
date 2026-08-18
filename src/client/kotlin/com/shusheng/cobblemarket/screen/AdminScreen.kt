package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.network.RequestEggTradingPayload
import com.shusheng.cobblemarket.network.SetEggTradingPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

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
        private val COBBREEDING_AVAILABLE = FabricLoader.getInstance().isModLoaded("cobbreeding")
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
        val startY = height / 2 - totalH / 2
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
        // 行 3
        addMenuButton(
            leftX, startY + (btnH + gap) * 2, btnW, btnH,
            Text.translatable("cobblemarket.op.blacklist_pokemon"),
            { client?.setScreen(PokemonBlacklistScreen()) }
        )
        addMenuButton(
            rightX, startY + (btnH + gap) * 2, btnW, btnH,
            Text.translatable("cobblemarket.op.blacklist_item"),
            { client?.setScreen(ItemBlacklistScreen()) }
        )
        // 行 4
        addMenuButton(
            leftX, startY + (btnH + gap) * 3, btnW, btnH,
            Text.translatable("cobblemarket.op.price_limit"),
            { client?.setScreen(PriceLimitScreen()) }
        )
        addMenuButton(
            rightX, startY + (btnH + gap) * 3, btnW, btnH,
            Text.translatable("cobblemarket.op.auction"),
            { client?.setScreen(AdminAuctionScreen()) }
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
                { client?.setScreen(MarketEntryScreen()) }
            )
        } else {
            addMenuButton(
                leftX, startY + (btnH + gap) * 4, btnW, btnH,
                Text.translatable("cobblemarket.gui.back"),
                { client?.setScreen(MarketEntryScreen()) }
            )
        }

        // resize 重建按钮后 visible 全是 true，弹窗开着时需恢复隐藏（老坑）
        if (eggConfirmOpen) setMenuButtonsVisible(false)
        if (COBBREEDING_AVAILABLE) ClientPlayNetworking.send(RequestEggTradingPayload())
    }

    private fun toggleEggTrading() {
        if (eggTradingEnabled) {
            // 关闭无需确认
            ClientPlayNetworking.send(SetEggTradingPayload(false))
        } else {
            // 开启需二次确认（蛋可绕过精灵黑名单），确认按钮带 3 秒冷静期
            openEggConfirmDialog()
        }
    }

    private fun confirmEggTrading() {
        ClientPlayNetworking.send(SetEggTradingPayload(true))
        closeEggConfirmDialog()
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val bg = Identifier.of("cobblemarket", "textures/gui/market_entry_background.png")
        val bgTop = btnStartY - 25
        context.drawTexture(bg, width / 2 - 96, bgTop, 0f, 0f, 192, 160, 192, 160)
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
            Text.translatable("cobblemarket.op.title").formatted(Formatting.GOLD),
            width / 2, btnStartY - 14, 0xFFFFFF
        )
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
        val lines = (1..8).map { i ->
            listOf(
                "cobblemarket.op.egg_l${i}_warn" to 0xFF5555,
                "cobblemarket.op.egg_l${i}_text" to 0xFFFFFF,
            )
        }
        var ty = dialogY + 34
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
            ty += 10
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
