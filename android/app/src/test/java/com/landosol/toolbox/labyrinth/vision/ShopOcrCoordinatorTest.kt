package com.landosol.toolbox.labyrinth.vision

import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class ShopOcrCoordinatorTest {
    private fun token(field: String, request: Long = 1) = ShopOcrToken("stock", 1, "run:node:1920x1080",
        "stock-A", request, request, field, 1, request)

    @Test fun `six ready titles drain without another frame or spacing timer`() {
        val workers = mutableListOf<Runnable>()
        val released = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val coordinator = ShopOcrCoordinator<String>({ workers.add(it) }, released::add)
        repeat(6) { index ->
            coordinator.submit(token("title-$index"), "$index", valid = { true },
                recognize = { input, _ -> reads.add(input); input }) { assertTrue(it is ShopOcrOutcome.Completed) }
        }
        assertEquals(1, workers.size)
        assertEquals(6, coordinator.retainedInputCount())
        workers.single().run()
        assertEquals((0..5).map(Int::toString), reads)
        assertEquals(reads, released)
        assertEquals(0, coordinator.retainedInputCount())
    }

    @Test fun `cancellation keeps running input alive and releases queued crops exactly once`() {
        val workers = mutableListOf<Runnable>()
        val released = mutableListOf<String>()
        val outcomes = mutableListOf<ShopOcrOutcome<String>>()
        lateinit var coordinator: ShopOcrCoordinator<String>
        coordinator = ShopOcrCoordinator({ workers.add(it) }, released::add)
        coordinator.submit(token("first"), "first", valid = { true }, recognize = { input, current ->
            coordinator.cancelAll()
            assertFalse(current())
            assertFalse(released.contains(input))
            input
        }, callback = outcomes::add)
        coordinator.submit(token("second"), "second", valid = { true },
            recognize = { _, _ -> error("cancelled input must not enter inference") }, callback = outcomes::add)
        workers.single().run()
        assertEquals(listOf("second", "first"), released)
        assertEquals(listOf(ShopOcrOutcome.Cancelled, ShopOcrOutcome.Cancelled), outcomes)
        coordinator.cancelAll()
        assertEquals(2, released.size)
    }

    @Test fun `changing pixels replaces only that queued field and retains other titles`() {
        val workers = mutableListOf<Runnable>()
        val released = mutableListOf<Int>()
        val inferred = mutableListOf<Int>()
        val coordinator = ShopOcrCoordinator<Int>({ workers.add(it) }, released::add)
        repeat(10) { value -> coordinator.submit(token("changing", value.toLong()), value, valid = { true },
            recognize = { input, _ -> inferred.add(input); input }) { } }
        coordinator.submit(token("other"), 100, valid = { true }, recognize = { input, _ -> inferred.add(input); input }) { }
        assertEquals(2, coordinator.retainedInputCount())
        assertEquals((0..8).toList(), released)
        workers.single().run()
        assertEquals(listOf(9, 100), inferred)
        assertEquals(11, released.distinct().size)
    }

    @Test fun `execution failure releases once and leaves budget for a genuine blank read`() {
        val workers = mutableListOf<Runnable>()
        val released = mutableListOf<Int>()
        val outcomes = mutableListOf<ShopOcrOutcome<String>>()
        val recorder = BoundedShopPerformanceRecorder()
        val coordinator = ShopOcrCoordinator<Int>({ workers.add(it) }, released::add, performance = recorder)
        val state = ShopOcrReadState(1)
        val failed = requireNotNull(state.request("wallet", "balance", 1, 1))
        coordinator.submit<String>(failed, 1, valid = { state.valid(failed) },
            recognize = { _, _ -> error("model or preprocessing failed") }) {
            outcomes.add(it); state.complete(failed, it)
        }
        workers.removeAt(0).run()
        assertEquals(listOf(ShopOcrOutcome.ExecutionFailed), outcomes)
        assertEquals(listOf(1), released)
        assertNull(state.cached("balance", 1))
        val blank = requireNotNull(state.request("wallet", "balance", 1, 2))
        coordinator.submit<String>(blank, 2, valid = { state.valid(blank) }, recognize = { _, _ -> null }) {
            outcomes.add(it); state.complete(blank, it)
        }
        workers.removeAt(0).run()
        assertEquals(ShopOcrOutcome.Completed<String>(null), outcomes.last())
        assertEquals(1, state.cached("balance", 1)?.confirmations)
        assertNull(state.cached("balance", 1)?.text)
        assertEquals(listOf(1, 2), released)
        assertEquals(1L, recorder.counts()["ocr.execution_failed"])
        assertEquals(1L, recorder.counts()["ocr.batch_completed"])
    }

    @Test fun `unchanged frames never replace a pending slow title`() {
        val state = ShopOcrReadState(1)
        val first = requireNotNull(state.request("stock", "last-title", 41, 1))
        repeat(100) { assertNull(state.request("stock", "last-title", 41, it + 2L)) }
        assertTrue(state.valid(first))
        assertTrue(state.complete(first, ShopOcrOutcome.Completed("title")))
        assertEquals(1, state.cached("last-title", 41)?.confirmations)
    }

    @Test fun `priority cannot starve older stock jobs`() {
        val workers = mutableListOf<Runnable>()
        val order = mutableListOf<String>()
        lateinit var coordinator: ShopOcrCoordinator<String>
        coordinator = ShopOcrCoordinator({ workers.add(it) }, {})
        fun submit(name: String, priority: Int) = coordinator.submit(token(name), name, priority,
            valid = { true }, recognize = { value, _ -> order.add(value); value }) { }
        submit("stock", 0)
        repeat(6) { submit("wallet-$it", 1) }
        workers.single().run()
        assertEquals("stock", order[2])
    }

    @Test fun `submission rejection capacity and callback failure still release once`() {
        var release = 0
        var infer = 0
        var result: ShopOcrOutcome<String>? = null
        val rejected = ShopOcrCoordinator<String>({ throw RejectedExecutionException() }, { release++ })
        rejected.submit(token("title"), "title", valid = { true }, recognize = { value, _ -> infer++; value }) {
            result = it; error("consumer failure")
        }
        assertEquals(ShopOcrOutcome.SubmissionFailed, result)
        assertEquals(0, infer)
        assertEquals(1, release)
        val workers = mutableListOf<Runnable>()
        val bounded = ShopOcrCoordinator<String>({ workers.add(it) }, { release++ }, capacity = 1)
        bounded.submit(token("one"), "one", valid = { true }, recognize = { value, _ -> value }) { }
        bounded.submit(token("two"), "two", valid = { true }, recognize = { _, _ -> error("overflow") }) { result = it }
        assertEquals(ShopOcrOutcome.SubmissionFailed, result)
        workers.single().run()
        assertEquals(3, release)
    }

    @Test fun `dual reads require new capture real completion and current provenance`() {
        val state = ShopOcrReadState()
        state.bind("session1-account1-enter1-node1-1920x1080", "stock")
        val first = requireNotNull(state.request("wallet", "balance", 31, 1, "rect-A"))
        state.complete(first, ShopOcrOutcome.Completed("100"))
        repeat(20) { assertNull(state.request("wallet", "balance", 31, 1, "rect-A")) }
        assertEquals(1, state.cached("balance", 31, "rect-A")?.confirmations)
        val second = requireNotNull(state.request("wallet", "balance", 31, 2, "rect-A"))
        assertFalse(state.complete(first, ShopOcrOutcome.Completed("100")))
        assertTrue(state.valid(second))
        state.complete(second, ShopOcrOutcome.Completed("100"))
        assertEquals(2, state.cached("balance", 31, "rect-A")?.confirmations)
        assertNull(state.cached("balance", 31, "rect-B"))
        val moved = requireNotNull(state.request("wallet", "balance", 31, 3, "rect-B"))
        state.complete(moved, ShopOcrOutcome.Completed("100"))
        assertEquals(1, state.cached("balance", 31, "rect-B")?.confirmations)
        state.bind("session1-account1-enter1-node1-1920x1080", "purchase-confirmation")
        assertNull(state.cached("balance", 31, "rect-B"))
    }

    @Test fun `cancelled and late requests never consume new generation budget`() {
        val state = ShopOcrReadState(1)
        val old = requireNotNull(state.request("stock", "title", 1, 1))
        state.clear()
        val current = requireNotNull(state.request("stock", "title", 1, 2))
        assertFalse(state.complete(old, ShopOcrOutcome.Completed("old")))
        assertTrue(state.valid(current))
        state.complete(current, ShopOcrOutcome.Cancelled)
        assertNull(state.cached("title", 1))
        val retry = requireNotNull(state.request("stock", "title", 1, 3))
        assertFalse(state.complete(current, ShopOcrOutcome.Completed("late")))
        state.complete(retry, ShopOcrOutcome.Completed("new"))
        assertEquals("new", state.cached("title", 1)?.text)
        assertEquals(1, state.cached("title", 1)?.confirmations)
    }
}
