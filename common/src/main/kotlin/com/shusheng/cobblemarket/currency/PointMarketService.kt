package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.event.SHOP_SELLER_UUID
import com.shusheng.cobblemarket.event.TransactionCategory
import com.shusheng.cobblemarket.event.TransactionHistory
import com.shusheng.cobblemarket.event.TransactionRecord
import com.shusheng.cobblemarket.event.TransactionType
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Pokemon
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.market.ItemRuleComponents
import com.shusheng.cobblemarket.permission.MarketPermissions
import com.shusheng.cobblemarket.util.MoveText
import com.shusheng.cobblemarket.util.PersistHelper
import com.shusheng.cobblemarket.util.RecordDetail
import com.shusheng.cobblemarket.util.SpeciesText
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
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
    /** 有人正在编辑这家店（服主 / 有权限的人打开了编辑窗）—— 暂时不卖，分文未扣 */
    SHOP_BEING_EDITED,
    /** 库存不够买这么多 */
    STOCK_NOT_ENOUGH,
    /** 余额不足（币种由商店决定：主货币 / RP —— 文案的货币单位随之变化） */
    NOT_ENOUGH_FUNDS,
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
    STALE_ENTRY,

    // ── 自定义商店（2026-10-20 新增）──

    /** 商店不存在（被删了 / id 打错 / 客户端伪造） */
    SHOP_NOT_FOUND,
    /** 商店已关闭（服主临时停业；格子还在，但右键与购买都不放行） */
    SHOP_DISABLED,
    /** 玩家不在绑定 NPC 的 8 格范围内 —— 只对自定义商店生效，防止远程发包买货 */
    TOO_FAR,

    /**
     * 商店类型不允许这类商品：只卖精灵的店收到物品上架 / 购买，或反过来。
     * ⚠ 客户端已经隐藏了另一类的 tab，这一条防的是伪造包 / 类型刚改过还没重开界面。
     */
    KIND_NOT_ALLOWED,

    /**
     * **RP 总开关关着** —— 自定义（NPC）商店整体不可购买。
     *
     * ⚠ 2026-10-23 用户报：总开关关着，玩家仍能从 NPC 商店买物品。
     * ⚠ **主 RP 商城不在此列**：它是总开关关闭时唯一还能用 RP 的地方（见 [buy] 的注释）。
     */
    POINTS_DISABLED,

    /**
     * **主 RP 商城被单独关闭**（`pointsMainShopEnabled`，2026-10-25）。
     * 自定义 NPC 商店不受影响；总开关关闭时主商城仍按历史设计可用（入口置灰，正常客户端进不去）。
     */
    MAIN_SHOP_DISABLED,

    /** 这是**定期领取店**，不能用购买流程（只能领取） */
    NOT_FOR_SALE,
    /** 这是**普通购买店**，没有东西可领 */
    NOT_A_CLAIM_SHOP,

    /**
     * **购买冷却中**（2026-10-25）：商店冷却或这一格的冷却还没结束。
     * [PointMarketBuyResult.Failure.remainingMs] 带上还剩多久，客户端 / 聊天提示据此显示倒计时。
     */
    ON_COOLDOWN,

    /** 冷却商品**一次只能买 1 件**（有冷却的格子即使库存无限也不允许批量买） */
    ONE_PER_PURCHASE
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

    /**
     * @param remainingMs 仅 [PointMarketError.ON_COOLDOWN] 用：距离可再次购买还有多久（毫秒）
     */
    data class Failure(
        val error: PointMarketError,
        val remainingMs: Long = 0L
    ) : PointMarketBuyResult
}

/**
 * 一键领取里**实际交付的一格物品**（2026-10-23）。
 *
 * 只给客户端的「物品飞入物品栏」动画用 —— 精灵不做动画，所以不带精灵。
 * [entryId] 让客户端把交付结果对回它点「领取」时看到的那个格子；[count] 是**真进了背包**的数量
 * （背包放不下时可能小于这一格的可领数量）。
 */
data class ClaimedItem(val entryId: String, val count: Int)

/**
 * **定期领取店的一键领取结果**（2026-10-23）。
 *
 * @param delivered 实际放进背包 / 队伍的总数量
 * @param leftBehind 有格子没领完（空间不足）—— 剩余留在店里，本期还能再领
 * @param hadRemaining 本次调用前有可领内容（用来区分「空间不足」和「本来就没东西可领」）
 * @param items 实际交付的**物品**格明细（顺序 = 格子顺序），客户端据此只给真进了背包的那几格播飞入动画
 * @param shinyPokemon 实际交付的**闪光精灵**（只收闪光；给「领取到闪光」的全服播报用）
 */
sealed interface PointMarketClaimResult {
    data class Success(
        val delivered: Int,
        val leftBehind: Boolean,
        val hadRemaining: Boolean,
        val items: List<ClaimedItem> = emptyList(),
        val shinyPokemon: List<Pokemon> = emptyList()
    ) : PointMarketClaimResult

    data class Failure(val error: PointMarketError) : PointMarketClaimResult
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
     * **初始训练家（配信假训练家）名字的长度上限** —— 取 **16**，与 MC 玩家名同口径
     *（NPC 名与玩家名共用存档里同一个字段，分寸也照玩家的来）。
     *
     * ⚠ 公开给客户端编辑窗用（输入框的 `setTextPredicate`）—— 两边**共用一个数**，
     *   各写一份必然慢慢分叉（本项目的老坑）。
     */
    const val MAX_OT_LENGTH = 16

    /**
     * 单次购买的数量上限（36 格 × 64 = 2304）。
     * 不是产品限制而是**防呆**：再多也必然放不下，先拦下来比让客户端填个天文数字更有意义。
     */
    private const val MAX_BUY_COUNT = 2304

    /** 主背包格数 —— 判收件空间只看它（副手当额外余量，宁可判严） */
    private const val MAIN_INVENTORY_SIZE = 36

    /**
     * 自定义商店的**交互距离**（格）：玩家必须离**任何一个**绑定的 NPC 这么近才允许购买。
     *
     * ⚠ 打开界面时服务端已经校验过一次（右键 NPC 本身就在交互距离内），但**购买是另一个包** ——
     *   客户端完全可以打开界面后走开、或者伪造 `BuyPointMarketEntryPayload` 远程买货。
     *   所以扣钱之前必须再按服务端世界里的实体位置判一次（与 `PointsMarketScreen` 无关）。
     * ⚠ 只对自定义商店生效：主商城没有 NPC，从入口界面随时可进。
     */
    private const val MAX_SHOP_DISTANCE = 8.0

    // ── 商店解析 ──

    /**
     * 按 shopId 取存储：主商城 → [PointShopState]；自定义商店 → [CustomShopState] 里那个。
     * 返回 null = 自定义商店不存在（主商城恒存在）。
     *
     * ⚠ 读（[pageEntries] / [pageCount]）遇到 null 时给空快照；写与购买则回具体错误。
     */
    fun store(server: MinecraftServer, shopId: String): ShopStore? =
        if (shopId == MAIN_SHOP_ID) PointShopState.get(server)
        else CustomShopState.get(server).find(shopId)

    /** 自定义商店对象（主商城恒 null） */
    fun customShop(server: MinecraftServer, shopId: String): CustomShop? =
        if (shopId == MAIN_SHOP_ID) null else CustomShopState.get(server).find(shopId)

    /** 这家店的结算币种：主商城（RP 商城）恒 RP，自定义商店看建店时的选择 */
    fun currencyOf(server: MinecraftServer, shopId: String): MarketCurrency =
        customShop(server, shopId)?.currency ?: MarketCurrency.POINTS

    /**
     * 玩家是否在商店绑定的**任意一个** NPC 的 8 格内。
     *
     * ⚠ 用玩家所在世界的 `getEntity(uuid)`：跨维度的 NPC 查不到 ⇒ 自然判为"不在附近"。
     * ⚠ 一个 NPC 都没绑 ⇒ **恒 false**（自定义商店没绑定就没有合法入口，管理命令开的界面只能看/配）。
     */
    private fun isNearBoundNpc(player: ServerPlayerEntity, shop: CustomShop): Boolean {
        val maxSq = MAX_SHOP_DISTANCE * MAX_SHOP_DISTANCE
        return shop.npcUuids.any { uuid ->
            player.serverWorld.getEntity(uuid)?.squaredDistanceTo(player)?.let { it <= maxSq } == true
        }
    }

    /**
     * 「买到闪光」播报的前缀名：**玩家是从哪个 NPC 进的店**就用谁的名字
     * （由 [com.shusheng.cobblemarket.network.PointMarketNetwork] 在右键开店时记下）。
     *
     * ⚠ **不能按"附近最近的绑定 NPC"猜**：两家 NPC 挨得近时，玩家离另一家更近就会播错名字。
     * NPC 已解绑 / 找不到实体 / 没有自定义名 / 不是从 NPC 打开的 ⇒ 一律退回**店名**。
     */
    fun merchantName(player: ServerPlayerEntity, shop: CustomShop, npcUuid: java.util.UUID?): String =
        npcUuid
            ?.takeIf { it in shop.npcUuids }
            ?.let { player.serverWorld.getEntity(it) }
            ?.customName?.string
            ?.takeIf { it.isNotBlank() }
            ?: shop.name

    // ── 读 ──

    /**
     * 某一页**已上架**的格子（序号升序）。空格子本来就不进表（数据层如此），
     * 所以返回值可能远少于 32 项 —— 界面照旧画满 32 个格子，空位由界面补。
     */
    fun pageEntries(
        server: MinecraftServer, page: Int, kind: String, shopId: String = MAIN_SHOP_ID
    ): List<Pair<Int, ShopEntry>> {
        val from = page * PointShopState.POINTS_SHOP_PAGE_SIZE
        val to = from + PointShopState.POINTS_SHOP_PAGE_SIZE
        val custom = customShop(server, shopId)
        // 只卖精灵的店请求物品页（或反过来）⇒ 空快照；服务端不把另一类的内容发出去
        if (custom != null && !custom.type.allows(kind)) return emptyList()
        val state = store(server, shopId) ?: return emptyList()
        return state.all(kind)
            .filterKeys { it in from until to }
            .toSortedMap()
            .map { it.key to it.value }
    }

    /** 某个种类的总页数（至少 1 页：一件没上架也留一页空位等着服主填） */
    fun pageCount(server: MinecraftServer, kind: String, shopId: String = MAIN_SHOP_ID): Int {
        val custom = customShop(server, shopId)
        if (custom != null && !custom.type.allows(kind)) return 1
        return store(server, shopId)?.pageCount(kind) ?: 1
    }

    /** 把任意页号收进合法范围（客户端翻页参数不可信） */
    fun clampPage(page: Int): Int = page.coerceIn(0, MAX_INDEX / PointShopState.POINTS_SHOP_PAGE_SIZE)

    // ── 写（判 `points.shop` 节点）──

    /**
     * 上架 / 编辑一格。**编辑就是覆盖**（同一格写新值），也正因此界面回填必须完整 ——
     * 「什么都不改直接保存 = 什么都不变」是全覆盖式表单的铁律。
     *
     * @return null = 成功；否则是拒绝原因（调用方翻成提示语）
     */
    fun save(
        server: MinecraftServer,
        player: ServerPlayerEntity,
        index: Int,
        entry: ShopEntry,
        shopId: String = MAIN_SHOP_ID
    ): PointMarketError? {
        if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return PointMarketError.NO_PERMISSION
        if (index < 0 || index > MAX_INDEX) return PointMarketError.BAD_INDEX
        // ⚠ 自定义商店**不判启用状态**：关店只是「玩家不能买」，管理员照样要能进去补货 / 改内容
        val state = store(server, shopId) ?: return PointMarketError.SHOP_NOT_FOUND
        val custom = customShop(server, shopId)
        val currency = custom?.currency ?: MarketCurrency.POINTS
        // ⚠ 主货币的后端是 Int 口径：超过 Int.MAX_VALUE 的价格买不了（`CurrencyService.remove` 必失败）
        //   ⇒ 保存时就拒绝，别让服主挂一个永远卖不出去的价格
        if (currency == MarketCurrency.PRIMARY && entry.price > Int.MAX_VALUE.toLong()) {
            return PointMarketError.BAD_NUMBER
        }
        // 领取店：`stock` = 每期每人可领数量，**0 合法**（不可领），**不允许不限量**（留空 = 0）
        val claimMode = custom?.mode == CustomShopMode.CLAIM
        val rawEntry = if (claimMode && entry.stock == ShopEntry.UNLIMITED) entry.copy(stock = 0) else entry
        val normalized = normalize(rawEntry, server, allowZeroStock = claimMode) ?: return reject(entry)
        // 商店类型是一道**服务端**闸门：只卖物品的店，伪造一个精灵上架包也存不进去
        custom?.let {
            if (!it.type.allows(normalized.kind)) return PointMarketError.KIND_NOT_ALLOWED
        }
        // 格子冷却只对**自定义普通购买店**有意义：主商城 / 领取店一律清 0（不信客户端）
        val withCooldown = if (custom != null && custom.mode == CustomShopMode.NORMAL) normalized
        else normalized.copy(cooldownHours = 0)
        // ⚠ **每次保存都换一个全新的 id**（绝不信客户端传的）：语义上等于"旧的作废、换了一个新的"，
        //   玩家手里那份随之失效 —— 他再点购买会被 [buy] 拒绝，而不是按改后的内容 / 价格成交。
        //   ⚠ 用 UUID 而不是自增版本号：下架后重新上架会从 0 重来，与玩家手里旧的 0 撞上就漏了。
        val replaced = withCooldown.copy(
            entryId = java.util.UUID.randomUUID().toString(),
            // 上架者（只给交易账本的「卖家」列用）—— 记**本次操作者**：格子被接管改放时以最后一次为准
            creatorUuid = player.uuid.toString(),
            creatorName = player.name.string
        )
        // ⚠ kind 取自**规范化后的 entry**：精灵与物品各占一套独立的格子编号（2026-09-25 起，
        //   tab 变成真过滤），同一个 index 在两个 tab 下是两个不同的位置
        state.put(replaced.kind, index, replaced)
        // 交易品变更强制落盘（防杀进程/崩溃蒸发，见 PersistHelper）
        PersistHelper.requestSave(server)
        CobbleMarket.LOGGER.info(
            "Point shop[{}] slot {} set by {} (kind={}, price={}, stock={}, id={})",
            shopId, index, player.name.string, replaced.kind, replaced.price, replaced.stock, replaced.entryId
        )
        // 交易账本留痕：上架（与市场 / 拍卖同口径）。
        // ⚠ **不计入交易量**（额度/卡片资格只看玩家间成交，见 AuctionState 的「统计隔离」）——
        //   记账本只是为了可追溯，本函数**不碰** FinanceState 的任何计数。
        // ⚠ detail 列留空：商城卖的是**配方**（规格），不是现成的精灵/物品，没有 NBT 可重建
        //   （RecordDetail 那套用不上）。物种/物品 id 已经落在「精灵/物品」列里了。
        TransactionHistory.get(server).addRecord(
            TransactionRecord(
                timestamp = System.currentTimeMillis(),
                type = TransactionType.ADD,
                category = if (replaced.kind == ShopEntry.KIND_POKEMON) TransactionCategory.POKEMON
                else TransactionCategory.ITEM,
                sellerUuid = player.uuid,
                sellerName = player.name.string,
                buyerUuid = null,
                buyerName = "",
                species = speciesForHistory(replaced),
                // ⚠ 账本的 price 是 Int，而价格是 Long ⇒ 钳制转换（RP 价实际远达不到上限）
                price = replaced.price.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                fee = 0,
                detail = "",
                currency = currency,
                primaryType = primaryTypeForHistory(player, null, replaced.pokemon),
                shopId = shopId,
                // 客户端视角物品（商品规格重建）：历史界面按它渲染正确名字
                displayNbt = replaced.item?.let { spec ->
                    runCatching {
                        PointMarketFactory.createItems(spec, 1, server.registryManager)?.firstOrNull()
                            ?.encode(server.registryManager, net.minecraft.nbt.NbtCompound()) as? net.minecraft.nbt.NbtCompound
                    }.getOrNull()
                }
            )
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
        server: MinecraftServer,
        player: ServerPlayerEntity,
        kind: String,
        index: Int,
        stock: Int,
        shopId: String = MAIN_SHOP_ID
    ): PointMarketError? {
        if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return PointMarketError.NO_PERMISSION
        if (kind != ShopEntry.KIND_POKEMON && kind != ShopEntry.KIND_ITEM) return PointMarketError.BAD_KIND
        if (index < 0 || index > MAX_INDEX) return PointMarketError.BAD_INDEX
        val state = store(server, shopId) ?: return PointMarketError.SHOP_NOT_FOUND
        // 入库前归一：负数一律按「不限量」算（界面留空传的就是它），别把 -5 这种写进存档
        // ⚠ 领取店**不允许不限量**：留空 = 0（本期不开放），否则就是无限免费领
        val claimMode = customShop(server, shopId)?.mode == CustomShopMode.CLAIM
        val normalized = if (stock < 0) {
            if (claimMode) 0 else ShopEntry.UNLIMITED
        } else stock
        if (!state.setStock(kind, index, normalized)) return PointMarketError.NOT_FOUND
        PersistHelper.requestSave(server)
        CobbleMarket.LOGGER.info(
            "Point shop[{}] slot {} stock set to {} by {}", shopId, index, normalized, player.name.string
        )
        return null
    }

    /** 下架（清空一格）；返回 null = 成功。⚠ 要指明 [kind] —— 同一序号在两个 tab 下是两个格子 */
    fun remove(
        server: MinecraftServer,
        player: ServerPlayerEntity,
        kind: String,
        index: Int,
        shopId: String = MAIN_SHOP_ID
    ): PointMarketError? {
        if (!MarketPermissions.has(player, MarketPermissions.POINTS_SHOP)) return PointMarketError.NO_PERMISSION
        if (kind != ShopEntry.KIND_POKEMON && kind != ShopEntry.KIND_ITEM) return PointMarketError.BAD_KIND
        if (index < 0 || index > MAX_INDEX) return PointMarketError.BAD_INDEX
        val state = store(server, shopId) ?: return PointMarketError.SHOP_NOT_FOUND
        // ⚠ **先取再删**：remove 会把条目从表里拿掉，之后就读不到价格 / 物种 —— 账本那条就记不全了
        val removed = state.find(kind, index)
        if (!state.remove(kind, index)) return PointMarketError.NOT_FOUND
        PersistHelper.requestSave(server)
        CobbleMarket.LOGGER.info("Point shop[{}] slot {} removed by {}", shopId, index, player.name.string)
        // 交易账本留痕：下架（与市场 / 拍卖同口径；币种 RP、**不计入交易量**）
        if (removed != null) {
            TransactionHistory.get(server).addRecord(
                TransactionRecord(
                    timestamp = System.currentTimeMillis(),
                    type = TransactionType.CANCEL,
                    category = if (kind == ShopEntry.KIND_POKEMON) TransactionCategory.POKEMON
                    else TransactionCategory.ITEM,
                    sellerUuid = player.uuid,
                    sellerName = player.name.string,
                    buyerUuid = null,
                    buyerName = "",
                    species = speciesForHistory(removed),
                    price = removed.price.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    fee = 0,
                    detail = "",
                    currency = MarketCurrency.POINTS,
                    primaryType = primaryTypeForHistory(player, null, removed.pokemon),
                    shopId = shopId
                )
            )
        }
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
     * ⚠ **扣掉的钱直接销毁**（不进任何人的账）：商城由服主单方面经营、没有收款方 ——
     * 主货币与 RP 都是这个口径（RP 与「RP 手续费销毁」同源，见 `docs/points_plan.md`）。
     * ⚠ 本函数**不碰** `FinanceState.recordTrade` —— 那会污染紫卡/黑卡门槛与贷款额度。
     * ⚠ **总开关关闭时主商城照常可用**：它是唯一还能用 RP 的地方（不判 `pointsEnabled`）；
     *   **自定义（NPC）商店不在此列** —— 总开关关着时整体不可购买（2026-10-23 用户拍板）。
     *
     * @param count 购买数量。**精灵格恒按 1 算**（买一次就是一只，数量由请求几次表达）——
     *              不把这个自由度留给客户端；物品格才是玩家在购买窗填的那个数
     * @param shopId [MAIN_SHOP_ID] = 主商城；其余 = 自定义商店 id。
     *              自定义商店会额外校验：存在 / 已启用 / 玩家在绑定 NPC 的 8 格内
     *              （购买是独立于"打开界面"的包，必须在这里再判一次，见 [MAX_SHOP_DISTANCE]）
     * @return [PointMarketBuyResult.Success]（含造出来的实物，供发庆祝动画）/ [PointMarketBuyResult.Failure]
     */
    fun buy(
        server: MinecraftServer,
        player: ServerPlayerEntity,
        kind: String,
        index: Int,
        count: Int,
        expectedEntryId: String,
        shopId: String = MAIN_SHOP_ID
    ): PointMarketBuyResult {
        if (kind != ShopEntry.KIND_POKEMON && kind != ShopEntry.KIND_ITEM) {
            return PointMarketBuyResult.Failure(PointMarketError.BAD_KIND)
        }
        if (index < 0 || index > MAX_INDEX) return PointMarketBuyResult.Failure(PointMarketError.BAD_INDEX)
        // 自定义商店：先确认它存在、开着、玩家就在 NPC 旁边 —— 全在**扣钱之前**
        val custom = customShop(server, shopId)
        if (shopId != MAIN_SHOP_ID && custom == null) {
            return PointMarketBuyResult.Failure(PointMarketError.SHOP_NOT_FOUND)
        }
        if (custom != null) {
            // ⚠ **有意不判市场总开关**（`marketEnabled`）：停市不关 NPC 商店 —— 用户 2026-10-24 拍板
            //   「跟随市场总开关就管得太宽了」。这里只认店自己的营业开关（下一行），别补 marketBlocked。
            // 领取店没有价格、不能走购买流程（客户端点格子也走不到这里，这是服务端兜底）
            if (custom.mode == CustomShopMode.CLAIM) {
                return PointMarketBuyResult.Failure(PointMarketError.NOT_FOR_SALE)
            }
            // RP 总开关关着 ⇒ **RP 计价**的自定义商店不可购买（2026-10-23 用户报：关着还能从 NPC 商店买）
            // ⚠ 只拦 RP 店；主货币店与 RP 总开关无关。主 RP 商城是总开关关闭时唯一还能用 RP 的地方
            if (custom.currency == MarketCurrency.POINTS && !CobbleMarketConfig.pointsEnabled) {
                return PointMarketBuyResult.Failure(PointMarketError.POINTS_DISABLED)
            }
            if (!custom.enabled) return PointMarketBuyResult.Failure(PointMarketError.SHOP_DISABLED)
            if (!isNearBoundNpc(player, custom)) return PointMarketBuyResult.Failure(PointMarketError.TOO_FAR)
            // 隐藏的另一类商品不能被伪造包买走（客户端已经没有那个 tab 了，这里是最后一道）
            if (!custom.type.allows(kind)) {
                return PointMarketBuyResult.Failure(PointMarketError.KIND_NOT_ALLOWED)
            }
        }
        // 主 RP 商城被服主单独关闭（2026-10-25）：令牌包也买不了；自定义 NPC 商店不受影响
        if (custom == null && !CobbleMarketConfig.pointsMainShopEnabled) {
            return PointMarketBuyResult.Failure(PointMarketError.MAIN_SHOP_DISABLED)
        }
        // 有人正在编辑这家店（2026-10-26）：整店暂停购买 —— 客户端按钮已置灰，
        // 这里是防伪造包的服务端兜底；放在扣钱之前，分文未扣。
        if (ShopEditLock.isLocked(shopId)) {
            return PointMarketBuyResult.Failure(PointMarketError.SHOP_BEING_EDITED)
        }
        val state: ShopStore = custom ?: PointShopState.get(server)
        val entry = state.find(kind, index) ?: return PointMarketBuyResult.Failure(PointMarketError.NOT_FOUND)

        // ⚠ **付钱前一刻的强制检查**，必须紧跟在取到 entry 之后、早于算价 ——
        //   条目 id 对不上就说明这一格在玩家看它之后**换过内容**（换了精灵 / 改了价 / 下架重上），
        //   按新的成交等于骗人。此时直接拒绝，**分文未扣**。
        //   ⚠ 这条**不能靠界面关窗替代**：广播有网络延迟（服务端发的"已变更"要跑一个来回才到客户端），
        //   玩家完全可能在收到之前就点了确认。服务端是单线程的，两个包必然一先一后 ——
        //   「保存先到」那种顺序就是靠这一行挡在扣钱之前的（「购买先到」则按旧内容成交，也没问题）。
        if (expectedEntryId != entry.entryId) return PointMarketBuyResult.Failure(PointMarketError.STALE_ENTRY)

        // ── 冷却（2026-10-25；2026-10-27 加「商品各自冷却」）：只对自定义普通购买店 ──
        // 两层彼此独立：
        //  · 整店冷却（cooldownHours）：买成任意一格 ⇒ **全店**对该玩家锁定
        //  · 商品各自冷却（itemCooldownHours）：只有**买过的那一格**锁定；单格可用 entry.cooldownHours 覆盖（取长）
        // 该格实际 = max(整店剩余, 该格剩余)；其他格只看整店剩余。
        val now = System.currentTimeMillis()
        val shopCooldownHours = if (custom != null && custom.mode == CustomShopMode.NORMAL) custom.cooldownHours else 0
        val itemCooldownHours = if (custom != null && custom.mode == CustomShopMode.NORMAL) custom.itemCooldownHours else 0
        val entryCooldownHours = if (custom != null && custom.mode == CustomShopMode.NORMAL) entry.cooldownHours else 0
        val slotCooldownHours = maxOf(itemCooldownHours, entryCooldownHours)
        if (custom != null && custom.mode == CustomShopMode.NORMAL) {
            val cooldownState = CustomShopCooldownState.get(server)
            val shopRemaining = cooldownState.shopRemaining(
                custom.id, player.uuid, shopCooldownHours * 3_600_000L, now
            )
            val slotRemaining = cooldownState.slotRemaining(
                custom.id, player.uuid, entry.entryId, slotCooldownHours * 3_600_000L, now
            )
            val remaining = maxOf(shopRemaining, slotRemaining)
            if (remaining > 0L) {
                return PointMarketBuyResult.Failure(PointMarketError.ON_COOLDOWN, remaining)
            }
        }
        // 有冷却的商品一次只能买 1 件（即使库存无限）—— 否则一次买满会让冷却形同虚设
        val onePerPurchase = custom != null && custom.mode == CustomShopMode.NORMAL &&
            maxOf(shopCooldownHours, slotCooldownHours) > 0

        val amount = if (entry.kind == ShopEntry.KIND_POKEMON) 1 else count
        if (amount < 1 || amount > MAX_BUY_COUNT) return PointMarketBuyResult.Failure(PointMarketError.BAD_COUNT)
        if (onePerPurchase && amount != 1) {
            return PointMarketBuyResult.Failure(PointMarketError.ONE_PER_PURCHASE)
        }

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

        // **整店一个币种**：主商城（RP 商城）固定 RP；自定义商店用建店时选的币种
        val currency = custom?.currency ?: MarketCurrency.POINTS
        if (CurrencyService.balance(player, currency) < java.math.BigInteger.valueOf(total)) {
            return PointMarketBuyResult.Failure(PointMarketError.NOT_ENOUGH_FUNDS)
        }

        // ── 造货：在此之前什么都没碰；造不出来 = 数据坏了 → 整笔拒绝 ──
        val pokemon = if (entry.kind == ShopEntry.KIND_POKEMON) {
            val spec = entry.pokemon ?: return PointMarketBuyResult.Failure(PointMarketError.BAD_SPEC)
            // ⚠ **造货全程必须兜住异常**：它排在扣款之前，异常抛出去 = 整笔购买**静默死掉**
            //   （`server.execute` 把异常吞了 → 界面关了、钱没扣、连红字都没有）。
            //   2026-09-26「配了具体性格就买不了」就是这么被埋了半天的 —— 见 [PointMarketFactory.createPokemon]。
            //   兜住之后至少会变成一条**具体原因**的失败提示 + 服务端 warn。
            runCatching { PointMarketFactory.createPokemon(spec, player) }
                .onFailure {
                    CobbleMarket.LOGGER.warn("Point shop: createPokemon failed for {}: {}", spec.species, it)
                }
                .getOrNull()
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
        if (!CurrencyService.remove(player, currency, total)) {
            state.setStock(kind, index, entry.stock) // 回滚库存（entry 是扣减前的快照）
            return PointMarketBuyResult.Failure(PointMarketError.NOT_ENOUGH_FUNDS)
        }

        // ⚠ **交付之前先留一份副本**：`deliverItems` 里的 `insertStack` 是"转移"语义 ——
        //   栈塞进背包后**原对象就空了**（它自己就是用 `stack.isEmpty` 判断塞没塞完的）。
        //   而调用方还要拿这些实物去发庆祝动画（见 [PointMarketBuyResult.Success] 的 `items`），
        //   之前直接把它们交出去 ⇒ 拿到手的全是**空栈** ⇒ `ItemStack.encode` 对空栈抛异常 ⇒
        //   动画静默消失（2026-09-26 服务端日志抓到的 `Cannot encode empty ItemStack`）。
        //   与 memory 里"参考栈必须 copy，活引用会被扣空"是同一个坑。
        val deliveredItems = items.map { it.copy() }

        val delivered = if (pokemon != null) {
            // Party.add 在队伍满时会自动转入 PC，返回 false = 两边都满
            runCatching { Cobblemon.storage.getParty(player).add(pokemon) }.getOrDefault(false)
        } else {
            deliverItems(player, items)
        }
        if (!delivered) {
            // 预检过了还失败 = 极小概率（背包在两步之间被别的东西占了）——
            // 钱和库存都退回去。宁可这单不成，也不能让玩家吃亏
            // ⚠ 退款走 `creditSeller`：主货币进**待领余额**（物品本位时背包放不下也不会丢），RP 直接回余额
            CurrencyService.creditSeller(server, player.uuid, currency, total)
            state.setStock(kind, index, entry.stock)
            CobbleMarket.LOGGER.warn(
                "Point shop: delivery failed for {} on slot {}, rolled back", player.name.string, index
            )
            return PointMarketBuyResult.Failure(PointMarketError.NO_SPACE)
        }

        // ── 冷却：**只有真正交付成功**才记（失败 / 回滚都不算）──
        // 商店冷却记在「整店」上；格子冷却记在 entryId 上（下架重上换了新 id，旧冷却自然失效）
        if (custom != null && custom.mode == CustomShopMode.NORMAL) {
            val cooldownState = CustomShopCooldownState.get(server)
            if (shopCooldownHours > 0) cooldownState.recordShopPurchase(custom.id, player.uuid, now)
            if (slotCooldownHours > 0) {
                cooldownState.recordSlotPurchase(custom.id, player.uuid, entry.entryId, now)
            }
        }

        // ── 记账 + 落盘 ──
        // 明细写**商品是什么**（不是"第几格"）：服主对账时格子序号毫无意义
        val goods = when (entry.kind) {
            ShopEntry.KIND_POKEMON -> entry.pokemon?.species.orEmpty()
            else -> entry.item?.itemId.orEmpty()
        }
        // 自定义商店在账本里带上店名（zh）/ 店 id（en）—— 服主对账时能分清是哪个 NPC 的店
        val shopZh = custom?.let { "（${it.name}）" } ?: ""
        val shopEn = custom?.let { " [shop ${it.id}]" } ?: ""
        // RP 账本只记 RP 变动 —— 主货币的成交只进交易历史（见下），别混进 RP 的 CSV
        if (currency == MarketCurrency.POINTS) {
            PointsAuditLogger.log(
                PointsLogType.STORE_BUY,
                player.uuid,
                player.name.string,
                total,
                PointsState.get(server).getBalance(player.uuid),
                player.name.string,
                "商城购买$shopZh：$goods ×$amount（第 ${index + 1} 格）",
                "Store purchase$shopEn: $goods x$amount (slot ${index + 1})"
            )
        }
        PersistHelper.requestSave(server)
        CobbleMarket.LOGGER.info(
            "Point shop[{}]: {} bought slot {} x{} for {} {}",
            shopId, player.name.string, index, amount, total, currency.name
        )
        // 交易账本留痕：成交（币种随商店、**不计入交易量** —— 额度与卡片资格只看玩家间成交）。
        // ⚠ 商店成交的卖家 = **商店本身**，不是上架的管理员（2026-10-26 用户报：卖出曾进上架者的
        //   个人历史）。个人历史按 UUID 过滤 ⇒ 上架者只看到上架记录；买家仍按 buyerUuid 看到买入；
        //   完整记录只留在「全部交易历史」里。
        TransactionHistory.get(server).addRecord(
            TransactionRecord(
                timestamp = System.currentTimeMillis(),
                type = TransactionType.PURCHASE,
                category = if (entry.kind == ShopEntry.KIND_POKEMON) TransactionCategory.POKEMON
                else TransactionCategory.ITEM,
                sellerUuid = SHOP_SELLER_UUID,
                // 主商城没有店名 ⇒ CSV 卖家列留空（来源列 = shop）；自定义商店写店名
                sellerName = custom?.name.orEmpty(),
                buyerUuid = player.uuid,
                buyerName = player.name.string,
                species = speciesForHistory(entry),
                price = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                fee = 0,
                detail = detailForHistory(server, pokemon, deliveredItems),
                currency = currency,
                primaryType = primaryTypeForHistory(player, pokemon, null),
                // 标记来源：界面据此把卖家列画成店名 /「▣ 商城」（上架 / 下架那两条**不标**）
                fromShop = true,
                shopName = custom?.name.orEmpty(),
                shopId = shopId,
                // 客户端视角物品（商品规格是客户端上架时发来的那份）：历史界面按它渲染正确名字。
                // 精灵商品没有物品栈 ⇒ null（历史界面按物种 key 显示）
                displayNbt = deliveredItems.firstOrNull()?.let { stack ->
                    runCatching {
                        stack.encode(server.registryManager, net.minecraft.nbt.NbtCompound()) as? net.minecraft.nbt.NbtCompound
                    }.getOrNull()
                }
            )
        )
        // 把造出来的实物一并交出去：调用方要拿它发庆祝动画（概率闪光只有此刻才确定）。
        // ⚠ 交的是**交付前留的副本**，不是 `items` 本身（那边已经被交付消耗成空栈了，见上）
        return PointMarketBuyResult.Success(
            pokemon = pokemon, items = deliveredItems, count = amount, total = total
        )
    }

    /**
     * **定期领取店：一键领取全部**（2026-10-23 用户拍板）。
     *
     * 口径：
     * - **免费**：不扣任何货币；RP 总开关与币种都与此无关
     * - 每个玩家每格每期可领 `stock` 个（**每期每人**，不消耗全服库存）
     * - **滚动冷却**：第一次真正领到东西时开始计时，满 `claimHours` 小时重置
     * - **不叠加**：过期未领的部分直接作废（[CustomShopClaimState] 重置时清空）
     * - **装不下就留在店里**：本次只交付放得下的部分，剩余本期还能再领；刷新后作废
     *
     * @return 实际交付数量 + 是否有剩余 + 本次调用前是否有可领内容（区分"空间不足"与"没东西可领"）
     */
    fun claimAll(
        server: MinecraftServer,
        player: ServerPlayerEntity,
        shopId: String
    ): PointMarketClaimResult {
        val shop = customShop(server, shopId) ?: return PointMarketClaimResult.Failure(PointMarketError.SHOP_NOT_FOUND)
        if (shop.mode != CustomShopMode.CLAIM) {
            return PointMarketClaimResult.Failure(PointMarketError.NOT_A_CLAIM_SHOP)
        }
        if (!shop.enabled) return PointMarketClaimResult.Failure(PointMarketError.SHOP_DISABLED)
        if (!isNearBoundNpc(player, shop)) return PointMarketClaimResult.Failure(PointMarketError.TOO_FAR)

        val now = System.currentTimeMillis()
        val periodMs = shop.claimHours.coerceIn(1, MAX_CLAIM_HOURS) * 3_600_000L
        val state = CustomShopClaimState.get(server)
        // 过期会在这里原地重置（清空已领计数，不累积）
        val period = state.periodFor(shop.id, player.uuid, now, periodMs)

        var deliveredTotal = 0
        var leftBehind = false
        var hadRemaining = false
        // 物品交付明细（给客户端的飞入动画）；精灵不记 —— 用户 2026-10-23 定「精灵不做动画」
        val claimedItems = ArrayList<ClaimedItem>()
        // 实际交付的精灵（只用于「领取到闪光」全服播报；普通精灵不占）
        val deliveredPokemon = ArrayList<Pokemon>()
        for (kind in listOf(ShopEntry.KIND_POKEMON, ShopEntry.KIND_ITEM)) {
            if (!shop.type.allows(kind)) continue
            shop.all(kind).toSortedMap().forEach { (_, entry) ->
                val quota = entry.stock
                if (quota <= 0) return@forEach
                val remaining = state.remaining(period, entry.entryId, quota)
                if (remaining <= 0) return@forEach
                hadRemaining = true
                val delivered = if (kind == ShopEntry.KIND_POKEMON) {
                    deliverClaimPokemon(player, entry, remaining, deliveredPokemon)
                } else {
                    deliverClaimItems(server, player, entry, remaining)
                }
                if (delivered > 0) {
                    state.recordClaim(shop.id, player.uuid, period, entry.entryId, delivered, now)
                    deliveredTotal += delivered
                    if (kind == ShopEntry.KIND_ITEM) claimedItems += ClaimedItem(entry.entryId, delivered)
                    // 交易账本留痕：领取（**不是卖出**）。只记实际交付的数量；
                    // 卖家 = 商店本身（零 UUID）⇒ 不进管理员个人历史，只进领取者个人历史 + 全服历史。
                    // detail 写 count= 供历史界面显示 ×N（精灵的领取也记，界面按 CLAIM 显示）。
                    TransactionHistory.get(server).addRecord(
                        TransactionRecord(
                            timestamp = System.currentTimeMillis(),
                            type = TransactionType.CLAIM,
                            category = if (kind == ShopEntry.KIND_POKEMON) TransactionCategory.POKEMON
                            else TransactionCategory.ITEM,
                            sellerUuid = SHOP_SELLER_UUID,
                            sellerName = shop.name,
                            buyerUuid = player.uuid,
                            buyerName = player.name.string,
                            species = speciesForHistory(entry),
                            price = 0,
                            fee = 0,
                            detail = "count=$delivered",
                            currency = shop.currency,
                            primaryType = primaryTypeForHistory(player, null, entry.pokemon),
                            fromShop = true,
                            shopName = shop.name,
                            shopId = shop.id
                        )
                    )
                }
                if (delivered < remaining) leftBehind = true
            }
        }
        if (deliveredTotal > 0) PersistHelper.requestSave(server)
        CobbleMarket.LOGGER.info(
            "Claim shop[{}]: {} claimed {} reward(s){}",
            shop.id, player.name.string, deliveredTotal, if (leftBehind) " (inventory full, remainder kept)" else ""
        )
        return PointMarketClaimResult.Success(
            deliveredTotal, leftBehind, hadRemaining, claimedItems,
            // 只留闪光：普通精灵在聊天里没有播报，留着只是白占对象
            deliveredPokemon.filter { it.shiny }
        )
    }

    /**
     * 领取精灵：最多 [count] 只；队伍满会自动进 PC，两边都满就停（剩余留在店里）。
     * 实际交付的精灵追加到 [delivered]（闪光播报要用；由调用方清空 / 读取）。
     */
    private fun deliverClaimPokemon(
        player: ServerPlayerEntity,
        entry: ShopEntry,
        count: Int,
        delivered: MutableList<Pokemon>
    ): Int {
        val spec = entry.pokemon ?: return 0
        var deliveredCount = 0
        repeat(count) {
            val pokemon = runCatching { PointMarketFactory.createPokemon(spec, player) }
                .onFailure { CobbleMarket.LOGGER.warn("Claim shop: createPokemon failed for {}: {}", spec.species, it) }
                .getOrNull() ?: return deliveredCount
            val ok = runCatching { Cobblemon.storage.getParty(player).add(pokemon) }.getOrDefault(false)
            if (!ok) return deliveredCount
            delivered += pokemon
            deliveredCount++
        }
        return deliveredCount
    }

    /** 领取物品：最多 [count] 件；只放得下多少算多少（剩余留在店里） */
    private fun deliverClaimItems(server: MinecraftServer, player: ServerPlayerEntity, entry: ShopEntry, count: Int): Int {
        val spec = entry.item ?: return 0
        val stacks = PointMarketFactory.createItems(spec, count, server.registryManager) ?: return 0
        var delivered = 0
        for (stack in stacks) {
            val before = stack.count
            player.inventory.insertStack(stack)
            // `insertStack` 部分放入也返回 true，只能看栈的剩余量（同 CurrencyHandler.give 的写法）
            delivered += before - stack.count
        }
        return delivered
    }

    /** 队伍或 PC 还有空位吗（`add` 会自动溢出到 PC，所以两边都满才是真放不下） */
    /**
     * 交易账本的「精灵/物品」列：精灵存**翻译 key**（全项目口径，见 [SpeciesText.translationKeyOf]）、
     * 物品存 itemId。
     *
     * ⚠ 商城的配置里存的是**物种 ID**（`cobblemon:pikachu`），直接写进账本会让界面显示底层 ID，
     * 而且 CSV 回读后的值与内存里那条对不上 ⇒ 去重失效、一条交易显示成两条。
     */
    private fun speciesForHistory(entry: ShopEntry): String =
        entry.pokemon?.species?.let { SpeciesText.translationKeyOf(it) } ?: entry.item?.itemId.orEmpty()

    /**
     * 精灵名的**属性色**（形态感知 —— 阿罗拉六尾是冰系）：与市场 / 拍卖 / 求购同一个口径
     *（`cobblemon.type.<主属性>`），界面按它上色。
     *
     * 购买时手上有造好的精灵、直接取；**上架 / 下架只有配置**（没有精灵对象），而主属性
     * **与形态有关** ⇒ 照 [PointMarketFactory.createPokemon] 造一只来问（纯计算、用完即弃，
     * 服主上架本来就不是高频操作）。取不到就空着 —— 只是名字无色，不影响成交与账本。
     */
    private fun primaryTypeForHistory(player: ServerPlayerEntity, pokemon: Pokemon?, spec: ShopPokemonSpec?): String {
        val resolved = pokemon
            ?: spec?.let { runCatching { PointMarketFactory.createPokemon(it, player) }.getOrNull() }
            ?: return ""
        return "cobblemon.type.${resolved.primaryType.name.lowercase()}"
    }

    /**
     * 交易账本的**「详情」列**（与市场 / 拍卖 / 求购同口径）：精灵记完整数值、物品记 NBT 与稀有度。
     *
     * ⚠ 物品这一列**不能空**：历史界面的物品名**稀有度色全靠它** —— `RecordDetail.rarityFromDetail`
     *   只从详情列里抠 `rarity=` 那一段（界面拿到的 DTO 里没有单独的稀有度字段）。
     *   2026-09-28 用户实测报「商城的记录名字没有颜色」，根因就是这里原先写死空串。
     * ⚠ 商城的货是**当场造的**，NBT 得现取：精灵 `saveToNBT`、物品 `encode`
     *  （传 `deliveredItems` 而不是 `items` —— 后者交付时已被扣成空栈，见 [buy] 里的注释）。
     *   取不到就空着：记账**不该拖垮成交**。
     */
    private fun detailForHistory(server: MinecraftServer, pokemon: Pokemon?, items: List<ItemStack>): String =
        try {
            if (pokemon != null) {
                RecordDetail.pokemon(
                    pokemon.saveToNBT(server.registryManager, NbtCompound()),
                    server, emptyMap(), pokemon.level, pokemon.shiny
                )
            } else {
                val stack = items.firstOrNull()
                val nbt = stack?.encode(server.registryManager) as? NbtCompound
                if (nbt == null) "" else RecordDetail.item(nbt, items.sumOf { it.count }, server.registryManager)
            }
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Point shop: failed to build history detail: {}", e.message)
            ""
        }

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
    private fun normalize(
        entry: ShopEntry, server: MinecraftServer, allowZeroStock: Boolean = false
    ): ShopEntry? {
        if (entry.price < 0L) return null
        // 格子冷却：0 = 跟随商店 / 不冷却；只允许 0~一年，坏值整条拒绝
        if (entry.cooldownHours !in 0..MAX_COOLDOWN_HOURS) return null
        // 普通购买店：0 是**售罄后的展示值**，不是上架时能设的库存（要限量就 ≥1，不限量用 -1）
        // 定期领取店（[allowZeroStock]）：0 = 本期不可领，是合法配置；-1 在调用方已折算成 0
        val minStock = if (allowZeroStock) 0 else 1
        if (entry.stock != ShopEntry.UNLIMITED && entry.stock < minStock) return null
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

        // 初始训练家（配信假训练家）：trim 后为空 = 没设（生成时初训家还是买家本人）；
        // 超长 / 含 `§`（颜色注入，会污染界面上所有显示这个名字的地方）**整条拒绝** ——
        // 与其它字段同一个口径：不做"尽力修补"（见 [normalize] 注释）
        val originalTrainer = spec.originalTrainer?.trim()?.takeIf { it.isNotEmpty() }
        if (originalTrainer != null &&
            (originalTrainer.length > MAX_OT_LENGTH || originalTrainer.contains('§'))
        ) return null

        return spec.copy(species = speciesId, originalTrainer = originalTrainer)
    }

    /**
     * 物品规格：物品 id 必须存在；组件快照走 [ItemRuleComponents.sanitizeFullSpec] **重建校验** ——
     * 客户端塞什么组件进来都不算数，服务端重建得出来的才算。
     *
     * ⚠⭐ **完整保留全部组件**（2026-10-02 用户拍板：「小手按钮应该要做到完完整整的复制……
     *   不然物品商城的功能就算不完整」）：商城卖的是**实物**，「插件 / 升级数据」这类
     *   模组自定义组件也要跟着商品走。**重建校验仍在**：畸形 NBT 重建失败 ⇒ 整条拒绝。
     * ⚠ 与规则条目（黑名单 / 价格限制 / 求购单）那套**白名单**口径**有意不同**，别"统一"掉
     *   （那边存的是匹配条件，白名单外的组件会变成怪规则 —— 见 [ItemRuleComponents] 的注释）。
     */
    private fun normalizeItem(spec: ShopItemSpec, server: MinecraftServer): ShopItemSpec? {
        val identifier = Identifier.tryParse(spec.itemId) ?: return null
        if (Registries.ITEM.getOrEmpty(identifier).isEmpty) return null
        val sanitized = ItemRuleComponents.sanitizeFullSpec(
            spec.itemId, spec.componentsSpec, server.registryManager
        )
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
