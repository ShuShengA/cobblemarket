package com.shusheng.cobblemarket.event

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
    val detail: String = ""
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
            detail = if (nbt.contains("detail")) nbt.getString("detail") else ""
        )
    }
}
