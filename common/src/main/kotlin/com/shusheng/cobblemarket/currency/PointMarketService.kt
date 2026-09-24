package com.shusheng.cobblemarket.currency

import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.market.ItemRuleComponents
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.util.MoveText
import com.shusheng.cobblemarket.util.PersistHelper
import com.shusheng.cobblemarket.util.SpeciesText
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier

/**
 * RP 商城的失败原因（调用方翻成玩家语言的提示）。
 *
 * ⚠ 与充值那条线（[PointShopError]）分开：那个讲的是**订单状态机**，这个讲的是**商品规格**，
 * 混在一个枚举里两边都得写用不上的分支。
 */
enum class PointMarketError {
    /** 没有 `points.shop` 节点（服主恒真）—— 2026-09-25 起由节点判定，不再认 OP */
    NO_PERMISSION,
    /** 格子序号越界（负数 / 超出允许的页数） */
    BAD_INDEX,
    /** 该格子本来就是空的（下架一个空位） */
    NOT_FOUND,
    /** 种类字段认不出来（只认 pokemon / item） */
    BAD_KIND,
    /** 规格与种类对不上（精灵格却给了物品规格，或反过来） */
    BAD_SPEC,
    /** 物种解析不出来 */
    BAD_SPECIES,
    /** 技能名解析不出来 */
    BAD_MOVE,
    /** 物品 id 解析不出来 */
    BAD_ITEM,
    /** 价格 / 库存 / 个体值这类数字不合法 */
    BAD_NUMBER,

    /** 六项努力值**总和**超限（各项单独看都合法，加起来过了有效的 508） */
    BAD_EV_TOTAL
}

/**
 * **RP 商城（商品区）的服务端内核** —— 上架 / 编辑 / 下架的校验与落库。
 *
 * 与充值订单那条线无关（[PointShopService] 管的是「买 RP」，本类管的是「用 RP 买商品」），
 * 两者只共用 [PointsState] 的余额与 [PointsAuditLogger] 的审计。
 *
 * 铁律（对照 `docs/points_plan.md` 的「安全设计」）：
 * - **信任边界**：规格只在**创建**时来自客户端（持节点者上架），此后一切操作只读服务端对象
 * - **创建类请求非法即拒绝**：与读档的「容错跳过」语义**相反**，这里绝不"尽量修正后存进去"
 * - **改写判 `points.shop` 节点**（2026-09-25 从 OP 改判，见 `docs/permission_nodes_plan.md` ⑩）：
 *   商品规格由持节点者填，等于**发给玩家的东西**的质量把关权 —— 该由服主决定交给谁
 */
object PointMarketService {

    /**
     * 格子序号上限（64 页）。
     * 挡的是「客户端 bug / 伪造包把序号写成天文数字」—— 序号是要进存档的，
     * 一个 Int.MAX_VALUE 会让 [PointShopState.pageCount] 算出天量页数、翻页按钮永远点不完。
     */
    const val MAX_INDEX = PointShopState.POINTS_SHOP_PAGE_SIZE * 64 - 1

    /** 一格的形态数量上限（照黑名单的口径：异常输入不该随商品持久化膨胀存档） */
    private const val MAX_ASPECTS = 32

    private const val MAX_IV = 31
    private const val MAX_EV = 252

    /**
     * 六项努力值的**总和**上限 —— 取 **508**（有效的满值），不是 Cobblemon 的硬上限 510。
     *
     * ⚠ 单项 252 挡不住总和：六项全填 252 加起来是 1512。而 Cobblemon 那边 `EVs.canSet` 会**静默
     * 拒设**第三项起的值（出来是 `[252,252,0,0,0,0]`，跟服主填的完全不是一回事），`EVs.CODEC`
     * 反序列化时更是直接报错。所以这一条必须在保存规格时就拦下（2026-09-20 用户提出）。
     * ⚠ 为什么是 508 不是 510：「每 4 点 EV 才换 1 点能力值」，510 里最后 2 点换不出任何能力值
     *   （`252/252/6` 与 `252/252/4` 战力相同）—— 与客户端 `EV_TOTAL_MAX` 同一口径。
     */
    private const val MAX_EV_TOTAL = 508
    private const val MIN_LEVEL = 1
    private const val MAX_LEVEL = 100

    // ── 读 ──

    /**
     * 某一页**已上架**的格子（序号升序）。空格子本来就不进表（数据层如此），
     * 所以返回值可能远少于 32 项 —— 界面照旧画满 32 个格子，空位由界面补。
     */
    fun pageEntries(server: MinecraftServer, page: Int): List<Pair<Int, ShopEntry>> {
        val from = page * PointShopState.POINTS_SHOP_PAGE_SIZE
        val to = from + PointShopState.POINTS_SHOP_PAGE_SIZE
        return PointShopState.get(server).all()
            .filterKeys { it in from until to }
            .toSortedMap()
            .map { it.key to it.value }
    }

    /** 总页数（至少 1 页：一件没上架也留一页空位等着服主填） */
    fun pageCount(server: MinecraftServer): Int = PointShopState.get(server).pageCount()

    /** 把任意页号收进合法范围（客户端翻页参数不可信） */
    fun clampPage(page: Int): Int = page.coerceIn(0, MAX_INDEX / PointShopState.POINTS_SHOP_PAGE_SIZE)

    // ── 写（判 `points.shop` 节点）──

    /**
     * 上架 / 编辑一格。**编辑就是覆盖**（同一格写新值），也正因此界面回填必须完整 ——
     * 「什么都不改直接保存 = 什么都不变」是全覆盖式表单的铁律。
     *
     * @return null = 成功；否则是拒绝原因（调用方翻成提示语）
     */
    fun save(server: MinecraftServer, player: ServerPlayerEntity, index: Int, entry: ShopEntry): PointMarketError? {
        if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return PointMarketError.NO_PERMISSION
        if (index < 0 || index > MAX_INDEX) return PointMarketError.BAD_INDEX
        val normalized = normalize(entry, server) ?: return reject(entry)
        PointShopState.get(server).put(index, normalized)
        // 交易品变更强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
        PersistHelper.requestSave(server)
        CobbleMarket.LOGGER.info(
            "Point shop slot {} set by {} (kind={}, price={}, stock={})",
            index, player.name.string, normalized.kind, normalized.price, normalized.stock
        )
        return null
    }

    /** 下架（清空一格）；返回 null = 成功 */
    fun remove(server: MinecraftServer, player: ServerPlayerEntity, index: Int): PointMarketError? {
        if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return PointMarketError.NO_PERMISSION
        if (index < 0 || index > MAX_INDEX) return PointMarketError.BAD_INDEX
        if (!PointShopState.get(server).remove(index)) return PointMarketError.NOT_FOUND
        PersistHelper.requestSave(server)
        CobbleMarket.LOGGER.info("Point shop slot {} removed by {}", index, player.name.string)
        return null
    }

    // ── 校验 / 规范化 ──

    /**
     * 校验并**规范化**一格商品（返回 null = 不合法，拒绝入库）。
     *
     * 规范化只做「把客户端给的值折算成存档该存的形式」，**不做"尽力修补"**：
     * 任何一处对不上就直接整条拒绝 —— 半修半存的数据比拒绝更难排查。
     */
    private fun normalize(entry: ShopEntry, server: MinecraftServer): ShopEntry? {
        if (entry.price < 0L) return null
        // 0 是**售罄后的展示值**，不是上架时能设的库存：要限量就 ≥1，不限量用 -1（界面留空即 -1）
        if (entry.stock != ShopEntry.UNLIMITED && entry.stock < 1) return null
        return when (entry.kind) {
            ShopEntry.KIND_POKEMON -> {
                val spec = entry.pokemon ?: return null
                if (entry.item != null) return null
                entry.copy(pokemon = normalizePokemon(spec) ?: return null, item = null)
            }

            ShopEntry.KIND_ITEM -> {
                val spec = entry.item ?: return null
                if (entry.pokemon != null) return null
                entry.copy(item = normalizeItem(spec, server) ?: return null, pokemon = null)
            }

            else -> null
        }
    }

    /**
     * 精灵规格：校验各项能否落地，并把**物种统一存成资源 id**。
     *
     * 客户端理论上传的已经是本地解析出的 id（服务端只有英文环境，中文名在这儿解析不了），
     * 但服务端自己再定一次 —— 存档里混进"中文名"这种在英文服务端解析不出来的值，
     * 会一直潜伏到有人买东西那一刻才炸。
     */
    private fun normalizePokemon(spec: ShopPokemonSpec): ShopPokemonSpec? {
        // ⚠ resolveByNameOrId 对**含冒号的输入不校验存在性**（原样返回），所以还要真解析一次
        val speciesId = SpeciesText.resolveByNameOrId(spec.species) ?: return null
        val identifier = Identifier.tryParse(speciesId) ?: return null
        if (PokemonSpecies.getByIdentifier(identifier) == null) return null

        // 技能：存的是槽位串（空槽 = 空串），非空槽必须能解析成真技能
        if (MoveText.slotIds(spec.moves).any { it.isNotEmpty() && Moves.getByName(it) == null }) return null

        if (spec.aspects.size > MAX_ASPECTS) return null
        if (spec.minPerfectIvs !in 0..6) return null
        val ivs = spec.ivs
        if (ivs != null && (ivs.size != 6 || ivs.any { it < -1 || it > MAX_IV })) return null
        val evs = spec.evs
        if (evs != null && (evs.size != 6 || evs.any { it < 0 || it > MAX_EV })) return null
        // 总和另算一道（见 [MAX_EV_TOTAL] = 508）：各项都合法也可能加起来超
        if (evs != null && evs.sum() > MAX_EV_TOTAL) return null
        spec.level?.let { if (it !in MIN_LEVEL..MAX_LEVEL) return null }

        return spec.copy(species = speciesId)
    }

    /**
     * 物品规格：物品 id 必须存在；组件快照走 [ItemRuleComponents.sanitizeSpec] 重建一遍，
     * **白名单外的组件一律丢弃**（与黑名单/限价同一套语义）—— 客户端塞什么组件进来都不算数，
     * 服务端重建出来的才算。
     */
    private fun normalizeItem(spec: ShopItemSpec, server: MinecraftServer): ShopItemSpec? {
        val identifier = Identifier.tryParse(spec.itemId) ?: return null
        if (Registries.ITEM.getOrEmpty(identifier).isEmpty) return null
        val sanitized = ItemRuleComponents.sanitizeSpec(spec.itemId, spec.componentsSpec, server.registryManager)
        return spec.copy(componentsSpec = sanitized)
    }

    /**
     * 规范化失败时**尽量给出具体原因**（只回一句"规格非法"，OP 对着一个填错的输入框无从下手）。
     * 判据都是重跑一遍便宜的小检查，不为了报错把 normalize 拆成两遍。
     */
    private fun reject(entry: ShopEntry): PointMarketError = when (entry.kind) {
        ShopEntry.KIND_POKEMON -> when {
            entry.pokemon == null || entry.item != null -> PointMarketError.BAD_SPEC
            SpeciesText.resolveByNameOrId(entry.pokemon.species) == null -> PointMarketError.BAD_SPECIES
            MoveText.slotIds(entry.pokemon.moves)
                .any { it.isNotEmpty() && Moves.getByName(it) == null } -> PointMarketError.BAD_MOVE
            (entry.pokemon.evs?.sum() ?: 0) > MAX_EV_TOTAL -> PointMarketError.BAD_EV_TOTAL
            else -> PointMarketError.BAD_NUMBER
        }

        ShopEntry.KIND_ITEM -> when {
            entry.item == null || entry.pokemon != null -> PointMarketError.BAD_SPEC
            Identifier.tryParse(entry.item.itemId) == null -> PointMarketError.BAD_ITEM
            Registries.ITEM.getOrEmpty(Identifier.tryParse(entry.item.itemId)!!).isEmpty -> PointMarketError.BAD_ITEM
            else -> PointMarketError.BAD_NUMBER
        }

        else -> PointMarketError.BAD_KIND
    }
}
