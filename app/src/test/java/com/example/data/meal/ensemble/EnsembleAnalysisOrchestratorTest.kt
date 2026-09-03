package com.example.data.meal.ensemble

import com.example.data.remote.FoodAnalysisResult
import com.example.data.remote.IngredientDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the escalation policy end-to-end with fake providers, so every
 * fallback branch is verified without spending a single paid API token.
 */
class EnsembleAnalysisOrchestratorTest {

    private class FakeProvider(
        override val id: String,
        private val available: Boolean = true,
        private val outcome: (FoodVisionRequest) -> FoodVisionOutcome,
    ) : FoodVisionProvider {
        var callCount = 0
            private set
        var lastRequest: FoodVisionRequest? = null
            private set

        override fun isAvailable() = available

        override suspend fun analyze(request: FoodVisionRequest): FoodVisionOutcome {
            callCount++
            lastRequest = request
            return outcome(request)
        }
    }

    private fun success(
        provider: String,
        name: String,
        confidence: ConfidenceProfile,
    ): FoodVisionOutcome = FoodVisionOutcome.Success(
        FoodAnalysisEvidence(
            provider = provider,
            result = FoodAnalysisResult(
                name = name,
                calories = 300,
                ingredients = listOf(IngredientDto(name = name, weightGrams = 150.0, calories = 300)),
            ),
            latencyMs = 50L,
            confidence = confidence,
        )
    )

    private val strong = ConfidenceProfile(0.95, 0.90, 0.90, 0.95, 0.95)
    private val weak = ConfidenceProfile(0.40, 0.40, 0.40, 0.40, 0.40)

    private val request = FoodVisionRequest(base64Image = "AAAA", promptHint = "almoço")

    @Test
    fun `a confident primary short-circuits the challenger`() = runTest {
        val primary = FakeProvider("qwen-groq") { success("qwen-groq", "lentil curry", strong) }
        val challenger = FakeProvider("gemini") { success("gemini", "lentil curry", strong) }

        val result = EnsembleAnalysisOrchestrator(primary, challenger, logger = {}).analyze(request)

        assertNotNull(result)
        assertEquals("qwen-groq", result!!.evidence.provider)
        assertEquals(0, challenger.callCount)
        assertTrue(result.decisionPath.contains("gate_accept"))
    }

    @Test
    fun `a failing primary falls straight through to the challenger`() = runTest {
        val primary = FakeProvider("qwen-groq") {
            FoodVisionOutcome.Failure("qwen-groq", FoodVisionErrorKind.UNAVAILABLE)
        }
        val challenger = FakeProvider("gemini") { success("gemini", "lentil curry", strong) }

        val result = EnsembleAnalysisOrchestrator(primary, challenger, logger = {}).analyze(request)

        assertNotNull(result)
        assertEquals("gemini", result!!.evidence.provider)
        assertEquals(1, challenger.callCount)
    }

    @Test
    fun `a weak primary triggers exactly one challenger call`() = runTest {
        val primary = FakeProvider("qwen-groq") { success("qwen-groq", "lentil curry", weak) }
        val challenger = FakeProvider("gemini") { success("gemini", "lentil curry", strong) }

        val result = EnsembleAnalysisOrchestrator(primary, challenger, logger = {}).analyze(request)

        assertEquals(1, challenger.callCount)
        assertTrue(result!!.decisionPath.contains("challenger_ran"))
    }

    @Test
    fun `a failing challenger keeps the primary result instead of erroring`() = runTest {
        val primary = FakeProvider("qwen-groq") { success("qwen-groq", "lentil curry", weak) }
        val challenger = FakeProvider("gemini") {
            FoodVisionOutcome.Failure("gemini", FoodVisionErrorKind.RATE_LIMITED)
        }

        val result = EnsembleAnalysisOrchestrator(primary, challenger, logger = {}).analyze(request)

        assertNotNull("user must still get usable numbers", result)
        assertEquals("qwen-groq", result!!.evidence.provider)
        assertTrue(result.decisionPath.contains("kept_primary"))
    }

    @Test
    fun `all providers failing yields null so the caller keeps its salvage path`() = runTest {
        val primary = FakeProvider("qwen-groq") {
            FoodVisionOutcome.Failure("qwen-groq", FoodVisionErrorKind.REQUEST_FAILED)
        }
        val challenger = FakeProvider("gemini") {
            FoodVisionOutcome.Failure("gemini", FoodVisionErrorKind.REQUEST_FAILED)
        }

        val result = EnsembleAnalysisOrchestrator(primary, challenger, logger = {}).analyze(request)

        assertNull(result)
    }

    @Test
    fun `material disagreement invokes the adjudicator with the original image`() = runTest {
        val primary = FakeProvider("qwen-groq") { success("qwen-groq", "chicken breast", weak) }
        val challenger = FakeProvider("gemini") { success("gemini", "chicken thigh", strong) }
        val judge = FakeProvider("qwen-judge") { success("qwen-judge", "chicken thigh", strong) }

        val result = EnsembleAnalysisOrchestrator(primary, challenger, judge, logger = {})
            .analyze(request)

        assertEquals(1, judge.callCount)
        assertEquals("qwen-judge", result!!.evidence.provider)
        assertEquals("adjudicator must see the original image", "AAAA", judge.lastRequest?.base64Image)
        assertTrue(judge.lastRequest?.promptHint.orEmpty().contains("ADJUDICATION TASK"))
    }

    @Test
    fun `a failed adjudication falls back to the safest validated answer`() = runTest {
        val primary = FakeProvider("qwen-groq") { success("qwen-groq", "chicken breast", weak) }
        val challenger = FakeProvider("gemini") { success("gemini", "chicken thigh", strong) }
        val judge = FakeProvider("qwen-judge") {
            FoodVisionOutcome.Failure("qwen-judge", FoodVisionErrorKind.TIMEOUT)
        }

        val result = EnsembleAnalysisOrchestrator(primary, challenger, judge, logger = {})
            .analyze(request)

        assertNotNull(result)
        assertTrue(result!!.decisionPath.contains("adjudication_failed"))
        // Never corrupt state: the answer must be one we already validated.
        assertTrue(result.evidence.provider in setOf("qwen-groq", "gemini"))
    }

    @Test
    fun `adjudication is skipped for text-only requests that add no visual evidence`() = runTest {
        val textOnly = FoodVisionRequest(base64Image = null, reanalysisSummary = "editado")
        val primary = FakeProvider("qwen-groq") { success("qwen-groq", "chicken breast", weak) }
        val challenger = FakeProvider("gemini") { success("gemini", "chicken thigh", strong) }
        val judge = FakeProvider("qwen-judge") { success("qwen-judge", "chicken thigh", strong) }

        EnsembleAnalysisOrchestrator(primary, challenger, judge, logger = {}).analyze(textOnly)

        assertEquals("no image means the judge adds nothing", 0, judge.callCount)
    }

    @Test
    fun `an unconfigured provider is skipped rather than invoked and failed`() = runTest {
        val primary = FakeProvider("qwen-groq", available = false) {
            throw AssertionError("must not be invoked when unavailable")
        }
        val challenger = FakeProvider("gemini") { success("gemini", "lentil curry", strong) }

        val result = EnsembleAnalysisOrchestrator(primary, challenger, logger = {}).analyze(request)

        assertEquals(0, primary.callCount)
        assertEquals("gemini", result!!.evidence.provider)
        assertTrue(result.failures.any { it.kind == FoodVisionErrorKind.NOT_CONFIGURED })
    }

    @Test
    fun `disabling challengers caps the run at a single paid call`() = runTest {
        val primary = FakeProvider("qwen-groq") { success("qwen-groq", "lentil curry", weak) }
        val challenger = FakeProvider("gemini") { success("gemini", "lentil curry", strong) }

        val result = EnsembleAnalysisOrchestrator(
            primary, challenger,
            policy = EnsemblePolicy.DEFAULT.copy(maxChallengers = 0),
            logger = {},
        ).analyze(request)

        assertEquals(0, challenger.callCount)
        assertEquals("qwen-groq", result!!.evidence.provider)
    }
}
