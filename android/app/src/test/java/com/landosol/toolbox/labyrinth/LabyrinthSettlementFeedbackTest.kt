package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.labyrinth.vision.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LabyrinthSettlementFeedbackTest {
    @Test fun `old score frames before completion never revert chest sequence or consume retries`() = runBlocking {
        for (offset in listOf(80L, 186L)) {
            val f = Fixture()
            try {
                f.stage(LabyrinthPostBossStage.SCORE_RESULT)
                f.feed(score(), 10_000); f.feed(score(), 10_001)
                val tap = f.nextTap()
                val streak = f.field("postEntryStableFrames")
                f.feed(score(), 10_002)
                assertEquals(streak, f.field("postEntryStableFrames"))
                f.complete(tap, 11_000)
                assertEquals(LabyrinthPostBossStage.CHEST_SEQUENCE, f.field("postBossStage"))
                val attempts = f.field("postEntryAttempts")
                f.feed(score(), 11_000 - offset, 14_000)
                assertEquals(LabyrinthPostBossStage.CHEST_SEQUENCE, f.field("postBossStage"))
                assertEquals(attempts, f.field("postEntryAttempts"))
                assertEquals(1, f.calls.size)
                f.feed(score(), 14_001)
                assertEquals(LabyrinthPostBossStage.SCORE_RESULT, f.field("postBossStage"))
                assertEquals(2, f.nextTap().let { f.calls.size })
            } finally { f.close() }
        }
        Unit
    }

    @Test fun `trusted chest independently confirms from every earlier stage only after two captures`() = runBlocking {
        for (stage in listOf(LabyrinthPostBossStage.NONE, LabyrinthPostBossStage.BEFORE_SCORE,
            LabyrinthPostBossStage.SCORE_RESULT, LabyrinthPostBossStage.CHEST_SEQUENCE)) {
            val f = Fixture()
            try {
                f.stage(stage)
                val frame = chest()
                f.feed(frame, 10_000)
                assertTrue(f.calls.isEmpty())
                f.feed(frame, 10_000)
                assertTrue(f.calls.isEmpty())
                f.feed(frame, 10_001)
                val tap = f.nextTap()
                assertEquals(AutomationAction.Tap(ScreenPoint(960f, 965f)), tap.action)
                assertEquals(0, f.field("chestConfirmAttempts"))
                f.complete(tap, 10_200)
                assertEquals(1, f.field("chestConfirmAttempts"))
                assertEquals(LabyrinthPostBossStage.CHEST_RESULT, f.field("postBossStage"))
                assertEquals(0, f.field("postEntryAttempts"))
            } finally { f.close() }
        }
        Unit
    }

    @Test fun `low confidence missing title or missing button cannot confirm`() = runBlocking {
        val candidates = listOf(chest().copy(observation = chest().observation.copy(confidence = 0.84)),
            frame(LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT, mapOf(EntryAnchorId.RUN_CLEAR_CHEST_CONFIRM_BUTTON to anchor(745, 910, 430, 110))),
            frame(LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT, mapOf(EntryAnchorId.RUN_CLEAR_CHEST_RESULT_TITLE to anchor(650, 52, 620, 60))))
        for (candidate in candidates) {
            val f = Fixture()
            try {
                f.stage(LabyrinthPostBossStage.SCORE_RESULT)
                f.feed(candidate, 10_000); f.feed(candidate, 10_001)
                assertTrue(f.calls.isEmpty())
                assertEquals(LabyrinthPostBossStage.SCORE_RESULT, f.field("postBossStage"))
                f.feed(frame(LabyrinthEntryPageState.UNKNOWN), 26_000)
                f.awaitIdle()
                assertFalse(f.state.value.routeProgress!!.complete)
                assertNotEquals(LabyrinthEntryRecognitionStatus.RUNNING, f.state.value.status)
            } finally { f.close() }
        }
        Unit
    }

    @Test fun `chest retry waits for completion and page jitter does not refill budget`() = runBlocking {
        val f = Fixture()
        try {
            f.feed(chest(), 10_000); f.feed(chest(), 10_001)
            f.complete(f.nextTap(), 11_000)
            val started = f.field("chestResultStartedAt")
            f.feed(chest(), 12_499); f.feed(chest(), 12_999)
            assertEquals(1, f.calls.size)
            f.feed(frame(LabyrinthEntryPageState.UNKNOWN), 13_000)
            f.feed(frame(LabyrinthEntryPageState.NODE_SELECTION), 13_001)
            assertEquals(1, f.calls.size)
            assertEquals(started, f.field("chestResultStartedAt"))
            f.feed(chest(), 13_002); f.feed(chest(), 13_003)
            f.complete(f.nextTap(), 13_100)
            assertEquals(2, f.field("chestConfirmAttempts"))
            f.feed(chest(), 15_100); f.feed(chest(), 15_101)
            f.complete(f.nextTap(), 15_200)
            f.feed(chest(), 17_199)
            assertEquals(3, f.calls.size)
            assertEquals(LabyrinthEntryRecognitionStatus.RUNNING, f.state.value.status)
            f.feed(chest(), 17_200)
            f.awaitIdle()
            assertEquals(3, f.calls.size)
            assertNotEquals(LabyrinthEntryRecognitionStatus.RUNNING, f.state.value.status)
        } finally { f.close() }
        Unit
    }

    @Test fun `unknown or map feedback reaches chest deadline without extra taps or run clear`() = runBlocking {
        val f = Fixture()
        try {
            f.feed(chest(), 10_000); f.feed(chest(), 10_001)
            f.complete(f.nextTap(), 10_100)
            f.feed(frame(LabyrinthEntryPageState.NODE_SELECTION), 12_000)
            f.feed(frame(LabyrinthEntryPageState.UNKNOWN), 26_000)
            f.awaitIdle()
            assertEquals(1, f.calls.size)
            assertFalse(f.state.value.routeProgress!!.complete)
            assertNotEquals(LabyrinthEntryRecognitionStatus.RUNNING, f.state.value.status)
        } finally { f.close() }
        Unit
    }

    @Test fun `chest executed is not clear and final item followed by stable home is still required`() = runBlocking {
        val f = Fixture()
        try {
            f.feed(chest(), 10_000); f.feed(chest(), 10_001)
            f.complete(f.nextTap(), 10_100)
            assertFalse(f.state.value.routeProgress!!.complete)
            f.feed(frame(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE), 12_100)
            f.feed(frame(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE), 12_101)
            assertEquals(LabyrinthPostBossStage.CHEST_RESULT, f.field("postBossStage"))
            assertEquals(1, f.calls.size)
            f.feed(item(), 12_200); f.feed(item(), 12_201)
            val itemTap = f.nextTap()
            assertEquals(LabyrinthPostBossStage.FINAL_ITEM_REWARD, f.field("postBossStage"))
            f.complete(itemTap, 12_300)
            assertEquals(LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME, f.field("postBossStage"))
            assertFalse(f.state.value.routeProgress!!.complete)
            f.feed(frame(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE), 12_299, 14_000)
            assertFalse(f.state.value.routeProgress!!.complete)
            f.feed(frame(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE).let { it.copy(observation = it.observation.copy(confidence = 0.60)) }, 14_001)
            assertFalse(f.state.value.routeProgress!!.complete)
            f.feed(frame(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE), 14_002)
            assertFalse(f.state.value.routeProgress!!.complete)
            f.feed(frame(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE), 14_003)
            f.awaitIdle()
            assertTrue(f.state.value.routeProgress!!.complete)
        } finally { f.close() }
        Unit
    }

    @Test fun `first trusted item at the final retry boundary gets a bounded stability window`() = runBlocking {
        val f = Fixture()
        try {
            f.feed(chest(), 10_000); f.feed(chest(), 10_001)
            f.complete(f.nextTap(), 10_100)
            f.feed(chest(), 12_100); f.feed(chest(), 12_101)
            f.complete(f.nextTap(), 12_200)
            f.feed(chest(), 14_200); f.feed(chest(), 14_201)
            f.complete(f.nextTap(), 14_300)
            assertEquals(3, f.field("chestConfirmAttempts"))
            f.feed(item(), 16_300)
            assertEquals(LabyrinthEntryRecognitionStatus.RUNNING, f.state.value.status)
            assertEquals(3, f.calls.size)
            f.feed(item(), 16_301)
            val close = f.nextTap()
            assertEquals(LabyrinthPostBossStage.FINAL_ITEM_REWARD, f.field("postBossStage"))
            f.complete(close, 16_400)
            f.feed(frame(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE), 18_000)
            f.feed(frame(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE), 18_001)
            f.awaitIdle()
            assertTrue(f.state.value.routeProgress!!.complete)
        } finally { f.close() }
        Unit
    }

    @Test fun `a lone item candidate cannot extend settlement indefinitely`() = runBlocking {
        val f = Fixture()
        try {
            f.feed(chest(), 10_000); f.feed(chest(), 10_001)
            f.complete(f.nextTap(), 10_100)
            f.feed(item(), 26_000)
            assertEquals(LabyrinthEntryRecognitionStatus.RUNNING, f.state.value.status)
            f.feed(frame(LabyrinthEntryPageState.UNKNOWN), 27_501)
            f.awaitIdle()
            assertFalse(f.state.value.routeProgress!!.complete)
            assertNotEquals(LabyrinthEntryRecognitionStatus.RUNNING, f.state.value.status)
        } finally { f.close() }
        Unit
    }

    @Test fun `paused stale and dry planner never consume chest execution budget`() = runBlocking {
        for (mode in listOf("paused", "stale", "dry-planner")) {
            val f = Fixture()
            try {
                when (mode) {
                    "paused" -> f.manager.setPaused(f.id, true)
                    "stale" -> f.manager.stop(f.id)
                    else -> f.state.value = f.state.value.copy(dryRun = true)
                }
                f.feed(chest(), 10_000); f.feed(chest(), 10_001); f.awaitIdle()
                assertTrue(f.calls.isEmpty())
                assertEquals(0, f.field("chestConfirmAttempts"))
                assertEquals(Long.MIN_VALUE, f.field("lastChestConfirmCompletedAt"))
                invoke(f.session, "resetNodeExecutionState")
                assertEquals(Long.MIN_VALUE, f.field("chestResultStartedAt"))
                assertEquals(0, f.field("chestItemStableFrames"))
            } finally { f.close() }
        }
        Unit
    }

    @Test fun `outer frame dispatch keeps map feedback owned by chest settlement`() = runBlocking {
        val f = Fixture()
        try {
            f.feed(chest(), 10_000); f.feed(chest(), 10_001)
            f.complete(f.nextTap(), 10_100)
            f.now.set(12_000)
            invoke(f.session, "processFrameResult", f.id, frame(LabyrinthEntryPageState.NODE_SELECTION), 1920, 1080, 12_000L)
            assertEquals(1, f.calls.size)
            assertEquals(LabyrinthPostBossStage.CHEST_RESULT, f.field("postBossStage"))
            f.now.set(26_000)
            invoke(f.session, "processFrameResult", f.id, frame(LabyrinthEntryPageState.NODE_SELECTION), 1920, 1080, 26_000L)
            f.awaitIdle()
            assertFalse(f.state.value.routeProgress!!.complete)
            assertNotEquals(LabyrinthEntryRecognitionStatus.RUNNING, f.state.value.status)
        } finally { f.close() }
        Unit
    }

    @Test fun `untrusted chest candidate yields to two fresh trusted score results`() = runBlocking {
        val f = Fixture()
        try {
            f.stage(LabyrinthPostBossStage.CHEST_SEQUENCE)
            val uncertain = chest().let { it.copy(observation = it.observation.copy(confidence = 0.84)) }
            f.feed(uncertain, 10_000)
            f.feed(score(), 10_001)
            assertTrue(f.calls.isEmpty())
            f.feed(score(), 10_002)
            val tap = f.nextTap()
            assertEquals(LabyrinthPostBossStage.SCORE_RESULT, f.field("postBossStage"))
            assertEquals(Long.MIN_VALUE, f.field("chestResultStartedAt"))
            f.complete(tap, 10_100)
            assertEquals(LabyrinthPostBossStage.CHEST_SEQUENCE, f.field("postBossStage"))
        } finally { f.close() }
        Unit
    }

    @Test fun `dry run and foreground rejection leave chest execution budget untouched`() = runBlocking {
        val dry = Fixture(managerDryRun = true)
        try {
            dry.feed(chest(), 10_000); dry.feed(chest(), 10_001); dry.awaitIdle()
            assertTrue(dry.calls.isEmpty())
            assertEquals(0, dry.field("chestConfirmAttempts"))
            assertEquals(Long.MIN_VALUE, dry.field("lastChestConfirmCompletedAt"))
        } finally { dry.close() }
        val rejected = Fixture()
        try {
            rejected.feed(chest(), 10_000); rejected.feed(chest(), 10_001)
            rejected.complete(rejected.nextTap(), 10_100, AutomationBackendResult.Rejected("游戏不在前台"))
            assertEquals(0, rejected.field("chestConfirmAttempts"))
            assertEquals(Long.MIN_VALUE, rejected.field("lastChestConfirmCompletedAt"))
            assertFalse(rejected.inFlight)
        } finally { rejected.close() }
        Unit
    }

    private data class TapCall(val action: AutomationAction,
        val outcome: CompletableDeferred<AutomationBackendResult> = CompletableDeferred())
    private class Fixture(managerDryRun: Boolean = false) {
        val now = AtomicLong(10_000L)
        val manager = AutomationSessionManager(now::get)
        val calls = CopyOnWriteArrayList<TapCall>()
        private val entering = Channel<TapCall>(Channel.UNLIMITED)
        private val closing = AtomicBoolean(false)
        val session = LabyrinthEntryRecognitionSession(sessionManager = manager, captureActive = { true },
            processorFactory = { { error("No device capture") } },
            actionExecutor = SessionBoundActionExecutor(manager) { action ->
                val call = TapCall(action); calls += call; entering.send(call)
                if (closing.get()) call.outcome.complete(AutomationBackendResult.Rejected("fixture cleanup"))
                call.outcome.await()
            }, actionsAvailable = { true }, clock = now::get)
        val id = runBlocking { (manager.start(AutomationMode.LABYRINTH, dryRun = managerDryRun)
            as AutomationSessionStartResult.Started).session.id }
        @Suppress("UNCHECKED_CAST")
        val state = get(session, "_state") as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
        init {
            set(session, "activeSessionId", id)
            set(session, "entryPhaseComplete", true)
            set(session, "routeNodeCount", 36)
            set(session, "postBossStage", LabyrinthPostBossStage.NONE)
            set(session, "currentRunId", "settlement-test")
            state.value = LabyrinthEntryRecognitionSessionState(status = LabyrinthEntryRecognitionStatus.RUNNING,
                sessionId = id, dryRun = false, routeProgress = LabyrinthRouteProgress(5, 36, 36, complete = false))
        }
        fun field(name: String): Any? = get(session, name)
        fun stage(value: LabyrinthPostBossStage) = set(session, "postBossStage", value)
        val inFlight get() = (get(session, "actionInFlight") as AtomicBoolean).get()
        fun feed(result: LabyrinthEntryFrameResult, capturedAt: Long, processedAt: Long = maxOf(now.get(), capturedAt)) {
            now.set(processedAt)
            state.value = state.value.copy(frameCount = state.value.frameCount + 1, lastResult = result, frameTrace = LabyrinthFrameTrace())
            invoke(session, "handlePostEntryPage", id, result, 1920, 1080, capturedAt)
        }
        suspend fun nextTap() = withTimeout(5_000L) { entering.receive() }
        suspend fun complete(call: TapCall, completedAt: Long, result: AutomationBackendResult = AutomationBackendResult.Completed) {
            now.set(completedAt); check(call.outcome.complete(result)); awaitIdle()
        }
        suspend fun awaitIdle() {
            withTimeout(5_000L) {
                while (inFlight) delay(1L)
                if (get(session, "activeSessionId") == null)
                    while (state.value.status == LabyrinthEntryRecognitionStatus.RUNNING) delay(1L)
            }
        }
        suspend fun close() {
            closing.set(true)
            calls.forEach { it.outcome.complete(AutomationBackendResult.Rejected("fixture cleanup")) }
            awaitIdle(); session.stop()
        }
    }
    companion object {
        private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        private fun set(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
            val method = target.javaClass.declaredMethods.single { (it.name == name || it.name.startsWith("$name-")) && it.parameterCount == args.size }
                .apply { isAccessible = true }
            val adapted = method.parameterTypes.mapIndexed { i, type ->
                if (type == java.lang.Long.TYPE && args[i] is AutomationSessionId) (args[i] as AutomationSessionId).value else args[i]
            }.toTypedArray()
            return method.invoke(target, *adapted)
        }
        private fun anchor(x: Int, y: Int, w: Int, h: Int) = EntryAnchorMatch(0.99, EntryPixelRect(x, y, w, h))
        private fun frame(page: LabyrinthEntryPageState, anchors: Map<String, EntryAnchorMatch> = emptyMap()) = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(page, 1.0, mapOf(page to 1.0), LabyrinthAnchorScores(anchors.mapValues { it.value.score })),
            matchedFeatures = emptyList(), elapsedMillis = 0, anchorMatches = anchors, frameWidth = 1920, frameHeight = 1080)
        private fun score() = frame(LabyrinthEntryPageState.RUN_CLEAR_RESULT, mapOf(
            EntryAnchorId.RUN_RESULT_LOGO to anchor(768, 68, 380, 104), EntryAnchorId.RUN_RESULT_CLOSE_BUTTON to anchor(752, 948, 416, 92)))
        private fun chest() = frame(LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT, mapOf(
            EntryAnchorId.RUN_CLEAR_CHEST_RESULT_TITLE to anchor(650, 52, 620, 60), EntryAnchorId.RUN_CLEAR_CHEST_CONFIRM_BUTTON to anchor(745, 910, 430, 110)))
        private fun item() = frame(LabyrinthEntryPageState.ITEM_REWARD, mapOf(
            EntryAnchorId.ITEM_REWARD_TITLE to anchor(760, 50, 400, 75), EntryAnchorId.ITEM_REWARD_CLOSE to anchor(745, 895, 430, 140)))
    }
}
