package com.example.ui.components

import com.example.data.local.FoodEntry
import com.example.data.local.MealCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the "Fontes de açúcar livre" fix (2026-07-10). The CARBS breakdown popup
 * used to (a) render a bare "—" for every free-sugar row and (b) list savoury /
 * plain meals (café com leite, frango assado) as free-sugar sources because the
 * LLM over-assigned FoodEntry.freeSugarsG.
 *
 * [effectiveFreeSugars] is the single source of truth for how much of the LLM's
 * per-meal number we believe; [computeCarbsRows] must fold suppressed sugar back
 * into the good leg (invariant: primary + secondary == Σ carbs) and never emit a
 * bare "—" in the ingredient column.
 */
class EffectiveFreeSugarsTest {

    /** Build an `ingredientsJson` blob in the shape [parseIngredients] decodes. */
    private fun ingredientsJson(vararg names: String): String =
        names.joinToString(prefix = "[", postfix = "]") { n ->
            """{"name":"$n","weightGrams":50.0,"carbs":10.0}"""
        }

    /** Same, but with per-ingredient carbs so tests can exercise the
     *  highest-carb culprit ranking in [topFreeSugarSourceHint]. */
    private fun ingredientsJson(vararg items: Pair<String, Double>): String =
        items.joinToString(prefix = "[", postfix = "]") { (n, c) ->
            """{"name":"$n","weightGrams":50.0,"carbs":$c}"""
        }

    /** Per-ingredient carbs + freeSugarsG, for the per-ingredient attribution
     *  path in [computeCarbsRows]. A null third element omits the freeSugarsG
     *  key entirely (legacy-blob simulation). */
    private fun ingredientsJsonFs(vararg items: Triple<String, Double, Double?>): String =
        items.joinToString(prefix = "[", postfix = "]") { (n, c, fs) ->
            val fsPart = if (fs != null) ""","freeSugarsG":$fs""" else ""
            """{"name":"$n","weightGrams":50.0,"carbs":$c$fsPart}"""
        }

    private fun food(
        id: Long = 1,
        name: String,
        carbs: Double,
        freeSugarsG: Double?,
        ingredientsJson: String? = null
    ) = FoodEntry(
        id = id,
        name = name,
        calories = 100,
        protein = 0.0,
        carbs = carbs,
        fat = 0.0,
        mealCategory = MealCategory.SNACK,
        ingredientsJson = ingredientsJson,
        freeSugarsG = freeSugarsG
    )

    @Test
    fun `coffee with milk is suppressed to zero`() {
        // café com leite: ingredients present, none sugary → false positive.
        val f = food(
            name = "Café com leite",
            carbs = 15.0,
            freeSugarsG = 12.5,
            ingredientsJson = ingredientsJson("café", "leite meio-gordo")
        )
        assertEquals(0.0, effectiveFreeSugars(f), 0.0001)
    }

    @Test
    fun `roast chicken is suppressed to zero`() {
        val f = food(
            name = "Frango assado com arroz",
            carbs = 40.0,
            freeSugarsG = 10.6,
            ingredientsJson = ingredientsJson("frango assado", "arroz branco basmati")
        )
        assertEquals(0.0, effectiveFreeSugars(f), 0.0001)
    }

    @Test
    fun `sugary ingredient keeps the free sugars`() {
        // "chocolate" is in HIGH_FREE_SUGAR_HINTS → strong signal, kept.
        val f = food(
            name = "Café com leite e chocolate",
            carbs = 30.0,
            freeSugarsG = 10.0,
            ingredientsJson = ingredientsJson("café", "chocolate de leite")
        )
        assertEquals(10.0, effectiveFreeSugars(f), 0.0001)
    }

    @Test
    fun `no ingredient data trusts the LLM number`() {
        // legacy / model omitted the breakdown → we can't prove it wrong.
        val f = food(name = "Sobremesa", carbs = 20.0, freeSugarsG = 8.0, ingredientsJson = null)
        assertEquals(8.0, effectiveFreeSugars(f), 0.0001)
    }

    @Test
    fun `free sugars are clamped at carbs`() {
        val f = food(name = "Bolo", carbs = 5.0, freeSugarsG = 40.0, ingredientsJson = ingredientsJson("bolo"))
        assertEquals(5.0, effectiveFreeSugars(f), 0.0001)
    }

    @Test
    fun `computeCarbsRows never emits a bare dash and preserves the carb total`() {
        val foods = listOf(
            food(id = 1, name = "Café com leite", carbs = 15.0, freeSugarsG = 12.5,
                ingredientsJson = ingredientsJson("café", "leite")),
            food(id = 2, name = "Coca-Cola", carbs = 20.0, freeSugarsG = 20.0,
                ingredientsJson = ingredientsJson("coca-cola"))
        )
        val breakdown = computeCarbsRows(foods)

        // Invariant: the two tables together account for every gram of carbs.
        val rowSum = breakdown.primary.sumOf { it.amount } + breakdown.secondary.sumOf { it.amount }
        assertEquals(35.0, rowSum, 0.01)

        // No row may show the "—" placeholder in the ingredient column.
        (breakdown.primary + breakdown.secondary).forEach {
            assertTrue("row '${it.ingredientName}' must not be a bare dash",
                it.ingredientName != MACRO_ROW_MISSING)
        }

        // The café-com-leite meal must NOT appear in the free-sugar (secondary) leg.
        assertTrue(breakdown.secondary.none { it.mealName == "Café com leite" })
        // Coca-Cola must remain a free-sugar source.
        assertTrue(breakdown.secondary.any { it.mealName == "Coca-Cola" })
    }

    @Test
    fun `sugar-free coffee alone is suppressed to zero`() {
        // Regression (2026-07-11): the ingredient NAME contains "sem açúcar",
        // whose "açúcar" substring used to match HIGH_FREE_SUGAR_HINTS and keep
        // the LLM's number. The negation must cancel the hint → suppressed.
        val f = food(
            name = "Café com leite (sem açúcar)",
            carbs = 3.5,
            freeSugarsG = 12.5,
            ingredientsJson = ingredientsJson("Café com leite (sem açúcar)")
        )
        assertEquals(0.0, effectiveFreeSugars(f), 0.0001)
        assertEquals(null, topFreeSugarSourceHint(parseIngredients(f.ingredientsJson)))
    }

    @Test
    fun `gila pastry with sugar-free coffee keeps the sugar and labels the pastry`() {
        // Real meal (food_entries id 193): two ingredients, sugar-free coffee
        // FIRST, gila pastry ("doce de") SECOND. The kept 18g must be labelled
        // the pastry (higher carb, real culprit), not the coffee.
        val ings = ingredientsJson(
            "Café com leite (sem açúcar)" to 3.5,
            "Pastel de massa folhada com doce de gila" to 45.0
        )
        val f = food(
            name = "Café com leite e pastel de gila",
            carbs = 48.5,
            freeSugarsG = 18.0,
            ingredientsJson = ings
        )
        assertEquals(18.0, effectiveFreeSugars(f), 0.0001)
        assertEquals(
            "Pastel de massa folhada com doce de gila",
            topFreeSugarSourceHint(parseIngredients(ings))
        )
    }

    @Test
    fun `coffee WITH sugar still counts as a free-sugar source`() {
        // Guard against over-stripping: "com açúcar" is a positive mention.
        val f = food(
            name = "Café com açúcar",
            carbs = 10.0,
            freeSugarsG = 8.0,
            ingredientsJson = ingredientsJson("Café com açúcar")
        )
        assertEquals(8.0, effectiveFreeSugars(f), 0.0001)
    }

    /* ── Per-ingredient free-sugar attribution (2026-07-11) ──────────────── */

    @Test
    fun `id 193 attributes all free sugar to the gila pastry via per-ingredient data`() {
        // The real meal (food_entries id 193): café sem açúcar (0g free sugar)
        // + gila pastry (all 18g). Per-ingredient data must land the 18g on the
        // pastry and NOTHING on the coffee — the bug the user reported.
        val ings = ingredientsJsonFs(
            Triple("Café com leite (sem açúcar)", 3.5, 0.0),
            Triple("Pastel de massa folhada com doce de gila", 45.0, 18.0)
        )
        val f = food(id = 193, name = "Café com leite e pastel de gila",
            carbs = 48.5, freeSugarsG = 18.0, ingredientsJson = ings)
        val bd = computeCarbsRows(listOf(f))

        // Free-sugar leg: exactly one row, on the pastry, 18g.
        assertEquals(1, bd.secondary.size)
        assertTrue(bd.secondary.single().ingredientName.contains("Pastel"))
        assertEquals(18.0, bd.secondary.single().amount, 0.01)
        assertTrue(bd.secondary.none { it.ingredientName.contains("Café") })

        // Good leg: coffee 3.5 + pastry residual (45-18)=27 = 30.5.
        assertEquals(30.5, bd.primary.sumOf { it.amount }, 0.05)
        // Conservation: primary + secondary == Σ carbs.
        assertEquals(48.5, bd.primary.sumOf { it.amount } + bd.secondary.sumOf { it.amount }, 0.05)
    }

    @Test
    fun `per-ingredient free sugars are prorated and rescaled to the meal total`() {
        // Two sugary ingredients; per-ingredient sum (5+15=20) differs from the
        // meal-level total (18) → rescale so the rows sum to exactly 18.
        val ings = ingredientsJsonFs(
            Triple("compota", 20.0, 5.0),
            Triple("bolo", 30.0, 15.0)
        )
        val f = food(name = "Bolo com compota", carbs = 50.0,
            freeSugarsG = 18.0, ingredientsJson = ings)
        val bd = computeCarbsRows(listOf(f))

        assertEquals(2, bd.secondary.size)
        assertEquals(18.0, bd.secondary.sumOf { it.amount }, 0.01)
        // Proportions preserved: bolo (15/20) gets 3× the compota (5/20).
        val compota = bd.secondary.first { it.ingredientName == "compota" }
        val bolo = bd.secondary.first { it.ingredientName == "bolo" }
        assertEquals(4.5, compota.amount, 0.01)
        assertEquals(13.5, bolo.amount, 0.01)
    }

    @Test
    fun `legacy meal without per-ingredient sugar falls back to the heuristic row`() {
        // No freeSugarsG on ingredients (null) → single meal-level row labelled
        // by topFreeSugarSourceHint, never a bare dash.
        val f = food(name = "Sobremesa", carbs = 30.0, freeSugarsG = 12.0,
            ingredientsJson = ingredientsJson("bolo", "natas"))
        val bd = computeCarbsRows(listOf(f))
        assertEquals(1, bd.secondary.size)
        assertEquals(12.0, bd.secondary.single().amount, 0.01)
        assertTrue(bd.secondary.single().ingredientName != MACRO_ROW_MISSING)
    }

    @Test
    fun `mixed completeness falls back to the meal-level heuristic row`() {
        // One ingredient carries freeSugarsG, the other is null → hasAllFree
        // false → single meal-level heuristic row (not a partial per-ingredient
        // split that would under-count).
        val ings = ingredientsJsonFs(
            Triple("bolo", 30.0, 15.0),
            Triple("café", 3.0, null)
        )
        val f = food(name = "Bolo e café", carbs = 33.0,
            freeSugarsG = 15.0, ingredientsJson = ings)
        val bd = computeCarbsRows(listOf(f))
        assertEquals(1, bd.secondary.size)
        assertEquals(15.0, bd.secondary.single().amount, 0.01)
    }

}
