package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopCategoryState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopReceiptRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthShoppingPolicyIntegrationTest {
    private val rect = EntryPixelRect(0, 0, 10, 10)
    private val budgetPolicy = LabyrinthShopPolicy(budgetOptimizationEnabled = true)

    @Test fun `默认关闭预算兼容既有未提供金额的购买调用`() {
        val item = relic("legacy", 100).copy(priceRead = null)
        val decision = LabyrinthShopPolicy().decide(5, listOf(item), emptyList(), refreshAvailable = true)
        assertTrue(decision is LabyrinthShopDecision.BuyRelic)
        assertNull(decision.budget)
        assertEquals(decision, LabyrinthShopPolicy(budgetOptimizationEnabled = false).decide(
            5, listOf(item), emptyList(), refreshAvailable = true,
            verifiedBalance = -1, verifiedRefreshPrice = -1))
    }

    @Test fun `默认关闭预算保留选择印记未提供价格的既有兜底`() {
        val item = role("legacy-choice", 100, choice = true).copy(priceRead = null)
        val decision = LabyrinthShopPolicy().decide(3, listOf(item), emptyList(), refreshAvailable = false)
        assertTrue(decision is LabyrinthShopDecision.BuyRoleImprint)
        assertTrue((decision as LabyrinthShopDecision.BuyRoleImprint).opensRolePicker)
        assertNull(decision.budget)
    }

    @Test fun `开启后未知余额或负余额不能盲买`() {
        for (balance in listOf(null, -1L)) {
            assertTrue(decide(listOf(relic("a", 100)), balance = balance) is LabyrinthShopDecision.Wait)
        }
    }

    @Test fun `可购买遗物随机与选择印记缺价格或未双读都等待`() {
        for (item in listOf(relic("relic", 100), role("random", 100), role("choice", 100, choice = true))) {
            assertTrue(decide(listOf(item.copy(priceRead = null))) is LabyrinthShopDecision.Wait)
            assertTrue(decide(listOf(item.copy(priceRead = LabyrinthShopReceiptRead("100", 1, 1))))
                is LabyrinthShopDecision.Wait)
            assertTrue(decide(listOf(item.copy(priceRead = LabyrinthShopReceiptRead("-1", 1, 2))))
                is LabyrinthShopDecision.Wait)
        }
    }

    @Test fun `未知可购买商品不能被其他已知商品绕过`() {
        val unknown = item("unknown", 100, LabyrinthShopItemKind.UNKNOWN)
        assertTrue(decide(listOf(relic("known", 100), unknown)) is LabyrinthShopDecision.Wait)
        assertTrue(decide(listOf(unknown)) is LabyrinthShopDecision.Wait)
    }

    @Test fun `组合选择限制用户偏好不得改买篮子外昂贵遗物`() {
        val preferred = LabyrinthRelicChoicePolicy(LabyrinthRelicChoicePolicyConfig(
            markPriority = listOf(LabyrinthRelicMark.DEFENSE)))
        val policy = LabyrinthShopPolicy(relicChoicePolicy = preferred, budgetOptimizationEnabled = true)
        val result = decide(listOf(relic("a-expensive", 600, LabyrinthRelicMark.DEFENSE),
            relic("b-cheap", 350, LabyrinthRelicMark.CRITICAL), role("c-random", 250)),
            balance = 600, policy = policy) as LabyrinthShopDecision.BuyRelic
        assertEquals("b-cheap", result.item.slotId)
        assertEquals(listOf("b-cheap", "c-random"), result.budget!!.selectedSlots)
        assertEquals(600L, result.budget!!.basketCost)
        assertEquals(600, result.budget!!.directScore)
    }

    @Test fun `用户遗物偏好只决定预算篮子内下一件的购买顺序`() {
        val preferred = LabyrinthRelicChoicePolicy(LabyrinthRelicChoicePolicyConfig(
            markPriority = listOf(LabyrinthRelicMark.DEFENSE)))
        val policy = LabyrinthShopPolicy(relicChoicePolicy = preferred, budgetOptimizationEnabled = true)
        val result = decide(listOf(relic("critical", 100, LabyrinthRelicMark.CRITICAL),
            relic("defense", 100, LabyrinthRelicMark.DEFENSE)), balance = 200,
            policy = policy) as LabyrinthShopDecision.BuyRelic
        assertEquals("defense", result.item.slotId)
        assertEquals(2, result.budget!!.selectedSlots.size)
    }

    @Test fun `实际刷新费预留生效且关闭刷新后释放购买额度`() {
        val items = listOf(relic("a", 400), relic("b", 400))
        val reserved = decide(items, balance = 1_000, refreshAvailable = true,
            refreshPrice = 150) as LabyrinthShopDecision.BuyRelic
        assertEquals(550L, reserved.budget!!.reserve)
        assertEquals(400L, reserved.budget!!.basketCost)
        val released = decide(items, balance = 1_000, refreshAvailable = true,
            policy = LabyrinthShopPolicy(refreshEnabled = false, budgetOptimizationEnabled = true))
            as LabyrinthShopDecision.BuyRelic
        assertEquals(0L, released.budget!!.reserve)
        assertEquals(800L, released.budget!!.basketCost)
    }

    @Test fun `无法同时预留刷新和最廉商品时按实际余额购买`() {
        val result = decide(listOf(relic("a", 400)), balance = 500,
            refreshAvailable = true, refreshPrice = 200) as LabyrinthShopDecision.BuyRelic
        assertEquals(0L, result.budget!!.reserve)
        assertEquals(400L, result.budget!!.basketCost)
    }

    @Test fun `售罄行无法预留费用及商品时释放预算购买随机印记而不空刷新`() {
        val row = disabledRow().take(2) + role("random", 100)
        val result = decide(row, balance = 100, refreshAvailable = true, refreshPrice = 73)
            as LabyrinthShopDecision.BuyRoleImprint
        assertEquals("random", result.item.slotId)
        assertEquals(0L, result.budget!!.reserve)
        assertEquals(100L, result.budget!!.basketCost)
    }

    @Test fun `已核实库存不足以周转时不空刷新且保留核实选择印记兜底`() {
        val row = listOf(role("random", 101), role("choice", 100, choice = true))
        val result = decide(row, balance = 100, refreshAvailable = true, refreshPrice = 73,
            purchases = 3) as LabyrinthShopDecision.BuyRoleImprint
        assertEquals("choice", result.item.slotId)
        assertEquals(0L, result.budget!!.reserve)
    }

    @Test fun `明确随机身份排除疑似遗物图标且不打开选择页`() {
        val random = role("random", 100).copy(relicMatch = relic("misleading", 100).relicMatch!!
            .copy(relicId = null, confidence = 0.72))
        val result = decide(listOf(random), balance = 100) as LabyrinthShopDecision.BuyRoleImprint
        assertEquals("random", result.item.slotId)
        assertFalse(result.opensRolePicker)
    }

    @Test fun `随机印记无明确标题或缺职能身份时等待`() {
        assertTrue(decide(listOf(role("unknown-title", 100).copy(titleText = null))) is LabyrinthShopDecision.Wait)
        assertTrue(decide(listOf(role("unknown-role", 100).copy(roleImprintLabel = null))) is LabyrinthShopDecision.Wait)
        assertTrue(decide(listOf(role("unknown-choice", 100, choice = true).copy(roleImprintLabel = null)))
            is LabyrinthShopDecision.Wait)
    }

    @Test fun `随机可买时选择印记不参加预算篮子`() {
        val result = decide(listOf(role("choice", 100, choice = true), role("random", 100)), balance = 200)
            as LabyrinthShopDecision.BuyRoleImprint
        assertEquals("random", result.item.slotId)
        assertEquals(listOf("random"), result.budget!!.selectedSlots)
    }

    @Test fun `无可买随机遗物且不能刷新时核实选择印记兜底`() {
        val result = decide(listOf(relic("expensive-relic", 301), role("expensive-random", 301),
            role("choice", 200, choice = true)), balance = 200) as LabyrinthShopDecision.BuyRoleImprint
        assertEquals("choice", result.item.slotId)
        assertTrue(result.opensRolePicker)
        assertEquals(0, result.budget!!.directScore)
        assertEquals(200L, result.budget!!.basketCost)
        assertEquals(listOf("choice"), result.budget!!.selectedSlots)
    }

    @Test fun `选择印记余额不足或零余额不能兜底购买`() {
        for (balance in listOf(99L, 0L)) {
            assertTrue(decide(listOf(role("choice", 100, choice = true)), balance = balance)
                is LabyrinthShopDecision.Close)
        }
    }

    @Test fun `预算不足或零余额不能购买也不能支付刷新费用`() {
        for (balance in listOf(99L, 0L)) {
            assertTrue(decide(listOf(relic("a", 100)), balance = balance) is LabyrinthShopDecision.Close)
            assertTrue(decide(disabledRow(), balance = balance, refreshAvailable = true, refreshPrice = 100)
                is LabyrinthShopDecision.Close)
        }
    }

    @Test fun `购买开关与区域四禁购优先于未知预算检查`() {
        val unknown = relic("unknown-price", 100).copy(priceRead = null,
            categoryState = LabyrinthShopCategoryState.READING)
        assertTrue(decide(listOf(unknown), balance = null, refreshAvailable = true,
            policy = LabyrinthShopPolicy(buyRelics = false, budgetOptimizationEnabled = true)) is LabyrinthShopDecision.Close)
        for (start in 1..5) {
            val policy = LabyrinthShopPolicy(finalArea = start, budgetOptimizationEnabled = true)
            assertTrue(decide(listOf(unknown), area = 4, balance = null,
                refreshAvailable = true, policy = policy) is LabyrinthShopDecision.Close)
            assertTrue(decide(disabledRow(), area = 4, balance = 1_000,
                refreshAvailable = true, refreshPrice = 37, policy = policy) is LabyrinthShopDecision.Close)
        }
    }

    @Test fun `预算分支保留自定义禁购区域`() {
        val policy = LabyrinthShopPolicy(noPurchaseAreas = setOf(2, 4), budgetOptimizationEnabled = true)
        assertTrue(decide(listOf(relic("a", 100)), area = 2, policy = policy) is LabyrinthShopDecision.Close)
    }

    @Test fun `刷新起始区域配置逐区生效且区域四始终禁购`() {
        for (start in 1..5) {
            for (area in 1..5) {
                val policy = LabyrinthShopPolicy(finalArea = start, budgetOptimizationEnabled = true)
                val result = decide(disabledRow(), area = area, refreshAvailable = true,
                    refreshPrice = 37, policy = policy)
                assertEquals("start=$start area=$area result=$result", area != 4 && area >= start,
                    result is LabyrinthShopDecision.Refresh)
            }
        }
    }

    @Test fun `区域三仍只允许一次刷新并在额度耗尽后保留印记兜底`() {
        val policy = LabyrinthShopPolicy(finalArea = 3, budgetOptimizationEnabled = true)
        val row = disabledRow().take(2) + role("choice", 200, choice = true)
        assertTrue(decide(row, area = 3, refreshAvailable = true, refreshPrice = 73,
            policy = policy) is LabyrinthShopDecision.Refresh)
        val spent = decide(row, area = 3, refreshAvailable = true, refreshes = 1,
            policy = policy) as LabyrinthShopDecision.BuyRoleImprint
        assertTrue(spent.opensRolePicker)
        assertTrue(decide(disabledRow(), area = 3, refreshAvailable = true, refreshes = 1,
            policy = policy) is LabyrinthShopDecision.Close)
    }

    @Test fun `未配置刷新上限的区域不硬编码两次限制`() {
        for (used in listOf(0, 1, 2, 5)) {
            assertTrue(decide(disabledRow(), refreshAvailable = true, refreshPrice = 251,
                refreshes = used) is LabyrinthShopDecision.Refresh)
        }
        val policy = LabyrinthShopPolicy(refreshLimitsByArea = mapOf(5 to 0), budgetOptimizationEnabled = true)
        assertTrue(decide(disabledRow(), refreshAvailable = true, policy = policy) is LabyrinthShopDecision.Close)
    }

    @Test fun `售罄刷新继续优先于印记但只使用本次核实费用`() {
        val row = disabledRow().take(2) + role("random", 200)
        val result = decide(row, balance = 1_000, refreshAvailable = true,
            refreshPrice = 73) as LabyrinthShopDecision.Refresh
        assertTrue(result.reason.contains("73币"))
        assertFalse(result.reason.contains("300"))
    }

    @Test fun `刷新费用必须复核且余额恰好覆盖实际费用即可刷新`() {
        for (price in listOf(null, -1L)) {
            assertTrue(decide(disabledRow(), refreshAvailable = true, refreshPrice = price)
                is LabyrinthShopDecision.Wait)
        }
        assertTrue(decide(disabledRow(), balance = 73, refreshAvailable = true, refreshPrice = 73)
            is LabyrinthShopDecision.Refresh)
        assertTrue(decide(disabledRow(), balance = 72, refreshAvailable = true, refreshPrice = 73)
            is LabyrinthShopDecision.Close)
        assertTrue(decide(listOf(relic("a", 100)), refreshAvailable = true) is LabyrinthShopDecision.Wait)
    }

    @Test fun `不能确定刷新按钮时等待但关闭刷新或额度耗尽不要求复核`() {
        assertTrue(decide(disabledRow(), refreshAvailable = false, refreshKnown = false) is LabyrinthShopDecision.Wait)
        val disabled = LabyrinthShopPolicy(refreshEnabled = false, budgetOptimizationEnabled = true)
        assertTrue(decide(disabledRow(), refreshAvailable = true, refreshKnown = false,
            balance = null, policy = disabled) is LabyrinthShopDecision.Close)
        assertTrue(decide(listOf(relic("a", 100)), refreshAvailable = true, refreshKnown = false,
            policy = disabled) is LabyrinthShopDecision.BuyRelic)
        val capped = LabyrinthShopPolicy(finalArea = 3, budgetOptimizationEnabled = true)
        assertTrue(decide(listOf(relic("a", 100)), area = 3, refreshAvailable = true,
            refreshKnown = false, refreshes = 1, policy = capped) is LabyrinthShopDecision.BuyRelic)
    }

    @Test fun `起始区域之前不要求刷新费用且禁用行安全退出`() {
        assertTrue(decide(listOf(relic("a", 100)), area = 3, refreshAvailable = true)
            is LabyrinthShopDecision.BuyRelic)
        assertTrue(decide(disabledRow(), area = 3, refreshAvailable = true, balance = null)
            is LabyrinthShopDecision.Close)
        assertTrue(decide(disabledRow(), area = null, refreshAvailable = true, balance = null)
            is LabyrinthShopDecision.Close)
    }

    @Test fun `缺商品行或仍有可买遗物时不提前刷新`() {
        assertTrue(decide(disabledRow().take(2), refreshAvailable = true, refreshPrice = 73)
            is LabyrinthShopDecision.Close)
        assertTrue(decide(listOf(relic("a", 100)), balance = 1_000,
            refreshAvailable = true, refreshPrice = 73) is LabyrinthShopDecision.BuyRelic)
    }

    @Test fun `三个明确印记仍恢复售罄并按当前刷新规则处理`() {
        val row = (1..3).map { role("random$it", 100) }
        assertTrue(decide(row, refreshAvailable = true, refreshPrice = 73) is LabyrinthShopDecision.Refresh)
        assertTrue(decide(emptyList(), refreshAvailable = true, refreshPrice = 73,
            purchases = 3) is LabyrinthShopDecision.Refresh)
    }

    @Test fun `禁用商品身份未知不阻塞刷新但未禁用未知按钮仍等待`() {
        for (category in LabyrinthShopCategoryState.entries) {
            assertTrue(decide(disabledRow().map { it.copy(categoryState = category) },
                refreshAvailable = true, refreshPrice = 73) is LabyrinthShopDecision.Refresh)
        }
        assertTrue(decide(disabledRow().take(2) + item("unknown", 100, LabyrinthShopItemKind.UNKNOWN)
            .copy(status = LabyrinthShopItemStatus.UNKNOWN), refreshAvailable = true, refreshPrice = 73)
            is LabyrinthShopDecision.Wait)
    }

    @Test fun `身份识别已耗尽的未知商品仍跳过且不伪造售罄证据`() {
        val exhausted = item("exhausted", 100, LabyrinthShopItemKind.UNKNOWN)
            .copy(categoryState = LabyrinthShopCategoryState.EXHAUSTED, priceRead = null)
        assertTrue(decide(listOf(exhausted, relic("known", 100))) is LabyrinthShopDecision.BuyRelic)
        assertTrue(decide(disabledRow().take(2) + exhausted,
            refreshAvailable = true, refreshPrice = 73) is LabyrinthShopDecision.Close)
        assertTrue(decide(listOf(exhausted), balance = null) is LabyrinthShopDecision.Close)
        assertTrue(decide(listOf(exhausted.copy(categoryState = LabyrinthShopCategoryState.READING)))
            is LabyrinthShopDecision.Wait)
    }

    @Test fun `已识别遗物缺属性元数据时不买`() {
        val incomplete = relic("a", 100).let { it.copy(relicMatch = it.relicMatch!!.copy(attribute = null)) }
        assertTrue(decide(listOf(incomplete)) is LabyrinthShopDecision.Wait)
    }

    @Test fun `策略接受十二候选并把十三候选转为安全等待`() {
        val items = (1..13).map { relic("item$it", 100) }
        val result = decide(items.take(12), balance = 1_200) as LabyrinthShopDecision.BuyRelic
        assertEquals(12, result.budget!!.selectedSlots.size)
        assertTrue(decide(items, balance = 1_300) is LabyrinthShopDecision.Wait)
        assertTrue(decide(items, balance = 0) is LabyrinthShopDecision.Wait)
    }

    @Test fun `Long边界余额和刷新费用不引起负预算或盲刷新`() {
        val result = decide(listOf(relic("a", 100)), balance = Long.MAX_VALUE,
            refreshAvailable = true, refreshPrice = Long.MAX_VALUE) as LabyrinthShopDecision.BuyRelic
        assertEquals(0L, result.budget!!.reserve)
        assertEquals(100L, result.budget!!.basketCost)
        assertTrue(decide(disabledRow(), balance = Long.MAX_VALUE - 1,
            refreshAvailable = true, refreshPrice = Long.MAX_VALUE) is LabyrinthShopDecision.Close)
    }

    @Test fun `商品压缩后重新依据当前身份价格预算决定而不记旧槽位`() {
        val first = decide(listOf(relic("slot1", 100, LabyrinthRelicMark.CRITICAL)), balance = 100)
            as LabyrinthShopDecision.BuyRelic
        val next = decide(listOf(relic("slot1", 90, LabyrinthRelicMark.DEFENSE)), balance = 90)
            as LabyrinthShopDecision.BuyRelic
        assertEquals("会心", first.relicDecision.choice.attribute)
        assertEquals("守备", next.relicDecision.choice.attribute)
        assertEquals(90L, next.budget!!.basketCost)
    }

    private fun decide(
        items: List<LabyrinthShopItemMatch>, balance: Long? = 1_000, area: Int? = 5,
        refreshAvailable: Boolean = false, refreshPrice: Long? = null, refreshKnown: Boolean = true,
        refreshes: Int = 0, purchases: Int = 0, policy: LabyrinthShopPolicy = budgetPolicy,
    ) = policy.decide(
        area, items, emptyList(), refreshAvailable = refreshAvailable,
        refreshAvailabilityKnown = refreshKnown, refreshesUsedInVisit = refreshes,
        relicPurchasesInCycle = purchases, verifiedBalance = balance, verifiedRefreshPrice = refreshPrice,
    )

    private fun disabledRow() = (1..3).map {
        item("disabled$it", 100, LabyrinthShopItemKind.UNKNOWN)
            .copy(status = LabyrinthShopItemStatus.DISABLED, priceRead = null)
    }

    private fun relic(id: String, price: Long, mark: LabyrinthRelicMark = LabyrinthRelicMark.CRITICAL) =
        item(id, price, LabyrinthShopItemKind.RELIC).copy(relicMatch = LabyrinthRelicMatch(
            slotId = id, relicId = id, displayName = id, attribute = mark.label, attributeBonus = "1",
            effect = null, confidence = 0.9, screenRect = rect, iconRect = rect, rivalMargin = 0.2,
        ))

    private fun role(id: String, price: Long, choice: Boolean = false) =
        item(id, price, LabyrinthShopItemKind.ROLE_IMPRINT).copy(
            titleText = "攻击型职能${if (choice) "选择" else "随机"}印记",
            roleImprintLabel = "攻击型职能印记", choiceRoleImprint = choice,
        )

    private fun item(id: String, price: Long, kind: LabyrinthShopItemKind) = LabyrinthShopItemMatch(
        slotId = id, screenRect = rect, buyButtonRect = rect, buyButtonScore = 0.9,
        buyButtonEnabledEvidence = 1.0, status = LabyrinthShopItemStatus.AVAILABLE, kind = kind,
        priceRead = LabyrinthShopReceiptRead(price.toString(), 1L, 2),
    )
}
