package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.*
import org.junit.Test

class ShopPerformanceWorkloadTest {
    private val rows = listOf("购买完成", "消耗阿尔法金币×100", "购买了苍珠手镯。", "1000", "900", "确认")
    private val rect = EntryPixelRect(780, 710, 360, 65)

    @Test fun `successful five field double read runs exactly ten inferences`() {
        var now = 0L
        var calls = 0
        val state = LabyrinthShopReceiptReadState { now }
        repeat(2) {
            val request = requireNotNull(state.requestContent(10, 20))
            val result = recognizeShopReceiptFields(rows, { true }) { calls++; it }
            assertEquals(6, result.size)
            assertNull(result[5])
            state.completeRows(request, result, rect)
            now += 200
        }
        assertEquals(10, calls)
        assertTrue(requireNotNull(state.receipt).verified)
        assertNull(state.receipt?.button)
        val withoutRect = shopReceiptFromCollageRows(rows.take(5), 3, 2, null)
        assertFalse(withoutRect.verified)
        assertFalse(shopReceiptFromCollageRows(rows.toMutableList().apply { set(4, "901") }, 3, 2, rect).verified)
    }

    @Test fun `nonreceipt unknown and missing headings never infer transaction fields`() {
        listOf<String?>("更新确认", "购买完成？", "购买", null).forEach { heading ->
            var calls = 0
            val result = recognizeShopReceiptFields(rows.toMutableList<String?>().apply { set(0, heading) }, { true }) {
                calls++; it
            }
            assertEquals(if (heading == null) 0 else 1, calls)
            assertTrue(result.drop(1).all { it == null })
        }
    }

    @Test fun `cancelling receipt between fields stops remaining native work`() {
        var current = true
        var calls = 0
        val result = recognizeShopReceiptFields(rows, { current }) {
            calls++; if (calls == 2) current = false; it
        }
        assertEquals(2, calls)
        assertNull(result[2])
    }

    @Test fun `confirmed other title survives irrelevant body changes but rechecks true receipt`() {
        var now = 0L
        val state = LabyrinthShopReceiptReadState { now }
        val other = listOf("更新确认", null, null, null, null, null)
        repeat(2) { state.completeRows(requireNotNull(state.requestContent(10, 20)), other, rect); now += 200 }
        now = 800
        assertNull(state.requestContent(10, 99))
        assertNull(state.frameResult(1920, 1080))
        now = 1700
        val probe = requireNotNull(state.requestContent(10, 100))
        assertNull(state.frameResult(1920, 1080))
        state.completeRows(probe, other, rect)
        val trueReceipt = requireNotNull(state.requestContent(11, 100))
        assertNull(state.receipt)
        state.completeRows(trueReceipt, rows.take(5), rect)
        now += 200
        state.completeRows(requireNotNull(state.requestContent(11, 100)), rows.take(5), rect)
        assertTrue(requireNotNull(state.receipt).verified)
        assertNotNull(state.requestContent(11, 101))
        assertNull(state.receipt)
    }

    @Test fun `missing and conflicting headings are retried and old diagnostic callbacks stay stale`() {
        var now = 0L
        val state = LabyrinthShopReceiptReadState { now }
        state.completeRows(requireNotNull(state.requestContent(1, 1)), emptyList(), rect)
        now = 200
        val retry = requireNotNull(state.requestContent(1, 1))
        state.clear()
        assertFalse(state.completeRows(retry, rows, rect))
        assertNull(state.diagnostics())
        val current = requireNotNull(state.requestContent(1, 1))
        state.cancel(retry)
        assertTrue(state.isCurrent(current))
    }

    @Test fun `batch pixel hash preserves legacy overflow row order and every pixel`() {
        val random = java.util.Random(42)
        for (width in listOf(1, 7, 390)) for (height in listOf(1, 6, 55)) {
            val pixels = IntArray(width * height) { random.nextInt() }
            var legacy = 1125899906842597L + width * 31L + height
            for (y in 0 until height) for (x in 0 until width) legacy = legacy * 31 + pixels[y * width + x]
            assertEquals(legacy, exactRoiPixelFingerprint(pixels, width, height))
            val changed = pixels.copyOf().apply { this[lastIndex] = this[lastIndex] xor 1 }
            assertNotEquals(legacy, exactRoiPixelFingerprint(changed, width, height))
        }
    }

    @Test fun `modal demand never samples hidden shelf or wallet pixels`() {
        val image = com.landosol.toolbox.clanbattle.recognition.PixelImage(1920, 1080, IntArray(1920 * 1080))
        val recognizer = LabyrinthShopRecognizer()
        for (dialog in LabyrinthShopDialogState.entries.filter { it != LabyrinthShopDialogState.NONE }) {
            val result = recognizer.recognize(image, dialog)
            assertTrue(result.items.isEmpty())
            assertNull(result.viewportPixels)
            assertNull(result.coverage)
            assertEquals(dialog, result.dialogState)
        }
        assertTrue(recognizer.recognize(image, modalVisible = true).items.isEmpty())
    }

    @Test fun `diagnostics are bounded monotonic and cannot propagate sink failures`() {
        val recorder = BoundedShopPerformanceRecorder(2)
        repeat(5) { recorder.event("read", started = 1, clock = { it + 2L }) }
        assertEquals(2, recorder.snapshot().size)
        assertEquals(5L, recorder.counts()["read"])
        assertEquals(5L, recorder.snapshot().last().durationNanos)
        assertEquals(3L, recorder.counts()["diagnostic.dropped"])
        assertEquals(2, recorder.drain().size)
        assertTrue(recorder.drain().isEmpty())
        assertEquals(5L, recorder.counts()["read"])
        ShopPerformanceRecorder { error("diagnostic failure") }.event("read")
    }

    @Test fun `first consumption preserves source and measures only first waiting interval`() {
        var now = 100L
        val recorder = BoundedShopPerformanceRecorder()
        val state = ShopOcrReadState(2, recorder) { now }
        val first = requireNotNull(state.request("wallet", "balance", 7, 10))
        state.complete(first, ShopOcrOutcome.Completed("100"))
        now = 150L
        state.cached("balance", 7, consumingCapture = 11)
        now = 180L
        state.cached("balance", 7, consumingCapture = 12)
        val consumed = recorder.snapshot().single { it.name == "ocr.consumed" }
        assertEquals(50L, consumed.durationNanos)
        assertEquals(10L, consumed.captureSequence)
        assertEquals(11L, consumed.consumingCaptureSequence)
        assertEquals(first.requestSequence, consumed.requestSequence)
        assertEquals(1L, recorder.counts()["ocr.cache_hit"])
        val second = requireNotNull(state.request("wallet", "balance", 7, 12))
        state.complete(second, ShopOcrOutcome.Completed("100"))
        now = 210L
        state.cached("balance", 7, consumingCapture = 13)
        assertEquals(2L, recorder.counts()["ocr.consumed"])
        state.clear()
        state.cached("balance", 7, consumingCapture = 14)
        assertEquals(3, recorder.snapshot().size)
        val tracking = ShopOcrConsumption(recorder) { now }
        tracking.completed(first)
        tracking.consume(second.requestSequence, 20)
        tracking.clear()
        tracking.consume(first.requestSequence, 21)
        assertEquals(3, recorder.snapshot().size)
    }

    @Test fun `receipt inference failure cannot be returned as completed missing fields`() {
        var calls = 0
        try {
            recognizeShopReceiptFields(rows, { true }) {
                if (++calls == 2) error("preprocessing failed")
                it
            }
            fail("execution failure must reach the coordinator")
        } catch (_: IllegalStateException) {
            assertEquals(2, calls)
        }
    }

    @Test fun `visit endpoints are once per scope and retain transaction association`() {
        val recorder = BoundedShopPerformanceRecorder()
        val previous = ShopPerformance.recorder
        try {
            ShopPerformance.recorder = recorder
            val visit = ShopVisitPerformance()
            visit.bind("first")
            repeat(3) { visit.once("visit.entered", "SHOP") }
            visit.once("receipt.saved", "SHOP_PURCHASE_COMPLETE", "receipt-1")
            visit.once("receipt.saved", "SHOP_PURCHASE_COMPLETE", "receipt-1")
            visit.once("receipt.saved", "SHOP_PURCHASE_COMPLETE", "receipt-2")
            visit.bind("second")
            visit.once("visit.entered", "SHOP")
            assertEquals(2L, recorder.counts()["visit.entered"])
            assertEquals(listOf("receipt-1", "receipt-2"), recorder.snapshot()
                .filter { it.name == "receipt.saved" }.map { it.transactionId })
        } finally { ShopPerformance.recorder = previous }
    }

    @Test fun `delayed OCR diagnostics retain submission scope and source capture`() {
        val recorder = BoundedShopPerformanceRecorder()
        val previous = ShopPerformance.recorder
        val original = ShopPerformance.context()
        var worker: Runnable? = null
        try {
            ShopPerformance.recorder = recorder
            ShopPerformance.bind("first-visit", 1)
            val coordinator = ShopOcrCoordinator<Int>({ worker = it }, {}, performance = recorder)
            coordinator.submit(ShopOcrToken("wallet", 1, "first", "shop", 12, 3, "balance", 1, 4),
                0, valid = { true }, recognize = { _, _ -> ShopPerformance.event("ocr.inference"); "100" }) { }
            ShopPerformance.bind("second-visit", 2)
            requireNotNull(worker).run()
            val events = recorder.snapshot()
            assertTrue(events.all { it.scope == "first-visit" })
            assertTrue(events.all { it.captureSequence == 12L && it.requestSequence == 4L })
            assertEquals("second-visit", ShopPerformance.context().scope)
        } finally {
            ShopPerformance.recorder = previous
            ShopPerformance.scope = original.scope
            ShopPerformance.visit = original.visit
            ShopPerformance.generation = original.generation
        }
    }
}
