package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.shop.*
import com.landosol.toolbox.labyrinth.vision.*
import org.junit.Assert.*
import org.junit.Test

class LabyrinthShopPurchaseRequirementsTest {
    private val rect = EntryPixelRect(0, 0, 20, 20)
    private fun relic(slot: Int, price: Long, stars: Int = 1, mark: LabyrinthRelicMark = LabyrinthRelicMark.CRITICAL) =
        LabyrinthShopItemMatch("shop_item_$slot", rect, rect, 1.0, status = LabyrinthShopItemStatus.AVAILABLE,
            kind = LabyrinthShopItemKind.RELIC, price = price,
            relicMatch = LabyrinthRelicMatch("r$slot", "r$slot", "遗物$slot", mark.label, "1", null,
                1.0, rect, iconRect = rect, rarity = stars))
    private fun role(slot: Int, role: LabyrinthImprintRole, price: Long = 100, choice: Boolean = false) =
        relic(slot, price).copy(kind = LabyrinthShopItemKind.ROLE_IMPRINT, relicMatch = null,
            titleText = "${role.label}型职能的${if (choice) "选择" else "随机"}印记",
            roleImprintLabel = "${role.label}型职能印记", choiceRoleImprint = choice)
    private fun decide(items: List<LabyrinthShopItemMatch>, area: Int = 5, balance: Long? = 1000,
        used: Int = 0, success: Int = used, refresh: Boolean = true, known: Boolean = true,
        fee: Long? = LabyrinthShopPrices.refreshPrice(used), budget: Long? = 600,
        policy: LabyrinthShopPolicy = LabyrinthShopPolicy(finalArea = 3, budgetOptimizationEnabled = true),
        settled: Boolean = true): LabyrinthShopDecision {
        val stock = items + LabyrinthShopSlots.keys.filterNot { key -> items.any { it.slotId == key } }.map {
            LabyrinthShopItemMatch(it, rect, rect, 1.0, status = LabyrinthShopItemStatus.DISABLED)
        }
        return policy.decide(area, stock, emptyList(), refreshAvailable = refresh, refreshAvailabilityKnown = known,
            refreshesUsedInVisit = used, successfulRefreshesInVisit = success, verifiedBalance = balance,
            refreshPrice = fee, refreshPurchaseBudget = budget,
            coverage = LabyrinthShopCoverage(0, 10, LabyrinthShopScanPhase.COMPLETE), transactionsSettled = settled)
    }

    @Test fun `area three never buys three stars even with priority and unlimited gold`() {
        val policy = LabyrinthShopPolicy(relicChoicePolicy = LabyrinthStrategySettings(
            relicMarkPriority = listOf(LabyrinthRelicMark.DEFENSE)).relicPolicy(),
            refreshEnabled = false, budgetOptimizationEnabled = true)
        assertTrue(decide(listOf(relic(1, 600, 3, LabyrinthRelicMark.DEFENSE)), area = 3,
            balance = 100000, policy = policy) is LabyrinthShopDecision.Close)
    }
    @Test fun `area five ordinary stock excludes three stars and refreshes without counting them as purchased`() {
        assertTrue(decide(listOf(relic(1, 600, 3)), balance = 5000) is LabyrinthShopDecision.Refresh)
        assertTrue(decide(listOf(relic(1, 600, 3), relic(2, 600)), balance = 5000) is LabyrinthShopDecision.BuyRelic)
    }
    @Test fun `two settled refreshes allow three stars but two occupied quotas do not`() {
        assertTrue(decide(listOf(relic(1, 600, 3)), used = 2) is LabyrinthShopDecision.BuyRelic)
        assertTrue(decide(listOf(relic(1, 600, 3)), used = 2, success = 0) is LabyrinthShopDecision.Wait)
        assertTrue(decide(listOf(relic(1, 600, 3)), used = 2, success = 1) is LabyrinthShopDecision.Wait)
    }
    @Test fun `cannot fund second effective refresh releases three stars and reserve`() {
        val result = decide(listOf(relic(1, 600, 3)), used = 1, balance = 1000) as LabyrinthShopDecision.BuyRelic
        assertEquals(0L, result.budget!!.reserve)
        assertEquals(600L, result.budget!!.basketCost)
        assertTrue(decide(listOf(relic(1, 600, 3)), refresh = false) is LabyrinthShopDecision.BuyRelic)
        assertTrue(decide(listOf(relic(1, 600, 3)), refresh = false, known = false) is LabyrinthShopDecision.Wait)
    }
    @Test fun `empty reserved basket is not an empty affordable inventory`() {
        val result = decide(listOf(relic(1, 600)), balance = 1000, fee = 300, budget = 600) as LabyrinthShopDecision.BuyRelic
        assertEquals(600L, result.budget!!.basketCost)
        assertEquals(0L, result.budget!!.reserve)
    }
    @Test fun `fee only small remainder unknown funding and zero resource prices cannot refresh`() {
        for ((used, balance) in listOf(0 to 300L, 1 to 600L, 0 to 899L)) {
            assertTrue(decide(emptyList(), used = used, balance = balance) is LabyrinthShopDecision.Close)
        }
        assertTrue(decide(emptyList(), balance = 900) is LabyrinthShopDecision.Refresh)
        assertTrue(decide(emptyList(), budget = null) is LabyrinthShopDecision.Wait)
        assertTrue(decide(emptyList(), budget = 0) is LabyrinthShopDecision.Wait)
        assertTrue(decide(listOf(relic(1, 0)), balance = 300) is LabyrinthShopDecision.Close)
        assertFalse(LabyrinthShopRefreshFunds.sufficient(Long.MAX_VALUE, Long.MAX_VALUE, 1))
    }
    @Test fun `leftover coins close only after eligibility and choice fallback are exhausted`() {
        assertTrue(decide(listOf(relic(1, 600)), balance = 599, refresh = false) is LabyrinthShopDecision.Close)
        assertTrue(decide(listOf(role(1, LabyrinthImprintRole.HEALING, 500, choice = true)),
            balance = 500, refresh = false) is LabyrinthShopDecision.BuyRoleImprint)
        assertTrue(decide(emptyList(), balance = null, refresh = false) is LabyrinthShopDecision.Wait)
        assertTrue(decide(emptyList(), balance = 0, settled = false) is LabyrinthShopDecision.Wait)
        assertTrue(decide(listOf(relic(1, 600).copy(price = null)), refresh = false) is LabyrinthShopDecision.Wait)
    }
    @Test fun `random priority is shared by basket preference and next click while keeping budget`() {
        val priority = LabyrinthRandomImprintPriority(listOf(LabyrinthImprintRole.HEALING))
        val policy = LabyrinthShopPolicy(refreshEnabled = false, budgetOptimizationEnabled = true,
            randomImprintPriority = priority)
        val goods = listOf(role(1, LabyrinthImprintRole.ATTACK), role(2, LabyrinthImprintRole.HEALING))
        assertEquals("shop_item_2", (decide(goods, balance = 100, policy = policy) as LabyrinthShopDecision.BuyRoleImprint).item.slotId)
        assertEquals("shop_item_2", (decide(goods, balance = 200, policy = policy) as LabyrinthShopDecision.BuyRoleImprint).item.slotId)
        assertTrue(decide(goods, balance = 99, policy = policy) is LabyrinthShopDecision.Close)
        assertTrue(decide(listOf(goods[1].copy(titleText = null)), policy = policy) is LabyrinthShopDecision.Wait)
    }
    @Test fun `empty random configuration keeps group and slot ordering without new other group reward`() {
        val priority = LabyrinthRandomImprintPriority()
        val goods = listOf(role(1, LabyrinthImprintRole.DEBUFF), role(2, LabyrinthImprintRole.ATTACK), role(3, LabyrinthImprintRole.TANK))
        assertEquals(listOf("shop_item_1", "shop_item_2", "shop_item_3"), goods.sortedWith(priority.comparator).map { it.slotId })
        val basket = LabyrinthShopBudgetPlanner.plan(listOf(relic(1, 200), role(2, LabyrinthImprintRole.TANK),
            role(3, LabyrinthImprintRole.HEALING)), 200, emptyMap(), null, false)
        assertEquals(listOf("shop_item_1"), basket.selected.map { it.slotId })
    }
    @Test fun `partial role settings cover all roles once and round trip with duplicate rejection`() {
        val settings = LabyrinthStrategySettings(randomImprintRolePriority = listOf(LabyrinthImprintRole.HEALING, LabyrinthImprintRole.BUFF))
        assertNull(settings.validationError())
        assertEquals(settings, LabyrinthStrategySettingsCodec.decode(LabyrinthStrategySettingsCodec.encode(settings)))
        assertNotNull(settings.copy(randomImprintRolePriority = listOf(LabyrinthImprintRole.BUFF, LabyrinthImprintRole.BUFF)).validationError())
        val priority = LabyrinthRandomImprintPriority(settings.randomImprintRolePriority)
        val roles = LabyrinthImprintRole.entries.mapIndexed { index, role -> role(index + 1, role) }.sortedWith(priority.comparator)
        assertEquals(LabyrinthImprintRole.entries.size, roles.map { it.roleImprintLabel }.distinct().size)
        assertEquals(listOf(LabyrinthImprintRole.HEALING, LabyrinthImprintRole.BUFF), roles.take(2).map { LabyrinthImprintRole.fromLabel(it.roleImprintLabel) })
    }
    @Test fun `attribute groups use configured prefix and dynamic relic defaults without exclusions`() {
        val empty = LabyrinthStrategySettings().relicPolicy()
        val early = empty.attributePriorityGroups(emptyMap(), 1)
        val late = empty.attributePriorityGroups(mapOf(LabyrinthRelicMark.ENHANCEMENT to 10), 5)
        assertNotEquals(early, late)
        val partial = LabyrinthStrategySettings(relicMarkPriority = listOf(LabyrinthRelicMark.DEFENSE)).relicPolicy()
            .attributePriorityGroups(emptyMap(), 1)
        assertEquals(listOf(LabyrinthRelicMark.DEFENSE), partial.first())
        assertEquals(LabyrinthRelicMark.entries.toSet(), partial.flatten().toSet())
        assertEquals(LabyrinthRelicMark.entries.size, partial.flatten().size)
    }
}
