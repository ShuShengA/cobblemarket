package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.abilities.PotentialAbility
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.client.settings.ServerSettings
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.PokemonSizeCategory
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import com.shusheng.cobblemarket.client.ClientPermissionCache
import com.shusheng.cobblemarket.client.CloseAnimation
import com.shusheng.cobblemarket.client.CustomCloseAnimationScreen
import com.shusheng.cobblemarket.client.CustomShopClaimCache
import com.shusheng.cobblemarket.client.CustomShopCooldownCache
import com.shusheng.cobblemarket.client.ItemClaimFlyAnimation
import com.shusheng.cobblemarket.client.ItemComponentsDisplay
import com.shusheng.cobblemarket.client.PointMarketCache
import com.shusheng.cobblemarket.client.ShopCardDiscountCache
import com.shusheng.cobblemarket.util.FeatureCandidates
import com.shusheng.cobblemarket.client.PointsShopCache
import com.shusheng.cobblemarket.client.drawItemWithBar
import com.shusheng.cobblemarket.client.formatPriceShortLong
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.shusheng.cobblemarket.client.icon.PreviewStateHolder
import com.shusheng.cobblemarket.client.playButtonClickSound
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.CustomShopMode
import com.shusheng.cobblemarket.currency.CustomShopType
import com.shusheng.cobblemarket.currency.DEFAULT_CLAIM_HOURS
import com.shusheng.cobblemarket.currency.MAX_COOLDOWN_HOURS
import com.shusheng.cobblemarket.currency.MAIN_SHOP_ID
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.PointMarketService
import com.shusheng.cobblemarket.currency.PointShopState
import com.shusheng.cobblemarket.currency.ShopEntry
import com.shusheng.cobblemarket.currency.ShopItemSpec
import com.shusheng.cobblemarket.currency.ShopCardDiscount
import com.shusheng.cobblemarket.currency.ShopPokemonSpec
import com.shusheng.cobblemarket.market.ItemRuleComponents
import com.shusheng.cobblemarket.network.BuyPointMarketEntryPayload
import com.shusheng.cobblemarket.network.ClaimCustomShopPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.PointMarketSearchPayload
import com.shusheng.cobblemarket.network.PointMarketSlotInfo
import com.shusheng.cobblemarket.network.SearchPointMarketPayload
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.currency.PointShopSnapshotState
import com.shusheng.cobblemarket.network.DeleteShopSnapshotPayload
import com.shusheng.cobblemarket.network.RedeemPointCodePayload
import com.shusheng.cobblemarket.network.RequestShopSnapshotsPayload
import com.shusheng.cobblemarket.network.RequestSpeciesFeatureAspectsPayload
import com.shusheng.cobblemarket.network.RequestSpeciesMovesPayload
import com.shusheng.cobblemarket.network.SaveShopSnapshotPayload
import com.shusheng.cobblemarket.network.ShopSnapshotInfo
import com.shusheng.cobblemarket.network.RemovePointMarketEntryPayload
import com.shusheng.cobblemarket.network.RequestPointMarketPayload
import com.shusheng.cobblemarket.network.RequestShopCardDiscountPayload
import com.shusheng.cobblemarket.network.RequestPointsShopPayload
import com.shusheng.cobblemarket.network.SavePointMarketEntryPayload
import com.shusheng.cobblemarket.network.SetShopEditingPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.util.ItemNameText
import com.shusheng.cobblemarket.util.MoveText
import com.shusheng.cobblemarket.util.RemainingTime
import com.shusheng.cobblemarket.util.SpeciesText
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * **RP 商城**：两级结构里的中间层（入口界面 → 本界面 → 充值界面）。
 *
 * 本层放两样东西：
 * - **兑换RP**（常驻）：输入 / 粘贴兑换码 —— 国内外网站、发卡网、活动码、补偿码都走这条
 * - **购买RP**入口（B2.5b2 接入）：档位列表 → 收款码弹窗；仅当服主配了档位与收款码时显示
 *
 * 商品区（RP 商城）方案未定，本层先**留位置不渲染** —— 内容整体放在背景中偏下，
 * 上方那片留白就是它的位置。
 *
 * 背景渲染（方案 2026-09-17 拍板）：`points_market_background.png` **等比缩放 + 居中 + 四周留 4%**。
 * ⚠ **不用九宫格**：四条边的中段都有红色装饰条，九宫格拉伸会把它们拉长变形（越宽的屏拉得越长）。
 */
class PointsMarketScreen(
    /**
     * 哪个商店：[MAIN_SHOP_ID] = 从入口界面进来的主 RP 商城；其余 = 自定义（NPC）商店 id。
     *
     * ⚠ 同一个界面同时服务两种商店：只有顶部兑换区与返回目标不同（见 [isCustomShop]），
     * 商品区 / 购买窗 / 编辑窗**完全共用** —— 别为自定义商店另抄一份界面。
     */
    val shopId: String = MAIN_SHOP_ID,
    /** 自定义商店的显示名（主商城留空；标题见 [drawMarketDecor]） */
    private val customShopName: String = "",
    /** 自定义商店出售什么：`pokemon` / `item` / `both`（主商城恒 both，见 [CustomShopType]） */
    shopType: String = CustomShopType.BOTH.id,
    /** 从管理界面打开的：返回按钮回管理界面，而不是回游戏 */
    private val returnToAdmin: Boolean = false,
    /**
     * 这家店的结算币种（**整店一个**）：主商城恒 RP；自定义商店看建店时的选择。
     * 价格 / 购买窗 / 总价全按它显示（服务端扣款也按它，界面只是展示）。
     */
    private val shopCurrency: MarketCurrency = MarketCurrency.POINTS,
    /** 经营模式：`normal` = 普通购买；`claim` = 定期领取（主商城恒普通） */
    private val shopMode: CustomShopMode = CustomShopMode.NORMAL,
    /** 领取周期（小时）；只有领取店用得到 */
    private val claimHours: Int = DEFAULT_CLAIM_HOURS,
    /** 整店购买冷却（小时，0 = 不开）；只有普通自定义商店用得到 */
    private val shopCooldownHours: Int = 0,
    /** 商品各自冷却（小时，0 = 不开）；只有普通自定义商店用得到 */
    private val itemCooldownHours: Int = 0
) : Screen(
    if (shopId == MAIN_SHOP_ID)
        Text.translatable("cobblemarket.points.market.title", CurrencyService.pointsTitleIcon())
    else Text.literal(customShopName.ifEmpty { shopId })
), CustomCloseAnimationScreen {

    /** 是不是自定义（NPC）商店 —— 顶部兑换区显隐、返回目标都用它 */
    private val isCustomShop: Boolean get() = shopId != MAIN_SHOP_ID

    /** 定期领取店：隐藏购买流程，底部出现「领取」按钮（主商城永远不是领取店） */
    private val isClaimShop: Boolean get() = isCustomShop && shopMode == CustomShopMode.CLAIM

    /** **自定义普通购买店**：格子冷却 / 限购只在这类店有意义（主商城与领取店都没有） */
    private fun isCustomNormalShop(): Boolean = isCustomShop && !isClaimShop

    /**
     * 这一格**实际生效的冷却小时** = `max(整店冷却, 商品各自冷却, 本格覆盖)`；0 = 不冷却。
     * 本格填 0 / 留空 = 跟随商店 / 各自冷却（见 [ShopEntry.cooldownHours]）。
     */
    private fun effectiveCooldownHours(entry: ShopEntry): Int =
        if (!isCustomNormalShop()) 0
        else maxOf(shopCooldownHours, itemCooldownHours, entry.cooldownHours)

    /** 有冷却的商品一次只能买 1 件（与服务器 `PointMarketService.buy` 同一判据） */
    private fun isCooldownEntry(entry: ShopEntry): Boolean = effectiveCooldownHours(entry) > 0

    /** 这一格当前剩余冷却（毫秒）；0 = 现在就能买 */
    private fun cooldownRemaining(entry: ShopEntry): Long =
        if (!isCustomNormalShop()) 0L else CustomShopCooldownCache.remainingOf(shopId, entry.entryId)

    /**
     * 单类型商店**只显示那一类**的网格（不建 tab 按钮）。
     * null = 双 tab（主商城 / `both` 自定义商店）。
     *
     * ⚠ [shopType] 由打开包带下来；类型命令改了之后需要重新打开界面（服务端另有闸门，
     *   旧界面就算还留着另一类的格子也买不了 / 存不进去）。
     */
    private val fixedKind: String? = if (isCustomShop) when (CustomShopType.fromId(shopType)) {
        CustomShopType.POKEMON -> ShopEntry.KIND_POKEMON
        CustomShopType.ITEM -> ShopEntry.KIND_ITEM
        CustomShopType.BOTH -> null
    } else null

    init {
        // 自定义商店**每次打开都强制拉一次**新快照（只在新实例上做，resize 重建不会清）：
        // 缓存是进程级的，重连服务器 / 服务器重启后可能还留着旧店的数据；
        // 主商城保持原有行为（它一直是这个缓存口径，不在这次改动范围内）。
        if (isCustomShop) PointMarketCache.clear()
    }

    /** 卡片购买折扣只问一次（resize / 关购买窗都会重跑 [init]()，重复问没有意义） */
    private var cardDiscountRequested = false

    private var codeField: TextFieldWidget? = null
    /** 输入框内容：界面 resize 会重建控件，靠它把玩家已经敲进去的码恢复回来 */
    private var pendingCodeText = ""

    // ── 商品区（精灵 / 物品 双 tab 网格）──
    private var currentTab = 0
    private var pokemonTabButton: NineSliceButton? = null
    private var itemTabButton: NineSliceButton? = null
    private var prevButton: NineSliceButton? = null
    private var nextButton: NineSliceButton? = null
    /** 领取店的「一键领取」按钮（冷却期间置灰，2026-10-25） */
    private var claimButton: NineSliceButton? = null
    private var currentPage = 1
    /** 总页数：**服务端快照说了算**（按最大已填格子序号算，本地不自己推） */
    /**
     * 总页数 = 服务端快照的页数（**只按已上架内容算**）+ **有编辑权限时多一页**。
     *
     * ⚠ 为什么要按权限补一页：服务端按固定的 `POINTS_SHOP_PAGE_SIZE=32` 算页，而客户端
     *   **一页可见几格随分辨率变**（大屏 8×4=32、矮屏按宽度铺满只有 8×3=24）——
     *   矮屏玩家把看得见的 24 格全填满，序号也才到 23，服务端算出来仍是 1 页，
     *   「下一页」点不动、第 25 格起又看不见 ⇒ **填不满 → 翻不了 → 更看不见** 的死锁
     *   （2026-09-27 用户实测：8×3 全满仍翻不动）。
     *   **有编辑权限的人**得能翻到空页去放货 ⇒ 给他补一页；**普通玩家**不该看到空页 ⇒ 不补。
     *   ⚠ 只能在这补：服务端快照是**广播**的，它没法按人算。
     */
    private val totalPages: Int
        get() = PointMarketCache.totalPages + if (canEditShop()) 1 else 0
    /** 鼠标所在格子索引；-1 = 不在任何格子上。悬停的格子会**放大绘制**（浮在邻居之上） */
    private var hoveredCell = -1

    // ── 窗口「从格子长大」的展开动画（2026-09-27；做法照 [com.shusheng.cobblemarket.client.FilterPanelAnimation]）──

    /**
     * 动画开始时刻（0 = 没有动画在跑）。**全局时间戳**，不用 `delta`/`tickDelta` ——
     * 那个被 `FloatingState` 累加过，拿它当时间轴速度会变成「帧率 ÷ 40」（文档 points_shop_plan 记过这条）。
     */
    private var dialogAnimStart = 0L

    /** 上一帧动画是否在跑（用来检测「刚播完」，好把窗口控件放出来） */
    private var dialogAnimWasActive = false

    /**
     * 展开起点矩形 `[x, y, w, h]`：**1.15 倍的格子**（= 点下去那一刻鼠标正悬停、看到的那个放大态），
     * 见 [HOVER_SCALE]。收回终点另算（1.0 倍基础格子，那时鼠标已经不在上面了）。
     */
    private var dialogAnimFrom = IntArray(4)

    /** 插值终点矩形：展开时是弹窗矩形、收回时是 **1.0 倍基础格子**（非对称，见文档） */
    private var dialogAnimTo = IntArray(4)

    /** 本次是不是**收回**动画（决定插值方向 + 内容淡出还是淡入） */
    private var dialogAnimClosing = false

    /**
     * 收回动画**播完**要做的事（真正清状态 + 重建界面）。展开时恒为 null。
     * ⚠ [startDialogAnim] 会把它清掉 —— 切格子时 `closeBuyDialog` 与 `openBuyDialog` 连着调，
     *   不清的话这个待办会在新窗口的动画结束时触发、**把刚开的窗口关掉**。
     */
    private var dialogAnimOnEnd: (() -> Unit)? = null

    /** 展开动画时长（ms）。200 与筛选面板那套一致（文档写的「实测调参」起点） */
    private val dialogAnimMs = com.shusheng.cobblemarket.client.FilterPanelAnimation.DURATION_MS

    /** 动画原始进度 0..1（未开始或已结束都算 1） */
    private fun dialogAnimRaw(): Float =
        if (dialogAnimStart == 0L) 1f
        else ((System.currentTimeMillis() - dialogAnimStart).toFloat() / dialogAnimMs).coerceIn(0f, 1f)

    /** smoothstep 缓动（先快后慢，与筛选面板 / 进出场动画同一根曲线） */
    private fun dialogAnimProgress(): Float {
        val raw = dialogAnimRaw()
        return raw * raw * (3f - 2f * raw)
    }

    /** 动画进行中 —— 窗口内的**控件**要藏起来、**内容**先不画（见 [updateDialogAnimControls]） */
    private fun dialogAnimActive(): Boolean = dialogAnimStart != 0L && dialogAnimRaw() < 1f

    // ── 自定义商店的「打开动画」：从鼠标位置放大（2026-10-20 用户要求）──
    // 只作用于自定义商店（右键 NPC / `/market shop open`），主商城保持原来的直接出现。
    // 起点取**首帧的鼠标坐标** —— 玩家右键 NPC 时鼠标就在点击位置，界面下一帧才画，
    // 中间不会有真实移动；比在 S2C handler 里换算窗口像素更省事，也天然跟着 GUI scale 走。

    /** 打开动画是否已经开始（一个界面实例只播一次；resize 重跑 [init] 不重播） */
    private var openAnimStarted = false
    private var openAnimStart = 0L
    private var openAnimOriginX = 0f
    private var openAnimOriginY = 0f
    private var openAnimOriginCaptured = false

    /** 动画时长（ms）—— 比弹窗的 200ms 稍长一点，整屏缩放看得清 */
    private val openAnimMs = 220L

    /** 起手缩放（相对最终背景）—— 640×360 下约 37×21，从鼠标位置的一小块长到全屏 */
    private val openAnimStartScale = 0.06f

    private fun openAnimRaw(): Float =
        ((System.currentTimeMillis() - openAnimStart).toFloat() / openAnimMs).coerceIn(0f, 1f)

    private fun openAnimActive(): Boolean =
        isCustomShop && openAnimStarted && openAnimRaw() < 1f

    // ── 自定义商店关闭动画（打开动画的反向，2026-10-25） ──
    private var closeAnimStarted = false
    private var closeAnimStart = 0L
    private var closeAnimTarget: Screen? = null

    /**
     * 关闭时长**对齐入口界面的关闭动画**（[CloseAnimation.DURATION_MS] = 100ms）：
     * 播完 `setScreen(null)` 时原版会自动锁定鼠标、光标回到屏幕中心，
     * 与按 E/Esc 关入口界面是同一套手感（用户 2026-10-25 指出）
     */
    private val closeAnimMs = CloseAnimation.DURATION_MS

    private fun closeAnimRaw(): Float =
        ((System.currentTimeMillis() - closeAnimStart).toFloat() / closeAnimMs).coerceIn(0f, 1f)

    override fun isCustomCloseActive(): Boolean = closeAnimStarted

    override fun startCustomClose(target: Screen?): Boolean {
        if (!isCustomShop || closeAnimStarted) return false
        closeAnimStarted = true
        closeAnimStart = System.currentTimeMillis()
        closeAnimTarget = target
        return true
    }

    private fun finishCustomClose() {
        closeAnimStarted = false
        val to = closeAnimTarget
        closeAnimTarget = null
        client?.setScreen(to)
    }

    /**
     * 窗口**内容**的淡入 alpha：动画前 70% 全透明、后 30% 渐显到 1。
     *
     * 照 [com.shusheng.cobblemarket.client.FilterPanelAnimation] 的「1.4 倍进度」思路 ——
     * 让内容比背景晚一点、但比「到点啪一下出现」柔和。
     * ⚠ 调用方必须用 `setShaderColor` 包住内容绘制，并在**同一次 render 内**恢复成 `1,1,1,1`
     *   （内容里有多个 `return` 分支，所以用 `try/finally`，别指望顺序恢复）。
     */
    private fun dialogAnimContentAlpha(): Float {
        val p = dialogAnimProgress()
        // 收回时**反过来**：前 30% 就淡干净 —— 别让内容拖到窗口快缩没了还挂着
        return if (dialogAnimClosing) (1f - p / 0.3f).coerceIn(0f, 1f)
        else ((p - 0.7f) / 0.3f).coerceIn(0f, 1f)
    }

    /**
     * 当前这一帧窗口该画成多大：动画中 = 从 [dialogAnimFrom] 插值到最终矩形；否则 = 最终矩形。
     *
     * ⚠ 用**插值矩形重画九宫格**，而不是拿 matrices 缩放贴图 —— 后者会把圆角拉变形
     * （`drawTexture` 的 width/height 是**源图采样区域**，传缩放尺寸只会把左上角那一块抽出来放大）。
     */
    private fun dialogAnimRect(): IntArray {
        val full = intArrayOf(editDialogX(), editDialogY(), editDialogW(), editDialogHeight())
        if (!dialogAnimActive()) return full
        val t = dialogAnimProgress()
        val a = dialogAnimFrom
        val b = dialogAnimTo
        return intArrayOf(
            (a[0] + (b[0] - a[0]) * t).roundToInt(),
            (a[1] + (b[1] - a[1]) * t).roundToInt(),
            (a[2] + (b[2] - a[2]) * t).roundToInt(),
            (a[3] + (b[3] - a[3]) * t).roundToInt()
        )
    }

    /**
     * 启动**收回**动画：弹窗矩形 → **1.0 倍基础格子**（不是 1.15 —— 关窗时鼠标已经不在格子上了），
     * 播完执行 [onEnd]（真正清状态 + 重建界面）。
     *
     * ⚠ 靠 [dialogAnimOnEnd] 延迟关闭 ⇒ 这 200ms 里 `buySlot`/`editSlot` **还开着**，
     *   所以下层照旧不可点（`dialogOpen()` 仍为 true）—— 这是有意为之，不是漏拦。
     */
    private fun startCloseAnimation(slot: Int, onEnd: () -> Unit) {
        val layout = gridLayout(bgRect())
        val pageCell = slot - (currentPage - 1) * PointShopState.POINTS_SHOP_PAGE_SIZE
        dialogAnimFrom = intArrayOf(editDialogX(), editDialogY(), editDialogW(), editDialogHeight())
        dialogAnimTo = intArrayOf(layout.cellX(pageCell), layout.cellY(pageCell), layout.cell, layout.cell)
        dialogAnimClosing = true
        dialogAnimStart = System.currentTimeMillis()
        dialogAnimWasActive = true
        dialogAnimOnEnd = onEnd
    }

    /**
     * 记录展开起点并启动动画。**从格子点开时调**（起点 = 那个格子的 1.15 倍态）。
     * @param slot 被点的格子**全局序号**
     */
    private fun startDialogAnim(slot: Int) {
        val layout = gridLayout(bgRect())
        val pageCell = slot - (currentPage - 1) * PointShopState.POINTS_SHOP_PAGE_SIZE
        val size = layout.cell
        // 与悬停放大同一份算法（drawShopCell 里的 grow）：两边各外扩一半 ⇒ 放大后仍居中于原格子
        val grow = ((size * HOVER_SCALE) - size).roundToInt() / 2
        dialogAnimFrom = intArrayOf(
            layout.cellX(pageCell) - grow, layout.cellY(pageCell) - grow,
            size + grow * 2, size + grow * 2
        )
        dialogAnimTo = intArrayOf(editDialogX(), editDialogY(), editDialogW(), editDialogHeight())
        dialogAnimClosing = false
        // ⚠ 切格子是「先 close 再 open」连着调的：不清掉的话收回的待办会在新窗口动画结束时**把新窗关掉**
        dialogAnimOnEnd = null
        dialogAnimStart = System.currentTimeMillis()
        dialogAnimWasActive = true
    }

    /**
     * 动画期间把窗口内的 MC 控件藏起来（它们由 `super.render()` 统一画、塞不进插值矩形）；
     * **播完这一帧恢复**（检测「上一帧在跑、这一帧跑完」）。
     *
     * ⚠ 只负责开关**控件**；窗口**内容**（标题 / 预览 / 字段文字）由两个 render 各自判 [dialogAnimActive]。
     */
    private fun updateDialogAnimControls() {
        val active = dialogAnimActive()
        if (active) {
            val base = if (buySlot >= 0) buyWidgetBase else editWidgetBase
            if (base in 0 until children().size) {
                children().drop(base)
                    .filterIsInstance<net.minecraft.client.gui.widget.ClickableWidget>()
                    .forEach { it.visible = false }
            }
        } else if (dialogAnimWasActive) {
            val onEnd = dialogAnimOnEnd
            if (onEnd != null) {
                // 收回播完：真正关窗（清状态 + 重建界面）。控件交给重建，不再走下面的可见性恢复
                dialogAnimOnEnd = null
                dialogAnimClosing = false
                onEnd()
            } else if (editSlot >= 0) {
                // 展开播完：把可见性交回常规逻辑（编辑窗有整套判断；购买窗控件默认就是显示的）
                updateEditControlsVisibility()
            } else if (buySlot >= 0) {
                val base = buyWidgetBase
                if (base in 0 until children().size) {
                    children().drop(base)
                        .filterIsInstance<net.minecraft.client.gui.widget.ClickableWidget>()
                        .forEach { it.visible = true }
                }
            }
        }
        dialogAnimWasActive = active
    }

    // ── 商城格子 添加 / 编辑窗口（2026-09-20 用户口述；**判 points.shop 节点**，见 [canEditShop]）──

    /** 正在编辑的格子**全局序号**（跨页递增，不是页内下标）；-1 = 窗口没开 */
    private var editSlot = -1

    // ── 商城格子 购买窗口（2026-09-25；**任何人点有内容的格子**都是它，见 [openBuyDialog]）──
    //
    // 布局照「OP 添加/编辑精灵窗」那一套（用户 2026-09-25 拍板），但**只读** ——
    // 同样的字段、同样的位置（共用 [posOf] / [editColX] 等坐标函数），只是控件换成文本。
    // ⚠ 「格子长大 → 弹窗」的过渡动画**两个窗口都没有做**（用户 2026-09-25 拍板先不做），
    //   是真的"点一下就出现"；以后要补就是两个窗口一起补，别只补一边。

    /** 购买窗开着的格子**全局序号**；-1 = 没开。⚠ 与 [editSlot] **互斥**（同时只开一个窗口） */
    private var buySlot = -1

    /**
     * 这家店现在是否「正在被编辑」（服务端广播，见 [onShopEditLock]）。
     *
     * 编辑期间整店暂停购买：购买窗的「购买」按钮置灰、点击只响失败音并提示。
     * ⚠ 服务端 `PointMarketService.buy` **也拦** —— 这里只是界面反馈，防伪造包靠服务端。
     */
    private var shopEditLocked = false

    /** 编辑窗开着时每 30 秒发一次 `editing=true` 当心跳（服务端 15 分钟收不到才按超时释放） */
    private var lastEditHeartbeat = 0L

    // ── 自定义购买店：商品搜索（2026-10-26）──
    //
    // 商店最多 64 页，客户端一次只加载一页 ⇒ 搜索由**服务端扫全店**；客户端负责把搜索词
    // 在本地名称表里解析成候选 id（服务端语言 ≠ 客户端语言），再按 32 条一页取结果。

    /** 搜索框（只有自定义普通购买店建；所有玩家可见） */
    private var shopSearchField: TextFieldWidget? = null
    private var searchText = ""
    /** 搜索结果模式：网格显示命中项，而不是当前普通页 */
    private var searchMode = false
    private var searchResults: List<PointMarketSlotInfo> = emptyList()
    private var searchPage = 0
    private var searchTotalPages = 1
    private var searchTotal = 0
    /** 上次发出的搜索（`kind|text`）与时刻 —— 客户端防抖，别每帧发包 */
    private var lastSearchSent = ""
    private var lastSearchSentAt = 0L
    /** 名称表缓存（id → 小写本地化名称）；物品 / 物种各一份，懒加载 */
    private var itemNameIndex: List<Pair<String, String>>? = null
    private var speciesNameIndex: List<Pair<String, String>>? = null

    /**
     * 打开购买窗时那一条的 **id**（见 [ShopEntry.entryId]）。
     * 点购买时带回服务端比对；也用来判断"这格有没有被换过内容"（见 [checkBuySlotStillValid]）。
     */
    private var buySlotEntryId = ""

    /** 购买数量。**只对物品格有意义** —— 精灵格恒按 1 算（买几次就请求几次，服务端同样口径） */
    private var buyCount = 1
    private var buyCountField: TextFieldWidget? = null

    private var buyConfirmButton: NineSliceButton? = null
    private var buyCancelButton: NineSliceButton? = null

    /** 数量非法时的红字（画在按钮行的空位里；同拍卖出价弹窗那套） */
    private var buyHintMsg: Text? = null
    private var buyHintUntil = 0L

    /** 本窗口这批控件的起始下标 —— 关窗重建时从这里往后清（同 [editWidgetBase]，两者的理由一样） */
    private var buyWidgetBase = 0

    /**
     * 开窗后**自动叫一声**的时刻（0 = 不用叫）。
     * 与 [editCryAt] 同理：必须等渲染之后再播（`addFirstAnimation` 依赖 draw 里才建好的模型）。
     */
    private var buyCryAt = 0L

    /** 预览用的精灵图标状态（购买窗自己一份，别与编辑窗共用 —— 两个窗口不会同时开，但共用会串味） */
    /**
     * 购买窗预览的姿态状态 —— 走 [PreviewStateHolder]：**renderable 换了就整个换新**，
     * 否则会把上一只的模型 / 动作带过来（用户 2026-09-26 实测「新那只模仿旧那只的动作」）。
     */
    private val buyPreviewState = PreviewStateHolder()

    /**
     * 预览图标的屏幕位置 —— **渲染时记录**（见 [renderBuyPreview]），关窗时拿去当庆祝动画的起点。
     *
     * ⚠ 别在别处按坐标函数重算：`Origin.ofIcon` 有个 `centerAnchorY` 开关（渲染默认 false = 顶端锚定），
     * 重算时传错就是**起点偏下半个槽位**（2026-09-25 用户实测踩到）。
     * 拍卖场那边的注释说得对：「渲染时记录 = 永远和实际画出来的一致」。
     */
    private var buyIconOrigin: com.shusheng.cobblemarket.client.PokemonCelebrationAnimation.Origin? = null
    private var buyItemIconOrigin: com.shusheng.cobblemarket.client.ItemCelebrationAnimation.Origin? = null

    /**
     * 购买窗名字行那枚「非法」印章的**屏幕矩形** `[x, y, w, h]` ——
     * **渲染时记录**（[renderBuyNameLines]），悬停判定用（[renderBuyPokemonFields] 末尾）。
     * null = 这一格没标非法 / 不是精灵格。
     *
     * ⚠ 那枚图标是**自绘**的（不是控件）⇒ 没有内置 tooltip，位置只能自己记
     *（项目的既有惯例：渲染时记录 = 与实际画出来的必然一致）。
     */
    private var buyIllegalBadgeRect: IntArray? = null

    /** 提交失败提示（窗口内、[EDIT_HINT_MS] 毫秒）：画在标题右侧的空位，见 [renderEditDialogBackground] */
    private var editHintMsg: Text? = null
    private var editHintUntil = 0L

    // ── 配方快照弹窗：位置与开关 ──
    //
    // ⚠ 位置函数**渲染与命中判定共用**（各算一份必然错位，本项目反复踩过）

    /** 「保存当前配置」那一行的 y：与添加窗的物种搜索框同一行（标题下面第一条） */
    private fun snapshotSaveRowY(): Int = editDialogY() + EDIT_SPECIES_Y

    /** 底部「关闭」那排的 y：与添加窗的按钮行对齐 */
    private fun snapshotCloseRowY(): Int = editButtonRowY()

    /** 名字输入框的宽度（窗口宽减去「保存」按钮与内边距） */
    private fun snapshotNameFieldW(): Int = editDialogW() - EDIT_PAD * 2 - SNAPSHOT_SAVE_BTN_W - 6

    /** 列表第 [index] 条的 y（已算进滚动；渲染与命中判定共用） */
    private fun snapshotRowY(index: Int): Int =
        snapshotSaveRowY() + EDIT_ROW_H + 10 + (index - snapshotScroll) * SNAPSHOT_ROW_H

    /**
     * 列表一屏能放几条。
     *
     * ⚠ 底部必须**先扣掉 [SNAPSHOT_MORE_RESERVE]**（「还有 N 条」那行灰字占的高度）——
     *   行数 = 可用高度 / 行高 取整，余数不够时最后一行就压到灰字上（2026-09-25 用户报：
     *   「和当前显示的最后一条重合一部分」）。只把灰字往下挪治不了本 —— 换个窗口高度，余数一变又重合。
     *
     *   扣 12 之后，最后一行的**文字**底部距灰字顶部恒 ≥ 4px：行高 16、字高 8，行内上下各留 4px 白边，
     *   所以余数 ≥ 12 时行数**不变**（那时本来也不重叠），余数 < 12 时才少放一行 —— 正是会重叠的那半情况。
     */
    private fun snapshotVisibleRows(): Int =
        ((snapshotCloseRowY() - (snapshotSaveRowY() + EDIT_ROW_H + 10) - SNAPSHOT_MORE_RESERVE) / SNAPSHOT_ROW_H)
            .coerceAtLeast(1)

    /** 列表最大可滚到第几条（不够一屏时为 0） */
    private fun snapshotMaxScroll(): Int =
        (PointMarketCache.snapshots.size - snapshotVisibleRows()).coerceAtLeast(0)

    /** 每一行右侧那个「×」删除区的宽度 */
    private fun snapshotDeleteW(): Int = 14

    /** 快照的**默认名字**（用户 2026-09-25 定：默认自动生成、可手改） */
    private fun defaultSnapshotName(): String = if (isPokemonSlot()) {
        val species = editSpecies ?: return ""
        val lv = editLevelText.trim().toIntOrNull() ?: 1
        val shiny = (editShinyText.trim().toFloatOrNull() ?: 0f) > 0f
        SpeciesText.translated(species).string + " Lv.$lv" + if (shiny) " ★" else ""
    } else {
        if (editItemId.isEmpty()) "" else itemDisplayName(editItemId)
    }

    /**
     * 打开快照弹窗。
     *
     * ⚠ 它**叠在添加窗之上**：打开时把添加窗那批控件隐藏掉（同 [openOptionList] 的做法），
     *   关掉再恢复 —— 两批控件同时留在 children 里会互相拦截点击（列表永远点不到，本项目踩过）。
     */
    private fun openSnapshotDialog() {
        snapshotDialog = true
        snapshotScroll = 0
        hoveredSnapshot = -1
        // 预填自动名（服主想改就当场改）
        snapshotDraftName = defaultSnapshotName()
        // 拉一次最新列表：**别的持节点的人可能刚存过**，本地缓存不一定新
        sendToServer(RequestShopSnapshotsPayload())
        updateEditControlsVisibility()
        rebuildSnapshotDialog()
    }

    /**
     * 打开「需要前置条件的形态」说明界面（编辑窗右上角那枚 info 点的）。
     *
     * ⚠ **必须把自己（本实例）传过去** —— 服主是**在编辑窗配到一半**时点进来的，
     *   在那边新建一个 [PointsMarketScreen] 再回来，编辑窗连同草稿就全没了。
     *   [GatedFormScreen] 那边按"回原实例"处理，`init()` 会照状态把编辑窗重建出来。
     */
    private fun openGatedFormScreen() {
        client?.setScreen(GatedFormScreen(this))
    }

    /** 关弹窗：卸掉它自己那批控件 + 把添加窗的控件恢复回来 */
    private fun closeSnapshotDialog() {
        snapshotDialog = false
        snapshotScroll = 0
        // ⚠ 滚动条可能**正被拖着**（关弹窗时鼠标还按着）—— 状态一并复位，否则留个"半拖"状态
        ScrollBar.shared.cancel()
        hoveredSnapshot = -1
        // ⚠ **顺手清掉失败提示**：它是**这个弹窗自己的**（保存失败时由 [failSubmit] 设的），
        //   关窗后还留着会盖到添加窗的元素上 —— 提示「只该长在产生它的那个界面里」
        //   （2026-09-25 用户报：回到上一个界面后提示还在）
        editHintMsg = null
        clearSnapshotWidgets()
        updateEditControlsVisibility()
    }

    /**
     * 卸掉弹窗自己那批控件（名字框 / 保存 / 关闭）；列表行是**自绘**的，不用管。
     *
     * ⚠ 用**显式列表**清，别用「记下 children 下标、往后 `drop`」那一套（[editWidgetBase] 的写法）：
     *   下标在窗口 resize 重建时会失效 —— [buildEditDialog] 会把 `editWidgetBase` 之后的控件
     *   （快照这批也在其中）整个卸掉重建成新的，旧下标这时指向的是**新添加窗的控件**，再 drop 就把它们删了。
     *   ⚠ 旧的写法 2026-09-25 已实打实踩过一次：初值写成 0，**首次**建弹窗 `drop(0)` 把添加窗控件全删光 ——
     *   弹窗盖着时看不出来，一关就「界面元素全没了」（用户报）。
     */
    private fun clearSnapshotWidgets() {
        snapshotButtons.forEach { remove(it) }
        snapshotButtons.clear()
        snapshotNameField?.let { remove(it) }
        snapshotNameField = null
    }

    /** 建弹窗的控件（名字框 / 保存 / 关闭）；列表行是**自绘**的，见 [renderSnapshotDialog] */
    private fun rebuildSnapshotDialog() {
        clearSnapshotWidgets()

        val y = snapshotSaveRowY()
        snapshotNameField = TextFieldWidget(
            textRenderer, editDialogX() + EDIT_PAD, y, snapshotNameFieldW(), EDIT_ROW_H, Text.literal("")
        ).also {
            it.setMaxLength(PointShopSnapshotState.MAX_NAME_LENGTH)
            it.text = snapshotDraftName
            it.setChangedListener { t -> snapshotDraftName = t }
            addSelectableChild(it)
            addDrawableChild(it)
        }
        snapshotButtons += NineSliceButton(
            editDialogX() + EDIT_PAD + snapshotNameFieldW() + 6, y, SNAPSHOT_SAVE_BTN_W, EDIT_ROW_H,
            Text.translatable("cobblemarket.points.store.snapshot_save"), { saveCurrentAsSnapshot() }
        ).also { addDrawableChild(it) }
        snapshotButtons += NineSliceButton(
            editDialogX() + editDialogW() - EDIT_PAD - EDIT_BUTTON_W, snapshotCloseRowY(),
            EDIT_BUTTON_W, EDIT_BUTTON_H,
            Text.translatable("cobblemarket.points.store.buy_close"), { closeSnapshotDialog() }
        ).also { addDrawableChild(it) }
    }

    /**
     * 把当前草稿存成快照（名字取输入框；**重名由服务端自动加序号**，绝不覆盖 —— 见 `PointShopSnapshotState.add`）。
     * ⚠ 存的是**当前草稿**：价格 / 库存缺了会被 [buildShopEntry] 挡下（快照里也要带这两项）。
     */
    private fun saveCurrentAsSnapshot() {
        val name = snapshotDraftName.trim()
        if (name.isEmpty()) {
            failSubmit("snapshot_name_required")
            return
        }
        // ⚠ **逐项挡、走 [failSubmit]**：`buildShopEntry` 只回 null、不区分缺哪一项，
        //   之前这里三处都只 `playFailSound()` —— 服主点保存只有一声闷响，不知道哪儿没填
        //   （2026-09-25 用户报）。顺序与 [submitEdit] 一致：价格 → 物种 / 物品
        // ⚠ 领取店没有价格框（价格固定 0），跳过这一关
        if (!isClaimShop) {
            val price = editPriceField?.text?.trim()?.toLongOrNull()
            if (price == null || price < 0) {
                failSubmit("price_required")
                return
            }
        }
        if (slotCooldownShown() && editCooldownHours() == null) {
            failSubmit("cooldown_invalid")
            return
        }
        val entry = buildShopEntry() ?: run {
            if (isPokemonSlot()) failSubmit("species_required") else failSubmit("item_required")
            return
        }
        sendToServer(SaveShopSnapshotPayload(name.take(PointShopSnapshotState.MAX_NAME_LENGTH), entry))
    }

    /**
     * 用某条快照**填充当前草稿**（服主再改想改的那几项，然后照常添加）。
     *
     * ⚠ 走 [fillDraft] —— 它本来就是"把一份完整规格填进草稿"，与当年"编辑已有格子"用的是同一套。
     *   填完**要重建控件**（值都在控件上，不重建就还是空的）。
     * ⚠ 同时把 `editOriginal` 设成快照那份：`buildPokemonSpec` 基于它 `copy()`，
     *   界面**没接的项**（只剩昵称了 —— EV / 证章 / 极巨等级后来都补上了控件）才不会被抹掉。
     */
    private fun applySnapshot(snapshot: ShopSnapshotInfo) {
        editSpecBase = snapshot.entry
        // 价格 / 库存 / 冷却框的**暂存文本优先于 editSpecBase**（见 buildEditDialog 的 ifEmpty）——
        // 编辑已有格子时它们已经被原值填满，不清掉的话快照里的这三项不会生效。
        editPriceText = ""
        editStockText = ""
        editCooldownText = snapshot.entry.cooldownHours.takeIf { it > 0 }?.toString().orEmpty()
        fillDraft(snapshot.entry)
        closeSnapshotDialog()
        // 值都落在控件上，必须重建才看得到；走 init 那条路（它认得 editSlot，会把窗口建回来）
        clearChildren()
        init()
        // 点击音不在这里播 —— 它属于**点击事件**（点行填充、点「×」删除都要出声），
        // 统一由 mouseClicked 的快照分支播一次，见那里
    }

    /**
     * 快照弹窗的内容（背景由 [renderEditDialogBackground] 画好了）。
     * 上一栏是「名字框 + 保存当前配置」，下面列已有的快照 —— 行**自绘**（要悬停高亮 + 右侧「×」）。
     */
    private fun renderSnapshotDialog(context: DrawContext, mouseX: Int, mouseY: Int) {
        val centerX = editDialogX() + editDialogW() / 2
        val listTop = snapshotSaveRowY() + EDIT_ROW_H + 10
        val snapshots = PointMarketCache.snapshots
        if (!PointMarketCache.snapshotsLoaded) {
            context.drawCenteredTextWithShadow(
                textRenderer, Text.translatable("cobblemarket.points.store.snapshot_loading"),
                centerX, listTop + 6, 0x888888
            )
            return
        }
        if (snapshots.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer, Text.translatable("cobblemarket.points.store.snapshot_empty"),
                centerX, listTop + 6, 0x888888
            )
            return
        }
        // 先扫一遍命中（下面画的时候要用），每帧重算
        hoveredSnapshot = -1
        val rowLeft = editDialogX() + EDIT_PAD
        val rowW = editDialogW() - EDIT_PAD * 2
        val rows = snapshotVisibleRows()
        val last = minOf(snapshots.size, snapshotScroll + rows)
        for (i in snapshotScroll until last) {
            val y = snapshotRowY(i)
            if (mouseX >= rowLeft && mouseX < rowLeft + rowW && mouseY >= y && mouseY < y + SNAPSHOT_ROW_H) {
                hoveredSnapshot = i
            }
        }
        for (i in snapshotScroll until last) {
            val snapshot = snapshots[i]
            val y = snapshotRowY(i)
            val hovered = i == hoveredSnapshot
            if (hovered) context.fill(rowLeft, y, rowLeft + rowW, y + SNAPSHOT_ROW_H, 0x40FFFFFF)
            // 名字（截断；右边给「×」留位置）
            context.drawTextWithShadow(
                textRenderer,
                TextUtil.truncateStyled(Text.literal(snapshot.name), rowW - snapshotDeleteW() - 4),
                rowLeft + 2, y + (SNAPSHOT_ROW_H - 8) / 2, 0xFFFFFF
            )
            // 「×」只在悬停那一行画（平时整屏都是叉太吵）
            if (hovered) {
                context.drawCenteredTextWithShadow(
                    textRenderer, Text.literal("×"),
                    rowLeft + rowW - snapshotDeleteW() / 2, y + (SNAPSHOT_ROW_H - 8) / 2, 0xFF8888
                )
            }
        }
        // 滚动条（贴列表右缘外 3px，与选项列表**同一个口径**）—— 走共用的 [ScrollBar]：
        // **可拖动** + 点轨道跳转。⚠ 它与下面那句「还有 N 条」的灰字**各管一半**，都要有：
        // 灰字说"下面还有内容"，滚动条说"你在哪儿、还能拖多远"
        ScrollBar.shared.draw(
            context,
            rowLeft + rowW + 3, listTop, rows * SNAPSHOT_ROW_H,
            snapshots.size, rows, snapshotScroll
        )
        // 还有没显示完的：底下给个灰字提示（也提示能滚）
        if (snapshotMaxScroll() > 0) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(
                    "cobblemarket.points.store.snapshot_more",
                    (snapshots.size - snapshotScroll - rows).coerceAtLeast(0)
                ),
                centerX, snapshotCloseRowY() - 12, 0x888888
            )
        }
    }

    /**
     * 该格原本的完整内容（新增时为 null）。
     *
     * ⚠ 提交时**基于它 `copy()`**，只覆盖窗口接了的字段 —— 界面没接的高级项
     * （EV / 亲密度 / 昵称 / 证章 / 极巨等级 / 物品组件…）必须原样保留，
     * 否则「什么都不改直接保存」会把这些悄悄抹掉（全量覆盖式表单的铁律，本项目有前科）。
     */
    private var editSpecBase: ShopEntry? = null

    /**
     * 本次编辑窗是不是在**编辑已有的格子**（点有内容的格子进来）。
     *
     * 决定：标题「编辑 / 添加」、按钮「保存 / 添加」、右上角那枚「下架」建不建。
     * ⚠ **不能用 `editSpecBase != null` 判** —— 点空格子后应用一个快照也会把它填成非 null，
     *   那时仍然是「添加」，标题 / 按钮不该变成「编辑 / 保存」（2026-09-25 用户报过同类误判）。
     */
    private var editEditingSlot = false

    // 窗口控件引用（关闭时清空；resize 后靠它们恢复草稿）
    private var editSpeciesField: TextFieldWidget? = null
    private var editLevelField: TextFieldWidget? = null
    /** 极巨等级输入框（0 ~ 服主配的 `maxDynamaxLevel`）——「所有精灵都能极巨化」那一项 */
    private var editDmaxField: TextFieldWidget? = null
    private var editPriceField: TextFieldWidget? = null
    private var editStockField: TextFieldWidget? = null
    /** 格子冷却输入框（小时；空白 / 0 = 跟随商店）—— 只有自定义普通购买店才建 */
    private var editCooldownField: TextFieldWidget? = null
    /** 格子冷却的暂存文本（输入框重建时恢复，同价格 / 库存框那套） */
    private var editCooldownText: String = ""
    private var editSpeciesPrev: NineSliceButton? = null
    private var editSpeciesNext: NineSliceButton? = null

    /** 清空搜索框（搜索框右侧第一枚，用户 2026-09-20 加）；与两枚箭头一起藏，见 [updateEditControlsVisibility] */
    private var editSpeciesClear: NineSliceButton? = null

    /** 添加窗右上角那枚「快照」（关窗时随其余控件一起清） */
    private var editSnapshotButton: NineSliceButton? = null

    /**
     * 按钮组最左那枚**形态警告**（`⚠`）—— 点开 [GatedFormScreen] 看完整说明。
     *
     * ⭐ **常驻**（用户 2026-09-30 定）：判据 [SpeciesText.hasGatedAspect] 是**硬编码清单**
     * （72 个 aspect），**自制 / 魔改数据包加的战斗形态不在里面** ⇒ 靠"命中才出现"的话那些形态
     * 永远不会有提示。改成常驻后入口一直在，服主随时能点开看。
     * ⚠ 但**颜色分两态**：草稿里真选中那类形态才染红（[GATED_WARN_COLOR]），否则常规白 ——
     *   红色常驻会被读成"当前配置有问题"。
     *
     * ⚠ **颜色每帧在 [render] 里刷**（同 [prevButton] 那套写法）：判据是草稿里的形态，而改动形态的
     *   路子有十几处（选项列表点选 / 快照回填 / 换物种 / 清空），逐个补事件必然漏。
     *   **可见性**则交给 [updateEditControlsVisibility]（常驻 ⇒ 只在叠层打开时藏）。
     * ⚠ 物品窗没有形态 ⇒ 判据恒假、颜色恒白，且那枚按钮**本来就不建**（见 `buildEditDialog`）。
     *
     * ⚠ **位置在按钮组最左端**（原先贴着「添加」—— 一轮实测后用户定的："那个角落太空、
     *   按钮突然冒出来根本注意不到；贴着服主配完必然要点的「添加」才看得见"；2026-09-30 加
     *   「非法」时整组改成居中排，它成了组里第一枚）。
     */
    private var editGatedInfoButton: NineSliceButton? = null

    /**
     * 编辑窗那枚**常驻**的「非法」开关（**只有精灵窗有**）。
     *
     * 勾上 = 从这一格买到的精灵带上 `cobblemarket:illegal` 标记，**将来对战禁赛按它判**
     *（与自动打的 `shop` 来源标记分工不同，见 [PokemonTags]）。
     *
     * ⚠ 它**不藏在任何判据后面**（不像 info 那样"选中战斗形态才出现"）——
     *   常驻是用户 2026-09-30 明确要求的：警告判据只有「战斗形态」一维，漏了标记就漏。
     */
    private var editIllegalButton: NineSliceButton? = null

    /**
     * 右上角那枚**「解锁特性 / 技能池」**开关（**只有精灵窗有**，紧挨「快照」左侧）。
     *
     * 选中 = 特性 / 技能候选不再受物种限制，且**自动带上非法标记** —— 详见 [editFreePick]。
     * ⚠ 界面上**没有文字**：状态靠 [FREE_PICK_LOCK_ICON] / [FREE_PICK_UNLOCK_ICON] 两张图表达
     *（用户 2026-09-30 定 —— 原先那版写「自由」两个字，用户的评价是"不准确"）。
     */
    private var editFreePickButton: NineSliceButton? = null

    // ── 配方快照弹窗（服主用；点添加窗右上角那枚「快照」开）──
    //
    // 为什么要有它（2026-09-25 用户定）：不再能编辑已有格子，改内容走「下架 → 重新添加」——
    // 没有快照就得把二十来个字段重配一遍。有了它，「重新添加」=「选快照 → 改想改的 → 添加」。
    // 弹窗**合成一个**（用户拍板）：上面一栏「保存当前配置」（预填自动名、可改），下面列出已有的。

    /** 快照弹窗开着没 */
    private var snapshotDialog = false

    /** 「保存当前配置」那个名字框的内容（重建时靠它恢复 —— 同 [editPriceText] 那套） */
    private var snapshotDraftName = ""
    private var snapshotNameField: TextFieldWidget? = null

    /** 弹窗里的按钮（关窗时统一清） */
    private val snapshotButtons = mutableListOf<NineSliceButton>()

    /** 列表滚到第几条（超过一屏才有效果） */
    private var snapshotScroll = 0

    /** 列表里鼠标悬停在哪一条（-1 = 没有）；自绘行要高亮，得自己记 */
    private var hoveredSnapshot = -1

    private var editConfirmButton: NineSliceButton? = null
    private var editCancelButton: NineSliceButton? = null
    private var editRemoveButton: NineSliceButton? = null

    /**
     * 开窗 / 换物种后**自动叫一声**的时刻（0 = 不用叫）。
     * 必须等渲染之后再播 —— `addFirstAnimation` 依赖 draw 里才建好的模型，见 [IconCryClick]。
     */
    private var editCryAt = 0L

    // 物种搜索（照搬黑名单那套：精确优先的候选 + 箭头切换 + 预览槽）
    private var editSpecies: Species? = null
    private var editSpeciesCandidates = listOf<Species>()
    private var editSpeciesIndex = 0
    private var editPreview: RenderablePokemon? = null
    /** 编辑窗预览的姿态状态 —— 同 [buyPreviewState]，换物种时由 holder 自动换新 */
    private val editPreviewState = PreviewStateHolder()

    // ── 窗口草稿（字段与 ShopPokemonSpec 一一对应；提交时整份拼成 ShopEntry）──

    /** 形态 aspects（不含 `shiny` —— 闪光单独一栏管，免得两处打架） */
    private var editAspects = emptyList<String>()

    /**
     * 闪光概率的**输入框原文**（百分数，空 = 不闪）—— 它是闪光这一项的**唯一真值**，
     * 左边那枚按钮只是快捷档位（点一下把对应数值填进来）。
     * 支持小数（`0.5` = 千分之五），`100` 及以上按必闪处理（写进 aspects 的 `shiny`）。
     */
    private var editShinyText = ""
    private var editShinyField: TextFieldWidget? = null

    /** 性别：0 = 随机，1 = 雄，2 = 雌，3 = 无性别 */
    private var editGender = 0
    private var editNature = ""
    private var editAbility = ""
    private var editBall = ""

    /** 体型：0 = 随机，1..5 = XS/S/M/L/XL（提交时换算成 scale 倍率） */
    private var editSize = 0
    private var editAlpha = false
    private var editTera = ""
    private var editGmax = false
    private var editTradeable = true

    /**
     * 服主勾的「非法」—— **常驻开关**（用户 2026-09-30 拍板）。
     *
     * ⚠ **刻意不做成"命中战斗形态才出现"**：那枚上架警告只覆盖「战斗形态」**一维**，
     *   而服主只能靠它才知道要勾 ⇒ **提示漏了标记就漏**（用户原话：「我就怕你做的提示功能可能会遗漏」）。
     *   常驻 = 服主任何时候都能自己勾。
     * ⚠ 与 [KEY_FORM] 那枚 info 警告**各自独立，不做强制联动**（警告归警告、勾选归勾选）。
     */
    private var editIllegal = false

    /**
     * **「自由选择特性 / 技能」** —— 右上角那枚开关（**只有精灵窗有**，紧挨「快照」左侧）。
     *
     * 勾上 ⇒ 特性 / 技能的候选**不再受该物种限制**（改用 `Abilities.all()` / `Moves.all()`），
     * 于是能造出「有非法特性 / 非法技能」的精灵 —— 这正是这个开关存在的理由
     *（用户 2026-09-30：「既然 Cobblemon 本来就支持非法，不如把非法入口扩大」）。
     *
     * ## ⚠⭐ 与「非法」开关的联动（用户 2026-09-30 定）
     *
     * **不变式：自由 ⇒ 非法。**
     * - 勾上它 ⇒ **自动勾上「非法」**（放开限制造出来的必然非法，不该还能忘标）
     * - 取消「非法」⇒ **自动取消它**（否则会停在"自由开着、却没标非法"这种自相矛盾的状态上 ——
     *   那样造出来的非法精灵身上不会有任何标记）
     * ⇒ 两个方向的落地都在 [toggleFreePick] / [toggleIllegal] 里
     *
     * ⚠ 它**与形态警告仍然互不联动**（那条是"提醒你形态有风险"，这条是"我要造非法的"，
     *   见 [editIllegal] 的注释）。
     *
     * ## ⚠ 纯编辑态 —— 不进存档、不上 payload
     *
     * 精灵生成时只看 `ability` / `moves` / `illegal` 三个字段，**不看"当初是怎么选出来的"**
     *（⇒ 不需要给 [ShopPokemonSpec] 加字段，也不用动网络包）。
     * ⚠ 但**快照回填要能反推它**（见 [fillDraft]）：一只带非法特性 / 技能的快照回填进来时，
     *   若不置上它，末尾那两处 normalize 会把这些值当成"换物种后的残留"清掉。
     */
    private var editFreePick = false

    /** 携带物：**完整物品栈 NBT 的 SNBT 串**（与挂单 `heldItemNbt` 同口径）；null = 无 */
    private var editHeldItem: String? = null
    private var editHeldItemName = ""

    /** 保底 V 数（0 = 不保底；**只在六项个体值全空时生效**，与价格限制界面的口径一致） */
    private var editMinIvs = 0

    /**
     * 六项个体值 / 努力值的输入框原文（空 = 该项随机 / 0）。
     * ⚠ 个体值：**整组全空**时才回退到「保底 V 数」；任一项填了就按六项走（数据层两者互斥）。
     */
    private var editIvText = List(STAT_COUNT) { "" }
    private val editIvFields = mutableListOf<TextFieldWidget>()
    private var editEvText = List(STAT_COUNT) { "" }
    private val editEvFields = mutableListOf<TextFieldWidget>()

    /** 亲密度（空 = 默认）/ 携带物是否对他人可见 / 证章 id 列表（可挂多个） */
    private var editFriendshipText = ""
    private var editFriendshipField: TextFieldWidget? = null
    private var editHeldVisible = true
    private var editMarks = emptyList<String>()

    /**
     * **初始训练家（配信）** 输入框的暂存文本 —— 空 = 不设，
     * 生成的精灵初训家还是**买家本人**（见 [ShopPokemonSpec.originalTrainer]）。
     */
    private var editOriginalTrainerText = ""
    private var editOriginalTrainerField: TextFieldWidget? = null

    // 物品窗口选中的物品（id + 组件快照 + 显示名；显示名只是给人看的，不进存档）
    // ⚠ 一律走 [setEditItem] 赋值，别直接改这三个 —— 大图的缓存靠它刷新
    private var editItemId = ""
    private var editItemName = ""
    private var editItemComponents: NbtCompound? = null

    /**
     * 右侧那张**物品大图**要画的栈（从上面三个字段重建好的，见 [setEditItem]）。
     *
     * ⚠ 必须缓存：重建要反序列化组件快照（[rebuildItemStack]），**每帧现算**等于给每个渲染帧
     * 白加一次物品反序列化 —— 商城格子、购买窗那边都是缓存着画的。
     */
    private var editItemPreviewStack: ItemStack? = null

    /**
     * 大图**左侧**那列词条的缓存 —— 见 [itemInfoLines]。
     *
     * 键是「栈 + 展开档位」：栈换了（选了别的物品）或 Shift / Ctrl 状态变了才重建；
     * **两个都要比** —— 只比栈的话，按住 Shift 永远不会展开（档位变了栈没变）。
     */
    private var itemInfoCacheStack: ItemStack? = null
    private var itemInfoCacheType: TooltipType? = null
    private var itemInfoCacheLines: List<Pair<Text, Int>> = emptyList()

    /**
     * 物品窗的**物品搜索框** —— 用户 2026-09-26 定：**直接长在界面上**，不再是「物品：无」按钮点开列表。
     * 输入即展开候选列表（照添加精灵窗的物种搜索框那个形态）。
     */
    private var editItemSearchField: TextFieldWidget? = null

    /** 物品搜索框里的词（resize 重建时恢复输入用，同 [editSpeciesText] 那套） */
    private var editItemSearchText = ""

    /**
     * 物品窗「取手持物品」那枚小手 —— 常驻在**搜索框左侧**（用户 2026-09-26 点名要那个位置观感）。
     * ⚠ 它必须进 [updateEditControlsVisibility] 的**白名单**：列表展开时别的控件全藏，
     *   唯独搜索框与它得留着（藏了就点不到、也搜不了）。
     */
    private var editItemHeldButton: NineSliceButton? = null

    /**
     * 程序性设搜索框文本时置位 —— 挡掉 changedListener。
     *
     * 选中一条候选后要把框里的词换成**那件物品的名字**，而 `TextFieldWidget.text =` 会同步触发
     * 监听器 ⇒ 不挡的话这次"回填"又被当成用户在打字，列表立刻弹回来（选中即重开，死循环观感）。
     */
    private var editItemSearchSyncing = false

    /** 四个技能槽的逗号串（空槽是空串，见 [MoveText]） */
    private var editMoves = ""

    /**
     * 展开中的选项列表种类（形态 / 性格 / 特性 / 球 / 太晶 / 技能 / 携带物 共用一套）；
     * null = 没有列表展开。展开时**窗口其余控件全部隐藏** —— 它们先于列表添加，
     * 不隐藏的话点击会被它们拦截、列表永远点不到（黑名单那儿踩过）。
     */
    private var optionListKind: String? = null

    /** 该字段的**全部**候选项（搜索词为过滤前的全集） */
    private var optionListAll: List<OptionItem> = emptyList()

    /** 过滤后的候选项 —— 渲染、点击、滚动全都用它 */
    /** 上次建变体列表时用的候选表版本（见 [PointMarketCache.featureAspectsVersion]），见 `tick` */
    private var variantAspectsSeen = -1

    private var optionListItems: List<OptionItem> = emptyList()
    private var optionListScroll = 0

    /** 列表的搜索框（**只在列表展开期间存在**）与搜索词；纯本地过滤，不发包 */
    private var optionSearchField: TextFieldWidget? = null
    private var optionSearchText = ""

    /**
     * 「技能」列表的**来源筛选**（[SpeciesText.MoveSources] 里的桶键）；`null` = 「全部」。
     * ⚠ 只在技能列表上有意义（那排按钮也只在那儿显示），**开列表时复位** —— 与搜索词同一个口径。
     */
    private var moveSourceFilter: String? = null

    /** 上次建技能列表时用的招式表版本（见 [PointMarketCache.speciesMovesVersion]），见 `tick` */
    private var moveTableSeen = -1

    /**
     * 该列表是否**多选**（目前只有证章）。
     * 多选时点一行只切换勾选、**不关列表** —— 不然挂第二个章还得把按钮重新点开。
     */
    private var optionListMultiSelect = false

    /** 列表底部的「返回」按钮（关列表、回字段界面），随列表一起建/删 */
    private val optionListButtons = mutableListOf<NineSliceButton>()

    /**
     * 多选列表底部那枚「清空」（只有证章这种多选列表才有，其余列表恒为 null）。
     *
     * ⚠ 它的 `active` 得**跟着挂载状态走**：原来只在建按钮那一刻设过一次（`rebuildOptionList`），
     *   而打开列表时往往一个证章都没挂 → 按钮置灰，之后按「编辑证章」再挂满了也**一直是灰的、点不动**
     *   （2026-09-25 用户实测报「清空按钮是灰字且点了没效果」）。
     *   现在由 [refreshEditLabels] 统一刷新 —— 建列表 / 选 / 取消 / 清空四条路径本来就已经在调它。
     */
    private var optionClearButton: NineSliceButton? = null

    /**
     * 展开列表里的一行。
     *
     * [value] 是**游戏内的标识**（资源 id / 名字 / SNBT 串），[label] 只给人看；
     * [stack] 非空时行首画物品图标（球种、携带物这种「值本身就是一件物品」的字段）。
     * 图标在**开列表时预构建**，渲染时直接用 —— 每帧解析 SNBT 会掉帧。
     */
    private class OptionItem(
        val value: String,
        val label: String,
        val stack: ItemStack? = null,
        /**
         * 带样式的显示文本（**技能名要带属性色**）。
         * ⚠ 存成 [label] 那样的 String 会把样式丢干净 —— `displayName.string` 一转就没了
         * （2026-09-20 用户实测：技能列表里全是白字）。
         */
        val labelText: Text = Text.literal(label),
        /** 行首**贴图**图标（证章用，原图 32×32）；球种/携带物那种物品走 [stack] */
        val iconTexture: Identifier? = null,
        /**
         * 「变体」用：这一项属于哪个 feature 维度（注册键，如 `snake_pattern`）。
         * **同 key 互斥、跨 key 共存** —— 选中时只摘掉同 key 的旧值（见 [toggleFeatureAspect]）。
         * 其余字段全为 null（它们都是一维单选，没有分组这回事）。
         */
        val groupKey: String? = null,
        /**
         * 「变体」用：**该维度的标题行**（画成分节标题，不可点、不参与选择）。
         * ⚠ 它的 [value] 恒为空串，靠这个标记与「不指定」项区分开。
         */
        val isHeader: Boolean = false,
        /**
         * 「技能」用：这个招属于**哪些来源桶**（[SpeciesText.MoveSources]）——
         * 技能列表顶上那排筛选按钮按"属不属于"命中（**桶间不去重**：火牙三桶都有，
         * 在「等级」「招式机」「教学」三个筛选下都出得来）。
         *
         * ⚠ **空集 = 不属于任何桶**（「无」那一项、以及「自由选择」的全表那份）——
         *   空集**任何筛选下都显示**：否则选了某一桶之后「无」被筛掉，那一槽就再也清不掉了。
         */
        val sources: Set<String> = emptySet()
    )

    /**
     * 弹窗控件的**起始下标**：建弹窗时记下 `children().size`，之后新增的都是弹窗的。
     * 用途有两个 —— 重复开窗时清掉残留、列表展开时兜底隐藏（见 [updateEditControlsVisibility]）。
     */
    private var editWidgetBase = -1

    /** 字段按钮按 key 索引（刷新文案时逐个 setMessage；技能槽的 key 形如 `skill0`） */
    private val editButtons = mutableMapOf<String, NineSliceButton>()

    /**
     * 窗口内容的**滚动偏移**（屏幕放不下时才有值）。
     * 只有中间那坨字段网格随它动，价格/库存与按钮是**钉在窗口底部**的（不然够不着）。
     */
    private var editContentScroll = 0

    // 输入框原文：resize 会重建控件，不留下这几份就会清空（占位符那种小字丢得最明显）
    private var editSpeciesText = ""

    /**
     * 正在**恢复**物种搜索框的文本（重建控件时）—— 这期间的赋值不算「用户改了输入」。
     *
     * ⚠⚠ **必须有这道闸**：MC 的 `TextFieldWidget.setText` **会同步触发 changedListener**
     *   （口径同 [editItemSearchSyncing] 那条注释），而这里 listener 里跑的是 [updateEditSpecies]
     *   —— 它顺手把 `editSpeciesIndex` 归零、物种换成候选第一个。
     *   表现：「搜『喷火』→ 点箭头切到喷火驼 → 点开形态说明界面再回来 → **变回喷火龙**」
     *   （2026-09-30 用户实测）。
     * ⚠ 同一路径 **resize 也会走到**（resize → `init` → [buildEditDialog]），所以这坑在
     *   子界面出现之前就存在，只是没被撞上；⚠ 快照弹窗不走 `init`，所以它从来不触发。
     *
     * ⚠ 别把它加到 [fillDraft] 那条赋值上（回填物种）—— 那里**就是要**让候选跟着重算。
     */
    private var editSpeciesSyncing = false
    private var editLevelText = ""
    private var editDmaxText = ""
    private var editPriceText = ""
    private var editStockText = ""

    /** 本次进界面是否已请求过商城快照（入口按钮的显隐要等它回来） */
    private var shopInfoRequested = false

    /** 上次请求商品快照的时刻（客户端侧限流，配合服务端 500ms 节流窗口，见 [tick]） */
    private var lastPageRequestAt = 0L

    /** 「购买 RP」入口（显隐由商城快照决定，见 [onShopInfo]） */
    private var buyEntryButton: NineSliceButton? = null

    /** 背景等比缩放后的矩形（居中） */
    private data class BgRect(val x: Int, val y: Int, val w: Int, val h: Int)

    /** 兑换结果提示（界面内、5 秒）：Text 原样保存，见 [onMarketResult] 的注释 */
    private var resultMsg: Text? = null
    private var resultColor = 0x55FF55
    private var resultUntil = 0L

    /** 「兑换记录」入口（见 init 的注释：位置随「购买 RP」是否可见而变，永不重建界面） */
    private var recordButton: NineSliceButton? = null

    /** 返回按钮与兑换按钮：**弹窗期间要连带隐藏**，所以存成字段（原先都是匿名的） */
    private var backButton: NineSliceButton? = null
    private var redeemButton: NineSliceButton? = null

    /** 商店记录入口（出售 / 领取）：只有服主 / 持 `points.shop` 才建 */
    private var shopHistoryButton: NineSliceButton? = null

    /**
     * 摆放「兑换记录」按钮：
     * - 「购买 RP」可见 → 贴在它**左侧**
     * - 不可见（没配档位）→ **顶上它的位置**
     *
     * ⚠ 用 `bgRect()` 现算而不是缓存 init 里的局部量：档位快照到达时只调这个函数，
     * 不能重建界面（会清掉兑换框里已输入的内容）。
     */
    fun layoutRecordButton() {
        val btn = recordButton ?: return
        val showBuy = PointsShopCache.tiers.isNotEmpty()
        // 「购买 RP」左缘；不显示购买入口时，记录按钮**顶上它的位置**（各自按自己的宽度贴右缘）
        val rightEdgeX = bgRect().x + bgRect().w - borderInset()
        btn.x = if (showBuy) rightEdgeX - BUY_ENTRY_W - ROW_GAP - RECORD_ENTRY_W
        else rightEdgeX - RECORD_ENTRY_W
    }

    private fun bgScale(): Float {
        val availW = width * (1f - MARGIN_RATIO * 2f)
        val availH = height * (1f - MARGIN_RATIO * 2f)
        return min(availW / TEX_W, availH / TEX_H)
    }

    private fun bgRect(): BgRect {
        val scale = bgScale()
        val w = (TEX_W * scale).roundToInt()
        val h = (TEX_H * scale).roundToInt()
        return BgRect((width - w) / 2, (height - h) / 2, w, h)
    }

    /** 背景图边框厚度（1280×720 原图约 78px）缩放后的实际值：贴边控件要避开它 */
    private fun borderInset(): Int = (TEX_BORDER * bgScale()).roundToInt()

    /**
     * **原图坐标（1280×720 口径）→ 当前缩放下的逻辑像素**。
     * 用户给的尺寸都是照原图说的（「与顶部 28 像素」），换算只走这一个口子，
     * 免得各处各写一遍缩放、改一个位置要满文件找。
     */
    private fun texPx(value: Int): Int = (value * bgScale()).roundToInt()

    override fun init() {
        super.init()
        // 告诉缓存"现在在看哪个商店" —— 迟到的旧商店快照会被丢掉（见 PointMarketCache.viewingShopId）
        PointMarketCache.viewingShopId = shopId
        // 卡片购买折扣按玩家各算各的 ⇒ 打开界面单独问一次（购买窗的优惠提示读缓存）
        if (!cardDiscountRequested) {
            cardDiscountRequested = true
            sendToServer(RequestShopCardDiscountPayload(shopId))
        }
        // 换店时先把上一个商店的格子清掉：不清的话在新快照到达前会拿旧店数据画一帧
        if (PointMarketCache.shopId != shopId) PointMarketCache.clear()
        // 打开动画只在**界面实例第一次 init** 时启动；resize 会重跑 init，别让动画重播
        if (isCustomShop && !openAnimStarted) {
            openAnimStarted = true
            openAnimStart = System.currentTimeMillis()
        }
        val bg = bgRect()
        val inset = borderInset()
        val cx = bg.x + bg.w / 2

        // 返回按钮：主商城**底部居中**（原图口径）；自定义商店挪到**右上角**
        //（底部居中那个位置留给领取店的「领取」按钮 —— 用户 2026-10-23 要求）
        // ⚠ 自定义商店没有"入口界面"这一层：返回直接回游戏（用户 2026-10-20 拍板）
        val backX = if (isCustomShop) bg.x + bg.w - inset - BACK_W else cx - BACK_W / 2
        val backY = if (isCustomShop) redeemRowY(bg) else backButtonY(bg)
        backButton = NineSliceButton(
            backX, backY, BACK_W, BACK_H,
            Text.literal(""),
            {
                when {
                    !isCustomShop -> client?.setScreen(MarketEntryScreen(skipDropAnim = true))
                    else -> {
                        // 返回也走「打开动画的反向」：缩回右键 NPC 的位置后再切界面
                        val target: Screen? = if (returnToAdmin) CustomShopAdminScreen() else null
                        if (com.shusheng.cobblemarket.client.ClientConfig.marketAnimation &&
                            startCustomClose(target)
                        ) {
                            CloseAnimation.playCloseSound()
                        } else {
                            client?.setScreen(target)
                        }
                    }
                }
            },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        // 领取店的「一键领取全部」：占原本返回按钮的位置（底部居中）。
        // 尺寸 / 纵向位置**与左右两颗翻页按钮一致**（2026-10-24 用户要求：加宽加高，同一条底栏）。
        // 只放图标（手接礼物，用户 2026-10-24 提供的正式 48×48 像素图）不写文字；名称放进 tooltip。
        if (isClaimShop) {
            val btn = NineSliceButton(
                cx - PAGE_BTN_W / 2, pageRowY(bg), PAGE_BTN_W, PAGE_BTN_H,
                Text.literal(""),
                {
                    // 飞入动画的起点必须在**点击这一刻**快照：回执到达时格子可能已经翻页 / 界面已关
                    ItemClaimFlyAnimation.setPending(shopId, claimIconOrigins())
                    sendToServer(ClaimCustomShopPayload(shopId))
                },
                iconLeft = Identifier.of("cobblemarket", "textures/gui/button/claim.png"),
                iconTexW = 48, iconTexH = 48, iconScale = 1f / 3f,
                tooltip = Text.translatable("cobblemarket.points.store.claim")
            )
            claimButton = btn
            addDrawableChild(btn)
        }

        // 兑换区：**主商城独有**。兑换码 / 购买 RP / 兑换记录都属于「买 RP」那条线，
        // 摆在 NPC 店里既没用也误导（用户 2026-10-20 拍板：只留商品区）。
        // 商城快照（档位 / 有没有收款码）决定右上角那个入口显不显示；没请求过就请求一次
        //（init 会被 resize 反复调用，用标志挡住重复请求）
        if (!isCustomShop && !shopInfoRequested) {
            sendToServer(RequestPointsShopPayload(true))
            shopInfoRequested = true
        }

        val rowY = redeemRowY(bg)
        val fieldX = bg.x + inset
        val buyBtnX = bg.x + bg.w - inset - BUY_ENTRY_W
        val tabGroupW = TAB_W * 2 + TAB_GAP
        val tabLeftX: Int
        if (isCustomShop) {
            // 自定义商店的顶部只剩两个 tab —— 在整条可用区域里居中（不再给兑换行让位）
            tabLeftX = cx - tabGroupW / 2
        } else {
            // ⚠ 兑换行要挤下**四组**东西（输入框 / 兑换 / 双 tab / 购买），而背景是等比缩放、
            //   控件是固定逻辑像素 —— GUI scale 调大时逻辑分辨率变小（1080p 用 Auto 时只有 640×360，
            //   scale 更大时 480×270），背景缩了控件没缩就会挤成一团。
            //   所以输入框宽度按**剩余空间**算，压到下限为止。
            val rowNeed = REDEEM_BUTTON_W + ROW_GAP + tabGroupW + ROW_GAP + RECORD_ENTRY_W +
                ROW_GAP + BUY_ENTRY_W + ROW_GAP * 2
            val fieldW = minOf(REDEEM_FIELD_W, bg.w - 2 * inset - rowNeed).coerceAtLeast(MIN_REDEEM_FIELD_W)

            // 兑换码输入框（重建后恢复输入；上限按外部码带分隔符的 39 字符留余量）
            codeField = TextFieldWidget(textRenderer, fieldX, rowY, fieldW, ROW_H, Text.literal(""))
            codeField?.setMaxLength(MAX_CODE_INPUT)
            // ⚠ 占位提示**按输入框实际宽度挑**：原版画 placeholder **不做截断也不裁剪**，
            //   GUI scale 自动那一档输入框只有 24px 宽，长的「输入兑换码」会一路画到右边的
            //   「兑换」按钮上（2026-09-19 用户实测）。两条都放不下就干脆不显示 —— 那种宽度下
            //   任何字样都会溢出去（按钮本身就说明了这里是干什么的）。
            codeField?.setPlaceholder(
                listOf(
                    "cobblemarket.points.market.code_hint",
                    "cobblemarket.points.market.code_hint_short"
                ).asSequence()
                    .map { Text.translatable(it) }
                    .firstOrNull { textRenderer.getWidth(it) <= fieldW - 8 }   // 8 = 左右各 4px 内边距
            )
            codeField?.setChangedListener { pendingCodeText = it }
            codeField?.text = pendingCodeText
            addSelectableChild(codeField)
            addDrawableChild(codeField)

            // 兑换按钮：输入框右侧 4px（按钮间距规则）
            redeemButton = NineSliceButton(
                fieldX + fieldW + ROW_GAP, rowY, REDEEM_BUTTON_W, ROW_H,
                Text.translatable("cobblemarket.points.market.redeem_button"),
                { doRedeem() }
            )
            addDrawableChild(redeemButton)

            // 「购买 RP」入口：**与左边的兑换码输入框左右对称**（离各自一侧边框的距离都是 inset、
            // 同一水平线）—— 用户 2026-09-18 指出原来放在标题那一行"太右上"。
            // 只判档位、**不判收款码**：没配收款码时入口照样给，点进去弹窗里会提示缺什么（藏起来
            // 会让服主以为功能坏了）。按钮**总是建出来、靠 visible 开关**：快照到达时只改一个标志，
            // 重建整个界面会清掉玩家已经敲进兑换框的内容。
            val buyBtn = NineSliceButton(
                buyBtnX, rowY,
                BUY_ENTRY_W, ROW_H,
                Text.translatable("cobblemarket.points.recharge.entry", CurrencyService.pointsIcon()),
                { client?.setScreen(PointsRechargeScreen()) }
            )
            buyBtn.visible = PointsShopCache.tiers.isNotEmpty()
            buyEntryButton = buyBtn
            addDrawableChild(buyBtn)

            // 「兑换记录」按钮（用户 2026-09-19 定）：**放在「购买 RP」左侧**；购买入口不可见时
            // （没配档位）**顶上它的位置**。与购买入口同理**总是建出来、只改 x/visible** ——
            // 重建界面会清掉玩家已经敲进兑换框的内容。
            val recordBtn = NineSliceButton(
                buyBtnX - ROW_GAP - RECORD_ENTRY_W, rowY, RECORD_ENTRY_W, ROW_H,
                Text.translatable("cobblemarket.points.market.record_entry"),
                { client?.setScreen(PointsRecordScreen()) }
            )
            recordButton = recordBtn
            addDrawableChild(recordBtn)
            layoutRecordButton()

            // 精灵 / 物品 tab：与兑换行同一水平线，**在「兑换按钮右侧 ~ 购买按钮左侧」这段空档里居中**
            // （用户 2026-09-18 定的是"居中"，但死盯背景正中在小分辨率下会压到左边的兑换按钮上 ——
            //  改成居中于剩余空间后，大分辨率下位置与原来一致，小分辨率下自动让位）
            val tabMinLeft = fieldX + fieldW + ROW_GAP + REDEEM_BUTTON_W + ROW_GAP
            // 右边界要给**两个**按钮让位（购买 RP + 它左侧的兑换记录），各自按自己的宽度算
            val tabMaxLeft = buyBtnX - ROW_GAP - RECORD_ENTRY_W - ROW_GAP - tabGroupW
            tabLeftX =
                if (tabMinLeft <= tabMaxLeft) (cx - tabGroupW / 2).coerceIn(tabMinLeft, tabMaxLeft)
                // 兜底：极端小分辨率下这一行真的挤不下（按钮都已按文字宽度收到最小），
                // 只能让 tab 紧跟兑换按钮 —— 此时它会压到「兑换记录」上，属于「看得见但挤」，
                // 再往下就是字都看不清了，不如保持这个下限。
                else tabMinLeft
        }
        // 单类型商店没有 tab：直接是那一类的网格（2026-10-20 用户要求）
        if (fixedKind == null) {
            pokemonTabButton = NineSliceButton(tabLeftX, rowY, TAB_W, TAB_H, Text.literal(""), { switchTab(0) })
            itemTabButton = NineSliceButton(
                tabLeftX + TAB_W + TAB_GAP, rowY, TAB_W, TAB_H, Text.literal(""), { switchTab(1) }
            )
            addDrawableChild(pokemonTabButton)
            addDrawableChild(itemTabButton)
            updateTabButtons()
        }

        // 自定义普通购买店：顶部左侧的搜索框（**所有玩家**可见；跨全部页搜索）。
        // 记录按钮排在它之后 —— 只有服主 / 持 `points.shop` 的人建。
        val historyLabel = if (canEditShop()) {
            Text.translatable(
                if (isClaimShop) "cobblemarket.shop_history.claim_button"
                else "cobblemarket.shop_history.sale_button"
            )
        } else null
        val historyW = historyLabel?.let { (textRenderer.getWidth(it) + 12).coerceAtLeast(76) } ?: 0
        val searchW = if (isCustomNormalShop()) {
            // 左边到「居中的 tab」之间还剩多少 —— 搜索框别顶到 tab 上
            val available = tabLeftX - (bg.x + inset) - 12 -
                (if (historyW > 0) historyW + 6 else 0)
            available.coerceIn(56, 120)
        } else 0
        if (isCustomNormalShop()) {
            val field = TextFieldWidget(textRenderer, bg.x + inset, rowY, searchW, ROW_H, Text.literal(""))
            field.setMaxLength(32)
            field.setChangedListener { searchText = it }
            field.text = searchText
            field.setPlaceholder(
                TextUtil.fieldHint(
                    field,
                    "cobblemarket.points.market.search_hint",
                    "cobblemarket.points.market.search_hint_short"
                )
            )
            shopSearchField = field
            addSelectableChild(field)
            addDrawableChild(field)
        } else {
            shopSearchField = null
        }

        // 商店记录入口（出售 / 领取）：只有服主 / 持 `points.shop` 的人建。
        //  · 自定义商店：顶部左侧（搜索框之后；没有搜索框就贴左）
        //  · 主商城：顶部是兑换行、挤不下 ⇒ 底部左侧（返回按钮那一行）
        if (historyLabel != null) {
            val shopTitle = if (isCustomShop) Text.literal(customShopName.ifEmpty { shopId })
            else Text.translatable("cobblemarket.points.market.title", CurrencyService.pointsTitleIcon())
            val btn = NineSliceButton(
                bg.x + inset + (if (searchW > 0) searchW + 6 else 0),
                if (isCustomShop) rowY else backButtonY(bg),
                historyW, ROW_H, historyLabel,
                {
                    client?.setScreen(
                        HistoryScreen(
                            showAll = true,
                            shopId = shopId,
                            claimOnly = isClaimShop,
                            shopTitle = shopTitle,
                            returnScreen = this@PointsMarketScreen
                        )
                    )
                }
            )
            shopHistoryButton = btn
            addDrawableChild(btn)
        } else {
            shopHistoryButton = null
        }

        // 翻页按钮：与返回按钮同一行。**三个按钮按背景宽度三等分、各自在所属等分里居中**
        // （用户 2026-09-18）—— 返回按钮因此仍在正中间，位置不变
        val pageBtnY = pageRowY(bg)
        val third = bg.w / 3
        prevButton = NineSliceButton(
            bg.x + third / 2 - PAGE_BTN_W / 2, pageBtnY, PAGE_BTN_W, PAGE_BTN_H,
            Text.literal(""),
            { prevPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/previous.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.prev")
        )
        nextButton = NineSliceButton(
            bg.x + bg.w - third / 2 - PAGE_BTN_W / 2, pageBtnY, PAGE_BTN_W, PAGE_BTN_H,
            Text.literal(""),
            { nextPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/next.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.next")
        )
        addDrawableChild(prevButton)
        addDrawableChild(nextButton)

        // ⚠ resize 会把控件全部清掉（原版 `Screen.resize` → `clearChildren` + `init`）——
        //   窗口开着就按草稿重建一份，别让它凭空消失、主控件又冒回来
        if (editSlot >= 0) {
            hideMainControls()
            // ⚠ **基线归位**（2026-09-26 修）：resize 刚把 children 整个清空过，而 `editWidgetBase`
            //   还停在上一次建编辑窗时的数值 —— [buildEditDialog] 开头正是拿它判断"要不要清理旧控件"，
            //   这一轮主控件数只要比上次多，`drop(editWidgetBase)` 就会**误删刚建出来的那一个**
            //   （表现成某个主按钮凭空消失）。归到"当前已有控件数"后那个判断自然为假
            //   （`size in 0 until size`），清理分支只在真该清的时候（[openEditDialog] 那条路）才走
            editWidgetBase = children().size
            buildEditDialog()
        }
        // 购买窗同理 —— 不重建的话 resize 后它凭空消失、主控件又冒回来
        // ⚠ 数量框会回到 1：玩家改到一半的数量丢了可以接受（resize 本来就少见，
        //   为它单存一份草稿字段不值当），但别让窗口整个消失
        if (buySlot >= 0) {
            hideMainControls()
            buildBuyDialog()
        }
        // 快照弹窗同理。⚠ **必须排在 [buildEditDialog] 之后**：那一句会把 `editWidgetBase`
        // 之后建的控件全部卸掉，快照那批也在其中 —— 顺序反了就白建一场
        if (editSlot >= 0 && snapshotDialog) {
            rebuildSnapshotDialog()
            // 再藏一次编辑窗那批：buildEditDialog 把它们建出来时是可见的
            updateEditControlsVisibility()
        }
        // 展开的选项列表**同属这一类漏网**（2026-09-26 修）：它的「返回」/「清空」按钮与那条搜索框
        // 都不是 [buildEditDialog] 建的 ⇒ resize 清空 children 后不会自己回来。
        // 而**候选行是自绘的**、照常画 ⇒ 表现是「列表还在，但底下那枚「返回」没了」，只能按 Esc 退。
        // ⚠ 必须排在 [buildEditDialog] 之后（同上面快照弹窗那条）：那一句会卸掉 `editWidgetBase`
        //   之后建的控件，排在前面等于白建一场
        if (editSlot >= 0 && optionListKind != null) {
            buildOptionSearchField()
            rebuildOptionList()
        }
    }

    // ── 位置（**全部走逻辑像素**）──
    // ⚠ 控件尺寸（行高 16）是逻辑像素，而用户给的尺寸（边框 78、标题距顶 28）是原图口径 ——
    // 两者不能混着算：2026-09-18 把「输入框底 = 90 + 16」当真，结果分割线横穿了输入框。

    /** 兑换行（输入框 / 兑换按钮 / 两个 tab）顶边：内容区左上角再下移 [ROW_TOP_OFFSET] */
    private fun redeemRowY(bg: BgRect): Int = bg.y + borderInset() + ROW_TOP_OFFSET

    /** 返回按钮顶边：底边距背景底 BACK_BOTTOM（原图口径） */
    private fun backButtonY(bg: BgRect): Int = bg.y + bg.h - texPx(BACK_BOTTOM) - BACK_H

    /**
     * 底部「翻页 / 领取」那一行的顶边 —— **翻页按钮与领取按钮共用**，别各算一份。
     * （翻页按钮比返回按钮再上移 2px，领取按钮现在也贴这一行、同宽同高）
     */
    private fun pageRowY(bg: BgRect): Int = backButtonY(bg) - 2

    /**
     * 上分割线 y：**与兑换行解耦**（写在原兑换行位置的下方，不含 [ROW_TOP_OFFSET]）。
     * 这样把兑换行往下挪时分割线不动，两者的间距随之收窄到 `DIVIDER_GAP − ROW_TOP_OFFSET`。
     */
    private fun dividerTopY(bg: BgRect): Int = bg.y + borderInset() + ROW_H + DIVIDER_GAP

    /** 下分割线 y：返回按钮顶边上方 DIVIDER_GAP */
    private fun dividerBottomY(bg: BgRect): Int = backButtonY(bg) - DIVIDER_GAP

    /**
     * **点击空白失焦**（含 TextFieldWidget 的新界面必做，见 memory `feedback_gui_dialog`）：
     * 不做的话点空白退不出输入状态，E 键会被输入框吃掉、关不掉界面。
     * 先记「点之前焦点在不在输入框」再交给 super —— 让点击先落到控件上（点输入框内部不能误清）。
     */
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 打开 / 关闭动画播放中：控件还没画出来（或正在消失），别让点击落到网格 / 按钮上
        if (openAnimActive() || (isCustomShop && closeAnimStarted)) return true
        // 窗口开着：事件**只给窗口**（格子与主界面一律不响应；下层控件已 visible=false）
        if (editSlot >= 0) return handleEditDialogClick(mouseX, mouseY, button)
        if (buySlot >= 0) return handleBuyDialogClick(mouseX, mouseY, button)

        val wasInInput = focused != null && (focused === codeField || focused === shopSearchField)
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput &&
            codeField?.isMouseOver(mouseX, mouseY) != true &&
            shopSearchField?.isMouseOver(mouseX, mouseY) != true
        ) {
            focused = null
        }
        // 点格子（2026-10-26 定：左键 = 主操作「购买」，右键 = 权限者的「编辑」）：
        //  · 左键 —— **所有人**点有内容的格子开**购买窗**（与已发布行为一致）；空格子只有
        //    服主 / 持 `points.shop` 的人有动作 = 添加。
        //  · 右键 —— 服主 / 持 `points.shop` 的人点**有内容的格子**开**编辑窗**。
        //  · 领取店没有购买：左键（权限者）= 编辑 / 添加，普通玩家无反应；右键无反应。
        // ⚠ 判身份**只认 `canEditShop()`**：不认 OP，所以 OP 在这里跟普通玩家一样是买家。
        if (!result && (button == 0 || button == 1)) {
            val cell = cellAt(mouseX, mouseY, gridLayout(bgRect()))
            if (cell >= 0) {
                val slot = slotOfCell(cell)
                // 搜索结果页里超出命中数的格子没有真实序号 —— 别拿 -1 去开编辑窗
                if (slot < 0) return true
                val occupied = entryAt(slot) != null
                val admin = canEditShop()
                if (button == 0) {
                    if (occupied) {
                        // 领取店没有购买：权限者开编辑窗（补货 / 改可领数量），普通玩家无反应
                        if (isClaimShop) {
                            if (admin) openEditDialog(slot)
                            return true
                        }
                        openBuyDialog(slot)
                        return true
                    }
                    // 空格子：权限者 = 添加；普通玩家无反应
                    if (admin) {
                        openEditDialog(slot)
                        return true
                    }
                } else if (admin && occupied && !isClaimShop) {
                    // 右键 = 编辑（权限者的次级操作；领取店右键无反应）
                    openEditDialog(slot)
                    return true
                }
            }
        }
        return result
    }

    /** 窗口内的点击：控件优先 → 列表行 → 点空白失焦 → 点预览图叫一声 */
    private fun handleEditDialogClick(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 快照弹窗开着：事件只给它（列表行是自绘的，命中判定自己来）
        if (snapshotDialog) {
            val focusWasInput = snapshotNameField != null && focused === snapshotNameField
            val result = super.mouseClicked(mouseX, mouseY, button)
            // ⚠ 点输入框之外要**手动失焦**，否则 E 键被输入框吃掉、Esc 也关不掉窗口（项目老坑）
            if (focusWasInput && snapshotNameField?.isMouseOver(mouseX, mouseY) != true) focused = null
            // ⚠ 必须判 `!result`（控件没吃掉这次点击）—— 同下面选项列表那处。
            //   `hoveredSnapshot` 是**上一帧 render** 记下的：鼠标刚从列表行移到名字框、下一帧还没跑，
            //   它仍是旧值 ≥ 0 —— 不判 result 的话这次点输入框会被当成「点了那一行」，
            //   直接 applySnapshot 把弹窗关了、草稿也被覆盖（2026-09-25 用户报「有时点不进输入态」）
            if (!result && !focusWasInput && button == 0 && hoveredSnapshot >= 0) {
                PointMarketCache.snapshots.getOrNull(hoveredSnapshot)?.let { snapshot ->
                    // 快照行是**自绘**的（不走 NineSliceButton），点击音得自己播。
                    // 放在这里而不是 [applySnapshot] 里：**点「×」删除也要出声**，那条路不进 applySnapshot。
                    // ⚠ 必须用 playButtonClickSound（与按钮同档 0.25）—— 原先这里走的是 playMarketSound，
                    //   那是**服务端事件音效**、按素材响度给 0.9，响了 3.6 倍（2026-09-25 用户报）
                    playButtonClickSound()
                    val rowLeft = editDialogX() + EDIT_PAD
                    val rowW = editDialogW() - EDIT_PAD * 2
                    // 点右侧那个「×」区 = 删；点行别处 = 填充当前草稿
                    if (mouseX >= rowLeft + rowW - snapshotDeleteW()) {
                        sendToServer(DeleteShopSnapshotPayload(snapshot.name))
                    } else {
                        applySnapshot(snapshot)
                    }
                }
            }
            // ⭐ **滚动条**（在列表右缘外那一条）：按滑块 / 点轨道空白都直接改 scroll（点轨道 = 跳转）。
            //   ⚠ 排在**行判定之后**（同选项列表那条注释）；⚠ 还要判**确实装不下** ——
            //   [ScrollBar.draw] 在装得下时不记几何，命中会拿上一次残留的那份
            if (!result && button == 0 && PointMarketCache.snapshots.size > snapshotVisibleRows()) {
                ScrollBar.shared.mouseClicked(mouseX, mouseY, snapshotScroll)?.let {
                    snapshotScroll = it
                    return true
                }
            }
            return true
        }

        val focusWasInput = isEditInputFocused()
        val result = super.mouseClicked(mouseX, mouseY, button)
        // 展开的列表：行是**自绘**的（要画物品图标），命中判定得自己来
        if (optionListKind != null) {
            // ⚠ 搜索框**优先**：点在它上面就直接放行，不再往列表行上判 ——
            //   列表行的命中只看坐标，压在搜索框底下的那行会被误伤（点到输入框却选中了行）。
            //   物品窗那条搜索框**常驻在界面上**（见 [buildItemFields]），同样要放行
            if (optionSearchField?.isMouseOver(mouseX, mouseY) == true) return true
            if (editItemSearchField?.isMouseOver(mouseX, mouseY) == true) return true
            // 技能列表顶上那排**来源筛选按钮**（自绘）：命中就切档。
            // ⚠ **整条带子都要吃掉**（含按钮之间的空隙）—— 漏掉的话点空处会落到右边那张精灵大图上，
            //   精灵当场叫一声，看着像"按钮坏了"（见 [moveFilterBand]）
            if (showMoveFilterRow() && moveFilterBand().contains(mouseX.toInt(), mouseY.toInt())) {
                if (!result && button == 0) clickMoveFilterChip(mouseX, mouseY)
                return true
            }
            // ⚠ **只有真的点在列表行上才吃掉这次点击**：列表只占左半边，右边那块是精灵大图 ——
            //   原来这里无条件 `return true`，点精灵图标被列表整个吞掉、**叫都不叫**
            //   （2026-09-27 用户报「变体弹窗的精灵图标点击没反应」）。
            //   落在列表外（右侧预览 / 列表下方的空白）就放行，交给下面的预览点击判定
            if (hoveredOptionRow(mouseX, mouseY) >= 0) {
                if (!result && button == 0) clickOptionRow(mouseX, mouseY)
                focused = null
                return true
            }
            // ⭐ **滚动条**（在列表右缘外那一条）：按下滑块 / 点轨道空白都直接改 scroll，
            //   点轨道 = **跳转**（见 [ScrollBar] 的交互口径）。
            //   ⚠ 必须排在**上面那步行判定之后** —— 滚动条的命中区左右放宽过（滑块才 3px 宽，
            //   1:1 抓不住），压在列表右缘那一两像素会被它截胡；**行优先**才符合直觉
            //   ⚠ 还要判**这次列表确实装不下**：[ScrollBar.draw] 在装得下时**直接返回、不记几何**，
            //   此时命中用的是**上一次**留下的那份 ⇒ 会点出一个越界的位置（列表看着空了）。
            //   铺开其余界面时发现样板漏了这条，一并补上
            if (button == 0 && optionListItems.size > optionListRows()) {
                ScrollBar.shared.mouseClicked(mouseX, mouseY, optionListScroll)?.let {
                    optionListScroll = it
                    return true
                }
            }
        }
        // 点在输入框之外 → 手动失焦（否则 E 键被输入框吃掉、Esc 也关不掉窗口）
        if (focusWasInput && !isOverEditInput(mouseX, mouseY)) focused = null
        // 点预览槽 = 点精灵图标（既有标配：点一下就随机叫一声）
        if (button == 0 && isPokemonSlot() && overEditPreview(mouseX, mouseY)) {
            editPreview?.let { IconCryClick.play(editPreviewState.stateFor(it), it) }
        }
        return result
    }

    /** 鼠标在第几个**可见行**上（-1 = 不在任何行上）—— 渲染的悬停高亮与点击判定共用它 */
    private fun hoveredOptionRow(mouseX: Double, mouseY: Double): Int {
        for (i in 0 until optionListRows()) {
            val r = optionRowRect(i)
            if (mouseX >= r.x && mouseX < r.x + r.w && mouseY >= r.y && mouseY < r.y + r.h) return i
        }
        return -1
    }

    /** 点展开列表的某一行；命中就按单选 / 多选处理 */
    private fun clickOptionRow(mouseX: Double, mouseY: Double): Boolean {
        val i = hoveredOptionRow(mouseX, mouseY)
        if (i < 0) return false
        optionListItems.getOrNull(optionListScroll + i)?.let { item ->
            // 分节标题（「变体」的 feature 名那一行）**只是一行字**：点了不做事，**也不出声** ——
            // 出声会让人以为选上了（同头目之证那枚"点了不响应"的道理）
            if (item.isHeader) return true
            // 选项行是**自绘**的（不走 NineSliceButton），点击音得自己播 ——
            // 缺了它，在性格 / 特性 / 太晶属性这些**展开式列表**里选东西会没有任何声音
            //（2026-09-25 用户报）。⚠ 单选、多选两种情况都要，所以放在分支之前
            playButtonClickSound()
            // 多选（证章 / 变体）只切勾选、不关列表；单选选完就走
            if (optionListMultiSelect) toggleMultiOption(item) else selectOption(item.value)
        }
        return true
    }

    /**
     * 拖动中 —— 本界面里**只有滚动条**在用（见 [ScrollBar]）。
     *
     * ⚠ 以后要加别的拖动逻辑先看这里：拖动状态的复位在 [mouseReleased] 与 [ScrollBar.cancel]，
     *   别各处再写一份。
     */
    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (optionListKind != null) {
            ScrollBar.shared.mouseDragged(mouseY, optionListScroll)?.let {
                optionListScroll = it
                return true
            }
        }
        // 快照弹窗的列表滚动条 —— 与选项列表用**同一个实例**（这两个列表不会同时出现：
        // 快照弹窗开着时 `optionListKind` 恒为 null，见 [editDialogCovered]）
        if (snapshotDialog) {
            ScrollBar.shared.mouseDragged(mouseY, snapshotScroll)?.let {
                snapshotScroll = it
                return true
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    /**
     * 松开：结束滚动条的拖动。
     *
     * ⚠ 结束拖动时要**吃掉这次松开** —— 否则它会在别处又触发一次点击
     *   （松手那一刻鼠标往往正压着某一行或某个按钮）。
     */
    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (ScrollBar.shared.mouseReleased()) return true
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(
        mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double
    ): Boolean {
        // 打开 / 关闭动画播放中：还没有可滚的内容（或正在消失）
        if (openAnimActive() || (isCustomShop && closeAnimStarted)) return true
        // 快照列表：滚轮翻（最多 64 条，一屏放不下）
        if (snapshotDialog) {
            val max = snapshotMaxScroll()
            if (max > 0) {
                snapshotScroll = (snapshotScroll - verticalAmount.toInt()).coerceIn(0, max)
            }
            return true
        }
        // 展开的选项列表：滚轮翻页（技能列表可能几百项）
        if (optionListKind != null) {
            val max = (optionListItems.size - optionListRows()).coerceAtLeast(0)
            if (max > 0) optionListScroll = (optionListScroll - verticalAmount.toInt()).coerceIn(0, max)
            return true
        }
        // 窗口内容滚动（屏幕矮、字段网格装不下时才有效果）
        if (editSlot >= 0 && editMaxScroll() > 0) {
            editContentScroll = (editContentScroll - verticalAmount.toInt()).coerceIn(0, editMaxScroll())
            applyEditScroll()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // Esc：窗口开着就**先收窗口**（列表展开时先收列表），别一按就退出整个界面
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (editSlot >= 0) {
                // 从"最上面那层"开始收：快照弹窗 → 展开的选项列表 → 编辑窗本身
                when {
                    snapshotDialog -> closeSnapshotDialog()
                    optionListKind != null -> closeOptionList()
                    else -> closeEditDialog()
                }
                return true
            }
            if (buySlot >= 0) {
                closeBuyDialog()
                return true
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    /**
     * 窗口里**全部**输入框（固定区那几个 + 滚动区那一串）—— 失焦判定两处共用，别各列一份。
     *
     * ⚠ **物品窗的搜索框在这份清单里**（它也是固定区的一个输入框）：漏了它，
     *   `isOverEditInput` 就认不出它 ⇒ 焦点在搜索框上时**点搜索框自己**会被判成"点了别处"而失焦
     *   （想点进去挪光标，结果每次都掉出来）。
     */
    private fun allEditFields(): List<TextFieldWidget?> =
        listOf(editSpeciesField, editItemSearchField, editPriceField, editStockField, editCooldownField) +
            scrollableFields().map { it.first }

    private fun isEditInputFocused(): Boolean = allEditFields().any { it != null && focused === it }

    private fun isOverEditInput(mouseX: Double, mouseY: Double): Boolean =
        allEditFields().any { it?.isMouseOver(mouseX, mouseY) == true }

    private fun overEditPreview(mouseX: Double, mouseY: Double): Boolean {
        if (editSlot < 0) return false
        // ⚠ 坐标**只能**从 [editPreviewSlotX] / [editPreviewSlotY] 来（槽位在预览区里是居中的）——
        //   自己拿 [editPreviewX] / [editPreviewY] 算就漏掉居中偏移，判定框整块偏左（见那两个函数的注释）
        val x = editPreviewSlotX()
        val y = editPreviewSlotY()
        val size = editPreviewSize()
        return mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size
    }

    /** 悬停格子：只在鼠标移动时重算（每帧算一遍没必要，格子是静态的） */
    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        super.mouseMoved(mouseX, mouseY)
        // 弹窗开着时不认悬停：鼠标在弹窗上滑过时，底下那格跟着放大闪来闪去没意义
        hoveredCell = if (freezeHover()) -1 else cellAt(mouseX, mouseY, gridLayout(bgRect()))
    }

    /**
     * 商城快照的**补发**（照市场列表的做法）。
     *
     * ⚠ 服务端的列表请求有 500ms 节流，窗口内的请求被**静默丢弃** —— 连点翻页会让列表一直空着，
     * 所以这里盯着「当前页的快照到没到」，没到就按节流间隔重发一次。
     */
    override fun tick() {
        super.tick()
        // 关闭动画播完：在这里切界面（render 里 setScreen 会搅乱光标锁定状态）
        if (isCustomShop && closeAnimStarted && closeAnimRaw() >= 1f) {
            finishCustomClose()
            return
        }
        // 编辑窗心跳：每 30 秒续一次，避免服务端 15 分钟超时把「编辑中」锁放掉
        if (editSlot >= 0) {
            val now = System.currentTimeMillis()
            if (now - lastEditHeartbeat >= 30_000L) {
                lastEditHeartbeat = now
                sendToServer(SetShopEditingPayload(shopId, true))
            }
        }
        // ① 有人刚改了 / 下架了正在看的这一格 → 关窗 + 提示（见 checkBuySlotStillValid）
        checkBuySlotStillValid()
        // ② 变体候选**异步**回来（打开列表时才请求）⇒ 列表开着就重建一次。
        //    ⚠ 必须放在下面那句 `if (dialogOpen()) return` **之前** —— 需要重建的正是"编辑窗开着、
        //      变体列表也开着"的时候，而那句话恰恰是"弹窗开着就提前返回"。
        //      没有这段的话，先开列表、包后到就会一直停在**空列表**上（黑名单/价格限制那边实测过）
        val aspectsVersion = PointMarketCache.featureAspectsVersion
        if (optionListKind == OPT_FEATURE && variantAspectsSeen != aspectsVersion) {
            variantAspectsSeen = aspectsVersion
            rebuildOptionList()
        }
        // ③ 完整招式表**异步**回来（开窗 / 换物种 / 开列表时请求）⇒ 补两件事：
        //    ① 正开着的技能列表重建一次（候选这下才带上教学 / 形态专属 / 进化那几桶——
        //       [openOptionList] 建表时表可能还在飞，那时用的是本地三桶）
        //    ② [normalizeMoves] 补跑一次：它在表没到时**故意跳过**（本地池子是真子集，会误清）
        //    ⚠ 同上面那条：必须排在下面那句 `if (dialogOpen()) return` **之前**
        val movesVersion = PointMarketCache.speciesMovesVersion
        if (movesVersion != moveTableSeen) {
            moveTableSeen = movesVersion
            val openKind = optionListKind
            if (openKind != null && openKind.startsWith(OPT_SKILL)) {
                optionListAll = buildOptionItems(openKind)
                applyOptionFilter()
                rebuildOptionList()
            }
            if (editSlot >= 0 && isPokemonSlot() && moveTableReady(editSpecies)) {
                normalizeMoves()
                refreshEditLabels()
            }
        }
        if (dialogOpen()) return
        // 搜索防抖：停手 650ms 才发；搜索结果模式下**不发**普通翻页请求（分页由搜索包自己处理）
        maybeSendSearch()
        if (searchMode) return
        val wantPage = currentPage - 1
        // ⚠ 判据**必须带上 kind**：精灵与物品各是一套数据，只看页号的话 ——
        //   缓存里留着**另一个 tab** 的快照时会被当成"已经加载好了"，于是一个包都不发、网格空着
        //  （2026-09-26 用户报：切过 tab 再进商城，精灵网格是空的，再切一次 tab 才出来）。
        //   ⚠ `PointMarketCache.kind` 这个字段本来就是为此存的，之前只写不读。
        // ⚠ **还要判商店**：缓存是全局一份，A 店与 B 店的页号 / tab 可能恰好相同 ——
        //   只比页号与 tab 会把「B 店的快照还没来」误判成「已经加载好了」，于是一个包都不发、网格空着
        if (PointMarketCache.loaded && PointMarketCache.page == wantPage &&
            PointMarketCache.kind == currentKind() && PointMarketCache.shopId == shopId
        ) return
        val now = System.currentTimeMillis()
        if (now - lastPageRequestAt < PAGE_REQUEST_INTERVAL_MS) return
        lastPageRequestAt = now
        sendToServer(RequestPointMarketPayload(wantPage, currentKind(), shopId))
    }

    // ── 商品区：tab / 翻页 / 网格几何 ──

    /**
     * 当前界面在看的商品种类 —— **界面与 kind 的唯一换算处**（别在别处再写一遍）。
     * 单类型商店直接返回固定种类（没有 tab，currentTab 无意义）。
     */
    private fun currentKind(): String =
        fixedKind ?: if (currentTab == 0) ShopEntry.KIND_POKEMON else ShopEntry.KIND_ITEM

    private fun switchTab(tab: Int) {
        if (currentTab == tab) return
        currentTab = tab
        // 切 tab 重置翻页：换了商品集合还停在第 3 页，看着像空列表
        currentPage = 1
        updateTabButtons()
        // ⚠ 清缓存 + **重新请求**：精灵与物品各占一套独立的格子编号（2026-09-25 起 tab 是真过滤），
        //   不清的话新快照到达前会拿上一个 tab 的数据画，格子序号还恰好对得上、看着像"没切过去"
        PointMarketCache.clear()
        // 切 tab 退出搜索模式；搜索框文本保留，下一 tick 会按新 tab 重新搜
        searchMode = false
        searchResults = emptyList()
        lastSearchSent = ""
        sendToServer(RequestPointMarketPayload(currentPage - 1, currentKind(), shopId))
    }

    private fun updateTabButtons() {
        val pokemonLabel = Text.translatable("cobblemarket.points.market.tab_pokemon").string
        val itemLabel = Text.translatable("cobblemarket.points.market.tab_item").string
        // 选中的 tab 靠**按钮自己的选中态**（背景第三段）表达，不再在文字前加 `● `
        // （原注释说的"按钮底色图只有一段状态"是贴图调整之前的旧情况，现在 button_9slice 是三段）
        pokemonTabButton?.setMessage(Text.literal(pokemonLabel))
        pokemonTabButton?.selectedVisual = currentTab == 0
        itemTabButton?.setMessage(Text.literal(itemLabel))
        itemTabButton?.selectedVisual = currentTab == 1
    }

    private fun prevPage() {
        // 搜索结果模式：翻的是**搜索结果**的页，不是普通商品页
        if (searchMode) {
            if (searchPage <= 0) return
            sendSearch(searchPage - 1)
            return
        }
        if (currentPage <= 1) return
        currentPage--
        // 换了页要立刻拉新快照（把节流计时清零，下一个 tick 就发，不用干等 500ms）
        lastPageRequestAt = 0L
    }

    private fun nextPage() {
        if (searchMode) {
            if (searchPage >= searchTotalPages - 1) return
            sendSearch(searchPage + 1)
            return
        }
        if (currentPage >= totalPages) return
        currentPage++
        lastPageRequestAt = 0L
    }

    /** 网格排布：行列固定，格子尺寸与间隔按可用空间算出来，整体居中 */
    private class GridLayout(
        val startX: Int, val startY: Int,
        val cols: Int, val rows: Int,
        val cell: Int, val gap: Int
    ) {
        fun cellX(index: Int): Int = startX + (index % cols) * (cell + gap)
        fun cellY(index: Int): Int = startY + (index / cols) * (cell + gap)
    }

    /** 格子图标区的矩形（左上角 + 边长） */
    private data class IconRect(val x: Int, val y: Int, val size: Int)

    /**
     * 格子内容（精灵模型 / 物品图标）占的那块正方形。
     *
     * 图标区：上边距取边长 8% **再下移 [ICON_TOP_NUDGE]**，下边到价格行上方，再按比例取小值 ——
     * 小格子里自动缩小、不压到价格（写死比例的话，格子小于约 55px 就会叠在一起）。
     *
     * ⚠ 那个 4px 的下移是必要的：格子上边框才 6px 宽，而 8% 的上边距（70px 格子约 6px）
     *   正好把图标顶到边框内缘，3D 模型再往上溢出 4px 就**盖住边框**了（2026-09-20 用户指出）。
     *   代价：底部让给价格行、动不了，所以图标会按比例小一点点（70px 格子约小 3px）。
     *
     * [drawX] / [drawY] / [drawSize] 是**当前画出来的那个格子**（悬停放大后与静止时不同），
     * 绘制与领取飞入动画的起点共用这一份 —— 两处各算一遍迟早不同步。
     */
    private fun iconRectOf(drawX: Int, drawY: Int, drawSize: Int): IconRect {
        val iconTop = drawY + (drawSize * ICON_TOP_RATIO).roundToInt() + ICON_TOP_NUDGE
        val iconBottom = drawY + drawSize - PRICE_BOTTOM_GAP - 2
        val iconSize = (iconBottom - iconTop)
            .coerceAtMost((drawSize * ICON_SIZE_RATIO).roundToInt())
            .coerceAtLeast(MIN_ICON_SIZE)
        val iconX = drawX + (drawSize - iconSize) / 2
        return IconRect(iconX, iconTop, iconSize)
    }

    private fun gridLayout(bg: BgRect): GridLayout {
        // 跨度与两条分割线一致，视觉上才对得齐
        val left = bg.x + texPx(DIVIDER_BORDER_X) - DIVIDER_EXTEND
        val right = bg.x + bg.w - texPx(DIVIDER_BORDER_X) + DIVIDER_EXTEND
        val top = dividerTopY(bg) + GRID_PADDING
        val bottom = dividerBottomY(bg) - GRID_PADDING
        val availW = right - left
        val availH = bottom - top
        val byW = availW / (GRID_COLS + (GRID_COLS - 1) * GRID_GAP_RATIO)
        val byH = availH / (GRID_ROWS + (GRID_ROWS - 1) * GRID_GAP_RATIO)
        // 格子取哪个值见 [GRID_W_ROWS_TRADE_RATIO]：大分辨率按 [GRID_ROWS] 行算（保住每页 32 格），
        // 小分辨率按宽度铺满、行数自适应（不然格子被高度压得很小、左右白空一大片）
        val cell = (if (byW >= byH * GRID_W_ROWS_TRADE_RATIO) byW else byH).toInt().coerceAtLeast(16)
        val gap = (cell * GRID_GAP_RATIO).toInt()
        // 能放几行：rows × cell + (rows−1) × gap ≤ availH
        val rows = ((availH + gap) / (cell + gap)).coerceAtLeast(1)
        val gridW = GRID_COLS * cell + (GRID_COLS - 1) * gap
        val gridH = rows * cell + (rows - 1) * gap
        return GridLayout(
            startX = left + (availW - gridW) / 2,
            startY = top + (availH - gridH) / 2,
            cols = GRID_COLS,
            rows = rows,
            cell = cell,
            gap = gap
        )
    }

    // ── OP 添加 / 编辑窗口 ──

    /**
     * 商城上下架判 **`points.shop` 节点**（2026-09-25 从 OP 改判，服主恒真）。
     *
     * ⚠ 读**服务端下发的快照**（[ClientPermissionCache]）而**不是** `MarketPermissions.has` ——
     * 后者是服务端判定，客户端玩家没有 server 实例、永远返回 false。
     * ⚠ 这是**显隐判定**，服务端在 `PointMarketService.save/remove` 里另有同判据的真鉴权 ——
     * 改这里要一起改，别只改一边。
     */
    private fun canEditShop(): Boolean =
        ClientPermissionCache.isServerOwner() || ClientPermissionCache.has(MarketPermissions.POINTS_SHOP)

    /** 鼠标落在哪个格子上（**页内下标**）；-1 = 不在任何格子上 */
    private fun cellAt(mouseX: Double, mouseY: Double, layout: GridLayout): Int {
        for (i in 0 until layout.cols * layout.rows) {
            val x = layout.cellX(i)
            val y = layout.cellY(i)
            if (mouseX >= x && mouseX < x + layout.cell && mouseY >= y && mouseY < y + layout.cell) return i
        }
        return -1
    }

    /** 页内下标 → **全局序号**（数据层按全局序号存：第 1 页 = 0~31、第 2 页 = 32~63…） */
    private fun globalSlot(pageCellIndex: Int): Int =
        (currentPage - 1) * PointShopState.POINTS_SHOP_PAGE_SIZE + pageCellIndex

    // ── 自定义购买店：搜索结果模式 ──

    /** 网格下标 → 全局格子序号：搜索结果模式下按命中项的**真实序号**，普通模式按当前页。 */
    private fun slotOfCell(cell: Int): Int =
        if (searchMode) searchResults.getOrNull(cell)?.index ?: -1 else globalSlot(cell)

    /**
     * 按格子序号取条目：搜索结果不在 `PointMarketCache.slots` 里，购买 / 编辑窗要一起认。
     * 搜索模式下**优先用搜索结果**（它与网格上看到的是同一份），普通模式只用页缓存。
     */
    private fun entryAt(slot: Int): ShopEntry? =
        if (searchMode) searchResults.firstOrNull { it.index == slot }?.entry
        else PointMarketCache.slots[slot]

    /** 搜索防抖：文本变了且停手 [SEARCH_DEBOUNCE_MS] 才发（服务端还有 500ms 节流）。 */
    private fun maybeSendSearch() {
        if (!isCustomNormalShop()) return
        val text = searchText.trim()
        if (text.isEmpty()) {
            exitSearch()
            return
        }
        val key = "${currentKind()}|$text"
        val now = System.currentTimeMillis()
        if (key == lastSearchSent || now - lastSearchSentAt < SEARCH_DEBOUNCE_MS) return
        lastSearchSent = key
        lastSearchSentAt = now
        sendSearch(0)
    }

    private fun sendSearch(page: Int) {
        val q = searchText.trim()
        if (q.isEmpty()) return
        sendToServer(SearchPointMarketPayload(shopId, currentKind(), q, matchingEntryIds(q), page))
    }

    private fun exitSearch() {
        if (!searchMode && lastSearchSent.isEmpty()) return
        searchMode = false
        searchResults = emptyList()
        searchPage = 0
        searchTotalPages = 1
        searchTotal = 0
        lastSearchSent = ""
        // 普通页可能停在被搜索打断之前 —— 清零让它下一 tick 补一次请求
        lastPageRequestAt = 0L
    }

    /** 收到服务端搜索分页结果（[PointMarketSearchPayload]）；只认本店、本 tab、当前查询词。 */
    fun onShopSearchResults(payload: PointMarketSearchPayload) {
        if (payload.shopId != shopId || payload.kind != currentKind()) return
        if (payload.query != searchText.trim()) return
        if (payload.query.isEmpty()) {
            exitSearch()
            return
        }
        searchMode = true
        searchResults = payload.slots
        searchPage = payload.page
        searchTotalPages = payload.totalPages
        searchTotal = payload.total
    }

    /**
     * 客户端本地把搜索词解析成候选 id。
     *
     * ⚠ **名称只能在客户端解析**（服务端语言 ≠ 客户端语言）；服务端拿这些 id + 原始查询串
     * 做匹配。物品走注册表本地化名，物种走 `PokemonSpecies.species` 的翻译 key。
     */
    private fun matchingEntryIds(query: String): List<String> {
        val q = query.lowercase()
        if (q.isEmpty()) return emptyList()
        return if (currentKind() == ShopEntry.KIND_ITEM) {
            val index = itemNameIndex ?: run {
                val built = ArrayList<Pair<String, String>>()
                for (item in net.minecraft.registry.Registries.ITEM) {
                    val id = net.minecraft.registry.Registries.ITEM.getId(item).toString()
                    val name = runCatching { Text.translatable(item.translationKey).string }.getOrDefault(id)
                    built += id to name.lowercase()
                }
                itemNameIndex = built
                built
            }
            index.asSequence()
                .filter { (id, name) -> name.contains(q) || id.lowercase().contains(q) }
                .map { it.first }.take(SEARCH_ID_LIMIT).toList()
        } else {
            val index = speciesNameIndex ?: run {
                val built = ArrayList<Pair<String, String>>()
                for (species in PokemonSpecies.species) {
                    val id = species.resourceIdentifier.toString()
                    val name = runCatching { Text.translatable(species.translationKey).string }.getOrDefault(id)
                    built += id to name.lowercase()
                }
                speciesNameIndex = built
                built
            }
            index.asSequence()
                .filter { (id, name) -> name.contains(q) || id.lowercase().contains(q) }
                .map { it.first }.take(SEARCH_ID_LIMIT).toList()
        }
    }

    /** 当前视图的总页数 / 当前页码（普通分页与搜索结果分页共用翻页按钮）。 */
    private fun viewPages(): Int = if (searchMode) searchTotalPages else totalPages
    private fun viewPageNumber(): Int = if (searchMode) searchPage + 1 else currentPage

    /**
     * 窗口宽度：**跟着 RP 商城背景的宽度**（用户 2026-09-20 要的「和背景一样大」）。
     *
     * ⚠ 只有宽度跟着背景，高度不能 —— 自动 GUI 下逻辑屏才 270 高、背景本身也才 248，
     * 照背景做反而更矮。所以是**宽着排**：宽一点就多排一列，行数自然掉下来（12 行 → 7~8 行）。
     */
    private fun editDialogW(): Int =
        minOf(EDIT_DIALOG_MAX_W, (bgRect().w * 0.96f).roundToInt()).coerceAtLeast(EDIT_DIALOG_MIN_W)

    private fun editDialogX(): Int = (width - editDialogW()) / 2

    /**
     * 一行几列：按窗口宽度算（列宽不小于 [EDIT_MIN_COL_W]）。
     * 大分辨率下排 5 列、小分辨率（GUI scale 调大）自动退回 3 列，那时才轮到滚动。
     */
    /**
     * 窗口横向一共分 **6 格**（2026-09-20 用户定的新布局）：
     * **左 3 格**放字段（每行 3 个）、**右 3 格**给预览图与个体/努力值输入框。
     * 窗口总宽不变，所以每格比原来 5 列时略窄一点。
     */
    private fun editCols(): Int = EDIT_COLS

    /** 列宽：扣掉左右内边距与列间隔后平分 */
    private fun editColW(): Int =
        (editDialogW() - EDIT_PAD * 2 - (editCols() - 1) * EDIT_COL_GAP) / editCols()

    /**
     * 字段的**排列顺序**（流式：从左到右、从上到下填格子）。
     * 加字段、挪位置只改这一张表 —— 建控件、滚动、可见性、命中判定全都按它算。
     */
    private fun editFieldOrder(): List<String> = buildList {
        // 闪光拆成一格按钮 + 一格概率框（挨着，视觉上是一组）
        // **左 3 格**的 19 个字段，每行 3 个（右边 3 格留给预览图与个体/努力值输入框）。
        // 顺序是用户 2026-09-20 定的那张表，2026-09-26 补入「极巨等级」后按"成组同排"重排过一次。
        listOf(
            KEY_SHINY, KEY_SHINY_CHANCE, KEY_FORM,
            // 「变体」占**形态正下方**那格（第 2 行第 3 格，原本是「特性」的位置）——
            // 两者都是"这只长什么样"，挨着放才好找；被顶下去的字段**整体顺延一格**
            // （用户 2026-09-27 定：同组的离得近）
            KEY_LEVEL, KEY_GENDER, KEY_FEATURE,
            KEY_ABILITY, KEY_NATURE, KEY_BALL,
            KEY_SIZE,
            // 排法（用户 2026-09-26 定）：**成组的尽量同一行** ——
            //   极巨等级紧挨超极巨化（能不能极巨化 / 能不能超极巨化）；
            //   携带物 + 携带物可见 同排（一个是带什么、一个是模型上露不露，本就一对）
            KEY_ALPHA, KEY_GMAX, KEY_DMAX,
            KEY_TERA, KEY_TRADEABLE, KEY_FRIENDSHIP,
            KEY_HELD, KEY_HELD_VISIBLE, KEY_MARKS,
            // 「初始训练家」（配信）占保底 V 数右边那格 —— 它是**文本**输入框（见 [otLabelW]）
            KEY_MIN_IVS, KEY_OT
        ).forEachIndexed { i, key ->
            add(key)
            if (i % 3 == 2) add(FIELD_BREAK)   // 一行满了就换行，右 3 格留空
        }
        // 六项个体值 / 努力值、四个技能槽**都不在这张表里** —— 它们有各自的固定区
        // （前两者在右侧区、技能在最后一行居中），位置见 [posOf]
    }

    /** 六项个体值 / 努力值在右侧区的位置：`iv0`..`iv5` → 0..5，`ev0`..`ev5` → 6..11 */
    private fun statIndexOf(key: String): Int? = when {
        key.startsWith(KEY_IV) -> key.removePrefix(KEY_IV).toIntOrNull()
        key.startsWith(KEY_EV) -> key.removePrefix(KEY_EV).toIntOrNull()?.plus(STAT_COUNT)
        else -> null
    }

    /**
     * 字段 key → **(行, 列)** —— **一切位置都从这里来**（建控件、滚动、可见性、渲染都走它）。
     * 散在各处各算一份必然错位。
     *
     * 三类字段各自的归属：
     * - **个体值 / 努力值**：右侧 3 格 × 4 行（IV 两行在上、EV 两行在下），见 [EDIT_ROW_STAT]
     * - **技能槽**：最后一行、6 格居中（左右各留一格的边距）
     * - **其余**：按 [editFieldOrder] 流式排在左 3 格
     */
    private fun posOf(key: String): Pair<Int, Int> {
        if (key.startsWith(KEY_SKILL)) {
            val slot = key.removePrefix(KEY_SKILL).toIntOrNull() ?: 0
            // 技能独立成行（在字段网格下面一行），6 格里居中：左右各留一格
            return skillRow() to (1 + slot)
        }
        statIndexOf(key)?.let { idx ->
            // 个体值 / 努力值：**放在左侧那 6 行字段的下面**（用户 2026-09-20 调整过位置）——
            // 一行 6 个：IV 占一行、EV 占一行
            return (fieldRowCount() + idx / STAT_COUNT) to (idx % STAT_COUNT)
        }
        ensureFieldLayout()
        return fieldLayout[key] ?: (0 to 0)
    }

    // 布局缓存：渲染每帧都要查行号，按顺序表现算是白烧；**列数变了才重算**
    private var fieldLayout: Map<String, Pair<Int, Int>> = emptyMap()
    private var fieldLayoutCols = -1
    private var fieldLayoutRows = 0

    private fun ensureFieldLayout() {
        val cols = editCols()
        if (cols == fieldLayoutCols) return
        val map = mutableMapOf<String, Pair<Int, Int>>()
        var row = 0
        var col = 0
        editFieldOrder().forEach { key ->
            if (key == FIELD_BREAK) {
                row++
                col = 0
                return@forEach
            }
            map[key] = row to col
            col++
            if (col >= cols) {
                col = 0
                row++
            }
        }
        fieldLayout = map
        fieldLayoutCols = cols
        fieldLayoutRows = row + if (col > 0) 1 else 0
    }

    /** 字段在网格里的行 / 列（流式排布 + 换行标记，唯一口径） */
    private fun editRowOf(key: String): Int = posOf(key).first

    private fun editColOf(key: String): Int = posOf(key).second

    /** 左侧字段占的行数（0 .. fieldLayoutRows-1） */
    private fun fieldRowCount(): Int {
        ensureFieldLayout()
        return fieldLayoutRows
    }

    /** **技能那一行**的行号（在字段网格与个体/努力值两行之下）—— 单独拆出来，避免和总行数循环依赖 */
    private fun skillRow(): Int = fieldRowCount() + 2

    /** 内容一共几行：字段 6 行 + 个体值 1 行 + 努力值 1 行 + 技能 1 行（滚动上限靠它算） */
    private fun editRowCount(): Int = fieldRowCount() + 3

    /**
     * 窗口高度：设计高见 [EDIT_DIALOG_H]（262），屏幕放不下就**按屏幕钳制**。
     *
     * ⚠ 自动 GUI 尺寸下逻辑屏高只有 270（1080p），设计高必然顶出屏幕 ——
     * 所以下面三段各自定位：**上固定**（标题 + 物种行）、**中间可滚动**（字段网格）、
     * **下固定**（价格/库存 + 按钮，钉在窗口底部）。这一条是项目里连踩多次的老坑。
     */
    private fun editDialogHeight(): Int =
        minOf(EDIT_DIALOG_H + if (buySlot >= 0) BUY_DISCOUNT_EXTRA_H else 0, height - 8)

    /** 窗口顶边：竖直居中 */
    private fun editDialogY(): Int = ((height - editDialogHeight()) / 2).coerceAtLeast(4)

    /** 某列的左缘（列宽随窗口宽度算，见 [editColW]） */
    private fun editColX(col: Int): Int = editDialogX() + EDIT_PAD + col * (editColW() + EDIT_COL_GAP)

    /**
     * 窗口内容的**右缘**：取最后一列字段按钮的右边缘。
     *
     * ⚠ 别用「窗口右缘 − 内边距」—— 列宽是整数除法（余数丢掉），两种算法会差几像素，
     * 顶部的箭头、「1/3」计数和右上角的下架按钮就都跟下面的字段按钮错开（2026-09-20 用户指出）。
     */
    private fun editRowRightX(): Int = editColX(editCols() - 1) + editColW()

    // ── 两处「按最宽文案加宽」（用户 2026-09-26）────────────────────────
    //
    // ⚠ 字段列宽 [editColW]（82px）是**按中文**定的，英文那边放不下、被截断：
    //   ·「Shiny: Chance 50%」= 102px ⇒ 购买窗的闪光行截成「Shiny: Chance …」
    //   ·「Move 1: Sword Dance」= 114px ⇒ 技能按钮截成「Sword…」
    //   这两处各自有横向余量（闪光那行右边空着一格、技能行左右各空一格），所以**就地加宽** ——
    //   整张网格的列宽动不得，那会牵动建控件 / 滚动重摆 / 命中判定 / 悬停窗四五处。

    /** 技能槽按钮的宽度：4 个**平分整行**（原来只占中间 4 格，左右各空一格） */
    private fun skillButtonW(): Int =
        (editRowRightX() - editColX(0) - EDIT_COL_GAP * (MoveText.SLOTS - 1)) / MoveText.SLOTS

    /** 第 [slot] 个技能按钮的左缘 */
    private fun skillButtonX(slot: Int): Int = editColX(0) + slot * (skillButtonW() + EDIT_COL_GAP)

    /**
     * 字段按钮的左缘 / 宽度 —— **建控件（[editFieldButton]）与滚动重摆（[applyEditScroll]）必须共用**。
     * 技能槽占满整行、其余走列网格；各算一份的话一滚动技能按钮就跳回中间那 4 格（本项目的老坑）。
     */
    private fun fieldButtonX(key: String): Int =
        if (key.startsWith(KEY_SKILL)) skillButtonX(key.removePrefix(KEY_SKILL).toIntOrNull() ?: 0)
        else editColX(editColOf(key))

    private fun fieldButtonW(key: String): Int =
        if (key.startsWith(KEY_SKILL)) skillButtonW() else editColW()

    /**
     * 「下架」按钮的**顶边**：让它**距顶 = 距右**（用户 2026-09-20 定）。
     *
     * 原来死钉在窗口顶 +4，而右边到背景缘还空着 10~15px（列宽是整数除法，这个余量随窗口宽度浮动，
     * 见 [editRowRightX]）—— 看着就是「贴着上边、右边却留一条缝」。现在两边取**同一个数值**
     * （=`editRowRightX` 到背景右缘的距离），窗口怎么变都对称。
     */
    private fun editRemoveY(): Int =
        editDialogY() + (editDialogX() + editDialogW() - editRowRightX())

    /**
     * 物种行右端：**对齐左 3 列的右缘**（用户 2026-09-20 定）——
     * 右边那 3 格是预览图和个体值的位置，物种行不该伸过去。
     */
    private fun editSpeciesRightX(): Int = editColX(2) + editColW()

    /**
     * 物种搜索框的**右缘**：对齐「闪光概率」输入框的右边缘（用户 2026-09-20 定）。
     *
     * 右侧余下的地方（[editSpeciesRightX] 之前）正好排「清空 + 两枚候选箭头 + 1/3 计数」——
     * 三者与物种行右缘对齐，所以缩短搜索框腾出的空间刚好够，箭头与计数**原地不动**。
     */
    private fun editSpeciesFieldRightX(): Int = editColX(1) + editColW()

    /** 物种输入框宽度：从左内边距到 [editSpeciesFieldRightX] */
    private fun editSpeciesFieldW(): Int = editSpeciesFieldRightX() - (editDialogX() + EDIT_PAD)

    /**
     * 预览图区：**右侧 3 格 × 上 2 行**（用户 2026-09-20 定的新布局 —— 原来它居中在标题下方，
     * 而右边「性格」以上那两块一直空着，正好把预览放大放过去）。
     */
    private fun editPreviewX(): Int = editColX(3)
    private fun editPreviewY(): Int = editRowY(0)
    private fun editPreviewW(): Int = editColW() * 3 + EDIT_COL_GAP * 2
    // 预览图占**右侧整块**：个体/努力值挪到下面去了，右侧从顶上一直空到「亲密度」行
    private fun editPreviewH(): Int = EDIT_ROW_STEP * fieldRowCount() - (EDIT_ROW_STEP - EDIT_ROW_H)

    /** 预览图的绘制边长（正方形，取区域里短的那边） */
    private fun editPreviewSize(): Int = minOf(editPreviewW(), editPreviewH())

    /**
     * 预览槽**实际画在哪儿** —— 正方形，**居中**在右侧那块预览区里（区域通常比槽宽得多）。
     *
     * ⚠ 渲染与点击判定（[overEditPreview]）**必须共用这两个**：直接拿 [editPreviewX] / [editPreviewY]
     * 当槽位左上角会整体偏左半个差值（宽屏下 70 多像素），点图标就没反应（2026-09-20 用户报，
     * 上回只修了 Y 的基准、没带上居中偏移，于是又犯一次）。
     */
    private fun editPreviewSlotX(): Int = editPreviewX() + (editPreviewW() - editPreviewSize()) / 2

    private fun editPreviewSlotY(): Int = editPreviewY() + (editPreviewH() - editPreviewSize()) / 2

    /**
     * 预览大图的**裁剪框**（用户 2026-09-20 定）：允许精灵长出槽位 ——
     * 高的（伽勒尔椰蛋树这类）能一直伸到弹窗顶部，横向也撑得出去；**下方不动**（脚踩在槽底）。
     *
     * ⚠ **只有这个界面的预览大图用** —— 行内小图标、悬停窗、商城格子仍旧吃
     * [PokemonIconRenderer] 默认的「外扩 4px」，`clip` 传 null 就是那套。
     *
     * | 边 | 位置 |
     * |---|---|
     * | 左 | 形态那一列按钮的右缘（再往左就盖到字段按钮上了） |
     * | 上 | 弹窗背景**顶部边框内** [PREVIEW_CLIP_MARGIN] px |
     * | 右 | 弹窗背景**右边框内** [PREVIEW_CLIP_MARGIN] px |
     * | 下 | 槽底外扩 4px（与默认余量同口径） |
     *
     * 上 / 右**都扣掉边框宽**（`NINE_SLICE_BORDER`）—— 两条边口径一致，不偏不倚（用户 2026-09-20 拍板）。
     */
    private fun editPreviewClip(slotY: Int, slotSize: Int): IntArray = intArrayOf(
        editSpeciesRightX(),
        editDialogY() + NINE_SLICE_BORDER + PREVIEW_CLIP_MARGIN,
        editDialogX() + editDialogW() - NINE_SLICE_BORDER - PREVIEW_CLIP_MARGIN,
        slotY + slotSize + 4
    )

    /** 清空按钮：紧贴搜索框右侧（留 4px，与「输入框→相邻控件」的既有口径一致） */
    private fun editClearX(): Int = editSpeciesFieldRightX() + 4

    /** 两枚候选箭头跟着清空按钮排下去（各自间隔 2px） */
    private fun editArrowX(): Int = editClearX() + SPECIES_CLEAR_W + 2
    private fun editArrow2X(): Int = editArrowX() + EDIT_ARROW_W + 2

    /** 中间滚动区的高度（字段网格能露出来的部分） */
    private fun editScrollViewHeight(): Int =
        (editDialogHeight() - EDIT_ROWS_TOP - editBottomBlock()).coerceAtLeast(EDIT_ROW_STEP)

    /**
     * 底部固定块的高度：编辑窗 44；**购买窗 54**（多出的 10px 是卡片折扣提示行）。
     *
     * ⚠ 高度（[editDialogHeight] 已 +10）与这里**必须成对改**：只加高度 ⇒ 价格行跟着下移、
     *   按钮被挤到底边（2026-10-28 用户实测就是这个）。价格行/按钮行都从
     *   `高度 − 底部块` 算，所以底部块也要 +10，价格行才留在原位。
     */
    private fun editBottomBlock(): Int =
        EDIT_BOTTOM_BLOCK + if (buySlot >= 0) BUY_DISCOUNT_EXTRA_H else 0

    /**
     * 滚动上限。
     *
     * ⚠ **物品窗恒为 0**：它没有字段网格，而右侧那张物品大图的 Y 落在 [editRowY] 上
     * （见 [editPreviewY]）⇒ 留着滚动空间等于让大图能被滚出窗口。
     * 原先只靠"算出来恰好 ≤ 0"兜着（矮屏被钳制时就不成立了），这里显式钉死。
     */
    private fun editMaxScroll(): Int {
        if (!isPokemonSlot()) return 0
        return (editRowCount() * EDIT_ROW_STEP - editScrollViewHeight()).coerceAtLeast(0)
    }

    /** 第 [row] 行（两列字段）的顶边 —— **建控件与渲染共用这一个**，滚动偏移也在这里生效 */
    private fun editRowY(row: Int): Int =
        editDialogY() + EDIT_ROWS_TOP + row * EDIT_ROW_STEP - editContentScroll

    /** 价格 / 库存行的顶边（钉在底部，不随滚动移动） */
    private fun editPriceRowY(): Int = editDialogY() + editDialogHeight() - editBottomBlock()

    /**
     * 价格 / 库存这一组（「标签 + 输入框」× 2，横跨两格）的**总宽**与**左缘**。
     *
     * 整组在窗口内容区里**水平居中**（用户 2026-09-20）—— 原来它俩贴着左边第 0、1 格，
     * 而下面那行按钮是居中的，上下两行看着各偏一边；居中后共用同一条中轴。
     *
     * ⚠ 建控件（[buildEditDialog]）与画标签（[renderEditDialogBackground]）**两处都走这里**，
     *   各算一份必然错开（本项目连踩多次的老坑）。
     */
    /**
     * 价格 / 库存**单格**的宽度 —— 取 **1.5 个字段列**。
     *
     * ⚠ 用户 2026-09-26 报：原来按 1 列算，输入框只剩 `editColW - EDIT_LABEL_W`（82-32 ≈ 50px），
     *   **一次放不下价格的最大位数**（12 位 ≈ 72px，输入框会横向滚动、看不见填了几位）。
     *   两格各让出半列 ⇒ 输入框有 91px（15 位），整组仍是"2 格 + 1 间隔"的格局，
     *   [editPriceGroupX] 的居中算法不用动。
     */
    private fun editPriceColW(): Int = editColW() * 3 / 2

    /**
     * 底部「价格 / 库存」这一行是否多一列**格子冷却**。
     *
     * 只有**自定义普通购买店**的编辑窗才有（主商城 / 领取店没有这个概念）。
     * ⚠ 这一列用的是价格/库存行**本来就空着**的横向余量：不新增行、不增高弹窗、
     *   也不压缩价格/库存框的宽度（每格宽度仍由 [editPriceColW] 决定）。
     */
    private fun slotCooldownShown(): Boolean = isCustomNormalShop()

    /** 冷却列数：2 = 价格/库存，3 = 价格/库存/冷却 */
    private fun editPriceGroupCols(): Int = if (slotCooldownShown()) 3 else 2

    private fun editPriceGroupW(): Int =
        editPriceColW() * editPriceGroupCols() + EDIT_COL_GAP * (editPriceGroupCols() - 1)

    /**
     * 冷却标签：全称（`冷却` / `Cooldown`）在窄屏会把输入框压到放不下数字，退回短版
     * （中文仍是「冷却」，英文「CD」）。判据按**实宽**，不按字符数。
     */
    private fun slotCooldownLabel(): Text {
        val full = Text.translatable("cobblemarket.points.store.field.cooldown")
        return if (editPriceColW() - textRenderer.getWidth(full) >= 24) full
        else Text.translatable("cobblemarket.points.store.field.cooldown_short")
    }

    private fun slotCooldownLabelW(): Int = textRenderer.getWidth(slotCooldownLabel())

    /** 冷却输入框的 x（第三列标签之后） */
    private fun slotCooldownFieldX(): Int =
        editPriceGroupX() + (editPriceColW() + EDIT_COL_GAP) * 2 + slotCooldownLabelW()

    private fun slotCooldownFieldW(): Int = (editPriceColW() - slotCooldownLabelW()).coerceAtLeast(16)

    private fun editPriceGroupX(): Int =
        editDialogX() + EDIT_PAD + (editDialogW() - EDIT_PAD * 2 - editPriceGroupW()) / 2

    /** 底部按钮行的顶边 */
    /**
     * 按钮行：编辑窗固定 +18；**购买窗 +28**（多出的 10px 是「卡片价格优惠」提示行，
     * 只在购买窗显示，见 [renderBuyDialog]）。
     */
    private fun editButtonRowY(): Int =
        editPriceRowY() + if (buySlot >= 0) 36 else 18

    /** 某一行当前露没露在滚动区里（整行判断：半截的行控件本身没法裁剪，只能整体藏） */
    private fun editRowVisible(row: Int): Boolean {
        val top = editRowY(row)
        val viewTop = editDialogY() + EDIT_ROWS_TOP
        return top >= viewTop - 1 && top + EDIT_ROW_H <= viewTop + editScrollViewHeight() + 1
    }

    // ⚠ 跟 [currentKind] 走，不要再直接看 currentTab —— 单类型商店的 currentTab 恒为 0，
    //   只卖物品的店会因此把编辑窗开成精灵规格
    private fun isPokemonSlot(): Boolean = currentKind() == ShopEntry.KIND_POKEMON

    /**
     * 开编辑窗：**右键**有内容的格子 = 编辑；**左键**空格子 = 添加（领取店左键有内容也是编辑）。
     * 普通玩家左键有内容的格子 = 购买窗；点空格 / 右键无反应。
     */
    private fun openEditDialog(slot: Int) {
        if (!canEditShop()) return
        // 格子是**自绘**的，不走 NineSliceButton 那套内置点击音 —— 不在这里补一下，
        // 点格子的手感就是"没反应"（用户 2026-09-20 报「网格内的交互都没音效」）
        playButtonClickSound()
        editSlot = slot
        // 告诉服务端「这家店进入编辑中」：其他玩家的购买按钮会立刻置灰（服务端同时拦购买）
        sendToServer(SetShopEditingPayload(shopId, true))
        lastEditHeartbeat = System.currentTimeMillis()
        // 窗口从**这个格子**长大出来（起点 = 它的 1.15 倍悬停态）
        startDialogAnim(slot)
        // 形态候选表：species feature（花纹 / 帽子 / mega）**客户端自己算不出来**，
        // 得问服务端要（见 [SpeciesText.selectableFormAspects]）。精灵 tab 才用得上。
        // ⚠ 包到得晚也不影响使用：那时候服主还在搜物种，缓存空时列表退化成只列 forms
        if (isPokemonSlot()) sendToServer(RequestSpeciesFeatureAspectsPayload())
        // 有内容的格子 = 编辑：把它整份填进草稿（空 = 添加，保持 resetDraft 的默认值）。
        // `editSpecBase` 是提交时 `copy()` 的基底 —— 界面没接的高级项靠它原样保留。
        val original = entryAt(slot)
        editEditingSlot = original != null
        editSpecBase = original
        resetDraft()
        if (original != null) {
            fillDraft(original)
            // 冷却不随 fillDraft（快照回填也不带它）：编辑已有格子时按原值回填；
            // 0 = 跟随整店 → 留空（占位符显示整店值）
            editCooldownText = original.cooldownHours.takeIf { it > 0 }?.toString().orEmpty()
        }
        hideMainControls()
        buildEditDialog()
        // 开窗自动叫一声（延迟到渲染之后再播，见 IconCryClick）
        editCryAt = System.currentTimeMillis() + IconCryClick.AUTO_CRY_DELAY_MS
    }

    /**
     * 关窗：清掉窗口状态 + **重建界面**把主控件恢复回来。
     *
     * ⚠ 重建会重跑 init，兑换框里玩家敲了一半的码靠 `pendingCodeText` 恢复（见 init 的注释），
     * 翻页位置等状态也都是字段、不受影响。
     */
    private fun closeEditDialog(animate: Boolean = true) {
        if (animate && editSlot >= 0) {
            startCloseAnimation(editSlot) { reallyCloseEditDialog() }
            return
        }
        reallyCloseEditDialog()
    }

    /** 真正关窗（收回动画播完、或不需要动画时直接走这条） */
    private fun reallyCloseEditDialog() {
        // 关编辑窗 = 解除「编辑中」锁（服务端会广播解锁）；⚠ 先判再清 editSlot
        if (editSlot >= 0) sendToServer(SetShopEditingPayload(shopId, false))
        // 搜索模式下刚改过内容：让下一 tick 重搜一次，别让结果网格停在旧内容上
        if (searchMode) lastSearchSent = ""
        editSlot = -1
        editSpecBase = null
        editEditingSlot = false
        editHintMsg = null
        editSpeciesField = null
        editLevelField = null
        editDmaxField = null
        editShinyField = null
        editPriceField = null
        editStockField = null
        editCooldownField = null
        editSpeciesPrev = null
        editSpeciesNext = null
        editSpeciesClear = null
        editItemSearchField = null
        editItemHeldButton = null
        editItemSearchSyncing = false
        editSpeciesSyncing = false
        editConfirmButton = null
        editRemoveButton = null
        editButtons.clear()
        optionListKind = null
        optionListButtons.clear()
        optionListScroll = 0
        resetDraft()
        clearChildren()
        init()
    }

    /** 弹窗期间把主界面控件全部藏起来（**下层不许留可点的东西**，前科：弹窗没藏住货币按钮） */
    private fun hideMainControls() {
        codeField?.visible = false
        redeemButton?.visible = false
        backButton?.visible = false
        buyEntryButton?.visible = false
        recordButton?.visible = false
        shopHistoryButton?.visible = false
        shopSearchField?.visible = false
        pokemonTabButton?.visible = false
        itemTabButton?.visible = false
        prevButton?.visible = false
        nextButton?.visible = false
    }

    // ── 购买窗（普通玩家侧）──────────────────────────────────
    //
    // 布局**照搬编辑窗**（同一套 [posOf] / [editColX] / [editRowY] / [editPreviewSize]），
    // 把控件换成只读文本 —— 玩家看到的字段与顺序跟服主设置时**一模一样**，
    // 不会出现「买了个不知道是什么的东西」（用户 2026-09-25 拍板）。

    /**
     * ① **有人保存 / 下架了这一格 → 关掉开着的购买窗并提示**（用户 2026-09-25 定的方案）。
     *
     * 判据是**条目 id**：打开窗时记下当时的 entryId，快照一到就比对 —— 对不上就是被换过内容
     *（换了精灵 / 改了价 / 整格下架）。
     *
     * ⚠ 这只是**体验**，不是安全边界：广播跑一个来回有延迟，玩家完全可能已经点下了确认。
     *   那种情况由服务端 `PointMarketService.buy` 的版本校验兜底拒绝（分文不扣）—— 两层配合才完整。
     *   把这条写在 `tick` 里而不是收到包时，是为了不依赖"包到达"这个时机（界面每 tick 自己看一眼）。
     */
    private fun checkBuySlotStillValid() {
        if (buySlot < 0) return
        val entry = entryAt(buySlot)
        if (entry != null && entry.entryId == buySlotEntryId) return
        // 内容变了 / 整格没了：关窗 + 界面内红字（复用兑换结果那条提示的位置）。
        // ⚠ 两种情况**文案必须分开**（用户 2026-09-25 报）：整格没了是**下架** ——
        //   笼统说「刚被改动过」，玩家会以为是自己看花了眼、跑去重开界面找那件商品
        closeBuyDialog()
        resultMsg = Text.translatable(
            if (entry == null) "cobblemarket.points.store.err.slot_removed"
            else "cobblemarket.points.store.err.stale_entry"
        )
        resultColor = 0xFF6666
        resultUntil = System.currentTimeMillis() + STALE_MSG_MS
    }

    /**
     * 服务端广播的「编辑中」状态变化（`ShopEditLockPayload`）。
     *
     * 只认**本店**的包（换店时别的店的广播可能迟到）。购买窗开着就立刻刷新按钮 + 提示，
     * 让玩家不用关窗重开。
     */
    fun onShopEditLock(lockShopId: String, locked: Boolean) {
        if (lockShopId != shopId) return
        shopEditLocked = locked
        if (buySlot < 0) return
        buyConfirmButton?.dimmed = locked
        if (locked) {
            buyHintMsg = Text.translatable("cobblemarket.points.store.err.shop_being_edited")
            buyHintUntil = System.currentTimeMillis() + BUY_HINT_MS
        } else {
            buyHintMsg = null
        }
    }

    /**
     * 打开购买窗。[slot] 必须是**有内容的**格子（调用方 [mouseClicked] 已判过，这里再兜一道）。
     *
     * 入口：**所有人左键**有内容的格子（与已发布行为一致）；服主 / 持 `points.shop` 的人
     * 也是左键购买，**右键才是编辑**（见 [openEditDialog]）。
     */
    private fun openBuyDialog(slot: Int) {
        if (entryAt(slot) == null) return
        // 格子是自绘的、不走 NineSliceButton 内置点击音 —— 不补手感就是"点了没反应"（同 openEditDialog）
        playButtonClickSound()
        buySlot = slot
        // 窗口从**这个格子**长大出来（起点 = 它的 1.15 倍悬停态）
        startDialogAnim(slot)
        buyCount = 1
        // 记下这一条的 id：购买时带回服务端比对，也用来判断它有没有被换过内容
        buySlotEntryId = entryAt(slot)?.entryId.orEmpty()
        buyHintMsg = null
        // ⚠ 购买窗**不滚动**（只读文本自己画，没有控件要挪），但 [editRowY] 会减去这个偏移 ——
        //   编辑窗滚过之后再开购买窗，整片字段会跟着上移错位，所以这里必须清零
        editContentScroll = 0
        hideMainControls()
        buildBuyDialog()
        // 开窗自动叫一声（延迟到渲染之后再播，见 IconCryClick）
        buyCryAt = System.currentTimeMillis() + IconCryClick.AUTO_CRY_DELAY_MS
    }

    /**
     * 关窗：清状态 + **重建界面**把主控件恢复回来（与 [closeEditDialog] 同一套）。
     * ⚠ 重建会重跑 init，兑换框里敲了一半的码靠 `pendingCodeText` 恢复 —— 与编辑窗同一条路。
     */
    private fun closeBuyDialog(animate: Boolean = true) {
        // 购买成功那条路传 animate=false —— 接下来是**庆祝动画**（从弹窗图标位置起飞），
        // 两个动画叠在一起会打架（弹窗正在缩回、同时精灵从弹窗起飞）
        if (animate && buySlot >= 0) {
            startCloseAnimation(buySlot) { reallyCloseBuyDialog() }
            return
        }
        reallyCloseBuyDialog()
    }

    /** 真正关窗（收回动画播完、或不需要动画时直接走这条） */
    private fun reallyCloseBuyDialog() {
        buySlot = -1
        buySlotEntryId = ""
        buyCount = 1
        buyHintMsg = null
        buyCountField = null
        buyConfirmButton = null
        buyCancelButton = null
        buyWidgetBase = 0
        clearChildren()
        init()
    }

    /** 建窗口控件：背景层 + 数量框（物品格才有）+ 确认 / 取消 */
    private fun buildBuyDialog() {
        val entry = entryAt(buySlot) ?: return
        // 清掉上一次残留的控件（同编辑窗：map 只存最后一次的引用，管不到旧对象）
        if (buyWidgetBase in 0 until children().size) {
            children().drop(buyWidgetBase).toList().forEach { remove(it) }
        }
        buyWidgetBase = children().size

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderBuyDialog(context, mouseX, mouseY, delta)
            }
        })

        // 数量框：**只有物品格有** —— 精灵格买一次就是一只（买几只就点几次），服务端同样这么算
        // 领取店不卖东西，没有数量可填
        // ⚠ 有冷却的商品**一次只能买 1 件**（隐藏数量框、恒发 1）—— 与服务器同一判据
        if (entry.kind != ShopEntry.KIND_POKEMON && !isClaimShop && !isCooldownEntry(entry)) {
            val field = TextFieldWidget(
                textRenderer, buyCountX(), editPriceRowY(), BUY_COUNT_W, EDIT_ROW_H, Text.literal("")
            )
            field.setTextPredicate { s -> s.length <= 4 && s.all { it.isDigit() } }
            field.text = "1"
            field.setChangedListener { text -> buyCount = text.trim().toIntOrNull() ?: 0 }
            buyCountField = field
            addSelectableChild(field)
            addDrawableChild(field)
        } else {
            buyCount = 1
        }

        // ⚠ 购买窗**只给玩家**：库存（补货）与下架都在**编辑窗**里做 ——
        //   2026-10-26 起能管商城的人点格子直接进编辑窗，不再经过这里。
        val rowY = editButtonRowY()
        // ⚠ 领取店**不会走到这里**（玩家点格子无反应、服主 / 持 `points.shop` 的人进的是编辑窗）——
        //   这个判断只是兜底：万一将来出现只读查看入口，别给它挂一枚「购买」。
        if (!isClaimShop) {
            buyConfirmButton = NineSliceButton(
                editDialogX() + editDialogW() - EDIT_PAD - BUY_BTN_W * 2 - BUTTON_GAP, rowY, BUY_BTN_W, 16,
                Text.translatable("cobblemarket.points.store.buy_confirm"), { confirmBuy() }
            ).also { addDrawableChild(it) }
            // 店里有人正在编辑：打开就是置灰态（点击仍会走 confirmBuy → 失败音 + 提示）
            buyConfirmButton?.dimmed = shopEditLocked
        }
        buyCancelButton = NineSliceButton(
            editDialogX() + editDialogW() - EDIT_PAD - BUY_BTN_W, rowY, BUY_BTN_W, 16,
            Text.translatable("cobblemarket.points.store.buy_cancel"), { closeBuyDialog() }
        ).also { addDrawableChild(it) }
    }

    /**
     * 编辑窗右上角「下架」：清空这一格（**只在编辑已有格子时出现**）。
     *
     * ⚠ **不播收回动画**：服务端收到请求后会**立刻广播新快照**（几十毫秒），而收回动画要 200ms；
     *   快照到达时弹窗还开着 ⇒ 界面按"弹窗开着"的状态处理刷新，下架后整个商城显示为空，
     *   必须重开界面才恢复（2026-09-27 用户实测）。下架本来就会立刻改格子内容，
     *   让界面尽早回到常规态比播完动画更要紧。
     * ⚠ 没有二次确认（与当年那枚一致）；服务端 `PointMarketService.remove` 只判 `points.shop`。
     */
    private fun removeFromEditDialog() {
        if (editSlot < 0) return
        sendToServer(RemovePointMarketEntryPayload(editSlot, currentKind(), shopId))
        closeEditDialog(animate = false)
    }

    /** 窗口内的点击：控件优先 → 点空白失焦。窗口开着时事件**一律吃掉**，不让它漏到下层 */
    private fun handleBuyDialogClick(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val focusWasInput = buyCountField != null && focused === buyCountField
        super.mouseClicked(mouseX, mouseY, button)
        // 点在输入框之外 → 手动失焦（否则 E 键被输入框吃掉、Esc 也关不掉窗口）
        if (focusWasInput && buyCountField?.isMouseOver(mouseX, mouseY) != true) focused = null
        // 点**精灵大图** = 点精灵图标（项目标配：点一下随机叫一声）。
        // 开窗时本来就自动叫过一声，这里补的是「再点它」那一下 —— 编辑窗的预览槽一直有，购买窗漏了
        //（2026-09-25 用户报「大图没有点击反馈」）。
        // ⚠ 必须判 `KIND_POKEMON`：**物品格没有大图**，不判的话点那块空白也会叫
        if (button == 0 && buySlot >= 0) {
            entryAt(buySlot)
                ?.takeIf { it.kind == ShopEntry.KIND_POKEMON && overBuyPreview(mouseX, mouseY) }
                ?.let { entry ->
                    cellIcon(buySlot, entry).renderable?.let { IconCryClick.play(buyPreviewState.stateFor(it), it) }
                }
        }
        return true
    }

    /**
     * 鼠标在购买窗的**精灵大图**上。
     *
     * ⚠ 坐标与 [renderBuyPreview] **同源**（`editPreviewSlot*` 那套：槽位在预览区里是**居中**的）——
     *   别自己拿 `editPreviewX/Y` 算，漏掉居中偏移会让判定框整块偏（编辑窗那边有过这个坑，
     *   见 [overEditPreview] 的注释）。
     */
    private fun overBuyPreview(mouseX: Double, mouseY: Double): Boolean {
        val x = editPreviewSlotX()
        val y = editPreviewSlotY()
        val size = editPreviewSize()
        return mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size
    }

    /**
     * 确认购买：把数量交给服务端。
     *
     * ⚠ **校验全在服务端**（库存 / 余额 / 收件空间都在 `PointMarketService.buy` 里），
     * 这里只挡一眼就能看出的非法值，免得白跑一趟；被拒的原因由服务端发到聊天栏。
     * ⚠ 发送后**立刻关窗**（同主市场的购买确认弹窗）—— 结果走聊天栏 + 快照，界面不用等回执。
     */
    private fun confirmBuy() {
        // 店里有人正在编辑：整店暂停购买（按钮已置灰，点击只响失败音）
        if (shopEditLocked) {
            buyHintMsg = Text.translatable("cobblemarket.points.store.err.shop_being_edited")
            buyHintUntil = System.currentTimeMillis() + BUY_HINT_MS
            playFailSound()
            return
        }
        val entry = entryAt(buySlot) ?: return closeBuyDialog()
        // ⚠ **售罄与超量都在本地先拦**：两者本地快照就知道，没必要让服务端拒一趟、
        //   再把原因只发到聊天栏 —— 玩家要求这类提示**直接显示在界面上**（2026-09-25）。
        //   库存就在快照里、本来就知道，没必要白跑一趟服务端。
        //   ⚠ 服务端那两道**仍然保留**（客户端快照可能过期：别人刚买走最后一件），
        //     只是那种情况少见，走聊天栏兜底即可。
        if (entry.isSoldOut()) {
            buyHintMsg = Text.translatable("cobblemarket.points.store.err.sold_out")
            buyHintUntil = System.currentTimeMillis() + BUY_HINT_MS
            playFailSound()
            return
        }
        // 冷却中：本地直接拦（服务端同样会拦，这里只是不让玩家白跑一趟）。
        // ⚠ **不再往价格行上方写红字** —— 倒计时已常驻在购买按钮左侧（灰字），点击只补失败音。
        if (cooldownRemaining(entry) > 0) {
            playFailSound()
            return
        }
        // 有冷却的商品恒买 1 件
        val count = if (entry.kind == ShopEntry.KIND_POKEMON || isCooldownEntry(entry)) 1 else buyCount
        if (count < 1) {
            buyHintMsg = Text.translatable("cobblemarket.points.store.err.bad_count")
            buyHintUntil = System.currentTimeMillis() + BUY_HINT_MS
            playFailSound()
            return
        }
        // 限量格买超了：同「售罄」，也是本地就能判的（`stock > 0` 才是限量，`-1` 是不限量）
        if (entry.stock > 0 && count > entry.stock) {
            buyHintMsg = Text.translatable("cobblemarket.points.store.err.stock_not_enough")
            buyHintUntil = System.currentTimeMillis() + BUY_HINT_MS
            playFailSound()
            return
        }

        // ⚠ **关窗之前**登记庆祝动画的起飞起点 —— 弹窗一关那个图标就不在了，坐标得提前抓。
        //   起点是**渲染时记下来的**（见 [renderBuyPreview] / [renderBuyItemFields]），这里直接取用。
        //   带 3 秒过期（服务端可能最终没发包：余额不足 / 组件被关），不会留脏值。
        //   精灵与物品各有各的一套动画、`Origin` 也是两个类，所以两边分开登记。
        buyIconOrigin?.let(com.shusheng.cobblemarket.client.PokemonCelebrationAnimation::setPendingOrigin)
        buyItemIconOrigin?.let(com.shusheng.cobblemarket.client.ItemCelebrationAnimation::setPendingOrigin)

        // ⚠ kind 取 [currentKind]：窗口开着时主控件已隐藏、切不了 tab，所以它 == 打开窗口时那个 tab
        // ⚠ 带上**玩家看到的那一条**的 id：服务端比对，对不上就拒绝（防按改后的内容 / 价格成交）
        sendToServer(BuyPointMarketEntryPayload(buySlot, count, currentKind(), buySlotEntryId, shopId))
        // 收回动画与**庆祝动画共存**（2026-09-27 用户拍板）：弹窗缩回格子的同时，精灵从弹窗图标
        // 位置起飞。能共存是因为庆祝动画的起点 `buyIconOrigin` 在**渲染时**记录，而收回期间内容段
        // 早已淡出、`renderBuyPreview` 不执行 ⇒ 起点保留弹窗静止时那一份，不会跟着窗口一起缩。
        closeBuyDialog()
    }

    /** 窗口内容：遮罩 + 九宫格 + 标题 + 预览 + 字段 + 价格行 */
    private fun renderBuyDialog(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val entry = entryAt(buySlot) ?: return
        val x = editDialogX()
        val y = editDialogY()
        // 遮罩压暗下层（不压的话底下那排格子会跟弹窗抢注意力）
        drawScreenDimMask(context, width, height)
        // 背景画**插值矩形**：展开动画期间窗口从格子那里长大（播完即最终矩形）。
        // ⚠ 用矩形重画九宫格，不用 matrices 缩放贴图 —— 后者会把圆角拉变形
        val animRect = dialogAnimRect()
        drawNineSlice(
            context, DIALOG_BACKGROUND_TEXTURE,
            animRect[0], animRect[1], animRect[2], animRect[3], 0, DIALOG_BACKGROUND_TEX_H
        )
        // 动画期间**只画背景**：内容挤在小矩形里会糊成一团 ⇒ 等内容段淡入（见 [dialogAnimContentAlpha]）。
        // ⚠ 内容里有多个 return 分支，shader 必须 try/finally 恢复（漏了会让整个界面串色）
        val contentAlpha = dialogAnimContentAlpha()
        if (contentAlpha <= 0f) return
        context.setShaderColor(contentAlpha, contentAlpha, contentAlpha, contentAlpha)
        try {
        context.drawCenteredTextWithShadow(
            textRenderer,
            // 购买窗给**所有人**（左键）；服主 / 持 `points.shop` 的人右键是编辑。
            // 「查看」那套只读标题随只读窗一并去掉（2026-09-25 用户定）
            Text.translatable(
                if (entry.kind == ShopEntry.KIND_POKEMON) "cobblemarket.points.store.buy_title"
                else "cobblemarket.points.store.buy_item_title"
            ).formatted(Formatting.GOLD),
            x + editDialogW() / 2, y + 10, 0xFFFFFF
        )

        if (entry.kind == ShopEntry.KIND_POKEMON) {
            entry.pokemon?.let { renderBuyNameLines(context, it) }
            renderBuyPreview(context, entry, delta)
            entry.pokemon?.let { renderBuyPokemonFields(context, it, mouseX, mouseY) }
        } else {
            renderBuyItemFields(context, entry)
        }

        renderBuyPriceRow(context, entry)

        // **卡片价格优惠**（2026-10-28）：购买按钮上方一行 —— 卡图标 +「价格优惠 N%」。
        // 折扣按玩家各算各的（[ShopCardDiscountCache]），没折扣 / 还没收到包时不画。
        val discountRate = ShopCardDiscountCache.rateOf(shopId)
        if (discountRate > 0.0 && !isClaimShop) {
            val line = Text.translatable(
                "cobblemarket.points.store.card_discount",
                shopDiscountPercentText(discountRate)
            )
            // 图标用**16×16**（物品图标标准尺寸；12px 时用户实测「卡片好小」）——
            // 这一行的高度预算见 BUY_DISCOUNT_EXTRA_H，文字与图标中心对齐（差 1px 可接受）
            val iconSize = 16
            val gap = 3
            val totalW = iconSize + gap + textRenderer.getWidth(line)
            // 居中于「购买 / 取消」那一对按钮（与价格行的「总价」同一条中轴）
            val confirmX = x + editDialogW() - EDIT_PAD - BUY_BTN_W * 2 - BUTTON_GAP
            val left = confirmX + (BUY_BTN_W * 2 + BUTTON_GAP - totalW) / 2
            val lineY = editButtonRowY() - 12
            drawShopCardIcon(context, ShopCardDiscountCache.cardOf(shopId), left, lineY - 5, iconSize)
            context.drawTextWithShadow(textRenderer, line, left + iconSize + gap, lineY, 0xFFCC33)
        }

        // 冷却中：倒计时画在**购买按钮左侧**（灰字，与领取店「下次领取」同款），并把按钮置灰。
        // ⚠ dimmed 与「编辑锁」共用：任一成立就置灰 —— render 每帧都会重设，别只写 cooldown
        //   （否则编辑锁的置灰会被这里覆盖掉）。
        val cooldown = cooldownRemaining(entry)
        buyConfirmButton?.dimmed = shopEditLocked || cooldown > 0
        if (cooldown > 0 && !isClaimShop) {
            val cooldownText = Text.translatable(
                "cobblemarket.points.store.buy_cooldown",
                RemainingTime.text(
                    CustomShopCooldownCache.untilOf(shopId, entry.entryId),
                    "cobblemarket.points.store.cooldown_ready",
                    localizedUnits = true
                )
            )
            val confirmX = x + editDialogW() - EDIT_PAD - BUY_BTN_W * 2 - BUTTON_GAP
            val textX = (confirmX - 8 - textRenderer.getWidth(cooldownText)).coerceAtLeast(x + EDIT_PAD)
            context.drawTextWithShadow(
                textRenderer, cooldownText, textX,
                editButtonRowY() + (16 - 8) / 2,
                0xAAAAAA
            )
        }

        // 本地挡下的非法值（服务端拒绝的原因另走聊天栏）
        buyHintMsg?.let { msg ->
            if (System.currentTimeMillis() < buyHintUntil) {
                context.drawTextWithShadow(textRenderer, msg, x + EDIT_PAD, editPriceRowY() - 11, 0xFF6666)
            }
        }
        } finally {
            context.setShaderColor(1f, 1f, 1f, 1f)
        }
    }

    /** 折扣率 → 百分比文本（与成功回执同一口径，见 `discountPercentTextWithSymbol`） */
    private fun shopDiscountPercentText(rate: Double): String =
        com.shusheng.cobblemarket.util.discountPercentTextWithSymbol(rate)

    /** 画打折卡种的物品图标（紫电卡 / 金轮卡）；物品缺失时什么都不画，只是少了图标 */
    private fun drawShopCardIcon(context: DrawContext, card: String, x: Int, y: Int, size: Int) {
        val id = if (card == ShopCardDiscount.CARD_BLACK) "meowth_black_card" else "meowth_purple_card"
        val item = Registries.ITEM.get(net.minecraft.util.Identifier.of("cobblemarket", id))
        if (item == net.minecraft.item.Items.AIR) return
        com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
            itemStack = ItemStack(item),
            x = x.toDouble(),
            y = y.toDouble(),
            scale = size / 16.0,
            matrixStack = context.matrices
        )
    }

    /** 预览槽：与编辑窗同位置同尺寸（右侧 3 格），但画的是**商品规格**那一只 */
    private fun renderBuyPreview(context: DrawContext, entry: ShopEntry, delta: Float) {
        val slotSize = editPreviewSize()
        val slotX = editPreviewSlotX()
        val slotY = editPreviewSlotY()
        // ⚠ 用**大图版**槽位贴图：本弹窗的槽比列表图标大得多，86×86 那张放大会糊（见 drawPokemonSlotBig）
        drawPokemonSlotBig(context, slotX, slotY, slotSize)
        // 图标直接复用格子那份缓存（同一只精灵，没必要再建一个模型）
        val renderable = cellIcon(buySlot, entry).renderable
        // 记下这个图标的位置（买下时庆祝动画要从这里起飞）。**渲染时记录** = 与实际画出来的必然一致；
        // ⚠ 拿不到 renderable 时置空，免得沿用上一只的旧坐标（拍卖场同款处理）
        buyIconOrigin = if (renderable == null) null
        else com.shusheng.cobblemarket.client.PokemonCelebrationAnimation.Origin.ofIcon(
            x = slotX, y = slotY, size = slotSize, centerAnchorY = false
        )
        if (renderable == null) return
        val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
            com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
        try {
            PokemonIconRenderer.draw(
                context = context,
                renderable = renderable,
                state = buyPreviewState.stateFor(renderable),
                x = slotX, y = slotY, size = slotSize,
                delta = delta,
                animate = useFloat,
                clip = editPreviewClip(slotY, slotSize)
            )
            // 开窗后自动叫一声：**必须在渲染之后**（addFirstAnimation 依赖 draw 里建好的模型）
            if (buyCryAt != 0L && System.currentTimeMillis() >= buyCryAt) {
                buyCryAt = 0L
                IconCryClick.play(buyPreviewState.stateFor(renderable), renderable)
            }
        } catch (_: Exception) {
        }

        // 「非法」印章：贴**精灵预览图的右上角**（全项目统一口径，见 [EntryBadgeRenderer.drawIllegalBadge]）。
        // ⚠ 必须画在**预览之后** —— 名字行那个函数（[renderBuyNameLines]）跑在预览**之前**，
        //   印章画在那儿会被图标盖住（2026-10-02 收口时发现的坑）。
        // ⚠⭐ 「非法」那两个字在**悬停窗**里（用户 2026-09-30：名字行上挂一行红字不好看）——
        //   所以这里只画图标，并把矩形记下来给 [renderBuyPokemonFields] 末尾弹说明用
        //   （自绘元素没有内置 tooltip，只能自己来）。
        // ⚠⚠ 不合法时**必须显式清空** —— 否则切到别的商品时还挂着上一只的矩形，悬停会凭空弹说明
        buyIllegalBadgeRect = if (entry.pokemon?.illegal == true) {
            EntryBadgeRenderer.drawIllegalBadge(context, slotX, slotY, slotSize)
        } else {
            null
        }
    }

    /**
     * 精灵字段：**照搬编辑窗的位置**（同一张 [editFieldOrder] 表、同一套坐标函数），画成只读文本。
     * 值一律取自 **[ShopPokemonSpec]**（服务端下发的规格），**不读界面草稿** —— 两个窗口不共用草稿。
     */
    private fun renderBuyPokemonFields(
        context: DrawContext, spec: ShopPokemonSpec, mouseX: Int, mouseY: Int
    ) {
        // 证章行的悬停窗落点（在字段循环里判定、**全部画完之后**才画，才浮得在字段上面）。
        // ⚠ 存的是**鼠标位置**，不是那一行的坐标 —— 具体落点由 [renderMarksTooltip] 按
        //   「鼠标上方优先」算，与界面里其它悬停窗**同一套**（照 [HistoryScreen] 的 `renderTooltip`）
        var marksTooltipAt: Pair<Int, Int>? = null
        // 「变体」那一行的悬停（同证章：格子只有 82px，多维度时值会被截断，得能看全）
        var featureTooltipAt: Pair<Int, Int>? = null
        for (key in editFieldOrder()) {
            if (key == FIELD_BREAK) continue
            val (row, col) = posOf(key)
            if (!editRowVisible(row)) continue
            // 「证章」那一行：悬停弹小窗把**全部**证章图标排出来（见 renderMarksTooltip）
            if (key == KEY_MARKS && spec.marks.isNotEmpty() && overBuyField(mouseX, mouseY, row, col)) {
                marksTooltipAt = mouseX to mouseY
            }
            // 「变体」那行同理：整串变体名在 82px 的格子里必然被截断
            if (key == KEY_FEATURE && spec.aspects.isNotEmpty() && overBuyField(mouseX, mouseY, row, col)) {
                featureTooltipAt = mouseX to mouseY
            }
            drawBuyField(
                context, editColX(col), editRowY(row), editColW(), buyFieldLine(key, spec),
                // 头目那行的标签是**徽章贴图**（与编辑窗按钮、列表行同一张），得单独画；其余字段没有
                badge = if (key == KEY_ALPHA) "alpha" else null,
                // 性别 / 体型画**值图标**（无性别 / 随机体型时为 null，那时照常显示文字值）
                valueIcon = fieldValueIcon(key, spec),
                // 球种 / 携带物画**物品图标**（携带物关掉可见性时不画，与商城格子同一个口径）
                icon = when (key) {
                    KEY_HELD -> if (spec.heldItemVisible) stackOfSpec(spec.heldItemSpec) else ItemStack.EMPTY
                    KEY_BALL -> spec.ball?.let { itemStackOf(it) }
                    else -> null
                }
            )
        }
        // 六项个体值 / 努力值：一行六个（与编辑窗同一行、同一列序）。
        // ⚠ **每项自己的颜色**，取的是市场悬停窗 / 列表行那张色表（用户 2026-09-25 要求照它来）；
        //   努力值一律红，同悬停窗里 EV 段。
        val ivRow = fieldRowCount()
        for (i in 0 until STAT_COUNT) {
            if (editRowVisible(ivRow)) {
                drawBuyField(
                    context, editColX(i), editRowY(ivRow), editColW(), ivLine(spec, i), STAT_ROW_COLORS[i]
                )
            }
            if (editRowVisible(ivRow + 1)) {
                drawBuyField(
                    context, editColX(i), editRowY(ivRow + 1), editColW(), evLine(spec, i), EV_ROW_COLOR
                )
            }
        }
        // 四个技能槽：**居中一行**（与编辑窗同一行，但**宽度不跟编辑窗走**）——
        // 编辑窗那 4 个是按钮，占满整行是为了放下长技能名（`Move 1: Sword Dance`）；
        // 这里只是文字，居中更好看（用户 2026-09-26 定）。
        if (editRowVisible(skillRow())) {
            val skills = MoveText.slotIds(spec.moves)
            for (i in 0 until MoveText.SLOTS) {
                drawBuyField(context, editColX(1 + i), editRowY(skillRow()), editColW(), skillLine(skills, i))
            }
        }

        // 悬停窗**最后画**（浮在上面）
        marksTooltipAt?.let { (tx, ty) -> renderMarksTooltip(context, spec, tx, ty) }
        featureTooltipAt?.let { (tx, ty) -> renderFeatureTooltip(context, spec, tx, ty) }
        // 「非法」印章的悬停窗（同上面两个：**最后画**才浮在字段上面）。
        // ⚠ 名额是**名字行**记下的（那行画得比这里早），判的却是这一刻的鼠标位置
        buyIllegalBadgeRect?.let { r ->
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                renderIllegalBadgeTooltip(context, mouseX, mouseY)
            }
        }
    }

    /**
     * 购买窗「变体」那行的悬停小窗：把**每个变体**单独列一行（格子里那串会被截断）。
     *
     * ⚠ 显示名走 [featureValueLabel]（官方形态词条 → 物品名 → 原文），**不依赖变体候选表** ——
     *   购买窗是玩家看的，那张表要 `points.shop` 权限才发得下来。
     * ⚠ 外框复用 [withTooltipLayer]（与「变体」按钮的悬停窗、以及证章那枚同一套落点与背景）。
     */
    private fun renderFeatureTooltip(context: DrawContext, spec: ShopPokemonSpec, mouseX: Int, mouseY: Int) {
        val values = featureAspectsOf(spec).map { featureValueLabel(it) }
        if (values.isEmpty()) return
        val pad = MARK_TIP_PAD
        val lineH = 11
        val w = values.maxOf { textRenderer.getWidth(it) } + pad * 2
        val h = values.size * lineH + pad * 2
        withTooltipLayer(context, mouseX, mouseY, w, h) { tx, ty ->
            values.forEachIndexed { i, v ->
                context.drawTextWithShadow(textRenderer, v, tx + pad, ty + pad + i * lineH, 0xFFFFFF)
            }
        }
    }

    /** 鼠标在不在这一个字段格子里（悬停判定用 —— 与绘制共用 [editColX] / [editRowY] 那套坐标） */
    private fun overBuyField(mouseX: Int, mouseY: Int, row: Int, col: Int): Boolean {
        val x = editColX(col)
        val y = editRowY(row)
        return mouseX >= x && mouseX < x + editColW() && mouseY >= y && mouseY < y + EDIT_ROW_H
    }

    /**
     * 购买窗「证章」那一行悬停时的小窗：把**全部**证章图标排出来（每行
     * [EntryBadgeRenderer.MARKS_PER_ROW] 个，多了换行）。
     *
     * ⚠ 与市场行悬停的区别**就在这里**：那边走 `EntryBadgeRenderer.drawMarksRow`，
     * **有意**只画一行、多出来的显示成「+N」—— **那是设计，不是缺陷**：行悬停窗里空间有限，
     * 证章铺开会把名字 / 属性 / IV 那些信息挤没（用户 2026-09-25 专门澄清过）。
     * ⚠ **别去"修"那一边**：它跟本条是不同的取舍。
     * 购买窗这一行**专门给它做了悬停窗**（不跟别的东西抢位置），所以才**全画出来**。
     *
     * ⚠ 图标尺寸与间距（12px 一格、8×8 图标）与 `drawMarksRow` 同口径，两边看着才是一套。
     */
    private fun renderMarksTooltip(context: DrawContext, spec: ShopPokemonSpec, mouseX: Int, mouseY: Int) {
        // 同购买窗那一行：**头目自动带的那个也要列出来**（服主设了头目就该看到头目之证）
        // ⚠ 取的是 **`Mark.texture`**，不是证章的 id —— 这是两个字段：id 形如 `cobblemon:mark_alpha`
        //   （数据文件的 key），贴图在数据文件里另给。原先直接把 id 当贴图传给 blitk，
        //   等于去加载一张不存在的图（2026-09-25 顺手修）
        val textures = effectiveMarksOf(spec.marks, spec.alpha).mapNotNull { id ->
            Identifier.tryParse(id)?.let { com.cobblemon.mod.common.api.mark.Marks.getByIdentifier(it) }?.texture
        }
        if (textures.isEmpty()) return
        // ⚠ 这是**独立的证章悬停小窗**：它自身的宽就是按 `perRow` 反算出来的
        //   （`w = minOf(size, perRow) * CELL + pad*2`）⇒ 沿用**固定列数**，
        //   与 [EntryBadgeRenderer.marksPerRow] 那套「跟随可用宽度」是两回事（那个用在主窗口里）
        val perRow = 10
        val rows = (textures.size + perRow - 1) / perRow
        val pad = MARK_TIP_PAD
        val w = minOf(textures.size, perRow) * MARK_CELL + pad * 2
        val h = rows * MARK_CELL + pad * 2
        // 落点与界面里其它行悬停窗**同一套**（照 [HistoryScreen.renderTooltip]）：
        // 水平鼠标右移 12、**优先放在鼠标上方 4px**（顶不下才翻到下方），再钳进屏幕。
        // ⚠ 别自己另发明一套 —— 所有悬停窗必须长在同一个位置（2026-09-25 用户指出）
        val tx = minOf(mouseX + 12, width - w - 12).coerceAtLeast(4)
        val tyAbove = mouseY - h - 4
        val ty = if (tyAbove <= 0) minOf(mouseY + 12, height - h) else tyAbove
        // ⚠ **必须抬 Z**：不抬的话下层那些字段文字会从悬停窗上透出来
        //   （同 HistoryScreen 那句 `translate(0, 0, 400)`）
        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, tx, ty, w, h, 0, DIALOG_BACKGROUND_TEX_H)
        textures.forEachIndexed { idx, texture ->
            com.cobblemon.mod.common.api.gui.blitk(
                matrixStack = context.matrices, texture = texture,
                x = tx + pad + (idx % perRow) * MARK_CELL,
                y = ty + pad + (idx / perRow) * MARK_CELL,
                width = 8, height = 8
            )
        }
        context.matrices.pop()
    }

    /**
     * **名字 + 属性**行 —— 画在编辑窗「物种搜索框」那个位置（购买窗没有搜索框，那一条正好空着）。
     *
     * ⚠ 这两行是购买窗**最要紧的信息**，而编辑窗里物种是个搜索框、不在 [editFieldOrder] 里 ——
     * 照搬字段表会把它整条漏掉（2026-09-25 用户实测指出）。
     * 走与全项目悬停窗**同一个入口**（[EntryBadgeRenderer.nameWithLevelLine] / [typeLine]）：
     * 名字带主属性色、属性名分段着色，Lv 与「+」这些陪衬文字不设色。
     * ⚠ 与悬停窗不同的是：这里名字后面**不挂闪光星标** —— 闪光由下面第一格字段表达（见下方注释）。
     */
    private fun renderBuyNameLines(context: DrawContext, spec: ShopPokemonSpec) {
        val species = Identifier.tryParse(spec.species)?.let { PokemonSpecies.getByIdentifier(it) } ?: return
        val primary = "cobblemon.type.${species.primaryType.name.lowercase()}"
        val secondary = species.secondaryType?.let { "cobblemon.type.${it.name.lowercase()}" }.orEmpty()
        val y = editDialogY() + EDIT_SPECIES_Y + (EDIT_ROW_H - 8) / 2
        // ⚠ **名字后面不挂闪光星标**：闪光信息在下面第一格字段里已经有了（[shinyStarText]：
        //   必闪 = 金 ★、按概率 = ★ + 百分比、不闪 = 深灰 ★），名字后面再挂一个
        //   就是同一件事显示两遍（用户 2026-09-26 报「第二行又显示了一个闪光符号」）。
        val nameLine = EntryBadgeRenderer.nameWithLevelLine(
            Text.literal(SpeciesText.translated(species).string),
            primary,
            // 等级留空 = 生成 1 级（与 PointMarketFactory 同口径）
            spec.level ?: 1
        )
        context.drawTextWithShadow(textRenderer, nameLine, editDialogX() + EDIT_PAD, y, 0xFFFFFF)
        // 属性跟在名字右边（名字宽度是**画出来的**那个宽度，不是估算 —— 两段才不会叠字）
        val typeLine = EntryBadgeRenderer.typeLine(primary, secondary)
        val typeX = editDialogX() + EDIT_PAD + textRenderer.getWidth(nameLine) + 8
        context.drawTextWithShadow(textRenderer, typeLine, typeX, y, 0xFFFFFF)
        // ⚠ 「非法」印章**不再画在这一行**（2026-10-02 收口）：它现在贴**精灵预览图的右上角**、
        //   走全项目共用入口（原先画在属性行右边、固定 12px —— 与别处两套口径）。
        //   ⚠ 也不能在这儿画：本函数跑在 [renderBuyPreview] **之前**，画了会被预览图盖住。
        //   悬停命中矩形（[buyIllegalBadgeRect]）也随它一起搬到了那边。
    }

    /**
     * 名字行那枚「非法」印章的悬停窗 —— 自绘元素没有内置 tooltip，只能自己画。
     *
     * ⚠ 落点与外观**走全项目同一套** [withTooltipLayer]（同 [renderMarksTooltip] /
     *   [renderFeatureTooltip]）：鼠标右移 12、优先上方 4px、钳进屏幕 + 抬 Z —— 别自己另发明一个位置。
     * ⚠ 文字用**红色**（与格子角标、售罄的 0、EV 段同一个红）。这里空间不受限，所以照**完整词条**画
     *  （名字行上挂不下那两个字，才挪进来的 —— 用户 2026-09-30）。
     */
    private fun renderIllegalBadgeTooltip(context: DrawContext, mouseX: Int, mouseY: Int) {
        val text = Text.translatable("cobblemarket.points.store.illegal")
        val pad = MARK_TIP_PAD
        val w = textRenderer.getWidth(text) + pad * 2
        val h = 8 + pad * 2
        withTooltipLayer(context, mouseX, mouseY, w, h) { tx, ty ->
            context.drawTextWithShadow(textRenderer, text, tx + pad, ty + pad, 0xFF5555)
        }
    }

    /** 物品格：右侧大图 + 大图**左侧**那一列词条（物品名 + 附魔 / 招式 / 自定义名 / 耐久） */
    private fun renderBuyItemFields(context: DrawContext, entry: ShopEntry) {
        val spec = entry.item ?: return
        // ⚠ 原来这里还画了一行「物品：xxx」（占着左上角那条物种搜索框的位置）——
        //   自从大图左侧上了**词条列**（第一行就是物品名），它就纯属重复了（用户 2026-09-26 指出）。
        //   精灵格那边仍保留名字行：它的名字带着属性色 + Lv，词条列里没有这些。
        // 物品图标（大图）：画在右侧预览区同一位置，玩家能看清附魔/改名的样子
        val icon = cellIcon(buySlot, entry).stack ?: return
        val slotSize = editPreviewSize()
        val slotX = editPreviewSlotX()
        val slotY = editPreviewSlotY()
        // ⚠ **不画 [drawPokemonSlot]** —— 与添加物品窗同一个口径（见 [renderEditItemPreviewSlot]）：
        //   那是给精灵站的底座（影子 + 地面），物品是 16×16 贴图放大、自己就是个方块，垫一层只显得脏
        // 大图**左侧**那列词条：**与添加物品窗共用同一个** [renderItemInfoLines]
        // （两个窗口不会同时开，所以那份词条缓存也是共用的，不必各存一份）
        renderItemInfoLines(context, icon, slotX, slotY, slotSize)
        // 记下位置（买下时庆祝动画从这里起飞）—— 渲染时记录，与实际画出来的必然一致
        buyItemIconOrigin = com.shusheng.cobblemarket.client.ItemCelebrationAnimation.Origin.ofIcon(
            x = slotX, y = slotY, size = slotSize
        )
        // 物品图标是 16×16，缩放到占满槽位（与 ItemCelebrationAnimation 里那个 `size / 16f` 同口径）
        val scale = slotSize / 16f
        context.matrices.push()
        context.matrices.translate((slotX + slotSize / 2f).toDouble(), (slotY + slotSize / 2f).toDouble(), 0.0)
        context.matrices.scale(scale, scale, 1f)
        // ⚠ 走 [drawItemWithBar]（本项目画物品图标的**唯一入口**）：它顺带画耐久条，
        //   条在缩放矩阵下会跟着等比放大（庆祝动画 / 悬停大图都是这么做的）
        drawItemWithBar(context, icon, -8, -8)
        context.matrices.pop()
    }

    /**
     * 底部一行：单价 / 剩余 / 数量（物品格）/ 总价。
     *
     * ⚠ **不按上面那 6 列摆** —— 字段网格的列宽是给「标签 + 短值」用的，
     * 而这一行每项都带一个**金额 + 图标**（`单价：▣9,999`），按列宽摆必然溢出。
     * ⚠ 横向位置是**四等分**（见 [buyColW]），位置一律走 [buyStockLabelX] / [buyCountLabelX]，
     * **别在这儿另算一份**。
     */
    private fun renderBuyPriceRow(context: DrawContext, entry: ShopEntry) {
        val y = editPriceRowY() + (EDIT_ROW_H - 8) / 2
        // 领取店没有价格（购买窗只给普通玩家，领取店不会走到这里），不画单价
        if (!isClaimShop) {
            context.drawTextWithShadow(
                textRenderer,
                Text.translatable(
                    "cobblemarket.points.store.buy_price",
                    CurrencyService.amountText(entry.price, shopCurrency)
                ),
                buyRowLeft(), y, 0xFFFFFF
            )
        }
        // 「剩余」那一格：不可编辑的「剩余：N」（购买窗只读；补货/下架在编辑窗）
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable(
                "cobblemarket.points.store.buy_stock",
                if (entry.stock == ShopEntry.UNLIMITED) Text.translatable("cobblemarket.points.store.value.unlimited")
                else Text.literal(entry.stock.toString())
            ),
            buyStockLabelX(), y, 0xFFFFFF
        )
        // 数量 / 总价：**只有物品格有**（精灵格买一次就是一只，显示总价只是把单价重复一遍）
        // 领取店没有购买数量/总价（金额与数量都不适用）
        if (entry.kind != ShopEntry.KIND_POKEMON && !isClaimShop) {
            // ⚠ 标签位置与建控件（[buyCountX]）**同源**，别在这儿再算一遍第 3 格起点
            context.drawTextWithShadow(
                textRenderer, Text.translatable("cobblemarket.points.store.buy_count"), buyCountLabelX(), y, 0xFFFFFF
            )
            // 有冷却的商品没有数量框（恒 1 件），在框的位置画一个只读的「1」
            if (isCooldownEntry(entry)) {
                context.drawTextWithShadow(textRenderer, Text.literal("1"), buyCountX(), y, 0xFFFFFF)
            }
            val total = entry.price * buyCount.coerceAtLeast(1)
            // 「总价」**就在这一行**（用户 2026-09-26 澄清：不是新起一行），只是水平位置
            //   **居中于购买 / 取消那一对按钮**，而不是居中于窗口 —— 这一行是四项等分铺满的，
            //   居中于窗口会直接压在「剩余 / 数量」那两格上（第一版就是这么错的重叠）。
            // ⚠ 这是混排行（汉字 + 金额 + 货币图标），别用 drawCenteredTextWithShadow
            //   —— 它会让行首汉字高一点（项目踩过的坑），算好 x 再画。
            val totalText = Text.translatable(
                "cobblemarket.points.store.buy_total",
                CurrencyService.amountText(total, shopCurrency)
            )
            val btnLeft = editDialogX() + editDialogW() - EDIT_PAD - BUY_BTN_W * 2 - BUTTON_GAP
            val btnCenter = btnLeft + (BUY_BTN_W * 2 + BUTTON_GAP) / 2
            // 居中之后**再往右顶到「数量」输入框之后**：金额特别长时居中的结果会压到那个框上，
            // 宁可让它超出窗口右缘（金额极大时的既有风险），也不能盖住别人家的控件
            val totalX = (btnCenter - textRenderer.getWidth(totalText) / 2)
                .coerceAtLeast(buyCountX() + BUY_COUNT_W + 4)
            context.drawTextWithShadow(textRenderer, totalText, totalX, y, 0xFFFFFF)
        }
    }

    // ── 购买窗底部那一行的横向位置（**四等分**）──
    //
    // ⚠ 用户 2026-09-26 定：**单价 / 剩余 / 数量 这三个的位置不许动** ——
    //   中间试过一版"单价吃剩余、另两格按最宽内容给足"，位置一乱他一眼就看出来了（截图比对）。
    //   所以这里一律回到等分：第 1 格单价、第 2 格剩余、第 3 格数量（总价已搬到按钮上方自成一行）。

    /** 底部一行的格宽（四等分；总价搬走之后第 4 格空着） */
    private fun buyColW(): Int = (editDialogW() - EDIT_PAD * 2) / 4

    private fun buyRowLeft(): Int = editDialogX() + EDIT_PAD

    /** 「剩余」标签（或只读值）的左缘 = 第 2 格 */
    private fun buyStockLabelX(): Int = buyRowLeft() + buyColW()

    /** 「数量：」标签的左缘 —— 接在「剩余」那格之后，再让开 [BUY_COUNT_LABEL_GAP] 别贴死左边的框 */
    private fun buyCountLabelX(): Int = buyRowLeft() + buyColW() * 2 + BUY_COUNT_LABEL_GAP

    /**
     * 数量输入框的 x：紧贴「数量：」标签之后 —— **用标签的实际宽度**，不是 [EDIT_LABEL_W]。
     *
     * ⚠ `EDIT_LABEL_W` 是编辑窗「价格 / 库存」那对标签的宽度（32，按最长的英文 `Stock` 留的），
     *   与「数量：」无关；照它偏移会让标签右缘到输入框之间空出一大截（用户 2026-09-26 报：
     *   「数量」离自己的输入框太远）。渲染（[renderBuyPriceRow]）与建控件（[buildBuyDialog]）共用这一个。
     */
    private fun buyCountX(): Int =
        buyCountLabelX() + textRenderer.getWidth(Text.translatable("cobblemarket.points.store.buy_count")) + 4

    /**
     * 字段行的**值图标** —— 值不用文字而用贴图表达时走它（性别 / 体型）。
     * ⚠ 两种图标**尺寸不一样**，所以用 sealed 分开，别拿一个字符串猜是哪一种。
     */
    private sealed interface FieldValueIcon {
        /** 公母图标（Cobblemon PC 贴图，6×8 —— 与编辑窗那个性别按钮同源）。⚠ **无性别没有对应图标**，那时走文字 */
        data class Gender(val male: Boolean) : FieldValueIcon
        /** 体型徽章（37×16 按 0.5 → 18.5×8，与列表行、头目那行同一张）。随机 / 未设时走文字 */
        data class Size(val badge: String) : FieldValueIcon
    }

    /**
     * 一行只读字段（**标签冒号写在词条里**，这里只负责截断与摆放）。
     * [color] 是**行色** —— 默认白，个体值 / 努力值那几行按项给色（见 [STAT_ROW_COLORS]）。
     * [badge] = **行首徽章**（体型名，只有头目那行传 `"alpha"`）：徽章是**贴图**、插不进 [line] 这个
     *   Text 里，所以单独画一个行首图，后面的文字**往右让位**、可用宽度相应收窄。
     * [valueIcon] = **行内值图标**（性别 / 体型）：值同样是贴图，画在标签文本之后
     *   （用户 2026-09-26：「性别：这里用性别图标，不要用雄性雌性；体型也用图标」）。
     *   ⚠ 调用方要**同时**让 [line] 的值段留空（判据见 [fieldValueIcon]），否则文字和图标会各显一遍。
     */
    private fun drawBuyField(
        context: DrawContext, x: Int, y: Int, w: Int, line: Text,
        color: Int = 0xFFFFFF,
        badge: String? = null,
        valueIcon: FieldValueIcon? = null,
        icon: ItemStack? = null
    ) {
        var textX = x
        var textW = w
        if (badge != null) {
            // 行首徽章**复用列表行那一套**（[EntryBadgeRenderer.drawSizeBadgeIcon]：同一张 37×16 的图、
            // 同样 0.5 缩放，还带返回宽度）—— 别在这儿自己 blit：贴图缩放要走 matrices，是踩过的坑
            val badgeW = EntryBadgeRenderer.drawSizeBadgeIcon(context, badge, textX, y + (EDIT_ROW_H - 8) / 2)
            textX += badgeW + 2
            textW -= badgeW + 2
        }
        // ⚠ 截断用 truncateStyled：truncateText 会把 Text 压成一段、丢掉图标字形的字体样式
        val text = TextUtil.truncateStyled(line, textW)
        context.drawTextWithShadow(textRenderer, text, textX, y + (EDIT_ROW_H - 8) / 2, color)
        // [valueIcon] = **行内值图标**（性别 / 体型）：紧跟在标签文本之后（值段已由调用方留空）
        if (valueIcon != null) {
            val vy = y + (EDIT_ROW_H - 8) / 2
            val vx = textX + textRenderer.getWidth(text) + 2
            when (valueIcon) {
                is FieldValueIcon.Gender -> com.cobblemon.mod.common.api.gui.blitk(
                    matrixStack = context.matrices,
                    texture = if (valueIcon.male) GENDER_ICON_MALE else GENDER_ICON_FEMALE,
                    x = vx, y = vy, width = 6, height = 8
                )
                is FieldValueIcon.Size ->
                    EntryBadgeRenderer.drawSizeBadgeIcon(context, valueIcon.badge, vx, vy)
            }
        }
        // [icon] = **行尾物品图标**（携带物 / 球种用）：这两个字段的值是物品，画名字会放不下
        //（「附魔金苹果」这类长名，一格只有 82px）—— 与编辑窗按钮上那个图标同一套画法
        if (icon != null && !icon.isEmpty) {
            drawItemWithBar(
                context, icon,
                textX + textRenderer.getWidth(text) + 2, y + (EDIT_ROW_H - 16) / 2
            )
        }
    }

    /**
     * 这一格的**值能不能用图标表达** —— 能就返回图标，不能（无性别 / 随机体型）返回 null 走文字。
     *
     * ⚠ **判据只有这一个**：渲染（[drawBuyField] 的 `valueIcon`）与取文本（[buyFieldLine] 把值段留空）
     *   都调它。两边各判各的，必然出现「图标和文字各显一遍」或「什么都没有」。
     */
    private fun fieldValueIcon(key: String, spec: ShopPokemonSpec): FieldValueIcon? = when (key) {
        KEY_GENDER -> when (spec.gender?.uppercase()) {
            "MALE" -> FieldValueIcon.Gender(true)
            "FEMALE" -> FieldValueIcon.Gender(false)
            // 无性别在游戏里就没有对应图标（Cobblemon 只给了 ♂ / ♀ 两张 pc 贴图）→ 退回文字
            else -> null
        }
        // 0 = 随机 / 未设（编辑窗的「不指定」）
        KEY_SIZE -> sizeIndexOf(spec.scale).takeIf { it > 0 }
            ?.let { PokemonSizeCategory.entries.getOrNull(it - 1) }
            ?.let { FieldValueIcon.Size(it.name) }
        else -> null
    }

    /** 一个字段的只读文本；返回空文本 = 这项不显示 */
    private fun buyFieldLine(key: String, spec: ShopPokemonSpec): Text {
        val random = Text.translatable("cobblemarket.points.store.value.random")
        val default = Text.translatable("cobblemarket.points.store.value.default")
        val none = Text.translatable("cobblemarket.points.store.value.none")
        return when (key) {
            // 星标写法与编辑窗的闪光按钮**同一套**（见 [shinyStarText]）—— 用户 2026-09-26：
            // 原来写「闪光：按概率 50%」，英文 102px 放不进一格、被截断
            KEY_SHINY -> shinyStarText(
                always = "shiny" in spec.aspects,
                chancePercent = spec.shinyChance.takeIf { it > 0f }?.let { formatChance(it * 100f) }
            )
            // 闪光概率那格在编辑窗是个输入框，只读窗没有可显示的独立信息（并在闪光里了）
            KEY_SHINY_CHANCE -> Text.empty()
            // ⚠ 只显示**形态**的 aspect：`spec.aspects` 里还混着「变体」（species feature）的值，
            //   它们跟形态是两回事（2026-09-27 拆开），混进来这行会显示成看不懂的花纹串。
            //   判据走**正向白名单**（该物种 forms 的 aspects）—— 玩家端**拿不到候选表**
            //   （那要 `points.shop` 权限），所以这里不能靠"减掉变体"来滤
            KEY_FORM -> Text.translatable(
                "cobblemarket.points.store.field.form",
                formAspectsOf(spec)
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("/") { SpeciesText.aspectLabel(it) } ?: default.string
            )
            // 变体（species feature）：`spec.aspects` 去掉形态、去掉闪光，剩下的就是它 ——
            // 正好与上面「形态」那行**互为补集**，两行加起来就是全部 aspects。
            // ⚠ 显示名走 [featureValueLabel]（官方形态词条 → 物品名 → 原文），**不能**走
            //   "按候选表翻名字"：玩家端拿不到变体候选表（那要 `points.shop` 权限才发）
            KEY_FEATURE -> Text.translatable(
                "cobblemarket.points.store.field.feature",
                featureAspectsOf(spec)
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("/") { featureValueLabel(it) } ?: default.string
            )
            // 等级：服主留空 = 生成 1 级（与官方 PokemonProperties 同口径，见 PointMarketFactory）
            KEY_LEVEL -> Text.translatable("cobblemarket.points.store.field.level", (spec.level ?: 1).toString())
            // 极巨等级：**所有**精灵都能极巨化，所以不受物种约束，照实显示（0 也照实写）
            KEY_DMAX -> Text.translatable("cobblemarket.points.store.field.dmax", spec.dmaxLevel.toString())
            // 性别 / 体型：**值段留空**、实际值由 [drawBuyField] 的 `valueIcon` 画贴图
            //（与下面球种同一个口径）。⚠ 判据共用 [fieldValueIcon] —— 两边各判各的会各显一遍
            KEY_GENDER -> Text.translatable(
                "cobblemarket.points.store.field.gender",
                if (fieldValueIcon(KEY_GENDER, spec) != null) ""
                else when (spec.gender?.uppercase()) {
                    "MALE" -> Text.translatable("cobblemarket.aspect.male")
                    "FEMALE" -> Text.translatable("cobblemarket.aspect.female")
                    "GENDERLESS" -> Text.translatable("cobblemarket.points.store.value.genderless")
                    else -> random
                }
            )
            // ⚠ 特性**不能走 [fieldOf]**：那条路是"查本界面的候选表"，而特性候选表是按
            //   **编辑窗草稿物种**的特性池建的（见 [buildOptionItems] 的 `OPT_ABILITY`）——
            //   购买窗里 `editSpecies` 是空的 ⇒ 查不到 ⇒ 回退成**原始英文 id**
            //（用户 2026-09-26 报：中文界面里显示「特性：blaze」）。
            //   这里按 id 直接取特性自己的显示名，与物种无关（与 [PointMarketFactory.applyAbility] 同一个入口）。
            KEY_ABILITY -> abilityText(spec.ability)
            KEY_NATURE -> fieldOf("nature", spec.nature, OPT_NATURE, random)
            // 球种 / 携带物：值**不画名字**，交给 [drawBuyField] 的 icon 参数画物品图标
            //（用户 2026-09-26：「附魔金苹果」这类长名一格放不下）—— 与编辑窗按钮上那个图标同一个口径
            KEY_BALL -> Text.translatable("cobblemarket.points.store.field.ball", "")
            // 同上：有体型徽章时值段留空（判据共用 [fieldValueIcon]）
            KEY_SIZE -> Text.translatable(
                "cobblemarket.points.store.field.size",
                if (fieldValueIcon(KEY_SIZE, spec) != null) ""
                else PokemonSizeCategory.entries.getOrNull(sizeIndexOf(spec.scale) - 1)?.name ?: random.string
            )
            KEY_ALPHA -> Text.translatable(
                "cobblemarket.points.store.field.alpha",
                yesNoText(spec.alpha)
            )
            KEY_GMAX -> Text.translatable("cobblemarket.points.store.field.gmax", yesNoText(spec.gmaxFactor))
            KEY_TERA -> Text.translatable(
                "cobblemarket.points.store.field.tera",
                spec.teraType?.let { teraTextOf(it) } ?: random
            )
            KEY_TRADEABLE -> Text.translatable(
                "cobblemarket.points.store.field.tradeable", yesNoText(spec.tradeable)
            )
            KEY_HELD -> Text.translatable("cobblemarket.points.store.field.held", "")
            KEY_HELD_VISIBLE -> Text.translatable(
                "cobblemarket.points.store.field.held_visible", yesNoText(spec.heldItemVisible)
            )
            KEY_FRIENDSHIP -> Text.translatable(
                "cobblemarket.points.store.field.friendship",
                spec.friendship?.toString() ?: Text.translatable("cobblemarket.points.store.value.default")
            )
            // 证章数要把**头目自动带的那个**算进去（见 [effectiveMarksOf]）——
            // 只数 spec.marks 的话，服主没勾证章时这里显示 0，而实际生成出来的精灵带着头目之证
            //（2026-09-25 用户报）
            KEY_MARKS -> {
                val marks = effectiveMarksOf(spec.marks, spec.alpha)
                Text.translatable(
                    "cobblemarket.points.store.field.marks",
                    if (marks.isEmpty()) none else Text.literal(marks.size.toString())
                )
            }
            KEY_MIN_IVS -> Text.translatable(
                "cobblemarket.points.store.field.min_ivs",
                if (spec.ivs != null || spec.minPerfectIvs <= 0) Text.translatable("cobblemarket.points.store.value.any")
                else Text.literal("${spec.minPerfectIvs}V")
            )
            // 初始训练家（配信）：**服主没设就整格不画**（用户 2026-10-02 定）——
            // 默认情况下买到的初训家就是买家自己，这格写什么都是多余；
            // 做法与 [KEY_SHINY_CHANCE] 那格一致（编辑窗是输入框、只读窗没有独立信息可显示）
            KEY_OT -> spec.originalTrainer?.let {
                Text.translatable("cobblemarket.points.store.field.ot", it)
            } ?: Text.empty()
            else -> Text.empty()
        }
    }

    /**
     * 规格里**属于形态（forms）**的那些 aspect —— 购买窗显示「形态：」用。
     *
     * ⚠ 走**正向白名单**（该物种 forms 的 aspects），不是"从 spec.aspects 里减掉变体"：
     *   购买窗是**玩家**看的，而变体候选表要 `points.shop` 权限才发得下来 ⇒ 玩家端那张表**恒为空**，
     *   靠减法是滤不掉的。forms 是客户端本来就有的数据，谁看都在。
     */
    private fun formAspectsOf(spec: ShopPokemonSpec): List<String> =
        formAspectsAmong(
            Identifier.tryParse(spec.species)?.let { PokemonSpecies.getByIdentifier(it) },
            spec.aspects
        )

    /** 编辑窗里**已选中的形态**（`editAspects` 里属于 forms 的那些）—— 形态按钮的标签与列表打勾用 */
    private fun pickedFormAspects(): List<String> = formAspectsAmong(editSpecies, editAspects)

    /**
     * 草稿选中的形态**是不是「需要前置条件」的那种** —— 决定编辑窗那枚 `⚠` **点不点亮**（红 / 白）。
     *
     * ⚠ 判据走 [SpeciesText.hasGatedAspect]（按 aspect 值判，形态 / 变体 / 闪光**混放也能用**），
     *   **不用** [pickedFormAspects] 再逐 form 判：那条路要现算 `selectableForms`（本函数每帧都调，白烧）。
     * ⚠ 它**只影响颜色，不影响显隐** —— 那枚按钮是**常驻**的（判据会漏掉自制 / 魔改的形态，
     *   见 [editGatedInfoButton] 的注释）。
     * ⚠ 顺手挡一下物品窗：那里 `editAspects` 恒空、本来也判不出东西，写出来是为了意图明确。
     */
    private fun hasGatedPickedForm(): Boolean =
        editSlot >= 0 && isPokemonSlot() && SpeciesText.hasGatedAspect(editAspects)

    /**
     * 规格里的**变体** aspects（非形态、非闪光）—— 购买窗那行「变体：」用。
     *
     * ⚠ 只能**正向排除**（去掉 forms 的、去掉 `shiny`），**不能**靠"减掉候选表"：
     *   购买窗是**玩家**看的，而变体候选表要 `points.shop` 权限才发得下来 ⇒ 玩家端恒为空。
     *   排除法与 [formAspectsOf] 正好互补：两行加起来就是 `spec.aspects` 的全貌。
     */
    private fun featureAspectsOf(spec: ShopPokemonSpec): List<String> {
        val species = Identifier.tryParse(spec.species)?.let { PokemonSpecies.getByIdentifier(it) }
        val formAspects = SpeciesText.selectableForms(species).flatMap { it.aspects }.toSet()
        return spec.aspects.filter { it != "shiny" && it !in formAspects }
    }

    /**
     * 从一堆 aspect 里挑出**属于形态（forms）**的那些 —— 购买窗与编辑窗**共用这一份**。
     *
     * ⚠ 形态与变体在 `editAspects` / `spec.aspects` 里是**混放**的（存档层本来就只有 `aspects` 一个
     *   列表），凡是**显示形态**的地方都得过这一道，漏一处就出怪相：选了花纹之后形态按钮显示成
     *   花纹串、形态列表里原来的地区形态也不再被打勾（2026-09-27 用户实测报的就是前者）。
     * ⚠ 判据走**正向白名单**（该物种 forms 的 aspects），不是"减掉变体"：购买窗是**玩家**看的，
     *   而变体候选表要 `points.shop` 权限才发得下来 ⇒ 玩家端那张表恒为空，靠减法是滤不掉的。
     */
    private fun formAspectsAmong(species: Species?, aspects: List<String>): List<String> {
        val formAspects = SpeciesText.selectableForms(species).map { it.aspects.toSet() }
        return aspects.filter { a -> a != "shiny" && formAspects.any { a in it } }
    }

    /**
     * 草稿当前选中的**形态**（由 `editAspects` 反推）—— 特性池 / 技能池都跟着它走。
     *
     * ⚠ 与数据层**同一个 API**：造精灵读的是 `pokemon.form`，而 `Pokemon.updateForm()` 就是
     *   `species.getForm(aspects)`（见 `PointMarketFactory` 的 `applyAbility` / `applyMoves`）。
     *   界面判据与生成结果必须同源 —— 两处各自算同一个量，迟早分叉，
     *   下面那两条"候选池没跟着形态走"就是这么来的（2026-10-12 用户实测报洛托姆配不出「过热」）。
     * ⚠ `editAspects` 里混着变体（species feature）的 aspect —— **一起传进去是对的**：服务端也是拿
     *   `pokemon.aspects` 整个集合判形态，而 `getForm` 只要求"形态自己的 aspects 都在集合里"。
     */
    private fun pickedForm(): FormData? = editSpecies?.getForm(editAspects.toSet())

    /**
     * **当前形态的招式来源桶**（`桶 → 招式 id`，见 [SpeciesText.MoveSources]）——
     * 技能候选与它上面那排筛选按钮的**唯一数据源**。
     *
     * 优先用**服务端下发的那份**（`SpeciesMovesPayload`，六桶齐全）；没到 / 没请求到就用**本地**兜底 ——
     * 本地只有三桶：Cobblemon 同步物种给客户端时 `Learnset` **只传升级 / 招式机 / 遗传**
     * （`tutor` / `form_change` / `evolution` 一列都不传）⇒ 专用服务器上客户端**根本拿不到**教学招；
     * 单机是内存连接、不同步，本地那份就是全的。⚠ 兜底是**有意的**：宁可少几桶，也别让技能列表空着。
     *
     * ⚠ 形态怎么取：**自带招式表的形态**（洛托姆的五个家电那种）服务端会单独发一份，
     *   键是 `form.aspects` 拼的串；形态没写自己的表 ⇒ 表里不会有它 ⇒ 回落 [SpeciesMovesPayload.base]
     *   （与 `FormData.moves` 的回落语义完全一致）。
     */
    private fun pickedMoveBuckets(): Map<String, List<String>> {
        val species = editSpecies ?: return emptyMap()
        val payload = PointMarketCache.speciesMoves[species.resourceIdentifier.toString()]
        if (payload != null) {
            val key = pickedForm()?.aspects?.joinToString(",").orEmpty()
            return payload.forms[key] ?: payload.base
        }
        return pickedForm()?.let { SpeciesText.learnsetBuckets(it.moves) }.orEmpty()
    }

    /**
     * **当前形态的合法招式 id** = [pickedMoveBuckets] 六桶的并集。
     *
     * ⚠ 这个集合**恰好**是本体判「这只精灵能不能学」的那一套（`LearnsetQuery.LEGAL` =
     *   升级 / 遗传 / 教学 / 招式机 / 形态专属 / 进化），也恰好是服务端造精灵时补招用的
     *   `getAllLegalMoves()` ⇒ **只要服主选的招在这里面，造出来就是"正常能学到"的精灵**，
     *   不该被逼去开「自由选择」（那条路做出来的是贴着「非法」的）。
     */
    private fun legalMoveIds(): Set<String> = pickedMoveBuckets().values.flatten().toSet()

    /**
     * 拉某个物种的**完整招式表**（六桶）—— **已缓存就不发**。
     *
     * ⚠ 服务端有 500ms 节流（与其余列表请求同档），**被丢掉也不影响用**：技能候选会自动用本地
     *   三桶兜底（见 [pickedMoveBuckets]）。⇒ 只在"换物种 / 开列表"这类**人类节奏**的动作里发，
     *   丢了下次还有机会，不用自己搞重试。
     */
    private fun requestSpeciesMoves(species: Species?) {
        val id = species?.resourceIdentifier?.toString() ?: return
        if (PointMarketCache.speciesMoves.containsKey(id)) return
        sendToServer(RequestSpeciesMovesPayload(id))
    }

    /**
     * 这个物种的**完整招式表到了没** —— 没到就别拿池子判合法性（本地那份只有三桶）。
     *
     * ⚠ 用在两处**会动草稿**的判定上（池外反推、[normalizeMoves] 的清理）：本地池子是服务端池子的
     *   **真子集**（少教学 / 形态专属 / 进化），拿它判会把**合法的招**当成"池外值"误清掉。
     *   与 [normalizeAspects] 的"宁可晚判也不能误清"同一条规矩。
     * ⚠ 物种为空 ⇒ 返回 true：没物种就没得判，让调用方照原样走（它那边本来也只是清空）。
     */
    private fun moveTableReady(species: Species?): Boolean {
        val id = species?.resourceIdentifier?.toString() ?: return true
        return PointMarketCache.speciesMoves.containsKey(id)
    }

    /**
     * **特性候选池的唯一取法** = 当前形态的可能特性（含隐藏特性）。
     *
     * ⚠ 同 [legalMoves]：形态可以有自己的特性表（阿罗拉九尾是「降雪」、Mega 系各有专属特性），
     *   [FormData.abilities] 同样在形态自己没写表时**回落物种级**。
     */
    private fun legalAbilities(): List<PotentialAbility> = pickedForm()?.abilities?.toList().orEmpty()

    /** 下拉类字段（特性 / 性格 / 球种）：走编辑窗同一张候选表翻显示名，查不到就显示原值 */
    /**
     * 特性名（**按 id 直接取**，不依赖物种 —— 见 [buyFieldLine] 的 `KEY_ABILITY`）。
     *
     * `ability.displayName` 是个**词条 key**（编辑器候选表那边就是 `Text.translatable(它)` 用的），
     * 所以这里照同一个口径翻译；id 取不到特性时原样显示 id，绝不空着。
     */
    private fun abilityText(id: String?): Text {
        if (id.isNullOrEmpty()) {
            return Text.translatable(
                "cobblemarket.points.store.field.ability",
                Text.translatable("cobblemarket.points.store.value.random")
            )
        }
        val name = com.cobblemon.mod.common.api.abilities.Abilities.get(id)
            ?.let { Text.translatable(it.displayName) }
            ?: Text.literal(id)
        return Text.translatable("cobblemarket.points.store.field.ability", name)
    }

    private fun fieldOf(key: String, value: String?, kind: String, fallback: Text): Text {
        if (value.isNullOrEmpty()) return Text.translatable("cobblemarket.points.store.field.$key", fallback)
        return Text.translatable(
            "cobblemarket.points.store.field.$key", Text.literal(optionLabelOf(kind, value))
        )
    }

    private fun yesNoText(value: Boolean): Text =
        Text.translatable(if (value) "cobblemarket.points.store.value.yes" else "cobblemarket.points.store.value.no")

    /**
     * 六项个体值的一格：`HP: 31`（格式照市场悬停窗：**缩写 + 半角冒号 + 空格 + 值**）。
     * 该项没填（`-1`）= 生成时单独掷，显示 `-`（数据层口径，见 [ShopPokemonSpec.ivs]）。
     * ⚠ 颜色不在这里给 —— 它走 [drawBuyField] 的行色（六项各不相同，见 [STAT_ROW_COLORS]）。
     */
    private fun ivLine(spec: ShopPokemonSpec, index: Int): Text {
        val value = spec.ivs?.getOrNull(index) ?: -1
        return Text.literal("${STAT_ABBR[index]}: ${if (value < 0) "-" else value.toString()}")
    }

    /**
     * 六项努力值的一格：`EV：0`（`null` 与 0 都是"不加"）。
     *
     * ⚠ 必须与上方**个体值**行区分开：那行是 `HP: 31`（项名 + 值），这行原来长得一模一样，
     *   两行叠在一起玩家分不清哪行是努力值（2026-09-25 用户报）。
     *   现在统一写成 `EV：值`，靠**列对齐**与上方的项一一对应。
     * ⚠ 冒号走词条：中文全角「：」、英文半角「: 」（项目文案约定，别在代码里拼 `": "`）。
     */
    private fun evLine(spec: ShopPokemonSpec, index: Int): Text {
        val value = spec.evs?.getOrNull(index) ?: 0
        return Text.translatable("cobblemarket.points.store.value.ev", value.toString())
    }

    /** 技能槽一格：`1 十万伏特`，空槽显示「空」 —— 生成时会从可学技能里随机补（见 PointMarketFactory） */
    private fun skillLine(slots: List<String>, index: Int): Text {
        val id = slots.getOrNull(index).orEmpty()
        return Text.translatable(
            "cobblemarket.points.store.field.skill",
            (index + 1).toString(),
            if (id.isEmpty()) Text.translatable("cobblemarket.points.store.value.empty") else MoveText.name(id)
        )
    }

    // ⚠ 原先这里有个 `itemLine(spec)`（购买窗左上角那行「物品：xxx」）—— 2026-09-26 用户点名删掉：
    //   自从大图左侧上了词条列（第一行就是物品名），那行就纯属重复了。

    /** 物品 id → 本地化名（取物品自己的翻译键）；查不到就原样显示 id，**绝不空着** */
    private fun itemDisplayName(itemId: String): String =
        Identifier.tryParse(itemId)
            ?.let { Registries.ITEM.getOrEmpty(it) }
            ?.map { Text.translatable(it.translationKey).string }
            ?.orElse(itemId)
            ?: itemId

    /** 太晶属性：带**属性色**（与编辑窗的 teraText 同一张色表）；未知 id 原样显示 */
    private fun teraTextOf(teraId: String): Text {
        val name = com.cobblemon.mod.common.api.types.tera.TeraTypes.toList()
            .firstOrNull { it.id.toString() == teraId }?.displayName?.string ?: teraId
        return Text.literal(name).styled { s ->
            s.withColor(com.shusheng.cobblemarket.util.TypeTextColors.color(teraId.substringAfter(':')))
        }
    }

    /** 草稿回到「默认」（新增模式 / 关窗时） */
    private fun resetDraft() {
        editAspects = emptyList()
        editShinyText = ""
        editGender = 0
        editNature = ""
        editAbility = ""
        editBall = DEFAULT_BALL_ID
        editSize = 0
        editAlpha = false
        editTera = ""
        editGmax = false
        editTradeable = true
        editIllegal = false
        editFreePick = false
        editHeldItem = null
        editHeldItemName = ""
        editMinIvs = 0
        editMoves = ""
        // 走统一入口：顺手把右侧大图那枚栈也清掉（见 [setEditItem]）
        setEditItem("", "", null)
        // 搜索框里的词也要清（同 [editSpeciesText] 那条注释：漏清就成了"点第二个格子还留着上一次的内容"）
        editItemSearchText = ""
        editIvText = List(STAT_COUNT) { "" }
        editEvText = List(STAT_COUNT) { "" }
        editFriendshipText = DEFAULT_FRIENDSHIP.toString()
        // 初始训练家（配信）：**默认空**（= 生成的精灵初训家还是买家本人）
        editOriginalTrainerText = ""
        editHeldVisible = true
        editMarks = emptyList()
        // ⚠ 输入框的暂存文本**必须一起重置** —— 它们是 resize 重建时恢复输入用的，
        //   漏清的话"点第二个格子，里面还是上一格填的内容"（2026-09-20 用户实测）
        editSpeciesText = ""
        // 等级 / 亲密度是**预填默认值**（用户 2026-09-20 定）：新建就是 Lv.1 / 亲密度 50，
        // 而不是空着（空 = 生成时随机掷）。清掉照样能回到随机，这里只是给个起点
        editLevelText = DEFAULT_LEVEL.toString()
        // 极巨等级默认 0（= 无）——与等级不同，这不是"给个起点"，0 就是 Cobblemon 的默认值
        editDmaxText = "0"
        editPriceText = ""
        editStockText = ""
        editCooldownText = ""
        editSpecies = null
        editSpeciesCandidates = emptyList()
        editSpeciesIndex = 0
        editPreview = null
    }

    /**
     * 把一份**完整规格**填进草稿 —— 供**快照回填**用（服主选了一个快照，界面自动配好）。
     *
     * ⚠ 一个字段都不能漏 —— 漏了的字段在提交时会被默认值覆盖，
     * 「什么都不改直接确认 = 什么都不变」就破了（全量覆盖式表单的铁律）。
     *
     * ⚠ 它**曾经是「编辑已有格子」的回填**（2026-09-25 之前，`openEditDialog` 把该格已存的
     * 规格填进来）。服主不再能编辑已有格子之后，唯一的用途就是**快照**了。
     * ⚠ **当前还没有调用点**（快照功能未做）—— 别因为"没人调"就把它删了。
     */
    private fun fillDraft(entry: ShopEntry) {
        entry.item?.let { item ->
            setEditItem(
                item.itemId,
                Registries.ITEM.getOrEmpty(Identifier.tryParse(item.itemId))
                    .map { Text.translatable(it.translationKey).string }
                    .orElse(item.itemId),
                item.componentsSpec
            )
            return
        }
        val spec = entry.pokemon ?: return
        // 闪光：固定闪光记在 aspects 里，概率闪光记在 shinyChance 里（数据层两者互斥）
        editAspects = spec.aspects.filter { it != "shiny" }
        editShinyText = when {
            "shiny" in spec.aspects -> "100"
            spec.shinyChance > 0f -> formatChance(spec.shinyChance * 100f)
            else -> ""
        }
        editGender = GENDER_VALUES.indexOf(spec.gender ?: "").coerceAtLeast(0)
        editNature = spec.nature.orEmpty()
        editAbility = spec.ability.orEmpty()
        editBall = spec.ball?.takeIf { it.isNotEmpty() } ?: DEFAULT_BALL_ID
        editSize = sizeIndexOf(spec.scale)
        editAlpha = spec.alpha
        editTera = spec.teraType.orEmpty()
        editGmax = spec.gmaxFactor
        editTradeable = spec.tradeable
        editIllegal = spec.illegal
        editHeldItem = spec.heldItemSpec
        editHeldItemName = spec.heldItemSpec?.let { heldItemLabel(it) }.orEmpty()
        // 两者互斥：`ivs` 有值时保底不参与（数据层口径，见 ShopPokemonSpec 注释）—— 回填按同一口径走，
        // 免得界面停在「两个都有值」那种生成时说不清听谁的状态
        editMinIvs = if (spec.ivs != null) 0 else spec.minPerfectIvs
        editMoves = spec.moves
        // 六项个体值 / 努力值：空 = 未填（个体值那组 -1 就是"该项随机"）
        editIvText = spec.ivs?.map { if (it < 0) "" else it.toString() } ?: List(STAT_COUNT) { "" }
        editEvText = spec.evs?.map { if (it <= 0) "" else it.toString() } ?: List(STAT_COUNT) { "" }
        editFriendshipText = spec.friendship?.toString().orEmpty()
        // 初始训练家（配信）：null 回填成空框（界面「空」与数据「不设」是同一个状态）
        editOriginalTrainerText = spec.originalTrainer.orEmpty()
        // ⚠ 等级**必须在这里显式覆盖** [resetDraft] 预填的那个默认值 —— 输入框那边的 ifEmpty 兜底
        //   只在 `editLevelText` 为空时才生效，预填之后它不再为空，不覆盖的话编辑任何一条已有商品
        //   都会显示成 Lv.1（数据没错、只是显示骗人，但保存下去就成了 1 级）
        editLevelText = spec.level?.toString().orEmpty()
        // ⚠ 同等级：必须**显式覆盖**（输入框的 ifEmpty 兜底只在空时生效，预填后不再为空）
        editDmaxText = spec.dmaxLevel.toString()
        editHeldVisible = spec.heldItemVisible
        editMarks = spec.marks
        // 物种：解析出来才能显示预览与形态/特性候选
        val species = Identifier.tryParse(spec.species)?.let { PokemonSpecies.getByIdentifier(it) }
        editSpecies = species
        editSpeciesCandidates = listOfNotNull(species)
        editSpeciesIndex = 0
        // 回填这一只的规格 ⇒ 顺手把它的完整招式表拉下来（下面的池外反推与技能列表都要用）
        requestSpeciesMoves(species)
        // ⚠ 这几行 normalize **必须落在物种定下来之后**。原先它们写在上头（那时 editSpecies 还是
        //   [resetDraft] 清掉的 null）⇒ 全是空操作，「编辑老数据时收进合法范围」其实从没生效
        //   （2026-09-20 修；注释当时就写着"物种定了之后"，代码没跟上）
        //
        // ⚠⚠ 而**「自由选择」的反推更要排在它们之前**（它是纯编辑态、不在规格里）：
        //   一只带非法特性 / 技能的快照回填进来时，若不先置上它，下面两处 normalize 会把这些值
        //   当成"换物种后的残留"清掉 —— 表现是「快照里的非法特性，读回来就没了」。
        //   判据与那两处 normalize **同一口径**（该物种 / 形态的池子），见 [editFreePick]
        run {
            // ⚠ 池子走**当前形态**（见 [pickedMoveBuckets] / [legalAbilities]）：洛托姆那种"形态专属招"若按
            //   物种级判，回填一只加热形态的洛托姆时「过热」会被当成池外值 ⇒ 误判成"自由选择"，
            //   界面上那把锁一进来就是开的（非法的来源凭空多一条）
            val poolAbilities = legalAbilities().map { it.template.name }
            // ⚠ 招式那半**要等完整招式表**（[moveTableReady]）：只有本地三桶时，教学 / 形态专属招
            //   全被当成"池外值" ⇒ 同样是误判成"自由选择"。⚠ **表到了不补判**（有意的）：
            //   宁可少判一次，也别在服主已经开始配的草稿上把锁开开关关 —— 少了这一判的代价只是
            //   "一把本来该开的锁没开"，服务端生成时照旧按它自己那张全表来。
            val poolMoves = if (moveTableReady(species)) legalMoveIds() else null
            editFreePick = (spec.ability?.isNotEmpty() == true && spec.ability !in poolAbilities) ||
                (poolMoves != null && MoveText.slotIds(spec.moves).any { it.isNotEmpty() && it !in poolMoves })
        }
        normalizeGender()
        normalizeGmax()
        normalizeAspects()
        normalizeAbility()
        normalizeMoves()
        editSpeciesField?.text = species?.resourceIdentifier?.toString() ?: spec.species
        refreshEditPreview()
    }

    /**
     * 提交被**本地**校验拦下时的反馈：音效 + 界面内红字 + 聊天栏红字（双通道，同兑换那边）。
     *
     * ⚠ 光响一声 fail 音效玩家不知道哪儿不对（2026-09-20 用户报「价格没填只有音效、没提示」）。
     * 界面内的那一条画在**标题右侧那片空位**（用户指出「标题和下架按钮之间不是还有很多空隙」），
     * 位置见 [renderEditDialogBackground]。
     */
    private fun failSubmit(key: String) {
        playFailSound()
        val msg = Text.translatable("cobblemarket.points.store.hint.$key")
        editHintMsg = msg
        editHintUntil = System.currentTimeMillis() + EDIT_HINT_MS
        client?.player?.sendMessage(msg.copy().formatted(Formatting.RED), false)
    }

    /** 提交：把草稿拼成 [ShopEntry] 发给服务端（服务端还会全量校验一遍） */
    /**
     * 当前草稿 → [ShopEntry]；返回 null = 还没配全（价格非法 / 物种没选 / 物品没选）。
     *
     * [submitEdit]（上架）与「保存为快照」**共用这一份** —— 两处各拼一份的话，
     * 以后加字段必然漏一处（本项目"同一样东西写两遍"的坑已经踩过很多次）。
     */
    /**
     * 草稿里的**格子冷却小时**：空白 / 0 = 跟随商店（存 0）；非自定义普通店恒 0。
     * 返回 null = 填了非法值（超出 0..[MAX_COOLDOWN_HOURS] / 非数字），由调用方给出具体提示。
     */
    private fun editCooldownHours(): Int? {
        if (!slotCooldownShown()) return 0
        val raw = editCooldownField?.text?.trim().orEmpty()
        if (raw.isEmpty()) return 0
        val value = raw.toIntOrNull() ?: return null
        if (value !in 0..MAX_COOLDOWN_HOURS) return null
        return value
    }

    private fun buildShopEntry(): ShopEntry? {
        // 领取店没有价格框：价格固定 0（服务端也不看）
        val price = if (isClaimShop) {
            editPriceText.trim().toLongOrNull() ?: 0L
        } else {
            editPriceField?.text?.trim()?.toLongOrNull() ?: return null
        }
        if (price < 0) return null
        val stockText = editStockField?.text?.trim().orEmpty()
        // 普通店：留空 = 不限量（-1）；领取店：留空 = 0（不开放，**不能无限**）
        val stock = if (stockText.isEmpty()) {
            if (isClaimShop) 0 else ShopEntry.UNLIMITED
        } else stockText.toIntOrNull() ?: return null
        val cooldown = editCooldownHours() ?: return null
        return if (isPokemonSlot()) {
            val spec = buildPokemonSpec() ?: return null
            ShopEntry(ShopEntry.KIND_POKEMON, price, stock, pokemon = spec, cooldownHours = cooldown)
        } else {
            if (editItemId.isEmpty()) return null
            ShopEntry(
                ShopEntry.KIND_ITEM, price, stock,
                item = ShopItemSpec(editItemId, editItemComponents), cooldownHours = cooldown
            )
        }
    }

    private fun submitEdit() {
        // 价格单独再挡一道：只有它能给出"请填写价格"这条**具体**提示
        //（[buildShopEntry] 只回 null，不区分是哪一项缺）。领取店没有价格，跳过这一关
        if (!isClaimShop) {
            val price = editPriceField?.text?.trim()?.toLongOrNull()
            if (price == null || price < 0) {
                failSubmit("price_required")
                return
            }
        }
        if (slotCooldownShown() && editCooldownHours() == null) {
            failSubmit("cooldown_invalid")
            return
        }
        val entry = buildShopEntry() ?: run {
            // 走到这儿只可能是物种 / 物品没选（价格上面挡过了）
            if (isPokemonSlot()) failSubmit("species_required") else failSubmit("item_required")
            return
        }
        sendToServer(SavePointMarketEntryPayload(editSlot, entry, shopId))
        closeEditDialog()
    }

    /**
     * 草稿 → 精灵规格。
     *
     * ⚠ **基于原规格 `copy()`**：窗口没接的高级项（EV / 亲密度 / 昵称 / 证章 / 逐项个体值…）
     * 原样保留，否则编辑一次就被抹平。
     */
    private fun buildPokemonSpec(): ShopPokemonSpec? {
        val base = editSpecBase?.pokemon
        val speciesId = editSpecies?.resourceIdentifier?.toString() ?: base?.species ?: return null
        // 闪光两态互斥：固定闪光写进 aspects、概率闪光写进 shinyChance（数据层注释有写）。
        // 输入框填的是**百分数**，到 100% 就按必闪处理
        val chance = (editShinyText.trim().toFloatOrNull() ?: 0f).coerceIn(0f, 100f) / 100f
        val always = chance >= 1f
        val aspects = editAspects.filter { it != "shiny" }.toMutableList()
        if (always) aspects.add("shiny")
        val spec = base ?: ShopPokemonSpec(species = speciesId)
        // 六项个体值 / 努力值：空输入按「未填」算（个体值 -1 = 该项随机，努力值 0 = 不加）
        val ivs = editIvText.map { it.trim().toIntOrNull()?.coerceIn(0, 31) ?: -1 }
        val hasIv = ivs.any { it >= 0 }
        val evs = editEvText.map { it.trim().toIntOrNull()?.coerceIn(0, 252) ?: 0 }
        return spec.copy(
            species = speciesId,
            aspects = aspects,
            shinyChance = if (always) 0f else chance,
            level = editLevelField?.text?.trim()?.toIntOrNull(),
            // 极巨等级（0 = 无）。Cobblemon 官方字段，生成精灵时写进去；上限由服务端 coerceIn
            dmaxLevel = editDmaxField?.text?.trim()?.toIntOrNull() ?: 0,
            gender = GENDER_VALUES.getOrNull(editGender)?.takeIf { it.isNotEmpty() },
            nature = editNature.ifEmpty { null },
            ability = editAbility.ifEmpty { null },
            ball = editBall.ifEmpty { DEFAULT_BALL_ID },
            scale = sizeScale(),
            alpha = editAlpha,
            teraType = editTera.ifEmpty { null },
            gmaxFactor = editGmax,
            tradeable = editTradeable,
            illegal = editIllegal,
            heldItemSpec = editHeldItem,
            // 六项个体值：**整组全空**才回退到「保底 V 数」（数据层对这两种玩法是互斥的，见注释）
            ivs = if (hasIv) ivs else null,
            minPerfectIvs = if (hasIv) 0 else editMinIvs,
            evs = if (evs.any { it > 0 }) evs else null,
            friendship = editFriendshipText.trim().toIntOrNull()?.coerceIn(0, 255),
            // 初始训练家（配信）：trim 后为空 = 不设（生成时初训家还是买家本人）。
            // ⚠ 长度 / `§` 由输入框 predicate 当场拒收，服务端 [PointMarketService.normalizePokemon] 再卡一道
            originalTrainer = editOriginalTrainerText.trim().ifEmpty { null },
            heldItemVisible = editHeldVisible,
            // ⚠ 带上**头目自动那个**：服务端生成时 Cobblemon 自己也会加一遍（Set 去重、无害），
            //   但数据层要存它 —— 存档读回来、购买窗显示的数才对得上
            marks = effectiveMarks(),
            moves = editMoves
        )
    }

    /** 体型档位 → scale 倍率（取该档**中点**；分档边界是游戏配置算的，不能写死，见 [PokemonSizeCategory]） */
    private fun sizeScale(): Float? {
        val category = PokemonSizeCategory.entries.getOrNull(editSize - 1) ?: return null
        val min = ServerSettings.pokemonIntrinsicSizeMin
        val max = ServerSettings.pokemonIntrinsicSizeMax
        val segment = (max - min) / PokemonSizeCategory.entries.size
        return min + segment * (category.ordinal + 0.5f)
    }

    /** scale 倍率 → 体型档位下标（0 = 随机） */
    private fun sizeIndexOf(scale: Float?): Int {
        val s = scale ?: return 0
        val category = PokemonSizeCategory.fromScale(s)
        return category.ordinal + 1
    }

    /** 携带物 SNBT → 显示名（解析不出来就退回原文，绝不崩） */
    private fun heldItemLabel(snbt: String): String = runCatching {
        val nbt = net.minecraft.nbt.StringNbtReader.parse(snbt)
        val id = nbt.getString("id")
        val item = net.minecraft.registry.Registries.ITEM.getOrEmpty(Identifier.tryParse(id)).orElse(null)
        item?.let { Text.translatable(it.translationKey).string } ?: id
    }.getOrDefault("")

    // ── 窗口：建控件 ──

    /**
     * 建窗口控件（开窗与 resize 后重建都会调；初值一律从草稿来）。
     *
     * ⚠ **背景必须第一个加**：MC 按添加顺序渲染 children，先加背景、后加控件，
     * 控件才画在背景之上（黑名单弹窗同款做法）。背景作为 Drawable 加进 children，
     * 而不是在 `render` 里画 —— 在 render 里画会盖住 super.render 画出来的控件。
     */
    private fun buildEditDialog() {
        // ⚠ 先把**上一次弹窗残留的控件**清掉：`editButtons` 这类 map 只存最后一次建的引用，
        //   重复建出来的旧对象还挂在 children 上，靠"逐个列名字隐藏"永远管不到它们
        //   （2026-09-20 用户实测：列表展开时左上角冒出 4 个叠在一起的按钮）
        if (editWidgetBase in 0 until children().size) {
            children().drop(editWidgetBase).toList().forEach { remove(it) }
        }
        editWidgetBase = children().size

        val x = editDialogX()
        val y = editDialogY()

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderEditDialogBackground(context, mouseX, mouseY, delta)
            }
        })

        if (isPokemonSlot()) buildPokemonFields(x, y) else buildItemFields(y)

        // 价格 / 库存（两种窗口都有；**钉在窗口底部**，不随内容滚动）
        // ⚠ 领取店**不需要价格**：不建价格框，把「配额」框拉宽占满整组（用户 2026-10-23 报）
        val priceY = editPriceRowY()
        if (!isClaimShop) {
            editPriceField = TextFieldWidget(
                textRenderer, editPriceGroupX() + EDIT_LABEL_W, priceY,
                editPriceColW() - EDIT_LABEL_W, EDIT_ROW_H, Text.literal("")
            )
            editPriceField?.setTextPredicate { s -> s.length <= 12 && s.all { it.isDigit() } }
            editPriceField?.setChangedListener { editPriceText = it }
            editPriceField?.text = editPriceText.ifEmpty {
                editSpecBase?.price?.takeIf { it > 0L }?.toString().orEmpty()
            }
            addSelectableChild(editPriceField)
            addDrawableChild(editPriceField)
        } else {
            editPriceField = null
        }

        val stockX = if (isClaimShop) editPriceGroupX() + EDIT_LABEL_W
        else editPriceGroupX() + editPriceColW() + EDIT_COL_GAP + EDIT_LABEL_W
        val stockW = if (isClaimShop) editPriceColW() * 2 + EDIT_COL_GAP - EDIT_LABEL_W
        else editPriceColW() - EDIT_LABEL_W
        editStockField = TextFieldWidget(
            textRenderer, stockX, priceY, stockW, EDIT_ROW_H, Text.literal("")
        )
        editStockField?.setTextPredicate { s -> s.length <= 9 && s.all { it.isDigit() } }
        // 领取店：这个框是「每期每人可领数量」，留空 = 0（不开放），不是不限量
        // ⚠ 占位符必须按框宽实测挑长短版（原版不裁剪，长了直接溢出框外）
        editStockField?.let { f ->
            f.setPlaceholder(
                if (isClaimShop) TextUtil.fieldHint(
                    f, "cobblemarket.points.store.claim_quota_hint",
                    "cobblemarket.points.store.claim_quota_hint_short"
                ) else Text.translatable("cobblemarket.points.store.value.unlimited")
            )
        }
        editStockField?.setChangedListener { editStockText = it }
        // 回填：不限量 → 留空（「不改 = 什么都不变」）
        editStockField?.text = editStockText.ifEmpty {
            editSpecBase?.stock?.takeIf { it != ShopEntry.UNLIMITED }?.toString().orEmpty()
        }
        addSelectableChild(editStockField)
        addDrawableChild(editStockField)

        // 格子冷却（只有自定义普通购买店）：放在底部「价格 / 库存」这一行的**第三列**。
        // ⚠ 不新增行、不增高弹窗、不压缩前两格 —— 用的是这一行本来就空着的横向余量。
        // 留空 / 0 = 跟随商店（取整店冷却与商品各自冷却的较长者）；占位符直接显示这个默认值。
        // ⚠ 占位符是**纯数字**（如 `1`），不能带 `h` —— 输入框只收数字，带单位会误导玩家以为要手打单位
        //   （2026-10-27 用户报：原 `0h` 看着像要求输入单位）。0 也要显示 `0`，不要写 `—`。
        if (slotCooldownShown()) {
            editCooldownField = TextFieldWidget(
                textRenderer, slotCooldownFieldX(), priceY,
                slotCooldownFieldW(), EDIT_ROW_H, Text.literal("")
            )
            editCooldownField?.setTextPredicate { s -> s.length <= 5 && s.all { it.isDigit() } }
            editCooldownField?.setChangedListener { editCooldownText = it }
            editCooldownField?.text = editCooldownText
            editCooldownField?.setPlaceholder(
                Text.literal(maxOf(shopCooldownHours, itemCooldownHours).toString())
            )
            addSelectableChild(editCooldownField)
            addDrawableChild(editCooldownField)
        } else {
            editCooldownField = null
        }

        // 底部按钮行：保存 / 添加 + 取消（居中并排，间距 8）。
        // ⚠ 按 [editEditingSlot] 判，**别按 editSpecBase** —— 快照回填也会把它填成非 null，
        //   那时仍是往空格子里「添加」，按钮不该变成「保存」（2026-09-25 用户报过同类误判）
        val btnY = editButtonRowY()
        val confirmText = if (editEditingSlot) "cobblemarket.points.store.save" else "cobblemarket.points.store.add"
        val contentW = editDialogW() - EDIT_PAD * 2
        // 底部按钮行**整组居中**：精灵窗那组左边多两位成员 —— 「形态说明」info（16px）与「非法」开关。
        //
        // ⚠⚠ **info 那一格恒占位**（它只在选中战斗形态时才画出来）⇒ 它出现 / 消失
        //   不会让「非法 / 添加 / 取消」横移。用户 2026-09-30 拍板：加「非法」时一并按
        //   "整组固定"排，谁都不许动（按钮跳位是这个界面的老毛病）。
        // ⚠ 组宽**两条路各算一份**（物品窗那组只有两枚、窄 74px）：成员数不同，
        //   起始位置不能共用一份，否则物品窗那两枚会整体偏左。
        val groupStart = if (isPokemonSlot()) {
            val groupW = EDIT_GATED_INFO_W + EDIT_INFO_GAP + EDIT_ILLEGAL_W + 8 + EDIT_BUTTON_W * 2 + 8
            x + EDIT_PAD + (contentW - groupW) / 2
        } else {
            // 物品窗：维持原样（「添加 + 取消」两枚居中）
            x + EDIT_PAD + (contentW - EDIT_BUTTON_W * 2 - 8) / 2
        }
        // 抽成局部量：紧挨它左边要放 info 与「非法」（见下），各算一份必然错开
        // ⚠ 前面那两枚的宽度都要算进来 —— 只算 info 的话「非法」会压在「添加」上
        val confirmX = groupStart + if (isPokemonSlot())
            EDIT_GATED_INFO_W + EDIT_INFO_GAP + EDIT_ILLEGAL_W + 8 else 0
        editConfirmButton = NineSliceButton(
            confirmX, btnY, EDIT_BUTTON_W, EDIT_BUTTON_H, Text.translatable(confirmText), { submitEdit() }
        )
        addDrawableChild(editConfirmButton)
        // ⚠ 取消按钮**必须存字段**：展开选项列表时要把它一起藏掉，否则它压在列表上、
        //   一点就把整个窗口关了（2026-09-20 用户实测：选形态时点「取消」直接退出了编辑窗）
        editCancelButton = NineSliceButton(
            // ⚠ 由 [confirmX] 推出来，**不另起一个公式** —— 两个公式等价（原式是
            //   `x + EDIT_PAD + (contentW + 8) / 2`），但改组宽时改了一处漏另一处，
            //   取消就会压在「添加」上
            confirmX + EDIT_BUTTON_W + 8,
            btnY, EDIT_BUTTON_W, EDIT_BUTTON_H,
            Text.translatable("cobblemarket.buy_confirm.cancel"), { closeEditDialog() }
        )
        addDrawableChild(editCancelButton)

        // 按钮组最左那两枚（**只有精灵窗有**）：形态警告 + 「非法」开关。
        //
        // 「形态需要前置条件」警告：**紧挨「非法」的左边**，离「添加」也只隔一枚
        // —— 服主配完必然要点「添加」，挤在这一带才看得见（原先试过右上角，用户报"突然出现在
        // 偏僻角落、根本注意不到"）。
        // ⚠ 画的是**符号 `⚠` 本身**，不是图标贴图（用户 2026-09-30 点名要它）：
        //   与关市横幅「⚠ 市场功能已关闭」开头**同一个字符**（原先是一枚蓝色 info 图标，
        //   语义偏"信息"，而这个该是"警告"）。
        // ⭐ **它是常驻的**（用户 2026-09-30 定）：判据是**硬编码清单**，管不住自制 / 魔改加的
        //   战斗形态 ⇒ 靠"命中才出现"的话那些形态永远不会有提示，入口得一直在。
        //   ⚠ 但**颜色分两态**：草稿里真选中那类形态才染红（[GATED_WARN_COLOR]），否则常规白 ——
        //   一个红 ⚠ 常驻着会被读成"当前配置有问题"。刷色在 [render] 里每帧做。
        // ⚠ 与「添加」**垂直居中对齐**（按钮 18 高、这枚 16 高，上让 1px）
        if (isPokemonSlot()) {
            editGatedInfoButton = NineSliceButton(
                groupStart,
                btnY + (EDIT_BUTTON_H - EDIT_GATED_INFO_W) / 2,
                EDIT_GATED_INFO_W, EDIT_GATED_INFO_W,
                // ⚠ 符号走**词条**（同 `species_clear` 那个「×」的做法），别硬编码在代码里
                Text.translatable("cobblemarket.gated_form.button_icon"),
                { openGatedFormScreen() },
                // 初始白；渲染第一帧起由 [render] 按草稿状态刷（建控件时草稿还没回填完）
                textColor = 0xFFFFFF,
                tooltip = Text.translatable("cobblemarket.gated_form.button_tip")
            ).also { addDrawableChild(it) }

            // 「非法」开关：**常驻**（用户 2026-09-30 拍板 —— 不做成"命中战斗形态才出现"：
            // 那枚警告只管「战斗形态」一维，判据漏了标记就漏，常驻才轮得到服主自己判断）。
            // 勾上的意思 = **从这一格买到的精灵会带上 `cobblemarket:illegal`**，
            // 将来对战禁赛按它判（与自动打的 `shop` 来源标记分工不同，见 PokemonTags）。
            // 图标按钮：`illegal.png`（48×48 的印章图，**图里自带 ILLEGAL 字样** ⇒ 不再配文字 ——
            // 再挂一行「非法 / Illegal」就是同一件事说两遍）。
            // ⚠ **勾没勾由按钮底图表达**（`selectedVisual`，同 tab 按钮那套）—— 用户 2026-09-30 定：
            //   图标只有这一张，不另做"选中版图标"。
            // ⚠ 图标缩到 16px 后图上的字是**看不清**的（本来就是印章图形），它是**符号**不是文字 ——
            //   自明性靠悬停说明（[illegal_tip] 那条），别指望玩家读出图里的字
            editIllegalButton = NineSliceButton(
                groupStart + EDIT_GATED_INFO_W + EDIT_INFO_GAP,
                btnY, EDIT_ILLEGAL_W, EDIT_ILLEGAL_W,
                Text.literal(""),
                { toggleIllegal() },
                iconLeft = ILLEGAL_BADGE_ICON,
                iconTexW = 48, iconTexH = 48, iconScale = 0.3333f,
                tooltip = Text.translatable("cobblemarket.points.store.illegal_tip")
            ).also { addDrawableChild(it) }
        }

        // **快照**：右上角那枚（`editRemoveY()` 这名字是"下架"还在右上角时留下的，
        // 它算的是"距顶 = 距右"那条对角线上，与具体是哪枚按钮无关）。
        // 2026-10-26 起「下架」也回到了编辑窗（编辑已有格子时才建，见本节末尾）——
        // 精灵窗一整排是「下架 | 自由选择 | 快照」，物品窗没有后两枚，下架就贴右缘。
        // ⚠ **快照只有精灵窗有**：快照存的是**精灵配方**（物种 / 形态 / 个体值 / 技能那些），
        //   物品窗没有可存的东西 —— 原先无条件建，物品窗上就挂着一枚点了没用的按钮
        //（2026-09-25 用户指出）
        if (isPokemonSlot()) {
            editSnapshotButton = NineSliceButton(
                editRowRightX() - EDIT_SNAPSHOT_W, editRemoveY(),
                EDIT_SNAPSHOT_W, EDIT_ROW_H,
                Text.translatable("cobblemarket.points.store.snapshot_button"), { openSnapshotDialog() }
            ).also { addDrawableChild(it) }

            // 「解锁特性 / 技能池」那枚开关：**紧挨「快照」左侧**（用户 2026-09-30 定的位置 ——
            // 最初那枚 info 图标就放在这儿，后来搬去「添加」旁边，这个位置就一直空着）。
            // ⚠ 与快照**同高、同顶边**（都贴 [editRemoveY] 那条"距顶 = 距右"的线）。
            // ⚠⭐ 状态由**图标本身**表达（用户 2026-09-30 定）：没选中 = 闭锁 [FREE_PICK_LOCK_ICON]、
            //   选中 = 开锁 [FREE_PICK_UNLOCK_ICON] ⇒ **不用 `selectedVisual`**
            //   （那是给"图标不变、只能靠底色表达"的按钮用的；这里再叠一层底色是同一件事说两遍）。
            //   ⚠ 也**不用文字**：用户否掉了原先的「自由」两个字（原话「不准确」）。
            // ⚠ 联动见 [toggleFreePick]：选中它 ⇒ 底部的「非法」自动跟着选中
            editFreePickButton = NineSliceButton(
                editRowRightX() - EDIT_SNAPSHOT_W - BUTTON_GAP - EDIT_FREE_SIZE, editRemoveY(),
                EDIT_FREE_SIZE, EDIT_FREE_SIZE,
                Text.literal(""),
                { toggleFreePick() },
                iconLeft = if (editFreePick) FREE_PICK_UNLOCK_ICON else FREE_PICK_LOCK_ICON,
                iconTexW = 48, iconTexH = 48, iconScale = 0.3333f,
                tooltip = Text.translatable("cobblemarket.points.store.free_pick_tip")
            ).also { addDrawableChild(it) }
        }

        // 「下架」（**只在编辑已有格子时建**）：精灵窗排在「下架 | 自由选择 | 快照」这一排的最左；
        // 物品窗没有后两枚 ⇒ 按原来的位置贴右缘。
        // ⚠ 右缘用 [editRowRightX]（最右列字段的右缘），**别用「窗口右缘 − 内边距」** ——
        //   列宽是整数除法，两者能差几像素，看着就是"没对齐"（2026-09-20 用户指出）。
        if (editEditingSlot) {
            val removeX = if (isPokemonSlot())
                editRowRightX() - EDIT_SNAPSHOT_W - BUTTON_GAP - EDIT_FREE_SIZE - BUTTON_GAP - EDIT_REMOVE_W
            else
                editRowRightX() - EDIT_REMOVE_W
            editRemoveButton = NineSliceButton(
                removeX, editRemoveY(), EDIT_REMOVE_W, EDIT_ROW_H,
                Text.translatable("cobblemarket.points.store.remove"), { removeFromEditDialog() }
            ).also { addDrawableChild(it) }
        }

        refreshEditLabels()
        applyEditScroll()
    }

    /**
     * 滚动区里所有输入框的「控件 + 行号 + 列内横向偏移」——
     * 滚动时要按它统一重算坐标与可见性（散在各处各写一遍必然漏掉几个）。
     */
    private fun scrollableFields(): List<Pair<TextFieldWidget?, String>> = buildList {
        add(editShinyField to KEY_SHINY_CHANCE)
        add(editLevelField to KEY_LEVEL)
        add(editDmaxField to KEY_DMAX)
        add(editFriendshipField to KEY_FRIENDSHIP)
        add(editOriginalTrainerField to KEY_OT)
        editIvFields.forEachIndexed { i, f -> add(f to "$KEY_IV$i") }
        editEvFields.forEachIndexed { i, f -> add(f to "$KEY_EV$i") }
    }

    /**
     * 应用滚动：把中间那坨字段控件整体上下移，并按「整行是否露在滚动区里」显隐。
     *
     * ⚠ 原版控件没有裁剪，半截的行只能**整体藏起来**（列表滚动也是这么做的）；
     * 价格/库存与按钮在滚动区之外，不参与。
     */
    private fun applyEditScroll() {
        editContentScroll = editContentScroll.coerceIn(0, editMaxScroll())
        editButtons.forEach { (key, btn) ->
            // ⚠ 技能槽**不走列网格**（4 个平分整行）—— 位置必须与 [editFieldButton] 同源，
            //   各算一份的话一滚动技能按钮就跳回中间那 4 格（本项目"建与重摆两处"的老坑）
            btn.x = fieldButtonX(key)
            btn.y = editRowY(editRowOf(key))
        }
        // 滚动区里的所有输入框跟着行一起移动（闪光 / 等级 / 亲密度 / 六项个体 / 六项努力）
        scrollableFields().forEach { (field, key) ->
            field ?: return@forEach
            val row = editRowOf(key)
            // ⚠ 列内缩进必须带上（等级那格的「Lv.」前缀）—— 这行原来直接写 editColX，一滚动就把前缀压掉了
            field.x = editColX(editColOf(key)) + editFieldInsetX(key)
            field.y = editRowY(row)
            // 滚出视野的输入框要失焦，否则按键还在往一个看不见的框里打
            if (!editRowVisible(row) && focused === field) focused = null
        }
        updateEditControlsVisibility()
    }

    /** 精灵窗口的字段控件（物种搜索 + 两列网格） */
    private fun buildPokemonFields(x: Int, y: Int) {
        val field = TextFieldWidget(
            textRenderer, x + EDIT_PAD, y + EDIT_SPECIES_Y, editSpeciesFieldW(), EDIT_ROW_H, Text.literal("")
        )
        field.setPlaceholder(Text.translatable("cobblemarket.points.store.species_hint"))
        // ⚠ 输入变化要重解析候选（精确优先，见 SpeciesText.candidatesByNameOrId）——
        //   直接取第一个会把中文名互为子串的物种静默换掉（「鬼斯」/「鬼斯通」）
        field.setChangedListener { text ->
            if (editSpeciesSyncing) return@setChangedListener
            editSpeciesText = text; updateEditSpecies(text)
        }
        // ⚠ 这条赋值**会触发上面那个 listener** ⇒ 必须挡一道（见 [editSpeciesSyncing]）：
        //   不挡的话每次重建都把候选下标归零、物种退回第一个
        editSpeciesSyncing = true
        field.text = editSpeciesText.ifEmpty {
            editSpecies?.resourceIdentifier?.toString() ?: editSpecBase?.pokemon?.species.orEmpty()
        }
        editSpeciesSyncing = false
        editSpeciesField = field
        addSelectableChild(field)
        addDrawableChild(field)

        // 清空搜索框（用户 2026-09-20）：只把输入框里的字清掉，不碰别的
        editSpeciesClear = NineSliceButton(
            editClearX(), y + EDIT_SPECIES_Y, SPECIES_CLEAR_W, EDIT_ROW_H,
            Text.translatable("cobblemarket.points.store.species_clear"), { clearEditSpecies() },
            tooltip = Text.translatable("cobblemarket.gui.species_clear")
        )
        addDrawableChild(editSpeciesClear)

        editSpeciesPrev = NineSliceButton(
            editArrowX(), y + EDIT_SPECIES_Y, EDIT_ARROW_W, EDIT_ROW_H, Text.literal(""),
            { cycleEditSpecies(-1) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/previous.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.species_prev")
        )
        editSpeciesNext = NineSliceButton(
            editArrow2X(), y + EDIT_SPECIES_Y, EDIT_ARROW_W, EDIT_ROW_H, Text.literal(""),
            { cycleEditSpecies(1) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/button/next.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.species_next")
        )
        addDrawableChild(editSpeciesPrev)
        addDrawableChild(editSpeciesNext)

        // ── 字段网格：**按顺序表流式排布**（列数随窗口宽度变，位置一律现算，见 editFieldOrder）──
        editIvFields.clear()
        editEvFields.clear()
        // ⚠ **建控件的遍历源 ≠ 布局表**：`editFieldOrder` 只描述左 3 列那 18 个字段的位置，
        //   个体值 / 努力值 / 技能槽有各自的固定区（见 posOf），必须一起遍历到，
        //   否则它们的控件压根不会被建出来（2026-09-20 用户实测：12 个框和技能全没了）
        val allKeys = buildList {
            addAll(editFieldOrder())
            repeat(STAT_COUNT) { add("$KEY_IV$it") }
            repeat(STAT_COUNT) { add("$KEY_EV$it") }
            repeat(MoveText.SLOTS) { add("$KEY_SKILL$it") }
        }
        for (key in allKeys) {
            when {
                key == KEY_SHINY_CHANCE -> {
                    editShinyField = newEditField(key)
                    editShinyField?.setTextPredicate { s -> s.length <= 6 && s.all { it.isDigit() || it == '.' } }
                    editShinyField?.setPlaceholder(Text.literal("%"))
                    editShinyField?.setChangedListener { text ->
                        editShinyText = text
                        refreshEditPreview()
                        refreshEditLabels()
                    }
                    editShinyField?.text = editShinyText
                }

                key == KEY_LEVEL -> {
                    editLevelField = newEditField(key)
                    editLevelField?.setTextPredicate(intInputIn(1..100))
                    // ⚠ 占位符**不能带冒号**（表单 label 才带），所以与 field.* 分开写（项目文案约定）
                    editLevelField?.setPlaceholder(Text.translatable("cobblemarket.points.store.field.level_hint"))
                    editLevelField?.setChangedListener { editLevelText = it }
                    editLevelField?.text = editLevelText.ifEmpty {
                        editSpecBase?.pokemon?.level?.toString().orEmpty()
                    }
                }

                // 极巨等级：数字输入框（**不受物种约束** —— 所有精灵都能极巨化，与超极巨化那格不同）
                key == KEY_DMAX -> {
                    editDmaxField = newEditField(key)
                    // 上限用 Cobblemon 的**硬约束** 0..10（`@IntConstraint`），不读 `Cobblemon.config` ——
                    // 那是**服务端**配置，客户端读到的未必是服务端的值；超上限时服务端自己会 coerceIn
                    editDmaxField?.setTextPredicate(intInputIn(0..10))
                    // 占位符不带冒号（表单 label 才带），与等级同款
                    editDmaxField?.setPlaceholder(Text.translatable("cobblemarket.points.store.field.dmax_hint"))
                    editDmaxField?.setChangedListener { editDmaxText = it }
                    editDmaxField?.text = editDmaxText.ifEmpty {
                        editSpecBase?.pokemon?.dmaxLevel?.toString().orEmpty()
                    }
                }

                key == KEY_FRIENDSHIP -> {
                    editFriendshipField = newEditField(key)
                    editFriendshipField?.setTextPredicate(intInputIn(0..255))
                    editFriendshipField?.setPlaceholder(
                        Text.translatable("cobblemarket.points.store.field.friendship_hint")
                    )
                    editFriendshipField?.setChangedListener { editFriendshipText = it }
                    editFriendshipField?.text = editFriendshipText
                }

                // 初始训练家（配信）：**文本**输入框，空 = 不设（生成的精灵初训家还是买家本人）
                key == KEY_OT -> {
                    editOriginalTrainerField = newEditField(key)
                    // ⚠ 长度上限与服务端**共用同一个数**（[PointMarketService.MAX_OT_LENGTH]）；
                    //   `§` 禁掉 —— 颜色注入符会让所有显示这个名字的地方变色 / 乱码
                    editOriginalTrainerField?.setTextPredicate { s ->
                        s.length <= PointMarketService.MAX_OT_LENGTH && '§' !in s
                    }
                    // ⚠ 占位符**不带冒号**（表单 label 才带）；按量出来的宽度选长短两版 ——
                    //   原版输入框画占位符**不裁剪**，长了会溢出到隔壁格（照 EV hint 的先例）
                    // ⚠ 文案是**示例名**（「如小智」/ "e.g. サトシ"），**不是字段名**（用户 2026-10-02 定）：
                    //   写「初始训练家」会被误读成"不填就叫这个"；示例名还能让中英玩家都看出
                    //   这里能填任意文字（中文 / 假名都行），不是只能填字母
                    val otHintFull = Text.translatable("cobblemarket.points.store.field.ot_hint")
                    editOriginalTrainerField?.setPlaceholder(
                        if (textRenderer.getWidth(otHintFull) <= editFieldW(key)) otHintFull
                        else Text.translatable("cobblemarket.points.store.field.ot_hint_short")
                    )
                    editOriginalTrainerField?.setChangedListener { editOriginalTrainerText = it }
                    editOriginalTrainerField?.text = editOriginalTrainerText
                }

                // 六项个体值 / 努力值：顺序表里各连着 6 个，按序进各自的清单
                key.startsWith(KEY_IV) -> {
                    val i = key.removePrefix(KEY_IV).toInt()
                    val f = newEditField(key)
                    f.setTextPredicate(intInputIn(0..31))
                    // 占位符带上上限（用户 2026-09-20）：输入被范围拒收时，玩家能一眼看出该填多少
                    f.setPlaceholder(Text.translatable("cobblemarket.points.store.iv_hint", STAT_LABELS[i]))
                    f.setChangedListener { t ->
                        editIvText = editIvText.toMutableList().also { it[i] = t }
                        // 保底按钮的置灰**跟着敲键即时走**（互斥，见 applyIvInputLock）——
                        // 只更这一个控件的状态，不用重建整屏文案
                        editButtons[KEY_MIN_IVS]?.dimmed = hasIvInput()
                    }
                    f.text = editIvText.getOrElse(i) { "" }
                    editIvFields.add(f)
                }

                key.startsWith(KEY_EV) -> {
                    val i = key.removePrefix(KEY_EV).toInt()
                    val f = newEditField(key)
                    // ⚠ 除单项 252 外还有**总和上限**（[EV_TOTAL_MAX] = 有效的 508，不是硬上限 510）：
                    //   六项加起来超了的话，游戏设值时会把第三项起**静默拒掉**（[EVs.canSet]），
                    //   出来的是 [252,252,0,0,0,0] 跟填的完全不是一回事；NBT 反序列化那边更是直接报错
                    //   （2026-09-20 用户问「六个框都填 252 怎么办」）。所以这里跨框一起算着拦
                    f.setTextPredicate(intInputIn(0..252) { v -> evSumExcept(i) + v <= EV_TOTAL_MAX })
                    // 加前缀区分两组 —— 项名缩写两组是一样的，不看前缀分不出哪组是哪组。
                    // 上限 252 也写进占位符里（同个体值那组，用户 2026-09-20）。
                    // ⚠ 带前缀那版**放不下就不用**：列宽跟着窗口走，GUI scale 大时一列只剩 39px，
                    //   `EV SpA≤252` 会顶出输入框（用户 2026-09-25 报过这个症状）。
                    //   按**量出来的宽度**选版本，别靠估（估错过一次了）
                    val evHintFull = Text.translatable("cobblemarket.points.store.ev_hint", STAT_LABELS[i])
                    f.setPlaceholder(
                        if (textRenderer.getWidth(evHintFull) <= editFieldW(key)) evHintFull
                        else Text.translatable("cobblemarket.points.store.ev_hint_short", STAT_LABELS[i])
                    )
                    f.setChangedListener { t -> editEvText = editEvText.toMutableList().also { it[i] = t } }
                    f.text = editEvText.getOrElse(i) { "" }
                    editEvFields.add(f)
                }

                // 携带物 / 球种：按钮上直接放**物品图标**（去文字化），图标在 refreshEditLabels 里刷新
                key == KEY_HELD || key == KEY_BALL -> {
                    val btn = NineSliceButton(
                        editColX(editColOf(key)), editRowY(editRowOf(key)),
                        editColW(), EDIT_ROW_H, Text.literal(""),
                        { onFieldClick(key) },
                        iconAfterText = true,
                        // 不加悬停提示：按钮上已有「标签：」+ 图标，多一层提示是噪音（2026-09-20 用户指出）
                    )
                    editButtons[key] = btn
                    addDrawableChild(btn)
                }

                // 头目也单独建：要用 alpha 徽章（与列表行里那张同一张图）
                key == KEY_ALPHA -> {
                    val btn = NineSliceButton(
                        editColX(editColOf(key)), editRowY(editRowOf(key)),
                        editColW(), EDIT_ROW_H, Text.literal(""),
                        { onFieldClick(key) },
                        iconTexW = ALPHA_BADGE_TEX_W, iconTexH = ALPHA_BADGE_TEX_H,
                        iconScale = ALPHA_BADGE_SCALE
                        // 徽章在**前**当标签（`iconAfterText` 保持默认 false）、值写「：是 / ：否」——
                        // 2026-09-25 用户改的口径，理由见 refreshAlphaButton
                    )
                    editButtons[key] = btn
                    addDrawableChild(btn)
                }

                // 性别按钮单独建：要 6×8 的 ♂/♀ 图标（尺寸与其余字段按钮不同，构造期就得定）
                key == KEY_GENDER -> {
                    val btn = NineSliceButton(
                        editColX(editColOf(key)), editRowY(editRowOf(key)),
                        editColW(), EDIT_ROW_H, Text.literal(""),
                        { onFieldClick(key) },
                        iconTexW = 6, iconTexH = 8, iconScale = 1.5f
                    )
                    editButtons[key] = btn
                    addDrawableChild(btn)
                }

                else -> editFieldButton(key) { onFieldClick(key) }
            }
        }
    }

    /**
     * 记下物品窗选中的物品 —— **唯一入口**（列表选中、小手取主手、草稿重置都走它）。
     *
     * 顺手把 [editItemPreviewStack] 重建好：大图渲染是每帧跑的，不能让它自己去反序列化。
     */
    private fun setEditItem(id: String, name: String, components: NbtCompound?) {
        editItemId = id
        editItemName = name
        editItemComponents = components
        editItemPreviewStack =
            if (id.isEmpty()) null else rebuildItemStack(ShopItemSpec(id, components))
    }

    /** 物品搜索框的左缘：让开窗口内边距 + 小手按钮（用户 2026-09-26 要「小手贴搜索框左边」） */
    private fun editItemSearchX(): Int = editDialogX() + EDIT_PAD + OPTION_HELD_BTN_W + 4

    /**
     * 物品搜索框的**右缘**与精灵窗那条物种搜索框对齐（[editSpeciesFieldRightX]）——
     * 只因为左边让出了小手才窄 20px，"两种窗口第一眼是同一个界面"的观感不变。
     */
    private fun editItemSearchW(): Int = editSpeciesFieldRightX() - editItemSearchX()

    /**
     * 物品窗口的字段 = **一个物品搜索框**（用户 2026-09-26 定）。
     *
     * 原先是一格「物品：无」按钮，点它才弹搜索列表 —— 用户指出多此一举：
     * 「直接在这个界面添加物品不就行了」⇒ 搜索框**直接长在界面上**，输入即出候选
     * （照添加精灵窗的物种搜索框那个形态）。小手按钮留在**搜索框左侧**。
     *
     * ⚠ 物品窗不走字段顺序表（那张表是精灵的），所以坐标自己算 —— 垂直位置用 [EDIT_SPECIES_Y]，
     *   与精灵窗搜索框**同一条水平线**。
     */
    private fun buildItemFields(y: Int) {
        val searchY = y + EDIT_SPECIES_Y
        val field = TextFieldWidget(
            textRenderer, editItemSearchX(), searchY, editItemSearchW(), EDIT_ROW_H, Text.literal("")
        )
        // 复用全项目统一的搜索框占位词条（黑名单、市场那些列表都是它）
        field.setPlaceholder(Text.translatable("cobblemarket.gui.search"))
        field.setMaxLength(48)
        field.setChangedListener { text ->
            if (editItemSearchSyncing) return@setChangedListener
            editItemSearchText = text
            updateItemSearch(text)
        }
        // ⚠ 初值赋值也会触发监听器 —— 不挡的话**开窗/重建就会把列表弹出来**（那时草稿里已有上次选的物品）
        editItemSearchSyncing = true
        field.text = editItemSearchText.ifEmpty { editItemName }
        editItemSearchSyncing = false
        editItemSearchField = field
        addSelectableChild(field)
        addDrawableChild(field)

        // 小手：点一下取主手那件当商品（组件一起提取）—— 与列表里选中一条候选**同一套提取**，
        // 见 [pickHeldItemAsShopEntry]。图标 / 尺寸与黑名单搜索框那枚一模一样
        val held = NineSliceButton(
            editDialogX() + EDIT_PAD, searchY, OPTION_HELD_BTN_W, EDIT_ROW_H,
            Text.literal(""),
            { pickHeldItemAsShopEntry() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/cursor/hand.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.points.store.pick_held")
        )
        editItemHeldButton = held
        addDrawableChild(held)
    }

    /**
     * 物品搜索框变化 → 候选列表的**开合与重建**（唯一入口）。
     *
     * 与精灵窗「输入即重解析候选」同一个思路，区别是物品没有"精确优先"那套 ——
     * 候选本身就是 [buildItemCandidates] 按搜索词现算的（与携带物、黑名单同一个搜索入口）。
     *
     * ⚠ **清空 = 收起列表**（不是"列出全部物品"）：一屏几百件物品没有意义，与携带物一致。
     */
    private fun updateItemSearch(text: String) {
        val q = text.trim()
        if (q.isEmpty()) {
            if (optionListKind == OPT_ITEM) closeOptionList()
            return
        }
        val wasOpen = optionListKind == OPT_ITEM
        // ⚠ 这里**不能用 [openOptionList]**：它会把 optionSearchText 清零再筛，等于每次输入都落回空候选
        optionListKind = OPT_ITEM
        optionListMultiSelect = false
        optionSearchText = q
        applyOptionFilter()
        // 搜索框常驻在界面上，物品档没有"列表自带的那条搜索框"可建（见 [buildOptionSearchField]）
        if (!wasOpen) buildOptionSearchField()
        rebuildOptionList()
    }

    /** 建一个窗口字段按钮（位置按字段 key 现算 —— 列数随窗口宽度变，不能记死坐标） */
    private fun editFieldButton(key: String, onClick: () -> Unit) {
        val btn = NineSliceButton(
            fieldButtonX(key), editRowY(editRowOf(key)), fieldButtonW(key), EDIT_ROW_H,
            Text.literal(""), { onClick() }
        )
        editButtons[key] = btn
        addDrawableChild(btn)
    }

    /**
     * 「Lv.」前缀占的宽度 —— 就取字面量的实际宽度（词条与全项目等级行同一个）。
     *
     * 数字要**正好接上**（用户 2026-09-20）：输入框自带的 4px 文字内边距刚好当间隙，
     * 前缀宽度取字面量宽度即可，不用另外加。
     */
    private fun levelLabelW(): Int = textRenderer.getWidth(Text.translatable(LEVEL_LABEL_KEY))

    /**
     * 「亲密度」标签的宽度（输入框让位量与标签绘制**共用这一个数**，别各算一份）。
     *
     * ⚠ **放不下就返回 0** = 不画标签、输入框恢复全宽。字段列宽是跟着窗口变的：
     * 常见窗口（`editDialogW` 取到上限 560）一列 **82px**，够放英文 `Friendship`(60px) + 3 位数输入框；
     * 但 GUI scale 调大后窗口被钳到 300，一列只剩 **39px** —— 那时硬画标签会把输入框挤没，
     * 不如退回"没有标签"（占位符「亲密度（最大255）」本来就在，只是默认填了 50 看不见）。
     * 判据是**让出标签后还剩多少**，见 [MIN_FRIENDSHIP_INPUT_W]。
     */
    private fun friendshipLabelW(): Int {
        val w = textRenderer.getWidth(Text.translatable("cobblemarket.points.store.field.friendship_label"))
        return if (editColW() - w >= MIN_FRIENDSHIP_INPUT_W) w else 0
    }

    /** 亲密度要不要画标签（宽度不够时退回不画，见 [friendshipLabelW]） */
    private fun showFriendshipLabel(): Boolean = friendshipLabelW() > 0

    /**
     * 「初始训练家（配信）」输入框前缀标签的宽度 —— 口径与 [friendshipLabelW] 一致，
     * 放不下就返回 0（不画标签、输入框吃满整格）。
     *
     * ⚠ 它是**文本**框（名字最长 16 字符），判据比亲密度的 3 位数宽一点：
     *   短名字（「サトシ」3 个字）也得完整看得见，见 [MIN_OT_INPUT_W]。
     */
    private fun otLabelW(): Int {
        val w = textRenderer.getWidth(Text.translatable("cobblemarket.points.store.field.ot_label"))
        return if (editColW() - w >= MIN_OT_INPUT_W) w else 0
    }

    /**
     * 「极巨等级」输入框前缀标签的宽度 —— **它的默认值是 0**，服主对着一个孤零零的「0」
     * 根本不知道是什么（用户 2026-09-27 报：「鬼知道这是什么，应该是极巨等级+输入框」）。
     * 口径与 [friendshipLabelW] 一致：整格减掉标签后放不下输入框就返回 0（不画标签、输入框吃满）。
     */
    private fun dmaxLabelW(): Int {
        val w = textRenderer.getWidth(Text.translatable("cobblemarket.points.store.field.dmax_label"))
        return if (editColW() - w >= MIN_FRIENDSHIP_INPUT_W) w else 0
    }

    /**
     * 该字段输入框在**列内**的左缩进（目前只有等级那格有前缀）。
     * ⚠ 建控件（[newEditField]）与滚动重排（[applyEditScroll]）**两处都走这里** ——
     *   各算一份的话，一滚动前缀就压到输入框上了。
     */
    private fun editFieldInsetX(key: String): Int = when (key) {
        KEY_LEVEL -> levelLabelW()
        // 极巨等级同亲密度：默认值是 0，光看数字不知道是什么（用户 2026-09-27 提出）
        KEY_DMAX -> dmaxLabelW()
        // 亲密度**默认填 50**，会把 placeholder 盖住 —— 服主看着一个光秃秃的数字不知道是什么意思，
        // 所以照等级的做法在左边画个标签、输入框让出标签宽度（2026-09-25 用户提出）
        KEY_FRIENDSHIP -> friendshipLabelW()
        // 初始训练家（配信）：文本框，同亲密度在左边画标签（填了名字后光看内容认不出这格是什么）
        KEY_OT -> otLabelW()
        else -> 0
    }

    /** 该字段输入框的宽度（让出 [editFieldInsetX] 那块前缀） */
    private fun editFieldW(key: String): Int = editColW() - editFieldInsetX(key)

    /**
     * 数值输入框的输入限制：**空串放行**（= 不填 / 随机），其余必须落在 [range] 里。
     *
     * ⚠ 超范围要**当场拒收**（predicate 返回 false 时这次输入根本不进框），别指望提交时那句
     *   `coerceIn` —— 那会把框里显示的 `150` 悄悄改成 `100` 再存下去，玩家看到的和存下的不是一回事。
     *   （2026-09-20 用户指出等级 / 亲密度 / 个体值 / 努力值都只限了位数、没限范围。）
     *
     * 顺带禁前导零（`031` 这种看着不像话）；单独一个 `0` 仍然合法（个体值 / 努力值 / 亲密度都取 0）。
     * 区间口径与服务端 [com.shusheng.cobblemarket.currency.PointMarketService] 的校验一致。
     */
    private fun intInputIn(range: IntRange, extraOk: (Int) -> Boolean = { true }): (String) -> Boolean = { s ->
        s.isEmpty() ||
            (s.length <= 3 && s.all { it.isDigit() } && (s.length == 1 || s[0] != '0') &&
                (s.toIntOrNull() ?: -1) in range &&
                extraOk(s.toIntOrNull() ?: 0))
    }

    /**
     * 六项努力值里**除第 [except] 项之外的和** —— 总和上限（[EV_TOTAL_MAX]）跨项判定用。
     * 读 `editEvText` 暂存文本，敲键时即时算（与 [hasIvInput] 同一套实时口径）。
     */
    private fun evSumExcept(except: Int): Int =
        editEvText.filterIndexed { idx, _ -> idx != except }
            .sumOf { it.trim().toIntOrNull() ?: 0 }

    /** 建一个窗口输入框（位置同样按字段 key 现算；predicate / 占位符 / 监听由调用方补） */
    private fun newEditField(key: String): TextFieldWidget {
        val f = TextFieldWidget(
            textRenderer, editColX(editColOf(key)) + editFieldInsetX(key), editRowY(editRowOf(key)),
            editFieldW(key), EDIT_ROW_H, Text.literal("")
        )
        addSelectableChild(f)
        addDrawableChild(f)
        return f
    }

    /** 字段按钮的点击分发（顺序表里除输入框以外的项都走这里） */
    private fun onFieldClick(key: String) {
        when (key) {
            KEY_SHINY -> cycleShiny()
            KEY_FORM -> openOptionList(OPT_FORM)
            KEY_FEATURE -> openOptionList(OPT_FEATURE)
            KEY_GENDER -> cycleGender()
            KEY_NATURE -> openOptionList(OPT_NATURE)
            KEY_ABILITY -> openOptionList(OPT_ABILITY)
            KEY_BALL -> openOptionList(OPT_BALL)
            KEY_SIZE -> cycleSize()
            KEY_TERA -> openOptionList(OPT_TERA)
            KEY_HELD -> openOptionList(OPT_CARRY)
            KEY_MARKS -> openOptionList(OPT_MARKS)
            KEY_MIN_IVS -> cycleMinIvs()
            KEY_ALPHA -> {
                editAlpha = !editAlpha
                refreshEditLabels()
            }

            KEY_GMAX -> cycleGmax()

            KEY_TRADEABLE -> {
                editTradeable = !editTradeable
                refreshEditLabels()
            }

            KEY_HELD_VISIBLE -> {
                editHeldVisible = !editHeldVisible
                // 预览要跟着变 —— 这个开关管的就是「别人看不看得到携带物」，
                // 编辑时该看到最终效果（2026-09-20 用户实测：切了没反应）
                refreshEditPreview()
                refreshEditLabels()
            }

            else -> key.removePrefix(KEY_SKILL).toIntOrNull()?.let { openOptionList("$OPT_SKILL$it") }
        }
    }

    // ── 窗口：字段交互 ──

    private fun updateEditSpecies(text: String) {
        editSpeciesCandidates = SpeciesText.candidatesByNameOrId(text)
        editSpeciesIndex = 0
        applyEditSpecies(editSpeciesCandidates.firstOrNull())
        updateEditSpeciesArrows()
    }

    /**
     * 清空物种搜索框（用户 2026-09-20）。
     *
     * 走 `text = ""` 而不是直接改 `editSpeciesText` —— 赋值会触发 changedListener，
     * 候选与预览跟着现有联动自然收掉，不必在这里再抄一遍那套逻辑。
     */
    private fun clearEditSpecies() {
        editSpeciesField?.text = ""
    }

    private fun cycleEditSpecies(delta: Int) {
        if (editSpeciesCandidates.size <= 1) return
        editSpeciesIndex = (editSpeciesIndex + delta + editSpeciesCandidates.size) % editSpeciesCandidates.size
        applyEditSpecies(editSpeciesCandidates[editSpeciesIndex])
        updateEditSpeciesArrows()
        // 切到别的物种了，叫声跟着换（只在这里叫：打字时每个字都会重解析，不能跟着叫）
        editCryAt = System.currentTimeMillis() + IconCryClick.AUTO_CRY_DELAY_MS
    }

    /** 箭头只在候选 ≥2 **且没展开选项列表**时出现（唯一候选没什么可切的；列表展开时它会是干扰） */
    private fun updateEditSpeciesArrows() {
        // ⚠ 候选箭头**也是叠层控件**（它们压在展开的列表上）—— 判据必须与 [updateEditControlsVisibility]
        //   的 `covered` 一致：**快照弹窗同样是叠层**。原来只判 `optionListKind == null`，
        //   于是快照弹窗一开、只要之前搜过物种（候选 > 1），这两枚箭头就冒出来压在**名字输入框**上，
        //   点输入框被它截胡 —— 表现成「有时能点、有时点不进输入态」（2026-09-25 用户报）
        val multi = editSpeciesCandidates.size > 1 && optionListKind == null && !snapshotDialog
        editSpeciesPrev?.visible = multi
        editSpeciesNext?.visible = multi
    }

    private fun applyEditSpecies(species: Species?) {
        editSpecies = species
        // 换了物种 ⇒ 它的完整招式表按需拉一份（技能候选与来源筛选都要用；见 [requestSpeciesMoves]）
        requestSpeciesMoves(species)
        // 换了物种，原来的性别 / 超极巨化 / 形态 / 特性 / 技能可能就不合法了（喷火龙 → 小磁怪）
        // → 各自收进合法范围
        normalizeGender()
        normalizeGmax()
        normalizeAspects()
        normalizeAbility()
        normalizeMoves()
        refreshEditPreview()
        refreshEditLabels()
    }

    /** 按当前形态 + 闪光选择重建预览模型（必闪时叠 `shiny` aspect，预览就渲染闪光形态） */
    private fun refreshEditPreview() {
        val species = editSpecies ?: run { editPreview = null; return }
        val aspects = editAspects.toMutableSet()
        if (isShinyAlways()) aspects.add("shiny")
        // ⚠ **头目 → aspect**：Cobblemon 的 `ALPHA_ASPECT` provider 会在 `isAlpha` 为真时提供 `"alpha"`，
        //   而它同时驱动**红眼**。预览走的就是同一套 `drawProfilePokemon`（购买庆祝动画里那只精灵
        //   就是这么红的，它的 aspects 来自服务端生成的完整精灵）；只有预览是从 `editAspects` 现建的、
        //   不带这个 ⇒ 手动补上（2026-09-25 用户报「头目按钮切换时大图眼睛不变红」）。
        //   ⚠ 别写到 `editAspects` 里：那是**形态**字段，头目由数据层的 `alpha` 表达（同性别那套做法）。
        if (editAlpha) aspects.add("alpha")
        // ⚠ **性别 → 形态**：公母长相不同的物种（爱管侍那类）靠 aspects 里的 male / female 区分，
        //   选了性别就叠上去，预览图才会跟着变（2026-09-20 用户要求的"反向生效"）。
        //   ⚠ **不写回 `editAspects`** —— 数据层的 `gender` 字段已经表达了性别，
        //   写回 aspects 会在生成精灵时重复设置。
        when (GENDER_VALUES.getOrNull(editGender).orEmpty()) {
            "MALE" -> {
                aspects.remove("female"); aspects.add("male")
            }

            "FEMALE" -> {
                aspects.remove("male"); aspects.add("female")
            }

            else -> Unit   // 随机 / 无性别：不叠，按物种默认形态渲染
        }
        // ⚠ 第三个参数是**携带物**：不传的话预览里看不到它带了什么（与市场的图标/大图同口径）。
        //   **可见性关着就不传** —— 那正是"别人会看到的样子"，编辑时也要照着显示
        editPreview = RenderablePokemon(
            species, aspects,
            if (editHeldVisible) stackOfSpec(editHeldItem) else ItemStack.EMPTY
        )
    }

    /**
     * 携带物规格串 → 物品栈（预览渲染用）。
     *
     * ⚠ 走项目现成的 [heldIconStack] **而不是自己解 SNBT** —— 它多一层兜底：
     * SNBT 重建失败还能按 `id` 建个基础物品，预览不至于整只消失（2026-09-20 用户实测：
     * 自己那版解不出来就返回空栈，携带物完全不显示）。
     */
    private fun stackOfSpec(snbt: String?): ItemStack {
        val raw = snbt?.takeIf { it.isNotEmpty() } ?: return ItemStack.EMPTY
        val id = runCatching {
            net.minecraft.nbt.StringNbtReader.parse(raw).getString("id")
        }.getOrDefault("")
        return com.shusheng.cobblemarket.client.heldIconStack(id, raw) ?: ItemStack.EMPTY
    }

    /**
     * 闪光档位按钮：点一下切到下一个档位，并把值**填进输入框**（输入框才是真值）。
     * 手动敲了非档位值（如 `0.5`）时，再点按钮会回到「不闪」—— 环从头上继续。
     */
    private fun cycleShiny() {
        val cur = editShinyText.trim()
        val idx = SHINY_PRESETS.indexOfFirst { it == cur }
        val next = SHINY_PRESETS[(idx + 1).mod(SHINY_PRESETS.size)]
        editShinyText = next
        editShinyField?.text = next
        refreshEditPreview()
        refreshEditLabels()
    }

    /** 必闪（概率 ≥ 100%）：预览渲染闪光形态，规格里写进 aspects 的 `shiny` */
    private fun isShinyAlways(): Boolean = (editShinyText.trim().toFloatOrNull() ?: 0f) >= 100f

    /** 存档里的 0~1 概率 → 输入框的百分数原文（整数不带小数点，小数照原样） */
    private fun formatChance(percent: Float): String =
        if (percent == percent.toInt().toFloat()) percent.toInt().toString() else percent.toString()

    /**
     * 该物种**可能**的性别档位下标（照特性按钮的做法：候选受物种约束）。
     *
     * 神兽那种无性别物种只剩「无性别」，只有单一性别的物种同理 —— 此时按钮点也点不动。
     * 「随机」只在有两种以上性别时才有意义（唯一的性别没什么可随机）。
     * 物种还没解析出来时给全部档位。
     */
    private fun availableGenderIndices(): List<Int> {
        val possible = editSpecies?.possibleGenders ?: return GENDER_VALUES.indices.toList()
        return GENDER_VALUES.indices.filter { idx ->
            val name = GENDER_VALUES[idx]
            if (name.isEmpty()) possible.size > 1
            else possible.any { it.name == name }   // 值直接用 Cobblemon 的枚举名
        }
    }

    /**
     * 这个物种**能不能超极巨化** —— 照性别按钮的做法受物种约束。
     *
     * `Species.canGmax()`（Cobblemon 自己判的：形态里有没有 `gmax`）。
     * ⚠ 与「极巨化等级」区分开：**所有**精灵都能极巨化，那个不受物种约束，别一起限制。
     */
    private fun speciesCanGmax(): Boolean = editSpecies?.canGmax() ?: true

    /** 不支持的物种固定为「否」（换物种 / 编辑老数据时原来的「是」可能就不合法了） */
    private fun normalizeGmax() {
        if (!speciesCanGmax()) editGmax = false
    }

    private fun cycleGmax() {
        // 没有超极巨化形态的物种点不动 —— 不是藏起来，是不给切（同性别按钮）
        if (!speciesCanGmax()) return
        editGmax = !editGmax
        refreshEditLabels()
    }

    /** 性别档位收进该物种可能的范围（换物种 / 编辑老数据时原性别可能不合法） */
    private fun normalizeGender() {
        val options = availableGenderIndices()
        if (options.isNotEmpty() && editGender !in options) editGender = options.first()
    }

    /**
     * 形态收进该物种**真有的**范围 —— 与 [normalizeGender] / [normalizeGmax] 同一件事的第三项。
     *
     * 用户 2026-09-20 报：性别与超极巨化都会跟着搜索出的物种收，唯独形态不收 ——
     * 换成没有该形态的精灵（喷火龙 → 小磁怪），形态按钮还挂着上一个物种的形态。
     *
     * 判据与形态候选表**同一个口径**（见 [buildOptionItems] 的 `OPT_FORM`：候选 = 物种各个
     * form 的 aspects）。比对**按集合不按字符串** —— 顺序不该影响合法性判定。
     * 对不上就回到「默认」（`editAspects` 空 = 标准形态，按钮显示「默认」）。
     */
    private fun normalizeAspects() {
        if (editAspects.isEmpty()) return
        // ⚠⚠ **候选表还没到就整条跳过**：这时候"变体"的 aspect 认不出来（`featureGroupsOf` 是空的），
        //   会被当成非法形态**当场清掉**。而快照回填正是「回填 → 紧跟一条 normalizeAspects」的路子
        //   （见 [applyDraft]），赶在包回来之前回填就把服主存好的花纹吃了。**宁可晚判也不能误清**。
        if (!PointMarketCache.featureAspectsLoaded) return
        val known = featureGroupsOf(editSpecies).flatMap { it.aspects }.toSet()
        // 变体那部分**逐个**判：换物种后旧物种的变体值可能就不在这份候选里了，直接丢掉
        val variants = editAspects.filter { it in known }
        val rest = editAspects.filterNot { it in known }
        // 剩下的是形态（forms）：判据与形态候选**同一个数据源**（[SpeciesText.selectableForms]，
        // 已滤掉超极巨化）—— 两边不一致的话，选不出来的形态却判成合法、或者选得出来却被清掉
        val formOk = rest.isEmpty() || SpeciesText.selectableForms(editSpecies)
            .any { it.aspects.isNotEmpty() && it.aspects.toSet() == rest.toSet() }
        editAspects = (if (formOk) rest else emptyList()) + variants
    }

    /**
     * 特性收进该物种 / 形态**真有的**范围 —— 上面三件（性别 / 超极巨化 / 形态）之外的第四件
     * （用户 2026-09-20 报「特性按钮一样没受钳制」）。
     *
     * 判据与特性候选表**同一个口径**（[buildOptionItems] 的 `OPT_ABILITY` = [legalAbilities]，
     * **跟着当前形态走**）。
     *
     * ⚠ 物种为空时**也要清**：那时候选表是空的，而 [optionLabelOf] 查不到会把**内部 id 原样显示**
     *   出来（「特性：cobblemon:blaze」这种英文字母，用户实测）—— 清掉回「随机」，界面才说得通。
     */
    private fun normalizeAbility() {
        // ⚠ 「自由选择」开着时**不清理** —— 服主就是**故意**选该物种池外的特性（见 [editFreePick]）；
        //   关掉那个开关时由 [toggleFreePick] 显式跑一次本函数，把池外的值清回「随机」
        if (editFreePick) return
        if (editAbility.isEmpty()) return
        val allowed = legalAbilities().map { it.template.name }
        if (editAbility !in allowed) editAbility = ""
    }

    /**
     * 技能槽收进该物种 / 形态**能学的**范围 —— 第五件（用户 2026-09-20 拍板「要加」）。
     *
     * 判据与技能候选表**同一个口径**（[buildOptionItems] 的兜底分支：`legalMoveIds()`，
     * **跟着当前形态走**）。学不了的槽**清空** —— 即回「未指定」，生成时那一槽随机补招（数据层的既有语义）。
     *
     * ⚠ 别改成「替他随机填一个合法技能」：界面会凭空多出几个不是他选的技能、看着像自己设的，
     *   反而把「换物种后这里失效了」藏起来；清空则显示「技能1：空」，要重挑一目了然。
     *   服务端也接得住 —— 它只校验技能**存在**，不校验该物种能不能学（[PointMarketService]）。
     */
    private fun normalizeMoves() {
        // 同 [normalizeAbility]：自由选择开着就不清（关掉时由 [toggleFreePick] 补跑一次）
        if (editFreePick) return
        // ⚠ **完整招式表没到就先别清**（[moveTableReady]）：本地那份少三桶，拿它清会把教学 /
        //   形态专属 / 进化招**误清掉**（服主刚配好的「过热」一换形态就没了）。
        //   表到了由 `tick` 补跑一次本函数（那一跑才是拿全表判的）
        if (!moveTableReady(editSpecies)) return
        val ids = MoveText.slotIds(editMoves)
        if (ids.none { it.isNotEmpty() }) return
        val allowed = legalMoveIds()
        val fixed = ids.map { if (it.isEmpty() || it in allowed) it else "" }
        if (fixed != ids) editMoves = MoveText.encode(fixed)
    }

    /**
     * 右上角那枚**「自由选择特性 / 技能」**的点击 —— 连带「非法」一起管（不变式见 [editFreePick]）。
     *
     * ⚠ **关掉时要把该物种池外的特性 / 技能清掉**（跑那两处 normalize）：它们之所以能留在草稿里，
     *   全靠这个开关开着；开关一关，它们就是「换物种后没清干净的残留」—— 与既有行为一致。
     *   ⚠ 不清的话会停在"自由关着、非法也没勾，但特性是非法的"这种自相矛盾的状态上，
     *     提交后造出来的就是**没有标记的非法精灵**。
     */
    private fun toggleFreePick() {
        editFreePick = !editFreePick
        if (editFreePick) {
            // ⭐ 联动（用户 2026-09-30 定）：放开限制造出来的必然非法 ⇒ 自动打上标记，别让服主忘
            editIllegal = true
        } else {
            editIllegal = false
            normalizeAbility()
            normalizeMoves()
        }
        refreshEditLabels()
    }

    /**
     * 底部那枚**「非法」**的点击 —— 取消它时**连带取消「自由选择」**（不变式：自由 ⇒ 非法，见 [editFreePick]）。
     *
     * ⚠ 顺序：先关「自由」（[toggleFreePick] 会把池外的特性 / 技能清掉，并把非法一并置回 false），
     *   所以这里直接转发给它就行，别自己再改一遍 —— 两处各改一遍必然分叉。
     */
    private fun toggleIllegal() {
        if (editIllegal && editFreePick) {
            toggleFreePick()
            return
        }
        editIllegal = !editIllegal
        refreshEditLabels()
    }

    private fun cycleGender() {
        val options = availableGenderIndices()
        // 只有一种可能（神兽那种）就点不动 —— 按用户要求，不是藏起来而是不给切
        if (options.size <= 1) return
        val i = options.indexOf(editGender)
        editGender = options[(if (i < 0) 0 else i + 1) % options.size]
        refreshEditPreview()
        refreshEditLabels()
    }

    private fun cycleSize() {
        editSize = (editSize + 1) % (PokemonSizeCategory.entries.size + 1)
        refreshEditLabels()
    }

    /**
     * 六项个体值里**有没有填过** —— 与「保底 V 数」互斥的判据（两个方向见 [applyIvInputLock]）。
     * 读 `editIvText` 暂存文本：输入框的 listener 会实时同步它（见 buildPokemonFields）。
     */
    private fun hasIvInput(): Boolean = editIvText.any { it.isNotBlank() }

    /** 保底 V 数 > 0 ⇒ 六项输入框锁住 */
    private fun ivInputsLocked(): Boolean = editMinIvs > 0

    /**
     * 六项个体值输入框的锁：**保底 V 数 > 0 时不许再输入**（用户 2026-09-20 拍板的互斥反方向）。
     *
     * 为什么反向也要锁：只禁保底按钮的话，「六项填了值 → 保底被静默丢弃」这个坑还在。
     * 两侧都锁死，界面就不可能停在「两个都设了、不知道听谁」的状态上。
     *
     * `active = false` 拦住鼠标（点不进焦点），再**把已有焦点踢掉** —— 原版 Screen 分发按键时
     * 不看 active，留着焦点还能往一个点不动的框里打字。
     */
    private fun applyIvInputLock() {
        val locked = ivInputsLocked()
        editIvFields.forEach { f ->
            f.active = !locked
            if (locked && focused === f) focused = null
        }
    }

    private fun cycleMinIvs() {
        // 六项已逐项指定就不给动 —— 两者互斥，能点的话只会把保底静默丢弃（同性别按钮：不是藏起来，是不给切）
        if (hasIvInput()) return
        editMinIvs = (editMinIvs + 1) % 7
        refreshEditLabels()
    }

    // 下架入口 = 编辑窗右上角那枚「下架」（见 [removeFromEditDialog]，只在编辑已有格子时出现）。
    // 2026-09-25 曾删掉过一次（改为「下架 → 重新添加」），2026-10-26 随「点击直接编辑」一起恢复。

    // ── 窗口：展开式选项列表（形态 / 性格 / 特性 / 球 / 太晶 / 技能 共用）──

    /**
     * 展开一个选项列表。**展开时窗口其余控件全部隐藏** —— 它们先于列表按钮添加，
     * 不隐藏的话点击会被它们拦截、列表永远点不到（黑名单那儿踩过）。
     */
    private fun openOptionList(kind: String) {
        // 技能列表：**来源筛选复位**（与搜索词同一个口径）+ 顺手催一下完整招式表 ——
        // 表还没到也照常开（候选用本地三桶），到了由 `tick` 把正开着的列表重建一次
        if (kind.startsWith(OPT_SKILL)) {
            moveSourceFilter = null
            requestSpeciesMoves(editSpecies)
        }
        // ⚠⚠ **每个「可选字段」都得能回到「随机」**（2026-09-27 用户实测：特性 / 性格 / 太晶
        //   选中一个值之后就再也回不去了 —— 候选里压根没有"随机"这一项）。
        //   形态不用管：它的候选本来就带「默认」（`OptionItem("")`）。
        //   值给**空串** = 该字段的默认态（服务端按空 = 随机生成）。
        // ⚠ 球种**不加**：它表达的是"造出来的精灵装哪个球"，没有"随机球"这个语义
        //   （用户点名的也只有特性 / 性格 / 太晶三项）。
        optionListAll = buildOptionItems(kind).let { base ->
            val needRandom = kind == OPT_NATURE || kind == OPT_ABILITY || kind == OPT_TERA
            if (needRandom) {
                listOf(
                    OptionItem("", Text.translatable("cobblemarket.points.store.value.random").string)
                ) + base
            } else base
        }
        // 携带物 / 商品物品一进来就是空的（搜索式，见 buildCarryItems / buildItemCandidates），
        // 不能当成"这个字段没有候选"。
        // ⚠ 商品物品**已经不再从这里进来了**（搜索框常驻在界面上，见 [updateItemSearch]）——
        //   这一支留着当兜底：真调进来也是"空列表 + 边打边搜"，不会被误判成"这个字段没候选"而响失败音
        if (optionListAll.isEmpty() && kind != OPT_CARRY && kind != OPT_ITEM) {
            playFailSound()
            return
        }
        optionListKind = kind
        // 「变体」也是多选：**每个维度各选一个、互不干扰**，选完还得接着选下一个维度，
        // 所以点一下不能关列表（同证章那套，靠「返回」收场）
        optionListMultiSelect = kind == OPT_MARKS || kind == OPT_FEATURE
        optionSearchText = ""
        applyOptionFilter()
        buildOptionSearchField()
        rebuildOptionList()
    }

    private fun closeOptionList() {
        optionSearchField?.let { remove(it) }
        optionSearchField = null
        optionSearchText = ""
        optionListAll = emptyList()
        optionListItems = emptyList()
        optionListKind = null
        optionListButtons.forEach { remove(it) }
        optionListButtons.clear()
        optionListScroll = 0
        // ⚠ 滚动条可能**正被拖着**（列表关掉时鼠标还按着）—— 状态一并复位，
        //   否则会留个"半拖"状态：下次画出来的是新内容，而 `dragging` 还是 true，鼠标没按也跟着走
        ScrollBar.shared.cancel()
        updateEditControlsVisibility()
        refreshEditLabels()
    }

    /**
     * 搜索词过滤（**纯本地**：候选本来就是客户端现成的，不用走服务端的列表请求那套节流）。
     * 中文名直接 `contains`，英文技能名大小写不敏感。
     */
    private fun applyOptionFilter() {
        // 携带物是**搜索式**的：列表内容本身跟着搜索词重建（不是"过滤一份预生成的列表"）
        // 搜索式：「携带物」与「商品物品」的列表内容都跟着搜索词**重建**（不是"过滤一份预生成的列表"）
        if (optionListKind == OPT_CARRY || optionListKind == OPT_ITEM) {
            optionListAll = if (optionListKind == OPT_ITEM) buildItemCandidates(optionSearchText)
            else buildCarryItems(optionSearchText)
            optionListItems = optionListAll
            optionListScroll = 0
            return
        }
        val q = optionSearchText.trim()
        // 技能列表的来源筛选（`null` = 全部）：**空集那一类（「无」）任何筛选下都留着** ——
        // 否则选了某一桶之后「无」被筛掉，那一槽就再也清不掉了
        val bySource = if (showMoveFilterRow() && moveSourceFilter != null) {
            optionListAll.filter { it.sources.isEmpty() || moveSourceFilter in it.sources }
        } else optionListAll
        // ⚠ 搜索时**把分节标题一起滤掉**（「变体」列表的分节头）：它自己不是候选项，
        //   留着的话要么匹配不上干占一行、要么匹配上了却让分组看着错位 —— 搜索态只看命中项
        optionListItems = if (q.isEmpty()) bySource
        else bySource.filter { !it.isHeader && it.label.contains(q, ignoreCase = true) }
        // 过滤后行数变了，滚动位置要回到顶（不然停在一个空白的偏移上）
        optionListScroll = 0
    }

    /**
     * 技能列表顶上那排**来源筛选按钮**显不显示。
     *
     * 只有技能候选有"来源"可言：「自由选择」那份是**全表**（`Moves.all()`，900 条上下），
     * 压根没有桶 ⇒ 不显示 —— 别摆一排点了没反应的按钮。
     */
    private fun showMoveFilterRow(): Boolean =
        optionListKind?.startsWith(OPT_SKILL) == true && !editFreePick

    /** 筛选按钮的文案：`全部` + 六桶（[SpeciesText.MoveSources]）；未收录回落显示键本身 */
    private fun moveSourceLabel(source: String?): String {
        val key = "cobblemarket.points.store.movesrc.${source ?: "all"}"
        val t = Text.translatable(key).string
        return if (t == key) source.orEmpty() else t
    }

    /**
     * 筛选按钮的**标签与矩形** —— 渲染与点击判定**共用这一份**（各算一份必然错位）。
     * 列表第一项是「全部」（`source = null`）。
     *
     * ⚠ **流式排布**：一行放不下就换行 —— 列表只占左半边（见 [optionListW]，最宽也就 260 出头），
     *   而中英两版宽度差得多（「形态专属」36px vs `Form` 24px），写死一行在窄窗口下会戳出去。
     *   ⚠ 第一项不参与换行（再窄也得有个落点），否则窗口一窄就无限换行。
     */
    private fun moveFilterChips(): List<Pair<String?, com.shusheng.cobblemarket.client.TooltipPlacement.Rect>> {
        val out = mutableListOf<Pair<String?, com.shusheng.cobblemarket.client.TooltipPlacement.Rect>>()
        val left = editDialogX() + EDIT_PAD
        val right = left + optionListW()
        var x = left
        var y = editDialogY() + OPTION_FILTER_Y
        (listOf<String?>(null) + SpeciesText.MoveSources.ORDER).forEach { source ->
            val w = textRenderer.getWidth(moveSourceLabel(source)) + OPTION_FILTER_PAD * 2
            if (x > left && x + w > right) {
                x = left
                y += OPTION_FILTER_H + OPTION_FILTER_GAP
            }
            out += source to com.shusheng.cobblemarket.client.TooltipPlacement.Rect(x, y, w, OPTION_FILTER_H)
            x += w + OPTION_FILTER_GAP
        }
        return out
    }

    /** 筛选行占掉的高度（不显示时 = 0）—— [optionListTop] 靠它给列表让位 */
    private fun moveFilterRowHeight(): Int {
        if (!showMoveFilterRow()) return 0
        val chips = moveFilterChips()
        val bottom = chips.maxOf { it.second.y + it.second.h }
        return bottom - (editDialogY() + OPTION_FILTER_Y) + OPTION_FILTER_LEAD
    }

    /**
     * **整条筛选行**的矩形（含按钮之间的空隙）。
     *
     * ⚠ 点击判定要按整条带子吃 —— 只吃按钮本身的话，点在两个按钮中间会**落到右边那张精灵大图上**，
     *   精灵当场叫一声（那是预览的点击效果），看着像"按钮坏了"。
     */
    private fun moveFilterBand(): com.shusheng.cobblemarket.client.TooltipPlacement.Rect =
        com.shusheng.cobblemarket.client.TooltipPlacement.Rect(
            editDialogX() + EDIT_PAD,
            editDialogY() + OPTION_FILTER_Y,
            optionListW(),
            moveFilterRowHeight()
        )

    /**
     * 点某个筛选按钮：切到那一档（点「全部」回全量）。
     * ⚠ 只动筛选、**不关列表**（多半还要接着挑技能）；不响那声点击音会让人以为没生效。
     */
    private fun clickMoveFilterChip(mouseX: Double, mouseY: Double): Boolean {
        if (!showMoveFilterRow()) return false
        val hit = moveFilterChips()
            .firstOrNull { it.second.contains(mouseX.toInt(), mouseY.toInt()) }
            ?: return false
        playButtonClickSound()
        moveSourceFilter = hit.first
        applyOptionFilter()
        rebuildOptionList()
        return true
    }

    /**
     * 列表上方的搜索框（列表展开期间才有，关列表时一并移除）。
     *
     * ⚠ **商品物品那档已经不用它了**（2026-09-26）：物品窗的搜索框**常驻在界面上**
     * （见 [buildItemFields]），列表跟着它走。这里再建一个就成了"列表自带的那条"，
     * 两条搜索框并存、各自开着不同的词 —— 那正是上一版做歪的地方。
     */
    private fun buildOptionSearchField() {
        if (optionListKind == OPT_ITEM) return
        val field = TextFieldWidget(
            textRenderer,
            editDialogX() + EDIT_PAD, editDialogY() + OPTION_SEARCH_Y,
            optionListW(), OPTION_SEARCH_H,
            Text.literal("")
        )
        // 复用全项目统一的搜索框占位词条（黑名单、市场那些列表都是它）
        field.setPlaceholder(Text.translatable("cobblemarket.gui.search"))
        field.setMaxLength(48)
        field.setChangedListener { text ->
            optionSearchText = text
            applyOptionFilter()
            rebuildOptionList()
        }
        field.text = optionSearchText
        optionSearchField = field
        addSelectableChild(field)
        addDrawableChild(field)
    }

    /**
     * 小手按钮：取**主手那件**当商品（组件一起提取）。
     *
     * ⚠⭐ 走 [ItemRuleComponents.extractFullSpec]（**完整组件**）—— 与列表选中候选那条路
     *（[selectOption] 的 `OPT_ITEM` 分支，走**白名单**）**有意不是一套**（2026-10-02 用户拍板）：
     * 小手 = **复制服主手上那件**（要一模一样，含模组自定义的插件 / 升级数据）；
     * 候选 = 凭空造一件**全新**的（附魔书 / 招式学习器，本来就只有白名单那几个组件）。
     */
    private fun pickHeldItemAsShopEntry() {
        val stack = client?.player?.mainHandStack ?: return
        if (stack.isEmpty) {
            playFailSound()
            return
        }
        val lookup = client?.world?.registryManager ?: return
        setEditItem(
            Registries.ITEM.getId(stack.item).toString(),
            stack.name.string,
            // ⭐ **完整组件**（2026-10-02 用户拍板）：「小手要做到完完整整的复制手上那件」——
            //   附魔 / 磨损之外，「插件 / 升级数据」这类**模组自定义组件**也要跟着商品走
            //   （少一个，买家拿到的就不是原来那件，物品商城的功能就不完整）。
            //   ⚠ 与规则条目（黑名单 / 价格限制 / 求购单）的**白名单**口径**有意不同，别"统一"掉**：
            //   那边存的是"匹配条件"，白名单外的组件会变成怪规则 —— 见 [ItemRuleComponents] 的注释
            ItemRuleComponents.extractFullSpec(stack, lookup)
        )
        // 名字回填进搜索框（否则框里还是上一次搜的词，看着像"没选上"）
        setItemSearchText(editItemName)
        // 选完关列表（它结尾会刷新标签，不用自己再刷一次）
        closeOptionList()
    }

    /**
     * 把选中的物品名**回填进搜索框**，且**不触发** [updateItemSearch]。
     *
     * 选完一条候选后，框里留着用户打的搜索词（比如「钻石」）会让人不确定到底选了哪件 ——
     * 换成那件物品的完整名字，与右侧那张大图互为印证。
     *
     * ⚠ 直接 `field.text =` 会**同步触发** changedListener ⇒ 这次回填被当成"又在打字"、列表立刻弹回来
     * （选中即重开）。所以拿 [editItemSearchSyncing] 挡一道。
     */
    private fun setItemSearchText(text: String) {
        editItemSearchText = text
        val f = editItemSearchField ?: return
        if (f.text == text) return
        editItemSearchSyncing = true
        f.text = text
        editItemSearchSyncing = false
    }

    /**
     * 有没有东西**叠在编辑窗上面**（展开的选项列表 / 快照弹窗）。
     *
     * ⚠ 判据是「**有没有叠层**」，不是「选项列表展开了没」—— 快照弹窗也是叠层（它盖在添加窗上），
     *   只判 optionListKind 的话，快照弹窗一开编辑窗那批控件就全冒出来了、黑压压压在新弹窗底下。
     *
     * ⚠ 抽成函数是因为它有**两个**使用点：编辑窗控件的显隐（[updateEditControlsVisibility]）
     *   与 info 按钮的每帧显隐（[render]）。这种"两处都要判"的东西各写一份，改一处必漏另一处。
     */
    private fun editDialogCovered(): Boolean = optionListKind != null || snapshotDialog

    /**
     * 窗口控件的显隐**统一入口**：展开列表时全藏，否则按滚动可见性。
     * 各写一遍必然漏 —— 展开完列表恢复时会把滚出视野的按钮也一起显示出来。
     */
    private fun updateEditControlsVisibility() {
        val covered = editDialogCovered()
        editSpeciesField?.visible = !covered
        editPriceField?.visible = !covered
        editStockField?.visible = !covered
        // ⚠ 格子冷却框同样要显式恢复：兜底逻辑只在 covered 时隐藏，**不会**在收起列表后把它放回来
        editCooldownField?.visible = !covered
        // 滚动区里的输入框：按各自行号的可见性来（见 [scrollableFields]）
        scrollableFields().forEach { (field, key) ->
            field?.visible = !covered && editRowVisible(editRowOf(key))
        }
        editConfirmButton?.visible = !covered
        editCancelButton?.visible = !covered
        editRemoveButton?.visible = !covered
        // 右上角那枚「快照」同理（它也是编辑窗的控件，别忘在清单外）
        editSnapshotButton?.visible = !covered
        // 形态警告那枚 `⚠`（按钮组最左）：**常驻** ⇒ 这里只管"叠层打开时一律藏"，
        //   免得展开选项列表时它浮在列表上。⚠ 它的**颜色**另在 [render] 里每帧刷
        editGatedInfoButton?.visible = !covered
        // 常驻的「非法」开关同理：它"常驻"指的是**不藏在形态判据后面**，
        // 不是"叠层打开时也露在外面"—— 展开选项列表 / 快照弹窗时照样得藏
        editIllegalButton?.visible = !covered
        // 右上角那枚「自由选择」：编辑窗的控件，跟 covered 走（它与快照是同一排的）
        editFreePickButton?.visible = !covered
        // 清空按钮：跟 covered 走就行（**不跟候选数量走** —— 那是箭头的事，清空随时都能点）
        editSpeciesClear?.visible = !covered
        editButtons.forEach { (key, btn) -> btn.visible = !covered && editRowVisible(editRowOf(key)) }
        // 列表的搜索框：**只在列表展开时在**（快照弹窗不算），显式跟着它走（别指望它默认 true 一直有效）
        optionSearchField?.visible = optionListKind != null
        // ⚠ **物品窗那对是常驻的**：列表展开时别的控件全藏，唯独它俩得留着 ——
        //   搜索框就是列表的驱动（藏起来就没法接着打字），小手与它并排、一起留着
        editItemSearchField?.visible = true
        editItemHeldButton?.visible = true
        // 候选箭头同理：它们也压在列表上（用户实测：选形态时两枚箭头跑到列表界面里了）
        updateEditSpeciesArrows()

        // ⚠ **兜底**：叠层打开时，凡是从 [editWidgetBase] 起建出来、又不在上面那份清单里的控件，
        //   一律隐藏 —— 逐个列名字隐藏必然有漏（上一条注释就是踩过的坑）。
        //   列表自己的控件（搜索框 / 返回）与**快照弹窗自己的控件**不在兜底范围内，它们本来就该显示。
        //   ⚠ 2026-09-25：这里原来只判 `optionListKind != null` —— **快照弹窗整个不吃兜底**，
        //     于是任何「漏判叠层」的控件都会在快照弹窗里冒出来（箭头的漏判就是这么显形的）。
        //     改判 `covered`，两处叠层一起兜。
        if (covered) {
            val own = buildSet<net.minecraft.client.gui.widget.ClickableWidget> {
                optionSearchField?.let { add(it) }
                // 物品窗常驻的搜索框 + 小手：**必须在白名单里**，否则被这条兜底一起藏掉 ——
                // 表现是"列表一展开，搜索框就没了、小手也点不到"（兜底本来就专治这种漏判）
                editItemSearchField?.let { add(it) }
                editItemHeldButton?.let { add(it) }
                addAll(optionListButtons)
                snapshotNameField?.let { add(it) }
                addAll(snapshotButtons)
            }
            children().drop(editWidgetBase)
                .filterIsInstance<net.minecraft.client.gui.widget.ClickableWidget>()
                .filter { it !in own }
                .forEach { it.visible = false }
        }
    }

    private fun rebuildOptionList() {
        optionListButtons.forEach { remove(it) }
        optionListButtons.clear()
        // 旧的那枚已经被 remove 了，字段跟着置空（否则 refreshEditLabels 会去改一个已卸下的按钮）
        optionClearButton = null
        val open = optionListKind != null
        updateEditControlsVisibility()
        if (!open) return
        // 行本身**自绘**（见 [renderOptionRows]：要在行首画物品图标，而 NineSliceButton 只收贴图 id）。
        // 这里只建底部那几枚 ——列表展开时其余按钮全藏了，没它们就只能按 Esc 退出。
        val btnY = editDialogY() + editDialogHeight() - EDIT_BUTTON_H - EDIT_PAD
        // 多选列表（证章）多一枚「清空」：挂了好几个时不用一个个点掉（2026-09-20 用户提议）。
        // 清空**不关列表**（清完往往还要接着挑），所以调用后不 closeOptionList。
        if (optionListMultiSelect) {
            val totalW = EDIT_BUTTON_W * 2 + 8
            val startX = editDialogX() + (editDialogW() - totalW) / 2
            val clear = NineSliceButton(
                startX, btnY, EDIT_BUTTON_W, EDIT_BUTTON_H,
                Text.translatable("cobblemarket.points.store.clear"), { clearOptionSelection() }
            )
            optionClearButton = clear
            // 一个都没挂时置灰（没什么可清的）—— 走统一刷新，别再在这里单独设一次
            //（那正是原来的 bug：只设这一次，之后状态变了没人管）
            refreshOptionClearButton()
            optionListButtons.add(clear)
            addDrawableChild(clear)
            val back = NineSliceButton(
                startX + EDIT_BUTTON_W + 8, btnY, EDIT_BUTTON_W, EDIT_BUTTON_H,
                Text.translatable("cobblemarket.gui.back"), { closeOptionList() }
            )
            optionListButtons.add(back)
            addDrawableChild(back)
            return
        }
        val back = NineSliceButton(
            editDialogX() + (editDialogW() - EDIT_BUTTON_W) / 2,
            btnY, EDIT_BUTTON_W, EDIT_BUTTON_H,
            Text.translatable("cobblemarket.gui.back"), { closeOptionList() }
        )
        optionListButtons.add(back)
        addDrawableChild(back)
    }

    /**
     * 列表底部那枚「清空」：**不关列表**，清完还能接着挑。
     *
     * ⚠ 按列表种类分派：证章清的是勾选；变体清的是**所有维度的选中**。
     *   变体这边**只清变体的**（`featureGroupsOf` 里那些）—— 形态的 aspect **不动**，
     *   它们归形态按钮管，清变体不该顺手把形态也清掉。
     */
    private fun clearOptionSelection() {
        when (optionListKind) {
            OPT_FEATURE -> {
                val all = featureGroupsOf(editSpecies).flatMap { it.aspects }.toSet()
                editAspects = editAspects.filterNot { it in all }
                refreshEditPreview()
                refreshEditLabels()
            }

            else -> {
                editMarks = emptyList()
                refreshEditLabels()
            }
        }
        // 清完状态变了，按钮的亮灭要跟着走（统一刷新，别在调用点各设一次）
        refreshOptionClearButton()
    }

    /**
     * 列表第一行的**顶边**：搜索框底下；技能列表还要再让出**来源筛选那一行**（见 [moveFilterRowHeight]）。
     *
     * ⚠ 行数 / 行矩形 / 滚动条**三处必须共用这一个** —— 各算一份的话必有一处错位
     *   （要么压到筛选行上、要么滚动条比列表高出一截）。
     */
    private fun optionListTop(): Int = OPTION_LIST_TOP + moveFilterRowHeight()

    /** 列表一屏能放几行：**撑满窗口**（顶部到「返回」按钮上方），别在下面空一大片 */
    private fun optionListRows(): Int =
        ((editDialogHeight() - optionListTop() - OPTION_LIST_BOTTOM) / OPTION_ROW_STEP).coerceAtLeast(1)

    /** 列表第 [i] 个可见行的矩形 —— **渲染与点击判定共用这一个**，各算一份必然错位 */
    /**
     * 展开列表的**内容宽度**。
     *
     * ⚠ **「变体」列表只占左半边**（右边让给精灵大图 —— 见渲染分支里那处成对的 `OPT_FEATURE` 判断），
     *   其余列表（性格 / 球种 / 技能…）照旧占满整窗。
     * ⚠ 行背景 [optionRowRect] 与搜索框**必须共用这一个**：各算一份的话两个宽度对不上，
     *   搜索框会戳出列表右边。
     */
    private fun optionListW(): Int {
        val full = editDialogW() - EDIT_PAD * 2
        // 左边界一直排到预览区左缘（`editPreviewX()`），两边各留一个内边距
        return (editPreviewX() - editDialogX() - EDIT_PAD * 2).coerceIn(0, full)
    }

    private fun optionRowRect(i: Int) = com.shusheng.cobblemarket.client.TooltipPlacement.Rect(
        editDialogX() + EDIT_PAD,
        editDialogY() + optionListTop() + i * OPTION_ROW_STEP,
        optionListW(),
        OPTION_ROW_H
    )

    /** 某行是不是「当前选中」：多选列表看已挂的证章，单选列表比当前值 */
    private fun isOptionSelected(item: OptionItem): Boolean =
        if (optionListKind == OPT_FEATURE) {
            // 「变体」：「不指定」项在该维度**一个值都没选**时打勾（其余项按"串在不在 editAspects 里"）
            if (item.value.isEmpty()) {
                featureGroupsOf(editSpecies).firstOrNull { it.key == item.groupKey }
                    ?.aspects.orEmpty().none { it in editAspects }
            } else {
                item.value in editAspects
            }
        } else if (optionListMultiSelect) {
            // 头目之证的勾选态**跟着头目开关走**（不由服主在这里勾），见 [effectiveMarksOf]
            if (item.value == ALPHA_MARK_ID) editAlpha else item.value in editMarks
        } else {
            item.value == currentOptionValue()
        }

    /** 「变体」：该物种的各个 feature 维度（服务端下发；表还没到 / 该物种没有 feature 时为空） */
    private fun featureGroupsOf(species: Species?): List<FeatureCandidates> =
        PointMarketCache.featureAspects[species?.resourceIdentifier?.toString()].orEmpty()

    /** 模型变体缓存：物种 id → 该物种模型里真有的 aspect 集合（`null` = 没算出来，见下） */
    private val modelAspectCache = mutableMapOf<String, Set<String>?>()

    /**
     * 该物种**模型里真有**的 aspect 集合 —— 只有在这里面的候选，选了才看得到变化。
     *
     * 依据是 Cobblemon 自己的模型分发文件（`assets/cobblemon/bedrock/pokemon/resolvers/` 下，
     * 每个物种一个「编号_物种名」目录，里面的 json 用 `variations[].aspects` 绑定外观）：
     * 一个 aspect 想在模型上生效就得在这儿挂一条 variation —— 森林蜥蜴的 `cosmetic_item-stick`
     * 正是挂了 `grovyle_stick.png` 那一层，才真能叼着木棍（用户 2026-09-27 实测确认）。
     *
     * ⚠ **返回 null = "没算出来"**（读不到资源 / 解析失败 / 该物种压根没模型变体），
     *   调用方**这时绝不能过滤** —— 宁可多列几个空选项，也别因为读不到文件把整张列表清空
     *   （模组加的物种常常没有自己的 bedrock 模型）。
     * ⚠ 这是**客户端资源**（`assets/`），服务端读不到 ⇒ 只能在客户端做这一道。
     * ⚠ 按需算 + 缓存：只在打开某个物种的变体列表时读一次，不必扫上千个物种。
     */
    private fun modelAspectsOf(species: Species?): Set<String>? {
        val id = species?.resourceIdentifier ?: return null
        val key = id.toString()
        if (modelAspectCache.containsKey(key)) return modelAspectCache[key]
        val result = runCatching {
            // ⚠ **直接用 Cobblemon 自己解析好的表**（`VaryingModelRepository`），不要自己去扫
            //   `bedrock/pokemon/resolvers/` 那份 json —— 它扫那目录时用的是 `listResources`，
            //   **不分命名空间**，模组 / 数据包 / 服务器资源包放在 `assets/<自己的id>/...` 下的
            //   皮肤与纹路**都会被收进来**；我们若自己按 species 目录名去匹配，只会匹配到内置那批，
            //   模组加的 aspect 会被当成"没实现"**误滤掉**（2026-09-27 用户追问时发现的）。
            //   用它这张表还有个好处：它已经按 order 合并好，Cobblemon 改了读法我们也自动跟上。
            com.cobblemon.mod.common.client.render.models.blockbench.repository.VaryingModelRepository
                .variations[id]
                ?.variations
                ?.flatMap { it.aspects }
                ?.toSet()
                // ⚠ 空集当成"没算出来"：物种可能只用默认外观（只有一条 aspects=[] 的 variation），
                //   这时返回空集会让**所有**候选被滤掉 —— 那就跟"读不到"一样，不该过滤
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        modelAspectCache[key] = result
        return result
    }

    /**
     * 「变体」按钮的标签内容：**已选的变体**用 `/` 连起来（一个都没选时显示「默认」）。
     *
     * ⚠ 只算 feature 那些 —— `editAspects` 里还混着**形态**（forms）的 aspect，那是「形态」按钮的事，
     *   显示到这儿会重复（用户 2026-09-27 拍板把两者彻底分开）。
     */
    private fun featureButtonLabel(): String {
        // ⚠ 同悬停窗：**属于形态的 aspect 不算变体**（`partner-cap` 那种两边都挂的会混进来）
        val formAspects = SpeciesText.selectableForms(editSpecies).flatMap { it.aspects }.toSet()
        val picked = featureGroupsOf(editSpecies)
            .flatMap { g -> g.aspects.filter { it in editAspects && it !in formAspects } }
        // ⚠ 每个值都要过 [featureValueLabel]（官方形态词条 → 物品名 → 原文）——
        //   直接拼 aspect 串的话按钮上会是 `cosmetic_item-rage_candy_bar`，而悬停窗却写着
        //   「愤怒馒头」，同一个东西两个名字（2026-09-27 用户报）
        return picked.joinToString("/") { featureValueLabel(it) }.ifEmpty {
            Text.translatable("cobblemarket.points.store.value.default").string
        }
    }

    /**
     * 「变体」里点一项：**只动它所属的那一个维度**。
     *
     * 这就是"组内互斥、组间共存"的落点 —— 先把**该维度**（同 [OptionItem.groupKey]）的旧值整个摘掉、
     * 再加新值；别的维度的 aspect **原样留着**，所以花纹和帽子能同时挂在同一只精灵上。
     *
     * ⚠ 「不指定」项（空串）：**只摘不加** —— 摘干净 = "这个维度不管，交给游戏按 `default` 处理"。
     *   ⚠ `default = random` 的维度（阿柏怪花纹那类）**不指定就是每次生成都随机掷一个**，
     *   商城是每次购买现造一只 ⇒ 每个买家拿到的可能都不一样。
     */
    private fun toggleFeatureAspect(item: OptionItem) {
        val group = featureGroupsOf(editSpecies).firstOrNull { it.key == item.groupKey } ?: return
        val rest = editAspects.filterNot { it in group.aspects }
        editAspects = if (item.value.isEmpty()) rest else rest + item.value
        refreshEditPreview()
        refreshEditLabels()
        // ⚠ 「清空」的亮灭跟着选中状态走（原先漏了这一句，选了变体按钮还是灰的 —— 用户 2026-09-27 报）
        refreshOptionClearButton()
        // 不关列表、也不用重建控件：行是自绘的，下一帧就带上新的勾选状态
    }

    private fun toggleMultiOption(item: OptionItem) {
        // 「变体」走按维度替换，不是简单切换（见 [toggleFeatureAspect]）
        if (optionListKind == OPT_FEATURE) {
            toggleFeatureAspect(item)
            return
        }
        // **头目之证不归这里切**：它跟着头目开关走，勾掉也没用（Cobblemon 生成时照样带）——
        // 让它可点反而是误导，所以点了不响应，要改就去动头目那个按钮
        if (item.value == ALPHA_MARK_ID) return
        editMarks = if (item.value in editMarks) editMarks - item.value else editMarks + item.value
        refreshEditLabels()
        // 不关列表、也不用重建控件：行是自绘的，下一帧就带上新的勾选状态
    }

    private fun currentOptionValue(): String? = when (optionListKind) {
        // ⚠ 同理只取形态：混进变体的话，形态列表里原来的地区形态一项**再也不会被打勾**（比不相等）
        OPT_FORM -> pickedFormAspects().joinToString(",")
        OPT_NATURE -> editNature
        OPT_ABILITY -> editAbility
        OPT_BALL -> editBall
        OPT_TERA -> editTera
        OPT_CARRY -> editHeldItem.orEmpty()
        // 证章与「变体」都是多选，没有单一「当前值」（选中判定走 isOptionSelected）
        OPT_MARKS -> null
        OPT_FEATURE -> null
        else -> optionListKind?.removePrefix(OPT_SKILL)?.toIntOrNull()
            ?.let { MoveText.slotIds(editMoves).getOrNull(it).orEmpty() }
    }

    private fun selectOption(value: String) {
        when (optionListKind) {
            OPT_FORM -> {
                // ⚠ **只换形态那一部分**，变体（species feature）的值原样留着 ——
                //   整组替换的话，选个地区形态就把花纹吃掉了（两者是并存的，见 [toggleFeatureAspect]）
                val variants = editAspects.filterNot { it in pickedFormAspects() }
                editAspects = value.split(",").filter { it.isNotEmpty() } + variants
                // ⚠⭐ 换了形态 ⇒ 特性池与技能池跟着换（见 [legalAbilities] / [legalMoves]），原来选的
                //   特性 / 技能可能就不在这个形态的池子里了 ⇒ 立刻收一遍，与「换物种」同一个口径
                //   （[applyEditSpecies] 也跑这两条）。不清的话能配出「默认形态 + 过热」这种自相矛盾的
                //   组合、还不会被打上「非法」标 —— 正是这次要堵的那条路
                normalizeAbility()
                normalizeMoves()
                refreshEditPreview()
            }

            OPT_NATURE -> editNature = value
            OPT_ABILITY -> editAbility = value
            OPT_BALL -> editBall = value
            OPT_TERA -> editTera = value
            OPT_CARRY -> {
                editHeldItem = value.ifEmpty { null }
                editHeldItemName = optionListItems.firstOrNull { it.value == value }?.label.orEmpty()
                // 换了携带物，预览要跟着变（不然图标上还是旧的）
                refreshEditPreview()
            }

            OPT_ITEM -> {
                // 候选自带重建好的物品栈（见 [buildItemCandidates]），走**白名单**提取 ——
                // ⚠ 与「小手」（[pickHeldItemAsShopEntry]，**完整组件**）**有意不是一套**（2026-10-02）：
                //   候选是凭空构造的全新物品（附魔书 / 招式学习器），本来就只有白名单那几个组件；
                //   小手要完整复制手上那件，见那边的注释
                optionListItems.firstOrNull { it.value == value }?.let { opt ->
                    val stack = opt.stack ?: return@let
                    val lookup = client?.world?.registryManager ?: return@let
                    setEditItem(
                        Registries.ITEM.getId(stack.item).toString(),
                        // ⚠ 名字取**候选的 label**（= `displayName`），不是栈名 —— 变体候选
                        //   （招式学习器 / 附魔书）在栈里只有「招式学习器」四个字，
                        //   回填进搜索框就看不出选的到底是哪个招式 / 哪条附魔了
                        opt.label,
                        // 同上：实物语义，磨损跟着商品走
                        ItemRuleComponents.extractSpec(stack, lookup, includeDamage = true)
                    )
                    setItemSearchText(editItemName)
                    // ⚠ 不用自己刷标签：`selectOption` 结尾的 [closeOptionList] 会统一刷
                }
            }

            else -> {
                val slot = optionListKind?.removePrefix(OPT_SKILL)?.toIntOrNull()
                if (slot != null) {
                    val ids = MoveText.slotIds(editMoves).toMutableList()
                    while (ids.size < MoveText.SLOTS) ids.add("")
                    ids[slot] = value
                    editMoves = MoveText.encode(ids)
                }
            }
        }
        closeOptionList()
    }

    /**
     * 「变体」列表里**分节标题**的文字 = feature 注册名的词条（`cobblemarket.feature.<键>`）。
     *
     * ⚠ 未收录就**回落显示键本身**（`snake_pattern`）—— 模组 / 数据包自己加的 feature 我们
     *   不可能预先翻译，显示原文总比显示空白强（与 `SpeciesText.aspectLabel` 同一套做法）。
     */
    private fun featureGroupLabel(key: String): String {
        val k = "cobblemarket.feature.$key"
        val t = Text.translatable(k).string
        return if (t == k) key else t
    }

    /**
     * 「变体」**候选值**的显示名：优先用 **Cobblemon 官方的形态词条**
     * （`cobblemon.ui.pokedex.info.form.<aspect>` —— 图鉴里显示这个形态叫什么就用它），
     * 没有的**回落 aspect 原文**。
     *
     * ⚠ 官方那套并不全（实测 795 条里**不含**花纹 `snake-pattern-*`、饰品 `cosmetic-item-*`、
     *   `mega_x` 这些）⇒ 只能"有就用、没有回落"，别指望它一份搞定。
     * ⚠ 好处不止好看：**语言自动跟着走**（中文端「阿罗拉趋异的样子」、英文端它自己的写法），
     *   而且 Cobblemon 将来补了词条我们自动受益，不用维护自己的表。
     * ⚠ 别拿 `cobblemarket.aspect.*` 来顶（那是**形态按钮**用的短名表，用途不同）。
     */
    // ⚠ 实现已经挪到 [SpeciesText.featureValueLabel]（黑名单那边也要用，各写一份必然漂移）
    private fun featureValueLabel(aspect: String): String = SpeciesText.featureValueLabel(aspect)


    /**
     * 「不指定」那一项的名字 —— **跟着数据里的 `default` 走**（2026-09-27 用户提出）。
     *
     * 数据写 `random` 的（阿柏怪花纹、晃晃斑斑纹、霜奶仙奶油…共 23 个）就写**「随机」**，
     * 其余写「默认」。⚠ 不能一律叫「默认」：`default = random` 的维度**不选就是随机掷一个**，
     * 服主以为"默认"是标准外观，卖出去才发现每只都不一样（用户实测阿柏怪后当场问过这个）。
     * 标成「随机」，选之前就知道会发生什么。
     */
    private fun featureUnsetLabel(g: FeatureCandidates): String =
        if (g.default == "random") Text.translatable("cobblemarket.points.store.value.random").string
        else Text.translatable("cobblemarket.points.store.value.default").string

    /**
     * 某个字段的候选列表（值, 显示名）。
     * ⚠ 值一律存**游戏内的标识**（资源 id / 名字），显示名才走本地化 —— 反过来存会把中英两套数据搅在一起。
     */
    private fun buildOptionItems(kind: String): List<OptionItem> {
        val species = editSpecies
        return when (kind) {
            // 形态：默认（标准形态）+ 物种的各个 form；一个 form 对应一组 aspects
            OPT_FORM -> {
                val opts = mutableListOf(
                    OptionItem("", Text.translatable("cobblemarket.points.store.value.default").string)
                )
                // 超极巨化形态**不在候选里**：它是 battleOnly 形态，超极巨化由这枚窗口的
                // 「超极巨化」按钮（gmaxFactor）表达，见 SpeciesText.selectableForms
                // ⚠ **只管 forms** —— species feature（花纹 / 帽子 / 颜色）**不进这里**：
                //   混进来会把极巨化之类的战斗形态带回来、还会混入 `mega` 那种对某些物种无意义的
                //   死选项（2026-09-27 用户实测抓到 eternamax 后拍板拆开），见 [selectableFormAspects]
                SpeciesText.selectableFormAspects(species).forEach { (value, label) ->
                    opts.add(OptionItem(value, label))
                }
                opts
            }

            // 变体：species feature 的各个维度（花纹 / 帽子 / 颜色…），**按维度分节** ——
            // 组内互斥（点第二个换掉第一个）、组间共存（花纹和帽子能同时挂着）。
            // ⚠ 候选**只能靠服务端下发**（客户端拿不到 choice 型，见 [SpeciesText.allFeatureAspects]）：
            //   表还没到 / 该物种没有 feature 时这里是**空列表** ⇒ [openOptionList] 会响失败音
            //   并拒绝打开（= 按钮"点不动"，正是要的效果），别在这儿想办法自己算。
            OPT_FEATURE -> {
                val groups = featureGroupsOf(species)
                // ① 该物种**模型里真有**的 aspect（null = 还没算出来 / 读不到 ⇒ 不过滤）
                val modeled = modelAspectsOf(species)
                // ② 已经在「形态」里的 aspect 不再列（见下）
                val formAspects = SpeciesText.selectableForms(species).flatMap { it.aspects }.toSet()
                val opts = mutableListOf<OptionItem>()
                groups.forEach { g ->
                    // ⚠ **两道过滤缺一不可**：
                    //   ① 模型过滤治"空选项"—— 候选是 feature 的**全部** choices，而具体物种常常只实现了
                    //      其中一部分（森林蜥蜴的 `cosmetic_item` 26 个里只有 `stick` 有外观，用户实测）
                    //   ② 按值去重治"两处重复"—— Cobblemon 里 form 常常**就是** feature 的一个取值
                    //      （卡拉卡拉的阿罗拉形态 aspects 就是 `region-bias-alola`），不去重的话同一个东西
                    //      在形态按钮和变体按钮各出现一次，服主配了一边、另一边没配，还以为是我们的 bug
                    val visible = g.aspects.filter { a ->
                        (modeled == null || a in modeled) && a !in formAspects
                    }
                    // 整节都被滤掉就**不显示这一节**（剩下的值一个能用的都没有，标题+「默认」只是噪音）
                    if (visible.isEmpty()) return@forEach
                    // 维度标题（不可点）
                    opts.add(OptionItem("", featureGroupLabel(g.key), groupKey = g.key, isHeader = true))
                    // 「不指定」——名字跟着数据里的 default 走，见 [featureUnsetLabel]
                    opts.add(OptionItem("", featureUnsetLabel(g), groupKey = g.key))
                    visible.forEach { a -> opts.add(OptionItem(a, featureValueLabel(a), groupKey = g.key)) }
                }
                opts
            }

            // 性格：显示名走 Cobblemon 自己的词条（照市场筛选面板的写法）
            OPT_NATURE -> com.cobblemon.mod.common.api.pokemon.Natures.all().map { n ->
                val key = "cobblemon.nature.${n.name.path}"
                val t = Text.translatable(key).string
                OptionItem(n.name.toString(), if (t == key) n.displayName else t)
            }

            // 特性：默认 = 该物种 / 形态的全部可能特性（含隐藏特性，见 [legalAbilities]）；
            // ⚠ **「自由选择」开着时改成全部特性**（`Abilities.all()`，300 条上下）——
            //   这正是那个开关的作用：选出来的会是"非法特性"，Cobblemon 自己会给它标
            //   `Ability.forced`（见 [editFreePick]）。⚠ 全表要**按显示名排序**，否则按注册顺序
            //   排、中文界面下毫无规律，只能靠搜索
            OPT_ABILITY -> if (editFreePick) {
                com.cobblemon.mod.common.api.abilities.Abilities.all()
                    .map { ability ->
                        OptionItem(ability.name, Text.translatable(ability.displayName).string)
                    }
                    .sortedBy { it.label }
            } else {
                legalAbilities().map { potential ->
                    val ability = potential.template
                    OptionItem(ability.name, Text.translatable(ability.displayName).string)
                }
            }

            // 球种：**没有 displayName**，显示名走物品词条（`item.cobblemon.poke_ball`，与挂单详情同口径）；
            // 图标就是球物品自己的图标（画在行首，与其余界面的物品图标同一套）
            OPT_BALL -> com.cobblemon.mod.common.api.pokeball.PokeBalls.all().map { ball ->
                val id = ball.name.toString()
                OptionItem(
                    value = id,
                    label = Text.translatable("item.${ball.name.namespace}.${ball.name.path}").string,
                    stack = itemStackOf(id),
                    // 球名带**稀有度色**（大师球该是紫的），与物品栏里一致
                    labelText = com.shusheng.cobblemarket.util.ItemNameText.rarityColoredNameById(id)
                        ?: Text.translatable("item.${ball.name.namespace}.${ball.name.path}")
                )
            }

            // 太晶：名字按**属性色**着色（与精灵/技能的属性着色同一张表）。
            // ⚠ 太晶的 `id.path` 正好是属性名（normal / fire / …），直接喂 TypeTextColors；
            //   星晶那类查不到会落默认色，安全。**别用 `.displayName.string`** —— 转 String 样式全丢。
            OPT_TERA -> com.cobblemon.mod.common.api.types.tera.TeraTypes.toList()
                .map {
                    val label = it.displayName.string
                    OptionItem(
                        value = it.id.toString(),
                        label = label,
                        labelText = Text.literal(label)
                            .styled { s -> s.withColor(com.shusheng.cobblemarket.util.TypeTextColors.color(it.id.path)) }
                    )
                }

            // 携带物是**搜索式**的：内容随搜索词变（见 [buildCarryItems] / [applyOptionFilter]），
            // 这里不预生成列表 —— 开列表时是空的，靠搜索框找
            OPT_CARRY -> emptyList()

            // 证章：Cobblemon 的证章注册表（自带 32×32 图标与名字）。**多选** ——
            // 一只精灵能同时挂好几个，所以这张列表不关窗、点一下切一个
            OPT_MARKS -> com.cobblemon.mod.common.api.mark.Marks.all()
                .map {
                    OptionItem(
                        value = it.identifier.toString(),
                        label = it.getName().string,
                        iconTexture = it.texture
                    )
                }
                .sortedBy { it.label }

            // 技能：**默认解锁该物种的全部可学技能** —— 这只是服主完全自定义捏出来的，
            // 不该受「这只已经学过什么」限制（2026-09-20 用户明确）。
            // 名字走 MoveText.name（**按技能属性着色**，与悬停窗的技能块同一张色表）。
            // ⚠ 第一项是「无」= **清空该槽**（生成时这一槽随机补招）—— 内置选招屏没有这个动作，
            //   服主选完想撤掉就没处点了（2026-09-20 用户指出）
            // 商品物品：**搜索式**（同携带物）—— 一进来是空列表，内容由 [buildItemCandidates]
            // 跟着搜索词重建；别让它落到下面的技能分支去（那会列出一堆技能当物品候选）。
            // ⚠ 物品窗现在不走这里（见 [updateItemSearch]），留着同样是为了不落到技能分支那个兜底
            OPT_ITEM -> emptyList()

            else -> buildList {
                add(OptionItem("", Text.translatable("cobblemarket.points.store.value.none").string))
                // ⚠ **排除其它槽位已选的技能**（本槽自己的保留，否则自己都选不上）——
                //   与 Cobblemon 队伍详情一致：同一只精灵同一个技能只能占一个槽
                //   （2026-09-25 用户报：现在四个槽能全选同一个技能）。
                //   ⚠ 这与上面那条「不受**这只已学过什么**的限制」**不冲突**：那条说的是
                //     不限于它当前会的招（仍然解锁全部可学技能），这条说的是**四个槽之间**别撞车。
                val thisSlot = kind.removePrefix(OPT_SKILL).toIntOrNull()
                val takenByOthers = MoveText.slotIds(editMoves)
                    .filterIndexed { i, id -> i != thisSlot && id.isNotEmpty() }
                    .toSet()
                // ⚠ **「自由选择」开着 ⇒ 全部技能**（`Moves.all()`，900 条上下）—— 与特性同一个
                //   开关（见 [editFreePick]）；否则只有该物种 / 形态能学的那些（见 [pickedMoveBuckets]）。
                //   两条路**共用同一条流水线**（排掉其它槽已选 → 按显示名排序），别各写一遍 ——
                //   区别只在"带不带来源"：全表那份没有来源可言（筛选行那时也不显示）
                val bySource: Map<String, Set<String>> = if (editFreePick) {
                    com.cobblemon.mod.common.api.moves.Moves.all().associate { it.name to emptySet<String>() }
                } else {
                    // ⚠ 一个招属于几桶就带几桶（**桶间不去重**，用户 2026-10-13 定）⇒
                    //   按"属不属于"筛选，「火花」在「等级」和「招式机」两个筛选下都出得来
                    val merged = mutableMapOf<String, MutableSet<String>>()
                    pickedMoveBuckets().forEach { (source, ids) ->
                        ids.forEach { id -> merged.getOrPut(id) { mutableSetOf() } += source }
                    }
                    merged
                }
                addAll(
                    bySource.entries
                        .filter { it.key !in takenByOthers }
                        .mapNotNull { (id, sources) ->
                            val move = com.cobblemon.mod.common.api.moves.Moves.getByName(id)
                                ?: return@mapNotNull null
                            OptionItem(
                                id, move.displayName.string,
                                labelText = MoveText.name(id), sources = sources
                            )
                        }
                        .sortedBy { it.label }
                )
            }
        }
    }

    /**
     * 携带物候选：**搜索式**（照物品黑名单的添加对话框：ID / 中文名 / 附魔 / 招式都能搜）。
     *
     * ⚠ 不预先列背包 —— 服主要配的携带物不一定是身上那件（2026-09-20 用户明确）。
     * 值仍是**完整物品栈的 SNBT**（数据层 `heldItemSpec` 的口径）：带组件快照的候选
     * （附魔书 / 招式学习器）按快照重建，普通物品用默认栈。
     */
    private fun buildCarryItems(query: String): List<OptionItem> {
        val q = query.trim()
        val none = OptionItem("", Text.translatable("cobblemarket.points.store.value.none").string)
        // ⚠ **空搜索时也要给「无」**：否则一打开列表就是空的，连"清掉携带物"的入口都没有 ——
        //   选中过一件之后就永远回不到随机了（2026-09-27 用户实测）。
        //   只放这一项：候选要按搜索词现查，空词查出来的是整个物品表，那不是列表该干的事。
        if (q.isEmpty()) return listOf(none)
        val opts = mutableListOf(none)
        com.shusheng.cobblemarket.client.ItemCandidateResolver.resolve(q)
            .take(MAX_CARRY_CANDIDATES)
            .forEach { c ->
                val stack = carryStackOf(c) ?: return@forEach
                val snbt = encodeStackSnbt(stack) ?: return@forEach
                opts.add(
                    OptionItem(
                        value = snbt,
                        label = c.displayName,
                        stack = stack.copyWithCount(1),
                        // 物品名带**稀有度色**（附魔金、稀有品紫…）——但名字要取**候选的**，
                        // 变体候选（招式学习器 / 附魔书）在栈里是看不出是哪一个的，见 [candidateLabelText]
                        labelText = candidateLabelText(c, stack)
                    )
                )
            }
        return opts.distinctBy { it.value }
    }

    /**
     * **商品物品候选**：搜索式，与 [buildCarryItems] 走**完全同一条搜索入口**
     *（[com.shusheng.cobblemarket.client.ItemCandidateResolver.resolve]）——
     * 支持 ID / 中文名 / 附魔 / 招式，与黑名单、物品市场那些搜索框同一个口径。
     *
     * ⚠ 与携带物的差别**只在 value 的含义**：携带物存完整 SNBT，商品存 `itemId` + 组件规格两项
     *（见 `ShopItemSpec`）。这里**照旧吐 SNBT**，由 [selectOption] 选中时拆成那两项 ——
     * 免得这条搜索路径再多写一份「SNBT → 组件规格」的解析（那正是最容易漂移的地方）。
     */
    private fun buildItemCandidates(query: String): List<OptionItem> {
        val q = query.trim()
        // 空搜索词不给候选：一屏几百个物品没有意义（与携带物一致）
        if (q.isEmpty()) return emptyList()
        return com.shusheng.cobblemarket.client.ItemCandidateResolver.resolve(q)
            .take(MAX_ITEM_CANDIDATES)
            .mapNotNull { c ->
                val stack = carryStackOf(c) ?: return@mapNotNull null
                val snbt = encodeStackSnbt(stack) ?: return@mapNotNull null
                OptionItem(
                    value = snbt,
                    label = c.displayName,
                    stack = stack.copyWithCount(1),
                    // 物品名带**稀有度色**（附魔金、稀有品紫…），与物品栏里一致
                    // 同上：名字取**候选的**（变体候选在栈里看不出是哪一个），见 [candidateLabelText]
                    labelText = candidateLabelText(c, stack)
                )
            }
            .distinctBy { it.value }
    }

    /**
     * 候选行的显示名 —— **用候选自带的 [com.shusheng.cobblemarket.client.ItemCandidate.displayName]，
     * 不是重建出来的栈的名字**。
     *
     * ⚠ 两者在**变体候选**上不一样：栈里只叫「招式学习器」「附魔书」，变体后缀（`· 打鼾` / `· 锋利 V`）
     *   只写在 displayName 里。拿栈名去画 ⇒ 一屏候选全都同名，玩家分不出选的是哪个
     *   （2026-09-26 用户实测：搜「打鼾」出来一排「招式学习器」、搜「锋利」一排「附魔书」）。
     *   黑名单 / 求购单 / 价格限制三处的候选行一直是拿 `displayName` 显示的，这里当初漏了这条。
     *
     * 名字与栈名**一致**时才走 [ItemNameText.rarityColoredName]（那种情况能顺带带上自定义名的样式）；
     * 不一致就用候选名 + 栈的**稀有度色**（颜色口径仍与物品栏悬停一致）。
     */
    private fun candidateLabelText(
        c: com.shusheng.cobblemarket.client.ItemCandidate, stack: ItemStack
    ): Text {
        val stackName = ItemNameText.rarityColoredName(stack)
        if (stackName != null && stackName.string == c.displayName) return stackName
        return Text.literal(c.displayName).styled { it.withColor(ItemNameText.rarityColor(stack)) }
    }

    /** 候选 → 物品栈（带组件快照的按快照重建；解析不出来返回 null，这一条就跳过） */
    private fun carryStackOf(c: com.shusheng.cobblemarket.client.ItemCandidate): ItemStack? = runCatching {
        if (c.componentsSpec == null) {
            Identifier.tryParse(c.itemId)
                ?.let { Registries.ITEM.getOrEmpty(it).orElse(null) }
                ?.let { ItemStack(it) }
        } else {
            ItemStack.fromNbtOrEmpty(
                client?.world?.registryManager ?: return null,
                NbtCompound().apply {
                    putString("id", c.itemId)
                    putInt("count", 1)
                    put("components", c.componentsSpec)
                }
            )
        }
    }.getOrNull()

    /** 资源 id → 单件物品栈（解析不出来返回 null，行首就不画图标） */
    private fun itemStackOf(itemId: String): ItemStack? = runCatching {
        Identifier.tryParse(itemId)
            ?.let { Registries.ITEM.getOrEmpty(it).orElse(null) }
            ?.let { ItemStack(it) }
    }.getOrNull()

    /** 物品栈 → SNBT 串（客户端只能编码**同步过来的**组件，服务端收到后还会自己重建一遍） */
    private fun encodeStackSnbt(stack: ItemStack): String? = runCatching {
        stack.encode(client?.world?.registryManager ?: return null).toString()
    }.getOrNull()

    // ── 窗口：渲染 ──

    /** 画窗口背景与静态文字（**作为第一个 Drawable 添加**，才画在原版控件之下） */
    private fun renderEditDialogBackground(
        context: DrawContext, mouseX: Int, mouseY: Int, delta: Float
    ) {
        val x = editDialogX()
        val y = editDialogY()

        // 遮罩：压暗下层（不压的话下面那排格子会跟弹窗抢注意力）
        drawScreenDimMask(context, width, height)
        // ⚠ 高度必须用 [editDialogHeight]（钳制后的实际值），**不能传设计常量** ——
        //   逻辑屏不足 EDIT_DIALOG_H + 8 时窗口会矮一截，背景照常量画就会往下冒出窗口底边（2026-09-20 修）
        // 展开动画期间画**插值矩形**（窗口从格子那里长大），播完就是最终矩形
        val animRect = dialogAnimRect()
        drawNineSlice(
            context, DIALOG_BACKGROUND_TEXTURE,
            animRect[0], animRect[1], animRect[2], animRect[3], 0, DIALOG_BACKGROUND_TEX_H
        )
        // 动画期间**只画背景**：内容挤在小矩形里会糊成一团 ⇒ 等内容段淡入（见 [dialogAnimContentAlpha]）。
        // ⚠ shader 必须 try/finally 恢复（本段里有多处分支，漏了会让整个界面串色）
        val contentAlpha = dialogAnimContentAlpha()
        if (contentAlpha <= 0f) return
        context.setShaderColor(contentAlpha, contentAlpha, contentAlpha, contentAlpha)
        try {

        // 标题：快照弹窗 / 展开的选项列表各自换标题（同一个位置只有一个标题，不叠字）
        val listKind = optionListKind
        val title = if (snapshotDialog) {
            Text.translatable("cobblemarket.points.store.snapshot_button")
        } else if (listKind != null) {
            Text.translatable(
                "cobblemarket.points.store.pick",
                Text.translatable(optionListShortKey(listKind)).string
            )
        } else {
            // 精灵 / 物品 × 添加 / 编辑，四种标题分开（物品格子点开时写「添加精灵」就驴唇不对马嘴了）。
            // ⚠ 按 [editEditingSlot] 判，**别按 `editSpecBase`** —— 快照回填后它也会非 null，
            //   那时仍是在往空格子里「添加」，标题不该变成「编辑」（2026-09-25 用户报过同类误判）
            val pokemon = isPokemonSlot()
            Text.translatable(
                when {
                    editEditingSlot && pokemon -> "cobblemarket.points.store.edit_title"
                    editEditingSlot -> "cobblemarket.points.store.edit_item_title"
                    pokemon -> "cobblemarket.points.store.add_title"
                    else -> "cobblemarket.points.store.add_item_title"
                }
            )
        }
        context.drawCenteredTextWithShadow(
            textRenderer, title.copy().formatted(Formatting.GOLD), x + editDialogW() / 2, y + 10, 0xFFFFFF
        )

        // 提交失败提示：**画在弹窗正下方**（界面之外、紧贴界面）——
        //   原先在标题那一行的右端（标题居中、右边空着一大片），缩在标题边上像标题的一部分，很奇怪
        //  （2026-09-26 用户报；与购买窗那条失败提示的位置观感统一）。
        // ⚠ 必须排**列表展开之前、快照弹窗之前**（那两处都会 `return` 掉后面的绘制）——
        //   排在它们后面就等于**只在添加窗下才显示**（2026-09-25 修快照提示时踩到）
        // ⚠ 底边用 [editDialogHeight]（**钳制后**的实际高度），别拿设计常量：屏幕矮时弹窗会矮一截
        editHintMsg?.let { hint ->
            if (System.currentTimeMillis() < editHintUntil) {
                val hintY = (editDialogY() + editDialogHeight() + 4).coerceAtMost(height - 12)
                context.drawCenteredTextWithShadow(
                    textRenderer, hint, editDialogX() + editDialogW() / 2, hintY, 0xFF6666
                )
            }
        }

        // 快照弹窗**叠在添加窗之上**（与展开的选项列表互斥，两者都是"盖住下面的窗口"）。
        // ⚠ **必须排在提示之后**：它 `return` 掉就不再往下画了 ——
        //   排前面的话，快照弹窗里点「保存当前配置」失败的提示永远看不见（2026-09-25 用户报）
        if (snapshotDialog) {
            renderSnapshotDialog(context, mouseX, mouseY)
            return
        }

        if (listKind != null) {
            // 技能列表顶上那排来源筛选按钮：**排在行前面**（行从它下面起，见 [optionListTop]）
            renderMoveFilterRow(context, mouseX, mouseY)
            renderOptionRows(context, mouseX, mouseY)
            renderOptionListDecor(context, x, y)
            // ⚠ **列表展开时右侧照样露出大图**（本来是变体列表独有，2026-09-27 用户拍板推广到
            //   **所有**列表：形态 / 太晶 / 性格 / 特性…都是"看着选"，右边一直有大图才连贯，
            //   不会有"又打开了一个新界面"的感觉）。列表只占左半边（见 [optionListW]），
            //   右边正好是编辑窗原本预览的位置（`editPreviewX/Y`，不用重算）
            if (isPokemonSlot()) renderEditPreviewSlot(context, x, y, delta) else renderEditItemPreviewSlot(context)
            return
        }

        // 右侧那张大图：精灵是 3D 模型（可浮动、点一下会叫），物品是 16×16 图标放大 ——
        // **位置同一套坐标**（用户 2026-09-26：「右侧是物品大图，和添加精灵界面图标位置一样」）
        if (isPokemonSlot()) renderEditPreviewSlot(context, x, y, delta) else renderEditItemPreviewSlot(context)

        // 输入框前面的**前缀标签**（用户 2026-09-20 要的「数字正好接上」）——
        // ⚠ 它们跟字段网格一样在**滚动区**里：位置与显隐都得跟着那一行走，写死坐标一滚就露馅
        // ⚠ 前缀宽度必须与 [editFieldInsetX] 让出的宽度**同源**，两边各算一份必然错位
        if (isPokemonSlot()) {
            for ((key, labelKey) in listOf(
                KEY_LEVEL to LEVEL_LABEL_KEY,
                KEY_FRIENDSHIP to "cobblemarket.points.store.field.friendship_label",
                KEY_DMAX to "cobblemarket.points.store.field.dmax_label",
                KEY_OT to "cobblemarket.points.store.field.ot_label"
            )) {
                // ⚠ 格子太窄时前缀宽是 0（输入框吃满整格）⇒ 这里就不能画，否则标签会压到输入框上。
                //   判据**直接问 [editFieldInsetX]**，别在这儿各写一份特例（原来只判了亲密度一个）
                if (editFieldInsetX(key) <= 0) continue
                val row = editRowOf(key)
                if (editRowVisible(row)) {
                    context.drawTextWithShadow(
                        textRenderer, Text.translatable(labelKey),
                        editColX(editColOf(key)), editRowY(row) + (EDIT_ROW_H - 8) / 2, 0xFFFFFF
                    )
                }
            }
        }

        // 价格 / 库存的标签（原版输入框自己画不了标签，位置与它对同一行）
        // 领取店没有价格框：只在最左侧画「配额」
        val labelY = editPriceRowY() + (EDIT_ROW_H - 8) / 2
        if (!isClaimShop) {
            context.drawTextWithShadow(
                textRenderer, Text.translatable("cobblemarket.points.store.field.price"),
                editPriceGroupX(), labelY, 0xFFFFFF
            )
            context.drawTextWithShadow(
                textRenderer, Text.translatable("cobblemarket.points.store.field.stock"),
                editPriceGroupX() + editPriceColW() + EDIT_COL_GAP, labelY, 0xFFFFFF
            )
            // 第三列：格子冷却（只有自定义普通购买店有）
            if (slotCooldownShown()) {
                context.drawTextWithShadow(
                    textRenderer, slotCooldownLabel(),
                    editPriceGroupX() + (editPriceColW() + EDIT_COL_GAP) * 2, labelY, 0xFFFFFF
                )
            }
        } else {
            context.drawTextWithShadow(
                textRenderer, Text.translatable("cobblemarket.points.store.field.claim_quota"),
                editPriceGroupX(), labelY, 0xFFFFFF
            )
        }
        } finally {
            context.setShaderColor(1f, 1f, 1f, 1f)
        }

        // ⚠ 悬停小窗**最后画**（浮在所有东西上面）—— 编辑窗里只有「变体」和「证章」两个按钮做
        renderEditButtonTooltip(context, mouseX, mouseY)
    }

    /**
     * 编辑窗里**两个按钮**的悬停小窗（用户 2026-09-27 定：只做这两个，不做全界面）：
     * 「变体」与「证章」—— 这两个按钮**装不下自己要表达的东西**：变体多维度时标签会被截断
     * （`a/b/c` 挤在 82px 里），证章是一排图标、本来就塞不进一个格子。
     *
     * ⚠ 外观照购买窗那枚 [renderMarksTooltip]：**我们自己的背景**（`dialog_background.png`）+
     *   同一套落点（鼠标右移 12、优先上方 4px、钳进屏幕）+ 抬 Z 400。
     *   **别退回原版的 `setTooltip`** —— 那是黑底原版样式，跟界面里其它悬停窗不是一套
     *   （用户 2026-09-27 指出：购买窗的证章悬停用的是我们自己的背景，两边必须一致）。
     */
    private fun renderEditButtonTooltip(context: DrawContext, mouseX: Int, mouseY: Int) {
        // ── 「变体」：每个维度一行「维度名：当前值」——
        //    按钮标签只放得下"值/值/值"，这里才能看出**每个值分别属于哪个维度**
        if (editButtons[KEY_FEATURE]?.isHovered() == true) {
            val groups = featureGroupsOf(editSpecies)
            if (groups.isEmpty()) return
            val pad = MARK_TIP_PAD
            val lineH = 11
            // ⚠ **与列表同一口径**：已经在「形态」里的 aspect 不算变体 —— 它常常同时是某个
            //   feature 的候选（`partner-cap` 既是皮卡丘的搭档帽形态、又是 `league_cap` 的取值），
            //   不过滤的话悬停窗会把形态按钮里选的也算进来：用户只选了饰品，这里却多出一行
            //   「帽子：partner-cap」（2026-09-27 用户实测指出）
            val formAspects = SpeciesText.selectableForms(editSpecies).flatMap { it.aspects }.toSet()
            val lines = groups.map { g ->
                val picked = g.aspects.filter { it in editAspects && it !in formAspects }
                val value = if (picked.isEmpty()) featureUnsetLabel(g)
                else picked.joinToString("/") { featureValueLabel(it) }
                Text.translatable(
                    "cobblemarket.points.store.feature_tip", featureGroupLabel(g.key), value
                )
            }
            val w = lines.maxOf { textRenderer.getWidth(it) } + pad * 2
            val h = lines.size * lineH + pad * 2
            withTooltipLayer(context, mouseX, mouseY, w, h) { tx, ty ->
                lines.forEachIndexed { i, line ->
                    context.drawTextWithShadow(textRenderer, line, tx + pad, ty + pad + i * lineH, 0xFFFFFF)
                }
            }
            return
        }
        // ── 「证章」：把**全部**已选证章的图标排出来（同购买窗那枚的口径）——
        //    ⚠ 数据源是 `editMarks` + `editAlpha`（编辑中的草稿），**不是** `spec.marks`
        if (editButtons[KEY_MARKS]?.isHovered() == true) {
            val textures = effectiveMarksOf(editMarks, editAlpha).mapNotNull { id ->
                Identifier.tryParse(id)
                    ?.let { com.cobblemon.mod.common.api.mark.Marks.getByIdentifier(it) }?.texture
            }
            if (textures.isEmpty()) return
            val perRow = 10
            val rows = (textures.size + perRow - 1) / perRow
            val pad = MARK_TIP_PAD
            val w = minOf(textures.size, perRow) * MARK_CELL + pad * 2
            val h = rows * MARK_CELL + pad * 2
            withTooltipLayer(context, mouseX, mouseY, w, h) { tx, ty ->
                textures.forEachIndexed { idx, texture ->
                    com.cobblemon.mod.common.api.gui.blitk(
                        matrixStack = context.matrices, texture = texture,
                        x = tx + pad + (idx % perRow) * MARK_CELL,
                        y = ty + pad + (idx / perRow) * MARK_CELL,
                        width = 8, height = 8
                    )
                }
            }
        }
    }

    /**
     * 悬停小窗的**外框**：落点 + 背景 + 抬 Z —— 内容由 [draw] 自己画。
     *
     * ⚠ 落点照 [renderMarksTooltip] 与 `HistoryScreen.renderTooltip`，**全项目同一套**，
     *   别另发明（用户 2026-09-25 明确要求所有悬停窗长在同一个位置）。
     */
    private inline fun withTooltipLayer(
        context: DrawContext, mouseX: Int, mouseY: Int, w: Int, h: Int,
        draw: (Int, Int) -> Unit
    ) {
        val tx = minOf(mouseX + 12, width - w - 12).coerceAtLeast(4)
        val tyAbove = mouseY - h - 4
        val ty = if (tyAbove <= 0) minOf(mouseY + 12, height - h) else tyAbove
        // ⚠ **必须抬 Z**：不抬的话下层那些字段文字会从悬停窗上透出来（同 renderMarksTooltip）
        context.matrices.push()
        context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, tx, ty, w, h, 0, DIALOG_BACKGROUND_TEX_H)
        draw(tx, ty)
        context.matrices.pop()
    }

    /** 物种预览槽：槽位贴图 + 精灵模型 + 候选计数（照黑名单的添加精灵弹窗） */
    private fun renderEditPreviewSlot(context: DrawContext, x: Int, y: Int, delta: Float) {
        // 预览区：右侧 3 格 × 上 2 行，槽位居中在里头、精灵再在槽里居中（比原来那个 24px 的小图标大得多）
        val slotSize = editPreviewSize()
        val slotX = editPreviewSlotX()
        val slotY = editPreviewSlotY()
        // ⚠ 用**大图版**槽位贴图：本弹窗的槽比列表图标大得多，86×86 那张放大会糊（见 drawPokemonSlotBig）
        drawPokemonSlotBig(context, slotX, slotY, slotSize)

        val renderable = editPreview
        if (renderable != null) {
            val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
            try {
                PokemonIconRenderer.draw(
                    context = context,
                    renderable = renderable,
                    state = editPreviewState.stateFor(renderable),
                    x = slotX, y = slotY, size = slotSize,
                    delta = delta,
                    animate = useFloat,
                    // 大图专用裁剪框：允许高出 / 横出槽位（见 editPreviewClip）
                    clip = editPreviewClip(slotY, slotSize)
                )
                // 开窗 / 换物种后自动叫一声：**必须在渲染之后** —— addFirstAnimation 依赖
                // currentModel，那是 draw 里才建好的（照其他弹窗的写法）
                if (editCryAt != 0L && System.currentTimeMillis() >= editCryAt) {
                    editCryAt = 0L
                    IconCryClick.play(editPreviewState.stateFor(renderable), renderable)
                }
            } catch (_: Exception) {
            }
        }

        // 候选计数（1/3）：居中于物种输入框与预览槽之间那段空隙
        // ⚠⚠ **叠层打开时不能画**：本函数在**列表展开时也会被调**（右侧要留着大图"看着选"，
        //   见 [renderEditDialogBackground] 那个 `if (listKind != null)` 分支），若照画，这个计数
        //   就会**浮在选项列表之上** —— 2026-09-30 用户实测：「点开技能、性格选择弹窗，1/2 都会透上来」。
        //   快照弹窗同理（那时走的是下面那条分支，但这条判据一样管）。
        //   ⚠ 它是**自绘文字、不是 MC 控件** ⇒ 吃不到 [updateEditControlsVisibility] 那条
        //   "不在白名单里一律隐藏"的兜底（兜底只遍历 `children()`，自绘的一律漏）⇒ **必须在这里自己判**。
        //   判据用同一个 [editDialogCovered]，别另写一套（两套判据必然分叉）
        if (editSpeciesCandidates.size > 1 && !editDialogCovered()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal("${editSpeciesIndex + 1}/${editSpeciesCandidates.size}").formatted(Formatting.GRAY),
                // 预览槽挪去标题下方了，候选计数改贴在箭头右侧那段留白里（居中，右端与字段按钮对齐）
                editSpeciesRightX() - SPECIES_COUNT_W / 2, y + EDIT_SPECIES_Y + 3, 0xFFFFFF
            )
        }
    }

    /**
     * 物品预览槽：**与精灵窗同一套坐标**（[editPreviewSlotX] / [editPreviewSlotY] / [editPreviewSize]）
     * —— 用户 2026-09-26 要的就是「右侧是物品大图，和添加精灵界面图标位置一样」。
     *
     * 图标来自**当前草稿**（`editItemId` + 组件快照），走 [rebuildItemStack] 那条重建路
     * （与商城格子、购买窗同一套口径），16×16 缩放到占满槽位 —— 附魔光效、改名都看得清。
     *
     * ⚠ 物品**没有**叫声 / 浮动那一套（它是 2D 贴图，不是 3D 模型）——
     *   所以这里不登记动画起点、也不播 [IconCryClick]，与精灵窗的差别就在这。
     */
    private fun renderEditItemPreviewSlot(context: DrawContext) {
        val slotSize = editPreviewSize()
        val slotX = editPreviewSlotX()
        val slotY = editPreviewSlotY()
        // ⚠ **不画 [drawPokemonSlot] 那层占位背景**（用户 2026-09-26 实测指出）：
        //   那是给精灵站的底座（影子 + 地面），物品是 16×16 贴图放大、自己就是个方块，
        //   底下垫一层只显得脏。精灵窗照旧（那边确实需要）。
        // 还没选物品 = 这一块**什么都不画**（连空槽都没有）；栈是选物品时缓存好的（[setEditItem]），
        // 别在这里现重建，那是每帧一次的反序列化
        val stack = editItemPreviewStack?.takeIf { !it.isEmpty } ?: return
        // 大图**左侧**那列说明文字先画（两者不重叠，先后无所谓，按从左到右的顺序读着顺）
        renderItemInfoLines(context, stack, slotX, slotY, slotSize)
        val scale = slotSize / 16f
        context.matrices.push()
        context.matrices.translate((slotX + slotSize / 2f).toDouble(), (slotY + slotSize / 2f).toDouble(), 0.0)
        context.matrices.scale(scale, scale, 1f)
        // 同上：统一走 [drawItemWithBar]，耐久条跟着放大
        drawItemWithBar(context, stack, -8, -8)
        context.matrices.pop()
    }

    /**
     * 物品大图**左侧**那列词条（用户 2026-09-26 要的）。
     *
     * ⚠ **添加物品窗与购买窗共用这一个**（用户点名：这两个窗口是**并行关系**，元素必须一致）——
     *   两边布局本来就是同一套坐标（见 [renderEditItemPreviewSlot] / [renderBuyItemFields]），
     *   而且两个窗口不会同时开着，所以 [itemInfoCacheLines] 那份缓存也共用一份就够。
     *
     * 内容与别处**同一套**：[ItemComponentsDisplay.itemTooltip] —— 物品名 + 附魔 / 招式 / 自定义名 / 耐久，
     * 按住 **Shift / Ctrl** 展开更多（走 [ItemComponentsDisplay.tooltipTypeForHover]，物品市场、拍卖创建
     * 那几个界面都是这个口径）。**差别只在位置**：别处这套词条是跟着鼠标弹出来的，这里常驻画在大图左边。
     *
     * ⚠ 行数按大图那一列的高度**截断** —— Shift 展开后能有十几行，漫到下面的价格行上就没法看了。
     */
    private fun renderItemInfoLines(
        context: DrawContext, stack: ItemStack, slotX: Int, slotY: Int, slotSize: Int
    ) {
        val lines = itemInfoLines(stack)
        if (lines.isEmpty()) return
        // 可用区间 = 弹窗内边距 → 大图左缘（再让开 [EDIT_ITEM_INFO_GAP]）。
        // ⚠ 别从 [editSpeciesRightX] 起：那里到大图只剩 **73px**（一列 82px 的宽度），
        //   英文物品名一长就截得只剩几个字母；从内边距起有 340 多像素。
        val left = editDialogX() + EDIT_PAD
        val maxWidth = slotX - EDIT_ITEM_INFO_GAP - left
        if (maxWidth <= 0) return
        // 超出大图高度时截断，**末行注明还有几行** —— 否则 Shift 展开后被截掉多少玩家不知道
        //（同一套口径：词条见 [com.shusheng.cobblemarket.client.clampTooltipRows]）
        val maxRows = (slotSize / EDIT_ITEM_INFO_LINE_H).coerceAtLeast(1)
        val shown = if (lines.size > maxRows) {
            lines.take(maxRows - 1) +
                (Text.translatable("cobblemarket.gui.tooltip_more", lines.size - maxRows + 1) to 0xAAAAAA)
        } else lines
        // 超宽的先截到列宽内 —— 走保留分段样式的版本，别把物品名的稀有度色洗成白的
        val texts = shown.map { (line, color) ->
            (if (textRenderer.getWidth(line) > maxWidth) TextUtil.truncateStyled(line, maxWidth) else line) to color
        }
        // **整列在那条空档里居中**：按**最宽那一行**算列宽、整列居中，各行仍左对齐
        //（逐行各自居中会让长短行参差，反而不整齐）。
        // ⚠ 原先这里是左对齐贴边，**购买窗与添加窗都改过**（用户 2026-09-26 先报购买窗、再要求
        //   添加窗一致）—— 所以不再留「居不居中」的开关，两个窗口共用这一个行为。
        val colX = left + (maxWidth - texts.maxOf { textRenderer.getWidth(it.first) }) / 2
        // 垂直方向**与大图居中对齐**（整列文字居中在大图那一格的高度里）
        var y = slotY + (slotSize - shown.size * EDIT_ITEM_INFO_LINE_H) / 2
        texts.forEach { (text, color) ->
            context.drawTextWithShadow(textRenderer, text, colX, y, color)
            y += EDIT_ITEM_INFO_LINE_H
        }
    }

    /**
     * 词条行的构建 + 缓存 —— **必须缓存**：构建要遍历物品的组件（附魔 / 招式 / 自定义名），
     * 而渲染是每帧一次（照 `AuctionCreateScreen.renderItemTooltip` 那个缓存的写法）。
     *
     * ⚠ 缓存键是「栈 **+ 展开档位**」两个都要比：只比栈的话，按住 Shift 永远不会展开
     *   （档位变了、栈没变），松开也一样。
     */
    private fun itemInfoLines(stack: ItemStack): List<Pair<Text, Int>> {
        val type = if (ItemComponentsDisplay.hoverExpanded()) ItemComponentsDisplay.tooltipTypeForHover()
        else TooltipType.BASIC
        if (stack === itemInfoCacheStack && type == itemInfoCacheType) return itemInfoCacheLines
        itemInfoCacheStack = stack
        itemInfoCacheType = type
        itemInfoCacheLines = ItemComponentsDisplay.itemTooltip(stack, client?.player, type).map { it to 0xFFFFFF }
        return itemInfoCacheLines
    }

    /**
     * 技能列表顶上的**来源筛选按钮**（自绘 —— 与选项行同一套：真控件会被下面的自绘列表截胡）。
     *
     * 观感与选项行一致（同一张 `row_background.png`：0 普通 / 1 悬停 / 2 选中），只是矮一档
     * （[OPTION_FILTER_H]，九宫格最小要 12px）—— 它表达的是一排"档位"，不是可选条目。
     */
    private fun renderMoveFilterRow(context: DrawContext, mouseX: Int, mouseY: Int) {
        if (!showMoveFilterRow()) return
        moveFilterChips().forEach { (source, r) ->
            val state = when {
                source == moveSourceFilter -> 2
                r.contains(mouseX, mouseY) -> 1
                else -> 0
            }
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, r.x, r.y, r.w, r.h, state, ROW_BACKGROUND_TEX_H)
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal(moveSourceLabel(source)),
                r.x + r.w / 2, r.y + (r.h - 8) / 2, 0xFFFFFF
            )
        }
    }

    /**
     * 展开列表的行 —— **自绘**（不用 NineSliceButton）：行首要画物品图标（球种、携带物），
     * 而按钮只支持贴图 id 的图标，球是物品、得走物品渲染。
     */
    private fun renderOptionRows(context: DrawContext, mouseX: Int, mouseY: Int) {
        val rows = optionListRows()
        // 搜索式列表（携带物）还没输入 / 没命中时：给一行提示，别留一片空白看着像坏了
        if (optionListItems.isEmpty()) {
            val r = optionRowRect(0)
            // ⚠ **技能列表要换一句**：那边空了是"筛出来是空的"（某只精灵一档教学招都没有、
            //   或搜索词没命中），提示"输入物品名搜索"会把人往物品上引 —— 完全对不上
            val key = if (optionListKind?.startsWith(OPT_SKILL) == true) {
                "cobblemarket.points.store.no_move_match"
            } else "cobblemarket.points.store.search_item_hint"
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(key).formatted(Formatting.GRAY),
                r.x + r.w / 2, r.y + (OPTION_ROW_H - 8) / 2, 0xFFFFFF
            )
            return
        }
        val hovered = hoveredOptionRow(mouseX.toDouble(), mouseY.toDouble())
        optionListItems.drop(optionListScroll).take(rows).forEachIndexed { i, opt ->
            val r = optionRowRect(i)
            // 分节标题（「变体」用）：**只画一行字** —— 不画行背景、不参与悬停与勾选，
            // 让它一眼看上去就是"标题"而不是"一个能点的选项"
            if (opt.isHeader) {
                context.drawTextWithShadow(
                    textRenderer,
                    Text.literal(opt.label).formatted(Formatting.YELLOW),
                    r.x + 4, r.y + (OPTION_ROW_H - 8) / 2, 0xFFFFFF
                )
                return@forEachIndexed
            }
            // 行背景：与其余界面的列表行**同一张** `row_background.png`。
            // ⚠ 三段是 **0 普通 / 1 悬停 / 2 选中**，别拿 1 当选中用（项目里其他列表的 `1` 都是悬停）。
            // ⚠ 也别拿弹窗背景凑合 —— 那是给大窗口的，装饰条压到 16px 高的行上会糊成一条条红线。
            val state = when {
                isOptionSelected(opt) -> 2
                i == hovered -> 1
                else -> 0
            }
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, r.x, r.y, r.w, r.h, state, ROW_BACKGROUND_TEX_H)
            // 图标垂直居中（行高比图标高，留出上下余量，别顶在行边缘上）
            val iconY = r.y + (OPTION_ROW_H - OPTION_ROW_ICON_W) / 2
            val stack = opt.stack
            if (stack != null) {
                // 候选行也是物品图标 —— 同样走 [drawItemWithBar]（缺了它，搜出来的快坏的镐子
                // 看着跟满耐久一样，正是「看不到耐久就可能高价买到快报废的工具」那个坑）
                drawItemWithBar(context, stack, r.x + 1, iconY)
            } else opt.iconTexture?.let { tex ->
                // 贴图图标（证章）：原图 32×32，缩到行内高 —— 缩放走 matrices，别直接传目标尺寸
                context.matrices.push()
                context.matrices.translate((r.x + 1).toDouble(), iconY.toDouble(), 0.0)
                val s = OPTION_ROW_ICON_W / 32f
                context.matrices.scale(s, s, 1f)
                context.drawTexture(tex, 0, 0, 0f, 0f, 32, 32, 32, 32)
                context.matrices.pop()
            }
            // 选中态加「● 」前缀（项目统一的选中写法）；**不强制白色** —— 技能名要保持属性色
            var text = if (state == 2) Text.literal("● ").append(opt.labelText) else opt.labelText
            val maxW = r.w - 8
            // 超长才截断（极少发生，技能名一般十来个字，行宽 240 放得下）。
            // ⚠ 用 `truncateStyled` 而不是 `truncateString(x.string, …)` —— 后者会把 Text 压成纯文本、
            //   丢掉各段的样式（属性色、货币字形都会没）。原先的注释自己都写了"截断后会变纯文本"，
            //   2026-09-28 统一改掉（同物品市场那个方块 bug 的根因）
            if (textRenderer.getWidth(text) > maxW) text = TextUtil.truncateStyled(text, maxW)
            // 行很长，文字**居中**才好看；但**不能**用 drawCenteredTextWithShadow（它会让行首汉字
            // 抬高一点），所以自己算 x 再 drawText（混排文字的老坑，见项目约定）
            val drawX = r.x + (r.w - textRenderer.getWidth(text)) / 2
            context.drawTextWithShadow(textRenderer, text, drawX, r.y + (OPTION_ROW_H - 8) / 2, 0xFFFFFF)
        }
    }

    /**
     * 展开列表的滚动条（条目装得下就不画）。
     *
     * ⚠ 走共用的 [ScrollBar]（**可拖动** —— 2026-09-30 用户报「滚动条点不动」后抽出来的）：
     *   它的几何由 [ScrollBar.draw] 记下来供拖动换算，**别在这儿自己 fill**（自己画的条拖不动）。
     * ⚠ 位置贴着**列表自己的右缘**（`optionListW()`）—— 原来按整窗右缘算，变体列表只占左半边时
     *   滚动条会孤零零飘在右半边的预览区里（同 [optionRowRect] / 搜索框，三处共用一个宽度）
     */
    private fun renderOptionListDecor(context: DrawContext, x: Int, y: Int) {
        val rows = optionListRows()
        ScrollBar.shared.draw(
            context,
            editDialogX() + EDIT_PAD + optionListW() + 3,
            y + optionListTop(), rows * OPTION_ROW_STEP,
            optionListItems.size, rows, optionListScroll
        )
    }

    // ── 窗口：按钮文案 ──

    private fun optionListShortKey(kind: String): String =
        if (kind.startsWith(OPT_SKILL)) "cobblemarket.points.store.short.skill"
        else "cobblemarket.points.store.short.$kind"

    private fun valueText(key: String): String = Text.translatable("cobblemarket.points.store.value.$key").string

    /** 值 → 显示名（从该字段的候选表里查；查不到就显示原值，绝不空着） */
    private fun optionLabelOf(kind: String, value: String): String {
        if (value.isEmpty()) return valueText("random")
        return buildOptionItems(kind).firstOrNull { it.value == value }?.label ?: value
    }

    /**
     * 刷新所有字段按钮的文案。
     *
     * ⚠ 标签与冒号**写进词条**（`field.xxx` = 「闪光：%s」），不在代码里拼 ": " ——
     * 中英标点不同（项目约定，见 CLAUDE.md 的界面文案约定）。
     */
    private fun refreshEditLabels() {
        // 三列下按钮很窄，值（物品名 / 技能名）可能很长 —— 统一截断，别让它溢出按钮外。
        // ⚠⚠ **必须把标签前缀的宽也扣掉**：原来只按 `width - 8` 截「值」，总宽成了
        //   「前缀 + (width-8)」⇒ 超出按钮；而文字是**居中**画的 ⇒ 超出部分**向两侧**溢出
        //   （左侧尤其明显，2026-09-27 用户实测：中文直接溢出左边界、英文右侧省略号）
        fun label(key: String, value: String, width: Int = editColW()): Text {
            val head = Text.translatable("cobblemarket.points.store.field.$key", "").string
            val room = (width - 8 - textRenderer.getWidth(head)).coerceAtLeast(12)
            return Text.translatable(
                "cobblemarket.points.store.field.$key",
                TextUtil.truncateString(value, room)
            )
        }

        /**
         * **长英文标签**的三个字段（可交易 / 携带物 / 携带可见）走这条：
         * 按钮用 `_short` 词条（`Trade:` / `Item:` / `Shown:`），**完整文案挂悬停** ——
         * 英文原标签（`Tradeable: Yes` ≈84px、`Held shown: Yes` ≈96px）在 82px 的格子里放不下，
         * 截断后只剩 `Tradeable: Y...` 那种谁也看不懂的样子（2026-09-27 用户实测）。
         * ⚠ 中文两版词条**内容相同**（中文本来就短）—— 统一走这条只是为了中英一套代码。
         */
        fun shortField(key: String, value: String) {
            val btn = editButtons[key] ?: return
            val head = Text.translatable("cobblemarket.points.store.field.${key}_short", "").string
            val room = (editColW() - 8 - textRenderer.getWidth(head)).coerceAtLeast(12)
            btn.setMessage(
                Text.translatable(
                    "cobblemarket.points.store.field.${key}_short",
                    TextUtil.truncateString(value, room)
                )
            )
            btn.setTooltip(
                net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("cobblemarket.points.store.field.$key", value)
                )
            )
        }

        /**
         * 长值字段（形态 / 特性）：**选中后丢掉前缀**，整格留给值 —— 前缀白吃约 50px，
         * 而这两个字段的值天然比别的长（`Ability: Lightning Rod` 光值就 127px，格子才 82px）。
         * 完整文案（带前缀）挂到悬停上；⚠ 没选时仍走 [label]（光一个「默认」看不出是哪个字段）。
         */
        fun longValueButton(key: String, selected: Boolean, value: String) {
            val btn = editButtons[key] ?: return
            if (!selected) {
                btn.setMessage(label(key, value))
                btn.setTooltip(null)
                return
            }
            btn.setMessage(Text.literal(TextUtil.truncateString(value, editColW() - 8)))
            btn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(label(key, value, editColW() * 3)))
        }

        refreshShinyButton()
        longValueButton(KEY_FORM, pickedFormAspects().isNotEmpty(), formLabel())
        refreshGenderButton()
        editButtons[KEY_NATURE]?.setMessage(label("nature", natureLabel()))
        longValueButton(KEY_ABILITY, editAbility.isNotEmpty(), abilityLabel())
        editButtons[KEY_BALL]?.setMessage(label("ball", ballLabel()))
        editButtons[KEY_SIZE]?.setMessage(label("size", sizeLabel()))
        refreshAlphaButton()
        // ⚠ 太晶的值要**带属性色**：不能走 label() 那条只收 String 的通道（一转 String 颜色就没了），
        //   直接拼 Text —— 与选项列表、悬停窗的技能块同一张色表（2026-09-20 用户实测：按钮上还是白字）
        // ⚠⚠ 但**同样要截断**：这条以前连截断都没有 ⇒ 英文「Tera: Fighting」≈84px 就溢出了按钮
        //   （2026-09-27 用户让顺手查，实测成立）。用 truncateStyled 保住属性色，
        //   前缀宽照 label() 的口径一起扣（文字是居中的，不扣就往两侧溢出）
        editButtons[KEY_TERA]?.let { btn ->
            val head = Text.translatable("cobblemarket.points.store.field.tera", "").string
            val room = (editColW() - 8 - textRenderer.getWidth(head)).coerceAtLeast(12)
            btn.setMessage(
                Text.translatable(
                    "cobblemarket.points.store.field.tera",
                    TextUtil.truncateStyled(teraText(), room)
                )
            )
        }
        editButtons[KEY_GMAX]?.setMessage(label("gmax", yesNoLabel(editGmax)))
        // 这个物种没有超极巨化形态 → 同性别按钮：**整个按钮置灰**（`dimmed` 盖黑遮罩，
        // 就是入口界面喵喵银行关闭时那个做法；`active = false` 只是文字变暗，不够）
        editButtons[KEY_GMAX]?.dimmed = !speciesCanGmax()
        shortField(KEY_TRADEABLE, yesNoLabel(editTradeable))
        // 「非法」那枚是**常驻**的独立按钮（不在网格里）：消息是固定的词条、不随值变，
        // 所以这里只刷**选中态**（背景第三段，同 tab 按钮那套做法）。
        // ⚠ 它是**选中类**开关 ⇒ 必须有 selectedVisual（项目规矩：只有选中类才加）
        editIllegalButton?.selectedVisual = editIllegal
        // 右上角那枚「自由选择」同理（它也是**选中类**开关，消息固定不变）
        // 右上角那枚「解锁」同理，但它**不走 `selectedVisual`** —— 状态由 lock / unlock **图标本身**
        // 表达（见 [editFreePickButton] 的注释）
        editFreePickButton?.iconLeft = if (editFreePick) FREE_PICK_UNLOCK_ICON else FREE_PICK_LOCK_ICON
        // 携带物 / 球种：**标签 + 物品图标**（「携带物：[图标]」）——
        // 标签只留字段名不带值（值由图标表达，这才是去文字化的本意）；没设时只有标签
        shortField(KEY_HELD, "")
        editButtons[KEY_HELD]?.iconStack = stackOfSpec(editHeldItem).takeIf { !it.isEmpty }
        editButtons[KEY_BALL]?.setMessage(label("ball", ""))
        editButtons[KEY_BALL]?.iconStack = itemStackOf(editBall)
        editButtons[KEY_MIN_IVS]?.setMessage(label("min_ivs", minIvsLabel()))
        // 「保底 V 数」与「六项个体值」**双向互斥**（用户 2026-09-20 拍板）：
        // 六项填了 → 保底按钮置灰点不动；保底 > 0 → 六项输入框整个锁住。两个方向都在这里统一落地
        editButtons[KEY_MIN_IVS]?.dimmed = hasIvInput()
        applyIvInputLock()
        shortField(KEY_HELD_VISIBLE, yesNoLabel(editHeldVisible))
        editButtons[KEY_MARKS]?.setMessage(label("marks", marksLabel()))
        editButtons[KEY_FEATURE]?.setMessage(label("feature", featureButtonLabel()))
        // 「变体」一个**真能选的**候选都没有（或候选表还没到）→ **整个按钮置灰**，
        // 同「超极巨化」那枚的做法：不藏起来 —— 位置固定、点不动，按钮才不会随物种选来选去而跳位。
        // ⚠ 判据必须与点击那条**同一个量**：[openOptionList] 也是拿 [buildOptionItems] 判空
        //   （空 ⇒ 响失败音、拒绝打开）。⭐ 曾经的坑（2026-10-12 用户实测）：置灰看的是
        //   `featureGroupsOf(...)` = "有没有 feature 维度"，而洛托姆的家电（`appliance`）虽然是个
        //   维度、5 个取值却**全是形态**的 aspect，被候选表里 `formAspects` 那道过滤**整组滤掉**
        //   ⇒ 有维度、零可选项 ⇒ 按钮亮着、点了只响失败音
        editButtons[KEY_FEATURE]?.dimmed = buildOptionItems(OPT_FEATURE).isEmpty()
        // ⚠ 物品窗口那格「物品：无」按钮**已删**（2026-09-26 改成界面上直接一个搜索框，见 [buildItemFields]）——
        //   所以这里不再有 KEY_ITEM 那一项

        // 四个技能槽：显示该槽已有的技能名（走 MoveText 的属性色），空槽显示「空」
        val skillIds = MoveText.slotIds(editMoves)
        for (slot in 0 until MoveText.SLOTS) {
            val id = skillIds.getOrNull(slot).orEmpty()
            editButtons["$KEY_SKILL$slot"]?.setMessage(
                Text.translatable(
                    "cobblemarket.points.store.field.skill",
                    (slot + 1).toString(),
                    if (id.isEmpty()) Text.literal(valueText("empty")) else MoveText.name(id)
                )
            )
        }

        // 列表底部那枚「清空」的可用态也跟着走：选中集合一变，它该不该可点就变了。
        // ⚠ 放在这里是因为「建列表 / 选 / 取消 / 清空」四条路径都会调到本函数 ——
        //   散到各处各调一次，漏一条就是按钮状态卡住（原 bug 就是这么来的）
        refreshOptionClearButton()
    }

    /**
     * 「清空」按钮的可用态：一个证章都没挂时置灰（没什么可清的）。
     * 建列表时与每次改选 / 清空后都要走它 —— 见 [optionClearButton] 的注释。
     */
    private fun refreshOptionClearButton() {
        optionClearButton?.active = when (optionListKind) {
            // 变体：**任一维度**有选中就亮（清空 = 把所有维度置回「不指定」）
            // ⚠ 判据必须与 [clearOptionSelection] 真做的事**一致**，否则会"亮着却清不掉"
            //   （2026-09-27 用户报：变体列表的「清空」一直灰着，选了也灰 —— 原先这里只判了证章）
            OPT_FEATURE -> featureGroupsOf(editSpecies).any { g -> g.aspects.any { it in editAspects } }
            // 证章：「清空」只清**服主勾的那些** —— 头目之证不归它管（清了也还在），
            // 所以判据要排掉它，否则只剩头目之证时按钮还是亮的、点了却什么都没变
            else -> editMarks.any { it != ALPHA_MARK_ID }
        }
    }

    private fun yesNoLabel(value: Boolean): String = valueText(if (value) "yes" else "no")

    /**
     * 头目按钮：**徽章当标签、值写「是 / 否」**（用户 2026-09-25 改的口径）。
     *
     * ⚠ 这**推翻了**原先的「去文字化」做法（`头目：` 文字 + 徽章 + 靠**选中态**表示「是」）——
     *   选中态得盯着按钮才知道是不是头目，不如把值直接写出来。
     *   现在与超极巨化 / 可否再交易 / 携带物可见那三个按钮是**同一套写法**，只多了个行首徽章。
     */
    private fun refreshAlphaButton() {
        val btn = editButtons[KEY_ALPHA] ?: return
        // 词条只有「：%s」—— 冒号写在词条里（项目文案约定），标签那个位置留给行首的徽章
        btn.setMessage(Text.translatable("cobblemarket.points.store.field.alpha", yesNoText(editAlpha)))
        btn.iconLeft = ALPHA_BADGE_ICON
        // 值已由文字表达，不再需要选中态（2026-09-25 用户拍板）
        btn.selectedVisual = false
    }

    /**
     * 闪光按钮：**图标化**（与头目 / 性别同一套去文字化做法，用户 2026-09-20 定）。
     *
     * 记号就取其他界面那个闪光星标 `★`（[EntryBadgeRenderer] 里闪光一律追加金色 ★），三态：
     * - **必闪**（≥100%）= **金色** ★
     * - **概率**（其余正数）= **金色** ★ + 「：1%」
     * - **不闪**（空 / 0）= 灰色 ★
     *
     * ⚠ 星色判据是「**填了概率就是金色**」，不是「只有必闪才金色」—— 概率那档原先被我写成灰星了
     *   （2026-09-20 用户指出）。灰星只留给「不闪」这一种状态。
     *
     * 星标**一直都在** —— 别做成没有就空白，那样看不出这格是干什么的（头目按钮同理）；
     * 三种状态靠**颜色**与**右侧数字**区分，右侧那个输入框仍是概率的唯一真值。
     *
     * ⚠ 分段**平级挂在空 Text 下**：金色绝不能当父节点，否则冒号与百分比跟着变金（项目文案铁律）。
     */
    /**
     * 闪光的**星标写法** —— 编辑窗的闪光按钮与购买窗的只读行**共用这一个**。
     *
     * 不闪 = 灰星、必闪 = 金星、按概率 = 金星 + 百分比。
     *
     * ⚠ 用户 2026-09-26 定：购买窗原来写「闪光：按概率 50%」（英文 `Shiny: Chance 50%` = 102px），
     *   而一格只有 82px，被截成「Shiny: Chance …」。改成星标后中英文都只占 20~80px，
     *   连"跨两格"都不用 —— 星标本来就是项目里通用的闪光标记（见 `EntryBadgeRenderer.nameWithShinyStar`）。
     * ⚠ 冒号走 [shiny_chance_suffix] 词条（中文全角 / 英文半角加空格），别在代码里拼。
     *
     * @param chancePercent 概率的**百分比文本**（已格式化、不含 `%`）；[always] 为真或它为 null 时不显示
     */
    private fun shinyStarText(always: Boolean, chancePercent: String?): Text {
        val hasShiny = always || chancePercent != null
        val star = Text.literal(SHINY_STAR).formatted(if (hasShiny) Formatting.GOLD else Formatting.DARK_GRAY)
        if (!hasShiny || always) return star
        return Text.literal("")
            .append(star)
            .append(Text.translatable("cobblemarket.points.store.shiny_chance_suffix", "$chancePercent%"))
    }

    private fun refreshShinyButton() {
        val btn = editButtons[KEY_SHINY] ?: return
        val raw = editShinyText.trim()
        val percent = raw.toFloatOrNull()
        val always = percent != null && percent >= 100f
        // 「不闪」= 没填或填 0（0% 闪光就是不闪，别画成金色）—— 判据收在 [shinyStarText] 里
        val hasShiny = percent != null && percent > 0f
        btn.setMessage(shinyStarText(always, if (hasShiny && !always) raw else null))
    }

    private fun formLabel(): String =
        // ⚠ 只认**形态**：`editAspects` 里混着变体（species feature）的那些，直接读整个列表的话，
        //   一只**没有 forms** 的精灵（比如阿柏怪，只有花纹那些变体）选了变体之后，形态按钮会
        //   显示成花纹串 —— 它本该老老实实显示「默认」（2026-09-27 用户实测报的）
        pickedFormAspects().let { picked ->
            if (picked.isEmpty()) valueText("default")
            else picked.joinToString("/") { SpeciesText.aspectLabel(it) }
        }

    /**
     * 性别按钮：**图标化**（沿用项目 1.1.x 起的去文字化方向，不再写「性别：雄」）。
     * 雄性 ♂ / 雌性 ♀ / 不限 ♂♀ 并排；**无性别没有对应图标**，只有这时才退回文字。
     */
    private fun refreshGenderButton() {
        val btn = editButtons[KEY_GENDER] ?: return
        // 只有一种可能的性别（神兽那种无性别 / 单性别物种）→ **整个按钮置灰**。
        // ⚠ 用 `dimmed`（整块盖半透明黑），**不是** `active = false` ——
        //   后者只把文字/图标调成 0xA0A0A0，按钮底图原样，看着只是"字有点暗"。
        //   模板就是入口界面「喵喵银行关闭」时那个按钮（2026-09-20 用户指出）
        btn.dimmed = availableGenderIndices().size <= 1
        when (GENDER_VALUES.getOrNull(editGender).orEmpty()) {
            "MALE" -> {
                btn.iconLeft = GENDER_ICON_MALE; btn.iconLeft2 = null; btn.setMessage(Text.literal(""))
            }

            "FEMALE" -> {
                btn.iconLeft = GENDER_ICON_FEMALE; btn.iconLeft2 = null; btn.setMessage(Text.literal(""))
            }

            "GENDERLESS" -> {
                btn.iconLeft = null; btn.iconLeft2 = null
                btn.setMessage(Text.translatable("cobblemarket.points.store.value.genderless"))
            }

            else -> {
                btn.iconLeft = GENDER_ICON_MALE; btn.iconLeft2 = GENDER_ICON_FEMALE
                btn.setMessage(Text.literal(""))
            }
        }
    }

    private fun natureLabel(): String = optionLabelOf(OPT_NATURE, editNature)

    private fun abilityLabel(): String = optionLabelOf(OPT_ABILITY, editAbility)

    private fun ballLabel(): String = optionLabelOf(OPT_BALL, editBall)

    /**
     * 太晶属性的**带色文本**（按属性色，与选项列表同一口径）。
     * ⚠ 值存的是资源 id（`cobblemon:water`），`:` 后面那截正好是属性名 —— 直接喂 TypeTextColors。
     */
    private fun teraText(): Text {
        if (editTera.isEmpty()) return Text.translatable("cobblemarket.points.store.value.random")
        val name = com.cobblemon.mod.common.api.types.tera.TeraTypes.toList()
            .firstOrNull { it.id.toString() == editTera }?.displayName?.string ?: editTera
        return Text.literal(name)
            .styled { s -> s.withColor(com.shusheng.cobblemarket.util.TypeTextColors.color(editTera.substringAfter(':'))) }
    }

    private fun teraLabel(): String = optionLabelOf(OPT_TERA, editTera)

    private fun sizeLabel(): String =
        PokemonSizeCategory.entries.getOrNull(editSize - 1)?.name ?: valueText("random")

    private fun minIvsLabel(): String = if (editMinIvs <= 0) valueText("any") else "${editMinIvs}V"

    private fun heldLabel(): String = if (editHeldItem == null) valueText("none") else editHeldItemName

    /** 证章：按钮上只说挂了几个（名字长、还能挂多个，具体是哪些进列表看） */
    /**
     * **实际会生成到精灵上的证章** = 记着的那份 + **头目自动带的头目之证**。
     *
     * ⚠ 头目之证不由我们决定：Cobblemon 的 `Pokemon.isAlpha` setter 里会
     * `exchangeMark(mark_alpha, true)` —— 服主在证章列表里勾没勾都一样会带上。
     * 所以界面上必须把它算进去，否则「明明设了头目、证章却显示 0 个」（2026-09-25 用户报）。
     *
     * ⚠ 先**过滤掉**再加：`marks` 里可能本来就残留这个 id（编辑旧挂单 / 早期存的数据），
     *   不过滤会重复计数（显示 2 个）。
     */
    private fun effectiveMarksOf(marks: List<String>, alpha: Boolean): List<String> =
        marks.filterNot { it == ALPHA_MARK_ID } + if (alpha) listOf(ALPHA_MARK_ID) else emptyList()

    /** 编辑窗当前草稿实际会带的证章（见 [effectiveMarksOf]） */
    private fun effectiveMarks(): List<String> = effectiveMarksOf(editMarks, editAlpha)

    private fun marksLabel(): String =
        effectiveMarks().size.let { if (it == 0) valueText("none") else it.toString() }

    private fun doRedeem() {
        val code = codeField?.text?.trim().orEmpty()
        if (code.isEmpty()) {
            playFailSound()
            return
        }
        // 不在这里清空：失败时玩家还要改那串码；成功由 onMarketResult 清
        sendToServer(RedeemPointCodePayload(code))
    }

    /** 商城快照到了：切换「购买 RP」入口的显隐（**不重建界面**，免得清掉已输入的内容） */
    fun onShopInfo() {
        buyEntryButton?.visible = PointsShopCache.tiers.isNotEmpty()
        // 购买入口的可见性变了 → 记录按钮跟着换位置（**不重建界面**，见 layoutRecordButton）
        layoutRecordButton()
    }

    /**
     * 兑换结果（服务端 MarketResultPayload 到达；成功才清空输入框 —— 码已用掉，留着没用）。
     *
     * ⚠ **成功与失败都要给出可见反馈**：此前只清空输入框 / 什么都不做，玩家输完码只听到一声音效，
     * 根本不知道兑换成没成、错在哪（2026-09-19 用户实测）。服务端的 message 里本来就写清了原因
     * （成功给了多少 / 码无效 / 冷却中），照其他界面发到聊天栏即可。
     */
    fun onMarketResult(payload: MarketResultPayload) {
        if (payload.success) {
            codeField?.text = ""
            pendingCodeText = ""
        }
        // 界面内提示（5 秒）：玩家正盯着兑换框，只发聊天栏容易被界面挡着漏看
        resultMsg = payload.message
        resultColor = if (payload.success) 0x55FF55 else 0xFF6666
        resultUntil = System.currentTimeMillis() + 5000
        client?.player?.sendMessage(
            payload.message.copy().formatted(if (payload.success) Formatting.GREEN else Formatting.RED),
            false
        )
    }

    /**
     * 界面销毁时放开缓存：不放开的话，下一个打开**另一个**商店的界面在 `init` 之前
     * （一帧都不到的空档）可能被旧商店快照落进来；而且离开游戏后全局缓存会一直卡着一个 id。
     *
     * ⚠ 判 `== shopId` 再清：换店时原版是**先 removed 旧界面、再 init 新界面**，
     *   顺序正常；但 resize / 其它路径若出现交叠，别把新界面刚设的 id 清掉。
     */
    override fun removed() {
        super.removed()
        // 界面被关闭 / 换店时编辑窗还开着：同样要解锁，否则这家店会一直显示「编辑中」。
        // ⚠ 断线时这里也会被调：连接已经没了就别发包（ClientPlayNetworking.send 会抛异常）。
        if (editSlot >= 0) {
            editSlot = -1
            if (client?.networkHandler != null) sendToServer(SetShopEditingPayload(shopId, false))
        }
        // 界面以任何方式离开时清掉关闭动画状态（防残留到下一次打开）
        closeAnimStarted = false
        closeAnimTarget = null
        if (PointMarketCache.viewingShopId == shopId) PointMarketCache.viewingShopId = null
    }

    /**
     * 把背景贴图按**目标矩形**画出来（正常界面与打开动画共用）。
     *
     * ⚠ `drawTexture` 的 width/height **同时是「从源图采样的区域尺寸」**（1:1 采样），
     * 不是目标缩放尺寸 —— 直接传缩放后的目标尺寸，只会把原图左上角那一块抽出来放大
     * （2026-09-18 用户实测踩到：「只剩整张图的左上角」）。
     * 缩放必须走矩阵：按原图尺寸绘制、再整体缩到目标大小（同入口掉落动画的做法）。
     */
    private fun drawBackgroundScaled(context: DrawContext, x: Int, y: Int, w: Int, h: Int) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(w.toFloat() / TEX_W, h.toFloat() / TEX_H, 1f)
        context.drawTexture(BACKGROUND, 0, 0, 0f, 0f, TEX_W, TEX_H, TEX_W, TEX_H)
        context.matrices.pop()
    }

    /**
     * 自定义商店的打开动画：面板从**鼠标位置**的一小块长到正常大小。
     *
     * 曲线用 **ease-out（先快后慢）**，而不是弹窗那套 smoothstep：
     * 前 80% 就把面板撑到 ≥96%，最后 20% 才淡入标题 / 网格 ——
     * 这样内容在**最终位置**出现时已经装得进面板，不会先露到面板外面。
     *
     * ⚠ 动画期间 `super.render()` 整个跳过：控件（返回 / tab / 翻页）是 MC 控件、
     *   没法塞进缩放中的矩形，等播完那一帧再交给正常路径画。点击 / 滚轮也一并挡掉。
     * ⚠ 内容里的 shader 颜色必须在同一次 render 内恢复（用 try/finally，别指望顺序）。
     */
    private fun renderOpenAnimation(context: DrawContext, delta: Float) {
        val bg = bgRect()
        val t = openAnimRaw()
        val eased = 1f - (1f - t) * (1f - t)
        val scale = openAnimStartScale + (1f - openAnimStartScale) * eased
        val centerX = openAnimOriginX + (bg.x + bg.w / 2f - openAnimOriginX) * eased
        val centerY = openAnimOriginY + (bg.y + bg.h / 2f - openAnimOriginY) * eased
        val w = (bg.w * scale).roundToInt().coerceAtLeast(1)
        val h = (bg.h * scale).roundToInt().coerceAtLeast(1)
        drawBackgroundScaled(
            context,
            (centerX - w / 2f).roundToInt(),
            (centerY - h / 2f).roundToInt(),
            w, h
        )

        val alpha = ((t - 0.8f) / 0.2f).coerceIn(0f, 1f)
        if (alpha <= 0f) return
        // 动画期间不认悬停：格子放大 / 浮起会在缩放中的面板里乱跳
        hoveredCell = -1
        context.setShaderColor(alpha, alpha, alpha, alpha)
        try {
            drawMarketDecor(context, delta)
        } finally {
            context.setShaderColor(1f, 1f, 1f, 1f)
        }
    }

    /**
     * 关闭动画：打开动画的**严格反向**（时间反演）——
     * 面板从全尺寸缩回打开时的鼠标点（右键 NPC 的位置），内容在前 20% 淡出。
     * 打开用 ease-out（`1-(1-t)²`），关闭取反演 ⇒ ease-in（`t²`）。
     */
    private fun renderCloseAnimation(context: DrawContext, delta: Float) {
        val bg = bgRect()
        val t = closeAnimRaw()
        val eased = t * t
        val scale = 1f + (openAnimStartScale - 1f) * eased
        val bgCx = bg.x + bg.w / 2f
        val bgCy = bg.y + bg.h / 2f
        val centerX = bgCx + (openAnimOriginX - bgCx) * eased
        val centerY = bgCy + (openAnimOriginY - bgCy) * eased
        val w = (bg.w * scale).roundToInt().coerceAtLeast(1)
        val h = (bg.h * scale).roundToInt().coerceAtLeast(1)
        // 面板整体在最后 40% 淡出：动画到点后要等下一次 tick 才真正切界面，
        // 不淡出会停在一个 6% 的小面板上（用户 2026-10-25 报「最后顿一下」）
        val panelAlpha = ((1f - t) / 0.4f).coerceIn(0f, 1f)
        context.setShaderColor(panelAlpha, panelAlpha, panelAlpha, panelAlpha)
        try {
            drawBackgroundScaled(
                context,
                (centerX - w / 2f).roundToInt(),
                (centerY - h / 2f).roundToInt(),
                w, h
            )
        } finally {
            context.setShaderColor(1f, 1f, 1f, 1f)
        }

        val alpha = (1f - t / 0.2f).coerceIn(0f, 1f) * panelAlpha
        if (alpha <= 0f) return
        hoveredCell = -1
        context.setShaderColor(alpha, alpha, alpha, alpha)
        try {
            drawMarketDecor(context, delta)
        } finally {
            context.setShaderColor(1f, 1f, 1f, 1f)
        }
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val bg = bgRect()
        drawBackgroundScaled(context, bg.x, bg.y, bg.w, bg.h)

        // 兑换结果提示：水平居中、落在**返回按钮底边与背景底边之间**那段空隙的中点
        // （用户 2026-09-19 指定）。此前画的是**背景正中** —— 压着界面内容很难看，
        // 注释写着「返回按钮与背景底边之间」但代码没落实，这次补上。
        // ⚠ 传**原来的 Text**，别转成 String 再拼 —— 结果里带 RP 图标字形（字体挂在 Text 样式上），
        //   一转 String 图标就变成方块（本项目反复踩过的坑）
        val showingResult = resultMsg != null && System.currentTimeMillis() < resultUntil
        if (showingResult) {
            resultMsg?.let { msg ->
                // 返回按钮底边 = bg 底边往上 BACK_BOTTOM（原图口径），这段空隙的中心再上移半行字高
                val slotCenterY = bg.y + bg.h - texPx(BACK_BOTTOM) / 2
                context.drawCenteredTextWithShadow(
                    textRenderer, msg,
                    bg.x + bg.w / 2,
                    slotCenterY - 4,
                    resultColor
                )
            }
        } else if (canEditShop() && editSlot < 0 && buySlot < 0 && !snapshotDialog) {
            // 服主 / 持 `points.shop` 的人：在这里常驻一行两个入口的提示
            //（与结果提示共用位置；有结果时让位）。领取店没有购买，只提示编辑。
            // ⚠ 弹窗打开时不画：这行属于主界面，弹窗叠上来后它还亮着会抢注意力
            //   （2026-10-28 用户报「打开弹窗时白字还高亮」）
            val slotCenterY = bg.y + bg.h - texPx(BACK_BOTTOM) / 2
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable(
                    if (isClaimShop) "cobblemarket.points.store.manage_hint_edit_only"
                    else "cobblemarket.points.store.manage_hint"
                ),
                bg.x + bg.w / 2,
                slotCenterY - 4,
                // 白字：这一行画在背景底部的红色条上，灰色（0x777777）在红底上不显眼
                //（2026-10-27 用户报）
                0xFFFFFF
            )
        }
    }

    /**
     * 标题 / 两条红色分割线 / 商品区网格。
     *
     * ⚠ **弹窗开着时一律不画** —— 这几样是画在 `super.render()`（也就是全部控件）**之后**的，
     * 不跳过的话标题与网格会整个盖在弹窗上（2026-09-20 用户实测两次抓出）。
     * 这是项目里的既有做法，见 `BlacklistScreen.render` 的 `if (addField != null) return`。
     */
    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // ⚠ **必须在最开头**：清空本帧的图标注册表，并记下当前鼠标坐标。
        //   坐标是**头部跟随鼠标**的唯一来源 —— 漏调的后果是 `mouseX/mouseY` 恒为 0，
        //   本界面所有图标都盯着屏幕左上角一动不动（2026-09-20 用户报「网格与编辑窗的精灵
        //   都不跟随，而其他界面都跟」）。注册表还兼着**点击叫声**的命中判定（见 [IconRegistry]
        //   的注释），不清空会逐帧累积、点到别处的图标。
        //   全项目其余 11 个界面都在 render 开头调它，本界面当初漏了。
        IconRegistry.beginFrame(mouseX, mouseY)
        // 自定义商店关闭动画（打开动画的反向）：缩回打开时的鼠标点（≈右键的 NPC）。
        // ⚠ 真正切界面在 [tick] 里做 —— 不能在 render 里 setScreen（光标锁定状态会异常，项目踩过）
        if (isCustomShop && closeAnimStarted) {
            if (!openAnimOriginCaptured) {
                openAnimOriginX = mouseX.toFloat()
                openAnimOriginY = mouseY.toFloat()
                openAnimOriginCaptured = true
            }
            renderCloseAnimation(context, delta)
            return
        }
        // 自定义商店打开动画：首帧记下鼠标位置（= 玩家右键 NPC 的点击点），
        // 动画期间只画面板与淡入中的内容；控件等播完再交给正常路径画。
        if (openAnimActive()) {
            if (!openAnimOriginCaptured) {
                openAnimOriginX = mouseX.toFloat()
                openAnimOriginY = mouseY.toFloat()
                openAnimOriginCaptured = true
            }
            renderOpenAnimation(context, delta)
            return
        }
        // 翻页按钮的可用态：第一页 →「上一页」置灰、最后一页 →「下一页」置灰（照精灵市场的做法）。
        // ⚠ 放在 render 里而不是建控件时：`totalPages` 来自**服务端快照**，会随每次广播变，
        //   建控件时那份早就过期了（2026-09-27 用户报「第一页时上一页该是暗的」）
        val pagesInView = viewPages()
        val pageNo = viewPageNumber()
        prevButton?.active = pageNo > 1
        nextButton?.active = pageNo < pagesInView
        // 领取按钮：冷却中置灰（状态由服务端在打开商店 / 每次领取后下发；0 = 现在就能领）
        claimButton?.dimmed = isClaimShop &&
            CustomShopClaimCache.nextResetAtOf(shopId) > System.currentTimeMillis()
        // 边界时直接隐藏（2026-10-25 用户要求）；弹窗开着时不动（hideMainControls 已把它们藏起来）
        if (!dialogOpen()) {
            prevButton?.visible = pageNo > 1
            nextButton?.visible = pageNo < pagesInView
        }
        // 展开动画期间把窗口控件藏起来（它们是 MC 控件、由 super.render 统一画，塞不进插值矩形）；
        // 播完这一帧会自动放回去（见 updateDialogAnimControls）
        if (dialogOpen()) updateDialogAnimControls()
        // 「形态需要前置条件」那枚警告：**常驻**（用户 2026-09-30 定），这里每帧只刷**它的颜色**。
        //
        // ⚠⭐ 为什么改成常驻：它的判据 [SpeciesText.hasGatedAspect] 是**硬编码清单**（72 个 aspect，
        //   见 [SpeciesText.GATED_FORM_ASPECTS]），**自制 / 魔改数据包加的战斗形态不在清单里**
        //   ⇒ 靠"命中才出现"的话那些形态**永远不会给提示**（用户点出的正是这个：
        //   「指定 72 种形态就把范围定死了，管得住原版，管不住自制和魔改」）。
        //   常驻 = 入口一直在，服主随时能点开看，**不依赖判据命中**。
        // ⚠ 但**颜色跟着草稿走**：真选中那类形态才染红 —— 一个红 ⚠ 常驻在那儿会被读成
        //   "当前配置有问题"（本项目"红 = 警告态"到处都是：关市横幅 / 售罄的 0 / EV 段），
        //   没命中时用常规白，语义退回"入口 / 帮助"，不误导。
        //
        // ⚠ 刷颜色**必须每帧**、且**排在 [updateDialogAnimControls] 之后**（同 [prevButton]）：
        //   那个函数在开窗动画期间把 `editWidgetBase` 之后的控件一律藏掉、播完又交给
        //   [updateEditControlsVisibility]；改形态的路子有十几处（选项列表点选 / 快照回填 /
        //   换物种 / 清空），逐个补事件必然漏一处。
        //   ⚠ **可见性不在这儿管** —— 交给 [updateEditControlsVisibility] 的 `covered`
        //   （常驻 = 只在叠层打开时藏起来）
        editGatedInfoButton?.textColor = if (hasGatedPickedForm()) GATED_WARN_COLOR else 0xFFFFFF
        super.render(context, mouseX, mouseY, delta)
        if (dialogOpen()) return
        drawMarketDecor(context, delta)
        // 领取店：底部「领取」按钮上方显示刷新倒计时
        drawClaimRefreshHint(context)
        // 格子悬停窗：两种商店都给完整详情（领取店额外给完整的「可领 N」；购买店给完整单价 + 库存）
        drawCellTooltip(context, mouseX, mouseY)
    }

    /**
     * 「领取」飞入动画的起点：**当前页可见的物品格**（精灵不做动画）。
     *
     * 只登记、不判「这一格现在能不能领」—— 实际飞哪几格由服务端回执决定（可能部分交付，
     * 背包放不下的留在店里）。别页 / 另一个 tab 的格子看不见起点，登记了也没用，直接不登记。
     *
     * ⚠ 必须在**点击领取那一刻**调用：回执到达时界面可能已经翻页 / 关掉，格子位置就没了。
     */
    private fun claimIconOrigins(): Map<String, ItemClaimFlyAnimation.Icon> {
        if (!isClaimShop) return emptyMap()
        val layout = gridLayout(bgRect())
        val icons = LinkedHashMap<String, ItemClaimFlyAnimation.Icon>()
        for (cell in 0 until layout.cols * layout.rows) {
            val slot = slotOfCell(cell)
            if (slot < 0) continue
            val entry = entryAt(slot) ?: continue
            if (entry.kind != ShopEntry.KIND_ITEM || entry.entryId.isEmpty()) continue
            // 与格子里画的是**同一个栈**（动画触发时会复制再加数量，不会污染商店图标）
            val stack = cellIcon(slot, entry).stack ?: continue
            val rect = iconRectOf(layout.cellX(cell), layout.cellY(cell), layout.cell)
            icons[entry.entryId] = ItemClaimFlyAnimation.Icon(
                stack = stack,
                origin = ItemClaimFlyAnimation.Origin.ofIcon(rect.x, rect.y, rect.size)
            )
        }
        return icons
    }

    /**
     * 这一格当前还能领几个（领取店专用）。
     *
     * - 还没收到状态（-1）→ 返回 -1，界面先不画
     * - 冷却已过（`nextResetAt` 到了）→ 直接按满配额显示，不等服务端补发（服务端也是懒重置）
     */
    private fun claimRemaining(entry: ShopEntry): Int {
        val cached = CustomShopClaimCache.remainingOf(shopId, entry.entryId, entry.stock)
        if (cached < 0) return -1
        val next = CustomShopClaimCache.nextResetAtOf(shopId)
        if (next > 0L && System.currentTimeMillis() >= next) return entry.stock
        return cached
    }

    /**
     * **商城格子的悬停窗**（2026-10-24 领取店先加，2026-10-26 扩到全部商店）。
     *
     * 精灵给完整规格、物品给原版词条；最后一行按商店补：
     *  · 领取店：完整的「Claimable: N / 可领 N」（格子里只放得下短标签 `N left`）
     *  · 购买店：**完整单价 + 限量库存**（格子里的价格是缩写、库存只有角标）
     *
     * ⚠ 落点 / 外观走全项目同一套 [withTooltipLayer]（同证章 / 变体 / 非法印章那几个悬停窗）。
     */
    private fun drawCellTooltip(context: DrawContext, mouseX: Int, mouseY: Int) {
        // ⚠ 这里**现算**格子（不用缓存的 hoveredCell）：翻页 / 数据刷新后它可能停在旧值上
        val cell = cellAt(mouseX.toDouble(), mouseY.toDouble(), gridLayout(bgRect()))
        if (cell < 0) return
        val slot = slotOfCell(cell)
        if (slot < 0) return
        val entry = entryAt(slot) ?: return
        val lines = cellTooltipLines(slot, entry)
        if (lines.isEmpty()) return
        val pad = MARK_TIP_PAD
        val lineH = 10
        // 太高就截断（物品词条可能很多），但**最后一行摘要必须留着**
        val maxLines = ((height - pad * 2) / lineH).coerceAtLeast(2)
        val shown = if (lines.size <= maxLines) lines else lines.take(maxLines - 1) + lines.last()
        val w = shown.maxOf { textRenderer.getWidth(it) } + pad * 2
        val h = shown.size * lineH + pad * 2
        withTooltipLayer(context, mouseX, mouseY, w, h) { tx, ty ->
            shown.forEachIndexed { i, line ->
                context.drawTextWithShadow(textRenderer, line, tx + pad, ty + pad + i * lineH, 0xFFFFFF)
            }
        }
    }

    /** 悬停窗内容：精灵 = 规格详情；物品 = 原版词条；**最后一行按商店补摘要** */
    private fun cellTooltipLines(slot: Int, entry: ShopEntry): List<Text> {
        val lines = mutableListOf<Text>()
        val spec = entry.pokemon
        if (spec != null) {
            lines += claimPokemonSpecLines(spec)
        } else {
            val itemSpec = entry.item
            val stack = cellIcon(slot, entry).stack
            if (itemSpec != null && stack != null && !stack.isEmpty) {
                lines += ItemComponentsDisplay.itemTooltip(
                    stack, client?.player, ItemComponentsDisplay.tooltipTypeForHover()
                )
            } else if (itemSpec != null) {
                lines += Text.literal(itemDisplayName(itemSpec.itemId))
            }
        }
        if (isClaimShop) {
            val remaining = claimRemaining(entry)
            if (remaining >= 0) {
                lines += Text.translatable("cobblemarket.points.store.claim_remaining", remaining)
                    .formatted(if (remaining > 0) Formatting.GREEN else Formatting.DARK_GRAY)
            }
        } else {
            // 格子里的价格是**缩写**（9.9M），悬停给完整单价；库存角标只有数字，这里补「剩余」
            val summary = Text.literal("").append(
                Text.translatable(
                    "cobblemarket.points.store.buy_price",
                    CurrencyService.amountText(entry.price, shopCurrency)
                )
            )
            if (entry.stock != ShopEntry.UNLIMITED) {
                summary.append(Text.literal("    "))
                    .append(Text.translatable("cobblemarket.points.store.buy_stock", entry.stock))
            }
            lines += summary
        }
        return lines.filter { it.string.isNotEmpty() }
    }

    /**
     * 精灵规格的详情行 —— **与购买窗同一份取数**（[buyFieldLine] / 形态 / 闪光 / 球种 / 携带物），
     * 只是改成单列文字（悬停窗里画不了购买窗那套图标控件）。
     */
    private fun claimPokemonSpecLines(spec: ShopPokemonSpec): List<Text> {
        val lines = mutableListOf<Text>()
        val species = Identifier.tryParse(spec.species)?.let { PokemonSpecies.getByIdentifier(it) }
        if (species != null) {
            val primary = "cobblemon.type.${species.primaryType.name.lowercase()}"
            val secondary = species.secondaryType?.let { "cobblemon.type.${it.name.lowercase()}" }.orEmpty()
            // 名字 + 等级 + 属性：与购买窗名字行同一套（名字属性色、等级/加号不设色）
            lines += Text.literal("")
                .append(
                    EntryBadgeRenderer.nameWithLevelLine(
                        Text.literal(SpeciesText.translated(species).string), primary, spec.level ?: 1
                    )
                )
                .append(Text.literal("  "))
                .append(EntryBadgeRenderer.typeLine(primary, secondary))
        } else {
            lines += Text.literal(spec.species)
        }
        // 闪光（金★ / 灰★ / ★ xx%）—— 走购买窗的同一份星标文本；悬停窗里补上「闪光：」标签
        //（购买窗那格本身就是星标按钮，不需要标签）
        lines += Text.translatable(
            "cobblemarket.points.store.field.shiny", buyFieldLine(KEY_SHINY, spec)
        )
        formAspectsOf(spec).takeIf { it.isNotEmpty() }?.let {
            lines += Text.translatable(
                "cobblemarket.points.store.field.form",
                it.joinToString("/") { aspect -> SpeciesText.aspectLabel(aspect) }
            )
        }
        featureAspectsOf(spec).takeIf { it.isNotEmpty() }?.let {
            lines += Text.translatable(
                "cobblemarket.points.store.field.feature",
                it.joinToString("/") { aspect -> featureValueLabel(aspect) }
            )
        }
        lines += buyFieldLine(KEY_ABILITY, spec)
        lines += buyFieldLine(KEY_NATURE, spec)
        // 性别 / 体型：购买窗用贴图表示，文字窗这边给文字值
        lines += Text.translatable(
            "cobblemarket.points.store.field.gender",
            when (spec.gender?.uppercase()) {
                "MALE" -> Text.translatable("cobblemarket.aspect.male")
                "FEMALE" -> Text.translatable("cobblemarket.aspect.female")
                "GENDERLESS" -> Text.translatable("cobblemarket.points.store.value.genderless")
                else -> Text.translatable("cobblemarket.points.store.value.random")
            }
        )
        lines += Text.translatable(
            "cobblemarket.points.store.field.size",
            PokemonSizeCategory.entries.getOrNull(sizeIndexOf(spec.scale) - 1)?.name
                ?: Text.translatable("cobblemarket.points.store.value.random").string
        )
        // 球种 / 携带物：悬停窗里给可读名字（购买窗那边是物品图标）
        spec.ball?.takeIf { it.isNotBlank() }?.let {
            lines += Text.translatable("cobblemarket.points.store.field.ball", itemDisplayName(it))
        }
        if (spec.heldItemVisible) {
            val held = stackOfSpec(spec.heldItemSpec)
            if (!held.isEmpty) {
                lines += Text.translatable(
                    "cobblemarket.points.store.field.held",
                    ItemNameText.rarityColoredName(held) ?: Text.translatable(held.item.translationKey)
                )
            }
        }
        lines += claimIvLine(spec)
        if (spec.ivs == null && spec.minPerfectIvs > 0) {
            lines += Text.translatable("cobblemarket.points.store.field.min_ivs", "${spec.minPerfectIvs}V")
        }
        claimEvLine(spec)?.let { lines += it }
        lines += claimMovesLine(spec)
        // 稀有 / 特殊项：**有才显示**（避免一堆「否」把悬停窗撑长）
        if (spec.teraType != null) lines += buyFieldLine(KEY_TERA, spec)
        if (spec.gmaxFactor) lines += buyFieldLine(KEY_GMAX, spec)
        if (spec.dmaxLevel > 0) lines += buyFieldLine(KEY_DMAX, spec)
        // 头目：购买窗那行画的是**徽章贴图**当标签（`field.alpha` 词条只有「：%s」），
        // 悬停窗里用带文字标签的词条，别直接复用那行（会显示成孤零零的「：是」）
        if (spec.alpha) {
            lines += Text.translatable(
                "cobblemarket.points.store.field.alpha_label", yesNoText(true)
            )
        }
        if (effectiveMarksOf(spec.marks, spec.alpha).isNotEmpty()) lines += buyFieldLine(KEY_MARKS, spec)
        spec.originalTrainer?.takeIf { it.isNotBlank() }?.let { lines += buyFieldLine(KEY_OT, spec) }
        // 不可交易的**必须写出来**（玩家拿到后不能转手，属于重要信息）
        if (!spec.tradeable) lines += buyFieldLine(KEY_TRADEABLE, spec)
        return lines
    }

    /** 个体值一行：全随机 → 「个体值：随机」；有具体值 → `HP 31 / Atk 31 / …`，没填的项标「随机」 */
    private fun claimIvLine(spec: ShopPokemonSpec): Text {
        val ivs = spec.ivs
            ?: return Text.translatable(
                "cobblemarket.points.store.field.ivs",
                Text.translatable("cobblemarket.points.store.value.random")
            )
        val value = Text.literal("")
        var first = true
        ivs.forEachIndexed { i, v ->
            if (v < 0) return@forEachIndexed
            if (!first) value.append(Text.literal(" / "))
            value.append(Text.literal("${STAT_ABBR[i]} $v"))
            first = false
        }
        if (ivs.any { it < 0 }) {
            if (!first) value.append(Text.literal(" / "))
            value.append(Text.translatable("cobblemarket.points.store.value.random"))
        }
        return Text.translatable("cobblemarket.points.store.field.ivs", value)
    }

    /** 努力值一行：**全 0 / 没设就不画**（只给玩家看真正设过的项） */
    private fun claimEvLine(spec: ShopPokemonSpec): Text? {
        val evs = spec.evs ?: return null
        val value = Text.literal("")
        var first = true
        evs.forEachIndexed { i, v ->
            if (v <= 0) return@forEachIndexed
            if (!first) value.append(Text.literal(" / "))
            value.append(Text.literal("${STAT_ABBR[i]} $v"))
            first = false
        }
        if (first) return null
        return Text.translatable("cobblemarket.points.store.field.evs", value)
    }

    /** 技能一行：四个槽位按顺序列出；空槽 = 生成时随机补，标「随机」 */
    private fun claimMovesLine(spec: ShopPokemonSpec): Text {
        val slots = spec.moves.split(",")
        val value = Text.literal("")
        repeat(4) { i ->
            if (i > 0) value.append(Text.literal(" / "))
            val id = slots.getOrNull(i)?.trim().orEmpty()
            value.append(
                if (id.isEmpty()) Text.translatable("cobblemarket.points.store.value.random")
                else MoveText.name(id)
            )
        }
        return Text.translatable("cobblemarket.points.store.field.moves", value)
    }

    /** 领取店：底部按钮上方的「刷新剩余」提示（没有进行中的周期就不画） */
    private fun drawClaimRefreshHint(context: DrawContext) {
        if (!isClaimShop) return
        val next = CustomShopClaimCache.nextResetAtOf(shopId)
        if (next <= 0L) return
        val bg = bgRect()
        val remain = com.shusheng.cobblemarket.util.RemainingTime.text(
            next, "cobblemarket.points.store.claim_ready",
            secondPrecision = false, localizedUnits = true
        )
        // 放在「领取」按钮**右侧**（不是上方 —— 上方会压到那条红色分割线，用户 2026-10-23 报）。
        // ⚠ 领取按钮 2026-10-24 改成与翻页按钮**同宽同高**（80×20）⇒ 偏移量必须跟着换。
        // ⚠ 右侧放不下英文全称时（640×360 下会压到「下一页」按钮）**改到按钮正下方居中** ——
        //   按钮行下沿到背景底边还有一段空白，是唯一不会撞到别的控件的位置。
        val buttonY = pageRowY(bg)
        val hint = Text.translatable("cobblemarket.points.store.claim_refresh_in", remain)
        val rightX = bg.x + bg.w / 2 + PAGE_BTN_W / 2 + 8
        val rightLimit = (nextButton?.x ?: (bg.x + bg.w)) - 4
        if (rightX + textRenderer.getWidth(hint) <= rightLimit) {
            context.drawTextWithShadow(
                textRenderer, hint, rightX, buttonY + (PAGE_BTN_H - 8) / 2, 0xAAAAAA
            )
        } else {
            context.drawCenteredTextWithShadow(
                textRenderer, hint, bg.x + bg.w / 2, buttonY + PAGE_BTN_H + 6, 0xAAAAAA
            )
        }
    }

    private fun drawMarketDecor(context: DrawContext, delta: Float) {
        val bg = bgRect()
        // 标题：与其余界面同一套写法（**金色样式挂在 Text 上**，绘制行色仍是白 ——
        // 直接把行色写成金色是错的，那样整行都是金的、与全模组颜色模板不符）。
        // ⚠ 自定义商店用**服主起的店名**（服务端随打开包下发），不再显示「RP 商城」固定词条
        val titleText = if (isCustomShop) {
            Text.literal(customShopName.ifEmpty { shopId }).formatted(Formatting.GOLD)
        } else {
            Text.translatable("cobblemarket.points.market.title", CurrencyService.pointsTitleIcon())
                .formatted(Formatting.GOLD)
        }
        context.drawCenteredTextWithShadow(
            textRenderer,
            titleText,
            bg.x + bg.w / 2, bg.y + texPx(TITLE_TOP), 0xFFFFFF
        )
        // 两条红色分割线：**夹出中间的商品区**（服主上架的精灵/物品放这里）。
        // 颜色与入口界面/管理面板那两条统一走 GuiColors.DIVIDER（1px 粗，跨度左右避开背景边框，
        // 不盖住边框上的装饰 —— 用户 2026-09-18 定）。
        // 长度：避开边框的基础上，左右各再向边框方向延长 DIVIDER_EXTEND
        val lineLeft = bg.x + texPx(DIVIDER_BORDER_X) - DIVIDER_EXTEND
        val lineRight = bg.x + bg.w - texPx(DIVIDER_BORDER_X) + DIVIDER_EXTEND
        val lineTop = dividerTopY(bg)
        val lineBottom = dividerBottomY(bg)
        val divider = com.shusheng.cobblemarket.client.GuiColors.DIVIDER
        context.fill(lineLeft, lineTop, lineRight, lineTop + 1, divider)
        context.fill(lineLeft, lineBottom, lineRight, lineBottom + 1, divider)

        // 商品区网格：格子尺寸固定、行列按可用空间算、整体居中。
        // **悬停的那个格子最后单独画**（放大后浮在邻居之上），前面的循环跳过它。
        val layout = gridLayout(bg)
        val cellCount = layout.cols * layout.rows
        for (i in 0 until cellCount) {
            if (i == hoveredCell) continue
            drawShopCell(context, layout, i, delta, hover = false)
        }
        if (hoveredCell in 0 until cellCount) {
            drawShopCell(context, layout, hoveredCell, delta, hover = true)
        }
        // 搜索没有命中：网格是空的，给一句提示（否则像界面坏了）
        // ⚠ 位置贴着**上分割线**下方（用户 2026-10-27 要求）——原先放 layout.startY + 24，
        //   正好压在第一行格子中间，看起来像界面坏了
        if (searchMode && searchResults.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.market.search_empty").formatted(Formatting.GRAY),
                bg.x + bg.w / 2,
                dividerTopY(bg) + 6,
                0xFFFFFF
            )
        }
    }

    /** 弹窗开着时鼠标移动到不同格子不该再触发悬停放大（放大动画在弹窗底下闪没意义） */
    private fun freezeHover(): Boolean = dialogOpen()

    /**
     * 有没有窗口开着（**编辑或购买**）。
     * 悬停冻结、主装饰跳过、事件只给窗口、tick 不发列表请求 —— 四处统一判它，别再各写一遍 `editSlot >= 0`。
     */
    private fun dialogOpen(): Boolean = editSlot >= 0 || buySlot >= 0

    // ── 商品区：格子内容 ──

    /**
     * 画一个格子：底图 + 内容（精灵 3D 图标 / 物品图标）+ 价格 + 库存角标。
     * 空格子只有底图 —— 服主点它上架，玩家点了没反应。
     */
    private fun drawShopCell(
        context: DrawContext, layout: GridLayout, cell: Int, delta: Float, hover: Boolean
    ) {
        val x = layout.cellX(cell)
        val y = layout.cellY(cell)
        val size = layout.cell
        // 悬停的格子放大后**仍然居中于原格子**（两边各外扩一半），且只放大绘制、不动别的格子位置
        val grow = if (hover) (((size * HOVER_SCALE) - size).roundToInt() / 2) else 0
        drawNineSlice(
            context, DIALOG_BACKGROUND_TEXTURE,
            x - grow, y - grow, size + grow * 2, size + grow * 2, 0, DIALOG_BACKGROUND_TEX_H
        )

        val slot = slotOfCell(cell)
        if (slot < 0) return
        val entry = entryAt(slot) ?: return
        val icon = cellIcon(slot, entry)

        // 内容跟着**当前画出来的那个格子**走（悬停时 drawSize 就是放大后的边长）——
        // 只放大底图、内容原地不动的话，放大后的格子里精灵会缩在中间显得很小。
        // ⚠ 价格与数量是例外：它们只"贴边"不放大（字号不动），文字用矩阵缩放会发糊。
        val drawSize = size + grow * 2
        val drawX = x - grow
        val drawY = y - grow

        // 图标区（算法见 [iconRectOf]；领取飞入动画的起点也用同一份，别各算一遍）
        val iconRect = iconRectOf(drawX, drawY, drawSize)
        val iconX = iconRect.x
        val iconY = iconRect.y
        val iconSize = iconRect.size

        val renderable = icon.renderable
        val state = icon.state
        if (renderable != null && state != null) {
            // 槽背景：与列表 / 弹窗里的精灵图标同一张底图（模型直接浮在格子上会显得没有底座）
            drawPokemonSlot(context, iconX, iconY, iconSize)
            val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
            try {
                PokemonIconRenderer.draw(context, renderable, state, iconX, iconY, iconSize, delta, useFloat)
            } catch (_: Exception) {
            }
        } else {
            icon.stack?.let { stack ->
                val scale = iconSize / 16f
                context.matrices.push()
                // 中心与精灵图标**同一个**（iconX/iconY 已经按当前格子算过，别再拿正常格子算一遍）
                context.matrices.translate((iconX + iconSize / 2f).toDouble(), (iconY + iconSize / 2f).toDouble(), 0.0)
                context.matrices.scale(scale, scale, 1f)
                // 商城格子里的物品图标同样带耐久条 —— 买家在格子上就该看出快坏了
                drawItemWithBar(context, stack, -8, -8)
                context.matrices.pop()
            }
        }

        // 价格：格子底部居中（走全模组统一的金额文本，自带 RP 图标字形）。
        // ⚠ 贴的是**当前画出来的那个格子**的下边框 —— 悬停时格子从 `y-grow` 放大到 `y+size+grow`，
        //   底边随之往下走，价格也得跟着，才是「始终紧贴格子内边框」；
        //   按正常格子的坐标写死的话，一悬停价格就浮在离下边框一截的位置（2026-09-20 用户指出）。
        //   上边距 12 = 文字高 8 + 离下边框 4（原来 10 会压到边框线上）
        val bottom = y + size + grow
        // 领取店：格子下方显示「可领 N」（价格对领取店没有意义）；0 = 已领完，画灰
        if (isClaimShop) {
            val remaining = claimRemaining(entry)
            if (remaining >= 0) {
                // 格子里**只放短标签**（用户 2026-10-24：英文 `Claimable` 放不下；「Claim N」又是动作语义）
                // —— 短标签是「N left / 可领 N」，完整的「Claimable: N」放在**悬停窗最后一行**
                //   （见 [drawCellTooltip]）。窄到连短标签都放不下时退回纯数字。
                val label = Text.translatable("cobblemarket.points.store.claim_remaining_short", remaining)
                val text = if (textRenderer.getWidth(label) <= size - 2) label
                else Text.literal(remaining.toString())
                context.drawCenteredTextWithShadow(
                    textRenderer,
                    text,
                    // -1：绿字看着比白色价格更贴底边（用户 2026-10-23 报压住边框），单独上移 1px
                    x + size / 2, bottom - PRICE_BOTTOM_GAP - 1,
                    if (remaining > 0) 0x55FF55 else 0x777777
                )
            }
        } else {
            context.drawCenteredTextWithShadow(
                textRenderer,
                // 金额走**缩写**（`9,999,999` → `9.9M`）：格子就这么宽，完整价格会长到顶破整个格子
                //（用户 2026-09-20 报）。与列表界面同一个口径（[formatPriceShortLong]，它的注释就写着
                //「小格子用的价格缩写」），网格这边当初漏了。
                // ⚠ 缩写串是**字符串**喂进 amountText 的：`goldAmount → formatAmount` 对非数值类型
                //   **原样输出**（不套千分位），所以不会被二次加工；RP 图标仍由 unitText 补，没走字符串拼接
                CurrencyService.amountText(formatPriceShortLong(entry.price), shopCurrency),
                x + size / 2, bottom - PRICE_BOTTOM_GAP, 0xFFFFFF
            )
        }

        // 「非法」角标（服主在编辑窗标过的那一格）：**左上角一枚印章图**（右上角给库存角标 ×N 占了）。
        // 用户 2026-09-30 定：标过的必须**在格子上就看得出来**，否则服主事后想不起来哪只标了。
        // ⚠ 2026-09-30 二次改：原先画的是**红色「非法」两个字**，用户嫌丑 ⇒ 换成印章图。
        //   顺带解决了一个老问题：文字在窄格子下必须截断（英文 `Illegal` 42px 塞不进 30px 的格子），
        //   而图标只按比例缩放，**任何格子尺寸都不会溢出到邻格**。
        // ⚠ 2026-10-02 三次改：**收口到共用入口**（[EntryBadgeRenderer.drawIllegalBadgeLeft]）——
        //   原先这里自己手写 matrices 缩放、尺寸按 10~16 钳制、贴边 4px，与全项目口径各写一套
        //   （比例/边距调一次要改两处）。现在尺寸与边距都走共用常量，全项目只有"贴哪一角"不同。
        if (entry.pokemon?.illegal == true) {
            EntryBadgeRenderer.drawIllegalBadgeLeft(context, x - grow, y - grow, size)
        }

        // 库存角标：**只有限量格才画**（不限量是默认玩法，画个 ∞ 只是碍眼）；
        // 售罄时变红 0，格子**保留**（服主可以补货或换内容）。贴当前格子的右上角内边框，
        // **上距 = 右距**（同一个常量，见 [STOCK_BADGE_INSET]）
        if (entry.stock != ShopEntry.UNLIMITED) {
            // `×N` 与**物品市场的库存角标同一套写法**（`ItemMarketScreen` 那句 `"×${entry.count}"`）——
            // 光画一个数字，得像物品市场那样看过才知道它是什么（用户 2026-09-25 指出该照它来）
            val badge = "×${entry.stock}"
            context.drawTextWithShadow(
                textRenderer, Text.literal(badge),
                // ⚠ **右对齐**：这是右上角的角标，`drawTextWithShadow` 却是左对齐 ——
                //   按内边距当起点的话，数字会从那儿往右长，两位数就顶出格子（2026-09-25 用户实测）
                x + size + grow - STOCK_BADGE_INSET - textRenderer.getWidth(badge), y - grow + STOCK_BADGE_INSET,
                if (entry.isSoldOut()) 0xFF5555 else 0xFFFFFF
            )
        }
    }

    /** 格子图标的构造结果（缓存在 [cellIcons]） */
    private class CellIcon(
        val entry: ShopEntry,
        val renderable: RenderablePokemon? = null,
        val state: FloatingState? = null,
        val stack: ItemStack? = null
    )

    private val cellIcons = mutableMapOf<Int, CellIcon>()

    /**
     * 取格子的图标（没有就构造一份缓存）。
     *
     * ⚠ 缓存比对用**引用**（`===`）：快照一到就会重建整个 [ShopEntry] 对象树，
     * 引用变了就重建图标 —— 服主编辑完商品立刻能看到新预览，不用重开界面。
     */
    private fun cellIcon(index: Int, entry: ShopEntry): CellIcon {
        val cached = cellIcons[index]
        if (cached != null && cached.entry === entry) return cached
        val built = buildCellIcon(entry)
        cellIcons[index] = built
        return built
    }

    private fun buildCellIcon(entry: ShopEntry): CellIcon = when (entry.kind) {
        ShopEntry.KIND_POKEMON -> {
            val spec = entry.pokemon
            val species = spec?.species?.let { Identifier.tryParse(it) }
                ?.let { PokemonSpecies.getByIdentifier(it) }
            if (spec == null || species == null) CellIcon(entry)
            // 预览渲染：**固定闪光出闪光形态；概率闪光出默认形态**（概率只在悬停窗标出来）
            else CellIcon(
                entry = entry,
                // 携带物一起带上（同编辑预览）；**可见性关着的商品，格子里也不显示** ——
                // 那正是「别人看不到它带了什么」该有的效果
                renderable = RenderablePokemon(
                    species, spec.aspects.toMutableSet(),
                    if (spec.heldItemVisible) stackOfSpec(spec.heldItemSpec) else ItemStack.EMPTY
                ),
                state = FloatingState()
            )
        }

        ShopEntry.KIND_ITEM -> {
            val spec = entry.item
            CellIcon(entry, stack = spec?.let { rebuildItemStack(it) }?.takeIf { !it.isEmpty })
        }

        else -> CellIcon(entry)
    }

    /** 按规格重建物品栈（含组件）——重建不出来就是空图标，绝不崩 */
    private fun rebuildItemStack(spec: ShopItemSpec): ItemStack? = runCatching {
        val lookup = client?.world?.registryManager ?: return null
        ItemStack.fromNbtOrEmpty(
            lookup,
            net.minecraft.nbt.NbtCompound().apply {
                putString("id", spec.itemId)
                putInt("count", 1)
                spec.componentsSpec?.let { put("components", it) }
            }
        )
    }.getOrNull()

    override fun shouldPause() = false

    private companion object {
        val BACKGROUND = Identifier.of("cobblemarket", "textures/gui/background/points_market_background.png")
        const val TEX_W = 1280
        const val TEX_H = 720

        /** 四周留白：给游戏画面留一圈（方案拍板 4%） */
        const val MARGIN_RATIO = 0.04f

        /**
         * 原图边框厚度：贴边控件（返回按钮、标题）避开它，别压到装饰条上。
         * 实测（扫描内容区包围盒）：左右 59px、顶部约 85px —— 取 90 统一兜住最厚的一边。
         */
        const val TEX_BORDER = 90

        /** 输入框字符上限：外部码 32 位按 4 位分组 = 39 字符，留一点余量 */
        const val MAX_CODE_INPUT = 48

        /** 标题距背景顶（原图 1280×720 口径，用户 2026-09-18 定） */
        const val TITLE_TOP = 28

        /** 搜索防抖：停手这么久才发包（必须 > 服务端 500ms 读节流窗口） */
        const val SEARCH_DEBOUNCE_MS = 650L
        /** 客户端最多带多少个名称命中的候选 id（与服务端 `MAX_SEARCH_IDS` 对齐） */
        const val SEARCH_ID_LIMIT = 512

        /** 返回按钮：底边距背景底（原图口径）、按钮尺寸 */
        const val BACK_BOTTOM = 75
        const val BACK_W = 50
        const val BACK_H = 16

        /**
         * 兑换区：输入框与按钮尺寸（输入框靠内容区左上角摆放）。
         * ⚠ 120 是**上限**，不是定值：GUI scale 调大后逻辑分辨率会小到放不下这一行，
         * 那时按剩余空间压缩（见 init 里的 `fieldW`）。下限给 40（约 6 个字符，输入框本来就会横向滚动）。
         */
        const val REDEEM_FIELD_W = 120

        /**
         * 兑换框的**下限宽度**。GUI scale 自动（逻辑宽只有 400 出头）时，这一行剩下的空间就是它 ——
         * 从 40 收到 24：那一档本来就只看得见三四个字符（输入框自己会横向滚动），
         * 但少了这 16px，档位 tab 就会顶到「兑换记录」上（用户实测的重叠就是这么来的）。
         */
        const val MIN_REDEEM_FIELD_W = 24

        /** 兑换行各控件之间的间隙（按钮间距规则：至少 4px） */
        const val ROW_GAP = 4

        /**
         * 兑换按钮宽 48：够放下英文 "Redeem"（约 36px）与中文「兑换」（约 18px），
         * 再宽两边就是空白 —— 2026-09-18 用户指出 56 太宽、还会挤到居中的 tab。
         */
        const val REDEEM_BUTTON_W = 48

        /**
         * 商品区网格：**行列固定、格子尺寸自适应**（铺满可用空间）。
         *
         * ⚠ 反过来做（格子固定、行列自适应）不行 —— 2026-09-18 用户实测：格子在大窗口下显得小，
         * 而且高度除不尽时会在上下留下大片空余（截图里 3 行只占了一半高度）。
         * 固定行列后格子会撑满每一寸空间，且格子尺寸随窗口一起变大。
         */
        const val GRID_COLS = 8
        const val GRID_ROWS = 4

        /**
         * 「拿行数换格子大小」的门槛：**按宽度算的格子比按高度算的大出这个比例时**，
         * 才放弃 [GRID_ROWS] 行、改成按宽度铺满 + 行数自适应。
         *
         * 为什么要分情况：可用区宽高比（1080p 下 2.14）比 8×4 网格的（2.06）还宽一点，
         * 所以「按宽铺满」必然掉一行。大分辨率下那点差距只换来 4% 的格子，不值；
         * 小分辨率（GUI scale 调大）下能换来 40% —— 480×270 时按高度算每格只有 27px，
         * 图标小、左右还白空一大片（2026-09-18 用户实测）。
         */
        const val GRID_W_ROWS_TRADE_RATIO = 1.1f

        /**
         * 间隔 = 格子 × 该比例（不写死像素：大小窗口下观感才一致）。
         * 取 0.3 是因为**悬停放大**要留余量 —— 放大 [HOVER_SCALE] 后每边外扩 (0.15/2)=7.5% 的格子，
         * 而间隔是 30%，扣掉两边外扩还剩 15% ⇒ **不会碰到上下左右的邻居**（用户要的效果）。
         */
        const val GRID_GAP_RATIO = 0.3f

        /** 悬停放大倍率：鼠标所在格子放大到这么大，并且**最后绘制**（浮在邻居之上） */
        const val HOVER_SCALE = 1.15f

        /** 格子内容离当前格子下边框的距离（含文字自身高度 8：即文字底边离边框 4） */
        const val PRICE_BOTTOM_GAP = 12

        /** 图标区上限：占当前格子边长的比例（大格子里图标不会顶满） */
        const val ICON_SIZE_RATIO = 0.7f

        /** 图标区上边距占边长的比例 */
        const val ICON_TOP_RATIO = 0.08f

        /**
         * 图标上边距在 [ICON_TOP_RATIO] 之外**再下移的像素** —— 避开格子上边框（才 6px 宽），
         * 给 3D 模型那 4px 的向上溢出留出地方。太大图标会明显变小，见 renderCell 的 iconTop。
         */
        const val ICON_TOP_NUDGE = 4

        /** 图标最小边长（再小就看不清了，宁可挤一点也不缩没） */
        const val MIN_ICON_SIZE = 8

        /**
         * 库存角标距格子**右上角那两条边**的距离（右距 = 上距，共用这一个值）。
         *
         * 判据（用户 2026-09-25 定）：角标到格子上边框的距离，与到右边框的距离**要一样**。
         * ⚠ 参考物品市场（`ItemMarketScreen` 的 `x + slotSize - 2 - 宽, y + 3`）时**别只抄它的 2** ——
         *   那边格子小、上距本就取 3；本界面按「先下移 1px 避开格子上边框」定 4，右边距跟着取同一个值。
         */
        const val STOCK_BADGE_INSET = 4

        /** 网格与上下两条分割线的间距（逻辑像素） */
        const val GRID_PADDING = 6

        /** 精灵 / 物品 tab：尺寸与两者之间的间隔 */
        const val TAB_W = 56
        const val TAB_H = 16
        const val TAB_GAP = 4

        /** 翻页按钮尺寸（照 MarketScreen 的搬来） */
        const val PAGE_BTN_W = 80
        const val PAGE_BTN_H = 20

        /**
         * 商品快照请求的**客户端限流**间隔：与服务端 `RequestThrottle.READ_INTERVAL_MS` 同值 ——
         * 发得比它密只会被静默丢掉，白费带宽（见 [tick] 的补发逻辑）。
         */
        const val PAGE_REQUEST_INTERVAL_MS = 500L

        /**
         * 右上角「购买 RP」入口宽度。
         *
         * ⚠ 2026-09-19 用户实测：**72 太宽**（文字只占 30~35px，两边全是空白），
         * GUI scale 调成自动时这一行挤不下，档位 tab 被兜底逻辑顶到「兑换记录」上（重叠）。
         * 收到 44：中文「购买 ▣」约 30px、英文 "Buy ▣" 约 35px，两边各留 4~7px。
         */
        const val BUY_ENTRY_W = 44

        /**
         * 「兑换记录」入口宽度（**与购买入口不同宽**）：英文 "History" 约 42px / 中文「兑换记录」36px，
         * 留 5~8px 内边距 → 52（原来跟着购买入口吃 72，白占 20px）。
         */
        const val RECORD_ENTRY_W = 52

        /** 红色分割线左右各避开的背景边框宽度（原图口径，用户 2026-09-18 定 78） */
        const val DIVIDER_BORDER_X = 78

        /** 在「避开边框」的基础上左右各再延长的量：**逻辑像素**（用户 2026-09-18 调） */
        const val DIVIDER_EXTEND = 2

        /** 兑换行高（输入框与按钮）：**逻辑像素** */
        const val ROW_H = 16

        /** 分割线与相邻控件（兑换行 / 返回按钮）的空隙：**逻辑像素** */
        const val DIVIDER_GAP = 6

        /**
         * 兑换行相对内容区顶再下移的量（逻辑像素）。
         * 用户 2026-09-18：照入口界面「求购单按钮顶边距红色分割线」那个间距（3px）往下挪一点。
         * 上分割线不跟着动 ⇒ 两者间距收窄到 `DIVIDER_GAP − ROW_TOP_OFFSET` = 3px。
         */
        const val ROW_TOP_OFFSET = 3

        // ── OP 添加 / 编辑窗口（尺寸固定：18 个字段两列排下来就是这么多，**不随内容伸缩**）──

        /**
         * 窗口高度的**设计值**（放不下按屏幕钳制，见 [editDialogHeight]）。
         *
         * 算式：`44`（[EDIT_ROWS_TOP]）+ 内容 9 行的最后一个行底（`8×19 + 16 = 168`）
         * + `6`（技能行与底部那组的分组间距）+ `44`（[EDIT_BOTTOM_BLOCK]）= **262**。
         *
         * ⚠ 原值 288 比内容高出 32px，全空在技能行与价格行之间（2026-09-20 用户指出，已收紧）。
         * 巧合但正好：自动 GUI（1080p）逻辑屏高 270 ⇒ `270 − 8 = 262`，
         * 所以**大屏小屏现在完全一致**（以前是小屏被钳到 262 才是理想布局）。
         */
        // ⚠ 2026-09-26：字段表加了「极巨等级」⇒ 多一行 ⇒ 这里必须 +EDIT_ROW_STEP(19)。
        //   高度与底部块（价格行 / 按钮行，都基于 editDialogHeight() − EDIT_BOTTOM_BLOCK）是**成对**的：
        //   只加字段不改这里，中间内容会整体下移 19px 而底部不动，挤在一起。
        const val EDIT_DIALOG_H = 281

        /**
         * **购买窗**比编辑窗高出的部分（2026-10-28）：多一行「卡片价格优惠」提示。
         *
         * 值是 **18** —— 那行里要放 **16×16 的卡图标**（物品图标标准尺寸，放 12px 用户实测「卡片好小」），
         * 上下各留一点空隙。
         * ⚠ 三处必须一起改：窗口高度（[editDialogHeight]）、底部块（[editBottomBlock]）、
         *   按钮行（[editButtonRowY] 购买窗 `priceRow + 36`）—— 只改一处会压出边框或按钮贴底。
         */
        const val BUY_DISCOUNT_EXTRA_H = 18

        /**
         * 窗口宽度的上下限：宽度**跟着 RP 商城背景走**（见 [editDialogW]），
         * 只在这两头收一下 —— 大屏上别摊太宽，小屏上别窄到字都放不下。
         */
        const val EDIT_DIALOG_MAX_W = 560
        const val EDIT_DIALOG_MIN_W = 300

        const val EDIT_PAD = 10

        /** 列宽的下限：列数由窗口宽度算出来（见 [editCols]），列宽不小于这个值 */
        const val EDIT_MIN_COL_W = 66
        const val EDIT_COL_GAP = 9

        /**
         * 行高与行距：**16 / 19**（标准按钮高度）。
         *
         * ⚠ 上两轮为了塞进 270 的逻辑屏高，行高压到过 14 —— 太扁了：物品图标是 16×16，
         * 顶在 14 高的按钮里上下全无余量（2026-09-20 用户指出）。宁可让矮屏滚一点点，
         * 也不要把按钮压扁：高屏上窗口本来就放得下，只有逻辑屏不足 279 时才滚十几像素。
         */
        const val EDIT_ROW_H = 16
        const val EDIT_ROW_STEP = 19

        /** 顺序表里的**换行标记**（"另起一行"），见 [editFieldOrder] */
        const val FIELD_BREAK = "\n"

        /** 窗口横向的**总格数**：左 3 格字段 + 右 3 格给预览图与个体/努力值（用户 2026-09-20 定的布局） */
        const val EDIT_COLS = 6

        /** 个体值 / 努力值在右侧区的起始行（个体值第 1 行与「性格」同行，努力值末行与「亲密度」同行） */
        const val EDIT_ROW_STAT = 2

        /** 携带物搜索一次最多给多少个候选（搜「球」这类宽词命中很多，够翻就行） */
        const val MAX_CARRY_CANDIDATES = 200

        /** 物种搜索行（相对窗口顶）：输入框 + 两枚候选箭头 + 「1/3」计数，位置与宽度都随窗口宽算 */
        const val EDIT_SPECIES_Y = 24
        const val EDIT_ARROW_W = 18

        /** 清空按钮（搜索框右侧第一个）的宽度 —— 与两枚候选箭头同宽同高，四个排成一溜 */
        const val SPECIES_CLEAR_W = 18

        /** 「1/3」候选计数占的宽度（右端与物种行右缘对齐，见 [editSpeciesRightX]） */
        const val SPECIES_COUNT_W = 26



        /** 字段网格从这一行开始（相对窗口顶）—— 上面是标题 / 预览 / 物种行（固定不滚） */
        const val EDIT_ROWS_TOP = 44

        /**
         * 字段网格**没有固定行号表** —— 位置一律由 [editFieldOrder] + [editCols] 流式算出来
         *（列数随窗口宽度变，写死行号必然错位）。
         * 价格/库存也不在这个网格里，它们钉在窗口底部（见 [editPriceRowY]）。
         */

        /** 六项属性的占位名（与黑名单的个体值输入框同一套缩写；努力值那组加 EV- 前缀区分） */
        val STAT_LABELS = listOf("HP", "ATK", "DEF", "SPA", "SPD", "SPE")
        const val STAT_COUNT = 6

        /** 底部固定块的高度：价格/库存行(16) + 间距(2) + 按钮行(18) + 底边距(8) */
        const val EDIT_BOTTOM_BLOCK = 44

        // ── 购买窗（普通玩家侧）──

        /** 购买窗底部按钮的宽度（「购买」「取消」并排，右边距与编辑窗的按钮行对齐） */
        const val BUY_BTN_W = 60

        /** 两个并排按钮之间的空隙（按钮间距至少 4px，见项目约定） */
        const val BUTTON_GAP = 4

        /** 数量输入框的宽度（最多 4 位数，2304 是服务端上限） */
        const val BUY_COUNT_W = 40

        /** 「数量不合法」红字的显示时长 */
        const val BUY_HINT_MS = 2500L

        /** 「商品刚被编辑过」那条界面内提示的显示时长（比兑换结果短一点，它只是告知） */
        const val STALE_MSG_MS = 4000L

        /**
         * 亲密度输入框让出标签后**至少**要留这么宽。
         * 亲密度最大 255 = **3 位数**，3 字符 × 6px = 18px，再留 2px 余量 ⇒ 20。
         * 不够就干脆不画标签（见 [friendshipLabelW]）—— 硬画会把输入框挤得放不下数字。
         */
        const val MIN_FRIENDSHIP_INPUT_W = 20

        /**
         * 「初始训练家」输入框让出标签后**至少**要留这么宽。
         * 它是**文本**框：短名字「サトシ」3 个字 = 27px（中文/假名 9px 一个），
         * 取 24px 让 3-4 个字符能完整露出来（更长的靠输入框自带滚动）。
         * 不够就干脆不画标签（见 [otLabelW]）—— 硬画会把输入框挤得几乎看不见。
         */
        const val MIN_OT_INPUT_W = 24

        /** 证章悬停窗：每个图标占的格子边长（与 `EntryBadgeRenderer.drawMarksRow` 的 12px 同口径） */
        const val MARK_CELL = 12

        /** 证章悬停窗的内边距 */
        const val MARK_TIP_PAD = 5

        /**
         * 添加窗右上角「快照」按钮的宽度。
         * 中文「快照」36px、英文 `Snapshots` 54px —— 取宽的再加边距（项目一贯的"按最宽文案定宽"）。
         */
        const val EDIT_SNAPSHOT_W = 62

        /**
         * 按钮组最左那枚「形态需要前置条件」警告的边长 —— 16×16 见方，里面画的是
         * **符号 `⚠`**（不是图标贴图，见 [buildEditDialog]；用户 2026-09-30 从蓝色 info 图标改过来）。
         *
         * ⚠ 位置在**按钮组的最左端**（原先跟着 `confirmX` 贴着「添加」，2026-09-30 加「非法」时
         *   整组改成居中排，它就成了组里第一枚）。
         * ⚠ 宽度用不着纠结：窗口拉到下限 [EDIT_DIALOG_MIN_W] 时，整组仍有余量
         *   —— 一个符号任何档位都放得下（早先那版放右上角时，那条带在窄窗口下只剩 ~64px）。
         */
        const val EDIT_GATED_INFO_W = 16

        /** 警告符号与它右边那枚「非法」之间的空隙（沿用原先"info 距添加 4px"那个口径） */
        const val EDIT_INFO_GAP = 4

        /**
         * 「形态需要前置条件」那枚警告**点亮时的红** —— 与关市横幅 / 售罄的 0 / EV 段 / 商城格子的
         * 「非法」角标**同一个红**（那行本来就是用 0xFF5555 画的红字，符号跟着一起红
         * ⇒ "红色三角"是**染**出来的，字形本身没有颜色）。
         *
         * ⚠ 只在**草稿里真选中那类形态时**才用它；平时是常规白 —— 理由见 [render] 里刷色那段。
         */
        const val GATED_WARN_COLOR = 0xFF5555

        /**
         * 底部那枚「非法」开关的**边长**（正方形图标按钮，名字沿用 info 那枚的 "W" 写法）。
         *
         * 取 **18 = 底部按钮行的高**（[EDIT_BUTTON_H]）⇒ 与「添加 / 取消」一排齐平。
         * ⚠ 比 [EDIT_GATED_INFO_W]（16）大 2px 是有意的：那枚只是**看**的提示，
         *   这枚是要**点**的开关，手感上该大一点。
         */
        const val EDIT_ILLEGAL_W = 18

        /**
         * 右上角那枚「解锁特性 / 技能池」开关的**边长**（正方形图标按钮）——
         * **紧挨快照左侧**（间距 [BUTTON_GAP]）、与快照同顶边（[editRemoveY]）。
         *
         * 取 16 = 与快照同高（[EDIT_ROW_H]）⇒ 一排齐平。
         * ⚠ 它**没有文案**，也不看文案宽窄：状态由 [FREE_PICK_LOCK_ICON] /
         *   [FREE_PICK_UNLOCK_ICON] 两张图自己表达（用户 2026-09-30 定）。
         */
        const val EDIT_FREE_SIZE = 16

        /** 快照弹窗里「保存当前配置」按钮的宽度（中文 54px、英文 `Save Current` 72px，取宽的） */
        const val SNAPSHOT_SAVE_BTN_W = 80

        /** 快照列表每行的高度 */
        const val SNAPSHOT_ROW_H = 16

        /**
         * 快照列表底部给「还有 N 条（滚轮上下翻）」那行灰字**预留**的高度。
         * 灰字固定画在 `snapshotCloseRowY() - 12`（它自身 8px 高，距底部按钮行 4px，
         * 合按钮间距那条约 4px 空隙），这 12px 就是它占的地 ——
         * 不留出来最后一条就压在它身上，见 [snapshotVisibleRows]。
         */
        const val SNAPSHOT_MORE_RESERVE = 12

        /**
         * 六项个体值 / 努力值的属性缩写。
         * ⚠ 刻意**不翻译**：`HP/Atk/Def/SpA/SpD/Spe` 是游戏圈通用写法（与项目既有的
         * 「IV 占位符不翻译」同一口径），翻译反而对不上 Cobblemon 队伍详情里的叫法。
         */
        val STAT_ABBR = listOf("HP", "Atk", "Def", "SpA", "SpD", "Spe")

        /**
         * 六项个体值的**行色** —— 与市场悬停窗 / 列表行**同一张色表**
         * （`EntryBadgeRenderer.drawInfoLines` 里那六行，用户 2026-09-25 要求照它来）。
         */
        val STAT_ROW_COLORS = listOf(0x66FF66, 0xFF6666, 0xFFCC66, 0x6699FF, 0x66FF99, 0xFF99FF)

        /** 六项努力值的行色：一律**红**（与悬停窗里 EV 段同色 `Formatting.RED`） */
        const val EV_ROW_COLOR = 0xFF5555

        /** 底部按钮行（高度收到 18 才和顶部新增的预览行对得上账，见 EDIT_ROW_STEP 的注释） */
        const val EDIT_BUTTON_W = 80
        const val EDIT_BUTTON_H = 18

        /** 「下架」按钮（只在编辑已有内容时出现）的宽度；位置右上角、**距顶 = 距右**，见 [editRemoveY] */
        const val EDIT_REMOVE_W = 44

        /** 「标签 + 输入框」的列内布局（价格 / 库存那两格） */
        const val EDIT_LABEL_W = 32

        /** 购买窗「数量：」标签距第 3 格起点的空隙 —— 别贴死左边那个库存输入框（用户 2026-09-26） */
        const val BUY_COUNT_LABEL_GAP = 8

        /** 预览大图裁剪框离弹窗边（上 / 右）留的空隙，见 [editPreviewClip] */
        const val PREVIEW_CLIP_MARGIN = 2

        /** 展开列表上方的搜索框（只在列表展开期间存在，关列表时一并移除） */
        const val OPTION_SEARCH_Y = 28

        /**
         * 技能列表顶上那排**「来源」筛选按钮**（在搜索框底下，见 [moveFilterChips]）。
         *
         * ⚠ 高度 14 贴着九宫格的下限（`NINE_SLICE_BORDER = 6`，上下边框各 6 ⇒ 最少 12）——
         *   再矮就不是圆角卡片、成了一条线。
         */
        const val OPTION_FILTER_Y = 46
        const val OPTION_FILTER_H = 14

        /** 筛选按钮的左右内边距 / 按钮间距 / 与列表第一行之间的空隙 */
        const val OPTION_FILTER_PAD = 5
        const val OPTION_FILTER_GAP = 4
        const val OPTION_FILTER_LEAD = 6

        /** 提交失败提示在弹窗内停留的毫秒数（与兑换结果提示同档） */
        const val EDIT_HINT_MS = 5000L
        const val OPTION_SEARCH_H = 16

        /**
         * 展开式选项列表：从搜索框下面起、行距 18px，**行数按窗口高度算满**（见 [optionListRows]）——
         * 写死行数会在底部空出一大片，而球种这类字段有几十项（2026-09-20 用户实测指出）。
         */
        const val OPTION_LIST_TOP = 48

        /** 选项行：行高 18 是为了让 16×16 的物品图标**上下各留 1 像素**（顶格会贴到行边缘） */
        const val OPTION_ROW_STEP = 20
        const val OPTION_ROW_H = 18

        /** 行首物品图标的宽度（球种 / 携带物用） */
        const val OPTION_ROW_ICON_W = 16

        /** 列表底部留给「返回」按钮的区域：按钮 20 + 间距 8 + 底边距 8 */
        const val OPTION_LIST_BOTTOM = 36

        /**
         * 球种的**默认值**：普通精灵球（用户 2026-09-20 定）。
         *
         * 原先空值走「随机」、提交时写 `null` ⇒ 生成的精灵球种不明（Cobblemon 内部会给个默认，
         * 但说不清是哪个）。现在三处都落这个值：新建（[resetDraft]）、编辑老数据（[fillDraft]）、
         * 提交兜底（[buildPokemonSpec]）—— **「不设置」就等于精灵球**，界面上也不再出现「随机」。
         */
        const val DEFAULT_BALL_ID = "cobblemon:poke_ball"

        /**
         * 努力值的**总和**上限 —— 取 **508**，不是 Cobblemon 的硬上限 510。
         *
         * 「**每 4 点 EV 才换 1 点能力值**」，所以 510 里最后那 2 点换不出任何东西：
         * `252/252/6`（510）与 `252/252/4`（508）的**战力完全相同**。
         * 卡 510 的唯一后果是让服主以为自己配满了；卡 508 不违反游戏规则（510 只是"存得下"），
         * 只是不给白填的机会。口径与 `oak_tips` 里那条 EV 说明一致（2026-09-20 用户指出）。
         */
        const val EV_TOTAL_MAX = 508

        /** 新建商品时**预填**的等级与亲密度（用户 2026-09-20 定）—— 清掉即回「随机 / 默认」 */
        const val DEFAULT_LEVEL = 1
        const val DEFAULT_FRIENDSHIP = 50

        // 字段按钮的 key（刷新文案与统一隐藏用）
        const val KEY_SHINY = "shiny"
        const val KEY_FORM = "form"
        const val KEY_GENDER = "gender"
        const val KEY_NATURE = "nature"
        const val KEY_ABILITY = "ability"
        const val KEY_BALL = "ball"
        const val KEY_SIZE = "size"
        const val KEY_ALPHA = "alpha"
        const val KEY_TERA = "tera"
        const val KEY_GMAX = "gmax"
        const val KEY_TRADEABLE = "tradeable"
        const val KEY_HELD = "held"
        const val KEY_MIN_IVS = "min_ivs"
        const val KEY_HELD_VISIBLE = "held_visible"
        const val KEY_MARKS = "marks"

        /** 新增的「变体」按钮：species feature 维度（花纹 / 帽子 / 颜色…），**与形态按钮完全分开** */
        const val KEY_FEATURE = "feature"
        const val KEY_SKILL = "skill"

        /**
         * Cobblemon 的**头目之证** id —— `Pokemon.isAlpha` 的 setter 里会自动 `exchangeMark` 它，
         * 所以服主在证章列表里勾没勾，只要精灵是头目就一定会带。见 [effectiveMarksOf]。
         */
        const val ALPHA_MARK_ID = "cobblemon:mark_alpha"


        // 展开式选项列表的种类（技能带槽位后缀，如 "skill0"）
        const val OPT_FORM = "form"
        const val OPT_NATURE = "nature"
        const val OPT_ABILITY = "ability"
        const val OPT_BALL = "ball"
        const val OPT_TERA = "tera"
        const val OPT_CARRY = "carry"
        const val OPT_MARKS = "marks"

        /** 「变体」列表：**按 feature 分节、组内互斥、组间共存**（见 [buildOptionItems]） */
        const val OPT_FEATURE = "feature"

        /**
         * 物品窗的**物品候选列表** —— **搜索式**，与 [OPT_CARRY] 同一套机制（见 [buildItemCandidates]）。
         * ⚠ 触发方式已改：不再是"点按钮弹列表"，而是界面上那条搜索框**边打边搜**（见 [updateItemSearch]）
         */
        const val OPT_ITEM = "item"

        /** 物品窗搜索框**左侧**那枚小手按钮的宽度（黑名单同款：16×16，贴在搜索框左边） */
        const val OPTION_HELD_BTN_W = 16

        /** 物品大图**左侧**那列词条与大图 / 左侧分栏之间留的空隙 */
        const val EDIT_ITEM_INFO_GAP = 6

        /** 词条行高 —— 与其余界面的悬停词条同档（10px），别各写一份 */
        const val EDIT_ITEM_INFO_LINE_H = 10

        /** 商品候选一次最多列几条（搜索词太泛时防刷屏 + 防拖慢渲染，同 [MAX_CARRY_CANDIDATES]） */
        const val MAX_ITEM_CANDIDATES = 200
        const val OPT_SKILL = "skill"

        // 性别 / 头目的**图标**（沿用项目既有素材，别再找新的）：
        // ♂♂♀♀ 是 Cobblemon 的 pc 图标；alpha 徽章与列表行里用的是同一张
        val GENDER_ICON_MALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
        val GENDER_ICON_FEMALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
        val ALPHA_BADGE_ICON = Identifier.of("cobblemon", "textures/gui/summary/icon_size_alpha.png")

        /**
         * 「解锁特性 / 技能池」那枚开关的两个图标（**用户 2026-09-30 提供**；48×48，
         * 按 `iconScale ≈ 1/3` 缩到 16px 显示）。
         *
         * 闭锁 = **未选中**（候选表照旧按物种限）、开锁 = **选中**（放开成全部特性 / 技能）。
         * ⚠ 状态就靠换这张图表达，不叠 `selectedVisual`、也没有文字 —— 见 [EDIT_FREE_SIZE]。
         */
        val FREE_PICK_LOCK_ICON = Identifier.of("cobblemarket", "textures/gui/pokemon/lock.png")
        val FREE_PICK_UNLOCK_ICON = Identifier.of("cobblemarket", "textures/gui/pokemon/unlock.png")

        /**
         * 「非法」的印章图（48×48）—— **只声明一份**（在 [EntryBadgeRenderer] 里），这里只是转发：
         * - 编辑窗底部那枚「非法」开关（按钮图标）
         * - **商城格子左上角**的角标（缩放后画在格子上）
         * - ⭐ **市场 / 拍卖 / 管理端的行角标**（`EntryBadgeRenderer.drawIllegalBadge`，贴**右上角**）
         */
        val ILLEGAL_BADGE_ICON = EntryBadgeRenderer.ILLEGAL_BADGE_TEX

        /**
         * 头目徽章的**原始尺寸与显示缩放** —— 编辑窗按钮与购买窗只读行**共用这一份**，别各写一遍
         * （写了就会漂移，本项目"同一样东西写两遍"的坑踩过多次）。
         * ⚠ 缩放必须走 `matrices`：`drawTexture` 的 width/height 是**源图采样区域**，
         *   直接传缩放后的尺寸只会画出左上角一角（项目踩过的坑，同 `NineSliceButton` 的画法）。
         */
        const val ALPHA_BADGE_TEX_W = 37
        const val ALPHA_BADGE_TEX_H = 16
        const val ALPHA_BADGE_SCALE = 0.5f

        /** 性别档位（0 = 随机；值直接用 Cobblemon 的枚举名） */
        val GENDER_VALUES = listOf("", "MALE", "FEMALE", "GENDERLESS")

        /**
         * 闪光按钮的快捷档位（百分数原文，空串 = 不闪）：点一下就把这个值填进概率输入框。
         * 档位只是方便，真正的值以输入框为准 —— 服主想写 `0.5` 这种小数直接敲就行。
         */
        /**
         * 闪光档位按钮的**循环顺序**（用户 2026-09-20 定）：不闪 → 1% → 5% → 10% → 25% → 50% → 必闪。
         *
         * ⚠ 必闪排在**最后** —— 它是「100 及以上」那一档，语义上是这条链的终点，
         *   不该紧挨着「不闪」（原顺序是 `"", "100", "1"…`，点一下就从"不闪"直接跳到"必闪"）。
         */
        val SHINY_PRESETS = listOf("", "1", "5", "10", "25", "50", "100")

        /**
         * 闪光星标 —— **与列表行 / 悬停窗同一个记号**（[EntryBadgeRenderer] 里闪光一律追加 ` ★`）。
         * 颜色不在这里定：必闪金、不闪灰，见 [refreshShinyButton]。
         */
        const val SHINY_STAR = "★"

        /** 闪光拆成两格：按钮（显示当前档位）+ 概率输入框（支持小数）—— 挨着排，视觉上是一组 */
        const val KEY_SHINY_CHANCE = "shiny_chance"

        /** 六项个体值 / 努力值的字段前缀（`iv0`..`iv5` / `ev0`..`ev5`） */
        const val KEY_IV = "iv"
        const val KEY_EV = "ev"

        /** 两个输入框字段（在顺序表里有位置，但控件是输入框不是按钮） */
        const val KEY_LEVEL = "level"

        /**
         * 极巨等级（Cobblemon 官方 `Pokemon.dmaxLevel`，0 ~ `Cobblemon.config.maxDynamaxLevel`）。
         *
         * ⚠ 与 [KEY_GMAX]（超极巨化）**是两件事**，别混：
         * - **极巨化**：**所有**精灵都能，强度由等级决定 ⇒ **不受物种约束**
         * - **超极巨化**：只有特定物种能（`Species.canGmax()`）⇒ 那个按钮受物种约束
         */
        const val KEY_DMAX = "dmax"

        /**
         * 等级那一格的「Lv.」前缀词条 —— **与全项目等级行同一个**（`Lv.25` 那个写法，
         * 见 [EntryBadgeRenderer]），不另起词条，免得两处慢慢写岔。
         */
        const val LEVEL_LABEL_KEY = "cobblemarket.gui.lv"
        const val KEY_FRIENDSHIP = "friendship"

        /**
         * 「初始训练家」（配信）输入框 —— 在「保底 V 数」右边那格（第 7 行第 3 格，2026-10-02 加）。
         * 填了就按官网配信写成假训练家；留空 = 生成的精灵初训家还是买家本人（见 [ShopPokemonSpec.originalTrainer]）。
         */
        const val KEY_OT = "ot"
    }
}
