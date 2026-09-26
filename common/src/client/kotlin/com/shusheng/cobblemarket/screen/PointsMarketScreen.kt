package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.client.settings.ServerSettings
import com.cobblemon.mod.common.pokemon.PokemonSizeCategory
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import com.shusheng.cobblemarket.client.ClientPermissionCache
import com.shusheng.cobblemarket.client.ItemComponentsDisplay
import com.shusheng.cobblemarket.client.PointMarketCache
import com.shusheng.cobblemarket.client.PointsShopCache
import com.shusheng.cobblemarket.client.drawItemWithBar
import com.shusheng.cobblemarket.client.formatPriceShortLong
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.shusheng.cobblemarket.client.playButtonClickSound
import com.shusheng.cobblemarket.network.UpdatePointMarketStockPayload
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.currency.CurrencyService
import com.shusheng.cobblemarket.currency.MarketCurrency
import com.shusheng.cobblemarket.currency.PointShopState
import com.shusheng.cobblemarket.currency.ShopEntry
import com.shusheng.cobblemarket.currency.ShopItemSpec
import com.shusheng.cobblemarket.currency.ShopPokemonSpec
import com.shusheng.cobblemarket.market.ItemRuleComponents
import com.shusheng.cobblemarket.network.BuyPointMarketEntryPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.currency.PointShopSnapshotState
import com.shusheng.cobblemarket.network.DeleteShopSnapshotPayload
import com.shusheng.cobblemarket.network.RedeemPointCodePayload
import com.shusheng.cobblemarket.network.RequestShopSnapshotsPayload
import com.shusheng.cobblemarket.network.SaveShopSnapshotPayload
import com.shusheng.cobblemarket.network.ShopSnapshotInfo
import com.shusheng.cobblemarket.network.RemovePointMarketEntryPayload
import com.shusheng.cobblemarket.network.RequestPointMarketPayload
import com.shusheng.cobblemarket.network.RequestPointsShopPayload
import com.shusheng.cobblemarket.network.SavePointMarketEntryPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.util.ItemNameText
import com.shusheng.cobblemarket.util.MoveText
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
class PointsMarketScreen : Screen(
    Text.translatable("cobblemarket.points.market.title", CurrencyService.pointsTitleIcon())
) {

    private var codeField: TextFieldWidget? = null
    /** 输入框内容：界面 resize 会重建控件，靠它把玩家已经敲进去的码恢复回来 */
    private var pendingCodeText = ""

    // ── 商品区（精灵 / 物品 双 tab 网格）──
    private var currentTab = 0
    private var pokemonTabButton: NineSliceButton? = null
    private var itemTabButton: NineSliceButton? = null
    private var prevButton: NineSliceButton? = null
    private var nextButton: NineSliceButton? = null
    private var currentPage = 1
    /** 总页数：**服务端快照说了算**（按最大已填格子序号算，本地不自己推） */
    private val totalPages: Int get() = PointMarketCache.totalPages
    /** 鼠标所在格子索引；-1 = 不在任何格子上。悬停的格子会**放大绘制**（浮在邻居之上） */
    private var hoveredCell = -1

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
     * 这个窗**能不能下架这一格**（= 点它的人能不能管商城）。
     *
     * ⚠ **不是「服主专属」**：判定走 [canEditShop] —— **服主 或 持 `points.shop` 节点的人**都算
     *（项目服主制，**不认 OP**）。服务端 `PointMarketService.remove` 同样只判这个节点。
     *
     * ⚠ 窗口本身**不分身份**：所有人看到的都是购买窗（标题「购买」、底部「购买 + 取消」），
     * 能管的人只是**右上角多一枚「下架」**。2026-09-25 用户定：不必单独做一个只读查看窗
     * ——「不再能编辑已有格子」之后，改内容走「下架 → 重新添加」（配快照免重填字段）。
     */
    private var buyCanRemove = false

    /** 购买窗**右上角**那枚「下架」（只有能管这一格的人才建，见 [buildBuyDialog]） */
    private var buyRemoveButton: NineSliceButton? = null

    /**
     * 打开购买窗时那一条的 **id**（见 [ShopEntry.entryId]）。
     * 点购买时带回服务端比对；也用来判断"这格有没有被换过内容"（见 [checkBuySlotStillValid]）。
     */
    private var buySlotEntryId = ""

    /** 购买数量。**只对物品格有意义** —— 精灵格恒按 1 算（买几次就请求几次，服务端同样口径） */
    private var buyCount = 1
    private var buyCountField: TextFieldWidget? = null

    /** 购买窗的**库存框** —— 只有能管这一格的人有（普通玩家那格是不可编辑的纯文本）。见 [buildBuyDialog] */
    private var buyStockField: TextFieldWidget? = null

    /**
     * 打开窗时那份库存 —— 提交前比一下，**没变就不发包**（免得每次失焦都白跑一趟服务端）。
     * 同时也当"当前已生效的值"用：提交成功后同步成新值。
     */
    private var buyStockOriginal = ShopEntry.UNLIMITED
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
     * 购买窗预览的姿态状态。
     *
     * ⚠ **换精灵时必须整个换新**（见 [openBuyDialog]）：`PokemonIconRenderer` 会把当前模型 /
     *   动画 locator 写进 state，复用同一个就会把**上一只的动作**带过来 ——
     *   用户 2026-09-26 实测：「动画刚开始播就退出界面、立刻点另一只 ⇒ 新那只模仿旧那只的动作」。
     *   叫声没串是因为它走的是 `renderable` 参数，动作走 state。
     */
    private var buyPreviewState = FloatingState()

    /**
     * 预览图标的屏幕位置 —— **渲染时记录**（见 [renderBuyPreview]），关窗时拿去当庆祝动画的起点。
     *
     * ⚠ 别在别处按坐标函数重算：`Origin.ofIcon` 有个 `centerAnchorY` 开关（渲染默认 false = 顶端锚定），
     * 重算时传错就是**起点偏下半个槽位**（2026-09-25 用户实测踩到）。
     * 拍卖场那边的注释说得对：「渲染时记录 = 永远和实际画出来的一致」。
     */
    private var buyIconOrigin: com.shusheng.cobblemarket.client.PokemonCelebrationAnimation.Origin? = null
    private var buyItemIconOrigin: com.shusheng.cobblemarket.client.ItemCelebrationAnimation.Origin? = null

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

    /** 关弹窗：卸掉它自己那批控件 + 把添加窗的控件恢复回来 */
    private fun closeSnapshotDialog() {
        snapshotDialog = false
        snapshotScroll = 0
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
        val price = editPriceField?.text?.trim()?.toLongOrNull()
        if (price == null || price < 0) {
            failSubmit("price_required")
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
     *   界面**没接的高级项**（EV / 昵称 / 证章 / 极巨化等级…）才不会被抹掉。
     */
    private fun applySnapshot(snapshot: ShopSnapshotInfo) {
        editSpecBase = snapshot.entry
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
     * （EV / 亲密度 / 昵称 / 证章 / 极巨化等级 / 物品组件…）必须原样保留，
     * 否则「什么都不改直接保存」会把这些悄悄抹掉（全量覆盖式表单的铁律，本项目有前科）。
     */
    private var editSpecBase: ShopEntry? = null

    // 窗口控件引用（关闭时清空；resize 后靠它们恢复草稿）
    private var editSpeciesField: TextFieldWidget? = null
    private var editLevelField: TextFieldWidget? = null
    private var editPriceField: TextFieldWidget? = null
    private var editStockField: TextFieldWidget? = null
    private var editSpeciesPrev: NineSliceButton? = null
    private var editSpeciesNext: NineSliceButton? = null

    /** 清空搜索框（搜索框右侧第一枚，用户 2026-09-20 加）；与两枚箭头一起藏，见 [updateEditControlsVisibility] */
    private var editSpeciesClear: NineSliceButton? = null

    /** 添加窗右上角那枚「快照」（关窗时随其余控件一起清） */
    private var editSnapshotButton: NineSliceButton? = null

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
    /** 编辑窗预览的姿态状态 —— ⚠ 换物种时要整个换新，见 [applyEditSpecies]（与 [buyPreviewState] 同一个坑） */
    private var editPreviewState = FloatingState()

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
    private var optionListItems: List<OptionItem> = emptyList()
    private var optionListScroll = 0

    /** 列表的搜索框（**只在列表展开期间存在**）与搜索词；纯本地过滤，不发包 */
    private var optionSearchField: TextFieldWidget? = null
    private var optionSearchText = ""

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
        val iconTexture: Identifier? = null
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
    private var editLevelText = ""
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
        val bg = bgRect()
        val inset = borderInset()
        val cx = bg.x + bg.w / 2

        // 返回按钮：**底部居中**，底边距背景底 BACK_BOTTOM（原图口径）
        backButton = NineSliceButton(
            cx - BACK_W / 2, backButtonY(bg), BACK_W, BACK_H,
            Text.literal(""),
            { client?.setScreen(MarketEntryScreen(skipDropAnim = true)) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        )
        addDrawableChild(backButton)

        // 兑换区：**内容区左上角**（不占中部的商品区；没有小标题，输入框本身就是说明）
        // 商城快照（档位 / 有没有收款码）决定右上角那个入口显不显示；没请求过就请求一次
        //（init 会被 resize 反复调用，用标志挡住重复请求）
        if (!shopInfoRequested) {
            sendToServer(RequestPointsShopPayload(true))
            shopInfoRequested = true
        }

        val rowY = redeemRowY(bg)
        val fieldX = bg.x + inset
        val buyBtnX = bg.x + bg.w - inset - BUY_ENTRY_W
        // ⚠ 兑换行要挤下**四组**东西（输入框 / 兑换 / 双 tab / 购买），而背景是等比缩放、
        //   控件是固定逻辑像素 —— GUI scale 调大时逻辑分辨率变小（1080p 用 Auto 时只有 640×360，
        //   scale 更大时 480×270），背景缩了控件没缩就会挤成一团。
        //   所以输入框宽度按**剩余空间**算，压到下限为止。
        val tabGroupW = TAB_W * 2 + TAB_GAP
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
        val tabLeftX =
            if (tabMinLeft <= tabMaxLeft) (cx - tabGroupW / 2).coerceIn(tabMinLeft, tabMaxLeft)
            // 兜底：极端小分辨率下这一行真的挤不下（按钮都已按文字宽度收到最小），
            // 只能让 tab 紧跟兑换按钮 —— 此时它会压到「兑换记录」上，属于「看得见但挤」，
            // 再往下就是字都看不清了，不如保持这个下限。
            else tabMinLeft
        pokemonTabButton = NineSliceButton(tabLeftX, rowY, TAB_W, TAB_H, Text.literal(""), { switchTab(0) })
        itemTabButton = NineSliceButton(
            tabLeftX + TAB_W + TAB_GAP, rowY, TAB_W, TAB_H, Text.literal(""), { switchTab(1) }
        )
        addDrawableChild(pokemonTabButton)
        addDrawableChild(itemTabButton)
        updateTabButtons()

        // 翻页按钮：与返回按钮同一行。**三个按钮按背景宽度三等分、各自在所属等分里居中**
        // （用户 2026-09-18）—— 返回按钮因此仍在正中间，位置不变
        val pageBtnY = backButtonY(bg) - 2
        val third = bg.w / 3
        prevButton = NineSliceButton(
            bg.x + third / 2 - PAGE_BTN_W / 2, pageBtnY, PAGE_BTN_W, PAGE_BTN_H,
            Text.literal(""),
            { prevPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/previous.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.prev")
        )
        nextButton = NineSliceButton(
            bg.x + bg.w - third / 2 - PAGE_BTN_W / 2, pageBtnY, PAGE_BTN_W, PAGE_BTN_H,
            Text.literal(""),
            { nextPage() },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/next.png"),
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
        // 窗口开着：事件**只给窗口**（格子与主界面一律不响应；下层控件已 visible=false）
        if (editSlot >= 0) return handleEditDialogClick(mouseX, mouseY, button)
        if (buySlot >= 0) return handleBuyDialogClick(mouseX, mouseY, button)

        val wasInInput = focused != null && focused === codeField
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && codeField?.isMouseOver(mouseX, mouseY) != true) {
            focused = null
        }
        // 点格子：持 `points.shop` 节点的人开**编辑窗**；其余人（**含 OP** —— 见 CLAUDE.md 铁律）
        // 点**有内容的**格子开**购买窗**，点空格子无反应。
        // ⚠ 判身份**只认 `canEditShop()`**：它 2026-09-25 起不再认 OP，所以 OP 在这里跟普通玩家一样是买家。
        if (!result && button == 0) {
            val cell = cellAt(mouseX, mouseY, gridLayout(bgRect()))
            if (cell >= 0) {
                val slot = globalSlot(cell)
                val occupied = PointMarketCache.slots[slot] != null
                val admin = canEditShop()
                // 能管商城的人（[canEditShop] = 服主 或 持 `points.shop` 节点）点**空格子** → 添加窗
                if (admin && !occupied) {
                    openEditDialog(slot)
                    return true
                }
                // 有内容的格子：**所有人都进购买窗**，能管的人只是右上角多一枚「下架」
                // ⚠ 谁都不能编辑已有格子（2026-09-25 用户拍板）—— 想改就走「下架 → 重新添加」
                if (occupied) {
                    openBuyDialog(slot, canRemove = admin)
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
            if (!result && button == 0) clickOptionRow(mouseX, mouseY)
            focused = null
            return true
        }
        // 点在输入框之外 → 手动失焦（否则 E 键被输入框吃掉、Esc 也关不掉窗口）
        if (focusWasInput && !isOverEditInput(mouseX, mouseY)) focused = null
        // 点预览槽 = 点精灵图标（既有标配：点一下就随机叫一声）
        if (button == 0 && isPokemonSlot() && overEditPreview(mouseX, mouseY)) {
            editPreview?.let { IconCryClick.play(editPreviewState, it) }
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
            // 选项行是**自绘**的（不走 NineSliceButton），点击音得自己播 ——
            // 缺了它，在性格 / 特性 / 太晶属性这些**展开式列表**里选东西会没有任何声音
            //（2026-09-25 用户报）。⚠ 单选、多选两种情况都要，所以放在分支之前
            playButtonClickSound()
            // 多选（证章）只切勾选、不关列表；单选选完就走
            if (optionListMultiSelect) toggleMultiOption(item.value) else selectOption(item.value)
        }
        return true
    }

    override fun mouseScrolled(
        mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double
    ): Boolean {
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
        listOf(editSpeciesField, editItemSearchField, editPriceField, editStockField) +
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
        // ① 有人刚改了 / 下架了正在看的这一格 → 关窗 + 提示（见 checkBuySlotStillValid）
        checkBuySlotStillValid()
        if (dialogOpen()) return
        val wantPage = currentPage - 1
        // ⚠ 判据**必须带上 kind**：精灵与物品各是一套数据，只看页号的话 ——
        //   缓存里留着**另一个 tab** 的快照时会被当成"已经加载好了"，于是一个包都不发、网格空着
        //  （2026-09-26 用户报：切过 tab 再进商城，精灵网格是空的，再切一次 tab 才出来）。
        //   ⚠ `PointMarketCache.kind` 这个字段本来就是为此存的，之前只写不读。
        if (PointMarketCache.loaded && PointMarketCache.page == wantPage &&
            PointMarketCache.kind == currentKind()
        ) return
        val now = System.currentTimeMillis()
        if (now - lastPageRequestAt < PAGE_REQUEST_INTERVAL_MS) return
        lastPageRequestAt = now
        sendToServer(RequestPointMarketPayload(wantPage, currentKind()))
    }

    // ── 商品区：tab / 翻页 / 网格几何 ──

    /**
     * 当前 tab 对应的商品种类 —— **tab 与 kind 的唯一换算处**（别在别处再写一遍）
     */
    private fun currentKind(): String =
        if (currentTab == 0) ShopEntry.KIND_POKEMON else ShopEntry.KIND_ITEM

    private fun switchTab(tab: Int) {
        if (currentTab == tab) return
        currentTab = tab
        // 切 tab 重置翻页：换了商品集合还停在第 3 页，看着像空列表
        currentPage = 1
        updateTabButtons()
        // ⚠ 清缓存 + **重新请求**：精灵与物品各占一套独立的格子编号（2026-09-25 起 tab 是真过滤），
        //   不清的话新快照到达前会拿上一个 tab 的数据画，格子序号还恰好对得上、看着像"没切过去"
        PointMarketCache.clear()
        sendToServer(RequestPointMarketPayload(currentPage - 1, currentKind()))
    }

    private fun updateTabButtons() {
        val pokemonLabel = Text.translatable("cobblemarket.points.market.tab_pokemon").string
        val itemLabel = Text.translatable("cobblemarket.points.market.tab_item").string
        // 选中态用 selectedText（照 PriceLimitScreen 的 tab）——按钮底色图只有一段状态，
        // 靠文字高亮区分选中
        pokemonTabButton?.setMessage(
            if (currentTab == 0) com.shusheng.cobblemarket.util.TextUtil.selectedText(pokemonLabel)
            else Text.literal(pokemonLabel)
        )
        itemTabButton?.setMessage(
            if (currentTab == 1) com.shusheng.cobblemarket.util.TextUtil.selectedText(itemLabel)
            else Text.literal(itemLabel)
        )
    }

    private fun prevPage() {
        if (currentPage <= 1) return
        currentPage--
        // 换了页要立刻拉新快照（把节流计时清零，下一个 tick 就发，不用干等 500ms）
        lastPageRequestAt = 0L
    }

    private fun nextPage() {
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
        // **左 3 格**的 18 个字段，每行 3 个（右边 3 格留给预览图与个体/努力值输入框）。
        // 顺序就是用户 2026-09-20 定的那张表。
        listOf(
            KEY_SHINY, KEY_SHINY_CHANCE, KEY_FORM,
            KEY_LEVEL, KEY_GENDER, KEY_ABILITY,
            KEY_NATURE, KEY_BALL, KEY_SIZE,
            KEY_ALPHA, KEY_GMAX, KEY_TERA,
            KEY_TRADEABLE, KEY_HELD, KEY_HELD_VISIBLE,
            KEY_FRIENDSHIP, KEY_MARKS, KEY_MIN_IVS
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
    private fun editDialogHeight(): Int = minOf(EDIT_DIALOG_H, height - 8)

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
        (editDialogHeight() - EDIT_ROWS_TOP - EDIT_BOTTOM_BLOCK).coerceAtLeast(EDIT_ROW_STEP)

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
    private fun editPriceRowY(): Int = editDialogY() + editDialogHeight() - EDIT_BOTTOM_BLOCK

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

    private fun editPriceGroupW(): Int = editPriceColW() * 2 + EDIT_COL_GAP

    private fun editPriceGroupX(): Int =
        editDialogX() + EDIT_PAD + (editDialogW() - EDIT_PAD * 2 - editPriceGroupW()) / 2

    /** 底部按钮行的顶边 */
    private fun editButtonRowY(): Int = editDialogY() + editDialogHeight() - EDIT_BOTTOM_BLOCK + 18

    /** 某一行当前露没露在滚动区里（整行判断：半截的行控件本身没法裁剪，只能整体藏） */
    private fun editRowVisible(row: Int): Boolean {
        val top = editRowY(row)
        val viewTop = editDialogY() + EDIT_ROWS_TOP
        return top >= viewTop - 1 && top + EDIT_ROW_H <= viewTop + editScrollViewHeight() + 1
    }

    private fun isPokemonSlot(): Boolean = currentTab == 0

    /**
     * 点格子：持 `points.shop` 节点者点空格 = 添加、点有内容的格 = 编辑。
     * 普通玩家点有内容的格 = 购买窗口（**尚未实现**）；点空格无反应。
     */
    private fun openEditDialog(slot: Int) {
        if (!canEditShop()) return
        // 格子是**自绘**的，不走 NineSliceButton 那套内置点击音 —— 不在这里补一下，
        // 点格子的手感就是"没反应"（用户 2026-09-20 报「网格内的交互都没音效」）
        playButtonClickSound()
        editSlot = slot
        // ⚠ 这里**只处理"添加"**（只有空网格会走到这，见 mouseClicked）：`editOriginal` 恒为 null。
        //   点有内容的格子走的是 [openBuyDialog]（购买窗 + 右上角多一枚「下架」），不再有"编辑"——
        //   用户 2026-09-25 拍板，想改内容就走「下架 → 重新添加」。
        //   `editOriginal` 这个字段留着给**快照回填**用（那时它装的是快照那份规格）。
        editSpecBase = null
        resetDraft()
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
    private fun closeEditDialog() {
        editSlot = -1
        editSpecBase = null
        editHintMsg = null
        editSpeciesField = null
        editLevelField = null
        editShinyField = null
        editPriceField = null
        editStockField = null
        editSpeciesPrev = null
        editSpeciesNext = null
        editSpeciesClear = null
        editItemSearchField = null
        editItemHeldButton = null
        editItemSearchSyncing = false
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
        val entry = PointMarketCache.slots[buySlot]
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
     * 打开购买窗。[slot] 必须是**有内容的**格子（调用方 [mouseClicked] 已判过，这里再兜一道）。
     *
     * [canRemove] = true（持 `points.shop` 节点 / 服主）时，窗口**右上角多一枚「下架」**；
     * 其余内容与按钮和买家**完全一样** —— 服主自己也走这个窗买东西，**没有单独的只读查看窗**
     *（2026-09-25 用户定：不必为服主单做一个窗）。
     */
    private fun openBuyDialog(slot: Int, canRemove: Boolean = false) {
        if (PointMarketCache.slots[slot] == null) return
        // 格子是自绘的、不走 NineSliceButton 内置点击音 —— 不补手感就是"点了没反应"（同 openEditDialog）
        playButtonClickSound()
        buySlot = slot
        buyCanRemove = canRemove
        buyCount = 1
        // 记下这一条的 id：购买时带回服务端比对，也用来判断它有没有被换过内容
        buySlotEntryId = PointMarketCache.slots[slot]?.entryId.orEmpty()
        buyHintMsg = null
        // ⚠ 购买窗**不滚动**（只读文本自己画，没有控件要挪），但 [editRowY] 会减去这个偏移 ——
        //   编辑窗滚过之后再开购买窗，整片字段会跟着上移错位，所以这里必须清零
        editContentScroll = 0
        // ⚠ **整个换新**：上一只的模型 / 动画 locator 记在 state 里，不换的话新那只会上演**上一只的动画**
        //   （用户 2026-09-26：动画刚开播就退出、立刻点另一只 ⇒ 新那只模仿旧那只的动作）
        buyPreviewState = FloatingState()
        hideMainControls()
        buildBuyDialog()
        // 开窗自动叫一声（延迟到渲染之后再播，见 IconCryClick）
        buyCryAt = System.currentTimeMillis() + IconCryClick.AUTO_CRY_DELAY_MS
    }

    /**
     * 关窗：清状态 + **重建界面**把主控件恢复回来（与 [closeEditDialog] 同一套）。
     * ⚠ 重建会重跑 init，兑换框里敲了一半的码靠 `pendingCodeText` 恢复 —— 与编辑窗同一条路。
     */
    private fun closeBuyDialog() {
        // ⚠ **必须在清 buySlot 之前**：用户改完库存直接点「购买 / 取消」时，输入框会随控件一起被清掉 ——
        //   不在这儿补一刀，那次编辑就白填了（失焦那条路只覆盖"点了界面别处"）
        submitBuyStockIfChanged()
        buySlot = -1
        buyCanRemove = false
        buySlotEntryId = ""
        buyCount = 1
        buyHintMsg = null
        buyCountField = null
        buyStockField = null
        buyConfirmButton = null
        buyCancelButton = null
        buyRemoveButton = null
        buyWidgetBase = 0
        clearChildren()
        init()
    }

    /** 建窗口控件：背景层 + 数量框（物品格才有）+ 确认 / 取消 */
    private fun buildBuyDialog() {
        val entry = PointMarketCache.slots[buySlot] ?: return
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
        if (entry.kind != ShopEntry.KIND_POKEMON) {
            val field = TextFieldWidget(
                textRenderer, buyCountX(), editPriceRowY(), BUY_COUNT_W, EDIT_ROW_H, Text.literal("")
            )
            field.setTextPredicate { s -> s.length <= 4 && s.all { it.isDigit() } }
            field.text = "1"
            field.setChangedListener { text -> buyCount = text.trim().toIntOrNull() ?: 0 }
            buyCountField = field
            addSelectableChild(field)
            addDrawableChild(field)
        }

        // 库存框：**只有能管这一格的人有**（普通玩家那格是不可编辑的纯文本，见 [renderBuyPriceRow]）。
        // 编辑功能取消后，售罄的格子仍留在界面上就是为了补货 —— 这里就是补货的入口（2026-09-25 用户定）。
        // ⚠ 留空 = 不限量（与添加窗的库存框同口径）；框里显示的是**十进制数**，不是数据层的 -1
        if (buyCanRemove) {
            buyStockOriginal = entry.stock
            val stockField = TextFieldWidget(
                textRenderer, buyStockFieldX(), editPriceRowY(), buyStockFieldW(), EDIT_ROW_H, Text.literal("")
            )
            stockField.setTextPredicate { s -> s.length <= 9 && s.all { it.isDigit() } }
            stockField.setPlaceholder(Text.translatable("cobblemarket.points.store.value.unlimited"))
            stockField.text = if (entry.stock == ShopEntry.UNLIMITED) "" else entry.stock.toString()
            buyStockField = stockField
            addSelectableChild(stockField)
            addDrawableChild(stockField)
        }

        val rowY = editButtonRowY()
        // 右上角那枚「下架」：**只有能管这一格的人**（持 `points.shop` 节点，服主恒真）才建。
        // ⚠ 它与买家的「购买 / 取消」**并存**，不再是二选一 —— 能管的人自己也能买
        //   （2026-09-25 用户定：不必单独做一个只读查看窗，就用购买窗，多的只是这枚按钮）。
        //   位置沿用原编辑窗那枚（右上角，见 [editRemoveY]）。
        if (buyCanRemove) {
            buyRemoveButton = NineSliceButton(
                editRowRightX() - EDIT_REMOVE_W, editRemoveY(), EDIT_REMOVE_W, EDIT_ROW_H,
                Text.translatable("cobblemarket.points.store.remove"), { removeFromBuyDialog() }
            ).also { addDrawableChild(it) }
        }
        buyConfirmButton = NineSliceButton(
            editDialogX() + editDialogW() - EDIT_PAD - BUY_BTN_W * 2 - BUTTON_GAP, rowY, BUY_BTN_W, 16,
            Text.translatable("cobblemarket.points.store.buy_confirm"), { confirmBuy() }
        ).also { addDrawableChild(it) }
        buyCancelButton = NineSliceButton(
            editDialogX() + editDialogW() - EDIT_PAD - BUY_BTN_W, rowY, BUY_BTN_W, 16,
            Text.translatable("cobblemarket.points.store.buy_cancel"), { closeBuyDialog() }
        ).also { addDrawableChild(it) }
    }

    /**
     * 在购买窗右上角点「下架」：清空这一格。
     *
     * ⚠ **不是服主专属**：能点的人 = [canEditShop]（**服主 或 持 `points.shop` 节点的人**，
     *   不认 OP），服务端 `PointMarketService.remove` 同样只判这个节点。
     *
     * 与原编辑窗那枚「下架」同一个包、同一套语义 —— 只是操作对象换成了 [buySlot]（那枚已随
     * 「不再能编辑已有格子」一并删除，现在这里是**唯一**的下架入口）。
     * ⚠ 没有二次确认，与编辑窗那枚**保持一致**（别只给一边加，两边行为会分叉）。
     */
    private fun removeFromBuyDialog() {
        if (buySlot < 0) return
        sendToServer(RemovePointMarketEntryPayload(buySlot, currentKind()))
        closeBuyDialog()
    }

    /** 窗口内的点击：控件优先 → 点空白失焦。窗口开着时事件**一律吃掉**，不让它漏到下层 */
    private fun handleBuyDialogClick(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val focusWasInput = buyCountField != null && focused === buyCountField
        val focusWasStock = buyStockField != null && focused === buyStockField
        super.mouseClicked(mouseX, mouseY, button)
        // 点在输入框之外 → 手动失焦（否则 E 键被输入框吃掉、Esc 也关不掉窗口）
        if (focusWasInput && buyCountField?.isMouseOver(mouseX, mouseY) != true) focused = null
        // 库存框**失焦即保存**（用户 2026-09-25 定）：点别处、点「购买 / 取消」都算数。
        // ⚠ 判据用**点击前**的焦点状态（`focusWasStock`）—— `super.mouseClicked` 之后 focused 已经变了
        if (focusWasStock && buyStockField?.isMouseOver(mouseX, mouseY) != true) {
            focused = null
            submitBuyStockIfChanged()
        }
        // 点**精灵大图** = 点精灵图标（项目标配：点一下随机叫一声）。
        // 开窗时本来就自动叫过一声，这里补的是「再点它」那一下 —— 编辑窗的预览槽一直有，购买窗漏了
        //（2026-09-25 用户报「大图没有点击反馈」）。
        // ⚠ 必须判 `KIND_POKEMON`：**物品格没有大图**，不判的话点那块空白也会叫
        if (button == 0 && buySlot >= 0) {
            PointMarketCache.slots[buySlot]
                ?.takeIf { it.kind == ShopEntry.KIND_POKEMON && overBuyPreview(mouseX, mouseY) }
                ?.let { entry ->
                    cellIcon(buySlot, entry).renderable?.let { IconCryClick.play(buyPreviewState, it) }
                }
        }
        return true
    }

    /**
     * 把库存框里改过的值发给服务端 —— **没变就不发**（免得每次失焦都白跑一趟服务端）。
     *
     * ⚠ 留空 = 不限量（传 [ShopEntry.UNLIMITED]）；非法输入（非数字 / 超长）按"没改"处理，不误发。
     * ⚠ 发完把 [buyStockOriginal] 同步成新值：同一次开窗里再失焦几次也不会重复发包。
     */
    private fun submitBuyStockIfChanged() {
        val field = buyStockField ?: return
        if (buySlot < 0) return
        val raw = field.text.trim()
        val value = if (raw.isEmpty()) ShopEntry.UNLIMITED else raw.toIntOrNull() ?: return
        if (value == buyStockOriginal) return
        sendToServer(UpdatePointMarketStockPayload(buySlot, currentKind(), value))
        buyStockOriginal = value
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
        val entry = PointMarketCache.slots[buySlot] ?: return closeBuyDialog()
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
        val count = if (entry.kind == ShopEntry.KIND_POKEMON) 1 else buyCount
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
        sendToServer(BuyPointMarketEntryPayload(buySlot, count, currentKind(), buySlotEntryId))
        closeBuyDialog()
    }

    /** 窗口内容：遮罩 + 九宫格 + 标题 + 预览 + 字段 + 价格行 */
    private fun renderBuyDialog(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val entry = PointMarketCache.slots[buySlot] ?: return
        val x = editDialogX()
        val y = editDialogY()
        // 遮罩压暗下层（不压的话底下那排格子会跟弹窗抢注意力）
        drawScreenDimMask(context, width, height)
        drawNineSlice(
            context, DIALOG_BACKGROUND_TEXTURE, x, y, editDialogW(), editDialogHeight(), 0, DIALOG_BACKGROUND_TEX_H
        )
        context.drawCenteredTextWithShadow(
            textRenderer,
            // 标题**不分身份**：能管这一格的人（持 `points.shop` 节点 / 服主）进的也是购买窗，
            // 只是右上角多一枚「下架」—— 「查看」那套标题随只读窗一并去掉（2026-09-25 用户定）
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

        // 本地挡下的非法值（服务端拒绝的原因另走聊天栏）
        buyHintMsg?.let { msg ->
            if (System.currentTimeMillis() < buyHintUntil) {
                context.drawTextWithShadow(textRenderer, msg, x + EDIT_PAD, editPriceRowY() - 11, 0xFF6666)
            }
        }
    }

    /** 预览槽：与编辑窗同位置同尺寸（右侧 3 格），但画的是**商品规格**那一只 */
    private fun renderBuyPreview(context: DrawContext, entry: ShopEntry, delta: Float) {
        val slotSize = editPreviewSize()
        val slotX = editPreviewSlotX()
        val slotY = editPreviewSlotY()
        drawPokemonSlot(context, slotX, slotY, slotSize)
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
                state = buyPreviewState,
                x = slotX, y = slotY, size = slotSize,
                delta = delta,
                animate = useFloat,
                clip = editPreviewClip(slotY, slotSize)
            )
            // 开窗后自动叫一声：**必须在渲染之后**（addFirstAnimation 依赖 draw 里建好的模型）
            if (buyCryAt != 0L && System.currentTimeMillis() >= buyCryAt) {
                buyCryAt = 0L
                IconCryClick.play(buyPreviewState, renderable)
            }
        } catch (_: Exception) {
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
        for (key in editFieldOrder()) {
            if (key == FIELD_BREAK) continue
            val (row, col) = posOf(key)
            if (!editRowVisible(row)) continue
            // 「证章」那一行：悬停弹小窗把**全部**证章图标排出来（见 renderMarksTooltip）
            if (key == KEY_MARKS && spec.marks.isNotEmpty() && overBuyField(mouseX, mouseY, row, col)) {
                marksTooltipAt = mouseX to mouseY
            }
            drawBuyField(
                context, editColX(col), editRowY(row), editColW(), buyFieldLine(key, spec),
                // 头目那行的标签是**徽章贴图**（与编辑窗按钮、列表行同一张），得单独画；其余字段没有
                badge = if (key == KEY_ALPHA) "alpha" else null,
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
        val perRow = EntryBadgeRenderer.MARKS_PER_ROW
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
     * 名字带主属性色 + 闪光星标、属性名分段着色，Lv 与「+」这些陪衬文字不设色。
     */
    private fun renderBuyNameLines(context: DrawContext, spec: ShopPokemonSpec) {
        val species = Identifier.tryParse(spec.species)?.let { PokemonSpecies.getByIdentifier(it) } ?: return
        val primary = "cobblemon.type.${species.primaryType.name.lowercase()}"
        val secondary = species.secondaryType?.let { "cobblemon.type.${it.name.lowercase()}" }.orEmpty()
        val y = editDialogY() + EDIT_SPECIES_Y + (EDIT_ROW_H - 8) / 2
        val nameLine = EntryBadgeRenderer.nameWithLevelLine(
            EntryBadgeRenderer.nameWithShinyStar(SpeciesText.translated(species).string, "shiny" in spec.aspects),
            primary,
            // 等级留空 = 生成 1 级（与 PointMarketFactory 同口径）
            spec.level ?: 1
        )
        context.drawTextWithShadow(textRenderer, nameLine, editDialogX() + EDIT_PAD, y, 0xFFFFFF)
        // 属性跟在名字右边（名字宽度是**画出来的**那个宽度，不是估算 —— 两段才不会叠字）
        context.drawTextWithShadow(
            textRenderer,
            EntryBadgeRenderer.typeLine(primary, secondary),
            editDialogX() + EDIT_PAD + textRenderer.getWidth(nameLine) + 8,
            y,
            0xFFFFFF
        )
    }

    /** 物品格：右侧大图 + 大图**左侧**那一列词条（物品名 + 附魔 / 招式 / 自定义名 / 耐久） */
    private fun renderBuyItemFields(context: DrawContext, entry: ShopEntry) {
        val spec = entry.item ?: return
        // ⚠ 原来这里还画了一行「物品：xxx」（占着左上角那条物种搜索框的位置）——
        //   自从大图左侧上了**词条列**（第一行就是物品名），它就纯属重复了（用户 2026-09-26 指出）。
        //   精灵格那边仍保留名字行：它的名字带着属性色 + 闪光星标 + Lv，词条列里没有这些。
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
        context.drawTextWithShadow(
            textRenderer,
            Text.translatable(
                "cobblemarket.points.store.buy_price",
                CurrencyService.amountText(entry.price, MarketCurrency.POINTS)
            ),
            buyRowLeft(), y, 0xFFFFFF
        )
        // 「剩余」那一格：**能管这一格的人**在这儿是个可编辑的输入框（见 [buildBuyDialog]），
        // 所以只画标签、值交给控件；普通玩家仍是不可编辑的「剩余：N」
        if (buyCanRemove) {
            context.drawTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.store.buy_stock_label"),
                buyStockLabelX(), y, 0xFFFFFF
            )
        } else {
            context.drawTextWithShadow(
                textRenderer,
                Text.translatable(
                    "cobblemarket.points.store.buy_stock",
                    if (entry.stock == ShopEntry.UNLIMITED) Text.translatable("cobblemarket.points.store.value.unlimited")
                    else Text.literal(entry.stock.toString())
                ),
                buyStockLabelX(), y, 0xFFFFFF
            )
        }
        // 数量 / 总价：**只有物品格有**（精灵格买一次就是一只，显示总价只是把单价重复一遍）
        if (entry.kind != ShopEntry.KIND_POKEMON) {
            // ⚠ 标签位置与建控件（[buyCountX]）**同源**，别在这儿再算一遍第 3 格起点
            context.drawTextWithShadow(
                textRenderer, Text.translatable("cobblemarket.points.store.buy_count"), buyCountLabelX(), y, 0xFFFFFF
            )
            val total = entry.price * buyCount.coerceAtLeast(1)
            // 「总价」**就在这一行**（用户 2026-09-26 澄清：不是新起一行），只是水平位置
            //   **居中于购买 / 取消那一对按钮**，而不是居中于窗口 —— 这一行是四项等分铺满的，
            //   居中于窗口会直接压在「剩余 / 数量」那两格上（第一版就是这么错的重叠）。
            // ⚠ 这是混排行（汉字 + 金额 + 货币图标），别用 drawCenteredTextWithShadow
            //   —— 它会让行首汉字高一点（项目踩过的坑），算好 x 再画。
            val totalText = Text.translatable(
                "cobblemarket.points.store.buy_total",
                CurrencyService.amountText(total, MarketCurrency.POINTS)
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

    /**
     * 库存框的 x：「剩余：」标签的**右缘**（那一格从标签之后开始）。
     * ⚠ 渲染（[renderBuyPriceRow]）与建控件（[buildBuyDialog]）**共用这个函数** ——
     *   各算一份必然错位（本项目反复踩过）。
     */
    private fun buyStockFieldX(): Int =
        buyStockLabelX() + textRenderer.getWidth(Text.translatable("cobblemarket.points.store.buy_stock_label"))

    /** 库存框宽度：那一格**扣掉标签之后**剩下的宽度，别顶到下一格的「数量」上 */
    private fun buyStockFieldW(): Int =
        (buyColW() - textRenderer.getWidth(Text.translatable("cobblemarket.points.store.buy_stock_label")) - 4)
            .coerceAtLeast(16)

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
     * 一行只读字段（**标签冒号写在词条里**，这里只负责截断与摆放）。
     * [color] 是**行色** —— 默认白，个体值 / 努力值那几行按项给色（见 [STAT_ROW_COLORS]）。
     * [badge] = **行首徽章**（体型名，只有头目那行传 `"alpha"`）：徽章是**贴图**、插不进 [line] 这个
     *   Text 里，所以单独画一个行首图，后面的文字**往右让位**、可用宽度相应收窄。
     */
    private fun drawBuyField(
        context: DrawContext, x: Int, y: Int, w: Int, line: Text,
        color: Int = 0xFFFFFF,
        badge: String? = null,
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
        // [icon] = **行尾物品图标**（携带物 / 球种用）：这两个字段的值是物品，画名字会放不下
        //（「附魔金苹果」这类长名，一格只有 82px）—— 与编辑窗按钮上那个图标同一套画法
        if (icon != null && !icon.isEmpty) {
            drawItemWithBar(
                context, icon,
                textX + textRenderer.getWidth(text) + 2, y + (EDIT_ROW_H - 16) / 2
            )
        }
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
            KEY_FORM -> Text.translatable(
                "cobblemarket.points.store.field.form",
                spec.aspects.filter { it != "shiny" }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("/") { SpeciesText.aspectLabel(it) } ?: default.string
            )
            // 等级：服主留空 = 生成 1 级（与官方 PokemonProperties 同口径，见 PointMarketFactory）
            KEY_LEVEL -> Text.translatable("cobblemarket.points.store.field.level", (spec.level ?: 1).toString())
            KEY_GENDER -> Text.translatable(
                "cobblemarket.points.store.field.gender",
                when (spec.gender?.uppercase()) {
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
            KEY_SIZE -> Text.translatable(
                "cobblemarket.points.store.field.size",
                sizeIndexOf(spec.scale)?.let { PokemonSizeCategory.entries.getOrNull(it - 1)?.name } ?: random.string
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
            else -> Text.empty()
        }
    }

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
        editHeldVisible = true
        editMarks = emptyList()
        // ⚠ 输入框的暂存文本**必须一起重置** —— 它们是 resize 重建时恢复输入用的，
        //   漏清的话"点第二个格子，里面还是上一格填的内容"（2026-09-20 用户实测）
        editSpeciesText = ""
        // 等级 / 亲密度是**预填默认值**（用户 2026-09-20 定）：新建就是 Lv.1 / 亲密度 50，
        // 而不是空着（空 = 生成时随机掷）。清掉照样能回到随机，这里只是给个起点
        editLevelText = DEFAULT_LEVEL.toString()
        editPriceText = ""
        editStockText = ""
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
        // ⚠ 等级**必须在这里显式覆盖** [resetDraft] 预填的那个默认值 —— 输入框那边的 ifEmpty 兜底
        //   只在 `editLevelText` 为空时才生效，预填之后它不再为空，不覆盖的话编辑任何一条已有商品
        //   都会显示成 Lv.1（数据没错、只是显示骗人，但保存下去就成了 1 级）
        editLevelText = spec.level?.toString().orEmpty()
        editHeldVisible = spec.heldItemVisible
        editMarks = spec.marks
        // 物种：解析出来才能显示预览与形态/特性候选
        val species = Identifier.tryParse(spec.species)?.let { PokemonSpecies.getByIdentifier(it) }
        editSpecies = species
        editSpeciesCandidates = listOfNotNull(species)
        editSpeciesIndex = 0
        // ⚠ 这几行 normalize **必须落在物种定下来之后**。原先它们写在上头（那时 editSpecies 还是
        //   [resetDraft] 清掉的 null）⇒ 全是空操作，「编辑老数据时收进合法范围」其实从没生效
        //   （2026-09-20 修；注释当时就写着"物种定了之后"，代码没跟上）
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
    private fun buildShopEntry(): ShopEntry? {
        val price = editPriceField?.text?.trim()?.toLongOrNull() ?: return null
        if (price < 0) return null
        val stockText = editStockField?.text?.trim().orEmpty()
        // 留空 = 不限量（-1）；填 0 没意义（上架即售罄），服务端也会拒
        val stock = if (stockText.isEmpty()) ShopEntry.UNLIMITED else stockText.toIntOrNull() ?: return null
        return if (isPokemonSlot()) {
            val spec = buildPokemonSpec() ?: return null
            ShopEntry(ShopEntry.KIND_POKEMON, price, stock, pokemon = spec)
        } else {
            if (editItemId.isEmpty()) return null
            ShopEntry(ShopEntry.KIND_ITEM, price, stock, item = ShopItemSpec(editItemId, editItemComponents))
        }
    }

    private fun submitEdit() {
        // 价格单独再挡一道：只有它能给出"请填写价格"这条**具体**提示
        //（[buildShopEntry] 只回 null，不区分是哪一项缺）
        val price = editPriceField?.text?.trim()?.toLongOrNull()
        if (price == null || price < 0) {
            failSubmit("price_required")
            return
        }
        val entry = buildShopEntry() ?: run {
            // 走到这儿只可能是物种 / 物品没选（价格上面挡过了）
            if (isPokemonSlot()) failSubmit("species_required") else failSubmit("item_required")
            return
        }
        sendToServer(SavePointMarketEntryPayload(editSlot, entry))
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
            gender = GENDER_VALUES.getOrNull(editGender)?.takeIf { it.isNotEmpty() },
            nature = editNature.ifEmpty { null },
            ability = editAbility.ifEmpty { null },
            ball = editBall.ifEmpty { DEFAULT_BALL_ID },
            scale = sizeScale(),
            alpha = editAlpha,
            teraType = editTera.ifEmpty { null },
            gmaxFactor = editGmax,
            tradeable = editTradeable,
            heldItemSpec = editHeldItem,
            // 六项个体值：**整组全空**才回退到「保底 V 数」（数据层对这两种玩法是互斥的，见注释）
            ivs = if (hasIv) ivs else null,
            minPerfectIvs = if (hasIv) 0 else editMinIvs,
            evs = if (evs.any { it > 0 }) evs else null,
            friendship = editFriendshipText.trim().toIntOrNull()?.coerceIn(0, 255),
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
        val priceY = editPriceRowY()
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

        editStockField = TextFieldWidget(
            textRenderer, editPriceGroupX() + editPriceColW() + EDIT_COL_GAP + EDIT_LABEL_W, priceY,
            editPriceColW() - EDIT_LABEL_W, EDIT_ROW_H, Text.literal("")
        )
        editStockField?.setTextPredicate { s -> s.length <= 9 && s.all { it.isDigit() } }
        editStockField?.setPlaceholder(Text.translatable("cobblemarket.points.store.value.unlimited"))
        editStockField?.setChangedListener { editStockText = it }
        // 回填：不限量 → 留空（「不改 = 什么都不变」）
        editStockField?.text = editStockText.ifEmpty {
            editSpecBase?.stock?.takeIf { it != ShopEntry.UNLIMITED }?.toString().orEmpty()
        }
        addSelectableChild(editStockField)
        addDrawableChild(editStockField)

        // 底部按钮行：添加 + 取消（居中并排，间距 8）。
        // ⚠ 恒为「添加」—— 编辑态 2026-09-25 已取消，快照回填也只是填进新增的草稿，
        //   所以**别按 editSpecBase 判**（那是 copy 基底，回填后非 null，会错显成「保存」）
        val btnY = editButtonRowY()
        val confirmText = "cobblemarket.points.store.add"
        val contentW = editDialogW() - EDIT_PAD * 2
        editConfirmButton = NineSliceButton(
            x + EDIT_PAD + (contentW - EDIT_BUTTON_W * 2 - 8) / 2,
            btnY, EDIT_BUTTON_W, EDIT_BUTTON_H, Text.translatable(confirmText), { submitEdit() }
        )
        addDrawableChild(editConfirmButton)
        // ⚠ 取消按钮**必须存字段**：展开选项列表时要把它一起藏掉，否则它压在列表上、
        //   一点就把整个窗口关了（2026-09-20 用户实测：选形态时点「取消」直接退出了编辑窗）
        editCancelButton = NineSliceButton(
            x + EDIT_PAD + (contentW + 8) / 2,
            btnY, EDIT_BUTTON_W, EDIT_BUTTON_H,
            Text.translatable("cobblemarket.buy_confirm.cancel"), { closeEditDialog() }
        )
        addDrawableChild(editCancelButton)

        // **快照**：右上角那枚 —— 位置**就是原来「下架」的那个**（`editRemoveY()` 这名字是那时候留下的，
        // 它算的是"距顶 = 距右"那条对角线上，与具体是哪枚按钮无关）。
        // 用户 2026-09-25 拍板：服主不再能编辑已有格子，右上角这一格改放快照。
        // 那枚「下架」后来搬到了**购买窗的右上角**（能管商城的人才看得见），见 [removeFromBuyDialog]。
        // ⚠ **快照只有精灵窗有**：快照存的是**精灵配方**（物种 / 形态 / 个体值 / 技能那些），
        //   物品窗没有可存的东西 —— 原先无条件建，物品窗上就挂着一枚点了没用的按钮
        //（2026-09-25 用户指出）
        if (isPokemonSlot()) {
            editSnapshotButton = NineSliceButton(
                editRowRightX() - EDIT_SNAPSHOT_W, editRemoveY(),
                EDIT_SNAPSHOT_W, EDIT_ROW_H,
                Text.translatable("cobblemarket.points.store.snapshot_button"), { openSnapshotDialog() }
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
        add(editFriendshipField to KEY_FRIENDSHIP)
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
        field.setChangedListener { text -> editSpeciesText = text; updateEditSpecies(text) }
        field.text = editSpeciesText.ifEmpty {
            editSpecies?.resourceIdentifier?.toString() ?: editSpecBase?.pokemon?.species.orEmpty()
        }
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
            iconLeft = Identifier.of("cobblemarket", "textures/gui/previous.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.species_prev")
        )
        editSpeciesNext = NineSliceButton(
            editArrow2X(), y + EDIT_SPECIES_Y, EDIT_ARROW_W, EDIT_ROW_H, Text.literal(""),
            { cycleEditSpecies(1) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/next.png"),
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

                key == KEY_FRIENDSHIP -> {
                    editFriendshipField = newEditField(key)
                    editFriendshipField?.setTextPredicate(intInputIn(0..255))
                    editFriendshipField?.setPlaceholder(
                        Text.translatable("cobblemarket.points.store.field.friendship_hint")
                    )
                    editFriendshipField?.setChangedListener { editFriendshipText = it }
                    editFriendshipField?.text = editFriendshipText
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
            iconLeft = Identifier.of("cobblemarket", "textures/gui/hand.png"),
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
     * 该字段输入框在**列内**的左缩进（目前只有等级那格有前缀）。
     * ⚠ 建控件（[newEditField]）与滚动重排（[applyEditScroll]）**两处都走这里** ——
     *   各算一份的话，一滚动前缀就压到输入框上了。
     */
    private fun editFieldInsetX(key: String): Int = when (key) {
        KEY_LEVEL -> levelLabelW()
        // 亲密度**默认填 50**，会把 placeholder 盖住 —— 服主看着一个光秃秃的数字不知道是什么意思，
        // 所以照等级的做法在左边画个标签、输入框让出标签宽度（2026-09-25 用户提出）
        KEY_FRIENDSHIP -> friendshipLabelW()
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
        // ⚠ 物种**真的换了**才重置预览状态（同一个 state 里留着上一只的模型 / 动作，会串味）——
        //   本函数**每敲一个键都会调**（见 [updateEditSpecies]），无条件重置会让动画一直在原地打转
        if (editSpecies !== species) editPreviewState = FloatingState()
        editSpecies = species
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
        // 判据与形态候选**同一个数据源**（[SpeciesText.selectableForms]，已滤掉超极巨化）——
        // 两边不一致的话，选不出来的形态却判成合法、或者选得出来却被清掉
        val ok = SpeciesText.selectableForms(editSpecies)
            .any { it.aspects.isNotEmpty() && it.aspects.toSet() == editAspects.toSet() }
        if (!ok) editAspects = emptyList()
    }

    /**
     * 特性收进该物种**真有的**范围 —— 上面三件（性别 / 超极巨化 / 形态）之外的第四件
     * （用户 2026-09-20 报「特性按钮一样没受钳制」）。
     *
     * 判据与特性候选表**同一个口径**（[buildOptionItems] 的 `OPT_ABILITY` = 该物种的全部可能特性）。
     *
     * ⚠ 物种为空时**也要清**：那时候选表是空的，而 [optionLabelOf] 查不到会把**内部 id 原样显示**
     *   出来（「特性：cobblemon:blaze」这种英文字母，用户实测）—— 清掉回「随机」，界面才说得通。
     */
    private fun normalizeAbility() {
        if (editAbility.isEmpty()) return
        val allowed = editSpecies?.abilities?.map { it.template.name }
        if (allowed == null || editAbility !in allowed) editAbility = ""
    }

    /**
     * 技能槽收进该物种**能学的**范围 —— 第五件（用户 2026-09-20 拍板「要加」）。
     *
     * 判据与技能候选表**同一个口径**（[buildOptionItems] 的兜底分支：`species.moves.getAllLegalMoves()`）。
     * 学不了的槽**清空** —— 即回「未指定」，生成时那一槽随机补招（数据层的既有语义）。
     *
     * ⚠ 别改成「替他随机填一个合法技能」：界面会凭空多出几个不是他选的技能、看着像自己设的，
     *   反而把「换物种后这里失效了」藏起来；清空则显示「技能1：空」，要重挑一目了然。
     *   服务端也接得住 —— 它只校验技能**存在**，不校验该物种能不能学（[PointMarketService]）。
     */
    private fun normalizeMoves() {
        val ids = MoveText.slotIds(editMoves)
        if (ids.none { it.isNotEmpty() }) return
        val allowed = editSpecies?.moves?.getAllLegalMoves()?.map { it.name }?.toSet() ?: emptySet()
        val fixed = ids.map { if (it.isEmpty() || it in allowed) it else "" }
        if (fixed != ids) editMoves = MoveText.encode(fixed)
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

    // ⚠ 原先这里有个 `confirmRemoveSlot()`（清空 [editSlot]）—— 2026-09-25 服主不再能编辑已有格子后
    //   它变成了死代码（唯一的调用点是编辑窗里那枚「下架」，而那枚按钮已经删了），故一并删掉。
    //   下架的入口现在只有**购买窗右上角那枚**（能管商城的人才看得见），见 [removeFromBuyDialog]。

    // ── 窗口：展开式选项列表（形态 / 性格 / 特性 / 球 / 太晶 / 技能 共用）──

    /**
     * 展开一个选项列表。**展开时窗口其余控件全部隐藏** —— 它们先于列表按钮添加，
     * 不隐藏的话点击会被它们拦截、列表永远点不到（黑名单那儿踩过）。
     */
    private fun openOptionList(kind: String) {
        optionListAll = buildOptionItems(kind)
        // 携带物 / 商品物品一进来就是空的（搜索式，见 buildCarryItems / buildItemCandidates），
        // 不能当成"这个字段没有候选"。
        // ⚠ 商品物品**已经不再从这里进来了**（搜索框常驻在界面上，见 [updateItemSearch]）——
        //   这一支留着当兜底：真调进来也是"空列表 + 边打边搜"，不会被误判成"这个字段没候选"而响失败音
        if (optionListAll.isEmpty() && kind != OPT_CARRY && kind != OPT_ITEM) {
            playFailSound()
            return
        }
        optionListKind = kind
        optionListMultiSelect = kind == OPT_MARKS
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
        optionListItems = if (q.isEmpty()) optionListAll
        else optionListAll.filter { it.label.contains(q, ignoreCase = true) }
        // 过滤后行数变了，滚动位置要回到顶（不然停在一个空白的偏移上）
        optionListScroll = 0
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
            editDialogW() - EDIT_PAD * 2, OPTION_SEARCH_H,
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
     * ⚠ 走 [ItemRuleComponents.extractSpec] —— 与列表里选中一条候选**同一套提取**
     *（见 [selectOption] 的 `OPT_ITEM` 分支），两条路径的组件口径才不会分叉。
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
            // ⚠ includeDamage = true：**卖的是实物**，磨损得跟着走 —— 默认那套是给黑名单 / 价格限制
            //   那种"规则条目"用的（那边磨损是噪音）。漏了它，一把快坏的镐子上架后买家拿到满耐久
            ItemRuleComponents.extractSpec(stack, lookup, includeDamage = true)
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
     * 窗口控件的显隐**统一入口**：展开列表时全藏，否则按滚动可见性。
     * 各写一遍必然漏 —— 展开完列表恢复时会把滚出视野的按钮也一起显示出来。
     */
    private fun updateEditControlsVisibility() {
        // ⚠ 判据是「**有没有东西叠在编辑窗上面**」，不是「选项列表展开了没」——
        //   快照弹窗也是叠层（它盖在添加窗上），只判 optionListKind 的话，
        //   快照弹窗一开编辑窗那批控件就全冒出来了、黑压压压在新弹窗底下。
        val covered = optionListKind != null || snapshotDialog
        editSpeciesField?.visible = !covered
        editPriceField?.visible = !covered
        editStockField?.visible = !covered
        // 滚动区里的输入框：按各自行号的可见性来（见 [scrollableFields]）
        scrollableFields().forEach { (field, key) ->
            field?.visible = !covered && editRowVisible(editRowOf(key))
        }
        editConfirmButton?.visible = !covered
        editCancelButton?.visible = !covered
        editRemoveButton?.visible = !covered
        // 右上角那枚「快照」同理（它也是编辑窗的控件，别忘在清单外）
        editSnapshotButton?.visible = !covered
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
                Text.translatable("cobblemarket.points.store.clear"), { clearMarks() }
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

    /** 清空已挂的证章（多选列表底部的「清空」）—— **不关列表**，清完还能接着挑 */
    private fun clearMarks() {
        editMarks = emptyList()
        refreshEditLabels()
    }

    /** 列表一屏能放几行：**撑满窗口**（顶部到「返回」按钮上方），别在下面空一大片 */
    private fun optionListRows(): Int =
        ((editDialogHeight() - OPTION_LIST_TOP - OPTION_LIST_BOTTOM) / OPTION_ROW_STEP).coerceAtLeast(1)

    /** 列表第 [i] 个可见行的矩形 —— **渲染与点击判定共用这一个**，各算一份必然错位 */
    private fun optionRowRect(i: Int) = com.shusheng.cobblemarket.client.TooltipPlacement.Rect(
        editDialogX() + EDIT_PAD,
        editDialogY() + OPTION_LIST_TOP + i * OPTION_ROW_STEP,
        editDialogW() - EDIT_PAD * 2,
        OPTION_ROW_H
    )

    /** 某行是不是「当前选中」：多选列表看已挂的证章，单选列表比当前值 */
    private fun isOptionSelected(value: String): Boolean =
        if (optionListMultiSelect) {
            // 头目之证的勾选态**跟着头目开关走**（不由服主在这里勾），见 [effectiveMarksOf]
            if (value == ALPHA_MARK_ID) editAlpha else value in editMarks
        } else {
            value == currentOptionValue()
        }

    private fun toggleMultiOption(value: String) {
        // **头目之证不归这里切**：它跟着头目开关走，勾掉也没用（Cobblemon 生成时照样带）——
        // 让它可点反而是误导，所以点了不响应，要改就去动头目那个按钮
        if (value == ALPHA_MARK_ID) return
        editMarks = if (value in editMarks) editMarks - value else editMarks + value
        refreshEditLabels()
        // 不关列表、也不用重建控件：行是自绘的，下一帧就带上新的勾选状态
    }

    private fun currentOptionValue(): String? = when (optionListKind) {
        OPT_FORM -> editAspects.joinToString(",")
        OPT_NATURE -> editNature
        OPT_ABILITY -> editAbility
        OPT_BALL -> editBall
        OPT_TERA -> editTera
        OPT_CARRY -> editHeldItem.orEmpty()
        // 证章是多选，没有单一「当前值」（选中判定走 isOptionSelected）
        OPT_MARKS -> null
        else -> optionListKind?.removePrefix(OPT_SKILL)?.toIntOrNull()
            ?.let { MoveText.slotIds(editMoves).getOrNull(it).orEmpty() }
    }

    private fun selectOption(value: String) {
        when (optionListKind) {
            OPT_FORM -> {
                editAspects = value.split(",").filter { it.isNotEmpty() }
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
                // 候选自带重建好的物品栈（见 [buildItemCandidates]）—— 与「从主手取」用**同一套提取**
                //（[ItemRuleComponents.extractSpec]），两条路径的组件口径才不会分叉
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
                SpeciesText.selectableForms(species).forEach { form ->
                    val aspects = form.aspects
                    if (aspects.isNotEmpty()) {
                        opts.add(
                            OptionItem(
                                aspects.joinToString(","),
                                aspects.joinToString("/") { SpeciesText.aspectLabel(it) }
                            )
                        )
                    }
                }
                opts.distinctBy { it.value }
            }

            // 性格：显示名走 Cobblemon 自己的词条（照市场筛选面板的写法）
            OPT_NATURE -> com.cobblemon.mod.common.api.pokemon.Natures.all().map { n ->
                val key = "cobblemon.nature.${n.name.path}"
                val t = Text.translatable(key).string
                OptionItem(n.name.toString(), if (t == key) n.displayName else t)
            }

            // 特性：该物种的全部可能特性（含隐藏特性）
            OPT_ABILITY -> species?.abilities?.toList().orEmpty().map { potential ->
                val ability = potential.template
                OptionItem(ability.name, Text.translatable(ability.displayName).string)
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
                addAll(
                    species?.moves?.getAllLegalMoves().orEmpty()
                        .filter { it.name !in takenByOthers }
                        .map { OptionItem(it.name, it.displayName.string, labelText = MoveText.name(it.name)) }
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
        if (q.isEmpty()) return emptyList()
        val opts = mutableListOf(
            OptionItem("", Text.translatable("cobblemarket.points.store.value.none").string)
        )
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
        drawNineSlice(
            context, DIALOG_BACKGROUND_TEXTURE, x, y, editDialogW(), editDialogHeight(), 0, DIALOG_BACKGROUND_TEX_H
        )

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
            // 精灵 / 物品两种窗口的标题分开（物品格子点开时写「添加精灵」就驴唇不对马嘴了）。
            // ⚠ 这个窗口**只有「添加」一种形态**：编辑已有格子 2026-09-25 已取消（要改走「下架 → 重新添加」），
            //   快照回填也只是把一份规格填进**新增**的草稿。
            //   ⚠ **别拿 `editSpecBase` 判**：它装的是 copy 基底，快照回填后就非 null 了 ——
            //     按它判会让标题错显成「编辑精灵」（2026-09-25 用户报）
            val pokemon = isPokemonSlot()
            Text.translatable(
                if (pokemon) "cobblemarket.points.store.add_title"
                else "cobblemarket.points.store.add_item_title"
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
            renderOptionRows(context, mouseX, mouseY)
            renderOptionListDecor(context, x, y)
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
                KEY_FRIENDSHIP to "cobblemarket.points.store.field.friendship_label"
            )) {
                // 亲密度在小窗口里放不下标签 —— 那时输入框用的是全宽，这里就不能画（见 friendshipLabelW）
                if (key == KEY_FRIENDSHIP && !showFriendshipLabel()) continue
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
        val labelY = editPriceRowY() + (EDIT_ROW_H - 8) / 2
        context.drawTextWithShadow(
            textRenderer, Text.translatable("cobblemarket.points.store.field.price"),
            editPriceGroupX(), labelY, 0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer, Text.translatable("cobblemarket.points.store.field.stock"),
            editPriceGroupX() + editPriceColW() + EDIT_COL_GAP, labelY, 0xFFFFFF
        )
    }

    /** 物种预览槽：槽位贴图 + 精灵模型 + 候选计数（照黑名单的添加精灵弹窗） */
    private fun renderEditPreviewSlot(context: DrawContext, x: Int, y: Int, delta: Float) {
        // 预览区：右侧 3 格 × 上 2 行，槽位居中在里头、精灵再在槽里居中（比原来那个 24px 的小图标大得多）
        val slotSize = editPreviewSize()
        val slotX = editPreviewSlotX()
        val slotY = editPreviewSlotY()
        drawPokemonSlot(context, slotX, slotY, slotSize)

        val renderable = editPreview
        if (renderable != null) {
            val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
            try {
                PokemonIconRenderer.draw(
                    context = context,
                    renderable = renderable,
                    state = editPreviewState,
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
                    IconCryClick.play(editPreviewState, renderable)
                }
            } catch (_: Exception) {
            }
        }

        // 候选计数（1/3）：居中于物种输入框与预览槽之间那段空隙
        if (editSpeciesCandidates.size > 1) {
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
        // 左缘**与上面那条搜索框对齐**（都从 [EDIT_PAD] 起）—— 物品窗左侧 3 列是空的（没有字段网格），
        // 整条正好当信息栏用。
        // ⚠ 别从 [editSpeciesRightX] 起：那里到大图只剩 **73px**（一列 82px 的宽度），
        //   英文物品名一长就截得只剩几个字母；从内边距起有 340 多像素。
        val left = editDialogX() + EDIT_PAD
        val maxWidth = slotX - EDIT_ITEM_INFO_GAP - left
        if (maxWidth <= 0) return
        val shown = lines.take((slotSize / EDIT_ITEM_INFO_LINE_H).coerceAtLeast(1))
        // 垂直方向**与大图居中对齐**（整列文字居中在大图那一格的高度里）
        var y = slotY + (slotSize - shown.size * EDIT_ITEM_INFO_LINE_H) / 2
        shown.forEach { (line, color) ->
            // 超宽的截到列宽内 —— 走保留分段样式的版本，别把物品名的稀有度色洗成白的
            val text = if (textRenderer.getWidth(line) > maxWidth) TextUtil.truncateStyled(line, maxWidth) else line
            context.drawTextWithShadow(textRenderer, text, left, y, color)
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
     * 展开列表的行 —— **自绘**（不用 NineSliceButton）：行首要画物品图标（球种、携带物），
     * 而按钮只支持贴图 id 的图标，球是物品、得走物品渲染。
     */
    private fun renderOptionRows(context: DrawContext, mouseX: Int, mouseY: Int) {
        val rows = optionListRows()
        // 搜索式列表（携带物）还没输入 / 没命中时：给一行提示，别留一片空白看着像坏了
        if (optionListItems.isEmpty()) {
            val r = optionRowRect(0)
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.translatable("cobblemarket.points.store.search_item_hint").formatted(Formatting.GRAY),
                r.x + r.w / 2, r.y + (OPTION_ROW_H - 8) / 2, 0xFFFFFF
            )
            return
        }
        val hovered = hoveredOptionRow(mouseX.toDouble(), mouseY.toDouble())
        optionListItems.drop(optionListScroll).take(rows).forEachIndexed { i, opt ->
            val r = optionRowRect(i)
            // 行背景：与其余界面的列表行**同一张** `row_background.png`。
            // ⚠ 三段是 **0 普通 / 1 悬停 / 2 选中**，别拿 1 当选中用（项目里其他列表的 `1` 都是悬停）。
            // ⚠ 也别拿弹窗背景凑合 —— 那是给大窗口的，装饰条压到 16px 高的行上会糊成一条条红线。
            val state = when {
                isOptionSelected(opt.value) -> 2
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
            // 超长才截断（截断后会变纯文本 —— 极少发生，技能名一般十来个字，行宽 240 放得下）
            if (textRenderer.getWidth(text) > maxW) text = Text.literal(TextUtil.truncateString(text.string, maxW))
            // 行很长，文字**居中**才好看；但**不能**用 drawCenteredTextWithShadow（它会让行首汉字
            // 抬高一点），所以自己算 x 再 drawText（混排文字的老坑，见项目约定）
            val drawX = r.x + (r.w - textRenderer.getWidth(text)) / 2
            context.drawTextWithShadow(textRenderer, text, drawX, r.y + (OPTION_ROW_H - 8) / 2, 0xFFFFFF)
        }
    }

    /** 展开列表的滚动条（条目装得下就不画） */
    private fun renderOptionListDecor(context: DrawContext, x: Int, y: Int) {
        val total = optionListItems.size
        val rows = optionListRows()
        if (total <= rows) return
        val top = y + OPTION_LIST_TOP
        val trackH = rows * OPTION_ROW_STEP
        val barX = x + editDialogW() - EDIT_PAD + 3
        context.fill(barX, top, barX + 1, top + trackH, 0x40FFFFFF)
        val thumbH = (trackH * rows / total).coerceAtLeast(6)
        val maxScroll = (total - rows).coerceAtLeast(1)
        val thumbY = top + (trackH - thumbH) * optionListScroll / maxScroll
        context.fill(barX - 1, thumbY, barX + 2, thumbY + thumbH, 0xFFFFFFFF.toInt())
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
        // 三列下按钮很窄，值（物品名 / 技能名）可能很长 —— 统一截断，别让它溢出按钮外
        fun label(key: String, value: String, width: Int = editColW()): Text =
            Text.translatable(
                "cobblemarket.points.store.field.$key",
                TextUtil.truncateString(value, width - 8)
            )

        refreshShinyButton()
        editButtons[KEY_FORM]?.setMessage(label("form", formLabel()))
        refreshGenderButton()
        editButtons[KEY_NATURE]?.setMessage(label("nature", natureLabel()))
        editButtons[KEY_ABILITY]?.setMessage(label("ability", abilityLabel()))
        editButtons[KEY_BALL]?.setMessage(label("ball", ballLabel()))
        editButtons[KEY_SIZE]?.setMessage(label("size", sizeLabel()))
        refreshAlphaButton()
        // ⚠ 太晶的值要**带属性色**：不能走 label() 那条只收 String 的通道（一转 String 颜色就没了），
        //   直接拼 Text —— 与选项列表、悬停窗的技能块同一张色表（2026-09-20 用户实测：按钮上还是白字）
        editButtons[KEY_TERA]?.setMessage(
            Text.translatable("cobblemarket.points.store.field.tera", teraText())
        )
        editButtons[KEY_GMAX]?.setMessage(label("gmax", yesNoLabel(editGmax)))
        // 这个物种没有超极巨化形态 → 同性别按钮：**整个按钮置灰**（`dimmed` 盖黑遮罩，
        // 就是入口界面喵喵银行关闭时那个做法；`active = false` 只是文字变暗，不够）
        editButtons[KEY_GMAX]?.dimmed = !speciesCanGmax()
        editButtons[KEY_TRADEABLE]?.setMessage(label("tradeable", yesNoLabel(editTradeable)))
        // 携带物 / 球种：**标签 + 物品图标**（「携带物：[图标]」）——
        // 标签只留字段名不带值（值由图标表达，这才是去文字化的本意）；没设时只有标签
        editButtons[KEY_HELD]?.setMessage(label("held", ""))
        editButtons[KEY_HELD]?.iconStack = stackOfSpec(editHeldItem).takeIf { !it.isEmpty }
        editButtons[KEY_BALL]?.setMessage(label("ball", ""))
        editButtons[KEY_BALL]?.iconStack = itemStackOf(editBall)
        editButtons[KEY_MIN_IVS]?.setMessage(label("min_ivs", minIvsLabel()))
        // 「保底 V 数」与「六项个体值」**双向互斥**（用户 2026-09-20 拍板）：
        // 六项填了 → 保底按钮置灰点不动；保底 > 0 → 六项输入框整个锁住。两个方向都在这里统一落地
        editButtons[KEY_MIN_IVS]?.dimmed = hasIvInput()
        applyIvInputLock()
        editButtons[KEY_HELD_VISIBLE]?.setMessage(label("held_visible", yesNoLabel(editHeldVisible)))
        editButtons[KEY_MARKS]?.setMessage(label("marks", marksLabel()))
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
        // 「清空」只清**服主勾的那些** —— 头目之证不归它管（清了也还在），
        // 所以判据要排掉它，否则只剩头目之证时按钮还是亮的、点了却什么都没变
        optionClearButton?.active = editMarks.any { it != ALPHA_MARK_ID }
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
        if (editAspects.isEmpty()) valueText("default")
        else editAspects.joinToString("/") { SpeciesText.aspectLabel(it) }

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

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val bg = bgRect()
        // ⚠ drawTexture 的 width/height **同时是「从源图采样的区域尺寸」**（1:1 采样），
        // 不是目标缩放尺寸 —— 直接传缩放后的目标尺寸，只会把原图左上角那一块抽出来放大
        // （2026-09-18 用户实测踩到：「只剩整张图的左上角」）。
        // 缩放必须走矩阵：按原图尺寸绘制、再整体缩到目标大小（同入口掉落动画的做法）。
        context.matrices.push()
        context.matrices.translate(bg.x.toDouble(), bg.y.toDouble(), 0.0)
        context.matrices.scale(bgScale(), bgScale(), 1f)
        context.drawTexture(BACKGROUND, 0, 0, 0f, 0f, TEX_W, TEX_H, TEX_W, TEX_H)
        context.matrices.pop()

        // 兑换结果提示：水平居中、落在**返回按钮底边与背景底边之间**那段空隙的中点
        // （用户 2026-09-19 指定）。此前画的是**背景正中** —— 压着界面内容很难看，
        // 注释写着「返回按钮与背景底边之间」但代码没落实，这次补上。
        // ⚠ 传**原来的 Text**，别转成 String 再拼 —— 结果里带 RP 图标字形（字体挂在 Text 样式上），
        //   一转 String 图标就变成方块（本项目反复踩过的坑）
        resultMsg?.let { msg ->
            if (System.currentTimeMillis() < resultUntil) {
                // 返回按钮底边 = bg 底边往上 BACK_BOTTOM（原图口径），这段空隙的中心再上移半行字高
                val slotCenterY = bg.y + bg.h - texPx(BACK_BOTTOM) / 2
                context.drawCenteredTextWithShadow(
                    textRenderer, msg,
                    bg.x + bg.w / 2,
                    slotCenterY - 4,
                    resultColor
                )
            }
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
        super.render(context, mouseX, mouseY, delta)
        if (dialogOpen()) return
        drawMarketDecor(context, delta)
    }

    private fun drawMarketDecor(context: DrawContext, delta: Float) {
        val bg = bgRect()
        // 标题：与其余界面同一套写法（**金色样式挂在 Text 上**，绘制行色仍是白 ——
        // 直接把行色写成金色是错的，那样整行都是金的、与全模组颜色模板不符）
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemarket.points.market.title", CurrencyService.pointsTitleIcon())
                .formatted(Formatting.GOLD),
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

        val entry = PointMarketCache.slots[globalSlot(cell)] ?: return
        val icon = cellIcon(globalSlot(cell), entry)

        // 内容跟着**当前画出来的那个格子**走（悬停时 drawSize 就是放大后的边长）——
        // 只放大底图、内容原地不动的话，放大后的格子里精灵会缩在中间显得很小。
        // ⚠ 价格与数量是例外：它们只"贴边"不放大（字号不动），文字用矩阵缩放会发糊。
        val drawSize = size + grow * 2
        val drawX = x - grow
        val drawY = y - grow

        // 图标区：上边距取边长 8% **再下移 [ICON_TOP_NUDGE]**，下边到价格行上方，再按比例取小值 ——
        // 小格子里自动缩小、不压到价格（写死比例的话，格子小于约 55px 就会叠在一起）
        //
        // ⚠ 那个 4px 的下移是必要的：格子上边框才 6px 宽，而 8% 的上边距（70px 格子约 6px）
        //   正好把图标顶到边框内缘，3D 模型再往上溢出 4px 就**盖住边框**了（2026-09-20 用户指出）。
        //   代价：底部让给价格行、动不了，所以图标会按比例小一点点（70px 格子约小 3px）。
        val iconTop = drawY + (drawSize * ICON_TOP_RATIO).roundToInt() + ICON_TOP_NUDGE
        val iconBottom = drawY + drawSize - PRICE_BOTTOM_GAP - 2
        val iconSize = (iconBottom - iconTop)
            .coerceAtMost((drawSize * ICON_SIZE_RATIO).roundToInt())
            .coerceAtLeast(MIN_ICON_SIZE)
        val iconX = drawX + (drawSize - iconSize) / 2
        val iconY = iconTop

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
        context.drawCenteredTextWithShadow(
            textRenderer,
            // 金额走**缩写**（`9,999,999` → `9.9M`）：格子就这么宽，完整价格会长到顶破整个格子
            //（用户 2026-09-20 报）。与列表界面同一个口径（[formatPriceShortLong]，它的注释就写着
            //「小格子用的价格缩写」），网格这边当初漏了。
            // ⚠ 缩写串是**字符串**喂进 amountText 的：`goldAmount → formatAmount` 对非数值类型
            //   **原样输出**（不套千分位），所以不会被二次加工；RP 图标仍由 unitText 补，没走字符串拼接
            CurrencyService.amountText(formatPriceShortLong(entry.price), MarketCurrency.POINTS),
            x + size / 2, bottom - PRICE_BOTTOM_GAP, 0xFFFFFF
        )

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
        val BACKGROUND = Identifier.of("cobblemarket", "textures/gui/points_market_background.png")
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
        const val EDIT_DIALOG_H = 262

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

        /** 证章悬停窗：每个图标占的格子边长（与 `EntryBadgeRenderer.drawMarksRow` 的 12px 同口径） */
        const val MARK_CELL = 12

        /** 证章悬停窗的内边距 */
        const val MARK_TIP_PAD = 5

        /**
         * 添加窗右上角「快照」按钮的宽度。
         * 中文「快照」36px、英文 `Snapshots` 54px —— 取宽的再加边距（项目一贯的"按最宽文案定宽"）。
         */
        const val EDIT_SNAPSHOT_W = 62

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
         * 等级那一格的「Lv.」前缀词条 —— **与全项目等级行同一个**（`Lv.25` 那个写法，
         * 见 [EntryBadgeRenderer]），不另起词条，免得两处慢慢写岔。
         */
        const val LEVEL_LABEL_KEY = "cobblemarket.gui.lv"
        const val KEY_FRIENDSHIP = "friendship"
    }
}
