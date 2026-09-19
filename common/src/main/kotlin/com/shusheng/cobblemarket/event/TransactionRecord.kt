package com.shusheng.cobblemarket.event

import com.shusheng.cobblemarket.currency.MarketCurrency
import net.minecraft.nbt.NbtCompound
import java.util.UUID

enum class TransactionType {
    ADD, PURCHASE, CANCEL, RETURN,
    /** 求购单发布（买家冻结资金挂单；无对应货物上架，独立于 ADD） */
    ORDER
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
    val currency: MarketCurrency = MarketCurrency.PRIMARY
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
            currency = MarketCurrency.fromStored(nbt.getString("currency"))
        )
    }
}
