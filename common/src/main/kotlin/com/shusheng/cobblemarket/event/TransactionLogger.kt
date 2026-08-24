package com.shusheng.cobblemarket.event

import com.shusheng.cobblemarket.CobbleMarket

object TransactionLogger {

    fun register() {
        MarketEvents.ADD.subscribe { event ->
            CobbleMarket.LOGGER.info(
                "[Market] ADD | seller={} species={} price={} fee={}",
                event.listing.sellerName, event.listing.species, event.listing.price, event.fee
            )
        }
        MarketEvents.PURCHASE.subscribe { event ->
            CobbleMarket.LOGGER.info(
                "[Market] PURCHASE | buyer={} seller={} species={} price={}",
                event.buyerUuid, event.sellerUuid, event.listing.species, event.price
            )
        }
        MarketEvents.CANCEL.subscribe { event ->
            CobbleMarket.LOGGER.info(
                "[Market] CANCEL | seller={} species={}",
                event.sellerUuid, event.listing.species
            )
        }
        MarketEvents.RETURN.subscribe { event ->
            CobbleMarket.LOGGER.info(
                "[Market] RETURN | player={} species={}",
                event.playerUuid, event.listing.species
            )
        }
    }
}
