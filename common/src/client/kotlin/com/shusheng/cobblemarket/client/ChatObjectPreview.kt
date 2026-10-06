package com.shusheng.cobblemarket.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.ItemHoverPreview
import com.shusheng.cobblemarket.client.icon.PokemonHoverPreview
import com.shusheng.cobblemarket.network.ObjectPreviewNetwork
import com.shusheng.cobblemarket.network.ObjectPreviewPayload
import com.shusheng.cobblemarket.network.RequestObjectPreviewPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.item.ItemStack
import net.minecraft.util.Identifier
import java.util.UUID

/**
 * **聊天播报的悬浮大图**（按 T 打开聊天 → 鼠标停在拍卖/求购的全服播报上）。
 *
 * 播报里的拍品名挂了客户端指令（见 `AuctionNetwork.announceAuction` / `BuyOrderNetwork`），
 * 这里就顺着那条 `clickEvent` 认出「鼠标下是哪个拍品」，再去服务端要数据（[RequestObjectPreviewPayload]）
 * 并在**右半屏的中心**画大图 —— 复用列表那套悬停大图的渲染器（居中模式）。
 *
 * 三条与列表预览不同的规矩（2026-09-17 拍板）：
 * - **不受「精灵预览图/物品预览图」开关约束**：聊天里的大图无条件显示（居中模式跳过开关判断）
 * - **防扫过**：同一拍品连续悬停 [HOVER_DWELL_MS] 才算选中 —— 鼠标划过一屏聊天时不该闪一片大图
 * - **鼠标移开立刻收起**（不进淡出、不留残影），缓存保留，回头再悬停秒显
 *
 * 识别方式与具体是哪条播报无关：**只要那段聊天文字挂了 `auctionpeek` / `auction` / `review` 指令就生效**
 * （新增指令时记得在 [hoveredAnnouncement] 里补一条映射）。叫声沿用 [IconCryClick]（随机普通叫/战吼、静态模式与睡觉不叫）：
 * 显示后延迟 [IconCryClick.AUTO_CRY_DELAY_MS] 才响，理由与各弹窗一致 ——
 * 大图刚出现时玩家还没反应过来声音从哪来。
 */
object ChatObjectPreview {

    /** 扫过不算：同一拍品要连续悬停这么久才显示（聊天里滑过一段文字太容易了） */
    private const val HOVER_DWELL_MS = 250L

    /** 缓存上限与存活时长：拍品数据不会变（成交后条目本身就查不到了），但也不能无限攒 */
    private const val CACHE_MAX = 64
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    /** 「已请求过」集合的上限：悬停过的 id 会一直堆着，聊天刷屏时几小时能攒出几千条 */
    private const val REQUESTED_MAX = 256

    /**
     * 两次**真正叫出声**之间至少要隔这么久。
     *
     * ⚠⚠ 为什么是 2.5 秒（2026-10-07 重定，此前 1.2 秒**不够**）：
     * 那一声**不是因为我们的触发就立刻响的** —— 声音是动画里的 `sound_effects` 关键帧，
     * 而关键帧在**起手之后**：实测喷火龙在 `0.6667s / 1.0833s` 处（各物种不同，但都在起手之后），
     * 那一声本身还有 1 秒多 ⇒ 从我们触发算起，**最晚到 ~2.3 秒才响完**。
     * 原先按「触发时刻 + 1.2 秒」算，玩家在 1.2~2.5 秒之间移回来就会被判成"早过去了"⇒ 重新触发
     * ⇒ 第二声在新动画跑到关键帧时响起、**恰好叠在第一声的尾巴上**（2026-10-07 用户实测报的）。
     * ⚠ 而且它是 `SimpleSoundInstance` **自动播**的，我们手里没有句柄、**停不掉** ⇒
     * 唯一的办法就是**别在它还在响的时候再触发**（重叫必然叠音，没有第二条路）。
     */
    private const val CRY_COOLDOWN_MS = 2500L

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

    /** 缓存里的一条拍品：[isPokemon] 分流用哪套渲染器，另一个字段恒为 null */
    private class Entry(
        val isPokemon: Boolean,
        val renderable: RenderablePokemon?,
        val state: FloatingState?,
        val stack: ItemStack?,
        val at: Long
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

    /** 上次**真正触发**叫声的时刻：短时间内不重复叫，见 [CRY_COOLDOWN_MS] */
    private var lastCryAt = 0L

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
                at = now
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
        // ⚠ **把正在播的叫声动画停掉**（2026-10-07）：不清的话那段战吼会**停在半截**，
        //   下次悬停接着播 —— 看着像"继续"而不是重新叫；更糟的是**移开时若还没跑到声音关键帧，
        //   那一声会等你回来时才补响**，与紧接着的第二次触发叠上（用户实测报的叠音正是这个）。
        // ⚠ 只清 `activeAnimations`（= [IconCryClick.play] 内部同款操作，安全）：
        //   姿势 / 待机动画**不在这个列表里**（见 Cobblemon 的 runEffects），别去碰 pose 那套
        //   （`:primary` 会 dump 掉姿势动画 —— 暴鲤龙立起来那次，2026-09-22 踩过）
        cache[shownId]?.state?.activeAnimations?.clear()
        shownId = null
        cryAt = 0L
    }

    /** 开始显示某个拍品：排一次叫声（**只在真正开始显示时排**，否则每帧都会重排、永远叫不出来） */
    private fun beginShow(id: String, entry: Entry, now: Long) {
        shownId = id
        // 重置帧间隔基准：上次显示是几分钟前的话，第一帧会带着一个巨大的 delta
        lastFrameNanos = 0L
        // 物品不叫；精灵安排「显示后 AUTO_CRY_DELAY_MS」再叫（理由见类注释）。
        // ⚠ 刚叫过就别再排（[CRY_COOLDOWN_MS]）—— 那一声还在响时重叫必然叠上，而声音停不掉
        cryAt = if (entry.isPokemon && now - lastCryAt >= CRY_COOLDOWN_MS) {
            now + IconCryClick.AUTO_CRY_DELAY_MS
        } else {
            0L
        }
    }

    private fun draw(context: DrawContext, entry: Entry) {
        // 横向落点 = **右半屏的中心**（= 屏宽的 3/4）：屏幕正中会压住聊天框上方的正文，
        // 右半边通常是空的，视觉上也不跟左下的聊天栏打架（2026-09-17 用户定的位置）
        val centerX = context.scaledWindowWidth * 0.75f
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
                // 记**触发**时刻（不是"响完"时刻）：冷却窗要盖住「起手 + 那一声」的总时长
                lastCryAt = System.currentTimeMillis()
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
        } else {
            val stack = entry.stack ?: return
            ItemHoverPreview.render(
                context = context,
                stack = stack,
                panelLeft = 0,
                screenHeight = screenHeight,
                centerX = centerX
            )
        }
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
        // 播报格式由服务端定：`/cobblemarket auction <uuid>`（拍卖的 [点击出价]）/
        // `/cobblemarket auctionpeek <uuid>`（拍卖的**名字**，点了不做事）/ `/cobblemarket review <uuid>`（求购）
        val command = style.clickEvent?.value ?: return null
        if (!command.startsWith(COMMAND_PREFIX)) return null
        val parts = command.substring(COMMAND_PREFIX.length).split(' ')
        // ⚠ **指令词 ≠ 协议 kind**，必须显式映射：求购的指令是 `review`，而协议里的 kind 是
        // `buyorder`（`ObjectPreviewNetwork.KIND_BUY_ORDER`）。写成「指令词直接当 kind 用」的话
        // 拍卖碰巧同名（auction）能跑，**求购这一路会永远静默不出图**（2026-09-17 用户实测踩到）
        val kind = when (parts.getOrNull(0)) {
            "auction" -> ObjectPreviewNetwork.KIND_AUCTION
            // 拍卖播报的**名字**（2026-10-06 起名字改挂这条，点击无动作）：名字只做悬停、不做跳转
            // ⇒ 与 [点击出价] 那条**同一个 kind**（同一场拍卖，走同一份缓存 / 同一个大图）
            "auctionpeek" -> ObjectPreviewNetwork.KIND_AUCTION
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
