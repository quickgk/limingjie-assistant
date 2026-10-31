package com.landosol.toolbox.labyrinth.vision

/**
 * 回执六行的读数与判定，只进调试状态，不参与任何决策。
 *
 * 回执没读成或还在读时同样记录：现场报告里要能直接看到「哪个字段读成了什么、为什么没通过」。
 */
data class LabyrinthShopReceiptOcrDiagnostics(
    val status: String,
    val reviewReason: String? = null,
    val heading: String? = null,
    val item: String? = null,
    val expenditure: String? = null,
    val balanceBefore: String? = null,
    val balanceAfter: String? = null,
    val button: String? = null,
    val confirmations: Int? = null,
    /** PP-OCRv4 读数；它是主读，顶层字段就是它的结果，这里保留便于按引擎对账。 */
    val paddle: LabyrinthShopReceiptPaddleRead? = null,
    /** 影子读数：ML Kit 的结果，只用于对比，不参与判定。 */
    val mlkit: LabyrinthShopReceiptPaddleRead? = null,
    /** 当前主读引擎名，便于报告里一眼看出判定出自谁。 */
    val primaryEngine: String? = null,
)

/** 某一识别引擎对同一批回执字段的读数。 */
data class LabyrinthShopReceiptPaddleRead(
    val status: String,
    val model: String? = null,
    val heading: String? = null,
    val expenditure: String? = null,
    val item: String? = null,
    val balanceBefore: String? = null,
    val balanceAfter: String? = null,
    val button: String? = null,
)

internal fun LabyrinthShopPurchaseReceiptObservation.ocrDiagnostics(
    status: String,
    rawRows: List<String?>? = null,
    paddle: LabyrinthShopReceiptPaddleRead? = null,
    mlkit: LabyrinthShopReceiptPaddleRead? = null,
    primaryEngine: String? = null,
): LabyrinthShopReceiptOcrDiagnostics = LabyrinthShopReceiptOcrDiagnostics(
    status = status,
    reviewReason = reviewReason,
    // 诊断存归一前的原文：端头噪声正是要排查的东西，归一后就看不见了。
    heading = rawRows?.getOrNull(0) ?: heading?.text,
    expenditure = rawRows?.getOrNull(1) ?: expenditure?.text,
    item = rawRows?.getOrNull(2) ?: item?.text,
    balanceBefore = rawRows?.getOrNull(3) ?: balanceBefore?.text,
    balanceAfter = rawRows?.getOrNull(4) ?: balanceAfter?.text,
    button = rawRows?.getOrNull(5) ?: button?.text,
    confirmations = listOfNotNull(heading, item, expenditure, balanceBefore, balanceAfter, button)
        .maxOfOrNull { it.confirmations },
    paddle = paddle,
    mlkit = mlkit,
    primaryEngine = primaryEngine,
)

/** 字段证据仅在实际OCR回调完成时计数，重复消费缓存不会增加复核次数。 */
data class LabyrinthShopReceiptRead(
    val text: String?,
    val evidenceId: Long,
    val confirmations: Int = 1,
)

data class LabyrinthShopPurchaseReceiptObservation(
    val heading: LabyrinthShopReceiptRead? = null,
    val item: LabyrinthShopReceiptRead? = null,
    val expenditure: LabyrinthShopReceiptRead? = null,
    val balanceBefore: LabyrinthShopReceiptRead? = null,
    val balanceAfter: LabyrinthShopReceiptRead? = null,
    val button: LabyrinthShopReceiptRead? = null,
    val confirmRect: EntryPixelRect? = null,
) {
    val titleConfirmed: Boolean get() = labyrinthShopCompletionHeading(heading?.text)
    val itemName: String? get() = item?.text?.filterNot(Char::isWhitespace)
        // 回执裁图左边缘会带出杂散字符（实测 ML Kit 读 `|购买了破守帽退散。`、Paddle 读 `“购买了…`），
        // 正则要求以「购买了」开头，不先去掉就会整条解析失败。与拼图路径 read(row) 的归一同一口径。
        ?.trim { !it.isLetterOrDigit() }
        ?.let { Regex("^购买了(.+?)[。.]?$").matchEntire(it)?.groupValues?.get(1) }
        ?.takeIf { it.length in 2..80 }
    val spent: Long? get() = expenditure?.text?.filterNot(Char::isWhitespace)?.let {
        // 金→全仅限完整花费句；金额本身不做字符猜测，且必须与余额差额一致。
        Regex("^消耗阿[尔爾]法[金全]币[×xX*](\\d{1,3}(?:[,，.·]\\d{3})+|\\d+)$")
            .matchEntire(it)?.groupValues?.get(1)?.let(::parseAmount)
    }
    val before: Long? get() = balanceBefore?.text?.let(::parseAmount)
    val after: Long? get() = balanceAfter?.text?.let(::parseAmount)
    /** 参与复核的只有标题与四项交易事实；确认按钮由弹窗标题和确认矩形几何证明，不靠 OCR 认字。 */
    val independentReadsConfirmed: Boolean get() =
        listOf(heading, item, expenditure, balanceBefore, balanceAfter)
            .all { it != null && it.confirmations >= 2 }
    val verified: Boolean get() = reviewReason == null
    val reviewReason: String? get() = when {
        !titleConfirmed -> "购买完成标题未核实"
        !independentReadsConfirmed -> "等待各区域独立OCR复核"
        itemName == null -> "购买商品名称无法可靠解析"
        spent == null || before == null || after == null -> "实际花费或前后余额无法可靠解析"
        spent!! <= 0 || before!! < spent!! || before!! - spent!! != after -> "购买花费与余额差额不一致"
        confirmRect == null -> "回执确认按钮未核实"
        else -> null
    }

    companion object {
        fun parseAmount(text: String): Long? {
            val value = text.filterNot(Char::isWhitespace)
            // 千分位除了 , ，还可能被 OCR 读成 . 或 ·（实测 3,330 会读成 3.330）。
            // 只有在「1~3 位 + 恰好 3 位一组」的分组形态下才把 . · 当分隔符，
            // 这样 3.33 这类真正的小数仍然解析失败，不会被悄悄当成 333。
            if (!Regex("(?:[0-9]{1,3}(?:[,，.·][0-9]{3})+|[0-9]+)").matches(value)) return null
            return value.replace(",", "").replace("，", "").replace(".", "").replace("·", "")
                .toLongOrNull()
                ?.takeIf { it <= 999_999_999 }
        }
    }
}

/** 钱包的纯模型；Android依赖仅存在于适配器中。 */
data class LabyrinthCurrencyObservation(
    val read: LabyrinthShopReceiptRead? = null,
) {
    val balance: Long? get() = read?.takeIf { it.confirmations >= 2 }?.text
        ?.let(LabyrinthShopPurchaseReceiptObservation::parseAmount)
}
