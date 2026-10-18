package com.shusheng.cobblemarket.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.client.icon.IconCryClick
import com.shusheng.cobblemarket.client.icon.IconRegistry
import com.shusheng.cobblemarket.client.icon.ItemHoverPreview
import com.shusheng.cobblemarket.client.icon.PokemonHoverPreview
import com.shusheng.cobblemarket.network.AuctionPreviewInfo
import com.shusheng.cobblemarket.network.ListingEntry
import com.shusheng.cobblemarket.network.ObjectPreviewNetwork
import com.shusheng.cobblemarket.network.ObjectPreviewPayload
import com.shusheng.cobblemarket.network.RequestObjectPreviewPayload
import com.shusheng.cobblemarket.platform.sendToServer
import com.shusheng.cobblemarket.screen.EntryBadgeRenderer
import com.shusheng.cobblemarket.screen.ROW_BACKGROUND_TEX_H
import com.shusheng.cobblemarket.screen.ROW_BACKGROUND_TEXTURE
import com.shusheng.cobblemarket.screen.drawNineSlice
import com.shusheng.cobblemarket.util.RemainingTime
import com.shusheng.cobblemarket.util.TextUtil
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.text.OrderedText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.util.UUID

/**
 * **聊天播报的悬浮大图**（按 T 打开聊天 → 鼠标停在拍卖/求购的全服播报上）。
 *
 * 播报里的拍品名挂了客户端指令（见 `AuctionNetwork.announceAuction` / `BuyOrderNetwork` / `ShareNetwork`），
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
 * 识别方式与具体是哪条播报无关：**只要那段聊天文字挂了 `auctionpeek` / `auction` / `review` / `share`
 * 指令就生效**（新增指令时记得在 [hoveredAnnouncement] 里补一条映射）。叫声沿用 [IconCryClick]（随机普通叫/战吼、静态模式与睡觉不叫）：
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
     * 两次**真正叫出声**之间至少要隔这么久。
     *
     * ⚠⚠ 为什么是 2.5 秒（2026-10-07 重定，此前 1.2 秒**不够**）：
     * 那一声**不是因为我们的触发就立刻响的** —— 声音是动画里的 `sound_effects` 关键帧，
     * 而关键帧在**起手之后**：实测喷火龙在 `0.6667s / 1.0833s` 处（各物种不同，但都在起手之后），
     * 那一声本身还有 1 秒多 ⇒ 从我们触发算起，**最晚到 ~2.3 秒才响完**。
     * 原先按「触发时刻 + 1.2 秒」算，玩家在 1.2~2.5 秒之间移回来就会被判成"早过去了"⇒ 重新触发
     * ⇒ 第二声在新动画跑到关键帧时响起、**恰好叠在第一声的尾巴上**（2026-10-07 用户实测报的）。
     * ⚠ 而且它是 `SimpleSoundInstance` **自动播**的，我们手里没有句柄、**停不掉**
     * （远大于 [IconCryClick] 自己的 200ms 节流也是这个道理）⇒
     * 唯一的办法就是**别在它还在响的时候再触发**（重叫必然叠音，没有第二条路）。
     * ⚠ 移开时**顺手把叫声动画停掉**（见 [hide]）：既不再"回来接着播半截"，也避免"移开时还没跑到
     * 关键帧、等你回来才补响那一声"。
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
        val name: Text? = null,
        /**
         * **拍卖状态**（只有拍卖播报那条有）：当前价 / 出价次数 / 起拍价 / 最低加价 /
         * 结束时间 / 领先者 —— 排成信息行的**尾部**，见 [auctionFooterLines]。
         * ⚠ 内容会变（当前价、领先者），所以悬停期间会**重新请求**刷新（见 [REQUEST_REFRESH_MS]）。
         */
        val auction: AuctionPreviewInfo? = null,
        /**
         * 精灵的**身份键**（物种 + 形态）：刷新回来时靠它认「还是同一只吗」——
         * 是就**复用** [renderable] / [state]，否则每刷新一次模型动画都从头开始（看着像跳了一下）。
         */
        val pokemonKey: String? = null,
        /**
         * 这条是不是**展示**播报。展示**没有交易信息**（没有卖家、没有价格）⇒ 信息行的尾部
         * 整个不画 —— 否则会凭空画出「卖家：xxx / 价格：0 ₽」（2026-09-29 用户要求展示单独处理）
         */
        val isShare: Boolean = false,
        /** ID 卡展示的数据快照（非空 = 这条是 ID 卡展示，画整张卡） */
        val idCardRow: com.shusheng.cobblemarket.overview.PlayerOverviewRow? = null
    )

    /** 大图数据缓存（插入序，超上限淘汰最旧的） */
    private val cache = LinkedHashMap<String, Entry>()

    /**
     * 已请求过的 id → **上次请求的时刻**。
     *
     * ⚠ 语义不是「只请求一次」：**拍卖那条的当前价 / 出价次数 / 领先者会变**，悬停窗既然要显示它们，
     * 就得定期重新问（见 [REQUEST_REFRESH_MS]）。大图数据本身不会变 ⇒ 缓存照旧**秒显**，
     * 刷新只是把新数据换上去（[onData] 里同一只会**复用模型**，动画不会重头开始）。
     */
    private val requested = LinkedHashMap<String, Long>()

    /**
     * 同一个拍品**隔多久允许重新请求一次**。
     *
     * 取 10 秒：别人出价的频率不会比这更高，而玩家悬停看一会儿也能自动刷到最新价。
     * ⚠ 服务端对超限请求是**静默丢弃**，所以这个值必须大于服务端
     *   `RequestThrottle.READ_INTERVAL_MS`（500ms）—— 10 秒远大于它，安全。
     */
    private const val REQUEST_REFRESH_MS = 10_000L

    /** 上次实际发出请求的时刻（客户端让开服务端限流窗口用，见 [REQUEST_MIN_INTERVAL_MS]） */
    private var lastRequestAt = 0L

    /** 本帧鼠标下的拍品 id（null = 没停在播报上），与 [hoverSince] 一起算停留时长 */
    private var hoverId: String? = null
    private var hoverSince = 0L

    /** 正在显示大图的拍品 id（null = 没显示） */
    private var shownId: String? = null

    /** 待播叫声时刻（0 = 无）。每次「开始显示」重排一次，见 [beginShow] */
    private var cryAt = 0L

    /** 上次**真正叫出声**的时刻：短时间内不重复叫，见 [CRY_COOLDOWN_MS] */
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

        // ⚠ 请求**不只在没数据时发**：拍卖那条的当前价 / 出价次数 / 领先者会变，悬停期间要定期刷新
        //   （[requestOnce] 自己按 [REQUEST_REFRESH_MS] 判断该不该真发 ⇒ 每帧调也不会每帧发包）
        requestOnce(hovered, now)
        val entry = cached(hovered.id, now)
        if (entry == null) {
            // 数据还没到：这帧先不显示
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

        // ID 卡展示：没有精灵/物品模型，直接用数据快照。
        // ⚠ 必须**最先判** —— 它的 isPokemon=false 会掉进下面的物品分支，而那个分支会因 itemId 为空直接 return
        val entry = if (payload.idCardRow != null) {
            Entry(
                isPokemon = false, renderable = null, state = null, stack = null,
                at = now, idCardRow = payload.idCardRow
            )
        } else if (payload.isPokemon) {
            val speciesId = Identifier.tryParse(payload.speciesId) ?: return
            val species = PokemonSpecies.getByIdentifier(speciesId) ?: return
            // 形态照各界面统一口径：挂单真实 aspects + 闪光补 "shiny"（缺了这一步闪光精灵画成普通色）
            val aspects = payload.aspects.toMutableSet()
            if (payload.shiny && "shiny" !in aspects) aspects.add("shiny")
            // 携带物喂给模型：Cobblemon 会把它渲染在手上/嘴上，不传的话大图里的精灵是空手的
            val held = payload.heldItemNbt?.let { ItemStack.fromNbtOrEmpty(registryManager, it) } ?: ItemStack.EMPTY
            // 身份键：物种 + 形态。**刷新回来时若还是同一只就复用渲染对象**（见 Entry.pokemonKey）——
            // 否则数据一刷新（拍卖状态每 10 秒重问一次）模型动画就从头开始，看着像跳了一下
            val pokemonKey = payload.speciesId + "|" + aspects.sorted().joinToString(",")
            val reuse = cache[payload.id]?.takeIf { it.isPokemon && it.pokemonKey == pokemonKey }
            Entry(
                isPokemon = true,
                // 大图里的**模型**：玩家设了「隐藏」就不拿东西（别的界面同理，用户 2026-09-26 拍板）
                renderable = reuse?.renderable
                    ?: RenderablePokemon(species, aspects, if (payload.heldItemVisible) held else ItemStack.EMPTY),
                state = reuse?.state ?: FloatingState(),
                stack = null,
                at = now,
                // 右侧信息行（服务端组装好随包下发；没有就只画大图）
                listing = payload.listing,
                // 名字行走**官方词条 key**（`cobblemon.species.<name>.name`）—— 与市场同一口径
                name = if (payload.listing == null) null else EntryBadgeRenderer.nameWithShinyStar(
                    Text.translatable(speciesNameKey(payload.speciesId)), payload.shiny
                ),
                auction = payload.auction,
                pokemonKey = pokemonKey,
                isShare = payload.isShare,
                idCardRow = payload.idCardRow
            )
        } else {
            // 拍卖存整栈 NBT、求购单存组件快照 —— 两种格式各走各的重建入口（都以 itemId 兜底）
            val stack = if (payload.itemNbt != null) iconStackOf(payload.itemId, payload.itemNbt)
            else ItemComponentsDisplay.iconStack(payload.itemId, payload.itemComponentsSpec)
            if (stack == null || stack.isEmpty) return
            // 数量以包里单独传的为准：挂单 NBT 里存的是上架总量（同庆祝动画的处理）
            stack.count = payload.count.coerceAtLeast(1)
            // ⚠ 物品拍卖**同样**带拍卖状态（拍卖场的物品悬停窗也是「词条 + 当前价 / 倒计时 / 领先者」）
            Entry(
                isPokemon = false, renderable = null, state = null, stack = stack, at = now,
                auction = payload.auction, isShare = payload.isShare, idCardRow = payload.idCardRow
            )
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
        // ⚠ 只清这两个 map —— 姿势 / 待机动画**不在**它们里（见 Cobblemon 的 runEffects），
        //   别去碰 pose 那套（`:primary` 会 dump 掉姿势动画 —— 暴鲤龙立起来那次，2026-09-22 踩过）：
        // - `activeAnimations`：叫声 / 战吼（[IconCryClick.play] 内部也清这个）
        // - `quirks`：官方 quirk（眨眼 / idle 小动作）住在**另一个 map** 里
        //   （`allActiveAnimations = activeAnimations + quirks`）—— 漏了它，小动作会停半截、回来接着播
        //   （2026-10-07 开 quirk 时一并加上）
        cache[shownId]?.state?.let { state ->
            state.activeAnimations.clear()
            state.quirks.clear()
        }
        shownId = null
        cryAt = 0L
    }

    /** 开始显示某个拍品：排一次叫声（**只在真正开始显示时排**，否则每帧都会重排、永远叫不出来） */
    private fun beginShow(id: String, entry: Entry, now: Long) {
        shownId = id
        // 重置帧间隔基准：上次显示是几分钟前的话，第一帧会带着一个巨大的 delta
        lastFrameNanos = 0L
        // 每次开始显示都当作「第一次出现」：清掉这个 state 上一轮留下的姿势与未播完的主动画。
        // ⚠ 用户 2026-10-18 报：白天悬停过、时间切到夜晚后再悬停，会先补一段「站起 → 躺下」。
        //   真因：`Entry.state` 是**跨悬停缓存**的（见 [cache] / [Entry.state]），而 [hide] 只清了
        //   `activeAnimations` / `quirks`、没清 `currentPose`；于是 `resolve()` 拿旧姿势当过渡起点、
        //   [IconAnimationResolver] 的 `ensurePoseTransition` 就补了一段过渡。
        //   `currentPose = null` ⇒ 找不到旧姿势、不播过渡，`drawProfilePokemon` 直接把姿势设成
        //   当前该有的（与 1.2.1「进界面直接就是睡姿」同一原则）。
        // ⚠ **悬停期间**真的跨昼夜时 `beginShow` 不会再被调用 ⇒ 仍走平滑过渡（有意保留，
        //   与市场界面的行内图标一致）。
        entry.state?.let { state ->
            state.currentPose = null
            state.primaryAnimation = null
            state.poseIntensity = 1F
        }
        // 物品不叫；精灵安排「显示后 AUTO_CRY_DELAY_MS」再叫（理由见类注释）。
        // ⚠ 刚叫过就别再排（[CRY_COOLDOWN_MS]）—— 移开、马上移回会让声音叠上，而声音停不掉
        cryAt = if (entry.isPokemon && now - lastCryAt >= CRY_COOLDOWN_MS) {
            now + IconCryClick.AUTO_CRY_DELAY_MS
        } else {
            0L
        }
    }

    /** 物种 ID（`cobblemon:pikachu`）→ 官方名字词条 key（`cobblemon.species.pikachu.name`） */
    private fun speciesNameKey(speciesId: String): String =
        "cobblemon.species.${speciesId.substringAfter(':').lowercase()}.name"

    /**
     * 解析 ID 卡展示要画的**玩家实体**：
     * ① 世界里有（在线且在附近）→ 直接用；
     * ② 在线但不在附近 → 用 tab 列表的 profile 建一个 `OtherClientPlayerEntity`（皮肤照样加载）；
     * ③ 离线 / 都拿不到 → null ⇒ 卡片模型区留空（不崩）。
     */
    private fun resolveIdCardPlayer(uuid: UUID): net.minecraft.entity.player.PlayerEntity? {
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return null
        world.getPlayerByUuid(uuid)?.let { return it }
        val entry = client.networkHandler?.getPlayerListEntry(uuid) ?: return null
        return runCatching {
            net.minecraft.client.network.OtherClientPlayerEntity(world, entry.profile)
        }.getOrNull()
    }

    private fun draw(context: DrawContext, entry: Entry) {
        // 横向落点 = **屏幕正中**（2026-09-29 用户拍板，原先在右半屏的中心 = 屏宽的 3/4）：
        // 大图自己是严格居中的那一个，词条面板再从它**右缘**往后排 ——
        // ⚠ 不是"大图 + 面板两块合起来居中"（那样大图会偏左），用户 2026-09-29 专门澄清过
        val centerX = context.scaledWindowWidth * 0.5f
        val screenHeight = context.scaledWindowHeight
        // ID 卡展示：画**整张卡**（背景 + 两列信息）；不画 3D 玩家模型 —— 展示者的实体本地不一定有
        val idCard = entry.idCardRow
        if (idCard != null) {
            // ID 卡**偏右**放（2026-10-18 用户要求）：聊天框占着左侧，居中的卡会压到聊天文字上。
            // ⚠ 不能按屏幕 3/4 写死（用户实测「太靠右了，聊天框的宽度没有这么宽」）——
            //   按**聊天框实际右缘**算，在「聊天框右侧的剩余区域」里居中：
            //   - ChatHud.render 的矩阵 = scale(chatScale) → translate(4,0)，背景画到 k+8（k = ceil(width/chatScale)）
            //     ⇒ on-screen 右缘 = width + 12 × chatScale（GUI 单位，与 scaledWindowWidth 同一坐标系）
            //   - 再留 8px 间隙；剩余区域比卡片还窄时贴右缘留 8px（小分辨率下无解，只能别画出屏幕）
            val chatHud = MinecraftClient.getInstance().inGameHud.chatHud
            val chatRight = chatHud.width + (12.0 * chatHud.chatScale).toInt()
            val areaLeft = (chatRight + 8).toFloat()
            val areaRight = context.scaledWindowWidth.toFloat()
            val desiredLeft = areaLeft + ((areaRight - areaLeft - IdCardRenderer.CARD_W) / 2f)
            val maxLeft = (areaRight - IdCardRenderer.CARD_W - 8f).coerceAtLeast(8f)
            val cardLeft = desiredLeft.coerceIn(8f, maxLeft)
            val cardTop = (screenHeight - IdCardRenderer.CARD_H) / 2f
            IdCardRenderer.draw(
                context, MinecraftClient.getInstance().textRenderer, idCard, cardLeft, cardTop,
                resolveIdCardPlayer(idCard.uuid)
            )
            return
        }
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
            // 右侧那块完整信息行（与精灵市场行悬停窗同源）；没有数据就只留大图
            clearDepthBeforePanel(context)
            val listing = entry.listing
            val name = entry.name
            if (listing != null && name != null) {
                val auction = entry.auction
                val footer = when {
                    // 展示：**没有交易信息** ⇒ 尾部整个不要（否则会画出「卖家：xxx / 价格：0 ₽」）
                    entry.isShare -> emptyList()
                    // 拍卖：换成拍卖状态那几行（照拍卖场的行悬停窗 —— 市场那套的「价格」对拍卖没意义）
                    // ⚠ `withDivider = false`：**证章块自己已经画了下分割线**（`drawInfoLines` 的证章块 =
                    //   图标行 + 下分割线），footer 再加一条就是**重复**（2026-09-29 用户截图报出）。
                    //   ⚠ 别照搬拍卖场那边的做法 —— 那边要这条线，是因为它的证章块**不画**下分割线、
                    //   由这条**充当**它（两种实现不一样，不能直接搬）
                    auction != null -> auctionFooterLines(auction, withDivider = false)
                    // 市场式（待交付）：用默认的「卖家 / 价格」两行
                    else -> null
                }
                drawPokemonInfoPanel(context, listing, name, screenHeight, footer)
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
            // 物品拍卖同样要接拍卖那几行：拍卖场的物品悬停窗也是「词条 + 当前价 / 倒计时 / 领先者」。
            // ⚠ 物品侧这条线**恒要**（分隔「词条」与「拍卖信息」两块，没有证章那回事）
            clearDepthBeforePanel(context)
            drawItemTooltipPanel(
                context, stack, screenHeight,
                entry.auction?.let { auctionFooterLines(it, withDivider = true) }
            )
        }
    }

    // ── 词条面板的尺寸 ──
    /** 词条面板的内边距 */
    private const val PANEL_PAD = 4
    /** 一行词条的高度（默认字体 9 + 1） */
    private const val LINE_H = 10

    /**
     * 画**面板之前**再 flush 一次 + 清深度缓冲 —— 大图刚往深度缓冲里写过值。
     *
     * ⚠ 大图自己的绘制开头清过一次（防它被先画的 GUI 剔除，见 `ItemHoverPreview` / `PokemonHoverPreview`），
     *   但**清完它自己也会写深度** ⇒ 随后画的面板，凡是落在**大图轮廓之内**的部分会被深度测试
     *   整个剔掉 —— 看起来就像"面板钻到大图下面/被咬掉一块"。
     *   2026-10-13 用户截图报的正是这个：物品词条面板左边缺一块，而且**两行文字的切口不一样**
     *   （跟着物品模型的形状走）—— 切口跟着模型形状就是残留深度的指纹，
     *   与 2026-10-03「尾巴靠后的精灵被切一块、切口是直线」是同一个病。
     * ⚠ 清在这里是安全的：面板是这一帧最后画的东西，之后再画的 2D 元素不受空深度缓冲影响。
     */
    private fun clearDepthBeforePanel(context: DrawContext) {
        context.draw()
        com.mojang.blaze3d.systems.RenderSystem.clear(
            org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT,
            MinecraftClient.IS_SYSTEM_MAC
        )
    }

    /**
     * 词条面板的左缘：**中心落在右半屏的中心**（= 屏宽的 3/4），并钳在屏幕内。
     *
     * ⚠ 为什么不贴着大图右缘摆（2026-09-29 用户实测报出）：大图**自己**在屏幕正中没错，
     *   但精灵模型不是以原点对称的、多数还带尾巴 / 翅膀 ⇒ 向右伸得比想象的远，
     *   贴着摆会被模型盖住半个面板。挪到右半屏中心后两者自然拉开距离，大图仍待在正中。
     * ⚠ 精灵与物品**共用这一个落点**：悬停两种东西时面板不该跳来跳去。
     */
    private fun panelXFor(context: DrawContext, panelW: Int): Int {
        val screenW = context.scaledWindowWidth
        val center = (screenW * 0.75f).toInt()
        return (center - panelW / 2).coerceIn(0, maxOf(0, screenW - panelW - 4))
    }

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
    private fun drawItemTooltipPanel(
        context: DrawContext, stack: ItemStack, screenHeight: Int,
        /** 尾部行（拍卖那条传 [auctionFooterLines]，见 `draw()`）：null = 只有物品词条 */
        footer: List<Pair<Text?, Int>>? = null
    ) {
        val client = MinecraftClient.getInstance()
        val tooltip = ItemComponentsDisplay.itemTooltip(stack, client.player, TooltipType.BASIC)
        if (tooltip.isEmpty()) return
        val font = client.textRenderer

        // 词条可比屏幕宽（长名字 / 长 lore）⇒ 内容宽要有个上限，上限 = **大图右缘到屏幕右缘之间放得下的**：
        //   面板中心固定在 0.75 屏宽（见 [panelXFor]）⇒ 面板左缘 = `0.75屏 − (内容宽 + 内边距×2)/2`；
        //   而大图是**居中**摆的、边长 [ItemHoverPreview.PREVIEW_SIZE] ⇒ 右缘在 `半屏 + 大图半宽`。
        //   ⇒ 内容宽 ≤ `半屏 − 2 × (大图半宽 + 左侧留的间隙)`，面板左缘才能落在大图右缘之外。
        // ⚠ 原上限是"半个屏幕"⇒ 面板左缘正好落在大图**正中心**上，大图右半边压在词条底下
        //   （2026-10-13 用户截图报出）。
        // ⚠ **短词条完全不受影响**：上限没被触发时面板宽度仍是实测值、位置与以前一模一样。
        val previewHalf = ItemHoverPreview.PREVIEW_SIZE / 2
        val leftGap = 8 + PANEL_PAD
        val maxContentW = (context.scaledWindowWidth * 0.5f).toInt() - 2 * (previewHalf + leftGap)
        val availH = screenHeight - 16

        val maxRows = ((availH - PANEL_PAD * 2) / LINE_H).coerceAtLeast(1)
        val footerRows = footer.orEmpty()
        // ⚠ 尾部（拍卖状态）**优先占位**：当前价 / 倒计时有时效性，词条少看两行不影响判断；
        //   反过来把尾部截掉，玩家就看不到还剩多久、现在多少钱了
        val tooltipRows = (maxRows - footerRows.size).coerceAtLeast(1)

        // 内容宽：按**未换行的实测宽**定（短词条据此保持窄面板，与以前一致），超过上限才用上限
        val rawTextW = tooltip.maxOfOrNull { font.getWidth(it) } ?: 0
        val contentW = minOf(rawTextW, maxContentW).coerceAtLeast(48)

        // ⚠ **先换行、再按行数截断**（顺序不能反）：换行走原版 `TextRenderer.wrapLines` —— **样式跟着走**
        //   （附魔行那种分段颜色不会丢）。原先直接对整行截断 ⇒ 长 lore 只剩半句 + 一个「…」
        //   （2026-10-13 用户报「右侧文字显示不完整」）。
        val wrappedTooltip = tooltip.flatMap { line -> font.wrapLines(line, contentW) }
        // 统一成行模型：null 元素 = 分割线（与项目其余行列表同一约定）；
        // 尾部那几行是**我们自己拼的短行**（价格 / 倒计时），不参与换行、原样转成 OrderedText（保样式）
        val tooltipPart: List<Pair<OrderedText?, Int>> = wrappedTooltip.take(tooltipRows).map { it to 0xFFFFFF }
        // ⚠ 尾部那几行是**我们自己拼的短行**、不参与换行 ⇒ 超宽时在 **Text 上**截断（[TextUtil.truncateStyled]
        //   保分段样式）再转 OrderedText。⚠ **不能等画的时候再截**：`OrderedText` 这边没有可用的
        //   `trimToWidth` 重载（只有 String / StringVisitable 的），改到那时只能丢样式
        val lines: List<Pair<OrderedText?, Int>> = tooltipPart + footerRows.map { (text, color) ->
            text?.let { TextUtil.truncateStyled(it, contentW).asOrderedText() } to color
        }
        // ⚠ priceRows 记的是 **footer 内**的行号，而实际渲染的是「tooltip 词条 + footer」拼起来的整表 ⇒
        //   判定时要减去 footer 的起始行号（`tooltipPart.size`）。
        //   ⚠ **别在这里直接把 priceRows 偏移掉**：精灵侧的 footer 只在 beginShow 时构建一次，
        //     而本方法是**每帧**调的 —— 每帧再偏移一次会一路越界，单位就再也画不出来（2026-09-29 踩过）
        val footerOffset = tooltipPart.size
        val shown = lines.take(maxRows)

        val panelW = contentW + PANEL_PAD * 2
        val panelX = panelXFor(context, panelW)
        val panelH = shown.size * LINE_H + PANEL_PAD * 2
        val panelY = (screenHeight / 2 - panelH / 2)

        // HUD 层浮窗用行背景那套九宫格（与项目里自绘悬停窗同一个贴图）
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, panelX, panelY, panelW, panelH, 1, ROW_BACKGROUND_TEX_H)

        shown.forEachIndexed { i, (line, color) ->
            val rowY = panelY + PANEL_PAD + i * LINE_H
            if (line == null) {
                // 分割线：撑满面板内容宽（与精灵面板 / 市场悬停窗同一画法）
                context.fill(panelX + PANEL_PAD, rowY + 4, panelX + PANEL_PAD + contentW, rowY + 5, 0xFF888888.toInt())
            } else {
                // 词条那部分**已经换好行**、尾部那几行也**已经截好**了（见上面 `lines` 的构造）——
                // 这里直接画。⚠ 走 OrderedText 的重载：`drawTextWithShadow` 只收 Text
                // ⚠ 金额行（当前价 / 起拍价 / 最低加价）的货币单位补在行尾 —— 单位不在文本里，
                //   见 auctionFooterLines；物品货币时它就是那件物品的图标。
                //   ⚠ 它返回的是**文本结束坐标**（不是宽度），直接拿它当单位起点
                val endX = context.drawText(font, line, panelX + PANEL_PAD, rowY, color, true)
                // ⚠ 这里**恒按主货币**画：拍卖播报的 payload（`AuctionPreviewInfo`）没有币种字段，
                //   客户端根本判断不出这场拍卖是不是 RP（要补得动 payload = 大版本范畴）。
                //   现状就是恒 ₽，本次改动不改这个行为
                if (i - footerOffset in priceRows) drawCurrencyUnit(context, font, false, endX, rowY, 0xFFAA00)
            }
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
     * **拍卖那条的尾部信息行** —— 取代信息行默认的「卖家 / 价格」两行。
     *
     * 口径照搬**拍卖场的行悬停窗**（`AuctionScreen.renderPokemonTooltip` 的动态行那一段）：
     * 分割线 / 卖家 / 当前价（有人出价才补 `×次数`）/ 结束倒计时（不足 5 分钟转红）/ 领先者 /
     * 「我发布的」。**另加「起拍价」「最低加价」两行** —— 那两项在拍卖场是放在**出价弹窗**里的，
     * 而聊天里点进来之前看不到弹窗，缺了它们玩家没法判断该出多少。
     *
     * ⚠ 与市场那套的「价格」行**不是一回事**：拍卖有起拍价 / 当前价 / 最低加价**三个**金额概念，
     * 塞进一行「价格」只会误导（2026-09-29 用户指出）。
     * ⚠ 用的是 `_label` 变体的词条（**自带冒号**）—— 不带冒号那版是给出价弹窗的表单用的
     * （那边由代码补 `": "`，属于欠账，别再学）。
     * ⚠ 全是**会变**的数据 ⇒ 悬停期间会定期重新请求（见 [REQUEST_REFRESH_MS]）。
     */
    /**
     * 本帧聊天悬停窗里**金额行**的下标 —— 拍卖播报有三行（当前价 / 起拍价 / 最低加价），
     * 所以是个集合，不是单个下标。构建行时收集，渲染时按它补货币单位（物品货币时是物品图标）。
     */
    private var priceRows: MutableSet<Int> = mutableSetOf()

    private fun auctionFooterLines(
        info: AuctionPreviewInfo,
        /**
         * 是否在顶部补一条分割线。
         * **精灵**：跟**证章块**走 —— 那条线本就是证章块的下分割线（拍卖场同口径），没证章时
         * 凭空多一条会被一眼看出来（2026-09-29 用户报）。
         * **物品**：恒 `true` —— 它分隔的是「物品词条」与「拍卖信息」两块，不分隔会糊在一起。
         */
        withDivider: Boolean
    ): List<Pair<Text?, Int>> {
        val lines = mutableListOf<Pair<Text?, Int>>()
        priceRows = mutableSetOf()   // 每次重建都清空（一帧只显示精灵或物品其中之一）
        // ⚠ 货币单位**不拼进文本**，由渲染循环补在行尾（物品货币时是那件物品的图标）——
        //   金额行有三处，所以下面**每处 add 前**都要把下标记进 priceRows
        fun gold(v: Int): Text =
            Text.literal("${formatPrice(v)} ").formatted(Formatting.GOLD)

        if (withDivider) lines.add(null to 0xFFFFFF)
        lines.add(
            Text.translatable("cobblemarket.auction.seller").append(Text.literal(info.sellerName)) to 0xFFFFFF
        )
        // 当前价：还没人出价时显示「起拍 ₽xxx」（拍卖场那套口径），有人出价才补出价次数
        val current = Text.translatable("cobblemarket.auction.current_price").append(
            if (info.currentPrice > 0) gold(info.currentPrice)
            else Text.translatable("cobblemarket.auction.from").append(gold(info.startingPrice))
        )
        priceRows += lines.size
        lines.add(
            (if (info.bidCount > 0)
                current.append(Text.literal("  ×${info.bidCount}").formatted(Formatting.GRAY))
            else current) to 0xFFFFFF
        )
        priceRows += lines.size
        lines.add(Text.translatable("cobblemarket.auction.starting_price_label").append(gold(info.startingPrice)) to 0xFFFFFF)
        priceRows += lines.size
        lines.add(Text.translatable("cobblemarket.auction.min_increment_label").append(gold(info.minIncrement)) to 0xFFFFFF)
        // 结束倒计时：颜色与拍卖场同源（RemainingTime.color，不足 5 分钟转红告警）
        lines.add(
            Text.translatable("cobblemarket.auction.ends").append(
                RemainingTime.text(info.endsAt, "cobblemarket.auction.ending")
            ) to RemainingTime.color(info.endsAt)
        )
        if (info.currentBidderName.isNotEmpty()) {
            lines.add(
                Text.translatable("cobblemarket.auction.leader").append(Text.literal(info.currentBidderName)) to 0xFFDD66
            )
        }
        if (MinecraftClient.getInstance().player?.uuid == info.sellerUuid) {
            lines.add(Text.translatable("cobblemarket.auction.mine_mark") to 0x55FF55)
        }
        return lines
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
        context: DrawContext, listing: ListingEntry, name: Text, screenHeight: Int,
        /** 尾部行：拍卖那条传 [auctionFooterLines]；其余传 null = 用默认的「卖家 / 价格」 */
        footer: List<Pair<Text?, Int>>? = null
    ) {
        // 内容宽：信息行 / 技能块 / 名字行 / 携带物图标 / 技能块那条固定兜底 / 尾部自带那几行 ——
        // 口径全在 EntryBadgeRenderer.infoPanelContentWidth 里（与 drawInfoLines 同源，别在这儿另算）
        val contentW = EntryBadgeRenderer.infoPanelContentWidth(listing, name, footer)

        val panelW = contentW + PANEL_PAD * 2
        val panelH = EntryBadgeRenderer.measureInfoLinesHeight(listing, contentW, footer) + PANEL_PAD * 2
        val panelX = panelXFor(context, panelW)
        // 纵向与旁边的大图一样取屏幕中线；⚠ 比屏幕还高时**贴顶**（宁可下缘出去，也别把标题行顶没了）
        val panelY = ((screenHeight - panelH) / 2).coerceAtLeast(2)

        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, panelX, panelY, panelW, panelH, 1, ROW_BACKGROUND_TEX_H)

        // ⚠ `alignLeft = true`：**照搬精灵市场行悬停窗的排布** —— 文字行贴左缘、分割线与技能块撑满
        //   内容宽，而**证章行仍然居中**（那族本来就是**混合**的，别一概说成"全部左对齐"，
        //   2026-09-29 用户特意提醒了证章这一条）。与弹窗那种"每行都居中"的排版不是一回事。
        EntryBadgeRenderer.drawInfoLines(
            context = context,
            entry = listing,
            displayName = name,
            centerX = panelX + panelW / 2,
            startY = panelY + PANEL_PAD,
            dividerW = contentW,
            maxWidth = contentW,
            footer = footer,
            alignLeft = true
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

    /**
     * 请求这个拍品的数据（悬停期间**每帧都会调**，由它自己决定该不该真发）。
     *
     * 两道闸：① 距上次请求不足 [REQUEST_REFRESH_MS] → 不发（也就不用担心每帧发一包）；
     * ② 距上次**实际发包**不足 [REQUEST_MIN_INTERVAL_MS] → 这帧先让开、**也不算已请求**
     *（下一帧接着试，否则这一拍品会永久空白）。
     */
    private fun requestOnce(hovered: Hovered, now: Long) {
        val lastAsked = requested[hovered.id]
        if (lastAsked != null && now - lastAsked < REQUEST_REFRESH_MS) return
        // 还在服务端限流窗口里就先不发、**也不算已请求**：下一帧接着试，别让这一拍品永久空白
        if (now - lastRequestAt < REQUEST_MIN_INTERVAL_MS) return
        lastRequestAt = now
        requested[hovered.id] = now
        // 同样设上限：按插入序淘汰最旧的，避免聊天刷屏时无限增长
        while (requested.size > REQUESTED_MAX) {
            val oldest = requested.keys.firstOrNull() ?: break
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
            // 分享播报：名字上挂的是 `/cobblemarket share <id>`（点击无动作，见 ShareNetwork.shareMessage）
            "share" -> ObjectPreviewNetwork.KIND_SHARE
            else -> return null
        }
        val rawId = parts.getOrNull(1) ?: return null
        // 必须能解析成 UUID（播报里就是 UUID），并规范化成标准小写形式：
        // 服务端回包里的 id 用的是同一个形式，不统一就对不上缓存键
        val uuid = runCatching { UUID.fromString(rawId) }.getOrNull() ?: return null
        return Hovered(uuid.toString(), kind)
    }
}
