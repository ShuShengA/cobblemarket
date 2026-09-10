package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.ClientConfig
import com.shusheng.cobblemarket.client.balanceHudPixelPos
import com.shusheng.cobblemarket.client.balanceHudSize
import com.shusheng.cobblemarket.client.hudPosOverride
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import kotlin.math.abs

/**
 * 余额 HUD 位置编辑界面（纯客户端）：游戏画面压暗、HUD 正常显示，按住左键把 HUD 拖到任意位置。
 *
 * 吸附规则（两轴各判各的，阈值内取最近的一条；实时判断而非锁定——鼠标移开即自动脱离）：
 *  - 边缘：HUD 左/右缘贴屏幕左/右缘、上/下缘贴屏幕上/下缘
 *  - 中心线：HUD **中心点**对齐屏幕中心线（是中心对齐，不是边缘对齐）
 * 吸附生效时画 1px 半透明对齐辅助线做反馈，否则玩家会觉得"突然跳过去了"。
 *
 * HUD 本身不在这里画：ScreenMixin 已在界面最上层统一渲染（不会被压暗层盖住），本界面只负责
 * 改 hudPosOverride（实时位置）+ 接鼠标输入 —— 所以拖动时 HUD 天然跟随。
 */
class BalanceHudPositionScreen : Screen(Text.translatable("cobblemarket.hud_position.title")) {

    private var dragging = false
    private var grabDX = 0
    private var grabDY = 0

    /** 当前 HUD 左上角像素坐标（拖动中实时更新，经 hudPosOverride 驱动 HUD 渲染） */
    private var posX = 0
    private var posY = 0

    /** 本次吸附命中的对齐线屏幕坐标（null = 该轴未吸附），仅用于画辅助线 */
    private var alignLineX: Int? = null
    private var alignLineY: Int? = null

    private var hudW = 60
    private var hudH = 16

    override fun init() {
        super.init()
        val c = client ?: return
        val size = balanceHudSize(c)
        hudW = size.first
        hudH = size.second
        // 位置来源统一走 hudPosOverride：首次进入用配置值，resize 重建时沿用拖动中的值（按新屏尺寸重算像素）
        if (hudPosOverride == null) hudPosOverride = ClientConfig.balanceHudX to ClientConfig.balanceHudY
        val pos = balanceHudPixelPos(width, height, hudW, hudH)
        posX = pos.first
        posY = pos.second

        addDrawableChild(
            NineSliceButton(
                width / 2 - 28, height - 40, 56, 20,
                Text.translatable("cobblemarket.settings.done"),
                { confirm() }
            )
        )
    }

    /** 界面以任何方式离开（确定 / Esc / E / 关闭动画）都在这里收尾：清掉实时位置覆盖。
     *  必须放 removed()：Esc 在市场动画开启时走 CloseAnimation → setScreen(null)，不经过 close()。 */
    override fun removed() {
        super.removed()
        hudPosOverride = null
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 按钮优先：HUD 被拖到「完成」按钮上时仍点得到按钮（否则玩家再也点不了确定）
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        // 命中判定用 HUD 自身矩形：按下落在 HUD 上才算抓住
        if (button == 0 && mouseX >= posX && mouseX < posX + hudW && mouseY >= posY && mouseY < posY + hudH) {
            dragging = true
            grabDX = mouseX.toInt() - posX
            grabDY = mouseY.toInt() - posY
            return true
        }
        return false
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (!dragging) return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
        moveTo(mouseX.toInt() - grabDX, mouseY.toInt() - grabDY)
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (dragging && button == 0) {
            dragging = false
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    /** 自由位置 → 钳制在屏内 → 吸附 → 写实时覆盖 */
    private fun moveTo(freeX: Int, freeY: Int) {
        val maxX = (width - hudW).coerceAtLeast(0)
        val maxY = (height - hudH).coerceAtLeast(0)
        val cx = freeX.coerceIn(0, maxX)
        val cy = freeY.coerceIn(0, maxY)
        // 每轴三条目标：两端（HUD 贴屏幕边）+ 中心（HUD 中心点落在屏幕中心线上）
        val targetX = nearestWithin(intArrayOf(0, maxX / 2, maxX), cx)
        val targetY = nearestWithin(intArrayOf(0, maxY / 2, maxY), cy)
        posX = targetX ?: cx
        posY = targetY ?: cy
        alignLineX = targetX?.let { alignLineScreenX(it, maxX) }
        alignLineY = targetY?.let { alignLineScreenY(it, maxY) }
        hudPosOverride = normalize(posX, posY)
    }

    /** 阈值内最近的目标；都不在阈值内返回 null（= 自由摆放） */
    private fun nearestWithin(targets: IntArray, value: Int): Int? =
        targets.filter { abs(it - value) <= SNAP_DISTANCE }.minByOrNull { abs(it - value) }

    /** 命中目标 → 辅助线的屏幕位置：贴边目标画在屏幕边上，居中目标画在屏幕中心线 */
    private fun alignLineScreenX(target: Int, maxX: Int): Int = when (target) {
        0 -> 0
        maxX -> width - 1
        else -> width / 2
    }

    private fun alignLineScreenY(target: Int, maxY: Int): Int = when (target) {
        0 -> 0
        maxY -> height - 1
        else -> height / 2
    }

    /** 像素位置 → 归一化（分母 max(1, …) 防除零） */
    private fun normalize(px: Int, py: Int): Pair<Float, Float> {
        val maxX = (width - hudW).coerceAtLeast(1)
        val maxY = (height - hudH).coerceAtLeast(1)
        return (px.toFloat() / maxX).coerceIn(0f, 1f) to (py.toFloat() / maxY).coerceIn(0f, 1f)
    }

    private fun confirm() {
        val pos = normalize(posX, posY)
        ClientConfig.setBalanceHudPosition(pos.first, pos.second)
        client?.setScreen(MarketEntryScreen(skipDropAnim = true))
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // super 画默认压暗背景（世界画面 + 暗化）+ 底部确定按钮；HUD 由 ScreenMixin 在最上层补画
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.hud_position.hint").formatted(Formatting.GOLD),
            width / 2, 24, 0xFFFFFF
        )
        // 对齐辅助线：吸附生效时该轴画一条 1px 半透明白线
        alignLineX?.let {
            val x = it.coerceIn(0, width - 1)
            context.fill(x, 0, x + 1, height, 0x80FFFFFF.toInt())
        }
        alignLineY?.let {
            val y = it.coerceIn(0, height - 1)
            context.fill(0, y, width, y + 1, 0x80FFFFFF.toInt())
        }
    }

    companion object {
        /** 吸附触发距离（逻辑像素）：距目标线不超过这个值就吸过去 */
        private const val SNAP_DISTANCE = 8
    }
}
