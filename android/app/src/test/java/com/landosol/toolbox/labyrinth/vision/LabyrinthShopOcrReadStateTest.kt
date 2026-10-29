package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthShopOcrReadStateTest {
    private val confirm = EntryPixelRect(780, 710, 360, 65)

    @Test fun `价格等待真正第二次回调且缓存读取不增加计数`() {
        var now = 0L
        val state = LabyrinthShopPriceReadState { now }
        val first = requireNotNull(state.request(11))
        assertNull(state.request(11))
        assertTrue(state.reads.isEmpty())
        state.complete(first, prices())
        repeat(20) { assertEquals(1, state.reads["wallet"]?.confirmations) }
        state.complete(first, prices())
        assertEquals(1, state.reads["wallet"]?.confirmations)
        assertNull(state.request(11))
        now = 300
        val second = requireNotNull(state.request(11))
        assertNotEquals(first.evidenceId, second.evidenceId)
        assertEquals(1, state.reads["wallet"]?.confirmations)
        state.complete(second, prices())
        state.reads.values.forEach { assertEquals(2, it.confirmations) }
        now = 600
        assertNull(state.request(11))
        assertNull(state.reviewReason)
    }

    @Test fun `价格OCR漂移只重置漂移字段且允许第三次独立复核`() {
        var now = 0L
        val state = LabyrinthShopPriceReadState { now }
        state.complete(requireNotNull(state.request(11)), prices())
        now = 300
        state.complete(requireNotNull(state.request(11)), prices("101"))
        assertEquals(1, state.reads["shop_item_1"]?.confirmations)
        assertEquals(2, state.reads["shop_item_2"]?.confirmations)
        now = 600
        state.complete(requireNotNull(state.request(11)), prices("101"))
        assertEquals(2, state.reads["shop_item_1"]?.confirmations)
        assertEquals("101", state.reads["shop_item_1"]?.text)
    }

    @Test fun `价格旧epoch结果丢弃且旧请求未完成时不能积累作业`() {
        var now = 0L
        val state = LabyrinthShopPriceReadState { now }
        val old = requireNotNull(state.request(11))
        now = 300
        assertNull(state.request(22))
        assertTrue(state.reads.isEmpty())
        state.complete(old, prices())
        assertTrue(state.reads.isEmpty())
        val current = requireNotNull(state.request(22))
        state.complete(old, prices())
        assertNull(state.request(22))
        state.complete(current, prices("200"))
        assertEquals("200", state.reads["shop_item_1"]?.text)
        assertEquals(1, state.reads["shop_item_1"]?.confirmations)
        state.clear()
        val next = requireNotNull(state.request(22))
        state.clear()
        assertNull(state.request(22))
        state.complete(next, prices())
        assertTrue(state.reads.isEmpty())
        assertNotNull(state.request(22))
    }

    @Test fun `价格失效指纹即使回到旧图也不能接受旧epoch回调`() {
        val state = LabyrinthShopPriceReadState { 0 }
        val first = requireNotNull(state.request(11))
        state.request(22)
        state.request(11)
        state.complete(first, prices())
        assertTrue(state.reads.isEmpty())
        assertNotNull(state.request(11))
    }

    @Test fun `无效金额不会凭两次相同空结果复核且六次后自动重读`() {
        var now = 0L
        val state = LabyrinthShopPriceReadState { now }
        repeat(6) { index ->
            val ticket = requireNotNull(state.request(11))
            state.complete(ticket, prices("1O0"))
            assertEquals(1, state.reads["shop_item_1"]?.confirmations)
            if (index < 5) now += 300
        }
        assertNotNull(state.reviewReason)
        now += 2_999
        assertNull(state.request(11))
        now++
        val recovered = requireNotNull(state.request(11))
        assertTrue(state.reads.isEmpty())
        assertNull(state.reviewReason)
        state.complete(recovered, prices())
        assertEquals(1, state.reads["shop_item_1"]?.confirmations)
    }

    @Test fun `回执只有事实一致的第二次实际回调才能通过`() {
        var now = 0L
        val state = LabyrinthShopReceiptReadState { now }
        val first = requireNotNull(state.request(11))
        assertNull(state.receipt)
        assertNull(state.request(11))
        state.complete(first, receiptLines(), confirm)
        assertTrue(requireNotNull(state.receipt).titleConfirmed)
        repeat(20) { assertFalse(requireNotNull(state.receipt).verified); assertEquals(1, state.receipt?.heading?.confirmations) }
        state.complete(first, receiptLines(), confirm)
        assertEquals(1, state.receipt?.heading?.confirmations)
        now = 199
        assertNull(state.request(11))
        now = 200
        val second = requireNotNull(state.request(11))
        assertNotEquals(first.ticket.evidenceId, second.ticket.evidenceId)
        assertFalse(requireNotNull(state.receipt).verified)
        state.complete(second, receiptLines(), confirm)
        assertTrue(requireNotNull(state.receipt).verified)
        now = 400
        assertNull(state.request(11))
    }

    @Test fun `回执任一字段漂移都会撤销整个复核`() {
        var now = 0L
        val state = LabyrinthShopReceiptReadState { now }
        state.complete(requireNotNull(state.request(11)), receiptLines(), confirm)
        now = 200
        val changed = receiptLines("购买了防守铠甲。")
        state.complete(requireNotNull(state.request(11)), changed, confirm)
        assertEquals(1, state.receipt?.balanceBefore?.confirmations)
        assertFalse(requireNotNull(state.receipt).verified)
        now = 400
        state.complete(requireNotNull(state.request(11)), changed, confirm)
        assertTrue(requireNotNull(state.receipt).verified)
        assertEquals("防守铠甲", state.receipt?.itemName)
    }

    @Test fun `字段两端多出的非文字符号不算读数漂移`() {
        var now = 0L
        val state = LabyrinthShopReceiptReadState { now }
        state.complete(requireNotNull(state.request(11)), receiptLines(), confirm)
        now = 200
        val noisy = receiptLines().map { line ->
            when (line.text) {
                "购买了苍珠手镯。" -> line.copy(text = "|购买了苍珠手镯。|")
                "1000" -> line.copy(text = "|1000")
                "900" -> line.copy(text = "900|")
                else -> line
            }
        }
        state.complete(requireNotNull(state.request(11)), noisy, confirm)
        assertTrue(requireNotNull(state.receipt).verified)
        assertEquals("苍珠手镯", state.receipt?.itemName)
    }

    @Test fun `回执旧epoch与重复回调不能释放或污染新作业`() {
        var now = 0L
        val state = LabyrinthShopReceiptReadState { now }
        val old = requireNotNull(state.request(11))
        state.clear()
        now = 200
        assertNull(state.request(11))
        state.complete(old, receiptLines(), confirm)
        assertNull(state.receipt)
        val current = requireNotNull(state.request(11))
        state.complete(old, receiptLines(), confirm)
        assertNull(state.request(11))
        state.complete(current, receiptLines("购买了防守铠甲。"), confirm)
        assertEquals("防守铠甲", state.receipt?.itemName)
        assertEquals(1, state.receipt?.heading?.confirmations)
        now = 400
        assertNotNull(state.request(22))
        assertNull(state.receipt)
    }

    @Test fun `回执OCR失败会释放作业且长时间停留后重新复核`() {
        var now = 0L
        val state = LabyrinthShopReceiptReadState { now }
        state.complete(requireNotNull(state.request(11)), null, confirm)
        assertNull(state.receipt)
        now = 200
        state.complete(requireNotNull(state.request(11)), receiptLines(), confirm)
        now = 400
        state.complete(requireNotNull(state.request(11)), receiptLines(), confirm)
        assertTrue(requireNotNull(state.receipt).verified)
        now = 5_399
        assertNull(state.request(11))
        now = 5_400
        val reread = requireNotNull(state.request(11))
        assertNull(state.receipt)
        state.complete(reread, receiptLines(), confirm)
        assertFalse(requireNotNull(state.receipt).verified)
    }

    @Test fun `钱包复用ROI调度器缓存不会被重复计为第二次读取`() {
        var now = 0L
        val scheduler = RelicOcrScheduler({ now })
        val first = requireNotNull(scheduler.request("wallet", 11))
        scheduler.complete(first, "100")
        repeat(20) {
            val cached = requireNotNull(scheduler.cached("wallet", 11))
            assertNull(LabyrinthCurrencyObservation(LabyrinthShopReceiptRead(cached.text, cached.id, cached.confirmations)).balance)
        }
        now = 350
        val second = requireNotNull(scheduler.request("wallet", 11))
        scheduler.complete(second, "100")
        val read = requireNotNull(scheduler.cached("wallet", 11))
        assertEquals(100L, LabyrinthCurrencyObservation(LabyrinthShopReceiptRead(read.text, read.id, read.confirmations)).balance)
    }

    @Test fun `回执读取中只返回不可操作页面而不伪造购买证据`() {
        val state = LabyrinthShopReceiptReadState { 0 }
        val pending = requireNotNull(state.request(11))
        repeat(20) {
            assertEquals(LabyrinthShopReceiptReadStatus.READING, state.readStatus)
            val reading = requireNotNull(state.frameResult(1920, 1080))
            assertEquals(LabyrinthEntryPageState.UNKNOWN, reading.observation.state)
            assertEquals(0.0, reading.observation.confidence, 0.0)
            assertEquals(0.0, reading.observation.anchorScores[EntryAnchorId.SHOP_TITLE], 0.0)
            assertNull(reading.shopObservation?.purchaseReceipt)
            assertFalse(reading.matchedFeatures.contains("shop.receipt.fast_path"))
            assertEquals(1920, reading.frameWidth)
            assertEquals(1080, reading.frameHeight)
            assertSame(reading, resolveShopReceiptOrPage(reading) { error("读取中不应操作底层页面") })
            assertNull(state.request(11))
        }
        state.complete(pending, null, confirm)
        assertEquals(LabyrinthShopReceiptReadStatus.READING, state.readStatus)
        assertNull(requireNotNull(state.frameResult(1920, 1080)).shopObservation?.purchaseReceipt)
    }

    @Test fun `独立OCR否定标题或缺失标题后立即让出快速路径`() {
        listOf("退出商店", "确认购买", "商店", "购买", "购买完成？", "").forEach { heading ->
            val state = LabyrinthShopReceiptReadState { 0 }
            val ticket = requireNotNull(state.request(11))
            val lines = receiptLines().map { if (it.text == "购买完成") it.copy(text = heading) else it }
            state.complete(ticket, lines, confirm)
            assertEquals(LabyrinthShopReceiptReadStatus.NOT_RECEIPT, state.readStatus)
            assertFalse(requireNotNull(state.receipt).titleConfirmed)
            assertNull(state.frameResult(1920, 1080))
        }
        listOf(emptyList(), receiptLines().drop(1)).forEach { lines ->
            val state = LabyrinthShopReceiptReadState { 0 }
            state.complete(requireNotNull(state.request(11)), lines, confirm)
            assertEquals(LabyrinthShopReceiptReadStatus.NOT_RECEIPT, state.readStatus)
            assertNull(state.frameResult(1920, 1080))
        }
    }

    @Test fun `否定标题缓存与同帧在途重读不能重新抢占页面`() {
        var now = 0L
        val state = LabyrinthShopReceiptReadState { now }
        val first = requireNotNull(state.request(11))
        state.complete(first, receiptLines().drop(1), confirm)
        repeat(20) {
            assertNull(state.request(11))
            assertNull(state.frameResult(1920, 1080))
        }
        now = 200
        val retry = requireNotNull(state.request(11))
        assertNotEquals(first.ticket.evidenceId, retry.ticket.evidenceId)
        assertEquals(LabyrinthShopReceiptReadStatus.NOT_RECEIPT, state.readStatus)
        assertNull(state.frameResult(1920, 1080))
        state.complete(first, receiptLines(), confirm)
        assertNull(state.frameResult(1920, 1080))
        assertNull(state.request(11))
        state.complete(retry, emptyList(), confirm)
        assertNull(state.frameResult(1920, 1080))
    }

    @Test fun `否定标题后的新帧重新读取且真回执仍须独立双读`() {
        var now = 0L
        val state = LabyrinthShopReceiptReadState { now }
        val rejected = requireNotNull(state.request(11))
        state.complete(rejected, receiptLines().drop(1), confirm)
        assertNull(state.frameResult(1920, 1080))
        val first = requireNotNull(state.request(22))
        assertNotEquals(rejected.ticket.epoch, first.ticket.epoch)
        assertEquals(LabyrinthShopReceiptReadStatus.READING, state.readStatus)
        assertNull(requireNotNull(state.frameResult(960, 540)).shopObservation?.purchaseReceipt)
        state.complete(first, receiptLines(), confirm)
        assertEquals(LabyrinthShopReceiptReadStatus.PURCHASE_COMPLETE, state.readStatus)
        val unverified = requireNotNull(state.frameResult(1920, 1080))
        assertEquals(LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE, unverified.observation.state)
        assertEquals(LabyrinthShopDialogState.PURCHASE_COMPLETE, unverified.shopObservation?.dialogState)
        assertFalse(requireNotNull(unverified.shopObservation?.purchaseReceipt).verified)
        assertSame(unverified, resolveShopReceiptOrPage(unverified) { error("真回执复核中不能回退") })
        repeat(20) {
            state.complete(first, receiptLines(), confirm)
            assertEquals(1, state.receipt?.heading?.confirmations)
        }
        now = 200
        val second = requireNotNull(state.request(22))
        assertNotEquals(first.ticket.evidenceId, second.ticket.evidenceId)
        state.complete(second, receiptLines(), confirm)
        val verified = requireNotNull(state.frameResult(1920, 1080))
        assertTrue(requireNotNull(verified.shopObservation?.purchaseReceipt).verified)
        assertEquals(0.98, verified.observation.confidence, 0.0)
    }

    @Test fun `新帧在途时旧epoch的真标题与重复回调不能污染读取状态`() {
        val state = LabyrinthShopReceiptReadState { 0 }
        val old = requireNotNull(state.request(11))
        assertNull(state.request(22))
        assertEquals(LabyrinthShopReceiptReadStatus.READING, state.readStatus)
        state.complete(old, receiptLines(), confirm)
        assertNull(state.receipt)
        assertEquals(LabyrinthShopReceiptReadStatus.READING, state.readStatus)
        val current = requireNotNull(state.request(22))
        state.complete(old, receiptLines(), confirm)
        assertNull(state.request(22))
        assertNull(state.receipt)
        state.complete(current, receiptLines().drop(1), confirm)
        assertEquals(LabyrinthShopReceiptReadStatus.NOT_RECEIPT, state.readStatus)
        assertNull(state.frameResult(1920, 1080))
        state.clear()
        assertEquals(LabyrinthShopReceiptReadStatus.READING, state.readStatus)
        assertNotNull(state.request(22))
    }

    @Test fun `否定回执在两层生产回退中保留真实分类和弹窗观测`() {
        val state = LabyrinthShopReceiptReadState { 0 }
        state.complete(requireNotNull(state.request(11)), emptyList(), confirm)
        val cases = listOf(
            LabyrinthEntryPageState.SHOP to listOf(EntryAnchorId.SHOP_INSTRUCTION, EntryAnchorId.SHOP_CLOSE),
            LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION to listOf(EntryAnchorId.SHOP_PURCHASE_CONFIRMATION_TITLE),
            LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION to listOf(EntryAnchorId.SHOP_EXIT_CONFIRMATION_TITLE),
            LabyrinthEntryPageState.ITEM_REWARD to listOf(EntryAnchorId.ITEM_REWARD_TITLE,
                EntryAnchorId.ITEM_REWARD_INSTRUCTION, EntryAnchorId.ITEM_REWARD_CLOSE),
            LabyrinthEntryPageState.EVENT_CHOICE to listOf(EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE,
                EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON),
        )
        cases.forEach { (expected, anchors) ->
            var classified: LabyrinthEntryFrameResult? = null
            var pageReads = 0
            var normalResolverReads = 0
            val resolved = resolveShopReceiptOrPage(state.frameResult(1920, 1080)) {
                pageReads++
                val scores = LabyrinthAnchorScores((anchors + EntryAnchorId.SHOP_TITLE).associateWith { 0.95 })
                val observation = LabyrinthEntryPageClassifier().classify(scores)
                val page = LabyrinthEntryFrameResult(observation, listOf("normal.page"), 23,
                    anchorMatches = mapOf(EntryAnchorId.SHOP_TITLE to EntryAnchorMatch(0.95, confirm)),
                    shopObservation = LabyrinthShopObservation(emptyList(), observation.state.toShopDialogState()),
                    frameWidth = 1920, frameHeight = 1080, stageMillis = mapOf("normal" to 23L))
                classified = page
                // 普通 resolver 再遇到相同粗筛时也必须使用否定证据，而不是重建未知回执。
                resolveShopReceiptOrPage(state.frameResult(1920, 1080)) {
                    normalResolverReads++
                    page
                }
            }
            assertEquals(expected, resolved.observation.state)
            assertSame(classified, resolved)
            assertEquals(1, pageReads)
            assertEquals(1, normalResolverReads)
            assertEquals(expected.toShopDialogState(), resolved.shopObservation?.dialogState)
            assertNull(resolved.shopObservation?.purchaseReceipt)
            assertEquals(listOf("normal.page"), resolved.matchedFeatures)
            assertEquals(mapOf("normal" to 23L), resolved.stageMillis)
        }
    }

    @Test fun `确认标题不绕过金额差额也不回退到底层页面`() {
        val badAmount = receiptLines().map { if (it.text == "900") it.copy(text = "901") else it }
        val badDigits = receiptLines().map { if (it.text == "1000") it.copy(text = "1O00") else it }
        listOf(badAmount to confirm, badDigits to confirm, receiptLines() to null).forEach { (lines, rect) ->
            var now = 0L
            val state = LabyrinthShopReceiptReadState { now }
            state.complete(requireNotNull(state.request(11)), lines, rect)
            now = 200
            state.complete(requireNotNull(state.request(11)), lines, rect)
            assertEquals(LabyrinthShopReceiptReadStatus.PURCHASE_COMPLETE, state.readStatus)
            val result = requireNotNull(state.frameResult(1920, 1080))
            val receipt = requireNotNull(result.shopObservation?.purchaseReceipt)
            assertTrue(receipt.independentReadsConfirmed)
            assertFalse(receipt.verified)
            assertNotNull(receipt.reviewReason)
            assertEquals(0.0, result.observation.confidence, 0.0)
            assertSame(result, resolveShopReceiptOrPage(result) { error("未核实回执不得绕过校验") })
        }
    }

    private fun prices(first: String = "100") = listOf(first, "200", "300", "10", "1000").mapIndexed { index, text ->
        LabyrinthShopOcrLine(text, EntryPixelRect(index % 3 * 500 + 25, index / 3 * 180 + 25, 200, 40))
    }

    private fun receiptRow(index: Int, left: Int = 20) =
        EntryPixelRect(left, index * LABYRINTH_SHOP_RECEIPT_COLLAGE_ROW_HEIGHT + 40, 600, 60)

    private fun receiptLines(item: String = "购买了苍珠手镯。") = listOf(
        LabyrinthShopOcrLine("购买完成", receiptRow(0)),
        LabyrinthShopOcrLine("消耗阿尔法金币×100", receiptRow(1)),
        LabyrinthShopOcrLine(item, receiptRow(2)),
        LabyrinthShopOcrLine("1000", receiptRow(3)),
        LabyrinthShopOcrLine("900", receiptRow(4)),
        LabyrinthShopOcrLine("确认", receiptRow(5)),
    )
}
