package com.shusheng.cobblemarket.screen

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.entity.PoseType
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.network.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.joml.Quaternionf

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
    private var typeIdx = 0
    private val minIvs = IntArray(6) { -1 }
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
    private data class IconData(val renderable: RenderablePokemon, val state: FloatingState)
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
            iconData[i] = IconData(RenderablePokemon(species, aspects, ItemStack.EMPTY), FloatingState())
        }
    }

    override fun init() {
        super.init()
        val lx = width / 2 - 148
        val panelW = 296

        // Row 1: Search (full width)
        searchField = TextFieldWidget(textRenderer, lx + 2, 30, panelW - 4, 16, Text.translatable("cobblemarket.sell.search"))
        searchField?.setPlaceholder(Text.translatable("cobblemarket.sell.search"))
        addSelectableChild(searchField)
        addDrawableChild(searchField)

        // Row 2 (y=50): IV fields
        fun mkIv(x: Int, ph: String): TextFieldWidget {
            val f = TextFieldWidget(textRenderer, x, 50, 46, 16, Text.literal(""))
            f.setPlaceholder(Text.literal(ph))
            f.setTextPredicate { it.length <= 2 && it.all { c -> c.isDigit() } }
            addSelectableChild(f); addDrawableChild(f)
            return f
        }
        hpF = mkIv(lx + 2, "HP"); atkF = mkIv(lx + 51, "ATK"); defF = mkIv(lx + 100, "DEF")
        spaF = mkIv(lx + 149, "SpA"); spdF = mkIv(lx + 198, "SpD"); speF = mkIv(lx + 247, "Spd")

        // 返回按钮：右上角（与精灵市场统一；交付模式返回求购单界面）
        addDrawableChild(NineSliceButton(
            lx + panelW - 50, 13, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(if (deliverMode) BuyOrderScreen() else MarketScreen()) }
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

        if (!loaded) {
            ClientPlayNetworking.send(RequestMyPokemonPayload(0, requestId))
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
        shinyBtn?.visible = !typeListOpen
        genderBtn?.visible = !typeListOpen
        priceField?.visible = !typeListOpen && !deliverMode
        sellBtn?.visible = !typeListOpen
        htBtn?.visible = !typeListOpen
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

    private fun filteredList(): List<PokemonPreview> {
        syncIvFields()
        return pokemonList.filter { p ->
            val q = searchField?.text?.trim()?.takeIf { it.isNotEmpty() }
            (q == null || speciesDisplay(p).contains(q, ignoreCase = true) || p.speciesName.contains(q, ignoreCase = true)) &&
            (!shinyOnly || p.shiny) &&
            (genderFilter.isEmpty() || p.gender == genderFilter) &&
            (typeFilter.isEmpty() || p.primaryType == typeFilter || p.secondaryType == typeFilter) &&
            // IV 按有效值匹配：特训项用特训值，未特训用真实值（原生 31 与训练 31 都命中）
            (minIvs[0] < 0 || (if (p.htHp >= 0) p.htHp else p.ivsHp) == minIvs[0]) &&
            (minIvs[1] < 0 || (if (p.htAtk >= 0) p.htAtk else p.ivsAtk) == minIvs[1]) &&
            (minIvs[2] < 0 || (if (p.htDef >= 0) p.htDef else p.ivsDef) == minIvs[2]) &&
            (minIvs[3] < 0 || (if (p.htSpAtk >= 0) p.htSpAtk else p.ivsSpAtk) == minIvs[3]) &&
            (minIvs[4] < 0 || (if (p.htSpDef >= 0) p.htSpDef else p.ivsSpDef) == minIvs[4]) &&
            (minIvs[5] < 0 || (if (p.htSpd >= 0) p.htSpd else p.ivsSpd) == minIvs[5]) &&
            // 特训筛选三态
            when (htFilter) {
                1 -> p.htHp >= 0 || p.htAtk >= 0 || p.htDef >= 0 || p.htSpAtk >= 0 || p.htSpDef >= 0 || p.htSpd >= 0
                2 -> !(p.htHp >= 0 || p.htAtk >= 0 || p.htDef >= 0 || p.htSpAtk >= 0 || p.htSpDef >= 0 || p.htSpd >= 0)
                else -> true
            }
        }
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

    private fun syncIvFields() {
        val fields = arrayOf(hpF, atkF, defF, spaF, spdF, speF)
        for (i in 0..5) {
            val raw = fields[i]?.text ?: ""
            val digits = raw.filter { it.isDigit() }.take(2)
            val v = digits.toIntOrNull()?.coerceIn(0, 31) ?: -1
            if (digits != raw) fields[i]?.text = if (v < 0) "" else v.toString()
            minIvs[i] = v
        }
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
        if (payload.hasMore) {
            // 响应驱动串行拉取，直到服务端说没有更多页
            ClientPlayNetworking.send(RequestMyPokemonPayload(payload.page + 1, requestId))
        } else {
            loadedAll = true
        }
    }

    private fun sellSelected() {
        val filtered = filteredList()
        if (selectedIndex !in filtered.indices) return
        val price = priceField?.text?.toIntOrNull() ?: return
        if (price <= 0) return
        ClientPlayNetworking.send(SellFromStoragePayload(filtered[selectedIndex].uuid, price))
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

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f, 1f)
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
            drawPanelSlice(context, mid, panelLeft, y)
            y += sliceH
        }
        drawPanelSlice(context, bot, panelLeft, panelBottom - sliceH)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable(if (deliverMode) "cobblemarket.sell.deliver_title" else "cobblemarket.sell.title").formatted(Formatting.GOLD),
            width / 2, 20, 0xFFFFFF)
        val lx = width / 2 - 148
        val panelW = 296
        val iconSize = 20
        val startY = 96
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
            val e = filtered[i]
            val origIdx = pokemonList.indexOf(e)
            val y = startY + (i - scrollOffset) * rowHeight
            val rowState = when { i == selectedIndex -> 2; i == hovered -> 1; else -> 0 }
            drawNineSlice(context, ROW_BACKGROUND_TEXTURE, lx, y, panelW, rowHeight, rowState, ROW_BACKGROUND_TEX_H)

            // 3D icon slot background
            val slotX = lx + 2
            val slotY = y + 2
            val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
            context.matrices.push()
            context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
            context.matrices.scale(iconSize / 66f, iconSize / 66f, 1f)
            context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
            context.matrices.pop()
            val iconX = lx + 2
            val iconY = y + 2
            renderPokemonIcon(context, origIdx, iconX, iconY, iconSize)

            // Species（[队]/[PC] 固定色，精灵名属性色 + 金色闪光星标拆段绘制）
            val src = Text.translatable(if (e.source == "party") "cobblemarket.sell.party" else "cobblemarket.sell.pc").string
            val tc = typeColor(if (e.primaryType.isNotEmpty()) e.primaryType else "cobblemon.type.normal")
            val srcColor = if (e.source == "party") 0x55FF55 else 0x55AAFF
            var sx = lx + 28
            context.drawText(textRenderer, src, sx, y + 7, srcColor, false)
            sx += textRenderer.getWidth(src) + 4

            // Ball icon（球种统一在精灵名称左侧）
            if (e.ball.isNotEmpty()) {
                val ballId = Identifier.tryParse(e.ball.removePrefix("item.").replaceFirst(".", ":"))
                if (ballId != null) {
                    val bi = Registries.ITEM.get(ballId)
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = ItemStack(bi), x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
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

            // Held item icon（紧跟性别）
            if (e.heldItemId.isNotEmpty()) {
                Identifier.tryParse(e.heldItemId)?.let { heldId ->
                    val heldItem = Registries.ITEM.get(heldId)
                    if (heldItem != Registries.ITEM.get(Identifier.of("minecraft", "air"))) {
                        com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                            itemStack = ItemStack(heldItem), x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                        sx += 12
                    }
                }
            }

            // Level
            context.drawText(textRenderer, "Lv.${e.level}", lx + 135, y + 7, 0xAAAAAA, false)
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
    }

    private fun renderPokemonIcon(context: DrawContext, origIdx: Int, x: Int, y: Int, size: Int) {
        val data = iconData[origIdx] ?: return run {
            val tc = 0x88888888.toInt()
            context.fill(x, y, x + size, y + size, tc)
        }
        val matrices = context.matrices
        matrices.push()
        try {
            context.enableScissor(x - 1, y + 1, x + size + 2, y + size + 2)
            matrices.translate(x + size / 2.0, y + 1.0, 0.0)
            matrices.scale(size / 25f * 2.5f, size / 25f * 2.5f, 1f)
            drawProfilePokemon(
                renderablePokemon = data.renderable, matrixStack = matrices,
                rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                state = data.state, partialTicks = 0f, scale = 4.5f
            )
        } catch (_: Exception) {
        } finally {
            context.disableScissor()
            matrices.pop()
        }
    }

    private fun renderTooltip(context: DrawContext, p: PokemonPreview, mx: Int, my: Int, bgState: Int) {
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

        val lines = mutableListOf<Pair<Text, Int>>()
        lines.add(EntryBadgeRenderer.nameWithShinyStar(speciesDisplay(p), p.shiny)
            .copy().append(Text.literal("  Lv.${p.level}")) to 0xFFFFFF)
        lines.add(Text.literal("${Text.translatable("cobblemarket.gui.tooltip_type").string}$typeText") to 0xFFFFFF)
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
        lines.add(Text.literal("  $hp:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsHp, p.htHp)}") to 0x66FF66); lines.add(Text.literal("  $atk:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsAtk, p.htAtk)}") to 0xFF6666)
        lines.add(Text.literal("  $def:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsDef, p.htDef)}") to 0xFFCC66); lines.add(Text.literal("  $spa:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpAtk, p.htSpAtk)}") to 0x6699FF)
        lines.add(Text.literal("  $spd:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpDef, p.htSpDef)}") to 0x66FF99); lines.add(Text.literal("  $spe:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpd, p.htSpd)}") to 0xFF99FF)

        var mw = 0; lines.forEach { mw = maxOf(mw, textRenderer.getWidth(it.first)) }
        if (heldItemLine >= 0) {
            mw = maxOf(mw, textRenderer.getWidth(lines[heldItemLine].first) + 14)
        }
        val pad = 4
        val tx = minOf(mx + 12, width - mw - 12)
        val th = lines.size * 10 + pad
        val ty = if (my - th - 4 <= 0) minOf(my + 12, height - th) else my - th - 4

        context.matrices.push(); context.matrices.translate(0.0, 0.0, 400.0)
        drawNineSlice(context, ROW_BACKGROUND_TEXTURE, tx - pad, ty - pad, mw + 2 * pad, lines.size * 10 + 2 * pad, bgState, ROW_BACKGROUND_TEX_H)
        lines.forEachIndexed { i, (line, color) ->
            if (i == heldItemLine) {
                context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
                Identifier.tryParse(p.heldItemId)?.let { heldId ->
                    com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                        itemStack = ItemStack(Registries.ITEM.get(heldId)),
                        x = tx + textRenderer.getWidth(line) + 2.0,
                        y = ty + i * 10 + 0.0,
                        scale = 0.6,
                        matrixStack = context.matrices
                    )
                }
            } else if (i == 0) {
                // 第一行（名字★Lv）带公母图标
                EntryBadgeRenderer.drawNameLineLeft(context, line, p.gender, tx, ty + i * 10, color)
            } else {
                context.drawTextWithShadow(textRenderer, line, tx, ty + i * 10, color)
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
            ClientPlayNetworking.send(RequestMyPokemonPayload(0, requestId))
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
    }
}
