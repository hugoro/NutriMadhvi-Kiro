package com.example.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.health.SamsungHealthRepository
import com.example.data.local.BodyCompositionSnapshotEntity
import com.example.data.local.BodyPlanCheckpointEntity
import com.example.data.local.ChatDatabase
import com.example.data.local.PerformanceGoalEntity
import com.example.data.local.PerformanceSnapshotEntity
import com.example.data.remote.GeminiRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review item 1 (cancellation/process-interruption): the deterministic
 * checkpoint must be persisted IMMEDIATELY after the snapshot insert —
 * before any network work — so a hung network, a crash, or a process kill
 * mid-enrichment can never leave the checkpoint missing.
 *
 * Review item 4 (retry): re-enrichment of an OLD checkpoint must run from
 * the checkpoint's own anchored inputs (persisted goal/composition/phase),
 * persist the real `llm:<model>` traceability, and debounce repeated taps
 * while one enrichment is in flight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelCheckpointPersistenceTest {

    private lateinit var db: ChatDatabase
    private lateinit var context: android.content.Context

    /** Exposes the protected [ChatViewModel.onCleared] so the test can
     *  simulate a process interruption mid-enrichment. */
    private class TestableChatViewModel(
        repository: GeminiRepository,
        shRepository: SamsungHealthRepository,
    ) : ChatViewModel(repository, shRepository) {
        fun simulateProcessKill() = onCleared()
    }

    /** Network that NEVER completes — simulates a hung request / process
     *  interruption before any LLM reply arrives. */
    private class HangingRepo(db: ChatDatabase) : GeminiRepository(
        chatDao = db.chatDao,
        foodDao = db.foodDao,
        activityDao = db.activityDao,
        foodHabitDao = db.foodHabitDao,
        targetSnapshotDao = db.targetSnapshotDao,
        bmrSnapshotDao = db.bmrSnapshotDao,
        bodyCompositionSnapshotDao = db.bodyCompositionSnapshotDao,
        dailyActivitySnapshotDao = db.dailyActivitySnapshotDao,
        userInsightDao = db.userInsightDao,
        performanceSnapshotDao = db.performanceSnapshotDao,
        performanceGoalDao = db.performanceGoalDao,
        bodyPlanCheckpointDao = db.bodyPlanCheckpointDao,
    ) {
        override suspend fun generateCoachReport(prompt: String): String? {
            withContext(Dispatchers.Default) { CompletableDeferred<Unit>().await() }
            return null
        }

        override suspend fun generateBodyPlanContent(prompt: String, language: String): GeminiRepository.BodyPlanLlmResult {
            withContext(Dispatchers.Default) { CompletableDeferred<Unit>().await() }
            return GeminiRepository.BodyPlanLlmResult(null, null)
        }
    }

    /** Network that answers the body-plan call from a gate, recording the
     *  prompt + call count — used to verify anchoring and debouncing. */
    private class GatedRepo(db: ChatDatabase) : GeminiRepository(
        chatDao = db.chatDao,
        foodDao = db.foodDao,
        activityDao = db.activityDao,
        foodHabitDao = db.foodHabitDao,
        targetSnapshotDao = db.targetSnapshotDao,
        bmrSnapshotDao = db.bmrSnapshotDao,
        bodyCompositionSnapshotDao = db.bodyCompositionSnapshotDao,
        dailyActivitySnapshotDao = db.dailyActivitySnapshotDao,
        userInsightDao = db.userInsightDao,
        performanceSnapshotDao = db.performanceSnapshotDao,
        performanceGoalDao = db.performanceGoalDao,
        bodyPlanCheckpointDao = db.bodyPlanCheckpointDao,
    ) {
        val gate = CompletableDeferred<Unit>()
        var callCount = 0
        var lastPrompt: String? = null

        override suspend fun generateCoachReport(prompt: String): String? = null

        override suspend fun generateBodyPlanContent(prompt: String, language: String): GeminiRepository.BodyPlanLlmResult {
            callCount++
            lastPrompt = prompt
            withContext(Dispatchers.IO) { gate.await() }
            return GeminiRepository.BodyPlanLlmResult(
                text = "Relatório do checkpoint.\n\n[TACTICS]" +
                    """[{"category":"protein","text":"Proteína em 1.8 g/kg por dia","windowDays":28}]""" +
                    "\n[/TACTICS]",
                modelUsed = "test-model",
            )
        }
    }

    @Before
    fun setUp() {
        // The ViewModel's viewModelScope dispatches to Main — drive it
        // synchronously from the test thread so the deterministic (local)
        // work runs immediately, while the hanging/gated network jobs
        // suspend on real IO threads exactly like in production.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun seedComposition(id: Long, weightKg: Float, fatPct: Float) = runBlocking {
        db.bodyCompositionSnapshotDao.insert(
            BodyCompositionSnapshotEntity(
                sourceTimestampMs = id * 1000L,
                capturedAtMs = id * 1000L,
                weightKg = weightKg,
                bodyFatPct = fatPct,
                muscleMassKg = (weightKg * 0.4).toFloat(),
            )
        )
    }

    private fun waitForCheckpoint(timeoutMs: Long = 10_000): BodyPlanCheckpointEntity? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val cp = runBlocking { db.bodyPlanCheckpointDao.latest() }
            if (cp != null) return cp
            Thread.sleep(50)
        }
        return null
    }

    // ── Item 1: checkpoint survives a network that never completes ─────────

    @Test
    fun `local checkpoint is persisted before network and survives process interruption`() {
        val repo = HangingRepo(db)
        val vm = TestableChatViewModel(repo, SamsungHealthRepository(context))

        // Some composition data (a two-reading history so the
        // deterministic phase is computed locally).
        seedComposition(700L, 70f, 24f)
        seedComposition(701L, 69.5f, 23f)

        vm.savePerformanceSnapshotAndRequestReport(
            PerformanceSnapshotEntity(
                frontalWaistRatio = 0.24f,
                frontalHipRatio = 0.31f,
                lateralBellyRatio = 0.19f,
            )
        )

        // The checkpoint must appear in the DB even though BOTH network
        // jobs hang forever.
        val cp = waitForCheckpoint()
        assertNotNull(
            "deterministic checkpoint must exist without any network completion",
            cp,
        )
        val saved = cp!!
        assertTrue("checkpoint must be anchored to the new snapshot", saved.snapshotId != null)
        assertNotNull("phase must be computed locally before the network", saved.phase)
        assertNotNull("priorities must be computed locally before the network", saved.prioritiesJson)
        assertNull("tactics belong to the hung network job — must be pending", saved.tacticsJson)

        // Process interruption: the VM's scope is cancelled while both
        // network jobs are still in flight.
        vm.simulateProcessKill()
        val after = runBlocking { db.bodyPlanCheckpointDao.latest() }
        assertNotNull("checkpoint must survive the process interruption", after)
        assertEquals(saved.id, after!!.id)
        assertEquals(saved.phase, after.phase)
        assertEquals(saved.prioritiesJson, after.prioritiesJson)

        // The snapshot itself is also persisted (the checkpoint's anchor).
        val snap = runBlocking { db.performanceSnapshotDao.getById(saved.snapshotId!!) }
        assertNotNull("snapshot must be persisted with the checkpoint", snap)
        assertEquals(0.24f, snap!!.frontalWaistRatio!!, 0.0001f)
    }

    // ── Item 4: retry is checkpoint-anchored, traceable, debounced ──────────

    @Test
    fun `retry of an old checkpoint re-enriches from anchored inputs with model traceability`() = runBlocking {
        // Old snapshot + anchored composition + checkpoint with an anchored goal.
        val oldSnapshotId = db.performanceSnapshotDao.insert(
            PerformanceSnapshotEntity(
                capturedAtMs = 1_700_000_000_000L,
                frontalWaistRatio = 0.30f,
                frontalHipRatio = 0.33f,
            )
        )
        val compId = db.bodyCompositionSnapshotDao.insert(
            BodyCompositionSnapshotEntity(
                sourceTimestampMs = 1_700_000_010_000L,
                capturedAtMs = 1_700_000_010_000L,
                weightKg = 70f,
                bodyFatPct = 24f,
                muscleMassKg = 28f,
            )
        )
        val oldCpId = db.bodyPlanCheckpointDao.insert(
            BodyPlanCheckpointEntity(
                checkpointAtMs = 1_700_000_020_000L,
                snapshotId = oldSnapshotId,
                bodyCompRowId = compId,
                phase = "CUT",
                prioritiesJson = """[{"zone":"waist","direction":"REDUCE","gapPp":1.8,"tissue":"FAT_DOMINANT"}]""",
                goalText = "ANCHORED-GOAL-TEXT",
                goalTargetsJson = """{"waist":0.23}""",
            )
        )

        // TODAY's state has moved on: a newer snapshot and a different goal.
        db.performanceSnapshotDao.insert(
            PerformanceSnapshotEntity(
                capturedAtMs = 1_800_000_000_000L,
                frontalWaistRatio = 0.22f,
                frontalHipRatio = 0.30f,
            )
        )
        db.bodyCompositionSnapshotDao.insert(
            BodyCompositionSnapshotEntity(
                sourceTimestampMs = 1_800_000_010_000L,
                capturedAtMs = 1_800_000_010_000L,
                weightKg = 80f,
                bodyFatPct = 30f,
                muscleMassKg = 32f,
            )
        )
        db.performanceGoalDao.insert(PerformanceGoalEntity(goalText = "TODAYS-DIFFERENT-GOAL"))

        val repo = GatedRepo(db)
        val vm = TestableChatViewModel(repo, SamsungHealthRepository(context))

        vm.requestCheckpointEnrichment(oldCpId)

        // Repeated tap while the first enrichment is in flight → ignored.
        Thread.sleep(300)
        vm.requestCheckpointEnrichment(oldCpId)
        vm.requestCheckpointEnrichment(oldCpId)

        repo.gate.complete(Unit)

        // Wait for the enrichment to land.
        val deadline = System.currentTimeMillis() + 10_000
        var enriched: BodyPlanCheckpointEntity? = null
        while (System.currentTimeMillis() < deadline) {
            val cp = db.bodyPlanCheckpointDao.getById(oldCpId)
            if (cp?.tacticSource != null) enriched = cp
            else Thread.sleep(50)
        }
        assertNotNull("retry must enrich the old checkpoint", enriched)
        assertEquals("exactly one LLM call — repeated taps are debounced", 1, repo.callCount)
        assertEquals("model traceability must persist the actual model", "llm:test-model", enriched!!.tacticSource)
        assertNotNull(enriched.tacticsJson)
        assertNotNull("report must be persisted too", enriched.coachReport)

        // The prompt must be built from the CHECKPOINT's anchored goal —
        // never from today's (different) goal.
        val prompt = repo.lastPrompt
        assertNotNull(prompt)
        assertTrue("prompt must use the anchored goal text", prompt!!.contains("ANCHORED-GOAL-TEXT"))
        assertTrue("prompt must NOT use today's goal", !prompt.contains("TODAYS-DIFFERENT-GOAL"))
    }

    @Test
    fun `retry of a vanished checkpoint surfaces a visible error not a silent return`() = runBlocking {
        val repo = GatedRepo(db)
        val vm = TestableChatViewModel(repo, SamsungHealthRepository(context))

        vm.requestCheckpointEnrichment(999_999L)

        val deadline = System.currentTimeMillis() + 10_000
        var error: String? = null
        while (System.currentTimeMillis() < deadline) {
            error = vm.checkpointEnrichmentErrors.value[999_999L]
            if (error != null) break
            Thread.sleep(50)
        }
        assertEquals("checkpoint_gone", error)
        assertEquals(0, repo.callCount)
    }
}
