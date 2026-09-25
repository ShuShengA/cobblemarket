package com.shusheng.cobblemarket.currency

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Pokemon
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.market.ItemRuleComponents
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.util.MoveText
import com.shusheng.cobblemarket.util.PersistHelper
import com.shusheng.cobblemarket.util.SpeciesText
import net.minecraft.item.ItemStack
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
    BAD_EV_TOTAL,

    // ── 购买侧（2026-09-25 新增）──

    /** 购买数量不合法（≤0 或超防呆上限） */
    BAD_COUNT,
    /** 这一格**已售罄**（库存 0；格子本身还在） */
    SOLD_OUT,
    /** 库存不够买这么多 */
    STOCK_NOT_ENOUGH,
    /** RP 余额不足 */
    NOT_ENOUGH_POINTS,
    /** **收件空间不足**（队伍和 PC 都满 / 背包放不下）—— 下单前就拒绝，绝不丢玩家东西 */
    NO_SPACE,
    /** 按规格造不出来（物种/物品已被删、存档数据损坏）—— 此时**分文未扣** */
    GENERATE_FAILED,

    /**
     * **这一格在玩家看它之后被服主改过**（内容版本号对不上）—— 拒绝成交、**分文未扣**。
     *
     * 玩家看到的可能是旧精灵 / 旧价格，按新的成交等于骗人；这一条是"付钱前一刻的强制检查"，
     * 光靠界面上关窗拦不住（广播有网络延迟，见 [PointMarketService.buy] 的竞态说明）。
     */
    STALE_ENTRY
}

/**
 * **一次购买的结果**。
 *
 * 为什么不只返回个错误码：买成功后要发**庆祝动画**，而它需要**实际造出来的那件东西** ——
 * 概率闪光只有掷完才知道闪没闪，拿规格去猜就会把"买到闪光"报成不闪。
 */
sealed interface PointMarketBuyResult {

    /**
     * @param pokemon 买精灵时是**实际造出来的那只**（庆祝动画要用它的形态与闪光）；买物品时为 null
     * @param items 买物品时是**按 maxCount 拆好的若干栈**；买精灵时为空
     * @param count 成交数量（精灵格恒 1）
     * @param total 实际扣掉的 RP（回执显示用）
     */
    data class Success(
        val pokemon: Pokemon?,
        val items: List<ItemStack>,
        val count: Int,
        val total: Long
    ) : PointMarketBuyResult

    data class Failure(val error: PointMarketError) : PointMarketBuyResult
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

    /**
     * 单次购买的数量上限（36 格 × 64 = 2304）。
     * 不是产品限制而是**防呆**：再多也必然放不下，先拦下来比让客户端填个天文数字更有意义。
     */
    private const val MAX_BUY_COUNT = 2304

    /** 主背包格数 —— 判收件空间只看它（副手当额外余量，宁可判严） */
    private const val MAIN_INVENTORY_SIZE = 36

    // ── 读 ──

    /**
     * 某一页**已上架**的格子（序号升序）。空格子本来就不进表（数据层如此），
     * 所以返回值可能远少于 32 项 —— 界面照旧画满 32 个格子，空位由界面补。
     */
    fun pageEntries(server: MinecraftServer, page: Int, kind: String): List<Pair<Int, ShopEntry>> {
        val from = page * PointShopState.POINTS_SHOP_PAGE_SIZE
        val to = from + PointShopState.POINTS_SHOP_PAGE_SIZE
        return PointShopState.get(server).all(kind)
            .filterKeys { it in from until to }
            .toSortedMap()
            .map { it.key to it.value }
    }

    /** 某个种类的总页数（至少 1 页：一件没上架也留一页空位等着服主填） */
    fun pageCount(server: MinecraftServer, kind: String): Int = PointShopState.get(server).pageCount(kind)

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
        val state = PointShopState.get(server)
        // ⚠ **每次保存都换一个全新的 id**（绝不信客户端传的）：语义上等于"旧的作废、换了一个新的"，
        //   玩家手里那份随之失效 —— 他再点购买会被 [buy] 拒绝，而不是按改后的内容 / 价格成交。
        //   ⚠ 用 UUID 而不是自增版本号：下架后重新上架会从 0 重来，与玩家手里旧的 0 撞上就漏了。
        val replaced = normalized.copy(entryId = java.util.UUID.randomUUID().toString())
        // ⚠ kind 取自**规范化后的 entry**：精灵与物品各占一套独立的格子编号（2026-09-25 起，
        //   tab 变成真过滤），同一个 index 在两个 tab 下是两个不同的位置
        state.put(replaced.kind, index, replaced)
        // 交易品变更强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
        PersistHelper.requestSave(server)
        CobbleMarket.LOGGER.info(
            "Point shop slot {} set by {} (kind={}, price={}, stock={}, id={})",
            index, player.name.string, replaced.kind, replaced.price, replaced.stock, replaced.entryId
        )
        return null
    }

    /**
     * **补货：只改库存**（`-1` = 不限量）；返回 null = 成功。
     *
     * ⚠ **刻意不动 `entryId`**：编辑功能取消后，售罄的格子留在界面上就是为了让服主补货
     *（原话见 [PointShopState.setStock] 的用途）。补货只是"又进了几件"，**不是换了商品** ——
     * 换 id 会让所有开着的界面弹「这件商品刚被改动过」，把正常的补货说成内容变更。
     * ⚠ 也**不走 [save]**：那一版每次都会换新 id（防按改后内容成交，见它的注释），语义正好相反。
     */
    fun updateStock(
        server: MinecraftServer, player: ServerPlayerEntity, kind: String, index: Int, stock: Int
    ): PointMarketError? {
        if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return PointMarketError.NO_PERMISSION
        if (kind != ShopEntry.KIND_POKEMON && kind != ShopEntry.KIND_ITEM) return PointMarketError.BAD_KIND
        if (index < 0 || index > MAX_INDEX) return PointMarketError.BAD_INDEX
        // 入库前归一：负数一律按「不限量」算（界面留空传的就是它），别把 -5 这种写进存档
        val normalized = if (stock < 0) ShopEntry.UNLIMITED else stock
        if (!PointShopState.get(server).setStock(kind, index, normalized)) return PointMarketError.NOT_FOUND
        PersistHelper.requestSave(server)
        CobbleMarket.LOGGER.info(
            "Point shop slot {} stock set to {} by {}", index, normalized, player.name.string
        )
        return null
    }

    /** 下架（清空一格）；返回 null = 成功。⚠ 要指明 [kind] —— 同一序号在两个 tab 下是两个格子 */
    fun remove(
        server: MinecraftServer, player: ServerPlayerEntity, kind: String, index: Int
    ): PointMarketError? {
        if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return PointMarketError.NO_PERMISSION
        if (kind != ShopEntry.KIND_POKEMON && kind != ShopEntry.KIND_ITEM) return PointMarketError.BAD_KIND
        if (index < 0 || index > MAX_INDEX) return PointMarketError.BAD_INDEX
        if (!PointShopState.get(server).remove(kind, index)) return PointMarketError.NOT_FOUND
        PersistHelper.requestSave(server)
        CobbleMarket.LOGGER.info("Point shop slot {} removed by {}", index, player.name.string)
        return null
    }

    // ── 买（玩家侧；**不判权限** —— 谁有 RP 谁能买）──

    /**
     * **玩家用 RP 买一格商品** —— 商城唯一"花钱"的入口。
     *
     * 事务顺序（**每一步都不能颠倒**）：
     * 1. 全部校验：格子 / 数量 / 库存 / 余额 / **收件空间**
     * 2. **先把货造出来**（[PointMarketFactory] 是纯对象构造，失败时什么都还没动）
     * 3. 扣库存 → 扣钱 → 交付
     * 4. 中途任何一步失败 → **回滚前面的步骤**，绝不出现"扣了钱没拿到货"
     *
     * ⚠ **崩溃窗口**：[PointShopState.consume] 的注释定了「**先扣库存再扣钱**」——
     * 最坏是少卖一份（服主补货即可），反过来则会超卖。
     * ⚠ **扣掉的 RP 直接销毁**（不进任何人的账）：商城由服主单方面经营、没有收款方，
     * 与「RP 手续费销毁」同一口径（见 `docs/points_plan.md`）。
     * ⚠ 本函数**不碰** `FinanceState.recordTrade` —— 那会污染紫卡/黑卡门槛与贷款额度。
     * ⚠ **总开关关闭时这里照常可用**：商城是唯一还能用 RP 的地方（不判 `pointsEnabled`）。
     *
     * @param count 购买数量。**精灵格恒按 1 算**（买一次就是一只，数量由请求几次表达）——
     *              不把这个自由度留给客户端；物品格才是玩家在购买窗填的那个数
     * @return [PointMarketBuyResult.Success]（含造出来的实物，供发庆祝动画）/ [PointMarketBuyResult.Failure]
     */
    fun buy(
        server: MinecraftServer,
        player: ServerPlayerEntity,
        kind: String,
        index: Int,
        count: Int,
        expectedEntryId: String
    ): PointMarketBuyResult {
        if (kind != ShopEntry.KIND_POKEMON && kind != ShopEntry.KIND_ITEM) {
            return PointMarketBuyResult.Failure(PointMarketError.BAD_KIND)
        }
        if (index < 0 || index > MAX_INDEX) return PointMarketBuyResult.Failure(PointMarketError.BAD_INDEX)
        val state = PointShopState.get(server)
        val entry = state.find(kind, index) ?: return PointMarketBuyResult.Failure(PointMarketError.NOT_FOUND)

        // ⚠ **付钱前一刻的强制检查**，必须紧跟在取到 entry 之后、早于算价 ——
        //   条目 id 对不上就说明这一格在玩家看它之后**换过内容**（换了精灵 / 改了价 / 下架重上），
        //   按新的成交等于骗人。此时直接拒绝，**分文未扣**。
        //   ⚠ 这条**不能靠界面关窗替代**：广播有网络延迟（服务端发的"已变更"要跑一个来回才到客户端），
        //   玩家完全可能在收到之前就点了确认。服务端是单线程的，两个包必然一先一后 ——
        //   「保存先到」那种顺序就是靠这一行挡在扣钱之前的（「购买先到」则按旧内容成交，也没问题）。
        if (expectedEntryId != entry.entryId) return PointMarketBuyResult.Failure(PointMarketError.STALE_ENTRY)

        val amount = if (entry.kind == ShopEntry.KIND_POKEMON) 1 else count
        if (amount < 1 || amount > MAX_BUY_COUNT) return PointMarketBuyResult.Failure(PointMarketError.BAD_COUNT)

        // 库存：0 = 已售罄（格子还留着）；-1 = 无限量
        if (entry.stock == 0) return PointMarketBuyResult.Failure(PointMarketError.SOLD_OUT)
        if (entry.stock > 0 && amount > entry.stock) {
            return PointMarketBuyResult.Failure(PointMarketError.STOCK_NOT_ENOUGH)
        }

        // 总价 —— 防溢出：溢成负数就等于白送（price 是服主填的 Long，没有上限）
        if (entry.price > Long.MAX_VALUE / amount) {
            return PointMarketBuyResult.Failure(PointMarketError.BAD_NUMBER)
        }
        val total = entry.price * amount

        val points = PointsState.get(server)
        if (points.getBalance(player.uuid) < total) {
            return PointMarketBuyResult.Failure(PointMarketError.NOT_ENOUGH_POINTS)
        }

        // ── 造货：在此之前什么都没碰；造不出来 = 数据坏了 → 整笔拒绝 ──
        val pokemon = if (entry.kind == ShopEntry.KIND_POKEMON) {
            val spec = entry.pokemon ?: return PointMarketBuyResult.Failure(PointMarketError.BAD_SPEC)
            PointMarketFactory.createPokemon(spec, player)
                ?: return PointMarketBuyResult.Failure(PointMarketError.GENERATE_FAILED)
        } else {
            null
        }
        val items = if (entry.kind == ShopEntry.KIND_ITEM) {
            val spec = entry.item ?: return PointMarketBuyResult.Failure(PointMarketError.BAD_SPEC)
            PointMarketFactory.createItems(spec, amount, server.registryManager)
                ?: return PointMarketBuyResult.Failure(PointMarketError.GENERATE_FAILED)
        } else {
            emptyList()
        }

        // ── 收件空间：**必须在扣款之前**（用户 2026-09-20 拍板：宁可拒绝，绝不丢玩家东西）──
        if (pokemon != null && !hasPokemonSpace(player)) {
            return PointMarketBuyResult.Failure(PointMarketError.NO_SPACE)
        }
        if (items.isNotEmpty() && !hasItemSpace(player, items)) {
            return PointMarketBuyResult.Failure(PointMarketError.NO_SPACE)
        }

        // ── 扣库存 → 扣钱 → 交付：失败逐级回滚 ──
        if (!state.consume(kind, index, amount)) return PointMarketBuyResult.Failure(PointMarketError.SOLD_OUT)
        if (!points.removeBalance(player.uuid, total)) {
            state.setStock(kind, index, entry.stock) // 回滚库存（entry 是扣减前的快照）
            return PointMarketBuyResult.Failure(PointMarketError.NOT_ENOUGH_POINTS)
        }

        val delivered = if (pokemon != null) {
            // Party.add 在队伍满时会自动转入 PC，返回 false = 两边都满
            runCatching { Cobblemon.storage.getParty(player).add(pokemon) }.getOrDefault(false)
        } else {
            deliverItems(player, items)
        }
        if (!delivered) {
            // 预检过了还失败 = 极小概率（背包在两步之间被别的东西占了）——
            // 钱和库存都退回去。宁可这单不成，也不能让玩家吃亏
            points.addBalance(player.uuid, total)
            state.setStock(kind, index, entry.stock)
            CobbleMarket.LOGGER.warn(
                "Point shop: delivery failed for {} on slot {}, rolled back", player.name.string, index
            )
            return PointMarketBuyResult.Failure(PointMarketError.NO_SPACE)
        }

        // ── 记账 + 落盘 ──
        // 明细写**商品是什么**（不是"第几格"）：服主对账时格子序号毫无意义
        val goods = when (entry.kind) {
            ShopEntry.KIND_POKEMON -> entry.pokemon?.species.orEmpty()
            else -> entry.item?.itemId.orEmpty()
        }
        PointsAuditLogger.log(
            PointsLogType.STORE_BUY,
            player.uuid,
            player.name.string,
            total,
            points.getBalance(player.uuid),
            player.name.string,
            "商城购买：$goods ×$amount（第 ${index + 1} 格）",
            "Store purchase: $goods x$amount (slot ${index + 1})"
        )
        PersistHelper.requestSave(server)
        CobbleMarket.LOGGER.info(
            "Point shop: {} bought slot {} x{} for {} RP", player.name.string, index, amount, total
        )
        // 把造出来的实物一并交出去：调用方要拿它发庆祝动画（概率闪光只有此刻才确定）
        return PointMarketBuyResult.Success(pokemon = pokemon, items = items, count = amount, total = total)
    }

    /** 队伍或 PC 还有空位吗（`add` 会自动溢出到 PC，所以两边都满才是真放不下） */
    private fun hasPokemonSpace(player: ServerPlayerEntity): Boolean {
        val storage = Cobblemon.storage
        if (storage.getParty(player).getFirstAvailablePosition() != null) return true
        return storage.getPC(player).getFirstAvailablePosition() != null
    }

    /**
     * 背包放得下这些栈吗。
     *
     * 只算主背包 [MAIN_INVENTORY_SIZE] 格（`insertStack` 还会往副手放 ⇒ 实际容量只多不少，
     * 也就是这里**宁可判严**）；空槽按该物品的 `maxCount` 算，同类堆叠算它的剩余空间。
     */
    private fun hasItemSpace(player: ServerPlayerEntity, stacks: List<ItemStack>): Boolean {
        val sample = stacks.firstOrNull() ?: return true
        val max = sample.maxCount.coerceAtLeast(1)
        var capacity = 0L
        val inventory = player.inventory
        for (slot in 0 until MAIN_INVENTORY_SIZE) {
            val existing = inventory.getStack(slot)
            when {
                existing.isEmpty -> capacity += max
                ItemStack.areItemsAndComponentsEqual(existing, sample) -> capacity += (max - existing.count)
            }
        }
        return capacity >= stacks.sumOf { it.count.toLong() }
    }

    /**
     * 把货塞进背包；返回 false = 没全塞下（调用方负责回滚钱与库存）。
     *
     * ⚠ `insertStack` **部分放入也返回 true**，靠返回值判断不出来 —— 必须看传入栈的剩余量
     *（同 `CurrencyHandler.give` 的写法）。
     * ⚠ 万一中途失败（预检过了还失败属极小概率）：**已塞进去的照数取回**，
     * 保证「要么全成、要么全退」，不让玩家白得半份货。
     */
    private fun deliverItems(player: ServerPlayerEntity, stacks: List<ItemStack>): Boolean {
        val inserted = ArrayList<ItemStack>()
        for (stack in stacks) {
            val before = stack.count
            player.inventory.insertStack(stack)
            val put = before - stack.count
            if (put > 0) inserted.add(stack.copy().apply { count = put })
            if (!stack.isEmpty) {
                takeBackItems(player, inserted)
                return false
            }
        }
        return true
    }

    /** 交付失败时把已进背包的那部分取回来（按物品 + 组件匹配，不碰别的东西） */
    private fun takeBackItems(player: ServerPlayerEntity, inserted: List<ItemStack>) {
        val inventory = player.inventory
        inserted.forEach { wanted ->
            var left = wanted.count
            for (slot in 0 until MAIN_INVENTORY_SIZE) {
                if (left <= 0) break
                val existing = inventory.getStack(slot)
                if (existing.isEmpty || !ItemStack.areItemsAndComponentsEqual(existing, wanted)) continue
                val take = minOf(left, existing.count)
                existing.decrement(take)
                if (existing.isEmpty) inventory.setStack(slot, ItemStack.EMPTY)
                left -= take
            }
        }
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
