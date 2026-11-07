package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.gamedata.LabyrinthSummonUnitResource
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import org.junit.Assert.*
import org.junit.Test

class LabyrinthShopPricingTest {
    @Test fun `all catalog price tiers use additive integer discounts`() {
        val expected = mapOf(
            1200 to listOf(1200L, 900L, 1080L, 780L),
            1800 to listOf(1800L, 1350L, 1620L, 1170L),
            2800 to listOf(2800L, 2100L, 2520L, 1820L),
            3600 to listOf(3600L, 2700L, 3240L, 2340L),
            3800 to listOf(3800L, 2850L, 3420L, 2470L),
            9999 to listOf(9999L, 7499L, 8999L, 6499L),
        )
        val contexts = listOf(context(false, false), context(true, false), context(false, true), context(true, true))
        expected.forEach { (base, prices) ->
            assertEquals("base=$base", prices, contexts.map { it.price(base) })
        }
        assertEquals(1_395_864_370L, context(true, true).price(Int.MAX_VALUE))
    }

    @Test fun `zero means not listed and missing or conflicting identities are never guessed`() {
        val pricing = context(true, true)
        val zero = pricing.price(ShoppingTestData.item(price = 0), LabyrinthShopImprintCatalog())
        assertEquals(0L, zero.price)
        assertFalse(zero.purchasable)
        assertNull(pricing.price(null as Int?))
        assertNull(pricing.price(-1))
        val missing = ShoppingTestData.item().let { it.copy(relicMatch = it.relicMatch!!.copy(basePrice = null)) }
        assertNull(pricing.price(missing, LabyrinthShopImprintCatalog()).price)
        assertNull(pricing.price(ShoppingTestData.item().copy(kind = LabyrinthShopItemKind.UNKNOWN), LabyrinthShopImprintCatalog()).price)
        assertNull(pricing.price(ShoppingTestData.role(), LabyrinthShopImprintCatalog()).price)
    }

    @Test fun `snapshot belongs to actual account and never follows mutable settings`() {
        val acquired = mutableSetOf(7L)
        val a = LabyrinthShopPricingContext.snapshot(7, 100, 1, acquired)
        val b = LabyrinthShopPricingContext.snapshot(8, 100, 1, acquired)
        val unknown = LabyrinthShopPricingContext.snapshot(null, 100, 1, acquired)
        acquired.clear(); acquired += 8L
        assertTrue(a.node21Acquired); assertFalse(b.node21Acquired); assertFalse(unknown.node21Acquired)
        assertEquals(1350L, a.price(1800)); assertEquals(1800L, b.price(1800))
        assertEquals(7L, a.accountId); assertEquals(100L, a.enterId)
    }

    @Test fun `only actual guild three grants pricing oath without needing inventory observations`() {
        assertTrue(LabyrinthShopPricingContext(guildId = 3).hasOath)
        for (guild in listOf(null, 0, 1, 2, 4, 5)) {
            assertFalse(LabyrinthShopPricingContext(guildId = guild).hasOath)
            assertEquals(1800L, LabyrinthShopPricingContext(guildId = guild).price(1800))
        }
    }

    @Test fun `imprint catalog keys include random versus choice without exposing a relic id`() {
        val catalog = LabyrinthShopImprintCatalog(listOf(
            LabyrinthSummonUnitResource("11003000", "攻击型职能的选择印记", 1, 1, 2800),
            LabyrinthSummonUnitResource("31003000", "攻击型职能的随机印记", 3, 1, 1200),
            LabyrinthSummonUnitResource("31000999", "公主庆典角色随机印记", 3, 1, 1800),
        ))
        val pricing = context(true, true)
        val choice = pricing.price(ShoppingTestData.role(choice = true), catalog)
        val random = pricing.price(ShoppingTestData.role(), catalog)
        assertEquals(1820L, choice.price); assertEquals(780L, random.price)
        assertNull(choice.relicMatch); assertNull(random.relicMatch)
        assertNull(catalog.find(ShoppingTestData.role().copy(titleText = "公主庆典角色随机印记")))
        assertNull(catalog.find(ShoppingTestData.role().copy(choiceRoleImprint = true)))
        assertNull(catalog.find(ShoppingTestData.role().copy(roleImprintLabel = "全体选择印记")))
    }

    @Test fun `refresh prices are undiscounted bounded and do not wrap invalid counters`() {
        assertEquals(300L, LabyrinthShopPrices.refreshPrice(0))
        assertEquals(600L, LabyrinthShopPrices.refreshPrice(1))
        for (count in listOf(-1, 2, 3, Int.MAX_VALUE)) assertNull(LabyrinthShopPrices.refreshPrice(count))
    }

    private fun context(node: Boolean, oath: Boolean) =
        LabyrinthShopPricingContext(accountId = 1, enterId = 100, guildId = if (oath) 3 else 1, node21Acquired = node)
}
