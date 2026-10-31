package com.landosol.toolbox.labyrinth.vision

/**
 * Per visible title, at most three crop variants. A variant is retired only once its OCR read has
 * actually completed (or a long backstop elapses), never merely because time passed.
 *
 * OCR runs on one shared serial queue at 2-5 s per read, so a fixed 3 s-per-round wall clock
 * retired titles whose only read was still in flight: 2026-09-21 bundle 134750 shows all three
 * shop titles stuck at READING with no text, yet ticked round 1 -> 2 -> 3 on a 3 s cadence and
 * were marked EXHAUSTED, closing the shop after just one or two purchases.
 */
internal class LabyrinthShopCategoryResolution {
    private data class State(val fingerprint: Long, var attempt: Int = 0, var variantStartedAt: Long)
    private val slots = mutableMapOf<String, State>()

    fun clear() = slots.clear()

    /** The current crop-variant index (0..[MAX_ATTEMPTS]) for this slot. Does not advance itself. */
    fun attempt(slot: String, fingerprint: Long, now: Long): Int {
        val state = slots[slot]?.takeIf { it.fingerprint == fingerprint }
            ?: State(fingerprint, variantStartedAt = now).also { slots[slot] = it }
        return state.attempt
    }

    /**
     * Retire the current variant and move to the next only when its OCR actually finished
     * ([completed]), or when the OCR engine itself has gone silent for
     * [ENGINE_SILENT_BACKSTOP_MILLIS].
     *
     * [engineLastCompletedAt] is the last time *any* slot got an answer. Timing this slot alone
     * would be wrong: reads share one serial queue, so a title queued behind the other two waits
     * for their reads even on a healthy device. Bundle 134750 measured completion-to-completion
     * gaps with a p90 of 5.4 s, which puts the third slot's turn ~16 s out; a per-variant clock
     * set below that retires titles whose only read has not run yet, which is the very bug this
     * class was fixed for. Engine liveness has no such queue component.
     */
    fun noteVariantOutcome(
        slot: String,
        fingerprint: Long,
        variant: Int,
        completed: Boolean,
        engineLastCompletedAt: Long,
        now: Long,
    ) {
        val state = slots[slot]?.takeIf { it.fingerprint == fingerprint } ?: return
        if (variant != state.attempt || state.attempt >= MAX_ATTEMPTS) return
        val lastProgress = maxOf(state.variantStartedAt, engineLastCompletedAt)
        if (completed || now - lastProgress >= ENGINE_SILENT_BACKSTOP_MILLIS) {
            state.attempt++
            state.variantStartedAt = now
        }
    }

    fun resolve(item: LabyrinthShopItemMatch, text: String?, evidenceId: Long?, attempt: Int): LabyrinthShopItemMatch {
        val imprint = LabyrinthShopItemText.looksLikeRoleImprint(text)
        val label = LabyrinthShopItemText.roleImprintLabel(text)
        // 印记必须连角色一起读出来才算确认：只读出「职能…印记」而角色缺失时继续换裁剪重试，
        // 三次都读不出就按 EXHAUSTED 跳过——既不盲买，也不让整店无限等待。
        val ready = (imprint && label != null) || (!text.isNullOrBlank() && item.relicMatch?.recognized == true)
        return item.copy(
            kind = when {
                imprint -> LabyrinthShopItemKind.ROLE_IMPRINT
                ready -> LabyrinthShopItemKind.RELIC
                else -> LabyrinthShopItemKind.UNKNOWN
            },
            relicMatch = if (imprint) null else item.relicMatch,
            roleImprintLabel = label,
            choiceRoleImprint = imprint && LabyrinthShopItemText.isChoiceRoleImprint(text),
            titleText = text,
            titleEvidenceId = evidenceId,
            categoryState = when {
                ready -> LabyrinthShopCategoryState.READY
                attempt >= MAX_ATTEMPTS -> LabyrinthShopCategoryState.EXHAUSTED
                else -> LabyrinthShopCategoryState.READING
            },
            categoryAttempt = minOf(attempt + 1, MAX_ATTEMPTS),
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        /**
         * How long the whole OCR engine may produce nothing before a waiting title gives up its
         * current crop. Roughly three times the slowest single read observed on the reporting
         * device (6.4 s), so only a genuinely stalled engine trips it.
         */
        const val ENGINE_SILENT_BACKSTOP_MILLIS = 20_000L
    }
}
