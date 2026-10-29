package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.labyrinth.vision.LabyrinthShopPurchaseReceiptObservation
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LabyrinthShopReceiptLedgerTest {
    @get:Rule val temp = TemporaryFolder()
    private val root by lazy { temp.newFolder("receipts") }
    private val store by lazy { LabyrinthShoppingJournal(root) }
    private val run by lazy { store.open(1, 1, null, 0) }
    private val ledger by lazy { LabyrinthShopReceiptLedger(store) }
    private val module by lazy { LabyrinthShoppingModule(store).also { it.open(1, 1, null, 0) } }
    private fun record(receipt: LabyrinthShopPurchaseReceiptObservation = ShoppingTestData.receipt(),
        encode: (FileOutputStream) -> Boolean = ShoppingTestData.encode) = ledger.record(run, receipt, 7, 1, 1, encode)
    private fun automated(role: Boolean = false, choice: Boolean = false) {
        module.recordIntent(if (role) ShoppingTestData.role(choice) else ShoppingTestData.item(), 1000, 7, 1, 0, ShoppingTestData.encode)
        module.confirmDispatched()
    }
    private fun corrupt(event: JsonObject) = File(run.directory, event["evidence_ref"]!!.jsonPrimitive.content).writeBytes(byteArrayOf(9))

    @Test fun `回执各区域必须各自双读不能只凭标题`() {
        val receipt = ShoppingTestData.receipt()
        listOf(receipt.copy(heading = receipt.heading!!.copy(confirmations = 1)),
            receipt.copy(item = receipt.item!!.copy(confirmations = 1)),
            receipt.copy(expenditure = receipt.expenditure!!.copy(confirmations = 1)),
            receipt.copy(balanceBefore = receipt.balanceBefore!!.copy(confirmations = 1)),
            receipt.copy(balanceAfter = receipt.balanceAfter!!.copy(confirmations = 1)))
            .forEach { ShoppingTestData.reject { record(it) } }
        assertTrue(store.read(run).isEmpty())
    }

    @Test fun `标题或确认矩形不符严格拒绝`() {
        val receipt = ShoppingTestData.receipt()
        listOf(receipt.copy(heading = ShoppingTestData.read("确认购买")), receipt.copy(confirmRect = null))
            .forEach { ShoppingTestData.reject { record(it) } }
        assertTrue(store.read(run).isEmpty())
    }

    @Test fun `金额解析异常零花费不足余额和差额不符拒绝`() {
        listOf(ShoppingTestData.receipt(spent = 0), ShoppingTestData.receipt(spent = 1100),
            ShoppingTestData.receipt(after = 800), ShoppingTestData.receipt().copy(balanceBefore = ShoppingTestData.read("1O00")))
            .forEach { ShoppingTestData.reject { record(it) } }
        assertTrue(store.read(run).isEmpty())
    }

    @Test fun `回执商品必须精确匹配遗物名称不得模糊包含`() {
        automated()
        listOf("苍珠手环", "高级苍珠手镯", "苍珠手镯强化版").forEach { name ->
            ShoppingTestData.reject { record(ShoppingTestData.receipt(name)) }
        }
        assertEquals(2, store.read(run).size)
    }

    @Test fun `同商品不同实际价格仍拒绝即使余额差额自洽`() {
        automated(); ShoppingTestData.reject { record(ShoppingTestData.receipt(spent = 200)) }
        assertEquals(2, store.read(run).size)
    }

    @Test fun `同商品价格但回执前余额不同严格拒绝`() {
        automated(); ShoppingTestData.reject { record(ShoppingTestData.receipt(before = 1200)) }
        assertEquals(2, store.read(run).size)
    }

    @Test fun `遗物名称只忽略空白不猜测错字`() {
        automated(); val result = record(ShoppingTestData.receipt("苍 珠 手 镯"))
        assertEquals("relic-1", result.itemId); assertTrue(result.automated)
    }

    @Test fun `印记按精确职能及随机选择语义匹配`() {
        automated(role = true)
        val result = record(ShoppingTestData.receipt("攻击型职能的随机印记"))
        assertTrue(result.automated); assertNull(result.itemId)
    }

    @Test fun `随机印记不能与选择印记互换`() {
        automated(role = true)
        ShoppingTestData.reject { record(ShoppingTestData.receipt("攻击型职能的选择印记")) }
    }

    @Test fun `选择印记不能与随机印记互换`() {
        automated(role = true, choice = true)
        ShoppingTestData.reject { record(ShoppingTestData.receipt("攻击型职能的随机印记")) }
        assertTrue(record(ShoppingTestData.receipt("攻击型职能的选择印记")).automated)
    }

    @Test fun `不同职能同价格印记不得关联`() {
        automated(role = true)
        ShoppingTestData.reject { record(ShoppingTestData.receipt("治疗型职能的随机印记")) }
    }

    @Test fun `原图编码失败不生成财务回执且保留意图可重试`() {
        automated(); ShoppingTestData.reject { record(encode = { it.write(byteArrayOf(1)); false }) }
        assertEquals(2, store.read(run).size); assertFalse(module.canDismiss(ShoppingTestData.receipt()))
        assertTrue(record().automated); assertEquals(3, store.read(run).size)
    }

    @Test fun `空回执原图不可入账`() {
        ShoppingTestData.reject { record(encode = { true }) }; assertTrue(store.read(run).isEmpty())
    }

    @Test fun `重复回执仅复核原图返回同一个提交`() {
        automated(); val first = record()
        assertEquals(first, record(encode = ShoppingTestData.neverEncode))
        assertEquals(1, store.read(run).count { ShoppingTestData.type(it) == "shop.purchase_completed" })
    }

    @Test fun `回执原图篡改重复接受及持有提交都拒绝关闭返回false`() {
        automated(); val first = record(); corrupt(store.read(run).last())
        ShoppingTestData.reject { record() }
        ShoppingTestData.reject { ledger.markOwnershipCommitted(run, first.key, 2) }
        assertFalse(module.canDismiss(ShoppingTestData.receipt()))
    }

    @Test fun `回执原图缺失重启后不得重拍替换`() {
        automated(); record(); val event = store.read(run).last()
        assertTrue(File(run.directory, event["evidence_ref"]!!.jsonPrimitive.content).delete())
        val reopened = LabyrinthShopReceiptLedger(LabyrinthShoppingJournal(root))
        ShoppingTestData.reject { reopened.record(run, ShoppingTestData.receipt(), 7, 1, 2, ShoppingTestData.encode) }
    }

    @Test fun `意图原图篡改后首次回执拒绝`() {
        automated(); corrupt(store.read(run).first())
        ShoppingTestData.reject { record() }; assertEquals(2, store.read(run).size)
    }

    @Test fun `已保存自动回执仍必须复核意图原图`() {
        automated(); record(); corrupt(store.read(run).first())
        ShoppingTestData.reject { record(encode = ShoppingTestData.neverEncode) }
        assertFalse(module.canDismiss(ShoppingTestData.receipt()))
    }

    @Test fun `持有标记必须发生在回执后而不能凭任意key先写`() {
        ShoppingTestData.reject { ledger.markOwnershipCommitted(run, "purchase-not-yet", 1) }
        assertTrue(store.read(run).isEmpty())
    }

    @Test fun `持有标记耐久幂等并在回执序号之后`() {
        automated(); val first = record(); ledger.markOwnershipCommitted(run, first.key, 2)
        ledger.markOwnershipCommitted(run, first.key, 3)
        val events = store.read(run)
        assertEquals("shop.ownership_committed", ShoppingTestData.type(events.last()))
        assertEquals(4, events.size)
        assertTrue(record(encode = ShoppingTestData.neverEncode).ownershipCommitted)
    }

    @Test fun `不同回执不能沿用已提交旧回执关闭许可`() {
        automated(); val first = record(); ledger.markOwnershipCommitted(run, first.key, 2)
        assertFalse(module.canDismiss(ShoppingTestData.receipt("钢铁护符")))
        assertFalse(module.canDismiss(ShoppingTestData.receipt(before = 1200)))
    }

    @Test fun `持有已提交但后续新意图不能污染旧重复回执归属`() {
        automated(); val first = record(); ledger.markOwnershipCommitted(run, first.key, 2)
        module.recordIntent(ShoppingTestData.item("钢铁护符", "relic-2"), 900, 7, 1, 3, ShoppingTestData.encode)
        val duplicate = record(encode = ShoppingTestData.neverEncode)
        assertEquals(first.key, duplicate.key); assertEquals("relic-1", duplicate.itemId)
        assertTrue(duplicate.ownershipCommitted)
    }

    @Test fun `回执保存各区域独立读数及证据编号`() {
        record(); val ocr = ShoppingTestData.payload(store.read(run).single())["ocr"]!!.jsonObject
        assertEquals(setOf("heading", "item", "expenditure", "before", "after", "button"), ocr.keys)
        ocr.values.forEach { assertEquals(2, it.jsonObject["confirmations"]!!.jsonPrimitive.int)
            assertEquals(42L, it.jsonObject["evidence_id"]!!.jsonPrimitive.long) }
    }

    @Test fun `跨楼层回执不能静默关联`() {
        automated()
        ShoppingTestData.reject { ledger.record(run, ShoppingTestData.receipt(), 7, 2, 1, ShoppingTestData.encode) }
    }

    @Test fun `多个确认派发事实不得静默降级成人工回执`() {
        automated(); val dispatch = store.read(run).last()
        store.append(run, "second-confirm", "shop.purchase_confirm_dispatched", 7, 1, 1,
            ShoppingTestData.payload(dispatch), dispatch["evidence_ref"]!!.jsonPrimitive.content,
            verification = "dispatched_only", source = "automation_action")
        ShoppingTestData.reject { record() }; assertEquals(3, store.read(run).size)
    }

    @Test fun `多个持有事实不得当成未提交或重复写入`() {
        automated(); val first = record(); ledger.markOwnershipCommitted(run, first.key, 2)
        val ownership = store.read(run).last()
        store.append(run, "second-ownership", "shop.ownership_committed", 7, 1, 3,
            ShoppingTestData.payload(ownership), ownership["evidence_ref"]!!.jsonPrimitive.content, source = "ownership_commit")
        ShoppingTestData.reject { ledger.markOwnershipCommitted(run, first.key, 4) }
        assertFalse(module.canDismiss(ShoppingTestData.receipt()))
    }

    @Test fun `重复回执不能使用同节点旧键跨楼层重放`() {
        automated(); record()
        ShoppingTestData.reject { ledger.record(run, ShoppingTestData.receipt(), 7, 2, 2, ShoppingTestData.neverEncode) }
    }

    @Test fun `回执前取消不能保留prepared自动许可`() {
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0, ShoppingTestData.encode)
        module.prepareConfirmation(1); val intent = store.read(run).first()
        // 绕过 facade 注入非法撤销，验证 commit 不能因 prepared 尚在就忽略取消次序。
        store.append(run, "unsafe-cancel", "shop.purchase_intent_cancelled", 7, 1, 2,
            buildJsonObject { put("intent_id", intent["event_id"]!!) }, verification = "cancelled", source = "automation_action")
        val saved = record()
        ShoppingTestData.rewriteEvents(run) { event -> if (ShoppingTestData.type(event) == "shop.purchase_completed")
            JsonObject(event + ("payload" to JsonObject(ShoppingTestData.payload(event) + buildJsonObject {
                put("automated", true); put("intent_id", intent["event_id"]!!); put("item_id", "relic-1")
            }))) else event }
        ShoppingTestData.reject { ledger.markOwnershipCommitted(run, saved.key, 3) }
        ShoppingTestData.reject { module.pendingOwnership() }
    }

    @Test fun `prepared回执自动关联仍严格匹配商品金额节点楼层`() {
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0, ShoppingTestData.encode)
        assertTrue(module.prepareConfirmation(1))
        listOf(ShoppingTestData.receipt("高级苍珠手镯"), ShoppingTestData.receipt(spent = 200),
            ShoppingTestData.receipt(before = 1200)).forEach { ShoppingTestData.reject { record(it) } }
        ShoppingTestData.reject { ledger.record(run, ShoppingTestData.receipt(), 8, 1, 2, ShoppingTestData.encode) }
        ShoppingTestData.reject { ledger.record(run, ShoppingTestData.receipt(), 7, 2, 2, ShoppingTestData.encode) }
        assertTrue(record().automated)
    }

    @Test fun `只存在intent不能篡改automated升级为自动提交`() {
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0, ShoppingTestData.encode)
        val saved = record(); assertFalse(saved.automated)
        ShoppingTestData.rewriteEvents(run) { event -> if (ShoppingTestData.type(event) == "shop.purchase_completed")
            JsonObject(event + ("payload" to JsonObject(ShoppingTestData.payload(event) + ("automated" to JsonPrimitive(true))))) else event }
        ShoppingTestData.reject { module.pendingOwnership() }
        ShoppingTestData.reject { ledger.markOwnershipCommitted(run, saved.key, 2) }
    }

    @Test fun `已拒绝prepared不能篡改回执自动属性恢复持有`() {
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0, ShoppingTestData.encode)
        module.prepareConfirmation(1); module.rejectConfirmation(2); record()
        ShoppingTestData.rewriteEvents(run) { event -> if (ShoppingTestData.type(event) == "shop.purchase_completed")
            JsonObject(event + ("payload" to JsonObject(ShoppingTestData.payload(event) + ("automated" to JsonPrimitive(true))))) else event }
        ShoppingTestData.reject { module.pendingOwnership() }
    }

    @Test fun `回执之后才保存的dispatch不能作为自动完成证据`() {
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0, ShoppingTestData.encode)
        record(); val intent = store.read(run).first()
        store.append(run, "late-confirm", "shop.purchase_confirm_dispatched", 7, 1, 2,
            buildJsonObject { put("intent_id", intent["event_id"]!!) }, intent["evidence_ref"]!!.jsonPrimitive.content,
            verification = "dispatched_only", source = "automation_action")
        ShoppingTestData.rewriteEvents(run) { event -> if (ShoppingTestData.type(event) == "shop.purchase_completed")
            JsonObject(event + ("payload" to JsonObject(ShoppingTestData.payload(event) + ("automated" to JsonPrimitive(true))))) else event }
        ShoppingTestData.reject { module.pendingOwnership() }
    }

    @Test fun `pendingOwnership拒绝回执金额身份节点楼层及独立读数篡改`() {
        val mutations = listOf<(JsonObject) -> JsonObject>(
            { p -> JsonObject(p + ("item_id" to JsonPrimitive("relic-forged"))) },
            { p -> JsonObject(p + ("item_name" to JsonPrimitive("钢铁护符"))) },
            { p -> JsonObject(p + ("spent" to JsonPrimitive(200))) },
            { p -> JsonObject(p + ("balance_before" to JsonPrimitive(1200))) },
            { p -> JsonObject(p + ("balance_after" to JsonPrimitive(800))) },
            { p -> JsonObject(p + ("confirmation_id" to JsonPrimitive("other-prepare"))) },
            { p -> JsonObject(p + ("ocr" to JsonObject(p["ocr"]!!.jsonObject + ("item" to buildJsonObject {
                put("text", "购买了苍珠手镯。"); put("evidence_id", 42); put("confirmations", 1)
            })))) }
        )
        mutations.forEachIndexed { index, mutate ->
            val current = store.open(1, 100L + index, null, 0)
            module.open(1, current.enterId, null, 0)
            module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0, ShoppingTestData.encode)
            module.prepareConfirmation(1); module.acceptReceipt(ShoppingTestData.receipt(), 7, 1, 2, ShoppingTestData.encode)
            ShoppingTestData.rewriteEvents(current) { event -> if (ShoppingTestData.type(event) == "shop.purchase_completed")
                JsonObject(event + ("payload" to mutate(ShoppingTestData.payload(event)))) else event }
            ShoppingTestData.reject { module.pendingOwnership() }
        }
        listOf("node_id", "floor").forEachIndexed { index, field ->
            val current = store.open(1, 200L + index, null, 0); module.open(1, current.enterId, null, 0)
            module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0, ShoppingTestData.encode)
            module.prepareConfirmation(1); module.acceptReceipt(ShoppingTestData.receipt(), 7, 1, 2, ShoppingTestData.encode)
            ShoppingTestData.rewriteEvents(current) { event -> if (ShoppingTestData.type(event) == "shop.purchase_completed")
                JsonObject(event + (field to JsonPrimitive(8))) else event }
            ShoppingTestData.reject { module.pendingOwnership() }
        }
    }

    @Test fun `pendingOwnership拒绝prepared身份来源状态或原图摘要篡改`() {
        listOf("node_id" to JsonPrimitive(8), "floor" to JsonPrimitive(2),
            "source" to JsonPrimitive("actual_screen"), "verification_status" to JsonPrimitive("dispatched_only"),
            "evidence_sha256" to JsonPrimitive("forged")).forEachIndexed { index, mutation ->
            val current = store.open(1, 300L + index, null, 0); module.open(1, current.enterId, null, 0)
            module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0, ShoppingTestData.encode)
            module.prepareConfirmation(1); module.acceptReceipt(ShoppingTestData.receipt(), 7, 1, 2, ShoppingTestData.encode)
            ShoppingTestData.rewriteEvents(current) { event -> if (ShoppingTestData.type(event) == "shop.purchase_confirm_prepared")
                JsonObject(event + mutation) else event }
            ShoppingTestData.reject { module.pendingOwnership() }
        }
    }

    @Test fun `prepared撤回后新prepared对应回执仍验证最新精确编号`() {
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0, ShoppingTestData.encode)
        module.prepareConfirmation(1); val first = store.read(run).last()
        module.rejectConfirmation(2); module.prepareConfirmation(3); assertTrue(record().automated)
        ShoppingTestData.rewriteEvents(run) { event -> if (ShoppingTestData.type(event) == "shop.purchase_completed")
            JsonObject(event + ("payload" to JsonObject(ShoppingTestData.payload(event) + ("confirmation_id" to first["event_id"]!!)))) else event }
        ShoppingTestData.reject { module.pendingOwnership() }
    }

    @Test fun `未派发但关联意图的重复回执也必须复核意图原图`() {
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0, ShoppingTestData.encode)
        assertFalse(record().automated); corrupt(store.read(run).first())
        ShoppingTestData.reject { record(encode = ShoppingTestData.neverEncode) }
    }
}
