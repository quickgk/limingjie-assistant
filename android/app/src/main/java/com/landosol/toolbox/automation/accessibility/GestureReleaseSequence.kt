package com.landosol.toolbox.automation.accessibility

import com.landosol.toolbox.automation.AutomationBackendResult
import java.util.concurrent.atomic.AtomicReference

/** Survives caller cancellation without allowing another action to interrupt pointer release. */
internal class GestureDispatchOwnership {
    private val owner = AtomicReference<Any?>(null)
    fun acquire(token: Any): Boolean = owner.compareAndSet(null, token)
    fun owns(token: Any): Boolean = owner.get() === token
    fun release(token: Any): Boolean = owner.compareAndSet(token, null)
    fun isActive(): Boolean = owner.get() != null
}

/** A continued contact is complete only after its separately dispatched final stroke. */
internal class GestureReleaseSequence(private val hasHold: Boolean) {
    enum class Phase { TRAVEL, RELEASE }
    enum class Event { COMPLETED, CANCELLED, REJECTED, TIMED_OUT }

    data class Transition(
        val dispatchRelease: Boolean = false,
        val result: AutomationBackendResult? = null,
    )

    private var phase = Phase.TRAVEL
    private var finished = false
    private var failure: String? = null

    fun advance(source: Phase, event: Event, reason: String): Transition {
        if (finished || source != phase) return Transition()
        if (source == Phase.TRAVEL && hasHold &&
            (event == Event.COMPLETED || event == Event.TIMED_OUT)
        ) {
            if (event == Event.TIMED_OUT) failure = reason
            phase = Phase.RELEASE
            return Transition(dispatchRelease = true)
        }
        finished = true
        return Transition(result = when {
            event != Event.COMPLETED -> AutomationBackendResult.Rejected(failure ?: reason)
            failure != null -> AutomationBackendResult.Rejected(checkNotNull(failure))
            else -> AutomationBackendResult.Completed
        })
    }
}
