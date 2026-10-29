package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拼图按行归属字段后的解析规则。 */
class LabyrinthShopReceiptParsingTest {
    private val confirm = EntryPixelRect(780, 710, 360, 65)

    @Test fun `拼图按行归位六块字段`() {
        val receipt = shopReceiptFromCollageLines(collage().reversed(), 5, 2, confirm)
        assertTrue(receipt.verified)
        assertEquals("苍珠手镯", receipt.itemName)
        assertEquals(100L, receipt.spent)
        assertEquals(1_000L, receipt.before)
        assertEquals(900L, receipt.after)
        assertEquals("确认", receipt.button?.text)
        assertEquals(confirm, receipt.confirmRect)
    }

    @Test fun `标题必须逐字匹配购买完成或购买完毕`() {
        assertTrue(labyrinthShopCompletionHeading(" 购买 完毕 "))
        assertTrue(labyrinthShopCompletionHeading("购买完成"))
        listOf(null, "购买", "购买完成？", "确认购买", "购买完成苍珠手镯", "购买完畢")
            .forEach { assertFalse(labyrinthShopCompletionHeading(it)) }
        val noisy = collage().map { if (it.text == "购买完成") it.copy(text = "购买完成？") else it }
        assertFalse(shopReceiptFromCollageLines(noisy, 5, 2, confirm).verified)
    }

    @Test fun `同一行的多段元素按位置拼回一个字段`() {
        val split = collage().filterNot { it.text == "消耗阿尔法金币×100" } + listOf(
            line("消耗阿尔法金币", 1, 10),
            line("×100", 1, 300),
        )
        val receipt = shopReceiptFromCollageLines(split.reversed(), 2, 2, confirm)
        assertTrue(receipt.verified)
        assertEquals("消耗阿尔法金币×100", receipt.expenditure?.text)
    }

    @Test fun `字段两端多出的非文字符号在取词时归一`() {
        val noisy = collage().map { line ->
            when (line.text) {
                "购买了苍珠手镯。" -> line.copy(text = "|购买了苍珠手镯。|")
                "1000" -> line.copy(text = "|1000")
                "900" -> line.copy(text = "900|")
                else -> line
            }
        }
        val receipt = shopReceiptFromCollageLines(noisy, 11, 2, confirm)
        assertTrue(receipt.verified)
        assertEquals("苍珠手镯", receipt.itemName)
        assertEquals(100L, receipt.spent)
        assertEquals(1_000L, receipt.before)
        assertEquals(900L, receipt.after)
    }

    @Test fun `确认按钮只作证据不参与核实`() {
        listOf("关闭", "确定", "确认").forEach { text ->
            val lines = collage().map { if (rowOf(it) == 5) it.copy(text = text) else it }
            assertTrue(text, shopReceiptFromCollageLines(lines, 3, 2, confirm).verified)
        }
        val withoutButton = collage().filterNot { rowOf(it) == 5 }
        assertTrue(shopReceiptFromCollageLines(withoutButton, 3, 2, confirm).verified)
    }

    @Test fun `确认矩形缺失仍拒绝`() {
        assertFalse(shopReceiptFromCollageLines(collage(), 1, 2, null).verified)
    }

    @Test fun `金额或差额不符拒绝`() {
        val badAfter = collage().map { if (it.text == "900") it.copy(text = "901") else it }
        assertFalse(shopReceiptFromCollageLines(badAfter, 1, 2, confirm).verified)
        val badDigits = collage().map { if (it.text == "1000") it.copy(text = "1O00") else it }
        assertFalse(shopReceiptFromCollageLines(badDigits, 1, 2, confirm).verified)
        val zeroSpent = collage().map { if (it.text == "消耗阿尔法金币×100") it.copy(text = "消耗阿尔法金币×0") else it }
        assertFalse(shopReceiptFromCollageLines(zeroSpent, 1, 2, confirm).verified)
    }

    @Test fun `缺任一数据行即视为未解析`() {
        listOf(0, 1, 2, 3, 4).forEach { row ->
            val lines = collage().filterNot { rowOf(it) == row }
            assertFalse("row=$row", shopReceiptFromCollageLines(lines, 1, 2, confirm).verified)
        }
    }

    private fun rowOf(line: LabyrinthShopOcrLine): Int =
        line.rect!!.top / LABYRINTH_SHOP_RECEIPT_COLLAGE_ROW_HEIGHT

    private fun line(text: String, row: Int, left: Int = 20) =
        LabyrinthShopOcrLine(text, EntryPixelRect(left, row * LABYRINTH_SHOP_RECEIPT_COLLAGE_ROW_HEIGHT + 40, 600, 60))

    private fun collage(item: String = "购买了苍珠手镯。") = listOf(
        line("购买完成", 0),
        line("消耗阿尔法金币×100", 1),
        line(item, 2),
        line("1000", 3),
        line("900", 4),
        line("确认", 5),
    )
}
