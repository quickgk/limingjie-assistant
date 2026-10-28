package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthShopReceiptParsingTest {
    private val confirm = EntryPixelRect(780, 710, 360, 65)

    @Test fun `回执六字段各自定位并验证确认矩形`() {
        val receipt = shopReceiptFromLines(lines().reversed(), 5, 2, confirm)
        assertTrue(receipt.verified)
        assertEquals("苍珠手镯", receipt.itemName)
        assertEquals(100L, receipt.spent)
        assertEquals(1_000L, receipt.before)
        assertEquals(900L, receipt.after)
        assertEquals(confirm, receipt.confirmRect)
        assertEquals(5L, receipt.button?.evidenceId)
    }

    @Test fun `标题必须完整匹配完成或完毕不能使用外框猜测`() {
        assertTrue(labyrinthShopCompletionHeading(" 购买 完毕 "))
        assertTrue(labyrinthShopCompletionHeading("购买完成"))
        listOf(null, "购买", "购买完成？", "确认购买", "购买完成苍珠手镯", "购买完畢")
            .forEach { assertFalse(labyrinthShopCompletionHeading(it)) }
        assertFalse(shopReceiptFromLines(lines().map { if (it.text == "购买完成") it.copy(text = "商店") else it }, 5, 2, confirm).verified)
    }

    @Test fun `底层钱包和无框文字绝不能冒充回执字段`() {
        val withoutBefore = lines().filterNot { it.text == "1,000" } +
            LabyrinthShopOcrLine("1,000", EntryPixelRect(1260, 940, 150, 64)) + LabyrinthShopOcrLine("1,000", null)
        val receipt = shopReceiptFromLines(withoutBefore, 1, 2, confirm)
        assertNull(receipt.balanceBefore)
        assertFalse(receipt.verified)
    }

    @Test fun `商品与花费ROI错位以及左右余额串框都拒绝`() {
        val misplaced = lines().map { line -> when (line.text) {
            "购买了苍珠手镯。" -> line.copy(rect = EntryPixelRect(650, 390, 250, 30))
            "消耗阿尔法金币×100" -> line.copy(rect = EntryPixelRect(600, 445, 300, 30))
            else -> line
        } }
        assertFalse(shopReceiptFromLines(misplaced, 1, 2, confirm).verified)
        val crossing = lines().filterNot { it.text == "1,000" || it.text == "900" } +
            LabyrinthShopOcrLine("1,000900", EntryPixelRect(1000, 590, 340, 30))
        val receipt = shopReceiptFromLines(crossing, 1, 2, confirm)
        assertNull(receipt.balanceBefore)
        assertNull(receipt.balanceAfter)
        assertFalse(receipt.verified)
    }

    @Test fun `确认文字必须位于实际按钮且点击矩形必须匹配映射`() {
        assertFalse(shopReceiptFromLines(lines(), 1, 2, null).verified)
        val wrongRect = EntryPixelRect(980, 706, 400, 80)
        assertNull(shopReceiptFromLines(lines(), 1, 2, wrongRect).confirmRect)
        val outside = lines().map { if (it.text == "确认") it.copy(rect = EntryPixelRect(900, 700, 80, 8)) else it }
        assertNull(shopReceiptFromLines(outside, 1, 2, confirm).confirmRect)
        val wrongText = lines().map { if (it.text == "确认") it.copy(text = "关闭") else it }
        assertFalse(shopReceiptFromLines(wrongText, 1, 2, confirm).verified)
        val backgroundButton = lines().map { if (it.text == "确认") it.copy(rect = EntryPixelRect(1430, 930, 80, 30)) else it }
        assertFalse(shopReceiptFromLines(backgroundButton, 1, 2, confirm).verified)
    }

    @Test fun `缩放与宽屏使用FIT映射按钮而文本仍为参考坐标`() {
        listOf(960 to 540, 2780 to 1264).forEach { (width, height) ->
            val mapped = ReferenceFitMapper.map(width, height, EntryReferenceSize(1920, 1080), LABYRINTH_SHOP_RECEIPT_CONFIRM_RECT)
            val receipt = shopReceiptFromLines(lines(), 1, 2, mapped, width, height)
            assertTrue(receipt.verified)
            assertEquals(mapped, receipt.confirmRect)
            assertFalse(shopReceiptFromLines(lines(), 1, 2, confirm, width, height).verified)
        }
    }

    @Test fun `元素框分割仍按位置恢复花费和余额`() {
        val split = lines().filterNot { it.text == "消耗阿尔法金币×100" || it.text == "确认" } + listOf(
            LabyrinthShopOcrLine("消耗阿尔法", EntryPixelRect(650, 390, 140, 30)),
            LabyrinthShopOcrLine("金币×100", EntryPixelRect(795, 390, 130, 30)),
            LabyrinthShopOcrLine("确", EntryPixelRect(900, 725, 35, 30)),
            LabyrinthShopOcrLine("认", EntryPixelRect(940, 725, 35, 30)),
        )
        assertTrue(shopReceiptFromLines(split.reversed(), 2, 2, confirm).verified)
    }

    private fun lines() = listOf(
        LabyrinthShopOcrLine("购买完成", EntryPixelRect(850, 270, 200, 40)),
        LabyrinthShopOcrLine("消耗阿尔法金币×100", EntryPixelRect(650, 390, 300, 30)),
        LabyrinthShopOcrLine("购买了苍珠手镯。", EntryPixelRect(600, 445, 300, 30)),
        LabyrinthShopOcrLine("1,000", EntryPixelRect(1000, 590, 100, 35)),
        LabyrinthShopOcrLine("900", EntryPixelRect(1270, 590, 100, 35)),
        LabyrinthShopOcrLine("确认", EntryPixelRect(900, 725, 80, 30)),
    )
}
