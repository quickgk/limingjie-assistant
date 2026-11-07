package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.labyrinth.vision.LabyrinthShopPurchaseReceiptObservation
import java.io.File
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
    private fun record(receipt: LabyrinthShopPurchaseReceiptObservation = ShoppingTestData.receipt()) =
        ledger.record(run, receipt, 7, 1, 1)
    private fun automated(role: Boolean = false, choice: Boolean = false) {
        module.recordIntent(if (role) ShoppingTestData.role(choice) else ShoppingTestData.item(), 1000, 7, 1, 0)
        module.confirmDispatched()
    }
    private fun corrupt(event: JsonObject) = ShoppingTestData.rewriteEvents(run) { saved ->
        if (saved["event_id"] == event["event_id"]) JsonObject(saved + ("source" to JsonPrimitive("invalid"))) else saved
    }

    @Test fun `关闭许可独立核对当前节点楼层回执编号和持有顺序`() {
        automated(); val first = record(); ledger.markOwnershipCommitted(run, first.key, 2)
        val receipt = ShoppingTestData.receipt()
        assertTrue(module.canDismiss(receipt, 7, 1, first.key))
        assertFalse(module.canDismiss(receipt, 8, 1, first.key))
        assertFalse(module.canDismiss(receipt, 7, 2, first.key))
        assertFalse(module.canDismiss(receipt, null, 1, first.key))
        assertFalse(module.canDismiss(receipt, 7, null, first.key))
        assertFalse(module.canDismiss(receipt, 7, 1, "another-receipt"))
        ShoppingTestData.rewriteEvents(run) { event -> if (ShoppingTestData.type(event) == "shop.ownership_committed")
            JsonObject(event + ("verification_status" to JsonPrimitive("intent_only"))) else event }
        assertFalse(module.canDismiss(receipt, 7, 1, first.key))
    }

    @Test fun `关闭许可独立拒绝早于回执或重复的持有标记`() {
        automated(); val first = record(); ledger.markOwnershipCommitted(run, first.key, 2)
        val original = store.read(run)
        val completed = original.single { ShoppingTestData.type(it) == "shop.purchase_completed" }
        val owned = original.single { ShoppingTestData.type(it) == "shop.ownership_committed" }
        ShoppingTestData.rewriteEvents(run) { event -> when (event["event_id"]) {
            completed["event_id"] -> JsonObject(owned + ("seq" to event["seq"]!!))
            owned["event_id"] -> JsonObject(completed + ("seq" to event["seq"]!!))
            else -> event
        } }
        assertFalse(module.canDismiss(ShoppingTestData.receipt(), 7, 1))
        ShoppingTestData.rewriteEvents(run) { event -> original.single { it["seq"] == event["seq"] } }
        assertTrue(module.canDismiss(ShoppingTestData.receipt(), 7, 1))
        store.append(run, "duplicate-ownership", "shop.ownership_committed", 7, 1, 3,
            ShoppingTestData.payload(owned), source = "ownership_commit")
        assertFalse(module.canDismiss(ShoppingTestData.receipt(), 7, 1))
    }

    @Test fun `已有持有标记也不能绕过分类职能或选择类型校验`() {
        for (field in listOf("item_kind", "role_label", "choice_imprint")) {
            val current = store.open(1, 20L + field.length, null, 0)
            val facade = LabyrinthShoppingModule(store).also { it.open(1, current.enterId, null, 0) }
            facade.recordIntent(ShoppingTestData.role(), 1000, 7, 1, 0)
            ShoppingTestData.prepare(facade, 1)
            val receipt = ShoppingTestData.receipt("攻击型职能的随机印记")
            val commit = facade.acceptReceipt(receipt, 7, 1, 2); facade.markOwnershipCommitted(commit.key, 3)
            assertTrue(facade.canDismiss(receipt, 7, 1))
            val value = when (field) {
                "item_kind" -> JsonPrimitive("relic")
                "role_label" -> JsonPrimitive("治疗型职能印记")
                else -> JsonPrimitive(true)
            }
            ShoppingTestData.rewriteEvents(current) { event -> if (ShoppingTestData.type(event) == "shop.purchase_intent")
                JsonObject(event + ("payload" to JsonObject(ShoppingTestData.payload(event) + (field to value)))) else event }
            assertFalse(facade.canDismiss(receipt, 7, 1))
        }
    }

    @Test fun `持有已提交之后出现相关放弃仍禁止正常关闭`() {
        automated(); val first = record(); ledger.markOwnershipCommitted(run, first.key, 2)
        val intent = store.read(run).first()
        store.append(run, "abandoned-after-receipt", "shop.purchase_abandoned", 7, 1, 3,
            buildJsonObject { put("intent_id", intent["event_id"]!!); put("reason", "人工接管") },
            verification = "abandoned", source = "automation_action")
        assertFalse(module.canDismiss(ShoppingTestData.receipt(), 7, 1))
    }

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

    @Test fun `回执商品名的繁日字形与简体写法视为同一个字`() {
        module.recordIntent(ShoppingTestData.item(name = "樱坚牢战衣", id = "relic-2"), 1000, 7, 1, 0)
        module.confirmDispatched()
        // 实机把「樱」读成「櫻」：同一个字的不同字形不能当成另一件商品。
        assertNotNull(record(ShoppingTestData.receipt("櫻坚牢战衣")))
        assertEquals(3, store.read(run).size)
    }

    @Test fun `铁的简繁日字形仅同字归一且不修改证据原文`() {
        for (name in listOf("铁防甲", "鐵防甲", "鉄防甲")) {
            assertTrue(LabyrinthShopItemName.equal("铁防甲", name))
            assertEquals("铁防甲", LabyrinthShopItemName.canonical(name))
        }
        assertFalse(LabyrinthShopItemName.equal("铁防甲", "铁防乙"))
        assertFalse(LabyrinthShopItemName.equal("铁防甲", "铁防甲!"))
        assertFalse(LabyrinthShopItemName.equal("铁防甲", null))
        val read = ShoppingTestData.receipt("鉄防甲").item!!
        assertEquals("购买了鉄防甲。", read.text)
    }

    @Test fun `回执商品名差一个字仍然拒绝`() {
        module.recordIntent(ShoppingTestData.item(name = "樱坚牢战衣", id = "relic-2"), 1000, 7, 1, 0)
        module.confirmDispatched()
        ShoppingTestData.reject { record(ShoppingTestData.receipt("樱坚牢战裙")) }
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

    @Test fun `日志IO失败不生成财务回执且保留意图可重试`() {
        automated()
        ShoppingTestData.withBlockedAppend(run) { ShoppingTestData.reject { record() } }
        assertEquals(2, store.read(run).size); assertFalse(ShoppingTestData.canDismiss(module, ShoppingTestData.receipt()))
        assertTrue(record().automated); assertEquals(3, store.read(run).size)
    }

    @Test fun `重复回执复核持久事实并返回同一个提交`() {
        automated(); val first = record()
        assertEquals(first, record())
        assertEquals(1, store.read(run).count { ShoppingTestData.type(it) == "shop.purchase_completed" })
        assertEquals(setOf("manifest.json", "events.jsonl"), run.directory.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun `回执字段篡改重复接受及持有提交都拒绝关闭返回false`() {
        automated(); val first = record(); corrupt(store.read(run).last())
        ShoppingTestData.reject { record() }
        ShoppingTestData.reject { ledger.markOwnershipCommitted(run, first.key, 2) }
        assertFalse(ShoppingTestData.canDismiss(module, ShoppingTestData.receipt()))
    }

    @Test fun `重启不需要旁路文件即可幂等恢复同一回执`() {
        automated(); val first = record()
        val reopened = LabyrinthShopReceiptLedger(LabyrinthShoppingJournal(root))
        assertEquals(first, reopened.record(run, ShoppingTestData.receipt(), 7, 1, 2))
    }

    @Test fun `意图来源篡改后首次回执拒绝`() {
        automated(); corrupt(store.read(run).first())
        ShoppingTestData.reject { record() }; assertEquals(2, store.read(run).size)
    }

    @Test fun `已保存自动回执仍必须复核意图来源`() {
        automated(); record(); corrupt(store.read(run).first())
        ShoppingTestData.reject { record() }
        assertFalse(ShoppingTestData.canDismiss(module, ShoppingTestData.receipt()))
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
        assertTrue(record().ownershipCommitted)
    }

    @Test fun `不同回执不能沿用已提交旧回执关闭许可`() {
        automated(); val first = record(); ledger.markOwnershipCommitted(run, first.key, 2)
        assertFalse(ShoppingTestData.canDismiss(module, ShoppingTestData.receipt("钢铁护符")))
        assertFalse(ShoppingTestData.canDismiss(module, ShoppingTestData.receipt(before = 1200)))
    }

    @Test fun `持有已提交但后续新意图不能污染旧重复回执归属`() {
        automated(); val first = record(); ledger.markOwnershipCommitted(run, first.key, 2)
        module.recordIntent(ShoppingTestData.item("钢铁护符", "relic-2"), 900, 7, 1, 3)
        val duplicate = record()
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
        ShoppingTestData.reject { ledger.record(run, ShoppingTestData.receipt(), 7, 2, 1) }
    }

    @Test fun `多个确认派发事实不得静默降级成人工回执`() {
        automated(); val dispatch = store.read(run).last()
        store.append(run, "second-confirm", "shop.purchase_confirm_dispatched", 7, 1, 1,
            ShoppingTestData.payload(dispatch),
            verification = "dispatched_only", source = "automation_action")
        ShoppingTestData.reject { record() }; assertEquals(3, store.read(run).size)
    }

    @Test fun `多个持有事实不得当成未提交或重复写入`() {
        automated(); val first = record(); ledger.markOwnershipCommitted(run, first.key, 2)
        val ownership = store.read(run).last()
        store.append(run, "second-ownership", "shop.ownership_committed", 7, 1, 3,
            ShoppingTestData.payload(ownership), source = "ownership_commit")
        ShoppingTestData.reject { ledger.markOwnershipCommitted(run, first.key, 4) }
        assertFalse(ShoppingTestData.canDismiss(module, ShoppingTestData.receipt()))
    }

    @Test fun `重复回执不能使用同节点旧键跨楼层重放`() {
        automated(); record()
        ShoppingTestData.reject { ledger.record(run, ShoppingTestData.receipt(), 7, 2, 2) }
    }

    @Test fun `回执前取消不能保留prepared自动许可`() {
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0)
        ShoppingTestData.prepare(module, 1); val intent = store.read(run).first()
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
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0)
        assertTrue(ShoppingTestData.prepare(module, 1))
        listOf(ShoppingTestData.receipt("高级苍珠手镯"), ShoppingTestData.receipt(spent = 200),
            ShoppingTestData.receipt(before = 1200)).forEach { ShoppingTestData.reject { record(it) } }
        ShoppingTestData.reject { ledger.record(run, ShoppingTestData.receipt(), 8, 1, 2) }
        ShoppingTestData.reject { ledger.record(run, ShoppingTestData.receipt(), 7, 2, 2) }
        assertTrue(record().automated)
    }

    @Test fun `只存在intent不能篡改automated升级为自动提交`() {
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0)
        val saved = record(); assertFalse(saved.automated)
        ShoppingTestData.rewriteEvents(run) { event -> if (ShoppingTestData.type(event) == "shop.purchase_completed")
            JsonObject(event + ("payload" to JsonObject(ShoppingTestData.payload(event) + ("automated" to JsonPrimitive(true))))) else event }
        ShoppingTestData.reject { module.pendingOwnership() }
        ShoppingTestData.reject { ledger.markOwnershipCommitted(run, saved.key, 2) }
    }

    @Test fun `已拒绝prepared不能篡改回执自动属性恢复持有`() {
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0)
        ShoppingTestData.prepare(module, 1); module.rejectConfirmation(2); record()
        ShoppingTestData.rewriteEvents(run) { event -> if (ShoppingTestData.type(event) == "shop.purchase_completed")
            JsonObject(event + ("payload" to JsonObject(ShoppingTestData.payload(event) + ("automated" to JsonPrimitive(true))))) else event }
        ShoppingTestData.reject { module.pendingOwnership() }
    }

    @Test fun `回执之后才保存的dispatch不能作为自动完成证据`() {
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0)
        record(); val intent = store.read(run).first()
        store.append(run, "late-confirm", "shop.purchase_confirm_dispatched", 7, 1, 2,
            buildJsonObject { put("intent_id", intent["event_id"]!!) },
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
            module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0)
            ShoppingTestData.prepare(module, 1); module.acceptReceipt(ShoppingTestData.receipt(), 7, 1, 2)
            ShoppingTestData.rewriteEvents(current) { event -> if (ShoppingTestData.type(event) == "shop.purchase_completed")
                JsonObject(event + ("payload" to mutate(ShoppingTestData.payload(event)))) else event }
            ShoppingTestData.reject { module.pendingOwnership() }
        }
        listOf("node_id", "floor").forEachIndexed { index, field ->
            val current = store.open(1, 200L + index, null, 0); module.open(1, current.enterId, null, 0)
            module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0)
            ShoppingTestData.prepare(module, 1); module.acceptReceipt(ShoppingTestData.receipt(), 7, 1, 2)
            ShoppingTestData.rewriteEvents(current) { event -> if (ShoppingTestData.type(event) == "shop.purchase_completed")
                JsonObject(event + (field to JsonPrimitive(8))) else event }
            ShoppingTestData.reject { module.pendingOwnership() }
        }
    }

    @Test fun `pendingOwnership拒绝prepared身份来源状态或事实摘要篡改`() {
        listOf("node_id" to JsonPrimitive(8), "floor" to JsonPrimitive(2),
            "source" to JsonPrimitive("actual_screen"), "verification_status" to JsonPrimitive("dispatched_only"),
            "enter_id" to JsonPrimitive(-1)).forEachIndexed { index, mutation ->
            val current = store.open(1, 300L + index, null, 0); module.open(1, current.enterId, null, 0)
            module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0)
            ShoppingTestData.prepare(module, 1); module.acceptReceipt(ShoppingTestData.receipt(), 7, 1, 2)
            ShoppingTestData.rewriteEvents(current) { event -> if (ShoppingTestData.type(event) == "shop.purchase_confirm_prepared")
                JsonObject(event + mutation) else event }
            ShoppingTestData.reject { module.pendingOwnership() }
        }
    }

    @Test fun `prepared撤回后新prepared对应回执仍验证最新精确编号`() {
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0)
        ShoppingTestData.prepare(module, 1); val first = store.read(run).last()
        module.rejectConfirmation(2); ShoppingTestData.prepare(module, 3); assertTrue(record().automated)
        ShoppingTestData.rewriteEvents(run) { event -> if (ShoppingTestData.type(event) == "shop.purchase_completed")
            JsonObject(event + ("payload" to JsonObject(ShoppingTestData.payload(event) + ("confirmation_id" to first["event_id"]!!)))) else event }
        ShoppingTestData.reject { module.pendingOwnership() }
    }

    @Test fun `未派发但关联意图的重复回执也必须复核意图来源`() {
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 0)
        assertFalse(record().automated); corrupt(store.read(run).first())
        ShoppingTestData.reject { record() }
    }
}
