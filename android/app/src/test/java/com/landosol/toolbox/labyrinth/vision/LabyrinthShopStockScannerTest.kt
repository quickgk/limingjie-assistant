package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic translated content, not a claim about two unrelated device screenshots. */
object ShopScanFixtures {
    fun pixels(shift: Int = 0, width: Int = 1920, seed: Int = 7, changedTitle: Boolean = false): LabyrinthShopViewportPixels {
        val pad = (width - 1920) / 2
        val colors = IntArray(width * 1080) { i ->
            val x = i % width - pad
            val y = i / width + shift
            var hash = x * 73856093 xor (y * 19349663) xor seed
            hash = hash xor (hash ushr 13)
            val c = (hash and 255).let { if (changedTitle && x in 100..570 && i / width in 505..530) 255 - it else it }
            0xff000000.toInt() or (c shl 16) or (c shl 8) or c
        }
        return requireNotNull(LabyrinthShopViewportPixels.sample(PixelImage(width, 1080, colors)))
    }
    fun goods(): List<LabyrinthShopItemMatch> = LabyrinthShopSlots.keys.mapIndexed { i, key ->
        val rect = EntryPixelRect(350 + i % 3 * 578, 480 + i / 3 * 380, 290, 90)
        LabyrinthShopItemMatch(key, rect, rect, 0.99, 1.0, LabyrinthShopItemStatus.AVAILABLE,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT, titleText = "攻击型职能的随机印记",
            roleImprintLabel = "攻击型职能印记", price = 1200)
    }
    fun a(epoch: Long = 0, items: List<LabyrinthShopItemMatch> = goods(), width: Int = 1920) =
        LabyrinthShopObservation(items, stockEpoch = epoch, viewport = LabyrinthShopViewport.A,
            viewportPixels = pixels(width = width))
    fun b(epoch: Long = 0, items: List<LabyrinthShopItemMatch> = goods(), shift: Int = 105, width: Int = 1920) =
        LabyrinthShopObservation(items.map { it.copy(titleText = null, kind = LabyrinthShopItemKind.UNKNOWN,
            relicMatch = null, buyButtonRect = it.buyButtonRect.copy(top = it.buyButtonRect.top - 105)) },
            stockEpoch = epoch, viewport = LabyrinthShopViewport.B, contentOffset = 105,
            viewportPixels = pixels(shift, width))
    fun complete(scanner: LabyrinthShopStockScanner, items: List<LabyrinthShopItemMatch> = goods(), at: Long = 10): LabyrinthShopObservation {
        scanner.observe(a(scanner.epoch, items), at)
        check(scanner.dispatched(scanner.epoch, LabyrinthShopScroll.TO_BOTTOM, at + 1))
        scanner.actionFinished(scanner.epoch, true)
        check(!scanner.observe(b(scanner.epoch, items), at + 2).stockComplete)
        return scanner.observe(b(scanner.epoch, items), at + 3)
    }
}

class LabyrinthShopStockScannerTest {
    @Test fun `new epoch rejects queued pre-mutation A even when processing happens later`() {
        val scanner = LabyrinthShopStockScanner()
        ShopScanFixtures.complete(scanner)
        scanner.invalidate(200)
        assertNull(scanner.observe(ShopScanFixtures.a(scanner.epoch), 199).coverage)
        assertNull(scanner.observe(ShopScanFixtures.a(scanner.epoch), 200).coverage)
        assertEquals(LabyrinthShopScroll.TO_BOTTOM, scanner.observe(ShopScanFixtures.a(scanner.epoch), 201).coverage?.scroll)
    }

    @Test fun `six same-name slots merge by position and use latest B buttons`() {
        val scanner = LabyrinthShopStockScanner()
        val goods = ShopScanFixtures.goods()
        val complete = ShopScanFixtures.complete(scanner, goods)
        assertTrue(complete.stockComplete)
        assertEquals(LabyrinthShopSlots.keys, complete.items.map { it.slotId })
        assertEquals(List(6) { "攻击型职能的随机印记" }, complete.items.map { it.titleText })
        assertEquals(goods.map { it.buyButtonRect.left }, complete.items.map { it.buyButtonRect.left })
        val disabled = goods.mapIndexed { i, item -> if (i == 4) item.copy(status = LabyrinthShopItemStatus.DISABLED) else item }
        val next = scanner.observe(ShopScanFixtures.b(scanner.epoch, disabled), 20)
        assertTrue(next.stockComplete)
        assertEquals(LabyrinthShopItemStatus.DISABLED, next.items[4].status)
    }

    @Test fun `visible disabled three request swipe not close refresh or buy`() {
        val scanner = LabyrinthShopStockScanner()
        val goods = ShopScanFixtures.goods().mapIndexed { i, it -> it.copy(status =
            if (i < 3) LabyrinthShopItemStatus.DISABLED else LabyrinthShopItemStatus.UNKNOWN) }
        val a = scanner.observe(ShopScanFixtures.a(items = goods), 1)
        val decision = LabyrinthShopPolicy().decide(5, a.items, emptyList(), refreshAvailable = true, coverage = a.coverage)
        assertEquals(LabyrinthShopDecision.Scan(LabyrinthShopScroll.TO_BOTTOM), decision)
        val swipe = requireNotNull(labyrinthShopSwipe(1920, 1080, (decision as LabyrinthShopDecision.Scan).direction))
        assertTrue(swipe.start.y > swipe.end.y)
        assertFalse(a.stockComplete)
    }

    @Test fun `executed old frame noop reverse random change or wrong epoch never complete`() {
        for (bad in listOf(ShopScanFixtures.b(shift = 0), ShopScanFixtures.b(shift = -105),
                ShopScanFixtures.b(epoch = 99), ShopScanFixtures.b().copy(viewportPixels = ShopScanFixtures.pixels(105, seed = 51)))) {
            val scanner = LabyrinthShopStockScanner()
            scanner.observe(ShopScanFixtures.a(), 100)
            assertTrue(scanner.dispatched(0, LabyrinthShopScroll.TO_BOTTOM, 200))
            scanner.actionFinished(0, true)
            assertFalse(scanner.observe(bad, 150).stockComplete)
            assertFalse(scanner.observe(bad, 300).stockComplete)
            assertFalse(scanner.observe(bad, 400).stockComplete)
        }
        val scanner = LabyrinthShopStockScanner()
        scanner.observe(ShopScanFixtures.a(), 1)
        scanner.dispatched(0, LabyrinthShopScroll.TO_BOTTOM, 2)
        scanner.actionFinished(0, false)
        assertFalse(scanner.observe(ShopScanFixtures.b(), 3).stockComplete)
        assertFalse(scanner.observe(ShopScanFixtures.b(scanner.epoch), 4).stockComplete)
    }

    @Test fun `stable B requires two distinct captures and dispatched acknowledgement`() {
        val scanner = LabyrinthShopStockScanner()
        scanner.observe(ShopScanFixtures.a(), 1)
        scanner.dispatched(0, LabyrinthShopScroll.TO_BOTTOM, 10)
        assertFalse(scanner.observe(ShopScanFixtures.b(), 11).stockComplete)
        scanner.actionFinished(0, true)
        assertFalse(scanner.observe(ShopScanFixtures.b(), 12).stockComplete)
        assertFalse(scanner.observe(ShopScanFixtures.b(), 12).stockComplete)
        assertTrue(scanner.observe(ShopScanFixtures.b(), 13).stockComplete)
    }

    @Test fun `pixel-less or wrong epoch frame breaks consecutive stable B evidence`() {
        for (wrongEpoch in listOf(false, true)) {
            val scanner = LabyrinthShopStockScanner()
            scanner.observe(ShopScanFixtures.a(), 1)
            scanner.dispatched(0, LabyrinthShopScroll.TO_BOTTOM, 10)
            scanner.actionFinished(0, true)
            assertFalse(scanner.observe(ShopScanFixtures.b(), 11).stockComplete)
            val invalid = if (wrongEpoch) ShopScanFixtures.b(epoch = 99)
                else ShopScanFixtures.b().copy(viewportPixels = null)
            assertNull(scanner.observe(invalid, 12).coverage)
            assertFalse(scanner.observe(ShopScanFixtures.b(), 13).stockComplete)
            assertTrue(scanner.observe(ShopScanFixtures.b(), 14).stockComplete)
        }
    }

    @Test fun `returning from B requires executed fresh A with downward pixel translation`() {
        for (badPixels in listOf(ShopScanFixtures.pixels(105), ShopScanFixtures.pixels(210),
                ShopScanFixtures.pixels(seed = 51))) {
            val scanner = LabyrinthShopStockScanner()
            assertEquals(LabyrinthShopScroll.TO_TOP, scanner.observe(ShopScanFixtures.b(), 1).coverage?.scroll)
            assertTrue(scanner.dispatched(0, LabyrinthShopScroll.TO_TOP, 10))
            assertNull(scanner.observe(ShopScanFixtures.a(), 11).coverage?.scroll)
            scanner.actionFinished(0, true)
            assertNull(scanner.observe(ShopScanFixtures.a().copy(viewportPixels = badPixels), 12).coverage?.scroll)
            assertEquals(LabyrinthShopScroll.TO_BOTTOM, scanner.observe(ShopScanFixtures.a(), 13).coverage?.scroll)
        }
    }

    @Test fun `completed stock rejects refill signal and never revives an unread disabled identity`() {
        val scanner = LabyrinthShopStockScanner()
        val goods = ShopScanFixtures.goods().map { it.copy(status = LabyrinthShopItemStatus.DISABLED,
            categoryState = LabyrinthShopCategoryState.EXHAUSTED, kind = LabyrinthShopItemKind.UNKNOWN, titleText = null) }
        assertTrue(ShopScanFixtures.complete(scanner, goods).stockComplete)
        val epoch = scanner.epoch
        assertFalse(scanner.observe(ShopScanFixtures.b(items = ShopScanFixtures.goods()), 20).stockComplete)
        assertTrue(scanner.epoch > epoch)
        assertEquals(LabyrinthShopScroll.TO_TOP, scanner.observe(ShopScanFixtures.b(scanner.epoch), 21).coverage?.scroll)
    }

    @Test fun `a single changed visible title invalidates completed stock instead of averaging it away`() {
        val scanner = LabyrinthShopStockScanner()
        assertTrue(ShopScanFixtures.complete(scanner).stockComplete)
        val epoch = scanner.epoch
        val changed = ShopScanFixtures.b().copy(viewportPixels = ShopScanFixtures.pixels(105, changedTitle = true))
        assertFalse(scanner.observe(changed, 20).stockComplete)
        assertTrue(scanner.epoch > epoch)
    }

    @Test fun `missing duplicate and unknown buttons cannot grant coverage`() {
        val goods = ShopScanFixtures.goods()
        for (bad in listOf(goods.dropLast(1), goods.dropLast(1) + goods.first(),
            goods.mapIndexed { i, it -> if (i == 5) it.copy(status = LabyrinthShopItemStatus.UNKNOWN) else it })) {
            val scanner = LabyrinthShopStockScanner()
            scanner.observe(ShopScanFixtures.a(), 1)
            scanner.dispatched(0, LabyrinthShopScroll.TO_BOTTOM, 2)
            scanner.actionFinished(0, true)
            assertFalse(scanner.observe(ShopScanFixtures.b(items = bad), 3).stockComplete)
            assertFalse(scanner.observe(ShopScanFixtures.b(items = bad), 4).stockComplete)
        }
    }

    @Test fun `disabled unread identities complete but available unread identities do not`() {
        val unread = ShopScanFixtures.goods().map { it.copy(kind = LabyrinthShopItemKind.UNKNOWN,
            titleText = null, categoryState = LabyrinthShopCategoryState.EXHAUSTED) }
        assertTrue(ShopScanFixtures.complete(LabyrinthShopStockScanner(), unread.map { it.copy(status = LabyrinthShopItemStatus.DISABLED) }).stockComplete)
        assertFalse(ShopScanFixtures.complete(LabyrinthShopStockScanner(), unread).stockComplete)
    }

    @Test fun `purchase refill scope generation and viewport invalidate instead of reusing old B`() {
        for (change in listOf<(LabyrinthShopStockScanner) -> Unit>({ it.invalidate() }, { it.bindScope("new-node") },
            { it.bindScope("new-account-enter-session") }, { it.bindScope("2560x1080") })) {
            val scanner = LabyrinthShopStockScanner()
            val old = ShopScanFixtures.complete(scanner)
            change(scanner)
            assertFalse(scanner.observe(old, 100).stockComplete)
            val startAtBottom = scanner.observe(ShopScanFixtures.b(scanner.epoch), 101)
            assertEquals(LabyrinthShopScroll.TO_TOP, startAtBottom.coverage?.scroll)
            assertTrue(scanner.dispatched(scanner.epoch, LabyrinthShopScroll.TO_TOP, 102))
            scanner.actionFinished(scanner.epoch, true)
            val compacted = ShopScanFixtures.goods().mapIndexed { i, it -> it.copy(titleText = "治疗型职能的随机印记", roleImprintLabel = "治疗型职能印记") }
            val top = scanner.observe(ShopScanFixtures.a(scanner.epoch, compacted), 103)
            assertEquals(LabyrinthShopScroll.TO_BOTTOM, top.coverage?.scroll)
            scanner.dispatched(scanner.epoch, LabyrinthShopScroll.TO_BOTTOM, 104)
            scanner.actionFinished(scanner.epoch, true)
            scanner.observe(ShopScanFixtures.b(scanner.epoch, compacted), 105)
            val rebuilt = scanner.observe(ShopScanFixtures.b(scanner.epoch, compacted), 106)
            assertTrue(rebuilt.stockComplete)
            assertTrue(rebuilt.items.all { it.roleImprintLabel == "治疗型职能印记" })
        }
    }

    @Test fun `area four and disabled purchase switch can exit without any scan`() {
        assertTrue(LabyrinthShopPolicy().decide(4, emptyList(), emptyList(), refreshAvailable = true) is LabyrinthShopDecision.Close)
        assertTrue(LabyrinthShopPolicy(buyRelics = false).decide(5, emptyList(), emptyList(), refreshAvailable = true) is LabyrinthShopDecision.Close)
        assertTrue(LabyrinthShopPolicy(refreshEnabled = false).decide(5, emptyList(), emptyList(), refreshAvailable = true) is LabyrinthShopDecision.Wait)
    }

    @Test fun `stable swapped columns and mismatched hitbox displacement never complete`() {
        for (change in listOf<(List<LabyrinthShopItemMatch>) -> List<LabyrinthShopItemMatch>>(
            { items -> items.mapIndexed { i, item -> if (i < 2) item.copy(slotId = items[1 - i].slotId) else item } },
            { items -> items.map { it.copy(buyButtonRect = it.buyButtonRect.copy(left = it.buyButtonRect.left + 25)) } },
            { items -> items.map { it.copy(buyButtonRect = it.buyButtonRect.copy(top = it.buyButtonRect.top - 25)) } },
        )) {
            val scanner = LabyrinthShopStockScanner()
            scanner.observe(ShopScanFixtures.a(), 10)
            assertTrue(scanner.dispatched(scanner.epoch, LabyrinthShopScroll.TO_BOTTOM, 11))
            scanner.actionFinished(scanner.epoch, true)
            val normal = ShopScanFixtures.b()
            val invalid = normal.copy(items = change(normal.items))
            assertFalse(scanner.observe(invalid, 12).stockComplete)
            assertFalse(scanner.observe(invalid, 13).stockComplete)
        }
    }

    @Test fun `wide screens use reference fit for both motion and real swipe`() {
        val top = ShopScanFixtures.pixels(width = 2560)
        assertEquals(105, ShopScanFixtures.pixels(105, width = 2560).upwardDisplacementFrom(top))
        val normal = requireNotNull(labyrinthShopSwipe(1920, 1080, LabyrinthShopScroll.TO_BOTTOM))
        val wide = requireNotNull(labyrinthShopSwipe(2560, 1080, LabyrinthShopScroll.TO_BOTTOM))
        assertEquals(normal.start.x + 320f, wide.start.x)
        assertEquals(normal.start.y, wide.start.y)
        assertEquals(normal.end.y, wide.end.y)
    }
}
