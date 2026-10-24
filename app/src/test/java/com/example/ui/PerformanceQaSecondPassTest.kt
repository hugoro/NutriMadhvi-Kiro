package com.example.ui

import com.example.data.chat.PerformanceCoachBuilder
import com.example.data.local.PerformanceSnapshotEntity
import com.example.data.performance.BodyPlanPromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 2026-10 (second pass): locale-correct numbers, no internal phase codes in
 * labels, complete PT/EN coverage for the new second-pass strings, and
 * prompt-language mapping (no silent Portuguese fallback for non-PT users).
 */
class PerformanceQaSecondPassTest {

    // ── Locale-aware display helpers (PerformanceTab.kt) ─────────────────────

    @Test
    fun `roundTo1Ui uses the app locale decimal separator`() {
        assertEquals("0,3", 0.3f.roundTo1Ui("pt"))
        assertEquals("0.3", 0.3f.roundTo1Ui("en"))
        assertEquals("-0,3", (-0.3f).roundTo1Ui("pt"))
        // whole numbers stay integral (no "3,0" noise)
        assertEquals("3", 3.0f.roundTo1Ui("pt"))
        assertEquals("3", 3.0f.roundTo1Ui("en"))
    }

    @Test
    fun `localizeDecimals converts persisted US-style decimals for display`() {
        val stored = "cintura -0.3pp, glúteos +0.2pp"
        assertEquals("cintura -0,3pp, glúteos +0,2pp", localizeDecimals(stored, "pt"))
        // English keeps the US separator — display-only, stored value untouched
        assertEquals(stored, localizeDecimals(stored, "en"))
        // text without decimals passes through unchanged
        assertEquals("sem números", localizeDecimals("sem números", "pt"))
    }

    // ── Phase labels: no internal codes exposed to users ─────────────────────

    @Test
    fun `phase labels carry no internal code prefix in pt and en`() {
        for (lang in listOf("pt", "en")) {
            for (key in listOf("phase_cut", "phase_bulk", "phase_recomp", "phase_maintain")) {
                val label = tr(key, lang)
                assertTrue("$label should be non-blank", label.isNotBlank())
                assertFalse("$label must not embed the code", label.contains(key.removePrefix("phase_").uppercase(), ignoreCase = false))
            }
        }
        assertEquals("Perder gordura", tr("phase_cut", "pt"))
        assertEquals("Fat loss", tr("phase_cut", "en"))
        // unknown codes must have a dedicated fallback label
        val unknown = tr("phase_unknown", "pt")
        assertTrue(unknown.isNotBlank())
        assertTrue(unknown != "phase_unknown")
    }

    // ── New second-pass I18n keys exist in both languages ────────────────────

    @Test
    fun `second-pass keys exist in pt and en`() {
        assertEquals("Relatório a gerar...", tr("progress_report_pending", "pt"))
        assertTrue(tr("progress_report_pending", "en").isNotBlank())
        assertTrue(tr("goal_silhouette_title", "pt").isNotBlank())
        assertTrue(tr("goal_silhouette_no_targets", "pt").isNotBlank())
        assertTrue(tr("goal_silhouette_no_targets", "en").isNotBlank())
        assertTrue(tr("perf_silhouette_comp", "pt").isNotBlank())
        assertTrue(tr("perf_coach_report", "pt").isNotBlank())
        assertTrue(tr("perf_start_assessment", "pt").isNotBlank())
        // Samsung Health status — the "Connect S-Health" button was English-only
        assertTrue(tr("S-Health", "pt").isNotBlank())
        assertTrue(tr("S-Health", "en").isNotBlank())
    }

    // ── Prompt language mapping: no silent Portuguese fallback ───────────────

    @Test
    fun `coach report prompt maps english and other languages explicitly`() {
        val snapshot = PerformanceSnapshotEntity(capturedAtMs = 1_700_000_000_000L)
        val en = PerformanceCoachBuilder.buildPrompt(snapshot, emptyList(), "João", "en")
        assertTrue("EN prompt must name English", en.contains("em English"))
        val fr = PerformanceCoachBuilder.buildPrompt(snapshot, emptyList(), "João", "fr")
        assertTrue("fr prompt must pass the code through", fr.contains("em fr"))
        assertFalse("fr prompt must not force Portuguese", fr.contains("em português"))
        val pt = PerformanceCoachBuilder.buildPrompt(snapshot, emptyList(), "João", "pt-PT")
        assertTrue("PT prompt must name Portuguese", pt.contains("em português"))
    }

    @Test
    fun `tactics prompt maps english and portuguese explicitly`() {
        val en = BodyPlanPromptBuilder.buildTacticsPrompt(null, emptyList(), "perder peso", null, "João", "en")
        assertTrue("EN tactics prompt must name English", en.contains("in English"))
        assertFalse("EN tactics prompt must not use the raw code", en.contains("Escreve em en"))
        val pt = BodyPlanPromptBuilder.buildTacticsPrompt(null, emptyList(), "perder peso", null, "João", "pt-PT")
        assertTrue("PT tactics prompt must name Portuguese", pt.contains("português (Portugal)"))
        val other = BodyPlanPromptBuilder.buildTacticsPrompt(null, emptyList(), "perder peso", null, "João", "es")
        assertTrue("other languages pass the code through", other.contains("in es"))
        assertNotNull(en)
    }
}
