package com.landosol.toolbox.labyrinth.shop

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LabyrinthShopBalanceLedgerTest {
    @get:Rule val temp = TemporaryFolder()
    private val root by lazy { temp.newFolder("balance") }
    private val store by lazy { LabyrinthShoppingJournal(root) }
    private val run by lazy { store.open(1, 10, null, 0) }
    private val ledger by lazy { LabyrinthShopBalanceLedger(store) }
    private fun observe(balance: Long, node: Long? = 7) = ledger.observe(run, balance, node, 1, 1,
        balance.toString(), 42, ShoppingTestData.encode)
    private fun last() = store.read(run).last()
    private fun payload() = ShoppingTestData.payload(last())
    private fun source(type: String, body: JsonObject = buildJsonObject {}, withEvidence: Boolean = true,
        verification: String = if (type == "battle.completed") "verified" else "dispatched_only",
        origin: String = if (type == "battle.completed") "actual_screen" else "automation_action"): JsonObject {
        val key = "fact-${UUID.randomUUID()}"
        val evidence = if (withEvidence) store.evidence(run, key, ShoppingTestData.encode) else null
        return store.append(run, key, type, 7, 1, 1, body, evidence, verification, origin)
    }
    private fun refresh(price: Long? = 300, balance: Long = 1000) = source("shop.refresh_dispatched", buildJsonObject {
        put("refresh_price", JsonPrimitive(price)); put("balance_before", balance)
    })
    private fun win() = source("battle.completed", buildJsonObject { put("outcome", "won") })
    private fun assertUnattributed(delta: Long) {
        assertEquals("currency.observed", ShoppingTestData.type(last()))
        assertEquals(delta, payload()["unattributed_delta"]!!.jsonPrimitive.long)
        assertEquals(JsonNull, payload()["received"]); assertEquals(JsonNull, payload()["spent"])
    }

    @Test fun `首次观察只有余额事实且不存在奖励归因`() {
        observe(1000)
        assertEquals("currency.observed", ShoppingTestData.type(last()))
        assertEquals(JsonNull, payload()["balance_before"]); assertEquals(JsonNull, payload()["received"])
        assertEquals(1000L, ledger.verifiedBalance(run))
    }

    @Test fun `负余额拒绝并不创建证据或事件`() {
        ShoppingTestData.reject { observe(-1) }; assertTrue(store.read(run).isEmpty())
    }

    @Test fun `相同节点余额观察幂等但重复时仍复核旧证据`() {
        observe(1000)
        ledger.observe(run, 1000, 7, 1, 2, "1000", 43, ShoppingTestData.neverEncode)
        assertEquals(1, store.read(run).size)
        File(run.directory, last()["evidence_ref"]!!.jsonPrimitive.content).writeBytes(byteArrayOf(9))
        ShoppingTestData.reject { observe(1000) }
        ShoppingTestData.reject { ledger.verifiedBalance(run) }
    }

    @Test fun `跨节点相同余额保存新的观察锚点但不记奖励`() {
        observe(1000); observe(1000, 8)
        assertEquals(2, store.read(run).size); assertEquals("currency.observed", ShoppingTestData.type(last()))
        assertEquals(JsonNull, payload()["received"])
    }

    @Test fun `无任何战斗事件余额增加不能臆造奖励`() {
        observe(1000); observe(1300); assertUnattributed(300)
    }

    @Test fun `无任何来源余额减少不能臆造刷新费用`() {
        observe(1000); observe(700); assertUnattributed(-300)
    }

    @Test fun `严格单一实际刷新来源按实际费用归因`() {
        observe(1000); refresh(650); observe(350)
        assertEquals("shop.refreshed", ShoppingTestData.type(last()))
        assertEquals(650L, payload()["spent"]!!.jsonPrimitive.long)
        assertEquals("single_source_balance_interval", payload()["attribution"]!!.jsonPrimitive.content)
    }

    @Test fun `缺少刷新费用不能回退300`() {
        observe(1000); refresh(null); observe(700); assertUnattributed(-300)
    }

    @Test fun `刷新费用为零或负数不允许匹配推断`() {
        observe(1000); refresh(-300); observe(1300); assertUnattributed(300)
    }

    @Test fun `刷新前余额与锚点不一致不能只按差额归因`() {
        observe(1000); refresh(300, 1200); observe(700); assertUnattributed(-300)
    }

    @Test fun `实际刷新金额与净差额不一致保持未归因`() {
        observe(1000); refresh(650); observe(700); assertUnattributed(-300)
    }

    @Test fun `两次刷新即使总额正好匹配也不是单一来源`() {
        observe(1000); refresh(100); refresh(200); observe(700); assertUnattributed(-300)
        assertEquals(2, payload()["interval_event_ids"]!!.jsonArray.size)
    }

    @Test fun `刷新混合胜利不得把净差额当刷新或战斗奖励`() {
        observe(1000); refresh(300); win(); observe(700); assertUnattributed(-300)
    }

    @Test fun `刷新混合事件选择不得归因`() {
        observe(1000); refresh(300); source("event.choice_dispatched"); observe(700); assertUnattributed(-300)
    }

    @Test fun `只有单个耐久已核实胜利才可推断净奖励`() {
        observe(1000); win(); observe(1250)
        assertEquals("battle.reward", ShoppingTestData.type(last()))
        assertEquals(250L, payload()["received"]!!.jsonPrimitive.long)
    }

    @Test fun `战斗失败不能推断奖励`() {
        observe(1000); source("battle.completed", buildJsonObject { put("outcome", "lost") })
        observe(1250); assertUnattributed(250)
    }

    @Test fun `多次胜利不能分摊或猜测奖励`() {
        observe(1000); win(); win(); observe(1250); assertUnattributed(250)
    }

    @Test fun `未核实战斗意图即使写了won也不是事实`() {
        observe(1000); source("battle.completed", buildJsonObject { put("outcome", "won") },
            verification = "intent_only", origin = "automation_intent")
        observe(1250); assertUnattributed(250)
    }

    @Test fun `有战斗开始但无胜利事实不能推断奖励`() {
        observe(1000); source("battle.started"); observe(1250); assertUnattributed(250)
    }

    @Test fun `单次事件选择是单一净额来源不声称知道毛额`() {
        observe(1000); source("event.choice_dispatched"); observe(900)
        assertEquals("event.reward", ShoppingTestData.type(last()))
        assertEquals(100L, payload()["spent"]!!.jsonPrimitive.long)
        assertEquals("net_balance_change", payload()["amount_basis"]!!.jsonPrimitive.content)
    }

    @Test fun `同节点多次事件选择也不能视为单一来源`() {
        observe(1000); source("event.choice_dispatched"); source("event.choice_dispatched")
        observe(1250); assertUnattributed(250)
        assertFalse(payload()["gross_amounts_known"]!!.jsonPrimitive.boolean)
    }

    @Test fun `未知金融事件或购买确认派发使区间来源不确定`() {
        observe(1000); refresh(300); source("shop.purchase_confirm_dispatched")
        observe(700); assertUnattributed(-300)
    }

    @Test fun `没有原图的刷新事实不能归因`() {
        observe(1000)
        source("shop.refresh_dispatched", buildJsonObject { put("refresh_price", 300); put("balance_before", 1000) }, false)
        observe(700); assertUnattributed(-300)
    }

    @Test fun `来源原图篡改只能保存未归因新观察`() {
        observe(1000); val fact = refresh(300)
        File(run.directory, fact["evidence_ref"]!!.jsonPrimitive.content).writeBytes(byteArrayOf(9))
        observe(700); assertUnattributed(-300)
    }

    @Test fun `派发后余额未变化仍记未归因观察并消费区间`() {
        observe(1000); refresh(300); observe(1000)
        assertEquals(3, store.read(run).size); assertEquals("currency.observed", ShoppingTestData.type(last()))
        observe(700); assertUnattributed(-300)
    }

    @Test fun `余额新原图失败不能前移锚点或吞掉来源`() {
        observe(1000); refresh(300)
        ShoppingTestData.reject { ledger.observe(run, 700, 7, 1, 2, "700", 43, { false }) }
        assertEquals(2, store.read(run).size); assertNull(ledger.verifiedBalance(run))
        observe(700); assertEquals("shop.refreshed", ShoppingTestData.type(last()))
    }

    @Test fun `上一锚点原图篡改不得用新观察悄悄修复`() {
        observe(1000); val original = last()
        File(run.directory, original["evidence_ref"]!!.jsonPrimitive.content).writeBytes(byteArrayOf(9))
        ShoppingTestData.reject { observe(900) }; assertEquals(1, store.read(run).size)
    }

    @Test fun `财务回执之后刷新只核对之后的净变化不重复扣购买`() {
        LabyrinthShopReceiptLedger(store).record(run, ShoppingTestData.receipt(), 7, 1, 1, ShoppingTestData.encode)
        refresh(300, 900); observe(600)
        assertEquals("shop.refreshed", ShoppingTestData.type(last()))
        assertEquals(900L, payload()["balance_before"]!!.jsonPrimitive.long)
        assertEquals(300L, payload()["spent"]!!.jsonPrimitive.long)
    }

    @Test fun `prepared未知重启使旧余额失效确定拒绝后恢复`() {
        observe(1000)
        val module = LabyrinthShoppingModule(store).also { it.open(1, 10, null, 0) }
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 1, ShoppingTestData.encode)
        module.prepareConfirmation(2); assertNull(ledger.verifiedBalance(run))
        assertNull(LabyrinthShopBalanceLedger(LabyrinthShoppingJournal(root)).verifiedBalance(run))
        module.rejectConfirmation(3); assertEquals(1000L, ledger.verifiedBalance(run))
        module.cancelIntent(4); assertEquals(1000L, ledger.verifiedBalance(run))
    }

    @Test fun `伪造未知异常或缺失原图的拒绝不能恢复旧余额`() {
        val mutations = listOf<(JsonObject) -> JsonObject>(
            { e -> JsonObject(e + ("verification_status" to JsonPrimitive("unknown"))) },
            { e -> JsonObject(e + ("source" to JsonPrimitive("actual_screen"))) },
            { e -> JsonObject(e + ("evidence_ref" to JsonNull)) },
            { e -> JsonObject(e + ("node_id" to JsonPrimitive(8))) },
            { e -> JsonObject(e + ("floor" to JsonPrimitive(2))) },
            { e -> JsonObject(e + ("payload" to JsonObject(ShoppingTestData.payload(e) + ("result" to JsonPrimitive("exception"))))) },
            { e -> JsonObject(e + ("payload" to JsonObject(ShoppingTestData.payload(e) + ("prepared_id" to JsonPrimitive("other-prepare"))))) }
        )
        mutations.forEachIndexed { index, mutate ->
            val current = store.open(1, 100L + index, null, 0)
            ledger.observe(current, 1000, 7, 1, 0, "1000", 42, ShoppingTestData.encode)
            val module = LabyrinthShoppingModule(store).also { it.open(1, current.enterId, null, 0) }
            module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 1, ShoppingTestData.encode)
            module.prepareConfirmation(2); module.rejectConfirmation(3)
            ShoppingTestData.rewriteEvents(current) { event ->
                if (ShoppingTestData.type(event) == "shop.purchase_confirm_rejected") mutate(event) else event }
            ShoppingTestData.reject { ledger.verifiedBalance(current) }
            ShoppingTestData.reject { module.cancelIntent(4) }
            ShoppingTestData.reject { module.prepareConfirmation(5) }
        }
    }

    @Test fun `拒绝原图摘要篡改重启不得授权旧余额`() {
        observe(1000)
        val module = LabyrinthShoppingModule(store).also { it.open(1, 10, null, 0) }
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 1, ShoppingTestData.encode)
        module.prepareConfirmation(2); module.rejectConfirmation(3)
        ShoppingTestData.rewriteEvents(run) { event -> if (ShoppingTestData.type(event) == "shop.purchase_confirm_rejected")
            JsonObject(event + ("evidence_sha256" to JsonPrimitive("forged"))) else event }
        ShoppingTestData.reject { LabyrinthShopBalanceLedger(LabyrinthShoppingJournal(root)).verifiedBalance(run) }
    }

    @Test fun `prepared撤回后其它未对账财务来源仍使旧余额失效`() {
        observe(1000)
        val module = LabyrinthShoppingModule(store).also { it.open(1, 10, null, 0) }
        module.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 1, ShoppingTestData.encode)
        module.prepareConfirmation(2); module.rejectConfirmation(3)
        refresh(100); assertNull(ledger.verifiedBalance(run))
        observe(900); assertUnattributed(-100)
    }

    private fun preparedRefresh(): LabyrinthShoppingModule {
        observe(1000)
        return LabyrinthShoppingModule(store).also {
            it.open(1, 10, null, 0)
            assertTrue(it.prepareRefresh("attempt-1", ShoppingTestData.read("250"), ShoppingTestData.wallet(1000), 7, 1, 2, ShoppingTestData.encode))
        }
    }

    @Test fun `刷新prepared真实单一扣费可在没有executor回调时严格settle`() {
        val module = preparedRefresh()
        observe(750)
        assertEquals("shop.refresh_settled", ShoppingTestData.type(last()))
        assertEquals(250L, payload()["spent"]!!.jsonPrimitive.long)
        assertEquals(750L, ledger.verifiedBalance(run)); assertNull(module.pendingRefresh())
        val size = store.read(run).size
        observe(750); module.refreshDispatched("attempt-1", 3)
        assertEquals(size, store.read(run).size)
    }

    @Test fun `prepared后同节点同楼层原余额双读不污染区间随后只settle一次`() {
        val module = preparedRefresh()
        val events = store.read(run)
        val anchor = ledger.lastAnchor(run)
        repeat(2) {
            ledger.observe(run, 1000, 7, 1, 3, "1000", 43L + it, ShoppingTestData.neverEncode)
            assertEquals(events, store.read(run)); assertEquals(anchor, ledger.lastAnchor(run))
            assertNull(module.verifiedBalance); assertNotNull(module.pendingRefresh())
            assertEquals(1, module.refreshCount(7, 1))
        }
        val reopened = LabyrinthShoppingModule(LabyrinthShoppingJournal(root)).also { it.open(1, 10, null, 4) }
        assertNull(reopened.verifiedBalance); assertNotNull(reopened.pendingRefresh())
        reopened.observeBalance(ShoppingTestData.wallet(750), 7, 1, 5, ShoppingTestData.encode)
        observe(750); reopened.refreshDispatched("attempt-1", 6)
        assertEquals(750L, reopened.verifiedBalance); assertNull(reopened.pendingRefresh())
        assertEquals(1, store.read(run).count { ShoppingTestData.type(it) == "shop.refresh_settled" })
        assertEquals(1, store.read(run).count { ShoppingTestData.type(it) == "currency.observed" })
        assertEquals(250L, payload()["spent"]!!.jsonPrimitive.long)
    }

    @Test fun `未决刷新的同节点第三余额只记诊断跨节点仍记锚点`() {
        for ((index, change) in listOf("差额", "节点", "楼层", "同余额跨节点", "同余额跨楼层").withIndex()) {
            val current = store.open(1, 200L + index, null, 0)
            val module = LabyrinthShoppingModule(store).also { it.open(1, current.enterId, null, 0) }
            module.observeBalance(ShoppingTestData.wallet(1000), 7, 1, 1, ShoppingTestData.encode)
            module.prepareRefresh("attempt-$index", ShoppingTestData.read("250"),
                ShoppingTestData.wallet(1000), 7, 1, 2, ShoppingTestData.encode)
            val before = store.read(current).size
            module.observeBalance(ShoppingTestData.wallet(if (change == "差额") 800 else if (change.startsWith("同余额")) 1000 else 750),
                if (change.endsWith("节点")) 8 else 7, if (change.endsWith("楼层")) 2 else 1, 3, ShoppingTestData.encode)
            val observed = store.read(current).last()
            if (change == "差额") {
                // 同节点同楼层的第三个数值：留诊断，绝不落成锚点。
                assertEquals("currency.observed_pending_refresh", ShoppingTestData.type(observed))
                assertEquals(800L, ShoppingTestData.payload(observed)["balance_observed"]!!.jsonPrimitive.long)
                assertNull(ShoppingTestData.payload(observed)["spent"])
                assertFalse(store.read(current).drop(before).any { ShoppingTestData.type(it) == "currency.observed" })
            } else {
                // 换了节点或楼层：那是另一处的真实余额事实，仍要落成锚点。
                assertEquals("currency.observed", ShoppingTestData.type(observed))
                assertEquals(JsonNull, ShoppingTestData.payload(observed)["spent"])
            }
            assertNull(module.verifiedBalance); assertNotNull(module.pendingRefresh())
            module.observeBalance(ShoppingTestData.wallet(750), 7, 1, 4, ShoppingTestData.encode)
            if (change == "差额") {
                assertEquals(750L, module.verifiedBalance); assertNull(module.pendingRefresh())
                assertEquals(1, store.read(current).count { ShoppingTestData.type(it) == "shop.refresh_settled" })
            } else {
                assertNull(module.verifiedBalance); assertNotNull(module.pendingRefresh())
                assertFalse(store.read(current).any { ShoppingTestData.type(it) == "shop.refresh_settled" })
            }
        }
    }

    @Test fun `prepared混合胜利事件选择或未知财务不能归因刷新`() {
        for ((index, kind) in listOf("battle.completed", "event.choice_dispatched", "unknown.financial").withIndex()) {
            val current = store.open(1, 220L + index, null, 0)
            val module = LabyrinthShoppingModule(store).also { it.open(1, current.enterId, null, 0) }
            module.observeBalance(ShoppingTestData.wallet(1000), 7, 1, 1, ShoppingTestData.encode)
            module.prepareRefresh("attempt-$index", ShoppingTestData.read("250"), ShoppingTestData.wallet(1000), 7, 1, 2, ShoppingTestData.encode)
            val evidence = store.evidence(current, "other-$index", ShoppingTestData.encode)
            store.append(current, "other-$index", kind, 7, 1, 3, buildJsonObject { put("outcome", "won") }, evidence)
            module.observeBalance(ShoppingTestData.wallet(1000), 7, 1, 4, ShoppingTestData.encode)
            assertEquals("currency.observed_pending_refresh", ShoppingTestData.type(store.read(current).last()))
            assertNull(module.verifiedBalance); assertNotNull(module.pendingRefresh())
            module.observeBalance(ShoppingTestData.wallet(750), 7, 1, 5, ShoppingTestData.encode)
            val observed = store.read(current).last()
            // 区间里混了别的财务事件，250 的差额不能算作这次刷新。
            assertEquals("currency.observed_pending_refresh", ShoppingTestData.type(observed))
            assertNull(ShoppingTestData.payload(observed)["spent"])
            assertNull(module.verifiedBalance); assertNotNull(module.pendingRefresh())
            assertFalse(store.read(current).any { ShoppingTestData.type(it) == "shop.refresh_settled" })
        }
    }

    @Test fun `刷新prepared拒绝或settled保存证据篡改恢复必须fail closed`() {
        val mutations = listOf<(JsonObject) -> JsonObject>(
            { e -> JsonObject(e + ("evidence_sha256" to JsonPrimitive("forged"))) },
            { e -> JsonObject(e + ("node_id" to JsonPrimitive(8))) },
            { e -> JsonObject(e + ("verification_status" to JsonPrimitive("unknown"))) },
            { e -> JsonObject(e + ("payload" to JsonObject(ShoppingTestData.payload(e) + ("prepared_id" to JsonPrimitive("other"))))) }
        )
        for ((index, mutate) in mutations.withIndex()) for (settled in listOf(false, true)) {
            val current = store.open(1, 240L + index * 2 + if (settled) 1 else 0, null, 0)
            val module = LabyrinthShoppingModule(store).also { it.open(1, current.enterId, null, 0) }
            module.observeBalance(ShoppingTestData.wallet(1000), 7, 1, 1, ShoppingTestData.encode)
            module.prepareRefresh("attempt-1", ShoppingTestData.read("250"), ShoppingTestData.wallet(1000), 7, 1, 2, ShoppingTestData.encode)
            if (settled) module.observeBalance(ShoppingTestData.wallet(750), 7, 1, 3, ShoppingTestData.encode)
            else module.rejectRefresh("attempt-1", 3)
            ShoppingTestData.rewriteEvents(current) { if (ShoppingTestData.type(it) ==
                if (settled) "shop.refresh_settled" else "shop.refresh_rejected") mutate(it) else it }
            ShoppingTestData.reject { module.verifiedBalance }
            ShoppingTestData.reject { module.pendingRefresh() }
        }
    }

    @Test fun `prepared原余额双读后迟到dispatch再见扣费只严格settle一次`() {
        val module = preparedRefresh()
        val events = store.read(run)
        observe(1000)
        assertEquals(events, store.read(run))
        assertNull(module.verifiedBalance); assertNotNull(module.pendingRefresh())
        module.refreshDispatched("attempt-1", 3)
        ledger.observe(run, 1000, 7, 1, 4, "1000", 43, ShoppingTestData.neverEncode)
        observe(750)
        assertEquals("shop.refresh_settled", ShoppingTestData.type(last()))
        assertEquals(250L, payload()["spent"]!!.jsonPrimitive.long)
        assertEquals(2, payload()["interval_event_ids"]!!.jsonArray.size)
        assertEquals(750L, module.verifiedBalance); assertNull(module.pendingRefresh())
        val settled = store.read(run)
        observe(750); module.refreshDispatched("attempt-1", 5)
        assertEquals(settled, store.read(run))
        assertEquals(1, settled.count { ShoppingTestData.type(it) == "shop.refresh_settled" })
        assertFalse(settled.any { ShoppingTestData.type(it) == "shop.refreshed" })
    }

    @Test fun `真实不同余额先记诊断迟到dispatch后正确扣费仍只结算一次`() {
        val module = preparedRefresh()
        observe(800)
        assertEquals("currency.observed_pending_refresh", ShoppingTestData.type(last()))
        assertNull(module.verifiedBalance); assertNotNull(module.pendingRefresh())
        module.refreshDispatched("attempt-1", 3)
        observe(750)
        assertEquals("shop.refresh_settled", ShoppingTestData.type(last()))
        assertEquals(250L, payload()["spent"]!!.jsonPrimitive.long)
        assertEquals(750L, module.verifiedBalance); assertNull(module.pendingRefresh())
        assertEquals(1, store.read(run).count { ShoppingTestData.type(it) == "shop.refresh_settled" })
        assertFalse(store.read(run).any { ShoppingTestData.type(it) == "shop.refreshed" })
    }

    @Test fun `prepared原余额缺少独立双读匹配或证据不得no op`() {
        val reads = listOf(ShoppingTestData.read("1000", 1), ShoppingTestData.read("800"),
            ShoppingTestData.read(null), ShoppingTestData.read("1000"))
        for ((index, read) in reads.withIndex()) {
            val current = store.open(1, 260L + index, null, 0)
            val module = LabyrinthShoppingModule(store).also { it.open(1, current.enterId, null, 0) }
            module.observeBalance(ShoppingTestData.wallet(1000), 7, 1, 1, ShoppingTestData.encode)
            module.prepareRefresh("attempt-$index", ShoppingTestData.read("250"), ShoppingTestData.wallet(1000), 7, 1, 2, ShoppingTestData.encode)
            val before = store.read(current).size
            ledger.observe(current, 1000, 7, 1, 3, read.text, if (index == 3) null else read.evidenceId,
                ShoppingTestData.encode, read.confirmations)
            // 不合格的读数既不能当成「同余额无变化」，也不能挪动锚点。
            assertFalse(store.read(current).drop(before).any { ShoppingTestData.type(it) == "currency.observed" })
            assertNull(module.verifiedBalance); assertNotNull(module.pendingRefresh())
            module.observeBalance(ShoppingTestData.wallet(750), 7, 1, 4, ShoppingTestData.encode)
            assertEquals(750L, module.verifiedBalance); assertNull(module.pendingRefresh())
            assertEquals(1, store.read(current).count { ShoppingTestData.type(it) == "shop.refresh_settled" })
        }
    }

    @Test fun `刷新settled余额证据写入失败仍未决且重试只结算一次`() {
        val module = preparedRefresh()
        ShoppingTestData.reject { module.observeBalance(ShoppingTestData.wallet(750), 7, 1, 3) { false } }
        assertNull(module.verifiedBalance); assertNotNull(module.pendingRefresh())
        observe(750); observe(750)
        assertEquals(1, store.read(run).count { ShoppingTestData.type(it) == "shop.refresh_settled" })
    }

    @Test fun `重启读取余额与未对账区间不依赖内存`() {
        observe(1000); refresh(400)
        val reopened = LabyrinthShopBalanceLedger(LabyrinthShoppingJournal(root))
        assertNull(reopened.verifiedBalance(run))
        reopened.observe(run, 600, 7, 1, 3, "600", 43, ShoppingTestData.encode)
        assertEquals("shop.refreshed", ShoppingTestData.type(last()))
        assertEquals(600L, reopened.verifiedBalance(run))
    }
}
