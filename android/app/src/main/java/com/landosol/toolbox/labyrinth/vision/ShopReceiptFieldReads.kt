package com.landosol.toolbox.labyrinth.vision

/** Fixed six positions; the optional button is not read, and a nonreceipt only probes its title. */
internal fun <T> recognizeShopReceiptFields(
    crops: List<T?>,
    current: () -> Boolean,
    recognize: (T) -> String?,
): List<String?> {
    val rows = MutableList<String?>(6) { null }
    for (index in 0..4) {
        if (!current()) break
        val crop = crops.getOrNull(index)
        rows[index] = crop?.let {
            ShopPerformance.withContext(ShopPerformance.context().copy(field = "receipt-$index")) { recognize(it) }
        }
        if (index == 0 && !labyrinthShopCompletionHeading(rows[0])) break
    }
    return rows
}
