package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.client.ClientConfig
import com.shusheng.cobblemarket.client.IconAnimMode
import com.shusheng.cobblemarket.client.drawItemWithBar
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.shusheng.cobblemarket.client.icon.PreviewStateHolder
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.ShareNetwork
import com.shusheng.cobblemarket.network.SharePayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.lwjgl.glfw.GLFW
import java.util.UUID

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
 * 按钮上画的是**要展示的东西本身**（不是固定图标）：
 * - 精灵 ⇒ **那只精灵的 3D 图**（头部跟随鼠标）+ 落到即叫一声；队伍位为空时只画槽底图
 * - 物品 ⇒ **主手那件**的物品图（受损时带耐久条）；空手时画精灵球占位图
 *
 * ⚠ **必须在 `isMarketScreen` 白名单里**（`client/CobbleMarketClient.kt`）——
 *   否则按住 X 时没有自定义光标（鼠标开关也就形同虚设）。
 * ⚠ `shouldPause() = false` + 不画暗罩：按住期间世界照常跑、画面照常可见（单机也不暂停）。
 * ⚠ 松键关屏由 tick 负责（见 `CobbleMarketClient` 里对 X 键的轮询）；
 *   展示成功/被拒的收尾走 [onMarketResult]（**等回包**才关，见那里的注释）。
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

    // ── 精灵那一侧的渲染状态 ──
    /**
     * ⚠ **必须按 uuid 缓存 renderable / state**：`PreviewStateHolder.stateFor` 是**引用比较**，
     * 每帧重建 renderable 等于每帧重置 state ⇒ 动画永远播不出来（只会立在第一帧）。
     */
    private var cachedUuid: UUID? = null
    private var cachedRenderable: RenderablePokemon? = null
    private val pokemonState = PreviewStateHolder()

    /** 精灵叫声的到点时刻（0 = 不叫）。换精灵时重新安排 —— 等价于"弹窗打开置位" */
    private var cryAt = 0L

    override fun init() {
        super.init()
        baseY = height / 2 - BTN / 2
        val leftX = width / 2 - BTN - GAP / 2
        val rightX = width / 2 + GAP / 2

        // ⚠ 按钮**只留背景**（不给 iconFrames / iconLeft）：内容由 render 自己画 —— 3D 精灵与
        //   物品图都必须画在 super.render 之后、且要先 flush 清深度，交给按钮画反而做不到
        pokemonBtn = NineSliceButton(
            leftX, baseY, BTN, BTN, Text.literal(""), { doShowcase(Kind.POKEMON) },
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S
        ).also {
            it.setTooltip(Tooltip.of(Text.translatable("cobblemarket.share.pokemon")))
            addDrawableChild(it)
        }
        itemBtn = NineSliceButton(
            rightX, baseY, BTN, BTN, Text.literal(""), { doShowcase(Kind.ITEM) },
            texture = BUTTON_TEXTURE_S, texH = BUTTON_TEX_H_S
        ).also {
            it.setTooltip(Tooltip.of(Text.translatable("cobblemarket.share.item")))
            addDrawableChild(it)
        }
    }

    /** 不画暗罩：按住期间世界照常可见（照其余模组界面） */
    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) = Unit

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // ⚠ 必须在最开头：头部跟随靠它拿鼠标坐标，不调则精灵盯着屏幕左上角不动
        IconRegistry.beginFrame(mouseX, mouseY)

        // 掉落 + 悬停上浮都是**直接改按钮的 y**（瞬间跳，不做缓动）
        val drop = dropOffsetY()
        val hovered = hoveredAt(mouseX, mouseY, drop)
        hoveredKind = hovered
        val pokemonY = baseY + drop - (if (hovered == Kind.POKEMON) LIFT else 0)
        val itemY = baseY + drop - (if (hovered == Kind.ITEM) LIFT else 0)
        pokemonBtn?.y = pokemonY
        itemBtn?.y = itemY

        // 先让按钮把自己的背景画出来（贴图在下），内容再压上去
        super.render(context, mouseX, mouseY, delta)

        drawPokemonContent(context, width / 2 - BTN - GAP / 2, pokemonY, delta)
        drawItemContent(context, width / 2 + GAP / 2, itemY)
    }

    // ── 两个按钮的内容 ──

    private fun drawPokemonContent(context: DrawContext, x: Int, y: Int, delta: Float) {
        refreshRenderable()
        // 内容一律画在内框里（⚠ 按钮贴图自带边框，铺满会把它盖掉）
        val cx = x + INSET
        val cy = y + INSET
        // ⚠⚠ **不画槽底占位图**（用户 2026-09-30 定）—— 按钮上只放「要展示的那个东西本身」。
        //   2026-09-29 曾**刻意与物品侧不对称**（精灵侧画 `pokemon_slot.png` 当"位置"的表示），
        //   2026-09-30 用户改口径：这里也不用占位图 ⇒ **两侧现在一致**（空 ⇒ 什么都不画）。
        //   所以队伍位为空时，按钮上只剩它自己的背景（只有刚进游戏还没选初始精灵才会这样）。
        val rp = cachedRenderable ?: return

        // ⚠ 3D 之前要 flush 拼批 + 清深度，否则模型会被先前画的 GUI 整片剔除
        context.draw()
        RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
        PokemonIconRenderer.draw(
            context = context,
            renderable = rp,
            state = pokemonState.stateFor(rp),
            x = cx, y = cy, size = ICON_SIZE, delta = delta,
            // 静态模式下不跟头（与其余界面同一个判据）
            animate = ClientConfig.iconAnimMode == IconAnimMode.FLOAT,
            useScissor = false
        )
        context.draw()

        // ⚠ 叫一声必须在**画完模型之后**：模型是渲染时才建的，提前调没有 currentModel 可播
        if (cryAt != 0L && System.currentTimeMillis() >= cryAt) {
            cryAt = 0L
            IconCryClick.play(pokemonState.stateFor(rp), rp)
        }
    }

    /** 只在**换了精灵**时重建 renderable（按 uuid 判）—— 每帧重建会让动画永远停在第一帧 */
    private fun refreshRenderable() {
        val pokemon = CobblemonClient.storage.party.get(CobblemonClient.storage.selectedSlot)
        val uuid = pokemon?.uuid
        if (uuid == cachedUuid) return
        cachedUuid = uuid
        cachedRenderable = pokemon?.asRenderablePokemon()
        // 换了一只（或换成空的）= 一次新的"打开"⇒ 重新安排叫声；空的不叫
        cryAt = if (cachedRenderable != null) {
            System.currentTimeMillis() + IconCryClick.AUTO_CRY_DELAY_MS
        } else {
            0L
        }
    }

    private fun drawItemContent(context: DrawContext, x: Int, y: Int) {
        val stack = client?.player?.mainHandStack
        // 空手 ⇒ **什么都不画**（用户 2026-09-29 定：按钮留白，不要占位图）。
        // ⚠ 两侧**已对称**：精灵那侧的槽底图也在 2026-09-30 去掉了（见 [drawPokemonContent]）。
        if (stack == null || stack.isEmpty) return
        // ⚠ 物品图同样要先 flush + 清深度（照物品悬停大图那套）
        context.draw()
        RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
        val s = ITEM_SCALE
        // 图标以按钮中心为基准放大：16×16 的图标，左上角要往左上各退 8×s
        context.matrices.push()
        context.matrices.translate(
            (x + BTN / 2f - 8f * s).toDouble(),
            (y + BTN / 2f - 8f * s).toDouble(),
            0.0
        )
        context.matrices.scale(s, s, 1f)
        // 唯一入口：内部按 stack.rarity 上色，**受损时才画耐久条**（满耐久不画）
        drawItemWithBar(context, stack, 0, 0)
        context.matrices.pop()
        context.draw()
    }

    // ── 命中 / 动画 / 收尾 ──

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
        // ⚠ **发完不要立刻关界面**：要等服务端回包再关。两个原因：
        //   ① 展示专属音效的判定是"回包到达时界面还开着"（`CobbleMarketClient` 的 shareSucceeded）
        //      —— 提前关掉就会落到通用成功音（2026-09-29 用户实测报的）；
        //   ② 界面里点行展示也是等回包的，行为要一致。
        //   ⚠ 失败（空手 / 空位）在上面各自 return 前已经关掉了，那条路不等回包。
    }

    /**
     * 服务端回包（展示成功、或被 10 秒冷静期拒掉）—— 与界面里点行展示**同一套收尾**：
     * 结果消息发到聊天栏（成功绿字 / 失败红字），然后关界面。
     *
     * ⚠ **失败那条必须发**：被冷静期拒掉时服务端给的原因就在 [MarketResultPayload.message] 里，
     *   少了它玩家只听到一声失败音、不知道是自己点太快
     *  （2026-09-29 用户报的：快速展示与界面里点行展示两条路没对齐）。
     */
    fun onMarketResult(payload: MarketResultPayload) {
        client?.player?.sendMessage(
            payload.message.copy().formatted(if (payload.success) Formatting.GREEN else Formatting.RED),
            false
        )
        closeSelf()
    }

    private fun closeSelf() {
        client?.setScreen(null)
    }

    /**
     * 吞掉方向键。
     *
     * 这个界面上没有任何"用键盘操作"的东西，但方向键会被 MC 当成**界面焦点导航** ——
     * 焦点经过哪个按钮就把它的 tooltip 弹出来（2026-09-29 用户实测：按一下 ↓，
     * 精灵按钮左下角冒出「精灵」悬停窗，而鼠标根本没移上去）。
     *
     * ⚠ **不把方向键转发给 Cobblemon**（用户 2026-09-29 定）：按住 X 期间不给模组加"换精灵"
     *   的负担 —— 玩家松开 X 自己切就好。所以这里就是单纯吞掉。
     */
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN ||
            keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT
        ) {
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
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
        /**
         * 内容和按钮边缘之间留的空隙。
         *
         * ⚠ 按钮贴图**自带一圈窄边框**，内容铺满就会把它盖掉（2026-09-29 用户实测报的）——
         * 所以内容一律画在 `[INSET, BTN - INSET]` 这个内框里。
         */
        private const val INSET = 8
        /** 内容可用边长 */
        private const val CONTENT = BTN - INSET * 2
        /** 精灵模型的绘制尺寸（`PokemonIconRenderer` 的 size，内部自己算缩放） */
        private const val ICON_SIZE = CONTENT
        /** 物品放大倍数：16px 的图标 × 6 = 96px，在内容框里留一圈 */
        private const val ITEM_SCALE = CONTENT / 16f
    }
}
