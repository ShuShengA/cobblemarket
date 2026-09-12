package com.shusheng.cobblemarket.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

/**
 * 自定义鼠标光标：进入本模组的界面时，用模组自带的图标代替系统鼠标箭头。
 *
 * **只在本模组的界面里生效**（判据是 [isMarketScreen] 那份全界面白名单，新增界面按老规矩加进去即可）。
 * 不做全局替换——GLFW 的光标是**整个窗口**的，全局换会连原版界面和游戏内无界面时一起改掉。
 *
 * 开关见 [ClientConfig.customCursor]（默认开）：用系统光标主题的玩家可以关掉。
 */
object CustomCursor {

    private val TEXTURE = Identifier.of("cobblemarket", "textures/gui/cursor.png")

    /** 源图边长（是张方图） */
    private const val SRC_SIZE = 128

    /** 显示边长（像素）：源图缩放后画多大。
     *  源图是满幅的（图形铺满整张 128×128，不像系统箭头那样四周留白），所以同样尺寸下视觉上会大一圈，
     *  取值要比系统光标保守——16 是试出来的观感，嫌大/嫌小直接调这个数。 */
    private const val DISPLAY_SIZE = 12f

    /** 热点：源图里「真正被点击的那个点」的像素坐标。
     *  自绘时把它对准鼠标坐标，否则会出现「明明指着按钮却点不中」的偏移感。
     *  这张图的闪电尖就在左上角、离边缘约 2~4px（读图确认），故取 (4, 5)：
     *  取太远会让整张图往左上飘，视觉上变成「光标没跟在鼠标上」。 */
    private const val HOTSPOT_X = 4f
    private const val HOTSPOT_Y = 5f

    /**
     * 每帧调用（由 ScreenMixin 注入在 `Screen.renderWithTooltip` 的 TAIL，即全部界面内容与
     * 动画都画完之后）。顺便把系统光标的显隐也管了——**必须每帧都调**：
     *
     * 不用「进入时隐藏、离开时恢复」的配对写法。那种写法一旦漏掉任意一条路径（崩溃、切屏、
     * 断线回主菜单），光标就会永久藏起来；每帧按当前状态重算，任何异常路径都会在下一次渲染
     * 自动纠正回来。
     */
    fun render(context: DrawContext, mouseX: Int, mouseY: Int, screen: Screen?) {
        val client = MinecraftClient.getInstance()
        if (ClientConfig.customCursor && isMarketScreen(screen) && isWindowHovered(client)) {
            setGlfwCursor(client, GLFW.GLFW_CURSOR_HIDDEN)
            draw(context, mouseX, mouseY)
        } else {
            // 恢复成 MC 自己的约定：GUI 里是普通箭头，游戏内（无界面）是捕获锁定
            setGlfwCursor(client, if (screen != null) GLFW.GLFW_CURSOR_NORMAL else GLFW.GLFW_CURSOR_DISABLED)
        }
    }

    /**
     * 鼠标是否真的悬停在游戏窗口上。
     *
     * 鼠标移出窗口后，MC 的 `mouseX`/`mouseY` 就不再更新、停在被夹到窗口边缘的最后那个值上——
     * 不做这个判断的话，窗口外会留下一个「紧贴屏幕边缘不动的假光标」，加上系统真正显示的光标，
     * 看起来就是两个鼠标（2026-09-12 用户实测）。移出时不画、并恢复系统光标即可。
     */
    private fun isWindowHovered(client: MinecraftClient): Boolean =
        GLFW.glfwGetWindowAttrib(client.window.handle, GLFW.GLFW_HOVERED) == GLFW.GLFW_TRUE

    /**
     * 把系统光标设成 [mode]。
     *
     * **每次都读一下 GLFW 里的实际值再决定要不要设，不缓存"上次设过什么"**：
     * MC 自己会在一些时机改光标模式（切屏、拖动等），缓存会让我们误以为「已经是 HIDDEN 了」
     * 从而跳过重设 → 系统光标露出来、和自绘的图叠成两个鼠标（2026-09-12 用户实测）。
     * `glfwGetInputMode` 只是一次取值，每帧调用没有负担。
     */
    private fun setGlfwCursor(client: MinecraftClient, mode: Int) {
        if (GLFW.glfwGetInputMode(client.window.handle, GLFW.GLFW_CURSOR) == mode) return
        GLFW.glfwSetInputMode(client.window.handle, GLFW.GLFW_CURSOR, mode)
    }

    private fun draw(context: DrawContext, mouseX: Int, mouseY: Int) {
        val scale = DISPLAY_SIZE / SRC_SIZE
        val matrices = context.matrices
        matrices.push()
        // 热点对准鼠标坐标；z 拉高，保证盖在所有界面内容之上
        matrices.translate(
            (mouseX - HOTSPOT_X * scale).toDouble(),
            (mouseY - HOTSPOT_Y * scale).toDouble(),
            400.0
        )
        matrices.scale(scale, scale, 1f)
        context.drawTexture(TEXTURE, 0, 0, 0f, 0f, SRC_SIZE, SRC_SIZE, SRC_SIZE, SRC_SIZE)
        matrices.pop()
    }
}
