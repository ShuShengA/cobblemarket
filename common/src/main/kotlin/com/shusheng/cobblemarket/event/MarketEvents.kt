package com.shusheng.cobblemarket.event

import com.shusheng.cobblemarket.market.MarketListing
import java.util.UUID

data class AddEvent(val listing: MarketListing, val fee: Int)

data class PurchaseEvent(
    val buyerUuid: UUID,
    val buyerName: String,
    val sellerUuid: UUID,
    val listing: MarketListing,
    val price: Int
)

data class CancelEvent(val sellerUuid: UUID, val listing: MarketListing)

data class ReturnEvent(val playerUuid: UUID, val listing: MarketListing)

object MarketEvents {
    val ADD = Event<AddEvent>()
    val PURCHASE = Event<PurchaseEvent>()
    val CANCEL = Event<CancelEvent>()
    val RETURN = Event<ReturnEvent>()
}
