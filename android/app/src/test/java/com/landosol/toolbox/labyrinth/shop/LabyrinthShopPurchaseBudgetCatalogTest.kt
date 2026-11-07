package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.gamedata.RelicResource
import com.landosol.toolbox.gamedata.LabyrinthSummonUnitResource
import org.junit.Assert.*
import org.junit.Test

class LabyrinthShopPurchaseBudgetCatalogTest {
    private fun catalog(vararg pairs: Pair<Int, Int>) = LabyrinthShopPurchaseBudgetCatalog(pairs.mapIndexed { index, (stars, price) ->
        RelicResource(id = "$index", rarity = stars.toString(), price = price) })
    private fun imprints(price: Int) = LabyrinthShopImprintCatalog(listOf(
        LabyrinthSummonUnitResource(id = "31001000", name = "攻击型职能的随机印记", summonType = 3, summonNum = 1, price = price)))

    @Test fun `minimum uses allowed resources not observed stock and excludes zero prices`() {
        val catalog = catalog(1 to 0, 1 to 1800, 2 to 2800, 3 to 500)
        assertEquals(1200L, catalog.minimum(3, 1, LabyrinthShopPricingContext(), imprints(1200)))
        assertEquals(1200L, catalog.minimum(5, 1, LabyrinthShopPricingContext(), imprints(1200)))
        assertEquals(500L, catalog.minimum(5, 2, LabyrinthShopPricingContext(), imprints(1200)))
        assertEquals(1800L, catalog.minimum(3, 1, LabyrinthShopPricingContext(), LabyrinthShopImprintCatalog()))
        assertNull(catalog(1 to 0).minimum(5, 1, LabyrinthShopPricingContext(), LabyrinthShopImprintCatalog()))
    }
    @Test fun `discount changes goods funding while refresh fees stay fixed`() {
        val catalog = catalog(1 to 1800)
        val context = LabyrinthShopPricingContext(accountId = 1, enterId = 100, guildId = 3, node21Acquired = true)
        val budget = catalog.minimum(5, 1, context, imprints(1200))
        assertEquals(780L, budget)
        assertEquals(300L, LabyrinthShopPrices.refreshPrice(0))
        assertEquals(600L, LabyrinthShopPrices.refreshPrice(1))
        assertFalse(LabyrinthShopRefreshFunds.sufficient(1079, 300, budget))
        assertTrue(LabyrinthShopRefreshFunds.sufficient(1080, 300, budget))
    }
    @Test fun `invalid area or incomplete resource metadata does not become zero funding`() {
        val catalog = LabyrinthShopPurchaseBudgetCatalog(listOf(RelicResource(id = "unknown", price = 1800)))
        assertNull(catalog.minimum(5, 1, LabyrinthShopPricingContext(), imprints(1200)))
        assertNull(catalog(1 to 1800).minimum(4, 1, LabyrinthShopPricingContext(), imprints(1200)))
        assertNull(catalog(1 to 1800).minimum(null, 1, LabyrinthShopPricingContext(), imprints(1200)))
    }
}
