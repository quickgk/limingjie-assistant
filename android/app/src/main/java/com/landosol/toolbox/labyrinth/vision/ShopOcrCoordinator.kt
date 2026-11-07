package com.landosol.toolbox.labyrinth.vision

import java.util.concurrent.atomic.AtomicBoolean

/** Each token describes copied pixels, not permission to act on the source frame. */
internal data class ShopOcrToken(
    val owner: String,
    val generation: Long,
    val scope: String,
    val pageStage: String,
    val sourceCaptureSequence: Long,
    val roiFingerprint: Long,
    val field: String,
    val attempt: Int,
    val requestSequence: Long,
    val provenance: String = "",
)

internal sealed interface ShopOcrOutcome<out R> {
    data class Completed<R>(val value: R?) : ShopOcrOutcome<R>
    data object Cancelled : ShopOcrOutcome<Nothing>
    data object SubmissionFailed : ShopOcrOutcome<Nothing>
    data object ExecutionFailed : ShopOcrOutcome<Nothing>
}

/** One physical worker, bounded copied ROIs, and fair priority for current modal fields. */
internal class ShopOcrCoordinator<T>(
    private val execute: (Runnable) -> Unit,
    private val release: (T) -> Unit,
    private val capacity: Int = 16,
    private val performance: ShopPerformanceRecorder = ShopPerformanceRecorder.NONE,
    private val clock: () -> Long = System::nanoTime,
) {
    private class Job<T>(
        val token: ShopOcrToken,
        val input: T,
        val priority: Int,
        val valid: () -> Boolean,
        val run: (T, () -> Boolean) -> Any?,
        val callback: (ShopOcrOutcome<Any?>) -> Unit,
        val enqueued: Long,
        val context: ShopPerformanceContext,
    ) {
        val cancelled = AtomicBoolean(false)
        val terminal = AtomicBoolean(false)
    }

    private val lock = Any()
    private val queue = mutableListOf<Job<T>>()
    private var running: Job<T>? = null
    private var workerScheduled = false
    private var priorityStreak = 0

    fun <R> submit(
        token: ShopOcrToken,
        input: T,
        priority: Int = 0,
        valid: () -> Boolean,
        recognize: (T, () -> Boolean) -> R?,
        callback: (ShopOcrOutcome<R>) -> Unit,
    ) {
        @Suppress("UNCHECKED_CAST")
        val job = Job(token, input, priority, valid, recognize, { callback(it as ShopOcrOutcome<R>) }, clock(),
            ShopPerformance.context().copy(field = token.field, capture = token.sourceCaptureSequence,
                request = token.requestSequence))
        var schedule = false
        val retired = mutableListOf<Job<T>>()
        val admitted = synchronized(lock) {
            val obsolete: (Job<T>) -> Boolean = { it.token.owner == token.owner &&
                it.token.field == token.field && it.token != token }
            queue.filter(obsolete).forEach { it.cancelled.set(true); retired.add(it) }
            queue.removeAll(retired.toSet())
            running?.takeIf(obsolete)?.cancelled?.set(true)
            if (queue.size + (if (running == null) 0 else 1) >= capacity ||
                queue.any { it.token == token } || running?.token == token) false
            else {
                queue.add(job)
                if (!workerScheduled) { workerScheduled = true; schedule = true }
                true
            }
        }
        retired.forEach { finish(it, ShopOcrOutcome.Cancelled) }
        if (!admitted) { finish(job, ShopOcrOutcome.SubmissionFailed); return }
        performance.event("ocr.enqueued", field = token.field, generation = token.generation,
            capture = token.sourceCaptureSequence, request = token.requestSequence, clock = clock, context = job.context)
        if (schedule) {
            try { execute(Runnable(::drain)) }
            catch (_: Exception) {
                val rejected = synchronized(lock) {
                    workerScheduled = false
                    queue.toList().also { queue.clear() }
                }
                rejected.forEach { finish(it, ShopOcrOutcome.SubmissionFailed) }
            }
        }
    }

    /** Running native inference retains its input until it returns naturally. */
    fun cancel(owner: String, generation: Long? = null) {
        val retired = synchronized(lock) {
            fun matches(job: Job<T>) = job.token.owner == owner &&
                (generation == null || job.token.generation == generation)
            running?.takeIf(::matches)?.cancelled?.set(true)
            queue.filter(::matches).also { jobs ->
                jobs.forEach { it.cancelled.set(true) }
                queue.removeAll(jobs.toSet())
            }
        }
        retired.forEach { finish(it, ShopOcrOutcome.Cancelled) }
    }

    fun cancelAll() {
        val retired = synchronized(lock) {
            running?.cancelled?.set(true)
            queue.toList().also { queue.clear(); it.forEach { job -> job.cancelled.set(true) } }
        }
        retired.forEach { finish(it, ShopOcrOutcome.Cancelled) }
    }

    internal fun retainedInputCount(): Int = synchronized(lock) { queue.size + if (running == null) 0 else 1 }

    private fun drain() {
        while (true) {
            val job = synchronized(lock) {
                if (queue.isEmpty()) { workerScheduled = false; return }
                // A continuously requested wallet cannot starve the last stock titles.
                val index = if (priorityStreak >= 2) 0 else queue.indices.maxByOrNull { queue[it].priority }!!
                queue.removeAt(index).also {
                    priorityStreak = if (it.priority > 0) priorityStreak + 1 else 0
                    running = it
                }
            }
            fun current(): Boolean = !job.cancelled.get() && runCatching(job.valid).getOrDefault(false)
            val outcome = if (!current()) ShopOcrOutcome.Cancelled else {
                performance.event("ocr.started", job.enqueued, job.token.field, job.token.generation,
                    job.token.sourceCaptureSequence, job.token.requestSequence, clock = clock, context = job.context)
                val result = runCatching { ShopPerformance.withContext(job.context) { job.run(job.input, ::current) } }
                when {
                    !current() -> ShopOcrOutcome.Cancelled
                    result.isFailure -> ShopOcrOutcome.ExecutionFailed
                    else -> ShopOcrOutcome.Completed(result.getOrNull())
                }
            }
            finish(job, outcome)
            synchronized(lock) { if (running === job) running = null }
        }
    }

    private fun finish(job: Job<T>, outcome: ShopOcrOutcome<Any?>) {
        if (!job.terminal.compareAndSet(false, true)) return
        val callbackStarted = clock()
        try {
            runCatching { ShopPerformance.withContext(job.context) { job.callback(outcome) } }
        } finally {
            performance.event("ocr.callback", callbackStarted, job.token.field, job.token.generation,
                job.token.sourceCaptureSequence, job.token.requestSequence, clock = clock, context = job.context)
            runCatching { release(job.input) }
            performance.event(when (outcome) {
                is ShopOcrOutcome.Completed -> "ocr.batch_completed"
                ShopOcrOutcome.Cancelled -> "ocr.cancelled"
                ShopOcrOutcome.SubmissionFailed -> "ocr.submission_failed"
                ShopOcrOutcome.ExecutionFailed -> "ocr.execution_failed"
            }, job.enqueued, job.token.field, job.token.generation, job.token.sourceCaptureSequence,
                job.token.requestSequence, clock = clock, context = job.context)
        }
    }
}

/** Only genuine completed requests count as reads; the second look needs a newer capture. */
internal class ShopOcrReadState(
    private val maximumAttempts: Int = 2,
    private val performance: ShopPerformanceRecorder = ShopPerformanceRecorder.NONE,
    private val clock: () -> Long = System::nanoTime,
) {
    private data class Slot(
        val fingerprint: Long,
        val provenance: String,
        val consumption: ShopOcrConsumption,
        var attempts: Int = 0,
        var capture: Long = Long.MIN_VALUE,
        var pending: ShopOcrToken? = null,
        var read: RelicOcrScheduler.Read? = null,
        var rearmed: Boolean = false,
    )
    private val slots = mutableMapOf<String, Slot>()
    private var sequence = 0L
    private var generation = 0L
    private var scope = ""
    private var page = ""

    @Synchronized fun bind(newScope: String, newPage: String): Boolean {
        if (scope == newScope && page == newPage) return false
        scope = newScope; page = newPage; clear(); return true
    }
    @Synchronized fun clear() { generation++; slots.clear() }
    @Synchronized fun cached(field: String, fingerprint: Long, provenance: String = "",
        consumingCapture: Long? = null): RelicOcrScheduler.Read? {
        val slot = slots[field]?.takeIf { it.fingerprint == fingerprint && it.provenance == provenance } ?: return null
        return slot.read?.also { read -> consumingCapture?.let { slot.consumption.consume(read.id, it) } }
    }

    @Synchronized fun request(owner: String, field: String, fingerprint: Long, capture: Long,
        provenance: String = ""): ShopOcrToken? {
        val slot = slots[field]?.takeIf { it.fingerprint == fingerprint && it.provenance == provenance }
            ?: Slot(fingerprint, provenance, ShopOcrConsumption(performance, clock)).also { slots[field] = it }
        if (slot.pending != null || slot.attempts >= maximumAttempts || capture <= slot.capture) return null
        slot.attempts++
        slot.capture = capture
        return ShopOcrToken(owner, generation, scope, page, capture, fingerprint, field,
            slot.attempts, ++sequence, provenance).also { slot.pending = it }
    }

    @Synchronized fun valid(token: ShopOcrToken): Boolean = token.generation == generation &&
        token.scope == scope && token.pageStage == page && slots[token.field]?.let {
            it.fingerprint == token.roiFingerprint && it.pending == token
        } == true

    @Synchronized fun complete(token: ShopOcrToken, outcome: ShopOcrOutcome<String>): Boolean {
        if (!valid(token)) return false
        val slot = slots.getValue(token.field)
        slot.pending = null
        if (outcome !is ShopOcrOutcome.Completed) { slot.attempts--; return false }
        slot.consumption.completed(token)
        val text = outcome.value?.trim()
        val previous = slot.read
        slot.read = RelicOcrScheduler.Read(text, token.requestSequence,
            if (previous != null && previous.text == text) previous.confirmations + 1 else 1)
        if (previous != null && previous.text != text && !slot.rearmed) {
            slot.rearmed = true; slot.attempts = maximumAttempts - 1
        }
        return true
    }
}
