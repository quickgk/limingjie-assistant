package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthShopAmountParsingTest {
    @Test fun `金额只接受完整十进制及严格千分位格式`() {
        mapOf("0" to 0L, "001" to 1L, "999999999" to 999_999_999L,
            "999,999,999" to 999_999_999L, "123，456" to 123_456L, " 1,234 \n" to 1_234L)
            .forEach { (text, amount) -> assertEquals(text, amount, LabyrinthShopPurchaseReceiptObservation.parseAmount(text)) }
        listOf("", " ", "-1", "+1", "1.0", "1e3", "1万", "余额100", "100币", "1O0", "１２３",
            "1,23", "1234,567", "1,,234", ",123", "123,", "1000000000", "9223372036854775808")
            .forEach { assertNull(it, LabyrinthShopPurchaseReceiptObservation.parseAmount(it)) }
    }

    @Test fun `实际花费只解析完整句且不猜数字`() {
        listOf("消耗阿尔法金币×1,234", "消耗阿爾法全币x1234", "消耗 阿尔法金币 X 1，234", "消耗阿尔法金币*1234")
            .forEach { text -> assertEquals(text, 1_234L, LabyrinthShopPurchaseReceiptObservation(expenditure = read(text)).spent) }
        listOf("消耗阿尔法金币×1O0", "消耗金币×100", "消耗阿尔法金币×1,23", "消耗阿尔法金币×-100",
            "消耗阿尔法金币×100。", "阿尔法金币×100", "消耗阿尔法金币×1000000000")
            .forEach { assertNull(it, LabyrinthShopPurchaseReceiptObservation(expenditure = read(it)).spent) }
    }

    @Test fun `回执复核还必须满足实际花费余额差额`() {
        val receipt = validReceipt()
        assertTrue(receipt.verified)
        assertEquals("苍珠手镯", receipt.itemName)
        assertEquals(1_000L, receipt.spent)
        assertEquals(0L, receipt.after)
        assertFalse(receipt.copy(balanceBefore = read("999")).verified)
        assertFalse(receipt.copy(balanceAfter = read("1")).verified)
        assertFalse(receipt.copy(expenditure = read("消耗阿尔法金币×0")).verified)
        assertFalse(receipt.copy(balanceAfter = read("-1")).verified)
        assertFalse(receipt.copy(item = read("苍珠手镯")).verified)
        assertFalse(receipt.copy(item = read("购买了杖")).verified)
        assertFalse(receipt.copy(heading = read("购买了苍珠手镯")).verified)
        assertFalse(receipt.copy(confirmRect = null).verified)
    }

    @Test fun `任一回执字段只有一次读取都不算完整复核`() {
        val receipt = validReceipt()
        listOf(receipt.copy(heading = read("购买完成", 1)), receipt.copy(item = read("购买了苍珠手镯。", 1)),
            receipt.copy(expenditure = read("消耗阿尔法金币×1000", 1)), receipt.copy(balanceBefore = read("1000", 1)),
            receipt.copy(balanceAfter = read("0", 1)))
            .forEach { assertFalse(it.independentReadsConfirmed); assertFalse(it.verified) }
        assertFalse(LabyrinthShopPurchaseReceiptObservation().verified)
    }

    @Test fun `商品和刷新费的双读价格以及总复核状态保持独立`() {
        val item = item().copy(priceRead = read("100", 1))
        assertNull(item.verifiedPrice)
        val verified = item.copy(priceRead = read("100"))
        assertEquals(100L, verified.verifiedPrice)
        assertNull(item.copy(priceRead = read("0")).verifiedPrice)
        assertNull(item.copy(priceRead = read("1O0")).verifiedPrice)
        val shop = LabyrinthShopObservation(listOf(verified), refreshPriceRead = read("10"))
        assertTrue(shop.pricesReviewed)
        assertEquals(10L, shop.verifiedRefreshPrice)
        assertFalse(shop.copy(items = listOf(item)).pricesReviewed)
        assertFalse(shop.copy(refreshPriceRead = read("10", 1)).pricesReviewed)
        assertFalse(shop.copy(priceReviewReason = "等待复核").pricesReviewed)
        assertFalse(shop.copy(items = emptyList()).pricesReviewed)
    }

    @Test fun `钱包允许零余额且拒绝超上限`() {
        assertNull(LabyrinthCurrencyObservation(read("100", 1)).balance)
        assertEquals(0L, LabyrinthCurrencyObservation(read("0")).balance)
        val counts = LabyrinthCurrencyObservation(read("999,999,999"))
        assertEquals(999_999_999L, counts.balance)
        assertNull(counts.copy(read = read("1000000000")).balance)
    }

    private fun read(text: String, confirmations: Int = 2) = LabyrinthShopReceiptRead(text, 7, confirmations)
    private fun item() = LabyrinthShopItemMatch("shop_item_1", EntryPixelRect(0, 0, 10, 10),
        EntryPixelRect(0, 0, 10, 10), 0.9, status = LabyrinthShopItemStatus.AVAILABLE)
    private fun validReceipt() = LabyrinthShopPurchaseReceiptObservation(read("购买完成"), read("购买了苍珠手镯。"),
        read("消耗阿尔法金币×1000"), read("1000"), read("0"), read("确认"), EntryPixelRect(780, 710, 360, 65))
}
