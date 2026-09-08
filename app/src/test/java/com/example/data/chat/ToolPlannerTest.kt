package com.example.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Phase 8 — unit tests for [ToolPlanner].
 * Plain JVM, no Mockito, fixed dates. Verifies that each keyword family routes
 * to the right [ToolPlanner.ToolKind], chit-chat yields an empty plan, and the
 * window is carried through verbatim from the intent.
 */
class ToolPlannerTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 8)
    private val weekWindow = DateWindow(today.minusDays(6), today, "esta semana")

    private fun intent(
        text: String,
        type: DataType = DataType.BOTH,
        window: DateWindow = weekWindow,
    ) = ChatIntent.DataQuery(type = type, window = window, originalText = text)

    // --- CALORIES -----------------------------------------------------------

    @Test
    fun calories_budgetKeyword_en() {
        val plan = ToolPlanner.plan("am i over my calorie budget this week", intent("am i over my calorie budget this week"))
        assertTrue(ToolPlanner.ToolKind.CALORIES in plan.kinds)
    }

    @Test
    fun calories_deficitKeyword_pt() {
        val plan = ToolPlanner.plan("estou em défice calórico?", intent("estou em défice calórico?"))
        assertTrue(ToolPlanner.ToolKind.CALORIES in plan.kinds)
    }

    @Test
    fun calories_targetKeyword_pt() {
        val text = "como estou nas calorias esta semana?"
        val plan = ToolPlanner.plan(text, intent(text))
        assertTrue(ToolPlanner.ToolKind.CALORIES in plan.kinds)
    }

    @Test
    fun calories_surplusKeyword_pt() {
        val text = "tenho superavit calórico?"
        val plan = ToolPlanner.plan(text, intent(text))
        assertTrue(ToolPlanner.ToolKind.CALORIES in plan.kinds)
    }

    // --- MACROS -------------------------------------------------------------

    @Test
    fun macros_proteinKeyword_pt() {
        val text = "quanta proteína comi esta semana?"
        val plan = ToolPlanner.plan(text, intent(text, type = DataType.MEALS))
        assertTrue(ToolPlanner.ToolKind.MACROS in plan.kinds)
    }

    @Test
    fun macros_carbsKeyword_en() {
        val text = "how many carbs did i have today"
        val plan = ToolPlanner.plan(text, intent(text, type = DataType.MEALS))
        assertTrue(ToolPlanner.ToolKind.MACROS in plan.kinds)
    }

    @Test
    fun macros_macroBreakdown_en() {
        val text = "show me my macro breakdown"
        val plan = ToolPlanner.plan(text, intent(text, type = DataType.MEALS))
        assertTrue(ToolPlanner.ToolKind.MACROS in plan.kinds)
    }

    // --- WEIGHT -------------------------------------------------------------

    @Test
    fun weight_lostWeightKeyword_en() {
        val text = "how much weight did i lose this month?"
        val plan = ToolPlanner.plan(text, intent(text))
        assertTrue(ToolPlanner.ToolKind.WEIGHT in plan.kinds)
    }

    @Test
    fun weight_emagreci_pt() {
        val text = "emagreci este mês?"
        val plan = ToolPlanner.plan(text, intent(text))
        assertTrue(ToolPlanner.ToolKind.WEIGHT in plan.kinds)
    }

    @Test
    fun weight_bodyFat_en() {
        val text = "has my body fat changed?"
        val plan = ToolPlanner.plan(text, intent(text))
        assertTrue(ToolPlanner.ToolKind.WEIGHT in plan.kinds)
    }

    @Test
    fun weight_massaMuscular_pt() {
        val text = "e a minha massa muscular esta semana?"
        val plan = ToolPlanner.plan(text, intent(text))
        assertTrue(ToolPlanner.ToolKind.WEIGHT in plan.kinds)
    }

    // --- ACTIVITY -----------------------------------------------------------

    @Test
    fun activity_intentDataTypeActivity_triggers() {
        val text = "quantos passos dei esta semana?"
        val plan = ToolPlanner.plan(text, intent(text, type = DataType.ACTIVITY))
        assertTrue(ToolPlanner.ToolKind.ACTIVITY in plan.kinds)
    }

    @Test
    fun activity_intentDataTypeBoth_triggers() {
        val text = "como foi a minha atividade esta semana?"
        val plan = ToolPlanner.plan(text, intent(text, type = DataType.BOTH))
        assertTrue(ToolPlanner.ToolKind.ACTIVITY in plan.kinds)
    }

    @Test
    fun activity_intentMealsOnly_doesNotTrigger() {
        val text = "o que comi esta semana?"
        val plan = ToolPlanner.plan(text, intent(text, type = DataType.MEALS))
        assertFalse(ToolPlanner.ToolKind.ACTIVITY in plan.kinds)
    }

    // --- EMPTY plan (chit-chat, theoretical) --------------------------------

    @Test
    fun emptyPlan_chitChat_reasoning() {
        // "olá" → no keywords match → empty plan
        val plan = ToolPlanner.plan("olá, como estás?", intent("olá, como estás?", type = DataType.BOTH))
        // ACTIVITY would fire if type=BOTH, so use MEALS to isolate chit-chat
        val planMeals = ToolPlanner.plan("olá, como estás?", intent("olá, como estás?", type = DataType.MEALS))
        assertTrue(planMeals.kinds.isEmpty())
    }

    @Test
    fun emptyPlan_theoretical_question() {
        val text = "o que é o jejum intermitente?"
        val plan = ToolPlanner.plan(text, intent(text, type = DataType.MEALS))
        assertTrue(plan.kinds.isEmpty())
    }

    // --- Window passthrough -------------------------------------------------

    @Test
    fun windowIsCarriedThrough() {
        val custom = DateWindow(today.minusDays(29), today, "últimos 30 dias")
        val text = "calorie deficit this month"
        val plan = ToolPlanner.plan(text, intent(text, window = custom))
        assertEquals(custom, plan.window)
    }

    // --- BOTH intent wires calories + activity ------------------------------

    @Test
    fun both_calorieAndActivity_wired() {
        val text = "calorie deficit and how many steps did i take?"
        val plan = ToolPlanner.plan(text, intent(text, type = DataType.BOTH))
        assertTrue(ToolPlanner.ToolKind.CALORIES in plan.kinds)
        assertTrue(ToolPlanner.ToolKind.ACTIVITY in plan.kinds)
    }

    // --- MEALS intent + broad meal phrase → auto-pair CALORIES+MACROS ------

    @Test
    fun mealsIntent_ateWithCaloricQuestion_addsMacros() {
        // CALORIES keyword fires; "o que comi" is a broad meal phrase → MACROS added
        val text = "quantas calorias o que comi esta semana?"
        val plan = ToolPlanner.plan(text, intent(text, type = DataType.MEALS))
        assertTrue(ToolPlanner.ToolKind.CALORIES in plan.kinds)
        assertTrue(ToolPlanner.ToolKind.MACROS in plan.kinds)
    }

    @Test
    fun mealsIntent_explicitMacroAndCalorie_noDoubleAdd() {
        // Both explicitly asked → both kinds present (no duplicate/error)
        val text = "what's my macro breakdown and calorie deficit?"
        val plan = ToolPlanner.plan(text, intent(text, type = DataType.MEALS))
        assertTrue(ToolPlanner.ToolKind.CALORIES in plan.kinds)
        assertTrue(ToolPlanner.ToolKind.MACROS in plan.kinds)
        // Set guarantees no duplicates — size check
        assertEquals(plan.kinds.size, plan.kinds.toSet().size)
    }
}
