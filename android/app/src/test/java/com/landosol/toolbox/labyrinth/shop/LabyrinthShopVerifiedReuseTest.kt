package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.labyrinth.vision.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LabyrinthShopVerifiedReuseTest {
    @get:Rule val temp = TemporaryFolder()
    private fun module() = LabyrinthShoppingModule(LabyrinthShoppingJournal(temp.newFolder())).also { it.open(1, 100, null, 0) }

    @Test fun `all types count only after ownership with receipt deduplication and exact after balances`() {
        val module = module()
        module.observeBalance(ShoppingTestData.wallet(1000), 7, 1, 1)
        var balance = 1000L
        for ((index, item) in listOf(ShoppingTestData.item(), ShoppingTestData.role(), ShoppingTestData.role(choice = true)).withIndex()) {
            val now = 10L + index * 10
            module.recordIntent(item, balance, 7, 1, now)
            module.confirmDispatched(now + 1)
            assertNull(module.verifiedBalance(7, 1))
            val receipt = ShoppingTestData.receipt(item.titleText!!, before = balance)
            val commit = module.acceptReceipt(receipt, 7, 1, now + 2)
            assertEquals(index, module.successfulPurchaseCount(7, 1))
            module.markOwnershipCommitted(commit.key, now + 3)
            module.markOwnershipCommitted(commit.key, now + 4)
            module.acceptReceipt(receipt, 7, 1, now + 5)
            balance -= 100
            assertEquals(balance, module.verifiedBalance(7, 1))
            assertEquals(index + 1, module.successfulPurchaseCount(7, 1))
            assertEquals(1, module.successfulPurchaseCount(7, 1, relicsOnly = true))
            assertNull(module.verifiedBalance(8, 1)); assertNull(module.verifiedBalance(7, 2))
        }
        assertEquals(3, module.successfulPurchaseCount(7, 1))
        assertEquals(0, module.successfulPurchaseCount(8, 1))
    }

    @Test fun `cancelled and dispatched unresolved purchases never count or spend again`() {
        val module = module()
        module.observeBalance(ShoppingTestData.wallet(1000), 7, 1, 1)
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 2)
        module.cancelIntent(3)
        assertEquals(0, module.successfulPurchaseCount(7, 1))
        assertEquals(1000L, module.verifiedBalance(7, 1))
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 4)
        module.confirmDispatched(5)
        assertEquals(0, module.successfulPurchaseCount(7, 1))
        assertNull(module.verifiedBalance(7, 1))
    }

    @Test fun `refresh quotas are not success and only exact actual fee changes settle once`() {
        val module = module()
        module.observeBalance(ShoppingTestData.wallet(1000), 7, 1, 1)
        module.prepareRefresh("first", 300, ShoppingTestData.wallet(1000), 7, 1, 2)
        assertEquals(1, module.refreshCount(7, 1))
        assertEquals(0, module.successfulRefreshCount(7, 1))
        module.refreshDispatched("first", 3)
        module.observeBalance(ShoppingTestData.wallet(750), 7, 1, 4)
        assertEquals(0, module.successfulRefreshCount(7, 1))
        assertNull(module.verifiedBalance(7, 1))
        module.observeBalance(ShoppingTestData.wallet(700), 7, 1, 5)
        module.observeBalance(ShoppingTestData.wallet(700), 7, 1, 6)
        assertEquals(1, module.successfulRefreshCount(7, 1))
        assertEquals(700L, module.verifiedBalance(7, 1))
        assertTrue(module.refreshCycleStart(7, 1) > 0)
    }
}
