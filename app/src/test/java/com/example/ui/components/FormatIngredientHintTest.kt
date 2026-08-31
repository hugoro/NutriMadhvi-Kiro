package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the macro-hint fix (2026-07-10). The label under each macro bar in the
 * edit-meal detailed-macros section must show the grams of THAT macronutrient
 * the ingredient contributes (e.g. "20.0g" of protein), NOT the ingredient's
 * total portion weight (which used to show "200g" under every bar).
 */
class FormatIngredientHintTest {

    private fun ing(
        name: String,
        weightGrams: Double,
        protein: Double? = null,
        carbs: Double? = null,
        fat: Double? = null
    ) = StoredIngredient(
        name = name, weightGrams = weightGrams,
        protein = protein, carbs = carbs, fat = fat
    )

    /** Grams as the production code formats them — locale-dependent decimal
     *  separator (pt → "20,0g", en → "20.0g"). Comparing against this keeps the
     *  test asserting the number, not the JVM's default locale. */
    private fun g(value: Double) = String.format("%.1fg", value)

    @Test
    fun `protein bar shows grams of protein, not portion weight`() {
        val pudim = ing("Pudim Caramelo Proteína Continente", weightGrams = 200.0, protein = 20.0, carbs = 13.3, fat = 2.7)
        val hint = formatIngredientHint(listOf(pudim), Macro.PROTEIN)
        assertEquals("(${g(20.0)} Pudim Caramelo Proteína Continente)", hint)
    }

    @Test
    fun `carbs and fat bars each show their own macro grams`() {
        val pudim = ing("Pudim Caramelo Proteína Continente", weightGrams = 200.0, protein = 20.0, carbs = 13.3, fat = 2.7)
        assertEquals("(${g(13.3)} Pudim Caramelo Proteína Continente)", formatIngredientHint(listOf(pudim), Macro.CARBS))
        assertEquals("(${g(2.7)} Pudim Caramelo Proteína Continente)", formatIngredientHint(listOf(pudim), Macro.FAT))
    }

    @Test
    fun `multiple ingredients are listed sorted by contribution descending`() {
        val a = ing("Frango", weightGrams = 150.0, protein = 30.0)
        val b = ing("Arroz", weightGrams = 100.0, protein = 5.3)
        val hint = formatIngredientHint(listOf(b, a), Macro.PROTEIN)
        assertEquals("(${g(30.0)} Frango; ${g(5.3)} Arroz)", hint)
    }

    @Test
    fun `ingredient below threshold for the macro is dropped`() {
        // 0.3g fat is below the 0.5g default threshold → not shown under the Fat bar.
        val pasta = ing("Massa", weightGrams = 100.0, protein = 12.0, carbs = 75.0, fat = 0.3)
        assertNull(formatIngredientHint(listOf(pasta), Macro.FAT))
        // But it does show under carbs.
        assertEquals("(${g(75.0)} Massa)", formatIngredientHint(listOf(pasta), Macro.CARBS))
    }

    @Test
    fun `empty list yields null`() {
        assertNull(formatIngredientHint(emptyList(), Macro.PROTEIN))
    }

    @Test
    fun `small macro contribution keeps one decimal (no integer truncation)`() {
        // Regression guard: the old code truncated weights >= 10g to Int.
        // A 12.4g carb contribution must render as "12.4g"/"12,4g", never "12g".
        val x = ing("Fruta", weightGrams = 80.0, carbs = 12.4)
        val hint = formatIngredientHint(listOf(x), Macro.CARBS)
        assertEquals("(${g(12.4)} Fruta)", hint)
    }
}
