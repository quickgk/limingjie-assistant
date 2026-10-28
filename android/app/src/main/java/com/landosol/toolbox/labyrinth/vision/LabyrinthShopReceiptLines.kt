package com.landosol.toolbox.labyrinth.vision

/** OCR框已映射至1920×1080参考坐标，禁止取底层商店余额或串入相邻字段。 */
internal fun shopReceiptFromLines(
    lines: List<LabyrinthShopOcrLine>,
    id: Long,
    confirmations: Int,
    confirmRect: EntryPixelRect?,
    frameWidth: Int = 1920,
    frameHeight: Int = 1080,
): LabyrinthShopPurchaseReceiptObservation {
    fun read(left: Int, top: Int, right: Int, bottom: Int): LabyrinthShopReceiptRead? {
        val hits = lines.filter { line -> line.rect?.let { rect ->
            rect.left >= left && rect.top >= top &&
                rect.left.toLong() + rect.width <= right &&
                rect.top.toLong() + rect.height <= bottom
        } == true }.sortedWith(compareBy({ it.rect!!.top }, { it.rect!!.left }))
        if (hits.isEmpty()) return null
        return LabyrinthShopReceiptRead(hits.joinToString("") { it.text }, id, confirmations)
    }
    val button = read(810, 700, 1110, 785)
    val expectedConfirm = ReferenceFitMapper.map(frameWidth, frameHeight, EntryReferenceSize(1920, 1080),
        LABYRINTH_SHOP_RECEIPT_CONFIRM_RECT)
    val buttonInRect = lines.filter { line -> line.rect?.let { rect ->
        rect.left >= 810 && rect.top >= 700 &&
            rect.left.toLong() + rect.width <= 1110 && rect.top.toLong() + rect.height <= 785
    } == true }.all { line ->
        val rect = requireNotNull(line.rect)
        val x = rect.left + rect.width / 2
        val y = rect.top + rect.height / 2
        x in 780 until 1140 && y in 710 until 775
    }
    return LabyrinthShopPurchaseReceiptObservation(
        heading = read(700, 250, 1220, 335),
        expenditure = read(620, 375, 1320, 430),
        item = read(510, 432, 1400, 485),
        balanceBefore = read(980, 580, 1140, 645),
        balanceAfter = read(1240, 580, 1430, 645),
        button = button,
        confirmRect = confirmRect?.takeIf {
            it == expectedConfirm && buttonInRect && button?.text?.filterNot(Char::isWhitespace) == "确认"
        },
    )
}

internal val LABYRINTH_SHOP_RECEIPT_CONFIRM_RECT = EntryReferenceRect(780, 710, 360, 65)

/** 整张回执的两次独立读数必须六字段全部一致；读取缓存本身不计数。调用方持有锁。 */
internal class LabyrinthShopReceiptReadState(private val clock: () -> Long) {
    data class Request(val ticket: LabyrinthShopOcrEpoch.Ticket, val scale: Int)
    private val requests = LabyrinthShopOcrEpoch()
    private var attempts = 0
    private var nextStart = 0L
    private var previousText: List<String?>? = null
    private var confirmations = 0
    private var verifiedAt = 0L
    var receipt: LabyrinthShopPurchaseReceiptObservation? = null
        private set

    fun clear() {
        requests.clear()
        receipt = null
        previousText = null
        confirmations = 0
        attempts = 0
        nextStart = 0
    }

    fun request(fingerprint: Long): Request? {
        if (requests.observe(fingerprint)) {
            receipt = null
            previousText = null
            confirmations = 0
            attempts = 0
            nextStart = 0
        }
        val now = clock()
        if (receipt?.verified == true && now - verifiedAt >= 5_000) {
            requests.clear()
            requests.observe(fingerprint)
            receipt = null
            previousText = null
            confirmations = 0
            attempts = (attempts / 4 + 1) * 4
            nextStart = now
        }
        if (now < nextStart || receipt?.verified == true) return null
        val ticket = requests.request() ?: return null
        val scale = if (attempts / 4 % 2 == 0) 2 else 1
        nextStart = now + if (attempts < 4) 200 else 1_500
        attempts++
        return Request(ticket, scale)
    }

    fun complete(
        request: Request,
        lines: List<LabyrinthShopOcrLine>?,
        confirmRect: EntryPixelRect?,
        frameWidth: Int = 1920,
        frameHeight: Int = 1080,
    ) {
        if (!requests.complete(request.ticket) || lines == null) return
        val parsed = shopReceiptFromLines(lines, request.ticket.evidenceId, 1, confirmRect, frameWidth, frameHeight)
        val fields = listOf(parsed.heading?.text, parsed.item?.text, parsed.expenditure?.text,
            parsed.balanceBefore?.text, parsed.balanceAfter?.text, parsed.button?.text)
        confirmations = if (previousText == fields) confirmations + 1 else 1
        previousText = fields
        receipt = shopReceiptFromLines(lines, request.ticket.evidenceId, confirmations, confirmRect, frameWidth, frameHeight)
        if (receipt?.verified == true) verifiedAt = clock()
    }
}
