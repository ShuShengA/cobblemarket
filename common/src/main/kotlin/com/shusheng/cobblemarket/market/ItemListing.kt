package com.shusheng.cobblemarket.market

import com.shusheng.cobblemarket.currency.MarketCurrency
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
    var returnedAt: Long? = null,
    /**
     * 这一单用什么币种结算。**追加在末尾并给默认值** ⇒ 旧档读不到这个键就是主货币。
     *
     * ⚠ 币种只在**创建**那一刻定下来，此后扣款 / 退款 / 发货 / 入账 / 手续费**一律从本对象读**
     * （方案「三条硬要求」第 1、3 条）—— 别再去 payload 或 `CurrencyHandler` 现取。
     */
    val currency: MarketCurrency = MarketCurrency.PRIMARY,
    /**
     * **客户端视角的物品 NBT**（Polymer 等伪装物品才有值；空 = 与 [itemNbt] 一致）。
     * 只用于下发给客户端**显示**，交易一律用 [itemNbt]。末尾追加 + 默认值 ⇒ 旧档读不到就是空。
     */
    val displayNbt: NbtCompound? = null
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
        putString("currency", currency.name)
        displayNbt?.let { put("displayNbt", it) }
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
            returnedAt = if (nbt.contains("returnedAt")) nbt.getLong("returnedAt") else null,
            // 旧档没这个键（getString 给空串）/ 值是垃圾 ⇒ fromStored 一律落主货币，不抛不 warn
            currency = MarketCurrency.fromStored(nbt.getString("currency")),
            displayNbt = if (nbt.contains("displayNbt")) nbt.getCompound("displayNbt") else null
        )
    }
}
