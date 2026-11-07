package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.vision.*
import java.io.File
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

    @Test fun `实际运行账号快照隔离设置变化且公会3赠送誓约只影响价格`() = runBlocking {
        val cases = listOf(Triple(1L, 1, 100L), Triple(1L, 3, 90L), Triple(2L, 1, 75L), Triple(2L, 3, 65L))
        for ((account, guild, expected) in cases) {
            var settings = LabyrinthStrategySettings(node21AcquiredAccountIds = setOf(2L))
            val fixture = Fixture(temp.newFolder(), accountId = account, guildId = guild,
                strategy = { LabyrinthStrategySnapshot(settings, null) })
            try {
                fixture.start()
                settings = settings.copy(node21AcquiredAccountIds = setOf(1L))
                val current = stock()
                assertFalse(fixture.observe(current))
                val decision = fixture.call("currentShopDecision", fixture.session.state.value.lastResult) as LabyrinthShopDecision.BuyRelic
                assertEquals(expected, decision.item.price)
                assertEquals(400, decision.budget!!.directScore)
                val pricing = fixture.get("shoppingPricingContext") as LabyrinthShopPricingContext
                assertEquals(account, pricing.accountId); assertEquals(100L, pricing.enterId)
                assertEquals(account == 2L, pricing.node21Acquired); assertEquals(guild == 3, pricing.hasOath)
                assertTrue(fixture.store.snapshot.observedRelics.isEmpty())
                assertTrue(fixture.session.state.value.observedRelics.isEmpty())
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
                fixture.awaitIdle()
                val intent = fixture.events().single { ShoppingTestData.type(it) == "shop.purchase_intent" }
                assertEquals(expected, ShoppingTestData.payload(intent)["expected_spent"]!!.jsonPrimitive.long)
                assertEquals(JsonPrimitive("catalog"), ShoppingTestData.payload(intent)["price_source"])
                assertFalse(ShoppingTestData.payload(intent).containsKey("price_confirmations"))
                assertTrue(fixture.store.snapshot.observedRelics.isEmpty())
                fixture.confirm()
                assertTrue(fixture.observe(completed(ShoppingTestData.receipt(spent = expected))))
                fixture.awaitIdle()
                assertEquals(1000L - expected, fixture.module.verifiedBalance)
                assertTrue(fixture.store.snapshot.observedRelics.none { it.relicId == "40003" })
            } finally { fixture.stop() }
        }
    }

    @Test fun `完整购买先存意图再点击确认回执持有最后关闭`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start()
            fixture.buy()
            assertEquals(listOf("currency.observed", "shop.purchase_intent"), fixture.types())
            assertEquals(1, fixture.taps.get())
            assertTrue(fixture.events().all { it["run_id"] == JsonPrimitive(fixture.run().runId) })
            fixture.confirm()
            assertEquals("shop.purchase_confirm_dispatched", fixture.types().last())
            assertEquals(0, fixture.store.commits.get())
            assertTrue(fixture.observe(completed()))
            fixture.awaitIdle()
            assertEquals(1, fixture.store.commits.get())
            assertEquals(1, fixture.count())
            assertTrue(fixture.canDismiss(receipt))
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

    @Test fun `日志IO异常不得点击购买或关闭且会话继续`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start()
            fixture.observe(stock())
            ShoppingTestData.withBlockedAppend(fixture.run()) {
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
            }
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
            assertFalse(fixture.canDismiss(pending))
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
            assertFalse(fixture.canDismiss(receipt))
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
            assertFalse(first.canDismiss(receipt))
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

    @Test fun `钱包变化后旧余额不能授权缺少新双读的商品`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.observe(stock())
            fixture.session.shopWalletChanged()
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
            assertFalse(fixture.canDismiss(receipt))
        } finally { fixture.stop() }
    }

    @Test fun `回执后商品页复用实际余额且恢复切店必须重读`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy(); fixture.confirm()
            fixture.observe(completed()); fixture.awaitIdle()
            fixture.observe(completed())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE); fixture.awaitIdle()
            assertEquals(900L, fixture.module.verifiedBalance(50001, 5))
            fixture.observe(stock(balance = 900).copy(currencyObservation = null))
            assertFalse(fixture.session.shopWalletReadRequired())
            assertEquals(900L, fixture.call("reusableShoppingBalance"))
            assertNull(fixture.session.state.value.lastResult?.currencyObservation)
            fixture.nextShop()
            fixture.observe(stock(balance = 900).copy(currencyObservation = null))
            assertTrue(fixture.session.shopWalletReadRequired())
            assertNull(fixture.call("reusableShoppingBalance"))
        } finally { fixture.stop() }
    }

    @Test fun `三笔随机商品成功提交后真实单屏覆盖不依赖遗物计数`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start()
            var balance = 1000L
            for (index in 0..2) {
                val role = ShoppingTestData.role(role = "攻击").copy(slotId = "shop_item_${index + 4}")
                val source = stock(balance, listOf(role))
                val original = source.shopObservation!!.items
                val moved = original.mapIndexed { position, current ->
                    val identity = when (position) { 0 -> original[index + 3]; index + 3 -> original[0]; else -> current }
                    identity.copy(slotId = current.slotId, screenRect = current.screenRect, buyButtonRect = current.buyButtonRect)
                }
                fixture.observe(source.copy(shopObservation = source.shopObservation!!.copy(items = moved)))
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_IMPRINT, imprint = role.roleImprintLabel)
                fixture.awaitIdle()
                fixture.observe(imprintConfirmation(role).copy(currencyObservation = ShoppingTestData.wallet(balance)
                    .copy(source = LabyrinthCurrencySource.PURCHASE_CONFIRMATION)))
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_IMPRINT, imprint = role.roleImprintLabel)
                fixture.awaitIdle()
                val actual = ShoppingTestData.receipt(role.titleText!!, before = balance)
                fixture.observe(completed(actual)); fixture.awaitIdle()
                fixture.observe(completed(actual))
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE); fixture.awaitIdle()
                balance -= 100
                assertEquals(index + 1, fixture.module.successfulPurchaseCount(50001, 5))
                assertEquals(0, fixture.count())
            }
            val scanner = fixture.get("shopStockScanner") as LabyrinthShopStockScanner
            assertEquals(setOf("shop_item_4", "shop_item_5", "shop_item_6"), fixture.module.inventorySummary(50001, 5).purchasedSlots)
            assertEquals(setOf("shop_item_4", "shop_item_5", "shop_item_6"), scanner.purchasedSlots())
            val goods = ShopScanFixtures.goods().mapIndexed { index, item -> if (index >= 3)
                item.copy(status = LabyrinthShopItemStatus.UNKNOWN, categoryState = LabyrinthShopCategoryState.READING,
                    price = null, titleText = null, roleImprintLabel = null) else item }
            val raw = frame(LabyrinthEntryPageState.SHOP, ShopScanFixtures.a(scanner.epoch, goods))
                .copy(currencyObservation = null)
            fixture.now += 1000
            assertFalse((fixture.call("resolveShopStockFrame", raw, fixture.now) as LabyrinthEntryFrameResult)
                .shopObservation!!.stockComplete)
            fixture.now += 1000
            fixture.session.shopWalletChanged()
            assertEquals(setOf("shop_item_4", "shop_item_5", "shop_item_6"), scanner.purchasedSlots())
            val transient = frame(LabyrinthEntryPageState.NODE_SELECTION)
            fixture.call("resolveShopStockFrame", transient, fixture.now)
            fixture.now += 1000
            assertFalse((fixture.call("resolveShopStockFrame", raw.copy(shopObservation = raw.shopObservation!!.copy(stockEpoch = scanner.epoch)), fixture.now) as LabyrinthEntryFrameResult)
                .shopObservation!!.stockComplete)
            fixture.now += 1000
            val resolved = fixture.call("resolveShopStockFrame", raw.copy(shopObservation = raw.shopObservation!!.copy(stockEpoch = scanner.epoch)), fixture.now) as LabyrinthEntryFrameResult
            assertTrue(resolved.shopObservation!!.stockComplete)
            assertEquals(3, resolved.shopObservation!!.items.size)
            assertEquals(LabyrinthShopCoverageMode.SINGLE_SCREEN, resolved.shopObservation!!.coverage?.mode)
            val oldEpoch = scanner.epoch
            fixture.call("syncShopContinuity", false)
            assertEquals(oldEpoch, scanner.epoch)
            fixture.now += 1000
            fixture.call("bindShopStockScope", 1921, 1080)
            assertTrue(scanner.epoch > oldEpoch)
            assertEquals(setOf("shop_item_4", "shop_item_5", "shop_item_6"), scanner.purchasedSlots())
            fixture.now += 1000
            fixture.call("resolveShopStockFrame", transient, fixture.now)
            assertTrue(scanner.purchasedSlots().isNotEmpty())
            fixture.now += 1000
            fixture.call("resolveShopStockFrame", transient, fixture.now)
            assertTrue(scanner.purchasedSlots().isEmpty())
            fixture.call("syncShopContinuity", false)
            assertTrue(scanner.purchasedSlots().isEmpty())
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
            fixture.observe(imprintConfirmation(role))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_IMPRINT, imprint = role.roleImprintLabel)
            fixture.awaitIdle()
            val roleReceipt = ShoppingTestData.receipt(role.titleText!!)
            fixture.observe(completed(roleReceipt)); fixture.awaitIdle()
            assertTrue(fixture.canDismiss(roleReceipt))
            assertEquals(1, fixture.store.commits.get())
            assertTrue(fixture.store.snapshot.joinedCharacters.isEmpty())
            assertTrue(fixture.store.snapshot.observedRelics.isEmpty())
            assertEquals(0, fixture.count())
            assertFalse(fixture.observe(completed(roleReceipt)))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE); fixture.awaitIdle()
            assertEquals(3, fixture.taps.get())
            assertEquals(listOf("currency.observed", "shop.purchase_intent", "shop.purchase_confirm_prepared",
                "shop.purchase_confirm_dispatched", "shop.purchase_completed", "shop.ownership_committed"), fixture.types())
        } finally { fixture.stop() }
    }

    @Test fun `随机与选择印记均由当前标题双读授权且不增加余额事件`() = runBlocking {
        for (choice in listOf(false, true)) {
            val role = ShoppingTestData.role(choice)
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.observe(stock(goods = listOf(role)))
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_IMPRINT, imprint = role.roleImprintLabel); fixture.awaitIdle()
                fixture.observe(imprintConfirmation(role))
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_IMPRINT, imprint = role.roleImprintLabel); fixture.awaitIdle()
                assertEquals(2, fixture.taps.get())
                assertEquals("shop.purchase_confirm_dispatched", fixture.types().last())
                assertEquals(1, fixture.types().count { it == "currency.observed" })
            } finally { fixture.stop() }
        }
    }

    @Test fun `印记确认必须独立双读当前职能与随机选择类型而非回显计划`() = runBlocking {
        val role = ShoppingTestData.role()
        val reads = listOf(null, ShoppingTestData.read(role.titleText, 1), ShoppingTestData.read("治疗型职能的随机印记"),
            ShoppingTestData.read("攻击型职能的选择印记"), ShoppingTestData.read("攻击型职能印记"),
            ShoppingTestData.read("攻击型职能的随机选择印记"))
        for (title in reads) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.observe(stock(goods = listOf(role)))
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_IMPRINT, imprint = role.roleImprintLabel); fixture.awaitIdle()
                val current = imprintConfirmation(role)
                fixture.observe(current.copy(shopObservation = current.shopObservation!!.copy(confirmationTitle = title)))
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_IMPRINT, imprint = role.roleImprintLabel); fixture.awaitIdle()
                assertEquals(1, fixture.taps.get())
                assertEquals(listOf("currency.observed", "shop.purchase_intent"), fixture.types())
            } finally { fixture.stop() }
        }
    }

    @Test fun `真实刷新确认独立双读后冻结本次规则价格与前余额`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start()
            val stock = refreshStock()
            fixture.observe(stock)
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_REFRESH); fixture.awaitIdle()
            assertFalse(fixture.types().contains("shop.refresh_dispatched"))
            fixture.state(refreshConfirmation())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            val event = fixture.events().single { ShoppingTestData.type(it) == "shop.refresh_dispatched" }
            assertEquals(300L, ShoppingTestData.payload(event)["refresh_price"]!!.jsonPrimitive.long)
            assertEquals(1000L, ShoppingTestData.payload(event)["balance_before"]!!.jsonPrimitive.long)
            assertEquals(setOf("manifest.json", "events.jsonl"), fixture.run().directory.listFiles()!!.map { it.name }.toSet())
            fixture.observe(stock.copy(currencyObservation = ShoppingTestData.wallet(700)))
            assertEquals("shop.refresh_settled", fixture.types().last())
            assertEquals(listOf("currency.observed", "shop.refresh_prepared", "shop.refresh_dispatched", "shop.refresh_settled"), fixture.types())
            assertEquals(700L, fixture.module.verifiedBalance)
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
            val legacy = stock(goods = listOf(item.copy(price = null, relicMatch = item.relicMatch?.copy(basePrice = null)))).copy(currencyObservation = null)
            fixture.state(legacy)
            assertTrue(fixture.call("currentShopDecision", fixture.session.state.value.lastResult) is LabyrinthShopDecision.BuyRelic)
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
            assertTrue(fixture.events().last()["source"] == JsonPrimitive("actual_screen"))
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
            assertTrue(restarted.canDismiss(receipt))
        } finally { restarted.stop() }
    }

    @Test fun `未prepared意图重启必须重新取得当前确认身份及钱包双读`() = runBlocking {
        val root = temp.newFolder()
        val first = Fixture(root)
        try { first.start(); first.buy() } finally { first.stop() }
        val restarted = Fixture(root)
        try {
            restarted.start()
            assertTrue(restarted.observe(confirmation().copy(currencyObservation = null)))
            restarted.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, item.relicMatch!!.relicId); restarted.awaitIdle()
            assertEquals(0, restarted.taps.get())
            assertEquals("shop.purchase_intent", restarted.types().last())
            restarted.confirm()
            assertEquals(1, restarted.taps.get())
            assertEquals("shop.purchase_confirm_dispatched", restarted.types().last())
            assertEquals(1, restarted.types().count { it == "currency.observed" })
        } finally { restarted.stop() }
    }

    @Test fun `购物页无进展超期放弃本店取消弹窗且下一店可新购`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.trackTerminal(); fixture.buy()
            val waiting = confirmation().copy(currencyObservation = null)
            assertTrue(fixture.observe(waiting))
            fixture.awaitIdle()
            assertTrue(fixture.session.state.value.running)
            fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS
            assertTrue(fixture.observe(waiting))
            fixture.awaitIdle()
            assertTrue(fixture.session.state.value.running)
            assertTrue(fixture.session.state.value.message.orEmpty().contains("购物流程连续"))
            assertEquals(1, fixture.taps.get())
            assertEquals("shop.purchase_abandoned", fixture.types().last())
            assertNull(LabyrinthShopReceiptLedger.pendingIntent(fixture.events()))
            assertNull(fixture.module.verifiedBalance)
            val wide = confirmation().copy(frameWidth = 2400)
            assertTrue(fixture.observe(wide)); fixture.awaitIdle()
            assertEquals(2, fixture.taps.get())
            assertEquals(AutomationAction.Tap(ScreenPoint(981f, 868.5f)), fixture.actions.last())
            val close = EntryPixelRect(1400, 900, 400, 100)
            fixture.now += 2000
            assertTrue(fixture.observe(stock().copy(anchorMatches = mapOf(EntryAnchorId.SHOP_CLOSE to EntryAnchorMatch(1.0, close)))))
            fixture.awaitIdle()
            assertEquals(AutomationAction.Tap(ScreenPoint(1600f, 950f)), fixture.actions.last())
            fixture.now += 2000
            val exit = frame(LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
                LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.EXIT_CONFIRMATION,
                    refreshConfirmationTitle = ShoppingTestData.read("商店确认"))).copy(
                observation = LabyrinthEntryPageObservation(LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION, 1.0,
                    emptyMap(), LabyrinthAnchorScores(mapOf(EntryAnchorId.SHOP_TITLE to 1.0))),
                nodeMoveConfirmation = LabyrinthNodeMoveConfirmationObservation(1.0, rect, rect),
                anchorMatches = mapOf(
                    EntryAnchorId.SHOP_EXIT_CONFIRMATION_TITLE to EntryAnchorMatch(1.0, rect),
                    EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON to EntryAnchorMatch(1.0, rect)))
            assertTrue(fixture.observe(exit)); fixture.awaitIdle()
            assertEquals(4, fixture.taps.get())
            assertEquals(AutomationAction.Tap(ScreenPoint(20f, 10f)), fixture.actions.last())
            assertFalse(fixture.observe(frame(LabyrinthEntryPageState.NODE_SELECTION)))
            fixture.nextShop()
            assertFalse(fixture.observe(stock(balance = 900)))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId); fixture.awaitIdle()
            assertEquals(2, fixture.types().count { it == "shop.purchase_intent" })
            assertEquals("shop.purchase_intent", fixture.types().last())
            assertEquals(0, fixture.store.commits.get())
            assertTrue(fixture.terminals.isEmpty())
        } finally { fixture.stop() }
    }

    @Test fun `页面变化只给一次过渡额度`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy()
            // 第一次变化（商店页 → UNKNOWN）拿到过渡额度。
            assertTrue(fixture.observe(frame(LabyrinthEntryPageState.UNKNOWN)))
            fixture.awaitIdle()
            assertTrue(fixture.session.state.value.running)
            // 第二次变化在 +100 秒：若每次变化都重置，计时会回到 0。
            fixture.now += 100_000
            assertTrue(fixture.observe(confirmation().copy(currencyObservation = null)))
            fixture.awaitIdle()
            assertTrue(fixture.session.state.value.running)
            // 再次变化不重置期限，累计等待到期后只放弃本店。
            fixture.now += 100_000
            assertTrue(fixture.observe(frame(LabyrinthEntryPageState.UNKNOWN)))
            fixture.awaitIdle()
            assertTrue(fixture.session.state.value.running)
            assertTrue(fixture.session.state.value.message.orEmpty().contains("购物流程连续"))
            assertEquals("shop.purchase_abandoned", fixture.types().last())
            assertNull(LabyrinthShopReceiptLedger.pendingIntent(fixture.events()))
        } finally { fixture.stop() }
    }

    @Test fun `手势被拒绝后未恢复且超期放弃本店`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.trackTerminal(); fixture.observe(stock())
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
            assertTrue(fixture.session.state.value.running)
            assertNotNull(pendingExit(fixture))
            assertNull(LabyrinthShopReceiptLedger.pendingIntent(fixture.events()))
            assertTrue(fixture.terminals.isEmpty())
        } finally { fixture.stop() }
    }

    @Test fun `连续三次确定拒绝放弃本店不再写意图且不污染下一店`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.trackTerminal(); fixture.observe(stock())
            fixture.backend = { AutomationBackendResult.Rejected("无障碍手势被系统取消") }
            repeat(SHOPPING_REJECTION_BUDGET) {
                // Every real backend attempt invalidates visuals, including a definite rejection.
                fixture.observe(stock())
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
                fixture.awaitIdle()
            }
            assertTrue(fixture.session.state.value.running)
            assertTrue(fixture.session.state.value.message.orEmpty().contains("连续${SHOPPING_REJECTION_BUDGET}次被确定拒绝"))
            assertEquals(SHOPPING_REJECTION_BUDGET, fixture.types().count { it == "shop.purchase_intent" })
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId); fixture.awaitIdle()
            assertEquals(SHOPPING_REJECTION_BUDGET, fixture.types().count { it == "shop.purchase_intent" })
            fixture.nextShop(); fixture.backend = { AutomationBackendResult.Completed }
            assertFalse(fixture.observe(stock()))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId); fixture.awaitIdle()
            assertEquals(SHOPPING_REJECTION_BUDGET + 1, fixture.types().count { it == "shop.purchase_intent" })
            assertTrue(fixture.terminals.isEmpty())
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
            // 超时放弃先排队，不能与仍在 executor 中的动作并发。
            // 先用掉过渡额度，确保这一帧确实命中期限。
            fixture.set("shoppingHoldPage", LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION)
            fixture.set("shoppingTransitionGranted", true)
            fixture.now += 61_000
            fixture.call("processFrameResult", fixture.id(), confirmation(), 1920, 1080, fixture.now)
            assertTrue((fixture.get("actionInFlight") as AtomicBoolean).get())
            assertEquals(1, fixture.taps.get())
            assertNotNull(pendingExit(fixture))
            assertTrue(fixture.session.state.value.running)
            gate.complete(Unit)
            fixture.awaitIdle()
            // 动作确实完成，未落地的超时放弃请求可被真实进展撤销。
            assertNull(pendingExit(fixture))
            assertFalse(fixture.types().contains("shop.purchase_abandoned"))
            assertTrue(fixture.session.state.value.running)
        } finally {
            gate.complete(Unit)
            fixture.stop()
        }
    }

    @Test fun `购物动作超期先保留真实动作锁结束后再放弃并取消弹窗`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        try {
            fixture.start(); fixture.trackTerminal(); fixture.buy()
            fixture.backend = { started.complete(Unit); gate.await(); error("确认执行结果未知") }
            fixture.observe(confirmation())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, item.relicMatch!!.relicId)
            withTimeout(5000) { started.await() }
            assertTrue(fixture.get("shoppingConfirmationInFlight") as Boolean)
            fixture.now += 61_000
            fixture.set("shoppingLastProgressAt", fixture.now - SHOPPING_HOLD_TIMEOUT_MILLIS - 1_000)
            fixture.set("shoppingHoldPage", LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION)
            fixture.set("shoppingTransitionGranted", true)
            fixture.call("processFrameResult", fixture.id(), confirmation(), 1920, 1080, fixture.now)
            assertTrue((fixture.get("actionInFlight") as AtomicBoolean).get())
            assertNotNull(pendingExit(fixture))
            assertEquals(2, fixture.taps.get())
            assertFalse(fixture.types().contains("shop.purchase_abandoned"))
            fixture.observe(confirmation())
            assertEquals(2, fixture.taps.get())
            assertTrue(fixture.terminals.isEmpty())
            gate.complete(Unit)
            fixture.awaitIdle()
            assertEquals("shop.purchase_abandoned", fixture.types().last())
            assertFalse(fixture.types().contains("shop.purchase_confirm_rejected"))
            assertFalse(fixture.types().contains("shop.purchase_confirm_dispatched"))
            fixture.backend = { AutomationBackendResult.Completed }
            fixture.now += 2000
            fixture.observe(confirmation()); fixture.awaitIdle()
            assertEquals(3, fixture.taps.get())
            assertEquals(AutomationAction.Tap(ScreenPoint(741f, 868.5f)), fixture.actions.last())
            assertTrue(fixture.session.state.value.running)
            assertTrue(fixture.terminals.isEmpty())
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
    private fun pendingExit(fixture: Fixture): Any? =
        (fixture.get("pendingShoppingExit") as AtomicReference<Any?>).get()

    @Test fun `持续UNKNOWN超期放弃购买不盲点且下一店可新购`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.trackTerminal(); fixture.buy()
            assertTrue(fixture.observe(frame(LabyrinthEntryPageState.UNKNOWN)))
            fixture.awaitIdle()
            assertTrue(fixture.session.state.value.running)
            fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS
            assertTrue(fixture.observe(frame(LabyrinthEntryPageState.UNKNOWN)))
            fixture.awaitIdle()
            assertTrue(fixture.session.state.value.running)
            assertTrue(fixture.session.state.value.message.orEmpty().contains("当前页面 UNKNOWN"))
            assertEquals("shop.purchase_abandoned", fixture.types().last())
            assertTrue(fixture.observe(frame(LabyrinthEntryPageState.UNKNOWN)))
            assertEquals(1, fixture.taps.get())
            fixture.nextShop()
            fixture.buy()
            assertEquals(2, fixture.types().count { it == "shop.purchase_intent" })
            assertTrue(fixture.terminals.isEmpty())
        } finally { fixture.stop() }
    }

    @Test fun `未决刷新店外超期只放弃原店并释放下一店`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.trackTerminal(); fixture.openRefresh()
            fixture.backend = { error("刷新可能已经扣费，结果未知") }
            fixture.state(refreshConfirmation())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals("shop.refresh_prepared", fixture.types().last())
            fixture.set("activeNodeType", LabyrinthNodeTypes.EVENT)
            assertTrue(fixture.observe(frame(LabyrinthEntryPageState.NODE_SELECTION)))
            fixture.awaitIdle()
            assertTrue(fixture.session.state.value.running)
            fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS
            assertTrue(fixture.observe(frame(LabyrinthEntryPageState.NODE_SELECTION)))
            fixture.awaitIdle()
            assertTrue(fixture.session.state.value.running)
            assertTrue(fixture.session.state.value.message.orEmpty().contains("NODE_SELECTION"))
            assertEquals(listOf("currency.observed", "shop.refresh_prepared", "shop.refresh_abandoned"), fixture.types())
            assertNull(fixture.module.pendingRefresh())
            assertNull(fixture.module.verifiedBalance)
            fixture.backend = { AutomationBackendResult.Completed }
            fixture.nextShop()
            assertFalse(fixture.observe(stock(balance = 700)))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId); fixture.awaitIdle()
            assertEquals("shop.purchase_intent", fixture.types().last())
            assertEquals(0, fixture.refreshCount())
            assertFalse(fixture.types().contains("shop.refresh_settled"))
            assertFalse(fixture.types().contains("shop.refresh_rejected"))
            assertTrue(fixture.terminals.isEmpty())
        } finally { fixture.stop() }
    }

    @Test fun `prepareConfirmation返回false不得进入executor也不追加prepared`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy()
            assertTrue(ShoppingTestData.prepare(fixture.module, fixture.now))
            fixture.confirm()
            assertEquals(1, fixture.taps.get())
            assertEquals(1, fixture.types().count { it == "shop.purchase_confirm_prepared" })
            assertFalse(fixture.types().contains("shop.purchase_confirm_dispatched"))
            assertFalse(fixture.get("shoppingConfirmationInFlight") as Boolean)
        } finally { fixture.stop() }
    }

    @Test fun `等待较久后当前商品页仍可建立购买意图`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.observe(stock())
            fixture.now += 16_000
            fixture.observe(stock())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId); fixture.awaitIdle()
            assertEquals(1, fixture.taps.get())
            assertEquals(listOf("currency.observed", "shop.purchase_intent"), fixture.types())
        } finally { fixture.stop() }
    }

    @Test fun `确定Rejected保存reject后凭当前确认读数可重新prepare`() = runBlocking {
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
            assertTrue(fixture.call("handleShoppingFrame", fixture.id(), absent, fixture.now) as Boolean)
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
            first.observe(imprintConfirmation(role))
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

    @Test fun `确认只使用当前输入而不依赖等待时长或前页缓存`() = runBlocking {
        for (transition in listOf("等待", "页面", "动作")) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.buy()
                when (transition) {
                    "等待" -> fixture.now += 16_000
                    "页面" -> fixture.observe(frame(LabyrinthEntryPageState.UNKNOWN))
                    "动作" -> fixture.call("prepareShoppingAction", LabyrinthPostEntryActionKind.SELECT_RELIC, fixture.now, null, null, null)
                }
                fixture.confirm()
                assertEquals(transition, 2, fixture.taps.get())
                assertEquals(1, fixture.types().count { it == "currency.observed" })
                assertEquals("shop.purchase_confirm_dispatched", fixture.types().last())
            } finally { fixture.stop() }
        }
    }

    @Test fun `当前节点账户或已对账金额变化不能确认旧意图`() = runBlocking {
        for (change in listOf("节点", "账号", "财务")) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.buy()
                when (change) {
                    "节点" -> fixture.set("validatedRoute", (fixture.get("validatedRoute") as LabyrinthRouteJson).copy(currentBlockId = 50002))
                    "账号" -> fixture.set("activeRunAccountId", 2L)
                    "财务" -> fixture.module.observeBalance(ShoppingTestData.wallet(999), 50001, 5, fixture.now)
                }
                fixture.state(confirmation())
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, item.relicMatch!!.relicId); fixture.awaitIdle()
                assertEquals(change, 1, fixture.taps.get())
                assertFalse(change, fixture.types().contains("shop.purchase_confirm_prepared"))
            } finally { fixture.stop() }
        }
    }

    @Test fun `确认缺失钱包单读商店缓存金额不足价格或身份不符均不点击`() = runBlocking {
        val current = confirmation()
        val currentWallet = current.currencyObservation!!
        val badFrames = listOf(
            current.copy(currencyObservation = null),
            current.copy(currencyObservation = currentWallet.copy(read = ShoppingTestData.read("1000", 1))),
            current.copy(currencyObservation = ShoppingTestData.wallet(1000)),
            current.copy(currencyObservation = ShoppingTestData.wallet(999).copy(source = LabyrinthCurrencySource.PURCHASE_CONFIRMATION)),
            current.copy(currencyObservation = ShoppingTestData.wallet(50).copy(source = LabyrinthCurrencySource.PURCHASE_CONFIRMATION)),
            current.copy(shopObservation = current.shopObservation!!.copy(purchaseCandidate = null)),
            current.copy(shopObservation = current.shopObservation!!.copy(purchaseCandidate = item.relicMatch!!.copy(basePrice = 101))),
            current.copy(shopObservation = current.shopObservation!!.copy(purchaseCandidate = item.relicMatch!!.copy(displayName = "其它遗物"))),
            current.copy(shopObservation = current.shopObservation!!.copy(purchaseCandidate = item.relicMatch!!.copy(relicId = "other"))),
            current.copy(shopObservation = current.shopObservation!!.copy(dialogState = LabyrinthShopDialogState.NONE)),
            current.copy(observation = current.observation.copy(state = LabyrinthEntryPageState.SHOP)))
        for (bad in badFrames) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.buy(); fixture.observe(bad)
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, item.relicMatch!!.relicId); fixture.awaitIdle()
                assertEquals(1, fixture.taps.get())
                assertEquals(listOf("currency.observed", "shop.purchase_intent"), fixture.types())
            } finally { fixture.stop() }
        }
    }

    @Test fun `买按钮点下后游戏仍停在商店页只保留待确认意图`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy()
            // 手势还没落地，游戏仍是商店页：这一帧属于同一次购买的过渡，不是预算证据失效。
            assertTrue(fixture.observe(stock()))
            assertNotNull(LabyrinthShopReceiptLedger.pendingIntent(fixture.events()))
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
            val covered = item.copy(categoryState = LabyrinthShopCategoryState.EXHAUSTED, price = null,
                titleText = null, relicMatch = item.relicMatch!!.copy(relicId = null, displayName = null))
            val dialog = frame(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
                LabyrinthShopObservation(listOf(covered, covered.copy(slotId = "slot-2")),
                    LabyrinthShopDialogState.PURCHASE_CONFIRMATION, purchaseCandidate = item.relicMatch))
            assertFalse(fixture.observe(dialog))
            assertNotNull(LabyrinthShopReceiptLedger.pendingIntent(fixture.events()))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, item.relicMatch!!.relicId)
            fixture.awaitIdle()
            assertEquals(2, fixture.taps.get())
            assertEquals("shop.purchase_confirm_dispatched", fixture.types().last())
        } finally { fixture.stop() }
    }

    @Test fun `可靠确认图标不被标题缺读误作冲突且非空双读仍严格核对`() = runBlocking {
        val candidate = item.relicMatch!!.copy(displayName = "铁防甲")
        val cases = listOf(
            null to true,
            LabyrinthShopReceiptRead(null, 1, 2) to true,
            LabyrinthShopReceiptRead("  \n ", 2, 2) to true,
            LabyrinthShopReceiptRead("鉄防甲", 3, 2) to true,
            LabyrinthShopReceiptRead("铁防乙", 4, 2) to false,
            LabyrinthShopReceiptRead("铁防甲!", 5, 2) to false,
        )
        for ((title, permitted) in cases) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start()
                fixture.observe(stock(goods = listOf(item.copy(relicMatch = candidate))))
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, candidate.relicId); fixture.awaitIdle()
                val current = confirmation()
                val dialog = current.copy(shopObservation = current.shopObservation!!.copy(
                    purchaseCandidate = candidate, confirmationTitle = title))
                assertEquals(!permitted, fixture.observe(dialog))
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, candidate.relicId); fixture.awaitIdle()
                assertEquals(if (permitted) 2 else 1, fixture.taps.get())
                assertEquals(if (permitted) listOf("currency.observed", "shop.purchase_intent",
                    "shop.purchase_confirm_prepared", "shop.purchase_confirm_dispatched")
                    else listOf("currency.observed", "shop.purchase_intent"), fixture.types())
                assertEquals(0, fixture.store.commits.get())
            } finally { fixture.stop() }
        }
    }

    @Test fun `暴击弧弓与实际双读引字冲突仍拒绝确认`() = runBlocking {
        val candidate = item.relicMatch!!.copy(displayName = "暴击弧弓")
        for (title in listOf("暴击弧弓", "暴击弧引")) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.observe(stock(goods = listOf(item.copy(relicMatch = candidate))))
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, candidate.relicId); fixture.awaitIdle()
                val current = confirmation()
                fixture.observe(current.copy(shopObservation = current.shopObservation!!.copy(
                    purchaseCandidate = candidate, confirmationTitle = ShoppingTestData.read(title))))
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, candidate.relicId); fixture.awaitIdle()
                assertEquals(if (title == "暴击弧弓") 2 else 1, fixture.taps.get())
                assertEquals(if (title == "暴击弧弓") listOf("currency.observed", "shop.purchase_intent",
                    "shop.purchase_confirm_prepared", "shop.purchase_confirm_dispatched")
                    else listOf("currency.observed", "shop.purchase_intent"), fixture.types())
                assertEquals(0, fixture.store.commits.get())
            } finally { fixture.stop() }
        }
    }

    @Test fun `刷新确认框出现前的过渡帧不作废刷新证据`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.openRefresh()
            // 点下刷新后游戏先停在商店页，刷新证据必须跨过这一两帧。
            assertFalse(fixture.observe(refreshStock()))
            assertNotNull(fixture.get("shoppingRefreshAttempt"))
            // 只有当前商店背景、通用确认框及确认前钱包双读齐全才可消费。
            val dialog = refreshConfirmation()
            assertFalse(fixture.observe(dialog))
            assertNotNull(fixture.get("shoppingRefreshAttempt"))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals(2, fixture.taps.get())
            assertEquals("shop.refresh_dispatched", fixture.types().last())
            assertEquals(1, fixture.types().count { it == "shop.refresh_prepared" })
            assertNull(fixture.get("shoppingRefreshAttempt"))
        } finally { fixture.stop() }
    }

    @Test fun `刷新待确认时出现购买确认框会作废刷新证据`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.openRefresh()
            assertNotNull(fixture.get("shoppingRefreshAttempt"))
            fixture.observe(frame(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
                LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_CONFIRMATION)))
            assertNull(fixture.get("shoppingRefreshAttempt"))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals(1, fixture.taps.get())
            assertFalse(fixture.types().contains("shop.refresh_dispatched"))
        } finally { fixture.stop() }
    }

    @Test fun `误判退出的更新框逐帧双读不丢刷新意图且不走普通退出确认`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.openRefresh(); fixture.enablePostEntryRoute()
            val actual = refreshConfirmation().copy(observation = refreshConfirmation().observation.copy(
                state = LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
                anchorScores = LabyrinthAnchorScores(mapOf(EntryAnchorId.SHOP_TITLE to 0.99,
                    EntryAnchorId.SHOP_EXIT_CONFIRMATION_TITLE to 0.734))),
                shopObservation = LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.EXIT_CONFIRMATION))
            val first = resolveShoppingGenericDialog(actual, ShoppingTestData.read("更新确认", 1))
            fixture.now += 1000
            fixture.call("processFrameResult", fixture.id(), first, 1920, 1080, fixture.now)
            fixture.awaitIdle()
            assertNotNull(fixture.get("shoppingRefreshAttempt"))
            assertEquals(1, fixture.taps.get())
            assertFalse(fixture.types().contains("shop.refresh_prepared"))
            val second = resolveShoppingGenericDialog(actual, ShoppingTestData.read("更新确认"))
            repeat(4) {
                fixture.now += 1000
                fixture.call("processFrameResult", fixture.id(), second, 1920, 1080, fixture.now)
                fixture.awaitIdle()
            }
            assertEquals(2, fixture.taps.get())
            assertEquals(1, fixture.types().count { it == "shop.refresh_prepared" })
            assertEquals(1, fixture.types().count { it == "shop.refresh_dispatched" })
            assertEquals(listOf("currency.observed", "shop.refresh_prepared", "shop.refresh_dispatched"), fixture.types())
        } finally { fixture.stop() }
    }

    @Test fun `真实更新标题无意图或双读冲突不得漏到退出点击`() = runBlocking {
        for (title in listOf("更新确认", "更新确认?", "错误确认")) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.enablePostEntryRoute()
                val actual = refreshConfirmation().copy(observation = refreshConfirmation().observation.copy(
                    state = LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
                    anchorScores = LabyrinthAnchorScores(mapOf(EntryAnchorId.SHOP_TITLE to 0.99,
                        EntryAnchorId.SHOP_EXIT_CONFIRMATION_TITLE to 0.734))))
                val resolved = resolveShoppingGenericDialog(actual, ShoppingTestData.read(title))
                repeat(3) {
                    fixture.now += 1000
                    fixture.call("processFrameResult", fixture.id(), resolved, 1920, 1080, fixture.now)
                    fixture.awaitIdle()
                }
                assertEquals(0, fixture.taps.get())
                assertFalse(fixture.types().contains("shop.refresh_prepared"))
            } finally { fixture.stop() }
        }
    }

    @Test fun `刷新必须使用当前双读钱包且地图或退出弹窗不能授权消费`() = runBlocking {
        val current = refreshConfirmation()
        val badFrames = listOf(frame(LabyrinthEntryPageState.UNKNOWN),
            current.copy(currencyObservation = null),
            current.copy(currencyObservation = ShoppingTestData.wallet(1000)),
            current.copy(currencyObservation = current.currencyObservation!!.copy(read = ShoppingTestData.read("1000", 1))),
            refreshConfirmation(900),
            current.copy(observation = current.observation.copy(state = LabyrinthEntryPageState.NODE_SELECTION)),
            current.copy(observation = current.observation.copy(state = LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION)),
            current.copy(observation = current.observation.copy(anchorScores = LabyrinthAnchorScores(emptyMap()))))
        for (bad in badFrames) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.openRefresh(); fixture.state(bad)
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
                assertEquals(1, fixture.taps.get())
                assertEquals(listOf("currency.observed"), fixture.types())
            } finally { fixture.stop() }
        }
    }

    @Test fun `刷新等待较久仍只能由当前确认双读创建一次prepared`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.openRefresh(); fixture.now += 16_000
            fixture.state(refreshConfirmation())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals(2, fixture.taps.get())
            assertEquals(1, fixture.types().count { it == "shop.refresh_prepared" })
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals(2, fixture.taps.get())
        } finally { fixture.stop() }
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

    @Test fun `真实suspend刷新executor超过60秒原余额帧不污染后700持锁settle迟到Executed幂等`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.start(); fixture.openRefresh()
            fixture.backend = {
                assertEquals("shop.refresh_prepared", fixture.types().last())
                entered.complete(Unit); release.await(); AutomationBackendResult.Completed
            }
            fixture.state(refreshConfirmation())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH)
            withTimeout(5000) { entered.await() }
            assertEquals(1, fixture.refreshCount()); assertEquals(0, fixture.count())
            val preparedEvents = fixture.events()
            val heldSince = fixture.get("actionInFlightSince")
            val frameCount = fixture.session.state.value.frameCount
            fixture.set("lastObservedPageState", LabyrinthEntryPageState.UNKNOWN)
            fixture.now += 60_001
            val originalBalanceFrame = refreshStock()
            fixture.call("processFrameResult", fixture.id(), originalBalanceFrame, 1920, 1080, fixture.now)
            assertEquals(fixture.expectedUnscannedPublication(originalBalanceFrame), fixture.session.state.value.lastResult)
            assertFalse(fixture.session.state.value.lastResult!!.shopObservation!!.stockComplete)
            assertEquals(frameCount + 1, fixture.session.state.value.frameCount)
            assertEquals(preparedEvents, fixture.events())
            assertNotNull(fixture.module.pendingRefresh()); assertNull(fixture.module.verifiedBalance)
            assertEquals(1, fixture.refreshCount()); assertEquals(0, fixture.count())
            assertTrue((fixture.get("actionInFlight") as AtomicBoolean).get())
            assertEquals(heldSince, fixture.get("actionInFlightSince"))
            fixture.now += 1000
            val settledFrame = refreshStock(700)
            fixture.call("processFrameResult", fixture.id(), settledFrame, 1920, 1080, fixture.now)
            assertEquals(fixture.expectedUnscannedPublication(settledFrame), fixture.session.state.value.lastResult)
            assertFalse(fixture.session.state.value.lastResult!!.shopObservation!!.stockComplete)
            assertEquals(frameCount + 2, fixture.session.state.value.frameCount)
            assertEquals("shop.refresh_settled", fixture.types().last()); assertEquals(0, fixture.count())
            assertTrue(fixture.events().last()["source"] == JsonPrimitive("actual_screen"))
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
            assertNull(fixture.module.pendingRefresh()); assertEquals(700L, fixture.module.verifiedBalance)
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
                fixture.state(refreshConfirmation())
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH)
                withTimeout(5000) { entered.await() }
                val preparedEvents = fixture.events()
                val heldSince = fixture.get("actionInFlightSince")
                val pendingRefresh = fixture.module.pendingRefresh()
                val frameCount = fixture.session.state.value.frameCount
                fixture.now += 60_001
                val balanceFrame = refreshStock(700)
                val blockedFrame = if (gate == "channel") {
                    fixture.set("channelPromptVisibleUntil", fixture.now + 10_000)
                    fixture.set("channelPromptMessage", "渠道弹窗占有画面")
                    balanceFrame
                } else balanceFrame.copy(observation = balanceFrame.observation.copy(
                    anchorScores = LabyrinthAnchorScores(mapOf(EntryAnchorId.SESSION_DATE_CHANGE_TITLE to 1.0))))
                fixture.call("processFrameResult", fixture.id(), blockedFrame, 1920, 1080, fixture.now)
                assertEquals(fixture.expectedUnscannedPublication(blockedFrame), fixture.session.state.value.lastResult)
                assertFalse(fixture.session.state.value.lastResult!!.shopObservation!!.stockComplete)
                assertEquals(frameCount + 1, fixture.session.state.value.frameCount)
                assertEquals(preparedEvents, fixture.events())
                assertEquals(pendingRefresh, fixture.module.pendingRefresh())
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
            first.state(refreshConfirmation())
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
            assertFalse(restarted.observe(refreshStock(700)))
            assertEquals("shop.refresh_settled", restarted.types().last())
            repeat(3) {
                restarted.set("shoppingRunInitialized", false)
                restarted.observe(refreshStock(700))
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
            first.state(refreshConfirmation())
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
            assertFalse(restarted.observe(refreshStock(700)))
            assertEquals("shop.refresh_settled", restarted.types().last())
            assertEquals(1, restarted.types().count { it == "shop.refresh_settled" })
            assertNull(restarted.module.pendingRefresh())
            assertEquals(700L, restarted.module.verifiedBalance)
            assertFalse(restarted.observe(refreshStock(700)))
            assertEquals(1, restarted.types().count { it == "shop.refresh_settled" })
        } finally { restarted.stop() }
    }

    @Test fun `确定刷新Rejected释放prepared额度可重新正常刷新`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.openRefresh()
            fixture.backend = { AutomationBackendResult.Rejected("确定未点击") }
            fixture.state(refreshConfirmation())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals("shop.refresh_rejected", fixture.types().last()); assertEquals(0, fixture.refreshCount())
            assertEquals(1000L, fixture.module.verifiedBalance); assertNull(fixture.module.pendingRefresh())
            fixture.backend = { AutomationBackendResult.Completed }
            fixture.openRefresh(); fixture.state(refreshConfirmation())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals(1, fixture.refreshCount()); assertEquals(2, fixture.types().count { it == "shop.refresh_prepared" })
            fixture.observe(refreshStock(700))
            assertEquals("shop.refresh_settled", fixture.types().last()); assertEquals(1, fixture.refreshCount())
        } finally { fixture.stop() }
    }

    @Test fun `刷新日志IO失败不点击并在重新核实当前钱包后重试`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        var log: File? = null
        var saved: File? = null
        try {
            fixture.start(); fixture.openRefresh()
            log = File(fixture.run().directory, "events.jsonl")
            saved = File(fixture.run().directory, "saved-events.jsonl")
            Files.move(log.toPath(), saved.toPath()); assertTrue(log.mkdir())
            fixture.state(refreshConfirmation())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals(1, fixture.taps.get()); assertEquals(0, fixture.refreshCount())
            assertTrue(log.delete()); Files.move(saved.toPath(), log.toPath())
            fixture.observe(refreshConfirmation())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            assertEquals(2, fixture.taps.get()); assertEquals(1, fixture.refreshCount())
        } finally {
            if (log?.isDirectory == true) { assertTrue(log.delete()); Files.move(saved!!.toPath(), log.toPath()) }
            fixture.stop()
        }
    }

    @Test fun `回执无法核实超期后仅独立退出不假记账也不旁路正常关闭`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.trackTerminal(); fixture.buy(); fixture.confirm()
            val unreadable = receipt.copy(expenditure = ShoppingTestData.read(null))
            fixture.observe(completed(unreadable))
            fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS
            fixture.observe(completed(unreadable)); fixture.awaitIdle()
            assertEquals("shop.purchase_abandoned", fixture.types().last())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE); fixture.awaitIdle()
            assertEquals(2, fixture.taps.get())
            assertFalse(fixture.canDismiss(unreadable))
            fixture.observe(completed(unreadable)); fixture.awaitIdle()
            assertEquals(3, fixture.taps.get())
            assertFalse(fixture.types().contains("shop.purchase_completed"))
            assertFalse(fixture.types().contains("shop.ownership_committed"))
            assertEquals(0, fixture.store.commits.get())
            assertEquals(0, fixture.count())
            fixture.nextShop(); fixture.buy()
            assertEquals(2, fixture.types().count { it == "shop.purchase_intent" })
            assertTrue(fixture.terminals.isEmpty())
        } finally { fixture.stop() }
    }

    @Test fun `持有提交持续失败超期放弃后不再阻塞下一店`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.trackTerminal(); fixture.buy(); fixture.confirm()
            fixture.store.failBeforeCommit = true
            fixture.observe(completed()); fixture.awaitIdle()
            assertEquals(1, fixture.module.pendingOwnership().size)
            assertFalse(fixture.canDismiss(receipt))
            fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS
            fixture.observe(completed()); fixture.awaitIdle()
            assertEquals("shop.purchase_abandoned", fixture.types().last())
            assertTrue(fixture.module.pendingOwnership().isEmpty())
            assertFalse(fixture.canDismiss(receipt))
            assertEquals(0, fixture.store.commits.get())
            assertEquals(0, fixture.count())
            assertEquals(1, fixture.types().count { it == "shop.purchase_completed" })
            assertFalse(fixture.types().contains("shop.ownership_committed"))
            fixture.observe(completed()); fixture.awaitIdle()
            assertEquals(3, fixture.taps.get())
            fixture.nextShop(); fixture.buy()
            assertEquals(2, fixture.types().count { it == "shop.purchase_intent" })
            assertTrue(fixture.terminals.isEmpty())
        } finally { fixture.stop() }
    }

    @Test fun `放弃后关店许可不能把误分类更新框右确认当安全退出`() = runBlocking {
        for (read in listOf(null, ShoppingTestData.read("更新确认", 1), ShoppingTestData.read("更新确认"))) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.buy()
                val waiting = confirmation().copy(currencyObservation = null)
                fixture.observe(waiting); fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS
                fixture.observe(waiting); fixture.awaitIdle()
                fixture.now += 2000
                val close = EntryPixelRect(1400, 900, 400, 100)
                fixture.observe(stock().copy(anchorMatches = mapOf(EntryAnchorId.SHOP_CLOSE to EntryAnchorMatch(1.0, close))))
                fixture.awaitIdle()
                val exit = checkNotNull(pendingExit(fixture))
                assertTrue(exit.javaClass.getDeclaredField("closeRequested").apply { isAccessible = true }.getBoolean(exit))
                val falseExit = frame(LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
                    LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.EXIT_CONFIRMATION,
                        refreshConfirmationTitle = read)).copy(
                    observation = LabyrinthEntryPageObservation(LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION, 0.734,
                        emptyMap(), LabyrinthAnchorScores(mapOf(EntryAnchorId.SHOP_TITLE to 0.99))),
                    nodeMoveConfirmation = if (read == null) null else
                        LabyrinthNodeMoveConfirmationObservation(1.0, rect, EntryPixelRect(535, 700, 400, 100)),
                    anchorMatches = mapOf(EntryAnchorId.SHOP_EXIT_CONFIRMATION_TITLE to EntryAnchorMatch(1.0, rect),
                        EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON to EntryAnchorMatch(1.0, rect)))
                fixture.now += 2000
                val actionCount = fixture.taps.get()
                fixture.observe(falseExit); fixture.awaitIdle()
                assertEquals(actionCount + 1, fixture.taps.get())
                assertEquals(if (read == null) AutomationAction.Back else
                    AutomationAction.Tap(ScreenPoint(735f, 750f)), fixture.actions.last())
                assertNotEquals(AutomationAction.Tap(ScreenPoint(20f, 10f)), fixture.actions.last())
                assertFalse(fixture.types().contains("shop.refresh_prepared"))
                assertFalse(fixture.types().contains("shop.refresh_dispatched"))
            } finally { fixture.stop() }
        }
    }

    @Test fun `放弃刷新后迟到通用确认只取消且撤销旧关店许可`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.trackTerminal(); fixture.openRefresh()
            fixture.backend = { error("刷新结果未知") }
            fixture.state(refreshConfirmation())
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
            fixture.observe(frame(LabyrinthEntryPageState.UNKNOWN))
            fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS
            fixture.observe(frame(LabyrinthEntryPageState.UNKNOWN)); fixture.awaitIdle()
            assertEquals("shop.refresh_abandoned", fixture.types().last())
            val saved = fixture.events()
            fixture.backend = { AutomationBackendResult.Completed }
            fixture.now += 2000
            val close = EntryPixelRect(1400, 900, 400, 100)
            fixture.observe(stock().copy(anchorMatches = mapOf(EntryAnchorId.SHOP_CLOSE to EntryAnchorMatch(1.0, close))))
            fixture.awaitIdle()
            assertEquals(AutomationAction.Tap(ScreenPoint(1600f, 950f)), fixture.actions.last())
            val cancel = EntryPixelRect(545, 690, 415, 105)
            val confirm = EntryPixelRect(975, 690, 415, 105)
            fixture.now += 2000
            val lateDialog = frame(LabyrinthEntryPageState.UNKNOWN, LabyrinthShopObservation(emptyList())).copy(
                nodeMoveConfirmation = LabyrinthNodeMoveConfirmationObservation(1.0, confirm, cancel),
                anchorMatches = mapOf(EntryAnchorId.SHOP_CLOSE to EntryAnchorMatch(1.0, close)))
            fixture.observe(lateDialog); fixture.awaitIdle()
            assertEquals(AutomationAction.Tap(ScreenPoint(752.5f, 742.5f)), fixture.actions.last())
            fixture.now += 2000
            val exitLooking = frame(LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION).copy(anchorMatches = mapOf(
                EntryAnchorId.SHOP_EXIT_CONFIRMATION_TITLE to EntryAnchorMatch(1.0, rect),
                EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON to EntryAnchorMatch(1.0, confirm)))
            fixture.observe(exitLooking); fixture.awaitIdle()
            assertEquals(AutomationAction.Back, fixture.actions.last())
            assertEquals(saved, fixture.events())
            assertTrue(fixture.terminals.isEmpty())
        } finally { fixture.stop() }
    }

    @Test fun `无意图的商品识别等待与刷新弹窗等待到期均只退出本店`() = runBlocking {
        for (refresh in listOf(false, true)) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.trackTerminal()
                val waiting = if (refresh) {
                    fixture.openRefresh()
                    frame(LabyrinthEntryPageState.UNKNOWN)
                } else stock(goods = listOf(item.copy(price = null, relicMatch = item.relicMatch?.copy(basePrice = null))))
                fixture.observe(waiting)
                fun handleWait() {
                    if (refresh) fixture.call("handleShopRefreshConfirmationFrame", fixture.id(), waiting, fixture.now)
                    else {
                        fixture.set("lastPostEntryState", LabyrinthEntryPageState.SHOP)
                        fixture.set("postEntryStableFrames", 4)
                        fixture.call("handlePostEntryPage", fixture.id(), waiting, 1920, 1080, fixture.now)
                    }
                }
                handleWait()
                fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS
                fixture.state(waiting)
                handleWait(); fixture.awaitIdle()
                assertNotNull(pendingExit(fixture))
                assertTrue(fixture.session.state.value.running)
                assertEquals(listOf("currency.observed"), fixture.types())
                fixture.nextShop(); fixture.buy()
                assertEquals(1, fixture.types().count { it == "shop.purchase_intent" })
                assertTrue(fixture.terminals.isEmpty())
            } finally { fixture.stop() }
        }
    }

    @Test fun `购买回调超时保持未知准备态而不冒充确定拒绝`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.trackTerminal(); fixture.buy()
            fixture.backend = { AutomationBackendResult.Rejected("显示器 0 的无障碍手势回调超时") }
            fixture.confirm()
            assertEquals("shop.purchase_confirm_prepared", fixture.types().last())
            assertFalse(fixture.types().contains("shop.purchase_confirm_rejected"))
            assertNotNull(LabyrinthShopReceiptLedger.pendingIntent(fixture.events()))
            fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS
            fixture.observe(confirmation()); fixture.awaitIdle()
            assertEquals("shop.purchase_abandoned", fixture.types().last())
            assertEquals(2, fixture.taps.get())
            assertTrue(fixture.terminals.isEmpty())
        } finally { fixture.stop() }
    }

    @Test fun `购买及刷新首按钮挂起超期均保留动作锁而不走通用停止`() = runBlocking {
        for (refresh in listOf(false, true)) {
            val fixture = Fixture(temp.newFolder())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                fixture.start(); fixture.trackTerminal()
                fixture.observe(if (refresh) refreshStock() else stock())
                fixture.backend = { entered.complete(Unit); release.await(); error("首按钮执行结果未知") }
                fixture.dispatch(if (refresh) LabyrinthPostEntryActionKind.SHOP_REFRESH else LabyrinthPostEntryActionKind.SHOP_BUY_RELIC,
                    if (refresh) null else item.relicMatch!!.relicId)
                withTimeout(5000) { entered.await() }
                fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS + 1000
                fixture.set("shoppingHoldPage", LabyrinthEntryPageState.UNKNOWN)
                fixture.set("shoppingTransitionGranted", true)
                fixture.call("processFrameResult", fixture.id(), frame(LabyrinthEntryPageState.UNKNOWN), 1920, 1080, fixture.now)
                assertTrue((fixture.get("actionInFlight") as AtomicBoolean).get())
                assertNotNull(pendingExit(fixture))
                assertEquals(1, fixture.taps.get())
                assertTrue(fixture.session.state.value.running)
                assertTrue(fixture.terminals.isEmpty())
                release.complete(Unit); fixture.awaitIdle()
                assertFalse((fixture.get("actionInFlight") as AtomicBoolean).get())
                assertNotNull(pendingExit(fixture))
                assertFalse(fixture.types().contains("shop.refresh_rejected"))
                assertTrue(fixture.session.state.value.running)
            } finally {
                release.complete(Unit)
                fixture.stop()
            }
        }
    }

    @Test fun `settled shop then long route interval gives next shop its own incomplete scan budget`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy(); fixture.confirm()
            assertTrue(fixture.observe(completed())); fixture.awaitIdle()
            assertFalse(fixture.observe(completed()))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE); fixture.awaitIdle()
            // The completed modal can remain visible after close landed, consuming its one transition allowance.
            assertTrue(fixture.observe(completed()))
            assertTrue(fixture.get("shoppingTransitionGranted") as Boolean)
            assertFalse(fixture.observe(frame(LabyrinthEntryPageState.NODE_SELECTION)))
            val oldProgress = fixture.get("shoppingLastProgressAt") as Long
            fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS + 60_000
            fixture.nextShop(50701)
            fixture.call("prepareNodeTransitionContext", LabyrinthNodeTypes.SHOP, 5)
            fixture.enablePostEntryRoute()
            fixture.call("bindShopStockScope", 1920, 1080)
            val scanner = fixture.get("shopStockScanner") as LabyrinthShopStockScanner
            val goods = ShopScanFixtures.goods()
            val unread = goods.map { it.copy(kind = LabyrinthShopItemKind.UNKNOWN,
                titleText = null, roleImprintLabel = null, categoryState = LabyrinthShopCategoryState.READING) }
            val reading = stock(balance = 900).copy(shopObservation = ShopScanFixtures.a(scanner.epoch, unread))
            val actionsBefore = fixture.actions.size
            repeat(2) {
                fixture.now += 1000
                fixture.call("processFrameResult", fixture.id(), reading, 1920, 1080, fixture.now)
                fixture.awaitIdle()
            }
            assertTrue(fixture.session.state.value.running)
            assertNull(pendingExit(fixture))
            assertEquals(actionsBefore, fixture.actions.size)
            assertFalse(fixture.session.state.value.lastResult!!.shopObservation!!.stockComplete)
            assertTrue((fixture.get("shoppingLastProgressAt") as Long) > oldProgress)
            val top = reading.copy(shopObservation = ShopScanFixtures.a(scanner.epoch, goods))
            assertFalse(fixture.observe(top))
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_SCROLL_BOTTOM); fixture.awaitIdle()
            assertTrue(fixture.actions.last() is AutomationAction.Swipe)
            val bottom = top.copy(shopObservation = ShopScanFixtures.b(scanner.epoch, goods))
            assertFalse(fixture.observe(bottom))
            assertFalse(fixture.session.state.value.lastResult!!.shopObservation!!.stockComplete)
            assertFalse(fixture.observe(bottom))
            assertTrue(fixture.session.state.value.lastResult!!.shopObservation!!.stockComplete)
            val decision = fixture.call("currentShopDecision", fixture.session.state.value.lastResult)
            assertTrue(decision is LabyrinthShopDecision.BuyRoleImprint)
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_IMPRINT, imprint = goods.first().roleImprintLabel)
            fixture.awaitIdle()
            val intent = fixture.events().last { ShoppingTestData.type(it) == "shop.purchase_intent" }
            assertEquals(JsonPrimitive(50701L), intent["node_id"])
            assertEquals(2, fixture.types().count { it == "shop.purchase_intent" })
            assertFalse(fixture.types().contains("shop.purchase_abandoned"))
        } finally { fixture.stop() }
    }

    @Test fun `same shop incomplete scan and stock invalidation do not renew expired hold`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.enablePostEntryRoute()
            fixture.call("bindShopStockScope", 1920, 1080)
            val scanner = fixture.get("shopStockScanner") as LabyrinthShopStockScanner
            val unread = ShopScanFixtures.goods().map { it.copy(kind = LabyrinthShopItemKind.UNKNOWN,
                titleText = null, roleImprintLabel = null, categoryState = LabyrinthShopCategoryState.READING) }
            val reading = stock().copy(shopObservation = ShopScanFixtures.a(scanner.epoch, unread))
            repeat(2) {
                fixture.now += 1000
                fixture.call("processFrameResult", fixture.id(), reading, 1920, 1080, fixture.now)
                fixture.awaitIdle()
            }
            val progress = fixture.get("shoppingLastProgressAt") as Long
            fixture.call("invalidateShopStock")
            fixture.call("bindShopStockScope", 1920, 1080)
            assertEquals(progress, fixture.get("shoppingLastProgressAt"))
            fixture.now += SHOPPING_HOLD_TIMEOUT_MILLIS + 1000
            val current = reading.copy(shopObservation = ShopScanFixtures.a(scanner.epoch, unread))
            fixture.call("processFrameResult", fixture.id(), current, 1920, 1080, fixture.now)
            fixture.awaitIdle()
            assertNotNull(pendingExit(fixture))
            assertTrue(fixture.session.state.value.message.orEmpty().contains("放弃本店"))
            assertFalse(fixture.types().contains("shop.purchase_intent"))
        } finally { fixture.stop() }
    }

    @Test fun `new shop context cannot renew unresolved financial work errors or exit requests`() = runBlocking {
        for (pending in listOf("intent", "prepared", "refresh", "ownership", "error", "io", "action", "exit")) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start(); fixture.observe(stock())
                when (pending) {
                    "intent" -> fixture.buy()
                    "prepared" -> { fixture.buy(); assertTrue(ShoppingTestData.prepare(fixture.module, fixture.now)) }
                    "refresh" -> {
                        fixture.openRefresh(); fixture.state(refreshConfirmation())
                        fixture.backend = { error("unknown refresh result") }
                        fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH); fixture.awaitIdle()
                        assertNotNull(fixture.module.pendingRefresh())
                        fixture.set("shoppingError", null)
                        fixture.set("shoppingRefreshAttempt", null)
                    }
                    "ownership" -> {
                        fixture.buy(); fixture.confirm()
                        fixture.module.acceptReceipt(receipt, 50001, 5, fixture.now)
                    }
                    "error" -> fixture.set("shoppingError", "unverified financial state")
                    "action" -> fixture.set("shoppingActionInFlight", true)
                }
                val progress = fixture.now - SHOPPING_HOLD_TIMEOUT_MILLIS - 1000
                fixture.set("shoppingLastProgressAt", progress)
                fixture.set("shoppingHoldPage", LabyrinthEntryPageState.UNKNOWN)
                fixture.set("shoppingTransitionGranted", true)
                fixture.set("shoppingRejectionsInStreak", 2)
                if (pending == "exit") {
                    fixture.set("shoppingActionInFlight", true)
                    (fixture.get("actionInFlight") as AtomicBoolean).set(true)
                    fixture.call("requestShoppingExit", "unresolved exit", true, null)
                    assertNotNull(pendingExit(fixture))
                    fixture.set("shoppingActionInFlight", false)
                }
                val eventsBefore = fixture.events()
                val invoke = { fixture.call("prepareNodeTransitionContext", LabyrinthNodeTypes.SHOP, 5) }
                if (pending == "io") {
                    val manifest = File(fixture.run().directory, "manifest.json")
                    val original = manifest.readBytes()
                    try { manifest.writeText("invalid-json"); invoke() } finally { manifest.writeBytes(original) }
                } else invoke()
                assertEquals(pending, progress, fixture.get("shoppingLastProgressAt"))
                assertEquals(pending, LabyrinthEntryPageState.UNKNOWN, fixture.get("shoppingHoldPage"))
                assertEquals(pending, true, fixture.get("shoppingTransitionGranted"))
                assertEquals(pending, 2, fixture.get("shoppingRejectionsInStreak"))
                assertEquals(pending, eventsBefore, fixture.events())
            } finally {
                fixture.set("shoppingActionInFlight", false)
                (fixture.get("actionInFlight") as AtomicBoolean).set(false)
                fixture.stop()
            }
        }
    }

    @Test fun `incomplete stock routes a real swipe and publishes merged six slots before policy`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start()
            fixture.enablePostEntryRoute()
            fixture.call("bindShopStockScope", 1920, 1080)
            val scanner = fixture.get("shopStockScanner") as LabyrinthShopStockScanner
            val goods = ShopScanFixtures.goods().map { it.copy(status = LabyrinthShopItemStatus.DISABLED) }
            val raw = stock().copy(shopObservation = ShopScanFixtures.a(scanner.epoch, goods))
            assertFalse(fixture.observe(raw))
            assertTrue(fixture.call("currentShopDecision", fixture.session.state.value.lastResult) is LabyrinthShopDecision.Scan)
            fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_SCROLL_BOTTOM)
            fixture.awaitIdle()
            assertEquals(1, fixture.actions.size)
            assertTrue(fixture.actions.single() is AutomationAction.Swipe)
            assertFalse(fixture.types().contains("shop.purchase_intent"))
            val bottom = raw.copy(shopObservation = ShopScanFixtures.b(scanner.epoch, goods))
            fixture.now += 1000
            fixture.call("processFrameResult", fixture.id(), bottom, 1920, 1080, fixture.now)
            fixture.awaitIdle()
            assertFalse(fixture.session.state.value.lastResult!!.shopObservation!!.stockComplete)
            assertTrue(fixture.session.state.value.running)
            assertTrue(fixture.call("currentShopDecision", fixture.session.state.value.lastResult) is LabyrinthShopDecision.Wait)
            fixture.now += 1000
            fixture.call("processFrameResult", fixture.id(), bottom, 1920, 1080, fixture.now)
            fixture.awaitIdle()
            val merged = fixture.session.state.value.lastResult!!.shopObservation!!
            assertTrue(merged.stockComplete)
            assertEquals(LabyrinthShopSlots.keys, merged.items.map { it.slotId })
            assertTrue(merged.items.all { it.titleText == "攻击型职能的随机印记" })
            assertEquals(1, fixture.actions.size)
        } finally { fixture.stop() }
    }

    @Test fun `final execution rejects incomplete or stale generation buys refresh and normal close`() = runBlocking {
        for (stale in listOf(false, true)) for (kind in listOf(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC,
                LabyrinthPostEntryActionKind.SHOP_REFRESH, LabyrinthPostEntryActionKind.SHOP_CLOSE)) {
            val fixture = Fixture(temp.newFolder())
            try {
                fixture.start()
                val stock = (if (kind == LabyrinthPostEntryActionKind.SHOP_REFRESH) refreshStock() else stock()).let {
                    it.copy(anchorMatches = it.anchorMatches + (EntryAnchorId.SHOP_CLOSE to EntryAnchorMatch(1.0, rect)))
                }
                fixture.observe(if (stale) stock else stock.copy(shopObservation = stock.shopObservation!!.copy(coverage = null)))
                if (stale) (fixture.get("shopStockScanner") as LabyrinthShopStockScanner).invalidate(fixture.now)
                fixture.dispatch(kind, item.relicMatch!!.relicId)
                fixture.awaitIdle()
                assertEquals(0, fixture.actions.size)
                assertFalse(fixture.types().contains("shop.purchase_intent"))
                assertFalse(fixture.types().contains("shop.refresh_prepared"))
            } finally { fixture.stop() }
        }
    }

    @Test fun `queued identical new frame or changed shop scope cannot buy refresh or normally close`() = runBlocking {
        for (changeScope in listOf(false, true)) for (kind in listOf(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC,
                LabyrinthPostEntryActionKind.SHOP_REFRESH, LabyrinthPostEntryActionKind.SHOP_CLOSE)) {
            val fixture = Fixture(temp.newFolder())
            val release = java.util.concurrent.CountDownLatch(1)
            try {
                fixture.start()
                val shelf = when (kind) {
                    LabyrinthPostEntryActionKind.SHOP_REFRESH -> refreshStock()
                    LabyrinthPostEntryActionKind.SHOP_CLOSE -> stock(goods = listOf(item.copy(status = LabyrinthShopItemStatus.DISABLED)))
                    else -> stock()
                }.let { it.copy(anchorMatches = it.anchorMatches + (EntryAnchorId.SHOP_CLOSE to EntryAnchorMatch(1.0, rect))) }
                fixture.observe(shelf)
                val decision = fixture.call("currentShopDecision", fixture.session.state.value.lastResult)
                assertTrue(when (kind) {
                    LabyrinthPostEntryActionKind.SHOP_BUY_RELIC -> decision is LabyrinthShopDecision.BuyRelic
                    LabyrinthPostEntryActionKind.SHOP_REFRESH -> decision is LabyrinthShopDecision.Refresh
                    else -> decision is LabyrinthShopDecision.Close
                })
                val entered = CompletableDeferred<Unit>()
                val calls = AtomicInteger()
                fixture.targetReady = {
                    if (calls.incrementAndGet() == 2) {
                        entered.complete(Unit)
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    true
                }
                fixture.dispatch(kind, if (kind == LabyrinthPostEntryActionKind.SHOP_BUY_RELIC) item.relicMatch!!.relicId else null)
                withTimeout(5000) { entered.await() }
                if (changeScope) fixture.nextShop() else {
                    // Same contents, different capture object: deliberately conservative last-frame gate.
                    @Suppress("UNCHECKED_CAST")
                    val state = fixture.get("_state") as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
                    state.value = state.value.copy(frameCount = state.value.frameCount + 1, lastResult = state.value.lastResult!!.copy())
                }
                release.countDown(); fixture.awaitIdle()
                assertEquals(0, fixture.actions.size)
                assertNull(LabyrinthShopReceiptLedger.pendingIntent(fixture.events()))
                assertNull(fixture.get("shoppingRefreshAttempt"))
                assertFalse(fixture.types().contains("shop.purchase_confirm_prepared"))
                assertFalse(fixture.types().contains("shop.refresh_prepared"))
                assertFalse(fixture.session.state.value.lastResult!!.shopObservation!!.stockComplete)
                if (kind == LabyrinthPostEntryActionKind.SHOP_BUY_RELIC) {
                    assertEquals(1, fixture.types().count { it == "shop.purchase_intent_cancelled" })
                    fixture.targetReady = { true }
                    fixture.buy()
                    assertEquals(1, fixture.actions.size)
                    assertEquals(2, fixture.types().count { it == "shop.purchase_intent" })
                }
            } finally { release.countDown(); fixture.stop() }
        }
    }

    @Test fun `queued rejection cannot cancel a replacement or prepared purchase intent`() = runBlocking {
        for (replacement in listOf(false, true)) {
            val fixture = Fixture(temp.newFolder())
            val release = java.util.concurrent.CountDownLatch(1)
            try {
                fixture.start(); fixture.observe(stock())
                val entered = CompletableDeferred<Unit>()
                val calls = AtomicInteger()
                fixture.targetReady = {
                    if (calls.incrementAndGet() == 2) {
                        entered.complete(Unit)
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    true
                }
                fixture.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, item.relicMatch!!.relicId)
                withTimeout(5000) { entered.await() }
                val original = LabyrinthShopReceiptLedger.pendingIntent(fixture.events())!!
                val retainedId = if (replacement) {
                    fixture.module.cancelIntent(fixture.now)
                    fixture.module.recordNewIntent(item, 1000, 50002, 5, fixture.now)
                } else original["event_id"]!!.jsonPrimitive.content
                assertTrue(fixture.module.prepareConfirmation(fixture.now, retainedId))
                fixture.nextShop()
                release.countDown(); fixture.awaitIdle()
                assertEquals(0, fixture.actions.size)
                assertEquals(retainedId, LabyrinthShopReceiptLedger.pendingIntent(fixture.events())!!["event_id"]!!.jsonPrimitive.content)
                assertEquals(if (replacement) 1 else 0, fixture.types().count { it == "shop.purchase_intent_cancelled" })
                assertTrue(fixture.types().contains("shop.purchase_confirm_prepared"))
            } finally { release.countDown(); fixture.stop() }
        }
    }

    @Test fun `scope change and receipt fast frame revoke visual stock without resetting durable intent`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start(); fixture.buy()
            val events = fixture.events()
            val epoch = fixture.session.currentShopStockEpoch()
            fixture.call("resolveShopStockFrame", completed(), fixture.now + 1)
            assertEquals(events, fixture.events())
            fixture.nextShop()
            fixture.call("resolveShopStockFrame", stock(), fixture.now + 2)
            assertTrue(fixture.session.currentShopStockEpoch() > epoch)
            assertEquals(events, fixture.events())
            assertFalse(fixture.session.state.value.lastResult?.shopObservation?.coverage?.epoch == fixture.session.currentShopStockEpoch())
        } finally { fixture.stop() }
    }

    @Test fun `refresh anchor with intermediate enabled evidence neither unlocks three stars nor closes`() = runBlocking {
        val fixture = Fixture(temp.newFolder())
        try {
            fixture.start()
            val goods = listOf(item.copy(relicMatch = item.relicMatch!!.copy(rarity = 3)))
            val raw = stock(goods = goods)
            fixture.observe(raw.copy(shopObservation = raw.shopObservation!!.copy(refreshButtonEnabledEvidence = 0.35)))
            assertTrue(fixture.call("currentShopDecision", fixture.session.state.value.lastResult) is LabyrinthShopDecision.Wait)
            assertEquals(0, fixture.taps.get())
        } finally { fixture.stop() }
    }

    private fun refreshStock(balance: Long = 1000): LabyrinthEntryFrameResult {
        val goods = (1..3).map { item.copy(slotId = "slot-$it", status = LabyrinthShopItemStatus.DISABLED) }
        val full = stock(balance, goods)
        return full.copy(shopObservation = full.shopObservation!!.copy(refreshButtonEnabledEvidence = 1.0))
    }

    private fun refreshConfirmation(balance: Long = 1000) = frame(LabyrinthEntryPageState.UNKNOWN).copy(
        observation = LabyrinthEntryPageObservation(LabyrinthEntryPageState.UNKNOWN, 1.0, emptyMap(),
            LabyrinthAnchorScores(mapOf(EntryAnchorId.SHOP_TITLE to 1.0))),
        nodeMoveConfirmation = LabyrinthNodeMoveConfirmationObservation(1.0, rect, EntryPixelRect(500, 800, 400, 80)),
        shopObservation = LabyrinthShopObservation(emptyList(), refreshConfirmationTitle = ShoppingTestData.read("更新确认")),
        currencyObservation = ShoppingTestData.wallet(balance).copy(source = LabyrinthCurrencySource.REFRESH_CONFIRMATION))

    private fun imprintConfirmation(value: LabyrinthShopItemMatch) = frame(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
        LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_CONFIRMATION,
            confirmationTitle = ShoppingTestData.read(value.titleText)))

    private fun confirmation() = frame(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
        LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_CONFIRMATION, purchaseCandidate = item.relicMatch))

    private fun stock(balance: Long = 1000, goods: List<LabyrinthShopItemMatch> = listOf(item)): LabyrinthEntryFrameResult {
        val full = completedPolicyStock(goods).mapIndexed { index, good ->
            val position = rect.copy(left = rect.left + index % 3 * 578, top = rect.top + index / 3 * 380)
            good.copy(slotId = LabyrinthShopSlots.keys[index], screenRect = position, buyButtonRect = position)
        }
        return frame(LabyrinthEntryPageState.SHOP, LabyrinthShopObservation(full,
            coverage = LabyrinthShopCoverage(0, 0, LabyrinthShopScanPhase.COMPLETE))).copy(
            currencyObservation = ShoppingTestData.wallet(balance),
            // Transaction tests begin after scanning; incomplete scan tests use observations without coverage.
            anchorMatches = mapOf(EntryAnchorId.SHOP_REFRESH_BUTTON to EntryAnchorMatch(1.0, rect)))
    }
    private fun completed(value: LabyrinthShopPurchaseReceiptObservation = receipt) =
        frame(LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
            LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_COMPLETE, purchaseReceipt = value))
    private fun frame(page: LabyrinthEntryPageState, shop: LabyrinthShopObservation? = null,
        special: LabyrinthSpecialEquipmentObservation? = null) = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(page, 1.0, emptyMap(), LabyrinthAnchorScores(emptyMap())),
        matchedFeatures = emptyList(), elapsedMillis = 0, shopObservation = shop, specialEquipment = special,
        frameWidth = 1920, frameHeight = 1080,
        anchorMatches = if (page == LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION)
            mapOf(EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON to EntryAnchorMatch(1.0, rect)) else emptyMap(),
        currencyObservation = if (page == LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION)
            ShoppingTestData.wallet(1000).copy(source = LabyrinthCurrencySource.PURCHASE_CONFIRMATION) else null)

    private inner class Fixture(val root: File, val store: IdempotentStore = IdempotentStore(), val enabled: Boolean = true,
        val moduleOnly: Boolean = false, val wrongJournal: Boolean = false,
        val accountId: Long = 1, val guildId: Int = 1, val strategy: (() -> LabyrinthStrategySnapshot)? = null) {
        val journal = LabyrinthShoppingJournal(root)
        val module = LabyrinthShoppingModule(journal)
        val manager = AutomationSessionManager()
        val taps = AtomicInteger()
        val actions = java.util.concurrent.CopyOnWriteArrayList<AutomationAction>()
        val terminals = java.util.concurrent.CopyOnWriteArrayList<com.landosol.toolbox.labyrinth.batch.LabyrinthRunTerminalEvent>()
        val resolutions = AtomicInteger()
        var now = 1000L
        var backend: suspend () -> AutomationBackendResult = { AutomationBackendResult.Completed }
        @Volatile var targetReady: () -> Boolean = { true }
        val session: LabyrinthEntryRecognitionSession by lazy { LabyrinthEntryRecognitionSession(
            sessionManager = manager, captureActive = { true },
            processorFactory = { { error("集成测试不创建 Bitmap") } },
            actionExecutor = SessionBoundActionExecutor(manager) { action ->
                if (action is AutomationAction.Tap && enabled && taps.get() == 0 && session.state.value.lastResult?.observation?.state == LabyrinthEntryPageState.SHOP &&
                    session.state.value.lastResult?.shopObservation?.items?.any { it.purchasable } == true) {
                    check(types().contains("shop.purchase_intent")) { "购买意图必须先于真实 tap 保存" }
                }
                if (enabled && session.state.value.lastResult?.observation?.state == LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION) {
                    if (action == AutomationAction.Tap(ScreenPoint(rect.left + rect.width / 2f, rect.top + rect.height / 2f))) {
                        check(types().last() == "shop.purchase_confirm_prepared") { "prepared必须先于真实executor落盘" }
                    } else {
                        check(types().contains("shop.purchase_abandoned")) { "取消必须属于显式放弃的购买" }
                        val frame = checkNotNull(session.state.value.lastResult)
                        val cancel = checkNotNull(ReferenceFitMapper.map(frame.frameWidth, frame.frameHeight,
                            EntryReferenceSize(1920, 1080), EntryReferenceRect(535, 820, 412, 97)))
                        check(action == AutomationAction.Tap(ScreenPoint(cancel.left + cancel.width / 2f, cancel.top + cancel.height / 2f))) {
                            "放弃购买只能点击居中适配的取消区域"
                        }
                    }
                }
                actions += action
                taps.incrementAndGet(); backend()
            },
            runTerminalListener = { terminals += it },
            actionsAvailable = { true }, actionTargetReady = { targetReady() }, clock = { now }, runStateStore = store,
            shoppingRelicResolver = { identity ->
                resolutions.incrementAndGet()
                item.relicMatch?.takeIf { it.relicId == identity || it.displayName == identity }
            },
            shoppingJournal = if (!enabled || moduleOnly) null else if (wrongJournal) LabyrinthShoppingJournal(root) else journal,
            shoppingModule = if (enabled) module else null,
            shoppingImprintCatalog = ShoppingTestData.imprintCatalog,
            strategyProvider = strategy,
        ) }
        suspend fun start() {
            assertTrue(session.startAutomation(accountId = accountId) is LabyrinthEntryRecognitionStartResult.Started)
            set("validatedRoute", LabyrinthRouteJson(100, guildId, 1, 1, false, false, "", emptyList(), emptyList(),
                listOf(LabyrinthNodeJson(5, 1, 1, 50001, LabyrinthNodeTypes.SHOP, null, emptyList(), false)), currentBlockId = 50001))
            set("activeNodeType", LabyrinthNodeTypes.SHOP)
            set("activeNodeArea", 5)
        }
        fun enablePostEntryRoute() {
            // This test has already entered a saved shop; the entry-only planner intentionally
            // finishes on SHOP when no route executor was configured.
            val route: suspend (Long?) -> LabyrinthRouteJson? = { get("validatedRoute") as LabyrinthRouteJson }
            val templates: () -> com.landosol.toolbox.labyrinth.node.NodeTemplateSet = {
                com.landosol.toolbox.labyrinth.node.NodeTemplateSet(emptyMap())
            }
            set("routeLoader", route)
            set("nodeTemplateLoader", templates)
            set("entryPhaseComplete", true)
        }
        fun expectedUnscannedPublication(raw: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
            val pricing = get("shoppingPricingContext") as LabyrinthShopPricingContext
            return raw.copy(shopObservation = raw.shopObservation?.let { shop ->
                shop.copy(coverage = null, items = shop.items.map { pricing.price(it, ShoppingTestData.imprintCatalog) })
            })
        }
        suspend fun stop() { session.stop(releaseCapture = false) }
        fun id() = get("activeSessionId") as AutomationSessionId
        fun run() = journal.open(accountId, 100, null, now)
        fun events() = journal.read(run())
        fun types() = events().map(ShoppingTestData::type)
        fun count() = get("shopRelicPurchasesThisCycle") as Int
        fun refreshCount() = get("shopRefreshesThisVisit") as Int
        fun canDismiss(value: LabyrinthShopPurchaseReceiptObservation): Boolean = module.canDismiss(value,
            (get("validatedRoute") as LabyrinthRouteJson).currentBlockId, get("activeNodeArea") as Int)
        fun trackTerminal() = set("currentRunId", "shopping-run")
        fun nextShop(nodeId: Long = 50002) {
            set("validatedRoute", (get("validatedRoute") as LabyrinthRouteJson).copy(currentBlockId = nodeId))
            set("confirmedNodeEntryReceipt", null)
            set("pendingNodeTransition", null)
            set("activeNodeType", LabyrinthNodeTypes.SHOP)
            set("activeNodeArea", 5)
            set("shopRelicPurchasesThisCycle", 0)
            set("shopRefreshesThisVisit", 0)
        }
        suspend fun openRefresh() {
            assertFalse(observe(refreshStock()))
            dispatch(LabyrinthPostEntryActionKind.SHOP_REFRESH); awaitIdle()
        }
        fun field(name: String) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun get(name: String): Any? = field(name).get(session)
        fun set(name: String, value: Any?) = field(name).set(session, value)
        @Suppress("UNCHECKED_CAST")
        fun state(result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
            val flow = get("_state") as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            call("bindShopStockScope", result.frameWidth, result.frameHeight)
            val scanner = get("shopStockScanner") as LabyrinthShopStockScanner
            val shop = result.shopObservation
            val normalized = if (result.observation.state == LabyrinthEntryPageState.SHOP && shop?.coverage != null) {
                scanner.invalidate()
                val top = shop.items.map { it.copy(buyButtonRect = it.buyButtonRect.copy(top = it.buyButtonRect.top + 105)) }
                val completed = ShopScanFixtures.complete(scanner, top, now - 10)
                set("shopStockPageActive", true)
                result.copy(shopObservation = completed.copy(refreshButtonEnabledEvidence = shop.refreshButtonEnabledEvidence))
            } else call("resolveShopStockFrame", result, now) as LabyrinthEntryFrameResult
            flow.value = flow.value.copy(lastResult = normalized)
            return normalized
        }
        fun observe(result: LabyrinthEntryFrameResult): Boolean {
            now += 1000
            val current = state(result)
            return call("handleShoppingFrame", id(), current, now) as Boolean
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
        fun dispatch(kind: LabyrinthPostEntryActionKind, relicId: String? = null, imprint: String? = null): Any? {
            val decision = session.state.value.lastResult?.let { call("currentShopDecision", it) }
            val target = when {
                kind == LabyrinthPostEntryActionKind.SHOP_BUY_RELIC && decision is LabyrinthShopDecision.BuyRelic -> decision.item.buyButtonRect
                kind == LabyrinthPostEntryActionKind.SHOP_BUY_IMPRINT && decision is LabyrinthShopDecision.BuyRoleImprint -> decision.item.buyButtonRect
                else -> rect
            }
            return call("dispatchPostEntryTap", id(), kind, "集成测试点击", target, now,
                null, null, relicId, imprint, null, false, emptyList<String>())
        }
        suspend fun awaitIdle() {
            withTimeout(5000) { while ((get("actionInFlight") as AtomicBoolean).get() ||
                (get("shoppingOwnershipInFlight") as AtomicBoolean).get() || pendingExitWrite()) delay(10) }
        }
        private fun pendingExitWrite(): Boolean {
            val exit = pendingExit(this) ?: return false
            return !exit.javaClass.getDeclaredField("abandoned").apply { isAccessible = true }.getBoolean(exit)
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
