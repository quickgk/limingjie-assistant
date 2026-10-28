package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/**
 * 只读取未被弹窗遮挡的钱包；商店拼图的余额快照优先，不重复OCR。
 *
 * 地图页（NODE_SELECTION）只读钱包一个 ROI：角色数与遗物数没有消费者，多读一个 ROI 就是每帧
 * 多一次 OCR 与耗电。
 */
class AndroidLabyrinthCurrencyResolver(
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
) {
    private val ocr = AndroidRelicRoiOcr(submitTextRead)

    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
        if (result.observation.state == LabyrinthEntryPageState.SHOP && result.currencyObservation != null) {
            clear()
            return result
        }
        val rect = when (result.observation.state) {
            LabyrinthEntryPageState.NODE_SELECTION -> EntryReferenceRect(1540, 20, 210, 62)
            LabyrinthEntryPageState.SHOP -> EntryReferenceRect(1260, 940, 150, 64)
            else -> { clear(); return result }
        }
        if (result.shopObservation?.purchaseReceipt != null || result.nodeMoveConfirmation != null) {
            clear()
            return result
        }
        val mapped = ReferenceFitMapper.map(bitmap.width, bitmap.height, REFERENCE, rect) ?: return result
        val read = ocr.read(bitmap, "wallet-${result.observation.state.name}", mapped, 2)
            ?.let { LabyrinthShopReceiptRead(it.text, it.id, it.confirmations) }
        return result.copy(currencyObservation = LabyrinthCurrencyObservation(read))
    }

    internal fun clear() { ocr.clear() }

    private companion object { val REFERENCE = EntryReferenceSize(1920, 1080) }
}
