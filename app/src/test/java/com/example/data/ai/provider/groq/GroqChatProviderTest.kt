package com.example.data.ai.provider.groq

import com.example.data.ai.provider.ChatErrorKind
import com.example.data.ai.provider.ChatEvent
import com.example.data.ai.provider.ChatGeneration
import com.example.data.ai.provider.ChatMessagePart
import com.example.data.ai.provider.ChatRequest
import com.example.data.ai.provider.ChatRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2 (plan §8, §22) — Groq/Qwen3.8-27B chat provider:
 * streaming mapping, 429 detection + fast-failover budget, timeout/5xx/network
 * classification, response validation, request shaping, availability gate.
 * Pure JVM: streamer + key + clock are injected.
 */
class GroqChatProviderTest {

    @Before fun clear() = GroqStreamBudget.reset()
    @After fun cleanup() = GroqStreamBudget.reset()

    private fun provider(
        key: String = "gsk_realkey",
        now: () -> Long = { 1_000L },
        streamer: (String, GroqStreamRequest) -> Flow<GroqStreamChunk>,
    ) = GroqChatProvider(
        model = "qwen/qwen3.8-27b",
        apiKeyProvider = { key },
        streamer = streamer,
        nowMs = now,
    )

    private fun textRequest(text: String = "quantas calorias comi?") = ChatRequest(
        systemInstruction = "És a NutriMadhvi. PT-PT.",
        messages = listOf(ChatMessagePart(ChatRole.USER, text)),
    )

    @Test
    fun id_and_capabilities() {
        val p = provider { _, _ -> flowOf() }
        assertEquals("groq:qwen/qwen3.8-27b", p.id.value)
        // §18: Groq chat is text-only — must not receive image turns.
        assertFalse(p.capabilities.multimodalInput)
        assertTrue(p.capabilities.textInput)
        assertTrue(p.capabilities.streaming)
    }

    @Test
    fun placeholder_and_blank_key_unavailable() {
        assertFalse(provider(key = "MY_GROQ_API_KEY") { _, _ -> flowOf() }.isAvailable())
        assertFalse(provider(key = "") { _, _ -> flowOf() }.isAvailable())
        assertTrue(provider(key = "gsk_x") { _, _ -> flowOf() }.isAvailable())
    }

    @Test
    fun partial_then_done_maps_to_delta_then_completed() = runTest {
        var captured: GroqStreamRequest? = null
        val p = provider { _, req ->
            captured = req
            flow {
                emit(GroqStreamChunk.Partial("Comeste ", "Comeste "))
                emit(GroqStreamChunk.Partial("1800 kcal", "Comeste 1800 kcal"))
                emit(GroqStreamChunk.Done("Comeste 1800 kcal", "stop", GroqUsage(50, 20, 70)))
            }
        }
        val events = p.stream(textRequest()).toList()

        assertEquals(3, events.size)
        assertEquals(ChatEvent.Delta("Comeste ", "Comeste "), events[0])
        val done = events[2] as ChatEvent.Completed
        assertEquals("Comeste 1800 kcal", done.fullText)
        assertEquals("groq:qwen/qwen3.8-27b", done.providerModel.value)
        assertEquals(70, done.usage?.totalTokens)

        // Request shaping: system message first, then user; stream=true;
        // reasoning_effort=low (2026-10-06: the thinking streams in
        // delta.reasoning, so the reasoning pass no longer costs latency in
        // the answer text); no response_format for plain text.
        assertEquals("system", captured?.messages?.get(0)?.role)
        assertEquals("És a NutriMadhvi. PT-PT.", captured?.messages?.get(0)?.content)
        assertEquals("user", captured?.messages?.get(1)?.role)
        assertTrue(captured?.stream == true)
        assertEquals("low", captured?.reasoningEffort)
        assertNull(captured?.responseFormat)
    }

    @Test
    fun http_429_classified_rate_limited_and_blocks_further_availability() = runTest {
        var t = 1_000L
        val p = provider(now = { t }) { _, _ ->
            flowOf(GroqStreamChunk.Error(IllegalStateException("HTTP 429: rate"), httpStatus = 429))
        }
        assertTrue(p.isAvailable()) // healthy before the 429

        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.RATE_LIMITED, failed.error.kind)

        // §12: after a 429 the router must skip Groq (fast failover), not retry.
        assertFalse(p.isAvailable())
        // …until the cooldown elapses.
        t += GroqStreamBudget.DEFAULT_COOLDOWN_MS
        assertTrue(p.isAvailable())
    }

    @Test
    fun http_503_maps_to_server_error() = runTest {
        val p = provider { _, _ ->
            flowOf(GroqStreamChunk.Error(IllegalStateException("HTTP 503"), httpStatus = 503))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.SERVER_ERROR, failed.error.kind)
    }

    @Test
    fun http_401_maps_to_auth_or_config() = runTest {
        val p = provider { _, _ ->
            flowOf(GroqStreamChunk.Error(IllegalStateException("HTTP 401"), httpStatus = 401))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.AUTH_OR_CONFIG, failed.error.kind)
    }

    @Test
    fun http_400_maps_to_invalid_request() = runTest {
        val p = provider { _, _ ->
            flowOf(GroqStreamChunk.Error(IllegalStateException("HTTP 400"), httpStatus = 400))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.INVALID_REQUEST, failed.error.kind)
    }

    @Test
    fun truncation_maps_to_invalid_response_with_partial() = runTest {
        val p = provider { _, _ ->
            flow {
                emit(GroqStreamChunk.Partial("resposta cor", "resposta cor"))
                emit(
                    GroqStreamChunk.Error(
                        IllegalStateException("Stream truncated: finish_reason=length"),
                        partial = "resposta cor",
                    )
                )
            }
        }
        val failed = p.stream(textRequest()).toList().last() as ChatEvent.Failed
        assertEquals(ChatErrorKind.INVALID_RESPONSE, failed.error.kind)
        assertEquals("resposta cor", failed.error.partialText)
        assertTrue(failed.error.hadPartialOutput)
    }

    @Test
    fun socket_timeout_maps_to_timeout() = runTest {
        val p = provider { _, _ ->
            flowOf(GroqStreamChunk.Error(java.net.SocketTimeoutException("read timed out")))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.TIMEOUT, failed.error.kind)
    }

    @Test
    fun connect_exception_maps_to_network() = runTest {
        val p = provider { _, _ ->
            flowOf(GroqStreamChunk.Error(java.net.ConnectException("refused")))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.NETWORK, failed.error.kind)
    }

    @Test
    fun structured_generation_sets_json_object_response_format() = runTest {
        var captured: GroqStreamRequest? = null
        val p = provider { _, req ->
            captured = req
            flowOf(GroqStreamChunk.Done("{}", "stop"))
        }
        val req = textRequest().copy(generation = ChatGeneration(structured = true, maxOutputTokens = 512))
        p.stream(req).toList()
        assertEquals("json_object", captured?.responseFormat?.type)
        assertEquals(512, captured?.maxTokens)
    }

    // --- 2026-10-06 (Frente Thinking) --------------------------------------

    @Test
    fun reasoning_chunks_map_to_thinking_never_delta() = runTest {
        val p = provider { _, _ ->
            flow {
                emit(GroqStreamChunk.Reasoning("Vou ", "Vou "))
                emit(GroqStreamChunk.Reasoning("ver as refeições", "Vou ver as refeições"))
                emit(GroqStreamChunk.Partial("Comeste 1800 kcal", "Comeste 1800 kcal"))
                emit(GroqStreamChunk.Done("Comeste 1800 kcal", "stop"))
            }
        }
        val events = p.stream(textRequest()).toList()

        assertEquals(4, events.size)
        assertEquals(ChatEvent.Thinking("Vou ", "Vou "), events[0])
        assertEquals(ChatEvent.Thinking("ver as refeições", "Vou ver as refeições"), events[1])
        // The answer text is clean: thinking never merged into content.
        assertEquals(ChatEvent.Delta("Comeste 1800 kcal", "Comeste 1800 kcal"), events[2])
        val completed = events[3] as ChatEvent.Completed
        assertEquals("Comeste 1800 kcal", completed.fullText)
    }

    @Test
    fun reasoning_effort_is_low_for_chat_none_for_structured() = runTest {
        var captured: GroqStreamRequest? = null
        val p = provider { _, req ->
            captured = req
            flowOf(GroqStreamChunk.Done("ok", "stop"))
        }
        p.stream(textRequest()).toList()
        assertEquals("low", captured?.reasoningEffort)

        captured = null
        val req = textRequest().copy(generation = ChatGeneration(structured = true, maxOutputTokens = 512))
        p.stream(req).toList()
        assertEquals("none", captured?.reasoningEffort)
    }

}