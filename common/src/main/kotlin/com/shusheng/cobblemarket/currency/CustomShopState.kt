package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

/**
 * **自定义商店出售什么**（2026-10-20 用户要求：只卖物品的 NPC 不该看到精灵 tab）。
 *
 * - [POKEMON] / [ITEM]：单一类型 —— 界面**不显示 tab**，直接是该类的网格，编辑窗也只会是那一类
 * - [BOTH]：两类都卖 —— 保留原来的精灵 / 物品双 tab
 *
 * ⚠ NBT 存 `type` 字符串；**缺字段 = [BOTH]**（旧测试店 / 手改存档都能读）。
 * ⚠ 服务端也按它拦：只卖物品的店，伪造一个精灵上架 / 购买包会被拒（不只靠客户端隐藏 tab）。
 */
enum class CustomShopType(val id: String) {
    POKEMON("pokemon"),
    ITEM("item"),
    BOTH("both");

    /** 这个类型是否允许 [kind]（[ShopEntry.KIND_POKEMON] / [ShopEntry.KIND_ITEM]） */
    fun allows(kind: String): Boolean = when (this) {
        POKEMON -> kind == ShopEntry.KIND_POKEMON
        ITEM -> kind == ShopEntry.KIND_ITEM
        BOTH -> true
    }

    companion object {
        /** 宽松解析（存档 / 网络都可能给出奇怪值）：认不出就按 [BOTH]，不崩 */
        fun fromId(id: String?): CustomShopType =
            values().firstOrNull { it.id == id?.trim()?.lowercase() } ?: BOTH
    }
}

/** 领取周期默认 / 上限（小时）：24 = 每日，8760 = 一年（够覆盖「每月」720） */
const val DEFAULT_CLAIM_HOURS: Int = 24
const val MAX_CLAIM_HOURS: Int = 8760

/**
 * **商店的经营模式**（2026-10-23 用户要求）：
 *
 * - [NORMAL]：普通购买店（用 [CustomShop.currency] 结账）
 * - [CLAIM]：**定期领取店** —— 商品免费，每个玩家每 [CustomShop.claimHours] 小时
 *   可以领走各格的数量；领到的数量不叠加（过期未领的部分作废）。
 *
 * ⚠ 与 [CustomShopType]（卖什么）是**两根独立的轴**：领取店也能选精灵 / 物品 / 两者。
 */
enum class CustomShopMode(val id: String) {
    NORMAL("normal"),
    CLAIM("claim");

    companion object {
        /** 宽松解析（存档 / 网络）：认不出就按普通购买店，不崩 */
        fun fromId(raw: String?): CustomShopMode =
            values().firstOrNull { it.id == raw?.trim()?.lowercase() } ?: NORMAL

        /** 请求校验：非法值返回 null，调用方必须拒绝（防篡改） */
        fun fromRequest(raw: String?): CustomShopMode? =
            values().firstOrNull { it.id == raw?.trim()?.lowercase() }
    }
}

/**
 * **一个自定义（NPC）商店**。
 *
 * 与主商城的 [PointShopState] **结构同构**：精灵 / 物品各占一套独立的格子编号、空格子不进表、
 * 售罄格保留、`-1` = 无限量 —— 所以直接实现 [ShopStore]，服务层的购买 / 上架 / 校验逻辑
 * 一份就够（[PointMarketService] 只认接口）。
 *
 * 额外五样：
 * - [name]：显示在商城标题上的名字（主商城用固定词条，自定义商店用这个）
 * - [type]：只卖精灵 / 只卖物品 / 两者都卖（见 [CustomShopType]）
 * - [currency]：结算币种（主货币 / RP）—— **建店时选定，有商品之后不可改**（改币种等于把价格全部重新解释）
 * - [mode] / [claimHours]：普通购买 / 定期领取，以及领取周期（小时）—— 模式建店时选定，
 *   周期可以随时改（不改变任何价格语义）
 * - [npcUuids]：绑定到哪些 NPC；玩家右键这些实体即打开本商店（一个商店可以绑多个）
 *
 * ⚠ 条目的序列化**复用** `writeShopEntryBody` / `readShopEntryBody`（与主商城同一份）——
 * 两处各写一遍，加字段时漏一处就会静默丢数据（本项目反复踩过）。
 */
class CustomShop internal constructor(
    private val owner: CustomShopState,
    val id: String,
    var name: String,
    var enabled: Boolean = true,
    var type: CustomShopType = CustomShopType.BOTH,
    var currency: MarketCurrency = MarketCurrency.POINTS,
    var mode: CustomShopMode = CustomShopMode.NORMAL,
    /** 领取周期（小时）：24 = 每日、168 = 每周、720 = 每月（**滚动冷却**，从该玩家第一次领取开始算） */
    var claimHours: Int = DEFAULT_CLAIM_HOURS
) : ShopStore {

    /** 种类 → (格子序号 → 商品)。空格子不存（省存档空间），序号本身即位置 */
    private val slots = mutableMapOf<String, MutableMap<Int, ShopEntry>>()

    /** 绑定到本商店的 NPC 实体 UUID（顺序无意义，用 LinkedHashSet 让 list 输出稳定） */
    internal val npcUuids = linkedSetOf<UUID>()

    /**
     * 绑定时的显示信息（类型 id + 自定义名）—— 管理界面离线也要能画出头像 / 名字。
     * ⚠ 实体不在玩家所在世界 / 区块未加载时，只靠 UUID 查不到实体类型；这份元信息是兜底。
     * 旧绑定（加这个字段之前）没有元信息，管理端会尝试现场解析实体，解析不到就显示占位。
     */
    internal val npcMeta = mutableMapOf<UUID, CustomShopNpcMeta>()

    private fun slotsOf(kind: String): MutableMap<Int, ShopEntry> = slots.getOrPut(kind) { mutableMapOf() }

    /** 任何改动都要让存档知道 —— 否则只会在正常关服时落盘，杀进程/崩溃会丢 */
    private fun changed() = owner.markChanged()

    override fun find(kind: String, index: Int): ShopEntry? = slots[kind]?.get(index)

    override fun all(kind: String): Map<Int, ShopEntry> = slots[kind] ?: emptyMap()

    override fun put(kind: String, index: Int, entry: ShopEntry) {
        slotsOf(kind)[index] = entry
        changed()
    }

    override fun remove(kind: String, index: Int): Boolean {
        if (slots[kind]?.remove(index) == null) return false
        changed()
        return true
    }

    override fun consume(kind: String, index: Int, amount: Int): Boolean {
        if (amount <= 0) return false
        val bucket = slots[kind] ?: return false
        val entry = bucket[index] ?: return false
        if (entry.stock == ShopEntry.UNLIMITED) return true
        if (entry.stock < amount) return false
        bucket[index] = entry.copy(stock = entry.stock - amount)
        changed()
        return true
    }

    override fun setStock(kind: String, index: Int, amount: Int): Boolean {
        val bucket = slots[kind] ?: return false
        val entry = bucket[index] ?: return false
        bucket[index] = entry.copy(stock = amount)
        changed()
        return true
    }

    /** 这个商店里已上架的条目总数（`/market shop delete` 回执用 —— 删除会连隐藏的一起删） */
    fun entryCount(): Int = slots.values.sumOf { it.size }

    /** **界面上看得见**的条目数（单类型店只算那一类；`/market shop list` 显示用） */
    fun visibleEntryCount(): Int = when (type) {
        CustomShopType.POKEMON -> all(ShopEntry.KIND_POKEMON).size
        CustomShopType.ITEM -> all(ShopEntry.KIND_ITEM).size
        CustomShopType.BOTH -> entryCount()
    }

    /** 绑定的 NPC 数量（`/market shop list` 显示用） */
    fun npcCount(): Int = npcUuids.size

    /** 某个 NPC 的显示信息（没有记录 = 旧绑定 / 手改存档） */
    fun npcMetaOf(uuid: UUID): CustomShopNpcMeta? = npcMeta[uuid]

    /** 全部绑定的显示信息（管理端列表用；顺序跟 [npcUuids] 一致） */
    fun npcMetaEntries(): List<Pair<UUID, CustomShopNpcMeta?>> =
        npcUuids.map { it to npcMeta[it] }

    /** 精灵 / 物品各自的条目数（管理端悬停窗用，含被类型隐藏的另一类） */
    fun pokemonCount(): Int = all(ShopEntry.KIND_POKEMON).size
    fun itemCount(): Int = all(ShopEntry.KIND_ITEM).size
}

/**
 * 绑定 NPC 时记下的显示信息：实体类型 id + 自定义名 + 模型信息。
 *
 * [species] / [aspects] 只有宝可梦才有（其它实体为空）：物种决定画哪只模型、显示哪个名字，
 * aspects 决定闪光 / 地区形态。**新 NBT 键、旧档缺省为空**，读取端按空值落默认。
 */
data class CustomShopNpcMeta(
    val typeId: String,
    val customName: String = "",
    val species: String = "",
    val aspects: String = ""
)

/** 自定义商店的操作结果（命令侧翻成中英提示） */
enum class CustomShopError {
    /** id 不合法（空 / 超长 / 含非法字符 / 用了保留字 `main`） */
    INVALID_ID,
    /** id 已存在 */
    EXISTS,
    /** 商店数量达到上限 */
    TOO_MANY,
    /** 名字不合法（超长 / 含 `§`） */
    INVALID_NAME,
    /** 商店不存在 */
    NOT_FOUND,
    /** 该实体已经绑在别的商店上了（一个 NPC 只服务一个商店） */
    ALREADY_BOUND,
    /** 一个商店绑的 NPC 数量达到上限（防手抖把全世界的实体都绑进来） */
    TOO_MANY_NPCS,

    // ── 管理界面（2026-10-21）──

    /** 类型字符串认不出来（`pokemon` / `item` / `both` 之外的值） */
    INVALID_TYPE,
    /** 要绑定的实体不在了 / 不在玩家所在世界 / 区块没加载 */
    NPC_NOT_FOUND,
    /** 要绑定的实体离玩家太远（管理界面只列 16 格内的） */
    NPC_TOO_FAR,
    /** 币种字符串认不出来（`primary` / `points` 之外的值） */
    INVALID_CURRENCY,
    /** 店里还有商品，不能改币种（改币种 = 把价格全部重新解释） */
    NOT_EMPTY,
    /** 模式字符串认不出来（`normal` / `claim` 之外的值） */
    INVALID_MODE,
    /** 领取周期不合法（1 ~ 8760 小时） */
    INVALID_PERIOD
}

/**
 * **自定义（NPC）商店存档**（`cobblemarket_custom_shops.dat`）。
 *
 * ⚠ **新 NBT 键 / 新文件**：主商城的 [PointShopState] 一个字节都不动 —— 老存档里没有这个文件，
 * 读出来就是空表，天然满足「升级不损坏旧存档」的铁律。
 *
 * 读取逐条容错：单个商店 / 单个格子损坏只跳过它并 warn，绝不整体抛异常。
 */
class CustomShopState private constructor() : PersistentState() {

    /** id → 商店（用 LinkedHashMap 保持创建顺序，`/market shop list` 输出稳定） */
    private val shops = linkedMapOf<String, CustomShop>()

    /**
     * 读档期间为 true：[CustomShop.put] 不该把「刚从盘里读出来的数据」标成脏（否则每次启动都多写一次盘）。
     * ⚠ **默认 false**：没有存档文件时走构造器那条路，不会进读取 lambda ——
     *   默认 true 的话新世界的商店改什么都标不脏、关服才会落盘。
     */
    private var loading = false

    // ── 读 ──

    /** 按 id 取商店；大小写 / 首尾空格按创建规则统一（命令手输大写也认） */
    fun find(id: String): CustomShop? = shops[id.trim().lowercase()]

    fun all(): List<CustomShop> = shops.values.toList()

    /** 某个实体绑到了哪个商店（没绑 = null）。右键交互用它查 */
    fun shopForNpc(uuid: UUID): CustomShop? = shops.values.firstOrNull { uuid in it.npcUuids }

    // ── 写（服务端命令 / 交互；权限由调用方判） ──

    /** 新建商店；返回 null = 成功。默认 [CustomShopType.BOTH]；货币默认主货币；模式默认普通购买 */
    fun create(
        rawId: String,
        rawName: String,
        type: CustomShopType = CustomShopType.BOTH,
        currency: MarketCurrency = MarketCurrency.PRIMARY,
        mode: CustomShopMode = CustomShopMode.NORMAL,
        claimHours: Int = DEFAULT_CLAIM_HOURS
    ): CustomShopError? {
        val id = normalizeId(rawId)
        if (!SHOP_ID_PATTERN.matches(id) || id == MAIN_SHOP_ID) return CustomShopError.INVALID_ID
        if (shops.containsKey(id)) return CustomShopError.EXISTS
        if (shops.size >= MAX_SHOPS) return CustomShopError.TOO_MANY
        if (claimHours !in 1..MAX_CLAIM_HOURS) return CustomShopError.INVALID_PERIOD
        val name = normalizeName(rawName, id) ?: return CustomShopError.INVALID_NAME
        shops[id] = CustomShop(
            this, id, name, type = type, currency = currency, mode = mode, claimHours = claimHours
        )
        markDirty()
        return null
    }

    /**
     * 改领取周期（小时）；返回 null = 成功。
     *
     * ⚠ 周期只影响"下一次刷新何时到"（滚动冷却），**不改变任何价格 / 库存语义** ⇒ 随时可改。
     * 普通购买店也能改，但界面不会用到。
     */
    fun setClaimHours(id: String, hours: Int): CustomShopError? {
        val shop = shops[normalizeId(id)] ?: return CustomShopError.NOT_FOUND
        if (hours !in 1..MAX_CLAIM_HOURS) return CustomShopError.INVALID_PERIOD
        shop.claimHours = hours
        markDirty()
        return null
    }

    /**
     * 改一家店的结算币种；返回 null = 成功。
     *
     * ⚠ **只允许空店改**：已有商品时改币种 = 把每个价格数字重新解释成另一种货币
     * （100 RP 变 100 主货币），必然出事 —— 要么删商品重上，要么删店重建。
     */
    fun setCurrency(id: String, currency: MarketCurrency): CustomShopError? {
        val shop = shops[normalizeId(id)] ?: return CustomShopError.NOT_FOUND
        if (shop.entryCount() > 0) return CustomShopError.NOT_EMPTY
        if (shop.currency == currency) return null
        shop.currency = currency
        markDirty()
        return null
    }

    /** 删除商店（连同它的全部条目与 NPC 绑定）；返回是否真的删掉了 */
    fun delete(id: String): Boolean {
        if (shops.remove(normalizeId(id)) == null) return false
        markDirty()
        return true
    }

    /** 改名；返回 null = 成功 */
    fun rename(id: String, rawName: String): CustomShopError? {
        val shop = shops[normalizeId(id)] ?: return CustomShopError.NOT_FOUND
        val name = normalizeName(rawName, shop.id) ?: return CustomShopError.INVALID_NAME
        shop.name = name
        markDirty()
        return null
    }

    /**
     * 设置商店出售的类型。
     * ⚠ **不删除另一类商品**：切到单类型后它们只是**隐藏**（服务端也不会再卖 / 上架），
     *   切回 [CustomShopType.BOTH] 会原样出现 —— 切类型不该顺手毁掉服主配好的货。
     */
    fun setType(id: String, type: CustomShopType): CustomShopError? {
        val shop = shops[normalizeId(id)] ?: return CustomShopError.NOT_FOUND
        if (shop.type == type) return null
        shop.type = type
        markDirty()
        return null
    }

    /** 开店 / 关店（关掉后右键不再打开、购买也会被拒） */
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val shop = shops[normalizeId(id)] ?: return false
        if (shop.enabled == enabled) return true
        shop.enabled = enabled
        markDirty()
        return true
    }

    /**
     * 把一个实体绑到商店上。
     * ⚠ **一个实体只服务一个商店**：已经绑在别处时返回 [CustomShopError.ALREADY_BOUND]，
     *   调用方提示"先解绑" —— 自动改绑会让人在不知情的情况下搬走别人的商店入口。
     */
    fun bind(
        id: String,
        uuid: UUID,
        typeId: String = "",
        customName: String = "",
        species: String = "",
        aspects: String = ""
    ): CustomShopError? {
        val shop = shops[normalizeId(id)] ?: return CustomShopError.NOT_FOUND
        val existing = shopForNpc(uuid)
        if (existing != null) {
            if (existing.id != shop.id) return CustomShopError.ALREADY_BOUND
            // 已绑在本店：允许刷新元信息（重新 bind 一遍不该报错，也不该丢掉新名字）
            if (hasMeta(typeId, customName, species, aspects)) {
                shop.npcMeta[uuid] = CustomShopNpcMeta(typeId, customName, species, aspects)
                markDirty()
            }
            return null
        }
        if (shop.npcUuids.size >= MAX_NPCS_PER_SHOP) return CustomShopError.TOO_MANY_NPCS
        shop.npcUuids.add(uuid)
        if (hasMeta(typeId, customName, species, aspects)) {
            shop.npcMeta[uuid] = CustomShopNpcMeta(typeId, customName, species, aspects)
        }
        markDirty()
        return null
    }

    /** 全是空值 = 没东西可记（别在存档里塞空条目） */
    private fun hasMeta(typeId: String, customName: String, species: String, aspects: String): Boolean =
        typeId.isNotEmpty() || customName.isNotEmpty() || species.isNotEmpty() || aspects.isNotEmpty()

    /** 解绑某个实体（从所有商店里移除）；返回它原本所在的商店 */
    fun unbind(uuid: UUID): CustomShop? {
        var removedFrom: CustomShop? = null
        shops.values.forEach { shop ->
            if (shop.npcUuids.remove(uuid)) {
                shop.npcMeta.remove(uuid)
                removedFrom = shop
                markDirty()
            }
        }
        return removedFrom
    }

    /** [CustomShop] 改条目后回调（`markDirty` 在 PersistentState 里是 protected） */
    internal fun markChanged() {
        if (!loading) markDirty()
    }

    // ── 校验 / 规范化 ──

    private fun normalizeId(raw: String): String = raw.trim().lowercase()

    /** trim 后为空 = 用 id 当名字；超长 / 含 `§` 一律拒绝（颜色注入会污染所有显示这个名字的地方） */
    private fun normalizeName(raw: String, fallbackId: String): String? {
        val trimmed = raw.trim()
        val name = trimmed.ifEmpty { fallbackId }
        if (name.length > MAX_NAME_LENGTH || name.contains('§')) return null
        return name
    }

    // ── 序列化 ──

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        shops.values.forEach { shop ->
            val c = NbtCompound()
            c.putString("id", shop.id)
            c.putString("name", shop.name)
            // 默认是开的 —— 只在关店时写，省空间（读取端缺字段落 true）
            if (!shop.enabled) c.putBoolean("enabled", false)
            // 默认是 both —— 只在单类型时写（读取端缺字段落 BOTH）
            if (shop.type != CustomShopType.BOTH) c.putString("type", shop.type.id)
            // 默认 POINTS（缺字段 = 老店，它们都是 RP 店）；只在非 RP 时写，省空间
            if (shop.currency != MarketCurrency.POINTS) c.putString("currency", shop.currency.name)
            // 默认 NORMAL / 24 小时；只在非默认时写
            if (shop.mode != CustomShopMode.NORMAL) c.putString("mode", shop.mode.id)
            if (shop.claimHours != DEFAULT_CLAIM_HOURS) c.putInt("claimHours", shop.claimHours)
            if (shop.npcUuids.isNotEmpty()) {
                putStringList(c, "npcUuids", shop.npcUuids.map { it.toString() })
            }
            // NPC 显示信息（新键；旧存档没有 = 空表，管理端现场解析实体兜底）
            val metaList = NbtList()
            shop.npcMetaEntries().forEach { (uuid, meta) ->
                if (meta == null) return@forEach
                val mc = NbtCompound()
                mc.putString("uuid", uuid.toString())
                if (meta.typeId.isNotEmpty()) mc.putString("typeId", meta.typeId)
                if (meta.customName.isNotEmpty()) mc.putString("name", meta.customName)
                if (meta.species.isNotEmpty()) mc.putString("species", meta.species)
                if (meta.aspects.isNotEmpty()) mc.putString("aspects", meta.aspects)
                metaList.add(mc)
            }
            if (!metaList.isEmpty()) c.put("npcMeta", metaList)
            val slotList = NbtList()
            shop.all(ShopEntry.KIND_POKEMON).forEach { (index, entry) -> slotList.add(writeSlot(index, entry)) }
            shop.all(ShopEntry.KIND_ITEM).forEach { (index, entry) -> slotList.add(writeSlot(index, entry)) }
            c.put("slots", slotList)
            list.add(c)
        }
        nbt.put("shops", list)
        return nbt
    }

    private fun writeSlot(index: Int, entry: ShopEntry): NbtCompound {
        val c = NbtCompound()
        // 序号是**这一层**的事（快照库没有格子序号），规格本体走共享函数
        c.putInt("index", index)
        writeShopEntryBody(c, entry)
        return c
    }

    companion object {
        /** 商店数量上限（防手抖 / 恶意客户端把存档撑爆） */
        const val MAX_SHOPS = 64

        /** 商店名字符上限（标题一行放得下；中英都按最宽文案定宽） */
        const val MAX_NAME_LENGTH = 32

        /** 一个商店最多绑几个 NPC */
        const val MAX_NPCS_PER_SHOP = 64

        /**
         * 商店 id：小写字母 / 数字 / `_` / `-`，1~32 个字符。
         * ⚠ `main` 是**主商城的保留 id**，自定义商店不许用（否则会串到主商城）。
         */
        val SHOP_ID_PATTERN = Regex("^[a-z0-9_-]{1,32}$")

        private val TYPE = PersistentState.Type(
            { CustomShopState() },
            { nbt, _ ->
                CustomShopState().apply {
                    loading = true
                    try {
                        // 逐条容错：单个商店损坏只跳过它 + warn，绝不整体抛异常（存档铁律）
                        nbt.getList("shops", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                            try {
                                readShop(element as NbtCompound)
                            } catch (e: Exception) {
                                CobbleMarket.LOGGER.warn("Skipping corrupted custom shop: {}", e.message)
                            }
                        }
                    } finally {
                        // 读完了：之后任何改动都要正常标脏（异常路径也一样，否则这份状态永远写不出去）
                        loading = false
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): CustomShopState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_custom_shops")
    }

    /**
     * 从 NBT 读一个商店（逐格容错）。
     * ⚠ 读档**不做创建期校验**：手改存档写了奇怪但非空的 id 也照读 —— 宁可用，
     *   也不要因为一个字符把整店货吞掉；`main` 除外（会和主商城路由撞车）。
     */
    private fun readShop(c: NbtCompound) {
        // 与 create/find 同一套大小写规则：手改存档写了大写也照样能按 id 找到 / 删掉
        val rawId = normalizeId(c.getString("id"))
        if (rawId.isEmpty() || rawId.length > MAX_NAME_LENGTH || rawId == MAIN_SHOP_ID) {
            CobbleMarket.LOGGER.warn("Skipping custom shop with invalid id: {}", rawId)
            return
        }
        if (shops.containsKey(rawId)) {
            CobbleMarket.LOGGER.warn("Skipping duplicate custom shop id: {}", rawId)
            return
        }
        val name = c.getString("name").ifBlank { rawId }
            .take(MAX_NAME_LENGTH).replace("§", "").ifBlank { rawId }
        val shop = CustomShop(
            owner = this,
            id = rawId,
            name = name,
            enabled = if (c.contains("enabled")) c.getBoolean("enabled") else true,
            // 缺字段 / 认不出的值都落 BOTH（存档铁律：读取容错，不崩）
            type = CustomShopType.fromId(if (c.contains("type")) c.getString("type") else null),
            // ⚠ 这里**不能用** `MarketCurrency.fromStored`（它缺省落主货币）—— 老的自定义店都是 RP 店，
            //   缺字段必须落 POINTS 才不改变它们的价格含义
            currency = MarketCurrency.fromRequest(if (c.contains("currency")) c.getString("currency") else null)
                ?: MarketCurrency.POINTS,
            // 缺字段 / 认不出的值都落 NORMAL（读取容错）；周期夹到合法范围
            mode = CustomShopMode.fromId(if (c.contains("mode")) c.getString("mode") else null),
            claimHours = if (c.contains("claimHours")) {
                c.getInt("claimHours").coerceIn(1, MAX_CLAIM_HOURS)
            } else DEFAULT_CLAIM_HOURS
        )
        // NPC 绑定：单个 UUID 坏了解析就跳过，不影响商店本体
        readStringList(c, "npcUuids").forEach { raw ->
            try {
                shop.npcUuids.add(UUID.fromString(raw))
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Skipping invalid NPC uuid in custom shop {}: {}", rawId, raw)
            }
        }
        // NPC 显示信息：缺字段 = 旧绑定（管理端现场解析实体兜底）；坏条目跳过
        if (c.contains("npcMeta")) {
            c.getList("npcMeta", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                try {
                    val mc = element as NbtCompound
                    val uuid = UUID.fromString(mc.getString("uuid"))
                    if (uuid !in shop.npcUuids) return@forEach
                    shop.npcMeta[uuid] = CustomShopNpcMeta(
                        typeId = if (mc.contains("typeId")) mc.getString("typeId") else "",
                        customName = if (mc.contains("name")) mc.getString("name") else "",
                        species = if (mc.contains("species")) mc.getString("species") else "",
                        aspects = if (mc.contains("aspects")) mc.getString("aspects") else ""
                    )
                } catch (e: Exception) {
                    CobbleMarket.LOGGER.warn("Skipping invalid NPC meta in custom shop {}: {}", rawId, e.message)
                }
            }
        }
        // 条目：单条损坏只跳过它（与主商城 read 同一口径）
        c.getList("slots", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
            try {
                val sc = element as NbtCompound
                val index = sc.getInt("index")
                if (index < 0) return@forEach
                val entry = readShopEntryBody(sc) ?: return@forEach
                shop.put(entry.kind, index, entry)
            } catch (e: Exception) {
                CobbleMarket.LOGGER.warn("Skipping corrupted entry in custom shop {}: {}", rawId, e.message)
            }
        }
        shops[rawId] = shop
    }
}
