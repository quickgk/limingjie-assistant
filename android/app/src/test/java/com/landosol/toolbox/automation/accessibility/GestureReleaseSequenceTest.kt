package com.landosol.toolbox.automation.accessibility

import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.accessibility.GestureReleaseSequence.Event
import com.landosol.toolbox.automation.accessibility.GestureReleaseSequence.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureReleaseSequenceTest {
    @Test
    fun `held travel completion dispatches release but does not complete action`() {
        val sequence = GestureReleaseSequence(hasHold = true)
        val travel = sequence.advance(Phase.TRAVEL, Event.COMPLETED, "")
        assertTrue(travel.dispatchRelease)
        assertNull(travel.result)
        val release = sequence.advance(Phase.RELEASE, Event.COMPLETED, "")
        assertFalse(release.dispatchRelease)
        assertEquals(AutomationBackendResult.Completed, release.result)
    }

    @Test
    fun `plain tap or swipe completes without continued release`() {
        val result = GestureReleaseSequence(hasHold = false).advance(Phase.TRAVEL, Event.COMPLETED, "")
        assertFalse(result.dispatchRelease)
        assertEquals(AutomationBackendResult.Completed, result.result)
    }

    @Test
    fun `travel timeout attempts release exactly once and keeps unknown outcome rejected`() {
        val sequence = GestureReleaseSequence(hasHold = true)
        val travel = sequence.advance(Phase.TRAVEL, Event.TIMED_OUT, "travel timeout")
        assertTrue(travel.dispatchRelease)
        assertNull(travel.result)
        assertIgnored(sequence.advance(Phase.TRAVEL, Event.TIMED_OUT, "duplicate timeout"))
        assertIgnored(sequence.advance(Phase.TRAVEL, Event.COMPLETED, "late callback"))
        val release = sequence.advance(Phase.RELEASE, Event.COMPLETED, "")
        assertEquals(AutomationBackendResult.Rejected("travel timeout"), release.result)
    }

    @Test
    fun `late travel events cannot complete or cancel the release phase`() {
        val sequence = GestureReleaseSequence(hasHold = true)
        sequence.advance(Phase.TRAVEL, Event.COMPLETED, "")
        for (event in Event.entries) assertIgnored(sequence.advance(Phase.TRAVEL, event, "late"))
        assertEquals(AutomationBackendResult.Completed, sequence.advance(Phase.RELEASE, Event.COMPLETED, "").result)
    }

    @Test
    fun `release failures reject without another release or replacement gesture`() {
        for (event in listOf(Event.CANCELLED, Event.REJECTED, Event.TIMED_OUT)) {
            val sequence = GestureReleaseSequence(hasHold = true)
            sequence.advance(Phase.TRAVEL, Event.COMPLETED, "")
            val release = sequence.advance(Phase.RELEASE, event, "release failure")
            assertFalse(release.dispatchRelease)
            assertEquals(AutomationBackendResult.Rejected("release failure"), release.result)
            for (late in Event.entries) assertIgnored(sequence.advance(Phase.RELEASE, late, "late"))
        }
    }

    @Test
    fun `travel cancellation or dispatch rejection terminates without a new contact`() {
        for (event in listOf(Event.CANCELLED, Event.REJECTED)) {
            val sequence = GestureReleaseSequence(hasHold = true)
            val result = sequence.advance(Phase.TRAVEL, event, "not active")
            assertFalse(result.dispatchRelease)
            assertEquals(AutomationBackendResult.Rejected("not active"), result.result)
            assertIgnored(sequence.advance(Phase.TRAVEL, Event.COMPLETED, "late"))
            assertIgnored(sequence.advance(Phase.RELEASE, Event.COMPLETED, "late"))
        }
    }

    @Test
    fun `plain gesture timeout does not inject a release stroke`() {
        val result = GestureReleaseSequence(hasHold = false).advance(Phase.TRAVEL, Event.TIMED_OUT, "timeout")
        assertFalse(result.dispatchRelease)
        assertEquals(AutomationBackendResult.Rejected("timeout"), result.result)
    }

    @Test
    fun `terminal result is delivered once even with duplicate callbacks`() {
        val sequence = GestureReleaseSequence(hasHold = true)
        sequence.advance(Phase.TRAVEL, Event.COMPLETED, "")
        sequence.advance(Phase.RELEASE, Event.COMPLETED, "")
        for (phase in Phase.entries) for (event in Event.entries) assertIgnored(sequence.advance(phase, event, "late"))
    }

    @Test
    fun `premature release callback cannot complete a pending travel`() {
        val sequence = GestureReleaseSequence(hasHold = true)
        assertIgnored(sequence.advance(Phase.RELEASE, Event.COMPLETED, "out of order"))
        assertTrue(sequence.advance(Phase.TRAVEL, Event.COMPLETED, "").dispatchRelease)
        assertEquals(AutomationBackendResult.Completed, sequence.advance(Phase.RELEASE, Event.COMPLETED, "").result)
    }

    @Test
    fun `caller cancellation cannot let another action interrupt pending release`() {
        val ownership = GestureDispatchOwnership()
        val first = Any()
        val second = Any()
        val sequence = GestureReleaseSequence(hasHold = true)
        assertTrue(ownership.acquire(first))
        // Cancellation drops only the waiter; the injected chain still owns its token.
        assertTrue(ownership.isActive())
        assertFalse(ownership.acquire(second))
        val travel = sequence.advance(Phase.TRAVEL, Event.COMPLETED, "")
        assertTrue(travel.dispatchRelease)
        assertFalse(ownership.acquire(second))
        val release = sequence.advance(Phase.RELEASE, Event.COMPLETED, "")
        assertEquals(AutomationBackendResult.Completed, release.result)
        assertTrue(ownership.release(first))
        assertTrue(ownership.acquire(second))
        assertFalse(ownership.release(first))
        assertTrue(ownership.owns(second))
    }

    @Test
    fun `dispatch rejection and release failure free only their own chain`() {
        for (event in listOf(Event.REJECTED, Event.CANCELLED, Event.TIMED_OUT)) {
            val ownership = GestureDispatchOwnership()
            val token = Any()
            assertTrue(ownership.acquire(token))
            val sequence = GestureReleaseSequence(hasHold = true)
            sequence.advance(Phase.TRAVEL, Event.COMPLETED, "")
            val result = sequence.advance(Phase.RELEASE, event, "failed")
            assertTrue(result.result is AutomationBackendResult.Rejected)
            assertTrue(ownership.release(token))
            assertFalse(ownership.isActive())
            assertFalse(ownership.release(token))
        }
    }

    private fun assertIgnored(transition: GestureReleaseSequence.Transition) {
        assertFalse(transition.dispatchRelease)
        assertNull(transition.result)
    }
}
