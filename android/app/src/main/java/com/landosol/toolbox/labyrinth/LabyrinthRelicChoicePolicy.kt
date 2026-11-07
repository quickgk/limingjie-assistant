package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import kotlinx.serialization.Serializable

@Serializable
enum class LabyrinthRelicMark(val label: String) {
    ACCELERATION("加速"),
    CRITICAL("会心"),
    DEFENSE("守备"),
    ENHANCEMENT("强化"),
    DEBUFF("弱体"),
    ;

    companion object {
        fun fromLabel(value: String?): LabyrinthRelicMark? = when (value?.trim()) {
            "暴击" -> CRITICAL
            "削弱", "崩弱" -> DEBUFF
            else -> entries.firstOrNull { it.label == value?.trim() }
        }
    }
}

enum class LabyrinthRelicChoicePhase {
    BUILD_BASELINE,
    REACH_ANY_FIFTEEN,
    PIVOT_TO_DEBUFF_FIFTEEN,
}

data class LabyrinthRelicChoicePolicyConfig(
    val baselineTarget: Int = 4,
    val capstoneTarget: Int = 15,
    val debuffPivotMinimum: Int = 12,
    val debuffPivotTolerance: Int = 2,
    val baselineLastArea: Int = 3,
    /**
     * User's preferred marks, strongest first. Empty keeps the built-in behaviour.
     *
     * This orders the marks the policy is otherwise indifferent about; it does not override the
     * parts that follow from the game's own tiers. An immediate jump to [capstoneTarget] still
     * wins, and a mark already at the cap is still never chosen, because both are about actual
     * payoff rather than taste.
     */
    val markPriority: List<LabyrinthRelicMark> = emptyList(),
) {
    init {
        require(baselineTarget > 0)
        require(capstoneTarget > baselineTarget)
        require(debuffPivotMinimum in (baselineTarget + 1)..capstoneTarget)
        require(debuffPivotTolerance >= 0)
        require(baselineLastArea in 0..5)
        require(markPriority.distinct().size == markPriority.size) {
            "遗物优先级不能重复"
        }
    }

    /** Bonus applied to a candidate of [mark]; 0 when the user expressed no preference. */
    fun priorityBonus(mark: LabyrinthRelicMark): Double {
        val rank = markPriority.indexOf(mark)
        if (rank < 0) return 0.0
        // Ranks are worth less than finishing a 15 tier and roughly on par with the focus bonus,
        // so a preference steers ties without overriding a clearly better stack.
        return (markPriority.size - rank) * PRIORITY_RANK_WEIGHT
    }

    private companion object {
        const val PRIORITY_RANK_WEIGHT = 1.2
    }
}

data class LabyrinthRelicChoiceDecision(
    val choice: LabyrinthRelicMatch,
    val phase: LabyrinthRelicChoicePhase,
    val stacksBefore: Map<LabyrinthRelicMark, Int>,
    val focusMark: LabyrinthRelicMark,
    val reason: String,
)

/** Stage-aware heuristic, not a simulation of individual relic effects or guaranteed optimal play. */
class LabyrinthRelicChoicePolicy(
    private val config: LabyrinthRelicChoicePolicyConfig = LabyrinthRelicChoicePolicyConfig(),
) {
    /** Explicit marks precede unconfigured marks, which retain the built-in stage-aware heuristic. */
    fun attributePriorityGroups(
        stacks: Map<LabyrinthRelicMark, Int>, currentArea: Int? = null, lockedFocus: LabyrinthRelicMark? = null,
    ): List<List<LabyrinthRelicMark>> {
        val normalized = LabyrinthRelicMark.entries.associateWith { stacks.getOrDefault(it, 0) }
        val (focus, pivot) = resolveFocus(normalized, lockedFocus)
        val early = currentArea != null && currentArea in 1..config.baselineLastArea
        val remaining = LabyrinthRelicMark.entries.filterNot { it in config.markPriority }
            .groupBy { mark -> candidateScore(mark, 1, normalized, early, focus, pivot) }
            .toSortedMap(compareByDescending { it }).values.map { it.toList() }
        return config.markPriority.map { listOf(it) } + remaining
    }

    private fun resolveFocus(stacks: Map<LabyrinthRelicMark, Int>, lockedFocus: LabyrinthRelicMark?): Pair<LabyrinthRelicMark, Boolean> {
        val unfinished = LabyrinthRelicMark.entries.filter { stacks.getValue(it) < config.capstoneTarget }
        val highest = unfinished.maxByOrNull { stacks.getValue(it) }
        val debuff = LabyrinthRelicMark.DEBUFF
        val pivot = stacks.getValue(debuff) >= config.debuffPivotMinimum &&
            (highest == null || stacks.getValue(debuff) + config.debuffPivotTolerance >= stacks.getValue(highest))
        val retained = lockedFocus?.takeIf { stacks.getValue(it) < config.capstoneTarget }
        return (if (pivot) debuff else retained ?: config.markPriority.firstOrNull { it in unfinished } ?: highest ?: debuff) to pivot
    }

    private fun candidateScore(mark: LabyrinthRelicMark, bonus: Int, stacks: Map<LabyrinthRelicMark, Int>,
        early: Boolean, focus: LabyrinthRelicMark, pivot: Boolean): Double {
        val before = stacks.getValue(mark)
        val useful = minOf(bonus, (config.capstoneTarget - before).coerceAtLeast(0))
        val deficit = if (mark in BASELINE_MARKS) (config.baselineTarget - before).coerceAtLeast(0) else 0
        return useful * 2.0 + minOf(bonus, deficit) * (if (early) 2.5 else 0.25) +
            (if (deficit > 0 && bonus >= deficit) if (early) 4.0 else 2.0 else 0.0) +
            (if (useful > 0) before.coerceAtMost(config.capstoneTarget) * 0.5 else 0.0) +
            (if (mark == focus && useful > 0) 3.0 else 0.0) +
            (if (mark == LabyrinthRelicMark.DEBUFF && pivot && useful > 0) 1.0 else 0.0) +
            (if (useful > 0) config.priorityBonus(mark) else 0.0)
    }

    fun choose(
        acquired: List<LabyrinthObservedRelic>,
        candidates: List<LabyrinthRelicMatch>,
        lockedFocus: LabyrinthRelicMark? = null,
        calibratedStacks: Map<LabyrinthRelicMark, Int> = emptyMap(),
        currentArea: Int? = null,
    ): LabyrinthRelicChoiceDecision? {
        val stacks = markStacks(acquired, calibratedStacks)
        val recognized = candidates.mapNotNull { match ->
            if (!match.recognized) return@mapNotNull null
            val mark = LabyrinthRelicMark.fromLabel(match.attribute) ?: return@mapNotNull null
            val bonus = match.attributeBonus?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            Candidate(match, mark, bonus)
        }
        if (recognized.isEmpty()) return null

        val deficits = BASELINE_MARKS.associateWith { mark ->
            (config.baselineTarget - stacks.getValue(mark)).coerceAtLeast(0)
        }
        val debuff = LabyrinthRelicMark.DEBUFF
        val (focus, debuffCanPivot) = resolveFocus(stacks, lockedFocus)

        val immediateCapstone = recognized
            .filter { candidate ->
                stacks.getValue(candidate.mark) < config.capstoneTarget &&
                    stacks.getValue(candidate.mark) + candidate.bonus >= config.capstoneTarget
            }
            .maxWithOrNull(
                compareBy<Candidate> { if (it.mark == debuff) 1 else 0 }
                    .thenBy { stacks.getValue(it.mark) }
                    .thenBy { it.bonus },
            )
        // Unknown area must not keep a late-run takeover trapped in the early baseline policy.
        val early = currentArea != null && currentArea in 1..config.baselineLastArea
        fun score(candidate: Candidate): Double =
            candidateScore(candidate.mark, candidate.bonus, stacks, early, focus, debuffCanPivot)
        val choice = immediateCapstone ?: recognized.maxWithOrNull(
            compareBy<Candidate> { score(it) }.thenBy { it.bonus },
        ) ?: return null
        // A missing focus candidate alone doesn't force a switch; an available but clearly
        // worse focus can be displaced. Focus is a small bonus, never a hard filter.
        val effectiveFocus = immediateCapstone?.mark ?: if (
            choice.mark != focus && recognized.any { it.mark == focus }
        ) choice.mark else focus
        val buildingBaseline = early && immediateCapstone == null && deficits.getOrDefault(choice.mark, 0) > 0
        val phase = if (buildingBaseline) {
            LabyrinthRelicChoicePhase.BUILD_BASELINE
        } else if (effectiveFocus == debuff && (debuffCanPivot || immediateCapstone?.mark == debuff)) {
            LabyrinthRelicChoicePhase.PIVOT_TO_DEBUFF_FIFTEEN
        } else {
            LabyrinthRelicChoicePhase.REACH_ANY_FIFTEEN
        }
        val after = stacks.getValue(choice.mark) + choice.bonus
        val reason = when {
            immediateCapstone != null ->
                "${choice.mark.label}可由${stacks.getValue(choice.mark)}层直接到达${config.capstoneTarget}层，优先取得高收益档位"
            buildingBaseline ->
                "区域$currentArea：补${config.baselineTarget}层仅作加分；${choice.mark.label}（${stacks.getValue(choice.mark)}→$after）"
            choice.mark == effectiveFocus ->
                "${if (early) "前期" else "后期/区域未知，降低补${config.baselineTarget}层权重"}；停止平均发展，" +
                    "按增量与档位收益主追${effectiveFocus.label}（${stacks.getValue(choice.mark)}→$after）"
            else ->
                "按增量与档位收益选择${choice.mark.label}+${choice.bonus}；本轮没有${focus.label}，不切换主追印记"
        }
        return LabyrinthRelicChoiceDecision(choice.match, phase, stacks, effectiveFocus, reason)
    }

    fun markStacks(
        acquired: List<LabyrinthObservedRelic>,
        calibratedStacks: Map<LabyrinthRelicMark, Int> = emptyMap(),
    ): Map<LabyrinthRelicMark, Int> {
        val totals = LabyrinthRelicMark.entries.associateWith { 0 }.toMutableMap()
        acquired.forEach { relic ->
            val mark = LabyrinthRelicMark.fromLabel(relic.attribute) ?: return@forEach
            val bonus = relic.attributeBonus.trim().toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
            totals[mark] = totals.getValue(mark) + bonus * relic.observationCount.coerceAtLeast(1)
        }
        calibratedStacks.forEach { (mark, stacks) ->
            if (stacks >= 0) totals[mark] = stacks
        }
        return totals
    }

    private companion object {
        val BASELINE_MARKS = setOf(LabyrinthRelicMark.ACCELERATION, LabyrinthRelicMark.CRITICAL, LabyrinthRelicMark.DEFENSE)
    }

    private data class Candidate(
        val match: LabyrinthRelicMatch,
        val mark: LabyrinthRelicMark,
        val bonus: Int,
    )
}
