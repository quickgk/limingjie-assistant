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
    @Test fun `fast receipt route invalidates stock even without ordinary item resolution and late OCR stays stale`() {
        var now = 0L
        val scheduler = RelicOcrScheduler({ now }, maximumAttempts = 1)
        val categories = LabyrinthShopCategoryResolution()
        val accepted = mutableMapOf("shop_item_1" to "old")
        val ticket = requireNotNull(scheduler.request("shop-title-1-shop_item_1-0", 42))
        categories.attempt("shop_item_1", 42, now)
        categories.noteVariantOutcome("shop_item_1", 42, 0, true, now, now)
        assertEquals(1, categories.attempt("shop_item_1", 42, now))
        val receiptFrame = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE, 1.0,
                emptyMap(), LabyrinthAnchorScores(emptyMap())), matchedFeatures = emptyList(), elapsedMillis = 0)
        assertSame(receiptFrame, resolveShopReceiptOrPage(receiptFrame, invalidateStock = {
            scheduler.clear(); categories.clear(); accepted.clear()
        }) { error("fast receipt must not resolve ordinary stock") })
        now = 500
        scheduler.complete(ticket, "old")
        assertNull(scheduler.cached("shop-title-2-shop_item_1-0", 42))
        assertTrue(accepted.isEmpty())
        assertEquals(0, categories.attempt("shop_item_1", 42, now))
        val current = requireNotNull(scheduler.request("shop-title-2-shop_item_1-0", 42))
        scheduler.complete(current, "new")
        assertEquals("new", scheduler.cached("shop-title-2-shop_item_1-0", 42)?.text)
    }

    @Test fun `确认钱包来源必须对应当前页面且通用弹窗不能作为地图钱包`() {
        fun frame(page: LabyrinthEntryPageState, dialog: LabyrinthShopDialogState = LabyrinthShopDialogState.NONE,
            scores: Map<String, Double> = emptyMap()) = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(page, 1.0, emptyMap(), LabyrinthAnchorScores(scores)),
            matchedFeatures = emptyList(), elapsedMillis = 0,
            shopObservation = LabyrinthShopObservation(emptyList(), dialog))
        val purchase = frame(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION, LabyrinthShopDialogState.PURCHASE_CONFIRMATION)
        assertEquals(LabyrinthCurrencySource.PURCHASE_CONFIRMATION, labyrinthCurrencyReadSource(purchase))
        assertEquals(LabyrinthCurrencySource.PURCHASE_CONFIRMATION, labyrinthCurrencyReadSource(purchase.copy(
            nodeMoveConfirmation = LabyrinthNodeMoveConfirmationObservation(1.0, confirm, confirm))))
        assertNull(labyrinthCurrencyReadSource(frame(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION)))
        val modal = frame(LabyrinthEntryPageState.UNKNOWN, scores = mapOf(EntryAnchorId.SHOP_TITLE to 1.0)).copy(
            nodeMoveConfirmation = LabyrinthNodeMoveConfirmationObservation(1.0, confirm, confirm),
            shopObservation = LabyrinthShopObservation(emptyList(), refreshConfirmationTitle = LabyrinthShopReceiptRead("更新确认", 2, 2)))
        assertEquals(LabyrinthCurrencySource.REFRESH_CONFIRMATION, labyrinthCurrencyReadSource(modal))
        assertNull(labyrinthCurrencyReadSource(modal.copy(shopObservation = modal.shopObservation!!.copy(
            refreshConfirmationTitle = LabyrinthShopReceiptRead("更新确认", 1, 1)))))
        assertNull(labyrinthCurrencyReadSource(modal.copy(observation = modal.observation.copy(
            state = LabyrinthEntryPageState.NODE_SELECTION))))
        assertNull(labyrinthCurrencyReadSource(modal.copy(observation = modal.observation.copy(
            state = LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION))))
        assertNull(labyrinthCurrencyReadSource(modal.copy(observation = modal.observation.copy(
            anchorScores = LabyrinthAnchorScores(emptyMap())))))
        assertEquals(LabyrinthCurrencySource.WALLET, labyrinthCurrencyReadSource(frame(LabyrinthEntryPageState.SHOP)))
    }

    @Test fun `通用蓝底相似不能确认退出且当前更新标题双读才纠正分类`() {
        val original = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION, 0.734,
                emptyMap(), LabyrinthAnchorScores(mapOf(EntryAnchorId.SHOP_TITLE to 0.99,
                    EntryAnchorId.SHOP_EXIT_CONFIRMATION_TITLE to 0.734))),
            matchedFeatures = emptyList(), elapsedMillis = 0,
            nodeMoveConfirmation = LabyrinthNodeMoveConfirmationObservation(1.0, confirm, confirm),
            shopObservation = LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.EXIT_CONFIRMATION))
        for (read in listOf(null, LabyrinthShopReceiptRead(null, 2, 2), LabyrinthShopReceiptRead("更新确认", 1, 1),
            LabyrinthShopReceiptRead("更新确认?", 2, 2), LabyrinthShopReceiptRead("商店确认", 2, 2))) {
            val resolved = resolveShoppingGenericDialog(original, read)
            assertFalse(labyrinthShopRefreshDialogVisible(resolved))
            assertNull(labyrinthCurrencyReadSource(resolved))
            assertEquals(read?.text == "商店确认", labyrinthShopExitDialogConfirmed(resolved))
        }
        val correct = resolveShoppingGenericDialog(original, LabyrinthShopReceiptRead("更 新确认", 2, 2))
        assertEquals(LabyrinthEntryPageState.UNKNOWN, correct.observation.state)
        assertTrue(labyrinthShopRefreshDialogVisible(correct))
        assertFalse(labyrinthShopExitDialogConfirmed(correct))
        assertEquals(LabyrinthCurrencySource.REFRESH_CONFIRMATION, labyrinthCurrencyReadSource(correct))
        assertFalse(labyrinthShopRefreshDialogVisible(correct.copy(nodeMoveConfirmation = null)))
        assertFalse(labyrinthShopRefreshDialogVisible(correct.copy(observation = correct.observation.copy(
            state = LabyrinthEntryPageState.NODE_SELECTION))))
        var now = 0L
        val scheduler = RelicOcrScheduler({ now })
        val first = requireNotNull(scheduler.request("generic", 11))
        scheduler.complete(first, "更新确认")
        now = 400
        val old = requireNotNull(scheduler.request("generic", 11))
        scheduler.clear()
        scheduler.complete(old, "更新确认")
        assertNull(scheduler.cached("generic", 12))
    }

    @Test fun `读取确认钱包不会把单读或重复getter变成双读`() {
        val single = LabyrinthCurrencyObservation(LabyrinthShopReceiptRead("5,800", 41, 1),
            LabyrinthCurrencySource.PURCHASE_CONFIRMATION)
        repeat(20) { assertNull(single.balance); assertEquals(1, single.read!!.confirmations) }
        val verified = single.copy(read = LabyrinthShopReceiptRead("5,800", 42, 2))
        assertEquals(5800L, verified.balance)
        assertNull(verified.copy(read = verified.read!!.copy(text = null)).balance)
        assertEquals(false, LabyrinthShopItemText.confirmedImprintChoice("攻击型职能的随机印记"))
        assertEquals(true, LabyrinthShopItemText.confirmedImprintChoice("攻击型职能的选择印记"))
        assertNull(LabyrinthShopItemText.confirmedImprintChoice("攻击型职能印记"))
        assertNull(LabyrinthShopItemText.confirmedImprintChoice("选择随机印记"))
    }

    private val confirm = EntryPixelRect(780, 710, 360, 65)

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

    @Test fun `回执六行原文一直可以在调试诊断里查到`() {
        var now = 0L
        val state = LabyrinthShopReceiptReadState { now }
        assertNull(state.diagnostics())
        val bad = receiptLines().map { if (it.text == "购买完成") it.copy(text = "商店") else it }
        state.complete(requireNotNull(state.request(11)), bad, confirm)
        val rejected = requireNotNull(state.diagnostics())
        assertEquals("NOT_RECEIPT", rejected.status)
        assertEquals("购买完成标题未核实", rejected.reviewReason)
        assertEquals("商店", rejected.heading)
        assertEquals("购买了苍珠手镯。", rejected.item)
        assertEquals("消耗阿尔法金币×100", rejected.expenditure)
        assertEquals("1000", rejected.balanceBefore)
        assertEquals("900", rejected.balanceAfter)
        now = 200
        state.complete(requireNotNull(state.request(11)), receiptLines(), confirm)
        val waiting = requireNotNull(state.diagnostics())
        assertEquals("PURCHASE_COMPLETE", waiting.status)
        assertEquals("购买完成", waiting.heading)
        assertEquals("等待各区域独立OCR复核", waiting.reviewReason)
        now = 400
        state.complete(requireNotNull(state.request(11)), receiptLines(), confirm)
        assertEquals(null, requireNotNull(state.diagnostics()).reviewReason)
        // 单独记录的引擎读数只进诊断，不影响判定。
        assertNull(requireNotNull(state.diagnostics()).paddle)
        state.markPaddleRunning("ppocr_v5_mobile_rec")
        val running = requireNotNull(requireNotNull(state.diagnostics()).paddle)
        assertEquals("running", running.status)
        assertEquals("ppocr_v5_mobile_rec", running.model)
        state.setPaddle(listOf("购买完毕", "消耗阿尔法金币×100", "购买了苍珠手镯。", "1000", "900", "确认"))
        val paddle = requireNotNull(requireNotNull(state.diagnostics()).paddle)
        assertEquals("ok", paddle.status)
        assertEquals("购买了苍珠手镯。", paddle.item)
        assertNull(requireNotNull(state.diagnostics()).reviewReason)
        state.setPaddle(listOf(null, null, null, null, null, null))
        assertEquals("unavailable", requireNotNull(requireNotNull(state.diagnostics()).paddle).status)
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
