package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock

internal enum class LabyrinthShopReceiptReadStatus { READING, NOT_RECEIPT, PURCHASE_COMPLETE }

internal val LabyrinthShopReceiptReadState.readStatus: LabyrinthShopReceiptReadStatus
    get() = when {
        receipt == null -> LabyrinthShopReceiptReadStatus.READING
        receipt?.titleConfirmed == true -> LabyrinthShopReceiptReadStatus.PURCHASE_COMPLETE
        else -> LabyrinthShopReceiptReadStatus.NOT_RECEIPT
    }

/** 读取中只阻止操作；已完成的否定标题让出页面分类，只有确认标题才携带回执证据。 */
internal fun LabyrinthShopReceiptReadState.frameResult(frameWidth: Int, frameHeight: Int): LabyrinthEntryFrameResult? {
    val status = readStatus
    if (status == LabyrinthShopReceiptReadStatus.NOT_RECEIPT) return null
    val observed = receipt?.takeIf { status == LabyrinthShopReceiptReadStatus.PURCHASE_COMPLETE }
    return LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(
            if (observed != null) LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE else LabyrinthEntryPageState.UNKNOWN,
            if (observed?.verified == true) 0.98 else 0.0,
            emptyMap(),
            LabyrinthAnchorScores(emptyMap()),
            reason = if (observed == null) "shop-receipt-ocr-reading"
                else observed.reviewReason ?: "shop-receipt-collage-verified",
        ),
        matchedFeatures = listOf(if (observed == null) "shop.receipt.reading" else "shop.receipt.fast_path"),
        elapsedMillis = 0,
        shopObservation = observed?.let {
            LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_COMPLETE, purchaseReceipt = it)
        },
        frameWidth = frameWidth,
        frameHeight = frameHeight,
    )
}

/**
 * 回执识别器。
 *
 * 六块字段各自裁出、放大后排进一张白底拼图，整图只 OCR 一次；白字蓝底的标题与确认按钮先做
 * 白字黑底二值化。两次读数的复核交给 [LabyrinthShopReceiptReadState] 按解析事实比较。
 */
internal class AndroidLabyrinthShopReceiptResolver(
    clock: () -> Long = SystemClock::elapsedRealtime,
    private val submit: (Bitmap, (List<LabyrinthShopOcrLine>?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readElementsAsync,
) {
    private val lock = Any()
    private val state = LabyrinthShopReceiptReadState(clock)

    fun clear() = synchronized(lock) { state.clear() }

    fun resolve(bitmap: Bitmap): LabyrinthEntryFrameResult? {
        val confirmRect = ReferenceFitMapper.map(bitmap.width, bitmap.height, REFERENCE, LABYRINTH_SHOP_RECEIPT_CONFIRM_RECT)
        val fingerprint = receiptFingerprint(bitmap)
        synchronized(lock) {
            state.request(fingerprint)?.let { request ->
                var owned: Bitmap? = null
                try {
                    owned = buildCollage(bitmap)
                    val collage = requireNotNull(owned)
                    submit(collage) { raw ->
                        try {
                            synchronized(lock) { state.complete(request, raw, confirmRect) }
                        } finally {
                            if (!collage.isRecycled) collage.recycle()
                        }
                    }
                } catch (_: Exception) {
                    state.complete(request, null, confirmRect)
                    owned?.let { if (!it.isRecycled) it.recycle() }
                }
            }
            return state.frameResult(bitmap.width, bitmap.height)
        }
    }

    /** 余额中间的橙色箭头会动画；只指纹真实交易文字，避免稳定回执被动画反复失效。 */
    private fun receiptFingerprint(bitmap: Bitmap): Long {
        var fingerprint = 1125899906842597L + bitmap.width * 31L + bitmap.height
        for (referenceRect in LABYRINTH_SHOP_RECEIPT_COLLAGE_FIELDS) {
            val rect = ReferenceFitMapper.map(bitmap.width, bitmap.height, REFERENCE, referenceRect) ?: continue
            if (rect.width <= 0 || rect.height <= 0 ||
                rect.left + rect.width > bitmap.width || rect.top + rect.height > bitmap.height) continue
            val pixels = IntArray(rect.width * rect.height)
            bitmap.getPixels(pixels, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
            for (pixel in pixels) fingerprint = fingerprint * 31 + pixel
        }
        return fingerprint
    }

    private fun buildCollage(bitmap: Bitmap): Bitmap {
        val rowHeight = LABYRINTH_SHOP_RECEIPT_COLLAGE_ROW_HEIGHT
        val collage = Bitmap.createBitmap(LABYRINTH_SHOP_RECEIPT_COLLAGE_WIDTH,
            rowHeight * LABYRINTH_SHOP_RECEIPT_COLLAGE_FIELDS.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(collage)
        canvas.drawColor(Color.WHITE)
        LABYRINTH_SHOP_RECEIPT_COLLAGE_FIELDS.forEachIndexed { index, referenceRect ->
            val rect = ReferenceFitMapper.map(bitmap.width, bitmap.height, REFERENCE, referenceRect)
                ?: return@forEachIndexed
            if (rect.width <= 0 || rect.height <= 0 ||
                rect.left + rect.width > bitmap.width || rect.top + rect.height > bitmap.height) return@forEachIndexed
            val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
            val scaled = Bitmap.createScaledBitmap(crop,
                crop.width * LABYRINTH_SHOP_RECEIPT_COLLAGE_SCALE,
                crop.height * LABYRINTH_SHOP_RECEIPT_COLLAGE_SCALE, true)
            if (scaled !== crop) crop.recycle()
            val tile = if (index in LABYRINTH_SHOP_RECEIPT_BINARIZED_ROWS) binarizeWhiteText(scaled) else scaled
            if (tile !== scaled) scaled.recycle()
            val left = (LABYRINTH_SHOP_RECEIPT_COLLAGE_WIDTH - tile.width).coerceAtLeast(0) / 2
            val top = index * rowHeight + (rowHeight - tile.height).coerceAtLeast(0) / 2
            canvas.drawBitmap(tile, left.toFloat(), top.toFloat(), null)
            if (!tile.isRecycled) tile.recycle()
        }
        return collage
    }

    /** 蓝底白字：亮像素画成黑字、其余画成白底，避免蓝底、渐变与描边干扰字形识别。 */
    private fun binarizeWhiteText(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val bright = (pixel ushr 16 and 0xff) > 200 &&
                (pixel ushr 8 and 0xff) > 200 && (pixel and 0xff) > 200
            pixels[index] = if (bright) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        val REFERENCE = EntryReferenceSize(1920, 1080)
    }
}
