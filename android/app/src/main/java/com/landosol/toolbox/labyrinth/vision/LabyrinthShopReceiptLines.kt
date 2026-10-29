package com.landosol.toolbox.labyrinth.vision

/**
 * 回执各字段在 1920×1080 参考坐标里的位置。
 *
 * 识别前把这几块分别裁出来、放大后排进一张白底拼图（六行，行高固定），再整图 OCR 一次。
 * 这样每个字段只面对自己那一行文字，不再被模态弹窗的蓝底标题、金色边框、圆点分隔线干扰。
 * 顺序即拼图的行序：0 标题、1 花费、2 商品、3 前余额、4 后余额、5 确认按钮。
 */
internal val LABYRINTH_SHOP_RECEIPT_COLLAGE_FIELDS = listOf(
    // 标题只取蓝底标题条本身：再往上就是被压暗的商店背景，那里的文字在原始帧上仍然可读，
    // 裁进拼图会并进标题行，标题便永远对不上。
    EntryReferenceRect(700, 264, 520, 60),
    EntryReferenceRect(620, 375, 700, 55),
    EntryReferenceRect(510, 420, 890, 70),
    EntryReferenceRect(980, 580, 145, 65),
    EntryReferenceRect(1240, 580, 190, 65),
    EntryReferenceRect(810, 700, 300, 85),
)

/**
 * 白字蓝底的两块（标题、确认按钮）先做二值反转：蓝底、渐变和描边不会再干扰字形识别，
 * 标题块也只剩标题条自己的四个字。生产帧恒为 1920×1080，二值化后笔画足够粗。
 */
internal val LABYRINTH_SHOP_RECEIPT_BINARIZED_ROWS = setOf(0, 5)

/** 拼图每行高度；解析按元素中心落在哪一行归属字段。 */
internal const val LABYRINTH_SHOP_RECEIPT_COLLAGE_ROW_HEIGHT = 200
internal const val LABYRINTH_SHOP_RECEIPT_COLLAGE_WIDTH = 1800
internal const val LABYRINTH_SHOP_RECEIPT_COLLAGE_SCALE = 2
internal const val LABYRINTH_SHOP_RECEIPT_BUTTON_ROW = 5

/** 确认按钮在参考坐标里的位置；点击矩形不依赖 OCR 结果。 */
internal val LABYRINTH_SHOP_RECEIPT_CONFIRM_RECT = EntryReferenceRect(780, 710, 360, 65)

/** 解析一次拼图 OCR 的结果；行序见 [LABYRINTH_SHOP_RECEIPT_COLLAGE_FIELDS]。 */
internal fun shopReceiptFromCollageLines(
    lines: List<LabyrinthShopOcrLine>,
    id: Long,
    confirmations: Int,
    confirmRect: EntryPixelRect?,
): LabyrinthShopPurchaseReceiptObservation {
    fun read(row: Int, normalize: Boolean = true): LabyrinthShopReceiptRead? {
        val top = row * LABYRINTH_SHOP_RECEIPT_COLLAGE_ROW_HEIGHT
        val bottom = top + LABYRINTH_SHOP_RECEIPT_COLLAGE_ROW_HEIGHT
        val hits = lines.filter { line ->
            line.rect?.let { rect -> rect.top + rect.height / 2 in top until bottom } == true
        }.sortedWith(compareBy({ it.rect!!.top }, { it.rect!!.left }))
        if (hits.isEmpty()) return null
        // 拼图逐行隔离后仍可能在端头带出零碎符号，取词时归一掉；标题逐字匹配，不做归一。
        val joined = hits.joinToString("") { it.text }
        val text = if (normalize) joined.trim { !it.isLetterOrDigit() } else joined
        if (text.isEmpty()) return null
        return LabyrinthShopReceiptRead(text, id, confirmations)
    }
    return LabyrinthShopPurchaseReceiptObservation(
        heading = read(0, normalize = false),
        expenditure = read(1),
        item = read(2),
        balanceBefore = read(3),
        balanceAfter = read(4),
        // 按钮只作诊断证据：弹窗身份由标题与确认矩形几何证明，不再靠这两个字兜底。
        button = read(LABYRINTH_SHOP_RECEIPT_BUTTON_ROW),
        confirmRect = confirmRect,
    )
}

/**
 * 回执读数状态。
 *
 * 两次独立读数在**解析后的事实**（标题文本、商品名、花费、前余额、后余额）上一致即复核通过：
 * 原始句子的前缀、标点差异不再清零计数。读取缓存本身不计数。调用方持有锁。
 */
internal class LabyrinthShopReceiptReadState(private val clock: () -> Long) {
    data class Request(val ticket: LabyrinthShopOcrEpoch.Ticket)
    private val requests = LabyrinthShopOcrEpoch()
    private var attempts = 0
    private var nextStart = 0L
    private var previousFacts: List<String?>? = null
    private var confirmations = 0
    private var verifiedAt = 0L
    var receipt: LabyrinthShopPurchaseReceiptObservation? = null
        private set

    fun clear() {
        requests.clear()
        receipt = null
        previousFacts = null
        confirmations = 0
        attempts = 0
        nextStart = 0
    }

    fun request(fingerprint: Long): Request? {
        if (requests.observe(fingerprint)) {
            receipt = null
            previousFacts = null
            confirmations = 0
            attempts = 0
            nextStart = 0
        }
        val now = clock()
        if (receipt?.verified == true && now - verifiedAt >= 5_000) {
            requests.clear()
            requests.observe(fingerprint)
            receipt = null
            previousFacts = null
            confirmations = 0
            attempts = (attempts / 4 + 1) * 4
            nextStart = now
        }
        if (now < nextStart || receipt?.verified == true) return null
        val ticket = requests.request() ?: return null
        nextStart = now + if (attempts < 4) 200 else 1_500
        attempts++
        return Request(ticket)
    }

    fun complete(
        request: Request,
        lines: List<LabyrinthShopOcrLine>?,
        confirmRect: EntryPixelRect?,
    ) {
        if (!requests.complete(request.ticket) || lines == null) return
        val parsed = shopReceiptFromCollageLines(lines, request.ticket.evidenceId, 1, confirmRect)
        val facts = listOf(parsed.heading?.text, parsed.itemName, parsed.spent?.toString(),
            parsed.before?.toString(), parsed.after?.toString())
        confirmations = if (previousFacts == facts) confirmations + 1 else 1
        previousFacts = facts
        receipt = shopReceiptFromCollageLines(lines, request.ticket.evidenceId, confirmations, confirmRect)
        if (receipt?.verified == true) verifiedAt = clock()
    }
}
