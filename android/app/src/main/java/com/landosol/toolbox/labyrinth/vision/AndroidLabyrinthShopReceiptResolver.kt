package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
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
                else observed.reviewReason ?: "shop-receipt-whole-modal-verified",
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

/** 整个回执裁图读两次，六字段定位及复核由纯Kotlin状态处理。 */
internal class AndroidLabyrinthShopReceiptResolver(
    clock: () -> Long = SystemClock::elapsedRealtime,
    private val submit: (Bitmap, (List<LabyrinthShopOcrLine>?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readElementsAsync,
) {
    private val lock = Any()
    private val state = LabyrinthShopReceiptReadState(clock)

    fun clear() = synchronized(lock) { state.clear() }

    fun resolve(bitmap: Bitmap): LabyrinthEntryFrameResult? {
        val cropRect = requireNotNull(ReferenceFitMapper.map(bitmap.width, bitmap.height, REFERENCE, MODAL))
        val confirmRect = ReferenceFitMapper.map(bitmap.width, bitmap.height, REFERENCE, LABYRINTH_SHOP_RECEIPT_CONFIRM_RECT)
        // 余额中间的橙色箭头会动画；只指纹真实交易文字，避免稳定回执被动画反复失效。
        var fingerprint = 1125899906842597L + bitmap.width * 31L + bitmap.height
        for (referenceRect in TRANSACTION_TEXT) {
            val rect = requireNotNull(ReferenceFitMapper.map(bitmap.width, bitmap.height, REFERENCE, referenceRect))
            val pixels = IntArray(rect.width * rect.height)
            bitmap.getPixels(pixels, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
            for (pixel in pixels) fingerprint = fingerprint * 31 + pixel
        }
        synchronized(lock) {
            state.request(fingerprint)?.let { request ->
                var owned: Bitmap? = null
                try {
                    // ROI严格小于整帧，createBitmap不会借用调用方的整帧位图。
                    owned = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width, cropRect.height)
                    if (request.scale != 1) {
                        val original = requireNotNull(owned)
                        owned = Bitmap.createScaledBitmap(original, original.width * request.scale, original.height * request.scale, true)
                        if (owned !== original) original.recycle()
                    }
                    val input = requireNotNull(owned)
                    val inputWidth = input.width
                    val inputHeight = input.height
                    val frameWidth = bitmap.width
                    val frameHeight = bitmap.height
                    submit(input) { raw ->
                        try {
                            val lines = raw?.map { line -> line.copy(rect = line.rect?.let { rect ->
                                EntryPixelRect(
                                    MODAL.x + rect.left * MODAL.width / inputWidth,
                                    MODAL.y + rect.top * MODAL.height / inputHeight,
                                    maxOf(1, rect.width * MODAL.width / inputWidth),
                                    maxOf(1, rect.height * MODAL.height / inputHeight),
                                )
                            }) }
                            synchronized(lock) { state.complete(request, lines, confirmRect, frameWidth, frameHeight) }
                        } finally { if (!input.isRecycled) input.recycle() }
                    }
                } catch (_: Exception) {
                    state.complete(request, null, confirmRect, bitmap.width, bitmap.height)
                    owned?.let { if (!it.isRecycled) it.recycle() }
                }
            }
            return state.frameResult(bitmap.width, bitmap.height)
        }
    }

    private companion object {
        val REFERENCE = EntryReferenceSize(1920, 1080)
        val MODAL = EntryReferenceRect(480, 250, 970, 555)
        val TRANSACTION_TEXT = listOf(
            EntryReferenceRect(700, 250, 520, 85),
            EntryReferenceRect(620, 375, 700, 55),
            EntryReferenceRect(510, 432, 890, 53),
            // 实际帧的动画箭头从x=1131开始。
            EntryReferenceRect(980, 580, 145, 65),
            EntryReferenceRect(1240, 580, 190, 65),
            EntryReferenceRect(810, 710, 300, 65),
        )
    }
}
