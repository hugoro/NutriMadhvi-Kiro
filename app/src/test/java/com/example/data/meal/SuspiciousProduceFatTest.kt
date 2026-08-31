package com.example.data.meal

import com.example.data.remote.FoodAnalysisResult
import com.example.data.remote.IngredientDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the suspicious-produce-fat escalation heuristic added 2026-07-10
 * (papaya-1.4g case). A confidently-`high` Flash result that puts real fat
 * on low-fat whole produce must be flagged so the orchestrator gets a Pro
 * second opinion instead of shipping the misattribution.
 *
 * See [MealAnalysisOrchestrator.hasSuspiciousProduceFatForTest] and
 * [MealAnalysisOrchestrator.isLowFatProduce].
 */
class SuspiciousProduceFatTest {

    private fun result(vararg ings: IngredientDto) =
        FoodAnalysisResult(confidence = "high", ingredients = ings.toList())

    private fun ing(name: String, grams: Double, fat: Double?) =
        IngredientDto(name = name, weightGrams = grams, fat = fat)

    @Test
    fun `papaya 130g carrying 1_4g fat is suspicious`() {
        // The reported bug: 1.4 g / 130 g ≈ 1.08 g per 100 g, far above 0.3.
        val r = result(ing("papaia", 130.0, 1.4))
        assertTrue(MealAnalysisOrchestrator.hasSuspiciousProduceFatForTest(r))
    }

    @Test
    fun `papaya 130g with realistic 0_2g fat is not suspicious`() {
        // 0.2 g / 130 g ≈ 0.15 g per 100 g — below the 0.3 threshold.
        val r = result(ing("papaia", 130.0, 0.2))
        assertFalse(MealAnalysisOrchestrator.hasSuspiciousProduceFatForTest(r))
    }

    @Test
    fun `greek yogurt carrying its own fat is not flagged`() {
        // Yogurt is not low-fat produce, so any fat on it is legitimate.
        val r = result(ing("iogurte grego natural", 200.0, 5.0))
        assertFalse(MealAnalysisOrchestrator.hasSuspiciousProduceFatForTest(r))
    }

    @Test
    fun `the real breakfast - fat on papaya flags the meal even with yogurt present`() {
        val r = result(
            ing("papaia", 130.0, 1.4),
            ing("iogurte grego natural", 170.0, 3.8)
        )
        assertTrue(MealAnalysisOrchestrator.hasSuspiciousProduceFatForTest(r))
    }

    @Test
    fun `fatty produce like avocado is intentionally excluded`() {
        // Avocado genuinely carries fat — must NOT be treated as low-fat produce.
        val r = result(ing("abacate", 100.0, 15.0))
        assertFalse(MealAnalysisOrchestrator.hasSuspiciousProduceFatForTest(r))
        assertFalse(MealAnalysisOrchestrator.isLowFatProduce("abacate"))
        assertFalse(MealAnalysisOrchestrator.isLowFatProduce("avocado"))
    }

    @Test
    fun `english and gujarati-logged produce names still match`() {
        assertTrue(MealAnalysisOrchestrator.isLowFatProduce("papaya"))
        assertTrue(MealAnalysisOrchestrator.isLowFatProduce("Tomato, sliced"))
        assertTrue(MealAnalysisOrchestrator.isLowFatProduce("fresh spinach"))
    }

    @Test
    fun `null or zero-weight ingredients do not crash or falsely flag`() {
        assertFalse(MealAnalysisOrchestrator.hasSuspiciousProduceFatForTest(result()))
        assertFalse(
            MealAnalysisOrchestrator.hasSuspiciousProduceFatForTest(
                result(ing("papaia", 0.0, 1.4), ing("maçã", 100.0, null))
            )
        )
    }

    /* ─────────────────── zero-fat beverages (Coca-Cola case) ─────────────────── */

    @Test
    fun `coca-cola 330g carrying 2g fat is suspicious`() {
        // The reported bug: a soda has 0 g fat; 2 g on it is a mis-split.
        val r = result(ing("Coca-Cola", 330.0, 2.0))
        assertTrue(MealAnalysisOrchestrator.hasSuspiciousBeverageFatForTest(r))
    }

    @Test
    fun `coca-cola with 0g fat is not suspicious`() {
        val r = result(ing("Coca-Cola", 330.0, 0.0))
        assertFalse(MealAnalysisOrchestrator.hasSuspiciousBeverageFatForTest(r))
    }

    @Test
    fun `water black coffee and tea carrying fat are flagged`() {
        assertTrue(
            MealAnalysisOrchestrator.hasSuspiciousBeverageFatForTest(result(ing("água", 250.0, 0.5)))
        )
        assertTrue(
            MealAnalysisOrchestrator.hasSuspiciousBeverageFatForTest(result(ing("café", 60.0, 0.4)))
        )
        assertTrue(
            MealAnalysisOrchestrator.hasSuspiciousBeverageFatForTest(result(ing("chá verde", 200.0, 0.6)))
        )
    }

    @Test
    fun `dairy and fatty drinks carrying fat are NOT flagged`() {
        // Critical false-positive guard: their fat is legitimate.
        assertFalse(
            MealAnalysisOrchestrator.hasSuspiciousBeverageFatForTest(result(ing("latte", 240.0, 6.0)))
        )
        assertFalse(
            MealAnalysisOrchestrator.hasSuspiciousBeverageFatForTest(result(ing("milkshake de morango", 350.0, 12.0)))
        )
        assertFalse(
            MealAnalysisOrchestrator.hasSuspiciousBeverageFatForTest(result(ing("leite meio-gordo", 200.0, 3.2)))
        )
    }

    @Test
    fun `café com leite is not treated as zero-fat beverage`() {
        // A bare "café" match must be overridden by the dairy exclusion.
        assertFalse(MealAnalysisOrchestrator.isZeroFatBeverage("café com leite"))
        assertFalse(MealAnalysisOrchestrator.isZeroFatBeverage("chá com leite"))
        assertFalse(MealAnalysisOrchestrator.isZeroFatBeverage("cappuccino"))
    }

    @Test
    fun `multilingual and variant beverage names still match`() {
        assertTrue(MealAnalysisOrchestrator.isZeroFatBeverage("Coca-Cola Zero"))
        assertTrue(MealAnalysisOrchestrator.isZeroFatBeverage("Diet Coke"))
        assertTrue(MealAnalysisOrchestrator.isZeroFatBeverage("sparkling water"))
        assertTrue(MealAnalysisOrchestrator.isZeroFatBeverage("black coffee"))
        assertTrue(MealAnalysisOrchestrator.isZeroFatBeverage("refrigerante de laranja"))
    }

    @Test
    fun `beverage heuristic tolerates null and zero-weight rows`() {
        assertFalse(MealAnalysisOrchestrator.hasSuspiciousBeverageFatForTest(result()))
        assertFalse(
            MealAnalysisOrchestrator.hasSuspiciousBeverageFatForTest(
                result(ing("Coca-Cola", 330.0, null))
            )
        )
    }

    @Test
    fun `real meal - fat on coca-cola flags even with legit chicken present`() {
        // Mirrors the screenshot: roast chicken (legit fat) + soda (mis-split).
        val r = result(
            ing("Frango assado", 200.0, 16.0),
            ing("Coca-Cola", 330.0, 2.0)
        )
        assertTrue(MealAnalysisOrchestrator.hasSuspiciousBeverageFatForTest(r))
    }
}
