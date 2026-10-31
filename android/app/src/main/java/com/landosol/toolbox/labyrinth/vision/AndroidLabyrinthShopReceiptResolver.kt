package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock

internal enum class LabyrinthShopReceiptReadStatus { READING, NOT_RECEIPT, PURCHASE_COMPLETE }

/**
 * 是否保留 ML Kit 影子读数。
 *
 * 关闭时回执路径只跑 PP-OCRv4；ML Kit 仅在 Paddle 引擎不可用时作为主读退路，
 * 那条退路不受这个开关影响。
 */
private const val SHADOW_MLKIT_ENABLED = false

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
        shopReceiptOcr = diagnostics(),
        frameWidth = frameWidth,
        frameHeight = frameHeight,
    )
}

/**
 * 回执识别器。
 *
 * 主读是 PP-OCRv4：六块字段按参考矩形各自裁出、逐块识别，读数直接进判定。未配置 Paddle 引擎
 * 时退回拼图路径：六块裁图放大后排进一张白底拼图、整图只 OCR 一次，白字蓝底的标题与确认按钮
 * 先做白字黑底二值化。两次读数的复核交给 [LabyrinthShopReceiptReadState] 按解析事实比较。
 */
internal class AndroidLabyrinthShopReceiptResolver(
    clock: () -> Long = SystemClock::elapsedRealtime,
    private val submit: (Bitmap, (List<LabyrinthShopOcrLine>?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readElementsAsync,
    /**
     * PP-OCRv4 主读引擎；null 表示不运行，此时退回拼图 + ML Kit，判定链保持可用但没有影子读数。
     */
    private val paddle: PaddleReceiptOcr? = null,
) {
    private val lock = Any()
    private val state = LabyrinthShopReceiptReadState(clock)
    private val paddleExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "paddle-receipt-ocr").apply { isDaemon = true }
    }
    /** 影子读数每个回执画面只跑一次。 */
    private var mlkitFingerprint: Long? = null

    fun clear() = synchronized(lock) {
        state.clear()
        mlkitFingerprint = null
    }

    /** 最近一次回执读数与判定；回执没读成时也带着它，供调试状态记录现场。 */
    fun diagnostics(): LabyrinthShopReceiptOcrDiagnostics? = synchronized(lock) { state.diagnostics() }

    fun resolve(bitmap: Bitmap): LabyrinthEntryFrameResult? {
        val confirmRect = ReferenceFitMapper.map(bitmap.width, bitmap.height, REFERENCE, LABYRINTH_SHOP_RECEIPT_CONFIRM_RECT)
        val fingerprint = receiptFingerprint(bitmap)
        synchronized(lock) {
            // PP-OCRv4 是主读：它同时承担判定与诊断里的 paddle 字段。引擎不可用时退回 ML Kit，
            // 判定链保持可用，只是没有影子读数。
            state.setPrimaryEngine(paddle?.modelName ?: MLKIT_SHADOW_MODEL)
            state.request(fingerprint)?.let { request ->
                val engine = paddle
                if (engine != null) submitPaddle(bitmap, request, confirmRect, engine)
                else submitMlKit(bitmap, request, confirmRect)
            }
            scheduleMlKitShadow(bitmap, fingerprint)
            return state.frameResult(bitmap.width, bitmap.height)
        }
    }

    /** 主读：PP-OCRv4 逐块识别六行，结果直接进判定。 */
    private fun submitPaddle(
        bitmap: Bitmap,
        request: LabyrinthShopReceiptReadState.Request,
        confirmRect: EntryPixelRect?,
        engine: PaddleReceiptOcr,
    ) {
        state.markPaddleRunning(engine.modelName)
        val crops = cropFields(bitmap)
        paddleExecutor.execute {
            val texts = crops.map { crop ->
                try {
                    engine.recognize(crop)
                } catch (_: Exception) {
                    null
                } finally {
                    if (!crop.isRecycled) crop.recycle()
                }
            }
            synchronized(lock) {
                state.setPaddle(texts)
                state.completeRows(request, texts, confirmRect)
            }
        }
    }

    /** 主读退路：Paddle 不可用时仍用拼图 + ML Kit，保证判定链不断。 */
    private fun submitMlKit(
        bitmap: Bitmap,
        request: LabyrinthShopReceiptReadState.Request,
        confirmRect: EntryPixelRect?,
    ) {
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

    /**
     * 影子读数：每个回执画面只跑一次 ML Kit，结果只写诊断，不参与判定。
     *
     * 默认关闭——Paddle 已经是主读，关掉可省一次拼图构建（2× 放大 + 两行二值化）加一次整图 OCR。
     * 需要重新评估引擎时把 [SHADOW_MLKIT_ENABLED] 打开即可，判定链不受影响。
     */
    private fun scheduleMlKitShadow(bitmap: Bitmap, fingerprint: Long) {
        if (!SHADOW_MLKIT_ENABLED) return
        if (paddle == null) return
        if (mlkitFingerprint == fingerprint) return
        mlkitFingerprint = fingerprint
        var owned: Bitmap? = null
        try {
            owned = buildCollage(bitmap)
            val collage = requireNotNull(owned)
            submit(collage) { raw ->
                try {
                    synchronized(lock) {
                        if (mlkitFingerprint == fingerprint && raw != null) {
                            state.setMlKit(shopReceiptCollageRowTexts(raw))
                        }
                    }
                } finally {
                    if (!collage.isRecycled) collage.recycle()
                }
            }
        } catch (_: Exception) {
            owned?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /** 按参考矩形裁出六个字段，调用线程内完成（位图生命周期由调用方保证）。 */
    private fun cropFields(bitmap: Bitmap): List<Bitmap> =
        LABYRINTH_SHOP_RECEIPT_COLLAGE_FIELDS.mapNotNull { reference ->
            val rect = ReferenceFitMapper.map(bitmap.width, bitmap.height, REFERENCE, reference)
                ?: return@mapNotNull null
            if (rect.width <= 0 || rect.height <= 0 ||
                rect.left + rect.width > bitmap.width || rect.top + rect.height > bitmap.height) return@mapNotNull null
            Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
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
