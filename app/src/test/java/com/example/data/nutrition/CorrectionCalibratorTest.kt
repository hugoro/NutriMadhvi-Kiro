package com.example.data.nutrition

import com.example.data.local.MealCorrectionDao
import com.example.data.local.MealCorrectionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [CorrectionCalibrator]: directional-bias math (median,
 * confidence weighting, N-threshold gate, trivial-bias suppression) and
 * recurrent ingredient-substitution detection. Uses a hand-written fake DAO —
 * no Room, no Mockito.
 */
class CorrectionCalibratorTest {

    /** In-memory fake — the calibrator only reads (listAll/count). */
    private class FakeDao(private val rows: List<MealCorrectionEntity>) : MealCorrectionDao {
        override suspend fun insert(correction: MealCorrectionEntity): Long = 0
        override suspend fun listAll(): List<MealCorrectionEntity> = rows
        override suspend fun count(): Int = rows.size
        override suspend fun trimTo(keep: Int) {}
    }

    private fun calibrator(rows: List<MealCorrectionEntity>) = CorrectionCalibrator(FakeDao(rows))

    private fun row(
        aiCalories: Int? = null,
        userCalories: Int? = null,
        aiProtein: Double? = null,
        userProtein: Double? = null,
        confidence: String? = "high",
        aiIngredientsJson: String? = null,
        userIngredientsJson: String? = null,
    ) = MealCorrectionEntity(
        createdAtMs = 0,
        initialConfidence = confidence,
        aiCalories = aiCalories,
        userCalories = userCalories,
        aiProtein = aiProtein,
        userProtein = userProtein,
        aiIngredientsJson = aiIngredientsJson,
        userIngredientsJson = userIngredientsJson,
    )

    // --- empty ---------------------------------------------------------------

    @Test
    fun emptyTable_yieldsEmptyCalibration() = runTest {
        val cal = calibrator(emptyList()).getCalibration()
        assertTrue(cal.isEmpty)
        assertEquals("", cal.toPromptBlock())
    }

    // --- N-threshold gate ----------------------------------------------------

    @Test
    fun fewerThanThreshold_noMacroBias() = runTest {
        // 4 corrections < MIN_MACRO_SAMPLES (5) → no calorie bias surfaces.
        val rows = List(4) { row(aiCalories = 700, userCalories = 500) }
        val cal = calibrator(rows).getCalibration()
        assertNull("bias must not surface below N-threshold", cal.calorieBiasPct)
    }

    @Test
    fun atThreshold_biasSurfaces() = runTest {
        // 5 corrections, AI consistently 40% high (700 vs 500).
        val rows = List(5) { row(aiCalories = 700, userCalories = 500) }
        val cal = calibrator(rows).getCalibration()
        assertTrue("bias should surface at N-threshold", cal.calorieBiasPct != null)
        // (700-500)/500 = +0.40
        assertEquals(0.40, cal.calorieBiasPct!!, 0.001)
    }

    // --- direction -----------------------------------------------------------

    @Test
    fun overEstimate_positiveBias_promptSaysBiasDown() = runTest {
        val rows = List(5) { row(aiCalories = 600, userCalories = 500) } // +20%
        val cal = calibrator(rows).getCalibration()
        assertTrue(cal.calorieBiasPct!! > 0)
        val block = cal.toPromptBlock()
        assertTrue(block.contains("HIGH"))
        assertTrue(block.contains("bias it DOWN"))
    }

    @Test
    fun underEstimate_negativeBias_promptSaysBiasUp() = runTest {
        val rows = List(5) { row(aiCalories = 400, userCalories = 500) } // -20%
        val cal = calibrator(rows).getCalibration()
        assertTrue(cal.calorieBiasPct!! < 0)
        val block = cal.toPromptBlock()
        assertTrue(block.contains("LOW"))
        assertTrue(block.contains("bias it UP"))
    }

    // --- median robustness ---------------------------------------------------

    @Test
    fun medianIgnoresSingleOutlier() = runTest {
        // Four at +20%, one wild outlier at +200% (fat-fingered). Median stays
        // near +20%, not dragged up by the outlier a mean would chase.
        val rows = listOf(
            row(aiCalories = 600, userCalories = 500),
            row(aiCalories = 600, userCalories = 500),
            row(aiCalories = 600, userCalories = 500),
            row(aiCalories = 600, userCalories = 500),
            row(aiCalories = 1500, userCalories = 500), // +200%
        )
        val cal = calibrator(rows).getCalibration()
        assertEquals(0.20, cal.calorieBiasPct!!, 0.05)
    }

    // --- trivial-bias suppression --------------------------------------------

    @Test
    fun trivialBias_suppressed() = runTest {
        // 3% median is below MIN_MEANINGFUL_BIAS (8%) → not surfaced.
        val rows = List(6) { row(aiCalories = 515, userCalories = 500) } // +3%
        val cal = calibrator(rows).getCalibration()
        assertNull("a ~3% bias is noise and must be suppressed", cal.calorieBiasPct)
    }

    // --- confidence weighting ------------------------------------------------

    @Test
    fun highConfidenceCorrectionsDominateMedian() = runTest {
        // 2 low-confidence corrections say +10%, 3 high-confidence say +50%.
        // High weight ×3 each → the weighted median lands on the high side.
        val rows = listOf(
            row(aiCalories = 550, userCalories = 500, confidence = "low"),  // +10%
            row(aiCalories = 550, userCalories = 500, confidence = "low"),  // +10%
            row(aiCalories = 750, userCalories = 500, confidence = "high"), // +50%
            row(aiCalories = 750, userCalories = 500, confidence = "high"), // +50%
            row(aiCalories = 750, userCalories = 500, confidence = "high"), // +50%
        )
        val cal = calibrator(rows).getCalibration()
        assertTrue(
            "high-confidence corrections should dominate the weighted median",
            cal.calorieBiasPct!! >= 0.40
        )
    }

    // --- ingredient substitution ---------------------------------------------

    @Test
    fun recurrentSubstitution_detected() = runTest {
        val aiJson = """[{"name":"pão de massa mãe","weightGrams":60.0}]"""
        val userJson = """[{"name":"tosta","weightGrams":10.0}]"""
        // Same substitution 2× → surfaces (MIN_SUBSTITUTION_COUNT = 2).
        val rows = listOf(
            row(aiIngredientsJson = aiJson, userIngredientsJson = userJson),
            row(aiIngredientsJson = aiJson, userIngredientsJson = userJson),
        )
        val cal = calibrator(rows).getCalibration()
        assertEquals(1, cal.substitutions.size)
        val sub = cal.substitutions.first()
        assertEquals("pão de massa mãe", sub.from)
        assertEquals("tosta", sub.to)
        assertEquals(2, sub.count)
        assertTrue(cal.toPromptBlock().contains("tosta"))
    }

    @Test
    fun oneOffSubstitution_notSurfaced() = runTest {
        // A substitution seen only once is below MIN_SUBSTITUTION_COUNT.
        val rows = listOf(
            row(
                aiIngredientsJson = """[{"name":"pão","weightGrams":60.0}]""",
                userIngredientsJson = """[{"name":"tosta","weightGrams":10.0}]""",
            )
        )
        val cal = calibrator(rows).getCalibration()
        assertTrue("a one-off substitution must not surface", cal.substitutions.isEmpty())
    }

    @Test
    fun ambiguousMultiChange_notCountedAsSubstitution() = runTest {
        // Two dropped + two added → ambiguous, not a clean 1:1 substitution.
        val aiJson = """[{"name":"pão","weightGrams":60.0},{"name":"queijo","weightGrams":30.0}]"""
        val userJson = """[{"name":"tosta","weightGrams":10.0},{"name":"fiambre","weightGrams":20.0}]"""
        val rows = List(3) { row(aiIngredientsJson = aiJson, userIngredientsJson = userJson) }
        val cal = calibrator(rows).getCalibration()
        assertTrue("ambiguous multi-change must not be a substitution", cal.substitutions.isEmpty())
    }

    // --- cache staleness -----------------------------------------------------

    @Test
    fun caches_untilInvalidated() = runTest {
        val cal = calibrator(List(5) { row(aiCalories = 600, userCalories = 500) })
        val first = cal.getCalibration()
        val second = cal.getCalibration()
        // Same instance returned from cache (count unchanged).
        assertTrue(first === second)
    }

    // --- sampleSize in prompt ------------------------------------------------

    @Test
    fun promptBlock_reportsSampleSize() = runTest {
        val rows = List(6) { row(aiCalories = 700, userCalories = 500) }
        val block = calibrator(rows).getCalibration().toPromptBlock()
        assertTrue(block.contains("6 past corrections"))
        assertFalse(block.isBlank())
    }
}
