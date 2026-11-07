package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.labyrinth.LabyrinthRandomImprintPriority
import com.landosol.toolbox.labyrinth.LabyrinthRelicChoicePolicy
import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopCategoryState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemText

/** 仅优化已识别的当前库存，不推测刷新后的商品。 */
internal object LabyrinthShopBudgetPlanner {
    data class Plan(
        val selected: List<LabyrinthShopItemMatch>, val cost: Long, val score: Int,
        val reserve: Long, val reason: String,
    )

    fun eligible(item: LabyrinthShopItemMatch): Boolean = when (item.kind) {
        LabyrinthShopItemKind.RELIC -> item.relicMatch?.recognized == true
        LabyrinthShopItemKind.ROLE_IMPRINT -> !item.choiceRoleImprint &&
            LabyrinthShopItemText.confirmedImprintChoice(item.titleText) == false && !item.roleImprintLabel.isNullOrBlank()
        else -> false
    }

    fun preferredRole(item: LabyrinthShopItemMatch): Boolean = LabyrinthRandomImprintPriority().rank(item) == 0

    fun plan(
        items: List<LabyrinthShopItemMatch>, balance: Long, stacks: Map<LabyrinthRelicMark, Int>,
        refreshPrice: Long?, reserveRefresh: Boolean,
        attributeGroups: List<List<LabyrinthRelicMark>> = LabyrinthRelicChoicePolicy().attributePriorityGroups(stacks),
        rolePriority: LabyrinthRandomImprintPriority = LabyrinthRandomImprintPriority(),
        refreshPurchaseBudget: Long? = null,
    ): Plan {
        require(balance >= 0) { "余额不能为负数" }
        require(refreshPrice == null || refreshPrice >= 0) { "刷新费用不能为负数" }
        require(refreshPurchaseBudget == null || refreshPurchaseBudget > 0) { "刷新购货预算必须为正数" }
        require(items.all { it.price == null || it.price!! >= 0 }) { "商品价格不能为负数" }
        require(stacks.values.all { it >= 0 }) { "已核实层数不能为负数" }
        require(attributeGroups.flatten().toSet() == LabyrinthRelicMark.entries.toSet() &&
            attributeGroups.flatten().size == LabyrinthRelicMark.entries.size) { "属性优先组必须无重复地覆盖全部属性" }
        val identified = items.filter { it.categoryState == LabyrinthShopCategoryState.READY && eligible(it) }
        require(identified.count { it.purchasable } <= 12) { "可见商品过多，拒绝无界组合搜索" }
        val known = identified.filter { it.price != null && it.price > 0 }
        val turnover = refreshPurchaseBudget ?: known.minOfOrNull { it.price!! }
        val envelope = if (reserveRefresh && refreshPrice != null && refreshPrice > 0 && turnover != null &&
            refreshPrice <= balance && turnover <= balance - refreshPrice
        ) refreshPrice + turnover else 0L
        val limit = balance - envelope
        val available = known.filter { it.purchasable && it.price!! <= limit }.sortedBy { it.slotId }
        data class Basket(
            val goods: List<LabyrinthShopItemMatch>, val cost: Long, val score: Int,
            val four: List<Int>, val cap: List<Int>, val roles: List<Int>,
        )
        fun compare(a: List<Int>, b: List<Int>): Int = a.zip(b).firstOrNull { it.first != it.second }
            ?.let { it.first.compareTo(it.second) } ?: 0
        var best = Basket(emptyList(), 0L, 0, List(attributeGroups.size) { 0 },
            List(attributeGroups.size * 2) { 0 }, List(rolePriority.groupCount) { 0 })
        // 档位收益不计入结算奖励分；同分先比较档位，再比较成本与随机印记职能。
        fun better(a: Basket, b: Basket): Boolean = when {
            a.score != b.score -> a.score > b.score
            a.four != b.four -> compare(a.four, b.four) > 0
            a.cap != b.cap -> compare(a.cap, b.cap) > 0
            a.cost != b.cost -> a.cost < b.cost
            else -> compare(a.roles, b.roles) > 0
        }
        for (mask in 1 until (1 shl available.size)) {
            val goods = available.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            var cost = 0L
            var affordable = true
            for (item in goods) {
                val price = item.price!!
                if (price > limit - cost) { affordable = false; break }
                cost += price
            }
            if (!affordable) continue
            val bonuses = goods.mapNotNull { item -> item.relicMatch?.let { match ->
                val mark = LabyrinthRelicMark.fromLabel(match.attribute) ?: return@let null
                mark to (match.attributeBonus?.toIntOrNull()?.toLong() ?: 0L)
            } }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            fun crosses(mark: LabyrinthRelicMark, tier: Int): Boolean {
                val before = stacks.getOrDefault(mark, 0).toLong()
                return before < tier && before + bonuses.getOrDefault(mark, 0L) >= tier
            }
            val candidate = Basket(
                goods, cost, goods.count { it.kind == LabyrinthShopItemKind.RELIC } * 400 +
                    goods.count { it.kind == LabyrinthShopItemKind.ROLE_IMPRINT } * 200,
                attributeGroups.map { group -> group.count { crosses(it, 4) } },
                listOf(15, 9).flatMap { tier -> attributeGroups.map { group -> group.count { crosses(it, tier) } } },
                rolePriority.counts(goods.filter { it.kind == LabyrinthShopItemKind.ROLE_IMPRINT }),
            )
            if (better(candidate, best)) best = candidate
        }
        return Plan(best.goods, best.cost, best.score, envelope,
            "当前已识别库存组合${best.score}分/${best.cost}币；" +
                if (envelope > 0) "预留${refreshPrice}刷新费及${turnover}购货预算，不预测新库存" else "本轮按实际余额换分")
    }
}
