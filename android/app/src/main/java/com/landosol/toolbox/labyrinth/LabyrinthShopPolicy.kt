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
    data class Scan(val direction: com.landosol.toolbox.labyrinth.vision.LabyrinthShopScroll) : LabyrinthShopDecision
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
     * Stricter per-area refill caps inside one shop visit. Every shop allows at most two
     * refills (300 then 600); area 3 defaults to one, because a second refill there can
     * leave too little money for the new row.
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
    private val randomImprintPriority: LabyrinthRandomImprintPriority = LabyrinthRandomImprintPriority(),
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
        refreshPrice: Long? = null,
        coverage: com.landosol.toolbox.labyrinth.vision.LabyrinthShopCoverage? = null,
        successfulRefreshesInVisit: Int = 0,
        refreshPurchaseBudget: Long? = null,
        transactionsSettled: Boolean = true,
    ): LabyrinthShopDecision {
        if (!buyRelics) return LabyrinthShopDecision.Close("策略已关闭商店购买，退出商店")
        if (!transactionsSettled) return LabyrinthShopDecision.Wait("已有购物操作等待确认或对账，禁止新购、刷新或正常关店")
        val refreshAllowed = refreshEnabled && currentArea != null && currentArea >= finalArea
        // The area budget is what keeps a shop from spending 300 coins on a refill that the
        // remaining balance cannot buy from. Running out of budget simply falls through to the
        // normal buy-imprint / close decisions below.
        val refreshBudget = minOf(com.landosol.toolbox.labyrinth.shop.LabyrinthShopPrices.MAX_REFRESHES,
            currentArea?.let { refreshLimitsByArea[it] } ?: Int.MAX_VALUE)
        val refreshPermitted = refreshAllowed && refreshesUsedInVisit in 0 until refreshBudget
        // Area 4 has an expensive stock pool, not a separate pricing multiplier. Keep the hard
        // saving rule: no purchase or refresh, so coins carry into area 5. Lowering the configured
        // refresh starting area must not give area 4 a spending license.
        if (currentArea != null && currentArea in noPurchaseAreas) {
            return LabyrinthShopDecision.Close("区域${currentArea}按策略不购买商店商品，直接退出")
        }
        if (coverage?.complete(items) != true) {
            return coverage?.scroll?.let { LabyrinthShopDecision.Scan(it) }
                ?: LabyrinthShopDecision.Wait("等待完整库存识别，暂不购买、刷新或正常关店")
        }
        if (items.any { it.status == LabyrinthShopItemStatus.UNKNOWN })
            return LabyrinthShopDecision.Wait("库存按钮状态尚未核实，不猜测禁用或可购买")
        if (budgetOptimizationEnabled) {
            if (refreshesUsedInVisit > successfulRefreshesInVisit) {
                return LabyrinthShopDecision.Wait("刷新额度已有占用但成功结算未核实，禁止继续消费或正常关店")
            }
            return budgetDecision(
                currentArea, items, acquired, calibratedStacks, lockedFocus,
                refreshPermitted, refreshAvailable, refreshAvailabilityKnown,
                verifiedBalance, refreshPrice, successfulRefreshesInVisit, refreshPurchaseBudget,
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
        val visibleStockExhausted = items.size == com.landosol.toolbox.labyrinth.vision.LabyrinthShopSlots.keys.size &&
            items.all { it.status == LabyrinthShopItemStatus.DISABLED ||
                (it.categoryState == LabyrinthShopCategoryState.READY && it.kind == LabyrinthShopItemKind.ROLE_IMPRINT) }
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
                        "当前六格已无可购买遗物，恢复为本轮遗物已售罄；按本次规则价格刷新下一轮遗物"
                    } else {
                        "本轮前三个遗物已买完，按本次规则价格刷新下一轮遗物"
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
        val noActionableRelicInRow = items.size == com.landosol.toolbox.labyrinth.vision.LabyrinthShopSlots.keys.size && items.all {
            it.status == LabyrinthShopItemStatus.DISABLED ||
                (it.purchasable && it.categoryState == LabyrinthShopCategoryState.READY &&
                    it.kind == LabyrinthShopItemKind.ROLE_IMPRINT)
        }
        if (refreshPermitted && noActionableRelicInRow) {
            if (!refreshAvailabilityKnown) {
                return LabyrinthShopDecision.Wait("当前商品行没有可购买遗物，等待刷新按钮稳定识别")
            }
            if (refreshAvailable) {
                return LabyrinthShopDecision.Refresh("当前商品行没有可购买遗物，按本次规则价格刷新下一轮遗物")
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
                currentArea != null && refreshesUsedInVisit >= refreshBudget ->
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
        currentArea: Int?, items: List<LabyrinthShopItemMatch>, acquired: List<LabyrinthObservedRelic>,
        calibratedStacks: Map<LabyrinthRelicMark, Int>, lockedFocus: LabyrinthRelicMark?,
        refreshPermitted: Boolean, refreshAvailable: Boolean, refreshAvailabilityKnown: Boolean,
        balance: Long?, refreshPrice: Long?, successfulRefreshes: Int, refreshPurchaseBudget: Long?,
    ): LabyrinthShopDecision {
        if (balance == null || balance < 0) {
            return LabyrinthShopDecision.Wait("等待当前商店余额两次独立识别复核，拒绝盲买、刷新或正常关店")
        }
        if (balance == 0L) return LabyrinthShopDecision.Close("已核实余额为零，结束商店")
        if (currentArea == null || currentArea !in 1..5 || successfulRefreshes < 0) {
            return LabyrinthShopDecision.Wait("商店区域或成功刷新次数未核实")
        }
        if (items.any { it.status == LabyrinthShopItemStatus.UNKNOWN ||
                it.purchasable && it.categoryState != LabyrinthShopCategoryState.READY }) {
            return LabyrinthShopDecision.Wait("库存身份或按钮状态未完整核实，等待有限识别重试")
        }
        val live = items.filter { it.purchasable }
        if (live.any { item ->
                !LabyrinthShopBudgetPlanner.eligible(item) &&
                    !(item.kind == LabyrinthShopItemKind.ROLE_IMPRINT && item.choiceRoleImprint &&
                        !item.roleImprintLabel.isNullOrBlank() &&
                        com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemText.confirmedImprintChoice(item.titleText) == true)
            }) return LabyrinthShopDecision.Wait("商品身份未核实，拒绝按不完整库存盲买")
        if (live.any { it.price == null || it.price!! < 0 }) {
            return LabyrinthShopDecision.Wait("可购买商品缺少安全的资源价格，拒绝猜价")
        }
        if (currentArea in setOf(3, 5) && live.any {
                it.kind == LabyrinthShopItemKind.RELIC && it.relicMatch?.rarity == null
            }) return LabyrinthShopDecision.Wait("遗物稀有度资源未核实，拒绝猜测购买阶段")
        if (refreshPermitted && !refreshAvailabilityKnown) {
            return LabyrinthShopDecision.Wait("刷新按钮状态未知，不能据此解禁商品或正常关店")
        }
        if (refreshPermitted && refreshAvailable && (refreshPrice == null || refreshPrice <= 0)) {
            return LabyrinthShopDecision.Wait("本次刷新缺少有效规则价格或次数已达上限")
        }
        val refreshFunds = com.landosol.toolbox.labyrinth.shop.LabyrinthShopRefreshFunds.sufficient(
            balance, refreshPrice, refreshPurchaseBudget)
        val refreshFundingUnknown = refreshPermitted && refreshAvailable &&
            (refreshPurchaseBudget == null || refreshPurchaseBudget <= 0)
        val canFundRefresh = refreshPermitted && refreshAvailable && refreshFunds
        val threeStarAllowed = currentArea == 5 && (successfulRefreshes >= 2 ||
            !refreshPermitted || !refreshAvailable || !refreshFunds && !refreshFundingUnknown)
        val allowed = live.filter { it.price!! > 0 && (it.kind != LabyrinthShopItemKind.RELIC ||
            it.relicMatch?.rarity != 3 || currentArea != 3 && (currentArea != 5 || threeStarAllowed)) }
        val affordableRelic = allowed.any { it.kind == LabyrinthShopItemKind.RELIC && it.price!! <= balance }
        val stockCanRefresh = !affordableRelic
        if (stockCanRefresh && refreshFundingUnknown) {
            return LabyrinthShopDecision.Wait("刷新后最低必要购货预算无法可靠确定，拒绝空刷或正常关店")
        }
        val stacks = relicChoicePolicy.markStacks(acquired, calibratedStacks)
        val attributeGroups = relicChoicePolicy.attributePriorityGroups(stacks, currentArea, lockedFocus)
        fun plan(reserve: Boolean) = LabyrinthShopBudgetPlanner.plan(
            allowed, balance, stacks, refreshPrice, reserve, attributeGroups, randomImprintPriority,
            refreshPurchaseBudget = refreshPurchaseBudget?.takeIf { it > 0 },
        )
        var basket = try { plan(canFundRefresh) } catch (error: IllegalArgumentException) {
            return LabyrinthShopDecision.Wait("预算输入不安全：${error.message}")
        }
        fun summary() = LabyrinthShopBudgetSummary(balance, basket.cost, basket.score, basket.reserve,
            basket.selected.map { it.slotId })
        if (canFundRefresh && stockCanRefresh) {
            return LabyrinthShopDecision.Refresh(
                "本轮已无合规买得起的遗物，按${refreshPrice}币刷新，保留${refreshPurchaseBudget}币购货能力", summary())
        }
        // 预留挤空当前组合且尚不能刷新时，释放预留购买当前可承担商品，不把空篮当购物完成。
        if (basket.selected.isEmpty() && allowed.any { LabyrinthShopBudgetPlanner.eligible(it) && it.price!! <= balance }) {
            basket = plan(false)
        }
        val relics = basket.selected.filter { it.kind == LabyrinthShopItemKind.RELIC }
        if (relics.isNotEmpty()) {
            val decision = relicChoicePolicy.choose(acquired, relics.mapNotNull { it.relicMatch }, lockedFocus,
                calibratedStacks, currentArea)
                ?: return LabyrinthShopDecision.Wait("已选预算组合缺少可确认遗物属性，拒绝盲买")
            val item = relics.first { it.relicMatch?.relicId == decision.choice.relicId }
            return LabyrinthShopDecision.BuyRelic(item, decision.copy(reason = "${basket.reason}；${decision.reason}"), summary())
        }
        val role = basket.selected.filter { it.kind == LabyrinthShopItemKind.ROLE_IMPRINT }
            .minWithOrNull(randomImprintPriority.comparator)
        if (role != null) return LabyrinthShopDecision.BuyRoleImprint(
            role, role.roleImprintLabel!!, "${basket.reason}；购买预算内随机职能印记", summary())
        // 选择印记不参加组合搜索；没有可承担遗物/随机印记且不能有效刷新时按实际余额兜底。
        val choice = allowed.filter { it.kind == LabyrinthShopItemKind.ROLE_IMPRINT && it.choiceRoleImprint &&
            it.price!! <= balance }.minByOrNull { it.slotId }
        if (choice != null && !canFundRefresh) return LabyrinthShopDecision.BuyRoleImprint(
            choice, choice.roleImprintLabel!!, "无法有效刷新，购买核实价格内的选择印记兜底",
            summary().copy(basketCost = choice.price!!, reserve = 0, selectedSlots = listOf(choice.slotId)))
        return LabyrinthShopDecision.Close("已确认没有合规且买得起的商品，也不能有效刷新；${basket.reason}", summary())
    }
}
