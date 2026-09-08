package com.example.data.chat

import com.example.data.local.BodyCompositionSnapshotEntity
import com.example.data.local.FoodEntry
import com.example.data.local.MealCategory
import com.example.data.local.TargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 8 — unit tests for [NutritionToolRunner].
 * Plain JVM, no Mockito, fixed UTC zone + fixed dates. Verifies:
 *   - empty plan → ""
 *   - each kind emits exactly that section in stable order
 *   - WEIGHT with empty history emits no weight block
 *   - emitted numbers match what [NutritionTools] computes directly
 *     (guards against a divergent second copy of the arithmetic)
 */
class NutritionToolRunnerTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 9, 8)
    private val weekWindow = DateWindow(today.minusDays(6), today, "esta semana")

    private fun ms(date: LocalDate, hour: Int = 12): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun food(date: LocalDate, kcal: Int, p: Double = 0.0, c: Double = 0.0, f: Double = 0.0) =
        FoodEntry(
            name = "meal",
            calories = kcal,
            protein = p,
            carbs = c,
            fat = f,
            timestamp = ms(date),
            mealCategory = MealCategory.SNACK,
        )

    private fun target(kcal: Int) = TargetSnapshot(
        effectiveDateEpochDay = 0,
        targetKcal = kcal,
        targetProtein = 100.0,
        targetCarbs = 200.0,
        targetFat = 60.0,
        targetSteps = 8000,
        targetActiveMinutes = 30,
        targetActivityCalories = 400,
    )

    private fun bodyComp(date: LocalDate, weight: Float, fat: Float? = null) =
        BodyCompositionSnapshotEntity(
            sourceTimestampMs = ms(date),
            weightKg = weight,
            bodyFatPct = fat,
        )

    private fun emptyPlan() = ToolPlanner.ToolPlan(kinds = emptySet(), window = weekWindow)
    private fun plan(vararg kinds: ToolPlanner.ToolKind) =
        ToolPlanner.ToolPlan(kinds = kinds.toSet(), window = weekWindow)

    // --- empty plan ---------------------------------------------------------

    @Test
    fun emptyPlan_returnsEmpty() {
        val result = NutritionToolRunner.run(plan = emptyPlan(), zone = zone)
        assertEquals("", result)
    }

    // --- header presence when non-empty -------------------------------------

    @Test
    fun nonEmptyPlan_includesHeader() {
        val foods = listOf(food(today, 500))
        val result = NutritionToolRunner.run(
            plan = plan(ToolPlanner.ToolKind.CALORIES),
            foods = foods,
            zone = zone,
        )
        assertTrue(result.contains("[COMPUTED TOOLS"))
        assertTrue(result.contains("do not recompute"))
    }

    // --- CALORIES -----------------------------------------------------------

    @Test
    fun calories_blockPresent_numbersMatchNutritionTools() {
        val foods = listOf(food(today, 1800), food(today.minusDays(1), 2200))
        val t = target(2000)
        val plan = plan(ToolPlanner.ToolKind.CALORIES)
        val result = NutritionToolRunner.run(plan = plan, foods = foods, target = t, zone = zone)

        // Verify the block is present
        assertTrue(result.contains("[CALORIE SUMMARY"))

        // Verify numbers match NutritionTools directly (guards divergence)
        val expected = NutritionTools.getCalorieSummary(foods, weekWindow, t, zone)
        assertTrue(result.contains("${expected.totalKcal}"))
        assertTrue(result.contains("${expected.avgOnLoggedDays}"))
    }

    @Test
    fun calories_noTarget_noVerdictLine() {
        val foods = listOf(food(today, 1500))
        val result = NutritionToolRunner.run(
            plan = plan(ToolPlanner.ToolKind.CALORIES),
            foods = foods,
            target = null,
            zone = zone,
        )
        assertFalse(result.contains("over budget"))
        assertFalse(result.contains("under budget"))
    }

    // --- MACROS -------------------------------------------------------------

    @Test
    fun macros_blockPresent_numbersMatchNutritionTools() {
        val foods = listOf(food(today, 600, p = 50.0, c = 100.0, f = 20.0))
        val result = NutritionToolRunner.run(
            plan = plan(ToolPlanner.ToolKind.MACROS),
            foods = foods,
            zone = zone,
        )
        assertTrue(result.contains("[MACRO SUMMARY"))
        val expected = NutritionTools.getMacroSummary(foods, weekWindow, zone)
        assertTrue(result.contains("${expected.proteinPct}%"))
    }

    // --- WEIGHT -------------------------------------------------------------

    @Test
    fun weight_withHistory_blockPresent() {
        val history = listOf(
            bodyComp(today, 79.0f, fat = 20.0f),
            bodyComp(today.minusDays(7), 80.0f),
        )
        val result = NutritionToolRunner.run(
            plan = plan(ToolPlanner.ToolKind.WEIGHT),
            bodyComp = history,
            zone = zone,
        )
        assertTrue(result.contains("[WEIGHT TREND"))
    }

    @Test
    fun weight_emptyHistory_noWeightSection() {
        // WEIGHT kind requested but history empty → should not emit empty block
        val result = NutritionToolRunner.run(
            plan = plan(ToolPlanner.ToolKind.WEIGHT),
            bodyComp = emptyList(),
            zone = zone,
        )
        // No content was emitted at all (header+blocks would otherwise appear)
        assertEquals("", result)
    }

    // --- ACTIVITY -----------------------------------------------------------

    @Test
    fun activity_blockPresent() {
        val result = NutritionToolRunner.run(
            plan = plan(ToolPlanner.ToolKind.ACTIVITY),
            zone = zone,
        )
        assertTrue(result.contains("[ACTIVITY SUMMARY"))
    }

    // --- stable emission order: CALORIES → MACROS → WEIGHT → ACTIVITY ------

    @Test
    fun stableOrder_allKinds() {
        val foods = listOf(food(today, 500, p = 30.0))
        val history = listOf(bodyComp(today, 80.0f))
        val result = NutritionToolRunner.run(
            plan = plan(
                ToolPlanner.ToolKind.ACTIVITY,   // intentionally out-of-order in input
                ToolPlanner.ToolKind.WEIGHT,
                ToolPlanner.ToolKind.CALORIES,
                ToolPlanner.ToolKind.MACROS,
            ),
            foods = foods,
            bodyComp = history,
            zone = zone,
        )
        // All four present
        assertTrue(result.contains("[CALORIE SUMMARY"))
        assertTrue(result.contains("[MACRO SUMMARY"))
        assertTrue(result.contains("[WEIGHT TREND"))
        assertTrue(result.contains("[ACTIVITY SUMMARY"))

        // Order: CALORIE before MACRO before WEIGHT before ACTIVITY
        val cIdx = result.indexOf("[CALORIE SUMMARY")
        val mIdx = result.indexOf("[MACRO SUMMARY")
        val wIdx = result.indexOf("[WEIGHT TREND")
        val aIdx = result.indexOf("[ACTIVITY SUMMARY")
        assertTrue(cIdx < mIdx)
        assertTrue(mIdx < wIdx)
        assertTrue(wIdx < aIdx)
    }

    // --- additivity (non-computational turn → no-op) -----------------------

    @Test
    fun emptyPlanLeavesNoTrace() {
        // Simulates what happens on chit-chat turns: the runner returns ""
        // and appending "" to dynamicSystemText changes nothing.
        val computedBlock = NutritionToolRunner.run(plan = emptyPlan(), zone = zone)
        assertEquals("", computedBlock)
        val dynamicSystemText = "base" + computedBlock + "rest"
        assertEquals("baserest", dynamicSystemText)
    }
}
