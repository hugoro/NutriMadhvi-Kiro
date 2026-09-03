package com.example.data.meal.ensemble

import com.example.data.remote.FoodAnalysisResult
import com.example.data.remote.IngredientDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfidenceScorerTest {

    private fun result(
        name: String? = "grilled chicken",
        confidence: String? = "high",
        calories: Int? = 300,
        ingredients: List<IngredientDto>? = listOf(
            IngredientDto(name = "chicken", weightGrams = 150.0, calories = 300)
        ),
    ) = FoodAnalysisResult(
        name = name,
        calories = calories,
        confidence = confidence,
        ingredients = ingredients,
    )

    @Test
    fun `an empty result scores as unknown rather than confident`() {
        val profile = ConfidenceScorer.score(
            FoodAnalysisResult(name = null, ingredients = null)
        )
        assertEquals(ConfidenceProfile.UNKNOWN, profile)
    }

    @Test
    fun `an unrated result is treated as medium, never as high`() {
        val profile = ConfidenceScorer.score(result(confidence = null))
        assertTrue(
            "unrated must not clear the 0.85 identification gate on its own",
            profile.identification < 0.85,
        )
    }

    @Test
    fun `regional dish names lower specificity so the router seeks a second opinion`() {
        val generic = ConfidenceScorer.score(result(name = "lentil curry"))
        val regional = ConfidenceScorer.score(result(name = "Dal Makhani"))
        assertTrue(
            "an unjustified regional name must score lower on specificity",
            regional.specificity < generic.specificity,
        )
    }

    @Test
    fun `hedging language lowers identification confidence`() {
        val firm = ConfidenceScorer.score(result(name = "grilled chicken"))
        val hedged = ConfidenceScorer.score(result(name = "possibly grilled chicken"))
        assertTrue(hedged.identification < firm.identification)
    }

    @Test
    fun `a single ingredient scores lower on completeness than a full breakdown`() {
        val single = ConfidenceScorer.score(
            result(ingredients = listOf(IngredientDto(name = "rice", weightGrams = 200.0)))
        )
        val multi = ConfidenceScorer.score(
            result(
                ingredients = listOf(
                    IngredientDto(name = "rice", weightGrams = 200.0),
                    IngredientDto(name = "beans", weightGrams = 80.0),
                )
            )
        )
        assertTrue(single.completeness < multi.completeness)
    }

    @Test
    fun `macros that do not add up lower the consistency score`() {
        val coherent = ConfidenceScorer.score(
            result(
                calories = 300,
                ingredients = listOf(IngredientDto(name = "chicken", weightGrams = 150.0, calories = 300)),
            )
        )
        val incoherent = ConfidenceScorer.score(
            result(
                calories = 300,
                ingredients = listOf(IngredientDto(name = "chicken", weightGrams = 150.0, calories = 1200)),
            )
        )
        assertTrue(incoherent.consistency < coherent.consistency)
    }

    @Test
    fun `saturated fat above total fat is flagged as inconsistent`() {
        val impossible = ConfidenceScorer.score(
            result(
                ingredients = listOf(
                    IngredientDto(name = "butter", weightGrams = 10.0, fat = 8.0, saturatedFatG = 20.0)
                )
            )
        )
        val sane = ConfidenceScorer.score(
            result(
                ingredients = listOf(
                    IngredientDto(name = "butter", weightGrams = 10.0, fat = 8.0, saturatedFatG = 5.0)
                )
            )
        )
        assertTrue(impossible.consistency < sane.consistency)
    }

    @Test
    fun `missing ingredient weights lower portion confidence`() {
        val weighed = ConfidenceScorer.score(result())
        val unweighed = ConfidenceScorer.score(result(ingredients = emptyList()))
        assertTrue(unweighed.portion < weighed.portion)
    }

    @Test
    fun `all scores stay within bounds even for pathological input`() {
        val profile = ConfidenceScorer.score(
            FoodAnalysisResult(
                name = "possibly unclear maybe Dal Makhani traditional homemade style",
                calories = 10,
                confidence = "low",
                ingredients = listOf(
                    IngredientDto(name = "", weightGrams = -5.0, calories = 9000, fat = 1.0, saturatedFatG = 99.0)
                ),
            )
        )
        listOf(
            profile.identification, profile.portion, profile.specificity,
            profile.completeness, profile.consistency,
        ).forEach { assertTrue("score out of bounds: $it", it in 0.0..1.0) }
    }
}
