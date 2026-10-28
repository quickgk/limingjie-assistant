package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopCategoryState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch

/** 仅优化已识别的当前库存，不推测刷新后的商品。 */
internal object LabyrinthShopBudgetPlanner {
    data class Plan(
        val selected: List<LabyrinthShopItemMatch>,
        val cost: Long,
        val score: Int,
        val reserve: Long,
        val reason: String,
    )

    fun eligible(item: LabyrinthShopItemMatch): Boolean = when (item.kind) {
        LabyrinthShopItemKind.RELIC -> item.relicMatch?.recognized == true
        LabyrinthShopItemKind.ROLE_IMPRINT -> !item.choiceRoleImprint &&
            item.titleText.orEmpty().contains("随机") && !item.roleImprintLabel.isNullOrBlank()
        else -> false
    }

    fun preferredRole(item: LabyrinthShopItemMatch): Boolean =
        listOf("攻击", "破防", "强化", "增益", "减益").any(item.roleImprintLabel.orEmpty()::contains) &&
            listOf("干扰", "妨碍", "坦克", "掩护", "治疗").none(item.roleImprintLabel.orEmpty()::contains)

    fun plan(
        items: List<LabyrinthShopItemMatch>,
        balance: Long,
        stacks: Map<LabyrinthRelicMark, Int>,
        refreshPrice: Long?,
        reserveRefresh: Boolean,
    ): Plan {
        require(balance >= 0) { "余额不能为负数" }
        require(refreshPrice == null || refreshPrice >= 0) { "刷新费用不能为负数" }
        require(items.all { it.verifiedPrice == null || it.verifiedPrice!! >= 0 }) { "商品价格不能为负数" }
        require(stacks.values.all { it >= 0 }) { "已核实层数不能为负数" }
        val identified = items.filter { it.categoryState == LabyrinthShopCategoryState.READY && eligible(it) }
        require(identified.count { it.purchasable } <= 12) { "可见商品过多，拒绝无界组合搜索" }
        val known = identified.filter { it.verifiedPrice != null }
        val cheapest = known.minOfOrNull { it.verifiedPrice!! }
        // 最廉已识别商品只定义周转金额；费用加一件商品不可承担时释放预留。
        val envelope = if (reserveRefresh && refreshPrice != null && refreshPrice > 0 && cheapest != null &&
            refreshPrice <= balance && cheapest <= balance - refreshPrice
        ) refreshPrice + cheapest else 0L
        val limit = balance - envelope
        val available = known.filter { it.purchasable && it.verifiedPrice!! <= limit }.sortedBy { it.slotId }
        val priority = listOf(
            LabyrinthRelicMark.ENHANCEMENT, LabyrinthRelicMark.ACCELERATION,
            LabyrinthRelicMark.CRITICAL, LabyrinthRelicMark.DEFENSE, LabyrinthRelicMark.DEBUFF,
        )
        data class Basket(
            val goods: List<LabyrinthShopItemMatch>, val cost: Long, val score: Int,
            val four: Int, val cap: Int, val roles: Int,
        )
        var best = Basket(emptyList(), 0L, 0, 0, 0, 0)
        fun better(a: Basket, b: Basket): Boolean = when {
            a.score != b.score -> a.score > b.score
            a.four != b.four -> a.four > b.four
            a.cap != b.cap -> a.cap > b.cap
            a.cost != b.cost -> a.cost < b.cost
            else -> a.roles > b.roles
        }
        for (mask in 1 until (1 shl available.size)) {
            val goods = available.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            var cost = 0L
            var affordable = true
            for (item in goods) {
                val price = item.verifiedPrice!!
                // 在相加前比较剩余额度，Long.MAX_VALUE 附近的组合也不能回绕。
                if (price > limit - cost) {
                    affordable = false
                    break
                }
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
                goods, cost, goods.map { if (it.kind == LabyrinthShopItemKind.RELIC) 400 else 200 }.sum(),
                priority.mapIndexed { i, mark -> if (crosses(mark, 4)) 1 shl (4 - i) else 0 }.sum(),
                priority.map { (if (crosses(it, 15)) 10 else 0) + (if (crosses(it, 9)) 1 else 0) }.sum(),
                goods.count { preferredRole(it) },
            )
            if (better(candidate, best)) best = candidate
        }
        return Plan(
            best.goods, best.cost, best.score, envelope,
            "当前已识别库存组合${best.score}分/${best.cost}币；" +
                if (envelope > 0) "预留${refreshPrice}刷新费及${cheapest}购货周转，不预测新库存" else "本轮按实际余额换分",
        )
    }
}
