package com.shusheng.cobblemarket.event

import com.shusheng.cobblemarket.currency.MarketCurrency
import net.minecraft.nbt.NbtCompound
import java.util.UUID

enum class TransactionType {
    ADD, PURCHASE, CANCEL, RETURN,
    /** 求购单发布（买家冻结资金挂单；无对应货物上架，独立于 ADD） */
    ORDER,
    /** 定期领取店的领取（免费发放，不是卖出；2026-10-26 新增） */
    CLAIM
}

enum class TransactionCategory {
    POKEMON, ITEM
}

data class TransactionRecord(
    val timestamp: Long,
    val type: TransactionType,
    val category: TransactionCategory,
    val sellerUuid: UUID,
    val sellerName: String,
    val buyerUuid: UUID?,
    val buyerName: String,
    val species: String,
    val price: Int,
    val fee: Int,
    // CSV「详情」列：精灵完整数值/物品 NBT 文本化（com.shusheng.cobblemarket.util.RecordDetail 构建；旧记录为空串）
    val detail: String = "",
    /**
     * 这笔流水是什么币种的（CSV 多一列）。**追加在末尾并给默认值** ⇒ 旧记录读不到就是主货币。
     * ⚠ 交易历史界面与 CSV 都按它显示单位；漏了会让 RP 流水看起来像主货币流水。
     */
    val currency: MarketCurrency = MarketCurrency.PRIMARY,
    // CSV「主属性」列：精灵名上色用（`cobblemon.type.fire` 这种 key，**形态感知** —— 挂单时取的
    // pokemon.primaryType）。物品记录为空串；旧记录也读不到 → 落空串，界面按无色处理（只增不改）
    val primaryType: String = "",
    /**
     * 这笔流水是不是**商店**（主 RP 商城或自定义 NPC 商店）卖出的。
     *
     * 商城购买与玩家间市场直购是**同一个 [TransactionType.PURCHASE]**，界面光看类型分不出来，
     * 所以由产生记录的一侧打标（`PointMarketService.buy` 传 true）。**追加在末尾并给默认值**
     * ⇒ 旧记录读不到就是普通市场交易。⚠ 商店成交的卖家是**商店本身**（见 [SHOP_SELLER_UUID]），
     * 所以不会进上架者的个人历史，只在「全部交易历史」里；界面据此把卖家列画成店名 / 「▣ 商城」。
     */
    val fromShop: Boolean = false,
    /**
     * 商店名（仅自定义 NPC 商店有值）。**追加在末尾并给默认值** ⇒ 旧记录 / 主商城落空串。
     * 成交时快照当时的店名（店后来改名 / 删除也不影响历史行）。
     * ⚠ 空串 + [fromShop] = 主 RP 商城；非空 = 自定义商店，界面直接画店名。
     */
    val shopName: String = "",
    /**
     * 这笔流水属于哪家商店（[com.shusheng.cobblemarket.currency.MAIN_SHOP_ID] = 主商城；
     * 其余 = 自定义商店 id）。玩家间交易 / 拍卖 / 求购一律空串。
     * **追加在末尾并给默认值** ⇒ 旧记录读不到就是空串（无法归属到某家店，只出现在全服历史）。
     * ⚠ 按店筛选（商店记录界面）靠它，别拿店名筛（会改名）。
     */
    val shopId: String = "",
    /**
     * **客户端视角的物品 NBT**（Polymer 等伪装物品才有值；空 = 与真实物品一致）。
     * 历史界面渲染物品名时优先用它 —— 真实物品 id 客户端不认识，按 id 查名字会显示成一串 id。
     * 末尾追加 + 默认值 ⇒ 旧记录读不到就是空（回退现状）。
     */
    val displayNbt: NbtCompound? = null
) {
    fun toNbt(): NbtCompound = NbtCompound().apply {
        putLong("timestamp", timestamp)
        putString("type", this@TransactionRecord.type.name)
        putString("category", category.name)
        putUuid("sellerUuid", sellerUuid)
        putString("sellerName", sellerName)
        buyerUuid?.let { putUuid("buyerUuid", it) }
        putString("buyerName", buyerName)
        putString("species", species)
        putInt("price", price)
        putInt("fee", fee)
        putString("detail", detail)
        putString("currency", currency.name)
        putString("primaryType", primaryType)
        putBoolean("fromShop", fromShop)
        putString("shopName", shopName)
        putString("shopId", shopId)
        displayNbt?.let { put("displayNbt", it) }
    }

    companion object {
        fun fromNbt(nbt: NbtCompound): TransactionRecord = TransactionRecord(
            timestamp = nbt.getLong("timestamp"),
            type = TransactionType.valueOf(nbt.getString("type")),
            category = if (nbt.contains("category")) TransactionCategory.valueOf(nbt.getString("category")) else TransactionCategory.POKEMON,
            sellerUuid = nbt.getUuid("sellerUuid"),
            sellerName = nbt.getString("sellerName"),
            buyerUuid = if (nbt.containsUuid("buyerUuid")) nbt.getUuid("buyerUuid") else null,
            buyerName = nbt.getString("buyerName"),
            species = nbt.getString("species"),
            price = nbt.getInt("price"),
            fee = nbt.getInt("fee"),
            detail = if (nbt.contains("detail")) nbt.getString("detail") else "",
            // 旧记录没这个键（getString 给空串）/ 值是垃圾 ⇒ fromStored 一律落主货币，不抛不 warn
            currency = MarketCurrency.fromStored(nbt.getString("currency")),
            primaryType = if (nbt.contains("primaryType")) nbt.getString("primaryType") else "",
            // 旧记录没这个键 ⇒ false（普通市场交易），不抛不 warn
            fromShop = nbt.contains("fromShop") && nbt.getBoolean("fromShop"),
            // 旧记录 / 主商城 ⇒ 空串（界面退回「商城」）
            shopName = if (nbt.contains("shopName")) nbt.getString("shopName") else "",
            shopId = if (nbt.contains("shopId")) nbt.getString("shopId") else "",
            displayNbt = if (nbt.contains("displayNbt")) nbt.getCompound("displayNbt") else null
        )
    }
}

/**
 * 商店成交记录里的「卖家」占位 UUID。
 *
 * 商店没有玩家卖家 ⇒ 不能写某个管理员（否则这笔卖出会进他的个人历史，2026-10-26 用户报）。
 * 个人历史按 UUID 过滤，零 UUID 不会匹配任何玩家；买家侧仍按 `buyerUuid` 正常可见。
 * ⚠ **不要**改成 `null`：CSV 回读把「卖家 UUID 缺失」当老格式、会退化成按名字匹配。
 */
val SHOP_SELLER_UUID: UUID = UUID(0L, 0L)
