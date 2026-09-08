package com.example.data.ai.provider.gemini

import com.example.data.ai.provider.ChatError
import com.example.data.ai.provider.ChatErrorKind
import com.example.data.ai.provider.ChatEvent
import com.example.data.ai.provider.ChatGeneration
import com.example.data.ai.provider.ChatImage
import com.example.data.ai.provider.ChatMessagePart
import com.example.data.ai.provider.ChatRequest
import com.example.data.ai.provider.ChatRole
import com.example.data.ai.provider.GeminiPassThrough
import com.example.data.remote.GeminiRequest
import com.example.data.remote.StreamChunk
import com.example.data.remote.UsageMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 (plan §21) — verifies the Gemini adapter is a faithful, behaviour-
 * preserving wrapper over [com.example.data.remote.GeminiStreamingClient]:
 *   - request mapping (roles, system instruction, images, cache ref, thinking);
 *   - StreamChunk → ChatEvent relabelling with normalized errors;
 *   - availability gate (placeholder key = not configured).
 *
 * No Android framework: the base64 encoder and the streamer are injected, so
 * this runs as a plain JVM unit test alongside the ensemble tests.
 */
class GeminiChatProviderTest {

    private val fakeBase64: (ByteArray) -> String = { "B64(${it.size})" }

    private fun provider(
        key: String = "AQ.real-key",
        streamer: (String, String, GeminiRequest) -> Flow<StreamChunk>,
    ) = GeminiChatProvider(
        model = "flash-x",
        apiKeyProvider = { key },
        streamer = streamer,
        base64Encoder = fakeBase64,
    )

    private fun textRequest(text: String = "olá") = ChatRequest(
        systemInstruction = "És a NutriMadhvi.",
        messages = listOf(ChatMessagePart(ChatRole.USER, text)),
    )

    @Test
    fun id_is_provider_colon_model() {
        val p = provider { _, _, _ -> flowOf() }
        assertEquals("gemini:flash-x", p.id.value)
        assertEquals("gemini", p.id.provider)
        assertEquals("flash-x", p.id.model)
    }

    @Test
    fun placeholder_key_is_not_available() {
        val p = provider(key = "MY_GEMINI_API_KEY") { _, _, _ -> flowOf() }
        assertTrue(!p.isAvailable())
    }

    @Test
    fun blank_key_is_not_available() {
        val p = provider(key = "") { _, _, _ -> flowOf() }
        assertTrue(!p.isAvailable())
    }

    @Test
    fun real_key_is_available() {
        val p = provider(key = "AQ.something") { _, _, _ -> flowOf() }
        assertTrue(p.isAvailable())
    }

    @Test
    fun partial_then_done_maps_to_delta_then_completed() = runTest {
        var captured: GeminiRequest? = null
        val p = provider { _, _, req ->
            captured = req
            flow {
                emit(StreamChunk.Partial("Olá ", "Olá "))
                emit(StreamChunk.Partial("mundo", "Olá mundo"))
                emit(StreamChunk.Done("Olá mundo", "STOP", UsageMetadata(10, 5, 15)))
            }
        }

        val events = p.stream(textRequest()).toList()

        assertEquals(3, events.size)
        assertEquals(ChatEvent.Delta("Olá ", "Olá "), events[0])
        assertEquals(ChatEvent.Delta("mundo", "Olá mundo"), events[1])
        val done = events[2] as ChatEvent.Completed
        assertEquals("Olá mundo", done.fullText)
        assertEquals("gemini:flash-x", done.providerModel.value)
        assertEquals(10, done.usage?.promptTokens)
        assertEquals(5, done.usage?.completionTokens)
        assertEquals(15, done.usage?.totalTokens)

        // Request mapping: system instruction lifted out, single user turn.
        assertEquals("És a NutriMadhvi.", captured?.systemInstruction?.parts?.first()?.text)
        assertEquals(1, captured?.contents?.size)
        assertEquals("user", captured?.contents?.first()?.role)
        assertEquals("olá", captured?.contents?.first()?.parts?.first()?.text)
    }

    @Test
    fun done_without_usage_yields_null_usage_not_fabricated() = runTest {
        val p = provider { _, _, _ ->
            flowOf(StreamChunk.Done("resposta", "STOP", usage = null))
        }
        val done = p.stream(textRequest()).toList().single() as ChatEvent.Completed
        assertNull(done.usage)
    }

    @Test
    fun truncation_error_maps_to_invalid_response_with_partial() = runTest {
        val cause = IllegalStateException("Stream truncated: finishReason=MAX_TOKENS")
        val p = provider { _, _, _ ->
            flow {
                emit(StreamChunk.Partial("88.3 kg para", "88.3 kg para"))
                emit(StreamChunk.Error(cause, partial = "88.3 kg para"))
            }
        }
        val events = p.stream(textRequest()).toList()
        val failed = events.last() as ChatEvent.Failed
        assertEquals(ChatErrorKind.INVALID_RESPONSE, failed.error.kind)
        assertEquals("88.3 kg para", failed.error.partialText)
        assertTrue(failed.error.hadPartialOutput)
        assertSame(cause, failed.error.cause)
    }

    @Test
    fun http_429_maps_to_rate_limited() = runTest {
        val p = provider { _, _, _ ->
            flowOf(StreamChunk.Error(IllegalStateException("HTTP 429: rate limit")))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.RATE_LIMITED, failed.error.kind)
        assertTrue(!failed.error.hadPartialOutput)
    }

    @Test
    fun http_503_maps_to_server_error() = runTest {
        val p = provider { _, _, _ ->
            flowOf(StreamChunk.Error(IllegalStateException("HTTP 503: unavailable")))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.SERVER_ERROR, failed.error.kind)
    }

    @Test
    fun http_401_maps_to_auth_or_config() = runTest {
        val p = provider { _, _, _ ->
            flowOf(StreamChunk.Error(IllegalStateException("HTTP 401: bad key")))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.AUTH_OR_CONFIG, failed.error.kind)
    }

    @Test
    fun socket_timeout_maps_to_timeout() = runTest {
        val p = provider { _, _, _ ->
            flowOf(StreamChunk.Error(java.net.SocketTimeoutException("read timed out")))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.TIMEOUT, failed.error.kind)
    }

    @Test
    fun unknown_host_maps_to_network() = runTest {
        val p = provider { _, _, _ ->
            flowOf(StreamChunk.Error(java.net.UnknownHostException("no dns")))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.NETWORK, failed.error.kind)
    }

    @Test
    fun images_attached_to_last_user_turn_and_base64_encoded() = runTest {
        var captured: GeminiRequest? = null
        val p = provider { _, _, req ->
            captured = req
            flowOf(StreamChunk.Done("ok", "STOP"))
        }
        val req = ChatRequest(
            messages = listOf(ChatMessagePart(ChatRole.USER, "o que é isto?")),
            images = listOf(ChatImage(ByteArray(4), "image/jpeg")),
        )
        p.stream(req).toList()

        val parts = captured?.contents?.first()?.parts.orEmpty()
        assertEquals(2, parts.size) // text + inlineData
        assertEquals("image/jpeg", parts[1].inlineData?.mimeType)
        assertEquals("B64(4)", parts[1].inlineData?.data)
    }

    @Test
    fun gemini_hints_carry_cache_ref_and_thinking_level() = runTest {
        var captured: GeminiRequest? = null
        val p = provider { _, _, req ->
            captured = req
            flowOf(StreamChunk.Done("ok", "STOP"))
        }
        val req = textRequest().copy(
            geminiHints = GeminiPassThrough(
                cachedContent = "cachedContents/abc",
                thinkingLevel = "low",
            ),
        )
        p.stream(req).toList()

        assertEquals("cachedContents/abc", captured?.cachedContent)
        assertEquals("low", captured?.generationConfig?.thinkingConfig?.thinkingLevel)
    }

    @Test
    fun plain_turn_sends_null_generation_config() = runTest {
        var captured: GeminiRequest? = null
        val p = provider { _, _, req ->
            captured = req
            flowOf(StreamChunk.Done("ok", "STOP"))
        }
        p.stream(textRequest()).toList()
        // No hints, no generation knobs → null config, matching pre-refactor
        // payload shape for an ordinary chat turn.
        assertNull(captured?.generationConfig)
    }

    @Test
    fun structured_generation_sets_json_mime_type() = runTest {
        var captured: GeminiRequest? = null
        val p = provider { _, _, req ->
            captured = req
            flowOf(StreamChunk.Done("{}", "STOP"))
        }
        val req = textRequest().copy(generation = ChatGeneration(structured = true))
        p.stream(req).toList()
        assertEquals("application/json", captured?.generationConfig?.responseMimeType)
    }
}
