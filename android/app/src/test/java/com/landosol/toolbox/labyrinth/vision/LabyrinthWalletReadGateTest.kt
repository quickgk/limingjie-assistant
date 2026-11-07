package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.*
import org.junit.Test

class LabyrinthWalletReadGateTest {
    private fun frame(page: LabyrinthEntryPageState, dialog: LabyrinthShopDialogState = LabyrinthShopDialogState.NONE) =
        LabyrinthEntryFrameResult(LabyrinthEntryPageObservation(page, 1.0, emptyMap(), LabyrinthAnchorScores(emptyMap())), emptyList(), 0,
            shopObservation = LabyrinthShopObservation(emptyList(), dialog))

    @Test fun `only ordinary verified shop suppresses wallet requests and no observation is invented`() {
        val ordinary = frame(LabyrinthEntryPageState.SHOP)
        assertTrue(labyrinthWalletReadRequired(ordinary, true))
        assertFalse(labyrinthWalletReadRequired(ordinary, false))
        assertNull(ordinary.currencyObservation)
        assertTrue(labyrinthWalletReadRequired(ordinary.copy(shopObservation = null), false))
    }

    @Test fun `confirmations refresh completion and unrelated pages cannot suppress real reads`() {
        for (page in listOf(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
            LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE, LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
            LabyrinthEntryPageState.NODE_SELECTION, LabyrinthEntryPageState.UNKNOWN)) {
            assertTrue(labyrinthWalletReadRequired(frame(page), false))
        }
        assertTrue(labyrinthWalletReadRequired(frame(LabyrinthEntryPageState.SHOP,
            LabyrinthShopDialogState.PURCHASE_CONFIRMATION), false))
        assertTrue(labyrinthWalletReadRequired(frame(LabyrinthEntryPageState.SHOP).copy(shopObservation =
            LabyrinthShopObservation(emptyList(), purchaseReceipt = LabyrinthShopPurchaseReceiptObservation())), false))
    }
}
