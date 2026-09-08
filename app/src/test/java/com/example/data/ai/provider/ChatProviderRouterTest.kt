package com.example.data.ai.provider

import com.example.data.chat.ModelCircuitBreaker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 (plan §11, §12, §22 "Router") — failover chain
 * `Groq → RunInfra → Gemini` with a provider/model circuit breaker.
 *
 * Pure JVM: fake providers + an injected-clock breaker. Covers the plan's
 * explicit router acceptance list.
 */
class ChatProviderRouterTest {

    // --- fakes -------------------------------------------------------------

    private class FakeProvider(
        provider: String,
        model: String,
        private val available: Boolean = true,
        multimodal: Boolean = false,
        private val behaviour: () -> Flow<ChatEvent>,
    ) : ChatProvider {
        override val id = ProviderModelId.of(provider, model)
        override val capabilities = ProviderCapabilities(
            textInput = true,
            multimodalInput = multimodal,
            streaming = true,
        )
        var streamCalls = 0
            private set

        override fun isAvailable() = available
        override fun stream(request: ChatRequest): Flow<ChatEvent> {
            streamCalls++
            return behaviour()
        }
    }

    private fun failedPreToken(id: ProviderModelId, kind: ChatErrorKind) =
        flowOf<ChatEvent>(ChatEvent.Failed(ChatError(kind, kind.name, id)))

    private fun textRequest() = ChatRequest(
        messages = listOf(ChatMessagePart(ChatRole.USER, "quantas calorias?")),
    )

    private fun imageRequest() = ChatRequest(
        messages = listOf(ChatMessagePart(ChatRole.USER, "o que é este prato?")),
        images = listOf(ChatImage(byteArrayOf(1, 2, 3), "image/jpeg")),
    )

    private fun breaker() = ModelCircuitBreaker(nowMs = { 0L })

    // --- tests -------------------------------------------------------------

    @Test
    fun groq_success_stops_chain() = runTest {
        val groqDone = FakeProvider("groq", "q") { completedFor("groq:q", "resposta") }
        val runinfra = FakeProvider("runinfra", "q") { error("must not be called") }
        val router = ChatProviderRouter(listOf(groqDone, runinfra), breaker())

        val events = router.stream(textRequest()).toList()
        val done = events.last() as ChatEvent.Completed
        assertEquals("groq:q", done.providerModel.value)
        assertEquals(0, runinfra.streamCalls)
    }

    private fun completedFor(idStr: String, text: String): Flow<ChatEvent> {
        val id = ProviderModelId.parse(idStr)
        return flowOf(ChatEvent.Delta(text, text), ChatEvent.Completed(text, id))
    }

    @Test
    fun groq_429_fails_over_to_runinfra() = runTest {
        val groq = FakeProvider("groq", "q") {
            failedPreToken(ProviderModelId.of("groq", "q"), ChatErrorKind.RATE_LIMITED)
        }
        val runinfra = FakeProvider("runinfra", "q") { completedFor("runinfra:q", "R") }
        val router = ChatProviderRouter(listOf(groq, runinfra), breaker())

        val done = router.stream(textRequest()).toList().last() as ChatEvent.Completed
        assertEquals("runinfra:q", done.providerModel.value)
        assertEquals(1, groq.streamCalls)
        assertEquals(1, runinfra.streamCalls)
    }

    @Test
    fun groq_503_fails_over_to_runinfra() = runTest {
        val groq = FakeProvider("groq", "q") {
            failedPreToken(ProviderModelId.of("groq", "q"), ChatErrorKind.SERVER_ERROR)
        }
        val runinfra = FakeProvider("runinfra", "q") { completedFor("runinfra:q", "R") }
        val router = ChatProviderRouter(listOf(groq, runinfra), breaker())
        val done = router.stream(textRequest()).toList().last() as ChatEvent.Completed
        assertEquals("runinfra:q", done.providerModel.value)
    }

    @Test
    fun groq_timeout_fails_over_to_runinfra() = runTest {
        val groq = FakeProvider("groq", "q") {
            failedPreToken(ProviderModelId.of("groq", "q"), ChatErrorKind.TIMEOUT)
        }
        val runinfra = FakeProvider("runinfra", "q") { completedFor("runinfra:q", "R") }
        val router = ChatProviderRouter(listOf(groq, runinfra), breaker())
        val done = router.stream(textRequest()).toList().last() as ChatEvent.Completed
        assertEquals("runinfra:q", done.providerModel.value)
    }

    @Test
    fun runinfra_failure_falls_through_to_gemini() = runTest {
        val groq = FakeProvider("groq", "q") {
            failedPreToken(ProviderModelId.of("groq", "q"), ChatErrorKind.NETWORK)
        }
        val runinfra = FakeProvider("runinfra", "q") {
            failedPreToken(ProviderModelId.of("runinfra", "q"), ChatErrorKind.SERVER_ERROR)
        }
        val gemini = FakeProvider("gemini", "flash") { completedFor("gemini:flash", "G") }
        val router = ChatProviderRouter(listOf(groq, runinfra, gemini), breaker())

        val done = router.stream(textRequest()).toList().last() as ChatEvent.Completed
        assertEquals("gemini:flash", done.providerModel.value)
        assertEquals(1, gemini.streamCalls)
    }

    @Test
    fun open_circuit_is_skipped() = runTest {
        val b = breaker()
        // Two failures opens groq:q (FAILURE_THRESHOLD = 2).
        b.recordFailure("groq:q"); b.recordFailure("groq:q")
        assertTrue(b.isOpen("groq:q"))

        val groq = FakeProvider("groq", "q") { error("open circuit must be skipped") }
        val runinfra = FakeProvider("runinfra", "q") { completedFor("runinfra:q", "R") }
        val router = ChatProviderRouter(listOf(groq, runinfra), b)

        val done = router.stream(textRequest()).toList().last() as ChatEvent.Completed
        assertEquals("runinfra:q", done.providerModel.value)
        assertEquals(0, groq.streamCalls) // never invoked while open
    }

    @Test
    fun unavailable_provider_is_skipped() = runTest {
        val groq = FakeProvider("groq", "q", available = false) { error("unavailable must be skipped") }
        val runinfra = FakeProvider("runinfra", "q") { completedFor("runinfra:q", "R") }
        val router = ChatProviderRouter(listOf(groq, runinfra), breaker())

        val done = router.stream(textRequest()).toList().last() as ChatEvent.Completed
        assertEquals("runinfra:q", done.providerModel.value)
        assertEquals(0, groq.streamCalls)
    }

    @Test
    fun permanent_4xx_records_failure_and_opens_after_threshold() = runTest {
        // §22: "permanent 4xx not retried indefinitely." A single turn fails
        // over on 4xx, but the breaker accrues failures so repeated turns stop
        // hitting the bad provider.
        val b = breaker()
        val groq = FakeProvider("groq", "q") {
            failedPreToken(ProviderModelId.of("groq", "q"), ChatErrorKind.INVALID_REQUEST)
        }
        val runinfra = FakeProvider("runinfra", "q") { completedFor("runinfra:q", "R") }
        val router = ChatProviderRouter(listOf(groq, runinfra), b)

        // Turn 1 + 2: groq 4xx each time, accruing 2 failures → opens.
        router.stream(textRequest()).toList()
        router.stream(textRequest()).toList()
        assertTrue(b.isOpen("groq:q"))
        assertEquals(2, groq.streamCalls)

        // Turn 3: groq now skipped (open), goes straight to runinfra.
        router.stream(textRequest()).toList()
        assertEquals(2, groq.streamCalls) // NOT retried indefinitely
    }

    @Test
    fun image_required_never_routed_to_text_only_provider() = runTest {
        // §18: groq + runinfra are text-only; only gemini is multimodal.
        val groq = FakeProvider("groq", "q", multimodal = false) { error("text-only must not serve image") }
        val runinfra = FakeProvider("runinfra", "q", multimodal = false) { error("text-only must not serve image") }
        val gemini = FakeProvider("gemini", "flash", multimodal = true) { completedFor("gemini:flash", "G") }
        val router = ChatProviderRouter(listOf(groq, runinfra, gemini), breaker())

        val done = router.stream(imageRequest()).toList().last() as ChatEvent.Completed
        assertEquals("gemini:flash", done.providerModel.value)
        assertEquals(0, groq.streamCalls)
        assertEquals(0, runinfra.streamCalls)
    }

    @Test
    fun all_skipped_yields_single_auth_or_config_failure() = runTest {
        val groq = FakeProvider("groq", "q", available = false) { error("skip") }
        val gemini = FakeProvider("gemini", "flash", available = false) { error("skip") }
        val router = ChatProviderRouter(listOf(groq, gemini), breaker())

        val events = router.stream(textRequest()).toList()
        assertEquals(1, events.size)
        val failed = events.single() as ChatEvent.Failed
        assertEquals(ChatErrorKind.AUTH_OR_CONFIG, failed.error.kind)
    }

    @Test
    fun success_closes_breaker_for_that_provider() = runTest {
        val b = breaker()
        b.recordFailure("runinfra:q") // one prior failure, not yet open
        val groq = FakeProvider("groq", "q") {
            failedPreToken(ProviderModelId.of("groq", "q"), ChatErrorKind.TIMEOUT)
        }
        val runinfra = FakeProvider("runinfra", "q") { completedFor("runinfra:q", "R") }
        val router = ChatProviderRouter(listOf(groq, runinfra), b)

        router.stream(textRequest()).toList()
        // runinfra succeeded → its breaker is closed again.
        assertFalse(b.isOpen("runinfra:q"))
    }

    @Test
    fun mid_stream_death_with_fallback_emits_reset_then_completes_from_fallback() = runTest {
        // §13 Phase-5: a provider that dies AFTER partial output does not
        // append a second answer. The router emits Reset (discard the buffer)
        // and re-renders from the healthy fallback → still one persisted answer.
        val groqId = ProviderModelId.of("groq", "q")
        val groq = FakeProvider("groq", "q") {
            flow {
                emit(ChatEvent.Delta("meia ", "meia "))
                emit(ChatEvent.Failed(ChatError(ChatErrorKind.NETWORK, "drop", groqId, partialText = "meia ")))
            }
        }
        val runinfra = FakeProvider("runinfra", "q") { completedFor("runinfra:q", "resposta completa") }
        val router = ChatProviderRouter(listOf(groq, runinfra), breaker())

        val events = router.stream(textRequest()).toList()

        // Exactly one Reset, naming who died and who took over.
        val resets = events.filterIsInstance<ChatEvent.Reset>()
        assertEquals(1, resets.size)
        assertEquals("groq:q", resets[0].fromProviderModel.value)
        assertEquals("runinfra:q", resets[0].toProviderModel.value)

        // Reset comes AFTER groq's delta and BEFORE runinfra's deltas.
        val resetIdx = events.indexOfFirst { it is ChatEvent.Reset }
        assertTrue(events.take(resetIdx).any { it is ChatEvent.Delta }) // groq partial before
        assertTrue(events.drop(resetIdx + 1).any { it is ChatEvent.Delta }) // fallback deltas after

        // Exactly one terminal, and it is the fallback's Completed.
        val terminals = events.filter { it is ChatEvent.Completed || it is ChatEvent.Failed }
        assertEquals(1, terminals.size)
        val done = terminals.single() as ChatEvent.Completed
        assertEquals("runinfra:q", done.providerModel.value)
        assertEquals("resposta completa", done.fullText)
        assertEquals(1, runinfra.streamCalls)
    }

    @Test
    fun mid_stream_death_with_no_fallback_surfaces_controlled_failure_with_partial() = runTest {
        // §13: "otherwise show a controlled failure." No healthy fallback left
        // → surface Failed carrying the partial; never invent an answer, never
        // emit a spurious Reset.
        val groqId = ProviderModelId.of("groq", "q")
        val groq = FakeProvider("groq", "q") {
            flow {
                emit(ChatEvent.Delta("parcial", "parcial"))
                emit(ChatEvent.Failed(ChatError(ChatErrorKind.NETWORK, "drop", groqId, partialText = "parcial")))
            }
        }
        // Only fallback is unavailable → nowhere healthy to go.
        val gemini = FakeProvider("gemini", "flash", available = false) { error("skip") }
        val router = ChatProviderRouter(listOf(groq, gemini), breaker())

        val events = router.stream(textRequest()).toList()
        assertTrue(events.none { it is ChatEvent.Reset })
        assertTrue(events.any { it is ChatEvent.Delta })
        val failed = events.last() as ChatEvent.Failed
        assertEquals("parcial", failed.error.partialText)
        assertEquals(0, gemini.streamCalls)
    }

    @Test
    fun provider_that_throws_is_treated_as_failure_and_chain_advances() = runTest {
        val groq = FakeProvider("groq", "q") {
            flow<ChatEvent> { throw IllegalStateException("boom") }
        }
        val runinfra = FakeProvider("runinfra", "q") { completedFor("runinfra:q", "R") }
        val router = ChatProviderRouter(listOf(groq, runinfra), breaker())

        val done = router.stream(textRequest()).toList().last() as ChatEvent.Completed
        assertEquals("runinfra:q", done.providerModel.value)
    }
}
