package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.*

/** Unit policy fixtures describe six known physical positions, not scanner evidence. */
internal fun completedPolicyStock(items: List<LabyrinthShopItemMatch>): List<LabyrinthShopItemMatch> =
    (items + (items.size until 6).map { index ->
        val rect = EntryPixelRect(350 + index % 3 * 578, 375 + index / 3 * 380, 290, 90)
        LabyrinthShopItemMatch("sold-position-$index", rect, rect, 1.0,
            status = LabyrinthShopItemStatus.DISABLED, categoryState = LabyrinthShopCategoryState.EXHAUSTED)
    }).mapIndexed { index, item -> item.copy(slotId = "shop_item_${index + 1}") }

internal fun LabyrinthShopPolicy.decideAfterFullScan(
    currentArea: Int?, items: List<LabyrinthShopItemMatch>, acquired: List<LabyrinthObservedRelic>,
    calibratedStacks: Map<LabyrinthRelicMark, Int> = emptyMap(), refreshAvailable: Boolean,
    lockedFocus: LabyrinthRelicMark? = null, relicPurchasesInCycle: Int = 0,
    refreshAvailabilityKnown: Boolean = true, refreshesUsedInVisit: Int = 0,
    verifiedBalance: Long? = null, refreshPrice: Long? = null,
    successfulRefreshesInVisit: Int = 0, refreshPurchaseBudget: Long? = null,
    transactionsSettled: Boolean = true,
): LabyrinthShopDecision {
    val stock = completedPolicyStock(items)
    val aliases = items.mapIndexed { index, item -> "shop_item_${index + 1}" to item.slotId }.toMap()
    fun alias(item: LabyrinthShopItemMatch) = item.copy(slotId = aliases[item.slotId] ?: item.slotId)
    fun alias(summary: LabyrinthShopBudgetSummary?) = summary?.copy(
        selectedSlots = summary.selectedSlots.map { aliases[it] ?: it })
    val decision = decide(currentArea, stock, acquired, calibratedStacks, refreshAvailable, lockedFocus,
        relicPurchasesInCycle, refreshAvailabilityKnown, refreshesUsedInVisit, verifiedBalance, refreshPrice,
        coverage = LabyrinthShopCoverage(0, 100, LabyrinthShopScanPhase.COMPLETE),
        successfulRefreshesInVisit = successfulRefreshesInVisit, refreshPurchaseBudget = refreshPurchaseBudget,
        transactionsSettled = transactionsSettled)
    // Preserve fixture names in assertions while the policy itself only sees physical slot IDs.
    return when (decision) {
        is LabyrinthShopDecision.BuyRelic -> decision.copy(item = alias(decision.item), budget = alias(decision.budget))
        is LabyrinthShopDecision.BuyRoleImprint -> decision.copy(item = alias(decision.item), budget = alias(decision.budget))
        is LabyrinthShopDecision.Refresh -> decision.copy(budget = alias(decision.budget))
        is LabyrinthShopDecision.Close -> decision.copy(budget = alias(decision.budget))
        else -> decision
    }
}
