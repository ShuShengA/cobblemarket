package com.shusheng.cobblemarket.currency

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtString
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState

/**
 * **RP 商城的一件商品**（服主"捏"出来的**配方**，不是服主库存里的实物）。
 *
 * ⚠ 与主市场挂单的根本差别：挂单是**唯一实体**（卖掉就没了），商城商品是**配方** ——
 * 玩家买一次就照规格**生成**一份新的。因此：
 * - 同一格能卖无数次（[stock] = -1 无限量时）；
 * - 生成的精灵**初训家 = 买家本人、UUID 全新** —— 服主的名字不会印到卖出去的精灵上
 *   （这正是"不让服主先抓一只再上架"的原因，2026-09-18 用户点出）。
 *
 * [stock] 售罄后**本条目仍然保留**（格子继续显示，右上角数量变红 0，玩家买不了），
 * 服主可以补货、删格或改成别的内容。
 */
data class ShopEntry(
    /** `pokemon` / `item`（非法值在读档时整条丢弃） */
    val kind: String,
    /** 售价（RP）。商城的价由服主定，**不受价格限制表 / 黑名单 / 手续费约束** */
    val price: Long,
    /** 剩余数量；**-1 = 无限量**（默认），0 = 已售罄 */
    val stock: Int = UNLIMITED,
    val pokemon: ShopPokemonSpec? = null,
    val item: ShopItemSpec? = null,

    /**
     * **条目唯一标识**：服务端**每次保存都换一个新的**（新上架 / 编辑 / 下架后重新上架，全都算）。
     * 玩家买走一件**不会**动它（库存变化不是内容变化）。
     *
     * 用途：玩家打开购买窗时记下当时那个 id、点购买时带回来，服务端**扣钱之前**比对 ——
     * 对不上就说明这一格在玩家看它之后**换过内容**（换了精灵 / 改了价），此时必须拒绝：
     * 玩家以为买的是他看到的那个，按改后的内容或价格成交等于骗人。
     * 详见 [PointMarketService.buy]（那里有竞态窗口的完整说明）。
     *
     * ⚠ **不能用自增版本号**：下架再重新上架会从 0 重来，玩家手里旧的 0 与新的 0 正好撞上、
     *   校验就漏了（2026-09-25 复盘时发现的）。UUID 不会撞。
     * ⚠ 放在**参数表末尾**并给默认值 —— 存档铁律：加字段只增不改，旧档读不到就落空串。
     */
    val entryId: String = "",
    /**
     * **上架者**（服主那一侧）—— 只给交易账本的「卖家」列用，不参与任何业务判定。
     *
     * ⚠ 末尾追加 + 默认值：**存档铁律（只增不改）** —— 旧档读不到就是空串，
     *   写盘后旧版本照样能读（多一个字段而已）。空串 = 这条在加字段之前就挂着。
     */
    val creatorUuid: String = "",
    val creatorName: String = ""
) {
    fun isSoldOut(): Boolean = stock == 0

    companion object {
        const val KIND_POKEMON = "pokemon"
        const val KIND_ITEM = "item"
        const val UNLIMITED = -1
    }
}

/**
 * 精灵商品的规格。
 *
 * 每一项都支持两种模式（用户 2026-09-18 定的方向：**既能设具体值，也能设随机/保底**）：
 * - 字段为 **null ⇒ 随机**（生成时按游戏规则掷）
 * - 字段有值 ⇒ 就是那个值
 *
 * [ivs] / [minPerfectIvs] 是这条规则的特例，因为个体值多了一个"保底"玩法
 *（照价格限制界面的 V 数：服主选 1V = 至少 1 项是 31）。
 */
data class ShopPokemonSpec(
    /** 物种 ID（形如 `cobblemon:pikachu`；界面用中文名解析，这里存 ID） */
    val species: String,
    /** 形态（含 `shiny`）；空 = 默认形态 */
    val aspects: List<String> = emptyList(),
    /**
     * **概率闪光**（0f~1f，0 = 不闪）—— 用户 2026-09-20 口述的第二种闪光玩法。
     *
     * 与 [aspects] 里的固定 `shiny` **互斥**，两者各管一种服主设置：
     * - **固定闪光** ⇒ `aspects` 含 `shiny`，本项恒 0
     * - **概率闪光** ⇒ 本项 > 0，`aspects` **不含** `shiny` —— 正因如此，预览才渲染**默认（非闪光）形态**，
     *   悬停窗也好标出「闪光 x%」
     */
    val shinyChance: Float = 0f,
    val level: Int? = null,
    val gender: String? = null,
    val nature: String? = null,
    val ability: String? = null,
    val ball: String? = null,
    /**
     * 体型倍率（Cobblemon 的 `ScaleModifier`）；null = 默认（1.0）。
     * 游戏里按它分 XS/S/M/L/XL 档（见 `util/PokemonSize`）。
     */
    val scale: Float? = null,
    /** 头目（Alpha）：详情页名字旁的 ALPHA 徽章，稀有标签 */
    val alpha: Boolean = false,
    /** 太晶属性（TeraType 的 id）；null = 按主属性默认 */
    val teraType: String? = null,
    /** 超极巨化（Gmax） */
    val gmaxFactor: Boolean = false,
    /** 极巨化等级（0 = 无） */
    val dmaxLevel: Int = 0,
    /** 携带物是否对他人可见（false = 别人看不到，同游戏内"隐藏携带物"） */
    val heldItemVisible: Boolean = true,
    /** 是否可交易（false = 玩家拿到后不能转手，服主做活动奖品时用得上） */
    val tradeable: Boolean = true,
    /**
     * 六项个体值（HP/攻/防/特攻/特防/速），有值时**恒为 6 项**；**null = 整组随机**（此时看 [minPerfectIvs]）。
     *
     * 有值时，每项 **`-1` = 该项没填**（生成时单独掷）—— 与黑名单 `ivHp >= 0` 同口径，
     * 服主可以只钉死两项、其余随机。⚠ 读档时长度不是 6 的一律当 null（坏数据不参与生成）。
     */
    val ivs: List<Int>? = null,
    /** 随机时的保底：至少几项是 31（0 = 不保底）。[ivs] 有具体值时本项无意义 */
    val minPerfectIvs: Int = 0,
    // ── 高级项：界面精细讨论后再接入口，字段先留着（存档结构定了就别再改） ──
    /** 六项努力值；null = 全 0 */
    val evs: List<Int>? = null,
    val friendship: Int? = null,
    val nickname: String? = null,
    /**
     * 携带物：**完整物品栈 NBT 的 SNBT 串**（形如 `{id:"minecraft:diamond_sword",count:1,...}`）。
     * 与挂单的 `ListingEntry.heldItemNbt` 同一口径 —— 客户端据此重建图标，
     * 不带组件就丢附魔光效 / 自定义名（[[project_held_item_component]] 那坑）。
     */
    val heldItemSpec: String? = null,
    val marks: List<String> = emptyList(),
    /**
     * 四个技能槽（**技能名，空槽是空串**），形如 `thunderbolt,quickattack,,` ——
     * 与挂单 / 拍卖 / 精灵 extraData 用的是**同一套逗号串**（见 `util/MoveText`），
     * 客户端解析、悬停窗渲染、服务端写回全都走它，不另立格式。
     *
     * ⚠ 必须是**槽位串**而不是"会的招列表"：服主要能看到自己设的是第几个槽，
     * 空串 = 该槽没设（生成时随机补）。
     */
    val moves: String = ""
)

/**
 * 物品商品的规格。买了**照规格生成**，不消耗服主背包。
 *
 * ⚠ **没有"一次给几个"**：买多少由玩家在购买窗里自己填（2026-09-20 用户拍板），
 * 价格按「单价 × 数量」当场算，所以规格里只需要「是什么物品」。
 */
data class ShopItemSpec(
    val itemId: String,
    /**
     * 物品组件快照（附魔等）；null = 无组件。
     * ⚠ 与黑名单 / 价格限制 / 求购单**同一个类型、同一套白名单组件**
     * （见 `market/ItemRuleComponents`）—— 生成物品时按它重建组件，不是 SNBT 串。
     */
    val componentsSpec: NbtCompound? = null
)

/**
 * **RP 商城存档**（`cobblemarket_point_shop.dat`）。
 *
 * 格子序号**全局递增**（第 1 页 = 0~31、第 2 页 = 32~63…），空格子不进表。
 * 页数按**最大已填序号**算（见 [pageCount]），所以服主跳着填也不会错位。
 *
 * ⚠ 只有 OP 能改（上架/编辑/下架），**不下放任何权限节点**（同 `points give` 的印钞权口径）。
 */
class PointShopState private constructor() : PersistentState() {

    /**
     * **种类 → (格子序号 → 商品)**。
     *
     * ⚠ 精灵与物品**各占一套独立的编号**（2026-09-25 用户拍板把 tab 变成真过滤）——
     * 「精灵 tab 的第 5 格」与「物品 tab 的第 5 格」是**两个不同的位置**。
     * 空格子不存（省存档空间），序号本身即位置。
     */
    private val slots = mutableMapOf<String, MutableMap<Int, ShopEntry>>()

    private fun slotsOf(kind: String): MutableMap<Int, ShopEntry> = slots.getOrPut(kind) { mutableMapOf() }

    fun find(kind: String, index: Int): ShopEntry? = slots[kind]?.get(index)

    /** 某个种类下的全部已上架格子（序号 → 商品） */
    fun all(kind: String): Map<Int, ShopEntry> = slots[kind] ?: emptyMap()

    fun size(kind: String): Int = slots[kind]?.size ?: 0

    /** 上架 / 覆盖某格（[kind] 由 [ShopEntry.kind] 决定，调用方已校验过两者一致） */
    fun put(kind: String, index: Int, entry: ShopEntry) {
        slotsOf(kind)[index] = entry
        markDirty()
    }

    /** 清空某格（服主"删除这个格子里的内容"）；返回是否真的删掉了 */
    fun remove(kind: String, index: Int): Boolean {
        if (slots[kind]?.remove(index) == null) return false
        markDirty()
        return true
    }

    /**
     * 卖出 [amount] 份：库存 -amount；无限量不动。
     * 返回 false = **售罄或库存不足**（调用方必须拒绝交易，且此时**一分钱都不能扣**）。
     *
     * ⚠ 与扣款同一事务内调用：**先扣库存再加货/扣钱**，崩溃窗口最坏是少卖一份（可补），
     * 反过来则会超卖 —— 玩家付了钱却拿不到货，或者拿了货没付钱。
     */
    fun consume(kind: String, index: Int, amount: Int = 1): Boolean {
        if (amount <= 0) return false
        val bucket = slots[kind] ?: return false
        val entry = bucket[index] ?: return false
        if (entry.stock == ShopEntry.UNLIMITED) return true
        if (entry.stock < amount) return false
        bucket[index] = entry.copy(stock = entry.stock - amount)
        markDirty()
        return true
    }

    /** 补货 / 改库存。`amount < 0` = 改成无限量 */
    fun setStock(kind: String, index: Int, amount: Int): Boolean {
        val bucket = slots[kind] ?: return false
        val entry = bucket[index] ?: return false
        bucket[index] = entry.copy(stock = amount)
        markDirty()
        return true
    }

    /**
     * 某个种类的页数：按**该种类下最大已填序号**算，至少 1 页
     *（一件没上架也有一页空位等着服主填）。
     * 例：填了 0~31 → 2 页（第 2 页全空，供继续添加）；填了 0~32 → 仍是 2 页。
     */
    fun pageCount(kind: String): Int {
        val maxIndex = slots[kind]?.keys?.maxOrNull() ?: return 1
        // ⚠ **只按内容算**（"已填最大序号在第几页"）——**不给空页**。
        //   客户端那边对**有编辑权限的人**再多显示一页（见 `PointsMarketScreen.totalPages`）：
        //   他得能翻到空页去放货；普通玩家看到空页没意义，翻过去只能对着空白发呆
        //   （2026-09-27 用户定的口径）。
        //   ⚠ 服务端**没法**按人算：快照是**广播**给所有人的，只能客户端按自己的权限补。
        return maxIndex / POINTS_SHOP_PAGE_SIZE + 1
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        // ⚠ **两层遍历**（2026-09-25 起精灵与物品各占一套独立的格子编号）：
        //   外壳是种类、里面才是 (序号 → 商品) —— 直接 `slots.forEach { (index, entry) }`
        //   解构出来的 `index` 其实是 kind（编译期就会报类型错，这里记一笔免得再犯）
        slots.values.flatMap { it.entries }.forEach { (index, entry) ->
            val c = NbtCompound()
            // 序号是**这一层**的事（快照库没有格子序号），规格本体走共享函数
            c.putInt("index", index)
            writeShopEntryBody(c, entry)
            list.add(c)
        }
        nbt.put("slots", list)
        return nbt
    }

    companion object {
        /** 一页的格子数：8 列 × 4 行（用户 2026-09-18 拍板；每页永远是满的） */
        const val POINTS_SHOP_PAGE_SIZE = 32

        private val TYPE = PersistentState.Type(
            { PointShopState() },
            { nbt, _ ->
                PointShopState().apply {
                    // 逐条容错：单条损坏只跳过它 + warn，绝不整体抛异常（见 CLAUDE.md 的存档铁律）
                    nbt.getList("slots", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            val c = element as NbtCompound
                            val index = c.getInt("index")
                            if (index < 0) return@forEach
                            // 规格本体走共享函数（与快照库同一份）；它返回 null = 这条坏了或种类对不上
                            val entry = readShopEntryBody(c) ?: return@forEach
                            // ⚠ 按 kind 分组：精灵与物品各占一套独立的格子编号（2026-09-25 起）
                            slots.getOrPut(entry.kind) { mutableMapOf() }[index] = entry
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted shop entry: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): PointShopState =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_point_shop")
    }
}

// ── 商品规格的 NBT 读写（**格子存档与快照库共用**）──
//
// 抽出来是因为两处存的是**同一套结构**（[PointShopState] 的格子、[PointShopSnapshotState] 的快照）——
// 各写一份序列化，加字段时漏一处就会静默丢数据（本项目反复踩过这类"两处各写一遍"的坑）。
// ⚠ **不含格子序号**：序号是格子存档那一层的事，快照库里没有这概念。

/** 把一个商品的规格写进 [c]（调用方自己写它那一层特有的字段，如格子序号） */
internal fun writeShopEntryBody(c: NbtCompound, entry: ShopEntry) {
    c.putString("kind", entry.kind)
    c.putLong("price", entry.price)
    c.putInt("stock", entry.stock)
    // 空串不写（老数据 / 读不到就落空串时省空间）；读端缺字段落空串
    if (entry.entryId.isNotEmpty()) c.putString("entryId", entry.entryId)
    // 上架者（账本「卖家」列用）。空 = 老条目，省略整个键（与 entryId 同风格）
    if (entry.creatorUuid.isNotEmpty()) c.putString("creatorUuid", entry.creatorUuid)
    if (entry.creatorName.isNotEmpty()) c.putString("creatorName", entry.creatorName)
    entry.pokemon?.let { p ->
        val pc = NbtCompound()
        pc.putString("species", p.species)
        putStringList(pc, "aspects", p.aspects)
        if (p.shinyChance > 0f) pc.putFloat("shinyChance", p.shinyChance)
        p.level?.let { pc.putInt("level", it) }
        p.gender?.let { pc.putString("gender", it) }
        p.nature?.let { pc.putString("nature", it) }
        p.ability?.let { pc.putString("ability", it) }
        p.ball?.let { pc.putString("ball", it) }
        p.scale?.let { pc.putFloat("scale", it) }
        if (p.alpha) pc.putBoolean("alpha", true)
        p.teraType?.let { pc.putString("teraType", it) }
        if (p.gmaxFactor) pc.putBoolean("gmaxFactor", true)
        if (p.dmaxLevel > 0) pc.putInt("dmaxLevel", p.dmaxLevel)
        if (!p.heldItemVisible) pc.putBoolean("heldItemVisible", false)
        if (!p.tradeable) pc.putBoolean("tradeable", false)
        p.ivs?.let { pc.putIntArray("ivs", it) }
        pc.putInt("minPerfectIvs", p.minPerfectIvs)
        p.evs?.let { pc.putIntArray("evs", it) }
        p.friendship?.let { pc.putInt("friendship", it) }
        p.nickname?.let { pc.putString("nickname", it) }
        p.heldItemSpec?.let { pc.putString("heldItemSpec", it) }
        if (p.marks.isNotEmpty()) putStringList(pc, "marks", p.marks)
        if (p.moves.isNotEmpty()) pc.putString("moves", p.moves)
        c.put("pokemon", pc)
    }
    entry.item?.let { i ->
        val ic = NbtCompound()
        ic.putString("itemId", i.itemId)
        // 空快照不写（读回来是 null = 无组件，与黑名单同口径）
        i.componentsSpec?.takeIf { !it.isEmpty }?.let { ic.put("components", it) }
        c.put("item", ic)
    }
}

/**
 * 从 [c] 读出一个商品；返回 null = 这条坏了 / 种类对不上 ——
 * 调用方记 warn 后**跳过这一条**，绝不整体抛异常（CLAUDE.md 的存档铁律）。
 *
 * ⚠ 与 [writeShopEntryBody] 是**一对**，改一边必须改另一边。
 */
internal fun readShopEntryBody(c: NbtCompound): ShopEntry? {
    val kind = c.getString("kind")
    // 认不出的种类整条丢弃：宁少一件商品，也不让坏数据变成"空白格子"
    if (kind != ShopEntry.KIND_POKEMON && kind != ShopEntry.KIND_ITEM) {
        CobbleMarket.LOGGER.warn("Skipping shop entry with unknown kind: {}", kind)
        return null
    }
    val pokemon = if (c.contains("pokemon")) {
        val pc = c.getCompound("pokemon")
        val species = pc.getString("species")
        if (species.isBlank()) null else ShopPokemonSpec(
            species = species,
            aspects = readStringList(pc, "aspects"),
            shinyChance = if (pc.contains("shinyChance"))
                pc.getFloat("shinyChance").coerceIn(0f, 1f) else 0f,
            level = if (pc.contains("level")) pc.getInt("level") else null,
            gender = if (pc.contains("gender")) pc.getString("gender") else null,
            nature = if (pc.contains("nature")) pc.getString("nature") else null,
            ability = if (pc.contains("ability")) pc.getString("ability") else null,
            ball = if (pc.contains("ball")) pc.getString("ball") else null,
            scale = if (pc.contains("scale")) pc.getFloat("scale") else null,
            alpha = pc.getBoolean("alpha"),
            teraType = if (pc.contains("teraType")) pc.getString("teraType") else null,
            gmaxFactor = pc.getBoolean("gmaxFactor"),
            dmaxLevel = if (pc.contains("dmaxLevel")) pc.getInt("dmaxLevel") else 0,
            heldItemVisible = if (pc.contains("heldItemVisible")) pc.getBoolean("heldItemVisible") else true,
            tradeable = if (pc.contains("tradeable")) pc.getBoolean("tradeable") else true,
            // 长度不是 6 的当"整组随机"：坏数组不参与生成（铁律：读取容错）
            ivs = if (pc.contains("ivs"))
                pc.getIntArray("ivs").toList().takeIf { it.size == 6 } else null,
            minPerfectIvs = if (pc.contains("minPerfectIvs")) pc.getInt("minPerfectIvs") else 0,
            evs = if (pc.contains("evs")) pc.getIntArray("evs").toList() else null,
            friendship = if (pc.contains("friendship")) pc.getInt("friendship") else null,
            nickname = if (pc.contains("nickname")) pc.getString("nickname") else null,
            heldItemSpec = if (pc.contains("heldItemSpec")) pc.getString("heldItemSpec") else null,
            marks = readStringList(pc, "marks"),
            moves = if (pc.contains("moves")) pc.getString("moves") else ""
        )
    } else null
    val item = if (c.contains("item")) {
        val ic = c.getCompound("item")
        val itemId = ic.getString("itemId")
        if (itemId.isBlank()) null else ShopItemSpec(
            itemId = itemId,
            componentsSpec = if (ic.contains("components")) ic.getCompound("components") else null
        )
    } else null
    // 规格与种类对不上（如 kind=pokemon 却没有精灵规格）→ 丢弃
    //（warn 里不再带格子序号：快照库没有这个概念，序号由格子那边自己补）
    if (kind == ShopEntry.KIND_POKEMON && pokemon == null) {
        CobbleMarket.LOGGER.warn("Skipping shop pokemon entry without a valid spec")
        return null
    }
    if (kind == ShopEntry.KIND_ITEM && item == null) {
        CobbleMarket.LOGGER.warn("Skipping shop item entry without a valid spec")
        return null
    }
    return ShopEntry(
        kind = kind,
        price = c.getLong("price").coerceAtLeast(0L),
        stock = if (c.contains("stock")) c.getInt("stock") else ShopEntry.UNLIMITED,
        pokemon = pokemon,
        item = item,
        entryId = if (c.contains("entryId")) c.getString("entryId") else "",
        // ⚠ 缺字段一律落空串（旧档没有这两个键）—— 存档铁律：读取容错，不抛异常
        creatorUuid = if (c.contains("creatorUuid")) c.getString("creatorUuid") else "",
        creatorName = if (c.contains("creatorName")) c.getString("creatorName") else ""
    )
}

// ── NBT 字符串列表辅助（1.21 的 NbtCompound 没有 putStringList / getStringList）──

private fun putStringList(nbt: NbtCompound, key: String, values: List<String>) {
    val list = NbtList()
    values.forEach { list.add(NbtString.of(it)) }
    nbt.put(key, list)
}

private fun readStringList(nbt: NbtCompound, key: String): List<String> {
    if (!nbt.contains(key)) return emptyList()
    return nbt.getList(key, NbtElement.STRING_TYPE.toInt())
        .mapNotNull { (it as? NbtString)?.asString() }
}
