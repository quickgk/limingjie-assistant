package com.landosol.toolbox.labyrinth.vision

/** The ordinary product page may omit wallet OCR; transactional confirmations never may. */
internal fun labyrinthWalletReadRequired(result: LabyrinthEntryFrameResult, ordinaryShopRequired: Boolean): Boolean =
    ordinaryShopRequired || result.observation.state != LabyrinthEntryPageState.SHOP ||
        result.nodeMoveConfirmation != null || result.shopObservation?.dialogState != LabyrinthShopDialogState.NONE ||
        result.shopObservation?.purchaseReceipt != null
