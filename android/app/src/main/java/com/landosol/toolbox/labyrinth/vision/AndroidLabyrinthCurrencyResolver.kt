package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/** 钱包始终来自当前画面的独立双读，确认页不会继承商店页读数。 */
class AndroidLabyrinthCurrencyResolver(
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
    submitShopTextRead: (Bitmap, (String?) -> Unit) -> Unit = submitTextRead,
) {
    private val ocr = AndroidRelicRoiOcr(submitTextRead)
    private val shopOcr = AndroidRelicRoiOcr(submitShopTextRead)
    private var coordinatedShopOcr: AndroidShopRoiOcr? = null
    private var fallbackCaptureSequence = 0L
    internal fun useShopRuntime(runtime: AndroidShopOcrRuntime) {
        coordinatedShopOcr = AndroidShopRoiOcr(runtime, "wallet")
    }
    private var source: LabyrinthCurrencySource? = null
    private var shopRead = false
    private var ordinaryWalletFingerprint: Long? = null

    private fun clearReads() {
        ocr.clear()
        shopOcr.clear()
        coordinatedShopOcr?.clear()
    }

    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult =
        resolve(bitmap, result, AndroidFrameRoiFingerprints(bitmap, ++fallbackCaptureSequence), result.shopObservation?.stockEpoch ?: 0)

    @Synchronized internal fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult, frame: AndroidFrameRoiFingerprints,
        epoch: Long, ordinaryShopRequired: Boolean = true,
        onShopWalletChanged: () -> Unit = {}): LabyrinthEntryFrameResult {
        if (!labyrinthWalletReadRequired(result, ordinaryShopRequired)) {
            val rect = ReferenceFitMapper.map(bitmap.width, bitmap.height, REFERENCE,
                EntryReferenceRect(1260, 940, 150, 64)) ?: return result.copy(currencyObservation = null)
            val fingerprint = frame.fingerprint(bitmap, rect)
            val previous = ordinaryWalletFingerprint
            ordinaryWalletFingerprint = fingerprint
            if (previous == null || previous == fingerprint) {
                clearReads()
                return result.copy(currencyObservation = null)
            }
            clearReads()
            onShopWalletChanged()
        }
        coordinatedShopOcr?.bind("$epoch:${bitmap.width}x${bitmap.height}", result.observation.state.name)
        val currentSource = labyrinthCurrencyReadSource(result)
        if (currentSource == null) {
            clearReads()
            source = null
            shopRead = false
            return result.copy(currencyObservation = null)
        }
        val currentShopRead = result.observation.state != LabyrinthEntryPageState.NODE_SELECTION
        if (source != currentSource || shopRead != currentShopRead) clearReads()
        source = currentSource
        shopRead = currentShopRead
        val referenceRect = when (currentSource) {
            LabyrinthCurrencySource.PURCHASE_CONFIRMATION -> LABYRINTH_SHOP_CONFIRMATION_BALANCE_RECT
            LabyrinthCurrencySource.REFRESH_CONFIRMATION -> LABYRINTH_SHOP_REFRESH_BALANCE_RECT
            LabyrinthCurrencySource.WALLET -> if (result.observation.state == LabyrinthEntryPageState.NODE_SELECTION)
                EntryReferenceRect(1540, 20, 210, 62) else EntryReferenceRect(1260, 940, 150, 64)
        }
        val mapped = ReferenceFitMapper.map(bitmap.width, bitmap.height, REFERENCE, referenceRect)
            ?: return result.copy(currencyObservation = null)
        val reader = if (currentShopRead) shopOcr else ocr
        if (result.observation.state == LabyrinthEntryPageState.SHOP)
            ordinaryWalletFingerprint = frame.fingerprint(bitmap, mapped)
        val field = "wallet-${currentSource.name}-${result.observation.state.name}"
        val raw = if (currentShopRead && coordinatedShopOcr != null)
            coordinatedShopOcr!!.read(bitmap, frame, field, mapped, 2, priority = 1)
        else reader.read(bitmap, field, mapped, 2)
        val read = raw?.let { LabyrinthShopReceiptRead(it.text, it.id, it.confirmations) }
        return result.copy(currencyObservation = LabyrinthCurrencyObservation(read, currentSource))
    }

    @Synchronized internal fun clear() {
        clearReads()
        ordinaryWalletFingerprint = null
        source = null
        shopRead = false
    }

    private companion object { val REFERENCE = EntryReferenceSize(1920, 1080) }
}
