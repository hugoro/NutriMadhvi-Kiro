package com.example.data.meal.ensemble

import com.example.data.nutrition.CorrectionCalibration
import com.example.data.remote.FoodAnalysisResult
import com.example.data.remote.IngredientDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [MealResultJudge]. Hand-written fake [GroqApiService] —
 * no Mockito, no network. Covers: gating (empty calibration / no key),
 * plausibility rejection, fail-open on error, and a PII-free prompt.
 */
class MealResultJudgeTest {

    private fun service(handler: suspend (GroqChatRequest) -> GroqChatResponse) =
        object : GroqApiService {
            override suspend fun chatCompletions(
                bearer: String,
                request: GroqChatRequest,
                model: String,
            ): GroqChatResponse = handler(request)
        }

    private fun responseWith(content: String?) = GroqChatResponse(
        id = "req-judge-1",
        choices = listOf(GroqChoice(message = GroqResponseMessage(content = content))),
    )

    private fun judge(
        apiKey: String = "gsk_test",
        handler: suspend (GroqChatRequest) -> GroqChatResponse,
    ) = MealResultJudge(apiKey = apiKey, service = service(handler))

    private val originalResult = FoodAnalysisResult(
        name = "bread with cheese",
        calories = 600,
        protein = 20.0,
        carbs = 60.0,
        fat = 25.0,
        confidence = "medium",
        ingredients = listOf(IngredientDto(name = "bread", weightGrams = 60.0, calories = 300)),
    )

    private val calibration = CorrectionCalibration(
        calorieBiasPct = 0.25, // AI runs 25% high
        substitutions = listOf(
            CorrectionCalibration.Substitution(from = "sourdough", to = "toast", count = 3)
        ),
        sampleSize = 12,
    )

    // --- gating --------------------------------------------------------------

    @Test
    fun emptyCalibration_returnsOriginalWithoutCalling() = runTest {
        var called = false
        val result = judge { called = true; responseWith("{}") }
            .review(originalResult, CorrectionCalibration())
        assertFalse("must not call the model with an empty calibration", called)
        assertEquals(originalResult, result)
    }

    @Test
    fun blankKey_returnsOriginalWithoutCalling() = runTest {
        var called = false
        val result = judge(apiKey = "") { called = true; responseWith("{}") }
            .review(originalResult, calibration)
        assertFalse(called)
        assertEquals(originalResult, result)
    }

    @Test
    fun placeholderKey_reportsUnavailable() {
        assertFalse(judge(apiKey = "MY_GROQ_API_KEY") { responseWith("{}") }.isAvailable())
    }

    // --- happy path ----------------------------------------------------------

    @Test
    fun plausibleAdjustment_isApplied() = runTest {
        // Judge lowers calories to 480 with consistent macros (Atwater ok).
        val adjusted = """
            {"name":"toast with cheese","calories":480,"protein":18.0,"carbs":48.0,"fat":22.0,
             "fullSummary":"toast with cheese","ingredients":[{"name":"toast","weightGrams":10.0,"calories":50}]}
        """.trimIndent()
        val result = judge { responseWith(adjusted) }.review(originalResult, calibration)
        assertEquals("toast with cheese", result.name)
        assertEquals(480, result.calories)
        // Metadata the judge must not touch is preserved.
        assertEquals("medium", result.confidence)
    }

    @Test
    fun fencedJson_stillParses() = runTest {
        val adjusted = "```json\n{\"name\":\"toast\",\"calories\":480,\"protein\":18.0,\"carbs\":48.0,\"fat\":22.0}\n```"
        val result = judge { responseWith(adjusted) }.review(originalResult, calibration)
        assertEquals("toast", result.name)
        assertEquals(480, result.calories)
    }

    // --- plausibility guard --------------------------------------------------

    @Test
    fun implausibleAdjustment_isRejected_keepsOriginal() = runTest {
        // Atwater-inconsistent: 4*10 + 4*10 + 9*10 = 170 kcal, not 900.
        val bad = """{"name":"x","calories":900,"protein":10.0,"carbs":10.0,"fat":10.0}"""
        val result = judge { responseWith(bad) }.review(originalResult, calibration)
        assertEquals("implausible verdict must be rejected", originalResult, result)
    }

    // --- fail-open -----------------------------------------------------------

    @Test
    fun transportError_returnsOriginal() = runTest {
        val result = judge { throw java.io.IOException("network down") }
            .review(originalResult, calibration)
        assertEquals(originalResult, result)
    }

    @Test
    fun unparseableContent_returnsOriginal() = runTest {
        val result = judge { responseWith("I cannot help with that.") }
            .review(originalResult, calibration)
        assertEquals(originalResult, result)
    }

    @Test
    fun blankCompletion_returnsOriginal() = runTest {
        val result = judge { responseWith(null) }.review(originalResult, calibration)
        assertEquals(originalResult, result)
    }

    // --- prompt hygiene ------------------------------------------------------

    @Test
    fun prompt_isPiiFreeAndCarriesCalibration() = runTest {
        var capturedPrompt: String? = null
        judge {
            capturedPrompt = it.messages.firstOrNull()?.content?.firstOrNull()?.text
            responseWith("""{"name":"toast","calories":480,"protein":18.0,"carbs":48.0,"fat":22.0}""")
        }.review(originalResult, calibration)

        val p = capturedPrompt.orEmpty()
        // Carries the candidate + calibration signal.
        assertTrue("prompt should include the calibration", p.contains("toast") || p.contains("25%"))
        assertTrue("prompt should include the analysis to review", p.contains("bread with cheese"))
        // No image data URIs, no api key.
        assertFalse("prompt must not contain base64 image data", p.contains("data:image"))
        assertFalse("prompt must not contain the api key", p.contains("gsk_test"))
    }

    // --- text-only request shape ---------------------------------------------

    @Test
    fun request_isTextOnly_noImagePart() = runTest {
        var capturedRequest: GroqChatRequest? = null
        judge {
            capturedRequest = it
            responseWith("""{"name":"toast","calories":480,"protein":18.0,"carbs":48.0,"fat":22.0}""")
        }.review(originalResult, calibration)

        val parts = capturedRequest?.messages?.firstOrNull()?.content.orEmpty()
        assertTrue("judge request must have a text part", parts.any { it.text != null })
        assertFalse("judge request must carry NO image part", parts.any { it.imageUrl != null })
    }
}
