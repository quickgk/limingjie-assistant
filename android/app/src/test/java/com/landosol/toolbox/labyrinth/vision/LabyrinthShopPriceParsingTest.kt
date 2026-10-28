package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabyrinthShopPriceParsingTest {
    @Test fun `五格拼图准确区分商品刷新价格与钱包`() {
        val lines = listOf(line(0, "1,234"), line(1, "567"), line(2, "999，999"), line(3, "用10更新"), line(4, "0"))
        assertEquals(mapOf("shop_item_1" to "1234", "shop_item_2" to "567", "shop_item_3" to "999999",
            "refresh" to "10", "wallet" to "0"), labyrinthShopPricesFromCollage(lines.reversed()))
    }

    @Test fun `商品及刷新费必须正数且拼图金额限制为六位`() {
        listOf("0", "-1", "1O0", "1,23", "1000000", "１２３", "100金币").forEach { text ->
            val values = labyrinthShopPricesFromCollage(listOf(line(0, text), line(3, text)))
            assertNull(text, values["shop_item_1"])
            assertNull(text, values["refresh"])
        }
        assertEquals("999999", labyrinthShopPricesFromCollage(listOf(line(4, "999999")))["wallet"])
        assertNull(labyrinthShopPricesFromCollage(listOf(line(4, "1000000")))["wallet"])
        assertNull(labyrinthShopPricesFromCollage(listOf(line(3, "用10刷新")))["refresh"])
    }

    @Test fun `跨格及越界元素框不按中心点猜字段`() {
        val values = labyrinthShopPricesFromCollage(listOf(
            LabyrinthShopOcrLine("100", EntryPixelRect(490, 20, 30, 30)),
            LabyrinthShopOcrLine("200", EntryPixelRect(20, 170, 100, 30)),
            LabyrinthShopOcrLine("300", EntryPixelRect(1050, 230, 100, 30)),
            LabyrinthShopOcrLine("400", null),
        ))
        assertEquals(LABYRINTH_SHOP_PRICE_KEYS.toSet(), values.keys)
        values.forEach { (key, value) -> assertNull(key, value) }
    }

    @Test fun `分割千分位元素仅在所属格子里拼接`() {
        val values = labyrinthShopPricesFromCollage(listOf(
            LabyrinthShopOcrLine("1,", EntryPixelRect(25, 25, 30, 30)),
            LabyrinthShopOcrLine("234", EntryPixelRect(60, 25, 90, 30)),
            line(1, "200"), line(4, "5,000"),
        ))
        assertEquals("1234", values["shop_item_1"])
        assertEquals("200", values["shop_item_2"])
        assertEquals("5000", values["wallet"])
        assertNull(values["shop_item_3"])
    }

    private fun line(index: Int, text: String) = LabyrinthShopOcrLine(text,
        EntryPixelRect(index % 3 * 500 + 25, index / 3 * 180 + 25, 200, 40))
}
