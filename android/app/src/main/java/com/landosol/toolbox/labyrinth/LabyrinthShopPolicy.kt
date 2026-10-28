package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.shop.LabyrinthShopBudgetPlanner
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopCategoryState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus

data class LabyrinthShopBudgetSummary(
    val balance: Long,
    val basketCost: Long,
    val directScore: Int,
    val reserve: Long,
    val selectedSlots: List<String>,
)

sealed interface LabyrinthShopDecision {
    val budget: LabyrinthShopBudgetSummary? get() = null

    data class BuyRelic(
        val item: LabyrinthShopItemMatch,
        val relicDecision: LabyrinthRelicChoiceDecision,
        override val budget: LabyrinthShopBudgetSummary? = null,
    ) : LabyrinthShopDecision

    data class BuyRoleImprint(
        val item: LabyrinthShopItemMatch,
        val label: String,
        val reason: String,
        override val budget: LabyrinthShopBudgetSummary? = null,
    ) : LabyrinthShopDecision {
        /** 选择印记打开角色选择页；随机印记直接授予角色。 */
        val opensRolePicker: Boolean get() = item.choiceRoleImprint
    }

    data class Refresh(val reason: String, override val budget: LabyrinthShopBudgetSummary? = null) : LabyrinthShopDecision
    data class Close(val reason: String, override val budget: LabyrinthShopBudgetSummary? = null) : LabyrinthShopDecision
    data class Wait(val reason: String) : LabyrinthShopDecision
}

/**
 * Shop policy for route execution.
 *
 * Rules requested by the live Dry Run:
 * - relics always outrank role-imprint goods;
 * - area 4 never buys and never refreshes: it is a hard saving stop whose coins carry into
 *   area 5, whatever the configured refresh starting area happens to be;
 * - in every other area refresh follows the configured starting area and enable switch;
 * - completed relic cycles, or a fully observed row with no actionable relic, can refresh, but
 *   each shop visit is capped by [refreshLimitsByArea] so a refill that the remaining coins cannot
 *   buy from is not paid for twice by mistake;
 * - when no relic can still be bought and another relic cycle cannot be refreshed, a safely
 *   identified purchasable role imprint may consume the remaining coins and enter role selection;
 * - every live good passes bounded title classification before purchase; unresolved goods are
 *   skipped after three crop variants and never count as purchased relic stock.
 *
 * The visible row is evaluated again after every completed purchase. The game compacts later
 * goods into the open slot, so the next decision intentionally does not remember a physical slot.
 */
class LabyrinthShopPolicy(
    private val relicChoicePolicy: LabyrinthRelicChoicePolicy = LabyrinthRelicChoicePolicy(),
    private val finalArea: Int = 5,
    private val noPurchaseAreas: Set<Int> = setOf(4),
    private val relicsPerStockCycle: Int = 3,
    private val ambiguousRelicLikeConfidence: Double = 0.45,
    private val buyRelics: Boolean = true,
    private val refreshEnabled: Boolean = true,
    /**
     * Per-area cap on 300-coin refills inside one shop visit. Area 3 defaults to a single refill:
     * a second refill there routinely returns a row the current balance cannot buy, so it only
     * spends coins. Areas absent from this map keep the unlimited behaviour.
     */
    private val refreshLimitsByArea: Map<Int, Int> = mapOf(3 to 1),
    /**
     * 预算组合分支开关。
     *
     * 默认 false 只是为了让未接购物模块的旧调用与回放测试继续走原分支；生产装配
     * （LandosolToolboxApplication 注入 shoppingModule）恒为 true，没有面向用户的开关。
     * 也就是说：这里不是灰度，而是「旧分支仅测试保留」。
     */
    private val budgetOptimizationEnabled: Boolean = false,
) {
    fun decide(
        currentArea: Int?,
        items: List<LabyrinthShopItemMatch>,
        acquired: List<LabyrinthObservedRelic>,
        calibratedStacks: Map<LabyrinthRelicMark, Int> = emptyMap(),
        refreshAvailable: Boolean,
        lockedFocus: LabyrinthRelicMark? = null,
        relicPurchasesInCycle: Int = 0,
        refreshAvailabilityKnown: Boolean = true,
        refreshesUsedInVisit: Int = 0,
        verifiedBalance: Long? = null,
        verifiedRefreshPrice: Long? = null,
    ): LabyrinthShopDecision {
        if (!buyRelics) return LabyrinthShopDecision.Close("策略已关闭商店购买，退出商店")
        val refreshAllowed = refreshEnabled && currentArea != null && currentArea >= finalArea
        // The area budget is what keeps a shop from spending 300 coins on a refill that the
        // remaining balance cannot buy from. Running out of budget simply falls through to the
        // normal buy-imprint / close decisions below.
        val refreshBudget = currentArea?.let { refreshLimitsByArea[it] }
        val refreshPermitted = refreshAllowed && (refreshBudget == null || refreshesUsedInVisit < refreshBudget)
        // Area 4 is a hard saving stop: no purchase and no 300-coin refresh, so the coins survive
        // into area 5. This is independent of the configured refresh starting area -- lowering
        // that to 3 must affect areas 3 and 5 only, never hand area 4 a spending license.
        if (currentArea != null && currentArea in noPurchaseAreas) {
            return LabyrinthShopDecision.Close("区域${currentArea}按策略不购买商店商品，直接退出")
        }
        if (budgetOptimizationEnabled) {
            return budgetDecision(
                currentArea, items, acquired, calibratedStacks, lockedFocus,
                refreshAllowed, refreshPermitted, refreshAvailable, refreshAvailabilityKnown,
                relicPurchasesInCycle, verifiedBalance, verifiedRefreshPrice,
            )
        }

        // Exact role identity is required before spending coins on an imprint. A coarse
        // ROLE_IMPRINT classification is still useful because it proves the slot is not a relic
        // and can recover an exhausted stock cycle after an app restart/reinstall.
        // Prefer the random imprint over the choice imprint of the same role class. Both grant a
        // character of that class, but the choice variant costs well over twice as much (2026-09-22
        // shop: 随机印记 780 against 选择印记 1,820) and it opens a full-roster picker the run then
        // has to answer. Cheaper and fewer moving parts for the same reward, so it goes first;
        // within one variant the leftmost slot still wins, keeping the pick deterministic.
        val purchasableRoleImprint = items
            .filter { item ->
                item.categoryState == LabyrinthShopCategoryState.READY &&
                    item.purchasable && item.kind == LabyrinthShopItemKind.ROLE_IMPRINT &&
                    !item.roleImprintLabel.isNullOrBlank()
            }
            .minWithOrNull(compareBy({ it.choiceRoleImprint }, { it.slotId }))

        // The game exposes exactly three relic goods per stock cycle. In a continuous session the
        // counter below reaches 3 from purchase-complete commits. If the app is restarted while
        // still inside the shop, that volatile counter is lost; when all three active goods have
        // already compacted to role-imprints, the relic stock is nevertheless unambiguously
        // exhausted and the cycle can be recovered without re-identifying a nonexistent relic.
        val visibleStockExhausted = items.size == relicsPerStockCycle &&
            items.all { it.categoryState == LabyrinthShopCategoryState.READY && it.kind == LabyrinthShopItemKind.ROLE_IMPRINT }
        val effectiveRelicPurchasesInCycle = if (visibleStockExhausted) {
            maxOf(relicPurchasesInCycle, relicsPerStockCycle)
        } else {
            relicPurchasesInCycle
        }

        // A committed cycle is sufficient even when the refill titles are still being read.
        if (effectiveRelicPurchasesInCycle >= relicsPerStockCycle) {
            if (refreshPermitted && !refreshAvailabilityKnown) {
                return LabyrinthShopDecision.Wait("遗物已买完，等待刷新按钮稳定识别")
            }
            if (refreshPermitted && refreshAvailable) {
                return LabyrinthShopDecision.Refresh(
                    if (visibleStockExhausted && relicPurchasesInCycle < relicsPerStockCycle) {
                        "当前三个商品位均为职能印记，恢复为本轮三个遗物已售罄；按策略花300刷新下一轮遗物"
                    } else {
                        "本轮前三个遗物已买完，按策略花300刷新下一轮遗物"
                    },
                )
            }
            if (purchasableRoleImprint != null) {
                return LabyrinthShopDecision.BuyRoleImprint(
                    item = purchasableRoleImprint,
                    label = purchasableRoleImprint.roleImprintLabel ?: "职能印记",
                    reason = "本轮三个遗物已买完且无法继续刷新，用剩余金币购买可确认印记",
                )
            }
            if (items.any { it.purchasable && it.categoryState == LabyrinthShopCategoryState.READING }) {
                return LabyrinthShopDecision.Wait("遗物已买完，正在有限重试确认剩余商品类别")
            }
            return LabyrinthShopDecision.Close(
                if ((currentArea ?: 0) >= finalArea) {
                    "本轮前三个遗物已买完且无法刷新，也没有可购买印记，结束商店"
                } else {
                    "区域${currentArea ?: "?"}本轮前三个遗物已买完，没有可购买印记，结束商店"
                },
            )
        }
        val purchasable = items.filter { it.purchasable && it.categoryState == LabyrinthShopCategoryState.READY }
        val recognizedRelicItems = purchasable.filter {
            it.kind != LabyrinthShopItemKind.ROLE_IMPRINT && it.relicMatch?.recognized == true
        }
        if (recognizedRelicItems.isNotEmpty()) {
            val candidates = recognizedRelicItems.mapNotNull(LabyrinthShopItemMatch::relicMatch)
            val relicDecision = relicChoicePolicy.choose(
                acquired = acquired,
                candidates = candidates,
                calibratedStacks = calibratedStacks,
                currentArea = currentArea,
                lockedFocus = lockedFocus,
            )
            if (relicDecision != null) {
                val item = recognizedRelicItems.first { it.relicMatch?.relicId == relicDecision.choice.relicId }
                return LabyrinthShopDecision.BuyRelic(item, relicDecision)
            }
            return LabyrinthShopDecision.Wait("已识别到遗物，但属性/层数元数据不足，拒绝盲买")
        }

        if (refreshAllowed && items.any { it.status == LabyrinthShopItemStatus.UNKNOWN }) {
            return LabyrinthShopDecision.Wait("商店商品按钮尚未完整识别，等待稳定后再决定刷新或退出")
        }

        val reading = items.firstOrNull { it.purchasable && it.categoryState == LabyrinthShopCategoryState.READING }
        if (reading != null) {
            return LabyrinthShopDecision.Wait("${reading.slotId}正在确认商品类别（${reading.categoryAttempt}/3轮）")
        }
        val ambiguous = purchasable.firstOrNull { item ->
            if (item.kind == LabyrinthShopItemKind.ROLE_IMPRINT) return@firstOrNull false
            val relic = item.relicMatch ?: return@firstOrNull false
            !relic.recognized && relic.confidence >= ambiguousRelicLikeConfidence
        }
        if (ambiguous != null) {
            val relic = ambiguous.relicMatch
            return LabyrinthShopDecision.Wait(
                "${ambiguous.slotId}疑似遗物(${"%.2f".format(relic?.confidence ?: 0.0)})但身份未过安全线",
            )
        }

        // Purchase counts can be lost on restart, and sold-out/disabled titles may never pass
        // OCR. Button state independently proves that those goods cannot be bought. Require the
        // complete row: missing controls and unresolved affordable goods do not prove depletion.
        val noActionableRelicInRow = items.size == relicsPerStockCycle && items.all {
            it.status == LabyrinthShopItemStatus.DISABLED ||
                (it.purchasable && it.categoryState == LabyrinthShopCategoryState.READY &&
                    it.kind == LabyrinthShopItemKind.ROLE_IMPRINT)
        }
        if (refreshPermitted && noActionableRelicInRow) {
            if (!refreshAvailabilityKnown) {
                return LabyrinthShopDecision.Wait("当前商品行没有可购买遗物，等待刷新按钮稳定识别")
            }
            if (refreshAvailable) {
                return LabyrinthShopDecision.Refresh("当前商品行没有可购买遗物，按策略花300刷新下一轮遗物")
            }
        }

        if (purchasableRoleImprint != null) {
            return LabyrinthShopDecision.BuyRoleImprint(
                item = purchasableRoleImprint,
                label = purchasableRoleImprint.roleImprintLabel ?: "职能印记",
                reason = "当前没有可继续购买的遗物，购买可确认印记作为剩余金币兜底",
            )
        }

        return LabyrinthShopDecision.Close(
            when {
                items.any { it.categoryState == LabyrinthShopCategoryState.EXHAUSTED } ->
                    "未确认商品已完成三轮识别并跳过；没有可确认购买项，退出商店"
                !refreshEnabled -> "策略禁止刷新，当前无可确认遗物或可购买印记，结束商店"
                currentArea != null && refreshBudget != null && refreshesUsedInVisit >= refreshBudget ->
                    "区域${currentArea}本店刷新额度已用完（上限${refreshBudget}次），结束商店"
                (currentArea ?: 0) >= finalArea && refreshAvailable ->
                    "当前商品行尚未确认已无可购买遗物，不提前刷新；且没有可购买印记，结束商店"
                (currentArea ?: 0) >= finalArea ->
                    "当前无可确认遗物、可购买印记或可用刷新，结束商店"
                else -> "区域${currentArea ?: "?"}当前无可确认遗物或可购买印记，结束商店"
            },
        )
    }

    private fun budgetDecision(
        currentArea: Int?,
        items: List<LabyrinthShopItemMatch>,
        acquired: List<LabyrinthObservedRelic>,
        calibratedStacks: Map<LabyrinthRelicMark, Int>,
        lockedFocus: LabyrinthRelicMark?,
        refreshAllowed: Boolean,
        refreshPermitted: Boolean,
        refreshAvailable: Boolean,
        refreshAvailabilityKnown: Boolean,
        relicPurchasesInCycle: Int,
        balance: Long?,
        refreshPrice: Long?,
    ): LabyrinthShopDecision {
        val visibleStockExhausted = items.size == relicsPerStockCycle && items.all {
            it.categoryState == LabyrinthShopCategoryState.READY && it.kind == LabyrinthShopItemKind.ROLE_IMPRINT
        }
        val cycleCompleted = relicPurchasesInCycle >= relicsPerStockCycle || visibleStockExhausted
        val noActionableRelicInRow = items.size == relicsPerStockCycle && items.all {
            it.status == LabyrinthShopItemStatus.DISABLED ||
                (it.purchasable && it.categoryState == LabyrinthShopCategoryState.READY &&
                    it.kind == LabyrinthShopItemKind.ROLE_IMPRINT)
        }
        val stockCanRefresh = cycleCompleted || noActionableRelicInRow
        if (refreshAllowed && items.any { it.status == LabyrinthShopItemStatus.UNKNOWN }) {
            return LabyrinthShopDecision.Wait("商店商品按钮尚未完整识别，等待稳定后再计算预算")
        }
        val reading = items.firstOrNull { it.purchasable && it.categoryState == LabyrinthShopCategoryState.READING }
        if (reading != null) {
            return LabyrinthShopDecision.Wait("${reading.slotId}正在确认商品类别（${reading.categoryAttempt}/3轮）")
        }
        // 禁用商品与识别已耗尽的未知商品仍按原安全规则跳过，不阻塞整店。
        val live = items.filter { it.purchasable && it.categoryState == LabyrinthShopCategoryState.READY }
        val unidentified = live.firstOrNull { item ->
            !LabyrinthShopBudgetPlanner.eligible(item) &&
                !(item.kind == LabyrinthShopItemKind.ROLE_IMPRINT && item.choiceRoleImprint &&
                    !item.roleImprintLabel.isNullOrBlank())
        }
        if (unidentified != null) {
            return LabyrinthShopDecision.Wait("${unidentified.slotId}商品身份未核实，拒绝按不完整库存盲买")
        }
        if (refreshPermitted && !refreshAvailabilityKnown && (live.isNotEmpty() || stockCanRefresh)) {
            return LabyrinthShopDecision.Wait("等待刷新按钮稳定识别后计算实际预算")
        }
        if (live.isEmpty() && !(refreshPermitted && refreshAvailable && stockCanRefresh)) {
            return LabyrinthShopDecision.Close("没有可确认购买项或符合策略的刷新，结束商店")
        }
        if (balance == null || balance < 0) {
            return LabyrinthShopDecision.Wait("等待当前余额两次独立识别复核，拒绝盲买或刷新")
        }
        if (live.any { it.verifiedPrice == null || it.verifiedPrice!! < 0 }) {
            return LabyrinthShopDecision.Wait("等待可购买商品价格两次独立识别复核，不猜价格或折扣")
        }
        val canRefresh = refreshPermitted && refreshAvailable
        if (canRefresh && (refreshPrice == null || refreshPrice < 0)) {
            return LabyrinthShopDecision.Wait("等待本次刷新实际费用复核，不按固定费用预支")
        }
        val plan = try {
            LabyrinthShopBudgetPlanner.plan(
                items, balance, relicChoicePolicy.markStacks(acquired, calibratedStacks), refreshPrice, canRefresh,
            )
        } catch (error: IllegalArgumentException) {
            return LabyrinthShopDecision.Wait("预算输入不安全：${error.message}")
        }
        val summary = LabyrinthShopBudgetSummary(
            balance, plan.cost, plan.score, plan.reserve, plan.selected.map { it.slotId },
        )
        // 有已识别商品周转依据时，释放预留不能被售罄刷新优先级绕过；
        // 全禁用且价格未知的行仍保留原售罄恢复规则，仅核实实际刷新费用。
        val refreshEnvelopeAvailable = plan.reserve > 0 || items.none {
            it.categoryState == LabyrinthShopCategoryState.READY &&
                LabyrinthShopBudgetPlanner.eligible(it) && it.verifiedPrice != null
        }
        if (canRefresh && stockCanRefresh && refreshPrice!! <= balance && refreshEnvelopeAvailable) {
            return LabyrinthShopDecision.Refresh(
                "当前遗物轮已耗尽，按策略使用核实费用${refreshPrice}币刷新；${plan.reason}", summary,
            )
        }
        val relics = plan.selected.filter { it.kind == LabyrinthShopItemKind.RELIC }
        if (relics.isNotEmpty()) {
            val decision = relicChoicePolicy.choose(
                acquired, relics.mapNotNull { it.relicMatch }, lockedFocus,
                calibratedStacks, currentArea,
            ) ?: return LabyrinthShopDecision.Wait("已选预算组合缺少可确认遗物属性，拒绝盲买")
            val item = relics.first { it.relicMatch?.relicId == decision.choice.relicId }
            return LabyrinthShopDecision.BuyRelic(
                item, decision.copy(reason = "${plan.reason}；${decision.reason}"), summary,
            )
        }
        val role = plan.selected.sortedWith(
            compareByDescending<LabyrinthShopItemMatch> { LabyrinthShopBudgetPlanner.preferredRole(it) }
                .thenBy { it.slotId },
        ).firstOrNull()
        if (role != null) {
            return LabyrinthShopDecision.BuyRoleImprint(
                role, role.roleImprintLabel!!, "${plan.reason}；购买预算内随机职能印记", summary,
            )
        }
        // 选择印记不参加源算法组合搜索；仅在无可买遗物/随机印记且不能刷新时保留原兜底。
        val affordableEligible = live.any {
            LabyrinthShopBudgetPlanner.eligible(it) && it.verifiedPrice!! <= balance
        }
        val choice = live.filter {
            it.kind == LabyrinthShopItemKind.ROLE_IMPRINT && it.choiceRoleImprint &&
                it.verifiedPrice!! <= balance - plan.reserve
        }.minByOrNull { it.slotId }
        if (!affordableEligible && choice != null) {
            return LabyrinthShopDecision.BuyRoleImprint(
                choice, choice.roleImprintLabel!!,
                "遗物/随机印记无可买且无法按策略刷新，按核实价格${choice.verifiedPrice}币购买选择印记兜底",
                summary.copy(basketCost = choice.verifiedPrice!!, selectedSlots = listOf(choice.slotId)),
            )
        }
        return LabyrinthShopDecision.Close("当前已核实库存没有预算内购买项或符合策略的刷新；${plan.reason}", summary)
    }
}
