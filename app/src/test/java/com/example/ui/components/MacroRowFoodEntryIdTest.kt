package com.example.ui.components

import com.example.data.local.FoodEntry
import com.example.data.local.MealCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the "tap a macro row → open Edit Meal" fix (2026-07-10). Previously only
 * the CALORIES popup carried a `foodEntryId` on its rows; PROTEIN / FAT / CARBS
 * good-source rows shipped `foodEntryId = null`, so the dialog could never make
 * them clickable. This test guarantees every macro row maps back to its source
 * meal id — including the meal-level "—" fallback rows — while the amounts are
 * left untouched (the fix only annotates rows; it must not change any number).
 */
class MacroRowFoodEntryIdTest {

    private fun ingredientsJson(vararg entries: String): String =
        entries.joinToString(prefix = "[", postfix = "]")

    private fun food(
        id: Long,
        name: String,
        protein: Double = 0.0,
        carbs: Double = 0.0,
        fat: Double = 0.0,
        freeSugarsG: Double? = null,
        saturatedFatG: Double? = null,
        ingredientsJson: String? = null
    ) = FoodEntry(
        id = id,
        name = name,
        calories = 100,
        protein = protein,
        carbs = carbs,
        fat = fat,
        mealCategory = MealCategory.LUNCH,
        ingredientsJson = ingredientsJson,
        freeSugarsG = freeSugarsG,
        saturatedFatG = saturatedFatG
    )

    @Test
    fun `protein ingredient rows carry the source meal id`() {
        val f = food(
            id = 42,
            name = "Frango grelhado",
            protein = 30.0,
            ingredientsJson = ingredientsJson(
                """{"name":"peito de frango","weightGrams":150.0,"protein":28.0}""",
                """{"name":"azeite","weightGrams":5.0,"protein":2.0}"""
            )
        )
        val breakdown = computeMacroRows(MacroKind.PROTEIN, listOf(f))
        assertTrue("expected ingredient rows", breakdown.primary.isNotEmpty())
        breakdown.primary.forEach { assertEquals(42L, it.foodEntryId) }
    }

    @Test
    fun `protein fallback row carries the source meal id`() {
        // No per-ingredient protein → single meal-level "—" fallback row.
        val f = food(id = 7, name = "Batido proteico", protein = 25.0)
        val breakdown = computeMacroRows(MacroKind.PROTEIN, listOf(f))
        assertEquals(1, breakdown.primary.size)
        assertEquals(MACRO_ROW_MISSING, breakdown.primary.first().ingredientName)
        assertEquals(7L, breakdown.primary.first().foodEntryId)
    }

    @Test
    fun `fat sat and unsat rows carry the source meal id`() {
        val f = food(
            id = 9,
            name = "Salmão com azeite",
            fat = 20.0,
            saturatedFatG = 5.0,
            ingredientsJson = ingredientsJson(
                """{"name":"salmão","weightGrams":150.0,"fat":15.0,"saturatedFatG":4.0}""",
                """{"name":"azeite","weightGrams":10.0,"fat":5.0,"saturatedFatG":1.0}"""
            )
        )
        val breakdown = computeFatRows(listOf(f))
        (breakdown.primary + breakdown.secondary).also {
            assertTrue("expected fat rows", it.isNotEmpty())
            it.forEach { row -> assertEquals(9L, row.foodEntryId) }
        }
    }

    @Test
    fun `carbs good-source and free-sugar rows carry the source meal id`() {
        val f = food(
            id = 13,
            name = "Panqueca com mel",
            carbs = 50.0,
            freeSugarsG = 15.0,
            ingredientsJson = ingredientsJson(
                """{"name":"farinha","weightGrams":80.0,"carbs":35.0}""",
                """{"name":"mel","weightGrams":20.0,"carbs":15.0}"""
            )
        )
        val breakdown = computeCarbsRows(listOf(f))
        (breakdown.primary + breakdown.secondary).also {
            assertTrue("expected carb rows", it.isNotEmpty())
            it.forEach { row -> assertEquals(13L, row.foodEntryId) }
        }
    }

    @Test
    fun `annotating the id does not change the amounts`() {
        val f = food(id = 1, name = "Arroz", carbs = 40.0)
        val total = computeCarbsRows(listOf(f))
            .let { it.primary + it.secondary }
            .sumOf { it.amount }
        // Whole carb value still lands on rows (good-sources fallback here).
        assertEquals(40.0, total, 0.01)
    }
}
