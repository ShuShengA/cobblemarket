package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.client.CobblemonClient
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.ShareNetwork
import com.shusheng.cobblemarket.network.SharePayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

/**
 * **快速展示**：按住快捷键（默认 X）期间存在的**临时界面**（2026-09-29 用户定）。
 *
 * 形态：
 * - 两个大按钮从屏幕上方**落下**（掉落那套照入口界面），左右居中、中间留空隙，
 *   边长取自喵喵银行卡片（128）
 * - 鼠标悬停哪个，哪个**瞬间上浮**（⚠ 不做缓动 —— 这个功能就叫"快速展示"，动作不能拖）
 * - **松开快捷键** ⇒ 展示悬停的那个；没悬停任何按钮 ⇒ 什么都不做
 * - **点按钮** ⇒ 立刻展示并关掉（与在界面里点行一个效果）
 *
 * 两个按钮展示什么：
 * - 精灵 ⇒ 队伍里**当前选中的那只**（= 左侧队伍栏里可放出的那只，↑↓ / R+滚轮 切的同一个字段）
 * - 物品 ⇒ **主手**那件
 * - ⚠ 取不到（队伍位是空的 / 手上是空的）⇒ 失败音 + 聊天红字
 *
 * ⚠ **必须在 `isMarketScreen` 白名单里**（`client/CobbleMarketClient.kt`）——
 *   否则按住 X 时没有自定义光标（鼠标开关也就形同虚设）。
 * ⚠ `shouldPause() = false` + 不画暗罩：按住期间世界照常跑、画面照常可见（单机也不暂停）。
 * ⚠ 松键关屏由 tick 负责（见 `CobbleMarketClient` 里对 X 键的轮询），
 *   本类只提供 [finishAndClose] 给它调。
 */
class QuickShowcaseScreen : Screen(Text.literal("")) {

    /** 展示哪一类 */
    enum class Kind { POKEMON, ITEM }

    /** 掉落动画起点（构造时记；resize 重建不重置） */
    private var animStart = System.currentTimeMillis()

    /**
     * 鼠标**当前停在哪个按钮上**（null = 没停在任何按钮上）。
     *
     * tick 检测到松键时会读它：有值就展示那个，没值就什么都不做。
     * 每帧在 [render] 里更新（与上浮用的是同一份判定，不会出现"看着浮起来了、松手却没反应"）。
     */
    var hoveredKind: Kind? = null
        private set

    private var pokemonBtn: NineSliceButton? = null
    private var itemBtn: NineSliceButton? = null

    /** 两个按钮的目标 y（上浮与掉落都相对它算） */
    private var baseY = 0

    override fun init() {
        super.init()
        baseY = height / 2 - BTN / 2
        val leftX = width / 2 - BTN - GAP / 2
        val rightX = width / 2 + GAP / 2

        pokemonBtn = NineSliceButton(
            leftX, baseY, BTN, BTN,
            Text.literal(""),
            { doShowcase(Kind.POKEMON) },
            // 与入口界面同一个会动的小卡比兽（8 帧）
            iconFrames = (0..7).map { Identifier.of("cobblemarket", "textures/gui/munchlax/munchlax_$it.png") },
            iconTexW = 48, iconTexH = 48, iconScale = ICON_SCALE,
            // 入口型按钮的底色（与入口界面那两个子按钮同款）
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S
        ).also {
            it.setTooltip(Tooltip.of(Text.translatable("cobblemarket.share.pokemon")))
            addDrawableChild(it)
        }
        itemBtn = NineSliceButton(
            rightX, baseY, BTN, BTN,
            Text.literal(""),
            { doShowcase(Kind.ITEM) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/pokeball_icon.png"),
            iconTexW = 48, iconTexH = 48, iconScale = ICON_SCALE,
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S
        ).also {
            it.setTooltip(Tooltip.of(Text.translatable("cobblemarket.share.item")))
            addDrawableChild(it)
        }
    }

    /** 不画暗罩：按住期间世界照常可见（照其余模组界面） */
    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) = Unit

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 掉落 + 悬停上浮都是**直接改按钮的 y**（瞬间跳，不做缓动）
        val drop = dropOffsetY()
        val hovered = hoveredAt(mouseX, mouseY, drop)
        hoveredKind = hovered
        pokemonBtn?.y = baseY + drop - (if (hovered == Kind.POKEMON) LIFT else 0)
        itemBtn?.y = baseY + drop - (if (hovered == Kind.ITEM) LIFT else 0)
        super.render(context, mouseX, mouseY, delta)
    }

    /**
     * 命中判定**不读按钮自己的 y**，而是用「目标位 + 当前掉落偏移」现算 ——
     * 这样和上面设的 y 永远是同一份算式（读按钮的 y 会慢一帧，快速划过时会出现
     * "看着浮起来了、松手却没反应"）。
     */
    private fun hoveredAt(mouseX: Int, mouseY: Int, drop: Int): Kind? {
        fun hit(idx: Int): Boolean {
            val x = if (idx == 0) width / 2 - BTN - GAP / 2 else width / 2 + GAP / 2
            val y = baseY + drop
            return mouseX >= x && mouseX < x + BTN && mouseY >= y && mouseY < y + BTN
        }
        return when {
            hit(0) -> Kind.POKEMON
            hit(1) -> Kind.ITEM
            else -> null
        }
    }

    /** 从屏幕上方落到目标位的偏移（ease-out：像"落地"那样收尾）。动画只播一次 */
    private fun dropOffsetY(): Int {
        val t = ((System.currentTimeMillis() - animStart) / DROP_MS.toFloat()).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t)
        return -((1f - eased) * (baseY + BTN)).toInt()
    }

    /** 松键时 tick 调它：悬停哪个就展示哪个；没悬停 ⇒ 什么都不做，只关界面 */
    fun finishAndClose() {
        val kind = hoveredKind
        if (kind == null) closeSelf() else doShowcase(kind)
    }

    /** 展示 [kind] 然后关界面。取不到东西 ⇒ 失败音 + 聊天红字（用户 2026-09-29 定） */
    private fun doShowcase(kind: Kind) {
        val player = client?.player ?: return
        when (kind) {
            Kind.POKEMON -> {
                // ⚠ 这就是左侧队伍栏"可放出"的那只（同一个客户端字段），不是"队伍第 1 只"
                val pokemon = CobblemonClient.storage.party.get(CobblemonClient.storage.selectedSlot)
                if (pokemon == null) {
                    // 只有刚进游戏还没选初始精灵时才会走到这（Cobblemon 失去精灵时会自动改选别的位）
                    playFailSound()
                    player.sendMessage(
                        Text.translatable("cobblemarket.share.no_pokemon").formatted(Formatting.RED), false
                    )
                    closeSelf()
                    return
                }
                sendToServer(SharePayload(ShareNetwork.KIND_POKEMON, pokemon.uuid.toString()))
            }

            Kind.ITEM -> {
                if (player.mainHandStack.isEmpty) {
                    playFailSound()
                    player.sendMessage(
                        Text.translatable("cobblemarket.share.no_item").formatted(Formatting.RED), false
                    )
                    closeSelf()
                    return
                }
                // 服务端按**槽位号**读它自己的背包（归属天然成立）——与物品上架界面同一套
                sendToServer(SharePayload(ShareNetwork.KIND_ITEM, player.inventory.selectedSlot.toString()))
            }
        }
        // 展示成功由服务端回包触发收尾（跟界面里点行一样：不需要本界面自己再做什么）
        closeSelf()
    }

    private fun closeSelf() {
        client?.setScreen(null)
    }

    override fun shouldPause() = false

    companion object {
        /** 按钮边长（用户定：同喵喵银行那两张卡片） */
        private const val BTN = 128
        /** 两个按钮之间的空隙 */
        private const val GAP = 24
        /** 悬停时上浮的像素（瞬间跳） */
        private const val LIFT = 10
        /** 掉落时长（ms）—— 要"快速"，别拖 */
        private const val DROP_MS = 220L
        /** 图标缩放：48px 源图 × 2 = 96px，在 128 的按钮里留一圈边 */
        private const val ICON_SCALE = 2.0f
    }
}
