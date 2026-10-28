package com.landosol.toolbox.labyrinth.vision

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
        ?.let { Regex("^购买了(.+?)[。.]?$").matchEntire(it)?.groupValues?.get(1) }
        ?.takeIf { it.length in 2..80 }
    val spent: Long? get() = expenditure?.text?.filterNot(Char::isWhitespace)?.let {
        // 金→全仅限完整花费句；金额本身不做字符猜测，且必须与余额差额一致。
        Regex("^消耗阿[尔爾]法[金全]币[×xX*](\\d{1,3}(?:[,，]\\d{3})+|\\d+)$")
            .matchEntire(it)?.groupValues?.get(1)?.let(::parseAmount)
    }
    val before: Long? get() = balanceBefore?.text?.let(::parseAmount)
    val after: Long? get() = balanceAfter?.text?.let(::parseAmount)
    val independentReadsConfirmed: Boolean get() =
        listOf(heading, item, expenditure, balanceBefore, balanceAfter, button)
            .all { it != null && it.confirmations >= 2 }
    val verified: Boolean get() = reviewReason == null
    val reviewReason: String? get() = when {
        !titleConfirmed -> "购买完成标题未核实"
        !independentReadsConfirmed -> "等待各区域独立OCR复核"
        itemName == null -> "购买商品名称无法可靠解析"
        spent == null || before == null || after == null -> "实际花费或前后余额无法可靠解析"
        spent!! <= 0 || before!! < spent!! || before!! - spent!! != after -> "购买花费与余额差额不一致"
        button?.text?.filterNot(Char::isWhitespace) != "确认" || confirmRect == null -> "回执确认按钮未核实"
        else -> null
    }

    companion object {
        fun parseAmount(text: String): Long? {
            val value = text.filterNot(Char::isWhitespace)
            if (!Regex("(?:[0-9]{1,3}(?:[,，][0-9]{3})+|[0-9]+)").matches(value)) return null
            return value.replace(",", "").replace("，", "").toLongOrNull()
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
