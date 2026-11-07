package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/** 快速路径和普通路径共用回退：否定结果不能再次抢占已识别页面。 */
internal fun resolveShopReceiptOrPage(
    receipt: LabyrinthEntryFrameResult?,
    invalidateStock: () -> Unit = {},
    page: () -> LabyrinthEntryFrameResult,
): LabyrinthEntryFrameResult = receipt?.also { invalidateStock() } ?: page()

/** Every visible title is classified before a relic match can authorize a purchase. */
internal class AndroidLabyrinthShopItemResolver(
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
    submitReceiptLines: (Bitmap, (List<LabyrinthShopOcrLine>?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readElementsAsync,
    /** 回执字段的主读（PP-OCRv4）。 */
    paddle: PaddleReceiptOcr? = null,
    private val runtime: AndroidShopOcrRuntime = AndroidShopOcrRuntime(paddle, submitTextRead),
) {
    private val receiptResolver = AndroidLabyrinthShopReceiptResolver(submit = submitReceiptLines, paddle = paddle, runtime = runtime)
    private val ocr = AndroidShopRoiOcr(runtime, "stock-titles", maximumAttempts = 1)
    private val confirmationOcr = AndroidShopRoiOcr(runtime, "confirmation-title")
    private val genericDialogOcr = AndroidShopRoiOcr(runtime, "generic-dialog-title")
    private val categories = LabyrinthShopCategoryResolution()
    private data class AcceptedTitle(val rect: EntryPixelRect, val fingerprint: Long, val read: RelicOcrScheduler.Read)
    private val accepted = mutableMapOf<String, AcceptedTitle>()
    private var stockEpoch = Long.MIN_VALUE
    private var titleViewport: LabyrinthShopViewport? = null

    @Synchronized fun bindStockEpoch(epoch: Long) {
        if (stockEpoch == epoch) return
        stockEpoch = epoch
        clearStockTitles()
        confirmationOcr.clear()
        genericDialogOcr.clear()
        receiptResolver.bindScope(epoch.toString())
    }

    @Synchronized fun clear() {
        clearStockTitles()
        confirmationOcr.clear()
        genericDialogOcr.clear()
        receiptResolver.clear()
    }

    @Synchronized fun clearStockTitles() {
        ocr.clear()
        categories.clear()
        accepted.clear()
        titleViewport = null
    }

    @Synchronized
    fun fastReceipt(bitmap: Bitmap, frame: AndroidFrameRoiFingerprints, shopTitleScore: () -> Double): LabyrinthEntryFrameResult? {
        if (bitmap.width <= bitmap.height || !modalShellVisible(bitmap) || shopTitleScore() < 0.65) {
            receiptResolver.clear()
            return null
        }
        clearStockTitles()
        return receiptResolver.resolve(bitmap, frame).also { if (it != null) {
            confirmationOcr.clear()
            genericDialogOcr.clear()
        } }
    }

    @Synchronized fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult, frame: AndroidFrameRoiFingerprints,
        purchasedSlots: Set<String> = emptySet()): LabyrinthEntryFrameResult {
        val scores = result.observation.anchorScores
        // 外框只启动OCR；成功与点击仍须标题、字段双读、余额差额及确认矩形共同核验。
        val receiptShell = modalShellVisible(bitmap) &&
            (scores[EntryAnchorId.SHOP_TITLE] >= 0.65 ||
                result.observation.state == LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE)
        if (receiptShell) clearStockTitles()
        val receipt = if (receiptShell) receiptResolver.resolve(bitmap, frame) else {
            receiptResolver.clear()
            null
        }
        val resolved = resolveShopReceiptOrPage(receipt?.let {
            confirmationOcr.clear()
            genericDialogOcr.clear()
            result.copy(observation = it.observation, shopObservation = it.shopObservation,
                matchedFeatures = result.matchedFeatures + it.matchedFeatures,
                shopReceiptOcr = it.shopReceiptOcr)
        }, invalidateStock = ::clearStockTitles) { resolveItems(bitmap, result, frame, purchasedSlots) }
        // 回执没认出来时同样要把六行读数带进调试状态。
        return if (resolved.shopReceiptOcr != null) resolved
            else resolved.copy(shopReceiptOcr = receiptResolver.diagnostics())
    }

    private fun resolveItems(bitmap: Bitmap, result: LabyrinthEntryFrameResult, frame: AndroidFrameRoiFingerprints,
        purchasedSlots: Set<String>): LabyrinthEntryFrameResult {
        val scope = "$stockEpoch:${bitmap.width}x${bitmap.height}"
        confirmationOcr.bind(scope, result.observation.state.name)
        genericDialogOcr.bind(scope, result.observation.state.name)
        ocr.bind(scope, "stock-A")
        if (labyrinthShopGenericDialogVisible(result)) {
            clearStockTitles()
            confirmationOcr.clear()
            val rect = ReferenceFitMapper.map(bitmap.width, bitmap.height, STANDARD_REFERENCE,
                LABYRINTH_SHOP_GENERIC_TITLE_RECT)
            val read = rect?.let { genericDialogOcr.read(bitmap, frame, "shop-generic-dialog-title", it, 2, whiteText = true, priority = 1) }
                ?.let { LabyrinthShopReceiptRead(it.text, it.id, it.confirmations) }
            return resolveShoppingGenericDialog(result, read)
        }
        genericDialogOcr.clear()
        if (result.observation.state == LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION &&
            result.shopObservation?.dialogState == LabyrinthShopDialogState.PURCHASE_CONFIRMATION) {
            clearStockTitles()
            val rect = ReferenceFitMapper.map(bitmap.width, bitmap.height, STANDARD_REFERENCE,
                LABYRINTH_SHOP_CONFIRMATION_TITLE_RECT) ?: return result
            val read = confirmationOcr.read(bitmap, frame, "purchase-confirmation-title", rect, 2, darkText = true, priority = 1)
                ?.let { LabyrinthShopReceiptRead(it.text, it.id, it.confirmations) }
            return result.copy(shopObservation = result.shopObservation.copy(confirmationTitle = read))
        }
        confirmationOcr.clear()
        if (result.observation.state != LabyrinthEntryPageState.SHOP) {
            clearStockTitles()
            return result
        }
        val shop = result.shopObservation ?: return result
        if (result.nodeMoveConfirmation != null ||
            shop.viewport != LabyrinthShopViewport.A && purchasedSlots.isEmpty()) {
            clearStockTitles()
            return result
        }
        if (titleViewport != shop.viewport) clearStockTitles()
        titleViewport = shop.viewport
        val items = shop.items.map { item ->
            if (item.slotId in shop.emptySlotIds || item.slotId in purchasedSlots) return@map item
            val index = LabyrinthShopSlots.keys.indexOf(item.slotId)
            if (index < 0) return@map item
            val referenceRect = EntryReferenceRect(listOf(125, 703, 1278)[index % 3],
                230 + index / 3 * 380 - shop.contentOffset, 390, 55)
            if (referenceRect.y < 202 || referenceRect.y + referenceRect.height > 875) return@map item
            val titleRect = ReferenceFitMapper.map(bitmap.width, bitmap.height, STANDARD_REFERENCE, referenceRect)
                ?: return@map item
            val fingerprint = frame.fingerprint(bitmap, titleRect)
            accepted[item.slotId]?.takeIf { it.fingerprint == fingerprint && it.rect == titleRect }?.let { cached ->
                return@map categories.resolve(item, cached.read.text, cached.read.id, 0)
            }
            accepted.remove(item.slotId)
            val now = android.os.SystemClock.elapsedRealtime()
            val attempt = categories.attempt(item.slotId, fingerprint, now)
            val variant = minOf(attempt, 2)
            val rect = if (variant == 2) titleRect.copy(
                top = maxOf(0, titleRect.top - 3),
                height = minOf(bitmap.height - maxOf(0, titleRect.top - 3), titleRect.height + 6),
            ) else titleRect
            val read = ocr.read(bitmap, frame, "shop-title-$stockEpoch-${item.slotId}-$variant", rect,
                scale = if (variant == 0) 2 else 3)
            val resolved = categories.resolve(item, read?.text, read?.id, attempt)
            if (resolved.categoryState == LabyrinthShopCategoryState.READY && read != null) {
                accepted[item.slotId] = AcceptedTitle(titleRect, fingerprint, read)
            } else {
                // Retire this crop variant only once its OCR actually completed (read != null),
                // not on a fixed interval: the reads share one serial queue and a time clock was
                // failing titles that were still being processed. The engine's own last answer
                // tells the difference between "queued" and "dead".
                categories.noteVariantOutcome(
                    slot = item.slotId,
                    fingerprint = fingerprint,
                    variant = variant,
                    completed = read != null,
                    engineLastCompletedAt = ocr.lastCompletionAt(),
                    now = now,
                )
            }
            resolved
        }
        return result.copy(shopObservation = shop.copy(items = items))
    }

    private fun modalShellVisible(bitmap: Bitmap): Boolean {
        fun color(x: Int, y: Int): Int? {
            val rect = ReferenceFitMapper.map(bitmap.width, bitmap.height, STANDARD_REFERENCE,
                EntryReferenceRect(x - 3, y - 3, 6, 6)) ?: return null
            return bitmap.getPixel(rect.left + rect.width / 2, rect.top + rect.height / 2)
        }
        fun blue(x: Int, y: Int): Boolean = color(x, y)?.let {
            val red = it ushr 16 and 255
            val green = it ushr 8 and 255
            val blue = it and 255
            blue > 145 && blue > red + 45 && green > 85
        } ?: false
        fun white(x: Int, y: Int): Boolean = color(x, y)?.let {
            (it ushr 16 and 255) > 225 && (it ushr 8 and 255) > 225 && (it and 255) > 225
        } ?: false
        return blue(600, 280) && blue(1300, 280) && white(600, 500) && white(1340, 500) &&
            blue(810, 735) && blue(1110, 735)
    }

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
    }
}
