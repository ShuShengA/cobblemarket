package com.shusheng.cobblemarket.market

import net.minecraft.nbt.NbtCompound
import java.util.UUID

data class ItemListing(
    val id: UUID,
    val sellerUuid: UUID,
    val sellerName: String,
    val itemId: String,
    val itemNbt: NbtCompound,
    var count: Int,
    val price: Int,
    val createdAt: Long,
    val expiresAt: Long,
    var status: ListingStatus,
    /** 进入待领取退回列表的时间戳；null = 从未进入（旧存档兼容） */
    var returnedAt: Long? = null
) {
    fun isActive(): Boolean = status == ListingStatus.ACTIVE

    fun toNbt(): NbtCompound = NbtCompound().apply {
        putUuid("id", id)
        putUuid("sellerUuid", sellerUuid)
        putString("sellerName", sellerName)
        putString("itemId", itemId)
        put("itemNbt", itemNbt)
        putInt("count", count)
        putInt("price", price)
        putLong("createdAt", createdAt)
        putLong("expiresAt", expiresAt)
        putString("status", status.name)
        returnedAt?.let { putLong("returnedAt", it) }
    }

    companion object {
        fun fromNbt(nbt: NbtCompound): ItemListing = ItemListing(
            id = nbt.getUuid("id"),
            sellerUuid = nbt.getUuid("sellerUuid"),
            sellerName = nbt.getString("sellerName"),
            itemId = nbt.getString("itemId"),
            itemNbt = nbt.getCompound("itemNbt"),
            count = nbt.getInt("count"),
            price = nbt.getInt("price"),
            createdAt = nbt.getLong("createdAt"),
            expiresAt = nbt.getLong("expiresAt"),
            status = ListingStatus.valueOf(nbt.getString("status")),
            returnedAt = if (nbt.contains("returnedAt")) nbt.getLong("returnedAt") else null
        )
    }
}
