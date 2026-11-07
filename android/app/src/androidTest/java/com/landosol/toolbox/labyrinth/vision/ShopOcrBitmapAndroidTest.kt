package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ShopOcrBitmapAndroidTest {
    @Test fun exactBatchHashAndFrameProvenanceMatchLegacyPixels() {
        val random = java.util.Random(73)
        val image = Bitmap.createBitmap(IntArray(80 * 40) { random.nextInt() or 0xff000000.toInt() }, 80, 40, Bitmap.Config.ARGB_8888)
        try {
            val recorder = BoundedShopPerformanceRecorder()
            val frame = AndroidFrameRoiFingerprints(image, 1, performance = recorder)
            for (rect in listOf(EntryPixelRect(0, 0, 80, 40), EntryPixelRect(3, 7, 19, 13), EntryPixelRect(79, 39, 1, 1))) {
                var expected = 1125899906842597L + rect.width * 31L + rect.height
                for (y in rect.top until rect.top + rect.height) for (x in rect.left until rect.left + rect.width)
                    expected = expected * 31 + image.getPixel(x, y)
                assertEquals(expected, frame.fingerprint(image, rect))
                assertEquals(expected, frame.fingerprint(image, rect))
            }
            assertEquals(3L, recorder.counts()["roi.hashed"])
            assertEquals(3L, recorder.counts()["roi.reused"])
            val changed = requireNotNull(image.copy(Bitmap.Config.ARGB_8888, true))
            try {
                try { frame.fingerprint(changed, EntryPixelRect(0, 0, 1, 1)); fail("wrong frame") }
                catch (_: IllegalArgumentException) { }
                val rect = EntryPixelRect(3, 7, 19, 13)
                changed.setPixel(3, 7, image.getPixel(3, 7) xor 1)
                assertNotEquals(image.getPixel(3, 7), changed.getPixel(3, 7))
                assertNotEquals(relicRoiFingerprint(image, rect), relicRoiFingerprint(changed, rect))
            } finally { changed.recycle() }
        } finally { image.recycle() }
    }

    @Test fun cancellingRealCropsDoesNotRecycleNativeInFlightInputOrCountARead() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val calls = AtomicInteger()
        var owned: Bitmap? = null
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val coordinator = ShopOcrCoordinator<Bitmap>(executor::execute, { it.recycle(); done.countDown() })
        val state = ShopOcrReadState()
        val token = requireNotNull(state.request("wallet", "balance", 1, 1))
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val crop = Bitmap.createBitmap(source, 10, 10, 40, 20)
        try {
            coordinator.submit(token, crop, valid = { state.valid(token) }, recognize = { bitmap, _ ->
                owned = bitmap; calls.incrementAndGet(); entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                assertFalse(bitmap.isRecycled)
                "100"
            }) { state.complete(token, it) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            state.clear()
            coordinator.cancelAll()
            source.recycle()
            assertFalse(requireNotNull(owned).isRecycled)
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(crop.isRecycled)
            assertEquals(1, calls.get())
            assertNull(state.cached("balance", 1))
        } finally {
            release.countDown()
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            if (!source.isRecycled) source.recycle()
        }
    }
}
