package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.ClientConfig
import com.shusheng.cobblemarket.client.IdCardBackgroundTexture
import com.shusheng.cobblemarket.client.IdCardDataCache
import com.shusheng.cobblemarket.client.IdCardRenderer
import com.shusheng.cobblemarket.client.OVERVIEW_ICON_W
import com.shusheng.cobblemarket.client.OverviewLine
import com.shusheng.cobblemarket.client.amountUnitWidth
import com.shusheng.cobblemarket.client.drawAmountWithUnit
import com.shusheng.cobblemarket.client.currencyUnitWidth
import com.shusheng.cobblemarket.client.drawCurrencyUnit
import com.shusheng.cobblemarket.client.drawItemWithBar
import com.shusheng.cobblemarket.client.drawLineIcon
import com.shusheng.cobblemarket.client.lineIconY
import com.shusheng.cobblemarket.client.lineTextY
import com.shusheng.cobblemarket.client.overviewLines
import com.shusheng.cobblemarket.util.TextUtil
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.ingame.InventoryScreen
import net.minecraft.entity.EquipmentSlot
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.opengl.GL11

/**
 * **市场 ID 卡** —— 玩家看自己在这套系统里的全部状态（人人可用，自己看自己，不走权限节点）。
 *
 * 版式（比例全部从内置背景图量出来，见方案第十二节）：
 * - **卡片本体 = 图的下方「宽 × 9/16」那块**，图按卡片宽等比缩放、**底边与左右对齐卡片**，
 *   顶部多出来的部分（服主放的吉祥物 / 标题）**照画不裁**、自然露在卡片上方
 * - 卡片内部：竖分割线在 **36.6%** 处 ⇒ 左 **37%** 是玩家模型区、右 **63%** 是信息区
 * - ⭐ 模型画在模型区的**偏上**（内框顶 → 75.1% 那块），底部那一条让给品牌字
 *
 * ⚠ 与全服总览的分工：总览是"服主看所有人"（要 `overview` 节点），本界面是"自己看自己"；
 *   **内容与总览的悬停窗逐项一致** —— 靠共用 `overviewLines` / 同一个 `buildRows` 保证。
 */
class IdCardScreen : Screen(Text.translatable("cobblemarket.idcard.title")) {

    private companion object {
        /**
         * 卡片本体逻辑尺寸（16:9）。
         * ⚠ **440 是屏幕高度允许的上限** —— 再宽卡片高就到 270，加上顶部装饰会超出一屏
         *   （1080p 默认档位的逻辑屏高只有 360）。
         * ⚠ 加宽会让**背景图里的一切等比放大**（标题 / 装饰精灵 / 内框 / 分割线），
         *   形状不变（等比缩放，不是拉伸）—— 2026-10-16 用户拍板走这条，
         *   为的是给英文标签和更长的金额留出宽度。
         */
        const val CARD_W = 440

        /** 440 × 9/16 = 247.5 —— **必须与图的 9/16 严格一致**，否则图里的边框会与卡片本体错位 */
        const val CARD_H = 247.5f

        // 内置背景图（纹理 id + 尺寸）**收口在 [IdCardBackgroundTexture]** 那一组 ——
        // 这里不再各写一份：那个 object 是「没配图时用哪张、多大」的唯一定义处
        // （2026-10-17 收口，此前项目里散着三份 1280×940）

        // ── 卡片内部比例（从内置图量出来的，见 docs/player_overview_plan.md 第十二节）──
        const val INNER_LEFT = 0.051f
        const val INNER_RIGHT = 0.952f
        const val INNER_TOP = 0.139f
        const val INNER_BOTTOM = 0.864f
        /** 竖分割线（模型区 / 信息区的分界） */
        const val SPLIT = 0.366f
        /** 品牌字顶部 —— 模型只画到这条线以上（「模型偏上」的量化落点） */
        const val TITLE_TOP = 0.751f

        /**
         * 模型占可用区高度的比例。
         * ⚠ `drawEntity` 的缩放是**按区域高度算的** —— 区域取满会让模型比原先大一圈
         *   （2026-10-16 用户实测问「为什么变大了」），所以位置用满、大小单独收一点。
         */
        const val MODEL_SCALE = 0.85f

        /**
         * 模型整体**下移**的像素数 —— 给「戴了模组的帽子 / 装了个子更高的模型」留顶部余量
         * （用户 2026-10-16 要求；模型是**居中**于那块区域的，上下各只剩 ~17px）。
         * ⚠ 别调太大：实测整体下移 18px 就会让脚压到品牌字（2026-10-16 第二轮实测）。
         * ⚠ 只挪区域、不改区域高度 ⇒ 模型的**缩放不变**（size 是按区域高度算的）。
         */
        const val MODEL_DROP = 10

        /** 原版 HUD 的经验条纹理（1.21 起走 GUI atlas） */
        val EXPERIENCE_BAR_BACKGROUND_TEXTURE = Identifier.of("minecraft", "hud/experience_bar_background")
        val EXPERIENCE_BAR_PROGRESS_TEXTURE = Identifier.of("minecraft", "hud/experience_bar_progress")

        /** 入场掉落动画：下落 / 停留 / 淡出（毫秒）—— 与入口界面同款 */
        const val DROP_DURATION_MS = 100L
        const val HOLD_DURATION_MS = 100L
        const val FADE_DURATION_MS = 100L
    }

    /** 金额行的命中框：界面显示的是**缩写**（`9.8k`），鼠标停上来时用完整千分位展全 */
    private class AmountHit(val x: Int, val y: Int, val w: Int, val full: String)
    private val amountHits = mutableListOf<AmountHit>()

    /** 入场掉落动画开始时间（首次 init 记录；resize 重建不重播）—— 与入口界面同款三段动画 */
    private var dropAnimStart = -1L

    /** 模型右下角的「跟随 / 旋转」切换按钮（图标随状态变化，要持引用） */
    private var spinButton: NineSliceButton? = null

    override fun init() {
        super.init()
        // 入场掉落动画只在新打开时播（resize 重建不重播）—— 与入口界面的 dropAnimStart 同款
        if (dropAnimStart < 0) dropAnimStart = System.currentTimeMillis()
        // 返回按钮 —— **卡片内容区右下角**（2026-10-16 用户实测反馈：原先放屏幕右上角、离得太远）
        val geo = geometry()
        addDrawableChild(NineSliceButton(
            // ⚠ 右缘要**离内框右边那条线 6px** —— 贴到 innerRight 会正好压在白线上（2026-10-16 用户实测）
            (geo.innerRight - 50 - 6).toInt(), (geo.innerBottom - 16 - 2).toInt(), 50, 16,
            Text.literal(""),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        ))

        // 展示按钮（2026-10-17）：把自己的**整张 ID 卡**发到全服聊天（别人悬停能看整张卡）。
        // 发完直接关界面回游戏 —— 展示的落点是聊天，玩家该直接看到那条播报
        addDrawableChild(NineSliceButton(
            (geo.innerRight - 50 - 6 - 50 - 4).toInt(), (geo.innerBottom - 16 - 2).toInt(), 50, 16,
            Text.literal(""),
            {
                // 置位「ID 卡展示」标记 —— 界面立刻关掉，回包时靠它播展示专属音效
                com.shusheng.cobblemarket.client.CobbleMarketClient.pendingIdCardShare = true
                com.shusheng.cobblemarket.platform.sendToServer(
                    com.shusheng.cobblemarket.network.SharePayload(
                        com.shusheng.cobblemarket.network.ShareNetwork.KIND_IDCARD, ""
                    )
                )
                client?.setScreen(null)
            },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/icon/share.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.share.id_card_button")
        ))

        // 模型右下角：切换「跟随鼠标 / 原地旋转」（2026-10-28）。
        // 按钮**底部与模型脚底齐平**：模型居中于「内框顶 → 品牌字顶」区域、缩放 0.85
        // ⇒ 脚底 ≈ 区域底往上约 11px
        val spinBtn = NineSliceButton(
            (geo.split - 18 - 4).toInt(),
            (geo.titleTop + MODEL_DROP - 33).toInt(),
            18, 18,
            Text.literal(""),
            {
                ClientConfig.setIdCardSpin(!ClientConfig.idCardSpin)
                applySpinButtonIcon()
            },
            tooltip = Text.translatable("cobblemarket.idcard.spin_toggle")
        )
        spinButton = spinBtn
        addDrawableChild(spinBtn)
        applySpinButtonIcon()

        // 每次打开都重新拉：1) 自己的数据（顺便刷新）2) 背景图（服主换图后无需重连）
        IdCardDataCache.request()
        IdCardBackgroundTexture.requestRefresh()
    }

    /**
     * 旋转按钮的图标随状态切换：
     * - 跟随鼠标：模组自己的鼠标图标（`cursor.png`，128×128）
     * - 原地旋转：用户提供的 `spin.png`（48×48）
     *
     * ⚠ 两张图像素尺寸不同，`iconTexW / iconTexH / iconScale` 必须**一起换** —— 只换 iconLeft
     *   会让 48×48 那张按 128 的缩放画成一小点。
     */
    private fun applySpinButtonIcon() {
        val btn = spinButton ?: return
        // 图标画成 14×14（按钮 18×18 里留 2px 边距）—— 按 18 画会顶满、鼠标图标略大越界
        // （2026-10-28 用户报「超过按钮本身大小」）
        if (ClientConfig.idCardSpin) {
            btn.iconLeft = Identifier.of("cobblemarket", "textures/gui/icon/spin.png")
            btn.iconTexW = 48
            btn.iconTexH = 48
            btn.iconScale = 14f / 48f
        } else {
            btn.iconLeft = Identifier.of("cobblemarket", "textures/gui/cursor/cursor.png")
            btn.iconTexW = 128
            btn.iconTexH = 128
            btn.iconScale = 14f / 128f
        }
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        drawCardBackground(context)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 入场掉落动画（照入口界面，受「界面动画」开关控制）：
        // 下落 + 停留阶段只画掉落的卡片；淡出阶段完整界面在下、卡片渐透明
        val animElapsed = System.currentTimeMillis() - dropAnimStart
        if (ClientConfig.marketAnimation) {
            if (animElapsed < DROP_DURATION_MS + HOLD_DURATION_MS) {
                renderDropCard(context, animElapsed, 1f)
                return
            }
            val fadeElapsed = animElapsed - DROP_DURATION_MS - HOLD_DURATION_MS
            if (fadeElapsed < FADE_DURATION_MS) {
                renderCardContent(context, mouseX, mouseY, delta)
                // flush 夹心（照入口界面 / 精灵动画）：先提交界面，再画卡片并立刻提交，
                // 否则界面写进深度缓冲后会把后画的卡片整片剔除
                context.draw()
                RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
                renderDropCard(context, animElapsed, 1f - fadeElapsed.toFloat() / FADE_DURATION_MS)
                context.draw()
                return
            }
        }
        renderCardContent(context, mouseX, mouseY, delta)
    }

    /** 界面本体（动画结束后 / 淡出阶段的底图） */
    private fun renderCardContent(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        val row = IdCardDataCache.row ?: run {
            context.drawCenteredTextWithShadow(
                textRenderer, Text.translatable("cobblemarket.overview.loading"),
                width / 2, height / 2, 0xFFFFFF
            )
            return
        }

        val geo = geometry()
        // ⚠ 顺序要紧：**先画 2D 文字、最后画 3D 模型**。反过来的话，模型之后绘制的 GUI
        //   会把深度写进缓冲，把模型切掉一块（2026-10-16 用户实测报「模型顶部被切割」）
        drawInfoColumns(context, row, geo)
        drawPlayerModel(context, geo, mouseX, mouseY)
        // 模型左侧的装备列：**必须放在模型之后**（模型绘制会 flush + 清深度，
        // 放到前面会被 3D 批次覆盖/切块）
        drawEquipSlots(context, geo)
        // 卡片底部居中：经验条 + 等级（照原版物品栏上方的 HUD 样式）
        drawExperienceBar(context, geo)

        // 金额是**缩写**显示（`9.8k`）⇒ 停在那一行上时展全完整千分位
        // （照入口界面「全服累计成交额」的做法：缩写 + 悬停出完整值）
        amountHits.firstOrNull { mouseX in it.x until (it.x + it.w) && mouseY in it.y until (it.y + 10) }
            ?.let { renderAmountTooltip(context, mouseX, mouseY, it.full) }
    }

    /**
     * 掉落动画的卡片：**当前生效的背景图**（服主换的也算）从屏幕顶外缓出落到卡片位置，
     * 同时 0→1 放大（照入口界面的 `renderDropImage`）。[alpha] 供淡出阶段渐变。
     */
    private fun renderDropCard(context: DrawContext, elapsed: Long, alpha: Float) {
        val entry = IdCardBackgroundTexture.get()
        val tex = entry?.id ?: IdCardBackgroundTexture.BUILTIN_TEX
        val texW = entry?.width ?: IdCardBackgroundTexture.BUILTIN_WIDTH
        val texH = entry?.height ?: IdCardBackgroundTexture.BUILTIN_HEIGHT
        val geo = geometry()
        val imgScale = CARD_W.toFloat() / texW
        val drawW = texW * imgScale
        val drawH = texH * imgScale
        val k = (elapsed.toFloat() / DROP_DURATION_MS).coerceIn(0f, 1f)
        fun smooth(v: Float): Float = v * v * (3f - 2f * v)
        val eased = smooth(k)
        // 起点：图底贴屏幕顶（完全在屏幕外）
        val y = -drawH + (geo.textureTop + drawH) * eased
        val scale = eased * imgScale
        context.matrices.push()
        context.matrices.translate((geo.textureLeft + drawW / 2f).toDouble(), (y + drawH / 2f).toDouble(), 0.0)
        context.matrices.scale(scale, scale, 1f)
        // RGB 随 alpha 一起衰减：先变暗再消失（照入口界面）
        context.setShaderColor(alpha, alpha, alpha, alpha)
        context.drawTexture(tex, -texW / 2, -texH / 2, 0f, 0f, texW, texH, texW, texH)
        context.setShaderColor(1f, 1f, 1f, 1f)
        context.matrices.pop()
    }

    /** 完整金额的小面板（照 `MarketEntryScreen.renderAmountTooltip`：鼠标右上、贴屏边兜底） */
    private fun renderAmountTooltip(context: DrawContext, mx: Int, my: Int, amount: String) {
        val pad = 4
        val w = amountUnitWidth(textRenderer, amount, false) + 2 * pad
        val h = 14
        val tx = minOf(mx + 12, width - w - 4)
        val ty = maxOf(my - h - 4, 0)
        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx, ty, w, h, 1, ROW_BACKGROUND_TEX_H)
        drawAmountWithUnit(context, textRenderer, amount, false, tx + pad, ty + 3, 0xFFD700.toInt())
        context.matrices.pop()
    }

    // ── 背景：整张图按卡片宽等比缩放、底边对齐卡片底边（顶部溢出照画）──

    private fun drawCardBackground(context: DrawContext) {
        val entry = IdCardBackgroundTexture.get()
        val tex = entry?.id ?: IdCardBackgroundTexture.BUILTIN_TEX
        val texW = entry?.width ?: IdCardBackgroundTexture.BUILTIN_WIDTH
        val texH = entry?.height ?: IdCardBackgroundTexture.BUILTIN_HEIGHT

        val geo = geometry()
        val scale = CARD_W.toFloat() / texW

        context.matrices.push()
        // ⚠ drawTexture 的 width/height 是**采样区**（不是显示尺寸）⇒ 缩放只能靠 matrices.scale
        context.matrices.translate(geo.textureLeft.toDouble(), geo.textureTop.toDouble(), 0.0)
        context.matrices.scale(scale, scale, 1f)
        context.drawTexture(tex, 0, 0, 0f, 0f, texW, texH, texW, texH)
        context.matrices.pop()
    }

    /** 卡片几何：图按卡片宽缩放后的整体位置 + 卡片内框与分割线 */
    private class Geometry(
        val cardLeft: Float, val cardTop: Float, val cardBottom: Float,
        val textureLeft: Float, val textureTop: Float,
        val innerLeft: Float, val innerRight: Float, val innerTop: Float, val innerBottom: Float,
        val split: Float, val titleTop: Float,
    )

    private fun geometry(): Geometry {
        val entry = IdCardBackgroundTexture.get()
        val texW = entry?.width ?: IdCardBackgroundTexture.BUILTIN_WIDTH
        val texH = entry?.height ?: IdCardBackgroundTexture.BUILTIN_HEIGHT
        val scale = CARD_W.toFloat() / texW
        val drawH = texH * scale

        // ⭐ **卡片本体（内容框）居中**，不是整张图居中 ——
        //   图底边与卡片底边对齐，顶部装饰往上溢出 ⇒ **整张图是偏上的**
        //   （2026-10-16 用户实测指出：我原先让整张图居中，卡片就整个沉到屏幕下半部了）
        val cardTop = (height - CARD_H) / 2f
        val cardBottom = cardTop + CARD_H
        val textureTop = cardBottom - drawH
        val cardLeft = (width - CARD_W) / 2f

        return Geometry(
            cardLeft = cardLeft, cardTop = cardTop, cardBottom = cardBottom,
            textureLeft = cardLeft, textureTop = textureTop,
            innerLeft = cardLeft + CARD_W * INNER_LEFT,
            innerRight = cardLeft + CARD_W * INNER_RIGHT,
            innerTop = cardTop + CARD_H * INNER_TOP,
            innerBottom = cardTop + CARD_H * INNER_BOTTOM,
            split = cardLeft + CARD_W * SPLIT,
            titleTop = cardTop + CARD_H * TITLE_TOP,
        )
    }

    // ── 左侧：玩家自己的 3D 模型（偏上，底部让给品牌字）──

    private fun drawPlayerModel(context: DrawContext, geo: Geometry, mouseX: Int, mouseY: Int) {
        val player = MinecraftClient.getInstance().player ?: return

        // ⚠ 画 3D 前必须 **先 flush 已排队的 GUI、再清深度缓冲** —— 否则模型会被残留的深度信息
        //   切掉一块（精灵图标为此时栽过好几轮，见 memory project_pokemon_icon_depth）
        context.draw()
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)

        // ⚠ 位置是 2026-10-16 用户**两轮**实测定下来的：区域取满「内框顶 → 品牌字顶」整块，
        //   既不留余量也不下移 —— 第一次留 85% 余量显得偏上，第二次整体下移 18px 又压住了品牌字
        // ➕ 再按用户要求整体下移 MODEL_DROP：戴帽子的模型更高，顶部原来会贴着内框上沿
        val x1 = geo.innerLeft.toInt()
        val y1 = geo.innerTop.toInt() + MODEL_DROP
        val x2 = geo.split.toInt()
        val y2 = geo.titleTop.toInt() + MODEL_DROP
        val modelBottom = y2

        // ⚠ 1.21.1 里这个方法叫 `drawEntity`（旧名 renderEntityInInventoryFollowsMouse 已不存在，
        //   老代码/教程里还能见到）—— 反编译 MC 的映射 jar 确认过签名
        val modelSize = ((modelBottom - y1) * MODEL_SCALE / 2f).toInt()   // size（缩放）—— 别用满区域高度
        if (ClientConfig.idCardSpin) {
            // 原地匀速旋转：相机绕 Y 转 = 模型自转（`drawEntity` 的 atan 朝向只有 ±63°，转不了 360°）
            IdCardRenderer.drawSpinningEntity(
                context, x1, y1, x2, modelBottom, modelSize, IdCardRenderer.spinAngleRad(), player
            )
        } else {
            InventoryScreen.drawEntity(
                context, x1, y1, x2, modelBottom, modelSize,
                0f,                               // yOffset
                mouseX.toFloat(), mouseY.toFloat(),
                player
            )
        }

        // 画完把模型批次提交、**再清一次深度** —— 别把 3D 的深度留给后续绘制
        // （下一帧的卡片背景 / 文字都会受它影响，表现就是"莫名其妙被切一块"）
        context.draw()
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
    }

    /**
     * 模型**左侧**的装备列：头盔 / 胸甲 / 护腿 / 靴子，自上而下 4 格、上下间距 1px。
     *
     * - 背景框用 `dialog_background.png`（与弹窗同款九宫格），空槽也画框
     * - 槽体 22×22、物品 16×16 居中（留 3px 边距，别让 16×16 的图标把边框压没）
     * - 只画**自己**的装备：本界面本来就看自己，直接读客户端玩家实体，不需要网络包
     * - ⚠ 必须在 [drawPlayerModel] 之后调用（模型会 flush + 清深度，放到前面会被切块）
     */
    private fun drawEquipSlots(context: DrawContext, geo: Geometry) {
        val player = MinecraftClient.getInstance().player ?: return
        val slotSize = 22
        val gap = 1
        val x = (geo.innerLeft + 4).toInt()
        val y0 = (geo.innerTop + MODEL_DROP).toInt()
        val slots = listOf(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        )
        slots.forEachIndexed { i, slot ->
            val y = y0 + i * (slotSize + gap)
            drawNineSlice(
                context, DIALOG_BACKGROUND_TEXTURE, x, y, slotSize, slotSize,
                0, DIALOG_BACKGROUND_TEX_H
            )
            val stack = player.getEquippedStack(slot)
            if (!stack.isEmpty) drawItemWithBar(context, stack, x + 3, y + 3)
        }
    }

    /**
     * 卡片底部**居中**的经验条 + 等级（照原版物品栏上方的 HUD：
     * 绿色进度条 + 条正上方的绿色等级数字）。
     *
     * 位置：内框底线与卡片底边之间那两条装饰白线之间，水平居中于卡片。
     */
    private fun drawExperienceBar(context: DrawContext, geo: Geometry) {
        val player = MinecraftClient.getInstance().player ?: return
        // 按卡片比例缩小（原版 182×5 在 440 宽的卡上太大、也太靠下 —— 2026-10-27 用户实测）
        val barW = 120
        val barH = 4
        // +1px：背景图本身不是绝对对称，纯居中看着偏左（2026-10-28 用户实测）
        val centerX = geo.cardLeft + CARD_W / 2f + 1f
        val x = (centerX - barW / 2f).toInt()
        // 与右下角「分享 / 返回」按钮**同一水平中心**：按钮顶 = innerBottom-18、高 16 ⇒ 中心 = innerBottom-10；
        // 条高 4 ⇒ 条顶 = 中心 - 2（2026-10-28 用户指定）
        val y = (geo.innerBottom - 12f).toInt()
        context.drawGuiTexture(EXPERIENCE_BAR_BACKGROUND_TEXTURE, x, y, barW, barH)
        val progress = (player.experienceProgress * barW).toInt().coerceIn(0, barW)
        if (progress > 0) {
            context.drawGuiTexture(
                EXPERIENCE_BAR_PROGRESS_TEXTURE, 182, 5, 0, 0, x, y, progress, barH
            )
        }
        // 等级数字：原版绿色（0x80FF20），条正上方居中、带阴影
        context.drawCenteredTextWithShadow(
            textRenderer, Text.literal(player.experienceLevel.toString()),
            centerX.toInt(), y - 8, 0x80FF20
        )
    }

    // ── 右侧：两列信息（与总览悬停窗同一份文本，只是排成两列）──

    private fun drawInfoColumns(context: DrawContext, row: com.shusheng.cobblemarket.overview.PlayerOverviewRow, geo: Geometry) {
        // showOnline = false：自己看自己必然在线，名字后面不跟「在线」（用户 2026-10-16 指出）
        val lines = overviewLines(row, showOnline = false)
        amountHits.clear()
        // ⚠ 按**分组块**分列，一块不拆开 —— 2026-10-16 用户实测指出：原先按"前半 / 后半"硬切，
        //   会出现「钱」这个标题落在左列末尾、它的内容（主货币余额）却跑到右列开头的割裂
        val columns = splitByGroups(lines)

        // ⚠ 2026-10-16 用户实测：文字要**下移并右移**（原先顶着内框上沿、紧贴分割线）
        val startX = geo.split + 10f
        val startY = geo.innerTop + 18f
        val colGap = 8f
        val colW = (geo.innerRight - geo.split - colGap) / 2f

        columns.forEachIndexed { ci, col ->
            val x = startX + ci * (colW + colGap)
            // ⚠ 行高**逐行累加**（不是 `下标 × 10`）—— 带图标的那两行比普通行高（OVERVIEW_ICON_ROW_HEIGHT）
            var y = startY
            col.forEach { line ->
                // ⚠ 行内位置**只用这两个算式**（与悬停窗共用同一份，别在这里另算一遍 —— 两处各算迟早不同步）
                val rowY = y.toInt()
                val textY = lineTextY(rowY, line)
                val iconY = lineIconY(rowY, line)
                // ⚠ 截断到列宽，别让长文案压到内框右边那条线。
                //   用 `truncateStyled`：它会**保住词条参数里那段的颜色**，而 `truncateText`
                //   只留顶层样式，会把金额的颜色抹掉。
                //   ⚠ 还得**扣掉货币单位的宽度** —— 单位是截断之后另外补画的，
                //     不扣的话文字截到线内、单位照样顶出去（2026-10-16 用户实测报的就是这个）
                // ⚠ 单位宽度/画法都**按这一行的币种**：RP 行补 RP 图标、主货币行补 ₽ / 物品图标
                val unitW = if (line.money) currencyUnitWidth(textRenderer, line.isPoints) else 0
                // 行首有图标时（图鉴那两行）：文字要**右移**、截断宽度也要把图标那块扣掉
                val iconW = if (line.icon != null) OVERVIEW_ICON_W else 0
                val text = TextUtil.truncateStyled(line.text, (colW.toInt() - unitW - iconW).coerceAtLeast(0))
                line.icon?.let { drawLineIcon(context, it, x.toInt(), iconY) }
                val drawn = context.drawTextWithShadow(textRenderer, text, (x + iconW).toInt(), textY, 0xFFFFFF)
                // 金额行：数字的颜色已由词条参数自带（金 = 能花的钱 / 蓝 = 额度、待还），这里只补行尾的单位
                if (line.money) {
                    drawCurrencyUnit(context, textRenderer, line.isPoints, drawn, textY, line.color)
                    // 记下命中框 —— 界面上是缩写，鼠标停上来要能展全完整千分位
                    line.fullAmount?.let {
                        val endX = drawn + currencyUnitWidth(textRenderer, line.isPoints)
                        amountHits += AmountHit(x.toInt(), textY, endX - x.toInt(), it)
                    }
                }
                y += line.height
            }
        }
    }

    /**
     * 把行按**分组块**切成两列：一块（标题 + 它的内容）不会被拆到两列去。
     * 先按分组标题切块，再从前往后填：第一列够一半了，后面的整块给第二列。
     */
    private fun splitByGroups(lines: List<OverviewLine>): List<List<OverviewLine>> {
        val blocks = mutableListOf<MutableList<OverviewLine>>()
        lines.forEach { line ->
            when {
                // 分组标题开新的一块；首行（名字那行）也自起一块
                line.isGroup || blocks.isEmpty() -> blocks += mutableListOf(line)
                else -> blocks.last().add(line)
            }
        }
        val target = lines.size / 2
        val col1 = mutableListOf<OverviewLine>()
        val col2 = mutableListOf<OverviewLine>()
        blocks.forEach { b -> if (col1.size < target) col1.addAll(b) else col2.addAll(b) }
        return listOf(col1, col2)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean = false

    override fun shouldPause() = false
}
