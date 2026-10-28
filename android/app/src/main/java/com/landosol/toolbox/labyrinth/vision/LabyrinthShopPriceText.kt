package com.landosol.toolbox.labyrinth.vision

/** 只读取固定五格数字拼图，各元素框必须完整落在自己的格子中。 */
internal fun labyrinthShopPricesFromCollage(lines: List<LabyrinthShopOcrLine>): Map<String, String?> =
    LABYRINTH_SHOP_PRICE_KEYS.mapIndexed { index, key ->
        val left = index % 3 * 500
        val top = index / 3 * 180
        val values = lines.filter { line -> line.rect?.let { rect ->
            rect.left >= left && rect.top >= top &&
                rect.left.toLong() + rect.width <= left + 500 &&
                rect.top.toLong() + rect.height <= top + 180
        } == true }.sortedWith(compareBy({ it.rect!!.top }, { it.rect!!.left }))
            .joinToString("") { it.text }.filterNot(Char::isWhitespace)
        val text = if (key == "refresh" && values.startsWith("用")) {
            Regex("^用([0-9,，]+)更新$").matchEntire(values)?.groupValues?.get(1)
        } else values
        key to text?.let(LabyrinthShopPurchaseReceiptObservation::parseAmount)?.takeIf {
            it <= 999_999 && (key == "wallet" || it > 0)
        }?.toString()
    }.toMap()

internal val LABYRINTH_SHOP_PRICE_KEYS = listOf("shop_item_1", "shop_item_2", "shop_item_3", "refresh", "wallet")

/** 五字段共享一次OCR；只有当前epoch的独立请求能增加一致读数。调用方持有锁。 */
internal class LabyrinthShopPriceReadState(private val clock: () -> Long) {
    private val requests = LabyrinthShopOcrEpoch()
    private var attempts = 0
    private var nextStart = 0L
    var reads: Map<String, LabyrinthShopReceiptRead> = emptyMap()
        private set

    fun clear() {
        requests.clear()
        reads = emptyMap()
        attempts = 0
        nextStart = 0
    }

    fun request(fingerprint: Long): LabyrinthShopOcrEpoch.Ticket? {
        if (requests.observe(fingerprint)) {
            reads = emptyMap()
            attempts = 0
            nextStart = 0
        }
        val now = clock()
        if (attempts >= 6 && now >= nextStart) {
            attempts = 0
            reads = emptyMap()
        }
        if (reads.values.count { it.confirmations >= 2 } == LABYRINTH_SHOP_PRICE_KEYS.size || now < nextStart) return null
        val ticket = requests.request() ?: return null
        attempts++
        nextStart = now + if (attempts < 6) 300 else 3_000
        return ticket
    }

    fun complete(ticket: LabyrinthShopOcrEpoch.Ticket, lines: List<LabyrinthShopOcrLine>?) {
        if (!requests.complete(ticket) || lines == null) return
        val values = labyrinthShopPricesFromCollage(lines)
        reads = LABYRINTH_SHOP_PRICE_KEYS.associateWith { key ->
            val value = values[key]
            val old = reads[key]
            LabyrinthShopReceiptRead(value, ticket.evidenceId,
                if (value != null && old?.text == value) old.confirmations + 1 else 1)
        }
    }

    val reviewReason: String? get() =
        if (attempts >= 6 && reads.values.count { it.confirmations >= 2 } < LABYRINTH_SHOP_PRICE_KEYS.size)
            "价格/余额独立复核未完成，保留当前页并自动重读" else null
}
