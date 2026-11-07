package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationMode
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Deterministic clock and real active manager for the legacy private-handler session fixtures. */
internal object LabyrinthBattleUiTestClock {
    private val clocks = Collections.synchronizedMap(IdentityHashMap<Any, AtomicLong>())

    fun register(session: Any, clock: AtomicLong) { clocks[session] = clock }

    fun start(manager: AutomationSessionManager) = runBlocking {
        repeat(7) { index ->
            manager.start(AutomationMode.LABYRINTH, dryRun = false)
            if (index < 6) manager.stop(requireNotNull(manager.current()).id)
        }
    }

    fun before(session: Any, method: String, arguments: Array<out Any?>) {
        if (method !in setOf("handleBattleWaitFrame", "handleBattleTeamSelectionExecution", "handleSpecialEquipmentFlowFrame", "handlePostEntryPage")) return
        clocks[session]?.let { clock ->
            await(session)
            (arguments.lastOrNull() as? Long)?.let(clock::set)
        }
    }

    fun after(session: Any) { if (clocks.containsKey(session)) await(session) }

    private fun await(session: Any) = runBlocking {
        val field = session.javaClass.getDeclaredField("actionInFlight").apply { isAccessible = true }
        val since = session.javaClass.getDeclaredField("actionInFlightSince").apply { isAccessible = true }
        withTimeout(5_000L) {
            while ((field.get(session) as AtomicBoolean).get() && since.getLong(session) != Long.MIN_VALUE) delay(1L)
        }
    }
}
