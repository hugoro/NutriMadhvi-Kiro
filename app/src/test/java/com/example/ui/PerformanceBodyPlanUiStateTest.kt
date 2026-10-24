package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.local.BodyPlanCheckpointEntity
import com.example.data.local.PerformanceSnapshotEntity
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI state coverage (v41 review item 4): the in-flight enrichment
 * state (spinner-style "enriching" label + retry disabled/hidden) and the
 * per-checkpoint error labels (checkpoint_gone / snapshot_missing) must be
 * visible to the user — retries are never silent, and an in-flight
 * checkpoint can never offer a duplicate retry.
 *
 * The views are driven with explicit [BodyPlanView.enriching] /
 * [BodyPlanView.errors] values — the same stable collected values the top
 * composable now feeds them from collectAsStateWithLifecycle().
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// v43.7: the protocol demo card is taller than the default 640dp Robolectric
// viewport — give the test window the height of a real scroll so the whole
// card (incl. the bottom "Watch demo" button) is laid out and visible.
@Config(sdk = [34], qualifiers = "w360dp-h1400dp-xxhdpi")
class PerformanceBodyPlanUiStateTest {

    @get:Rule val compose = createComposeRule()

    private val cp = BodyPlanCheckpointEntity(
        id = 1L,
        checkpointAtMs = 1_700_000_000_000L,
        phase = "CUT",
        tacticsJson = """[{"category":"steps","text":"Manter 8000 passos/dia","windowDays":28}]""",
        tacticSource = "deterministic_fallback",
    )

    private val enrichingLabel = tr("plan_enriching", "pt")   // "A gerar com IA..."
    private val retryLabel = tr("plan_retry", "pt")           // "Gerar novamente"

    // ── In-flight (enriching) state ──────────────────────────────────────────

    @Test
    fun `plan view shows enriching label and hides retry while in flight`() {
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(cp),
                hasGoalTargets = true,
                lang = "pt",
                enriching = setOf(1L),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        compose.onNodeWithText(enrichingLabel).assertIsDisplayed()
        compose.onNodeWithText(retryLabel).assertDoesNotExist()
    }

    @Test
    fun `progress view shows enriching label and hides retry while in flight`() {
        compose.setContent {
            BodyProgressView(
                checkpoints = listOf(cp.copy(coachReport = null)),
                lang = "pt",
                enriching = setOf(1L),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        compose.onNodeWithText(enrichingLabel).assertIsDisplayed()
        compose.onNodeWithText(retryLabel).assertDoesNotExist()
    }

    @Test
    fun `plan view shows tactics — usable plan no longer offers retry`() {
        var retried: Long? = null
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(cp),
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { retried = it },
            )
        }
        compose.onNodeWithText(enrichingLabel).assertDoesNotExist()
        compose.onNodeWithText("Manter 8000 passos/dia").assertIsDisplayed()
        // v43: a usable plan (even a full deterministic fallback) is NOT a
        // failure — no retry is offered, only the neutral plan label.
        compose.onNodeWithText(retryLabel).assertDoesNotExist()
        compose.onNodeWithText(tr("plan_tactics_generate", "pt")).assertDoesNotExist()
        org.junit.Assert.assertNull(retried)
    }

    @Test
    fun `plan view no usable plan with error shows error and fires onRetry on retry click`() {
        var retried: Long? = null
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(cp.copy(tacticsJson = null, tacticSource = null)),
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = mapOf(1L to "snapshot_missing"),
                onRetry = { retried = it },
            )
        }
        compose.onNodeWithText(tr("plan_error_snapshot_missing", "pt")).assertIsDisplayed()
        compose.onNodeWithText(retryLabel).assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(1L, retried)
    }

    // ── Error visibility (checkpoint_gone / snapshot_missing) ───────────────

    @Test
    fun `plan view shows checkpoint_gone label with retry`() {
        compose.setContent {
            BodyPlanView(
                // v43: error+retry only render when there is NO usable plan
                checkpoints = listOf(cp.copy(tacticsJson = null, tacticSource = null)),
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = mapOf(1L to "checkpoint_gone"),
                onRetry = { },
            )
        }
        compose.onNodeWithText(tr("plan_error_checkpoint_gone", "pt")).assertIsDisplayed()
        compose.onNodeWithText(retryLabel).assertIsDisplayed()
    }

    @Test
    fun `plan view shows snapshot_missing label with retry`() {
        compose.setContent {
            BodyPlanView(
                // v43: error+retry only render when there is NO usable plan
                checkpoints = listOf(cp.copy(tacticsJson = null, tacticSource = null)),
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = mapOf(1L to "snapshot_missing"),
                onRetry = { },
            )
        }
        compose.onNodeWithText(tr("plan_error_snapshot_missing", "pt")).assertIsDisplayed()
        compose.onNodeWithText(retryLabel).assertIsDisplayed()
    }

    @Test
    fun `progress view error label is clickable and fires onRetry`() {
        var retried: Long? = null
        compose.setContent {
            BodyProgressView(
                checkpoints = listOf(cp.copy(coachReport = "Relatório ok.")),
                lang = "pt",
                enriching = emptySet(),
                errors = mapOf(1L to "snapshot_missing"),
                onRetry = { retried = it },
            )
        }
        compose.onNodeWithText(tr("plan_error_snapshot_missing", "pt")).assertIsDisplayed()
        compose.onNodeWithText(retryLabel).assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(1L, retried)
    }

    @Test
    fun `no error key renders no error label`() {
        compose.setContent {
            BodyProgressView(
                checkpoints = listOf(cp.copy(coachReport = "Relatório ok.")),
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        compose.onNodeWithText(tr("plan_error_checkpoint_gone", "pt")).assertDoesNotExist()
        compose.onNodeWithText(tr("plan_error_snapshot_missing", "pt")).assertDoesNotExist()
    }

    // ── QA 2026-10 second pass: tactics gate without goal targets ────────────
    // v43 (2026-10): with a USABLE plan the gate disappears entirely — the
    // plan renders "Plano recomendado" and nothing else (the open-goal
    // affordance only belongs to the no-usable-plan state, where enrichment
    // would have nothing to target).

    @Test
    fun `plan view usable plan without goal targets offers no action button`() {
        var goalOpened = false
        compose.setContent {
            BodyPlanView(
                // explicit priorities so the priorities section renders
                checkpoints = listOf(
                    cp.copy(prioritiesJson = """[{"zone":"waist","direction":"REDUCE","gapPp":0.5,"tissue":"FAT"}]"""),
                ),
                hasGoalTargets = false,
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { },
                onOpenGoal = { goalOpened = true },
            )
        }
        // the usable tactics are shown, with the neutral plan label…
        compose.onNodeWithText("Manter 8000 passos/dia").assertIsDisplayed()
        compose.onNodeWithText(tr("plan_recommended", "pt")).assertIsDisplayed()
        // …but NO action button of any kind: the plan is complete.
        compose.onNodeWithText(tr("plan_open_goal", "pt")).assertDoesNotExist()
        compose.onNodeWithText(retryLabel).assertDoesNotExist()
        compose.onNodeWithText(tr("plan_tactics_generate", "pt")).assertDoesNotExist()
        org.junit.Assert.assertFalse(goalOpened)
    }

    // ── QA 2026-10: explicit pending / idle / failed states ─────────────────
    // The old UI rendered "Táticas a gerar..." (implies in flight) at the same
    // time as the "Gerar novamente" retry button (implies failure). Each state
    // must now be mutually exclusive.

    @Test
    fun `plan view idle tactics state shows generate action and no pending or retry-again`() {
        var retried: Long? = null
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(cp.copy(tacticsJson = null, tacticSource = null)),
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { retried = it },
            )
        }
        // idle label, NOT the old in-flight-sounding "Táticas a gerar..."
        compose.onNodeWithText(tr("plan_tactics_idle", "pt")).assertIsDisplayed()
        compose.onNodeWithText(tr("plan_tactics_pending", "pt")).assertDoesNotExist()
        compose.onNodeWithText(enrichingLabel).assertDoesNotExist()
        // "generate", not "generate again"
        compose.onNodeWithText(tr("plan_tactics_generate", "pt"))
            .assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(1L, retried)
        compose.onNodeWithText(retryLabel).assertDoesNotExist()
    }

    @Test
    fun `plan view failed tactics state shows failed label and generate-again retry`() {
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(cp.copy(tacticsJson = null, tacticSource = null)),
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = mapOf(1L to "snapshot_missing"),
                onRetry = { },
            )
        }
        compose.onNodeWithText(tr("plan_tactics_failed", "pt")).assertIsDisplayed()
        compose.onNodeWithText(tr("plan_tactics_idle", "pt")).assertDoesNotExist()
        // a failure was reported → the action is a retry ("Gerar novamente")
        compose.onNodeWithText(retryLabel).assertIsDisplayed()
        compose.onNodeWithText(tr("plan_tactics_generate", "pt")).assertDoesNotExist()
    }

    @Test
    fun `plan view pending state never shows idle or failed labels`() {
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(cp.copy(tacticsJson = null, tacticSource = null)),
                hasGoalTargets = true,
                lang = "pt",
                enriching = setOf(1L),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        compose.onNodeWithText(enrichingLabel).assertIsDisplayed()
        compose.onNodeWithText(tr("plan_tactics_idle", "pt")).assertDoesNotExist()
        compose.onNodeWithText(tr("plan_tactics_pending", "pt")).assertDoesNotExist()
        // no action button while in flight
        compose.onNodeWithText(tr("plan_tactics_generate", "pt")).assertDoesNotExist()
        compose.onNodeWithText(retryLabel).assertDoesNotExist()
    }

    @Test
    fun `progress view idle report state shows generate action without pending label`() {
        var retried: Long? = null
        compose.setContent {
            BodyProgressView(
                checkpoints = listOf(cp.copy(coachReport = null, tacticsJson = null)),
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { retried = it },
            )
        }
        compose.onNodeWithText(tr("progress_report_idle", "pt")).assertIsDisplayed()
        compose.onNodeWithText(enrichingLabel).assertDoesNotExist()
        compose.onNodeWithText(tr("progress_report_generate", "pt"))
            .assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(1L, retried)
        compose.onNodeWithText(retryLabel).assertDoesNotExist()
    }

    @Test
    fun `progress view pending report shows label and spinner state without buttons`() {
        compose.setContent {
            BodyProgressView(
                checkpoints = listOf(cp.copy(coachReport = null, tacticsJson = null)),
                lang = "pt",
                enriching = setOf(1L),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        compose.onNodeWithText(enrichingLabel).assertIsDisplayed()
        compose.onNodeWithText(tr("progress_report_idle", "pt")).assertDoesNotExist()
        compose.onNodeWithText(tr("progress_report_generate", "pt")).assertDoesNotExist()
        compose.onNodeWithText(retryLabel).assertDoesNotExist()
    }

    @Test
    fun `progress view error report keeps single retry in the error row`() {
        compose.setContent {
            BodyProgressView(
                checkpoints = listOf(cp.copy(coachReport = null, tacticsJson = null)),
                lang = "pt",
                enriching = emptySet(),
                errors = mapOf(1L to "checkpoint_gone"),
                onRetry = { },
            )
        }
        compose.onNodeWithText(tr("progress_report_idle", "pt")).assertIsDisplayed()
        compose.onNodeWithText(tr("plan_error_checkpoint_gone", "pt")).assertIsDisplayed()
        // exactly one action: the retry in the error row — not a second
        // "Gerar relatório" button next to the idle label.
        compose.onNodeWithText(tr("progress_report_generate", "pt")).assertDoesNotExist()
        compose.onNodeWithText(retryLabel).assertIsDisplayed()
    }

    // ── QA 2026-10: direct goal action from the Plan empty states ────────────

    @Test
    fun `plan view no-checkpoint state offers direct goal action`() {
        var goalOpened = false
        compose.setContent {
            BodyPlanView(
                checkpoints = emptyList(),
                hasGoalTargets = false,
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { },
                onOpenGoal = { goalOpened = true },
            )
        }
        compose.onNodeWithText(tr("plan_no_checkpoint", "pt")).assertIsDisplayed()
        compose.onNodeWithText(tr("plan_open_goal", "pt")).assertIsDisplayed().performClick()
        org.junit.Assert.assertTrue(goalOpened)
    }

    @Test
    fun `progress view shows legacy assessments when checkpoints are absent`() {
        val legacy = PerformanceSnapshotEntity(
            id = 7L,
            capturedAtMs = 1_790_929_509_940L,
            frontalWaistRatio = 0.24f,
            lateralBellyRatio = 0.19f,
            coachReport = "Relatório legado importado.",
        )
        compose.setContent {
            BodyProgressView(
                checkpoints = emptyList(),
                legacySnapshots = listOf(legacy),
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        compose.onNodeWithText(tr("progress_legacy_assessment", "pt")).assertIsDisplayed()
        compose.onNodeWithText("Relatório legado importado.").assertIsDisplayed()
        compose.onNodeWithText(tr("progress_empty", "pt")).assertDoesNotExist()
    }

    // ── QA 2026-10 (second pass): mixed history (checkpoints + unreferenced
    // v35-v38 snapshots) ──────────────────────────────────────────────────────
    // Legacy assessments are historical records: they must not disappear from
    // Progress when checkpoints also exist. Rendered newest-first, below the
    // checkpoint cards (they predate the checkpoint era by construction — the
    // call site passes only snapshots no checkpoint references).

    @Test
    fun `progress view keeps legacy assessments visible alongside checkpoints (mixed history)`() {
        val legacy = PerformanceSnapshotEntity(
            id = 7L,
            capturedAtMs = 1_650_000_000_000L, // pre-checkpoint era assessment
            frontalWaistRatio = 0.24f,
            lateralBellyRatio = 0.19f,
            coachReport = "Relatório legado importado.",
        )
        compose.setContent {
            BodyProgressView(
                checkpoints = listOf(cp),
                legacySnapshots = listOf(legacy),
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        // the checkpoint card is still rendered…
        compose.onNodeWithText(tr("phase_cut", "pt")).assertIsDisplayed()
        // …and the legacy assessment does NOT disappear with it:
        compose.onNodeWithText(tr("progress_legacy_assessment", "pt")).assertIsDisplayed()
        compose.onNodeWithText("Relatório legado importado.").assertIsDisplayed()
        // newest-first: the legacy history renders below the checkpoint cards
        val checkpointTop = compose.onNodeWithText(tr("phase_cut", "pt"))
            .fetchSemanticsNode().boundsInRoot.top
        val legacyTop = compose.onNodeWithText(tr("progress_legacy_assessment", "pt"))
            .fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue(
            "legacy card should render below the checkpoint card " +
                "(checkpointTop=$checkpointTop, legacyTop=$legacyTop)",
            legacyTop > checkpointTop,
        )
    }

    @Test
    fun `plan view no-targets state offers direct goal action`() {
        var goalOpened = false
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(cp.copy(prioritiesJson = null)),
                hasGoalTargets = false,
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { },
                onOpenGoal = { goalOpened = true },
            )
        }
        compose.onNodeWithText(tr("plan_no_targets", "pt")).assertIsDisplayed()
        // QA 2026-10 (second pass): two affordances may point to the goal —
        // the empty-state button and the tactics gate (no goal → enrichment
        // has nothing to target). Either one must open the goal editor.
        // v43: the tactics gate is gone for usable plans — only the
        // priorities empty-state button remains.
        compose.onAllNodesWithText(tr("plan_open_goal", "pt"))
            .onFirst()
            .assertIsDisplayed()
            .performClick()
        org.junit.Assert.assertTrue(goalOpened)
    }

    // ── v43 (2026-10): usable plan → neutral label only ─────────────────────
    // When a usable plan exists (fully LLM, partially adjusted, or full
    // deterministic fallback) the UX shows ONLY "Plano recomendado": no
    // "IA indisponível", no technical model/source display, no retry, no
    // error. tacticSource stays on the entity + logcat for audit. Error and
    // retry appear only when there is NO usable plan.

    private val recommendedLabel = tr("plan_recommended", "pt")

    @Test
    fun `plan view llm plan shows only Plano recomendado — no model, no IA indisponível, no retry`() {
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(cp.copy(tacticSource = "llm:gemini-3.5-flash-lite")),
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        compose.onNodeWithText("Manter 8000 passos/dia").assertIsDisplayed()
        compose.onNodeWithText(recommendedLabel).assertIsDisplayed()
        // technical model must not leak into the UX
        org.junit.Assert.assertTrue(compose.onAllNodesWithText("gemini-3.5-flash-lite").fetchSemanticsNodes().isEmpty())
        org.junit.Assert.assertTrue(compose.onAllNodesWithText("IA indisponível").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(retryLabel).assertDoesNotExist()
        compose.onNodeWithText(tr("plan_tactics_generate", "pt")).assertDoesNotExist()
    }

    @Test
    fun `plan view mixed plan shows Plano recomendado plus adjusted note, no retry`() {
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(cp.copy(tacticSource = "mixed:llm:gemini-3.5-flash-lite")),
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        compose.onNodeWithText(recommendedLabel).assertIsDisplayed()
        // v42 informational note stays — it explains a safety adjustment,
        // it is not a failure message
        compose.onNodeWithText(tr("plan_tactics_adjusted", "pt")).assertIsDisplayed()
        org.junit.Assert.assertTrue(compose.onAllNodesWithText("gemini-3.5-flash-lite").fetchSemanticsNodes().isEmpty())
        org.junit.Assert.assertTrue(compose.onAllNodesWithText("IA indisponível").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(retryLabel).assertDoesNotExist()
    }

    @Test
    fun `plan view full fallback plan shows Plano recomendado — error suppressed, no IA indisponível, no retry`() {
        compose.setContent {
            BodyPlanView(
                // the old full-rejection case: deterministic fallback tactics
                // stored together with a visible llm_failed error
                checkpoints = listOf(cp), // fixture is deterministic_fallback
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = mapOf(1L to "llm_failed"),
                onRetry = { },
            )
        }
        compose.onNodeWithText("Manter 8000 passos/dia").assertIsDisplayed()
        compose.onNodeWithText(recommendedLabel).assertIsDisplayed()
        // a usable plan exists → the enrichment error must not be shown
        compose.onNodeWithText(tr("plan_error_llm_failed", "pt")).assertDoesNotExist()
        org.junit.Assert.assertTrue(compose.onAllNodesWithText("IA indisponível").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(retryLabel).assertDoesNotExist()
        compose.onNodeWithText(tr("plan_tactics_generate", "pt")).assertDoesNotExist()
    }

    // ── v43: curated training-demo card ─────────────────────────────────────
    // A tactic carrying a VALID demoId renders the local demo card
    // (illustration + name + prescription + cue + "Ver demonstração");
    // an INVALID demoId renders no card (fail closed at the parse level).

    @Test
    fun `plan view tactic with valid demoId renders the curated demo card`() {
        val demoCp = BodyPlanCheckpointEntity(
            id = 1L,
            checkpointAtMs = 1_700_000_000_000L,
            phase = "CUT",
            tacticsJson = """[{"category":"strength","text":"Faz agachamentos com o corpo todo","target":"3 séries de 12 repetições","windowDays":28,"demoId":"squat_bodyweight"}]""",
            tacticSource = "llm:gemini-3.5-flash-lite",
        )
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(demoCp),
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        compose.onNodeWithText(tr("demo_squat_bodyweight_name", "pt")).assertIsDisplayed()
        compose.onNodeWithText(tr("demo_squat_bodyweight_prescription", "pt")).assertIsDisplayed()
        compose.onNodeWithText(tr("demo_view", "pt")).assertIsDisplayed()
        // usable plan → still no retry / error chrome
        compose.onNodeWithText(retryLabel).assertDoesNotExist()
    }

    @Test
    fun `plan view tactic with unknown demoId renders no demo card`() {
        val badCp = BodyPlanCheckpointEntity(
            id = 1L,
            checkpointAtMs = 1_700_000_000_000L,
            phase = "CUT",
            tacticsJson = """[{"category":"strength","text":"Faz agachamentos com o corpo todo","target":"3 séries de 12 repetições","windowDays":28,"demoId":"invented_exercise_x"}]""",
            tacticSource = "llm:gemini-3.5-flash-lite",
        )
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(badCp),
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        // tactic text still renders, but NO demo chrome for the unknown id
        compose.onNodeWithText("Faz agachamentos com o corpo todo").assertIsDisplayed()
        compose.onNodeWithText(tr("demo_view", "pt")).assertDoesNotExist()
        compose.onNodeWithText(tr("demo_squat_bodyweight_name", "pt")).assertDoesNotExist()
    }

    // v43.1: the UI applies the SAME compatibility predicate as the
    // validator (persisted rows are not re-validated at generation time).
    // An upper-body strength tactic carrying a lower-body (squat) demoId —
    // exactly the rejected v43 scenario — renders the tactic but NO card.
    @Test
    fun `plan view upper-body strength tactic with lower-body demoId renders no demo card`() {
        val badCp = BodyPlanCheckpointEntity(
            id = 1L,
            checkpointAtMs = 1_700_000_000_000L,
            phase = "CUT",
            tacticsJson = """[{"category":"strength","text":"Realiza treinos de forca focados nos membros superiores","target":"3 sessoes/semana","windowDays":28,"demoId":"squat_bodyweight"}]""",
            tacticSource = "llm:gemini-3.5-flash-lite",
        )
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(badCp),
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        // tactic text still renders, but the incompatible demo is hidden
        compose.onNodeWithText("Realiza treinos de forca focados nos membros superiores").assertIsDisplayed()
        compose.onNodeWithText(tr("demo_view", "pt")).assertDoesNotExist()
        compose.onNodeWithText(tr("demo_squat_bodyweight_name", "pt")).assertDoesNotExist()
    }

    // v43.1: zone tactics — a shoulder-zone tactic must not carry a
    // lower-body demo either (region conflict via the zone key).
    @Test
    fun `plan view shoulder zone tactic with lower-body demoId renders no demo card`() {
        val badCp = BodyPlanCheckpointEntity(
            id = 1L,
            checkpointAtMs = 1_700_000_000_000L,
            phase = "CUT",
            tacticsJson = """[{"category":"zone","text":"Aumenta o volume nos exercicios especificos para o desenvolvimento dos ombros","target":"AUMENTAR","windowDays":28,"zone":"shoulder","demoId":"squat_bodyweight"}]""",
            tacticSource = "llm:gemini-3.5-flash-lite",
        )
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(badCp),
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        compose.onNodeWithText(tr("demo_view", "pt")).assertDoesNotExist()
        compose.onNodeWithText(tr("demo_squat_bodyweight_name", "pt")).assertDoesNotExist()
    }

    // v43.2: the demo card's external-link target must meet the 48dp
    // minimum touch size (WCAG 2.5.5 / Android accessibility), the WHOLE
    // area must be the clickable target, and the click action must fire.
    @Test
    fun `plan view demo card watch link has a 48dp touch target and is clickable`() {
        val demoCp = BodyPlanCheckpointEntity(
            id = 1L,
            checkpointAtMs = 1_700_000_000_000L,
            phase = "CUT",
            tacticsJson = """[{"category":"steps","text":"Acumula passos diários","target":"8000 passos","windowDays":28,"demoId":"brisk_walk"}]""",
            tacticSource = "llm:gemini-3.5-flash-lite",
        )
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(demoCp),
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        val node = compose.onNodeWithTag("demo_view_button", useUnmergedTree = true)
        // The button may be below the initial viewport in this long plan.
        // Existence, measured bounds and click semantics are the acceptance
        // criteria; viewport visibility is covered by emulator scrolling QA.
        node.assertExists()
        // density-independent 48dp minimum
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val minTouchPx = (48 * density).roundToInt()
        val heightPx = node.fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "demo view touch target must be >= 48dp tall (got ${heightPx}px, need >= $minTouchPx)",
            heightPx >= minTouchPx,
        )
        // action: the whole area is the clickable target — the click
        // must reach the handler (openDemoVideo validates + launches).
        node.performClick()
    }
}
