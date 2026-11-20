package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.client.ClientConfig
import com.shusheng.cobblemarket.client.ClientPermissionCache
import com.shusheng.cobblemarket.client.CobbleMarketClient
import com.shusheng.cobblemarket.client.IconAnimMode
import com.shusheng.cobblemarket.client.LoadState
import com.shusheng.cobblemarket.client.PhotoCapture
import com.shusheng.cobblemarket.client.PlayerSkinCache
import com.shusheng.cobblemarket.client.QuickCommandBind
import com.shusheng.cobblemarket.client.drawItemWithBar
import com.shusheng.cobblemarket.client.drawMarkCountBadge
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.shusheng.cobblemarket.client.icon.PreviewStateHolder
import com.shusheng.cobblemarket.client.playButtonClickSound
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.ClearPeekMarkPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PeekTargetEntry
import com.shusheng.cobblemarket.network.PeekTargetsPayload
import com.shusheng.cobblemarket.network.RequestPeekTargetsPayload
import com.shusheng.cobblemarket.network.SetPeekMarkPayload
import com.shusheng.cobblemarket.network.ShareNetwork
import com.shusheng.cobblemarket.network.SharePayload
import com.shusheng.cobblemarket.network.StartPeekPayload
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
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
 *
 * ## 快捷指令按钮（2026-11-20 用户要求）
 *
 * ID 卡框左右两侧各有一组 **2×2、共 8 个 32×32 小按钮**（随 ID 卡一起落下 / 收起；
 * 悬停时与三个大框一样**瞬间上浮** —— 2026-11-20 用户要求；落下完成前不可点，与三个大框同口径）：
 * - **未绑定**：显示加号贴图 `quick_command_add.png`，**左键**打开绑定弹窗
 * - **已绑定**：显示与三个大按钮同款的弹窗背景 + 短名，**左键 = 直接发送指令**，
 *   **右键 = 打开编辑弹窗**（指令改为留空并确认 = 清除该槽）
 * - 发送走 `networkHandler.sendCommand`，与在聊天框输入指令按回车**完全同一条路** ——
 *   权限仍由服务端判定，模组不放行任何东西；也**不占用展示冷却**（快捷指令不算展示，
 *   2026-11-20 用户明确）
 * - 绑定数据存**纯客户端** [ClientConfig.quickCommands]（8 槽），服务端零改动
 * - 绑定弹窗复用「标记可疑玩家」那套样式（标题 + 输入框 + 确定/取消），
 *   弹窗期间松手不关界面（见 [isModalSessionActive]）
 *
 * ## 相机按钮（功能2，2026-11-20）
 *
 * 精灵 / 物品两个大框**中缝的正上方**（水平居中）放一枚相机按钮，**与快捷指令按钮同大（32×32）**
 * （用户 2026-11-20 要求；1080p/S3 档三框距屏顶 40px，32 + 间隙 2 + 上浮 6 = 40，正好放下）。
 * 点击 = 进入**取景界面**（[com.shusheng.cobblemarket.screen.PhotoCaptureScreen]）：
 * 屏幕四角显示取景角标，**左键拍照、右键退出**；拍照时先关掉取景界面、再等两 tick 截图 ——
 * 照片里不含任何模组界面。照片算「展示」，受展示开关与冷却约束。
 */
class QuickShowcaseScreen(
    /**
     * 打开时**跳过下落 / 面板滑入动画**（三框直接出现在目标位）。
     * 从拍照确认界面取消 / 发送成功返回时用 —— 与标记 / 绑定弹窗关闭后「三框原地恢复」的观感一致，
     * 不重播一遍掉落（2026-11-20 用户要求取消逻辑对齐）。
     */
    private val instantDrop: Boolean = false,
) : Screen(Text.literal("")) {

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

    // ── 右侧「在线玩家」面板（仅 overview 节点 / 服主；2026-11-10）──
    /** 服务端下发的在线玩家（除自己外，按名字排序） */
    private var peekTargets = listOf<PeekTargetEntry>()
    /** 首包是否已到 —— 区分「加载中」与「真的没有其他人」 */
    private val peekTargetsLoadState = LoadState()
    /** 名单滚动偏移（行） */
    private var peekTargetScroll = 0
    /** 可拖动的滚动条（本界面只有这一条，独立实例即可） */
    private val peekTargetScrollBar = ScrollBar()
    /** 右键选中的行（头像位置变成「可疑」按钮）；null = 没有 */
    private var markModeUuid: UUID? = null
    /**
     * 提交中的标记操作（目标 UUID / 期望结果 / 过期时刻）——
     * 服务端处理完会推回新名单，`onPeekTargets` 里核对状态一致后播成功音效
     * （失败/限流时不会变，超时丢弃，避免永久挂起）。
     */
    private var pendingMarkResult: Triple<UUID, Boolean, Long>? = null
    /** 理由输入弹窗的目标；null = 弹窗关闭 */
    private var markPromptTarget: PeekTargetEntry? = null
    private var markReasonField: TextFieldWidget? = null
    private val peekMarkIcon = Identifier.of("cobblemarket", "textures/gui/icon/peek_mark.png")
    private val markTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
    /** 上一次请求名单的时刻（打开期间每 2 秒刷新） */
    private var lastTargetsRequestAt = 0L
    /** 关闭动画开始那一刻面板的 x —— 滑入到一半关闭时从当前位置往右滑出，不跳帧 */
    private var closeFromPanelX = 0

    // ── 快捷指令按钮（2026-11-20）──
    /** 绑定/编辑弹窗对应的槽位；null = 弹窗关闭 */
    private var quickBindSlot: Int? = null
    private var quickBindNameField: TextFieldWidget? = null
    private var quickBindCommandField: TextFieldWidget? = null
    /** 未绑定槽位的加号贴图（48×48，缩到按钮大小） */
    private val quickCommandAddIcon =
        Identifier.of("cobblemarket", "textures/gui/icon/quick_command_add.png")

    // ── 相机按钮（功能2，2026-11-20）──
    /** 相机图标（48×48，缩到按钮内） */
    private val cameraIcon =
        Identifier.of("cobblemarket", "textures/gui/icon/camera.png")

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
        if (instantDrop) {
            // 让 dropFinished / 面板滑入判定立即成立（把动画起点挪到两个动画时长之前）
            animStart = System.currentTimeMillis() - (DROP_MS + PANEL_SLIDE_MS + 100L)
        }
        // 问一次 ID 卡背景图：服主换图后，这里的 ID 卡框要跟着变（没变只回版本号、不重传图）
        com.shusheng.cobblemarket.client.IdCardBackgroundTexture.requestRefresh()
        // 在线玩家面板（仅 overview 节点 / 服主）：进界面拉一次名单；没权限不请求、面板也不画
        if (canSeePeekPanel()) {
            lastTargetsRequestAt = System.currentTimeMillis()
            sendToServer(RequestPeekTargetsPayload())
        }
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

        // 可疑标记的理由输入弹窗：独占渲染。遮罩 / 弹窗背景必须画在控件之前，
        // 输入框由 `super.render` 画；三个大框临时隐藏，避免从遮罩上透出来。
        if (markPromptTarget != null) {
            renderMarkPrompt(context, mouseX, mouseY, delta)
            return
        }

        // 快捷指令绑定弹窗：同样独占渲染（遮罩 / 弹窗背景在控件之前，三个大框与 8 个小按钮都不画）
        if (quickBindSlot != null) {
            renderQuickBindPrompt(context, mouseX, mouseY, delta)
            return
        }

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
            // 快捷指令按钮与 ID 卡同一组，跟它一起上收（不响应悬停 / 点击）
            drawQuickCommandButtons(
                context, mouseX, mouseY, baseY + BTN + GAP + offset, interactive = false
            )
            // 相机按钮同理：跟三框组一起上收
            drawCameraButton(
                context, mouseX, mouseY, baseY + BTN + GAP + offset, interactive = false
            )
            // 右侧面板**不跟着上收**：从当前位置向右滑出（与入场方向相反）
            if (canSeePeekPanel()) {
                val panelX = (closeFromPanelX + (width + 8 - closeFromPanelX) * eased).toInt()
                renderPeekPanel(context, panelX, mouseX, mouseY, interactive = false)
            }
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

        // 快捷指令按钮：ID 卡左右两侧各 2×2。⚠ 基准位用**未上浮**的 ID 卡目标位 ——
        //   按钮不参与大框的悬停上浮（跟着上浮的话，鼠标移向按钮时 ID 卡回落会让按钮跳 10px、命中跟着抖）
        drawQuickCommandButtons(
            context, mouseX, mouseY, baseY + BTN + GAP + drop, interactive = dropDone
        )
        // 相机按钮：精灵 / 物品两框中缝正上方，随三框组落下 / 收起
        drawCameraButton(
            context, mouseX, mouseY, baseY + BTN + GAP + drop, interactive = dropDone
        )

        // 右侧在线玩家面板：从屏幕右缘滑入（不参与三个大框的下落 / 上浮）
        if (canSeePeekPanel()) {
            renderPeekPanel(
                context, peekPanelSlideX(), mouseX, mouseY,
                interactive = peekPanelSlideFinished()
            )
        }

        // 面板行悬停：显示这名玩家所在的维度（2026-11-14 用户要求）。
        // 必须画在面板之后（`drawTooltip` 在鼠标旁，面板是自绘的，会被后画的盖住）。
        if (canSeePeekPanel() && peekPanelSlideFinished()) {
            peekTargetAt(peekPanelSlideX(), mouseX.toDouble(), mouseY.toDouble())?.let { entry ->
                drawDimensionTooltip(context, entry, mouseX, mouseY)
            }
        }

        // 快捷指令按钮的悬停窗：完整指令 + 操作提示（落下完成前不出，同三框口径）
        if (dropDone) {
            quickSlotAt(mouseX.toDouble(), mouseY.toDouble())?.let { slot ->
                drawQuickCommandTooltip(context, slot, mouseX, mouseY)
            }
            // 相机按钮的悬停窗
            if (cameraButtonAt(mouseX.toDouble(), mouseY.toDouble())) {
                drawCameraTooltip(context, mouseX, mouseY)
            }
        }
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

    // ── 快捷指令按钮（2026-11-20）──

    /**
     * 单侧整块的左缘：**块与 ID 卡之间的 14px 间隙固定**，块往外侧生长（外侧是空白区）。
     * ⚠ 不要改成"在 ID 卡与上排按钮之间的 76px 里居中" —— 按钮放大后那样会挤压内侧间隙。
     */
    private fun quickBlockX(slot: Int): Int =
        if (slot < 4) width / 2 - BTN / 2 - QUICK_INSET - QUICK_BLOCK
        else width / 2 + BTN / 2 + QUICK_INSET

    /**
     * 某个槽位的左上角。[idCardBaseY] = **未上浮**的 ID 卡框 y（含落下 / 收起偏移）；
     * 整块在 ID 卡框里竖直居中。
     */
    private fun quickSlotOrigin(slot: Int, idCardBaseY: Int): Pair<Int, Int> {
        val index = slot % 4
        val col = index % QUICK_COLS
        val row = index / QUICK_COLS
        val blockY = idCardBaseY + (BTN - QUICK_BLOCK) / 2
        return (quickBlockX(slot) + col * (QUICK_BTN + QUICK_GAP)) to
            (blockY + row * (QUICK_BTN + QUICK_GAP))
    }

    /**
     * 鼠标下的快捷指令槽位；null = 不在任何槽位内（含正在收起时）。
     *
     * 判定分两层（悬停上浮 [QUICK_LIFT] 带来的需要）：
     * ① **基础区**（未上浮的位置）优先；
     * ② 再判**上浮后露出的顶部条**（`y − QUICK_LIFT .. y`）—— 否则悬停在按钮最上面几像素时
     *   按钮上浮、鼠标却落在判定区外，表现为「看着悬停、点了没反应」。
     * [QUICK_LIFT] 小于行距 [QUICK_GAP]，两行按钮的上浮区不会重叠（同侧上下只留 2px）。
     *
     * ⚠ 点击与 tooltip 都用这一个入口 —— 与渲染里「给悬停的那个减少 QUICK_LIFT」是同一份判定，不会错位。
     */
    private fun quickSlotAt(mouseX: Double, mouseY: Double): Int? {
        if (closing) return null
        val idCardBaseY = baseY + BTN + GAP + dropOffsetY()
        for (slot in 0 until ClientConfig.QUICK_COMMAND_SLOTS) {
            val (x, y) = quickSlotOrigin(slot, idCardBaseY)
            if (mouseX >= x && mouseX < x + QUICK_BTN && mouseY >= y && mouseY < y + QUICK_BTN) {
                return slot
            }
        }
        var best: Int? = null
        var bestDy = Double.MAX_VALUE
        for (slot in 0 until ClientConfig.QUICK_COMMAND_SLOTS) {
            val (x, y) = quickSlotOrigin(slot, idCardBaseY)
            if (mouseX >= x && mouseX < x + QUICK_BTN && mouseY >= y - QUICK_LIFT && mouseY < y) {
                val dy = kotlin.math.abs(mouseY - (y + QUICK_BTN / 2.0))
                if (dy < bestDy) {
                    bestDy = dy
                    best = slot
                }
            }
        }
        return best
    }

    /**
     * 画 8 个快捷指令按钮：未绑定 = 加号贴图，已绑定 = **与三个大按钮同款**的弹窗背景 + 短名。
     * 悬停的那个**瞬间上浮** [QUICK_LIFT] px —— 与三个大框同一效果（无缓动），2026-11-20 用户要求。
     */
    private fun drawQuickCommandButtons(
        context: DrawContext,
        mouseX: Int,
        mouseY: Int,
        idCardBaseY: Int,
        interactive: Boolean,
    ) {
        // 悬停判定与点击 / tooltip 共用 [quickSlotAt]（每帧只算一次）：上浮后按钮顶部仍要点得到
        val hoveredSlot = if (interactive) quickSlotAt(mouseX.toDouble(), mouseY.toDouble()) else null
        for (slot in 0 until ClientConfig.QUICK_COMMAND_SLOTS) {
            val (x, originY) = quickSlotOrigin(slot, idCardBaseY)
            val hovered = hoveredSlot == slot
            val y = if (hovered) originY - QUICK_LIFT else originY
            val bind = ClientConfig.quickCommands.getOrNull(slot)
            if (bind == null) {
                // 未绑定：**整张 48×48 加号图**缩到按钮大小（图自带圆角边框与底色），
                // 不再垫项目按钮底 —— 两层边框会叠在一起（用户 2026-11-20 给的图本身就是按钮外观）
                val scale = QUICK_BTN / QUICK_ADD_TEX.toFloat()
                context.matrices.push()
                context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
                context.matrices.scale(scale, scale, 1f)
                context.drawTexture(
                    quickCommandAddIcon, 0, 0, 0f, 0f,
                    QUICK_ADD_TEX, QUICK_ADD_TEX, QUICK_ADD_TEX, QUICK_ADD_TEX
                )
                context.matrices.pop()
            } else {
                // 已绑定：底色与**三个大按钮**同款（dialog_background，2026-11-20 用户要求）。
                // ⚠ 这张贴图只有**一段** —— 悬停不换图（与三框一致），反馈靠上面的瞬间上浮。
                drawNineSlice(
                    context, DIALOG_BACKGROUND_TEXTURE, x, y, QUICK_BTN, QUICK_BTN,
                    0, DIALOG_BACKGROUND_TEX_H
                )
                // 32px 宽去掉内边距约 28px：中文 3 字 / 英文 4~5 字母，超宽截断；
                // 完整指令看悬停窗
                val label = TextUtil.truncateString(bind.displayLabel(), QUICK_BTN - 4)
                context.drawCenteredTextWithShadow(
                    textRenderer, Text.literal(label),
                    x + QUICK_BTN / 2, y + (QUICK_BTN - 8) / 2, 0xFFFFFF
                )
            }
        }
    }

    /**
     * 左键快捷指令按钮：空槽 ⇒ 打开绑定弹窗；已绑定 ⇒ 发送指令并收起界面。
     *
     * ⚠ 发送走 `networkHandler.sendChatCommand` —— **就是原版聊天框 `ChatScreen.sendMessage`
     *   输入 `/` 指令时调用的那个方法**：权限、参数与命令存在性全由服务端判定，模组不做任何放行。
     *   ⚠ 别用 `sendCommand`：它对「需要签名参数」的命令（1.21 的 `/msg` / `/tell` 这类
     *   聊天消息参数）**静默不发包**（返回 false），而 `sendChatCommand` 会走带签名的
     *   `ChatCommandSignedC2SPacket` 正常发出；普通命令两者发的是同一个
     *   `CommandExecutionC2SPacket`，没有差别（2026-11-20 用户发布前确认）。
     *   也**不经过展示通道**，所以**不占展示冷却**（2026-11-20 用户明确：快捷指令不算展示）。
     * ⚠ 发送后**不等回包**（原版命令没有模组回包），直接走收起动画回游戏。
     */
    private fun useQuickCommand(slot: Int) {
        val bind = ClientConfig.quickCommands.getOrNull(slot)
        if (bind == null || bind.command.isBlank()) {
            openQuickBind(slot)
            return
        }
        playButtonClickSound()
        client?.player?.networkHandler?.sendChatCommand(bind.command)
        closeSelf()
    }

    /**
     * 打开绑定 / 编辑弹窗（左键空槽 / 右键任意槽）。样式复用「标记可疑玩家」那一窗。
     * 预填该槽当前内容；**指令留空并确认 = 清除该槽**。
     */
    private fun openQuickBind(slot: Int) {
        if (quickBindSlot != null) return
        playButtonClickSound()
        quickBindSlot = slot
        val existing = ClientConfig.quickCommands.getOrNull(slot)
        val dx = quickBindDialogX()
        val dy = quickBindDialogY()
        val labelW = quickBindLabelWidth()
        val fieldX = dx + 10 + labelW + 4
        val fieldW = (dx + QUICK_BIND_DIALOG_W - 10 - fieldX).coerceAtLeast(40)

        val nameField = TextFieldWidget(textRenderer, fieldX, dy + 24, fieldW, 16, Text.literal(""))
        nameField.setMaxLength(8)
        nameField.text = existing?.name.orEmpty()
        nameField.setPlaceholder(Text.translatable("cobblemarket.quick_command.name_hint"))

        val commandField = TextFieldWidget(textRenderer, fieldX, dy + 46, fieldW, 16, Text.literal(""))
        commandField.setMaxLength(256)
        commandField.text = existing?.command.orEmpty()
        commandField.setPlaceholder(Text.translatable("cobblemarket.quick_command.command_hint"))

        quickBindNameField = nameField
        quickBindCommandField = commandField
        addDrawableChild(nameField)
        addDrawableChild(commandField)
        // ⚠ **打开时不默认聚焦任何输入框**（2026-11-20 用户要求）：弹窗刚出现时不该已经是
        //   「正在输入」状态（光标闪在框里），点了哪个框才开始输入。
        //   ⚠ 之后点输入框时仍需要同时把焦点交给 widget 与 Screen（标记弹窗踩过：
        //   只设 widget 的话按键分发不到、打不了字）—— 那条路走 super.mouseClicked 的默认处理。
        setFocused(null)
        // 弹窗期间隐藏三个大框（8 个小按钮是自绘的，render 的独占分支里不会画）
        pokemonBtn?.visible = false
        idCardBtn?.visible = false
        itemBtn?.visible = false
    }

    private fun closeQuickBind() {
        quickBindNameField?.let { remove(it) }
        quickBindCommandField?.let { remove(it) }
        quickBindNameField = null
        quickBindCommandField = null
        // 焦点交还界面（否则移除掉的输入框仍占着 Screen 焦点）
        setFocused(null)
        quickBindSlot = null
        pokemonBtn?.visible = true
        idCardBtn?.visible = true
        itemBtn?.visible = true
    }

    /**
     * 确认绑定：指令去掉首尾空白与前导 `/`；**指令留空 = 清除该槽**（按钮回到加号）。
     * 短名只影响按钮显示，留空时按钮显示指令首词（见 [QuickCommandBind.displayLabel]）。
     */
    private fun submitQuickBind() {
        val slot = quickBindSlot ?: return
        val command = quickBindCommandField?.text.orEmpty().trim().removePrefix("/").trim()
        val name = quickBindNameField?.text.orEmpty().trim()
        // 成功音（用户 2026-11-20 要求）：确定绑定要有反馈 —— 只播点击音听不出「保存成功了」。
        // 清除绑定（指令留空）同样算操作成功，一并播。
        CobbleMarketClient.playResultSound(true)
        ClientConfig.setQuickCommand(
            slot, if (command.isEmpty()) null else QuickCommandBind(name, command)
        )
        closeQuickBind()
    }

    /** 绑定弹窗：遮罩 + 弹窗背景 + 标题 + 两行标签 + 输入框（由 super.render 画）+ 底部按钮 */
    private fun renderQuickBindPrompt(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0x80000000.toInt())
        val dx = quickBindDialogX()
        val dy = quickBindDialogY()
        drawNineSlice(
            context, DIALOG_BACKGROUND_TEXTURE, dx, dy,
            QUICK_BIND_DIALOG_W, QUICK_BIND_DIALOG_H, 0, DIALOG_BACKGROUND_TEX_H
        )
        val editing = ClientConfig.quickCommands.getOrNull(quickBindSlot ?: 0) != null
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable(
                if (editing) "cobblemarket.quick_command.edit_title"
                else "cobblemarket.quick_command.bind_title"
            ).formatted(Formatting.GOLD),
            width / 2, dy + 8, 0xFFFFFF
        )
        // 标签列（两行共用同一列宽，见 [quickBindLabelWidth]）
        context.drawTextWithShadow(
            textRenderer, Text.translatable("cobblemarket.quick_command.name_label"),
            dx + 10, dy + 24 + 4, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer, Text.translatable("cobblemarket.quick_command.command_label"),
            dx + 10, dy + 46 + 4, 0xFFFFFF
        )
        // 输入框控件（两个 TextFieldWidget 在 openQuickBind 里 addDrawableChild）
        super.render(context, mouseX, mouseY, delta)
        val (confirmX, confirmY) = quickBindConfirmButtonOrigin()
        val (cancelX, cancelY) = quickBindCancelButtonOrigin()
        drawDialogButton(
            context, confirmX, confirmY,
            Text.translatable("cobblemarket.quick_command.confirm"), mouseX, mouseY
        )
        drawDialogButton(
            context, cancelX, cancelY,
            Text.translatable("cobblemarket.quick_command.cancel"), mouseX, mouseY
        )
    }

    /** 标签列宽：**两行取更宽的那条**（中英按各自语言量，别按中文观感定） */
    private fun quickBindLabelWidth(): Int = maxOf(
        textRenderer.getWidth(Text.translatable("cobblemarket.quick_command.name_label")),
        textRenderer.getWidth(Text.translatable("cobblemarket.quick_command.command_label"))
    )

    private fun quickBindDialogX(): Int = (width - QUICK_BIND_DIALOG_W) / 2
    private fun quickBindDialogY(): Int = (height - QUICK_BIND_DIALOG_H) / 2
    private fun quickBindConfirmButtonOrigin(): Pair<Int, Int> =
        (width / 2 - MARK_BTN_W - 4) to (quickBindDialogY() + QUICK_BIND_DIALOG_H - 10 - MARK_BTN_H)
    private fun quickBindCancelButtonOrigin(): Pair<Int, Int> =
        (width / 2 + 4) to (quickBindDialogY() + QUICK_BIND_DIALOG_H - 10 - MARK_BTN_H)

    /**
     * 快捷指令按钮的悬停窗：完整指令 + 操作提示；空槽只提示「点击绑定指令」。
     * 样式照面板行的悬停窗（九宫格 + 不透明底），画在鼠标左下并 clamp。
     */
    private fun drawQuickCommandTooltip(context: DrawContext, slot: Int, mouseX: Int, mouseY: Int) {
        val bind = ClientConfig.quickCommands.getOrNull(slot)
        val lines = mutableListOf<Pair<Text, Int>>()
        if (bind == null) {
            lines += Text.translatable("cobblemarket.quick_command.empty_hint") to 0xAAAAAA
        } else {
            // 指令可能很长：悬停窗里按宽度截断到 180px
            lines += Text.literal(TextUtil.truncateString("/" + bind.command, 180)) to 0xFFFFFF
            lines += Text.translatable("cobblemarket.quick_command.hint") to 0xAAAAAA
        }
        val padding = 4
        val lineH = textRenderer.fontHeight + 2
        val w = lines.maxOf { (text, _) -> textRenderer.getWidth(text) } + padding * 2
        val h = lines.size * lineH + padding * 2 - 2
        var x = mouseX - w
        var y = mouseY + 12
        if (x < 4) x = 4
        if (y + h > height - 4) y = mouseY - 12 - h
        if (y < 4) y = 4
        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        // 九宫格是半透明的，先垫一层不透明底（同 drawDimensionTooltip）
        context.fill(x, y, x + w, y + h, 0xF0101010.toInt())
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, x, y, w, h, 1, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, (text, color) ->
            context.drawTextWithShadow(textRenderer, text, x + padding, y + padding + i * lineH, color)
        }
        context.matrices.pop()
    }

    // ── 相机按钮（功能2，2026-11-20）──

    /**
     * 相机按钮的位置：**精灵 / 物品两框中缝的正上方**、三框组顶边之上 [CAMERA_GAP]。
     * [idCardBaseY] 是 ID 卡框 y（含落下 / 收起偏移）⇒ 三框组顶边 = idCardBaseY − [BTN] − [GAP]。
     * 小窗口下贴屏幕顶（[CAMERA_TOP_MARGIN]）。
     */
    private fun cameraButtonOrigin(idCardBaseY: Int): Pair<Int, Int> {
        val x = width / 2 - CAMERA_BTN / 2
        val rawY = idCardBaseY - BTN - GAP - CAMERA_GAP - CAMERA_BTN
        // 收起动画期间允许移出屏幕（与三框一起上收）；其余时候贴顶保底
        val y = if (closing) rawY else rawY.coerceAtLeast(CAMERA_TOP_MARGIN)
        return x to y
    }

    /** 鼠标是否在相机按钮上（含上浮露出的顶部带，与快捷指令按钮同一口径） */
    private fun cameraButtonAt(mouseX: Double, mouseY: Double): Boolean {
        if (closing) return false
        val (x, y) = cameraButtonOrigin(baseY + BTN + GAP + dropOffsetY())
        return mouseX >= x && mouseX < x + CAMERA_BTN &&
            mouseY >= y - QUICK_LIFT && mouseY < y + CAMERA_BTN
    }

    /** 相机按钮：与三框同款底色 + 相机图标；悬停瞬间上浮 [QUICK_LIFT] */
    private fun drawCameraButton(
        context: DrawContext,
        mouseX: Int,
        mouseY: Int,
        idCardBaseY: Int,
        interactive: Boolean,
    ) {
        val (x, originY) = cameraButtonOrigin(idCardBaseY)
        val hovered = interactive && cameraButtonAt(mouseX.toDouble(), mouseY.toDouble())
        val y = if (hovered) (originY - QUICK_LIFT).coerceAtLeast(0) else originY
        drawNineSlice(
            context, DIALOG_BACKGROUND_TEXTURE, x, y, CAMERA_BTN, CAMERA_BTN,
            0, DIALOG_BACKGROUND_TEX_H
        )
        // 图标 48 → 26，在按钮内居中（四周各留 3px）
        val iconSize = CAMERA_BTN - 6
        val scale = iconSize / CAMERA_TEX.toFloat()
        context.matrices.push()
        context.matrices.translate(
            x + (CAMERA_BTN - iconSize) / 2.0,
            y + (CAMERA_BTN - iconSize) / 2.0,
            0.0
        )
        context.matrices.scale(scale, scale, 1f)
        context.drawTexture(
            cameraIcon, 0, 0, 0f, 0f,
            CAMERA_TEX, CAMERA_TEX, CAMERA_TEX, CAMERA_TEX
        )
        context.matrices.pop()
    }

    /** 点击相机：收起快速功能界面 → 进入取景界面（左键拍照 / 右键退出，流程见 [PhotoCapture]） */
    private fun startPhotoCapture() {
        if (closing || !dropFinished()) return
        playButtonClickSound()
        PhotoCapture.armViewfinder()
        closeSelf()
    }

    /** 相机按钮悬停窗（单行；样式同快捷指令悬停窗） */
    private fun drawCameraTooltip(context: DrawContext, mouseX: Int, mouseY: Int) {
        val text = Text.translatable("cobblemarket.photo.camera_tip")
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

    // ── 右侧「在线玩家」面板（仅 overview 节点 / 服主；2026-11-10）──

    override fun tick() {
        super.tick()
        // 标记提交等回包超时兜底：万一名单回包一直没来，也要把理由框收掉，
        // 否则标记会话一直挂着、按住模式下界面永远收不起来（正常路径见 onPeekTargets）。
        pendingMarkResult?.let { (_, _, expiresAt) ->
            if (System.currentTimeMillis() > expiresAt) {
                pendingMarkResult = null
                if (markPromptTarget != null) closeMarkPrompt()
            }
        }
        if (closing || !canSeePeekPanel()) return
        // 打开期间每 2 秒刷新：有人上下线时面板跟着变（服务端对读请求有 500ms 限流）
        val now = System.currentTimeMillis()
        if (now - lastTargetsRequestAt >= TARGETS_REFRESH_MS) {
            lastTargetsRequestAt = now
            sendToServer(RequestPeekTargetsPayload())
        }
    }

    /** 服务端回包：在线玩家名单（快速展示右侧面板用） */
    fun onPeekTargets(payload: PeekTargetsPayload) {
        // 标记/取消操作的结果确认：名单里该玩家的选中态与期望一致 ⇒ 播成功音效
        pendingMarkResult?.let { (uuid, wantMarked, expiresAt) ->
            val updated = payload.entries.firstOrNull { it.uuid == uuid }
            if (updated != null && updated.markedByMe == wantMarked) {
                com.shusheng.cobblemarket.client.CobbleMarketClient.playResultSound(true)
                pendingMarkResult = null
                if (wantMarked) {
                    // 提交确认：关理由框 ⇒ 标记会话结束（按住模式下界面随后自动收起）
                    closeMarkPrompt()
                } else {
                    // 取消标记确认后才退出右键选中态：否则本地先恢复头像 + 常驻标记图标、
                    // 等服务端回包又删掉标记 ⇒ 视觉上闪一下（2026-11-17 用户实测反馈）
                    markModeUuid = null
                }
            } else if (System.currentTimeMillis() > expiresAt) {
                pendingMarkResult = null
                // 超时没等到确认：把理由框收掉，别让界面一直锁着
                if (markPromptTarget != null) closeMarkPrompt()
            }
        }
        peekTargets = payload.entries
        peekTargetsLoadState.markLoaded()
        peekTargetScroll = peekTargetScroll.coerceIn(0, maxOf(0, peekTargets.size - peekPanelRows()))
        // 名单换了 = 滚动条内容换了：把拖动状态一并复位（否则会留个"半拖"）
        peekTargetScrollBar.cancel()
        // 右键选中 / 输入弹窗对应的玩家如果下线了，一并清掉
        markModeUuid = markModeUuid?.takeIf { uuid -> peekTargets.any { it.uuid == uuid } }
        val prompt = markPromptTarget
        if (prompt != null) {
            val updated = peekTargets.firstOrNull { it.uuid == prompt.uuid }
            if (updated == null) closeMarkPrompt() else markPromptTarget = updated
        }
    }

    /**
     * 面板是否显示：与玩家总览行尾「监视」按钮**同一判据**（服主 / overview 节点，**不认 OP**）。
     * ⚠ 显隐只是入口，不是鉴权 —— 点击仍发 `StartPeekPayload`，服务端在 `PeekService.start` 再判。
     */
    private fun canSeePeekPanel(): Boolean =
        ClientPermissionCache.isServerOwner() || ClientPermissionCache.has(MarketPermissions.OVERVIEW)

    /**
     * 面板一屏放得下几行（标题区与底部留白之后）。
     *
     * 当前几何：(280 − 29 − 4) / 24 = **10 行**；10 × 24 = 240，最后一行底 = 面板顶 + 269，
     * 距面板底还有 11px —— **即使名单再长也只画 [peekPanelRows] 行，绝不会压出底边框**。
     * 行数变了这里的算式自动跟着走，别在渲染处另写一个数。
     */
    private fun peekPanelRows(): Int =
        ((PANEL_H - PANEL_HEADER_H - PANEL_BOTTOM_PAD) / PANEL_ROW_H).coerceAtLeast(1)

    /** 面板完全滑入后的 x */
    private fun peekPanelTargetX(): Int = width - PANEL_RIGHT_MARGIN - PANEL_W

    /** 面板当前 x：从屏幕右缘外滑入（ease-out，与三个大框的掉落同起点、同时长） */
    private fun peekPanelSlideX(): Int {
        val t = ((System.currentTimeMillis() - animStart) / PANEL_SLIDE_MS.toFloat()).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t)
        return (width + (peekPanelTargetX() - width) * eased).toInt()
    }

    /** 滑入是否结束 —— 结束前不响应点击 / 滚轮（与三个大框「落下完成前不响应」同一口径） */
    private fun peekPanelSlideFinished(): Boolean =
        System.currentTimeMillis() - animStart >= PANEL_SLIDE_MS

    private fun peekPanelContains(panelX: Int, mouseX: Double, mouseY: Double): Boolean =
        mouseX >= panelX && mouseX < panelX + PANEL_W &&
            mouseY >= baseY && mouseY < baseY + PANEL_H

    /** 鼠标位置对应的名单条目；null = 不在行内 / 那一行是空的 */
    private fun peekTargetAt(panelX: Int, mouseX: Double, mouseY: Double): PeekTargetEntry? {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        // 名字 / 头像区域；⚠ 右缘再内缩 10px 让开滚动条（它命中区左右各放宽 3px，压在行尾会打架）
        if (mx < panelX + 4 || mx >= panelX + PANEL_W - 10) return null
        val startY = baseY + PANEL_HEADER_H
        if (my < startY) return null
        val index = (my - startY) / PANEL_ROW_H
        if (index !in 0 until peekPanelRows()) return null
        return peekTargets.getOrNull(peekTargetScroll + index)
    }

    /**
     * 画右侧面板：九宫格底 + 标题两行（在线玩家 / 人数）+ 名单（名字左、皮肤头像右）
     * + 行顶分割线 + 可拖动滚动条；悬停行高亮（维度词条在 [render] 末尾画）。
     *
     * @param interactive 滑入完成后才接受悬停反馈 / 词条（滑入中鼠标在移动，反馈会闪）
     */
    private fun renderPeekPanel(
        context: DrawContext,
        panelX: Int,
        mouseX: Int,
        mouseY: Int,
        interactive: Boolean,
    ) {
        val panelY = baseY
        drawNineSlice(
            context, DIALOG_BACKGROUND_TEXTURE, panelX, panelY, PANEL_W, PANEL_H,
            0, DIALOG_BACKGROUND_TEX_H
        )
        // 标题两行（照喵喵银行持有者面板：卡名 + 计数）。
        // ⚠ 词条 / 数字都可能变长，与模板一样一律按面板内宽截断，别让文字顶出边框
        context.drawCenteredTextWithShadow(
            textRenderer,
            TextUtil.truncateText(
                Text.translatable("cobblemarket.quick_showcase.online_title"), PANEL_W - 12
            ),
            panelX + PANEL_W / 2, panelY + 7, 0xFFFFFF
        )
        context.drawCenteredTextWithShadow(
            textRenderer,
            TextUtil.truncateText(
                if (peekTargetsLoadState.everLoaded) {
                    Text.translatable("cobblemarket.quick_showcase.online_count", peekTargets.size)
                } else {
                    Text.translatable("cobblemarket.gui.loading")
                },
                PANEL_W - 12
            ),
            panelX + PANEL_W / 2, panelY + 18, 0xAAAAAA
        )

        val startY = panelY + PANEL_HEADER_H
        val rows = peekPanelRows()
        if (peekTargets.isEmpty()) {
            // 标题区与名单之间的分割线（空态也要有 —— 2026-11-10 用户截图要求）
            context.fill(panelX + 4, startY, panelX + PANEL_W - 4, startY + 1, 0xFF888888.toInt())
            // 首包前显示「加载中…」，到了才是真正的「没有其他在线玩家」。
            // ⚠ 空态**必须截断**：不会裁剪，英文比面板宽时两侧会直接露出边框
            //   （喵喵银行 2026-09-20 用户实测过；照模板用 truncateString + 面板内宽）
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal(
                    TextUtil.truncateString(
                        Text.translatable(
                            peekTargetsLoadState.emptyKey("cobblemarket.quick_showcase.online_empty")
                        ).string,
                        PANEL_W - 12
                    )
                ),
                panelX + PANEL_W / 2, startY + 8, 0xFFFFFF
            )
            return
        }

        peekTargets.drop(peekTargetScroll).take(rows).forEachIndexed { i, entry ->
            val y = startY + i * PANEL_ROW_H
            // 悬停区与点击区同源（右缘让开滚动条，见 [peekTargetAt]）。
            // 高亮画在这里；维度词条由 `render` 末尾统一画（2026-11-14 用户要求），
            // 不能在面板绘制过程中画 —— 后续行 / 分割线会盖住 tooltip。
            val hovered = interactive &&
                mouseX >= panelX + 4 && mouseX < panelX + PANEL_W - 10 &&
                mouseY >= y && mouseY < y + PANEL_ROW_H
            if (hovered) {
                context.fill(panelX + 4, y, panelX + PANEL_W - 4, y + PANEL_ROW_H, 0x33FFFFFF)
            }
            // 行顶分割线（照喵喵银行持有者面板；第一行不画 —— 标题区下方那条已经占了这个位置）
            if (i > 0) {
                context.fill(panelX + 4, y, panelX + PANEL_W - 4, y + 1, 0xFF888888.toInt())
            }
            // 名字：24 行高、8px 字 → y+8 垂直居中；右侧依次是标记位 + 头像
            context.drawTextWithShadow(
                textRenderer,
                Text.literal(truncateName(entry.name, PANEL_W - 52)),
                panelX + 6, y + 8, 0xFFFFFF
            )
            val avatarX = panelX + PANEL_W - 4 - PANEL_AVATAR - 4
            val markIconX = panelX + PANEL_W - 42
            val markIconSize = 12
            val markIconInset = (PANEL_AVATAR - markIconSize) / 2
            if (markModeUuid == entry.uuid) {
                // 右键后：头像位置变成「可疑」按钮。用项目统一按钮贴图
                // （普通 / 悬停 / 选中三段，选中态是按钮自带的蓝色），不要自创绿色叠层。
                val btnHovered = interactive &&
                    mouseX >= avatarX && mouseX < avatarX + PANEL_AVATAR &&
                    mouseY >= y + 4 && mouseY < y + 4 + PANEL_AVATAR
                drawNineSlice(
                    context, BUTTON_TEXTURE, avatarX, y + 4, PANEL_AVATAR, PANEL_AVATAR,
                    when {
                        // 选中态 = **自己**标记过（别人标记过不影响按钮语义：点它就打自己的）
                        entry.markedByMe -> 2
                        btnHovered -> 1
                        else -> 0
                    },
                    BUTTON_TEX_H
                )
                drawMarkIcon(
                    context, avatarX + markIconInset, y + 4 + markIconInset, markIconSize
                )
            } else {
                // 皮肤头像 16px，行高 24 → 上下各 4px。
                // ⚠ 右侧再让出 2px 给滚动条滑块（2026-11-14 用户实测「滚动条盖住一点头像」）
                drawAvatar(
                    context, entry.uuid, entry.name,
                    avatarX, y + 4, PANEL_AVATAR
                )
            }
            // 已标记：头像左侧常驻一个标记图标（右键选中那行不重复画，按钮本身已是选中态）；
            // ≥2 人标记时图标右上角带数量角标
            if (entry.marks.isNotEmpty() && markModeUuid != entry.uuid) {
                val iconX = markIconX + markIconInset
                val iconY = y + 4 + markIconInset
                drawMarkIcon(context, iconX, iconY, markIconSize)
                drawMarkCountBadge(context, textRenderer, entry.marks.size, iconX, iconY, markIconSize)
            }
        }
        // 标题区与名单之间的分割线 —— 放在行高亮**之后**画，免得第一行的高亮把这根线盖淡
        context.fill(panelX + 4, startY, panelX + PANEL_W - 4, startY + 1, 0xFF888888.toInt())
        // 滚动条：只有装不下时才画（可拖动；几何供 mouseClicked / mouseDragged 用）
        peekTargetScrollBar.draw(
            context, panelX + PANEL_W - 5, startY,
            rows * PANEL_ROW_H, peekTargets.size, rows, peekTargetScroll
        )
    }

    /**
     * 面板行的悬停窗：维度 +（已标记时）可疑标记信息与理由。
     * 用项目自己的九宫格背景（与列表悬停窗同款，不用原版黑底 tooltip）。
     *
     * ⚠ 位置是**鼠标左下**（右缘贴鼠标、顶边在光标下方）—— 画在左上会盖住同一行的玩家名
     *   （2026-11-14 用户要求）。靠近屏幕边缘时做 clamp，底部放不下就翻到光标上方。
     */
    private fun drawDimensionTooltip(context: DrawContext, entry: PeekTargetEntry, mouseX: Int, mouseY: Int) {
        data class TipLine(val text: Text, val icon: Boolean, val color: Int)
        val lines = mutableListOf<TipLine>()
        lines += TipLine(
            Text.translatable(
                "cobblemarket.quick_showcase.dimension",
                com.shusheng.cobblemarket.util.DimensionText.of(entry.dimension)
            ),
            false, 0xFFFFFF
        )
        // 操作提示：右键行可以把头像变成可疑按钮（自己已标记过时是取消自己的）
        lines += TipLine(
            Text.translatable(
                if (entry.markedByMe) "cobblemarket.quick_showcase.mark_hint_marked"
                else "cobblemarket.quick_showcase.mark_hint"
            ),
            false, 0xAAAAAA
        )
        // 所有标记（按时间升序）：每条 = 行首图标 +「由 X 于 Y 标记」+ 理由行
        entry.marks.forEach { mark ->
            lines += TipLine(
                Text.translatable(
                    "cobblemarket.quick_showcase.marked_by",
                    Text.literal(mark.byName.ifBlank { "?" }),
                    Text.literal(
                        if (mark.at > 0) markTimeFormatter.format(java.time.Instant.ofEpochMilli(mark.at))
                        else "-"
                    )
                ),
                true, 0xFF7777
            )
            if (mark.reason.isNotBlank()) {
                lines += TipLine(
                    Text.translatable(
                        "cobblemarket.quick_showcase.mark_reason", Text.literal(mark.reason)
                    ),
                    false, 0xAAAAAA
                )
            }
        }
        val iconSize = 8
        val iconGap = 2
        val wrapped = mutableListOf<Pair<net.minecraft.text.OrderedText, TipLine>>()
        lines.forEach { line ->
            val maxW = 180 - if (line.icon) iconSize + iconGap else 0
            textRenderer.wrapLines(line.text, maxW).forEachIndexed { i, text ->
                wrapped += text to line.copy(icon = line.icon && i == 0)
            }
        }
        val padding = 4
        val lineH = maxOf(textRenderer.fontHeight + 2, iconSize + 2)
        val w = wrapped.maxOf { (text, line) ->
            textRenderer.getWidth(text) + if (line.icon) iconSize + iconGap else 0
        } + padding * 2
        val h = wrapped.size * lineH + padding * 2 - 2
        var x = mouseX - w
        var y = mouseY + 12
        if (x < 4) x = 4
        if (y + h > height - 4) y = mouseY - 12 - h
        if (y < 4) y = 4

        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        // 九宫格是半透明的，先垫一层不透明底（同 `renderOverviewTooltip`）
        context.fill(x, y, x + w, y + h, 0xF0101010.toInt())
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, x, y, w, h, 1, ROW_BACKGROUND_TEX_H)
        wrapped.forEachIndexed { i, (text, line) ->
            val rowY = y + padding + i * lineH
            var textX = x + padding
            if (line.icon) {
                drawMarkIcon(context, textX, rowY + (lineH - iconSize) / 2, iconSize)
                textX += iconSize + iconGap
            }
            context.drawTextWithShadow(
                textRenderer, text, textX, rowY + (lineH - textRenderer.fontHeight) / 2, line.color
            )
        }
        context.matrices.pop()
    }

    private fun truncateName(name: String, maxWidth: Int): String {
        if (textRenderer.getWidth(name) <= maxWidth) return name
        var cut = name.length
        while (cut > 1 && textRenderer.getWidth(name.substring(0, cut) + "…") > maxWidth) cut--
        return name.substring(0, cut) + "…"
    }

    /** 皮肤头像（与喵喵银行持有者面板同一套：在线走 tab 列表纹理，离线异步拉取） */
    private fun drawAvatar(context: DrawContext, uuid: UUID, name: String, x: Int, y: Int, size: Int) {
        val texture = PlayerSkinCache.skinFor(uuid, name)
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(size / 8f, size / 8f, 1f)
        context.drawTexture(texture, 0, 0, 8f, 8f, 8, 8, 64, 64)
        context.matrices.pop()
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
     * 是否处于需要暂停松手关闭的**弹窗 / 标记会话**：
     * 右键选中了玩家（可疑按钮已出现）、理由输入框打开、或快捷指令绑定弹窗打开。
     *
     * 按住模式（X）期间**暂停松手关闭** —— 否则松手会丢选中态、打断正在输入的内容
     * （2026-11-19 用户要求；快捷指令绑定弹窗同理）。会话结束的下一 tick 由
     * `CobbleMarketClient` 补一次收起（若此时 X 已松开）。
     */
    fun isModalSessionActive(): Boolean =
        markModeUuid != null || markPromptTarget != null || quickBindSlot != null

    /**
     * 请求关闭：三个框**向上收起**（[CLOSE_MS]），动画结束才真正关界面。
     * 按一下模式的「再按 X / Esc / 点框外」、按住模式的松手 / 回包收尾都走这里。
     * 收起期间不响应悬停 / 点击 / 松手选择（同落下动画）。
     */
    fun requestClose() {
        if (closing) return
        // 理由 / 快捷指令绑定弹窗打开时，按 X / 点外面都先别关界面（弹窗自己处理取消）
        if (markPromptTarget != null || quickBindSlot != null) return
        // ⚠ 注意：**别在这里判 markModeUuid**（右键选中玩家 ≠ 要锁住界面）——
        //   会话结束后的收起由 CobbleMarketClient 的边沿检测补（见那里的注释），
        //   这里若一并返回，收起时机就永远等不到了。
        closing = true
        closeStart = System.currentTimeMillis()
        closeFromOffset = dropOffsetY()
        // 面板不参与上收：记住它此刻的 x，关闭动画里从当前位置向右滑出（滑入到一半也不跳帧）
        closeFromPanelX = peekPanelSlideX()
        hoveredKind = null
        cryAt = 0L
        peekTargetScrollBar.cancel()
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
        // 弹窗打开时松键不关界面（正在输入理由 / 绑定指令）
        if (markPromptTarget != null || quickBindSlot != null) return
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
        if (markPromptTarget != null) {
            when (keyCode) {
                GLFW.GLFW_KEY_ESCAPE -> {
                    closeMarkPrompt()
                    return true
                }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    submitMark()
                    return true
                }
            }
            // 其余按键交给输入框（方向键在这时是移动光标，不能吞）
            return super.keyPressed(keyCode, scanCode, modifiers)
        }
        if (quickBindSlot != null) {
            when (keyCode) {
                GLFW.GLFW_KEY_ESCAPE -> {
                    closeQuickBind()
                    return true
                }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    submitQuickBind()
                    return true
                }
            }
            // 其余按键交给输入框（同标记弹窗）
            return super.keyPressed(keyCode, scanCode, modifiers)
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN ||
            keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT
        ) {
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    /**
     * 理由输入框的**重复字符防护**：按住模式下，快速功能键（默认 X）还按着时，它的自动重复
     * 会当成字符打进输入框（"xxxx"——2026-11-19 用户实测报出）。
     *
     * 这里只在「输入框已打开 + 快速键正物理按下」时吞掉**它打出的那个字符**；
     * 键松开后照常输入（包括理由里真正需要的字母 x）。绑定到不可打印键（F1 等）时不做处理。
     * ⚠ 快捷指令绑定弹窗同样有两个输入框，一并覆盖。
     */
    override fun charTyped(chr: Char, modifiers: Int): Boolean {
        if ((markPromptTarget != null || quickBindSlot != null) &&
            CobbleMarketClient.isQuickShowcaseKeyHeld()
        ) {
            val keyChar = CobbleMarketClient.quickShowcaseKeyChar()
            if (keyChar != null && chr.equals(keyChar, ignoreCase = true)) return true
        }
        return super.charTyped(chr, modifiers)
    }

    /** 按一下模式：点框外 = 取消（按住模式点外面不关，松手才是确认） */
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (closing) return true

        // 理由输入弹窗优先（输入框控件由 super 处理；按钮 / 外部点击自己判）
        if (markPromptTarget != null) {
            if (button == 0) {
                val dx = markDialogX()
                val dy = markDialogY()
                val inDialog = mouseX >= dx && mouseX < dx + MARK_DIALOG_W &&
                    mouseY >= dy && mouseY < dy + MARK_DIALOG_H
                if (!inDialog) {
                    closeMarkPrompt()
                    return true
                }
                val (confirmX, confirmY) = markConfirmButtonOrigin()
                if (mouseX >= confirmX && mouseX < confirmX + MARK_BTN_W &&
                    mouseY >= confirmY && mouseY < confirmY + MARK_BTN_H
                ) {
                    submitMark()
                    return true
                }
                val (cancelX, cancelY) = markCancelButtonOrigin()
                if (mouseX >= cancelX && mouseX < cancelX + MARK_BTN_W &&
                    mouseY >= cancelY && mouseY < cancelY + MARK_BTN_H
                ) {
                    playButtonClickSound()
                    closeMarkPrompt()
                    return true
                }
                // 输入框自己的点击（切焦点）
                super.mouseClicked(mouseX, mouseY, button)
            }
            return true
        }

        // 快捷指令绑定弹窗：与标记弹窗同一套「点框外取消 / 按钮自己判」的处理
        if (quickBindSlot != null) {
            if (button == 0) {
                val dx = quickBindDialogX()
                val dy = quickBindDialogY()
                val inDialog = mouseX >= dx && mouseX < dx + QUICK_BIND_DIALOG_W &&
                    mouseY >= dy && mouseY < dy + QUICK_BIND_DIALOG_H
                if (!inDialog) {
                    closeQuickBind()
                    return true
                }
                val (confirmX, confirmY) = quickBindConfirmButtonOrigin()
                if (mouseX >= confirmX && mouseX < confirmX + MARK_BTN_W &&
                    mouseY >= confirmY && mouseY < confirmY + MARK_BTN_H
                ) {
                    submitQuickBind()
                    return true
                }
                val (cancelX, cancelY) = quickBindCancelButtonOrigin()
                if (mouseX >= cancelX && mouseX < cancelX + MARK_BTN_W &&
                    mouseY >= cancelY && mouseY < cancelY + MARK_BTN_H
                ) {
                    playButtonClickSound()
                    closeQuickBind()
                    return true
                }
                // 点击弹窗内的空白 / 标题：输入框**显式失焦**（2026-11-20 用户报「输入框没有做失焦处理」）。
                // ⚠ Screen 的默认点击处理不会替我们清掉输入框的焦点 —— 光靠 super 的话，
                //   光标与按键仍留在框里。点在输入框上则交给 super 正常切换焦点（它会清另一个）。
                val onField = quickBindNameField?.isMouseOver(mouseX, mouseY) == true ||
                    quickBindCommandField?.isMouseOver(mouseX, mouseY) == true
                super.mouseClicked(mouseX, mouseY, button)
                if (!onField) {
                    quickBindNameField?.setFocused(false)
                    quickBindCommandField?.setFocused(false)
                    setFocused(null)
                }
            }
            return true
        }

        // 相机按钮：左键拍照（拍照流程见 PhotoCapture：先收起界面再截图）
        if (button == 0 && !closing && dropFinished() && cameraButtonAt(mouseX, mouseY)) {
            startPhotoCapture()
            return true
        }

        // 快捷指令按钮：左键 = 空槽绑定 / 已绑定直接使用；右键 = 编辑。
        // ⚠ 必须排在下面的「右键标记玩家」之前 —— 那一支对没有 overview 权限的玩家不生效，
        //   而绑定按钮对所有玩家开放。
        if (!closing && dropFinished()) {
            val quickSlot = quickSlotAt(mouseX, mouseY)
            if (quickSlot != null) {
                if (button == 0) {
                    useQuickCommand(quickSlot)
                    return true
                }
                if (button == 1) {
                    openQuickBind(quickSlot)
                    return true
                }
            }
        }

        // 右键：选中 / 取消选中一行（头像变成「可疑」按钮）
        if (button == 1 && canSeePeekPanel() && peekPanelSlideFinished()) {
            val panelX = peekPanelTargetX()
            markModeUuid = if (peekPanelContains(panelX, mouseX, mouseY)) {
                val target = peekTargetAt(panelX, mouseX, mouseY)
                if (target != null && markModeUuid != target.uuid) target.uuid else null
            } else {
                null
            }
            return true
        }

        // 右侧在线玩家面板：**行优先**（滚动条命中区比视觉宽）；点面板空白处也吃掉，
        // 别让「按一下模式点框外关闭」把点面板当成点外面
        if (button == 0 && canSeePeekPanel() && peekPanelSlideFinished()) {
            val panelX = peekPanelTargetX()
            if (peekPanelContains(panelX, mouseX, mouseY)) {
                // 标记按钮优先：右键选中那行的头像位置（不经过 peekTargetAt 的右缘限制）
                val markButton = markButtonEntryAt(panelX, mouseX, mouseY)
                if (markButton != null) {
                    playButtonClickSound()
                    // 只取消**自己**的标记；别人标记过时按钮是未选中态 ⇒ 点它是打自己的标记
                    if (markButton.markedByMe) {
                        pendingMarkResult = Triple(
                            markButton.uuid, false, System.currentTimeMillis() + MARK_RESULT_TIMEOUT_MS
                        )
                        sendToServer(ClearPeekMarkPayload(markButton.uuid))
                        // ⚠ 这里**不**立刻退出选中态：先恢复头像会闪一下「头像 + 常驻标记图标」，
                        //   等服务端回包确认标记已移除后在 onPeekTargets 里再退出（2026-11-17 用户实测反馈）
                    } else {
                        openMarkPrompt(markButton)
                    }
                    return true
                }
                val target = peekTargetAt(panelX, mouseX, mouseY)
                if (target != null) {
                    markModeUuid = null
                    playButtonClickSound()
                    // 与总览行尾「监视」按钮完全同一个入口；成功 / 失败都走聊天反馈
                    sendToServer(StartPeekPayload(target.uuid))
                    closeSelf()
                    return true
                }
                markModeUuid = null
                // 滚动条（只有装不下时才画几何 —— 先判溢出，防读到上一份旧几何）
                if (peekTargets.size > peekPanelRows()) {
                    peekTargetScrollBar.mouseClicked(mouseX, mouseY, peekTargetScroll)?.let {
                        peekTargetScroll = it
                        return true
                    }
                }
                return true
            }
        }
        // 点面板外：清掉右键选中态
        if (markModeUuid != null && !(canSeePeekPanel() && peekPanelContains(peekPanelTargetX(), mouseX, mouseY))) {
            markModeUuid = null
        }
        val handled = super.mouseClicked(mouseX, mouseY, button)
        if (!handled && ClientConfig.quickShowcasePressOnce) {
            requestClose()
            return true
        }
        return handled
    }

    // ── 可疑标记：理由弹窗 / 图标 / 形状 ──

    private fun renderMarkPrompt(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0x80000000.toInt())
        val dx = markDialogX()
        val dy = markDialogY()
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dx, dy, MARK_DIALOG_W, MARK_DIALOG_H, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.quick_showcase.mark_title").formatted(Formatting.GOLD),
            width / 2, dy + 8, 0xFFFFFF
        )
        // 输入框左侧的「原因：」标签
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.quick_showcase.mark_reason_label"),
            dx + 10, dy + 24 + 4, 0xFFFFFF
        )
        // 输入框控件在 openMarkPrompt 时 addDrawableChild，交给 super.render
        super.render(context, mouseX, mouseY, delta)
        val (confirmX, confirmY) = markConfirmButtonOrigin()
        val (cancelX, cancelY) = markCancelButtonOrigin()
        drawDialogButton(
            context, confirmX, confirmY,
            Text.translatable("cobblemarket.quick_showcase.mark_confirm"), mouseX, mouseY
        )
        drawDialogButton(
            context, cancelX, cancelY,
            Text.translatable("cobblemarket.quick_showcase.mark_cancel"), mouseX, mouseY
        )
    }

    /** 弹窗底部按钮（标记理由窗与快捷指令绑定窗共用；尺寸即 [MARK_BTN_W]×[MARK_BTN_H]） */
    private fun drawDialogButton(
        context: DrawContext, x: Int, y: Int, text: Text, mouseX: Int, mouseY: Int
    ) {
        val hovered = mouseX in x until x + MARK_BTN_W && mouseY in y until y + MARK_BTN_H
        drawNineSlice(
            context, BUTTON_TEXTURE, x, y, MARK_BTN_W, MARK_BTN_H,
            if (hovered) 1 else 0, BUTTON_TEX_H
        )
        context.drawCenteredTextWithShadow(textRenderer, text, x + MARK_BTN_W / 2, y + 4, 0xFFFFFF)
    }

    private fun markDialogX(): Int = (width - MARK_DIALOG_W) / 2
    private fun markDialogY(): Int = (height - MARK_DIALOG_H) / 2
    private fun markConfirmButtonOrigin(): Pair<Int, Int> =
        (width / 2 - MARK_BTN_W - 4) to (markDialogY() + MARK_DIALOG_H - 10 - MARK_BTN_H)
    private fun markCancelButtonOrigin(): Pair<Int, Int> =
        (width / 2 + 4) to (markDialogY() + MARK_DIALOG_H - 10 - MARK_BTN_H)

    /** 标记图标源图 48×48，按 [size] 绘制（头像/按钮都是 16） */
    private fun drawMarkIcon(context: DrawContext, x: Int, y: Int, size: Int) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(size / 48f, size / 48f, 1f)
        context.drawTexture(peekMarkIcon, 0, 0, 0f, 0f, 48, 48, 48, 48)
        context.matrices.pop()
    }

    /** 鼠标落在右键选中那一行的头像按钮上时返回该行（不经过 [peekTargetAt] 的右缘限制） */
    private fun markButtonEntryAt(panelX: Int, mouseX: Double, mouseY: Double): PeekTargetEntry? {
        val uuid = markModeUuid ?: return null
        val avatarX = panelX + PANEL_W - 4 - PANEL_AVATAR - 4
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        if (mx < avatarX || mx >= avatarX + PANEL_AVATAR) return null
        val startY = baseY + PANEL_HEADER_H
        if (my < startY) return null
        val index = (my - startY) / PANEL_ROW_H
        if (index !in 0 until peekPanelRows()) return null
        return peekTargets.getOrNull(peekTargetScroll + index)?.takeIf { it.uuid == uuid }
    }

    private fun openMarkPrompt(entry: PeekTargetEntry) {
        markPromptTarget = entry
        markModeUuid = entry.uuid
        val labelW = textRenderer.getWidth(Text.translatable("cobblemarket.quick_showcase.mark_reason_label"))
        val fieldX = markDialogX() + 10 + labelW + 4
        val fieldW = (markDialogX() + MARK_DIALOG_W - 10 - fieldX).coerceAtLeast(40)
        val field = TextFieldWidget(
            textRenderer,
            fieldX, markDialogY() + 24, fieldW, 16,
            Text.literal("")
        )
        field.setMaxLength(80)
        field.setPlaceholder(Text.translatable("cobblemarket.quick_showcase.mark_reason_hint"))
        markReasonField = field
        addDrawableChild(field)
        // ⚠ 只调 widget 的 setFocused(true) 不够：Screen 的焦点还在别处 ⇒ 按键不会分发到输入框，
        //   表现是「光标在闪但打不了字」（2026-11-16 用户实测）。
        //   ⚠ 不能用 `setInitialFocus(field)` —— 它内部走 element.getNavigationPath()，
        //   而 TextFieldWidget 没有导航路径、返回 null ⇒ 焦点静默不生效（2026-11-17 实测复查）。
        //   直接调 AbstractParentElement.setFocused(Element) 才是真正把焦点交给它。
        field.setFocused(true)
        setFocused(field)
        // 弹窗期间隐藏三个大框按钮，避免从遮罩上透出来
        pokemonBtn?.visible = false
        idCardBtn?.visible = false
        itemBtn?.visible = false
    }

    private fun closeMarkPrompt() {
        markReasonField?.let { remove(it) }
        markReasonField = null
        // 焦点交还界面（否则移除掉的输入框仍占着 Screen 焦点，后续按键会被它吞掉）
        setFocused(null)
        markPromptTarget = null
        markModeUuid = null
        pokemonBtn?.visible = true
        idCardBtn?.visible = true
        itemBtn?.visible = true
    }

    private fun submitMark() {
        // 等回包期间不重复提交（防连点连发两次）
        if (pendingMarkResult != null) return
        val target = markPromptTarget ?: return
        playButtonClickSound()
        pendingMarkResult = Triple(
            target.uuid, true, System.currentTimeMillis() + MARK_RESULT_TIMEOUT_MS
        )
        sendToServer(SetPeekMarkPayload(target.uuid, markReasonField?.text.orEmpty()))
        // ⚠ **不在这里关弹窗**：先等服务端回包（`onPeekTargets` 里播成功音并关框）。
        //   按住模式下会话一结束界面就会收起（见 `CobbleMarketClient` 的边沿检测），这里若先关，
        //   回包到达时界面已经关了，成功音/失败反馈就丢了。回包通常同一 tick 内到达，
        //   超时由 [tick] 兜底关框。
    }

    /** 滚轮：面板内滚动名单（与喵喵银行持有者面板同口径） */
    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (markPromptTarget != null) return true
        if (!closing && canSeePeekPanel() && peekPanelSlideFinished() &&
            peekTargets.size > peekPanelRows() &&
            peekPanelContains(peekPanelTargetX(), mouseX, mouseY)
        ) {
            peekTargetScroll = (peekTargetScroll - verticalAmount.toInt())
                .coerceIn(0, peekTargets.size - peekPanelRows())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    /** 拖动：面板滚动条（拖动状态由 [ScrollBar] 自己记；返回 null = 没在拖） */
    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (canSeePeekPanel()) {
            val newScroll = peekTargetScrollBar.mouseDragged(mouseY, peekTargetScroll)
            if (newScroll != null) {
                peekTargetScroll = newScroll
                return true
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    /** 松开：结束滚动条拖动（结束后吃掉这次松开，否则它会在别处再触发一次点击） */
    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (peekTargetScrollBar.mouseReleased()) return true
        return super.mouseReleased(mouseX, mouseY, button)
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

        /** 标记操作等待服务端回包的超时（超过丢弃 pending，避免永久挂起） */
        private const val MARK_RESULT_TIMEOUT_MS = 2_000L

        // ── 快捷指令按钮（2026-11-20）──
        /**
         * 按钮边长。用户 2026-11-20 实机看后定：**边长为三个大按钮（[BTN] = 128）的四分之一 = 32**。
         * 单侧 2×2 块因此是 68；块**右缘（左块）/ 左缘（右块）固定**，只向外长，
         * 与 ID 卡之间的 14px 间隙不变（见 [quickBlockX]）。
         */
        private const val QUICK_BTN = BTN / 4
        /**
         * 同侧按钮的间距（行 / 列同一值，2×2 观感均匀）。
         * ⚠ **行距必须大于 [QUICK_LIFT]**：否则下一行悬停上浮时会盖住上一行的底边
         *   （2026-11-20 用户实测报出，初版是间距 4 / 上浮 10）。
         */
        private const val QUICK_GAP = 8
        /**
         * 悬停上浮量。**独立于三个大框的 [LIFT] = 10**：32px 的小按钮上 10px 相对位移太大
         * （用户 2026-11-20 要求减小）⇒ 取 6。上浮后与上一行仍留 `QUICK_GAP − 6 = 2px`，不会覆盖。
         * ⚠ 改动这个值必须同步检查 [QUICK_GAP] 的行距与 [quickSlotAt] 的顶部判定带。
         */
        private const val QUICK_LIFT = 6
        /** 每侧 2 列（2 行 × 2 列 = 每侧 4 个，共 8 个） */
        private const val QUICK_COLS = 2
        /** 单侧整块尺寸（[QUICK_COLS] 个按钮 + 中间 [QUICK_COLS] − 1 个间距） */
        private const val QUICK_BLOCK = QUICK_BTN * QUICK_COLS + (QUICK_COLS - 1) * QUICK_GAP
        /** 整块与 ID 卡框之间的水平间隙（ID 卡两侧各 76px 空间里居中：14 + 48 + 14） */
        private const val QUICK_INSET = 14
        /** 加号贴图源尺寸（48×48，用户 2026-11-20 提供） */
        private const val QUICK_ADD_TEX = 48
        /** 绑定弹窗宽 / 高（与标记弹窗同宽；高度 = 标题 + 两行输入 + 按钮行 + 底部 10px） */
        private const val QUICK_BIND_DIALOG_W = 240
        private const val QUICK_BIND_DIALOG_H = 116

        // ── 相机按钮（功能2，2026-11-20）──
        /**
         * 相机按钮边长：**与快捷指令按钮一致**（用户 2026-11-20 要求），= 三个大按钮的 1/4 = 32。
         * ⚠ 顶部只有 40px（1080p/S3）⇒ 32 + 间隙 2 + 上浮 6 = 40 正好放下；
         *   更小的窗口靠 [CAMERA_TOP_MARGIN] 贴顶保底、上浮距离自动变短。
         */
        private const val CAMERA_BTN = QUICK_BTN
        /** 相机按钮与三框组顶边的间隙（配合 [CAMERA_BTN] 与 [QUICK_LIFT]，三者加起来 = 顶部 40px） */
        private const val CAMERA_GAP = 2
        /** 相机按钮距屏幕顶的最小距离（小窗口贴顶保底） */
        private const val CAMERA_TOP_MARGIN = 2
        /** 相机图标源尺寸（48×48） */
        private const val CAMERA_TEX = 48
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

        // ── 右侧「在线玩家」面板（2026-11-10）──
        /** 面板宽度：原 128 + 16（给头像左侧的可疑标记图标留位，2026-11-14 用户要求） */
        private const val PANEL_W = 144

        /** 可疑标记理由弹窗 */
        private const val MARK_DIALOG_W = 240
        private const val MARK_DIALOG_H = 90
        private const val MARK_BTN_W = 80
        private const val MARK_BTN_H = 16

        /** 面板离屏幕右缘的空隙 */
        private const val PANEL_RIGHT_MARGIN = 16

        /** 面板高度 = 三个大框整组的高度（与大框组顶部对齐） */
        private const val PANEL_H = BTN * 2 + GAP

        /** 标题区高度（标题 + 人数两行；名单从这里开始） */
        private const val PANEL_HEADER_H = 29

        /** 面板底部留白 */
        private const val PANEL_BOTTOM_PAD = 4

        /** 名单行高（项目 2026-10-04 起的新列表基准 24） */
        private const val PANEL_ROW_H = 24

        /** 皮肤头像尺寸 */
        private const val PANEL_AVATAR = 16

        /** 面板从屏幕右缘滑入的时长（与三个大框的掉落同起点、同时长） */
        private const val PANEL_SLIDE_MS = 220L

        /** 在线名单刷新间隔（打开期间；服务端读请求有 500ms 限流） */
        private const val TARGETS_REFRESH_MS = 2_000L
    }
}
