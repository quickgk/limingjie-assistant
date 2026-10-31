package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.labyrinth.vision.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal object ShoppingTestData {
    val rect = EntryPixelRect(0, 0, 40, 20)
    val raw = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3, 4)
    val encode: (FileOutputStream) -> Boolean = { it.write(raw); true }
    val neverEncode: (FileOutputStream) -> Boolean = { error("幂等重试不应再次编码") }
    fun read(text: String?, confirmations: Int = 2) = LabyrinthShopReceiptRead(text, 42, confirmations)
    fun wallet(balance: Long) = LabyrinthCurrencyObservation(read(balance.toString()))
    fun receipt(name: String = "苍珠手镯", spent: Long = 100, before: Long = 1000, after: Long = before - spent) =
        LabyrinthShopPurchaseReceiptObservation(read("购买完成"), read("购买了$name。"),
            read("消耗阿尔法金币×$spent"), read(before.toString()), read(after.toString()), read("确认"), rect)
    fun item(name: String = "苍珠手镯", id: String = "relic-1", price: Long = 100) = LabyrinthShopItemMatch(
        slotId = "slot-1", screenRect = rect, buyButtonRect = rect, buyButtonScore = 1.0,
        status = LabyrinthShopItemStatus.AVAILABLE, kind = LabyrinthShopItemKind.RELIC,
        relicMatch = LabyrinthRelicMatch("slot-1", id, name, "强化", "1", "效果", 1.0, rect, iconRect = rect),
        titleText = name, priceRead = read(price.toString()))
    fun role(choice: Boolean = false, role: String = "攻击") = item().copy(
        kind = LabyrinthShopItemKind.ROLE_IMPRINT, relicMatch = null,
        titleText = "${role}型职能的${if (choice) "选择" else "随机"}印记",
        roleImprintLabel = "${role}型职能印记", choiceRoleImprint = choice)
    fun payload(event: JsonObject) = event["payload"]!!.jsonObject
    fun type(event: JsonObject) = event["type"]!!.jsonPrimitive.content
    fun rewriteEvents(run: LabyrinthShoppingJournal.Run, transform: (JsonObject) -> JsonObject) {
        val file = File(run.directory, "events.jsonl")
        val events = file.readLines().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }
        file.writeText(events.joinToString("\n", postfix = "\n") { transform(it).toString() })
    }
    fun reject(block: () -> Unit) {
        try { block() } catch (_: IllegalStateException) { return } catch (_: IllegalArgumentException) { return }
        fail("应拒绝不安全或不一致操作")
    }
}

class LabyrinthShoppingModuleIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val root by lazy { temp.newFolder("shopping") }
    private val store by lazy { LabyrinthShoppingJournal(root) }
    private val module by lazy { LabyrinthShoppingModule(store).also { it.open(1, 100, "batch", 1) } }
    private fun events() = store.read(store.open(1, 100, null, 2))
    private fun observe(balance: Long = 1000) = module.observeBalance(ShoppingTestData.wallet(balance), 7, 1, 3, ShoppingTestData.encode)
    private fun intent(item: LabyrinthShopItemMatch = ShoppingTestData.item(), balance: Long = 1000, node: Long? = 7) =
        module.recordIntent(item, balance, node, 1, 4, ShoppingTestData.encode)
    private fun accept(receipt: LabyrinthShopPurchaseReceiptObservation = ShoppingTestData.receipt()) =
        module.acceptReceipt(receipt, 7, 1, 6, ShoppingTestData.encode)
    private fun restart() = LabyrinthShoppingModule(LabyrinthShoppingJournal(root)).also { it.open(1, 100, "changed", 10) }

    @Test fun `真实文件意图派发回执持有提交关闭完整流程`() {
        observe(); intent(); module.confirmDispatched()
        assertNull(module.verifiedBalance)
        val result = accept()
        assertEquals("relic-1", result.itemId); assertTrue(result.automated); assertFalse(result.ownershipCommitted)
        assertFalse(module.canDismiss(ShoppingTestData.receipt()))
        module.markOwnershipCommitted(result.key, 7)
        assertTrue(module.canDismiss(ShoppingTestData.receipt()))
        assertEquals(900L, module.verifiedBalance)
        assertEquals(listOf("currency.observed", "shop.purchase_intent", "shop.purchase_confirm_dispatched",
            "shop.purchase_completed", "shop.ownership_committed"), events().map(ShoppingTestData::type))
        events().filter { it["evidence_ref"] != JsonNull }.forEach {
            assertTrue(store.evidenceValid(store.open(1, 100, null, 2), it))
        }
    }

    @Test fun `意图重试与OCR证据编号变化不会新建意图`() {
        val first = intent()
        val changed = ShoppingTestData.item().copy(priceRead = LabyrinthShopReceiptRead("100", 999, 3))
        assertEquals(first, module.recordIntent(changed, 1000, 7, 1, 50, ShoppingTestData.neverEncode))
        assertEquals(1, events().size)
    }

    @Test fun `派发耐久且重复通知不重复入账`() {
        intent(); module.confirmDispatched(); module.confirmDispatched()
        assertEquals(1, events().count { ShoppingTestData.type(it) == "shop.purchase_confirm_dispatched" })
    }

    @Test fun `确认派发前重启恢复同一待确认意图`() {
        val first = intent()
        assertEquals(first, restart().recordIntent(ShoppingTestData.item(), 1000, 7, 1, 20, ShoppingTestData.neverEncode))
    }

    @Test fun `确认派发后重启从事件恢复自动回执关系`() {
        intent(); module.confirmDispatched()
        val recovered = restart()
        val result = recovered.acceptReceipt(ShoppingTestData.receipt(), 7, 1, 20, ShoppingTestData.encode)
        assertTrue(result.automated); assertFalse(recovered.canDismiss(ShoppingTestData.receipt()))
        recovered.markOwnershipCommitted(result.key, 21)
        assertTrue(recovered.canDismiss(ShoppingTestData.receipt()))
    }

    @Test fun `回执保存后重启不会double booking且持有可补提交`() {
        intent(); module.confirmDispatched(); val first = accept()
        val recovered = restart()
        assertEquals(first, recovered.acceptReceipt(ShoppingTestData.receipt(), 7, 1, 20, ShoppingTestData.neverEncode))
        recovered.markOwnershipCommitted(first.key, 21); recovered.markOwnershipCommitted(first.key, 22)
        assertEquals(1, events().count { ShoppingTestData.type(it) == "shop.purchase_completed" })
        assertEquals(1, events().count { ShoppingTestData.type(it) == "shop.ownership_committed" })
        assertTrue(restart().canDismiss(ShoppingTestData.receipt()))
    }

    @Test fun `未派发意图的匹配回执不冒充自动购买`() {
        intent(); val result = accept()
        assertFalse(result.automated)
        module.markOwnershipCommitted(result.key, 7)
        assertFalse(module.canDismiss(ShoppingTestData.receipt()))
        ShoppingTestData.reject { module.confirmDispatched() }
    }

    @Test fun `人工回执可保存但即使持有已提交也不能自动关闭`() {
        val result = accept()
        assertNull(result.itemId); assertFalse(result.automated)
        assertEquals("observed_without_intent", ShoppingTestData.payload(events().single())["association_status"]!!.jsonPrimitive.content)
        module.markOwnershipCommitted(result.key, 7)
        assertFalse(module.canDismiss(ShoppingTestData.receipt()))
    }

    @Test fun `legacy已派发意图必须拒绝取消且重启保留自动关系`() {
        intent(); module.confirmDispatched()
        ShoppingTestData.reject { module.cancelIntent(5) }
        val recovered = restart()
        ShoppingTestData.reject { recovered.cancelIntent(6) }
        val result = recovered.acceptReceipt(ShoppingTestData.receipt(), 7, 1, 8, ShoppingTestData.encode)
        assertTrue(result.automated); assertEquals("relic-1", result.itemId)
        assertFalse(events().any { ShoppingTestData.type(it) == "shop.purchase_intent_cancelled" })
    }

    @Test fun `取消未派发意图重启后不得恢复为自动关系`() {
        intent(); module.cancelIntent(5)
        val recovered = restart()
        val result = recovered.acceptReceipt(ShoppingTestData.receipt(), 7, 1, 8, ShoppingTestData.encode)
        assertFalse(result.automated); assertNull(result.itemId)
        assertFalse(recovered.canDismiss(ShoppingTestData.receipt()))
    }

    @Test fun `取消幂等且之后可以建立新意图`() {
        val first = intent(); module.cancelIntent(5); module.cancelIntent(6)
        val second = intent(); assertNotEquals(first, second)
        assertEquals(1, events().count { ShoppingTestData.type(it) == "shop.purchase_intent_cancelled" })
    }

    @Test fun `取消后新商品不受旧意图匹配污染`() {
        intent(); module.cancelIntent(5)
        intent(ShoppingTestData.item("钢铁护符", "relic-2")); module.confirmDispatched()
        val result = accept(ShoppingTestData.receipt("钢铁护符"))
        assertEquals("relic-2", result.itemId); assertTrue(result.automated)
    }

    @Test fun `跨节点意图变更必须拒绝`() {
        intent(); ShoppingTestData.reject { intent(node = 8) }
        assertEquals(1, events().size)
    }

    @Test fun `跨余额意图变更必须拒绝`() {
        intent(); ShoppingTestData.reject { intent(balance = 1100) }; assertEquals(1, events().size)
    }

    @Test fun `跨商品或价格或槽位意图变更必须拒绝`() {
        intent()
        listOf(ShoppingTestData.item("钢铁护符", "relic-2"), ShoppingTestData.item(price = 200),
            ShoppingTestData.item().copy(slotId = "slot-2")).forEach { changed -> ShoppingTestData.reject { intent(changed) } }
        assertEquals(1, events().size)
    }

    @Test fun `回执跨节点不能静默降级成人工回执`() {
        intent(); module.confirmDispatched()
        ShoppingTestData.reject { module.acceptReceipt(ShoppingTestData.receipt(), 8, 1, 6, ShoppingTestData.encode) }
        assertEquals(2, events().size)
    }

    @Test fun `未核实价格余额不足以及未知身份不允许保存意图`() {
        listOf(ShoppingTestData.item().copy(priceRead = ShoppingTestData.read("100", 1)),
            ShoppingTestData.item().copy(relicMatch = null), ShoppingTestData.item().copy(status = LabyrinthShopItemStatus.DISABLED))
            .forEach { ShoppingTestData.reject { intent(it) } }
        ShoppingTestData.reject { intent(balance = 99) }; assertTrue(events().isEmpty())
    }

    @Test fun `遗物身份不得与印记标题冲突`() {
        ShoppingTestData.reject { intent(ShoppingTestData.item().copy(titleText = "攻击型职能的随机印记")) }
        assertTrue(events().isEmpty())
    }

    @Test fun `未核实观察无入账且不得编码原图`() {
        module.observeBalance(null, 7, 1, 1, ShoppingTestData.neverEncode)
        module.observeBalance(LabyrinthCurrencyObservation(ShoppingTestData.read("1000", 1)), 7, 1, 1, ShoppingTestData.neverEncode)
        assertNull(module.verifiedBalance); assertTrue(events().isEmpty())
    }

    @Test fun `调用方余额与持久余额不一致拒绝购买或刷新`() {
        observe()
        ShoppingTestData.reject { intent(balance = 1200) }
        ShoppingTestData.reject { module.recordRefresh(100, 1200, 7, 1, 6, ShoppingTestData.encode) }
        assertEquals(1, events().size)
    }

    @Test fun `刷新实际费用对账并从重启日志恢复`() {
        observe(2000); module.recordRefresh(650, 2000, 7, 1, 5, ShoppingTestData.encode)
        assertNull(module.verifiedBalance)
        val recovered = restart()
        recovered.observeBalance(ShoppingTestData.wallet(1350), 7, 1, 6, ShoppingTestData.encode)
        assertEquals("shop.refreshed", ShoppingTestData.type(events().last()))
        assertEquals(650L, ShoppingTestData.payload(events().last())["spent"]!!.jsonPrimitive.long)
        assertEquals(1350L, recovered.verifiedBalance)
    }

    @Test fun `刷新差额不符只保存未归因变化不得回退300`() {
        observe(); module.recordRefresh(650, 1000, 7, 1, 5, ShoppingTestData.encode); observe(700)
        assertEquals("currency.observed", ShoppingTestData.type(events().last()))
        assertEquals(-300L, ShoppingTestData.payload(events().last())["unattributed_delta"]!!.jsonPrimitive.long)
        assertEquals(JsonNull, ShoppingTestData.payload(events().last())["spent"])
    }

    @Test fun `刷新重复通知相同参数时刻幂等且原图复核`() {
        observe(); module.recordRefresh(100, 1000, 7, 1, 5, ShoppingTestData.encode)
        module.recordRefresh(100, 1000, 7, 1, 5, ShoppingTestData.neverEncode)
        assertEquals(1, events().count { ShoppingTestData.type(it) == "shop.refresh_dispatched" })
        val event = events().last(); val run = store.open(1, 100, null, 2)
        File(run.directory, event["evidence_ref"]!!.jsonPrimitive.content).writeBytes(byteArrayOf(9))
        ShoppingTestData.reject { module.recordRefresh(100, 1000, 7, 1, 5, ShoppingTestData.encode) }
    }

    @Test fun `购买意图未结束与未对账刷新都阻止后续刷新`() {
        observe(); intent()
        ShoppingTestData.reject { module.recordRefresh(100, 1000, 7, 1, 5, ShoppingTestData.encode) }
        module.cancelIntent(6); module.recordRefresh(100, 1000, 7, 1, 7, ShoppingTestData.encode)
        ShoppingTestData.reject { module.recordRefresh(100, 1000, 7, 1, 8, ShoppingTestData.encode) }
        ShoppingTestData.reject { intent() }
    }

    @Test fun `reset只断开当前会话不删除持久记录`() {
        observe(); intent(); module.reset()
        assertFalse(module.active); assertNull(module.verifiedBalance); assertFalse(module.canDismiss(ShoppingTestData.receipt()))
        assertEquals(2, events().size); assertTrue(restart().active)
        ShoppingTestData.reject { module.confirmDispatched() }
    }

    @Test fun `跨账户与实际enter隔离余额自动关联及回执键`() {
        intent(); module.confirmDispatched(); val first = accept(); module.markOwnershipCommitted(first.key, 7)
        module.open(2, 100, null, 10)
        assertNull(module.verifiedBalance); assertFalse(module.canDismiss(ShoppingTestData.receipt()))
        val otherAccount = accept(); assertFalse(otherAccount.automated); assertNotEquals(first.key, otherAccount.key)
        module.open(1, 101, null, 11)
        val otherEnter = accept(); assertFalse(otherEnter.automated); assertNotEquals(first.key, otherEnter.key)
        module.open(1, 100, "changed-batch", 12); assertTrue(module.canDismiss(ShoppingTestData.receipt()))
    }

    @Test fun `open使用无账户独立命名空间且失败不得残留旧账户`() {
        observe(); module.open(null, 100, null, 10); assertNull(module.verifiedBalance)
        ShoppingTestData.reject { module.open(1, 0, null, 11) }; assertFalse(module.active)
    }

    @Test fun `观察新余额后旧参数也不能重用待确认意图`() {
        observe(); intent(); observe(1200)
        ShoppingTestData.reject { intent() }; ShoppingTestData.reject { intent(balance = 1200) }
        module.cancelIntent(5)
        assertNotNull(intent(balance = 1200))
    }

    @Test fun `空节点与楼层使用稳定enter仍可完整关闭`() {
        module.recordIntent(ShoppingTestData.item(), 1000, null, null, 4, ShoppingTestData.encode)
        module.confirmDispatched()
        val receipt = module.acceptReceipt(ShoppingTestData.receipt(), null, null, 6, ShoppingTestData.encode)
        assertEquals(listOf(PendingOwnership(receipt, "苍珠手镯", null, null)), restart().pendingOwnership())
        module.markOwnershipCommitted(receipt.key, 7)
        assertTrue(restart().canDismiss(ShoppingTestData.receipt()))
    }

    @Test fun `非法实际刷新费用拒绝且不引入默认价格`() {
        listOf(0L, -1L, 1001L).forEach { price ->
            ShoppingTestData.reject { module.recordRefresh(price, 1000, 7, 1, 5, ShoppingTestData.encode) }
        }
        assertTrue(events().isEmpty())
    }

    @Test fun `其他账户不能用旧回执键提前标记持有`() {
        intent(); module.confirmDispatched(); val first = accept()
        module.open(2, 100, null, 10)
        ShoppingTestData.reject { module.markOwnershipCommitted(first.key, 11) }
        assertFalse(module.canDismiss(ShoppingTestData.receipt()))
    }

    @Test fun `prepared落盘先于真实executor且回执可先于dispatch回调`() {
        observe(); intent()
        assertTrue(module.prepareConfirmation(5))
        assertEquals("shop.purchase_confirm_prepared", ShoppingTestData.type(module.events().last()))
        assertNull(module.verifiedBalance)
        // 模拟 executor 尚未返回，回执识别回调已经进入 facade。
        val result = accept()
        assertTrue(result.automated)
        val beforeCallback = module.events()
        module.confirmDispatched(); module.confirmDispatched(); restart().confirmDispatched()
        assertEquals(beforeCallback, module.events())
        assertEquals(900L, module.verifiedBalance)
        module.markOwnershipCommitted(result.key, 7)
        assertTrue(module.canDismiss(ShoppingTestData.receipt()))
    }

    @Test fun `prepared再dispatch再回执的正常次序完整保留`() {
        intent(); assertTrue(module.prepareConfirmation(5)); module.confirmDispatched()
        assertFalse(module.prepareConfirmation(6))
        assertTrue(accept().automated)
        assertEquals(listOf("shop.purchase_intent", "shop.purchase_confirm_prepared",
            "shop.purchase_confirm_dispatched", "shop.purchase_completed"), module.events().map(ShoppingTestData::type))
    }

    @Test fun `executor异常或未知结果重启不得重按确认或取消`() {
        observe(); val first = intent(); assertTrue(module.prepareConfirmation(5))
        val recovered = restart()
        assertEquals(first, recovered.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 20, ShoppingTestData.neverEncode))
        assertFalse(recovered.prepareConfirmation(21)); assertNull(recovered.verifiedBalance)
        ShoppingTestData.reject { recovered.cancelIntent(22) }
        ShoppingTestData.reject { recovered.recordRefresh(100, 1000, 7, 1, 23, ShoppingTestData.encode) }
        assertEquals(3, recovered.events().size)
        assertTrue(recovered.acceptReceipt(ShoppingTestData.receipt(), 7, 1, 24, ShoppingTestData.encode).automated)
    }

    @Test fun `确认实际执行但dispatch日志中断重启仍由prepared关联`() {
        observe(); intent(); assertTrue(module.prepareConfirmation(5))
        val run = store.open(1, 100, null, 2)
        File(run.directory, "events.jsonl").appendText("{\"type\":\"shop.purchase_confirm_dispatched\"")
        val recovered = restart()
        assertFalse(recovered.prepareConfirmation(20)); assertNull(recovered.verifiedBalance)
        val result = recovered.acceptReceipt(ShoppingTestData.receipt(), 7, 1, 21, ShoppingTestData.encode)
        assertTrue(result.automated)
        recovered.confirmDispatched()
        assertEquals(0, recovered.events().count { ShoppingTestData.type(it) == "shop.purchase_confirm_dispatched" })
    }

    @Test fun `真实executor已执行而确认日志IO失败不会撤回prepared`() {
        observe(); intent(); assertTrue(module.prepareConfirmation(5))
        val run = store.open(1, 100, null, 2)
        val log = File(run.directory, "events.jsonl"); val backup = File(run.directory, "saved-events.jsonl")
        Files.move(log.toPath(), backup.toPath())
        assertTrue(log.mkdir())
        try {
            try { module.confirmDispatched(); fail("应报告日志 IO 失败") } catch (_: IOException) { }
        } finally {
            assertTrue(log.delete()); Files.move(backup.toPath(), log.toPath())
        }
        val recovered = restart()
        assertFalse(recovered.prepareConfirmation(20)); assertNull(recovered.verifiedBalance)
        ShoppingTestData.reject { recovered.cancelIntent(21) }
        assertTrue(recovered.acceptReceipt(ShoppingTestData.receipt(), 7, 1, 22, ShoppingTestData.encode).automated)
    }

    @Test fun `确定未执行拒绝先落盘才可取消且旧余额恢复`() {
        observe(); intent(); assertTrue(module.prepareConfirmation(5))
        ShoppingTestData.reject { module.cancelIntent(6) }
        module.rejectConfirmation(7); module.rejectConfirmation(8)
        assertEquals(1000L, restart().verifiedBalance)
        assertEquals(1, events().count { ShoppingTestData.type(it) == "shop.purchase_confirm_rejected" })
        ShoppingTestData.reject { module.confirmDispatched() }
        val recovered = restart(); recovered.cancelIntent(9)
        assertNotNull(recovered.recordIntent(ShoppingTestData.item(), 1000, 7, 1, 10, ShoppingTestData.encode))
        assertTrue(recovered.prepareConfirmation(11))
    }

    @Test fun `确定拒绝后同意图可重新prepare而不复用旧确认编号`() {
        observe(); intent(); assertTrue(module.prepareConfirmation(5)); module.rejectConfirmation(6)
        assertTrue(restart().prepareConfirmation(7)); assertFalse(module.prepareConfirmation(8))
        assertNull(module.verifiedBalance)
        val preparations = events().filter { ShoppingTestData.type(it) == "shop.purchase_confirm_prepared" }
        assertEquals(2, preparations.size); assertNotEquals(preparations[0]["event_id"], preparations[1]["event_id"])
        assertTrue(accept().automated)
    }

    @Test fun `已派发或已完成不能被拒绝释放prepared`() {
        intent(); module.prepareConfirmation(5); module.confirmDispatched()
        ShoppingTestData.reject { module.rejectConfirmation(6) }
        ShoppingTestData.reject { module.cancelIntent(6) }
        accept(); ShoppingTestData.reject { module.rejectConfirmation(7) }
    }

    @Test fun `已撤回prepared的匹配回执不自动恢复持有`() {
        intent(); module.prepareConfirmation(5); module.rejectConfirmation(6)
        assertFalse(accept().automated); assertTrue(restart().pendingOwnership().isEmpty())
        ShoppingTestData.reject { module.confirmDispatched() }
    }

    @Test fun `无需屏幕从保存自动回执恢复精确待提交持有`() {
        intent(); module.prepareConfirmation(5); val result = accept()
        val recovered = restart()
        assertEquals(listOf(PendingOwnership(result, "苍珠手镯", 7, 1)), recovered.pendingOwnership())
        recovered.markOwnershipCommitted(result.key, 7)
        assertTrue(recovered.pendingOwnership().isEmpty())
        recovered.confirmDispatched(); assertEquals(1, events().count { ShoppingTestData.type(it) == "shop.purchase_completed" })
    }

    /**
     * 商品卡读「樱」、回执读「櫻」：简繁差异不是身份不一致，必须仍能恢复持有。
     *
     * 回执提交阶段用的是 [LabyrinthShopItemName.equal] 归一比较，恢复持有阶段也必须是同一口径。
     */
    @Test fun `商品名简繁差异不阻断持有恢复`() {
        intent(ShoppingTestData.item("樱坚牢战衣"))
        module.prepareConfirmation(5)
        val result = accept(ShoppingTestData.receipt("櫻坚牢战衣"))
        assertEquals("relic-1", result.itemId)
        val recovered = restart()
        assertEquals(listOf(PendingOwnership(result, "櫻坚牢战衣", 7, 1)), recovered.pendingOwnership())
    }

    /** 回执裁图左边缘带出的杂散字符不能让商品名整条解析失败。 */
    @Test fun `商品名左侧杂散符号仍能解析`() {
        val noisy = ShoppingTestData.receipt("破守帽退散")
            .copy(item = ShoppingTestData.read("|购买了破守帽退散。"))
        assertEquals("破守帽退散", noisy.itemName)
        val noisyQuote = ShoppingTestData.receipt("破守帽退散")
            .copy(item = ShoppingTestData.read("“购买了破守帽退散。"))
        assertEquals("破守帽退散", noisyQuote.itemName)
    }

    @Test fun `manual和只有意图不恢复但精确自动印记恢复空持有事务`() {
        accept(); assertTrue(module.pendingOwnership().isEmpty())
        module.open(1, 101, null, 10)
        intent(); accept(); assertTrue(module.pendingOwnership().isEmpty())
        module.open(1, 102, null, 20)
        intent(ShoppingTestData.role()); module.prepareConfirmation(21)
        val result = accept(ShoppingTestData.receipt("攻击型职能的随机印记"))
        assertEquals(listOf(PendingOwnership(result, "攻击型职能的随机印记", 7, 1)), module.pendingOwnership())
        module.markOwnershipCommitted(result.key, 22)
        assertTrue(module.pendingOwnership().isEmpty())
    }

    @Test fun `pendingOwnership复核回执原图不得重拍修复`() {
        intent(); module.prepareConfirmation(5); accept()
        val run = store.open(1, 100, null, 2)
        File(run.directory, events().last()["evidence_ref"]!!.jsonPrimitive.content).writeBytes(byteArrayOf(9))
        ShoppingTestData.reject { restart().pendingOwnership() }
    }

    @Test fun `pendingOwnership复核意图与prepared的原图`() {
        intent(); module.prepareConfirmation(5); accept()
        val run = store.open(1, 100, null, 2)
        File(run.directory, events().first()["evidence_ref"]!!.jsonPrimitive.content).writeBytes(byteArrayOf(9))
        ShoppingTestData.reject { restart().pendingOwnership() }
    }

    @Test fun `facade只读接口严格绑定实例当前enter和已保存事件`() {
        observe()
        assertEquals(100L, module.enterId); assertTrue(module.ownsJournal(store))
        assertFalse(module.ownsJournal(LabyrinthShoppingJournal(root)))
        val saved = module.events().single(); assertTrue(module.evidenceValid(saved))
        assertFalse(module.evidenceValid(JsonObject(saved + ("node_id" to JsonPrimitive(8)))))
        module.open(2, 100, null, 10); assertFalse(module.evidenceValid(saved)); assertTrue(module.events().isEmpty())
        module.reset(); assertNull(module.enterId); assertTrue(module.events().isEmpty()); assertTrue(module.pendingOwnership().isEmpty())
        assertFalse(module.evidenceValid(saved))
    }

    @Test fun `异步回执与dispatch并发两种先后均不重复或产生倒序事件`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(8) { index ->
                module.open(1, 200L + index, null, 0)
                intent(); assertTrue(module.prepareConfirmation(5))
                val start = CountDownLatch(1)
                val receipt = pool.submit<ReceiptCommit> { start.await(); accept() }
                val callback = pool.submit { start.await(); module.confirmDispatched() }
                start.countDown(); assertTrue(receipt.get(10, TimeUnit.SECONDS).automated); callback.get(10, TimeUnit.SECONDS)
                val saved = module.events(); val completed = saved.single { ShoppingTestData.type(it) == "shop.purchase_completed" }
                saved.singleOrNull { ShoppingTestData.type(it) == "shop.purchase_confirm_dispatched" }?.let {
                    assertTrue(it["seq"]!!.jsonPrimitive.int < completed["seq"]!!.jsonPrimitive.int)
                }
                assertEquals(1, module.pendingOwnership().size)
            }
        } finally { pool.shutdownNow() }
    }

    private fun prepareRefresh(key: String = "attempt-1", price: Long = 250, balance: Long = 1000) =
        module.prepareRefresh(key, ShoppingTestData.read(price.toString()), ShoppingTestData.wallet(balance), 7, 1, 5, ShoppingTestData.encode)

    @Test fun `刷新prepared早余额settled后迟到dispatch重启通知均不追加事件`() {
        observe(); assertTrue(prepareRefresh())
        assertFalse(prepareRefresh()); assertNull(module.verifiedBalance)
        assertEquals(1, restart().refreshCount(7, 1))
        observe(750)
        assertEquals("shop.refresh_settled", ShoppingTestData.type(events().last()))
        val settled = events()
        module.refreshDispatched("attempt-1", 6); restart().refreshDispatched("attempt-1", 7)
        assertEquals(settled, events()); assertNull(module.pendingRefresh())
        assertEquals(750L, restart().verifiedBalance); assertEquals(1, restart().refreshCount(7, 1))
        assertTrue(module.refreshCycleStart(7, 1) > 0)
        ShoppingTestData.reject { module.rejectRefresh("attempt-1", 8) }
    }

    @Test fun `刷新prepared派发settled每个attempt只计一次`() {
        observe(); assertTrue(prepareRefresh()); module.refreshDispatched("attempt-1", 6)
        module.refreshDispatched("attempt-1", 7)
        assertEquals(1, module.refreshCount(7, 1)); assertEquals(0, module.refreshCycleStart(7, 1))
        restart().observeBalance(ShoppingTestData.wallet(750), 7, 1, 8, ShoppingTestData.encode)
        assertEquals(1, module.refreshCount(7, 1)); assertNull(module.pendingRefresh())
        assertEquals(listOf("currency.observed", "shop.refresh_prepared", "shop.refresh_dispatched", "shop.refresh_settled"),
            events().map(ShoppingTestData::type))
    }

    @Test fun `刷新prepared未知跨重启阻止新购刷新旧API和普通余额授权`() {
        observe(); assertTrue(prepareRefresh()); observe(800)
        val recovered = restart()
        assertNotNull(recovered.pendingRefresh()); assertNull(recovered.verifiedBalance)
        ShoppingTestData.reject { recovered.recordIntent(ShoppingTestData.item(), 800, 7, 1, 10, ShoppingTestData.encode) }
        ShoppingTestData.reject { recovered.prepareRefresh("attempt-2", ShoppingTestData.read("250"), ShoppingTestData.wallet(800), 7, 1, 11, ShoppingTestData.encode) }
        ShoppingTestData.reject { recovered.recordRefresh(250, 800, 7, 1, 12, ShoppingTestData.encode) }
        // 800 只是诊断，不授权任何操作；正确扣费读数仍可结算一次。
        recovered.observeBalance(ShoppingTestData.wallet(750), 7, 1, 13, ShoppingTestData.encode)
        assertNull(recovered.pendingRefresh()); assertEquals(750L, recovered.verifiedBalance)
        assertTrue(recovered.refreshCycleStart(7, 1) > 0)
        assertEquals(1, events().count { ShoppingTestData.type(it) == "shop.refresh_settled" })
    }

    @Test fun `确定刷新reject耐久释放额度余额且拒绝旧attempt迟到Executed`() {
        observe(); prepareRefresh(); module.rejectRefresh("attempt-1", 6); module.rejectRefresh("attempt-1", 7)
        assertEquals(0, restart().refreshCount(7, 1)); assertEquals(1000L, restart().verifiedBalance)
        assertNull(module.pendingRefresh()); assertFalse(prepareRefresh())
        ShoppingTestData.reject { module.refreshDispatched("attempt-1", 8) }
        assertTrue(prepareRefresh("attempt-2")); observe(750)
        assertEquals(1, module.refreshCount(7, 1)); assertNull(module.pendingRefresh())
    }

    @Test fun `刷新prepare前原图或实际双读失败不创建事件`() {
        observe()
        ShoppingTestData.reject { module.prepareRefresh("attempt-1", ShoppingTestData.read("250", 1), ShoppingTestData.wallet(1000), 7, 1, 5, ShoppingTestData.encode) }
        ShoppingTestData.reject { module.prepareRefresh("attempt-1", ShoppingTestData.read("250"),
            LabyrinthCurrencyObservation(ShoppingTestData.read("1000", 1)), 7, 1, 5, ShoppingTestData.encode) }
        ShoppingTestData.reject { module.prepareRefresh("attempt-1", ShoppingTestData.read("250"), ShoppingTestData.wallet(1000), 7, 1, 5) { false } }
        assertEquals(listOf("currency.observed"), events().map(ShoppingTestData::type))
    }

    @Test fun `并发刷新prepare和余额dispatch回调均只有单次合法结算`() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(6) { index ->
                module.open(1, 400L + index, null, 0); observe()
                val other = LabyrinthShoppingModule(LabyrinthShoppingJournal(root)).also { it.open(1, 400L + index, null, 0) }
                val start = CountDownLatch(1)
                val preparations = (0 until 8).map { i -> pool.submit<Boolean> {
                    start.await(); (if (i % 2 == 0) module else other).prepareRefresh("attempt-1", ShoppingTestData.read("250"),
                        ShoppingTestData.wallet(1000), 7, 1, 1, ShoppingTestData.encode)
                } }
                start.countDown(); assertEquals(1, preparations.count { it.get(10, TimeUnit.SECONDS) })
                val callbacks = CountDownLatch(1)
                val observed = pool.submit { callbacks.await(); module.observeBalance(ShoppingTestData.wallet(750), 7, 1, 2, ShoppingTestData.encode) }
                val dispatched = pool.submit { callbacks.await(); other.refreshDispatched("attempt-1", 3) }
                callbacks.countDown(); observed.get(10, TimeUnit.SECONDS); dispatched.get(10, TimeUnit.SECONDS)
                assertEquals(1, module.refreshCount(7, 1)); assertNull(module.pendingRefresh())
                val events = module.events()
                val settled = events.single { ShoppingTestData.type(it) == "shop.refresh_settled" }
                events.singleOrNull { ShoppingTestData.type(it) == "shop.refresh_dispatched" }?.let {
                    assertTrue(it["seq"]!!.jsonPrimitive.int < settled["seq"]!!.jsonPrimitive.int)
                }
                assertEquals(750L, other.verifiedBalance)
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun `印记恢复统一复核prepared回执持有和所有精确身份字段`() {
        val mutations = listOf<(JsonObject) -> JsonObject>(
            { e -> JsonObject(e + ("verification_status" to JsonPrimitive("unknown"))) },
            { e -> JsonObject(e + ("payload" to JsonObject(ShoppingTestData.payload(e) + ("role_label" to JsonPrimitive("治疗型职能印记"))))) },
            { e -> JsonObject(e + ("payload" to JsonObject(ShoppingTestData.payload(e) + ("choice_imprint" to JsonPrimitive(true))))) },
            { e -> JsonObject(e + ("payload" to JsonObject(ShoppingTestData.payload(e) + ("item_name" to JsonPrimitive("攻击型职能的选择印记"))))) }
        )
        mutations.forEachIndexed { index, mutate ->
            module.open(1, 300L + index, null, 0)
            intent(ShoppingTestData.role()); module.prepareConfirmation(1)
            accept(ShoppingTestData.receipt("攻击型职能的随机印记"))
            val run = store.open(1, 300L + index, null, 2)
            ShoppingTestData.rewriteEvents(run) { if (ShoppingTestData.type(it) == "shop.purchase_intent") mutate(it) else it }
            ShoppingTestData.reject { module.pendingOwnership() }
        }
        module.open(1, 310, null, 0)
        intent(ShoppingTestData.role()); module.prepareConfirmation(1)
        val result = accept(ShoppingTestData.receipt("攻击型职能的随机印记"))
        module.markOwnershipCommitted(result.key, 2)
        val run = store.open(1, 310, null, 3)
        ShoppingTestData.rewriteEvents(run) { if (ShoppingTestData.type(it) == "shop.ownership_committed")
            JsonObject(it + ("evidence_sha256" to JsonPrimitive("forged"))) else it }
        ShoppingTestData.reject { module.pendingOwnership() }
    }

    @Test fun `并发prepare仅一个调用获得真实执行许可`() {
        intent(); val other = restart(); val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 12).map { index -> pool.submit<Boolean> {
                start.await(); (if (index % 2 == 0) module else other).prepareConfirmation(5)
            } }
            start.countDown(); assertEquals(1, futures.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, events().count { ShoppingTestData.type(it) == "shop.purchase_confirm_prepared" })
        } finally { pool.shutdownNow() }
    }

    @Test fun `两个独立store及facade多线程同意图仅记录一次`() {
        module.active
        val other = restart(); val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 12).map { index -> pool.submit<String> {
                start.await(); (if (index % 2 == 0) module else other).recordIntent(ShoppingTestData.item(), 1000, 7, 1, 4, ShoppingTestData.encode)
            } }
            start.countDown()
            assertEquals(1, futures.map { it.get(10, TimeUnit.SECONDS) }.distinct().size)
            assertEquals(1, events().size)
        } finally { pool.shutdownNow() }
    }

    @Test fun `两个facade并发回执和持有标记不会重复记账`() {
        intent(); module.confirmDispatched(); val other = restart()
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 12).map { index -> pool.submit<String> {
                start.await(); val facade = if (index % 2 == 0) module else other
                val result = facade.acceptReceipt(ShoppingTestData.receipt(), 7, 1, 6, ShoppingTestData.encode)
                facade.markOwnershipCommitted(result.key, 7); result.key
            } }
            start.countDown(); assertEquals(1, futures.map { it.get(10, TimeUnit.SECONDS) }.distinct().size)
            assertEquals(1, events().count { ShoppingTestData.type(it) == "shop.purchase_completed" })
            assertEquals(1, events().count { ShoppingTestData.type(it) == "shop.ownership_committed" })
        } finally { pool.shutdownNow() }
    }
}
