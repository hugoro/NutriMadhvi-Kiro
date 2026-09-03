package com.example.data.meal.ensemble

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the pre-flight token budget: the point is that a request which cannot
 * possibly be served is never put on the wire.
 */
class GroqTokenBudgetTest {

    private val model = "qwen/qwen3.6-27b"

    @Before fun setUp() = GroqTokenBudget.reset()
    @After fun tearDown() = GroqTokenBudget.reset()

    @Test
    fun `fails open when nothing has been observed`() {
        assertTrue(GroqTokenBudget.canAfford(model, estimatedTokens = 5_000, nowMs = 0L))
        assertNull(GroqTokenBudget.remainingFor(model, nowMs = 0L))
    }

    @Test
    fun `blocks a request larger than the remaining allowance`() {
        GroqTokenBudget.record(model, remainingTokens = 1_400, resetAfterMs = 30_000, nowMs = 0L)
        assertFalse(GroqTokenBudget.canAfford(model, estimatedTokens = 3_000, nowMs = 1_000L))
    }

    @Test
    fun `allows a request that still fits`() {
        GroqTokenBudget.record(model, remainingTokens = 5_000, resetAfterMs = 30_000, nowMs = 0L)
        assertTrue(GroqTokenBudget.canAfford(model, estimatedTokens = 3_000, nowMs = 1_000L))
    }

    @Test
    fun `stale observation stops blocking once the window resets`() {
        GroqTokenBudget.record(model, remainingTokens = 0, resetAfterMs = 10_000, nowMs = 0L)
        assertFalse(GroqTokenBudget.canAfford(model, estimatedTokens = 100, nowMs = 5_000L))
        // Past the reset the allowance has refilled; a stale zero must not
        // keep the provider disabled forever.
        assertTrue(GroqTokenBudget.canAfford(model, estimatedTokens = 100, nowMs = 10_001L))
    }

    @Test
    fun `budget is tracked per model`() {
        GroqTokenBudget.record(model, remainingTokens = 0, resetAfterMs = 30_000, nowMs = 0L)
        assertFalse(GroqTokenBudget.canAfford(model, 1_000, nowMs = 1L))
        // Exhausting one model says nothing about another — this is what makes
        // "pick a model that still has budget" possible.
        assertTrue(GroqTokenBudget.canAfford("qwen/qwen3.8-27b", 1_000, nowMs = 1L))
    }

    @Test
    fun `parses groq duration headers`() {
        assertEquals(134L, GroqTokenBudget.parseResetDuration("134ms"))
        assertEquals(7_660L, GroqTokenBudget.parseResetDuration("7.66s"))
        assertEquals(86_400L, GroqTokenBudget.parseResetDuration("1m26.4s"))
        assertNull(GroqTokenBudget.parseResetDuration(null))
        assertNull(GroqTokenBudget.parseResetDuration(""))
        assertNull(GroqTokenBudget.parseResetDuration("soon"))
    }

    @Test
    fun `provider refuses to call the api when the window is exhausted`() = runTest {
        var called = false
        val provider = QwenGroqFoodVisionProvider(
            apiKey = "test-key",
            promptBuilder = { "prompt" },
            service = object : GroqApiService {
                override suspend fun chatCompletions(
                    bearer: String,
                    request: GroqChatRequest,
                    model: String,
                ): GroqChatResponse {
                    called = true
                    return GroqChatResponse()
                }
            },
            model = model,
            nowMs = { 1_000L },
        )

        GroqTokenBudget.record(model, remainingTokens = 10, resetAfterMs = 60_000, nowMs = 1_000L)

        val outcome = provider.analyze(
            FoodVisionRequest(base64Image = null, mimeType = "image/jpeg")
        )

        assertFalse("request must not reach the network", called)
        val failure = outcome as FoodVisionOutcome.Failure
        assertEquals(FoodVisionErrorKind.RATE_LIMITED, failure.kind)
    }
}
