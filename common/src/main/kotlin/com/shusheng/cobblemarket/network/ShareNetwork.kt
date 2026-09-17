package com.shusheng.cobblemarket.network

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.Pokemon
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.sendToPlayer
import com.shusheng.cobblemarket.util.PokemonSize
import com.shusheng.cobblemarket.util.RecordDetail
import com.shusheng.cobblemarket.util.RequestThrottle
import com.shusheng.cobblemarket.util.SpeciesText
import com.shusheng.cobblemarket.util.StatNames
import com.shusheng.cobblemarket.util.TypeTextColors
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID

// ── C2S：把自己的精灵/物品分享到全服聊天 ──

/**
 * [kind] 用 [ShareNetwork.KIND_POKEMON] / [ShareNetwork.KIND_ITEM]；
 * 精灵的 [target] = 精灵 UUID，物品的 [target] = 背包主栏槽位索引。
 *
 * **只传标识、不传内容**：服务端按标识在**自己的数据**里取实物（精灵按 UUID 查队伍/PC，
 * 物品按槽位读背包），客户端递上来的任何展示数据一律不信 —— 分享本身不动资产，
 * 但伪造的「分享」会变成全服可见的假消息，所以归属校验放在服务端做。
 */
data class SharePayload(val kind: String, val target: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<SharePayload>(CobbleMarket.id("share"))
        val CODEC: PacketCodec<PacketByteBuf, SharePayload> = PacketCodec.of(
            { p, b -> b.writeString(p.kind); b.writeString(p.target) },
            { b -> SharePayload(kind = b.readString(), target = b.readString()) }
        )
    }
}

/**
 * **精灵/物品分享**：把一只精灵或一件物品以富文本播报到全服聊天，其他玩家悬停看详情。
 *
 * 广播路径与拍卖播报（[AuctionNetwork] 的 `announceAuction`）完全一致：服务端构造 [Text] 后
 * 走原版 `sendMessage`，客户端不参与渲染 —— 所以接收侧零代码、无需新增 S2C。
 *
 * 安全口径（与其余网络处理一致）：
 * - **归属校验**：精灵必须真的在自己队伍/PC 里、物品槽位必须非空，查不到直接静默丢弃
 * - **节流**：每人 [SHARE_COOLDOWN_MS] 一次（防刷屏），超限静默丢弃
 * - **服主开关**：`shareEnabled` 关闭时整条路径不工作
 * - **非法入参不崩服**：UUID 解析失败、槽位越界、物品栈为空都走「静默忽略」而非抛异常
 */
object ShareNetwork {

    const val KIND_POKEMON = "pokemon"
    const val KIND_ITEM = "item"

    /** 分享冷却：全服播报类操作，10 秒一次既能防刷屏又不影响正常使用 */
    private const val SHARE_COOLDOWN_MS = 10_000L

    /**
     * 悬停详情里最多列几个证章名，其余折成「+N」。
     * 截图里那行图标能排 10 个（[com.shusheng.cobblemarket.screen.EntryBadgeRenderer.MARKS_PER_ROW]），
     * 但这里是**文字**：证章名连着排开会被原版 tooltip 自动折行，几十枚的精灵直接变成满屏竖条
     * （2026-09-17 用户实测踩到）——只留开头几个，其余用数量收口。
     */
    private const val MARKS_SHOWN = 3

    /**
     * 分享大图快照的存活时长与条数上限：聊天消息会一直在滚动区里，但十分钟前那条没人会再去悬停，
     * 过期的就该放掉。条数上限防刷屏刷爆内存。
     */
    private const val PREVIEW_TTL_MS = 10 * 60 * 1000L
    private const val PREVIEW_MAX = 128

    /** 一次分享的大图数据：临时 id → 快照（插入序，超上限淘汰最旧的）。只在主线程读写 */
    private class CachedPreview(val payload: ObjectPreviewPayload, val at: Long)
    private val previews = LinkedHashMap<String, CachedPreview>()

    /**
     * 给聊天悬浮大图（`ObjectPreviewNetwork` 的 `share` kind）取这次分享的快照。
     *
     * 与拍卖/求购的「按 id 现取」不同，这里取的是**广播那一刻的快照**：拍卖的拍品在服务器上一直存在，
     * 而分享的东西可能马上被交易、放生、丢弃——现取会取到「已经不是消息里那个东西」的结果，甚至取不到。
     */
    fun getPreview(id: UUID): ObjectPreviewPayload? {
        val key = id.toString()
        val entry = previews[key] ?: return null
        if (System.currentTimeMillis() - entry.at > PREVIEW_TTL_MS) {
            previews.remove(key)
            return null
        }
        return entry.payload
    }

    private fun cachePreview(shareId: UUID, payload: ObjectPreviewPayload) {
        val now = System.currentTimeMillis()
        previews[shareId.toString()] = CachedPreview(payload, now)
        // 顺带清理过期条目，再按插入序淘汰最旧的
        val iterator = previews.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.at > PREVIEW_TTL_MS) iterator.remove()
        }
        while (previews.size > PREVIEW_MAX) {
            val oldest = previews.keys.firstOrNull() ?: break
            previews.remove(oldest)
        }
    }

    /** 物品栈 → 整栈 NBT（客户端 `ItemStack.fromNbtOrEmpty` 直接解）；空栈/编码失败返回 null */
    private fun encodeStack(stack: ItemStack, registryManager: RegistryWrapper.WrapperLookup): NbtCompound? {
        if (stack.isEmpty) return null
        return try {
            stack.encode(registryManager) as? NbtCompound
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to encode shared item stack: {}", e.message)
            null
        }
    }

    fun register() {
        registerC2S(SharePayload.ID, SharePayload.CODEC) { payload, player ->
            val server = player.server
            // 整段都在主线程：冷却/开关的拒绝也要回包，而发包与广播都要求主线程
            server.execute {
                if (!CobbleMarketConfig.shareEnabled) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.share.disabled")))
                    return@execute
                }
                // 冷却**必须回包**：静默丢弃会让玩家以为分享坏了（点了没反应、聊天里也没消息）
                if (!RequestThrottle.allow(player.uuid, "share", SHARE_COOLDOWN_MS)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.share.cooldown")))
                    return@execute
                }
                // 这次分享的大图快照 id：消息里挂的识别指令与缓存共用它
                val shareId = UUID.randomUUID()
                val message = when (payload.kind) {
                    KIND_POKEMON -> pokemonShareMessage(player, shareId, payload.target)
                    KIND_ITEM -> itemShareMessage(player, shareId, payload.target)
                    // 非法 kind 只可能是改过的客户端：静默丢弃，与存档读取的容错语义相反
                    else -> null
                }
                if (message == null) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.network.not_found")))
                    return@execute
                }
                server.playerManager.playerList.forEach { it.sendMessage(message) }
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.share.success")))
            }
        }
    }

    /** 精灵分享：按 UUID 在自己的队伍 + PC 里查（查不到 = 伪造，丢弃） */
    private fun pokemonShareMessage(player: ServerPlayerEntity, shareId: UUID, rawUuid: String): Text? {
        val uuid = runCatching { UUID.fromString(rawUuid) }.getOrNull() ?: return null
        val pokemon = Cobblemon.storage.getParty(player).find { it.uuid == uuid }
            ?: Cobblemon.storage.getPC(player).find { it.uuid == uuid }
            ?: return null
        // 大图快照：形态取真实 aspects（闪光补 "shiny"，缺了会把闪光画成普通色，与各界面同口径）；
        // 携带物设成「隐藏」的不下发（同挂单/大图通道）
        cachePreview(
            shareId,
            ObjectPreviewPayload(
                id = shareId.toString(),
                isPokemon = true,
                speciesId = pokemon.species.resourceIdentifier.toString(),
                aspects = pokemon.aspects.toList(),
                shiny = pokemon.shiny,
                heldItemNbt = if (pokemon.heldItemVisible) {
                    encodeStack(pokemon.heldItem(), player.serverWorld.registryManager)
                } else null,
                itemId = "",
                itemNbt = null,
                itemComponentsSpec = null,
                count = 1
            )
        )
        return buildPokemonMessage(player, shareId, pokemon)
    }

    /** 物品分享：按槽位定位到背包主栏里的那个栈（越界/空栈一律丢弃；槽位号来自客户端，必须先校验范围） */
    private fun itemShareMessage(player: ServerPlayerEntity, shareId: UUID, rawSlot: String): Text? {
        val slot = rawSlot.toIntOrNull() ?: return null
        val main = player.inventory.main
        if (slot !in main.indices) return null
        val stack = main[slot]
        if (stack.isEmpty) return null
        // 数量按**背包里的总数**算：选择界面的列表把同类物品跨栈合并成一行（64+32 显示 96），
        // 玩家点的是那一行，分享出去的数字不该缩水成其中一个栈（用交易匹配的同一套相等规则，
        // 与列表的合并口径一致）。展示用，不涉及资产变动
        val total = main.sumOf { if (itemsEqualForTrading(it, stack)) it.count else 0 }

        cachePreview(
            shareId,
            ObjectPreviewPayload(
                id = shareId.toString(),
                isPokemon = false,
                speciesId = "",
                aspects = emptyList(),
                shiny = false,
                heldItemNbt = null,
                itemId = Registries.ITEM.getId(stack.item).toString(),
                itemNbt = encodeStack(stack, player.serverWorld.registryManager),
                itemComponentsSpec = null,
                count = total
            )
        )

        val nameText = Text.literal("").append(Text.translatable(stack.item.translationKey))
            .styled { it.withUnderline(true) }
        return shareMessage(
            player = player,
            shareId = shareId,
            nameText = nameText,
            hover = itemHover(stack, total, player.serverWorld.registryManager)
        )
    }

    private fun buildPokemonMessage(player: ServerPlayerEntity, shareId: UUID, pokemon: Pokemon): Text {
        val primaryTypeKey = "cobblemon.type.${pokemon.primaryType.name.lowercase()}"
        // 名字颜色按**当前形态**取主属性色（与列表/悬停窗同一口径：阿罗拉形态显示冰色而非物种默认色）
        val nameText = Text.literal("").append(SpeciesText.translated(pokemon.species)).styled {
            it.withColor(TypeTextColors.color(primaryTypeKey)).withUnderline(true)
        }
        val displayName = if (pokemon.shiny) {
            Text.literal("").append(Text.literal("★ ").formatted(Formatting.GOLD)).append(nameText)
        } else {
            Text.literal("").append(nameText)
        }
        return shareMessage(player, shareId, displayName, pokemonHover(pokemon))
    }

    /**
     * 消息骨架：`[分享] <玩家名> 分享了 <名字>`；`[分享]` 金、主体蓝、名字自带样式，
     * 挂着悬浮详情 + 一条**给聊天悬浮大图认人的指令**。
     *
     * 那条 `/cobblemarket share <id>` 是需求「不要点击跳转」下唯一可行的识别手段：
     * 原版聊天文字能挂的只有 clickEvent / hoverEvent，客户端 `ChatObjectPreview` 就是顺着
     * clickEvent 里的指令词认出「鼠标下是哪个对象」的。点击它时执行的是个**空分支**（客户端什么都不做），
     * 所以对玩家而言点了没反应。
     */
    private fun shareMessage(player: ServerPlayerEntity, shareId: UUID, nameText: Text, hover: Text): Text {
        val clickable = Text.literal("").append(nameText)
            .styled {
                it.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
                    .withClickEvent(
                        ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cobblemarket share $shareId")
                    )
            }
        return Text.literal("[分享] ").formatted(Formatting.GOLD)
            .append(
                Text.translatable(
                    "cobblemarket.chat.share_announce",
                    Text.literal(player.name.string),
                    clickable
                ).formatted(Formatting.BLUE)
            )
    }

    /**
     * 精灵悬浮详情：结构照拍卖播报的 `buildAnnouncementHover`（同一套行序与配色，玩家在哪看到都一样），
     * 只是数据直接读精灵对象 —— 分享没有拍卖那份 `extraData` 缓存。
     * 比拍卖多一行**证章**（分享的意义就是展示，证章是稀有个体标签；聊天画不了纹理，用证章名）。
     */
    private fun pokemonHover(pokemon: Pokemon): Text {
        val lines = mutableListOf<Text>()
        val genderText = when (pokemon.gender.name) {
            "MALE" -> Text.translatable("cobblemarket.aspect.male")
            "FEMALE" -> Text.translatable("cobblemarket.aspect.female")
            else -> Text.literal("")
        }
        val sizeTag = PokemonSize.from(pokemon)
        lines.add(
            Text.translatable("cobblemarket.gui.lv").append(Text.literal("${pokemon.level}"))
                .append(Text.literal(" "))
                .append(genderText)
                .append(
                    if (sizeTag.isEmpty()) Text.literal("")
                    else Text.literal((if (genderText.string.isEmpty()) "" else " ") + sizeTag)
                )
        )
        val primaryTypeKey = "cobblemon.type.${pokemon.primaryType.name.lowercase()}"
        lines.add(
            Text.translatable("cobblemarket.gui.tooltip_type").append(
                Text.translatable(primaryTypeKey).styled { it.withColor(TypeTextColors.color(primaryTypeKey)) }
            ).append(
                pokemon.secondaryType?.let { secondary ->
                    val key = "cobblemon.type.${secondary.name.lowercase()}"
                    Text.literal(" + ").append(
                        Text.translatable(key).styled { it.withColor(TypeTextColors.color(key)) }
                    )
                } ?: Text.literal("")
            )
        )
        lines.add(Text.translatable("cobblemarket.gui.tooltip_nature").append(Text.translatable("cobblemon.nature.${pokemon.effectiveNature.name.path}")))
        lines.add(Text.translatable("cobblemarket.gui.tooltip_ability").append(Text.translatable("cobblemon.ability.${pokemon.ability.name}")))
        lines.add(Text.translatable("cobblemarket.gui.tooltip_ball").append(Text.translatable("item.${pokemon.caughtBall.name.namespace}.${pokemon.caughtBall.name.path}")))
        // 携带物：玩家在队伍详情里设成「隐藏」的不显示（与挂单/大图同一口径）
        val held = pokemon.heldItem()
        if (!held.isEmpty && pokemon.heldItemVisible) {
            lines.add(Text.translatable("cobblemarket.gui.tooltip_held").append(Text.translatable(held.item.translationKey)))
        }
        // IV/EV 六行：行色按属性色系、EV 恒红（与出价弹窗、拍卖播报完全一致）
        lines.add(Text.translatable("cobblemarket.gui.tooltip_ivs"))
        fun ivLine(stat: Text, statKey: Stats, color: Formatting): Text =
            Text.literal("  ").append(stat)
                .append(Text.literal(": ${pokemon.ivs[statKey]}"))
                .formatted(color)
                .append(Text.literal("  EV: ${pokemon.evs[statKey]}").formatted(Formatting.RED))
        lines.add(ivLine(Text.literal(StatNames.HP), Stats.HP, Formatting.GREEN))
        lines.add(ivLine(Text.literal(StatNames.ATK), Stats.ATTACK, Formatting.RED))
        lines.add(ivLine(Text.literal(StatNames.DEF), Stats.DEFENCE, Formatting.GOLD))
        lines.add(ivLine(Text.literal(StatNames.SP_ATK), Stats.SPECIAL_ATTACK, Formatting.BLUE))
        lines.add(ivLine(Text.literal(StatNames.SP_DEF), Stats.SPECIAL_DEFENCE, Formatting.GREEN))
        lines.add(ivLine(Text.literal(StatNames.SPEED), Stats.SPEED, Formatting.LIGHT_PURPLE))
        lines.add(
            Text.translatable("cobblemarket.gui.friendship", pokemon.friendship)
                .styled { it.withColor(0xFF99CC) }
        )
        if (pokemon.marks.isNotEmpty()) {
            val shown = pokemon.marks.take(MARKS_SHOWN).map { it.getName().string }
            val rest = pokemon.marks.size - shown.size
            val marksText = shown.joinToString("、") + if (rest > 0) " +$rest" else ""
            lines.add(Text.translatable("cobblemarket.gui.tooltip_marks").append(Text.literal(marksText)))
        }

        var hover: MutableText = Text.literal("")
        lines.forEachIndexed { i, line ->
            hover = hover.append(line)
            if (i < lines.size - 1) hover = hover.append(Text.literal("\n"))
        }
        return hover
    }

    /** 物品悬浮详情：数量 + 附魔词条（与拍卖播报的物品版同一套，玩家在哪看到都一样） */
    private fun itemHover(stack: ItemStack, count: Int, registryManager: RegistryWrapper.WrapperLookup): Text {
        val lines = mutableListOf<Text>()
        lines.add(Text.translatable("cobblemarket.auction.count").append(Text.literal("$count")))
        // 附魔词条要从整栈 NBT 里读（与拍卖 hover 共用 RecordDetail 的提取逻辑）
        val nbt = try {
            stack.encode(registryManager) as? NbtCompound
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Failed to encode shared item: {}", e.message)
            null
        }
        RecordDetail.enchantmentLevels(nbt).forEach { (key, level) ->
            lines.add(
                Text.translatable("enchantment.${key.replace(':', '.')}")
                    .append(Text.literal(" ${romanNumeral(level)}"))
                    .formatted(Formatting.AQUA)
            )
        }
        var hover: MutableText = Text.literal("")
        lines.forEachIndexed { i, line ->
            hover = hover.append(line)
            if (i < lines.size - 1) hover = hover.append(Text.literal("\n"))
        }
        return hover
    }

    /** 附魔等级罗马数字（原版惯例；超 10 直接阿拉伯数字） */
    private fun romanNumeral(v: Int): String = when (v) {
        1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; 5 -> "V"
        6 -> "VI"; 7 -> "VII"; 8 -> "VIII"; 9 -> "IX"; 10 -> "X"
        else -> v.toString()
    }
}
