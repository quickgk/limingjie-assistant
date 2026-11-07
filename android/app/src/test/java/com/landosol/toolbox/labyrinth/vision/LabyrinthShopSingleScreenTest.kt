package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.*
import org.junit.Assert.*
import org.junit.Test

class LabyrinthShopSingleScreenTest {
    @Test fun `transaction proven sold cards stay in place without OCR or empty slot fabrication`() {
        for (count in 0..3) {
            val scanner = LabyrinthShopStockScanner()
            val sold = LabyrinthShopSlots.keys.drop(count).toSet()
            scanner.expectRemaining(count, sold)
            val raw = ShopScanFixtures.a(scanner.epoch, ShopScanFixtures.goods().mapIndexed { i, item ->
                if (i < count) item else item.copy(status = LabyrinthShopItemStatus.UNKNOWN,
                    categoryState = LabyrinthShopCategoryState.READING, price = null, titleText = null,
                    roleImprintLabel = null)
            })
            assertTrue(raw.emptySlotIds.isEmpty())
            assertFalse(scanner.observe(raw, 10).stockComplete)
            val complete = scanner.observe(raw, 11)
            assertTrue("remaining=$count", complete.stockComplete)
            assertEquals(count, complete.items.size)
            assertEquals(sold, complete.coverage!!.soldSlots)
            assertTrue(complete.coverage!!.absentSlots.isEmpty())
            assertTrue(complete.items.none { it.slotId in sold })
            scanner.invalidate(12)
            assertEquals(sold, scanner.purchasedSlots())
            assertFalse(scanner.observe(raw.copy(stockEpoch = scanner.epoch), 13).stockComplete)
            assertTrue(scanner.observe(raw.copy(stockEpoch = scanner.epoch), 14).stockComplete)
        }
    }

    @Test fun `retained sold slots contradicting a fresh enabled button revoke single screen`() {
        val scanner = LabyrinthShopStockScanner()
        scanner.expectRemaining(3, LabyrinthShopSlots.keys.drop(3).toSet())
        assertFalse(scanner.observe(ShopScanFixtures.a(scanner.epoch), 10).stockComplete)
        assertTrue(scanner.singleScreenRejected)
        assertTrue(scanner.purchasedSlots().isEmpty())
    }

    @Test fun `remaining bottom row fully visible in current B needs no return swipe`() {
        val scanner = LabyrinthShopStockScanner()
        scanner.expectRemaining(3, LabyrinthShopSlots.keys.take(3).toSet())
        val raw = ShopScanFixtures.b(scanner.epoch, ShopScanFixtures.goods().mapIndexed { i, item ->
            if (i < 3) item.copy(status = LabyrinthShopItemStatus.DISABLED) else item
        }).let { view -> view.copy(items = view.items.mapIndexed { i, item -> if (i < 3) item else
            item.copy(titleText = "攻击型职能的随机印记", kind = LabyrinthShopItemKind.ROLE_IMPRINT) }) }
        val reading = raw.copy(items = raw.items.map { if (it.slotId in scanner.purchasedSlots()) it else
            it.copy(categoryState = LabyrinthShopCategoryState.READING, titleText = null) })
        assertNull(scanner.observe(reading, 8).coverage?.scroll)
        assertNull(scanner.observe(raw, 10).coverage?.scroll)
        assertTrue(scanner.observe(raw, 11).stockComplete)
    }

    private fun compact(scanner: LabyrinthShopStockScanner, count: Int) = ShopScanFixtures.a(scanner.epoch,
        ShopScanFixtures.goods().take(count)).copy(emptySlotIds = LabyrinthShopSlots.keys.drop(count).toSet())

    @Test fun `three two one and empty remaining inventories need two fresh real views`() {
        for (count in 1..3) {
            val scanner = LabyrinthShopStockScanner()
            scanner.expectRemaining(count)
            val raw = compact(scanner, count)
            assertFalse(scanner.observe(raw, 10).stockComplete)
            val complete = scanner.observe(raw, 11)
            assertTrue("remaining=$count", complete.stockComplete)
            assertEquals(count, complete.items.size)
            assertEquals(LabyrinthShopCoverageMode.SINGLE_SCREEN, complete.coverage?.mode)
            assertNull(complete.coverage?.scroll)
            assertFalse(scanner.observe(raw, 11).stockComplete)
            assertTrue(complete.items.none { it.status == LabyrinthShopItemStatus.DISABLED })
        }
    }

    @Test fun `full subset forged slots and unknown missing cards do not become complete`() {
        val scanner = LabyrinthShopStockScanner()
        val goods = ShopScanFixtures.goods().take(2)
        assertFalse(LabyrinthShopCoverage(0, 10, LabyrinthShopScanPhase.COMPLETE,
            itemSlots = goods.map { it.slotId }.toSet()).complete(goods))
        assertFalse(LabyrinthShopCoverage(0, 10, LabyrinthShopScanPhase.COMPLETE,
            mode = LabyrinthShopCoverageMode.SINGLE_SCREEN, itemSlots = emptySet(), remainingCount = 0).complete(emptyList()))
        scanner.expectRemaining(2)
        val uncertain = compact(scanner, 2).copy(emptySlotIds = emptySet())
        repeat(14) { assertFalse(scanner.observe(uncertain, 10L + it).stockComplete) }
        assertTrue(scanner.singleScreenRejected)
        assertEquals(LabyrinthShopScroll.TO_BOTTOM,
            scanner.observe(ShopScanFixtures.a(scanner.epoch), 30).coverage?.scroll)
    }

    @Test fun `extra card contradiction rejects compact mode and returns to full scan`() {
        val scanner = LabyrinthShopStockScanner()
        scanner.expectRemaining(2)
        assertFalse(scanner.observe(compact(scanner, 3), 10).stockComplete)
        assertTrue(scanner.singleScreenRejected)
        assertEquals(LabyrinthShopScroll.TO_BOTTOM,
            scanner.observe(ShopScanFixtures.a(scanner.epoch), 11).coverage?.scroll)
    }

    @Test fun `unread identity price missing stale or moving pixels never grant compact completeness`() {
        for (change in listOf<(LabyrinthShopObservation) -> LabyrinthShopObservation>(
            { it.copy(items = it.items.map { item -> item.copy(categoryState = LabyrinthShopCategoryState.READING) }) },
            { it.copy(items = it.items.map { item -> item.copy(price = null) }) },
            { it.copy(items = it.items.map { item -> item.copy(status = LabyrinthShopItemStatus.UNKNOWN) }) },
            { it.copy(viewportPixels = null) },
        )) {
            val scanner = LabyrinthShopStockScanner(); scanner.expectRemaining(1)
            val raw = change(compact(scanner, 1))
            assertFalse(scanner.observe(raw, 10).stockComplete)
            assertFalse(scanner.observe(raw, 11).stockComplete)
        }
        val scanner = LabyrinthShopStockScanner(); scanner.expectRemaining(1)
        val raw = compact(scanner, 1)
        scanner.observe(raw, 10)
        assertFalse(scanner.observe(raw.copy(viewportPixels = ShopScanFixtures.pixels(seed = 123)), 11).stockComplete)
    }

    @Test fun `compact coverage reaches the real policy without padding disabled items`() {
        val scanner = LabyrinthShopStockScanner(); scanner.expectRemaining(2)
        val raw = compact(scanner, 2)
        scanner.observe(raw, 10)
        val complete = scanner.observe(raw, 11)
        val decision = LabyrinthShopPolicy(budgetOptimizationEnabled = true, refreshEnabled = false).decide(
            5, complete.items, emptyList(), refreshAvailable = false, verifiedBalance = 2400,
            coverage = complete.coverage)
        assertTrue(decision is LabyrinthShopDecision.BuyRoleImprint)
        assertTrue((decision as LabyrinthShopDecision.BuyRoleImprint).item.slotId in complete.coverage!!.itemSlots)
    }

    @Test fun `zero remaining needs executed positioning and two fresh absence views even after recovery`() {
        for (expected in listOf(null, 0)) {
            val scanner = LabyrinthShopStockScanner(); scanner.expectRemaining(expected)
            val raw = compact(scanner, 0).copy(viewport = LabyrinthShopViewport.UNKNOWN)
            assertEquals(LabyrinthShopScroll.TO_TOP, scanner.observe(raw, 10).coverage?.scroll)
            assertFalse(scanner.observe(raw, 11).stockComplete)
            assertTrue(scanner.dispatched(scanner.epoch, LabyrinthShopScroll.TO_TOP, 12))
            assertFalse(scanner.observe(raw, 13).stockComplete)
            scanner.actionFinished(scanner.epoch, true)
            assertFalse(scanner.observe(raw, 14).stockComplete)
            val complete = scanner.observe(raw, 15)
            assertTrue(complete.stockComplete)
            assertTrue(complete.items.isEmpty())
            assertEquals(LabyrinthShopSlots.keys.toSet(), complete.coverage!!.absentSlots)
            assertFalse(complete.coverage!!.copy(absentSlots = emptySet()).complete(emptyList()))
        }
    }

    @Test fun `actual compact positions need not be packed from the left`() {
        for (positions in listOf(listOf(1, 3), listOf(3))) {
            val scanner = LabyrinthShopStockScanner(); scanner.expectRemaining(positions.size)
            val goods = ShopScanFixtures.goods().filter { item -> positions.any { item.slotId == "shop_item_$it" } }
            val raw = ShopScanFixtures.a(scanner.epoch, goods).copy(
                emptySlotIds = LabyrinthShopSlots.keys.toSet() - goods.map { it.slotId }.toSet())
            assertFalse(scanner.observe(raw, 10).stockComplete)
            val complete = scanner.observe(raw, 11)
            assertTrue(complete.stockComplete)
            assertEquals(goods.map { it.slotId }, complete.items.map { it.slotId })
        }
    }

    @Test fun `full scan retains real five or four goods and proves absent positions without disabled padding`() {
        for (count in listOf(5, 4)) {
            val scanner = LabyrinthShopStockScanner()
            val all = ShopScanFixtures.goods()
            val absent = LabyrinthShopSlots.keys.drop(count).toSet()
            val top = ShopScanFixtures.a(items = all).copy(emptySlotIds = absent)
            val bottom = ShopScanFixtures.b(items = all).copy(emptySlotIds = absent)
            assertEquals(LabyrinthShopScroll.TO_BOTTOM, scanner.observe(top, 10).coverage?.scroll)
            assertTrue(scanner.dispatched(scanner.epoch, LabyrinthShopScroll.TO_BOTTOM, 11))
            scanner.actionFinished(scanner.epoch, true)
            assertFalse(scanner.observe(bottom, 12).stockComplete)
            val complete = scanner.observe(bottom, 13)
            assertTrue(complete.stockComplete)
            assertEquals(count, complete.items.size)
            assertEquals(absent, complete.coverage!!.absentSlots)
            assertTrue(complete.items.none { it.status == LabyrinthShopItemStatus.DISABLED })
            assertFalse(complete.coverage!!.copy(absentSlots = emptySet()).complete(complete.items))
            val moved = bottom.copy(emptySlotIds = emptySet())
            assertFalse(scanner.observe(moved, 14).stockComplete)
        }
    }

    @Test fun `compact top positioning allows a clamped swipe but still needs two fresh complete top views`() {
        for (count in 0..2) {
            val scanner = LabyrinthShopStockScanner(); scanner.expectRemaining(count)
            val top = compact(scanner, count)
            val offTop = top.copy(viewport = LabyrinthShopViewport.UNKNOWN, contentOffset = 40)
            assertEquals(LabyrinthShopScroll.TO_TOP, scanner.observe(offTop, 10).coverage?.scroll)
            assertTrue(scanner.dispatched(scanner.epoch, LabyrinthShopScroll.TO_TOP, 11))
            scanner.actionFinished(scanner.epoch, true)
            assertFalse(scanner.observe(top, 12).stockComplete)
            assertTrue(scanner.observe(top, 13).stockComplete)
        }
    }

    @Test fun `without real buttons the recognizer cannot infer a top viewport from default offset`() {
        val raw = LabyrinthShopRecognizer().recognize(PixelImage(1920, 1080, IntArray(1920 * 1080) { -1 }))
        assertEquals(LabyrinthShopViewport.UNKNOWN, raw.viewport)
        assertFalse(raw.stockComplete)
        assertEquals(LabyrinthShopSlots.keys.toSet(), raw.emptySlotIds)
    }

    @Test fun `only uniform light interiors establish absent cards`() {
        val rect = EntryPixelRect(0, 0, 100, 100)
        assertTrue(LabyrinthShopEmptySlotEvidence.matches(PixelImage(100, 100, IntArray(10000) { -1 }), rect))
        assertFalse(LabyrinthShopEmptySlotEvidence.matches(PixelImage(100, 100, IntArray(10000) { 0xff000000.toInt() }), rect))
        assertFalse(LabyrinthShopEmptySlotEvidence.matches(PixelImage(100, 100, IntArray(10000) {
            if (it % 10 == 0) 0xff999999.toInt() else -1 }), rect))
    }
}
