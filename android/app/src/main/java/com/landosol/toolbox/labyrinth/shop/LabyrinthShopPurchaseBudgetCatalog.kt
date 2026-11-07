package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.gamedata.RelicResource

/** The unknown refill is not predicted; this only rejects refills with no purchasing capacity. */
class LabyrinthShopPurchaseBudgetCatalog(private val relics: List<RelicResource> = emptyList()) {
    fun minimum(
        area: Int?, successfulRefreshesAfter: Int, pricing: LabyrinthShopPricingContext,
        imprints: LabyrinthShopImprintCatalog,
    ): Long? {
        if (area == null || area !in 1..5 || area == 4 || successfulRefreshesAfter < 0) return null
        if (relics.any { it.price < 0 || it.price > 0 && it.rarity.toIntOrNull() == null }) return null
        val allowThreeStar = area == 5 && successfulRefreshesAfter >= 2 || area !in setOf(3, 5)
        val bases = relics.filter { it.price > 0 && it.rarity != "4" && (it.rarity != "3" || allowThreeStar) }
            .map { it.price } + imprints.listedPrices().filter { it > 0 }
        return bases.mapNotNull(pricing::price).filter { it > 0 }.minOrNull()
    }
}

object LabyrinthShopRefreshFunds {
    fun sufficient(balance: Long, fee: Long?, purchaseBudget: Long?): Boolean =
        balance >= 0 && fee != null && fee > 0 && purchaseBudget != null && purchaseBudget > 0 &&
            fee <= balance && purchaseBudget <= balance - fee
}
