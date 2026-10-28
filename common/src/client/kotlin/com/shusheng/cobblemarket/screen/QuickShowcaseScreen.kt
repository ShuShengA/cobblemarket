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
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.lwjgl.glfw.GLFW
import java.util.UUID

/**
 * **快速展示**：按住（或按一下，模式在入口设置里切换）快捷键（默认 X）期间存在的**临时界面**（2026-09-29 用户定）。
 *
 * 形态：
 * - 三个大框从屏幕上方**落下**（掉落那套照入口界面），上排精灵/物品、下排 ID 卡，
 *   边长取自喵喵银行卡片（128）
 * - 鼠标悬停哪个，哪个**瞬间上浮**（⚠ 不做缓动 —— 这个功能就叫"快速展示"，动作不能拖）
 * - **按住模式**（默认）：松开快捷键 ⇒ 展示悬停的那个；没悬停任何按钮 ⇒ 什么都不做
 * - **按一下模式**：按一下 X 打开，**松开不关**；点框展示，再按一次 X（或 Esc / 点框外）关闭
 * - **点按钮** ⇒ 立刻展示并关掉（与在界面里点行一个效果）
 * - **收起**：按一下模式的「再按 X / Esc / 点框外」、按住模式的松手 / 回包收尾，
 *   都会把三个框**向上收起**（[CLOSE_MS]）再真正关界面，不再瞬消（2026-10-18 用户要求）
 * - ⚠ **三个框完全落下之前，悬停 / 点击 / 松手选择一律不响应**（2026-10-18 用户要求）：
 *   框还在往下掉时鼠标可能正好在 ID 卡框的**目标位置**上，松手会误触发展示。
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
    enum class Kind { POKEMON, IDCARD, ITEM }

    /** 掉落动画起点（构造时记；resize 重建不重置） */
    private var animStart = System.currentTimeMillis()

    // ── 收起动画（2026-10-18）──
    /** 是否正在收起：收起期间不响应悬停 / 点击 / 松手选择，播完才真正关界面 */
    private var closing = false
    private var closeStart = 0L
    /** 收起起点（相对目标位的偏移）：从当前落下位置开始收，中途关也不会跳一下 */
    private var closeFromOffset = 0
    /** 真正关界面的任务是否已排队（防止收完后每帧重复 setScreen） */
    private var closeQueued = false

    /**
     * 鼠标**当前停在哪个按钮上**（null = 没停在任何按钮上）。
     *
     * tick 检测到松键时会读它：有值就展示那个，没值就什么都不做。
     * 每帧在 [render] 里更新（与上浮用的是同一份判定，不会出现"看着浮起来了、松手却没反应"）。
     */
    var hoveredKind: Kind? = null
        private set

    private var pokemonBtn: NineSliceButton? = null
    private var idCardBtn: NineSliceButton? = null
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
        // 问一次 ID 卡背景图：服主换图后，这里的 ID 卡框要跟着变（没变只回版本号、不重传图）
        com.shusheng.cobblemarket.client.IdCardBackgroundTexture.requestRefresh()
        // 三个框作为**一整组**在屏幕上居中（2026-10-17 用户要求）：上排精灵/物品、下排 ID 卡
        baseY = (height - (BTN * 2 + GAP)) / 2
        val leftX = width / 2 - BTN - GAP / 2
        val rightX = width / 2 + GAP / 2

        // ⚠ 按钮**只留背景**（不给 iconFrames / iconLeft）：内容由 render 自己画 —— 3D 精灵与
        //   物品图都必须画在 super.render 之后、且要先 flush 清深度，交给按钮画反而做不到
        // ⚠ **刻意不挂悬停词条**（用户 2026-10-06 报「多余」）：框里画的就是那只精灵 / 那件物品本身，
        //   再写「精灵」「物品」纯属废话（他还专门截图说明过）。⚠ 入口界面那两枚 22×22 的**小图标**按钮
        //   仍挂着同样的词条 —— 那儿的图标不自解释，**两处别混**
        // ⚠ 贴图用**弹窗背景** `dialog_background.png`（用户 2026-10-07 定）—— 它只有**一段**
        //   （40×40），所以悬停时不会再换一张图（`NineSliceButton` 已把悬停态钳到 maxState，
        //   见那里的注释）；"悬停上浮"的反馈仍在，由 [LIFT] 那套位移负责。
        //   ⚠ 尺寸 / 掉落动画 / 上浮幅度一概未动。
        pokemonBtn = NineSliceButton(
            leftX, baseY, BTN, BTN, Text.literal(""), { doShowcase(Kind.POKEMON) },
            texture = DIALOG_BACKGROUND_TEXTURE, texH = DIALOG_BACKGROUND_TEX_H
        ).also { addDrawableChild(it) }
        itemBtn = NineSliceButton(
            rightX, baseY, BTN, BTN, Text.literal(""), { doShowcase(Kind.ITEM) },
            texture = DIALOG_BACKGROUND_TEXTURE, texH = DIALOG_BACKGROUND_TEX_H
        ).also { addDrawableChild(it) }
        // ID 卡框：**下方中间**（2026-10-17 用户要求；原来两个框的位置不动）
        idCardBtn = NineSliceButton(
            width / 2 - BTN / 2, baseY + BTN + GAP, BTN, BTN, Text.literal(""), { doShowcase(Kind.IDCARD) },
            texture = DIALOG_BACKGROUND_TEXTURE, texH = DIALOG_BACKGROUND_TEX_H
        ).also { addDrawableChild(it) }
    }

    /** 不画暗罩：按住期间世界照常可见（照其余模组界面） */
    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) = Unit

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // ⚠ 必须在最开头：头部跟随靠它拿鼠标坐标，不调则精灵盯着屏幕左上角不动
        IconRegistry.beginFrame(mouseX, mouseY)

        // ── 收起动画：三个框向上退场，播完才真正关界面 ──
        if (closing) {
            val t = ((System.currentTimeMillis() - closeStart) / CLOSE_MS.toFloat()).coerceIn(0f, 1f)
            if (t >= 1f) {
                // 最后一帧不画：框已经全部退到屏幕外，排队关界面。
                // ⚠ **不能在 render 里直接 setScreen(null)**（2026-10-18 用户实测）：
                //   界面在渲染途中关闭，光标锁定状态不会按正常路径恢复 —— 回到游戏后
                //   "视角只能转到屏幕边界"，按 E 开关一次背包才恢复。交给主线程下一 tick 执行。
                if (!closeQueued) {
                    closeQueued = true
                    // ⚠ 用 send（只入队）而**不是 execute**：execute 在客户端线程上会**立即执行**
                    //   （ThreadExecutor.shouldExecuteAsync 只在非本线程时为 true）⇒ 等于还在 render 里。
                    val mc = client
                    val self = this
                    if (mc != null) {
                        mc.send(Runnable {
                            // 只关「当前还是这个界面」的情况：排队到下一 tick 的窗口里
                            // 若已经切到别的界面（E 关闭 / 其它入口），别把新界面误关掉
                            if (mc.currentScreen === self) mc.setScreen(null)
                        })
                    }
                }
                return
            }
            val eased = t * t // 收起用 ease-in：先慢后快，像被"吸"上去
            // 目标偏移：让最下面的 ID 卡框底边也完全离开屏幕顶（再留 8px 余量）
            val closeTarget = -(baseY + BTN + GAP + BTN + 8)
            val offset = (closeFromOffset + (closeTarget - closeFromOffset) * eased).toInt()
            val pokemonY = baseY + offset
            val itemY = baseY + offset
            val idCardY = baseY + BTN + GAP + offset
            pokemonBtn?.y = pokemonY
            idCardBtn?.y = idCardY
            itemBtn?.y = itemY
            super.render(context, mouseX, mouseY, delta)
            drawPokemonContent(context, width / 2 - BTN - GAP / 2, pokemonY, delta)
            drawIdCardContent(context, width / 2 - BTN / 2, idCardY)
            drawItemContent(context, width / 2 + GAP / 2, itemY)
            return
        }

        // 掉落 + 悬停上浮都是**直接改按钮的 y**（瞬间跳，不做缓动）
        val drop = dropOffsetY()
        val dropDone = dropFinished()
        // ⚠ 落下动画结束前**不参与悬停/选择**（2026-10-18 用户要求）：
        //   框还在往下掉时鼠标可能正好在 ID 卡框的**目标位置**上，松手会误触发展示。
        //   ⇒ 落下期间 hoveredKind = null、不显示上浮；三个按钮也临时 active=false
        //   （挡住点击与点击音效，落下后再恢复）。
        val hovered = if (dropDone) hoveredAt(mouseX, mouseY, drop) else null
        hoveredKind = hovered
        pokemonBtn?.active = dropDone
        idCardBtn?.active = dropDone
        itemBtn?.active = dropDone
        val pokemonY = baseY + drop - (if (hovered == Kind.POKEMON) LIFT else 0)
        val itemY = baseY + drop - (if (hovered == Kind.ITEM) LIFT else 0)
        // ID 卡框在**下方中间**（目标位 = baseY 再下移一格）
        val idCardY = baseY + BTN + GAP + drop - (if (hovered == Kind.IDCARD) LIFT else 0)
        pokemonBtn?.y = pokemonY
        idCardBtn?.y = idCardY
        itemBtn?.y = itemY

        // 先让按钮把自己的背景画出来（贴图在下），内容再压上去
        super.render(context, mouseX, mouseY, delta)

        drawPokemonContent(context, width / 2 - BTN - GAP / 2, pokemonY, delta)
        drawIdCardContent(context, width / 2 - BTN / 2, idCardY)
        drawItemContent(context, width / 2 + GAP / 2, itemY)
    }

    // ── 三个框的内容 ──

    /**
     * ID 卡框的内容 = **ID 卡图标**（与入口底部那颗 ID 卡按钮同源：当前生效的 ID 卡背景图，
     * 没配 = 内置那张）。按**宽度**缩到框内、上下留 INSET 边距。
     */
    private fun drawIdCardContent(context: DrawContext, x: Int, y: Int) {
        val entry = com.shusheng.cobblemarket.client.IdCardBackgroundTexture.get()
        val tex = entry?.id ?: com.shusheng.cobblemarket.client.IdCardBackgroundTexture.BUILTIN_TEX
        val texW = entry?.width ?: com.shusheng.cobblemarket.client.IdCardBackgroundTexture.BUILTIN_WIDTH
        val texH = entry?.height ?: com.shusheng.cobblemarket.client.IdCardBackgroundTexture.BUILTIN_HEIGHT
        val scale = (BTN - INSET * 2).toFloat() / texW
        val drawW = texW * scale
        val drawH = texH * scale
        val cx = x + (BTN - drawW) / 2f
        val cy = y + (BTN - drawH) / 2f
        context.matrices.push()
        context.matrices.translate(cx.toDouble(), cy.toDouble(), 0.0)
        context.matrices.scale(scale, scale, 1f)
        context.drawTexture(tex, 0, 0, 0f, 0f, texW, texH, texW, texH)
        context.matrices.pop()
    }

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
        fun hit(x: Int, y: Int): Boolean =
            mouseX >= x && mouseX < x + BTN && mouseY >= y && mouseY < y + BTN
        val y = baseY + drop
        return when {
            hit(width / 2 - BTN - GAP / 2, y) -> Kind.POKEMON
            // ID 卡：**下方中间**（2026-10-17 用户要求；原来两个框的位置不动）
            hit(width / 2 - BTN / 2, y + BTN + GAP) -> Kind.IDCARD
            hit(width / 2 + GAP / 2, y) -> Kind.ITEM
            else -> null
        }
    }

    /** 从屏幕上方落到目标位的偏移（ease-out：像"落地"那样收尾）。动画只播一次 */
    private fun dropOffsetY(): Int {
        val t = ((System.currentTimeMillis() - animStart) / DROP_MS.toFloat()).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t)
        return -((1f - eased) * (baseY + BTN)).toInt()
    }

    /** 三个框是否已完全落到位（落下期间不响应悬停 / 点击 / 松手选择） */
    private fun dropFinished(): Boolean = System.currentTimeMillis() - animStart >= DROP_MS

    /**
     * 请求关闭：三个框**向上收起**（[CLOSE_MS]），动画结束才真正关界面。
     * 按一下模式的「再按 X / Esc / 点框外」、按住模式的松手 / 回包收尾都走这里。
     * 收起期间不响应悬停 / 点击 / 松手选择（同落下动画）。
     */
    fun requestClose() {
        if (closing) return
        closing = true
        closeStart = System.currentTimeMillis()
        closeFromOffset = dropOffsetY()
        hoveredKind = null
        cryAt = 0L
        pokemonBtn?.active = false
        idCardBtn?.active = false
        itemBtn?.active = false
    }

    /**
     * **按住模式**松键时 tick 调它：悬停哪个就展示哪个；没悬停 / **落下动画还没结束** ⇒ 什么都不做，只关界面。
     * （按一下模式不会调它 —— 松键不关，留给点击选择。）
     */
    fun finishAndClose() {
        if (closing) return
        val kind = if (dropFinished()) hoveredKind else null
        if (kind == null) closeSelf() else doShowcase(kind)
    }

    /** 展示 [kind] 然后关界面。取不到东西 ⇒ 失败音 + 聊天红字（用户 2026-09-29 定） */
    private fun doShowcase(kind: Kind) {
        // 落下动画结束前不响应（按钮 click 也会走到这里；render 里 active=false 是第一道，
        // 这里是第二道，防止别的入口绕过）；收起中也不再接受新的展示
        if (closing || !dropFinished()) return
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

            // ID 卡：发完就完事（卡一直存在，没有"取不到"的情况）
            Kind.IDCARD -> sendToServer(SharePayload(ShareNetwork.KIND_IDCARD, ""))
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
                // ➕ 带客户端视角物品 NBT：Polymer 伪装物品的真实物品客户端重建为空（没图标 / 没悬停窗）
                val nbt = runCatching {
                    MinecraftClient.getInstance().world?.registryManager?.let { rm ->
                        player.mainHandStack.encode(rm, net.minecraft.nbt.NbtCompound()) as? net.minecraft.nbt.NbtCompound
                    }
                }.getOrNull()
                sendToServer(SharePayload(ShareNetwork.KIND_ITEM, player.inventory.selectedSlot.toString(), nbt))
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
        requestClose()
    }

    /**
     * 吞掉方向键。
     *
     * 这个界面上没有任何"用键盘操作"的东西，方向键只会被 MC 当成**界面焦点导航** ——
     * 焦点跑到哪个按钮上就把它画成选中态。
     *
     * ⚠ **不把方向键转发给 Cobblemon**（用户 2026-09-29 定）：按住 X 期间不给模组加"换精灵"
     *   的负担 —— 玩家松开 X 自己切就好。所以这里就是单纯吞掉。
     * ⚠ 2026-10-06 起**"焦点导航会弹 tooltip"那层理由没有了**（这两个按钮的悬停词条已按用户要求
     *   去掉），但"焦点会改外观"与"不转发"两条依然成立 ⇒ 继续吞掉。
     */
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN ||
            keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT
        ) {
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    /** 按一下模式：点框外 = 取消（按住模式点外面不关，松手才是确认） */
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (closing) return true
        val handled = super.mouseClicked(mouseX, mouseY, button)
        if (!handled && ClientConfig.quickShowcasePressOnce) {
            requestClose()
            return true
        }
        return handled
    }

    /** Esc（`Screen.close()`）：同样走收起动画，别瞬消 */
    override fun close() {
        requestClose()
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
        /** 收起时长（ms）：三个框向上退场，播完才真正关界面 */
        private const val CLOSE_MS = 180L
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
