package com.shusheng.cobblemarket.market

import net.minecraft.nbt.NbtCompound
import java.util.UUID

enum class ListingStatus {
    ACTIVE,
    SOLD,
    CANCELLED,
    EXPIRED
}

enum class SortMode {
    PRICE_ASC,
    PRICE_DESC,
    LEVEL_ASC,
    LEVEL_DESC,
    NEWEST,
    OLDEST
}

data class MarketListing(
    val id: UUID,
    val sellerUuid: UUID,
    val sellerName: String,
    val pokemonNbt: NbtCompound,
    val species: String,
    val level: Int,
    val shiny: Boolean,
    val price: Int,
    val createdAt: Long,
    val expiresAt: Long,
    var status: ListingStatus,
    val extraData: Map<String, String> = emptyMap(),
    /** 进入待领取退回列表的时间戳；null = 从未进入（旧存档兼容） */
    var returnedAt: Long? = null
) {
    fun isActive(): Boolean = status == ListingStatus.ACTIVE

    fun speciesText(): net.minecraft.text.Text =
        net.minecraft.text.Text.translatable(extraData["speciesKey"] ?: "cobblemon.species.${species.lowercase()}.name")

    fun toNbt(): NbtCompound = NbtCompound().apply {
        putUuid("id", id)
        putUuid("sellerUuid", sellerUuid)
        putString("sellerName", sellerName)
        put("pokemon", pokemonNbt)
        putString("species", species)
        putInt("level", level)
        putBoolean("shiny", shiny)
        putInt("price", price)
        putLong("createdAt", createdAt)
        putLong("expiresAt", expiresAt)
        putString("status", status.name)
        returnedAt?.let { putLong("returnedAt", it) }
        val extra = NbtCompound()
        extraData.forEach { (k, v) -> extra.putString(k, v) }
        put("extraData", extra)
    }

    companion object {
        fun fromNbt(nbt: NbtCompound): MarketListing {
            val extra = nbt.getCompound("extraData")
            val extraMap = mutableMapOf<String, String>()
            extra.keys.forEach { key -> extraMap[key] = extra.getString(key) }
            return MarketListing(
                id = nbt.getUuid("id"),
                sellerUuid = nbt.getUuid("sellerUuid"),
                sellerName = nbt.getString("sellerName"),
                pokemonNbt = nbt.getCompound("pokemon"),
                species = nbt.getString("species"),
                level = nbt.getInt("level"),
                shiny = nbt.getBoolean("shiny"),
                price = nbt.getInt("price"),
                createdAt = nbt.getLong("createdAt"),
                expiresAt = nbt.getLong("expiresAt"),
                status = ListingStatus.valueOf(nbt.getString("status")),
                extraData = extraMap,
                returnedAt = if (nbt.contains("returnedAt")) nbt.getLong("returnedAt") else null
            )
        }
    }
}
