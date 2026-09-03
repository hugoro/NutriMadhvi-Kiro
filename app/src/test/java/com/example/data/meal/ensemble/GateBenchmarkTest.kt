package com.example.data.meal.ensemble

import com.example.data.remote.FoodAnalysisResult
import com.example.data.remote.IngredientDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard built from a real benchmark.
 *
 * Six meal photos from the device were sent to both Groq-hosted Qwen models and
 * scored against the user-validated entries stored for those same photos.
 * Median calorie error was 61% (qwen3.6) and 68% (qwen3.8), with outright
 * misidentifications. These are the actual answers returned.
 *
 * The gate must reject every one of them. If a future threshold change lets any
 * through, a model measurably worse than Gemini would start reaching the user.
 */
class GateBenchmarkTest {

    private fun result(name: String, kcal: Int, ings: List<Pair<String, Double>>) =
        FoodAnalysisResult(
            name = name,
            calories = kcal,
            protein = 10.0, carbs = 40.0, fat = 15.0,
            confidence = null,
            ingredients = ings.map { (n, g) ->
                IngredientDto(name = n, weightGrams = g, calories = (g * 2).toInt())
            },
        )

    @Test
    fun `no real qwen benchmark answer passes the gate`() {
        val cases = listOf(
            Triple("Café com leite e pasteis de nata", 385, listOf("café com leite" to 200.0, "pastel de nata" to 60.0)),
            Triple("Café com ovo, queijo e pão", 585, listOf("ovo" to 60.0, "queijo" to 30.0, "pão" to 80.0)),
            Triple("Tigela de pera e figo", 183, listOf("pera" to 120.0, "figo" to 80.0)),
            Triple("Café com leite e biscoitos", 365, listOf("café" to 200.0, "biscoito" to 50.0)),
            Triple("Refogado de arroz, pão de queijo e café", 646, listOf("arroz" to 150.0, "pão" to 80.0)),
            Triple("Ovos mexidos咖喱 com pão bolachas e leite", 563, listOf("ovo" to 100.0, "pão" to 70.0)),
        )

        var passed = 0
        cases.forEach { (name, kcal, ings) ->
            val evidence = FoodAnalysisEvidence(
                provider = "qwen-groq",
                result = result(name, kcal, ings),
                latencyMs = 2000L,
                confidence = ConfidenceScorer.score(result(name, kcal, ings)),
            )
            val decision = EnsembleRouter.gate(evidence, EnsemblePolicy.DEFAULT)
            val verdict = if (decision is RoutingDecision.Accept) "ACEITE".also { passed++ } else "escala"
            println("GATE $verdict | $name | conf=${evidence.confidence}")
        }
        println("GATE_SUMMARY aceites=$passed/${cases.size}")
        assertEquals(
            "a model benchmarked at ~60% calorie error must never pass the gate unchallenged",
            0, passed,
        )
    }
}
