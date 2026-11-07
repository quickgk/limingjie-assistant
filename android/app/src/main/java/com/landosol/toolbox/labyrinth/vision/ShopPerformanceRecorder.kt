package com.landosol.toolbox.labyrinth.vision

/** Bounded, monotonic diagnostics; recording failures never affect recognition or transactions. */
internal fun interface ShopPerformanceRecorder {
    fun record(event: ShopPerformanceEvent)

    companion object {
        val NONE = ShopPerformanceRecorder { }
    }
}

internal data class ShopPerformanceEvent(
    val name: String,
    val atNanos: Long,
    val durationNanos: Long = 0,
    val field: String? = null,
    val generation: Long = 0,
    val captureSequence: Long = 0,
    val requestSequence: Long = 0,
    val count: Long = 0,
    val scope: String? = null,
    val visit: Long = 0,
    val consumingCaptureSequence: Long = 0,
    val stage: String? = null,
    val action: String? = null,
    val transactionId: String? = null,
)

internal class BoundedShopPerformanceRecorder(private val capacity: Int = 256) : ShopPerformanceRecorder {
    private val events = ArrayDeque<ShopPerformanceEvent>()
    private val totals = linkedMapOf<String, Long>()
    init { require(capacity > 0) }

    @Synchronized override fun record(event: ShopPerformanceEvent) {
        if (events.size == capacity) {
            events.removeFirst()
            totals["diagnostic.dropped"] = (totals["diagnostic.dropped"] ?: 0) + 1
        }
        events.addLast(event)
        totals[event.name] = (totals[event.name] ?: 0) + 1
    }

    @Synchronized fun snapshot(): List<ShopPerformanceEvent> = events.toList()
    @Synchronized fun drain(): List<ShopPerformanceEvent> = events.toList().also { events.clear() }
    @Synchronized fun counts(): Map<String, Long> = totals.toMap()
    @Synchronized fun clear() { events.clear(); totals.clear() }
}

internal data class ShopPerformanceContext(
    val scope: String?, val visit: Long, val generation: Long,
    val field: String? = null, val capture: Long = 0, val request: Long = 0,
)

/** Diagnostic-only routing; disabled unless the debug host attaches a bounded recorder. */
internal object ShopPerformance {
    @Volatile var recorder: ShopPerformanceRecorder = ShopPerformanceRecorder.NONE
    @Volatile var scope: String? = null
    @Volatile var visit: Long = 0
    @Volatile var generation: Long = 0
    private val contextLock = Any()
    private val requestContext = ThreadLocal<ShopPerformanceContext>()

    fun context(): ShopPerformanceContext = requestContext.get() ?: synchronized(contextLock) {
        ShopPerformanceContext(scope, visit, generation)
    }

    fun <T> withContext(context: ShopPerformanceContext, action: () -> T): T {
        val previous = requestContext.get()
        requestContext.set(context)
        try { return action() }
        finally { if (previous == null) requestContext.remove() else requestContext.set(previous) }
    }

    fun bind(value: String, epoch: Long) = synchronized(contextLock) {
        if (value != scope) { scope = value; visit++ }
        generation = epoch
    }

    fun event(name: String, started: Long = 0, field: String? = null, capture: Long = 0, count: Long = 0,
        stage: String? = null, action: String? = null, transactionId: String? = null) {
        val context = context()
        recorder.event(name, started, field ?: context.field, context.generation,
            if (capture != 0L) capture else context.capture, request = context.request, count = count, context = context,
            stage = stage, action = action, transactionId = transactionId)
    }

    fun snapshot(): List<ShopPerformanceEvent> = (recorder as? BoundedShopPerformanceRecorder)?.snapshot().orEmpty()
    fun drain(): List<ShopPerformanceEvent> = (recorder as? BoundedShopPerformanceRecorder)?.drain().orEmpty()
    fun counts(): Map<String, Long> = (recorder as? BoundedShopPerformanceRecorder)?.counts().orEmpty()
}

internal enum class ShopWaitReason(val eventName: String) {
    OCR("wait.ocr"), NEW_FRAME("wait.new_frame"), PAGE_STABILITY("wait.page_stability"),
    GESTURE("wait.gesture"), FINANCIAL("wait.financial"), COOLDOWN("wait.cooldown"),
    OWNERSHIP("wait.ownership"), SAFETY("wait.safety"),
}

/** Visit milestones are bounded diagnostic state, never a source of action permission. */
internal class ShopVisitPerformance {
    private var scope: String? = null
    private val milestones = mutableSetOf<String>()
    private val transactions = linkedSetOf<String>()

    @Synchronized fun bind(value: String) {
        if (scope == value) return
        scope = value
        milestones.clear()
        transactions.clear()
    }

    @Synchronized fun once(name: String, stage: String? = null, transactionId: String? = null) {
        val key = "$name:$transactionId"
        val admitted = if (transactionId == null) milestones.add(key) else transactions.add(key)
        if (!admitted) return
        if (transactions.size > 64) transactions.remove(transactions.first())
        ShopPerformance.event(name, stage = stage, transactionId = transactionId)
    }

    @Synchronized fun clear() { scope = null; milestones.clear(); transactions.clear() }
}

/** A bounded result owner records first consumption once, without treating cache hits as new evidence. */
internal class ShopOcrConsumption(
    private val performance: ShopPerformanceRecorder,
    private val clock: () -> Long = System::nanoTime,
) {
    private data class Result(val context: ShopPerformanceContext, val completedAt: Long, var consumed: Boolean = false)
    private var result: Result? = null

    @Synchronized fun completed(token: ShopOcrToken) {
        result = Result(ShopPerformance.context().copy(generation = token.generation, field = token.field,
            capture = token.sourceCaptureSequence, request = token.requestSequence), clock())
    }

    @Synchronized fun consume(request: Long, capture: Long) {
        val current = result?.takeIf { it.context.request == request } ?: return
        val context = current.context
        performance.event(if (current.consumed) "ocr.cache_hit" else "ocr.consumed",
            started = if (current.consumed) 0 else current.completedAt,
            field = context.field, generation = context.generation, capture = context.capture,
            request = request, clock = clock, context = context, consumingCapture = capture)
        current.consumed = true
    }

    @Synchronized fun clear() { result = null }
}

internal fun ShopPerformanceRecorder.event(
    name: String,
    started: Long = 0,
    field: String? = null,
    generation: Long = 0,
    capture: Long = 0,
    request: Long = 0,
    count: Long = 0,
    clock: () -> Long = System::nanoTime,
    context: ShopPerformanceContext = ShopPerformance.context(),
    consumingCapture: Long = 0,
    stage: String? = null,
    action: String? = null,
    transactionId: String? = null,
) {
    if (this === ShopPerformanceRecorder.NONE) return
    runCatching {
        val now = clock()
        record(ShopPerformanceEvent(name, now, if (started == 0L) 0 else (now - started).coerceAtLeast(0),
            field, generation, capture, request, count, context.scope, context.visit, consumingCapture,
            stage, action, transactionId))
    }
}
