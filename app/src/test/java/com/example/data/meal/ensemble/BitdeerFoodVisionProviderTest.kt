package com.example.data.meal.ensemble

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Phase 9 — unit tests for [BitdeerFoodVisionProvider].
 *
 * Mirrors [QwenGroqFoodVisionProviderTest] in structure; exercises the same
 * correctness properties:
 *   - well-formed reply parses into the shared [FoodAnalysisResult] schema
 *   - prose / markdown fences around the JSON still parse
 *   - unparseable content → INVALID_JSON (never corrupts application state)
 *   - structurally valid but semantically empty result → RESULT_UNCERTAIN
 *   - missing API key → NOT_CONFIGURED (provider reports unavailable)
 *   - network errors → typed failures
 *   - HTTP 4xx / 5xx → mapped to correct [FoodVisionErrorKind]
 *   - analysisModel is stamped on successful results (badge contract)
 *   - habit IDs are filtered to offered set (safety filter)
 *
 * Uses a hand-wired [BitdeerApiService] fake — no Mockito, no Android, plain JVM.
 */
class BitdeerFoodVisionProviderTest {

    private val validJson = """
        {"name":"grilled salmon","calories":420,"confidence":"high",
         "ingredients":[{"name":"salmon fillet","weightGrams":200.0,"calories":420}]}
    """.trimIndent()

    private fun service(handler: suspend (GroqChatRequest) -> GroqChatResponse) =
        object : BitdeerApiService {
            override suspend fun chatCompletions(
                bearer: String,
                request: GroqChatRequest,
            ): GroqChatResponse = handler(request)
        }

    private fun responseWith(content: String?) = GroqChatResponse(
        id = "req-bitdeer-1",
        choices = listOf(GroqChoice(message = GroqResponseMessage(content = content))),
    )

    private fun provider(
        apiKey: String = "btd_test_key",
        handler: suspend (GroqChatRequest) -> GroqChatResponse = { responseWith(validJson) },
    ) = BitdeerFoodVisionProvider(
        apiKey = apiKey,
        promptBuilder = { "prompt" },
        service = service(handler),
    )

    private val request = FoodVisionRequest(base64Image = "AAAA", mimeType = "image/jpeg")

    // --- happy path ----------------------------------------------------------

    @Test
    fun `a well-formed reply parses into the shared result schema`() = runTest {
        val outcome = provider().analyze(request)

        assertTrue(outcome is FoodVisionOutcome.Success)
        val evidence = (outcome as FoodVisionOutcome.Success).evidence
        assertEquals("grilled salmon", evidence.result.name)
        assertEquals(420, evidence.result.calories)
        assertEquals("bitdeer-qwen3vl", evidence.provider)
    }

    @Test
    fun `provider id is bitdeer-qwen3vl`() {
        assertEquals("bitdeer-qwen3vl", provider().id)
    }

    @Test
    fun `analysisModel is stamped with the Bitdeer model id`() = runTest {
        val outcome = provider().analyze(request)

        assertTrue(outcome is FoodVisionOutcome.Success)
        val result = (outcome as FoodVisionOutcome.Success).evidence.result
        assertEquals("Qwen/Qwen3-VL-32B", result.analysisModel)
    }

    @Test
    fun `a reply wrapped in prose or a markdown fence still parses`() = runTest {
        val fenced = "Here is the analysis:\n```json\n$validJson\n```\nHope that helps!"
        val outcome = provider { responseWith(fenced) }.analyze(request)

        assertTrue(outcome is FoodVisionOutcome.Success)
        assertEquals("grilled salmon", (outcome as FoodVisionOutcome.Success).evidence.result.name)
    }

    // --- failure cases -------------------------------------------------------

    @Test
    fun `unparseable content is reported as invalid json, never as a result`() = runTest {
        val outcome = provider { responseWith("I cannot analyse this image.") }.analyze(request)

        assertTrue(outcome is FoodVisionOutcome.Failure)
        assertEquals(FoodVisionErrorKind.INVALID_JSON, (outcome as FoodVisionOutcome.Failure).kind)
    }

    @Test
    fun `a structurally valid but empty result is treated as uncertain`() = runTest {
        val outcome = provider { responseWith("""{"calories":0}""") }.analyze(request)

        assertTrue(outcome is FoodVisionOutcome.Failure)
        assertEquals(FoodVisionErrorKind.RESULT_UNCERTAIN, (outcome as FoodVisionOutcome.Failure).kind)
    }

    @Test
    fun `empty choices list is treated as invalid json`() = runTest {
        val outcome = provider {
            GroqChatResponse(id = "r", choices = emptyList())
        }.analyze(request)

        assertTrue(outcome is FoodVisionOutcome.Failure)
        assertEquals(FoodVisionErrorKind.INVALID_JSON, (outcome as FoodVisionOutcome.Failure).kind)
    }

    @Test
    fun `null message content is treated as invalid json`() = runTest {
        val outcome = provider { responseWith(null) }.analyze(request)

        assertTrue(outcome is FoodVisionOutcome.Failure)
        assertEquals(FoodVisionErrorKind.INVALID_JSON, (outcome as FoodVisionOutcome.Failure).kind)
    }

    // --- availability --------------------------------------------------------

    @Test
    fun `blank api key reports provider as unavailable`() {
        assertFalse(provider(apiKey = "").isAvailable())
    }

    @Test
    fun `placeholder api key reports provider as unavailable`() {
        assertFalse(provider(apiKey = "MY_BITDEER_API_KEY").isAvailable())
    }

    @Test
    fun `real api key reports provider as available`() {
        assertTrue(provider(apiKey = "btd_real_key").isAvailable())
    }

    @Test
    fun `analyze with blank key returns NOT_CONFIGURED without calling service`() = runTest {
        var called = false
        val outcome = provider(apiKey = "") {
            called = true
            responseWith(validJson)
        }.analyze(request)

        assertFalse(called)
        assertTrue(outcome is FoodVisionOutcome.Failure)
        assertEquals(FoodVisionErrorKind.NOT_CONFIGURED, (outcome as FoodVisionOutcome.Failure).kind)
    }

    // --- network and HTTP errors ---------------------------------------------

    @Test
    fun `IOException is reported as REQUEST_FAILED`() = runTest {
        val outcome = provider { throw IOException("network error") }.analyze(request)

        assertTrue(outcome is FoodVisionOutcome.Failure)
        assertEquals(FoodVisionErrorKind.REQUEST_FAILED, (outcome as FoodVisionOutcome.Failure).kind)
    }

    @Test
    fun `HTTP 429 is reported as RATE_LIMITED`() = runTest {
        val outcome = provider {
            throw HttpException(
                Response.error<Any>(429, "Too Many Requests".toResponseBody("text/plain".toMediaType()))
            )
        }.analyze(request)

        assertTrue(outcome is FoodVisionOutcome.Failure)
        assertEquals(FoodVisionErrorKind.RATE_LIMITED, (outcome as FoodVisionOutcome.Failure).kind)
    }

    @Test
    fun `HTTP 413 is reported as PAYLOAD_TOO_LARGE`() = runTest {
        val outcome = provider {
            throw HttpException(
                Response.error<Any>(413, "Payload Too Large".toResponseBody("text/plain".toMediaType()))
            )
        }.analyze(request)

        assertTrue(outcome is FoodVisionOutcome.Failure)
        assertEquals(FoodVisionErrorKind.PAYLOAD_TOO_LARGE, (outcome as FoodVisionOutcome.Failure).kind)
    }

    @Test
    fun `HTTP 503 is reported as UNAVAILABLE`() = runTest {
        val outcome = provider {
            throw HttpException(
                Response.error<Any>(503, "Service Unavailable".toResponseBody("text/plain".toMediaType()))
            )
        }.analyze(request)

        assertTrue(outcome is FoodVisionOutcome.Failure)
        assertEquals(FoodVisionErrorKind.UNAVAILABLE, (outcome as FoodVisionOutcome.Failure).kind)
    }

    // --- habit filtering -----------------------------------------------------

    @Test
    fun `appliedHabitIds are filtered to the offered set`() = runTest {
        val jsonWithHabits = """
            {"name":"pasta","calories":600,"confidence":"high",
             "appliedHabitIds":[1,2,3,99],
             "ingredients":[{"name":"pasta","weightGrams":200.0,"calories":600}]}
        """.trimIndent()
        val offeredRequest = request.copy(offeredHabitIds = setOf(1L, 3L))

        val outcome = provider { responseWith(jsonWithHabits) }.analyze(offeredRequest)

        assertTrue(outcome is FoodVisionOutcome.Success)
        val applied = (outcome as FoodVisionOutcome.Success).evidence.result.appliedHabitIds
        assertEquals(listOf(1L, 3L), applied?.sorted())
    }

    // --- reasoning_effort field omitted for Bitdeer --------------------------

    @Test
    fun `reasoning_effort is null in the outgoing request (Bitdeer does not support it)`() = runTest {
        var capturedRequest: GroqChatRequest? = null
        provider {
            capturedRequest = it
            responseWith(validJson)
        }.analyze(request)

        assertEquals(null, capturedRequest?.reasoningEffort)
    }
}
