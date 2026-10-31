package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test fun `标题匹配购买完成或购买完毕并容忍两端噪声`() {
        assertTrue(labyrinthShopCompletionHeading(" 购买 完毕 "))
        assertTrue(labyrinthShopCompletionHeading("购买完成"))
        assertTrue(labyrinthShopCompletionHeading("|购买完毕"))
        assertTrue(labyrinthShopCompletionHeading("购买完毕|"))
        assertTrue(labyrinthShopCompletionHeading("|购买完成|"))
        listOf(null, "购买", "购买完成？", "确认购买", "购买完成苍珠手镯", "购买完畢", "|购买完毕？")
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

    @Test fun `拼图六行读数写进调试诊断且失败也带原因`() {
        val bad = collage().map { if (it.text == "购买完成") it.copy(text = "|购买完毕？") else it }
        val receipt = shopReceiptFromCollageLines(bad, 7, 2, confirm)
        val diagnostic = receipt.ocrDiagnostics("NOT_RECEIPT", shopReceiptCollageRowTexts(bad))
        assertEquals("NOT_RECEIPT", diagnostic.status)
        assertEquals("购买完成标题未核实", diagnostic.reviewReason)
        // 诊断保留归一前的原文：端头符号和句号都要能看见。
        assertEquals("|购买完毕？", diagnostic.heading)
        assertEquals("购买了苍珠手镯。", diagnostic.item)
        assertEquals("消耗阿尔法金币×100", diagnostic.expenditure)
        assertEquals("1000", diagnostic.balanceBefore)
        assertEquals("900", diagnostic.balanceAfter)
        assertEquals("确认", diagnostic.button)
        assertEquals(2, diagnostic.confirmations)
        // 没有原文时退回取词结果（已归一）。
        assertEquals("购买了苍珠手镯", receipt.ocrDiagnostics("NOT_RECEIPT").item)
    }

    @Test fun `调试诊断并排记录引擎读数`() {
        val paddle = LabyrinthShopReceiptPaddleRead(
            status = "ok",
            heading = "购买完毕",
            expenditure = "消耗阿尔法金币×100",
            item = "购买了苍珠手镯。",
            balanceBefore = "1000",
            balanceAfter = "900",
            button = "确认",
        )
        val diagnostic = shopReceiptFromCollageLines(collage(), 3, 2, confirm)
            .ocrDiagnostics("PURCHASE_COMPLETE", shopReceiptCollageRowTexts(collage()), paddle)
        assertEquals("PURCHASE_COMPLETE", diagnostic.status)
        assertEquals(paddle, diagnostic.paddle)
        assertNull(shopReceiptFromCollageLines(collage(), 3, 2, confirm)
            .ocrDiagnostics("PURCHASE_COMPLETE").paddle)
    }

    /** 按行序直接解析（PP-OCRv4 主读走这条）必须与按元素归行解析得到同样的事实。 */
    @Test fun `按行序解析与按元素归行解析等价`() {
        val lines = collage()
        val rows = shopReceiptCollageRowTexts(lines)
        val byRows = shopReceiptFromCollageRows(rows, 5, 2, confirm)
        val byLines = shopReceiptFromCollageLines(lines, 5, 2, confirm)
        assertEquals(byLines.heading?.text, byRows.heading?.text)
        assertEquals(byLines.itemName, byRows.itemName)
        assertEquals(byLines.spent, byRows.spent)
        assertEquals(byLines.before, byRows.before)
        assertEquals(byLines.after, byRows.after)
        assertTrue(byRows.verified)
    }

    /** 主读连续两帧读到同样事实才通过复核；这是「独立双读」的真实含义（同一引擎重复读）。 */
    @Test fun `主读按行序落地并累积复核次数`() {
        var now = 1_000L
        val state = LabyrinthShopReceiptReadState { now }
        val rows = shopReceiptCollageRowTexts(collage())
        val first = state.request(42L)
        assertNotNull(first)
        state.completeRows(first!!, rows, confirm)
        assertEquals(1, state.diagnostics()?.confirmations)
        now += 500
        val second = state.request(42L)
        assertNotNull(second)
        state.completeRows(second!!, rows, confirm)
        assertEquals(2, state.diagnostics()?.confirmations)
        assertEquals("PURCHASE_COMPLETE", state.readStatus.name)
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

    /** 实测 3,330 会被读成 3.330：分组形态下的 . 与 · 按千分位处理。 */
    @Test fun `千分位被读成句点或间隔号时仍按千分位解析`() {
        assertEquals(3330L, LabyrinthShopPurchaseReceiptObservation.parseAmount("3.330"))
        assertEquals(3330L, LabyrinthShopPurchaseReceiptObservation.parseAmount("3·330"))
        assertEquals(3330L, LabyrinthShopPurchaseReceiptObservation.parseAmount("3，330"))
        assertEquals(3330L, LabyrinthShopPurchaseReceiptObservation.parseAmount("3,330"))
        assertEquals(1234567L, LabyrinthShopPurchaseReceiptObservation.parseAmount("1.234.567"))
        assertEquals(3330L, LabyrinthShopPurchaseReceiptObservation.parseAmount("3330"))
    }

    /** 真正的小数不能因为上一条规则被悄悄取整。 */
    @Test fun `非千分位形态的点号仍解析失败`() {
        assertNull(LabyrinthShopPurchaseReceiptObservation.parseAmount("3.33"))
        assertNull(LabyrinthShopPurchaseReceiptObservation.parseAmount("3.3300"))
        assertNull(LabyrinthShopPurchaseReceiptObservation.parseAmount("3.3"))
        assertNull(LabyrinthShopPurchaseReceiptObservation.parseAmount("0.5"))
    }

    /**
     * `33.330` 本身就是合法的千分位写法（三万三千三百三十），按规则解析成 33330。
     * 这是有意取舍：游戏余额恒为整数并带千分位，不做小数猜测。
     */
    @Test fun `前两位加三位分组仍按千分位解析`() {
        assertEquals(33330L, LabyrinthShopPurchaseReceiptObservation.parseAmount("33.330"))
    }

    /** 花费句里的千分位同样容忍句点。 */
    @Test fun `花费金额的千分位被读成句点仍能核实`() {
        val dotSpent = collage().map {
            if (it.text == "消耗阿尔法金币×100") it.copy(text = "消耗阿尔法金币×1.820") else it
        }
        val withBalances = dotSpent.map {
            when (it.text) {
                "1000" -> it.copy(text = "3.330")
                "900" -> it.copy(text = "1.510")
                else -> it
            }
        }
        val parsed = shopReceiptFromCollageLines(withBalances, 1, 2, confirm)
        assertEquals(1820L, parsed.spent)
        assertEquals(3330L, parsed.before)
        assertEquals(1510L, parsed.after)
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
