package com.shusheng.cobblemarket.screen

import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.client.PhotoCapture
import com.shusheng.cobblemarket.client.playCameraShutterSound
import com.shusheng.cobblemarket.platform.setMovementKeysActiveInScreens
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11

/**
 * **取景界面**（2026-11-20 用户要求）：相机按钮点下、快速功能界面收起后进入。
 *
 * - 屏幕四角显示取景角标，**左键任意位置 = 拍照、右键 = 退出**（Esc 同样退出）
 * - **鼠标移动 = 转动视角**、**滚轮 = 缩放**（临时改 FOV，退出 / 截图后恢复）
 * - **隐藏全部 HUD**（等同按 F1，2026-11-20 用户要求）：取景时不显示物品栏 / 准星 / 血量等，
 *   照片里也不会有；HUD 状态同样**延后到截图完成后**才恢复
 * - **移动键照常可用**（WASD / 空格 / Shift 等，2026-11-20 用户要求）：MC 打开界面时会清空
 *   `KeyBinding` 状态，这里每 tick 把物理按键状态同步回去，玩家能像正常游戏一样走动、跳跃，
 *   但鼠标左右键被本界面截走（拍照 / 退出）
 * - 取景 UI 与缩放效果都不会丢：拍照时先关掉这个界面，等两 tick 让世界画面渲染干净再截屏
 *   （见 `PhotoCapture`）；**FOV 故意延后到截图完成后才恢复**，照片才能保留取景时的放大效果
 *
 * ⚠ **刻意不进 `isMarketScreen` 白名单**：这里不是市场界面，E 键不该当"关闭市场界面"处理；
 *   系统光标在取景时被隐藏并居中，用来做自由视角。
 * ⚠ 不画暗罩、`shouldPause = false`：取景时要看清世界。
 *
 * 角标只准备**一张**贴图，源图是**左下角**方向（用户 2026-11-20 提供）：
 * 右下 = 水平镜像、左上 = 垂直镜像、右上 = 双向镜像。换图时若源图尺寸不是 48，改 [CORNER_TEX]。
 */
class PhotoCaptureScreen : Screen(Text.literal("")) {

    /** 打开取景前玩家设置的 FOV —— 滚轮缩放基于它，退出 / 截图完成后恢复 */
    private var originalFov: Int? = null

    /** 打开取景前的 HUD 隐藏状态（F1）—— 取景期间强制隐藏，退出 / 截图完成后恢复 */
    private var originalHudHidden = false

    /** 左键拍照后：FOV 保留到截图完成（由 PhotoCapture 恢复），不能随界面关闭立即复原 */
    private var capturing = false

    override fun init() {
        super.init()
        val client = client ?: return
        originalFov = client.options.getFov().value
        // 隐藏光标并放到屏幕中心：取景时鼠标用来转视角（见 [mouseMoved]）
        val cx = client.window.width / 2.0
        val cy = client.window.height / 2.0
        InputUtil.setCursorParameters(client.window.handle, GLFW.GLFW_CURSOR_HIDDEN, cx, cy)
        // 像按 F1 一样隐藏全部 HUD（取景时看不到物品栏 / 准星；照片里也不会有）
        originalHudHidden = client.options.hudHidden
        client.options.hudHidden = true
        // ⚠ NeoForge 把移动 / 跳跃 / 潜行 / 冲刺键的冲突上下文设成 IN_GAME（界面打开时恒失效）：
        //   取景期间临时改成 UNIVERSAL，否则下面每 tick 同步物理按键也走不动
        //   （2026-11-21 用户实测：NeoForge 取景时视角能转、WASD 不能移动；Fabric 无此概念）
        setMovementKeysActiveInScreens(true)
        // 按住移动键点开相机时，进入取景后按键仍应按着（MC 打开界面时会清空按键状态）
        KeyBinding.updatePressedStates()
    }

    /**
     * 每 tick 把**物理键盘状态**同步回 `KeyBinding` —— 取景时玩家能像正常游戏一样移动 /
     * 跳跃 / 潜行 / 冲刺（用户 2026-11-20 要求）。MC 打开界面时会清空按键绑定状态（防卡键），
     * 只靠事件转发的话按住进入、焦点切换都会丢键；每 tick 同步与正常游戏最一致。
     */
    override fun tick() {
        super.tick()
        KeyBinding.updatePressedStates()
        syncMouseBoundMovementKeys()
    }

    /**
     * 鼠标绑定的移动键（如把「奔跑」设成鼠标侧键）在取景界面里也要生效
     * （2026-11-21 用户实测：侧键冲刺无效、双击 W 反而可以）。
     *
     * ⚠ `KeyBinding.updatePressedStates()` **只处理键盘键**（内部只遍历 `KEYSYM` 类型的绑定）——
     *   而界面打开时鼠标按键事件被 Screen 拦截、也不会更新 KeyBinding ⇒ 鼠标绑定的键
     *   在整个取景期间一直停留在「未按下」。这里对移动相关键里绑了鼠标的，直接查 GLFW
     *   物理鼠标状态同步（与键盘那条同一思路，只是补上鼠标键）。
     * ⚠ 左键 / 右键（0 / 1）跳过 —— 取景里它们是**拍照 / 退出**，不能同时驱动移动。
     */
    private fun syncMouseBoundMovementKeys() {
        val client = client ?: return
        val handle = client.window.handle
        val keys = listOf(
            client.options.forwardKey,
            client.options.backKey,
            client.options.leftKey,
            client.options.rightKey,
            client.options.jumpKey,
            client.options.sneakKey,
            client.options.sprintKey,
        )
        for (button in GLFW.GLFW_MOUSE_BUTTON_MIDDLE..GLFW.GLFW_MOUSE_BUTTON_LAST) {
            val pressed = GLFW.glfwGetMouseButton(handle, button) == GLFW.GLFW_PRESS
            keys.forEach { key ->
                if (key.matchesMouse(button)) key.setPressed(pressed)
            }
        }
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) = Unit

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 窗口失焦 / 某些平台会重置光标模式：每帧校一次（取景时不该看见鼠标箭头）
        ensureCursorHidden()

        // ⚠ 四角里有**单轴负缩放**（水平或垂直镜像）：负缩放会翻转四边形绕序，
        //   被 GUI 渲染阶段开着的背面剔除吃掉 —— 2026-11-20 用户实测「只有左下 / 右上显示」。
        //   绘制期间临时关剔除，结束后按原状态恢复（不能无条件 enable：那会改到后续渲染）。
        val cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE)
        if (cullEnabled) RenderSystem.disableCull()
        try {
            // 四角角标（源图 = 左下角；其余三个角镜像）
            drawCorner(context, CORNER_MARGIN, height - CORNER_MARGIN - CORNER_SIZE, flipX = false, flipY = false) // 左下
            drawCorner(context, width - CORNER_MARGIN - CORNER_SIZE, height - CORNER_MARGIN - CORNER_SIZE, flipX = true, flipY = false) // 右下
            drawCorner(context, CORNER_MARGIN, CORNER_MARGIN, flipX = false, flipY = true)  // 左上
            drawCorner(context, width - CORNER_MARGIN - CORNER_SIZE, CORNER_MARGIN, flipX = true, flipY = true)  // 右上
        } finally {
            if (cullEnabled) RenderSystem.enableCull()
        }

        // 操作提示：位置照监视的「按 U 退出监视」（PeekHud）—— 屏幕底部居中、物品栏上方 h-62
        // （2026-11-20 用户实测：原先贴着屏幕底盖住了物品栏）
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.photo.viewfinder_hint"),
            width / 2, height - HINT_BOTTOM_OFFSET, 0xFFFFFF
        )
    }

    /**
     * 画一个角的角标。负缩放把源图（左下角方向）镜像到对应方向；
     * ⚠ 镜像时先平移到**右下角**再取负缩放，负缩放会把绘制方向整个翻过去。
     */
    private fun drawCorner(context: DrawContext, x: Int, y: Int, flipX: Boolean, flipY: Boolean) {
        val scale = CORNER_SIZE / CORNER_TEX.toFloat()
        context.matrices.push()
        context.matrices.translate(
            if (flipX) (x + CORNER_SIZE).toDouble() else x.toDouble(),
            if (flipY) (y + CORNER_SIZE).toDouble() else y.toDouble(),
            0.0
        )
        context.matrices.scale(
            if (flipX) -scale else scale,
            if (flipY) -scale else scale,
            1f
        )
        context.drawTexture(
            CORNER_TEXTURE, 0, 0, 0f, 0f,
            CORNER_TEX, CORNER_TEX, CORNER_TEX, CORNER_TEX
        )
        context.matrices.pop()
    }

    /**
     * 鼠标移动 = 转动视角。光标被隐藏并**每次移动后回中**，所以这里的坐标就是相对屏幕中心的位移；
     * 换算公式照原版鼠标（灵敏度 `(s×0.6+0.2)³ × 8`、Y 轴可反转），手感与正常游戏一致。
     *
     * ⚠ `Screen.mouseMoved` 收到的是 **GUI 缩放后的坐标**（`Mouse.tick` 里按 scaledWidth / width
     *  换算过），而原版灵敏度吃的是**物理像素**位移 ⇒ 先按 GUI 缩放比换算回去再乘灵敏度。
     * ⚠ 回中用 `glfwSetCursorPos`（**物理**窗口坐标）。
     */
    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        val client = client ?: return
        val dxLogical = mouseX - width / 2.0
        val dyLogical = mouseY - height / 2.0
        if (dxLogical != 0.0 || dyLogical != 0.0) {
            val scaleX = client.window.width.toDouble() / width.coerceAtLeast(1)
            val scaleY = client.window.height.toDouble() / height.coerceAtLeast(1)
            val sensitivity = client.options.getMouseSensitivity().value
            val d = sensitivity * 0.6 + 0.2
            val factor = d * d * d * 8.0
            val invertY = if (client.options.getInvertYMouse().value) -1.0 else 1.0
            client.player?.changeLookDirection(
                dxLogical * scaleX * factor,
                dyLogical * scaleY * factor * invertY
            )
        }
        GLFW.glfwSetCursorPos(
            client.window.handle, client.window.width / 2.0, client.window.height / 2.0
        )
    }

    /** 光标保持隐藏并居中（取景时鼠标用于转视角，不显示箭头） */
    private fun ensureCursorHidden() {
        val client = client ?: return
        val handle = client.window.handle
        if (GLFW.glfwGetInputMode(handle, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_HIDDEN) {
            InputUtil.setCursorParameters(
                handle, GLFW.GLFW_CURSOR_HIDDEN,
                client.window.width / 2.0, client.window.height / 2.0
            )
        }
    }

    /** 滚轮 = 缩放视角（原点在玩家设置，退出时恢复；向上滚放大） */
    override fun mouseScrolled(
        mouseX: Double, mouseY: Double,
        horizontalAmount: Double, verticalAmount: Double,
    ): Boolean {
        val fovOption = client?.options?.getFov() ?: return true
        fovOption.setValue(
            (fovOption.value - verticalAmount.toInt() * ZOOM_STEP).coerceIn(MIN_FOV, MAX_FOV)
        )
        return true
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        when (button) {
            0 -> {
                // 左键 = 拍照：先响快门（项目反馈音统一 0.5 档，见 playCameraShutterSound）
                playCameraShutterSound()
                // FOV / HUD 隐藏都保留到截图完成（照片保留取景时的放大、且不含 HUD），
                // 由 PhotoCapture 在截图后统一恢复
                capturing = true
                PhotoCapture.pendingFovRestore = originalFov
                PhotoCapture.pendingHudRestore = originalHudHidden
                PhotoCapture.armCapture()
                close()
            }
            1 -> close()   // 右键 = 退出
        }
        return true
    }

    override fun removed() {
        super.removed()
        // 拍照路径不在这里恢复 FOV / HUD（交给 PhotoCapture 在截图后恢复）；退出路径立即恢复
        if (!capturing) {
            restoreFov()
            restoreHud()
        }
        capturing = false
        // 移动键的冲突上下文还原（NeoForge 上是 IN_GAME；Fabric 是空操作）
        setMovementKeysActiveInScreens(false)
        // 清空按键状态：取景期间每 tick 同步过物理按键，直接回游戏会残留「按着」的键
        KeyBinding.unpressAll()
        // 光标恢复正常：取景时是隐藏的，回到游戏 / 确认界面都需要鼠标
        client?.let {
            InputUtil.setCursorParameters(
                it.window.handle, GLFW.GLFW_CURSOR_NORMAL,
                it.window.width / 2.0, it.window.height / 2.0
            )
        }
    }

    private fun restoreFov() {
        val original = originalFov ?: return
        originalFov = null
        client?.options?.getFov()?.setValue(original)
    }

    /** 退出取景（非拍照路径）：立刻恢复玩家原来的 HUD 隐藏状态 */
    private fun restoreHud() {
        client?.options?.hudHidden = originalHudHidden
    }

    override fun shouldPause() = false

    companion object {
        /** 取景角标贴图（源图是**左下角**；其余三个角镜像得到） */
        private val CORNER_TEXTURE =
            Identifier.of("cobblemarket", "textures/gui/icon/photo_corner.png")

        /** 角标源图像素尺寸（换图时若不同，改这里） */
        private const val CORNER_TEX = 48

        /** 角标显示边长 */
        private const val CORNER_SIZE = 32

        /** 角标距屏幕边缘的距离 */
        private const val CORNER_MARGIN = 16

        /** 提示离屏幕底部的距离：与 `PeekHud` 的「按 U 退出监视」同位置（h - 62） */
        private const val HINT_BOTTOM_OFFSET = 62

        /** 滚轮每格缩放多少 FOV（越小缩放越细腻） */
        private const val ZOOM_STEP = 5

        /** 取景缩放的 FOV 范围（与原版设置范围一致） */
        private const val MIN_FOV = 30
        private const val MAX_FOV = 110
    }
}
