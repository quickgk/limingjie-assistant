package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import android.os.SystemClock
import java.util.concurrent.Executors

/** All shop readers share this worker and the same Paddle engine; no frame is retained. */
internal class AndroidShopOcrRuntime(
    val engine: PaddleReceiptOcr?,
    private val submitFallback: (Bitmap, (String?) -> Unit) -> Unit,
    val performance: ShopPerformanceRecorder = ShopPerformanceRecorder.NONE,
) {
    companion object {
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "shop-ocr-coordinator").apply { isDaemon = true }
        }
    }
    val coordinator = ShopOcrCoordinator<List<Bitmap?>>(executor::execute, { inputs ->
        inputs.forEach { bitmap -> bitmap?.let { if (!it.isRecycled) it.recycle() } }
    }, performance = performance)

    @Volatile var lastCompletionAt: Long = Long.MIN_VALUE
        private set

    fun recognizeReceipt(bitmap: Bitmap): String? =
        requireNotNull(engine).recognizeLineOrThrow(bitmap).text.also {
            lastCompletionAt = SystemClock.elapsedRealtime()
        }

    fun recognize(bitmap: Bitmap): String? = recognizeText(bitmap).also {
        lastCompletionAt = SystemClock.elapsedRealtime()
    }

    private fun recognizeText(bitmap: Bitmap): String? {
        val paddle = engine
        if (paddle != null) return paddle.recognizeLineOrThrow(bitmap)
            .takeIf { it.score >= PPOCR_MIN_LINE_SCORE && it.text.isNotBlank() }?.text
        val latch = java.util.concurrent.CountDownLatch(1)
        var text: String? = null
        submitFallback(bitmap) { value -> text = value; latch.countDown() }
        // The fallback callback owns completion; never recycle its input on a timer.
        latch.await()
        return text
    }
}

/** Copies only the requested ROI, before the capture owner recycles its frame. */
internal class AndroidShopRoiOcr(
    private val runtime: AndroidShopOcrRuntime,
    private val owner: String,
    maximumAttempts: Int = 2,
) {
    private val state = ShopOcrReadState(maximumAttempts, runtime.performance)
    fun clear() { state.clear(); runtime.coordinator.cancel(owner) }
    fun bind(scope: String, page: String) { if (state.bind(scope, page)) runtime.coordinator.cancel(owner) }
    fun lastCompletionAt(): Long = runtime.lastCompletionAt

    fun read(bitmap: Bitmap, frame: AndroidFrameRoiFingerprints, field: String, rect: EntryPixelRect,
        scale: Int = 1, whiteText: Boolean = false, darkText: Boolean = false,
        priority: Int = 0): RelicOcrScheduler.Read? {
        if (!frame.isCurrent()) return null
        val fingerprint = frame.fingerprint(bitmap, rect)
        val provenance = "${rect.left},${rect.top},${rect.width},${rect.height}|$scale|$whiteText|$darkText"
        val cached = state.cached(field, fingerprint, provenance, frame.captureSequence)
        val token = state.request(owner, field, fingerprint, frame.captureSequence, provenance) ?: return cached
        val sourceCurrent = frame.isCurrent
        var input: Bitmap? = null
        try {
            val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
            input = crop
            if (scale > 1) {
                input = Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, false)
                if (input !== crop) crop.recycle()
            }
            if (whiteText || darkText) {
                check(!(whiteText && darkText))
                val source = requireNotNull(input)
                input = if (whiteText) shoppingWhiteTextCrop(source) else shoppingDarkTextCrop(source)
                if (input !== source && !source.isRecycled) source.recycle()
            }
            runtime.coordinator.submit(token, listOf(requireNotNull(input)), priority,
                valid = { sourceCurrent() && state.valid(token) }, recognize = { crops, valid ->
                    if (valid()) runtime.recognize(requireNotNull(crops[0])) else null
                }) { outcome ->
                val acceptedOutcome = if (sourceCurrent()) outcome else ShopOcrOutcome.Cancelled
                if (!state.complete(token, acceptedOutcome)) runtime.performance.event("ocr.stale_or_cancelled",
                    field = field, generation = token.generation, capture = token.sourceCaptureSequence,
                    request = token.requestSequence)
            }
            input = null
        } catch (_: Exception) {
            state.complete(token, ShopOcrOutcome.SubmissionFailed)
            input?.let { if (!it.isRecycled) it.recycle() }
        }
        return cached
    }
}
