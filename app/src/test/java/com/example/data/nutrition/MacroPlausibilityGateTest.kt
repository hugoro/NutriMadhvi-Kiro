package com.example.data.nutrition

import org.junit.Assert.assertTrue
import org.junit.Test

class MacroPlausibilityGateTest {

    private fun valid(r: MacroPlausibilityGate.Result) =
        r is MacroPlausibilityGate.Result.Valid
    private fun suspicious(r: MacroPlausibilityGate.Result) =
        r is MacroPlausibilityGate.Result.Suspicious

    @Test
    fun `boiled egg per 100g is valid`() {
        // Egg: 155 kcal, 13 P, 1 C, 11 F → Atwater = 52+4+99 = 155 ✓
        assertTrue(valid(MacroPlausibilityGate.checkPer100g(155.0, 13.0, 1.0, 11.0)))
    }

    @Test
    fun `olive oil near 900 kcal is valid`() {
        // Pure fat: 900 kcal, 0 P, 0 C, 100 F → Atwater = 900 ✓
        assertTrue(valid(MacroPlausibilityGate.checkPer100g(900.0, 0.0, 0.0, 100.0)))
    }

    @Test
    fun `bad USDA coffee match is suspicious`() {
        // The real bad match: 411 kcal but macros of a plain coffee (~1 P, 2 C, 1.5 F)
        // → Atwater = 4+8+13.5 = 25.5, kcal 411 is 16x too high.
        assertTrue(suspicious(MacroPlausibilityGate.checkPer100g(411.0, 1.0, 2.0, 1.5)))
    }

    @Test
    fun `impossible kcal above 900 is suspicious`() {
        assertTrue(suspicious(MacroPlausibilityGate.checkPer100g(1200.0, 0.0, 0.0, 100.0)))
    }

    @Test
    fun `negative kcal is suspicious`() {
        assertTrue(suspicious(MacroPlausibilityGate.checkPer100g(-10.0, 0.0, 0.0, 0.0)))
    }

    @Test
    fun `low-macro low-kcal item skips Atwater check`() {
        // Black coffee: ~2 kcal, negligible macros — below the 5.0 floor, always valid.
        assertTrue(valid(MacroPlausibilityGate.checkPer100g(2.0, 0.1, 0.0, 0.0)))
    }

    @Test
    fun `meal totals within tolerance is valid`() {
        assertTrue(valid(MacroPlausibilityGate.checkMealTotalsAgainstIngredients(455, 450)))
    }

    @Test
    fun `meal totals drifting far is suspicious`() {
        // 700 vs 400 = 75% drift
        assertTrue(suspicious(MacroPlausibilityGate.checkMealTotalsAgainstIngredients(700, 400)))
    }

    @Test
    fun `zero ingredient sum is always valid`() {
        assertTrue(valid(MacroPlausibilityGate.checkMealTotalsAgainstIngredients(455, 0)))
    }
}
