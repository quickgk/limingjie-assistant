package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.vision.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 真实 Session 门禁 + 文件 journal + 幂等持有端口；不创建 Android Bitmap。 */
class LabyrinthShoppingSessionIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val item = ShoppingTestData.item()
    private val rect = ShoppingTestData.rect
    private val receipt = ShoppingTestData.receipt()

    @Test fun `完整购买先存意图再点击确认回执持有最后关闭`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start()
            fixture.buy()
            assertEquals(listOf("currency.observed", "shop.purchase_intent"), fixture.types())
            assertEquals(1, fixture.taps.get())
            assertTrue(fixture.events().all { fixture.journal.evidenceValid(fixture.run(), it) })
            fixture.confirm()
            assertEquals("shop.purchase_confirm_dispatched", fixture.types().last())
            assertEquals(0, fixture.store.commits.get())
            assertTrue(fixture.observe(completed()))
            fixture.awaitIdle()
            assertEquals(1, fixture.store.commits.get())
            assertEquals(1, fixture.count())
            assertTrue(fixture.module.canDismiss(receipt))
            assertFalse(fixture.observe(completed()))
            fixture.call("persistObservation", fixture.id(), completed(), fixture.now)
            withTimeout(5000) { while (fixture.store.pageWrites.get() == 0) delay(10) }
            assertEquals(0, fixture.store.pageRelicWrites.get())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE)
            fixture.awaitIdle()
            assertEquals(3, fixture.taps.get())
            assertNull(fixture.get("plannedShopPurchaseRelicId"))
            assertNull(fixture.get("shoppingPurchaseCandidate"))
            assertNull(fixture.session.state.value.lastResult?.currencyObservation)
            assertTrue(fixture.session.state.value.lastResult?.shopObservation?.items.orEmpty().isEmpty())
            assertEquals(1, fixture.store.snapshot.observedRelics.single().observationCount)
            assertEquals(listOf("currency.observed", "shop.purchase_intent", "shop.purchase_confirm_prepared",
                "shop.purchase_confirm_dispatched", "shop.purchase_completed", "shop.ownership_committed"), fixture.types())
            assertTrue(fixture.observe(completed()))
            assertEquals(3, fixture.taps.get())
        } finally { fixture.stop() }
    }

    @Test fun `原图保存异常不得点击购买或关闭且会话继续`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start()
            fixture.observe(stock())
            fixture.set("shoppingFrameEncoder", { _: FileOutputStream -> false })
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
            fixture.awaitIdle()
            assertEquals(0, fixture.taps.get())
            assertTrue(fixture.session.state.value.running)
            assertTrue(fixture.session.state.value.message.orEmpty().contains("可重试"))
            assertEquals(listOf("currency.observed"), fixture.types())
        } finally { fixture.stop() }
    }

    @Test fun `回执未核实不计购买cycle不持有不关闭`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy(); fixture.confirm()
            val pending = receipt.copy(expenditure = ShoppingTestData.read("消耗阿尔法金币×100", 1))
            assertTrue(fixture.observe(completed(pending)))
            fixture.call("invalidatePreviousPageState", LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE, false)
            assertEquals(0, fixture.count())
            assertEquals(0, fixture.store.commits.get())
            assertNull(fixture.get("pendingShopPurchase"))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE)
            fixture.awaitIdle()
            assertEquals(2, fixture.taps.get())
            assertFalse(fixture.module.canDismiss(pending))
            assertTrue(fixture.session.state.value.running)
        } finally { fixture.stop() }
    }

    @Test fun `回执金额或商品不符不能保存不能计数且不终止流程`() = runBlocking {
        for (bad in listOf(receipt.copy(balanceAfter = ShoppingTestData.read("800")),
            ShoppingTestData.receipt("钢铁护符"))) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.buy(); fixture.confirm()
                assertTrue(fixture.observe(completed(bad)))
                fixture.awaitIdle()
                assertEquals(0, fixture.count())
                assertEquals(0, fixture.store.commits.get())
                assertFalse(fixture.types().contains("shop.purchase_completed"))
                assertEquals(2, fixture.taps.get())
                assertTrue(fixture.session.state.value.running)
            } finally { fixture.stop() }
        }
    }

    @Test fun `普通processFrameResult在页面变更与persistObservation之前截获未核实回执`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy(); fixture.confirm()
            fixture.set("lastObservedPageState", LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION)
            val pending = completed(receipt.copy(balanceAfter = ShoppingTestData.read("900", 1)))
            fixture.call("processFrameResult", fixture.id(), pending, 1920, 1080, fixture.now)
            fixture.awaitIdle()
            assertEquals(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION, fixture.get("lastObservedPageState"))
            assertEquals(0, fixture.store.pageRelicWrites.get())
            assertEquals(0, fixture.store.commits.get())
            assertEquals(0, fixture.count())
            assertEquals(2, fixture.taps.get())
        } finally { fixture.stop() }
    }

    @Test fun `确认和NONE加载过渡保留候选且确认后不超时改买下一件`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy(); fixture.confirm()
            fixture.now += 180_000
            for (page in listOf(LabyrinthEntryPageState.UNKNOWN, LabyrinthEntryPageState.GAME_LOADING_PROGRESS)) {
                assertTrue(fixture.observe(frame(page)))
                fixture.call("invalidatePreviousPageState", page, false)
                assertEquals(item.relicMatch, fixture.get("shoppingPurchaseCandidate"))
                assertEquals(item.relicMatch!!.relicId, fixture.get("plannedShopPurchaseRelicId"))
            }
            assertTrue(fixture.observe(stock()))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
            fixture.awaitIdle()
            assertEquals(2, fixture.taps.get())
            assertTrue(fixture.session.state.value.running)
            fixture.observe(completed()); fixture.awaitIdle()
            assertEquals(1, fixture.store.commits.get())
        } finally { fixture.stop() }
    }

    @Test fun `Room失败保留已保存回执重试成功后才关闭`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy(); fixture.confirm()
            fixture.store.failBeforeCommit = true
            fixture.observe(completed()); fixture.awaitIdle()
            assertTrue(fixture.types().contains("shop.purchase_completed"))
            assertFalse(fixture.module.canDismiss(receipt))
            assertEquals(0, fixture.count())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE)
            fixture.awaitIdle()
            assertEquals(2, fixture.taps.get())
            assertTrue(fixture.session.state.value.running)
            fixture.store.failBeforeCommit = false
            fixture.observe(completed()); fixture.awaitIdle()
            assertFalse(fixture.observe(completed()))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE)
            fixture.awaitIdle()
            assertEquals(3, fixture.taps.get())
            assertEquals(1, fixture.store.commits.get())
            assertEquals(1, fixture.types().count { it == "shop.purchase_completed" })
        } finally { fixture.stop() }
    }

    @Test fun `Room提交后中断重启恢复ID补mark不重复入库`() = runBlocking {
        val root = temp.newFolder()
        val store = IdempotentStore()
        val first = Fixture(root, store)
        try {
            first.start(); first.buy(); first.confirm()
            store.failAfterCommit = true
            first.observe(completed()); first.awaitIdle()
            assertEquals(1, store.commits.get())
            assertFalse(first.module.canDismiss(receipt))
        } finally { first.stop() }
        val restarted = Fixture(root, store)
        try {
            restarted.start()
            assertNull(restarted.get("shoppingPurchaseCandidate"))
            restarted.observe(completed()); restarted.awaitIdle()
            assertEquals(1, store.commits.get())
            assertEquals(1, store.snapshot.observedRelics.single().observationCount)
            assertFalse(restarted.observe(completed()))
            restarted.dispatch(LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE)
            restarted.awaitIdle()
            assertEquals(1, restarted.taps.get())
            assertEquals(1, restarted.types().count { it == "shop.purchase_completed" })
            assertEquals(1, restarted.types().count { it == "shop.ownership_committed" })
            assertEquals(1, restarted.resolutions.get())
        } finally { restarted.stop() }
    }

    @Test fun `重启确认已派发且仍见库存不能用新OCR余额重复购买`() = runBlocking {
        val root = temp.newFolder()
        val first = Fixture(root)
        try { first.start(); first.buy(); first.confirm() } finally { first.stop() }
        val restarted = Fixture(root)
        try {
            restarted.start()
            assertTrue(restarted.observe(stock(balance = 900)))
            restarted.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
            restarted.awaitIdle()
            assertEquals(0, restarted.taps.get())
            assertEquals(1, restarted.types().count { it == "shop.purchase_intent" })
            assertTrue(restarted.session.state.value.running)
        } finally { restarted.stop() }
    }

    @Test fun `旧余额不能授权缺少当前双读的新商品`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.observe(stock())
            fixture.observe(stock().copy(currencyObservation = null))
            assertTrue(fixture.call("currentShopDecision", fixture.session.state.value.lastResult) is LabyrinthShopDecision.Wait)
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
            fixture.awaitIdle()
            assertEquals(0, fixture.taps.get())
            assertEquals(1000L, fixture.module.verifiedBalance)
        } finally { fixture.stop() }
    }

    @Test fun `人工回执只记账不持有也不自动关闭`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start()
            assertTrue(fixture.observe(completed()))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE)
            fixture.awaitIdle()
            assertEquals(listOf("shop.purchase_completed"), fixture.types())
            assertEquals(0, fixture.store.commits.get())
            assertEquals(0, fixture.count())
            assertEquals(0, fixture.taps.get())
            assertFalse(fixture.module.canDismiss(receipt))
        } finally { fixture.stop() }
    }

    @Test fun `印记购得确认不伪造角色或遗物持有`() = runBlocking {
        val role = ShoppingTestData.role()
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start()
            fixture.observe(stock(goods = listOf(role)))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_IMPRINT, imprint = role.roleImprintLabel)
            fixture.awaitIdle()
            fixture.observe(frame(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
                LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_CONFIRMATION)))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_IMPRINT, imprint = role.roleImprintLabel)
            fixture.awaitIdle()
            val roleReceipt = ShoppingTestData.receipt(role.titleText!!)
            fixture.observe(completed(roleReceipt)); fixture.awaitIdle()
            assertTrue(fixture.module.canDismiss(roleReceipt))
            assertEquals(1, fixture.store.commits.get())
            assertTrue(fixture.store.snapshot.joinedCharacters.isEmpty())
            assertTrue(fixture.store.snapshot.observedRelics.isEmpty())
            assertEquals(0, fixture.count())
        } finally { fixture.stop() }
    }

    @Test fun `真实刷新确认后记原价原余额和前帧PNG不用回收后的编码器`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start()
            val disabled = (1..3).map { item.copy(slotId = "slot-$it", status = LabyrinthShopItemStatus.DISABLED) }
            val stock = stock(goods = disabled).copy(anchorMatches = mapOf(
                EntryAnchorId.SHOP_REFRESH_BUTTON to EntryAnchorMatch(1.0, rect)),
                shopObservation = LabyrinthShopObservation(disabled, refreshButtonEnabledEvidence = 1.0,
                    refreshPriceRead = ShoppingTestData.read("250")))
            fixture.observe(stock)
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_REFRESH); fixture.awaitIdle()
            assertFalse(fixture.types().contains("shop.refresh_dispatched"))
            fixture.set("shoppingFrameEncoder", ShoppingTestData.neverEncode)
            fixture.set("shoppingFramePng", { error("前帧已回收，不可重新编码") })
            fixture.state(frame(LabyrinthEntryPageState.UNKNOWN))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            val event = fixture.events().single { ShoppingTestData.type(it) == "shop.refresh_dispatched" }
            assertEquals(250L, ShoppingTestData.payload(event)["refresh_price"]!!.jsonPrimitive.long)
            assertEquals(1000L, ShoppingTestData.payload(event)["balance_before"]!!.jsonPrimitive.long)
            val evidence = File(fixture.run().directory, event["evidence_ref"]!!.jsonPrimitive.content)
            assertArrayEquals(ShoppingTestData.raw, evidence.readBytes())
            fixture.observe(stock.copy(currencyObservation = ShoppingTestData.wallet(750)))
            assertEquals("shop.refresh_settled", fixture.types().last())
            assertEquals(750L, fixture.module.verifiedBalance)
        } finally { fixture.stop() }
    }

    @Test fun `区域4与刷新额度仍由既有策略限制`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start()
            fixture.set("activeNodeArea", 4)
            fixture.observe(stock().copy(currencyObservation = null))
            assertTrue(fixture.call("currentShopDecision", fixture.session.state.value.lastResult) is LabyrinthShopDecision.Close)
            fixture.set("activeNodeArea", 3)
            fixture.set("shopPolicy", LabyrinthShopPolicy(finalArea = 3, budgetOptimizationEnabled = true))
            fixture.set("shopRefreshesThisVisit", 1)
            fixture.observe(stock(goods = (1..3).map { item.copy(status = LabyrinthShopItemStatus.DISABLED) }))
            assertFalse(fixture.call("currentShopDecision", fixture.session.state.value.lastResult) is LabyrinthShopDecision.Refresh)
            assertEquals(0, fixture.taps.get())
        } finally { fixture.stop() }
    }

    @Test fun `实际enter缺失只封锁购物不污染非购物和特别装备动作`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start()
            fixture.set("validatedRoute", null)
            assertFalse(fixture.observe(frame(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
                special = LabyrinthSpecialEquipmentObservation(stage = LabyrinthSpecialEquipmentStage.MAIN_DIALOG, confidence = 1.0))))
            assertTrue(fixture.call("prepareShoppingAction", LabyrinthPostEntryActionKind.SELECT_RELIC, fixture.now, null, null, null) as Boolean)
            assertTrue(fixture.call("dispatchSpecialEquipmentAction", fixture.id(), rect, "特别装备测试", fixture.now) as Boolean)
            fixture.awaitIdle()
            assertEquals(1, fixture.taps.get())
            assertTrue(fixture.session.state.value.running)
            fixture.observe(stock())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
            fixture.awaitIdle()
            assertEquals(1, fixture.taps.get())
            assertFalse(fixture.module.active)
        } finally { fixture.stop() }
    }

    @Test fun `legacy默认不开module保留未提供价格的购买判据`() = runBlocking {
        val fixture = Fixture(temp.newFolder(), enabled = false)
        try {
            fixture.start()
            val legacy = stock(goods = listOf(item.copy(priceRead = null))).copy(currencyObservation = null)
            fixture.state(legacy)
            assertTrue(fixture.call("currentShopDecision", legacy) is LabyrinthShopDecision.BuyRelic)
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
            fixture.awaitIdle()
            assertEquals(1, fixture.taps.get())
            assertFalse(fixture.module.active)
        } finally { fixture.stop() }
    }

    @Test fun `真实executor挂起超过60秒仍先保存回执持锁迟到Executed不重复确认`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.start(); fixture.buy()
            fixture.backend = {
                entered.complete(Unit)
                release.await()
                AutomationBackendResult.Completed
            }
            fixture.observe(confirmation())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, item.relicMatch!!.relicId)
            withTimeout(5000) { entered.await() }
            assertTrue(fixture.get("shoppingConfirmationInFlight") as Boolean)
            assertEquals("shop.purchase_confirm_prepared", fixture.types().last())
            val heldSince = fixture.get("actionInFlightSince")
            val frameCount = fixture.session.state.value.frameCount
            fixture.set("lastObservedPageState", LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION)
            fixture.now += 60_001
            val completedFrame = completed()
            fixture.call("processFrameResult", fixture.id(), completedFrame, 1920, 1080, fixture.now)
            assertEquals(completedFrame, fixture.session.state.value.lastResult)
            assertEquals(frameCount + 1, fixture.session.state.value.frameCount)
            assertEquals("shop.purchase_completed", fixture.types().last())
            assertTrue(fixture.journal.evidenceValid(fixture.run(), fixture.events().last()))
            fixture.now += 1000
            fixture.call("processFrameResult", fixture.id(), completedFrame, 1920, 1080, fixture.now)
            assertEquals(1, fixture.types().count { it == "shop.purchase_completed" })
            assertEquals(0, fixture.store.commits.get()); assertEquals(0, fixture.count())
            assertTrue((fixture.get("actionInFlight") as AtomicBoolean).get())
            assertEquals(heldSince, fixture.get("actionInFlightSince"))
            assertFalse((fixture.get("shoppingOwnershipInFlight") as AtomicBoolean).get())
            assertEquals(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION, fixture.get("lastObservedPageState"))
            assertEquals(0, fixture.store.pageWrites.get())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, item.relicMatch!!.relicId)
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE)
            assertEquals(2, fixture.taps.get())
            release.complete(Unit); fixture.awaitIdle()
            assertFalse(fixture.types().contains("shop.purchase_confirm_dispatched"))
            assertFalse(fixture.get("shoppingConfirmationInFlight") as Boolean)
            // 回执弹窗已经消失，下一帧仍只按耐久精确身份提交。
            fixture.observe(frame(LabyrinthEntryPageState.NODE_MAP_VIEW)); fixture.awaitIdle()
            assertEquals(1, fixture.store.commits.get())
            assertEquals(1, fixture.count())
            assertEquals(1, fixture.types().count { it == "shop.purchase_confirm_prepared" })
            assertEquals(1, fixture.types().count { it == "shop.ownership_committed" })
        } finally { release.complete(Unit); fixture.stop() }
    }

    @Test fun `executor异常保留未知prepared重启不能重复点击只等精确receipt`() = runBlocking {
        val root = temp.newFolder()
        val first = Fixture(root)
        try {
            first.start(); first.buy()
            first.backend = { error("模拟手势结果未知") }
            first.confirm()
            assertEquals("shop.purchase_confirm_prepared", first.types().last())
            assertFalse(first.types().contains("shop.purchase_confirm_rejected"))
            first.observe(confirmation())
            first.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, item.relicMatch!!.relicId)
            first.awaitIdle()
            assertEquals(2, first.taps.get())
        } finally { first.stop() }
        val restarted = Fixture(root, moduleOnly = true)
        try {
            restarted.start(); restarted.confirm()
            assertEquals(0, restarted.taps.get())
            assertEquals("shop.purchase_confirm_prepared", restarted.types().last())
            restarted.observe(completed()); restarted.awaitIdle()
            assertEquals(1, restarted.store.commits.get())
            assertTrue(restarted.module.canDismiss(receipt))
        } finally { restarted.stop() }
    }

    @Test fun `未prepared意图重启没有预算lease不得自动确认`() = runBlocking {
        val root = temp.newFolder()
        val first = Fixture(root)
        try { first.start(); first.buy() } finally { first.stop() }
        val restarted = Fixture(root)
        try {
            restarted.start(); restarted.confirm()
            assertEquals(0, restarted.taps.get())
            assertEquals("shop.purchase_intent", restarted.types().last())
            assertTrue(restarted.observe(stock()))
            assertNull(restarted.get("shoppingPurchaseLease"))
        } finally { restarted.stop() }
    }

    @Test fun `购物页无进展超过期限停止整局并保留原因`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy()
            // 重启后确认弹窗还在、内存里的预算证据已经丢了：这条 fail-closed 分支没有自动出路。
            fixture.set("shoppingPurchaseLease", null)
            assertTrue(fixture.observe(confirmation()))
            fixture.awaitIdle()
            assertTrue(fixture.session.state.value.running)
            fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS
            assertTrue(fixture.observe(confirmation()))
            fixture.awaitIdle()
            assertFalse(fixture.session.state.value.running)
            assertTrue(fixture.session.state.value.message.orEmpty().contains("购物流程连续"))
            assertEquals(1, fixture.taps.get())
        } finally { fixture.stop() }
    }

    @Test fun `页面变化只给一次过渡额度`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy()
            fixture.set("shoppingPurchaseLease", null)
            // 第一次变化（商店页 → UNKNOWN）拿到过渡额度。
            assertTrue(fixture.observe(frame(LabyrinthEntryPageState.UNKNOWN)))
            fixture.awaitIdle()
            assertTrue(fixture.session.state.value.running)
            // 第二次变化在 +100 秒：若每次变化都重置，计时会回到 0。
            fixture.now += 100_000
            assertTrue(fixture.observe(confirmation()))
            fixture.awaitIdle()
            assertTrue(fixture.session.state.value.running)
            // 再 +100 秒（累计 200 秒）回到 UNKNOWN：额度已经用掉，必须停止。
            // 悬空意图的兜底作废期限是 240 秒，晚于这里的 180 秒停止，所以保守策略仍然优先。
            fixture.now += 100_000
            assertTrue(fixture.observe(frame(LabyrinthEntryPageState.UNKNOWN)))
            fixture.awaitIdle()
            assertFalse(fixture.session.state.value.running)
            assertTrue(fixture.session.state.value.message.orEmpty().contains("购物流程连续"))
        } finally { fixture.stop() }
    }

    @Test fun `手势被拒绝后未恢复且超期会停止`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.observe(stock())
            fixture.backend = { AutomationBackendResult.Rejected("无障碍手势被系统取消") }
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
            fixture.awaitIdle()
            assertEquals("shop.purchase_intent_cancelled", fixture.types().last())
            assertTrue(fixture.session.state.value.running)
            // 被确定拒绝的循环会撤销意图；意图/prepared 不能算进展，否则期限永远不触发。
            fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS
            fixture.observe(stock())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
            fixture.awaitIdle()
            assertFalse(fixture.session.state.value.running)
        } finally { fixture.stop() }
    }

    @Test fun `连续三次确定拒绝提前停止不再继续写意图`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.observe(stock())
            fixture.backend = { AutomationBackendResult.Rejected("无障碍手势被系统取消") }
            repeat(SHOPPING_REJECTION_BUDGET) {
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
                fixture.awaitIdle()
            }
            assertFalse(fixture.session.state.value.running)
            assertTrue(fixture.session.state.value.message.orEmpty().contains("连续${SHOPPING_REJECTION_BUDGET}次被确定拒绝"))
            // 每次重试都会落一条意图；预算到就停，不会为一次卡死留下上百条。
            assertEquals(SHOPPING_REJECTION_BUDGET, fixture.types().count { it == "shop.purchase_intent" })
        } finally { fixture.stop() }
    }

    @Test fun `期限在动作执行中到期但动作随后成功不得误停`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        try {
            fixture.start(); fixture.observe(stock())
            fixture.backend = { started.complete(Unit); gate.await(); AutomationBackendResult.Completed }
            fixture.set("shoppingLastProgressAt", fixture.now - SHOPPING_HOLD_TIMEOUT_MILLIS - 1_000)
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
            // 等动作真的挂进 executor，再切到确认页；否则这一帧会改掉 executor 门禁看到的页面。
            started.await()
            // 动作还挂在 executor 里：此时来一帧命中 hold 并请求停止，但动作锁被占用，只能挂起。
            // 过渡额度必须先用掉，否则这一帧只会重置计时，根本不会产生停止请求。
            fixture.set("shoppingPurchaseLease", null)
            fixture.set("shoppingHoldPage", LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION)
            fixture.set("shoppingTransitionGranted", true)
            assertTrue(fixture.observe(confirmation()))
            assertNotNull(pendingStop(fixture))
            assertTrue(fixture.session.state.value.running)
            gate.complete(Unit)
            fixture.awaitIdle()
            // 动作真的落地了，挂起的停止请求必须被进展撤销。
            assertNull(pendingStop(fixture))
            assertTrue(fixture.session.state.value.running)
        } finally {
            gate.complete(Unit)
            fixture.stop()
        }
    }

    @Test fun `动作锁卡死且购物等待超期时释放并停止`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        try {
            fixture.start(); fixture.buy()
            fixture.backend = { started.complete(Unit); gate.await(); AutomationBackendResult.Completed }
            fixture.observe(confirmation())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, item.relicMatch!!.relicId)
            started.await()
            assertTrue(fixture.get("shoppingConfirmationInFlight") as Boolean)
            // 动作锁挂住超过 60 秒，且购物等待早已超期：不再保留锁，直接释放并停止。
            fixture.now += 61_000
            fixture.set("shoppingLastProgressAt", fixture.now - SHOPPING_HOLD_TIMEOUT_MILLIS - 1_000)
            fixture.call("processFrameResult", fixture.id(), confirmation(), 1920, 1080, fixture.now)
            // 动作最终会返回（这里放行），停止要等它释放锁之后才完成。
            gate.complete(Unit)
            withTimeout(15_000) { while (fixture.session.state.value.running) delay(10) }
            assertFalse(fixture.session.state.value.running)
            assertTrue(fixture.session.state.value.message.orEmpty().contains("购物等待已超期"))
        } finally {
            gate.complete(Unit)
            fixture.stop()
        }
    }

    @Test fun `游戏不在前台的拒绝不计入拒绝预算`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.observe(stock())
            fixture.backend = { AutomationBackendResult.Rejected(GAME_NOT_FOREGROUND_REASON) }
            repeat(SHOPPING_REJECTION_BUDGET + 2) {
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
                fixture.awaitIdle()
            }
            // 环境性拒绝有自己的等待路径，不该被当成点不动而提前停止。
            assertTrue(fixture.session.state.value.running)
            assertFalse(fixture.session.state.value.message.orEmpty().contains("被确定拒绝"))
        } finally { fixture.stop() }
    }

    @Test fun `非购物动作卡住走通用latch出口不标购物超期`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        try {
            fixture.start()
            // 用非商店页面派发普通点击，避免撞上 fixture 为购物点击准备的门禁断言。
            fixture.observe(frame(LabyrinthEntryPageState.NODE_SELECTION))
            fixture.backend = { started.complete(Unit); gate.await(); AutomationBackendResult.Completed }
            // 购物进展故意设得很久以前：如果实现误用购物期限，消息就会写成购物超期。
            fixture.set("shoppingLastProgressAt", fixture.now - SHOPPING_HOLD_TIMEOUT_MILLIS - 1_000)
            fixture.dispatch(LabyrinthPostEntryActionKind.GENERIC)
            started.await()
            fixture.now += 61_000
            fixture.call("processFrameResult", fixture.id(), frame(LabyrinthEntryPageState.NODE_SELECTION), 1920, 1080, fixture.now)
            gate.complete(Unit)
            withTimeout(15_000) { while (fixture.session.state.value.running) delay(10) }
            assertFalse(fixture.session.state.value.running)
            val message = fixture.session.state.value.message.orEmpty()
            assertTrue(message.contains("动作执行超过"))
            assertFalse(message.contains("购物等待已超期"))
        } finally {
            gate.complete(Unit)
            fixture.stop()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingStop(fixture: Fixture): Any? =
        (fixture.get("pendingShoppingStop") as AtomicReference<Any?>).get()

    @Test fun `持续UNKNOWN帧超过期限停止整局`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy()
            fixture.set("shoppingPurchaseLease", null)
            // 回执读不出时画面就停在 UNKNOWN：第一次变化只给过渡额度。
            assertTrue(fixture.observe(frame(LabyrinthEntryPageState.UNKNOWN)))
            fixture.awaitIdle()
            assertTrue(fixture.session.state.value.running)
            fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS
            assertTrue(fixture.observe(frame(LabyrinthEntryPageState.UNKNOWN)))
            fixture.awaitIdle()
            assertFalse(fixture.session.state.value.running)
            assertTrue(fixture.session.state.value.message.orEmpty().contains("当前页面 UNKNOWN"))
        } finally { fixture.stop() }
    }

    @Test fun `未决刷新跨节点停留超过期限停止整局`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.openRefresh()
            fixture.backend = { error("刷新可能已经扣费，结果未知") }
            fixture.state(frame(LabyrinthEntryPageState.UNKNOWN))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals("shop.refresh_prepared", fixture.types().last())
            // 离开商店节点后刷新仍未决：hold 不区分页面，所以停在别的页面也会计时。
            fixture.set("activeNodeType", LabyrinthNodeTypes.EVENT)
            assertTrue(fixture.observe(frame(LabyrinthEntryPageState.NODE_SELECTION)))
            fixture.awaitIdle()
            assertTrue(fixture.session.state.value.running)
            fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS
            assertTrue(fixture.observe(frame(LabyrinthEntryPageState.NODE_SELECTION)))
            fixture.awaitIdle()
            assertFalse(fixture.session.state.value.running)
            assertTrue(fixture.session.state.value.message.orEmpty().contains("NODE_SELECTION"))
        } finally { fixture.stop() }
    }

    @Test fun `prepareConfirmation返回false不得进入executor也不追加prepared`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy()
            assertTrue(fixture.module.prepareConfirmation(fixture.now))
            fixture.confirm()
            assertEquals(1, fixture.taps.get())
            assertEquals(1, fixture.types().count { it == "shop.purchase_confirm_prepared" })
            assertFalse(fixture.types().contains("shop.purchase_confirm_dispatched"))
            assertFalse(fixture.get("shoppingConfirmationInFlight") as Boolean)
        } finally { fixture.stop() }
    }

    @Test fun `已双读但商品前帧超过15秒不得购买或刷新`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.observe(stock())
            fixture.now += 15_001
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId); fixture.awaitIdle()
            assertEquals(0, fixture.taps.get())
            assertEquals(listOf("currency.observed"), fixture.types())
        } finally { fixture.stop() }
    }

    @Test fun `确定Rejected保存reject后相同短期lease可重新prepare`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy()
            fixture.backend = { AutomationBackendResult.Rejected("确定未执行") }
            fixture.confirm()
            assertEquals("shop.purchase_confirm_rejected", fixture.types().last())
            assertFalse(fixture.get("shoppingConfirmationInFlight") as Boolean)
            assertFalse(fixture.types().contains("shop.purchase_intent_cancelled"))
            fixture.backend = { AutomationBackendResult.Completed }
            fixture.confirm()
            assertEquals(3, fixture.taps.get())
            assertEquals(2, fixture.types().count { it == "shop.purchase_confirm_prepared" })
            assertEquals("shop.purchase_confirm_dispatched", fixture.types().last())
        } finally { fixture.stop() }
    }

    @Test fun `真实executor的PausedStaleSessionDryRun均保存确定未执行reject`() = runBlocking {
        for (gate in listOf("Paused", "StaleSession", "DryRun")) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.buy(); fixture.observe(confirmation())
                val activeField = fixture.manager.javaClass.getDeclaredField("active").apply { isAccessible = true }
                val current = fixture.manager.current()!!
                when (gate) {
                    "Paused" -> fixture.manager.setPaused(current.id, true)
                    "StaleSession" -> activeField.set(fixture.manager, current.copy(id = AutomationSessionId(current.id.value + 1)))
                    "DryRun" -> activeField.set(fixture.manager, current.copy(dryRun = true))
                }
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, item.relicMatch!!.relicId)
                fixture.awaitIdle()
                assertEquals(gate, "shop.purchase_confirm_rejected", fixture.types().last())
                assertEquals(gate, 1, fixture.taps.get())
                assertFalse(fixture.get("shoppingConfirmationInFlight") as Boolean)
                activeField.set(fixture.manager, current)
            } finally { fixture.stop() }
        }
    }

    @Test fun `Room失败且回执消失从module精确恢复不读当前屏幕`() = runBlocking {
        val fixture = Fixture(temp.newFolder(), moduleOnly = true)
        try {
            fixture.start(); fixture.buy(); fixture.confirm()
            fixture.store.failBeforeCommit = true
            fixture.observe(completed()); fixture.awaitIdle()
            fixture.set("shoppingPurchaseCandidate", null)
            fixture.store.failBeforeCommit = false
            val absent = frame(LabyrinthEntryPageState.NODE_MAP_VIEW)
            fixture.state(absent)
            fixture.set("shoppingFrameEncoder", null)
            fixture.set("shoppingFramePng", null)
            assertTrue(fixture.call("handleShoppingFrame", fixture.id(), absent, fixture.now, null) as Boolean)
            fixture.awaitIdle()
            assertEquals(1, fixture.store.commits.get())
            assertEquals(item.relicMatch!!.relicId, fixture.store.snapshot.observedRelics.single().relicId)
            assertEquals(1, fixture.resolutions.get())
            assertEquals(1, fixture.count())
            fixture.observe(frame(LabyrinthEntryPageState.NODE_MAP_VIEW)); fixture.awaitIdle()
            assertEquals(1, fixture.store.commits.get())
            assertEquals(1, fixture.types().count { it == "shop.ownership_committed" })
        } finally { fixture.stop() }
    }

    @Test fun `印记空持有失败后重启无receipt仍可恢复且manual从不自动提交`() = runBlocking {
        val root = temp.newFolder()
        val store = IdempotentStore()
        val role = ShoppingTestData.role()
        val first = Fixture(root, store)
        try {
            first.start()
            first.observe(stock(goods = listOf(role)))
            first.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_IMPRINT, imprint = role.roleImprintLabel); first.awaitIdle()
            first.observe(confirmation().copy(shopObservation = LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_CONFIRMATION)))
            first.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_IMPRINT, imprint = role.roleImprintLabel); first.awaitIdle()
            store.failBeforeCommit = true
            first.observe(completed(ShoppingTestData.receipt(role.titleText!!))); first.awaitIdle()
        } finally { first.stop() }
        val restarted = Fixture(root, store, moduleOnly = true)
        try {
            restarted.start(); store.failBeforeCommit = false
            restarted.observe(frame(LabyrinthEntryPageState.NODE_MAP_VIEW)); restarted.awaitIdle()
            assertEquals(1, store.commits.get())
            assertEquals(1, restarted.types().count { it == "shop.ownership_committed" })
            assertTrue(store.snapshot.observedRelics.isEmpty())
            assertTrue(store.snapshot.joinedCharacters.isEmpty())
            assertEquals(0, restarted.count())
        } finally { restarted.stop() }
        val manual = Fixture(temp.newFolder(), moduleOnly = true)
        try {
            manual.start(); manual.observe(completed()); manual.awaitIdle()
            manual.observe(frame(LabyrinthEntryPageState.NODE_MAP_VIEW)); manual.awaitIdle()
            assertEquals(0, manual.store.commits.get())
            assertEquals(listOf("shop.purchase_completed"), manual.types())
        } finally { manual.stop() }
    }

    @Test fun `module-only正常但同目录不同journal对象fail closed`() = runBlocking {
        val good = Fixture(temp.newFolder(), moduleOnly = true)
        try {
            good.start(); good.buy(); good.confirm()
            assertEquals(2, good.taps.get())
            assertEquals(100L, good.module.enterId)
        } finally { good.stop() }
        val bad = Fixture(temp.newFolder(), wrongJournal = true)
        try {
            bad.start(); bad.observe(stock())
            bad.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId); bad.awaitIdle()
            assertEquals(0, bad.taps.get())
            assertFalse(bad.module.active)
            assertTrue(bad.types().isEmpty())
            assertTrue(bad.session.state.value.message.orEmpty().contains("不是同一对象"))
        } finally { bad.stop() }
    }

    @Test fun `预算lease过期意外页面节点账号或其它动作后不能确认`() = runBlocking {
        for (change in listOf("TTL", "页面", "节点", "账号", "动作", "财务")) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.buy()
                when (change) {
                    "TTL" -> fixture.now += 15_001
                    "页面" -> fixture.observe(frame(LabyrinthEntryPageState.ITEM_REWARD))
                    "节点" -> fixture.set("validatedRoute", (fixture.get("validatedRoute") as LabyrinthRouteJson).copy(currentBlockId = 50002))
                    "账号" -> fixture.set("activeRunAccountId", 2L)
                    "动作" -> fixture.call("prepareShoppingAction", LabyrinthPostEntryActionKind.SELECT_RELIC, fixture.now, null, null, null)
                    "财务" -> fixture.module.observeBalance(ShoppingTestData.wallet(999), 50001, 5, fixture.now, ShoppingTestData.encode)
                }
                fixture.confirm()
                assertEquals(change, 1, fixture.taps.get())
                assertFalse(change, fixture.types().contains("shop.purchase_confirm_prepared"))
                assertTrue(fixture.session.state.value.running)
            } finally { fixture.stop() }
        }
    }

    @Test fun `预算lease允许短期UNKNOWN加载但当前金额价格或候选不符仍不确认`() = runBlocking {
        val good = Fixture(temp.newFolder())
        try {
            good.start(); good.buy()
            good.observe(frame(LabyrinthEntryPageState.UNKNOWN))
            good.observe(frame(LabyrinthEntryPageState.GAME_LOADING_PROGRESS))
            good.confirm()
            assertEquals(2, good.taps.get())
        } finally { good.stop() }
        // 商店页仍要求当帧核实价格与意图一致：读到不同价格立即作废预算证据。
        val repriced = Fixture(temp.newFolder())
        try {
            repriced.start(); repriced.buy()
            repriced.observe(stock(goods = listOf(item.copy(priceRead = ShoppingTestData.read("101")))))
            repriced.confirm()
            assertEquals(1, repriced.taps.get())
            assertFalse(repriced.types().contains("shop.purchase_confirm_prepared"))
        } finally { repriced.stop() }
        for (bad in listOf(confirmation().copy(currencyObservation = ShoppingTestData.wallet(999)),
            confirmation().copy(shopObservation = confirmation().shopObservation!!.copy(purchaseCandidate = item.relicMatch!!.copy(displayName = "其它遗物"))))) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.buy(); fixture.observe(bad)
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, item.relicMatch!!.relicId); fixture.awaitIdle()
                assertEquals(1, fixture.taps.get())
                assertFalse(fixture.types().contains("shop.purchase_confirm_prepared"))
            } finally { fixture.stop() }
        }
    }

    @Test fun `买按钮点下后游戏仍停在商店页的过渡帧不作废预算证据`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy()
            // 手势还没落地，游戏仍是商店页：这一帧属于同一次购买的过渡，不是预算证据失效。
            assertTrue(fixture.observe(stock()))
            assertNotNull(fixture.get("shoppingPurchaseLease"))
            assertEquals("购买意图已保存，等待同一商品确认弹窗；禁止新购", fixture.session.state.value.message)
            fixture.confirm()
            assertEquals(2, fixture.taps.get())
            assertEquals("shop.purchase_confirm_dispatched", fixture.types().last())
        } finally { fixture.stop() }
    }

    @Test fun `确认弹窗遮住背景商品时仍按弹窗图标核对意图`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy()
            // 真实识别在弹窗打开时仍列出被遮罩的背景商品：价格与标题都读不出，遗物图标也不再可信。
            val covered = item.copy(categoryState = LabyrinthShopCategoryState.EXHAUSTED, priceRead = null,
                titleText = null, relicMatch = item.relicMatch!!.copy(relicId = null, displayName = null))
            val dialog = frame(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
                LabyrinthShopObservation(listOf(covered, covered.copy(slotId = "slot-2")),
                    LabyrinthShopDialogState.PURCHASE_CONFIRMATION, purchaseCandidate = item.relicMatch))
            assertFalse(fixture.observe(dialog))
            assertNotNull(fixture.get("shoppingPurchaseLease"))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, item.relicMatch!!.relicId)
            fixture.awaitIdle()
            assertEquals(2, fixture.taps.get())
            assertEquals("shop.purchase_confirm_dispatched", fixture.types().last())
        } finally { fixture.stop() }
    }

    @Test fun `刷新确认框出现前的过渡帧不作废刷新证据`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.openRefresh()
            // 点下刷新后游戏先停在商店页，刷新证据必须跨过这一两帧。
            assertFalse(fixture.observe(refreshStock()))
            assertNotNull(fixture.get("shoppingRefreshEvidence"))
            // 刷新确认框与退出确认同版；证据在这一页同样必须保留。
            val dialog = frame(LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
                LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.EXIT_CONFIRMATION))
            assertFalse(fixture.observe(dialog))
            assertNotNull(fixture.get("shoppingRefreshEvidence"))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals(2, fixture.taps.get())
            assertEquals("shop.refresh_dispatched", fixture.types().last())
            assertEquals(1, fixture.types().count { it == "shop.refresh_prepared" })
            assertNull(fixture.get("shoppingRefreshEvidence"))
        } finally { fixture.stop() }
    }

    @Test fun `刷新待确认时出现购买确认框会作废刷新证据`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.openRefresh()
            assertNotNull(fixture.get("shoppingRefreshEvidence"))
            fixture.observe(frame(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
                LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_CONFIRMATION)))
            assertNull(fixture.get("shoppingRefreshEvidence"))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals(1, fixture.taps.get())
            assertFalse(fixture.types().contains("shop.refresh_dispatched"))
        } finally { fixture.stop() }
    }

    @Test fun `刷新预算lease过期或离开过渡页不复用旧价格PNG`() = runBlocking {
        for (change in listOf("TTL", "页面", "节点")) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start()
                val goods = (1..3).map { item.copy(slotId = "slot-$it", status = LabyrinthShopItemStatus.DISABLED) }
                fixture.observe(stock(goods = goods).copy(anchorMatches = mapOf(
                    EntryAnchorId.SHOP_REFRESH_BUTTON to EntryAnchorMatch(1.0, rect)),
                    shopObservation = LabyrinthShopObservation(goods, refreshButtonEnabledEvidence = 1.0,
                        refreshPriceRead = ShoppingTestData.read("250"))))
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_REFRESH); fixture.awaitIdle()
                when (change) {
                    "TTL" -> fixture.now += 15_001
                    "页面" -> fixture.observe(frame(LabyrinthEntryPageState.ITEM_REWARD))
                    "节点" -> fixture.set("validatedRoute", (fixture.get("validatedRoute") as LabyrinthRouteJson).copy(currentBlockId = 50002))
                }
                fixture.observe(frame(LabyrinthEntryPageState.UNKNOWN))
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
                assertEquals(change, 1, fixture.taps.get())
                assertFalse(fixture.types().contains("shop.refresh_dispatched"))
            } finally { fixture.stop() }
        }
    }

    @Test fun `OCRReadState到frameResult到processFrameResult仅独立双读后保存回执持有`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        var ocrNow = 0L
        val reads = LabyrinthShopReceiptReadState { ocrNow }
        fun row(index: Int) = EntryPixelRect(20, index * LABYRINTH_SHOP_RECEIPT_COLLAGE_ROW_HEIGHT + 40, 600, 60)
        val lines = listOf(
            LabyrinthShopOcrLine("购买完成", row(0)),
            LabyrinthShopOcrLine("消耗阿尔法金币×100", row(1)),
            LabyrinthShopOcrLine("购买了${item.relicMatch!!.displayName}。", row(2)),
            LabyrinthShopOcrLine("1000", row(3)),
            LabyrinthShopOcrLine("900", row(4)),
            LabyrinthShopOcrLine("确认", row(5)))
        val button = EntryPixelRect(780, 710, 360, 65)
        try {
            fixture.start(); fixture.buy(); fixture.confirm()
            fixture.set("lastObservedPageState", LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION)
            val first = checkNotNull(reads.request(11))
            repeat(3) {
                fixture.call("processFrameResult", fixture.id(), checkNotNull(reads.frameResult(1920, 1080)), 1920, 1080, fixture.now)
            }
            reads.complete(first, lines, button)
            repeat(3) {
                reads.complete(first, lines, button)
                fixture.call("processFrameResult", fixture.id(), checkNotNull(reads.frameResult(1920, 1080)), 1920, 1080, fixture.now)
            }
            assertEquals(0, fixture.store.commits.get())
            assertEquals(0, fixture.store.pageRelicWrites.get())
            assertFalse(fixture.types().contains("shop.purchase_completed"))
            ocrNow = 200
            reads.complete(checkNotNull(reads.request(11)), lines, button)
            fixture.call("processFrameResult", fixture.id(), checkNotNull(reads.frameResult(1920, 1080)), 1920, 1080, fixture.now)
            fixture.awaitIdle()
            assertEquals(1, fixture.store.commits.get())
            assertEquals(1, fixture.count())
            assertEquals(1, fixture.types().count { it == "shop.purchase_completed" })
            assertEquals(1, fixture.types().count { it == "shop.ownership_committed" })
            assertEquals(2, fixture.taps.get())
        } finally { fixture.stop() }
    }

    @Test fun `真实suspend刷新executor超过60秒原余额帧不污染后750持锁settle迟到Executed幂等`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.start(); fixture.openRefresh()
            fixture.set("shopRelicPurchasesThisCycle", 2)
            fixture.backend = {
                assertEquals("shop.refresh_prepared", fixture.types().last())
                entered.complete(Unit); release.await(); AutomationBackendResult.Completed
            }
            fixture.state(frame(LabyrinthEntryPageState.UNKNOWN))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH)
            withTimeout(5000) { entered.await() }
            assertEquals(1, fixture.refreshCount()); assertEquals(2, fixture.count())
            val preparedEvents = fixture.events()
            val heldSince = fixture.get("actionInFlightSince")
            val frameCount = fixture.session.state.value.frameCount
            fixture.set("lastObservedPageState", LabyrinthEntryPageState.UNKNOWN)
            fixture.now += 60_001
            val originalBalanceFrame = refreshStock()
            fixture.call("processFrameResult", fixture.id(), originalBalanceFrame, 1920, 1080, fixture.now)
            assertEquals(originalBalanceFrame, fixture.session.state.value.lastResult)
            assertEquals(frameCount + 1, fixture.session.state.value.frameCount)
            assertEquals(preparedEvents, fixture.events())
            assertNotNull(fixture.module.pendingRefresh()); assertNull(fixture.module.verifiedBalance)
            assertEquals(1, fixture.refreshCount()); assertEquals(2, fixture.count())
            assertTrue((fixture.get("actionInFlight") as AtomicBoolean).get())
            assertEquals(heldSince, fixture.get("actionInFlightSince"))
            fixture.now += 1000
            val settledFrame = refreshStock(750)
            fixture.call("processFrameResult", fixture.id(), settledFrame, 1920, 1080, fixture.now)
            assertEquals(settledFrame, fixture.session.state.value.lastResult)
            assertEquals(frameCount + 2, fixture.session.state.value.frameCount)
            assertEquals("shop.refresh_settled", fixture.types().last()); assertEquals(0, fixture.count())
            assertTrue(fixture.journal.evidenceValid(fixture.run(), fixture.events().last()))
            val events = fixture.events()
            fixture.now += 1000
            fixture.call("processFrameResult", fixture.id(), settledFrame, 1920, 1080, fixture.now)
            assertEquals(events, fixture.events())
            assertEquals(1, fixture.types().count { it == "shop.refresh_settled" })
            assertTrue(fixture.session.state.value.running)
            assertTrue((fixture.get("actionInFlight") as AtomicBoolean).get())
            assertEquals(heldSince, fixture.get("actionInFlightSince"))
            assertEquals(LabyrinthEntryPageState.UNKNOWN, fixture.get("lastObservedPageState"))
            assertEquals(0, fixture.store.pageWrites.get()); assertEquals(0, fixture.store.commits.get())
            for (kind in listOf(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH,
                    LabyrinthPostEntryActionKind.SHOP_REFRESH, LabyrinthPostEntryActionKind.SHOP_CLOSE,
                    LabyrinthPostEntryActionKind.SELECT_RELIC)) fixture.dispatch(kind)
            assertEquals(2, fixture.taps.get())
            release.complete(Unit); fixture.awaitIdle()
            assertEquals(events, fixture.events()); assertEquals(1, fixture.refreshCount())
            assertFalse((fixture.get("actionInFlight") as AtomicBoolean).get())
            assertNull(fixture.module.pendingRefresh()); assertEquals(750L, fixture.module.verifiedBalance)
            assertFalse(fixture.types().contains("shop.refresh_dispatched"))
            assertEquals(Long.MIN_VALUE, fixture.get("plannedShopRefreshConfirmedAt"))
        } finally { release.complete(Unit); fixture.stop() }
    }

    @Test fun `购物锁超过60秒仍先经过channel与sessionblock门禁不读取遮挡财务`() = runBlocking {
        for (gate in listOf("channel", "sessionblock")) {
            val fixture = Fixture(temp.newFolder())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                fixture.start(); fixture.openRefresh()
                fixture.backend = { entered.complete(Unit); release.await(); AutomationBackendResult.Completed }
                fixture.state(frame(LabyrinthEntryPageState.UNKNOWN))
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH)
                withTimeout(5000) { entered.await() }
                val preparedEvents = fixture.events()
                val heldSince = fixture.get("actionInFlightSince")
                val observedAt = fixture.get("shoppingObservedFrameAt")
                val frameCount = fixture.session.state.value.frameCount
                fixture.now += 60_001
                val balanceFrame = refreshStock(750)
                val blockedFrame = if (gate == "channel") {
                    fixture.set("channelPromptVisibleUntil", fixture.now + 10_000)
                    fixture.set("channelPromptMessage", "渠道弹窗占有画面")
                    balanceFrame
                } else balanceFrame.copy(observation = balanceFrame.observation.copy(
                    anchorScores = LabyrinthAnchorScores(mapOf(EntryAnchorId.SESSION_DATE_CHANGE_TITLE to 1.0))))
                fixture.call("processFrameResult", fixture.id(), blockedFrame, 1920, 1080, fixture.now)
                assertEquals(blockedFrame, fixture.session.state.value.lastResult)
                assertEquals(frameCount + 1, fixture.session.state.value.frameCount)
                assertEquals(preparedEvents, fixture.events())
                assertEquals(observedAt, fixture.get("shoppingObservedFrameAt"))
                if (gate == "channel") assertEquals("渠道弹窗占有画面", fixture.session.state.value.message)
                else assertEquals(1, fixture.get("sessionBlockStableFrames"))
                assertNotNull(fixture.module.pendingRefresh()); assertNull(fixture.module.verifiedBalance)
                assertTrue((fixture.get("actionInFlight") as AtomicBoolean).get())
                assertEquals(heldSince, fixture.get("actionInFlightSince"))
                assertEquals(2, fixture.taps.get()); assertEquals(0, fixture.store.pageWrites.get())
                fixture.set("channelPromptVisibleUntil", Long.MIN_VALUE)
                fixture.now += 1000
                fixture.call("processFrameResult", fixture.id(), refreshStock(), 1920, 1080, fixture.now)
                assertEquals(preparedEvents, fixture.events())
                fixture.now += 1000
                fixture.call("processFrameResult", fixture.id(), balanceFrame, 1920, 1080, fixture.now)
                assertEquals("shop.refresh_settled", fixture.types().last())
                assertTrue((fixture.get("actionInFlight") as AtomicBoolean).get())
                val settledEvents = fixture.events()
                release.complete(Unit); fixture.awaitIdle()
                assertEquals(settledEvents, fixture.events()); assertEquals(2, fixture.taps.get())
                assertEquals(1, fixture.types().count { it == "shop.refresh_settled" })
                assertFalse(fixture.types().contains("shop.refresh_dispatched"))
            } finally { release.complete(Unit); fixture.stop() }
        }
    }

    @Test fun `真实刷新扣费后dispatch落盘失败重启库存settle不再付费且重复恢复不重计`() = runBlocking {
        val root = temp.newFolder()
        val first = Fixture(root)
        var log: File? = null
        var saved: File? = null
        try {
            first.start(); first.openRefresh()
            log = File(first.run().directory, "events.jsonl")
            saved = File(first.run().directory, "saved-events.jsonl")
            first.backend = {
                assertEquals("shop.refresh_prepared", first.types().last())
                Files.move(log.toPath(), saved.toPath()); check(log.mkdir())
                AutomationBackendResult.Completed
            }
            first.state(frame(LabyrinthEntryPageState.UNKNOWN))
            first.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); first.awaitIdle()
            assertEquals(2, first.taps.get())
            assertTrue(first.session.state.value.message.orEmpty().contains("可重试"))
        } finally {
            if (log?.isDirectory == true) { assertTrue(log.delete()); Files.move(saved!!.toPath(), log.toPath()) }
            first.stop()
        }
        val restarted = Fixture(root)
        try {
            restarted.start()
            restarted.set("shopPolicy", LabyrinthShopPolicy(finalArea = 5, refreshLimitsByArea = mapOf(5 to 1),
                budgetOptimizationEnabled = true))
            assertFalse(restarted.observe(refreshStock(750)))
            assertEquals("shop.refresh_settled", restarted.types().last())
            repeat(3) {
                restarted.set("shoppingRunInitialized", false)
                restarted.observe(refreshStock(750))
                assertEquals(1, restarted.refreshCount()); assertEquals(0, restarted.count())
            }
            assertFalse(restarted.call("currentShopDecision", restarted.session.state.value.lastResult) is LabyrinthShopDecision.Refresh)
            restarted.dispatch(LabyrinthPostEntryActionKind.SHOP_REFRESH); restarted.awaitIdle()
            assertEquals(0, restarted.taps.get())
            assertEquals(1, restarted.types().count { it == "shop.refresh_prepared" })
            assertEquals(1, restarted.types().count { it == "shop.refresh_settled" })
        } finally { restarted.stop() }
    }

    @Test fun `刷新未知异常跨重启差额不符只记诊断不写锚点后续正确扣费仍可结算`() = runBlocking {
        val root = temp.newFolder()
        val first = Fixture(root)
        try {
            first.start(); first.openRefresh()
            first.backend = { error("刷新可能已经扣费，结果未知") }
            first.state(frame(LabyrinthEntryPageState.UNKNOWN))
            first.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); first.awaitIdle()
            assertEquals("shop.refresh_prepared", first.types().last())
            assertFalse(first.types().contains("shop.refresh_rejected"))
        } finally { first.stop() }
        val restarted = Fixture(root)
        try {
            restarted.start()
            // 第三个数值既不能解释扣费，也不能落成锚点；它只留证据，未决状态继续 fail-closed。
            assertTrue(restarted.observe(refreshStock(800)))
            val observed = restarted.events().last()
            assertEquals("currency.observed_pending_refresh", ShoppingTestData.type(observed))
            assertEquals(800L, ShoppingTestData.payload(observed)["balance_observed"]!!.jsonPrimitive.long)
            assertEquals(1000L, ShoppingTestData.payload(observed)["balance_before"]!!.jsonPrimitive.long)
            // 只有刷新前的那个余额锚点，800 没有新增锚点。
            assertEquals(1, restarted.types().count { it == "currency.observed" })
            assertNull(restarted.module.verifiedBalance); assertNotNull(restarted.module.pendingRefresh())
            for (kind in listOf(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, LabyrinthPostEntryActionKind.SHOP_REFRESH,
                    LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH, LabyrinthPostEntryActionKind.SHOP_CLOSE,
                    LabyrinthPostEntryActionKind.SHOP_CONFIRM_EXIT)) {
                restarted.dispatch(kind, item.relicMatch!!.relicId); restarted.awaitIdle()
            }
            assertEquals(0, restarted.taps.get()); assertEquals(1, restarted.refreshCount())
            // 诊断没有污染结算区间，正确扣费读数仍然只结算一次。
            assertFalse(restarted.observe(refreshStock(750)))
            assertEquals("shop.refresh_settled", restarted.types().last())
            assertEquals(1, restarted.types().count { it == "shop.refresh_settled" })
            assertNull(restarted.module.pendingRefresh())
            assertEquals(750L, restarted.module.verifiedBalance)
            assertFalse(restarted.observe(refreshStock(750)))
            assertEquals(1, restarted.types().count { it == "shop.refresh_settled" })
        } finally { restarted.stop() }
    }

    @Test fun `确定刷新Rejected释放prepared额度可重新正常刷新`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.openRefresh()
            fixture.backend = { AutomationBackendResult.Rejected("确定未点击") }
            fixture.state(frame(LabyrinthEntryPageState.UNKNOWN))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals("shop.refresh_rejected", fixture.types().last()); assertEquals(0, fixture.refreshCount())
            assertEquals(1000L, fixture.module.verifiedBalance); assertNull(fixture.module.pendingRefresh())
            fixture.backend = { AutomationBackendResult.Completed }
            fixture.openRefresh(); fixture.state(frame(LabyrinthEntryPageState.UNKNOWN))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals(1, fixture.refreshCount()); assertEquals(2, fixture.types().count { it == "shop.refresh_prepared" })
            fixture.observe(refreshStock(750))
            assertEquals("shop.refresh_settled", fixture.types().last()); assertEquals(1, fixture.refreshCount())
        } finally { fixture.stop() }
    }

    @Test fun `refresh journal失败不点击确认并保持原PNG可安全重试`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        var log: File? = null
        var saved: File? = null
        try {
            fixture.start(); fixture.openRefresh()
            log = File(fixture.run().directory, "events.jsonl")
            saved = File(fixture.run().directory, "saved-events.jsonl")
            Files.move(log.toPath(), saved.toPath()); assertTrue(log.mkdir())
            fixture.set("shoppingFrameEncoder", ShoppingTestData.neverEncode)
            fixture.set("shoppingFramePng", { error("bitmap已回收") })
            fixture.state(frame(LabyrinthEntryPageState.UNKNOWN))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals(1, fixture.taps.get()); assertEquals(0, fixture.refreshCount())
            assertTrue(log.delete()); Files.move(saved.toPath(), log.toPath())
            fixture.observe(frame(LabyrinthEntryPageState.UNKNOWN))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals(2, fixture.taps.get()); assertEquals(1, fixture.refreshCount())
        } finally {
            if (log?.isDirectory == true) { assertTrue(log.delete()); Files.move(saved!!.toPath(), log.toPath()) }
            fixture.stop()
        }
    }

    private fun refreshStock(balance: Long = 1000): LabyrinthEntryFrameResult {
        val goods = (1..3).map { item.copy(slotId = "slot-$it", status = LabyrinthShopItemStatus.DISABLED) }
        return stock(balance, goods).copy(shopObservation = LabyrinthShopObservation(goods,
            refreshButtonEnabledEvidence = 1.0, refreshPriceRead = ShoppingTestData.read("250")))
    }

    private fun confirmation() = frame(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
        LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_CONFIRMATION, purchaseCandidate = item.relicMatch))

    private fun stock(balance: Long = 1000, goods: List<LabyrinthShopItemMatch> = listOf(item)) =
        frame(LabyrinthEntryPageState.SHOP, LabyrinthShopObservation(goods)).copy(
            currencyObservation = ShoppingTestData.wallet(balance),
            // 预算策略需要“刷新可用性已知”：按钮锚点已识别，但默认灰态，不能误当成尚未读取。
            anchorMatches = mapOf(EntryAnchorId.SHOP_REFRESH_BUTTON to EntryAnchorMatch(1.0, rect)))
    private fun completed(value: LabyrinthShopPurchaseReceiptObservation = receipt) =
        frame(LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
            LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_COMPLETE, purchaseReceipt = value))
    private fun frame(page: LabyrinthEntryPageState, shop: LabyrinthShopObservation? = null,
        special: LabyrinthSpecialEquipmentObservation? = null) = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(page, 1.0, emptyMap(), LabyrinthAnchorScores(emptyMap())),
        matchedFeatures = emptyList(), elapsedMillis = 0, shopObservation = shop, specialEquipment = special,
        frameWidth = 1920, frameHeight = 1080)

    private inner class Fixture(val root: File, val store: IdempotentStore = IdempotentStore(), val enabled: Boolean = true,
        val moduleOnly: Boolean = false, val wrongJournal: Boolean = false) {
        val journal = LabyrinthShoppingJournal(root)
        val module = LabyrinthShoppingModule(journal)
        val manager = AutomationSessionManager()
        val taps = AtomicInteger()
        val resolutions = AtomicInteger()
        var now = 1000L
        var backend: suspend () -> AutomationBackendResult = { AutomationBackendResult.Completed }
        val session: LabyrinthEntryRecognitionSession by lazy { LabyrinthEntryRecognitionSession(
            sessionManager = manager, captureActive = { true },
            processorFactory = { { error("集成测试不创建 Bitmap") } },
            actionExecutor = SessionBoundActionExecutor(manager) {
                if (enabled && taps.get() == 0 && session.state.value.lastResult?.observation?.state == LabyrinthEntryPageState.SHOP &&
                    session.state.value.lastResult?.shopObservation?.items?.any { it.purchasable } == true) {
                    check(types().contains("shop.purchase_intent")) { "购买意图必须先于真实 tap 保存" }
                }
                if (enabled && session.state.value.lastResult?.observation?.state == LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION)
                    check(types().last() == "shop.purchase_confirm_prepared") { "prepared必须先于真实executor落盘" }
                taps.incrementAndGet(); backend()
            },
            actionsAvailable = { true }, clock = { now }, runStateStore = store,
            shoppingRelicResolver = { identity ->
                resolutions.incrementAndGet()
                item.relicMatch?.takeIf { it.relicId == identity || it.displayName == identity }
            },
            shoppingJournal = if (!enabled || moduleOnly) null else if (wrongJournal) LabyrinthShoppingJournal(root) else journal,
            shoppingModule = if (enabled) module else null,
        ) }
        suspend fun start() {
            assertTrue(session.startAutomation(accountId = 1) is LabyrinthEntryRecognitionStartResult.Started)
            set("validatedRoute", LabyrinthRouteJson(100, 1, 1, 1, false, false, "", emptyList(), emptyList(),
                listOf(LabyrinthNodeJson(5, 1, 1, 50001, LabyrinthNodeTypes.SHOP, null, emptyList(), false)), currentBlockId = 50001))
            set("activeNodeType", LabyrinthNodeTypes.SHOP)
            set("activeNodeArea", 5)
            set("shoppingFrameEncoder", ShoppingTestData.encode)
            set("shoppingFramePng", { ShoppingTestData.raw })
        }
        suspend fun stop() { session.stop(releaseCapture = false) }
        fun id() = get("activeSessionId") as AutomationSessionId
        fun run() = journal.open(1, 100, null, now)
        fun events() = journal.read(run())
        fun types() = events().map(ShoppingTestData::type)
        fun count() = get("shopRelicPurchasesThisCycle") as Int
        fun refreshCount() = get("shopRefreshesThisVisit") as Int
        suspend fun openRefresh() {
            assertFalse(observe(refreshStock()))
            dispatch(LabyrinthPostEntryActionKind.SHOP_REFRESH); awaitIdle()
        }
        fun field(name: String) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun get(name: String): Any? = field(name).get(session)
        fun set(name: String, value: Any?) = field(name).set(session, value)
        @Suppress("UNCHECKED_CAST")
        fun state(result: LabyrinthEntryFrameResult) {
            val flow = get("_state") as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            flow.value = flow.value.copy(lastResult = result)
        }
        fun observe(result: LabyrinthEntryFrameResult): Boolean {
            now += 1000
            state(result)
            set("shoppingFrameEncoder", ShoppingTestData.encode)
            set("shoppingFramePng", { ShoppingTestData.raw })
            return call("handleShoppingFrame", id(), result, now, ShoppingTestData.encode) as Boolean
        }
        suspend fun buy() {
            assertFalse(observe(stock()))
            dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
            awaitIdle()
        }
        suspend fun confirm() {
            observe(frame(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
                LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_CONFIRMATION, purchaseCandidate = item.relicMatch)))
            dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, item.relicMatch!!.relicId)
            awaitIdle()
        }
        fun dispatch(kind: LabyrinthPostEntryActionKind, relicId: String? = null, imprint: String? = null) =
            call("dispatchPostEntryTap", id(), kind, "集成测试点击", rect, now,
                null, null, relicId, imprint, null, false, emptyList<String>())
        suspend fun awaitIdle() {
            withTimeout(5000) { while ((get("actionInFlight") as AtomicBoolean).get() ||
                (get("shoppingOwnershipInFlight") as AtomicBoolean).get()) delay(10) }
        }
        fun call(name: String, vararg arguments: Any?): Any? {
            val method = session.javaClass.declaredMethods.single {
                (it.name == name || it.name.startsWith("$name-")) && it.parameterCount == arguments.size
            }.apply { isAccessible = true }
            val adapted = method.parameterTypes.mapIndexed { index, type ->
                val value = arguments[index]
                when {
                    type == java.lang.Long.TYPE -> (value as? AutomationSessionId)?.value ?: (value as Number).toLong()
                    type == java.lang.Integer.TYPE -> (value as Number).toInt()
                    else -> value
                }
            }.toTypedArray()
            return method.invoke(session, *adapted)
        }
    }

    private class IdempotentStore : LabyrinthRunStateStore {
        val commits = AtomicInteger()
        val pageRelicWrites = AtomicInteger()
        val pageWrites = AtomicInteger()
        private val receipts = mutableSetOf<String>()
        @Volatile var failBeforeCommit = false
        @Volatile var failAfterCommit = false
        @Volatile var snapshot = LabyrinthRunSnapshot("account:1", 1, "ACTIVE", null, 0, emptyList(), 0)
        override suspend fun begin(accountId: Long?, nowMillis: Long) = snapshot
        override suspend fun startNew(accountId: Long?, nowMillis: Long) = snapshot
        override suspend fun load(accountId: Long?) = snapshot
        override suspend fun recordPage(accountId: Long?, page: String, characters: List<LabyrinthCharacterMatch>,
            nowMillis: Long, relics: List<LabyrinthRelicMatch>, replaceCharacters: Boolean): LabyrinthRunSnapshot {
            if (relics.isNotEmpty()) pageRelicWrites.incrementAndGet()
            snapshot = snapshot.copy(joinedCharacters = LabyrinthRosterMerger.merge(snapshot.joinedCharacters, characters, nowMillis),
                observedRelics = LabyrinthRelicMerger.merge(snapshot.observedRelics, relics, nowMillis))
            pageWrites.incrementAndGet()
            return snapshot
        }
        override suspend fun recordShopPurchase(accountId: Long?, receiptId: String, page: String, nowMillis: Long,
            relics: List<LabyrinthRelicMatch>): LabyrinthRunSnapshot {
            if (failBeforeCommit) error("模拟 Room 可重试失败")
            if (receipts.add(receiptId)) {
                commits.incrementAndGet()
                snapshot = snapshot.copy(currentPage = page,
                    observedRelics = LabyrinthRelicMerger.merge(snapshot.observedRelics, relics, nowMillis))
            }
            if (failAfterCommit) {
                failAfterCommit = false
                error("模拟 Room 已提交但 journal mark 尚未完成的中断")
            }
            return snapshot
        }
    }
}
