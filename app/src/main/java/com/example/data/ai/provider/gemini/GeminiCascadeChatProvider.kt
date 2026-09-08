package com.example.data.ai.provider.gemini

import com.example.data.ai.provider.ChatError
import com.example.data.ai.provider.ChatErrorKind
import com.example.data.ai.provider.ChatEvent
import com.example.data.ai.provider.ChatProvider
import com.example.data.ai.provider.ChatRequest
import com.example.data.ai.provider.ProviderCapabilities
import com.example.data.ai.provider.ProviderModelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The Gemini link of the chat router chain (plan §11 `Groq → RunInfra →
 * Gemini`), Phase 6.
 *
 * ### Why this exists instead of reusing [GeminiChatProvider]
 * [GeminiChatProvider] is a THIN mapper straight over
 * [com.example.data.remote.GeminiStreamingClient] — it deliberately does NOT
 * re-implement the orchestrator-level resilience machinery. That machinery is
 * substantial and load-bearing (memories `chat-latency-fronts-2026-07-06`,
 * `gemini-stream-truncation-finishreason-2026-07-05`):
 *   - the multi-flash **fleet cascade** (`streamModelFleet`: 3.6 → 3.5 → 3.7 →
 *     lite, image-turn exclusions and attempt caps),
 *   - the **TTFT / idle stream watchdog**,
 *   - the **context-cache-rejection retry** (strip `cachedContent`, re-issue with
 *     the full system instruction),
 *   - the **thinking-config-rejection retry** (drop `thinkingConfig`, re-issue),
 *   - the **90 s outer wall-clock budget**,
 *   - the per-model **circuit breaker** bookkeeping and the `onModelResolved`
 *     callback that records which model actually answered.
 *
 * All of that lives in `ChatOrchestrator.streamReply`, NOT in
 * [GeminiChatProvider]. If the router consumed the thin mapper, the Gemini link
 * would silently lose every one of those behaviours — a real regression. So the
 * router's Gemini link is instead backed by the **existing** `streamReply`
 * engine, exposed here as an injected [engine] lambda. `streamReply` /
 * `streamModelFleet` are unchanged; this class only relabels their
 * partial/return/throw surface as neutral [ChatEvent]s so the router treats
 * Gemini exactly like any other provider.
 *
 * ### Contract mapping (matches [GroqChatProvider] / [RunInfraChatProvider])
 *   - each partial from the engine → [ChatEvent.Delta] (`text` == `accumulated`
 *     because `streamReply.onPartial` already hands over the cumulative text);
 *   - engine returns a non-blank final answer → one [ChatEvent.Completed]
 *     carrying the resolved `provider:model` id (from the engine's
 *     `onModelResolved`, so the badge stays correct);
 *   - engine returns blank → [ChatEvent.Failed] `INVALID_RESPONSE` (the router
 *     fails over rather than committing an empty answer);
 *   - engine throws (incl. the 90 s `TimeoutCancellationException`, which is a
 *     [CancellationException] subclass but is a *recoverable* timeout here) →
 *     [ChatEvent.Failed] with a classified kind. Genuine outer cancellation is
 *     rethrown so structured concurrency is honoured.
 *
 * This provider is the chain's **last resort**: it declares itself multimodal so
 * an image turn that skips the text-only Groq/RunInfra links still lands here.
 *
 * @param defaultModel the model id passed to the engine as the cascade's first
 *   attempt (the engine may fail over within the Gemini fleet). Used for the
 *   provider id before a concrete model resolves.
 * @param apiKeyProvider resolves the Gemini key lazily; blank / placeholder ⇒
 *   [isAvailable] false so the router skips (not fails) this link.
 * @param engine the orchestrator seam: runs `streamReply` and reports partials,
 *   the first token, and the resolved model via the callbacks; returns the final
 *   answer or throws. Injected so this class stays free of orchestrator state
 *   and is unit-testable with a fake.
 */
class GeminiCascadeChatProvider(
    private val defaultModel: String,
    private val apiKeyProvider: () -> String,
    private val engine: suspend (Callbacks) -> String,
) : ChatProvider {

    /** The callback surface the [engine] drives while streaming. Mirrors the
     *  subset of `streamReply`'s parameters the router cares about. */
    interface Callbacks {
        /** Cumulative text so far (matches `streamReply.onPartial`). */
        suspend fun onPartial(accumulated: String)
        /** Fired once, on the first token (matches `streamReply.onFirstToken`). */
        suspend fun onFirstToken()
        /** The concrete model that produced the answer, for the badge/log
         *  (matches `streamReply.onModelResolved`). */
        suspend fun onModelResolved(model: String)
    }

    override val id: ProviderModelId = ProviderModelId.of(PROVIDER, defaultModel)

    /** Last-resort chat provider AND the vision fallback — accepts images. */
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        textInput = true,
        multimodalInput = true,
        streaming = true,
        structuredOutput = true,
    )

    override fun isAvailable(): Boolean {
        val key = runCatching { apiKeyProvider() }.getOrNull().orEmpty()
        return key.isNotBlank() && key != PLACEHOLDER_KEY
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        // The engine reports the concrete model that answered; hold it so the
        // Completed event names the real model, not just the default.
        var resolvedModelId: ProviderModelId = id
        var sawFirstToken = false

        val callbacks = object : Callbacks {
            override suspend fun onPartial(accumulated: String) {
                if (!sawFirstToken) sawFirstToken = true
                emit(ChatEvent.Delta(text = accumulated, accumulated = accumulated))
            }

            override suspend fun onFirstToken() {
                // streamReply already gates this to fire once; nothing extra to
                // do here beyond letting the orchestrator's latch close. The
                // router doesn't need a distinct signal — the first Delta is it.
            }

            override suspend fun onModelResolved(model: String) {
                resolvedModelId = ProviderModelId.of(PROVIDER, model)
            }
        }

        val answer: String = try {
            engine(callbacks)
        } catch (ce: CancellationException) {
            // The engine's 90 s budget surfaces as TimeoutCancellationException
            // (a CancellationException subclass) — that is a RECOVERABLE timeout
            // the router should fail over on, not a teardown. Distinguish it
            // from genuine outer cancellation (session switch / ✕ / scope death),
            // which must propagate to honour structured concurrency (§8, §13).
            val isTimeout = ce is kotlinx.coroutines.TimeoutCancellationException
            if (!isTimeout) throw ce
            emit(
                ChatEvent.Failed(
                    ChatError(
                        kind = ChatErrorKind.TIMEOUT,
                        message = ce.message?.take(300) ?: "Gemini cascade timed out",
                        providerModel = resolvedModelId,
                        cause = ce,
                    )
                )
            )
            return@flow
        } catch (t: Throwable) {
            emit(ChatEvent.Failed(classify(t, resolvedModelId)))
            return@flow
        }

        if (answer.isBlank()) {
            emit(
                ChatEvent.Failed(
                    ChatError(
                        kind = ChatErrorKind.INVALID_RESPONSE,
                        message = "Gemini cascade returned a blank answer",
                        providerModel = resolvedModelId,
                    )
                )
            )
            return@flow
        }

        emit(ChatEvent.Completed(fullText = answer, providerModel = resolvedModelId))
    }

    private fun classify(t: Throwable, providerModel: ProviderModelId): ChatError {
        val msg = t.message.orEmpty()
        val kind = when {
            t is java.net.SocketTimeoutException ||
                t is java.util.concurrent.TimeoutException ||
                "timeout" in msg.lowercase() -> ChatErrorKind.TIMEOUT
            t is java.net.UnknownHostException ||
                t is java.net.ConnectException ||
                t is java.io.IOException -> ChatErrorKind.NETWORK
            "truncat" in msg || "finishReason" in msg || "finish_reason" in msg -> ChatErrorKind.INVALID_RESPONSE
            else -> ChatErrorKind.UNKNOWN
        }
        return ChatError(
            kind = kind,
            message = msg.take(300).ifBlank { t.javaClass.simpleName },
            providerModel = providerModel,
            cause = t,
        )
    }

    companion object {
        const val PROVIDER = "gemini"
        /** The .env placeholder — treated as "not configured" (env-key-name-drift
         *  trap: a placeholder is not a usable key). */
        private const val PLACEHOLDER_KEY = "MY_GEMINI_API_KEY"
    }
}
