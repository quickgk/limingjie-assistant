package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import kotlinx.serialization.Serializable

@Serializable
enum class LabyrinthImprintRole(val label: String, val preferredByDefault: Boolean) {
    ATTACK("攻击", true), BREAK("破防", true), ENHANCEMENT("强化", true),
    BUFF("增益", true), DEBUFF("减益", true),
    DISRUPTION("干扰", false), TANK("坦克", false), HEALING("治疗", false);

    companion object {
        fun fromLabel(label: String?): LabyrinthImprintRole? = when {
            label.orEmpty().contains("妨碍") -> DISRUPTION
            label.orEmpty().contains("掩护") -> TANK
            else -> entries.firstOrNull { label.orEmpty().contains(it.label) }
        }
    }
}

/** Explicit ranks precede the two built-in groups; unconfigured roles remain eligible. */
class LabyrinthRandomImprintPriority(private val configured: List<LabyrinthImprintRole> = emptyList()) {
    init { require(configured.distinct().size == configured.size) { "随机印记职能优先级不能重复" } }

    val groupCount: Int get() = configured.size + 2
    fun rank(item: LabyrinthShopItemMatch): Int {
        val role = LabyrinthImprintRole.fromLabel(item.roleImprintLabel)
        val explicit = configured.indexOf(role)
        return if (explicit >= 0) explicit else configured.size + if (role?.preferredByDefault == true) 0 else 1
    }

    fun counts(items: List<LabyrinthShopItemMatch>): List<Int> =
        List(groupCount) { group -> if (group == groupCount - 1) 0 else items.count { rank(it) == group } }

    val comparator: Comparator<LabyrinthShopItemMatch> get() =
        compareBy<LabyrinthShopItemMatch> { rank(it) }.thenBy { it.slotId }
}
