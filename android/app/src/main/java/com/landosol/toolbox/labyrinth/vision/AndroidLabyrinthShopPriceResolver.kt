package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock

/** 五个小数字ROI共享OCR作业；裁图与位图生命周期留在Android适配器。 */
internal class AndroidLabyrinthShopPriceResolver(
    private val submit: (Bitmap, (List<LabyrinthShopOcrLine>?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readElementsAsync,
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lock = Any()
    private val state = LabyrinthShopPriceReadState(clock)

    fun clear() = synchronized(lock) { state.clear() }

    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
        val shop = result.shopObservation
        if (result.observation.state != LabyrinthEntryPageState.SHOP || shop == null ||
            shop.dialogState != LabyrinthShopDialogState.NONE || shop.purchaseReceipt != null) {
            clear()
            return result
        }
        val fields = RECTS.mapValues { (_, rect) ->
            ReferenceFitMapper.map(bitmap.width, bitmap.height, REFERENCE, rect) ?: return result
        }
        var fingerprint = 17L
        fields.values.forEach { fingerprint = fingerprint * 31 + relicRoiFingerprint(bitmap, it) }
        shop.items.forEach { fingerprint = fingerprint * 31 + (it.titleText ?: it.relicMatch?.relicId).hashCode() }
        synchronized(lock) {
            state.request(fingerprint)?.let { ticket ->
                var owned: Bitmap? = null
                try {
                    val collage = Bitmap.createBitmap(1500, 360, Bitmap.Config.ARGB_8888)
                    owned = collage
                    val canvas = Canvas(collage)
                    canvas.drawColor(Color.WHITE)
                    fields.entries.forEachIndexed { index, (key, rect) ->
                        val scale = minOf(460f / rect.width, 140f / rect.height)
                        val left = index % 3 * 500 + 20
                        val top = index / 3 * 180 + 20
                        val target = Rect(left, top, left + (rect.width * scale).toInt(), top + (rect.height * scale).toInt())
                        if (key == "refresh") {
                            // 保留蓝色按钮上的真实白色字形，不合成或推测任何数字。
                            val pixels = IntArray(rect.width * rect.height)
                            bitmap.getPixels(pixels, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
                            for (i in pixels.indices) {
                                val pixel = pixels[i]
                                pixels[i] = if ((pixel ushr 16 and 255) > 210 &&
                                    (pixel ushr 8 and 255) > 210 && (pixel and 255) > 210) Color.BLACK else Color.WHITE
                            }
                            val binary = Bitmap.createBitmap(pixels, rect.width, rect.height, Bitmap.Config.ARGB_8888)
                            try { canvas.drawBitmap(binary, null, target, null) } finally { binary.recycle() }
                        } else {
                            canvas.drawBitmap(bitmap, Rect(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height), target, null)
                        }
                    }
                    submit(collage) { lines ->
                        try { synchronized(lock) { state.complete(ticket, lines) } }
                        finally { if (!collage.isRecycled) collage.recycle() }
                    }
                } catch (_: Exception) {
                    state.complete(ticket, null)
                    owned?.let { if (!it.isRecycled) it.recycle() }
                }
            }
            val reads = state.reads
            return result.copy(
                shopObservation = shop.copy(
                    items = shop.items.map { it.copy(priceRead = reads[it.slotId]) },
                    refreshPriceRead = reads["refresh"],
                    priceReviewReason = state.reviewReason,
                ),
                currencyObservation = LabyrinthCurrencyObservation(reads["wallet"]),
            )
        }
    }

    private companion object {
        val REFERENCE = EntryReferenceSize(1920, 1080)
        val RECTS = linkedMapOf(
            "shop_item_1" to EntryReferenceRect(175, 490, 170, 65),
            "shop_item_2" to EntryReferenceRect(753, 490, 170, 65),
            "shop_item_3" to EntryReferenceRect(1328, 490, 170, 65),
            "refresh" to EntryReferenceRect(648, 945, 58, 55),
            "wallet" to EntryReferenceRect(1260, 940, 150, 64),
        )
    }
}
