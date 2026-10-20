package com.example.data.ai.provider.gemini

import com.example.data.ai.provider.ChatErrorKind
import com.example.data.ai.provider.ChatEvent
import com.example.data.ai.provider.ChatImage
import com.example.data.ai.provider.ChatMessagePart
import com.example.data.ai.provider.ChatRequest
import com.example.data.ai.provider.ChatRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10 — unit tests for [GeminiCascadeChatProvider].
 *
 * This provider is the chain's **last resort** — it wraps the full Gemini
 * orchestrator engine (fleet cascade, watchdog, cache-rejection retry, …) via
 * an injected [engine] lambda. The class is thin: it relabels the engine's
 * callbacks and return/throw surface as neutral [ChatEvent]s, handles the
 * [TimeoutCancellationException]-vs-outer-cancel distinction, and gate-checks
 * the API key.
 *
 * Tests exercise:
 *   - isAvailable gate (blank / placeholder key → false; real key → true)
 *   - multimodal capability (last-resort must accept image turns)
 *   - partial streaming → Delta events; Completed carries the RESOLVED model,
 *     not the default
 *   - blank answer → INVALID_RESPONSE (never silently commit empty text)
 *   - TimeoutCancellationException → TIMEOUT failure (recoverable)
 *   - genuine CancellationException → rethrown (structured concurrency)
 *   - IOException → NETWORK failure
 *   - generic exception → UNKNOWN failure
 *   - onModelResolved updates the providerModel in Completed
 *   - onPartial fires first-token gate once
 *
 * No Android, no Room: the engine is a plain suspend lambda injected in each
 * test case.
 */
class GeminiCascadeChatProviderTest {

    private fun provider(
        key: String = "AQ.real-key",
        defaultModel: String = "gemini-3.6-flash",
        engine: suspend (GeminiCascadeChatProvider.Callbacks) -> String,
    ) = GeminiCascadeChatProvider(
        defaultModel = defaultModel,
        apiKeyProvider = { key },
        engine = engine,
    )

    private fun textRequest() = ChatRequest(
        messages = listOf(ChatMessagePart(ChatRole.USER, "quantas calorias comi esta semana?")),
    )

    private fun imageRequest() = ChatRequest(
        messages = listOf(ChatMessagePart(ChatRole.USER, "o que é este prato?")),
        images = listOf(ChatImage(byteArrayOf(1, 2, 3), "image/jpeg")),
    )

    // --- availability --------------------------------------------------------

    @Test
    fun blank_key_reports_unavailable() {
        assertFalse(provider(key = "") { "" }.isAvailable())
    }

    @Test
    fun placeholder_key_reports_unavailable() {
        assertFalse(provider(key = "MY_GEMINI_API_KEY") { "" }.isAvailable())
    }

    @Test
    fun real_key_reports_available() {
        assertTrue(provider(key = "AQ.something") { "" }.isAvailable())
    }

    // --- capabilities --------------------------------------------------------

    @Test
    fun provider_is_multimodal_last_resort() {
        val p = provider { "" }
        assertTrue(p.capabilities.multimodalInput)
        assertTrue(p.capabilities.textInput)
        assertTrue(p.capabilities.streaming)
    }

    @Test
    fun image_required_turn_is_servable() {
        assertTrue(provider { "" }.capabilities.canServe(imageRequest()))
    }

    // --- happy path ----------------------------------------------------------

    @Test
    fun clean_answer_emits_completed_with_full_text() = runTest {
        val events = provider { cbs ->
            cbs.onPartial("a")
            cbs.onPartial("ab")
            "ab"
        }.stream(textRequest()).toList()

        val deltas = events.filterIsInstance<ChatEvent.Delta>()
        assertEquals(2, deltas.size)
        assertEquals("a", deltas[0].accumulated)
        assertEquals("ab", deltas[1].accumulated)

        val done = events.last() as ChatEvent.Completed
        assertEquals("ab", done.fullText)
        assertEquals("gemini:gemini-3.6-flash", done.providerModel.value)
    }

    @Test
    fun resolved_model_overwrites_default_in_completed() = runTest {
        val events = provider(defaultModel = "gemini-3.6-flash") { cbs ->
            cbs.onModelResolved("gemini-3.5-flash") // cascade picked a fallback model
            "resposta"
        }.stream(textRequest()).toList()

        val done = events.last() as ChatEvent.Completed
        // The badge must name the ACTUAL model, not the default.
        assertEquals("gemini:gemini-3.5-flash", done.providerModel.value)
    }

    @Test
    fun default_model_used_when_on_model_resolved_never_fires() = runTest {
        val events = provider(defaultModel = "gemini-3.6-flash") { _ ->
            "resposta"
        }.stream(textRequest()).toList()

        val done = events.last() as ChatEvent.Completed
        assertEquals("gemini:gemini-3.6-flash", done.providerModel.value)
    }

    // --- blank answer --------------------------------------------------------

    @Test
    fun blank_answer_emits_invalid_response_failure() = runTest {
        val events = provider { _ -> "   " }.stream(textRequest()).toList()

        assertEquals(1, events.size)
        val failed = events.single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.INVALID_RESPONSE, failed.error.kind)
    }

    @Test
    fun empty_answer_after_partials_emits_invalid_response() = runTest {
        val events = provider { cbs ->
            cbs.onPartial("partial text")
            ""
        }.stream(textRequest()).toList()

        // Delta was emitted, then failure.
        assertTrue(events.any { it is ChatEvent.Delta })
        val failed = events.last() as ChatEvent.Failed
        assertEquals(ChatErrorKind.INVALID_RESPONSE, failed.error.kind)
    }

    // --- timeout (recoverable) -----------------------------------------------

    @Test
    fun timeout_cancellation_exception_emits_timeout_failure_not_rethrown() = runTest {
        // TimeoutCancellationException (a CancellationException subclass) is the
        // engine's 90s budget firing — it is RECOVERABLE: the router should fail
        // over to the next candidate, not tear down the session scope.
        val events = provider { _ ->
            kotlinx.coroutines.withTimeout(1L) {
                kotlinx.coroutines.delay(100L)
            }
            "unreachable"
        }.stream(textRequest()).toList()

        assertEquals(1, events.size)
        val failed = events.single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.TIMEOUT, failed.error.kind)
    }

    // --- outer cancellation (must propagate) ---------------------------------

    @Test
    fun genuine_cancellation_exception_is_rethrown() = runTest {
        // A genuine outer CancellationException (user cancelled, scope torn down)
        // must NOT be swallowed — it propagates out of stream() to honour
        // structured concurrency (§8, §13).
        var threw = false
        try {
            provider { _ -> throw CancellationException("scope cancelled") }
                .stream(textRequest())
                .toList()
        } catch (ce: CancellationException) {
            threw = true
        }
        assertTrue("genuine CancellationException must propagate", threw)
    }

    // --- network and generic failures ----------------------------------------

    @Test
    fun io_exception_emits_network_failure() = runTest {
        val events = provider { _ ->
            throw java.io.IOException("no route to host")
        }.stream(textRequest()).toList()

        val failed = events.single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.NETWORK, failed.error.kind)
    }

    @Test
    fun unknown_host_exception_emits_network_failure() = runTest {
        val events = provider { _ ->
            throw java.net.UnknownHostException("generativelanguage.googleapis.com")
        }.stream(textRequest()).toList()

        val failed = events.single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.NETWORK, failed.error.kind)
    }

    @Test
    fun socket_timeout_exception_emits_timeout_failure() = runTest {
        val events = provider { _ ->
            throw java.net.SocketTimeoutException("connect timed out")
        }.stream(textRequest()).toList()

        val failed = events.single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.TIMEOUT, failed.error.kind)
    }

    @Test
    fun generic_exception_emits_unknown_failure() = runTest {
        val events = provider { _ ->
            throw IllegalStateException("something unexpected")
        }.stream(textRequest()).toList()

        val failed = events.single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.UNKNOWN, failed.error.kind)
    }

    @Test
    fun truncation_in_message_emits_invalid_response_failure() = runTest {
        val events = provider { _ ->
            throw IllegalStateException("finishReason=MAX_TOKENS")
        }.stream(textRequest()).toList()

        val failed = events.single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.INVALID_RESPONSE, failed.error.kind)
    }

    // --- provider model id in failure events ---------------------------------

    @Test
    fun failure_carries_resolved_model_when_available() = runTest {
        val events = provider(defaultModel = "gemini-3.6-flash") { cbs ->
            cbs.onModelResolved("gemini-3.5-flash")
            throw java.io.IOException("dropped")
        }.stream(textRequest()).toList()

        val failed = events.single() as ChatEvent.Failed
        // Even in failure the badge names the concrete model, not the default.
        assertEquals("gemini:gemini-3.5-flash", failed.error.providerModel.value)
    }

    // --- exactly one terminal event ------------------------------------------

    @Test
    fun exactly_one_terminal_event_on_success() = runTest {
        val events = provider { cbs ->
            cbs.onPartial("ok")
            "ok"
        }.stream(textRequest()).toList()

        val terminals = events.filter { it is ChatEvent.Completed || it is ChatEvent.Failed }
        assertEquals(1, terminals.size)
    }

    @Test
    fun exactly_one_terminal_event_on_failure() = runTest {
        val events = provider { _ -> throw java.io.IOException("err") }
            .stream(textRequest()).toList()

        val terminals = events.filter { it is ChatEvent.Completed || it is ChatEvent.Failed }
        assertEquals(1, terminals.size)
    }

    // --- 2026-10-06 (Frente Thinking) --------------------------------------

    @Test
    fun onThinking_maps_to_thinking_and_never_touches_answer() = runTest {
        val events = provider { cb ->
            cb.onThinking("a avaliar os dados")
            cb.onThinking("a avaliar os dados do peso")
            cb.onPartial("Tens 82,5 kg")
            "Tens 82,5 kg"
        }.stream(textRequest()).toList()

        assertEquals(4, events.size)
        assertEquals(ChatEvent.Thinking("a avaliar os dados", "a avaliar os dados"), events[0])
        assertEquals(ChatEvent.Thinking("a avaliar os dados do peso", "a avaliar os dados do peso"), events[1])
        assertEquals(ChatEvent.Delta("Tens 82,5 kg", "Tens 82,5 kg"), events[2])
    }

    @Test
    fun thinking_only_turn_is_not_an_answer() = runTest {
        // Engine that only ever emits thinking and then a blank answer →
        // INVALID_RESPONSE (the thinking is feedback, not content).
        val events = provider { cb ->
            cb.onThinking("a pensar")
            ""
        }.stream(textRequest()).toList()

        assertTrue(events.any { it is ChatEvent.Thinking })
        val failed = events.filterIsInstance<ChatEvent.Failed>()
        assertEquals(1, failed.size)
        assertEquals(ChatErrorKind.INVALID_RESPONSE, failed[0].error.kind)
    }

}