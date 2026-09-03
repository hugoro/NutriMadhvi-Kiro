package com.example.data.meal.ensemble

import com.example.data.remote.FoodAnalysisResult
import com.example.data.remote.IngredientDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canonicalisation is the difference between a free consensus and a paid
 * adjudication round, so the two cases from the design brief are pinned here.
 */
class FoodLabelCanonicalizerTest {

    @Test
    fun `descriptive variants of the same dish are not a disagreement`() {
        assertFalse(
            FoodLabelCanonicalizer.isMaterialDisagreement("yellow lentil curry", "lentil curry")
        )
        assertEquals(
            FoodLabelCanonicalizer.canonicalLabel("yellow lentil curry"),
            FoodLabelCanonicalizer.canonicalLabel("lentil curry"),
        )
    }

    @Test
    fun `cross-language synonyms collapse onto the same concept`() {
        assertEquals(
            FoodLabelCanonicalizer.canonicalLabel("caril de lentilhas"),
            FoodLabelCanonicalizer.canonicalLabel("lentil curry"),
        )
        assertFalse(FoodLabelCanonicalizer.isMaterialDisagreement("dal", "lentils"))
    }

    @Test
    fun `different cuts of the same animal are a material disagreement`() {
        assertTrue(
            FoodLabelCanonicalizer.isMaterialDisagreement("chicken breast", "chicken thigh")
        )
    }

    @Test
    fun `completely different foods are a material disagreement`() {
        assertTrue(FoodLabelCanonicalizer.isMaterialDisagreement("grilled salmon", "beef stew"))
    }

    @Test
    fun `blank labels never fabricate a disagreement`() {
        assertFalse(FoodLabelCanonicalizer.isMaterialDisagreement(null, "rice"))
        assertFalse(FoodLabelCanonicalizer.isMaterialDisagreement("", "rice"))
    }

    @Test
    fun `similarity is symmetric and bounded`() {
        val a = FoodLabelCanonicalizer.similarity("lentil curry", "yellow lentil curry")
        val b = FoodLabelCanonicalizer.similarity("yellow lentil curry", "lentil curry")
        assertEquals(a, b, 0.0001)
        assertTrue(a in 0.0..1.0)
        assertEquals(1.0, a, 0.0001)
    }

    @Test
    fun `large portion drift is reported but only extreme drift is material`() {
        fun result(name: String, grams: Double) = FoodAnalysisResult(
            name = name,
            ingredients = listOf(IngredientDto(name = name, weightGrams = grams)),
        )

        val moderate = FoodLabelCanonicalizer.compare(result("rice", 200.0), result("rice", 260.0))
        val portion = moderate.single { it.field == "portionGrams" }
        assertFalse("30% drift should not trigger paid adjudication", portion.material)

        val extreme = FoodLabelCanonicalizer.compare(result("rice", 100.0), result("rice", 400.0))
        assertTrue(extreme.single { it.field == "portionGrams" }.material)
    }

    @Test
    fun `identical readings produce no disagreements at all`() {
        val r = FoodAnalysisResult(
            name = "lentil curry",
            ingredients = listOf(IngredientDto(name = "lentils", weightGrams = 150.0)),
        )
        assertTrue(FoodLabelCanonicalizer.compare(r, r).isEmpty())
    }
}
