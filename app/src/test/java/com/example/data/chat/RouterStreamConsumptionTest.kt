package com.example.data.chat

import com.example.BuildConfig
import com.example.data.ai.provider.ChatError
import com.example.data.ai.provider.ChatErrorKind
import com.example.data.ai.provider.ChatEvent
import com.example.data.ai.provider.ChatMessagePart
import com.example.data.ai.provider.ChatProvider
import com.example.data.ai.provider.ChatProviderRouter
import com.example.data.ai.provider.ChatRequest
import com.example.data.ai.provider.ChatRole
import com.example.data.ai.provider.ProviderCapabilities
import com.example.data.ai.provider.ProviderModelId
import com.example.data.ai.provider.gemini.GeminiCascadeChatProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 (plan Verification §1a–d) — the orchestrator's **router-stream
 * consumption** contract: `ChatOrchestrator.consumeRouterStream` maps the
 * neutral [ChatEvent] stream from a real [ChatProviderRouter] onto the
 * [HandleEvent] surface + a pure [ChatOrchestrator.Companion.RouterOutcome].
 *
 * These cover what [com.example.data.ai.provider.ChatProviderRouterTest] does
 * NOT: that suite tests the router's event *production*; this one tests the
 * orchestrator's *consumption* of it — the §13 "one user turn, one persisted
 * answer" invariant, the mid-stream [HandleEvent.Reset], and the
 * `analysisModel` badge resolution.
 *
 * Pure JVM: fake providers + injected-clock breaker, exactly like the router
 * suite. The DAO-touching tail of the flag-on branch (fast-reply fallback
 * insert / durable error row) stays in `handle` and is out of scope here — the
 * seam is deliberately DAO-free so it is testable in isolation.
 *
 * `consumeRouterStream` is a companion function precisely because it uses no
 * orchestrator state, so it is driven statically here — no [ChatOrchestrator]
 * instance, no DAO mocks.
 */
class RouterStreamConsumptionTest {

    // --- fakes (mirroring ChatProviderRouterTest) --------------------------

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
        override fun isAvailable() = available
        override fun stream(request: ChatRequest): Flow<ChatEvent> = behaviour()
    }

    private fun completedFor(idStr: String, text: String): Flow<ChatEvent> {
        val id = ProviderModelId.parse(idStr)
        return flowOf(ChatEvent.Delta(text, text), ChatEvent.Completed(text, id))
    }

    private fun failedPreToken(id: ProviderModelId, kind: ChatErrorKind) =
        flowOf<ChatEvent>(ChatEvent.Failed(ChatError(kind, kind.name, id)))

    private fun textRequest() = ChatRequest(
        messages = listOf(ChatMessagePart(ChatRole.USER, "quantas calorias?")),
    )

    private fun breaker() = ModelCircuitBreaker(nowMs = { 0L })

    private val GEMINI = GeminiCascadeChatProvider.PROVIDER

    /** Drive the seam statically and capture every emitted HandleEvent. */
    private suspend fun consume(
        router: ChatProviderRouter,
        request: ChatRequest = textRequest(),
        resolvedModelSoFar: String? = null,
    ): Pair<ChatOrchestrator.Companion.RouterOutcome, List<HandleEvent>> {
        val emitted = mutableListOf<HandleEvent>()
        val outcome = ChatOrchestrator.consumeRouterStream(
            events = router.stream(request),
            emit = { emitted.add(it) },
            onFirstDelta = { /* no fast reply in these tests */ },
            resolvedModelSoFar = resolvedModelSoFar,
            geminiProviderName = GEMINI,
        )
        return outcome to emitted
    }

    // --- (a) flag off → router never consumed ------------------------------

    @Test
    fun flagEnabled_routerBranchActive() {
        // CHAT_ROUTER_ENABLED=true: the router branch (Groq→RunInfra→Gemini)
        // is active for streaming turns. Asserting the flag here documents the
        // intentional activation (2026-09-08, Pixel validation).
        // Rollback: set CHAT_ROUTER_ENABLED=false in build.gradle.kts.
        assertTrue(
            "CHAT_ROUTER_ENABLED should be true (activated 2026-09-08)",
            BuildConfig.CHAT_ROUTER_ENABLED,
        )
    }

    // --- (b) Groq Completed → one answer, analysisModel == "groq:…" --------

    @Test
    fun groqCompleted_oneAnswer_modelIsGroq() = runTest {
        val groq = FakeProvider("groq", "q") { completedFor("groq:q", "resposta") }
        val runinfra = FakeProvider("runinfra", "q") { error("must not be called") }
        val router = ChatProviderRouter(listOf(groq, runinfra), breaker())

        val (outcome, emitted) = consume(router)

        assertTrue(outcome is ChatOrchestrator.Companion.RouterOutcome.Answer)
        val answer = outcome as ChatOrchestrator.Companion.RouterOutcome.Answer
        assertEquals("resposta", answer.text)
        assertEquals("groq:q", answer.resolvedModel)
        // No mid-stream failover → no Reset.
        assertEquals(0, emitted.count { it is HandleEvent.Reset })
        // At least one Partial carrying the cumulative text.
        assertTrue(emitted.any { it is HandleEvent.Partial && it.accumulatedText == "resposta" })
        // The seam never persists / never emits Done or Error itself.
        assertEquals(0, emitted.count { it is HandleEvent.Done })
        assertEquals(0, emitted.count { it is HandleEvent.Error })
    }

    // --- (c) Groq mid-stream death + RunInfra success → one answer + Reset --

    @Test
    fun groqMidStreamDeath_runInfraTakesOver_oneAnswer_resetEmitted() = runTest {
        val groq = FakeProvider("groq", "q") {
            flow {
                emit(ChatEvent.Delta("meia ", "meia "))
                emit(ChatEvent.Delta("resp", "meia resp"))
                // dies AFTER partial output → router emits a Reset then fails over.
                // hadPartialOutput is a computed getter over partialText, so we
                // set partialText to signal the stream had already produced tokens.
                emit(ChatEvent.Failed(ChatError(ChatErrorKind.NETWORK, "drop", ProviderModelId.of("groq", "q"), partialText = "meia resp")))
            }
        }
        val runinfra = FakeProvider("runinfra", "q") { completedFor("runinfra:q", "resposta completa") }
        val router = ChatProviderRouter(listOf(groq, runinfra), breaker())

        val (outcome, emitted) = consume(router)

        // Exactly one Reset (the §13 mid-stream failover boundary).
        assertEquals(1, emitted.count { it is HandleEvent.Reset })
        val reset = emitted.first { it is HandleEvent.Reset } as HandleEvent.Reset
        assertEquals("groq:q", reset.from)
        assertEquals("runinfra:q", reset.to)

        // Exactly one answer, from RunInfra (the survivor).
        assertTrue(outcome is ChatOrchestrator.Companion.RouterOutcome.Answer)
        val answer = outcome as ChatOrchestrator.Companion.RouterOutcome.Answer
        assertEquals("resposta completa", answer.text)
        assertEquals("runinfra:q", answer.resolvedModel)

        // The last Partial after the Reset re-renders from RunInfra's stream;
        // no Groq partial leaks into the final answer text.
        val resetIdx = emitted.indexOfFirst { it is HandleEvent.Reset }
        val afterReset = emitted.drop(resetIdx + 1).filterIsInstance<HandleEvent.Partial>()
        assertTrue(afterReset.isNotEmpty())
        assertFalse(afterReset.any { it.accumulatedText.startsWith("meia") })
    }

    // --- (d) whole chain fails → Exhausted, no answer ----------------------

    @Test
    fun wholeChainFails_exhausted_noAnswer() = runTest {
        val groq = FakeProvider("groq", "q") {
            failedPreToken(ProviderModelId.of("groq", "q"), ChatErrorKind.NETWORK)
        }
        val runinfra = FakeProvider("runinfra", "q") {
            failedPreToken(ProviderModelId.of("runinfra", "q"), ChatErrorKind.SERVER_ERROR)
        }
        val gemini = FakeProvider(GEMINI, "flash", multimodal = true) {
            failedPreToken(ProviderModelId.of(GEMINI, "flash"), ChatErrorKind.SERVER_ERROR)
        }
        val router = ChatProviderRouter(listOf(groq, runinfra, gemini), breaker())

        val (outcome, emitted) = consume(router)

        assertTrue(outcome is ChatOrchestrator.Companion.RouterOutcome.Exhausted)
        val exhausted = outcome as ChatOrchestrator.Companion.RouterOutcome.Exhausted
        // The failure is carried through so `handle` can persist a durable row.
        assertTrue(exhausted.failure != null)
        // No answer produced anywhere in the emitted surface.
        assertEquals(0, emitted.count { it is HandleEvent.Done })
        assertEquals(0, emitted.count { it is HandleEvent.Error })
    }

    // --- extra: Gemini-cascade Completed must NOT clobber a resolved model --

    @Test
    fun geminiCompleted_doesNotOverwriteResolvedModel() = runTest {
        // The Gemini cascade resolves its concrete model via onModelResolved
        // (passed as resolvedModelSoFar). A subsequent Gemini Completed carrying
        // only the default id must not overwrite the concrete model.
        val gemini = FakeProvider(GEMINI, "gemini-3.6-flash") {
            completedFor("$GEMINI:gemini-3.6-flash", "G")
        }
        val router = ChatProviderRouter(listOf(gemini), breaker())

        val (outcome, _) = consume(router, resolvedModelSoFar = "$GEMINI:gemini-3.5-flash")

        val answer = outcome as ChatOrchestrator.Companion.RouterOutcome.Answer
        // Concrete model resolved earlier is preserved (not clobbered by the
        // Completed event's provider id), because the provider IS gemini.
        assertEquals("$GEMINI:gemini-3.5-flash", answer.resolvedModel)
    }

    @Test
    fun nonGeminiCompleted_overwritesEvenIfModelWasPreset() = runTest {
        // A non-Gemini link's model resolves only at Completed, so it must win
        // even if resolvedModelSoFar was pre-seeded.
        val groq = FakeProvider("groq", "q") { completedFor("groq:q", "R") }
        val router = ChatProviderRouter(listOf(groq), breaker())

        val (outcome, _) = consume(router, resolvedModelSoFar = "$GEMINI:gemini-3.5-flash")

        val answer = outcome as ChatOrchestrator.Companion.RouterOutcome.Answer
        assertEquals("groq:q", answer.resolvedModel)
    }

    // --- 2026-10-06 (Frente Thinking) --------------------------------------

    @Test
    fun thinking_events_map_to_handle_thinking_and_stay_out_of_answer() = runTest {
        val groqId = ProviderModelId.of("groq", "q")
        val groq = FakeProvider("groq", "q") {
            flow {
                emit(ChatEvent.Thinking("a pensar…", "a pensar…"))
                emit(ChatEvent.Thinking("a pensar no défice", "a pensar no défice"))
                emit(ChatEvent.Delta("resposta", "resposta"))
                emit(ChatEvent.Completed("resposta", groqId))
            }
        }
        val router = ChatProviderRouter(listOf(groq), breaker())

        val (outcome, events) = consume(router, resolvedModelSoFar = null)

        val answer = outcome as ChatOrchestrator.Companion.RouterOutcome.Answer
        assertEquals("resposta", answer.text)
        // Thinking surfaced, answer text clean.
        val thinking = events.filterIsInstance<com.example.data.chat.HandleEvent.Thinking>()
        assertEquals(2, thinking.size)
        assertEquals("a pensar no défice", thinking[1].text)
        val partials = events.filterIsInstance<com.example.data.chat.HandleEvent.Partial>()
        assertEquals(1, partials.size)
        assertEquals("resposta", partials[0].accumulatedText)
    }

    @Test
    fun thinking_only_turn_is_not_an_answer_even_when_provider_completes_blank() = runTest {
        // A Completed with blank text is treated as no-answer by the seam;
        // thinking alone must never fabricate an Answer.
        val groqId = ProviderModelId.of("groq", "q")
        val groq = FakeProvider("groq", "q") {
            flow {
                emit(ChatEvent.Thinking("a pensar…", "a pensar…"))
                emit(ChatEvent.Completed("", groqId))
            }
        }
        val router = ChatProviderRouter(listOf(groq), breaker())

        val (outcome, _) = consume(router, resolvedModelSoFar = null)
        assertFalse("blank answer + thinking is not an Answer",
            outcome is ChatOrchestrator.Companion.RouterOutcome.Answer)
    }

}