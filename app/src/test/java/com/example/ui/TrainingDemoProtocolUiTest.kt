package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.local.BodyPlanCheckpointEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v43.7 (2026-10) — the personal-trainer execution protocol on the demo
 * card: INTENT → FEEL (anatomical figures + mirror rule) → EXECUTION (cue)
 * → AFTER (do / don't). All four sections must render for a valid demoId,
 * in that order, with the muscle-map figures present (the Robolectric
 * default profile gender is "Female" → female figures).
 *
 * Robolectric Compose — same harness as PerformanceBodyPlanUiStateTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The protocol card (4 sections + figures) is taller than the default 640dp
// Robolectric viewport — a 1400dp window lays out the whole card.
@Config(sdk = [34], qualifiers = "w360dp-h1400dp-xxhdpi")
class TrainingDemoProtocolUiTest {

    @get:Rule val compose = createComposeRule()

    private val demoCp = BodyPlanCheckpointEntity(
        id = 1L,
        checkpointAtMs = 1_700_000_000_000L,
        phase = "CUT",
        tacticsJson = """[{"category":"strength","text":"Faz agachamentos com o corpo todo","target":"3 séries de 12 repetições","windowDays":28,"demoId":"squat_bodyweight"}]""",
        tacticSource = "deterministic_fallback",
    )

    @Test
    fun `demo card renders the four protocol sections`() {
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
        // 1. intent
        compose.onNodeWithText(tr("demo_intent_label", "pt").uppercase()).assertIsDisplayed()
        compose.onNodeWithText(tr("demo_squat_bodyweight_intent", "pt")).assertIsDisplayed()
        // 2. feel (label + muscle-map figures + the feel line with mirror rule)
        compose.onNodeWithText(tr("demo_feel_label", "pt").uppercase()).assertIsDisplayed()
        compose.onNodeWithTag("demo_feel_figures").assertIsDisplayed()
        compose.onNodeWithTag("demo_feel_figure_frontal").assertIsDisplayed()
        // v43.13: the glute reads from its canonical view — the back.
        compose.onNodeWithTag("demo_feel_figure_posterior").assertIsDisplayed()
        // v43.8: the views are labeled (the 44dp pair read as "repeated
        // images" without labels) and the tap-to-zoom affordance is stated.
        compose.onNodeWithText(tr("view_frontal", "pt").uppercase()).assertIsDisplayed()
        compose.onNodeWithText(tr("view_posterior", "pt").uppercase()).assertIsDisplayed()
        compose.onNodeWithText(tr("demo_feel_zoom_hint", "pt")).assertIsDisplayed()
        compose.onNodeWithText(tr("demo_squat_bodyweight_feel", "pt")).assertIsDisplayed()
        // 3. execution (existing cue)
        compose.onNodeWithText(tr("demo_squat_bodyweight_cue", "pt")).assertIsDisplayed()
        // 4. after (do + don't)
        compose.onNodeWithText(tr("demo_after_label", "pt").uppercase()).assertIsDisplayed()
        compose.onNodeWithText(tr("demo_squat_bodyweight_after_do", "pt")).assertIsDisplayed()
        compose.onNodeWithText("✗ " + tr("demo_squat_bodyweight_after_dont", "pt"))
            .assertIsDisplayed()
        // regression: the base card chrome is intact
        compose.onNodeWithText(tr("demo_squat_bodyweight_name", "pt")).assertIsDisplayed()
        compose.onNodeWithText(tr("demo_view", "pt")).assertIsDisplayed()
    }

    @Test
    fun `demo card figures use the localized muscle captions`() {
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
        // squat: quads (frontal) + glutes (posterior) — the captions carry
        // the localized muscle names.
        compose.onNodeWithText(tr("muscle_quadriceps", "pt")).assertIsDisplayed()
        compose.onNodeWithText(tr("muscle_glutes", "pt")).assertIsDisplayed()
    }

    @Test
    fun `recovery demo card renders feel figures for its two views`() {
        val stretchCp = BodyPlanCheckpointEntity(
            id = 2L,
            checkpointAtMs = 1_700_000_000_000L,
            phase = "MAINTAIN",
            tacticsJson = """[{"category":"sleep","text":"Dorme o suficiente","target":"7–9 horas","windowDays":28,"demoId":"hip_flexor_stretch"}]""",
            tacticSource = "deterministic_fallback",
        )
        compose.setContent {
            BodyPlanView(
                checkpoints = listOf(stretchCp),
                hasGoalTargets = true,
                lang = "pt",
                enriching = emptySet(),
                errors = emptyMap(),
                onRetry = { },
            )
        }
        compose.onNodeWithTag("demo_feel_figure_frontal").assertIsDisplayed()
        // v43.13: glutes moved to their canonical view (posterior).
        compose.onNodeWithTag("demo_feel_figure_posterior").assertIsDisplayed()
        compose.onNodeWithText(tr("muscle_hip_flexors", "pt")).assertIsDisplayed()
        compose.onNodeWithText(tr("muscle_glutes", "pt")).assertIsDisplayed()
        compose.onNodeWithText(tr("demo_hip_flexor_stretch_after_do", "pt")).assertIsDisplayed()
    }

    @Test
    fun `tapping a figure opens the full-width zoom and tapping closes it`() {
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
        // tap the FRONTAL figure (first tap target in the figures row)
        compose.onAllNodesWithTag("demo_feel_figure_tap").onFirst().performClick()
        // the zoom shows the SAME view, full width, with label + close hint.
        // The view label now exists in BOTH the card and the zoom, so match
        // the (possibly 2) nodes and assert at least one is displayed.
        compose.onNodeWithTag("demo_feel_zoom").assertIsDisplayed()
        compose.onAllNodesWithText(tr("view_frontal", "pt").uppercase())
            .onFirst().assertIsDisplayed()
        compose.onNodeWithText(tr("demo_feel_zoom_close", "pt")).assertIsDisplayed()
        // tapping the zoom dismisses it (tap anywhere closes)
        compose.onNodeWithTag("demo_feel_zoom").performClick()
        compose.onNodeWithTag("demo_feel_zoom").assertDoesNotExist()
    }
}
