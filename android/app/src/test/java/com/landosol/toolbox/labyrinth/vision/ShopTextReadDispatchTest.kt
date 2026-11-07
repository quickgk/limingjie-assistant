package com.landosol.toolbox.labyrinth.vision

import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class ShopTextReadDispatchTest {
    @Test fun `each request performs independent inference and completes once`() {
        val queue = mutableListOf<Runnable>()
        var calls = 0
        val dispatcher = ShopTextReadDispatch<String>({ queue.add(it) }) { calls++; it }
        val reads = mutableListOf<String?>()
        repeat(2) { dispatcher.submit("加速玉饰", reads::add) }
        assertTrue(reads.isEmpty())
        assertEquals(2, queue.size)
        queue.forEach(Runnable::run)
        assertEquals(2, calls)
        assertEquals(listOf("加速玉饰", "加速玉饰"), reads)
    }

    @Test fun `failed inference returns missing read and next request still runs`() {
        val dispatcher = ShopTextReadDispatch<String>(Runnable::run) { value ->
            if (value == "fail") error("inference failed") else value
        }
        val reads = mutableListOf<String?>()
        dispatcher.submit("fail", reads::add)
        dispatcher.submit("8280", reads::add)
        assertEquals(listOf(null, "8280"), reads)
    }

    @Test fun `rejected submission returns one missing read`() {
        var inference = 0
        val dispatcher = ShopTextReadDispatch<String>({ throw RejectedExecutionException() }) { inference++; it }
        val reads = mutableListOf<String?>()
        dispatcher.submit("8280", reads::add)
        assertEquals(listOf<String?>(null), reads)
        assertEquals(0, inference)
    }

    @Test fun `callback failure cannot create second read or stop later callbacks`() {
        val dispatcher = ShopTextReadDispatch<String>(Runnable::run) { it }
        var completed = 0
        dispatcher.submit("8280") { completed++; error("consumer failed") }
        dispatcher.submit("8280") { completed++ }
        assertEquals(2, completed)
    }
}
