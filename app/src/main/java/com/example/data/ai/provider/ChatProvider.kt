package com.example.data.ai.provider

import kotlinx.coroutines.flow.Flow

/**
 * Provider-neutral Chat contract — the seam the multi-model refactor is
 * built on (Final Multimodel AI Implementation Plan §6).
 *
 * The goal is stated plainly in §25: *"Do not rewrite what already works.
 * Replace only provider-dependent parts."* So this interface is deliberately
 * shaped around the vocabulary the existing chat path already speaks —
 * incremental text deltas, a single terminal "done" with the full text and
 * usage, and a normalized error — rather than inventing a new streaming model
 * the orchestrator would have to be rewritten to consume.
 *
 * A [ChatProvider] is one addressable model behind one transport:
 *   - `gemini:<flash-model>`   (existing Gemini SSE transport)
 *   - `groq:qwen3.8-27b`       (§7)
 *   - `runinfra:qwen3.8-27b`   (§8, text-only)
 *
 * The router (§10, Phase 4) owns the failover *chain* across providers; a
 * single provider only ever speaks for itself. That separation is why
 * [ChatOrchestrator] can stop containing provider-specific HTTP logic (§6):
 * the orchestrator asks the router for a stream, the router walks the chain,
 * and each hop is one of these.
 *
 * ### Hard boundaries (plan §4, "Model access")
 * No provider may write to Room, DAOs, image storage or user-data files. A
 * provider receives an already-built [ChatRequest] and returns a stream of
 * [ChatEvent]; the application remains responsible for every read and write.
 * A provider that is handed image bytes (see [ChatRequest.images]) must treat
 * them as a transient, read-only representation and must never resolve,
 * rewrite, move or delete the underlying file — it never learns the path.
 */
interface ChatProvider {

    /** Stable identity used for the circuit breaker, logs and diagnostics —
     *  e.g. `groq:qwen3.8-27b`. Must be the same string [ProviderModelId]
     *  produces so breaker state keys line up across the codebase. */
    val id: ProviderModelId

    /** What this provider/model can do, so the router can skip it for
     *  requests it cannot serve (e.g. an image-required turn must never be
     *  routed to a text-only provider — §17). */
    val capabilities: ProviderCapabilities

    /**
     * True when the provider has everything it needs to run right now (API
     * key present and non-placeholder, transport constructed). A provider
     * that is not [isAvailable] is skipped by the router rather than invoked
     * and failed — that keeps "not configured" distinct from "the model
     * failed", which the plan calls out as a distinct failure kind (§11).
     */
    fun isAvailable(): Boolean

    /**
     * Stream a reply for [request].
     *
     * Contract, matching the existing Gemini streaming semantics so the
     * orchestrator's collector logic is reused rather than rewritten:
     *   - Emit zero or more [ChatEvent.Delta] as text arrives. `text` is the
     *     NEW fragment; `accumulated` is everything so far (providers that
     *     only know the delta must accumulate themselves before emitting).
     *   - Emit exactly one terminal event and then complete the flow:
     *     [ChatEvent.Completed] on a clean finish (with the full text and,
     *     when the provider reports it, [ChatUsage]), or [ChatEvent.Failed]
     *     with a [ChatError] on any failure — including truncation, which is
     *     a failure the router must be able to fall back on, never a silent
     *     Completed. This mirrors the `finishReason == "STOP"` rule the
     *     Gemini client already enforces.
     *   - Never emit a second terminal event.
     *
     * Failures are surfaced as [ChatEvent.Failed], NOT thrown, so the router
     * can apply its failover policy deterministically without a try/catch per
     * hop. The one exception is cancellation: a
     * [kotlinx.coroutines.CancellationException] MUST propagate so cancelling
     * the collecting coroutine tears down the underlying network call.
     */
    fun stream(request: ChatRequest): Flow<ChatEvent>
}
