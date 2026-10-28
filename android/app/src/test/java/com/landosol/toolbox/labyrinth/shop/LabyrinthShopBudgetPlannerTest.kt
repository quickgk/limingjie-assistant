package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopCategoryState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopReceiptRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LabyrinthShopBudgetPlannerTest {
    private val rect = EntryPixelRect(0, 0, 10, 10)

    @Test fun `组合优于先买单件高分遗物的贪心`() {
        val result = plan(listOf(relic("a", 600), relic("b", 350), role("c", 250)), 600)
        assertEquals(listOf("b", "c"), result.selected.map { it.slotId })
        assertEquals(600, result.score)
        assertEquals(600L, result.cost)
    }

    @Test fun `两件便宜遗物优于一件昂贵遗物`() {
        val result = plan(listOf(relic("a", 400), relic("b", 250), relic("c", 250)), 500)
        assertEquals(listOf("b", "c"), result.selected.map { it.slotId })
        assertEquals(800, result.score)
    }

    @Test fun `可承担时预留实际刷新费及最廉已识别商品`() {
        val result = plan(listOf(relic("a", 300), role("b", 200)), 1_000,
            refreshPrice = 300, reserveRefresh = true)
        assertEquals(500L, result.reserve)
        assertEquals(500L, result.cost)
        assertEquals(600, result.score)
        assertTrue(result.cost <= 1_000 - result.reserve)
    }

    @Test fun `费用与最廉商品不能一并承担时释放预留`() {
        val result = plan(listOf(relic("a", 300), role("b", 200)), 450,
            refreshPrice = 300, reserveRefresh = true)
        assertEquals(0L, result.reserve)
        assertEquals(listOf("a"), result.selected.map { it.slotId })
    }

    @Test fun `刷新费用不足或未启用预留不占用余额`() {
        val items = listOf(relic("a", 100))
        assertEquals(0L, plan(items, 100, refreshPrice = 101, reserveRefresh = true).reserve)
        assertEquals(0L, plan(items, 1_000, refreshPrice = 300).reserve)
        assertEquals(0L, plan(items, 1_000, reserveRefresh = true).reserve)
        assertEquals(0L, plan(items, 1_000, refreshPrice = 0, reserveRefresh = true).reserve)
    }

    @Test fun `保留源算法的已识别库存周转口径但禁用商品不进篮子`() {
        val disabled = relic("a", 100).copy(status = LabyrinthShopItemStatus.DISABLED)
        val result = plan(listOf(disabled, relic("b", 500)), 1_000,
            refreshPrice = 300, reserveRefresh = true)
        assertEquals(400L, result.reserve)
        assertEquals(listOf("b"), result.selected.map { it.slotId })
    }

    @Test fun `四层优先位先于十五层及成本`() {
        val result = plan(listOf(relic("a", 90, LabyrinthRelicMark.DEFENSE),
            relic("z", 100, LabyrinthRelicMark.ENHANCEMENT)), 100,
            mapOf(LabyrinthRelicMark.DEFENSE to 14, LabyrinthRelicMark.ENHANCEMENT to 3))
        assertEquals("z", result.selected.single().slotId)
    }

    @Test fun `四层位按强化加速会心守备弱体的源顺序比较`() {
        val priority = listOf(LabyrinthRelicMark.ENHANCEMENT, LabyrinthRelicMark.ACCELERATION,
            LabyrinthRelicMark.CRITICAL, LabyrinthRelicMark.DEFENSE, LabyrinthRelicMark.DEBUFF)
        for (index in 0 until priority.lastIndex) {
            val higher = priority[index]
            val lower = priority[index + 1]
            val result = plan(listOf(relic("a", 100, lower), relic("z", 100, higher)), 100,
                mapOf(higher to 3, lower to 3))
            assertEquals(higher.label, result.selected.single().relicMatch!!.attribute)
        }
    }

    @Test fun `四层相同时十五层优于九层`() {
        val result = plan(listOf(relic("a", 100, LabyrinthRelicMark.ACCELERATION),
            relic("z", 100, LabyrinthRelicMark.DEFENSE)), 100,
            mapOf(LabyrinthRelicMark.ACCELERATION to 8, LabyrinthRelicMark.DEFENSE to 14))
        assertEquals("z", result.selected.single().slotId)
    }

    @Test fun `没有十五层时九层优于不跨档位`() {
        val result = plan(listOf(relic("a", 100, LabyrinthRelicMark.CRITICAL),
            relic("z", 100, LabyrinthRelicMark.ACCELERATION)), 100,
            mapOf(LabyrinthRelicMark.CRITICAL to 5, LabyrinthRelicMark.ACCELERATION to 8))
        assertEquals("z", result.selected.single().slotId)
    }

    @Test fun `同分同档位优先更低总成本`() {
        val result = plan(listOf(relic("a", 200), relic("z", 100)), 200)
        assertEquals("z", result.selected.single().slotId)
        assertEquals(100L, result.cost)
    }

    @Test fun `同分同成本最后比较优先职能数量`() {
        val result = plan(listOf(role("a", 100, "坦克"), role("z", 100, "攻击")), 100)
        assertEquals("z", result.selected.single().slotId)
    }

    @Test fun `更低成本先于优先职能数量`() {
        val result = plan(listOf(role("a", 200, "攻击"), role("z", 100, "坦克")), 200)
        assertEquals("z", result.selected.single().slotId)
    }

    @Test fun `随机印记须有明确随机标题和职能且选择印记排除资格`() {
        val random = role("random", 100)
        assertTrue(LabyrinthShopBudgetPlanner.eligible(random))
        assertFalse(LabyrinthShopBudgetPlanner.eligible(random.copy(choiceRoleImprint = true)))
        assertFalse(LabyrinthShopBudgetPlanner.eligible(random.copy(titleText = "攻击型职能选择印记")))
        assertFalse(LabyrinthShopBudgetPlanner.eligible(random.copy(roleImprintLabel = null)))
        assertFalse(LabyrinthShopBudgetPlanner.eligible(relic("unknown", 100).copy(kind = LabyrinthShopItemKind.UNKNOWN)))
    }

    @Test fun `未知价格未双读及未完成身份读取均不进入已知库存`() {
        val known = relic("known", 100)
        val result = plan(listOf(known,
            relic("unknown", 100).copy(priceRead = null),
            relic("single-read", 100).copy(priceRead = LabyrinthShopReceiptRead("100", 1, 1)),
            relic("reading", 100).copy(categoryState = LabyrinthShopCategoryState.READING)), 1_000)
        assertEquals(listOf("known"), result.selected.map { it.slotId })
    }

    @Test fun `余额不足与零余额不产生购买组合`() {
        val items = listOf(relic("a", 100))
        assertTrue(plan(items, 99).selected.isEmpty())
        assertTrue(plan(items, 0).selected.isEmpty())
        assertEquals(0L, plan(items, 0).cost)
    }

    @Test fun `十二件可搜索十三件拒绝且不能用零余额绕过候选上限`() {
        val items = (1..13).map { relic("item$it", 100) }
        val result = plan(items.take(12), 1_200)
        assertEquals(12, result.selected.size)
        assertEquals(4_800, result.score)
        assertRejected { plan(items, 1_300) }
        assertRejected { plan(items, 0) }
    }

    @Test fun `拒绝负余额负费用及负层数`() {
        assertRejected { plan(emptyList(), -1) }
        assertRejected { plan(emptyList(), 0, refreshPrice = -1) }
        assertRejected { plan(emptyList(), 0, mapOf(LabyrinthRelicMark.CRITICAL to -1)) }
    }

    @Test fun `Long最大余额仍保留准确组合成本`() {
        val result = plan((1..12).map { relic("item$it", 999_999_999) }, Long.MAX_VALUE)
        assertEquals(11_999_999_988L, result.cost)
        assertEquals(12, result.selected.size)
    }

    @Test fun `预留恰好达到Long最大值不溢出`() {
        val result = plan(listOf(relic("a", 100)), Long.MAX_VALUE,
            refreshPrice = Long.MAX_VALUE - 100, reserveRefresh = true)
        assertEquals(Long.MAX_VALUE, result.reserve)
        assertTrue(result.selected.isEmpty())
        assertEquals(0L, result.cost)
    }

    @Test fun `Long边界费用加商品超过余额时释放而不回绕`() {
        val result = plan(listOf(relic("a", 100)), Long.MAX_VALUE,
            refreshPrice = Long.MAX_VALUE, reserveRefresh = true)
        assertEquals(0L, result.reserve)
        assertEquals(100L, result.cost)
    }

    @Test fun `多个最大Int层数加成不使档位比较回绕`() {
        val result = plan(listOf(relic("a", 90, LabyrinthRelicMark.ACCELERATION, Int.MAX_VALUE),
            relic("b", 90, LabyrinthRelicMark.ACCELERATION, Int.MAX_VALUE),
            relic("c", 100, LabyrinthRelicMark.ACCELERATION)), 200,
            mapOf(LabyrinthRelicMark.ACCELERATION to 3))
        assertEquals(listOf("a", "b"), result.selected.map { it.slotId })
        assertEquals(180L, result.cost)
        assertEquals(800, result.score)
    }

    private fun plan(
        items: List<LabyrinthShopItemMatch>, balance: Long,
        stacks: Map<LabyrinthRelicMark, Int> = emptyMap(),
        refreshPrice: Long? = null, reserveRefresh: Boolean = false,
    ) = LabyrinthShopBudgetPlanner.plan(items, balance, stacks, refreshPrice, reserveRefresh)

    private fun assertRejected(action: () -> Unit) {
        try {
            action()
            fail("应拒绝不安全的预算输入")
        } catch (_: IllegalArgumentException) {
            // 预期安全拒绝。
        }
    }

    private fun relic(
        id: String, price: Long, mark: LabyrinthRelicMark = LabyrinthRelicMark.CRITICAL, bonus: Int = 1,
    ) = item(id, price, LabyrinthShopItemKind.RELIC).copy(relicMatch = LabyrinthRelicMatch(
        slotId = id, relicId = id, displayName = id, attribute = mark.label,
        attributeBonus = bonus.toString(), effect = null, confidence = 0.9,
        screenRect = rect, iconRect = rect, rivalMargin = 0.2,
    ))

    private fun role(id: String, price: Long, label: String = "攻击") =
        item(id, price, LabyrinthShopItemKind.ROLE_IMPRINT).copy(
            titleText = "${label}型职能随机印记", roleImprintLabel = "${label}型职能印记",
        )

    private fun item(id: String, price: Long, kind: LabyrinthShopItemKind) = LabyrinthShopItemMatch(
        slotId = id, screenRect = rect, buyButtonRect = rect, buyButtonScore = 0.9,
        buyButtonEnabledEvidence = 1.0, status = LabyrinthShopItemStatus.AVAILABLE, kind = kind,
        priceRead = LabyrinthShopReceiptRead(price.toString(), 1L, 2),
    )
}
