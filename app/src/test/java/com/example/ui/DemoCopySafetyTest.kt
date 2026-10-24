package com.example.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v43.2: squat demo copy safety (independent review finding 3).
 *
 * The fixed-depth target "coxa paralela ao chão / thigh parallel to the
 * floor" is a FORM TARGET, not a safety rule — a user who cannot reach
 * parallel should not try to force it. The cue must instead describe a
 * COMFORTABLE, CONTROLLED, PAIN-FREE range, optional chair/support, and an
 * explicit stop rule for sharp/increasing pain. The progression must be
 * conditioned on solid, pain-free technique — no automatic pause at the
 * bottom.
 *
 * These tests pin that copy rule across all three languages.
 */
class DemoCopySafetyTest {

    /** Forbidden anywhere in the squat cue+progression, any language. */
    private val FORBIDDEN = listOf(
        "paralela", "paralelo", "parallel",
    )

    /** Forbidden only in the PROGRESSION (no automatic pause at bottom). */
    private val FORBIDDEN_IN_PROGRESSION = listOf(
        "pausa", "pause",
    )

    /** Required safety terms per language: (cue, progression). */
    private val REQUIRED = mapOf(
        "pt" to listOf(
            listOf("sem dor", "controle", "cadeira", "aguda"),
            listOf("sem dor", "técnica"),
        ),
        "en" to listOf(
            listOf("pain-free", "control", "chair", "sharp"),
            listOf("pain-free", "form"),
        ),
        "gu" to listOf(
            listOf("દરદ વગર", "નિયંત્રિત", "ખુરશી", "તીવ્ર"),
            listOf("દરદ વગર", "તકનીક"),
        ),
    )

    private fun lower(s: String): String = s.lowercase()

    @Test
    fun `squat cue contains no fixed depth target in any language`() {
        for ((lang, _) in REQUIRED) {
            val cue = lower(tr("demo_squat_bodyweight_cue", lang))
            val bad = FORBIDDEN.filter { it in cue }
            assertTrue(
                "$lang cue must not name a fixed depth target ($bad): $cue",
                bad.isEmpty(),
            )
        }
    }

    @Test
    fun `squat progression contains no fixed depth target or automatic pause`() {
        for ((lang, _) in REQUIRED) {
            val prog = lower(tr("demo_squat_bodyweight_progression", lang))
            val bad = (FORBIDDEN + FORBIDDEN_IN_PROGRESSION).filter { it in prog }
            assertTrue(
                "$lang progression must not name a fixed depth target or an automatic " +
                    "bottom pause ($bad): $prog",
                bad.isEmpty(),
            )
        }
    }

    @Test
    fun `squat cue requires comfort and safety terms in every language`() {
        for ((lang, req) in REQUIRED) {
            val cue = lower(tr("demo_squat_bodyweight_cue", lang))
            val missing = req[0].filter { it !in cue }
            assertTrue(
                "$lang cue missing safety terms $missing: $cue",
                missing.isEmpty(),
            )
        }
    }

    @Test
    fun `squat progression is conditioned on pain-free technique in every language`() {
        for ((lang, req) in REQUIRED) {
            val prog = lower(tr("demo_squat_bodyweight_progression", lang))
            val missing = req[1].filter { it !in prog }
            assertTrue(
                "$lang progression missing terms $missing: $prog",
                missing.isEmpty(),
            )
        }
    }

    @Test
    fun `walk and stretch cues do not prescribe fixed exercise depth`() {
        val fixedDepthTerms = listOf("coxa paralela", "thigh parallel")
        for (id in listOf("demo_brisk_walk", "demo_hip_flexor_stretch")) {
            for (lang in REQUIRED.keys) {
                val cue = lower(tr("${id}_cue", lang))
                val bad = fixedDepthTerms.filter { it in cue }
                assertTrue(
                    "$id cue ($lang) must not prescribe a fixed exercise depth ($bad): $cue",
                    bad.isEmpty(),
                )
            }
        }
    }
}
