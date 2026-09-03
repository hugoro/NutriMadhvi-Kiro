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
 * The malformed-response cases matter most here: the acceptance criteria
 * require that a broken Qwen reply can never corrupt application state.
 */
class QwenGroqFoodVisionProviderTest {

    private val validJson = """
        {"name":"lentil curry","calories":320,"confidence":"high",
         "ingredients":[{"name":"lentils","weightGrams":180.0,"calories":320}]}
    """.trimIndent()

    private fun service(handler: suspend () -> GroqChatResponse) = object : GroqApiService {
        override suspend fun chatCompletions(
            bearer: String,
            request: GroqChatRequest,
            model: String,
        ): GroqChatResponse = handler()
    }

    private fun responseWith(content: String?) = GroqChatResponse(
        id = "req-1",
        choices = listOf(GroqChoice(message = GroqResponseMessage(content = content))),
    )

    private fun provider(
        apiKey: String = "gsk_test",
        handler: suspend () -> GroqChatResponse,
    ) = QwenGroqFoodVisionProvider(
        apiKey = apiKey,
        promptBuilder = { "prompt" },
        service = service(handler),
    )

    private val request = FoodVisionRequest(base64Image = "AAAA", mimeType = "image/jpeg")

    @Test
    fun `a well-formed reply parses into the shared result schema`() = runTest {
        val outcome = provider { responseWith(validJson) }.analyze(request)

        assertTrue(outcome is FoodVisionOutcome.Success)
        val evidence = (outcome as FoodVisionOutcome.Success).evidence
        assertEquals("lentil curry", evidence.result.name)
        assertEquals(320, evidence.result.calories)
        assertEquals("qwen-groq", evidence.provider)
    }

    @Test
    fun `a reply wrapped in prose or a markdown fence still parses`() = runTest {
        val fenced = "Here is the analysis:\n```json\n$validJson\n```\nHope that helps!"
        val outcome = provider { responseWith(fenced) }.analyze(request)

        assertTrue(outcome is FoodVisionOutcome.Success)
        assertEquals("lentil curry", (outcome as FoodVisionOutcome.Success).evidence.result.name)
    }

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
        assertEquals(
            FoodVisionErrorKind.RESULT_UNCERTAIN,
            (outcome as FoodVisionOutcome.Failure).kind,
        )
    }

    @Test
    fun `an empty completion is a failure rather than a blank meal`() = runTest {
        val outcome = provider { responseWith(null) }.analyze(request)

        assertTrue(outcome is FoodVisionOutcome.Failure)
        assertEquals(FoodVisionErrorKind.INVALID_JSON, (outcome as FoodVisionOutcome.Failure).kind)
    }

    @Test
    fun `rate limiting is classified distinctly so the router can react`() = runTest {
        val outcome = provider {
            throw HttpException(
                Response.error<GroqChatResponse>(429, "".toResponseBody("text/plain".toMediaType()))
            )
        }.analyze(request)

        assertEquals(
            FoodVisionErrorKind.RATE_LIMITED,
            (outcome as FoodVisionOutcome.Failure).kind,
        )
    }

    @Test
    fun `server errors are classified as unavailable`() = runTest {
        val outcome = provider {
            throw HttpException(
                Response.error<GroqChatResponse>(503, "".toResponseBody("text/plain".toMediaType()))
            )
        }.analyze(request)

        assertEquals(
            FoodVisionErrorKind.UNAVAILABLE,
            (outcome as FoodVisionOutcome.Failure).kind,
        )
    }

    @Test
    fun `payload rejections are classified so they are never retried blindly`() = runTest {
        val outcome = provider {
            throw HttpException(
                Response.error<GroqChatResponse>(413, "".toResponseBody("text/plain".toMediaType()))
            )
        }.analyze(request)

        assertEquals(
            FoodVisionErrorKind.PAYLOAD_TOO_LARGE,
            (outcome as FoodVisionOutcome.Failure).kind,
        )
    }

    @Test
    fun `an oversized image is shrunk before the call rather than rejected by the server`() = runTest {
        val oversized = "A".repeat(600_000)
        var sentImage: String? = null
        val shrunkPayload = "B".repeat(1_000)

        val p = QwenGroqFoodVisionProvider(
            apiKey = "gsk_test",
            promptBuilder = { "prompt" },
            service = object : GroqApiService {
                override suspend fun chatCompletions(
                    bearer: String,
                    request: GroqChatRequest,
                    model: String,
                ): GroqChatResponse {
                    sentImage = request.messages.first().content
                        .firstOrNull { it.imageUrl != null }?.imageUrl?.url
                    return responseWith(validJson)
                }
            },
            imageShrinker = { _, budget -> shrunkPayload.take(budget) },
        )

        val outcome = p.analyze(request.copy(base64Image = oversized))

        assertTrue(outcome is FoodVisionOutcome.Success)
        assertTrue(sentImage!!.endsWith(shrunkPayload))
    }

    @Test
    fun `an image that cannot be shrunk fails fast without spending a call`() = runTest {
        var called = false
        val p = QwenGroqFoodVisionProvider(
            apiKey = "gsk_test",
            promptBuilder = { "prompt" },
            service = object : GroqApiService {
                override suspend fun chatCompletions(
                    bearer: String,
                    request: GroqChatRequest,
                    model: String,
                ): GroqChatResponse {
                    called = true
                    return responseWith(validJson)
                }
            },
            imageShrinker = { _, _ -> null },
        )

        val outcome = p.analyze(request.copy(base64Image = "A".repeat(600_000)))

        assertEquals(
            FoodVisionErrorKind.PAYLOAD_TOO_LARGE,
            (outcome as FoodVisionOutcome.Failure).kind,
        )
        assertFalse("no HTTP call should be made for a payload known to be rejected", called)
    }

    @Test
    fun `network errors are classified as request failures`() = runTest {
        val outcome = provider { throw IOException("offline") }.analyze(request)

        assertEquals(
            FoodVisionErrorKind.REQUEST_FAILED,
            (outcome as FoodVisionOutcome.Failure).kind,
        )
    }

    @Test
    fun `a missing or placeholder key makes the provider report itself unavailable`() = runTest {
        val blank = provider(apiKey = "") { responseWith(validJson) }
        val placeholder = provider(apiKey = "MY_GROQ_API_KEY") { responseWith(validJson) }

        assertTrue(!blank.isAvailable())
        assertTrue(!placeholder.isAvailable())
        assertEquals(
            FoodVisionErrorKind.NOT_CONFIGURED,
            (blank.analyze(request) as FoodVisionOutcome.Failure).kind,
        )
    }

    @Test
    fun `habit ids the model was never offered are filtered out`() = runTest {
        val withHabits = """
            {"name":"lentil curry","calories":320,"appliedHabitIds":[1,2,99],
             "ingredients":[{"name":"lentils","weightGrams":180.0}]}
        """.trimIndent()

        val outcome = provider { responseWith(withHabits) }.analyze(
            request.copy(offeredHabitIds = setOf(1L, 2L))
        )

        val applied = (outcome as FoodVisionOutcome.Success).evidence.result.appliedHabitIds
        assertEquals(listOf(1L, 2L), applied)
    }

    @Test
    fun `the image is sent as a transient data url without touching any file`() = runTest {
        var captured: GroqChatRequest? = null
        val capturing = QwenGroqFoodVisionProvider(
            apiKey = "gsk_test",
            promptBuilder = { "prompt" },
            service = object : GroqApiService {
                override suspend fun chatCompletions(
                    bearer: String,
                    request: GroqChatRequest,
                    model: String,
                ): GroqChatResponse {
                    captured = request
                    return responseWith(validJson)
                }
            },
        )

        capturing.analyze(FoodVisionRequest(base64Image = "AAAA", mimeType = "image/heic"))

        val parts = captured!!.messages.single().content
        val image = parts.single { it.type == "image_url" }
        assertEquals("data:image/heic;base64,AAAA", image.imageUrl?.url)
    }
}
