package com.example.data.ai.provider.runinfra

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 (plan §9, §11) — RunInfra/Qwen3.8-27B chat provider, the real Groq
 * failover: streaming mapping, error classification, request shaping,
 * availability gate. Pure JVM: the streamer + key are injected.
 */
class RunInfraChatProviderTest {

    private fun provider(
        key: String = "ri_realkey",
        streamer: (String, RunInfraStreamRequest) -> Flow<RunInfraStreamChunk>,
    ) = RunInfraChatProvider(
        model = "qwen3.8-27b",
        apiKeyProvider = { key },
        streamer = streamer,
    )

    private fun textRequest(text: String = "quantas calorias comi?") = ChatRequest(
        systemInstruction = "És a NutriMadhvi. PT-PT.",
        messages = listOf(ChatMessagePart(ChatRole.USER, text)),
    )

    @Test
    fun id_and_capabilities() {
        val p = provider { _, _ -> flowOf() }
        assertEquals("runinfra:qwen3.8-27b", p.id.value)
        assertFalse(p.capabilities.multimodalInput) // §18: never route Vision here
        assertTrue(p.capabilities.textInput)
        assertTrue(p.capabilities.streaming)
    }

    @Test
    fun placeholder_and_blank_key_unavailable() {
        assertFalse(provider(key = "MY_RUNINFRA_API_KEY") { _, _ -> flowOf() }.isAvailable())
        assertFalse(provider(key = "") { _, _ -> flowOf() }.isAvailable())
        assertTrue(provider(key = "ri_x") { _, _ -> flowOf() }.isAvailable())
    }

    @Test
    fun partial_then_done_maps_to_delta_then_completed() = runTest {
        var captured: RunInfraStreamRequest? = null
        val p = provider { _, req ->
            captured = req
            flow {
                emit(RunInfraStreamChunk.Partial("Comeste ", "Comeste "))
                emit(RunInfraStreamChunk.Partial("1800 kcal", "Comeste 1800 kcal"))
                emit(RunInfraStreamChunk.Done("Comeste 1800 kcal", "stop", RunInfraUsage(50, 20, 70)))
            }
        }
        val events = p.stream(textRequest()).toList()

        assertEquals(3, events.size)
        assertEquals(ChatEvent.Delta("Comeste ", "Comeste "), events[0])
        val done = events[2] as ChatEvent.Completed
        assertEquals("Comeste 1800 kcal", done.fullText)
        assertEquals("runinfra:qwen3.8-27b", done.providerModel.value)
        assertEquals(70, done.usage?.totalTokens)

        // Request shaping: system message first, then user; stream=true;
        // NO reasoning_effort field (Groq-only knob); no response_format for text.
        assertEquals("system", captured?.messages?.get(0)?.role)
        assertEquals("És a NutriMadhvi. PT-PT.", captured?.messages?.get(0)?.content)
        assertEquals("user", captured?.messages?.get(1)?.role)
        assertTrue(captured?.stream == true)
        assertNull(captured?.responseFormat)
    }

    @Test
    fun http_429_maps_to_rate_limited_but_provider_stays_available() = runTest {
        // Unlike Groq, RunInfra does not self-skip on 429 — it is the failover
        // destination, so a 429 classifies as RATE_LIMITED (→ fall through to
        // Gemini) yet the provider remains available for later turns.
        val p = provider { _, _ ->
            flowOf(RunInfraStreamChunk.Error(IllegalStateException("HTTP 429"), httpStatus = 429))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.RATE_LIMITED, failed.error.kind)
        assertTrue(p.isAvailable())
    }

    @Test
    fun http_503_maps_to_server_error() = runTest {
        val p = provider { _, _ ->
            flowOf(RunInfraStreamChunk.Error(IllegalStateException("HTTP 503"), httpStatus = 503))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.SERVER_ERROR, failed.error.kind)
    }

    @Test
    fun http_401_maps_to_auth_or_config() = runTest {
        val p = provider { _, _ ->
            flowOf(RunInfraStreamChunk.Error(IllegalStateException("HTTP 401"), httpStatus = 401))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.AUTH_OR_CONFIG, failed.error.kind)
    }

    @Test
    fun http_400_maps_to_invalid_request() = runTest {
        val p = provider { _, _ ->
            flowOf(RunInfraStreamChunk.Error(IllegalStateException("HTTP 400"), httpStatus = 400))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.INVALID_REQUEST, failed.error.kind)
    }

    @Test
    fun truncation_maps_to_invalid_response_with_partial() = runTest {
        val p = provider { _, _ ->
            flow {
                emit(RunInfraStreamChunk.Partial("resposta cor", "resposta cor"))
                emit(
                    RunInfraStreamChunk.Error(
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
            flowOf(RunInfraStreamChunk.Error(java.net.SocketTimeoutException("read timed out")))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.TIMEOUT, failed.error.kind)
    }

    @Test
    fun connect_exception_maps_to_network() = runTest {
        val p = provider { _, _ ->
            flowOf(RunInfraStreamChunk.Error(java.net.ConnectException("refused")))
        }
        val failed = p.stream(textRequest()).toList().single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.NETWORK, failed.error.kind)
    }

    @Test
    fun structured_generation_sets_json_object_response_format() = runTest {
        var captured: RunInfraStreamRequest? = null
        val p = provider { _, req ->
            captured = req
            flowOf(RunInfraStreamChunk.Done("{}", "stop"))
        }
        val req = textRequest().copy(generation = ChatGeneration(structured = true, maxOutputTokens = 512))
        p.stream(req).toList()
        assertEquals("json_object", captured?.responseFormat?.type)
        assertEquals(512, captured?.maxTokens)
    }

    // --- 2026-10-06 (Frente Thinking) --------------------------------------

    @Test
    fun reasoning_content_chunks_map_to_thinking_never_delta() = runTest {
        val p = provider { _, _ ->
            flow {
                emit(RunInfraStreamChunk.Reasoning("A ", "A "))
                emit(RunInfraStreamChunk.Reasoning("analisar", "A analisar"))
                emit(RunInfraStreamChunk.Partial("Tens 82,5 kg", "Tens 82,5 kg"))
                emit(RunInfraStreamChunk.Done("Tens 82,5 kg", "stop"))
            }
        }
        val events = p.stream(textRequest()).toList()

        assertEquals(4, events.size)
        assertEquals(ChatEvent.Thinking("A ", "A "), events[0])
        assertEquals(ChatEvent.Thinking("analisar", "A analisar"), events[1])
        assertEquals(ChatEvent.Delta("Tens 82,5 kg", "Tens 82,5 kg"), events[2])
        val completed = events[3] as ChatEvent.Completed
        assertEquals("Tens 82,5 kg", completed.fullText)
    }

    @Test
    fun reasoning_effort_is_low_for_chat_none_for_structured() = runTest {
        var captured: RunInfraStreamRequest? = null
        val p = provider { _, req ->
            captured = req
            flowOf(RunInfraStreamChunk.Done("ok", "stop"))
        }
        p.stream(textRequest()).toList()
        assertEquals("low", captured?.reasoningEffort)

        captured = null
        val req = textRequest().copy(generation = ChatGeneration(structured = true, maxOutputTokens = 512))
        p.stream(req).toList()
        assertEquals("none", captured?.reasoningEffort)
    }

}