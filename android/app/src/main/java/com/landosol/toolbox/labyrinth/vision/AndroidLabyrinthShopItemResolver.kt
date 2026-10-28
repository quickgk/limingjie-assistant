package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/** 快速路径和普通路径共用回退：否定结果不能再次抢占已识别页面。 */
internal fun resolveShopReceiptOrPage(
    receipt: LabyrinthEntryFrameResult?,
    page: () -> LabyrinthEntryFrameResult,
): LabyrinthEntryFrameResult = receipt ?: page()

/** Every visible title is classified before a relic match can authorize a purchase. */
class AndroidLabyrinthShopItemResolver(
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
    submitReceiptLines: (Bitmap, (List<LabyrinthShopOcrLine>?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readElementsAsync,
) {
    private val receiptResolver = AndroidLabyrinthShopReceiptResolver(submit = submitReceiptLines)
    private val ocr = AndroidRelicRoiOcr(submitTextRead, maximumAttempts = 1)
    private val categories = LabyrinthShopCategoryResolution()
    private val accepted = mutableMapOf<String, Pair<Long, RelicOcrScheduler.Read>>()

    fun fastReceipt(bitmap: Bitmap, shopTitleScore: () -> Double): LabyrinthEntryFrameResult? {
        if (bitmap.width <= bitmap.height || !modalShellVisible(bitmap) || shopTitleScore() < 0.65) {
            receiptResolver.clear()
            return null
        }
        return receiptResolver.resolve(bitmap)
    }

    @Synchronized
    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
        val scores = result.observation.anchorScores
        // 外框只启动OCR；成功与点击仍须标题、六字段双读、余额差额及按钮位置共同核验。
        val receiptShell = modalShellVisible(bitmap) &&
            (scores[EntryAnchorId.SHOP_TITLE] >= 0.65 ||
                result.observation.state == LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE)
        val receipt = if (receiptShell) receiptResolver.resolve(bitmap) else {
            receiptResolver.clear()
            null
        }
        return resolveShopReceiptOrPage(receipt?.let {
            result.copy(observation = it.observation, shopObservation = it.shopObservation,
                matchedFeatures = result.matchedFeatures + it.matchedFeatures)
        }) { resolveItems(bitmap, result) }
    }

    private fun resolveItems(bitmap: Bitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
        if (result.observation.state != LabyrinthEntryPageState.SHOP) {
            ocr.clear()
            categories.clear()
            accepted.clear()
            return result
        }
        val shop = result.shopObservation ?: return result
        val items = shop.items.map { item ->
            val referenceRect = TITLE_RECTS[item.slotId] ?: return@map item
            val titleRect = ReferenceFitMapper.map(bitmap.width, bitmap.height, STANDARD_REFERENCE, referenceRect)
                ?: return@map item
            val fingerprint = relicRoiFingerprint(bitmap, titleRect)
            accepted[item.slotId]?.takeIf { it.first == fingerprint }?.let { (_, read) ->
                return@map categories.resolve(item, read.text, read.id, 0)
            }
            accepted.remove(item.slotId)
            val now = android.os.SystemClock.elapsedRealtime()
            val attempt = categories.attempt(item.slotId, fingerprint, now)
            val variant = minOf(attempt, 2)
            val rect = if (variant == 2) titleRect.copy(
                top = maxOf(0, titleRect.top - 3),
                height = minOf(bitmap.height - maxOf(0, titleRect.top - 3), titleRect.height + 6),
            ) else titleRect
            val read = ocr.read(bitmap, "shop-title-${item.slotId}-$variant", rect,
                scale = if (variant == 0) 2 else 3)
            val resolved = categories.resolve(item, read?.text, read?.id, attempt)
            if (resolved.categoryState == LabyrinthShopCategoryState.READY && read != null) {
                accepted[item.slotId] = fingerprint to read
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
        val TITLE_RECTS = mapOf(
            "shop_item_1" to EntryReferenceRect(125, 230, 390, 55),
            "shop_item_2" to EntryReferenceRect(703, 230, 390, 55),
            "shop_item_3" to EntryReferenceRect(1278, 230, 390, 55),
        )
    }
}
