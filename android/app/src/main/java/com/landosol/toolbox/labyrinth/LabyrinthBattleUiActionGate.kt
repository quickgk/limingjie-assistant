package com.landosol.toolbox.labyrinth

/** Shared input feedback gate for team editing, roster scanning and equipment dialogs. */
internal class LabyrinthBattleUiActionGate {
    private var completedAtMillis = Long.MIN_VALUE
    private var feedbackKey: String? = null
    private var retryIntervalMillis = 0L

    @Synchronized
    fun permits(frameTimestampMillis: Long, nowMillis: Long, key: String?): Boolean {
        if (completedAtMillis == Long.MIN_VALUE) return true
        val interval = if (key != null && key == feedbackKey) {
            maxOf(BATTLE_TEAM_ACTION_INTERVAL_MILLIS, retryIntervalMillis)
        } else {
            BATTLE_TEAM_ACTION_INTERVAL_MILLIS
        }
        // Both the observation and the actual input must be after the completed gesture's settle window.
        return frameTimestampMillis >= completedAtMillis && nowMillis >= completedAtMillis &&
            frameTimestampMillis - completedAtMillis >= interval && nowMillis - completedAtMillis >= interval
    }

    @Synchronized
    fun observationIsAfterAction(frameTimestampMillis: Long): Boolean =
        completedAtMillis == Long.MIN_VALUE || frameTimestampMillis > completedAtMillis

    @Synchronized
    fun recordExecuted(key: String?, nowMillis: Long, retryMillis: Long = 0L) {
        completedAtMillis = nowMillis
        feedbackKey = key
        retryIntervalMillis = retryMillis
    }

    @Synchronized
    fun reset() {
        completedAtMillis = Long.MIN_VALUE
        feedbackKey = null
        retryIntervalMillis = 0L
    }
}

internal const val BATTLE_UI_BUTTON_RETRY_INTERVAL_MILLIS = 2_000L

/** Button identity excludes viewport revisions; character toggles retain their target-specific keys. */
internal fun labyrinthBattleUiFeedbackKey(step: LabyrinthBattleTeamExecutionStep): String? = when (step.kind) {
    LabyrinthBattleTeamExecutionKind.SELECT_FILTER -> "filter:${step.key.split(':').getOrNull(1) ?: step.label}"
    LabyrinthBattleTeamExecutionKind.RESET_BOSS_SWITCH_TEAM,
    LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM,
    LabyrinthBattleTeamExecutionKind.START_BATTLE,
    -> "${step.kind}:${step.label}"
    else -> null
}
