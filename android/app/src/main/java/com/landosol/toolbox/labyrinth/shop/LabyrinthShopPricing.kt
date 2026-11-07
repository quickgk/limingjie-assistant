package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.gamedata.LabyrinthSummonUnitDocument
import com.landosol.toolbox.gamedata.LabyrinthSummonUnitResource
import com.landosol.toolbox.gamedata.LabyrinthSummonUnitResources
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemText

/** Account-specific facts are frozen at run start, not read from live settings while purchasing. */
data class LabyrinthShopPricingContext(
    val accountId: Long? = null,
    val enterId: Long? = null,
    val guildId: Int? = null,
    val node21Acquired: Boolean = false,
) {
    /** Guild 3 grants relic 40003 on entry. No individual relic-removal state is modelled. */
    val hasOath: Boolean get() = guildId == 3

    fun price(basePrice: Int?): Long? {
        if (basePrice == null || basePrice < 0) return null
        val percent = 100 - (if (node21Acquired) 25 else 0) - (if (hasOath) 10 else 0)
        // A zero catalog price means not listed, never a free purchase.
        return basePrice.toLong() * percent / 100
    }

    fun price(item: LabyrinthShopItemMatch, imprints: LabyrinthShopImprintCatalog): LabyrinthShopItemMatch {
        val base = when (item.kind) {
            LabyrinthShopItemKind.RELIC -> item.relicMatch?.takeIf { it.recognized }?.basePrice
            LabyrinthShopItemKind.ROLE_IMPRINT -> imprints.find(item)?.price
            LabyrinthShopItemKind.UNKNOWN -> null
        }
        return item.copy(price = price(base))
    }

    companion object {
        fun snapshot(accountId: Long?, enterId: Long?, guildId: Int?, node21AcquiredAccountIds: Set<Long>) =
            LabyrinthShopPricingContext(accountId, enterId, guildId,
                accountId != null && accountId in node21AcquiredAccountIds)
    }
}

object LabyrinthShopPrices {
    const val MAX_REFRESHES = 2
    const val REFRESH_RULE = "refresh_300_600"

    fun refreshPrice(refreshesUsed: Int): Long? =
        refreshesUsed.takeIf { it in 0 until MAX_REFRESHES }?.let { 300L * (1 + it) }
}

/** Lookup never broadens title recognition: only the already-safe role/type identity is indexed. */
class LabyrinthShopImprintCatalog(units: List<LabyrinthSummonUnitResource> = emptyList()) {
    private val byRoleAndChoice: Map<Pair<String, Boolean>, LabyrinthSummonUnitResource>

    init {
        if (units.isNotEmpty()) {
            val reasons = LabyrinthSummonUnitResources.validate(LabyrinthSummonUnitDocument(summonUnits = units))
            require(reasons.isEmpty()) { reasons.joinToString("；") }
        }
        val indexed = units.mapNotNull { unit ->
            val label = LabyrinthShopItemText.roleImprintLabel(unit.name) ?: return@mapNotNull null
            require(LabyrinthShopItemText.isChoiceRoleImprint(unit.name) == (unit.summonType == 1)) {
                "印记名称与选择类型不一致：${unit.id}"
            }
            (label to (unit.summonType == 1)) to unit
        }
        require(indexed.map { it.first }.distinct().size == indexed.size) { "商店印记职能与类型重复" }
        byRoleAndChoice = indexed.toMap()
    }

    fun listedPrices(): List<Int> = byRoleAndChoice.values.map { it.price }

    fun find(item: LabyrinthShopItemMatch): LabyrinthSummonUnitResource? {
        if (item.kind != LabyrinthShopItemKind.ROLE_IMPRINT || item.relicMatch?.relicId != null) return null
        val label = item.roleImprintLabel ?: return null
        if (LabyrinthShopItemText.roleImprintLabel(item.titleText) != label ||
            LabyrinthShopItemText.isChoiceRoleImprint(item.titleText) != item.choiceRoleImprint) return null
        return byRoleAndChoice[label to item.choiceRoleImprint]
    }
}
