package com.shusheng.cobblemarket.market

import com.shusheng.cobblemarket.currency.MarketCurrency
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
    var returnedAt: Long? = null,
    /**
     * 这一单用什么币种结算。**追加在末尾并给默认值** ⇒ 旧档读不到这个键就是主货币。
     *
     * ⚠ 币种只在**创建**那一刻定下来，此后扣款 / 退款 / 发货 / 入账 / 手续费**一律从本对象读**
     * （方案「三条硬要求」第 1、3 条）—— 别再去 payload 或 `CurrencyHandler` 现取，
     * 那会让「扣款用 A 币、退款用 B 币」这类错位有可乘之机。
     */
    val currency: MarketCurrency = MarketCurrency.PRIMARY
) {
    fun isActive(): Boolean = status == ListingStatus.ACTIVE

    /** 通知消息用的展示名：名字带主属性色（闪光★由调用方按场景追加），见 [com.shusheng.cobblemarket.util.TypeTextColors.speciesName] */
    fun speciesText(): net.minecraft.text.Text =
        com.shusheng.cobblemarket.util.TypeTextColors.speciesName(
            net.minecraft.text.Text.translatable(extraData["speciesKey"] ?: "cobblemon.species.${species.lowercase()}.name"),
            extraData["primaryType"],
            false
        )

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
        putString("currency", currency.name)
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
                returnedAt = if (nbt.contains("returnedAt")) nbt.getLong("returnedAt") else null,
                // 旧档没这个键（getString 给空串）/ 值是垃圾 ⇒ fromStored 一律落主货币，不抛不 warn
                currency = MarketCurrency.fromStored(nbt.getString("currency"))
            )
        }
    }
}
