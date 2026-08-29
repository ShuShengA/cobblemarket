package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.playFailSound

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.shusheng.cobblemarket.network.CreateItemAuctionPayload
import com.shusheng.cobblemarket.network.CreatePokemonAuctionPayload
import com.shusheng.cobblemarket.network.MarketResultPayload
import com.shusheng.cobblemarket.network.MyPokemonListPayload
import com.shusheng.cobblemarket.network.PokemonPreview
import com.shusheng.cobblemarket.network.RequestBalancePayload
import com.shusheng.cobblemarket.network.RequestMyPokemonPayload
import com.shusheng.cobblemarket.platform.sendToServer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.joml.Quaternionf

/**
 * 上架拍卖：精灵 / 物品 双 tab。
 * 精灵列表照搬 SellSelectScreen（服务端分页拉取），物品列表照搬 ItemSellScreen（本地背包扫描）。
 * 点击行打开拍卖设置弹窗（起拍价 / 加价幅度（可空=默认）/ 时长档位）。
 */
class AuctionCreateScreen(private val initialTab: Int = 0) : Screen(Text.translatable("cobblemarket.auction.create_title")) {

    private val panelWidth = 296
    private val rowHeight = 24

    // 时长档位（分钟制）：进入界面时向服务端请求真实配置，收到前用默认值占位
    private var durationOptions = listOf(720, 1440, 2880, 4320)

    private var currentTab = initialTab.coerceIn(0, 1) // 0 = 精灵, 1 = 物品
    private var backButton: NineSliceButton? = null
    private val tabButtons = mutableListOf<NineSliceButton>()
    private var shinyButton: NineSliceButton? = null
    private var typeButton: NineSliceButton? = null
    private var resetButton: NineSliceButton? = null

    // ── 精灵 tab（照搬 SellSelectScreen） ──
    private var pokemonList = listOf<PokemonPreview>()
    private var loaded = false
    private var loadedAll = false
    private var closed = false
    // 防串扰：秒关再开界面时，旧界面的迟到响应会被 requestId 比对丢弃（独立计数器）
    private val requestId = auctionRequestId++
    private var scrollOffset = 0
    private var hoveredRow = -1
    private var searchField: TextFieldWidget? = null
    private var shinyOnly = false
    private var typeFilter = ""
    private var typeIdx = 0
    private val ivExact = IntArray(6) { -1 }
    // IV 比较方式：0 = 等于（默认），1 = 大于等于，2 = 小于等于
    private val ivOps = IntArray(6)
    private val ivOpButtons = mutableMapOf<Int, NineSliceButton>()
    // 特训筛选三态：0 = 不限，1 = 仅含训练，2 = 仅不含训练
    private var htFilter = 0
    private var htButton: NineSliceButton? = null
    private var hpF: TextFieldWidget? = null; private var atkF: TextFieldWidget? = null; private var defF: TextFieldWidget? = null
    private var spaF: TextFieldWidget? = null; private var spdF: TextFieldWidget? = null; private var speF: TextFieldWidget? = null

    private val allTypes = listOf("" to "") + listOf("normal","fire","water","electric","grass","ice","fighting","poison","ground","flying",
        "psychic","bug","rock","ghost","dragon","dark","steel","fairy").map { it to "cobblemon.type.$it" }

    private fun cycleType() {
        typeIdx = (typeIdx + 1) % allTypes.size
        typeFilter = allTypes[typeIdx].second
    }

    private data class IconData(
        val renderable: RenderablePokemon,
        val state: FloatingState,
        // 球种/携带物栈随图标缓存：render 每帧 new ItemStack 是分配热点，构建一次复用
        val ballStack: ItemStack?,
        val heldStack: ItemStack?
    )
    private val iconData = mutableMapOf<Int, IconData>()

    private val speciesNameCache = mutableMapOf<String, String>()
    private fun speciesDisplay(p: PokemonPreview): String =
        speciesNameCache.getOrPut(p.species) {
            val t = Text.translatable(p.species).string
            if (t == p.species) p.speciesName else t
        }

    private fun buildIconCache(startIdx: Int = 0) {
        if (startIdx == 0) iconData.clear()
        for (i in startIdx until pokemonList.size) {
            val p = pokemonList[i]
            val id = Identifier.tryParse(p.speciesId) ?: continue
            val species = PokemonSpecies.getByIdentifier(id) ?: continue
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

    // ── 物品 tab（照搬 ItemSellScreen 本地背包扫描） ──
    private data class SellItem(val stack: ItemStack, val count: Int)
    private var items = listOf<SellItem>()

    private fun loadInventory() {
        val player = client?.player ?: return
        val list = mutableListOf<SellItem>()
        val main = player.inventory.main
        for (i in 0 until main.size) {
            val stack = main[i]
            if (!stack.isEmpty) {
                val idx = list.indexOfFirst { ItemStack.areItemsAndComponentsEqual(it.stack, stack) }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(count = list[idx].count + stack.count)
                } else {
                    list.add(SellItem(stack.copy(), stack.count))
                }
            }
        }
        items = list
    }

    private fun itemDisplay(stack: ItemStack): String {
        val id = Registries.ITEM.getId(stack.item)
        val name = stack.name.string
        return if (name == stack.item.translationKey) id.path else name
    }

    // ── 拍卖设置弹窗 ──
    private var dialogPokemon: PokemonPreview? = null
    private var dialogItem: SellItem? = null
    private var startingField: TextFieldWidget? = null
    private var incrementField: TextFieldWidget? = null
    private var countField: TextFieldWidget? = null
    private var durationIndex = 0
    private var durationButton: NineSliceButton? = null
    private var confirmButton: NineSliceButton? = null
    private var cancelButton: NineSliceButton? = null
    private var dialogRenderable: RenderablePokemon? = null
    private val dialogPreviewState = FloatingState()

    private fun getListStartY() = if (currentTab == 0) 114 else 54
    private fun getMaxVisibleRows() = maxOf(0, (height - getListStartY() - 48) / rowHeight)

    override fun init() {
        super.init()
        sendToServer(RequestBalancePayload())
        // 请求服务端真实时长档位（配置可自定义，按钮显示跟随）
        sendToServer(com.shusheng.cobblemarket.network.RequestAuctionDurationsPayload())
        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        val backBtn = NineSliceButton(
            leftX + panelWidth - 50, 13, 50, 16,
            Text.translatable("cobblemarket.gui.back"),
            { client?.setScreen(AuctionScreen(currentTab)) }
        )
        backButton = backBtn
        addDrawableChild(backBtn)

        // tab 按钮
        val tabW = 62
        val tabStart = centerX - (tabW * 2 + 2) / 2
        tabButtons.clear()
        listOf("cobblemarket.auction.tab_pokemon", "cobblemarket.auction.tab_item").forEachIndexed { i, _ ->
            val btn = NineSliceButton(tabStart + i * (tabW + 2), 32, tabW, 14, Text.literal(""), { switchTab(i) })
            tabButtons.add(btn)
            addDrawableChild(btn)
        }
        updateTabButtons()

        if (currentTab == 0) {
            // Row 1: Search (full width)
            searchField = TextFieldWidget(textRenderer, leftX + 2, 50, panelWidth - 4, 16, Text.translatable("cobblemarket.sell.search"))
            searchField?.setPlaceholder(Text.translatable("cobblemarket.sell.search"))
            // 搜索变化时重建过滤缓存（render 每帧只读，见 rebuildFiltered 注释）
            searchField?.setChangedListener { rebuildFiltered() }
            addSelectableChild(searchField)
            addDrawableChild(searchField)

            // Row 2 (y=70): IV fields（照搬 SellSelectScreen）
            fun mkIv(x: Int, ph: String): TextFieldWidget {
                val f = TextFieldWidget(textRenderer, x, 70, 28, 16, Text.literal(""))
                f.setPlaceholder(Text.literal(ph))
                f.setTextPredicate { it.length <= 2 && it.all { c -> c.isDigit() } }
                addSelectableChild(f); addDrawableChild(f)
                return f
            }
            hpF = mkIv(leftX + 2, "HP"); atkF = mkIv(leftX + 49, "ATK"); defF = mkIv(leftX + 96, "DEF")
            spaF = mkIv(leftX + 143, "SpA"); spdF = mkIv(leftX + 190, "SpD"); speF = mkIv(leftX + 237, "Spd")

            // 三态比较按钮（= / ≥ / ≤ 循环，默认 = 与旧行为一致；非默认金色高亮；贴自己框成组）
            ivOpButtons.clear()
            for (i in 0..5) {
                val bx = leftX + 2 + i * 47 + 29
                val btn = NineSliceButton(
                    bx, 70, 14, 16,
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

            // Row 3 (y=90): 闪光 + 类型筛选（照搬 SellSelectScreen，无价格/卖出按钮）
            val btnY = 90
            shinyButton = NineSliceButton(
                leftX + 2, btnY, 62, 20,
                Text.translatable(if (shinyOnly) "cobblemarket.gui.shiny_on" else "cobblemarket.gui.shiny_off"),
                { shinyOnly = !shinyOnly; rebuild() },
                // 开 = 金色 ★，关 = 白色 ☆（与其他界面闪光按钮一致）
                if (shinyOnly) GOLD_COLOR else 0xFFFFFF
            )
            addDrawableChild(shinyButton)
            typeButton = NineSliceButton(
                leftX + 66, btnY, 58, 20,
                if (typeFilter.isEmpty()) Text.translatable("cobblemarket.sell.type") else Text.translatable(typeFilter),
                { cycleType(); rebuild() }
            )
            addDrawableChild(typeButton)
            // 特训筛选（三态循环）
            htButton = NineSliceButton(
                leftX + 128, btnY, 62, 20,
                htButtonText(),
                { toggleHtFilter(); rebuild() },
                if (htFilter != 0) GOLD_COLOR else 0xFFFFFF
            )
            addDrawableChild(htButton)
            resetButton = NineSliceButton(
                leftX + 244, btnY, 48, 20,
                Text.translatable("cobblemarket.gui.reset"),
                { resetFilters() }
            )
            addDrawableChild(resetButton)

            if (!loaded) {
                sendToServer(RequestMyPokemonPayload(0, requestId))
                loaded = true
            }
            rebuildFiltered()
        } else {
            loadInventory()
        }
    }

    private fun updateTabButtons() {
        val keys = listOf("cobblemarket.auction.tab_pokemon", "cobblemarket.auction.tab_item")
        tabButtons.forEachIndexed { i, btn ->
            val label = Text.translatable(keys[i]).string
            btn.setMessage(if (currentTab == i) com.shusheng.cobblemarket.util.TextUtil.selectedText(label) else Text.literal(label))
        }
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        scrollOffset = 0
        hoveredRow = -1
        clearChildren()
        init()
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

    private fun rebuild(keepInputs: Boolean = true) {
        // 闪光/属性/特训/tab 切换的重建会清空搜索词与 IV 输入：默认保存恢复；
        // 重置按钮走 rebuild(keepInputs = false) 显式清空
        val savedSearch = searchField?.text ?: ""
        val savedIv = arrayOf(
            hpF?.text ?: "", atkF?.text ?: "", defF?.text ?: "",
            spaF?.text ?: "", spdF?.text ?: "", speF?.text ?: ""
        )
        clearChildren()
        init()
        if (keepInputs) {
            searchField?.text = savedSearch
            val fields = arrayOf(hpF, atkF, defF, spaF, spdF, speF)
            savedIv.forEachIndexed { i, t -> fields[i]?.text = t }
        }
    }

    // 重置全部筛选条件（输入框由 rebuild(keepInputs=false) 显式清空）
    private fun resetFilters() {
        shinyOnly = false
        typeFilter = ""
        typeIdx = 0
        ivExact.fill(-1)
        for (i in 0..5) ivOps[i] = 0
        htFilter = 0
        scrollOffset = 0
        rebuild(keepInputs = false)
    }

    fun onPokemonList(payload: MyPokemonListPayload) {
        if (closed || payload.requestId != requestId) return
        if (payload.page == 0) {
            pokemonList = payload.pokemon
            buildIconCache()
        } else {
            val startIdx = pokemonList.size
            pokemonList = pokemonList + payload.pokemon
            buildIconCache(startIdx)
        }
        rebuildFiltered()
        if (payload.hasMore) {
            sendToServer(RequestMyPokemonPayload(payload.page + 1, requestId))
        } else {
            loadedAll = true
        }
    }

    fun onMarketResult(payload: MarketResultPayload) {
        createInFlight = false
        if (payload.success) {
            // 上架成功：回到拍卖场并保持当前 tab（物品上架留在物品 tab）
            client?.setScreen(AuctionScreen(currentTab))
        } else {
            // 创建失败（价格限制/黑名单/数量上限等）：聊天框红字提示，弹窗保留供改价重试
            client?.player?.sendMessage(payload.message.copy().formatted(Formatting.RED), false)
        }
    }

    // ── 拍卖设置弹窗 ──

    private fun openDialog(pokemon: PokemonPreview?, item: SellItem?) {
        dialogPokemon = pokemon
        dialogItem = item
        dialogRenderable = null
        backButton?.visible = false
        tabButtons.forEach { it.visible = false }
        searchField?.visible = false
        hpF?.visible = false; atkF?.visible = false; defF?.visible = false
        spaF?.visible = false; spdF?.visible = false; speF?.visible = false
        ivOpButtons.values.forEach { it.visible = false }
        shinyButton?.visible = false
        typeButton?.visible = false
        htButton?.visible = false
        resetButton?.visible = false
        val centerX = width / 2
        val dialogY = height / 2 - (if (item != null) 180 else 150) / 2

        if (pokemon != null) {
            val id = Identifier.tryParse(pokemon.speciesId)
            val species = id?.let { PokemonSpecies.getByIdentifier(it) }
            if (species != null) {
                val aspects = pokemon.aspects.toMutableSet()
                if (pokemon.shiny && "shiny" !in aspects) aspects.add("shiny")
                dialogRenderable = RenderablePokemon(species, aspects, ItemStack.EMPTY)
            }
        }

        addDrawable(object : Drawable {
            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                renderDialogBackground(context)
            }
        })

        startingField = TextFieldWidget(textRenderer, centerX - 20, dialogY + 52, 100, 16, Text.literal(""))
        startingField?.setPlaceholder(Text.translatable("cobblemarket.auction.starting_placeholder").formatted(Formatting.GRAY))
        startingField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addDrawableChild(startingField)

        incrementField = TextFieldWidget(textRenderer, centerX - 20, dialogY + 72, 100, 16, Text.literal(""))
        incrementField?.setPlaceholder(Text.translatable("cobblemarket.auction.increment_placeholder").formatted(Formatting.GRAY))
        incrementField?.setTextPredicate { it.length <= 9 && it.all { c -> c.isDigit() } }
        addDrawableChild(incrementField)

        durationButton = NineSliceButton(centerX - 20, dialogY + 92, 100, 14, Text.literal(""), { cycleDuration() })
        addDrawableChild(durationButton)
        updateDurationButton()

        if (item != null) {
            countField = TextFieldWidget(textRenderer, centerX - 20, dialogY + 112, 100, 16, Text.literal(""))
            countField?.setPlaceholder(Text.translatable("cobblemarket.auction.count_placeholder", item.count).formatted(Formatting.GRAY))
            countField?.setTextPredicate { it.length <= 4 && it.all { c -> c.isDigit() } }
            addDrawableChild(countField)
        }

        val btnY = if (item != null) dialogY + 134 else dialogY + 112
        confirmButton = NineSliceButton(
            centerX - 85, btnY, 80, 20,
            Text.translatable("cobblemarket.auction.create"),
            { confirmCreate() }
        )
        addDrawableChild(confirmButton)
        cancelButton = NineSliceButton(
            centerX + 5, btnY, 80, 20,
            Text.translatable("cobblemarket.buy_confirm.cancel"),
            { closeDialog() }
        )
        addDrawableChild(cancelButton)
    }

    // 弹窗打开时：预览精灵画在起拍价输入框上方，以输入框水平居中
    private fun renderPreviewAboveField(context: DrawContext, delta: Float) {
        val slotSize = 28
        val dialogY = height / 2 - (if (dialogItem != null) 180 else 150) / 2
        val fieldX = width / 2 - 20
        val fieldW = 100
        val slotX = fieldX + (fieldW - slotSize) / 2
        val slotY = dialogY + 52 - slotSize - 4
        val slotTexture = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
        context.matrices.push()
        context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
        context.matrices.scale(slotSize / 66f, slotSize / 66f, 1f)
        context.drawTexture(slotTexture, 0, 0, 0f, 0f, 66, 66, 66, 66)
        context.matrices.pop()
        val rp = dialogRenderable ?: return
        val matrices = context.matrices
        matrices.push()
        try {
            context.enableScissor(slotX - 1, slotY + 1, slotX + slotSize + 2, slotY + slotSize + 2)
            matrices.translate(slotX + slotSize / 2.0, slotY + 1.0, 0.0)
            matrices.scale(slotSize / 25f * 2.5f, slotSize / 25f * 2.5f, 1f)
            // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
            val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
            drawProfilePokemon(
                renderablePokemon = rp,
                matrixStack = matrices,
                rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                state = dialogPreviewState,
                partialTicks = if (useFloat) delta else 0f,
                scale = 4.5f
            )
        } catch (_: Exception) {
        } finally {
            context.disableScissor()
            matrices.pop()
        }
    }

    private fun renderDialogBackground(context: DrawContext) {
        val centerX = width / 2
        val dialogH = if (dialogItem != null) 180 else 150
        val dialogW = 220
        val dialogX = centerX - dialogW / 2
        val dialogY = height / 2 - dialogH / 2

        drawScreenDimMask(context, width, height)
        drawNineSlice(context, DIALOG_BACKGROUND_TEXTURE, dialogX, dialogY, dialogW, dialogH, 0, DIALOG_BACKGROUND_TEX_H)
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.auction.create_dialog_title").formatted(Formatting.GOLD),
            centerX, dialogY + 14, 0xFFFFFF)

        // 预览槽
        val slotSize = 28
        val slotX = centerX + 66
        val slotY = dialogY + 24
        if (dialogItem != null) {
            context.drawItem(dialogItem!!.stack, slotX + 6, slotY + 6)
        }

        // 字段标签
        val labelX = dialogX + 10
        var ly = dialogY + 56
        fun label(text: Text) {
            context.drawTextWithShadow(textRenderer, text.string, labelX, ly, 0xAAAAAA)
            ly += 20
        }
        label(Text.translatable("cobblemarket.auction.starting_price"))
        label(Text.translatable("cobblemarket.auction.min_increment"))
        ly -= 2
        label(Text.translatable("cobblemarket.auction.duration"))
        if (dialogItem != null) {
            label(Text.translatable("cobblemarket.auction.count"))
            // 物品拍卖：起拍价为整组总价提示
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.auction.price_total_hint").formatted(Formatting.GRAY),
                centerX, dialogY + 158, 0xFFFFFF)
        }
    }

    private fun formatDuration(minutes: Int): String =
        if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"

    /** 服务端真实时长档位到达：更新选项与按钮显示（服务器自定义配置时按钮不再失真） */
    fun onDurations(payload: com.shusheng.cobblemarket.network.AuctionDurationsPayload) {
        if (closed) return
        if (payload.durations.isEmpty()) return
        durationOptions = payload.durations
        durationIndex = durationIndex.coerceIn(0, durationOptions.size - 1)
        updateDurationButton()
    }

    private fun updateDurationButton() {
        durationButton?.setMessage(Text.literal(
            "${Text.translatable("cobblemarket.auction.duration").string}: ${formatDuration(durationOptions[durationIndex])}"
        ))
    }

    private fun cycleDuration() {
        durationIndex = (durationIndex + 1) % durationOptions.size
        updateDurationButton()
    }

    /** 创建请求在途标志：连点防重（onMarketResult 响应时复位） */
    private var createInFlight = false

    private fun confirmCreate() {
        // 防重：请求在途时忽略连点（服务端有 UUID 校验+节流兜底，这里防误发与响应混杂）
        if (createInFlight) return
        val starting = startingField?.text?.toIntOrNull() ?: run {
            playFailSound()
            return
        }
        if (starting <= 0) { playFailSound(); return }
        val increment = incrementField?.text?.toIntOrNull() ?: 0 // 0 = 用服务器默认
        if (increment < 0) { playFailSound(); return }
        val durationIndexToSend = durationIndex
        val pokemon = dialogPokemon
        val item = dialogItem
        if (pokemon != null) {
            createInFlight = true
            sendToServer(CreatePokemonAuctionPayload(pokemon.uuid, starting, increment, durationIndexToSend))
        } else if (item != null) {
            val count = countField?.text?.toIntOrNull() ?: run {
                playFailSound()
                return
            }
            if (count <= 0 || count > item.count) { playFailSound(); return }
            val registry = client?.world?.registryManager ?: run {
                playFailSound()
                return
            }
            val itemId = Registries.ITEM.getId(item.stack.item).toString()
            val itemNbt = item.stack.encode(registry, NbtCompound()) as? NbtCompound ?: NbtCompound()
            createInFlight = true
            sendToServer(CreateItemAuctionPayload(itemId, itemNbt, count, starting, increment, durationIndexToSend))
        }
        // 不立即关闭：等 onMarketResult 响应（成功跳转拍卖场，失败保留弹窗供改价重试）
    }

    private fun closeDialog() {
        dialogPokemon = null
        dialogItem = null
        startingField = null
        incrementField = null
        countField = null
        durationIndex = 0
        durationButton = null
        confirmButton = null
        cancelButton = null
        dialogRenderable = null
        clearChildren()
        init()
    }

    // ── 列表渲染 ──

    // 过滤结果缓存（照黑名单界面的 rebuildFiltered 模式）：数据/搜索/IV/筛选变化时重建，
    // render/renderBackground 每帧只读。indexedFilteredCache 同缓存带原始下标，省去行循环里每帧 indexOf。
    private var filteredCache = listOf<PokemonPreview>()
    private var indexedFilteredCache = listOf<IndexedValue<PokemonPreview>>()

    private fun filteredPokemon(): List<PokemonPreview> = filteredCache

    private fun rebuildFiltered() {
        syncIvFields()
        // 先 withIndex 再过滤：IndexedValue.index 保持 pokemonList 原始下标，
        // 供图标缓存 iconData（按原列表索引构建）正确取值（filteredCache.withIndex() 的序号是过滤后位置，搜索后首行图标会错位）
        indexedFilteredCache = pokemonList.withIndex().filter { (_, p) ->
            val q = searchField?.text?.trim()?.takeIf { it.isNotEmpty() }
            (q == null || speciesDisplay(p).contains(q, ignoreCase = true) || p.speciesName.contains(q, ignoreCase = true)) &&
            (!shinyOnly || p.shiny) &&
            (typeFilter.isEmpty() || p.primaryType == typeFilter || p.secondaryType == typeFilter) &&
            // IV 按有效值匹配：特训项用特训值，未特训用真实值（原生 31 与训练 31 都命中）
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

    private fun typeColor(tk: String) = when (tk.substringAfterLast(".").lowercase()) {
        "normal" -> 0xAAAA99; "fire" -> 0xFF4422; "water" -> 0x3399FF
        "electric" -> 0xFFCC33; "grass" -> 0x77CC55; "ice" -> 0x66CCFF
        "fighting" -> 0xBB5544; "poison" -> 0xAA5599; "ground" -> 0xDDBB55
        "flying" -> 0x8899FF; "psychic" -> 0xFF5599; "bug" -> 0xAABB22
        "rock" -> 0xBBAA66; "ghost" -> 0x6666BB; "dragon" -> 0x7766EE
        "dark" -> 0x775544; "steel" -> 0xAAAABB; "fairy" -> 0xFFAAFF
        else -> 0xFFFFFF
    }

    private fun displayCount(): Int = if (currentTab == 0) filteredPokemon().size else items.size

    private fun renderPokemonIcon(context: DrawContext, index: Int, x: Int, y: Int, size: Int, dark: Boolean = false, delta: Float = 0f) {
        val data = iconData[index] ?: return run {
            val tc = 0x88888888.toInt()
            context.fill(x, y, x + size, y + size, tc)
        }
        val matrices = context.matrices
        matrices.push()
        try {
            context.enableScissor(x - 1, y + 1, x + size + 2, y + size + 2)
            matrices.translate(x + size / 2.0, y + 1.0, 0.0)
            matrices.scale(size / 25f * 2.5f, size / 25f * 2.5f, 1f)
            // 动态模式：drawProfilePokemon 内部自会推进 FloatingState（与队伍界面同款），这里只控制是否传 delta；静态保持 0
            val useFloat = com.shusheng.cobblemarket.client.ClientConfig.iconAnimMode ==
                com.shusheng.cobblemarket.client.IconAnimMode.FLOAT
            drawProfilePokemon(
                renderablePokemon = data.renderable,
                matrixStack = matrices,
                rotation = Quaternionf().rotateXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                state = data.state,
                partialTicks = if (useFloat) delta else 0f,
                scale = 4.5f,
                // 弹窗打开时压暗（模型走独立渲染层，遮罩盖不住；颜色系数模拟遮罩效果）
                r = if (dark) 0.35f else 1f,
                g = if (dark) 0.35f else 1f,
                b = if (dark) 0.35f else 1f
            )
        } catch (_: Exception) {
        } finally {
            context.disableScissor()
            matrices.pop()
        }
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val panelLeft = width / 2 - 160
        val panelTop = 2
        val panelBottom = height - 32
        val sliceH = 16

        val top = Identifier.of("cobblemarket", "textures/gui/market_panel_auction_house_top.png")
        val mid = Identifier.of("cobblemarket", "textures/gui/market_panel_middle.png")
        val bot = Identifier.of("cobblemarket", "textures/gui/market_panel_bottom.png")

        drawPanelSlice(context, top, panelLeft, panelTop)
        var y = panelTop + sliceH
        while (y < panelBottom - sliceH) {
            drawPanelSlice(context, mid, panelLeft, y, minOf(sliceH, panelBottom - sliceH - y))
            y += sliceH
        }
        drawPanelSlice(context, bot, panelLeft, panelBottom - sliceH)

        // IV 输入变化时重建过滤缓存（原 filteredPokemon 每次调用都先 syncIvFields，缓存后在此统一检测；
        // renderBackground 每帧先于 render 运行，检测一次即可覆盖本帧全部读缓存点）
        if (syncIvFields()) rebuildFiltered()

        val leftX = width / 2 - panelWidth / 2
        val startY = getListStartY()
        val visibleRows = getMaxVisibleRows()
        val listAreaBottom = startY + visibleRows * rowHeight
        val count = displayCount()

        // 弹窗打开时不再绘制行背景/悬停行（下层列表对弹窗不可见）
        if (dialogPokemon == null && dialogItem == null) {
            hoveredRow = -1
            if (mouseX in leftX..(leftX + panelWidth) && mouseY in startY..listAreaBottom) {
                val row = (mouseY - startY) / rowHeight
                val shownCount = minOf(visibleRows, count - scrollOffset)
                if (row in 0 until shownCount) hoveredRow = row
            }

            repeat(minOf(visibleRows, maxOf(0, count - scrollOffset))) { di ->
                val rowY = startY + di * rowHeight
                val rowState = if (di == hoveredRow) 1 else 0
                drawNineSlice(context, ROW_BACKGROUND_TEXTURE, leftX, rowY, panelWidth, rowHeight, rowState, ROW_BACKGROUND_TEX_H)
            }
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        if (dialogPokemon != null || dialogItem != null) {
            // 弹窗打开时：精灵预览显示在起拍价输入框上方（以输入框居中）
            if (dialogPokemon != null && dialogRenderable != null) {
                renderPreviewAboveField(context, delta)
            }
            return
        }

        val centerX = width / 2
        val leftX = centerX - panelWidth / 2

        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("cobblemarket.auction.create_title").formatted(Formatting.GOLD),
            centerX, 20, 0xFFFFFF)

        // 余额（左上角，来自全局缓存）
        val balText = com.shusheng.cobblemarket.client.BalanceCache.balance
        if (balText.isNotEmpty()) {
            context.drawTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.gui.balance",
                    Text.literal(balText + " " + com.shusheng.cobblemarket.client.inlineCurrencyUnit()).formatted(Formatting.GOLD)),
                leftX + 4, 20, 0xFFFFFF)
        }

        val startY = getListStartY()
        context.fill(leftX, startY - 2, leftX + panelWidth, startY - 1, 0xFF555555.toInt())

        if (currentTab == 0) {
            if (!loaded) {
                context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.sell.loading").formatted(Formatting.GRAY),
                    centerX, startY + 50, 0xFFFFFF)
            } else if (displayCount() == 0) {
                context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("cobblemarket.auction.create_empty").formatted(Formatting.GRAY),
                    centerX, startY + 50, 0xFFFFFF)
            }
        } else if (displayCount() == 0) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.auction.create_empty").formatted(Formatting.GRAY),
                centerX, startY + 50, 0xFFFFFF)
        }

        // Tooltip on hover（照搬 SellSelectScreen）
        if (currentTab == 0 && hoveredRow >= 0) {
            val filtered = filteredPokemon()
            val absIdx = scrollOffset + hoveredRow
            if (absIdx in filtered.indices) {
                renderTooltip(context, filtered[absIdx], mouseX, mouseY, 1)
            }
        }

        if (currentTab == 0) {
            val displayList = indexedFilteredCache
            displayList.drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, (origIndex, p) ->
                val y = startY + i * rowHeight
                // 槽背景（GUI 层，弹窗遮罩自动压暗，照常渲染）；3D 精灵弹窗打开时颜色压暗
                val slotX = leftX + 2
                val slotY = y + 2
                context.matrices.push()
                context.matrices.translate(slotX.toDouble(), slotY.toDouble(), 0.0)
                context.matrices.scale(20f / 66f, 20f / 66f, 1f)
                context.drawTexture(POKEMON_SLOT_TEXTURE, 0, 0, 0f, 0f, 66, 66, 66, 66)
                context.matrices.pop()
                // 预览弹窗打开时不渲染 3D（模型层在衬底之上，压暗仍会浮在弹窗上）
                if (dialogPokemon == null && dialogItem == null) renderPokemonIcon(context, origIndex, slotX, slotY, 20, delta = delta)

                // 来源（[队]/[PC] 固定色，精灵名属性色 + 金色闪光星标，照搬 SellSelectScreen）
                val src = Text.translatable(if (p.source == "party") "cobblemarket.sell.party" else "cobblemarket.sell.pc").string
                val tc = typeColor(if (p.primaryType.isNotEmpty()) p.primaryType else "cobblemon.type.normal")
                val srcColor = if (p.source == "party") 0x55FF55 else 0x55AAFF
                var sx = leftX + 28
                context.drawText(textRenderer, src, sx, y + 7, srcColor, false)
                sx += textRenderer.getWidth(src) + 4

                // Ball icon（球种统一在精灵名称左侧；弹窗打开时不渲染；ball 非空就占位 12px，与原来一致）
                if (dialogPokemon == null && dialogItem == null && p.ball.isNotEmpty()) {
                    iconData[origIndex]?.ballStack?.let { ballStack ->
                        com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                            itemStack = ballStack, x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                    }
                    sx += 12
                }

                val name = speciesDisplay(p)
                context.drawTextWithShadow(textRenderer, name, sx, y + 7, tc)
                sx += textRenderer.getWidth(name)
                if (p.shiny) {
                    context.drawText(textRenderer, "★", sx + 2, y + 7, GOLD_COLOR, false)
                    sx += 2 + textRenderer.getWidth("★")
                }
                sx += 2

                // Gender icon（紧跟名字）
                if (p.gender == "MALE" || p.gender == "FEMALE") {
                    val gi = if (p.gender == "MALE") GENDER_ICON_MALE else GENDER_ICON_FEMALE
                    com.cobblemon.mod.common.api.gui.blitk(matrixStack = context.matrices, texture = gi, x = sx, y = y + 7, width = 6, height = 8)
                    sx += 8
                }

                // Held item icon（紧跟性别；弹窗打开时不渲染；空气/空 id 已过滤为 null）
                if (dialogPokemon == null && dialogItem == null) {
                    iconData[origIndex]?.heldStack?.let { heldStack ->
                        com.cobblemon.mod.common.client.render.renderScaledGuiItemIcon(
                            itemStack = heldStack, x = sx.toDouble(), y = y + 6.0, scale = 0.6, matrixStack = context.matrices)
                        sx += 12
                    }
                }

                // Level
                val levelText = Text.translatable("cobblemarket.gui.lv").string + p.level
                context.drawText(textRenderer, levelText, leftX + 200, y + 7, 0x000000, false)
            }
        } else {
            items.drop(scrollOffset).take(getMaxVisibleRows()).forEachIndexed { i, item ->
                val y = startY + i * rowHeight
                context.drawItem(item.stack, leftX + 4, y + 4)
                context.drawTextWithShadow(textRenderer,
                    com.shusheng.cobblemarket.util.TextUtil.truncateString("${itemDisplay(item.stack)} ×${item.count}", 200),
                    leftX + 24, y + 7, 0xFFFFFF)
            }
        }

        if (currentTab == 0 && loaded && !loadedAll) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("cobblemarket.sell.loading_more", pokemonList.size).formatted(Formatting.GRAY),
                centerX, height - 49, 0x888888)
        } else if (displayCount() > getMaxVisibleRows()) {
            context.drawCenteredTextWithShadow(textRenderer,
                "${scrollOffset + 1}-${minOf(scrollOffset + getMaxVisibleRows(), displayCount())} / ${displayCount()}",
                centerX, height - 49, 0x888888)
        }
    }

    // tooltip 内容缓存：内容只取决于条目，悬停同一行时每帧重建全部文本行是悬停掉帧主因
    private var tooltipCacheKey: java.util.UUID? = null
    private var tooltipCacheLines: List<Pair<Text, Int>> = emptyList()
    private var tooltipCacheHeldLine = -1
    private var tooltipCacheMaxWidth = 0

    // 悬停提示（照搬 SellSelectScreen）
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
            lines.add(Text.literal("  $hp:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsHp, p.htHp)}").append(Text.literal("  EV:${p.evsHp}").formatted(Formatting.RED)) to 0x66FF66); lines.add(Text.literal("  $atk:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsAtk, p.htAtk)}").append(Text.literal("  EV:${p.evsAtk}").formatted(Formatting.RED)) to 0xFF6666)
            lines.add(Text.literal("  $def:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsDef, p.htDef)}").append(Text.literal("  EV:${p.evsDef}").formatted(Formatting.RED)) to 0xFFCC66); lines.add(Text.literal("  $spa:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpAtk, p.htSpAtk)}").append(Text.literal("  EV:${p.evsSpAtk}").formatted(Formatting.RED)) to 0x6699FF)
            lines.add(Text.literal("  $spd:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpDef, p.htSpDef)}").append(Text.literal("  EV:${p.evsSpDef}").formatted(Formatting.RED)) to 0x66FF99); lines.add(Text.literal("  $spe:${com.shusheng.cobblemarket.util.TextUtil.ivText(p.ivsSpd, p.htSpd)}").append(Text.literal("  EV:${p.evsSpd}").formatted(Formatting.RED)) to 0xFF99FF)

            var mw = 0; lines.forEach { mw = maxOf(mw, textRenderer.getWidth(it.first)) }
            if (heldItemLine >= 0) {
                mw = maxOf(mw, textRenderer.getWidth(lines[heldItemLine].first) + 14)
            }
            tooltipCacheLines = lines
            tooltipCacheHeldLine = heldItemLine
            tooltipCacheMaxWidth = mw
        }
        val lines = tooltipCacheLines
        val heldItemLine = tooltipCacheHeldLine
        val mw = tooltipCacheMaxWidth
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

    private fun drawPanelSlice(context: DrawContext, texture: Identifier, x: Int, y: Int, sliceH: Int = 16) {
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(0.5f, 0.5f * sliceH / 16f, 1f)
        context.drawTexture(texture, 0, 0, 0f, 0f, 640, 32, 640, 32)
        context.matrices.pop()
    }

    // ── 交互 ──

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (dialogPokemon != null || dialogItem != null) return false
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxOf(0, displayCount() - getMaxVisibleRows()))
        return true
    }

    private fun isInputFieldFocused() = focused?.let { f ->
        f === searchField || f === hpF || f === atkF || f === defF || f === spaF || f === spdF || f === speF
    } ?: false

    private fun isMouseOverAnyInput(mouseX: Double, mouseY: Double): Boolean =
        searchField?.isMouseOver(mouseX, mouseY) == true ||
        hpF?.isMouseOver(mouseX, mouseY) == true ||
        atkF?.isMouseOver(mouseX, mouseY) == true ||
        defF?.isMouseOver(mouseX, mouseY) == true ||
        spaF?.isMouseOver(mouseX, mouseY) == true ||
        spdF?.isMouseOver(mouseX, mouseY) == true ||
        speF?.isMouseOver(mouseX, mouseY) == true

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (dialogPokemon != null || dialogItem != null) {
            return super.mouseClicked(mouseX, mouseY, button)
        }
        val wasInInput = isInputFieldFocused()
        // 点击行 → 打开拍卖设置弹窗
        val result = super.mouseClicked(mouseX, mouseY, button)
        if (wasInInput && !isMouseOverAnyInput(mouseX, mouseY)) {
            focused = null
        }
        val leftX = width / 2 - panelWidth / 2
        val startY = getListStartY()
        if (hoveredRow >= 0 &&
            mouseX in leftX.toDouble()..(leftX + panelWidth - 52).toDouble() &&
            mouseY in (startY + hoveredRow * rowHeight).toDouble()..(startY + (hoveredRow + 1) * rowHeight).toDouble()
        ) {
            val idx = scrollOffset + hoveredRow
            if (currentTab == 0) {
                val list = filteredPokemon()
                if (idx in list.indices) openDialog(list[idx], null)
            } else {
                if (idx in items.indices) openDialog(null, items[idx])
            }
        }
        return result
    }

    override fun resize(client: MinecraftClient, width: Int, height: Int) {
        val wasOpen = dialogPokemon != null || dialogItem != null
        val savedPokemon = dialogPokemon
        val savedItem = dialogItem
        val savedStarting = startingField?.text ?: ""
        val savedIncrement = incrementField?.text ?: ""
        val savedCount = countField?.text ?: ""
        val savedDuration = durationIndex
        super.resize(client, width, height)
        if (wasOpen) {
            dialogPokemon = null
            dialogItem = null
            openDialog(savedPokemon, savedItem)
            startingField?.text = savedStarting
            incrementField?.text = savedIncrement
            countField?.text = savedCount
            durationIndex = savedDuration
            updateDurationButton()
        }
    }

    override fun close() {
        closed = true
        super.close()
    }

    override fun shouldPause() = false

    companion object {
        private var auctionRequestId = 0
        val GENDER_ICON_MALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_male.png")
        val GENDER_ICON_FEMALE = Identifier.of("cobblemon", "textures/gui/pc/gender_icon_female.png")
        val POKEMON_SLOT_TEXTURE = Identifier.of("cobblemarket", "textures/gui/pokemon_slot.png")
    }
}
