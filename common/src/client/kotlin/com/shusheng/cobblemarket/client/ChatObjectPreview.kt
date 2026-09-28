package com.shusheng.cobblemarket.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.ItemHoverPreview
import com.shusheng.cobblemarket.client.icon.PokemonHoverPreview
import com.shusheng.cobblemarket.network.ListingEntry
import com.shusheng.cobblemarket.network.ObjectPreviewNetwork
import com.shusheng.cobblemarket.network.ObjectPreviewPayload
import com.shusheng.cobblemarket.network.RequestObjectPreviewPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.screen.EntryBadgeRenderer
import com.shusheng.cobblemarket.screen.ROW_BACKGROUND_TEX_H
import com.shusheng.cobblemarket.screen.ROW_BACKGROUND_TEXTURE
import com.shusheng.cobblemarket.screen.drawNineSlice
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.UUID

/**
 * **聊天播报的悬浮大图**（按 T 打开聊天 → 鼠标停在拍卖/求购的全服播报上）。
 *
 * 播报里的拍品名挂了客户端指令（见 `AuctionNetwork.announceAuction` / `BuyOrderNetwork`），
 * 这里就顺着那条 `clickEvent` 认出「鼠标下是哪个拍品」，再去服务端要数据（[RequestObjectPreviewPayload]）
 * 并在**屏幕正中**画大图（大图自己居中，不在「右半屏」了 —— 2026-09-29 用户定的），
 * 再于它**右侧**排一块词条面板：精灵走 [EntryBadgeRenderer.drawInfoLines]（与精灵市场行悬停窗
 * 同源，含等级 / 属性 / IV / 技能 / 证章…），物品走原版 tooltip（附魔 / 药水 / 属性修饰符全自动继承）。
 *
 * ⚠ **不再挂原版 HoverEvent 小窗**（那个灰框）：小窗的内容必须服务端拼文本，
 * 拼不出完整词条、长行还会显示不下，精灵更是连图标都画不了 ⇒ 两条路体验割裂
 * （2026-09-29 用户拍板：精灵与物品**统一用这一套**）。播报上的 `clickEvent` **保留** ——
 * 它是这里识别「鼠标下是哪条播报」的唯一依据。
 *
 * 三条与列表预览不同的规矩（2026-09-17 拍板）：
 * - **不受「精灵预览图/物品预览图」开关约束**：聊天里的大图无条件显示（居中模式跳过开关判断）
 * - **防扫过**：同一拍品连续悬停 [HOVER_DWELL_MS] 才算选中 —— 鼠标划过一屏聊天时不该闪一片大图
 * - **鼠标移开立刻收起**（不进淡出、不留残影），缓存保留，回头再悬停秒显
 *
 * 识别方式与具体是哪条播报无关：**只要那段聊天文字挂了 `auction` / `review` 指令就生效**
 * （新增播报时不必改这里）。叫声沿用 [IconCryClick]（随机普通叫/战吼、静态模式与睡觉不叫）：
 * 显示后延迟 [IconCryClick.AUTO_CRY_DELAY_MS] 才响，理由与各弹窗一致 ——
 * 大图刚出现时玩家还没反应过来声音从哪来。
 */
object ChatObjectPreview {

    /**
     * 扫过不算：同一拍品要连续悬停这么久才显示（聊天里滑过一段文字太容易了）。
     *
     * ⚠ 2026-09-29 由 **250 缩到 120**：**原版那个灰框小窗是立即出现的**，舍弃它之后若还要停留
     * 四分之一秒，玩家会觉得「悬停半天没反应」而不再多看一眼。120ms 只挡真正的「扫过」——
     * 手感上接近即时（这几帧里大图还没冒出来，玩家察觉不到）。
     */
    private const val HOVER_DWELL_MS = 120L

    /** 缓存上限与存活时长：拍品数据不会变（成交后条目本身就查不到了），但也不能无限攒 */
    private const val CACHE_MAX = 64
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    /** 「已请求过」集合的上限：悬停过的 id 会一直堆着，聊天刷屏时几小时能攒出几千条 */
    private const val REQUESTED_MAX = 256

    /**
     * 两次请求之间的最小间隔，**必须大于服务端 [com.shusheng.cobblemarket.util.RequestThrottle.READ_INTERVAL_MS]（500ms）**：
     * 服务端对超限的请求是**静默丢弃**，而超限那次如果被记成「已请求」就永远不会补发 ——
     * 表现为「连着悬停两个播报，第二个的大图死活不出来」。所以客户端自己先让开窗口，等下一帧再发。
     */
    private const val REQUEST_MIN_INTERVAL_MS = 550L

    /**
     * 单帧最多推进的等价 tick 数（1 秒 = 20 单位），上限同庆祝动画。
     * 卡顿/断点之后一帧可能隔很久，不封顶会一次性把动画跳完。
     */
    private const val MAX_SELF_DELTA = 2f

    /** 播报里挂的指令前缀（服务端 `withClickEvent` 写的就是它） */
    private const val COMMAND_PREFIX = "/cobblemarket "

    /**
     * 缓存里的一条拍品：[isPokemon] 分流用哪套渲染器，另一个字段恒为 null。
     *
     * [listing] / [name] 只有精灵有 —— 大图右侧那块信息行要用它们：
     * - [listing]：服务端随包下发的完整信息（`ObjectPreviewPayload.listing`）；
     *   求购的**需求**预览与拍卖的 NBT 损坏兜底**没有**它 ⇒ 那种情况只画大图、不画信息行
     * - [name]：**名字行**（物种按官方词条 key 翻译、闪光带 ★）。⚠ 不能拿 [ListingEntry.species]
     *   现拼 —— 那个字段是**裸物种名**（`pikachu`），`Text.translatable` 翻不出来
     */
    private class Entry(
        val isPokemon: Boolean,
        val renderable: RenderablePokemon?,
        val state: FloatingState?,
        val stack: ItemStack?,
        val at: Long,
        val listing: ListingEntry? = null,
        val name: Text? = null
    )

    /** 大图数据缓存（插入序，超上限淘汰最旧的） */
    private val cache = LinkedHashMap<String, Entry>()

    /** 已请求过的 id：同一拍品只发一次请求（悬停期间每帧都会走到这里，不能每帧发一包） */
    private val requested = LinkedHashSet<String>()

    /** 上次实际发出请求的时刻（客户端让开服务端限流窗口用，见 [REQUEST_MIN_INTERVAL_MS]） */
    private var lastRequestAt = 0L

    /** 本帧鼠标下的拍品 id（null = 没停在播报上），与 [hoverSince] 一起算停留时长 */
    private var hoverId: String? = null
    private var hoverSince = 0L

    /** 正在显示大图的拍品 id（null = 没显示） */
    private var shownId: String? = null

    /** 待播叫声时刻（0 = 无）。每次「开始显示」重排一次，见 [beginShow] */
    private var cryAt = 0L

    /** 上次推进动画的真实时间戳（纳秒），0 = 这一只还没推进过 */
    private var lastFrameNanos = 0L

    /**
     * 每帧入口：由 ScreenMixin 在 `Screen.renderWithTooltip` 末尾调用（参数就是原版给界面的鼠标坐标）。
     *
     * **只在聊天界面生效**：其余界面（市场各界面、其它模组界面）直接收摊返回，
     * 调用点在渲染链末尾，不影响任何既有图层。
     */
    @JvmStatic
    fun render(context: DrawContext, mouseX: Int, mouseY: Int) {
        val client = MinecraftClient.getInstance()
        // 只认原版聊天界面：ChatHud.getTextStyleAt 内部也要求聊天聚焦（即当前界面是 ChatScreen）
        if (client.currentScreen !is ChatScreen) {
            exitChat()
            return
        }
        // 图标注册表由各界面在自己 render 开头 beginFrame；聊天界面没有图标，这里补一次，
        // 顺便把鼠标位置刷新给大图 —— 头部跟随读的是 IconRegistry 里的坐标，不刷新就会用上一个界面的旧值
        IconRegistry.beginFrame(mouseX, mouseY)

        val now = System.currentTimeMillis()
        val hovered = hoveredAnnouncement(client, mouseX, mouseY)
        if (hovered == null) {
            exitChat()
            return
        }
        if (hovered.id != hoverId) {
            hoverId = hovered.id
            hoverSince = now
        }
        // 防扫过：还没停够时间就先不显示
        if (now - hoverSince < HOVER_DWELL_MS) {
            hide()
            return
        }

        val entry = cached(hovered.id, now)
        if (entry == null) {
            // 数据没到：发一次请求（同一 id 只发一次），这帧先不显示
            requestOnce(hovered, now)
            hide()
            return
        }
        if (shownId != hovered.id) beginShow(hovered.id, entry, now)
        draw(context, entry)
    }

    /** 服务端回包（客户端主线程调用）：建好渲染对象入缓存 */
    @JvmStatic
    fun onData(payload: ObjectPreviewPayload) {
        // 只收自己请求过的 id：防连发的另一半，也防服务端往缓存里塞没请求过的条目
        if (payload.id !in requested) return
        val now = System.currentTimeMillis()
        // 没有世界就没有注册表：物种/物品栈都重建不了（正常不会走到，服务端只在玩家在线时发包）
        val registryManager = MinecraftClient.getInstance().world?.registryManager ?: return

        val entry = if (payload.isPokemon) {
            val speciesId = Identifier.tryParse(payload.speciesId) ?: return
            val species = PokemonSpecies.getByIdentifier(speciesId) ?: return
            // 形态照各界面统一口径：挂单真实 aspects + 闪光补 "shiny"（缺了这一步闪光精灵画成普通色）
            val aspects = payload.aspects.toMutableSet()
            if (payload.shiny && "shiny" !in aspects) aspects.add("shiny")
            // 携带物喂给模型：Cobblemon 会把它渲染在手上/嘴上，不传的话大图里的精灵是空手的
            val held = payload.heldItemNbt?.let { ItemStack.fromNbtOrEmpty(registryManager, it) } ?: ItemStack.EMPTY
            Entry(
                isPokemon = true,
                // 大图里的**模型**：玩家设了「隐藏」就不拿东西（别的界面同理，用户 2026-09-26 拍板）
                renderable = RenderablePokemon(species, aspects, if (payload.heldItemVisible) held else ItemStack.EMPTY),
                state = FloatingState(),
                stack = null,
                at = now,
                // 右侧信息行（服务端组装好随包下发；没有就只画大图）
                listing = payload.listing,
                // 名字行走**官方词条 key**（`cobblemon.species.<name>.name`）—— 与市场同一口径
                name = if (payload.listing == null) null else EntryBadgeRenderer.nameWithShinyStar(
                    Text.translatable(speciesNameKey(payload.speciesId)), payload.shiny
                )
            )
        } else {
            // 拍卖存整栈 NBT、求购单存组件快照 —— 两种格式各走各的重建入口（都以 itemId 兜底）
            val stack = if (payload.itemNbt != null) iconStackOf(payload.itemId, payload.itemNbt)
            else ItemComponentsDisplay.iconStack(payload.itemId, payload.itemComponentsSpec)
            if (stack == null || stack.isEmpty) return
            // 数量以包里单独传的为准：挂单 NBT 里存的是上架总量（同庆祝动画的处理）
            stack.count = payload.count.coerceAtLeast(1)
            Entry(isPokemon = false, renderable = null, state = null, stack = stack, at = now)
        }
        cache[payload.id] = entry
        trimCache(now)
    }

    /** 离开聊天界面：悬停计时一起作废（下次进来要重新停留），缓存保留 */
    private fun exitChat() {
        hoverId = null
        hide()
    }

    /** 收起大图：鼠标移开、停留不够、数据没到都走这里（缓存不动，回头秒显） */
    private fun hide() {
        shownId = null
        cryAt = 0L
    }

    /** 开始显示某个拍品：排一次叫声（**只在真正开始显示时排**，否则每帧都会重排、永远叫不出来） */
    private fun beginShow(id: String, entry: Entry, now: Long) {
        shownId = id
        // 重置帧间隔基准：上次显示是几分钟前的话，第一帧会带着一个巨大的 delta
        lastFrameNanos = 0L
        // 物品不叫；精灵安排「显示后 AUTO_CRY_DELAY_MS」再叫（理由见类注释）
        cryAt = if (entry.isPokemon) now + IconCryClick.AUTO_CRY_DELAY_MS else 0L
    }

    /** 物种 ID（`cobblemon:pikachu`）→ 官方名字词条 key（`cobblemon.species.pikachu.name`） */
    private fun speciesNameKey(speciesId: String): String =
        "cobblemon.species.${speciesId.substringAfter(':').lowercase()}.name"

    private fun draw(context: DrawContext, entry: Entry) {
        // 横向落点 = **屏幕正中**（2026-09-29 用户拍板，原先在右半屏的中心 = 屏宽的 3/4）：
        // 大图自己是严格居中的那一个，词条面板再从它**右缘**往后排 ——
        // ⚠ 不是"大图 + 面板两块合起来居中"（那样大图会偏左），用户 2026-09-29 专门澄清过
        val centerX = context.scaledWindowWidth * 0.5f
        val screenHeight = context.scaledWindowHeight
        if (entry.isPokemon) {
            val renderable = entry.renderable ?: return
            val state = entry.state ?: return
            // 时间轴得自己推：悬停大图那份故意传 partialTicks = 0（列表里由行内图标推进），
            // 聊天里没有行内图标。按真实帧间隔换算成等价 tick 数，别用 tickDelta ——
            // FloatingState 是**累加**它的，那样动画会跟着帧率跑（庆祝动画踩过这个坑）。
            // 图标设为「静态」时不推：模型停在原地，与列表里静态模式的表现一致
            // （[IconCryClick.play] 自己也会在静态模式下不响，两边判据相同）
            if (ClientConfig.iconAnimMode == IconAnimMode.FLOAT) advanceAnimation(state)
            if (cryAt != 0L && System.currentTimeMillis() >= cryAt) {
                cryAt = 0L
                IconCryClick.play(state, renderable)
            }
            // panelLeft 传 0：居中模式下它不参与定位（大图固定在屏幕正中，屏幕高度仍用于纵向居中）
            PokemonHoverPreview.render(
                context = context,
                renderable = renderable,
                state = state,
                panelLeft = 0,
                screenHeight = screenHeight,
                centerX = centerX
            )
            // 右侧那块完整信息行（与精灵市场行悬停窗同源）；没有数据就只留大图
            val listing = entry.listing
            val name = entry.name
            if (listing != null && name != null) {
                drawPokemonInfoPanel(context, listing, name, centerX, screenHeight)
            }
        } else {
            val stack = entry.stack ?: return
            ItemHoverPreview.render(
                context = context,
                stack = stack,
                panelLeft = 0,
                screenHeight = screenHeight,
                centerX = centerX
            )
            drawItemTooltipPanel(context, stack, centerX, screenHeight)
        }
    }

    // ── 词条面板的尺寸 ──
    /** 大图半径（`ItemHoverPreview` 放大 8 倍 ⇒ 16×8÷2 = 64），词条面板据此贴到它右边 */
    private const val ICON_HALF = 64f
    /** 大图与词条面板之间的空隙 */
    private const val PANEL_GAP = 8
    /** 词条面板的内边距 */
    private const val PANEL_PAD = 4
    /** 一行词条的高度（默认字体 9 + 1） */
    private const val LINE_H = 10

    /**
     * 大图**右侧**列出的完整物品词条（**带九宫格背景** —— 2026-09-29 用户定：不能凭空飘字）。
     *
     * 为什么要它：很多物品**模型长得一样、区别全在词条上**，只给个大图等于没说
     * （2026-09-29 用户报的）。悬停小窗那条路是**服务端**拼文本、补不成原版那种完整词条；
     * 这里是**客户端**渲染 ⇒ 直接拿原版 tooltip：附魔 / 属性修饰符 / 药水 / lore / 耐久
     * 全都有，**Shift / Ctrl 展开也自动继承**，不用自己实现。
     *
     * ⚠ 调用点与它服务的大图一样，走的是「展示 / 拍卖 / 求购播报」**共用的那套**。
     * ⚠ 词条**保留第一行**：这一侧的大图没有物品名（不像列表悬停窗那边已经画了名字、要 `.drop(1)`）。
     */
    private fun drawItemTooltipPanel(context: DrawContext, stack: ItemStack, centerX: Float, screenHeight: Int) {
        val client = MinecraftClient.getInstance()
        val lines = ItemComponentsDisplay.itemTooltip(stack, client.player, TooltipType.BASIC)
        if (lines.isEmpty()) return
        val font = client.textRenderer

        // 大图占 [centerX - ICON_HALF, centerX + ICON_HALF]（放大倍数与 ItemHoverPreview 的 8 倍一致）
        val panelX = (centerX + ICON_HALF + PANEL_GAP).toInt()
        val availW = (context.scaledWindowWidth - panelX - 6).coerceAtLeast(48)
        val availH = screenHeight - 16

        // 高度不够就少画几行（末行加个「…」表示被截了）—— 词条多的物品（附魔堆满）会很长
        val maxRows = (availH - PANEL_PAD * 2) / LINE_H
        val shown = lines.take(maxRows.coerceAtLeast(1))

        val textW = shown.maxOf { font.getWidth(it) }
        val panelW = (minOf(textW, availW) + PANEL_PAD * 2).coerceAtLeast(40)
        val panelH = shown.size * LINE_H + PANEL_PAD * 2
        val panelY = (screenHeight / 2 - panelH / 2)

        // HUD 层浮窗用行背景那套九宫格（与项目里自绘悬停窗同一个贴图）
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, panelX, panelY, panelW, panelH, 1, ROW_BACKGROUND_TEX_H)

        shown.forEachIndexed { i, line ->
            // 超宽的单行截断（保分段样式：附魔行是分段的，压成一段会丢颜色）
            val cut = TextUtil.truncateStyled(line, availW)
            context.drawTextWithShadow(font, cut, panelX + PANEL_PAD, panelY + PANEL_PAD + i * LINE_H, 0xFFFFFF)
        }
        if (shown.size < lines.size) {
            // 被截掉了：末行右下角补一个省略号（完整内容请到物品栏里看）
            context.drawTextWithShadow(
                font, Text.literal("…"),
                panelX + panelW - PANEL_PAD - font.getWidth("…"),
                panelY + PANEL_PAD + (shown.size - 1) * LINE_H, 0x888888
            )
        }
    }

    /**
     * 大图**右侧**列出的**精灵完整信息行**（带九宫格背景）—— 与精灵市场行悬停窗**同一套**：
     * 同一份行列表、同一种排版（`EntryBadgeRenderer.drawInfoLines`），玩家看到的
     * 等级 / 属性+太晶 / 极巨 / 性格 / 特性 / 球种 / 携带物 / IV+EV / 亲密度 / 初始训练家 /
     * 技能 / 证章 / 卖家 / 价格，与在市场里悬停同一只时**一字不差**。
     *
     * 数据来自服务端随包下发的 [ListingEntry]（见 `ObjectPreviewPayload.listing`）——
     * 客户端原本只有物种 / 形态 / 闪光 / 携带物，**画不出信息行**，所以那几个字段由服务端顺手组装。
     *
     * ⚠ 面板**先量后画**：`drawInfoLines` 是「边画边返回结束 y」的，而背景框必须动笔前就摆好 ⇒
     *   宽度走 `measureInfoLines` / `movesBlockWidth` / `nameLineWidth`、
     *   高度走 `measureInfoLinesHeight`。后两个与 `drawInfoLines` 的行列表**严格对应**，
     *   改那个函数的行列表时这三处要一起改（少算一行 = 框比内容矮一截）。
     */
    private fun drawPokemonInfoPanel(
        context: DrawContext, listing: ListingEntry, name: Text, centerX: Float, screenHeight: Int
    ) {
        val panelX = (centerX + ICON_HALF + PANEL_GAP).toInt()
        // 屏幕右缘以内的可用宽：放不下就按它截断（长精灵名 / 长特性名），别画到屏幕外
        val availW = (context.scaledWindowWidth - panelX - 6).coerceAtLeast(64)

        // 内容宽：信息行 / 技能块 / 名字行 / 携带物图标 / 技能块那条固定兜底 —— 口径全在
        // EntryBadgeRenderer.infoPanelContentWidth 里（与 drawInfoLines 同源，别在这儿另算一遍）
        val contentW = EntryBadgeRenderer.infoPanelContentWidth(listing, name).coerceAtMost(availW)

        val panelW = contentW + PANEL_PAD * 2
        val panelH = EntryBadgeRenderer.measureInfoLinesHeight(listing, contentW) + PANEL_PAD * 2
        // 纵向与旁边的大图一样取屏幕中线；⚠ 比屏幕还高时**贴顶**（宁可下缘出去，也别把标题行顶没了）
        val panelY = ((screenHeight - panelH) / 2).coerceAtLeast(2)

        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, panelX, panelY, panelW, panelH, 1, ROW_BACKGROUND_TEX_H)

        // 行内容是**居中排版**的（drawInfoLines 的口径），所以中心传面板自己的中线
        EntryBadgeRenderer.drawInfoLines(
            context = context,
            entry = listing,
            displayName = name,
            centerX = panelX + panelW / 2,
            startY = panelY + PANEL_PAD,
            dividerW = contentW,
            maxWidth = contentW
        )
    }

    /** 按真实帧间隔推进模型时间轴（1 秒 = 20 单位，即 Cobblemon 口径的 partialTicks） */
    private fun advanceAnimation(state: FloatingState) {
        val nowNanos = System.nanoTime()
        val delta = if (lastFrameNanos == 0L) 0f
        else ((nowNanos - lastFrameNanos) / 1_000_000_000f * 20f).coerceAtMost(MAX_SELF_DELTA)
        lastFrameNanos = nowNanos
        state.updatePartialTicks(delta)
    }

    /** 取缓存；顺带丢掉过期的（拍品半小时就结束了，10 分钟前那份数据没必要留着） */
    private fun cached(id: String, now: Long): Entry? {
        val entry = cache[id] ?: return null
        if (now - entry.at > CACHE_TTL_MS) {
            cache.remove(id)
            return null
        }
        return entry
    }

    private fun requestOnce(hovered: Hovered, now: Long) {
        if (hovered.id in requested) return
        // 还在服务端限流窗口里就先不发、**也不算已请求**：下一帧接着试，别让这一拍品永久空白
        if (now - lastRequestAt < REQUEST_MIN_INTERVAL_MS) return
        lastRequestAt = now
        if (!requested.add(hovered.id)) return
        // 集合同样设上限：按插入序淘汰最旧的，避免聊天刷屏时无限增长
        while (requested.size > REQUESTED_MAX) {
            val oldest = requested.firstOrNull() ?: break
            requested.remove(oldest)
        }
        sendToServer(RequestObjectPreviewPayload(hovered.kind, hovered.id))
    }

    private fun trimCache(now: Long) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.at > CACHE_TTL_MS) iterator.remove()
        }
        while (cache.size > CACHE_MAX) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }

    /** 鼠标下的播报拍品 */
    private class Hovered(val id: String, val kind: String)

    /**
     * 读鼠标下那段聊天文字的样式。**只有播报的拍品名挂了 click 事件**，其余聊天文字返回 null。
     *
     * `ChatHud.getTextStyleAt` 收的是**屏幕缩放坐标**（原版 ChatScreen 也是把鼠标坐标原样传给它），
     * 且内部要求聊天栏处于聚焦态 —— 非聊天界面（我们这个入口的调用点覆盖所有界面）
     * 或者聊天收起时它返回 null，这里自然就不显示。
     */
    private fun hoveredAnnouncement(client: MinecraftClient, mouseX: Int, mouseY: Int): Hovered? {
        val style = client.inGameHud.chatHud
            .getTextStyleAt(mouseX.toDouble(), mouseY.toDouble()) ?: return null
        // 播报格式由服务端定：`/cobblemarket auction <uuid>`（拍卖）/ `/cobblemarket review <uuid>`（求购）
        val command = style.clickEvent?.value ?: return null
        if (!command.startsWith(COMMAND_PREFIX)) return null
        val parts = command.substring(COMMAND_PREFIX.length).split(' ')
        // ⚠ **指令词 ≠ 协议 kind**，必须显式映射：求购的指令是 `review`，而协议里的 kind 是
        // `buyorder`（`ObjectPreviewNetwork.KIND_BUY_ORDER`）。写成「指令词直接当 kind 用」的话
        // 拍卖碰巧同名（auction）能跑，**求购这一路会永远静默不出图**（2026-09-17 用户实测踩到）
        val kind = when (parts.getOrNull(0)) {
            "auction" -> ObjectPreviewNetwork.KIND_AUCTION
            "review" -> ObjectPreviewNetwork.KIND_BUY_ORDER
            else -> return null
        }
        val rawId = parts.getOrNull(1) ?: return null
        // 必须能解析成 UUID（播报里就是 UUID），并规范化成标准小写形式：
        // 服务端回包里的 id 用的是同一个形式，不统一就对不上缓存键
        val uuid = runCatching { UUID.fromString(rawId) }.getOrNull() ?: return null
        return Hovered(uuid.toString(), kind)
    }
}
