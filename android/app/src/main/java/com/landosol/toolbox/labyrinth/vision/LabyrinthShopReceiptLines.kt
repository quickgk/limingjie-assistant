package com.landosol.toolbox.labyrinth.vision

/**
 * 回执各字段在 1920×1080 参考坐标里的位置。
 *
 * 主读按这些矩形逐块裁剪交给 PP-OCRv4；未配置 Paddle 引擎时退回拼图路径：把这几块分别裁出来、
 * 放大后排进一张白底拼图（六行，行高固定），再整图 OCR 一次，每个字段只面对自己那一行文字，
 * 不再被模态弹窗的蓝底标题、金色边框、圆点分隔线干扰。
 * 顺序即字段与拼图的行序：0 标题、1 花费、2 商品、3 前余额、4 后余额、5 确认按钮。
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

/** 影子引擎在诊断里的标识名；它不参与判定，只用于和主读对账。 */
internal const val MLKIT_SHADOW_MODEL = "mlkit_text_recognition_v2"

/** 确认按钮在参考坐标里的位置；点击矩形不依赖 OCR 结果。 */
internal val LABYRINTH_SHOP_RECEIPT_CONFIRM_RECT = EntryReferenceRect(780, 710, 360, 65)

/** 拼图六行各自的原始读数（未归一），按行序排列；空行给 null。 */
internal fun shopReceiptCollageRowTexts(lines: List<LabyrinthShopOcrLine>): List<String?> =
    LABYRINTH_SHOP_RECEIPT_COLLAGE_FIELDS.indices.map { row ->
        val top = row * LABYRINTH_SHOP_RECEIPT_COLLAGE_ROW_HEIGHT
        val bottom = top + LABYRINTH_SHOP_RECEIPT_COLLAGE_ROW_HEIGHT
        lines.filter { line ->
            line.rect?.let { rect -> rect.top + rect.height / 2 in top until bottom } == true
        }.sortedWith(compareBy({ it.rect!!.top }, { it.rect!!.left }))
            .joinToString("") { it.text }
            .takeIf { it.isNotEmpty() }
    }

/** 解析一次拼图 OCR 的结果；行序见 [LABYRINTH_SHOP_RECEIPT_COLLAGE_FIELDS]。 */
internal fun shopReceiptFromCollageLines(
    lines: List<LabyrinthShopOcrLine>,
    id: Long,
    confirmations: Int,
    confirmRect: EntryPixelRect?,
): LabyrinthShopPurchaseReceiptObservation =
    shopReceiptFromCollageRows(shopReceiptCollageRowTexts(lines), id, confirmations, confirmRect)

/**
 * 同 [shopReceiptFromCollageLines]，但直接接收**按行序排好的六行文本**。
 *
 * PP-OCRv4 主读按参考矩形逐块裁剪、逐块识别，天然就是行序结果，不需要再按元素位置归行。
 */
internal fun shopReceiptFromCollageRows(
    rows: List<String?>,
    id: Long,
    confirmations: Int,
    confirmRect: EntryPixelRect?,
): LabyrinthShopPurchaseReceiptObservation {
    fun read(row: Int, normalize: Boolean = true): LabyrinthShopReceiptRead? {
        val joined = rows.getOrNull(row) ?: return null
        // 逐行取词后仍可能在端头带出零碎符号，取词时归一掉；标题逐字匹配，不做归一。
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
internal val LabyrinthShopReceiptReadState.readStatus: LabyrinthShopReceiptReadStatus
    get() = when {
        receipt == null -> LabyrinthShopReceiptReadStatus.READING
        receipt?.titleConfirmed == true -> LabyrinthShopReceiptReadStatus.PURCHASE_COMPLETE
        else -> LabyrinthShopReceiptReadStatus.NOT_RECEIPT
    }

internal class LabyrinthShopReceiptReadState(private val clock: () -> Long) {
    data class Request(val ticket: LabyrinthShopOcrEpoch.Ticket)
    private val requests = LabyrinthShopOcrEpoch()
    private var attempts = 0
    private var nextStart = 0L
    private var previousFacts: List<String?>? = null
    private var confirmations = 0
    private var verifiedAt = 0L
    private var rawRows: List<String?>? = null
    private var paddleStatus: String? = null
    private var paddleModel: String? = null
    private var paddleRows: List<String?>? = null
    /** 影子读数：ML Kit 的结果，只用于和主读对比，不参与判定。 */
    private var mlkitRows: List<String?>? = null
    /** 当前主读引擎名；由解析器在切换引擎时写入，只为诊断。 */
    private var primaryEngineName: String? = null
    var receipt: LabyrinthShopPurchaseReceiptObservation? = null
        private set

    fun clear() {
        requests.clear()
        receipt = null
        previousFacts = null
        rawRows = null
        paddleStatus = null
        paddleModel = null
        paddleRows = null
        mlkitRows = null
        primaryEngineName = null
        confirmations = 0
        attempts = 0
        nextStart = 0
    }

    /** 主读读数已提交，结果还没回来。 */
    fun markPaddleRunning(model: String?) {
        paddleStatus = "running"
        paddleModel = model
        paddleRows = null
    }

    /** 主读读数落地；全空说明引擎不可用或这一帧没读出来。 */
    fun setPaddle(rows: List<String?>) {
        paddleRows = rows
        paddleStatus = if (rows.all { it == null }) "unavailable" else "ok"
    }

    /** 影子引擎（ML Kit）的读数，只用于对比，不参与判定。 */
    fun setMlKit(rows: List<String?>) {
        mlkitRows = rows
    }

    /** 记录当前主读引擎，只影响诊断输出。 */
    fun setPrimaryEngine(name: String?) {
        primaryEngineName = name
    }

    fun request(fingerprint: Long): Request? {
        if (requests.observe(fingerprint)) {
            receipt = null
            previousFacts = null
            rawRows = null
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

    /** 六行读数与判定；一次都没读过时返回 null，避免给无关页面塞无意义的 READING。 */
    fun diagnostics(): LabyrinthShopReceiptOcrDiagnostics? {
        if (receipt == null && attempts == 0) return null
        val status = readStatus
        val paddle = paddleStatus?.let { value ->
            LabyrinthShopReceiptPaddleRead(
                status = value,
                model = paddleModel,
                heading = paddleRows?.getOrNull(0),
                expenditure = paddleRows?.getOrNull(1),
                item = paddleRows?.getOrNull(2),
                balanceBefore = paddleRows?.getOrNull(3),
                balanceAfter = paddleRows?.getOrNull(4),
                button = paddleRows?.getOrNull(5),
            )
        }
        val mlkit = mlkitRows?.let { rows ->
            LabyrinthShopReceiptPaddleRead(
                status = if (rows.all { it == null }) "unavailable" else "ok",
                model = MLKIT_SHADOW_MODEL,
                heading = rows.getOrNull(0),
                expenditure = rows.getOrNull(1),
                item = rows.getOrNull(2),
                balanceBefore = rows.getOrNull(3),
                balanceAfter = rows.getOrNull(4),
                button = rows.getOrNull(5),
            )
        }
        return receipt?.ocrDiagnostics(status.name, rawRows, paddle, mlkit, primaryEngineName)
            ?: LabyrinthShopReceiptOcrDiagnostics(
                status.name, paddle = paddle, mlkit = mlkit, primaryEngine = primaryEngineName,
            )
    }

    fun complete(
        request: Request,
        lines: List<LabyrinthShopOcrLine>?,
        confirmRect: EntryPixelRect?,
    ) = completeRows(request, lines?.let(::shopReceiptCollageRowTexts), confirmRect)

    /** 主读结果落地。行序固定为 [LABYRINTH_SHOP_RECEIPT_COLLAGE_FIELDS] 的顺序。 */
    fun completeRows(
        request: Request,
        rows: List<String?>?,
        confirmRect: EntryPixelRect?,
    ) {
        if (!requests.complete(request.ticket) || rows == null) return
        val parsed = shopReceiptFromCollageRows(rows, request.ticket.evidenceId, 1, confirmRect)
        val facts = listOf(parsed.heading?.text, parsed.itemName, parsed.spent?.toString(),
            parsed.before?.toString(), parsed.after?.toString())
        confirmations = if (previousFacts == facts) confirmations + 1 else 1
        previousFacts = facts
        receipt = shopReceiptFromCollageRows(rows, request.ticket.evidenceId, confirmations, confirmRect)
        rawRows = rows
        if (receipt?.verified == true) verifiedAt = clock()
    }
}
