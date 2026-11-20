package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.ClientConfig
import com.shusheng.cobblemarket.client.CobbleMarketClient
import com.shusheng.cobblemarket.client.MarketStateCache
import com.shusheng.cobblemarket.client.PhotoFrame
import com.shusheng.cobblemarket.client.playButtonClickSound
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.UploadPhotoPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

/**
 * **拍照确认界面**（2026-11-20）：相机拍完先给玩家看一眼，点「发送到聊天」才广播全服，点取消丢弃。
 *
 * 形态：**弹窗背景 + 整体居中**，照片**装进相框**显示（相框几何与聊天悬停预览同源，见 [PhotoFrame]）；
 * 相框下方是**照片名字输入框**（可留空，2026-11-20 用户要求），再下面是两个项目统一样式的按钮。
 * 名字会出现在聊天播报的方括号里（`[名字]`；留空时显示 `[照片]`）。
 *
 * 照片已经由 [com.shusheng.cobblemarket.client.PhotoCapture] 裁剪 / 压缩好（JPEG ≤256KB），
 * 发送的是同一份字节，所见即所得。
 *
 * ⚠ 纹理由本界面负责销毁（[removed]）。
 * ⚠ 发送后**等服务端回包**再关界面：成功绿字 + 关闭、失败红字 + 留在界面里重试
 *   （冷却 / 开关 / 大小都在服务端判，见 `PhotoNetwork`）。
 */
class PhotoConfirmScreen(
    private val texture: Identifier,
    private val texW: Int,
    private val texH: Int,
    private val photoBytes: ByteArray,
    /** 截图那一刻的玩家位置（「分享拍摄位置」用；不在拍摄时点则不会被记录） */
    private val captureLocation: Triple<Int, Int, Int>?,
) : Screen(Text.literal("")) {

    private var sending = false
    private var sendStartedAt = 0L

    /** 是否把拍摄位置叠加到照片右下角（默认关；2026-11-20 用户要求） */
    private var shareLocation = false

    /** 照片名字输入框（名字会显示在聊天播报的方括号里） */
    private var nameField: TextFieldWidget? = null

    // 布局（init 时按屏幕算一次；resize 会重新 init）
    private var frameW = 0f
    private var frameH = 0f
    private var dialogW = 0
    private var dialogH = 0
    private var dialogX = 0
    private var dialogY = 0

    override fun init() {
        super.init()
        // 相框显示宽：不超过 420，同时整块弹窗（标题 + 相框 + 名字框 + 按钮 + 边距）要放得下屏幕
        val maxByWidth = minOf(420, width - 80)
        val maxByHeight = (height - 130) * PhotoFrame.FRAME_W / PhotoFrame.FRAME_H
        frameW = minOf(maxByWidth, maxByHeight).coerceAtLeast(160).toFloat()
        frameH = PhotoFrame.heightForWidth(frameW)

        dialogW = frameW.toInt() + DIALOG_SIDE_PAD * 2
        dialogH = (TITLE_BLOCK_H + frameH + NAME_GAP + NAME_H + BUTTON_GAP + BUTTON_H +
            DIALOG_BOTTOM_PAD).toInt()
        dialogX = (width - dialogW) / 2
        dialogY = (height - dialogH) / 2

        // 名字输入框（resize 重建时保留已输入内容）
        val previousText = nameField?.text.orEmpty()
        val fieldY = (dialogY + TITLE_BLOCK_H + frameH + NAME_GAP).toInt()
        val field = TextFieldWidget(
            textRenderer, dialogX + DIALOG_SIDE_PAD, fieldY, frameW.toInt(), NAME_H,
            Text.literal("")
        )
        field.setMaxLength(MAX_NAME_LENGTH)
        field.text = previousText
        field.setPlaceholder(Text.translatable("cobblemarket.photo.name_hint"))
        field.setEditable(!sending)
        nameField = field
        addDrawableChild(field)
        // 与绑定弹窗一致：打开时不默认聚焦，点了哪个框才开始输入
        setFocused(null)
    }

    /**
     * ⚠ **这里必须置空、暗罩在 [render] 开头自己画**（与快速功能界面的标记 / 绑定弹窗同一做法）：
     * `render` 中间的 `super.render`（画名字输入框）会**再次调用 `renderBackground`**，
     * 若在那里画暗罩，第二层暗罩会盖在照片与相框之上 —— 2026-11-20 用户实测
     * 「只有标题、输入框、按钮是高亮，其余都是暗的」就是这个原因。
     */
    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) = Unit

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 暗罩（只画一次，在弹窗内容之前）
        drawScreenDimMask(context, width, height)

        // 弹窗背景（与标记 / 绑定弹窗同一张）
        drawNineSlice(
            context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH,
            0, DIALOG_BACKGROUND_TEX_H
        )
        // 标题：金色、居中、贴在弹窗顶部（项目弹窗统一写法）
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.photo.confirm_title").formatted(Formatting.GOLD),
            width / 2, dialogY + 8, 0xFFFFFF
        )

        // 照片装进相框（与聊天悬停预览同一套几何）
        val photoLeft = dialogX + DIALOG_SIDE_PAD.toFloat()
        val photoTop = dialogY + TITLE_BLOCK_H.toFloat()
        PhotoFrame.draw(context, photoLeft, photoTop, frameW, texture, texW, texH)
        // 选中「分享拍摄位置」时，照片右下角叠加坐标（所见即所得）
        if (shareLocation) {
            captureLocation?.let {
                PhotoFrame.drawLocation(
                    context, photoLeft, photoTop, frameW,
                    it.first, it.second, it.third
                )
            }
        }

        // 名字输入框（由 super.render 画）
        super.render(context, mouseX, mouseY, delta)

        // 底部按钮：位置（开关）/ 发送 / 取消，整组居中，距弹窗底 10px。
        // 「发送到聊天」受服主照片开关控制：关闭时置灰（拍照 / 预览不受影响，以后还要接相册）
        val (confirmX, confirmY) = confirmButtonOrigin()
        val (cancelX, cancelY) = cancelButtonOrigin()
        drawLocationButton(context, mouseX, mouseY)
        drawDialogButton(
            context, confirmX, confirmY,
            Text.translatable("cobblemarket.photo.send"), mouseX, mouseY,
            enabled = !sending && MarketStateCache.photoEnabled
        )
        drawDialogButton(
            context, cancelX, cancelY,
            Text.translatable("cobblemarket.photo.cancel"), mouseX, mouseY,
            enabled = !sending
        )

        // 发送按钮被服主关闭时的悬停提示（点击还会有红字 + 失败音）
        if (!sending && !MarketStateCache.photoEnabled &&
            mouseX in confirmX until confirmX + BUTTON_W &&
            mouseY in confirmY until confirmY + BUTTON_H
        ) {
            drawTip(
                context, Text.translatable("cobblemarket.photo.disabled"), mouseX, mouseY
            )
        }

        // 发送中提示：输入框与按钮之间那条 10px 的空隙
        if (sending) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.photo.sending"),
                width / 2, nameFieldBottom() + 1, 0xAAAAAA
            )
        }
    }

    private fun nameFieldBottom(): Int =
        (dialogY + TITLE_BLOCK_H + frameH + NAME_GAP).toInt() + NAME_H

    /** 弹窗底部按钮（与标记 / 绑定弹窗同款九宫格按钮；[enabled] = false 时变灰、不响应悬停） */
    private fun drawDialogButton(
        context: DrawContext, x: Int, y: Int, text: Text, mouseX: Int, mouseY: Int,
        enabled: Boolean,
    ) {
        val hovered = enabled && mouseX in x until x + BUTTON_W && mouseY in y until y + BUTTON_H
        drawNineSlice(
            context, BUTTON_TEXTURE, x, y, BUTTON_W, BUTTON_H,
            if (hovered) 1 else 0, BUTTON_TEX_H
        )
        context.drawCenteredTextWithShadow(
            textRenderer, text, x + BUTTON_W / 2, y + 4,
            if (enabled) 0xFFFFFF else 0xAAAAAA
        )
    }

    /** 按钮行 y（三个按钮共用；位置按钮更高，垂直居中于这一行） */
    private fun buttonRowY(): Int = dialogY + dialogH - DIALOG_BOTTOM_PAD - BUTTON_H

    /** 三个按钮整组的左缘（位置 22 + 发送 80 + 取消 80，横向各隔 4） */
    private fun buttonRowStartX(): Int {
        val totalW = LOCATION_BTN + BUTTON_GAP_X + BUTTON_W + BUTTON_GAP_X + BUTTON_W
        return width / 2 - totalW / 2
    }

    private fun locationButtonOrigin(): Pair<Int, Int> =
        buttonRowStartX() to (buttonRowY() - (LOCATION_BTN - BUTTON_H) / 2)

    private fun confirmButtonOrigin(): Pair<Int, Int> =
        (buttonRowStartX() + LOCATION_BTN + BUTTON_GAP_X) to buttonRowY()

    private fun cancelButtonOrigin(): Pair<Int, Int> =
        (buttonRowStartX() + LOCATION_BTN + BUTTON_GAP_X + BUTTON_W + BUTTON_GAP_X) to buttonRowY()

    /**
     * **分享拍摄位置**开关按钮（发送按钮左侧，22×22，普通 / 悬停 / 选中三段）。
     * 选中后照片右下角叠加 X / Y / Z；图标 48→18 居中。
     */
    private fun drawLocationButton(context: DrawContext, mouseX: Int, mouseY: Int) {
        val (x, y) = locationButtonOrigin()
        val hovered = !sending && mouseX in x until x + LOCATION_BTN &&
            mouseY in y until y + LOCATION_BTN
        val state = when {
            shareLocation -> 2
            hovered -> 1
            else -> 0
        }
        drawNineSlice(context, BUTTON_TEXTURE, x, y, LOCATION_BTN, LOCATION_BTN, state, BUTTON_TEX_H)
        val iconSize = LOCATION_BTN - 4
        val scale = iconSize / LOCATION_ICON_TEX.toFloat()
        context.matrices.push()
        context.matrices.translate(
            x + (LOCATION_BTN - iconSize) / 2.0,
            y + (LOCATION_BTN - iconSize) / 2.0,
            0.0
        )
        context.matrices.scale(scale, scale, 1f)
        context.drawTexture(
            LOCATION_ICON, 0, 0, 0f, 0f,
            LOCATION_ICON_TEX, LOCATION_ICON_TEX, LOCATION_ICON_TEX, LOCATION_ICON_TEX
        )
        context.matrices.pop()

        if (hovered) {
            drawTip(
                context,
                Text.translatable("cobblemarket.photo.share_location_tip"),
                mouseX, mouseY
            )
        }
    }

    /** 自绘悬停提示（鼠标左下，样式同项目其余自绘悬停窗） */
    private fun drawTip(context: DrawContext, text: Text, mouseX: Int, mouseY: Int) {
        val padding = 4
        val w = textRenderer.getWidth(text) + padding * 2
        val h = textRenderer.fontHeight + padding * 2
        var x = mouseX - w
        var y = mouseY + 12
        if (x < 4) x = 4
        if (y + h > height - 4) y = mouseY - 12 - h
        if (y < 4) y = 4
        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        context.fill(x, y, x + w, y + h, 0xF0101010.toInt())
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, x, y, w, h, 1, ROW_BACKGROUND_TEX_H)
        context.drawTextWithShadow(textRenderer, text, x + padding, y + padding, 0xFFFFFF)
        context.matrices.pop()
    }

    /**
     * 发送入口（按钮 / Enter）：服主关闭照片展示时 → **红字 + 失败音**，不发包
     * （置灰只是视觉，点击必须给反馈；项目惯例）。服务端上传处还会再兜一层。
     */
    private fun trySend() {
        if (sending) return
        if (!MarketStateCache.photoEnabled) {
            client?.player?.sendMessage(
                Text.translatable("cobblemarket.photo.disabled").formatted(Formatting.RED),
                false
            )
            playFailSound()
            return
        }
        send()
    }

    private fun send() {
        if (sending) return
        playButtonClickSound()
        sending = true
        sendStartedAt = System.currentTimeMillis()
        nameField?.setEditable(false)
        // 位置只在选中且有坐标时启用；名字原样发上去，服务端会清洗控制字符并截到 32 字符
        val locationEnabled = shareLocation && captureLocation != null
        sendToServer(
            UploadPhotoPayload(
                photoBytes,
                nameField?.text.orEmpty(),
                locationEnabled,
                captureLocation?.first ?: 0,
                captureLocation?.second ?: 0,
                captureLocation?.third ?: 0
            )
        )
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && !sending) {
            // 位置开关（发送按钮左侧）
            val (locationX, locationY) = locationButtonOrigin()
            if (mouseX >= locationX && mouseX < locationX + LOCATION_BTN &&
                mouseY >= locationY && mouseY < locationY + LOCATION_BTN
            ) {
                shareLocation = !shareLocation
                playButtonClickSound()
                return true
            }

            val (confirmX, confirmY) = confirmButtonOrigin()
            if (mouseX >= confirmX && mouseX < confirmX + BUTTON_W &&
                mouseY >= confirmY && mouseY < confirmY + BUTTON_H
            ) {
                trySend()
                return true
            }
            val (cancelX, cancelY) = cancelButtonOrigin()
            if (mouseX >= cancelX && mouseX < cancelX + BUTTON_W &&
                mouseY >= cancelY && mouseY < cancelY + BUTTON_H
            ) {
                playButtonClickSound()
                close()
                return true
            }
        }
        // 点输入框交给 super 切焦点；点弹窗内空白则显式失焦（与绑定弹窗同口径）
        val onField = nameField?.isMouseOver(mouseX, mouseY) == true
        super.mouseClicked(mouseX, mouseY, button)
        if (!onField) {
            nameField?.setFocused(false)
            setFocused(null)
        }
        // 弹窗独占：点在别处也吞掉，避免点穿到世界
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // Enter = 发送（同样受照片开关约束）；Esc 走 Screen 默认（取消并关闭）
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            trySend()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    /** 服务端回包（`CobbleMarketClient` 分发）：成功关闭、失败留在界面里重试 */
    fun onMarketResult(payload: MarketResultPayload) {
        client?.player?.sendMessage(
            payload.message.copy().formatted(if (payload.success) Formatting.GREEN else Formatting.RED),
            false
        )
        if (payload.success) {
            // 展示完成：三框上收的关闭动画回游戏（与精灵 / 物品 / ID 卡展示一致）
            closeAfterSuccessfulShare()
        } else {
            sending = false
            nameField?.setEditable(true)
        }
    }

    override fun tick() {
        super.tick()
        // 回包一直没来（掉线等）也要解锁，别让界面永久卡在「发送中」
        if (sending && System.currentTimeMillis() - sendStartedAt > SEND_TIMEOUT_MS) {
            sending = false
            nameField?.setEditable(true)
        }
    }

    /**
     * **取消**（取消按钮 / Esc）：回到快速功能界面，与标记 / 绑定弹窗的取消逻辑一致
     * （2026-11-20 用户要求）。三框**直接原地出现**（不重播下落）；按住模式且快速键已松开时
     * 与「弹窗会话结束」同一口径立即收起，按一下模式则停留在快速功能界面。
     */
    private fun returnToQuickShowcase() {
        val minecraft = client ?: return
        val quick = QuickShowcaseScreen(instantDrop = true)
        minecraft.setScreen(quick)
        if (!ClientConfig.quickShowcasePressOnce && !CobbleMarketClient.isQuickShowcaseKeyHeld()) {
            quick.requestClose()
        }
    }

    /**
     * **发送成功**（回包到达后）：播放**关闭动画**回游戏 —— 与展示精灵 / 物品 / ID 卡完全一致：
     * 三框原地出现后向上收起（[QuickShowcaseScreen.requestClose]），而不是回快速功能界面停留。
     * （2026-11-20 用户纠正：只有取消才回快速界面，发送成功是"展示完成"的收尾。）
     */
    private fun closeAfterSuccessfulShare() {
        val minecraft = client ?: return
        val quick = QuickShowcaseScreen(instantDrop = true)
        minecraft.setScreen(quick)
        quick.requestClose()
    }

    /** 只有取消（按钮 / Esc）走这里；发送成功走 [closeAfterSuccessfulShare] */
    override fun close() {
        returnToQuickShowcase()
    }

    override fun removed() {
        super.removed()
        runCatching { MinecraftClient.getInstance().textureManager.destroyTexture(texture) }
    }

    override fun shouldPause() = false

    companion object {
        /** 弹窗左右内边距（相框 / 名字框与弹窗边缘之间） */
        private const val DIALOG_SIDE_PAD = 20

        /** 标题区总高：顶部 8 + 标题 8 + 与相框的间隙 10 */
        private const val TITLE_BLOCK_H = 26

        /** 相框与名字输入框之间的空隙 */
        private const val NAME_GAP = 10

        /** 名字输入框高度 */
        private const val NAME_H = 18

        /** 名字输入框与按钮行之间的空隙（发送中提示画在这里） */
        private const val BUTTON_GAP = 10

        private const val BUTTON_W = 80
        private const val BUTTON_H = 16

        /** 位置开关按钮（方形，比文字按钮高，视觉上垂直居中于按钮行） */
        private const val LOCATION_BTN = 22
        private const val LOCATION_ICON_TEX = 48
        private val LOCATION_ICON =
            Identifier.of("cobblemarket", "textures/gui/icon/location.png")

        /** 按钮之间的横向间距 */
        private const val BUTTON_GAP_X = 4

        /** 按钮距弹窗底（与其余弹窗统一 10px） */
        private const val DIALOG_BOTTOM_PAD = 10

        /** 名字长度上限（与服务端截断长度一致） */
        private const val MAX_NAME_LENGTH = 32

        /** 发送等待回包的超时（超过解锁按钮；服务端冷却仍会兜底拒绝重复发送） */
        private const val SEND_TIMEOUT_MS = 5_000L
    }
}
