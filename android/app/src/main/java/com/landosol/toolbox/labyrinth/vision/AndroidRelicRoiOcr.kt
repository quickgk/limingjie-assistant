package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/** Owns only one crop; the OCR callback, not a timer, releases it. */
internal class AndroidRelicRoiOcr(private val submit: (Bitmap, (String?) -> Unit) -> Unit, maximumAttempts: Int = 2) {
    private val scheduler = RelicOcrScheduler({ android.os.SystemClock.elapsedRealtime() }, maximumAttempts = maximumAttempts)
    fun clear() = scheduler.clear()

    /** See [RelicOcrScheduler.lastCompletionAt]. */
    fun lastCompletionAt(): Long = scheduler.lastCompletionAt()

    fun read(bitmap: Bitmap, slot: String, rect: EntryPixelRect, scale: Int = 1,
        whiteText: Boolean = false, darkText: Boolean = false): RelicOcrScheduler.Read? {
        val fingerprint = relicRoiFingerprint(bitmap, rect)
        val cached = scheduler.cached(slot, fingerprint)
        val ticket = scheduler.request(slot, fingerprint) ?: return cached
        var input: Bitmap? = null
        try {
            val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
            input = crop
            if (scale > 1) {
                input = Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, false)
                if (input !== crop) crop.recycle()
            }
            if (whiteText || darkText) {
                check(!(whiteText && darkText)) { "一个裁图只使用一种文字颜色预处理" }
                val source = requireNotNull(input)
                input = if (whiteText) shoppingWhiteTextCrop(source) else shoppingDarkTextCrop(source)
                if (input !== source && !source.isRecycled) source.recycle()
            }
            val owned = requireNotNull(input)
            submit(owned) { text ->
                try {
                    scheduler.complete(ticket, text)
                } finally {
                    if (!owned.isRecycled) owned.recycle()
                }
            }
        } catch (failure: Exception) {
            scheduler.complete(ticket, null)
            input?.let { if (!it.isRecycled) it.recycle() }
        }
        return cached
    }
}

/** Exact pixels of the small ROI; sparse dHash can miss a changed digit. */
internal fun relicRoiFingerprint(bitmap: Bitmap, rect: EntryPixelRect): Long {
    val pixels = IntArray(rect.width * rect.height)
    bitmap.getPixels(pixels, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
    return exactRoiPixelFingerprint(pixels, rect.width, rect.height)
}

/** Frame and rectangle provenance cannot be replaced by an unbound precomputed Long. */
internal class AndroidFrameRoiFingerprints(
    val bitmap: Bitmap,
    val captureSequence: Long,
    val isCurrent: () -> Boolean = { true },
    private val performance: ShopPerformanceRecorder = ShopPerformanceRecorder.NONE,
) {
    private val hashes = mutableMapOf<EntryPixelRect, Long>()
    fun fingerprint(source: Bitmap, rect: EntryPixelRect): Long {
        require(source === bitmap && !bitmap.isRecycled)
        hashes[rect]?.let {
            performance.event("roi.reused", capture = captureSequence)
            return it
        }
        val started = System.nanoTime()
        return relicRoiFingerprint(bitmap, rect).also {
            hashes[rect] = it
            performance.event("roi.hashed", started, capture = captureSequence, count = rect.width.toLong() * rect.height)
        }
    }
}
