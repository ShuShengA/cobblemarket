package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.entity.PoseType
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.client.playFailSound
import com.shusheng.cobblemarket.network.*
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 选择要上架的精灵界面；求购单交付模式（deliverOrderId 非 null）复用本界面：
 * 选择精灵后返回求购单界面继续交付流程（价格在交付弹窗输入）。
 */
class SellSelectScreen(private val deliverOrderId: java.util.UUID? = null) : Screen(Text.translatable(
    if (deliverOrderId != null) "cobblemarket.sell.deliver_title" else "cobblemarket.sell.title"
)) {

    private val deliverMode = deliverOrderId != null

    private var pokemonList = listOf<PokemonPreview>()
    private var selectedIndex = -1
    private var priceField: TextFieldWidget? = null
    private var searchField: TextFieldWidget? = null
    private var loaded = false
    // 分页拉取状态：loadedAll 为 true 表示服务端已返回全部 PC 精灵
    private var loadedAll = false
    private var closed = false
    // 防串扰：秒关再开界面时，旧界面的迟到响应会被 requestId 比对丢弃
    private val requestId = nextRequestId++
    private val rowHeight = 24
    private var scrollOffset = 0
    private var shinyOnly = false
    // 性别筛选（照精灵市场：""=不限 → MALE → FEMALE 循环）
    private var genderFilter = ""
    private var typeFilter = ""

    // [PC] 图标：Cobblemon 电脑物品栈（lazy 缓存，避免每帧查注册表）
    private val pcItemStack: ItemStack by lazy { ItemStack(Registries.ITEM.get(Identifier.of("cobblemon", "pc"))) }
    private var typeIdx = 0
    private val ivExact = IntArray(6) { -1 }
    // IV 比较方式：0 = 等于（默认），1 = 大于等于，2 = 小于等于
    private val ivOps = IntArray(6)
    private val ivOpButtons = mutableMapOf<Int, NineSliceButton>()
    // 特训筛选三态：0 = 不限，1 = 仅含训练，2 = 仅不含训练
    private var htFilter = 0
    private var hpF: TextFieldWidget? = null; private var atkF: TextFieldWidget? = null; private var defF: TextFieldWidget? = null
    private var spaF: TextFieldWidget? = null; private var spdF: TextFieldWidget? = null; private var speF: TextFieldWidget? = null

    // 属性展开列表（照精灵市场：不限+19 属性、属性色、限高滚动、展开隐藏下层）
    private var typeListOpen = false
    private var typeListScroll = 0
    private val typeOptionButtons = mutableListOf<NineSliceButton>()
    private var shinyBtn: NineSliceButton? = null
    private var genderBtn: NineSliceButton? = null
    private var typeBtn: NineSliceButton? = null
    private var sellBtn: NineSliceButton? = null
    private var htBtn: NineSliceButton? = null

    // 3D icon cache
    private data class IconData(
        val renderable: RenderablePokemon,
        val state: FloatingState,
        // 球种/携带物栈随图标缓存：render 每帧 new ItemStack 是分配热点，构建一次复用
        val ballStack: ItemStack?,
        val heldStack: ItemStack?
    )
    private val iconData = mutableMapOf<Int, IconData>()

    // 物种显示名缓存：species 字段是翻译 key，客户端本地翻译（随客户端语言），搜索与渲染共用。
    // 翻译缺失（数据包物种未配语言文件）时返回 key 原文，fallback 到资源名；缓存随界面实例销毁，
    // 语言切换重开界面即重建。
    private val speciesNameCache = mutableMapOf<String, String>()
    private fun speciesDisplay(p: PokemonPreview): String =
        speciesNameCache.getOrPut(p.species) {
            val t = Text.translatable(p.species).string
            if (t == p.species) p.speciesName else t
        }

    // startIdx > 0 时只构建新增条目的图标，避免分页追加时全量重建
    private fun buildIconCache(startIdx: Int = 0) {
        if (startIdx == 0) iconData.clear()
        for (i in startIdx until pokemonList.size) {
            val p = pokemonList[i]
            val id = Identifier.tryParse(p.speciesId) ?: continue
            val species = PokemonSpecies.getByIdentifier(id) ?: continue
            // 用精灵真实 aspects（性别形态/地区形态等），不再只加 shiny——
            // 否则雌性爱管侍这类性别差异物种会渲染成默认雄性模型
            val aspects = p.aspects.toMutableSet()
            if (p.shiny && "shiny" !in aspects) aspects.add("shiny")
            iconData[i] = IconData(
                RenderablePokemon(species, aspects, ItemStack.EMPTY),
                FloatingState(),
                ballStack = buildBallStack(p.ball),
                heldStack = buildHeldStack(p.heldItemId)
            )
        }
    }

    /** 球种栈（解析失败为 null；渲染逻辑与原来一致：ball 非空就占位 12px，无论解析成败） */
    private fun buildBallStack(ball: String): ItemStack? {
        val ballId = Identifier.tryParse(ball.removePrefix("item.").replaceFirst(".", ":")) ?: return null
        return ItemStack(Registries.ITEM.get(ballId))
    }

    /** 携带物栈（空气视为无）；缓存供行渲染每帧复用 */
    private fun buildHeldStack(heldItemId: String): ItemStack? {
        if (heldItemId.isEmpty()) return null
        val heldId = Identifier.tryParse(heldItemId) ?: return null
        val heldItem = Registries.ITEM.get(heldId)
        return if (heldItem != Registries.ITEM.get(Identifier.of("minecraft", "air"))) ItemStack(heldItem) else null
    }

    override fun init() {
        super.init()
        val lx = width / 2 - 148
        val panelW = 296

        // Row 1: Search (full width)
        // 重建（弹窗关闭等 clearChildren+init）会清空搜索词与 IV 输入：保存恢复
        val savedSearch = searchField?.text ?: ""
        val savedIv = arrayOf(
            hpF?.text ?: "", atkF?.text ?: "", defF?.text ?: "",
            spaF?.text ?: "", spdF?.text ?: "", speF?.text ?: ""
        )
        searchField = TextFieldWidget(textRenderer, lx + 2, 30, panelW - 4, 16, Text.translatable("cobblemarket.sell.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.sell.search"))
        // 搜索/筛选变化时重建过滤缓存（render 每帧只读，见 rebuildFiltered 注释）
        searchField?.setChangedListener { rebuildFiltered() }
        addSelectableChild(searchField)
        addDrawableChild(searchField)
        searchField?.text = savedSearch

        // Row 2 (y=50): IV fields（输入框 32 宽 + 右侧 14 宽三态比较按钮）
        fun mkIv(x: Int, ph: String): TextFieldWidget {
            val f = TextFieldWidget(textRenderer, x, 50, 28, 16, Text.literal(""))
            f.setPlaceholder(Text.literal(ph))
            f.setTextPredicate { it.length <= 2 && it.all { c -> c.isDigit() } }
            addSelectableChild(f); addDrawableChild(f)
            return f
        }
        hpF = mkIv(lx + 2, "HP"); atkF = mkIv(lx + 49, "ATK"); defF = mkIv(lx + 96, "DEF")
        spaF = mkIv(lx + 143, "SpA"); spdF = mkIv(lx + 190, "SpD"); speF = mkIv(lx + 237, "Spd")
        val ivFields = arrayOf(hpF, atkF, defF, spaF, spdF, speF)
        savedIv.forEachIndexed { i, t -> ivFields[i]?.text = t }

        // 三态比较按钮（= / ≥ / ≤ 循环，默认 = 与旧行为一致；非默认金色高亮）
        ivOpButtons.clear()
        for (i in 0..5) {
            // 框 28 宽 + 1px 间隙贴自己框；按钮 14 宽后到下一框（间隔 47）留 4px——视觉上与自己的框成组
            val bx = lx + 2 + i * 47 + 29
            val btn = NineSliceButton(
                bx, 50, 14, 16,
                Text.literal(if (ivOps[i] == 1) "≥" else if (ivOps[i] == 2) "≤" else "="),
                {
                    ivOps[i] = (ivOps[i] + 1) % 3
                    syncIvOpButtons()
                    rebuildFiltered()
                },
                textColor = if (ivOps[i] != 0) GOLD_COLOR else 0xFFFFFF
            )
            ivOpButtons[i] = btn
            addDrawableChild(btn)
        }

        // 返回按钮：右上角（与精灵市场统一；交付模式返回求购单界面）
        addDrawableChild(NineSliceButton(
            lx + panelW - 50, 13, 50, 16,
            Text.literal(""),
            { client?.setScreen(if (deliverMode) BuyOrderScreen() else MarketScreen()) },
            iconLeft = Identifier.of("cobblemarket", "textures/gui/back.png"),
            iconTexW = 48, iconTexH = 48, iconScale = 0.25f,
            tooltip = Text.translatable("cobblemarket.gui.back")
        ))

        // Row 3 (y=72)：间距统一 4px，整行排满到面板右缘（lx+294），两种模式各自铺满不留空
        //   交付：闪光30 | 公母24 | 属性58 | 特训80 | 选择84
        //   上架：闪光30 | 公母24 | 属性58 | 价格56 | 上架44 | 特训60
        val btnY = 72
        shinyBtn = NineSliceButton(
            lx + 2, btnY, 30, 20,
            Text.literal(if (shinyOnly) "★" else "☆"),
            { shinyOnly = !shinyOnly; rebuild() },
            // 开 = 金色 ★，关 = 白色 ☆（与其他界面闪光按钮一致）
            if (shinyOnly) GOLD_COLOR else 0xFFFFFF
        )
        addDrawableChild(shinyBtn)

        // 公母按钮：照精灵市场（不限态 ♂♀ 双图标，循环 不限→仅公→仅母）
        genderBtn = NineSliceButton(
            lx + 36, btnY, 24, 20,
            Text.literal(""),
            { cycleGender() },
            iconTexW = 6, iconTexH = 8, iconScale = 1.5f
        )
        updateGenderButton()
        addDrawableChild(genderBtn)

        // 属性按钮：展开选择（照精灵市场：属性色文字 + 列表）
        typeBtn = NineSliceButton(
            lx + 64, btnY, 58, 20,
            if (typeFilter.isEmpty()) Text.translatable("cobblemarket.sell.type") else Text.translatable(typeFilter),
            { toggleTypeList() },
            if (typeFilter.isEmpty()) 0xFFFFFF else typeColor(typeFilter)
        )
        addDrawableChild(typeBtn)

        priceField = TextFieldWidget(textRenderer, lx + 126, btnY, 56, 18, Text.literal(""))
        priceField?.setPlaceholder(Text.translatable("cobblemarket.sell.price_placeholder"))
        priceField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addSelectableChild(priceField)
        addDrawableChild(priceField)
        // 交付模式：价格在求购单交付弹窗输入，隐藏价格框，特训按钮顶上这个位置
        priceField?.visible = !deliverMode

        sellBtn = NineSliceButton(
            if (deliverMode) lx + 210 else lx + 186, btnY, if (deliverMode) 84 else 44, 20,
            Text.translatable(if (deliverMode) "cobblemarket.sell.deliver_select" else "cobblemarket.sell.sell"),
            { if (deliverMode) deliverSelected() else sellSelected() }
        )
        addDrawableChild(sellBtn)
        // 特训筛选（三态循环；交付模式移到价格框位置，给选择按钮让位）
        htBtn = NineSliceButton(
            if (deliverMode) lx + 126 else lx + 234, btnY, if (deliverMode) 80 else 60, 20,
            htButtonText(),
            { toggleHtFilter(); rebuild() },
            if (htFilter != 0) GOLD_COLOR else 0xFFFFFF
        )
        addDrawableChild(htBtn)

        rebuildFiltered()

        if (!loaded) {
            sendToServer(RequestMyPokemonPayload(0, requestId))
            loaded = true
        }
    }

    private fun rebuild() {
        // 推倒重建会新建所有输入框，用户已输入的内容必须保存后恢复
        // （照精灵市场 resize 的保存/恢复模式；那儿的筛选不重建界面，所以只在 resize 做）
        val oldSearch = searchField?.text ?: ""
        val oldIv = arrayOf(
            hpF?.text ?: "", atkF?.text ?: "", defF?.text ?: "",
            spaF?.text ?: "", spdF?.text ?: "", speF?.text ?: ""
        )
        val oldPrice = priceField?.text ?: ""
        clearChildren()
        init()
        searchField?.text = oldSearch
        arrayOf(hpF, atkF, defF, spaF, spdF, speF).forEachIndexed { i, f -> f?.text = oldIv[i] }
        priceField?.text = oldPrice
    }

    override fun close() {
        closed = true
        super.close()
    }

    private val allTypes = listOf("" to "") + listOf("normal","fire","water","electric","grass","ice","fighting","poison","ground","flying",
        "psychic","bug","rock","ghost","dragon","dark","steel","fairy").map { it to "cobblemon.type.$it" }

    // ── 属性展开列表（照精灵市场：不限+19 属性、属性色、限高滚动、展开隐藏下层与行内容） ──

    private fun toggleTypeList() {
        typeListOpen = !typeListOpen
        typeListScroll = 0
        rebuildTypeList()
    }

    private fun rebuildTypeList() {
        typeOptionButtons.forEach { remove(it) }
        typeOptionButtons.clear()
        // 展开时只隐藏**被列表覆盖**的控件：列表从 y=96 起最多 8 行盖到 208，
        // 所以只有 Row3（y=72~92 的按钮行）需要让位。搜索框(y=30~46)与 IV 行(y=50~66)
        // 都在列表上方、完全不被遮挡，必须保持可见——精灵市场同理（那边搜索框也不隐藏，
        // 它隐藏 IV 是因为它的 IV 两行落在列表覆盖区内，布局不同不能照抄名单）
        // 列表从 y=96 起、Row3 按钮行在 y=72~92 结束——列表不覆盖按钮行，无需隐藏同行按钮
        // （照精灵市场：展开属性列表时筛选按钮行保持可见，只隐藏被列表覆盖的区域）
        if (!typeListOpen) return
        val lx = width / 2 - 148
        allTypes.drop(typeListScroll).take(MAX_TYPE_LIST_ROWS).forEachIndexed { i, (_, key) ->
            val display = if (key.isEmpty()) Text.translatable("cobblemarket.gui.filter_any").string
                else Text.translatable(key).string
            val btn = NineSliceButton(
                lx + 2, 96 + i * 14, 292, 14,
                if (key.isNotEmpty() && key == typeFilter)
                    com.shusheng.cobblemarket.util.TextUtil.selectedText(
                        com.shusheng.cobblemarket.util.TextUtil.truncateString(display, 260)
                    )
                else Text.literal(com.shusheng.cobblemarket.util.TextUtil.truncateString(display, 260)),
                { selectType(key) },
                if (key.isNotEmpty()) typeColor(key) else 0xFFFFFF
            )
            typeOptionButtons.add(btn)
            addDrawableChild(btn)
        }
    }

    private fun selectType(key: String) {
        typeFilter = key
        typeIdx = allTypes.indexOfFirst { it.second == key }.coerceAtLeast(0)
        typeBtn?.setMessage(if (key.isEmpty()) Text.translatable("cobblemarket.sell.type") else Text.translatable(key))
        typeBtn?.textColor = if (key.isEmpty()) 0xFFFFFF else typeColor(key)
        typeListOpen = false
        rebuildTypeList()
        rebuild()
    }

    // 过滤结果缓存（照黑名单界面的 rebuildFiltered 模式）：数据/搜索/IV/筛选变化时重建，
    // render 每帧只读。indexedFilteredCache 同缓存带原始下标，省去行循环里每帧 indexOf。
    private var filteredCache = listOf<PokemonPreview>()
    private var indexedFilteredCache = listOf<IndexedValue<PokemonPreview>>()

    private fun filteredList(): List<PokemonPreview> = filteredCache

    private fun rebuildFiltered() {
        syncIvFields()
        // 先 withIndex 再过滤：IndexedValue.index 保持 pokemonList 原始下标，
        // 供图标缓存 iconData（按原列表索引构建）正确取值（filteredCache.withIndex() 的序号是过滤后位置，搜索后首行图标会错位）
        indexedFilteredCache = pokemonList.withIndex().filter { (_, p) ->
            val q = searchField?.text?.trim()?.takeIf { it.isNotEmpty() }
            (q == null || speciesDisplay(p).contains(q, ignoreCase = true) || p.speciesName.contains(q, ignoreCase = true)) &&
            (!shinyOnly || p.shiny) &&
            (genderFilter.isEmpty() || p.gender == genderFilter) &&
            (typeFilter.isEmpty() || p.primaryType == typeFilter || p.secondaryType == typeFilter) &&
            // IV 按有效值匹配：特训项用特训值，未特训用真实值（原生 31 与训练 31 都命中）
            // 比较方式按 ivOps（默认等于）
            (ivExact[0] < 0 || ivMatch(ivOps[0], if (p.htHp >= 0) p.htHp else p.ivsHp, ivExact[0])) &&
            (ivExact[1] < 0 || ivMatch(ivOps[1], if (p.htAtk >= 0) p.htAtk else p.ivsAtk, ivExact[1])) &&
            (ivExact[2] < 0 || ivMatch(ivOps[2], if (p.htDef >= 0) p.htDef else p.ivsDef, ivExact[2])) &&
            (ivExact[3] < 0 || ivMatch(ivOps[3], if (p.htSpAtk >= 0) p.htSpAtk else p.ivsSpAtk, ivExact[3])) &&
            (ivExact[4] < 0 || ivMatch(ivOps[4], if (p.htSpDef >= 0) p.htSpDef else p.ivsSpDef, ivExact[4])) &&
            (ivExact[5] < 0 || ivMatch(ivOps[5], if (p.htSpd >= 0) p.htSpd else p.ivsSpd, ivExact[5])) &&
            // 特训筛选三态
            when (htFilter) {
                1 -> p.htHp >= 0 || p.htAtk >= 0 || p.htDef >= 0 || p.htSpAtk >= 0 || p.htSpDef >= 0 || p.htSpd >= 0
                2 -> !(p.htHp >= 0 || p.htAtk >= 0 || p.htDef >= 0 || p.htSpAtk >= 0 || p.htSpDef >= 0 || p.htSpd >= 0)
                else -> true
            }
        }.toList()
        filteredCache = indexedFilteredCache.map { it.value }
    }

    private fun updateGenderButton() {
        genderBtn?.iconLeft = if (genderFilter == "FEMALE") GENDER_ICON_FEMALE else GENDER_ICON_MALE
        // 不限态并排显示 ♂♀，选定态只留对应的那个
        genderBtn?.iconLeft2 = if (genderFilter.isEmpty()) GENDER_ICON_FEMALE else null
    }

    private fun cycleGender() {
        genderFilter = when (genderFilter) {
            "" -> "MALE"
            "MALE" -> "FEMALE"
            else -> ""
        }
        rebuild()
    }

    private fun htButtonText(): Text = Text.translatable(when (htFilter) {
        0 -> "cobblemarket.gui.filter_ht_any"
        1 -> "cobblemarket.gui.filter_ht_on"
        else -> "cobblemarket.gui.filter_ht_off"
    })

    private fun toggleHtFilter() {
        // 循环顺序：不限(0) → 不含特训(2) → 仅特训(1) → 不限
        htFilter = when (htFilter) {
            0 -> 2
            2 -> 1
            else -> 0
        }
    }

    /** IV 比较匹配：0 = 等于，1 = 大于等于，2 = 小于等于 */
    private fun ivMatch(op: Int, eff: Int, value: Int): Boolean = when (op) {
        1 -> eff >= value
        2 -> eff <= value
        else -> eff == value
    }

    private fun syncIvOpButtons() {
        ivOpButtons.forEach { (i, btn) ->
            btn.message = Text.literal(if (ivOps[i] == 1) "≥" else if (ivOps[i] == 2) "≤" else "=")
            btn.textColor = if (ivOps[i] != 0) GOLD_COLOR else 0xFFFFFF
        }
    }

    /** IV 输入框 → ivExact 同步；返回是否有变化（变化时调用方重建过滤缓存） */
    private fun syncIvFields(): Boolean {
        val fields = arrayOf(hpF, atkF, defF, spaF, spdF, speF)
        var changed = false
        for (i in 0..5) {
            val raw = fields[i]?.text ?: ""
            val digits = raw.filter { it.isDigit() }.take(2)
            val v = digits.toIntOrNull()?.coerceIn(0, 31) ?: -1
            if (digits != raw) fields[i]?.text = if (v < 0) "" else v.toString()
            if (ivExact[i] != v) changed = true
            ivExact[i] = v
        }
        return changed
    }

    fun onPokemonList(payload: MyPokemonListPayload) {
        if (closed || payload.requestId != requestId) return
        if (payload.page == 0) {
            pokemonList = payload.pokemon
            selectedIndex = -1
            buildIconCache()
        } else {
            // 追加后续页：图标只增量构建。客户端串行请求，服务端按序处理，不会乱序。
            val startIdx = pokemonList.size
            pokemonList = pokemonList + payload.pokemon
            buildIconCache(startIdx)
        }
        rebuildFiltered()
        if (payload.hasMore) {
            // 响应驱动串行拉取，直到服务端说没有更多页
            sendToServer(RequestMyPokemonPayload(payload.page + 1, requestId))
        } else {
            loadedAll = true
        }
    }

    private fun sellSelected() {
        val filtered = filteredList()
        if (selectedIndex !in filtered.indices) return
        val price = priceField?.text?.toIntOrNull() ?: run {
            playFailSound()
            return
        }
        if (price <= 0) { playFailSound(); return }
        sendToServer(SellFromStoragePayload(filtered[selectedIndex].uuid, price))
    }

    /** 交付模式：把所选精灵回传求购单界面（静态暂存），返回继续交付流程 */
    private fun deliverSelected() {
        val filtered = filteredList()
        if (selectedIndex !in filtered.indices) return
        BuyOrderScreen.pendingDeliverPokemon = filtered[selectedIndex]
        client?.setScreen(BuyOrderScreen())
    }

    private fun typeColor(tk: String) = when (tk.substringAfterLast(".").lowercase()) {
        "normal" -> 0xAAAA99; "fire" -> 0xFF4422; "water" -> 0x3399FF
        "electric" -> 0xFFCC33; "grass" -> 0x77CC55; "ice" -> 0x66CCFF
        "fighting" -> 0xBB5544; "poison" -> 0xAA5599; "ground" -> 0xDDBB55
        "flying" -> 0x8899FF; "psychic" -> 0xFF5599; "bug" -> 0xAABB22
        "rock" -> 0xBBAA66; "ghost" -> 0x6666BB; "dragon" -> 0x7766EE
        "dark" -> 0x775544; "steel" -> 0xAAAABB; "fairy" -> 0xFFAAFF
        else -> 0xFFFFFF
    }

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int, sliceH: Int = 16) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val panelLeft = width / 2 - 160
        val panelTop = 2
        val panelBottom = height - 32
        val sliceH = 16

        val top = Identifier.of("cobblemarket", "textures/gui/market_panel_top.png")
        val mid = Identifier.of("cobblemarket", "textures/gui/market_panel_middle.png")
        val bot = Identifier.of("cobblemarket", "textures/gui/market_panel_bottom.png")

        drawPanelSlice(context, top, panelLeft, panelTop)
        var y = panelTop + sliceH
        while (y < panelBottom - sliceH) {
            drawPanelSlice(context, mid, panelLeft, y, minOf(sliceH, panelBottom - sliceH - y))
            y += sliceH
        }
        drawPanelSlice(context, bot, panelLeft, panelBottom - sliceH)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // 固拉多第七步（精灵市场背景下层水平右飞）画在 super.render 之前：
        // super.render 内部先调 renderBackground 画市场面板背景，精灵先画会被面板自然盖住
        renderGroudonFly(context, true)
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable(if (deliverMode) "cobblemarket.sell.deliver_title" else "cobblemarket.sell.title").formatted(Formatting.GOLD),
            width / 2, 20, 0xFFFFFF)
        val lx = width / 2 - 148
        val panelW = 296
        val iconSize = 20
        val startY = 96
        // IV 输入变化时重建过滤缓存（原 filteredList 每次调用都先 syncIvFields，缓存后在此统一检测）
        if (syncIvFields()) rebuildFiltered()
        val filtered = filteredList()
        val maxVisible = maxOf(0, (height - startY - 48) / rowHeight)
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, filtered.size - maxVisible))

        // 底部计数/加载进度画在屏幕底部（height-49），在展开列表下方，照常显示
        if (loaded && !loadedAll) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("cobblemarket.sell.loading_more", pokemonList.size).formatted(Formatting.GRAY), width / 2, height - 49, 0x888888)
        } else if (filtered.size > maxVisible) {
            context.drawCenteredTextWithShadow(textRenderer, "${scrollOffset + 1}-${minOf(scrollOffset + maxVisible, filtered.size)} / ${filtered.size}", width / 2, height - 49, 0x888888)
        }

        // 属性展开列表打开时跳过行内容、悬停与列表区提示（都画在 children 之后，会刺穿列表按钮）
        if (typeListOpen) return
        var hovered = -1
        if (mouseX in lx..(lx + panelW) && mouseY >= startY) {
            val row = ((mouseY - startY) / rowHeight) + scrollOffset
            if (row in filtered.indices) hovered = row
        }
        // List
        for (i in scrollOffset until minOf(scrollOffset + maxVisible, filtered.size)) {
            val indexed = indexedFilteredCache[i]
            val e = indexed.value
            val origIdx = indexed.index
            val y = startY + (i - scrollOffset) * rowHeight
            val rowState = when { i == selectedIndex -> 2; i == hovered -> 1; else -> 0 }
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, lx, y, panelW, rowHeight, rowState, ROW_BACKGROUND_TEX_H)

            // 3D icon slot background
            val slotX = lx + 2
            val slotY = y + 2
            context.matrices.push()
            context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
            context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
            context.drawTexture(POKEMON_SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
            context.matrices.pop()
            val iconX = lx + 2
            val iconY = y + 2
            renderPokemonIcon(context, origIdx, iconX, iconY, iconSize, delta = delta)

            // Species（[队]/[PC] 固定色，精灵名属性色 + 金色闪光星标拆段绘制；PC 用电脑物品图标，[] 保持原色）
            val tc = typeColor(if (e.primaryType.isNotEmpty()) e.primaryType else "cobblemon.type.normal")
            val srcColor = if (e.source == "party") 0x55FF55 else 0x55AAFF
            var sx = lx + 28
            if (e.source == "party") {
                val src = Text.translatable("cobblemarket.sell.party").string
                context.drawText(textRenderer, src, sx, y + 7, srcColor, false)
                sx += textRenderer.getWidth(src) + 4
            } else {
                context.drawText(textRenderer, "[", sx, y + 7, srcColor, false)
                sx += textRenderer.getWidth("[")
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = pcItemStack, x = sx.toDouble(), y = y + 5.0, scale = 0.75, matrixStack = context.matrices)
                sx += 12
                context.drawText(textRenderer, "]", sx, y + 7, srcColor, false)
                sx += textRenderer.getWidth("]") + 4
            }

            // Ball icon（球种统一在精灵名称左侧；ball 非空就占位 12px，无论解析成败，与原来一致）
            if (e.ball.isNotEmpty()) {
                iconData[origIdx]?.ballStack?.let { ballStack ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = ballStack, x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                }
                sx += 12
            }

            context.drawTextWithShadow(textRenderer, speciesDisplay(e), sx, y + 7, tc)
            sx += textRenderer.getWidth(speciesDisplay(e))
            if (e.shiny) {
                context.drawText(textRenderer, "★", sx + 2, y + 7, GOLD_COLOR, false)
                sx += 2 + textRenderer.getWidth("★")
            }
            sx += 2

            // Gender icon（紧跟名字）
            if (e.gender == "MALE" || e.gender == "FEMALE") {
                val gi = if (e.gender == "MALE") GENDER_ICON_MALE else GENDER_ICON_FEMALE
                com.cobblemon.mod.common.api.gui.blitk(matrixStack = context.matrices, texture = gi, x = sx, y = y + 7, width = 6, height = 8)
                sx += 8
            }

            // Held item icon（紧跟性别；空气/空 id 已过滤为 null）
            iconData[origIdx]?.heldStack?.let { heldStack ->
                com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                    itemStack = heldStack, x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                sx += 12
            }

            // Level
            val levelText = Text.translatable("cobblemarket.gui.lv").string + e.level
            context.drawText(textRenderer, levelText, lx + 135, y + 7, 0x000000, false)
        }

        // Tooltip on hover
        if (hovered in filtered.indices) {
            renderTooltip(context, filtered[hovered], mouseX, mouseY, if (hovered == selectedIndex) 2 else 1)
        }

        // 列表区提示（startY+50 落在展开列表覆盖范围内，所以留在 return 之后）
        if (filtered.isEmpty() && loaded && loadedAll) {
            // 只有全部分页拉完才能断言"没有"，否则匹配项可能还在未加载的页里
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("cobblemarket.sell.no_pokemon").formatted(Formatting.GRAY), width / 2, startY + 50, 0xFFFFFF)
        }
        if (!loaded) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("cobblemarket.sell.loading").formatted(Formatting.GRAY), width / 2, startY + 50, 0xFFFFFF)
        }

        // 固拉多飞行：悬浮在界面之上（与精灵市场同公式，切换界面位置延续）。
        // flush 夹心 + 清深度：行内 3D 精灵图标/球种图标写深度或抬高 z，会盖住普通 GUI 纹理
        context.draw()
        com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.MinecraftClient.IS_SYSTEM_MAC)
        renderGroudonFly(context, false)
        context.draw()
    }

    /** 固拉多飞行（照精灵市场，重做第四步）：水平飞入停靠点 2s → 逆时针弧 90° 出屏 1.8s（θ 90°→0°，头朝上竖直出屏）→
     *  屏外切线掉头朝下 → 顺时针绕半圆 180° 到屏幕右半上方 1.2s（屏外提速）→
     *  屏外快速竖直下移 0.4s → 屏幕内加速下坠出屏底（速度与原 3s 版屏幕内段一致）→
     *  第四步（不连接上一步）：镜像图（头朝左、上下正常）从屏幕右上角外水平飞入停在右上角 1.2s，
     *  停留 5s 悬停（等下一步）。
     *  路径点 (px,py) 是图片左侧中间的轨迹（旋转轴心 = 图片左侧中间，贴图坐标 (0,150)），
     *  精灵主体拖在轴心后方，转弯时绕尾部牵引转向 */
    private fun renderGroudonFly(context: DrawContext, under: Boolean) {
        if (!com.shusheng.cobblemarket.client.ClientConfig.groudonFly) return
        val g = com.shusheng.cobblemarket.client.GroudonFly
        // 计时起点在全局 GroudonFly（照 PokemonCelebrationAnimation 的全局状态）：
        // 切换界面动画不重置，时间线延续
        if (g.startAt == 0L) g.startAt = System.currentTimeMillis()
        val t = (System.currentTimeMillis() - g.startAt).toFloat()
        // 轴心停靠点：左侧中间停在 (35,110) 时主体中心落在 (140,110)，与第一步停靠位置一致；
        // 半径 150 的圆弧：圆心在轴心正上方 150 处 (35,-40)（屏幕顶边外）
        val pivotX = 35f
        val pivotY = 110f
        val radius = 150f
        val centerX = pivotX
        val centerY = pivotY - radius
        val theta0 = atan2((pivotY - centerY).toDouble(), (pivotX - centerX).toDouble()).toFloat()
        // 逆时针段 θ: 90°→0°（绕 90°，头朝上出屏）
        val endTheta = 0f
        val arcEndMs = g.FLY_IN_MS + g.ARC_MS
        val arc2EndMs = arcEndMs + g.ARC2_MS
        // 下坠段：屏外 170px 用 DROP_OUT_MS 快速通过；屏幕内部分复刻原 3s k² 时间表，
        // 入屏时刻 tIn = 3000·√(170/(height+370))，之后按原曲线到 3000ms 出屏底，屏幕内速度感不变
        val tIn = 3000f * kotlin.math.sqrt(170f / (height + 370f))
        val dropEndMs = arc2EndMs + g.DROP_OUT_MS + (3000f - tIn)
        val flyIn2EndMs = dropEndMs + g.FLY_IN2_MS
        val hoverEndMs = flyIn2EndMs + g.HOVER_MS
        val flyLeftEndMs = hoverEndMs + g.FLY_LEFT_MS
        val flyIn3EndMs = flyLeftEndMs + g.FLY_IN2_MS
        val flyRightEndMs = flyIn3EndMs + g.FLY_RIGHT_MS
        // 顺时针半圆 180°：起点 = 出屏点（圆 (35,-40) 半径 150 的右端点 (185,-40)），
        // 终点 = 屏幕左半上方（1/4 宽处 (-170 高度)，倒放下坠段即「从左下飞到左上」）；圆心取两点中点，弧线拱向上方
        val arc2FromX = centerX + radius
        val arc2FromY = centerY
        val arc2ToX = width * 0.25f
        val arc2ToY = -170f
        val arc2CX = (arc2FromX + arc2ToX) / 2f
        val arc2CY = (arc2FromY + arc2ToY) / 2f
        val arc2R = kotlin.math.hypot(arc2FromX - arc2CX, arc2FromY - arc2CY)
        val arc2Theta0 = atan2((arc2FromY - arc2CY).toDouble(), (arc2FromX - arc2CX).toDouble()).toFloat()
        val arc2Theta1 = atan2((arc2ToY - arc2CY).toDouble(), (arc2ToX - arc2CX).toDouble()).toFloat() + (2f * PI.toFloat())
        // 整轮动画（上半 + 下半 = 2×flyRightEndMs）在精灵市场/上架选择界面循环播放：t 取模
        // 下半部分 = 上半部分绕屏幕中心的中心对称（180° 旋转：x、y 都翻转，顺序相同）：
        // tUp 从 0 顺序重演；视觉旋转方向不变（逆时针弧仍是逆时针，只是向下出屏底），运动方向反向；
        // 镜像图状态取反（头朝运动方向、正立不倒置）；层次判定按 tUp
        val tLoop = t % (2f * flyRightEndMs)
        val tUp: Float
        val flip: Boolean
        if (tLoop < flyRightEndMs) {
            tUp = tLoop
            flip = false
        } else {
            tUp = tLoop - flyRightEndMs
            flip = true
        }
        var px: Float
        var py: Float
        var targetRot: Float
        when {
            tUp < g.FLY_IN_MS -> {
                // 飞入：轴心从屏幕左边缘外水平飞向停靠点（朝右时图片整体在轴心右侧，轴心 x<-210 即全出屏），朝右。
                // 缓动末速度≈131px/s 与逆时针弧初线速度一致，速度连续无停顿（纯 ease-out 会停稳再启动）
                val k = tUp / g.FLY_IN_MS
                val s = 0.465f * (2f * k - k * k) + 0.535f * k
                px = -245f + (pivotX + 245f) * s
                py = pivotY
                targetRot = 0f
            }
            tUp < arcEndMs -> {
                // 逆时针弧 90° 出屏（视觉逆时针 = θ 减小，切线角 θ-90°，末段头朝上）
                val k = (tUp - g.FLY_IN_MS) / g.ARC_MS
                val theta = theta0 + (endTheta - theta0) * k
                px = centerX + radius * cos(theta)
                py = centerY + radius * sin(theta)
                targetRot = Math.toDegrees(theta.toDouble()).toFloat() - 90f
            }
            tUp < arc2EndMs -> {
                // 顺时针绕半圆 180°（θ 增大，切线角 θ+90°）：屏外掉头，弧线拱向上方到屏幕右半
                val k = (tUp - arcEndMs) / g.ARC2_MS
                val theta = arc2Theta0 + (arc2Theta1 - arc2Theta0) * k
                px = arc2CX + arc2R * cos(theta)
                py = arc2CY + arc2R * sin(theta)
                targetRot = Math.toDegrees(theta.toDouble()).toFloat() + 90f
            }
            tUp < dropEndMs -> {
                // 头朝下竖直下坠：屏外快速下移（不可见段提速），入屏后按原 3s 版屏幕内速度曲线加速
                val dt = tUp - arc2EndMs
                px = arc2ToX
                if (dt < g.DROP_OUT_MS) {
                    py = arc2ToY * (1f - dt / g.DROP_OUT_MS)
                } else {
                    val kk = (tIn + (dt - g.DROP_OUT_MS)) / 3000f
                    py = arc2ToY + (height + 200f - arc2ToY) * (kk * kk)
                }
                targetRot = 90f
            }
            tUp < flyIn2EndMs -> {
                // 第四步：镜像图（头朝左）从屏幕右上角外水平 ease-out 飞入，
                // 停在右上角（轴心 (width,90)：主体 x [width-210,width]、y [0,180] 刚好贴住右上角）
                val k = (tUp - dropEndMs) / g.FLY_IN2_MS
                val s = 1f - (1f - k) * (1f - k)
                px = (width + 245f) - 245f * s
                py = 90f
                targetRot = 0f
            }
            tUp < hoverEndMs -> {
                // 右上角悬停 5s，头正朝左
                px = width.toFloat()
                py = 90f
                targetRot = 0f
            }
            tUp < flyLeftEndMs -> {
                // 第五步：水平向左飞出屏幕，smoothstep 缓动（悬停平滑启动、出屏减速停稳）
                val k = (tUp - hoverEndMs) / g.FLY_LEFT_MS
                val s = 3f * k * k - 2f * k * k * k
                px = width.toFloat() - (width + 245f) * s
                py = 90f
                targetRot = 0f
            }
            tUp < flyIn3EndMs -> {
                // 第六步：恢复原图（再次镜像，头朝右）从左下角外水平飞入，穿过左下角不停留；
                // 缓动末速 ≈269px/s 与第七步匀速一致（速度连续，无停→启动停顿）
                val k = (tUp - flyLeftEndMs) / g.FLY_IN2_MS
                val s = 0.68f * k + 0.32f * k * k
                px = -245f + 245f * s
                py = height - 90f
                targetRot = 0f
            }
            tUp < flyRightEndMs -> {
                // 第七步：在市场面板背景下层水平向右飞（绘制在面板之前，被背景盖住），
                // 匀速 ≈269px/s 飞出屏幕右侧
                val k = (tUp - flyIn3EndMs) / g.FLY_RIGHT_MS
                px = 0f + (width + 245f) * k
                py = height - 90f
                targetRot = 0f
            }
            else -> {
                // 停在屏幕右侧外（等下一步）
                px = width + 245f
                py = height - 90f
                targetRot = 0f
            }
        }
        // 下半部分中心对称修正：x、y 都绕屏幕中心翻转（180° 旋转），朝向角不变（运动方向自然反向）
        if (flip) {
            px = width - px
            py = height - py
        }
        // 层次过滤：第六步（左下角飞入）与第七步（背景下层水平右飞）从飞出开始全程画在背景下层，
        // 避免第六步上层→第七步下层的层级突变；其余段画在上层。每帧只绘制一次，smoothRot 也只更新一次
        val isUnder = tUp >= flyLeftEndMs && tUp < flyRightEndMs
        if (isUnder != under) return
        g.smoothRot += (((targetRot - g.smoothRot + 540f) % 360f) - 180f) * 0.15f
        val rotDeg = g.smoothRot
        val flyFrame = ((System.currentTimeMillis() / g.FRAME_MS) % 40).toInt()
        val flyTex = Identifier.of("cobblemarket", "textures/gui/groudon_sheet.png")
        val flyCol = flyFrame % 10
        val flyRow = flyFrame / 10
        val flyW = 210f
        // 第四、五步用镜像图（scale x 取负，静态镜像非旋转中翻面）：头朝左、上下正常；
        // 第六步起恢复原图（再次镜像）。负 scale 翻转三角形朝向，行内 3D 精灵图标渲染
        // 可能残留背面剔除开启会整图不画——绘制时临时关 cull，画完按原状态恢复
        // 正向镜像区间为第四、五步；下半部分（中心对称）运动方向相反，镜像状态取反（头朝运动方向、正立不倒置）
        val mirrored = (tUp >= dropEndMs && tUp < flyLeftEndMs) != flip
        val wasCull = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_CULL_FACE)
        if (mirrored) org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_CULL_FACE)
        context.matrices.push()
        context.matrices.translate(px.toDouble(), py.toDouble(), 0.0)
        context.matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(rotDeg))
        context.matrices.scale(if (mirrored) -flyW / 350f else flyW / 350f, 180f / 300f, 1f)
        context.drawTexture(flyTex, 0, -150, (flyCol * 350).toFloat(), (flyRow * 300).toFloat(), 350, 300, 3500, 1200)
        context.matrices.pop()
        if (mirrored && wasCull) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_CULL_FACE)
    }

    private fun renderPokemonIcon(context: DrawContext, origIdx: Int, x: Int, y: Int, size: Int, delta: Float = 0f) {
        val data = iconData[origIdx] ?: return run {
            val tc = 0x88888888.toInt()
            context.fill(x, y, x + size, y + size, tc)
        }
        val matrices = context.matrices
        matrices.push()
        try {
            context.enableScissor(x - 4, y - 4, x + size + 4, y + size + 4)
            matrices.translate(x + size / 2.0, y + 1.0, 0.0)
            matrices.scale(size / 25f * 2.5f, size / 25f * 2.5f, 1f)
            // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
            val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
            drawProfilePokemon(
                renderablePokemon = data.renderable, matrixStack = matrices,
                rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                state = data.state, partialTicks = if (useFloat) delta else 0f, scale = 4.5f
            )
        } catch (_: Exception) {
        } finally {
            context.disableScissor()
            matrices.pop()
        }
    }

    // tooltip 内容缓存：内容只取决于条目，悬停同一行时每帧重建全部文本行是悬停掉帧主因
    private var tooltipCacheKey: java.util.UUID? = null
    private var tooltipCacheLines: List<Pair<Text?, Int>> = emptyList()
    private var tooltipCacheHeldLine = -1
    private var tooltipCacheMarksLine = -1
    private var tooltipCacheMarksRows = 0
    private var tooltipCacheMaxWidth = 0

    private fun renderTooltip(context: DrawContext, p: PokemonPreview, mx: Int, my: Int, bgState: Int) {
        // 文本行缓存：悬停同一行时内容不变，只在悬停目标变化时重建（见 tooltipCacheKey 注释）
        if (tooltipCacheKey != p.uuid) {
            tooltipCacheKey = p.uuid
            val hp = Text.translatable("cobblemon.stat.hp.name").string
            val atk = Text.translatable("cobblemon.stat.attack.name").string
            val def = Text.translatable("cobblemon.stat.defence.name").string
            val spa = Text.translatable("cobblemon.stat.special_attack.name").string
            val spd = Text.translatable("cobblemon.stat.special_defence.name").string
            val spe = Text.translatable("cobblemon.stat.speed.name").string
            val typeText = Text.translatable(p.primaryType).string +
                if (p.secondaryType.isNotEmpty()) " + ${Text.translatable(p.secondaryType).string}" else ""

            val hasHeldItem = p.heldItemId.isNotEmpty() &&
                Identifier.tryParse(p.heldItemId)?.let { Registries.ITEM.get(it) != Registries.ITEM.get(Identifier.of("minecraft", "air")) } == true

            // 行列表：null = 分割线；证章行在 marksLine 索引处画图标
            val lines = mutableListOf<Pair<Text?, Int>>()
            lines.add(EntryBadgeRenderer.nameWithShinyStar(speciesDisplay(p), p.shiny)
                .copy().append(Text.literal("  Lv.${p.level}")) to typeColor(p.primaryType))
            lines.add(Text.literal(Text.translatable("cobblemarket.gui.tooltip_type").string).append(EntryBadgeRenderer.typeLine(p.primaryType, p.secondaryType)) to 0xFFFFFF)
            lines.add(Text.literal(Text.translatable("cobblemarket.gui.tooltip_nature").string)
                .append(EntryBadgeRenderer.natureText(p.natureBase, p.nature))
                .append(Text.literal("  ${Text.translatable("cobblemarket.gui.tooltip_ability").string}"))
                .append(Text.translatable(p.ability)) to 0xFFFFFF)
            var heldItemLine = -1
            if (hasHeldItem) {
                heldItemLine = lines.size
                lines.add(Text.translatable("cobblemarket.gui.tooltip_held") to 0xFFFFFF)
            }
            lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs") to 0xFFFFFF)
            lines.add(Text.literal("  $hp:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsHp, p.htHp)}").append(Text.literal("  EV:${p.evsHp}").formatted(Formatting.RED)) to 0x66FF66); lines.add(Text.literal("  $atk:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsAtk, p.htAtk)}").append(Text.literal("  EV:${p.evsAtk}").formatted(Formatting.RED)) to 0xFF6666)
            lines.add(Text.literal("  $def:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsDef, p.htDef)}").append(Text.literal("  EV:${p.evsDef}").formatted(Formatting.RED)) to 0xFFCC66); lines.add(Text.literal("  $spa:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpAtk, p.htSpAtk)}").append(Text.literal("  EV:${p.evsSpAtk}").formatted(Formatting.RED)) to 0x6699FF)
            lines.add(Text.literal("  $spd:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpDef, p.htSpDef)}").append(Text.literal("  EV:${p.evsSpDef}").formatted(Formatting.RED)) to 0x66FF99); lines.add(Text.literal("  $spe:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpd, p.htSpd)}").append(Text.literal("  EV:${p.evsSpd}").formatted(Formatting.RED)) to 0xFF99FF); lines.add(Text.translatable("cobblemarket.gui.friendship", p.friendship) to 0xFF99CC)
            // 证章区块（拥有的全部证章，纯外观）：亲密度下方两条分割线夹图标（每行 6 个；服务端直接传纹理路径）
            var marksLine = -1
            var marksRows = 0
            val markTextures = p.marks.mapNotNull { Identifier.tryParse(it) }
            if (markTextures.isNotEmpty()) {
                marksRows = if (markTextures.size > 6) 2 else 1
                lines.add(null to 0)
                marksLine = lines.size
                lines.add(null to 0)
                lines.add(null to 0)
            }

            var mw = 0; lines.forEach { if (it.first != null) mw = maxOf(mw, textRenderer.getWidth(it.first)) }
            if (heldItemLine >= 0) {
                mw = maxOf(mw, textRenderer.getWidth(lines[heldItemLine].first) + 14)
            }
            if (marksRows > 0) mw = maxOf(mw, minOf(6, markTextures.size) * 12)
            tooltipCacheLines = lines
            tooltipCacheHeldLine = heldItemLine
            tooltipCacheMarksLine = marksLine
            tooltipCacheMarksRows = marksRows
            tooltipCacheMaxWidth = mw
        }
        val lines = tooltipCacheLines
        val heldItemLine = tooltipCacheHeldLine
        val marksLine = tooltipCacheMarksLine
        val marksRows = tooltipCacheMarksRows
        val mw = tooltipCacheMaxWidth
        val pad = 4
        val tx = minOf(mx + 12, width - mw - 12)
        val th = lines.size * 10 + pad + (marksRows - 1).coerceAtLeast(0) * 12
        val ty = if (my - th - 4 <= 0) minOf(my + 12, height - th) else my - th - 4

        context.matrices.push(); context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - pad, ty - pad, mw + 2 * pad, th + pad, bgState, ROW_BACKGROUND_TEX_H)
        var rowY = ty
        lines.forEachIndexed { i, (line, color) ->
            if (line == null && i == marksLine) {
                // 证章图标行：第一行最多 6 个；超过 6 个第二行显示「+N」
                val textures = p.marks.mapNotNull { Identifier.tryParse(it) }
                textures.take(6).forEachIndexed { idx, texture ->
                    com.cobblemon.mod.common.api.gui.blitk(
                        matrixStack = context.matrices, texture = texture,
                        x = tx + idx * 12, y = rowY, width = 8, height = 8
                    )
                }
                if (textures.size > 6) {
                    context.drawTextWithShadow(textRenderer, "+${textures.size - 6}", tx, rowY + 12, 0xAAAAAA)
                    rowY += 24
                } else {
                    rowY += 12
                }
            } else if (line == null) {
                // 分割线：1px 灰线，宽度只包住证章图标行（首行证章数 × 12 - 4）
                val rowW = minOf(6, p.marks.size) * 12 - 4
                context.fill(tx, rowY + 4, tx + rowW, rowY + 5, 0xFF555555.toInt())
                rowY += 10
            } else if (i == heldItemLine) {
                context.drawTextWithShadow(textRenderer, line, tx, rowY, color)
                Identifier.tryParse(p.heldItemId)?.let { heldId ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = ItemStack(Registries.ITEM.get(heldId)),
                        x = tx + textRenderer.getWidth(line) + 2.0,
                        y = rowY + 0.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                }
                rowY += 10
            } else if (i == 0) {
                // 第一行（名字★Lv）带公母图标
                EntryBadgeRenderer.drawNameLineLeft(context, line, p.gender, tx, rowY, color)
                rowY += 10
            } else {
                context.drawTextWithShadow(textRenderer, line, tx, rowY, color)
                rowY += 10
            }
        }
        context.matrices.pop()
    }

    private fun isInputFieldFocused() = focused?.let { f ->
        f === searchField || f === hpF || f === atkF || f === defF || f === spaF || f === spdF || f === speF || f === priceField
    } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true ||
        hpF?.isMouseOver(mouseX, mouseY) == true ||
        atkF?.isMouseOver(mouseX, mouseY) == true ||
        defF?.isMouseOver(mouseX, mouseY) == true ||
        spaF?.isMouseOver(mouseX, mouseY) == true ||
        spdF?.isMouseOver(mouseX, mouseY) == true ||
        speF?.isMouseOver(mouseX, mouseY) == true ||
        priceField?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mx: Double, my: Double, btn: Int): Boolean {
        val wasInInput = isInputFieldFocused()
        val r = super.mouseClicked(mx, my, btn)
        if (wasInInput && !isMouseOverAnyInput(mx, my)) {
            focused = null
        }
        // 与 render 相同的 IV 检测：事件读缓存前确保 ivExact 与输入框一致
        if (syncIvFields()) rebuildFiltered()
        val filtered = filteredList()
        val lx = width / 2 - 148
        val startY = 96
        val maxVisible = maxOf(0, (height - startY - 48) / rowHeight)
        if (mx in lx.toDouble()..(lx + 296).toDouble() && my >= startY) {
            val row = ((my - startY) / rowHeight).toInt() + scrollOffset
            if (row in filtered.indices) { selectedIndex = row; return true }
        }
        return r
    }

    override fun mouseScrolled(mx: Double, my: Double, h: Double, v: Double): Boolean {
        // 属性展开列表滚动
        if (typeListOpen) {
            if (allTypes.size > MAX_TYPE_LIST_ROWS) {
                typeListScroll = (typeListScroll - v.toInt()).coerceIn(0, allTypes.size - MAX_TYPE_LIST_ROWS)
                rebuildTypeList()
            }
            return true
        }
        if (syncIvFields()) rebuildFiltered()
        val filtered = filteredList()
        val maxVisible = maxOf(0, (height - 96 - 48) / rowHeight)
        scrollOffset = (scrollOffset - v.toInt()).coerceIn(0, maxOf(0, filtered.size - maxVisible))
        return true
    }

    fun onMarketResult(payload: MarketResultPayload) {
        if (payload.success) {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.GREEN), false)
            priceField?.text = ""
            selectedIndex = -1
            // 上架成功后从第一页重新拉取全量列表
            pokemonList = listOf()
            iconData.clear()
            loadedAll = false
            rebuildFiltered()
            sendToServer(RequestMyPokemonPayload(0, requestId))
        } else {
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.RED), false)
        }
    }

    override fun shouldPause() = false

    companion object {
        private var nextRequestId = 0
        const val MAX_TYPE_LIST_ROWS = 8
        val GENDER_ICON_MALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
        val GENDER_ICON_FEMALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
        val POKEMON_SLOT_TEXTURE = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
    }
}
