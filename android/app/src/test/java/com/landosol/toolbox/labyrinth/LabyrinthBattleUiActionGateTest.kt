package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthSpecialEquipmentObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthSpecialEquipmentStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LabyrinthBattleUiActionGateTest {
    @Test fun `recording pre click frames never authorize a second gesture`() {
        for ((completed, oldFrame) in listOf(27_686L to 27_607L, 21_522L to 21_407L, 30_713L to 30_607L)) {
            val gate = LabyrinthBattleUiActionGate()
            gate.recordExecuted("button", completed, 2_000L)
            assertFalse(gate.permits(oldFrame, completed + 3_000L, "button"))
            assertFalse(gate.observationIsAfterAction(oldFrame))
            assertFalse(gate.permits(completed + 599L, completed + 3_000L, "new"))
            assertTrue(gate.permits(completed + 600L, completed + 600L, "new"))
        }
    }

    @Test fun `same button waits but next target keeps the shorter interval`() {
        val gate = LabyrinthBattleUiActionGate()
        gate.recordExecuted("filter:wind", 1_620L, 2_000L)
        assertFalse(gate.permits(2_220L, 2_220L, "filter:wind"))
        assertTrue(gate.permits(2_220L, 2_220L, "filter:fire"))
        assertFalse(gate.permits(3_619L, 4_000L, "filter:wind"))
        assertTrue(gate.permits(3_620L, 3_620L, "filter:wind"))
        assertFalse(gate.permits(3_620L, 3_619L, "filter:wind"))
        assertFalse(gate.permits(1_619L, 1_619L, null))
        gate.reset()
        assertTrue(gate.permits(1L, 1L, "filter:wind"))
    }

    @Test fun `button identity ignores viewport revisions and never groups character toggles by coordinates`() {
        val action = AutomationAction.Tap(ScreenPoint(145f, 180f))
        val first = LabyrinthBattleTeamExecutionStep(LabyrinthBattleTeamExecutionKind.SELECT_FILTER, "filter:wind:1", "切换编组筛选：风", action)
        assertEquals(labyrinthBattleUiFeedbackKey(first), labyrinthBattleUiFeedbackKey(first.copy(key = "filter:wind:2")))
        assertNull(labyrinthBattleUiFeedbackKey(first.copy(kind = LabyrinthBattleTeamExecutionKind.DESELECT_CHARACTER)))
        assertNull(labyrinthBattleUiFeedbackKey(first.copy(kind = LabyrinthBattleTeamExecutionKind.SELECT_CHARACTER)))
    }

    @Test fun `auto dialog confirm waits for modal feedback before bounded retry`() {
        val auto = LabyrinthSpecialEquipmentObservation(stage = LabyrinthSpecialEquipmentStage.AUTO_DIALOG, confidence = 1.0, confirmButtonRect = EntryPixelRect(1, 1, 100, 100))
        fun decision(since: Long, attempts: Int = 1) = labyrinthSpecialEquipmentDecision(
            LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG, auto, attempts,
            stepElapsedMillis = since, equipConfirmEnabled = null, sinceLastStepDispatchMillis = since,
        )
        assertNull(decision(591L).tap)
        assertNull(decision(2_999L).tap)
        assertEquals(LabyrinthSpecialEquipmentTap.AUTO_CONFIRM, decision(3_000L).tap)
        assertTrue(decision(8_000L).finished)
        assertTrue(decision(3_000L, 3).finished)
    }

    @Test fun `real delayed executor blocks old frames across team scan and equipment resets`() = runBlocking {
        for (path in listOf("team", "scan", "equipment")) {
            val fixture = Fixture()
            try {
                fixture.now.set(1_000L)
                fixture.dispatch(path, 1_000L)
                withTimeout(5_000L) { fixture.entered.await() }
                fixture.now.set(1_620L)
                fixture.release.complete(Unit)
                fixture.awaitIdle()
                assertEquals(1, fixture.taps)
                fixture.now.set(2_500L)
                invoke(fixture.session, "resetBattleTeamExecutionTracking")
                invoke(fixture.session, "resetEffectiveCharacterScan")
                invoke(fixture.session, "resetSpecialEquipmentRound")
                fixture.dispatch(path, 1_600L)
                fixture.awaitIdle()
                assertEquals("$path reissued from a pre-click frame", 1, fixture.taps)
                fixture.dispatch(path, 2_221L)
                fixture.awaitIdle()
                assertEquals("$path reissued before feedback timeout", 1, fixture.taps)
                fixture.now.set(4_621L)
                fixture.dispatch(path, 4_621L)
                fixture.awaitIdle()
                assertEquals("$path must retain a bounded retry", 2, fixture.taps)
            } finally { fixture.session.stop() }
        }
    }

    @Test fun `non executed equipment outcomes never create a completed input gate`() = runBlocking {
        for (mode in listOf("paused", "stale", "dry", "rejected")) {
            val fixture = Fixture(mode)
            try {
                when (mode) {
                    "paused" -> fixture.manager.setPaused(fixture.id, true)
                    "stale" -> fixture.manager.stop(fixture.id)
                }
                fixture.now.set(1_000L)
                fixture.dispatch("equipment", 1_000L)
                fixture.awaitIdle()
                assertEquals(0, fixture.taps)
                assertTrue(fixture.gate.permits(1_001L, 1_001L, "anything"))
            } finally { fixture.session.stop() }
        }
    }

    @Test
    fun `boss pending action consumes unchanged frames and only acknowledges post click observations`() = runBlocking {
        val fixture = Fixture()
        try {
            val pendingType = fixture.session.javaClass.declaredClasses.single { it.simpleName == "PendingBattleTeamAction" }
            val constructor = pendingType.declaredConstructors.single { it.parameterCount == 6 }.apply { isAccessible = true }
            val pending = constructor.newInstance(LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM, "next team", emptyList<String>(), "BATTLE_TEAM_SELECTION:1", 1_620L, null)
            set(fixture.session, "pendingBattleTeamAction", pending)
            fixture.gate.recordExecuted("next", 1_620L, 2_000L)
            fun frame(tab: Int) = com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult(
                observation = com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation(
                    com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState.BATTLE_TEAM_SELECTION, 1.0, emptyMap(),
                    com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores(emptyMap()),
                ), matchedFeatures = emptyList(), elapsedMillis = 0,
                battleTeamSelection = com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation(
                    currentFilter = com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter.ALL,
                    filters = emptyList(), visibleCharacters = emptyList(), selectedCharacters = emptyList(),
                    scrollbar = com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation(EntryPixelRect(1, 1, 1, 1), null, false, false, 0.0),
                    bossTeamIndex = tab, recognitionState = com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState.STABLE,
                ),
            )
            assertTrue(invoke(fixture.session, "handleUnconfirmedBattleTeamActionFrame", fixture.id, frame(2), 1_600L) as Boolean)
            assertSame(pending, get(fixture.session, "pendingBattleTeamAction"))
            assertTrue(invoke(fixture.session, "handleUnconfirmedBattleTeamActionFrame", fixture.id, frame(1), 2_500L) as Boolean)
            assertSame(pending, get(fixture.session, "pendingBattleTeamAction"))
            assertFalse(invoke(fixture.session, "handleUnconfirmedBattleTeamActionFrame", fixture.id, frame(2), 2_501L) as Boolean)
            assertNull(get(fixture.session, "pendingBattleTeamAction"))
        } finally { fixture.session.stop() }
    }

    @Test fun `equipment handler rolls back a non executed stage and attempt before retry`() = runBlocking {
        for (mode in listOf("paused", "dry", "rejected")) {
            val fixture = Fixture(mode)
            try {
                set(fixture.session, "combatContext", LabyrinthCombatContext(LabyrinthCombatKind.NORMAL))
                set(fixture.session, "specialEquipmentSettings", LabyrinthStrategySettings(
                    specialEquipmentMode = LabyrinthSpecialEquipmentMode.AUTO_EQUIP,
                    specialEquipmentKinds = setOf(LabyrinthCombatKind.NORMAL),
                ))
                set(fixture.session, "specialEquipmentFlowStage", LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG)
                if (mode == "paused") fixture.manager.setPaused(fixture.id, true)
                val frame = com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult(
                    observation = com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation(
                        com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState.BATTLE_TEAM_SELECTION, 1.0, emptyMap(),
                        com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores(emptyMap()),
                    ), matchedFeatures = emptyList(), elapsedMillis = 0,
                    specialEquipment = LabyrinthSpecialEquipmentObservation(
                        stage = LabyrinthSpecialEquipmentStage.AUTO_DIALOG, confidence = 1.0,
                        confirmButtonRect = EntryPixelRect(100, 100, 100, 100),
                    ),
                )
                fixture.now.set(1_000L)
                assertTrue(invoke(fixture.session, "handleSpecialEquipmentFlowFrame", fixture.id, frame, 1_000L) as Boolean)
                fixture.awaitIdle()
                assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG, get(fixture.session, "specialEquipmentFlowStage"))
                assertEquals(0, get(fixture.session, "specialEquipmentStepAttempts"))
                assertEquals(Long.MIN_VALUE, get(fixture.session, "specialEquipmentStepLastDispatchAt"))
                assertEquals(0, fixture.taps)
            } finally { fixture.session.stop() }
        }
    }

    @Test fun `same filter recovery target keeps its identity when message and viewport change`() {
        val action = AutomationAction.Tap(ScreenPoint(145f, 180f))
        val first = LabyrinthBattleTeamExecutionStep(LabyrinthBattleTeamExecutionKind.SELECT_FILTER, "filter:WIND:1", "切换编组筛选：风", action)
        val recovery = first.copy(key = "filter-recovery:WIND:2", label = "火筛选已扫到底仍未找到甲；切换风继续查找")
        assertEquals(labyrinthBattleUiFeedbackKey(first), labyrinthBattleUiFeedbackKey(recovery))
    }

    @Test fun `feedback check happens while holding input latch and releases a denied attempt`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.gate.recordExecuted("filter:wind", 1_620L, 2_000L)
            fixture.now.set(2_500L)
            assertFalse(invoke(fixture.session, "beginBattleUiAction", 1_600L, "filter:wind") as Boolean)
            assertFalse((get(fixture.session, "actionInFlight") as AtomicBoolean).get())
            assertFalse(invoke(fixture.session, "beginBattleUiAction", 2_221L, "filter:wind") as Boolean)
            assertFalse((get(fixture.session, "actionInFlight") as AtomicBoolean).get())
            assertTrue(invoke(fixture.session, "beginBattleUiAction", 2_221L, "filter:fire") as Boolean)
            assertTrue((get(fixture.session, "actionInFlight") as AtomicBoolean).get())
            assertFalse(invoke(fixture.session, "beginBattleUiAction", 4_000L, "filter:fire") as Boolean)
            invoke(fixture.session, "endGuardedAction", true)
            Unit
        } finally { fixture.session.stop() }
    }

    private class Fixture(mode: String = "normal") {
        val now = AtomicLong(0L)
        val manager = AutomationSessionManager(now::get)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        @Volatile var taps = 0
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager, captureActive = { true }, processorFactory = { { error("No device input") } },
            actionExecutor = SessionBoundActionExecutor(manager) {
                if (mode == "rejected") AutomationBackendResult.Rejected("游戏不在前台") else {
                    taps++
                    entered.complete(Unit)
                    release.await()
                    AutomationBackendResult.Completed
                }
            }, actionsAvailable = { true }, clock = now::get,
        )
        val id = runBlocking {
            val started = manager.start(AutomationMode.LABYRINTH, dryRun = mode == "dry") as AutomationSessionStartResult.Started
            set(session, "activeSessionId", started.session.id)
            started.session.id
        }
        val gate get() = get(session, "battleUiActionGate") as LabyrinthBattleUiActionGate
        fun dispatch(path: String, frame: Long) {
            val tap = AutomationAction.Tap(ScreenPoint(145f, 180f))
            when (path) {
                "team" -> {
                    val step = LabyrinthBattleTeamExecutionStep(LabyrinthBattleTeamExecutionKind.SELECT_FILTER, "filter:wind:$frame", "切换编组筛选：风", tap)
                    invoke(session, "dispatchBattleTeamAction", id, step, frame)
                }
                "scan" -> invoke(session, "dispatchEffectiveCharacterScanAction", id, "有效效果扫描：恢复全部", tap, frame, null, null)
                else -> invoke(session, "dispatchSpecialEquipmentAction", id, EntryPixelRect(100, 100, 100, 100), "特别装备：点击弹窗2「确认」", frame, Any(), {}, {}, "AUTO_DIALOG")
            }
        }
        suspend fun awaitIdle() = withTimeout(5_000L) {
            while ((get(session, "actionInFlight") as AtomicBoolean).get()) delay(1L)
        }
    }

    companion object {
        private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        private fun set(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
            val method = target.javaClass.declaredMethods.single {
                (it.name == name || it.name.startsWith("$name-")) && it.parameterCount == args.size
            }.apply { isAccessible = true }
            val adapted = method.parameterTypes.mapIndexed { i, type ->
                if (type == java.lang.Long.TYPE && args[i] is AutomationSessionId) (args[i] as AutomationSessionId).value else args[i]
            }.toTypedArray()
            return method.invoke(target, *adapted)
        }
    }
}
