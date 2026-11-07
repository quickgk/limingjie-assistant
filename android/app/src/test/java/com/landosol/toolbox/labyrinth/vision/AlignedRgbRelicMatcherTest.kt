package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import org.junit.Assert.*
import org.junit.Test

class AlignedRgbRelicMatcherTest {
    private fun template(id: String, seed: Int, size: Int = 128): LabyrinthRelicTemplate {
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            // Smooth coordinate channels keep a meaningful coarse peak but distinguish
            // adjacent sampled pixels; constant-color blocks can hide an odd-pixel shift.
            val red = x * 2
            val green = y * 2
            val blue = ((x / 16 + y / 16) * 13 + seed * 29) and 255
            0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
        return LabyrinthRelicTemplate(id, id, "加速", "1", "", PixelImage(size, size, pixels))
    }

    private fun translated(source: PixelImage, rect: EntryPixelRect, dx: Int, dy: Int): PixelImage {
        val pixels = IntArray(240 * 240) { 0xff303030.toInt() }
        // Fill the mapped cells, rather than leaving holes in the larger rendered icon.
        // Its source sample coordinates still exactly match the matcher's integer mapping.
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val startX = x * (rect.width - 1) / (source.width - 1)
            val startY = y * (rect.height - 1) / (source.height - 1)
            val endX = if (x + 1 == source.width) rect.width else (x + 1) * (rect.width - 1) / (source.width - 1)
            val endY = if (y + 1 == source.height) rect.height else (y + 1) * (rect.height - 1) / (source.height - 1)
            for (targetY in startY until endY) for (targetX in startX until endX) {
                pixels[(rect.top + dy + targetY) * 240 + rect.left + dx + targetX] = source[x, y]
            }
        }
        return PixelImage(240, 240, pixels)
    }

    @Test fun `bounded refinement includes odd pixel alignment omitted by coarse grid`() {
        val wanted = template("wanted", 7)
        val rival = template("rival", 11)
        val rect = EntryPixelRect(40, 40, 155, 155)
        val ranked = AlignedRgbRelicMatcher().rank(translated(wanted.image, rect, -5, -1), rect, listOf(wanted, rival))
        assertEquals("wanted", ranked.first().first.relicId)
        assertEquals("Exact sampled pixels must be found without changing the metric", 1.0, ranked.first().second, 0.0)
        assertTrue(ranked.first().second - ranked[1].second >= 0.05)
    }

    @Test fun `every shortlisted rival receives the same fine search`() {
        val original = template("original", 7)
        val duplicate = original.copy(relicId = "duplicate")
        val rect = EntryPixelRect(40, 40, 155, 155)
        val ranked = AlignedRgbRelicMatcher().rank(translated(original.image, rect, -5, -1), rect, listOf(original, duplicate))
        assertEquals(1.0, ranked[0].second, 0.0)
        assertEquals(1.0, ranked[1].second, 0.0)
        assertEquals("An ambiguous duplicate must not gain a false margin", 0.0, ranked[0].second - ranked[1].second, 0.0)
    }

    @Test fun `fine alignment does not extend the allowed motion radius`() {
        val base = template("source", 19)
        val source = base.copy(image = PixelImage(128, 128, IntArray(128 * 128) { index ->
            var value = index * 73856093 xor 19349663
            value = value xor (value ushr 13)
            0xff000000.toInt() or (value and 0x00ffffff)
        }))
        val rect = EntryPixelRect(40, 40, 155, 155)
        val ranked = AlignedRgbRelicMatcher().rank(translated(source.image, rect, -7, -1), rect, listOf(source))
        assertTrue("The exact noisy image one pixel outside the six-pixel radius is not recovered: ${ranked.single().second}",
            ranked.single().second < 0.9)
    }

    @Test fun `single-pixel coarse grid and opposite odd offsets keep exact alignment`() {
        val matcher = AlignedRgbRelicMatcher()
        // Use same-size small templates so the exact mapping has no downscale collisions.
        // 100 px uses step 1; 155 px uses the live-shop step 2 and opposite odd displacement.
        for ((size, dx, dy) in listOf(Triple(100, 3, -2), Triple(155, 5, 1))) {
            val templateSize = minOf(size, 128)
            val wanted = template("wanted", 7, templateSize)
            val rival = template("rival", 11, templateSize)
            val rect = EntryPixelRect(40, 40, size, size)
            val ranked = matcher.rank(translated(wanted.image, rect, dx, dy), rect, listOf(wanted, rival))
            assertEquals("size=$size offset=$dx,$dy", 1.0, ranked.first().second, 0.0)
            assertEquals("wanted", ranked.first().first.relicId)
        }
    }

    @Test fun `empty and too small rectangles have no candidate`() {
        val frame = PixelImage(240, 240, IntArray(240 * 240))
        val matcher = AlignedRgbRelicMatcher()
        assertTrue(matcher.rank(frame, EntryPixelRect(0, 0, 155, 155), emptyList()).isEmpty())
        assertTrue(matcher.rank(frame, EntryPixelRect(0, 0, 2, 155), listOf(template("source", 23))).isEmpty())
    }

    @Test fun `refined icon still needs checked title and original full stock evidence`() {
        // Only the icon pixels are synthetic; the category and scanner are the production gates.
        val wanted = template("11001", 7)
        val rival = template("11002", 11)
        val rect = EntryPixelRect(40, 40, 155, 155)
        val ranked = AlignedRgbRelicMatcher().rank(translated(wanted.image, rect, -5, -1), rect, listOf(wanted, rival))
        val best = ranked.first()
        val margin = best.second - ranked[1].second
        assertTrue(best.second >= 0.50 && margin >= 0.05)
        val match = LabyrinthRelicMatch("shop_item_2", best.first.relicId, "加速玉饰", "加速", "1", "",
            best.second, screenRect = rect, iconRect = rect, rivalMargin = margin)
        val raw = ShopScanFixtures.goods()[1].copy(kind = LabyrinthShopItemKind.UNKNOWN,
            titleText = null, roleImprintLabel = null, relicMatch = match)
        val categories = LabyrinthShopCategoryResolution()
        val withoutTitle = categories.resolve(raw, null, null, 0)
        assertEquals(LabyrinthShopCategoryState.READING, withoutTitle.categoryState)
        val unresolved = categories.resolve(raw.copy(relicMatch = match.copy(relicId = null,
            rivalMargin = 0.049)), "加速玉饰", 1, 3)
        assertEquals(LabyrinthShopCategoryState.EXHAUSTED, unresolved.categoryState)
        val rejected = ShopScanFixtures.goods().map { if (it.slotId == raw.slotId) unresolved else it }
        assertFalse(ShopScanFixtures.complete(LabyrinthShopStockScanner(), rejected).stockComplete)
        val identified = categories.resolve(raw, "加速玉饰", 2, 0)
        assertEquals(LabyrinthShopCategoryState.READY, identified.categoryState)
        val goods = ShopScanFixtures.goods().map { if (it.slotId == raw.slotId) identified else it }
        val scanner = LabyrinthShopStockScanner()
        assertFalse(scanner.observe(ShopScanFixtures.a(items = goods), 10).stockComplete)
        assertTrue(scanner.dispatched(scanner.epoch, LabyrinthShopScroll.TO_BOTTOM, 11))
        scanner.actionFinished(scanner.epoch, true)
        assertFalse(scanner.observe(ShopScanFixtures.b(items = goods), 12).stockComplete)
        assertFalse(scanner.observe(ShopScanFixtures.b(items = goods), 12).stockComplete)
        assertTrue(scanner.observe(ShopScanFixtures.b(items = goods), 13).stockComplete)
    }

    @Test fun `zero aligned image is unchanged and blank image stays ambiguous`() {
        val source = template("source", 23)
        val rect = EntryPixelRect(40, 40, 155, 155)
        val matcher = AlignedRgbRelicMatcher()
        assertEquals(1.0, matcher.rank(translated(source.image, rect, 0, 0), rect, listOf(source)).single().second, 0.0)
        val blank = PixelImage(240, 240, IntArray(240 * 240) { 0xff303030.toInt() })
        val duplicate = source.copy(relicId = "duplicate")
        val ranked = matcher.rank(blank, rect, listOf(source, duplicate))
        assertEquals(0.0, ranked[0].second - ranked[1].second, 0.0)
    }
}
